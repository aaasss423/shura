package app.shura.translator;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted settings.
 *
 * The default server address points at the host machine's loopback forwarded port,
 * which is what `npm run start` exposes when `adb reverse` is in use. It is a default,
 * not a claim: the address is editable on the Settings screen and shown in full, so a
 * user pointing at a real host is never silently talking to the wrong server.
 */
public final class Prefs {

    private static final String FILE = "shura-translator";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_SOURCE = "sourceLanguage";
    private static final String KEY_TIMEOUT = "timeoutMs";

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8787";
    public static final String DEFAULT_SOURCE = "auto";
    public static final String TARGET_LANGUAGE = "ar";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        String value = prefs.getString(KEY_BASE_URL, "");
        return value == null || value.isEmpty() ? DEFAULT_BASE_URL : value;
    }

    public void setBaseUrl(String value) {
        prefs.edit().putString(KEY_BASE_URL, value == null ? "" : value.trim()).apply();
    }

    /** Empty means "let the server route"; the app does not pick a model. */
    public String engine() {
        String value = prefs.getString(KEY_ENGINE, "");
        return value == null ? "" : value;
    }

    public void setEngine(String value) {
        prefs.edit().putString(KEY_ENGINE, value == null ? "" : value.trim()).apply();
    }

    public String sourceLanguage() {
        String value = prefs.getString(KEY_SOURCE, "");
        return value == null || value.isEmpty() ? DEFAULT_SOURCE : value;
    }

    public void setSourceLanguage(String value) {
        prefs.edit().putString(KEY_SOURCE, value == null ? "" : value.trim()).apply();
    }

    public int timeoutMs() {
        return prefs.getInt(KEY_TIMEOUT, 60000);
    }

    public void setTimeoutMs(int value) {
        prefs.edit().putInt(KEY_TIMEOUT, value).apply();
    }
}
