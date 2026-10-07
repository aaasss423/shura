/**
 * Shared domain types.
 *
 * Nothing here mentions a concrete engine. The engine boundary lives in
 * src/engine/engine.ts so that service, chapter, REST and UI layers can be
 * written against these contracts only.
 */

export type LanguageCode = string;

/** 'auto' asks the service to detect the source language. */
export const AUTO_LANGUAGE = 'auto';

export interface LanguageInfo {
  code: LanguageCode;
  name: string;
  nativeName?: string;
  direction: 'ltr' | 'rtl';
}

export interface LanguageDetectionResult {
  language: LanguageCode;
  confidence: number;
  /** Script evidence used for the decision, useful for debugging. */
  evidence: LanguageEvidence;
  /** Alternative candidates ordered by confidence. */
  alternatives: Array<{ language: LanguageCode; confidence: number }>;
}

export interface LanguageEvidence {
  arabicChars: number;
  latinChars: number;
  hiragana: number;
  katakana: number;
  han: number;
  hangul: number;
  cyrillic: number;
  totalSignificant: number;
  /** Convenience sum of hiragana + katakana. */
  kanaCount: number;
}

export interface TranslationOptions {
  /** Force a specific source language instead of detection. */
  sourceLanguage?: LanguageCode | typeof AUTO_LANGUAGE;
  targetLanguage: LanguageCode;
  /** Engine id; defaults to the configured engine. */
  engine?: string;
  /** Disable cache read/write for this call. */
  noCache?: boolean;
  /** Ignore stored cache entries but still write fresh results. */
  refresh?: boolean;
  /** Per-call timeout override in milliseconds. */
  timeoutMs?: number;
  /** Per-call retry override. */
  retries?: number;
  /** Prefer cached results even if TTL expired (offline-friendly). */
  acceptStaleCache?: boolean;
  /** Optional preceding context handed to engines that support it. Never part of the cache key. */
  contextBefore?: string;
  /** Free-form engine hints; only stable hints may affect the cache key. */
  hints?: Record<string, string>;
}

export interface TranslationResult {
  text: string;
  sourceLanguage: LanguageCode;
  detectedLanguage?: LanguageCode;
  targetLanguage: LanguageCode;
  engine: string;
  fromCache: boolean;
  segments: number;
  elapsedMs: number;
  quality?: QualityReport;
}

export interface QualityIssue {
  kind:
    | 'empty_translation'
    | 'untranslated'
    | 'repetition'
    | 'source_target_mixed'
    | 'truncated'
    | 'script_mismatch'
    | 'numeric_loss';
  severity: 'warning' | 'error';
  message: string;
  segmentIndex?: number;
}

export interface QualityReport {
  ok: boolean;
  score: number;
  issues: QualityIssue[];
}

export interface Segment {
  index: number;
  text: string;
  /** Preserved separator (newline structure) to re-join on merge. */
  trailing?: string;
  forced?: boolean;
}

export interface SegmentedText {
  segments: Segment[];
  /** Original text when segmentation is a no-op (short input). */
  whole?: boolean;
}

export interface MergedText {
  text: string;
  missingSegments: number[];
  originalSegments: number[];
}

export interface TranslationProgress {
  totalSegments: number;
  completedSegments: number;
  failedSegments: number;
  cachedSegments: number;
  currentSegment?: number;
  state: 'pending' | 'running' | 'completed' | 'cancelled' | 'failed';
}

export interface ChapterSegmentInput {
  id?: string;
  text: string;
  /** Optional speaker label preserved verbatim in the output. */
  speaker?: string;
}

export interface ChapterSegmentResult {
  index: number;
  id?: string;
  source: string;
  translated: string;
  /** True when translation failed and the original text was kept. */
  fallback: boolean;
  fromCache: boolean;
  attempts: number;
  error?: { code: string; message: string };
  elapsedMs: number;
}

export interface ChapterTranslationRequest {
  segments: ChapterSegmentInput[];
  sourceLanguage?: LanguageCode | typeof AUTO_LANGUAGE;
  targetLanguage: LanguageCode;
  engine?: string;
  noCache?: boolean;
  /** Joiner inserted between translated segment texts. */
  joinWith?: string;
  deadlineMs?: number;
  concurrency?: number;
  /** What to do when a segment fails permanently. */
  failurePolicy?: 'partial' | 'abort';
}

export interface ChapterTranslationResult {
  text: string;
  targetLanguage: LanguageCode;
  engine: string;
  segments: ChapterSegmentResult[];
  progress: TranslationProgress;
  elapsedMs: number;
  degraded: boolean;
}

export interface EngineTranslationRequest {
  text: string;
  sourceLanguage: LanguageCode | typeof AUTO_LANGUAGE;
  targetLanguage: LanguageCode;
  hints?: Record<string, string>;
  timeoutMs?: number;
  /** Abort signal; engines must honour it. */
  signal?: AbortSignal;
}

export interface EngineTranslationResponse {
  text: string;
  engine: string;
  detectedSourceLanguage?: LanguageCode;
  /** Engine-reported confidence in the translation, 0..1. */
  confidence?: number;
  raw?: unknown;
}

export interface EngineLanguagePairSupport {
  source: LanguageCode;
  target: LanguageCode;
}

/** Hard limits an engine imposes, used by segmentation. */
export interface EngineLimits {
  maxCharsPerRequest: number;
  maxCharsPerSecond?: number;
}

export interface ProgressListener {
  (progress: TranslationProgress): void;
}