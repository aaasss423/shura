/**
 * Engine routing and fallback.
 *
 * Purpose: pick which engine handles a request, and decide what happens when
 * that engine is unusable. Two independent mechanisms:
 *
 *  - **Routing** maps a source language to a preferred engine (for example
 *    CJK -> DeepL, everything else -> MyMemory).
 *  - **Fallback** provides an ordered list of alternative engines used when the
 *    chosen one fails at the engine level.
 *
 * Both live above `src/engine/engine.ts` and only ever deal in engine ids, so
 * no layer above this file needs to know what an engine actually is.
 *
 * Cache safety: the resolved engine id becomes part of the cache key, and a
 * fallback attempt re-enters the service with the fallback id, so results are
 * always stored under the engine that produced them. A failed attempt stores
 * nothing.
 */

import type { LanguageCode } from '../core/types';
import { isTranslationError } from '../core/errors';

export interface EngineRouteRule {
  /** Source language, or '*' for any. */
  source: LanguageCode | '*';
  engine: string;
}

export interface RoutingDecision {
  engine: string;
  /** Why this engine was chosen; surfaced for diagnostics. */
  reason: 'explicit' | 'default' | 'route';
  /** Engines to try in order if the chosen one fails. */
  fallbacks: string[];
}

export interface EngineAvailability {
  (engineId: string): { available: boolean; reason?: string };
}

export interface EngineRouterOptions {
  defaultEngineId: string;
  rules?: EngineRouteRule[];
  fallbackIds?: string[];
  /** Lets routing skip an engine that is registered but not usable. */
  isAvailable?: EngineAvailability;
  /** Consecutive failures before an engine is temporarily skipped. */
  failureThreshold?: number;
  /** How long a failing engine is skipped, in ms. 0 disables the breaker. */
  cooldownMs?: number;
  now?: () => number;
}

/** Failure codes that justify switching to another engine. */
const FALLBACK_ELIGIBLE = new Set([
  'ENGINE_UNAVAILABLE',
  'ENGINE_ERROR',
  'RATE_LIMITED',
  'QUOTA_EXCEEDED',
  'TIMEOUT',
  'INTERNAL_ERROR',
  'CONFIG_ERROR',
  'UNSUPPORTED_PAIR',
]);

export function isFallbackEligible(error: unknown): boolean {
  if (!isTranslationError(error)) {
    return false;
  }
  // Never fall back for caller mistakes or for cancellation: those would fail
  // identically on every engine.
  if (error.code === 'VALIDATION_ERROR' || error.code === 'CANCELLED' || error.code === 'DEADLINE_EXCEEDED') {
    return false;
  }
  return FALLBACK_ELIGIBLE.has(error.code);
}

export class EngineRouter {
  private readonly defaultEngineId: string;
  private readonly rules: EngineRouteRule[];
  private readonly fallbackIds: string[];
  private readonly isAvailable: EngineAvailability;
  private readonly failureThreshold: number;
  private readonly cooldownMs: number;
  private readonly now: () => number;
  /** engine id -> consecutive failures and the time the breaker reopens. */
  private readonly failures = new Map<string, { count: number; until: number }>();

  constructor(options: EngineRouterOptions) {
    this.defaultEngineId = options.defaultEngineId;
    // More specific rules first: an explicit language beats '*'.
    this.rules = [...(options.rules ?? [])].sort((a, b) => {
      if (a.source === b.source) {
        return 0;
      }
      if (a.source === '*') {
        return 1;
      }
      if (b.source === '*') {
        return -1;
      }
      return 0;
    });
    this.fallbackIds = (options.fallbackIds ?? []).filter((id) => id !== options.defaultEngineId);
    this.isAvailable = options.isAvailable ?? (() => ({ available: true }));
    this.failureThreshold = Math.max(1, options.failureThreshold ?? 2);
    this.cooldownMs = Math.max(0, options.cooldownMs ?? 60_000);
    this.now = options.now ?? Date.now;
  }

  /**
   * Records a failed engine call.
   *
   * After `failureThreshold` consecutive failures the engine is skipped for
   * `cooldownMs`. Without this, a request would keep paying the timeout and
   * rate-limit cost of a dead engine (or an exhausted quota) on every call,
   * even though the fallback answers immediately.
   */
  noteFailure(engineId: string): void {
    if (this.cooldownMs === 0) {
      return;
    }
    const previous = this.failures.get(engineId);
    const count = (previous?.count ?? 0) + 1;
    this.failures.set(engineId, {
      count,
      until: count >= this.failureThreshold ? this.now() + this.cooldownMs : 0,
    });
  }

  /** Records a successful call, closing the breaker. */
  noteSuccess(engineId: string): void {
    this.failures.delete(engineId);
  }

  /** True while the breaker is open for this engine. */
  isCoolingDown(engineId: string): boolean {
    if (this.cooldownMs === 0) {
      return false;
    }
    const entry = this.failures.get(engineId);
    if (!entry) {
      return false;
    }
    if (entry.until > 0 && this.now() >= entry.until) {
      // Cooldown elapsed: let the engine be tried again.
      this.failures.delete(engineId);
      return false;
    }
    return entry.until > 0;
  }

  /** Breaker state for diagnostics. */
  describeBreakers(): Array<{ engine: string; failures: number; coolingDown: boolean }> {
    return [...this.failures.entries()].map(([engine, entry]) => ({
      engine,
      failures: entry.count,
      coolingDown: this.isCoolingDown(engine),
    }));
  }

  /**
   * Resolves the engine for a request.
   *
   * An explicit `requestedEngine` always wins: a caller that names an engine
   * gets that engine, not a routed one.
   */
  resolve(sourceLanguage: LanguageCode, requestedEngine?: string): RoutingDecision {
    const fallbacks = this.fallbackIds;

    if (requestedEngine) {
      return { engine: requestedEngine, reason: 'explicit', fallbacks };
    }

    const matched = this.rules.find(
      (rule) => rule.source === '*' || rule.source === sourceLanguage,
    );
    if (matched && matched.engine !== this.defaultEngineId) {
      // A routed engine always gets the default engine as a last-resort
      // fallback, so routing can never leave a request with no way out.
      return {
        engine: matched.engine,
        reason: 'route',
        fallbacks: fallbacks.includes(this.defaultEngineId)
          ? fallbacks
          : [...fallbacks, this.defaultEngineId],
      };
    }

    return { engine: this.defaultEngineId, reason: 'default', fallbacks };
  }

  /**
   * Returns the engine to use, skipping configured-but-unusable engines.
   *
   * Example: the default is DeepL but DEEPL_API_KEY is absent, and MyMemory is
   * registered as a fallback. The decision becomes MyMemory instead of failing
   * with a configuration error.
   */
  resolveAvailable(
    sourceLanguage: LanguageCode,
    requestedEngine?: string,
  ): { decision: RoutingDecision; skipped: Array<{ engine: string; reason: string }> } {
    const decision = this.resolve(sourceLanguage, requestedEngine);
    const skipped: Array<{ engine: string; reason: string }> = [];

    const candidates = [decision.engine, ...decision.fallbacks];
    const available: string[] = [];
    for (const candidate of candidates) {
      if (this.isCoolingDown(candidate)) {
        skipped.push({
          engine: candidate,
          reason: `temporarily skipped after ${this.failureThreshold} consecutive failures`,
        });
        continue;
      }
      const status = this.isAvailable(candidate);
      if (status.available) {
        available.push(candidate);
      } else {
        skipped.push({ engine: candidate, reason: status.reason ?? 'unavailable' });
      }
    }

    if (available.length > 0) {
      // Only engines known to be usable are offered as fallbacks, so a caller
      // never burns a request against an engine that is known to be down.
      return {
        decision: {
          engine: available[0]!,
          reason: available[0] === decision.engine ? decision.reason : 'route',
          fallbacks: available.slice(1),
        },
        skipped,
      };
    }

    // Nothing is usable. The caller reports the primary engine and the reasons.
    return { decision, skipped };
  }

  /** Rules and fallbacks, for the REST layer and CLI. Never includes secrets. */
  describe(): { default: string; rules: EngineRouteRule[]; fallbacks: string[] } {
    return { default: this.defaultEngineId, rules: this.rules, fallbacks: this.fallbackIds };
  }
}

/**
 * Parses `TRANSLATION_ENGINE_ROUTES`, e.g. `ja=deepl,zh=deepl,ko=deepl,en=mymemory`.
 * Invalid entries throw a ConfigError with the offending text.
 */
export function parseRoutes(raw: string): EngineRouteRule[] {
  if (!raw.trim()) {
    return [];
  }
  const rules: EngineRouteRule[] = [];
  for (const entry of raw.split(',')) {
    const trimmed = entry.trim();
    if (trimmed.length === 0) {
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq === -1) {
      throw new Error(
        `TRANSLATION_ENGINE_ROUTES entry "${trimmed}" must be in the form source=engine (for example ja=deepl)`,
      );
    }
    const source = trimmed.slice(0, eq).trim().toLowerCase();
    const engine = trimmed.slice(eq + 1).trim();
    if (source.length === 0 || engine.length === 0) {
      throw new Error(`TRANSLATION_ENGINE_ROUTES entry "${trimmed}" has an empty source or engine`);
    }
    rules.push({ source, engine });
  }
  return rules;
}

/** Parses `TRANSLATION_ENGINE_FALLBACKS`, e.g. `mymemory` or `mymemory,echo`. */
export function parseFallbacks(raw: string): string[] {
  return raw
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id.length > 0);
}