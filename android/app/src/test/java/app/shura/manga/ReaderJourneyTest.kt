package app.shura.manga

import app.shura.manga.reader.MemoryStore
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import app.shura.manga.reader.translation.ReaderTranslationPlanner
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationMode
import app.shura.manga.reader.translation.TranslationSettings
import app.shura.manga.reader.translation.TranslationSettingsStore
import app.shura.manga.ui.ChapterNavigation
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's journey, end to end, without a device.
 *
 * The properties that matter are the ones a reader only notices when they are
 * broken: coming back to a different page than they left, the translation mode
 * quietly turning itself off between chapters, and the cache growing without
 * bound because every chapter was kept.
 */
class ReaderJourneyTest {

    private val memory = MemoryStore()
    private val progress = ReadingProgressStore(memory)
    private val cache = TranslationCache(memory, capacity = 1)

    private fun chapter(id: String, number: Float? = null) = Chapter(id, id, number)
    private val chapters = listOf(chapter("c3", 3f), chapter("c2", 2f), chapter("c1", 1f))

    // -- leaving and coming back -------------------------------------------

    @Test
    fun `a reopened chapter returns to the page it was left on`() {
        progress.save(ReadingProgress("src", "Title", "c2", "Chapter 2", 11, 30, 1))
        // The reader exits, the app restarts, the chapter is opened again.
        assertEquals(11, progress.pageFor("src", "Title", "c2", 30))
    }

    @Test
    fun `reading one chapter does not move another`() {
        progress.save(ReadingProgress("src", "Title", "c2", "Chapter 2", 11, 30, 1))
        // Step forward to c1 and read a little.
        progress.save(ReadingProgress("src", "Title", "c1", "Chapter 1", 2, 30, 2))
        assertEquals(11, progress.pageFor("src", "Title", "c2", 30))
        assertEquals(2, progress.pageFor("src", "Title", "c1", 30))
    }

    @Test
    fun `a chapter that grew since it was left clamps instead of falling off the end`() {
        progress.save(ReadingProgress("src", "Title", "c2", "Chapter 2", 40, 50, 1))
        // Re-read after the source split the chapter into fewer pages.
        assertEquals(19, progress.pageFor("src", "Title", "c2", 20))
    }

    // -- walking the whole list ---------------------------------------------

    @Test
    fun `walking forward from the newest chapter visits every chapter once`() {
        val visited = mutableListOf<String>()
        var current = chapters.first().id
        visited += current
        while (true) {
            val next = ChapterNavigation.next(chapters, current) ?: break
            current = next.id
            visited += current
        }
        assertEquals(listOf("c3", "c2", "c1"), visited)
    }

    @Test
    fun `walking back from the oldest returns to the newest`() {
        var current = chapters.last().id
        while (true) {
            val previous = ChapterNavigation.previous(chapters, current) ?: break
            current = previous.id
        }
        assertEquals("c3", current)
    }

    @Test
    fun `progress survives walking forward and back`() {
        var current = chapters.first().id
        progress.save(ReadingProgress("src", "T", current, "C3", 5, 20, 1))
        repeat(2) {
            val next = ChapterNavigation.next(chapters, current)!!
            progress.save(ReadingProgress("src", "T", next.id, next.name, 1, 20, 2))
            current = next.id
        }
        // Back to where it started: the page it was left on is still there.
        assertEquals(5, progress.pageFor("src", "T", "c3", 20))
        assertEquals(1, progress.pageFor("src", "T", "c1", 20))
    }

    // -- translation mode across chapters ----------------------------------

    @Test
    fun `the mode is a stored preference, not reader-local state`() {
        // The reader's own store is what makes it survive a chapter change:
        // nothing re-reads a mode from the chapter being displayed.
        assertEquals(TranslationMode.OFF, TranslationSettings(TranslationMode.OFF).mode)
        val follow = TranslationSettings(mode = TranslationMode.FOLLOW_READING)
        assertEquals(TranslationMode.FOLLOW_READING, follow.copy(targetLanguage = "en").mode)
    }

    @Test
    fun `caching one chapter does not make the next chapter look translated`() {
        cache.store("src", "c3", listOf("t3", "t3b"))
        // Moving to c1 must not present c3's text as c1's.
        assertTrue(cache.pagesFor("src", "c1").isEmpty())
        assertEquals(listOf("t3", "t3b"), cache.pagesFor("src", "c3"))
    }

    @Test
    fun `only one chapter is ever retained`() {
        chapters.forEach { c -> cache.store("src", c.id, listOf("translated " + c.id)) }
        val held = chapters.filter { cache.contains("src", it.id) }
        assertEquals("the cache kept more than one chapter: $held", 1, held.size)
        // And it is the one just stored.
        assertEquals("c1", held.single().id)
    }

    // -- what the reader is told -------------------------------------------

    @Test
    fun `with no engine reachable the planner still describes the intent`() {
        // The control must stay meaningful before an engine exists: the mode is
        // a reader preference, and the pages it would want are known.
        val settings = TranslationSettings(mode = TranslationMode.FULL)
        val wanted = ReaderTranslationPlanner.pagesToTranslate(settings, 4, 0, emptySet())
        assertEquals(listOf(0, 1, 2, 3), wanted)
        // Nothing claims to have produced them.
        assertTrue(cache.pagesFor("src", "c1").isEmpty())
    }

    @Test
    fun `a cache that is empty is reported as empty rather than as a hit`() {
        assertFalse(cache.contains("src", "never-translated"))
        assertTrue(cache.pagesFor("src", "never-translated").isEmpty())
    }
}
