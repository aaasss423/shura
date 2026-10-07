/**
 * Pre-benchmark gate CLI.
 *
 * Checks the nine preconditions for one model and prints the evidence for each.
 * Exits non-zero when the stack is not ready, so it can be used in a shell
 * pipeline or CI step before a benchmark.
 *
 * Usage:
 *   node dist/src/scripts/preflight.js --model local-tg4
 *   node dist/src/scripts/preflight.js --all
 */

import { MODEL_CATALOG, getModelSpec } from '../platform/serving/modelCatalog';
import { ServingEngine } from '../platform/serving/servingEngine';
import {
  detectGpu,
  detectRuntime,
  renderPreflight,
  runPreflight,
} from '../platform/serving/preflight';
import { access, constants } from 'node:fs/promises';

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index !== -1 ? process.argv[index + 1] : undefined;
}

async function main(): Promise<void> {
  const requested = arg('all') !== undefined ? MODEL_CATALOG.map((m) => m.id) : [arg('model') ?? 'local-tg4'];
  const revision = arg('revision');
  const gpu = await detectGpu();
  const runtime = await detectRuntime();
  const results = [];

  for (const id of requested) {
    const spec = getModelSpec(id);
    const modelDir = arg(`${id}-models`) ?? `./models/${spec.modelId}`;
    const engine = new ServingEngine({
      model: spec,
      endpoint: arg(`${id}-endpoint`) ?? `http://127.0.0.1:${spec.defaultPort}`,
      ...(revision !== undefined ? { revision } : {}),
      ...(arg('serving-style') !== undefined ? { servingStyle: arg('serving-style') as never } : {}),
      readinessTimeoutMs: Number(arg('readiness-timeout-ms') ?? 30000),
      onLog: (message) => process.stdout.write(`  [${id}] ${message}\n`),
    });

    const result = await runPreflight({
      spec,
      ...(revision !== undefined ? { revision } : {}),
      probes: {
        gpu: async () => gpu,
        runtime: async () => runtime,
        // Bounded independently of the engine's production readiness budget.
        warmUp: async () => {
          const budget = Number(arg('warmup-timeout-ms') ?? 30000);
          const readiness = await engine.waitUntilReady(budget);
          return readiness.ready
            ? engine.warmUp()
            : { durationMs: readiness.waitedMs, loaded: false, detail: readiness.detail };
        },
        modelFiles: async () => {
          try {
            await access(modelDir, constants.R_OK);
            return { present: true, detail: `model directory readable: ${modelDir}` };
          } catch {
            return {
              present: false,
              detail:
                `no readable weights at ${modelDir}. Either place them there or pass ` +
                `--${id}-models <dir>. If the serving runtime manages its own cache (llama-server -hf), ` +
                'confirm the download succeeded before continuing.',
            };
          }
        },
        engine,
      },
    });
    results.push(result);
    process.stdout.write(renderPreflight(result) + '\n');
  }

  const blocked = results.filter((r) => !r.ready);
  process.stdout.write('\nverdict\n');
  process.stdout.write('-------\n');
  if (blocked.length === 0) {
    process.stdout.write(`  all ${results.length} model(s) passed every precondition.\n`);
    process.stdout.write(`  next: node dist/src/scripts/benchmark.js --engines ${requested.join(',')} --split test\n`);
    return;
  }
  for (const result of blocked) {
    process.stdout.write(`  ${result.model}: NOT READY\n`);
    for (const check of result.blocking) {
      process.stdout.write(`    ${check.status.padEnd(7)} ${check.id}\n`);
    }
  }
  process.stdout.write('\n  No benchmark was run and no model was measured. Fix the above first.\n');
  process.exitCode = 1;
}

main().catch((error: unknown) => {
  process.stderr.write(`preflight failed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});
