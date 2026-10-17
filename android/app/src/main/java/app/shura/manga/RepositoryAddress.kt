package app.shura.manga

import java.net.URL
import java.security.MessageDigest

/**
 * What is wrong with a typed repository address.
 *
 * Named rather than carried as a sentence, because the sentence has to be in the
 * user's language and this is not where languages live - [RepositoryStatusText]
 * turns each of these into a localised line. What has to live here is the
 * decision, and every one of these is a refusal rather than a repair: an
 * address the app cannot parse is not one it should quietly rewrite into
 * something it thinks the user meant.
 */
enum class AddressProblem {
    /** Nothing was typed. */
    EMPTY,

    /** Not a URL at all, or a URL with no host. */
    MALFORMED,

    /**
     * Plain HTTP.
     *
     * The manifest sets `usesCleartextTraffic="false"`, so a cleartext fetch
     * would fail anyway - but it would fail as a confusing network error rather
     * than as "that address is not secure", so it is refused here where the
     * reason can be said plainly.
     */
    NOT_HTTPS,

    /** A URL with credentials in it. Never used by a repository, and a way to
     *  make an address look like one host while pointing somewhere else. */
    HAS_USER_INFO,

    /** A URL carrying a query string, which cannot be extended with a path. */
    HAS_QUERY,
}

/** A repository address that passed [RepositoryAddress.parse]. */
data class RepositoryAddress(
    val url: URL,
    /** The address as it will be shown and stored: normalised, no trailing slash. */
    val base: String,
    /** Host, for a row that has to say where a repository came from. */
    val host: String,
    /**
     * A stable directory name for this repository.
     *
     * Two repositories on the same host at different paths must not share a
     * directory, and a repository that is added again after a removal must land
     * back where it was, so the id is derived from the normalised address and
     * carries a digest of it. Readable because a directory listing is sometimes
     * the fastest way to find out what is on the device, and unique because the
     * digest is.
     */
    val id: String,
) {

    /** Whether an address was accepted, and what was accepted. */
    sealed interface Result {
        data class Success(val address: RepositoryAddress) : Result
        data class Failure(val problem: AddressProblem) : Result
    }

    /**
     * Deciding whether a typed address may be fetched from.
     *
     * WHY THIS EXISTS
     *
     * A repository address is typed by hand, so every field of it is a place
     * where the string a user pasted and the address that is actually contacted
     * can come apart. This is the one place that decides they are the same
     * thing.
     *
     * WHY IT IS NOT A DOWNLOAD
     *
     * No socket is opened here. Everything in this file is a decision about a
     * string, which is why it can be tested without a device and without a
     * network, and why the reasons it returns are the reasons the row shows
     * rather than whatever an HTTP library happened to throw.
     */
    companion object {

        /** Hosts that may be reached over cleartext, and only when asked for. */
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        private val SAFE_ID = Regex("[^a-z0-9]+")

        /**
         * Validates and normalises [raw].
         *
         * [allowCleartext] exists for a debug build talking to a loopback server -
         * the emulator's own host - and is false everywhere else, which is the only
         * value the app itself uses. It never widens anything: a cleartext address
         * is still refused unless its host is loopback.
         */
        fun parse(raw: String, allowCleartext: Boolean = false): Result {
            val text = raw.trim()
            if (text.isEmpty()) return Result.Failure(AddressProblem.EMPTY)
            // Anything with whitespace in it is a paste that went wrong, and
            // java.net.URL is lenient enough to accept some of it.
            if (text.any { it.isWhitespace() }) return Result.Failure(AddressProblem.MALFORMED)
            val url = try {
                URL(text)
            } catch (_: Exception) {
                return Result.Failure(AddressProblem.MALFORMED)
            }
            val scheme = url.protocol?.lowercase().orEmpty()
            if (scheme != "https" && scheme != "http") {
                return Result.Failure(AddressProblem.MALFORMED)
            }
            val host = url.host?.lowercase().orEmpty()
            if (host.isEmpty()) return Result.Failure(AddressProblem.MALFORMED)
            if (url.userInfo != null) return Result.Failure(AddressProblem.HAS_USER_INFO)
            if (!url.query.isNullOrEmpty()) return Result.Failure(AddressProblem.HAS_QUERY)
            if (scheme != "https") {
                val loopback = host in LOOPBACK || host.removePrefix("[") in LOOPBACK
                if (!allowCleartext || !loopback) return Result.Failure(AddressProblem.NOT_HTTPS)
            }
            val port = if (url.port == -1) "" else ":${url.port}"
            val path = url.path.trimEnd('/')
            val base = "$scheme://$host$port$path"
            return Result.Success(
                RepositoryAddress(
                    url = URL(base),
                    base = base,
                    host = host,
                    id = idFor(host, port, path),
                ),
            )
        }

        /** Whether [raw] may be fetched from, without building anything from it. */
        fun isValid(raw: String, allowCleartext: Boolean = false): Boolean =
            parse(raw, allowCleartext) is Result.Success

        /**
         * The directory name for one address.
         *
         * Readable first and unique second: `shura.app-repository-1a2b3c4d` says
         * what it is when someone is reading a file listing, and the digest is what
         * stops two paths on one host from colliding into one directory - which
         * would make removing one repository remove the other.
         */
        private fun idFor(host: String, port: String, path: String): String {
            val canonical = "$host$port$path"
            val slug = canonical.lowercase()
                .replace(SAFE_ID, "-")
                .trim('-')
                .take(48)
                .ifEmpty { "repository" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(8)
            return "$slug-$digest"
        }
    }
}
