/**
 * Environment / configuration loading.
 *
 * Rules:
 *  - All tunables come from environment variables (optionally via a .env file).
 *  - No API key, secret or credential is ever stored in source. Credentials are
 *    read from the environment only and are redacted in logs.
 *  - Engine selection is a single string, so switching engines is a config
 *    change and not a code change.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { ConfigError } from '../core/errors';
import type { LogLevel } from '../core/logger';
import type { RetryPolicy } from '../core/retry';
import type { EngineRouteRule } from '../engine/routing';
import { parseFallbacks, parseRoutes } from '../engine/routing';

export interface EngineConfig {
  /** Active engine id. */
  engine: string;
  mymemory: {
    endpoint: string;
    contactEmail?: string;
    maxQueryChars: number;
    timeoutMs: number;
    minIntervalMs: number;
  };
  /**
   * DeepL settings.
   *
   * `apiKey` is read from DEEPL_API_KEY only. It is never written to any file
   * the project owns, never logged, and never returned by the REST layer —
   * `describeConfig` reports `configured: true|false` instead.
   */
  deepl: {
    apiKey?: string;
    endpoint: string;
    timeoutMs: number;
    maxCharsPerRequest: number;
    minIntervalMs: number;
    formality?: DeepLFormality;
  };
  /** Allows the offline echo engine to be registered at all. */
  allowEchoEngine: boolean;
  /** Per-source-language engine routing, e.g. [{ source: 'ja', engine: 'deepl' }]. */
  routes: EngineRouteRule[];
  /** Ordered fallback engines used when the routed engine fails. */
  fallbacks: string[];
  /** Consecutive engine failures before the breaker skips an engine. */
  failureThreshold: number;
  /** How long a failing engine is skipped, in ms. 0 disables the breaker. */
  cooldownMs: number;
}

export type DeepLFormality = 'default' | 'more' | 'less' | 'prefer_more' | 'prefer_less';

export interface ServerConfig {
  host: string;
  port: number;
}

export interface TimeoutConfig {
  requestTimeoutMs: number;
  chapterDeadlineMs: number;
}

export interface CacheConfig {
  enabled: boolean;
  directory: string;
  ttlMs: number;
  maxEntries: number;
}

export interface ChapterConfig {
  maxSegments: number;
  concurrency: number;
  segmentMaxChars: number;
  failurePolicy: 'partial' | 'abort';
}

export interface AppConfig {
  server: ServerConfig;
  engine: EngineConfig;
  timeouts: TimeoutConfig;
  retry: RetryPolicy;
  cache: CacheConfig;
  chapter: ChapterConfig;
  logLevel: LogLevel;
  /** Absolute path of the config root, used to resolve the cache directory. */
  rootDir: string;
}

export type EnvSource = Record<string, string | undefined>;

function loadDotEnv(rootDir: string): EnvSource {
  const file = path.join(rootDir, '.env');
  if (!fs.existsSync(file)) {
    return {};
  }
  const result: EnvSource = {};
  const content = fs.readFileSync(file, 'utf8');
  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith('#')) {
      continue;
    }
    const eq = line.indexOf('=');
    if (eq === -1) {
      continue;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

function readNumber(
  source: EnvSource,
  key: string,
  fallback: number,
  { min = 0, max = Number.MAX_SAFE_INTEGER }: { min?: number; max?: number } = {},
): number {
  const raw = source[key];
  if (raw === undefined || raw.trim() === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isFinite(value)) {
    throw new ConfigError(`${key} must be a number, received "${raw}"`);
  }
  if (value < min || value > max) {
    throw new ConfigError(`${key} must be between ${min} and ${max}, received ${value}`);
  }
  return value;
}

function readString(source: EnvSource, key: string, fallback: string): string {
  const raw = source[key];
  return raw === undefined || raw.trim() === '' ? fallback : raw.trim();
}

function readBoolean(source: EnvSource, key: string, fallback: boolean): boolean {
  const raw = source[key];
  if (raw === undefined || raw.trim() === '') {
    return fallback;
  }
  const normalized = raw.trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(normalized)) {
    return true;
  }
  if (['0', 'false', 'no', 'off'].includes(normalized)) {
    return false;
  }
  throw new ConfigError(`${key} must be a boolean, received "${raw}"`);
}

function readLogLevel(source: EnvSource, key: string, fallback: LogLevel): LogLevel {
  const raw = readString(source, key, fallback);
  if (['silent', 'error', 'warn', 'info', 'debug'].includes(raw)) {
    return raw as LogLevel;
  }
  throw new ConfigError(`${key} must be one of silent|error|warn|info|debug, received "${raw}"`);
}

export interface LoadConfigOptions {
  rootDir?: string;
  env?: EnvSource;
  /** When false, .env is not read. Defaults to true. */
  readDotEnv?: boolean;
}

export function loadConfig(options: LoadConfigOptions = {}): AppConfig {
  const rootDir = options.rootDir ?? process.cwd();
  const fromFile = options.readDotEnv === false ? {} : loadDotEnv(rootDir);
  const env: EnvSource = { ...fromFile, ...(options.env ?? process.env) };

  const cacheDirectory = path.isAbsolute(readString(env, 'CACHE_DIR', '.cache'))
    ? readString(env, 'CACHE_DIR', '.cache')
    : path.join(rootDir, readString(env, 'CACHE_DIR', '.cache'));

  const contactEmail = readString(env, 'MYMEMORY_CONTACT_EMAIL', '');
  // Read from the environment only. Never persisted by this project, never logged.
  const deeplKey = readString(env, 'DEEPL_API_KEY', '');

  const formalityRaw = readString(env, 'DEEPL_FORMALITY', '');
  const formality: DeepLFormality | undefined = ((): DeepLFormality | undefined => {
    if (formalityRaw.length === 0) {
      return undefined;
    }
    const allowed: DeepLFormality[] = ['default', 'more', 'less', 'prefer_more', 'prefer_less'];
    if (!allowed.includes(formalityRaw as DeepLFormality)) {
      throw new ConfigError(
        `DEEPL_FORMALITY must be one of ${allowed.join('|')}, received "${formalityRaw}"`,
      );
    }
    return formalityRaw as DeepLFormality;
  })();

  return {
    rootDir,
    server: {
      host: readString(env, 'SERVER_HOST', '127.0.0.1'),
      port: readNumber(env, 'SERVER_PORT', 8787, { min: 0, max: 65535 }),
    },
    engine: {
      // DeepL is the default when a key is present, because it is materially
      // better for the project's priority pairs. Without a key the default
      // stays MyMemory so the platform keeps working.
      engine: readString(
        env,
        'TRANSLATION_ENGINE',
        deeplKey ? 'deepl' : 'mymemory',
      ),
      allowEchoEngine: readBoolean(env, 'TRANSLATION_ALLOW_ECHO_ENGINE', false),
      routes: parseRoutes(readString(env, 'TRANSLATION_ENGINE_ROUTES', '')),
      fallbacks: parseFallbacks(
        readString(env, 'TRANSLATION_ENGINE_FALLBACKS', deeplKey ? 'mymemory' : ''),
      ),
      failureThreshold: readNumber(env, 'ENGINE_FAILURE_THRESHOLD', 2, { min: 1, max: 100 }),
      cooldownMs: readNumber(env, 'ENGINE_COOLDOWN_MS', 60000, { min: 0, max: 3600000 }),
      mymemory: {
        endpoint: readString(env, 'MYMEMORY_ENDPOINT', 'https://api.mymemory.translated.net/get'),
        ...(contactEmail ? { contactEmail } : {}),
        maxQueryChars: readNumber(env, 'MYMEMORY_MAX_QUERY_CHARS', 480, { min: 50, max: 500 }),
        timeoutMs: readNumber(env, 'MYMEMORY_TIMEOUT_MS', 15000, { min: 100, max: 120000 }),
        minIntervalMs: readNumber(env, 'MYMEMORY_MIN_INTERVAL_MS', 250, { min: 0, max: 10000 }),
      },
      deepl: {
        ...(deeplKey ? { apiKey: deeplKey } : {}),
        // Free plan by default; Pro plans use https://api.deepl.com/v2/translate
        endpoint: readString(env, 'DEEPL_ENDPOINT', 'https://api-free.deepl.com/v2/translate'),
        timeoutMs: readNumber(env, 'DEEPL_TIMEOUT_MS', 15000, { min: 100, max: 120000 }),
        maxCharsPerRequest: readNumber(env, 'DEEPL_MAX_CHARS', 4000, { min: 100, max: 120000 }),
        minIntervalMs: readNumber(env, 'DEEPL_MIN_INTERVAL_MS', 100, { min: 0, max: 10000 }),
        ...(formality === undefined ? {} : { formality }),
      },
    },
    timeouts: {
      requestTimeoutMs: readNumber(env, 'REQUEST_TIMEOUT_MS', 15000, { min: 100, max: 600000 }),
      chapterDeadlineMs: readNumber(env, 'CHAPTER_DEADLINE_MS', 120000, { min: 1000, max: 3600000 }),
    },
    retry: {
      maxAttempts: readNumber(env, 'RETRY_MAX_ATTEMPTS', 3, { min: 1, max: 10 }),
      baseDelayMs: readNumber(env, 'RETRY_BASE_DELAY_MS', 300, { min: 0, max: 60000 }),
      maxDelayMs: readNumber(env, 'RETRY_MAX_DELAY_MS', 4000, { min: 0, max: 300000 }),
      jitterRatio: readNumber(env, 'RETRY_JITTER_RATIO', 0.2, { min: 0, max: 1 }),
    },
    cache: {
      enabled: readBoolean(env, 'CACHE_ENABLED', true),
      directory: cacheDirectory,
      ttlMs: readNumber(env, 'CACHE_TTL_MS', 2_592_000_000, { min: 1000 }),
      maxEntries: readNumber(env, 'CACHE_MAX_ENTRIES', 5000, { min: 10 }),
    },
    chapter: {
      maxSegments: readNumber(env, 'CHAPTER_MAX_SEGMENTS', 400, { min: 1, max: 10000 }),
      concurrency: readNumber(env, 'CHAPTER_CONCURRENCY', 3, { min: 1, max: 16 }),
      segmentMaxChars: readNumber(env, 'CHAPTER_SEGMENT_MAX_CHARS', 450, { min: 20, max: 100000 }),
      failurePolicy: ((): 'partial' | 'abort' => {
        const raw = readString(env, 'CHAPTER_FAILURE_POLICY', 'partial');
        if (raw === 'partial' || raw === 'abort') {
          return raw;
        }
        throw new ConfigError(`CHAPTER_FAILURE_POLICY must be partial|abort, received "${raw}"`);
      })(),
    },
    logLevel: readLogLevel(env, 'LOG_LEVEL', 'info'),
  };
}

/**
 * Log-safe view of the config.
 *
 * Credentials are never included: only booleans and a non-reversible key
 * fingerprint. This is what `GET /config` returns, so the REST layer cannot
 * leak a secret even by accident.
 */
export function describeConfig(config: AppConfig): Record<string, unknown> {
  return {
    engine: config.engine.engine,
    // Kept at the top level: existing REST consumers read these flat fields.
    endpoint: config.engine.mymemory.endpoint,
    authenticated: Boolean(config.engine.mymemory.contactEmail),
    engines: {
      mymemory: {
        endpoint: config.engine.mymemory.endpoint,
        authenticated: Boolean(config.engine.mymemory.contactEmail),
        maxCharsPerRequest: config.engine.mymemory.maxQueryChars,
      },
      deepl: {
        endpoint: config.engine.deepl.endpoint,
        // Presence only. The key itself is never emitted.
        configured: Boolean(config.engine.deepl.apiKey),
        keyFingerprint: config.engine.deepl.apiKey
          ? createHash('sha256').update(config.engine.deepl.apiKey).digest('hex').slice(0, 12)
          : undefined,
        maxCharsPerRequest: config.engine.deepl.maxCharsPerRequest,
      },
    },
    routes: config.engine.routes,
    fallbacks: config.engine.fallbacks,
    requestTimeoutMs: config.timeouts.requestTimeoutMs,
    chapterDeadlineMs: config.timeouts.chapterDeadlineMs,
    retryMaxAttempts: config.retry.maxAttempts,
    cacheEnabled: config.cache.enabled,
    cacheTtlMs: config.cache.ttlMs,
    chapterConcurrency: config.chapter.concurrency,
    chapterSegmentMaxChars: config.chapter.segmentMaxChars,
    logLevel: config.logLevel,
  };
}