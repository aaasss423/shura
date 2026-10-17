package app.shura.manga.reader

import app.shura.manga.StoredChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deleting a chapter once it has been read, without a device.
 *
 * This setting removes a file the reader may still want, so the decision is
 * pinned here rather than left to a composable: three facts go in, and the only
 * acceptable answer is the narrow one - all three must hold, and a chapter is
 * offered once.
 */
class ChapterDeletionTest {

    /**
     * A stored chapter as the library holds it. Empty page list on purpose: the
     * decision is about whether a chapter exists on disk, and building one must
     * not need a device to turn a path into an image reference.
     */
    private fun stored(chapterId: String) = StoredChapter(
        dir = File("/nonexistent-shura-library/$chapterId"),
        sourceId = "src",
        mangaId = "manga-1",
        chapterId = chapterId,
        title = "Chapter $chapterId",
        coverUrl = "",
        pageUris = emptyList(),
    )

    // -- the decision ---------------------------------------------------------

    @Test
    fun `a chapter is deleted only when all three facts hold`() {
        assertTrue(ChapterDeletion.shouldDelete(lastPageReached = true, preferenceOn = true, chapterStored = true))
        // Any one of them missing is a no-op rather than a partial delete.
        assertFalse(ChapterDeletion.shouldDelete(lastPageReached = false, preferenceOn = true, chapterStored = true))
        assertFalse(ChapterDeletion.shouldDelete(lastPageReached = true, preferenceOn = false, chapterStored = true))
        assertFalse(ChapterDeletion.shouldDelete(lastPageReached = true, preferenceOn = true, chapterStored = false))
        assertFalse(ChapterDeletion.shouldDelete(lastPageReached = false, preferenceOn = false, chapterStored = false))
    }

    @Test
    fun `a chapter nobody asked to delete is never deleted`() {
        // Off is the default, so the common case is a no-op with no reading
        // position involved at all.
        assertFalse(ChapterDeletion.shouldDelete(lastPageReached = true, preferenceOn = false, chapterStored = true))
    }

    @Test
    fun `a chapter that was never stored offers nothing to delete`() {
        // The library could not answer "is it saved?" and named nothing: there is
        // no file to remove, and that is not an error to raise at the reader.
        val finished = ChapterFinished("c7", lastPageReached = true, deleteAfterReading = true)
        val inLibrary: StoredChapter? = null
        assertFalse(
            ChapterDeletion.shouldDelete(
                lastPageReached = finished.lastPageReached,
                preferenceOn = finished.deleteAfterReading,
                chapterStored = inLibrary != null,
            ),
        )
    }

    @Test
    fun `the library names which chapter the reader finished`() {
        // The shape the activity uses: the reader reports an id, the library
        // says whether anything is saved under it, and the two decide together.
        val library = listOf(stored("c6"), stored("c7"), stored("c8"))
        fun deletes(finished: ChapterFinished): Boolean {
            val stored = library.firstOrNull { it.chapterId == finished.chapterId }
            return ChapterDeletion.shouldDelete(
                lastPageReached = finished.lastPageReached,
                preferenceOn = finished.deleteAfterReading,
                chapterStored = stored != null,
            )
        }

        assertTrue(deletes(ChapterFinished("c7", lastPageReached = true, deleteAfterReading = true)))
        // Nothing saved under that id: nothing to delete, and no error.
        assertFalse(deletes(ChapterFinished("c9", lastPageReached = true, deleteAfterReading = true)))
        // Read to the end with the preference off.
        assertFalse(deletes(ChapterFinished("c7", lastPageReached = true, deleteAfterReading = false)))
        // Put the phone down before the end.
        assertFalse(deletes(ChapterFinished("c7", lastPageReached = false, deleteAfterReading = true)))
    }

    // -- once per chapter -----------------------------------------------------

    @Test
    fun `the last page reports itself repeatedly and deletes once`() {
        val claim = ChapterDeletionClaim()
        // Same page, same preference, same chapter: a recomposition, a rotation
        // and a settling pager all look like this.
        val offers = List(5) { claim.claim("c1", wanted = true) }
        assertEquals("exactly one delete may be offered", 1, offers.count { it })
    }

    @Test
    fun `a chapter is claimed once, whichever answer the library gives`() {
        val claim = ChapterDeletionClaim()
        // The claim happens before the library answers, so a chapter that was
        // never stored cannot be offered again on the next page event either.
        assertFalse(claim.claim("c1", wanted = false))
        assertFalse(claim.claim("c1", wanted = false))
        // And when it does become wanted, exactly once.
        assertTrue(claim.claim("c1", wanted = true))
        assertFalse(claim.claim("c1", wanted = true))
    }

    @Test
    fun `the next chapter in the same session is offered in its own right`() {
        val claim = ChapterDeletionClaim()
        assertTrue(claim.claim("c1", wanted = true))
        assertFalse(claim.claim("c1", wanted = true))
        assertTrue(claim.claim("c2", wanted = true))
        assertTrue(claim.claim("extra", wanted = true))
    }

    @Test
    fun `a chapter with no id is never offered`() {
        val claim = ChapterDeletionClaim()
        assertFalse(claim.claim("", wanted = true))
        assertFalse(claim.claim("   ", wanted = true))
    }

    @Test
    fun `the preference turned on mid chapter deletes nothing until the end`() {
        // The reader asks on every page, so a switch turned on at page two of a
        // twenty-page chapter must not act until the reader reaches the last one.
        val claim = ChapterDeletionClaim()
        val lastPage = 19
        val preferenceTurnedOnAt = 2
        fun wanted(page: Int) = page == lastPage && page >= preferenceTurnedOnAt

        val offers = (0 until lastPage).map { page -> claim.claim("c1", wanted = wanted(page)) }
        assertFalse("no page before the last one may offer a delete", offers.any { it })
        assertTrue("the last page is the one that may", claim.claim("c1", wanted = wanted(lastPage)))
        assertFalse(claim.claim("c1", wanted = wanted(lastPage)))
    }

    @Test
    fun `a new session is a new claim, so a chapter read twice is offered twice`() {
        // Deliberate: by the time the reader reopens a chapter they have asked
        // for this again, and a gate that remembered forever would never delete
        // anything a reader came back for.
        val first = ChapterDeletionClaim()
        assertTrue(first.claim("c1", wanted = true))
        val second = ChapterDeletionClaim()
        assertTrue(second.claim("c1", wanted = true))
    }
}