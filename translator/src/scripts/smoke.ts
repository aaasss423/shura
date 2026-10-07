/**
 * Smoke test: proves the platform performs real translation end to end.
 *
 * Unlike a unit test this is a runnable, human-readable diagnostic. Each step
 * prints what actually happened, and the process exits non-zero if any step
 * fails. `--live` is implied because a fake translation would prove nothing.
 *
 * Usage:
 *   node dist/src/scripts/smoke.js            # offline engine checks + live translation
 *   node dist/src/scripts/smoke.js --offline  # skip network steps
 */

import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { loadConfig } from '../config/index';
import { createTranslator, type Translator } from '../translator/translator';
import { startServer } from '../server/server';
import { EngineRegistry } from '../engine/registry';
import { ScriptedEngine } from '../../test/helpers/scriptedEngine';
import { silentLogger } from '../core/logger';
import { CancellationToken } from '../core/cancellation';
import { isTranslationError } from '../core/errors';
import { containsArabic } from '../arabic/arabic';
import type { StartedServer } from '../server/server';

const offline = process.argv.includes('--offline');
const steps: Array<{ name: string; status: 'PASS' | 'FAIL' | 'SKIP'; detail: string }> = [];

type StepStatus = 'PASS' | 'FAIL' | 'SKIP';

/** Records a check. A boolean `ok` maps to PASS/FAIL; a string detail means SKIP. */
function report(name: string, status: StepStatus | boolean, detail: string): void {
  const resolved: StepStatus = typeof status === 'boolean' ? (status ? 'PASS' : 'FAIL') : status;
  steps.push({ name, status: resolved, detail });
  const icon = resolved === 'PASS' ? '\u2713' : resolved === 'FAIL' ? '\u2717' : '\u2013';
  process.stdout.write(`${icon} ${name}: ${detail}\n`);
}

/** Records a check as SKIPPED with a reason. */
function skip(name: string, reason: string): void {
  report(name, 'SKIP', reason);
}

function heading(title: string): void {
  process.stdout.write(`\n=== ${title} ===\n`);
}

async function main(): Promise<void> {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'tp-smoke-'));
  let translator: Translator | undefined;
  let server: StartedServer | undefined;

  try {
    // ---------- offline structural checks (no network) ----------
    heading('1. offline engine and API checks');

    const scripted = new ScriptedEngine({ respond: (r) => `ع:${r.text}` });
    const registry = new EngineRegistry().register('scripted', () => scripted);

    const offlineConfig = loadConfig({
      readDotEnv: false,
      rootDir: tempDir,
      env: {
        TRANSLATION_ENGINE: 'scripted',
        LOG_LEVEL: 'silent',
        CACHE_ENABLED: 'true',
        CACHE_DIR: tempDir,
        RETRY_MAX_ATTEMPTS: '1',
        RETRY_BASE_DELAY_MS: '1',
        REQUEST_TIMEOUT_MS: '2000',
        CHAPTER_DEADLINE_MS: '10000',
      },
    });

    translator = createTranslator({ config: offlineConfig, logger: silentLogger, registry });

    // 1. real request reaches the engine
    const first = await translator.translate({ text: 'Smoke probe one', sourceLanguage: 'en', targetLanguage: 'ar' });
    report(
      'request reaches the engine',
      first.fromCache === false && scripted.callCount === 1,
      `engine calls=${scripted.callCount}, fromCache=${first.fromCache}, text=${JSON.stringify(first.text)}`,
    );

    // 2. cache hit on the identical repeat
    const second = await translator.translate({ text: 'Smoke probe one', sourceLanguage: 'en', targetLanguage: 'ar' });
    report(
      'identical repeat is a cache hit',
      second.fromCache === true && scripted.callCount === 1,
      `fromCache=${second.fromCache}, engine calls still ${scripted.callCount}`,
    );

    // 3. language correctness
    const zh = await translator.translate({ text: '你以为我是谁？', targetLanguage: 'ar' });
    report(
      'source language detected correctly',
      zh.detectedLanguage === 'zh',
      `detected=${zh.detectedLanguage} (pure Han must not be ja)`,
    );

    // 4. retry on a transient failure
    const flaky = new ScriptedEngine({ failTimes: 1, respond: () => 'نجح' });
    const retryTranslator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: tempDir,
        env: {
          TRANSLATION_ENGINE: 'flaky',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '3',
          RETRY_BASE_DELAY_MS: '1',
          RETRY_MAX_DELAY_MS: '2',
          RETRY_JITTER_RATIO: '0',
          REQUEST_TIMEOUT_MS: '2000',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('flaky', () => flaky),
      disableCache: true,
    });
    const retried = await retryTranslator.translate({ text: 'Retry probe', targetLanguage: 'ar' });
    report(
      'retry recovers from a transient failure',
      retried.text === 'نجح' && flaky.callCount === 2,
      `attempts=${flaky.callCount}, text=${JSON.stringify(retried.text)}`,
    );

    // 5. timeout
    const hanging = new ScriptedEngine({ hang: true });
    const hangTranslator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: tempDir,
        env: {
          TRANSLATION_ENGINE: 'hang',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '250',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('hang', () => hanging),
      disableCache: true,
    });
    const hangStart = Date.now();
    try {
      await hangTranslator.translate({ text: 'Hang probe', targetLanguage: 'ar' });
      report('timeout fires on a hanging engine', false, 'no error was raised');
    } catch (error) {
      const code = isTranslationError(error) ? error.code : 'UNKNOWN';
      report(
        'timeout fires on a hanging engine',
        code === 'TIMEOUT',
        `${code} after ${Date.now() - hangStart}ms`,
      );
    }

    // 6. cancellation
    const slow = new ScriptedEngine({ delayMs: 4000 });
    const cancelTranslator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: tempDir,
        env: {
          TRANSLATION_ENGINE: 'slow',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          REQUEST_TIMEOUT_MS: '9000',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('slow', () => slow),
      disableCache: true,
    });
    const token = new CancellationToken();
    const cancelPromise = cancelTranslator.translate({ text: 'Cancel probe', targetLanguage: 'ar', token });
    setTimeout(() => token.cancel(), 50);
    try {
      await cancelPromise;
      report('cancellation aborts the request', false, 'request completed despite cancellation');
    } catch (error) {
      const code = isTranslationError(error) ? error.code : 'UNKNOWN';
      report('cancellation aborts the request', code === 'CANCELLED', `code=${code}`);
    }

    // 7. chapter end to end with a partial failure
    const partial = new ScriptedEngine({
      respond: (r) => {
        if (/height 165/.test(r.text)) {
          throw new Error('segment unavailable');
        }
        return `ع:${r.text}`;
      },
    });
    const partialTranslator = createTranslator({
      config: loadConfig({
        readDotEnv: false,
        rootDir: tempDir,
        env: {
          TRANSLATION_ENGINE: 'partial',
          LOG_LEVEL: 'silent',
          CACHE_ENABLED: 'false',
          RETRY_MAX_ATTEMPTS: '1',
          RETRY_BASE_DELAY_MS: '1',
        },
      }),
      logger: silentLogger,
      registry: new EngineRegistry().register('partial', () => partial),
      disableCache: true,
    });
    const chapter = await partialTranslator.translateChapter({
      segments: [
        { id: 'p1', text: 'Are you serious right now?!' },
        { id: 'p2', text: 'minimum height 165 cm, age 18' },
        { id: 'p3', text: 'The rain stopped around midnight.' },
      ],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    report(
      'chapter survives one failing segment',
      chapter.degraded === true && chapter.progress.failedSegments === 1 && chapter.text.includes('serious'),
      `failed=${chapter.progress.failedSegments}, degraded=${chapter.degraded}, kept original=${chapter.text.includes('height 165')}`,
    );

    // 8. REST on a real booted server
    server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: silentLogger });
    const restResponse = await fetch(`${server.url}/translate`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: 'REST smoke probe', sourceLanguage: 'en', targetLanguage: 'ar' }),
    });
    const restBody = (await restResponse.json()) as Record<string, unknown>;
    report(
      'REST /translate on a booted server',
      restResponse.status === 200 && typeof restBody.text === 'string',
      `status=${restResponse.status}, direction=${restBody.direction}`,
    );

    const restCached = (await (
      await fetch(`${server.url}/translate`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ text: 'REST smoke probe', sourceLanguage: 'en', targetLanguage: 'ar' }),
      })
    ).json()) as Record<string, unknown>;
    report('REST cache hit on repeat', restCached.fromCache === true, `fromCache=${restCached.fromCache}`);

    const badRequest = await fetch(`${server.url}/translate`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: 'x' }),
    });
    report(
      'REST validation error maps to 400',
      badRequest.status === 400,
      `status=${badRequest.status}`,
    );

    const uiResponse = await fetch(`${server.url}/`);
    report('UI is served by the same server', uiResponse.status === 200, `status=${uiResponse.status}`);

    // ---------- live translation ----------
    heading('2. live translation (real network)');

    if (offline) {
      skip('live translation', '--offline requested');
    } else {
      await runLiveChecks(tempDir);
    }

    // ---------- summary ----------
    heading('summary');
    const failed = steps.filter((s) => s.status === 'FAIL');
    const skipped = steps.filter((s) => s.status === 'SKIP');
    for (const step of steps) {
      process.stdout.write(`${step.status.padEnd(4)} ${step.name}\n`);
    }
    process.stdout.write(
      `\n${steps.length} checks: ${steps.length - failed.length - skipped.length} passed, ${failed.length} failed, ${skipped.length} skipped\n`,
    );
    process.exitCode = failed.length > 0 ? 1 : 0;
  } finally {
    await server?.close();
    await translator?.flushCache();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

async function runLiveChecks(tempDir: string): Promise<void> {
  const config = loadConfig({
    readDotEnv: false,
    rootDir: tempDir,
    env: {
      TRANSLATION_ENGINE: 'mymemory',
      LOG_LEVEL: 'silent',
      CACHE_ENABLED: 'true',
      CACHE_DIR: tempDir,
      RETRY_MAX_ATTEMPTS: '3',
      RETRY_BASE_DELAY_MS: '500',
      RETRY_MAX_DELAY_MS: '4000',
      RETRY_JITTER_RATIO: '0',
      REQUEST_TIMEOUT_MS: '20000',
      CHAPTER_DEADLINE_MS: '120000',
      MYMEMORY_MAX_QUERY_CHARS: '450',
      ...(process.env.MYMEMORY_CONTACT_EMAIL ? { MYMEMORY_CONTACT_EMAIL: process.env.MYMEMORY_CONTACT_EMAIL } : {}),
    },
  });
  const live = createTranslator({ config, logger: silentLogger });

  // DeepL is optional. An unconfigured optional provider must not be reported as a
  // failure: the platform routes around it by design, and a red line here reads as
  // "the product is broken" when the correct state is "a BYOK key was not supplied".
  const deepl = live.describeEngines().find((e) => e.id === 'deepl');
  if (deepl?.available === true) {
    report('DeepL credential', true, 'configured; DeepL will be benchmarked');
  } else {
    skip('DeepL credential', deepl?.reason ?? 'unavailable (optional provider, not configured)');
  }

  const cases: Array<[string, string, string]> = [
    ['English -> Arabic', 'en', 'Are you serious right now?!'],
    ['Japanese -> Arabic', 'ja', 'そんなわけないだろ。'],
    ['Chinese -> Arabic', 'zh', '你以为我是谁？'],
    ['Korean -> Arabic', 'ko', '안녕하세요? 반갑습니다.'],
  ];

  let quotaBlocked = false;

  for (const [label, source, text] of cases) {
    if (quotaBlocked) {
      skip(label, 'MyMemory free daily quota already exhausted');
      continue;
    }
    try {
      const result = await live.translate({ text, sourceLanguage: source, targetLanguage: 'ar' });
      const good =
        result.fromCache === false &&
        containsArabic(result.text) &&
        result.text.trim() !== text.trim() &&
        result.engine === 'mymemory';
      report(
        label,
        good,
        `src="${text}" -> out="${result.text}" (quality ${result.quality?.score}, ${result.elapsedMs}ms)`,
      );
    } catch (error) {
      if (isTranslationError(error) && error.code === 'QUOTA_EXCEEDED') {
        quotaBlocked = true;
        skip(label, 'MyMemory free daily quota exhausted (6h reset, shared per IP)');
      } else {
        const message = error instanceof Error ? error.message.slice(0, 120) : String(error);
        report(label, false, `${isTranslationError(error) ? error.code : 'UNKNOWN'}: ${message}`);
      }
    }
  }

  if (!quotaBlocked) {
    // Cache sequence against the real engine.
    const probe = `Smoke cache probe ${Date.now()}: the lantern flickered twice.`;
    await live.clearCache();
    const c1 = await live.translate({ text: probe, sourceLanguage: 'en', targetLanguage: 'ar' });
    const c2 = await live.translate({ text: probe, sourceLanguage: 'en', targetLanguage: 'ar' });
    report(
      'live cache sequence (call1 real, call2 hit)',
      c1.fromCache === false && c2.fromCache === true && c1.text === c2.text,
      `call1 fromCache=${c1.fromCache}, call2 fromCache=${c2.fromCache}`,
    );

    const chapter = await live.translateChapter({
      segments: [
        { id: 'p1', text: 'Are you serious right now?!' },
        { id: 'p2', text: "Don't worry, I'll be fine." },
        { id: 'p3', text: 'The rain stopped somewhere around midnight.' },
      ],
      sourceLanguage: 'en',
      targetLanguage: 'ar',
      concurrency: 1,
    });
    report(
      'live chapter end to end',
      chapter.degraded === false && chapter.segments.every((s) => containsArabic(s.translated)),
      chapter.segments.map((s) => `${s.id}="${s.translated}"`).join(' | '),
    );
  }

  await live.flushCache();
}

main().catch((error: unknown) => {
  process.stderr.write(`smoke run crashed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});

