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
        client = ShuraRepositoryClient(root, null, TrustAnchor("b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"))
    }

    @Test
    fun trustAnchorIsTheCertificateTheApksAreSignedWith() {
        val anchor = client.trustAnchor()
        assertTrue("anchor must be a 64-hex digest", anchor.matches(Regex("[0-9a-f]{64}")))
        // Cross-check against the artifact itself: the published anchor must be
        // the digest of a certificate that is actually in the APK.
        val apk = File(root, "apk").listFiles()!!.sortedBy { it.name }.first()
        ZipFile(apk).use { z ->
            val rsa = z.entries().asSequence().first { it.name.startsWith("META-INF/") && it.name.endsWith(".RSA") }
            val der = certificateFromPkcs7(z.getInputStream(rsa).readBytes())
            assertEquals(anchor, MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) })
        }
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
        val entry = client.entries().first { it.pkg.endsWith("procomic") }
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
        val entry = client.entries().first { it.pkg.endsWith("procomic") }
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

    /** Pulls the X.509 out of the JAR/v1 PKCS#7 block, header included. */
    private fun certificateFromPkcs7(blob: ByteArray): ByteArray {
        fun elements(buf: ByteArray): List<Triple<Int, ByteArray, ByteArray>> {
            val out = mutableListOf<Triple<Int, ByteArray, ByteArray>>()
            var i = 0
            while (i < buf.size) {
                val tag = buf[i].toInt() and 0xFF
                var j = i + 1
                var n = buf[j].toInt() and 0xFF
                j++
                if (n and 0x80 != 0) {
                    val k = n and 0x7F
                    n = 0
                    for (x in 0 until k) n = (n shl 8) or (buf[j + x].toInt() and 0xFF)
                    j += k
                }
                out.add(Triple(tag, buf.copyOfRange(i, j + n), buf.copyOfRange(j, j + n)))
                i = j + n
            }
            return out
        }
        val contentInfo = elements(blob).first()
        val wrapped = elements(contentInfo.third).first { it.first == 0xA0 }
        val signedData = elements(wrapped.third).first()
        for ((tag, _, payload) in elements(signedData.third)) {
            if (tag == 0xA0 || tag == 0xA1) {
                for ((ct, cert, _) in elements(payload)) {
                    if (ct == 0x30) return cert
                }
            }
        }
        fail("no certificate in the PKCS#7 block")
        error("unreachable")
    }
}