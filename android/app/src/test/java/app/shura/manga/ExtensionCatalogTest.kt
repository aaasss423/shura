package app.shura.manga

import app.shura.runtime.TrustAnchor
import app.shura.runtime.TrustPolicy
import app.shura.runtime.TrustedPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store must not offer anything the loader would refuse.
 *
 * An extension store is where trust becomes a button. A row that reads
 * "install" for something the policy refuses is worse than no store at all: it
 * looks like the app agreed, and the failure lands later at load time with no
 * explanation. So the verdict each row shows is the verdict the loader
 * enforces, and these tests pin them side by side.
 */
class ExtensionCatalogTest {

    @get:Rule val folder = TemporaryFolder()

    private val procomicSigner = "a".repeat(64)
    private val shuraSigner = "b".repeat(64)
    private val strangerSigner = "c".repeat(64)

    private fun policy() = TrustPolicy(
        TrustAnchor("f".repeat(64)),
        listOf(
            TrustedPublisher(procomicSigner, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic"),
            TrustedPublisher(shuraSigner, listOf("app.shura.extension."), "shura"),
        ),
    )

    private fun index(vararg rows: String): File {
        val dir = folder.newFolder("repo")
        File(dir, "apk").mkdirs()
        File(dir, ExtensionCatalog.INDEX_FILE)
            .writeText("""{"packages":[${rows.joinToString(",")}]}""")
        return dir
    }

    private fun row(pkg: String, signer: String, identity: String = "Work") =
        """{"pkg":"$pkg","version":"1.0","apk":"apk/$pkg.apk","lang":"en",
            "identity":"$identity","artifact_sha256":"${"d".repeat(64)}",
            "signing_certificate_sha256":"$signer","index_version_code":"1"}"""

    @Test
    fun `an extension signed by a pinned publisher in scope is trusted`() {
        val dir = index(row("eu.kanade.tachiyomi.extension.ar.procomic", procomicSigner))
        File(dir, "apk/eu.kanade.tachiyomi.extension.ar.procomic.apk").writeText("apk")

        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        assertEquals(ExtensionTrust.TRUSTED, entry.trust)
    }

    @Test
    fun `a known publisher signing outside its scope is out of scope, not trusted`() {
        // The signature is genuine. The authority simply does not cover this
        // package - which is the whole point of scoping a publisher.
        val dir = index(row("app.shura.extension.evil", procomicSigner))
        File(dir, "apk/app.shura.extension.evil.apk").writeText("apk")

        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        assertEquals(ExtensionTrust.OUT_OF_SCOPE, entry.trust)
    }

    @Test
    fun `an unknown signer is refused`() {
        val dir = index(row("app.shura.extension.asurascans", strangerSigner))
        File(dir, "apk/app.shura.extension.asurascans.apk").writeText("apk")

        val catalog = ExtensionCatalog(policy(), dir)
        val entry = catalog.describe(dir).single()
        assertEquals(ExtensionTrust.UNKNOWN_PUBLISHER, entry.trust)
        // And the store must say why, rather than just greying the row out.
        assertTrue(catalog.whyNot(listOf(entry), entry.pkg)!!.contains("does not trust"))
    }

    @Test
    fun `an extension whose file is absent is not offered as installed`() {
        val dir = index(row("app.shura.extension.asurascans", shuraSigner))

        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        // Trust would be TRUSTED if the bytes were here. They are not, so the
        // row says the file is missing rather than promising a working source.
        assertEquals(ExtensionTrust.NOT_DOWNLOADED, entry.trust)
    }

    @Test
    fun `a repository with no publisher pinned refuses rather than falls back`() {
        // No publishers at all: the build has no Shura key. Every extension must
        // read as untrusted, not as trusted-by-omission.
        val dir = index(row("app.shura.extension.asurascans", shuraSigner))
        File(dir, "apk/app.shura.extension.asurascans.apk").writeText("apk")
        val empty = TrustPolicy(TrustAnchor("f".repeat(64)), emptyList())

        val catalog = ExtensionCatalog(empty, dir)
        val entries = catalog.describe(dir)
        assertTrue(catalog.runnable(entries).isEmpty())
    }

    @Test
    fun `a missing repository is an empty store, not a crash`() {
        val empty = folder.newFolder("nothing-here")
        assertEquals(emptyList<ExtensionEntry>(), ExtensionCatalog(policy(), empty).describe(empty))
    }

    @Test
    fun `a malformed index is an empty store, not a crash`() {
        val dir = folder.newFolder("broken")
        File(dir, ExtensionCatalog.INDEX_FILE).writeText("{ not json")
        assertEquals(emptyList<ExtensionEntry>(), ExtensionCatalog(policy(), dir).describe(dir))
    }

    @Test
    fun `only trusted extensions are reported runnable`() {
        val dir = index(
            row("eu.kanade.tachiyomi.extension.ar.procomic", procomicSigner, "ProComic"),
            row("app.shura.extension.asurascans", strangerSigner, "Asura"),
        )
        File(dir, "apk/eu.kanade.tachiyomi.extension.ar.procomic.apk").writeText("apk")
        File(dir, "apk/app.shura.extension.asurascans.apk").writeText("apk")

        val catalog = ExtensionCatalog(policy(), dir)
        val entries = catalog.describe(dir)
        assertEquals(listOf("ProComic"), catalog.runnable(entries).map { it.identity })
    }
}