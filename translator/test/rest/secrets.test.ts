/**
 * REST tests for engine selection and runtime secret handling.
 *
 * Boots real servers and issues real HTTP requests. The central guarantee
 * verified here is negative: a DeepL API key must never appear in any response
 * body, log line, or error payload.
 */

import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, describe, it } from 'node:test';
import { loadConfig } from '../../src/config/index';
import { EngineRegistry } from '../../src/engine/registry';
import { createTranslator } from '../../src/translator/translator';
import { startServer, type StartedServer } from '../../src/server/server';
import { silentLogger } from '../../src/core/logger';
import { ScriptedEngine, UnconfiguredEngine } from '../helpers/scriptedEngine';
import { EncryptedFileSecretStore, EnvironmentFirstSecretStore } from '../../src/security/secretStore';

const FAKE_KEY = 'fake-deepl-key-0123456789';
const tempDirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-secret-rest-'));
  tempDirs.push(dir);
  return dir;
}

after(async () => {
  await Promise.all(tempDirs.map((d) => fs.rm(d, { recursive: true, force: true })));
});

interface Fixture {
  server: StartedServer;
  base: string;
  logs: string[];
  primary: ScriptedEngine;
  secondary: ScriptedEngine;
}

async function boot(env: Record<string, string> = {}): Promise<Fixture> {
  const dir = await tempDir();
  const logs: string[] = [];
  const primary = new ScriptedEngine({ respond: () => 'ع:primary' });
  const secondary = new ScriptedEngine({ respond: () => 'ع:secondary' });

  const config = loadConfig({
    readDotEnv: false,
    rootDir: dir,
    env: {
      LOG_LEVEL: 'info',
      CACHE_ENABLED: 'false',
      REQUEST_TIMEOUT_MS: '3000',
      ...env,
    },
  });

  // A logger that records every line, so tests can assert nothing leaks.
  const recordingLogger = {
    error: (m: string, c?: Record<string, unknown>) => logs.push(JSON.stringify({ level: 'error', msg: m, ...c })),
    warn: (m: string, c?: Record<string, unknown>) => logs.push(JSON.stringify({ level: 'warn', msg: m, ...c })),
    info: (m: string, c?: Record<string, unknown>) => logs.push(JSON.stringify({ level: 'info', msg: m, ...c })),
    debug: (m: string, c?: Record<string, unknown>) => logs.push(JSON.stringify({ level: 'debug', msg: m, ...c })),
    child: () => recordingLogger,
  };

  const translator = createTranslator({
    config,
    logger: recordingLogger,
    registry: new EngineRegistry()
      .register('deepl', () => primary as never)
      .register('mymemory', () => secondary as never),
    // An isolated vault so tests never touch the real user store.
    secretStore: new (require('../../src/security/secretStore').EnvironmentFirstSecretStore)({
      vault: new (require('../../src/security/secretStore').EncryptedFileSecretStore)(path.join(dir, 'vault')),
      env: {},
    }),
    disableCache: true,
  });

  const server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: recordingLogger });
  return { server, base: server.url, logs, primary, secondary };
}

async function getJson(base: string, route: string): Promise<{ status: number; body: any; raw: string }> {
  const response = await fetch(`${base}${route}`);
  const raw = await response.text();
  return { status: response.status, body: raw ? JSON.parse(raw) : null, raw };
}

async function send(
  base: string,
  method: string,
  route: string,
  body?: unknown,
): Promise<{ status: number; body: any; raw: string }> {
  const response = await fetch(`${base}${route}`, {
    method,
    ...(body === undefined
      ? {}
      : { headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }),
  });
  const raw = await response.text();
  return { status: response.status, body: raw ? JSON.parse(raw) : null, raw };
}

describe('GET /engines', () => {
  it('lists engines with availability and never leaks a key', async () => {
    const fixture = await boot({ TRANSLATION_ENGINE: 'deepl' });
    try {
      const { status, body, raw } = await getJson(fixture.base, '/engines');
      assert.equal(status, 200);
      assert.ok(Array.isArray(body.engines));
      assert.ok(body.engines.some((e: any) => e.id === 'deepl'));
      assert.ok(body.engines.some((e: any) => e.id === 'mymemory'));
      assert.ok(!raw.includes(FAKE_KEY));
      assert.ok(!raw.toLowerCase().includes('apikey'));
    } finally {
      await fixture.server.close();
    }
  });

  it('reports routing configuration', async () => {
    const fixture = await boot({
      TRANSLATION_ENGINE: 'deepl',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl,zh=deepl',
      TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
    });
    try {
      const { status, body } = await getJson(fixture.base, '/engines');
      assert.equal(status, 200);
      assert.equal(body.routing.default, 'deepl');
      assert.deepEqual(body.routing.rules, [
        { source: 'ja', engine: 'deepl' },
        { source: 'zh', engine: 'deepl' },
      ]);
      assert.deepEqual(body.routing.fallbacks, ['mymemory']);
    } finally {
      await fixture.server.close();
    }
  });
});

describe('GET /engine/route', () => {
  it('resolves the engine for a source language', async () => {
    const fixture = await boot({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
      TRANSLATION_ENGINE_FALLBACKS: 'mymemory',
    });
    try {
      const { status, body } = await getJson(fixture.base, '/engine/route?source=ja');
      assert.equal(status, 200);
      assert.equal(body.engine, 'deepl');
      assert.equal(body.reason, 'route');
    } finally {
      await fixture.server.close();
    }
  });
});

describe('engine selection over REST', () => {
  it('routes by language and reports the serving engine', async () => {
    const fixture = await boot({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
    });
    try {
      const ja = await send(fixture.base, 'POST', '/translate', {
        text: '本気なのか？',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
      });
      assert.equal(ja.status, 200);
      assert.equal(ja.body.engine, 'deepl');

      const en = await send(fixture.base, 'POST', '/translate', {
        text: 'Are you serious?',
        sourceLanguage: 'en',
        targetLanguage: 'ar',
      });
      assert.equal(en.body.engine, 'mymemory');
    } finally {
      await fixture.server.close();
    }
  });

  it('honours an explicit engine in the request body', async () => {
    const fixture = await boot({
      TRANSLATION_ENGINE: 'mymemory',
      TRANSLATION_ENGINE_ROUTES: 'ja=deepl',
    });
    try {
      const { body } = await send(fixture.base, 'POST', '/translate', {
        text: '本気なのか？',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        engine: 'mymemory',
      });
      assert.equal(body.engine, 'mymemory');
    } finally {
      await fixture.server.close();
    }
  });

  it('reports an unavailable engine instead of pretending to succeed', async () => {
    const fixture = await boot({ TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: 'mymemory' });
    // Replace the primary with an engine that reports no credential.
    await fixture.server.close();
    const dir = await tempDir();
    const server = await startServer({
      translator: createTranslator({
        config: loadConfig({
          readDotEnv: false,
          rootDir: dir,
          env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false', TRANSLATION_ENGINE: 'deepl', TRANSLATION_ENGINE_FALLBACKS: 'mymemory' },
        }),
        logger: silentLogger,
        registry: new EngineRegistry()
          .register('deepl', () => new UnconfiguredEngine('DeepL is not configured (DEEPL_API_KEY is not set)') as never)
          .register('mymemory', () => new ScriptedEngine({ respond: () => 'ع:ok' }) as never),
        disableCache: true,
      }),
      host: '127.0.0.1',
      port: 0,
      logger: silentLogger,
    });
    try {
      const { status, body } = await send(server.url, 'POST', '/translate', {
        text: '本気なのか？',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
      });
      assert.equal(status, 200);
      assert.equal(body.engine, 'mymemory', 'the fallback must serve the request');
    } finally {
      await server.close();
    }
  });
});

describe('runtime secret endpoints', () => {
  it('starts with no configured secret', async () => {
    const fixture = await boot();
    try {
      const { status, body } = await getJson(fixture.base, '/secrets');
      assert.equal(status, 200);
      const deepl = body.secrets.find((s: any) => s.engine === 'deepl');
      assert.equal(deepl.configured, false);
      assert.equal(deepl.source, 'none');
    } finally {
      await fixture.server.close();
    }
  });

  it('stores a key and reports status without echoing the value', async () => {
    const fixture = await boot();
    try {
      const saved = await send(fixture.base, 'PUT', '/secrets/deepl', { apiKey: FAKE_KEY });
      assert.equal(saved.status, 200);
      assert.equal(saved.body.secret.configured, true);
      assert.ok(saved.body.secret.fingerprint);
      assert.ok(!saved.raw.includes(FAKE_KEY), 'the key must never be echoed back');
      assert.ok(!saved.raw.includes(FAKE_KEY.slice(0, 10)));

      const listed = await getJson(fixture.base, '/secrets');
      assert.ok(!listed.raw.includes(FAKE_KEY));
      assert.equal(listed.body.secrets.find((s: any) => s.engine === 'deepl').configured, true);
    } finally {
      await fixture.server.close();
    }
  });

  it('makes the real DeepL engine available immediately after saving', async () => {
    // Uses the real engine, not a test double: this is the end-to-end proof
    // that saving a key in the UI is enough, with no restart.
    const dir = await tempDir();
    const vaultDir = path.join(dir, 'vault');
    const translator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: dir,
        env: { LOG_LEVEL: 'silent', CACHE_ENABLED: 'false', TRANSLATION_ENGINE: 'deepl' },
      }),
      logger: silentLogger,
      secretStore: new EnvironmentFirstSecretStore({
        vault: new EncryptedFileSecretStore(vaultDir),
        env: {},
      }),
      disableCache: true,
    });
    const server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: silentLogger });
    try {
      const before = await getJson(server.url, '/engines');
      assert.equal(before.body.engines.find((e: any) => e.id === 'deepl').available, false);
      assert.match(before.body.engines.find((e: any) => e.id === 'deepl').reason, /DEEPL_API_KEY/);

      await send(server.url, 'PUT', '/secrets/deepl', { apiKey: FAKE_KEY });

      const after = await getJson(server.url, '/engines');
      const deepl = after.body.engines.find((e: any) => e.id === 'deepl');
      assert.equal(deepl.available, true, 'a saved key must enable the engine without a restart');
      assert.equal(deepl.reason, undefined);
      assert.ok(!after.raw.includes(FAKE_KEY));

      // The key is on disk only inside the vault, with restrictive permissions.
      const vaultFiles = await fs.readdir(vaultDir);
      assert.ok(vaultFiles.includes('secrets.enc'));
      const stats = await fs.stat(path.join(vaultDir, 'secrets.enc'));
      assert.equal(stats.mode & 0o777, 0o600);
      const contents = await fs.readFile(path.join(vaultDir, 'secrets.enc'), 'utf8');
      assert.ok(!contents.includes(FAKE_KEY), 'the key must not be readable on disk');

      await send(server.url, 'DELETE', '/secrets/deepl');
      const disabled = await getJson(server.url, '/engines');
      assert.equal(disabled.body.engines.find((e: any) => e.id === 'deepl').available, false);
    } finally {
      await server.close();
    }
  });

  it('deletes a key and reports not configured', async () => {
    const fixture = await boot({ TRANSLATION_ENGINE: 'deepl' });
    try {
      await send(fixture.base, 'PUT', '/secrets/deepl', { apiKey: FAKE_KEY });
      const deleted = await send(fixture.base, 'DELETE', '/secrets/deepl');
      assert.equal(deleted.status, 200);
      assert.equal(deleted.body.secret.configured, false);
      assert.ok(!deleted.raw.includes(FAKE_KEY));
    } finally {
      await fixture.server.close();
    }
  });

  it('rejects an empty key with 400 and a clear code', async () => {
    const fixture = await boot();
    try {
      const { status, body } = await send(fixture.base, 'PUT', '/secrets/deepl', { apiKey: '   ' });
      assert.equal(status, 400);
      assert.equal(body.error.code, 'VALIDATION_ERROR');
      const missing = await send(fixture.base, 'PUT', '/secrets/deepl', {});
      assert.equal(missing.status, 400);
    } finally {
      await fixture.server.close();
    }
  });

  it('never writes the key into logs', async () => {
    const fixture = await boot();
    try {
      await send(fixture.base, 'PUT', '/secrets/deepl', { apiKey: FAKE_KEY });
      await send(fixture.base, 'GET', '/secrets');
      await send(fixture.base, 'GET', '/config');
      await send(fixture.base, 'GET', '/engines');
      const logText = fixture.logs.join('\n');
      assert.ok(!logText.includes(FAKE_KEY), 'the key must never reach the log');
    } finally {
      await fixture.server.close();
    }
  });

  it('never writes the key into /config', async () => {
    const fixture = await boot({ DEEPL_API_KEY: FAKE_KEY });
    try {
      const { status, body, raw } = await getJson(fixture.base, '/config');
      assert.equal(status, 200);
      assert.ok(!raw.includes(FAKE_KEY));
      assert.equal(body.engines.deepl.configured, true);
      assert.equal(body.engines.deepl.keyFingerprint.length, 12);
    } finally {
      await fixture.server.close();
    }
  });

});
