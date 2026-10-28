package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Exercises the real Shura repository files: no fixtures, no mocks.
 *
 * Everything asserted here runs against `repo/` as Shura Core published it, so a
 * regression in the contract or in the verification order fails these tests
 * rather than surfacing as an unexplained failure to load an extension on a device.
 */
class ShuraRepositoryClientTest {

    private lateinit var root: File
    private lateinit var client: ShuraRepositoryClient

    @Before
    fun setUp() {
        root = File(System.getProperty("shura.repo.dir") ?: "../../repo")
        assertTrue("no published repository at $root", File(root, "repo.json").isFile)
        client = ShuraRepositoryClient(root, null, TrustPolicy(
            TrustAnchor("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"),
            listOf(TrustedPublisher("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                listOf("eu.kanade.tachiyomi.extension.")))))
    }

    @Test
    fun trustAnchorIsTheCertificateTheApksAreSignedWith() {
        val anchor = client.trustAnchor()
        assertTrue("anchor must be a 64-hex digest", anchor.matches(Regex("[0-9a-f]{64}")))
        // Cross-check against the artifact itself: the published anchor must be
        // the digest of a certificate that is actually in the APK. APKs signed
        // with the v2/v3 scheme do not carry a JAR v1 PKCS#7 block, so the
        // authoritative certificate digest comes from the Android SDK signer tool.
        val apk = File(root, "apk").listFiles()!!.sortedBy { it.name }.first()
        val certificate = apksignerCertificateDigest(apk)
        assertEquals(anchor, certificate)
    }

    @Test
    fun everyEntryResolvesToAPresentFileWithItsPublishedDigest() {
        val entries = client.entries()
        assertTrue("repository publishes no packages", entries.isNotEmpty())
        for (entry in entries) {
            val apk = File(root, "apk").resolve(entry.apk)
            assertTrue("${entry.apk} is published but absent", apk.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            assertEquals(entry.identity, entry.artifactSha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun everyEntryCarriesTheRepositoryAnchor() {
        val anchor = client.trustAnchor()
        for (entry in client.entries()) {
            assertEquals(entry.identity, anchor, entry.signingCertificateSha256)
        }
    }

    @Test
    fun verifyRefusesBeforeAnyPinComparisonWithoutASignatureReader() {
        // Ordering matters: the file's own signer is checked first, so a bogus
        // published pin never even gets to be compared off-device. The
        // foreign-pin case is covered on-device in ApkSignatureVerificationTest.
        val entry = client.entries().first()
            .copy(signingCertificateSha256 = "0".repeat(64))
        try {
            client.verify(entry)
            fail("a foreign pin must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("signature verifier"))
        }
    }

    @Test
    fun verifyRefusesAnArtifactWhoseDigestDoesNotMatch() {
        val entry = client.entries().first()
            .copy(artifactSha256 = "1".repeat(64))
        try {
            client.verify(entry)
            fail("a digest mismatch must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("SHA-256 mismatch"))
        }
    }

    @Test
    fun verifyRefusesAMissingArtifact() {
        val entry = client.entries().first().copy(apk = "not-published.apk")
        try {
            client.verify(entry)
            fail("an absent APK must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("not present"))
        }
    }

    @Test
    fun repoJsonWithoutAFingerprintIsRejected() {
        try {
            RepositoryMeta.parse("""{"meta":{"name":"Shura"}}""")
            fail("a missing fingerprint must be rejected")
        } catch (e: LoadFailure.Manifest) {
            assertTrue(e.message!!.contains("64-hex"))
        }
    }

    @Test
    fun aWrongFingerprintShapeIsRejected() {
        try {
            RepositoryMeta.parse("""{"meta":{"signingKeyFingerprint":"abc"}}""")
            fail("a malformed fingerprint must be rejected")
        } catch (e: LoadFailure.Manifest) {
            assertNotNull(e.message)
        }
    }

    private fun apksignerCertificateDigest(apk: File): String {
        val tool = listOf(
            "/opt/android-sdk/build-tools/35.0.0/apksigner",
            "/opt/android-sdk/build-tools/35.0.0/apksigner.bat",
            "apksigner",
        ).firstOrNull { path ->
            val file = File(path)
            file.exists() || path == "apksigner"
        } ?: error("apksigner not found")

        val command = if (tool.endsWith(".bat") || tool == "apksigner") {
            listOfNotNull(tool).ifEmpty { listOf("apksigner") }
        } else {
            listOf(tool)
        }
        val process = ProcessBuilder(command + listOf("verify", "--print-certs", "--verbose", apk.absolutePath))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val match = Regex("Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})").find(output)
            ?: Regex("certificate SHA-256 digest: ([0-9a-fA-F]{64})").find(output)
        check(process.waitFor() == 0 || match != null) { output }
        requireNotNull(match) { "no signer digest found in apksigner output for ${apk.name}\n$output" }
        return match.groupValues[1].lowercase()
    }
}