package app.shura.translator.net;

import app.shura.translator.model.Models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * Typed access to the platform API.
 *
 * Every path here exists in the running server; none was invented for the app:
 * `/health`, `/engines`, `/engine/health`, `/translate`, `/translate/chapter`,
 * `/detect-language`, plus read-only views of `/knowledge`, `/glossary`, `/memory`
 * so the reader can see that those layers are in the path.
 *
 * Translation Memory, Glossary and Knowledge are applied server-side inside the
 * pipeline. The app does not reimplement any of it: it asks for a translation and
 * reports which engine and cache produced it.
 */
public final class TranslationApi {

    private final ApiClient client;

    public TranslationApi(ApiClient client) {
        this.client = client;
    }

    public ApiClient client() {
        return client;
    }

    public String baseUrl() {
        return client.baseUrl();
    }

    public Models.Health health(Cancellation cancellation) throws ApiException {
        return Models.parseHealth(client.get("/health", cancellation));
    }

    public List<Models.Engine> engines(Cancellation cancellation) throws ApiException {
        return Models.parseEngines(client.get("/engines", cancellation));
    }

    public Models.Health engineHealth(String engineId, Cancellation cancellation) throws ApiException {
        String path = engineId == null || engineId.isEmpty()
                ? "/engine/health"
                : "/engine/health?engine=" + encode(engineId);
        // /engine/health answers 503 when unhealthy. That is a real answer about the
        // engine, not a failed request, so it is read rather than thrown.
        try {
            return Models.parseHealth(client.get(path, cancellation));
        } catch (ApiException error) {
            if (error.status() == 503) {
                return new Models.Health(false, error.getMessage(), 0);
            }
            throw error;
        }
    }

    /**
     * POST /translate
     *
     * `contextBefore` carries the preceding sentence so context-sensitive lines
     * ("that's what she said") resolve correctly; the server uses it and the app
     * should send it whenever it has one.
     */
    public Models.Translation translate(TranslateRequest request, Cancellation cancellation) throws ApiException {
        JSONObject body = new JSONObject();
        put(body, "text", request.text);
        put(body, "sourceLanguage", request.sourceLanguage);
        put(body, "targetLanguage", request.targetLanguage);
        put(body, "engine", request.engine);
        put(body, "contextBefore", request.contextBefore);
        put(body, "noCache", request.noCache);
        putNumber(body, "timeoutMs", request.timeoutMs);
        putNumber(body, "retries", request.retries);
        return Models.parseTranslation(client.post("/translate", body, cancellation));
    }

    /**
     * POST /translate/chapter
     *
     * `failurePolicy: partial` keeps a chapter readable when a few segments fail,
     * which is the right default for a reader: a page with one untranslated bubble
     * beats an error. The server reports what degraded.
     */
    public Models.Chapter translateChapter(ChapterRequest request, Cancellation cancellation) throws ApiException {
        JSONObject body = new JSONObject();
        JSONArray segments = new JSONArray();
        // JSONArray.put(String) is unchecked; a null segment would throw here, which
        // is caught by the caller's non-empty check rather than silently serialized.
        for (String segment : request.segments) {
            segments.put(segment);
        }
        putValue(body, "segments", segments);
        put(body, "sourceLanguage", request.sourceLanguage);
        put(body, "targetLanguage", request.targetLanguage);
        put(body, "engine", request.engine);
        put(body, "joinWith", request.joinWith);
        putNumber(body, "concurrency", request.concurrency);
        putNumber(body, "deadlineMs", request.deadlineMs);
        put(body, "failurePolicy", request.failurePolicy);
        put(body, "noCache", request.noCache);
        return Models.parseChapter(client.post("/translate/chapter", body, cancellation));
    }

    public String detectLanguage(String text, Cancellation cancellation) throws ApiException {
        JSONObject body = new JSONObject();
        put(body, "text", text);
        JSONObject json = client.post("/detect-language", body, cancellation);
        return json.optString("language", "");
    }

    /**
     * Read-only counts from the knowledge layers.
     *
     * A convenience for the diagnostics screen, not a translation path: it lets a
     * reader see that glossary and memory exist for their language without the app
     * pretending to own them. A failure here is non-fatal.
     */
    public int countOf(String path, String language, Cancellation cancellation) {
        try {
            JSONObject json = client.get(path + (language == null || language.isEmpty() ? "" : "?sourceLanguage=" + language), cancellation);
            return json.optInt("total", json.optInt("count", 0));
        } catch (ApiException error) {
            return -1;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            // UTF-8 is guaranteed by the platform; unreachable in practice.
            throw new IllegalStateException("UTF-8 is unavailable", error);
        }
    }

    private static void put(JSONObject target, String key, String value) throws ApiException {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw badRequest(key, error);
        }
    }

    private static void put(JSONObject target, String key, boolean value) throws ApiException {
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw badRequest(key, error);
        }
    }

    private static void putValue(JSONObject target, String key, Object value) throws ApiException {
        if (value == null) {
            return;
        }
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw badRequest(key, error);
        }
    }

    private static void putNumber(JSONObject target, String key, int value) throws ApiException {
        if (value <= 0) {
            return;
        }
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw badRequest(key, error);
        }
    }

    /**
     * A body we could not build is our bug, not the server's, so it is not retryable:
     * sending the same broken request again would only produce the same failure.
     */
    private static ApiException badRequest(String key, Exception cause) {
        return new ApiException(ApiException.Kind.PROTOCOL, 0, "REQUEST_BUILD_FAILED",
                "Could not build the request field \"" + key + "\"", false, cause);
    }

    /** Request fields, kept separate from the transport so they can be asserted on. */
    public static final class TranslateRequest {
        public String text = "";
        public String sourceLanguage = "auto";
        public String targetLanguage = "ar";
        public String engine = "";
        public String contextBefore = "";
        public boolean noCache = false;
        public int timeoutMs = 0;
        public int retries = 0;
    }

    public static final class ChapterRequest {
        public List<String> segments = java.util.Collections.emptyList();
        public String sourceLanguage = "auto";
        public String targetLanguage = "ar";
        public String engine = "";
        public String joinWith = "\n";
        public int concurrency = 0;
        public int deadlineMs = 0;
        public String failurePolicy = "partial";
        public boolean noCache = false;
    }
}
