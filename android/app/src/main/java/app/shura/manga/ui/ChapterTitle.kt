package app.shura.manga.ui

import app.shura.source.Chapter
import app.shura.source.ChapterNames

/**
 * What a chapter row calls itself.
 *
 * THE BUG THIS REPLACES
 *
 * Every chapter row in the app read "null". Hundreds of them, one after
 * another, down the chapter list: 451, 450, 449, each with the four letters
 * `null` where the chapter's name belongs. Two things had to be wrong for that
 * to reach a screen.
 *
 * The source: `org.json`'s `optString` does not read a JSON `null` as "no
 * value" - it runs the value through `String.valueOf`, and `JSONObject.NULL`
 * stringifies to the four characters `null`. MangaDex publishes `"title": null`
 * for every chapter that has no translated title, so
 * `attributes.optString("title")` returned the word "null", and
 * `title.isNotBlank()` was happy to keep it. A source that reports no name is
 * a normal thing; a row that prints "null" for it is not.
 *
 * The fallback: the row did `chapter.name.ifBlank { chapter.id }`, and
 * `ifBlank` is only ever asked about emptiness. "null" is not empty. So even a
 * source that handed over the word by accident produced a row saying "null"
 * rather than a row saying something true about the chapter.
 *
 * THE RULE
 *
 * A row says the chapter's name when the source gave one. When the source gave
 * none, it says the chapter's *number*, which the same payload does carry and
 * which is the other half of what identifies a chapter. Only when there is
 * neither does it fall back to the chapter id - the one string that is always
 * there and always names the chapter, however ugly it looks.
 *
 * The word in front of the number is passed in rather than read from [Strings]
 * here, for the reason [ChapterMeta] does the same: this is the decision about
 * which fact is shown, and the caller's language is the caller's business. It
 * also keeps the decision testable without a composition.
 */
object ChapterTitle {

    /** Whether [raw] is something the source said rather than something it lacked. */
    fun isMissing(raw: String?): Boolean = ChapterNames.isMissing(raw)

    /**
     * The name a source actually gave, or "" when it gave none.
     *
     * The single place the four characters "null" stop being a chapter's name.
     * Everything that carries a chapter's name across a boundary goes through
     * [ChapterNames] - the host sources, the runtime that adapts an extension,
     * and the stored manifest - so a source, an extension and a saved chapter
     * are all held to the same rule rather than each to a slightly different one.
     */
    fun nameOf(raw: String?): String = ChapterNames.usable(raw)

    /**
     * A chapter number the way a reader writes it: whole numbers lose the
     * decimal, and a real fraction keeps only its fraction.
     *
     * Sources are loose about numbers - absent, `"12"`, `12.0`, `12.5` - so the
     * Float is never printed raw. "1.0" in the leading column of four hundred
     * rows is the artefact this removes.
     */
    fun numberLabel(number: Float?): String? {
        if (number == null || number.isNaN()) return null
        val whole = number.toLong()
        return if (whole.toFloat() == number) whole.toString() else number.toString()
    }

    /** The row's own line: the name, else the number, else the id. */
    fun of(chapter: Chapter): String =
        titled(name = chapter.name, number = chapter.number, id = chapter.id, chapterWord = "")

    /**
     * The same, with the reader's word for a chapter in front of the number.
     *
     * A bare number is a poor row title - it cannot be told from the number in
     * the leading column, and a screen full of them says nothing. With the word
     * in front it is a sentence the reader can read, and it is still only what
     * the source reported.
     */
    fun titled(name: String?, number: Float?, id: String, chapterWord: String): String {
        val given = nameOf(name)
        if (given.isNotEmpty()) return given
        val printed = numberLabel(number)
        if (printed != null) {
            return if (chapterWord.isBlank()) printed else "$chapterWord $printed"
        }
        // Last resort, and it is a fact rather than an invention: the chapter's
        // own id is how every other part of the app names it.
        return id.trim().ifEmpty { id }
    }
}