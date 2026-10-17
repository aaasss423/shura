package app.shura.manga

import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.BatchDownload
import app.shura.manga.ui.BatchState
import app.shura.manga.ui.ChapterListOrder
import app.shura.manga.ui.ChapterNavigation
import app.shura.manga.ui.DetailsResume
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a work's own page offers: three batch downloads, and one button that
 * either starts the work or resumes it.
 *
 * Both are decisions rather than plumbing, and both are made by pure functions
 * so they can be pinned here rather than by tapping a button on a device. The
 * cases that matter are the ones nobody would think to check by hand: the work
 * where every chapter is already read, and the second tap on the same button.
 *
 * The batch order is pinned here too, and pinned as reading order from chapter
 * one rather than as "whatever the list shows". A batch is not a view of the
 * chapter list: the list opens newest first because that is what an ongoing
 * series needs, while "download the next five" means the first five chapters of
 * the story. A test that only checked the batch against the visible list would
 * have passed for the newest-first behaviour and let the wrong one through.
 */
class WorkDetailsActionsTest {

    private fun chapter(id: String, number: Float? = null) = Chapter(id, "Chapter $id", number)

    /** Newest first, which is how the chapter list opens. */
    private fun numbered(count: Int): List<Chapter> =
        (count downTo 1).map { chapter("c$it", it.toFloat()) }

    private fun readOf(vararg ids: String) = ids.toSet()

    private fun progress(
        sourceId: String = "src",
        mangaId: String = "",
        chapterId: String,
        chapterName: String = "Chapter $chapterId",
        page: Int = 3,
        updatedAt: Long = 1_000L,
    ) = ReadingProgress(
        sourceId = sourceId,
        mangaTitle = "Some Title",
        mangaId = mangaId,
        chapterId = chapterId,
        chapterName = chapterName,
        page = page,
        pageCount = 20,
        updatedAt = updatedAt,
    )

    // -- the order the batch works in ---------------------------------------

    @Test
    fun `the list is ordered newest first, the way the chapter list shows it`() {
        val ordered = ChapterListOrder.reading(numbered(3), ascending = false)
        assertEquals(listOf("c3", "c2", "c1"), ordered.map { it.id })
        assertEquals(listOf("c1", "c2", "c3"), ChapterListOrder.reading(numbered(3), ascending = true).map { it.id })
    }

    @Test
    fun `a chapter with no number is not treated as chapter zero`() {
        val ordered = ChapterListOrder.reading(
            listOf(chapter("extra"), chapter("c1", 1f), chapter("c2", 2f)),
            ascending = true,
        )
        assertEquals(listOf("c1", "c2", "extra"), ordered.map { it.id })
    }

    // -- next five, next ten, the rest --------------------------------------

    @Test
    fun `the next five start at chapter one and take five`() {
        // Nothing read, nothing saved: the batch is the first five chapters of
        // the story, NOT the five that arrived last. This is the whole point of
        // the change - a reader who picks a work up wants chapter one waiting.
        val batch = BatchDownload.take(
            BatchDownload.inReadingOrder(numbered(12)), BatchState(), limit = 5,
        )
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), batch.map { it.id })
    }

    @Test
    fun `a batch is not the newest five`() {
        val newestFive = ChapterListOrder.reading(numbered(12), ascending = false).take(5)
        val batch = BatchDownload.take(
            BatchDownload.inReadingOrder(numbered(12)), BatchState(), limit = 5,
        )
        assertEquals(listOf("c12", "c11", "c10", "c9", "c8"), newestFive.map { it.id })
        assertTrue(
            "the batch must not be the newest five",
            batch.map { it.id }.intersect(newestFive.map { it.id }.toSet()).isEmpty(),
        )
    }

    @Test
    fun `the next ten take ten`() {
        val batch = BatchDownload.take(
            BatchDownload.inReadingOrder(numbered(12)), BatchState(), limit = 10,
        )
        assertEquals(10, batch.size)
        assertEquals("c1", batch.first().id)
        assertEquals("c10", batch.last().id)
    }

    @Test
    fun `the rest takes everything that is left, however many that is`() {
        val batch = BatchDownload.take(
            BatchDownload.inReadingOrder(numbered(12)), BatchState(), limit = null,
        )
        assertEquals(12, batch.size)
        assertEquals("c1", batch.first().id)
    }

    @Test
    fun `a batch starts at the first chapter the reader has not finished`() {
        val visible = BatchDownload.inReadingOrder(numbered(12))
        val batch = BatchDownload.take(visible, BatchState(read = readOf("c1", "c2", "c3")), limit = 5)
        // Chapter one is already read, so the first unread one starts the run.
        assertEquals(listOf("c4", "c5", "c6", "c7", "c8"), batch.map { it.id })
    }

    @Test
    fun `with chapter one already read it starts at the first unread one`() {
        val visible = BatchDownload.inReadingOrder(numbered(12))
        val oneRead = BatchDownload.take(visible, BatchState(read = readOf("c1")), limit = 5)
        val twoRead = BatchDownload.take(visible, BatchState(read = readOf("c1", "c2")), limit = 5)
        val chapterGap = BatchDownload.take(visible, BatchState(read = readOf("c4")), limit = 3)

        assertEquals(listOf("c2", "c3", "c4", "c5", "c6"), oneRead.map { it.id })
        assertEquals(listOf("c3", "c4", "c5", "c6", "c7"), twoRead.map { it.id })
        // A chapter read in the middle leaves a hole behind it rather than
        // moving the start: chapter one is still the beginning of the story.
        assertEquals(listOf("c1", "c2", "c3"), chapterGap.map { it.id })
    }

    @Test
    fun `a batch skips chapters that are already saved or already queued`() {
        val visible = BatchDownload.inReadingOrder(numbered(8))
        val batch = BatchDownload.take(
            visible,
            BatchState(read = readOf("c1"), downloaded = setOf("c2"), queued = setOf("c3")),
            limit = 5,
        )
        assertEquals(listOf("c4", "c5", "c6", "c7", "c8"), batch.map { it.id })
    }

    @Test
    fun `a work with nothing left to offer offers nothing`() {
        val visible = BatchDownload.inReadingOrder(numbered(3))
        val spent = BatchState(read = setOf("c3"), downloaded = setOf("c2"), queued = setOf("c1"))
        assertTrue(BatchDownload.pending(visible, spent).isEmpty())
        assertTrue(BatchDownload.take(visible, spent, 5).isEmpty())
        assertTrue(BatchDownload.take(visible, spent, 10).isEmpty())
        assertTrue(BatchDownload.take(visible, spent, null).isEmpty())
    }

    @Test
    fun `a fully read work is not downloaded again`() {
        // The failure this guards: with nothing to do, returning the whole list
        // would re-download a finished series the first time anyone taps.
        val visible = BatchDownload.inReadingOrder(numbered(4))
        val allRead = BatchState(read = visible.map { it.id }.toSet())
        assertEquals(0, BatchDownload.take(visible, allRead, limit = null).size)
    }

    @Test
    fun `a second tap queues the chapters after the first, not the same ones again`() {
        val visible = BatchDownload.inReadingOrder(numbered(12))
        val first = BatchDownload.take(visible, BatchState(), limit = 5)
        // The queue now holds the first batch, which is what the page is told.
        val afterFirst = BatchState(queued = first.map { it.id }.toSet())
        val second = BatchDownload.take(visible, afterFirst, limit = 5)

        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), first.map { it.id })
        assertEquals(listOf("c6", "c7", "c8", "c9", "c10"), second.map { it.id })
        assertTrue("the two taps overlap", first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `the last batch is smaller than the button says and then stops`() {
        val visible = BatchDownload.inReadingOrder(numbered(12))
        var queued = emptySet<String>()
        var sizes = mutableListOf<Int>()
        while (true) {
            val batch = BatchDownload.take(visible, BatchState(queued = queued), limit = 5)
            if (batch.isEmpty()) break
            sizes += batch.size
            queued = queued + batch.map { it.id }
        }
        assertEquals(listOf(5, 5, 2), sizes)
        assertEquals(visible.size, queued.size)
        // The whole run is the work from chapter one to the end, with no gaps.
        assertEquals((1..12).map { "c$it" }, queued.sortedBy { it.drop(1).toInt() })
    }

    @Test
    fun `the batch walks reading order even though the chapter list shows newest first`() {
        // The two orders are deliberate, not a disagreement to be fixed: the list
        // opens on the newest update, the batch starts where the story does.
        val newestFirst = ChapterListOrder.reading(numbered(6), ascending = false)
        val batch = BatchDownload.take(
            BatchDownload.inReadingOrder(newestFirst), BatchState(read = readOf("c1")), limit = 2,
        )
        assertEquals(listOf("c6", "c5", "c4", "c3", "c2", "c1"), newestFirst.map { it.id })
        assertEquals(listOf("c2", "c3"), batch.map { it.id })
        // Nothing is invented and nothing is hidden: the same chapters either way.
        assertTrue("the batch offers chapters of this work only", batch.all { it in newestFirst })
    }

    @Test
    fun `an empty chapter list offers nothing rather than throwing`() {
        assertTrue(BatchDownload.take(emptyList(), BatchState(), limit = 5).isEmpty())
        assertTrue(BatchDownload.pending(emptyList(), BatchState()).isEmpty())
    }

    // -- Start: nothing read yet -------------------------------------------

    @Test
    fun `with no history the button says Start and opens chapter one`() {
        val chapters = ChapterListOrder.reading(numbered(30), ascending = false)
        val target = DetailsResume.target(resume = null, chapters = chapters)

        assertNotNull("Start needs a chapter to open", target)
        assertEquals("c1", target!!.chapter.id)
        assertFalse(DetailsResume.isContinuing(null))
    }

    @Test
    fun `Start opens chapter one whether or not the source lists it first`() {
        // The built-in source asks for chapters ascending and extensions hand
        // back whatever their site returned, so "the first chapter" cannot mean
        // the first row: on a 30-chapter work that would open chapter 30.
        val newestFirst = ChapterListOrder.reading(numbered(30), ascending = false)
        assertEquals("c30", newestFirst.first().id)
        assertEquals("c1", DetailsResume.target(resume = null, chapters = newestFirst)!!.chapter.id)

        // The other way round, where the first row is chapter one after all.
        val oldestFirst = ChapterListOrder.reading(numbered(30), ascending = true)
        assertEquals("c1", oldestFirst.first().id)
        assertEquals("c1", DetailsResume.target(resume = null, chapters = oldestFirst)!!.chapter.id)
    }

    @Test
    fun `a started chapter arrives with the whole list, not without it`() {
        // Without the list the reader has no arrows at all, which is the thing a
        // resumed or started chapter is most often opened for.
        val chapters = ChapterListOrder.reading(numbered(4), ascending = false)
        val target = DetailsResume.target(resume = null, chapters = chapters)!!
        assertEquals(chapters, target.chapters)
        assertTrue(target.locatedInList)
        // Chapter one is the far end of a newest-first list: one way out of it,
        // and not two.
        assertNotNull(ChapterNavigation.previous(target.chapters, target.chapter.id))
        assertNull(ChapterNavigation.next(target.chapters, target.chapter.id))
    }

    @Test
    fun `a work with no chapters cannot offer Start`() {
        assertNull(DetailsResume.target(resume = null, chapters = emptyList()))
    }

    // -- Continue: something read -------------------------------------------

    @Test
    fun `with history the button says Continue and opens the chapter last read`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val resume = DetailsResume.hintFor(
            listOf(progress(chapterId = "c4", updatedAt = 5_000L)),
            sourceId = "src",
            mangaId = "",
            chapters = chapters,
        )
        val target = DetailsResume.target(resume, chapters)

        assertTrue(DetailsResume.isContinuing(resume))
        assertNotNull("Continue needs a chapter", target)
        assertEquals("c4", target!!.chapter.id)
        // The reader opens the work's page the reader reached, not the top of
        // the list and not the first unread chapter.
        assertFalse(target.chapter.id == chapters.first().id)
    }

    @Test
    fun `a continued chapter keeps both arrows`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val target = DetailsResume.target(progress(chapterId = "c4"), chapters)!!
        assertNotNull(ChapterNavigation.previous(target.chapters, target.chapter.id))
        assertNotNull(ChapterNavigation.next(target.chapters, target.chapter.id))
    }

    @Test
    fun `the most recently read chapter is the one that is continued`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val history = listOf(
            progress(chapterId = "c2", updatedAt = 100L),
            progress(chapterId = "c7", updatedAt = 900L),
            progress(chapterId = "c3", updatedAt = 300L),
        )
        val resume = DetailsResume.hintFor(history, "src", "", chapters)
        assertEquals("c7", resume?.chapterId)
        assertEquals("c7", DetailsResume.target(resume, chapters)?.chapter?.id)
    }

    @Test
    fun `another work's reading is not continued on this one`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val elsewhere = listOf(
            progress(sourceId = "another-source", chapterId = "c3", updatedAt = 900L),
            // A chapter id that is not in this work's list, from a work whose id
            // is known and is not this one.
            progress(mangaId = "another-manga", chapterId = "gone", updatedAt = 800L),
        )
        assertNull(DetailsResume.hintFor(elsewhere, "src", "this-manga", chapters))
        // Nothing to continue, so the page says Start rather than Continue.
        assertFalse(
            DetailsResume.isContinuing(
                DetailsResume.hintFor(elsewhere, "src", "this-manga", chapters),
            ),
        )
    }

    @Test
    fun `an entry that names this work is continued even if the source dropped the chapter`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val resume = DetailsResume.hintFor(
            listOf(progress(mangaId = "this-manga", chapterId = "removed-chapter")),
            "src", "this-manga", chapters,
        )
        assertEquals("removed-chapter", resume?.chapterId)
    }

    @Test
    fun `another work's chapter id does not make its page this work's page`() {
        // Sources are not careful about ids, so two works can offer the same one.
        // The entry belongs to the other work, and the page it saved is that
        // chapter's, so it must not be resumed here.
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val history = listOf(
            progress(mangaId = "another-manga", chapterId = "c4", updatedAt = 900L),
            // The same chapter, opened from Discover, which records no work id.
            progress(mangaId = "", chapterId = "c6", updatedAt = 800L),
        )
        assertEquals("c6", DetailsResume.hintFor(history, "src", "this-manga", chapters)?.chapterId)
        assertNull(DetailsResume.hintFor(history.take(1), "src", "this-manga", chapters))
    }

    @Test
    fun `an entry with no chapter id is not a place to resume to`() {
        val chapters = ChapterListOrder.reading(numbered(3), ascending = false)
        assertNull(DetailsResume.hintFor(listOf(progress(chapterId = "")), "src", "", chapters))
    }

    // -- when the chapter list is unavailable -------------------------------

    @Test
    fun `with no history and no chapter list there is nothing to offer`() {
        // No progress to continue and no way to name chapter one: the button is
        // absent rather than a dead one.
        assertNull(DetailsResume.target(resume = null, chapters = null))
    }

    @Test
    fun `with history and no chapter list Continue still opens the chapter`() {
        val resume = progress(chapterId = "c7", chapterName = "Chapter 7")
        val target = DetailsResume.target(resume, chapters = null)

        assertNotNull("history already names the chapter", target)
        assertEquals("c7", target!!.chapter.id)
        assertEquals("Chapter 7", target.chapter.name)
        // No list, so no arrows - the same trade every other resume path makes,
        // rather than stepping into a list that could not be established.
        assertTrue(target.chapters.isEmpty())
        assertFalse(target.locatedInList)
        assertNull(ChapterNavigation.next(target.chapters, target.chapter.id))
    }

    @Test
    fun `a continued chapter uses the name and number the source reports now`() {
        val chapters = ChapterListOrder.reading(numbered(10), ascending = false)
        val target = DetailsResume.target(progress(chapterId = "c4", chapterName = "stale"), chapters)!!
        assertEquals("Chapter c4", target.chapter.name)
        assertEquals(4f, target.chapter.number!!, 0f)
    }

    @Test
    fun `a chapter the source reorganised away opens with no arrows`() {
        val chapters = ChapterListOrder.reading(numbered(5), ascending = false)
        val target = DetailsResume.target(progress(chapterId = "vanished"), chapters)!!

        assertEquals("vanished", target.chapter.id)
        assertFalse(target.locatedInList)
        assertTrue(target.chapters.isEmpty())
    }
}