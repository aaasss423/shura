package app.shura.manga.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's page counter.
 *
 * Two numbers a reader actually saw on a chapter of forty pages:
 *
 *  - **"1 / 1"** - the counter fell back to the number of pages that had
 *    *arrived* rather than the chapter's own count, so a chapter read one page
 *    into looked like a one-page chapter.
 *  - **"2 من 1"** - the page number was never bounded by the total, so any
 *    disagreement between the two printed a page above the end of the chapter.
 *
 * Both are decisions, not layout, so both are pinned here rather than looked at.
 */
class ReaderCounterTest {

    @Test
    fun `a chapter of forty does not read as one page because one has landed`() {
        // What the reader knows: the source said forty, one page has arrived.
        val counter = ReaderCounter.of(index = 0, known = 40, loaded = 1)
        assertEquals("1 / 40", counter.toString())
    }

    @Test
    fun `a chapter with no published total counts itself from what has loaded`() {
        // A stored chapter, or an import: nothing said how many pages there are,
        // and three of them are on the disk.
        assertEquals(3, ReaderCounter.total(known = null, loaded = 3))
        assertEquals(3, ReaderCounter.total(known = 0, loaded = 3))
        assertEquals("2 / 3", ReaderCounter.of(index = 1, known = null, loaded = 3).toString())
    }

    @Test
    fun `a chapter that has published nothing and loaded nothing still has a page`() {
        // Zero is not a page count. A reader looking at a page is looking at a
        // chapter with at least that one page, so the counter never says 0.
        assertEquals(1, ReaderCounter.total(known = null, loaded = 0))
        assertEquals(1, ReaderCounter.total(known = 0, loaded = 0))
        assertEquals(1, ReaderCounter.total(known = -5, loaded = 0))
    }

    @Test
    fun `the page never exceeds the total`() {
        // The index belongs to whatever list the reader is looking at, which can
        // be longer than the count that was published, or shorter than the place
        // the reader was at. Neither may produce "page 2 of 1".
        assertEquals(1, ReaderCounter.page(index = 1, total = 1))
        assertEquals(40, ReaderCounter.page(index = 99, total = 40))
        assertEquals("40 / 40", ReaderCounter.of(index = 99, known = 40, loaded = 1).toString())
    }

    @Test
    fun `a chapter that shrank under the reader does not leave it past the end`() {
        // A chapter still being written whose pages disappear, or a source that
        // reorganised under a reader mid-turn: the index the reader holds is
        // longer than the total it has. The page is clamped to the end, not
        // printed above it.
        assertEquals(3, ReaderCounter.page(index = 39, total = 3))
        assertEquals(3, ReaderCounter.of(index = 39, known = 3, loaded = 3).page)
        // And the larger of the two facts wins for the total, so a total that is
        // still larger than the loaded count never collapses mid-chapter.
        assertEquals(40, ReaderCounter.total(known = 40, loaded = 3))
        assertEquals(40, ReaderCounter.total(known = 3, loaded = 40))
    }

    @Test
    fun `the counter and the reading progress agree about the chapter`() {
        // Both are asked the same question about the same chapter, so a reopened
        // chapter cannot start on a page its own counter says does not exist.
        (1..60).forEach { pages ->
            val total = ReaderCounter.total(known = pages, loaded = 1)
            val saved = ReadingProgressStore(MemoryStore())
                .also { store ->
                    store.save(ReadingProgress("s", "M", "m1", "c1", "C", 59, pages, 1))
                }
                .pageFor("s", "M", "c1", total)
            assertTrue("page $saved is past the end of $pages", saved + 1 <= total)
        }
    }

    @Test
    fun `both numbers are never zero`() {
        for (known in listOf(null, 0, -1, 1, 7)) {
            for (loaded in listOf(0, 1, 3, 99)) {
                for (index in listOf(-5, 0, 2, 500)) {
                    val counter = ReaderCounter.of(index, known, loaded)
                    assertTrue("total was ${counter.total}", counter.total >= 1)
                    assertTrue("page was ${counter.page}", counter.page >= 1)
                    assertTrue(
                        "page ${counter.page} of ${counter.total}",
                        counter.page <= counter.total,
                    )
                }
            }
        }
    }
}
