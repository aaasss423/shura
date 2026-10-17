package app.shura.manga.ui

/**
 * Starting a screen's first request, exactly once.
 *
 * WHY THIS IS A CLASS AND NOT A LINE IN THE SCREEN
 *
 * A search screen that only asks its source for something when the reader
 * presses the search button opens onto nothing: no list, no spinner, no error,
 * and nothing that says a request was never made. Entering the screen has to be
 * the request, or the reader has to know they have to ask - and the screen gave
 * them no way to tell which state they were in.
 *
 * The obvious way to write "load on enter" inside a Compose screen is a
 * `LaunchedEffect` keyed by the thing being loaded, which does restart when the
 * key changes and not on every recomposition. That part is the framework's, not
 * mine. What is easy to get wrong, and what this class makes impossible to get
 * wrong, is the other half: the *decision* to start. If the screen can reach it
 * from more than one place - the effect, a retry, a saved-state restore - then
 * "fire once per entry" has to be remembered somewhere, because nothing else
 * will remember it.
 *
 * So it is remembered here, in an object with no Android and no Compose in it,
 * which is why it can be pinned by a test at all: the interesting case - five
 * recompositions and one request - is not something a device test reaches, and
 * it is the case that goes wrong.
 *
 * One instance belongs to one entry into the screen. Coming back to the screen
 * builds a new one, so arriving again is a fresh first request, which is what
 * arriving again means.
 */
class ExploreLoadGate {

    private val started = mutableSetOf<String>()

    /**
     * Whether this is the first request for [key].
     *
     * True once for each key. Every asking after that - another recomposition,
     * another effect, a second call in the same frame - is false, so a recompose
     * can never turn into a second request to the source.
     */
    fun shouldStart(key: String): Boolean = started.add(key)

    /** Whether a request for [key] has already been started. */
    fun hasStarted(key: String): Boolean = key in started
}
