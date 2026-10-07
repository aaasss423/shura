/**
 * HTTP transport for a local inference server.
 *
 * Talks the three wire formats the catalogued servers speak, and reports what the
 * server said about *itself* so identity checks have something real to compare
 * against. Nothing here assumes the server is up: every call reports transport
 * failure as a typed error rather than letting a `fetch` rejection escape.
 */

import { CancelledError, EngineError, EngineUnavailableError, TimeoutError, isTranslationError } from '../../core/errors';
import type { ServingStyle } from './modelCatalog';

export interface HttpClientOptions {
  endpoint: string;
  servingStyle: ServingStyle;
  /** Model name sent to an OpenAI-compatible server. */
  modelId: string;
  timeoutMs?: number;
  temperature?: number;
  maxTokens?: number;
  promptTemplate?: string;
  fetchImpl?: typeof fetch;
}

export interface SingleRequest {
  text: string;
  signal?: AbortSignal;
  timeoutMs?: number;
  /** Extra instruction appended after the text, e.g. glossary guidance. */
  instruction?: string;
}

export interface RawResult {
  text: string;
  elapsedMs: number;
  raw: unknown;
  /** Runtime-reported decode rate, when the server provided one. */
  timings: RuntimeTimings;
}

export interface ServerSelfReport {
  /** Model name the server claims to have loaded. */
  servedAs?: string;
  /** Server-reported context window, when exposed. */
  contextLength?: number;
  /** True when the server answered a readiness probe. */
  reachable: boolean;
  detail: string;
}

const DEFAULT_PROMPT = 'Translate the following {source} text into natural Arabic. Preserve tone and register. Output only the translation.\n\n{text}';

export class InferenceHttpClient {
  private readonly endpoint: string;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;
  private readonly promptTemplate: string;

  constructor(private readonly options: HttpClientOptions) {
    this.endpoint = options.endpoint.replace(/\/+$/, '');
    this.timeoutMs = options.timeoutMs ?? 60000;
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.promptTemplate = options.promptTemplate ?? DEFAULT_PROMPT;
  }

  get style(): ServingStyle {
    return this.options.servingStyle;
  }

  buildPrompt(text: string, sourceName: string, instruction?: string): string {
    const base = this.promptTemplate
      .replace('{source}', sourceName)
      .replace('{target}', 'Arabic')
      .replace('{text}', text);
    return instruction ? `${base}\n\n${instruction}` : base;
  }

  /**
   * One request.
   *
   * Timeout and cancellation are both enforced here rather than trusted to the
   * transport: `fetch` alone gives neither, and an inference request that ignores
   * them will hold a VRAM slot forever.
   */
  async complete(request: SingleRequest, sourceName: string): Promise<RawResult> {
    const started = Date.now();
    const body = this.buildBody([this.buildPrompt(request.text, sourceName, request.instruction)]);
    const json = await this.send(body, request.signal, request.timeoutMs ?? this.timeoutMs);
    return {
      text: this.extractText(json),
      elapsedMs: Date.now() - started,
      raw: json,
      timings: extractTimings(json),
    };
  }

  /**
   * Many prompts, one request.
   *
   * Only the OpenAI-compatible `/chat/completions` endpoint takes an array of
   * messages, so llama.cpp's `/completion` is fanned out into concurrent single
   * calls. That fan-out is bounded by the scheduler's semaphore, so this is not an
   * unbounded burst.
   */
  async completeBatch(
    requests: Array<{ text: string; instruction?: string; sourceLabel?: string }>,
    fallbackSourceLabel: string,
    signal?: AbortSignal,
  ): Promise<Array<{ text: string; raw: unknown; timings: RuntimeTimings }>> {
    if (requests.length === 1) {
      const one = await this.complete(requests[0]!, requests[0]?.sourceLabel ?? fallbackSourceLabel);
      return [{ text: one.text, raw: one.raw, timings: one.timings }];
    }

    if (this.options.servingStyle === 'openai') {
      const body = this.buildBody(
        requests.map((r) => this.buildPrompt(r.text, r.sourceLabel ?? fallbackSourceLabel, r.instruction)),
      );
      const json = await this.send(body, signal, this.timeoutMs);
      const timings = extractTimings(json);
      return extractBatch(json, requests.length).map((r) => ({ ...r, timings }));
    }

    return Promise.all(
      requests.map(async (r) => {
        const one = await this.complete(r, r.sourceLabel ?? fallbackSourceLabel);
        return { text: one.text, raw: one.raw, timings: one.timings };
      }),
    );
  }

  /**
   * Readiness probe.
   *
   * A TCP-connection failure is reported as unreachable, never as healthy. The
   * distinction is the whole point: an unreachable server must not pass as ready.
   */
  async probe(timeoutMs = 3000): Promise<ServerSelfReport> {
    const started = Date.now();
    const path = this.options.servingStyle === 'ollama' ? '/api/tags' : '/health';
    try {
      const response = await this.fetchImpl(`${this.endpoint}${path}`, {
        method: 'GET',
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (response.status >= 500) {
        return {
          reachable: false,
          detail: `server responded ${response.status}: it is up but not serving`,
          ...(await selfReport(this.options.servingStyle, response).then((r) => r)),
        };
      }
      const report = await selfReport(this.options.servingStyle, response);
      return {
        reachable: true,
        detail: `reachable in ${Date.now() - started}ms (${path} -> ${response.status})`,
        ...report,
      };
    } catch (error) {
      return {
        reachable: false,
        detail: `server unreachable at ${this.endpoint}${path}: ${
          error instanceof Error ? error.message : String(error)
        }`,
      };
    }
  }

  /** Asks the server which model it loaded, so identity can be verified. */
  async describe(): Promise<ServerSelfReport> {
    const path = this.options.servingStyle === 'ollama' ? '/api/tags' : '/props';
    try {
      const response = await this.fetchImpl(`${this.endpoint}${path}`, {
        method: 'GET',
        signal: AbortSignal.timeout(3000),
      });
      const report = await selfReport(this.options.servingStyle, response);
      return { ...report, reachable: true, detail: `identity read from ${path}` };
    } catch (error) {
      return {
        reachable: false,
        detail: `could not read server identity: ${error instanceof Error ? error.message : String(error)}`,
      };
    }
  }

  private buildBody(prompts: string[]): Record<string, unknown> {
    const temperature = this.options.temperature ?? 0;
    const maxTokens = this.options.maxTokens ?? 512;
    if (this.options.servingStyle === 'llamacpp') {
      return { prompt: prompts[0], stream: false, temperature, n_predict: maxTokens };
    }
    if (this.options.servingStyle === 'ollama') {
      return { model: this.options.modelId, prompt: prompts[0], stream: false, options: { temperature } };
    }
    return {
      model: this.options.modelId,
      messages: prompts.map((content) => ({ role: 'user', content })),
      temperature,
      max_tokens: maxTokens,
      stream: false,
    };
  }

  private async send(body: Record<string, unknown>, signal: AbortSignal | undefined, timeoutMs: number): Promise<unknown> {
    const path =
      this.options.servingStyle === 'llamacpp'
        ? '/completion'
        : this.options.servingStyle === 'ollama'
          ? '/api/generate'
          : '/chat/completions';

    const controller = new AbortController();
    const onAbort = (): void => controller.abort();
    signal?.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await this.fetchImpl(`${this.endpoint}${path}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!response.ok) {
        const detail = await response.text().catch(() => '');
        if (response.status === 503 || response.status === 502) {
          throw new EngineUnavailableError(
            `inference server at ${this.endpoint} is not ready: ${response.status}`,
            { details: { status: response.status } },
          );
        }
        throw new EngineError(
          `inference server responded ${response.status}: ${detail.slice(0, 200)}`,
          { retryable: response.status >= 500 || response.status === 429 },
        );
      }
      return await response.json();
    } catch (error) {
      if (signal?.aborted) {
        throw new CancelledError('inference request was cancelled by the caller');
      }
      if (controller.signal.aborted) {
        throw new TimeoutError(`inference request exceeded ${timeoutMs}ms`, {
          details: { endpoint: this.endpoint },
        });
      }
      if (isTranslationError(error)) {
        throw error;
      }
      // An abort caused by the caller's signal is a cancellation, not a transport
      // failure. Surfacing it as a generic EngineError would hide it from the
      // cancellation accounting and let the caller retry work it already gave up on.
      if (signal?.aborted) {
        throw new CancelledError('inference request was cancelled by the caller');
      }
      throw new EngineError(
            `inference request to ${this.endpoint} failed: ${error instanceof Error ? error.message : String(error)}`,
            { retryable: true },
          );
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  private extractText(json: unknown): string {
    const text = extractBatch(json, 1)[0]?.text ?? '';
    if (text.trim().length === 0) {
      throw new EngineError('inference server returned an empty translation', { retryable: true });
    }
    return text;
  }
}

async function selfReport(style: ServingStyle, response: Response): Promise<Partial<ServerSelfReport>> {
  try {
    const json = (await response.json()) as Record<string, unknown>;
    if (style === 'ollama') {
      const models = (json.models as Array<Record<string, unknown>> | undefined) ?? [];
      return { servedAs: models[0]?.name as string | undefined };
    }
    // llama.cpp /props
    const meta = json.default_generation_settings as Record<string, unknown> | undefined;
    const loaded = (json.model_path as string | undefined) ?? (json.model as string | undefined);
    return {
      ...(typeof loaded === 'string' ? { servedAs: normalizeServedModel(loaded) } : {}),
      ...(typeof meta?.n_ctx === 'number' ? { contextLength: Number(meta.n_ctx) } : {}),
    };
  } catch {
    return {};
  }
}

/**
 * Runtime-reported decode timings.
 *
 * llama.cpp returns a `timings` object with `predicted_n` and
 * `predicted_per_second`. When it is present it is the runtime's own counter, so it
 * is recorded as measured. When it is absent the answer is "not measured".
 *
 * Deliberately *not* derived from character counts: tokens/sec computed from output
 * length would be a guess dressed as a measurement, and it is exactly the kind of
 * number that later gets quoted as if the runtime had produced it.
 */
export interface RuntimeTimings {
  source: 'runtime' | 'absent';
  tokensPerSecond?: number;
  predictedTokens?: number;
  promptTokens?: number;
  note: string;
}

export function extractTimings(json: unknown): RuntimeTimings {
  const record = (json ?? {}) as Record<string, unknown>;
  const timings = record.timings as Record<string, unknown> | undefined;
  if (!timings || typeof timings !== 'object') {
    return {
      source: 'absent',
      note:
        'the serving runtime returned no timings block, so tokens/sec is not measured. ' +
        'It is deliberately not estimated from output length.',
    };
  }
  const perSecond = Number(timings.predicted_per_second);
  const predicted = Number(timings.predicted_n);
  const prompt = Number(timings.prompt_n);
  if (!Number.isFinite(perSecond) || perSecond <= 0) {
    return {
      source: 'absent',
      note: 'the runtime returned a timings block without a usable predicted_per_second value',
    };
  }
  return {
    source: 'runtime',
    tokensPerSecond: Math.round(perSecond * 100) / 100,
    ...(Number.isFinite(predicted) ? { predictedTokens: predicted } : {}),
    ...(Number.isFinite(prompt) ? { promptTokens: prompt } : {}),
    note: 'tokens/sec reported by the serving runtime',
  };
}

/**
 * Normalizes a served-model name for comparison.
 *
 * A llama.cpp server reports a file path with a quantisation suffix
 * (`translategemma-4b-it-qat-q4_K_M.gguf`); an OpenAI-compatible server reports
 * the bare model id. Comparing those literally would make every real server look
 * like a mismatch, so the comparison runs on the stem.
 */
export function normalizeServedModel(name: string): string {
  const base = name.split('/').pop() ?? name;
  return base
    .replace(/\.(gguf|safetensors|bin|pt|onnx)$/i, '')
    .replace(/[-_.]?(q\d+_k_[ms]|q\d+_\d+|int8|fp16|bf16|f16)$/i, '')
    .toLowerCase();
}

/** Pulls out one text per prompt, tolerating both single and batch shapes. */
export function extractBatch(json: unknown, expected: number): Array<{ text: string; raw: unknown }> {
  const record = json as Record<string, unknown> | null;
  if (!record || typeof record !== 'object') {
    throw new EngineError('inference server returned a non-object response', { retryable: true });
  }

  const choices = record.choices as Array<Record<string, unknown>> | undefined;
  if (Array.isArray(choices)) {
    const texts = choices.map((choice) => {
      const message = choice.message as Record<string, unknown> | undefined;
      const content = message?.content ?? choice.text;
      return typeof content === 'string' ? content : '';
    });
    return pad(texts, expected, json);
  }

  // llama.cpp: `content` on /completion, `generated_text` on legacy /completions.
  const content = (record.content ?? record.response ?? record.generated_text) as unknown;
  if (typeof content === 'string') {
    return pad([content], expected, json);
  }
  throw new EngineError('inference server response had no text field', { retryable: true });
}

/**
 * Pads or truncates to `expected` so a short response cannot silently shift every
 * later item's result onto the wrong text.
 */
function pad(texts: string[], expected: number, raw: unknown): Array<{ text: string; raw: unknown }> {
  const out: Array<{ text: string; raw: unknown }> = [];
  for (let i = 0; i < expected; i += 1) {
    const text = texts[i];
    if (text === undefined) {
      throw new EngineError(
        `inference server returned ${texts.length} results for ${expected} prompts; results cannot be matched safely`,
        { retryable: true },
      );
    }
    out.push({ text, raw });
  }
  return out;
}
