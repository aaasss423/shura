# ADR 0007 — Learning is curated; the system never trains on itself

Status: Accepted · 2026-10

## Context

It is tempting to feed every translation — and its own output — back into
training. That produces a model that confidently reproduces its own mistakes,
compounding errors across generations.

## Decision

Learning is a **pipeline**, and data must be *approved* before it becomes
training data:

```
translation → candidate → quality/confidence → approved
  → dataset → cleaning → dedup → validation → training (LoRA/fine-tune)
  → eval (ADR 0006) → model version → canary → production
```

- Every translation is recorded as a **candidate** with its source, output,
  glossary version, model version and quality report.
- Nothing is auto-promoted. Promotion requires a confidence floor *and* either
  human approval or a passing evaluation on the frozen eval set.
- Training data is deduplicated against the eval set.
- Model versions are immutable and referenced by every translation that used them,
  so any regression is traceable to a version.

## Consequences

- Slower improvement, no error compounding.
- `modelVersion` in the cache key (ADR 0009) means a new model version
  re-translates rather than serving old results.
- Canary rollout is required before a new version takes traffic, and the tier
  router can route a percentage to the candidate.
- The training pipeline is out of scope for this phase; the *interfaces*
  (candidate records, approval state, dataset export) are designed in now.

## Reversal

A "trusted automated promotion" mode could be added for high-confidence exact
translation-memory matches, but never for model output generally.
