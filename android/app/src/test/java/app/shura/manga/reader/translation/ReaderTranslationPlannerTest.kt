package app.shura.manga.reader.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTranslationPlannerTest {

    @Test fun offModeRequestsNothing() {
        val pages = ReaderTranslationPlanner.pagesToTranslate(
            TranslationSettings(mode = TranslationMode.OFF), 20, 4, emptySet()
        )
        assertTrue(pages.isEmpty())
    }

    @Test fun fullModePrefetchesTheWholeChapter() {
        val pages = ReaderTranslationPlanner.pagesToTranslate(
            TranslationSettings(mode = TranslationMode.FULL), 20, 0, setOf(0, 1)
        )
        assertEquals((2 until 20).toList(), pages)
    }

    @Test fun followReadingModeStaysNearTheVisiblePage() {
        val pages = ReaderTranslationPlanner.pagesToTranslate(
            TranslationSettings(mode = TranslationMode.FOLLOW_READING), 50, 10, emptySet()
        )
        assertEquals(listOf(10, 11, 12), pages)
    }

    @Test fun followReadingDoesNotRewindNorTraverseTheWholeChapter() {
        val settings = TranslationSettings(mode = TranslationMode.FOLLOW_READING)
        val atPage42 = ReaderTranslationPlanner.pagesToTranslate(settings, 100, 42, emptySet())
        val atPage5 = ReaderTranslationPlanner.pagesToTranslate(settings, 100, 5, emptySet())
        assertTrue(atPage42.size == 3)
        assertTrue(atPage42.all { it >= 42 })
        assertTrue(atPage5.all { it >= 5 })
    }

    @Test fun followReadingClampsAtTheLastPage() {
        val pages = ReaderTranslationPlanner.pagesToTranslate(
            TranslationSettings(mode = TranslationMode.FOLLOW_READING), 5, 4, emptySet()
        )
        assertEquals(listOf(4), pages)
    }
}