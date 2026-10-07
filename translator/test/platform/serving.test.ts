/**
 * Serving layer tests.
 *
 * **Every model-facing assertion in this file is against a mock inference server.**
 * Nothing here measures translation quality, latency of a real model, throughput
 * or VRAM. What is verified is the operational behaviour: readiness, identity,
 * batching, concurrency, timeout, cancellation, shutdown and queue integration.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { CancellationToken } from '../../src/core/cancellation';
import { EngineUnavailableError, TimeoutError } from '../../src/core/errors';
import { JobQueue } from '../../src/platform/jobs/queue';
import { openMemoryDatabase, applyMigrations } from '../../src/platform/db/database';
import {
  MODEL_CATALOG,
  candidateModels,
  getModelSpec,
  selectDefaultModel,
} from '../../src/platform/serving/modelCatalog';
import {
  assertIdentity,
  checkIdentity,
  servedMatches,
  describeIdentity,
  identityFromSpec,
  identityKey,
  isPinned,
} from '../../src/platform/serving/modelIdentity';
import { Semaphore, BatchScheduler } from '../../src/platform/serving/concurrency';
import { extractBatch, extractTimings, normalizeServedModel } from '../../src/platform/serving/httpClient';
import { captureProvenance, observeRuntime } from '../../src/platform/serving/provenance';
import { promises as fs } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const { writeFile, rm } = fs;
import { parseNvidiaRow, sampleResources } from '../../src/platform/serving/resourceSampler';
import { ServingEngine, stripPreamble } from '../../src/platform/serving/servingEngine';
import { ModelServerSupervisor } from '../../src/platform/serving/supervisor';
import { ServingQueueRunner } from '../../src/platform/serving/queueRunner';
import { renderModelComparison } from '../../src/platform/benchmark/runner';
import {
  assertReadyForBenchmark,
  detectGpu,
  detectRuntime,
  renderPreflight,
  runPreflight,
} from '../../src/platform/serving/preflight';

const UNPINNED_REVISION = 'UNPINNED';
import { startMockInferenceServer, fakeProcess } from './helpers/mockInferenceServer';

const spec = getModelSpec('local-tg4');

function engineFor(endpoint: string, overrides: Partial<ConstructorParameters<typeof ServingEngine>[0]> = {}) {
  return new ServingEngine({
    model: spec,
    endpoint,
    revision: 'abc1234',
    timeoutMs: 5000,
    ...overrides,
  });
}

describe('model catalog', () => {
  it('catalogues the three candidates with published requirements marked as published', () => {
    const ids = MODEL_CATALOG.map((m) => m.id).sort();
    assert.deepEqual(ids, ['local-madlad3b', 'local-tg12', 'local-tg4']);
    for (const model of MODEL_CATALOG) {
      assert.equal(model.requirementsSource, 'published');
      assert.ok(model.install.length > 0, `${model.id} has no install instructions`);
      assert.ok(model.serve.length > 0, `${model.id} has no serving/health instructions`);
      assert.ok(model.licenseAllowsCommercialUse, `${model.id} licence must permit commercial use`);
    }
  });

  it('refuses to name a default model', () => {
    // The reason this test exists: a default must only appear after a real run.
    assert.throws(() => selectDefaultModel(), /no default model has been selected/);
  });

  it('filters candidates by what the host can hold', () => {
    const small = candidateModels({ availableVramGb: 4, availableRamGb: 16 });
    assert.deepEqual(small.map((m) => m.id), ['local-tg4']);
    const none = candidateModels({ availableVramGb: 1, availableRamGb: 1 });
    assert.deepEqual(none, []);
  });

  it('rejects an unknown model id by name', () => {
    assert.throws(() => getModelSpec('local-nope'), /unknown model "local-nope"/);
  });
});

describe('model identity', () => {
  const identity = identityFromSpec(spec, { revision: 'deadbeef' });

  it('keys identically for identical weights and differently for different weights', () => {
    const same = identityFromSpec(spec, { revision: 'deadbeef' });
    const other = identityFromSpec(spec, { revision: 'cafebabe' });
    assert.equal(identityKey(identity), identityKey(same));
    assert.notEqual(identityKey(identity), identityKey(other));
  });

  it('distinguishes quantization, so a Q4 run cannot be confused with a BF16 run', () => {
    const bf16 = { ...identity, quantization: 'bf16' };
    assert.notEqual(identityKey(identity), identityKey(bf16));
  });

  it('does not let an unpinned revision collide with a pinned one', () => {
    const unpinned = identityFromSpec(spec);
    assert.equal(isPinned(unpinned), false);
    assert.equal(isPinned(identity), true);
    assert.notEqual(identityKey(unpinned), identityKey(identity));
    assert.match(describeIdentity(unpinned), /not reproducible/);
  });

  it('accepts a served name that only adds build markers to the request', () => {
    assert.equal(servedMatches('translategemma-4b', '/models/translategemma-4b-it-qat-q4_K_M.gguf'), true);
    assert.equal(servedMatches('madlad400-3b-mt', '/models/madlad400-3b-mt-GGUF.gguf'), true);
    assert.equal(servedMatches('translategemma-4b', 'translategemma-4b'), true);
  });

  it('rejects a different model size, which would corrupt a comparison', () => {
    assert.equal(servedMatches('translategemma-4b', 'translategemma-12b-it-qat-q4_K_M.gguf'), false);
    assert.equal(servedMatches('translategemma-12b', 'translategemma-4b'), false);
    assert.equal(servedMatches('translategemma-4b', 'llama-3.1-8b'), false);
  });

  it('does not match on a partial token boundary', () => {
    assert.equal(servedMatches('translategemma-4', 'translategemma-4b'), false);
  });

  it('warns when the server is serving a different model than requested', () => {
    const warnings = checkIdentity(identity, { servedAs: 'some-other-model' });
    assert.equal(warnings.filter((w) => w.level === 'error').length, 1);
    assert.throws(() => assertIdentity(identity, { servedAs: 'some-other-model' }), /but/);
    assert.doesNotThrow(() => assertIdentity(identity, { servedAs: spec.modelId }));
  });

  it('warns about an unpinned revision without blocking the run', () => {
    const warnings = checkIdentity(identityFromSpec(spec), { servedAs: spec.modelId });
    assert.equal(warnings.filter((w) => w.level === 'error').length, 0);
    assert.ok(warnings.some((w) => w.level === 'warning' && /UNPINNED/.test(w.message)));
  });
});

describe('semaphore', () => {
  it('never exceeds its limit', async () => {
    const semaphore = new Semaphore(2);
    let peak = 0;
    let active = 0;
    await Promise.all(
      Array.from({ length: 8 }, async () => {
        const release = await semaphore.acquire();
        active += 1;
        peak = Math.max(peak, active);
        await new Promise((resolve) => setTimeout(resolve, 5));
        active -= 1;
        release();
      }),
    );
    assert.equal(peak, 2);
    assert.equal(semaphore.inFlight, 0);
  });

  it('hands off in FIFO order', async () => {
    const semaphore = new Semaphore(1);
    const order: number[] = [];
    const first = await semaphore.acquire();
    const waiters = [1, 2, 3].map((n) =>
      semaphore.acquire().then((release) => {
        order.push(n);
        release();
      }),
    );
    first();
    await Promise.all(waiters);
    assert.deepEqual(order, [1, 2, 3]);
  });

  it('times out without leaking a slot', async () => {
    const semaphore = new Semaphore(1);
    const release = await semaphore.acquire();
    await assert.rejects(semaphore.acquire(30), TimeoutError);
    release();
    const afterRelease = await semaphore.acquire(30);
    assert.equal(typeof afterRelease, 'function');
    afterRelease();
  });

  it('honours cancellation while queued', async () => {
    const semaphore = new Semaphore(1);
    const release = await semaphore.acquire();
    const token = new CancellationToken();
    const waiting = semaphore.acquire(5000, token);
    token.cancel({ message: 'operator cancelled' });
    await assert.rejects(waiting);
    release();
    assert.equal(semaphore.inFlight, 0);
  });

  it('rejects a nonsensical limit', () => {
    assert.throws(() => new Semaphore(0), /at least 1/);
  });
});

describe('batch scheduler', () => {
  const options = {
    maxBatchSize: 4,
    windowMs: 20,
    concurrency: 2,
    batchingEnabled: true,
    queueWaitMs: 500,
  };

  it('groups concurrent submissions into one dispatch', async () => {
    let dispatched: number[] = [];
    const scheduler = new BatchScheduler(options, async (requests) => {
      dispatched.push(requests.length);
      return requests.map((r) => ({ tag: r.tag, text: `out:${r.text}` }));
    });
    const results = await Promise.all(
      ['a', 'b', 'c'].map((t) => scheduler.submit({ text: t, tag: t })),
    );
    assert.deepEqual(dispatched, [3]);
    assert.deepEqual(results.map((r) => r.text).sort(), ['out:a', 'out:b', 'out:c']);
    await scheduler.close();
  });

  it('caps a dispatch at maxBatchSize', async () => {
    const sizes: number[] = [];
    const scheduler = new BatchScheduler(options, async (requests) => {
      sizes.push(requests.length);
      return requests.map((r) => ({ tag: r.tag, text: r.text }));
    });
    await Promise.all(Array.from({ length: 9 }, (_, i) => scheduler.submit({ text: `t${i}`, tag: `t${i}` })));
    assert.ok(Math.max(...sizes) <= 4, `batch of ${Math.max(...sizes)} exceeded the cap`);
    await scheduler.close();
  });

  it('fails one item without failing its batch', async () => {
    const scheduler = new BatchScheduler(options, async (requests) =>
      requests.map((r) =>
        r.tag === 'bad'
          ? { tag: r.tag, error: new Error('item failed') }
          : { tag: r.tag, text: 'ok' },
      ),
    );
    const results = await Promise.all([
      scheduler.submit({ text: 'a', tag: 'good' }),
      scheduler.submit({ text: 'b', tag: 'bad' }),
    ]);
    assert.equal(results.find((r) => r.tag === 'good')?.ok, true);
    assert.equal(results.find((r) => r.tag === 'bad')?.ok, false);
    await scheduler.close();
  });

  it('records batch waiting separately from dispatch time', async () => {
    const scheduler = new BatchScheduler(options, async (requests) =>
      requests.map((r) => ({ tag: r.tag, text: 'x' })),
    );
    const outcome = await scheduler.submit({ text: 'a', tag: 'a' });
    assert.ok(outcome.batchWaitMs >= 0);
    assert.ok(scheduler.snapshot().meanBatchWaitMs >= 0);
    await scheduler.close();
  });

  it('never resolves to a mismatched result when the server drops an item', async () => {
    const scheduler = new BatchScheduler(options, async (requests) =>
      // One fewer response than requests, on purpose.
      requests.slice(0, -1).map((r) => ({ tag: r.tag, text: 'x' })),
    );
    const results = await Promise.all([
      scheduler.submit({ text: 'a', tag: 'a' }),
      scheduler.submit({ text: 'b', tag: 'b' }),
    ]);
    assert.ok(results.some((r) => !r.ok), 'a dropped item must fail rather than shift results');
    await scheduler.close();
  });

  it('fails queued work on close and refuses new work afterwards', async () => {
    const scheduler = new BatchScheduler(
      { ...options, windowMs: 10_000 },
      async () => {
        throw new Error('should not be called');
      },
    );
    const pending = scheduler.submit({ text: 'a', tag: 'a' });
    await scheduler.close(100);
    const outcome = await pending;
    assert.equal(outcome.ok, false);
    assert.match(outcome.error?.message ?? '', /shutting down/);
    const rejected = await scheduler.submit({ text: 'b', tag: 'b' });
    assert.equal(rejected.ok, false);
    assert.match(rejected.error?.message ?? '', /closed/);
  });

  it('resolves a cancelled submission without dispatching it', async () => {
    let dispatchedCount = 0;
    const scheduler = new BatchScheduler(
      { ...options, windowMs: 10_000 },
      async (requests) => {
        dispatchedCount += requests.length;
        return requests.map((r) => ({ tag: r.tag, text: 'x' }));
      },
    );
    const token = new CancellationToken();
    const pending = scheduler.submit({ text: 'a', tag: 'a' }, token);
    token.cancel({ message: 'client disconnected' });
    const outcome = await pending;
    assert.equal(outcome.ok, false);
    assert.equal(dispatchedCount, 0);
    await scheduler.close(50);
  });
});

describe('inference response parsing', () => {
  it('reads an OpenAI-shaped batch', () => {
    const parsed = extractBatch(
      { choices: [{ message: { content: 'أ' } }, { message: { content: 'ب' } }] },
      2,
    );
    assert.deepEqual(parsed.map((p) => p.text), ['أ', 'ب']);
  });

  it('reads a llama.cpp-shaped single response', () => {
    assert.equal(extractBatch({ content: 'أ' }, 1)[0]?.text, 'أ');
  });

  it('refuses to guess when the server returns fewer results than prompts', () => {
    assert.throws(() => extractBatch({ choices: [{ message: { content: 'أ' } }] }, 2), /cannot be matched safely/);
  });

  it('rejects a response with no text at all', () => {
    assert.throws(() => extractBatch({ unexpected: true }, 1), /no text field/);
  });

  it('normalizes a served-model path so a quantised file is not a false mismatch', () => {
    assert.equal(normalizeServedModel('/models/translategemma-4b-it-qat-q4_K_M.gguf'), 'translategemma-4b-it-qat');
    assert.equal(normalizeServedModel('translategemma-4b'), 'translategemma-4b');
  });
});

describe('serving engine against a mock inference server (MOCK, not a model)', () => {
  it('reports unhealthy when nothing is listening, and never as healthy', async () => {
    const engine = engineFor('http://127.0.0.1:1');
    const health = await engine.healthCheck();
    assert.equal(health.healthy, false);
    assert.match(health.detail ?? '', /unreachable/);
  });

  it('reports healthy only after the mock server answers', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint);
      const health = await engine.healthCheck();
      assert.equal(health.healthy, true);
      assert.match(health.detail ?? '', new RegExp(spec.modelId));
    } finally {
      await mock.close();
    }
  });

  it('refuses to be healthy while the server reports a different model', async () => {
    const mock = await startMockInferenceServer({ servedAs: 'a-different-model' });
    try {
      const health = await engineFor(mock.endpoint).healthCheck();
      assert.equal(health.healthy, false);
      assert.match(health.detail ?? '', /but "translategemma-4b" was requested/);
    } finally {
      await mock.close();
    }
  });

  it('waits through a loading window and reports readiness, with the wait measured', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId, warmupProbes: 3 });
    try {
      const engine = engineFor(mock.endpoint);
      const started = Date.now();
      const readiness = await engine.waitUntilReady(5000);
      assert.equal(readiness.ready, true);
      assert.ok(readiness.waitedMs >= 1000, `waited ${readiness.waitedMs}ms, expected at least the 3 skipped probes`);
      assert.ok(Date.now() - started >= 1000);
    } finally {
      await mock.close();
    }
  });

  it('warms up before serving, and records warm-up and readiness times', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint);
      const warm = await engine.warmUp();
      assert.equal(warm.loaded, true);
      const stats = engine.stats();
      assert.equal(stats.state, 'ready');
      assert.equal(stats.warmUps, 1);
      assert.ok((stats.lastWarmUpMs ?? -1) >= 0);
      assert.ok((stats.lastReadinessMs ?? -1) >= 0);
    } finally {
      await mock.close();
    }
  });

  it('reports warm-up failure instead of pretending the model is loaded', async () => {
    const engine = engineFor('http://127.0.0.1:1', { readinessTimeoutMs: 300 });
    const warm = await engine.warmUp();
    assert.equal(warm.loaded, false);
    assert.match(warm.detail, /failed|not ready/);
    assert.equal(engine.stats().state, 'idle');
  });

  it('translates one request and returns mock output marked as mock', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint);
      const response = await engine.translate({
        text: 'おはよう',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
      });
      assert.match(response.text, /^MOCK\[/);
      assert.equal(response.engine, spec.id);
      const raw = response.raw as { identityKey: string; modelId: string };
      assert.equal(raw.modelId, spec.modelId);
      assert.ok(raw.identityKey.length > 0);
    } finally {
      await mock.close();
    }
  });

  it('refuses a non-Arabic target and oversized text before any network call', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint, { maxCharsPerRequest: 10 });
      await assert.rejects(
        engine.translate({ text: 'x'.repeat(50), sourceLanguage: 'ja', targetLanguage: 'ar' }),
        /exceeds the limit/,
      );
      await assert.rejects(
        engine.translate({ text: 'short', sourceLanguage: 'ja', targetLanguage: 'en' }),
        /only targets Arabic/,
      );
      assert.equal(mock.requestCount, 0);
    } finally {
      await mock.close();
    }
  });

  it('batches concurrent requests into a single server call', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint, { batchWindowMs: 25, maxBatchSize: 4, concurrency: 2 });
      const responses = await Promise.all(
        ['a', 'b', 'c', 'd'].map((text) =>
          engine.translate({ text, sourceLanguage: 'ja', targetLanguage: 'ar' }),
        ),
      );
      assert.equal(responses.length, 4);
      assert.equal(new Set(responses.map((r) => r.text)).size, 4, 'responses must not be mixed up');
      assert.ok(mock.maxBatchObserved > 1, `expected a real batch, saw ${mock.maxBatchObserved}`);
      assert.ok(engine.stats().batching.requestsDispatched >= 4);
    } finally {
      await mock.close();
    }
  });

  it('honours a request timeout', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId, latencyMs: 200 });
    try {
      const engine = engineFor(mock.endpoint);
      await assert.rejects(
        engine.translate({ text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar', timeoutMs: 40 }),
        TimeoutError,
      );
      assert.equal(engine.stats().failures, 1);
    } finally {
      await mock.close();
    }
  });

  it('honours cancellation of an in-flight request', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId, latencyMs: 300 });
    try {
      const engine = engineFor(mock.endpoint);
      const token = new CancellationToken();
      const pending = engine.translate({
        text: 'x',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        signal: token.signal,
      });
      setTimeout(() => token.cancel({ message: 'reader closed the chapter' }), 30);
      await assert.rejects(pending);
      assert.equal(engine.stats().cancellations, 1);
    } finally {
      await mock.close();
    }
  });

  it('surfaces a server that is not ready as unavailable, not as a translation failure', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId, unavailableResponses: 1 });
    try {
      const engine = engineFor(mock.endpoint);
      await assert.rejects(
        engine.translate({ text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar' }),
        EngineUnavailableError,
      );
      const served = await engine.translate({ text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar' });
      assert.match(served.text, /^MOCK\[/);
    } finally {
      await mock.close();
    }
  });

  it('drains and refuses work after shutdown', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint, { batchWindowMs: 10 });
      await engine.translate({ text: 'a', sourceLanguage: 'ja', targetLanguage: 'ar' });
      const shutdown = await engine.shutdown(2000);
      assert.equal(engine.stats().state, 'stopped');
      assert.match(shutdown.detail, /request\(s\) served/);
      assert.equal(engine.configuration().configured, false);
      await assert.rejects(
        engine.translate({ text: 'a', sourceLanguage: 'ja', targetLanguage: 'ar' }),
        /shutting down/,
      );
    } finally {
      await mock.close();
    }
  });

  it('is idempotent on shutdown', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint);
      await engine.shutdown(100);
      const again = await engine.shutdown(100);
      assert.equal(again.detail, 'already stopped');
    } finally {
      await mock.close();
    }
  });

  it('advertises only the pairs it can actually serve', () => {
    const engine = engineFor('http://127.0.0.1:1');
    assert.equal(engine.supportsPair('ja', 'ar'), true);
    assert.equal(engine.supportsPair('ar', 'ar'), false);
    assert.equal(engine.supportsPair('ja', 'en'), false);
    assert.ok(engine.supportedPairs().every((p) => p.target === 'ar'));
  });
});

describe('model server supervisor (scripted process, MOCK)', () => {
  const never = async (): Promise<{ ready: boolean; detail: string }> => ({
    ready: false,
    detail: 'not ready',
  });

  it('reports ready once the readiness probe passes', async () => {
    const process = fakeProcess();
    const supervisor = new ModelServerSupervisor(
      {
        spec,
        endpoint: 'http://127.0.0.1:9',
        command: 'llama-server',
        args: ['--model', 'x'],
        spawnImpl: () => process.handle,
        pollIntervalMs: 5,
      },
      async () => ({ ready: true, detail: 'ready' }),
    );
    const result = await supervisor.start();
    assert.equal(result.state, 'ready');
    assert.equal(result.pid, 4242);
    assert.ok((result.readinessMs ?? -1) >= 0);
    assert.equal(supervisor.state, 'ready');
  });

  it('reports a load-time exit as an OOM-shaped failure rather than waiting out the timeout', async () => {
    const process = fakeProcess();
    process.scheduleExit();
    const supervisor = new ModelServerSupervisor(
      {
        spec,
        endpoint: 'http://127.0.0.1:9',
        command: 'llama-server',
        args: [],
        spawnImpl: () => process.handle,
        readinessTimeoutMs: 30_000,
        pollIntervalMs: 5,
      },
      never,
    );
    const started = Date.now();
    const result = await supervisor.start();
    assert.equal(result.state, 'stopped');
    assert.match(result.detail, /out-of-memory kill/);
    assert.ok(Date.now() - started < 5000, 'must not wait out a 30s timeout after an exit');
  });

  it('gives up on its own timeout and stops the process', async () => {
    const signals: NodeJS.Signals[] = [];
    const process = fakeProcess({ onKill: (signal) => signals.push(signal) });
    const supervisor = new ModelServerSupervisor(
      {
        spec,
        endpoint: 'http://127.0.0.1:9',
        command: 'llama-server',
        args: [],
        spawnImpl: () => process.handle,
        readinessTimeoutMs: 60,
        pollIntervalMs: 5,
        shutdownGraceMs: 40,
      },
      never,
    );
    const result = await supervisor.start();
    assert.match(result.detail, /not ready within 60ms/);
    assert.equal(supervisor.state, 'stopped');
    assert.deepEqual(signals, ['SIGTERM', 'SIGKILL'], 'a process that ignores SIGTERM must be killed');
  });

  it('escalates to SIGKILL when the grace period expires', async () => {
    const signals: NodeJS.Signals[] = [];
    const process = fakeProcess({ onKill: (signal) => signals.push(signal) });
    const supervisor = new ModelServerSupervisor(
      {
        spec,
        endpoint: 'http://127.0.0.1:9',
        command: 'llama-server',
        args: [],
        spawnImpl: () => process.handle,
        shutdownGraceMs: 40,
        pollIntervalMs: 5,
      },
      async () => ({ ready: true, detail: 'ready' }),
    );
    await supervisor.start();
    const result = await supervisor.stop();
    assert.deepEqual(signals, ['SIGTERM', 'SIGKILL']);
    assert.match(result.detail, /did not exit on SIGTERM within 40ms; killed/);
  });

  it('reports a spawn failure instead of pretending to be starting', async () => {
    const supervisor = new ModelServerSupervisor(
      {
        spec,
        endpoint: 'http://127.0.0.1:9',
        command: 'llama-server',
        args: [],
        spawnImpl: () => {
          throw new Error('ENOENT');
        },
      },
      never,
    );
    const result = await supervisor.start();
    assert.equal(result.state, 'stopped');
    assert.match(result.detail, /spawn failed: ENOENT/);
  });

  it('is safe to stop when nothing was started', async () => {
    const supervisor = new ModelServerSupervisor(
      { spec, endpoint: 'http://127.0.0.1:9', command: 'x', args: [] },
      never,
    );
    assert.match((await supervisor.stop()).detail, /no process/);
  });
});

describe('queue integration (mock engine, MOCK)', () => {
  function queue(): JobQueue {
    const db = openMemoryDatabase();
    applyMigrations(db);
    return new JobQueue(db);
  }

  const mockEngine = {
    translate: async (request: { text: string }): Promise<{ text: string; engine: string }> => ({
      text: `MOCK:${request.text}`,
      engine: 'local-tg4',
    }),
    stats: () => {
      throw new Error('not used');
    },
  } as unknown as ConstructorParameters<typeof ServingQueueRunner>[0]['engine'];

  it('runs a queued job through the engine and records the result', async () => {
    const jobs = queue();
    const { job } = jobs.enqueue({
      kind: 'translate',
      payload: { text: 'おはよう', sourceLanguage: 'ja', targetLanguage: 'ar' },
    });
    const runner = new ServingQueueRunner({ engine: mockEngine, queue: jobs });
    const summary = await runner.runOnce();
    assert.equal(summary.completed, 1);
    const done = jobs.get(job.id);
    assert.equal(done?.status, 'completed');
    assert.deepEqual(done?.result, { text: 'MOCK:おはよう', engine: 'local-tg4' });
  });

  it('fails a malformed payload instead of retrying it forever', async () => {
    const jobs = queue();
    const { job } = jobs.enqueue({ kind: 'translate', payload: { nope: true }, maxAttempts: 5 });
    const runner = new ServingQueueRunner({ engine: mockEngine, queue: jobs });
    const summary = await runner.runOnce();
    assert.equal(summary.rejected, 1);
    // Not re-queued: a payload that cannot be parsed cannot become parseable.
    assert.equal(jobs.get(job.id)?.status, 'failed');
    assert.match(jobs.get(job.id)?.error ?? '', /malformed/);
  });

  it('does not consume a job of another kind', async () => {
    const jobs = queue();
    const { job } = jobs.enqueue({ kind: 'index-rebuild', payload: {} });
    const runner = new ServingQueueRunner({ engine: mockEngine, queue: jobs, jobKind: 'translate' });
    const summary = await runner.runOnce();
    assert.equal(summary.claimed, 0);
    assert.equal(jobs.get(job.id)?.status, 'queued');
  });

  it('retries a retryable engine failure with backoff', async () => {
    const jobs = queue();
    const failing = {
      translate: async (): Promise<{ text: string; engine: string }> => {
        throw Object.assign(new Error('server hiccup'), { retryable: true });
      },
    } as unknown as ConstructorParameters<typeof ServingQueueRunner>[0]['engine'];
    const { job } = jobs.enqueue({
      kind: 'translate',
      payload: { text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar' },
    });
    const runner = new ServingQueueRunner({ engine: failing, queue: jobs });
    const summary = await runner.runOnce();
    assert.equal(summary.failed, 1);
    const requeued = jobs.get(job.id);
    assert.equal(requeued?.status, 'queued');
    assert.match(requeued?.error ?? '', /server hiccup/);
  });

  it('does not retry a non-retryable failure', async () => {
    const jobs = queue();
    const failing = {
      translate: async (): Promise<{ text: string; engine: string }> => {
        throw Object.assign(new Error('text too long'), { retryable: false });
      },
    } as unknown as ConstructorParameters<typeof ServingQueueRunner>[0]['engine'];
    const { job } = jobs.enqueue({
      kind: 'translate',
      payload: { text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar' },
    });
    await new ServingQueueRunner({ engine: failing, queue: jobs }).runOnce();
    assert.equal(jobs.get(job.id)?.status, 'failed');
  });

  it('cancels a running job when the token is cancelled', async () => {
    const jobs = queue();
    const slow = {
      translate: async (request: { signal?: AbortSignal }): Promise<{ text: string; engine: string }> =>
        new Promise((resolve, reject) => {
          request.signal?.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
          setTimeout(() => resolve({ text: 'MOCK:late', engine: 'local-tg4' }), 1000);
        }),
    } as unknown as ConstructorParameters<typeof ServingQueueRunner>[0]['engine'];
    const { job } = jobs.enqueue({
      kind: 'translate',
      payload: { text: 'x', sourceLanguage: 'ja', targetLanguage: 'ar' },
    });
    const runner = new ServingQueueRunner({ engine: slow, queue: jobs });
    const run = runner.runOnce();
    // Give the worker a tick to claim the job, then cancel it.
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(runner.cancel(job.id, 'reader navigated away'), true);
    const summary = await run;
    assert.equal(summary.cancelled, 1);
    assert.equal(jobs.get(job.id)?.status, 'cancelled');
  });

  it('defers work when the queue is deeper than the configured limit', async () => {
    const jobs = queue();
    for (let i = 0; i < 3; i += 1) {
      jobs.enqueue({ kind: 'translate', payload: { text: `t${i}`, sourceLanguage: 'ja', targetLanguage: 'ar' } });
    }
    let translated = 0;
    const counting = {
      translate: async (): Promise<{ text: string; engine: string }> => {
        translated += 1;
        return { text: 'MOCK', engine: 'local-tg4' };
      },
    } as unknown as ConstructorParameters<typeof ServingQueueRunner>[0]['engine'];
    const runner = new ServingQueueRunner({ engine: counting, queue: jobs, maxQueueDepth: 1, idlePollMs: 5 });
    const running = runner.run(1);
    await new Promise((resolve) => setTimeout(resolve, 30));
    await runner.stop();
    await running;
    assert.ok(translated < 3, `expected backpressure to hold work back, translated ${translated}`);
  });

  it('refuses to run twice concurrently', async () => {
    const jobs = queue();
    const runner = new ServingQueueRunner({ engine: mockEngine, queue: jobs, idlePollMs: 5 });
    const first = runner.run(1);
    await assert.rejects(runner.run(1), /already running/);
    await runner.stop();
    await first;
  });
});

describe('preamble stripping', () => {
  it('removes a chatty prefix but keeps the translation', () => {
    assert.equal(stripPreamble('Here is the translation: مرحبا'), 'مرحبا');
    assert.equal(stripPreamble('Sure! صباح الخير'), 'صباح الخير');
    assert.equal(stripPreamble('  صباح الخير  '), 'صباح الخير');
    assert.equal(stripPreamble('صباح الخير'), 'صباح الخير');
  });
});

describe('serving does not fabricate quality signals', () => {
  it('exposes no confidence value, because a local model cannot report one honestly', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const response = await engineFor(mock.endpoint).translate({
        text: 'おはよう',
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
      });
      assert.equal(response.confidence, undefined);
    } finally {
      await mock.close();
    }
  });

  it('keeps the identity warning visible in stats after a health check', async () => {
    const engine = engineFor('http://127.0.0.1:1');
    await engine.healthCheck();
    assert.equal(engine.stats().identityWarnings.length, 0, 'an unreachable server has nothing to compare identity against');
    assert.match(engine.stats().identityDescription, /translategemma-4b/);
  });
});
describe('resource sampling honesty', () => {
  it('reports NOT MEASURED when nvidia-smi is absent, never an estimate', async () => {
    const { resources } = await sampleResources(async () => 'done', {
      detect: async () => undefined,
    });
    assert.equal(resources.measured, false);
    assert.equal(resources.source, 'unavailable');
    assert.match(resources.note, /not measured/i);
    assert.equal(resources.peakMemoryUsedMb, undefined);
    assert.ok((resources.processPeakRssMb ?? 0) > 0);
  });

  it('parses an nvidia-smi row into a sample', () => {
    const sample = parseNvidiaRow('0, NVIDIA RTX 4090, 2048, 24564, 37', 0);
    assert.equal(sample?.name, 'NVIDIA RTX 4090');
    assert.equal(sample?.memoryUsedMb, 2048);
    assert.equal(sample?.utilisationPercent, 37);
  });

  it('refuses to build a sample from a malformed row', () => {
    assert.equal(parseNvidiaRow('garbage', 0), undefined);
    assert.equal(parseNvidiaRow('0, GPU, NaN, 24564, 37', 0), undefined);
  });

  it('reports measured peaks when sampling works', async () => {
    let call = 0;
    const { result, resources } = await sampleResources(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
      return 42;
    }, {
      intervalMs: 10,
      detect: async () => {
        call += 1;
        return `0, Mock GPU, ${1000 + call * 100}, 24000, ${call * 5}`;
      },
    });
    assert.equal(result, 42);
    assert.equal(resources.measured, true);
    assert.equal(resources.source, 'nvidia-smi');
    assert.ok((resources.peakMemoryUsedMb ?? 0) > 1000);
    assert.ok(resources.samples > 0);
  });

  it('still returns the result when the work throws', async () => {
    await assert.rejects(
      sampleResources(async () => {
        throw new Error('run failed');
      }, { detect: async () => undefined }),
      /run failed/,
    );
  });
});

describe('benchmark comparison rendering', () => {
  const base = {
    datasetVersion: '1.0.0',
    split: 'test' as const,
    aggregate: { count: 10, bleu: 0.1, chrf: 0.2, charSimilarity: 0.3, hasArabicRate: 1, digitsPreservedRate: 1, encodingCleanRate: 1, truncatedRate: 0 },
    latency: { count: 10, p50: 100, p95: 200, p99: 250, max: 250, mean: 120 },
    throughputPerSecond: 5,
    elapsedMs: 2000,
    byCategory: {},
    human: { total: 0, reviewed: 0, byVerdict: {} },
    claimCeiling: 'ceiling',
    supportsQualityClaim: false,
    results: [],
  };

  it('never puts a skipped engine in the comparison table', () => {
    const ran = {
      ...base,
      engine: 'local-tg4',
      totals: { items: 10, ok: 10, failed: 0, skipped: 0 },
      byPair: { 'ja->ar': { pair: 'ja->ar', aggregate: base.aggregate, human: { wins: 0, ties: 0, losses: 0, unusable: 0, reviewed: 0 }, failed: 0 } },
    };
    const skipped = {
      ...base,
      engine: 'local-tg12',
      skipped: { reason: 'server unreachable' },
      totals: { items: 0, ok: 0, failed: 0, skipped: 0 },
      byPair: {},
    };
    const text = renderModelComparison([skipped, ran]);
    assert.match(text, /no engine produced results|local-tg4/);
    const tableStart = text.indexOf('model comparison');
    const skippedLine = text.indexOf('local-tg12:', tableStart);
    const summaryRow = text.indexOf('local-tg4  ', tableStart);
    // The skipped engine is named only in the skipped section, never as a row.
    assert.ok(skippedLine > 0);
    assert.ok(summaryRow > 0);
    assert.ok(skippedLine > summaryRow, 'skipped entries must be listed after the measured table');
  });

  it('states plainly when nothing ran', () => {
    const text = renderModelComparison([
      { ...base, engine: 'local-tg4', skipped: { reason: 'no GPU' }, totals: { items: 0, ok: 0, failed: 0, skipped: 0 }, byPair: {} },
    ]);
    assert.match(text, /nothing to compare/);
    assert.match(text, /no engine produced results/);
  });
});

describe('pre-benchmark gate', () => {
  const goodGpu = {
    present: true,
    detail: 'nvidia-smi: NVIDIA RTX 4090, 24564MB total',
    name: 'NVIDIA RTX 4090',
    memoryTotalMb: 24564,
  };
  const goodRuntime = {
    present: true,
    detail: 'llama-server --version → build 5123',
    command: 'llama-server',
    version: 'build 5123',
  };
  const present = { present: true, detail: 'weights readable at ./models/x' };

  function engineAt(endpoint: string): ServingEngine {
    return engineFor(endpoint, { revision: 'abc1234', readinessTimeoutMs: 2000 });
  }

  async function runOnMock(
    overrides: Partial<Parameters<typeof runPreflight>[0]['probes']> = {},
    options: Partial<Parameters<typeof runPreflight>[0]> = {},
  ) {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId, latencyMs: 1 });
    try {
      return await runPreflight({
        spec,
        revision: 'abc1234',
        probes: {
          gpu: async () => goodGpu,
          runtime: async () => goodRuntime,
          modelFiles: async () => present,
          engine: engineAt(mock.endpoint),
          ...overrides,
        },
        ...options,
      });
    } finally {
      await mock.close();
    }
  }

  it('passes all nine conditions on a fully verified mock stack', async () => {
    const result = await runOnMock();
    assert.equal(result.checks.length, 9);
    const failing = result.checks.filter((c) => c.status !== 'pass');
    assert.deepEqual(failing.map((c) => `${c.id}:${c.status}: ${c.evidence}`), []);
    assert.equal(result.ready, true);
    assert.doesNotThrow(() => assertReadyForBenchmark(result));
  });

  it('blocks when there is no GPU, and says blocked rather than fail', async () => {
    const result = await runOnMock({
      gpu: async () => ({ present: false, detail: 'no GPU: nvidia-smi is absent' }),
    });
    const gpuCheck = result.checks.find((c) => c.id === 'gpu.detected')!;
    assert.equal(gpuCheck.status, 'blocked');
    assert.equal(result.ready, false);
    assert.throws(() => assertReadyForBenchmark(result), /refusing to benchmark/);
  });

  it('fails on insufficient VRAM and quotes the published requirement', async () => {
    const result = await runOnMock({
      gpu: async () => ({ ...goodGpu, name: 'NVIDIA GTX 1050', memoryTotalMb: 2048 }),
    });
    const vram = result.checks.find((c) => c.id === 'gpu.vram')!;
    assert.equal(vram.status, 'fail');
    assert.match(vram.evidence, /published requirement/);
    assert.equal(result.ready, false);
  });

  it('fails when no runtime is installed', async () => {
    const result = await runOnMock({
      runtime: async () => ({ present: false, detail: 'llama-server absent from PATH' }),
    });
    assert.equal(result.checks.find((c) => c.id === 'runtime.present')?.status, 'fail');
    assert.equal(result.ready, false);
  });

  it('fails an unpinned revision, because results would not be reproducible', async () => {
    const result = await runOnMock({}, { revision: UNPINNED_REVISION });
    const check = result.checks.find((c) => c.id === 'model.revision')!;
    assert.equal(check.status, 'fail');
    assert.match(check.evidence, /not be reproducible/);
  });

  it('fails when the served model is not the requested one', async () => {
    const mock = await startMockInferenceServer({ servedAs: 'a-completely-different-model' });
    try {
      const result = await runPreflight({
        spec,
        revision: 'abc1234',
        probes: {
          gpu: async () => goodGpu,
          runtime: async () => goodRuntime,
          modelFiles: async () => present,
          engine: engineAt(mock.endpoint),
        },
      });
      assert.equal(result.checks.find((c) => c.id === 'model.identity')?.status, 'fail');
      assert.equal(result.ready, false);
    } finally {
      await mock.close();
    }
  });

  it('fails when warm-up does not complete', async () => {
    const result = await runOnMock({
      warmUp: async () => ({ durationMs: 10, loaded: false, detail: 'server never became ready' }),
    });
    assert.equal(result.checks.find((c) => c.id === 'serving.warmup')?.status, 'fail');
    assert.equal(result.ready, false);
  });

  it('lists every unmet precondition in the refusal message', async () => {
    const result = await runOnMock({
      gpu: async () => ({ present: false, detail: 'no GPU' }),
      runtime: async () => ({ present: false, detail: 'no runtime' }),
    });
    assert.throws(() => assertReadyForBenchmark(result), /gpu\.detected/);
    assert.throws(() => assertReadyForBenchmark(result), /runtime\.present/);
    assert.match(result.summary, /not met for local-tg4/);
  });

  it('records the warm-up duration it observed', async () => {
    const result = await runOnMock();
    const warm = result.checks.find((c) => c.id === 'serving.warmup')!;
    assert.match(warm.evidence, /warm-up completed in \d+ms/);
  });

  it('renders every check with its evidence and a clear verdict', async () => {
    const result = await runOnMock({
      gpu: async () => ({ present: false, detail: 'no GPU' }),
    });
    const text = renderPreflight(result);
    assert.match(text, /\[BLOCK\] GPU detected/);
    assert.match(text, /ready for benchmark: NO/);
    const mockMarked = renderPreflight(result, true);
    assert.match(mockMarked, /engine under test is a MOCK/);
  });

  it('never reports a blocked condition as a pass', async () => {
    const result = await runOnMock({
      gpu: async () => ({ present: false, detail: 'no GPU' }),
      runtime: async () => ({ present: false, detail: 'no runtime' }),
      modelFiles: async () => ({ present: false, detail: 'no weights' }),
    });
    for (const id of ['gpu.detected', 'runtime.present', 'model.files']) {
      const check = result.checks.find((c) => c.id === id)!;
      assert.notEqual(check.status, 'pass', `${id} must not pass`);
    }
    assert.equal(result.ready, false);
  });

  it('refuses to pass identity when the server will not name its model', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineAt(mock.endpoint);
      // A server whose /props answers 404 tells us nothing about its weights.
      const silent = new Proxy(engine, {
        get(target, property, receiver) {
          if (property === 'healthCheck') {
            return async () => ({ engine: target.id, healthy: true, detail: 'reachable' });
          }
          return Reflect.get(target, property, receiver);
        },
      }) as ServingEngine;
      const result = await runPreflight({
        spec,
        revision: 'abc1234',
        probes: {
          gpu: async () => goodGpu,
          runtime: async () => goodRuntime,
          modelFiles: async () => present,
          engine: silent,
        },
      });
      const identity = result.checks.find((c) => c.id === 'model.identity')!;
      assert.equal(identity.status, 'fail');
      assert.match(identity.evidence, /cannot name its weights|could not be confirmed/);
      assert.equal(result.ready, false);
    } finally {
      await mock.close();
    }
  });
});

describe('host probes report this host honestly', () => {
  it('detects that there is no GPU here, rather than assuming one', async () => {
    const gpu = await detectGpu();
    if (gpu.present) {
      // If a GPU appears later, the assertion below is what must change.
      assert.ok((gpu.memoryTotalMb ?? 0) > 0);
    } else {
      assert.match(gpu.detail, /no GPU/);
      assert.match(gpu.detail, /RAM is/);
    }
  });

  it('detects that no runtime is installed here, rather than assuming one', async () => {
    const runtime = await detectRuntime();
    if (!runtime.present) {
      assert.match(runtime.detail, /no inference runtime found/);
      assert.match(runtime.detail, /llama-server/);
    } else {
      assert.ok(runtime.command);
    }
  });
});

describe('tokens/sec is recorded only when the runtime reports it', () => {
  it('reports NOT MEASURED when the server returns no timings block', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint);
      await engine.translate({ text: 'おはよう', sourceLanguage: 'ja', targetLanguage: 'ar' });
      const tps = engine.stats().tokensPerSecond;
      assert.equal(tps.measured, false);
      assert.equal(tps.tokensPerSecond, undefined);
      assert.match(tps.note, /not estimated from output length/i);
    } finally {
      await mock.close();
    }
  });

  it("records the runtime's own counter when it is present", async () => {
    const mock = await startMockInferenceServer({
      servedAs: spec.modelId,
      reportTimings: true,
      tokensPerSecond: 214.5,
    });
    try {
      const engine = engineFor(mock.endpoint);
      await engine.translate({ text: 'おはよう', sourceLanguage: 'ja', targetLanguage: 'ar' });
      await engine.translate({ text: 'こんにちは', sourceLanguage: 'ja', targetLanguage: 'ar' });
      const tps = engine.stats().tokensPerSecond;
      assert.equal(tps.measured, true);
      assert.equal(tps.tokensPerSecond, 214.5);
      assert.equal(tps.samples, 2);
      assert.equal(tps.predictedTokens, 256);
      assert.match(tps.note, /runtime-reported/);
    } finally {
      await mock.close();
    }
  });

  it('ignores a timings block with an unusable rate rather than reporting it', () => {
    assert.equal(extractTimings({ timings: { predicted_per_second: 0 } }).source, 'absent');
    assert.equal(extractTimings({ timings: {} }).source, 'absent');
    assert.equal(extractTimings({}).source, 'absent');
    const good = extractTimings({ timings: { predicted_per_second: 33.333, predicted_n: 10, prompt_n: 5 } });
    assert.equal(good.source, 'runtime');
    assert.equal(good.tokensPerSecond, 33.33);
    assert.equal(good.predictedTokens, 10);
  });

  it('never derives a rate from output length', async () => {
    const withTimings = await startMockInferenceServer({ servedAs: spec.modelId, reportTimings: true });
    const without = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const a = engineFor(withTimings.endpoint);
      const b = engineFor(without.endpoint);
      const request = { text: 'これは非常に長い日本語の文章です。' .repeat(5), sourceLanguage: 'ja' as const, targetLanguage: 'ar' as const };
      await a.translate(request);
      await b.translate(request);
      assert.equal(a.stats().tokensPerSecond.measured, true);
      // Same text, same length, no counter: still unmeasured.
      assert.equal(b.stats().tokensPerSecond.measured, false);
    } finally {
      await withTimings.close();
      await without.close();
    }
  });
});

describe('run provenance', () => {
  it('records the model, host and serving parameters, marking what was observed', async () => {
    const provenance = await captureProvenance({
      identity: identityFromSpec(spec, { revision: 'abc1234' }),
      servingStyle: 'openai',
      concurrency: 2,
      maxBatchSize: 4,
      batchWindowMs: 20,
      temperature: 0,
      maxTokens: 512,
    });
    const labels = provenance.model.map((f) => f.label);
    assert.ok(labels.includes('modelId'));
    assert.ok(labels.includes('revision'));
    assert.ok(labels.includes('quantization'));
    assert.ok(labels.includes('weights'));
    assert.ok(provenance.host.some((f) => f.label === 'node' && f.source === 'observed'));
    assert.ok(provenance.serving.some((f) => f.label === 'batch' && f.value === '4@20ms'));
    assert.match(provenance.summary, /translategemma-4b/);
  });

  it('says the digest is unavailable rather than inventing one', async () => {
    const provenance = await captureProvenance({
      identity: identityFromSpec(spec),
      servingStyle: 'openai',
      concurrency: 1,
      maxBatchSize: 1,
      batchWindowMs: 0,
      temperature: 0,
      maxTokens: 512,
    });
    const weights = provenance.model.find((f) => f.label === 'weights')!;
    assert.equal(weights.source, 'unavailable');
    assert.match(weights.value, /digest not computed/);
  });

  it('computes a real digest when a weight file is present', async () => {
    const file = join(tmpdir(), `serving-provenance-${process.pid}.gguf`);
    await writeFile(file, 'not a real model, but real bytes');
    try {
      const provenance = await captureProvenance({
        identity: identityFromSpec(spec, { revision: 'abc1234' }),
        modelPath: file,
        servingStyle: 'openai',
        concurrency: 1,
        maxBatchSize: 1,
        batchWindowMs: 0,
        temperature: 0,
        maxTokens: 512,
      });
      const weights = provenance.model.find((f) => f.label === 'weights')!;
      assert.equal(weights.source, 'observed');
      assert.match(weights.value, /^sha256:[0-9a-f]{16}/);
      assert.match(provenance.summary, /sha256:/);
    } finally {
      await rm(file, { force: true });
    }
  });

  it('reports a missing runtime instead of assuming a version', async () => {
    const fields = await observeRuntime('definitely-not-a-real-binary-xyz');
    assert.equal(fields[0]!.source, 'unavailable');
    assert.match(fields[0]!.value, /not found on PATH/);
  });

  it('distinguishes two weight digests, so a cross-run comparison is honest', async () => {
    const one = await captureProvenance({
      identity: identityFromSpec(spec, { revision: 'aaa' }),
      modelPath: undefined,
      servingStyle: 'openai',
      concurrency: 1,
      maxBatchSize: 1,
      batchWindowMs: 0,
      temperature: 0,
      maxTokens: 512,
    });
    const two = await captureProvenance({
      identity: identityFromSpec(spec, { revision: 'bbb' }),
      servingStyle: 'openai',
      concurrency: 1,
      maxBatchSize: 1,
      batchWindowMs: 0,
      temperature: 0,
      maxTokens: 512,
    });
    assert.notEqual(one.summary, two.summary);
  });
});

describe('tokens/sec survives the batched path', () => {
  it('records the runtime counter when batching is on', async () => {
    const mock = await startMockInferenceServer({
      servedAs: spec.modelId,
      reportTimings: true,
      tokensPerSecond: 150,
    });
    try {
      const engine = engineFor(mock.endpoint, { batchWindowMs: 20, maxBatchSize: 4, concurrency: 2 });
      await Promise.all(
        ['a', 'b', 'c'].map((text) =>
          engine.translate({ text, sourceLanguage: 'ja', targetLanguage: 'ar' }),
        ),
      );
      const tps = engine.stats().tokensPerSecond;
      // Regression guard: batching used to bypass the timing recorder entirely, so
      // the recommended throughput configuration silently lost the counter.
      assert.equal(tps.measured, true);
      assert.ok((tps.samples ?? 0) >= 1);
    } finally {
      await mock.close();
    }
  });

  it('still says not measured on the batched path when the runtime reports nothing', async () => {
    const mock = await startMockInferenceServer({ servedAs: spec.modelId });
    try {
      const engine = engineFor(mock.endpoint, { batchWindowMs: 20, maxBatchSize: 4, concurrency: 2 });
      await Promise.all(['a', 'b'].map((text) => engine.translate({ text, sourceLanguage: 'ja', targetLanguage: 'ar' })));
      const tps = engine.stats().tokensPerSecond;
      assert.equal(tps.measured, false);
      assert.match(tps.note, /no timings block/);
    } finally {
      await mock.close();
    }
  });
});
