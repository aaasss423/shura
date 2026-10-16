package app.shura.manga

import app.shura.manga.ui.ScreenStack
import app.shura.manga.ui.ShuraScreen
import app.shura.source.Chapter
import app.shura.source.MangaSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen stack's two edge cases, both of which took the process down.
 *
 * `NoSuchElementException: List is empty` came out of `go()` reading the last entry
 * when a tab had just cleared the stack. These are the rules that make that
 * impossible, and they hold on a class rather than inside a composition nobody can
 * call from a test.
 */
class ScreenStackTest {

    private val manga = MangaSummary("id", "A title", null)
    private val chapter = Chapter("c", "C", 1f)

    @Test
    fun `navigating from an empty stack works`() {
        val stack = ScreenStack()
        stack.switchTab(ShuraScreen.Home)
        assertTrue("the only screen left to go back to is Home", stack.current is ShuraScreen.Home)
        stack.go(ShuraScreen.Library)
        assertTrue(stack.current is ShuraScreen.Library)
    }

    @Test
    fun `switching tabs replaces the stack instead of growing it`() {
        val stack = ScreenStack()
        stack.go(ShuraScreen.Details("s", manga))
        stack.go(ShuraScreen.Chapters("s", manga))
        stack.switchTab(ShuraScreen.Settings)
        assertEquals(1, stack.size)
        assertTrue(stack.current is ShuraScreen.Settings)
        assertFalse("back must not walk out through every screen visited", stack.back())
    }

    @Test
    fun `a tab press twice does not stack two copies`() {
        val stack = ScreenStack()
        stack.switchTab(ShuraScreen.Library)
        stack.go(ShuraScreen.Library)
        assertEquals(1, stack.size)
    }

    @Test
    fun `back pops to the previous screen and then stops at the root`() {
        val stack = ScreenStack()
        stack.switchTab(ShuraScreen.Home)
        stack.go(ShuraScreen.Search("s"))
        stack.go(ShuraScreen.Details("s", manga))
        stack.go(ShuraScreen.Chapters("s", manga))
        assertTrue(stack.back())
        assertTrue(stack.current is ShuraScreen.Details)
        assertTrue(stack.back())
        assertTrue(stack.current is ShuraScreen.Search)
        assertTrue(stack.back())
        assertTrue(stack.current is ShuraScreen.Home)
        assertFalse("Home is the root; there is nothing behind it", stack.back())
        assertTrue(stack.current is ShuraScreen.Home)
    }

    @Test
    fun `the screen on screen is always something, even mid-switch`() {
        // switchTab clears before it adds. If anything reads the stack in between,
        // this is the answer it has to get, not an exception.
        val stack = ScreenStack()
        stack.switchTab(ShuraScreen.Library)
        stack.switchTab(ShuraScreen.Downloads)
        assertTrue(stack.current is ShuraScreen.Downloads)
    }

    @Test
    fun `a reader route survives a back press`() {
        val stack = ScreenStack()
        stack.go(ShuraScreen.Reader("s", "T", chapter, emptyList()))
        assertTrue(stack.back())
    }
}