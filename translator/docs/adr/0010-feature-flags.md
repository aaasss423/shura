# ADR 0010 — Every non-core capability is behind a feature flag

Status: Accepted · 2026-10

## Context

The target architecture contains a lot that is not needed to translate a line of
text: research, prewarm, BYOK, async translation, model routing tiers, advanced
quality, the training pipeline. Shipping them all switched on would make the core
harder to reason about and harder to operate.

## Decision

Flags are configuration with one consistent shape, resolved once at boot, and
each guards exactly one capability:

| Flag | Default | Guards |
| --- | --- | --- |
| `ENABLE_LOCAL_ENGINE` | true | local model engine |
| `ENABLE_DEEPL` | false | DeepL provider |
| `ENABLE_MYMEMORY` | false | MyMemory provider |
| `ENABLE_BYOK` | false | user-supplied provider keys |
| `ENABLE_RESEARCH_AGENT` | false | background research worker |
| `ENABLE_PREWARM` | false | corpus prewarming |
| `ENABLE_ASYNC_TRANSLATION` | false | job-based translation API |
| `ENABLE_MODEL_ROUTING` | false | tier routing instead of one model |
| `ENABLE_ADVANCED_QUALITY` | false | evaluator-model scoring |
| `ENABLE_TRAINING_PIPELINE` | false | dataset export and training |
| `ENABLE_AUTH` | true | API key authentication |
| `ENABLE_ENTITLEMENTS` | true | plan and quota enforcement |

Flags **remove** capability; they never enable code that must be correct to
translate at all. A disabled flag returns a clear 501-style "disabled" error, not
a silent no-op.

## Consequences

- The core path has a minimal flag footprint: local engine, cache, quality.
- Deployment profiles ("dev", "self-hosted", "full") become a flag set.
- Features are testable in isolation: turn one on and assert it; turn it off and
  assert the core still works.
- A regression test asserts the core path works with every optional flag off.

## Reversal

Flags that are always on in practice should be removed rather than left as dead
configuration.
