package app.shura.manga

import java.io.File

/**
 * What a reader asked to be done to one work's downloads.
 *
 * WHY THREE AND NOT ONE
 *
 * "Delete" used to mean one thing and had to keep meaning it, so the only
 * button offered removed the files. That leaves a reader who wants their shelf
 * tidy with no way to do it, and a reader who wants the disk back with no way to
 * keep following the series - two different intentions that a single destructive
 * button cannot serve, and the reason people used "remove from library" and then
 * found the chapter still on disk.
 *
 * So the three acts stay separate, as Mihon keeps them separate:
 *
 *  - [DELETE_DOWNLOADS] - the pages go; the work stays in the library.
 *  - [REMOVE_FROM_LIBRARY] - the work leaves the library; its pages stay.
 *  - [DELETE_EVERYTHING] - both, which is what "delete this series" means to
 *    most people and is only reachable by choosing it, never by accident.
 */
enum class WorkCleanupAction {
    DELETE_DOWNLOADS,
    REMOVE_FROM_LIBRARY,
    DELETE_EVERYTHING,
}

/**
 * What one cleanup will touch, decided before anything is deleted.
 *
 * Built as a value rather than performed in place so the blast radius can be
 * asserted: [chapters] holds only this work's chapters, and [applyWorkCleanup]
 * refuses anything outside the store directory. "Another work's chapters are
 * untouched" is otherwise a claim about a recursive delete, which is exactly the
 * kind of claim that should not be taken on trust.
 */
data class WorkCleanupPlan(
    val action: WorkCleanupAction,
    /** The library record this work has, or a synthesised one if it has none. */
    val work: LibraryWork,
    /** Exactly the chapters of this work that are on the device. */
    val chapters: List<StoredChapter>,
    /** Whether the library record goes. */
    val removeFromLibrary: Boolean,
    /** Queue rows for this work that must be dropped so nothing re-fills it. */
    val dropJobs: List<String>,
    /** Of those, the ones still moving - cancelling them is real work. */
    val cancelJobs: List<String>,
) {
    val deletesFiles: Boolean
        get() = action == WorkCleanupAction.DELETE_DOWNLOADS ||
            action == WorkCleanupAction.DELETE_EVERYTHING

    val deletesBytes: Long
        get() = if (deletesFiles) chapters.sumOf { directorySize(it.dir) } else 0L
}

/** What a cleanup actually did. */
data class WorkCleanupResult(
    val action: WorkCleanupAction,
    /** Chapters whose directory is really gone. */
    val deletedChapters: Int,
    val deletedBytes: Long,
    /** Chapters that were asked for and survived, named so the row can say so. */
    val failedChapters: List<String>,
    val removedFromLibrary: Boolean,
)

/**
 * Decides what a cleanup touches.
 *
 * [library] is the record in `filesDir/works`, or null when this work is only
 * on the device because it was downloaded. "Remove from library" on a work that
 * is not in the library is not an error: there was nothing to remove and the
 * files stay, which is the honest answer rather than a silent file deletion.
 */
fun planWorkCleanup(
    work: DownloadedWork,
    action: WorkCleanupAction,
    library: LibraryWork? = null,
): WorkCleanupPlan {
    val record = library ?: LibraryWork(
        sourceId = work.sourceId,
        mangaId = work.mangaId,
        title = work.title,
        coverUrl = work.coverUrl,
    )
    val jobs = work.jobs
    return WorkCleanupPlan(
        action = action,
        work = record,
        // `work.stored` is already exactly this work's chapters: it is built by
        // `groupDownloads`, which files a chapter under (source, manga). Nothing
        // here re-derives it from a title.
        chapters = work.stored,
        removeFromLibrary = action == WorkCleanupAction.REMOVE_FROM_LIBRARY ||
            action == WorkCleanupAction.DELETE_EVERYTHING,
        dropJobs = jobs.map(DownloadJob::key),
        cancelJobs = jobs.filter { it.isActive }.map(DownloadJob::key),
    )
}

/**
 * Performs a plan.
 *
 * [store] is the chapter directory and [workDir] the library directory; both
 * are passed in rather than built from a `Context` so the whole of this is
 * checkable on a JVM against a temporary folder.
 *
 * The delete is a list of exact directories, each of which must be a direct
 * child of [store]. A plan that names anything else is refused outright rather
 * than followed: a recursive delete of a path built from an id is how a work's
 * chapters take another work's with them.
 *
 * Bytes are measured before the delete, so the space the reader got back is the
 * space that was really there rather than a figure recorded when the chapter
 * was written.
 */
fun applyWorkCleanup(plan: WorkCleanupPlan, workDir: File, store: File): WorkCleanupResult {
    var deleted = 0
    var bytes = 0L
    val failed = mutableListOf<String>()
    if (plan.deletesFiles) {
        val root = store.canonicalFile
        for (chapter in plan.chapters) {
            val dir = runCatching { chapter.dir.canonicalFile }.getOrNull()
            if (dir == null || dir.parentFile != root) {
                failed += chapter.chapterId
                continue
            }
            val size = directorySize(dir)
            val gone = runCatching { dir.deleteRecursively() }.getOrDefault(false) && !dir.exists()
            if (gone) {
                deleted += 1
                bytes += size
            } else {
                failed += chapter.chapterId
            }
        }
    }
    val removed = if (plan.removeFromLibrary) {
        runCatching { removeWork(workDir, plan.work) }.getOrDefault(false)
    } else {
        false
    }
    return WorkCleanupResult(
        action = plan.action,
        deletedChapters = deleted,
        deletedBytes = bytes,
        failedChapters = failed,
        removedFromLibrary = removed,
    )
}

/**
 * The sentence a row shows after a cleanup.
 *
 * A cleanup that fails has to say which chapters survived, because "done" over a
 * directory that is still there is the lie this whole file exists to avoid.
 */
fun cleanupSummary(result: WorkCleanupResult, sizeFormatter: (Long) -> String): String = buildString {
    when {
        result.deletedChapters > 0 -> append(
            "${result.deletedChapters} · ${sizeFormatter(result.deletedBytes)}",
        )
        result.action != WorkCleanupAction.REMOVE_FROM_LIBRARY -> append("0")
    }
    if (result.removedFromLibrary) {
        if (isNotEmpty()) append(" · ")
        append("library")
    }
    if (result.failedChapters.isNotEmpty()) {
        if (isNotEmpty()) append(" · ")
        append("${result.failedChapters.size} kept")
    }
}.ifBlank { "0" }