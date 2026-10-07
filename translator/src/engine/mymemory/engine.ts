/**
 * MyMemory-backed translation engine (https://mymemory.translated.net).
 *
 * Real network implementation. Notes that matter in production:
 *  - Free tier allows ~5000 chars/day per IP without a contact address, and
 *    rejects queries longer than 500 characters, so `limits.maxCharsPerRequest`
 *    is set below that and segmentation respects it.
 *  - Anonymous requests are heavily rate limited; MYMEMORY_CONTACT_EMAIL
 *    raises the ceiling. The address is read from configuration, never source.
 *  - Quality for CJK -> Arabic is noticeably weaker than for English -> Arabic.
 *    That is a property of this engine, not of the platform, and is recorded in
 *    the quality report rather than hidden.
 */

import {
  EngineError,
  QuotaExceededError,
  RateLimitError,
  UnsupportedLanguageError,
} from '../../core/errors';
import type { CancellationToken } from '../../core/cancellation';
import {
  getLanguageInfo,
  isKnownLanguage,
  listSourceLanguages,
  listTargetLanguages,
  normalizeLanguageCode,
} from '../../language/registry';
import type {
  EngineLanguagePairSupport,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../core/types';
import { HttpError, httpGetJson } from '../http';
import type { TranslationEngine } from '../engine';
import type { EngineHealth } from '../engine';

export interface MyMemoryEngineOptions {
  endpoint?: string;
  contactEmail?: string;
  maxQueryChars?: number;
  timeoutMs?: number;
  /**
   * Minimum delay between outbound requests, in milliseconds.
   * The free tier is aggressively rate limited, so serialising requests and
   * leaving headroom prevents a chapter burst from tripping the limiter.
   */
  minIntervalMs?: number;
}

interface MyMemoryResponse {
  responseData?: {
    translatedText?: string;
    match?: number;
  };
  responseStatus?: number | string;
  responseDetails?: string;
  quotaFinished?: boolean;
}

const MYMEMORY_MAX_QUERY = 500;

/** Throttle delay. Not unref'd: a pending request is real pending work. */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

export class MyMemoryEngine implements TranslationEngine {
  readonly id = 'mymemory';
  readonly name = 'MyMemory';
  readonly limits = {
    maxCharsPerRequest: 0,
  } as { maxCharsPerRequest: number };

  private readonly endpoint: string;
  private readonly contactEmail?: string;
  private readonly maxQueryChars: number;
  private readonly timeoutMs: number;
  private readonly minIntervalMs: number;
  private lastRequestAt = 0;
  private lastErrorBody?: string;
  /** Serialises outbound requests so callers cannot burst the free tier. */
  private throttle: Promise<unknown> = Promise.resolve();

  constructor(options: MyMemoryEngineOptions = {}) {
    this.endpoint = options.endpoint ?? 'https://api.mymemory.translated.net/get';
    this.contactEmail = options.contactEmail || undefined;
    this.maxQueryChars = Math.min(options.maxQueryChars ?? 480, MYMEMORY_MAX_QUERY);
    this.timeoutMs = options.timeoutMs ?? 15000;
    this.minIntervalMs = Math.max(0, options.minIntervalMs ?? 250);
    this.limits.maxCharsPerRequest = this.maxQueryChars;
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    const text = request.text.trim();
    if (text.length === 0) {
      throw new EngineError('empty text rejected by engine', { engine: this.id, retryable: false });
    }
    if (text.length > this.maxQueryChars) {
      throw new EngineError(
        `text of ${text.length} characters exceeds engine limit of ${this.maxQueryChars}`,
        { engine: this.id, retryable: false, details: { limit: this.maxQueryChars } },
      );
    }

    const source = normalizeLanguageCode(request.sourceLanguage);
    const target = normalizeLanguageCode(request.targetLanguage);
    if (!source || source === 'auto') {
      throw new UnsupportedLanguageError(`source language not supported: ${request.sourceLanguage}`);
    }
    if (!target || target === 'auto') {
      throw new UnsupportedLanguageError(`target language not supported: ${request.targetLanguage}`);
    }
    if (!this.supportsPair(source, target)) {
      throw new UnsupportedLanguageError(`MyMemory does not support ${source} -> ${target}`);
    }

    const url = this.buildUrl(text, source, target);

    let response: Awaited<ReturnType<typeof httpGetJson<MyMemoryResponse>>>;
    try {
      response = await this.throttled(() =>
        httpGetJson<MyMemoryResponse>(url, {
          timeoutMs: request.timeoutMs ?? this.timeoutMs,
          ...(request.signal ? { signal: request.signal } : {}),
          userAgent: 'translation-platform/0.1 (+mymemory-engine)',
        }),
      );
    } catch (error) {
      // httpGetJson rejects on non-2xx before the payload is inspected. MyMemory
      // uses 429 for both transient throttling and permanent daily-quota
      // exhaustion, so the body is the only way to tell them apart.
      if (error instanceof HttpError && error.body) {
        throw this.mapApiError(error.status, error.body);
      }
      throw error;
    }

    const payload = response.body ?? {};
    const status = Number(payload.responseStatus ?? response.status);
    const detail =
      typeof payload.responseDetails === 'string' && payload.responseDetails.length > 0
        ? payload.responseDetails
        : undefined;

    if (payload.quotaFinished === true) {
      throw new QuotaExceededError('MyMemory daily quota exhausted');
    }

    if (status >= 400 || status === 403) {
      throw this.mapApiError(status, detail ?? this.lastErrorBody ?? '');
    }

    const translated = payload.responseData?.translatedText;
    if (typeof translated !== 'string' || translated.length === 0) {
      throw new EngineError('engine returned an empty translation', {
        engine: this.id,
        retryable: true,
        details: detail === undefined ? undefined : { details: detail },
      });
    }

    if (/QUERY LENGTH LIMIT EXCEEDED|NO QUERY SPECIFIED|INVALID LANGUAGE PAIR/i.test(translated)) {
      throw this.mapApiError(403, translated);
    }

    // The quota warning has also been observed on HTTP 200 responses, where it
    // arrives inside translatedText rather than responseDetails.
    if (/USED ALL AVAILABLE FREE TRANSLATIONS|NEXT AVAILABLE IN/i.test(translated)) {
      throw new QuotaExceededError(translated);
    }

    const confidence =
      typeof payload.responseData?.match === 'number'
        ? Math.max(0, Math.min(1, payload.responseData.match))
        : undefined;

    return {
      text: translated,
      engine: this.id,
      ...(source !== request.sourceLanguage ? {} : { detectedSourceLanguage: source }),
      ...(confidence === undefined ? {} : { confidence }),
      raw: payload,
    };
  }

  /** Serialises requests and enforces the minimum inter-request interval. */
  private async throttled<T>(run: () => Promise<T>): Promise<T> {
    const previous = this.throttle.catch(() => undefined);
    let release!: () => void;
    this.throttle = new Promise<void>((resolve) => {
      release = resolve;
    });

    await previous;
    try {
      const waitMs = this.minIntervalMs - (Date.now() - this.lastRequestAt);
      if (waitMs > 0) {
        await sleep(waitMs);
      }
      this.lastRequestAt = Date.now();
      try {
        return await run();
      } catch (error) {
        // Retain the raw body: a 429 carrying a quota message must be
        // reclassified as non-retryable instead of retried for hours.
        if (error instanceof HttpError && error.body) {
          this.lastErrorBody = error.body;
        }
        throw error;
      }
    } finally {
      release();
    }
  }

  private mapApiError(status: number, details?: string): EngineError {
    // MyMemory answers 429 both for "slow down" and for "daily quota spent",
    // distinguished only by the body. Quota exhaustion must NOT be retried:
    // the free tier resets hours later, so retrying only wastes the request
    // budget and delays the fallback that actually helps the user.
    if (/USED ALL AVAILABLE FREE TRANSLATIONS|NEXT AVAILABLE IN|QUOTA|TOO MANY REQUESTS/i.test(details ?? '')) {
      const isDailyExhaustion = /USED ALL AVAILABLE FREE TRANSLATIONS|NEXT AVAILABLE IN/i.test(details ?? '');
      return isDailyExhaustion
        ? new QuotaExceededError(details ?? 'MyMemory daily quota exhausted')
        : new RateLimitError(details ?? 'MyMemory rate limit reached');
    }
    if (status === 403) {
      const message = details ?? 'engine rejected request (403)';
      if (/QUOTA|LIMIT EXCEEDED/i.test(message)) {
        return new QuotaExceededError(message);
      }
      return new EngineError(message, { engine: this.id, retryable: false, details: { status } });
    }
    if (status === 429) {
      return new RateLimitError(details ?? 'engine rate limited');
    }
    return new EngineError(details ?? `engine error (status ${status})`, {
      engine: this.id,
      retryable: true,
      details: { status },
    });
  }

  private buildUrl(text: string, source: string, target: string): string {
    const url = new URL(this.endpoint);
    url.searchParams.set('q', text);
    url.searchParams.set('langpair', `${source}|${target}`);
    if (this.contactEmail) {
      url.searchParams.set('de', this.contactEmail);
    }
    return url.toString();
  }

  getSourceLanguages(): LanguageInfo[] {
    return listSourceLanguages();
  }

  getTargetLanguages(): LanguageInfo[] {
    return listTargetLanguages();
  }

  supportsPair(source: LanguageCode, target: LanguageCode): boolean {
    const s = normalizeLanguageCode(source);
    const t = normalizeLanguageCode(target);
    if (!s || !t || s === 'auto' || t === 'auto') {
      return false;
    }
    // MyMemory covers the platform registry; unknown codes are rejected.
    return isKnownLanguage(s) && isKnownLanguage(t) && s !== t;
  }

  supportedPairs(): EngineLanguagePairSupport[] {
    const pairs: EngineLanguagePairSupport[] = [];
    for (const source of this.getSourceLanguages()) {
      for (const target of this.getTargetLanguages()) {
        if (this.supportsPair(source.code, target.code)) {
          pairs.push({ source: source.code, target: target.code });
        }
      }
    }
    return pairs;
  }

  async healthCheck(token?: CancellationToken): Promise<EngineHealth> {
    const started = Date.now();
    try {
      await this.translate({
        text: 'ok',
        sourceLanguage: 'en',
        targetLanguage: 'ar',
        timeoutMs: this.timeoutMs,
        ...(token ? { signal: token.signal } : {}),
      });
      return { engine: this.id, healthy: true, latencyMs: Date.now() - started };
    } catch (error) {
      const message = error instanceof HttpError ? `HTTP ${error.status}` : String((error as Error).message ?? error);
      return { engine: this.id, healthy: false, detail: message, latencyMs: Date.now() - started };
    }
  }

  /** Exposed for the CLI diagnostics; never contains credentials in output. */
  describeLimits(): { maxQueryChars: number; authenticated: boolean } {
    return { maxQueryChars: this.maxQueryChars, authenticated: Boolean(this.contactEmail) };
  }

  /** Human-readable language name helper used by REST payloads. */
  static languageName(code: string): string {
    return getLanguageInfo(code)?.name ?? code;
  }
}