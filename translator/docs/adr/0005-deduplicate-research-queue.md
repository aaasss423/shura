# ADR 0005 — One research job per normalized key

Status: Accepted · 2026-10

## Context

500 users reading chapter 12 of the same series will each hit the same unknown
phrase. Without deduplication that is 500 web searches for one answer.

## Decision

`research_jobs` has a **unique key** on `(normalized_key, target_language)`.
Enqueue is an upsert that returns the existing job when one is live
(`queued` or `running`), and increments `waiters`.

Lifecycle: `queued → running → completed | failed | needs_review`, with `retry`
as a scheduled return to `queued`.

Normalized key is `(source_language, normalized_term, category_hint,
target_language)` — deliberately **excluding** the surrounding sentence.
"先生" is one research job whether it appeared in ten different panels. Context is
attached to the job, not part of its identity.

## Consequences

- `enqueue` is idempotent and safe to call from the hot path.
- Waiting requests do not each get a result; they get the entry once the job
  completes. The response contract therefore never promises "researched".
- `waiters` gives us a natural popularity signal for prewarm prioritisation: the
  phrases real readers hit most are the ones worth researching first.
- A failed job is retried with backoff up to `max_attempts`, then lands in
  `needs_review` rather than being silently dropped.

## Reversal

No. Deduplication is the difference between one search and five hundred.
