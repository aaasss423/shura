package app.shura.host

import app.shura.source.Page
import java.io.File
import java.net.URI

/** Downloads a chapter's page images with Shura's bounded per-hop HTTPS loader and MangaDex-only host allowlist. */
class MangaDexChapterDownloader(private val downloader: SourceDownloader = SourceDownloader()) {

    /** Exact host allowlist for chapter pages, refusing anything outside MangaDex domains. */
    fun allowedHostsForPages(pages: List<Page>): Set<String> {
        require(pages.isNotEmpty()) { "chapter has no pages" }
        val actual = pages.map { URI(it.imageUrl).host?.lowercase() ?: error("missing page host") }.toSet()
        actual.forEach { host ->
            require(
                host == "mangadex.org" || host == "mangadex.network" ||
                    host.endsWith(".mangadex.org") || host.endsWith(".mangadex.network")
            ) { "download host is not allowed: $host" }
        }
        return actual + setOf("mangadex.org", "mangadex.network", "uploads.mangadex.org", "uploads.mangadex.network")
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