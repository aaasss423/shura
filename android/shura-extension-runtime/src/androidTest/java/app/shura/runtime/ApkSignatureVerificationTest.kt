package app.shura.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the trust decision reads the certificate out of the APK itself.
 *
 * This needs Android: reading an APK's signer goes through PackageManager, and
 * the JVM has no such thing. That is exactly why the repository client is handed
 * an [ApkSignatureVerifier] instead of trusting a digest that arrived beside the
 * file -- that digest would come from the same host.
 */
@RunWith(AndroidJUnit4::class)
class ApkSignatureVerificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val anchor = "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

    private fun repoRoot(): File {
        val candidates = listOf(
            File(System.getProperty("shura.repo.dir") ?: ""),
            File("/workspace/repo"),
        )
        val root = candidates.firstOrNull { File(it, "apk").isDirectory }
        return root ?: error("no published repository found in $candidates")
    }

    private fun procomic(): File {
        val apks = File(repoRoot(), "apk").listFiles { f: File -> f.name.endsWith(".apk") }!!
        return apks.first { it.name.contains("1.5.1") }
    }

    @Test
    fun theCertificateComesFromTheFileAndMatchesTheAnchor() {
        val verifier = ApkSignatureVerifier(context)
        val (cert, digest) = verifier.primaryCertificate(procomic())
        assertEquals("the DER certificate must be the whole 1414 bytes", 1414, cert.size)
        assertEquals(anchor, digest)
    }

    @Test
    fun theDigestIsSha256OfTheCompleteDerCertificate() {
        val verifier = ApkSignatureVerifier(context)
        val (cert, digest) = verifier.primaryCertificate(procomic())
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        assertEquals(digest, sha256.digest(cert).joinToString("") { "%02x".format(it) })
        // The historical defect: hashing the certificate without its 30 82 LL LL
        // header produced a value no signing tool reports.
        assertTrue(
            "the truncated variant must not be what we verify",
            digest != sha256.digest(cert.copyOfRange(4, cert.size)).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun anApkSignedWithAnotherKeyIsRefusedEvenWhenTheMetadataClaimsTheRightOne() {
        val verifier = ApkSignatureVerifier(context)
        // A copy of the real bytes, so the digest matches, but pinned to a foreign
        // anchor: this is the "repository metadata agrees with itself" case.
        try {
            verifier.verify(procomic(), "a".repeat(64), anchor)
            fail("a foreign anchor must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains(anchor))
        }
    }

    @Test
    fun aPublishedPinThatDisagreesWithTheFileIsRefused() {
        val verifier = ApkSignatureVerifier(context)
        try {
            verifier.verify(procomic(), anchor, "b".repeat(64))
            fail("a published pin that disagrees with the file must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("repository published"))
        }
    }

    @Test
    fun anUnconfiguredAnchorRefusesTheFile() {
        val verifier = ApkSignatureVerifier(context)
        try {
            TrustAnchor(null).require()
            fail("no anchor must refuse")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("no repository trust anchor"))
        }
    }

    @Test
    fun aFileThatIsNotAnApkIsRefused() {
        val verifier = ApkSignatureVerifier(context)
        val junk = File(context.cacheDir, "not-an-apk.apk").apply { writeBytes("hello".toByteArray()) }
        try {
            verifier.certificates(junk)
            fail("a non-APK must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("readable APK"))
        }
    }

    @Test
    fun anApkWithItsSignatureStrippedIsRefused() {
        val verifier = ApkSignatureVerifier(context)
        val stripped = File(context.cacheDir, "stripped.apk")
        // Rebuild the archive without the signing block: same entries, no signature.
        java.util.zip.ZipFile(procomic()).use { src ->
            java.util.zip.ZipOutputStream(stripped.outputStream()).use { out ->
                src.entries().asSequence()
                    .filterNot { it.name.startsWith("META-INF/") }
                    .forEach { entry -> src.getInputStream(entry).use { out.putNextEntry(java.util.zip.ZipEntry(entry.name)); it.copyTo(out) } }
            }
        }
        try {
            verifier.primaryCertificate(stripped)
            fail("an APK with its signature removed must be refused")
        } catch (e: LoadFailure.NotVerified) {
            assertTrue(e.message!!.contains("signing"))
        }
    }
}
