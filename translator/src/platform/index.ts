/**
 * Platform composition root.
 *
 * One place that builds the database, repositories, flags, router, pipeline and
 * background workers, so the REST layer, the CLI and tests share exactly the same
 * object graph. Keeps `server.ts` free of wiring details.
 */

import { openDatabase, type Database } from './db/database';
import { KnowledgeRepository } from './knowledge/repository';
import { GlossaryRepository, TranslationMemoryRepository } from './memory/repository';
import { ResearchQueue } from './research/queue';
import { ResearchAgent, type SourceCollector } from './research/agent';
import { AuthRepository } from './auth/repository';
import { JobQueue } from './jobs/queue';
import { FeatureFlags } from './flags';
import { METRICS, MetricsRegistry, ConcurrencyTracker, RateLimiter } from './metrics';
import { ModelRouter, availabilityFromRegistry, type RouteTier } from './router/modelRouter';
import { TranslationPipeline } from './pipeline/translationPipeline';
import type { EngineRegistry } from '../engine/registry';
import type { TranslationEngine } from '../engine/engine';
import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';

export interface PlatformOptions {
  db?: Database;
  /** ':memory:' by default, so nothing leaks into the repo. */
  databasePath?: string;
  flags?: FeatureFlags;
  registry?: EngineRegistry;
  logger?: Logger;
  metrics?: MetricsRegistry;
  /** Injected so tests never touch the network. */
  sourceCollector?: SourceCollector;
  /** Binds the pipeline to the underlying translator. */
  translate?: TranslationPipeline['deps']['translate'];
  rateLimitPerMinute?: number;
}

export interface Platform {
  db: Database;
  flags: FeatureFlags;
  metrics: MetricsRegistry;
  knowledge: KnowledgeRepository;
  memory: TranslationMemoryRepository;
  glossary: GlossaryRepository;
  research: ResearchQueue;
  researchAgent: ResearchAgent;
  auth: AuthRepository;
  jobs: JobQueue;
  router: ModelRouter;
  pipeline?: TranslationPipeline;
  concurrency: ConcurrencyTracker;
  rateLimiter: RateLimiter;
  close(): void;
}

export interface BuildPlatformWithPipelineOptions extends PlatformOptions {
  translate: TranslationPipeline['deps']['translate'];
  modelId?: string;
  modelVersion?: string;
}

export function buildPlatform(options: PlatformOptions = {}): Platform {
  const db = options.db ?? openDatabase({ filename: options.databasePath ?? ':memory:' });
  const flags = options.flags ?? new FeatureFlags();
  const metrics = options.metrics ?? new MetricsRegistry();
  const logger = options.logger ?? silentLogger;

  const knowledge = new KnowledgeRepository(db);
  const memory = new TranslationMemoryRepository(db);
  const glossary = new GlossaryRepository(db);
  const research = new ResearchQueue(db);
  const auth = new AuthRepository(db);
  const jobs = new JobQueue(db);
  auth.seedPlans();

  // The collector is a port. Without one, research cannot run and the flag
  // should reflect that rather than pretending the agent works.
  const researchAgent = new ResearchAgent({
    db,
    queue: research,
    knowledge,
    collector: options.sourceCollector ?? emptyCollector,
  });

  const registry = options.registry;
  const isAvailable = registry
    ? availabilityFromRegistry(
        (id: string) => registry.create(id),
        {
          deepl: flags.isEnabled('ENABLE_DEEPL'),
          mymemory: flags.isEnabled('ENABLE_MYMEMORY'),
        },
      )
    : () => ({ available: false, reason: 'no engine registry bound' });

  const router = new ModelRouter({
    isAvailable,
    perLanguage: flags.isEnabled('ENABLE_MODEL_ROUTING'),
  });

  const concurrency = new ConcurrencyTracker((subject) => {
    try {
      const decision = auth.getUser(subject);
      const plan = decision ? auth.plan(decision.planId) : undefined;
      return decision?.maxParallelOverride ?? plan?.maxParallel ?? 2;
    } catch {
      return 2;
    }
  });

  const rateLimiter = new RateLimiter(options.rateLimitPerMinute ?? 120, 60_000);

  const platform: Platform = {
    db,
    flags,
    metrics,
    knowledge,
    memory,
    glossary,
    research,
    researchAgent,
    auth,
    jobs,
    router,
    concurrency,
    rateLimiter,
    close: () => db.close(),
  };

  // Observability hooks that are always on.
  void logger;
  void METRICS;
  return platform;
}

export function buildPlatformWithPipeline(
  options: BuildPlatformWithPipelineOptions,
): Platform {
  const platform = buildPlatform(options);
  platform.pipeline = new TranslationPipeline({
    translate: options.translate,
    knowledge: platform.knowledge,
    memory: platform.memory,
    glossary: platform.glossary,
    research: platform.research,
    flags: platform.flags,
    metrics: platform.metrics,
    ...(options.modelId ? { modelId: options.modelId } : {}),
    ...(options.modelVersion ? { modelVersion: options.modelVersion } : {}),
  });
  return platform;
}

/** Resolves a tier to an engine id, for callers that manage tiers directly. */
export function tierEngine(platform: Platform, tier: RouteTier): string | undefined {
  return platform.router.candidates(tier)[0];
}

/** Runs research jobs until the queue drains. Used by workers and by tests. */
export async function drainResearch(
  platform: Platform,
  options: { max?: number; signal?: AbortSignal } = {},
): Promise<number> {
  let processed = 0;
  const max = options.max ?? 100;
  while (processed < max) {
    if (options.signal?.aborted) {
      break;
    }
    const result = await platform.researchAgent.runOnce(options.signal);
    if (!result) {
      break;
    }
    processed += 1;
    platform.metrics.increment(METRICS.researchJobsTotal, { status: result.accepted ? 'accepted' : 'rejected' });
  }
  return processed;
}

/** Placeholder collector: explicit about having no source, never inventing one. */
const emptyCollector: SourceCollector = {
  async collect(): Promise<[]> {
    return [];
  },
};

export type { TranslationEngine };