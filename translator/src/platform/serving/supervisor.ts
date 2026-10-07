/**
 * Model server supervision.
 *
 * Owns the "model loading" half of serving: starting an inference server as a
 * child process, waiting for it to become ready, and shutting it down without
 * leaving a GPU handle or an orphan behind.
 *
 * The process is injected, never spawned directly. That is what makes this
 * testable without a GPU and without a runtime — and it is also why no test in
 * this file can accidentally claim a model ran.
 */

import { spawn as nodeSpawn, type ChildProcess } from 'node:child_process';

import type { CancellationToken } from '../../core/cancellation';
import type { ModelSpec } from './modelCatalog';

export interface ProcessHandle {
  readonly pid?: number;
  readonly exited: Promise<number | null>;
  kill(signal: NodeJS.Signals): boolean;
}

export type SpawnProcess = (command: string, args: string[], options: { cwd?: string; env?: NodeJS.ProcessEnv }) => ProcessHandle;

export interface SupervisorOptions {
  spec: ModelSpec;
  endpoint: string;
  /** Executable, e.g. `llama-server`. */
  command: string;
  args: string[];
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  spawnImpl?: SpawnProcess;
  /** How long a load may take. Model loading is slow; this must not be tight. */
  readinessTimeoutMs?: number;
  pollIntervalMs?: number;
  /** Grace period after SIGTERM before SIGKILL. */
  shutdownGraceMs?: number;
  onLog?: (message: string) => void;
}

export type SupervisorState = 'stopped' | 'starting' | 'ready' | 'stopping';

export interface SupervisorResult {
  state: SupervisorState;
  pid?: number;
  /** Wall clock of the readiness wait on this host. Undefined until ready. */
  readinessMs?: number;
  detail: string;
}

/**
 * Starts and stops one inference server.
 *
 * `isReady` is supplied by the caller because readiness is protocol, not process
 * state: a llama.cpp server binds the port several seconds before the weights are
 * resident, and a supervisor that trusts the port would report ready too early.
 */
export class ModelServerSupervisor {
  private readonly spawnImpl: SpawnProcess;
  private readonly readinessTimeoutMs: number;
  private readonly pollIntervalMs: number;
  private readonly shutdownGraceMs: number;

  private process: ProcessHandle | undefined;
  private supervisorState: SupervisorState = 'stopped';
  private lastReadinessMs: number | undefined;

  constructor(
    private readonly options: SupervisorOptions,
    private readonly isReady: (endpoint: string) => Promise<{ ready: boolean; detail: string }>,
  ) {
    this.spawnImpl = options.spawnImpl ?? defaultSpawn;
    this.readinessTimeoutMs = options.readinessTimeoutMs ?? 300000;
    this.pollIntervalMs = options.pollIntervalMs ?? 1000;
    this.shutdownGraceMs = options.shutdownGraceMs ?? 20000;
  }

  get state(): SupervisorState {
    return this.supervisorState;
  }

  get pid(): number | undefined {
    return this.process?.pid;
  }

  get readinessMs(): number | undefined {
    return this.lastReadinessMs;
  }

  /**
   * Spawns the server and waits for real readiness.
   *
   * If the child exits during the wait, the loop stops immediately instead of
   * polling a dead port until the timeout — an OOM kill during model load is the
   * single most common failure here, and it must be reported as such.
   */
  async start(token?: CancellationToken): Promise<SupervisorResult> {
    if (this.supervisorState !== 'stopped') {
      return { state: this.supervisorState, detail: `already ${this.supervisorState}` };
    }
    this.supervisorState = 'starting';
    const log = this.options.onLog ?? (() => undefined);
    log(`starting: ${this.options.command} ${this.options.args.join(' ')}`);

    try {
      this.process = this.spawnImpl(this.options.command, this.options.args, {
        ...(this.options.cwd !== undefined ? { cwd: this.options.cwd } : {}),
        ...(this.options.env !== undefined ? { env: this.options.env } : {}),
      });
    } catch (error) {
      this.supervisorState = 'stopped';
      return {
        state: 'stopped',
        detail: `spawn failed: ${error instanceof Error ? error.message : String(error)}`,
      };
    }

    const started = Date.now();
    let childExited = false;
    void this.process.exited.then((code) => {
      childExited = true;
      if (this.supervisorState !== 'stopping') {
        log(`inference server exited unexpectedly with code ${code}`);
        this.supervisorState = 'stopped';
      }
    });

    let detail = 'not probed';
    while (Date.now() - started < this.readinessTimeoutMs) {
      if (token?.isCancelled) {
        await this.stop();
        return { state: 'stopped', detail: 'cancelled while waiting for readiness' };
      }
      if (childExited) {
        this.supervisorState = 'stopped';
        return {
          state: 'stopped',
          detail:
            `inference server exited before becoming ready after ${Date.now() - started}ms. ` +
            'This is the usual signature of an out-of-memory kill while loading weights: check the ' +
            `published VRAM requirement (${this.options.spec.requirements.vramGbTypical}GB) against the host.`,
        };
      }
      const probe = await this.isReady(this.options.endpoint);
      detail = probe.detail;
      if (probe.ready) {
        this.lastReadinessMs = Date.now() - started;
        this.supervisorState = 'ready';
        log(`ready after ${this.lastReadinessMs}ms`);
        return {
          state: 'ready',
          ...(this.process.pid !== undefined ? { pid: this.process.pid } : {}),
          readinessMs: this.lastReadinessMs,
          detail,
        };
      }
      await new Promise<void>((resolve) => setTimeout(resolve, this.pollIntervalMs));
    }

    await this.stop();
    return {
      state: 'stopped',
      detail: `not ready within ${this.readinessTimeoutMs}ms: ${detail}`,
    };
  }

  /**
   * SIGTERM, wait for the grace period, then SIGKILL.
   *
   * llama.cpp flushes and frees the GPU handle on SIGTERM; SIGKILL is the
   * fallback for a wedged process, not the default path.
   */
  async stop(): Promise<SupervisorResult> {
    const handle = this.process;
    if (!handle) {
      this.supervisorState = 'stopped';
      return { state: 'stopped', detail: 'no process was started' };
    }
    this.supervisorState = 'stopping';
    const log = this.options.onLog ?? (() => undefined);
    log('sending SIGTERM');

    handle.kill('SIGTERM');
    const graceDeadline = Date.now() + this.shutdownGraceMs;
    let exited = false;
    void handle.exited.then(() => {
      exited = true;
    });

    while (!exited && Date.now() < graceDeadline) {
      await new Promise<void>((resolve) => setTimeout(resolve, 50));
    }
    let escalated = false;
    if (!exited) {
      escalated = true;
      log(`process did not exit within ${this.shutdownGraceMs}ms; sending SIGKILL`);
      handle.kill('SIGKILL');
      await handle.exited.catch(() => null);
    }
    this.process = undefined;
    this.supervisorState = 'stopped';
    // A killed process did not shut down cleanly, and saying otherwise hides the
    // one case an operator most needs to see.
    return {
      state: 'stopped',
      detail: escalated
        ? `did not exit on SIGTERM within ${this.shutdownGraceMs}ms; killed`
        : 'exited on SIGTERM',
    };
  }
}

const defaultSpawn: SpawnProcess = (command, args, options) => {
  const child: ChildProcess = nodeSpawn(command, args, {
    ...options,
    // Own process group, so a stopping server does not outlive the platform.
    detached: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return {
    get pid() {
      return child.pid;
    },
    exited: new Promise<number | null>((resolve) => {
      child.on('exit', (code) => resolve(code));
      child.on('error', () => resolve(null));
    }),
    kill(signal) {
      return child.kill(signal);
    },
  };
};
