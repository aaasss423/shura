import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  applyRtlIsolation,
  arabicRatio,
  containsArabic,
  countCharacters,
  countNonWhitespaceCharacters,
  extractNumbers,
  normalizeArabic,
  normalizeParagraphs,
  stripBidiMarks,
  toArabicDigits,
  toWesternDigits,
} from '../../src/arabic/arabic';

describe('arabic countCharacters', () => {
  // Regression: bidi marks used to be counted, inflating engine-limit estimates.
  it('excludes bidi control marks from the count', () => {
    assert.equal(countCharacters('مرحبا'), 5);
    assert.equal(countCharacters('\u202Bمرحبا\u202C'), 5);
    assert.equal(countCharacters('مر\u200Fحبا'), 5);
    assert.equal(countCharacters('a\u200Bb'), 2);
  });

  it('excludes LRM/RLM and zero-width non-joiners', () => {
    assert.equal(countCharacters('\u200Eمرحبا\u200F'), 5);
    assert.equal(countCharacters('x\u2060y'), 2);
  });

  it('counts surrogate pairs as single characters', () => {
    assert.equal(countCharacters('😀'), 1);
    assert.equal(countCharacters('مرحبا 😀'), 7);
  });

  it('returns zero for empty input', () => {
    assert.equal(countCharacters(''), 0);
  });

  it('ignores whitespace when asked for non-whitespace counts', () => {
    assert.equal(countNonWhitespaceCharacters('مرحبا  بالعالم'), 12);
  });
});

describe('arabic normalizeArabic', () => {
  it('converts ASCII punctuation inside Arabic runs', () => {
    const result = normalizeArabic('مرحبا, كيف حالك?');
    assert.ok(result.includes('\u060C'), 'comma should become Arabic comma');
    assert.ok(result.includes('\u061F'), 'question mark should become Arabic question mark');
  });

  it('keeps Latin text untouched apart from whitespace tidying', () => {
    const input = 'Who are you?';
    assert.equal(normalizeArabic(input), input);
  });

  it('does not mangle Japanese or Chinese text', () => {
    const ja = 'そんなわけないだろ。';
    assert.equal(normalizeArabic(ja), ja);
    const zh = '别开玩笑了。';
    assert.equal(normalizeArabic(zh), zh);
  });

  // Regression guard: punctuation rewriting must only apply to Arabic runs.
  it('does not convert question marks inside Latin-only text', () => {
    assert.ok(!normalizeArabic('Really? Yes?').includes('\u061F'));
  });

  it('does convert a question mark adjacent to an Arabic letter', () => {
    assert.ok(normalizeArabic('كيف حالك؟').includes('\u061F'));
    assert.ok(normalizeArabic('حالك؟').includes('\u061F'));
  });

  it('strips bidi control marks', () => {
    assert.equal(normalizeArabic('\u202Bمرحبا\u202C'), 'مرحبا');
  });

  it('removes tatweel without touching other letters', () => {
    // 'محـــربا' minus the tatweel character must be exactly 'محربا'.
    assert.equal(normalizeArabic('محـــربا'), 'محربا');
  });

  it('collapses multiple spaces and excessive blank lines', () => {
    const result = normalizeArabic('مرحبا    بالعالم\n\n\n\nأهلا');
    assert.equal(result, 'مرحبا بالعالم\n\nأهلا');
  });

  it('normalizes CRLF line endings', () => {
    assert.equal(normalizeArabic('سطر\r\nثانٍ'), 'سطر\nثانٍ');
  });

  it('preserves mixed Arabic and Latin content', () => {
    const result = normalizeArabic('مرحبا, this is OpenAI 123');
    assert.ok(result.includes('OpenAI'));
    assert.ok(result.includes('123'));
  });

  it('returns empty string for empty input', () => {
    assert.equal(normalizeArabic(''), '');
  });

  it('preserves Arabic digits', () => {
    const input = 'العمر ١٨ سنة';
    assert.ok(normalizeArabic(input).includes('١٨'));
  });
});

describe('arabic helpers', () => {
  it('detects Arabic presence', () => {
    assert.equal(containsArabic('مرحبا'), true);
    assert.equal(containsArabic('hello'), false);
  });

  it('computes Arabic ratio', () => {
    assert.equal(arabicRatio('مرحبا بالعالم'), 1);
    assert.equal(arabicRatio('hello world'), 0);
    const mixed = arabicRatio('مرحبا world');
    assert.ok(mixed > 0 && mixed < 1, `ratio ${mixed}`);
  });

  it('extracts numbers from both digit systems', () => {
    assert.deepEqual(extractNumbers('العمر ١٨ و 25'), ['18', '25']);
    assert.deepEqual(extractNumbers('height 165 cm'), ['165']);
  });

  it('converts between digit systems', () => {
    assert.equal(toArabicDigits('123'), '١٢٣');
    assert.equal(toWesternDigits('١٢٣'), '123');
  });

  it('strips bidi marks', () => {
    assert.equal(stripBidiMarks('\u202Babc\u202C'), 'abc');
  });

  it('applies RTL isolation only to text containing Arabic', () => {
    const wrapped = applyRtlIsolation('مرحبا world');
    assert.ok(wrapped.startsWith('\u202B') && wrapped.endsWith('\u202C'));
    assert.equal(applyRtlIsolation('hello world'), 'hello world');
  });

  it('does not double-wrap already isolated text', () => {
    const once = applyRtlIsolation('مرحبا');
    assert.equal(applyRtlIsolation(once), once);
  });

  it('normalizes paragraphs', () => {
    assert.equal(normalizeParagraphs('a\n\n\n\nb\n\n\nc'), 'a\n\nb\n\nc');
  });
});