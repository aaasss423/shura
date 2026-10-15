package app.shura.manga.reader

import android.content.Context

/**
 * How the reader lays out a chapter.
 *
 * Vertical is a continuous strip, which is what manga is drawn for: panels run
 * together the way they were designed to be read. Paged turns one page at a
 * time, which suits a screen reader, one-handed use, and anyone who gets lost
 * in a long strip.
 *
 * Whichever is chosen is kept - the choice is a reading preference, not a
 * per-session one, and resetting it on every launch is the kind of small
 * betrayal that makes an app feel unconsidered.
 */
enum class ReaderMode {
    VERTICAL,
    PAGED;

    companion object {
        /** Never throws on a stored value from a build that knew another mode. */
        fun parse(raw: String?): ReaderMode =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: VERTICAL
    }
}

/** Persists the reading preference. */
class ReaderSettingsStore(context: Context) {

    private val preferences =
        context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(mode = ReaderMode.parse(preferences.getString(KEY_MODE, null)))

    fun save(settings: ReaderSettings) {
        preferences.edit().putString(KEY_MODE, settings.mode.name).apply()
    }

    private companion object {
        const val KEY_MODE = "reader-mode"
    }
}

data class ReaderSettings(val mode: ReaderMode = ReaderMode.VERTICAL)