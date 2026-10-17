package app.shura.manga

/**
 * What the downloads screen is a list of.
 *
 * THE QUESTION A READER ASKS FIRST
 *
 * "Which book is this?" - not "which page of which chapter". The downloads tab
 * used to answer the second one, so a reader who had saved five chapters of one
 * series found five rows and had to read all five to notice they were one book;
 * and a reader who expected one row per manga found a list of chapters with
 * covers on them, which looks like a chapter list that happens to be in the
 * wrong place.
 *
 * So a row is a *work*, identified the same way the library identifies one:
 * (source, manga), never the title. Two sources can carry works with the same
 * name, and merging them would file one series' chapters under another's cover.
 *
 * Nothing here is stored. A work on this screen is built out of two things the
 * app already holds - the chapters on the device ([StoredChapter], written by
 * `persistStoredChapter` and read back by `indexStoredChapters`) and the rows in
 * the download queue ([DownloadJob]). A parallel store would be a second answer
 * to "what is on this device", and the two would disagree the first time a
 * chapter was deleted by hand.
 *
 * No Android in this file, so the grouping is testable without a device: the
 * count a row claims and the screen a tap opens are the two things the user's
 * report was about, and neither can be checked by looking at code.
 */
data class DownloadedWork(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val coverUrl: String,
    /** Chapters whose pages are on the device. */
    val stored: List<StoredChapter>,
    /** Queue rows for this work, whatever state they are in. */
    val jobs: List<DownloadJob>,
) {
    /** Identity, and the chapter store's own: stable across sources. */
    val key: String get() = "${sourceId}|${mangaId}"

    /** How many of this work's chapters are on the device. */
    val localCount: Int get() = stored.size

    /** How many are downloading, or waiting to. */
    val activeCount: Int get() = jobs.count { it.isActive }

    /** How many failed and can be started again. */
    val failedCount: Int get() = jobs.count { it.state == DownloadState.FAILED }

    /** True while anything of this work is still moving. */
    val inProgress: Boolean get() = activeCount > 0

    /** The newest saved chapter, or null when nothing of this work is saved. */
    val latestStored: StoredChapter? get() = stored.maxByOrNull { it.dir.lastModified() }
}

/** Whether a download is still going to become a file. */
val DownloadJob.isActive: Boolean
    get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING

/**
 * Groups saved chapters and queue rows into works.
 *
 * Both sides go in, so a work appears as soon as anything of it is either saved
 * or in flight - a download that failed before saving anything still has a row,
 * and a row the reader can retry from is the only place that failure is
 * visible. Ordering puts work with something in flight first, then A-Z, so the
 * list does not reshuffle itself under a running download as chapters land.
 *
 * The name is the work's own: the store records it beside each chapter, and the
 * queue carries it on every row. `chapter.title` is only consulted when neither
 * knows - it is "Chapter 2" for most chapters of most series, and falling back
 * to it is what put a downloads row reading "Chapter 2" where the reader
 * expected a manga.
 */
fun groupDownloads(
    stored: List<StoredChapter>,
    jobs: List<DownloadJob>,
): List<DownloadedWork> {
    // Collected into builders and frozen once: a work with five hundred saved
    // chapters must not cost five hundred list copies on the main thread, which
    // is the whole reason this screen reads the store in one pass.
    class Builder(val sourceId: String, val mangaId: String) {
        var title: String = ""
        var coverUrl: String = ""
        val stored = mutableListOf<StoredChapter>()
        val jobs = mutableListOf<DownloadJob>()
    }

    val byKey = LinkedHashMap<String, Builder>()
    fun builderFor(sourceId: String, mangaId: String): Builder =
        byKey.getOrPut("${sourceId}|${mangaId}") { Builder(sourceId, mangaId) }

    for (chapter in stored) {
        val row = builderFor(chapter.sourceId, chapter.mangaId)
        // The first non-empty cover wins: a chapter saved without one is still
        // a chapter of the work, it just is not evidence of a cover.
        if (row.coverUrl.isBlank()) row.coverUrl = chapter.coverUrl
        if (row.title.isBlank()) row.title = chapter.mangaTitle
        // Last resort, and only for a chapter saved before the work's name was
        // recorded: a chapter's name beats a blank row, and a row that says
        // "Chapter 2" is at least naming something the reader can recognise.
        if (row.title.isBlank()) row.title = chapter.title
        row.stored += chapter
    }
    for (job in jobs) {
        val row = builderFor(job.sourceId, job.mangaId)
        if (row.coverUrl.isBlank()) row.coverUrl = job.coverUrl
        if (row.title.isBlank()) row.title = job.mangaTitle
        row.jobs += job
    }
    return byKey.values
        .map { row ->
            DownloadedWork(
                sourceId = row.sourceId,
                mangaId = row.mangaId,
                title = row.title,
                coverUrl = row.coverUrl,
                stored = storedChaptersOf(row.stored, row.sourceId, row.mangaId),
                jobs = row.jobs.toList(),
            )
        }
        .sortedWith(
            compareByDescending<DownloadedWork> { it.inProgress }
                .thenByDescending { it.activeCount }
                .thenBy { it.title.lowercase() },
        )
}

/**
 * One work's saved chapters, newest first.
 *
 * Sorted by the file's own timestamp rather than by name, because a chapter's
 * name is "Chapter 12" often enough that reading order cannot be recovered from
 * it - and "Chapter 12" sorts above "Chapter 9" as text, which is the order the
 * index hands them over in. When the reader saved two chapters inside the same
 * filesystem tick their order is whatever the filesystem says, which is the
 * truth available.
 */
fun storedChaptersOf(
    stored: List<StoredChapter>,
    sourceId: String,
    mangaId: String,
): List<StoredChapter> =
    stored.filter { it.sourceId == sourceId && it.mangaId == mangaId }
        .sortedByDescending { it.dir.lastModified() }
