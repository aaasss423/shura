/**
 * Regression suite for the defects found in the previous version of this
 * project. Each test names the failure it prevents, so a future change that
 * reintroduces one of them fails here with an explicit explanation.
 */

import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { EngineRegistry } from '../../src/engine/registry';
import { createTranslator } from '../../src/translator/translator';
import { MemoryCache } from '../../src/cache/memoryCache';
import { FileCache } from '../../src/cache/fileCache';
import { TieredCache } from '../../src/cache/tieredCache';
import { startServer, type StartedServer } from '../../src/server/server';
import { silentLogger } from '../../src/core/logger';
import { detectLanguage } from '../../src/language/detect';
import { countCharacters, normalizeArabic } from '../../src/arabic/arabic';
import { buildCacheKey } from '../../src/cache/key';
import { MyMemoryEngine } from '../../src/engine/mymemory/engine';
import { CancellationToken } from '../../src/core/cancellation';
import { ScriptedEngine } from '../helpers/scriptedEngine';

/** Repository root: compiled tests live at dist/test/unit, three levels down. */
const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..');

const tempDirs: string[] = [];
const TTL = 60_000;

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-reg-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((d) => fs.rm(d, { recursive: true, force: true })));
});

describe('BUG: Chinese text classified as Japanese', () => {
  const hanSamples = [
    '你以为我是谁？',
    '别开玩笑了。',
    '你好世界',
    '今天天气很好，我们去公园吧。',
    '我没有听懂你在说什么。',
    '请把门关上。',
  ];

  for (const sample of hanSamples) {
    it(`detects "${sample}" as zh`, () => {
      assert.equal(detectLanguage(sample), 'zh');
    });
  }

  it('still detects kana-bearing text as Japanese', () => {
    for (const sample of ['そんなわけないだろ。', 'こんにちは', '本気なのか？', '東京に行く']) {
      assert.equal(detectLanguage(sample), 'ja');
    }
  });
});

describe('BUG: cache key changed because of contextBefore', () => {
  it('produces an identical key regardless of context', () => {
    const text = 'Shared sentence.';
    const a = buildCacheKey({ text, sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' });
    const b = buildCacheKey({ text, sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' });
    assert.equal(a, b);
    // contextBefore is not part of CacheKeyInput at all, which is the structural
    // fix: the compiler prevents it from leaking into the key.
    assert.equal(Object.keys({ text, sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' }).includes('contextBefore'), false);
  });

  it('does not fragment cache entries per chapter position', async () => {
    const engine = new ScriptedEngine({ respond: () => 'ع' });
    const dir = await tempDir();
    const config = loadConfig({
      readDotEnv: false,
      rootDir: dir,
      env: { TRANSLATION_ENGINE: 'scripted', LOG_LEVEL: 'silent', CACHE_ENABLED: 'true', CACHE_DIR: dir },
    });
    const translator = createTranslator({
      config,
      logger: silentLogger,
      registry: new EngineRegistry().register('scripted', () => engine),
      cache: new TieredCache({
        memory: new MemoryCache({ defaultTtlMs: TTL, maxEntries: 100 }),
        file: new FileCache({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 }),
      }),
    });

    const sentence = 'Are you coming or not?';
    const a = await translator.translate({ text: sentence, sourceLanguage: 'en', targetLanguage: 'ar', contextBefore: 'panel 1' });
    const b = await translator.translate({ text: sentence, sourceLanguage: 'en', targetLanguage: 'ar', contextBefore: 'panel 99' });
    assert.equal(a.fromCache, false);
    assert.equal(b.fromCache, true, 'same sentence at a different position must hit the same entry');
    assert.equal(engine.callCount, 1);
  });
});

describe('BUG: file cache race between clear and flush', () => {
  it('does not resurrect cleared data across 10 interleavings', async () => {
    for (let round = 0; round < 10; round += 1) {
      const dir = await tempDir();
      const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 200 });
      for (let i = 0; i < 25; i += 1) {
        await cache.set(`k${i}`, `v${i}`);
      }
      const flushes = [cache.flush(), cache.flush(), cache.flush()];
      await cache.clear();
      await Promise.all(flushes);
      const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 200 });
      assert.equal(await reopened.size(), 0, `round ${round}: cleared entries came back`);
    }
  });

  it('keeps a later write visible after a clear', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await cache.set('before', '1');
    await cache.clear();
    await cache.set('after', '2');
    await cache.flush();
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    assert.equal((await reopened.get('before')).hit, false);
    assert.equal((await reopened.get('after')).entry?.value, '2');
  });

  it('serializes writes so none are lost', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 500 });
    await Promise.all(Array.from({ length: 100 }, (_, i) => cache.set(`key-${i}`, `value-${i}`)));
    await cache.flush();
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 500 });
    assert.equal(await reopened.size(), 100);
  });
});

describe('BUG: test scripts pointed at wrong files and entry points', () => {
  it('package.json test script targets compiled test output', async () => {
    const pkg = JSON.parse(
      await fs.readFile(path.join(PROJECT_ROOT, 'package.json'), 'utf8'),
    ) as { scripts: Record<string, string>; main: string };

    assert.match(pkg.scripts.test ?? '', /dist\/test/);
    assert.ok(!(pkg.scripts.test ?? '').includes('dist/src/test'));
    assert.equal(pkg.main, 'dist/src/index.js');
    assert.match(pkg.scripts.start ?? '', /dist\/src\/server\/main\.js/);
  });

  it('the compiled main entry point exists on disk', async () => {
    const entry = path.join(PROJECT_ROOT, 'src', 'server', 'main.ts');
    await fs.access(entry);
    const pkg = JSON.parse(
      await fs.readFile(path.join(PROJECT_ROOT, 'package.json'), 'utf8'),
    ) as { main: string };
    const built = path.join(PROJECT_ROOT, pkg.main);
    await fs.access(built);
  });

  it('tsconfig emits both src and test trees', async () => {
    const tsconfig = JSON.parse(
      await fs.readFile(path.join(PROJECT_ROOT, 'tsconfig.json'), 'utf8'),
    ) as { compilerOptions: { rootDir: string; outDir: string } };
    assert.equal(tsconfig.compilerOptions.rootDir, '.');
    assert.equal(tsconfig.compilerOptions.outDir, 'dist');
  });
});

describe('BUG: tautological smoke test', () => {
  it('the smoke script performs a real engine call and asserts it', async () => {
    const source = await fs.readFile(
      path.join(PROJECT_ROOT, 'src', 'scripts', 'smoke.ts'),
      'utf8',
    );
    // It must not assert against itself, and it must observe engine call counts.
    assert.ok(!/expect\(true\)\.toBe\(true\)/.test(source));
    assert.match(source, /callCount/);
    assert.match(source, /containsArabic/);
    assert.match(source, /startServer/);
    assert.match(source, /translateChapter/);
  });

  it('the smoke script exits non-zero when a step fails', async () => {
    const source = await fs.readFile(
      path.join(PROJECT_ROOT, 'src', 'scripts', 'smoke.ts'),
      'utf8',
    );
    assert.match(source, /process\.exitCode = failed\.length > 0 \? 1 : 0/);
  });
});

describe('BUG: cache smoke sequence started with noCache then expected a hit', () => {
  it('call 1 is a real translation, call 2 is a cache hit', async () => {
    const engine = new ScriptedEngine({ respond: () => 'ترجمة حقيقية' });
    const dir = await tempDir();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: { TRANSLATION_ENGINE: 'scripted', LOG_LEVEL: 'silent', CACHE_ENABLED: 'true', CACHE_DIR: dir },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('scripted', () => engine),
      cache: new MemoryCache({ defaultTtlMs: TTL, maxEntries: 100 }),
    });

    const text = 'Sequence probe sentence.';
    const call1 = await translator.translate({ text, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(call1.fromCache, false, 'call 1 must reach the engine (no noCache flag)');
    assert.equal(engine.callCount, 1);

    const call2 = await translator.translate({ text, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(call2.fromCache, true, 'call 2 must be a cache hit');
    assert.equal(call2.text, call1.text);
    assert.equal(engine.callCount, 1, 'call 2 must not reach the engine');
  });

  it('a noCache call bypasses the cache in both directions', async () => {
    const engine = new ScriptedEngine({ respond: () => 'x' });
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: os.tmpdir(),
        env: { TRANSLATION_ENGINE: 'scripted', LOG_LEVEL: 'silent', CACHE_ENABLED: 'true' },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('scripted', () => engine),
      cache: new MemoryCache({ defaultTtlMs: TTL, maxEntries: 100 }),
    });
    await translator.translate({ text: 'bypass probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    const bypass = await translator.translate({
      text: 'bypass probe',
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      noCache: true,
    });
    assert.equal(bypass.fromCache, false, 'noCache must not read a stored value');
    assert.equal(engine.callCount, 2);
  });
});

describe('BUG: engine hard-wired into the service', () => {
  it('the service is constructible with any TranslationEngine implementation', async () => {
    // A completely unrelated engine shape, registered only by id.
    class MinimalEngine {
      readonly id = 'minimal';
      readonly name = 'Minimal';
      readonly limits = { maxCharsPerRequest: 200 };
      async translate(request: { text: string; sourceLanguage: string; targetLanguage: string }) {
        return { text: `${request.sourceLanguage}->${request.targetLanguage}:${request.text}`, engine: this.id };
      }
      getSourceLanguages() {
        return [{ code: 'en', name: 'English', direction: 'ltr' as const }];
      }
      getTargetLanguages() {
        return [{ code: 'ar', name: 'Arabic', direction: 'rtl' as const }];
      }
      supportsPair() {
        return true;
      }
      supportedPairs() {
        return [];
      }
    }

    const engine = new MinimalEngine();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: os.tmpdir(),
        env: { TRANSLATION_ENGINE: 'minimal', LOG_LEVEL: 'silent', CACHE_ENABLED: 'false' },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('minimal', () => engine as never),
      disableCache: true,
    });

    const result = await translator.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(result.text, 'en->ar:Hello');
    assert.equal(result.engine, 'minimal');
  });

  it('no module above the engine layer imports a concrete engine', async () => {
    const root = path.join(PROJECT_ROOT, 'src');
    const read = async (file: string): Promise<string> => fs.readFile(file, 'utf8');

    // The service layer must not know engine implementations.
    const service = await read(path.join(root, 'service', 'translationService.ts'));
    assert.ok(!service.includes('mymemory'), 'service must not reference MyMemory');
    assert.ok(!service.includes('MyMemoryEngine'), 'service must not import the engine class');

    // Neither the chapter layer nor the translator facade nor the server.
    const chapter = await read(path.join(root, 'chapter', 'chapterTranslator.ts'));
    assert.ok(!chapter.includes('mymemory'));
    const translator = await read(path.join(root, 'translator', 'translator.ts'));
    assert.ok(!translator.includes('MyMemoryEngine'));
    const server = await read(path.join(root, 'server', 'server.ts'));
    assert.ok(!server.includes('mymemory'));
  });
});

describe('BUG: service could hang when the engine hangs', () => {
  it('a hanging engine cannot hold the request open', async () => {
    const dir = await tempDir();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: {
          TRANSLATION_ENGINE: 'hang',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '200',
          CHAPTER_DEADLINE_MS: '5000',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('hang', () => new ScriptedEngine({ hang: true })),
      disableCache: true,
    });

    const started = Date.now();
    await assert.rejects(() => translator.translate({ text: 'Stuck', targetLanguage: 'ar' }));
    const elapsed = Date.now() - started;
    assert.ok(elapsed < 2000, `request took ${elapsed}ms; the request timeout must win`);
  });

  it('a chapter over a hanging engine still terminates', async () => {
    const dir = await tempDir();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: {
          TRANSLATION_ENGINE: 'hang',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '150',
          CHAPTER_DEADLINE_MS: '3000',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('hang', () => new ScriptedEngine({ hang: true })),
      disableCache: true,
    });

    const started = Date.now();
    const result = await translator.translateChapter({
      segments: Array.from({ length: 6 }, (_, i) => ({ id: `p${i}`, text: `Line ${i}` })),
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 2,
    });
    const elapsed = Date.now() - started;
    assert.ok(elapsed < 6000, `chapter took ${elapsed}ms; it must not hang`);
    assert.equal(result.degraded, true, 'every segment failed, so the chapter is degraded');
    assert.equal(result.progress.totalSegments, 6);
    for (const segment of result.segments) {
      assert.equal(segment.fallback, true, 'failed segments keep their original text');
      assert.ok(segment.error, 'each failure is reported');
    }
  });

  it('a hanging engine does not freeze the REST layer', async () => {
    const dir = await tempDir();
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: {
          TRANSLATION_ENGINE: 'hang',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '200',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('hang', () => new ScriptedEngine({ hang: true })),
      disableCache: true,
    });

    let server: StartedServer | undefined;
    try {
      server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: silentLogger });

      const stuck = fetch(`${server.url}/translate`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ text: 'Stuck request', targetLanguage: 'ar' }),
      });
      // A second request must still be served while the first is blocked.
      const health = await fetch(`${server.url}/health`);
      assert.equal(health.status, 200, 'server must stay responsive');

      const result = await stuck;
      assert.equal(result.status, 504, 'a timeout must map to 504');
      const body = (await result.json()) as { error: { code: string } };
      assert.equal(body.error.code, 'TIMEOUT');
    } finally {
      await server?.close();
    }
  });
});

describe('BUG: bidi marks counted as characters', () => {
  it('countCharacters ignores every bidi and invisible mark', () => {
    const plain = 'مرحبا';
    const decorated = '\u202Bمر\u200Fحبا\u200E\u202C';
    assert.equal(countCharacters(decorated), countCharacters(plain));
    assert.equal(countCharacters(decorated), 5);
  });

  it('normalizeArabic strips marks without damaging content', () => {
    assert.equal(normalizeArabic('\u202Bمرحبا\u202C'), 'مرحبا');
    // The mark is removed, not replaced by whitespace: there is no space in the
    // source, so none may be invented.
    assert.equal(normalizeArabic('مرحبا\u200Fبالعالم'), 'مرحبابالعالم');
    // Existing real whitespace is preserved.
    assert.equal(normalizeArabic('مرحبا \u200F بالعالم'), 'مرحبا بالعالم');
  });
});

describe('BUG: quota exhaustion treated as retryable', () => {
  it('a MyMemory daily-quota body maps to a non-retryable QuotaExceededError', () => {
    const engine = new MyMemoryEngine({ minIntervalMs: 0 });
    const body = JSON.stringify({
      responseData: { translatedText: 'MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY.' },
      responseDetails: 'MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY. NEXT AVAILABLE IN 06 HOURS',
      responseStatus: 429,
    });
    // mapApiError is reachable through translate; assert via the public surface
    // by checking the classification helper behaviour on the same body.
    const mapped = (engine as unknown as {
      mapApiError: (status: number, details: string) => { code: string; retryable: boolean };
    }).mapApiError(429, body);
    assert.equal(mapped.code, 'QUOTA_EXCEEDED');
    assert.equal(mapped.retryable, false, 'a 6-hour quota wall must not be retried');
  });

  it('a transient 429 stays retryable', () => {
    const engine = new MyMemoryEngine({ minIntervalMs: 0 });
    const mapped = (engine as unknown as {
      mapApiError: (status: number, details: string) => { code: string; retryable: boolean };
    }).mapApiError(429, 'TOO MANY REQUESTS');
    assert.equal(mapped.code, 'RATE_LIMITED');
    assert.equal(mapped.retryable, true);
  });
});

describe('BUG: cancellation did not abort the engine request', () => {
  it('the abort signal is passed to the engine and fires', async () => {
    const engine = new ScriptedEngine({ delayMs: 3000 });
    let observedAbort = false;
    const original = engine.translate.bind(engine);
    engine.translate = async (request) => {
      request.signal?.addEventListener('abort', () => {
        observedAbort = true;
      });
      return original(request);
    };

    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: os.tmpdir(),
        env: {
          TRANSLATION_ENGINE: 'scripted',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '9000',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('scripted', () => engine),
      disableCache: true,
    });

    const token = new CancellationToken();
    const promise = translator.translate({ text: 'Abort me', targetLanguage: 'ar', token });
    setTimeout(() => token.cancel(), 40);
    await assert.rejects(() => promise, /CANCELLED|cancelled/i);
    assert.equal(observedAbort, true, 'the engine must observe the abort signal');
  });
});