/**
 * Observability.
 *
 * A metrics registry with the dimensions the ops view needs (requirement 24),
 * plus latency percentiles. Deliberately in-process and dependency-free: the
 * `MetricsSink` interface is the seam for Prometheus/OTLP later.
 *
 * Rules the implementation follows:
 *  - counters are monotonic; latency is a histogram, not an average
 *  - a failed request is counted as a failure, never silently dropped
 *  - percentiles are computed from a bounded reservoir, so memory is capped
 */

export type MetricLabels = Record<string, string>;

export interface MetricSink {
  counter(name: string, labels: MetricLabels, delta: number): void;
  observe(name: string, labels: MetricLabels, valueMs: number): void;
}

export interface LatencySummary {
  count: number;
  min: number;
  max: number;
  mean: number;
  p50: number;
  p95: number;
  p99: number;
}

interface Histogram {
  /** Bounded reservoir: keeps the tail without unbounded memory. */
  samples: number[];
  max: number;
  seen: number;
  total: number;
  min: number;
}

function labelKey(labels: MetricLabels): string {
  const keys = Object.keys(labels).sort();
  return keys.length === 0 ? '' : keys.map((k) => `${k}=${labels[k]}`).join(',');
}

/** Percentile over a bounded reservoir. */
export class LatencyHistogram {
  private readonly histogram: Histogram = { samples: [], max: 2048, seen: 0, total: 0, min: Number.POSITIVE_INFINITY };

  observe(valueMs: number): void {
    if (!Number.isFinite(valueMs) || valueMs < 0) {
      return;
    }
    this.histogram.seen += 1;
    this.histogram.total += valueMs;
    this.histogram.min = Math.min(this.histogram.min, valueMs);
    if (this.histogram.samples.length < this.histogram.max) {
      this.histogram.samples.push(valueMs);
      return;
    }
    // Reservoir sampling keeps the distribution representative.
    const index = Math.floor(Math.random() * this.histogram.seen);
    if (index < this.histogram.max) {
      this.histogram.samples[index] = valueMs;
    }
  }

  summary(): LatencySummary {
    const samples = [...this.histogram.samples].sort((a, b) => a - b);
    if (samples.length === 0) {
      return { count: 0, min: 0, max: 0, mean: 0, p50: 0, p95: 0, p99: 0 };
    }
    const at = (p: number): number =>
      samples[Math.min(samples.length - 1, Math.max(0, Math.ceil((p / 100) * samples.length) - 1))]!;
    return {
      count: this.histogram.seen,
      min: Number.isFinite(this.histogram.min) ? this.histogram.min : 0,
      max: samples[samples.length - 1]!,
      mean: Number((this.histogram.total / this.histogram.seen).toFixed(2)),
      p50: at(50),
      p95: at(95),
      p99: at(99),
    };
  }

  get count(): number {
    return this.histogram.seen;
  }
}

export class MetricsRegistry {
  private readonly counters = new Map<string, { labels: MetricLabels; value: number }>();
  private readonly histograms = new Map<string, LatencyHistogram>();
  private readonly sinks: MetricSink[] = [];

  addSink(sink: MetricSink): void {
    this.sinks.push(sink);
  }

  increment(name: string, labels: MetricLabels = {}, delta = 1): void {
    const key = `${name}|${labelKey(labels)}`;
    const existing = this.counters.get(key);
    if (existing) {
      existing.value += delta;
    } else {
      this.counters.set(key, { labels, value: delta });
    }
    for (const sink of this.sinks) {
      sink.counter(name, labels, delta);
    }
  }

  observe(name: string, valueMs: number, labels: MetricLabels = {}): void {
    const key = `${name}|${labelKey(labels)}`;
    let histogram = this.histograms.get(key);
    if (!histogram) {
      histogram = new LatencyHistogram();
      this.histograms.set(key, histogram);
    }
    histogram.observe(valueMs);
    for (const sink of this.sinks) {
      sink.observe(name, labels, valueMs);
    }
  }

  counter(name: string, labels: MetricLabels = {}): number {
    return this.counters.get(`${name}|${labelKey(labels)}`)?.value ?? 0;
  }

  /** All counters for a metric name, summed across label sets. */
  total(name: string): number {
    let sum = 0;
    for (const [key, entry] of this.counters) {
      if (key === name || key.startsWith(`${name}|`)) {
        sum += entry.value;
      }
    }
    return sum;
  }

  latency(name: string, labels: MetricLabels = {}): LatencySummary {
    return this.histograms.get(`${name}|${labelKey(labels)}`)?.summary() ?? {
      count: 0, min: 0, max: 0, mean: 0, p50: 0, p95: 0, p99: 0,
    };
  }

  /** A flat snapshot for a health or admin endpoint. */
  snapshot(): Record<string, unknown> {
    const counters: Record<string, number> = {};
    for (const [key, entry] of this.counters) {
      counters[key.split('|')[0]!] = (counters[key.split('|')[0]!] ?? 0) + entry.value;
    }
    const latencies: Record<string, LatencySummary> = {};
    for (const [key, histogram] of this.histograms) {
      latencies[key] = histogram.summary();
    }
    return { counters, latencies };
  }

  reset(): void {
    this.counters.clear();
    this.histograms.clear();
  }
}

/** Metric names used across the platform, so they cannot drift. */
export const METRICS = {
  requestsTotal: 'requests_total',
  requestErrors: 'request_errors_total',
  charactersTranslated: 'characters_translated_total',
  latencyMs: 'request_latency_ms',
  engineLatencyMs: 'engine_latency_ms',
  cacheHits: 'cache_hits_total',
  cacheMisses: 'cache_misses_total',
  modelLatencyMs: 'model_latency_ms',
  failedJobs: 'failed_jobs_total',
  retries: 'retries_total',
  researchQueueSize: 'research_queue_size',
  researchJobsTotal: 'research_jobs_total',
  knowledgeHits: 'knowledge_hits_total',
  memoryHits: 'translation_memory_hits_total',
  glossaryApplied: 'glossary_applied_total',
  queueDepth: 'queue_depth',
  workerUtilization: 'worker_utilization',
  breakerOpen: 'breaker_open',
  deniedRequests: 'denied_requests_total',
} as const;

/**
 * Tracks per-key concurrency so `max_parallel` is real rather than advisory.
 * An unbounded counter here is exactly the failure mode that lets one client
 * saturate the workers.
 */
export class ConcurrencyTracker {
  private readonly active = new Map<string, number>();

  constructor(private readonly limitPerSubject: (subject: string) => number) {}

  /** Returns a release function, or throws when the limit is reached. */
  acquire(subject: string): () => void {
    const limit = Math.max(1, this.limitPerSubject(subject));
    const current = this.active.get(subject) ?? 0;
    if (current >= limit) {
      throw new ConcurrencyLimitError(subject, limit);
    }
    this.active.set(subject, current + 1);
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      const next = (this.active.get(subject) ?? 1) - 1;
      if (next <= 0) {
        this.active.delete(subject);
      } else {
        this.active.set(subject, next);
      }
    };
  }

  inFlight(subject: string): number {
    return this.active.get(subject) ?? 0;
  }

  /** Across all subjects, for utilisation reporting. */
  total(): number {
    let sum = 0;
    for (const count of this.active.values()) {
      sum += count;
    }
    return sum;
  }
}

export class ConcurrencyLimitError extends Error {
  readonly code = 'CONCURRENCY_LIMIT';
  readonly status = 429;

  constructor(
    readonly subject: string,
    readonly limit: number,
  ) {
    super(`concurrency limit reached for ${subject} (max ${limit} in flight)`);
    this.name = 'ConcurrencyLimitError';
  }
}

/** Fixed-window rate limiter. Cheap, predictable, and enough for abuse control. */
export class RateLimiter {
  private readonly windows = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Returns remaining allowance; throws when the window is exhausted. */
  consume(subject: string, cost = 1): { remaining: number; resetAt: number } {
    const timestamp = this.now();
    const existing = this.windows.get(subject);
    if (!existing || existing.resetAt <= timestamp) {
      const fresh = { count: cost, resetAt: timestamp + this.windowMs };
      this.windows.set(subject, fresh);
      return { remaining: Math.max(0, this.limit - cost), resetAt: fresh.resetAt };
    }
    if (existing.count + cost > this.limit) {
      throw new RateLimitError(subject, this.limit, existing.resetAt);
    }
    existing.count += cost;
    return { remaining: Math.max(0, this.limit - existing.count), resetAt: existing.resetAt };
  }

  used(subject: string): number {
    return this.windows.get(subject)?.count ?? 0;
  }
}

export class RateLimitError extends Error {
  readonly code = 'RATE_LIMITED';
  readonly status = 429;

  constructor(
    readonly subject: string,
    readonly limit: number,
    readonly resetAt: number,
  ) {
    super(`rate limit exceeded for ${subject} (${limit} per window)`);
    this.name = 'RateLimitError';
  }
}