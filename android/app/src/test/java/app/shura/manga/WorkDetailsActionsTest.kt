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
 */
class WorkDetailsActionsTest {

    private fun chapter(id: String, number: Float? = null) = Chapter(id, "Chapter $id", number)

    /** Newest first, which is how both chapter lists in the app open. */
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
    fun `the next five start at the newest chapter and take five`() {
        val batch = BatchDownload.take(numbered(12), BatchState(), limit = 5)
        assertEquals(listOf("c12", "c11", "c10", "c9", "c8"), batch.map { it.id })
    }

    @Test
    fun `the next ten take ten`() {
        val batch = BatchDownload.take(numbered(12), BatchState(), limit = 10)
        assertEquals(10, batch.size)
        assertEquals("c12", batch.first().id)
    }

    @Test
    fun `the rest takes everything that is left, however many that is`() {
        assertEquals(12, BatchDownload.take(numbered(12), BatchState(), limit = null).size)
    }

    @Test
    fun `a batch starts after the newest chapter the reader has finished`() {
        val visible = numbered(12)
        val batch = BatchDownload.take(visible, BatchState(read = readOf("c12", "c11", "c10")), limit = 5)
        assertEquals(listOf("c9", "c8", "c7", "c6", "c5"), batch.map { it.id })
    }

    @Test
    fun `a batch skips chapters that are already saved or already queued`() {
        val visible = numbered(8)
        val batch = BatchDownload.take(
            visible,
            BatchState(read = readOf("c8"), downloaded = setOf("c7"), queued = setOf("c6")),
            limit = 5,
        )
        assertEquals(listOf("c5", "c4", "c3", "c2", "c1"), batch.map { it.id })
    }

    @Test
    fun `a work with nothing left to offer offers nothing`() {
        val visible = numbered(3)
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
        val visible = numbered(4)
        val allRead = BatchState(read = visible.map { it.id }.toSet())
        assertEquals(0, BatchDownload.take(visible, allRead, limit = null).size)
    }

    @Test
    fun `a second tap queues the chapters after the first, not the same ones again`() {
        val visible = numbered(12)
        val first = BatchDownload.take(visible, BatchState(), limit = 5)
        // The queue now holds the first batch, which is what the page is told.
        val afterFirst = BatchState(queued = first.map { it.id }.toSet())
        val second = BatchDownload.take(visible, afterFirst, limit = 5)

        assertEquals(listOf("c12", "c11", "c10", "c9", "c8"), first.map { it.id })
        assertEquals(listOf("c7", "c6", "c5", "c4", "c3"), second.map { it.id })
        assertTrue("the two taps overlap", first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `the last batch is smaller than the button says and then stops`() {
        val visible = numbered(12)
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
    }

    @Test
    fun `what the button offers is exactly what the list shows underneath it`() {
        val visible = ChapterListOrder.reading(numbered(6), ascending = false)
        val batch = BatchDownload.take(visible, BatchState(read = readOf("c6")), limit = 2)
        // The chapters follow the one already read, in the order displayed.
        assertEquals(listOf("c5", "c4"), batch.map { it.id })
        assertTrue("nothing offered may be hidden by a filter", batch.all { it in visible })
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