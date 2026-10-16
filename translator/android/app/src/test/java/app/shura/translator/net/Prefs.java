package app.shura.translator.net;

/**
 * Test-side default settings holder.
 *
 * The app's `Prefs` needs an Android `Context`; unit tests have none. This carries the
 * same defaults so the integration test talks to a live server the way the app would,
 * without an Android runtime.
 */
final class Prefs {

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:8787";
    static final String DEFAULT_SOURCE = "auto";
    static final String TARGET_LANGUAGE = "ar";

    private final String baseUrl;
    private final String sourceLanguage;
    private final String engine;
    private final int timeoutMs;

    Prefs() {
        this(DEFAULT_BASE_URL, DEFAULT_SOURCE, "", 60000);
    }

    Prefs(String baseUrl, String sourceLanguage, String engine, int timeoutMs) {
        this.baseUrl = baseUrl;
        this.sourceLanguage = sourceLanguage;
        this.engine = engine;
        this.timeoutMs = timeoutMs;
    }

    String baseUrl() {
        return baseUrl;
    }

    String sourceLanguage() {
        return sourceLanguage;
    }

    String engine() {
        return engine;
    }

    int timeoutMs() {
        return timeoutMs;
    }
}
