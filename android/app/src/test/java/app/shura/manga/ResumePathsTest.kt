package app.shura.manga

import app.shura.manga.ui.ChapterNavigation
import app.shura.manga.ui.ChapterResume
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuing a chapter and opening a stored one.
 *
 * Both are *resume* paths, and both differ from opening a chapter by tapping it:
 * the reader already has a chapter but not the list around it. Before this, both
 * opened the reader with an empty list, so previous and next had nothing to step
 * through and were silently absent - the one thing a resumed chapter is most
 * likely to be used for.
 *
 * The rule these pin: the chapter is located in the *fetched* list, not assumed
 * to be there, and the list is only handed over when the chapter is genuinely
 * in it.
 */
class ResumePathsTest {

    private fun chapter(id: String, number: Float? = null) = Chapter(id, "Chapter $number", number)
    private val fetched = listOf(chapter("c3", 3f), chapter("c2", 2f), chapter("c1", 1f))

    // -- Continue reading ---------------------------------------------------

    @Test
    fun `continue locates the chapter in the fetched list and hands it over`() {
        val stored = Chapter("c2", "Chapter 2", 2f)
        val resumed = ChapterResume.resolve(stored, fetched)

        assertTrue("the chapter was not located", resumed.locatedInList)
        assertEquals(fetched, resumed.chapters)
        // The fetched copy wins: it carries what the source reports *now*.
        assertEquals(fetched.first { it.id == "c2" }.name, resumed.chapter.name)
        assertNull(resumed.failure)
    }

    @Test
    fun `a continued chapter has both arrows restored`() {
        val resumed = ChapterResume.resolve(chapter("c2", 2f), fetched)
        assertNotNull("previous is missing on a continued chapter",
            ChapterNavigation.previous(resumed.chapters, resumed.chapter.id))
        assertNotNull("next is missing on a continued chapter",
            ChapterNavigation.next(resumed.chapters, resumed.chapter.id))
    }

    @Test
    fun `a continued chapter at the end of the list keeps only the arrow that exists`() {
        val oldest = ChapterResume.resolve(chapter("c1", 1f), fetched)
        assertTrue(oldest.locatedInList)
        assertNull("there is nothing after the oldest chapter",
            ChapterNavigation.next(oldest.chapters, oldest.chapter.id))
        assertNotNull(ChapterNavigation.previous(oldest.chapters, oldest.chapter.id))
    }

    @Test
    fun `the continued chapter is the one the reader actually shows`() {
        // Both list entries carry the same id; the one resolved must be the
        // resolved one, not the caller's stale copy.
        val stored = Chapter("c2", "stale name", null)
        val resumed = ChapterResume.resolve(stored, fetched)
        assertEquals(resumed.chapter,
            fetched.first { it.id == "c2" })
        assertFalse(resumed.chapter.name == "stale name")
    }

    // -- when the list cannot be established --------------------------------

    @Test
    fun `a chapter missing from the fetched list opens without arrows`() {
        // The source reorganised. The reader still opens the chapter it was
        // asked for, but offers no navigation into a list it is not part of.
        val resumed = ChapterResume.resolve(chapter("gone", 9f), fetched)
        assertFalse(resumed.locatedInList)
        assertTrue(resumed.chapters.isEmpty())
        assertNull(ChapterNavigation.next(resumed.chapters, resumed.chapter.id))
        // The chapter itself is preserved rather than dropped.
        assertEquals("gone", resumed.chapter.id)
    }

    @Test
    fun `a source that could not be reached is reported rather than silently empty`() {
        val resumed = ChapterResume.resolve(chapter("c2", 2f), null)
        assertFalse(resumed.locatedInList)
        assertTrue(resumed.chapters.isEmpty())
        assertNotNull("the failure must be available to show", resumed.failure)
    }

    @Test
    fun `an empty fetched list is not treated as a located chapter`() {
        val resumed = ChapterResume.resolve(chapter("c2", 2f), emptyList())
        assertFalse(resumed.locatedInList)
        assertTrue(resumed.chapters.isEmpty())
    }

    // -- both paths agree ---------------------------------------------------

    @Test
    fun `resume and a fresh open produce the same reader state`() {
        // Tapping the chapter in the list and continuing to it must land on the
        // same thing, or "continue" would be a different reader.
        val tapped = ChapterResume.resolve(fetched[1], fetched)
        val continued = ChapterResume.resolve(Chapter("c2", "Chapter 2", 2f), fetched)
        assertEquals(tapped.chapters, continued.chapters)
        assertEquals(tapped.chapter.id, continued.chapter.id)
        assertEquals(
            ChapterNavigation.next(tapped.chapters, tapped.chapter.id)?.id,
            ChapterNavigation.next(continued.chapters, continued.chapter.id)?.id)
    }

    @Test
    fun `every chapter in the list resumes to something navigable`() {
        for (target in fetched) {
            val resumed = ChapterResume.resolve(target, fetched)
            assertTrue("${target.id} was not located", resumed.locatedInList)
            // Exactly the middle chapter has both directions; the ends have one.
            val directions = listOf(
                ChapterNavigation.next(resumed.chapters, target.id) != null,
                ChapterNavigation.previous(resumed.chapters, target.id) != null,
            ).count { it }
            assertTrue("$target.id had no direction at all", directions >= 1)
        }
    }
}
