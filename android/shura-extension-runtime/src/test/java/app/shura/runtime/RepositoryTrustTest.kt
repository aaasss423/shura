package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The trust path, actually run.
 *
 * `ShuraRepositoryClient.verify()` was where every check lived - digest,
 * signature, package identity, ABI - and it had never been executed by a test,
 * because the client took a concrete `ApkSignatureVerifier` whose certificates
 * come from PackageManager and so need a `Context`. The only assertion anyone
 * could reach was "no verifier was supplied, refusing".
 *
 * The APK signature itself is verified by ApkSignatureVerificationTest on
 * Android. This JVM test injects only the certificate-pin comparison so it can
 * exercise the repository digest, package, manifest and ABI checks against the
 * exact APK bundled with the app.
 */
class RepositoryTrustTest {

    private val repository = File(System.getProperty("shura.repo.dir") ?: "src/main/assets/repository")
    private val packageName = "eu.kanade.tachiyomi.extension.ar.anyonemanga"
    private val shippedApk: File = File(repository, "apk")
        .listFiles()?.firstOrNull { it.name.contains("anyonemanga") && it.extension == "apk" }
        ?: error("the bundled repository has no APK for $packageName")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "shura-trust-$prefix-${System.nanoTime()}").apply { mkdirs() }

    /** The APK signature is tested on-device; this checks the client's pin comparisons. */
    private class PinnedCertificate(private val expected: String) : ApkSignatureCheck {
        override fun verify(apk: File, anchor: String, publishedPin: String): String {
            if (!expected.equals(anchor, ignoreCase = true)) {
                throw LoadFailure.NotVerified(
                    "${apk.name} is signed by $expected but this build trusts $anchor")
            }
            if (!expected.equals(publishedPin, ignoreCase = true)) {
                throw LoadFailure.NotVerified(
                    "${apk.name} is signed by $expected but the repository published $publishedPin")
            }
            return expected
        }
    }

    private fun certificateOf(): String =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    /** The current repository signer is trusted for the Mihon extension family. */
    private fun shippedPolicy(extra: List<TrustedPublisher> = emptyList()): TrustPolicy = TrustPolicy(
        TrustAnchor(certificateOf()),
        listOf(TrustedPublisher(
            certificateOf(),
            listOf("eu.kanade.tachiyomi.extension."),
            "shura-repo")) + extra,
    )

    /**
     * A repository whose entry describes [apk], with its digest computed for
     * real rather than pasted.
     */
    private fun repositoryFor(apk: File, dir: File, pin: String, digestOverride: String? = null): File {
        File(dir, "apk").mkdirs()
        val target = File(dir, "apk/${apk.name}")
        apk.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
        val metadata = ApkManifestReader().read(apk)
        val version = metadata.versionName
        val abi = metadata.metaData["tachiyomix.extensionLib"]
            ?: error("${apk.name} has no extension ABI metadata")
        File(dir, "repo.json").writeText(
            org.json.JSONObject().put("meta", org.json.JSONObject()
                .put("name", "Shura").put("signingKeyFingerprint", pin)).toString())
        val entry = org.json.JSONObject()
            .put("pkg", metadata.packageName)
            .put("version", version)
            .put("lang", "ar")
            .put("identity", "${metadata.packageName}|$version")
            .put("apk", apk.name)
            .put("artifact_sha256", digestOverride ?: sha256(File(dir, "apk/${apk.name}").readBytes()))
            .put("signing_certificate_sha256", pin)
            .put("index_version_code", metadata.versionCode)
            .put("index_extension_lib", abi)
        File(dir, "index.shura.json").writeText(
            org.json.JSONObject().put("packages", org.json.JSONArray().put(entry)).toString())
        return dir
    }

    // -- the current bundled artifact and repository checks -----------------

    @Test
    fun `a bundled extension passes the repository trust path when its pin is verified`() {
        val apk = shippedApk
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("good"), pin)

        val client = ShuraRepositoryClient(
            dir, PinnedCertificate(pin), shippedPolicy())
        val entry = client.entries().single()
        val verified = client.verify(entry)

        // The staged copy is the one that was verified: a byte-for-byte match of
        // what the index published, not the file that happened to be there.
        assertEquals(entry.artifactSha256, sha256(verified.apk.readBytes()))
        assertTrue(verified.apk.isFile)
        assertEquals(packageName, verified.packageName)
        // The ABI came out of the artifact's manifest, not from the index.
        assertTrue("abi was not read from the APK", verified.abi > 0f)
    }

    // -- refusals -----------------------------------------------------------

    @Test
    fun `an artifact that does not match its published digest is refused`() {
        val apk = shippedApk
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("tampered"), pin)

        // The bytes are then altered; the signature still names the real key, so
        // only the digest can catch this - which is why it is checked first.
        val staged = File(dir, "apk/${apk.name}")
        staged.writeBytes(staged.readBytes() + byteArrayOf(0))

        val client = ShuraRepositoryClient(dir, PinnedCertificate(pin), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a modified artifact was accepted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun `an artifact signed by a key the build does not trust is refused`() {
        val apk = shippedApk
        // The repository declares and publishes a key of its own; the build
        // still trusts the pinned one. Agreeing with itself is not enough.
        val other = "d".repeat(64)
        val dir = repositoryFor(apk, tempDir("otherkey"), other)

        val client = ShuraRepositoryClient(dir, PinnedCertificate(other), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a repository pinning its own key was trusted", failure != null)
        // The repository agrees with itself, and is still refused: the anchor is
        // the only thing that decides.
        assertTrue(failure!!.message!!, failure.message!!.contains("anchor mismatch"))
    }

    @Test
    fun `an artifact whose published pin disagrees with the anchor is refused`() {
        val apk = shippedApk
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("mismatch"), pin)

        // The entry claims a different signer than the repository declares.
        val index = File(dir, "index.shura.json")
        val text = index.readText().replace(pin, "e".repeat(64))
        index.writeText(text)

        val client = ShuraRepositoryClient(dir, PinnedCertificate(pin), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a repository that lies about its signer was trusted", failure != null)
    }

    @Test
    fun `no verifier at all is refused rather than trusted on metadata`() {
        val apk = shippedApk
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("noverify"), pin)

        val client = ShuraRepositoryClient(dir, null, shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("metadata alone was trusted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no APK signature verifier"))
    }
}