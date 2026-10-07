/**
 * Serving CLI.
 *
 * Starts an inference server for one model, waits for real readiness, warms it up,
 * and stays up until interrupted. This is the piece that removes the blocker on a
 * GPU host: one command per candidate instead of a terminal juggling a child
 * process and a benchmark.
 *
 * It never downloads weights and never pretends a model loaded. If the runtime is
 * missing or the server does not become ready, it exits non-zero with the reason.
 *
 * Usage:
 *   node dist/src/scripts/serve.js --model local-tg4
 *   node dist/src/scripts/serve.js --model local-madlad3b --models ./models/madlad400-3b-mt
 *   node dist/src/scripts/serve.js --model local-tg4 --readiness-timeout-ms 600000
 */

import { ServingEngine } from '../platform/serving/servingEngine';
import { ModelServerSupervisor } from '../platform/serving/supervisor';
import { detectGpu, detectRuntime, renderPreflight, runPreflight } from '../platform/serving/preflight';
import { getModelSpec } from '../platform/serving/modelCatalog';
import { assertIdentity, identityFromSpec } from '../platform/serving/modelIdentity';
import { access, constants } from 'node:fs/promises';

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index !== -1 ? process.argv[index + 1] : undefined;
}

const log = (message: string): void => {
  process.stdout.write(`[serve] ${message}\n`);
};

async function main(): Promise<void> {
  const spec = getModelSpec(arg('model') ?? 'local-tg4');
  const endpoint = arg('endpoint') ?? `http://127.0.0.1:${spec.defaultPort}`;
  const revision = arg('revision');
  const modelsDir = arg('models') ?? `./models/${spec.modelId}`;

  const gpu = await detectGpu();
  const runtime = await detectRuntime();
  log(`host: ${gpu.present ? gpu.detail : 'no GPU'}`);
  log(`runtime: ${runtime.present ? runtime.detail : 'NOT INSTALLED'}`);

  const engine = new ServingEngine({
    model: spec,
    endpoint,
    ...(revision !== undefined ? { revision } : {}),
    ...(arg('serving-style') !== undefined ? { servingStyle: arg('serving-style') as never } : {}),
    concurrency: Number(arg('concurrency') ?? 1),
    maxBatchSize: Number(arg('batch-size') ?? 1),
    batchWindowMs: Number(arg('batch-window-ms') ?? 0),
    readinessTimeoutMs: Number(arg('readiness-timeout-ms') ?? 600000),
    onLog: log,
  });

  const preflight = await runPreflight({
    spec,
    ...(revision !== undefined ? { revision } : {}),
    probes: {
      gpu: async () => gpu,
      runtime: async () => runtime,
      modelFiles: async () => {
        try {
          await access(modelsDir, constants.R_OK);
          return { present: true, detail: `weights readable at ${modelsDir}` };
        } catch {
          return { present: false, detail: `no readable weights at ${modelsDir}` };
        }
      },
      // Bounded: the engine's production readiness budget is minutes, and a gate
      // that blocks for minutes is a gate nobody runs before a benchmark.
      warmUp: async () => {
        const budget = Number(arg('preflight-warmup-ms') ?? 45000);
        const readiness = await engine.waitUntilReady(budget);
        if (!readiness.ready) {
          return { durationMs: readiness.waitedMs, loaded: false, detail: readiness.detail };
        }
        return engine.warmUp();
      },
      engine,
    },
  });
  process.stdout.write(renderPreflight(preflight) + '\n');

  if (!preflight.ready) {
    process.stdout.write(
      '\n[serve] not starting: the stack failed preflight. The details above are the actual blockers.\n',
    );
    process.exitCode = 1;
    return;
  }

  const command = arg('command') ?? runtime.command ?? 'llama-server';
  const args = arg('args')
    ? arg('args')!.split(' ').filter(Boolean)
    : spec.install.join(' ').split(' ').filter(Boolean);

  const supervisor = new ModelServerSupervisor(
    {
      spec,
      endpoint,
      command,
      args,
      ...(arg('cwd') !== undefined ? { cwd: arg('cwd') } : {}),
      readinessTimeoutMs: Number(arg('readiness-timeout-ms') ?? 600000),
      onLog: log,
    },
    async (target) => {
      const probe = await engine.waitUntilReady(2000);
      void target;
      return { ready: probe.ready, detail: probe.detail };
    },
  );

  const start = await supervisor.start();
  log(`supervisor: ${start.state} — ${start.detail}`);
  if (start.state !== 'ready') {
    process.exitCode = 1;
    return;
  }

  const health = await engine.healthCheck();
  log(`health: ${health.healthy ? 'ok' : 'FAILED'} — ${health.detail ?? ''}`);
  if (!health.healthy) {
    await supervisor.stop();
    process.exitCode = 1;
    return;
  }

  const identity = identityFromSpec(spec, { ...(revision !== undefined ? { revision } : {}) });
  const stats = engine.stats();
  log(`identity key: ${stats.identityKey}`);
  try {
    assertIdentity(identity, { servedAs: identity.modelId });
  } catch (error) {
    log(`identity check failed: ${(error as Error).message}`);
  }

  const warm = await engine.warmUp();
  log(`warm-up: ${warm.loaded ? 'ok' : 'FAILED'} — ${warm.detail}`);
  if (!warm.loaded) {
    await supervisor.stop();
    process.exitCode = 1;
    return;
  }

  log(`READY. ${spec.displayName} on ${endpoint}. Keep this process running; benchmark against it with:`);
  log(`  node dist/src/scripts/benchmark.js --engines ${spec.id} --split test`);

  let shuttingDown = false;
  const shutdown = async (reason: string): Promise<void> => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    log(`shutting down (${reason})`);
    await engine.shutdown(15000);
    const stop = await supervisor.stop();
    log(`supervisor stopped: ${stop.detail}`);
    process.exit(0);
  };
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));

  // Stay up. The interval also surfaces a server that died while idle.
  const heartbeat = setInterval(() => {
    void engine.healthCheck().then((current) => {
      if (!current.healthy) {
        void shutdown(`health check failed: ${current.detail ?? 'no detail'}`);
      }
    });
  }, 30000);
  heartbeat.unref();
  await new Promise(() => undefined);
}

main().catch((error: unknown) => {
  process.stderr.write(`serve failed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});
