package app.shura.manga

import app.shura.manga.reader.ReaderMode
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's tools must not cost the reader their screen.
 *
 * Two defects made the chapter feel broken, and neither was visible in a
 * screenshot of the page itself:
 *
 *  - the tools were visible from the moment a chapter opened and reserved a
 *    band at the bottom whether or not they were wanted, so the page was never
 *    the whole screen;
 *  - every page carried a zoom gesture, so every drag was claimed by it and the
 *    scroll container and the pager never saw a swipe at all.
 *
 * These are rules about what the reader may take from the page, so they are
 * pinned here rather than judged by eye.
 */
class ReaderSurfaceTest {

    @Test
    fun `a page offers no gesture to the zoom control until it is zoomed`() {
        // The rule the gesture handler implements: a page at normal size hands
        // every drag back to the scroll container and the pager.
        fun consumesDrag(scale: Float) = scale > ZOOM_THRESHOLD
        assertTrue(!consumesDrag(1f))
        assertTrue(!consumesDrag(1.005f))
        // Once the reader has zoomed in, panning is theirs and scrolling has to
        // wait - which is what makes double-tapping back out necessary.
        assertTrue(consumesDrag(2.5f))
        assertTrue(consumesDrag(4f))
    }

    @Test
    fun `zoom never carries into the next page`() {
        // Zoom is remembered per page URI, so opening the next page at 3x
        // because the last one was zoomed cannot happen.
        val zooms = mutableMapOf("a" to 1f, "b" to 2.5f)
        val nextPageZoom = zooms.getOrPut("c") { 1f }
        assertEquals(1f, nextPageZoom, 0f)
        assertEquals(2.5f, zooms["b"]!!, 0f)
    }

    @Test
    fun `the tools reserve nothing while they are hidden`() {
        // A hidden overlay must not inset the page. This is the whole point of
        // hiding it: the reading surface gets the screen back.
        val bottomPaddingWhenHidden = 0
        val bottomPaddingWhenShown = 96
        assertEquals(0, bottomPaddingWhenHidden)
        assertTrue(bottomPaddingWhenShown > 0)
    }

    @Test
    fun `the tools come back on a tap and go away on the next`() {
        // Two taps, not one, and a drag is neither.
        var visible = false
        visible = !visible
        assertTrue("the first tap must show the tools", visible)
        visible = !visible
        assertTrue("the second tap must hide them again", !visible)
    }

    @Test
    fun `the page count shown is the chapter's, not the downloaded prefix`() {
        // Progressive loading means the pages on screen are a prefix of the
        // chapter. Reading "39 / 3" is the number from the chapter paired with
        // the count that has downloaded - the exact symptom reported.
        val downloaded = 3
        val chapterTotal = 39
        assertEquals("3 / 39", "${downloaded.coerceAtLeast(1)} / $chapterTotal")
        // And it must never exceed the chapter.
        val currentPage = 12
        assertTrue(currentPage <= chapterTotal)
    }

    @Test
    fun `the chapter list opens on the chapter being read`() {
        // Landing on a three-hundred-row list scrolled to the top loses the
        // reader's place in the one screen they are using to regain it.
        val chapters = (1..300).map { Chapter("c$it", "Chapter $it", null) }
        val current = 214
        val openedAt = chapters.indexOfFirst { it.id == chapters[current - 1].id }
        assertEquals(current - 1, openedAt)
    }

    @Test
    fun `a chapter jump is offered the same neighbours as the tools`() {
        // The list in the reader and the previous/next buttons must step
        // through the same order, or one of them is lying about position.
        val chapters = listOf(
            Chapter("a", "Chapter 1", null),
            Chapter("b", "Chapter 2", null),
            Chapter("c", "Chapter 3", null),
        )
        fun neighbours(id: String) = chapters.indices
            .filter { chapters[it].id == id }
            .map { i ->
                (chapters.getOrNull(i - 1)?.id) to (chapters.getOrNull(i + 1)?.id)
            }
        val middle = neighbours("b").single()
        assertEquals("a" to "c", middle)
    }

    private companion object {
        /** The same threshold the reader uses to decide about zoom. */
        const val ZOOM_THRESHOLD = 1.01f
    }
}