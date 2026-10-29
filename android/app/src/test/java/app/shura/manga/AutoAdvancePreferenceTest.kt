package app.shura.manga

import app.shura.manga.reader.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-advance must be off until asked for, and its delay must stay inside a
 * range a reader can actually wait for.
 *
 * The countdown is offered by the reader, but the numbers come from here: a
 * stored value of `0` from a hand-edited preference, or a slider that landed on
 * an absurd delay, must not make a chapter jump the instant it opens.
 */
class AutoAdvancePreferenceTest {

    @Test
    fun `auto advance is off by default with a three second delay`() {
        val preferences = ReaderPreferences()
        assertFalse(preferences.autoAdvanceChapter)
        assertEquals(3, preferences.autoAdvanceDelay)
    }

    @Test
    fun `the delay is clamped to a readable range`() {
        assertEquals(2, ReaderPreferences(autoAdvanceSeconds = 0).autoAdvanceDelay)
        assertEquals(2, ReaderPreferences(autoAdvanceSeconds = 1).autoAdvanceDelay)
        assertEquals(3, ReaderPreferences(autoAdvanceSeconds = 3).autoAdvanceDelay)
        assertEquals(10, ReaderPreferences(autoAdvanceSeconds = 30).autoAdvanceDelay)
        assertEquals(2, ReaderPreferences.autoAdvanceSecondsOf(-5))
        assertEquals(2, ReaderPreferences.autoAdvanceSecondsOf(0))
    }

    @Test
    fun `enabling keeps the chosen delay`() {
        val preferences = ReaderPreferences(autoAdvanceChapter = true, autoAdvanceSeconds = 5)
        assertTrue(preferences.autoAdvanceChapter)
        assertEquals(5, preferences.autoAdvanceDelay)
    }
}
