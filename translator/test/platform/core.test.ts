/**
 * Platform unit tests: database, knowledge, memory, glossary, context,
 * research queue, research agent, flags, router, metrics.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { openMemoryDatabase, applyMigrations } from '../../src/platform/db/database';
import { loadMigrations } from '../../src/platform/db/migrations';
import { KnowledgeRepository, normalizeTerm } from '../../src/platform/knowledge/repository';
import {
  GlossaryRepository,
  TranslationMemoryRepository,
  enforceGlossary,
  normalizeForMemory,
} from '../../src/platform/memory/repository';
import { ResearchQueue, researchKey } from '../../src/platform/research/queue';
import { ResearchAgent, SOURCE_CREDIBILITY, chooseConsensus, fingerprintUrl, type CollectedSource } from '../../src/platform/research/agent';
import { ContextAnalyzerless } from './helpers/noop';

const entriesOf = (glossary: GlossaryRepository) => glossary.list('ja', 'ar');
import {
  analyzeContext,
  contextCacheKey,
  detectRegister,
  extractWordLikeRuns,
} from '../../src/platform/context';
import { FeatureFlags, FeatureDisabledError, FLAG_NAMES } from '../../src/platform/flags';
import { ModelRouter, NoEngineAvailableError } from '../../src/platform/router/modelRouter';
import {
  ConcurrencyTracker,
  ConcurrencyLimitError,
  LatencyHistogram,
  MetricsRegistry,
  RateLimiter,
  RateLimitError,
} from '../../src/platform/metrics';
import {
  DeterministicEngine,
  LocalHttpEngine,
  stripPreamble,
  extractText,
} from '../../src/platform/engine/local/engine';

void ContextAnalyzerless;

describe('database and migrations', () => {
  it('creates the full schema', () => {
    const db = openMemoryDatabase();
    const tables = db
      .all<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table'")
      .map((r) => r.name);
    for (const expected of [
      'knowledge', 'translation_memory', 'glossary', 'characters', 'series',
      'research_jobs', 'research_sources', 'plans', 'users', 'entitlements',
      'api_keys', 'usage', 'translations', 'jobs', 'engines', 'model_routes',
      'prewarm_items', 'audit_logs', 'metrics_counters',
    ]) {
      assert.ok(tables.includes(expected), `missing table ${expected}`);
    }
    db.close();
  });

  it('is idempotent', () => {
    const db = openMemoryDatabase();
    assert.equal(applyMigrations(db, loadMigrations()), 0, 're-running migrations must apply nothing');
    db.close();
  });

  it('rolls back a failing transaction', () => {
    const db = openMemoryDatabase();
    assert.throws(() =>
      db.transaction(() => {
        db.run('INSERT INTO plans (id, name) VALUES (?, ?)', ['X', 'X']);
        throw new Error('boom');
      }),
    );
    assert.equal(db.get('SELECT COUNT(*) AS n FROM plans')?.n, 0);
    db.close();
  });
});

describe('knowledge base', () => {
  it('stores, resolves and bumps the version on update', () => {
    const db = openMemoryDatabase();
    const kb = new KnowledgeRepository(db);
    const first = kb.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', category: 'honorific',
      term: '先輩', translation: 'السينباي', confidence: 0.9, verificationState: 'verified',
    });
    assert.equal(first.version, 1);
    const second = kb.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', category: 'honorific',
      term: '先輩', translation: 'الزعيم', confidence: 0.95,
    });
    assert.equal(second.version, 2);
    assert.equal(second.translation, 'الزعيم');
    assert.equal(kb.count(), 1, 'an update must not create a duplicate');
    db.close();
  });

  it('rejects an unknown category', () => {
    const db = openMemoryDatabase();
    const kb = new KnowledgeRepository(db);
    assert.throws(
      () => kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'nope' as never, term: 'x', translation: 'y' }),
      /unknown knowledge category/,
    );
    db.close();
  });

  it('prefers a character-scoped entry over the series and global ones', () => {
    const db = openMemoryDatabase();
    const kb = new KnowledgeRepository(db);
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: '先輩', translation: 'عالمي', confidence: 0.99 });
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: '先輩', translation: 'سلسلة', confidence: 0.99, seriesId: 's1' });
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: '先輩', translation: 'شخصية', confidence: 0.1, characterId: 'c1' });

    assert.equal(kb.resolve('ja', 'ar', '先輩', { characterId: 'c1' })?.translation, 'شخصية');
    assert.equal(kb.resolve('ja', 'ar', '先輩', { seriesId: 's1' })?.translation, 'سلسلة');
    assert.equal(kb.resolve('ja', 'ar', '先輩')?.translation, 'عالمي');
    db.close();
  });

  it('resolves many terms in one batch and is order independent', () => {
    const db = openMemoryDatabase();
    const kb = new KnowledgeRepository(db);
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: '勇者', translation: 'البطل', confidence: 0.9 });
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: '魔王', translation: 'ملك الشياطين', confidence: 0.9 });
    const found = kb.resolveMany('ja', 'ar', ['魔王', '勇者', '勇者']);
    assert.equal(found.size, 2);
    assert.equal(found.get('魔王')?.translation, 'ملك الشياطين');
    db.close();
  });

  it('honours a confidence floor', () => {
    const db = openMemoryDatabase();
    const kb = new KnowledgeRepository(db);
    kb.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: 'x', translation: 'y', confidence: 0.3 });
    assert.equal(kb.resolve('ja', 'ar', 'x', { minConfidence: 0.5 }), undefined);
    assert.ok(kb.resolve('ja', 'ar', 'x', { minConfidence: 0.2 }));
    db.close();
  });

  it('normalizes terms for lookup without folding uncased scripts', () => {
    assert.equal(normalizeTerm('  先輩  '), '先輩');
    assert.equal(normalizeTerm('ＡＮＮＡ'), 'anna', 'NFKC + case fold for Latin');
    assert.equal(normalizeTerm(' 魔王 '), '魔王');
  });
});

describe('translation memory', () => {
  it('stores and looks up above the confidence floor', () => {
    const db = openMemoryDatabase();
    const tm = new TranslationMemoryRepository(db);
    tm.store({
      sourceLanguage: 'ja', targetLanguage: 'ar',
      sourceText: 'そんなわけないだろ', targetText: 'هذا مستحيل.',
      context: 'casual dialogue', confidence: 0.96,
    });
    const hit = tm.lookup('ja', 'ar', '  そんなわけないだろ  ');
    assert.equal(hit?.targetText, 'هذا مستحيل.');
    assert.equal(tm.lookup('ja', 'ar', 'x', { minConfidence: 0.99 }), undefined);
    db.close();
  });

  it('upserts rather than duplicating', () => {
    const db = openMemoryDatabase();
    const tm = new TranslationMemoryRepository(db);
    tm.store({ sourceLanguage: 'ja', targetLanguage: 'ar', sourceText: 'a', targetText: '1', confidence: 0.9 });
    tm.store({ sourceLanguage: 'ja', targetLanguage: 'ar', sourceText: 'a', targetText: '2', confidence: 0.95 });
    assert.equal(tm.count(), 1);
    assert.equal(tm.lookup('ja', 'ar', 'a')?.targetText, '2');
    db.close();
  });

  it('normalizes whitespace for matching', () => {
    assert.equal(normalizeForMemory('  a   b\n c '), 'a b c');
  });
});

describe('glossary', () => {
  it('repairs a known wrong rendering and reports the required form', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '魔王',
      defaultTranslation: 'ملك الشياطين', variants: ['الملك الشرير'], mode: 'force',
      priority: 10, context: 'fantasy manga',
    });
    const enforced = enforceGlossary('هزم الملك الشرير', entriesOf(glossary));
    assert.equal(enforced.text, 'هزم ملك الشياطين');
    assert.equal(enforced.enforcement.replaced.length, 1);
    assert.deepEqual(enforced.missingRequired, [], 'the required form is present after repair');
    db.close();
  });

  it('reports a missing required translation rather than inventing one', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '勇者',
      defaultTranslation: 'البطل', mode: 'force',
    });
    const enforced = enforceGlossary('قاتل الوحش', entriesOf(glossary));
    assert.equal(enforced.text, 'قاتل الوحش', 'nothing is fabricated');
    assert.deepEqual(enforced.missingRequired, ['البطل']);
    assert.equal(enforced.satisfied, 0);
    db.close();
  });

  it('counts a satisfied required translation', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '勇者',
      defaultTranslation: 'البطل', mode: 'force',
    });
    const enforced = enforceGlossary('ظهر البطل', entriesOf(glossary));
    assert.equal(enforced.satisfied, 1);
    assert.deepEqual(enforced.missingRequired, []);
    db.close();
  });

  it('reports a forbidden translation instead of silently passing', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '先輩',
      defaultTranslation: 'السينباي', forbidden: 'المحترف', mode: 'force',
    });
    const enforced = enforceGlossary('هو المحترف هنا', entriesOf(glossary));
    assert.equal(enforced.enforcement.forbiddenHits.length, 1);
    assert.equal(enforced.enforcement.forbiddenHits[0]?.forbidden, 'المحترف');
    db.close();
  });

  it('bumps the aggregate version on edit so the cache invalidates', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    const before = glossary.currentVersion();
    glossary.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '勇者', defaultTranslation: 'البطل' });
    assert.ok(glossary.currentVersion() > before);
    db.close();
  });
});

describe('context analysis', () => {
  it('renders glossary and knowledge instructions', () => {
    const db = openMemoryDatabase();
    const glossary = new GlossaryRepository(db);
    glossary.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', term: '先輩',
      defaultTranslation: 'السينباي', mode: 'force', priority: 5,
    });
    const analysis = analyzeContext({
      context: { sourceLanguage: 'ja', targetLanguage: 'ar', currentText: '先輩だ', register: 'dialogue' },
      glossary: glossary.list('ja', 'ar'),
    });
    assert.match(analysis.instructions, /先輩/);
    assert.match(analysis.instructions, /السينباي/);
    assert.match(analysis.instructions, /spoken dialogue/);
    assert.equal(analysis.sourceLanguage, 'ja');
    db.close();
  });

  it('detects the source language when auto', () => {
    const analysis = analyzeContext({
      context: { sourceLanguage: 'auto', targetLanguage: 'ar', currentText: '本気なのか？' },
      glossary: [],
    });
    assert.equal(analysis.sourceLanguage, 'ja');
    assert.equal(analysis.detectedLanguage, 'ja');
  });

  it('keeps context out of the cache key by default', () => {
    const base = { sourceLanguage: 'ja' as const, targetLanguage: 'ar' as const, currentText: 'x' };
    const a = contextCacheKey({ ...base, previousText: 'p1', nextText: 'n1' });
    const b = contextCacheKey({ ...base, previousText: 'p2', nextText: 'n2' });
    assert.equal(a, b, 'position must not fragment the cache (regression guard)');
    const withWindow = contextCacheKey({ ...base, previousText: 'p1' }, true);
    assert.notEqual(withWindow, a, 'opt-in context keys differ');
  });

  it('includes series and character scope in the cache key', () => {
    const base = { sourceLanguage: 'ja' as const, targetLanguage: 'ar' as const, currentText: 'x' };
    assert.notEqual(contextCacheKey({ ...base, seriesId: 's1' }), contextCacheKey({ ...base, seriesId: 's2' }));
  });

  it('extracts CJK runs and single characters', () => {
    const runs = extractWordLikeRuns('先輩は勇者だ Anna');
    assert.ok(runs.includes('先輩は勇者だ'));
    assert.ok(runs.includes('先'));
    assert.ok(runs.includes('Anna'));
  });

  it('detects register', () => {
    assert.equal(detectRegister('ドン'), 'sound_effect');
    assert.equal(detectRegister('Are you serious?!'), 'dialogue');
    assert.equal(detectRegister('The rain stopped somewhere around midnight and the street went silent.'), 'narration');
  });
});

describe('research queue', () => {
  it('deduplicates concurrent asks for the same phrase', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db);
    const first = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '先輩' });
    assert.equal(first.created, true);
    const results = Array.from({ length: 499 }, () =>
      queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: ' 先輩 ' }),
    );
    assert.ok(results.every((r) => r.deduplicated));
    assert.equal(queue.stats().queued, 1, '500 users must produce one job');
    assert.equal(queue.get(first.job.id)?.waiters, 500);
    db.close();
  });

  it('treats a different sentence as the same job but a different term as new', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db);
    const a = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '先輩', context: 'line one' });
    const b = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '先輩', context: 'totally different line' });
    assert.equal(b.job.id, a.job.id);
    const c = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '魔王' });
    assert.notEqual(c.job.id, a.job.id);
    db.close();
  });

  it('claims a job exactly once', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db);
    queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'a' });
    const first = queue.claimNext();
    const second = queue.claimNext();
    assert.ok(first);
    assert.equal(second, undefined, 'a claimed job must not be handed out twice');
    assert.equal(first?.status, 'running');
    assert.equal(first?.attempts, 1);
    db.close();
  });

  it('retries then parks in needs_review', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db);
    const { job } = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'x', maxAttempts: 2 });
    queue.claimNext();
    const afterFirst = queue.fail(job.id, 'boom', 0);
    assert.equal(afterFirst.status, 'retry');
    queue.requeue(job.id);
    queue.claimNext();
    const afterSecond = queue.fail(job.id, 'boom again', 0);
    assert.equal(afterSecond.status, 'needs_review');
    assert.equal(afterSecond.error, 'boom again');
    db.close();
  });

  it('deduplicates sources by fingerprint', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db);
    const { job } = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'x' });
    queue.addSource(job.id, { url: 'https://a.example/page?utm=1', kind: 'dictionary', credibility: 0.9, fingerprint: fingerprintUrl('https://a.example/page?utm=1') });
    queue.addSource(job.id, { url: 'https://a.example/page?utm=2', kind: 'dictionary', credibility: 0.9, fingerprint: fingerprintUrl('https://a.example/page?utm=2') });
    assert.equal(queue.sources(job.id).length, 1, 'the same page must not be counted twice');
    db.close();
  });

  it('keys on pair and category, not the surrounding sentence', () => {
    assert.equal(
      researchKey('ja', 'ar', '先輩', 'honorific'),
      researchKey('ja', 'ar', ' 先輩 ', 'honorific'),
    );
    assert.notEqual(researchKey('ja', 'ar', '先輩'), researchKey('ja', 'ar', '魔王'));
    assert.notEqual(researchKey('ja', 'ar', '先輩', 'honorific'), researchKey('ja', 'ar', '先輩', 'name'));
  });
});

describe('research agent', () => {
  const collector = (sources: CollectedSource[]) => ({
    async collect() { return sources; },
  });

  it('refuses a single source', async () => {
    const db = openMemoryDatabase();
    const knowledge = new KnowledgeRepository(db);
    const queue = new ResearchQueue(db);
    const agent = new ResearchAgent({
      db, queue, knowledge,
      collector: collector([{ url: 'https://d.example/1', kind: 'dictionary', translation: 'قائد' }]),
    });
    const job = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '大将' }).job;
    const result = await agent.research(job);
    assert.equal(result.accepted, false);
    assert.ok(result.confidence <= 0.5, 'a single source must be capped');
    assert.match(result.reason ?? '', /single source/);
    db.close();
  });

  it('accepts when multiple credible sources agree', async () => {
    const db = openMemoryDatabase();
    const knowledge = new KnowledgeRepository(db);
    const queue = new ResearchQueue(db);
    const agent = new ResearchAgent({
      db, queue, knowledge,
      collector: collector([
        { url: 'https://dict.example/a', kind: 'dictionary', translation: 'الزعيم', meaning: 'commander' },
        { url: 'https://ref.example/b', kind: 'reference', translation: 'الزعيم' },
        { url: 'https://corpus.example/c', kind: 'usage_corpus', translation: 'الزعيم' },
      ]),
    });
    const job = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '大将' }).job;
    const result = await agent.research(job);
    assert.equal(result.accepted, true);
    assert.ok(result.confidence >= 0.55, `confidence ${result.confidence}`);
    assert.equal(result.entry?.verificationState, 'candidate', 'research output is never auto-verified');
    assert.equal(queue.sources(job.id).length, 3);
    db.close();
  });

  it('rejects when sources disagree', async () => {
    const db = openMemoryDatabase();
    const knowledge = new KnowledgeRepository(db);
    const queue = new ResearchQueue(db);
    const agent = new ResearchAgent({
      db, queue, knowledge,
      collector: collector([
        { url: 'https://a.example/1', kind: 'community', translation: 'أ' },
        { url: 'https://b.example/2', kind: 'community', translation: 'ب' },
        { url: 'https://c.example/3', kind: 'community', translation: 'ج' },
      ]),
    });
    const job = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'x' }).job;
    const result = await agent.research(job);
    assert.equal(result.accepted, false, 'no agreement means no acceptance');
    db.close();
  });

  it('skips work already in the knowledge base', async () => {
    const db = openMemoryDatabase();
    const knowledge = new KnowledgeRepository(db);
    const queue = new ResearchQueue(db);
    knowledge.upsert({
      sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term',
      term: '勇者', translation: 'البطل', confidence: 0.9,
    });
    const agent = new ResearchAgent({ db, queue, knowledge, collector: collector([]) });
    const job = queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: '勇者' }).job;
    const result = await agent.research(job);
    assert.equal(result.reason, 'already known');
    assert.equal(result.sourcesUsed, 0);
    db.close();
  });

  it('picks the consensus translation, not the first result', () => {
    const consensus = chooseConsensus([
      { url: '1', kind: 'community', translation: 'قليل', credibility: 0.9 },
      { url: '2', kind: 'dictionary', translation: 'كثير', credibility: 0.8 },
      { url: '3', kind: 'reference', translation: 'كثير', credibility: 0.85 },
    ]);
    assert.equal(consensus.translation, 'كثير', 'the echoed answer wins over the loudest page');
  });

  it('normalizes URLs for fingerprinting', () => {
    assert.equal(fingerprintUrl('https://WWW.Example.com/path/?utm=1'), fingerprintUrl('http://example.com/path'));
    assert.notEqual(fingerprintUrl('https://a.com/1'), fingerprintUrl('https://a.com/2'));
  });

  it('scores source kinds sensibly', () => {
    assert.ok(SOURCE_CREDIBILITY.dictionary! > SOURCE_CREDIBILITY.community!);
    assert.ok(SOURCE_CREDIBILITY.community! > SOURCE_CREDIBILITY.machine_translation!);
  });

  it('moves a job to retry when nothing is found', async () => {
    const db = openMemoryDatabase();
    const knowledge = new KnowledgeRepository(db);
    const queue = new ResearchQueue(db);
    const agent = new ResearchAgent({ db, queue, knowledge, collector: collector([]) });
    queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: 'x' });
    const result = await agent.runOnce();
    assert.equal(result?.accepted, false);
    assert.equal(result?.job.status, 'retry');
    db.close();
  });
});

describe('feature flags', () => {
  it('defaults providers off and the local engine on', () => {
    const flags = new FeatureFlags();
    assert.equal(flags.isEnabled('ENABLE_LOCAL_ENGINE'), true);
    assert.equal(flags.isEnabled('ENABLE_DEEPL'), false);
    assert.equal(flags.isEnabled('ENABLE_RESEARCH_AGENT'), false);
  });

  it('parses from the environment', () => {
    const flags = FeatureFlags.fromEnv({ ENABLE_DEEPL: 'true', ENABLE_RESEARCH_AGENT: '0' });
    assert.equal(flags.isEnabled('ENABLE_DEEPL'), true);
    assert.equal(flags.isEnabled('ENABLE_RESEARCH_AGENT'), false);
  });

  it('rejects a non-boolean value', () => {
    assert.throws(() => FeatureFlags.fromEnv({ ENABLE_DEEPL: 'perhaps' }), /must be a boolean/);
  });

  it('throws an explicit error when a capability is off', () => {
    const flags = new FeatureFlags();
    assert.throws(() => flags.require('ENABLE_RESEARCH_AGENT'), FeatureDisabledError);
    assert.doesNotThrow(() => flags.require('ENABLE_LOCAL_ENGINE'));
  });

  it('core-only still has a working core', () => {
    const core = new FeatureFlags().withCoreOnly();
    assert.equal(core.isEnabled('ENABLE_LOCAL_ENGINE'), true);
    assert.equal(core.isEnabled('ENABLE_DEEPL'), false);
    assert.equal(core.isEnabled('ENABLE_RESEARCH_AGENT'), false);
  });

  it('covers every documented flag', () => {
    const flags = new FeatureFlags();
    assert.equal(Object.keys(flags.all()).length, FLAG_NAMES.length);
  });
});

describe('model router', () => {
  it('uses the standard tier by default', () => {
    const router = new ModelRouter();
    assert.equal(router.resolve({ sourceLanguage: 'ja', targetLanguage: 'ar' }).engineId, 'local-tg4');
  });

  it('uses the high tier on request', () => {
    const router = new ModelRouter();
    assert.equal(
      router.resolve({ sourceLanguage: 'ja', targetLanguage: 'ar', quality: 'high' }).engineId,
      'local-tg12',
    );
  });

  it('falls back when the primary is unavailable', () => {
    const router = new ModelRouter({
      isAvailable: (id) => ({ available: id !== 'local-tg4', reason: 'model not loaded' }),
    });
    const decision = router.resolve({ sourceLanguage: 'en', targetLanguage: 'ar' });
    assert.equal(decision.engineId, 'local-madlad3b');
    assert.equal(decision.reason, 'fallback');
    assert.ok(decision.substituted);
  });

  it('prefers a BYOK engine when supplied', () => {
    const router = new ModelRouter();
    const decision = router.resolve({ sourceLanguage: 'en', targetLanguage: 'ar', byokEngineId: 'deepl' });
    assert.equal(decision.engineId, 'deepl');
    assert.equal(decision.reason, 'byok');
  });

  it('ignores per-language rules until they are enabled', () => {
    const rules = [{ tier: 'standard' as const, sourceLanguage: 'ja', targetLanguage: 'ar', engineId: 'custom', priority: 0, weight: 100, enabled: true }];
    assert.notEqual(new ModelRouter({ rules }).resolve({ sourceLanguage: 'ja', targetLanguage: 'ar' }).engineId, 'custom');
    assert.equal(
      new ModelRouter({ rules, perLanguage: true }).resolve({ sourceLanguage: 'ja', targetLanguage: 'ar' }).engineId,
      'custom',
    );
  });

  it('reports clearly when nothing is available', () => {
    const router = new ModelRouter({ isAvailable: () => ({ available: false, reason: 'all down' }) });
    assert.throws(() => router.resolve({ sourceLanguage: 'en', targetLanguage: 'ar' }), NoEngineAvailableError);
  });
});

describe('metrics and limits', () => {
  it('computes percentiles, not just an average', () => {
    const histogram = new LatencyHistogram();
    for (let i = 1; i <= 100; i += 1) {
      histogram.observe(i);
    }
    const summary = histogram.summary();
    assert.equal(summary.count, 100);
    assert.equal(summary.p50, 50);
    assert.equal(summary.p95, 95);
    assert.equal(summary.max, 100);
  });

  it('aggregates counters by label', () => {
    const metrics = new MetricsRegistry();
    metrics.increment('hits', { engine: 'local' });
    metrics.increment('hits', { engine: 'local' });
    metrics.increment('hits', { engine: 'deepl' });
    assert.equal(metrics.counter('hits', { engine: 'local' }), 2);
    assert.equal(metrics.total('hits'), 3);
  });

  it('enforces a real concurrency limit', () => {
    const tracker = new ConcurrencyTracker(() => 2);
    const a = tracker.acquire('u1');
    const b = tracker.acquire('u1');
    assert.throws(() => tracker.acquire('u1'), ConcurrencyLimitError);
    a();
    const c = tracker.acquire('u1');
    assert.ok(c);
    b();
    c();
    assert.equal(tracker.inFlight('u1'), 0);
  });

  it('releases only once even if released twice', () => {
    const tracker = new ConcurrencyTracker(() => 1);
    const release = tracker.acquire('u');
    release();
    release();
    assert.equal(tracker.inFlight('u'), 0, 'a double release must not free capacity that was not taken');
  });

  it('rate limits within a window', () => {
    let now = 1_000_000;
    const limiter = new RateLimiter(2, 1000, () => now);
    limiter.consume('u');
    limiter.consume('u');
    assert.throws(() => limiter.consume('u'), RateLimitError);
    now += 1001;
    assert.doesNotThrow(() => limiter.consume('u'), 'a new window resets the allowance');
  });
});

describe('local engine', () => {
  it('is unavailable with no model loaded, so routing skips it', async () => {
    const engine = new DeterministicEngine();
    assert.equal(engine.configuration().configured, true);
  });

  it('produces a deterministic, clearly synthetic result', async () => {
    const engine = new DeterministicEngine();
    const request = { text: 'Hello', sourceLanguage: 'en', targetLanguage: 'ar' };
    const first = await engine.translate(request);
    const second = await engine.translate(request);
    assert.equal(first.text, second.text, 'determinism matters for cache testing');
    assert.equal(first.confidence, 0, 'a stub must never claim confidence');
    assert.match(first.text, /^\[en->ar:/);
  });

  it('only targets Arabic and reports its model identity', async () => {
    const engine = new DeterministicEngine();
    assert.equal(engine.supportsPair('ja', 'ar'), true);
    assert.deepEqual(engine.model.modelVersion, 'v1');
  });

  it('strips chat boilerplate from model output', () => {
    assert.equal(stripPreamble('Translation: مرحبا'), 'مرحبا');
    assert.equal(stripPreamble('```ar\nمرحبا\n```'), 'مرحبا');
    assert.equal(stripPreamble('  مرحبا  '), 'مرحبا');
  });

  it('extracts text from both serving styles', () => {
    assert.equal(extractText('llamacpp', { content: 'a' }), 'a');
    assert.equal(
      extractText('openai', { choices: [{ message: { content: 'b' } }] }),
      'b',
    );
  });
});
describe('LocalHttpEngine health', () => {
  it('reports unhealthy when no model server is listening', async () => {
    // Regression: a swallowed fetch error used to be read as "healthy", which
    // sends production traffic to a dead endpoint.
    const engine = new LocalHttpEngine({
      modelId: 'translategemma-4b',
      endpoint: 'http://127.0.0.1:1',
    });
    const health = await engine.healthCheck();
    assert.equal(health.healthy, false);
    assert.match(health.detail ?? '', /unreachable/i);
  });

  it('reports unhealthy without probing when no model is loaded', async () => {
    const engine = new LocalHttpEngine({
      modelId: 'translategemma-12b',
      endpoint: 'http://127.0.0.1:8082',
      modelLoaded: false,
    });
    const health = await engine.healthCheck();
    assert.equal(health.healthy, false);
    assert.equal(engine.configuration().configured, false);
  });
});
