/**
 * The write-side view of `HoldoutGuard`.
 *
 * Declared structurally and imported as a type only, so the knowledge, memory and
 * research modules can enforce the holdout without importing the eval module at
 * runtime (`holdout.ts` needs their normalizers, and a runtime cycle here would be
 * a trap for anyone refactoring later).
 */

export interface HoldoutWriteInput {
  term?: string;
  translation?: string;
  sourceText?: string;
  targetText?: string;
}

export interface HoldoutGuardLike {
  checkWrite(input: HoldoutWriteInput): { allowed: boolean; reason?: string; matchedItemId?: string };
  assertWrite(input: HoldoutWriteInput): void;
}
