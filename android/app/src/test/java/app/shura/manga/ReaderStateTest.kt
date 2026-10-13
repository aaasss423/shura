package app.shura.manga

import app.shura.manga.reader.MemoryStore
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationMode
import app.shura.manga.reader.translation.TranslationSettings
import app.shura.manga.reader.translation.ReaderTranslationPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The reader's own state, off-device.
 *
 * The parts that are pure decision logic - which page to reopen at, which
 * pages a mode wants translated, what the cache is allowed to hold - are
 * exactly the parts that are worth pinning, because they are what silently
 * loses a reader's place or re-spends their data.
 */
class ReaderStateTest {

    /** Each test gets its own store: they share one file on a real device. */
    private val memory = MemoryStore()

    // -- reading progress --------------------------------------------------

    @Test
    fun `progress is kept per chapter not globally`() {
        val store = ReadingProgressStore(memory)
        store.clearHistory()
        store.save(ReadingProgress("src", "One Piece", "ch1", "Chapter 1", 7, 20, 1))
        store.save(ReadingProgress("src", "One Piece", "ch2", "Chapter 2", 3, 20, 2))

        // A global "last page" would have moved ch1 to ch2's page.
        assertEquals(7, store.pageFor("src", "One Piece", "ch1", 20))
        assertEquals(3, store.pageFor("src", "One Piece", "ch2", 20))
    }

    @Test
    fun `a stored page beyond the chapter is clamped rather than crashing`() {
        val store = ReadingProgressStore(memory)
        store.save(ReadingProgress("s", "M", "c", "C", 99, 5, 1))
        assertEquals(4, store.pageFor("s", "M", "c", 5))
        assertEquals(0, store.pageFor("s", "M", "other", 0))
    }

    @Test
    fun `an unread chapter starts at the first page`() {
        val store = ReadingProgressStore(memory)
        assertEquals(0, store.pageFor("s", "M", "never-opened", 30))
        assertFalse(store.hasProgress("s", "M", "never-opened"))
    }

    @Test
    fun `history is newest first and does not grow without bound`() {
        val store = ReadingProgressStore(memory)
        store.clearHistory()
        // More entries than the cap, written oldest last.
        (1..40).forEach { n ->
            store.save(ReadingProgress("s", "M", "c$n", "Chapter $n", 0, 10, n.toLong()))
        }
        val history = store.history()
        assertTrue("history is unbounded: ${history.size}", history.size <= 24)
        assertEquals("c40", history.first().chapterId)
    }

    @Test
    fun `reopening a chapter moves it to the front instead of duplicating it`() {
        val store = ReadingProgressStore(memory)
        store.clearHistory()
        store.save(ReadingProgress("s", "M", "a", "A", 0, 10, 1))
        store.save(ReadingProgress("s", "M", "b", "B", 0, 10, 2))
        store.save(ReadingProgress("s", "M", "a", "A", 5, 10, 3))
        val history = store.history()
        assertEquals(2, history.size)
        assertEquals("a", history.first().chapterId)
        assertEquals(5, history.first().page)
    }

    // -- translation cache -------------------------------------------------

    @Test
    fun `the cache holds one chapter and releases the previous one`() {
        // Capacity one is the whole point: a reader moving forward translates
        // once and does not accumulate every chapter they ever opened.
        val cache = TranslationCache(memory, capacity = 1)
        cache.clear()
        cache.store("src", "ch1", listOf("a", "b"))
        cache.store("src", "ch2", listOf("c", "d"))

        assertEquals(listOf("c", "d"), cache.pagesFor("src", "ch2"))
        assertTrue("the previous chapter is still cached", cache.pagesFor("src", "ch1").isEmpty())
    }

    @Test
    fun `an empty page list clears rather than caching a blank chapter`() {
        val cache = TranslationCache(memory, capacity = 1)
        cache.clear()
        cache.store("src", "ch1", listOf("a"))
        cache.store("src", "ch1", emptyList())
        assertFalse(cache.contains("src", "ch1"))
    }

    @Test
    fun `cache lookups are per source so two sources cannot collide`() {
        val cache = TranslationCache(memory, capacity = 2)
        cache.clear()
        cache.store("srcA", "ch1", listOf("from-a"))
        cache.store("srcB", "ch1", listOf("from-b"))
        assertEquals(listOf("from-a"), cache.pagesFor("srcA", "ch1"))
        assertEquals(listOf("from-b"), cache.pagesFor("srcB", "ch1"))
    }

    // -- translation modes -------------------------------------------------

    @Test
    fun `follow reading asks for a bounded look-ahead, not the whole chapter`() {
        val plan = ReaderTranslationPlanner.pagesToTranslate(
            TranslationSettings(mode = TranslationMode.FOLLOW_READING),
            totalPages = 50, firstVisible = 10, alreadyTranslated = emptySet(),
        )
        assertTrue("look-ahead is unbounded", plan.size <= 3)
        assertEquals(listOf(10, 11, 12), plan)
    }

    @Test
    fun `full mode wants the whole chapter but never re-asks for what is done`() {
        val settings = TranslationSettings(mode = TranslationMode.FULL)
        assertEquals((0 until 5).toList(),
            ReaderTranslationPlanner.pagesToTranslate(settings, 5, 0, emptySet()))
        assertEquals(listOf(3, 4),
            ReaderTranslationPlanner.pagesToTranslate(settings, 5, 0, setOf(0, 1, 2)))
    }

    @Test
    fun `off mode translates nothing and survives an empty chapter`() {
        val off = TranslationSettings(mode = TranslationMode.OFF)
        assertTrue(ReaderTranslationPlanner.pagesToTranslate(off, 20, 5, emptySet()).isEmpty())
        assertTrue(ReaderTranslationPlanner.pagesToTranslate(off, 0, 0, emptySet()).isEmpty())
    }
}
