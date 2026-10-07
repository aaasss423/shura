/**
 * Server entry point. Boots the translator and the REST API.
 */

import { loadConfig, describeConfig } from '../config/index';
import { createLogger } from '../core/logger';
import { createTranslator } from '../translator/translator';
import { startServer } from './server';

async function main(): Promise<void> {
  const config = loadConfig();
  const logger = createLogger({ level: config.logLevel });

  const translator = createTranslator({ config, logger });

  // Load engine API keys from the environment or the encrypted runtime store.
  // Values stay in memory; only status is ever logged.
  const secrets = await translator.loadSecrets();
  for (const secret of secrets) {
    logger.info('engine credential status', {
      engine: secret.engine,
      configured: secret.configured,
      source: secret.source,
    });
  }

  const started = await startServer({
    translator,
    host: config.server.host,
    port: config.server.port,
    logger,
  });

  logger.info('translation platform ready', {
    url: started.url,
    config: describeConfig(config),
  });

  const shutdown = (signal: string): void => {
    logger.info('shutting down', { signal });
    void (async () => {
      await translator.flushCache();
      await started.close();
      process.exit(0);
    })();
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((error: unknown) => {
  process.stderr.write(`failed to start server: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});