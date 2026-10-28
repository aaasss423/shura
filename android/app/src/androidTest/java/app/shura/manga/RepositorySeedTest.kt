package app.shura.manga

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositorySeedTest {

    @Test
    fun bundledRepositorySeedsOnFirstUseWithoutReplacingItOnSecondUse() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File.createTempFile("repository-seed-test", "", base.cacheDir)
            .apply {
                delete()
                mkdirs()
            }
        val isolatedFiles = File(testRoot, "files").apply { mkdirs() }
        val isolatedCache = File(testRoot, "cache").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = isolatedFiles
            override fun getCacheDir(): File = isolatedCache
        }

        try {
            assertTrue("this build must bundle its repository", RepositorySeed.isPresent(context))
            assertTrue("first seed must install the bundled repository", RepositorySeed.seed(context))
            val installed = ShuraRepository.directory(context)
            assertTrue(File(installed, "index.shura.json").isFile)
            assertTrue(File(installed, "repo.json").isFile)
            assertFalse("a second seed must leave the installed repository alone", RepositorySeed.seed(context))
        } finally {
            testRoot.deleteRecursively()
        }
    }
}
