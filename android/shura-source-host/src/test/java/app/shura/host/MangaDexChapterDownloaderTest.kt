package app.shura.host

import app.shura.source.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaDexChapterDownloaderTest {

    private fun pages(vararg urls: String) = urls.mapIndexed { index, url -> Page(index, url) }

    @Test fun rejectsForeignHosts() {
        val downloader = MangaDexChapterDownloader()
        assertThrows(IllegalArgumentException::class.java) {
            downloader.allowedHostsForPages(pages("https://evil.example.com/x/1.jpg"))
        }
    }

    @Test fun allowsMangaDexNetworkAndAppendsActualHosts() {
        val downloader = MangaDexChapterDownloader()
        val allowed = downloader.allowedHostsForPages(
            pages("https://uploads.mangadex.network/data/h/a.jpg", "https://s2.mangadex.network/data/h/b.png")
        )
        assertTrue(allowed.contains("uploads.mangadex.network"))
        assertTrue(allowed.contains("s2.mangadex.network"))
        assertEquals(5, allowed.size)
    }

    @Test fun skipsExistingPageFilesWithoutNetwork() {
        val downloader = MangaDexChapterDownloader()
        val dir = File(System.getProperty("java.io.tmpdir"), "shura-dl-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val existing = File(dir, "page-001.img").apply { writeBytes(ByteArray(64) { 7 }) }
            val files = downloader.downloadChapter(
                pages("https://uploads.mangadex.network/data/h/a.jpg"),
                dir,
            )
            assertEquals(1, files.size)
            assertEquals(existing, files.single())
            assertEquals(64, files.single().length())
        } finally {
            dir.deleteRecursively()
        }
    }
}