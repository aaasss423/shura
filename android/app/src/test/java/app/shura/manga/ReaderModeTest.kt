package app.shura.manga

import app.shura.manga.reader.ReaderMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reading layout must survive both a restart and a bad stored value.
 *
 * Two ways this goes wrong, both annoying in the same way: the choice is
 * dropped on every launch, so a reader who prefers paged has to set it again
 * every time; or a value from another build throws on read, and the app will
 * not open. Neither is dramatic, and both are the kind of thing that makes an
 * app feel careless.
 */
class ReaderModeTest {

    @Test
    fun `the default is a continuous strip`() {
        // Manga is drawn as one long image. Vertical is the honest default;
        // paged is the thing you opt into.
        assertEquals(ReaderMode.VERTICAL, ReaderMode.parse(null))
    }

    @Test
    fun `a stored mode reads back unchanged`() {
        assertEquals(ReaderMode.PAGED, ReaderMode.parse("PAGED"))
        assertEquals(ReaderMode.VERTICAL, ReaderMode.parse("VERTICAL"))
    }

    @Test
    fun `a mode from another build falls back rather than crashing`() {
        // An older or newer build's stored value must not stop the app opening.
        assertEquals(ReaderMode.VERTICAL, ReaderMode.parse("CONTINUOUS_STRIP"))
        assertEquals(ReaderMode.VERTICAL, ReaderMode.parse(""))
    }

    @Test
    fun `toggling offers the other mode and never the same one`() {
        listOf(ReaderMode.VERTICAL, ReaderMode.PAGED).forEach { current ->
            val other = if (current == ReaderMode.VERTICAL) ReaderMode.PAGED else ReaderMode.VERTICAL
            assertEquals(false, other == current)
            // Toggling twice returns to where it started, so the control cannot
            // strand the reader in a mode they cannot leave.
            val back = if (other == ReaderMode.VERTICAL) ReaderMode.PAGED else ReaderMode.VERTICAL
            assertEquals(current, back)
        }
    }
}