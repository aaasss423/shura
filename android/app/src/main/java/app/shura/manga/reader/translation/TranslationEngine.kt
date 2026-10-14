package app.shura.manga.reader.translation

/**
 * The reader's view of a translation engine.
 *
 * ## What the engine actually does
 *
 * Shura's engine is a **text** engine. Its chapter contract is a list of text
 * segments and a list of translated segments back - it does not read text out
 * of a page image, and it does not draw translation onto one. This interface
 * says exactly that and nothing more, so nothing in the app can imply the
 * engine understands a manga page when it does not.
 *
 * Manga pages are images. Turning a page into something translatable needs
 * text extraction, which this engine does not do. The gap is named here rather
 * than papered over: a reader can display translated text that the app
 * already has, and nothing more. See [TranslationEngineAvailability.imageSupport]
 * for the bit that says so at runtime.
 */
interface TranslationEngine {

    /** Whether the engine can be reached, and what it supports if it can. */
    suspend fun availability(): TranslationEngineAvailability

    /**
     * Translates text segments as one unit.
     *
     * A page of manga is several segments, and translating them as a chapter
     * rather than one at a time is what preserves context between the lines of
     * a page. Implementations must not block the caller's thread.
     */
    suspend fun translateChapter(request: TranslationChapterRequest): TranslationChapterResult

    /** Cancels work in flight. Safe to call when nothing is running. */
    fun cancel()
}

/** One piece of text to translate, addressed by the page it came from. */
data class TranslationSegmentInput(
    /** The page index this segment belongs to, so results can be put back. */
    val pageIndex: Int,
    val text: String,
)

data class TranslationChapterRequest(
    val segments: List<TranslationSegmentInput>,
    val sourceLanguage: String,
    val targetLanguage: String,
    /** Whole-chapter bound; a chapter that cannot finish reports partial. */
    val deadlineMs: Long = 120_000,
    /** What to do when a segment cannot be translated at all. */
    val failurePolicy: FailurePolicy = FailurePolicy.PARTIAL,
)

enum class FailurePolicy { PARTIAL, ABORT }

/** One translated segment, or the reason it has none. */
data class TranslationSegmentResult(
    val pageIndex: Int,
    val text: String?,
    val error: String? = null,
) {
    val failed: Boolean get() = text.isNullOrBlank()
}

data class TranslationChapterResult(
    val segments: List<TranslationSegmentResult>,
    val engine: String = "",
    val elapsedMs: Long = 0,
    val degraded: Boolean = false,
    val error: String? = null,
) {
    val completed: Boolean get() = error == null
    val anySucceeded: Boolean get() = segments.any { !it.failed }

    /** Translated text per page, joined. Pages with nothing are absent. */
    fun textByPage(joinWith: String = "\n"): Map<Int, String> =
        segments.groupBy { it.pageIndex }
            .mapValues { (_, group) ->
                group.mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString(joinWith)
            }
            .filterValues { it.isNotBlank() }
}

/**
 * Whether the engine is reachable and what it can do.
 *
 * [imageSupport] is false for a text engine and is reported rather than assumed,
 * because the difference is the difference between "translated manga" and
 * "translated text that happened to be near some manga".
 */
data class TranslationEngineAvailability(
    val reachable: Boolean,
    val engine: String = "",
    val detail: String = "",
    val languages: List<String> = emptyList(),
    val imageSupport: Boolean = false,
) {
    /**
     * Whether a translated page can be produced from a page image.
     *
     * Always false against a text engine. It exists so the reader can say
     * "this engine cannot read a page" rather than showing an empty page and
     * letting the reader conclude the translation failed.
     */
    val canTranslateMangaPages: Boolean get() = reachable && imageSupport
}
