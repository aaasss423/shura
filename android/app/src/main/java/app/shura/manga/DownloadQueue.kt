package app.shura.manga

import android.content.Context
import app.shura.manga.ui.ChapterDownloader
import app.shura.manga.ui.ChapterTitle
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
 * The two directories the queue writes to.
 *
 * WHY A SEAM RATHER THAN A `Context`
 *
 * Cancelling downloads is a claim about coroutines, staging directories and
 * rows in a list, and every one of those three can be observed on a JVM - but
 * not through a `Context`, whose `filesDir` and `cacheDir` are android.jar
 * stubs that throw. So the queue is written against these two paths, and the
 * production constructor supplies them from the context.
 *
 * Nothing else about the queue changed: the same directory layout, the same
 * staging names, the same manifest.
 */
class ChapterFiles(
    /** Where finished chapters live. */
    val store: File,
    /** Where pages are staged while a download is in flight. */
    val cache: File,
) {
    /** The directory one chapter is written into. */
    fun dirOf(chapterId: String): File = chapterDirIn(store, chapterId)

    /** The staging directory one download writes into. */
    fun stagingOf(key: String): File = File(cache, "download-$key".replace(Regex("[^A-Za-z0-9_.-]"), "_"))
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
    private val files: ChapterFiles,
    private val scope: CoroutineScope,
    private val downloader: ChapterDownloader,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        downloader: ChapterDownloader,
    ) : this(
        ChapterFiles(store = chapterStore(context), cache = context.cacheDir),
        scope,
        downloader,
    )

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
        storedChapterRecordIn(files.store, sourceId, chapterId) != null

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
                chapterTitle = ChapterTitle.of(chapter),
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

    /**
     * Saves a batch of chapters, or reports why it could not save them.
     *
     * The same promise as [download], for many chapters at once, and the same
     * one-at-a-time discipline: they queue and they wait, one after another,
     * whichever order they were offered in.
     *
     * WHY IT IS NOT A LOOP OVER [download]
     *
     * "Download the rest" on a long series offers every chapter it has left. A
     * loop would put all of them through the disk check and then through
     * `_jobs.update` one at a time, and each update rebuilds the whole list the
     * screen observes - so a five hundred chapter work meant five hundred
     * directory lookups and five hundred full-list copies on the thread that
     * handled the tap, before the first page was even asked for. That is the
     * tap that does not come back. Here the check is one set the caller already
     * holds, and the rows are added in one emission, so the cost of the tap is
     * the cost of the list rather than the cost of the list times its length.
     *
     * What is already on the device ([onDisk]) and what already has a row are
     * skipped, so nothing is queued twice, and chapters are distinct by id, so a
     * source that repeats one of them in its own list contributes it once.
     */
    fun downloadAll(
        source: ShuraSource,
        mangaId: String,
        mangaTitle: String,
        chapters: List<Chapter>,
        coverUrl: String = "",
        /** Chapter ids already in the store, so this costs no directory walk. */
        onDisk: Set<String> = emptySet(),
    ) {
        val rows = _jobs.value
        val spokenFor = rows.mapTo(mutableSetOf(), DownloadJob::key)
        val fresh = chapters
            .filterNot { it.id in onDisk || isDownloaded(source.id, it.id) }
            .filterNot { DownloadJob.keyOf(source.id, it.id) in spokenFor }
            .let { candidates ->
                // One row per chapter, even if the source listed one twice.
                val ids = mutableSetOf<String>()
                candidates.filter { ids.add(it.id) }
            }
        if (fresh.isEmpty()) return

        _jobs.update { existing ->
            val keys = fresh.mapTo(mutableSetOf()) { DownloadJob.keyOf(source.id, it.id) }
            existing.filterNot { it.key in keys } + fresh.map { chapter ->
                DownloadJob(
                    key = DownloadJob.keyOf(source.id, chapter.id),
                    sourceId = source.id,
                    sourceName = source.name,
                    mangaId = mangaId,
                    mangaTitle = mangaTitle,
                    chapterId = chapter.id,
                    chapterTitle = ChapterTitle.of(chapter),
                    coverUrl = coverUrl,
                    total = 0,
                    done = 0,
                    state = DownloadState.QUEUED,
                )
            }
        }

        fresh.forEach { chapter ->
            val key = DownloadJob.keyOf(source.id, chapter.id)
            running[key] = scope.launch {
                oneAtATime.withLock {
                    runDownload(source, mangaId, mangaTitle, chapter, coverUrl, key)
                }
            }
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
            val staging = files.stagingOf(key)
            try {
                update(key) { it.copy(state = DownloadState.RUNNING) }
                val refs = withContext(Dispatchers.IO) { source.pages(chapter.id) }
                if (refs.isEmpty()) error("the source returned no pages for this chapter")
                update(key) { it.copy(total = refs.size) }

                staging.mkdirs()
                val landed = mutableListOf<File>()
                for ((position, page) in refs.withIndex()) {
                    val uri = downloader.pageAt(source, chapter.id, page)
                    val file = File(requireNotNull(uri.path) { "the downloader returned a non-file URI" })
                    if (!file.exists() || file.length() == 0L) error("page ${position + 1} did not arrive")
                    landed += file
                    update(key) { it.copy(done = position + 1) }
                }
                saved = landed
                withContext(Dispatchers.IO) {
                    writeChapterManifest(
                        store = files.store,
                        sourceId = source.id,
                        mangaId = mangaId,
                        chapterId = chapter.id,
                        title = ChapterTitle.of(chapter),
                        files = landed,
                        coverUrl = coverUrl,
                        // The work's name, so the library and the downloads screen
                        // can name the book rather than one of its chapters.
                        mangaTitle = mangaTitle,
                    )
                }
                update(key) { it.copy(state = DownloadState.DONE, detail = "") }
                onLibraryChanged?.invoke()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Really stopped: the staging directory goes with it, and so does a
                // chapter directory that was written but never got a manifest.
                discard(key)
                update(key) { it.copy(state = DownloadState.CANCELLED) }
                throw cancelled
            } catch (error: Exception) {
                // The partial files are not a chapter. Leaving them would produce a
                // library entry that opens to blank pages.
                discard(key)
                update(key) {
                    it.copy(state = DownloadState.FAILED, detail = error.message ?: "download failed")
                }
            } finally {
                if (saved.isEmpty()) discard(key)
                running.remove(key)
            }
    }

    /**
     * Stops one download. The chapter is not saved, and nothing is left behind.
     */
    fun cancel(key: String) {
        running.remove(key)?.cancel()
        _jobs.update { existing -> existing.map { if (it.key == key) it.copy(state = DownloadState.CANCELLED) else it } }
        discard(key)
    }

    /** Forgets a finished row. Downloading it again is allowed. */
    fun forget(key: String) {
        running.remove(key)
        _jobs.update { existing -> existing.filterNot { it.key == key } }
    }

    /**
     * Stops every download that is running or waiting, in one action.
     *
     * WHY IT IS NOT A LOOP OVER [cancel]
     *
     * Two of the three things "stop everything" needs are not per-row at all:
     *
     *  - A queued download is often not running. Two hundred chapters queued on
     *    a long series means two hundred coroutines parked on the one-at-a-time
     *    lock, and cancelling one of them throws inside `withLock` *before*
     *    [runDownload] is ever entered - so nothing cleans up its staging
     *    directory and nothing moves its row off QUEUED. Those rows are dropped
     *    outright here instead: a download that never started has nothing to
     *    report and nothing to clean.
     *  - The running one has to be cancelled for real, so its `catch` removes
     *    the partial files and says CANCELLED rather than leaving a spinner and
     *    a directory of half a chapter.
     *
     * What it does not touch: DONE and FAILED rows, and every chapter already in
     * the store. Cancelling the queue must never be a way to lose a saved
     * chapter, so the only bytes removed are the ones of downloads that were
     * still moving.
     *
     * Returns how many rows were still active, so the screen can say something
     * true rather than flashing and saying nothing.
     */
    fun cancelAll(): Int {
        val active = _jobs.value.filter { it.isActive }
        if (active.isEmpty()) return 0
        val keys = active.mapTo(mutableSetOf(), DownloadJob::key)
        // Remove the handle before cancelling it: the coroutine's own `finally`
        // removes it too, and cancelling while it is still in the map would leave
        // a cancelled job the next download could be blocked behind.
        keys.forEach { key -> running.remove(key)?.cancel() }
        _jobs.update { existing ->
            existing.mapNotNull { job ->
                when {
                    job.key !in keys -> job
                    // Never started: there is nothing to report and nothing on
                    // disk, so the row goes rather than claiming to be cancelled.
                    job.state == DownloadState.QUEUED -> null
                    // Started and really stopped: the row stays and says so.
                    else -> job.copy(state = DownloadState.CANCELLED, detail = "")
                }
            }
        }
        // Belt and braces for a job cancelled before it entered runDownload: any
        // staging directory left over is removed here as well, and only for the
        // chapters that were actually in flight.
        keys.forEach { key -> discardStaging(key) }
        return active.size
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
        indexStoredChapterRecords(files.store).map { it.chapterId }.toSet()

    private fun update(key: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { existing -> existing.map { if (it.key == key) transform(it) else it } }
    }

    /**
     * Removes everything a stopped download may have left behind.
     *
     * Two directories, and the second one is the subtle half: `persistStoredChapter`
     * copies pages into the chapter's own directory and writes `manifest.json`
     * last, so a cancellation partway through the copy leaves page files inside a
     * directory the index would not offer. It is inert - `readChapterRecord`
     * requires the manifest - but it is the reader's space, and it would be
     * re-used by the next attempt. Removed here, and only when there is no
     * manifest, so a chapter that really is in the library can never be deleted
     * by a cancellation.
     */
    private fun discard(key: String) {
        discardStaging(key)
        val chapterId = key.substringAfterLast('/', "")
        if (chapterId.isBlank()) return
        val dir = files.dirOf(chapterId)
        if (File(dir, "manifest.json").isFile) return
        runCatching { dir.deleteRecursively() }
    }

    private fun discardStaging(key: String) {
        runCatching { files.stagingOf(key).deleteRecursively() }
    }
}