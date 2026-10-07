/**
 * Request validation. Runs before any engine or cache work so invalid requests
 * fail fast with a clear, non-retryable error.
 */

import { EmptyInputError, ValidationError } from './errors';
import { countCharacters } from '../arabic/arabic';
import { normalizeLanguageCode } from '../language/registry';
import { AUTO_LANGUAGE } from './types';

export interface ValidateTranslateInput {
  text: unknown;
  sourceLanguage?: unknown;
  targetLanguage: unknown;
}

export interface ValidatedTranslateInput {
  text: string;
  sourceLanguage: string;
  targetLanguage: string;
}

export function validateTranslateInput(input: ValidateTranslateInput): ValidatedTranslateInput {
  if (typeof input.text !== 'string') {
    throw new ValidationError('text must be a string', { received: typeof input.text });
  }
  if (input.text.trim().length === 0) {
    throw new EmptyInputError();
  }

  const sourceRaw = input.sourceLanguage ?? AUTO_LANGUAGE;
  if (typeof sourceRaw !== 'string') {
    throw new ValidationError('sourceLanguage must be a string');
  }
  const source = normalizeLanguageCode(sourceRaw) ?? AUTO_LANGUAGE;
  if (source !== AUTO_LANGUAGE && !normalizeLanguageCode(source)) {
    throw new ValidationError(`unknown source language: ${sourceRaw}`, { sourceLanguage: sourceRaw });
  }

  if (typeof input.targetLanguage !== 'string' || input.targetLanguage.trim() === '') {
    throw new ValidationError('targetLanguage is required');
  }
  const target = normalizeLanguageCode(input.targetLanguage);
  if (!target || target === AUTO_LANGUAGE) {
    throw new ValidationError(`target language must be a concrete language, received "${input.targetLanguage}"`, {
      targetLanguage: input.targetLanguage,
    });
  }
  if (target === source) {
    throw new ValidationError('sourceLanguage and targetLanguage must differ');
  }

  return { text: input.text, sourceLanguage: source, targetLanguage: target };
}

export interface ValidateDetectInput {
  text: unknown;
}

export function validateDetectInput(input: ValidateDetectInput): string {
  if (typeof input.text !== 'string') {
    throw new ValidationError('text must be a string', { received: typeof input.text });
  }
  if (input.text.trim().length === 0) {
    throw new EmptyInputError('text must contain at least one non-whitespace character');
  }
  return input.text;
}

export interface ValidateChapterInput {
  segments: unknown;
  sourceLanguage?: unknown;
  targetLanguage: unknown;
}

export interface ChapterSegmentInput {
  id?: string;
  text: string;
  speaker?: string;
}

export interface ValidatedChapterInput {
  segments: ChapterSegmentInput[];
  sourceLanguage: string;
  targetLanguage: string;
}

export function validateChapterInput(input: ValidateChapterInput): ValidatedChapterInput {
  if (!Array.isArray(input.segments)) {
    throw new ValidationError('segments must be an array of { text } objects');
  }
  if (input.segments.length === 0) {
    throw new ValidationError('segments must contain at least one item');
  }
  if (input.segments.length > 5000) {
    throw new ValidationError('segments array is too large (max 5000)', { count: input.segments.length });
  }

  const segments: ChapterSegmentInput[] = input.segments.map((raw, index) => {
    if (typeof raw === 'string') {
      if (raw.trim().length === 0) {
        throw new ValidationError(`segments[${index}] is empty`, { index });
      }
      return { text: raw };
    }
    if (!raw || typeof raw !== 'object') {
      throw new ValidationError(`segments[${index}] must be an object or string`, { index });
    }
    const item = raw as Record<string, unknown>;
    if (typeof item.text !== 'string' || item.text.trim().length === 0) {
      throw new ValidationError(`segments[${index}].text must be a non-empty string`, { index });
    }
    if (item.id !== undefined && typeof item.id !== 'string') {
      throw new ValidationError(`segments[${index}].id must be a string`, { index });
    }
    if (item.speaker !== undefined && typeof item.speaker !== 'string') {
      throw new ValidationError(`segments[${index}].speaker must be a string`, { index });
    }
    const segment: ChapterSegmentInput = { text: item.text };
    if (typeof item.id === 'string') {
      segment.id = item.id;
    }
    if (typeof item.speaker === 'string') {
      segment.speaker = item.speaker;
    }
    return segment;
  });

  const base = validateTranslateInput({
    text: segments[0]!.text,
    sourceLanguage: input.sourceLanguage ?? AUTO_LANGUAGE,
    targetLanguage: input.targetLanguage,
  });

  return {
    segments,
    sourceLanguage: base.sourceLanguage,
    targetLanguage: base.targetLanguage,
  };
}

/** Guard against payloads that would segment into an unbounded number of parts. */
export function assertReasonableSize(text: string, maxChars = 200_000): void {
  const size = countCharacters(text);
  if (size > maxChars) {
    throw new ValidationError(`text of ${size} characters exceeds the ${maxChars} character limit`, {
      size,
      maxChars,
    });
  }
}