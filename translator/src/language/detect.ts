/**
 * Language detection.
 *
 * Design rule that matters most here: Japanese kana is a *decisive* signal.
 * Pure Han text must not be classified as Japanese simply because CJK code
 * points overlap in Unicode. The previous project got this wrong, so the
 * decision order is explicit and unit-tested per case.
 */

import type { LanguageCode, LanguageDetectionResult, LanguageEvidence } from '../core/types';

export const UNKNOWN_LANGUAGE = 'und';

const RE_HIRAGANA = /[\u3040-\u309F\u3041-\u3096\u309D-\u309F]/g;
const RE_KATAKANA = /[\u30A0-\u30FF\u31F0-\u31FF\uFF66-\uFF9D]/g;
const RE_HAN = /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u{20000}-\u{2A6DF}]/gu;
const RE_HANGUL = /[\uAC00-\uD7A3\u1100-\u11FF\u3130-\u318F]/g;
const RE_ARABIC = /[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/g;
const RE_CYRILLIC = /[\u0400-\u04FF\u0500-\u052F]/g;
const RE_LATIN = /[A-Za-z\u00C0-\u024F]/g;

/** Marks and formatting characters that must never influence detection. */
const RE_INVISIBLE =
  /[\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF\u061C]/g;

function count(text: string, re: RegExp): number {
  const matches = text.match(re);
  return matches ? matches.length : 0;
}

export function collectEvidence(input: string): LanguageEvidence {
  const text = input.replace(RE_INVISIBLE, '');
  return {
    arabicChars: count(text, RE_ARABIC),
    latinChars: count(text, RE_LATIN),
    hiragana: count(text, RE_HIRAGANA),
    katakana: count(text, RE_KATAKANA),
    han: count(text, RE_HAN),
    hangul: count(text, RE_HANGUL),
    cyrillic: count(text, RE_CYRILLIC),
    totalSignificant: count(text, /\S/g),
    kanaCount: count(text, RE_HIRAGANA) + count(text, RE_KATAKANA),
  };
}

export interface DetectionResult {
  language: LanguageCode;
  confidence: number;
  evidence: LanguageEvidence;
  alternatives: Array<{ language: LanguageCode; confidence: number }>;
}

function score(evidence: LanguageEvidence): Map<LanguageCode, number> {
  const scores = new Map<LanguageCode, number>();
  const add = (lang: LanguageCode, value: number): void => {
    scores.set(lang, (scores.get(lang) ?? 0) + value);
  };

  // Kana exists only in Japanese (or Okinawan/Ryukyuan, which we fold into ja).
  // Even one kana character outranks any amount of Han.
  const kana = evidence.hiragana + evidence.katakana;
  if (kana > 0) {
    add('ja', 3 + kana * 2);
  }

  if (evidence.hangul > 0) {
    add('ko', 3 + evidence.hangul * 2);
  }

  if (evidence.han > 0) {
    // With kana already counted for ja, Han alone leans Chinese.
    if (kana > 0) {
      add('zh', evidence.han * 0.4);
    } else {
      add('zh', 3 + evidence.han);
    }
  }

  if (evidence.arabicChars > 0) {
    add('ar', 3 + evidence.arabicChars);
  }

  if (evidence.cyrillic > 0) {
    add('ru', 3 + evidence.cyrillic);
  }

  if (evidence.latinChars > 0) {
    add('en', 3 + evidence.latinChars);
  }

  return scores;
}

/**
 * Detects the language of `input`.
 *
 * Returns 'und' with confidence 0 when there is no script signal at all
 * (empty string, digits only, symbols only).
 */
export function detectLanguageDetailed(input: string): DetectionResult {
  const evidence = collectEvidence(input ?? '');
  const scores = score(evidence);

  if (evidence.totalSignificant === 0 || scores.size === 0) {
    return {
      language: UNKNOWN_LANGUAGE,
      confidence: 0,
      evidence,
      alternatives: [],
    };
  }

  const ranked = [...scores.entries()]
    .map(([language, weight]) => ({ language, weight }))
    .sort((a, b) => b.weight - a.weight || a.language.localeCompare(b.language));

  const total = ranked.reduce((sum, item) => sum + item.weight, 0);
  const top = ranked[0]!;

  // Pure Han with no kana is a high-confidence Chinese call; keep it high so a
  // chapter of Chinese dialogue is not misrouted to a Japanese engine.
  let confidence = total > 0 ? top.weight / total : 0;
  if (top.language === 'zh' && evidence.hiragana === 0 && evidence.katakana === 0) {
    confidence = Math.max(confidence, 0.9);
  }

  const alternatives = ranked
    .slice(1, 4)
    .map((item) => ({ language: item.language, confidence: total > 0 ? item.weight / total : 0 }));

  return {
    language: top.language,
    confidence: Math.round(confidence * 1000) / 1000,
    evidence,
    alternatives,
  };
}

export function detectLanguage(input: string): LanguageCode {
  return detectLanguageDetailed(input).language;
}

export function toLanguageDetectionResult(input: string): LanguageDetectionResult {
  return detectLanguageDetailed(input);
}

/**
 * Resolves a source language for a translation request.
 * 'auto' (or a missing value) triggers detection.
 */
export function resolveSourceLanguage(
  requested: LanguageCode | 'auto' | undefined,
  text: string,
): { language: LanguageCode; detected?: LanguageCode; confidence: number } {
  if (requested && requested !== 'auto') {
    return { language: requested, confidence: 1 };
  }
  const detection = detectLanguageDetailed(text);
  if (detection.language === UNKNOWN_LANGUAGE) {
    return { language: 'en', confidence: 0 };
  }
  return { language: detection.language, detected: detection.language, confidence: detection.confidence };
}