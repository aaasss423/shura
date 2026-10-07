/**
 * HTTP helper for engines.
 *
 * Applies request timeout and abort signal in one place so every engine gets
 * correct cancellation behaviour without reimplementing it, and normalizes
 * transport failures into the platform error taxonomy.
 */

import {
  CancelledError,
  EngineUnavailableError,
  TimeoutError,
  TranslationError,
} from '../core/errors';

export interface HttpRequestOptions {
  timeoutMs: number;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  userAgent?: string;
  maxResponseBytes?: number;
}

export interface HttpResponse<T = unknown> {
  status: number;
  ok: boolean;
  body: T;
  headers: Record<string, string>;
}

const TRANSIENT_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);

export class HttpError extends TranslationError {
  override readonly status: number;
  readonly retryAfterMs?: number;
  /** Raw response body, so callers can classify status-shared error codes. */
  readonly body?: string;

  constructor(message: string, status: number, retryAfterMs?: number, body?: string) {
    super(status === 429 ? 'RATE_LIMITED' : 'ENGINE_ERROR', message, {
      retryable: TRANSIENT_STATUS.has(status),
      details: { status, ...(retryAfterMs === undefined ? {} : { retryAfterMs }) },
    });
    this.name = 'HttpError';
    this.status = status;
    this.retryAfterMs = retryAfterMs;
    this.body = body;
  }
}

export async function httpGetJson<T = unknown>(url: string, options: HttpRequestOptions): Promise<HttpResponse<T>> {
  return requestJson<T>('GET', url, undefined, options);
}

export async function httpPostJson<T = unknown>(
  url: string,
  body: unknown,
  options: HttpRequestOptions,
): Promise<HttpResponse<T>> {
  return requestJson<T>('POST', url, body, options);
}

async function requestJson<T>(
  method: 'GET' | 'POST',
  url: string,
  body: unknown,
  options: HttpRequestOptions,
): Promise<HttpResponse<T>> {
  const controller = new AbortController();
  const externalSignal = options.signal;
  let timedOut = false;

  const onExternalAbort = (): void => controller.abort(externalSignal?.reason);
  if (externalSignal) {
    if (externalSignal.aborted) {
      // The token owns the reason; the abort reason itself is an opaque sentinel.
      throw new CancelledError('request cancelled before it started');
    }
    externalSignal.addEventListener('abort', onExternalAbort, { once: true });
  }

  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs);
  if (typeof timer.unref === 'function') {
    timer.unref();
  }

  try {
    const response = await fetch(url, {
      method,
      signal: controller.signal,
      headers: {
        accept: 'application/json',
        'user-agent': options.userAgent ?? 'translation-platform/0.1 (+standalone)',
        ...(body === undefined ? {} : { 'content-type': 'application/json' }),
        ...(options.headers ?? {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });

    const headers: Record<string, string> = {};
    response.headers.forEach((value, key) => {
      headers[key.toLowerCase()] = value;
    });

    const raw = await readBounded(response, options.maxResponseBytes);
    let parsed: T;
    try {
      parsed = raw.length === 0 ? (undefined as T) : (JSON.parse(raw) as T);
    } catch {
      throw new TranslationError('ENGINE_ERROR', `engine returned non-JSON response (status ${response.status})`, {
        retryable: true,
        details: { status: response.status, preview: raw.slice(0, 200) },
      });
    }

    if (!response.ok) {
      const retryAfter = headers['retry-after'];
      const retryAfterMs = retryAfter ? (Number.parseInt(retryAfter, 10) || 0) * 1000 : undefined;
      // The body is attached because several engines encode distinct, very
      // different failures under the same status (429 rate limit vs 429 quota
      // exhausted). The status alone cannot tell them apart.
      throw new HttpError(
        `engine responded with HTTP ${response.status}`,
        response.status,
        retryAfterMs,
        raw.length > 2000 ? raw.slice(0, 2000) : raw,
      );
    }

    return { status: response.status, ok: true, body: parsed, headers };
  } catch (error) {
    if (timedOut) {
      throw new TimeoutError(`engine request timed out after ${options.timeoutMs}ms`, {
        details: { timeoutMs: options.timeoutMs },
      });
    }
    if (externalSignal?.aborted) {
      throw new CancelledError('request cancelled by caller');
    }
    throw normalizeTransportError(error);
  } finally {
    clearTimeout(timer);
    externalSignal?.removeEventListener('abort', onExternalAbort);
  }
}

async function readBounded(response: Response, maxBytes?: number): Promise<string> {
  if (!maxBytes) {
    return response.text();
  }
  const text = await response.text();
  if (text.length > maxBytes) {
    return text.slice(0, maxBytes);
  }
  return text;
}

function normalizeTransportError(error: unknown): TranslationError {
  if (error instanceof TranslationError) {
    return error;
  }
  if (error instanceof Error) {
    const errno = (error as NodeJS.ErrnoException).code;
    if (error.name === 'AbortError') {
      return new TimeoutError('request aborted');
    }
    if (
      errno === 'ENOTFOUND' ||
      errno === 'EAI_AGAIN' ||
      errno === 'ECONNREFUSED' ||
      errno === 'ECONNRESET' ||
      errno === 'UND_ERR_SOCKET' ||
      errno === 'UND_ERR_CONNECT_TIMEOUT' ||
      errno === 'ETIMEDOUT'
    ) {
      return new EngineUnavailableError(`engine unreachable: ${errno}`, { cause: error });
    }
  }
  return new EngineUnavailableError(error instanceof Error ? error.message : 'engine request failed', {
    cause: error,
  });
}

export function isTransientStatus(status: number): boolean {
  return TRANSIENT_STATUS.has(status);
}