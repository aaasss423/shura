package app.shura.manga

import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every number this app prints is printed from the arguments it was given.
 *
 * WHAT WAS WRONG
 *
 * Four strings passed two arguments and asked for both of them by index 1:
 *
 *     "library.manageSize"  "%1$d chapters · %1$s on device"
 *     "library.cleanupDone" "Deleted %1$d chapters (%1$s)"
 *     "repos.summary"       "%1$d repositories · %1$d extensions"
 *     "repos.added"         "Added %1$s (%1$d extensions)"
 *
 * So the second half of each line repeated the first: a manage dialog offering
 * "451 chapters · 451 on device", a summary saying there are as many extensions
 * as there are repositories, and - because `%1$d` was handed a *String* - a
 * notice that threw `IllegalFormatConversionException` rather than appearing at
 * all. A count that renders as a different count is worse than no count, and a
 * counter that throws takes the screen with it.
 *
 * These pin the rendered sentence for two deliberately different arguments, in
 * both languages, because "it did not throw" is not the claim - "it says both
 * numbers" is.
 */
class CounterFormattingTest {

    private val languages = UiLanguage.entries

    private fun both(key: String, vararg args: Any): Map<UiLanguage, String> =
        languages.associateWith { Strings.t(key, it, *args) }

    @Test
    fun `a size sentence carries the chapter count and the size, not one number twice`() {
        // 451 chapters, 1.5 MB. "451 chapters · 451 on device" is what this used
        // to say, because both halves asked for argument one.
        val arabic = Strings.t("library.manageSize", UiLanguage.ARABIC, 451, "1.5 MB")
        val english = Strings.t("library.manageSize", UiLanguage.ENGLISH, 451, "1.5 MB")

        assertEquals("451 فصل · 1.5 MB على الجهاز", arabic)
        assertEquals("451 chapters · 1.5 MB on device", english)
    }

    @Test
    fun `a cleanup notice carries the count and the size`() {
        val arabic = Strings.t("library.cleanupDone", UiLanguage.ARABIC, 451, "1.5 MB")
        val english = Strings.t("library.cleanupDone", UiLanguage.ENGLISH, 451, "1.5 MB")

        assertEquals("حُذف 451 فصل (1.5 MB)", arabic)
        assertEquals("Deleted 451 chapters (1.5 MB)", english)
    }

    @Test
    fun `a repository summary carries both numbers rather than the first one twice`() {
        val arabic = Strings.t("repos.summary", UiLanguage.ARABIC, 2, 72)
        val english = Strings.t("repos.summary", UiLanguage.ENGLISH, 2, 72)

        assertEquals("2 مستودع · 72 إضافة", arabic)
        assertEquals("2 repositories · 72 extensions", english)
    }

    @Test
    fun `adding a repository says its name and its package count instead of throwing`() {
        // `%1$d` with a String argument raises IllegalFormatConversionException on
        // a device, which is the whole notice not appearing at all.
        val arabic = Strings.t("repos.added", UiLanguage.ARABIC, "Shura", 72)
        val english = Strings.t("repos.added", UiLanguage.ENGLISH, "Shura", 72)

        assertEquals("أُضيف Shura (72 إضافة)", arabic)
        assertEquals("Added Shura (72 extensions)", english)

        assertEquals(
            "Updated Shura (72 extensions)",
            Strings.t("repos.updated", UiLanguage.ENGLISH, "Shura", 72),
        )
    }

    @Test
    fun `no string asks for an argument twice by the same index`() {
        // The whole class of defect, over every string rather than the four that
        // were found: a `%N$` that appears twice with the same N means the second
        // occurrence is printing the first occurrence's argument.
        val table = listOf(
            "library.manageSize" to 2,
            "library.cleanupDone" to 2,
            "repos.summary" to 2,
            "repos.added" to 2,
            "repos.updated" to 2,
            "downloads.summary" to 2,
            "downloads.progress" to 2,
            "library.progress" to 2,
            "reader.pages" to 2,
        )
        languages.forEach { language ->
            table.forEach { (key, args) ->
                val text = Strings.t(key, language, *Array<Any>(args) { 7 })
                val indices = Regex("%(\\d+)\\$(?:s|d)").findAll(text)
                    .map { it.groupValues[1] }
                    .toList()
                assertEquals(
                    "$key asks for the same argument twice: '$text'",
                    indices.size,
                    indices.distinct().size,
                )
            }
        }
    }

    @Test
    fun `every number pair renders two different numbers as two different numbers`() {
        // The sentence must actually depend on both arguments: a string whose
        // second number is accepted and ignored passes every "did not throw"
        // check and still prints one count twice.
        val pairs = listOf(
            "repos.summary" to (2 to 72),
            "downloads.summary" to (3 to 40),
            "downloads.progress" to (3 to 40),
            "library.progress" to (3 to 40),
            "reader.pages" to (3 to 40),
            "library.selected" to (1 to 4),
            "search.results" to (1 to 4),
        )
        languages.forEach { language ->
            pairs.forEach { (key, args) ->
                val (first, second) = args
                val one = Strings.t(key, language, first, second)
                val other = Strings.t(key, language, second, first)
                assertFalse("$key ignores one of its numbers in ${language.name}: '$one'", one == other)
                assertTrue("$key dropped an argument in ${language.name}: '$one'", one.isNotBlank())
            }
        }
    }

    @Test
    fun `every count and size pair renders both of them`() {
        // These take an Int and a formatted size, in that order. They used to ask
        // for argument one twice, so the size line printed the chapter count.
        val pairs = listOf(
            "library.manageSize" to (451 to "1.5 MB"),
            "library.cleanupDone" to (451 to "1.5 MB"),
        )
        languages.forEach { language ->
            pairs.forEach { (key, args) ->
                val (count, size) = args
                val one = Strings.t(key, language, count, size)
                val other = Strings.t(key, language, count, "9.9 MB")
                assertTrue("$key dropped its size in ${language.name}: '$one'", one.contains(size))
                assertFalse("$key ignored its size argument in ${language.name}", one == other)
            }
        }
    }

    @Test
    fun `every name and count pair renders both of them`() {
        // These take a String first and a number second. `repos.added` used to ask
        // for argument one twice, so its `%1$d` was handed the repository's name
        // and threw IllegalFormatConversionException rather than saying anything.
        val pairs = listOf(
            "repos.added" to ("Shura" to 72),
            "repos.updated" to ("Shura" to 72),
        )
        languages.forEach { language ->
            pairs.forEach { (key, args) ->
                val (name, count) = args
                val one = Strings.t(key, language, name, count)
                assertTrue("$key dropped its name in ${language.name}: '$one'", one.contains(name))
                assertTrue("$key dropped its count in ${language.name}: '$one'", one.contains(count.toString()))
            }
        }
    }

    @Test
    fun `every string with two placeholders exists in both languages`() {
        // A key present in no language renders as the key itself, which is
        // visible rather than silently blank - and in an Arabic interface it is
        // English-shaped noise.
        val keys = listOf(
            "library.manageSize", "library.cleanupDone", "repos.summary",
            "repos.added", "repos.updated", "repos.packages", "repos.checkedAt",
            "repos.removed", "repos.removeFailed", "chapters.number",
            "downloads.summary", "downloads.progress", "downloads.workChapters",
            "downloads.workActive", "discover.chapterCount", "reader.pages",
        )
        languages.forEach { language ->
            keys.forEach { key ->
                assertFalse("$key is missing in ${language.name}", Strings.t(key, language, 1, 2) == key)
            }
        }
    }

    @Test
    fun `a work's chapter count is rendered from the chapter count`() {
        // The details page's own count, and the work rows' - the two places a
        // reader is told how long a series is. Both come from the count and
        // nothing else, so a chapter count can never be printed as anything
        // about a download.
        assertEquals("451 فصل", Strings.t("discover.chapterCount", UiLanguage.ARABIC, 451))
        assertEquals("451 chapters", Strings.t("discover.chapterCount", UiLanguage.ENGLISH, 451))
        assertEquals("451 فصل على الجهاز", Strings.t("downloads.workChapters", UiLanguage.ARABIC, 451))
        assertEquals("451 chapters on device", Strings.t("downloads.workChapters", UiLanguage.ENGLISH, 451))
        assertEquals("451 قيد التنزيل", Strings.t("downloads.workActive", UiLanguage.ARABIC, 451))
        assertEquals("451 downloading", Strings.t("downloads.workActive", UiLanguage.ENGLISH, 451))
    }
}
