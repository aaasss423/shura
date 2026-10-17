package app.shura.source

/**
 * When a chapter's "name" is not a name.
 *
 * WHY THIS LIVES NEXT TO THE MODEL
 *
 * Because every module that touches a chapter's name has to agree on the same
 * list, and three of them did not. The source API is the one module all of them
 * already depend on - the host adapters, the extension runtime that adapts a
 * DEX this project cannot recompile, and the app that draws the rows - so the
 * rule is here rather than copied into each of them.
 *
 * THE FOUR LETTERS
 *
 * `org.json`'s `optString` is not "the string, or empty". On Android it runs
 * whatever is there through `String.valueOf`, and `JSONObject.NULL`
 * stringifies to the four characters `null`. Every source that reads a chapter
 * title through it therefore hands back the word "null" for a chapter that has
 * no title - which is most of a long series - and the app then prints it on
 * every row, with the chapter's own number sitting in the column beside it.
 *
 * So a name is only a name when it is neither empty nor one of the words a
 * parser emits when it had nothing. What a row draws instead - the chapter's
 * number, or its id - is a decision about facts and lives with the row.
 */
object ChapterNames {

    /**
     * Values that mean "the parser had nothing", not "the site called it this".
     *
     * The dashes and the question mark are here because a chapter with no title
     * is sometimes rendered as one by whatever built the row, and a list of four
     * hundred identical dashes is exactly as useless as a list of four hundred
     * identical nulls.
     */
    private val NOT_A_NAME = setOf(
        "null", "nil", "none", "undefined", "nan", "n/a", "na",
        "-", "--", "---", "\u2013", "\u2014", "?", "??",
    )

    /** Whether [raw] is something the source said rather than something it lacked. */
    fun isMissing(raw: String?): Boolean = usable(raw).isEmpty()

    /** The name a source actually gave, or "" when it gave none. */
    fun usable(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return if (text.lowercase() in NOT_A_NAME) "" else text
    }
}