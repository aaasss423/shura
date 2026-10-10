package app.shura.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real path, on a real device, with the real artifact Shura Core publishes:
 *
 *   Shura Repository -> SHA-256 -> certificate from the APK file -> manifest/ABI
 *                    -> DexClassLoader -> ProComic instance -> live search
 *                    -> MangaSummary
 *
 * No mocks: the APK is the published one, the signer is read out of it, and the
 * search is a real network request made by the extension's own code. The class
 * is constructed directly because the DEX proves ProComic *is* the Source - there
 * is no createSources() to call.
 *
 * If a step fails, the failure says which one and why; nothing is swallowed.
 */
@RunWith(AndroidJUnit4::class)
class RealExtensionLoadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val anchor = "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

    /** The published repository, packaged into the test APK's assets. */
    /**
     * The published repository, read out of the test APK's own assets.
     *
     * Packaged there by `stagePublishedRepoForTests`, so the tests read the
     * committed bytes wherever they run. A host path would not exist on a device,
     * and the sandbox path did not exist on CI.
     */
    private fun publishDir(name: String): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val out = File(context.cacheDir, "published-repo")
        fun copy(path: String) {
            assets.open(path).use { input ->
                val target = File(out, path)
                target.parentFile?.mkdirs()
                input.copyTo(target.outputStream())
            }
        }
        copy("repo.json")
        copy("index.shura.json")
        assets.list("apk")?.forEach { copy("apk/$it") }
        return File(out, name)
    }

    private fun repoRoot(): File = publishDir("")


    /** Copies the published artifact into the app so the loader uses its own bytes. */
    private fun stageRepository(): File {
        val src = repoRoot()
        val dst = File(context.filesDir, "repo")
        dst.mkdirs()
        File(dst, "apk").mkdirs()
        listOf("repo.json", "index.shura.json").forEach { name ->
            File(src, name).copyTo(File(dst, name), overwrite = true)
        }
        File(src, "apk").listFiles { f: File -> f.name.endsWith(".apk") }!!.forEach {
            it.copyTo(File(dst, "apk/${it.name}"), overwrite = true)
        }
        return dst
    }

    @Test
    fun loadsThePublishedProcomicAndPerformsARealSearch() {
        val repository = stageRepository()
        val client = ShuraRepositoryClient(
            repository,
            ApkSignatureVerifier(context),
            TrustAnchor(anchor),
        )

        // 1. the entry resolves to the published file
        val entry = client.entries().first { it.pkg.endsWith("procomic") }
        assertTrue("published APK is missing", File(repository, "apk/${entry.apk}").isFile)

        // 2-4. digest, certificate read from the file, manifest, ABI
        val verified = client.verify(entry)
        assertEquals("eu.kanade.tachiyomi.extension.ar.procomic", verified.packageName)
        assertEquals(1.6f, verified.abi, 0.001f)

        // 5. stage into private storage, DexClassLoader, resolve the real class
        val loader = ExtensionLoader(context)
        val named = verified.copy(sourceClassName = loader.resolveSourceClass(verified.metadata))
        assertEquals(
            "eu.kanade.tachiyomi.extension.ar.procomic.ProComic",
            named.sourceClassName,
        )

        // 6. a real Source instance
        val loaded = loader.load(named)
        assertNotNull("the extension produced no Source", loaded.source)
        assertTrue(
            "loaded object is ${loaded.source.javaClass.name}, not a Source",
            loaded.source is eu.kanade.tachiyomi.source.CatalogueSource,
        )

        // 7. a real network search made by the extension's own code
        val adapter = ExtensionSourceAdapter(loaded)
        val results = kotlinx.coroutines.runBlocking { adapter.search("one piece", 1) }
        assertTrue("a live search returned nothing", results.isNotEmpty())
        results.forEach {
            assertTrue("result has no title: $it", it.title.isNotBlank())
        }

        // 8. mapped into Shura's own model
        val summary = results.first()
        assertTrue(summary is app.shura.source.MangaSummary)
        assertTrue(summary.title.isNotBlank())
        loaded.close()
    }

    @Test
    fun aWrongAnchorStopsTheLoadBeforeAnyCodeRuns() {
        val repository = stageRepository()
        val client = ShuraRepositoryClient(
            repository,
            ApkSignatureVerifier(context),
            TrustAnchor("f".repeat(64)),
        )
        val entry = client.entries().first { it.pkg.endsWith("procomic") }
        try {
            client.verify(entry)
            org.junit.Assert.fail("a foreign anchor must stop the load")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("anchor"))
        }
    }
}
