package app.shura.manga

import app.shura.manga.reader.ProgressivePageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Progressive loading must make the reader faster without ever lying to it.
 *
 * Two promises are easy to break while "optimising" this: that the first page
 * appears before the last one downloads, and that a page number always names
 * the same picture. Losing the second is the worse bug - it looks like the
 * reader works and silently misnumbers the chapter - so both are pinned here.
 */
class ProgressiveLoadingTest {

    // Pages are plain strings here; the real loader is generic so its rules can
    // be tested without android.net.Uri, which the JVM cannot construct.
    private fun uri(index: Int) = "file:///chapter/page-$index.img"

    private val failed = "file:///chapter/page-unavailable"

    @Test
    fun `the first page is readable before the whole chapter has downloaded`() = runBlocking {
        val slowTail = CompletableDeferred<String?>()
        var requested = 0
        val loader = ProgressivePageLoader(
            pageCount = 30,
            failedPage = failed,
            fetch = { index ->
                requested++
                // Every page after the first hangs until the test says so.
                if (index > 0) slowTail.await() else uri(0)
            },
            lookAhead = 0,
        )
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        scope.launch { loader.start(scope) }

        var guard = 0
        while (loader.available.isEmpty() && guard++ < 500) delay(1)
        // The reader has something to show while 29 pages are still pending.
        assertEquals(listOf(uri(0)), loader.available.toList())
        assertFalse(loader.isComplete)

        slowTail.complete(uri(1))
        slowTail.complete(uri(2))
        assertTrue(requested >= 1)
    }

    @Test
    fun `a page never changes its number`() = runBlocking {
        val loader = ProgressivePageLoader(
            pageCount = 6,
            failedPage = failed,
            fetch = { uri(it) },
            lookAhead = 5,
        )
        loader.start(this)
        var guard = 0
        while (!loader.isComplete && guard++ < 500) delay(1)

        // Index N of the chapter is the same URI before and after everything
        // loaded. This is the rule a fast scroll depends on.
        loader.available.forEachIndexed { index, page ->
            assertEquals("page ${index + 1} shows the wrong image", uri(index), page)
        }
    }

    @Test
    fun `a page that keeps failing keeps its place instead of stopping the chapter`() = runBlocking {
        val loader = ProgressivePageLoader(
            pageCount = 5,
            failedPage = failed,
            fetch = { index ->
                // Page 2 never arrives; every other page is fine.
                if (index == 1) null else uri(index)
            },
            lookAhead = 4,
            retries = 1,
        )
        loader.start(this)
        var guard = 0
        while (!loader.isComplete && guard++ < 500) delay(1)

        // Dropping page 2 would shift 3, 4 and 5 down a number each. Keeping it
        // means the rest of the chapter is still correct.
        assertEquals(listOf(1), loader.failedPages())
        assertEquals(failed, loader.available[1])
        assertEquals(uri(4), loader.available[4])
    }

    @Test
    fun `a failed page can be retried in place`() = runBlocking {
        val broken = AtomicInteger(0)
        val loader = ProgressivePageLoader(
            pageCount = 3,
            failedPage = failed,
            fetch = { index ->
                if (index == 0 && broken.get() == 0) null else uri(index)
            },
            lookAhead = 2,
            retries = 0,
        )
        loader.start(this)
        var guard = 0
        while (!loader.isComplete && guard++ < 500) delay(1)
        assertEquals(failed, loader.available[0])

        // The network came back. Retrying page 1 must not disturb pages 2-3.
        broken.incrementAndGet()
        loader.retry(0)
        guard = 0
        while (!loader.isComplete && guard++ < 500) delay(1)
        assertEquals(listOf(uri(0), uri(1), uri(2)), loader.available.toList())
    }

    @Test
    fun `downloading stops when the chapter is left`() = runBlocking {
        val started = AtomicInteger(0)
        val loader = ProgressivePageLoader(
            pageCount = 40,
            failedPage = failed,
            fetch = { index -> started.incrementAndGet(); uri(index) },
            lookAhead = 3,
        )
        loader.start(this)
        // Leaving the reader must not leave 40 downloads running behind it.
        loader.cancel()
        val atCancel = started.get()
        delay(50)
        assertTrue("downloads continued after leaving", started.get() - atCancel <= 4)
        assertTrue(loader.available.isEmpty())
    }
}