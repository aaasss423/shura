# Translation Platform

A standalone, layered translation service. It translates text and whole chapters,
detects languages, handles Arabic specifically, caches results persistently, and
exposes everything through a REST API with a usable web UI.

Two real engines are available: **DeepL** (higher quality, needs `DEEPL_API_KEY`)
and **MyMemory** (free tier, no key, weaker on CJK → Arabic). Selection is a
configuration value, with per-language routing and automatic fallback.

It has no dependency on Shura, Mihon, Keiyoushi, or any manga application. It is
a plain Node/TypeScript project with **zero runtime dependencies**. Shura
integration is a later phase; the architecture is built for it, but nothing here
knows Shura exists.

**Priority order:** Arabic output quality, stability, extensibility.

---

## Quick start

```sh
npm install
cp .env.example .env        # optional; every value has a default
npm run build
npm start                   # http://127.0.0.1:8787
```

Open `http://127.0.0.1:8787` for the UI.

```sh
npm run typecheck     # tsc --noEmit
npm test              # unit + integration + REST (no network)
npm run test:live     # real translation against MyMemory
npm run smoke         # end-to-end diagnostics with printed evidence
npm run smoke -- --offline
npm run benchmark     # DeepL vs MyMemory on the same fixtures
```

Requirements: Node.js >= 20.11 (developed and verified on Node 24).

---

## Architecture

```
UI (ui/)
  ↓  HTTP
REST API (src/server/)
  ↓  in-process calls
Translator (src/translator/)          public SDK / application API
  ↓
Translation Service (src/service/)    validation, detection, cache, quality
  ↓                                  orchestration, retry, timeout, cancel
Chapter Translator (src/chapter/)     progress, partial failure, retry
  ↓
TranslationEngine  ← interface (src/engine/engine.ts)
  ↓
Concrete engines    MyMemoryEngine, EchoEngine, …
```

Supporting layers are independent modules, not inline code:

| Layer | Location | Responsibility |
| --- | --- | --- |
| Language detection | `src/language/` | script evidence, kana-first ja/zh decision |
| Language registry | `src/language/registry.ts` | canonical codes, aliases, direction |
| Arabic processing | `src/arabic/` | normalization, visible-character counting, RTL, digits |
| Segmentation | `src/segmentation/` | engine-sized chunks, ordered merge with fallback |
| Cache | `src/cache/` | deterministic keys, memory + persistent tiers, TTL |
| Retry | `src/core/retry.ts` | policy, exponential backoff, retry-worthiness |
| Timeout | `src/core/timeout.ts` | per-request timeout, service deadline |
| Cancellation | `src/core/cancellation.ts` | token, abort signal, linking |
| Engine routing | `src/engine/routing.ts` | per-language routing, fallback, circuit breaker |
| Credentials | `src/security/secretStore.ts` | encrypted runtime key storage, status only |
| Configuration | `src/config/` | env parsing and validation |
| Validation | `src/core/validation.ts` | request contracts |
| Quality | `src/service/quality.ts` | mechanical damage detection |
| Logging | `src/core/logger.ts` | structured, dependency-free |

The rule that shapes everything: **no layer above `src/engine/engine.ts` knows
any engine implementation.** A regression test enforces this by scanning the
service, chapter, translator and server sources for engine references.

---

## Public API

```ts
import { createTranslator } from '@shura-tools/translation-platform';

const translator = createTranslator();

// Single text
const result = await translator.translate({
  text: 'Are you serious right now?!',
  sourceLanguage: 'auto',   // or 'en'
  targetLanguage: 'ar',
});
// → { text, sourceLanguage, detectedLanguage, targetLanguage, engine,
//     fromCache, segments, elapsedMs, quality, direction }

// Full chapter, with progress and per-segment results
const chapter = await translator.translateChapter({
  segments: [{ id: 'p1', text: 'Are you serious?' }, { id: 'p2', text: "I'll be fine." }],
  sourceLanguage: 'en',
  targetLanguage: 'ar',
  concurrency: 3,
  onProgress: (p) => console.log(p.completedSegments, '/', p.totalSegments),
});
// → { text, segments[], progress, degraded, elapsedMs }
// Retry only the segments that failed:
const fixed = await translator.retryChapter(chapter, request);

// Language detection
translator.detectLanguage({ text: '本気なのか？' });
// → { language: 'ja', confidence, evidence, alternatives }

// Capabilities
translator.getSupportedLanguages();
translator.getEngineCapabilities();
await translator.checkEngineHealth();
```

### Options accepted by `translate()`

| Option | Type | Effect |
| --- | --- | --- |
| `text` | `string` | required, non-empty |
| `sourceLanguage` | `string` | `'auto'` or a language code |
| `targetLanguage` | `string` | required, concrete language |
| `engine` | `string` | override the active engine for this call |
| `noCache` | `boolean` | skip cache read *and* write |
| `refresh` | `boolean` | skip cache read, still write |
| `timeoutMs` | `number` | per-request timeout override |
| `retries` | `number` | additional attempts after the first |
| `deadlineMs` | `number` | service-level budget for the whole call |
| `acceptStaleCache` | `boolean` | allow expired entries |
| `contextBefore` | `string` | engine context hint; **never** part of the cache key |
| `token` | `CancellationToken` | cancellation handle |
| `hints` | `Record<string,string>` | stable engine hints; part of the cache key |

---

## Engines, routing and fallback

| Engine | Key required | Quality for CJK → Arabic | Limits |
| --- | --- | --- | --- |
| `deepl` | `DEEPL_API_KEY` | materially better | 4000 chars/request, 128 KiB per request |
| `mymemory` | no | mediocre on the free tier | 480 chars/request |
| `echo` | no | offline test double only | — |

Selection is configuration:

```sh
TRANSLATION_ENGINE=deepl                       # active engine
TRANSLATION_ENGINE_ROUTES=ja=deepl,zh=deepl,ko=deepl
TRANSLATION_ENGINE_FALLBACKS=mymemory          # ordered fallbacks
```

With no explicit `TRANSLATION_ENGINE`, the default is `deepl` when a key is
present and `mymemory` otherwise.

**Routing** maps a source language to an engine. A routed engine always gets the
default engine appended as a last-resort fallback, so routing can never leave a
request with no way out. An explicit `engine` in a request skips routing.

**Fallback** re-runs the request on the next engine when the current one fails
at the engine level (unavailable, rate limited, quota exhausted, timeout,
unsupported pair). It never triggers for caller errors or cancellation.

**Circuit breaker** — after `ENGINE_FAILURE_THRESHOLD` consecutive engine-level
failures (default 2) an engine is skipped for `ENGINE_COOLDOWN_MS` (default
60s). Without it, a dead engine or an exhausted quota would cost a timeout on
every single request even though the fallback answers instantly.

Cache safety: the resolved engine id is part of the cache key, and a result is
always stored under the engine that produced it. A failed attempt stores
nothing, so switching engines never returns a translation from the wrong engine
and never forces needless re-translation of an unchanged routing decision.

Available at runtime:

```sh
GET /engines          # per-engine availability + limits + routing table
GET /engine/route?source=ja
GET /engine/health?engine=deepl
```

## TranslationEngine

Adding an engine means implementing one interface and registering it. Nothing
else in the platform changes — not the service, chapter translation, REST API,
or UI.

```ts
import { TranslationEngine, createTranslator } from '.../src/index';

class MyEngine implements TranslationEngine {
  readonly id = 'my-engine';
  readonly name = 'My Engine';
  readonly limits = { maxCharsPerRequest: 400 };

  async translate(request: EngineTranslationRequest): Promise<EngineTranslationResponse> {
    // Honour request.signal and request.timeoutMs. Do not cache: caching is the
    // service's job.
    return { text: '…', engine: this.id };
  }

  getSourceLanguages() { /* LanguageInfo[] */ }
  getTargetLanguages() { /* LanguageInfo[] */ }
  supportsPair(source, target) { return true; }
  supportedPairs() { /* [{ source, target }] */ }
  async healthCheck() { /* optional */ return { engine: this.id, healthy: true }; }
  // Optional: report a missing credential so routing skips this engine.
  configuration() { return { configured: true }; }
}

const registry = createDefaultRegistry();
registry.register('my-engine', () => new MyEngine());

const translator = createTranslator({ registry });
// or per-call: translator.translate({ …, engine: 'my-engine' })
```

Switching the active engine is configuration only:

```sh
TRANSLATION_ENGINE=my-engine
```

Cache keys are derived from the **requested engine id**, so two engines can never
share entries even if they report the same `id` field, and `translationResult.engine`
reports the requested id so provenance matches the key.

---

## Language detection

Detection is script-evidence based with one decisive rule: **kana means
Japanese.** Pure Han text is Chinese, even though the two scripts share Unicode
code points.

| Input | Detected |
| --- | --- |
| `本気なのか？` | `ja` (kana present) |
| `你以为我是谁？` | `zh` (Han only, confidence >= 0.9) |
| `世界は俺のものだ` | `ja` (one katakana is decisive) |
| `안녕하세요? 반갑습니다.` | `ko` |
| `مرحبا، كيف حالك؟` | `ar` |
| `Who are you?` | `en` |
| `1234 !!!` | `und`, confidence 0 |

Bidi marks are stripped before counting, so decorated text scores the same as
plain text.

---

## Arabic processing

- `countCharacters()` counts **visible** characters by code point. Bidi controls
  (U+202A–U+202E, U+200E/F, U+061C), zero-width characters and BOMs are excluded,
  because including them inflated segment sizes against engine limits.
- `normalizeArabic()` is deliberately conservative. With no Arabic letter
  present it only tidies whitespace and returns the text otherwise unchanged, so
  English, Japanese and Chinese are never mangled. With Arabic present it
  converts ASCII comma/semicolon and sentence-final `?` to their Arabic forms
  (skipping whitespace to find the neighbouring character, so English questions
  inside an Arabic document survive), strips bidi marks, removes tatweel,
  collapses redundant whitespace and blank lines, and normalizes CRLF.
- Helpers: `containsArabic`, `arabicRatio`, `extractNumbers`, `toArabicDigits`,
  `toWesternDigits`, `stripBidiMarks`, `applyRtlIsolation`, `normalizeParagraphs`.

---

## Segmentation and merge

`segmentText(text, { maxChars })` splits at paragraph boundaries first, then
sentence boundaries, then whitespace, and only slices mid-run when a single
token (a long CJK span) exceeds the limit. Short input returns one segment with
`whole: true`; empty input returns none. `maxSegments` collapses the tail into
the final segment rather than dropping it.

`mergeSegments(segments, translations)` rejoins in order and restores the stored
separators. A missing or blank translation **falls back to the original text**,
so a failed segment degrades to "untranslated" rather than "missing content".

---

## Chapter translation

```
segments -> per-segment segmentation -> bounded concurrency
         -> per-segment retry/timeout/cancellation -> cache
         -> merge with original-text fallback -> progress events
```

- Progress via `onProgress`, including `completedSegments`, `failedSegments`,
  `cachedSegments`, `currentSegment` and `state`.
- One failing segment does **not** lose the chapter. Under the default
  `partial` policy the original text is kept, the result is marked
  `degraded: true`, and processing continues. `failurePolicy: 'abort'` makes a
  failure abort the chapter instead.
- `retryChapter(previous, request)` re-requests only the failed segments and
  merges them back, leaving successful segments untouched.
- Panel segments longer than the segment limit are split and rejoined.

---

## Cache

Two tiers: `MemoryCache` in front of `FileCache`, combined by `TieredCache`.
Cache failures degrade to a miss and never fail a request.

- **Deterministic keys.** SHA-256 over engine id, source, target, sorted stable
  hints, and whitespace-normalized text. Whitespace normalization collapses
  spaces, CR, and repeated newlines.
- **`contextBefore` is not part of the key.** It is not even a parameter of
  `buildCacheKey`, so the same sentence hits the same entry wherever it appears
  in a chapter.
- **TTL**, LRU-style eviction, `clear()`, and persistence across restarts.
- **Serialized writes.** All mutations pass through one queue.
- **A stale flush can never resurrect cleared data.** `clear()` bumps a
  generation counter *synchronously*; queued flushes and writes capture the
  generation when called and abort if it changed.
- **ENOENT-safe rename.** If the cache directory disappears between write and
  rename, it is recreated and the rename retried once; a still-failing rename
  leaves the cache dirty for a later retry instead of throwing.

---

## Retry, timeout, cancellation

- **Retry** — exponential backoff with jitter, capped. Only transient failures
  are retried (`ENGINE_UNAVAILABLE`, `RATE_LIMITED`, `TIMEOUT`, `INTERNAL_ERROR`).
  Permanent failures (`VALIDATION_ERROR`, `UNSUPPORTED_PAIR`, `EMPTY_INPUT`,
  `TEXT_TOO_LONG`, `QUOTA_EXCEEDED`, `CANCELLED`, `DEADLINE_EXCEEDED`,
  `CONFIG_ERROR`) are rethrown on the first attempt.
- **Timeout** — two mechanisms. A per-request timeout bounds one engine attempt;
  a service deadline bounds a whole chapter and reports which one fired. Each
  attempt is clamped to `min(requestTimeout, remaining budget)`, so a hanging
  engine can never hold a request or a chapter open.
- **Cancellation** — `CancellationToken` with a real `AbortSignal`. There is a
  pre-flight check before any cache or network work, an abort listener that
  settles the in-flight promise, and races against both the timeout and the
  token. Cancellation beats timeout, so a cancelled request reports
  `CANCELLED` rather than waiting for a timeout or surfacing the wrong code.
  Timers are deliberately not `unref`'d so a pending backoff or timeout can
  always fire.

---

## REST API

Base URL `http://127.0.0.1:8787`.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/translate` | translate one text |
| `POST` | `/translate/chapter` | translate a chapter |
| `POST` | `/detect-language` | detect the language of a text |
| `GET` | `/languages` | source/target languages, direction, engines |
| `GET` | `/engines` | per-engine availability, limits, routing table, breakers |
| `GET` | `/engine/route` | which engine would serve `?source=ja` |
| `GET` | `/secrets` | credential status per engine (never values) |
| `PUT` | `/secrets/deepl` | store the DeepL key; responds with status only |
| `DELETE` | `/secrets/deepl` | remove the stored DeepL key |
| `GET` | `/engine/capabilities` | engine id, languages, character limit |
| `GET` | `/engine/health` | engine health probe |
| `GET` | `/config` | effective configuration (credentials redacted) |
| `GET` | `/health` | service liveness |
| `DELETE` | `/cache` | clear the cache |
| `GET` | `/`, `/app.js`, `/styles.css` | standalone UI |

### `POST /translate`

```jsonc
// request
{
  "text": "Are you serious right now?!",
  "sourceLanguage": "auto",       // optional, default "auto"
  "targetLanguage": "ar",
  "engine": "mymemory",           // optional
  "noCache": false,               // optional
  "refresh": false,               // optional
  "timeoutMs": 15000,             // optional
  "retries": 2,                   // optional
  "deadlineMs": 30000             // optional
}
```

```jsonc
// 200 response
{
  "text": "هل أنت جاد الآن؟",
  "rawText": "…",                 // before Arabic normalization
  "sourceLanguage": "en",
  "detectedLanguage": "en",       // present when auto-detected
  "targetLanguage": "ar",
  "engine": "mymemory",
  "fromCache": false,
  "segments": 1,
  "elapsedMs": 812,
  "direction": "rtl",
  "quality": { "ok": true, "score": 0.85, "issues": [] }
}
```

### `POST /translate/chapter`

```jsonc
// request
{
  "segments": [{ "id": "p1", "text": "Are you serious?", "speaker": "Anna" }],
  "sourceLanguage": "en",
  "targetLanguage": "ar",
  "concurrency": 3,               // optional
  "joinWith": "\n",               // optional
  "deadlineMs": 120000,           // optional
  "noCache": false,               // optional
  "failurePolicy": "partial"      // "partial" | "abort"
}
```

```jsonc
// 200 response
{
  "text": "…",
  "segments": [
    { "index": 0, "id": "p1", "source": "…", "translated": "…",
      "fallback": false, "fromCache": false, "attempts": 1, "elapsedMs": 640 }
  ],
  "progress": { "totalSegments": 1, "completedSegments": 1, "failedSegments": 0,
                "cachedSegments": 0, "state": "completed" },
  "degraded": false,
  "elapsedMs": 651,
  "direction": "rtl",
  "progressEvents": [ /* every progress snapshot */ ]
}
```

### `POST /detect-language`

```jsonc
// request
{ "text": "你以为我是谁？" }

// 200 response
{
  "language": "zh", "confidence": 1,
  "evidence": { "han": 6, "kanaCount": 0, "arabicChars": 0, "latinChars": 0,
                "hiragana": 0, "katakana": 0, "hangul": 0, "cyrillic": 0,
                "totalSignificant": 7 },
  "alternatives": [],
  "direction": "ltr"
}
```

### Errors

```jsonc
{ "error": { "code": "VALIDATION_ERROR", "message": "targetLanguage is required",
             "status": 400, "retryable": false, "details": {} } }
```

| Code | HTTP | Retryable |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | no |
| `UNSUPPORTED_LANGUAGE` | 400 | no |
| `UNSUPPORTED_PAIR` | 400 | no |
| `EMPTY_INPUT` | 400 | no |
| `TEXT_TOO_LONG` | 413 | no |
| `RATE_LIMITED` | 429 | yes |
| `QUOTA_EXCEEDED` | 429 | **no** |
| `CANCELLED` | 499 | no |
| `ENGINE_UNAVAILABLE` | 503 | yes |
| `ENGINE_ERROR` | 502 | yes |
| `TIMEOUT` | 504 | yes |
| `DEADLINE_EXCEEDED` | 504 | yes |
| `CONFIG_ERROR` / `CACHE_ERROR` / `INTERNAL_ERROR` | 500 | no / varies |

Client disconnects cancel the in-flight engine request rather than leaving it
running.

---

## UI

`ui/` is a dependency-free single page served by the same server, with no mock
data path — every action is a real API call.

- Source/target selectors, auto-detect, text input, translate, copy, clear, retry.
- Loading, error, and quality-issue states.
- RTL output rendering when the target is Arabic.
- Chapter mode: panels from blank-line-separated text, progress bar with
  segment counts, per-segment translated/source/error detail, degraded-chapter
  warning, and "retry failed segments" that re-requests only what failed.
- Live engine, health, and character-limit badges.
- Settings tab: per-engine availability with reasons, the routing table, and a
  **password-style DeepL API key field** with show/hide, Save, Test connection
  and Delete. The stored value is never fetched back into the page; only
  `Configured / Not configured` plus a fingerprint is shown.

`npm run sync-ui` copies `ui/` into `dist/src/ui` for packaging.

---

## Configuration

Every setting is an environment variable, optionally loaded from `.env`. **No
credential is stored in source.** `GET /config` and logs never include the
contact address; they report `authenticated: true|false` instead.

| Variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_HOST` / `SERVER_PORT` | `127.0.0.1` / `8787` | listen address |
| `TRANSLATION_ENGINE` | `deepl` with a key, else `mymemory` | active engine id |
| `TRANSLATION_ENGINE_ROUTES` | *(empty)* | per-language routing, e.g. `ja=deepl,zh=deepl` |
| `TRANSLATION_ENGINE_FALLBACKS` | `mymemory` when a key is present | ordered fallbacks |
| `ENGINE_FAILURE_THRESHOLD` | `2` | consecutive failures before the breaker opens |
| `ENGINE_COOLDOWN_MS` | `60000` | how long a failing engine is skipped |
| `DEEPL_API_KEY` | *(empty)* | DeepL credential; env or encrypted store |
| `DEEPL_ENDPOINT` | free-plan endpoint | set to the Pro endpoint if needed |
| `DEEPL_TIMEOUT_MS` | `15000` | engine-level timeout |
| `DEEPL_MAX_CHARS` | `4000` | per-request limit, below the 128 KiB cap |
| `DEEPL_MIN_INTERVAL_MS` | `100` | throttle between requests |
| `DEEPL_FORMALITY` | *(empty)* | `default`/`more`/`less`/`prefer_*` |
| `MYMEMORY_ENDPOINT` | MyMemory `get` | engine endpoint |
| `MYMEMORY_CONTACT_EMAIL` | *(empty)* | optional; raises the free rate limit |
| `MYMEMORY_MAX_QUERY_CHARS` | `480` | capped at the API's 500 |
| `MYMEMORY_TIMEOUT_MS` | `15000` | engine-level timeout |
| `MYMEMORY_MIN_INTERVAL_MS` | `250` | throttle between requests |
| `REQUEST_TIMEOUT_MS` | `15000` | per-attempt timeout |
| `CHAPTER_DEADLINE_MS` | `120000` | service-level budget |
| `RETRY_MAX_ATTEMPTS` | `3` | attempts including the first |
| `RETRY_BASE_DELAY_MS` / `RETRY_MAX_DELAY_MS` / `RETRY_JITTER_RATIO` | `300` / `4000` / `0.2` | backoff |
| `CACHE_ENABLED` | `true` | cache on/off |
| `CACHE_DIR` | `.cache` | persistent cache directory |
| `CACHE_TTL_MS` | `2592000000` | 30 days |
| `CACHE_MAX_ENTRIES` | `5000` | eviction threshold |
| `CHAPTER_MAX_SEGMENTS` | `400` | chapter size guard |
| `CHAPTER_CONCURRENCY` | `3` | parallel segments |
| `CHAPTER_SEGMENT_MAX_CHARS` | `450` | per-segment limit |
| `CHAPTER_FAILURE_POLICY` | `partial` | `partial` or `abort` |
| `TRANSLATION_ALLOW_ECHO_ENGINE` | `false` | enable the offline test engine |
| `LOG_LEVEL` | `info` | `silent`…`debug` |

Invalid values fail fast with a `ConfigError` naming the variable.

---

## Credentials

**DeepL API key** is read from `DEEPL_API_KEY`, or from the encrypted runtime
store written by the UI. It is never hard-coded, never logged, and never
returned by any endpoint:

- Sent only in the `Authorization: DeepL-Auth-Key …` header — never in a URL.
- `GET /config` reports `configured: true|false` plus a 12-character SHA-256
  fingerprint. No value.
- `GET /secrets` reports status, source and fingerprint. No value.
- `PUT /secrets/deepl` responds with status only. The UI never reads the key back.
- Errors carry a status and an `X-Trace-ID`, never the key.

Storage when set through the UI: `$XDG_CONFIG_HOME/translation-platform/`
(default `~/.config/translation-platform/`), **outside the project directory**,
directory `0700` and files `0600`. Values are encrypted with AES-256-GCM under a
data key wrapped by scrypt from machine-local material. This is defence in depth
against backups, logs, screenshots and repository copies — not a defence against
someone who already has shell on this machine. The `SecretStore` interface exists
so a hardware-backed adapter (Android Keystore, macOS Keychain, libsecret) can be
dropped in on a host that has one.

**MyMemory contact address** is optional, read from `MYMEMORY_CONTACT_EMAIL`,
never logged, never returned.

## Engines in detail

### MyMemory (`mymemory`)

`https://api.mymemory.translated.net` — free, no key.

- Hard limit of 500 characters per query; segmentation respects it.
- The anonymous free tier is a **shared per-IP daily budget** (~5000 chars). When
  it is exhausted the API returns HTTP 429 with a quota message; the engine raises
  a **non-retryable** `QUOTA_EXCEEDED`, because retrying a six-hour wall only
  wastes time.
- Requests are throttled (250 ms minimum interval, serialized) so a chapter does
  not trip the rate limiter.

**Measured quality** (live, recorded earlier in this project): English → Arabic is
good. Japanese, Chinese and Korean → Arabic is **mediocre**. Good results such as
`本気なのか？` → `أأنت جاد؟` and `俺はここで待っている。` → `سأنتظر هنا.` sit
alongside genuinely weak ones such as `そんなわけないだろ。` → `اتمزح ؟` and
`别开玩笑了。` → `لا تكن سخيفًا.` ("don't be silly" flattened into a mild
admonition). The quality layer reports this rather than hiding it.

### DeepL (`deepl`)

`https://api-free.deepl.com/v2/translate` (free plan; set `DEEPL_ENDPOINT` to
`https://api.deepl.com/v2/translate` for Pro).

- `POST /v2/translate` with a JSON body, auth via the `DeepL-Auth-Key` header.
- 4000 characters per request by default, well under the 128 KiB API limit.
- Error mapping is deliberate: **456 → non-retryable `QUOTA_EXCEEDED`**,
  429/529 → retryable `RATE_LIMITED`, 403 → `CONFIG_ERROR` naming `DEEPL_API_KEY`,
  413 → `TEXT_TOO_LONG`, 5xx → retryable.
- Requests are serialized with a 100 ms minimum interval, so a chapter with
  concurrency 3 does not produce a burst per segment.

**Live DeepL quality has not been measured in this environment** — no
`DEEPL_API_KEY` was available while building this. The engine, its error
mapping, its routing and its credential handling are all unit- and
integration-tested, and 38 live tests exist for it, but no live DeepL request has
been made. `npm run test:live:deepl` and `npm run benchmark` produce the real
comparison as soon as a key is present.

### Echo (`echo`)

Offline test double. Rejected unless `TRANSLATION_ALLOW_ECHO_ENGINE=true`, marked
with confidence 0, and never used as a production fallback.

---

## Testing

```sh
npm test                       # 364 tests: unit + integration + REST, no network
npm run test:unit              # unit only (253)
npm run test:integration       # 64
npm run test:rest              # 47
npm run test:live              # MyMemory live; LIVE_TRANSLATION_TESTS=1
npm run test:live:deepl        # DeepL live; LIVE_DEEPL_TESTS=1 + DEEPL_API_KEY
npm run smoke                  # end-to-end diagnostics
npm run smoke -- --offline
npm run benchmark              # DeepL vs MyMemory on the same fixtures
```

Live tests are skipped unless their flag is set (`LIVE_TRANSLATION_TESTS=1` for
MyMemory, `LIVE_DEEPL_TESTS=1` plus `DEEPL_API_KEY` for DeepL). When a quota is
exhausted, or a key is absent, they report **SKIPPED with the reason** — never a
silent pass, and never a false failure.

### Adding a DeepL key

Three options; all keep the key out of the repository.

1. **Environment** — `export DEEPL_API_KEY=…` before `npm start`. Environment
   variables always win over the encrypted store.
2. **UI** — Settings tab → paste the key → Save. Encrypted on disk (see
   [Credentials](#credentials)) and effective immediately, no restart.
3. **Encrypted runtime store** — written by the UI or by
   `PUT /secrets/deepl`. Stored under `$XDG_CONFIG_HOME/translation-platform/`
   (default `~/.config/translation-platform/`), outside any project folder.

Without a key nothing breaks: DeepL reports
`unavailable: DEEPL_API_KEY is not set`, routing skips it, and MyMemory keeps
serving requests. No fake translation is ever produced.

| Suite | File | Count |
| --- | --- | --- |
| Unit | `test/unit/*.test.ts` | 253 |
| Integration | `test/integration/*.test.ts` | 64 |
| REST | `test/rest/*.test.ts` | 47 |
| Live (MyMemory) | `test/live/liveTranslation.test.ts` | 31 (need quota) |
| Live (DeepL) | `test/live/deeplLive.test.ts` | 38 (need key) |

One unit assertion was intentionally updated during the DeepL integration: the
default registry now also exposes `deepl`, so `registry.ids()` changed from
`[echo, mymemory]` to `[deepl, echo, mymemory]`. No test was deleted.

`test/unit/regression.test.ts` names each defect from the previous version and
fails if it returns: Chinese-as-Japanese, cache keys polluted by `contextBefore`,
file-cache `clear`/`flush` races, resurrecting flushed data, wrong test scripts
or entry points, tautological smoke tests, a `noCache`-first cache sequence,
engines hard-wired into the service, hangs when the engine hangs, bidi marks
counted as characters, quota treated as retryable, and cancellation not reaching
the engine.

Stability: the full suite is run repeatedly after each change; see the final
report for the exact numbers.

---

## Project layout

```
src/
  index.ts                    public exports (import from here)
  config/                     env parsing, validation, redacted description
  core/                       types, errors, logger, retry, timeout, cancellation, validation
  language/                   detection, registry
  arabic/                     Arabic processing
  segmentation/               segment / merge
  cache/                      keys, memory, file, tiered
  engine/                     interface, HTTP helper, registry, routing
    deepl/                    DeepL engine
    mymemory/                 MyMemory engine
    echo/                     offline test engine
  security/                   encrypted secret store
  service/                    translation service, quality
  translator/                 public Translator API + routing/fallback/breaker
  chapter/                    chapter translation
  server/                     REST server, routes, static UI, main
  scripts/
    smoke.ts                  smoke diagnostics
    benchmark.ts              DeepL vs MyMemory comparison
test/
  unit/  integration/  rest/  live/  helpers/
ui/                           standalone UI incl. the Settings key field
docs/                         architecture, roadmap
```

---

## Shura readiness

Not integrated, by design. When that happens the intended shape is:

```
Shura
  ↓  Translation API / SDK (this package)
Translator
  ↓
Translation Service
  ↓
TranslationEngine
```

Shura would need to know none of: MyMemory's endpoint or limits, cache
internals, retry policy, segmentation internals, or error message text — only
`translate()`, `translateChapter()`, `detectLanguage()`,
`getSupportedLanguages()` and the error `code`/`status` contract.

For the reader UI, translated text is expected to render **inside the reader's
own text flow** — not as a screen overlay or floating window — so that scroll,
swipe, tap, zoom, navigation and chapter controls remain unaffected. Nothing in
this project implements that; it is noted here as the integration requirement.

---

## Status

Implemented and tested: core layers, language detection, Arabic processing,
segmentation and merge, persistent cache, the engine interface, **both** engines
(DeepL and MyMemory), routing, fallback, the circuit breaker, retry /
timeout / cancellation, the translation service, the Translator API, chapter
translation, the REST server, the UI including the secure key field, the
encrypted credential store, 364 automated tests, and a passing smoke run.

Not verified here: **DeepL translation quality**. The engine is implemented and
unit-tested, its error mapping is tested, and live tests exist, but no live
DeepL request has been made because no key was available in this environment.
Run `npm run test:live:deepl` and `npm run benchmark` with a key to close that
gap. Known limit: the MyMemory free tier has a shared daily quota, and live
MyMemory tests report SKIPPED while it is exhausted. Android is out of scope — no
Android build is claimed.