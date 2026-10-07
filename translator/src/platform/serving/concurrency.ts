/**
 * Concurrency control and micro-batching for a local model server.
 *
 * A local inference server has a hard physical limit: the batch it is decoding
 * occupies VRAM, and queueing more work than it can hold turns into an
 * out-of-memory kill rather than a slower response. So the client side has to
 * make the limit explicit instead of discovering it.
 *
 * `Semaphore` is the limit. `BatchScheduler` is the latency/throughput trade: it
 * holds a request for at most `windowMs` waiting for company, then dispatches a
 * batch. The trade is deliberate and measured by the caller, not assumed — a
 * benchmark that reports per-item latency must know whether that latency
 * included batch waiting.
 */

import { EngineError, TimeoutError } from '../../core/errors';
import type { CancellationToken } from '../../core/cancellation';

/**
 * Counting semaphore with FIFO hand-off.
 *
 * FIFO matters: without it a chapter-length request queue starves behind a stream
 * of one-line bubbles, and the translation is still running when the reader moves
 * on.
 */
export class Semaphore {
  private available: number;
  private readonly waiting: Array<() => void> = [];

  constructor(readonly limit: number) {
    if (limit < 1) {
      throw new EngineError(`semaphore limit must be at least 1, received ${limit}`, { retryable: false });
    }
    this.available = limit;
  }

  get inFlight(): number {
    return this.limit - this.available;
  }

  get queued(): number {
    return this.waiting.length;
  }

  /**
   * Acquires a slot, queueing if all are in use.
   *
   * Omitting `waitMs` means "wait indefinitely" — never "skip the limit". A limit
   * that is only enforced when a timeout is supplied is not a limit: a caller
   * asking for no timeout would drive the counter negative and let every request
   * through at once, which is exactly what the semaphore exists to prevent.
   */
  async acquire(waitMs?: number, token?: CancellationToken): Promise<() => void> {
    token?.throwIfCancelled();
    if (this.available > 0) {
      this.available -= 1;
      return this.makeRelease();
    }

    let releaseSlot!: () => void;
    const granted = new Promise<void>((resolve) => {
      releaseSlot = resolve;
    });
    this.waiting.push(releaseSlot);

    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<'timeout'>((resolve) => {
      timer = setTimeout(() => resolve('timeout'), waitMs ?? Number.MAX_SAFE_INTEGER);
    });
    const cancelled = new Promise<'cancelled'>((resolve) => {
      token?.onCancel(() => resolve('cancelled'));
    });

    const outcome = await Promise.race([
      granted.then(() => 'granted' as const),
      ...(waitMs === undefined ? [] : [timeout]),
      cancelled,
    ]);
    if (timer) {
      clearTimeout(timer);
    }

    if (outcome === 'timeout') {
      // Withdraw from the queue: a grant that arrives later must not leak a slot.
      const index = this.waiting.indexOf(releaseSlot);
      if (index !== -1) {
        this.waiting.splice(index, 1);
      }
      throw new TimeoutError(`no inference slot became free within ${waitMs}ms`, {
        details: { limit: this.limit, queued: this.queued },
      });
    }
    if (outcome === 'cancelled') {
      const index = this.waiting.indexOf(releaseSlot);
      if (index !== -1) {
        this.waiting.splice(index, 1);
      } else {
        // Granted at the same moment the token was cancelled: give the slot back.
        this.releaseInternal();
      }
      token?.throwIfCancelled();
    }
    return this.makeRelease();
  }

  private makeRelease(): () => void {
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.releaseInternal();
    };
  }

  private releaseInternal(): void {
    const next = this.waiting.shift();
    if (next) {
      next();
      return;
    }
    this.available = Math.min(this.limit, this.available + 1);
  }
}

export interface BatchRequest {
  text: string;
  /** Opaque caller data returned with the matching result. */
  tag: string;
  /**
   * Source language of *this* item.
   *
   * Per request, not per batch: a batch holds one dispatch but several languages,
   * and a prompt built from a batch-level guess mislabels every item that does not
   * happen to share the first request's language.
   */
  sourceLabel?: string;
  instruction?: string;
  timeoutMs?: number;
  signal?: AbortSignal;
}

export interface BatchOutcome {
  tag: string;
  /** True when this item succeeded. Items fail independently: a poison item must not fail its batch. */
  ok: boolean;
  text?: string;
  error?: Error;
  /** Time from enqueue to dispatch, i.e. how much of the latency was batch waiting. */
  batchWaitMs: number;
}

export interface BatchSchedulerOptions {
  /** Hard cap on how many requests one dispatch carries. */
  maxBatchSize: number;
  /** How long a request waits for company before the batch is dispatched. */
  windowMs: number;
  /**
   * Disables batching: every request is dispatched alone, immediately. Used for
   * latency measurement, where batch waiting would be an artefact of the harness
   * rather than a property of the model.
   */
  batchingEnabled?: boolean;
  /** In-flight dispatch limit. */
  concurrency: number;
  /** How long a request may wait for a slot. */
  queueWaitMs?: number;
  /** Wall clock cap for one dispatch, including server time. */
  dispatchTimeoutMs?: number;
  now?: () => number;
}

export interface BatchDispatchContext {
  token?: CancellationToken;
}

export type BatchDispatcher = (
  requests: BatchRequest[],
  context: BatchDispatchContext,
) => Promise<Array<{ tag: string; text?: string; error?: Error }>>;

/**
 * Collects requests into batches.
 *
 * A batch is dispatched when it is full or when `windowMs` has passed since the
 * oldest waiting request, whichever comes first. The second rule is what stops a
 * trickle of traffic from adding unbounded latency to every request.
 */
export class BatchScheduler {
  private readonly semaphore: Semaphore;
  private waiting: QueuedRequest[] = [];
  private timer: NodeJS.Timeout | undefined;
  private closed = false;
  private readonly now: () => number;

  readonly stats = {
    dispatched: 0,
    requestsDispatched: 0,
    batchWaitMsTotal: 0,
    timeouts: 0,
    cancelled: 0,
    failures: 0,
    maxObservedBatch: 0,
  };

  constructor(
    private readonly options: BatchSchedulerOptions,
    private readonly dispatch: BatchDispatcher,
  ) {
    this.semaphore = new Semaphore(options.concurrency);
    this.now = options.now ?? Date.now;
  }

  get queued(): number {
    return this.waiting.length;
  }

  get inFlight(): number {
    return this.semaphore.inFlight;
  }

  /** Submits one request and resolves with its outcome. Never rejects. */
  async submit(request: BatchRequest, token?: CancellationToken): Promise<BatchOutcome> {
    if (this.closed) {
      return {
        tag: request.tag,
        ok: false,
        error: new EngineError('batch scheduler is closed', { retryable: false }),
        batchWaitMs: 0,
      };
    }
    token?.throwIfCancelled();

    const enqueuedAt = this.now();
    return new Promise<BatchOutcome>((resolve) => {
      const entry: QueuedRequest = {
        request,
        enqueuedAt,
        resolve,
        cancelled: false,
      };
      if (token) {
        token.onCancel(() => {
          if (entry.cancelled) {
            return;
          }
          entry.cancelled = true;
          this.stats.cancelled += 1;
          const index = this.waiting.indexOf(entry);
          if (index !== -1) {
            this.waiting.splice(index, 1);
            resolve({ tag: request.tag, ok: false, error: new Error('cancelled before dispatch'), batchWaitMs: this.now() - enqueuedAt });
          }
        });
      }
      this.waiting.push(entry);
      this.scheduleFlush();
      if (!this.options.batchingEnabled) {
        void this.flush();
      } else if (this.waiting.length >= this.options.maxBatchSize) {
        void this.flush();
      }
    });
  }

  private scheduleFlush(): void {
    if (this.closed || !this.options.batchingEnabled || this.timer) {
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, this.options.windowMs);
  }

  private async flush(): Promise<void> {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    if (this.waiting.length === 0) {
      return;
    }
    const batch = this.waiting.splice(0, this.options.maxBatchSize);
    this.stats.dispatched += 1;
    this.stats.requestsDispatched += batch.length;
    this.stats.maxObservedBatch = Math.max(this.stats.maxObservedBatch, batch.length);

    let release: (() => void) | undefined;
    try {
      release = await this.semaphore.acquire(this.options.queueWaitMs);
    } catch (error) {
      this.resolveAll(batch, () => error as Error);
      return;
    }

    const dispatchStarted = this.now();
    try {
      const responses = await this.dispatch(
        batch.map((e) => e.request),
        {},
      );
      const byTag = new Map(responses.map((r) => [r.tag, r]));
      for (const entry of batch) {
        if (entry.cancelled) {
          continue;
        }
        const wait = dispatchStarted - entry.enqueuedAt;
        this.stats.batchWaitMsTotal += wait;
        const response = byTag.get(entry.request.tag);
        if (!response) {
          this.stats.failures += 1;
          entry.resolve({
            tag: entry.request.tag,
            ok: false,
            error: new EngineError('batch response did not include this request', { retryable: true }),
            batchWaitMs: wait,
          });
          continue;
        }
        if (response.error) {
          this.stats.failures += 1;
        }
        entry.resolve({
          tag: entry.request.tag,
          ok: !response.error && response.text !== undefined,
          ...(response.text !== undefined ? { text: response.text } : {}),
          ...(response.error ? { error: response.error } : {}),
          batchWaitMs: wait,
        });
      }
    } catch (error) {
      this.stats.failures += batch.length;
      this.resolveAll(batch, () => error as Error, dispatchStarted);
    } finally {
      release();
      if (this.waiting.length > 0) {
        this.scheduleFlush();
      }
    }
  }

  private resolveAll(
    batch: QueuedRequest[],
    errorFor: (entry: QueuedRequest) => Error,
    dispatchedAt: number = this.now(),
  ): void {
    for (const entry of batch) {
      if (entry.cancelled) {
        continue;
      }
      entry.resolve({
        tag: entry.request.tag,
        ok: false,
        error: errorFor(entry),
        batchWaitMs: dispatchedAt - entry.enqueuedAt,
      });
    }
  }

  /**
   * Stops accepting work and fails everything still waiting.
   *
   * Graceful shutdown: in-flight dispatches are left to finish, because cutting a
   * decode in half loses the batch that was already paid for.
   */
  async close(timeoutMs = 5000): Promise<void> {
    this.closed = true;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    const pending = this.waiting.splice(0, this.waiting.length);
    this.resolveAll(pending, () => new EngineError('serving engine is shutting down', { retryable: true }));

    const deadline = this.now() + timeoutMs;
    while (this.semaphore.inFlight > 0 && this.now() < deadline) {
      await new Promise<void>((resolve) => setTimeout(resolve, 10));
    }
  }

  snapshot() {
    return {
      ...this.stats,
      queued: this.queued,
      inFlight: this.inFlight,
      meanBatchWaitMs:
        this.stats.requestsDispatched === 0
          ? 0
          : Math.round((this.stats.batchWaitMsTotal / this.stats.requestsDispatched) * 100) / 100,
      meanBatchSize:
        this.stats.dispatched === 0 ? 0 : Math.round((this.stats.requestsDispatched / this.stats.dispatched) * 100) / 100,
    };
  }
}

interface QueuedRequest {
  request: BatchRequest;
  enqueuedAt: number;
  resolve: (outcome: BatchOutcome) => void;
  cancelled: boolean;
}
