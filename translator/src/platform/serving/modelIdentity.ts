/**
 * Model identity.
 *
 * ADR 0009 puts `modelId` and `modelVersion` in the cache key so a model swap
 * invalidates instead of silently serving stale translations. That only works if
 * the identity is *recorded* and *comparable*, which is what this module owns.
 *
 * Two properties matter:
 *
 *  - an unpinned revision is visible as unpinned, not silently equal to "latest";
 *  - two servers serving different weights never claim the same identity, so a
 *    benchmark cannot mix results from a 4B and a 12B run.
 */

import { createHash } from 'node:crypto';

import { ConfigError } from '../../core/errors';
import { normalizeServedModel } from './httpClient';
import type { ModelSpec, ServingStyle } from './modelCatalog';

export const UNPINNED = 'UNPINNED';

export interface ModelIdentity {
  /** Catalog id, e.g. `local-tg4`. */
  engineId: string;
  /** Upstream model name as the server knows it. */
  modelId: string;
  /**
   * Content identity of the weights. A commit hash, a file digest, or `UNPINNED`.
   * `UNPINNED` is legal but makes a comparison unsafe; `isPinned` reports it.
   */
  revision: string;
  quantization: string;
  servingStyle: ServingStyle;
  /** Server-reported model name, when the server tells us. */
  servedAs?: string;
}

export function isPinned(identity: ModelIdentity): boolean {
  return identity.revision !== UNPINNED && identity.revision.length > 0;
}

export interface IdentityWarning {
  level: 'error' | 'warning';
  message: string;
}

/**
 * Whether a server-reported model name satisfies a request.
 *
 * Servers report a *file*: `madlad400-3b-mt-GGUF.gguf`, or a repo id:
 * `translategemma-4b-it-qat`. Those carry suffix markers the request does not, so
 * an exact comparison rejects the correct model and would send a real deployment
 * into a false "serving the wrong model" state.
 *
 * Matching is asymmetric on purpose: the served name may extend the requested one
 * (same model, extra build markers) but must never be *shorter* or share only a
 * partial token. That asymmetry is what keeps `translategemma-4b` from matching
 * `translategemma-12b`, which is exactly the confusion that would corrupt a
 * benchmark.
 */
export function servedMatches(requested: string, served: string): boolean {
  const want = normalizeServedModel(requested);
  const got = normalizeServedModel(served);
  if (want === got) {
    return true;
  }
  if (got.length <= want.length) {
    return false;
  }
  if (!got.startsWith(want)) {
    return false;
  }
  // Only on a segment boundary, so `translategemma-4` never matches `…-4b`.
  return got[want.length] === '-' || got[want.length] === '.' || got[want.length] === '_';
}

/**
 * Checks an identity before it is trusted for comparison or caching.
 *
 * A mismatch between what we asked for and what the server reports is an error,
 * not a warning: it means the benchmark would attribute results to the wrong
 * model, which is the exact failure this project cannot afford.
 */
export function checkIdentity(
  expected: ModelIdentity,
  actual: Partial<ModelIdentity> | undefined,
): IdentityWarning[] {
  const warnings: IdentityWarning[] = [];
  if (!actual || !actual.servedAs) {
    return warnings;
  }
  if (!servedMatches(expected.modelId, actual.servedAs)) {
    warnings.push({
      level: 'error',
      message: `server is serving "${actual.servedAs}" but "${expected.modelId}" was requested`,
    });
  }
  if (!isPinned(expected)) {
    warnings.push({
      level: 'warning',
      message:
        `model revision for "${expected.modelId}" is UNPINNED: results cannot be reproduced and ` +
        'the cache key cannot distinguish two different builds',
    });
  }
  return warnings;
}

/** Enforces `checkIdentity`, so a mismatched server cannot be benchmarked. */
export function assertIdentity(
  expected: ModelIdentity,
  actual: Partial<ModelIdentity> | undefined,
): void {
  const errors = checkIdentity(expected, actual).filter((w) => w.level === 'error');
  if (errors.length > 0) {
    throw new ConfigError(errors.map((e) => e.message).join('; '));
  }
}

/**
 * The cache-key and report fragment for this identity.
 *
 * Stable for identical weights, and different for different weights: that is the
 * whole contract. `UNPINNED` is included verbatim so two unpinned runs of the
 * same model do not collide with a pinned run.
 */
export function identityKey(identity: ModelIdentity): string {
  const raw = [
    identity.engineId,
    identity.modelId,
    identity.revision,
    identity.quantization,
  ].join('|');
  return `${identity.modelId}@${identity.revision}#${createHash('sha256').update(raw).digest('hex').slice(0, 12)}`;
}

/** Human-readable one-liner for logs and report headers. */
export function describeIdentity(identity: ModelIdentity): string {
  const pin = isPinned(identity) ? identity.revision : `${UNPINNED} (results not reproducible)`;
  return `${identity.modelId} rev=${pin} quant=${identity.quantization} style=${identity.servingStyle}`;
}

export function identityFromSpec(
  spec: ModelSpec,
  overrides: { revision?: string; servingStyle?: ServingStyle; quantization?: string } = {},
): ModelIdentity {
  return {
    engineId: spec.id,
    modelId: spec.modelId,
    revision: overrides.revision ?? spec.revision,
    quantization: overrides.quantization ?? spec.quantization,
    servingStyle: overrides.servingStyle ?? spec.defaultStyle,
  };
}
