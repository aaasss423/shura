package app.shura.translator.net;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP transport for the platform API.
 *
 * Built on `HttpURLConnection` and `org.json`, both of which are in the Android
 * framework. That is a deliberate constraint: no OkHttp, no Retrofit, no Gson.
 * Those are three more dependencies and three more version conflicts for a client
 * that needs POST-with-JSON and cancellation, both of which the framework does
 * natively — and it means the client is testable on a plain JVM.
 */
public final class ApiClient {

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ApiClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = normalizeBase(baseUrl);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Trims trailing slashes so path joining never produces a double slash. */
    public static String normalizeBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public JSONObject get(String path, Cancellation cancellation) throws ApiException {
        return request("GET", path, null, cancellation);
    }

    public JSONObject post(String path, JSONObject body, Cancellation cancellation) throws ApiException {
        return request("POST", path, body == null ? new JSONObject() : body, cancellation);
    }

    /**
     * Issues one request.
     *
     * Aborting calls `disconnect()`, which closes the socket rather than abandoning
     * the future: the work genuinely stops and the server observes the disconnect.
     */
    private JSONObject request(String method, String path, JSONObject body, Cancellation cancellation)
            throws ApiException {
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }

        HttpURLConnection connection = null;
        boolean handedOff = false;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }

            final HttpURLConnection active = connection;
            Runnable abort = () -> {
                try {
                    active.disconnect();
                } catch (RuntimeException ignored) {
                    // Already closed.
                }
            };
            if (cancellation != null) {
                cancellation.onCancel(abort);
                handedOff = true;
                if (cancellation.isCancelled()) {
                    abort.run();
                    throw new ApiException(ApiException.Kind.CANCELLED, 0, "CANCELLED", "Cancelled", false, null);
                }
            }

            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            String text = readBody(connection, status);
            if (cancellation != null && cancellation.isCancelled()) {
                throw new ApiException(ApiException.Kind.CANCELLED, 0, "CANCELLED", "Cancelled", false, null);
            }
            if (status < 200 || status >= 300) {
                throw ApiException.fromStatus(status, text);
            }
            if (text.isEmpty()) {
                return new JSONObject();
            }
            try {
                return new JSONObject(text);
            } catch (Exception error) {
                throw new ApiException(ApiException.Kind.PROTOCOL, status, "BAD_JSON",
                        "The server did not return JSON. Is this URL a translation server?", true, error);
            }
        } catch (ApiException error) {
            throw error;
        } catch (SocketTimeoutException error) {
            throw new ApiException(ApiException.Kind.TIMEOUT, 0, "TIMEOUT",
                    "The server took too long to answer.", true, error);
        } catch (IOException error) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new ApiException(ApiException.Kind.CANCELLED, 0, "CANCELLED", "Cancelled", false, error);
            }
            throw new ApiException(ApiException.Kind.NETWORK, 0, "UNREACHABLE",
                    "Cannot reach " + baseUrl + ". Check the server address and that it is running.", true, error);
        } finally {
            if (connection != null && !handedOff) {
                connection.disconnect();
            } else if (connection != null && cancellation == null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection, int status) {
        try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException error) {
            return "";
        }
    }
}
