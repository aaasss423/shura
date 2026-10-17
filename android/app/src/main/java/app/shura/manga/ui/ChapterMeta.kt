package app.shura.manga.ui

import app.shura.manga.ui.screens.detailJoin

/**
 * What a chapter row is allowed to say about itself.
 *
 * THE RULE
 *
 * A meta line is a sentence about facts. So every part of one has to be a fact
 * this app actually holds, and a fact it does not hold is left out. It is never
 * replaced by a placeholder, a dash, or a word borrowed from somewhere else.
 *
 * That is the whole fix, and it is small because the alternative is worse: a
 * chapter row that read "Pro Chan - غير موجود" was claiming the chapter does
 * not exist. The app did not mean that. It had no date for the chapter - the
 * source contract carries an id, a name and a number, and nothing else
 * (`app.shura.source.Chapter`) - and something filled the gap with the
 * extension store's "not on device" wording, which is a different claim about a
 * different subject entirely. A reader cannot tell which one was meant, and the
 * wrong one is the one that reads as a fault.
 *
 * So the parts are decided here, from what is known, and joined by the same
 * separator every other detail line in the app uses.
 *
 * The words are passed in rather than read from [Strings] here: this is the
 * decision about which facts are shown, and the caller's language is the
 * caller's business. That also keeps it testable without a composition.
 */
object ChapterMeta {

    /** The words a chapter row can use, in the reader's language. */
    data class Labels(
        val unread: String,
        val read: String,
        val saved: String,
        val queued: String,
    )

    /**
     * The facts, in the order they are printed.
     *
     * The state is one word and only one: a chapter being saved says so instead
     * of saying whether it was read, because that is the more urgent fact about
     * it and because a row cannot honestly be two states at once.
     *
     * An empty [sourceName] contributes nothing. It is not replaced: there is no
     * name to print, so the line is the state on its own, and a line that says
     * one true thing is better than one padded with a word that means
     * something else.
     */
    fun parts(
        sourceName: String,
        read: Boolean,
        saved: Boolean = false,
        queued: Boolean = false,
        labels: Labels,
    ): List<String> = listOfNotNull(
        sourceName.trim().takeIf { it.isNotBlank() },
        state(read = read, saved = saved, queued = queued, labels = labels)
            .takeIf { it.isNotBlank() },
    )

    /**
     * The one word that says what is true of this chapter right now.
     *
     * Most specific first: a download in flight is the state a reader would
     * change their mind about, and "unread" under a progress bar is noise.
     */
    fun state(read: Boolean, saved: Boolean, queued: Boolean, labels: Labels): String = when {
        queued -> labels.queued
        saved -> labels.saved
        read -> labels.read
        else -> labels.unread
    }

    /** The whole line, as it is drawn. */
    fun line(
        sourceName: String,
        read: Boolean,
        saved: Boolean = false,
        queued: Boolean = false,
        labels: Labels,
    ): String = detailJoin(*parts(sourceName, read, saved, queued, labels).toTypedArray())
}
