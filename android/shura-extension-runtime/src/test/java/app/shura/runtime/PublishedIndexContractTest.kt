package app.shura.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader, against an index the publisher actually produced.
 *
 * Every other test in this module writes its own `index.shura.json`. That proves
 * the parser tolerates a hand-built shape; it does not prove the shape Shura
 * publishes is one the parser can read. The fixture in
 * `src/test/resources/published/` is written by the real `RepositoryPublisher`,
 * and `tests/test_publisher_reader_contract.py` regenerates it and fails if it
 * drifts, so this stays an integration test rather than a second fixture.
 */
class PublishedIndexContractTest {

    private fun published(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("published/$name")
            ?: throw AssertionError("published/$name is missing from the test resources")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `the reader parses the index the publisher writes`() {
        val entries = RepositoryIndex.parse(published("index.shura.json"))
        assertEquals("expected exactly the one published extension", 1, entries.size)
        val entry = entries.single()
        assertEquals("app.shura.extension.mangadex", entry.pkg)
        assertEquals("1.0", entry.version)
        assertEquals("ar", entry.lang)
        assertEquals("app.shura.extension.mangadex|1.0", entry.identity)
        assertEquals("app_shura_extension_mangadex-1.0.apk", entry.apk)
    }

    @Test
    fun `every field the client compares against the artifact is populated`() {
        val entry = RepositoryIndex.parse(published("index.shura.json")).single()

        // Compared against the downloaded APK's bytes. Empty here would mean the
        // comparison is against nothing.
        assertTrue("artifact digest is missing", entry.artifactSha256.isNotEmpty())
        assertEquals(64, entry.artifactSha256.length)

        // The trust anchor travels with the entry.
        assertTrue("signing certificate is missing", entry.signingCertificateSha256.isNotEmpty())
        assertEquals(64, entry.signingCertificateSha256.length)

        // These two used to be absent, so they arrived as null and 0 and every
        // comparison against them was against a value nothing had published.
        assertNotNull("extensionLib was not published", entry.indexExtensionLib)
        assertTrue(
            "extensionLib must not be blank",
            entry.indexExtensionLib!!.isNotBlank(),
        )
        assertTrue(
            "versionCode " + entry.indexVersionCode + " is the reader's silent default",
            entry.indexVersionCode > 0L,
        )
    }

    @Test
    fun `the extensionLib the index publishes is the value the APK declares`() {
        val entry = RepositoryIndex.parse(published("index.shura.json")).single()
        // Shura derives it from the version string; "1.0" -> "1". The extension
        // declares the same value in its manifest, which is why the two agree.
        assertEquals("1", entry.indexExtensionLib)
    }

    @Test
    fun `the repository trust anchor parses from the published repo json`() {
        val meta = RepositoryMeta.parse(published("repo.json"))
        assertEquals("Shura", meta.name)
        assertEquals(
            "f5b757d7c773d65e74907c08e4f8d2fdb30a3d76b78a764e2093f6a9e69bcba5",
            meta.signingKeyFingerprint,
        )
    }

    @Test
    fun `an index without a packages array is refused rather than read as empty`() {
        val failure = runCatching { RepositoryIndex.parse("""{"repo":"Shura"}""") }
        assertTrue("a malformed index must fail loudly", failure.isFailure)
    }

    @Test
    fun `a published apk name cannot escape the apk directory`() {
        val entry = RepositoryIndex.parse(published("index.shura.json")).single()
        // The client resolves File(root, "apk").resolve(entry.apk), so a name
        // containing a separator would reach outside it.
        assertTrue(
            "apk name ${entry.apk} contains a path separator",
            !entry.apk.contains('/') && !entry.apk.contains(".."),
        )
        assertEquals(
            entry.apk,
            File("apk", entry.apk).name,
        )
    }
}