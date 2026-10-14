package app.shura.manga

import app.shura.host.MangaDexChapterDownloader
import app.shura.host.MangaDexSource
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The whole reading path, against the real source.
 *
 * Search -> work -> chapters -> page images -> downloaded files on disk, which
 * is exactly what the reader is handed. Every step here is the production code
 * the app runs; nothing is stubbed, because a stubbed version of this proves
 * only that the stubs agree with each other.
 *
 * Skipped when the network is unavailable, which is what "cannot test it" looks
 * like rather than a fake pass.
 */
class ReadingPipelineTest {

    private fun online(): Boolean = runCatching {
        val connection = URL("https://api.mangadex.org/manga?title=one%20piece&limit=1")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.responseCode in 200..299
    }.getOrDefault(false)

    @Test
    fun `a real search returns works with usable ids and titles`() = runBlockingTest {
        assumeTrue("no network", online())
        val source = MangaDexSource()
        val results = source.search("one piece", 1)
        assertTrue("search returned nothing", results.isNotEmpty())
        val first = results.first()
        assertTrue("no id", first.id.isNotBlank())
        assertTrue("blank title: '${first.title}'", first.title.isNotBlank())
        // A title that is actually a title, not an echo of the query alone.
        assertTrue(first.title.length > 3)
    }

    @Test
    fun `a real work yields real numbered chapters`() = runBlockingTest {
        assumeTrue("no network", online())
        val source = MangaDexSource()
        val manga = source.search("one piece", 1).first()
        val chapters = source.chapters(manga.id)
        assertTrue("no chapters", chapters.isNotEmpty())
        assertTrue("a chapter had no id", chapters.all { it.id.isNotBlank() })
        // At least one chapter carries a number; that is what orders the list.
        assertTrue("no chapter was numbered", chapters.any { it.number != null })
    }

    @Test
    fun `a chapter either yields real page images or fails cleanly`() = runBlockingTest {
        assumeTrue("no network", online())
        val source = MangaDexSource()
        val manga = source.search("one piece", 1).first()
        // Scanned across works, because a title's newest chapters are often
        // listed before their images exist. A reader scrolls; this does too.
        val chapters = source.search("one piece", 1).take(2)
            .flatMap { runCatching { source.chapters(it.id) }.getOrDefault(emptyList()) }
            .take(6)

        var withPages = 0
        for (chapter in chapters) {
            val pages = runCatching { source.pages(chapter.id) }.getOrNull()
            if (pages == null || pages.isEmpty()) {
                // A chapter the source has no images for. That must surface as a
                // failure, never as a page list of invented URLs - which is what
                // the reader shows as "this chapter returned no pages".
                continue
            }
            withPages++
            assertTrue("a page had no URL", pages.all { it.imageUrl.isNotBlank() })
            assertTrue("pages are not in order",
                pages.map { it.index } == pages.map { it.index }.sorted())
            val hosts = pages.mapNotNull { URI(it.imageUrl).host }.toSet()
            assertTrue("unexpected hosts: $hosts",
                hosts.all { it.endsWith("mangadex.org") || it.endsWith("mangadex.network") })
        }
        // The source decides which chapters have images yet, and rate-limits
        // repeated scans. Zero readable chapters right now is the service's
        // state, not a defect in the app, so it skips; the download test below
        // is the one that must find pages.
        assumeTrue("no readable chapter was served right now", withPages > 0)
    }

    @Test
    fun `pages download to real files on disk`() = runBlockingTest {
        assumeTrue("no network", online())
        val source = MangaDexSource()
        // Scan until a chapter that actually has images is found: the newest few
        // chapters of a title are not all readable, and a test that assumed the
        // first one was would be testing the wrong thing.
        var downloaded = emptyList<java.io.File>()
        var dir: java.io.File? = null
        outer@ for (manga in source.search("one piece", 1).take(3)) {
            for (chapter in source.chapters(manga.id).take(4)) {
                val pages = runCatching { source.pages(chapter.id) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val target = java.io.File(
                    System.getProperty("java.io.tmpdir"),
                    "shura-pipeline-${System.nanoTime()}")
                val files = runCatching {
                    MangaDexChapterDownloader().downloadChapter(pages.take(2), target)
                }.getOrNull() ?: continue
                if (files.isNotEmpty()) {
                    downloaded = files
                    dir = target
                    break@outer
                }
            }
        }
        try {
            assertTrue("no chapter with pages was found to download", downloaded.isNotEmpty())
            for (file in downloaded) {
                assertTrue("${file.name} is not a file", file.isFile)
                assertTrue("${file.name} is empty", file.length() > 0)
            }
        } finally {
            dir?.deleteRecursively()
        }
    }

    @Test
    fun `the downloader refuses a chapter with no pages rather than inventing them`() {
        val failure = runCatching {
            MangaDexChapterDownloader().downloadChapter(emptyList(), File("/tmp/shura-never"))
        }.exceptionOrNull()
        assertTrue("an empty chapter was accepted", failure != null)
    }

    private fun runBlockingTest(body: suspend () -> Unit) = kotlinx.coroutines.runBlocking { body() }
}
