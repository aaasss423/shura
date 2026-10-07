# ADR 0006 — Build our own eval set before trusting any model claim

Status: Accepted · 2026-10

## Context

The most important finding from model research: **there is no public benchmark
for `ja→ar`, `zh→ar` or `ko→ar`.** WMT24++ is `en→xx` for 55 languages. Every
quality claim about our priority pairs is therefore an extrapolation, and the one
place we found translation tuning *losing* (`ja→en`, TranslateGemma 12B/27B worse
than the Gemma 3 baseline) shows extrapolation is not safe.

## Decision

Build a **manga evaluation corpus** as a first-class deliverable, and require it
before any model or routing change is called a quality improvement.

Corpus properties:
- Real dialogue and narration per source language, tagged by category
  (dialogue, narration, sound_effect, honorific, slang, name, numbers,
  punctuation, mixed Latin, multi-line).
- Reference translations reviewed by a competent speaker, with per-item notes.
- Includes deliberate traps: honorifics that must not be dropped, onomatopoeia,
  gender markers, series-specific terms.
- Versioned and frozen, so scores are comparable across runs.

Evaluation reports per-pair and per-category results, plus side-by-side output,
and **never** collapses into a single opaque number.

## Consequences

- Model selection becomes measurable instead of assumed.
- The prewarm corpus (a separate concern) can reuse the same storage and
  category taxonomy.
- Fine-tuning data must be disjoint from the eval set, or the numbers lie.
- Until this exists, `docs/model-selection.md` stays labelled as a hypothesis.

## Reversal

No. Measuring your own domain is the difference between engineering and guessing.
