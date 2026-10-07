# ADR 0004 — Research runs in the background and never blocks a response

Status: Accepted · 2026-10

## Context

When the pipeline meets an unknown phrase it could (a) block and research it
inline, or (b) translate now and research in the background. Option (a) puts a
multi-second web search in the critical path of every reader, and multiplies that
cost by every concurrent user hitting the same phrase.

## Decision

**Research never blocks a response.**

1. The pipeline translates immediately using the model, glossary and translation
   memory.
2. The unknown phrase is enqueued (see ADR 0005).
3. A background worker researches it and writes to the knowledge base.
4. **The next** request for that phrase benefits.

Critical-path exceptions are explicit and must be requested: `critical: true`
blocks up to a hard deadline, is rate-limited far more aggressively, and is
refused when the research queue is above its saturation threshold.

## Consequences

- Response latency is bounded by the model, not by research.
- Knowledge improves over time instead of on first sight — the user-visible
  behaviour is "the second chapter is better than the first", which is honest and
  worth stating in the UI.
- A freshness field marks unverified knowledge so the reader can be told which
  translations came from a term that is still being confirmed.
- Research workers are a separate pool with their own concurrency limit and a
  much lower rate limit toward web sources, so research cannot starve
  translation.

## Reversal

Not without reintroducing latency for readers. If research were inline, the
deduplication work in ADR 0005 would have to be synchronous and would become a
global bottleneck.
