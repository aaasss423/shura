# Model selection for the local translation core

Status: **Accepted**. Date: 2026-10. Derived from public sources listed at the
bottom. All numbers below are from those sources; none are from our own
benchmark, because we have not run one yet (see "What we could not verify").

## The decision, first

| Tier | Model | Licence | Role |
| --- | --- | --- | --- |
| **high** | `translategemma:12b` (Q4_K_M, ~8.1 GB) | Gemma Terms of Use | best local quality, default for chapter mode |
| **standard** | `translategemma:4b` (Q4_K_M, ~3 GB) | Gemma Terms of Use | default for interactive single lines — the volume path |
| **fallback / licence-clean** | `google/madlad400-3b-mt` | Apache-2.0 | used if the Gemma terms cannot be accepted, or if the primary is overloaded |
| **BYOK** | DeepL / MyMemory / anything | provider | optional, user-funded |
| **rejected** | NLLB-200, Unbabel Tower | CC-BY-NC-4.0 | **non-commercial — cannot ship** |

One model family, two sizes, for all of en/ja/zh/ko → ar. Rationale in
"Why one family".

## Comparison

Licence column is the hard filter. A model you cannot legally ship is not a
candidate, no matter how good it is.

| Model | Languages | Arabic | Params | RAM / VRAM | Quantisation | Speed (sense) | Licence | Commercial | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **TranslateGemma 4B** | ja, zh, ko, en → ar (ar_EG, ar_SA variants) | benchmarked en→ar | 4B | ~3 GB VRAM (Q4) | Q4/Q5/Q8/FP16 | fastest tier | Gemma ToU | yes, with use restrictions | **chosen (volume path)** |
| **TranslateGemma 12B** | same | benchmarked en→ar | 12B | ~8.1 GB VRAM (Q4) | Q4/Q5/Q8/FP16 | ~3× slower than 4B | Gemma ToU | yes, with use restrictions | **chosen (quality path)** |
| TranslateGemma 27B | same | benchmarked en→ar | 27B | ~17 GB VRAM | Q4/Q5/Q8/FP16 | slowest | Gemma ToU | yes, with use restrictions | rejected: cost/benefit, 4B already beats Gemma3-27B on en→ar |
| **Qwen3-30B-A3B** | 100+ incl. all four sources | general, not MT-specialised | 30B MoE, 3B active | ~18.6–21.4 GB (Q4_K_M) | Q2–Q8, FP16 | fast for its size (MoE) | **Apache-2.0** | yes | fallback candidate — fully permissive, but a general LLM, not an MT model |
| Qwen3-8B | 100+ | general | 8B | ~5 GB (Q4) | Q4–Q8 | fast | Apache-2.0 | yes | cheap permissive option; still not MT-tuned |
| **MADLAD-400-3B-MT** | 419 incl. ar | general domain | 3B T5 | ~6 GB (fp16) | AWQ/INT8/FP16 | moderate | **Apache-2.0** | yes | **chosen fallback** — licence-clean |
| MADLAD-400-10B-MT | 419 | general domain | 10B T5 | ~20 GB | same | slow | Apache-2.0 | yes | rejected: heavy, no evidence it beats TG on our pairs |
| Opus-MT | pair-specific, 1500+ pairs | **many pairs have no Arabic** | ~75M–300M | ~0.5–1.5 GB | many int8 variants | very fast, CPU-viable | CC-BY-4.0 | yes, with attribution | rejected as primary: per-pair models, weak/zero Arabic coverage |
| **NLLB-200** | 200 incl. ar | general | 600M–3.3B | ~3 GB (600M, fp16) | fp16/int8 | fast | **CC-BY-NC-4.0** | **NO** | **rejected — licence** |
| Unbabel Tower | 10 | general | 7B/13B | ~16–28 GB | fp16/int8 | moderate | **CC-BY-NC-4.0** | **NO** | **rejected — licence** |
| Argos / LibreTranslate models | varies | partial | small | <1 GB | packed int8 | fast on CPU | MIT code, model licences vary | mixed | rejected as core; acceptable as an emergency CPU path |
| Bergamot / Marian | limited | poor | small | <1 GB | int8 | very fast | varies | mixed | rejected: Arabic far too weak for our priority |

## The numbers that decided it

From the TranslateGemma technical report (arXiv 2601.09012), MetricX on WMT24++
(lower is better), `en→ar_EG`:

| Model | MetricX en→ar_EG |
| --- | --- |
| TranslateGemma 4B | **2.54** |
| TranslateGemma 12B | 2.78 |
| TranslateGemma 27B | 3.57 |
| Gemma 3 4B (baseline) | 3.32 |
| Gemma 3 12B (baseline) | 3.70 |
| Gemma 3 27B (baseline) | 4.60 |

A 4B MT-specialised model beats a 27B general model on Arabic by a wide margin
(2.54 vs 4.60). That is the single most useful data point in the whole
comparison: it means translation-specific fine-tuning buys far more than
parameter count, and it means our cheap tier does not have to be bad.

Human evaluation (same report, WMT25, 10 pairs) confirms the trend, and the 12B
model beats the larger 27B baseline overall.

## Why one family instead of per-language models

We were asked to consider model routing per language. The data says no:

1. **No published benchmark exists for our priority pairs.** WMT24++ is
   `en → xx` for 55 languages. There is no `ja→ar`, `zh→ar` or `ko→ar` set. So
   "TranslateGemma is better for Japanese" is currently an *unmeasured claim*.
   Splitting across three models before measuring would be inventing a result.
2. **Cross-lingual consistency matters more than per-pair peak.** A single
   family's output style is consistent across languages, so the same character
   sounds the same in a Japanese panel and a Korean one. Mixing families breaks
   that, and manga readers notice immediately.
3. **One deployment, one operational story.** Two model families doubles the
   loading, quantisation, memory and canary surface for a benefit nobody has
   demonstrated.
4. **Routing stays available.** The tier router (high / standard / fallback /
   BYOK) is implemented now, and per-language routing can be enabled later
   against *our* measurements, with no re-architecture.

## Why MT-specialised rather than a general LLM

A general instruction model told to "translate this" produces more fluent but
more variable output: it paraphrases, drops honorifics inconsistently, invents
gender, and cannot be pinned to a glossary. An MT-tuned seq2seq or MT-tuned LLM
is trained to be faithful. For manga — where a wrong honorific or a dropped
onomatopoeia is a visible error in a speech bubble — faithfulness wins over
elegance. Post-processing and glossary enforcement are ours to add either way;
faithfulness is much cheaper to build on.

## Manga specifics — honest assessment

**None of these models is trained or benchmarked on manga dialogue.** That is a
real gap, not something to paper over. What we can say:

- Manga dialogue is short, informal, second-person, present tense, heavy on
  sound words and ellipses. That distribution is closer to web/subtitle text
  than to literary prose, which is where MT models are strongest.
- The things that break are recoverable *by us*, not by the model: character
  names (our glossary/character layer), honorifics (glossary), sound effects
  (knowledge base + pattern rules), series terminology (knowledge base).
- The plan is a **domain LoRA** later, trained only on approved translations
  (see ADR 0007). That is the intended route to real manga quality, and it is
  only safe because approved data is curated first.

## Hardware requirements

Per worker, at Q4 quantisation, 8k context:

| Tier | VRAM | System RAM | Notes |
| --- | --- | --- | --- |
| TranslateGemma 4B | ~3 GB | ~6 GB | 1 worker per 8 GB GPU; CPU-only is viable but slow |
| TranslateGemma 12B | ~8.1 GB | ~12 GB | 1 worker per 16 GB GPU |
| MADLAD-400-3B-MT | ~6 GB | ~9 GB | fallback; also the CPU-runnable option |
| Qwen3-30B-A3B (Q4_K_M) | ~18.6–21.4 GB | ~24 GB | needs a 24 GB GPU; not the default |

Batch size drives throughput far more than model size at this scale: a 4B model
with batch 32 beats a 27B model with batch 1 on aggregate tokens/second. The job
queue therefore batches at the worker, not at the API.

## What we could NOT verify

- **No `ja→ar`, `zh→ar` or `ko→ar` public benchmark.** Every quality claim above
  is for `en→ar` (plus `ja→en`, `en→zh`, `en→ko`). Extrapolating to our pairs is
  an assumption.
- **We have not run any of these models.** No GPU is available in the
  development environment. No latency or throughput number in this document is
  ours.
- **Gemma Terms of Use has not been reviewed by a lawyer.** It permits commercial
  use with obligations (prohibited-use policy, passing restrictions to users,
  notice requirements). It is *open weights*, not OSI open source. This is the
  single biggest legal unknown in the plan.
- **One contrary data point worth recording:** on `ja→en`, TranslateGemma 27B
  scores 13.4 and 12B scores 15.7, while the *baseline* Gemma 3 27B scores
  11.6. Translation tuning is not uniformly better in every direction. This is
  exactly why our own eval set exists.

## Consequences for the architecture

1. `LocalEngine` talks to a local inference server (llama.cpp / TGI / vLLM) over
   HTTP, so the serving stack is swappable and the platform stays
   dependency-free in Node.
2. The engine reports its `modelId` and `modelVersion`; **both go into the cache
   key**, so swapping models cannot serve stale translations.
3. Tier routing is a config concern (`MODEL_ROUTING`), not code.
4. `google/madlad400-3b-mt` is registered as a real second engine so the
   Apache-2.0 path is exercised, not theoretical.
5. An **evaluation harness over a manga corpus** is a first-class deliverable
   (ADR 0006). Until it exists, no model claim is a product claim.

## Sources

- TranslateGemma technical report — arXiv 2601.09012 (blog.google, 2026-01-16)
- WMT24++ — arXiv 2502.12404 / ACL 2025 Findings (55 `en→xx` pairs)
- MADLAD-400 — arXiv 2309.04682; `google/madlad400-3b-mt` model card (Apache-2.0)
- NLLB-200 — `facebook/nllb-200-distilled-600M` model card (CC-BY-NC-4.0)
- Qwen3-30B-A3B — Qwen model card (Apache-2.0), ggml GGUF quant sizes
- Licence cross-check — Smartling open-source translation LLM survey (Sep 2026)