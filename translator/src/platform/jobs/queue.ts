/**
 * Async job queue (requirement 27).
 *
 * Priority, backpressure, deduplication, retries, cancellation, claim safety.
 * The database is the queue, so a restart does not lose work and multiple
 * workers can share it.
 *
 * Claiming is a conditional UPDATE so exactly one worker wins, mirroring the
 * research queue. On Postgres the same logic runs as `FOR UPDATE SKIP LOCKED`.
 */

import { randomUUID } from 'node:crypto';
import type { Database } from '../db/database';

export const JOB_STATUSES = [
  'queued',
  'running',
  'completed',
  'failed',
  'cancelled',
] as const;

export type JobStatus = (typeof JOB_STATUSES)[number];

export interface Job {
  id: string;
  userId?: string;
  status: JobStatus;
  kind: string;
  priority: number;
  payload: unknown;
  result?: unknown;
  error?: string;
  progress?: Record<string, unknown>;
  attempts: number;
  maxAttempts: number;
  dedupKey?: string;
  cancelRequested: boolean;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface EnqueueJobInput {
  kind: string;
  payload: unknown;
  userId?: string;
  /** 1 (highest) .. 10. */
  priority?: number;
  maxAttempts?: number;
  /** Content hash: a duplicate enqueue returns the existing job. */
  dedupKey?: string;
}

export class JobQueue {
  constructor(private readonly db: Database) {}

  enqueue(input: EnqueueJobInput): { job: Job; deduplicated: boolean } {
    const id = randomUUID();
    return this.db.transaction(() => {
      if (input.dedupKey) {
        const existing = this.db.get<Record<string, unknown>>(
          `SELECT * FROM jobs WHERE dedup_key = ? AND status IN ('queued','running')`,
          [input.dedupKey],
        );
        if (existing) {
          return { job: jobRow(existing), deduplicated: true };
        }
      }
      this.db.run(
        `INSERT INTO jobs (id, user_id, status, kind, priority, payload, attempts, max_attempts, dedup_key)
         VALUES (?,?,'queued',?,?,?,0,?,?)`,
        [
          id,
          input.userId ?? null,
          input.kind,
          input.priority ?? 5,
          JSON.stringify(input.payload ?? {}),
          input.maxAttempts ?? 3,
          input.dedupKey ?? null,
        ],
      );
      return { job: this.require(id), deduplicated: false };
    });
  }

  /**
   * Claims the highest-priority ready job, or undefined when idle.
   *
   * `filter.kind` exists so a worker that serves one kind does not consume another
   * kind's job and then have to release it. Omitting it keeps the original
   * kind-agnostic behaviour.
   */
  claimNext(filter: { kind?: string } = {}): Job | undefined {
    return this.db.transaction(() => {
      const row = this.db.get<Record<string, unknown>>(
        `SELECT * FROM jobs
          WHERE status = 'queued' AND cancel_requested = 0
            AND scheduled_at <= strftime('%Y-%m-%dT%H:%M:%fZ','now')
            ${filter.kind !== undefined ? 'AND kind = ?' : ''}
          ORDER BY priority ASC, created_at ASC
          LIMIT 1`,
        filter.kind !== undefined ? [filter.kind] : [],
      );
      if (!row) {
        return undefined;
      }
      const id = String(row.id);
      const result = this.db.run(
        `UPDATE jobs SET status = 'running', attempts = attempts + 1, started_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
          WHERE id = ? AND status = 'queued'`,
        [id],
      );
      if (result.changes === 0) {
        return undefined;
      }
      return this.require(id);
    });
  }

  complete(id: string, result: unknown): Job {
    this.db.run(
      `UPDATE jobs SET status = 'completed', result = ?, error = NULL,
              completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = ?`,
      [JSON.stringify(result ?? null), id],
    );
    return this.require(id);
  }

  /** Failure with retry until `maxAttempts`, then terminal failure. */
  /**
   * Records a failure.
   *
   * `retryable: false` marks the job failed regardless of remaining attempts. A
   * failure that cannot succeed on a retry — a malformed payload, a request the
   * engine will always reject — must not consume the whole attempt budget before
   * it stops, and must not be rescheduled with a delay that looks like patience.
   */
  fail(id: string, error: string, retryDelaySeconds = 0, options: { retryable?: boolean } = {}): Job {
    const job = this.require(id);
    const permanent = options.retryable === false;
    const exhausted = permanent || job.attempts >= job.maxAttempts;
    if (exhausted) {
      this.db.run(
        `UPDATE jobs SET status = 'failed', error = ?, completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
        [error, id],
      );
    } else {
      const next = new Date(Date.now() + retryDelaySeconds * 1000).toISOString();
      this.db.run(
        `UPDATE jobs SET status = 'queued', error = ?, scheduled_at = ?,
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
        [error, next, id],
      );
    }
    return this.require(id);
  }

  /**
   * Requests cancellation.
   *
   * A queued job stops immediately. A running job is flagged: cancelling must not
   * kill a half-written result, so the worker observes the flag and stops at its
   * next checkpoint.
   */
  cancel(id: string): Job {
    this.db.run('UPDATE jobs SET cancel_requested = 1 WHERE id = ?', [id]);
    const job = this.require(id);
    if (job.status === 'queued') {
      this.db.run(
        `UPDATE jobs SET status = 'cancelled', completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
        [id],
      );
    }
    return this.require(id);
  }

  /**
   * Terminates a running job as cancelled.
   *
   * `cancel()` only finishes queued work, because at that point nobody owns the
   * job. For a running job the worker does own it: after it observes the flag and
   * stops, it calls this. Without it a cancelled job sits in `running` until the
   * stale-job reaper notices, which looks identical to a hung worker.
   */
  markCancelled(id: string, reason = 'cancelled'): Job {
    this.db.run(
      `UPDATE jobs SET status = 'cancelled', error = ?, cancel_requested = 1,
              completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = ?`,
      [reason, id],
    );
    return this.require(id);
  }

  isCancellationRequested(id: string): boolean {
    return Number(
      this.db.get<{ cancel_requested: number }>('SELECT cancel_requested FROM jobs WHERE id = ?', [id])?.cancel_requested ?? 0,
    ) === 1;
  }

  progress(id: string, progress: Record<string, unknown>): void {
    this.db.run(
      `UPDATE jobs SET progress = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
      [JSON.stringify(progress), id],
    );
  }

  get(id: string): Job | undefined {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM jobs WHERE id = ?', [id]);
    return row ? jobRow(row) : undefined;
  }

  list(filter: { userId?: string; status?: JobStatus; limit?: number } = {}): Job[] {
    const conditions: string[] = ['1'];
    const params: unknown[] = [];
    if (filter.userId) {
      conditions.push('user_id = ?');
      params.push(filter.userId);
    }
    if (filter.status) {
      conditions.push('status = ?');
      params.push(filter.status);
    }
    params.push(Math.min(filter.limit ?? 100, 1000));
    return this.db
      .all<Record<string, unknown>>(
        `SELECT * FROM jobs WHERE ${conditions.join(' AND ')} ORDER BY created_at DESC LIMIT ?`,
        params,
      )
      .map(jobRow);
  }

  /**
   * Backpressure: refuses new work above the queue depth limit.
   *
   * Returning a signal rather than blocking is deliberate — an API server that
   * blocks on a full queue turns into an outage instead of shedding load.
   */
  stats(): { queued: number; running: number; completed: number; failed: number; cancelled: number; depth: number } {
    const rows = this.db.all<{ status: string; n: number }>('SELECT status, COUNT(*) AS n FROM jobs GROUP BY status');
    const stats = { queued: 0, running: 0, completed: 0, failed: 0, cancelled: 0, depth: 0 };
    for (const row of rows) {
      const count = Number(row.n);
      if (row.status in stats) {
        stats[row.status as keyof typeof stats] = count;
      }
      if (row.status === 'queued' || row.status === 'running') {
        stats.depth += count;
      }
    }
    return stats;
  }

  /** Returns running jobs to the queue after a worker crash. */
  requeueStale(olderThanSeconds = 900): number {
    const cutoff = new Date(Date.now() - olderThanSeconds * 1000).toISOString();
    return this.db.run(
      `UPDATE jobs SET status = 'queued', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE status = 'running' AND started_at IS NOT NULL AND started_at < ?`,
      [cutoff],
    ).changes;
  }

  private require(id: string): Job {
    const job = this.get(id);
    if (!job) {
      throw new Error(`job ${id} not found`);
    }
    return job;
  }
}

export class QueueSaturatedError extends Error {
  readonly code = 'QUEUE_SATURATED';
  readonly status = 503;

  constructor(readonly depth: number, readonly limit: number) {
    super(`queue is saturated (${depth}/${limit})`);
    this.name = 'QueueSaturatedError';
  }
}

function jobRow(row: Record<string, unknown>): Job {
  const parse = (value: unknown): unknown => {
    if (value === null || value === undefined) {
      return undefined;
    }
    try {
      return JSON.parse(String(value));
    } catch {
      return undefined;
    }
  };
  const result = parse(row.result);
  return {
    id: String(row.id),
    ...(row.user_id ? { userId: String(row.user_id) } : {}),
    status: String(row.status) as JobStatus,
    kind: String(row.kind),
    priority: Number(row.priority ?? 5),
    payload: parse(row.payload) ?? {},
    ...(result === undefined ? {} : { result }),
    ...(row.error ? { error: String(row.error) } : {}),
    ...(parse(row.progress) === undefined ? {} : { progress: parse(row.progress) as Record<string, unknown> }),
    attempts: Number(row.attempts ?? 0),
    maxAttempts: Number(row.max_attempts ?? 3),
    ...(row.dedup_key ? { dedupKey: String(row.dedup_key) } : {}),
    cancelRequested: Number(row.cancel_requested ?? 0) === 1,
    createdAt: String(row.created_at),
    ...(row.started_at ? { startedAt: String(row.started_at) } : {}),
    ...(row.completed_at ? { completedAt: String(row.completed_at) } : {}),
  };
}