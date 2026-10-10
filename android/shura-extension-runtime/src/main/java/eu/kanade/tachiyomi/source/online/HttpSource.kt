// Transitional HttpSource + network surface.
//
// The DEX requires exactly:
//   eu.kanade.tachiyomi.source.online.HttpSource.<init>() -> void
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
    open val versionId: Int = 1
    abstract override val lang: String
    abstract override val name: String

    /** The shared, host-owned client. Extensions must not construct their own. */
    val client: OkHttpClient get() = NetworkHelper.client

    /** Raw GET against the source's base URL. Overridden per extension. */
    open fun request(path: String): Request = Request.Builder().url(baseUrl + path).build()
}