import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CancellationToken } from '../../src/core/cancellation';
import { DeadlineBudget, withTimeout, withTokenAndTimeout } from '../../src/core/timeout';
import { computeDelay, shouldRetry, withRetry, DEFAULT_RETRY_POLICY } from '../../src/core/retry';
import {
  CancelledError,
  DeadlineExceededError,
  EngineUnavailableError,
  QuotaExceededError,
  RateLimitError,
  TimeoutError,
  ValidationError,
} from '../../src/core/errors';

const immediateSleep = async (): Promise<void> => undefined;


describe('CancellationToken', () => {
  it('starts uncancelled', () => {
    const token = new CancellationToken();
    assert.equal(token.isCancelled, false);
  });

  it('marks itself cancelled and aborts its signal', () => {
    const token = new CancellationToken();
    token.cancel({ message: 'user pressed stop' });
    assert.equal(token.isCancelled, true);
    assert.equal(token.signal.aborted, true);
  });

  it('is idempotent', () => {
    const token = new CancellationToken();
    let calls = 0;
    token.onCancel(() => {
      calls += 1;
    });
    token.cancel();
    token.cancel();
    assert.equal(calls, 1);
  });

  it('notifies listeners registered before cancellation', () => {
    const token = new CancellationToken();
    let received = false;
    token.onCancel(() => {
      received = true;
    });
    token.cancel();
    assert.equal(received, true);
  });

  it('notifies immediately when already cancelled', () => {
    const token = new CancellationToken();
    token.cancel();
    let received = false;
    token.onCancel(() => {
      received = true;
    });
    assert.equal(received, true);
  });

  it('supports listener removal', () => {
    const token = new CancellationToken();
    let calls = 0;
    const off = token.onCancel(() => {
      calls += 1;
    });
    off();
    token.cancel();
    assert.equal(calls, 0);
  });

  it('throwIfCancelled throws before any work starts (pre-flight)', () => {
    const token = new CancellationToken();
    token.cancel();
    assert.throws(() => token.throwIfCancelled(), CancelledError);
  });

  it('throwIfCancelled is a no-op when not cancelled', () => {
    assert.doesNotThrow(() => new CancellationToken().throwIfCancelled());
  });

  it('links tokens so cancelling one cancels the other', () => {
    const a = new CancellationToken();
    const b = new CancellationToken();
    const linked = CancellationToken.link([a, b]);
    a.cancel();
    assert.equal(linked.isCancelled, true);
    assert.equal(b.isCancelled, false);
  });

  it('link propagates an already-cancelled token', () => {
    const a = new CancellationToken();
    a.cancel();
    assert.equal(CancellationToken.link([a, new CancellationToken()]).isCancelled, true);
  });
});

describe('withTimeout', () => {
  it('resolves when the operation finishes in time', async () => {
    assert.equal(await withTimeout(async () => 'done', 1000), 'done');
  });

  it('rejects with TimeoutError when the operation hangs', async () => {
    const slow = new Promise<never>(() => undefined);
    await assert.rejects(() => withTimeout(slow, 30), TimeoutError);
  });

  it('invokes onTimeout so the caller can abort work', async () => {
    let triggered = false;
    const slow = new Promise<never>(() => undefined);
    await assert.rejects(() =>
      withTimeout(slow, 20, {
        onTimeout: () => {
          triggered = true;
        },
      }),
    );
    assert.equal(triggered, true);
  });

  it('does not fire the timer for a fast operation', async () => {
    let triggered = false;
    await withTimeout(async () => 'ok', 500, {
      onTimeout: () => {
        triggered = true;
      },
    });
    assert.equal(triggered, false);
  });

  it('passes through when the timeout is non-positive', async () => {
    assert.equal(await withTimeout(async () => 'fast', 0), 'fast');
  });

  it('does not leave the process waiting on a pending timer', async () => {
    const started = Date.now();
    await assert.rejects(() => withTimeout(new Promise<never>(() => undefined), 20), TimeoutError);
    assert.ok(Date.now() - started < 1000);
  });
});

describe('DeadlineBudget', () => {
  it('reports remaining time', () => {
    const budget = new DeadlineBudget(1000);
    assert.ok(budget.deadline.remainingMs() > 900);
    assert.equal(budget.deadline.expired(), false);
  });

  it('rejects an operation that outruns the deadline', async () => {
    const budget = new DeadlineBudget(30);
    await assert.rejects(() => budget.timeout(new Promise<never>(() => undefined)), DeadlineExceededError);
  });

  it('invokes onExpire exactly once', async () => {
    let calls = 0;
    const budget = new DeadlineBudget(20, {
      onExpire: () => {
        calls += 1;
      },
    });
    await assert.rejects(() => budget.timeout(new Promise<never>(() => undefined)));
    assert.equal(calls, 1);
  });

  it('clamps a per-attempt timeout to the remaining budget', () => {
    const budget = new DeadlineBudget(100);
    assert.ok(budget.clamp(5000) <= 100);
    assert.ok(budget.clamp(50) <= 50);
  });

  it('throwIfExpired raises once the budget is spent', async () => {
    const budget = new DeadlineBudget(10);
    await new Promise((r) => setTimeout(r, 25));
    assert.throws(() => budget.throwIfExpired(), DeadlineExceededError);
  });

  it('resolves when the operation completes inside the budget', async () => {
    const budget = new DeadlineBudget(1000);
    assert.equal(await budget.timeout(async () => 'ok'), 'ok');
  });
});

describe('withTokenAndTimeout', () => {
  it('cancels the token when the timeout fires', async () => {
    const token = new CancellationToken();
    await assert.rejects(() => withTokenAndTimeout(() => new Promise<never>(() => undefined), token, 20));
    assert.equal(token.isCancelled, true);
  });

  it('throws immediately for an already-cancelled token', async () => {
    const token = new CancellationToken();
    token.cancel();
    await assert.rejects(() => withTokenAndTimeout(async () => 'x', token, 1000), CancelledError);
  });
});

describe('retry policy', () => {
  it('retries transient engine failures', () => {
    assert.equal(shouldRetry(new EngineUnavailableError('down')), true);
    assert.equal(shouldRetry(new RateLimitError('slow down')), true);
    assert.equal(shouldRetry(new TimeoutError('too slow')), true);
  });

  // Regression: retrying permanent errors wasted the whole budget.
  it('does not retry permanent errors', () => {
    assert.equal(shouldRetry(new ValidationError('bad input')), false);
    assert.equal(shouldRetry(new QuotaExceededError('quota gone')), false);
    assert.equal(shouldRetry(new CancelledError()), false);
    assert.equal(shouldRetry(new DeadlineExceededError('budget')), false);
  });

  it('computes exponential backoff capped at maxDelayMs', () => {
    const policy = { ...DEFAULT_RETRY_POLICY, jitterRatio: 0 };
    assert.equal(computeDelay(1, policy), 300);
    assert.equal(computeDelay(2, policy), 600);
    assert.equal(computeDelay(3, policy), 1200);
    assert.equal(computeDelay(20, policy), policy.maxDelayMs);
  });
});

describe('withRetry', () => {
  it('returns the first successful result', async () => {
    const result = await withRetry(async () => 'ok', { sleep: immediateSleep });
    assert.equal(result, 'ok');
  });

  it('retries then succeeds', async () => {
    let attempts = 0;
    const result = await withRetry(
      async (attempt) => {
        attempts = attempt;
        if (attempt < 3) {
          throw new EngineUnavailableError('flaky');
        }
        return 'recovered';
      },
      { sleep: immediateSleep, policy: { ...DEFAULT_RETRY_POLICY, jitterRatio: 0 } },
    );
    assert.equal(result, 'recovered');
    assert.equal(attempts, 3);

  });

  it('stops at maxAttempts and rethrows the last error', async () => {
    let attempts = 0;
    await assert.rejects(
      () =>
        withRetry(
          async (attempt) => {
            attempts = attempt;
            throw new EngineUnavailableError('always down');
          },
          { sleep: immediateSleep, policy: { ...DEFAULT_RETRY_POLICY, maxAttempts: 3, jitterRatio: 0 } },
        ),
      EngineUnavailableError,
    );
    assert.equal(attempts, 3);
  });

  it('does not retry validation errors at all', async () => {
    let attempts = 0;
    await assert.rejects(
      () =>
        withRetry(
          async (attempt) => {
            attempts = attempt;
            throw new ValidationError('nope');
          },
          { sleep: immediateSleep },
        ),
      ValidationError,
    );
    assert.equal(attempts, 1);
  });

  it('does not retry after cancellation', async () => {
    const token = new CancellationToken();
    token.cancel();
    let attempts = 0;
    await assert.rejects(
      () =>
        withRetry(
          async (attempt) => {
            attempts = attempt;
            return 'x';
          },
          { signal: token.signal, sleep: immediateSleep },
        ),
      CancelledError,
    );
    assert.equal(attempts, 0);
  });

  it('reports retry events', async () => {
    const events: number[] = [];
    await withRetry(
      async (attempt) => {
        if (attempt < 2) {
          throw new EngineUnavailableError('flaky');
        }
        return 'ok';
      },
      {
        sleep: immediateSleep,
        onRetry: (attempt) => {
          events.push(attempt);
        },
      },
    );
    assert.deepEqual(events, [1]);
  });

  it('honours a single-attempt policy', async () => {
    let attempts = 0;
    await assert.rejects(
      () =>
        withRetry(
          async (attempt) => {
            attempts = attempt;
            throw new EngineUnavailableError('down');
          },
          { sleep: immediateSleep, policy: { ...DEFAULT_RETRY_POLICY, maxAttempts: 1 } },
        ),
      EngineUnavailableError,
    );
    assert.equal(attempts, 1);
  });
});