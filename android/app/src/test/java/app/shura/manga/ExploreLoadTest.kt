package app.shura.manga

import app.shura.manga.ui.ExploreLoadGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entering the search screen has to ask the source something.
 *
 * The screen that lists a source's catalogue opened onto nothing: no results, no
 * spinner, no error, and nothing to say a request had never been made. The only
 * way anything appeared was the search button - so "the screen is empty" and "the
 * screen has not asked yet" looked identical, and the reader had to guess which
 * one they were looking at.
 *
 * The half of this a test can hold is the decision: one request per entry, and
 * not one more for every recomposition. A Compose screen recomposes whenever
 * anything it observes changes - a download finishing, the language switching,
 * the keyboard opening - and if entering the screen were "run the request" as
 * plain code in the body, each of those would be another request to a source that
 * is a real server.
 */
class ExploreLoadTest {

    @Test
    fun `the first request for a source is allowed`() {
        assertTrue(ExploreLoadGate().shouldStart("procomic"))
    }

    @Test
    fun `recomposing does not start another request`() {
        val gate = ExploreLoadGate()
        assertTrue("the first entry asks", gate.shouldStart("procomic"))

        // A recomposition is the same screen being drawn again. Fifty of them is
        // not fifty requests; the second one is already a bug.
        repeat(50) { assertFalse("recomposition #$it started a request", gate.shouldStart("procomic")) }
        assertTrue(gate.hasStarted("procomic"))
    }

    @Test
    fun `a second source entering starts its own request`() {
        // Opening a different catalogue is a different screen, and the reader
        // expects it to have looked. Keyed by the source, not "once ever".
        val gate = ExploreLoadGate()
        assertTrue(gate.shouldStart("one"))
        assertTrue(gate.shouldStart("two"))
        assertFalse(gate.shouldStart("one"))
    }

    @Test
    fun `coming back to the screen asks again`() {
        // Leaving and returning builds a new screen, so it gets a new gate. A
        // reader who went away and came back to a stale list would rightly call
        // that a bug of its own.
        assertTrue(ExploreLoadGate().shouldStart("procomic"))
        assertTrue(ExploreLoadGate().shouldStart("procomic"))
    }

    @Test
    fun `a gate that has not started a source says so`() {
        val gate = ExploreLoadGate()
        assertFalse(gate.hasStarted("procomic"))
        gate.shouldStart("procomic")
        assertTrue(gate.hasStarted("procomic"))
    }
}
