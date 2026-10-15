package app.shura.host

import app.shura.source.Page
import java.io.File
import java.net.URI

/** Downloads a chapter's page images with Shura's bounded per-hop HTTPS loader and MangaDex-only host allowlist. */
class MangaDexChapterDownloader(private val downloader: SourceDownloader = SourceDownloader()) {

    private companion object {
        /** Always permitted, regardless of what a page's own URL points at. */
        val MANDATORY_HOSTS = setOf(
            "mangadex.org", "mangadex.network",
            "uploads.mangadex.org", "uploads.mangadex.network",
        )
    }

    /** Exact host allowlist for chapter pages, refusing anything outside MangaDex domains. */
    fun allowedHostsForPages(pages: List<Page>): Set<String> {
        require(pages.isNotEmpty()) { "chapter has no pages" }
        val actual = pages.map { URI(it.imageUrl).host?.lowercase() ?: error("missing page host") }.toSet()
        verifyPageHosts(actual)
        return actual + MANDATORY_HOSTS
    }

    /**
     * The allowlist for a single page.
     *
     * Progressive loading fetches one page at a time, so the check has to be
     * answerable per page. Same rule as [allowedHostsForPages]: a page off the
     * MangaDex domains is refused, not merely reported.
     */
    fun allowedHostsForPage(page: Page): Set<String> = allowedHostsForPages(listOf(page))

    /** Downloads exactly one page, resumable, under the same host rule. */
    fun downloadPage(
        page: Page,
        destinationDir: File,
        maxBytesPerPage: Long = 10_000_000L,
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

    private fun verifyPageHosts(hosts: Set<String>) {
        hosts.forEach { host ->
            require(
                host == "mangadex.org" || host == "mangadex.network" ||
                    host.endsWith(".mangadex.org") || host.endsWith(".mangadex.network")
            ) { "download host is not allowed: $host" }
        }
    }

    /** Returns the chapter's page files. Already-present non-empty files are kept (resumable downloads). */
    fun downloadChapter(
        pages: List<Page>,
        destinationDir: File,
        maxBytesPerPage: Long = 10_000_000L,
        report: ((done: Int, total: Int) -> Unit)? = null,
    ): List<File> {
        require(pages.isNotEmpty()) { "chapter has no pages" }
        val allowed = allowedHostsForPages(pages)
        val ordered = pages.sortedBy { it.index }
        val total = ordered.size
        destinationDir.mkdirs()
        return ordered.mapIndexed { position, page ->
            val file = File(destinationDir, String.format("page-%03d.img", page.index + 1))
            if (file.exists() && file.length() > 0L) {
                report?.invoke(position + 1, total)
                file
            } else {
                downloader.download(page.imageUrl, file, allowed, maxBytesPerPage).file.also {
                    report?.invoke(position + 1, total)
                }
            }
        }
    }
}