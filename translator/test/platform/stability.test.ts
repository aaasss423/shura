/**
 * Stability measurement.
 *
 * Compares repeated runs of the same corpus. The assertions are about *what the
 * measurement reports*, not about any model's behaviour: no model has been run in
 * this repository.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { measureStability, parseRepeats, renderStability } from '../../src/platform/benchmark/stability';
import type { ItemResult } from '../../src/platform/benchmark/runner';

function ok(itemId: string, output: string, chrf: number): ItemResult {
  return {
    itemId,
    language: 'ja',
    category: 'dialogue',
    difficulty: 'medium',
    split: 'test',
    outcome: 'ok',
    sourceText: `src-${itemId}`,
    output,
    reference: `ref-${itemId}`,
    score: {
      bleu: 0,
      chrf,
      lengthRatio: 1,
      charSimilarity: chrf,
      hasArabic: true,
      digitsPreserved: true,
      encodingClean: true,
    },
    latencyMs: 10,
    attempts: 1,
  };
}

function failed(itemId: string): ItemResult {
  return {
    itemId,
    language: 'ja',
    category: 'dialogue',
    difficulty: 'medium',
    split: 'test',
    outcome: 'failed',
    sourceText: `src-${itemId}`,
    reference: `ref-${itemId}`,
    latencyMs: 10,
    attempts: 1,
    errorMessage: 'synthetic failure',
  };
}

describe('stability measurement', () => {
  it('reports agreement 1 for a bit-identical corpus across repeats', () => {
    const run = [ok('a', 'x', 0.5), ok('b', 'y', 0.7)];
    const report = measureStability([run, run, run]);
    assert.equal(report.repeats, 3);
    assert.equal(report.items, 2);
    assert.equal(report.agreementRatio, 1);
    assert.equal(report.chrF.spread, 0);
    assert.equal(report.flakyItems, 0);
    assert.equal(report.hardFailures, 0);
  });

  it('reports partial agreement when the model varies between runs', () => {
    const report = measureStability([
      [ok('a', 'x', 0.5), ok('b', 'y', 0.5)],
      [ok('a', 'x', 0.5), ok('b', 'DIFFERENT', 0.5)],
    ]);
    assert.equal(report.agreementRatio, 0.5);
    assert.match(report.note, /a distribution, not a point/);
  });

  it('gives the score spread as an error bar, not a single point', () => {
    const report = measureStability([
      [ok('a', 'x', 0.4), ok('b', 'x', 0.4)],
      [ok('a', 'x', 0.6), ok('b', 'x', 0.6)],
    ]);
    assert.deepEqual(report.chrFPerRepeat, [0.4, 0.6]);
    assert.equal(report.chrF.min, 0.4);
    assert.equal(report.chrF.max, 0.6);
    assert.equal(report.chrF.spread, 0.2);
  });

  it('separates intermittent failures from ones that always fail', () => {
    const report = measureStability([
      // 'flaky' fails only in the second repeat; 'dead' fails in both.
      [ok('a', 'x', 0.5), ok('flaky', 'y', 0.5), failed('dead')],
      [ok('a', 'x', 0.5), failed('flaky'), failed('dead')],
    ]);
    assert.equal(report.flakyItems, 1);
    assert.equal(report.hardFailures, 1);
    assert.deepEqual(report.failuresPerRepeat, [1, 2]);
    assert.equal(report.agreementRatio, 0.3333); // 1 of 3 items, rounded to 4dp
  });

  it('treats a missing item as a failure for that repeat, not as agreement', () => {
    // "It did not appear" and "it translated correctly" must not collapse.
    const report = measureStability([[ok('a', 'x', 0.5), ok('b', 'y', 0.5)], [ok('a', 'x', 0.5)]]);
    assert.equal(report.hardFailures, 0);
    assert.equal(report.flakyItems, 1);
    assert.equal(report.agreementRatio, 0.5);
    assert.deepEqual(report.failuresPerRepeat, [0, 1]);
  });

  it('does not count a repeated failure as agreement', () => {
    const report = measureStability([[failed('a')], [failed('a')]]);
    assert.equal(report.hardFailures, 1);
    assert.equal(report.agreementRatio, 0);
    assert.deepEqual(report.failuresPerRepeat, [1, 1]);
  });

  it('counts a failed repeat against an item that also succeeded once', () => {
    const report = measureStability([[ok('a', 'x', 0.5)], [failed('a')]]);
    assert.equal(report.flakyItems, 1);
    assert.deepEqual(report.failuresPerRepeat, [0, 1]);
  });

  it('says NOT MEASURED when no repeats were run', () => {
    const report = measureStability([]);
    assert.equal(report.repeats, 0);
    assert.match(report.note, /NOT MEASURED/);
    assert.equal(report.agreementRatio, 0);
  });

  it('handles a single repeat without inventing stability', () => {
    const report = measureStability([[ok('a', 'x', 0.5)]]);
    assert.equal(report.repeats, 1);
    assert.equal(report.agreementRatio, 1);
    assert.equal(report.chrF.spread, 0);
  });

  it('renders agreement, spread and failures per repeat', () => {
    const text = renderStability(
      measureStability([
        [ok('a', 'x', 0.4), ok('b', 'y', 0.4), ok('c', 'z', 0.4)],
        [ok('a', 'x', 0.6), ok('b', 'y', 0.6), failed('c')],
      ]),
    );
    assert.match(text, /agreement=0\.6667/);
    assert.match(text, /spread=0\.2/);
    assert.match(text, /failures per repeat = \[0, 1\]/);
    assert.match(text, /failed intermittently/);
  });
});

describe('repeat count parsing', () => {
  it('defaults to a single run when the flag is absent', () => {
    assert.equal(parseRepeats(undefined), 1);
  });

  it('accepts a positive integer', () => {
    assert.equal(parseRepeats('3'), 3);
    assert.equal(parseRepeats(' 12 '), 12);
  });

  it('refuses a value that is not a number instead of running zero times', () => {
    // Math.max(1, Number('abc')) is NaN: the run loop then never executes, and
    // `runs[runs.length - 1]!` returned undefined for a benchmark of nothing.
    assert.throws(() => parseRepeats('abc'), /must be a positive integer; got "abc"/);
    assert.throws(() => parseRepeats(''), /must be a positive integer/);
    assert.throws(() => parseRepeats('   '), /must be a positive integer/);
    assert.throws(() => parseRepeats('3x'), /must be a positive integer/);
  });

  it('refuses counts that are not whole, and counts below one', () => {
    assert.throws(() => parseRepeats('2.5'), /must be a positive integer/);
    assert.throws(() => parseRepeats('0'), /must be a positive integer/);
    assert.throws(() => parseRepeats('-4'), /must be a positive integer/);
    assert.throws(() => parseRepeats('Infinity'), /must be a positive integer/);
    assert.throws(() => parseRepeats('NaN'), /must be a positive integer/);
  });

  it('says no benchmark was run, rather than reporting one', () => {
    assert.throws(() => parseRepeats('0'), /No benchmark was run/);
  });

  it('never returns a value that could skip the run loop', () => {
    for (const raw of [undefined, '1', '2', '99']) {
      const repeats = parseRepeats(raw);
      assert.equal(Number.isInteger(repeats) && repeats >= 1, true, `${String(raw)} must be usable as a loop bound`);
      assert.deepEqual(Array.from({ length: repeats }, (_, i) => i).length, repeats);
    }
  });
});
