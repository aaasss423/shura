package app.shura.translator.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Response models mirroring the platform REST contract.
 *
 * Parsing lives here rather than in the UI so it can be unit tested on the JVM
 * against the exact JSON the server emits. Field names come from
 * `serializeTranslation` / `serializeChapter` in `src/server/server.ts`; nothing
 * here is invented.
 *
 * Every optional field is genuinely optional. The server omits fields depending on
 * engine and configuration, and a client that assumes they exist would crash on a
 * perfectly valid response.
 */
public final class Models {

    private Models() {
    }

    /** One segment's translation as returned by /translate and /translate/chapter. */
    public static final class Segment {
        public final int index;
        public final String source;
        public final String target;
        public final Boolean failed;

        Segment(int index, String source, String target, Boolean failed) {
            this.index = index;
            this.source = source;
            this.target = target;
            this.failed = failed;
        }

        public boolean isFailed() {
            return Boolean.TRUE.equals(failed);
        }
    }

    /** POST /translate */
    public static final class Translation {
        public final String text;
        public final String rawText;
        public final String sourceLanguage;
        public final String detectedLanguage;
        public final String targetLanguage;
        public final String engine;
        public final boolean fromCache;
        public final long elapsedMs;
        /** "rtl" for Arabic. Applied to the output view rather than assumed. */
        public final String direction;
        public final List<Segment> segments;

        Translation(String text, String rawText, String sourceLanguage, String detectedLanguage,
                    String targetLanguage, String engine, boolean fromCache, long elapsedMs,
                    String direction, List<Segment> segments) {
            this.text = text;
            this.rawText = rawText;
            this.sourceLanguage = sourceLanguage;
            this.detectedLanguage = detectedLanguage;
            this.targetLanguage = targetLanguage;
            this.engine = engine;
            this.fromCache = fromCache;
            this.elapsedMs = elapsedMs;
            this.direction = direction;
            this.segments = segments;
        }

        /** Arabic output is RTL; the server sends this so the client never guesses. */
        public boolean isRtl() {
            return "rtl".equals(direction) || "ar".equals(targetLanguage);
        }
    }

    /** POST /translate/chapter */
    public static final class Chapter {
        public final String text;
        public final String targetLanguage;
        public final String engine;
        public final long elapsedMs;
        /** True when some segments failed and the rest were kept. */
        public final boolean degraded;
        public final String direction;
        public final List<Segment> segments;

        Chapter(String text, String targetLanguage, String engine, long elapsedMs, boolean degraded,
                String direction, List<Segment> segments) {
            this.text = text;
            this.targetLanguage = targetLanguage;
            this.engine = engine;
            this.elapsedMs = elapsedMs;
            this.degraded = degraded;
            this.direction = direction;
            this.segments = segments;
        }
    }

    /** GET /engines */
    public static final class Engine {
        public final String id;
        public final String name;
        public final boolean available;
        public final String reason;

        public Engine(String id, String name, boolean available, String reason) {
            this.id = id;
            this.name = name;
            this.available = available;
            this.reason = reason;
        }
    }

    /** GET /health and GET /engine/health */
    public static final class Health {
        public final boolean healthy;
        public final String detail;
        public final double latencyMs;

        public Health(boolean healthy, String detail, double latencyMs) {
            this.healthy = healthy;
            this.detail = detail;
            this.latencyMs = latencyMs;
        }
    }

    // --- parsing ----------------------------------------------------------

    public static Translation parseTranslation(JSONObject json) {
        JSONArray rawSegments = json.optJSONArray("segments");
        List<Segment> segments = new ArrayList<>();
        if (rawSegments != null) {
            for (int i = 0; i < rawSegments.length(); i++) {
                JSONObject item = rawSegments.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                segments.add(new Segment(
                        item.optInt("index", i),
                        optString(item, "source"),
                        optString(item, "target"),
                        item.has("failed") ? item.optBoolean("failed") : null));
            }
        }
        return new Translation(
                optString(json, "text"),
                optString(json, "rawText"),
                optString(json, "sourceLanguage"),
                optString(json, "detectedLanguage"),
                optString(json, "targetLanguage"),
                optString(json, "engine"),
                json.optBoolean("fromCache", false),
                json.optLong("elapsedMs", 0),
                optString(json, "direction"),
                Collections.unmodifiableList(segments));
    }

    public static Chapter parseChapter(JSONObject json) {
        JSONArray rawSegments = json.optJSONArray("segments");
        List<Segment> segments = new ArrayList<>();
        if (rawSegments != null) {
            for (int i = 0; i < rawSegments.length(); i++) {
                JSONObject item = rawSegments.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                segments.add(new Segment(
                        item.optInt("index", i),
                        optString(item, "source"),
                        optString(item, "target"),
                        item.has("failed") ? item.optBoolean("failed") : null));
            }
        }
        return new Chapter(
                optString(json, "text"),
                optString(json, "targetLanguage"),
                optString(json, "engine"),
                json.optLong("elapsedMs", 0),
                json.optBoolean("degraded", false),
                optString(json, "direction"),
                Collections.unmodifiableList(segments));
    }

    /**
     * Reads /engines defensively.
     *
     * The array may sit under `engines` or be the whole body; both shapes have been
     * observed across the platform's own routes, so both are accepted.
     */
    public static List<Engine> parseEngines(JSONObject json) {
        JSONArray array = json.optJSONArray("engines");
        if (array == null) {
            array = json.optJSONArray("data");
        }
        List<Engine> engines = new ArrayList<>();
        if (array == null) {
            return engines;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            engines.add(new Engine(
                    optString(item, "id"),
                    optString(item, "name"),
                    item.optBoolean("available", false),
                    optString(item, "reason")));
        }
        return engines;
    }

    /**
     * GET /health returns `{status:"ok", engine, uptimeSeconds}`; the per-engine
     * probe at /engine/health returns `{healthy, detail, latencyMs}` and answers 503
     * when unhealthy. Both shapes are read here.
     */
    public static Health parseHealth(JSONObject json) {
        boolean healthy = json.has("healthy")
                ? json.optBoolean("healthy", false)
                : "ok".equals(json.optString("status", ""));
        String detail = optString(json, "detail");
        if (detail.isEmpty()) {
            String engine = optString(json, "engine");
            detail = engine.isEmpty() ? "" : "engine=" + engine;
        }
        return new Health(healthy, detail, json.optDouble("latencyMs", 0));
    }

    private static String optString(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) {
            return "";
        }
        String value = json.optString(key, "");
        return "null".equals(value) ? "" : value;
    }
}
