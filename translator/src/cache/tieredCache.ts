/**
 * Two-tier cache: memory in front of persistent file storage.
 *
 * Any failure inside the tier is swallowed and reported as a miss so a cache
 * problem can never fail a translation request.
 */

import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';
import { MemoryCache } from './memoryCache';
import { FileCache } from './fileCache';
import type { CacheEntry, CacheGetResult, CacheReadOptions, TranslationCache } from './types';

export interface TieredCacheOptions {
  memory: MemoryCache<unknown>;
  file: FileCache<unknown>;
  logger?: Logger;
}

export class TieredCache<T = unknown> implements TranslationCache<T> {
  private readonly memory: MemoryCache<unknown>;
  private readonly file: FileCache<unknown>;
  private readonly logger: Logger;

  constructor(options: TieredCacheOptions) {
    this.memory = options.memory;
    this.file = options.file;
    this.logger = options.logger ?? silentLogger;
  }

  async get(key: string, options: CacheReadOptions = {}): Promise<CacheGetResult<T>> {
    const memoryResult = await this.memory.get(key, options);
    if (memoryResult.hit && memoryResult.entry) {
      return memoryResult as CacheGetResult<T>;
    }

    let fileResult: CacheGetResult<unknown> = { hit: false, stale: false };
    try {
      fileResult = await this.file.get(key, options);
    } catch (error) {
      this.logger.warn('file cache read failed', { error: String(error) });
      return { hit: false, stale: false };
    }

    if (fileResult.hit && fileResult.entry) {
      // Promote to memory with a fresh TTL derived from the remaining life.
      const remaining = Math.max(1000, fileResult.entry.expiresAt - Date.now());
      await this.memory.set(key, fileResult.entry.value, remaining);
      return { hit: true, entry: fileResult.entry as CacheEntry<T>, stale: fileResult.stale };
    }

    return { hit: false, stale: memoryResult.stale || fileResult.stale };
  }

  async set(key: string, value: T, ttlMs?: number): Promise<void> {
    await this.memory.set(key, value, ttlMs);
    try {
      await this.file.set(key, value, ttlMs);
    } catch (error) {
      this.logger.warn('file cache write failed', { error: String(error) });
    }
  }

  async delete(key: string): Promise<void> {
    await this.memory.delete(key);
    try {
      await this.file.delete(key);
    } catch (error) {
      this.logger.warn('file cache delete failed', { error: String(error) });
    }
  }

  /**
   * Clears both tiers.
   *
   * Memory is dropped synchronously so a subsequent read cannot observe a value
   * that the persistent tier is about to lose.
   */
  async clear(): Promise<void> {
    await this.memory.clear();
    try {
      await this.file.clear();
    } catch (error) {
      this.logger.warn('file cache clear failed', { error: String(error) });
    }
  }

  async size(): Promise<number> {
    try {
      return await this.file.size();
    } catch {
      return this.memory.size();
    }
  }

  async flush(): Promise<void> {
    try {
      await this.file.flush();
    } catch (error) {
      this.logger.warn('file cache flush failed', { error: String(error) });
    }
  }

  async close(): Promise<void> {
    await this.flush();
  }
}