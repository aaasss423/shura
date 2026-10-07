/**
 * Public entry point for embedding the translation platform in another
 * application (for example a reader). Import from here, never from internals.
 */

export { Translator, createTranslator } from './translator/translator';
export type { TranslatorInit, TranslateRequest, ChapterTranslateRequest } from './translator/translator';

export { TranslationService } from './service/translationService';
export type { TranslationServiceOptions, ServiceTranslateContext } from './service/translationService';

export { ChapterTranslator } from './chapter/chapterTranslator';
export type { ChapterTranslatorOptions } from './chapter/chapterTranslator';

export type { TranslationEngine, EngineHealth } from './engine/engine';
export { EngineRegistry, createDefaultRegistry, EchoEngine, MyMemoryEngine } from './engine/registry';
export type { BuiltInEngineOptions } from './engine/registry';

export {
  detectLanguage,
  detectLanguageDetailed,
  resolveSourceLanguage,
  collectEvidence,
  UNKNOWN_LANGUAGE,
} from './language/detect';
export {
  normalizeLanguageCode,
  getLanguageInfo,
  isKnownLanguage,
  isRtlLanguage,
  listAllLanguages,
  listSourceLanguages,
  listTargetLanguages,
} from './language/registry';

export {
  normalizeArabic,
  countCharacters,
  countNonWhitespaceCharacters,
  arabicRatio,
  containsArabic,
  extractNumbers,
  stripBidiMarks,
  applyRtlIsolation,
  toArabicDigits,
  toWesternDigits,
  normalizeParagraphs,
} from './arabic/arabic';

export { segmentText, mergeSegments, DEFAULT_SEGMENTATION } from './segmentation/segment';
export type { SegmentationOptions } from './segmentation/segment';

export { buildCacheKey, normalizeForCacheKey, CACHE_KEY_VERSION } from './cache/key';
export type { CacheKeyInput } from './cache/key';
export { MemoryCache } from './cache/memoryCache';
export { FileCache } from './cache/fileCache';
export { TieredCache } from './cache/tieredCache';
export type { CacheEntry, CacheGetResult, TranslationCache } from './cache/types';

export { CancellationToken } from './core/cancellation';
export type { CancelReason } from './core/cancellation';
export { withTimeout, DeadlineBudget, withTokenAndTimeout } from './core/timeout';
export { withRetry, shouldRetry, DEFAULT_RETRY_POLICY } from './core/retry';
export type { RetryPolicy } from './core/retry';

export { checkQuality, aggregateQuality } from './service/quality';
export type { QualityCheckInput } from './service/quality';

export * from './core/errors';
export * from './core/types';
export { createLogger, silentLogger } from './core/logger';
export type { Logger, LogLevel } from './core/logger';
export { loadConfig, describeConfig } from './config/index';
export type { AppConfig } from './config/index';
export {
  validateTranslateInput,
  validateDetectInput,
  validateChapterInput,
  assertReasonableSize,
} from './core/validation';