// Transitional HttpSource + network surface.
//
// The DEX requires exactly:
//   eu.kanade.tachiyomi.source.online.HttpSource.<init>() -> void
//   eu.kanade.tachiyomi.source.online.HttpSource.getNetwork() ->
//       eu.kanade.tachiyomi.network.NetworkHelper
//   eu.kanade.tachiyomi.network.NetworkHelper.getClient() -> okhttp3.OkHttpClient
//   eu.kanade.tachiyomi.network.RequestsKt.GET$default(String, Headers,
//       CacheControl, int, Object) -> okhttp3.Request
//
// HttpSource is what ProComic extends. The abstract search/detail members are
// declared here as open so an extension class resolves them; Shura never calls
// them directly - it goes through reflection in ExtensionLoader and adapts the
// result onto app.shura.source.ShuraSource.
//
// The network client is Shura's own, timeouts included, so an extension's
// traffic is bounded by the host rather than by the APK.
package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Base class of the shipped extensions.
 *
 * `lang`, `name`, `baseUrl` and `versionId` are left abstract because the
 * extensions override them with their own constants; declaring them open here
 * keeps the loader from having to know their names beyond the three the DEX
 * actually references.
 */
abstract class HttpSource : CatalogueSource {
    abstract val baseUrl: String
    override val id: Long get() = 0L
    open val versionId: Int = 1
    abstract override val lang: String
    abstract override val name: String

    /** The shared host network surface expected by the published extensions. */
    open val network: NetworkHelper get() = NetworkHelper

    /** The shared, host-owned client. Extensions must not construct their own. */
    open val client: OkHttpClient get() = NetworkHelper.client

    open val headers: okhttp3.Headers by lazy { okhttp3.Headers.Builder().build() }
    open fun headersBuilder(): okhttp3.Headers.Builder = okhttp3.Headers.Builder()

    /** Raw GET against the source's base URL. Overridden per extension. */
    open fun request(path: String): Request = Request.Builder().url(baseUrl + path).build()
}