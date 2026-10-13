package app.shura.manga.ui

import app.shura.source.Chapter

/**
 * Which chapter a step lands on.
 *
 * Kept as pure functions over the list so it can be tested without a reader, a
 * source or a network - the boundary cases (first chapter, last chapter, a
 * chapter that is not in the list) are exactly the ones a device test is worst
 * at reaching and exactly the ones a reader notices immediately when wrong.
 *
 * The list is in the order chapters are *read*: newest first, which is what
 * every source here returns. Reading forward therefore moves *up* the list -
 * "next" from the newest chapter is the one below it - so next is +1 and
 * previous is -1. The arrows are placed by reading order, not by screen
 * direction: in an RTL layout those two are the opposite way round from left
 * and right, and following the text direction would walk the reader backwards
 * through the story.
 */
object ChapterNavigation {

    /**
     * The chapter `offset` steps away, or null when there is nowhere to go.
     *
     * Null rather than clamping: at the last chapter "next" should do nothing
     * at all, not silently re-open the chapter already being read.
     */
    fun neighbour(chapters: List<Chapter>, currentId: String, offset: Int): Chapter? {
        if (chapters.isEmpty()) return null
        val index = chapters.indexOfFirst { it.id == currentId }
        // A chapter that is not in this list - a library entry, or a source that
        // reorganised - has no neighbours here, and guessing one would move the
        // reader somewhere they did not ask to go.
        if (index < 0) return null
        val target = index + offset
        return chapters.getOrNull(target)
    }

    fun next(chapters: List<Chapter>, currentId: String): Chapter? =
        neighbour(chapters, currentId, offset = 1)

    fun previous(chapters: List<Chapter>, currentId: String): Chapter? =
        neighbour(chapters, currentId, offset = -1)

    fun hasNext(chapters: List<Chapter>, currentId: String): Boolean =
        next(chapters, currentId) != null

    fun hasPrevious(chapters: List<Chapter>, currentId: String): Boolean =
        previous(chapters, currentId) != null

}


/**
 * What the reader needs when a chapter is opened by resuming, not by tapping.
 *
 * Opening from Home or from the Library is a different act from opening from the
 * chapter list: the reader already has a chapter, but not the list around it.
 * Without the list its previous/next have nothing to step through, which is
 * why those two entry points used to open a reader with no arrows at all.
 *
 * So the chapters are refetched, and the stored chapter is located *in that
 * list* rather than assumed to be there. Three outcomes, all of them honest:
 *
 *  - the chapter is in the list: it is the current one, and the list travels
 *    with it, so the reader can step both ways.
 *  - the list came back but without that chapter - the source reorganised, or
 *    the id changed. The reader still opens the chapter it was asked for, but
 *    with no arrows, because stepping into a neighbouring chapter that is not
 *    in the real list would be inventing navigation.
 *  - the list could not be fetched at all. Same outcome, and the reason is
 *    available so the UI can say the source could not be reached rather than
 *    silently offering a reader with less in it.
 */
data class ResumeContext(
    val chapter: Chapter,
    /** The real list, or empty when it could not be established. */
    val chapters: List<Chapter>,
    /** Whether [chapters] actually contains [chapter]. */
    val locatedInList: Boolean,
    val failure: String? = null,
)

object ChapterResume {

    fun resolve(chapter: Chapter, fetched: List<Chapter>?): ResumeContext {
        if (fetched == null) {
            return ResumeContext(chapter, emptyList(), locatedInList = false,
                failure = "the chapter list could not be fetched")
        }
        val located = fetched.any { it.id == chapter.id }
        return ResumeContext(
            // The fetched copy, not the caller's: it carries the name and
            // number the source currently reports for that chapter.
            chapter = fetched.firstOrNull { it.id == chapter.id } ?: chapter,
            chapters = if (located) fetched else emptyList(),
            locatedInList = located,
        )
    }
}
