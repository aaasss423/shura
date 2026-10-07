/**
 * Research Agent.
 *
 * The pipeline in ADR 0004, expressed as an independent module with injectable
 * dependencies:
 *
 *   normalize → dedupe → collect sources → score credibility → extract →
 *   compare → context-match → confidence → knowledge
 *
 * It never accepts the first search result. A single source caps confidence
 * hard, because one unverified snippet is how a wrong translation becomes
 * permanent.
 *
 * The web search itself is a port (`SourceCollector`). No network code lives in
 * this file, so the agent is fully testable and the platform keeps zero
 * dependencies.
 */

import { createHash } from 'node:crypto';
import type { Database } from '../db/database';
import { normalizeTerm } from '../knowledge/repository';
import type {
  KnowledgeCategory,
  KnowledgeEntry,
  KnowledgeSourceType,
} from '../knowledge/repository';
import { KnowledgeRepository } from '../knowledge/repository';
import { ResearchQueue, type ResearchJob } from './queue';

export interface CollectedSource {
  url: string;
  title?: string;
  /** 'dictionary' | 'usage_corpus' | 'community' | 'reference' | 'other' */
  kind: string;
  /** The candidate meaning/translation this source supports. */
  meaning?: string;
  translation?: string;
  snippet?: string;
}

export interface SourceCollector {
  collect(input: {
    term: string;
    sourceLanguage: string;
    targetLanguage: string;
    limit: number;
    signal?: AbortSignal;
  }): Promise<CollectedSource[]>;
}

/** Base credibility per source kind. Tunable, not a constant of nature. */
export const SOURCE_CREDIBILITY: Record<string, number> = {
  dictionary: 0.9,
  reference: 0.85,
  usage_corpus: 0.7,
  community: 0.5,
  machine_translation: 0.2,
  other: 0.3,
};

export interface ResearchResult {
  job: ResearchJob;
  accepted: boolean;
  entry?: KnowledgeEntry;
  confidence: number;
  sourcesUsed: number;
  reason?: string;
}

export interface ResearchAgentOptions {
  db: Database;
  queue: ResearchQueue;
  collector: SourceCollector;
  knowledge: KnowledgeRepository;
  /** Sources to request per job. */
  sourcesPerJob?: number;
  /** Confidence ceiling for each source count. */
  minConfidence?: number;
  /** Abort research below this confidence rather than storing a guess. */
  acceptanceThreshold?: number;
  /** Wall clock per job, so a slow collector cannot stall a worker forever. */
  timeoutMs?: number;
  now?: () => number;
}

export class ResearchAgent {
  private readonly options: Required<Omit<ResearchAgentOptions, 'collector' | 'knowledge' | 'queue' | 'db'>>;
  // Declared before the constructor on purpose. With `useDefineForClassFields`
  // (the ES2022 default), a field declaration is a *definition* that resets the
  // property to undefined at construction — so declaring these after the
  // constructor would silently erase what the constructor assigned.
  private readonly queue: ResearchQueue;
  private readonly collector: SourceCollector;
  private readonly knowledge: KnowledgeRepository;

  constructor(options: ResearchAgentOptions) {
    this.options = {
      sourcesPerJob: options.sourcesPerJob ?? 5,
      minConfidence: options.minConfidence ?? 0.5,
      acceptanceThreshold: options.acceptanceThreshold ?? 0.55,
      timeoutMs: options.timeoutMs ?? 20_000,
      now: options.now ?? Date.now,
    };
    this.queue = options.queue;
    this.collector = options.collector;
    this.knowledge = options.knowledge;
  }

  /**
   * Claims and processes one job. Returns undefined when the queue is empty, so
   * a worker loop can simply stop.
   */
  async runOnce(signal?: AbortSignal): Promise<ResearchResult | undefined> {
    const job = this.queue.claimNext();
    if (!job) {
      return undefined;
    }
    try {
      const result = await this.research(job, signal);
      if (result.accepted && result.entry) {
        this.queue.complete(job.id);
      } else {
        this.queue.fail(job.id, result.reason ?? 'confidence below acceptance threshold');
      }
      return { ...result, job: this.queue.get(job.id) ?? job };
    } catch (error) {
      this.queue.fail(job.id, error instanceof Error ? error.message : String(error));
      return {
        job: this.queue.get(job.id) ?? job,
        accepted: false,
        confidence: 0,
        sourcesUsed: 0,
        reason: error instanceof Error ? error.message : String(error),
      };
    }
  }

  /** The pipeline for one job. Exposed so it can be called directly in tests. */
  async research(job: ResearchJob, signal?: AbortSignal): Promise<ResearchResult> {
    const term = normalizeTerm(job.term);

    // 1. Dedup: if knowledge already holds this term above threshold, there is
    //    nothing to research. Reopening a job must not redo finished work.
    const existing = this.knowledge.resolve(job.sourceLanguage, job.targetLanguage, term, {
      ...(job.categoryHint ? { category: job.categoryHint as KnowledgeCategory } : {}),
    });
    if (existing && existing.confidence >= this.options.acceptanceThreshold) {
      return { job, accepted: true, entry: existing, confidence: existing.confidence, sourcesUsed: 0,
        reason: 'already known' };
    }

    // 2. Collect, bounded by a wall clock.
    const sources = await this.withTimeout(
      this.collector.collect({
        term,
        sourceLanguage: job.sourceLanguage,
        targetLanguage: job.targetLanguage,
        limit: this.options.sourcesPerJob,
        ...(signal ? { signal } : {}),
      }),
      this.options.timeoutMs,
    );

    // 3. Score and deduplicate sources.
    const scored = this.scoreSources(sources);
    for (const source of scored) {
      this.queue.addSource(job.id, {
        url: source.url,
        ...(source.title === undefined ? {} : { title: source.title }),
        kind: source.kind,
        credibility: source.credibility,
        ...(source.snippet === undefined ? {} : { snippet: source.snippet }),
        fingerprint: fingerprintUrl(source.url),
      });
    }

    if (scored.length === 0) {
      return { job, accepted: false, confidence: 0, sourcesUsed: 0, reason: 'no sources returned' };
    }

    // 4. Compare: one source cannot be enough.
    const confidence = this.scoreConfidence(scored);

    if (scored.length < 2) {
      return {
        job,
        accepted: false,
        confidence,
        sourcesUsed: scored.length,
        reason: 'a single source is not enough to accept a translation',
      };
    }
    if (confidence < this.options.acceptanceThreshold) {
      return { job, accepted: false, confidence, sourcesUsed: scored.length,
        reason: `confidence ${confidence.toFixed(2)} below threshold ${this.options.acceptanceThreshold}` };
    }

    // 5. Consensus: the highest-credibility source that agrees with the others.
    const winner = chooseConsensus(scored);
    if (!winner.translation && !winner.meaning) {
      return { job, accepted: false, confidence, sourcesUsed: scored.length,
        reason: 'sources agreed on credibility but produced no translation' };
    }

    // 6. Store, tagged unverified so a human can promote it later (ADR 0007).
    const category: KnowledgeCategory =
      (job.categoryHint as KnowledgeCategory | undefined) ?? inferCategory(winner.kind, job.term);
    const entry = this.knowledge.upsert({
      sourceLanguage: job.sourceLanguage,
      targetLanguage: job.targetLanguage,
      category,
      term: job.term,
      translation: winner.translation ?? job.term,
      ...(winner.meaning ? { meaning: winner.meaning } : {}),
      ...(job.context ? { context: job.context } : {}),
      ...(job.seriesId ? { seriesId: job.seriesId } : {}),
      confidence,
      source: winner.url,
      sourceType: 'research_agent' as KnowledgeSourceType,
      verificationState: 'candidate',
    });

    return { job, accepted: true, entry, confidence, sourcesUsed: scored.length };
  }

  private scoreSources(sources: CollectedSource[]): Array<CollectedSource & { credibility: number }> {
    const byFingerprint = new Map<string, CollectedSource & { credibility: number }>();
    for (const source of sources) {
      const fingerprint = fingerprintUrl(source.url);
      const credibility = SOURCE_CREDIBILITY[source.kind] ?? SOURCE_CREDIBILITY.other ?? 0.3;
      const existing = byFingerprint.get(fingerprint);
      if (existing) {
        // Same page twice: keep the higher credibility, do not double count.
        existing.credibility = Math.max(existing.credibility, credibility);
        continue;
      }
      byFingerprint.set(fingerprint, { ...source, credibility });
    }
    return [...byFingerprint.values()].sort((a, b) => b.credibility - a.credibility);
  }

  /**
   * Confidence from source count, credibility and agreement.
   *
   * Agreement matters more than count: five pages copying one bad snippet is
   * worse than two independent sources that agree.
   */
  private scoreConfidence(scored: Array<CollectedSource & { credibility: number }>): number {
    if (scored.length === 0) {
      return 0;
    }
    const top = scored.slice(0, Math.min(3, scored.length));
    const meanCredibility = top.reduce((sum, s) => sum + s.credibility, 0) / top.length;

    const candidates = scored
      .map((s) => (s.translation ?? '').trim())
      .filter((t) => t.length > 0)
      .map(normalizeTerm);
    const distinct = new Set(candidates).size;
    const total = Math.max(1, candidates.length);
    const agreement = candidates.length === 0 ? 0 : 1 / distinct + (distinct === 1 ? (total - 1) / total : 0);

    const diversity = Math.min(1, scored.length / 3);

    // Single-source ceiling: the single most important rule here.
    const singleSourceCap = scored.length === 1 ? 0.5 : 1;
    const raw = (meanCredibility * 0.6 + agreement * 0.25 + diversity * 0.15) * singleSourceCap;
    return clamp01(Number(raw.toFixed(3)));
  }

  private async withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    const guard = new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`source collection timed out after ${ms}ms`)), ms);
    });
    try {
      return await Promise.race([promise, guard]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
      promise.catch(() => undefined);
    }
  }
}

export function fingerprintUrl(url: string): string {
  const normalized = url
    .toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/^www\./, '')
    .replace(/[?#].*$/, '')
    .replace(/\/+$/, '');
  return createHash('sha256').update(normalized).digest('hex').slice(0, 24);
}

/** Highest-credibility source that at least one other source echoes. */
export function chooseConsensus(
  scored: Array<CollectedSource & { credibility: number }>,
): CollectedSource {
  const withTranslation = scored.filter((s) => (s.translation ?? '').trim().length > 0);
  if (withTranslation.length === 0) {
    return scored[0]!;
  }
  const counts = new Map<string, number>();
  for (const source of withTranslation) {
    const key = normalizeTerm(source.translation!);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const best = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0]![0];
  return withTranslation.find((s) => normalizeTerm(s.translation!) === best)!;
}

function inferCategory(kind: string, term: string): KnowledgeCategory {
  if (kind === 'dictionary') {
    return term.length <= 4 ? 'term' : 'phrase';
  }
  if (kind === 'community') {
    return 'slang';
  }
  if (kind === 'usage_corpus') {
    return 'manga_expression';
  }
  return 'phrase';
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}