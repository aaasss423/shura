/**
 * Mock inference server.
 *
 * **This is a mock. It does not run a model and produces no translation quality.**
 * It exists to exercise the serving protocol end to end without a GPU: readiness
 * polling, batching, identity reporting, timeouts, cancellation and shutdown.
 *
 * Its output is prefixed `MOCK` so that any transcript, snapshot or log produced by
 * a test using it is self-evidently not a model result.
 */

import * as http from 'node:http';
import type { AddressInfo } from 'node:net';

export interface MockServerOptions {
  /** Model name reported by /props and /v1/models. */
  servedAs?: string;
  servingStyle?: 'openai' | 'llamacpp';
  /** Milliseconds of simulated decode time per item. */
  latencyMs?: number;
  /** Fails readiness for this many probes, then becomes ready. */
  warmupProbes?: number;
  /** Return HTTP 503 for this many requests before succeeding. */
  unavailableResponses?: number;
  /** Forces an HTTP error status. */
  failWithStatus?: number;
  contextLength?: number;
  /**
   * Emit a llama.cpp-style `timings` block, so the runtime-reported tokens/sec path
   * can be exercised. Off by default so absence is also covered.
   */
  reportTimings?: boolean;
  tokensPerSecond?: number;
}

export interface MockServer {
  readonly endpoint: string;
  /** Every prompt the server received, in order. */
  readonly prompts: string[];
  readonly requestCount: number;
  readonly maxBatchObserved: number;
  close(): Promise<void>;
}

export async function startMockInferenceServer(options: MockServerOptions = {}): Promise<MockServer> {
  const style = options.servingStyle ?? 'openai';
  const latencyMs = options.latencyMs ?? 0;
  let servedAs = options.servedAs ?? 'mock-model-q4';
  let probes = 0;
  let unavailable = 0;
  const prompts: string[] = [];
  let requestCount = 0;
  let maxBatchObserved = 0;

  const server = http.createServer((req, res) => {
    requestCount += 1;
    const url = req.url ?? '';

    if (url === '/health' || url === '/props' || url === '/v1/models') {
      probes += 1;
      if (probes <= (options.warmupProbes ?? 0)) {
        res.writeHead(503, { 'content-type': 'text/plain' });
        res.end('loading model');
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      if (url === '/health') {
        res.end(JSON.stringify({ status: 'ok' }));
      } else if (url === '/v1/models') {
        res.end(JSON.stringify({ data: [{ id: servedAs }] }));
      } else {
        res.end(
          JSON.stringify({
            model_path: `/models/${servedAs}.gguf`,
            default_generation_settings: { n_ctx: options.contextLength ?? 8192 },
          }),
        );
      }
      return;
    }

    if (options.failWithStatus !== undefined) {
      res.writeHead(options.failWithStatus, { 'content-type': 'text/plain' });
      res.end('mock failure');
      return;
    }
    if (unavailable < (options.unavailableResponses ?? 0)) {
      unavailable += 1;
      res.writeHead(503, { 'content-type': 'text/plain' });
      res.end('mock: not ready');
      return;
    }

    let body = '';
    req.on('data', (chunk) => {
      body += String(chunk);
    });
    req.on('end', () => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      const incoming = style === 'openai' ? ((parsed.messages as Array<{ content: string }>) ?? []) : [{ content: String(parsed.prompt ?? '') }];
      maxBatchObserved = Math.max(maxBatchObserved, incoming.length);
      for (const message of incoming) {
        prompts.push(message.content ?? '');
      }

      const finish = (): void => {
        const payload = mockOutput(incoming.map((m) => m.content ?? ''), style, options);
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(payload);
      };
      if (latencyMs > 0) {
        setTimeout(finish, latencyMs * incoming.length);
      } else {
        finish();
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as AddressInfo;

  return {
    endpoint: `http://127.0.0.1:${address.port}`,
    prompts,
    get requestCount() {
      return requestCount;
    },
    get maxBatchObserved() {
      return maxBatchObserved;
    },
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.();
        server.close(() => resolve());
      }),
    get servedAs() {
      return servedAs;
    },
    set servedAs(value: string) {
      servedAs = value;
    },
  } as MockServer & { servedAs: string };
}

/**
 * Echoes the last line of each prompt, which is the item text.
 *
 * Echoing the prompt head would collapse every item to the same string — the
 * instructions are shared — and a batching test could no longer tell a correctly
 * matched response from a shifted one.
 */
function mockOutput(prompts: string[], style: 'openai' | 'llamacpp', options: MockServerOptions = {}): string {
  const contents = prompts.map((p) => `MOCK[${p.split('\n').filter((l) => l.trim().length > 0).pop() ?? p}]`);
  const timings = options.reportTimings
    ? {
        prompt_n: 24,
        prompt_ms: 40,
        prompt_per_second: 600,
        predicted_n: 128,
        predicted_ms: 640,
        predicted_per_second: options.tokensPerSecond ?? 200,
      }
    : undefined;

  if (style === 'openai') {
    return JSON.stringify({
      choices: contents.map((content) => ({ message: { role: 'assistant', content } })),
      ...(timings ? { timings } : {}),
    });
  }
  return JSON.stringify({
    content: contents.join('\n'),
    ...(timings ? { timings } : {}),
  });
}

/**
 * Fake child process for the supervisor.
 *
 * Never spawns anything: readiness and exit are scripted, so the supervisor's
 * lifecycle can be tested without a runtime installed.
 */
export function fakeProcess(options: {
  pid?: number;
  /** Resolves `exited` after this many ms. Omit for a process that never exits. */
  exitAfterMs?: number;
  exitCode?: number;
  onSpawn?: () => void;
  onKill?: (signal: NodeJS.Signals) => void;
} = {}) {
  let resolveExit: ((code: number | null) => void) | undefined;
  const exited = new Promise<number | null>((resolve) => {
    resolveExit = resolve;
  });
  let timer: NodeJS.Timeout | undefined;

  return {
    handle: {
      pid: options.pid ?? 4242,
      exited,
      kill(signal: NodeJS.Signals) {
        options.onKill?.(signal);
        if (signal === 'SIGKILL') {
          if (timer) {
            clearTimeout(timer);
          }
          resolveExit?.(null);
          return true;
        }
        return true;
      },
    },
    /** Fires the scripted exit. */
    exit(code = options.exitCode ?? 0): void {
      resolveExit?.(code);
    },
    dispose(): void {
      if (timer) {
        clearTimeout(timer);
      }
    },
    /** Resolves `exited` after a delay, simulating a process that exits on its own. */
    scheduleExit(): void {
      timer = setTimeout(() => resolveExit?.(options.exitCode ?? 0), options.exitAfterMs ?? 10);
    },
    spawnCalls: 0 as number,
  };
}
