/**
 * Model router (requirement 20).
 *
 * Picks an engine by tier: high / standard / fallback / byok. Independent of any
 * single engine — rules are data, so adding a model never touches this file.
 *
 * Tier selection is *not* language routing by default. ADR 0006 requires our own
 * eval set before per-language routes are justified, so `perLanguage` is
 * available but off until it is measured.
 */

import type { TranslationEngine } from '../../engine/engine';
import type { LanguageCode } from '../../core/types';

export type RouteTier = 'high' | 'standard' | 'fallback' | 'byok';

export interface RouteRule {
  tier: RouteTier;
  /** Source language, or '*'. */
  sourceLanguage: string;
  targetLanguage: string;
  engineId: string;
  priority: number;
  weight: number;
  enabled: boolean;
}

export interface RoutingDecision {
  engineId: string;
  tier: RouteTier;
  reason: 'tier_default' | 'rule' | 'byok' | 'fallback';
  /** Set when the primary was unavailable and a fallback was substituted. */
  substituted?: { engineId: string; reason: string };
}

export interface ModelRouterOptions {
  rules?: RouteRule[];
  /** Tier → engine id. The first available entry of a tier wins. */
  tierEngines?: Record<RouteTier, string[]>;
  isAvailable?: (engineId: string) => { available: boolean; reason?: string };
  /** Per-language routing stays off until our eval set justifies it. */
  perLanguage?: boolean;
}

export const DEFAULT_TIER_ENGINES: Record<RouteTier, string[]> = {
  high: ['local-tg12'],
  standard: ['local-tg4'],
  fallback: ['local-madlad3b', 'mymemory'],
  byok: [],
};

export class ModelRouter {
  private readonly rules: RouteRule[];
  private readonly tierEngines: Record<RouteTier, string[]>;
  private readonly isAvailable: (engineId: string) => { available: boolean; reason?: string };
  private readonly perLanguage: boolean;

  constructor(options: ModelRouterOptions = {}) {
    this.rules = (options.rules ?? []).filter((r) => r.enabled);
    this.tierEngines = options.tierEngines ?? DEFAULT_TIER_ENGINES;
    this.isAvailable = options.isAvailable ?? (() => ({ available: true }));
    this.perLanguage = options.perLanguage ?? false;
  }

  /**
   * Resolves the engine for a request.
   *
   * `quality` lets a caller ask for the high tier (chapter mode, paid plans)
   * without the router guessing.
   */
  resolve(input: {
    sourceLanguage: LanguageCode;
    targetLanguage: LanguageCode;
    quality?: 'high' | 'standard';
    byokEngineId?: string;
  }): RoutingDecision {
    const tier: RouteTier =
      input.byokEngineId && input.byokEngineId.length > 0
        ? 'byok'
        : input.quality === 'high'
          ? 'high'
          : 'standard';

    if (this.perLanguage && tier !== 'byok') {
      const rule = this.rules.find(
        (r) =>
          r.tier === tier &&
          (r.sourceLanguage === '*' || r.sourceLanguage === String(input.sourceLanguage)) &&
          (r.targetLanguage === '*' || r.targetLanguage === String(input.targetLanguage)),
      );
      if (rule && this.isAvailable(rule.engineId).available) {
        return { engineId: rule.engineId, tier, reason: 'rule' };
      }
    }

    if (tier === 'byok' && input.byokEngineId) {
      const status = this.isAvailable(input.byokEngineId);
      if (status.available) {
        return { engineId: input.byokEngineId, tier, reason: 'byok' };
      }
    }

    const chain = this.tierEngines[tier] ?? [];
    const attempted: Array<{ engineId: string; reason: string }> = [];

    for (const engineId of chain) {
      const status = this.isAvailable(engineId);
      if (status.available) {
        return {
          engineId,
          tier,
          reason: 'tier_default',
          ...(attempted.length > 0
            ? { substituted: { engineId: attempted[0]!.engineId, reason: attempted.map((a) => a.reason).join('; ') } }
            : {}),
        };
      }
      attempted.push({ engineId, reason: status.reason ?? 'unavailable' });
    }

    // The dedicated fallback tier comes first: a smaller fallback is the right
    // answer when the usual tier is unavailable, not a *bigger* model from a
    // higher tier that was never meant for bulk traffic.
    for (const engineId of this.tierEngines.fallback ?? []) {
      if (this.isAvailable(engineId).available) {
        return {
          engineId,
          tier: 'fallback',
          reason: 'fallback',
          substituted: {
            engineId: attempted[0]?.engineId ?? chain[0] ?? engineId,
            reason: attempted.map((a) => a.reason).join('; ') || 'primary tier empty',
          },
        };
      }
      attempted.push({ engineId, reason: this.isAvailable(engineId).reason ?? 'unavailable' });
    }

    // Last resort: any engine that answers. Returning nothing here would turn a
    // capacity problem into an unexplained 503.
    for (const [otherTier, engines] of Object.entries(this.tierEngines)) {
      if (otherTier === tier || otherTier === 'fallback') {
        continue;
      }
      for (const engineId of engines) {
        if (this.isAvailable(engineId).available) {
          return {
            engineId,
            tier: otherTier as RouteTier,
            reason: 'fallback',
            substituted: {
              engineId: attempted[0]?.engineId ?? chain[0] ?? engineId,
              reason: attempted.map((a) => a.reason).join('; ') || 'primary tier empty',
            },
          };
        }
      }
    }

    throw new NoEngineAvailableError(attempted);
  }

  /** Every candidate for a tier, for a canary percentage or an ops view. */
  candidates(tier: RouteTier): string[] {
    return [...(this.tierEngines[tier] ?? [])];
  }

  describe(): { tiers: Record<RouteTier, string[]>; perLanguage: boolean; rules: RouteRule[] } {
    return { tiers: this.tierEngines, perLanguage: this.perLanguage, rules: this.rules };
  }
}

export class NoEngineAvailableError extends Error {
  readonly code = 'NO_ENGINE_AVAILABLE';
  readonly status = 503;

  constructor(readonly attempts: Array<{ engineId: string; reason: string }>) {
    super(
      `no translation engine is available: ${
        attempts.map((a) => `${a.engineId} (${a.reason})`).join('; ') || 'no engines configured'
      }`,
    );
    this.name = 'NoEngineAvailableError';
  }
}

/** Availability probe over a registry of engines. */
export function availabilityFromRegistry(
  create: (engineId: string) => TranslationEngine,
  flags: { deepl?: boolean; mymemory?: boolean } = {},
): (engineId: string) => { available: boolean; reason?: string } {
  return (engineId: string) => {
    const providerFlags: Record<string, boolean | undefined> = {
      deepl: flags.deepl,
      mymemory: flags.mymemory,
    };
    if (engineId in providerFlags && providerFlags[engineId] === false) {
      return { available: false, reason: `${engineId} is disabled by feature flag` };
    }
    try {
      const engine = create(engineId);
      const probe = engine.configuration?.();
      if (probe && !probe.configured) {
        return { available: false, reason: probe.reason ?? 'not configured' };
      }
      return { available: true };
    } catch (error) {
      return { available: false, reason: error instanceof Error ? error.message : String(error) };
    }
  };
}