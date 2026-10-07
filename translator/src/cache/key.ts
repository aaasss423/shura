/**
 * Deterministic cache keys.
 *
 * Rules learned from the previous project:
 *  - `contextBefore` is NEVER part of the key. The same sentence must hit the
 *    same cache entry regardless of where it appears in a chapter.
 *  - Whitespace is normalized (multiple spaces, newlines, CRLF) before hashing.
 *  - Only stable, semantic options participate in the key. Volatile options
 *    (timeout, retries, refresh, noCache) never do.
 */

import { createHash } from 'node:crypto';

export const CACHE_KEY_VERSION = 'v1';

export interface CacheKeyInput {
  text: string;
  sourceLanguage: string;
  targetLanguage: string;
  engine: string;
  /** Stable engine hints only (e.g. formality). */
  hints?: Record<string, string>;
}

/** Normalizes text for keying: CRLF -> LF, trim each line, collapse spaces. */
export function normalizeForCacheKey(text: string): string {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+/g, ' ')
    .replace(/[ \t]*\n[ \t]*/g, '\n')
    .replace(/ {2,}/g, ' ')
    .replace(/\n{2,}/g, '\n')
    .trim();
}

function stableStringify(value: Record<string, string>): string {
  return Object.keys(value)
    .sort()
    .map((k) => `${k}=${value[k]}`)
    .join('&');
}

export function buildCacheKey(input: CacheKeyInput): string {
  const normalizedText = normalizeForCacheKey(input.text);
  const payload = [
    CACHE_KEY_VERSION,
    input.engine,
    input.sourceLanguage,
    input.targetLanguage,
    stableStringify(input.hints ?? {}),
    normalizedText,
  ].join('\u0001');

  const hash = createHash('sha256').update(payload, 'utf8').digest('hex');
  return `${input.engine}:${input.sourceLanguage}:${input.targetLanguage}:${hash.slice(0, 32)}`;
}