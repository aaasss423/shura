# Agent Work State — Phase 2 (content fetch/classify subsystem)

Resource-constrained environment: work in short batches, checkpoint to this file after each.
Do NOT restart from scratch. Read the last "Next step" line and resume from there.

## Environment constraints (why batching is required)

- Device is an Android/PRoot guest, not a normal Linux box.
- Observed load average ~26–30 with 7.7 GB total RAM.
- Memory at diagnosis time: 3988 MB used, 193 MB free, 3583 MB available, swap 589 MB used.
  => real memory pressure; swap is already in use. This is the likely cause of agent termination.
- `ps`/`top` exist but return no rows (procfs restricted), and `dmesg` is unreadable.
  => per-process CPU/memory attribution is NOT available. Do not claim it.
- Disk is NOT a constraint: 191.6 GB available on `/` (13% used).

## What phase 2 is

New content pipeline under `shura_core/content/` plus three new test files.
Goal: fetch/classification gate that is default-deny, integrated into the production accept path.

## Status ledger

| Batch | Scope | State |
|-------|-------|-------|
| 0 | Lightweight diagnosis | DONE |
| 1 | Content schema + classification gate | DONE — 45 passed |
| 2 | Content fetch + network guard | IN PROGRESS — OOM cause found & fixed |

## Batch 0 — diagnosis (complete)

Findings:
- No pre-existing checkpoint file existed before this one.
- No git stash, no evidence of a destructive git operation.
- No captured exit code or crash log from the previous failure; the agent process was killed
  from outside (consistent with an OOM/resource kill), not with a test assertion failure.
- Work stopped during test execution / file writing: `shura_core/content/*.py` timestamps span
  20:29–20:50 and the three new test files are untracked and never committed.
- Full-suite reruns are the most plausible trigger: the repo also contains Android/Gradle,
  Node (translator), and ClamAV-related paths that are expensive or fail without real tooling.

Files present but not yet committed (do not delete, do not overwrite):
- Modified: `shura_core/pipeline/processor.py`, `shura_core/publishing/repository.py`,
  `shura_core/security/artifacts.py`, `tests/test_content_policy.py`, `tests/test_core.py`,
  `tests/test_malware.py`
- Modified (OFF LIMITS, leave alone): everything under `translator/`
- Untracked: `shura_core/content/` (`__init__.py`, `schema.py`, `store.py`, `storage.py`,
  `fetcher.py`), `tests/test_content_fetch.py`, `tests/test_content_schema.py`,
  `tests/test_integration_production_path.py`
- Untracked OFF LIMITS: `star-catcher/`, `translator/android/`, `repo/` contents

Verification so far:
- `python3 -m pytest --collect-only` on the three new test files: 94 tests collected in 0.61s,
  exit 0. No import errors. Collection is cheap and safe to repeat.

## Rules for this session

- One worker. No parallel test runs. No `-n` / xdist.
- Run only the specific test file for the batch being worked on.
- Do not run Gradle, Android builds, real ClamAV, or the translator Node suites.
- Do not touch `repo/`, Android, `translator/`, `star-catcher/`.
- No commit, no push, no destructive git.

## Batch log

### Batch 0 — diagnosis — DONE
Ran: meminfo, df, uptime/loadavg, ps (no rows), dmesg (denied), git status/log/diff --stat,
pytest --collect-only (94 tests, exit 0).
Result: memory pressure confirmed; per-process attribution unavailable; no crash log.
Next step: run `tests/test_content_schema.py` alone and record pass/fail.

### Batch 1 — `tests/test_content_schema.py` — DONE (no code change needed)
Command: `python3 -m pytest tests/test_content_schema.py -q -p no:cacheprovider`
Result: **45 passed, 3 subtests passed in 1.72s**, exit 0.
Files changed: none. The already-written `shura_core/content/schema.py` satisfies its tests.
Next step: run `tests/test_content_fetch.py` alone and record pass/fail.

### Batch 2 — `tests/test_content_fetch.py` — ROOT CAUSE FOUND AND FIXED

Symptom: the run died after 4 dots, twice (once inline, once under nohup). Log tail was `....`.
Isolated to the 5th collected test: `ValidationTests::test_oversized_dimensions_are_rejected`.

Root cause: the test helper built a real pixel buffer.
`png(60000, 60000)` in `tests/test_content_fetch.py` joins 60000 rows of
`1 + 60000*3` bytes => ~10.8 GB of raw rows before `zlib.compress`. That single
allocation is what was exhausting the device and getting the agent killed.
This is a TEST-DESIGN bug, not a production bug.

Why the production code is already correct: `validate_page`
(`shura_core/content/storage.py:122`) reads only `HEADER_BYTES` and pulls
width/height from the IHDR at `storage.py:60`; it refuses on dimensions at
`storage.py:161-164` and never decodes the image. Building real pixels was pure waste.

Fix applied (test-only, no production change):
- Added `declared_png(width, height)` helper: emits IHDR with the declared
  dimensions plus a trivial empty IDAT. ~90 bytes instead of ~10.8 GB.
- `test_oversized_dimensions_are_rejected` now uses `declared_png(60000, 60000)`.
- Added `test_oversized_pixel_count_is_rejected` using `declared_png(19000, 19000)`,
  which exercises the previously-uncovered `MAX_PIXELS` branch at `storage.py:163`
  (19000 < MAX_DIMENSION=20000 so it passes the side check, then fails on 361M pixels).

Verification:
- `pytest tests/test_content_fetch.py::ValidationTests -q` => **7 passed in 0.71s**, exit 0.
  Same tests that previously killed the run now finish in under a second.

### Batch 3 — fix `source_policy` fixture — DONE

Symptom: after the OOM fix, 22 of 43 tests in `test_content_fetch.py` failed with
`sqlite3.OperationalError: 6 values for 7 columns`.

Root cause: test-only. `FetchCase.setUp` inserted into `source_policy`
(`shura_core/content/schema.py:53`, 7 columns) but supplied 6 VALUES — `retention_days`
was never bound. Every test inheriting `FetchCase` died in setUp.
Production `sync_source_policy` at `shura_core/content/fetcher.py:318` already binds all 7
placeholders correctly, so no production change was needed.

Fix: `tests/test_content_fetch.py` setUp now binds `retention_days=30`
(`VALUES('s1','index','["example.org"]',1,?,?,'now')`, params `(1 << 40, 30)`).

Verification: `pytest tests/test_content_fetch.py -q` => **6 failed, 37 passed in 6.51s**
(down from 22 failed / 21 passed).

### Batch 4 — `usage_bytes` + byte-budget + fence (PRODUCTION fixes) — DONE

Three separate defects found once setUp was unblocked.

1. `usage_bytes` never counted the pages it was meant to bound — PRODUCTION BUG.
   `begin_attempt` (`shura_core/content/store.py:216-218`) puts final pages at
   `pages/<slug>/<work>/<chapter>/g0001/p0000.img` but parts at `content/tmp/...`.
   `usage_bytes` walked only `root/"content"`, i.e. only transient `.part` files.
   Placed pages were invisible, so the quota check at `fetcher.py:140`
   (`if storage.usage_bytes(self.root) >= quota`) could never fire — the source
   storage quota was dead code.
   Fix: `shura_core/content/storage.py::usage_bytes` now sums `root/"pages"` and
   `root/"content"`, deliberately excluding the `.db` (the quota bounds page data,
   not the index).

2. Byte ceiling was only checked at entry — PRODUCTION BUG.
   `Budget.exhausted()` (`fetcher.py:53`) was consulted once at the top of
   `fetch_page`, so the first page always landed even when it could not fit;
   only the *next* page was skipped. `max_bytes` was therefore overshootable by
   one page per run.
   Fix: `shura_core/content/fetcher.py::fetch_page` now compares
   `bytes_used + checked.size > max_bytes` after validation, and if it does not
   fit, unlinks the part, calls `abandon_attempt`, records `PENDING` and returns
   `SKIPPED` — mirroring the existing quota-hold path. The page stays startable.

3. Fence test mock expected two `assert_fence` calls, only one exists — TEST BUG.
   `fetcher.py:25` imports `assert_fence` into its own namespace, so
   `mock.patch("shura_core.content.fetcher.assert_fence")` intercepts only the
   call at `fetcher.py:177`. The calls in `store.py` (188/192/241/244/265/269)
   resolve through the `store` module globals and bypass the mock. The test's
   `side_effect=[None, FenceLost(...)]` therefore never raised.
   Fix: `side_effect=FenceLost("displaced")` so the single intercepted call raises.
   Production order (`assert_fence` at 177 BEFORE `place_page` at 178) was already correct.

Verification: `pytest tests/test_content_fetch.py::BudgetAndQuotaTests ::FenceDuringFetchTests -q`
=> **7 passed in 1.22s**. `test_usage_is_measured_from_the_filesystem` now passes too.

### Batch 5 — redirect guards + FAILED-retry — DONE

4. Redirect tests broke on Python 3.14 — TEST BUG (version coupling).
   Both `NetworkGuardTests` called `http_error_302(None, None, 302, Request, None, None)`.
   That is the ≤3.13 stdlib signature. On 3.14 urllib dropped the `newurl` parameter:
   `http_error_302(self, req, fp, code, msg, headers)` — hence "takes 6 positional
   arguments but 7 were given". The project declares `requires-python = ">=3.11"` and
   CI runs 3.12, so the test had to work on both.
   Fix: call `SafeRedirect.redirect_request(...)` instead. That is the project-defined
   override at `shura_core/security/network.py:25` where the allow-list, loop and hop
   checks actually live, and its signature is stable across versions. Pass a real
   `urllib.request.Request` because the `super()` call dereferences `req.get_method()`.
   `test_a_redirect_loop_is_stopped_by_the_redirect_limit` was split into its two real
   assertions (loop detection, and the hop ceiling independently) instead of looping 4x
   and asserting a raise on every iteration, which was only true by accident of arity.

5. A rejected page could never be retried — PRODUCTION BUG.
   `test_a_rejected_page_can_be_retried_and_can_succeed` returned `WAITING:
   "page 0 is FAILED, not startable"`. `begin_attempt` (store.py:200) consults `STARTABLE`,
   which was `("PENDING", "TRANSIENT", "QUOTA_HOLD")` — `FAILED` absent, so a rejected page
   was permanently stuck. That contradicts `fetch_page`'s own docstring ("Every outcome
   other than DONE leaves the page startable") and left `abandon_attempt` (which correctly
   resets the page to PENDING) immediately overwritten by `_record(..., "FAILED", ...)`.
   Fix: `shura_core/content/store.py::STARTABLE` now includes `FAILED`, with a comment
   stating that FAILED records why the last attempt was rejected, not that the page is
   ungettable. `BLOCKED` stays deliberately absent so content-gated pages are never retried.
   The recorded state is still `FAILED`, so the two tests asserting `state == "FAILED"`
   (lines 215/238) still hold.

Verification: `pytest tests/test_content_fetch.py -q` => **43 passed in 3.98s**, exit 0.
Whole file green for the first time.

### Batch 6 — integration + regression — DONE

`tests/test_integration_production_path.py` => **7 passed in 9.86s**, exit 0.
  No changes needed; the production-path wiring already honoured the new fence,
  byte budget and startable rules.

Regression check on the tracked files modified in earlier sessions (these exercise
the production code changed in batches 4 and 5):
- `tests/test_content_policy.py` + `tests/test_core.py` => **53 passed in 40.92s**, exit 0.
- `tests/test_malware.py` => **14 passed in 226.62s (3m46s)**, exit 0.

RESOURCE NOTE — this is the measured explanation for the earlier terminations:
`tests/test_malware.py` alone runs for ~3m46s on this device, under a load average of
~29 with swap already in use. That single file, run as part of a full suite with no
other bound, is far more than the guest can hold. Full-suite runs must stay a single,
deliberate, end-of-phase action — never a diagnostic.

### Current totals for the phase-2 files
- `tests/test_content_schema.py` 45 passed
- `tests/test_content_fetch.py` 43 passed
- `tests/test_integration_production_path.py` 7 passed

### Batch 7 — single full-suite run — DONE, PHASE 2 COMPLETE

Launched detached (`setsid nohup`, output to `/tmp/full_suite.log`) so the run would
survive a tool-call interruption. It did; polled to completion.

`python3 -m pytest tests/ -q` => **287 passed, 2 skipped, 17 subtests passed in 319.24s (5m19s)**, EXIT=0.

The 2 skips are pre-existing (the Pillow-gated `tests/test_protected_tile_decode.py`
harness documented in `.gitignore`, plus one more).

### PHASE 2 STATUS: COMPLETE AND PROVEN

Full suite exit 0. Not claimed until the full run proved it.

Files changed by this session (all under `shura_core/content/` + `tests/`, which are
untracked, so `git diff --stat` shows nothing for them):

Production:
- `shura_core/content/storage.py` — `usage_bytes` now counts `pages/` as well as `content/tmp/`
- `shura_core/content/fetcher.py` — `fetch_page` refuses to commit a page that does not
  fit in the remaining byte budget (SKIPPED, page left startable)
- `shura_core/content/store.py` — `STARTABLE` now includes `FAILED` so a rejected page
  can be retried; `BLOCKED` still excluded

Tests:
- `tests/test_content_fetch.py` — `declared_png()` header-only helper (replaces a ~10.8 GB
  allocation), `source_policy` fixture binds `retention_days`, fence mock fixed to one
  call, redirect tests moved to the version-stable `redirect_request` hook
- `AGENT_STATE.md` — this checkpoint file (new, untracked)

NOT touched this session, verified byte-identical to the diagnosis-time capture:
`repo/`, `android/`, `translator/`, `star-catcher/`. The `M translator/*` and
`?? star-catcher/`, `?? translator/android/` entries in `git status` are pre-existing
from before this session, not from it.

No commit, no push, no destructive git command was used at any point.

### If you resume
Nothing is outstanding in phase 2. Read this file first; do not re-run the diagnostics
and do not re-run the suites unless you are changing something — the full suite costs
5m19s on this device and is what previously exhausted it.

---

# Phase 3 — approved scope (network stream + production wiring)

Approved: `SafeHTTP.stream` + `SafeHTTPTransport` + `run_fetch_run` + CLI `content-fetch`.
NOT approved / excluded this phase: `daemon.py` wiring.

## Condition 1 — network policy errors separated from content-size errors

`chapter_pages.state` (schema.py:141-142) allows PENDING / IN_PROGRESS / DONE / TRANSIENT /
FAILED / BLOCKED / QUOTA_HOLD. Classification on the new streaming path:

| Raised by | Meaning | State | Retried? |
|---|---|---|---|
| `NetworkPolicyError` — host not allowed, https required, credentials in URL, non-standard port, private/reserved host, redirect limit, redirect loop | the request was refused by policy | **BLOCKED** | never (absent from `STARTABLE`, store.py:33) |
| `ResponseTooLarge` (new subclass of `NetworkPolicyError`) | the response body exceeds the ceiling | FAILED | bounded by `MAX_PAGE_ATTEMPTS` + backoff |
| `PageRejected` (storage.py:43, a `ValueError`) | the bytes are not a usable page | FAILED | bounded |
| `URLError` / `socket.timeout` / `OSError` | network fault | TRANSIENT | bounded |
| budget / quota | run paused | SKIPPED / QUOTA_HOLD | n/a |

Why a subclass rather than a message check: `network.py:48` and `:50` already both raise
`NetworkPolicyError("response exceeds size limit")`. Making `ResponseTooLarge` a *subclass*
keeps every existing `except NetworkPolicyError` working unchanged, while letting the fetcher
tell "the policy refused this" from "this was too big". No existing BLOCKED semantic changes:
BLOCKED stays out of `STARTABLE`, is never armed with a backoff, and is not a retryable state.

This does NOT create unlimited retries: the size case lands in FAILED, which is already capped
at `MAX_PAGE_ATTEMPTS = 5` with doubling backoff capped at `RETRY_MAX_SECONDS`.

## Condition 2 — activation comes from the local content-sources.json only

Verified before designing: `load_source_config(path)` (fetcher.py:298-342) already reads
`content-sources.json` directly and is default-deny — a missing/unreadable/invalid file, a
wrong `version`, a non-list `sources`, or any entry missing one of `_REQUIRED` yields fewer
sources, and a source is fetchable only when its own `fetch_enabled` is `true`.

Therefore `run_fetch_run`:
- calls `load_source_config(path)` — reads the JSON file, no database involved
- does NOT call `sync_source_policy` (fetcher.py:345), so nothing is mirrored into `source_policy`
- does NOT call `fetchable_sources` (fetcher.py:366), so the DB table is not an activation source
- does NOT touch `shura.db`, `source_policy`, or the existing sources table

Any source absent from the file, or present with `fetch_enabled: false`, is simply never
constructed — there is no code path that can fetch from it.

## Lock and fencing obligations (must survive this phase)

- `assert_fence` before `place_page` and before `commit_done` (already in `fetch_page`)
- `renew_lock` during the run; losing the lock raises `FenceLost` rather than returning quietly
- `release_lock` in `finally`
- `fetch_lock` is a single row (`lock_id=1`) — one fetching process at a time, no parallel workers

---

# Phase 3 — IMPLEMENTED (daemon wiring deliberately NOT done)

## Files created
- `shura_core/content/transport.py` — `SafeHTTPTransport`, delegates to `SafeHTTP`; `stream()`
  is the primary path, `get()` kept only for whole-body callers
- `shura_core/content/runner.py` — `FetchRunReport`, `default_identity`, `run_fetch_run`
- `tests/test_content_http.py` — 20 tests over a real loopback HTTP server

## Files changed
- `shura_core/security/network.py` — added `ResponseTooLarge(NetworkPolicyError)`, `_READ_CHUNK`,
  and `SafeHTTP.stream()` context manager; the two size raises in `get()` now use the subclass
- `shura_core/security/__init__.py` — export `ResponseTooLarge`
- `shura_core/content/fetcher.py` — `fetch_page` now streams; new `_fail` helper centralises
  partial-file removal + intent release + state recording on every failure path; imports the
  two network exceptions
- `shura_core/content/__init__.py` — export `SafeHTTPTransport`, `run_fetch_run`,
  `FetchRunReport`, `default_identity`
- `shura_core/cli.py` — `content-fetch` subcommand
- `tests/test_content_fetch.py` — `FixtureTransport` gained `stream()` mirroring the real
  semantics (chunks + limit+1 detection); fault raised on entry, as a real `open` would

## Two real defects found and fixed during implementation
1. `stream()` originally truncated silently at the ceiling instead of refusing. Fixed to read
   one byte past the limit and confirm there is more, matching `get()`'s `len(data)>limit`.
2. `default_identity()` returned a string while being annotated `tuple[str, str]`; unpacking it
   raised `ValueError`. Fixed to return `(f"pid-{pid}", hostname)`.

## Verification (targeted, no full suite)
- `tests/test_content_http.py` — **20 passed** (stream, BLOCKED-vs-FAILED, activation, guards)
- `tests/test_content_fetch.py` + `test_content_schema.py` — **98 passed, 3 subtests**
- `test_integration_production_path.py` + `test_content_http.py` + `test_content_policy.py`
  — **52 passed**
- `tests/test_core.py` — **28 passed**
- CLI smoke: `content-fetch` with no config file → `sourcesConsidered: 0`, nothing fetched,
  only `content.db` created (no `pages/`, no `content/tmp/`) — default-deny confirmed live

## NOT proven / not done this phase
- `daemon.py` is not wired, as instructed.
- No test runs against a real remote host; the HTTP tests bind 127.0.0.1 and construct the
  client with `https_only=False, allow_private_hosts=True, allow_nonstandard_ports=True`.
  HTTPS enforcement and private-host refusal are asserted separately against `validate_url`,
  but the end-to-end HTTPS path itself is unexercised here.
- `renew_lock` during a run is exercised only by code inspection and the locked-out test;
  there is no test that forces a mid-run lease expiry.
- `shura.db` / the existing sources tables are untouched; `git log` still at `b4e08a4`, no
  stash, and `find` confirms 0 files touched under `repo/ android/ translator/ star-catcher/`.

---

# Final verification round (locked) — DONE

## 1. ResponseTooLarge is not swallowed by the NetworkPolicyError clause
Reviewed the actual order in `fetch_page`: `ResponseTooLarge` (line 200) precedes
`NetworkPolicyError` (line 205), and Python takes the first match — so a size overrun records
FAILED with a backoff armed. Proven against the **stored row**, not the exception class:
`SizeIsRetryableAndPolicyIsNotTests` asserts `state == "FAILED"` **and** `next_retry != ""`.
Had the wrong clause won, those would be `BLOCKED` and `""`. The control case
(`test_a_policy_refusal_is_blocked_with_no_backoff`) shows the same server, same code path,
an off-allow-list redirect instead — landing `BLOCKED` with `next_retry == ""`.
Also proven the size path is bounded: after `MAX_PAGE_ATTEMPTS` attempts the row is FAILED,
`attempts == 5`, and `next_retry` is empty.

## 2. Lock loss — proven, not reviewed
`DisplacedOwnerTests` (tests/test_content_locking.py) takes the lease over mid-flight to mint a
strictly newer fencing token (`assertGreater(newer.token, stale_token)`), then lets worker A
finish. Proven: no `*.img` final file, page state never `DONE`, no evidence columns written,
no `.part` left. The mid-transfer case uses a body that validates so the fence check guarding
`place_page` is genuinely reached, and asserts `FenceLost` specifically (not bare `Exception`).

Two real defects found by these tests and fixed:
- **Renewal only happened between pages.** A single page slower than `LOCK_TTL_SECONDS` (90 s)
  could lose its own lease. Added a throttled `heartbeat` to `write_bounded`, driven by an
  injectable `clock` and `renew_every` (default `LOCK_TTL_SECONDS/3`). A lost fence during the
  heartbeat propagates instead of being noticed only at commit time.
- **A displaced owner leaked its `.part` file.** The `assert_fence` → `place_page` →
  `commit_done` sequence is now wrapped so `FenceLost` unlinks the part and re-raises; the save
  intent is left alone, since it belongs to an attempt this process no longer owns.

Renewal during a long transfer is proven with a fake clock advanced inside the chunk loop over
a ~2.5 MB undeclared-size body: renewal fires repeatedly *inside a single* `fetch_page`
(asserted `> 1`), not once per page.

## Changed this round
- `shura_core/content/storage.py` — `write_bounded(..., heartbeat=None)`
- `shura_core/content/fetcher.py` — `renew_every`/`clock` on `PageFetcher`, `_heartbeat`,
  passed into `write_bounded`; `FenceLost` cleanup around place/commit; `renew_lock` imported
- `tests/test_content_locking.py` (new) — 10 tests

## Results this round (no full suite, no test_malware.py)
- `tests/test_content_locking.py` — **10 passed** (10.25s)
- `test_content_fetch.py` + `test_content_schema.py` + `test_content_http.py`
  — **118 passed, 3 subtests** (15.56s)
- `test_integration_production_path.py` + `test_content_policy.py` — **32 passed** (8.10s)
- `tests/test_core.py` — **28 passed** (38.58s)

## Still NOT proven
- **No local HTTPS test.** Unverified: an end-to-end HTTPS fetch. It needs a certificate and a
  TLS-wrapped test server — a self-signed cert plus `ssl.SSLContext` in the existing
  ThreadingHTTPServer, and `https_only=True` with the host allow-listed. Roughly one fixture;
  listed as a separate step, not started, as instructed.
- `renew_every`'s interaction with a genuinely slow (not fake) clock is unmeasured; the
  threshold is asserted to be shorter than the lease, but no test lets real time pass.
- `daemon.py` still not wired.
- `tests/test_malware.py` not run this round (instructed); it does not touch the code changed
  here.

# Closing review — three points settled after the first green run

## 1. Memory: the oversized-image fixture — DONE, measured

Change in `tests/test_content_fetch.py`: `png()` line 35 (`raw = b"".join(...)`) built
the full raster. It is now bypassed for the dimension cases by `declared_png(width, height)`
(test file lines 40-52), which emits the IHDR plus an empty IDAT and nothing else.

- on-disk size: `declared_png(60000,60000)` = **65 bytes** vs **10.80 GB** for the old raster
- peak child RSS running only the two dimension tests: **37.0 MB**, 2 passed in 0.57s

`validate_page` reads the IHDR and refuses on dimensions (`storage.py:161-164`) without ever
decoding, so the pixels were never needed. `png()` is kept for the small, real fixtures.

## 2. Byte ceiling — DONE (this was a real gap)

Before: `fetcher.py` did `part.write_bytes(body)` — the *entire* body was written to disk and
only then did `validate_page` check the size. `SafeHTTP.get` bounded its own read
(`network.py:49`, `response.read(limit+1)`), but that ceiling and `max_page_bytes` are
independent, so a legal response could still be written whole before being refused.

Now: `storage.write_bounded(path, chunks, max_bytes=...)` writes in 64 KiB slices and raises
`PageRejected` the moment the running total would cross the limit. `fetcher._slices()` feeds
the transport body through it. The partial file is unlinked and the handle closed inside the
`except`/`with`, so nothing half-written can be counted as content and no descriptor leaks.

`BoundedWriteTests` (4 tests) covers a body that never declares a Content-Length and arrives in
1000-byte chunks against a 4096 limit: the write stops at the first over-limit chunk, the part
does not survive, the path is immediately reusable, an exactly-at-limit body is accepted, and
an over-limit body through `fetch_page` leaves `usage_bytes` unchanged.

## 3. FAILED semantics — DONE, bounded

`FAILED` means **"the last attempt was rejected and here is why"**, not "ungettable".
It is startable only within a bounded budget:

- `MAX_PAGE_ATTEMPTS = 5` — `store.begin_attempt` refuses once a page has used all 5
- `RETRY_BASE_SECONDS = 60`, doubling per attempt, capped at `RETRY_MAX_SECONDS = 3600`
- the page row's existing (previously unused) `next_retry` column carries the backoff;
  `store._reached()` gates the next attempt until it passes
- the ceiling is not silently extended: on the last allowed attempt `next_retry` is cleared,
  so a retired page does not keep re-arming a backoff

Security/final states are untouched: `BLOCKED` is deliberately absent from `STARTABLE` and is
never retried at any attempt count (`test_a_blocked_page_is_never_retried_...` asserts it).

`RetryPolicyTests` (6 tests) covers backoff armed on failure, WAITING during backoff, retry
succeeding after it elapses, the doubling-and-cap curve, retirement at the ceiling, a transient
network fault retrying within the ceiling, and BLOCKED staying unstartable.

### Verification for this review round (no full-suite rerun)
- `tests/test_content_fetch.py` **53 passed** in 2.07s
- `tests/test_content_schema.py` + `tests/test_integration_production_path.py`
  **52 passed, 3 subtests** in 8.48s
- `tests/test_core.py` + `tests/test_content_policy.py` **53 passed** in 35.26s
- `tests/test_malware.py` NOT re-run: it touches none of the code changed in this round
  (only `shura_core/content/*` and `tests/test_content_fetch.py` were modified) and costs 3m46s.

No commit, no push, no destructive git. `repo/`, `android/`, `translator/`, `star-catcher/`
untouched. No external content fetched.