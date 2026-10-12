package app.shura.extension.mangadex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The extension's three functions, called for real.
 *
 * They are ordinary JVM code - no Android types - so they run here without a
 * device or an emulator, which this environment has neither of. That makes it
 * possible to check the contract's shape against MangaDex's public API instead
 * of only compiling.
 *
 * The network is not a test dependency: if MangaDex cannot be reached the test
 * skips rather than failing, so a CI box without egress does not turn this into
 * a false alarm. A skipped test is not a passing test.
 */
class MangaDexSourceTest {

    private fun reachable(): Boolean = try {
        val c = java.net.URL("https://api.mangadex.org/manga?limit=1").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.responseCode in 200..399
    } catch (_: Exception) {
        false
    }

    @Test
    fun `the source declares itself the way the contract says`() {
        val source = MangaDexSource()
        // The loader's own gates: a CatalogueSource, and the ABI values.
        assertEquals("MangaDex", source.name)
        assertEquals("ar", source.lang)
        assertTrue("the source id must be a stable number, not a string",
            source.id > 0)
        assertTrue(
            "ExtensionLoader.load() refuses anything that is not a CatalogueSource",
            source is eu.kanade.tachiyomi.source.CatalogueSource)
        // Every method Shura's ExtensionSourceAdapter calls reflectively.
        for (method in listOf("searchMangaRequest", "searchMangaParse", "mangaDetailsRequest",
                              "mangaDetailsParse", "chapterListRequest", "chapterListParse",
                              "pageListRequest", "pageListParse")) {
            assertTrue("$method is not declared", source.javaClass.methods.any { it.name == method })
        }
    }

    @Test
    fun `search returns manga summaries in the contract's shape`() = runBlocking {
        assumeTrue("MangaDex is not reachable from here", reachable())
        val results = MangaDexSource().search("one piece", 1)
        assertTrue("search returned nothing for a well-known title", results.isNotEmpty())
        val first = results.first()
        assertNotNull("a summary needs an id", first.id)
        assertTrue("id must not be blank", first.id.isNotBlank())
        assertTrue("title must not be blank", first.title.isNotBlank())
        // coverUrl is nullable in the contract; this build does not populate it.
        assertEquals(null, first.coverUrl)
    }

    @Test
    fun `chapters returns Arabic chapters with stable ids and numbers`() = runBlocking {
        assumeTrue("MangaDex is not reachable from here", reachable())
        val mangaId = MangaDexSource().search("one piece", 1).firstOrNull()?.id
        assumeTrue("no manga to look up", mangaId != null)
        val chapters = MangaDexSource().chapters(mangaId!!)
        assertTrue("no Arabic chapters returned", chapters.isNotEmpty())
        val first = chapters.first()
        assertTrue("chapter id must not be blank", first.id.isNotBlank())
        // `number` is nullable in the contract and really is absent for
        // one-shots, so this asserts the ordering promise, not its presence.
        val numbered = chapters.mapNotNull { it.number }
        assertTrue("no numbered chapters at all", numbered.isNotEmpty())
        // The contract's ordering promise: newest first.
        assertEquals(
            "chapters must be ordered newest first",
            numbered.sortedDescending(), numbered,
        )
    }

    @Test
    fun `pages resolves a chapter to an image url`() = runBlocking {
        assumeTrue("MangaDex is not reachable from here", reachable())
        val source = MangaDexSource()
        val mangaId = source.search("one piece", 1).firstOrNull()?.id
        assumeTrue("no manga to look up", mangaId != null)
        val chapterId = source.chapters(mangaId!!).firstOrNull()?.id
        assumeTrue("no chapter to open", chapterId != null)
        val pages = source.pages(chapterId!!)
        assertTrue("no pages resolved for a readable chapter", pages.isNotEmpty())
        val page = pages.first()
        assertEquals("pages are zero-indexed in the contract", 0, page.index)
        assertTrue("page url must be absolute https", page.imageUrl.startsWith("https://"))
    }
}
