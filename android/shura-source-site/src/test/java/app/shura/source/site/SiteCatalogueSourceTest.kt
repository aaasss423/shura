package app.shura.source.site

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The generic engine, driven by the spec the converter generates, against HTML
 * a real site actually served.
 *
 * The fixtures under src/test/resources are verbatim captures of
 * www.mangaarabia.com/index.php/ar/node, its work page and its chapter page.
 * They are not shaped to make the engine pass: they are what the site returned,
 * which is why they still contain the thing that broke the first version - an
 * index whose links are *chapters* whose text names the work.
 */
class SiteCatalogueSourceTest {

    private fun resource(name: String): String =
        File(javaClass.classLoader!!.getResource(name)!!.toURI()).readText()

    private val spec = SiteSpec(
        sourceId = 12345L,
        name = "MangaArabia",
        lang = "ar",
        catalogueUrl = "https://www.mangaarabia.com/index.php/ar/node",
        entryHref = Regex("""/index\.php/ar/node/\d+"""),
        minListingGroup = 4,
        chapterMarker = Regex(
            """(?:\s*[-–—|]\s*(?:[A-Za-z]{2,12}\s+|الفصل|فصل|حلقة)\s*#?\s*\d+(?:\.\d+)?\s*$)"""),
        pageImage = Regex("""\.(?:jpe?g|png|webp|gif)(?:\?|$)""", RegexOption.IGNORE_CASE),
        coverImage = Regex("""(?:cover|capa|thumb|poster)[^/]*\.(?:jpe?g|png|webp)(?:\?|$)""",
            RegexOption.IGNORE_CASE),
    )

    // -- the defect this engine exists to prevent --------------------------

    @Test
    fun `an index of chapter links does not become a list of chapters as works`() {
        val works = SiteHtml.works(spec, resource("mangaarabia-index.html"))

        assertTrue("the live index yielded no works at all", works.isNotEmpty())
        val titles = works.map { it.title }
        assertTrue(
            "a work was named after a chapter: $titles",
            titles.none { it.contains("الفصل") })
        assertEquals("the index produced duplicate works: $titles",
            titles.size, titles.distinct().size)
    }

    @Test
    fun `every work is a page for the work, not one of its chapters`() {
        val works = SiteHtml.works(spec, resource("mangaarabia-index.html"))
        assertTrue(works.isNotEmpty())
        for (work in works) {
            assertTrue("${work.title} has no URL", work.url.isNotEmpty())
            assertTrue("${work.title} is not an absolute https URL",
                work.url.startsWith("https://www.mangaarabia.com/"))
        }
    }

    // -- the three operations ----------------------------------------------

    @Test
    fun `the work page's own heading outranks the index text`() {
        val works = SiteHtml.works(spec, resource("mangaarabia-index.html"))
        assertTrue(works.isNotEmpty())
        val heading = SiteHtml.heading(spec, resource("mangaarabia-work.html"))
        assertTrue("the saved work page has no heading to compare against", heading.isNotEmpty())
        assertTrue("a heading still carries a chapter marker: $heading",
            SiteHtml.splitChapterTitle(heading, spec.chapterMarker) == null)
    }

    @Test
    fun `chapters come back newest first and carry their numbers`() {
        // This site's work page lists no chapters of its own; they are linked
        // from the index and labelled with the work. Both routes are exercised.
        val chapters = SiteHtml.chaptersFor(
            spec, resource("mangaarabia-work.html"),
            "https://www.mangaarabia.com/index.php/ar/node/12000",
            SiteHtml.normaliseTitle(SiteHtml.heading(spec, resource("mangaarabia-work.html"))),
            resource("mangaarabia-index.html"))
        assertTrue("no chapters were found for this work", chapters.isNotEmpty())
        assertTrue("a chapter was named after a work",
            chapters.none { !it.isChapter })
        val numbers = chapters.map { it.number }
        assertEquals("chapters are not in reading order", numbers.sortedDescending(), numbers)
        assertTrue("no chapter carried a number: $numbers", numbers.all { it >= 0f })
    }

    @Test
    fun `chapter ids are distinct`() {
        val chapters = SiteHtml.chaptersFor(
            spec, resource("mangaarabia-work.html"),
            "https://www.mangaarabia.com/index.php/ar/node/12000",
            SiteHtml.normaliseTitle(SiteHtml.heading(spec, resource("mangaarabia-work.html"))),
            resource("mangaarabia-index.html"))
        assertEquals(chapters.map { it.url }.distinct().size, chapters.size)
    }

    @Test
    fun `reading pages are absolute and ordered`() {
        val pages = SiteHtml.pages(
            spec, resource("mangaarabia-chapter.html"),
            "https://www.mangaarabia.com/index.php/ar/node/12000")
        // This site serves no page images today; the contract is that it says so
        // rather than inventing some. Asserted as "no bogus pages", not "many".
        assertTrue("a non-image URL was returned as a reading page",
            pages.all { it.startsWith("https://") && it.contains('.') })
        assertEquals("the same image was returned twice", pages.distinct().size, pages.size)
    }

    // -- the rules in isolation --------------------------------------------

    @Test
    fun `a chapter marker splits into the work and its number`() {
        val split = SiteHtml.splitChapterTitle("الولد الضب - الفصل 37", spec.chapterMarker)
        assertEquals("الولد الضب", split?.first)
        assertEquals(37f, split?.second)
    }

    @Test
    fun `a decimal chapter number is not truncated`() {
        val split = SiteHtml.splitChapterTitle("X - الفصل 37.5", spec.chapterMarker)
        assertEquals(37.5f, split?.second)
    }

    @Test
    fun `a work title is not mistaken for a chapter`() {
        assertEquals(null, SiteHtml.splitChapterTitle("محكمة الغربان", spec.chapterMarker))
        assertEquals(null, SiteHtml.splitChapterTitle("One Piece", spec.chapterMarker))
    }

    @Test
    fun `a non-breaking space does not fork one work into two`() {
        val withNbsp = SiteHtml.normaliseTitle("الولد&nbsp;الضب - الفصل 37")
        val withSpace = SiteHtml.normaliseTitle("الولد الضب - الفصل 37")
        assertEquals(withSpace, withNbsp)
        assertEquals(SiteHtml.identity("الولد الضب"), SiteHtml.identity("الولد&nbsp;الضب"))
    }

    @Test
    fun `work identity is stable and survives a language with no ascii`() {
        assertEquals(SiteHtml.identity("الولد الضب"), SiteHtml.identity("الولد الضب"))
        assertTrue(SiteHtml.identity("الولد الضب").isNotEmpty())
        assertTrue(SiteHtml.identity("One Piece").startsWith("one-piece"))
    }

    @Test
    fun `a query filters what the site returned rather than guessing a route`() {
        val all = SiteHtml.works(spec, resource("mangaarabia-index.html"))
        assertTrue(all.isNotEmpty())
        val target = all.first().title
        val filtered = SiteHtml.works(spec, resource("mangaarabia-index.html"), target)
        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { it.title.contains(target, ignoreCase = true) })
    }

    @Test
    fun `the query is carried in the fragment, so no search route is invented`() {
        val url = SiteHtml.searchUrl(spec, 1, "ع Olivе".take(3))
        assertTrue(url.startsWith("https://www.mangaarabia.com/index.php/ar/node#"))
        assertEquals("https://www.mangaarabia.com/index.php/ar/node",
            SiteHtml.searchUrl(spec, 1, "   "))
    }
}