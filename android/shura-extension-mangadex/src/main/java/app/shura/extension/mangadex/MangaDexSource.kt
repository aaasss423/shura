package app.shura.extension.mangadex

import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import app.shura.source.ShuraSource
import java.net.HttpURLConnection
import java.net.URL
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
            val root = json(get("$base/manga?title=$query&limit=20&offset=$offset"))
            val items = root?.arr("data").orEmpty().objects()
            items.map { item ->
                val attrs = item.obj("attributes")
                MangaSummary(
                    id = item.str("id").orEmpty(),
                    title = attrs?.str("title").orEmpty(),
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
                        number = attrs?.num("chapter")?.toFloat(),
                    )
                }
                .sortedByDescending { it.number }
        }

    override suspend fun pages(chapterId: String): List<Page> =
        withContext(Dispatchers.IO) {
            val root = json(get("$base/at-home/server/$chapterId")) ?: return@withContext emptyList()
            val baseUrl = root.str("baseUrl")
            val hash = root.str("hash")
            val file = root.arr("chapter").orEmpty().objects().firstOrNull()?.str("file")
            if (baseUrl != null && hash != null && file != null) {
                listOf(Page(0, "$baseUrl/data/$hash/$file"))
            } else {
                emptyList()
            }
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
