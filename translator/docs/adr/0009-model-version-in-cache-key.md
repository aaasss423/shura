# ADR 0009 — Model id and version participate in the cache key

Status: Accepted · 2026-10

## Context

The existing cache key is
`(engine id, source, target, stable hints, normalized text)`. With a local model
that is no longer sufficient: the same text under a different model, or the same
model at a different version, is a different translation.

Serving a pre-migration translation after a model change is worse than
re-translating — it is invisible.

## Decision

The cache key gains `modelId` and `modelVersion` whenever the resolved engine
supplies them, plus a `glossaryVersion` when a glossary was applied. Context
(`previousText`, `nextText`, character, series) is **not** in the key by default —
it is in a separate `contextKey`, and context-sensitive requests use a
context-aware key only when the caller opts in, because including it would
reproduce the old `contextBefore` fragmentation bug.

Failure responses are never stored as translations. Only successful, quality-
checked results enter the cache.

## Consequences

- Swapping models correctly invalidates the affected entries instead of serving
  stale ones.
- Glossary edits must bump `glossaryVersion` for the same reason.
- Cache size grows per model version; the LRU bound handles it, and an explicit
  purge-by-model-version operation is available for operators.

## Reversal

Storing model version *outside* the key and serving stale entries would be
faster and wrong. Keep it in the key.
