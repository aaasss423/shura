/**
 * Resource sampling.
 *
 * VRAM and GPU utilisation are only meaningful if they are sampled *while* the
 * model runs, and only if the report says who measured them. This module shells
 * out to `nvidia-smi`, which is the tool every CUDA box already has, and degrades
 * to an explicit "unavailable" when it is not there.
 *
 * It never estimates. A host without `nvidia-smi` reports that it cannot measure,
 * because a made-up VRAM figure is worse than a missing one.
 */

import { execFile } from 'node:child_process';

export interface GpuSample {
  index: number;
  name: string;
  memoryUsedMb: number;
  memoryTotalMb: number;
  utilisationPercent: number;
}

export interface ResourceUsage {
  /** False when no measurement was possible; `note` then says why. */
  measured: boolean;
  source: 'nvidia-smi' | 'unavailable';
  note: string;
  samples: number;
  /** Peak observed across samples. */
  peakMemoryUsedMb?: number;
  peakUtilisationPercent?: number;
  gpuName?: string;
  memoryTotalMb?: number;
  /** Host RSS of this process, which is what actually matters on CPU offload. */
  processPeakRssMb?: number;
}

const NVIDIA_QUERY =
  'index,name,memory.used,memory.total,utilization.gpu';

/**
 * Samples GPU memory for as long as `work` runs.
 *
 * Sampling stops when `work` settles, so a run that throws still reports what was
 * observed up to that point rather than losing the numbers.
 */
export async function sampleResources<T>(
  work: () => Promise<T>,
  options: { intervalMs?: number; detect?: () => Promise<string | undefined> } = {},
): Promise<{ result: T; resources: ResourceUsage }> {
  const intervalMs = options.intervalMs ?? 500;
  const nvidiaSmi = options.detect ?? detectNvidiaSmi;

  const samples: GpuSample[] = [];
  let processPeakRss = process.memoryUsage().rss;
  let stopped = false;
  let unavailableReason = '';

  const probe = await nvidiaSmi();
  if (probe === undefined) {
    unavailableReason =
      'nvidia-smi is not available on this host, so GPU memory and utilisation were not measured';
  }

  const timer =
    probe === undefined
      ? undefined
      : setInterval(() => {
          void nvidiaSmi().then((line) => {
            if (line === undefined) {
              return;
            }
            const sample = parseNvidiaRow(line, samples.length);
            if (sample) {
              samples.push(sample);
            }
          });
          processPeakRss = Math.max(processPeakRss, process.memoryUsage().rss);
        }, intervalMs);

  try {
    const result = await work();
    return {
      result,
      resources: summarize(samples, processPeakRss, unavailableReason),
    };
  } finally {
    stopped = true;
    if (timer) {
      clearInterval(timer);
    }
    void stopped;
  }
}

function summarize(samples: GpuSample[], processPeakRss: number, unavailableReason: string): ResourceUsage {
  const base = {
    processPeakRssMb: Math.round((processPeakRss / (1024 * 1024)) * 10) / 10,
  };
  if (unavailableReason) {
    return { measured: false, source: 'unavailable', note: unavailableReason, samples: 0, ...base };
  }
  if (samples.length === 0) {
    return {
      measured: false,
      source: 'unavailable',
      note: 'nvidia-smi was present but returned no samples; nothing was measured',
      samples: 0,
      ...base,
    };
  }
  return {
    measured: true,
    source: 'nvidia-smi',
    note: `${samples.length} sample(s) taken during the run on this host`,
    samples: samples.length,
    peakMemoryUsedMb: Math.max(...samples.map((s) => s.memoryUsedMb)),
    peakUtilisationPercent: Math.max(...samples.map((s) => s.utilisationPercent)),
    gpuName: samples[0]!.name,
    memoryTotalMb: samples[0]!.memoryTotalMb,
    ...base,
  };
}

/** Returns one CSV row, or undefined when nvidia-smi is absent or fails. */
export async function detectNvidiaSmi(): Promise<string | undefined> {
  return new Promise<string | undefined>((resolve) => {
    execFile(
      'nvidia-smi',
      [`--query-gpu=${NVIDIA_QUERY}`, '--format=csv,noheader,nounits'],
      { timeout: 4000 },
      (error, stdout) => {
        if (error) {
          resolve(undefined);
          return;
        }
        const line = String(stdout).split('\n').find((l) => l.trim().length > 0);
        resolve(line?.trim());
      },
    );
  });
}

/**
 * Parses one `nvidia-smi --query-gpu=index,name,memory.used,memory.total,utilization.gpu`
 * CSV row.
 *
 * The leading `index` column is skipped rather than read as the name: treating it
 * as the name made every row unparseable and turned "GPU present" into
 * "nothing measured".
 */
export function parseNvidiaRow(row: string, index?: number): GpuSample | undefined {
  const parts = row.split(',').map((p) => p.trim());
  if (parts.length < 5) {
    return undefined;
  }
  const rowIndex = Number(parts[0]);
  const [name, used, total, utilisation] = parts.slice(1);
  const memoryUsedMb = Number(used);
  const memoryTotalMb = Number(total);
  const utilisationPercent = Number(utilisation);
  if (!Number.isFinite(memoryUsedMb) || !Number.isFinite(utilisationPercent)) {
    return undefined;
  }
  return {
    index: Number.isFinite(rowIndex) ? rowIndex : (index ?? 0),
    name: name ?? 'unknown gpu',
    memoryUsedMb,
    memoryTotalMb,
    utilisationPercent,
  };
}
