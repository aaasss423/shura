package app.shura.manga.reader

/**
 * What the reader saw when it finished a chapter.
 *
 * Two facts, both from the reader's side, because only the reader knows which
 * page is on screen and what the preference was when it got there. Whether the
 * chapter is actually on disk is the third fact, and that one belongs to whoever
 * owns the library - which is why this carries the other two rather than the
 * answer.
 */
data class ChapterFinished(
    val chapterId: String,
    /** The reader reached the chapter's last page, not merely a late one. */
    val lastPageReached: Boolean,
    /** "Delete the chapter after reading", as the reader saw it. */
    val deleteAfterReading: Boolean,
)

/**
 * Whether a finished chapter is deleted from the library. Mihon's advanced
 * setting, and the shape of it matters more than the feature does.
 *
 * Three facts, and all three have to hold:
 *
 *  - the reader reached the chapter's last page. Anything earlier is a reader
 *    who put the phone down, not a reader who finished;
 *  - the preference was on. Off is the default, and a reader who never asked for
 *    this never loses a chapter they saved;
 *  - the chapter is in the library. Nothing was ever stored, so there is nothing
 *    to delete and no error to raise about it.
 *
 * A pure function on purpose: the answer is a decision worth pinning, and
 * pinning it needs no device, no file and no composable.
 */
object ChapterDeletion {
    fun shouldDelete(
        lastPageReached: Boolean,
        preferenceOn: Boolean,
        chapterStored: Boolean,
    ): Boolean = lastPageReached && preferenceOn && chapterStored
}

/**
 * The one-shot gate the reader's page events go through.
 *
 * Arriving at the last page is an event that repeats: a recomposition, a
 * rotation, a page event fired twice while the pager settles. Without a gate,
 * each of those would offer the same chapter for deletion again. The chapter is
 * claimed when it is *offered*, so a second offer is refused even if the first
 * turned out to have nothing to delete - re-offering it would be the reader's
 * chapter deleted twice, and there is nothing to delete twice.
 *
 * One gate per reader, keyed by chapter id rather than reset per chapter, so a
 * chapter read and then reopened in the same session is not offered again. A new
 * session gets a new gate, which is the right answer: by then the reader has
 * asked for this again.
 */
class ChapterDeletionClaim {

    private val claimed = mutableSetOf<String>()

    /** True once for a given chapter, and never again. */
    fun claim(chapterId: String, wanted: Boolean): Boolean =
        wanted && chapterId.isNotBlank() && claimed.add(chapterId)
}