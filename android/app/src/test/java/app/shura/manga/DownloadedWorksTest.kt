package app.shura.manga

import app.shura.manga.ui.ShuraScreen
import app.shura.manga.ui.openTargetFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The downloads screen is a list of books.
 *
 * This is the user's first complaint, and it is a question about what a row
 * *means*: five chapters were saved of one manga, and the tab offered five rows
 * and no way to see the book they belonged to. The fix is not a different label
 * on the same row - it is that the row is the work, that its count is the number
 * of chapters on the device, and that tapping it opens that work's chapters
 * rather than one of them.
 *
 * None of it needs a device, which is the point: the count a row claims and the
 * screen a tap opens cannot be checked by looking at a layout.
 */
class DownloadedWorksTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun stored(
        dir: File,
        sourceId: String = "src",
        mangaId: String = "m1",
        chapterId: String,
        title: String = "A title",
        coverUrl: String = "",
        pages: Int = 3,
    ): StoredChapter {
        val chapterDir = File(dir, chapterId).apply { mkdirs() }
        // A real file, because the list orders by when the chapter landed.
        File(chapterDir, "000.jpg").writeText("x".repeat(pages))
        return StoredChapter(
            dir = chapterDir,
            sourceId = sourceId,
            mangaId = mangaId,
            chapterId = chapterId,
            // What the store records: the chapter's own name and, beside it, the
            // work's. A screen that groups these has to be able to say which book
            // they belong to.
            title = "Chapter $chapterId",
            coverUrl = coverUrl,
            pageUris = emptyList(),
            mangaTitle = title,
        )
    }

    private fun job(
        sourceId: String = "src",
        mangaId: String = "m1",
        chapterId: String = "c1",
        mangaTitle: String = "A title",
        state: DownloadState = DownloadState.RUNNING,
        done: Int = 1,
        total: Int = 4,
    ) = DownloadJob(
        key = DownloadJob.keyOf(sourceId, chapterId),
        sourceId = sourceId,
        sourceName = "Source",
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterTitle = "Chapter $chapterId",
        coverUrl = "",
        total = total,
        done = done,
        state = state,
    )

    @Test
    fun `five saved chapters of one work are one row that says five`() {
        // The exact shape of the report: one manga, five chapters, five rows.
        val saved = (1..5).map { stored(folder.root, chapterId = "c$it") }
        val works = groupDownloads(saved, emptyList())

        assertEquals(1, works.size)
        assertEquals(5, works.single().localCount)
        assertEquals("m1", works.single().mangaId)
        assertEquals("A title", works.single().title)
    }

    @Test
    fun `two works are two rows however many chapters each has`() {
        val saved = listOf(
            stored(folder.root, mangaId = "m1", chapterId = "a1"),
            stored(folder.root, mangaId = "m1", chapterId = "a2"),
            stored(folder.root, mangaId = "m2", chapterId = "b1", title = "Another title"),
        )
        val works = groupDownloads(saved, emptyList())

        assertEquals(2, works.size)
        assertEquals(listOf(1, 2), works.map { it.localCount }.sorted())
    }

    @Test
    fun `the same title from two sources stays two works`() {
        val saved = listOf(
            stored(folder.root, sourceId = "one", mangaId = "shared", chapterId = "c1"),
            stored(folder.root, sourceId = "two", mangaId = "shared", chapterId = "c1"),
        )
        assertEquals(2, groupDownloads(saved, emptyList()).size)
    }

    @Test
    fun `a work with a download in flight is a row before it has saved anything`() {
        // The failure and the cancelled download are rows for the same reason: if
        // they were not, the only place a download could have gone wrong would be
        // a screen that says there were no downloads.
        val works = groupDownloads(
            emptyList(),
            listOf(
                job(chapterId = "running", state = DownloadState.RUNNING),
                job(chapterId = "queued", state = DownloadState.QUEUED),
                job(chapterId = "failed", state = DownloadState.FAILED),
            ),
        )
        assertEquals(1, works.size)
        assertEquals(3, works.single().jobs.size)
        assertEquals(2, works.single().activeCount)
        assertTrue(works.single().inProgress)
        assertEquals(1, works.single().failedCount)
        assertEquals(0, works.single().localCount)
    }

    @Test
    fun `a work with something running is above one with nothing running`() {
        val saved = listOf(
            stored(folder.root, mangaId = "calm", chapterId = "c1", title = "Calm"),
            stored(folder.root, mangaId = "busy", chapterId = "c2", title = "Busy"),
        )
        val works = groupDownloads(saved, listOf(job(mangaId = "busy", chapterId = "c3")))
        assertEquals("busy", works.first().mangaId)
    }

    @Test
    fun `nothing saved and nothing queued is no rows`() {
        assertTrue(groupDownloads(emptyList(), emptyList()).isEmpty())
    }

    // -- what a tap opens -----------------------------------------------------

    @Test
    fun `tapping a work opens its own chapter list, never a chapter`() {
        val saved = (1..5).map { stored(folder.root, chapterId = "c$it") }
        val work = groupDownloads(saved, emptyList()).single()

        val target = openTargetFor(work)
        assertTrue("expected the saved chapters screen, got $target", target is ShuraScreen.SavedChapters)
        target as ShuraScreen.SavedChapters
        assertEquals("src", target.sourceId)
        assertEquals("m1", target.mangaId)
        assertEquals("A title", target.title)
        // And never a reader, which is where a tap used to end up.
        assertFalse(target is ShuraScreen.Reader)
    }

    @Test
    fun `a work with nothing saved opens the source's chapter list instead`() {
        // There is no local list to open, and a screen that opened nothing would
        // be a dead control; the source's own list is where its chapters are.
        val target = openTargetFor(
            groupDownloads(emptyList(), emptyList()).firstOrNull() ?: DownloadedWork(
                "src", "m1", "A title", "", emptyList(), emptyList(),
            ),
        )
        assertTrue(target is ShuraScreen.Chapters)
        assertFalse(target is ShuraScreen.Reader)
        assertFalse(target is ShuraScreen.SavedChapters)
    }

    @Test
    fun `one work's saved chapters are exactly its own`() {
        val saved = listOf(
            stored(folder.root, mangaId = "m1", chapterId = "a1"),
            stored(folder.root, mangaId = "m1", chapterId = "a2"),
            stored(folder.root, mangaId = "m2", chapterId = "b1"),
        )
        assertEquals(
            listOf("a1", "a2"),
            storedChaptersOf(saved, "src", "m1").map { it.chapterId }.sorted(),
        )
        assertTrue(storedChaptersOf(saved, "src", "nobody").isEmpty())
    }

    @Test
    fun `the cover of a work is the one its chapters carry`() {
        val saved = listOf(
            stored(folder.root, mangaId = "m1", chapterId = "a1", coverUrl = ""),
            stored(folder.root, mangaId = "m1", chapterId = "a2", coverUrl = "https://cdn/cover.jpg"),
        )
        assertEquals("https://cdn/cover.jpg", groupDownloads(saved, emptyList()).single().coverUrl)
    }

    @Test
    fun `a job in flight is a thing that is active and a finished one is not`() {
        assertTrue(job(state = DownloadState.RUNNING).isActive)
        assertTrue(job(state = DownloadState.QUEUED).isActive)
        assertFalse(job(state = DownloadState.DONE).isActive)
        assertFalse(job(state = DownloadState.FAILED).isActive)
        assertFalse(job(state = DownloadState.CANCELLED).isActive)
    }
}
