/**
 * `ServingEngine` — the managed local-model engine.
 *
 * Implements the platform's existing `TranslationEngine` interface exactly, so
 * nothing above this line changes: the router, the service, the REST layer and
 * the benchmark all address it as an ordinary engine. What it adds is the
 * operational surface a self-hosted model needs and a hosted API does not:
 *
 *   model loading   readiness wait + warm-up before the first real request
 *   identity        modelId/revision/quantization verified against the server
 *   health          a real probe; unreachable is never reported healthy
 *   batching        micro-batches sized and windowed by the operator
 *   concurrency     a counting semaphore, so the VRAM limit is explicit
 *   timeout         per request and per dispatch
 *   cancellation    honoured before dispatch and during it
 *   shutdown        drain, then close; in-flight batches are not cut in half
 *   metrics         latency, batch size, queue wait, failure counts
 *
 * It is NOT a translation-quality component and carries no quality claim.
 */

import { EngineError, EngineUnavailableError, isCancellation } from '../../core/errors';
import { CancellationToken } from '../../core/cancellation';
import { getLanguageInfo, listAllLanguages } from '../../language/registry';
import type {
  EngineLanguagePairSupport,
  EngineLimits,
  EngineTranslationRequest,
  EngineTranslationResponse,
  LanguageCode,
  LanguageInfo,
} from '../../core/types';
import type { EngineHealth, TranslationEngine } from '../../engine/engine';
import { LatencyHistogram } from '../metrics';
import { BatchScheduler, type BatchOutcome } from './concurrency';
import { InferenceHttpClient, type RuntimeTimings } from './httpClient';
import { checkIdentity, describeIdentity, identityFromSpec, identityKey, type ModelIdentity } from './modelIdentity';
import { getModelSpec, type ModelSpec, type ServingStyle } from './modelCatalog';

const LOCAL_SOURCE_LANGUAGES: LanguageCode[] = ['en', 'ja', 'zh', 'ko', 'ar'];

export interface ServingEngineOptions {
  /** Catalog id (`local-tg4`) or the full spec. */
  model: string | ModelSpec;
  endpoint?: string;
  servingStyle?: ServingStyle;
  /** Pin the weights revision. Leave unset only while experimenting. */
  revision?: string;
  /** How long `warmUp()` waits for readiness before giving up. */
  readinessTimeoutMs?: number;
  maxCharsPerRequest?: number;
  timeoutMs?: number;
  queueTimeoutMs?: number;
  /** In-flight dispatch limit. Must be <= the server's parallel decode capacity. */
  concurrency?: number;
  maxBatchSize?: number;
  /** Batch waiting window; 0 disables batching. */
  batchWindowMs?: number;
  temperature?: number;
  maxTokens?: number;
  promptTemplate?: string;
  /**
   * Set false when the server is managed elsewhere (systemd, a container). The
   * engine then never tries to spawn or stop anything.
   */
  manageProcess?: boolean;
  onLog?: (message: string) => void;
}

export type EngineLifecycleState = 'idle' | 'warming' | 'ready' | 'draining' | 'stopped';

export interface ServingEngineStats {
  state: EngineLifecycleState;
  identity: ModelIdentity;
  identityKey: string;
  identityDescription: string;
  /** Requests completed since construction. */
  requests: number;
  failures: number;
  cancellations: number;
  warmUps: number;
  /** Wall clock of the last warm-up on this host, in ms. Undefined until one runs. */
  lastWarmUpMs?: number;
  /** How long readiness took on this host, in ms. Undefined until it succeeds. */
  lastReadinessMs?: number;
  requestsTotal: number;
  queueDepth: number;
  inFlight: number;
  batching: ReturnType<BatchScheduler['snapshot']>;
  latency: ReturnType<LatencyHistogram['summary']>;
  /** Messages from identity verification that a caller must surface. */
  identityWarnings: string[];
  /**
   * Decode rate.
   *
   * `measured: false` when the runtime returned no counter. That is the expected
   * outcome on servers that do not report one, and it is why no character-based
   * proxy exists anywhere in this file.
   */
  tokensPerSecond: {
    measured: boolean;
    tokensPerSecond?: number;
    samples: number;
    predictedTokens?: number;
    note: string;
  };
  /** Model name the server reported, once a health check has read it. */
  servedAs?: string;
  /**
   * True only when the server named its model and it matched the request.
   *
   * Distinct from "no warnings": an absent warning can mean a clean match or a
   * server that reported nothing, and the preflight gate must not read the second
   * as the first.
   */
  identityConfirmed: boolean;
}

/**
 * Extra operations that `TranslationEngine` does not define.
 *
 * Kept off the base interface on purpose: the base interface stays the contract
 * for every engine, and the serving lifecycle is something only a managed local
 * model has.
 */
export interface ManagedEngine {
  readonly identity: ModelIdentity;
  warmUp(token?: CancellationToken): Promise<{ durationMs: number; loaded: boolean; detail: string }>;
  waitUntilReady(timeoutMs: number, token?: CancellationToken): Promise<{ ready: boolean; waitedMs: number; detail: string }>;
  shutdown(timeoutMs?: number): Promise<{ drainedBatches: number; detail: string }>;
  stats(): ServingEngineStats;
  setBatching(enabled: boolean): void;
}

export class ServingEngine implements TranslationEngine, ManagedEngine {
  readonly id: string;
  readonly name: string;
  readonly limits: EngineLimits;
  readonly identity: ModelIdentity;
  readonly identityKey: string;
  readonly identityDescription: string;

  private readonly client: InferenceHttpClient;
  private readonly scheduler: BatchScheduler;
  private readonly latency = new LatencyHistogram();
  private readonly timeoutMs: number;
  private readonly queueTimeoutMs: number;
  private readonly readinessTimeoutMs: number;
  private readonly maxChars: number;
  private readonly manageProcess: boolean;
  private readonly endpoint: string;
  private readonly onLog?: (message: string) => void;

  private state: EngineLifecycleState = 'idle';
  private batchingEnabled: boolean;
  private requests = 0;
  private failures = 0;
  private cancellations = 0;
  private warmUps = 0;
  private lastWarmUpMs: number | undefined;
  private lastReadinessMs: number | undefined;
  private identityWarnings: string[] = [];
  private servedAs: string | undefined;
  private identityConfirmed = false;
  private readonly tokenRates: number[] = [];
  private predictedTokens = 0;
  private timingsNote = 'no request has completed yet';
  private sequence = 0;

  constructor(options: ServingEngineOptions) {
    const spec = typeof options.model === 'string' ? getModelSpec(options.model) : options.model;
    this.identity = identityFromSpec(spec, {
      ...(options.revision !== undefined ? { revision: options.revision } : {}),
      ...(options.servingStyle !== undefined ? { servingStyle: options.servingStyle } : {}),
    });
    this.identityKey = identityKey(this.identity);
    this.identityDescription = describeIdentity(this.identity);

    this.endpoint = options.endpoint ?? `http://127.0.0.1:${spec.defaultPort}`;
    this.maxChars = options.maxCharsPerRequest ?? 1200;
    this.limits = { maxCharsPerRequest: this.maxChars };
    this.timeoutMs = options.timeoutMs ?? 60000;
    this.queueTimeoutMs = options.queueTimeoutMs ?? 15000;
    this.readinessTimeoutMs = options.readinessTimeoutMs ?? 120000;
    this.manageProcess = options.manageProcess ?? false;
    this.onLog = options.onLog;
    this.id = spec.id;
    this.name = `Serving (${this.identityDescription})`;
    this.batchingEnabled = (options.batchWindowMs ?? 0) > 0;

    this.client = new InferenceHttpClient({
      endpoint: this.endpoint,
      servingStyle: this.identity.servingStyle,
      modelId: this.identity.modelId,
      timeoutMs: this.timeoutMs,
      temperature: options.temperature ?? 0,
      maxTokens: options.maxTokens ?? 512,
      ...(options.promptTemplate !== undefined ? { promptTemplate: options.promptTemplate } : {}),
    });

    this.scheduler = new BatchScheduler(
      {
        maxBatchSize: Math.max(1, options.maxBatchSize ?? 1),
        windowMs: Math.max(0, options.batchWindowMs ?? 0),
        concurrency: Math.max(1, options.concurrency ?? 1),
        queueWaitMs: this.queueTimeoutMs,
        batchingEnabled: this.batchingEnabled,
      },
      async (requests) => {
        const results = await this.client.completeBatch(
          requests.map((r) => ({
            text: r.text,
            ...(r.instruction !== undefined ? { instruction: r.instruction } : {}),
            ...(r.sourceLabel !== undefined ? { sourceLabel: r.sourceLabel } : {}),
          })),
          sourceLabel('en'),
        );
        for (const result of results) {
          this.recordTimings(result.timings);
        }
        return results.map((result, index) => ({
          tag: requests[index]!.tag,
          text: stripPreamble(result.text.trim()),
        }));
      },
    );
  }

  // -- TranslationEngine ----------------------------------------------------

  /**
   * Translates one chunk.
   *
   * Prefers a single direct request; falls back to the scheduler when batching is
   * on. The two paths differ in latency behaviour and that difference is recorded
   * in `stats()`, because a benchmark that mixes them produces a number that means
   * nothing.
   */
  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    if (this.state === 'stopped' || this.state === 'draining') {
      throw new EngineUnavailableError(`serving engine ${this.id} is shutting down`, { engine: this.id });
    }
    const text = request.text;
    if (text.trim().length === 0) {
      throw new EngineError('empty text rejected by engine', { engine: this.id, retryable: false });
    }
    if (text.length > this.maxChars) {
      throw new EngineError(
        `text of ${text.length} characters exceeds the limit of ${this.maxChars}`,
        { engine: this.id, retryable: false, details: { limit: this.maxChars } },
      );
    }
    const target = request.targetLanguage;
    if (target !== 'ar') {
      throw new EngineError(`serving engine only targets Arabic, received "${target}"`, {
        engine: this.id,
        retryable: false,
      });
    }

    const source = sourceLabel(request.sourceLanguage);
    const instruction = request.hints?.instructions;
    const started = Date.now();
    this.sequence += 1;
    const tag = `${this.id}-${this.sequence}`;

    try {
      const result = this.batchingEnabled
        ? await this.submitThroughScheduler(text, source, tag, instruction, request)
        : await this.submitDirect(text, source, instruction, request);

      this.latency.observe(Date.now() - started);
      this.requests += 1;
      return {
        text: result.text,
        engine: this.id,
        raw: {
          modelId: this.identity.modelId,
          revision: this.identity.revision,
          identityKey: this.identityKey,
          elapsedMs: Date.now() - started,
          ...(this.batchingEnabled ? { batchWaitMs: result.batchWaitMs } : {}),
        },
      };
    } catch (error) {
      this.failures += 1;
      // Cancellation is not a failure of the engine, and counting it as one would
      // make a client disconnect look like a broken model.
      if (isCancellation(error)) {
        this.cancellations += 1;
      }
      throw error;
    }
  }

  private async submitDirect(
    text: string,
    source: string,
    instruction: string | undefined,
    request: EngineTranslationRequest,
  ): Promise<{ text: string; batchWaitMs: number }> {
    const result = await this.client.complete(
      {
        text,
        ...(instruction !== undefined ? { instruction } : {}),
        ...(request.signal ? { signal: request.signal } : {}),
        ...(request.timeoutMs !== undefined ? { timeoutMs: request.timeoutMs } : {}),
      },
      source,
    );
    this.recordTimings(result.timings);
    return { text: stripPreamble(result.text.trim()), batchWaitMs: 0 };
  }

  private async submitThroughScheduler(
    text: string,
    source: string,
    tag: string,
    instruction: string | undefined,
    request: EngineTranslationRequest,
  ): Promise<{ text: string; batchWaitMs: number }> {
    const token = new CancellationToken();
    const unsubscribe = request.signal
      ? bindAbort(request.signal, token)
      : () => undefined;
    try {
      const outcome: BatchOutcome = await this.scheduler.submit(
        {
          text,
          tag,
          sourceLabel: source,
          ...(instruction !== undefined ? { instruction } : {}),
          ...(request.signal ? { signal: request.signal } : {}),
          ...(request.timeoutMs !== undefined ? { timeoutMs: request.timeoutMs } : {}),
        },
        token,
      );
      if (!outcome.ok || outcome.text === undefined) {
        throw outcome.error ?? new EngineError('batched request failed', { retryable: true });
      }
      return { text: outcome.text, batchWaitMs: outcome.batchWaitMs };
    } finally {
      unsubscribe();
    }
  }

  getSourceLanguages(): LanguageInfo[] {
    return listAllLanguages().filter((l: LanguageInfo) => LOCAL_SOURCE_LANGUAGES.includes(l.code));
  }

  getTargetLanguages(): LanguageInfo[] {
    const arabic = getLanguageInfo('ar');
    return arabic ? [arabic] : [];
  }

  supportsPair(source: LanguageCode, target: LanguageCode): boolean {
    return LOCAL_SOURCE_LANGUAGES.includes(source) && target === 'ar' && source !== target;
  }

  supportedPairs(): EngineLanguagePairSupport[] {
    return LOCAL_SOURCE_LANGUAGES.filter((s) => s !== 'ar').map((source) => ({ source, target: 'ar' }));
  }

  configuration(): { configured: boolean; reason?: string } {
    if (this.state === 'stopped') {
      return { configured: false, reason: 'serving engine has been shut down' };
    }
    return { configured: true };
  }

  /**
   * Readiness, not liveness of the process.
   *
   * Checks three things in order: the transport answers, the server reports which
   * model it loaded, and that model is the one requested. A server that answers
   * while serving a different model is not ready for this engine.
   */
  async healthCheck(token?: CancellationToken): Promise<EngineHealth> {
    const probe = await this.client.probe();
    if (!probe.reachable) {
      return { engine: this.id, healthy: false, detail: probe.detail };
    }

    const selfReport = await this.client.describe();
    const warnings = checkIdentity(this.identity, {
      ...(selfReport.servedAs !== undefined ? { servedAs: selfReport.servedAs } : {}),
    });
    this.identityWarnings = warnings.map((w) => w.message);
    this.servedAs = selfReport.servedAs;
    this.identityConfirmed = selfReport.servedAs !== undefined && warnings.every((w) => w.level !== 'error');

    const blocking = warnings.find((w) => w.level === 'error');
    if (blocking) {
      return { engine: this.id, healthy: false, detail: blocking.message };
    }
    if (token?.isCancelled) {
      return { engine: this.id, healthy: false, detail: 'health check cancelled' };
    }
    const identityNote = selfReport.servedAs ? ` serving ${selfReport.servedAs}` : '';
    return {
      engine: this.id,
      healthy: true,
      detail: `${this.identityDescription}${identityNote}`,
    };
  }

  // -- ManagedEngine --------------------------------------------------------

  /**
   * Waits until the server answers, then issues one throwaway translation.
   *
   * The warm-up request exists because the first real request after a model loads
   * pays for weight paging and graph capture. Benchmarks must run after it, or
   * they measure the load instead of the model.
   */
  async warmUp(token?: CancellationToken): Promise<{ durationMs: number; loaded: boolean; detail: string }> {
    const started = Date.now();
    this.state = 'warming';
    try {
      const readiness = await this.waitUntilReady(this.readinessTimeoutMs, token);
      if (!readiness.ready) {
        // Back to idle, not left warming: a caller that polls state would
        // otherwise see a warm-up in progress that has already given up.
        this.state = 'idle';
        return { durationMs: Date.now() - started, loaded: false, detail: readiness.detail };
      }
      this.lastReadinessMs = readiness.waitedMs;
      const warmStarted = Date.now();
      const probe = await this.client.complete(
        { text: WARMUP_TEXT, timeoutMs: Math.max(30000, this.timeoutMs) },
        sourceLabel('en'),
      );
      this.state = 'ready';
      this.warmUps += 1;
      this.lastWarmUpMs = Date.now() - warmStarted;
      this.onLog?.(`warm-up ok in ${this.lastWarmUpMs}ms (readiness ${readiness.waitedMs}ms)`);
      return {
        durationMs: Date.now() - started,
        loaded: probe.text.length > 0,
        detail: `warm-up completed in ${this.lastWarmUpMs}ms after ${readiness.waitedMs}ms readiness wait`,
      };
    } catch (error) {
      this.state = 'idle';
      return {
        durationMs: Date.now() - started,
        loaded: false,
        detail: `warm-up failed: ${error instanceof Error ? error.message : String(error)}`,
      };
    }
  }

  /** Polls until ready or the deadline passes. */
  async waitUntilReady(
    timeoutMs: number,
    token?: CancellationToken,
  ): Promise<{ ready: boolean; waitedMs: number; detail: string }> {
    const started = Date.now();
    const interval = 500;
    let lastDetail = 'not probed';
    while (Date.now() - started < timeoutMs) {
      if (token?.isCancelled) {
        return { ready: false, waitedMs: Date.now() - started, detail: 'cancelled while waiting for readiness' };
      }
      const health = await this.healthCheck(token);
      lastDetail = health.detail ?? 'no detail';
      if (health.healthy) {
        return { ready: true, waitedMs: Date.now() - started, detail: lastDetail };
      }
      await new Promise<void>((resolve) => setTimeout(resolve, interval));
    }
    return { ready: false, waitedMs: Date.now() - started, detail: `not ready within ${timeoutMs}ms: ${lastDetail}` };
  }

  /**
   * Stops accepting work, drains what is in flight, then closes.
   *
   * Draining rather than aborting is the point: a half-decoded batch is paid for
   * and thrown away.
   */
  async shutdown(timeoutMs = 10000): Promise<{ drainedBatches: number; detail: string }> {
    if (this.state === 'stopped') {
      return { drainedBatches: 0, detail: 'already stopped' };
    }
    this.state = 'draining';
    const before = this.scheduler.snapshot().dispatched;
    await this.scheduler.close(timeoutMs);
    this.state = 'stopped';
    const drained = this.scheduler.snapshot().dispatched - before;
    const detail = `drained ${drained} batch(es); ${this.requests} request(s) served this lifetime`;
    this.onLog?.(detail);
    return { drainedBatches: drained, detail };
  }

  setBatching(enabled: boolean): void {
    this.batchingEnabled = enabled;
    this.onLog?.(`batching ${enabled ? 'enabled' : 'disabled'} (existing stats are no longer comparable)`);
  }

  private recordTimings(timings: RuntimeTimings): void {
    this.timingsNote = timings.note;
    if (timings.source === 'runtime' && timings.tokensPerSecond !== undefined) {
      this.tokenRates.push(timings.tokensPerSecond);
      this.predictedTokens += timings.predictedTokens ?? 0;
    }
  }

  stats(): ServingEngineStats {
    const mean =
      this.tokenRates.length === 0
        ? undefined
        : Math.round((this.tokenRates.reduce((a, b) => a + b, 0) / this.tokenRates.length) * 100) / 100;
    return {
      state: this.state,
      identity: this.identity,
      identityKey: this.identityKey,
      identityDescription: this.identityDescription,
      requests: this.requests,
      failures: this.failures,
      cancellations: this.cancellations,
      warmUps: this.warmUps,
      ...(this.lastWarmUpMs !== undefined ? { lastWarmUpMs: this.lastWarmUpMs } : {}),
      ...(this.lastReadinessMs !== undefined ? { lastReadinessMs: this.lastReadinessMs } : {}),
      requestsTotal: this.requests,
      queueDepth: this.scheduler.queued,
      inFlight: this.scheduler.inFlight,
      batching: this.scheduler.snapshot(),
      latency: this.latency.summary(),
      identityWarnings: [...this.identityWarnings],
      ...(this.servedAs !== undefined ? { servedAs: this.servedAs } : {}),
      identityConfirmed: this.identityConfirmed,
      tokensPerSecond: {
        measured: mean !== undefined,
        ...(mean !== undefined ? { tokensPerSecond: mean } : {}),
        samples: this.tokenRates.length,
        ...(this.predictedTokens > 0 ? { predictedTokens: this.predictedTokens } : {}),
        note: mean !== undefined
          ? `mean of ${this.tokenRates.length} runtime-reported sample(s); it is not estimated from output length`
          : this.timingsNote,
      },
    };
  }

  /** Exposed for the supervisor, health endpoints and tests. */
  get serverEndpoint(): string {
    return this.endpoint;
  }

  get managesProcess(): boolean {
    return this.manageProcess;
  }
}

/** Text used only to force weight paging. Never scored, never stored. */
const WARMUP_TEXT = 'Good morning.';

function sourceLabel(source: string): string {
  const info = getLanguageInfo(source as LanguageCode);
  return info?.name ?? source;
}

/** Removes chat preambles ("Here is the translation:") a served model may add. */
export function stripPreamble(text: string): string {
  return text
    .replace(/^\s*(sure|certainly|of course)[!.,:]?\s*/i, '')
    .replace(/^\s*here (is|are) the (arabic )?translation[^:]*:\s*/i, '')
    .replace(/^\s*translated? (text )?:\s*/i, '')
    .replace(/^[\s"'`]+|[\s"'`]+$/g, '');
}

function bindAbort(signal: AbortSignal, token: CancellationToken): () => void {
  if (signal.aborted) {
    token.cancel({ message: 'request was already cancelled' });
    return () => undefined;
  }
  return token.onCancel(() => token.cancel({ message: 'request signal aborted' }));
}
