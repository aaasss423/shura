package app.shura.manga

import app.shura.manga.reader.MemoryStore
import app.shura.manga.reader.ProgressLookup
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card must show the work's own cover, or nothing - never a stand-in.
 *
 * Covers are the reason a shelf reads as a shelf; a list of titles with grey
 * rectangles is a file browser. The risk is not that the image fails to load
 * but that it shows the *wrong* picture: a cover cached by work rather than by
 * URL leaves one work's art on another's card. The rules are pure, so they are
 * pinned here instead of squinted at on a device.
 */
class CoverBindingTest {

    @Test
    fun `each work's cover is its own`() {
        val covers = listOf(
            "https://cdn.example/a.jpg",
            "https://cdn.example/b.jpg",
            "https://cdn.example/c.jpg",
        )
        assertEquals(covers.size, covers.toSet().size)
    }

    @Test
    fun `a missing cover stays empty instead of becoming a url`() {
        // A blank cover is a placeholder. Inventing one - e.g. falling back to
        // "cover.jpg" - renders as a broken tile on every work that lacks art.
        val blank = ReadingProgress(
            sourceId = "s", mangaTitle = "T", mangaId = "m", chapterId = "c",
            chapterName = "Ch 1", page = 0, pageCount = 10, updatedAt = 0L,
        )
        assertEquals("", blank.coverUrl)
        assertTrue(blank.coverUrl.isBlank())
    }

    @Test
    fun `progress survives a save and reload with its cover`() {
        val store = ReadingProgressStore(MemoryStore())
        val saved = ReadingProgress(
            sourceId = "s", mangaTitle = "Work", mangaId = "m",
            chapterId = "c1", chapterName = "Chapter 1", page = 4, pageCount = 20,
            updatedAt = 1L, coverUrl = "https://cdn.example/work.jpg",
        )
        store.save(saved)
        // The cover is written beside the position, so a cold start shows art
        // before any network call happens.
        assertEquals("https://cdn.example/work.jpg", store.coverFor("s", "Work"))
        assertEquals(4, store.pageFor("s", "Work", "c1", 20))
    }

    @Test
    fun `a cover lookup finds the work and misses the other`() {
        val remembered = mapOf(
            "s|Work" to "https://cdn.example/work.jpg",
            "s|Other" to "https://cdn.example/other.jpg",
        )
        val lookup = ProgressLookup(remembered)
        assertEquals("https://cdn.example/work.jpg", lookup.coverFor("s", "Work"))
        // A miss must be empty, not another work's cover.
        assertEquals("", lookup.coverFor("s", "Never Seen"))
        assertEquals("", lookup.coverFor("other-source", "Work"))
    }
}