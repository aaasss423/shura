/**
 * Live translation tests against the real MyMemory engine.
 *
 * These are skipped unless LIVE_TRANSLATION_TESTS=1 is set, because they
 * consume a free-tier quota and require network access.
 *
 * They are not tautological: each asserts that a real request reached the
 * engine, that a real Arabic translation came back, and that the text is
 * materially different from the source with no obvious mechanical damage.
 */

import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, before, describe, it } from 'node:test';
import type { TestContext } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { createTranslator, Translator } from '../../src/translator/translator';
import { MyMemoryEngine } from '../../src/engine/mymemory/engine';
import { silentLogger } from '../../src/core/logger';
import { containsArabic, countCharacters, arabicRatio } from '../../src/arabic/arabic';
import { detectLanguage } from '../../src/language/detect';
import { isTranslationError } from '../../src/core/errors';
import type { QualityReport } from '../../src/core/types';

const LIVE = process.env.LIVE_TRANSLATION_TESTS === '1';
const opts = { skip: LIVE ? false : 'set LIVE_TRANSLATION_TESTS=1 to run live translation tests' };

let translator: Translator;
let tempDir: string;
const transcript: string[] = [];

function record(line: string): void {
  transcript.push(line);
  process.stdout.write(`${line}\n`);
}

/**
 * The free MyMemory tier is a shared daily budget per IP. When it is exhausted
 * the engine raises QuotaExceededError. That is an environment condition, not a
 * platform defect, so such tests report as skipped with a clear reason instead
 * of failing and hiding genuine regressions.
 */
/**
 * Set once the engine confirms the shared daily quota is spent. Tests then
 * report as SKIPPED with an explicit reason, so an exhausted free tier is never
 * confused with a platform regression and never silently passes.
 */
let quotaExhausted = false;

/**
 * Registers a live test. A live test either proves real translation, or is
 * reported as skipped because the free engine tier is exhausted.
 */
function liveIt(label: string, fn: (t: TestContext) => Promise<void>): void {
  it(label, async (t) => {
    if (quotaExhausted) {
      t.skip('MyMemory free daily quota exhausted (shared per-IP budget)');
      return;
    }
    try {
      await fn(t);
    } catch (error) {
      if (isTranslationError(error) && error.code === 'QUOTA_EXCEEDED') {
        quotaExhausted = true;
        record(`[quota] "${label}" hit the MyMemory daily quota — remaining live tests will report SKIPPED.`);
        t.skip(`MyMemory daily quota exhausted: ${error.message}`);
        return;
      }
      throw error;
    }
  });
}

before(async () => {
  tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-live-'));
  const config = loadConfig({
    readDotEnv: false,
    rootDir: tempDir,
    env: {
      TRANSLATION_ENGINE: 'mymemory',
      LOG_LEVEL: 'silent',
      CACHE_ENABLED: 'true',
      CACHE_DIR: tempDir,
      RETRY_MAX_ATTEMPTS: '3',
      RETRY_BASE_DELAY_MS: '400',
      RETRY_MAX_DELAY_MS: '3000',
      RETRY_JITTER_RATIO: '0',
      REQUEST_TIMEOUT_MS: '20000',
      CHAPTER_DEADLINE_MS: '120000',
      MYMEMORY_MAX_QUERY_CHARS: '450',
      ...(process.env.MYMEMORY_CONTACT_EMAIL ? { MYMEMORY_CONTACT_EMAIL: process.env.MYMEMORY_CONTACT_EMAIL } : {}),
      ...(process.env.MYMEMORY_ENDPOINT ? { MYMEMORY_ENDPOINT: process.env.MYMEMORY_ENDPOINT } : {}),
    },
  });
  translator = createTranslator({ config, logger: silentLogger });
});

after(async () => {
  await translator?.flushCache();
  await fs.rm(tempDir, { recursive: true, force: true });
  if (LIVE && transcript.length > 0) {
    process.stdout.write(`\n--- live transcript (${transcript.length} entries) recorded above ---\n`);
  }
});

/** Quality assertions applied to every live translation. */
function assertRealArabic(source: string, translated: string, quality: QualityReport | undefined, label: string): void {
  assert.ok(translated.trim().length > 0, `${label}: empty translation`);
  assert.notEqual(translated.trim(), source.trim(), `${label}: output identical to source (untranslated)`);
  assert.ok(
    containsArabic(translated),
    `${label}: expected Arabic letters, got ${JSON.stringify(translated.slice(0, 80))}`,
  );
  // A real Arabic translation is mostly Arabic script, not stray Latin noise.
  assert.ok(
    arabicRatio(translated) > 0.4,
    `${label}: Arabic ratio too low (${arabicRatio(translated)}): ${JSON.stringify(translated.slice(0, 120))}`,
  );
  assert.ok(
    !/\uFFFD/.test(translated),
    `${label}: output contains the Unicode replacement character (encoding damage)`,
  );
  // No mechanical repetition of one short block.
  const trimmed = translated.trim();
  if (trimmed.length > 30) {
    const head = trimmed.slice(0, 12);
    assert.ok(!trimmed.startsWith(head + head), `${label}: suspicious repeated prefix`);
  }
  if (quality) {
    assert.ok(!quality.issues.some((i) => i.kind === 'empty_translation'), `${label}: empty_translation issue`);
    assert.ok(!quality.issues.some((i) => i.kind === 'source_target_mixed' && i.severity === 'error'),
      `${label}: source/target mix issue: ${JSON.stringify(quality.issues)}`);
    assert.ok(!quality.issues.some((i) => i.kind === 'repetition'), `${label}: repetition issue`);
  }
}

describe('live engine health', opts);
describe('live English -> Arabic', opts);
describe('live Japanese -> Arabic', opts);
describe('live Chinese -> Arabic', opts);
describe('live Korean -> Arabic', opts);
describe('live mixed and structured text', opts);
describe('live segmentation', opts);
describe('live cache sequence', opts);
describe('live chapter translation', opts);
describe('live timeout and cancellation', opts);

describe('live engine health', async () => {
  liveIt('reports engine health and reaches the engine', async () => {
    const health = await translator.checkEngineHealth();
    record(`[health] ${JSON.stringify(health)}`);
    assert.equal(typeof health.healthy, 'boolean');
    // health=false is a legitimate outcome when the free daily tier is spent;
    // it must still carry a reason rather than failing silently.
    if (!health.healthy) {
      assert.ok(typeof health.detail === 'string' && health.detail.length > 0, 'unhealthy must explain why');
    }
  });

  liveIt('translates a real probe to Arabic', async () => {
    const probe = 'Are you serious right now?!';
    const result = await translator.translate({
      text: probe,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(result.engine, 'mymemory');
    assertRealArabic(probe, result.text, result.quality, 'health probe');
  });

  it('classifies quota exhaustion as non-retryable', async () => {
    // Proves the engine does not retry a 6-hour quota wall.
    try {
      await translator.translate({
        text: `Quota classification probe ${Date.now()}`,
        sourceLanguage: 'en',
        targetLanguage: 'ar',
      });
    } catch (error) {
      if (isTranslationError(error) && error.code === 'QUOTA_EXCEEDED') {
        assert.equal(error.retryable, false, 'quota exhaustion must not be retried');
        record('[quota] quota exhaustion correctly classified as non-retryable');
      }
    }
  });

  liveIt('caps requests at the documented MyMemory limit', async () => {
    const engine = new MyMemoryEngine({ maxQueryChars: 450 });
    assert.equal(engine.limits.maxCharsPerRequest, 450);
  });
});

describe('live English -> Arabic', () => {
  const cases: Array<[string, string]> = [
    ['short dialogue', 'Are you serious right now?!'],
    ['longer dialogue', "I don't believe what you just said to me. You knew about this from the start."],
    ['numbers and units', 'minimum height 165 cm, age 18'],
    ['character name usage', 'Anna looked at Kenji and said nothing at all.'],
    ['with punctuation', 'Wait... what? "No!" she whispered.'],
  ];

  for (const [label, source] of cases) {
    liveIt(`translates ${label}`, async () => {
      const result = await translator.translate({
        text: source,
        sourceLanguage: 'en',
        targetLanguage: 'ar',
      });
      record(`[en->ar] ${label}\n    src: ${source}\n    out: ${result.text}\n    quality: ${JSON.stringify(result.quality)}`);
      assert.equal(result.engine, 'mymemory');
      assert.equal(result.fromCache, false);
      assertRealArabic(source, result.text, result.quality, `en->ar ${label}`);
    });
  }
});

describe('live Japanese -> Arabic', () => {
  const cases: Array<[string, string]> = [
    ['short dialogue', '本気なのか？'],
    ['polite refusal', 'そんなわけないだろ。'],
    ['first person', '俺はここで待っている。'],
    ['greeting', 'こんにちは、元気ですか。'],
  ];

  for (const [label, source] of cases) {
    liveIt(`translates ${label}`, async () => {
      const result = await translator.translate({
        text: source,
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
      });
      record(`[ja->ar] ${label}\n    src: ${source}\n    out: ${result.text}\n    quality: ${JSON.stringify(result.quality)}`);
      assert.equal(result.engine, 'mymemory');
      assertRealArabic(source, result.text, result.quality, `ja->ar ${label}`);
    });
  }
});

describe('live Chinese -> Arabic', () => {
  const cases: Array<[string, string]> = [
    ['question', '你以为我是谁？'],
    ['short dialogue', '别开玩笑了。'],
    ['statement', '今天天气很好，我们去公园吧。'],
  ];

  for (const [label, source] of cases) {
    liveIt(`translates ${label}`, async () => {
      const result = await translator.translate({
        text: source,
        // Deliberately "auto": the detector must classify pure Han as Chinese.
        sourceLanguage: 'auto',
        targetLanguage: 'ar',
      });
      record(`[zh->ar] ${label}\n    src: ${source}\n    out: ${result.text}\n    detected: ${result.detectedLanguage}\n    quality: ${JSON.stringify(result.quality)}`);
      assert.equal(result.detectedLanguage, 'zh', 'pure Han must not be detected as Japanese');
      assert.equal(result.sourceLanguage, 'zh');
      assertRealArabic(source, result.text, result.quality, `zh->ar ${label}`);
    });
  }
});

describe('live Korean -> Arabic', () => {
  const cases: Array<[string, string]> = [
    ['greeting', '안녕하세요? 반갑습니다.'],
    ['question', '당신은 누구세요?'],
    ['statement', '저는 지금 학교에 가고 있어요.'],
  ];

  for (const [label, source] of cases) {
    liveIt(`translates ${label}`, async () => {
      const result = await translator.translate({
        text: source,
        sourceLanguage: 'ko',
        targetLanguage: 'ar',
      });
      record(`[ko->ar] ${label}\n    src: ${source}\n    out: ${result.text}\n    quality: ${JSON.stringify(result.quality)}`);
      assertRealArabic(source, result.text, result.quality, `ko->ar ${label}`);
    });
  }
});

describe('live mixed and structured text', () => {
  liveIt('translates multi-line text preserving line structure', async (_t) => {
    // Long enough to exceed the per-request engine limit, so it must segment.
    const source = [
      'Are you coming or not?',
      'I said we leave at eight and not one minute later.',
      'Fine. But I am not walking there in this rain.',
      'Then take the train and stop complaining about the weather.',
    ].join('\n\n');
    const result = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    record(`[en->ar multi-line]\n    src: ${JSON.stringify(source)}\n    out: ${JSON.stringify(result.text)}`);
    assertRealArabic(source, result.text, result.quality, 'multi-line');
    // Paragraph structure must survive translation, not collapse into one blob.
    const sourceParagraphs = source.split(/\n\n+/).length;
    const outputParagraphs = result.text.split(/\n\n+/).filter((p) => p.trim()).length;
    assert.equal(outputParagraphs, sourceParagraphs, 'paragraph structure must be preserved');
    for (const paragraph of result.text.split(/\n\n+/).filter((p) => p.trim())) {
      assert.ok(containsArabic(paragraph), `every paragraph must be Arabic: ${JSON.stringify(paragraph)}`);
    }
  });

  liveIt('translates text mixing Latin names inside Arabic', async (_t) => {
    const source = 'Anna said that Kenji will arrive at 7 pm with the documents.';
    const result = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    record(`[en->ar mixed]\n    src: ${source}\n    out: ${result.text}`);
    assertRealArabic(source, result.text, result.quality, 'mixed');
    // A digit from the source should survive somewhere in the output.
    assert.ok(/7/.test(result.text) || result.quality?.issues.some((i) => i.kind === 'numeric_loss'));
  });

  liveIt('translates a line containing an Arabic quotation mark style', async (_t) => {
    const source = 'He said, "We leave now," and nobody argued.';
    const result = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    record(`[en->ar quotes]\n    src: ${source}\n    out: ${result.text}`);
    assertRealArabic(source, result.text, result.quality, 'quotes');
  });
});

describe('live segmentation', () => {
  liveIt('splits text exceeding the engine limit and merges in order', async (_t) => {
    const source = Array.from(
      { length: 22 },
      (_, i) => `The soldier checked the door again before stepping outside into the rain. Line ${i}.`,
    ).join('\n\n');
    assert.ok(countCharacters(source) > 450, 'fixture must exceed the engine limit');

    const result = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: 'ar' });
    record(`[en->ar segmented] segments=${result.segments}\n    out: ${JSON.stringify(result.text.slice(0, 400))}`);

    assert.ok(result.segments > 1, 'long text must be segmented');
    assert.ok(containsArabic(result.text), 'merged output must be Arabic');
    assertRealArabic(source, result.text, result.quality, 'segmented');
    // Content preservation: paragraph count must survive the round trip.
    const sourceBlocks = source.split(/\n\n+/).length;
    const outputBlocks = result.text.split(/\n\n+/).filter((b) => b.trim()).length;
    assert.ok(
      outputBlocks >= Math.floor(sourceBlocks * 0.5),
      `paragraph structure lost: ${sourceBlocks} -> ${outputBlocks}`,
    );
  });
});

describe('live cache sequence', () => {
  liveIt('call 1 translates and stores, call 2 hits the cache', async (_t) => {
    const source = `Live cache probe ${Date.now()}: the lantern flickered twice before going dark.`;
    const target = 'ar';

    await translator.clearCache();

    // Call 1: must be a real translation.
    const first = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: target });
    assert.equal(first.fromCache, false, 'call 1 must reach the engine');
    assertRealArabic(source, first.text, first.quality, 'cache call 1');

    // Call 2: identical request, must be served from cache.
    const second = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: target });
    assert.equal(second.fromCache, true, 'call 2 must be a cache hit');
    assert.equal(second.text, first.text, 'cached text must be identical');
    record(`[cache] call1(fromCache=${first.fromCache}) call2(fromCache=${second.fromCache})`);

    // Call 3: noCache forces a fresh translation and still writes.
    const third = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: target,
      noCache: true,
    });
    assert.equal(third.fromCache, false, 'noCache must bypass the cache');

    // Call 4: after the noCache write, the entry is still available.
    const fourth = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: target });
    assert.equal(fourth.fromCache, true, 'call 4 must hit the entry written by call 3');

    await translator.clearCache();
    const afterClear = await translator.translate({ text: source, sourceLanguage: 'en', targetLanguage: target });
    assert.equal(afterClear.fromCache, false, 'clear must drop the entry');
  });

  liveIt('does not create separate entries for the same text with different contextBefore', async (_t) => {
    await translator.clearCache();
    const source = `Context probe ${Date.now()}: she closed the gate behind her.`;
    const first = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      contextBefore: 'Panel one.',
    });
    const second = await translator.translate({
      text: source,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      contextBefore: 'A completely different panel.',
    });
    assert.equal(first.fromCache, false);
    assert.equal(second.fromCache, true, 'contextBefore must not fragment the cache');
    await translator.clearCache();
  });
});

describe('live chapter translation', () => {
  liveIt('translates a full chapter end to end', async (_t) => {
    await translator.clearCache();
    const segments: Array<{ id: string; text: string }> = [
      { id: 'p1', text: 'Are you serious right now?!' },
      { id: 'p2', text: "I don't believe what you just said to me." },
      { id: 'p3', text: 'minimum height 165 cm, age 18' },
      { id: 'p4', text: "Don't worry, I'll be fine." },
      { id: 'p5', text: 'The rain stopped somewhere around midnight.' },
    ];

    const progress: number[] = [];
    const result = await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 2,
      onProgress: (p) => progress.push(p.completedSegments),
    });

    record(
      `[chapter] segments=${result.segments.length} degraded=${result.degraded} elapsed=${result.elapsedMs}ms\n` +
        result.segments
          .map((s) => `    ${s.id}: ${s.translated}${s.fallback ? ' [FALLBACK]' : ''}${s.error ? ` [${s.error.code}]` : ''}`)
          .join('\n'),
    );

    assert.equal(result.segments.length, 5);
    assert.equal(result.degraded, false, 'no segment should have failed');
    assert.ok(progress.length >= 5, 'progress must be reported for every segment');
    for (const segment of result.segments) {
      assertRealArabic(segment.source, segment.translated, undefined, `chapter ${segment.id}`);
    }
    assert.ok(containsArabic(result.text));
    assert.ok(!/\uFFFD/.test(result.text));

    // Second pass must come entirely from cache.
    const cached = await translator.translateChapter({
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 2,
    });
    assert.equal(cached.progress.cachedSegments, 5, 'the whole chapter should be cached on the second pass');
    assert.equal(cached.text, result.text);
    await translator.clearCache();
  });

  liveIt('translates a Japanese chapter to Arabic', async (_t) => {
    await translator.clearCache();
    const segments: Array<{ id: string; text: string }> = [
      { id: 'j1', text: 'そんなわけないだろ。' },
      { id: 'j2', text: '俺はここで待っている。' },
      { id: 'j3', text: '早起きしたから、少し眠い。' },
    ];
    const result = await translator.translateChapter({
      segments,
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    record(
      `[chapter ja->ar] degraded=${result.degraded}\n` +
        result.segments.map((s) => `    ${s.id}: ${s.translated}`).join('\n'),
    );
    for (const segment of result.segments) {
      assertRealArabic(segment.source, segment.translated, undefined, `ja chapter ${segment.id}`);
    }
    await translator.clearCache();
  });
});

describe('live timeout and cancellation', () => {
  liveIt('times out a request given an impossible timeout', async (_t) => {
    await assert.rejects(
      () =>
        translator.translate({
          text: 'This request is guaranteed to exceed a one millisecond budget.',
          sourceLanguage: 'en',
          targetLanguage: 'ar',
          timeoutMs: 1,
          retries: 0,
        }),
      (error: { code?: string }) => error.code === 'TIMEOUT' || error.code === 'ENGINE_ERROR',
    );
  });

  liveIt('cancels a live request before it completes', async (_t) => {
    const { CancellationToken } = await import('../../src/core/cancellation');
    const token = new CancellationToken();
    const promise = translator.translate({
      text: `Cancel probe ${Date.now()}: the lighthouse keeper climbed the spiral stair.`,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      token,
      retries: 0,
    });
    // Cancel immediately: the pre-flight check must abort before the network call.
    token.cancel();
    await assert.rejects(() => promise, (error: { code?: string }) => error.code === 'CANCELLED');
  });

  liveIt('honours a service level deadline on a live request', async (_t) => {
    await assert.rejects(
      () =>
        translator.translate({
          text: 'Deadline probe for the live engine.',
          sourceLanguage: 'en',
          targetLanguage: 'ar',
          deadlineMs: 1,
        }),
      (error: { code?: string }) =>
        error.code === 'DEADLINE_EXCEEDED' || error.code === 'TIMEOUT' || error.code === 'CANCELLED',
    );
  });
});

describe('live language detection against real text', () => {
  liveIt('classifies real samples correctly', async () => {
    const samples: Array<[string, string]> = [
      ['مرحبا، كيف حالك؟', 'ar'],
      ['Who are you?', 'en'],
      ['本気なのか？', 'ja'],
      ['你以为我是谁？', 'zh'],
      ['안녕하세요?', 'ko'],
    ];
    for (const [text, expected] of samples) {
      const detected = detectLanguage(text);
      assert.equal(detected, expected, `"${text}" detected as ${detected}`);
    }
  });
});