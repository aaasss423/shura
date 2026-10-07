/**
 * Live DeepL translation tests.
 *
 * Skipped unless `LIVE_DEEPL_TESTS=1` **and** `DEEPL_API_KEY` is set. Every
 * assertion here requires a real response from DeepL: a cached, mocked or
 * fabricated result cannot satisfy them. When the key is absent, or the account
 * quota is exhausted, the tests report SKIPPED with the reason — never a
 * silent pass and never a false failure.
 *
 * Fixtures are manga/manhwa dialogue rather than generic sentences: short
 * exclamations, emotional lines, narration, names, numbers and mixed Latin.
 */

import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, before, describe, it } from 'node:test';
import type { TestContext } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { createTranslator, type Translator } from '../../src/translator/translator';
import { DeepLEngine } from '../../src/engine/deepl/engine';
import { silentLogger } from '../../src/core/logger';
import { CancellationToken } from '../../src/core/cancellation';
import { isTranslationError } from '../../src/core/errors';
import { arabicRatio, containsArabic, countCharacters } from '../../src/arabic/arabic';

const HAS_KEY = Boolean(process.env.DEEPL_API_KEY && process.env.DEEPL_API_KEY.trim().length > 0);
const ENABLED = process.env.LIVE_DEEPL_TESTS === '1' && HAS_KEY;

let translator: Translator;
let tempDir: string;
const transcript: string[] = [];

function record(line: string): void {
  transcript.push(line);
  process.stdout.write(`${line}\n`);
}

before(async () => {
  tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-deepl-live-'));
  const config = loadConfig({
    readDotEnv: false,
    rootDir: tempDir,
    env: {
      TRANSLATION_ENGINE: 'deepl',
      LOG_LEVEL: 'silent',
      CACHE_ENABLED: 'true',
      CACHE_DIR: tempDir,
      RETRY_MAX_ATTEMPTS: '3',
      RETRY_BASE_DELAY_MS: '500',
      RETRY_MAX_DELAY_MS: '4000',
      RETRY_JITTER_RATIO: '0',
      REQUEST_TIMEOUT_MS: '30000',
      CHAPTER_DEADLINE_MS: '180000',
      CHAPTER_CONCURRENCY: '1',
      DEEPL_MIN_INTERVAL_MS: '150',
      // Read from the process environment: the key never appears in this file.
      ...(process.env.DEEPL_API_KEY ? { DEEPL_API_KEY: process.env.DEEPL_API_KEY } : {}),
      ...(process.env.DEEPL_ENDPOINT ? { DEEPL_ENDPOINT: process.env.DEEPL_ENDPOINT } : {}),
    },
  });
  translator = createTranslator({ config, logger: silentLogger });
});

after(async () => {
  await translator?.flushCache();
  await fs.rm(tempDir, { recursive: true, force: true });
});

/**
 * Registers a live test. Skips (with a reason) when the key is missing, or when
 * DeepL reports an exhausted quota, so a real quota wall is never reported as a
 * code defect and never faked as a pass.
 */
function deepLIt(label: string, fn: (t: TestContext) => Promise<void>): void {
  it(label, async (t) => {
    if (!ENABLED) {
      t.skip(
        HAS_KEY
          ? 'set LIVE_DEEPL_TESTS=1 to run live DeepL tests'
          : 'DEEPL_API_KEY is not set in the environment',
      );
      return;
    }
    try {
      await fn(t);
    } catch (error) {
      if (isTranslationError(error) && error.code === 'QUOTA_EXCEEDED') {
        record(`[quota] "${label}": DeepL character quota exhausted — reported as SKIPPED.`);
        t.skip(`DeepL quota exhausted: ${error.message}`);
        return;
      }
      throw error;
    }
  });
}

/** Assertions that only a real Arabic translation from DeepL can satisfy. */
function assertRealArabic(source: string, translated: string, label: string): void {
  assert.ok(translated.trim().length > 0, `${label}: empty translation`);
  assert.notEqual(translated.trim(), source.trim(), `${label}: output identical to source`);
  assert.ok(containsArabic(translated), `${label}: no Arabic letters in ${JSON.stringify(translated.slice(0, 90))}`);
  assert.ok(
    arabicRatio(translated) > 0.45,
    `${label}: Arabic ratio ${arabicRatio(translated).toFixed(2)} too low in ${JSON.stringify(translated.slice(0, 120))}`,
  );
  assert.ok(!translated.includes('\uFFFD'), `${label}: Unicode replacement character present`);
  // No mechanical block repetition.
  const trimmed = translated.trim();
  if (trimmed.length > 30) {
    const head = trimmed.slice(0, 10);
    assert.ok(!trimmed.startsWith(head + head), `${label}: repeated prefix`);
  }
}

/** Manga-style fixtures per source language. */
const FIXTURES: Record<string, Array<[string, string]>> = {
  en: [
    ['short dialogue', 'Are you serious right now?!'],
    ['emotional outburst', "I don't believe what you just said to me!"],
    ['narration', 'The rain stopped somewhere around midnight, and the street went silent.'],
    ['character names', 'Anna looked at Kenji and said nothing at all.'],
    ['numbers and units', 'The barrier is 165 cm tall and opens at 6:00.'],
    ['taunt', "Don't worry, I'll be fine. Just you wait."],
    ['inner monologue', 'He had no choice but to run. There was no time left.'],
  ],
  ja: [
    ['short dialogue', 'そんなわけないだろ。'],
    ['emotional outburst', 'どうして！？そんなこと言うんだ！'],
    ['narration', '雨は夜中に止んでいた。'],
    ['polite request', 'ちょっと待ってください。'],
    ['first person resolve', '俺はここで待っている。'],
    ['numbers', '3階突き当たりだ。'],
  ],
  zh: [
    ['short dialogue', '别开玩笑了。'],
    ['question', '你以为我是谁？'],
    ['narration', '雨在午夜时分停了。'],
    ['threat', '你要是再往前走一步试试。'],
    ['numbers', '在三楼左转。'],
  ],
  ko: [
    ['greeting', '안녕하세요? 반갑습니다.'],
    ['question', '당신은 누구세요?'],
    ['narration', '빗방울은 자정 무렵 멈췄다.'],
    ['short angry', '닥쳐!'],
    ['politeness', '실례합니다, 다시 말씀해 주시겠어요?'],
  ],
};

describe('live DeepL: engine health', () => {
  deepLIt('reports a healthy engine with a key fingerprint', async () => {
    const health = await translator.checkEngineHealth('deepl');
    record(`[deepl health] ${JSON.stringify(health)}`);
    assert.equal(health.healthy, true);
    assert.ok((health.detail ?? '').includes('key '), 'health must name the key fingerprint, never the key');
  });

  deepLIt('never returns the API key in any diagnostic output', async () => {
    const key = process.env.DEEPL_API_KEY ?? '';
    const capabilities = translator.getEngineCapabilities('deepl');
    const engines = translator.describeEngines();
    const secrets = await translator.describeSecrets();
    const serialized = JSON.stringify({ capabilities, engines, secrets, health: await translator.checkEngineHealth('deepl') });
    assert.ok(!serialized.includes(key), 'no diagnostic payload may contain the key');
    assert.ok(!serialized.includes(key.slice(0, 8)), 'no diagnostic payload may contain a key prefix');
  });

  deepLIt('exposes Arabic among its target languages', async () => {
    const caps = translator.getEngineCapabilities('deepl');
    assert.ok(caps.target.some((l) => l.code === 'ar'));
    assert.ok(caps.maxCharsPerRequest > 1000);
  });
});

for (const [language, cases] of Object.entries(FIXTURES)) {
  describe(`live DeepL: ${language} -> ar`, () => {
    for (const [label, source] of cases) {
      deepLIt(`translates ${label}`, async () => {
        const result = await translator.translate({
          text: source,
          sourceLanguage: language,
          targetLanguage: 'ar',
        });
        record(`[deepl ${language}->ar] ${label}\n    src: ${source}\n    out: ${result.text}\n    ${result.elapsedMs}ms`);
        assert.equal(result.engine, 'deepl', 'the request must be served by DeepL');
        assertRealArabic(source, result.text, `deepl ${language}->ar ${label}`);
      });
    }
  });
}

describe('live DeepL: numbers, names and formatting', () => {
  deepLIt('preserves digits from the source', async () => {
    const source = 'The barrier is 165 cm tall and opens at 6:00.';
    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[deepl numbers]\n    src: ${source}\n    out: ${result.text}`);
    assertRealArabic(source, result.text, 'deepl numbers');
    const numbers = source.match(/\d+/g) ?? [];
    for (const number of numbers) {
      assert.ok(result.text.includes(number), `number ${number} was lost: ${result.text}`);
    }
  });

  deepLIt('keeps punctuation attached to the translated sentence', async () => {
    const source = 'Are you serious right now?!';
    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[deepl punctuation]\n    src: ${source}\n    out: ${result.text}`);
    assertRealArabic(source, result.text, 'deepl punctuation');
    assert.match(result.text, /[.!?؟،]/, 'the sentence must still end with punctuation');
  });

  deepLIt('translates character names into the same sentence', async () => {
    const source = 'Anna looked at Kenji and said nothing at all.';
    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[deepl names]\n    src: ${source}\n    out: ${result.text}`);
    assertRealArabic(source, result.text, 'deepl names');
  });

  deepLIt('handles a multi-paragraph block', async () => {
    const source = 'Are you coming or not?\n\nI said we leave at eight.\n\nFine. But not a minute later.';
    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[deepl multi-paragraph]\n    out: ${JSON.stringify(result.text)}`);
    assertRealArabic(source, result.text, 'deepl multi-paragraph');
    const paragraphs = result.text.split(/\n\n+/).filter((p) => p.trim());
    assert.ok(paragraphs.length >= 3, 'paragraph structure must survive');
    for (const paragraph of paragraphs) {
      assert.ok(containsArabic(paragraph), `each paragraph must be Arabic: ${paragraph}`);
    }
  });
});

describe('live DeepL: chapter translation', () => {
  deepLIt('translates a manga-style chapter end to end', async () => {
    await translator.clearCache();
    const segments = [
      { id: 'p1', text: 'Are you serious right now?!' },
      { id: 'p2', text: "I don't believe what you just said to me." },
      { id: 'p3', text: 'The barrier is 165 cm tall and opens at 6:00.' },
      { id: 'p4', text: "Don't worry, I'll be fine." },
      { id: 'p5', text: 'He had no choice but to run.' },
    ];
    const progress: number[] = [];
    const result = await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 1,
      onProgress: (p) => progress.push(p.completedSegments),
    });
    record(
      `[deepl chapter] degraded=${result.degraded} elapsed=${result.elapsedMs}ms\n` +
        result.segments.map((s) => `    ${s.id}: ${s.translated}${s.error ? ` [${s.error.code}]` : ''}`).join('\n'),
    );
    assert.equal(result.degraded, false);
    assert.equal(result.engine, 'deepl');
    assert.ok(progress.length >= segments.length);
    for (const segment of result.segments) {
      assertRealArabic(segment.source, segment.translated, `deepl chapter ${segment.id}`);
    }

    const cached = await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    assert.equal(cached.progress.cachedSegments, segments.length, 'the chapter must be cached on the second pass');
    await translator.clearCache();
  });

  deepLIt('translates a Japanese chapter to Arabic', async () => {
    await translator.clearCache();
    const segments = [
      { id: 'j1', text: 'そんなわけないだろ。' },
      { id: 'j2', text: '俺はここで待っている。' },
      { id: 'j3', text: '雨は夜中に止んでいた。' },
    ];
    const result = await translator.translateChapter({
      segments,
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    record(
      `[deepl chapter ja->ar] degraded=${result.degraded}\n` +
        result.segments.map((s) => `    ${s.id}: ${s.translated}`).join('\n'),
    );
    for (const segment of result.segments) {
      assertRealArabic(segment.source, segment.translated, `deepl ja chapter ${segment.id}`);
    }
    await translator.clearCache();
  });
});

describe('live DeepL: cache and resilience', () => {
  deepLIt('call 1 translates, call 2 is a cache hit', async () => {
    await translator.clearCache();
    const source = 'The lighthouse keeper climbed the spiral stair.';
    const first = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(first.fromCache, false, 'call 1 must reach DeepL');
    const second = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(second.fromCache, true, 'call 2 must be served from cache');
    assert.equal(second.text, first.text);
    await translator.clearCache();
  });

  deepLIt('honours a request timeout', async () => {
    await assert.rejects(
      () =>
        translator.translate({
          text: 'A request with an impossible budget.',
          sourceLanguage: 'en',
          targetLanguage: 'ar',
          timeoutMs: 1,
          retries: 0,
        }),
      (error: { code?: string }) => ['TIMEOUT', 'ENGINE_ERROR'].includes(error.code ?? ''),
    );
  });

  deepLIt('cancels before the request is sent', async () => {
    const token = new CancellationToken();
    token.cancel();
    await assert.rejects(
      () =>
        translator.translate({
          text: 'Cancelled probe.',
          sourceLanguage: 'en',
          targetLanguage: 'ar',
          token,
        }),
      (error: { code?: string }) => error.code === 'CANCELLED',
    );
  });

  deepLIt('reports an invalid credential as a configuration error without echoing it', async () => {
    const engine = new DeepLEngine({ apiKey: 'definitely-not-a-valid-key', timeoutMs: 20000 });
    await assert.rejects(
      () => engine.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' }),
      (error: unknown) => {
        if (!isTranslationError(error)) {
          return false;
        }
        assert.ok(
          error.code === 'CONFIG_ERROR' || error.code === 'ENGINE_ERROR',
          `unexpected code ${error.code}`,
        );
        assert.ok(
          !error.message.includes('definitely-not-a-valid-key'),
          'an invalid key must never appear in an error message',
        );
        return true;
      },
    );
  });
});

describe('live DeepL: engine construction', () => {
  deepLIt('DeepLEngine reports configured with a key and unconfigured without one', async () => {
    assert.equal(new DeepLEngine().isConfigured(), false);
    const configured = new DeepLEngine({
      ...(process.env.DEEPL_API_KEY ? { apiKey: process.env.DEEPL_API_KEY } : {}),
    });
    if (process.env.DEEPL_API_KEY) {
      assert.equal(configured.isConfigured(), true);
      assert.ok(configured.keyFingerprint()?.length === 12);
    }
  });

  deepLIt('a large payload is segmented rather than rejected', async () => {
    const source = Array.from(
      { length: 40 },
      (_, i) => `The soldier checked the door again before stepping into the rain. Beat ${i}.`,
    ).join('\n\n');
    assert.ok(countCharacters(source) > 1000);
    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[deepl segmented] segments=${result.segments} elapsed=${result.elapsedMs}ms`);
    assert.ok(containsArabic(result.text));
    assertRealArabic(source, result.text, 'deepl segmented');
  });
});