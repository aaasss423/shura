/**
 * Translation pipeline.
 *
 * The box that is worth owning (see `docs/platform-architecture.md`):
 *
 *   context → terminology → TM → knowledge → model → post-process → quality
 *
 * Layering rules, so this stays readable as it grows:
 *  - The existing `TranslationEngine`, `Translator` and `TranslationService` are
 *    used unchanged. The pipeline composes *around* them.
 *  - Every layer is optional and flag-guarded; a deployment with only a local
 *    model still gets correct behaviour.
 *  - Nothing here imports a concrete engine.
 *
 * Cache keys gain model identity and glossary version (ADR 0009). Context stays
 * out of the key by default, so the same sentence in two panels shares one entry.
 */

import { createHash } from 'node:crypto';

import type { CancellationToken } from '../../core/cancellation';
import { countCharacters } from '../../arabic/arabic';
import { normalizeTerm } from '../knowledge/repository';
import type { KnowledgeEntry } from '../knowledge/repository';
import { analyzeContext, detectRegister, type ContextAnalysis, type TranslationContext } from '../context';
import type { GlossaryEntry } from '../memory/repository';
import { enforceGlossary } from '../memory/repository';
import type { KnowledgeRepository } from '../knowledge/repository';
import type { TranslationMemoryRepository } from '../memory/repository';
import type { GlossaryRepository } from '../memory/repository';
import type { ResearchQueue } from '../research/queue';
import type { FeatureFlags } from '../flags';
import { METRICS, type MetricsRegistry } from '../metrics';

export interface PipelineDeps {
  /** The existing platform translator. Untouched contract. */
  translate: (input: {
    text: string;
    sourceLanguage: string;
    targetLanguage: string;
    engine: string;
    hints?: Record<string, string>;
    contextBefore?: string;
    token?: CancellationToken;
    noCache?: boolean;
  }) => Promise<{ text: string; engine: string; fromCache: boolean; quality?: { score: number; ok: boolean } }>;
  knowledge?: KnowledgeRepository;
  memory?: TranslationMemoryRepository;
  glossary?: GlossaryRepository;
  research?: ResearchQueue;
  flags: FeatureFlags;
  metrics: MetricsRegistry;
  /** Below this, a TM hit is used directly instead of consulting the model. */
  memoryConfidenceThreshold?: number;
  /** Model identity for the cache key. */
  modelId?: string;
  modelVersion?: string;
}

export interface PipelineRequest {
  context: TranslationContext;
  engine: string;
  quality?: 'high' | 'standard';
  token?: CancellationToken;
  noCache?: boolean;
  /** Enqueue unknown terms for background research. */
  researchUnknown?: boolean;
}

export interface PipelineResult {
  text: string;
  engine: string;
  fromCache: boolean;
  /** Where the text came from, for transparent reporting. */
  source: 'memory' | 'model' | 'memory+model';
  analysis: ContextAnalysis;
  glossaryApplied: number;
  forbiddenHits: number;
  knowledgeHits: number;
  memoryHit: boolean;
  researchEnqueued: number;
  elapsedMs: number;
  quality?: { score: number; ok: boolean };
  /** Version components that participate in the cache key. */
  cacheContext: { modelId: string; modelVersion: string; glossaryVersion: number; knowledgeVersion: number };
}

const DEFAULT_MEMORY_THRESHOLD = 0.9;

/** Below this a memory entry is not even worth showing the model. */
const ADVISORY_MEMORY_FLOOR = 0.5;

export class TranslationPipeline {
  private readonly deps: PipelineDeps;

  constructor(deps: PipelineDeps) {
    this.deps = deps;
  }

  async translate(request: PipelineRequest): Promise<PipelineResult> {
    const started = Date.now();
    const { context, engine } = request;
    const { flags, metrics } = this.deps;

    const glossaryEntries = flags.isEnabled('ENABLE_GLOSSARY') && this.deps.glossary
      ? this.deps.glossary.list(context.sourceLanguage === 'auto' ? 'ja' : context.sourceLanguage, context.targetLanguage, {
          ...(context.seriesId ? { seriesId: context.seriesId } : {}),
          ...(context.genre ? { genre: context.genre } : {}),
        })
      : [];

    const register = context.register ?? detectRegister(context.currentText);

    // --- Layer 1: translation memory, before anything else (L3) ------------
    //
    // Two lookups on purpose. An entry at or above the threshold is
    // authoritative. A weaker entry is *advisory*: it is surfaced to the model as
    // a hint rather than used as the answer or silently discarded.
    const threshold = this.deps.memoryConfidenceThreshold ?? DEFAULT_MEMORY_THRESHOLD;
    let memoryHit: { targetText: string; confidence: number } | undefined;
    let advisoryHit: { targetText: string; confidence: number } | undefined;
    if (flags.isEnabled('ENABLE_TRANSLATION_MEMORY') && this.deps.memory) {
      const scope = {
        ...(context.seriesId ? { seriesId: context.seriesId } : {}),
        ...(context.characterId ? { characterId: context.characterId } : {}),
      };
      const source = context.sourceLanguage === 'auto' ? 'ja' : context.sourceLanguage;
      const strong = this.deps.memory.lookup(source, context.targetLanguage, context.currentText, {
        minConfidence: threshold,
        ...scope,
      });
      const weak = this.deps.memory.lookup(source, context.targetLanguage, context.currentText, {
        minConfidence: ADVISORY_MEMORY_FLOOR,
        ...scope,
      });
      const chosen = strong ?? weak;
      if (chosen) {
        this.deps.memory.recordUsage(chosen.id);
        memoryHit = { targetText: chosen.targetText, confidence: chosen.confidence };
        advisoryHit = strong ? undefined : memoryHit;
        metrics.increment(METRICS.memoryHits, { authoritative: strong ? 'yes' : 'no' });
      }
    }

    // --- Layer 2: knowledge retrieval -------------------------------------
    const knowledgeHits = this.retrieveKnowledge(context, glossaryEntries);

    const analysis = analyzeContext({
      context: { ...context, register },
      glossary: glossaryEntries,
      knowledge: knowledgeHits,
      // Only an authoritative hit counts as "the answer"; a weaker one is
      // surfaced as guidance instead.
      ...(memoryHit && !advisoryHit ? { memoryHit } : {}),
    });

    const glossaryVersion = this.deps.glossary?.currentVersion() ?? 0;
    const knowledgeVersion = this.deps.knowledge?.currentVersion() ?? 0;
    const cacheContext = {
      modelId: this.deps.modelId ?? 'unknown',
      modelVersion: this.deps.modelVersion ?? 'unversioned',
      glossaryVersion,
      knowledgeVersion,
    };

    // A high-confidence memory hit answers outright: no model call.
    if (memoryHit && memoryHit.confidence >= (this.deps.memoryConfidenceThreshold ?? DEFAULT_MEMORY_THRESHOLD)) {
      metrics.increment(METRICS.cacheHits, { layer: 'memory' });
      return {
        text: memoryHit.targetText,
        engine,
        fromCache: true,
        source: 'memory',
        analysis,
        glossaryApplied: 0,
        forbiddenHits: 0,
        knowledgeHits: knowledgeHits.length,
        memoryHit: true,
        researchEnqueued: 0,
        elapsedMs: Date.now() - started,
        cacheContext,
      };
    }

    // --- Layer 3: the model ------------------------------------------------
    const hints: Record<string, string> = {};
    if (analysis.instructions.length > 0) {
      hints.instructions = analysis.instructions;
    }
    if (advisoryHit) {
      hints.previousTranslation =
        `A previous translation of this line (confidence ${advisoryHit.confidence.toFixed(2)}) was: ${advisoryHit.targetText}`;
    }

    const modelResult = await this.deps.translate({
      text: context.currentText,
      sourceLanguage: context.sourceLanguage,
      targetLanguage: context.targetLanguage,
      engine,
      ...(Object.keys(hints).length > 0 ? { hints } : {}),
      ...(context.previousText ? { contextBefore: context.previousText } : {}),
      ...(request.token ? { token: request.token } : {}),
      ...(request.noCache === undefined ? {} : { noCache: request.noCache }),
    });

    // --- Layer 4: post processing -----------------------------------------
    const enforced = flags.isEnabled('ENABLE_GLOSSARY')
      ? enforceGlossary(modelResult.text, glossaryEntries)
      : { text: modelResult.text, enforcement: { replaced: [], forbiddenHits: [] } };

    if (enforced.enforcement.replaced.length > 0) {
      metrics.increment(METRICS.glossaryApplied);
    }

    // --- Layer 5: background research, never blocking ----------------------
    let researchEnqueued = 0;
    if (request.researchUnknown !== false && flags.isEnabled('ENABLE_RESEARCH_AGENT') && this.deps.research) {
      researchEnqueued = this.enqueueUnknownTerms(context, analysis, knowledgeHits);
    }

    return {
      text: enforced.text,
      engine: modelResult.engine,
      fromCache: modelResult.fromCache,
      source: memoryHit ? 'memory+model' : 'model',
      analysis,
      glossaryApplied: enforced.enforcement.replaced.length,
      forbiddenHits: enforced.enforcement.forbiddenHits.length,
      knowledgeHits: knowledgeHits.length,
      memoryHit: Boolean(memoryHit),
      researchEnqueued,
      elapsedMs: Date.now() - started,
      ...(modelResult.quality ? { quality: modelResult.quality } : {}),
      cacheContext,
    };
  }

  private retrieveKnowledge(
    context: TranslationContext,
    glossary: GlossaryEntry[],
  ): KnowledgeEntry[] {
    if (!this.deps.flags.isEnabled('ENABLE_KNOWLEDGE') || !this.deps.knowledge) {
      return [];
    }
    const terms = analyzeContext({ context, glossary }).terms;
    if (terms.length === 0) {
      return [];
    }
    const found = this.deps.knowledge.resolveMany(
      context.sourceLanguage === 'auto' ? 'ja' : context.sourceLanguage,
      context.targetLanguage,
      terms,
      {
        minConfidence: 0.4,
        ...(context.seriesId ? { seriesId: context.seriesId } : {}),
        ...(context.characterId ? { characterId: context.characterId } : {}),
      },
    );
    if (found.size > 0) {
      this.deps.metrics.increment(METRICS.knowledgeHits, {}, found.size);
      for (const entry of found.values()) {
        this.deps.knowledge.recordUsage(entry.id);
      }
    }
    return [...found.values()];
  }

  /**
   * Queues terms the knowledge base does not cover.
   *
   * Priority is low on purpose: prewarm and real reader traffic should
   * outrank opportunistic discovery.
   */
  private enqueueUnknownTerms(
    context: TranslationContext,
    analysis: ContextAnalysis,
    known: KnowledgeEntry[],
  ): number {
    if (!this.deps.research) {
      return 0;
    }
    const knownTerms = new Set(known.map((k) => k.normalizedTerm));
    for (const term of analysis.terms) {
      const normalized = normalizeTerm(term);
      if (normalized.length < 2 || knownTerms.has(normalized)) {
        continue;
      }
      // Only candidates that look like terms, not arbitrary prose.
      if (countCharacters(term) > 24) {
        continue;
      }
      this.deps.research.enqueue({
        sourceLanguage: analysis.sourceLanguage,
        targetLanguage: analysis.targetLanguage,
        term,
        context: context.currentText.slice(0, 120),
        priority: 8,
        ...(context.seriesId ? { seriesId: context.seriesId } : {}),
      });
    }
    return 1;
  }
}

/**
 * Cache-key context component (ADR 0009).
 *
 * Model identity and glossary version must be in the key; context must not,
 * because including it would recreate the position-dependent fragmentation bug.
 */
export function pipelineCacheContext(input: {
  modelId: string;
  modelVersion: string;
  glossaryVersion: number;
}): string {
  return [
    input.modelId,
    input.modelVersion,
    `glossary:${input.glossaryVersion}`,
  ].join('|');
}

export function hashCacheContext(value: string): string {
  return createHash('sha256').update(value).digest('hex').slice(0, 16);
}