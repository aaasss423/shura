package app.shura.manga

import app.shura.manga.ui.ChapterNavigation
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stepping between chapters.
 *
 * This is the rule the reader's two arrows depend on, and it is where the
 * boundaries live. Both were wrong before: the arrows were rendered disabled
 * because the reader was handed an empty chapter list, and the sign convention
 * was never checked against the order sources actually return.
 *
 * The list here is newest-first, which is what every source in the project
 * returns and therefore the order they are read in. Reading forward moves up
 * the list: "next" from c3 is c2, and "previous" from c1 is c2.
 */
class ChapterNavigationTest {

    private fun chapter(id: String, number: Float? = null) = Chapter(id, id, number)

    // Newest first: 3, 2, 1.
    private val newestFirst = listOf(chapter("c3", 3f), chapter("c2", 2f), chapter("c1", 1f))

    // -- both directions ----------------------------------------------------

    @Test
    fun `next steps forward through the reading order`() {
        assertEquals("c2", ChapterNavigation.next(newestFirst, "c3")?.id)
        assertEquals("c1", ChapterNavigation.next(newestFirst, "c2")?.id)
    }

    @Test
    fun `previous steps back through the reading order`() {
        assertEquals("c2", ChapterNavigation.previous(newestFirst, "c1")?.id)
        assertEquals("c3", ChapterNavigation.previous(newestFirst, "c2")?.id)
    }

    @Test
    fun `stepping twice lands where stepping once twice lands`() {
        var current = "c3"
        current = ChapterNavigation.next(newestFirst, current)!!.id
        assertEquals("c1", ChapterNavigation.next(newestFirst, current)!!.id)
    }

    // -- the boundaries -----------------------------------------------------

    @Test
    fun `there is no next after the oldest chapter`() {
        // Reading forward walks c3 -> c2 -> c1, so the end is c1.
        assertNull(ChapterNavigation.next(newestFirst, "c1"))
        assertFalse(ChapterNavigation.hasNext(newestFirst, "c1"))
    }

    @Test
    fun `there is no previous before the newest chapter`() {
        assertNull(ChapterNavigation.previous(newestFirst, "c3"))
        assertFalse(ChapterNavigation.hasPrevious(newestFirst, "c3"))
    }

    @Test
    fun `a single chapter has neither neighbour`() {
        val one = listOf(chapter("only"))
        assertNull(ChapterNavigation.next(one, "only"))
        assertNull(ChapterNavigation.previous(one, "only"))
        assertFalse(ChapterNavigation.hasNext(one, "only"))
        assertFalse(ChapterNavigation.hasPrevious(one, "only"))
    }

    @Test
    fun `an empty list has no neighbours rather than throwing`() {
        assertNull(ChapterNavigation.next(emptyList(), "anything"))
        assertNull(ChapterNavigation.previous(emptyList(), "anything"))
        // A library entry carries no chapter list at all; that must not crash
        // the reader's bar.
        assertFalse(ChapterNavigation.hasNext(emptyList(), "anything"))
    }

    @Test
    fun `a chapter that is not in the list has no neighbours`() {
        // The source reorganised, or the entry came from the library. Guessing
        // would move the reader somewhere they did not ask to go.
        assertNull(ChapterNavigation.next(newestFirst, "not-in-list"))
        assertNull(ChapterNavigation.previous(newestFirst, "not-in-list"))
    }

    @Test
    fun `availability matches the neighbour it gates`() {
        for (chapter in newestFirst) {
            assertEquals(
                "hasNext disagrees with next for ${chapter.id}",
                ChapterNavigation.next(newestFirst, chapter.id) != null,
                ChapterNavigation.hasNext(newestFirst, chapter.id))
            assertEquals(
                "hasPrevious disagrees with previous for ${chapter.id}",
                ChapterNavigation.previous(newestFirst, chapter.id) != null,
                ChapterNavigation.hasPrevious(newestFirst, chapter.id))
        }
    }

    // -- the list the reader is handed --------------------------------------

    @Test
    fun `every returned neighbour is a member of the list it came from`() {
        // The reader only ever opens what the source returned; a fabricated
        // chapter would be a chapter that does not exist.
        for (chapter in newestFirst) {
            listOf(ChapterNavigation.next(newestFirst, chapter.id),
                ChapterNavigation.previous(newestFirst, chapter.id))
                .filterNotNull()
                .forEach { assertTrue("${it.id} is not in the list", it in newestFirst) }
        }
    }

    @Test
    fun `a non-empty list yields at least one usable step from its middle`() {
        val middle = newestFirst[1].id
        assertTrue(ChapterNavigation.hasNext(newestFirst, middle))
        assertTrue(ChapterNavigation.hasPrevious(newestFirst, middle))
    }

    @Test
    fun `an offset of zero does not move`() {
        assertEquals("c2", ChapterNavigation.neighbour(newestFirst, "c2", 0)?.id)
    }
}
