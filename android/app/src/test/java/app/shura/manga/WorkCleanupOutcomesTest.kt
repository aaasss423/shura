package app.shura.manga

import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library's manage-downloads flow: two outcomes that must not blur.
 *
 * "Delete" used to mean one thing and had to keep meaning it, so the only button
 * offered removed the files. That leaves a reader who wants their shelf tidy with
 * no way to do it, and a reader who wants their disk back with no way to keep
 * following the series. People used "remove from library" and then found the
 * chapter still on disk - which is the failure this pins.
 *
 * Everything here runs against real files in a temporary folder, because the
 * claim is about what is and is not on the disk afterwards.
 */
class WorkCleanupOutcomesTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * A chapter written into the store for real, then read back as the app reads
     * it.
     *
     * `writeChapterManifest` rather than `persistStoredChapter`: the latter
     * hands back `file://` URIs, and `android.net.Uri` is a stub on the JVM that
     * throws. The bytes, the manifest and the directory are the same either way -
     * `persistStoredChapter` is a thin wrapper around this call.
     */
    private fun save(
        store: File,
        mangaId: String = "m1",
        chapterId: String,
        pages: Int = 3,
        title: String = "The Ghostly Doctor",
    ): StoredChapterRecord {
        val staged = File(folder.root, "$chapterId-page.jpg").apply { writeText("x".repeat(pages)) }
        val dir = writeChapterManifest(
            store = store,
            sourceId = "src",
            mangaId = mangaId,
            chapterId = chapterId,
            title = "Chapter $chapterId",
            files = listOf(staged),
            coverUrl = "",
            mangaTitle = title,
        )
        return indexStoredChapterRecords(dir.parentFile)
            .first { it.chapterId == chapterId && it.mangaId == mangaId }
    }

    /** The store's chapters in the shape the cleanup path takes them. */
    private fun stored(store: File): List<StoredChapter> =
        indexStoredChapterRecords(store).map { record ->
            StoredChapter(
                dir = record.dir,
                sourceId = record.sourceId,
                mangaId = record.mangaId,
                chapterId = record.chapterId,
                title = record.title,
                coverUrl = record.coverUrl,
                pageUris = emptyList(),
                mangaTitle = record.mangaTitle,
            )
        }

    private fun work(mangaId: String = "m1", title: String = "The Ghostly Doctor") =
        LibraryWork(sourceId = "src", mangaId = mangaId, title = title, coverUrl = "")

    private fun planFor(
        store: File,
        worksDir: File,
        work: LibraryWork,
        action: WorkCleanupAction,
    ): Pair<WorkCleanupPlan, DownloadedWork> {
        val downloaded = groupDownloads(stored(store), emptyList())
            .first { it.sourceId == work.sourceId && it.mangaId == work.mangaId }
        val plan = planWorkCleanup(downloaded, action, work)
        return plan to downloaded
    }

    // -- (a) delete the chapters, keep the work ---------------------------

    @Test
    fun `deleting the downloads takes the pages and leaves the work on the shelf`() {
        val store = folder.newFolder("chapters")
        val worksDir = folder.newFolder("works")
        storeWork(worksDir, work())
        val a = save(store, chapterId = "c1")
        val b = save(store, chapterId = "c2")

        val (plan, _) = planFor(store, worksDir, work(), WorkCleanupAction.DELETE_DOWNLOADS)
        val result = applyWorkCleanup(plan, worksDir, store)

        assertEquals("two chapters were asked for", 2, result.deletedChapters)
        assertEquals(listOf<String>(), result.failedChapters)
        assertFalse("the pages are still on the disk", a.dir.exists() || b.dir.exists())
        assertTrue("the space was not reported", result.deletedBytes > 0L)
        // The whole point: the work is still in the library.
        assertFalse("the work left the library", result.removedFromLibrary)
        assertEquals(1, listLibraryWorks(worksDir).size)
        assertTrue(isWorkInLibrary(worksDir, "src", "m1"))
    }

    // -- (b) remove the work, keep the chapters ---------------------------

    @Test
    fun `removing from the library takes the shelf entry and leaves the pages`() {
        val store = folder.newFolder("chapters")
        val worksDir = folder.newFolder("works")
        storeWork(worksDir, work())
        val a = save(store, chapterId = "c1")

        val (plan, _) = planFor(store, worksDir, work(), WorkCleanupAction.REMOVE_FROM_LIBRARY)
        val result = applyWorkCleanup(plan, worksDir, store)

        assertTrue("the removal was not reported", result.removedFromLibrary)
        assertEquals("chapters were deleted by a removal", 0, result.deletedChapters)
        assertEquals(0L, result.deletedBytes)
        assertTrue("the pages went with the shelf entry", a.dir.isDirectory)
        assertTrue(indexStoredChapterRecords(store).any { it.chapterId == "c1" })
        assertTrue("the shelf entry survived", listLibraryWorks(worksDir).isEmpty())
        assertFalse("the work is still on the shelf", isWorkInLibrary(worksDir, "src", "m1"))
    }

    // -- (c) both, which is a different choice again -----------------------

    @Test
    fun `deleting everything takes both, and only because it was asked for`() {
        val store = folder.newFolder("chapters")
        val worksDir = folder.newFolder("works")
        storeWork(worksDir, work())
        val a = save(store, chapterId = "c1")

        val (plan, _) = planFor(store, worksDir, work(), WorkCleanupAction.DELETE_EVERYTHING)
        val result = applyWorkCleanup(plan, worksDir, store)

        assertEquals(1, result.deletedChapters)
        assertTrue(result.removedFromLibrary)
        assertFalse("the pages are still on the disk", a.dir.exists())
        assertFalse(isWorkInLibrary(worksDir, "src", "m1"))
    }

    // -- the three are three, decided from the plan before anything is deleted

    @Test
    fun `the plan decides the blast radius before a single byte is touched`() {
        val store = folder.newFolder("chapters")
        val worksDir = folder.newFolder("works")
        storeWork(worksDir, work())
        save(store, chapterId = "c1")
        save(store, chapterId = "c2")
        // Another work entirely, which must be untouched by all three.
        save(store, mangaId = "m2", chapterId = "other", title = "Another title")
        val other = indexStoredChapterRecords(store).first { it.mangaId == "m2" }
        storeWork(worksDir, work(mangaId = "m2", title = "Another title"))

        val wanted = work()
        val downloaded = groupDownloads(stored(store), emptyList())
            .first { it.mangaId == wanted.mangaId }

        val delete = planWorkCleanup(downloaded, WorkCleanupAction.DELETE_DOWNLOADS, wanted)
        assertTrue(delete.deletesFiles)
        assertFalse(delete.removeFromLibrary)

        val remove = planWorkCleanup(downloaded, WorkCleanupAction.REMOVE_FROM_LIBRARY, wanted)
        assertFalse(remove.deletesFiles)
        assertTrue(remove.removeFromLibrary)

        val both = planWorkCleanup(downloaded, WorkCleanupAction.DELETE_EVERYTHING, wanted)
        assertTrue(both.deletesFiles)
        assertTrue(both.removeFromLibrary)

        // Only this work's chapters, whichever plan is run.
        listOf(delete, remove, both).forEach { plan ->
            assertEquals(
                "a plan named another work's chapter",
                setOf("c1", "c2"),
                plan.chapters.map { it.chapterId }.toSet(),
            )
        }
        assertTrue("another work's chapter was touched", other.dir.isDirectory)
        assertTrue("another work's shelf entry was touched", isWorkInLibrary(worksDir, "src", "m2"))
    }

    @Test
    fun `a cleanup that stops short says which chapters survived`() {
        val store = folder.newFolder("chapters")
        val worksDir = folder.newFolder("works")
        storeWork(worksDir, work())
        save(store, chapterId = "c1")

        val wanted = work()
        val downloaded = groupDownloads(stored(store), emptyList()).first { it.mangaId == "m1" }
        val plan = planWorkCleanup(downloaded, WorkCleanupAction.DELETE_DOWNLOADS, wanted)

        // A store the plan's chapters do not live under: the delete is refused
        // rather than followed, because a recursive delete of a path built from an
        // id is how one work's chapters take another's with them.
        val elsewhere = folder.newFolder("elsewhere")
        val result = applyWorkCleanup(plan, worksDir, elsewhere)

        assertEquals("a delete outside the store was carried out", 0, result.deletedChapters)
        assertEquals(listOf("c1"), result.failedChapters)
        assertTrue("the chapter was deleted anyway", File(store, "c1").isDirectory)
    }

    // -- the three answers the dialog offers are three different sentences

    @Test
    fun `the dialog says three different things in both languages`() {
        for (language in UiLanguage.entries) {
            val delete = Strings.t("library.cleanupDelete", language)
            val remove = Strings.t("library.cleanupRemove", language)
            val both = Strings.t("library.cleanupBoth", language)

            assertNotEquals(delete, remove)
            assertNotEquals(delete, both)
            assertNotEquals(remove, both)
            listOf(delete, remove, both).forEach {
                assertFalse("a button has no words of its own", it.startsWith("library."))
            }
            // Each says what it will do, so a confirmation can be read.
            listOf(
                "library.cleanupDeleteHelp", "library.cleanupRemoveHelp", "library.cleanupBothHelp",
            ).forEach { key ->
                val help = Strings.t(key, language)
                assertFalse("$key is missing", help.startsWith("library."))
                assertTrue("$key is empty", help.isNotBlank())
            }
        }
    }
}