package app.shura.extension.mangadex

import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MangaDex as a Shura source.
 *
 * WHY THIS EXTENDS HttpSource
 *
 * It used to implement `app.shura.source.ShuraSource` and nothing else, on the
 * understanding that was "Shura's own contract and no other project's types".
 * That was the wrong contract. ShuraExtensionLoader does not load a
 * `ShuraSource`: it loads whatever the manifest names, requires it to be a
 * `CatalogueSource`, and adapts that onto `ShuraSource` itself. So an
 * extension implementing only `ShuraSource` was rejected by the loader's own
 * type check and could never have loaded, however correctly it was built.
 *
 * The eight methods below are the ABI Shura's ExtensionSourceAdapter calls
 * reflectively. They are deliberately NOT `override`s: the transitional ABI
 * declares none of them, and declaring `override` would tie this extension to
 * an interface Shura is explicitly keeping transitional.
 *
 * MangaDex's public API needs no key, so this holds no credentials. Only the
 * three operations the contract declares are implemented - no library listing,
 * no genre filter, no tracking of read state.
 */
class MangaDexSource : HttpSource() {

    override val baseUrl: String = "https://api.mangadex.org"

    /** Stable across runs: Shura keys a loaded extension by this number. */
    override val id: Long = 0x4D444558L

    override val name: String = "MangaDex"
    override val lang: String = "ar"

    // -- the eight methods Shura's adapter calls reflectively ---------------

    fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        api(path = "/manga?title=${encode(query)}&limit=20&offset=${offset(page)}")

    fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val root = json(response.body?.string())
        val mangas = (root?.arr("data").orEmpty().objects()).map { item ->
            SManga.create().apply {
                url = item.str("id").orEmpty()
                title = title(item.obj("attributes"))
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    fun mangaDetailsRequest(manga: SManga) = api("/manga/${manga.url}")

    fun mangaDetailsParse(response: okhttp3.Response): SManga {
        val root = json(response.body?.string())
        val attributes = root?.obj("attributes")
        return SManga.create().apply {
            url = root?.str("id").orEmpty()
            title = title(attributes)
            // MangaDex puts the cover on a separate endpoint, and only as a
            // file name. Without the base the reader is shown a relative path.
            thumbnail_url = attributes?.obj("coverImage")?.str("fileName")
                ?.let { "https://uploads.mangadex.org/covers/$it" }.orEmpty()
            initialized = true
        }
    }

    fun chapterListRequest(manga: SManga) =
        api("/manga/${manga.url}/feed?translatedLanguage[]=$lang&limit=100")

    fun chapterListParse(response: okhttp3.Response): List<SChapter> =
        (json(response.body?.string())?.arr("data").orEmpty().objects())
            .map { item ->
                val attributes = item.obj("attributes")
                SChapter.create().apply {
                    url = item.str("id").orEmpty()
                    name = attributes?.str("title").orEmpty()
                    // MangaDex sends `chapter` as a quoted string ("792"), not a
                    // JSON number, so reading it as one left every chapter
                    // unnumbered and the order meaningless.
                    chapter_number = attributes?.num("chapter")?.toFloat()
                        ?: attributes?.str("chapter")?.trim()?.toFloatOrNull()
                        ?: -1f
                }
            }
            .sortedByDescending { it.chapter_number }

    fun pageListRequest(chapter: SChapter) = api("/at-home/server/${chapter.url}")

    fun pageListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.source.model.Page> {
        val root = json(response.body?.string()) ?: return emptyList()
        val base = root.str("baseUrl") ?: return emptyList()
        // `chapter` is not an array of filenames and not a map of them: it is
        // an object holding the image `hash` and a `data` array of page file
        // names. Reading either the wrong shape yields no pages at all.
        val chapter = root.obj("chapter") ?: return emptyList()
        val hash = chapter.str("hash") ?: return emptyList()
        // The array holds strings. Filtering it through objects() - which keeps
        // only JSON objects - silently produced an empty page list.
        val files = chapter.arr("data").orEmpty()
            .mapNotNull { (it as? JsonNode.Str)?.value }
        // The API returns pages in order, but the contract promises an ordered
        // list, so the order is established rather than assumed.
        return files
            .sortedWith(compareBy({ it.takeWhile { c -> !c.isDigit() }.length },
                                  { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }))
            .mapIndexed { index, name ->
                eu.kanade.tachiyomi.source.model.Page(index, "$base/data/$hash/$name")
            }
    }

    // -- the ShuraSource contract, kept -----------------------------------
    //
    // `ShuraSource` is what the app consumes; this class is what the loader can
    // actually instantiate. Both are served here: the adapter calls the eight
    // methods above, and ShuraSource's three are the same logic.

    suspend fun search(query: String, page: Int): List<MangaSummary> =
        withContext(Dispatchers.IO) {
            val root = json(get("/manga?title=${encode(query)}&limit=20&offset=${offset(page)}"))
            root?.arr("data").orEmpty().objects().map { item ->
                MangaSummary(id = item.str("id").orEmpty(),
                    title = title(item.obj("attributes")), coverUrl = null)
            }
        }

    suspend fun chapters(mangaId: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val root = json(get("/manga/$mangaId/feed?translatedLanguage[]=$lang&limit=100"))
            root?.arr("data").orEmpty().objects().map { item ->
                val attributes = item.obj("attributes")
                Chapter(id = item.str("id").orEmpty(), name = attributes?.str("title").orEmpty(),
                    number = attributes?.num("chapter")?.toFloat()
                        ?: attributes?.str("chapter")?.trim()?.toFloatOrNull())
            }.sortedByDescending { it.number ?: Float.NEGATIVE_INFINITY }
        }

    suspend fun pages(chapterId: String): List<Page> =
        withContext(Dispatchers.IO) {
            val root = json(get("/at-home/server/$chapterId")) ?: return@withContext emptyList()
            val baseUrl = root.str("baseUrl") ?: return@withContext emptyList()
            val chapter = root.obj("chapter") ?: return@withContext emptyList()
            val hash = chapter.str("hash") ?: return@withContext emptyList()
            val files = chapter.arr("data").orEmpty()
                .mapNotNull { (it as? JsonNode.Str)?.value }
            files.sortedWith(compareBy({ it.takeWhile { c -> !c.isDigit() }.length },
                                       { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }))
                .mapIndexed { index, name -> Page(index, "$baseUrl/data/$hash/$name") }
        }

    // -- helpers ----------------------------------------------------------

    private fun api(path: String) =
        okhttp3.Request.Builder().url(baseUrl + path).build()

    private fun offset(page: Int): Int = (page - 1).coerceAtLeast(0) * 20

    /** A bare space makes the URL invalid, and the request then quietly
     *  became an empty result. */
    private fun encode(query: String) = URLEncoder.encode(query, "UTF-8")

    /**
     * MangaDex returns `title` as a map keyed by language, not a string -
     * e.g. {"ja-ro": "One Piece"}. Reading it as a string returned null and
     * every result came back with a blank title. Prefer the extension's own
     * language, then English, then whatever the source has.
     */
    private fun title(attrs: JsonNode.Obj?): String {
        val titles = attrs?.obj("title") ?: return ""
        titles.str(lang)?.let { return it }
        titles.str("en")?.let { return it }
        return titles.fields.values.filterIsInstance<JsonNode.Str>().firstOrNull()?.value ?: ""
    }

    private fun get(path: String): String? = try {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
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