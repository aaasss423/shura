import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { loadConfig, type AppConfig } from '../../src/config/index';
import { EngineRegistry } from '../../src/engine/registry';
import { createTranslator, Translator } from '../../src/translator/translator';
import { MemoryCache } from '../../src/cache/memoryCache';
import { FileCache } from '../../src/cache/fileCache';
import { TieredCache } from '../../src/cache/tieredCache';
import { CancellationToken } from '../../src/core/cancellation';
import { silentLogger } from '../../src/core/logger';
import { CancelledError, TimeoutError, TranslationError } from '../../src/core/errors';
import { countCharacters, containsArabic } from '../../src/arabic/arabic';
import {
  AlwaysFailingEngine,
  ScriptedEngine,
  SelectiveFailureEngine,
} from '../helpers/scriptedEngine';

const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-int-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((d) => fs.rm(d, { recursive: true, force: true })));
});

interface Harness {
  translator: Translator;
  engine: ScriptedEngine;
  config: AppConfig;
}

function baseConfig(overrides: Record<string, string> = {}): AppConfig {
  return loadConfig({
    readDotEnv: false,
    rootDir: os.tmpdir(),
    env: {
      TRANSLATION_ENGINE: 'scripted',
      LOG_LEVEL: 'silent',
      RETRY_JITTER_RATIO: '0',
      RETRY_BASE_DELAY_MS: '1',
      RETRY_MAX_DELAY_MS: '2',
      REQUEST_TIMEOUT_MS: '3000',
      CHAPTER_DEADLINE_MS: '20000',
      CACHE_ENABLED: 'true',
      CHAPTER_SEGMENT_MAX_CHARS: '120',
      ...overrides,
    },
  });
}

async function harness(options: { engine?: ScriptedEngine; config?: AppConfig; cacheDir?: string } = {}): Promise<Harness> {
  const engine = options.engine ?? new ScriptedEngine();
  const config = options.config ?? baseConfig();
  const dir = options.cacheDir ?? (await tempDir());
  const registry = new EngineRegistry().register('scripted', () => engine);

  const memory = new MemoryCache<unknown>({ defaultTtlMs: config.cache.ttlMs, maxEntries: 500 });
  const file = new FileCache<unknown>({
    directory: dir,
    defaultTtlMs: config.cache.ttlMs,
    maxEntries: 500,
  });

  const translator = createTranslator({
    config,
    logger: silentLogger,
    registry,
    cache: new TieredCache<unknown>({ memory, file, logger: silentLogger }),
  });
  return { translator, engine, config };
}

describe('translation service integration', () => {
  it('translates a short text through the engine', async () => {
    const { translator, engine } = await harness();
    const result = await translator.translate({ text: 'Hello world', targetLanguage: 'ar' });
    assert.equal(result.text, '[tr] Hello world');
    assert.equal(result.engine, 'scripted');
    assert.equal(result.fromCache, false);
    assert.equal(result.segments, 1);
    assert.equal(engine.callCount, 1);
  });

  it('passes source and target languages to the engine', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(engine.calls[0]?.sourceLanguage, 'en');
    assert.equal(engine.calls[0]?.targetLanguage, 'ar');
  });

  it('detects the source language when auto', async () => {
    const { translator, engine } = await harness();
    const result = await translator.translate({ text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(engine.calls[0]?.sourceLanguage, 'ja');
    assert.equal(result.detectedLanguage, 'ja');
  });

  // Regression: Chinese was routed as Japanese because of shared Han code points.
  it('routes Chinese text to the engine as zh, not ja', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: '你以为我是谁？', targetLanguage: 'ar' });
    assert.equal(engine.calls[0]?.sourceLanguage, 'zh');
  });

  it('rejects unsupported language pairs before calling the engine', async () => {
    const { translator, engine } = await harness();
    await assert.rejects(
      () => translator.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'en' }),
      TranslationError,
    );
    assert.equal(engine.callCount, 0);
  });

  it('segments long text and merges the results in order', async () => {
    const engine = new ScriptedEngine({ respond: (r) => `ع:${r.text}` });
    const { translator } = await harness({ engine });
    const longText = Array.from({ length: 20 }, (_, i) => `Line ${i} of the chapter text.`).join('\n\n');
    const result = await translator.translate({ text: longText, targetLanguage: 'ar' });
    assert.ok(result.segments > 1, 'expected multiple segments');
    assert.equal(engine.callCount, result.segments);
    assert.ok(result.text.startsWith('ع:'));
    // Order must be preserved: each merged line keeps its own index.
    const indexOrder = [...result.text.matchAll(/Line (\d+)/g)].map((m) => Number(m[1]));
    assert.deepEqual(indexOrder, [...indexOrder].sort((a, b) => a - b));
  });

  it('respects the engine character limit in segmentation', async () => {
    const { translator, engine } = await harness();
    const long = 'x'.repeat(500);
    await translator.translate({ text: long, targetLanguage: 'ar' });
    for (const call of engine.calls) {
      assert.ok(countCharacters(call.text) <= 120, 'segment exceeded the configured limit');
    }
  });
});

describe('cache behaviour in the service', () => {
  it('serves the second identical request from cache', async () => {
    const { translator, engine } = await harness();
    const first = await translator.translate({ text: 'Cache me please', targetLanguage: 'ar' });
    const second = await translator.translate({ text: 'Cache me please', targetLanguage: 'ar' });
    assert.equal(first.fromCache, false);
    assert.equal(second.fromCache, true);
    assert.equal(second.text, first.text);
    assert.equal(engine.callCount, 1, 'engine must be hit once');
  });

  it('re-translates when noCache is set', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Cache me please', targetLanguage: 'ar' });
    const fresh = await translator.translate({ text: 'Cache me please', targetLanguage: 'ar', noCache: true });
    assert.equal(fresh.fromCache, false);
    assert.equal(engine.callCount, 2);
  });

  it('refresh bypasses the read but still stores', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Refresh me', targetLanguage: 'ar' });
    const refreshed = await translator.translate({ text: 'Refresh me', targetLanguage: 'ar', refresh: true });
    assert.equal(refreshed.fromCache, false);
    assert.equal(engine.callCount, 2);
    const cached = await translator.translate({ text: 'Refresh me', targetLanguage: 'ar' });
    assert.equal(cached.fromCache, true);
  });

  it('treats whitespace variants as the same cache entry', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Same   text', targetLanguage: 'ar' });
    const variant = await translator.translate({ text: '  Same text  ', targetLanguage: 'ar' });
    assert.equal(variant.fromCache, true);
    assert.equal(engine.callCount, 1);
  });

  // Regression: contextBefore was part of the key, splitting entries per position.
  it('does not create separate entries for different contextBefore', async () => {
    const { translator, engine } = await harness();
    const text = 'Shared sentence in a chapter.';
    const a = await translator.translate({ text, targetLanguage: 'ar', contextBefore: 'Earlier panel one.' });
    const b = await translator.translate({ text, targetLanguage: 'ar', contextBefore: 'A completely different panel.' });
    assert.equal(a.fromCache, false);
    assert.equal(b.fromCache, true);
    assert.equal(engine.callCount, 1);
  });

  it('uses different keys for different targets', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Hello', targetLanguage: 'ar' });
    await translator.translate({ text: 'Hello', targetLanguage: 'fr' });
    assert.equal(engine.callCount, 2);
  });

  it('survives a process restart by reloading the file cache', async () => {
    const dir = await tempDir();
    const engineA = new ScriptedEngine({ respond: () => 'ترجمة مخزنة' });
    const first = await harness({ engine: engineA, cacheDir: dir });
    await first.translator.translate({ text: 'Persist across restart', targetLanguage: 'ar' });
    await first.translator.flushCache();
    await first.translator.flushCache();

    // A brand new Translator with a cold in-memory tier must still hit.
    const engineB = new ScriptedEngine({ respond: () => 'ترجمة جديدة' });
    const second = await harness({ engine: engineB, cacheDir: dir });
    const result = await second.translator.translate({ text: 'Persist across restart', targetLanguage: 'ar' });
    assert.equal(result.fromCache, true);
    assert.equal(result.text, 'ترجمة مخزنة');
    assert.equal(engineB.callCount, 0);
  });

  it('clear removes entries so the next call re-translates', async () => {
    const { translator, engine } = await harness();
    await translator.translate({ text: 'Clear me', targetLanguage: 'ar' });
    await translator.clearCache();
    const after = await translator.translate({ text: 'Clear me', targetLanguage: 'ar' });
    assert.equal(after.fromCache, false);
    assert.equal(engine.callCount, 2);
  });

  it('cacheSize reflects stored entries', async () => {
    const { translator } = await harness();
    await translator.translate({ text: 'one', targetLanguage: 'ar' });
    await translator.translate({ text: 'two', targetLanguage: 'ar' });
    assert.ok((await translator.cacheSize()) >= 2);
  });
});

describe('retry in the real service path', () => {
  it('retries a transient engine failure and succeeds', async () => {
    const engine = new ScriptedEngine({
      failTimes: 2,
      respond: () => 'نجح بعد إعادة المحاولة',
    });
    const { translator } = await harness({ engine });
    const result = await translator.translate({ text: 'Retry me', targetLanguage: 'ar' });
    assert.equal(result.text, 'نجح بعد إعادة المحاولة');
    assert.equal(engine.callCount, 3);
  });

  it('gives up after maxAttempts and reports the failure', async () => {
    const engine = new ScriptedEngine({ failTimes: Number.MAX_SAFE_INTEGER });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '2' }) });
    await assert.rejects(() => translator.translate({ text: 'Never works', targetLanguage: 'ar' }));
    assert.equal(engine.callCount, 2);
  });

  it('honours a per-call retries override', async () => {
    const engine = new ScriptedEngine({ failTimes: Number.MAX_SAFE_INTEGER });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '5' }) });
    await assert.rejects(() => translator.translate({ text: 'Never works', targetLanguage: 'ar', retries: 0 }));
    assert.equal(engine.callCount, 1);
  });
});

describe('timeout in the real service path', () => {
  it('rejects with a timeout when the engine hangs', async () => {
    const engine = new ScriptedEngine({ hang: true });
    const { translator } = await harness({ engine, config: baseConfig({ REQUEST_TIMEOUT_MS: '150', RETRY_MAX_ATTEMPTS: '1' }) });
    await assert.rejects(() => translator.translate({ text: 'Hang forever', targetLanguage: 'ar' }), TimeoutError);
  });

  it('applies a service level deadline across a whole request', async () => {
    const engine = new ScriptedEngine({ delayMs: 400 });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    await assert.rejects(
      () => translator.translate({ text: 'Slow text', targetLanguage: 'ar', deadlineMs: 100 }),
      TranslationError,
    );
  });

  it('does not hang forever when the engine never settles', async () => {
    const engine = new ScriptedEngine({ hang: true });
    const { translator } = await harness({ engine, config: baseConfig({ REQUEST_TIMEOUT_MS: '120', RETRY_MAX_ATTEMPTS: '1' }) });
    const started = Date.now();
    await assert.rejects(() => translator.translate({ text: 'Stuck', targetLanguage: 'ar' }));
    assert.ok(Date.now() - started < 3000, 'must not wait indefinitely');
  });
});

describe('cancellation in the real service path', () => {
  it('rejects when the token is already cancelled (pre-flight)', async () => {
    const { translator, engine } = await harness();
    const token = new CancellationToken();
    token.cancel();
    await assert.rejects(
      () => translator.translate({ text: 'Hello', targetLanguage: 'ar', token }),
      CancelledError,
    );
    assert.equal(engine.callCount, 0, 'engine must not be called at all');
  });

  it('cancels an in-flight engine request', async () => {
    const engine = new ScriptedEngine({ delayMs: 5000 });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    const token = new CancellationToken();
    const promise = translator.translate({ text: 'Long request', targetLanguage: 'ar', token });
    setTimeout(() => token.cancel(), 40);
    await assert.rejects(() => promise, CancelledError);
    assert.equal(token.signal.aborted, true);
  });

  it('does not write a cache entry for a cancelled request', async () => {
    // Slow only the first call, so the follow-up request proves the cache state
    // rather than timing out again.
    const engine = new ScriptedEngine({
      respond: (_request, callIndex) => {
        if (callIndex === 0) {
          return new Promise<string>(() => undefined);
        }
        return 'مترجم بعد الإلغاء';
      },
    });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    const token = new CancellationToken();
    const promise = translator.translate({ text: 'Cancelled text', targetLanguage: 'ar', token });
    setTimeout(() => token.cancel(), 30);
    await assert.rejects(() => promise, CancelledError);

    const second = await translator.translate({ text: 'Cancelled text', targetLanguage: 'ar' });
    assert.equal(second.fromCache, false, 'cancelled work must not be cached');
    assert.equal(second.text, 'مترجم بعد الإلغاء');
  });
});

describe('chapter translation integration', () => {
  const segments = [
    { id: 'p1', text: 'Are you serious right now?!' },
    { id: 'p2', text: "I don't believe what you just said to me." },
    { id: 'p3', text: 'minimum height 165 cm, age 18' },
    { id: 'p4', text: "Don't worry, I'll be fine." },
  ];

  it('translates every segment and preserves order', async () => {
    const engine = new ScriptedEngine({ respond: (r) => `ع:${r.text}` });
    const { translator } = await harness({ engine });
    const result = await translator.translateChapter({ segments, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(result.segments.length, 4);
    assert.ok(result.degraded === false);
    const order = result.segments.map((s) => s.index);
    assert.deepEqual(order, [0, 1, 2, 3]);
    assert.ok(result.text.includes('Are you serious right now?!'));
  });

  it('reports progress for each completed segment', async () => {
    const engine = new ScriptedEngine({ respond: () => 'مترجم' });
    const { translator } = await harness({ engine });
    const progress: number[] = [];
    await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      onProgress: (p) => progress.push(p.completedSegments),
    });
    assert.ok(progress.length >= 4, `expected progress events, got ${progress.length}`);
    assert.equal(progress[progress.length - 1], 4);
  });

  it('serves cached segments and counts them', async () => {
    const engine = new ScriptedEngine({ respond: () => 'مترجم' });
    const { translator } = await harness({ engine });
    await translator.translateChapter({ segments, sourceLanguage: 'en', targetLanguage: 'ar' });
    const second = await translator.translateChapter({ segments, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(second.progress.cachedSegments, 4);
    assert.equal(engine.callCount, 4, 'engine must not be called again');
  });

  it('falls back to the original text when a segment fails permanently', async () => {
    const engine = new SelectiveFailureEngine(/minimum height/);
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    const result = await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(result.degraded, true);
    assert.equal(result.progress.failedSegments, 1);
    const failed = result.segments[2]!;
    assert.equal(failed.fallback, true);
    assert.equal(failed.translated, 'minimum height 165 cm, age 18');
    assert.ok(failed.error, 'the failure must be reported');
    // The rest of the chapter must survive.
    assert.ok(result.segments[0]!.translated.includes('Are you serious'));
    assert.ok(result.text.includes('Are you serious'));
  });

  it('aborts the whole chapter under the abort failure policy', async () => {
    const engine = new AlwaysFailingEngine();
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    await assert.rejects(() =>
      translator.translateChapter({
        segments,
        sourceLanguage: 'en',
        targetLanguage: 'ar',
        failurePolicy: 'abort',
      }),
    );
  });

  it('retries only the failed segments on demand', async () => {
    let failing = true;
    const engine = new ScriptedEngine({
      respond: (request) => {
        if (failing && /minimum height/.test(request.text)) {
          throw new Error('segment down');
        }
        return `ع:${request.text}`;
      },
    });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });

    const first = await translator.translateChapter({ segments, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(first.progress.failedSegments, 1);
    const goodBefore = first.segments[0]!.translated;
    const callsBefore = engine.callCount;

    failing = false;
    const retried = await translator.retryChapter(first, { segments, sourceLanguage: 'en', targetLanguage: 'ar' });

    assert.equal(retried.progress.failedSegments, 0);
    assert.equal(retried.degraded, false);
    assert.ok(retried.segments[2]!.translated.includes('165'), 'failed segment must now be translated');
    assert.equal(retried.segments[0]!.translated, goodBefore, 'already-good segments must be untouched');
    assert.equal(engine.callCount - callsBefore, 1, 'only the failed segment should be re-requested');
  });

  it('cancels a chapter in flight', async () => {
    const engine = new ScriptedEngine({ delayMs: 3000 });
    const { translator } = await harness({ engine, config: baseConfig({ RETRY_MAX_ATTEMPTS: '1' }) });
    const token = new CancellationToken();
    const promise = translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      token,
      concurrency: 1,
    });
    setTimeout(() => token.cancel(), 40);
    await assert.rejects(() => promise, CancelledError);
  });

  it('splits an over-long chapter segment into sub-segments', async () => {
    const engine = new ScriptedEngine({ respond: (r) => `ع:${r.text}` });
    const { translator } = await harness({ engine });
    const longPanel = Array.from({ length: 25 }, (_, i) => `Word${i}`).join(' ');
    const result = await translator.translateChapter({
      segments: [{ id: 'long', text: longPanel }],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.ok(engine.callCount > 1, 'a long panel must be split across engine calls');
    assert.ok(result.segments[0]!.translated.includes('Word24'), 'tail content must survive');
  });

  it('rejects an empty chapter', async () => {
    const { translator } = await harness();
    await assert.rejects(() =>
      translator.translateChapter({ segments: [], sourceLanguage: 'en', targetLanguage: 'ar' }),
    );
  });

  it('preserves speaker labels', async () => {
    const engine = new ScriptedEngine({ respond: () => 'مرحبا' });
    const { translator } = await harness({ engine });
    const result = await translator.translateChapter({
      segments: [{ id: 'a', text: 'Hello there.', speaker: 'Anna' }],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.ok(result.segments[0]!.translated.startsWith('Anna:'), result.segments[0]!.translated);
  });
});

describe('quality reporting in the service', () => {
  it('flags a Latin-only result for an Arabic target', async () => {
    const engine = new ScriptedEngine({ respond: () => 'This stayed English' });
    const { translator } = await harness({ engine });
    const result = await translator.translate({ text: 'Hello', targetLanguage: 'ar' });
    assert.ok(result.quality);
    assert.equal(result.quality?.ok, false);
    assert.ok(result.quality?.issues.some((i) => i.kind === 'source_target_mixed'));
  });

  it('accepts a proper Arabic result', async () => {
    const engine = new ScriptedEngine({ respond: () => 'من أنت؟' });
    const { translator } = await harness({ engine });
    const result = await translator.translate({ text: 'Who are you?', targetLanguage: 'ar' });
    assert.equal(result.quality?.ok, true);
    assert.ok(containsArabic(result.text));
  });
});

describe('engine switchability through configuration', () => {
  it('switches engines with no code change', async () => {
    const engineA = new ScriptedEngine({ respond: () => 'engine A' });
    const engineB = new ScriptedEngine({ respond: () => 'engine B' });
    const registry = new EngineRegistry()
      .register('scripted', () => engineA)
      .register('alternative', () => engineB);

    const config = baseConfig();
    const translator = createTranslator({ config, logger: silentLogger, registry, disableCache: true });
    assert.equal((await translator.translate({ text: 'x', targetLanguage: 'ar' })).text, 'engine A');
    assert.equal(
      (await translator.translate({ text: 'x', targetLanguage: 'ar', engine: 'alternative' })).text,
      'engine B',
    );
  });

  it('keys the cache per engine so switching engines does not collide', async () => {
    const engineA = new ScriptedEngine({ respond: () => 'engine A' });
    const engineB = new ScriptedEngine({ respond: () => 'engine B' });
    const registry = new EngineRegistry()
      .register('scripted', () => engineA)
      .register('alternative', () => engineB);
    const translator = createTranslator({
      config: baseConfig(),
      logger: silentLogger,
      registry,
      cache: new MemoryCache<unknown>({ defaultTtlMs: 60_000, maxEntries: 100 }),
    });
    await translator.translate({ text: 'shared', targetLanguage: 'ar' });
    const viaB = await translator.translate({ text: 'shared', targetLanguage: 'ar', engine: 'alternative' });
    assert.equal(viaB.fromCache, false);
    assert.equal(viaB.text, 'engine B');
  });

  it('fails fast when the configured engine is not registered', async () => {
    const config = baseConfig({ TRANSLATION_ENGINE: 'missing' });
    assert.throws(() => createTranslator({ config, logger: silentLogger, registry: new EngineRegistry() }));
  });
});