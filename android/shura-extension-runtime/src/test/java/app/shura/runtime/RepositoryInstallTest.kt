package app.shura.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Getting a repository from somewhere onto the device, and what happens when
 * there isn't one.
 *
 * The loader read `repo.json`, `index.shura.json` and `apk/<file>.apk` out of the
 * app's files directory, and nothing in the app ever wrote there. The only
 * reachable state was an empty directory: no extension could load, and
 * `ShuraExtensionLoader.load()` threw out of composition on first launch.
 *
 * These tests cover the two ends of that: installing a repository into the
 * directory the loader reads, and reporting - rather than throwing - when there
 * is none.
 *
 * The fixtures are a real published repository when one is supplied:
 *
 *     SHURA_LIVE_REPO=/path/to/repo gradle :shura-extension-runtime:test
 *
 * Without it they use a repository built to the index's contract, so the
 * refusal cases still run.
 */
class RepositoryInstallTest {

    private fun tempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"),
            "shura-install-${prefix}-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun digestOf(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certificate(): String = "b".repeat(64)

    /**
     * Installing and reading an index is not a trust decision, so these tests
     * pin a policy covering the sample package rather than asserting on trust.
     */
    private fun policy() = TrustPolicy(
        TrustAnchor(certificate()),
        listOf(TrustedPublisher(certificate(), listOf("app.shura.extension.sample"))),
    )

    /** A repository that satisfies the index contract, built on disk. */
    private fun buildRepository(name: String, extraApks: List<String> = emptyList()): File {
        val root = tempDir(name)
        File(root, "apk").mkdirs()
        val apk = File(root, "apk/extension-1.0.apk")
        apk.writeBytes(byteArrayOf(3, 0, 8, 0) + ByteArray(64))

        val entry = JSONObject()
            .put("pkg", "app.shura.extension.sample")
            .put("version", "1.0")
            .put("lang", "ar")
            .put("identity", "app.shura.extension.sample|1.0")
            .put("apk", "extension-1.0.apk")
            .put("artifact_sha256", digestOf(apk))
            .put("signing_certificate_sha256", certificate())
            .put("index_version_code", 10000)
            .put("index_extension_lib", "1.0")

        File(root, "index.shura.json").writeText(
            JSONObject().put("packages", JSONArray().put(entry)).toString())
        // The shape Shura Core actually writes: the anchor lives under "meta".
        File(root, "repo.json").writeText(
            JSONObject().put("meta", JSONObject()
                .put("name", "Shura")
                .put("signingKeyFingerprint", certificate())).toString())
        for (extra in extraApks) {
            File(root, "apk/$extra").writeBytes(ByteArray(32))
        }
        return root
    }

    private fun liveRepository(): File? {
        val path = System.getenv("SHURA_LIVE_REPO")
        if (path.isNullOrBlank()) return null
        val dir = File(path)
        return if (dir.isDirectory) dir else null
    }

    // -- installing ---------------------------------------------------------

    @Test
    fun `a repository installs and is readable by the client that will load it`() {
        val from = buildRepository("ok")
        val into = tempDir("into")
        val report = ShuraRepositoryInstaller.install(from, into)

        assertTrue(report.refused ?: "", report.ok)
        assertEquals(listOf("apk/extension-1.0.apk", "index.shura.json", "repo.json"),
            report.installed.sorted())
        assertTrue(File(into, "repo.json").isFile)
        assertTrue(File(into, "apk/extension-1.0.apk").isFile)

        // The client can now find what the loader asks for.
        val entries = ShuraRepositoryClient(into, null, policy()).entries()
        assertEquals(1, entries.size)
        assertEquals("app.shura.extension.sample", entries.single().pkg)
    }

    @Test
    fun `installing does not make a repository trusted`() {
        val from = buildRepository("untrusted")
        val into = tempDir("untrusted-into")
        ShuraRepositoryInstaller.install(from, into)

        // Installing is copying. Trust is still decided against the pinned
        // anchor, and a repository that declares a different key is refused
        // even though it is sitting exactly where the loader reads from.
        val wrong = TrustPolicy(
            TrustAnchor("c".repeat(64)),
            listOf(TrustedPublisher("c".repeat(64), listOf("app.shura.extension.sample"))))
        val failure = try {
            ShuraRepositoryClient(into, null, wrong).entries()
            null
        } catch (e: Throwable) { e }
        assertTrue("a repository with the wrong key was accepted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("anchor mismatch"))
    }

    @Test
    fun `a repository whose artifacts are missing is refused`() {
        val from = buildRepository("missing")
        File(from, "apk/extension-1.0.apk").delete()
        val report = ShuraRepositoryInstaller.install(from, tempDir("missing-into"))
        assertFalse(report.ok)
        assertTrue(report.refused, report.refused!!.contains("not in apk/"))
    }

    @Test
    fun `a repository carrying artifacts the index does not reference is refused`() {
        val from = buildRepository("extra", extraApks = listOf("not-referenced.apk"))
        val report = ShuraRepositoryInstaller.install(from, tempDir("extra-into"))
        assertFalse(report.ok)
        assertTrue(report.refused, report.refused!!.contains("does not reference"))
    }

    @Test
    fun `a failed install leaves no half-written repository`() {
        val from = buildRepository("atomic")
        val into = tempDir("atomic-into")
        // A first, good install so there is something to protect.
        ShuraRepositoryInstaller.install(from, into)
        assertTrue(File(into, "index.shura.json").isFile)

        // A second repository that cannot be installed.
        val bad = buildRepository("atomic-bad")
        File(bad, "index.shura.json").writeText("{not json")
        assertFalse(ShuraRepositoryInstaller.install(bad, into).ok)

        // The previous one is intact and still readable.
        val entries = ShuraRepositoryClient(into, null, policy()).entries()
        assertEquals(1, entries.size)
    }

    @Test
    fun `a directory with no repository in it is refused with a reason`() {
        val report = ShuraRepositoryInstaller.install(tempDir("empty"), tempDir("empty-into"))
        assertFalse(report.ok)
        assertTrue(report.refused, report.refused!!.contains("repo.json"))
    }

    // -- the state the app is actually in on first launch --------------------

    @Test
    fun `an absent repository is a message and not a crash`() {
        // Nothing installs a repository until the user does, so this empty
        // directory is the real state of a fresh install. Reading the index
        // used to throw out of load() and into composition, taking the app
        // down on first launch.
        val empty = tempDir("fresh")
        val failure = try {
            ShuraRepositoryClient(empty, null, policy()).entries()
            null
        } catch (e: Throwable) { e }
        assertTrue("expected the client to refuse an empty directory", failure != null)
        val message = failure!!.message.orEmpty()
        assertTrue(
            "empty repository must fail with a clear manifest message instead of crashing: $message",
            message.contains("repo.json") || message.contains("index.shura.json") || message.contains("missing"),
        )
    }

    // -- against a repository Shura Core actually published -----------------

    @Test
    fun `a really published repository installs`() {
        val live = liveRepository()
        assumeTrue("no published repository supplied", live != null)
        val into = tempDir("live")
        val report = ShuraRepositoryInstaller.install(live!!, into)

        assertTrue(report.refused ?: "", report.ok)
        assertTrue("nothing was copied", report.installed.isNotEmpty())

        val anchor = File(live, "repo.json").let {
            RepositoryMeta.parse(it.readText()).signingKeyFingerprint
        }
        val entries = ShuraRepositoryClient(
            into, null,
            TrustPolicy(TrustAnchor(anchor),
                listOf(TrustedPublisher(anchor, listOf("app.shura.extension.asurascans"))))).entries()
        assertEquals(
            "every published entry should have been copied",
            RepositoryIndex.parse(File(live, "index.shura.json").readText()).size,
            entries.size)
        // And every digest the index published is the digest on disk.
        for (entry in entries) {
            // (message, expected, actual): passing a String third argument
            // selects assertEquals(String, Object, Object) instead.
            assertEquals(entry.identity, entry.artifactSha256,
                digestOf(File(into, "apk/${entry.apk}")))
        }
    }
}