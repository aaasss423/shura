/**
 * Evaluation runner and benchmark harness.
 *
 * Runs a `TranslationEngine` over a dataset split, records the outputs, scores
 * them, and produces a report broken down per pair and per category.
 *
 * Two things it refuses to do:
 *  - cache anything, so a run measures the engine rather than the cache;
 *  - report a quality claim when the references are not human-verified. It
 *    prints the agreement figures and the ceiling that applies to them.
 *
 * Human judgement is a first-class input: a reviewer can mark a win/tie/loss per
 * item, and the report includes those marks. That file starts empty, so an
 * un-reviewed run reports zero human judgements rather than inventing any.
 */

import { readFileSync, existsSync } from 'node:fs';
import * as path from 'node:path';

import type { TranslationEngine } from '../../engine/engine';
import { CancellationToken } from '../../core/cancellation';
import { isTranslationError } from '../../core/errors';
import { LatencyHistogram } from '../metrics';
import type { EvalDataset, EvalItem, EvalSplit } from '../eval/dataset';
import { claimCeiling, provenanceOf, supportsQualityClaim } from '../eval/dataset';
import {
  aggregate,
  groupScores,
  scoreSegment,
  type AggregateScore,
  type SegmentScore,
} from '../eval/scoring';

export type ItemOutcome = 'ok' | 'failed' | 'skipped';

export interface ItemResult {
  itemId: string;
  language: string;
  category: string;
  difficulty: string;
  split: EvalSplit;
  outcome: ItemOutcome;
  sourceText: string;
  output?: string;
  reference: string;
  score?: SegmentScore;
  latencyMs: number;
  errorCode?: string;
  errorMessage?: string;
  attempts: number;
}

export type HumanVerdict = 'win' | 'tie' | 'loss' | 'unusable';

export interface HumanJudgement {
  itemId: string;
  verdict: HumanVerdict;
  reviewer: string;
  note?: string;
}

export interface PairReport {
  pair: string;
  aggregate: AggregateScore;
  human: { wins: number; ties: number; losses: number; unusable: number; reviewed: number };
  failed: number;
}

export interface CategoryReport {
  category: string;
  aggregate: AggregateScore;
  failed: number;
}

export interface BenchmarkReport {
  engine: string;
  modelId?: string;
  modelVersion?: string;
  datasetVersion: string;
  split: EvalSplit;
  /** Set when the engine could not be exercised at all. */
  skipped?: { reason: string };
  totals: {
    items: number;
    ok: number;
    failed: number;
    /** Items skipped because a prerequisite was missing. */
    skipped: number;
  };
  aggregate: AggregateScore;
  latency: { count: number; p50: number; p95: number; p99: number; max: number; mean: number };
  throughputPerSecond: number;
  elapsedMs: number;
  byPair: Record<string, PairReport>;
  byCategory: Record<string, CategoryReport>;
  human: { total: number; reviewed: number; byVerdict: Record<string, number> };
  /**
   * Serving conditions. Present when the engine is a managed local model.
   *
   * Recorded because a latency number without its serving conditions is not
   * reproducible: the same weights behind one concurrent slot and behind eight
   * are not the same measurement.
   */
  serving?: {
    identity: string;
    identityKey: string;
    warmUpMs?: number;
    readinessMs?: number;
    concurrency: number;
    maxBatchSize: number;
    batchWindowMs: number;
    state: string;
  };
  /**
   * What produced these numbers: weights digest, revision, build, GPU, sampling.
   *
   * Without it a score cannot be reproduced, and a comparison across two different
   * weight digests is a comparison of two different models.
   */
  provenance?: {
    summary: string;
    model: Array<{ label: string; value: string; source: string }>;
    host: Array<{ label: string; value: string; source: string }>;
    serving: Array<{ label: string; value: string; source: string }>;
  };
  /**
   * Decode rate from the runtime's own counter.
   *
   * `measured: false` is a legitimate, expected outcome — several servers report no
   * token count. It is never estimated from output length.
   */
  tokensPerSecond?: {
    measured: boolean;
    tokensPerSecond?: number;
    samples: number;
    predictedTokens?: number;
    note: string;
  };
  /** Measured on this host during the run, or an explicit "not measured". */
  resources?: {
    measured: boolean;
    source: string;
    note: string;
    peakMemoryUsedMb?: number;
    peakUtilisationPercent?: number;
    gpuName?: string;
    processPeakRssMb?: number;
  };
  /** The honest ceiling on what these numbers may be called. */
  claimCeiling: string;
  supportsQualityClaim: boolean;
  results: ItemResult[];
}

/**
 * Minimal dataset view needed for the claim ceiling, derived from the items
 * themselves so a run cannot claim more than its own inputs justify.
 */
function datasetOf(items: EvalItem[], version: string): EvalDataset {
  const humanVerified = items.filter((item) => provenanceOf(item) === 'human_verified').length;
  return {
    items,
    manifest: {
      version,
      created: '',
      description: 'derived from a run item list',
      languages: [...new Set(items.map((item) => item.sourceLanguage))],
      target_language: 'ar',
      splits: { dev: '', test: '' },
      reference_provenance: {},
      categories: [...new Set(items.map((item) => item.category))],
      leakage_policy: { holdout_enforcement: '', guarantees: [] },
    },
    stats: {
      version,
      total: items.length,
      byLanguage: {},
      bySplit: {},
      byCategory: {},
      humanVerified,
      aiDrafted: items.length - humanVerified,
    },
  };
}

export interface RunEvalOptions {
  engine: TranslationEngine;
  items: EvalItem[];
  datasetVersion: string;
  split: EvalSplit;
  /** Passed as engine id / hints when the engine supports them. */
  usePreviousText?: boolean;
  /** Cancellation for the whole run. */
  token?: CancellationToken;
  timeoutMs?: number;
  /** Wall clock cap for the whole run. */
  budgetMs?: number;
  /** Serving conditions to record alongside the numbers. */
  serving?: NonNullable<BenchmarkReport['serving']>;
  /** Resource usage observed on this host during the run. */
  resources?: BenchmarkReport['resources'];
  /** What produced these numbers. */
  provenance?: BenchmarkReport['provenance'];
  /** Runtime-reported decode rate, when one is available. */
  tokensPerSecond?: BenchmarkReport['tokensPerSecond'];
  onProgress?: (done: number, total: number) => void;
  judgements?: HumanJudgement[];
}

/**
 * Runs one engine over a set of items.
 *
 * A per-item failure is recorded and the run continues: a model that dies on one
 * category is a finding, not a reason to lose the other three languages.
 */
export async function runEval(options: RunEvalOptions): Promise<BenchmarkReport> {
  const { engine, items, datasetVersion, split } = options;
  const histogram = new LatencyHistogram();
  const results: ItemResult[] = [];
  const started = Date.now();
  const deadline = options.budgetMs ? started + options.budgetMs : Number.POSITIVE_INFINITY;

  for (const [index, item] of items.entries()) {
    const itemStarted = Date.now();
    const token = options.token ?? new CancellationToken();
    const perItemToken = CancellationToken.link([token]);

    if (Date.now() > deadline) {
      results.push({
        itemId: item.id,
        language: item.sourceLanguage,
        category: item.category,
        difficulty: item.difficulty,
        split: item.split,
        outcome: 'skipped',
        sourceText: item.sourceText,
        reference: item.referenceArabic,
        latencyMs: 0,
        attempts: 0,
        errorMessage: 'run budget exhausted before this item started',
      });
      continue;
    }

    try {
      const response = await engine.translate({
        text: item.sourceText,
        sourceLanguage: item.sourceLanguage,
        targetLanguage: item.targetLanguage,
        timeoutMs: options.timeoutMs ?? 120_000,
        signal: perItemToken.signal,
        ...(item.context ? { hints: { context: item.context } } : {}),
      });
      const latencyMs = Date.now() - itemStarted;
      histogram.observe(latencyMs);
      results.push({
        itemId: item.id,
        language: item.sourceLanguage,
        category: item.category,
        difficulty: item.difficulty,
        split: item.split,
        outcome: 'ok',
        sourceText: item.sourceText,
        output: response.text,
        reference: item.referenceArabic,
        score: scoreSegment(response.text, item.referenceArabic),
        latencyMs,
        attempts: 1,
      });
    } catch (error) {
      const latencyMs = Date.now() - itemStarted;
      results.push({
        itemId: item.id,
        language: item.sourceLanguage,
        category: item.category,
        difficulty: item.difficulty,
        split: item.split,
        outcome: 'failed',
        sourceText: item.sourceText,
        reference: item.referenceArabic,
        latencyMs,
        attempts: 1,
        ...(isTranslationError(error) ? { errorCode: error.code } : {}),
        errorMessage: error instanceof Error ? error.message : String(error),
      });
    }

    options.onProgress?.(index + 1, items.length);
  }

  return buildReport({
    engineId: engine.id,
    datasetVersion,
    split,
    results,
    histogram,
    elapsedMs: Date.now() - started,
    claimCeiling: claimCeiling(datasetOf(items, datasetVersion)),
    supportsQualityClaim: supportsQualityClaim(datasetOf(items, datasetVersion)),
    ...(options.judgements ? { judgements: options.judgements } : {}),
    ...(options.serving ? { serving: options.serving } : {}),
    ...(options.resources ? { resources: options.resources } : {}),
    ...(options.provenance ? { provenance: options.provenance } : {}),
    ...(options.tokensPerSecond ? { tokensPerSecond: options.tokensPerSecond } : {}),
    ...(engineConfigurationOf(engine)),
  });
}

function engineConfigurationOf(engine: TranslationEngine): { modelId?: string; modelVersion?: string } {
  const candidate = engine as unknown as { model?: { modelId: string; modelVersion: string } };
  return candidate.model ? { ...candidate.model } : {};
}

/** Builds a report from raw item results, so scoring can be re-run offline. */
export function buildReport(input: {
  engineId: string;
  modelId?: string;
  modelVersion?: string;
  datasetVersion: string;
  split: EvalSplit;
  results: ItemResult[];
  histogram: LatencyHistogram;
  elapsedMs: number;
  judgements?: HumanJudgement[];
  claimCeiling: string;
  supportsQualityClaim: boolean;
  serving?: BenchmarkReport['serving'];
  resources?: BenchmarkReport['resources'];
  provenance?: BenchmarkReport['provenance'];
  tokensPerSecond?: BenchmarkReport['tokensPerSecond'];
  skipped?: { reason: string };
}): BenchmarkReport {
  const scored = input.results
    .filter((r): r is ItemResult & { score: SegmentScore } => r.outcome === 'ok' && r.score !== undefined)
    .map((r) => ({ ...r.score, language: r.language, category: r.category }));

  const byLanguage = groupScores(scored, 'language');
  const byCategory = groupScores(scored, 'category');

  const judgements = input.judgements ?? [];
  const byVerdict: Record<string, number> = { win: 0, tie: 0, loss: 0, unusable: 0 };
  for (const judgement of judgements) {
    byVerdict[judgement.verdict] = (byVerdict[judgement.verdict] ?? 0) + 1;
  }
  const resultById = new Map(input.results.map((r) => [r.itemId, r]));

  const byPair: Record<string, PairReport> = {};
  for (const [pair, pairScore] of Object.entries(byLanguage)) {
    const language = pair.replace('->ar', '');
    const pairResults = input.results.filter((r) => r.language === language);
    const pairJudgements = judgements.filter((j) => resultById.get(j.itemId)?.language === language);
    byPair[pair] = {
      pair,
      aggregate: pairScore,
      human: {
        wins: pairJudgements.filter((j) => j.verdict === 'win').length,
        ties: pairJudgements.filter((j) => j.verdict === 'tie').length,
        losses: pairJudgements.filter((j) => j.verdict === 'loss').length,
        unusable: pairJudgements.filter((j) => j.verdict === 'unusable').length,
        reviewed: pairJudgements.length,
      },
      failed: pairResults.filter((r) => r.outcome === 'failed').length,
    };
  }

  const byCategoryReport: Record<string, CategoryReport> = {};
  for (const [category, categoryScore] of Object.entries(byCategory)) {
    byCategoryReport[category] = {
      category,
      aggregate: categoryScore,
      failed: input.results.filter((r) => r.category === category && r.outcome === 'failed').length,
    };
  }

  const ok = input.results.filter((r) => r.outcome === 'ok').length;
  const failed = input.results.filter((r) => r.outcome === 'failed').length;
  const skipped = input.results.filter((r) => r.outcome === 'skipped').length;
  const seconds = Math.max(0.001, input.elapsedMs / 1000);

  return {
    engine: input.engineId,
    ...(input.modelId ? { modelId: input.modelId } : {}),
    ...(input.modelVersion ? { modelVersion: input.modelVersion } : {}),
    datasetVersion: input.datasetVersion,
    split: input.split,
    ...(input.skipped ? { skipped: input.skipped } : {}),
    totals: { items: input.results.length, ok, failed, skipped },
    aggregate: aggregate(scored),
    latency: input.histogram.summary(),
    // Throughput over ok items only: a failing model is not fast.
    throughputPerSecond: Math.round((ok / seconds) * 100) / 100,
    elapsedMs: input.elapsedMs,
    byPair,
    byCategory: byCategoryReport,
    human: {
      total: judgements.length,
      reviewed: judgements.length,
      byVerdict,
    },
    ...(input.serving ? { serving: input.serving } : {}),
    ...(input.resources ? { resources: input.resources } : {}),
    ...(input.provenance ? { provenance: input.provenance } : {}),
    ...(input.tokensPerSecond ? { tokensPerSecond: input.tokensPerSecond } : {}),
    claimCeiling: input.claimCeiling,
    supportsQualityClaim: input.supportsQualityClaim,
    results: input.results,
  };
}

/** Human judgement store, persisted next to the dataset. */
export function loadJudgements(file: string): HumanJudgement[] {
  if (!existsSync(file)) {
    return [];
  }
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8')) as unknown;
    return Array.isArray(parsed) ? (parsed as HumanJudgement[]) : [];
  } catch {
    return [];
  }
}

export function judgementsPath(datasetRoot: string, split: EvalSplit): string {
  return path.join(datasetRoot, `judgements.${split}.json`);
}

/** Side-by-side text, for a human reviewer. Always printed, never summarised. */
export function renderComparison(report: BenchmarkReport, limit = Number.POSITIVE_INFINITY): string {
  const lines: string[] = [];
  const scored = report.results.filter((r) => r.outcome === 'ok');
  for (const item of scored.slice(0, limit)) {
    lines.push('');
    lines.push(`[${item.itemId}] ${item.language} / ${item.category} / ${item.difficulty}`);
    lines.push(`  src: ${item.sourceText}`);
    lines.push(`  ref: ${item.reference}`);
    lines.push(`  out: ${item.output}`);
    if (item.score) {
      lines.push(
        `  chrF=${item.score.chrf} BLEU=${item.score.bleu} sim=${item.score.charSimilarity} len=${item.score.lengthRatio}`,
      );
    }
  }
  return lines.join('\n');
}

/** Human-readable summary. Never prints a single overall "quality" number. */
export function renderSummary(reports: BenchmarkReport[]): string {
  const lines: string[] = [];
  for (const report of reports) {
    lines.push('');
    lines.push(`=== ${report.engine}${report.modelVersion ? ` (${report.modelVersion})` : ''} · split=${report.split} · dataset=${report.datasetVersion} ===`);
    if (report.skipped) {
      lines.push(`  SKIPPED: ${report.skipped.reason}`);
      continue;
    }
    lines.push(
      `  items: ${report.totals.items} ok=${report.totals.ok} failed=${report.totals.failed} skipped=${report.totals.skipped}`,
    );
    lines.push(
      `  chrF=${report.aggregate.chrf} BLEU=${report.aggregate.bleu} sim=${report.aggregate.charSimilarity} ` +
        `arabic=${report.aggregate.hasArabicRate} digits=${report.aggregate.digitsPreservedRate} ` +
        `truncated=${report.aggregate.truncatedRate}`,
    );
    lines.push(
      `  latency ms: p50=${report.latency.p50} p95=${report.latency.p95} p99=${report.latency.p99} · ` +
        `throughput=${report.throughputPerSecond}/s`,
    );
    if (report.serving) {
      const serving = report.serving;
      lines.push(
        `  serving: ${serving.identity} · concurrency=${serving.concurrency} ` +
          `batch=${serving.maxBatchSize}@${serving.batchWindowMs}ms state=${serving.state}` +
          (serving.warmUpMs !== undefined ? ` · warmUp=${serving.warmUpMs}ms` : ' · warmUp=NOT MEASURED') +
          (serving.readinessMs !== undefined ? ` · readiness=${serving.readinessMs}ms` : ''),
      );
    }
    if (report.provenance) {
      lines.push(`  provenance: ${report.provenance.summary}`);
    }
    if (report.tokensPerSecond) {
      const tps = report.tokensPerSecond;
      lines.push(
        tps.measured
          ? `  tokens/sec: ${tps.tokensPerSecond} (mean of ${tps.samples} runtime-reported sample(s))` +
            (tps.predictedTokens !== undefined ? `, ${tps.predictedTokens} predicted token(s)` : '')
          : `  tokens/sec: NOT MEASURED — ${tps.note}`,
      );
    }
    if (report.resources) {
      const resources = report.resources;
      lines.push(
        resources.measured
          ? `  resources: peak GPU ${resources.peakMemoryUsedMb}MB on ${resources.gpuName} ` +
              `(util ${resources.peakUtilisationPercent}%), process RSS ${resources.processPeakRssMb}MB · ${resources.note}`
          : `  resources: NOT MEASURED — ${resources.note}`,
      );
    }
    if (report.human.reviewed > 0) {
      lines.push(
        `  human: reviewed=${report.human.reviewed} ${JSON.stringify(report.human.byVerdict)}`,
      );
    } else {
      lines.push('  human: no judgements recorded (human review not performed)');
    }
    for (const [pair, pairReport] of Object.entries(report.byPair)) {
      lines.push(
        `  ${pair.padEnd(8)} chrF=${pairReport.aggregate.chrf} BLEU=${pairReport.aggregate.bleu} ` +
          `arabic=${pairReport.aggregate.hasArabicRate} n=${pairReport.aggregate.count} failed=${pairReport.failed}`,
      );
    }
    lines.push(`  claim ceiling: ${report.claimCeiling}`);
  }
  return lines.join('\n');
}

export { claimCeiling, supportsQualityClaim };
/**
 * Side-by-side comparison.
 *
 * Only engines that actually ran appear in the table. A skipped engine is listed
 * separately so a reader cannot mistake "not present" for "not attempted".
 */
export function renderModelComparison(reports: BenchmarkReport[]): string {
  const ran = reports.filter((r) => !r.skipped);
  const skipped = reports.filter((r) => r.skipped);
  const lines: string[] = ['', '=== model comparison (manga/manhwa evaluation corpus) ==='];

  if (ran.length === 0) {
    lines.push('  no engine produced results, so there is nothing to compare.');
  } else {
    lines.push(
      '  ' +
        ['engine', 'n', 'chrF', 'BLEU', 'p50ms', 'p95ms', '/s', 'tok/s', 'GPU MB', 'fail']
          .map((h) => h.padEnd(10))
          .join(''),
    );
    for (const report of [...ran].sort((a, b) => b.aggregate.chrf - a.aggregate.chrf)) {
      const gpu = report.resources?.measured ? String(report.resources.peakMemoryUsedMb ?? '?') : 'n/a';
      const tok = report.tokensPerSecond?.measured ? String(report.tokensPerSecond.tokensPerSecond) : 'n/a';
      lines.push(
        '  ' +
          [
            report.engine,
            String(report.aggregate.count),
            String(report.aggregate.chrf),
            String(report.aggregate.bleu),
            String(report.latency.p50),
            String(report.latency.p95),
            String(report.throughputPerSecond),
            tok,
            gpu,
            `${report.totals.failed}`,
          ]
            .map((c) => c.padEnd(10))
            .join(''),
      );
    }

    // Two runs with different weight digests are two different models; the table
    // must say so rather than let the reader assume otherwise.
    lines.push('', '  weights (a comparison across digests is a comparison of different models):');
    for (const report of ran) {
      const digest = report.provenance?.model.find((f) => f.label === 'weights')?.value ?? 'not recorded';
      lines.push(`    ${report.engine}: ${digest}`);
    }

    lines.push('', '  per language pair (chrF):');
    const pairs = [...new Set(ran.flatMap((r) => Object.keys(r.byPair)))].sort();
    lines.push('  ' + ['engine', ...pairs].map((h) => h.padEnd(12)).join(''));
    for (const report of ran) {
      lines.push(
        '  ' +
          [report.engine, ...pairs.map((p) => String(report.byPair[p]?.aggregate.chrf ?? '-'))]
            .map((c) => c.padEnd(12))
            .join(''),
      );
    }

    lines.push('', '  per category (chrF):');
    const categories = [...new Set(ran.flatMap((r) => Object.keys(r.byCategory)))].sort();
    lines.push('  ' + ['engine', ...categories].map((h) => h.padEnd(12)).join(''));
    for (const report of ran) {
      lines.push(
        '  ' +
          [report.engine, ...categories.map((c) => String(report.byCategory[c]?.aggregate.chrf ?? '-'))]
            .map((c) => c.padEnd(12))
            .join(''),
      );
    }
  }

  if (skipped.length > 0) {
    lines.push('', '  skipped (no numbers exist for these):');
    for (const report of skipped) {
      lines.push(`    ${report.engine}: ${report.skipped!.reason}`);
    }
  }
  return lines.join('\n');
}
