/**
 * Cache contracts. Implementations must be safe under concurrent access and
 * must never throw into the translation path: a cache failure degrades to a
 * miss, it does not fail the request.
 */

export interface CacheEntry<T = unknown> {
  key: string;
  value: T;
  createdAt: number;
  expiresAt: number;
}

export interface CacheGetResult<T> {
  hit: boolean;
  entry?: CacheEntry<T>;
  /** True when an entry existed but was expired. */
  stale: boolean;
}

export interface TranslationCache<T = unknown> {
  get(key: string, options?: CacheReadOptions): Promise<CacheGetResult<T>>;
  set(key: string, value: T, ttlMs?: number): Promise<void>;
  delete(key: string): Promise<void>;
  clear(): Promise<void>;
  size(): Promise<number>;
  /** Flush pending writes. Optional for memory-only implementations. */
  flush?(): Promise<void>;
  close?(): Promise<void>;
}

export interface CacheReadOptions {
  /** Return expired entries instead of treating them as a miss. */
  acceptStale?: boolean;
}

export interface CacheStats {
  hits: number;
  misses: number;
  writes: number;
  evictions: number;
}

export function createEntry<T>(key: string, value: T, ttlMs: number, now = Date.now()): CacheEntry<T> {
  return {
    key,
    value,
    createdAt: now,
    expiresAt: now + Math.max(0, ttlMs),
  };
}

export function isExpired<T>(entry: CacheEntry<T>, now = Date.now()): boolean {
  return entry.expiresAt <= now;
}

/**
 * True when the implementation has no persistent tier worth flushing.
 * Detected by the presence of a `flush` method, which only file-backed caches
 * implement.
 */
export function isMemoryOnlyCache<T>(cache: TranslationCache<T>): boolean {
  return typeof cache.flush !== 'function';
}