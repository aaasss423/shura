/**
 * Translation Memory and Glossary.
 *
 * Both are "before the model" layers (L3 and terminology). TM holds curated
 * translations and is consulted first because a verified previous translation
 * beats regenerating. Glossary holds enforced terminology with force / prefer /
 * forbid semantics.
 */

import type { Database } from '../db/database';
import { normalizeTerm } from '../knowledge/repository';
import type { HoldoutGuardLike } from '../eval/guard';

// ---------------------------------------------------------------------------
// Translation memory
// ---------------------------------------------------------------------------

export interface MemoryEntry {
  id: number;
  sourceLanguage: string;
  targetLanguage: string;
  sourceText: string;
  normalizedText: string;
  targetText: string;
  context?: string;
  seriesId?: string;
  characterId?: string;
  engine?: string;
  modelId?: string;
  modelVersion?: string;
  confidence: number;
  provenance: string;
  usageCount: number;
}

export interface MemoryInput {
  sourceLanguage: string;
  targetLanguage: string;
  sourceText: string;
  targetText: string;
  context?: string;
  seriesId?: string;
  characterId?: string;
  engine?: string;
  modelId?: string;
  modelVersion?: string;
  confidence?: number;
  provenance?: string;
}

/**
 * Normalizes a sentence for TM matching: same rules as term keys.
 *
 * Bidi controls and zero-width marks are stripped. They are invisible, they are
 * what a copy-paste out of an Arabic document brings with it, and leaving them
 * in would let holdout text pass a substring check that is meant to stop it.
 */
export function normalizeForMemory(text: string): string {
  return text
    .normalize('NFKC')
    .replace(/[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export class TranslationMemoryRepository {
  /**
   * `holdout` blocks a translation whose source or Arabic target is an
   * evaluation holdout item. Unguarded, a benchmark output would be stored and
   * then returned as a "known good" hit on the next run.
   */
  constructor(
    private readonly db: Database,
    private readonly holdout?: HoldoutGuardLike,
  ) {}

  /**
   * Stores a translation. Confidence and provenance come from the quality layer,
   * never from the model itself — a model that "felt confident" must not become
   * a lookup hit for the next reader.
   */
  store(input: MemoryInput): MemoryEntry {
    this.holdout?.assertWrite({ sourceText: input.sourceText, targetText: input.targetText });
    const normalized = normalizeForMemory(input.sourceText);
    this.db.run(
      `INSERT INTO translation_memory
         (source_language, target_language, source_text, normalized_text, target_text, context,
          series_id, character_id, engine, model_id, model_version, confidence, provenance)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON CONFLICT (source_language, target_language, normalized_text) DO UPDATE SET
         target_text = excluded.target_text,
         context = excluded.context,
         confidence = excluded.confidence,
         provenance = excluded.provenance,
         model_id = excluded.model_id,
         model_version = excluded.model_version,
         updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
      [
        input.sourceLanguage,
        input.targetLanguage,
        input.sourceText,
        normalized,
        input.targetText,
        input.context ?? null,
        input.seriesId ?? null,
        input.characterId ?? null,
        input.engine ?? null,
        input.modelId ?? null,
        input.modelVersion ?? null,
        input.confidence ?? 0,
        input.provenance ?? 'approved',
      ],
    );
    return this.require(normalized, input.sourceLanguage, input.targetLanguage);
  }

  /**
   * Exact-then-scope lookup, above a confidence floor.
   *
   * The floor is the point of a memory: a 0.4-confidence entry should inform the
   * model, not replace it.
   */
  lookup(
    sourceLanguage: string,
    targetLanguage: string,
    sourceText: string,
    options: { minConfidence?: number; seriesId?: string; characterId?: string } = {},
  ): MemoryEntry | undefined {
    const normalized = normalizeForMemory(sourceText);
    const conditions = ['normalized_text = ?'];
    const params: unknown[] = [sourceLanguage, targetLanguage, normalized];
    if (options.minConfidence !== undefined) {
      conditions.push('confidence >= ?');
      params.push(options.minConfidence);
    }
    const scopes = ['(series_id IS NULL AND ? IS NULL)', '(series_id = ?)', '(character_id = ?)'];
    const row = this.db.get<Record<string, unknown>>(
      `SELECT * FROM translation_memory
        WHERE source_language = ? AND target_language = ? AND ${conditions.join(' AND ')}
          AND (${scopes.join(' OR ')})
        ORDER BY
          CASE WHEN character_id IS NOT NULL THEN 0 WHEN series_id IS NOT NULL THEN 1 ELSE 2 END,
          confidence DESC
        LIMIT 1`,
      [
        sourceLanguage,
        targetLanguage,
        ...params.slice(2),
        options.seriesId ?? null,
        options.seriesId ?? null,
        options.characterId ?? null,
      ],
    );
    return row ? rowToEntry(row) : undefined;
  }

  recordUsage(id: number): void {
    this.db.run('UPDATE translation_memory SET usage_count = usage_count + 1 WHERE id = ?', [id]);
  }

  list(filter: { sourceLanguage?: string; limit?: number } = {}): MemoryEntry[] {
    const params: unknown[] = [];
    let where = '1';
    if (filter.sourceLanguage) {
      where = 'source_language = ?';
      params.push(filter.sourceLanguage);
    }
    params.push(Math.min(filter.limit ?? 200, 5000));
    return this.db
      .all<Record<string, unknown>>(
        `SELECT * FROM translation_memory WHERE ${where} ORDER BY updated_at DESC LIMIT ?`,
        params,
      )
      .map(rowToEntry);
  }

  delete(id: number): boolean {
    return this.db.run('DELETE FROM translation_memory WHERE id = ?', [id]).changes > 0;
  }

  count(): number {
    return Number(this.db.get<{ n: number }>('SELECT COUNT(*) AS n FROM translation_memory')?.n ?? 0);
  }

  private require(normalized: string, source: string, target: string): MemoryEntry {
    const row = this.db.get<Record<string, unknown>>(
      'SELECT * FROM translation_memory WHERE source_language = ? AND target_language = ? AND normalized_text = ?',
      [source, target, normalized],
    );
    if (!row) {
      throw new Error('translation memory entry vanished after write');
    }
    return rowToEntry(row);
  }
}

function rowToEntry(row: Record<string, unknown>): MemoryEntry {
  return {
    id: Number(row.id),
    sourceLanguage: String(row.source_language),
    targetLanguage: String(row.target_language),
    sourceText: String(row.source_text),
    normalizedText: String(row.normalized_text),
    targetText: String(row.target_text),
    ...(row.context ? { context: String(row.context) } : {}),
    ...(row.series_id ? { seriesId: String(row.series_id) } : {}),
    ...(row.character_id ? { characterId: String(row.character_id) } : {}),
    ...(row.engine ? { engine: String(row.engine) } : {}),
    ...(row.model_id ? { modelId: String(row.model_id) } : {}),
    ...(row.model_version ? { modelVersion: String(row.model_version) } : {}),
    confidence: Number(row.confidence ?? 0),
    provenance: String(row.provenance ?? 'approved'),
    usageCount: Number(row.usage_count ?? 0),
  };
}

// ---------------------------------------------------------------------------
// Glossary
// ---------------------------------------------------------------------------

export type GlossaryMode = 'force' | 'prefer' | 'forbid';

export interface GlossaryEntry {
  id: number;
  sourceLanguage: string;
  targetLanguage: string;
  term: string;
  normalizedTerm: string;
  defaultTranslation?: string;
  forbidden?: string;
  variants: string[];
  mode: GlossaryMode;
  category: string;
  aliases: string[];
  context?: string;
  seriesId?: string;
  genre?: string;
  priority: number;
  confidence: number;
  version: number;
  enabled: boolean;
}

export interface GlossaryInput {
  sourceLanguage: string;
  targetLanguage: string;
  term: string;
  defaultTranslation?: string;
  forbidden?: string;
  /**
   * Known wrong renderings the model tends to produce for this term.
   *
   * Enforcement cannot match on the *source* term: after translation the source
   * no longer appears in the output. The prevention mechanism is the prompt
   * instruction; the repair mechanism is rewriting these variants.
   */
  variants?: string[];
  mode?: GlossaryMode;
  category?: string;
  aliases?: string[];
  context?: string;
  seriesId?: string;
  genre?: string;
  priority?: number;
  confidence?: number;
}

/** Applied to the model output after translation. */
export interface GlossaryEnforcement {
  replaced: Array<{ from: string; to: string; term: string }>;
  forbiddenHits: Array<{ term: string; forbidden: string }>;
}

export class GlossaryRepository {
  constructor(private readonly db: Database) {}

  upsert(input: GlossaryInput): GlossaryEntry {
    const normalized = normalizeTerm(input.term);
    this.db.run(
      `INSERT INTO glossary
         (source_language, target_language, term, normalized_term, default_translation, forbidden,
          variants, mode, category, aliases, context, series_id, genre, priority, confidence, version, enabled)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,1)
       ON CONFLICT (source_language, target_language, normalized_term) DO UPDATE SET
         default_translation = excluded.default_translation,
         forbidden = excluded.forbidden,
         variants = excluded.variants,
         mode = excluded.mode,
         category = excluded.category,
         aliases = excluded.aliases,
         context = excluded.context,
         series_id = excluded.series_id,
         genre = excluded.genre,
         priority = excluded.priority,
         confidence = excluded.confidence,
         version = version + 1,
         updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
      [
        input.sourceLanguage,
        input.targetLanguage,
        input.term,
        normalized,
        input.defaultTranslation ?? null,
        input.forbidden ?? null,
        JSON.stringify(input.variants ?? []),
        input.mode ?? 'prefer',
        input.category ?? 'term',
        JSON.stringify(input.aliases ?? []),
        input.context ?? null,
        input.seriesId ?? null,
        input.genre ?? null,
        input.priority ?? 0,
        input.confidence ?? 1,
      ],
    );
    const row = this.db.get<Record<string, unknown>>(
      'SELECT * FROM glossary WHERE source_language = ? AND target_language = ? AND normalized_term = ?',
      [input.sourceLanguage, input.targetLanguage, normalized],
    );
    if (!row) {
      throw new Error('glossary entry vanished after write');
    }
    return glossaryRowToEntry(row);
  }

  /** Applicable entries for a request, highest priority first. */
  list(
    sourceLanguage: string,
    targetLanguage: string,
    options: { seriesId?: string; genre?: string; minConfidence?: number } = {},
  ): GlossaryEntry[] {
    const conditions = [
      'source_language = ?',
      'target_language = ?',
      'enabled = 1',
      '((series_id IS NULL) OR (series_id = ?))',
      '((genre IS NULL) OR (genre = ?))',
    ];
    const params: unknown[] = [
      sourceLanguage,
      targetLanguage,
      options.seriesId ?? null,
      options.genre ?? null,
    ];
    if (options.minConfidence !== undefined) {
      conditions.push('confidence >= ?');
      params.push(options.minConfidence);
    }
    return this.db
      .all<Record<string, unknown>>(
        `SELECT * FROM glossary WHERE ${conditions.join(' AND ')} ORDER BY priority DESC, confidence DESC`,
        params,
      )
      .map(glossaryRowToEntry);
  }

  setEnabled(id: number, enabled: boolean): void {
    this.db.run(
      `UPDATE glossary SET enabled = ?, version = version + 1,
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
      [enabled ? 1 : 0, id],
    );
  }

  delete(id: number): boolean {
    return this.db.run('DELETE FROM glossary WHERE id = ?', [id]).changes > 0;
  }

  count(): number {
    return Number(this.db.get<{ n: number }>('SELECT COUNT(*) AS n FROM glossary')?.n ?? 0);
  }

  /** Aggregate version, used in the cache key so edits invalidate entries. */
  currentVersion(): number {
    return Number(this.db.get<{ v: number }>('SELECT COALESCE(MAX(version), 0) AS v FROM glossary')?.v ?? 0);
  }
}

/**
 * Enforces the glossary on translated text.
 *
 * `force` rewrites the target to the required form; `forbid` reports a hit so
 * quality can penalise it. Ordering is highest priority first, and longest-term
 * first within a priority, so `魔王` is not partially rewritten by `魔`.
 */
export function enforceGlossary(
  targetText: string,
  entries: GlossaryEntry[],
): { text: string; enforcement: GlossaryEnforcement; satisfied: number; missingRequired: string[] } {
  let text = targetText;
  const replaced: GlossaryEnforcement['replaced'] = [];
  const forbiddenHits: GlossaryEnforcement['forbiddenHits'] = [];
  const missingRequired: string[] = [];
  let satisfied = 0;

  // Highest priority first, then longest term: 「魔王の 力」 must be rewritten
  // before 「魔」 is considered.
  const ordered = [...entries].sort((a, b) => b.priority - a.priority || b.term.length - a.term.length);

  for (const entry of ordered) {
    if (entry.forbidden && text.includes(entry.forbidden)) {
      forbiddenHits.push({ term: entry.term, forbidden: entry.forbidden });
    }

    if (entry.mode !== 'force') {
      // A preferred (non-forced) term still needs to be present, but a missing
      // one is only worth reporting.
      if (entry.defaultTranslation && text.includes(entry.defaultTranslation)) {
        satisfied += 1;
      } else if (entry.defaultTranslation) {
        missingRequired.push(entry.defaultTranslation);
      }
      continue;
    }

    // Repair: rewrite known wrong renderings to the required form.
    for (const variant of [...entry.variants].sort((a, b) => b.length - a.length)) {
      if (variant.length > 0 && text.includes(variant)) {
        text = text.split(variant).join(entry.defaultTranslation ?? variant);
        replaced.push({ from: variant, to: entry.defaultTranslation ?? variant, term: entry.term });
      }
    }

    // Presence is checked *after* repair, so a repaired term counts as satisfied.
    if (entry.defaultTranslation) {
      if (text.includes(entry.defaultTranslation)) {
        satisfied += 1;
      } else {
        missingRequired.push(entry.defaultTranslation);
      }
    }
  }

  return { text, enforcement: { replaced, forbiddenHits }, satisfied, missingRequired };
}

/**
 * Renders glossary entries as model instructions.
 *
 * Kept short on purpose: a 200-term glossary in the prompt fights the model's
 * own fluency. High-priority entries go in as instructions; the rest are enforced
 * deterministically afterwards.
 */
export function renderGlossaryInstructions(entries: GlossaryEntry[], maxEntries = 40): string {
  const force = entries.filter((e) => e.mode === 'force' && e.defaultTranslation).slice(0, maxEntries);
  if (force.length === 0) {
    return '';
  }
  const lines = force.map(
    (e) => `- "${e.term}" => "${e.defaultTranslation}"${e.context ? ` (context: ${e.context})` : ''}`,
  );
  return `Use these required translations exactly:\n${lines.join('\n')}`;
}

function glossaryRowToEntry(row: Record<string, unknown>): GlossaryEntry {
  const parseList = (value: unknown): string[] => {
    try {
      const parsed = value ? (JSON.parse(String(value)) as unknown) : [];
      return Array.isArray(parsed) ? parsed.map(String) : [];
    } catch {
      return [];
    }
  };
  const aliases = parseList(row.aliases);
  const variants = parseList(row.variants);
  return {
    id: Number(row.id),
    sourceLanguage: String(row.source_language),
    targetLanguage: String(row.target_language),
    term: String(row.term),
    normalizedTerm: String(row.normalized_term),
    ...(row.default_translation ? { defaultTranslation: String(row.default_translation) } : {}),
    ...(row.forbidden ? { forbidden: String(row.forbidden) } : {}),
    variants,
    mode: String(row.mode ?? 'prefer') as GlossaryMode,
    category: String(row.category ?? 'term'),
    aliases,
    ...(row.context ? { context: String(row.context) } : {}),
    ...(row.series_id ? { seriesId: String(row.series_id) } : {}),
    ...(row.genre ? { genre: String(row.genre) } : {}),
    priority: Number(row.priority ?? 0),
    confidence: Number(row.confidence ?? 1),
    version: Number(row.version ?? 1),
    enabled: Number(row.enabled ?? 1) === 1,
  };
}