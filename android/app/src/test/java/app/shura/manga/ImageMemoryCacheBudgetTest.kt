package app.shura.manga

import app.shura.manga.ui.image.imageMemoryCacheBudgetKb
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMemoryCacheBudgetTest {

    @Test
    fun cacheUsesAnEighthOfAConstrainedHeap() {
        assertEquals(8 * 1024, imageMemoryCacheBudgetKb(64L * 1024 * 1024))
    }

    @Test
    fun cacheHasALowUpperBoundOnLargeHeaps() {
        assertEquals(24 * 1024, imageMemoryCacheBudgetKb(1024L * 1024 * 1024))
    }

    @Test
    fun cacheKeepsAMinimumUsefulBudget() {
        assertEquals(2 * 1024, imageMemoryCacheBudgetKb(8L * 1024 * 1024))
    }
}
