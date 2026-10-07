# Architecture

How the translation platform is put together, and why.

## Layering

```
UI (ui/)
  ↓ HTTP
REST API (src/server/)
  ↓ in-process calls
Translator (src/translator/translator.ts)
  │   routing → fallback chain → circuit breaker   (src/engine/routing.ts)
  ↓
Translation Service (src/service/translationService.ts)
  ↓
Chapter Translator (src/chapter/chapterTranslator.ts)
  ↓
TranslationEngine (src/engine/engine.ts)   ← interface
  ↓
Concrete engine (src/engine/deepl/, src/engine/mymemory/, src/engine/echo/, …)
```

Requests enter at the REST layer or the SDK facade. Both converge on
`TranslationService`, which owns the orchestration: validate, resolve language,
consult cache, segment, call the engine, merge, check quality, store.

`ChapterTranslator` sits above the service rather than beside it, so chapter
translation gets the same cache, retry, timeout and cancellation behaviour as
single-text translation instead of reimplementing them.

## The engine boundary

`TranslationEngine` (`src/engine/engine.ts`) is the only place the platform
knows an engine is a swappable implementation:

```ts
interface TranslationEngine {
  readonly id: string;
  readonly name: string;
  readonly limits: EngineLimits;
  translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse>;
  getSourceLanguages(): LanguageInfo[];
  getTargetLanguages(): LanguageInfo[];
  supportsPair(source, target): boolean;
  supportedPairs(): EngineLanguagePairSupport[];
  healthCheck?(token): Promise<EngineHealth>;
}
```

Four design decisions matter:

1. **Engines do not cache.** `translate()` receives no cache. Caching belongs to
   the service, so every engine gets identical cache behaviour for free.
2. **Engines receive an `AbortSignal` and a `timeoutMs`.** Cancellation and
   timeouts are contract requirements, not best-effort.
3. **`limits.maxCharsPerRequest` drives segmentation.** The service clamps its
   configured segment size to the active engine's limit, so a stricter engine
   cannot be handed an oversized chunk. DeepL allows 4000 characters, MyMemory
   480; the same chapter code path works for both.
4. **Engines may report `configuration()`.** An engine that needs an API key
   returns `{ configured: false, reason }`, and routing skips it. This is what
   keeps "DeepL without a key" a routing decision instead of a crash.

Registration is by id (`EngineRegistry`). The active engine is one config value,
and a per-call `engine` option overrides it. A regression test greps the service,
chapter, translator and server sources to confirm no engine implementation is
referenced above the boundary.

`EngineRegistry` caches one instance per id. `createDefaultRegistry` accepts
either an options object or a **function** returning one, resolved on every
construction. That is what lets a credential loaded from the secret store after
startup take effect without a restart: `Translator.loadSecrets()` invalidates the
cached instance whenever the resolved key changes.

## Routing, fallback and the circuit breaker

`EngineRouter` (`src/engine/routing.ts`) sits in `Translator`, above the service,
so the service stays unaware of it:

- **Routing** maps a source language to an engine. A routed engine automatically
  gets the default engine appended as a last-resort fallback, so a route can
  never leave a request with no way out.
- **Availability** comes from `engine.configuration()`, so a missing credential is
  a routing decision rather than an exception. `GET /engines` reports it.
- **Fallback** re-enters the service with the next engine id. Only engine-level
  failures qualify (`ENGINE_UNAVAILABLE`, `RATE_LIMITED`, `QUOTA_EXCEEDED`,
  `TIMEOUT`, `UNSUPPORTED_PAIR`, …). Validation errors and cancellation never do:
  they would fail identically on every engine.
- **Circuit breaker.** After `ENGINE_FAILURE_THRESHOLD` consecutive engine-level
  failures an engine is skipped for `ENGINE_COOLDOWN_MS`. Without it, an engine
  behind an exhausted quota or an outage costs a timeout on every request even
  though the fallback answers instantly.

Cache safety follows from two rules: the resolved engine id is part of the cache
key, and a result is stored under the engine that produced it. A failed attempt
stores nothing, so switching engines never yields a translation from the wrong
engine.

`translationResult.engine` reports the **requested** id rather than whatever the
engine implementation calls itself, so provenance matches the cache key.

## Credentials

`src/security/secretStore.ts` resolves a key from the environment first and the
encrypted vault second. The vault lives outside the project directory
(`$XDG_CONFIG_HOME/translation-platform/`), directory `0700`, files `0600`, values
encrypted with AES-256-GCM under a scrypt-wrapped data key. The key is held in
memory only for the life of the process, is never written to a project file, and
never appears in a log, an error, or any API response — only a fingerprint.

## Translation flow

```
translate(request)
  ├─ token.throwIfCancelled()            pre-flight: never start cancelled work
  ├─ validateTranslateInput()            non-empty text, concrete target, differing pair
  ├─ resolveEngine()
  ├─ resolveSourceLanguage()             explicit code, or detection when 'auto'
  ├─ assert engine.supportsPair()
  ├─ buildCacheKey()                     engine id + pair + hints + normalized text
  ├─ cache.get()                         hit → return
  ├─ segmentText()                       clamped to engine limits
  ├─ per segment:
  │    ├─ segment cache get
  │    ├─ withRetry( budget.timeout(
  │    │      raceCancellation(
  │    │        withTimeout( engine.translate() ), token ) ) )
  │    └─ segment cache set
  ├─ mergeSegments()                     blank/missing → original text
  ├─ checkQuality()                      mechanical damage
  └─ cache.set() + flush()               restart recovery
```

Multi-segment requests run up to 4 segments concurrently; a shared
`DeadlineBudget` covers the whole request so per-attempt timeouts are clamped to
what is left of the total budget.

## Error model

Every failure crossing a boundary is a `TranslationError` carrying a stable
`code`, an HTTP `status`, and a `retryable` flag. Layers never inspect message
text. `toTranslationError()` normalizes anything thrown, including native
`AbortError`s.

This is what lets the REST layer and a future host application map failures
without knowing which engine produced them.

## Concurrency safety in the file cache

Three invariants:

1. **One queue.** All mutations run through `enqueue()`, chained on a promise
   that never rejects. Writes cannot interleave.
2. **Generation counter.** `clear()` increments `generation` synchronously,
   before any `await`. `set()`, `delete()` and `flush()` capture the generation
   at call time and abort if it changed by the time their queued task runs. This
   is why an old flush cannot rewrite data that was deleted after it was queued.
3. **Shared load promise.** `ensureLoaded()` creates one load promise that every
   caller awaits. Without it, a `set()` could write while a concurrent load was
   still reading, and the load's `entries = map` assignment would silently drop
   those writes.

Rename handles `ENOENT` by recreating the directory and retrying once; a
persistent failure leaves the cache dirty for a later retry rather than throwing
into the translation path.

## Language detection

Script counting produces evidence; scoring produces a ranking. The decisive
rule:

```
kana > 0  →  ja  (weight 3 + 2×kana)
han  > 0  →  zh  (weight 3 + han, reduced when kana is present)
```

Because kana is weighted far above Han, a single katakana character makes text
Japanese even when Han characters outnumber it. Pure Han gets a floor of 0.9
confidence, so a Chinese chapter is never routed to a Japanese engine by a thin
margin. Invisible marks are stripped before counting.

## Arabic handling

Two failure modes drove this layer.

**Counting.** Bidi controls are format characters, not content. Counting them
inflated segment sizes and pushed requests past engine limits.
`countCharacters()` strips them and counts by code point.

**Normalization.** Aggressive rewriting damages mixed-language text.
`normalizeArabic()` returns non-Arabic input unchanged apart from whitespace
tidying. For Arabic input it converts punctuation only inside Arabic runs —
`?` is converted when the nearest non-space neighbour on either side is Arabic,
so `كيف حالك?` is converted and `Really? Yes?` is not.

## Quality checks

`checkQuality()` detects mechanical damage, not bad writing:

- `empty_translation`
- `untranslated` (output identical to source)
- `repetition` (a block repeated three or more times)
- `source_target_mixed` (Arabic target, no Arabic letters)
- `script_mismatch` (source script still present in output)
- `truncated` (target far shorter than source, without repetition)
- `numeric_loss` (a number from the source is missing)

Engine-reported confidence is folded into the score. Reports are surfaced to the
caller and rendered in the UI; a warning never silently rewrites output.

## Cancellation and deadlines

Three separate concerns, deliberately not conflated:

- **Token.** Pre-flight check, abort signal, listener registration, linking.
- **Per-request timeout.** Bounds one engine attempt.
- **Service deadline.** Bounds a whole chapter; reports `DEADLINE_EXCEEDED`
  separately from `TIMEOUT`.

`raceCancellation()` exists because an engine may not settle its promise after
an abort. Without it, a cancelled request would wait for the request timeout and
report the wrong error. Timers are not `unref`'d: a pending backoff or timeout
is real pending work, and unref'ing it lets a CLI process exit mid-operation.

`DeadlineBudget.timeout()` takes a thunk, not a promise, so an already-expired
budget throws before any work starts and no orphan promise is left unhandled.

## Adding a layer

If a future need appears (a second cache backend, a translation-memory engine, a
streaming endpoint), add it as its own module behind an interface, register it,
and wire it through `Translator`. The rule to preserve: no module above
`src/engine/engine.ts` may reference a concrete engine.