# ADR 0002 — TranslateGemma family, tiered, with a MADLAD fallback

Status: Accepted · 2026-10

Full comparison, data and caveats: [`../model-selection.md`](../model-selection.md).
Summary:

## Decision

Three registered local engines, routed by tier:

| Engine id | Model | Licence | Tier |
| --- | --- | --- | --- |
| `local-tg4` | TranslateGemma 4B (Q4) | Gemma ToU | standard (volume) |
| `local-tg12` | TranslateGemma 12B (Q4) | Gemma ToU | high (chapter, priority) |
| `local-madlad3b` | google/madlad400-3b-mt | **Apache-2.0** | fallback, and the licence-clean path |

Rejected: **NLLB-200** and **Unbabel Tower** (CC-BY-NC-4.0, non-commercial),
MADLAD-400-10B (heavy, no evidence of gain), Opus-MT as primary (per-pair models,
poor Arabic coverage), Argos/Bergamot/Marian (Arabic too weak).

## Why

- TranslateGemma 4B scores MetricX **2.54** on `en→ar_EG` against **4.60** for
  Gemma 3 27B — translation-specific tuning beats 7× the parameters, so the cheap
  tier does not have to be bad.
- Apache-2.0 fallback means the platform has a legally clean path even if the
  Gemma terms turn out to be unacceptable, so we are not betting the product on
  one licence review.
- One family keeps cross-lingual voice consistent, which matters for a reader
  following one cast across chapters.

## Open risk, recorded not hidden

The Gemma Terms of Use have **not** been reviewed by a lawyer. They are open
weights with use restrictions, not OSI open source. If the review fails,
`local-madlad3b` becomes the primary and quality drops — that is the single
largest quality risk in the plan, and it is a licence risk, not a technical one.

## Also recorded

No `ja→ar`, `zh→ar` or `ko→ar` benchmark exists publicly. All quality data here
is `en→ar`. Per-language routing is therefore **deliberately not enabled** until
ADR 0006's eval set measures it.