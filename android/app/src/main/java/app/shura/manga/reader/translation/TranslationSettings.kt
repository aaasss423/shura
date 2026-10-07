package app.shura.manga.reader.translation

import android.content.Context

/**
 * Reader-local translation model.
 *
 * The translation *engine* is deliberately absent; this layer only describes the
 * reader UX contract required by the Shura spec:
 *  - translation is a reader mode (button in the chapter reader, settings in
 *    reader settings),
 *  - the mode stays enabled when moving to the next chapter until the user turns
 *    it off,
 *  - FULL mode translates ahead of reading, FOLLOW_READING mode translates only
 *    near the user's position (bounded look-ahead), and neither path ever
 *    intercepts reader touch events. Rendering is a normal composable inside the
 *    reader UI; no system overlay/accessibility service is used.
 */
enum class TranslationMode { OFF, FULL, FOLLOW_READING }

data class TranslationSettings(
    val mode: TranslationMode = TranslationMode.OFF,
    val targetLanguage: String = "ar",
    val sourceLanguage: String = "ja",
)

/** Number of pages translated ahead of the visible page in FOLLOW_READING mode. */
const val FOLLOW_READING_LOOKAHEAD = 3

/**
 * Pure decision logic: which pages need translation given the visible page and
 * the set of already-translated pages. This keeps "translation follows reading"
 * testable without any Android/runtime dependency.
 */
object ReaderTranslationPlanner {
    fun pagesToTranslate(
        settings: TranslationSettings,
        totalPages: Int,
        firstVisible: Int,
        alreadyTranslated: Set<Int>,
    ): List<Int> {
        if (settings.mode == TranslationMode.OFF || totalPages <= 0) return emptyList()
        val done = alreadyTranslated
        return when (settings.mode) {
            TranslationMode.FULL -> (0 until totalPages).filter { it !in done }
            TranslationMode.FOLLOW_READING -> {
                val start = firstVisible.coerceIn(0, totalPages - 1)
                val end = minOf(start + FOLLOW_READING_LOOKAHEAD, totalPages)
                (start until end).filter { it !in done }
            }
            TranslationMode.OFF -> emptyList()
        }
    }
}

/** Persists the mode across chapter switches so the reader does not forget it. */
class TranslationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader-translation", Context.MODE_PRIVATE)

    fun load(): TranslationSettings = TranslationSettings(
        mode = runCatching { TranslationMode.valueOf(preferences.getString(KEY_MODE, null) ?: return@runCatching "") }
            .getOrDefault(TranslationMode.OFF),
        targetLanguage = preferences.getString(KEY_TARGET, "ar") ?: "ar",
        sourceLanguage = preferences.getString(KEY_SOURCE, "ja") ?: "ja",
    )

    fun save(settings: TranslationSettings) {
        preferences.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_TARGET, settings.targetLanguage)
            .putString(KEY_SOURCE, settings.sourceLanguage)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_TARGET = "target"
        const val KEY_SOURCE = "source"
    }
}