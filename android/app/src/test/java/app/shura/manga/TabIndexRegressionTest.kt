package app.shura.manga

import app.shura.manga.ui.ShuraScreen
import app.shura.manga.ui.tabIndexFor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab row must never be handed an index it cannot use.
 *
 * The app crashed with `IndexOutOfBoundsException: Index -1 out of bounds` inside
 * Material3's TabRow, because a work's page and its chapter list are not tabs and
 * the mapping returned -1 for them. Pressing any tab from inside a work took the
 * process down. These pin every screen to a real index.
 */
class TabIndexRegressionTest {

    @Test
    fun `every screen resolves to a tab index the tab row can use`() {
        val manga = app.shura.source.MangaSummary("id", "A title", null)
        val screens = listOf(
            ShuraScreen.Home,
            ShuraScreen.Sources,
            ShuraScreen.Library,
            ShuraScreen.Downloads,
            ShuraScreen.Settings,
            ShuraScreen.Extensions,
            ShuraScreen.Search("s"),
            ShuraScreen.Details("s", manga),
            ShuraScreen.Chapters("s", manga),
            ShuraScreen.Reader("s", "A title", app.shura.source.Chapter("c", "C", 1f), emptyList()),
        )
        for (screen in screens) {
            val index = tabIndexFor(screen)
            assertTrue("$screen resolved to $index", index in 0..4)
        }
    }

    @Test
    fun `a work's pages belong to the tab the reader came from`() {
        val manga = app.shura.source.MangaSummary("id", "A title", null)
        val discover = tabIndexFor(ShuraScreen.Sources)
        assertTrue(tabIndexFor(ShuraScreen.Search("s")) == discover)
        assertTrue(tabIndexFor(ShuraScreen.Details("s", manga)) == discover)
        assertTrue(tabIndexFor(ShuraScreen.Chapters("s", manga)) == discover)
    }
}
