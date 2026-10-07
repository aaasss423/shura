/**
 * Queue integration for the serving engine.
 *
 * The existing `JobQueue` already stores work durably, prioritises it and claims
 * it safely. What it lacks is a bridge to a model server, and a bridge has to
 * answer three questions a hosted API never asks:
 *
 *  - when the queue is deeper than the model can absorb, does work queue or fail?
 *    It queues, up to a depth, then applies backpressure.
 *  - a cancelled job must stop occupying a VRAM slot.
 *  - a shutdown must not leave a job marked `running` forever.
 *
 * `ServingQueueRunner` is that bridge. It is deliberately thin: it does not
 * reimplement scheduling, it only translates between a job and the engine.
 */

import { CancellationToken } from '../../core/cancellation';
import type { Job, JobQueue } from '../jobs/queue';
import type { ManagedEngine, ServingEngineStats } from './servingEngine';

export interface TranslationJobPayload {
  text: string;
  sourceLanguage: string;
  targetLanguage: string;
  hints?: Record<string, string>;
  timeoutMs?: number;
}

export interface ServingQueueRunnerOptions {
  engine: ManagedEngine & { translate: TranslationEngineLike['translate'] };
  queue: JobQueue;
  /** Job kind this runner claims. */
  jobKind?: string;
  /** Refuse to claim while the queue is deeper than this. */
  maxQueueDepth?: number;
  /** How many workers share the runner. Each worker holds one VRAM slot. */
  workers?: number;
  /** Gap between polls when the queue is empty. */
  idlePollMs?: number;
  onLog?: (message: string) => void;
}

type TranslationEngineLike = {
  translate(request: {
    text: string;
    sourceLanguage: string;
    targetLanguage: string;
    hints?: Record<string, string>;
    timeoutMs?: number;
    signal?: AbortSignal;
  }): Promise<{ text: string; engine: string }>;
};

export interface WorkerRunSummary {
  claimed: number;
  completed: number;
  failed: number;
  cancelled: number;
  /** Claim attempts refused because the queue was too deep. */
  deferred: number;
  /** Jobs found malformed; failed rather than retried forever. */
  rejected: number;
}

export class ServingQueueRunner {
  private readonly tokens = new Map<string, CancellationToken>();
  private running = false;
  private stopped = false;
  private readonly jobKind: string;
  private readonly maxQueueDepth: number;
  private readonly idlePollMs: number;
  private readonly log: (message: string) => void;

  constructor(private readonly options: ServingQueueRunnerOptions) {
    this.jobKind = options.jobKind ?? 'translate';
    this.maxQueueDepth = options.maxQueueDepth ?? 500;
    this.idlePollMs = options.idlePollMs ?? 250;
    this.log = options.onLog ?? (() => undefined);
  }

  get inFlight(): number {
    return this.tokens.size;
  }

  /** Cancels a running job, so it stops holding a slot. */
  cancel(jobId: string, reason = 'cancelled by operator'): boolean {
    const token = this.tokens.get(jobId);
    if (!token) {
      return false;
    }
    token.cancel({ message: reason });
    return true;
  }

  /**
   * Runs workers until `stop()`.
   *
   * Each worker is a claim loop. The loop checks backpressure *before* claiming,
   * so a deep queue applies pressure to the producer instead of turning into an
   * unbounded wait inside the model server.
   */
  async run(workerCount = 1): Promise<WorkerRunSummary> {
    if (this.running) {
      throw new Error('queue runner is already running');
    }
    this.running = true;
    this.stopped = false;
    const summary: WorkerRunSummary = {
      claimed: 0,
      completed: 0,
      failed: 0,
      cancelled: 0,
      deferred: 0,
      rejected: 0,
    };

    const workers = Array.from({ length: Math.max(1, workerCount) }, () => this.workerLoop(summary));
    try {
      await Promise.all(workers);
    } finally {
      this.running = false;
    }
    return summary;
  }

  /** Runs one worker for a bounded number of claims. Used by tests and by cron. */
  async runOnce(workerCount = 1): Promise<WorkerRunSummary> {
    const summary: WorkerRunSummary = {
      claimed: 0,
      completed: 0,
      failed: 0,
      cancelled: 0,
      deferred: 0,
      rejected: 0,
    };
    const claims = await Promise.all(
      Array.from({ length: Math.max(1, workerCount) }, () => this.processOne(summary)),
    );
    void claims;
    return summary;
  }

  private async workerLoop(summary: WorkerRunSummary): Promise<void> {
    while (!this.stopped) {
      const depth = this.options.queue.stats().depth;
      if (depth > this.maxQueueDepth) {
        summary.deferred += 1;
        await this.sleep(this.idlePollMs);
        continue;
      }
      const processed = await this.processOne(summary);
      if (!processed) {
        await this.sleep(this.idlePollMs);
      }
    }
  }

  /** Claims and runs at most one job. Returns false when the queue was empty. */
  private async processOne(summary: WorkerRunSummary): Promise<boolean> {
    const job = this.claim();
    if (!job) {
      return false;
    }
    summary.claimed += 1;

    const payload = parsePayload(job);
    if (!payload) {
      summary.rejected += 1;
      this.options.queue.fail(job.id, 'malformed translation job payload', 0, { retryable: false });
      this.log(`job ${job.id} rejected: malformed payload`);
      return true;
    }
    if (job.cancelRequested) {
      summary.cancelled += 1;
      this.options.queue.markCancelled(job.id);
      return true;
    }

    const token = new CancellationToken();
    this.tokens.set(job.id, token);
    try {
      const response = await this.options.engine.translate({
        text: payload.text,
        sourceLanguage: payload.sourceLanguage,
        targetLanguage: payload.targetLanguage,
        ...(payload.hints !== undefined ? { hints: payload.hints } : {}),
        ...(payload.timeoutMs !== undefined ? { timeoutMs: payload.timeoutMs } : {}),
        signal: token.signal,
      });
      if (token.isCancelled) {
        summary.cancelled += 1;
        this.options.queue.markCancelled(job.id, 'cancelled before the translation completed');
        return true;
      }
      this.options.queue.complete(job.id, {
        text: response.text,
        engine: response.engine,
      });
      summary.completed += 1;
    } catch (error) {
      if (token.isCancelled) {
        summary.cancelled += 1;
        this.options.queue.markCancelled(job.id, 'cancelled while translating');
        return true;
      }
      summary.failed += 1;
      const message = error instanceof Error ? error.message : String(error);
      const retryable = (error as { retryable?: boolean }).retryable !== false;
      this.options.queue.fail(job.id, message, retryable ? 30 : 0, { retryable });
      this.log(`job ${job.id} failed: ${message}`);
    } finally {
      this.tokens.delete(job.id);
    }
    return true;
  }

  /**
   * Claims the next job of our kind.
   *
   * The kind filter is applied in the claim itself. A runner that claimed a
   * foreign job would have to release it, and every release costs an attempt and a
   * row write on work this worker cannot service.
   */
  private claim(): Job | undefined {
    const job = this.options.queue.claimNext({ kind: this.jobKind });
    if (job && job.kind !== this.jobKind) {
      // Defensive: a queue implementation that ignores the filter must not run
      // this job through the model.
      this.options.queue.fail(job.id, `job kind "${job.kind}" is not served by this runner`, 0, {
        retryable: false,
      });
      return undefined;
    }
    return job;
  }

  /** Cancels in-flight work and stops the loops. */
  async stop(reason = 'runner stopping'): Promise<void> {
    this.stopped = true;
    for (const [jobId, token] of this.tokens) {
      token.cancel({ message: reason });
      this.log(`cancelled job ${jobId}: ${reason}`);
    }
  }

  snapshot(): { running: boolean; inFlight: number; engine: ServingEngineStats } {
    return {
      running: this.running,
      inFlight: this.inFlight,
      engine: (this.options.engine as unknown as { stats(): ServingEngineStats }).stats(),
    };
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}

function parsePayload(job: Job): TranslationJobPayload | undefined {
  const payload = job.payload as Partial<TranslationJobPayload> | undefined;
  if (!payload || typeof payload.text !== 'string' || payload.text.trim().length === 0) {
    return undefined;
  }
  if (typeof payload.sourceLanguage !== 'string' || typeof payload.targetLanguage !== 'string') {
    return undefined;
  }
  return {
    text: payload.text,
    sourceLanguage: payload.sourceLanguage,
    targetLanguage: payload.targetLanguage,
    ...(payload.hints !== undefined ? { hints: payload.hints } : {}),
    ...(payload.timeoutMs !== undefined ? { timeoutMs: payload.timeoutMs } : {}),
  };
}
