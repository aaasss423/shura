package app.shura.manga.reader.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives translation for the reader.
 *
 * Decides *which* pages to translate, asks the engine for them, and keeps one
 * chapter's result. It is deliberately separate from both the engine and the
 * cache so the rules can be reasoned about - and tested - without a network:
 *
 *  - mode OFF: nothing is requested and the engine is not touched.
 *  - FULL: every page, once. A page already translated is never re-asked for.
 *  - FOLLOW_READING: a bounded look-ahead from the visible page.
 *
 * A cached chapter is answered from disk without contacting the engine at all,
 * which is what makes moving back to a chapter free.
 *
 * Failures are reported, never swallowed: a page that could not be translated
 * says so, and the reader can retry. Nothing here claims a translation
 * succeeded because a request returned.
 */
class TranslationController(
    private val engine: TranslationEngine?,
    private val cache: TranslationCache?,
    private val maxPagesPerRequest: Int = 8,
) {

    /** What the reader shows for a page's translated text. */
    data class PageState(
        val text: String? = null,
        val pending: Boolean = false,
        val failed: Boolean = false,
        val reason: String = "",
    )

    data class State(
        val mode: TranslationMode = TranslationMode.OFF,
        val engineReachable: Boolean = false,
        val engineName: String = "",
        /** True when the engine cannot read page images, so pages stay untranslated. */
        val textOnlyEngine: Boolean = true,
        val pages: Map<Int, PageState> = emptyMap(),
        val reason: String = "",
    )

    private val mutex = Mutex()
    private var lastResult: Map<Int, String> = emptyMap()
    private var lastFailure: String = ""
    private var lastMode: TranslationMode = TranslationMode.OFF
    private var engineReachable = false
    private var engineName = ""
    private var textOnly = true

    /** Cancels anything in flight; the reader calls this when it leaves. */
    fun cancel() {
        engine?.cancel()
    }

    /**
     * Whether the engine can be reached, asked once and cached until
     * [refreshAvailability] is called.
     *
     * A caller with no engine configured gets "unreachable" rather than an
     * exception, so the reader can render its offline state instead of
     * crashing on a build that ships without a translator.
     */
    suspend fun refreshAvailability(): Boolean {
        val availability = engine?.availability()
            ?: return false.also {
                engineReachable = false
                engineName = ""
            }
        engineReachable = availability.reachable
        engineName = availability.engine
        textOnly = !availability.imageSupport
        return engineReachable
    }

    /**
     * Produces the translated text for [totalPages], honouring the mode.
     *
     * Returns the state the reader should render. Never throws for an
     * unreachable engine - that is a state, not a crash.
     */
    suspend fun translate(
        settings: TranslationSettings,
        sourceId: String,
        chapterId: String,
        texts: Map<Int, String>,
        firstVisiblePage: Int,
        totalPages: Int,
    ): State = mutex.withLock {
        lastMode = settings.mode
        if (settings.mode == TranslationMode.OFF) {
            lastResult = emptyMap()
            lastFailure = ""
            return@withLock State(
                mode = settings.mode,
                engineReachable = engineReachable,
                engineName = engineName,
                textOnlyEngine = textOnly,
            )
        }

        // Page-aware cache. It is consulted for the pages this call wants, not
        // as a blanket shortcut: returning the whole cached chapter would stop
        // FOLLOW_READING translating anything the reader scrolls on to.
        val cached = cache?.pagesByIndex(sourceId, chapterId).orEmpty()
        val alreadyDone = cached.keys.toSet()

        val wanted = ReaderTranslationPlanner.pagesToTranslate(
            settings = settings,
            totalPages = totalPages,
            firstVisible = firstVisiblePage,
            alreadyTranslated = alreadyDone,
        )
            // Only pages that actually carry text: sending an empty string to a
            // translation engine costs a request and returns nothing.
            .filter { texts[it]?.isNotBlank() == true }
        val pending = wanted.toSet()
        // Everything wanted is already translated: answered entirely from the
        // cache, without touching the engine at all.
        if (pending.isEmpty()) {
            return@withLock State(
                mode = settings.mode,
                engineReachable = engineReachable,
                engineName = engineName,
                textOnlyEngine = textOnly,
                pages = cached.mapValues { (_, text) -> PageState(text = text) },
            )
        }

        if (engine == null || !engineReachable) {
            lastFailure = "no translation engine is reachable"
            return@withLock State(
                mode = settings.mode,
                engineReachable = false,
                engineName = engineName,
                textOnlyEngine = textOnly,
                pages = pending.associateWith {
                    PageState(pending = true, failed = true, reason = lastFailure)
                },
                reason = lastFailure,
            )
        }

        val requests = pending.sorted().chunked(maxPagesPerRequest)
        val collected = mutableMapOf<Int, String>()
        var failure = ""
        for (batch in requests) {
            if (failure.isNotEmpty()) break
            val request = TranslationChapterRequest(
                segments = batch.map {
                    TranslationSegmentInput(pageIndex = it, text = texts.getValue(it))
                },
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
            )
            try {
                val result = engine.translateChapter(request)
                engineReachable = true
                result.textByPage().forEach { (page, text) -> collected[page] = text }
                result.segments.filter { it.failed }.forEach { segment ->
                    if (failure.isEmpty()) {
                        failure = segment.error ?: "this page could not be translated"
                    }
                }
                if (!result.completed && failure.isEmpty()) {
                    failure = result.error ?: "the engine did not complete the chapter"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "the engine could not be reached"
                engineReachable = false
            }
        }

        lastFailure = failure
        if (collected.isNotEmpty() && cache != null) {
            // Merge into what was already there, so a chapter that was half
            // translated by an earlier scroll is not reset by the next batch.
            val merged = LinkedHashMap(cached).apply { putAll(collected) }
            cache.storePages(sourceId, chapterId, merged)
            lastResult = merged
            return@withLock state(collected, failure, pending)
        }
        lastResult = collected
        state(collected, failure, pending)
    }

    /** The state the reader renders for a finished batch. */
    private fun state(
        collected: Map<Int, String>,
        failure: String,
        pending: Set<Int>,
    ) = State(
        mode = lastMode,
        engineReachable = engineReachable,
        engineName = engineName,
        textOnlyEngine = textOnly,
        pages = pending.associateWith { page ->
            val text = collected[page]
            when {
                text != null -> PageState(text = text)
                failure.isNotEmpty() -> PageState(failed = true, reason = failure)
                else -> PageState(pending = true)
            }
        },
        reason = failure,
    )

    /** What was last produced, for a caller that wants it directly. */
    fun lastTranslation(): Map<Int, String> = lastResult

    fun lastError(): String = lastFailure
}
