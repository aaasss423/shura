package app.shura.host

import app.shura.source.MangaSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MangaDexSourceTest {

    private fun browserJson(): String = """{
        "result": "ok",
        "data": [
            {
                "id": "m-1111",
                "attributes": {
                    "title": {"en": "One Piece"},
                    "altTitles": [],
                    "latestUploadedChapter": "2026-01-01T00:00:00+00:00"
                },
                "relationships": [
                    {"type": "cover_art", "attributes": {"fileName": "m-1111.jpg"}}
                ]
            },
            {
                "id": "m-2222",
                "attributes": {
                    "title": {"ja": "名"},
                    "altTitles": [{"en": "Fallback Alt"}]
                },
                "relationships": []
            }
        ]
    }"""

    private fun feedJson(): String = """{
        "result": "ok",
        "data": [
            {"id": "c-11", "attributes": {"chapter": "1", "title": "Pilot"}},
            {"id": "c-12", "attributes": {"chapter": "extra", "title": ""}}
        ]
    }"""

    private fun atHomeJson(): String = """{
        "result": "ok",
        "baseUrl": "https://uploads.mangadex.network",
        "chapter": {"hash": "h123", "data": ["a.jpg", "b.png"]}
    }"""

    private fun routing(vararg routes: Pair<String, String>) =
        object : HttpGetter {
            private val map = routes.toMap()
            override fun get(url: String, headers: Map<String, String>): HttpResult {
                val body = map[url] ?: throw AssertionError("unexpected URL: $url")
                return HttpResult(200, body)
            }
        }

    @Test fun searchParsesResultsAndCover() = runBlocking {
        val source = MangaDexSource(routing("https://api.mangadex.org/manga?limit=20&offset=0&includes[]=cover_art&order[latestUploadedChapter]=desc" to browserJson()))
        val results = source.search("", 1)
        assertEquals(2, results.size)
        assertEquals("One Piece", results[0].title)
        assertEquals("https://uploads.mangadex.org/covers/m-1111/m-1111.jpg.256.jpg", results[0].coverUrl)
        assertEquals("Fallback Alt", results[1].title)
    }

    @Test fun searchEncodesQuery() = runBlocking {
        val hits = mutableListOf<String>()
        val source = MangaDexSource(HttpGetter { url, _ -> hits += url; HttpResult(200, """{"result":"ok","data":[]}""") })
        source.search("my hero", 2)
        assertTrue(hits.single(), hits.single().contains("offset=1") && hits.single().contains("my+hero"))
    }

    @Test fun chaptersParseNumbersAndFallbacks() = runBlocking {
        val source = MangaDexSource(routing("https://api.mangadex.org/manga/m-1111/feed?translatedLanguage[]=en&limit=500&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica" to feedJson()))
        val chapters = source.chapters("m-1111")
        assertEquals(2, chapters.size)
        assertEquals("Pilot", chapters[0].name)
        assertEquals(1f, chapters[0].number)
        assertEquals("Chapter 2", chapters[1].name)
        assertEquals(null, chapters[1].number)
    }

    @Test fun pagesUseAtHomeBaseAndHash() = runBlocking {
        val source = MangaDexSource(routing("https://api.mangadex.org/at-home/server/c-11" to atHomeJson()))
        val pages = source.pages("c-11")
        assertEquals(2, pages.size)
        assertEquals("https://uploads.mangadex.network/data/h123/a.jpg", pages[0].imageUrl)
        assertEquals(1, pages[1].index)
    }

    @Test fun searchFailureThrows() {
        val source = MangaDexSource(HttpGetter { _, _ -> HttpResult(429, "") })
        assertThrows(IllegalStateException::class.java) { runBlocking { source.search("x", 1) } }
    }

    @Test fun listResultsAlreadyHaveSummary() {
        // Guards against SourceApi drift: browse results must be MangaSummary instances.
        val sample = MangaSummary("id", "t", null)
        assertTrue(sample.id == "id" && sample.title == "t")
    }

    /**
     * A chapter whose title the API sent as JSON `null`.
     *
     * THIS IS WHERE THE WORD "null" CAME FROM
     *
     * MangaDex publishes `"title": null` for every chapter that has no
     * translated title, which is most of a long series. The parser read it with
     * `JSONObject.optString`, which is not "the string, or empty": on Android it
     * runs the value through `String.valueOf`, and `JSONObject.NULL` stringifies
     * to the four characters "null". `title.isNotBlank()` was satisfied by that,
     * so the name became "null" - and every chapter row in the app printed it,
     * with the chapter's own number sitting in the column beside it.
     *
     * The reference `org.json` on the test classpath returns "" for the same
     * input, so this test cannot see the four letters itself; what it pins is the
     * outcome both implementations must produce, which is that a missing title is
     * never a name and the chapter's number is used instead.
     */
    @Test fun aJsonNullTitleIsNeverTheChaptersName() = runBlocking {
        val feed = """{"result":"ok","data":[
            {"id":"c-451","attributes":{"chapter":"451","title":null}},
            {"id":"c-450","attributes":{"chapter":"450","title":null}},
            {"id":"c-named","attributes":{"chapter":"1","title":"Pilot"}}
        ]}"""
        val source = MangaDexSource(routing(FEED_URL to feed))
        val chapters = source.chapters("m-1111")

        assertEquals(3, chapters.size)
        listOf("c-451", "c-450").forEachIndexed { index, _ ->
            val chapter = chapters[index]
            assertFalse("a chapter was titled the word null", chapter.name.trim().equals("null", true))
            assertEquals("Chapter ${451 - index}", chapter.name)
        }
        // A title the site really wrote is still the name.
        assertEquals("Pilot", chapters[2].name)
    }

    private companion object {
        const val FEED_URL =
            "https://api.mangadex.org/manga/m-1111/feed?translatedLanguage[]=en&limit=500" +
                "&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
    }
}
