/**
 * LocalEngine — the core translation engine (ADR 0001).
 *
 * Implements the existing `TranslationEngine` interface, so nothing above
 * `src/engine/engine.ts` changes and providers stay optional. It talks to a local
 * inference server (llama.cpp, TGI, vLLM, Ollama) over HTTP, which keeps the
 * platform at zero Node dependencies and makes the serving stack swappable.
 *
 * Three implementations:
 *  - `LocalHttpEngine`     real local model server (llama.cpp / OpenAI-compatible)
 *  - `DeterministicEngine` offline, for tests and CI — never a translation
 *  - `LocalEngineFactory`  picks between them from configuration
 */

import { createHash } from 'node:crypto';

import {
  EngineError,
  EngineUnavailableError,
  TimeoutError,
  UnsupportedLanguageError,
} from '../../../core/errors';
import type { CancellationToken } from '../../../core/cancellation';
import { getLanguageInfo, listAllLanguages } from '../../../language/registry';
import type {
  EngineLanguagePairSupport,
  EngineLimits,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../../core/types';
import type { EngineHealth, TranslationEngine } from '../../../engine/engine';

export type LocalServingStyle = 'llamacpp' | 'openai';

export interface LocalEngineOptions {
  /**
   * Model identity. Both values enter the cache key (ADR 0009), so a model swap
   * invalidates rather than silently serving stale translations.
   */
  modelId: string;
  modelVersion?: string;
  /** Base URL of the local inference server. */
  endpoint: string;
  servingStyle?: LocalServingStyle;
  /** Engine id used for routing and cache keys. Defaults to `local`. */
  engineId?: string;
  /** Characters per request; drives segmentation. */
  maxCharsPerRequest?: number;
  timeoutMs?: number;
  /** Serialisation gap in ms, to avoid a burst per chapter segment. */
  minIntervalMs?: number;
  /** Prompt template. Arabic is the only target this platform supports. */
  promptTemplate?: string;
  /** Extra instructions appended after the text, e.g. glossary guidance. */
  suffix?: string;
  temperature?: number;
  maxTokens?: number;
  /**
   * No local model loaded yet. The engine reports itself unavailable so routing
   * picks a fallback instead of failing the request.
   */
  modelLoaded?: boolean;
}

const DEFAULT_PROMPT =
  'Translate the following {source} text into natural {target}. ' +
  'Preserve tone and register. Output only the translation.\n\n{text}';

/** Languages the local tier serves as source. Arabic is the target. */
const LOCAL_SOURCE_LANGUAGES = ['en', 'ja', 'zh', 'ko', 'ar'];

export class LocalHttpEngine implements TranslationEngine {
  readonly id: string;
  readonly name: string;
  readonly limits: EngineLimits;

  readonly modelId: string;
  readonly modelVersion: string;

  private readonly endpoint: string;
  private readonly servingStyle: LocalServingStyle;
  private readonly maxCharsPerRequest: number;
  private readonly timeoutMs: number;
  private readonly minIntervalMs: number;
  private readonly promptTemplate: string;
  private readonly suffix?: string;
  private readonly temperature: number;
  private readonly maxTokens: number;
  private readonly loaded: boolean;

  private lastRequestAt = 0;
  private throttle: Promise<unknown> = Promise.resolve();

  constructor(options: LocalEngineOptions) {
    if (!options.endpoint) {
      throw new EngineError('local engine requires an endpoint', { retryable: false });
    }
    this.modelId = options.modelId;
    this.modelVersion = options.modelVersion ?? 'unversioned';
    this.id = options.engineId ?? 'local';
    this.name = `Local (${options.modelId})`;
    this.endpoint = options.endpoint.replace(/\/+$/, '');
    this.servingStyle = options.servingStyle ?? 'openai';
    this.maxCharsPerRequest = options.maxCharsPerRequest ?? 1200;
    this.limits = { maxCharsPerRequest: this.maxCharsPerRequest };
    this.timeoutMs = options.timeoutMs ?? 60000;
    this.minIntervalMs = Math.max(0, options.minIntervalMs ?? 0);
    if (options.promptTemplate !== undefined) {
      this.promptTemplate = options.promptTemplate;
    } else {
      this.promptTemplate = DEFAULT_PROMPT;
    }
    if (options.suffix !== undefined) {
      this.suffix = options.suffix;
    }
    this.temperature = options.temperature ?? 0;
    this.maxTokens = options.maxTokens ?? 512;
    this.loaded = options.modelLoaded !== false;
  }

  /**
   * Credential probe consumed by routing. A local engine has no API key, so the
   * only reason to be unavailable is that no model is loaded.
   */
  configuration(): { configured: boolean; reason?: string } {
    return this.loaded
      ? { configured: true }
      : { configured: false, reason: `no local model loaded for ${this.modelId}` };
  }

  /** Model identity for the cache key and for provenance in stored results. */
  get model(): { modelId: string; modelVersion: string } {
    return { modelId: this.modelId, modelVersion: this.modelVersion };
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    if (!this.loaded) {
      throw new EngineUnavailableError(
        `local model "${this.modelId}" is not loaded`,
        { engine: this.id },
      );
    }
    const text = request.text;
    if (text.trim().length === 0) {
      throw new EngineError('empty text rejected by engine', { engine: this.id, retryable: false });
    }
    if (text.length > this.maxCharsPerRequest) {
      throw new EngineError(
        `text of ${text.length} characters exceeds the local engine limit of ${this.maxCharsPerRequest}`,
        { engine: this.id, retryable: false, details: { limit: this.maxCharsPerRequest } },
      );
    }

    const target = normalizeTarget(request.targetLanguage);
    if (!target) {
      throw new UnsupportedLanguageError(
        `local engine only targets Arabic, received "${request.targetLanguage}"`,
      );
    }
    const sourceName = sourceNameOf(request.sourceLanguage);

    const prompt = this.buildPrompt(text, sourceName, target, request.hints?.instructions);

    const started = Date.now();
    const body =
      this.servingStyle === 'llamacpp'
        ? { prompt, stream: false, temperature: this.temperature, n_predict: this.maxTokens }
        : {
            model: this.modelId,
            messages: [{ role: 'user', content: prompt }],
            temperature: this.temperature,
            max_tokens: this.maxTokens,
            stream: false,
          };

    const controller = new AbortController();
    const external = request.signal;
    const onAbort = (): void => controller.abort();
    external?.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => controller.abort(), request.timeoutMs ?? this.timeoutMs);

    let raw: string;
    try {
      raw = await this.throttled(async () => {
        const response = await fetch(`${this.endpoint}${this.servingStyle === 'llamacpp' ? '/completion' : '/chat/completions'}`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
          signal: controller.signal,
        });
        if (!response.ok) {
          const detail = await response.text().catch(() => '');
          throw new EngineError(
            `local model server responded ${response.status}: ${detail.slice(0, 200)}`,
            { engine: this.id, retryable: response.status >= 500 || response.status === 429 },
          );
        }
        const json = (await response.json()) as Record<string, unknown>;
        return extractText(this.servingStyle, json);
      });
    } catch (error) {
      if (external?.aborted) {
        throw error;
      }
      if (controller.signal.aborted) {
        throw new TimeoutError(
          `local model timed out after ${request.timeoutMs ?? this.timeoutMs}ms`,
          { engine: this.id },
        );
      }
      throw error instanceof Error && error.name === 'TranslationError'
        ? error
        : new EngineError(error instanceof Error ? error.message : 'local inference failed', {
            engine: this.id,
          });
    } finally {
      clearTimeout(timer);
      external?.removeEventListener('abort', onAbort);
    }

    const translated = stripPreamble(raw.trim());
    if (translated.length === 0) {
      throw new EngineError('local model returned an empty translation', {
        engine: this.id,
        retryable: true,
      });
    }

    return {
      text: translated,
      engine: this.id,
      // Local models carry no reliable intrinsic confidence; quality is scored
      // downstream. Reporting a made-up number here would be dishonest.
      raw: {
        modelId: this.modelId,
        modelVersion: this.modelVersion,
        elapsedMs: Date.now() - started,
        promptHash: createHash('sha256').update(prompt).digest('hex').slice(0, 12),
      },
    };
  }

  private buildPrompt(text: string, source: string, target: string, extra?: string): string {
    const base = this.promptTemplate
      .replace('{source}', source)
      .replace('{target}', target)
      .replace('{text}', text);
    return extra ? `${base}\n\n${extra}${this.suffix ? `\n${this.suffix}` : ''}` : base + (this.suffix ? `\n${this.suffix}` : '');
  }

  private async throttled<T>(run: () => Promise<T>): Promise<T> {
    if (this.minIntervalMs <= 0) {
      return run();
    }
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

  getSourceLanguages(): LanguageInfo[] {
    return listAllLanguages().filter((l: LanguageInfo) => LOCAL_SOURCE_LANGUAGES.includes(l.code));
  }

  getTargetLanguages(): LanguageInfo[] {
    const arabic = getLanguageInfo('ar');
    return arabic ? [arabic] : [];
  }

  supportsPair(source: LanguageCode, target: LanguageCode): boolean {
    return LOCAL_SOURCE_LANGUAGES.includes(String(source)) && target === 'ar' && source !== target;
  }

  supportedPairs(): EngineLanguagePairSupport[] {
    return LOCAL_SOURCE_LANGUAGES.filter((s) => s !== 'ar').map((source) => ({ source, target: 'ar' }));
  }

  async healthCheck(token?: CancellationToken): Promise<EngineHealth> {
    if (!this.loaded) {
      return { engine: this.id, healthy: false, detail: `no local model loaded for ${this.modelId}` };
    }
    const started = Date.now();
    try {
      const response = await fetch(`${this.endpoint}/health`, {
        ...(token ? { signal: token.signal } : {}),
      });
      // A response of any status proves the server is reachable. Only a 5xx
      // means the model itself is failing; 404 is common (Ollama has no
      // /health) and must not be read as "model down", but the status is kept
      // in the detail so nothing is hidden.
      const healthy = response.status < 500;
      return {
        engine: this.id,
        healthy,
        detail: healthy
          ? `${this.modelId}@${this.modelVersion} (${this.endpoint} responded ${response.status})`
          : `${this.modelId}@${this.modelVersion} reported ${response.status} at ${this.endpoint}`,
        latencyMs: Date.now() - started,
      };
    } catch (error) {
      // Connection refused / DNS / timeout. This must be unhealthy: treating an
      // unreachable server as healthy silently sends production traffic to a
      // dead endpoint.
      return {
        engine: this.id,
        healthy: false,
        detail: `${this.modelId} server unreachable at ${this.endpoint}: ${
          error instanceof Error ? error.message : String(error)
        }`,
        latencyMs: Date.now() - started,
      };
    }
  }
}

/**
 * Deterministic offline engine.
 *
 * Used by tests and CI. It produces a *marked* pseudo-translation: the output is
 * obviously synthetic and carries confidence 0, so it can never be mistaken for
 * a real translation. This is the same discipline as the existing `echo` engine.
 */
export class DeterministicEngine implements TranslationEngine {
  readonly id: string;
  readonly name: string;
  readonly limits: EngineLimits = { maxCharsPerRequest: 4000 };
  readonly modelId: string;
  readonly modelVersion: string;

  constructor(options: { engineId?: string; modelId?: string; modelVersion?: string } = {}) {
    this.id = options.engineId ?? 'local-deterministic';
    this.name = 'Local deterministic (offline, not a translation)';
    this.modelId = options.modelId ?? 'deterministic-stub';
    this.modelVersion = options.modelVersion ?? 'v1';
  }

  configuration(): { configured: boolean } {
    return { configured: true };
  }

  get model(): { modelId: string; modelVersion: string } {
    return { modelId: this.modelId, modelVersion: this.modelVersion };
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    request.signal?.throwIfAborted();
    // Deterministic hash so a test can assert stability across runs.
    const digest = createHash('sha256')
      .update(`${request.sourceLanguage}|${request.targetLanguage}|${request.text}`)
      .digest('hex')
      .slice(0, 8);
    return {
      text: `[${request.sourceLanguage}->${request.targetLanguage}:${digest}] ${request.text}`,
      engine: this.id,
      confidence: 0,
    };
  }

  getSourceLanguages(): LanguageInfo[] {
    return listAllLanguages();
  }

  getTargetLanguages(): LanguageInfo[] {
    return listAllLanguages();
  }

  supportsPair(source: LanguageCode, target: LanguageCode): boolean {
    return source !== target;
  }

  supportedPairs(): EngineLanguagePairSupport[] {
    return [{ source: 'en', target: 'ar' }];
  }

  async healthCheck(): Promise<EngineHealth> {
    return { engine: this.id, healthy: true, detail: 'deterministic offline stub' };
  }
}

function normalizeTarget(code: LanguageCode): string | undefined {
  const normalized = String(code).toLowerCase();
  return normalized === 'ar' ? 'Arabic' : undefined;
}

function sourceNameOf(code: LanguageCode): string {
  return getLanguageInfo(String(code))?.name ?? String(code);
}

/** Pulls the text out of either llama.cpp or OpenAI-compatible responses. */
export function extractText(style: LocalServingStyle, json: Record<string, unknown>): string {
  if (style === 'llamacpp') {
    return typeof json.content === 'string' ? json.content : '';
  }
  const choices = json.choices as Array<{ message?: { content?: string }; text?: string }> | undefined;
  const first = choices?.[0];
  return first?.message?.content ?? first?.text ?? '';
}

/**
 * Removes chat boilerplate a model may wrap the translation in.
 *
 * Chat models habitually emit "Translation: ..." or a fenced block; leaving it in
 * would ship English text to an Arabic reader.
 */
export function stripPreamble(text: string): string {
  let out = text.trim();
  out = out.replace(/^```[a-z]*\n?/i, '').replace(/\n?```$/, '');
  out = out.replace(/^(translation|translated|arabic|النص|الترجمة)\s*[:：]\s*/i, '');
  return out.trim();
}