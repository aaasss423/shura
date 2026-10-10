package app.shura.runtime

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Request

/**
 * Adapts a loaded extension onto Shura's own `app.shura.source.ShuraSource`.
 *
 * The method names and signatures are not invented: they were read out of the
 * shipped ProComic DEX, which declares
 *
 *   searchMangaRequest(int page, String query, FilterList) -> okhttp3.Request
 *   searchMangaParse(okhttp3.Response) -> MangasPage
 *   mangaDetailsParse(okhttp3.Response) -> SManga
 *   chapterListParse(okhttp3.Response) -> List<SChapter>
 *
 * They are called reflectively because the DEX binds against types that only
 * exist once the extension's class loader is in play; a compile-time call from
 * Shura's module would defeat the point.
 *
 * There is no `createSources()`: the APK proves the class *is* the Source.
 */
class ExtensionSourceAdapter(
    private val loaded: LoadedExtension,
    private val transport: ((Request) -> Pair<Int, okhttp3.ResponseBody>)? = null,
) : app.shura.source.ShuraSource {

    private val source: CatalogueSource get() = loaded.source

    override val id: String get() = loaded.id
    override val name: String get() = loaded.name
    override val language: String get() = loaded.language

    override suspend fun search(query: String, page: Int): List<app.shura.source.MangaSummary> {
        val mangasPage = searchPage(query, page)
        return mangasPage.mangas.map { it.toSummary() }
    }

    /** The full page, so a caller can see whether more results exist. */
    fun searchPage(query: String, page: Int): MangasPage {
        val request = callRequest(
            "searchMangaRequest",
            arrayOf(Int::class.javaPrimitiveType!!, String::class.java, FilterList::class.java),
            arrayOf<Any?>(page, query, FilterList(emptyArray())),
        ) as? Request ?: throw IllegalStateException("searchMangaRequest did not return a Request")
        val (code, body) = fetch(request)
        check(code in 200..299) { "search failed (HTTP $code)" }
        val parsed = callParse("searchMangaParse", body)
        return parsed as? MangasPage
            ?: throw IllegalStateException("searchMangaParse returned ${parsed?.javaClass?.name}, not MangasPage")
    }

    override suspend fun chapters(mangaId: String): List<app.shura.source.Chapter> {
        val manga = SManga.create().apply { url = mangaId }
        val request = callRequest(
            "mangaDetailsRequest", arrayOf<Class<*>>(SManga::class.java), arrayOf<Any?>(manga),
        ) as? Request ?: throw IllegalStateException("mangaDetailsRequest did not return a Request")
        val (code, body) = fetch(request)
        check(code in 200..299) { "details failed (HTTP $code)" }
        val detail = callParse("mangaDetailsParse", body) as? SManga
            ?: throw IllegalStateException("mangaDetailsParse did not return SManga")
        val chapterRequest = callRequest("chapterListRequest", arrayOf<Class<*>>(SManga::class.java), arrayOf<Any?>(detail)) as? Request
            ?: throw IllegalStateException("chapterListRequest did not return a Request")
        val (chapterCode, chapterBody) = fetch(chapterRequest)
        check(chapterCode in 200..299) { "chapters failed (HTTP $chapterCode)" }
        @Suppress("UNCHECKED_CAST")
        val chapters = callParse("chapterListParse", chapterBody) as? List<SChapter> ?: emptyList()
        return chapters.map { app.shura.source.Chapter(it.url, it.name, it.chapter_number) }
    }

    override suspend fun pages(chapterId: String): List<app.shura.source.Page> {
        val chapter = SChapter.create().apply { url = chapterId }
        val request = callRequest("pageListRequest", arrayOf<Class<*>>(SChapter::class.java), arrayOf<Any?>(chapter)) as? Request
            ?: throw IllegalStateException("pageListRequest did not return a Request")
        val (code, body) = fetch(request)
        check(code in 200..299) { "pages failed (HTTP $code)" }
        @Suppress("UNCHECKED_CAST")
        val pages = callParse("pageListParse", body) as? List<eu.kanade.tachiyomi.source.model.Page> ?: emptyList()
        return pages.sortedBy { it.index }.map {
            app.shura.source.Page(it.index, it.imageUrl ?: it.url.orEmpty())
        }
    }

    private fun callRequest(method: String, types: Array<Class<*>>, args: Array<out Any?>): Any? {
        val m = source.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.contentEquals(types) }
            ?: throw NoSuchMethodException(
                "${source.javaClass.name}.$method(${types.joinToString { it.simpleName }}) is not declared; " +
                    "the extension was not built against this ABI"
            )
        return m.invoke(source, *args)
    }

    private fun callParse(method: String, body: okhttp3.ResponseBody): Any? {
        val m = source.javaClass.methods.firstOrNull {
            it.name == method && it.parameterTypes.contentEquals(arrayOf(okhttp3.Response::class.java))
        } ?: throw NoSuchMethodException("${source.javaClass.name}.$method(okhttp3.Response) is not declared")
        // The ABI takes a Response, so the body is carried in a response whose
        // body has already been read; the extension parses the same bytes.
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://localhost/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
        return try {
            m.invoke(source, response)
        } finally {
            body.close()
        }
    }

    private fun SManga.toSummary() = app.shura.source.MangaSummary(url, title, thumbnail_url.takeIf { it.isNotBlank() })

    private fun fetch(request: Request): Pair<Int, okhttp3.ResponseBody> =
        transport?.invoke(request) ?: executeRequest(request)

    private fun executeRequest(request: Request): Pair<Int, okhttp3.ResponseBody> {
        val client = source.javaClass.methods.firstOrNull { it.name == "getClient" && it.parameterCount == 0 }
            ?.invoke(source) as? okhttp3.OkHttpClient
            ?: NetworkHelper.client
        return client.newCall(request).execute().use { response ->
            response.code to (response.body ?: throw IllegalStateException("empty body"))
        }
    }
}