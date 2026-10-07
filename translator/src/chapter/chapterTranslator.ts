/**
 * Chapter translation.
 *
 * Flow: input segments -> per-segment segmentation -> bounded-concurrency
 * translation with per-segment retry -> progress events -> merge with fallback.
 *
 * Failure semantics: one failed segment never discards the chapter. Under the
 * default `partial` policy the original text is kept for that segment, the
 * result is marked `degraded: true`, and processing continues. A segment may be
 * retried individually through `retryFailedSegments`.
 */

import { CancelledError, toTranslationError } from '../core/errors';
import { CancellationToken } from '../core/cancellation';
import { DeadlineBudget } from '../core/timeout';
import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';
import { segmentText, mergeSegments } from '../segmentation/segment';
import type { Segment } from '../core/types';
import type {
  ChapterSegmentResult,
  ChapterTranslationRequest,
  ChapterTranslationResult,
  ProgressListener,
  TranslationProgress,
} from '../core/types';
import type { Translator } from '../translator/translator';

export interface ChapterTranslatorOptions {
  translator: Translator;
  defaultConcurrency: number;
  defaultSegmentMaxChars: number;
  maxSegments: number;
  failurePolicy: 'partial' | 'abort';
  defaultDeadlineMs: number;
  logger?: Logger;
}

interface WorkItem {
  chapterIndex: number;
  segmentIndex: number;
  text: string;
}

export class ChapterTranslator {
  private readonly options: ChapterTranslatorOptions;
  private readonly logger: Logger;

  constructor(options: ChapterTranslatorOptions) {
    this.options = options;
    this.logger = options.logger ?? silentLogger;
  }

  async translateChapter(
    request: ChapterTranslationRequest,
    context: { token?: CancellationToken; onProgress?: ProgressListener } = {},
  ): Promise<ChapterTranslationResult> {
    const started = Date.now();
    const token = context.token ?? CancellationToken.none();
    token.throwIfCancelled();

    if (!Array.isArray(request.segments) || request.segments.length === 0) {
      throw toTranslationError(new Error('segments must be a non-empty array'));
    }
    if (request.segments.length > this.options.maxSegments) {
      throw toTranslationError(
        new Error(`chapter has ${request.segments.length} segments, limit is ${this.options.maxSegments}`),
      );
    }

    const targetLanguage = request.targetLanguage;
    const joinWith = request.joinWith ?? '\n';
    const concurrency = Math.max(1, Math.min(request.concurrency ?? this.options.defaultConcurrency, 16));
    const policy = request.failurePolicy ?? this.options.failurePolicy;
    const maxChars = this.options.defaultSegmentMaxChars;

    // Build the work list, remembering how chapter segments map to sub-segments.
    const work: WorkItem[] = [];
    const chapterSegments: Array<{ id?: string; speaker?: string; pieceIndexes: number[] }> = [];

    request.segments.forEach((chapterSegment, chapterIndex) => {
      const speakerPrefix = chapterSegment.speaker ? `${chapterSegment.speaker}: ` : '';
      const segmented = segmentText(chapterSegment.text, { maxChars });
      const pieceIndexes: number[] = [];
      for (const piece of segmented.segments) {
        pieceIndexes.push(work.length);
        work.push({
          chapterIndex,
          segmentIndex: piece.index,
          text: speakerPrefix ? `${speakerPrefix}${piece.text}` : piece.text,
        });
      }
      chapterSegments.push({
        ...(chapterSegment.id === undefined ? {} : { id: chapterSegment.id }),
        ...(chapterSegment.speaker === undefined ? {} : { speaker: chapterSegment.speaker }),
        pieceIndexes,
      });
    });

    const totalSegments = request.segments.length;
    const progress: TranslationProgress = {
      totalSegments,
      completedSegments: 0,
      failedSegments: 0,
      cachedSegments: 0,
      state: 'running',
    };
    const emit = (): void => context.onProgress?.({ ...progress });

    const budget = new DeadlineBudget(request.deadlineMs ?? this.options.defaultDeadlineMs, {
      message: 'chapter deadline exceeded',
      onExpire: () => token.cancel({ reason: 'deadline', message: 'chapter deadline exceeded' }),
    });

    const pieceResults = new Array<string | undefined>(work.length);
    const pieceFallback = new Array<boolean>(work.length).fill(false);
    const pieceFromCache = new Array<boolean>(work.length).fill(false);
    const pieceAttempts = new Array<number>(work.length).fill(0);
    const pieceErrors = new Array<{ code: string; message: string } | undefined>(work.length);
    const chapterStarted = new Map<number, number>();

    const queue = [...work.entries()];
    let processed = 0;

    const processOne = async (entry: [number, WorkItem]): Promise<void> => {
      token.throwIfCancelled();
      const [pieceIndex, item] = entry;
      const chapterStart = chapterStarted.get(item.chapterIndex);
      if (chapterStart === undefined) {
        chapterStarted.set(item.chapterIndex, Date.now());
      }
      try {
        const result = await budget.timeout(() =>
          this.options.translator.translate({
            text: item.text,
            sourceLanguage: request.sourceLanguage ?? 'auto',
            targetLanguage,
            ...(request.engine === undefined ? {} : { engine: request.engine }),
            ...(request.noCache === undefined ? {} : { noCache: request.noCache }),
            token,
          }),
        );
        pieceResults[pieceIndex] = result.text;
        pieceFromCache[pieceIndex] = result.fromCache;
        progress.cachedSegments += result.fromCache ? 1 : 0;
      } catch (error) {
        const normalized = toTranslationError(error);
        if (normalized.code === 'CANCELLED') {
          throw normalized;
        }
        this.logger.warn('chapter segment failed', {
          chapterIndex: item.chapterIndex,
          code: normalized.code,
          error: normalized.message,
        });
        pieceErrors[pieceIndex] = { code: normalized.code, message: normalized.message };
        pieceFallback[pieceIndex] = true;
        progress.failedSegments += 1;
        if (policy === 'abort') {
          throw normalized;
        }
      } finally {
        pieceAttempts[pieceIndex] = Math.max(1, pieceAttempts[pieceIndex] ?? 0);
      }
    };

    const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
      for (;;) {
        if (token.isCancelled) {
          throw new CancelledError('chapter translation cancelled');
        }
        const entry = queue.shift();
        if (!entry) {
          return;
        }
        await processOne(entry);
        processed += 1;
        progress.completedSegments = Math.min(totalSegments, processed);
        emit();
      }
    });

    try {
      await Promise.all(workers);
      progress.state = 'completed';
    } catch (error) {
      const normalized = toTranslationError(error);
      progress.state = normalized.code === 'CANCELLED' ? 'cancelled' : 'failed';
      emit();
      throw normalized;
    }
    emit();

    // Merge pieces back into chapter segments, then join chapter segments.
    const results: ChapterSegmentResult[] = [];
    const outputs: string[] = [];

    for (let chapterIndex = 0; chapterIndex < chapterSegments.length; chapterIndex += 1) {
      const meta = chapterSegments[chapterIndex]!;
      const source = request.segments[chapterIndex]!.text;
      const pieces: Segment[] = [];
      const translations = new Map<number, string>();

      meta.pieceIndexes.forEach((pieceIndex, localIndex) => {
        const value = pieceResults[pieceIndex];
        pieces.push({ index: localIndex, text: work[pieceIndex]!.text, trailing: '' });
        if (typeof value === 'string' && value.trim().length > 0) {
          translations.set(localIndex, value);
        }
      });

      const merged = mergeSegments(pieces, translations);
      let translated = merged.text;
      // A whole-segment fallback means nothing was translated.
      const anyFallback = meta.pieceIndexes.some((i) => pieceFallback[i]);
      const fallback = anyFallback && merged.originalSegments.length === pieces.length;

      if (fallback) {
        translated = source;
      } else if (meta.speaker) {
        translated = translated.startsWith(`${meta.speaker}:`)
          ? translated
          : `${meta.speaker}: ${translated.replace(/^[^:]{0,24}:\s*/, '')}`;
      }

      const firstError = meta.pieceIndexes
        .map((i) => pieceErrors[i])
        .find((e): e is { code: string; message: string } => e !== undefined);

      results.push({
        index: chapterIndex,
        ...(meta.id === undefined ? {} : { id: meta.id }),
        source,
        translated,
        fallback,
        fromCache: meta.pieceIndexes.some((i) => pieceFromCache[i]),
        attempts: Math.max(0, ...meta.pieceIndexes.map((i) => pieceAttempts[i] ?? 0)),
        ...(firstError ? { error: firstError } : {}),
        elapsedMs: Math.max(0, Date.now() - (chapterStarted.get(chapterIndex) ?? started)),
      });
      outputs.push(translated);
    }

    const degraded = results.some((r) => r.fallback || r.error !== undefined);

    return {
      text: outputs.join(joinWith),
      targetLanguage,
      // Taken from the translator rather than guessed: consumers need the real
      // engine id for provenance and for per-engine cache accounting.
      engine: this.options.translator.engine,
      segments: results,
      progress,
      elapsedMs: Date.now() - started,
      degraded,
    };
  }

  /**
   * Retries only the segments that previously failed, merging results back into
   * an existing chapter result. Segments that already succeeded are untouched.
   */
  async retryFailedSegments(
    previous: ChapterTranslationResult,
    request: ChapterTranslationRequest,
    context: { token?: CancellationToken; onProgress?: ProgressListener } = {},
  ): Promise<ChapterTranslationResult> {
    const failingIndexes = previous.segments
      .map((segment, index) => (segment.error ? index : -1))
      .filter((index) => index >= 0);

    if (failingIndexes.length === 0) {
      return previous;
    }

    const retryRequest: ChapterTranslationRequest = {
      ...request,
      segments: failingIndexes.map((index) => ({
        text: request.segments[index]!.text,
        ...(request.segments[index]!.id === undefined ? {} : { id: request.segments[index]!.id }),
        ...(request.segments[index]!.speaker === undefined ? {} : { speaker: request.segments[index]!.speaker }),
      })),
    };

    const retried = await this.translateChapter(retryRequest, context);

    const segments = previous.segments.map((segment) => ({ ...segment }));
    failingIndexes.forEach((originalIndex, retryIndex) => {
      const replacement = retried.segments[retryIndex];
      if (!replacement) {
        return;
      }
      const existing = segments[originalIndex];
      if (!existing) {
        return;
      }
      existing.translated = replacement.translated;
      existing.fallback = replacement.fallback;
      existing.error = replacement.error;
      existing.fromCache = replacement.fromCache;
      existing.attempts = existing.attempts + replacement.attempts;
      existing.elapsedMs = replacement.elapsedMs;
    });

    const joinWith = request.joinWith ?? '\n';
    return {
      text: segments.map((s) => s.translated).join(joinWith),
      targetLanguage: previous.targetLanguage,
      engine: previous.engine,
      segments,
      progress: {
        totalSegments: segments.length,
        completedSegments: segments.filter((s) => !s.error).length,
        failedSegments: segments.filter((s) => Boolean(s.error)).length,
        cachedSegments: segments.filter((s) => s.fromCache).length,
        state: segments.some((s) => s.error) ? 'failed' : 'completed',
      },
      elapsedMs: previous.elapsedMs + retried.elapsedMs,
      degraded: segments.some((s) => s.fallback || s.error),
    };
  }
}