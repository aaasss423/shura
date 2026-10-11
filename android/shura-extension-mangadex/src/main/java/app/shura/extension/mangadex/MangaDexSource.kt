package app.shura.extension.mangadex

import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import app.shura.source.ShuraSource
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MangaDex as a Shura source.
 *
 * Implements `app.shura.source.ShuraSource` and nothing else. No Tachiyomi or
 * Mihon type is referenced and no artifact from either project is a dependency:
 * the only thing this module declares is Shura's own source API.
 *
 * Only the three operations the contract declares are implemented. Nothing is
 * claimed beyond that interface - no library listing, no genre filter, no
 * tracking of read state.
 *
 * MangaDex's public API needs no key, so the extension holds no credentials.
 */
class MangaDexSource : ShuraSource {

    override val id: String = "mangadex"
    override val name: String = "MangaDex"
    override val language: String = "ar"

    private val base = "https://api.mangadex.org"

    override suspend fun search(query: String, page: Int): List<MangaSummary> =
        withContext(Dispatchers.IO) {
            val offset = (page - 1).coerceAtLeast(0) * 20
            // The title has to be encoded: a bare space makes URL() throw
            // InvalidURL, and the request below quietly became an empty result.
            val title = URLEncoder.encode(query, "UTF-8")
            val root = json(get("$base/manga?title=$title&limit=20&offset=$offset"))
            val items = root?.arr("data").orEmpty().objects()
            items.map { item ->
                val attrs = item.obj("attributes")
                MangaSummary(
                    id = item.str("id").orEmpty(),
                    title = title(attrs),
                    coverUrl = null,
                )
            }
        }

    override suspend fun chapters(mangaId: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val root = json(get("$base/manga/$mangaId/feed?translatedLanguage[]=ar&limit=100"))
            root?.arr("data").orEmpty().objects()
                .map { item ->
                    val attrs = item.obj("attributes")
                    Chapter(
                        id = item.str("id").orEmpty(),
                        name = attrs?.str("title").orEmpty(),
                        // MangaDex sends `chapter` as a quoted string ("792"),
                        // not a JSON number, so reading it as one left every
                        // chapter unnumbered and the order meaningless.
                        number = attrs?.num("chapter")?.toFloat()
                            ?: attrs?.str("chapter")?.trim()?.toFloatOrNull(),
                    )
                }
                .sortedByDescending { chapter -> chapter.number ?: Float.NEGATIVE_INFINITY }
        }

    override suspend fun pages(chapterId: String): List<Page> =
        withContext(Dispatchers.IO) {
            val root = json(get("$base/at-home/server/$chapterId")) ?: return@withContext emptyList()
            val baseUrl = root.str("baseUrl") ?: return@withContext emptyList()
            // `chapter` is not an array of filenames and not a map of them: it
            // is an object holding the image `hash` and a `data` array of page
            // filenames. Reading either the wrong shape yields no pages at all.
            val chapter = root.obj("chapter") ?: return@withContext emptyList()
            val hash = chapter.str("hash") ?: return@withContext emptyList()
            // The array holds strings. Filtering it through objects() - which
            // keeps only JSON objects - silently produced an empty page list.
            val files = chapter.arr("data").orEmpty()
                .mapNotNull { (it as? JsonNode.Str)?.value }
            // The API returns pages in order, but the contract promises an
            // ordered list, so the order is established rather than assumed.
            files.sortedBy { name -> name.takeWhile { !it.isDigit() }.length * -1 + (name.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE) }
                .mapIndexed { index, name -> Page(index, "$baseUrl/data/$hash/$name") }
        }

    /**
     * MangaDex returns `title` as a map keyed by language, not a string - e.g.
     * {"ja-ro": "One Piece"}. Reading it as a string returned null and every
     * result came back with a blank title. Prefer the extension's own language,
     * then English, then whatever the source has.
     */
    private fun title(attrs: JsonNode.Obj?): String {
        val titles = attrs?.obj("title") ?: return ""
        titles.str(language)?.let { return it }
        titles.str("en")?.let { return it }
        return titles.fields.values.filterIsInstance<JsonNode.Str>().firstOrNull()?.value ?: ""
    }

    private fun get(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun json(raw: String?): JsonNode.Obj? = try {
        raw?.let { JsonNode.parse(it) as? JsonNode.Obj }
    } catch (_: Exception) {
        null
    }
}
