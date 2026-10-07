/**
 * In-memory cache with TTL and LRU-ish eviction.
 */

import type { CacheGetResult, CacheReadOptions, CacheEntry, CacheStats, TranslationCache } from './types';
import { createEntry, isExpired } from './types';

export interface MemoryCacheOptions {
  defaultTtlMs: number;
  maxEntries: number;
  now?: () => number;
}

export class MemoryCache<T = unknown> implements TranslationCache<T> {
  private readonly map = new Map<string, CacheEntry<T>>();
  private readonly defaultTtlMs: number;
  private readonly maxEntries: number;
  private readonly now: () => number;
  readonly stats: CacheStats = { hits: 0, misses: 0, writes: 0, evictions: 0 };

  constructor(options: MemoryCacheOptions) {
    this.defaultTtlMs = options.defaultTtlMs;
    this.maxEntries = options.maxEntries;
    this.now = options.now ?? Date.now;
  }

  async get(key: string, options: CacheReadOptions = {}): Promise<CacheGetResult<T>> {
    const entry = this.map.get(key);
    if (!entry) {
      this.stats.misses += 1;
      return { hit: false, stale: false };
    }
    if (isExpired(entry, this.now())) {
      this.map.delete(key);
      this.stats.misses += 1;
      if (options.acceptStale) {
        this.stats.hits += 1;
        return { hit: true, entry, stale: true };
      }
      return { hit: false, stale: true };
    }
    // Refresh recency.
    this.map.delete(key);
    this.map.set(key, entry);
    this.stats.hits += 1;
    return { hit: true, entry, stale: false };
  }

  async set(key: string, value: T, ttlMs?: number): Promise<void> {
    if (this.map.has(key)) {
      this.map.delete(key);
    }
    this.map.set(key, createEntry(key, value, ttlMs ?? this.defaultTtlMs, this.now()));
    this.stats.writes += 1;
    while (this.map.size > this.maxEntries) {
      const oldest = this.map.keys().next();
      if (oldest.done) {
        break;
      }
      this.map.delete(oldest.value);
      this.stats.evictions += 1;
    }
  }

  async delete(key: string): Promise<void> {
    this.map.delete(key);
  }

  async clear(): Promise<void> {
    this.map.clear();
  }

  async size(): Promise<number> {
    return this.map.size;
  }

  async keys(): Promise<string[]> {
    return [...this.map.keys()];
  }
}