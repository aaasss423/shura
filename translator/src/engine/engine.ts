/**
 * The engine boundary.
 *
 * This is the only place in the platform that knows a translation engine
 * exists as a swappable implementation. Service, chapter, REST and UI code
 * depend on these types exclusively, so adding a new engine never requires
 * touching any layer above it.
 */

import type {
  EngineLanguagePairSupport,
  EngineLimits,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../core/types';
import type { CancellationToken } from '../core/cancellation';

export interface TranslationEngine {
  /** Stable identifier used for configuration and cache keys. */
  readonly id: string;
  readonly name: string;
  /** Characters the engine accepts in one request; segmentation must respect it. */
  readonly limits: EngineLimits;
  /**
   * Translate one chunk.
   * Implementations MUST honour `request.signal` and `request.timeoutMs`, and
   * MUST NOT cache; caching belongs to the service layer.
   */
  translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse>;
  /** Languages this engine can accept as source. */
  getSourceLanguages(): LanguageInfo[];
  /** Languages this engine can produce. */
  getTargetLanguages(): LanguageInfo[];
  supportsPair(source: LanguageCode, target: LanguageCode): boolean;
  supportedPairs(): EngineLanguagePairSupport[];
  /** Optional readiness probe (used by health endpoints and smoke tests). */
  healthCheck?(token?: CancellationToken): Promise<EngineHealth>;
  /**
   * Optional credential probe.
   *
   * An engine that needs an API key reports `configured: false` with a reason.
   * The translator then routes around it instead of failing the request. It
   * must never include the credential value, only whether one is present.
   */
  configuration?(): { configured: boolean; reason?: string };
}

export interface EngineHealth {
  engine: string;
  healthy: boolean;
  detail?: string;
  latencyMs?: number;
}