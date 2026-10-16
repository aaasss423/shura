package app.shura.manga.ui

import app.shura.manga.reader.ReadingProgress
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

/**
 * What a work's own page offers as its one reading button.
 *
 * A work's page had a "Start" button that could not appear: the caller passed
 * no chapter for it, because the chapter list is only fetched on the *next*
 * screen. Everything below is that button's decision, kept as functions over
 * two lists so it can be pinned by a test rather than by a tap.
 *
 * The two cases are genuinely different acts, and the button says which one it
 * is:
 *
 *  - nothing read yet: "Start", and the reader opens the first chapter. Not the
 *    first chapter *of the list the page shows* - that list is newest-first, so
 *    its top is the latest chapter of a finished series - but chapter one.
 *  - something read: "Continue", and the reader opens the chapter that was last
 *    read, not the newest one and not the first unread. The page the reader
 *    reached is restored by the reader itself, from the progress store.
 *
 * When the source's list cannot be established the two cases part company, and
 * deliberately so. "Start" has nothing to open, so there is no button rather
 * than one that would open a chapter nobody named. "Continue" does: history
 * already says which chapter it is, so the reader opens that chapter with no
 * arrows, which is the same trade [ChapterResume] makes everywhere else.
 */
object DetailsResume {

    /**
     * The chapter "Start" opens: the first one, whatever order it arrived in.
     *
     * Sources here are not consistent about which end of the list chapter one
     * is - the built-in one asks for them ascending, extensions hand back
     * whatever their own site returned - so this sorts by chapter number rather
     * than trusting a position. A chapter with no number loses to every
     * numbered one ("Extra" is not chapter zero) and, if *nothing* is numbered,
     * the source's own order decides, which is the only evidence there is.
     */
    fun firstChapter(chapters: List<Chapter>): Chapter? =
        chapters.minByOrNull { chapter -> chapter.number ?: NO_NUMBER }

    /** Whether the button says "Continue" rather than "Start". */
    fun isContinuing(resume: ReadingProgress?): Boolean = resume != null

    /**
     * The chapter the button opens, and the list that travels into the reader.
     *
     * Null when there is genuinely nothing to open: no progress, and no chapter
     * list to name chapter one from.
     */
    fun target(resume: ReadingProgress?, chapters: List<Chapter>?): ResumeContext? {
        if (resume == null) {
            val list = chapters ?: return null
            val first = firstChapter(list) ?: return null
            return ResumeContext(chapter = first, chapters = list, locatedInList = true)
        }
        // The history entry knows the chapter's id and name and nothing else;
        // resolving it in the real list is what gives the reader its arrows.
        return ChapterResume.resolve(Chapter(resume.chapterId, resume.chapterName, null), chapters)
    }

    /**
     * The most recent thing the reader read of *this* work, or null.
     *
     * History is keyed by chapter, and every entry it holds is "some work of
     * some source", so it has to be narrowed to this one before the newest of
     * it can mean anything. That is done in [belongsTo], and the newest
     * qualifying entry wins by its own timestamp rather than by wherever it
     * happens to sit in the list, because "the chapter they last read" means
     * the most recent one and not the last one written down.
     */
    fun hintFor(
        history: List<ReadingProgress>,
        sourceId: String,
        mangaId: String,
        chapters: List<Chapter>,
    ): ReadingProgress? {
        val listed = chapters.mapTo(mutableSetOf()) { it.id }
        return history
            .filter { entry ->
                entry.sourceId == sourceId &&
                    entry.chapterId.isNotBlank() &&
                    belongsTo(entry, mangaId, listed)
            }
            .maxByOrNull { it.updatedAt }
    }

    /**
     * Whether a history entry is about this work.
     *
     * When both sides know which work they are about, that is the whole answer
     * and the entry is taken at its word - including when the chapter is no
     * longer in the source's list at all, which is how a reorganised source
     * still gets continued.
     *
     * When only one side does - and that is the ordinary case, because a chapter
     * opened from Discover is recorded without the work's id - the chapter has
     * to be the evidence: it has to be one of this work's chapters. And an entry
     * that names a *different* work is never this one, even if a chapter id
     * happens to collide, because the page it saved belongs to that other
     * chapter.
     */
    private fun belongsTo(entry: ReadingProgress, mangaId: String, listed: Set<String>): Boolean {
        if (mangaId.isNotBlank() && entry.mangaId.isNotBlank()) return entry.mangaId == mangaId
        return entry.chapterId in listed
    }

    /** Sorts below every real chapter number. */
    private const val NO_NUMBER = Float.MAX_VALUE
}
