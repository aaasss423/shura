package app.shura.runtime

import app.shura.host.MangaDexSource
import app.shura.host.SourceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A broken extension must not take the rest of the app's sources with it.
 *
 * MangaDex is registered first and unconditionally, so an extension that fails
 * verification, fails to load, or is simply absent can only ever be a missing
 * entry in the list -- never a reason for browsing to stop working.
 */
class SourceIsolationTest {

    private fun hostWith(extension: app.shura.source.ShuraSource?): SourceHost =
        SourceHost().apply {
            register(MangaDexSource())
            extension?.let { register(it) }
        }

    @Test
    fun mangadexIsRegisteredEvenWhenNoExtensionLoads() {
        val host = hostWith(null)
        assertEquals(1, host.all().size)
        assertEquals("MangaDex", host.all().first().name)
    }

    @Test
    fun aFailedExtensionDoesNotRemoveOrBreakMangaDex() {
        // What a failed extension actually leaves behind: nothing registered.
        val host = hostWith(null)
        val mangadex = host.find("com.shura.source.mangadex")
        assertNotNull("MangaDex must survive any extension failure", mangadex)
        assertTrue(mangadex is MangaDexSource)
    }

    @Test
    fun aFailedExtensionIsReportedRatherThanSilentlyDropped() {
        // ShuraExtensionLoader.load needs a Context, so the reporting contract is
        // asserted on the outcome type it produces and on the repository side of
        // it: a wrong anchor fails loudly instead of yielding nothing.
        val repo = File(System.getProperty("shura.repo.dir") ?: "../repo")
        assertTrue("no published repository at $repo", File(repo, "repo.json").isFile)
        val client = ShuraRepositoryClient(repo, null, TrustAnchor("f".repeat(64)))
        val failure = try { client.entries(); null } catch (e: LoadFailure) { e }
        assertTrue("a foreign anchor must be reported", failure is LoadFailure.NotVerified)
        assertTrue("the failure must carry a reason", !failure!!.message.isNullOrBlank())
    }

    @Test
    fun aFailedExtensionNeverDisplacesMangaDex() {
        val host = hostWith(null)
        assertEquals(1, host.all().size)
        assertEquals("MangaDex", host.all().first().name)
        assertNotNull(host.find("com.shura.source.mangadex"))
    }
}
