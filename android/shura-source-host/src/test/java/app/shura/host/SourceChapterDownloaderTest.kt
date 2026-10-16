package app.shura.host

import app.shura.source.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a reader is allowed to fetch a page from.
 *
 * The rule used to be one hardcoded domain, which meant a reader built around
 * extensions could not read a single page from any of them. These pin the rule that
 * replaced it: the source names the host, and the host still has to be a public one.
 */
class SourceChapterDownloaderTest {

    private fun page(url: String, index: Int = 0) = Page(index, url)

    @Test
    fun `a source may fetch from the cdn it published on`() {
        val hosts = SourceChapterDownloader.allowedHostsForPages(
            listOf(page("https://cdn.asurascans.com/a/1.jpg"), page("https://cdn.asurascans.com/a/2.jpg", 1)),
        )
        assertEquals(setOf("cdn.asurascans.com"), hosts)
    }

    @Test
    fun `two hosts in one chapter are both allowed`() {
        val hosts = SourceChapterDownloader.allowedHostsForPages(
            listOf(page("https://a.example/1.jpg"), page("https://b.example/2.jpg", 1)),
        )
        assertEquals(setOf("a.example", "b.example"), hosts)
    }

    @Test
    fun `loopback and private hosts are refused whatever the source says`() {
        // The point of the whole class: a source must not be able to aim the
        // reader's network stack at the device or the router it sits on.
        for (url in listOf(
            "https://127.0.0.1/secret",
            "https://localhost/secret",
            "https://10.0.0.5/secret",
            "https://192.168.1.1/secret",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/secret",
            "https://router.local/secret",
        )) {
            assertThrows("must refuse $url", IllegalArgumentException::class.java) {
                SourceChapterDownloader.allowedHostsForPages(listOf(page(url)))
            }
        }
    }

    @Test
    fun `a name that resolves to loopback is refused too`() {
        // Read only as a string it would look like an ordinary host.
        assertTrue(SourceChapterDownloader.isUnreachableHost("127.0.0.1.nip.io"))
        assertTrue(SourceChapterDownloader.isUnreachableHost("LOCALHOST"))
        assertTrue(SourceChapterDownloader.isUnreachableHost("anything.local"))
    }

    @Test
    fun `an ordinary public host is allowed`() {
        assertFalse(SourceChapterDownloader.isUnreachableHost("cdn.asurascans.com"))
        assertFalse(SourceChapterDownloader.isUnreachableHost("uploads.mangadex.org"))
        assertFalse(SourceChapterDownloader.isUnreachableHost("example.co.uk"))
    }

    @Test
    fun `a page with no host is refused rather than fetched`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceChapterDownloader.allowedHostsForPages(listOf(page("not-a-url")))
        }
    }

    @Test
    fun `a chapter with no pages is refused rather than treated as complete`() {
        // An empty chapter that counts as "done" is how a library fills with
        // entries that open to nothing.
        assertThrows(IllegalArgumentException::class.java) {
            SourceChapterDownloader.allowedHostsForPages(emptyList())
        }
    }
}
