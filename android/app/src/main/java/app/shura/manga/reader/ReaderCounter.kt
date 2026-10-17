package app.shura.manga.reader

/**
 * The reader's page counter, as one decision.
 *
 * WHAT WAS WRONG
 *
 * The reader drew its position from `totalPages ?: pages.size`, and the two
 * halves of that expression mean different things. `totalPages` is the
 * *chapter's* page count - what the source says this chapter has - and
 * `pages.size` is how many of them have arrived so far. They are not
 * interchangeable, and substituting one for the other produces the two numbers
 * a reader could actually see:
 *
 *  - **"1 / 1" for a chapter of forty.** The activity publishes the chapter's
 *    count as a nullable total, and `null` used to be answered with the loaded
 *    count. A chapter whose first page had landed and whose thirty-ninth had
 *    not was therefore a one-page chapter, for as long as it took the rest to
 *    arrive - and on a stored chapter opened straight off the disk it stayed
 *    that way.
 *  - **"page 2 of 1".** The page number came from the reader's own index with
 *    no bound at all, so any disagreement between the two numbers - a list that
 *    shrank, a chapter that changed under a reader who was mid-turn, a total
 *    that had not been published yet - printed a page above the end of the
 *    chapter. A counter that says "2 of 1" is worse than no counter, because it
 *    looks like the app is reading the wrong book.
 *
 * THE RULE
 *
 * The chapter's count wins when it is known, and the loaded count is the
 * fallback rather than the other way round - so a chapter of forty reads "40"
 * from the moment it opens, and a chapter whose total was never published still
 * counts itself correctly from what has landed. The page number is then bounded
 * by that total, always. Neither number is ever zero and neither is ever
 * guessed: a total of at least one is what "this chapter has pages" means.
 *
 * Pure, so it can be pinned: a counter that cannot be checked by reading it is
 * a counter that will drift.
 */
object ReaderCounter {

    /** What the reader says about where it is. */
    data class Counter(val page: Int, val total: Int) {
        /** "3 / 40", as the reader's bar draws it. */
        override fun toString(): String = "$page / $total"
    }

    /**
     * How many pages this chapter has.
     *
     * [known] is what the source said, or null when nothing has said yet.
     * [loaded] is how many have arrived. At least one either way: a reader that
     * is looking at a page is looking at a chapter that has at least that one.
     */
    fun total(known: Int?, loaded: Int): Int = maxOf(known ?: 0, loaded, 1)

    /**
     * The page on screen, counted from one and never past the end.
     *
     * [index] is whatever the reader's own state says, which is a zero-based
     * index into a list that can be shorter than the chapter or longer than the
     * count that was published. Both ends are clamped here, once, rather than at
     * each of the three places that print it.
     */
    fun page(index: Int, total: Int): Int = (index + 1).coerceIn(1, maxOf(total, 1))

    /** Both numbers at once, which is how every surface asks for them. */
    fun of(index: Int, known: Int?, loaded: Int): Counter {
        val count = total(known, loaded)
        return Counter(page = page(index, count), total = count)
    }
}