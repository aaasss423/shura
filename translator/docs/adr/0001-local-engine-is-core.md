# ADR 0001 — The local engine is the core; providers are optional

Status: Accepted · 2026-10

## Context

The platform started with MyMemory as the only engine, then added DeepL. Both are
external: they impose quotas we do not control, they cost money per character at
scale, and they are third-party failure domains. The stated goal is a platform we
own, where the limits we impose on users are our limits.

## Decision

`LocalEngine` is the default and only mandatory engine. `TranslationEngine` stays
the single abstraction, and DeepL / MyMemory become optional `ProviderEngine`
implementations registered behind the same interface, used for BYOK, fallback and
benchmarking.

The platform must be able to serve every priority pair (en/ja/zh/ko → ar) with
**no** external provider configured and **no** outbound network call on the
translation path.

## Consequences

- `TRANSLATION_ENGINE` defaults to `local` when a local model is available, and
  never defaults to a provider.
- No layer above `src/engine/engine.ts` may *require* a provider. A regression test
  asserts the core path is reachable with providers disabled.
- Feature flags (`ENABLE_DEEPL`, `ENABLE_MYMEMORY`) exist so a deployment can
  remove providers entirely.
- Quality, terminology enforcement, memory and knowledge are all ours, so they
  apply equally to local and provider results. That is the actual reason to
  prefer local: the pipeline, not the model, is the asset.

## Reversal

Not reversible by choice. It would require rewriting the routing and entitlement
model. It is reversible in *configuration*: providers can be made the default
again per deployment if a local model proves too expensive to operate.
