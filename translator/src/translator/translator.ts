/**
 * Translator: the public application API.
 *
 * This is the surface Shura (or any other host) will use. It is a thin,
 * stable facade over TranslationService + ChapterTranslator + LanguageDetector.
 * It exposes no engine internals: callers pick an engine by id string only.
 */

import { loadConfig, type AppConfig } from '../config/index';
import { createLogger, silentLogger, type Logger } from '../core/logger';
import { CancellationToken } from '../core/cancellation';
import { DeadlineBudget } from '../core/timeout';
import { isTranslationError, toTranslationError, TranslationError, UnsupportedLanguageError } from '../core/errors';
import { validateDetectInput, validateTranslateInput, assertReasonableSize } from '../core/validation';
import { FileCache } from '../cache/fileCache';
import { MemoryCache } from '../cache/memoryCache';
import { TieredCache } from '../cache/tieredCache';
import type { TranslationCache } from '../cache/types';
import { createDefaultRegistry, EngineRegistry } from '../engine/registry';
import { EngineRouter, isFallbackEligible } from '../engine/routing';
import type { SecretStatus, SecretStore } from '../security/secretStore';
import { EnvironmentFirstSecretStore } from '../security/secretStore';
import type { EngineHealth } from '../engine/engine';
import { detectLanguageDetailed, resolveSourceLanguage } from '../language/detect';
import { listAllLanguages, listSourceLanguages, listTargetLanguages, isKnownLanguage } from '../language/registry';
import { TranslationService } from '../service/translationService';
import { ChapterTranslator } from '../chapter/chapterTranslator';
import type {
  ChapterTranslationRequest,
  ChapterTranslationResult,
  LanguageCode,
  LanguageDetectionResult,
  LanguageInfo,
  ProgressListener,
  TranslationOptions,
  TranslationResult,
} from '../core/types';

export interface TranslatorInit {
  config?: AppConfig;
  logger?: Logger;
  /** Register extra engines before resolving the default one. */
  registry?: EngineRegistry;
  /** Inject a cache; overrides configuration. */
  cache?: TranslationCache<unknown>;
  /** Skip creating the default cache entirely (used by some tests). */
  disableCache?: boolean;
  /** Runtime secret source for engine API keys. Environment variables win. */
  secretStore?: SecretStore;
}

export interface TranslateRequest extends TranslationOptions {
  text: string;
  /** Abort handle; cancellation is honoured at every layer. */
  token?: CancellationToken;
  /** Overall budget for this call. */
  deadlineMs?: number;
}

export interface ChapterTranslateRequest extends ChapterTranslationRequest {
  token?: CancellationToken;
  onProgress?: ProgressListener;
}

export class Translator {
  private readonly config: AppConfig;
  private readonly logger: Logger;
  private readonly registry: EngineRegistry;
  private readonly cache?: TranslationCache<unknown>;
  private readonly service: TranslationService;
  private readonly chapterTranslator: ChapterTranslator;
  private readonly router: EngineRouter;
  private readonly secretStore: SecretStore;
  /**
   * Keys resolved from the runtime secret store, held in memory only.
   * Never written to a project file, never logged, never returned by an API.
   */
  private readonly resolvedKeys: Partial<Record<string, string>> = {};

  constructor(init: TranslatorInit = {}) {
    this.config = init.config ?? loadConfig();
    this.logger = init.logger ?? createLogger({ level: this.config.logLevel });
    this.secretStore = init.secretStore ?? new EnvironmentFirstSecretStore();

    this.registry = init.registry ?? createDefaultRegistry({
      mymemory: {
        endpoint: this.config.engine.mymemory.endpoint,
        ...(this.config.engine.mymemory.contactEmail
          ? { contactEmail: this.config.engine.mymemory.contactEmail }
          : {}),
        maxQueryChars: this.config.engine.mymemory.maxQueryChars,
        timeoutMs: this.config.engine.mymemory.timeoutMs,
        minIntervalMs: this.config.engine.mymemory.minIntervalMs,
      },
      // The key comes from configuration (DEEPL_API_KEY). When it is absent the
      // engine is constructed unconfigured and reports itself unavailable, which
      // lets routing fall back instead of failing.
      // Resolved on every construction, so a key loaded from the secret store
      // after startup takes effect without restarting the process.
      deepl: () => ({
        ...(this.deeplApiKey() ? { apiKey: this.deeplApiKey() } : {}),
        endpoint: this.config.engine.deepl.endpoint,
        timeoutMs: this.config.engine.deepl.timeoutMs,
        maxCharsPerRequest: this.config.engine.deepl.maxCharsPerRequest,
        minIntervalMs: this.config.engine.deepl.minIntervalMs,
        ...(this.config.engine.deepl.formality === undefined
          ? {}
          : { formality: this.config.engine.deepl.formality }),
      }),
    });

    if (!this.registry.has(this.config.engine.engine)) {
      // Fail fast at construction: a misconfigured engine must not surface later
      // as a confusing per-request error.
      throw toTranslationError(
        new Error(
          `configured engine "${this.config.engine.engine}" is not registered; available: ${this.registry
            .ids()
            .filter((id) => id !== 'echo' || this.config.engine.allowEchoEngine)
            .join(', ')}`,
        ),
      );
    }

    if (!this.config.engine.allowEchoEngine && this.config.engine.engine === 'echo') {
      throw toTranslationError(
        new Error('the echo engine is offline-only; set TRANSLATION_ALLOW_ECHO_ENGINE=true to enable it'),
      );
    }

    // Availability is evaluated lazily so routing never constructs an engine
    // just to ask whether it can be used.
    this.router = new EngineRouter({
      defaultEngineId: this.config.engine.engine,
      rules: this.config.engine.routes,
      fallbackIds: this.config.engine.fallbacks,
      isAvailable: (engineId: string) => this.engineAvailability(engineId),
      failureThreshold: this.config.engine.failureThreshold,
      cooldownMs: this.config.engine.cooldownMs,
    });

    this.cache = init.disableCache
      ? undefined
      : (init.cache ??
        this.createDefaultCache());

    this.service = new TranslationService({
      resolveEngine: (engineId?: string) => this.registry.create(engineId ?? this.config.engine.engine),
      defaultEngineId: this.config.engine.engine,
      ...(this.cache ? { cache: this.cache as TranslationCache<string> } : {}),
      cacheEnabled: this.config.cache.enabled,
      defaultTimeoutMs: this.config.timeouts.requestTimeoutMs,
      defaultChapterDeadlineMs: this.config.timeouts.chapterDeadlineMs,
      retryPolicy: this.config.retry,
      segmentation: { maxChars: this.config.chapter.segmentMaxChars },
      logger: this.logger,
    });

    this.chapterTranslator = new ChapterTranslator({
      translator: this,
      defaultConcurrency: this.config.chapter.concurrency,
      defaultSegmentMaxChars: this.config.chapter.segmentMaxChars,
      maxSegments: this.config.chapter.maxSegments,
      failurePolicy: this.config.chapter.failurePolicy,
      defaultDeadlineMs: this.config.timeouts.chapterDeadlineMs,
      logger: this.logger,
    });
  }

  /**
   * Reports whether an engine can actually serve requests.
   *
   * A registered engine with no credential is registered-but-unavailable, which
   * is different from "not registered" and must never be reported as success.
   */
  private engineAvailability(engineId: string): { available: boolean; reason?: string } {
    if (!this.registry.has(engineId)) {
      return { available: false, reason: `engine "${engineId}" is not registered` };
    }
    if (engineId === 'echo' && !this.config.engine.allowEchoEngine) {
      return { available: false, reason: 'echo engine is disabled (TRANSLATION_ALLOW_ECHO_ENGINE=false)' };
    }
    // Engines that need a credential report it themselves. This keeps the check
    // general: a custom engine with its own key requirement works unchanged.
    try {
      const probe = this.registry.create(engineId).configuration?.();
      if (probe && !probe.configured) {
        return { available: false, reason: probe.reason ?? 'engine is not configured' };
      }
    } catch (error) {
      return { available: false, reason: error instanceof Error ? error.message : String(error) };
    }
    return { available: true };
  }

  private deeplApiKey(): string | undefined {
    return this.resolvedKeys.deepl ?? this.config.engine.deepl.apiKey;
  }

  /**
   * Loads engine API keys from the runtime secret store into memory.
   *
   * Called by the server at boot and after the UI saves a key. Environment
   * variables are handled inside the store and take precedence, so this never
   * overrides a CI-provided credential.
   */
  async loadSecrets(): Promise<SecretStatus[]> {
    const statuses: SecretStatus[] = [];
    for (const engineId of this.registry.ids()) {
      if (engineId === 'echo') {
        continue;
      }
      try {
        const status = await this.secretStore.status(engineId);
        statuses.push(status);
        const value = await this.secretStore.get(engineId);
        const previous = this.resolvedKeys[engineId];
        if (value && value.length > 0) {
          this.resolvedKeys[engineId] = value;
        } else {
          delete this.resolvedKeys[engineId];
        }
        // The engine captured its credential at construction, so a cached
        // instance must be rebuilt whenever the resolved value changes.
        if (previous !== this.resolvedKeys[engineId]) {
          this.registry.invalidate(engineId);
        }
      } catch {
        statuses.push({ engine: engineId, configured: false, source: 'none' });
      }
    }
    return statuses;
  }

  /** Secret status per engine. Values are never included. */
  async describeSecrets(): Promise<SecretStatus[]> {
    const statuses: SecretStatus[] = [];
    for (const engineId of this.registry.ids()) {
      if (engineId === 'echo') {
        continue;
      }
      try {
        statuses.push(await this.secretStore.status(engineId));
      } catch {
        statuses.push({ engine: engineId, configured: false, source: 'none' });
      }
    }
    return statuses;
  }

  /** Stores an engine API key in the runtime secret store. */
  async saveSecret(engine: string, value: string): Promise<SecretStatus> {
    await this.secretStore.set(engine, value);
    // The cached engine instance captured the previous (absent) credential.
    this.registry.invalidate(engine);
    return this.loadSecrets().then((all) => all.find((s) => s.engine === engine) ?? {
      engine,
      configured: true,
      source: 'vault',
    });
  }

  /** Removes an engine API key from the runtime secret store. */
  async deleteSecret(engine: string): Promise<SecretStatus> {
    await this.secretStore.delete(engine);
    delete this.resolvedKeys[engine];
    this.registry.invalidate(engine);
    const status = await this.secretStore.status(engine);
    return status;
  }

  private createDefaultCache(): TranslationCache<unknown> | undefined {
    if (!this.config.cache.enabled) {
      return undefined;
    }
    const memory = new MemoryCache<unknown>({
      defaultTtlMs: this.config.cache.ttlMs,
      maxEntries: this.config.cache.maxEntries,
    });
    const file = new FileCache<unknown>({
      directory: this.config.cache.directory,
      defaultTtlMs: this.config.cache.ttlMs,
      maxEntries: this.config.cache.maxEntries,
    });
    return new TieredCache<unknown>({ memory, file, logger: this.logger });
  }

  // --- Primary API -------------------------------------------------------

  /**
   * Translates a single text.
   *
   * Engine selection happens here rather than inside the service so the service
   * stays unaware of routing, and so a failed engine can be retried on a
   * fallback while still reporting which engine actually produced the text.
   */
  async translate(request: TranslateRequest): Promise<TranslationResult> {
    assertReasonableSize(request.text);
    const token = request.token ?? CancellationToken.none();
    token.throwIfCancelled();

    // Routing needs the source language. Detection here is the same pure
    // function the service uses, so the result is identical; passing the
    // resolved code down makes the cache key stable either way.
    const resolvedSource = resolveSourceLanguage(request.sourceLanguage ?? 'auto', request.text);
    const sourceLanguage = resolvedSource.language;

    const { decision, skipped } = this.router.resolveAvailable(sourceLanguage, request.engine);
    if (skipped.length > 0) {
      this.logger.warn('engine skipped during routing', { skipped });
    }

    const budget = new DeadlineBudget(
      request.deadlineMs ?? this.config.timeouts.chapterDeadlineMs,
      { message: 'translate deadline exceeded' },
    );

    const baseOptions = {
      text: request.text,
      // `auto` is forwarded so the service still reports detectedLanguage;
      // routing already used the resolved code above.
      sourceLanguage: request.sourceLanguage ?? 'auto',
      targetLanguage: request.targetLanguage,
      ...(request.noCache === undefined ? {} : { noCache: request.noCache }),
      ...(request.refresh === undefined ? {} : { refresh: request.refresh }),
      ...(request.timeoutMs === undefined ? {} : { timeoutMs: request.timeoutMs }),
      ...(request.retries === undefined ? {} : { retries: request.retries }),
      ...(request.acceptStaleCache === undefined ? {} : { acceptStaleCache: request.acceptStaleCache }),
      ...(request.contextBefore === undefined ? {} : { contextBefore: request.contextBefore }),
      ...(request.hints === undefined ? {} : { hints: request.hints }),
    };

    const candidates = [decision.engine, ...decision.fallbacks].filter(
      (id, index, all) => all.indexOf(id) === index,
    );

    let lastError: unknown;
    for (const [index, engineId] of candidates.entries()) {
      try {
        const result = await this.service.translate(
          { ...baseOptions, engine: engineId },
          { token, deadlineMs: budget.deadline.remainingMs() },
        );
        this.router.noteSuccess(engineId);
        return result;
      } catch (error) {
        void resolvedSource;
        lastError = error;
        if (isFallbackEligible(error)) {
          // Opens the breaker once the engine has failed repeatedly, so a dead
          // engine stops costing a request per call.
          this.router.noteFailure(engineId);
        }
        const isLast = index === candidates.length - 1;
        if (isLast || !isFallbackEligible(error) || budget.deadline.expired()) {
          throw error;
        }
        this.logger.warn('falling back to the next engine', {
          from: engineId,
          to: candidates[index + 1],
          code: isTranslationError(error) ? error.code : 'UNKNOWN',
        });
      }
    }
    throw lastError ?? new TranslationError('INTERNAL_ERROR', 'no engine available');
  }

  /** Breaker state per engine, safe to expose over REST. */
  describeBreakers(): Array<{ engine: string; failures: number; coolingDown: boolean }> {
    return this.router.describeBreakers();
  }

  /** Routing table and fallbacks, safe to expose over REST. */
  describeRouting(): { default: string; rules: Array<{ source: string; engine: string }>; fallbacks: string[] } {
    return this.router.describe();
  }

  /** Resolves which engine would handle a request, for diagnostics and the UI. */
  resolveEngineFor(
    sourceLanguage: string,
    requestedEngine?: string,
  ): { engine: string; reason: string; fallbacks: string[]; skipped: Array<{ engine: string; reason: string }> } {
    const { decision, skipped } = this.router.resolveAvailable(sourceLanguage, requestedEngine);
    return { engine: decision.engine, reason: decision.reason, fallbacks: decision.fallbacks, skipped };
  }

  /** Configured engine availability, safe to expose over REST. */
  describeEngines(): Array<{ id: string; available: boolean; reason?: string; maxCharsPerRequest?: number }> {
    return this.registry.ids().map((id) => {
      const availability = this.engineAvailability(id);
      let maxCharsPerRequest: number | undefined;
      try {
        maxCharsPerRequest = this.registry.create(id).limits.maxCharsPerRequest;
      } catch {
        maxCharsPerRequest = undefined;
      }
      return {
        id,
        available: availability.available,
        ...(availability.reason ? { reason: availability.reason } : {}),
        ...(maxCharsPerRequest === undefined ? {} : { maxCharsPerRequest }),
      };
    });
  }

  /** Translates a full chapter. One failed segment does not lose the chapter. */
  async translateChapter(request: ChapterTranslateRequest): Promise<ChapterTranslationResult> {
    return this.chapterTranslator.translateChapter(request, {
      ...(request.token ? { token: request.token } : {}),
      ...(request.onProgress ? { onProgress: request.onProgress } : {}),
    });
  }

  /** Retries only the segments that failed in a previous chapter result. */
  async retryChapter(
    previous: ChapterTranslationResult,
    request: ChapterTranslateRequest,
  ): Promise<ChapterTranslationResult> {
    return this.chapterTranslator.retryFailedSegments(previous, request, {
      ...(request.token ? { token: request.token } : {}),
      ...(request.onProgress ? { onProgress: request.onProgress } : {}),
    });
  }

  /** Detects the language of a text. */
  detectLanguage(request: { text: string }): LanguageDetectionResult {
    const text = validateDetectInput({ text: request.text });
    return detectLanguageDetailed(text);
  }

  /** Languages the platform knows about. */
  getSupportedLanguages(): {
    source: LanguageInfo[];
    target: LanguageInfo[];
    all: LanguageInfo[];
    engine: string;
    engines: string[];
  } {
    return {
      source: listSourceLanguages(),
      target: listTargetLanguages(),
      all: listAllLanguages(),
      engine: this.config.engine.engine,
      engines: this.registry.ids(),
    };
  }

  /** Languages the currently selected engine actually supports. */
  getEngineCapabilities(engineId?: string): {
    engine: string;
    source: LanguageInfo[];
    target: LanguageInfo[];
    maxCharsPerRequest: number;
  } {
    const engine = this.registry.create(engineId ?? this.config.engine.engine);
    return {
      engine: engine.id,
      source: engine.getSourceLanguages(),
      target: engine.getTargetLanguages(),
      maxCharsPerRequest: engine.limits.maxCharsPerRequest,
    };
  }

  async checkEngineHealth(engineId?: string): Promise<EngineHealth> {
    const engine = this.registry.create(engineId ?? this.config.engine.engine);
    if (!engine.healthCheck) {
      return { engine: engine.id, healthy: true, detail: 'engine does not expose a health check' };
    }
    return engine.healthCheck();
  }

  /** Validates a language pair against the active engine. */
  assertPairSupported(source: LanguageCode, target: LanguageCode): void {
    const engine = this.registry.create(this.config.engine.engine);
    if (!isKnownLanguage(source) || !isKnownLanguage(target)) {
      throw new UnsupportedLanguageError(`unknown language pair: ${source} -> ${target}`);
    }
    if (!engine.supportsPair(source, target)) {
      throw new UnsupportedLanguageError(`engine ${engine.id} does not support ${source} -> ${target}`);
    }
  }

  // --- Cache control -----------------------------------------------------

  async clearCache(): Promise<void> {
    await this.cache?.clear();
  }

  async cacheSize(): Promise<number> {
    return this.cache?.size() ?? 0;
  }

  async flushCache(): Promise<void> {
    await this.cache?.flush?.();
  }

  /** Applies the same validation the REST layer uses. */
  validate(input: { text: string; sourceLanguage?: unknown; targetLanguage: unknown }): void {
    validateTranslateInput({
      text: input.text,
      sourceLanguage: input.sourceLanguage ?? 'auto',
      targetLanguage: input.targetLanguage,
    });
  }

  get engine(): string {
    return this.config.engine.engine;
  }

  get loggerInstance(): Logger {
    return this.logger;
  }

  get settings(): AppConfig {
    return this.config;
  }
}

/** Convenience factory used by the CLI and tests. */
export function createTranslator(init: TranslatorInit = {}): Translator {
  return new Translator(init);
}

export { silentLogger };