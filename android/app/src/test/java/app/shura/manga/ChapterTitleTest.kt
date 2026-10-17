package app.shura.manga

import app.shura.manga.ui.ChapterMeta
import app.shura.manga.ui.ChapterTitle
import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a chapter row is allowed to call itself.
 *
 * THE SCREEN RECORDING
 *
 * Every chapter row read "null". Hundreds of them, down the list, with the
 * numbers 451, 450 and 449 in the column beside them - so the number was there,
 * the payload that carried it was there, and only the name was the four letters
 * a JSON parser prints for a field that was not there.
 *
 * Two defects, and both are pinned here because either one alone puts the word
 * back on the screen:
 *
 *  - a source that reads a title out of `org.json` with `optString`, which
 *    stringifies a JSON `null` rather than returning nothing;
 *  - a row that falls back with `ifBlank`, which is only ever asked whether the
 *    name is empty and never whether it is a name at all.
 *
 * So these tests hold the rule - a name when the source gave one, the number
 * when it did not, and never the word - and the parser test holds the boundary.
 */
class ChapterTitleTest {

    private fun titled(name: String?, number: Float?, id: String = "c1", word: String = "Chapter") =
        ChapterTitle.titled(name = name, number = number, id = id, chapterWord = word)

    // -- the literal "null" --------------------------------------------------

    @Test
    fun `a chapter whose name is the word null is not titled null`() {
        // Exactly what MangaDex sends: `"title": null` for a chapter with no
        // translated title. org.json's optString turns that into four letters,
        // and four hundred rows came out saying them.
        val chapter = Chapter("abc", "null", 451f)
        assertEquals("Chapter 451", titled("null", chapter.number, chapter.id))
    }

    @Test
    fun `the other ways of saying nothing are nothing too`() {
        // "undefined", "none", "NaN": the same class of accident from a different
        // parser, and a bare dash from a renderer. A row of four hundred dashes
        // is as useless as a row of four hundred nulls.
        for (missing in listOf("", "   ", "null", "NULL", "Null", "none", "undefined", "NaN", "-", "—", "?", "n/a")) {
            assertTrue("'$missing' was accepted as a name", ChapterTitle.isMissing(missing))
            assertEquals("", ChapterTitle.nameOf(missing))
        }
    }

    @Test
    fun `no name and no number falls back to the chapter id rather than nothing`() {
        // The id is a fact: it is how every other part of the app names this
        // chapter. A row with an empty title is not an answer.
        assertEquals("c451", titled("null", null, "c451"))
        assertEquals("c451", titled("", null, "c451"))
    }

    // -- number or name, whichever the source gave --------------------------

    @Test
    fun `a chapter with a number and no name shows the number`() {
        assertEquals("Chapter 451", titled("null", 451f))
        assertEquals("Chapter 12.5", titled("", 12.5f))
        // The reader's word, in the reader's language.
        assertEquals("الفصل 451", titled("null", 451f, word = "الفصل"))
    }

    @Test
    fun `a chapter with a name shows the name and not the number`() {
        // The name is the better half when there is one: it is what the site's
        // editor wrote, and a number beside it is already in the leading column.
        assertEquals("The Ghostly Doctor", titled("The Ghostly Doctor", 451f))
        assertEquals("الفصل الأخير", titled("الفصل الأخير", 451f, word = "الفصل"))
    }

    @Test
    fun `a bare number is never the whole of a row title`() {
        // Without the word, the title would be indistinguishable from the number
        // already printed in the leading column of every row.
        assertEquals("451", ChapterTitle.of(Chapter("c1", "null", 451f)))
        assertTrue(
            "the number is the whole line",
            ChapterTitle.titled("null", 451f, "c1", "Chapter") != "451",
        )
    }

    // -- numbers --------------------------------------------------------------

    @Test
    fun `whole numbers lose the decimal and real fractions keep only theirs`() {
        // Sources send 12, "12.0", 12 and 12.5 for the same chapter.
        assertEquals("12", ChapterTitle.numberLabel(12f))
        assertEquals("12", ChapterTitle.numberLabel(12.0f))
        assertEquals("12.5", ChapterTitle.numberLabel(12.5f))
        assertEquals(null, ChapterTitle.numberLabel(null))
        assertEquals(null, ChapterTitle.numberLabel(Float.NaN))
    }

    // -- the whole row --------------------------------------------------------

    @Test
    fun `a row with a null name still says one true thing under it`() {
        // The title is fixed by the number; the line under it is fixed by the
        // state. Neither borrows a word it has no fact for.
        val chapter = Chapter("abc", "null", 451f)
        val title = titled(chapter.name, chapter.number, chapter.id)
        val labels = ChapterMeta.Labels("غير مقروء", "مقروء", "محفوظة", "جارٍ")
        val meta = ChapterMeta.line(sourceName = "", read = false, labels = labels)

        assertFalse("the title is the word null", title.trim().equals("null", true))
        assertTrue("the title has no number in it: '$title'", title.contains("451"))
        assertEquals("غير مقروء", meta)
    }

    // -- the words ------------------------------------------------------------

    @Test
    fun `the word in front of a number exists in both languages`() {
        for (language in UiLanguage.entries) {
            val word = Strings.t("chapters.number", language, "451")
            assertFalse(
                "chapters.number is missing in ${language.name}",
                word == "chapters.number",
            )
            assertTrue("chapters.number did not take its argument: '$word'", word.contains("451"))
        }
        assertEquals("الفصل 451", Strings.t("chapters.number", UiLanguage.ARABIC, "451"))
        assertEquals("Chapter 451", Strings.t("chapters.number", UiLanguage.ENGLISH, "451"))
    }
}