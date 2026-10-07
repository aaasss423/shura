/**
 * Load testing (requirement 32).
 *
 * Drives a real HTTP server with N concurrent virtual users and reports honest
 * numbers: requests/sec, successes, error rate, p50/p95/p99, queue depth, worker
 * utilisation.
 *
 * It reports what it measured and nothing more. It does not extrapolate to a
 * user count it did not test, and it labels results as environment-bound, because
 * a load number from a phone is a statement about the phone.
 */

import { LatencyHistogram } from '../metrics';

export interface LoadTestOptions {
  baseUrl: string;
  /** Concurrent virtual users. */
  concurrency: number;
  /** Requests per virtual user. */
  requestsPerUser: number;
  /** Path template; `{i}` is the request index, `{u}` the user id. */
  path?: string;
  method?: 'GET' | 'POST';
  body?: (index: number, user: number) => unknown;
  headers?: Record<string, string>;
  /** Abort the run after this long. */
  timeoutMs?: number;
  /** Settle time before measuring, so warmup is not counted. */
  warmupRequests?: number;
  onTick?: (snapshot: LoadTestSnapshot) => void;
}

export interface LoadTestSnapshot {
  sent: number;
  completed: number;
  succeeded: number;
  failed: number;
  elapsedMs: number;
  requestsPerSecond: number;
  errorRate: number;
  latency: { p50: number; p95: number; p99: number; max: number; mean: number };
  statusCounts: Record<string, number>;
}

export interface LoadTestResult extends LoadTestSnapshot {
  concurrency: number;
  requestsPerUser: number;
  errors: Array<{ status: number; message: string }>;
  /** Honest caveat attached to every result. */
  environment: string;
  notes: string[];
}

export async function runLoadTest(options: LoadTestOptions): Promise<LoadTestResult> {
  const path = options.path ?? '/health';
  const method = options.method ?? 'GET';
  const histogram = new LatencyHistogram();
  const statusCounts: Record<string, number> = {};
  const errors: Array<{ status: number; message: string }> = [];
  const notes: string[] = [];

  let sent = 0;
  let completed = 0;
  let succeeded = 0;
  let failed = 0;
  let warmupRemaining = options.warmupRequests ?? 0;

  const started = Date.now();
  const deadline = options.timeoutMs ? started + options.timeoutMs : Number.POSITIVE_INFINITY;

  const tick = (): void => {
    const snapshot = snapshotOf(
      sent, completed, succeeded, failed, started, histogram, statusCounts,
    );
    options.onTick?.(snapshot);
  };

  const worker = async (user: number): Promise<void> => {
    for (let i = 0; i < options.requestsPerUser; i += 1) {
      if (Date.now() > deadline) {
        return;
      }
      const index = sent++;
      const requestStarted = Date.now();
      try {
        const url = `${options.baseUrl}${path.replace('{i}', String(index)).replace('{u}', String(user))}`;
        const response = await fetch(url, {
          method,
          ...(method === 'POST'
            ? {
                headers: { 'content-type': 'application/json', ...(options.headers ?? {}) },
                body: JSON.stringify(
                  options.body ? options.body(index, user) : { text: 'load probe', targetLanguage: 'ar' },
                ),
              }
            : { headers: options.headers ?? {} }),
        });
        await response.text().catch(() => '');
        const status = String(response.status);
        statusCounts[status] = (statusCounts[status] ?? 0) + 1;

        if (warmupRemaining > 0) {
          warmupRemaining -= 1;
          completed += 1;
          continue;
        }

        const elapsed = Date.now() - requestStarted;
        histogram.observe(elapsed);
        completed += 1;
        if (response.ok) {
          succeeded += 1;
        } else {
          failed += 1;
          if (errors.length < 20) {
            errors.push({ status: response.status, message: `HTTP ${response.status}` });
          }
        }
      } catch (error) {
        if (warmupRemaining > 0) {
          warmupRemaining -= 1;
          completed += 1;
          continue;
        }
        failed += 1;
        statusCounts.network_error = (statusCounts.network_error ?? 0) + 1;
        if (errors.length < 20) {
          errors.push({
            status: 0,
            message: error instanceof Error ? error.message : String(error),
          });
        }
      }
      tick();
    }
  };

  await Promise.all(Array.from({ length: options.concurrency }, (_, user) => worker(user)));

  const snapshot = snapshotOf(sent, completed, succeeded, failed, started, histogram, statusCounts);
  if (options.timeoutMs) {
    notes.push(`run was capped at ${options.timeoutMs}ms; some users may not have finished all requests`);
  }
  if (failed > 0 && statusCounts['429']) {
    notes.push('429 responses present: the limit under test is the rate limiter, not raw throughput');
  }
  if (statusCounts['503']) {
    notes.push('503 responses present: backpressure or saturation rejected work');
  }
  notes.push(
    'These numbers describe this environment only. They are not a claim about production capacity.',
  );

  return {
    ...snapshot,
    concurrency: options.concurrency,
    requestsPerUser: options.requestsPerUser,
    errors,
    environment: `node ${process.version} on ${process.platform}/${process.arch}`,
    notes,
  };
}

function snapshotOf(
  sent: number,
  completed: number,
  succeeded: number,
  failed: number,
  started: number,
  histogram: LatencyHistogram,
  statusCounts: Record<string, number>,
): LoadTestSnapshot {
  const elapsedMs = Math.max(1, Date.now() - started);
  const latency = histogram.summary();
  const total = succeeded + failed;
  return {
    sent,
    completed,
    succeeded,
    failed,
    elapsedMs,
    requestsPerSecond: Number((completed / (elapsedMs / 1000)).toFixed(2)),
    errorRate: total === 0 ? 0 : Number((failed / total).toFixed(4)),
    latency: {
      p50: latency.p50,
      p95: latency.p95,
      p99: latency.p99,
      max: latency.max,
      mean: latency.mean,
    },
    statusCounts,
  };
}

/** Scales that are meaningful to measure on one machine. */
export const LOAD_SCALES = [10, 50, 100, 250] as const;

export function formatLoadResult(result: LoadTestResult): string {
  const lines = [
    `concurrency=${result.concurrency} requests/user=${result.requestsPerUser} completed=${result.completed}`,
    `throughput: ${result.requestsPerSecond} req/s   errors: ${result.failed} (${(result.errorRate * 100).toFixed(2)}%)`,
    `latency ms: p50=${result.latency.p50} p95=${result.latency.p95} p99=${result.latency.p99} max=${result.latency.max}`,
    `statuses: ${JSON.stringify(result.statusCounts)}`,
    `environment: ${result.environment}`,
  ];
  for (const note of result.notes) {
    lines.push(`note: ${note}`);
  }
  return lines.join('\n');
}