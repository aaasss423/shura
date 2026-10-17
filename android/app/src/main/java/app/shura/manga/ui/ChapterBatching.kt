package app.shura.manga.ui

import app.shura.source.Chapter

/**
 * The order a work's chapters are offered in, and which of them a batch takes.
 *
 * Two screens list the same chapters and they deliberately disagree about the
 * order. The chapter list opens newest first, because a list that opens on
 * chapter 1 of 400 is the reason readers tap "next chapter" instead of reading.
 * A batch saves what comes next *in reading order*, from chapter one: "download
 * the next five" means the first five chapters of the story the reader has not
 * read yet, not the five chapters that arrived last.
 *
 * Both orders come from [ChapterListOrder], so there is one arrangement of the
 * data and each screen asks for the one it needs - rather than a private sort in
 * a composable that can drift away from the other.
 *
 * Nothing here touches Android, a source or a screen: these are the decisions
 * that are hard to check by hand (what happens when everything is already read,
 * when the second tap lands) and easy to get wrong once.
 */
object ChapterListOrder {

    /**
     * One row per chapter id, in the order they arrived.
     *
     * A chapter's identity is its id, and it is the id the rest of the app
     * already uses for everything it does with a chapter: the directory
     * `persistStoredChapter` writes it into, the key the download queue refuses a
     * second copy with, the key reading progress is stored under. So when a
     * source hands back the same id twice - a site whose own list repeats a
     * chapter, or a paginated one whose pages overlap - it has described one
     * chapter twice, and drawing two rows would offer two download buttons for
     * one chapter and two entries in the reader's step-through for one page.
     *
     * The first sighting wins, because that is the row the reader was shown
     * first.
     *
     * The chapter *number* is deliberately not the identity. Specials, side
     * stories and re-uploads share a number with the chapter they sit beside,
     * and dropping one of those loses a chapter somebody asked for; only the
     * id says which chapter this is.
     */
    fun distinct(chapters: List<Chapter>): List<Chapter> {
        val seen = mutableSetOf<String>()
        return chapters.filter { seen.add(it.id) }
    }

    /**
     * Chapters in the order they are read.
     *
     * Numbered chapters sort by number, ascending, and a chapter a source
     * reports without one goes to the end of that order rather than the
     * beginning - "Chapter: Extra" is not chapter zero. [ascending] flips it, so
     * `ascending = false` is the newest-first list the reader sees.
     *
     * A repeated id is collapsed on the way in ([distinct]): every caller of
     * this is drawing or walking a list of chapters, and a list with the same
     * chapter twice is wrong for all of them - including as the `key` of a lazy
     * list, which cannot hold one key twice.
     */
    fun reading(chapters: List<Chapter>, ascending: Boolean): List<Chapter> =
        distinct(chapters)
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
 * The rule is a rule about reading rather than about the source: the batch
 * starts at chapter one, walks forward in reading order, and takes the next few
 * chapters that are not read, not saved and not already queued. Two things
 * follow from that, and both are why this is a function and not a loop in a
 * composable.
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
     * The order a batch walks: reading order, from chapter one.
     *
     * Not the order the chapter list shows. That list is newest first so it can
     * be scanned from the latest update, and its order is the reader's to change
     * with the sort chips; a batch is not a view of the list but a decision about
     * what to save, and that decision starts where the story does.
     */
    fun inReadingOrder(chapters: List<Chapter>): List<Chapter> =
        ChapterListOrder.reading(chapters, ascending = true)

    /**
     * Every chapter a batch could still take, in the order they are taken.
     *
     * [ordered] is [inReadingOrder] applied to the work's chapters, so what this
     * returns is exactly the chapters a batch would queue from there.
     *
     * Distinct by id on the way through as well, so a list that reached this
     * point with a repeated chapter in it still cannot queue that chapter twice:
     * the queue would refuse the second copy, but the reader would have been
     * offered a batch that is one chapter longer than it can ever be.
     */
    fun pending(ordered: List<Chapter>, state: BatchState): List<Chapter> =
        ChapterListOrder.distinct(ordered).filterNot { state.isSpent(it) }

    /**
     * The first [limit] chapters a batch would take; every remaining one when
     * [limit] is null.
     *
     * Null rather than a sentinel number, because "the rest" is a different
     * action from "the next five" and not a very large five.
     */
    fun take(ordered: List<Chapter>, state: BatchState, limit: Int?): List<Chapter> {
        val pending = pending(ordered, state)
        return if (limit == null) pending else pending.take(limit.coerceAtLeast(0))
    }

    /**
     * What "download all chapters" takes: every chapter that is left.
     *
     * The same rule the "download the rest" button uses - not read, not saved,
     * not already queued - and the same order, chapter one first. Two things are
     * worth stating because they are the ones that get broken:
     *
     *  - there is no cap. A work with five hundred chapters offers five hundred
     *    chapters, in one list, once. Nothing here truncates, samples or pages,
     *    because a batch that quietly stopped at 100 would look exactly like a
     *    batch that was still working.
     *  - there are no duplicates. The list is distinct by chapter id, so a
     *    source that repeats a chapter in its own list contributes it once.
     */
    fun downloadAll(chapters: List<Chapter>, state: BatchState): List<Chapter> =
        take(inReadingOrder(chapters), state, limit = null)
}