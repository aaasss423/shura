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

    private val repositorySigner = "a".repeat(64)
    private val shuraSigner = "b".repeat(64)
    private val strangerSigner = "c".repeat(64)

    private fun policy() = TrustPolicy(
        TrustAnchor("f".repeat(64)),
        listOf(
            TrustedPublisher(
                repositorySigner,
                listOf("eu.kanade.tachiyomi.extension.ar.anyonemanga"),
                "shura-repo",
            ),
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

    /**
     * Written the way the real publisher writes it: a bare artifact name, with
     * the APK sitting under `apk/`. An earlier version of this test used
     * "apk/<name>", a shape the publisher never emits, and so hid a bug that
     * made every extension read as "not on device".
     */
    private fun row(
        pkg: String,
        signer: String,
        identity: String = "Work",
        version: String = "1.0",
        code: String = "1",
    ) = """{"pkg":"$pkg","version":"$version","apk":"$pkg-$version.apk","lang":"en",
            "identity":"$identity","artifact_sha256":"${"d".repeat(64)}",
            "signing_certificate_sha256":"$signer","index_version_code":"$code"}"""

    /** Puts an artifact where the installer actually puts one. */
    private fun publish(dir: File, pkg: String, version: String = "1.0") {
        File(dir, "apk").mkdirs()
        File(dir, "apk/$pkg-$version.apk").writeText("apk")
    }

    @Test
    fun `an extension signed by a pinned publisher in scope is trusted`() {
        val dir = index(row("eu.kanade.tachiyomi.extension.ar.anyonemanga", repositorySigner))
        publish(dir, "eu.kanade.tachiyomi.extension.ar.anyonemanga")

        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        assertEquals(ExtensionTrust.TRUSTED, entry.trust)
    }

    @Test
    fun `a known publisher signing outside its scope is out of scope, not trusted`() {
        // The signature is genuine. The authority simply does not cover this
        // package - which is the whole point of scoping a publisher.
        val dir = index(row("app.shura.extension.evil", repositorySigner))
        publish(dir, "app.shura.extension.evil")

        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        assertEquals(ExtensionTrust.OUT_OF_SCOPE, entry.trust)
    }

    @Test
    fun `an unknown signer is refused`() {
        val dir = index(row("app.shura.extension.asurascans", strangerSigner))
        publish(dir, "app.shura.extension.asurascans")

        val catalog = ExtensionCatalog(policy(), dir)
        val entry = catalog.describe(dir).single()
        assertEquals(ExtensionTrust.UNKNOWN_PUBLISHER, entry.trust)
        // And the store must say why, rather than just greying the row out.
        assertTrue(catalog.whyNot(listOf(entry), entry)!!.contains("does not trust"))
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
        publish(dir, "app.shura.extension.asurascans")
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
            row("eu.kanade.tachiyomi.extension.ar.anyonemanga", repositorySigner, "AnyoneManga"),
            row("app.shura.extension.asurascans", strangerSigner, "Asura"),
        )
        publish(dir, "eu.kanade.tachiyomi.extension.ar.anyonemanga")
        publish(dir, "app.shura.extension.asurascans")

        val catalog = ExtensionCatalog(policy(), dir)
        val entries = catalog.describe(dir)
        assertEquals(listOf("AnyoneManga"), catalog.runnable(entries).map { it.identity })
    }

    @Test
    fun `two versions of one package are an update, not two extensions`() {
        val pkg = "eu.kanade.tachiyomi.extension.ar.anyonemanga"
        val dir = index(
            row(pkg, repositorySigner, "AnyoneManga", "v1.6.55", "655"),
            row(pkg, repositorySigner, "AnyoneManga", "v1.6.56", "656"),
        )
        publish(dir, pkg, "v1.6.55")

        val catalog = ExtensionCatalog(policy(), dir)
        val entries = catalog.describe(dir)
        // Both rows exist in the index...
        assertEquals(2, entries.size)
        // ...but the store shows one, at the newest version, which is exactly
        // the "only one extension" symptom the real repository produced.
        val shown = ExtensionCatalog.newestPerPackage(entries)
        assertEquals(1, shown.size)
        assertEquals("v1.6.56", shown.single().version)
    }

    @Test
    fun `an artifact name cannot escape the apk directory`() {
        // The index is remote input. A traversal there would let a repository
        // address a file outside the directory it was installed into.
        val pkg = "app.shura.extension.evil"
        val dir = index(row(pkg, strangerSigner))
        File(dir, ExtensionCatalog.INDEX_FILE).writeText(
            """{"packages":[{"pkg":"$pkg","version":"1.0","apk":"../../etc/passwd","lang":"en",
                "identity":"x","artifact_sha256":"","signing_certificate_sha256":"$strangerSigner",
                "index_version_code":"1"}]}"""
        )
        val entry = ExtensionCatalog(policy(), dir).describe(dir).single()
        assertEquals(ExtensionTrust.NOT_DOWNLOADED, entry.trust)
        assertEquals(null, entry.artifactPath)
    }

    private fun entry(pkg: String, lang: String, identity: String = "$pkg|1.0"): ExtensionEntry =
        ExtensionEntry(
            pkg = pkg,
            version = "1.0",
            lang = lang,
            identity = identity,
            trust = ExtensionTrust.TRUSTED,
            artifactPresent = true,
            signer = "a",
            artifactPath = "/tmp/apk",
            artifactName = "$pkg.apk",
            publishedSha256 = "d".repeat(64),
        )

    @Test
    fun `a row is named from the package slug and language, not the raw identity`() {
        // The index carries no display name, only the package and the language.
        // The title a reader sees is the slug split at word boundaries, capped,
        // with the language behind it - not the whole dotted package string.
        val entry = entry("eu.kanade.tachiyomi.extension.ar.anyonemanga", "ar")
        assertEquals("Anyonemanga (ar)", ExtensionCatalog.displayName(entry))
    }

    @Test
    fun `camelCase and dash word boundaries in a slug become spaces`() {
        assertEquals("Mangadex", ExtensionCatalog.displayName(entry("app.shura.extension.mangadex", "")))
        assertEquals(
            "Manga Dex",
            ExtensionCatalog.displayName(entry("eu.kanade.tachiyomi.extension.en.mangaDex", "")),
        )
        assertEquals(
            "Manga Arabia (ar)",
            ExtensionCatalog.displayName(entry("eu.kanade.tachiyomi.extension.ar.manga-arabia", "ar")),
        )
    }

    @Test
    fun `an unreadable package falls back to the identity, never to nothing`() {
        val entry = entry("...||", "und", identity = "eu.kanade.tachiyomi.extension.x.y|9")
        assertEquals("eu.kanade.tachiyomi.extension.x.y|9", ExtensionCatalog.displayName(entry))
    }
}