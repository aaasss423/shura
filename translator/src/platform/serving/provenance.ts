/**
 * Run provenance.
 *
 * A benchmark number without its provenance is not reproducible, it is a rumour.
 * This module captures the things that decide the answer and are invisible in the
 * score itself: which file was loaded, which build decoded it, on which GPU, with
 * which sampling parameters.
 *
 * The rule throughout: record what was observed, and when something cannot be
 * observed say so. Nothing here infers a version, a digest or a driver number.
 */

import { createHash } from 'node:crypto';
import { createReadStream, promises as fs } from 'node:fs';
import * as os from 'node:os';
import { execFile } from 'node:child_process';

import { detectNvidiaSmi, parseNvidiaRow } from './resourceSampler';
import type { ModelIdentity } from './modelIdentity';

export interface ProvenanceField {
  label: string;
  value: string;
  /** `observed` values came from the host; `configured` came from our own settings. */
  source: 'observed' | 'configured' | 'unavailable';
}

export interface RunProvenance {
  model: ProvenanceField[];
  host: ProvenanceField[];
  serving: ProvenanceField[];
  /**
   * One-line summary for a report header.
   *
   * Includes the weight digest, because "same model name" does not mean "same
   * weights" and a comparison across two digests is a comparison of two models.
   */
  summary: string;
}

export interface ProvenanceOptions {
  identity: ModelIdentity;
  /** Absolute path to the weight file, when the operator knows it. */
  modelPath?: string;
  servingStyle: string;
  concurrency: number;
  maxBatchSize: number;
  batchWindowMs: number;
  temperature: number;
  maxTokens: number;
  contextLength?: number;
  runtimeCommand?: string;
}

/**
 * SHA-256 of the weight file.
 *
 * This is the only model identity that cannot be faked by a filename. It costs a
 * full read of the weights, so it is opt-in: `--digest` in the benchmark, or
 * implicit when the operator pins a revision.
 */
export async function digestFile(path: string): Promise<{ digest: string; bytes: number } | undefined> {
  try {
    const stat = await fs.stat(path);
    if (!stat.isFile()) {
      return undefined;
    }
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(path)) {
      hash.update(chunk as Buffer);
    }
    return { digest: hash.digest('hex'), bytes: stat.size };
  } catch {
    return undefined;
  }
}

/** Best-effort GPU description, including driver, straight from the host. */
export async function observeGpu(): Promise<ProvenanceField[]> {
  const fields: ProvenanceField[] = [];
  const row = await detectNvidiaSmi();
  if (row) {
    const sample = parseNvidiaRow(row, 0);
    if (sample) {
      fields.push({ label: 'gpu', value: `${sample.name} (${sample.memoryTotalMb}MB)`, source: 'observed' });
    }
  } else {
    fields.push({ label: 'gpu', value: 'not present', source: 'observed' });
  }

  const driver = await runCommand('nvidia-smi', [
    '--query-gpu=driver_version',
    '--format=csv,noheader',
  ]);
  fields.push(
    driver !== undefined
      ? { label: 'driver', value: driver, source: 'observed' }
      : { label: 'driver', value: 'unavailable (nvidia-smi absent)', source: 'unavailable' },
  );
  return fields;
}

/** Runtime build string. Never inferred: absent means absent. */
export async function observeRuntime(command = 'llama-server'): Promise<ProvenanceField[]> {
  const version = await runCommand(command, ['--version']);
  return [
    version !== undefined
      ? { label: 'runtime', value: `${command}: ${version}`, source: 'observed' }
      : { label: 'runtime', value: `${command} not found on PATH`, source: 'unavailable' },
  ];
}

export async function captureProvenance(options: ProvenanceOptions): Promise<RunProvenance> {
  const digest =
    options.modelPath !== undefined ? await digestFile(options.modelPath) : undefined;

  const model: ProvenanceField[] = [
    { label: 'modelId', value: options.identity.modelId, source: 'configured' },
    {
      label: 'revision',
      value: options.identity.revision,
      source: options.identity.revision === 'UNPINNED' ? 'configured' : 'observed',
    },
    { label: 'quantization', value: options.identity.quantization, source: 'configured' },
  ];
  if (options.modelPath !== undefined) {
    model.push(
      digest !== undefined
        ? { label: 'weights', value: `sha256:${digest.digest.slice(0, 16)} (${Math.round(digest.bytes / 1024 / 1024)}MB)`, source: 'observed' }
        : { label: 'weights', value: `sha256 unavailable (unreadable: ${options.modelPath})`, source: 'unavailable' },
    );
  } else {
    model.push({
      label: 'weights',
      value: 'digest not computed: no model file path given (pass --model-path to enable)',
      source: 'unavailable',
    });
  }

  const serving: ProvenanceField[] = [
    { label: 'style', value: options.servingStyle, source: 'configured' },
    { label: 'concurrency', value: String(options.concurrency), source: 'configured' },
    { label: 'batch', value: `${options.maxBatchSize}@${options.batchWindowMs}ms`, source: 'configured' },
    { label: 'temperature', value: String(options.temperature), source: 'configured' },
    { label: 'maxTokens', value: String(options.maxTokens), source: 'configured' },
    ...(options.contextLength !== undefined
      ? [{ label: 'contextLength', value: String(options.contextLength), source: 'observed' as const }]
      : []),
  ];

  const host: ProvenanceField[] = [
    { label: 'platform', value: `${os.platform()} ${os.release()} (${os.arch()})`, source: 'observed' },
    { label: 'node', value: process.version, source: 'observed' },
    { label: 'cpu', value: `${os.cpus()[0]?.model ?? 'unknown'} ×${os.cpus().length}`, source: 'observed' },
    { label: 'ram', value: `${(os.totalmem() / 1024 ** 3).toFixed(1)}GB`, source: 'observed' },
    ...(await observeGpu()),
    ...(await observeRuntime(options.runtimeCommand)),
  ];
  return {
    model,
    host,
    serving,
    summary: summarize([...model, ...serving, ...host]),
  };
}

function summarize(fields: ProvenanceField[]): string {
  const pick = (label: string): string => fields.find((f) => f.label === label)?.value ?? 'unknown';
  return [
    pick('modelId'),
    `rev=${pick('revision')}`,
    `quant=${pick('quantization')}`,
    pick('weights'),
    `conc=${pick('concurrency')}`,
    `batch=${pick('batch')}`,
    `temp=${pick('temperature')}`,
    pick('gpu'),
    pick('runtime'),
  ].join(' · ');
}

function runCommand(command: string, args: string[]): Promise<string | undefined> {
  return new Promise<string | undefined>((resolve) => {
    execFile(command, args, { timeout: 5000 }, (error, stdout, stderr) => {
      if (error) {
        resolve(undefined);
        return;
      }
      const output = `${String(stdout)}${String(stderr)}`.trim();
      resolve(output.length > 0 ? output.split('\n')[0]!.trim() : 'present (no version output)');
    });
  });
}
