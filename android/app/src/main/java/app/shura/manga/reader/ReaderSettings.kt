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

    fun loadPreferences(): ReaderPreferences = ReaderPreferences(
        brightness = preferences.getFloat(KEY_BRIGHTNESS, 0f),
        preloadPages = preferences.getInt(KEY_PRELOAD, 3),
        keepScreenOn = preferences.getBoolean(KEY_KEEP_ON, false),
        parallelDownloads = preferences.getInt(KEY_PARALLEL, 4),
        // False when the key was never written, so a build that never had this
        // setting - or one that stores nothing at all - still opens with the
        // reader's chapters exactly where they were.
        deleteChapterAfterReading = preferences.getBoolean(KEY_DELETE_AFTER, false),
        autoAdvanceChapter = preferences.getBoolean(KEY_AUTO_ADVANCE, false),
        autoAdvanceSeconds = ReaderPreferences.autoAdvanceSecondsOf(
            preferences.getInt(KEY_AUTO_ADVANCE_SECONDS, 3),
        ),
    )

    fun savePreferences(value: ReaderPreferences) {
        preferences.edit()
            .putFloat(KEY_BRIGHTNESS, ReaderPreferences.brightnessOf(value.brightness))
            .putInt(KEY_PRELOAD, ReaderPreferences.preloadOf(value.preloadPages))
            .putBoolean(KEY_KEEP_ON, value.keepScreenOn)
            .putInt(KEY_PARALLEL, ReaderPreferences.parallelismOf(value.parallelDownloads))
            .putBoolean(KEY_DELETE_AFTER, value.deleteChapterAfterReading)
            .putBoolean(KEY_AUTO_ADVANCE, value.autoAdvanceChapter)
            .putInt(KEY_AUTO_ADVANCE_SECONDS, value.autoAdvanceDelay)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "reader-mode"
        const val KEY_BRIGHTNESS = "reader-brightness"
        const val KEY_PRELOAD = "reader-preload"
        const val KEY_KEEP_ON = "reader-keep-screen-on"
        const val KEY_PARALLEL = "reader-parallel"
        const val KEY_DELETE_AFTER = "reader-delete-after-reading"
        const val KEY_AUTO_ADVANCE = "reader-auto-advance"
        const val KEY_AUTO_ADVANCE_SECONDS = "reader-auto-advance-seconds"
    }
}

data class ReaderSettings(val mode: ReaderMode = ReaderMode.VERTICAL)

/*
 * The rest of the reader's configuration lives in ReaderPreferences.kt, stored by the
 * same store so one save writes a coherent set: brightness, how far ahead pages are
 * preloaded, whether the screen stays awake, how many pages download at once, and
 * whether a chapter is deleted once it has been read.
 */