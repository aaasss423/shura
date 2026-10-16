package app.shura.manga.ui

import app.shura.source.Chapter

/**
 * The order a work's chapters are offered in, and which of them a batch takes.
 *
 * Two screens list the same chapters: the chapter list itself, and the work's own
 * page, which now offers to save several at once. They have to agree about what
 * "the next five" means, or the button queues chapters the reader cannot see
 * next to it. So the ordering is one function called from both, and it is the
 * order the reader is looking at - newest first, which is how every chapter list
 * in this app opens - rather than a second, private arrangement of the same data.
 *
 * Nothing here touches Android, a source or a screen: these are the decisions
 * that are hard to check by hand (what happens when everything is already read,
 * when the second tap lands) and easy to get wrong once.
 */
object ChapterListOrder {

    /**
     * Chapters in the order they are read.
     *
     * Numbered chapters sort by number, ascending, and a chapter a source
     * reports without one goes to the end of that order rather than the
     * beginning - "Chapter: Extra" is not chapter zero. [ascending] flips it, so
     * `ascending = false` is the newest-first list the reader sees.
     */
    fun reading(chapters: List<Chapter>, ascending: Boolean): List<Chapter> =
        chapters
            .sortedBy { chapter -> chapter.number ?: Float.MAX_VALUE }
            .let { if (ascending) it else it.reversed() }
}

/**
 * Which chapters a work's chapters are not able to be asked for twice.
 *
 * One set rather than three parameters at every call site, because the three
 * are always asked about together and mixing them up is silent: a chapter that
 * is already saved would be offered again and the button would appear to do
 * nothing when the queue refused the duplicate.
 *
 * Read and downloaded and queued are three different questions and all three
 * count. A chapter the reader has been through is not one they want to save, a
 * chapter already on disk cannot be saved again, and a chapter with a row in the
 * queue is already spoken for.
 */
data class BatchState(
    /** Chapters with reading progress. */
    val read: Set<String> = emptySet(),
    /** Chapter ids with a manifest on disk. */
    val downloaded: Set<String> = emptySet(),
    /** Chapters with a download row, whatever state that row is in. */
    val queued: Set<String> = emptySet(),
) {
    /** True when this chapter must not be offered by a batch action. */
    fun isSpent(chapter: Chapter): Boolean =
        chapter.id in read || chapter.id in downloaded || chapter.id in queued
}

/**
 * Which chapters a "download the next N" button queues.
 *
 * The rule is the one Mihon uses, and it is a rule about the reader's position
 * rather than about the source: the batch starts at the newest chapter that is
 * not read, not saved and not already queued, and takes the next few after it.
 * Two things follow from that, and both are why this is a function and not a
 * loop in a composable.
 *
 * Nothing to offer is an answer, not a fallback to re-downloading. A work whose
 * every chapter is read, saved or queued has no batch to run, so the buttons
 * offer an empty list and the caller disables them. Handing back the whole list
 * instead would re-download a finished series the first time anyone tapped.
 *
 * The list is read once per tap, so a second tap on the same button cannot
 * repeat the first: the chapters the first tap queued are queued by then, and
 * the button offers the ones after them - or nothing, once there is nothing
 * left.
 */
object BatchDownload {

    /**
     * Every chapter a batch could still take, in the order they are shown.
     *
     * [visible] is the list as the reader sees it, so what this returns is
     * exactly the chapters that follow the button.
     */
    fun pending(visible: List<Chapter>, state: BatchState): List<Chapter> =
        visible.filterNot { state.isSpent(it) }

    /**
     * The first [limit] chapters a batch would take; every remaining one when
     * [limit] is null.
     *
     * Null rather than a sentinel number, because "the rest" is a different
     * action from "the next five" and not a very large five.
     */
    fun take(visible: List<Chapter>, state: BatchState, limit: Int?): List<Chapter> {
        val pending = pending(visible, state)
        return if (limit == null) pending else pending.take(limit.coerceAtLeast(0))
    }
}