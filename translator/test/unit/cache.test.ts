import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { buildCacheKey, normalizeForCacheKey } from '../../src/cache/key';
import { FileCache } from '../../src/cache/fileCache';
import { MemoryCache } from '../../src/cache/memoryCache';
import { TieredCache } from '../../src/cache/tieredCache';

const TTL = 60_000;
const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-cache-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((dir) => fs.rm(dir, { recursive: true, force: true })));
});

describe('cache key', () => {
  // Regression: contextBefore used to change the key, so the same sentence at a
  // different position in a chapter produced a different cache entry.
  it('ignores surrounding context because it is not part of the key input', () => {
    const base = { text: 'Are you serious?', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' };
    const withContext = { ...base, contextBefore: 'Earlier dialogue in the chapter.' };
    assert.equal(buildCacheKey(base), buildCacheKey(withContext as typeof base));
  });

  it('is deterministic across calls', () => {
    const input = { text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' };
    assert.equal(buildCacheKey(input), buildCacheKey(input));
  });

  it('normalizes whitespace variants to the same key', () => {
    const a = buildCacheKey({
      text: 'Hello   world',
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      engine: 'mymemory',
    });
    const b = buildCacheKey({
      text: '  Hello \t world  ',
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      engine: 'mymemory',
    });
    assert.equal(a, b);
  });

  it('normalizes CRLF and newline spacing', () => {
    const a = buildCacheKey({ text: 'a\r\nb', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'x' });
    const b = buildCacheKey({ text: 'a\n\n\nb', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'x' });
    assert.equal(a, b);
  });

  it('differs for different target languages', () => {
    const base = { text: 'Hello', sourceLanguage: 'en', engine: 'mymemory' };
    assert.notEqual(
      buildCacheKey({ ...base, targetLanguage: 'ar' }),
      buildCacheKey({ ...base, targetLanguage: 'fr' }),
    );
  });

  it('differs for different engines', () => {
    const base = { text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' };
    assert.notEqual(buildCacheKey({ ...base, engine: 'a' }), buildCacheKey({ ...base, engine: 'b' }));
  });

  it('differs for different stable hints', () => {
    const base = { text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' };
    assert.notEqual(
      buildCacheKey({ ...base, hints: { formality: 'formal' } }),
      buildCacheKey({ ...base, hints: { formality: 'casual' } }),
    );
  });

  it('ignores hint key order', () => {
    const base = { text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' };
    assert.equal(
      buildCacheKey({ ...base, hints: { a: '1', b: '2' } }),
      buildCacheKey({ ...base, hints: { b: '2', a: '1' } }),
    );
  });

  it('does not collapse genuinely different text', () => {
    const base = { sourceLanguage: 'en', targetLanguage: 'ar', engine: 'mymemory' };
    assert.notEqual(buildCacheKey({ ...base, text: 'Hello' }), buildCacheKey({ ...base, text: 'Hello!' }));
  });

  it('normalizeForCacheKey is idempotent', () => {
    const once = normalizeForCacheKey('  a  b \r\n\r\n c ');
    assert.equal(normalizeForCacheKey(once), once);
  });
});

describe('MemoryCache', () => {
  it('stores and returns a value', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('k', 'value');
    const result = await cache.get('k');
    assert.equal(result.hit, true);
    assert.equal(result.entry?.value, 'value');
  });

  it('reports a miss for an unknown key', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: TTL, maxEntries: 10 });
    assert.equal((await cache.get('nope')).hit, false);
  });

  it('expires entries', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: 10, maxEntries: 10 });
    await cache.set('k', 'v');
    await new Promise((r) => setTimeout(r, 25));
    assert.equal((await cache.get('k')).hit, false);
  });

  it('returns stale entries when accepted', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: 10, maxEntries: 10 });
    await cache.set('k', 'v');
    await new Promise((r) => setTimeout(r, 25));
    const result = await cache.get('k', { acceptStale: true });
    assert.equal(result.hit, true);
    assert.equal(result.stale, true);
  });

  it('evicts the oldest entry when over capacity', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: TTL, maxEntries: 2 });
    await cache.set('a', '1');
    await cache.set('b', '2');
    await cache.set('c', '3');
    assert.equal((await cache.get('a')).hit, false);
    assert.equal((await cache.get('c')).hit, true);
    assert.equal(cache.stats.evictions, 1);
  });

  it('clears all entries', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('a', '1');
    await cache.clear();
    assert.equal(await cache.size(), 0);
  });

  it('tracks hits and misses', async () => {
    const cache = new MemoryCache<string>({ defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('a', '1');
    await cache.get('a');
    await cache.get('b');
    assert.equal(cache.stats.hits, 1);
    assert.equal(cache.stats.misses, 1);
  });
});

describe('FileCache', () => {
  it('persists entries across instances (restart recovery)', async () => {
    const dir = await tempDir();
    const first = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await first.set('k', 'persisted');
    await first.flush();

    const second = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    const result = await second.get('k');
    assert.equal(result.hit, true);
    assert.equal(result.entry?.value, 'persisted');
  });

  it('does not write on a no-op flush', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await cache.flush();
    const entries = await fs.readdir(dir);
    assert.equal(entries.length, 0);
  });

  it('removes the backing file on clear', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await cache.set('k', 'v');
    await cache.flush();
    await cache.clear();
    assert.equal(await cache.size(), 0);
    assert.equal((await cache.get('k')).hit, false);
  });

  it('survives a missing cache directory', async () => {
    const dir = path.join(await tempDir(), 'nested', 'deep');
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('k', 'v');
    await cache.flush();
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    assert.equal((await reopened.get('k')).hit, true);
  });

  it('ignores a corrupt cache file', async () => {
    const dir = await tempDir();
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(path.join(dir, 'translations.json'), '{not json');
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('k', 'v');
    assert.equal((await cache.get('k')).entry?.value, 'v');
  });

  it('drops expired entries on load', async () => {
    const dir = await tempDir();
    const first = new FileCache<string>({ directory: dir, defaultTtlMs: 10, maxEntries: 10 });
    await first.set('k', 'v');
    await first.flush();
    await new Promise((r) => setTimeout(r, 30));
    const second = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    assert.equal((await second.get('k')).hit, false);
  });

  it('serializes concurrent writes without losing entries', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await Promise.all(Array.from({ length: 40 }, (_, i) => cache.set(`k${i}`, `v${i}`)));
    await cache.flush();
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    assert.equal(await reopened.size(), 40);
  });

  // Regression: a flush queued before clear() used to resurrect deleted data.
  it('does not let a queued flush resurrect cleared data', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    await cache.set('a', '1');
    await cache.set('b', '2');

    const queuedFlush = cache.flush();
    await cache.clear();
    await queuedFlush;

    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    assert.equal(await reopened.size(), 0);
    assert.equal((await reopened.get('a')).hit, false);
  });

  // Regression: clear() and flush() racing left stale entries on disk.
  it('keeps clear and flush consistent under repeated interleaving', async () => {
    const dir = await tempDir();
    for (let round = 0; round < 5; round += 1) {
      const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
      await Promise.all(Array.from({ length: 10 }, (_, i) => cache.set(`r${i}`, String(i))));
      const flushPromise = cache.flush();
      await cache.clear();
      await flushPromise;
      await cache.close();
    }
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    assert.equal(await reopened.size(), 0);
  });

  it('drops writes queued before a clear', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    const pendingWrite = cache.set('late', 'value');
    await cache.clear();
    await pendingWrite;
    await cache.flush();

    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 100 });
    assert.equal((await reopened.get('late')).hit, false);
  });

  it('recovers when the directory disappears between write and rename', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    await cache.set('k', 'v');
    await fs.rm(dir, { recursive: true, force: true });
    await cache.flush();
    const reopened = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    assert.equal((await reopened.get('k')).entry?.value, 'v');
  });

  it('evicts oldest entries beyond capacity', async () => {
    const dir = await tempDir();
    const cache = new FileCache<string>({ directory: dir, defaultTtlMs: TTL, maxEntries: 3 });
    for (const key of ['a', 'b', 'c', 'd']) {
      await cache.set(key, key.toUpperCase());
    }
    assert.equal(await cache.size(), 3);
    assert.equal((await cache.get('a')).hit, false);
  });
});

describe('TieredCache', () => {
  it('reads through memory to file', async () => {
    const dir = await tempDir();
    const memory = new MemoryCache<unknown>({ defaultTtlMs: TTL, maxEntries: 10 });
    const file = new FileCache<unknown>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    const cache = new TieredCache<string>({ memory, file });

    await cache.set('k', 'v');
    const freshMemory = new MemoryCache<unknown>({ defaultTtlMs: TTL, maxEntries: 10 });
    const promoted = new TieredCache<string>({ memory: freshMemory, file });
    assert.equal((await promoted.get('k')).entry?.value, 'v');
    // Promotion into the fresh memory tier proves the file tier served it.
    assert.equal((await freshMemory.get('k')).hit, true);
  });

  it('reports a miss when both tiers are empty', async () => {
    const dir = await tempDir();
    const cache = new TieredCache<string>({
      memory: new MemoryCache({ defaultTtlMs: TTL, maxEntries: 10 }),
      file: new FileCache({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 }),
    });
    assert.equal((await cache.get('nothing')).hit, false);
  });

  it('clears both tiers', async () => {
    const dir = await tempDir();
    const memory = new MemoryCache<unknown>({ defaultTtlMs: TTL, maxEntries: 10 });
    const file = new FileCache<unknown>({ directory: dir, defaultTtlMs: TTL, maxEntries: 10 });
    const cache = new TieredCache<string>({ memory, file });
    await cache.set('k', 'v');
    await cache.clear();
    assert.equal((await cache.get('k')).hit, false);
    assert.equal(await memory.size(), 0);
  });

  it('degrades to a miss when the file tier throws', async () => {
    const broken = {
      get: async () => {
        throw new Error('disk failure');
      },
      set: async () => {
        throw new Error('disk failure');
      },
      delete: async () => undefined,
      clear: async () => undefined,
      size: async () => 0,
    };
    const cache = new TieredCache<string>({
      memory: new MemoryCache({ defaultTtlMs: TTL, maxEntries: 10 }),
      file: broken as never,
    });
    await cache.set('k', 'v');
    // Memory tier still serves the value.
    assert.equal((await cache.get('k')).hit, true);
  });
});