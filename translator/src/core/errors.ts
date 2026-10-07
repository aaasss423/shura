/**
 * Runtime error taxonomy.
 *
 * Every error that can reach the application boundary extends TranslationError
 * and carries a stable `code` plus an HTTP status, so the REST layer and any
 * future host application (for example Shura) can map failures without
 * depending on engine internals or on error message text.
 */

export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNSUPPORTED_LANGUAGE'
  | 'UNSUPPORTED_PAIR'
  | 'ENGINE_ERROR'
  | 'ENGINE_UNAVAILABLE'
  | 'RATE_LIMITED'
  | 'QUOTA_EXCEEDED'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'DEADLINE_EXCEEDED'
  | 'TEXT_TOO_LONG'
  | 'EMPTY_INPUT'
  | 'CACHE_ERROR'
  | 'CONFIG_ERROR'
  | 'INTERNAL_ERROR';

const STATUS_BY_CODE: Record<ErrorCode, number> = {
  VALIDATION_ERROR: 400,
  UNSUPPORTED_LANGUAGE: 400,
  UNSUPPORTED_PAIR: 400,
  EMPTY_INPUT: 400,
  TEXT_TOO_LONG: 413,
  RATE_LIMITED: 429,
  QUOTA_EXCEEDED: 429,
  TIMEOUT: 504,
  DEADLINE_EXCEEDED: 504,
  CANCELLED: 499,
  ENGINE_UNAVAILABLE: 503,
  ENGINE_ERROR: 502,
  CACHE_ERROR: 500,
  CONFIG_ERROR: 500,
  INTERNAL_ERROR: 500,
};

export interface TranslationErrorOptions {
  cause?: unknown;
  engine?: string;
  retryable?: boolean;
  details?: Record<string, unknown>;
}

export class TranslationError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly engine?: string;
  readonly retryable: boolean;
  readonly details?: Record<string, unknown>;

  constructor(code: ErrorCode, message: string, options: TranslationErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'TranslationError';
    this.code = code;
    this.status = STATUS_BY_CODE[code];
    this.engine = options.engine;
    this.retryable = options.retryable ?? DEFAULT_RETRYABLE.has(code);
    this.details = options.details;
  }

  toJSON(): Record<string, unknown> {
    return {
      code: this.code,
      message: this.message,
      status: this.status,
      retryable: this.retryable,
      engine: this.engine,
      details: this.details,
    };
  }
}

const DEFAULT_RETRYABLE = new Set<ErrorCode>([
  'ENGINE_UNAVAILABLE',
  'ENGINE_ERROR',
  'RATE_LIMITED',
  'TIMEOUT',
  'INTERNAL_ERROR',
]);

export class ValidationError extends TranslationError {
  constructor(message: string, details?: Record<string, unknown>) {
    super('VALIDATION_ERROR', message, { details, retryable: false });
    this.name = 'ValidationError';
  }
}

export class EmptyInputError extends TranslationError {
  constructor(message = 'text must be a non-empty string') {
    super('EMPTY_INPUT', message, { retryable: false });
    this.name = 'EmptyInputError';
  }
}

export class TimeoutError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super('TIMEOUT', message, options);
    this.name = 'TimeoutError';
  }
}

export class DeadlineExceededError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super('DEADLINE_EXCEEDED', message, options);
    this.name = 'DeadlineExceededError';
  }
}

export class CancelledError extends TranslationError {
  constructor(message = 'operation was cancelled', options: TranslationErrorOptions = {}) {
    super('CANCELLED', message, options);
    this.name = 'CancelledError';
  }
}

export class EngineError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super(options.retryable === false ? 'ENGINE_ERROR' : 'ENGINE_ERROR', message, options);
    this.name = 'EngineError';
  }
}

export class EngineUnavailableError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super('ENGINE_UNAVAILABLE', message, options);
    this.name = 'EngineUnavailableError';
  }
}

export class RateLimitError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super('RATE_LIMITED', message, options);
    this.name = 'RateLimitError';
  }
}

export class QuotaExceededError extends TranslationError {
  constructor(message: string, options: TranslationErrorOptions = {}) {
    super('QUOTA_EXCEEDED', message, { ...options, retryable: false });
    this.name = 'QuotaExceededError';
  }
}

export class UnsupportedLanguageError extends TranslationError {
  constructor(message: string) {
    super('UNSUPPORTED_LANGUAGE', message, { retryable: false });
    this.name = 'UnsupportedLanguageError';
  }
}

export class ConfigError extends TranslationError {
  constructor(message: string) {
    super('CONFIG_ERROR', message, { retryable: false });
    this.name = 'ConfigError';
  }
}

export function isTranslationError(value: unknown): value is TranslationError {
  return value instanceof TranslationError;
}

export function isCancellation(value: unknown): value is CancelledError {
  return isTranslationError(value) && value.code === 'CANCELLED';
}

/** Normalizes any thrown value into a TranslationError for stable boundaries. */
export function toTranslationError(value: unknown, fallbackCode: ErrorCode = 'INTERNAL_ERROR'): TranslationError {
  if (isTranslationError(value)) {
    return value;
  }
  if (value instanceof Error) {
    const name = value.name;
    if (name === 'AbortError') {
      return new CancelledError(value.message, { cause: value });
    }
    if (name === 'TimeoutError') {
      return new TimeoutError(value.message, { cause: value });
    }
    return new TranslationError(fallbackCode, value.message, { cause: value });
  }
  return new TranslationError(fallbackCode, typeof value === 'string' ? value : 'unknown error', {
    cause: value,
  });
}