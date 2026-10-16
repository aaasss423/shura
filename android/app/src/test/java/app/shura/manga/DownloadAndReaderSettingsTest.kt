package app.shura.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.shura.manga.reader.ReaderPreferences

/**
 * The rules the new reader and download surfaces obey, on a plain JVM.
 *
 * None of this needs a device: clamping, progress arithmetic and page merging are
 * the parts that were wrong before, and they are the parts that have to stay right
 * when the screens around them change.
 */
class DownloadAndReaderSettingsTest {

    // -- reader preferences ----------------------------------------------------

    @Test
    fun theDefaultReaderPreferencesAreUsableWithoutTouchingAnything() {
        val defaults = ReaderPreferences()
        assertEquals(3, defaults.preload)
        assertEquals(4, defaults.parallelism)
        assertTrue("the system brightness is left alone by default", !defaults.overridesBrightness)
        assertEquals(false, defaults.keepScreenOn)
    }

    @Test
    fun brightnessIsClampedToSomethingAReaderCanActuallySee() {
        assertEquals(0.05f, ReaderPreferences.brightnessOf(0f), 0.0001f)
        assertEquals(1f, ReaderPreferences.brightnessOf(4f), 0.0001f)
        assertEquals(0.5f, ReaderPreferences.brightnessOf(0.5f), 0.0001f)
        // Below the floor the screen is effectively off, which is not a setting.
        assertEquals(0.05f, ReaderPreferences.brightnessOf(-2f), 0.0001f)
    }

    @Test
    fun preloadAndParallelismAreBoundedByWhatAPhoneCanHold() {
        assertEquals(0, ReaderPreferences.preloadOf(-5))
        assertEquals(10, ReaderPreferences.preloadOf(99))
        assertEquals(1, ReaderPreferences.parallelismOf(0))
        assertEquals(8, ReaderPreferences.parallelismOf(1000))
        // And the getters clamp too, so a value written by an older build cannot
        // ask the loader for 1000 pages of lookahead.
        assertEquals(10, ReaderPreferences(preloadPages = 1000).preload)
        assertEquals(1, ReaderPreferences(parallelDownloads = 0).parallelism)
    }

    // -- download rows ---------------------------------------------------------

    @Test
    fun aDownloadRowNamesItsOwnChapterUniquely() {
        val key = DownloadJob.keyOf("mangadex", "chapter/7")
        assertTrue("a key that collides would let one chapter download twice", key.contains("mangadex"))
        assertTrue(key.contains("chapter"))
        assertEquals(key, DownloadJob.keyOf("mangadex", "chapter/7"))
    }

    @Test
    fun progressIsARealFractionAndSurvivesAnUnknownTotal() {
        val running = DownloadJob(
            key = "s/1", sourceId = "s", sourceName = "S", mangaId = "m",
            mangaTitle = "M", chapterId = "1", chapterTitle = "C", coverUrl = "",
            total = 4, done = 1, state = DownloadState.RUNNING,
        )
        assertEquals(0.25f, running.progress, 0.0001f)
        // Before the first page list arrives there is no total, and a divide by
        // zero here would put a NaN into a progress bar.
        val queued = running.copy(total = 0, done = 0, state = DownloadState.QUEUED)
        assertEquals(0f, queued.progress, 0.0001f)
    }

    @Test
    fun aSavedChapterCountsAsOneHundredPercent() {
        val done = DownloadJob(
            key = "s/1", sourceId = "s", sourceName = "S", mangaId = "m",
            mangaTitle = "M", chapterId = "1", chapterTitle = "C", coverUrl = "",
            total = 12, done = 12, state = DownloadState.DONE,
        )
        assertEquals(1f, done.progress, 0.0001f)
    }
}
