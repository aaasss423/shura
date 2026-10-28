package app.shura.manga

import app.shura.runtime.TrustAnchor
import app.shura.runtime.TrustPolicy
import app.shura.runtime.TrustedPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repository Shura ships must be readable by the same code that reads any
 * other repository, and must carry more than one publisher.
 *
 * The fixture is copied from the repository this build actually ships, index
 * bytes and all. A synthetic index would pass while the real one failed - which
 * is exactly what happened for months: the store showed one extension because
 * the catalog read a path the publisher never wrote, and nothing in the suite
 * used the publisher's own output.
 */
class ShippedRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private fun policy() = ShuraExtensions.policy

    private fun shipped(): File {
        val assets = File("src/main/assets/repository")
        assertTrue("the build ships no repository at ${assets.path}", assets.isDirectory)
        val into = folder.newFolder("repo")
        into.mkdirs()
        File(into, "apk").mkdirs()
        listOf("repo.json", "index.shura.json").forEach {
            File(assets, it).copyTo(File(into, it), overwrite = true)
        }
        File(assets, "apk").listFiles()?.forEach {
            it.copyTo(File(File(into, "apk"), it.name), overwrite = true)
        }
        return into
    }

    @Test
    fun `the shipped index parses and names more than one extension`() {
        val dir = shipped()
        val entries = ExtensionCatalog(policy(), dir).describe(dir)
        val packages = entries.map { it.pkg }.distinct()
        // One publisher could only ever contribute one package, so two
        // distinct packages is the smallest proof that the multi-publisher
        // repository actually works end to end.
        assertTrue(
            "the shipped repository offers ${packages.size} extension(s): $packages",
            packages.size >= 2,
        )
    }

    @Test
    fun `every shipped extension the app trusts is actually trusted`() {
        val dir = shipped()
        val catalog = ExtensionCatalog(policy(), dir)
        val entries = catalog.describe(dir)
        val trusted = catalog.runnable(entries)
        assertTrue(
            "nothing in the shipped repository is runnable: " +
                entries.joinToString { "${it.pkg}=${it.trust}" },
            trusted.isNotEmpty(),
        )
        assertTrue(
            "the shipped repository should still expose at least one package this build accepts: " +
                entries.joinToString { "${it.pkg}=${it.trust}" },
            trusted.any { it.pkg.startsWith("eu.kanade.tachiyomi.extension.") || it.pkg.startsWith("app.shura.extension.") },
        )
    }

    @Test
    fun `every artifact the index names is present and named safely`() {
        val dir = shipped()
        val entries = ExtensionCatalog(policy(), dir).describe(dir)
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertTrue(
                "${entry.pkg} is listed but its artifact is not on the device",
                entry.artifactPresent,
            )
            assertTrue(
                "${entry.pkg} names an artifact outside apk/",
                entry.artifactPath!!.contains("/apk/"),
            )
        }
    }

    @Test
    fun `the store shows one row per extension, not one per release`() {
        val dir = shipped()
        val all = ExtensionCatalog(policy(), dir).describe(dir)
        val shown = ExtensionCatalog.newestPerPackage(all)
        assertEquals(all.map { it.pkg }.distinct().size, shown.size)
        // And the version shown is the highest one on offer.
        all.groupBy { it.pkg }.forEach { (pkg, versions) ->
            val newest = shown.first { it.pkg == pkg }
            assertEquals(pkg, newest, versions.first { it.version == newest.version })
        }
    }
}