/**
 * Load test CLI.
 *
 * Boots the platform's own REST server, then drives it at increasing
 * concurrency and prints what was measured. Numbers describe this machine only.
 */

import { runLoadTest, formatLoadResult, LOAD_SCALES } from '../platform/loadtest/harness';
import { startServer } from '../server/server';
import { createTranslator } from '../translator/translator';
import { loadConfig } from '../config/index';
import { silentLogger } from '../core/logger';
import { EngineRegistry } from '../engine/registry';
import { DeterministicEngine } from '../platform/engine/local/engine';

async function main(): Promise<void> {
  const registry = new EngineRegistry().register(
    'local-deterministic',
    () => new DeterministicEngine() as never,
  );
  const translator = createTranslator({
    config: loadConfig({
      readDotEnv: false,
      rootDir: process.cwd(),
      env: {
        TRANSLATION_ENGINE: 'local-deterministic',
        LOG_LEVEL: 'silent',
        CACHE_ENABLED: 'false',
        RETRY_MAX_ATTEMPTS: '1',
      },
    }),
    logger: silentLogger,
    registry,
    disableCache: true,
  });

  const server = await startServer({ translator, host: '127.0.0.1', port: 0, logger: silentLogger });
  process.stdout.write(`load testing ${server.url}\n`);

  try {
    for (const concurrency of LOAD_SCALES) {
      const result = await runLoadTest({
        baseUrl: server.url,
        concurrency,
        requestsPerUser: 4,
        method: 'POST',
        path: '/translate',
        warmupRequests: 5,
        timeoutMs: 120_000,
        body: (index) => ({
          text: `Load probe sentence number ${index}.`,
          sourceLanguage: 'en',
          targetLanguage: 'ar',
        }),
      });
      process.stdout.write(`\n--- concurrency ${concurrency} ---\n${formatLoadResult(result)}\n`);
    }
  } finally {
    await server.close();
  }
}

main().catch((error: unknown) => {
  process.stderr.write(`load test failed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});
