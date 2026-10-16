package app.shura.translator.state;

/**
 * The lifecycle of one user-visible request: idle → running → succeeded / failed /
 * cancelled.
 *
 * Modelled explicitly rather than as scattered booleans because "loading, error,
 * retry, cancel" is the part of this app most likely to get wrong: a spinner that
 * survives a failure, a retry button on a request that cannot succeed, a cancel that
 * leaves the UI stuck.
 *
 * Pure Java with no Android types, so every transition is unit testable on the JVM.
 */
public final class RequestState<T> {

    public enum Phase {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private Phase phase = Phase.IDLE;
    private T result;
    private String errorMessage = "";
    /** True when retrying could plausibly succeed. Drives whether Retry is offered. */
    private boolean retryable = false;
    private long elapsedMs = 0;
    private int attempt = 0;

    public Phase phase() {
        return phase;
    }

    public boolean isBusy() {
        return phase == Phase.RUNNING;
    }

    public T result() {
        return result;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    /** Attempts made so far; 0 before the first try. */
    public int attempt() {
        return attempt;
    }

    public void start() {
        phase = Phase.RUNNING;
        attempt += 1;
        result = null;
        errorMessage = "";
        // Retryability of a *previous* failure must not leak into a new attempt:
        // showing "Retry" while running is contradictory UI.
        retryable = false;
    }

    public void succeed(T value, long tookMs) {
        phase = Phase.SUCCEEDED;
        result = value;
        errorMessage = "";
        retryable = false;
        elapsedMs = tookMs;
    }

    public void fail(String message, boolean canRetry) {
        phase = Phase.FAILED;
        result = null;
        errorMessage = message == null ? "" : message;
        retryable = canRetry;
    }

    public void cancel() {
        phase = Phase.CANCELLED;
        result = null;
        errorMessage = "";
        retryable = true;
    }

    public void reset() {
        phase = Phase.IDLE;
        result = null;
        errorMessage = "";
        retryable = false;
        elapsedMs = 0;
        attempt = 0;
    }

    /** A fresh attempt on a failed request; equivalent to `start`. */
    public void retry() {
        start();
    }

    @Override
    public String toString() {
        return "RequestState{" + phase + ", attempt=" + attempt
                + ", retryable=" + retryable + (errorMessage.isEmpty() ? "" : ", error=" + errorMessage) + "}";
    }
}
