/**
 * Test doubles for engines.
 *
 * `ScriptedEngine` records every request it receives so tests can assert that a
 * translation really reached an engine, how many times, and with what payload.
 * These are used for orchestration tests only; live translation tests use the
 * real MyMemory engine.
 */

import type {
  EngineHealth,
  TranslationEngine,
} from '../../src/engine/engine';
import type {
  EngineLanguagePairSupport,
  EngineLimits,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../src/core/types';
import { listAllLanguages } from '../../src/language/registry';
import { EngineUnavailableError, UnsupportedLanguageError } from '../../src/core/errors';

export interface ScriptedBehaviour {
  /** Fixed output for every call. */
  respond?: (request: EngineTranslationRequest, callIndex: number) => string | Promise<string>;
  /** Fail this many calls before succeeding. */
  failTimes?: number;
  error?: () => Error;
  /** Never resolve; used for timeout tests. */
  hang?: boolean;
  /** Milliseconds to wait before responding. */
  delayMs?: number;
  detectedSourceLanguage?: string;
  confidence?: number;
}

export class ScriptedEngine implements TranslationEngine {
  readonly id = 'scripted';
  readonly name = 'Scripted test engine';
  readonly limits: EngineLimits = { maxCharsPerRequest: 500 };

  readonly calls: EngineTranslationRequest[] = [];
  private behaviour: ScriptedBehaviour;
  private failures = 0;

  constructor(behaviour: ScriptedBehaviour = {}) {
    this.behaviour = behaviour;
  }

  setBehaviour(behaviour: ScriptedBehaviour): void {
    this.behaviour = behaviour;
    this.failures = 0;
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    this.calls.push(request);
    const callIndex = this.calls.length - 1;

    if (this.behaviour.hang) {
      return new Promise<EngineTranslationResponse>((_resolve, reject) => {
        request.signal?.addEventListener(
          'abort',
          () => {
            const error = new Error('aborted');
            error.name = 'AbortError';
            reject(error);
          },
          { once: true },
        );
      });
    }

    if (this.behaviour.delayMs) {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, this.behaviour.delayMs);
        request.signal?.addEventListener(
          'abort',
          () => {
            clearTimeout(timer);
            const error = new Error('aborted');
            error.name = 'AbortError';
            reject(error);
          },
          { once: true },
        );
      });
    }

    request.signal?.throwIfAborted();

    if (this.failures < (this.behaviour.failTimes ?? 0)) {
      this.failures += 1;
      throw this.behaviour.error ? this.behaviour.error() : new EngineUnavailableError('scripted failure');
    }

    const text = this.behaviour.respond
      ? await this.behaviour.respond(request, callIndex)
      : `[tr] ${request.text}`;

    return {
      text,
      engine: this.id,
      ...(this.behaviour.detectedSourceLanguage
        ? { detectedSourceLanguage: this.behaviour.detectedSourceLanguage }
        : {}),
      ...(this.behaviour.confidence === undefined ? {} : { confidence: this.behaviour.confidence }),
    };
  }

  get callCount(): number {
    return this.calls.length;
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
    return { engine: this.id, healthy: true };
  }
}

/** Engine that always fails; used to prove partial-failure handling. */
export class AlwaysFailingEngine extends ScriptedEngine {
  constructor() {
    super({ failTimes: Number.MAX_SAFE_INTEGER, error: () => new EngineUnavailableError('always down') });
  }
}

/** Engine that fails only for a specific source text; used for per-segment failure. */
export class SelectiveFailureEngine extends ScriptedEngine {
  constructor(failingPattern: RegExp) {
    super({
      respond: (request) => {
        if (failingPattern.test(request.text)) {
          throw new EngineUnavailableError('segment-specific failure');
        }
        return `[tr] ${request.text}`;
      },
    });
  }
}

/**
 * Engine that reports itself unconfigured, standing in for a real engine with
 * no API key. Routing must skip it rather than calling it.
 */
export class UnconfiguredEngine extends ScriptedEngine {
  readonly reason: string;

  constructor(reason = 'not configured: API key missing') {
    super({ failTimes: Number.MAX_SAFE_INTEGER });
    this.reason = reason;
  }

  configuration(): { configured: boolean; reason: string } {
    return { configured: false, reason: this.reason };
  }

  override async translate(): Promise<never> {
    // Mirrors DeepLEngine: no credential means no translation, ever.
    const { ConfigError } = require('../../src/core/errors') as {
      ConfigError: new (message: string) => Error;
    };
    throw new ConfigError(`${this.reason}; no translation was attempted.`);
  }
}

/** Engine that rejects unsupported pairs, to prove pair validation happens upstream. */
export class ArabicOnlyEngine extends ScriptedEngine {
  override supportsPair(_source: LanguageCode, target: LanguageCode): boolean {
    return target === 'ar';
  }

  override async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    if (!this.supportsPair(request.sourceLanguage, request.targetLanguage)) {
      throw new UnsupportedLanguageError('Arabic only engine');
    }
    return super.translate(request);
  }
}