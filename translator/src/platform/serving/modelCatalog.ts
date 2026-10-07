/**
 * Model catalog for local serving.
 *
 * This file describes *how to run* the candidate models. It deliberately does
 * NOT pick one: no candidate has been executed on any hardware in this project
 * (see `docs/benchmark-status.md`), so a default would be a guess dressed up as
 * a decision. `MODEL_CATALOG` has no `default` field and `selectDefaultModel`
 * throws rather than returning a guess.
 *
 * The `requirements` block is **published** information from model cards and
 * vendor documentation. It has not been measured on this host and is not a
 * benchmark result. It exists so an operator can tell whether a machine is even
 * a candidate before spending time installing 8 GB of weights.
 */

import { ConfigError } from '../../core/errors';

export type ServingStyle = 'openai' | 'llamacpp' | 'ollama';

export type Quantization = 'q4_k_m' | 'q5_k_m' | 'bf16' | 'int8' | 'fp16';

export interface ModelRequirements {
  /** Published minimum usable VRAM in GB. Unverified on this host. */
  vramGbMinimum: number;
  /** Published VRAM for the catalogued quantization. Unverified on this host. */
  vramGbTypical: number;
  /** Host RAM in GB needed when the model does not fit in VRAM. Unverified. */
  ramGbMinimum: number;
  /** Download size on disk in GB. Unverified. */
  downloadGb: number;
}

export interface ModelSpec {
  /** Stable platform id. Stable because it enters cache keys (ADR 0009). */
  id: string;
  /** Upstream model name, as the serving server knows it. */
  modelId: string;
  /** Pin a revision here. 'UNPINNED' is a warning state, not a normal one. */
  revision: string;
  displayName: string;
  sourceLanguageSupport: string[];
  license: string;
  /** Why a licence matters: non-commercial licences are excluded outright. */
  licenseAllowsCommercialUse: boolean;
  quantization: Quantization;
  servingStyles: ServingStyle[];
  defaultStyle: ServingStyle;
  defaultPort: number;
  requirements: ModelRequirements;
  /** Provenance of the numbers above. Always 'published' here, never 'measured'. */
  requirementsSource: 'published';
  install: string[];
  serve: string[];
  notes: string;
}

const CATALOG_VERSION = '1.0.0';

export const MODEL_CATALOG: readonly ModelSpec[] = Object.freeze([
  {
    id: 'local-tg4',
    modelId: 'translategemma-4b',
    revision: 'UNPINNED',
    displayName: 'TranslateGemma 4B (Q4_K_M)',
    sourceLanguageSupport: ['ja', 'zh', 'ko', 'en', 'ar'],
    license: 'Gemma Terms of Use',
    licenseAllowsCommercialUse: true,
    quantization: 'q4_k_m',
    servingStyles: ['openai', 'ollama'],
    defaultStyle: 'openai',
    defaultPort: 8081,
    requirements: {
      vramGbMinimum: 3,
      vramGbTypical: 3,
      ramGbMinimum: 8,
      downloadGb: 3,
    },
    requirementsSource: 'published',
    install: [
      'llama-server -hf google/translategemma-4b-it-qat-q4_K_M --port 8081 --jinja',
    ],
    serve: [
      'curl -s localhost:8081/health   # llama.cpp exposes /health',
      'curl -s localhost:8081/v1/models',
    ],
    notes:
      'Gemma family requires accepting the Gemma Terms of Use. Instruction-tuned variant only: ' +
      'the base model does not follow the translation prompt.',
  },
  {
    id: 'local-tg12',
    modelId: 'translategemma-12b',
    revision: 'UNPINNED',
    displayName: 'TranslateGemma 12B (Q4_K_M)',
    sourceLanguageSupport: ['ja', 'zh', 'ko', 'en', 'ar'],
    license: 'Gemma Terms of Use',
    licenseAllowsCommercialUse: true,
    quantization: 'q4_k_m',
    servingStyles: ['openai', 'ollama'],
    defaultStyle: 'openai',
    defaultPort: 8082,
    requirements: {
      vramGbMinimum: 8,
      vramGbTypical: 8.1,
      ramGbMinimum: 16,
      downloadGb: 8,
    },
    requirementsSource: 'published',
    install: [
      'llama-server -hf google/translategemma-12b-it-qat-q4_K_M --port 8082 --jinja',
    ],
    serve: [
      'curl -s localhost:8082/health',
      'curl -s localhost:8082/v1/models',
    ],
    notes:
      'Same licence family as the 4B. Published VRAM is roughly triple, so it is the first ' +
      'candidate to fall off a small GPU.',
  },
  {
    id: 'local-madlad3b',
    modelId: 'madlad400-3b-mt',
    revision: 'UNPINNED',
    displayName: 'MADLAD-400 3B MT (Q4_K_M)',
    sourceLanguageSupport: ['ja', 'zh', 'ko', 'en', 'ar'],
    license: 'Apache-2.0',
    licenseAllowsCommercialUse: true,
    quantization: 'q4_k_m',
    servingStyles: ['llamacpp', 'openai'],
    defaultStyle: 'llamacpp',
    defaultPort: 8083,
    requirements: {
      vramGbMinimum: 6,
      vramGbTypical: 6,
      ramGbMinimum: 8,
      downloadGb: 6,
    },
    requirementsSource: 'published',
    install: [
      'huggingface-cli download google/madlad400-3b-mt-GGUF --local-dir ./models/madlad400-3b-mt',
      'llama-server -m ./models/madlad400-3b-mt/*.q4_K_M.gguf --port 8083',
    ],
    serve: [
      'curl -s localhost:8083/health',
      'curl -s localhost:8083/props   # llama.cpp native props, no OpenAI wrapper needed',
    ],
    notes:
      'Apache-2.0 makes this the licence-clean candidate. It is an MT model, so it has no ' +
      'prompt-following: register, honorifics and manga register must come from the glossary and ' +
      'context layers, not from instructions in the prompt.',
  },
]);

export function getModelSpec(id: string): ModelSpec {
  const spec = MODEL_CATALOG.find((m) => m.id === id);
  if (!spec) {
    throw new ConfigError(
      `unknown model "${id}"; catalogued models: ${MODEL_CATALOG.map((m) => m.id).join(', ')}`,
    );
  }
  return spec;
}

export interface SelectModelOptions {
  /** Present only to let the error message be actionable. */
  availableVramGb?: number;
  availableRamGb?: number;
}

/**
 * Filters the catalog by what the host can actually hold.
 *
 * Used by `npm run serving:plan` so an operator sees the candidate list shrink
 * *before* downloading weights, rather than discovering it at load time.
 */
export function candidateModels(options: SelectModelOptions = {}): ModelSpec[] {
  return MODEL_CATALOG.filter((spec) => {
    if (options.availableVramGb !== undefined && spec.requirements.vramGbTypical > options.availableVramGb) {
      return false;
    }
    if (options.availableRamGb !== undefined && spec.requirements.ramGbMinimum > options.availableRamGb) {
      return false;
    }
    return true;
  });
}

/**
 * There is no default. Deliberately.
 *
 * Calling this until a real benchmark exists must fail loudly; a function that
 * returned "the smallest model" would launder an assumption into a decision.
 */
export function selectDefaultModel(): never {
  throw new ConfigError(
    'no default model has been selected: no candidate has been executed and measured on the ' +
      'manga/manhwa evaluation corpus. Run `npm run benchmark` on a GPU host first, then record ' +
      'the decision in docs/model-selection.md.',
  );
}

export const MODEL_CATALOG_VERSION = CATALOG_VERSION;
