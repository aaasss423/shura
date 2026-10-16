/**
 * Stability measurement.
 *
 * "It worked once" is not stability. A model at temperature 0 can still vary
 * between runs, because batch composition changes the reduction order in the
 * kernels. A number that cannot be reproduced twice is not a baseline, so this
 * measures it instead of assuming it.
 *
 * Two things are measured, both from real output:
 *
 *  - **agreement**: how often the same item produced byte-identical output across
 *    repeats. A model that is not bit-stable is still usable, but its score is a
 *    distribution rather than a point, and that has to be visible.
 *  - **score spread**: chrF min/max across repeats. The spread is the honest error
 *    bar on a single-run comparison.
 *
 * Nothing here is inferred from timing. It compares outputs.
 */

import type { ItemResult } from './runner';

export interface StabilityReport {
  repeats: number;
  items: number;
  /** Items whose output was identical in every repeat, 0..1. */
  agreementRatio: number;
  /** chrF of the whole corpus per repeat. */
  chrFPerRepeat: number[];
  chrF: { min: number; max: number; spread: number };
  /** Items that failed in at least one repeat but not all. */
  flakyItems: number;
  /** Items that failed in every repeat. */
  hardFailures: number;
  failuresPerRepeat: number[];
  /** Wording for the report; states what was and was not observed. */
  note: string;
}

/**
 * Compares repeated runs of the same item set.
 *
 * Items are matched by id. An item absent from one repeat is treated as a failure
 * for that repeat rather than skipped, because "it did not appear" and "it
 * translated correctly" must not collapse into the same outcome.
 */
export function measureStability(repeats: Array<Array<ItemResult>>): StabilityReport {
  const runCount = repeats.length;
  if (runCount === 0) {
    return {
      repeats: 0,
      items: 0,
      agreementRatio: 0,
      chrFPerRepeat: [],
      chrF: { min: 0, max: 0, spread: 0 },
      flakyItems: 0,
      hardFailures: 0,
      failuresPerRepeat: [],
      note: 'no repeats were run, so stability is NOT MEASURED',
    };
  }

  const allIds = [...new Set(repeats.flatMap((run) => run.map((r) => r.itemId)))].sort();

  // Denominator is the union of item ids, not what a repeat happened to return.
  // An item missing from a repeat did not translate correctly; counting only the
  // rows present would report a clean run for a corpus that was half skipped.
  const failuresPerRepeat = repeats.map(
    (run) => allIds.filter((id) => run.find((r) => r.itemId === id)?.outcome !== 'ok').length,
  );
  const chrFPerRepeat = repeats.map((run) => {
    const scored = run.filter((r) => r.outcome === 'ok' && r.score !== undefined);
    if (scored.length === 0) {
      return 0;
    }
    const mean = scored.reduce((sum, r) => sum + (r.score?.chrf ?? 0), 0) / scored.length;
    return round4(mean);
  });

  let identical = 0;
  let flaky = 0;
  let hardFailed = 0;

  for (const id of allIds) {
    const outputs: string[] = [];
    let okCount = 0;
    for (const run of repeats) {
      const result = run.find((r) => r.itemId === id);
      if (result && result.outcome === 'ok') {
        okCount += 1;
        outputs.push(result.output ?? '');
      }
    }
    if (okCount === 0) {
      hardFailed += 1;
      continue;
    }
    if (okCount < runCount) {
      flaky += 1;
      continue;
    }
    if (outputs.every((o) => o === outputs[0])) {
      identical += 1;
    }
  }

  const comparable = allIds.length;
  const agreementRatio = comparable === 0 ? 0 : round4(identical / comparable);
  const min = chrFPerRepeat.length === 0 ? 0 : Math.min(...chrFPerRepeat);
  const max = chrFPerRepeat.length === 0 ? 0 : Math.max(...chrFPerRepeat);

  return {
    repeats: runCount,
    items: comparable,
    agreementRatio,
    chrFPerRepeat,
    chrF: { min, max, spread: round4(max - min) },
    flakyItems: flaky,
    hardFailures: hardFailed,
    failuresPerRepeat,
    note:
      `agreement is byte-identical output across ${runCount} repeat(s) of the same corpus. ` +
      'A ratio below 1 means the score is a distribution, not a point; the chrF spread is the error bar.',
  };
}

/**
 * Parses the `--repeat` argument.
 *
 * A repeat count is a positive integer, and anything else is refused rather than
 * coerced. Coercion here is not a rounding detail: `Math.max(1, Number('abc'))` is
 * `NaN`, the run loop then executes zero times, and the benchmark reports on a run
 * that never happened — with the `!` on the last run lying to make it typecheck.
 */
export function parseRepeats(raw: string | undefined): number {
  if (raw === undefined) {
    return 1;
  }
  const value = Number(raw.trim());
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(
      `--repeat must be a positive integer; got ${JSON.stringify(raw)}. ` +
        'No benchmark was run, because a run that does not match the requested repeat count would be misreported.',
    );
  }
  return value;
}

export function renderStability(report: StabilityReport): string {
  const lines = [
    `  stability: ${report.repeats} repeat(s) · agreement=${report.agreementRatio} · ` +
      `chrF min=${report.chrF.min} max=${report.chrF.max} spread=${report.chrF.spread}`,
  ];
  if (report.flakyItems > 0 || report.hardFailures > 0) {
    lines.push(
      `  stability: ${report.flakyItems} item(s) failed intermittently, ` +
        `${report.hardFailures} failed in every repeat`,
    );
  }
  if (report.failuresPerRepeat.length > 1) {
    lines.push(`  stability: failures per repeat = [${report.failuresPerRepeat.join(', ')}]`);
  }
  return lines.join('\n');
}

function round4(value: number): number {
  return Math.round(value * 10000) / 10000;
}
