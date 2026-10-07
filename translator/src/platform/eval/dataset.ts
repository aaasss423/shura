/**
 * Evaluation dataset: types, loader, validation.
 *
 * Honesty rules baked into the types:
 *  - Every item declares its `referenceProvenance`. Machine-drafted references
 *    may only support an *agreement* claim, never an absolute quality claim.
 *  - The holdout split is a distinct type marker so it cannot be passed to a
 *    knowledge/memory writer by accident.
 */

export const EVAL_SPLITS = ['dev', 'test'] as const;
export type EvalSplit = (typeof EVAL_SPLITS)[number];

export const EVAL_CATEGORIES = [
  'dialogue',
  'colloquial',
  'slang',
  'idiom',
  'honorific',
  'name',
  'place',
  'sound_effect',
  'short',
  'long',
  'context_dependent',
  'manga_expression',
] as const;
export type EvalCategory = (typeof EVAL_CATEGORIES)[number];

export const EVAL_SOURCE_LANGUAGES = ['ja', 'zh', 'ko', 'en'] as const;
export type EvalSourceLanguage = (typeof EVAL_SOURCE_LANGUAGES)[number];

/**
 * Where the Arabic reference came from.
 *
 * `ai_drafted` is the honest default for this corpus. Until an item is promoted
 * to `human_verified`, scores computed against it describe *agreement with this
 * draft*, not translation quality.
 */
export type ReferenceProvenance = 'ai_drafted' | 'human_verified';

export interface EvalItem {
  id: string;
  sourceLanguage: EvalSourceLanguage;
  targetLanguage: 'ar';
  category: EvalCategory;
  difficulty: 'easy' | 'medium' | 'hard';
  split: EvalSplit;
  sourceText: string;
  referenceArabic: string;
  context?: string;
  series?: string;
  chapter?: number;
  character?: string;
  /** Defaults to `ai_drafted` when absent. */
  referenceProvenance?: ReferenceProvenance;
  notes?: string;
}

export interface EvalManifest {
  version: string;
  created: string;
  description: string;
  languages: string[];
  target_language: string;
  splits: Record<string, string>;
  reference_provenance: Record<string, string>;
  categories: string[];
  leakage_policy: { holdout_enforcement: string; guarantees: string[] };
}

export interface DatasetStats {
  version: string;
  total: number;
  byLanguage: Record<string, number>;
  bySplit: Record<string, number>;
  byCategory: Record<string, number>;
  humanVerified: number;
  aiDrafted: number;
}

export class DatasetError extends Error {
  readonly code = 'DATASET_INVALID';
  constructor(
    message: string,
    readonly itemId?: string,
  ) {
    super(message);
    this.name = 'DatasetError';
  }
}

function requireString(value: unknown, field: string, id: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new DatasetError(`${field} must be a non-empty string`, id);
  }
  return value;
}

function parseItem(raw: Record<string, unknown>): EvalItem {
  const id = requireString(raw.id, 'id', '<unknown>');
  const sourceLanguage = raw.source_language as EvalSourceLanguage;
  if (!EVAL_SOURCE_LANGUAGES.includes(sourceLanguage)) {
    throw new DatasetError(`unsupported source_language "${String(raw.source_language)}"`, id);
  }
  if (raw.target_language !== 'ar') {
    throw new DatasetError(`target_language must be "ar", received "${String(raw.target_language)}"`, id);
  }
  const category = raw.category as EvalCategory;
  if (!EVAL_CATEGORIES.includes(category)) {
    throw new DatasetError(`unknown category "${String(raw.category)}"`, id);
  }
  const split = raw.split as EvalSplit;
  if (!EVAL_SPLITS.includes(split)) {
    throw new DatasetError(`split must be dev|test, received "${String(raw.split)}"`, id);
  }
  const difficulty = (raw.difficulty as EvalItem['difficulty']) ?? 'medium';
  if (!['easy', 'medium', 'hard'].includes(difficulty)) {
    throw new DatasetError(`unknown difficulty "${String(raw.difficulty)}"`, id);
  }

  return {
    id,
    sourceLanguage,
    targetLanguage: 'ar',
    category,
    difficulty,
    split,
    sourceText: requireString(raw.source_text, 'source_text', id),
    referenceArabic: requireString(raw.reference_arabic, 'reference_arabic', id),
    ...(raw.context ? { context: String(raw.context) } : {}),
    ...(raw.series ? { series: String(raw.series) } : {}),
    ...(typeof raw.chapter === 'number' ? { chapter: raw.chapter } : {}),
    ...(raw.character ? { character: String(raw.character) } : {}),
    // Absent provenance is treated as machine-drafted, never as verified.
    referenceProvenance: (raw.reference_provenance as ReferenceProvenance) ?? 'ai_drafted',
    ...(raw.notes ? { notes: String(raw.notes) } : {}),
  };
}

/** Every item's effective provenance, defaulting to the conservative value. */
export function provenanceOf(item: EvalItem): ReferenceProvenance {
  return item.referenceProvenance ?? 'ai_drafted';
}

export interface EvalDataset {
  manifest: EvalManifest;
  items: EvalItem[];
  stats: DatasetStats;
}

export interface BuildDatasetInput {
  manifest: EvalManifest;
  /** Raw JSON per source language. */
  rawByLanguage: Partial<Record<EvalSourceLanguage, unknown>>;
}

/**
 * Builds and validates a dataset.
 *
 * Validation is strict and fails loudly: a silently malformed corpus produces
 * benchmark numbers that mean nothing.
 */
export function buildDataset(input: BuildDatasetInput): EvalDataset {
  const items: EvalItem[] = [];
  const seen = new Set<string>();

  for (const language of EVAL_SOURCE_LANGUAGES) {
    const raw = input.rawByLanguage[language];
    if (raw === undefined) {
      continue;
    }
    if (!Array.isArray(raw)) {
      throw new DatasetError(`corpus for "${language}" must be an array`);
    }
    for (const entry of raw) {
      if (typeof entry !== 'object' || entry === null) {
        throw new DatasetError(`corpus entry for "${language}" must be an object`);
      }
      const item = parseItem(entry as Record<string, unknown>);
      if (seen.has(item.id)) {
        throw new DatasetError(`duplicate item id "${item.id}"`, item.id);
      }
      seen.add(item.id);
      items.push(item);
    }
  }

  if (items.length === 0) {
    throw new DatasetError('dataset is empty');
  }

  const stats: DatasetStats = {
    version: input.manifest.version,
    total: items.length,
    byLanguage: {},
    bySplit: {},
    byCategory: {},
    humanVerified: 0,
    aiDrafted: 0,
  };

  for (const item of items) {
    stats.byLanguage[item.sourceLanguage] = (stats.byLanguage[item.sourceLanguage] ?? 0) + 1;
    stats.bySplit[item.split] = (stats.bySplit[item.split] ?? 0) + 1;
    stats.byCategory[item.category] = (stats.byCategory[item.category] ?? 0) + 1;
    if (provenanceOf(item) === 'human_verified') {
      stats.humanVerified += 1;
    } else {
      stats.aiDrafted += 1;
    }
  }

  return { manifest: input.manifest, items, stats };
}

export function filterDataset(
  dataset: EvalDataset,
  filter: {
    split?: EvalSplit;
    language?: EvalSourceLanguage;
    category?: EvalCategory;
    ids?: string[];
  } = {},
): EvalItem[] {
  return dataset.items.filter((item) => {
    if (filter.split && item.split !== filter.split) {
      return false;
    }
    if (filter.language && item.sourceLanguage !== filter.language) {
      return false;
    }
    if (filter.category && item.category !== filter.category) {
      return false;
    }
    if (filter.ids && !filter.ids.includes(item.id)) {
      return false;
    }
    return true;
  });
}

/** Pairs covered by the corpus, e.g. `ja->ar`. */
export function pairsIn(dataset: EvalDataset): string[] {
  const pairs = new Set<string>();
  for (const item of dataset.items) {
    pairs.add(`${item.sourceLanguage}->${item.targetLanguage}`);
  }
  return [...pairs].sort();
}

/**
 * Whether a dataset supports an absolute quality claim.
 *
 * Only a corpus whose references are human-verified does. This is what stops a
 * benchmark from promoting an AI-drafted reference into a quality claim.
 */
export function supportsQualityClaim(dataset: EvalDataset): boolean {
  const scored = dataset.items.filter((item) => item.split === 'test');
  if (scored.length === 0) {
    return false;
  }
  return scored.every((item) => provenanceOf(item) === 'human_verified');
}

/** The caveat that must accompany any report produced from this dataset. */
export function claimCeiling(dataset: EvalDataset): string {
  if (supportsQualityClaim(dataset)) {
    return 'References are human-verified: scores may be reported as translation quality for these pairs.';
  }
  const verified = dataset.stats.humanVerified;
  return (
    `References are NOT human-verified (${verified} of ${dataset.stats.total} items verified). ` +
    'Scores measure agreement with a machine-drafted Arabic reference, not absolute translation quality. ' +
    'They are comparable between models but must not be presented as a quality verdict.'
  );
}