package app.shura.translator.net;

/**
 * A transport or API failure, with enough detail for the UI to choose between
 * "retry" and "this will never work".
 *
 * The distinction matters: telling a user to retry a 401 is a lie, and so is
 * silently retrying a malformed request.
 */
public class ApiException extends Exception {

    public enum Kind {
        /** Server reachable, answered with an error status. */
        SERVER,
        /** Server not reachable at all: wrong URL, offline, wrong port. */
        NETWORK,
        /** Server sent something that is not the JSON we expect. */
        PROTOCOL,
        /** The call exceeded its own deadline. */
        TIMEOUT,
        /** The caller cancelled. */
        CANCELLED
    }

    private final Kind kind;
    private final int status;
    private final String code;
    private final boolean retryable;

    public ApiException(Kind kind, int status, String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.code = code == null ? "" : code;
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or 0 when the request never got an answer. */
    public int status() {
        return status;
    }

    /** Platform error code when the body carried one, otherwise empty. */
    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean isCancelled() {
        return kind == Kind.CANCELLED;
    }

    /**
     * Retry is offered only when it can plausibly succeed.
     *
     * 4xx other than 408/429 means the request itself is wrong, so retrying sends the
     * same bad request and wastes the reader's battery and patience.
     */
    public static ApiException fromStatus(int status, String body) {
        String code = "";
        String message = body == null ? "" : body;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body == null ? "{}" : body);
            if (json.has("error") && json.get("error") instanceof org.json.JSONObject) {
                org.json.JSONObject error = json.getJSONObject("error");
                code = error.optString("code", "");
                message = error.optString("message", message);
            } else if (json.has("message")) {
                message = json.optString("message", message);
            }
        } catch (Exception ignored) {
            // Not an error envelope; the raw body is the best message available.
        }
        boolean retryable = status >= 500 || status == 408 || status == 429;
        return new ApiException(Kind.SERVER, status, code,
                message.isEmpty() ? ("HTTP " + status) : message, retryable, null);
    }
}
