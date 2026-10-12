package app.shura.source.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine against a site that really can be read.
 *
 * These fixtures are verbatim captures of asurascans.com: its index at /comics,
 * one work page, and one chapter page. It is here because MangaArabia could not
 * be - its chapter pages serve a cover and two logos and no reading pages, so
 * it proves an extension can be built and never proves one can read. Here the
 * chapter page really does carry 26 images, three of which are covers that must
 * NOT become page 1.
 *
 * The discrimination is structural, not a URL extension. The chapter lives at
 * /comics/&lt;slug&gt;/chapter/6 and its pages live under
 * /asura-images/chapters/&lt;slug&gt;/6/, while covers live under
 * /asura-images/covers/. Only the pages carry this chapter's own identity.
 */
class AsuraScansSourceTest {

    private fun resource(name: String): String =
        File(javaClass.classLoader!!.getResource(name)!!.toURI()).readText()

    private val spec = SiteSpec(
        sourceId = 4242L,
        name = "Asura Scans",
        lang = "en",
        catalogueUrl = "https://asurascans.com/comics",
        entryHref = Regex("""/comics/[^/]+"""),
        minListingGroup = 4,
        chapterMarker = Regex("""\s*[-–—|]?\s*(?:Chapter|Ch\.?)\s*#?\s*\d+(?:\.\d+)?\s*$""",
            RegexOption.IGNORE_CASE),
        chapterHref = Regex("""/chapter/\d+"""),
        pageImage = Regex("""\.(?:jpe?g|png|webp|gif)(?:\?|$)""", RegexOption.IGNORE_CASE),
        pageImagePrefix = Regex(
            """/asura\-images/chapters/pick\-up\-a\-grimoire\-become\-a\-mage/6"""),
        imageHost = "cdn.asurascans.com",
        coverImage = Regex("""/covers/""", RegexOption.IGNORE_CASE),
    )

    private val chapterUrl =
        "https://asurascans.com/comics/pick-up-a-grimoire-become-a-mage-1bb35e56/chapter/6"

    // -- works and chapters -------------------------------------------------

    @Test
    fun `the index yields works and no work is named after a chapter`() {
        val works = SiteHtml.works(spec, resource("asura-index.html"))
        assertTrue("the live index yielded no works", works.isNotEmpty())
        val titles = works.map { it.title }
        assertEquals("duplicate works: $titles", titles.size, titles.distinct().size)
        assertTrue("a work is named after a chapter: $titles",
            titles.none { Regex("chapter", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        for (work in works) {
            assertTrue("${work.title} has no URL", work.url.isNotEmpty())
            assertTrue("${work.title} is not on the site", work.url.startsWith("https://asurascans.com/"))
        }
    }

    @Test
    fun `a work page lists its own chapters, numbered`() {
        val chapters = SiteHtml.chaptersFor(
            spec, resource("asura-work.html"), chapterUrl.substringBeforeLast("/"),
            SiteHtml.normaliseTitle(SiteHtml.heading(spec, resource("asura-work.html"))),
            resource("asura-index.html"))
        assertTrue("no chapters found", chapters.isNotEmpty())
        assertTrue("chapters carry no number", chapters.all { it.number >= 0f })
        assertEquals("a chapter was listed twice", chapters.map { it.url }.distinct().size,
            chapters.size)
        assertTrue("chapters are not newest first",
            chapters.map { it.number } == chapters.map { it.number }.sortedDescending())
    }

    @Test
    fun `the work page's own heading names the work`() {
        assertEquals("Pick Up a Grimoire, Become a Mage",
            SiteHtml.heading(spec, resource("asura-work.html")))
    }

    // -- the part that matters: pages ---------------------------------------

    @Test
    fun `a chapter returns its real reading pages`() {
        val pages = SiteHtml.pages(spec, resource("asura-chapter.html"), chapterUrl)
        assertTrue("the chapter returned no pages", pages.size > 5)
        assertEquals("the same page was returned twice", pages.distinct().size, pages.size)
        assertTrue("a page was not absolute", pages.all { it.startsWith("https://") })
    }

    @Test
    fun `a cover is never returned as a reading page`() {
        val pages = SiteHtml.pages(spec, resource("asura-chapter.html"), chapterUrl)
        // The same page really does carry three covers.
        assertTrue("the fixture no longer contains covers - re-check this test",
            resource("asura-chapter.html").contains("/covers/"))
        assertTrue("a cover leaked into the pages: $pages", pages.none { "/covers/" in it })
        assertTrue("a logo leaked into the pages: $pages", pages.none { "logo" in it })
    }

    @Test
    fun `a page from another chapter is not this chapter's page`() {
        val pages = SiteHtml.pages(spec, resource("asura-chapter.html"), chapterUrl)
        // Chapter 5's pages share the prefix but not the chapter number.
        assertTrue("another chapter's page leaked in",
            pages.none { it.contains("/chapters/pick-up-a-grimoire-become-a-mage/5/") })
    }

    @Test
    fun `every page belongs to the host that was proven to serve pages`() {
        val pages = SiteHtml.pages(spec, resource("asura-chapter.html"), chapterUrl)
        assertTrue(pages.all { it.startsWith("https://cdn.asurascans.com/") })
    }

    // -- the discrimination, stated as a rule -------------------------------

    @Test
    fun `an image extension alone is not enough to be a page`() {
        // Every cover and logo here matches the extension pattern. Only the
        // chapter path separates them, which is why the prefix is required
        // rather than optional.
        val allImages = Regex("""(?:src|data-src)\s*=\s*["']([^"']+)["']""")
            .findAll(resource("asura-chapter.html"))
            .map { it.groupValues[1] }
            .filter { spec.pageImage.containsMatchIn(it) }
            .toList()
        assertTrue("the fixture should contain covers and pages alike", allImages.size > 5)
        assertTrue("at least one of them is a cover", allImages.any { "/covers/" in it })
        assertTrue("the prefix is what separates them",
            SiteHtml.pages(spec, resource("asura-chapter.html"), chapterUrl).size < allImages.size)
    }

    @Test
    fun `with no proven prefix the engine still refuses to call chrome a page`() {
        // A spec whose prefix is empty must not start returning logos; it must
        // simply have nothing to prove from.
        val loose = spec.copy(pageImagePrefix = Regex(""), imageHost = "")
        val pages = SiteHtml.pages(loose, resource("asura-chapter.html"), chapterUrl)
        assertFalse("an unproven spec returned chrome as pages",
            pages.any { "/covers/" in it || "logo" in it })
    }
}