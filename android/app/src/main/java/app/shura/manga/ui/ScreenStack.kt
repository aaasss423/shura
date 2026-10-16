package app.shura.manga.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * The screen stack, with the two rules the UI depends on.
 *
 * This is here as a class rather than as two closures inside the composition
 * because both of its edge cases crashed the app in ways that were impossible to
 * see in review:
 *
 *  - a tab handler clears the stack and then navigates, so the navigation has to
 *    survive finding nothing to compare against (`last()` on an empty list threw
 *    `NoSuchElementException: List is empty`, and the process died on any tab
 *    press);
 *  - the screen being drawn is whatever is left, so the stack has to have an
 *    answer even while it is momentarily empty.
 */
class ScreenStack(initial: ShuraScreen = ShuraScreen.Home) {

    private val entries = mutableStateListOf<ShuraScreen>(initial)

    /** What is on screen. Home whenever the stack is empty. */
    val current: ShuraScreen get() = entries.lastOrNull() ?: ShuraScreen.Home

    val size: Int get() = entries.size

    fun lastOrNull(): ShuraScreen? = entries.lastOrNull()

    /**
     * Pushes a screen.
     *
     * Re-entering the screen already showing is ignored, so a double tap cannot
     * stack two copies of the same thing. Safe on an empty stack, which is the
     * state a tab leaves behind.
     */
    fun go(target: ShuraScreen) {
        if (entries.lastOrNull() == target) return
        entries.add(target)
    }

    /** Pops one screen. False when there is nothing left to go back to. */
    fun back(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** A tab replaces the stack: back must not walk out through every tab visited. */
    fun switchTab(target: ShuraScreen) {
        entries.clear()
        entries.add(target)
    }
}