# Model selection — decision record

**Status: UNDECIDED.** No candidate has been executed. No primary, no fallback, no
language routing. `selectDefaultModel()` in `src/platform/serving/modelCatalog.ts`
throws on purpose, and a test asserts it keeps throwing.

This file is the place a decision gets recorded. It cannot be filled in honestly
until `npm run benchmark` has produced real numbers on a GPU host.

---

## How to fill this in

1. `npm run preflight` — must report ready for each candidate.
2. `npm run serve -- --model <id> --revision <sha>` in one terminal per candidate,
   or start the servers directly per `docs/model-serving-runbook.md`.
3. `npm run benchmark -- --engines local-tg4 --split test --revision <sha> \
   --model-path <path/to/weights.gguf> --repeat 3 --json bench-tg4.json`
   Repeat for each candidate **at identical `--concurrency`**, or the comparison is
   not a comparison.
4. Have a fluent Arabic reviewer fill `eval/judgements-test.json` from
   `eval/judgements-test.template.json`.
5. Copy the measured numbers into the tables below, then decide.

Paste the report header verbatim. Do not retype it — the point of the provenance
line is that it is machine-generated.

---

## Required measurements

Anything without a reliable measurement stays **NOT MEASURED**. Nothing may be
inferred from character counts, wall-clock estimates or any proxy.

| Measurement | Value | Source |
| --- | --- | --- |
| weights SHA-256 | | `--model-path` |
| revision | | `--revision` |
| runtime build | | provenance line |
| GPU / VRAM / driver | | provenance line |
| warm-up time | | report |
| quality: overall chrF / BLEU | | report |
| quality per pair (4 pairs) | | report |
| quality per category (12) | | report |
| human review coverage | | report `human:` line |
| p50 / p95 / p99 latency | | report |
| throughput | | report |
| batch behaviour | | report `serving:` line |
| queue wait (mean batch wait) | | report |
| tokens/sec | runtime counter **only** | report |
| peak VRAM | `nvidia-smi` during run | report |
| peak host RAM | process RSS | report |
| failures | | report |
| cancellations | | report |
| stability (agreement, chrF spread) | `--repeat 3` | report |

---

## Candidate A — TranslateGemma 4B

| | measured |
| --- | --- |
| weights digest | NOT MEASURED |
| quality overall | NOT MEASURED |
| ja→ar / zh→ar / ko→ar / en→ar | NOT MEASURED |
| p50 / p95 / p99 | NOT MEASURED |
| throughput | NOT MEASURED |
| tokens/sec | NOT MEASURED |
| peak VRAM | NOT MEASURED |
| stability (agreement) | NOT MEASURED |
| licence | Gemma Terms of Use — commercial use permitted |

## Candidate B — TranslateGemma 12B

| | measured |
| --- | --- |
| weights digest | NOT MEASURED |
| quality overall | NOT MEASURED |
| ja→ar / zh→ar / ko→ar / en→ar | NOT MEASURED |
| p50 / p95 / p99 | NOT MEASURED |
| throughput | NOT MEASURED |
| tokens/sec | NOT MEASURED |
| peak VRAM | NOT MEASURED |
| stability (agreement) | NOT MEASURED |
| licence | Gemma Terms of Use — commercial use permitted |

## Candidate C — MADLAD-400 3B MT

| | measured |
| --- | --- |
| weights digest | NOT MEASURED |
| quality overall | NOT MEASURED |
| ja→ar / zh→ar / ko→ar / en→ar | NOT MEASURED |
| p50 / p95 / p99 | NOT MEASURED |
| throughput | NOT MEASURED |
| tokens/sec | NOT MEASURED |
| peak VRAM | NOT MEASURED |
| stability (agreement) | NOT MEASURED |
| licence | Apache-2.0 |

---

## Hypotheses to test, not conclusions

These are the reasons each candidate is on the shortlist. None is evidence.

- **TG4B** is the latency floor and the smallest VRAM cost. Hypothesis: adequate
  manga/manhwa quality at the lowest resource price.
- **TG12B** costs roughly 2.7× the VRAM. Hypothesis: a chrF gain large enough to
  justify it on the hard categories (idiom, honorific, context_dependent).
- **MADLAD** is the licence-clean option. Hypothesis: an MT model without
  prompt-following will trail the instruction-tuned Gemma variants on register and
  honorifics, and win nothing on quality — in which case it is the fallback, not the
  primary. It must also be measured as an MT model: register and honorifics come
  from the glossary and context layers, not from the prompt.

## Decision criteria, in order

1. **Quality on the manga/manhwa categories** — dialogue, slang, idiom, honorific,
   name, context_dependent, sound_effect. A model that wins overall while losing
   these is not the primary for this product.
2. **Human review**, on the categories the decision turns on.
3. **Stability** — a model that varies run to run cannot be a baseline.
4. **Latency and throughput** at the stated concurrency.
5. **VRAM / RAM** against the target deployment hardware.
6. **Licence** compatible with the intended distribution.

## Outputs to record

- **Primary:** UNDECIDED
- **Fallback:** UNDECIDED
- **Language routing required?:** UNDECIDED — only if per-pair results differ enough
  to matter and a second model is affordable
- **Is 4B sufficient, or does 12B justify its VRAM?:** UNDECIDED

## Rules for whoever fills this in

- Do not fill a cell with a number that did not come from a report on this corpus.
- Do not compare runs taken at different `--concurrency`, `--batch-size` or
  `--revision`.
- Do not compare across different weights digests and call it the same model.
- Leave a cell as NOT MEASURED when there is no measurement. A blank is honest; a
  guess is not.
- Record the exact commands used, so the run can be repeated.