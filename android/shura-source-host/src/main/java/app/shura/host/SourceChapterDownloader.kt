package app.shura.host

import app.shura.source.Page
import java.io.File
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Downloads chapter pages from whichever host the source itself named.
 *
 * The MangaDex-only allowlist in [MangaDexChapterDownloader] is the wrong rule for a
 * reader whose whole point is extensions: every other source publishes on its own
 * CDN, so every page of theirs was refused and became an error tile. But simply
 * dropping the check would be worse - a source could name `169.254.169.254` and the
 * app would fetch it on the reader's behalf.
 *
 * So the rule becomes: a source may fetch from the hosts it declared, and the hosts
 * it may declare are any public host. Everything else about the transport is
 * unchanged - HTTPS only, no credentials in the URL, the standard port, a byte
 * ceiling, per-hop redirect revalidation, and resumable partial files. The trust
 * boundary moves from "one hardcoded domain" to "the source itself, which the app
 * already verifies before it runs".
 */
class SourceChapterDownloader(private val downloader: SourceDownloader = SourceDownloader()) {

    companion object {
        /** Ten megabytes, the same ceiling MangaDex pages were held to. */
        const val MAX_BYTES_PER_PAGE: Long = 10_000_000L

        /**
         * Hostnames that always mean "this device or this network", never a comic.
         *
         * Refused by name as well as by address, because `localhost` and
         * `router.local` resolve without a lookup and a name check costs nothing.
         */
        private val LOCAL_SUFFIXES = listOf(
            "localhost", "local", "internal", "lan", "home.arpa",
        )

        /**
         * True when the host cannot be a public server.
         *
         * Resolves the name as well as reading the literal: `127.0.0.1.nip.io` and
         * friends resolve to loopback, and a check that only read the string would
         * wave them through.
         */
        fun isUnreachableHost(host: String): Boolean {
            val name = IDN.toASCII(host.lowercase().removeSuffix("."))
            if (name.isEmpty()) return true
            if (LOCAL_SUFFIXES.any { name == it || name.endsWith(".$it") }) return true
            // Fail open when the name cannot be resolved: a device in airplane mode
            // cannot resolve anything, and refusing every host there would make an
            // offline reader refuse a legitimate CDN. Resolution success is a bonus
            // check - it catches a public name that points at loopback - not the
            // gate. The gate stays: HTTPS, the declared-host allowlist, and the
            // per-hop revalidation in SourceDownloader, which re-checks every
            // redirect target against that same list.
            val literal = runCatching { InetAddress.getByName(name) }.getOrNull() ?: return false
            return when (literal) {
                is Inet4Address -> literal.isAnyLocalAddress ||
                    literal.isLoopbackAddress ||
                    literal.isLinkLocalAddress ||
                    literal.isSiteLocalAddress ||
                    literal.isMulticastAddress
                is Inet6Address -> literal.isAnyLocalAddress ||
                    literal.isLoopbackAddress ||
                    literal.isLinkLocalAddress ||
                    literal.isSiteLocalAddress ||
                    literal.isMulticastAddress
                else -> true
            }
        }

        /** The hosts a chapter's pages point at, refusing any that are not public. */
        fun allowedHostsForPages(pages: List<Page>): Set<String> {
            require(pages.isNotEmpty()) { "chapter has no pages" }
            val hosts = pages.map { page ->
                val host = URI(page.imageUrl).host?.lowercase()
                require(!host.isNullOrBlank()) { "page has no host: ${page.imageUrl}" }
                require(!isUnreachableHost(host)) { "download host is not reachable from a reader: $host" }
                host
            }
            return hosts.toSet()
        }
    }

    /** The hosts this chapter is allowed to fetch from, per page. */
    fun allowedHostsForPage(page: Page): Set<String> = allowedHostsForPages(listOf(page))

    /** Downloads exactly one page, resumable, under the rule above. */
    fun downloadPage(
        page: Page,
        destinationDir: File,
        maxBytesPerPage: Long = MAX_BYTES_PER_PAGE,
        report: ((done: Int, total: Int) -> Unit)? = null,
    ): File {
        destinationDir.mkdirs()
        val file = File(destinationDir, String.format("page-%03d.img", page.index + 1))
        if (file.exists() && file.length() > 0L) {
            report?.invoke(1, 1)
            return file
        }
        return downloader.download(page.imageUrl, file, allowedHostsForPage(page), maxBytesPerPage).file
            .also { report?.invoke(1, 1) }
    }

    /** The whole chapter, already-present pages kept. */
    fun downloadChapter(
        pages: List<Page>,
        destinationDir: File,
        maxBytesPerPage: Long = MAX_BYTES_PER_PAGE,
        report: ((done: Int, total: Int) -> Unit)? = null,
    ): List<File> {
        require(pages.isNotEmpty()) { "chapter has no pages" }
        allowedHostsForPages(pages)
        val ordered = pages.sortedBy { it.index }
        return ordered.mapIndexed { position, page ->
            downloadPage(page, destinationDir, maxBytesPerPage) { done, _ ->
                report?.invoke(position + done, ordered.size)
            }
        }
    }
}
