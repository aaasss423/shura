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
