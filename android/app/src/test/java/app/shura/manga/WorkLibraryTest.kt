package app.shura.manga

import app.shura.manga.StoredChapter
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.screens.LibraryWork
import app.shura.manga.ui.screens.LibraryFilter
import app.shura.manga.ui.screens.libraryRows

import app.shura.manga.ui.screens.buildLibrary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Following a work, which is not the same thing as having its chapters.
 *
 * The button on a work's page was drawn and did nothing, with a comment admitting
 * it. These pin what it now does: adding is idempotent, removing is honest about
 * whether there was anything to remove, and a file that cannot be read costs one
 * entry rather than the whole library.
 */
class WorkLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun work(title: String = "A title", mangaId: String = "m1") =
        LibraryWork(sourceId = "s", mangaId = mangaId, title = title, coverUrl = "https://cdn/x.jpg")

    @Test
    fun `a work is stored and read back whole`() {
        val dir = folder.newFolder("works")
        storeWork(dir, work().copy(favorite = true, category = "Reading"))
        val stored = listLibraryWorks(dir)
        assertEquals(1, stored.size)
        assertEquals("A title", stored[0].title)
        assertEquals("https://cdn/x.jpg", stored[0].coverUrl)
        assertEquals("s", stored[0].sourceId)
        assertTrue(stored[0].favorite)
        assertEquals("Reading", stored[0].category)
    }

    @Test
    fun `older work records default to not favorite and no category`() {
        val file = File(folder.newFolder("works"), "legacy.json")
        file.writeText("""{"sourceId":"s","mangaId":"m1","title":"Legacy"}""")
        val stored = readWork(file)!!
        assertFalse(stored.favorite)
        assertNull(stored.category)
    }

    @Test
    fun `adding the same work twice leaves one entry`() {
        val dir = folder.newFolder("works")
        storeWork(dir, work())
        storeWork(dir, work())
        assertEquals(1, listLibraryWorks(dir).size)
        assertTrue(isWorkInLibrary(dir, "s", "m1"))
    }

    @Test
    fun `removing says whether there was anything to remove`() {
        val dir = folder.newFolder("works")
        storeWork(dir, work())
        assertTrue(removeWork(dir, work()))
        // A second tap on a row that is already gone must not claim a success.
        assertFalse(removeWork(dir, work()))
        assertFalse(isWorkInLibrary(dir, "s", "m1"))
    }

    @Test
    fun `two sources with the same title stay separate`() {
        val dir = folder.newFolder("works")
        storeWork(dir, work(mangaId = "shared-id"))
        storeWork(dir, work(mangaId = "shared-id").copy(sourceId = "other"))
        assertEquals(2, listLibraryWorks(dir).size)
    }

    @Test
    fun `a corrupt entry costs one row, not the library`() {
        val dir = folder.newFolder("works")
        storeWork(dir, work(mangaId = "good"))
        File(dir, "broken.json").writeText("{ not json")
        val stored = listLibraryWorks(dir)
        assertEquals(1, stored.size)
        assertEquals("good", stored[0].mangaId)
    }

    @Test
    fun `an entry without the fields needed to open it is not an entry`() {
        val dir = folder.newFolder("works")
        // No mangaId: there would be no way to fetch its chapters, so a row that
        // opens to nothing is worse than no row.
        File(dir, "half.json").writeText("""{"sourceId":"s","title":"Orphan"}""")
        assertEquals(0, listLibraryWorks(dir).size)
        assertNull(readWork(File(dir, "half.json")))
    }

    @Test
    fun `the newest work comes first`() {
        val dir = folder.newFolder("works")
        val older = work(mangaId = "older").copy(addedAt = 1_000L)
        val newer = work(mangaId = "newer").copy(addedAt = 2_000L)
        storeWork(dir, newer)
        storeWork(dir, older)
        assertEquals("newer", listLibraryWorks(dir)[0].mangaId)
    }
}
/**
 * The library tab has to show what the add button added.
 *
 * Adding a work used to write a file nothing ever read, so the button looked
 * broken. These pin that a followed work appears in the library list even with no
 * chapter on the device, and that opening one cannot reach for a chapter that
 * does not exist.
 */
class FollowedWorksInLibraryTest {

    private val history = emptyList<ReadingProgress>()

    @Test
    fun `a followed work appears in the library with no chapters`() {
        val followed = listOf(LibraryWork("s", "m1", "A title", "https://cdn/c.jpg"))
        val works = buildLibrary(emptyList(), history, followed)
        assertEquals(1, works.size)
        assertEquals("A title", works[0].title)
        assertTrue(works[0].followed)
        assertEquals(0, works[0].chapterCount)
    }

    @Test
    fun `a followed work with downloaded chapters is one row, not two`() {
        val folder = File(System.getProperty("java.io.tmpdir"), "shura-lib-${System.nanoTime()}")
        folder.mkdirs()
        try {
            val stored = listOf(
                StoredChapter(folder, "s", "m1", "c1", "A title", "", emptyList()),
            )
            val followed = listOf(LibraryWork("s", "m1", "A title", ""))
            val works = buildLibrary(stored, history, followed)
            assertEquals(1, works.size)
            assertTrue(works[0].followed)
            assertEquals(1, works[0].chapterCount)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `a followed work with nothing downloaded has no chapter to resume`() {
        // resumeChapter!! would have thrown here: the tap that opened a freshly
        // added work would have taken the process down.
        val followed = listOf(LibraryWork("s", "m1", "A title", ""))
        val work = buildLibrary(emptyList(), history, followed)[0]
        assertEquals(null, work.resumeChapter)
    }

    @Test
    fun `favorite and category metadata survive library grouping and filters`() {
        val followed = listOf(
            app.shura.manga.LibraryWork("s", "fav", "Favorite title", favorite = true, category = "Reading"),
            app.shura.manga.LibraryWork("s", "other", "Other title", category = "Later"),
        )
        val works = buildLibrary(emptyList(), history, followed)

        assertEquals(listOf("Favorite title"), libraryRows(
            works, "", app.shura.manga.ui.screens.LibrarySort.TITLE, LibraryFilter.Favorites,
        ).map { it.title })
        assertEquals(listOf("Other title"), libraryRows(
            works, "", app.shura.manga.ui.screens.LibrarySort.TITLE, LibraryFilter.Category("Later"),
        ).map { it.title })
        assertEquals(listOf("Favorite title", "Other title"), libraryRows(
            works, "", app.shura.manga.ui.screens.LibrarySort.TITLE,
        ).map { it.title })
    }
}
