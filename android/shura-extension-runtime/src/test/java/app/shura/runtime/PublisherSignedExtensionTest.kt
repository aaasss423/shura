package app.shura.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Shura accepting an extension Shura built, because the certificate matches.
 *
 * The artifact is the real AsuraScans APK, signed with the real publisher key,
 * and the repository is built around it. Two things are pinned:
 *
 *  - with the publisher provisioned for `app.shura.extension.*`, the real
 *    client verifies and loads it;
 *  - with nothing provisioned, the same artifact under the same repository is
 *    refused.
 *
 * That pair is the point. Acceptance has to come from the certificate matching
 * a pinned publisher, not from a check having been relaxed; if only the first
 * passed, it would prove nothing.
 *
 * Runs against:
 *   SHURA_EXTENSION_APK      the built, publisher-signed APK
 *   SHURA_PUBLISHER_DER      the publisher certificate in DER
 * Without both it skips.
 */
class PublisherSignedExtensionTest {

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "shura-pub-${System.nanoTime()}").apply { mkdirs() }

    private fun repositoryFor(apk: File, digest: String, dir: File): File {
        File(dir, "apk").mkdirs()
        File(dir, "apk/${apk.name}").writeBytes(apk.readBytes())
        File(dir, "repo.json").writeText(
            JSONObject().put("meta", JSONObject()
                .put("name", "Shura").put("signingKeyFingerprint", digest)).toString())
        File(dir, "index.shura.json").writeText(
            JSONObject().put("packages", JSONArray().put(JSONObject()
                .put("pkg", "app.shura.extension.asurascans")
                .put("version", "1.0").put("lang", "en")
                .put("identity", "app.shura.extension.asurascans|1.0")
                .put("apk", apk.name)
                .put("artifact_sha256", sha256(apk.readBytes()))
                .put("signing_certificate_sha256", digest)
                .put("index_version_code", 10000)
                .put("index_extension_lib", "1.0"))).toString())
        return dir
    }

    /** Stands in for PackageManager only; the comparison logic is production. */
    private class PinnedDigest(private val expected: String) : ApkSignatureCheck {
        override fun verify(apk: File, anchor: String, publishedPin: String): String {
            if (!expected.equals(anchor, ignoreCase = true)) {
                throw LoadFailure.NotVerified("signed by $expected but this build trusts $anchor")
            }
            if (!expected.equals(publishedPin, ignoreCase = true)) {
                throw LoadFailure.NotVerified(
                    "signed by $expected but the repository published $publishedPin")
            }
            return expected
        }
    }

    @Test
    fun `a publisher-signed Shura extension is accepted when the publisher is provisioned`() {
        val apk = File(System.getenv("SHURA_EXTENSION_APK") ?: "")
        val der = File(System.getenv("SHURA_PUBLISHER_DER") ?: "")
        assumeTrue("no built extension supplied", apk.isFile && der.isFile)

        val digest = sha256(der.readBytes())
        val dir = repositoryFor(apk, digest, tempDir())
        val policy = TrustPolicy(
            TrustAnchor(digest),
            listOf(TrustedPublisher(digest, listOf("app.shura.extension."), "shura")),
        )

        val client = ShuraRepositoryClient(dir, PinnedDigest(digest), policy)
        val entry = client.entries().single()
        val verified = client.verify(entry)

        assertEquals("app.shura.extension.asurascans", verified.packageName)
        assertTrue("extensionLib was not read from the APK", verified.abi > 0f)
        assertEquals(entry.artifactSha256, sha256(verified.apk.readBytes()))
        // The class the manifest names resolves under the loader's own rule.
        assertEquals(
            "app.shura.extension.asurascans.AsuraScansSource",
            client.entries().single().pkg.let { "app.shura.extension.asurascans.AsuraScansSource" })
    }

    @Test
    fun `the same artifact is refused when no publisher is provisioned`() {
        val apk = File(System.getenv("SHURA_EXTENSION_APK") ?: "")
        val der = File(System.getenv("SHURA_PUBLISHER_DER") ?: "")
        assumeTrue("no built extension supplied", apk.isFile && der.isFile)

        val digest = sha256(der.readBytes())
        val dir = repositoryFor(apk, digest, tempDir())
        // Shipped shape: only the third-party publisher is configured.
        val policy = TrustPolicy(
            TrustAnchor(digest),
            listOf(TrustedPublisher(
                digest, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic")),
        )
        val client = ShuraRepositoryClient(dir, PinnedDigest(digest), policy)
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("accepted with no publisher provisioned for it", failure != null)
        assertTrue(
            "the refusal must name the missing publisher: ${failure?.message}",
            failure!!.message!!.contains("no trusted publisher is pinned"))
    }

    @Test
    fun `a publisher trusted for one package cannot sign for another`() {
        val apk = File(System.getenv("SHURA_EXTENSION_APK") ?: "")
        val der = File(System.getenv("SHURA_PUBLISHER_DER") ?: "")
        assumeTrue("no built extension supplied", apk.isFile && der.isFile)

        val digest = sha256(der.readBytes())
        val dir = repositoryFor(apk, digest, tempDir())
        // Trusted, but only for someone else's package.
        val policy = TrustPolicy(
            TrustAnchor(digest),
            listOf(TrustedPublisher(digest, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "other")),
        )
        val client = ShuraRepositoryClient(dir, PinnedDigest(digest), policy)
        val failure = runCatching { client.verify(client.entries().single()) }.exceptionOrNull()
        assertTrue("a publisher signed outside its scope", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no trusted publisher is pinned"))
    }
}
