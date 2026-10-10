// Transitional network surface.
//
// The DEX requires:
//   eu.kanade.tachiyomi.network.NetworkHelper.getClient() -> okhttp3.OkHttpClient
//   eu.kanade.tachiyomi.network.RequestsKt.GET$default(String, okhttp3.Headers,
//       okhttp3.CacheControl, int, Object) -> okhttp3.Request
//
// `RequestsKt` is the JVM name of a Kotlin file-level function `GET`, so the
// function is declared at file scope in this file.
//
// This client is Shura's. Extensions issue their requests through it, which is
// what bounds their traffic: connect/read timeouts, a response ceiling and a
// redirect policy are decided by the host, not by the APK being loaded.
package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Owns the single client every extension uses. */
object NetworkHelper {
    /**
     * Shared client.
     *
     * `followRedirects` stays on because real source hosts redirect, but the
     * hop count and the timeouts are ours, so a misbehaving extension cannot
     * make the host wait indefinitely.
     */
    @JvmStatic
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * `GET` as the shipped extensions call it.
 *
 * The trailing `int` in the DEX descriptor is Kotlin's default-argument mask,
 * which is why `cache` and `headers` need defaults.
 */
@JvmOverloads
fun GET(
    url: String,
    headers: Headers? = null,
    cache: CacheControl? = null,
): Request = Request.Builder().url(url).apply {
    headers?.let { headers(it) }
    cache?.let { cacheControl(it) }
}.build()