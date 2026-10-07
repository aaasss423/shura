/**
 * Context and terminology.
 *
 * `TranslationContext` is the extensible object the whole pipeline reads. It is
 * deliberately optional-heavy: a bare `{ sourceLanguage, targetLanguage,
 * currentText }` is valid, and every extra field narrows behaviour rather than
 * being required.
 *
 * `analyzeContext` turns a request into what the pipeline needs: resolved
 * source language, glossary entries, character names, translation-memory hits,
 * knowledge hits, and the instructions to hand the model.
 *
 * The same phrase can legitimately mean different things by character, gender,
 * scene or series. That is why the lookup order is character → series → genre →
 * global, and why it is implemented once here rather than in each caller.
 */

import type { LanguageCode } from '../core/types';
import { detectLanguageDetailed } from '../language/detect';
import { normalizeLanguageCode } from '../language/registry';
import { countCharacters } from '../arabic/arabic';
import type { GlossaryEntry } from './memory/repository';
import { renderGlossaryInstructions } from './memory/repository';
import type { KnowledgeEntry } from './knowledge/repository';
import { normalizeTerm } from './knowledge/repository';

export interface CharacterRef {
  id: string;
  displayName: string;
  preferredArabicName?: string;
  honorific?: string;
  gender?: string;
  aliases?: string[];
}

export interface TranslationContext {
  sourceLanguage: LanguageCode | 'auto';
  targetLanguage: LanguageCode;
  currentText: string;
  previousText?: string;
  nextText?: string;
  seriesId?: string;
  chapterId?: string;
  characterId?: string;
  genre?: string;
  characters?: CharacterRef[];
  /** Present tense narrative vs dialogue matters to several decisions. */
  register?: 'dialogue' | 'narration' | 'sound_effect';
}

/**
 * Context-derived cache component (ADR 0009).
 *
 * Deliberately narrow by default: previous/next text are *not* included, because
 * including them would fragment the cache per position — the exact bug this
 * project already fixed once. Callers who want context-sensitive caching opt in.
 */
export function contextCacheKey(context: TranslationContext, includeWindow = false): string {
  const parts = [
    context.seriesId ?? '-',
    context.chapterId ?? '-',
    context.characterId ?? '-',
    context.genre ?? '-',
    context.register ?? '-',
  ];
  if (includeWindow) {
    parts.push(normalizeTerm(context.previousText ?? ''), normalizeTerm(context.nextText ?? ''));
  }
  return parts.join('|');
}

/** Window size for terminology retrieval, in characters on each side. */
const CONTEXT_WINDOW = 160;

export interface ContextAnalysisInput {
  context: TranslationContext;
  glossary: GlossaryEntry[];
  knowledge?: KnowledgeEntry[];
  memoryHit?: { targetText: string; confidence: number };
}

export interface ContextAnalysis {
  sourceLanguage: string;
  targetLanguage: string;
  detectedLanguage?: string;
  detectionConfidence: number;
  terms: string[];
  instructions: string;
  knowledgeHits: KnowledgeEntry[];
  memoryHit?: { targetText: string; confidence: number };
  /** True when the pipeline can answer without touching the model. */
  servedFromMemory: boolean;
}

/**
 * Builds model instructions from context and retrieved knowledge.
 *
 * Deterministic and inspectable: what went into the prompt is visible here and
 * in the logs, which matters when a translation is disputed.
 */
export function analyzeContext(input: ContextAnalysisInput): ContextAnalysis {
  const { context, glossary, knowledge = [] } = input;

  const targetLanguage = normalizeLanguageCode(context.targetLanguage) ?? String(context.targetLanguage);
  const requested = context.sourceLanguage;

  let sourceLanguage = normalizeLanguageCode(requested) ?? 'auto';
  let detectedLanguage: string | undefined;
  let detectionConfidence = 1;
  if (sourceLanguage === 'auto' || !sourceLanguage) {
    const detection = detectLanguageDetailed(context.currentText);
    sourceLanguage = detection.language === 'und' ? 'en' : detection.language;
    if (detection.language !== 'und') {
      detectedLanguage = detection.language;
      detectionConfidence = detection.confidence;
    } else {
      detectionConfidence = 0;
    }
  }

  const window = buildWindow(context);
  const terms = extractCandidateTerms(window, context.characters ?? []);

  const knowledgeLines = knowledge
    .filter((k) => k.confidence >= 0.5 && k.translation.trim().length > 0)
    .slice(0, 25)
    .map(
      (k) =>
        `- "${k.term}" (${k.category}): "${k.translation}"${k.meaning ? ` — ${k.meaning}` : ''}`,
    );

  const instructionParts: string[] = [];
  const glossaryInstructions = renderGlossaryInstructions(glossary);
  if (glossaryInstructions) {
    instructionParts.push(glossaryInstructions);
  }
  if (knowledgeLines.length > 0) {
    instructionParts.push(`Known terminology:\n${knowledgeLines.join('\n')}`);
  }
  const registerHint = buildRegisterHint(context);
  if (registerHint) {
    instructionParts.push(registerHint);
  }
  if (context.previousText) {
    instructionParts.push(`Previous line (context only, do not translate): ${context.previousText}`);
  }
  if (context.nextText) {
    instructionParts.push(`Next line (context only, do not translate): ${context.nextText}`);
  }

  return {
    sourceLanguage,
    targetLanguage,
    ...(detectedLanguage ? { detectedLanguage } : {}),
    detectionConfidence,
    terms,
    instructions: instructionParts.join('\n\n'),
    knowledgeHits: knowledge,
    ...(input.memoryHit ? { memoryHit: input.memoryHit } : {}),
    servedFromMemory: Boolean(input.memoryHit && input.memoryHit.confidence >= 0.9),
  };
}

/** Previous + current + next, bounded. */
export function buildWindow(context: TranslationContext): string {
  const before = context.previousText ? context.previousText.slice(-CONTEXT_WINDOW) : '';
  const after = context.nextText ? context.nextText.slice(0, CONTEXT_WINDOW) : '';
  return [before, context.currentText, after].filter(Boolean).join(' ');
}

/**
 * Candidate terms for retrieval.
 *
 * Character names and aliases are always candidates even when they are not in
 * the text, because a name introduced two panels earlier still needs enforcing.
 * Longer strings are preferred: 「魔王の 力」 beats 「魔王」.
 */
export function extractCandidateTerms(window: string, characters: CharacterRef[] = []): string[] {
  const candidates = new Set<string>();

  for (const character of characters) {
    if (character.displayName) {
      candidates.add(character.displayName);
    }
    for (const alias of character.aliases ?? []) {
      candidates.add(alias);
    }
    if (character.preferredArabicName) {
      candidates.add(character.preferredArabicName);
    }
  }

  for (const run of extractWordLikeRuns(window)) {
    if (run.length >= 2) {
      candidates.add(run);
    }
  }

  return [...candidates];
}

/**
 * Word-like runs across scripts.
 *
 * Latin words split on whitespace; CJK runs are extracted as contiguous
 * character sequences because Japanese and Chinese have no spaces, and taking
 * the whole run is what lets a compound term match.
 */
export function extractWordLikeRuns(text: string): string[] {
  const runs: string[] = [];
  const latin = text.match(/[\p{Script=Latin}\p{Script=Cyrillic}\p{Script=Arabic}][\p{Script=Latin}\p{Script=Cyrillic}\p{Script=Arabic}'’.-]*/gu);
  if (latin) {
    runs.push(...latin.filter((w) => w.length > 1));
  }
  const cjk = text.match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]+/gu);
  if (cjk) {
    runs.push(...cjk);
    for (const run of cjk) {
      // Japanese and Chinese terms are substrings of the run, not the run itself:
      // 「先輩は強い」 must yield 「先輩」 or the honorific is never looked up.
      // Single characters matter too (先, 輩, 魔), and 2-4 character windows cover
      // the overwhelming majority of real terms without a combinatorial blow-up.
      for (const char of run) {
        runs.push(char);
      }
      for (let size = 2; size <= 4; size += 1) {
        for (let start = 0; start + size <= run.length; start += 1) {
          runs.push(run.slice(start, start + size));
        }
      }
    }
  }
  return runs;
}

function buildRegisterHint(context: TranslationContext): string {
  switch (context.register) {
    case 'dialogue':
      return 'This is spoken dialogue: keep it informal and natural, as characters would say it.';
    case 'narration':
      return 'This is narration: keep the narrative register, do not turn it into dialogue.';
    case 'sound_effect':
      return 'This is a sound effect: translate it as an onomatopoeia, not as a sentence.';
    default:
      return '';
  }
}

/** Heuristic register detection, used when the caller does not say. */
export function detectRegister(text: string): 'dialogue' | 'narration' | 'sound_effect' {
  const trimmed = text.trim();
  // Katakana-only or very short katakana runs are conventionally sound effects.
  const katakanaOnly = /^[\p{Script=Katakana}ー・\s!~「」]+$/u.test(trimmed);
  if (katakanaOnly && trimmed.length <= 8) {
    return 'sound_effect';
  }
  if (/[「」『』"“”]/.test(trimmed) || trimmed.length <= 40) {
    return 'dialogue';
  }
  return 'narration';
}

/** True when the text is short enough that context materially changes it. */
export function isContextSensitive(context: TranslationContext): boolean {
  return countCharacters(context.currentText) <= 60 || Boolean(context.previousText || context.nextText);
}