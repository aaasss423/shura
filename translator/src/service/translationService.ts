/**
 * Translation Service: the orchestration layer.
 *
 * Responsibilities, in order of execution:
 *   validation -> language resolution -> cache lookup -> segmentation ->
 *   engine call -> retry -> per-request timeout -> service deadline ->
 *   cancellation -> quality check -> cache store -> merge
 *
 * It depends only on the TranslationEngine interface. No MyMemory knowledge
 * leaks above this file, which is what allows the engine to be swapped without
 * touching service, chapter, REST or UI code.
 */

import { EmptyInputError, isTranslationError, toTranslationError, TranslationError } from '../core/errors';
import { CancellationToken } from '../core/cancellation';
import { DeadlineBudget, raceCancellation, withTimeout } from '../core/timeout';
import { withRetry } from '../core/retry';
import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';
import { buildCacheKey } from '../cache/key';
import type { TranslationCache } from '../cache/types';
import { isMemoryOnlyCache } from '../cache/types';
import type { TranslationEngine } from '../engine/engine';
import { resolveSourceLanguage } from '../language/detect';
import { normalizeLanguageCode } from '../language/registry';
import { mergeSegments, segmentText } from '../segmentation/segment';
import type { SegmentationOptions } from '../segmentation/segment';
import { countCharacters } from '../arabic/arabic';
import { validateTranslateInput } from '../core/validation';
import { checkQuality } from './quality';
import type {
  EngineTranslationResponse,
  QualityReport,
  Segment,
  TranslationOptions,
  TranslationResult,
} from '../core/types';

export interface TranslationServiceOptions {
  resolveEngine: (engineId?: string) => TranslationEngine;
  defaultEngineId: string;
  cache?: TranslationCache<unknown>;
  cacheEnabled?: boolean;
  defaultTimeoutMs: number;
  defaultChapterDeadlineMs: number;
  retryPolicy: import('../core/retry').RetryPolicy;
  segmentation?: Partial<SegmentationOptions>;
  logger?: Logger;
  /** Set false to disable per-request quality checks (they are on by default). */
  qualityChecks?: boolean;
}

export interface ServiceTranslateContext {
  token?: CancellationToken;
  /** Overall budget for this operation, including cache waits. */
  deadlineMs?: number;
  onProgress?: (event: { completedSegments: number; totalSegments: number }) => void;
}

interface CachedValue {
  text: string;
  engine: string;
  sourceLanguage: string;
  detectedLanguage?: string;
  quality?: QualityReport;
}

const MAX_PARALLEL_SEGMENTS = 4;

export class TranslationService {
  private readonly options: TranslationServiceOptions;
  private readonly logger: Logger;

  constructor(options: TranslationServiceOptions) {
    this.options = options;
    this.logger = options.logger ?? silentLogger;
  }

  get defaultEngineId(): string {
    return this.options.defaultEngineId;
  }

  async translate(
    options: TranslationOptions & { text: string },
    context: ServiceTranslateContext = {},
  ): Promise<TranslationResult> {
    const started = Date.now();
    const token = context.token ?? CancellationToken.none();
    // Pre-flight: an already-cancelled request must not touch cache or network.
    token.throwIfCancelled();

    const validated = validateTranslateInput({
      text: options.text,
      sourceLanguage: options.sourceLanguage ?? 'auto',
      targetLanguage: options.targetLanguage,
    });

    const engineKey = options.engine ?? this.options.defaultEngineId;
    const engine = this.options.resolveEngine(engineKey);
    const targetLanguage = validated.targetLanguage;

    if (!engine.supportsPair('en', targetLanguage)) {
      // Pair support for the *actual* source is checked after detection below;
      // this catches obviously unsupported targets early.
      if (!engine.getTargetLanguages().some((l) => l.code === targetLanguage)) {
        throw new TranslationError('UNSUPPORTED_PAIR', `engine ${engine.id} cannot target ${targetLanguage}`, {
          retryable: false,
          engine: engine.id,
        });
      }
    }

    const resolved = resolveSourceLanguage(validated.sourceLanguage, validated.text);
    const sourceLanguage = resolved.language;

    if (!engine.supportsPair(sourceLanguage, targetLanguage)) {
      throw new TranslationError(
        'UNSUPPORTED_PAIR',
        `engine ${engine.id} does not support ${sourceLanguage} -> ${targetLanguage}`,
        { retryable: false, engine: engine.id },
      );
    }

    const cacheEnabled = this.options.cacheEnabled !== false && this.options.cache !== undefined;
    const cache = this.options.cache;
    const hints = options.hints;

    // contextBefore is deliberately excluded from the cache key.
    // Keyed on the *requested* engine id, not engine.id, so two registered
    // engines can never share cache entries even if they report the same name.
    const cacheKey = buildCacheKey({
      text: validated.text,
      sourceLanguage,
      targetLanguage,
      engine: engineKey,
      ...(hints ? { hints } : {}),
    });

    if (cacheEnabled && cache && !options.noCache && !options.refresh) {
      token.throwIfCancelled();
      const hit = await cache.get(cacheKey, {
        acceptStale: options.acceptStaleCache === true,
      });
      if (hit.hit && hit.entry) {
        const value = hit.entry.value as unknown as CachedValue;
        return {
          text: value.text,
          sourceLanguage,
          ...(value.detectedLanguage ? { detectedLanguage: value.detectedLanguage } : {}),
          targetLanguage,
          engine: value.engine,
          fromCache: true,
          segments: 1,
          elapsedMs: Date.now() - started,
          ...(value.quality ? { quality: value.quality } : {}),
        };
      }
    }

    const budget = new DeadlineBudget(context.deadlineMs ?? this.options.defaultChapterDeadlineMs, {
      message: 'translation deadline exceeded',
      onExpire: () => token.cancel({ reason: 'deadline', message: 'deadline exceeded' }),
    });

    const segmented = segmentText(validated.text, {
      ...(this.options.segmentation ?? {}),
      maxChars: Math.min(
        this.options.segmentation?.maxChars ?? engine.limits.maxCharsPerRequest,
        engine.limits.maxCharsPerRequest,
      ),
    });

    if (segmented.segments.length === 0) {
      throw new EmptyInputError();
    }

    const translatedSegments = new Map<number, string>();
    let segmentCount = 0;

    const translateSegment = async (segment: Segment): Promise<string> => {
      const result = await this.translateSegment({
        segment,
        engine,
        sourceLanguage,
        targetLanguage,
        timeoutMs: options.timeoutMs ?? this.options.defaultTimeoutMs,
        retries: options.retries,
        budget,
        token,
        cache,
        cacheKey,
        // noCache / refresh must also bypass the per-segment tier, otherwise a
        // "fresh" request silently returns the stored segment.
        cacheEnabled: cacheEnabled && options.noCache !== true && options.refresh !== true,
        hints,
      });
      return result.text;
    };

    if (segmented.segments.length === 1) {
      translatedSegments.set(0, await translateSegment(segmented.segments[0]!));
      segmentCount = 1;
    } else {
      const queue = [...segmented.segments];
      const workers = Array.from({ length: Math.min(MAX_PARALLEL_SEGMENTS, queue.length) }, async () => {
        for (;;) {
          token.throwIfCancelled();
          budget.throwIfExpired();
          const segment = queue.shift();
          if (!segment) {
            return;
          }
          const translated = await translateSegment(segment);
          translatedSegments.set(segment.index, translated);
          segmentCount += 1;
          context.onProgress?.({ completedSegments: segmentCount, totalSegments: segmented.segments.length });
        }
      });
      await Promise.all(workers);
    }

    const merged = mergeSegments(segmented.segments, translatedSegments);

    let quality: QualityReport | undefined;
    if (this.options.qualityChecks !== false) {
      const source = segmented.segments.map((s) => s.text).join(' ');
      quality = checkQuality({ source, translated: merged.text, targetLanguage });
      if (!quality.ok) {
        this.logger.warn('quality issues detected', {
          engine: engine.id,
          sourceLanguage,
          targetLanguage,
          issues: quality.issues.map((i) => i.kind),
        });
      }
    }

    const value: CachedValue = {
      text: merged.text,
      // The requested engine id, which is what the cache key was built from.
      engine: engineKey,
      sourceLanguage,
      ...(resolved.detected ? { detectedLanguage: resolved.detected } : {}),
      ...(quality ? { quality } : {}),
    };

    if (cacheEnabled && cache) {
      try {
        await cache.set(cacheKey, value);
        if (cache.flush && !isMemoryOnlyCache(cache)) {
          await cache.flush();
        }
      } catch (error) {
        this.logger.warn('cache write failed', { error: String(error) });
      }
    }

    return {
      text: merged.text,
      sourceLanguage,
      ...(resolved.detected ? { detectedLanguage: resolved.detected } : {}),
      targetLanguage,
      // Reports the engine that was requested, not whatever the engine
      // implementation calls itself, so provenance matches the cache key.
      engine: engineKey,
      fromCache: false,
      segments: segmented.segments.length,
      elapsedMs: Date.now() - started,
      ...(quality ? { quality } : {}),
    };
  }

  private async translateSegment(input: {
    segment: Segment;
    engine: TranslationEngine;
    sourceLanguage: string;
    targetLanguage: string;
    timeoutMs: number;
    retries?: number;
    budget: DeadlineBudget;
    token: CancellationToken;
    cache?: TranslationCache<unknown>;
    cacheKey: string;
    cacheEnabled: boolean;
    hints?: Record<string, string>;
  }): Promise<{ text: string; attempts: number; fromCache: boolean }> {
    const { segment, engine, sourceLanguage, targetLanguage, budget, token } = input;
    token.throwIfCancelled();

    // Cache per segment when the request is not a single whole-text request.
    const segmentCacheKey = `${input.cacheKey}#s${segment.index}`;
    if (input.cacheEnabled && input.cache) {
      const hit = await input.cache.get(segmentCacheKey);
      if (hit.hit && hit.entry) {
        return { text: hit.entry.value as unknown as string, attempts: 0, fromCache: true };
      }
    }

    const policy =
      input.retries === undefined
        ? this.options.retryPolicy
        : { ...this.options.retryPolicy, maxAttempts: Math.max(1, input.retries + 1) };

    let attempts = 0;

    const response: EngineTranslationResponse = await withRetry(
      async (attempt) => {
        attempts = attempt;
        token.throwIfCancelled();
        // The attempt is bounded by min(request timeout, remaining budget).
        // Without this the service-level deadline would silently become the
        // only limit and a hanging engine would hold the request open.
        const attemptTimeoutMs = budget.clamp(input.timeoutMs);
        return budget.timeout(() =>
          raceCancellation(withTimeout(
            () =>
              engine.translate({
                text: segment.text,
                sourceLanguage,
                targetLanguage,
                ...(input.hints ? { hints: input.hints } : {}),
                timeoutMs: attemptTimeoutMs,
                signal: token.signal,
              }),
            attemptTimeoutMs,
            { message: `engine ${engine.id} timed out after ${attemptTimeoutMs}ms` },
          ), token),
        );
      },
      {
        policy,
        logger: this.logger,
        signal: token.signal,
      },
    ).catch((error: unknown) => {
      throw toTranslationError(error);
    });

    const text = response.text ?? '';
    if (input.cacheEnabled && input.cache && text.trim().length > 0) {
      try {
        await input.cache.set(segmentCacheKey, text);
      } catch (error) {
        this.logger.warn('segment cache write failed', { error: String(error) });
      }
    }

    return { text, attempts, fromCache: false };
  }

  /** Convenience wrapper: caches are flushed after each store so restart recovery works. */
  async flushCache(): Promise<void> {
    await this.options.cache?.flush?.();
  }
}

export function isEnginePairSupported(engine: TranslationEngine, source: string, target: string): boolean {
  const s = normalizeLanguageCode(source);
  const t = normalizeLanguageCode(target);
  return Boolean(s && t && engine.supportsPair(s, t));
}

export { isTranslationError, countCharacters };