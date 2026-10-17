package app.shura.runtime

import app.shura.source.ChapterNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the adapter applies to an extension's chapter names.
 *
 * `ExtensionSourceAdapter.chapters()` maps every `SChapter` an extension hands
 * back through [ChapterNames] before it becomes a chapter of this app's own
 * model. That map is the boundary, and the reason it exists is that the shipped
 * extensions are DEX files this project cannot recompile: they read titles out
 * of `org.json`, where `optString` on a JSON `null` returns the four characters
 * "null" on Android rather than nothing, and a catalogue that reports no title
 * for a chapter - which is most of a long series - then produces a chapter list
 * in which every row is titled "null".
 *
 * So the name an extension gives is held to the same rule as one this project's
 * own source gives, and this pins the shared list rather than a private copy of
 * it.
 */
class ExtensionChapterNamesTest {

    @Test
    fun `the word null is not a chapter name`() {
        assertEquals("", ChapterNames.usable("null"))
        assertEquals("", ChapterNames.usable("NULL"))
        assertEquals("", ChapterNames.usable(" null "))
        assertTrue(ChapterNames.isMissing("null"))
    }

    @Test
    fun `a name the site really wrote survives`() {
        assertEquals("Pilot", ChapterNames.usable("Pilot"))
        assertEquals("الفصل 451", ChapterNames.usable("  الفصل 451  "))
        assertFalse(ChapterNames.isMissing("Pilot"))
    }

    @Test
    fun `the other ways of saying nothing are nothing too`() {
        for (missing in listOf("", "   ", "none", "undefined", "NaN", "-", "—", "?", "n/a", null)) {
            assertTrue("'$missing' was accepted as a name", ChapterNames.isMissing(missing))
            assertEquals("", ChapterNames.usable(missing))
        }
    }

    @Test
    fun `a chapter titled with a word that merely mentions null is kept`() {
        // The rule is about the whole value, not about substrings: a chapter
        // genuinely called "Null Island" is a chapter with a name.
        assertEquals("Null Island", ChapterNames.usable("Null Island"))
        assertEquals("Chapter 451 - null", ChapterNames.usable("Chapter 451 - null"))
    }
}