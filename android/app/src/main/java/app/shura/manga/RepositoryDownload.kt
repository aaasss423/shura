package app.shura.manga

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Why a download did not finish. */
enum class FetchProblem {
    /** The address could not be reached at all. */
    NETWORK,

    /** A redirect pointed at a different host or port, and was not followed. */
    REDIRECT,

    /** The server answered with something other than a file. */
    STATUS,

    /** More bytes arrived than this repository is allowed to occupy. */
    TOO_LARGE,

    /** The connection produced no bytes. */
    EMPTY,
}

/** A download that stopped, with the reason in the user's terms. */
class RepositoryFetchException(
    val problem: FetchProblem,
    message: String,
) : IOException(message)

/**
 * Fetching one repository file, under rules that do not change per file.
 *
 * WHY THIS EXISTS
 *
 * Adding a repository means opening a connection to an address a person typed,
 * before anything about that address has been checked. The checks that matter
 * are all here, in one place, so that adding `repo.json` and adding an APK go
 * through the same ones rather than through whichever call site wrote them
 * first.
 *
 * THE RULES
 *
 *  - redirects are followed by hand, never by the URL class, so that one which
 *    leaves the host it started on is refused instead of obeyed;
 *  - every hop is re-checked for its scheme, so a redirect cannot downgrade an
 *    HTTPS fetch to a cleartext one;
 *  - the response is bounded while it is read, not after, so an endless stream
 *    cannot fill the disk before the limit is noticed;
 *  - both a connect and a read timeout are set, so a server that accepts a
 *    connection and then says nothing cannot hold the screen.
 *
 * None of this decides whether the repository is *trusted*. That is the
 * client's job, unchanged, and it runs on the bytes this leaves on disk.
 */
class RepositoryDownload(
    private val allowCleartext: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 3,
) {

    /**
     * Fetches `<address>/<path>` into [into] and returns how many bytes landed.
     *
     * [into] is deleted on every failure, so a half-written file is never
     * mistaken later for a repository that was fetched. The failure is thrown,
     * not returned, so no caller can forget it.
     */
    fun fetch(address: RepositoryAddress, path: String, into: File, maxBytes: Long): Long {
        var current = URL(address.base.trimEnd('/') + "/" + path.trimStart('/'))
        var hops = 0
        while (true) {
            val connection = try {
                current.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                into.delete()
                throw RepositoryFetchException(
                    FetchProblem.NETWORK, e.message ?: "$current could not be opened",
                )
            }
            try {
                connection.apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Accept", "application/json, */*")
                    setRequestProperty("User-Agent", "Shura")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        throw RepositoryFetchException(
                            FetchProblem.STATUS, "$current answered $code with no Location",
                        )
                    }
                    if (hops++ >= maxRedirects) {
                        throw RepositoryFetchException(
                            FetchProblem.REDIRECT,
                            "$current redirected more than $maxRedirects times",
                        )
                    }
                    val next = try {
                        URL(current, location)
                    } catch (_: Exception) {
                        throw RepositoryFetchException(
                            FetchProblem.REDIRECT, "$current redirected to an address that is not a URL",
                        )
                    }
                    // Same host *and* port, or nothing. A redirect is allowed to
                    // move a path; it is not allowed to move the origin, because
                    // the origin is what the address the user typed means.
                    if (!sameOrigin(current, next)) {
                        throw RepositoryFetchException(
                            FetchProblem.REDIRECT,
                            "$current redirected to $next, which is a different host or port; " +
                                "it was not followed",
                        )
                    }
                    if (!isSafeScheme(next)) {
                        throw RepositoryFetchException(
                            FetchProblem.REDIRECT,
                            "$current redirected to ${next.protocol}, which is not HTTPS",
                        )
                    }
                    current = next
                    continue
                }
                if (code !in 200..299) {
                    throw RepositoryFetchException(
                        FetchProblem.STATUS, "$path was answered with $code by $current",
                    )
                }
                val declared = connection.contentLengthLong
                if (declared > maxBytes) {
                    throw RepositoryFetchException(
                        FetchProblem.TOO_LARGE,
                        "$path declares $declared bytes, over the $maxBytes this repository may use",
                    )
                }
                return copy(connection, into, maxBytes, path)
            } catch (e: RepositoryFetchException) {
                into.delete()
                throw e
            } catch (e: Exception) {
                into.delete()
                throw RepositoryFetchException(
                    FetchProblem.NETWORK, e.message ?: "$path could not be fetched",
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Reads the body into [into], refusing to exceed [maxBytes] as it goes. */
    private fun copy(
        connection: HttpURLConnection,
        into: File,
        maxBytes: Long,
        path: String,
    ): Long {
        into.parentFile?.mkdirs()
        var written = 0L
        connection.inputStream.use { input ->
            into.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    // Checked as it is written, not afterwards: a response that
                    // never ends would otherwise fill the disk before the size
                    // was ever looked at.
                    if (written > maxBytes) {
                        throw RepositoryFetchException(
                            FetchProblem.TOO_LARGE,
                            "$path is larger than the $maxBytes this repository may use",
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (written == 0L) {
            throw RepositoryFetchException(FetchProblem.EMPTY, "$path came back empty")
        }
        return written
    }

    /** Same scheme, host and port - the origin the user actually named. */
    private fun sameOrigin(from: URL, to: URL): Boolean {
        fun port(u: URL) = if (u.port == -1) u.protocol.lowercase() else u.port
        return from.protocol.equals(to.protocol, ignoreCase = true) &&
            from.host.equals(to.host, ignoreCase = true) &&
            port(from) == port(to)
    }

    /**
     * HTTPS, always - except a cleartext loopback when the build was told it may
     * use one, which is the same rule [RepositoryAddress] applies and for the
     * same reason.
     */
    private fun isSafeScheme(url: URL): Boolean {
        val scheme = url.protocol.lowercase()
        if (scheme == "https") return true
        val host = url.host?.lowercase().orEmpty().removePrefix("[")
        return allowCleartext && scheme == "http" &&
            (host == "127.0.0.1" || host == "localhost" || host == "::1")
    }
}