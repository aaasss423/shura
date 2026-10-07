# Roadmap and status

Honest accounting of what exists, what is tested, what is verified against a live
engine, and what is not done.

## Status matrix

| Area | Implemented | Tested | Verified live | Notes |
| --- | --- | --- | --- | --- |
| Core types, errors, logging | yes | yes | n/a | — |
| Configuration (incl. DeepL, routing) | yes | yes | n/a | credentials redacted |
| Language detection | yes | yes | yes | en/ar/ja/zh/ko samples |
| Arabic processing | yes | yes | yes | real Arabic output exercised |
| Segmentation / merge | yes | yes | yes | live over-limit text |
| Memory cache | yes | yes | n/a | — |
| File cache | yes | yes | n/a | race cases in regression suite |
| TranslationEngine interface | yes | yes | n/a | `configuration()` probe |
| **MyMemory engine** | yes | yes | **yes** | real requests, real Arabic |
| **DeepL engine** | yes | yes | **no** | no key available; 38 live tests ready |
| Engine routing | yes | yes | n/a | per-language, auto-fallback |
| Engine fallback chain | yes | yes | n/a | ineligible errors excluded |
| Circuit breaker | yes | yes | n/a | prevents per-request retries |
| Echo test engine | yes | yes | n/a | offline only, gated by config |
| Encrypted credential store | yes | yes | n/a | 0600, outside the project |
| Retry | yes | yes | partly | transient failures live; quota wall proven live |
| Timeout | yes | yes | yes | real request timed out under a 1 ms budget |
| Cancellation | yes | yes | yes | pre-flight and in-flight, live and offline |
| Translation service | yes | yes | yes | — |
| Translator SDK | yes | yes | yes | — |
| Chapter translation | yes | yes | yes | en and ja chapters via MyMemory |
| REST API | yes | yes | yes | real booted server, real HTTP |
| UI (incl. secure key field) | yes | yes | n/a | served and exercised over HTTP |
| Benchmark script | yes | n/a | runs | reports skips honestly |
| Documentation | yes | n/a | n/a | README + docs/ |
| Android client | no | no | no | out of scope; no SDK claimed |
| Shura integration | no | no | no | deliberately deferred |

## What is NOT verified

**No live DeepL request has been made.** No `DEEPL_API_KEY` was available in this
environment. Everything about DeepL below is unit- and integration-tested
(construction, limits, language support, error mapping, routing, credentials,
cancellation, cache keys), and 38 live tests exist, but none of them has run
against the real API. Do not read "DeepL is integrated" as "DeepL quality has
been measured".

To close the gap:

```sh
export DEEPL_API_KEY=…
LIVE_DEEPL_TESTS=1 npm run test:live:deepl
npm run benchmark          # side-by-side with MyMemory
```

The MyMemory free tier is a shared per-IP daily budget and is currently
exhausted, so live MyMemory runs currently report SKIPPED with the reason.

## Engine choice

**Default is conditional, and that is the honest answer:**

- `DEEPL_API_KEY` present → default `deepl`, fallback `mymemory`.
- No key → default `mymemory`, no fallback, DeepL reported as unavailable.

Why not "DeepL is always the default"? It cannot be: without a key DeepL is
unusable, and a platform that hard-fails without a credential is worse than one
that keeps serving. Routing to MyMemory when DeepL is unavailable is the same
mechanism that handles an outage, so it needed to exist anyway.

Why not per-language routing by default? It is supported
(`TRANSLATION_ENGINE_ROUTES=ja=deepl,zh=deepl,ko=deepl`) and the plumbing is
tested, but enabling it **before** the benchmark confirms a benefit would be
claiming an unmeasured result. Enable it after `npm run benchmark` shows DeepL
winning on those pairs.

MyMemory remains a first-class engine, not a stub: it is the only keyless option,
it is the automatic fallback, and it still serves en → ar well.

## Priorities

### 1. Measure DeepL quality — the open question

Once a key exists:

- `npm run benchmark` for the side-by-side comparison.
- `LIVE_DEEPL_TESTS=1 npm run test:live:deepl` for the full matrix
  (en/ja/zh/ko → ar, dialogue, narration, names, numbers, punctuation, chapters).
- Then decide: DeepL everywhere, or DeepL for CJK with MyMemory for the rest.

If DeepL confirms the expectation, enable the CJK routes by default and document
the measured difference. If it does not, say so — the benchmark prints raw
translations precisely so a human can judge rather than trusting an invented
score.

### 2. Free-tier quota

MyMemory's anonymous budget is shared per IP (~5000 chars/day) and resets after
hours. Already handled: non-retryable `QUOTA_EXCEEDED`, throttled requests,
circuit breaker, and SKIPPED (never silently passing) live tests. Worth adding: a
per-engine token-bucket limiter and metrics for cache hit rate, retry count and
breaker state.

### 3. Glossaries and translation memory

Not implemented. DeepL supports both natively, and both are directly useful for
manga: pinning character names and honouring recurring series terminology. The
request surface already has a stable `hints` channel that participates in the
cache key, so this is additive.

### 4. Streaming translation

Not implemented. Chapter translation is segment-parallel and reports progress,
which covers the practical need; true streaming would add cancellation and
partial-result complexity for little gain at manga panel sizes.

### 5. Hardware-backed credentials

`SecretStore` is an interface precisely so this is swappable. On a host with a
real keystore (Android Keystore, macOS Keychain, Windows Credential Manager,
libsecret), implement an adapter and register it. The current AES-256-GCM file
vault is defence in depth against backups, logs, screenshots and repository
copies — not against someone who already has shell on the machine.

### 6. Reader integration

Not implemented, by design. When it happens:

- Render translations inside the reader's own text flow, **not** as a screen or
  floating overlay.
- Do not intercept scroll, swipe, tap, zoom, navigation or chapter controls.
- Consume only the SDK: `translate()`, `translateChapter()`,
  `detectLanguage()`, `getSupportedLanguages()`, and the error `code`/`status`
  contract.

## Explicitly not claimed

- DeepL translation quality — never measured here.
- Production readiness or a stability guarantee.
- Android build — no SDK in this environment.
- Shura integration — not started.
- Any automated "translation quality score". The benchmark reports mechanical
  signals only; fluency judgement is left to a human reading the output.