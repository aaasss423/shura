/**
 * Holdout enforcement (ADR 0006, and the leakage rule the corpus manifest
 * promises).
 *
 * The test split is the only thing standing between a benchmark and a
 * self-reinforcing system: if evaluation items can reach the knowledge base or
 * the translation memory, every future run scores higher without the model
 * improving. The guard is therefore enforced *at the writers*, not by
 * convention.
 *
 * `HoldoutGuard` is passed to the repositories that can create knowledge. It
 * blocks writes whose text matches a test item, in either direction: the source
 * text or the proposed Arabic translation.
 */

import { normalizeForMemory } from '../memory/repository';
import { normalizeTerm } from '../knowledge/repository';
import type { EvalDataset } from './dataset';
import { filterDataset, provenanceOf } from './dataset';

export interface HoldoutDecision {
  allowed: boolean;
  reason?: string;
  matchedItemId?: string;
}

export class HoldoutLeakError extends Error {
  readonly code = 'HOLDOUT_LEAKAGE';
  readonly status = 409;

  constructor(
    readonly itemId: string,
    detail: string,
  ) {
    super(`refusing to write evaluation holdout content: ${detail} (item ${itemId})`);
    this.name = 'HoldoutLeakError';
  }
}

export class HoldoutGuard {
  /** Normalized source texts of every test item. */
  private readonly sourceKeys = new Map<string, string>();
  /** Normalized Arabic references of every test item. */
  private readonly targetKeys = new Map<string, string>();
  /** Item ids, so the dataset can be extended at runtime. */
  private itemIds = new Set<string>();

  constructor(dataset: EvalDataset) {
    this.extend(dataset);
  }

  /** Adds items to the protected set (for datasets built in pieces). */
  extend(dataset: EvalDataset): void {
    for (const item of filterDataset(dataset, { split: 'test' })) {
      this.itemIds.add(item.id);
      this.sourceKeys.set(normalizeForMemory(item.sourceText), item.id);
      this.targetKeys.set(normalizeTerm(item.referenceArabic), item.id);
    }
  }

  /** True when the item id is protected. */
  isProtectedItem(itemId: string): boolean {
    return this.itemIds.has(itemId);
  }

  /** Ids currently protected. */
  protectedIds(): string[] {
    return [...this.itemIds];
  }

  /**
   * Checks a proposed knowledge write.
   *
   * Both the term and the proposed translation are checked: publishing the
   * reference Arabic as a knowledge entry is the easiest way to leak, because
   * the term alone looks innocuous.
   */
  checkWrite(input: {
    term?: string;
    translation?: string;
    sourceText?: string;
    targetText?: string;
  }): HoldoutDecision {
    const term = input.term ?? input.sourceText;
    const translation = input.translation ?? input.targetText;

    if (term) {
      const bySource = this.sourceKeys.get(normalizeForMemory(term));
      if (bySource) {
        return {
          allowed: false,
          reason: 'source text is an evaluation holdout item',
          matchedItemId: bySource,
        };
      }
    }
    if (translation) {
      const byTarget = this.targetKeys.get(normalizeTerm(translation));
      if (byTarget) {
        return {
          allowed: false,
          reason: 'translation matches an evaluation holdout reference',
          matchedItemId: byTarget,
        };
      }
    }
    return { allowed: true };
  }

  /** Throws instead of returning a decision. */
  assertWrite(input: {
    term?: string;
    translation?: string;
    sourceText?: string;
    targetText?: string;
  }): void {
    const decision = this.checkWrite(input);
    if (!decision.allowed) {
      throw new HoldoutLeakError(decision.matchedItemId ?? 'unknown', decision.reason ?? 'blocked');
    }
  }

  /** How many items a dataset export must exclude. */
  exportStats(dataset: EvalDataset): { exported: number; excluded: number; excludedIds: string[] } {
    const exported: string[] = [];
    const excludedIds: string[] = [];
    for (const item of dataset.items) {
      if (this.isProtectedItem(item.id)) {
        excludedIds.push(item.id);
      } else {
        exported.push(item.id);
      }
    }
    return { exported: exported.length, excluded: excludedIds.length, excludedIds };
  }
}

/**
 * Wraps a dataset for training or knowledge seeding.
 *
 * `allowHoldout` exists so a *human* can deliberately release specific items after
 * review — but it defaults to false, and it is recorded so the export can say
 * what happened.
 */
export function exportForTraining(
  dataset: EvalDataset,
  options: { allowHoldout?: boolean } = {},
): { items: Array<{ id: string; sourceText: string; referenceArabic: string; provenance: string }>; excludedHoldout: number; allowHoldout: boolean } {
  const allowHoldout = options.allowHoldout === true;
  const excludedHoldout = filterDataset(dataset, { split: 'test' }).length;
  const usable = allowHoldout ? dataset.items : filterDataset(dataset, { split: 'dev' });
  return {
    items: usable.map((item) => ({
      id: item.id,
      sourceText: item.sourceText,
      referenceArabic: item.referenceArabic,
      provenance: provenanceOf(item),
    })),
    excludedHoldout,
    allowHoldout,
  };
}