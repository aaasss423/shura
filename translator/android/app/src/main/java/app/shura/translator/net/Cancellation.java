package app.shura.translator.net;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A cancel token the UI owns and the network layer observes.
 *
 * Deliberately not Android's `CancellationSignal`: the API layer and its tests must
 * run on a plain JVM, and pulling a framework class in for a boolean would make the
 * whole client untestable without an emulator.
 *
 * Cancellation actually stops the work. `HttpURLConnection.disconnect()` on a
 * running request tears down the socket, which makes the server see the client go
 * away and lets its own token propagate — a flag nobody checks would leave the
 * reader staring at a spinner after they pressed stop.
 */
public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Runnable onCancel = () -> { };

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Cancels once. Repeat calls are ignored so a double tap cannot run the abort
     * handler twice.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                onCancel.run();
            } catch (RuntimeException ignored) {
                // An abort handler must not mask the cancellation itself.
            }
        }
    }

    /** Registers the abort action, running it immediately if already cancelled. */
    public void onCancel(Runnable action) {
        this.onCancel = action == null ? () -> { } : action;
        if (cancelled.get()) {
            try {
                this.onCancel.run();
            } catch (RuntimeException ignored) {
                // Same reason as above.
            }
        }
    }

    /** Throws if already cancelled, for pre-flight checks before any work starts. */
    public void throwIfCancelled() throws ApiException {
        if (cancelled.get()) {
            throw new ApiException(ApiException.Kind.CANCELLED, 0, "CANCELLED", "Cancelled", false, null);
        }
    }
}
