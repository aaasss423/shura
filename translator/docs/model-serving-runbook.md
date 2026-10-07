# Model serving runbook

How to bring up a candidate model, prove it is really loaded, and produce
measurements that can be trusted. Written to be followed on a machine that has a
GPU, because this one does not.

## The short version

```bash
npm run preflight                                  # 9 conditions; non-zero exit if not ready
npm run serve -- --model local-tg4 --revision <sha> # one terminal
npm run benchmark -- --engines local-tg4,local-tg12,local-madlad3b --split test --revision <sha> --json bench.json
```

**Nothing in this document has been executed here.** The current host has no GPU
(`/dev/dri`, `/dev/nvidia0` absent), no model runtime, and 7.6 GB of RAM — below
the published floor for every candidate. All commands below are unverified on this
host.

---

## 1. Requirements

### Hardware

| Candidate | Model | Published VRAM | Published host RAM | Download |
| --- | --- | --- | --- | --- |
| `local-tg4` | TranslateGemma 4B (Q4_K_M) | ~3 GB | 8 GB | ~3 GB |
| `local-tg12` | TranslateGemma 12B (Q4_K_M) | ~8.1 GB | 16 GB | ~8 GB |
| `local-madlad3b` | MADLAD-400 3B MT (Q4_K_M) | ~6 GB | 8 GB | ~6 GB |

Source: published model/vendor documentation. `requirementsSource: 'published'` in
`src/platform/serving/modelCatalog.ts` — never `measured`. Confirm against your own
host with `npm run serving:plan`, which prints the catalog alongside detected GPU
and RAM and filters the candidate list to what fits.

Practical floor: a 12 GB card serves all three. An 8 GB card serves the 4B and
MADLAD. A 4 GB card serves only the 4B.

CPU-only execution is deliberately not offered: at these sizes a chapter would
translate far slower than a reader waits.

### Runtime

- **NVIDIA driver + CUDA** — verify with `nvidia-smi`
- **llama.cpp server** (`llama-server`) — primary path
- **ollama** — alternative, OpenAI-compatible shim available
- Node.js >= 20.11, no npm dependencies beyond TypeScript and `@types/node`

No Python, torch or transformers is required. The platform reaches the model over
HTTP and holds zero ML dependencies.

---

## 2. Install and start a model

Each candidate gets its own port so they can be compared side by side without
stopping one to measure the next.

### TranslateGemma 4B — port 8081

```bash
llama-server -hf google/translategemma-4b-it-qat-q4_K_M \
  --port 8081 --jinja --alias translategemma-4b
```

Use the instruction-tuned variant. The base model does not follow the translation
prompt, and the difference will look like a model-quality problem rather than a
model-choice problem.

### TranslateGemma 12B — port 8082

```bash
llama-server -hf google/translategemma-12b-it-qat-q4_K_M \
  --port 8082 --jinja --alias translategemma-12b
```

### MADLAD-400 3B MT — port 8083

```bash
huggingface-cli download google/madlad400-3b-mt-GGUF --local-dir ./models/madlad400-3b-mt
llama-server -m ./models/madlad400-3b-mt/*.q4_K_M.gguf \
  --port 8083 --alias madlad400-3b-mt
```

MADLAD is an MT model: no prompt-following. Register, honorifics and manga speech
must come from the glossary and context layers, not from instructions in the
prompt. Its licence (Apache-2.0) is the cleanest of the three.

### Licence note

The Gemma family (4B and 12B) is governed by the Gemma Terms of Use, not by a
standard OSS licence. Accept them before shipping. NLLB-200 and Unbabel Tower were
excluded earlier for exactly this reason (CC-BY-NC-4.0, non-commercial).

---

## 3. Verify it is really up — the nine preconditions

```bash
npm run preflight                      # all three candidates
npm run preflight -- --model local-tg4 # one candidate
```

Each check reports `pass`, `fail` or `blocked`, plus the evidence it observed.
`blocked` means *this host cannot answer the question* (no GPU, no `nvidia-smi`);
`fail` means *the host answered and the answer was wrong*. The distinction matters:
a blocked environment is not a broken model.

| # | Check | Passes when |
| --- | --- | --- |
| 1 | `gpu.detected` | `nvidia-smi` responds, or GPU device nodes exist |
| 2 | `gpu.vram` | reported VRAM ≥ the candidate's **published** requirement |
| 3 | `runtime.present` | `llama-server`, `llama-cpp-server` or `ollama` executes and prints a version |
| 4 | `model.revision` | a revision is pinned via `--revision <sha>`, not `UNPINNED` |
| 5 | `model.files` | weights are readable at the configured path |
| 6 | `model.quantization` | a quantization is fixed (it is part of the cache key) |
| 7 | `serving.process` | the health probe answers on the configured endpoint |
| 8 | `model.identity` | the server **names** its model and it matches the request |
| 9 | `serving.warmup` | a throwaway decode completes |

The gate is **on by default in the benchmark**. A model whose preflight fails is
reported as `SKIPPED: preflight not met: …` and no numbers are produced. Two
traps it exists to prevent, both covered by tests:

- **The port is not readiness.** A llama.cpp server binds before the weights are
  resident. Readiness is polled, never inferred from an open port.
- **The model may not be the one you asked for.** A server that will not name its
  weights fails check 8 rather than being benchmarked. A 12B answering while the
  request said 4B would reorder the whole comparison table.

`npm run preflight` exits non-zero unless every candidate is ready, so it works as a
CI or shell-pipeline step.

## 4. Serve a model

One command per candidate. It runs preflight, starts the server, waits for real
readiness, verifies identity, warms up, and stays up.

```bash
npm run serve -- --model local-tg4 --revision <sha>
npm run serve -- --model local-madlad3b --revision <sha> --models ./models/madlad400-3b-mt
```

It refuses to start when preflight fails, and prints the blockers instead. On
`SIGINT`/`SIGTERM` it drains the engine, then sends `SIGTERM` to the server, waits
the grace period, then `SIGKILL`. It also exits if the server stops answering
health checks while idle, so a crashed model does not leave a "serving" process
behind.

To run the server yourself instead (systemd, container, separate terminal), skip
this step and use the direct commands in section 2; preflight covers both paths.

## 5. Benchmark

The sequence is fixed: identity → readiness → warm-up → corpus → quality metrics →
latency → throughput → resources.

```bash
# one candidate
npm run benchmark -- --engines local-tg4 --split test --revision <sha>

# all three, side by side
npm run benchmark -- \
  --engines local-tg4,local-tg12,local-madlad3b \
  --split test --revision <sha> \
  --concurrency 1 \
  --json bench-baseline.json
```

### Flags

| Flag | Meaning |
| --- | --- |
| `--engines a,b,c` | which candidates to run |
| `--split dev\|test` | evaluation split; `test` is the holdout |
| `--revision <sha>` | pin the weights revision (required by preflight) |
| `--concurrency N` | in-flight requests; default 1 |
| `--batch-size N` / `--batch-window-ms N` | micro-batching; `0` disables it |
| `--budget-ms N` | cap the whole run |
| `--limit N` | first N items, for a smoke pass |
| `--show-samples N` | print N item-level outputs |
| `--json PATH` | machine-readable report |
| `--no-preflight` | skip the gate (deliberate, and recorded in the output) |
| `--force` | run the corpus anyway after a failed gate |

### What is recorded per candidate

Quality: chrF++, BLEU, char similarity, Arabic presence, digit preservation,
truncation rate — globally, **per language pair** (`ja→ar`, `zh→ar`, `ko→ar`,
`en→ar`) and **per category** (`dialogue`, `slang`, `idiom`, `honorific`, `name`,
`context_dependent`, `sound_effect`, `short`, `long`, `colloquial`,
`manga_expression`).

Latency: p50/p95/p99, mean, max. Throughput: items/sec over successful items.
Serving: concurrency, batch size, batch window, warm-up time, readiness time,
identity key, engine state. Resources: peak GPU memory, peak GPU utilisation, GPU
name, process RSS. Plus request/failure/cancellation counts, failures per category
and mean batch wait.

**Provenance** is recorded on every report, and this is what makes a result
reproducible rather than merely repeatable:

| Field | Source |
| --- | --- |
| modelId, quantization, concurrency, batch, temperature, maxTokens | configured |
| revision | configured — `UNPINNED` is recorded as such, not hidden |
| weights `sha256:…` | observed, when `--model-path` is given |
| GPU name + total VRAM, driver, platform, CPU, RAM, Node, runtime build | observed |
| context length | observed, when the server reports it |

A report without a weights digest says so, and the comparison table prints the
digest column with the warning that a comparison across two digests is a comparison
of two different models. Pass `--model-path` on the first real run:

```bash
npm run benchmark -- --engines local-tg4 --split test --revision <sha> \
  --model-path ./models/translategemma-4b/translategemma-4b-it-qat-q4_K_M.gguf \
  --json bench-tg4.json
```

### Tokens/sec

Recorded **only** from the serving runtime's own counter. llama.cpp returns a
`timings` block with `predicted_per_second`; when it is present, the mean is
reported and labelled `runtime-reported`. When it is absent the report says:

```
tokens/sec: NOT MEASURED — the serving runtime returned no timings block,
so tokens/sec is not measured. It is deliberately not estimated from output length.
```

There is no character-based proxy anywhere in the code, and a test asserts that the
same long text is `measured: true` on a server that reports a rate and
`measured: false` on one that does not.

Then a comparison table ranked by chrF with separate rows per pair and per
category. A candidate that could not run appears only in the `skipped` section and
never in the table.

### Latency measurement discipline

- **Keep `--concurrency 1` and `--batch-size 1` for the latency baseline.** Then
  repeat at the target concurrency to record throughput. Report both; they answer
  different questions.
- **Tokens/sec** is not collected. None of the three serving paths expose a token
  counter in a form this platform trusts, so the report omits it rather than
  deriving one from characters. If your server exposes it, read it from
  `/metrics` or the server log and attach it manually.
- **VRAM/RAM come from `nvidia-smi` sampled during the run.** Without it the report
  says `NOT MEASURED`. It never estimates.
- **Never mix mock and real numbers.** Mock runs exist only to validate the harness
  and their output is prefixed `MOCK`.

### Human review

`eval/judgements-test.json` starts empty. A fluent Arabic reviewer fills
`{itemId, verdict: win|tie|loss|unusable, reviewer, note}`.

Until that file is non-empty, every report prints
`human: no judgements recorded (human review not performed)` and no selection may
be recorded. Automatic metrics cannot rank Arabic register, idiom handling or
honorific nuance; that is the human layer's job.

---

## 6. Decision gate

A default model may be selected only when all of these hold:

1. a real run on a GPU host, recorded in `docs/benchmark-status.md`
2. per-pair and per-category results, not a single global average
3. latency and throughput at a stated concurrency
4. VRAM and host RAM measured on that host
5. licence compatible with the intended distribution
6. human review of at least the categories the decision turns on

Then update `docs/model-selection.md` and remove the throw from
`selectDefaultModel()`. Until then that function throws on purpose, so no code path
can quietly assume a default.

---

## 7. Operating the engine in production

`ServingEngine` implements the platform's existing `TranslationEngine` interface —
nothing above it changes. Two extra pieces sit alongside it:

**Supervisor** (`ModelServerSupervisor`) — starts the server as a child process,
waits for real readiness, and on stop sends `SIGTERM`, waits the grace period, then
`SIGKILL`. A process that dies during load is reported as an out-of-memory kill
with the published VRAM requirement quoted, instead of being polled until timeout.
Leave `manageProcess: false` when systemd or a container owns the process.

**Queue runner** (`ServingQueueRunner`) — drains the existing `JobQueue` through the
engine. Applies backpressure when the queue is deeper than `maxQueueDepth`, claims
only its own job kind, propagates cancellation to the in-flight request so a
cancelled job stops holding a VRAM slot, and terminates cancelled running jobs
rather than leaving them `running`.

### Health endpoint

`ServingEngine.healthCheck()` returns healthy only when the transport answers
*and* the served model matches the request. Wire it into the platform health route;
it is safe to poll, and it fails closed.

### Graceful shutdown

```ts
await engine.shutdown(15000);
```

Drains queued work, lets in-flight batches finish, then closes. It refuses new
requests afterwards rather than accepting them into a dead scheduler.

### Sizing guidance

- `--concurrency` must not exceed the server's parallel decode capacity
  (`llama-server -np`). Exceeding it does not queue politely; it runs out of VRAM.
- Keep `--batch-size` at 1 for latency measurement. Batching trades per-item
  latency for throughput, and a latency number that includes batch waiting measures
  the harness, not the model. The report records both.
- `--batch-window-ms` bounds how long a request waits for company, so a trickle of
  traffic cannot add unbounded latency.