/**
 * Translation quality checks.
 *
 * These are safety checks, not a quality oracle: they catch mechanical damage
 * (empty output, untranslated source, repetition, mixed scripts, truncation,
 * lost numbers) so a bad engine response is reported instead of silently
 * shipped to the reader.
 */

import { arabicRatio, containsArabic, countCharacters, extractNumbers, stripBidiMarks } from '../arabic/arabic';
import type { LanguageCode, QualityIssue, QualityReport } from '../core/types';

export interface QualityCheckInput {
  source: string;
  translated: string;
  targetLanguage: LanguageCode;
  segmentIndex?: number;
  /** Engine-reported confidence, when available. */
  confidence?: number;
  /** Engines with weak CJK->Arabic support flag this so issues are warnings. */
  degradedEngine?: boolean;
}

function repeatRatio(text: string): number {
  const trimmed = text.trim();
  if (trimmed.length < 12) {
    return 0;
  }
  // Detect an immediately repeated block of >= 8 characters.
  for (let size = 8; size <= Math.floor(trimmed.length / 2); size += 1) {
    const head = trimmed.slice(0, size);
    let repeated = 1;
    while (trimmed.startsWith(head, size * repeated) && repeated < 12) {
      repeated += 1;
    }
    if (repeated >= 3) {
      return repeated / Math.ceil(trimmed.length / size);
    }
  }
  return 0;
}

function sourceScriptRatio(source: string, language: LanguageCode): number {
  if (language === 'ar') {
    return arabicRatio(source);
  }
  if (language === 'ja') {
    const kana = (source.match(/[\u3040-\u309F\u30A0-\u30FF]/g) ?? []).length;
    return kana / Math.max(1, countCharacters(source));
  }
  return 0;
}

export function checkQuality(input: QualityCheckInput): QualityReport {
  const issues: QualityIssue[] = [];
  const source = input.source ?? '';
  const translated = stripBidiMarks(input.translated ?? '');
  const targetLength = countCharacters(translated);

  const push = (issue: QualityIssue): void => {
    issues.push(input.segmentIndex === undefined ? issue : { ...issue, segmentIndex: input.segmentIndex });
  };

  if (targetLength === 0) {
    push({ kind: 'empty_translation', severity: 'error', message: 'engine returned empty text' });
    return { ok: false, score: 0, issues };
  }

  if (translated.trim() === source.trim()) {
    push({
      kind: 'untranslated',
      severity: 'warning',
      message: 'translated text is identical to the source text',
    });
  }

  const repetition = repeatRatio(translated);
  if (repetition >= 0.5) {
    push({
      kind: 'repetition',
      severity: 'error',
      message: `translation repeats the same block (ratio ${repetition.toFixed(2)})`,
    });
  }

  // Script mismatch: Arabic target with no Arabic letters at all.
  if (input.targetLanguage === 'ar' && !containsArabic(translated)) {
    const looksLikeLatinEcho = /^[A-Za-z0-9\s.,!?'"-]+$/.test(translated);
    push({
      kind: 'source_target_mixed',
      severity: looksLikeLatinEcho ? 'error' : 'warning',
      message: 'target language is Arabic but output contains no Arabic letters',
    });
  }

  // Non-Arabic target that still contains CJK/Hangul-heavy source text means
  // the source was returned untouched.
  const sourceRatio = sourceScriptRatio(source, sourceLanguageHint(source));
  if (sourceRatio > 0.5 && containsArabic(translated) === false && /[\u3040-\u30FF\u4E00-\u9FFF\uAC00-\uD7A3]/.test(translated)) {
    push({
      kind: 'script_mismatch',
      severity: 'error',
      message: 'output still contains source script characters',
    });
  }

  // Truncation heuristic: target far shorter than source and no repeated block.
  const sourceLength = countCharacters(stripBidiMarks(source));
  if (sourceLength >= 40 && targetLength < sourceLength * 0.25 && repetition === 0) {
    push({
      kind: 'truncated',
      severity: 'warning',
      message: `translation is much shorter than source (${targetLength} vs ${sourceLength})`,
    });
  }

  // Numeric integrity: every number in the source should survive.
  const sourceNumbers = extractNumbers(source);
  const translatedNumbers = new Set(extractNumbers(translated));
  const missing = sourceNumbers.filter((n) => !translatedNumbers.has(n));
  if (missing.length > 0 && sourceNumbers.length > 0) {
    push({
      kind: 'numeric_loss',
      severity: 'warning',
      message: `numbers missing from translation: ${missing.join(', ')}`,
    });
  }

  const errors = issues.filter((i) => i.severity === 'error').length;
  const warnings = issues.length - errors;
  let score = 1 - errors * 0.5 - warnings * 0.1;
  if (input.confidence !== undefined) {
    score = Math.min(score, Math.max(0, input.confidence));
  }
  score = Math.max(0, Math.round(score * 100) / 100);

  return { ok: errors === 0, score, issues };
}

function sourceLanguageHint(source: string): LanguageCode {
  if (/[\u3040-\u30FF]/.test(source)) {
    return 'ja';
  }
  if (containsArabic(source)) {
    return 'ar';
  }
  return 'en';
}

/** Aggregates per-segment reports into a single chapter-level report. */
export function aggregateQuality(reports: Array<QualityReport | undefined>): QualityReport {
  const issues: QualityIssue[] = [];
  let total = 0;
  let count = 0;
  for (const report of reports) {
    if (!report) {
      continue;
    }
    issues.push(...report.issues);
    total += report.score;
    count += 1;
  }
  if (count === 0) {
    return { ok: true, score: 1, issues: [] };
  }
  const score = Math.round((total / count) * 100) / 100;
  return { ok: issues.every((i) => i.severity !== 'error'), score, issues };
}