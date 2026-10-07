# Target platform architecture

Status: design accepted; implemented incrementally on top of the existing
translator (see [Current → Target](#current--target)).

## The shape

```
                    ┌──────────────────────────────────────────┐
User (reader / app) │  API tier                                 │
                    │  auth → entitlement → rate limit → payload │
                    └───────────────────┬──────────────────────┘
                                        │
                              ┌─────────▼─────────┐
                              │  Queue / Jobs     │  priority, backpressure,
                              │  dedup, cancel    │  dedup by content key
                              └─────────┬─────────┘
                                        │
                              ┌─────────▼─────────┐
                              │  Workers          │  batch, concurrency,
                              │                   │  timeout, retry, breaker
                              └─────────┬─────────┘
                                        │
  ┌─────────────────────────────────────▼─────────────────────────────────────┐
  │ Translation pipeline (ours — this is the asset)                          │
  │                                                                          │
  │  Context Analyzer ──► Terminology Lookup ──► Glossary ──► Character Names│
  │        │                                                                 │
  │        ├──► L3 Translation Memory                                         │
  │        ├──► L4 Knowledge Base                                            │
  │        └──► Research Queue (background, never blocking)                  │
  │                     │                                                    │
  │                     ▼                                                    │
  │              LocalEngine / ProviderEngine  ◄── Model Router (tiers)      │
  │                     │                                                    │
  │                     ▼                                                    │
  │            Post Processing ──► Quality ──► Cache (L1/L2) ──► Response    │
  └──────────────────────────────────────────────────────────────────────────┘
```

The model is one box in a ten-box pipeline. Everything else — terminology,
memory, knowledge, quality, enforcement — is ours and applies equally to a local
model, DeepL, or anything added later.

## Current → Target

| Concern | Today | Target | How |
| --- | --- | --- | --- |
| Engine boundary | `TranslationEngine` + DeepL/MyMemory/Echo | same interface, **local first** | ADR 0001 |
| Default engine | `deepl` with a key, else `mymemory` | `local`, providers optional | flag + config |
| Pipeline | detect → segment → engine → merge → quality | adds context, terminology, glossary, names, memory, knowledge | new layers below service |
| Knowledge | none | `knowledge` table, category taxonomy, provenance, confidence | ADR 0003 |
| Memory | none | `translation_memory` consulted before the model | — |
| Glossary | none | force / prefer / forbid / alias / context-scoped | — |
| Characters | none | names + aliases + series link | — |
| Research | none | dedup queue + background agent | ADR 0004, 0005 |
| Persistence | JSON cache file | Postgres (+SQLite dev) | ADR 0003 |
| Cache levels | L1 memory, L2 file | + L3 memory table, L4 knowledge | ADR 0009 |
| Auth | none | API keys hashed, entitlements, plans | — |
| Async | none | job queue, `/jobs/:id`, cancel | — |
| Observability | none | metrics registry, percentiles, breaker state | — |
| Flags | a few booleans | uniform flag set | ADR 0010 |
| Tests | 364 | 364 + per-area suites | this phase |

## What is reused unchanged

The existing translator is not rewritten. These modules are reused as-is and are
now the bottom of the new pipeline:

`language/detect` · `language/registry` · `arabic/arabic` · `segmentation/segment`
· `cache/*` · `core/retry` · `core/timeout` · `core/cancellation` ·
`core/validation` · `core/errors` · `service/quality` · `engine/engine` (the
abstraction) · `engine/http` · `engine/routing` · `security/secretStore`

`TranslationEngine`, `Translator`, `TranslationService` and the REST server keep
their contracts. New capability is added *around* them, not inside.

## Conflict points, and how they are handled

| Conflict | Handling |
| --- | --- |
| Existing `EngineRouter` is provider-shaped (id-based fallbacks) | Reused as-is: local engines are just more ids. Tier routing becomes rules over the same structure. |
| Cache key lacks model version | **Additive**: key gains optional `modelId`/`modelVersion`/`glossaryVersion`. Old keys keep working. |
| `Translator.translate` is the public SDK | Unchanged. The pipeline is composed *inside* it; new layers are transparent to callers. |
| REST `/translate` shape | Unchanged response fields. New fields are additive; new endpoints are new paths. |
| `contextBefore` was excluded from the cache key | Deliberately kept excluded by default, with an opt-in context-aware key — see ADR 0009. |
| Existing `SecretsStore` is single-process file vault | Reused for platform keys; BYOK needs a namespaced storage backend (ADR 0008). |

## Layer contracts

**Context Analyzer** — builds the `Context` object and decides what to retrieve.
Never translates.

**Terminology Lookup** — resolves terms from glossary, character names, knowledge
and memory into an instruction set for the model, and a post-processing checklist.

**LocalEngine** — implements `TranslationEngine`. Talks to a local inference
server (llama.cpp / TGI / vLLM) over HTTP. Reports `modelId` + `modelVersion`.
Declares `configuration()` so routing skips it when no model is loaded.

**Model Router** — picks a tier (high / standard / fallback / BYOK) from source
language, request size, quality demand and current load. Config, not code.

**Research Agent** — the background pipeline in ADR 0004. Independent module;
never imported by the translation hot path.

**Quality** — extends the existing mechanical checks with terminology
consistency and context consistency. Extensible to an evaluator model later
(`ENABLE_ADVANCED_QUALITY`).