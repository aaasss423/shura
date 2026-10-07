/**
 * Deterministic offline engine.
 *
 * This exists for tests and offline UI development only. It is NOT a fallback in
 * production and the service marks its results as untrusted quality so it can
 * never be mistaken for a real translation. Registering it requires
 * `TRANSLATION_ALLOW_ECHO_ENGINE=true`, and it is rejected by default.
 */

import type {
  EngineLanguagePairSupport,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../core/types';
import { listAllLanguages } from '../../language/registry';
import type { EngineHealth, TranslationEngine } from '../engine';

export interface EchoEngineOptions {
  /** Optional artificial delay, useful to exercise timeout/cancellation paths. */
  latencyMs?: number;
}

export class EchoEngine implements TranslationEngine {
  readonly id = 'echo';
  readonly name = 'Echo (offline test engine)';
  readonly limits = { maxCharsPerRequest: 100_000 };

  private readonly latencyMs: number;

  constructor(options: EchoEngineOptions = {}) {
    this.latencyMs = options.latencyMs ?? 0;
  }

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    if (this.latencyMs > 0) {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, this.latencyMs);
        request.signal?.addEventListener(
          'abort',
          () => {
            clearTimeout(timer);
            const abortError = new Error('aborted');
            abortError.name = 'AbortError';
            reject(abortError);
          },
          { once: true },
        );
      });
    }
    request.signal?.throwIfAborted();

    const source = request.sourceLanguage === 'auto' ? 'xx' : request.sourceLanguage;
    return {
      text: `[${source}->${request.targetLanguage}] ${request.text}`,
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
    return { engine: this.id, healthy: true, detail: 'offline test engine' };
  }
}