package app.shura.manga

import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import app.shura.source.ShuraSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * What "تنزيل كل الفصول" actually enqueues.
 *
 * The batch that decides which chapters are left is pure and easy to get right:
 * [app.shura.manga.ui.BatchDownload]. The half that is not is what the queue does
 * with that list - it has a cap to not have, a duplicate filter to apply and
 * three sets to reconcile - and it is the half a reader sees as "it said 445
 * chapters and saved 40".
 *
 * So these drive the queue itself over a long work and count the rows that come
 * out of it.
 */
class DownloadQueueBatchTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * A scope whose coroutines are created and never run.
     *
     * Deliberate, and the reason this file needs no waiting: the question is
     * what the queue *enqueues*, and rows are added before any coroutine body
     * starts. Letting them run would make this a race - on a slow machine
     * fewer rows would be present at the moment of the assertion and the test
     * would fail for the wrong reason. Parking the dispatcher freezes exactly
     * the code under test and nothing else.
     */
    private object Parked : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // The block is dropped rather than queued: this test is about what the
            // queue put in its rows, and every row is added before any coroutine
            // body would run.
        }
    }

    private fun queue(store: File = folder.newFolder("store")): DownloadQueue = DownloadQueue(
        files = ChapterFiles(store = store, cache = folder.newFolder("cache")),
        scope = CoroutineScope(Parked),
        // Nothing is ever fetched: the parked scope never enters a download.
        downloader = { _, _, _ -> error("no page should be fetched in this test") },
    )

    private fun numbered(count: Int) = (1..count).map { Chapter("c$it", "Chapter $it", it.toFloat()) }

    /** A source with no pages, so any download that did start would fail loudly. */
    private fun source(id: String = "src") = object : ShuraSource {
        override val id = id
        override val name = "Source"
        override val language = "ar"
        override suspend fun search(query: String, page: Int) = emptyList<MangaSummary>()
        override suspend fun chapters(mangaId: String) = emptyList<Chapter>()
        override suspend fun pages(chapterId: String) = emptyList<Page>()
    }

    @Test
    fun `every remaining chapter of a long work is queued once, with no cap`() {
        val q = queue()
        val work = numbered(451)

        q.downloadAll(
            source = source(),
            mangaId = "m1",
            mangaTitle = "The Ghostly Doctor",
            chapters = work,
            onDisk = emptySet(),
        )

        val jobs = q.jobs.value
        assertEquals("the queue took only part of the work", 451, jobs.size)
        assertEquals("the same chapter was queued twice", 451, jobs.map { it.chapterId }.toSet().size)
        assertEquals(work.map { it.id }.toSet(), jobs.map { it.chapterId }.toSet())
        jobs.forEach {
            assertEquals("m1", it.mangaId)
            assertEquals("The Ghostly Doctor", it.mangaTitle)
        }
    }

    @Test
    fun `a chapter the source listed twice is queued once`() {
        val q = queue()

        q.downloadAll(
            source = source(),
            mangaId = "m1",
            mangaTitle = "A title",
            // A site whose own list repeats a chapter, or a paginated list whose
            // pages overlap: one chapter described twice is one download.
            chapters = numbered(10) + numbered(10),
        )

        assertEquals(10, q.jobs.value.size)
    }

    @Test
    fun `chapters already queued are not queued a second time`() {
        val q = queue()
        val work = numbered(12)

        // The same work offered twice: the second tap must not double the queue,
        // and must not skip anything either.
        q.downloadAll(source = source(), mangaId = "m1", mangaTitle = "A title", chapters = work.take(5))
        q.downloadAll(source = source(), mangaId = "m1", mangaTitle = "A title", chapters = work)

        assertEquals(12, q.jobs.value.size)
        assertEquals(work.map { it.id }, q.jobs.value.map { it.chapterId })
    }

    @Test
    fun `chapters already on the device are left out`() {
        val q = queue()

        q.downloadAll(
            source = source(), mangaId = "m1", mangaTitle = "A title",
            chapters = numbered(12),
            onDisk = setOf("c1", "c2"),
        )

        assertEquals((3..12).map { "c$it" }, q.jobs.value.map { it.chapterId })
    }

    @Test
    fun `a chapter whose name is the word null is not recorded as the word null`() {
        val q = queue()

        q.downloadAll(
            source = source(),
            mangaId = "m1",
            mangaTitle = "The Ghostly Doctor",
            chapters = listOf(Chapter("c451", "null", 451f)),
        )

        val job = q.jobs.value.single()
        assertFalse(job.chapterTitle.trim().equals("null", true))
        assertTrue(
            "the chapter's number was dropped: '${job.chapterTitle}'",
            job.chapterTitle.contains("451"),
        )
    }

    @Test
    fun `the queue keeps its rows in the order the batch offered them`() {
        val q = queue()

        q.downloadAll(
            source = source(), mangaId = "m1", mangaTitle = "A title",
            chapters = numbered(5),
        )

        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), q.jobs.value.map { it.chapterId })
    }

    @Test
    fun `a chapter already saved to the store is never offered again`() {
        val store = folder.newFolder("saved")
        val q = queue(store)
        // A real chapter on disk, with the manifest the store index reads.
        val page = File(folder.root, "c1-000.jpg").apply { writeText("page") }
        writeChapterManifest(
            store = store,
            sourceId = "src",
            mangaId = "m1",
            chapterId = "c1",
            title = "Chapter 1",
            files = listOf(page),
            mangaTitle = "A title",
        )
        assertTrue("the chapter was not written to the store", q.isDownloaded("src", "c1"))

        q.downloadAll(source = source(), mangaId = "m1", mangaTitle = "A title", chapters = numbered(4))

        assertFalse("an already saved chapter was offered again", q.jobs.value.any { it.chapterId == "c1" })
        assertEquals(listOf("c2", "c3", "c4"), q.jobs.value.map { it.chapterId })
    }
}
