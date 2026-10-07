/**
 * Platform integration tests: pipeline composition, auth, entitlements, BYOK,
 * jobs, prewarm, load harness.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { openMemoryDatabase } from '../../src/platform/db/database';
import { KnowledgeRepository } from '../../src/platform/knowledge/repository';
import {
  GlossaryRepository,
  TranslationMemoryRepository,
} from '../../src/platform/memory/repository';
import { ResearchQueue } from '../../src/platform/research/queue';
import { ResearchAgent } from '../../src/platform/research/agent';
import { AuthRepository, DEFAULT_PLANS, generateApiKey, hashApiKey, safeEqual } from '../../src/platform/auth/repository';
import { JobQueue, QueueSaturatedError } from '../../src/platform/jobs/queue';
import { FeatureFlags } from '../../src/platform/flags';
import { MetricsRegistry, METRICS } from '../../src/platform/metrics';
import { TranslationPipeline } from '../../src/platform/pipeline/translationPipeline';
import { Prewarmer } from '../../src/platform/prewarm';
import { drainResearch, buildPlatform } from '../../src/platform/index';
import { runLoadTest } from '../../src/platform/loadtest/harness';
import { startServer } from '../../src/server/server';
import { createTranslator } from '../../src/translator/translator';
import { loadConfig } from '../../src/config/index';
import { silentLogger } from '../../src/core/logger';
import { EngineRegistry } from '../../src/engine/registry';
import { DeterministicEngine } from '../../src/platform/engine/local/engine';

interface Harness {
  db: ReturnType<typeof openMemoryDatabase>;
  knowledge: KnowledgeRepository;
  memory: TranslationMemoryRepository;
  glossary: GlossaryRepository;
  research: ResearchQueue;
  agent: ResearchAgent;
  auth: AuthRepository;
  jobs: JobQueue;
  metrics: MetricsRegistry;
  flags: FeatureFlags;
}

function harness(options: { flags?: Partial<Record<string, boolean>> } = {}): Harness {
  const db = openMemoryDatabase();
  const knowledge = new KnowledgeRepository(db);
  const memory = new TranslationMemoryRepository(db);
  const glossary = new GlossaryRepository(db);
  const research = new ResearchQueue(db);
  const auth = new AuthRepository(db);
  const jobs = new JobQueue(db);
  const metrics = new MetricsRegistry();
  const flags = new FeatureFlags(options.flags as never);
  auth.seedPlans();
  const agent = new ResearchAgent({
    db, queue: research, knowledge, metrics: undefined as never,
    collector: { async collect() { return []; } },
  } as never);
  return { db, knowledge, memory, glossary, research, agent, auth, jobs, metrics, flags };
}

describe('translation pipeline', () => {
  type TranslateFn = (input: {
    text: string;
    hints?: Record<string, string>;
  }) => Promise<{ text: string; engine: string; fromCache: boolean }>;

  function pipelineWith(h: Harness, translate: TranslateFn) {
    return new TranslationPipeline({
      translate: translate as never,
      knowledge: h.knowledge,
      memory: h.memory,
      glossary: h.glossary,
      research: h.research,
      flags: h.flags,
      metrics: h.metrics,
      modelId: 'translategemma-12b',
      modelVersion: '2026-01',
    });
  }

  const stub = (text = 'مترجم'): TranslateFn => async () => ({ text, engine: 'local', fromCache: false });

  it('translates through the model when nothing is known', async () => {
    const h = harness();
    const pipeline = pipelineWith(h, stub());
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '本気なのか？' },
      engine: 'local',
    });
    assert.equal(result.text, 'مترجم');
    assert.equal(result.source, 'model');
    assert.equal(result.cacheContext.modelId, 'translategemma-12b');
    assert.equal(result.cacheContext.modelVersion, '2026-01');
    h.db.close();
  });

  it('serves a high-confidence memory hit without calling the model', async () => {
    const h = harness();
    h.memory.store({
      sourceLanguage: 'ja', targetLanguage: 'ar',
      sourceText: 'そんなわけないだろ', targetText: 'هذا مستحيل.', confidence: 0.96,
    });
    let called = false;
    const pipeline = pipelineWith(h, async () => {
      called = true;
      return { text: 'from model', engine: 'local', fromCache: false };
    });
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: 'そんなわけないだろ' },
      engine: 'local',
    });
    assert.equal(result.text, 'هذا مستحيل.');
    assert.equal(result.source, 'memory');
    assert.equal(called, false, 'a high-confidence TM hit must skip the model');
    assert.equal(h.metrics.total(METRICS.memoryHits), 1);
    h.db.close();
  });

  it('treats a below-threshold memory hit as advisory guidance, not an answer', async () => {
    const h = harness();
    h.memory.store({
      sourceLanguage: 'ja', targetLanguage: 'ar',
      sourceText: 'x', targetText: 'ترجمة سابقة', confidence: 0.6,
    });
    let seenHints = '';
    const pipeline = pipelineWith(h, async (input) => {
      seenHints = `${input.hints?.instructions ?? ''}|${input.hints?.previousTranslation ?? ''}`;
      return { text: 'from model', engine: 'local', fromCache: false };
    });
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: 'x' },
      engine: 'local',
    });
    assert.equal(result.text, 'from model', 'a weak hit must never become the answer');
    assert.equal(result.memoryHit, true, 'the weak hit is recorded');
    assert.equal(result.source, 'memory+model');
    assert.match(seenHints, /previous translation/, 'it is offered to the model instead');
    assert.equal(
      h.metrics.counter(METRICS.memoryHits, { authoritative: 'no' }),
      1,
      'a weak hit is counted as advisory, not authoritative',
    );
    h.db.close();
  });

  it('ignores a memory entry below the advisory floor entirely', async () => {
    const h = harness();
    h.memory.store({
      sourceLanguage: 'ja', targetLanguage: 'ar',
      sourceText: 'x', targetText: 'garbage', confidence: 0.2,
    });
    const pipeline = pipelineWith(h, stub('from model'));
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: 'x' },
      engine: 'local',
    });
    assert.equal(result.memoryHit, false, '0.2 confidence is too weak to inform anything');
    h.db.close();
  });

  it('passes glossary instructions to the model and repairs the output', async () => {
    const h = harness();
    h.glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '魔王',
      defaultTranslation: 'ملك الشياطين', variants: ['الملك الشرير'], mode: 'force', priority: 10,
    });
    let seenInstructions = '';
    const pipeline = pipelineWith(h, async (input) => {
      seenInstructions = input.hints?.instructions ?? '';
      return { text: 'هزم الملك الشرير', engine: 'local', fromCache: false };
    });
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '魔王を倒した' },
      engine: 'local',
    });
    assert.match(seenInstructions, /魔王/);
    assert.match(seenInstructions, /ملك الشياطين/);
    assert.equal(result.text, 'هزم ملك الشياطين', 'the output is repaired after the model');
    assert.equal(result.glossaryApplied, 1);
    h.db.close();
  });

  it('retrieves knowledge into the prompt', async () => {
    const h = harness();
    h.knowledge.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term',
      term: '先輩', translation: 'السينباي', confidence: 0.9,
    });
    let seen = '';
    const pipeline = pipelineWith(h, async (input) => {
      seen = input.hints?.instructions ?? '';
      return { text: 'x', engine: 'local', fromCache: false };
    });
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '先輩は強い' },
      engine: 'local',
    });
    assert.match(seen, /先輩/);
    assert.equal(result.knowledgeHits, 1);
    h.db.close();
  });

  it('enqueues research in the background without blocking', async () => {
    const h = harness({ flags: { ENABLE_RESEARCH_AGENT: true } });
    const pipeline = pipelineWith(h, stub());
    const result = await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '未知の言葉' },
      engine: 'local',
      researchUnknown: true,
    });
    assert.equal(result.text, 'مترجم', 'the response is not delayed by research');
    assert.equal(h.research.stats().queued >= 1, true);
    h.db.close();
  });

  it('skips research entirely when the flag is off', async () => {
    const h = harness({ flags: { ENABLE_RESEARCH_AGENT: false } });
    const pipeline = pipelineWith(h, stub());
    await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '未知の言葉' },
      engine: 'local',
      researchUnknown: true,
    });
    assert.equal(h.research.stats().total, 0);
    h.db.close();
  });

  it('honours a character-scoped glossary and knowledge', async () => {
    const h = harness();
    h.knowledge.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term',
      term: '先輩', translation: 'البطل', confidence: 0.9, characterId: 'c1',
    });
    let seen = '';
    const pipeline = pipelineWith(h, async (input) => {
      seen = input.hints?.instructions ?? '';
      return { text: 'x', engine: 'local', fromCache: false };
    });
    await pipeline.translate({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '先輩だ', characterId: 'c1' },
      engine: 'local',
    });
    assert.match(seen, /البطل/);
    h.db.close();
  });
});

describe('API keys', () => {
  it('stores only a hash and a prefix', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    const rows = h.db.all<Record<string, unknown>>('SELECT * FROM api_keys');
    const serialized = JSON.stringify(rows);
    assert.ok(!serialized.includes(created.plaintext), 'the plaintext must never be stored');
    assert.ok(rows[0]!.key_hash === hashApiKey(created.plaintext));
    assert.ok(created.record.prefix.length < created.plaintext.length);
    h.db.close();
  });

  it('authenticates a presented key and refuses a wrong one', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    assert.ok(h.auth.authenticate(created.plaintext));
    assert.equal(h.auth.authenticate('tp_wrongwrongwrongwrong'), undefined);
    assert.equal(h.auth.authenticate(''), undefined);
    h.db.close();
  });

  it('revokes and rotates', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    h.auth.setKeyStatus(created.record.id, 'disabled');
    assert.equal(h.auth.authenticate(created.plaintext), undefined);

    const rotated = h.auth.rotateApiKey(created.record.id);
    assert.equal(h.auth.authenticate(created.plaintext), undefined, 'the old key stops working');
    assert.ok(h.auth.authenticate(rotated.plaintext));
    h.db.close();
  });

  it('refuses a revoked key', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    h.auth.setKeyStatus(created.record.id, 'revoked');
    assert.equal(h.auth.authenticate(created.plaintext), undefined);
    assert.throws(() => h.auth.rotateApiKey(created.record.id), /cannot rotate a revoked key/);
    h.db.close();
  });

  it('expires a key at its expiry date', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({
      userId: 'u1', name: 'test', expiresAt: new Date(Date.now() - 1000).toISOString(),
    });
    assert.equal(h.auth.authenticate(created.plaintext), undefined);
    h.db.close();
  });

  it('refuses a key belonging to a disabled user', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    h.auth.setUserStatus('u1', 'disabled', 'abuse');
    assert.equal(h.auth.authenticate(created.plaintext), undefined);
    h.db.close();
  });

  it('records usage and last-used time', () => {
    const h = harness();
    h.auth.createUser({ id: 'u1', planId: 'FREE' });
    const created = h.auth.createApiKey({ userId: 'u1', name: 'test' });
    h.auth.authenticate(created.plaintext);
    const record = h.auth.getKey(created.record.id);
    assert.equal(record?.usageCount, 1);
    assert.ok(record?.lastUsedAt);
    h.db.close();
  });

  it('generates high-entropy keys and compares in constant time', () => {
    const a = generateApiKey();
    const b = generateApiKey();
    assert.notEqual(a.plaintext, b.plaintext);
    assert.ok(a.plaintext.length >= 30);
    assert.equal(safeEqual('abc', 'abc'), true);
    assert.equal(safeEqual('abc', 'abd'), false);
    assert.equal(safeEqual('abc', 'abcd'), false);
  });
});

describe('plans and entitlements', () => {
  it('seeds the documented plans', () => {
    const h = harness();
    const ids = h.auth.listPlans().map((p) => p.id).sort();
    assert.deepEqual(ids, DEFAULT_PLANS.map((p) => p.id).sort());
    h.db.close();
  });

  it('gives ADMIN and SHURA unlimited product quota but real infra limits', () => {
    const h = harness();
    for (const planId of ['ADMIN', 'SHURA', 'BYOK']) {
      const user = h.auth.createUser({ id: `u-${planId}`, planId });
      const decision = h.auth.checkEntitlement(user);
      assert.equal(decision.allowed, true, planId);
      assert.equal(decision.limit, undefined, `${planId} must have no daily quota`);
      assert.ok(decision.maxParallel >= 2, `${planId} must still be concurrency limited`);
      assert.ok(decision.maxCharsPerRequest > 0);
    }
    h.db.close();
  });

  it('enforces the FREE daily quota', () => {
    const h = harness();
    const user = h.auth.createUser({ id: 'u-free', planId: 'FREE' });
    const plan = h.auth.plan('FREE')!;
    for (let i = 0; i < plan.dailyRequests!; i += 1) {
      h.auth.recordUsage({ userId: user.id });
    }
    const decision = h.auth.checkEntitlement(user);
    assert.equal(decision.allowed, false);
    assert.match(decision.reason ?? '', /daily quota exhausted/);
    h.db.close();
  });

  it('applies an admin override to the quota', () => {
    const h = harness();
    const user = h.auth.createUser({ id: 'u-api', planId: 'API_USER' });
    h.auth.setUserLimits('u-api', { daily: 1 });
    assert.equal(h.auth.checkEntitlement(user).allowed, true);
    h.auth.recordUsage({ userId: user.id });
    assert.equal(h.auth.checkEntitlement(user).allowed, false);
    h.db.close();
  });

  it('rejects a payload over the plan character limit', () => {
    const h = harness();
    const user = h.auth.createUser({ id: 'u-free', planId: 'FREE' });
    const decision = h.auth.checkEntitlement(user, { chars: 100_000 });
    assert.equal(decision.allowed, false);
    assert.match(decision.reason ?? '', /character limit/);
    h.db.close();
  });

  it('refuses a disabled account', () => {
    const h = harness();
    const user = h.auth.createUser({ id: 'u1', planId: 'PREMIUM' });
    h.auth.setUserStatus('u1', 'disabled', 'abuse');
    assert.equal(h.auth.checkEntitlement(user).allowed, false);
    h.db.close();
  });

  it('BYOK is exempt from product quota because it spends the user own provider', () => {
    const h = harness();
    const user = h.auth.createUser({ id: 'u-byok', planId: 'BYOK' });
    for (let i = 0; i < 50; i += 1) {
      h.auth.recordUsage({ userId: user.id });
    }
    const decision = h.auth.checkEntitlement(user);
    assert.equal(decision.allowed, true, 'BYOK must not be capped by our product quota');
    h.db.close();
  });

  it('records an audit trail', () => {
    const h = harness();
    h.auth.audit({ actor: 'admin', action: 'user.disable', subjectType: 'user', subjectId: 'u1' });
    const trail = h.auth.auditTrail({ subjectType: 'user' });
    assert.equal(trail.length, 1);
    assert.equal(trail[0]?.action, 'user.disable');
    h.db.close();
  });
});

describe('job queue', () => {
  it('claims by priority then age', () => {
    const h = harness();
    const jobs = h.jobs;
    jobs.enqueue({ kind: 'translate', payload: { n: 1 }, priority: 8 });
    jobs.enqueue({ kind: 'translate', payload: { n: 2 }, priority: 1 });
    assert.equal(jobs.claimNext()?.priority, 1, 'higher priority (lower number) goes first');
    h.db.close();
  });

  it('deduplicates identical in-flight work', () => {
    const h = harness();
    const first = h.jobs.enqueue({ kind: 'translate', payload: {}, dedupKey: 'k1' });
    const second = h.jobs.enqueue({ kind: 'translate', payload: {}, dedupKey: 'k1' });
    assert.equal(first.deduplicated, false);
    assert.equal(second.deduplicated, true);
    assert.equal(second.job.id, first.job.id);
    h.db.close();
  });

  it('retries then fails terminally', () => {
    const h = harness();
    const { job } = h.jobs.enqueue({ kind: 'translate', payload: {}, maxAttempts: 2 });
    h.jobs.claimNext();
    assert.equal(h.jobs.fail(job.id, 'boom').status, 'queued');
    h.jobs.claimNext();
    assert.equal(h.jobs.fail(job.id, 'boom').status, 'failed');
    h.db.close();
  });

  it('cancels queued work immediately and flags running work', () => {
    const h = harness();
    const queued = h.jobs.enqueue({ kind: 'translate', payload: {} });
    assert.equal(h.jobs.cancel(queued.job.id).status, 'cancelled');

    const running = h.jobs.enqueue({ kind: 'translate', payload: {} });
    h.jobs.claimNext();
    const cancelled = h.jobs.cancel(running.job.id);
    assert.equal(cancelled.status, 'running', 'a running job is flagged, not killed');
    assert.equal(cancelled.cancelRequested, true);
    assert.equal(h.jobs.isCancellationRequested(running.job.id), true);
    h.db.close();
  });

  it('reports depth for backpressure', () => {
    const h = harness();
    h.jobs.enqueue({ kind: 'translate', payload: {} });
    h.jobs.enqueue({ kind: 'translate', payload: {} });
    assert.equal(h.jobs.stats().depth, 2);
    assert.equal(new QueueSaturatedError(2, 1).status, 503);
    h.db.close();
  });

  it('returns stale running jobs to the queue after a crash', () => {
    const h = harness();
    const { job } = h.jobs.enqueue({ kind: 'translate', payload: {} });
    h.jobs.claimNext();
    h.db.run("UPDATE jobs SET started_at = '2000-01-01T00:00:00.000Z' WHERE id = ?", [job.id]);
    assert.equal(h.jobs.requeueStale(1), 1);
    assert.equal(h.jobs.get(job.id)?.status, 'queued');
    h.db.close();
  });
});

describe('prewarming', () => {
  it('creates a batch and queues research, prioritising by category', () => {
    const h = harness();
    const prewarmer = new Prewarmer(h.db, h.research);
    const batch = prewarmer.createBatch({
      sourceLanguage: 'ja', targetLanguage: 'ar',
      sources: [
        { category: 'honorific', terms: ['先輩', '先生'] },
        { category: 'term', terms: ['一般的'] },
      ],
    });
    assert.equal(batch.stats.pending, 3);
    assert.equal(h.research.stats().queued, 3);
    const jobs = h.research.list();
    const honorificJob = jobs.find((j) => j.term === '先輩');
    const termJob = jobs.find((j) => j.term === '一般的');
    assert.ok((honorificJob?.priority ?? 99) < (termJob?.priority ?? 0), 'honorifics are researched first');
    h.db.close();
  });

  it('is idempotent per batch', () => {
    const h = harness();
    const prewarmer = new Prewarmer(h.db, h.research);
    const sources = [{ category: 'honorific' as const, terms: ['先輩'] }];
    prewarmer.createBatch({ sourceLanguage: 'ja', targetLanguage: 'ar', sources, batchId: 'fixed' });
    const second = prewarmer.createBatch({ sourceLanguage: 'ja', targetLanguage: 'ar', sources, batchId: 'fixed' });
    assert.equal(second.stats.pending, 0, 're-running the same batch adds nothing');
    assert.equal(second.stats.skippedDuplicate, 1);
    assert.equal(h.research.stats().total, 1, 'and no second research job is created');
    h.db.close();
  });

  it('ships a starter corpus for all four source languages', () => {
    const languages = new Set(Prewarmer.starterCorpus().map((s) => s.sourceLanguage));
    for (const language of ['en', 'ja', 'zh', 'ko']) {
      assert.ok(languages.has(language), `starter corpus must cover ${language}`);
    }
  });
});

describe('research draining', () => {
  it('reports how many jobs it processed', async () => {
    const platform = buildPlatform({ flags: new FeatureFlags({ ENABLE_RESEARCH_AGENT: true }) });
    platform.research.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'a' });
    platform.research.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'b' });
    const processed = await drainResearch(platform, { max: 10 });
    assert.equal(processed, 2);
    assert.equal(await drainResearch(platform, { max: 10 }), 0, 'a drained queue stops immediately');
    platform.close();
  });
});

describe('load harness', () => {
  it('drives a real server and reports honest numbers', async () => {
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
    try {
      const result = await runLoadTest({
        baseUrl: server.url,
        concurrency: 10,
        requestsPerUser: 3,
        method: 'POST',
        path: '/translate',
        body: (index) => ({ text: `probe ${index}`, sourceLanguage: 'en', targetLanguage: 'ar' }),
      });
      assert.equal(result.completed, 30);
      assert.equal(result.failed, 0);
      assert.equal(result.succeeded, 30);
      assert.ok(result.requestsPerSecond > 0);
      assert.ok(result.latency.p50 >= 0);
      assert.ok(
        result.notes.some((n) => n.includes('not a claim about production capacity')),
        'every result must carry the environment caveat',
      );
    } finally {
      await server.close();
    }
  });
});

describe('core translation without any provider', () => {
  it('serves a request using only a local engine', async () => {
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
    const result = await translator.translate({
      text: '本気なのか？',
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
    });
    assert.equal(result.engine, 'local-deterministic');
    assert.ok(result.text.length > 0);
  });
});