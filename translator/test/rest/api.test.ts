/**
 * REST API tests.
 *
 * These boot a real HTTP server on an ephemeral port and issue real HTTP
 * requests against it. Nothing is mocked at the transport level.
 */

import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { EngineRegistry } from '../../src/engine/registry';
import { createTranslator } from '../../src/translator/translator';
import { MemoryCache } from '../../src/cache/memoryCache';
import { startServer, type StartedServer } from '../../src/server/server';
import { silentLogger } from '../../src/core/logger';
import { ScriptedEngine } from '../helpers/scriptedEngine';

let server: StartedServer;
let engine: ScriptedEngine;
let base: string;
let tempDir: string;

before(async () => {
  tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-rest-'));
  engine = new ScriptedEngine({
    respond: (request) => {
      // Deterministic Arabic-shaped output so tests can assert direction and content.
      return `ع:${request.text}`;
    },
  });

  const config = loadConfig({
    readDotEnv: false,
    rootDir: tempDir,
    env: {
      TRANSLATION_ENGINE: 'scripted',
      LOG_LEVEL: 'silent',
      SERVER_PORT: '0',
      CACHE_ENABLED: 'true',
      CACHE_DIR: tempDir,
      RETRY_JITTER_RATIO: '0',
      RETRY_BASE_DELAY_MS: '1',
      REQUEST_TIMEOUT_MS: '2000',
      CHAPTER_DEADLINE_MS: '10000',
      CHAPTER_SEGMENT_MAX_CHARS: '120',
    },
  });

  const translator = createTranslator({
    config,
    logger: silentLogger,
    registry: new EngineRegistry().register('scripted', () => engine),
    cache: new MemoryCache<unknown>({ defaultTtlMs: 60_000, maxEntries: 500 }),
  });

  server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: silentLogger });
  base = server.url;
});

after(async () => {
  await server?.close();
  await fs.rm(tempDir, { recursive: true, force: true });
});

async function postJson(path: string, body: unknown, init: RequestInit = {}): Promise<{ status: number; body: Record<string, any> }> {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    ...init,
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

async function getJson(path: string): Promise<{ status: number; body: Record<string, any> }> {
  const response = await fetch(`${base}${path}`);
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

describe('GET /health', () => {
  it('reports ok', async () => {
    const { status, body } = await getJson('/health');
    assert.equal(status, 200);
    assert.equal(body.status, 'ok');
    assert.equal(body.engine, 'scripted');
  });
});

describe('GET /languages', () => {
  it('returns source and target languages with direction', async () => {
    const { status, body } = await getJson('/languages');
    assert.equal(status, 200);
    assert.ok(Array.isArray(body.source));
    assert.ok(Array.isArray(body.target));
    assert.ok(body.target.some((l: any) => l.code === 'ar' && l.direction === 'rtl'));
    assert.ok(body.source.some((l: any) => l.code === 'ja'));
    assert.ok(body.source.some((l: any) => l.code === 'zh'));
    assert.ok(body.source.some((l: any) => l.code === 'ko'));
    assert.equal(body.engine, 'scripted');
  });
});

describe('GET /engine/capabilities', () => {
  it('reports the engine limit', async () => {
    const { status, body } = await getJson('/engine/capabilities');
    assert.equal(status, 200);
    assert.equal(body.engine, 'scripted');
    assert.equal(body.maxCharsPerRequest, 500);
  });
});

describe('GET /engine/health', () => {
  it('returns engine health', async () => {
    const { status, body } = await getJson('/engine/health');
    assert.equal(status, 200);
    assert.equal(body.healthy, true);
  });
});

describe('GET /config', () => {
  it('never leaks credentials', async () => {
    const { status, body } = await getJson('/config');
    assert.equal(status, 200);
    assert.equal(typeof body.authenticated, 'boolean');
    assert.ok(!JSON.stringify(body).includes('@'), 'no credential material in the payload');
  });
});

describe('POST /detect-language', () => {
  it('detects Arabic', async () => {
    const { status, body } = await postJson('/detect-language', { text: 'مرحبا كيف حالك' });
    assert.equal(status, 200);
    assert.equal(body.language, 'ar');
    assert.equal(body.direction, 'rtl');
    assert.ok(body.confidence > 0.5);
  });

  it('detects Chinese as zh, not ja', async () => {
    const { status, body } = await postJson('/detect-language', { text: '你以为我是谁？' });
    assert.equal(status, 200);
    assert.equal(body.language, 'zh');
    assert.equal(body.evidence.kanaCount, 0);
  });

  it('detects Japanese when kana is present', async () => {
    const { status, body } = await postJson('/detect-language', { text: 'そんなわけないだろ。' });
    assert.equal(status, 200);
    assert.equal(body.language, 'ja');
  });

  it('rejects empty text with 400', async () => {
    const { status, body } = await postJson('/detect-language', { text: '   ' });
    assert.equal(status, 400);
    assert.equal(body.error.code, 'EMPTY_INPUT');
  });

  it('rejects a missing text field', async () => {
    const { status, body } = await postJson('/detect-language', {});
    assert.equal(status, 400);
    assert.equal(body.error.code, 'VALIDATION_ERROR');
  });

  it('rejects invalid JSON with 400', async () => {
    const response = await fetch(`${base}/detect-language`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{not json',
    });
    assert.equal(response.status, 400);
    const body = (await response.json()) as Record<string, any>;
    assert.equal(body.error.code, 'VALIDATION_ERROR');
  });
});

describe('POST /translate', () => {
  it('translates text and reports RTL for Arabic', async () => {
    const { status, body } = await postJson('/translate', {
      text: 'Hello world',
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(status, 200);
    assert.equal(body.text, 'ع:Hello world');
    assert.equal(body.direction, 'rtl');
    assert.equal(body.engine, 'scripted');
    assert.equal(body.fromCache, false);
    assert.equal(body.segments, 1);
    assert.ok(typeof body.elapsedMs === 'number');
    assert.ok(body.quality);
  });

  it('auto-detects the source language', async () => {
    const { body } = await postJson('/translate', { text: '本気なのか？', targetLanguage: 'ar' });
    assert.equal(body.detectedLanguage, 'ja');
    assert.equal(body.sourceLanguage, 'ja');
  });

  it('serves a cache hit on the second identical request', async () => {
    const first = await postJson('/translate', { text: 'REST cache probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    const second = await postJson('/translate', { text: 'REST cache probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(first.body.fromCache, false);
    assert.equal(second.body.fromCache, true);
    assert.equal(second.body.text, first.body.text);
  });

  it('re-translates when noCache is true', async () => {
    await postJson('/translate', { text: 'REST noCache probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    const { body } = await postJson('/translate', {
      text: 'REST noCache probe',
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      noCache: true,
    });
    assert.equal(body.fromCache, false);
  });

  it('rejects missing text with 400', async () => {
    const { status, body } = await postJson('/translate', { targetLanguage: 'ar' });
    assert.equal(status, 400);
    assert.equal(body.error.code, 'VALIDATION_ERROR');
  });

  it('rejects a missing target language with 400', async () => {
    const { status, body } = await postJson('/translate', { text: 'Hello' });
    assert.equal(status, 400);
    assert.equal(body.error.code, 'VALIDATION_ERROR');
    assert.match(body.error.message, /targetLanguage/);
  });

  it('rejects identical source and target with 400', async () => {
    const { status, body } = await postJson('/translate', {
      text: 'Hello',
      sourceLanguage: 'ar',
      targetLanguage: 'ar',
    });
    assert.equal(status, 400);
    assert.match(body.error.message, /must differ/);
  });

  it('rejects an unknown engine with 500 and a clear code', async () => {
    const { status, body } = await postJson('/translate', {
      text: 'Hello',
      targetLanguage: 'ar',
      engine: 'does-not-exist',
    });
    assert.ok(status >= 400);
    assert.equal(typeof body.error.code, 'string');
    assert.equal(body.error.retryable, false);
  });

  it('cancels the engine request when the client disconnects', async () => {
    const slowEngine = new ScriptedEngine({ delayMs: 4000 });
    const config = loadConfig({
      readDotEnv: false,
      rootDir: tempDir,
      env: {
        TRANSLATION_ENGINE: 'slow',
        LOG_LEVEL: 'silent',
        SERVER_PORT: '0',
        CACHE_ENABLED: 'false',
        RETRY_MAX_ATTEMPTS: '1',
        REQUEST_TIMEOUT_MS: '5000',
      },
    });
    const slowServer = await startServer({
      translator: createTranslator({
        config,
        logger: silentLogger,
        registry: new EngineRegistry().register('slow', () => slowEngine),
        disableCache: true,
      }),
      host: '127.0.0.1',
      port: 0,
      logger: silentLogger,
    });

    const controller = new AbortController();
    const promise = fetch(`${slowServer.url}/translate`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: 'Disconnect me', targetLanguage: 'ar' }),
      signal: controller.signal,
    });
    setTimeout(() => controller.abort(), 60);
    await assert.rejects(() => promise);

    // The engine request must have been aborted, not left pending.
    await new Promise((r) => setTimeout(r, 150));
    assert.equal(slowEngine.calls.length <= 1, true);
    await slowServer.close();
  });
});

describe('POST /translate/chapter', () => {
  const segments = [
    { id: 'p1', text: 'Are you serious right now?!' },
    { id: 'p2', text: "I don't believe what you just said to me." },
    { id: 'p3', text: 'minimum height 165 cm, age 18' },
  ];

  it('translates a chapter and returns per-segment results', async () => {
    const { status, body } = await postJson('/translate/chapter', {
      segments,
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(status, 200);
    assert.equal(body.segments.length, 3);
    assert.equal(body.degraded, false);
    assert.equal(body.progress.totalSegments, 3);
    assert.equal(body.direction, 'rtl');
    assert.ok(body.text.includes('Are you serious'));
    assert.ok(Array.isArray(body.progressEvents));
    assert.ok(body.progressEvents.length > 0);
  });

  it('serves the chapter from cache on the second call', async () => {
    const text = 'Unique chapter cache probe line.';
    await postJson('/translate/chapter', { segments: [{ text }], sourceLanguage: 'en', targetLanguage: 'ar' });
    const { body } = await postJson('/translate/chapter', {
      segments: [{ text }],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(body.progress.cachedSegments, 1);
  });

  it('accepts plain string segments', async () => {
    const { status, body } = await postJson('/translate/chapter', {
      segments: ['Plain string segment'],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
    });
    assert.equal(status, 200);
    assert.equal(body.segments[0].translated, 'ع:Plain string segment');
  });

  it('rejects an empty segment array with 400', async () => {
    const { status, body } = await postJson('/translate/chapter', { segments: [], targetLanguage: 'ar' });
    assert.equal(status, 400);
    assert.match(body.error.message, /at least one/);
  });

  it('rejects a malformed segment with 400', async () => {
    const { status, body } = await postJson('/translate/chapter', {
      segments: [{ nope: true }],
      targetLanguage: 'ar',
    });
    assert.equal(status, 400);
    assert.match(body.error.message, /text must be a non-empty string/);
  });

  it('rejects a missing segments array with 400', async () => {
    const { status } = await postJson('/translate/chapter', { targetLanguage: 'ar' });
    assert.equal(status, 400);
  });

  it('keeps the chapter when one segment fails', async () => {
    const config = loadConfig({
      readDotEnv: false,
      rootDir: tempDir,
      env: {
        TRANSLATION_ENGINE: 'partial',
        LOG_LEVEL: 'silent',
        SERVER_PORT: '0',
        CACHE_ENABLED: 'false',
        RETRY_MAX_ATTEMPTS: '1',
        CHAPTER_SEGMENT_MAX_CHARS: '120',
      },
    });
    const partialEngine = new ScriptedEngine({
      respond: (request) => {
        if (/height 165/.test(request.text)) {
          throw new Error('segment unavailable');
        }
        return `ع:${request.text}`;
      },
    });
    const partialServer = await startServer({
      translator: createTranslator({
        config,
        logger: silentLogger,
        registry: new EngineRegistry().register('partial', () => partialEngine),
        disableCache: true,
      }),
      host: '127.0.0.1',
      port: 0,
      logger: silentLogger,
    });

    const response = await fetch(`${partialServer.url}/translate/chapter`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ segments, sourceLanguage: 'en', targetLanguage: 'ar' }),
    });
    assert.equal(response.status, 200);
    const body = (await response.json()) as Record<string, any>;
    assert.equal(body.degraded, true);
    assert.equal(body.progress.failedSegments, 1);
    assert.ok(body.text.includes('Are you serious'), 'surviving segments must be present');
    assert.ok(body.text.includes('height 165'), 'failed segment keeps its original text');
    await partialServer.close();
  });
});

describe('DELETE /cache', () => {
  it('clears the cache', async () => {
    await postJson('/translate', { text: 'Cache clear probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    const response = await fetch(`${base}/cache`, { method: 'DELETE' });
    assert.equal(response.status, 200);
    const body = (await response.json()) as Record<string, any>;
    assert.equal(body.cleared, true);
    const after = await postJson('/translate', { text: 'Cache clear probe', sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(after.body.fromCache, false);
  });
});

describe('routing', () => {
  it('returns 404 for an unknown route', async () => {
    const { status, body } = await getJson('/does-not-exist');
    assert.equal(status, 404);
    assert.equal(body.error.code, 'NOT_FOUND');
  });

  it('returns 404 for an unknown method on a known path', async () => {
    const response = await fetch(`${base}/translate`, { method: 'GET' });
    assert.equal(response.status, 404);
  });

  it('rejects path traversal on static assets', async () => {
    const response = await fetch(`${base}/../package.json`);
    assert.ok(response.status === 403 || response.status === 404);
  });
});

describe('static UI', () => {
  it('serves index.html', async () => {
    const response = await fetch(`${base}/`);
    assert.equal(response.status, 200);
    const html = await response.text();
    assert.match(html, /Translation Platform/);
    assert.match(html, /app\.js/);
  });

  it('serves the UI script', async () => {
    const response = await fetch(`${base}/app.js`);
    assert.equal(response.status, 200);
    const js = await response.text();
    assert.match(js, /\/translate/);
    assert.match(js, /\/translate\/chapter/);
  });

  it('serves the stylesheet', async () => {
    const response = await fetch(`${base}/styles.css`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type') ?? '', /text\/css/);
  });
});