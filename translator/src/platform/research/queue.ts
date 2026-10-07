/**
 * Research queue with deduplication (ADR 0005) and the Research Agent (0004).
 *
 * `enqueue` is idempotent and safe on the hot path: 500 users hitting the same
 * unknown phrase produce one job with 500 waiters. The queue is claimed by
 * background workers; nothing in the translation path ever waits for it.
 */

import type { Database } from '../db/database';
import { normalizeTerm } from '../knowledge/repository';
import type { HoldoutGuardLike } from '../eval/guard';

export const RESEARCH_STATUSES = [
  'queued',
  'running',
  'completed',
  'failed',
  'retry',
  'needs_review',
] as const;

export type ResearchStatus = (typeof RESEARCH_STATUSES)[number];

export interface ResearchJob {
  id: number;
  normalizedKey: string;
  sourceLanguage: string;
  targetLanguage: string;
  term: string;
  categoryHint?: string;
  status: ResearchStatus;
  priority: number;
  attempts: number;
  maxAttempts: number;
  waiters: number;
  context?: string;
  seriesId?: string;
  critical: boolean;
  error?: string;
  createdAt: string;
  completedAt?: string;
}

export interface ResearchSource {
  id: number;
  jobId: number;
  url?: string;
  title?: string;
  kind: string;
  credibility: number;
  snippet?: string;
  fingerprint: string;
  retrievedAt: string;
}

export interface EnqueueInput {
  sourceLanguage: string;
  targetLanguage: string;
  term: string;
  categoryHint?: string;
  context?: string;
  seriesId?: string;
  /** 1 (highest) .. 10. Prewarm uses low priority; inline critical uses 1. */
  priority?: number;
  critical?: boolean;
  maxAttempts?: number;
}

export interface EnqueueResult {
  job: ResearchJob;
  /** True when this call created the job rather than joining an existing one. */
  created: boolean;
  /** True when an existing live job was joined and its waiters incremented. */
  deduplicated: boolean;
}

/** Identity of a research need. Deliberately excludes the surrounding sentence. */
export function researchKey(
  sourceLanguage: string,
  targetLanguage: string,
  term: string,
  categoryHint = '',
): string {
  return `${sourceLanguage}→${targetLanguage}|${categoryHint}|${normalizeTerm(term)}`;
}

const LIVE_STATUSES = ['queued', 'running'] as const;

export class ResearchQueue {
  /**
   * `holdout` is enforced here so every path that can create knowledge is
   * covered, including prewarm batches, which reach the queue indirectly.
   */
  constructor(
    private readonly db: Database,
    private readonly holdout?: HoldoutGuardLike,
  ) {}

  /**
   * Idempotent enqueue. A live job is joined and its waiter count incremented
   * instead of a duplicate being created.
   */
  enqueue(input: EnqueueInput): EnqueueResult {
    this.holdout?.assertWrite({ term: input.term });
    const normalizedTerm = normalizeTerm(input.term);
    if (!normalizedTerm) {
      throw new Error('research term must be non-empty');
    }
    const key = researchKey(input.sourceLanguage, input.targetLanguage, input.term, input.categoryHint ?? '');

    return this.db.transaction(() => {
      const existingRow = this.db.get<Record<string, unknown>>(
        'SELECT * FROM research_jobs WHERE normalized_key = ?',
        [key],
      );

      const existingStatus = existingRow ? (String(existingRow.status) as ResearchStatus) : undefined;
      if (existingRow && existingStatus && (LIVE_STATUSES as readonly string[]).includes(existingStatus)) {
        this.db.run('UPDATE research_jobs SET waiters = waiters + 1 WHERE id = ?', [Number(existingRow.id)]);
        return {
          job: this.require(Number(existingRow.id)),
          created: false,
          deduplicated: true,
        };
      }

      if (existingRow) {
        // A finished job with the same key: reopen it rather than duplicating.
        this.db.run(
          `UPDATE research_jobs
              SET status = 'queued', attempts = 0, waiters = waiters + 1, error = NULL,
                  priority = MIN(priority, ?), critical = MAX(critical, ?),
                  context = COALESCE(?, context),
                  updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id = ?`,
          [input.priority ?? 5, input.critical ? 1 : 0, input.context ?? null, Number(existingRow.id)],
        );
        return { job: this.require(Number(existingRow.id)), created: false, deduplicated: true };
      }

      const result = this.db.run(
        `INSERT INTO research_jobs
           (normalized_key, source_language, target_language, term, category_hint, status, priority,
            attempts, max_attempts, waiters, context, series_id, critical)
         VALUES (?,?,?,?,?,'queued',?,0,?,1,?,?,?)`,
        [
          key,
          input.sourceLanguage,
          input.targetLanguage,
          input.term,
          input.categoryHint ?? null,
          input.priority ?? 5,
          input.maxAttempts ?? 3,
          input.context ?? null,
          input.seriesId ?? null,
          input.critical ? 1 : 0,
        ],
      );
      const id = Number(this.db.get<{ id: number }>('SELECT last_insert_rowid() AS id')?.id ?? result.changes);
      return { job: this.require(id), created: true, deduplicated: false };
    });
  }

  /**
   * Atomically claims the next job.
   *
   * On Postgres this is `FOR UPDATE SKIP LOCKED`; SQLite serialises writers, so
   * the conditional UPDATE is what makes it safe. Both give: exactly one winner,
   * and concurrent claimers never receive the same job.
   */
  claimNext(): ResearchJob | undefined {
    return this.db.transaction(() => {
      const row = this.db.get<Record<string, unknown>>(
        `SELECT * FROM research_jobs
          WHERE status IN ('queued','retry') AND scheduled_at <= strftime('%Y-%m-%dT%H:%M:%fZ','now')
          ORDER BY priority ASC, created_at ASC
          LIMIT 1`,
      );
      if (!row) {
        return undefined;
      }
      const id = Number(row.id);
      const result = this.db.run(
        `UPDATE research_jobs SET status = 'running', attempts = attempts + 1,
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
          WHERE id = ? AND status IN ('queued','retry')`,
        [id],
      );
      if (result.changes === 0) {
        return undefined;
      }
      return this.require(id);
    });
  }

  complete(id: number): ResearchJob {
    this.db.run(
      `UPDATE research_jobs SET status = 'completed', completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
      [id],
    );
    return this.require(id);
  }

  /**
   * Records a failure. Retries with backoff until `maxAttempts`, then parks the
   * job in `needs_review` — never silently dropped, because a silently dropped
   * job looks identical to a phrase that was successfully researched.
   */
  fail(id: number, error: string, backoffSeconds = 60): ResearchJob {
    const job = this.require(id);
    const exhausted = job.attempts >= job.maxAttempts;
    if (exhausted) {
      this.db.run(
        `UPDATE research_jobs SET status = 'needs_review', error = ?,
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
        [error, id],
      );
    } else {
      const nextAttemptAt = new Date(Date.now() + backoffSeconds * 1000).toISOString();
      this.db.run(
        `UPDATE research_jobs SET status = 'retry', error = ?, scheduled_at = ?,
                updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
        [error, nextAttemptAt, id],
      );
    }
    return this.require(id);
  }

  /** Returns a running job to the queue, e.g. after a worker crash. */
  requeue(id: number): void {
    this.db.run(
      `UPDATE research_jobs SET status = 'queued', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = ? AND status = 'running'`,
      [id],
    );
  }

  addSource(jobId: number, source: Omit<ResearchSource, 'id' | 'jobId' | 'retrievedAt'>): ResearchSource {
    // Deduplicate on the fingerprint so one page cannot inflate credibility by
    // being submitted twice.
    this.db.run(
      `INSERT INTO research_sources (job_id, url, title, kind, credibility, snippet, fingerprint)
       VALUES (?,?,?,?,?,?,?)
       ON CONFLICT (job_id, fingerprint) DO UPDATE SET credibility = excluded.credibility`,
      [jobId, source.url ?? null, source.title ?? null, source.kind, source.credibility, source.snippet ?? null, source.fingerprint],
    );
    const row = this.db.get<Record<string, unknown>>(
      'SELECT * FROM research_sources WHERE job_id = ? AND fingerprint = ?',
      [jobId, source.fingerprint],
    );
    if (!row) {
      throw new Error('research source vanished after write');
    }
    return sourceRow(row);
  }

  sources(jobId: number): ResearchSource[] {
    return this.db
      .all<Record<string, unknown>>(
        'SELECT * FROM research_sources WHERE job_id = ? ORDER BY credibility DESC, id ASC',
        [jobId],
      )
      .map(sourceRow);
  }

  list(filter: { status?: ResearchStatus; limit?: number } = {}): ResearchJob[] {
    const conditions: string[] = ['1'];
    const params: unknown[] = [];
    if (filter.status) {
      conditions.push('status = ?');
      params.push(filter.status);
    }
    params.push(Math.min(filter.limit ?? 100, 2000));
    return this.db
      .all<Record<string, unknown>>(
        `SELECT * FROM research_jobs WHERE ${conditions.join(' AND ')} ORDER BY priority ASC, created_at ASC LIMIT ?`,
        params,
      )
      .map(jobRow);
  }

  get(id: number): ResearchJob | undefined {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM research_jobs WHERE id = ?', [id]);
    return row ? jobRow(row) : undefined;
  }

  stats(): Record<ResearchStatus, number> & { total: number } {
    const rows = this.db.all<{ status: string; n: number }>(
      'SELECT status, COUNT(*) AS n FROM research_jobs GROUP BY status',
    );
    const stats = { queued: 0, running: 0, completed: 0, failed: 0, retry: 0, needs_review: 0, total: 0 };
    for (const row of rows) {
      const count = Number(row.n);
      if (row.status in stats) {
        stats[row.status as ResearchStatus] = count;
      }
      stats.total += count;
    }
    return stats;
  }

  private require(id: number): ResearchJob {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM research_jobs WHERE id = ?', [id]);
    if (!row) {
      throw new Error(`research job ${id} not found`);
    }
    return jobRow(row);
  }
}

function jobRow(row: Record<string, unknown>): ResearchJob {
  return {
    id: Number(row.id),
    normalizedKey: String(row.normalized_key),
    sourceLanguage: String(row.source_language),
    targetLanguage: String(row.target_language),
    term: String(row.term),
    ...(row.category_hint ? { categoryHint: String(row.category_hint) } : {}),
    status: String(row.status) as ResearchStatus,
    priority: Number(row.priority ?? 5),
    attempts: Number(row.attempts ?? 0),
    maxAttempts: Number(row.max_attempts ?? 3),
    waiters: Number(row.waiters ?? 0),
    ...(row.context ? { context: String(row.context) } : {}),
    ...(row.series_id ? { seriesId: String(row.series_id) } : {}),
    critical: Number(row.critical ?? 0) === 1,
    ...(row.error ? { error: String(row.error) } : {}),
    createdAt: String(row.created_at),
    ...(row.completed_at ? { completedAt: String(row.completed_at) } : {}),
  };
}

function sourceRow(row: Record<string, unknown>): ResearchSource {
  return {
    id: Number(row.id),
    jobId: Number(row.job_id),
    ...(row.url ? { url: String(row.url) } : {}),
    ...(row.title ? { title: String(row.title) } : {}),
    kind: String(row.kind ?? 'unknown'),
    credibility: Number(row.credibility ?? 0),
    ...(row.snippet ? { snippet: String(row.snippet) } : {}),
    fingerprint: String(row.fingerprint),
    retrievedAt: String(row.retrieved_at),
  };
}