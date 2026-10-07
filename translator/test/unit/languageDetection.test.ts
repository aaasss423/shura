import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  collectEvidence,
  detectLanguage,
  detectLanguageDetailed,
  resolveSourceLanguage,
  UNKNOWN_LANGUAGE,
} from '../../src/language/detect';

describe('language detection', () => {
  it('detects Arabic', () => {
    const result = detectLanguageDetailed('مرحبا، كيف حالك اليوم؟');
    assert.equal(result.language, 'ar');
    assert.ok(result.confidence > 0.9, `confidence ${result.confidence}`);
  });

  it('detects English', () => {
    assert.equal(detectLanguage('Who are you? I have been waiting here for hours.'), 'en');
  });

  it('detects Korean', () => {
    assert.equal(detectLanguage('안녕하세요? 반갑습니다.'), 'ko');
  });

  // Regression: pure Chinese was previously classified as Japanese because
  // both use Han characters. Kana must be required for a ja verdict.
  it('classifies pure Han text as Chinese, not Japanese', () => {
    const cases = ['你以为我是谁？', '别开玩笑了。', '你好世界', '今天天气很好，我们去公园吧。'];
    for (const text of cases) {
      const result = detectLanguageDetailed(text);
      assert.equal(result.language, 'zh', `"${text}" detected as ${result.language}`);
      assert.equal(result.evidence.kanaCount ?? 0, 0);
      assert.ok(result.confidence >= 0.9, `confidence ${result.confidence} for "${text}"`);
    }
  });

  it('classifies kana-bearing text as Japanese', () => {
    const cases = ['そんなわけないだろ。', '俺はここで待っている。', '本気なのか？', 'こんにちは'];
    for (const text of cases) {
      assert.equal(detectLanguage(text), 'ja', `"${text}"`);
    }
  });

  it('treats a single kana character as decisive for Japanese', () => {
    const mixed = '世界は俺のものだ';
    const result = detectLanguageDetailed(mixed);
    assert.equal(result.language, 'ja');
    assert.ok(result.evidence.katakana + result.evidence.hiragana > 0);
  });

  it('prefers Japanese over Chinese when kanji and kana are mixed', () => {
    const result = detectLanguageDetailed('東京に行く');
    assert.equal(result.language, 'ja');
  });

  it('detects Cyrillic as Russian', () => {
    assert.equal(detectLanguage('Привет, как дела?'), 'ru');
  });

  it('returns und with zero confidence for input without script signals', () => {
    const result = detectLanguageDetailed('1234 5678 !!!');
    assert.equal(result.language, UNKNOWN_LANGUAGE);
    assert.equal(result.confidence, 0);
  });

  it('returns und for empty input', () => {
    assert.equal(detectLanguage(''), UNKNOWN_LANGUAGE);
  });

  it('ignores bidi marks when counting characters', () => {
    const withMarks = '\u202Bمرحبا\u202C';
    const withoutMarks = 'مرحبا';
    assert.deepEqual(collectEvidence(withMarks), collectEvidence(withoutMarks));
    assert.equal(detectLanguage(withMarks), 'ar');
  });

  it('ranks alternatives with ja second for Han-dominant text', () => {
    const result = detectLanguageDetailed('这是一个测试');
    assert.equal(result.language, 'zh');
  });

  it('resolveSourceLanguage honours an explicit language', () => {
    const resolved = resolveSourceLanguage('en', 'これはテストです');
    assert.equal(resolved.language, 'en');
    assert.equal(resolved.detected, undefined);
    assert.equal(resolved.confidence, 1);
  });

  it('resolveSourceLanguage detects when auto', () => {
    const resolved = resolveSourceLanguage('auto', 'مرحبا بالعالم');
    assert.equal(resolved.language, 'ar');
    assert.equal(resolved.detected, 'ar');
  });

  it('falls back to English when auto detection finds nothing', () => {
    const resolved = resolveSourceLanguage('auto', '###');
    assert.equal(resolved.language, 'en');
    assert.equal(resolved.detected, undefined);
  });
});