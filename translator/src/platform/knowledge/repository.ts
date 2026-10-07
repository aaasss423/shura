/**
 * Knowledge Base.
 *
 * Language namespaced, provenance-bearing, confidence-scored, versioned. One
 * repository, one SQL surface (ADR 0003). Categories come from the taxonomy the
 * pipeline needs to enforce, not from an ad-hoc free-text field.
 *
 * Lookup is by normalized term. Ranking blends confidence, usage and
 * verification state so a human-verified entry outranks a scraped guess.
 */

import type { Database } from '../db/database';
import type { HoldoutGuardLike } from '../eval/guard';

export const KNOWLEDGE_CATEGORIES = [
  'term',
  'phrase',
  'slang',
  'name',
  'honorific',
  'idiom',
  'manga_expression',
  'sound_effect',
  'context_rule',
  'translation_pattern',
] as const;

export type KnowledgeCategory = (typeof KNOWLEDGE_CATEGORIES)[number];

export const VERIFICATION_STATES = ['unverified', 'candidate', 'verified', 'rejected'] as const;
export type VerificationState = (typeof VERIFICATION_STATES)[number];

export const SOURCE_TYPES = [
  'manual',
  'research_agent',
  'translation_memory',
  'glossary',
  'prewarm',
  'user_feedback',
] as const;
export type KnowledgeSourceType = (typeof SOURCE_TYPES)[number];

export interface KnowledgeEntry {
  id: number;
  sourceLanguage: string;
  targetLanguage: string;
  category: KnowledgeCategory;
  term: string;
  normalizedTerm: string;
  meaning?: string;
  translation: string;
  context?: string;
  seriesId?: string;
  characterId?: string;
  genre?: string;
  confidence: number;
  source?: string;
  sourceType: KnowledgeSourceType;
  verificationState: VerificationState;
  version: number;
  usageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeInput {
  sourceLanguage: string;
  targetLanguage: string;
  category: KnowledgeCategory;
  term: string;
  translation: string;
  meaning?: string;
  context?: string;
  seriesId?: string;
  characterId?: string;
  genre?: string;
  confidence?: number;
  source?: string;
  sourceType?: KnowledgeSourceType;
  verificationState?: VerificationState;
}

export interface KnowledgeLookupOptions {
  category?: KnowledgeCategory;
  seriesId?: string;
  characterId?: string;
  /** Only entries at or above this confidence. */
  minConfidence?: number;
  limit?: number;
}

/**
 * Normalizes a term for lookup.
 *
 * NFKC, case-folded where the script is cased, and whitespace collapsed. Script
 * detection matters: folding case in Japanese or Arabic changes nothing, but
 * folding it in Latin text would break "Anna" vs "anna".
 */
export function normalizeTerm(term: string): string {
  return term
    .normalize('NFKC')
    // Invisible formatting marks are dropped: they carry no linguistic content
    // but they do defeat an exact-match leakage check.
    .replace(/[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

function rowToEntry(row: Record<string, unknown>): KnowledgeEntry {
  return {
    id: Number(row.id),
    sourceLanguage: String(row.source_language),
    targetLanguage: String(row.target_language),
    category: String(row.category) as KnowledgeCategory,
    term: String(row.term),
    normalizedTerm: String(row.normalized_term),
    ...(row.meaning === null || row.meaning === undefined ? {} : { meaning: String(row.meaning) }),
    translation: String(row.translation),
    ...(row.context === null || row.context === undefined ? {} : { context: String(row.context) }),
    ...(row.series_id === null || row.series_id === undefined ? {} : { seriesId: String(row.series_id) }),
    ...(row.character_id === null || row.character_id === undefined ? {} : { characterId: String(row.character_id) }),
    ...(row.genre === null || row.genre === undefined ? {} : { genre: String(row.genre) }),
    confidence: Number(row.confidence ?? 0),
    ...(row.source === null || row.source === undefined ? {} : { source: String(row.source) }),
    sourceType: String(row.source_type ?? 'manual') as KnowledgeSourceType,
    verificationState: String(row.verification_state ?? 'unverified') as VerificationState,
    version: Number(row.version ?? 1),
    usageCount: Number(row.usage_count ?? 0),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export class KnowledgeRepository {
  /**
   * `holdout` is optional so existing callers keep working, but every knowledge
   * writer in production passes it: without it, an evaluation reference can be
   * published as a "term" and quietly become training data.
   */
  constructor(
    private readonly db: Database,
    private readonly holdout?: HoldoutGuardLike,
  ) {}

  /** Inserts, or updates and bumps `version` when the term already exists. */
  upsert(input: KnowledgeInput): KnowledgeEntry {
    this.holdout?.assertWrite({ term: input.term, translation: input.translation });
    const normalized = normalizeTerm(input.term);
    if (!normalized) {
      throw new Error('knowledge term must be non-empty');
    }
    if (!KNOWLEDGE_CATEGORIES.includes(input.category)) {
      throw new Error(`unknown knowledge category: ${input.category}`);
    }

    return this.db.transaction(() => {
      const existing = this.findByScope(input, normalized);
      if (existing) {
        const updated = this.db.run(
          `UPDATE knowledge
              SET translation = ?, meaning = ?, context = ?, genre = ?, confidence = ?,
                  source = ?, source_type = ?, verification_state = ?, version = version + 1,
                  updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id = ?`,
          [
            input.translation,
            input.meaning ?? null,
            input.context ?? null,
            input.genre ?? null,
            input.confidence ?? existing.confidence,
            input.source ?? null,
            input.sourceType ?? existing.sourceType,
            input.verificationState ?? existing.verificationState,
            existing.id,
          ],
        );
        if (updated.changes === 0) {
          return this.requireById(existing.id);
        }
        return this.requireById(existing.id);
      }

      const result = this.db.run(
        `INSERT INTO knowledge
           (source_language, target_language, category, term, normalized_term, meaning, translation,
            context, series_id, character_id, genre, confidence, source, source_type,
            verification_state, version, usage_count)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,0)`,
        [
          input.sourceLanguage,
          input.targetLanguage,
          input.category,
          input.term,
          normalized,
          input.meaning ?? null,
          input.translation,
          input.context ?? null,
          input.seriesId ?? null,
          input.characterId ?? null,
          input.genre ?? null,
          input.confidence ?? 0,
          input.source ?? null,
          input.sourceType ?? 'manual',
          input.verificationState ?? 'unverified',
        ],
      );
      return this.requireById(Number(result.changes) && this.lastInsertId());
    });
  }

  private lastInsertId(): number {
    const row = this.db.get<{ id: number }>('SELECT last_insert_rowid() AS id');
    return Number(row?.id ?? 0);
  }

  /**
   * Finds the entry occupying exactly the scope described by `input`.
   *
   * The scope must be an exact structural match. A looser predicate (for example
   * "series is null OR equals this series") would let a character-scoped write
   * overwrite the global row instead of creating its own.
   */
  private findByScope(input: KnowledgeInput, normalized: string): KnowledgeEntry | undefined {
    const { scopeClause, scopeParams } = KnowledgeRepository.scopeFor(input);
    const row = this.db.get<Record<string, unknown>>(
      `SELECT * FROM knowledge
        WHERE source_language = ? AND target_language = ? AND category = ? AND normalized_term = ?
          AND ${scopeClause}
        LIMIT 1`,
      [input.sourceLanguage, input.targetLanguage, input.category, normalized, ...scopeParams],
    );
    return row ? rowToEntry(row) : undefined;
  }

  /** The scope a write occupies: character, else series, else global. */
  private static scopeFor(input: {
    seriesId?: string;
    characterId?: string;
  }): { scopeClause: string; scopeParams: unknown[] } {
    if (input.characterId) {
      return { scopeClause: 'character_id = ?', scopeParams: [input.characterId] };
    }
    if (input.seriesId) {
      return {
        scopeClause: 'series_id = ? AND character_id IS NULL',
        scopeParams: [input.seriesId],
      };
    }
    return { scopeClause: 'series_id IS NULL AND character_id IS NULL', scopeParams: [] };
  }

  private requireById(id: number): KnowledgeEntry {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM knowledge WHERE id = ?', [id]);
    if (!row) {
      throw new Error(`knowledge entry ${id} disappeared`);
    }
    return rowToEntry(row);
  }

  /**
   * Resolves a term for a request, most-specific scope first.
   *
   * Character beats series beats genre beats global: "先生" for a specific
   * character must win over the series default. Ties break on confidence.
   */
  resolve(
    sourceLanguage: string,
    targetLanguage: string,
    term: string,
    options: KnowledgeLookupOptions = {},
  ): KnowledgeEntry | undefined {
    const normalized = normalizeTerm(term);
    if (!normalized) {
      return undefined;
    }
    const conditions: string[] = ['normalized_term = ?'];
    const params: unknown[] = [sourceLanguage, targetLanguage, normalized];

    if (options.category) {
      conditions.push('category = ?');
      params.push(options.category);
    }
    if (options.minConfidence !== undefined) {
      conditions.push('confidence >= ?');
      params.push(options.minConfidence);
    }

    // Narrowing set, most specific first: a request scoped to a character may
    // fall back to the series and global entries, but never the reverse.
    const scopes: string[] = [];
    if (options.characterId) {
      scopes.push('(character_id = ?)');
      params.push(options.characterId);
    }
    if (options.seriesId) {
      scopes.push('(series_id = ? AND character_id IS NULL)');
      params.push(options.seriesId);
    }
    scopes.push('(series_id IS NULL AND character_id IS NULL)');

    const row = this.db.get<Record<string, unknown>>(
      `SELECT * FROM knowledge
        WHERE source_language = ? AND target_language = ? AND ${conditions.join(' AND ')}
          AND (${scopes.join(' OR ')})
        ORDER BY
          CASE
            WHEN character_id IS NOT NULL THEN 0
            WHEN series_id IS NOT NULL THEN 1
            ELSE 2
          END,
          confidence DESC,
          usage_count DESC,
          updated_at DESC
        LIMIT 1`,
      params,
    );
    return row ? rowToEntry(row) : undefined;
  }

  /** Batch lookup: one query for a whole chapter instead of N queries. */
  resolveMany(
    sourceLanguage: string,
    targetLanguage: string,
    terms: string[],
    options: KnowledgeLookupOptions = {},
  ): Map<string, KnowledgeEntry> {
    const found = new Map<string, KnowledgeEntry>();
    const unique = [...new Set(terms.map((t) => normalizeTerm(t)).filter((t) => t.length > 0))];
    if (unique.length === 0) {
      return found;
    }
    // Chunked to stay under SQLite's variable limit.
    const chunkSize = 400;
    for (let i = 0; i < unique.length; i += chunkSize) {
      const chunk = unique.slice(i, i + chunkSize);
      const placeholders = chunk.map(() => '?').join(',');
      const conditions: string[] = [`normalized_term IN (${placeholders})`];
      const params: unknown[] = [sourceLanguage, targetLanguage, ...chunk];
      if (options.category) {
        conditions.push('category = ?');
        params.push(options.category);
      }
      if (options.minConfidence !== undefined) {
        conditions.push('confidence >= ?');
        params.push(options.minConfidence);
      }
      const scopes: string[] = [];
      if (options.characterId) {
        scopes.push('(character_id = ?)');
        params.push(options.characterId);
      }
      if (options.seriesId) {
        scopes.push('(series_id = ? AND character_id IS NULL)');
        params.push(options.seriesId);
      }
      scopes.push('(series_id IS NULL AND character_id IS NULL)');

      const rows = this.db.all<Record<string, unknown>>(
        `SELECT * FROM knowledge
          WHERE source_language = ? AND target_language = ? AND ${conditions.join(' AND ')}
            AND (${scopes.join(' OR ')})
          ORDER BY confidence DESC, usage_count DESC`,
        params,
      );
      for (const row of rows) {
        const entry = rowToEntry(row);
        if (!found.has(entry.normalizedTerm)) {
          found.set(entry.normalizedTerm, entry);
        }
      }
    }
    return found;
  }

  /** Records a use so frequently-hit terms rank higher and prewarm sooner. */
  recordUsage(id: number): void {
    this.db.run(
      `UPDATE knowledge
          SET usage_count = usage_count + 1, last_used_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = ?`,
      [id],
    );
  }

  list(filter: {
    sourceLanguage?: string;
    targetLanguage?: string;
    category?: KnowledgeCategory;
    verificationState?: VerificationState;
    limit?: number;
  } = {}): KnowledgeEntry[] {
    const conditions: string[] = ['1'];
    const params: unknown[] = [];
    if (filter.sourceLanguage) {
      conditions.push('source_language = ?');
      params.push(filter.sourceLanguage);
    }
    if (filter.targetLanguage) {
      conditions.push('target_language = ?');
      params.push(filter.targetLanguage);
    }
    if (filter.category) {
      conditions.push('category = ?');
      params.push(filter.category);
    }
    if (filter.verificationState) {
      conditions.push('verification_state = ?');
      params.push(filter.verificationState);
    }
    params.push(Math.min(filter.limit ?? 200, 5000));
    return this.db
      .all<Record<string, unknown>>(
        `SELECT * FROM knowledge WHERE ${conditions.join(' AND ')}
          ORDER BY updated_at DESC LIMIT ?`,
        params,
      )
      .map(rowToEntry);
  }

  setVerificationState(id: number, state: VerificationState): KnowledgeEntry {
    const result = this.db.run(
      `UPDATE knowledge SET verification_state = ?, version = version + 1,
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
      [state, id],
    );
    if (result.changes === 0) {
      throw new Error(`knowledge entry ${id} not found`);
    }
    return this.requireById(id);
  }

  delete(id: number): boolean {
    return this.db.run('DELETE FROM knowledge WHERE id = ?', [id]).changes > 0;
  }

  count(filter: { sourceLanguage?: string; verificationState?: VerificationState } = {}): number {
    const conditions: string[] = ['1'];
    const params: unknown[] = [];
    if (filter.sourceLanguage) {
      conditions.push('source_language = ?');
      params.push(filter.sourceLanguage);
    }
    if (filter.verificationState) {
      conditions.push('verification_state = ?');
      params.push(filter.verificationState);
    }
    const row = this.db.get<{ n: number }>(
      `SELECT COUNT(*) AS n FROM knowledge WHERE ${conditions.join(' AND ')}`,
      params,
    );
    return Number(row?.n ?? 0);
  }

  /** Highest version across entries: the glossary/memory cache-key component. */
  currentVersion(): number {
    const row = this.db.get<{ v: number }>('SELECT COALESCE(MAX(version), 0) AS v FROM knowledge');
    return Number(row?.v ?? 0);
  }
}