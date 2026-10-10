package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The repository's trust anchor must be independent of the repository.
 *
 * The defect this pins: the client used to read `signingKeyFingerprint` from the
 * downloaded `repo.json` and compare each entry's `signing_certificate_sha256`
 * against it. Both values arrive from the same host as the APK, so a host that
 * can serve a file can serve an APK signed with its own key *and* a repo.json
 * naming that key. The comparison is then self-consistent and proves nothing --
 * worse, `verify()` never read the certificate out of the APK at all.
 *
 * The fix is two-part and both halves are asserted here:
 *  1. the anchor is configured out of band, never taken from the download, and a
 *     repository whose anchor differs is refused outright;
 *  2. the certificate is read from the APK itself and must equal the anchor.
 */
class TrustAnchorIndependenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val genuine = "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"
    private val attacker = "a".repeat(64)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Builds a self-consistent repository hosted by the attacker. */
    private fun forgeRepository(dir: File, anchor: String): File {
        dir.mkdirs()
        File(dir, "apk").mkdirs()
        val payload = "a perfectly innocent looking apk".toByteArray()
        val apk = File(dir, "apk/evil.apk").apply { writeBytes(payload) }
        File(dir, "repo.json").writeText(
            """{"meta":{"name":"Shura","website":"","signingKeyFingerprint":"$anchor"}}"""
        )
        File(dir, "index.shura.json").writeText(
            """{"repo":"Shura","packages":[{"pkg":"org.evil.ext","version":"v1.0",
               "apk":"evil.apk","identity":"org.evil.ext|v1.0",
               "artifact_sha256":"${sha256(apk)}",
               "signing_certificate_sha256":"$anchor"}]}"""
        )
        return dir
    }

    @Test
    fun aRepositoryPinnedToAnUntrustedAnchorIsRefused() {
        val dir = forgeRepository(folder.root, attacker)
        // The operator pins the genuine anchor; the downloaded repo names another.
        val client = ShuraRepositoryClient(dir, null, TrustAnchor(genuine))
        val failure = try {
            client.entries()
            null
        } catch (e: LoadFailure) {
            e
        }
        assertTrue(
            "a repository with an untrusted anchor must be refused, got $failure",
            failure is LoadFailure.NotVerified,
        )
        assertTrue(failure!!.message!!.contains("anchor"))
    }

    @Test
    fun theAnchorIsNeverReadFromTheRepositoryItCertifies() {
        val dir = forgeRepository(folder.root, attacker)
        // Even the accessor itself must not hand back the downloaded value.
        val client = ShuraRepositoryClient(dir, null, TrustAnchor(genuine))
        assertEquals("the anchor must be the configured one", genuine, client.trustAnchor())
    }

    @Test
    fun theGenuineRepositoryIsAcceptedByItsAnchor() {
        val repo = File(System.getProperty("shura.repo.dir") ?: "../../repo")
        assertTrue("no published repository at $repo", File(repo, "repo.json").isFile)
        val client = ShuraRepositoryClient(repo, null, TrustAnchor(genuine))
        // It passes the anchor gate and its entries are readable: nothing about
        // the repository itself is rejected. The APK's own signature is then
        // required separately, which cannot run off-device.
        assertEquals(genuine, client.trustAnchor())
        assertTrue(client.entries().any { it.pkg.endsWith("procomic") })
    }

    @Test
    fun anUnconfiguredAnchorRefusesRatherThanTrustingTheDownload() {
        val repo = File(System.getProperty("shura.repo.dir") ?: "../../repo")
        assertTrue("no published repository at $repo", File(repo, "repo.json").isFile)
        val client = ShuraRepositoryClient(repo, null, TrustAnchor(null))
        val failure = try {
            client.entries()
            null
        } catch (e: LoadFailure) {
            e
        }
        assertTrue(
            "with no independent anchor configured, nothing may be trusted; got $failure",
            failure is LoadFailure.NotVerified,
        )
    }
}