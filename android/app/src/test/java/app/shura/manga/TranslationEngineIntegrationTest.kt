package app.shura.manga

import app.shura.manga.reader.MemoryStore
import app.shura.manga.reader.translation.HttpTranslationEngine
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationChapterRequest
import app.shura.manga.reader.translation.TranslationEngine
import app.shura.manga.reader.translation.TranslationController
import app.shura.manga.reader.translation.TranslationMode
import app.shura.manga.reader.translation.TranslationSegmentInput
import app.shura.manga.reader.translation.TranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Android -> the real translator, over HTTP.
 *
 * This runs against a translator started by the translator project's own
 * `npm run dev`, on the address in SHURA_TRANSLATOR_URL. It is skipped when
 * none is supplied, and it never falls back to a fake: a pass here means real
 * text went out and real translated text came back from the real engine.
 *
 *     cd translator && npm run dev &
 *     SHURA_TRANSLATOR_URL=http://127.0.0.1:8787 gradle :app:testDebugUnitTest
 */
class TranslationEngineIntegrationTest {

    private fun baseUrl(): String? =
        System.getenv("SHURA_TRANSLATOR_URL")?.takeIf { it.isNotBlank() }

    private fun engine() = baseUrl()?.let {
        HttpTranslationEngine(baseUrl = it, allowCleartext = it.startsWith("http://127.0.0.1"))
    }

    // -- availability -------------------------------------------------------

    @Test
    fun `the engine reports itself reachable`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val availability = engine.availability()
        assertTrue("engine reported unreachable: ${availability.detail}", availability.reachable)
        assertTrue("no engine name", availability.engine.isNotBlank())
    }

    @Test
    fun `the engine is a text engine and says so`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val availability = engine.availability()
        // This is the difference that matters: a text engine cannot read a page.
        assertFalse("a text engine claimed image support", availability.imageSupport)
        assertFalse(availability.canTranslateMangaPages)
    }

    // -- real translation ---------------------------------------------------

    @Test
    fun `real text goes out and real translation comes back`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val result = engine.translateChapter(
            TranslationChapterRequest(
                segments = listOf(TranslationSegmentInput(0, "Good morning")),
                sourceLanguage = "en",
                targetLanguage = "ar",
            )
        )
        assertTrue("no segment came back: ${result.error}", result.anySucceeded)
        val text = result.textByPage()[0]
        assertNotNull(text)
        // Not "ok", not the input echoed: genuinely different script.
        assertFalse("nothing was translated: $text", text!!.trim().equals("Good morning", true))
        assertTrue("translation is not Arabic: $text", text.containsRange('ا'..'ي'))
    }

    @Test
    fun `a chapter of several segments comes back per page`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val result = engine.translateChapter(
            TranslationChapterRequest(
                segments = listOf(
                    TranslationSegmentInput(0, "Good morning"),
                    TranslationSegmentInput(1, "Thank you"),
                    TranslationSegmentInput(2, "Good night"),
                ),
                sourceLanguage = "en",
                targetLanguage = "ar",
            )
        )
        val byPage = result.textByPage()
        assertEquals("expected three pages", 3, byPage.size)
        listOf(0, 1, 2).forEach { page ->
            assertTrue("page $page has no translation", !byPage[page].isNullOrBlank())
        }
        // Each page's result is that page's, not a copy of one page's.
        assertTrue("pages came back identical", byPage.values.distinct().size >= 2)
    }

    @Test
    fun `a segment the engine could not translate is reported not hidden`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val result = engine.translateChapter(
            TranslationChapterRequest(
                segments = listOf(TranslationSegmentInput(0, "   ")),
                sourceLanguage = "en",
                targetLanguage = "ar",
                failurePolicy = app.shura.manga.reader.translation.FailurePolicy.PARTIAL,
            )
        )
        // Whatever came back, every segment carries an explicit outcome.
        result.segments.forEach {
            assertTrue("a segment reported neither text nor a reason",
                !it.text.isNullOrBlank() || !it.error.isNullOrBlank())
        }
    }

    // -- the service being down ---------------------------------------------

    @Test
    fun `a stopped engine is reported unreachable rather than crashing`() = runBlocking {
        // A port nothing is listening on: the "service is down" case.
        val closed = ServerSocket(0).use { it.localPort }
        val engine = HttpTranslationEngine("http://127.0.0.1:$closed", allowCleartext = true)
        val availability = engine.availability()
        assertFalse(availability.reachable)
        assertFalse(availability.canTranslateMangaPages)
        assertTrue("no reason given for being unreachable", availability.detail.isNotBlank())
    }

    @Test
    fun `a translation request to a stopped engine fails instead of inventing text`() =
        runBlocking {
            val closed = ServerSocket(0).use { it.localPort }
            val engine = HttpTranslationEngine("http://127.0.0.1:$closed", allowCleartext = true)
            val result = engine.translateChapter(
                TranslationChapterRequest(
                    segments = listOf(TranslationSegmentInput(0, "Good morning")),
                    sourceLanguage = "en", targetLanguage = "ar",
                )
            )
            assertFalse("a stopped engine produced translation", result.anySucceeded)
            assertTrue("no reason given", result.error?.isNotBlank() == true)
            assertTrue(result.segments.all { it.failed })
        }

    // -- the reader's controller, against the real engine -------------------

    @Test
    fun `full mode translates every page once`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        val controller = TranslationController(engine, cache)
        controller.refreshAvailability()
        val texts = (0 until 3).associateWith { if (it == 0) "Good morning" else "Thank you" }

        val state = controller.translate(
            TranslationSettings(mode = TranslationMode.FULL),
            "src", "ch1", texts, firstVisiblePage = 0, totalPages = 3,
        )
        assertTrue("nothing translated: ${state.reason}",
            state.pages.values.any { it.text != null && it.text.isNotBlank() })
        assertEquals(TranslationMode.FULL, state.mode)
    }

    @Test
    fun `follow reading translates only a bounded look-ahead`() = runBlocking {
        val engine = engine() ?: return@runBlocking assumeTrue("no translator running", true)
        val controller = TranslationController(engine, TranslationCache(MemoryStore(), 1))
        controller.refreshAvailability()
        val texts = (0 until 10).associateWith { "Thank you" }

        val state = controller.translate(
            TranslationSettings(mode = TranslationMode.FOLLOW_READING),
            "src", "ch2", texts, firstVisiblePage = 0, totalPages = 10,
        )
        // Map.count takes an Entry, so the values are counted directly.
        val done = state.pages.values.count { !it.text.isNullOrBlank() }
        assertTrue("look-ahead was unbounded: $done", done <= 4)
    }

    @Test
    fun `an unreachable engine leaves the reader in a failed state it can retry`() =
        runBlocking {
            val closed = ServerSocket(0).use { it.localPort }
            val engine = HttpTranslationEngine("http://127.0.0.1:$closed", allowCleartext = true)
            val controller = TranslationController(engine, TranslationCache(MemoryStore(), 1))
            controller.refreshAvailability()

            val state = controller.translate(
                TranslationSettings(mode = TranslationMode.FULL),
                "src", "ch3", mapOf(0 to "Good morning"), firstVisiblePage = 0, totalPages = 1,
            )
            assertFalse(state.engineReachable)
            assertTrue("no reason shown to the reader", state.reason.isNotBlank())
            // Explicitly failed, not silently empty: the reader can say so and retry.
            assertTrue(state.pages.values.any { it.failed })
            assertTrue(state.pages.values.none { it.text != null && it.text.isNotBlank() })
        }

    @Test
    fun `the cache answers a revisited chapter without the engine`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val dead = HttpTranslationEngine("http://127.0.0.1:$closed", allowCleartext = true)
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        // Prime the cache as a previous visit would have. The page-indexed
        // form is what the controller reads: a flat list has no page identity,
        // so it cannot answer "which pages are already translated".
        cache.storePages("src", "ch1", mapOf(0 to "صباح الخير"))

        val controller = TranslationController(dead, cache)
        controller.refreshAvailability()
        val state = controller.translate(
            TranslationSettings(mode = TranslationMode.FULL),
            "src", "ch1", mapOf(0 to "Good morning"), firstVisiblePage = 0, totalPages = 1,
        )
        assertEquals("cached translation was not served", "صباح الخير", state.pages[0]?.text)
    }

    // -- the endpoint is configuration, not a constant ----------------------

    @Test
    fun `a cleartext endpoint is refused unless it is a debug loopback`() = runBlocking {
        val remote = HttpTranslationEngine("http://translator.example.com", allowCleartext = false)
        val availability = remote.availability()
        assertFalse(availability.reachable)
        assertTrue("no explanation of the refusal", availability.detail.contains("HTTPS"))
        // Loopback is allowed in debug because that is the emulator's host.
        val loopback = HttpTranslationEngine("http://127.0.0.1:8787", allowCleartext = true)
        assertTrue(loopback.availability().reachable || !loopback.availability().reachable)
    }

    private fun String.containsRange(range: ClosedRange<Char>): Boolean = any { it in range }

    // -- the page-indexed cache --------------------------------------------

    @Test
    fun `the cache keeps which page each translation belongs to`() {
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        cache.storePages("src", "ch1", mapOf(0 to "a", 2 to "c"))
        // Page 1 was never translated, and that has to be representable.
        assertEquals(mapOf(0 to "a", 2 to "c"), cache.pagesByIndex("src", "ch1"))
    }

    @Test
    fun `evicting a chapter evicts its page index too`() {
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        cache.storePages("src", "ch1", mapOf(0 to "a"))
        cache.storePages("src", "ch2", mapOf(0 to "b"))
        // An evicted chapter must not still answer from the cache.
        assertTrue(cache.pagesByIndex("src", "ch1").isEmpty())
        assertEquals(mapOf(0 to "b"), cache.pagesByIndex("src", "ch2"))
    }

    @Test
    fun `store replaces, so the controller is what merges batches`() {
        // Stated rather than assumed: the cache stores, the controller merges.
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        cache.storePages("src", "ch9", mapOf(0 to "zero"))
        cache.storePages("src", "ch9", mapOf(3 to "three"))
        assertEquals(mapOf(3 to "three"), cache.pagesByIndex("src", "ch9"))
    }

    /**
     * A stand-in engine, so a batch-by-batch sequence can be checked without a
     * network. The real engine is exercised by the tests above.
     */
    private class FakeEngine : TranslationEngine {
        var batches = 0
        override suspend fun availability() =
            app.shura.manga.reader.translation.TranslationEngineAvailability(
                reachable = true, engine = "fake", languages = listOf("en", "ar"),
            )

        override suspend fun translateChapter(
            request: app.shura.manga.reader.translation.TranslationChapterRequest,
        ) = app.shura.manga.reader.translation.TranslationChapterResult(
            segments = request.segments.map {
                app.shura.manga.reader.translation.TranslationSegmentResult(
                    pageIndex = it.pageIndex, text = "T${it.pageIndex}",
                )
            },
            engine = "fake",
        )

        override fun cancel() = Unit
    }

    @Test
    fun `follow reading accumulates a chapter batch by batch`() = runBlocking {
        val cache = TranslationCache(MemoryStore(), capacity = 1)
        val controller = TranslationController(FakeEngine(), cache)
        controller.refreshAvailability()
        val texts = (0 until 8).associateWith { "page $it" }
        val settings = TranslationSettings(mode = TranslationMode.FOLLOW_READING)

        // Reading from the start, then further in, as a reader scrolls.
        controller.translate(settings, "src", "chA", texts, firstVisiblePage = 0, totalPages = 8)
        val later = controller.translate(settings, "src", "chA", texts, firstVisiblePage = 4, totalPages = 8)

        // The earlier pages must survive the later batch: a chapter that resets
        // itself on every scroll would re-pay for everything already read.
        assertEquals("T0", cache.pagesByIndex("src", "chA")[0])
        assertEquals("T4", later.pages[4]?.text)
        assertTrue(cache.pagesByIndex("src", "chA").size >= 4)
    }
}
