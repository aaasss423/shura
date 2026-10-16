package app.shura.manga

import android.content.Context
import app.shura.manga.ui.ChapterDownloader
import app.shura.source.Chapter
import app.shura.source.ShuraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a download is doing right now. */
enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * One chapter being saved for offline reading.
 *
 * [key] is the identity the rest of the app uses to ask "is this downloaded?",
 * so a chapter cannot be downloaded twice under two rows.
 */
data class DownloadJob(
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val mangaId: String,
    val mangaTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val coverUrl: String,
    val total: Int,
    val done: Int,
    val state: DownloadState,
    val detail: String = "",
) {
    val progress: Float get() = if (total <= 0) 0f else done.toFloat() / total

    companion object {
        fun keyOf(sourceId: String, chapterId: String): String = "$sourceId/$chapterId"
    }
}

/**
 * Chapters being saved, and what became of them.
 *
 * This is the piece that was missing: [persistStoredChapter] and the loader's
 * whole-chapter path were both written and never reached, so the library had no
 * way to fill. Everything here is about making one honest promise - a chapter is
 * in the library only once every page landed - and saying so when it is not.
 *
 * A failed or cancelled download leaves nothing behind: a half-saved chapter
 * with a manifest would look complete in the library and open to blank pages.
 */
class DownloadQueue(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloader: ChapterDownloader,
) {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val running = mutableMapOf<String, Job>()

    /**
     * One chapter at a time.
     *
     * "Download all" on a long series queues two hundred chapters at once. Left
     * unserialised that is two hundred connections, two hundred decode buffers and
     * a phone that stops responding - the reader's own request is what breaks it.
     */
    private val oneAtATime = Mutex()

    /** Called after a chapter is written or removed, so the library re-reads. */
    var onLibraryChanged: (() -> Unit)? = null

    /** True when the chapter's files and manifest are on disk. */
    fun isDownloaded(sourceId: String, chapterId: String): Boolean =
        File(storedChapterDir(context, chapterId), "manifest.json").exists()

    /** True while the chapter has a row here, whatever its state. */
    fun jobFor(sourceId: String, chapterId: String): DownloadJob? =
        _jobs.value.firstOrNull { it.key == DownloadJob.keyOf(sourceId, chapterId) }

    /**
     * Saves a chapter, or reports why it could not be saved.
     *
     * Re-entrant by design: tapping download on a chapter already downloading
     * does not start a second copy of the same download.
     */
    fun download(
        source: ShuraSource,
        mangaId: String,
        mangaTitle: String,
        chapter: Chapter,
        coverUrl: String = "",
    ) {
        val key = DownloadJob.keyOf(source.id, chapter.id)
        if (isDownloaded(source.id, chapter.id) || running.containsKey(key)) return

        _jobs.update { existing ->
            existing.filterNot { it.key == key } + DownloadJob(
                key = key,
                sourceId = source.id,
                sourceName = source.name,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterId = chapter.id,
                chapterTitle = chapter.name.ifBlank { chapter.id },
                coverUrl = coverUrl,
                total = 0,
                done = 0,
                state = DownloadState.QUEUED,
            )
        }

        running[key] = scope.launch {
            oneAtATime.withLock { runDownload(source, mangaId, mangaTitle, chapter, coverUrl, key) }
        }
    }

    private suspend fun runDownload(
        source: ShuraSource,
        mangaId: String,
        mangaTitle: String,
        chapter: Chapter,
        coverUrl: String,
        key: String,
    ) {
            var saved: List<File> = emptyList()
            val staging = File(context.cacheDir, "download-$key".replace(Regex("[^A-Za-z0-9_.-]"), "_"))
            try {
                update(key) { it.copy(state = DownloadState.RUNNING) }
                val refs = withContext(Dispatchers.IO) { source.pages(chapter.id) }
                if (refs.isEmpty()) error("the source returned no pages for this chapter")
                update(key) { it.copy(total = refs.size) }

                staging.mkdirs()
                val files = mutableListOf<File>()
                for ((position, page) in refs.withIndex()) {
                    val uri = downloader.pageAt(source, chapter.id, page)
                    val file = File(requireNotNull(uri.path) { "the downloader returned a non-file URI" })
                    if (!file.exists() || file.length() == 0L) error("page ${position + 1} did not arrive")
                    files += file
                    update(key) { it.copy(done = position + 1) }
                }
                saved = files
                withContext(Dispatchers.IO) {
                    persistStoredChapter(
                        store = chapterStore(context),
                        sourceId = source.id,
                        mangaId = mangaId,
                        chapterId = chapter.id,
                        title = chapter.name.ifBlank { chapter.id },
                        files = files,
                        coverUrl = coverUrl,
                    )
                }
                update(key) { it.copy(state = DownloadState.DONE, detail = "") }
                onLibraryChanged?.invoke()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                discard(staging)
                update(key) { it.copy(state = DownloadState.CANCELLED) }
                throw cancelled
            } catch (error: Exception) {
                // The partial files are not a chapter. Leaving them would produce a
                // library entry that opens to blank pages.
                discard(staging)
                update(key) {
                    it.copy(state = DownloadState.FAILED, detail = error.message ?: "download failed")
                }
            } finally {
                if (saved.isEmpty()) discard(staging)
                running.remove(key)
            }
    }

    /** Stops a download. The chapter is not saved, and nothing is left behind. */
    fun cancel(key: String) {
        running.remove(key)?.cancel()
        _jobs.update { existing -> existing.map { if (it.key == key) it.copy(state = DownloadState.CANCELLED) else it } }
    }

    /** Forgets a finished row. Downloading it again is allowed. */
    fun forget(key: String) {
        running.remove(key)
        _jobs.update { existing -> existing.filterNot { it.key == key } }
    }

    /**
     * Starts a failed or cancelled download again.
     *
     * The row is dropped first so the new attempt owns the key: a retry that
     * appended instead would leave two rows for one chapter, and the reader would
     * see the failure still on screen next to the progress.
     */
    fun retry(key: String, sourceOf: (String) -> ShuraSource?) {
        val job = _jobs.value.firstOrNull { it.key == key } ?: return
        val source = sourceOf(job.sourceId)
        if (source == null) {
            update(key) { it.copy(state = DownloadState.FAILED, detail = "the source is no longer installed") }
            return
        }
        forget(key)
        download(
            source = source,
            mangaId = job.mangaId,
            mangaTitle = job.mangaTitle,
            chapter = Chapter(job.chapterId, job.chapterTitle, null),
            coverUrl = job.coverUrl,
        )
    }

    /** Chapters already in the library, by source id. */
    fun downloadedChapters(): Set<String> =
        indexStoredChapters(chapterStore(context)).map { it.chapterId }.toSet()

    private fun update(key: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { existing -> existing.map { if (it.key == key) transform(it) else it } }
    }

    private fun discard(staging: File) {
        runCatching { staging.deleteRecursively() }
    }
}

/**
 * The directory [persistStoredChapter] writes a chapter into.
 *
 * The name is the chapter id with everything outside a safe set replaced, so
 * "is this downloaded" has to ask the same question the writer answered rather
 * than guess a second layout.
 */
internal fun storedChapterDir(context: Context, chapterId: String): File =
    File(chapterStore(context), chapterId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
