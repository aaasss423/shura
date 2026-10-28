package app.shura.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `verify()` on a real signed APK, as far as the JVM can take it.
 *
 * There is no emulator or system image in this environment and no
 * `/dev/kvm`, so `RealExtensionLoadTest` - which needs a device - cannot run.
 * That test is not replaced or simulated here.
 *
 * What this does instead is drive the real [ShuraRepositoryClient] over a
 * repository produced by the real publisher, using the real signed APK, and
 * assert every step that does not require an Android runtime: the index is
 * read, the artifact is fetched from `apk/`, its digest is compared with the
 * one the publisher published, and its manifest is parsed and checked against
 * the published package. The run then stops where the runtime genuinely stops
 * - no signature verifier on the JVM - and the refusal is asserted.
 *
 * The APK path comes from `SHURA_TEST_APK` so the artifact itself does not
 * have to be committed; without it the test skips rather than inventing one.
 */
class ShuraRepositoryClientVerifyTest {

    companion object {
        /** Held out of band, exactly as TrustAnchor is meant to be. */
        private const val ANCHOR = "f5b757d7c773d65e74907c08e4f8d2fdb30a3d76b78a764e2093f6a9e69bcba5"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildRepository(): Pair<File, String>? {
        val apkPath = System.getenv("SHURA_TEST_APK") ?: return null
        val apk = File(apkPath)
        if (!apk.isFile) return null
        val dir = File(System.getProperty("java.io.tmpdir"), "shura-verify-${apk.name}")
        dir.deleteRecursively()
        File(dir, "apk").mkdirs()
        for (name in listOf("index.shura.json", "repo.json")) {
            val stream = javaClass.classLoader!!.getResourceAsStream("published/$name")
                ?: throw AssertionError("published/$name missing")
            File(dir, name).writeText(stream.bufferedReader().use { it.readText() })
        }
        // Publish the artifact under the name the index names, not the name the
        // build gave it: resolving that name is part of what is under test.
        val publishedName = RepositoryIndex
            .parse(File(dir, "index.shura.json").readText()).single().apk
        apk.copyTo(File(dir, "apk/$publishedName"), overwrite = true)
        return dir to publishedName
    }

    @Test
    fun `the client reads the publisher's index and verifies the artifact it names`() {
        val (repo, apkName) = buildRepository() ?: run {
            assumeTrue("SHURA_TEST_APK not set; no signed APK to verify", false)
            return
        }
        val client = ShuraRepositoryClient(
            root = repo,
            signatures = null,
            policy = TrustPolicy(
                TrustAnchor(ANCHOR),
                listOf(TrustedPublisher(ANCHOR, listOf("app.shura.extension.")))),
        )
        client.useStaging(File(repo, "staging"))

        val entries = client.entries()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(apkName, entry.apk)

        // The artifact named by the index is fetched from apk/ and its digest
        // is compared with the one the publisher wrote.
        val downloaded = File(repo, "apk").resolve(entry.apk)
        assertTrue("${entry.apk} is not present under ${downloaded.parentFile}", downloaded.isFile)
        assertEquals(
            "the artifact's digest does not match the one the index published",
            entry.artifactSha256,
            sha256(downloaded),
        )

        // The manifest is the artifact's own, parsed by the real reader.
        val metadata = ApkManifestReader().read(downloaded)
        assertEquals(
            "the APK's manifest package disagrees with the index",
            entry.pkg,
            metadata.packageName,
        )
        assertTrue(metadata.versionCode > 0L)
        assertEquals(
            "the extension class the loader resolves is absent from the APK",
            "app.shura.extension.mangadex.MangaDexSource",
            metadata.metaData["tachiyomi.extension.class"],
        )
        // The value must survive the round trip as something numeric. A bare
        // "1" in the manifest is encoded by aapt2 as TYPE_INT_BOOLEAN, which
        // this reader renders as "true" - and "true".toFloatOrNull() is null,
        // so ShuraRepositoryClient would refuse the extension outright.
        val abiText = metadata.metaData["tachiyomix.extensionLib"]
        assertNotNull("the APK declares no extensionLib", abiText)
        assertNotNull(
            "extensionLib '$abiText' is not a number; the client would refuse it",
            abiText.toString().toFloatOrNull(),
        )

        // The index publishes a version-derived value and the artifact declares
        // its own. They differ here by design: the artifact is authoritative,
        // and the client records the difference rather than acting on it.
        assertEquals("1", entry.indexExtensionLib)
        assertTrue(
            "this test is meant to cover divergence: index '${entry.indexExtensionLib}' " +
                "vs artifact '$abiText'",
            entry.indexExtensionLib != abiText,
        )

        // The runtime stops here: without a verifier the client refuses rather
        // than trusting the metadata alone.
        // The signature check runs before the manifest comparison, so with no
        // verifier the run stops there. That ordering is itself worth pinning:
        // metadata is never reconciled before the bytes are trusted.
        val failure = runCatching { client.verify(entry) }.exceptionOrNull()
        assertTrue(
            "verify() must refuse when no signature verifier is supplied",
            failure is LoadFailure.NotVerified,
        )
        assertTrue(
            "the refusal must be the missing verifier: ${failure?.message}",
            failure!!.message!!.contains("signature verifier"),
        )
        // The divergence between the index's derived value and the artifact's
        // own is only reachable once a verifier is present, i.e. on a device.
        // What the JVM can prove is that both sides of that comparison are
        // real and different, which is checked above.
    }

    @Test
    fun `a tampered artifact is refused on the digest before anything else`() {
        val (repo, apkName) = buildRepository() ?: run {
            assumeTrue("SHURA_TEST_APK not set", false)
            return
        }
        val target = File(repo, "apk/$apkName")
        target.writeBytes(target.readBytes() + ByteArray(16) { 0x41 })

        val client = ShuraRepositoryClient(
            root = repo,
            signatures = null,
            policy = TrustPolicy(
                TrustAnchor(ANCHOR),
                listOf(TrustedPublisher(ANCHOR, listOf("app.shura.extension.")))),
        )
        client.useStaging(File(repo, "staging2"))
        val entry = client.entries().single()
        val failure = runCatching { client.verify(entry) }.exceptionOrNull()
        assertTrue("a tampered artifact must be refused", failure is LoadFailure.NotVerified)
        assertTrue(
            "refusal should name the digest mismatch: ${failure?.message}",
            failure!!.message!!.contains("SHA-256 mismatch"),
        )
    }
}