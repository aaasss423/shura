/**
 * Pre-benchmark gate.
 *
 * Nine conditions must hold before a model number means anything. Each is checked
 * by observing the host, and each returns the evidence it observed — not a boolean
 * that hides why.
 *
 * `blocked` and `fail` are deliberately different:
 *
 *  - `fail`    the host answered and the answer was wrong (server up, wrong model)
 *  - `blocked` this host cannot answer the question at all (no GPU, no nvidia-smi)
 *
 * A blocked environment must not be reported as a failed model, and must not be
 * reported as a passing one either. `readyForBenchmark()` returns false for both,
 * so a benchmark cannot be launched on an unverified stack.
 *
 * Every probe is injectable, so this file is testable without a GPU and its logic
 * can be proven against a mock without any risk of being mistaken for a real run.
 */

import { access, constants } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import * as os from 'node:os';

import { detectNvidiaSmi, parseNvidiaRow } from './resourceSampler';
import { isPinned, type ModelIdentity } from './modelIdentity';
import type { ModelSpec } from './modelCatalog';
import type { ServingEngine } from './servingEngine';

export type PreflightStatus = 'pass' | 'fail' | 'blocked';

export interface PreflightCheck {
  id: string;
  title: string;
  status: PreflightStatus;
  /** What was actually observed, including the command or endpoint used. */
  evidence: string;
  /** Whether the benchmark may not run while this is not `pass`. */
  blocking: boolean;
}

export interface PreflightResult {
  model: string;
  checks: PreflightCheck[];
  ready: boolean;
  blocking: PreflightCheck[];
  /** Human-readable blockers, ready to print. */
  summary: string;
}

export interface GpuProbe {
  present: boolean;
  detail: string;
  name?: string;
  memoryTotalMb?: number;
  deviceNodes?: string[];
}

export interface RuntimeProbe {
  present: boolean;
  detail: string;
  command?: string;
  version?: string;
}

export interface PreflightProbes {
  gpu?: () => Promise<GpuProbe>;
  runtime?: () => Promise<RuntimeProbe>;
  /** Model files on disk, for a pinned revision. */
  modelFiles?: () => Promise<{ present: boolean; detail: string }>;
  /**
   * Warm-up probe.
   *
   * Injectable because the engine's own `warmUp()` uses the production readiness
   * budget, which is minutes. A preflight check must be bounded, or a gate meant
   * to be fast becomes the slowest step in the runbook.
   */
  warmUp?: () => Promise<{ durationMs: number; loaded: boolean; detail: string }>;
  engine: ServingEngine;
}

export interface PreflightOptions {
  spec: ModelSpec;
  /** Revision the operator intends to measure. Undefined means unpinned. */
  revision?: string;
  quantization?: string;
  probes: PreflightProbes;
  /** Set true when the engine under test is a mock; recorded on the result. */
  mock?: boolean;
}

/**
 * The nine preconditions, in the order they must be settled.
 *
 * Ordered by dependency: there is no point probing a server that cannot exist
 * because there is no GPU, and no point measuring latency on weights that are not
 * on disk. The order is also the order an operator should debug in.
 */
export async function runPreflight(options: PreflightOptions): Promise<PreflightResult> {
  const { spec, probes } = options;
  const engine = probes.engine;
  const identity: ModelIdentity = engine.identity;
  const checks: PreflightCheck[] = [];

  const gpu = await (probes.gpu ?? detectGpu)();
  checks.push({
    id: 'gpu.detected',
    title: 'GPU detected',
    status: gpu.present ? 'pass' : 'blocked',
    evidence: gpu.detail,
    blocking: true,
  });

  const requiredMb = Math.round(spec.requirements.vramGbTypical * 1024);
  if (!gpu.present) {
    checks.push({
      id: 'gpu.vram',
      title: `VRAM sufficient (>= ${spec.requirements.vramGbTypical}GB published)`,
      status: 'blocked',
      evidence: 'cannot be determined: no GPU was detected on this host',
      blocking: true,
    });
  } else {
    const enough = (gpu.memoryTotalMb ?? 0) >= requiredMb;
    checks.push({
      id: 'gpu.vram',
      title: `VRAM sufficient (>= ${spec.requirements.vramGbTypical}GB published)`,
      status: enough ? 'pass' : 'fail',
      evidence: enough
        ? `${gpu.name ?? 'gpu'} reports ${gpu.memoryTotalMb}MB, above the published ${requiredMb}MB`
        : `${gpu.name ?? 'gpu'} reports ${gpu.memoryTotalMb}MB, below the published ${requiredMb}MB for ${spec.id}. ` +
          'This is a published requirement and is unverified on this host; check the model card before concluding.',
      blocking: true,
    });
  }

  const runtime = await (probes.runtime ?? detectRuntime)();
  checks.push({
    id: 'runtime.present',
    title: 'Inference runtime installed and executable',
    status: runtime.present ? 'pass' : 'fail',
    evidence: runtime.detail,
    blocking: true,
  });

  const revision = options.revision ?? identity.revision;
  const revisionOk = isPinned({ ...identity, revision });
  const files = probes.modelFiles
    ? await probes.modelFiles()
    : { present: false, detail: 'no model file location configured; the server may manage its own cache' };
  checks.push({
    id: 'model.revision',
    title: 'Model revision pinned',
    status: revisionOk ? 'pass' : 'fail',
    evidence: revisionOk
      ? `revision ${revision}`
      : `revision is "${revision}": results would not be reproducible, and the cache key could not ` +
        `distinguish two different builds of the same model. Pass --revision <sha> before measuring.`,
    blocking: true,
  });
  checks.push({
    id: 'model.files',
    title: 'Model weights present',
    status: files.present ? 'pass' : 'blocked',
    evidence: files.detail,
    blocking: true,
  });

  const quantization = options.quantization ?? identity.quantization;
  checks.push({
    id: 'model.quantization',
    title: 'Quantization fixed',
    status: quantization.trim().length > 0 ? 'pass' : 'fail',
    evidence: `quantization=${quantization} (part of the cache key, so a BF16 run cannot be confused with a Q4 run)`,
    blocking: true,
  });

  const health = await engine.healthCheck();
  checks.push({
    id: 'serving.process',
    title: 'Serving process reachable',
    status: health.healthy ? 'pass' : 'fail',
    evidence: health.detail ?? (health.healthy ? 'reachable' : 'no detail'),
    blocking: true,
  });

  // Identity is only meaningful once the process answered, but it is the check
  // most likely to catch a misconfigured deployment, so it is never skipped.
  const selfReport = await describeServedModel(engine);
  const identityOk = health.healthy && selfReport.matches;
  checks.push({
    id: 'model.identity',
    title: 'Served model matches the requested model',
    status: identityOk ? 'pass' : 'fail',
    evidence: selfReport.detail,
    blocking: true,
  });

  const warm = await (probes.warmUp ?? (() => engine.warmUp()))();
  checks.push({
    id: 'serving.warmup',
    title: 'Warm-up completes',
    status: warm.loaded ? 'pass' : 'fail',
    evidence: warm.loaded
      ? `warm-up completed in ${warm.durationMs}ms — ${warm.detail}`
      : warm.detail,
    blocking: true,
  });

  const blocking = checks.filter((c) => c.blocking && c.status !== 'pass');
  return {
    model: spec.id,
    checks,
    ready: blocking.length === 0,
    blocking,
    summary:
      blocking.length === 0
        ? `all ${checks.length} preconditions passed for ${spec.id}`
        : `${blocking.length} of ${checks.length} preconditions not met for ${spec.id}: ` +
          blocking.map((c) => c.id).join(', '),
  };
}

/** Throws when the stack is not ready, so a benchmark cannot start on it. */
export function assertReadyForBenchmark(result: PreflightResult): void {
  if (result.ready) {
    return;
  }
  const detail = result.blocking.map((c) => `  - ${c.id}: ${c.status} — ${c.evidence}`).join('\n');
  throw new Error(
    `refusing to benchmark ${result.model}: preconditions not met\n${detail}\n` +
      'A benchmark on an unverified stack produces numbers that belong to no model.',
  );
}

export function renderPreflight(result: PreflightResult, mock = false): string {
  const lines: string[] = ['', `preflight: ${result.model}`];
  if (mock) {
    lines.push('  NOTE: engine under test is a MOCK. Passing here validates the checks, not a model.');
  }
  for (const check of result.checks) {
    const mark = check.status === 'pass' ? 'ok  ' : check.status === 'fail' ? 'FAIL' : 'BLOCK';
    lines.push(`  [${mark}] ${check.title}`);
    lines.push(`         ${check.evidence}`);
  }
  lines.push(`  ready for benchmark: ${result.ready ? 'YES' : 'NO'} — ${result.summary}`);
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// Default probes
// ---------------------------------------------------------------------------

/** Detects a GPU through nvidia-smi, falling back to device nodes. */
export async function detectGpu(): Promise<GpuProbe> {
  const nodes: string[] = [];
  for (const node of ['/dev/nvidia0', '/dev/nvidiactl', '/dev/dri/card0']) {
    try {
      await access(node, constants.F_OK);
      nodes.push(node);
    } catch {
      // Absent; recorded only if it turns out to be the only signal.
    }
  }

  const row = await detectNvidiaSmi();
  if (row) {
    const sample = parseNvidiaRow(row, 0);
    if (sample) {
      return {
        present: true,
        detail: `nvidia-smi: ${sample.name}, ${sample.memoryTotalMb}MB total`,
        name: sample.name,
        memoryTotalMb: sample.memoryTotalMb,
        deviceNodes: nodes,
      };
    }
    return { present: true, detail: `nvidia-smi responded but did not return a parseable row: ${row}`, deviceNodes: nodes };
  }
  if (nodes.length > 0) {
    return {
      present: true,
      detail: `nvidia-smi is unavailable, but device nodes exist: ${nodes.join(', ')}. VRAM cannot be read.`,
      deviceNodes: nodes,
    };
  }
  return {
    present: false,
    detail:
      `no GPU: nvidia-smi is absent and none of /dev/nvidia0, /dev/nvidiactl, /dev/dri/card0 exist. ` +
      `Host RAM is ${(os.totalmem() / 1024 ** 3).toFixed(1)}GB.`,
    deviceNodes: nodes,
  };
}

/** Checks that a runtime executable exists and reports a version. */
export async function detectRuntime(): Promise<RuntimeProbe> {
  for (const command of ['llama-server', 'llama-cpp-server', 'ollama']) {
    const version = await tryVersion(command, ['--version']);
    if (version !== undefined) {
      return { present: true, detail: `${command} --version → ${version}`, command, version };
    }
  }
  return {
    present: false,
    detail:
      'no inference runtime found: llama-server, llama-cpp-server and ollama are all absent from PATH. ' +
      'Install llama.cpp (provides llama-server) or ollama.',
  };
}

function tryVersion(command: string, args: string[]): Promise<string | undefined> {
  return new Promise<string | undefined>((resolve) => {
    execFile(command, args, { timeout: 4000 }, (error, stdout, stderr) => {
      if (error) {
        resolve(undefined);
        return;
      }
      const output = `${String(stdout)}${String(stderr)}`.trim().split('\n')[0] ?? '';
      resolve(output.length > 0 ? output : 'present (no version output)');
    });
  });
}

/**
 * Reads identity warnings the engine collected during its health check.
 *
 * A server that does not report which model it loaded is *not* a pass. It is a
 * stack on which a comparison cannot be trusted, because a 12B answering as a 4B
 * would reorder the whole table.
 */
async function describeServedModel(engine: ServingEngine): Promise<{ matches: boolean; detail: string }> {
  const stats = engine.stats();
  if (stats.servedAs === undefined) {
    return {
      matches: false,
      detail:
        'server did not report a model identity, so identity could not be confirmed. ' +
        'A serving stack that cannot name its weights cannot be benchmarked.',
    };
  }
  if (stats.identityConfirmed) {
    return { matches: true, detail: `server reports "${stats.servedAs}", matching the request` };
  }
  const mismatched = stats.identityWarnings.find((w) => w.includes('was requested'));
  return {
    matches: false,
    detail: mismatched ?? `server reports "${stats.servedAs}", which could not be confirmed against the request`,
  };
}
