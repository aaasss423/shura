/**
 * Prewarming (requirement 7).
 *
 * Launch day should not start from zero knowledge. Prewarming runs in batches
 * ahead of launch, prioritised by what readers actually hit, and every batch is
 * resumable because each item is keyed and idempotent.
 *
 * Priorities, highest first:
 *   1. honorifics and names  — wrong every time, cheap to learn
 *   2. manga expressions, sound effects, slang
 *   3. common dialogue verbs and sentence patterns
 *   4. general frequent words
 */

import { randomUUID } from 'node:crypto';
import type { Database } from './db/database';
import type { ResearchQueue } from './research/queue';
import type { KnowledgeCategory } from './knowledge/repository';

export interface PrewarmSource {
  sourceLanguage: string;
  targetLanguage: string;
  category: KnowledgeCategory;
  terms: string[];
}

export interface PrewarmBatch {
  id: string;
  sourceLanguage: string;
  targetLanguage: string;
  totalItems: number;
  createdAt: string;
}

export interface PrewarmStats {
  batchId: string;
  queued: number;
  skippedDuplicate: number;
  pending: number;
  completed: number;
}

const CATEGORY_PRIORITY: Record<KnowledgeCategory, number> = {
  honorific: 1,
  name: 1,
  manga_expression: 2,
  sound_effect: 2,
  slang: 2,
  idiom: 2,
  phrase: 3,
  context_rule: 3,
  translation_pattern: 3,
  term: 4,
};

export class Prewarmer {
  constructor(
    private readonly db: Database,
    private readonly research: ResearchQueue,
  ) {}

  /**
   * Creates a batch and enqueues research for every term.
   *
   * Resumable by construction: the unique index on
   * `(batch_id, source_language, category, normalized_term)` means re-running
   * the same input adds nothing.
   */
  createBatch(input: {
    sourceLanguage: string;
    targetLanguage: string;
    sources: Array<{ category: KnowledgeCategory; terms: string[] }>;
    batchId?: string;
  }): PrewarmBatch & { stats: PrewarmStats } {
    const batchId = input.batchId ?? `pw-${randomUUID()}`;
    const stats: PrewarmStats = { batchId, queued: 0, skippedDuplicate: 0, pending: 0, completed: 0 };

    this.db.transaction(() => {
      for (const source of input.sources) {
        for (const term of source.terms) {
          const trimmed = term.trim();
          if (trimmed.length === 0) {
            continue;
          }
          const inserted = this.db.run(
            `INSERT INTO prewarm_items (batch_id, source_language, target_language, category, term, normalized_term, status)
             VALUES (?,?,?,?,?,?,'pending')
             ON CONFLICT (batch_id, source_language, category, normalized_term) DO NOTHING`,
            [
              batchId,
              input.sourceLanguage,
              input.targetLanguage,
              source.category,
              trimmed,
              trimmed.normalize('NFKC').replace(/\s+/g, ' ').trim().toLowerCase(),
            ],
          );
          if (inserted.changes === 0) {
            stats.skippedDuplicate += 1;
            continue;
          }
          stats.pending += 1;
          const result = this.research.enqueue({
            sourceLanguage: input.sourceLanguage,
            targetLanguage: input.targetLanguage,
            term: trimmed,
            categoryHint: source.category,
            // Category priority is the ordering: honorifics before generic terms.
            priority: CATEGORY_PRIORITY[source.category] ?? 5,
          });
          if (result.deduplicated) {
            stats.skippedDuplicate += 1;
          } else {
            stats.queued += 1;
          }
          this.db.run(
            'UPDATE prewarm_items SET research_job_id = ? WHERE batch_id = ? AND normalized_term = ? AND status = ?',
            [result.job.id, batchId, trimmed.normalize('NFKC').toLowerCase(), 'pending'],
          );
        }
      }
    });

    const row = this.db.get<{ created_at: string }>('SELECT created_at FROM prewarm_items WHERE batch_id = ? LIMIT 1', [
      batchId,
    ]);

    return {
      id: batchId,
      sourceLanguage: input.sourceLanguage,
      targetLanguage: input.targetLanguage,
      totalItems: stats.pending + stats.skippedDuplicate,
      createdAt: row?.created_at ?? new Date().toISOString(),
      stats,
    };
  }

  /** Reconciles item status after the research queue has been drained. */
  refresh(batchId: string): PrewarmStats {
    const stats: PrewarmStats = { batchId, queued: 0, skippedDuplicate: 0, pending: 0, completed: 0 };
    const rows = this.db.all<Record<string, unknown>>('SELECT * FROM prewarm_items WHERE batch_id = ?', [batchId]);
    for (const row of rows) {
      const status = String(row.status);
      if (status === 'pending') {
        stats.pending += 1;
      } else if (status === 'completed') {
        stats.completed += 1;
      }
      const jobId = row.research_job_id;
      if (jobId) {
        const job = this.research.get(Number(jobId));
        if (job) {
          this.db.run('UPDATE prewarm_items SET status = ?, result = ? WHERE id = ?', [
            job.status,
            job.status === 'completed' ? JSON.stringify({ confidence: null }) : null,
            Number(row.id),
          ]);
        }
      }
    }
    return stats;
  }

  batches(): Array<{ batchId: string; items: number; completed: number }> {
    return this.db
      .all<Record<string, unknown>>(
        `SELECT batch_id,
                COUNT(*) AS items,
                SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed
           FROM prewarm_items GROUP BY batch_id ORDER BY MIN(created_at) ASC`,
      )
      .map((row) => ({
        batchId: String(row.batch_id),
        items: Number(row.items ?? 0),
        completed: Number(row.completed ?? 0),
      }));
  }

  /**
   * Suggested launch corpus, highest value first.
   *
   * A starter set rather than an exhaustive one: the eval set (ADR 0006) and the
   * real reader traffic will keep expanding knowledge after launch.
   */
  static starterCorpus(): PrewarmSource[] {
    return [
      {
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        category: 'honorific',
        terms: ['先輩', '先生', '先輩', '部長', '課長', '社長', '皇帝', '王様', 'お兄さん', 'お姉さん', 'お父さん', 'お母さん'],
      },
      {
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        category: 'manga_expression',
        terms: ['そんなわけないだろ', 'まてよ', 'それはまずい', '行くぞ', 'follow me', 'しね', 'いい加減にしろ'],
      },
      {
        sourceLanguage: 'zh',
        targetLanguage: 'ar',
        category: 'honorific',
        terms: ['前辈', '先生', '大哥', '老大', '大人', '少爷', '小姐'],
      },
      {
        sourceLanguage: 'zh',
        targetLanguage: 'ar',
        category: 'manga_expression',
        terms: ['别开玩笑了', '怎么可能', '住手', '你疯了吗', '交出来'],
      },
      {
        sourceLanguage: 'ko',
        targetLanguage: 'ar',
        category: 'honorific',
        terms: ['선배', '선생님', '형', '누나', '씨', '양반'],
      },
      {
        sourceLanguage: 'ko',
        targetLanguage: 'ar',
        category: 'manga_expression',
        terms: ['말도 안 돼', '그만해', '빨리', '됐어', '가자'],
      },
      {
        sourceLanguage: 'en',
        targetLanguage: 'ar',
        category: 'manga_expression',
        terms: ["Don't worry, I'll be fine.", 'Are you serious?', 'Give me a break.', 'Like hell you are.', 'Not a chance.'],
      },
      {
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        category: 'sound_effect',
        terms: ['ドン', 'バン', 'ズーン', 'Transparency', 'がたん', 'ぱたぱた'],
      },
    ];
  }
}