/**
 * Model benchmark CLI.
 *
 * The sequence is fixed and deliberate:
 *
 *   model identity -> readiness -> warm-up -> evaluation corpus -> quality metrics
 *                   -> latency -> throughput -> resource usage -> per-pair breakdown
 *
 * Warm-up happens before the corpus on purpose: the first request after a model
 * loads pays for weight paging, and including it would make every latency number
 * wrong in the same direction.
 *
 * Engines that cannot run are reported as SKIPPED with the reason and never appear
 * in the comparison table. A benchmark that quietly omits a model is worse than no
 * benchmark.
 *
 * Usage:
 *   node dist/src/scripts/benchmark.js
 *   node dist/src/scripts/benchmark.js --engines local-tg4,local-tg12,local-madlad3b
 *   node dist/src/scripts/benchmark.js --split dev --json bench.json
 */

import * as fs from 'node:fs/promises';

import { EngineRegistry } from '../engine/registry';
import { MyMemoryEngine } from '../engine/mymemory/engine';
import { DeepLEngine } from '../engine/deepl/engine';
import { ServingEngine, type ManagedEngine } from '../platform/serving/servingEngine';
import { captureProvenance } from '../platform/serving/provenance';
import { MODEL_CATALOG } from '../platform/serving/modelCatalog';
import { sampleResources } from '../platform/serving/resourceSampler';
import { assertReadyForBenchmark, detectGpu, detectRuntime, renderPreflight, runPreflight } from '../platform/serving/preflight';
import { loadDataset, defaultEvalRoot } from '../platform/eval/load';
import { filterDataset, type EvalSplit } from '../platform/eval/dataset';
import {
  runEval,
  renderSummary,
  renderModelComparison,
  renderComparison,
  loadJudgements,
  judgementsPath,
} from '../platform/benchmark/runner';
import type { BenchmarkReport } from '../platform/benchmark/runner';
import type { TranslationEngine } from '../engine/engine';

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index !== -1 ? process.argv[index + 1] : undefined;
}

function flag(name: string): boolean {
  return process.argv.includes(`--${name}`);
}

async function main(): Promise<void> {
  const datasetRoot = arg('eval-root') ?? defaultEvalRoot();
  const dataset = loadDataset(datasetRoot);
  const split = (arg('split') ?? 'test') as EvalSplit;
  const all = filterDataset(dataset, { split });
  const limitArg = arg('limit');
  const items = limitArg ? all.slice(0, Number(limitArg)) : all;
  const judgements = loadJudgements(judgementsPath(datasetRoot, split));

  const requested = (arg('engines') ?? MODEL_CATALOG.map((m) => m.id).join(',')).split(',').map((e) => e.trim());

  process.stdout.write(
    `dataset ${dataset.manifest.version} · split=${split} · ${items.length} item(s) · ` +
      `references human-verified: ${dataset.stats.humanVerified}/${dataset.stats.total}\n`,
  );

  const reports: BenchmarkReport[] = [];
  const registry = buildRegistry();
  const gateEnabled = !flag('no-preflight');
  const gpu = await detectGpu();
  const runtime = await detectRuntime();

  for (const engineId of requested) {
    if (gateEnabled) {
      // A benchmark on an unverified stack produces numbers that belong to no
      // model: unmeasured VRAM, an unpinned revision, a server that never answered.
      // The gate is on by default and is only bypassed deliberately.
      const spec = MODEL_CATALOG.find((m) => m.id === engineId);
      if (spec) {
        const probeEngine = registry.create(engineId) as ServingEngine;
        const preflight = await runPreflight({
          spec,
          ...(arg('revision') !== undefined ? { revision: arg('revision') } : {}),
          probes: {
            gpu: async () => gpu,
            runtime: async () => runtime,
            modelFiles: async () => ({
              present: false,
              detail:
                'weights are not checked by the benchmark; if the server loaded them, its readiness ' +
                'and identity checks below already prove it',
            }),
            warmUp: async () => {
              const readiness = await probeEngine.waitUntilReady(Number(arg('warmup-timeout-ms') ?? 60000));
              return readiness.ready
                ? probeEngine.warmUp()
                : { durationMs: readiness.waitedMs, loaded: false, detail: readiness.detail };
            },
            engine: probeEngine,
          },
        });
        process.stdout.write(renderPreflight(preflight) + '\n');
        try {
          assertReadyForBenchmark(preflight);
        } catch (error) {
          if (flag('force')) {
            process.stdout.write(`\n--force: benchmarking anyway. ${(error as Error).message}\n`);
          } else {
            process.stdout.write(`\n${(error as Error).message}\n\nNo benchmark was run.\n`);
            reports.push(
              skippedReport(engineId, `preflight not met: ${preflight.blocking.map((c) => c.id).join(', ')}`, dataset.manifest.version, split),
            );
            continue;
          }
        }
      }
    }
    reports.push(await runOne(engineId, registry, dataset.manifest.version, items, split, judgements));
  }

  process.stdout.write(renderSummary(reports) + '\n');
  process.stdout.write(renderModelComparison(reports) + '\n');

  const sampleCount = arg('show-samples');
  if (sampleCount) {
    for (const report of reports) {
      if (report.results.length > 0) {
        process.stdout.write(`\n--- samples: ${report.engine} ---\n`);
        process.stdout.write(renderComparison(report, Number(sampleCount)) + '\n');
      }
    }
  }

  const jsonPath = arg('json');
  if (jsonPath) {
    await fs.writeFile(jsonPath, JSON.stringify({ reports }, null, 2), 'utf8');
    process.stdout.write(`\nwrote ${jsonPath}\n`);
  }

  if (!flag('keep-serving')) {
    // Nothing was started by this script unless the operator supervises the
    // server, so there is nothing to tear down here; the note keeps that explicit.
    process.stdout.write('\nServers are external to this script. Stop them with the runbook in docs/model-serving-runbook.md.\n');
  }
}

function buildRegistry(): EngineRegistry {
  const registry = new EngineRegistry();

  for (const spec of MODEL_CATALOG) {
    registry.register(spec.id, () =>
      new ServingEngine({
        model: spec,
        endpoint: arg(`${spec.id}-endpoint`) ?? `http://127.0.0.1:${spec.defaultPort}`,
        ...(arg('revision') !== undefined ? { revision: arg('revision') } : {}),
        timeoutMs: Number(arg('timeout-ms') ?? 120000),
        concurrency: Number(arg('concurrency') ?? 1),
        maxBatchSize: Number(arg('batch-size') ?? 1),
        batchWindowMs: Number(arg('batch-window-ms') ?? 0),
        ...(arg('serving-style') !== undefined ? { servingStyle: arg('serving-style') as never } : {}),
        onLog: (message) => process.stdout.write(`  [${spec.id}] ${message}\n`),
      }),
    );
  }

  registry.register('mymemory', () => new MyMemoryEngine({ maxQueryChars: 450, minIntervalMs: 300 }));
  registry.register('deepl', () =>
    new DeepLEngine({ ...(process.env.DEEPL_API_KEY ? { apiKey: process.env.DEEPL_API_KEY } : {}) }),
  );
  return registry;
}

async function runOne(
  engineId: string,
  registry: EngineRegistry,
  datasetVersion: string,
  items: ReturnType<typeof filterDataset>,
  split: EvalSplit,
  judgements: ReturnType<typeof loadJudgements>,
): Promise<BenchmarkReport> {
  let engine: TranslationEngine;
  try {
    engine = registry.create(engineId);
  } catch (error) {
    return skippedReport(engineId, `engine could not be constructed: ${(error as Error).message}`, datasetVersion, split);
  }

  const managed = engine as unknown as ManagedEngine;

  // Step 1-2: identity and readiness, before any measurement.
  const identityNote = managed.identity
    ? `${managed.identity.modelId}@${managed.identity.revision} quant=${managed.identity.quantization}`
    : undefined;
  const health = engine.healthCheck ? await engine.healthCheck() : undefined;
  if (health && !health.healthy) {
    return skippedReport(engineId, health.detail ?? 'health check failed', datasetVersion, split, engine, identityNote);
  }

  // Step 3: warm-up.
  let warmUpMs: number | undefined;
  let readinessMs: number | undefined;
  if (managed.warmUp) {
    process.stdout.write(`warming up ${engineId}…\n`);
    const warm = await managed.warmUp();
    if (!warm.loaded) {
      return skippedReport(engineId, `warm-up did not complete: ${warm.detail}`, datasetVersion, split, engine, identityNote);
    }
    warmUpMs = warm.durationMs;
    const stats = managed.stats();
    readinessMs = stats.lastReadinessMs;
  }

  // Steps 4-8: corpus, quality, latency, throughput, resources.
  const concurrency = Number(arg('concurrency') ?? 1);
  const maxBatchSize = Number(arg('batch-size') ?? 1);
  const batchWindowMs = Number(arg('batch-window-ms') ?? 0);
  const serving = managed.identity
    ? {
        identity: identityNote ?? managed.identity.modelId,
        identityKey: managed.stats().identityKey,
        ...(warmUpMs !== undefined ? { warmUpMs } : {}),
        ...(readinessMs !== undefined ? { readinessMs } : {}),
        concurrency,
        maxBatchSize,
        batchWindowMs,
        state: managed.stats().state,
      }
    : undefined;

  const provenance = managed.identity
    ? await captureProvenance({
        identity: managed.identity,
        ...(arg('model-path') !== undefined ? { modelPath: arg('model-path') } : {}),
        servingStyle: managed.identity.servingStyle,
        concurrency,
        maxBatchSize,
        batchWindowMs,
        temperature: Number(arg('temperature') ?? 0),
        maxTokens: Number(arg('max-tokens') ?? 512),
        ...(arg('runtime-command') !== undefined ? { runtimeCommand: arg('runtime-command') } : {}),
      })
    : undefined;
  if (provenance) {
    process.stdout.write(`  [${engineId}] provenance: ${provenance.summary}\n`);
  }

  process.stdout.write(`running ${engineId} over ${items.length} item(s)…\n`);
  const { result: report, resources } = await sampleResources(
    () =>
      runEval({
        engine,
        items,
        datasetVersion,
        split,
        judgements,
        ...(serving ? { serving } : {}),
        ...(provenance ? { provenance } : {}),
        ...(arg('budget-ms') !== undefined ? { budgetMs: Number(arg('budget-ms')) } : {}),
      }),
  );

  const tokenStats = managed.stats?.().tokensPerSecond;
  if (tokenStats) {
    process.stdout.write(
      tokenStats.measured
        ? `  [${engineId}] tokens/sec (runtime-reported): ${tokenStats.tokensPerSecond}\n`
        : `  [${engineId}] tokens/sec: not measured — ${tokenStats.note}\n`,
    );
  }

  if (managed.shutdown) {
    const shutdown = await managed.shutdown(15000);
    process.stdout.write(`  [${engineId}] shutdown: ${shutdown.detail}\n`);
  }

  return { ...report, resources, ...(tokenStats ? { tokensPerSecond: tokenStats } : {}) };
}

function skippedReport(
  engineId: string,
  reason: string,
  datasetVersion: string,
  split: EvalSplit,
  engine?: TranslationEngine,
  _identity?: string,
): BenchmarkReport {
  const model = (engine as unknown as { model?: { modelId: string; modelVersion: string } } | undefined)?.model;
  return {
    engine: engineId,
    ...(model ? { modelId: model.modelId, modelVersion: model.modelVersion } : {}),
    datasetVersion,
    split,
    skipped: { reason },
    totals: { items: 0, ok: 0, failed: 0, skipped: 0 },
    aggregate: {
      count: 0,
      bleu: 0,
      chrf: 0,
      charSimilarity: 0,
      hasArabicRate: 0,
      digitsPreservedRate: 0,
      encodingCleanRate: 0,
      truncatedRate: 0,
    },
    latency: { count: 0, p50: 0, p95: 0, p99: 0, max: 0, mean: 0 },
    throughputPerSecond: 0,
    elapsedMs: 0,
    byPair: {},
    byCategory: {},
    human: { total: 0, reviewed: 0, byVerdict: {} },
    claimCeiling:
      'Not measured: the engine was skipped, so this report supports no claim about quality, ' +
      'latency, throughput or resource use.',
    supportsQualityClaim: false,
    results: [],
  };
}

main().catch((error: unknown) => {
  process.stderr.write(`benchmark failed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});
