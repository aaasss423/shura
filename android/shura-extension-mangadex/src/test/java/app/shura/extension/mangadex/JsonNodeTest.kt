package app.shura.extension.mangadex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The reader, against the shapes MangaDex actually returns. */
class JsonNodeTest {

    private fun sample(): JsonNode.Obj {
        val text = javaClass.classLoader!!.getResourceAsStream("at-home.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw AssertionError("at-home.json missing")
        return JsonNode.parse(text) as JsonNode.Obj
    }

    @Test
    fun `a localized title map is read as a map, not a string`() {
        val node = JsonNode.parse("""{"attributes":{"title":{"ja-ro":"One Piece","en":"One Piece"}}}""") as JsonNode.Obj
        val titles = node.obj("attributes")!!.obj("title")
        assertNotNull("title must not be read as a scalar", titles)
        assertEquals("One Piece", titles!!.str("ja-ro"))
        assertEquals("ja-ro", node.obj("attributes")!!.str("title") ?: "ja-ro")
    }

    @Test
    fun `the at-home chapter object yields its hash and page filenames`() {
        val root = sample()
        val chapter = root.obj("chapter")
        assertNotNull("chapter must be an object", chapter)
        val hash = chapter!!.str("hash")
        assertNotNull("hash missing", hash)
        val files = chapter.arr("data").orEmpty().mapNotNull { (it as? JsonNode.Str)?.value }
        assertEquals("expected three saved pages", 3, files.size)
        assertEquals(hash, "63b5a6ab736f3f1d847b68e37d1993c2")
    }

    @Test
    fun `baseUrl is a top level string`() {
        assertEquals("https://cmdxd98sb0x3yprd.mangadex.network", sample().str("baseUrl"))
    }
}
