package app.shura.source.site

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response

/**
 * What a generated extension needs to know in order to read one site.
 *
 * This is the whole of the "site specific" part. Everything that varies between
 * sites is data here; every line of behaviour lives in [SiteCatalogueSource] and
 * is written once. That is what makes the site-to-extension conversion
 * reusable rather than a folder of hand-written extensions.
 *
 * The values are produced by Shura's crawler from evidence the site actually
 * served. Nothing here is a guess that the engine would act on silently: see
 * [SiteCatalogueSource], which refuses a spec it cannot use and says why.
 */
data class SiteSpec(
    /** Shura's own identity for this source, as a stable number. */
    val sourceId: Long,
    val name: String,
    val lang: String,

    /** The work index. Search with an empty query returns the whole index. */
    val catalogueUrl: String,

    /**
     * Matches the hrefs that are entries of the catalogue.
     *
     * Two jobs: say which links are candidates at all, and separate works from
     * chapters of a work. A catalogue index routinely links chapters and works
     * with the same URL shape, so this matches both and the split is made on
     * the link's text and the page it points at.
     */
    val entryHref: Regex,

    /** How many links must share a shape before it counts as a listing, not a menu. */
    val minListingGroup: Int = 4,

    /**
     * A trailing "- Chapter 12" / "- الفصل 4" on a link's text marks it as a
     * chapter of the work named before it. Empty means the site titles its
     * chapters some other way and its index links works only.
     */
    val chapterMarker: Regex = Regex(""),

    /**
     * Matches the hrefs that are chapters rather than works.
     *
     * Titles are not dependable for this. One live catalogue writes a chapter as
     * "Chapter 6" butted against "2 days ago", so the text is "Chapter 62 days
     * ago": the number in it belongs to nothing, and a rule that reads a
     * trailing number out of it is reading nonsense. The URL says what the
     * entry is, so it is asked first.
     */
    val chapterHref: Regex = Regex(""),

    /** The first heading of a page is the work's own title, and outranks the index. */
    val trustPageHeading: Boolean = true,

    /** Matches the image srcs that are a chapter's reading pages. */
    val pageImage: Regex,

    /**
     * The path prefix a reading page of *this* chapter must live under.
     *
     * An image extension is not evidence of anything. Every catalogue serves
     * logos, banners and cover art as .jpg and .webp alongside its actual
     * pages, and a chapter page that returns those instead of pages produces a
     * chapter the reader opens to find nothing - which looks like a broken
     * manga rather than an unsupported site.
     *
     * So a page has to be *attributable to the chapter it was found on*. The
     * real sites put a chapter's pages under a path that contains that
     * chapter's own identity - the work slug and the chapter number - and put
     * covers and logos elsewhere. This is that path, proven by reading the
     * chapter and derived by the converter, so it is data and not a guess.
     */
    val pageImagePrefix: Regex,

    /**
     * The host the reading pages are served from.
     *
     * Usually not the site's own host: catalogues put images on a CDN. Recorded
     * because the reader fetches these URLs, and a host nobody declared is a
     * host nobody is allowed to reach.
     */
    val imageHost: String = "",

    /** Matches the image srcs that are a work's cover. Optional. */
    val coverImage: Regex? = null,
)

/**
 * A [CatalogueSource] built from a [SiteSpec].
 *
 * This is the generic half of the conversion: it implements the eight methods
 * Shura's [app.shura.runtime.ExtensionSourceAdapter] calls, entirely in terms of
 * the spec. A generated extension supplies a spec and inherits all of it.
 *
 * The failure behaviour is deliberate. When a site does not match the shape the
 * spec claims, the result is an empty list or an explicit error - never a
 * fabricated work, chapter or page. An extension that guesses is worse than one
 * that says it cannot read the site, because the guess reaches the reader as a
 * manga that does not exist.
 */
/**
 * The eight methods below are NOT overrides.
 *
 * Shura's ExtensionSourceAdapter calls them reflectively
 * (app.shura.runtime.ExtensionSourceAdapter.callRequest/callParse) because the
 * extension binds against types that only exist once its class loader is in
 * play. The transitional ABI declares none of them, so declaring `override`
 * here would be a compile error - and, worse, would tie this engine to an ABI
 * Shura is explicitly keeping transitional.
 */
abstract class SiteCatalogueSource : HttpSource() {

    /** The site root every request is resolved against. */
    abstract override val baseUrl: String

    /** The site this extension reads. Supplied by the generated subclass. */
    protected abstract val spec: SiteSpec

    override val lang: String get() = spec.lang
    override val name: String get() = spec.name
    override val id: Long get() = spec.sourceId

    /**
     * The index the last search already fetched, kept so a chapter list can be
     * answered from bytes the reader's own search already paid for.
     *
     * Bounded to one page: this is a cache of what was just read, not a store.
     */
    private var cachedIndex: String? = null

    /** The work a chapter request is currently being answered for. */
    private var pendingWorkTitle: String? = null

    // -- search -----------------------------------------------------------

    fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        Request.Builder().url(SiteHtml.searchUrl(spec, page, query)).build()

    /**
     * The ABI gives the parser a Response and nothing else, so the query
     * travels in the URL fragment. A fragment is never sent to the server: this
     * asks the site for exactly what the site serves and filters the answer
     * locally, instead of guessing a search route the site was never shown.
     */
    fun searchMangaParse(response: Response): eu.kanade.tachiyomi.source.model.MangasPage {
        cachedIndex = response.body?.string().orEmpty()
        val query = response.request.url.fragment?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
        }.orEmpty()
        return eu.kanade.tachiyomi.source.model.MangasPage(
            SiteHtml.works(spec, cachedIndex.orEmpty(), query),
            hasNextPage = false,
        )
    }

    // -- details ----------------------------------------------------------

    fun mangaDetailsRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request =
        Request.Builder().url(manga.url).build()

    fun mangaDetailsParse(response: Response): eu.kanade.tachiyomi.source.model.SManga {
        val html = response.body?.string().orEmpty()
        val manga = eu.kanade.tachiyomi.source.model.SManga.create()
        val heading = SiteHtml.heading(spec, html)
        manga.title = heading.ifBlank { manga.title }
        manga.thumbnail_url = SiteHtml.cover(spec, html, manga.url).orEmpty()
        manga.initialized = true
        return manga
    }

    // -- chapters ---------------------------------------------------------

    /**
     * Where a work's chapters are read from.
     *
     * The work's own page first: when it lists its chapters, that is the
     * cheapest correct answer and it survives the site reorganising its index.
     * A site that keeps its chapter links only in the index is handled by
     * [SiteHtml.chaptersFor], which falls back rather than reporting no chapters.
     */
    fun chapterListRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request {
        pendingWorkTitle = manga.title
        return Request.Builder().url(manga.url).build()
    }

    fun chapterListParse(response: Response): List<eu.kanade.tachiyomi.source.model.SChapter> {
        val html = response.body?.string().orEmpty()
        val fromWorkPage = SiteHtml.chapters(spec, html, response.request.url.toString())
        val workTitle = pendingWorkTitle.orEmpty()
        pendingWorkTitle = null
        val entries = if (fromWorkPage.isNotEmpty()) fromWorkPage
            else SiteHtml.chaptersFor(spec, "", spec.catalogueUrl, workTitle,
                cachedIndex.orEmpty())
        return entries.map { entry ->
                eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                    url = entry.url
                    name = entry.title
                    chapter_number = entry.number
                }
            }
    }

    // -- pages ------------------------------------------------------------

    fun pageListRequest(chapter: eu.kanade.tachiyomi.source.model.SChapter): Request =
        Request.Builder().url(chapter.url).build()

    fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> =
        SiteHtml.pages(spec, response.body?.string().orEmpty(), response.request.url.toString())
            .mapIndexed { index, image ->
                eu.kanade.tachiyomi.source.model.Page(index, image)
            }
}