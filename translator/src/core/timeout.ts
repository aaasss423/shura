/**
 * Timeouts: per-request and service-level deadline.
 *
 * Two distinct mechanisms because they solve different problems:
 *  - `withTimeout` bounds a single operation (one engine call).
 *  - `withDeadline` bounds a whole workflow (a chapter) and reports which one
 *    fired, so callers can distinguish "this request took too long" from
 *    "the chapter ran out of its budget".
 *
 * Both must never leave a dangling timer or a floating promise.
 */

import { CancelledError, DeadlineExceededError, TimeoutError } from './errors';
import type { CancellationToken } from './cancellation';

export interface TimeoutOptions {
  /** Receives a callback when the timeout fires; used to abort in-flight work. */
  onTimeout?: () => void;
  message?: string;
}

/**
 * Rejects with TimeoutError after `ms`, and always clears the timer.
 * The wrapped promise is not abandoned silently: late rejections are swallowed
 * so an aborted engine call cannot become an unhandled rejection.
 */
export async function withTimeout<T>(
  operation: Promise<T> | (() => Promise<T>),
  ms: number,
  options: TimeoutOptions = {},
): Promise<T> {
  if (!Number.isFinite(ms) || ms <= 0) {
    return typeof operation === 'function' ? operation() : operation;
  }

  const promise = typeof operation === 'function' ? operation() : operation;

  let timer: NodeJS.Timeout | undefined;
  const guard = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      options.onTimeout?.();
      reject(new TimeoutError(options.message ?? `operation timed out after ${ms}ms`, { details: { timeoutMs: ms } }));
    }, ms);
    // Deliberately NOT unref'd: a timeout that cannot fire because the event
    // loop emptied would let the process exit silently instead of reporting the
    // timeout. The timer is always cleared in the finally block below.
  });

  try {
    return await Promise.race([promise, guard]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
    // Ensure a late failure of the original promise cannot crash the process.
    promise.catch(() => undefined);
  }
}

/**
 * Races an operation against a cancellation token.
 *
 * Needed because an engine implementation may not settle its promise after an
 * abort. Cancellation must win over the timeout, otherwise a cancelled request
 * would hang until the request timeout fired and would surface the wrong error.
 */
export async function raceCancellation<T>(
  operation: Promise<T>,
  token: CancellationToken,
): Promise<T> {
  if (token.isCancelled) {
    // Observe the operation so a later rejection cannot become unhandled.
    operation.catch(() => undefined);
    throw new CancelledError(token.reason?.message ?? 'operation was cancelled');
  }
  const guard = new Promise<never>((_resolve, reject) => {
    const off = token.onCancel((reason) => {
      reject(new CancelledError(reason.message ?? 'operation was cancelled'));
    });
    void off;
  });
  try {
    return await Promise.race([operation, guard]);
  } finally {
    operation.catch(() => undefined);
  }
}

export interface Deadline {
  readonly expiresAt: number;
  remainingMs(): number;
  expired(): boolean;
  throwIfExpired(): void;
}

export interface DeadlineOptions {
  message?: string;
  /** Called once when the deadline is hit; used to abort the token. */
  onExpire?: () => void;
}

/**
 * Creates a deadline. `timeout()` wraps a promise so the deadline applies to the
 * whole operation, not just to individual steps inside it.
 */
export class DeadlineBudget {
  private readonly expiresAt: number;
  private fired = false;
  private readonly options: DeadlineOptions;

  constructor(budgetMs: number, options: DeadlineOptions = {}) {
    this.expiresAt = Date.now() + budgetMs;
    this.options = options;
  }

  get deadline(): Deadline {
    return {
      expiresAt: this.expiresAt,
      remainingMs: () => Math.max(0, this.expiresAt - Date.now()),
      expired: () => Date.now() >= this.expiresAt,
      throwIfExpired: () => this.throwIfExpired(),
    };
  }

  throwIfExpired(): void {
    if (this.fired || Date.now() >= this.expiresAt) {
      this.fired = true;
      this.options.onExpire?.();
      throw new DeadlineExceededError(
        this.options.message ?? `deadline exceeded after ${Math.round(this.expiresAt - Date.now())}ms`,
      );
    }
  }

  /**
   * Applies the remaining budget to a promise. Per-attempt timeouts are clamped
   * to whatever budget is left, which is what stops a chapter from hanging when
   * an engine call exceeds the chapter deadline.
   */
  async timeout<T>(operation: Promise<T> | (() => Promise<T>)): Promise<T> {
    const remaining = this.deadline.remainingMs();
    if (remaining <= 0) {
      // Take the operation as a thunk so nothing is started before the budget
      // check. A pre-started promise rejected here with nobody attached would
      // surface later as an unhandled rejection.
      this.throwIfExpired();
    }
    const promise = typeof operation === 'function' ? operation() : operation;
    let timer: NodeJS.Timeout | undefined;
    const guard = new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => {
        this.fired = true;
        this.options.onExpire?.();
        reject(
          new DeadlineExceededError(
            this.options.message ?? 'service level deadline exceeded',
            { details: { budgetMs: Math.round(this.expiresAt - Date.now()) } },
          ),
        );
      }, remaining);
      // Not unref'd, for the same reason as withTimeout: the deadline must be
      // able to fire. Cleared in the finally block.
    });
    try {
      return await Promise.race([promise, guard]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
      promise.catch(() => undefined);
    }
  }

  /** Clamps a per-attempt timeout to the remaining budget. */
  clamp(timeoutMs: number): number {
    return Math.max(1, Math.min(timeoutMs, this.deadline.remainingMs()));
  }
}

/**
 * Convenience wrapper combining a request timeout with a cancellation token:
 * the token aborts the operation, the timer bounds it.
 */
export async function withTokenAndTimeout<T>(
  operation: (token: CancellationToken) => Promise<T>,
  token: CancellationToken,
  ms: number,
  options: TimeoutOptions = {},
): Promise<T> {
  token.throwIfCancelled();
  return withTimeout(() => operation(token), ms, {
    message: options.message,
    onTimeout: () => {
      options.onTimeout?.();
      token.cancel({ reason: 'timeout', message: options.message ?? 'timeout' });
    },
  });
}