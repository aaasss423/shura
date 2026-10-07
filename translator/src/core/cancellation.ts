/**
 * Cancellation primitives.
 *
 * A `CancellationToken` is the single cancellation currency of the platform.
 * Every layer (service, engine, chapter, REST) accepts one and checks it at
 * well-defined points, including a pre-flight check before any work starts so
 * an already-cancelled request never reaches the network.
 */

import { CancelledError } from '../core/errors';

/** Opaque abort reason; the real reason lives on the token. */
const ABORT_SENTINEL = Symbol('translation.cancelled') as unknown as string;

export interface CancelReason {
  reason?: unknown;
  message?: string;
}

export class CancellationToken {
  private cancelled = false;
  private readonly controller = new AbortController();
  private readonly listeners = new Set<(reason: CancelReason) => void>();
  private cancelReason: CancelReason | undefined;

  static none(): CancellationToken {
    return new CancellationToken();
  }

  get isCancelled(): boolean {
    return this.cancelled;
  }

  get signal(): AbortSignal {
    return this.controller.signal;
  }

  get reason(): CancelReason | undefined {
    return this.cancelReason;
  }

  cancel(reason: CancelReason = {}): void {
    if (this.cancelled) {
      return;
    }
    this.cancelled = true;
    this.cancelReason = reason;
    // AbortController abort is synchronous, so in-flight fetches observe it
    // immediately rather than at the next microtask.
    //
    // The abort reason is deliberately a plain sentinel rather than an Error
    // instance: passing an Error as the abort reason makes undici surface that
    // same object through internal promises we do not own, which produced
    // unhandled rejections after the request had already failed cleanly. The
    // reason is carried on the token instead and read by throwIfCancelled().
    this.controller.abort(ABORT_SENTINEL);
    for (const listener of this.listeners) {
      try {
        listener(reason);
      } catch {
        // Listener errors must not break cancellation propagation.
      }
    }
    this.listeners.clear();
  }

  onCancel(listener: (reason: CancelReason) => void): () => void {
    if (this.cancelled) {
      listener(this.cancelReason ?? {});
      return () => undefined;
    }
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Throws if already cancelled. Call before any expensive work. */
  throwIfCancelled(): void {
    if (this.cancelled) {
      throw new CancelledError(this.cancelReason?.message ?? 'operation was cancelled', {
        ...(this.cancelReason?.reason === undefined
          ? {}
          : { details: { reason: String(this.cancelReason.reason) } }),
      });
    }
  }

  /** Combines this token with another; cancelling either cancels the result. */
  static link(tokens: Array<CancellationToken | undefined>): CancellationToken {
    const linked = new CancellationToken();
    for (const token of tokens) {
      if (!token) {
        continue;
      }
      if (token.isCancelled) {
        linked.cancel({ reason: token.reason });
        return linked;
      }
      token.onCancel((reason) => linked.cancel(reason));
    }
    return linked;
  }
}