import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { EngineRegistry } from '../../src/engine/registry';
import { createTranslator } from '../../src/translator/translator';
import { MemoryCache } from '../../src/cache/memoryCache';
import { silentLogger } from '../../src/core/logger';
import { isTranslationError } from '../../src/core/errors';
import { containsArabic } from '../../src/arabic/arabic';
import { EncryptedFileSecretStore } from '../../src/security/secretStore';
import { ScriptedEngine, UnconfiguredEngine } from '../helpers/scriptedEngine';

const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-deepl-int-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((d) => fs.rm(d, { recursive: true, force: true })));
});

interface Harness {
  translator: ReturnType<typeof createTranslator>;
  primary: ScriptedEngine;
  secondary: ScriptedEngine;
}

/**
 * Two engines standing in for DeepL (primary) and MyMemory (fallback), so
 * routing and fallback can be proven without network access or an API key.
 */
async function twoEngineHarness(
  env: Record<string, string>,
  primaryBehaviour: ConstructorParameters<typeof ScriptedEngine>[0] = {},
  primaryEngine?: ScriptedEngine,
): Promise<Harness> {
  const primary = primaryEngine ?? new ScriptedEngine({ respond: () => 'ع:primary', ...primaryBehaviour });
  const secondary = new ScriptedEngine({ respond: () => 'ع:secondary' });
  const dir = await tempDir();
  const config = loadConfig({
    readDotEnv: false,
    rootDir: dir,
    env: {
      LOG_LEVEL: 'silent',
      RETRY_MAX_ATTEMPTS: '2',
      RETRY_BASE_DELAY_MS: '1',
      RETRY_MAX_DELAY_MS: '2',
      RETRY_JITTER_RATIO: '0',
      REQUEST_TIMEOUT_MS: '3000',
      ...env,
    },
  });
  const registry = new EngineRegistry()
    .register('deepl', () => primary as never)
    .register('mymemory', () => secondary as never);
  const translator = createTranslator({
    config,
    logger: silentLogger,
    registry,
    cache: new MemoryCache({ defaultTtlMs: 60_000, maxEntries: 200 }),
  });
  return { translator, primary, secondary };
}

describe('engine selection by configuration', () => {
  it('defaults to mymemory when no DeepL key is present', async () => {
    const { translator } = await twoEngineHarness({ TRANSLATION_ENGINE: 'mymemory' });
    assert.equal(translator.engine, 'mymemory');
  });

  it('defaults to deepl when a DeepL key is present in the environment', async () => {
    const dir = await tempDir();
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: { DEEPL_API_KEY: 'fake-key-for-config-test', LOG_LEVEL: 'silent' },
    });
    assert.equal(config.engine.engine, 'deepl');
    assert.equal(config.engine.fallbacks.includes('mymemory'), true);
    assert.equal(config.engine.routes.length, 0);
  });

  it('an explicit TRANSLATION_ENGINE overrides the key-based default', async () => {
    const dir = await tempDir();
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: { DEEPL_API_KEY: 'fake-key-for-config-test', TRANSLATION_ENGINE: 'mymemory', LOG_LEVEL: 'silent' },
    });
    assert.equal(config.engine.engine, 'mymemory');
  });

  it('reads routes and fallbacks from configuration', async () => {
    const { translator } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'deepl',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl,zh=deepl,ko=deepl',
      TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
    });
    const routing = translator.describeRouting();
    assert.equal(routing.default, 'deepl');
    assert.equal(routing.rules.length, 3);
    assert.deepEqual(routing.fallbacks, ['mymemory']);
  });
});

describe('language-based routing', () => {
  it('sends Japanese to DeepL and English to MyMemory', async () => {
    const { translator, primary, secondary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });

    const ja = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(ja.engine, 'deepl');
    assert.equal(ja.text, 'ع:primary');
    assert.equal(primary.callCount, 1);

    const en = await translator.translate({ text: 'Are you serious?', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(en.engine, 'mymemory');
    assert.equal(secondary.callCount, 1);
  });

  it('routes by detected language when the source is auto', async () => {
    const { translator, primary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'zh=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    const zh = await translator.translate({ text: '你以为我是谁？', targetLanguage: 'ar' });
    assert.equal(zh.detectedLanguage, 'zh');
    assert.equal(zh.engine, 'deepl');
    assert.equal(primary.callCount, 1);
  });

  it('an explicit per-request engine overrides routing', async () => {
    const { translator, primary, secondary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    const result = await translator.translate({
      text: '本気なのか？',
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      engine: 'mymemory',
    });
    assert.equal(result.engine, 'mymemory');
    assert.equal(primary.callCount, 0);
    assert.equal(secondary.callCount, 1);
  });

  it('resolveEngineFor reports the decision without translating', async () => {
    const { translator, primary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ko=deepl',
      TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
    });
    const decision = translator.resolveEngineFor('ko');
    assert.equal(decision.engine, 'deepl');
    assert.equal(decision.reason, 'route');
    assert.deepEqual(decision.fallbacks, ['mymemory']);
    assert.equal(primary.callCount, 0, 'resolving must not call an engine');
  });
});

describe('fallback behaviour', () => {
  it('falls back to the secondary engine when the primary is unavailable', async () => {
    const { translator, primary, secondary } = await twoEngineHarness(
      {
        TRANSLATION_ENGINE: 'deepl',
        TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
        RETRY_MAX_ATTEMPTS: '1',
      },
      { failTimes: Number.MAX_SAFE_INTEGER },
    );
    const result = await translator.translate({ text: '本気なのか？', sourceLanguage: 'ja', targetLanguage: 'ar' });
    assert.equal(result.engine, 'mymemory', 'the result must report the engine that produced it');
    assert.equal(result.text, 'ع:secondary');
    assert.ok(primary.callCount >= 1, 'the primary must have been attempted');
    assert.equal(secondary.callCount, 1);
  });

  it('falls back on a quota wall', async () => {
    const { translator, secondary } = await twoEngineHarness(
      {
        TRANSLATION_ENGINE: 'deepl',
        TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
        RETRY_MAX_ATTEMPTS: '1',
      },
      {
        failTimes: Number.MAX_SAFE_INTEGER,
        error: () => {
          const error = new Error('quota exhausted') as Error & { code: string; retryable: boolean };
          error.code = 'QUOTA_EXCEEDED';
          error.retryable = false;
          return error;
        },
      },
    );
    const result = await translator.translate({ text: '本気なのか？', sourceLanguage: 'ja', targetLanguage: 'ar' });
    assert.equal(result.engine, 'mymemory');
    assert.equal(secondary.callCount, 1);
  });

  it('does not fall back for validation errors', async () => {
    const { translator, secondary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'deepl',
      TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
    });
    await assert.rejects(() =>
      translator.translate({ text: '   ', targetLanguage: 'ar' }),
    );
    assert.equal(secondary.callCount, 0, 'a validation error must not reach a fallback engine');
  });

  it('reports the original error when there is no fallback', async () => {
    const { translator } = await twoEngineHarness(
      { TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: '', RETRY_MAX_ATTEMPTS: '1' },
      { failTimes: Number.MAX_SAFE_INTEGER },
    );
    await assert.rejects(
      () => translator.translate({ text: '本気なのか？', sourceLanguage: 'ja', targetLanguage: 'ar' }),
      (error: unknown) => isTranslationError(error),
    );
  });
});

describe('cache safety across engine changes', () => {
  it('stores results under the engine that produced them', async () => {
    const { translator } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    const viaMymemory = await translator.translate({
      text: '本気なのか？',
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      engine: 'mymemory',
    });
    const viaDeepl = await translator.translate({
      text: '本気なのか？',
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      engine: 'deepl',
    });
    assert.equal(viaMymemory.text, 'ع:secondary');
    assert.equal(viaDeepl.text, 'ع:primary');
    assert.equal(viaDeepl.fromCache, false, 'a different engine must not reuse the entry');
  });

  it('does not re-translate when the routing decision is unchanged', async () => {
    const { translator, primary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    const first = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    const second = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(first.fromCache, false);
    assert.equal(second.fromCache, true);
    assert.equal(primary.callCount, 1, 'the engine must be called once');
  });

  it('a fallback result is cached under the fallback engine, not the failed one', async () => {
    const primary = new ScriptedEngine({ failTimes: Number.MAX_SAFE_INTEGER });
    const secondary = new ScriptedEngine({ respond: () => 'ع:secondary' });
    const dir = await tempDir();
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: {
        LOG_LEVEL: 'silent',
        CACHE_ENABLED: 'true',
        TRANSLATION_ENGINE: 'mymemory',
        TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
        TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
        RETRY_MAX_ATTEMPTS: '1',
        // Breaker opens immediately so the failed engine is not retried per call.
        ENGINE_FAILURE_THRESHOLD: '1',
        ENGINE_COOLDOWN_MS: '60000',
      },
    });
    const translator = createTranslator({
      config,
      logger: silentLogger,
      registry: new EngineRegistry()
        .register('deepl', () => primary as never)
        .register('mymemory', () => secondary as never),
      cache: new MemoryCache({ defaultTtlMs: 60_000, maxEntries: 100 }),
    });

    const first = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(first.engine, 'mymemory', 'the result must be attributed to the engine that served it');
    assert.equal(first.fromCache, false);
    assert.ok(primary.callCount >= 1, 'the routed engine was attempted first');
    assert.equal(secondary.callCount, 1);

    // The breaker is now open for deepl, so the next request goes straight to
    // the fallback without paying for the dead engine again.
    const second = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(second.engine, 'mymemory');
    assert.equal(primary.callCount, 1, 'a failing engine must not be retried on every request');
    const breakers = translator.describeBreakers();
    assert.equal(breakers.find((b) => b.engine === 'deepl')?.coolingDown, true);
  });

  it('reuses the cached fallback result on a repeat request', async () => {
    const primary = new ScriptedEngine({ failTimes: Number.MAX_SAFE_INTEGER });
    const secondary = new ScriptedEngine({ respond: () => 'ع:secondary' });
    const dir = await tempDir();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: {
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'true',
          TRANSLATION_ENGINE: 'mymemory',
          TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
          TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
          RETRY_MAX_ATTEMPTS: '1',
          ENGINE_FAILURE_THRESHOLD: '1',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry()
        .register('deepl', () => primary as never)
        .register('mymemory', () => secondary as never),
      cache: new MemoryCache({ defaultTtlMs: 60_000, maxEntries: 100 }),
    });

    await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    const repeat = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(repeat.fromCache, true, 'the successful translation is cached');
    assert.equal(repeat.engine, 'mymemory');
    assert.equal(secondary.callCount, 1, 'the cache must prevent a second call');
  });
});

describe('DeepL availability without a key', () => {
  it('reports deepl as unavailable and routes to mymemory instead', async () => {
    const primary = new UnconfiguredEngine();
    const { translator, secondary } = await twoEngineHarness(
      { TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: 'mymemory' },
      {},
      primary,
    );
    const engines = translator.describeEngines();
    const deepl = engines.find((e) => e.id === 'deepl');
    assert.equal(deepl?.available, false);
    assert.match(deepl?.reason ?? '', /API key missing/);

    const result = await translator.translate({ text: '本気なのか？', sourceLanguage: 'ja', targetLanguage: 'ar' });
    assert.equal(result.engine, 'mymemory');
    assert.equal(primary.callCount, 0, 'an unconfigured engine must not be called');
    assert.equal(secondary.callCount, 1);
  });

  it('fails clearly when no engine is usable', async () => {
    const { translator } = await twoEngineHarness(
      { TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: '' },
      {},
      new UnconfiguredEngine(),
    );
    await assert.rejects(
      () => translator.translate({ text: '本気なのか？', sourceLanguage: 'ja', targetLanguage: 'ar' }),
      (error: unknown) => {
        assert.ok(isTranslationError(error));
        assert.match(error.message, /API key missing/);
        return true;
      },
    );
  });
});

describe('secret store integration', () => {
  it('a key saved at runtime makes the engine available without a restart', async () => {
    const dir = await tempDir();
    const vault = new EncryptedFileSecretStore(dir);
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
    });
    const translator = createTranslator({
      config,
      logger: silentLogger,
      secretStore: vault,
      disableCache: true,
    });
    assert.equal(translator.describeEngines().find((e) => e.id === 'deepl')?.available, false);

    const status = await translator.saveSecret('deepl', 'runtime-key-abcdef123');
    assert.equal(status.configured, true);
    assert.ok(!JSON.stringify(status).includes('runtime-key-abcdef123'));
    assert.equal(
      translator.describeEngines().find((e) => e.id === 'deepl')?.available,
      true,
      'a saved key must make the engine usable immediately',
    );
  });

  it('loads a key from the vault into memory', async () => {
    const dir = await tempDir();
    const vault = new EncryptedFileSecretStore(dir);
    await vault.set('deepl', 'vault-key-abcdef123');
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
    });
    const translator = createTranslator({
      config,
      logger: silentLogger,
      secretStore: vault,
      disableCache: true,
    });
    const statuses = await translator.loadSecrets();
    const deepl = statuses.find((s) => s.engine === 'deepl');
    assert.equal(deepl?.configured, true);
    assert.equal(deepl?.source, 'vault');
    const engines = translator.describeEngines();
    assert.equal(engines.find((e) => e.id === 'deepl')?.available, true);
  });

  it('deleting a key makes the engine unavailable again', async () => {
    const dir = await tempDir();
    const vault = new EncryptedFileSecretStore(dir);
    await vault.set('deepl', 'vault-key-abcdef123');
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
      }),
      logger: silentLogger,
      secretStore: vault,
      disableCache: true,
    });
    await translator.loadSecrets();
    assert.equal(translator.describeEngines().find((e) => e.id === 'deepl')?.available, true);
    const status = await translator.deleteSecret('deepl');
    assert.equal(status.configured, false);
    assert.equal(translator.describeEngines().find((e) => e.id === 'deepl')?.available, false);
  });

  it('never returns the key through describeSecrets', async () => {
    const dir = await tempDir();
    const vault = new EncryptedFileSecretStore(dir);
    await vault.set('deepl', 'vault-key-abcdef123');
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
      }),
      logger: silentLogger,
      secretStore: vault,
      disableCache: true,
    });
    const secrets = await translator.describeSecrets();
    assert.ok(!JSON.stringify(secrets).includes('vault-key-abcdef123'));
    assert.ok(!JSON.stringify(translator.settings).includes('vault-key-abcdef123'));
  });
});

describe('chapter translation honours routing', () => {
  it('routes each chapter segment independently', async () => {
    const { translator, primary, secondary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    const result = await translator.translateChapter({
      segments: [
        { id: 'j1', text: '本気なのか？' },
        { id: 'e1', text: 'Are you serious?' },
      ],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    // sourceLanguage is pinned to en here, so routing sends both to mymemory.
    assert.equal(secondary.callCount, 2);
    assert.equal(primary.callCount, 0);
    assert.ok(result.segments.every((s) => containsArabic(s.translated)));
  });

  it('auto-detects per segment so mixed-language chapters route correctly', async () => {
    const { translator, primary, secondary } = await twoEngineHarness({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: '',
    });
    await translator.translateChapter({
      segments: [
        { id: 'j1', text: '本気なのか？' },
        { id: 'e1', text: 'Are you serious right now?' },
      ],
      sourceLanguage: 'auto',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    assert.equal(primary.callCount, 1, 'the Japanese segment goes to DeepL');
    assert.equal(secondary.callCount, 1, 'the English segment goes to MyMemory');
  });
});