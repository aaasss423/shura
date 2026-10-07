import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { countCharacters } from '../../src/arabic/arabic';
import { mergeSegments, segmentText } from '../../src/segmentation/segment';
import type { Segment } from '../../src/core/types';

const SHORT = 'Are you serious right now?!';
const LONG = Array.from({ length: 30 }, (_, i) => `Sentence number ${i} is here to pad the text.`).join(' ');

describe('segmentText', () => {
  it('returns a single whole segment for short text', () => {
    const result = segmentText(SHORT, { maxChars: 450 });
    assert.equal(result.whole, true);
    assert.equal(result.segments.length, 1);
    assert.equal(result.segments[0]?.text, SHORT);
  });

  it('returns no segments for empty text', () => {
    assert.deepEqual(segmentText('', { maxChars: 100 }).segments, []);
    assert.deepEqual(segmentText('   ', { maxChars: 100 }).segments, []);
  });

  it('splits long text into multiple segments', () => {
    const result = segmentText(LONG, { maxChars: 120 });
    assert.ok(result.segments.length > 1, 'expected multiple segments');
    assert.equal(result.whole, false);
  });

  it('respects the maxChars limit for every segment', () => {
    const result = segmentText(LONG, { maxChars: 120 });
    for (const segment of result.segments) {
      assert.ok(
        countCharacters(segment.text) <= 120,
        `segment of ${countCharacters(segment.text)} exceeds 120`,
      );
    }
  });

  it('preserves order and full content when concatenated', () => {
    const result = segmentText(LONG, { maxChars: 120 });
    const joined = result.segments.map((s) => s.text).join('');
    assert.equal(joined.replace(/\s+/g, ' ').trim(), LONG.replace(/\s+/g, ' ').trim());
  });

  it('keeps paragraph boundaries', () => {
    const text = 'First paragraph line one.\n\nSecond paragraph line one.\n\nThird paragraph.';
    const result = segmentText(text, { maxChars: 30 });
    assert.ok(result.segments.length >= 3);
    assert.ok(result.segments.some((s) => s.text.startsWith('Second')));
    assert.ok(result.segments.some((s) => (s.trailing ?? '').includes('\n\n')));
  });

  it('handles CJK text without whitespace by slicing', () => {
    const cjk = '你'.repeat(500);
    const result = segmentText(cjk, { maxChars: 100 });
    assert.ok(result.segments.length >= 5);
    for (const segment of result.segments) {
      assert.ok(countCharacters(segment.text) <= 100);
    }
    assert.equal(result.segments.map((s) => s.text).join(''), cjk);
  });

  it('forces a single segment when maxSegments is 1', () => {
    const result = segmentText(LONG, { maxChars: 80, maxSegments: 1 });
    assert.equal(result.segments.length, 1);
    assert.equal(result.segments[0]?.forced, true);
    assert.ok(result.segments[0]!.text.length > 80);
  });

  it('never loses text when collapsing to maxSegments', () => {
    const result = segmentText(LONG, { maxChars: 60, maxSegments: 3 });
    assert.equal(result.segments.length, 3);
    const joined = result.segments.map((s) => s.text).join('');
    assert.ok(joined.includes('Sentence number 29'), 'tail content must survive');
  });

  it('handles text exceeding engine limits by segmenting', () => {
    const result = segmentText(LONG, { maxChars: 450 });
    for (const segment of result.segments) {
      assert.ok(countCharacters(segment.text) <= 450);
    }
    assert.ok(result.segments.length > 1);
  });

  it('respects a very small maxChars limit', () => {
    const result = segmentText('abcdefghij', { maxChars: 3 });
    assert.ok(result.segments.length >= 4);
    for (const segment of result.segments) {
      assert.ok(countCharacters(segment.text) <= 3);
    }
  });
});

describe('mergeSegments', () => {
  const segments: Segment[] = [
    { index: 0, text: 'First.', trailing: '\n\n' },
    { index: 1, text: 'Second.', trailing: '\n\n' },
    { index: 2, text: 'Third.', trailing: '' },
  ];

  it('joins translated segments in order', () => {
    const merged = mergeSegments(segments, new Map([[0, 'الأول.'], [1, 'الثاني.'], [2, 'الثالث.']]));
    assert.equal(merged.text, 'الأول.\n\nالثاني.\n\nالثالث.');
    assert.deepEqual(merged.missingSegments, []);
  });

  it('falls back to the original text for an empty translation', () => {
    const merged = mergeSegments(segments, new Map([[0, 'الأول.'], [1, '   '], [2, 'الثالث.']]));
    assert.ok(merged.text.includes('Second.'), 'original text must be preserved');
    assert.deepEqual(merged.missingSegments, [1]);
    assert.deepEqual(merged.originalSegments, [1]);
  });

  it('falls back for missing entries', () => {
    const merged = mergeSegments(segments, new Map([[0, 'الأول.']]));
    assert.ok(merged.text.includes('Second.'));
    assert.ok(merged.text.includes('Third.'));
    assert.deepEqual(merged.missingSegments, [1, 2]);
  });

  it('accepts an array indexed by segment index', () => {
    const merged = mergeSegments(segments, ['الأول.', undefined, 'الثالث.']);
    assert.equal(merged.text, 'الأول.\n\nSecond.\n\nالثالث.');
    assert.deepEqual(merged.missingSegments, [1]);
  });

  it('returns empty text for no segments', () => {
    assert.deepEqual(mergeSegments([], new Map()), { text: '', missingSegments: [], originalSegments: [] });
  });

  it('round-trips segmentation and merge with unchanged text', () => {
    const segmented = segmentText(LONG, { maxChars: 120 });
    const identity = new Map<number, string>(segmented.segments.map((s) => [s.index, s.text]));
    const merged = mergeSegments(segmented.segments, identity);
    assert.equal(merged.missingSegments.length, 0);
    assert.equal(merged.text.replace(/\s+/g, ' ').trim(), LONG.replace(/\s+/g, ' ').trim());
  });
});