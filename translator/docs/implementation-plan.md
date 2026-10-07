# Implementation plan

Ordered so every phase is independently useful and the core keeps working if a
later phase stalls. Each phase lists its gate: nothing moves on until it passes.

## Phase 0 — Audit and research ✅ done

- Audited the existing translator: 364 tests, 30 source modules, zero runtime
  dependencies, `TranslationEngine` + `Translator` contracts to preserve.
- Researched self-hostable models. **Result: licence is the filter, not
  quality.** NLLB-200 and Unbabel Tower are CC-BY-NC-4.0 and cannot ship.
- Wrote 10 ADRs, model selection, target architecture.

**Gate:** existing suite still green.

## Phase 1 — Storage ✅ done

`src/platform/db/` — dialect-aware migrations, 18 tables, `node:sqlite` so the
test suite needs no services.

**Gate:** migrations idempotent, transactions roll back, full schema exists.

## Phase 2 — Knowledge, memory, glossary, context ✅ done

`src/platform/knowledge/`, `src/platform/memory/`, `src/platform/context.ts`.

Three bugs found by the tests and fixed:
- a character-scoped write overwrote the global row (loose scope predicate)
- CJK terms are substrings, so single-run extraction never found `先輩`
- glossary "enforcement" could not match a source term in translated output

**Gate:** scope resolution, batch lookup, confidence floors, context key
exclusion all tested.

## Phase 3 — Research queue and agent ✅ done

`src/platform/research/` — dedup by normalized key, atomic claim, background
agent with credibility scoring, consensus and a single-source ceiling.

**Gate:** 500 users → 1 job; single source refused; disagreement refused.

## Phase 4 — Local engine ✅ done

`src/platform/engine/local/` — `LocalHttpEngine` (llama.cpp / OpenAI-style),
`DeterministicEngine` for tests, model identity exposed for the cache key.

**Gate:** model id/version reported, unconfigured engines skipped by routing.

## Phase 5 — Auth, plans, entitlements, keys ✅ done

`src/platform/auth/` — hashed keys with prefix, rotation in one transaction,
plans as data, quota checks that re-read the user.

**Gate:** plaintext never stored; unlimited plans keep infra limits; BYOK exempt.

## Phase 6 — Jobs, metrics, router, prewarm, load harness ✅ done

`src/platform/jobs/`, `metrics.ts`, `router/`, `prewarm.ts`, `loadtest/`.

**Gate:** priority, dedup, retry, cancel-flag, backpressure, percentiles.

## Phase 7 — Pipeline and REST ✅ done

`src/platform/pipeline/`, `src/server/platformRoutes.ts` — additive endpoints
only; existing routes untouched.

**Gate:** 460 tests green; pre-existing endpoints unchanged.

---

## What remains, in priority order

### Next: the manga evaluation set (ADR 0006)

**This is the highest-value remaining work and it gates every model claim.**

1. Collect a per-language corpus: dialogue, narration, sound effects, honorifics,
   slang, names, numbers, punctuation, mixed Latin, multi-line.
2. Reference translations, reviewed, with per-item notes.
3. Include deliberate traps: honorifics that must survive, onomatopoeia, gender
   markers, series terminology.
4. A runner that reports per-pair and per-category results and prints
   side-by-side output. **No single opaque score.**
5. Run TranslateGemma 4B / 12B and MADLAD-400-3B against it once GPU hardware is
   available, then enable per-language routing with evidence.

Until this exists, `docs/model-selection.md` is a hypothesis, and the code says so.

### Then: serving stack

- llama.cpp / TGI / vLLM deployment for `local-tg4` and `local-tg12`.
- Batching at the worker (batch 32 on the 4B beats batch 1 on the 27B on
  aggregate throughput).
- GPU worker pool with health reporting and canary routing.

### Then: research sources

- Implement `SourceCollector` adapters (dictionary APIs, corpus search).
- Credibility table per source kind is config, not a constant.
- Rate limits toward web sources, well below the translation path's.

### Then: production storage

- Postgres adapter behind the existing `Database` interface.
- `SKIP LOCKED` claiming, `pg_trgm` fuzzy matching for manga phrases.
- Connection pooling, migrations in CI.

### Then: async and workers

- `POST /translate/async`, `GET /jobs/:id`, `POST /jobs/:id/cancel` (routes exist;
  the worker loop and cancellation checkpoints need wiring).
- Worker pools with health, utilisation and restart policies.

### Then: entitlements at the edge

- Enforce `max_parallel` with `ConcurrencyTracker` in the request path.
- Per-key rate limiting keyed on `prefix`, not plaintext.
- Admin endpoints already exist; the dashboard UI does not.

### Later

- Fine-tuning pipeline (ADR 0007): dataset export → cleaning → dedup → validation
  → training → eval → canary.
- BYOK namespaces in the secret vault (ADR 0008).
- Manga domain types (series/chapter/page/panel/character) in the request schema
  so OCR and image translation can be added without reshaping the API.
- Payment provider, behind the existing plan boundary — deliberately not built
  blind.

### Not planned

Android, Shura integration, streaming translation.