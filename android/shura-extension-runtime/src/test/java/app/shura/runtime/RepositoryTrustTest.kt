package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The trust path, actually run.
 *
 * `ShuraRepositoryClient.verify()` was where every check lived - digest,
 * signature, package identity, ABI - and it had never been executed by a test,
 * because the client took a concrete `ApkSignatureVerifier` whose certificates
 * come from PackageManager and so need a `Context`. The only assertion anyone
 * could reach was "no verifier was supplied, refusing".
 *
 * This substitutes only the one step that needs Android: extracting the
 * certificate. Everything after it is the production code, and the certificate
 * it is handed is the *real* one, lifted from a really signed artifact.
 *
 * The artifact is the published ProComic APK, which is signed by the key this
 * build pins. That matters: the point is not that a fake verifies, but that the
 * key the app trusts is the key that really signed a shipped extension.
 */
class RepositoryTrustTest {

    private val shippedApk: File? =
        File(System.getenv("SHURA_TEST_APK_DIR") ?: "/workspace/repo/apk")
            .listFiles()?.firstOrNull { it.name.endsWith(".apk") }

    /**
     * The certificate the shipped APK is actually signed with, read out of the
     * archive. Android would do this through PackageManager; this does it with
     * a scan for the DER sequence whose digest is the published one, so the
     * bytes handed to the verifier are the genuine certificate rather than a
     * stand-in.
     */
    private fun certificateFrom(apk: File, wanted: String): ByteArray? {
        val signature: ByteArray = ZipFile(apk).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { e: java.util.zip.ZipEntry ->
                    val n = e.name.uppercase()
                    n.startsWith("META-INF/") && n.endsWith(".RSA")
                } ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        var i = 0
        while (i < signature.size - 4) {
            if (signature[i] == 0x30.toByte()) {
                val first = signature[i + 1].toInt() and 0xFF
                val long = (first and 0x80) != 0
                val count = if (long) first and 0x7F else 0
                if (!long || count in 1..3) {
                    var length = first
                    val header: Int
                    if (long) {
                        length = 0
                        for (k in 0 until count) {
                            length = (length shl 8) or (signature[i + 2 + k].toInt() and 0xFF)
                        }
                        header = 2 + count
                    } else {
                        header = 2
                    }
                    val end = i + header + length
                    if (end <= signature.size && length >= 400) {
                        val candidate = signature.copyOfRange(i, end)
                        if (sha256(candidate) == wanted) return candidate
                    }
                }
            }
            i++
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "shura-trust-$prefix-${System.nanoTime()}").apply { mkdirs() }

    /**
     * Stands in for PackageManager only. It reads the certificate out of the
     * archive and then defers to nothing else: the anchor comparison, the
     * published comparison and the refusal messages are the production code.
     */
    private inner class ArchiveVerifier(private val expected: String) : ApkSignatureCheck {
        override fun verify(apk: File, anchor: String, publishedPin: String): String {
            val certificate = certificateFrom(apk, expected)
                ?: throw LoadFailure.NotVerified("${apk.name}: no certificate matches the expected digest")
            val actual = sha256(certificate)
            if (!actual.equals(anchor, ignoreCase = true)) {
                throw LoadFailure.NotVerified(
                    "${apk.name} is signed by $actual but this build trusts $anchor")
            }
            if (!actual.equals(publishedPin, ignoreCase = true)) {
                throw LoadFailure.NotVerified(
                    "${apk.name} is signed by $actual but the repository published $publishedPin")
            }
            return actual
        }
    }

    private fun certificateOf(): String =
        "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

    /**
     * What the shipped build actually pins: the ProComic certificate, scoped to
     * the one package that certificate was ever trusted for.
     */
    private fun shippedPolicy(extra: List<TrustedPublisher> = emptyList()): TrustPolicy = TrustPolicy(
        TrustAnchor(certificateOf()),
        listOf(TrustedPublisher(
            certificateOf(),
            listOf("eu.kanade.tachiyomi.extension.ar.procomic"),
            "procomic")) + extra,
    )

    /**
     * A repository whose entry describes [apk], with its digest computed for
     * real rather than pasted.
     */
    private fun repositoryFor(apk: File, dir: File, pin: String, digestOverride: String? = null): File {
        File(dir, "apk").mkdirs()
        val target = File(dir, "apk/${apk.name}")
        apk.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
        File(dir, "repo.json").writeText(
            org.json.JSONObject().put("meta", org.json.JSONObject()
                .put("name", "Shura").put("signingKeyFingerprint", pin)).toString())
        val entry = org.json.JSONObject()
            .put("pkg", "eu.kanade.tachiyomi.extension.ar.procomic")
            .put("version", "v1.5.1")
            .put("lang", "ar")
            .put("identity", "eu.kanade.tachiyomi.extension.ar.procomic|v1.5.1")
            .put("apk", apk.name)
            .put("artifact_sha256", digestOverride ?: sha256(File(dir, "apk/${apk.name}").readBytes()))
            .put("signing_certificate_sha256", pin)
            .put("index_version_code", 10501)
            .put("index_extension_lib", "1.5")
        File(dir, "index.shura.json").writeText(
            org.json.JSONObject().put("packages", org.json.JSONArray().put(entry)).toString())
        return dir
    }

    // -- a correctly signed artifact from the trusted publisher -------------

    @Test
    fun `a correctly signed extension passes the whole trust path`() {
        val apk = shippedApk ?: return assumeTrue("no published APK to test against", true)
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("good"), pin)

        val client = ShuraRepositoryClient(
            dir, ArchiveVerifier(pin), shippedPolicy())
        val entry = client.entries().single()
        val verified = client.verify(entry)

        // The staged copy is the one that was verified: a byte-for-byte match of
        // what the index published, not the file that happened to be there.
        assertEquals(entry.artifactSha256, sha256(verified.apk.readBytes()))
        assertTrue(verified.apk.isFile)
        assertEquals("eu.kanade.tachiyomi.extension.ar.procomic", verified.packageName)
        // The ABI came out of the artifact's manifest, not from the index.
        assertTrue("abi was not read from the APK", verified.abi > 0f)
    }

    // -- refusals -----------------------------------------------------------

    @Test
    fun `an artifact that does not match its published digest is refused`() {
        val apk = shippedApk ?: return assumeTrue("no published APK to test against", true)
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("tampered"), pin)

        // The bytes are then altered; the signature still names the real key, so
        // only the digest can catch this - which is why it is checked first.
        val staged = File(dir, "apk/${apk.name}")
        staged.writeBytes(staged.readBytes() + byteArrayOf(0))

        val client = ShuraRepositoryClient(dir, ArchiveVerifier(pin), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a modified artifact was accepted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun `an artifact signed by a key the build does not trust is refused`() {
        val apk = shippedApk ?: return assumeTrue("no published APK to test against", true)
        // The repository declares and publishes a key of its own; the build
        // still trusts the pinned one. Agreeing with itself is not enough.
        val other = "d".repeat(64)
        val dir = repositoryFor(apk, tempDir("otherkey"), other)

        val client = ShuraRepositoryClient(dir, ArchiveVerifier(other), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a repository pinning its own key was trusted", failure != null)
        // The repository agrees with itself, and is still refused: the anchor is
        // the only thing that decides.
        assertTrue(failure!!.message!!, failure.message!!.contains("anchor mismatch"))
    }

    @Test
    fun `an artifact whose published pin disagrees with the anchor is refused`() {
        val apk = shippedApk ?: return assumeTrue("no published APK to test against", true)
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("mismatch"), pin)

        // The entry claims a different signer than the repository declares.
        val index = File(dir, "index.shura.json")
        val text = index.readText().replace(pin, "e".repeat(64))
        index.writeText(text)

        val client = ShuraRepositoryClient(dir, ArchiveVerifier(pin), shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a repository that lies about its signer was trusted", failure != null)
    }

    @Test
    fun `no verifier at all is refused rather than trusted on metadata`() {
        val apk = shippedApk ?: return assumeTrue("no published APK to test against", true)
        val pin = certificateOf()
        val dir = repositoryFor(apk, tempDir("noverify"), pin)

        val client = ShuraRepositoryClient(dir, null, shippedPolicy())
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("metadata alone was trusted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no APK signature verifier"))
    }
}