# Benchmark status: what has and has not been measured

Date of last real run attempt: 2026-10-07
Dataset: `eval/` v1.0.0 — 196 items, 49 per source language (ja, zh, ko, en) → ar
Suite: **597 passing, 0 failing** (`npm run verify`)

This file exists so the model decision in `docs/model-selection.md` cannot be
mistaken for a decision that has already been validated. It records what was
actually executed on the actual hardware.

## Recorded state

| Area | State |
| --- | --- |
| Evaluation / holdout infrastructure | **Implemented + Tested** |
| Metric behaviour (BLEU, chrF++, validity checks) | **Measured** |
| Serving layer (loading, identity, health, warm-up, batching, concurrency, timeout, cancellation, shutdown, metrics, queue) | **Implemented + Tested against a mock server** |
| Actual model quality | **NOT MEASURED** |
| Actual model latency / throughput / VRAM | **NOT MEASURED** |
| Default production model | **UNDECIDED** |
| Reason not measured | No GPU and no model runtime in the current environment |

Pre-benchmark gate: **9 preconditions, implemented, tested and enforced by default**
(`src/platform/serving/preflight.ts`, `npm run preflight`). On this host 8 of 9
fail or are blocked for every candidate, so `npm run benchmark` refuses to produce
numbers and reports each engine as `SKIPPED: preflight not met: …`.

`selectDefaultModel()` throws by design, so no code path can assume a default.

## Environment, verified rather than assumed

- **No GPU.** `/dev/dri` and `/dev/nvidia0` are both absent; `nvidia-smi` absent.
- **7.6 GB host RAM** — below the published floor for every candidate.
- **No model runtime.** No `ollama`, no `llama-cli`, no `torch`, no `transformers`.
- No server listening on 8081, 8082 or 8083.
- Wiktionary API reachable. MyMemory daily quota exhausted for this IP.

## Implemented and tested

| Component | Path | Tests |
| --- | --- | --- |
| Corpus + strict validation | `src/platform/eval/dataset.ts` | `test/platform/eval.test.ts` |
| Filesystem corpus loader | `src/platform/eval/load.ts` | idem |
| Holdout enforcement at the writers | `src/platform/eval/holdout.ts`, `src/platform/eval/guard.ts` | idem |
| BLEU + chrF++ + validity checks | `src/platform/eval/scoring.ts` | idem |
| Run/report/render/comparison | `src/platform/benchmark/runner.ts` | idem |
| Model catalog (requirements as *published*) | `src/platform/serving/modelCatalog.ts` | `test/platform/serving.test.ts` |
| Model/version identity | `src/platform/serving/modelIdentity.ts` | idem |
| Concurrency + micro-batching | `src/platform/serving/concurrency.ts` | idem |
| Inference transport (3 wire formats) | `src/platform/serving/httpClient.ts` | idem |
| `ServingEngine` (drop-in `TranslationEngine`) | `src/platform/serving/servingEngine.ts` | idem |
| Process supervision / graceful shutdown | `src/platform/serving/supervisor.ts` | idem |
| Queue integration | `src/platform/serving/queueRunner.ts` | idem |
| Resource sampling (never estimates) | `src/platform/serving/resourceSampler.ts` | idem |
| Pre-benchmark gate (9 conditions) | `src/platform/serving/preflight.ts` | idem |
| Run provenance (weights digest, build, GPU, sampling) | `src/platform/serving/provenance.ts` | idem |
| Runtime-reported tokens/sec (never estimated) | `src/platform/serving/httpClient.ts` | idem |
| Serve CLI (start / readiness / warm-up / drain) | `src/scripts/serve.ts` | manual run below |
| Preflight CLI | `src/scripts/preflight.ts` | manual run below |
| Benchmark CLI | `src/scripts/benchmark.ts` | manual run below |
| Serving plan CLI | `src/scripts/servingPlan.ts` | manual run below |
| Runbook | `docs/model-serving-runbook.md` | — |

Suite: **597 passing, 0 failing** (`npm run verify`).

Serving-layer tests run against a **mock inference server** whose output is prefixed
`MOCK` and whose child process is scripted. They verify protocol and lifecycle
behaviour. They do not measure model quality, model latency, model throughput or
VRAM, and no test in this repository can.

Metric behaviour verified against known-answer cases:

- identical hypothesis/reference → chrF 1.0, BLEU 1.0, similarity 1.0
- unrelated Arabic → chrF 0.0, BLEU 0.0, similarity 0.0
- paraphrase → partial credit, strictly between 0 and 1
- empty hypothesis → 0 (no exception)
- Arabic diacritics and tatweel stripped, so correct-but-vocalised output is not penalised
- digit loss detected; digit preservation detected

## Not measured

No local model was executed. Three independent blockers, all verified on the
host rather than assumed:

1. **No GPU.** `/dev/dri` and `/dev/nvidia0` are both absent.
2. **No model runtime.** No `ollama`, no `llama-cli`, no Python `torch`, no
   `transformers`.
3. **No local server listening.** The benchmark probes each endpoint before
   running and reports it as SKIPPED.

Observed CLI output on this host:

```
dataset 1.0.0 · split=test · 5 item(s) · references human-verified: 0/196

=== local-tg4 · split=test · dataset=1.0.0 ===
  SKIPPED: server unreachable at http://127.0.0.1:8081/health: fetch failed

=== local-tg12 · split=test · dataset=1.0.0 ===
  SKIPPED: server unreachable at http://127.0.0.1:8082/health: fetch failed

=== local-madlad3b · split=test · dataset=1.0.0 ===
  SKIPPED: server unreachable at http://127.0.0.1:8083/health: fetch failed

=== model comparison (manga/manhwa evaluation corpus) ===
  no engine produced results, so there is nothing to compare.

  skipped (no numbers exist for these):
    local-tg4: server unreachable at http://127.0.0.1:8081/health: fetch failed
    local-tg12: server unreachable at http://127.0.0.1:8082/health: fetch failed
    local-madlad3b: server unreachable at http://127.0.0.1:8083/health: fetch failed
```

The harness itself was proven end to end against a **mock** server: warm-up →
8-item corpus → per-pair and per-category scores → latency percentiles →
throughput → `resources: NOT MEASURED — nvidia-smi is not available on this host`.
That run exercised the pipeline; it produced no model quality, model latency or VRAM
number of any kind.

Consequences, stated plainly:

- **No chrF/BLEU has been produced by any candidate model.**
- **No latency, throughput or VRAM figure has been produced by a real run.**
- The default model selection therefore remains **undecided**. The candidate
  shortlist from `docs/model-selection.md` stands as research; the default stays
  unset until a run happens.

## What the reference set is, precisely

All 196 references are AI-drafted and **zero are human-verified**. Consequences
enforced in code by `supportsQualityClaim()` / `claimCeiling()`:

- scores may be compared **between models** (agreement with one fixed reference),
- scores may **not** be reported as absolute translation quality,
- `claimCeiling()` is attached to every report and printed by the CLI.

This is not a formality. A chrF of 0.4 against a machine-drafted reference means
"disagrees with this draft", which is not the same statement as "wrong".

## Holdout: enforced, not promised

The test split (92 items) is blocked at the writers, not by convention:

- `KnowledgeRepository.upsert` — blocks on term **and** on proposed translation
- `TranslationMemoryRepository.store` — blocks on source text **and** target text
- `ResearchQueue.enqueue` — blocks on term; `Prewarmer` is covered through it
- normalization strips bidi controls and zero-width marks, so a copied line from
  an Arabic document cannot evade the check

Tests cover the blocked cases and confirm ordinary content still flows.

## Bugs found and fixed while building this

1. `LocalHttpEngine.healthCheck` swallowed the fetch rejection and then defaulted
   `healthy` to `true`. An unreachable model server was reported as healthy, so
   routing would have sent traffic to a dead endpoint and the benchmark reported
   "3 failed items" instead of "engine unavailable". Regression test added.
2. `runEval` accepted `judgements` and never forwarded them to `buildReport`, so
   every human review file was silently ignored and reports always said "no human
   judgements recorded".
3. BLEU divided by all four n-gram orders even when the hypothesis was shorter
   than the order, scoring a correct two-word output as 0. Now uses effective
   order, matching how BLEU is actually computed.
4. `normalizeTerm` / `normalizeForMemory` kept bidi controls and zero-width
   marks, which defeated the exact-match leakage check.

Found while building the serving layer:

5. `Semaphore.acquire()` without a timeout skipped the limit entirely and drove the
   counter negative, so every caller asking for "no timeout" ran at once — the exact
   failure a semaphore exists to prevent.
6. The batched dispatch path labelled every item as English, whatever its source
   language. A mixed-language batch would have mislabelled every non-first item.
7. `extractBatch` did not read llama.cpp's `content` field, so a real llama.cpp
   server would have been treated as returning no text at all.
8. Server identity was compared literally, so a llama.cpp `/props` path such as
   `translategemma-4b-it-qat-q4_K_M.gguf` never matched the requested
   `translategemma-4b` — every real deployment would have reported "serving the
   wrong model". Replaced with an asymmetric segment-prefix match that still
   rejects `4b` vs `12b`.
9. An aborted request was rethrown as a generic `EngineError`, hiding cancellation
   from the cancellation accounting and inviting a retry of work the caller had
   already abandoned.
10. `ModelServerSupervisor` reported "exited on SIGTERM" even after escalating to
    `SIGKILL` — a wedged process was recorded as a clean shutdown.
11. A failed `warmUp()` left the engine stuck in `warming` forever.
12. `parseNvidiaRow` read the leading `index` column as the GPU name, making every
    row unparseable and turning "GPU present" into "nothing measured".
13. `JobQueue.cancel()` only terminates *queued* jobs, so a job cancelled while
    running stayed `running` until the stale-job reaper — indistinguishable from a
    hung worker.
14. `JobQueue.fail()` ignored non-retryable failures, so a permanently invalid
    payload consumed the whole attempt budget before stopping.

Found while building the pre-benchmark gate:

15. Identity verification conflated "no warnings" with "matched": a server that
    reported no model name at all produced an empty warning list and could have
    been read as a pass. `ServingEngine.stats()` now exposes `servedAs` and
    `identityConfirmed` so the two cases are distinguishable.
16. The gate's warm-up check inherited the engine's production readiness budget
    (minutes), so a check meant to be fast became the slowest step in the runbook.
    The warm-up probe is now injectable and bounded.
17. The batched request path never recorded the runtime's decode timings, so
    tokens/sec was silently lost under exactly the batching configuration used for
    throughput measurement — the report said "no request has completed yet" after
    six successful ones. Regression test added.

## To unblock

1. Install a runtime (llama.cpp server or ollama) on a machine with a GPU and
   enough RAM for at least one candidate.
2. Run `npm run serving:plan` on that host; it prints the catalog and filters it to
   what fits.
3. Serve the candidates on `8081` / `8082` / `8083` per
   `docs/model-serving-runbook.md`, and pin each revision.
4. `npm run benchmark -- --split test --revision <sha> --json bench-<model>.json`,
   then repeat for the other candidates with identical `--concurrency`.
5. Have a fluent Arabic reviewer fill `eval/judgements-test.json`. Until then every
   report keeps the ceiling text, which is correct behaviour.
6. Only then update `docs/model-selection.md` and remove the throw from
   `selectDefaultModel()`.

The MyMemory daily quota for this host is also exhausted, so it is not usable as a
live comparison either. That is a live-test skip, not a product limitation:
MyMemory stays an optional fallback and never a core dependency.