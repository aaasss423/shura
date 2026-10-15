package app.shura.manga.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.collections.mutableListOf

/**
 * Loads a chapter's pages as they arrive, instead of all at once.
 *
 * Waiting for every page before showing the first is what makes a reader feel
 * slow: a 40-page chapter over a phone connection spends most of that time
 * downloading art nobody is looking at yet. This hands the reader page 1 as
 * soon as it lands and keeps a few pages ahead of the reading position, so
 * forward scrolling never waits and backward scrolling is already warm.
 *
 * Two rules keep this safe to render:
 *
 *  - **A page never moves.** Results are held by index and published only as a
 *    contiguous prefix, so the reader's page number always names the same
 *    image. Publishing out of order would show one page's picture under
 *    another's number.
 *  - **A failed page does not stall the ones after it.** After its retries are
 *    spent it keeps its slot as [FAILED_PAGE], which the reader renders as a
 *    retryable error. The alternative - dropping it - would shift every later
 *    page by one and silently misnumber the whole chapter.
 */
class ProgressivePageLoader<T : Any>(
    private val pageCount: Int,
    /**
     * Stands in for a page that never arrived.
     *
     * Supplied by the caller rather than built here: the reader's marker is an
     * android.net.Uri, which a plain JVM test cannot construct. Keeping this
     * class generic is what lets its ordering rules be tested directly.
     */
    private val failedPage: T,
    private val fetch: suspend (index: Int) -> T?,
    /** How many pages beyond the current position to keep ready. */
    private val lookAhead: Int = 3,
    /** How many pages may download at once. Bounded so a fast scroll cannot
     *  open one socket per page and starve the one the reader is waiting on. */
    private val parallelism: Int = 4,
    private val retries: Int = 2,
) {
    private val results = arrayOfNulls<Any?>(pageCount)
    private val inFlight = mutableMapOf<Int, Deferred<T?>>()
    private val gate = Semaphore(parallelism)
    private val lock = Any()

    /**
     * Called whenever [available] changes, so the UI can re-read it.
     *
     * The loader holds no Compose state on purpose: its rules - order, retries,
     * cancellation - are worth testing on a plain JVM, and a Compose snapshot
     * list cannot be created there. The reader subscribes instead.
     */
    var onChanged: (() -> Unit)? = null

    /** The pages available so far, as a contiguous run from page 1. */
    val available: List<T> get() = published

    private val published = mutableListOf<T>()

    private var scope: CoroutineScope? = null

    /** Pages known to have failed; each keeps its place in the chapter. */
    fun failedPages(): List<Int> = synchronized(lock) {
        results.indices.filter { results[it] == failedPage }
    }

    /** True once every slot holds either a real page or a recorded failure. */
    val isComplete: Boolean
        get() = synchronized(lock) { results.all { it != null } }

    /**
     * Starts loading and hands back the page list straight away.
     *
     * The list is live: it is filled in as pages land and the caller is told
     * through [onChanged]. Only a chapter with no pages at all waits.
     */
    suspend fun start(scope: CoroutineScope): List<T> {
        this.scope = scope
        ensure(0)
        return available
    }

    /**
     * Guarantees the reader has page [index] and the pages just after it.
     *
     * Safe to call on every page change: pages already loaded are not
     * re-requested, so scrolling back and forth costs nothing.
     */
    fun ensure(index: Int) {
        if (index < 0 || index >= pageCount) return
        // Downloads finish on IO threads while the reader scrolls on the main
        // one, so every shared field is touched under one lock.
        val started = synchronized(lock) {
            val scope = this.scope ?: return
            val last = minOf(pageCount - 1, index + lookAhead)
            (index..last).mapNotNull { i ->
                if (results[i] != null || inFlight.containsKey(i)) return@mapNotNull null
                inFlight[i] = scope.async {
                    val page = gate.withPermit { loadOne(i) }
                    synchronized(lock) {
                        results[i] = page
                        inFlight.remove(i)
                    }
                    // Publishing per page, not per batch: the reader should
                    // gain page 2 the moment it lands, not when page 3 also does.
                    publish()
                    page
                }
                i
            }
        }
        // Started outside the lock on purpose: launching a coroutine under a
        // lock invites it to complete and re-enter before the lock is released.
        started.forEach { }
    }

    private suspend fun loadOne(index: Int): T? = withContext(Dispatchers.IO) {
        var last: T? = null
        repeat(retries + 1) {
            last = fetch(index)
            if (last != null) return@withContext last
        }
        // The slot is kept - see the class note. A retryable placeholder beats
        // a chapter that stops dead on page 3 of 40.
        failedPage
    }

    /**
     * Re-fetches a single page that failed.
     *
     * Clears only that slot, so no page moves and the rest of the chapter is
     * untouched - the whole reason a failure keeps its place instead of
     * being dropped.
     */
    fun retry(index: Int) {
        if (index < 0 || index >= pageCount) return
        synchronized(lock) {
            results[index] = null
            inFlight.remove(index)?.cancel()
        }
        publish()
        ensure(index)
    }

    /** Publishes the longest contiguous run of finished pages. */
    private fun publish() {
        val next = mutableListOf<T>()
        val changed = synchronized(lock) {
            for (slot in results) {
                if (slot == null) break
                @Suppress("UNCHECKED_CAST")
                next.add(slot as T)
            }
            if (next == published) return
            published.clear()
            published.addAll(next)
            true
        }
        if (changed) onChanged?.invoke()
    }

    /** Fetches whatever is still missing, for "download the whole chapter". */
    suspend fun complete() {
        for (i in 0 until pageCount) ensure(i)
        val pending = synchronized(lock) { inFlight.values.toList() }
        pending.awaitAll()
        publish()
    }

    /**
     * Stops all work in progress and empties the reader.
     *
     * Called when the chapter changes or the reader closes. Without it, a
     * chapter the user backed out of keeps downloading into the list the next
     * chapter is about to fill.
     */
    fun cancel() {
        val running = synchronized(lock) {
            val copy = inFlight.values.toList()
            inFlight.clear()
            scope = null
            results.fill(null)
            copy
        }
        // Cancelled outside the lock so a cancelled coroutine's continuation
        // cannot try to take the same lock from another thread.
        running.forEach { it.cancel() }
        synchronized(lock) { published.clear() }
        onChanged?.invoke()
    }

}