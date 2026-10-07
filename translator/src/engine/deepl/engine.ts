/**
 * DeepL translation engine.
 *
 * Registered as a plug-in behind the same TranslationEngine interface as
 * MyMemory. Nothing above `src/engine/engine.ts` references this file.
 *
 * Why it exists: the free MyMemory tier produces mediocre CJK -> Arabic output.
 * DeepL is materially better at those pairs, which is the project's stated
 * top priority.
 *
 * Credential handling:
 *  - The API key is never hard-coded. It comes from `DEEPL_API_KEY` in the
 *    environment, or from the runtime secret store (see src/security/).
 *  - Without a key the engine is "not configured": translate() raises a clear
 *    non-retryable ConfigError, healthCheck() reports unhealthy with a reason.
 *    It never fabricates a translation.
 *  - The key is never included in error messages, logs, or responses.
 *
 * API facts this implementation relies on:
 *  - `POST /v2/translate`, auth via `Authorization: DeepL-Auth-Key <key>`.
 *  - Request body may be JSON: `{ text: string[], target_lang, source_lang? }`.
 *  - Response: `{ translations: [{ detected_source_language?, text }] }`.
 *  - 456 means the account character quota is exhausted and must NOT be retried.
 *  - 429/529 are rate limits and should be retried with backoff.
 *  - 413 means the payload is too large.
 *  - Total request size limit is 128 KiB.
 */

import { createHash } from 'node:crypto';

import {
  ConfigError,
  EngineError,
  QuotaExceededError,
  RateLimitError,
  TranslationError,
  UnsupportedLanguageError,
} from '../../core/errors';
import type { CancellationToken } from '../../core/cancellation';
import { getLanguageInfo, isKnownLanguage, listAllLanguages } from '../../language/registry';
import type {
  EngineLanguagePairSupport,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../core/types';
import { HttpError, httpPostJson } from '../http';
import type { EngineHealth, TranslationEngine } from '../engine';

/**
 * Platform language code -> DeepL language code.
 *
 * DeepL uses upper-case ISO codes with a few variants. Only the languages the
 * platform registry knows about are mapped; anything else is rejected up front
 * rather than producing a confusing API error.
 */
const DEEPL_LANGUAGE_CODES: Record<string, string> = {
  ar: 'AR',
  bg: 'BG',
  cs: 'CS',
  da: 'DA',
  de: 'DE',
  el: 'EL',
  en: 'EN',
  es: 'ES',
  et: 'ET',
  fi: 'FI',
  fr: 'FR',
  he: 'HE',
  hu: 'HU',
  id: 'ID',
  it: 'IT',
  ja: 'JA',
  ko: 'KO',
  lt: 'LT',
  lv: 'LV',
  nb: 'NB',
  nl: 'NL',
  pl: 'PL',
  pt: 'PT',
  ro: 'RO',
  ru: 'RU',
  sk: 'SK',
  sl: 'SL',
  sv: 'SV',
  tr: 'TR',
  uk: 'UK',
  zh: 'ZH',
};

export interface DeepLEngineOptions {
  /**
   * DeepL API key. Omit when not configured; the engine then reports itself as
   * unavailable instead of pretending to work.
   */
  apiKey?: string;
  /** Free plan endpoint. Pro plans use https://api.deepl.com. */
  endpoint?: string;
  timeoutMs?: number;
  /**
   * Maximum characters per request. The hard API limit is 128 KiB per request;
   * this stays well below it so a single segment can never trip a 413.
   */
  maxCharsPerRequest?: number;
  /** Serialization gap in ms; DeepL rate limits bursts. */
  minIntervalMs?: number;
  /** Keystroke formality preference. Undefined leaves the API default. */
  formality?: 'default' | 'more' | 'less' | 'prefer_more' | 'prefer_less';
  /**
   * Ask DeepL to preserve original formatting. Enabled by default because
   * dialogue punctuation matters for manga text.
   */
  preserveFormatting?: boolean;
}

interface DeepLTranslateResponse {
  translations?: Array<{
    detected_source_language?: string;
    text?: string;
    billed_characters?: number;
  }>;
  message?: string;
  code?: string;
}

const DEEPL_MAX_REQUEST_BYTES = 128 * 1024;

/** Validates a key's shape without ever echoing its value. */
function looksLikeDeepLKey(key: string): boolean {
  // DeepL keys are opaque; we only reject obviously wrong values so a typo
  // produces a clear configuration error instead of a confusing 403.
  return /^[A-Za-z0-9:_-]{10,}$/.test(key);
}

/** Non-reversible fingerprint used in diagnostics to confirm which key is loaded. */
export function fingerprintKey(key: string): string {
  return createHash('sha256').update(key).digest('hex').slice(0, 12);
}

export class DeepLEngine implements TranslationEngine {
  readonly id = 'deepl';
  readonly name = 'DeepL';
  readonly limits = { maxCharsPerRequest: 4000 };

  private readonly apiKey?: string;
  private readonly endpoint: string;
  private readonly timeoutMs: number;
  private readonly minIntervalMs: number;
  private readonly formality?: DeepLEngineOptions['formality'];
  private readonly preserveFormatting: boolean;

  private lastRequestAt = 0;
  private throttle: Promise<unknown> = Promise.resolve();
  /** Last observed X-Trace-ID, surfaced for support requests. Never a secret. */
  private lastTraceId?: string;

  constructor(options: DeepLEngineOptions = {}) {
    const key = options.apiKey?.trim();
    if (key && !looksLikeDeepLKey(key)) {
      throw new ConfigError(
        'DEEPL_API_KEY does not look like a DeepL API key (expected 10+ URL-safe characters)',
      );
    }
    this.apiKey = key || undefined;
    this.endpoint = options.endpoint ?? 'https://api-free.deepl.com/v2/translate';
    this.timeoutMs = options.timeoutMs ?? 15000;
    this.minIntervalMs = Math.max(0, options.minIntervalMs ?? 100);
    this.limits.maxCharsPerRequest = Math.max(
      100,
      Math.min(options.maxCharsPerRequest ?? 4000, DEEPL_MAX_REQUEST_BYTES - 2048),
    );
    if (options.formality !== undefined) {
      this.formality = options.formality;
    }
    this.preserveFormatting = options.preserveFormatting ?? true;
  }

  /** True when an API key is loaded. Safe to expose through the REST layer. */
  isConfigured(): boolean {
    return this.apiKey !== undefined;
  }

  /** Credential probe used by routing to skip an unconfigured engine. */
  configuration(): { configured: boolean; reason?: string } {
    return this.isConfigured()
      ? { configured: true }
      : { configured: false, reason: 'DeepL is not configured (DEEPL_API_KEY is not set)' };
  }

  /** Key fingerprint for diagnostics; never the key itself. */
  keyFingerprint(): string | undefined {
    return this.apiKey === undefined ? undefined : fingerprintKey(this.apiKey);
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    if (!this.apiKey) {
      throw new ConfigError(
        'DeepL is not configured: set DEEPL_API_KEY in the environment to enable it. ' +
          'No translation was attempted.',
      );
    }

    const text = request.text;
    if (text.trim().length === 0) {
      throw new EngineError('empty text rejected by engine', { engine: this.id, retryable: false });
    }
    if (text.length > this.limits.maxCharsPerRequest) {
      throw new EngineError(
        `text of ${text.length} characters exceeds the DeepL engine limit of ${this.limits.maxCharsPerRequest}`,
        { engine: this.id, retryable: false, details: { limit: this.limits.maxCharsPerRequest } },
      );
    }

    const target = this.toDeepLCode(request.targetLanguage);
    if (!target) {
      throw new UnsupportedLanguageError(`DeepL does not support target language ${request.targetLanguage}`);
    }
    // 'auto' is forwarded by omitting source_lang so DeepL performs its own
    // detection. The platform still detects first for cache-key stability.
    const source =
      request.sourceLanguage === 'auto' ? undefined : this.toDeepLCode(request.sourceLanguage);
    if (request.sourceLanguage !== 'auto' && !source) {
      throw new UnsupportedLanguageError(`DeepL does not support source language ${request.sourceLanguage}`);
    }
    if (source && source === target) {
      throw new UnsupportedLanguageError(`DeepL does not translate ${source} to itself`);
    }

    const body: Record<string, unknown> = { text: [text], target_lang: target };
    if (source) {
      body.source_lang = source;
    }
    if (this.preserveFormatting) {
      body.preserve_formatting = true;
    }
    if (this.formality) {
      body.formality = this.formality;
    }

    let response;
    try {
      response = await this.throttled(() =>
        httpPostJson<DeepLTranslateResponse>(this.endpoint, body, {
          timeoutMs: request.timeoutMs ?? this.timeoutMs,
          // The key travels in a header only; it never enters a URL, a log, or
          // an error message.
          headers: { authorization: `DeepL-Auth-Key ${this.apiKey}` },
          userAgent: 'translation-platform/0.1 (+deepl-engine)',
          maxResponseBytes: DEEPL_MAX_REQUEST_BYTES,
          ...(request.signal ? { signal: request.signal } : {}),
        }),
      );
    } catch (error) {
      throw this.mapError(error);
    }

    const traceId = response.headers['x-trace-id'];
    if (traceId) {
      this.lastTraceId = traceId;
    }

    const translations = response.body?.translations;
    if (!Array.isArray(translations) || translations.length === 0) {
      throw new EngineError('DeepL returned no translations', {
        engine: this.id,
        retryable: true,
        ...(this.lastTraceId ? { details: { traceId: this.lastTraceId } } : {}),
      });
    }

    const translated = translations[0]?.text;
    if (typeof translated !== 'string' || translated.length === 0) {
      throw new EngineError('DeepL returned an empty translation', {
        engine: this.id,
        retryable: true,
        ...(this.lastTraceId ? { details: { traceId: this.lastTraceId } } : {}),
      });
    }

    const detected = translations[0]?.detected_source_language;
    return {
      text: translated,
      engine: this.id,
      ...(detected ? { detectedSourceLanguage: detected.toLowerCase() } : {}),
      raw: { billedCharacters: translations[0]?.billed_characters, traceId: this.lastTraceId },
    };
  }

  /**
   * Serializes requests and enforces the minimum interval.
   * DeepL rate limits bursts; a chapter with concurrency 3 would otherwise
   * produce a burst per segment.
   */
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
        await new Promise<void>((resolve) => {
          setTimeout(resolve, waitMs);
        });
      }
      this.lastRequestAt = Date.now();
      return await run();
    } finally {
      release();
    }
  }

  /**
   * Maps transport/API failures onto the platform taxonomy.
   *
   * DeepL uses 456 for an exhausted character quota and 429/529 for rate
   * limits. They must not be conflated: 456 must not be retried, 429 should be.
   */
  private mapError(error: unknown): TranslationError {
    if (error instanceof TranslationError && !(error instanceof HttpError)) {
      return error;
    }
    if (error instanceof HttpError) {
      const message = extractDeepLMessage(error.body) ?? error.message;
      const traceId = error.details?.traceId;

      if (error.status === 456) {
        return new QuotaExceededError(`DeepL character quota exhausted (${message})`, {
          engine: this.id,
          retryable: false,
        });
      }
      if (error.status === 429 || error.status === 529) {
        return new RateLimitError(`DeepL rate limited (${message})`, {
          engine: this.id,
          retryable: true,
          ...(error.retryAfterMs === undefined ? {} : { details: { retryAfterMs: error.retryAfterMs } }),
        });
      }
      if (error.status === 403) {
        return new ConfigError(
          'DeepL rejected the API key (403). Check DEEPL_API_KEY; it will not be logged or returned.',
        );
      }
      if (error.status === 413) {
        return new TranslationError('TEXT_TOO_LONG', `DeepL request too large (${message})`, {
          engine: this.id,
          retryable: false,
        });
      }
      if (error.status === 400) {
        // Invalid request: retrying an identical request cannot help.
        return new EngineError(`DeepL rejected the request (400): ${message}`, {
          engine: this.id,
          retryable: false,
          ...(traceId === undefined ? {} : { details: { traceId } }),
        });
      }
      if (error.status >= 500) {
        return new EngineError(`DeepL server error ${error.status}`, {
          engine: this.id,
          retryable: true,
          ...(traceId === undefined ? {} : { details: { traceId } }),
        });
      }
      return new EngineError(`DeepL request failed with status ${error.status}`, {
        engine: this.id,
        retryable: false,
      });
    }
    return error instanceof Error
      ? new EngineError(error.message, { engine: this.id })
      : new EngineError('DeepL request failed', { engine: this.id });
  }

  private toDeepLCode(code: LanguageCode): string | undefined {
    const normalized = String(code ?? '').toLowerCase();
    if (normalized === 'auto') {
      return undefined;
    }
    return DEEPL_LANGUAGE_CODES[normalized];
  }

  getSourceLanguages(): LanguageInfo[] {
    return listAllLanguages().filter((l) => this.toDeepLCode(l.code) !== undefined);
  }

  getTargetLanguages(): LanguageInfo[] {
    return listAllLanguages().filter(
      (l) => this.toDeepLCode(l.code) !== undefined && !isVariantOnly(l.code),
    );
  }

  supportsPair(source: LanguageCode, target: LanguageCode): boolean {
    const s = this.toDeepLCode(source);
    const t = this.toDeepLCode(target);
    if (!s || !t || source === 'auto' || target === 'auto') {
      return false;
    }
    return isKnownLanguage(String(source)) && isKnownLanguage(String(target)) && s !== t;
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
    if (!this.apiKey) {
      return {
        engine: this.id,
        healthy: false,
        detail: 'not configured: DEEPL_API_KEY is not set',
      };
    }
    const started = Date.now();
    try {
      const result = await this.translate({
        text: 'ping',
        sourceLanguage: 'en',
        targetLanguage: 'ar',
        timeoutMs: this.timeoutMs,
        ...(token ? { signal: token.signal } : {}),
      });
      return {
        engine: this.id,
        healthy: true,
        detail: `key ${this.keyFingerprint()}, sample ${result.text.length} chars`,
        latencyMs: Date.now() - started,
      };
    } catch (error) {
      const message =
        error instanceof TranslationError ? `${error.code}: ${error.message}` : String((error as Error).message);
      return { engine: this.id, healthy: false, detail: message, latencyMs: Date.now() - started };
    }
  }

  /** Diagnostics for the CLI. Contains no credential material. */
  describe(): { endpoint: string; configured: boolean; keyFingerprint?: string; maxCharsPerRequest: number } {
    return {
      endpoint: this.endpoint,
      configured: this.isConfigured(),
      ...(this.keyFingerprint() ? { keyFingerprint: this.keyFingerprint() } : {}),
      maxCharsPerRequest: this.limits.maxCharsPerRequest,
    };
  }

  /** Convenience for the CLI and tests. */
  static languageName(code: string): string {
    return getLanguageInfo(code)?.name ?? code;
  }
}

/** Variants that DeepL only accepts as targets are not needed by this platform. */
function isVariantOnly(code: string): boolean {
  return code.includes('-');
}

function extractDeepLMessage(body?: string): string | undefined {
  if (!body) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(body) as { message?: string; error?: { message?: string } };
    return parsed.message ?? parsed.error?.message;
  } catch {
    return undefined;
  }
}