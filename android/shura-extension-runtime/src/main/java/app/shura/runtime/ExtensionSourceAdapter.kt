package app.shura.runtime

import app.shura.source.ChapterNames
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * Adapts a loaded extension onto Shura's own `app.shura.source.ShuraSource`.
 *
 * Two ABI generations are supported, and the generation is probed from the
 * loaded class itself rather than assumed:
 *
 * 1. The legacy request/parse ABI (Shura's own in-repo extension modules and
 *    site sources), read out of the shipped DEXes:
 *
 *      searchMangaRequest(int page, String query, FilterList) -> okhttp3.Request
 *      searchMangaParse(okhttp3.Response) -> MangasPage
 *      mangaDetailsRequest(SManga) -> okhttp3.Request
 *      mangaDetailsParse(okhttp3.Response) -> SManga
 *      chapterListRequest(SManga) -> okhttp3.Request
 *      chapterListParse(okhttp3.Response) -> List<SChapter>
 *      pageListRequest(SChapter) -> okhttp3.Request
 *      pageListParse(okhttp3.Response) -> List<Page>
 *
 * 2. The keiyoushi-generated ABI (extensionLib 1.6, which the extensions
 *    Shura Core publishes use). The APK binds against these on its own,
 *    possibly obfuscated, base class - e.g. `getSearchManga` on `n0`/`v` in
 *    the AnyoneManga artifact - and they are suspend functions:
 *
 *      getSearchManga(int page, String query, FilterList, Continuation) -> MangasPage
 *      getMangaUpdate(SManga, List, boolean, boolean, Continuation) -> SMangaUpdate
 *      getPageList(SChapter, Continuation) -> List<Page>
 *
 * Both are called reflectively: the DEX binds against types that only exist
 * once the extension's class loader is in play, and a compile-time call from
 * Shura's module would defeat the point.
 *
 * Discovery is never widened to find a missing method: [probeSourceMethod]
 * walks the instance's own class chain, accepts only public/protected
 * declarations, and anything else is a hard failure naming the expected
 * signature.
 */
class ExtensionSourceAdapter(
    private val loaded: LoadedExtension,
    private val transport: ((Request) -> Pair<Int, okhttp3.ResponseBody>)? = null,
) : app.shura.source.ShuraSource {


    private val source: CatalogueSource get() = loaded.source

    override val id: String get() = loaded.id
    override val name: String get() = loaded.name
    override val language: String get() = loaded.language

    /**
     * Which ABI generation this source implements.
     *
     * The probe is the shape of a method, not the name of a class: the
     * keiyoushi artifacts are minified (their base classes carry names like
     * `v` or `n0`), so matching on `getSearchManga` with its exact parameter
     * list is the only identification that survives minification. A source
     * that declares nothing of either shape fails on first use, with the
     * missing signature named.
     */
    private val keiyoushiAbi: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        probeSourceMethod(
            "getSearchManga",
            arrayOf(Int::class.javaPrimitiveType!!, String::class.java, FilterList::class.java, Continuation::class.java),
        ) != null
    }

    override suspend fun search(query: String, page: Int): List<app.shura.source.MangaSummary> {
        val mangasPage = if (keiyoushiAbi) {
            val method = findSourceMethod(
                "getSearchManga",
                arrayOf(Int::class.javaPrimitiveType!!, String::class.java, FilterList::class.java, Continuation::class.java),
            )
            callSuspend(method, MangasPage::class.java, page, query, FilterList(emptyList()))
        } else {
            searchPage(query, page)
        }
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
        val chapters = if (keiyoushiAbi) {
            val method = findSourceMethod(
                "getMangaUpdate",
                arrayOf(
                    SManga::class.java,
                    java.util.List::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Continuation::class.java,
                ),
            )
            val manga = SManga.create().apply { url = mangaId }
            callSuspend(method, SMangaUpdate::class.java, manga, emptyList<SChapter>(), true, false).chapters
        } else {
            legacyChapters(mangaId)
        }
        return chapters.map { chapter ->
            // The extension's answer stops being trusted here. These are DEX files
            // this module did not compile and cannot change, and they read their
            // titles out of `org.json`, where `optString` on a JSON null returns
            // the four characters "null" on Android rather than nothing. Believed
            // as it arrived, that is a chapter list in which every row is titled
            // "null" - hundreds deep, with the chapter's own number in the column
            // beside it. [ChapterNames] is the one shared rule for that, and what
            // the row draws instead is decided from the chapter's own facts.
            app.shura.source.Chapter(chapter.url, ChapterNames.usable(chapter.name), chapter.chapter_number)
        }
    }

    private fun legacyChapters(mangaId: String): List<SChapter> {
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
        return chapters
    }

    override suspend fun pages(chapterId: String): List<app.shura.source.Page> {
        val pages = if (keiyoushiAbi) {
            val method = findSourceMethod(
                "getPageList",
                arrayOf(SChapter::class.java, Continuation::class.java),
            )
            val chapter = SChapter.create().apply { url = chapterId }
            @Suppress("UNCHECKED_CAST")
            callSuspend(method, java.util.List::class.java, chapter) as List<eu.kanade.tachiyomi.source.model.Page>
        } else {
            legacyPages(chapterId)
        }
        return pages.sortedBy { it.index }.map {
            app.shura.source.Page(it.index, it.imageUrl ?: it.url.orEmpty())
        }
    }

    private fun legacyPages(chapterId: String): List<eu.kanade.tachiyomi.source.model.Page> {
        val chapter = SChapter.create().apply { url = chapterId }
        val request = callRequest("pageListRequest", arrayOf<Class<*>>(SChapter::class.java), arrayOf<Any?>(chapter)) as? Request
            ?: throw IllegalStateException("pageListRequest did not return a Request")
        val (code, body) = fetch(request)
        check(code in 200..299) { "pages failed (HTTP $code)" }
        @Suppress("UNCHECKED_CAST")
        val pages = callParse("pageListParse", body) as? List<eu.kanade.tachiyomi.source.model.Page> ?: emptyList()
        return pages
    }

    /**
     * Invokes a Kotlin suspend function through its JVM form: the last
     * parameter is a [Continuation] and the return is either the value (the
     * call completed without suspending) or the runtime's "started" marker
     * (it suspended and will resume the continuation with the value).
     *
     * The marker is never an instance of the call's declared result type,
     * which is what [expected] distinguishes: a `MangasPage` that came back
     * is used as-is; anything else is the marker, so the suspension stays in
     * place until the target resumes its continuation - on whatever thread
     * the target's work finished. No assumption is made about which thread
     * that is, and the caller's cancellation stays wired to the target's
     * resumption through the [CancellableContinuation] that is handed over.
     */
    private suspend fun <T> callSuspend(method: Method, expected: Class<T>, vararg args: Any?): T {
        return suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
            val allArgs = Array(args.size + 1) { if (it < args.size) args[it] else cont }
            val raw = method.invoke(source, *allArgs)
            if (expected.isInstance(raw)) {
                @Suppress("UNCHECKED_CAST")
                cont.resumeWith(Result.success(raw as T))
            }
        }
    }

    private fun callRequest(method: String, types: Array<Class<*>>, args: Array<out Any?>): Any? {
        return findSourceMethod(method, types).invoke(source, *args)
    }

    private fun callParse(method: String, body: okhttp3.ResponseBody): Any? {
        val m = findSourceMethod(method, arrayOf(okhttp3.Response::class.java))
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

    private fun findSourceMethod(name: String, parameterTypes: Array<Class<*>>): Method =
        probeSourceMethod(name, parameterTypes)
            ?: throw NoSuchMethodException(
                "${source.javaClass.name}.$name(${parameterTypes.joinToString { it.simpleName }}) " +
                    "is not declared; the extension was not built against this ABI"
            )

    /**
     * The discovery gate.
     *
     * Only the instance's own class chain is searched - never the whole
     * class loader - and only public/protected declarations are ever
     * invoked: a private or package-private method is part of a class's
     * interior, and calling into it by name is how a hostile or broken
     * artifact reaches code the ABI does not expose.
     */
    private fun probeSourceMethod(name: String, parameterTypes: Array<Class<*>>): Method? {
        var type: Class<*>? = source.javaClass
        while (type != null) {
            val method = try {
                type.getDeclaredMethod(name, *parameterTypes)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (method != null) {
                val modifiers = method.modifiers
                if (Modifier.isProtected(modifiers) || Modifier.isPublic(modifiers)) {
                    method.isAccessible = true
                    return method
                }
            }
            type = type.superclass
        }
        return null
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
