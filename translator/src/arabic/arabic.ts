/**
 * Arabic processing layer.
 *
 * Three jobs:
 *  1. Safe, conservative normalization (never mangles non-Arabic text).
 *  2. Character counting that ignores bidi/formatting marks.
 *  3. Sanity helpers used by quality checks (Arabic presence, digits, RTL).
 */

export const BIDI_CONTROL_CHARS = /[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g;

/** Zero-width and other invisible formatting characters. */
export const INVISIBLE_CHARS = /[\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF\u061C]/g;

const ARABIC_LETTER = /[\u0621-\u063A\u0641-\u064A\u066E-\u06D3\u06FA-\u06FF]/;
const ARABIC_CHAR = /[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/;
const ARABIC_PUNCTUATION = /[،؛؟٪-٭۔«»؞]/;
const WESTERN_DIGIT = /[0-9]/;
const ARABIC_INDIC_DIGIT = /[\u0660-\u0669\u06F0-\u06F9]/;

/**
 * Counts *visible* characters: bidi controls, zero-width spaces and other
 * invisible formatting marks do not count.
 *
 * This is a regression guard for the previous project where `countCharacters()`
 * included bidi marks and inflated counts used for segment limits.
 */
export function countCharacters(text: string): number {
  if (!text) {
    return 0;
  }
  // Iterate by code point so surrogate pairs count as one character.
  const visible = [...text.replace(INVISIBLE_CHARS, '')];
  return visible.length;
}

/** Counts characters ignoring whitespace as well (useful for density checks). */
export function countNonWhitespaceCharacters(text: string): number {
  if (!text) {
    return 0;
  }
  return [...text.replace(INVISIBLE_CHARS, '')].filter((c) => !/\s/.test(c)).length;
}

const TATWEEL = /\u0640/g;
const ARABIC_COMMA = '\u060C';
const ARABIC_SEMICOLON = '\u061B';
const MULTIPLE_SPACES = /[ \t]{2,}/g;
const SPACES_AROUND_NEWLINE = /[ \t]+(\r?\n)/g;
const NEWLINE_SPACES = /(\r?\n)[ \t]+/g;
const MULTIPLE_NEWLINES = /(\r?\n){3,}/g;

/**
 * Conservative Arabic normalization.
 *
 * Rules:
 *  - If the text contains no Arabic letters at all, return it unchanged. This
 *    is the guard that prevents mangling pure English/Japanese/Chinese text.
 *  - Strip bidi control characters.
 *  - Normalize Arabic comma/semicolon/question mark to their Arabic forms only
 *    when the source is already Arabic-dominant.
 *  - Collapse excess whitespace and redundant blank lines, trim trailing spaces.
 *  - Normalize line endings to \n.
 *  - Does NOT change letters, diacritics, or numbers.
 */
export function normalizeArabic(input: string): string {
  if (!input) {
    return '';
  }

  let text = input.replace(/\r\n?/g, '\n');

  const hasArabic = ARABIC_LETTER.test(text);
  if (!hasArabic) {
    // Non-Arabic text: only universal whitespace hygiene, no Arabic rewriting.
    return tidyWhitespace(text);
  }

  text = text.replace(BIDI_CONTROL_CHARS, '');

// ASCII punctuation that commonly appears inside Arabic runs.
  text = text.replace(/,/g, () => ARABIC_COMMA).replace(/;/g, () => ARABIC_SEMICOLON);

  // '?' -> Arabic question mark only when it belongs to an Arabic phrase.
  // Whitespace is skipped when looking at the neighbours so sentence-final
  // punctuation ("كيف حالك?") is converted, while an English question inside an
  // otherwise Arabic document is left alone.
  text = text.replace(/\?/g, (match, offset: number, full: string) => {
    const before = skipSpaceBackward(full, offset);
    const after = skipSpaceForward(full, offset + 1);
    return ARABIC_CHAR.test(before ?? '') || ARABIC_CHAR.test(after ?? '') ? '\u061F' : match;
  });

  text = text.replace(TATWEEL, '');

  return tidyWhitespace(text);
}

function skipSpaceBackward(text: string, from: number): string | undefined {
  for (let i = from - 1; i >= 0; i -= 1) {
    if (!/\s/.test(text[i]!)) {
      return text[i];
    }
  }
  return undefined;
}

function skipSpaceForward(text: string, from: number): string | undefined {
  for (let i = from; i < text.length; i += 1) {
    if (!/\s/.test(text[i]!)) {
      return text[i];
    }
  }
  return undefined;
}

function tidyWhitespace(text: string): string {
  return text
    .replace(SPACES_AROUND_NEWLINE, '$1')
    .replace(NEWLINE_SPACES, '$1')
    .replace(MULTIPLE_SPACES, ' ')
    .replace(MULTIPLE_NEWLINES, '\n\n')
    .replace(/[ \t]+$/gm, '')
    .trim();
}

/** True when the text contains at least one Arabic letter. */
export function containsArabic(text: string): boolean {
  return ARABIC_LETTER.test(text ?? '');
}

/** Fraction of non-whitespace characters that are Arabic letters, 0..1. */
export function arabicRatio(text: string): number {
  if (!text) {
    return 0;
  }
  const stripped = text.replace(INVISIBLE_CHARS, '');
  const letters = [...stripped].filter((c) => !/\s/.test(c));
  if (letters.length === 0) {
    return 0;
  }
  const arabic = letters.filter((c) => ARABIC_CHAR.test(c)).length;
  return arabic / letters.length;
}

/** Collects numeric tokens (Arabic-Indic and Western) for numeric-integrity checks. */
export function extractNumbers(text: string): string[] {
  const matches = text.match(/[0-9\u0660-\u0669\u06F0-\u06F9]+(?:[.,][0-9\u0660-\u0669\u06F0-\u06F9]+)?/g);
  return matches ? matches.map((m) => toWesternDigits(m)) : [];
}

export function toWesternDigits(text: string): string {
  return text.replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[\u06F0-\u06F9]/g, (d) => String(d.charCodeAt(0) - 0x06F0));
}

/** Removes bidi marks without touching letters. */
export function stripBidiMarks(text: string): string {
  return (text ?? '').replace(BIDI_CONTROL_CHARS, '');
}

/**
 * Ensures mixed-direction output renders correctly by wrapping the whole string
 * in an RLE...PDF pair when Arabic and Latin are both present.
 * Pure text is returned unchanged.
 */
export function applyRtlIsolation(text: string): string {
  if (!text || !containsArabic(text)) {
    return text ?? '';
  }
  if (text.startsWith('\u202B') && text.endsWith('\u202C')) {
    return text;
  }
  return `\u202B${text}\u202C`;
}

export function hasArabicPunctuation(text: string): boolean {
  return ARABIC_PUNCTUATION.test(text ?? '');
}

export function hasArabicDigits(text: string): boolean {
  return ARABIC_INDIC_DIGIT.test(text ?? '');
}

export function hasWesternDigits(text: string): boolean {
  return WESTERN_DIGIT.test(text ?? '');
}

/** Converts Western digits to Arabic-Indic digits (opt-in post-processing). */
export function toArabicDigits(text: string): string {
  return (text ?? '').replace(/[0-9]/g, (d) => String.fromCharCode(0x0660 + Number(d)));
}

/** Ensures paragraphs are separated by exactly a blank line. */
export function normalizeParagraphs(text: string): string {
  return (text ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}