import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { DeepLEngine, fingerprintKey } from '../../src/engine/deepl/engine';
import { ConfigError, UnsupportedLanguageError } from '../../src/core/errors';
import { createDefaultRegistry } from '../../src/engine/registry';
import {
  EngineRouter,
  isFallbackEligible,
  parseFallbacks,
  parseRoutes,
} from '../../src/engine/routing';
import {
  EncryptedFileSecretStore,
  EnvironmentFirstSecretStore,
} from '../../src/security/secretStore';
import { CancelledError } from '../../src/core/errors';
import { loadConfig } from '../../src/config/index';
import { silentLogger } from '../../src/core/logger';

const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-deepl-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((d) => fs.rm(d, { recursive: true, force: true })));
});

describe('DeepLEngine configuration', () => {
  it('reports itself unconfigured without a key', () => {
    const engine = new DeepLEngine();
    assert.equal(engine.isConfigured(), false);
    assert.equal(engine.keyFingerprint(), undefined);
  });

  it('accepts a plausible key', () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    assert.equal(engine.isConfigured(), true);
    assert.ok(engine.keyFingerprint());
  });

  it('rejects an obviously malformed key at construction', () => {
    assert.throws(() => new DeepLEngine({ apiKey: 'short' }), ConfigError);
  });

  it('never exposes the key in describe()', () => {
    const key = 'abc123def456:fx';
    const described = JSON.stringify(new DeepLEngine({ apiKey: key }).describe());
    assert.ok(!described.includes(key), 'describe() must not contain the key');
    assert.ok(described.includes('"configured":true'));
  });

  it('fingerprint is stable, short, and not the key', () => {
    const key = 'abc123def456:fx';
    assert.equal(fingerprintKey(key), fingerprintKey(key));
    assert.equal(fingerprintKey(key).length, 12);
    assert.ok(!key.includes(fingerprintKey(key)));
  });
});

describe('DeepLEngine limits and language support', () => {
  it('exposes a large per-request limit, well under the 128 KiB cap', () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    assert.ok(engine.limits.maxCharsPerRequest > 1000);
    assert.ok(engine.limits.maxCharsPerRequest <= 128 * 1024 - 2048);
  });

  it('supports the priority pairs', () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    for (const source of ['en', 'ja', 'zh', 'ko']) {
      assert.equal(engine.supportsPair(source, 'ar'), true, `${source} -> ar`);
    }
  });

  it('rejects auto and identical pairs', () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    assert.equal(engine.supportsPair('auto', 'ar'), false);
    assert.equal(engine.supportsPair('en', 'en'), false);
  });

  it('advertises Arabic as a target', () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    assert.ok(engine.getTargetLanguages().some((l) => l.code === 'ar'));
  });
});

describe('DeepLEngine without a key', () => {
  it('raises a clear configuration error instead of faking a translation', async () => {
    const engine = new DeepLEngine();
    await assert.rejects(
      () => engine.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' }),
      (error: Error) => {
        assert.ok(error instanceof ConfigError);
        assert.match(error.message, /DEEPL_API_KEY/);
        return true;
      },
    );
  });

  it('reports unhealthy with a reason in healthCheck', async () => {
    const health = await new DeepLEngine().healthCheck();
    assert.equal(health.healthy, false);
    assert.match(health.detail ?? '', /not configured/);
  });

  it('rejects an unsupported target language', async () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' });
    await assert.rejects(
      () => engine.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'xx' }),
      UnsupportedLanguageError,
    );
  });

  it('rejects text over the engine limit', async () => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx', maxCharsPerRequest: 100 });
    await assert.rejects(
      () => engine.translate({ text: 'x'.repeat(200), sourceLanguage: 'en', targetLanguage: 'ar' }),
      /exceeds the DeepL engine limit/,
    );
  });
});

describe('DeepLEngine API error mapping', () => {
  const mapError = (status: number, body?: string): { code: string; retryable: boolean; message: string } => {
    const engine = new DeepLEngine({ apiKey: 'abc123def456:fx' }) as unknown as {
      mapError: (error: unknown) => { code: string; retryable: boolean; message: string };
    };
    const { HttpError } = require('../../src/engine/http') as {
      HttpError: new (m: string, s: number, r?: number, b?: string) => Error;
    };
    return engine.mapError(new HttpError('failed', status, undefined, body));
  };

  it('maps 456 to a non-retryable quota error', () => {
    const mapped = mapError(456, JSON.stringify({ message: 'Quota exceeded.' }));
    assert.equal(mapped.code, 'QUOTA_EXCEEDED');
    assert.equal(mapped.retryable, false, 'an exhausted character quota must not be retried');
  });

  it('maps 429 and 529 to a retryable rate limit', () => {
    for (const status of [429, 529]) {
      const mapped = mapError(status, JSON.stringify({ message: 'Too many requests.' }));
      assert.equal(mapped.code, 'RATE_LIMITED', `status ${status}`);
      assert.equal(mapped.retryable, true, `status ${status}`);
    }
  });

  it('maps 403 to a configuration error naming the variable', () => {
    const mapped = mapError(403, JSON.stringify({ message: 'Authorization failed.' }));
    assert.equal(mapped.code, 'CONFIG_ERROR');
    assert.match(mapped.message, /DEEPL_API_KEY/);
  });

  it('maps 400 to a non-retryable engine error', () => {
    const mapped = mapError(400, JSON.stringify({ message: 'Invalid target_lang.' }));
    assert.equal(mapped.retryable, false);
  });

  it('maps 413 to TEXT_TOO_LONG', () => {
    assert.equal(mapError(413, JSON.stringify({ message: 'Request too large.' })).code, 'TEXT_TOO_LONG');
  });

  it('maps 5xx to a retryable engine error', () => {
    for (const status of [500, 502, 503, 504]) {
      const mapped = mapError(status, JSON.stringify({ message: 'Server error' }));
      assert.equal(mapped.retryable, true, `status ${status}`);
    }
  });

  it('keeps the key out of error messages', () => {
    const key = 'abc123def456:fx';
    const engine = new DeepLEngine({ apiKey: key }) as unknown as {
      mapError: (error: unknown) => Error;
    };
    const { HttpError } = require('../../src/engine/http') as {
      HttpError: new (m: string, s: number, r?: number, b?: string) => Error;
    };
    assert.ok(!engine.mapError(new HttpError('failed', 403)).message.includes(key));
  });
});

describe('engine routing', () => {
  it('routes per source language', () => {
    const router = new EngineRouter({
      defaultEngineId: 'mymemory',
      rules: [
        { source: 'ja', engine: 'deepl' },
        { source: 'zh', engine: 'deepl' },
        { source: 'ko', engine: 'deepl' },
      ],
    });
    assert.equal(router.resolve('ja').engine, 'deepl');
    assert.equal(router.resolve('zh').engine, 'deepl');
    assert.equal(router.resolve('ko').engine, 'deepl');
    assert.equal(router.resolve('en').engine, 'mymemory');
    assert.equal(router.resolve('en').reason, 'default');
  });

  it('an explicit engine overrides routing', () => {
    const router = new EngineRouter({
      defaultEngineId: 'mymemory',
      rules: [{ source: 'ja', engine: 'deepl' }],
    });
    assert.equal(router.resolve('ja', 'mymemory').engine, 'mymemory');
    assert.equal(router.resolve('ja', 'mymemory').reason, 'explicit');
  });

  it('matches a wildcard rule', () => {
    const router = new EngineRouter({
      defaultEngineId: 'mymemory',
      rules: [{ source: '*', engine: 'deepl' }],
      fallbackIds: ['mymemory'],
    });
    assert.equal(router.resolve('en').engine, 'deepl');
  });

  it('skips an unavailable engine and reports why', () => {
    const router = new EngineRouter({
      defaultEngineId: 'deepl',
      fallbackIds: ['mymemory'],
      isAvailable: (id) =>
        id === 'deepl' ? { available: false, reason: 'DEEPL_API_KEY is not set' } : { available: true },
    });
    const { decision, skipped } = router.resolveAvailable('ja');
    assert.equal(decision.engine, 'mymemory');
    assert.deepEqual(skipped, [{ engine: 'deepl', reason: 'DEEPL_API_KEY is not set' }]);
  });

  it('falls back through an ordered chain, skipping unusable entries', () => {
    const unavailable = new Set(['broken', 'deepl']);
    const router = new EngineRouter({
      defaultEngineId: 'broken',
      fallbackIds: ['deepl', 'mymemory'],
      isAvailable: (id) =>
        unavailable.has(id) ? { available: false, reason: `${id} unavailable` } : { available: true },
    });
    const { decision, skipped } = router.resolveAvailable('en');
    assert.equal(decision.engine, 'mymemory');
    assert.deepEqual(decision.fallbacks, []);
    assert.deepEqual(skipped.map((s) => s.engine), ['broken', 'deepl']);
  });

  it('never falls back for caller errors or cancellation', () => {
    assert.equal(isFallbackEligible(new CancelledError()), false);
    assert.equal(isFallbackEligible(new (class extends Error {})()), false);
  });

  it('parses route and fallback configuration', () => {
    assert.deepEqual(parseRoutes('ja=deepl, zh=deepl'), [
      { source: 'ja', engine: 'deepl' },
      { source: 'zh', engine: 'deepl' },
    ]);
    assert.deepEqual(parseRoutes(''), []);
    assert.deepEqual(parseFallbacks('mymemory, echo '), ['mymemory', 'echo']);
  });

  it('rejects a malformed route entry with a helpful message', () => {
    assert.throws(() => parseRoutes('justane'), /source=engine/);
    assert.throws(() => parseRoutes('=deepl'), /empty source or engine/);
  });

  it('never repeats the default engine as its own fallback', () => {
    const router = new EngineRouter({ defaultEngineId: 'mymemory', fallbackIds: ['mymemory'] });
    assert.deepEqual(router.describe().fallbacks, []);
  });
});

describe('secret store', () => {
  it('round-trips a secret through encrypted storage', async () => {
    const store = new EncryptedFileSecretStore(await tempDir());
    await store.set('deepl', 'abc123def456:fx');
    assert.equal(await store.get('deepl'), 'abc123def456:fx');
  });

  it('stores nothing readable in the vault file', async () => {
    const dir = await tempDir();
    const store = new EncryptedFileSecretStore(dir);
    await store.set('deepl', 'abc123def456:fx');
    const vault = await fs.readFile(store.path(), 'utf8');
    assert.ok(!vault.includes('abc123def456'), 'the key must not appear in the vault file');
    assert.ok(!vault.includes('fx'));
  });

  it('writes files with 0600 permissions', async () => {
    const dir = await tempDir();
    const store = new EncryptedFileSecretStore(dir);
    await store.set('deepl', 'abc123def456:fx');
    const stats = await fs.stat(store.path());
    assert.equal(stats.mode & 0o777, 0o600);
  });

  it('stores outside the project directory', async () => {
    const store = new EncryptedFileSecretStore();
    assert.ok(!store.path().includes(`${path.sep}translator${path.sep}`));
  });

  it('reports status without revealing the value', async () => {
    const store = new EncryptedFileSecretStore(await tempDir());
    await store.set('deepl', 'abc123def456:fx');
    const status = await store.status('deepl');
    assert.equal(status.configured, true);
    assert.equal(status.source, 'vault');
    assert.ok(status.fingerprint);
    assert.ok(!JSON.stringify(status).includes('abc123'));
  });

  it('reports not configured for an unknown engine', async () => {
    const store = new EncryptedFileSecretStore(await tempDir());
    const status = await store.status('deepl');
    assert.equal(status.configured, false);
    assert.equal(status.source, 'none');
  });

  it('deletes a secret', async () => {
    const store = new EncryptedFileSecretStore(await tempDir());
    await store.set('deepl', 'abc123def456:fx');
    assert.equal(await store.delete('deepl'), true);
    assert.equal(await store.get('deepl'), undefined);
    assert.equal(await store.delete('deepl'), false);
  });

  it('returns undefined when the vault cannot be decrypted', async () => {
    const dir = await tempDir();
    const store = new EncryptedFileSecretStore(dir);
    await store.set('deepl', 'abc123def456:fx');
    // Simulate a replaced key file.
    await fs.writeFile(path.join(dir, 'secrets.key'), JSON.stringify({ version: 1, salt: 'AAAAAAAAAAAAAAAAAAAAAA==', wrappedKey: 'AAAA', wrapIv: 'AAAAAAAAAAAAAAAA', wrapTag: 'AAAAAAAAAAAAAAAA' }));
    const reopened = new EncryptedFileSecretStore(dir);
    assert.equal(await reopened.get('deepl'), undefined, 'a failed decrypt must not throw or leak');
  });

  it('environment variables take precedence over the vault', async () => {
    const store = new EnvironmentFirstSecretStore({
      vault: new EncryptedFileSecretStore(await tempDir()),
      env: { DEEPL_API_KEY: 'fromenvironment123' },
    });
    assert.equal(await store.get('deepl'), 'fromenvironment123');
    const status = await store.status('deepl');
    assert.equal(status.source, 'environment');
    assert.ok(!JSON.stringify(status).includes('fromenvironment123'));
  });

  it('falls back to the vault when the environment is empty', async () => {
    const vault = new EncryptedFileSecretStore(await tempDir());
    await vault.set('deepl', 'fromvault123456');
    const store = new EnvironmentFirstSecretStore({ vault, env: {} });
    assert.equal(await store.get('deepl'), 'fromvault123456');
  });

  it('maps an engine id to its environment variable', () => {
    assert.equal(EnvironmentFirstSecretStore.envVarFor('deepl'), 'DEEPL_API_KEY');
    assert.equal(EnvironmentFirstSecretStore.envVarFor('my-engine'), 'MY_ENGINE_API_KEY');
  });
});

describe('DeepL without a key through the full stack', () => {
  it('a translation request fails with a clear configuration error, not a fake result', async () => {
    const { createTranslator } = await import('../../src/translator/translator');
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: os.tmpdir(),
        // Explicitly selects DeepL with no key and no fallback engine.
        env: { TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: '', LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
      }),
      logger: silentLogger,
      disableCache: true,
    });
    await assert.rejects(
      () => translator.translate({ text: 'Are you serious?', sourceLanguage: 'en', targetLanguage: 'ar' }),
      (error: Error) => {
        assert.match(error.message, /DEEPL_API_KEY/);
        assert.match(error.message, /No translation was attempted/);
        return true;
      },
    );
  });

  it('construction succeeds so the platform can report the state instead of crashing', async () => {
    const { createTranslator } = await import('../../src/translator/translator');
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: os.tmpdir(),
        env: { TRANSLATION_ENGINE: 'deepl', LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
      }),
      logger: silentLogger,
      disableCache: true,
    });
    const deepl = translator.describeEngines().find((e) => e.id === 'deepl');
    assert.equal(deepl?.available, false);
    assert.match(deepl?.reason ?? '', /DEEPL_API_KEY/);
  });
});

describe('registry exposes both engines', () => {
  it('registers deepl and mymemory', () => {
    const registry = createDefaultRegistry({ deepl: {}, mymemory: {} });
    assert.equal(registry.has('deepl'), true);
    assert.equal(registry.has('mymemory'), true);
  });

  it('creates a usable DeepL engine with no key that reports unconfigured', () => {
    const registry = createDefaultRegistry({ deepl: {} });
    const engine = registry.create('deepl') as DeepLEngine;
    assert.equal(engine.id, 'deepl');
    assert.equal(engine.isConfigured(), false);
  });
});