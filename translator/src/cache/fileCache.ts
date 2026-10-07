/**
 * Persistent file cache.
 *
 * Concurrency contract (explicitly fixing two bugs from the previous project):
 *
 *  1. Every mutating operation goes through a single serialized queue, so a
 *     write/flush can never interleave with another write.
 *  2. `clear()` bumps a generation counter *synchronously*. Any flush that was
 *     queued before the clear sees a stale generation when it runs and aborts,
 *     so an old flush can never resurrect data that was deleted.
 *  3. Rename during save handles ENOENT (directory removed underneath us) by
 *     recreating the directory and retrying once, and treats a still-missing
 *     target as a soft failure instead of throwing into the caller.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { createEntry, isExpired } from './types';
import type { CacheEntry, CacheGetResult, CacheReadOptions, TranslationCache } from './types';

export interface FileCacheOptions {
  directory: string;
  defaultTtlMs: number;
  now?: () => number;
  /** In-memory upper bound; LRU eviction triggers a persist. */
  maxEntries: number;
}

interface PersistedFile {
  version: 1;
  entries: Record<string, CacheEntry<unknown>>;
}

export class FileCache<T = unknown> implements TranslationCache<T> {
  private readonly directory: string;
  private readonly filePath: string;
  private readonly defaultTtlMs: number;
  private readonly now: () => number;
  private readonly maxEntries: number;

  private entries = new Map<string, CacheEntry<T>>();
  private loaded = false;
  private loadPromise?: Promise<void>;
  private dirty = false;

  /** Serializes all mutations. Never reject the chain. */
  private queue: Promise<void> = Promise.resolve();
  /** Incremented synchronously by clear() to invalidate queued flushes. */
  private generation = 0;

  constructor(options: FileCacheOptions) {
    this.directory = options.directory;
    this.filePath = path.join(options.directory, 'translations.json');
    this.defaultTtlMs = options.defaultTtlMs;
    this.now = options.now ?? Date.now;
    this.maxEntries = options.maxEntries;
  }

  private enqueue<TOut>(task: () => Promise<TOut>): Promise<TOut> {
    const run = this.queue.then(task, task);
    // Keep the chain alive regardless of individual task outcome.
    this.queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  /**
   * Loads the backing file at most once.
   *
   * The promise is created synchronously and shared by all callers, so
   * concurrent `set()` calls can never race a later `this.entries = new Map()`
   * assignment and lose entries written while the read was in flight.
   */
  private ensureLoaded(): Promise<void> {
    if (!this.loadPromise) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  private async load(): Promise<void> {
    let raw: string;
    try {
      raw = await fs.readFile(this.filePath, 'utf8');
    } catch {
      this.loaded = true;
      return;
    }
    const map = new Map<string, CacheEntry<T>>();
    try {
      const parsed = JSON.parse(raw) as PersistedFile;
      if (parsed && typeof parsed === 'object' && parsed.entries) {
        for (const [key, entry] of Object.entries(parsed.entries)) {
          if (entry && typeof entry.expiresAt === 'number' && !isExpired(entry as CacheEntry<T>, this.now())) {
            map.set(key, entry as CacheEntry<T>);
          }
        }
      }
    } catch {
      // Corrupt file: start from empty rather than failing every request.
    }
    // Only adopt the loaded data when nothing was written in the meantime.
    if (!this.loaded) {
      this.entries = map;
      this.loaded = true;
    } else {
      for (const [key, entry] of map) {
        if (!this.entries.has(key)) {
          this.entries.set(key, entry);
        }
      }
    }
  }

  async get(key: string, options: CacheReadOptions = {}): Promise<CacheGetResult<T>> {
    await this.ensureLoaded();
    const entry = this.entries.get(key);
    if (!entry) {
      return { hit: false, stale: false };
    }
    if (isExpired(entry, this.now())) {
      this.entries.delete(key);
      this.dirty = true;
      if (options.acceptStale) {
        return { hit: true, entry, stale: true };
      }
      return { hit: false, stale: true };
    }
    return { hit: true, entry, stale: false };
  }

  async set(key: string, value: T, ttlMs?: number): Promise<void> {
    // Captured before the first await so a clear() racing this call wins.
    const generation = this.generation;
    await this.ensureLoaded();
    await this.enqueue(async () => {
      if (generation !== this.generation) {
        // A clear() happened while this write was queued: drop the stale write.
        return;
      }
      this.entries.delete(key);
      this.entries.set(key, createEntry(key, value, ttlMs ?? this.defaultTtlMs, this.now()));
      this.dirty = true;
      while (this.entries.size > this.maxEntries) {
        const oldest = this.entries.keys().next();
        if (oldest.done) {
          break;
        }
        this.entries.delete(oldest.value);
      }
    });
  }

  async delete(key: string): Promise<void> {
    const generation = this.generation;
    await this.ensureLoaded();
    await this.enqueue(async () => {
      if (generation !== this.generation) {
        return;
      }
      if (this.entries.delete(key)) {
        this.dirty = true;
      }
    });
  }

  /**
   * Clears entries and removes the backing file.
   *
   * The generation bump happens synchronously (before any await) so that flushes
   * already sitting in the queue are guaranteed to be discarded.
   */
  async clear(): Promise<void> {
    this.generation += 1;
    const generation = this.generation;
    await this.enqueue(async () => {
      if (generation !== this.generation) {
        return;
      }
      this.entries = new Map();
      this.loaded = true;
      this.dirty = false;
      try {
        await fs.rm(this.filePath, { force: true });
      } catch {
        // Nothing to remove.
      }
    });
  }

  async size(): Promise<number> {
    await this.ensureLoaded();
    return this.entries.size;
  }

  async keys(): Promise<string[]> {
    await this.ensureLoaded();
    return [...this.entries.keys()];
  }

  /** Persists current state. No-op-safe: a no-op flush does not write. */
  async flush(): Promise<void> {
    // Synchronous capture: a clear() called after this point invalidates it.
    const generation = this.generation;
    await this.ensureLoaded();
    await this.enqueue(async () => {
      if (generation !== this.generation) {
        // Stale flush: a clear() already invalidated this snapshot. Never write.
        return;
      }
      if (!this.dirty) {
        return;
      }
      await this.persist();
    });
  }

  private async persist(): Promise<void> {
    const snapshot: PersistedFile = {
      version: 1,
      entries: Object.fromEntries(this.entries),
    };
    const payload = JSON.stringify(snapshot);
    const tmpPath = `${this.filePath}.${process.pid}.${Date.now()}.tmp`;

    try {
      await fs.mkdir(this.directory, { recursive: true });
    } catch {
      // Directory creation failed; attempt the write anyway and swallow below.
    }

    try {
      await fs.writeFile(tmpPath, payload, 'utf8');
    } catch {
      try {
        await fs.rm(tmpPath, { force: true });
      } catch {
        /* ignore */
      }
      this.dirty = true;
      return;
    }

    try {
      await fs.rename(tmpPath, this.filePath);
      this.dirty = false;
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code === 'ENOENT') {
        // The directory or target vanished. Recreate and retry exactly once.
        try {
          await fs.mkdir(path.dirname(this.filePath), { recursive: true });
          await fs.rename(tmpPath, this.filePath);
          this.dirty = false;
          return;
        } catch {
          // fall through to cleanup
        }
      }
      try {
        await fs.rm(tmpPath, { force: true });
      } catch {
        /* ignore */
      }
      // Keep dirty=true so a later flush can retry. Never throw to the caller.
      this.dirty = true;
    }
  }

  async close(): Promise<void> {
    await this.flush();
  }
}