/**
 * Retry with an explicit policy.
 *
 * Only errors that can actually benefit from another attempt are retried:
 * network/unavailable/rate-limit/timeout/internal. Validation errors, quota
 * exhaustion, unsupported language pairs and cancellations are permanent and
 * are rethrown immediately — retrying them would just burn time.
 */

import { CancelledError, isTranslationError, TranslationError } from '../core/errors';
import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';

export interface RetryPolicy {
  maxAttempts: number;
  baseDelayMs: number;
  maxDelayMs: number;
  /** Deterministic jitter factor 0..1; 0 makes delays reproducible in tests. */
  jitterRatio: number;
}

export const DEFAULT_RETRY_POLICY: RetryPolicy = {
  maxAttempts: 3,
  baseDelayMs: 300,
  maxDelayMs: 4000,
  jitterRatio: 0.2,
};

const NON_RETRYABLE = new Set([
  'VALIDATION_ERROR',
  'UNSUPPORTED_LANGUAGE',
  'UNSUPPORTED_PAIR',
  'EMPTY_INPUT',
  'TEXT_TOO_LONG',
  'QUOTA_EXCEEDED',
  'CANCELLED',
  'DEADLINE_EXCEEDED',
  'CONFIG_ERROR',
]);

export function shouldRetry(error: unknown, policy: RetryPolicy = DEFAULT_RETRY_POLICY): boolean {
  if (error instanceof CancelledError) {
    return false;
  }
  if (isTranslationError(error)) {
    if (NON_RETRYABLE.has(error.code)) {
      return false;
    }
    return error.retryable;
  }
  if (error instanceof Error) {
    // Unknown native errors (network, DNS) are worth another attempt.
    const name = (error as NodeJS.ErrnoException).code;
    if (name === 'ENOTFOUND' || name === 'ECONNRESET' || name === 'ETIMEDOUT' || name === 'ECONNREFUSED') {
      return true;
    }
    return true;
  }
  return policy.maxAttempts > 0;
}

export function computeDelay(attempt: number, policy: RetryPolicy, random = Math.random): number {
  const exponential = Math.min(policy.maxDelayMs, policy.baseDelayMs * 2 ** Math.max(0, attempt - 1));
  if (policy.jitterRatio <= 0) {
    return exponential;
  }
  const jitter = exponential * policy.jitterRatio * random();
  return Math.min(policy.maxDelayMs, Math.round(exponential - exponential * policy.jitterRatio / 2 + jitter));
}

export interface RetryOptions {
  policy?: RetryPolicy;
  logger?: Logger;
  /** Called before each sleep; useful for progress reporting. */
  onRetry?: (attempt: number, delayMs: number, error: unknown) => void;
  signal?: AbortSignal;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
}

/**
 * Backoff sleep.
 *
 * The timer is intentionally not unref'd: a pending backoff is real pending
 * work, and unref'ing it lets a CLI process exit mid-retry instead of
 * completing the retry or reporting the failure.
 */
export const defaultSleep = (ms: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new CancelledError('cancelled before retry sleep'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort(): void {
      clearTimeout(timer);
      reject(new CancelledError('cancelled during retry sleep'));
    }
    signal?.addEventListener('abort', onAbort, { once: true });
  });

/**
 * Runs `operation` with retries.
 *
 * `operation` receives the 1-based attempt number so callers can pass it to the
 * engine (for example to skip backoff hints).
 */
export async function withRetry<T>(
  operation: (attempt: number) => Promise<T>,
  options: RetryOptions = {},
): Promise<T> {
  const policy = options.policy ?? DEFAULT_RETRY_POLICY;
  const logger = options.logger ?? silentLogger;
  const sleep = options.sleep ?? defaultSleep;
  const maxAttempts = Math.max(1, policy.maxAttempts);

  let lastError: unknown;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (options.signal?.aborted) {
      throw new CancelledError('cancelled before attempt');
    }
    try {
      return await operation(attempt);
    } catch (error) {
      lastError = error;
      const isLast = attempt === maxAttempts;
      if (isLast || !shouldRetry(error, policy)) {
        throw error;
      }
      const delay = computeDelay(attempt, policy);
      logger.warn('retrying operation', {
        attempt,
        maxAttempts,
        delayMs: delay,
        error: isTranslationError(error) ? error.code : String(error),
      });
      options.onRetry?.(attempt, delay, error);
      await sleep(delay, options.signal);
    }
  }
  throw lastError instanceof Error ? lastError : new TranslationError('INTERNAL_ERROR', 'retry loop exhausted');
}