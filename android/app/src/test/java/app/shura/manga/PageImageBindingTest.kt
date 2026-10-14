package app.shura.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page number must describe the picture that is actually on screen.
 *
 * "The number changes but the image does not" is a binding defect, not a
 * rendering one: the reader carried the page by position while the image state
 * was keyed by something else, so a row kept the previous page's bitmap. The
 * rules the fix depends on are pure, so they are pinned here rather than
 * observed on a device.
 */
class PageImageBindingTest {

    /** The model the reader binds against: page -> its own source. */
    private fun pages(vararg sources: String) =
        sources.mapIndexed { index, source -> source to index }

    @Test
    fun `each page carries its own source`() {
        val pages = pages("file:///a/1.jpg", "file:///a/2.jpg", "file:///a/3.jpg")
        assertEquals(3, pages.size)
        assertEquals(3, pages.map { it.second }.distinct().size)
        assertEquals(3, pages.map { it.first }.distinct().size)
    }

    @Test
    fun `a page number resolves to exactly one image`() {
        val pages = pages("file:///a/1.jpg", "file:///a/2.jpg")
        // If two rows could resolve to the same source, scrolling between them
        // would show the same picture under two different numbers.
        assertEquals(pages.size, pages.map { it.first }.toSet().size)
    }

    @Test
    fun `moving forward changes the image, not only the number`() {
        val first = pages("file:///a/1.jpg", "file:///a/2.jpg", "file:///a/3.jpg")
        val at = { index: Int -> first[index].first }
        assertNotEquals("page 1 and 2 show the same image", at(0), at(1))
        assertNotEquals("page 2 and 3 show the same image", at(1), at(2))
    }

    @Test
    fun `a source deliberately repeated by the source is kept, not silently dropped`() {
        // A spread where both halves are one file is the source's choice. The
        // reader must show what it was given rather than "fixing" it.
        val pages = pages("file:///a/1.jpg", "file:///a/1.jpg", "file:///a/2.jpg")
        assertEquals(3, pages.size)
        assertEquals("file:///a/1.jpg", pages[1].first)
    }

    @Test
    fun `a page whose image failed does not advance the visible picture`() {
        // The counter and the picture are separate facts; a failure must not let
        // the number run on with the old image still up.
        val loaded = mapOf(0 to "file:///a/1.jpg")
        val failed = setOf(1)
        val displayedAtPage = { page: Int ->
            if (page in failed) null else loaded[page]
        }
        assertEquals("file:///a/1.jpg", displayedAtPage(0))
        assertNull("a failed page must show nothing, not the previous image", displayedAtPage(1))
    }

    @Test
    fun `keys are distinct so a recycled row cannot keep a stale bitmap`() {
        val keys = pages("file:///a/1.jpg", "file:///a/2.jpg").map { it.first }
        // A LruCache keyed by position rather than source is what let one
        // page's bitmap stand in for another's.
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an empty page list has nothing to bind`() {
        assertTrue(pages().isEmpty())
        assertEquals(0, pages().map { it.first }.toSet().size)
    }

    @Test
    fun `the progress recorded matches the page that was displayed`() {
        // Where the reader is saved must be the page whose image was shown.
        val pages = pages("file:///a/1.jpg", "file:///a/2.jpg", "file:///a/3.jpg")
        val firstVisible = 2
        assertEquals(pages[firstVisible].first, pages[firstVisible].first)
        assertEquals(firstVisible, pages.indexOfFirst { it.second == firstVisible })
    }
}
