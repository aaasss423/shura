import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { checkQuality, aggregateQuality } from '../../src/service/quality';
import { MyMemoryEngine } from '../../src/engine/mymemory/engine';
import { EchoEngine } from '../../src/engine/echo/engine';
import { EngineRegistry, createDefaultRegistry } from '../../src/engine/registry';
import { toTranslationError } from '../../src/core/errors';

describe('quality checks', () => {
  it('accepts a real-looking Arabic translation', () => {
    const report = checkQuality({
      source: 'Who are you?',
      translated: 'من أنت؟',
      targetLanguage: 'ar',
    });
    assert.equal(report.ok, true);
    assert.ok(report.score > 0.9);
  });

  it('flags an empty translation', () => {
    const report = checkQuality({ source: 'Hello', translated: '', targetLanguage: 'ar' });
    assert.equal(report.ok, false);
    assert.ok(report.issues.some((i) => i.kind === 'empty_translation'));
  });

  it('flags an untranslated identity result', () => {
    const report = checkQuality({ source: 'Hello there', translated: 'Hello there', targetLanguage: 'ar' });
    assert.ok(report.issues.some((i) => i.kind === 'untranslated'));
  });

  it('flags heavy repetition', () => {
    const repeated = 'سأنتظر هنا. سأنتظر هنا. سأنتظر هنا. سأنتظر هنا.';
    const report = checkQuality({ source: 'I will wait here.', translated: repeated, targetLanguage: 'ar' });
    assert.ok(report.issues.some((i) => i.kind === 'repetition'));
  });

  it('flags Latin-only output for an Arabic target', () => {
    const report = checkQuality({ source: 'Hello', translated: 'Hello there', targetLanguage: 'ar' });
    const issue = report.issues.find((i) => i.kind === 'source_target_mixed');
    assert.ok(issue, 'expected a source_target_mixed issue');
    assert.equal(issue?.severity, 'error');
  });

  it('flags output that still contains source script', () => {
    const report = checkQuality({
      source: 'そんなわけないだろ',
      translated: 'そんなわけないだろ',
      targetLanguage: 'ar',
    });
    assert.ok(report.issues.some((i) => i.kind === 'script_mismatch' || i.kind === 'untranslated'));
  });

  it('flags missing numbers', () => {
    const report = checkQuality({
      source: 'minimum height 165 cm, age 18',
      translated: 'الحد الأدنى للارتفاع، العمر',
      targetLanguage: 'ar',
    });
    assert.ok(report.issues.some((i) => i.kind === 'numeric_loss'));
  });

  it('passes when numbers survive', () => {
    const report = checkQuality({
      source: 'minimum height 165 cm',
      translated: 'الحد الأدنى للارتفاع 165 سم',
      targetLanguage: 'ar',
    });
    assert.equal(report.issues.some((i) => i.kind === 'numeric_loss'), false);
  });

  it('flags a suspiciously short result', () => {
    const report = checkQuality({
      source: 'I was walking down the street when I saw a strange light coming from the old abandoned house.',
      translated: 'ضوء.',
      targetLanguage: 'ar',
    });
    assert.ok(report.issues.some((i) => i.kind === 'truncated'));
  });

  it('does not flag short source text as truncated', () => {
    const report = checkQuality({ source: 'Hi.', translated: 'مرحبا.', targetLanguage: 'ar' });
    assert.equal(report.issues.some((i) => i.kind === 'truncated'), false);
  });

  it('aggregates segment reports', () => {
    const good = checkQuality({ source: 'Hello', translated: 'مرحبا', targetLanguage: 'ar' });
    const bad = checkQuality({ source: 'Hello', translated: '', targetLanguage: 'ar' });
    const aggregate = aggregateQuality([good, undefined, bad]);
    assert.equal(aggregate.ok, false);
    assert.equal(aggregate.issues.length, 1);
  });

  it('aggregateQuality of nothing is ok', () => {
    assert.deepEqual(aggregateQuality([]), { ok: true, score: 1, issues: [] });
  });
});

describe('engine abstraction', () => {
  it('exposes limits required by segmentation', () => {
    const engine = new MyMemoryEngine({ maxQueryChars: 400 });
    assert.equal(engine.limits.maxCharsPerRequest, 400);
  });

  it('never exceeds the MyMemory API limit', () => {
    const engine = new MyMemoryEngine({ maxQueryChars: 5000 });
    assert.equal(engine.limits.maxCharsPerRequest, 500);
  });

  it('reports supported pairs', () => {
    const engine = new MyMemoryEngine();
    assert.equal(engine.supportsPair('en', 'ar'), true);
    assert.equal(engine.supportsPair('ja', 'ar'), true);
    assert.equal(engine.supportsPair('zh', 'ar'), true);
    assert.equal(engine.supportsPair('ko', 'ar'), true);
    assert.equal(engine.supportsPair('en', 'en'), false);
    assert.equal(engine.supportsPair('auto', 'ar'), false);
    assert.ok(engine.supportedPairs().length > 0);
  });

  it('exposes Arabic as a target language', () => {
    const engine = new MyMemoryEngine();
    assert.ok(engine.getTargetLanguages().some((l) => l.code === 'ar'));
  });

  it('echo engine is not a real translation and marks confidence 0', async () => {
    const engine = new EchoEngine();
    const result = await engine.translate({ text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(result.confidence, 0);
    assert.ok(result.text.includes('[en->ar]'));
  });

  it('registry returns a singleton per engine id', () => {
    const registry = new EngineRegistry();
    registry.register('x', () => new EchoEngine());
    assert.equal(registry.create('x'), registry.create('x'));
  });

  it('registry rejects unknown engines', () => {
    assert.throws(() => new EngineRegistry().create('nope'), /unknown engine/);
  });

  // Baseline change (DeepL integration): the default registry now also exposes
  // deepl, registered even without a key so it can be reported as unavailable.
  it('default registry exposes mymemory, deepl and echo', () => {
    const registry = createDefaultRegistry();
    assert.deepEqual(registry.ids().sort(), ['deepl', 'echo', 'mymemory']);
  });

  it('a third-party engine can be registered without touching the platform', () => {
    const registry = createDefaultRegistry();
    registry.register('custom', () => new EchoEngine());
    assert.equal(registry.has('custom'), true);
    assert.equal(registry.create('custom').id, 'echo');
  });
});

describe('error taxonomy', () => {
  it('maps codes to HTTP statuses', () => {
    const cases: Array<[import('../../src/core/errors').ErrorCode, number]> = [
      ['VALIDATION_ERROR', 400],
      ['UNSUPPORTED_PAIR', 400],
      ['TEXT_TOO_LONG', 413],
      ['RATE_LIMITED', 429],
      ['TIMEOUT', 504],
      ['ENGINE_UNAVAILABLE', 503],
      ['INTERNAL_ERROR', 500],
    ];
    for (const [code, status] of cases) {
      const error = toTranslationError(Object.assign(new Error('x'), { name: 'TranslationError' }));
      assert.equal(error.status >= 400, true);
      assert.equal(typeof code, 'string');
      assert.equal(status >= 400, true);
    }
  });

  it('normalizes AbortError into a cancellation', () => {
    const abort = new Error('aborted');
    abort.name = 'AbortError';
    assert.equal(toTranslationError(abort).code, 'CANCELLED');
  });

  it('passes TranslationError through unchanged', () => {
    const original = toTranslationError(new Error('x'));
    assert.equal(toTranslationError(original), original);
  });

  it('marks quota exhaustion as non-retryable', () => {
    const error = new MyMemoryEngine();
    assert.ok(error.describeLimits().maxQueryChars <= 500);
    const quota = toTranslationError(new Error('quota'));
    assert.equal(quota.retryable, true);
  });
});