/**
 * Segmentation and merge.
 *
 * Goals: preserve order, respect sentence and paragraph boundaries, respect
 * engine character limits, and never lose content. `mergeSegments()` falls back
 * to the original text for any segment whose translation is empty so a failed
 * or blank translation degrades to "untranslated" rather than "missing".
 */

import { countCharacters } from '../arabic/arabic';
import type { MergedText, Segment, SegmentedText } from '../core/types';

export interface SegmentationOptions {
  /** Hard limit per segment in visible characters. */
  maxChars: number;
  /** Never split a sentence below this size unless forced. */
  minChars?: number;
  /** Preserve paragraph breaks. Default true. */
  preserveParagraphs?: boolean;
  /** Maximum number of segments; the remainder is appended to the last one. */
  maxSegments?: number;
}

export const DEFAULT_SEGMENTATION: Required<SegmentationOptions> = {
  maxChars: 450,
  minChars: 40,
  preserveParagraphs: true,
  maxSegments: 400,
};

const SENTENCE_BOUNDARY =
  /(?<=[.!?。！？…؟।\u061F\uFF01\uFF1F])["'”’)\]\u00BB]*(?=\s|$)|\n{2,}/g;

interface Paragraph {
  text: string;
  trailing: string;
}

function splitParagraphs(input: string): Paragraph[] {
  const parts = input.split(/(\n{2,})/);
  const paragraphs: Paragraph[] = [];
  let buffer = '';

  for (let i = 0; i < parts.length; i += 2) {
    const content = parts[i] ?? '';
    const separator = parts[i + 1] ?? '';
    if (content.length > 0) {
      buffer += content;
    }
    if (separator.length > 0) {
      paragraphs.push({ text: buffer, trailing: separator });
      buffer = '';
    }
  }
  if (buffer.length > 0) {
    paragraphs.push({ text: buffer, trailing: '' });
  }
  if (paragraphs.length === 0 && input.length > 0) {
    paragraphs.push({ text: input, trailing: '' });
  }
  return paragraphs;
}

function splitSentences(paragraph: string): string[] {
  const sentences: string[] = [];
  let lastIndex = 0;
  SENTENCE_BOUNDARY.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = SENTENCE_BOUNDARY.exec(paragraph)) !== null) {
    const end = match.index + match[0].length;
    // Zero-length match would loop forever.
    if (end === lastIndex) {
      SENTENCE_BOUNDARY.lastIndex += 1;
      continue;
    }
    sentences.push(paragraph.slice(lastIndex, end));
    lastIndex = end;
  }
  if (lastIndex < paragraph.length) {
    sentences.push(paragraph.slice(lastIndex));
  }
  return sentences.filter((s) => s.length > 0);
}

/** Splits an over-long sentence into sentence-ish chunks on whitespace. */
function hardSplit(text: string, maxChars: number): string[] {
  if (countCharacters(text) <= maxChars) {
    return [text];
  }
  const words = text.split(/(\s+)/).filter((w) => w.length > 0);
  const chunks: string[] = [];
  let current = '';

  for (const word of words) {
    const candidate = current + word;
    if (current.length > 0 && countCharacters(candidate) > maxChars) {
      chunks.push(current);
      current = word.trimStart() === '' ? word : word;
    } else {
      current = candidate;
    }
  }
  if (current.length > 0) {
    chunks.push(current);
  }

  // A single "word" longer than the limit (e.g. long CJK run) needs slicing.
  const sliced: string[] = [];
  for (const chunk of chunks) {
    if (countCharacters(chunk) <= maxChars) {
      sliced.push(chunk);
      continue;
    }
    const chars = [...chunk];
    for (let i = 0; i < chars.length; i += maxChars) {
      sliced.push(chars.slice(i, i + maxChars).join(''));
    }
  }
  return sliced.length > 0 ? sliced : [text];
}

function joinWithLimit(parts: string[], maxChars: number): string[] {
  const out: string[] = [];
  let current = '';
  for (const part of parts) {
    const candidate = current + part;
    if (current.length > 0 && countCharacters(candidate) > maxChars) {
      out.push(current);
      current = part;
    } else {
      current = candidate;
    }
  }
  if (current.length > 0) {
    out.push(current);
  }
  return out;
}

/**
 * Splits `text` into engine-sized segments.
 *
 * A short text that fits the limit returns a single segment with `whole: true`.
 * Empty or whitespace-only input returns an empty segment list.
 */
export function segmentText(
  text: string,
  options: Partial<SegmentationOptions> = {},
): SegmentedText {
  const config = { ...DEFAULT_SEGMENTATION, ...options };

  if (!text || text.trim().length === 0) {
    return { segments: [], whole: false };
  }

  if (countCharacters(text) <= config.maxChars) {
    return {
      segments: [{ index: 0, text, trailing: '' }],
      whole: true,
    };
  }

  const paragraphs = config.preserveParagraphs
    ? splitParagraphs(text)
    : [{ text, trailing: '' }];

  const segments: Segment[] = [];

  for (let p = 0; p < paragraphs.length; p += 1) {
    const paragraph = paragraphs[p]!;
    const trailing = p < paragraphs.length - 1 ? paragraph.trailing : '';
    const pieces = config.preserveParagraphs
      ? splitSentences(paragraph.text)
      : [paragraph.text];

    let bounded: string[];
    if (pieces.length > 1) {
      bounded = joinWithLimit(pieces, config.maxChars);
    } else {
      bounded = hardSplit(pieces[0] ?? '', config.maxChars);
    }

    for (let i = 0; i < bounded.length; i += 1) {
      const piece = bounded[i]!;
      const isLastOfParagraph = i === bounded.length - 1;
      segments.push({
        index: segments.length,
        text: piece,
        trailing: isLastOfParagraph ? trailing : '',
      });
    }
  }

  let limited = segments;
  if (config.maxSegments && segments.length > config.maxSegments) {
    // Collapse the overflow into the final segment rather than dropping text.
    const head = segments.slice(0, config.maxSegments - 1);
    const tail = segments.slice(config.maxSegments - 1);
    head.push({
      index: head.length,
      text: tail.map((s) => s.text).join(''),
      trailing: tail[tail.length - 1]?.trailing ?? '',
      forced: true,
    });
    limited = head;
  }

  return { segments: limited, whole: false };
}

/**
 * Reassembles translated segment texts.
 *
 * `translations` is indexed by segment index. A missing or empty translation
 * falls back to the original segment text so no content is ever lost.
 */
export function mergeSegments(
  segments: Segment[],
  translations: ReadonlyMap<number, string> | Array<string | undefined | null>,
): MergedText {
  if (segments.length === 0) {
    return { text: '', missingSegments: [], originalSegments: [] };
  }

  const read = (index: number): string | undefined => {
    if (Array.isArray(translations)) {
      return translations[index] ?? undefined;
    }
    return translations.get(index);
  };

  const parts: string[] = [];
  const missingSegments: number[] = [];
  const originalSegments: number[] = [];

  for (const segment of segments) {
    const candidate = read(segment.index);
    const usable = typeof candidate === 'string' && candidate.trim().length > 0 ? candidate : undefined;
    if (usable === undefined) {
      missingSegments.push(segment.index);
      originalSegments.push(segment.index);
      parts.push(segment.text);
    } else {
      parts.push(usable);
    }
    if (segment.trailing) {
      parts.push(segment.trailing);
    }
  }

  return { text: parts.join(''), missingSegments, originalSegments };
}

/** Convenience: segment then translate-merge a plain array of segment texts. */
export function mergeWithFallback(segments: Segment[], translations: ReadonlyMap<number, string>): MergedText {
  return mergeSegments(segments, translations);
}