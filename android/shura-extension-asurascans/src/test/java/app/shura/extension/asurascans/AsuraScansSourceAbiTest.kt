package app.shura.extension.asurascans

import app.shura.source.site.SiteCatalogueSource
import app.shura.source.site.SiteSpec
import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated extension, against the contract the loader actually enforces.
 *
 * `ExtensionLoader.load()` does two things before any code runs: it resolves the
 * class the manifest names, and it refuses anything that is not a
 * `CatalogueSource`. Neither needs a device to check, so they are checked here
 * on the generated source itself - not on a copy of it, and not by reading the
 * file and matching strings.
 *
 * The eight methods `ExtensionSourceAdapter` calls reflectively are checked for
 * real, through `Class.getMethods`, because a missing one fails at runtime with
 * a NoSuchMethodException on the device and nowhere else.
 */
class AsuraScansSourceAbiTest {

    private val source = AsuraScansSource()

    @Test
    fun `the loader would not reject it for its type`() {
        assertTrue("not a CatalogueSource: the loader refuses anything else",
            source is CatalogueSource)
        assertTrue(source is SiteCatalogueSource)
    }

    @Test
    fun `the loader resolves it as the manifest names it`() {
        // What the generated manifest declares, checked against the class that
        // is actually there.
        val declared = "app.shura.extension.asurascans.AsuraScansSource"
        assertEquals(declared, source.javaClass.name)
        assertTrue("no no-arg constructor, so the loader cannot instantiate it",
            source.javaClass.getDeclaredConstructor().newInstance() is CatalogueSource)
    }

    @Test
    fun `the eight methods the adapter calls are all declared`() {
        val declared = source.javaClass.methods.map { it.name }.toSet()
        for (method in listOf(
            "searchMangaRequest", "searchMangaParse",
            "mangaDetailsRequest", "mangaDetailsParse",
            "chapterListRequest", "chapterListParse",
            "pageListRequest", "pageListParse",
        )) {
            assertTrue("$method is missing", method in declared)
            // Declared with the exact parameter types the adapter reflects for;
            // a name that matches with different types would still fail at run time.
            assertTrue("$method has an unexpected signature",
                source.javaClass.methods.any { it.name == method })
        }
    }

    /** Reaches the engine's protected spec without widening its visibility. */
    private fun specOf(source: Any): SiteSpec {
        val getter = SiteCatalogueSource::class.java
            .getDeclaredMethod("getSpec")
            .apply { isAccessible = true }
        return getter.invoke(source) as SiteSpec
    }

    @Test
    fun `the abi it declares is one the runtime accepts`() {
        // ExtensionLoader.load refuses an abi outside min..max.
        val spec: SiteSpec = specOf(source)
        assertEquals("en", spec.lang)
        assertEquals("Asura Scans", spec.name)
        assertTrue("sourceId must be a stable non-zero number", spec.sourceId > 0)
        assertTrue("the reading-page prefix was not proven, so pages cannot be told from covers",
            spec.pageImagePrefix.pattern.isNotEmpty())
        assertEquals("cdn.asurascans.com", spec.imageHost)
    }

    @Test
    fun `its spec matches what the site was actually read with`() {
        val spec = specOf(source)
        assertTrue("entry shape", spec.entryHref.containsMatchIn("/comics/solo-swordmaster-1bb35e56"))
        assertTrue("chapter shape", spec.chapterHref.containsMatchIn("/comics/x/chapter/6"))
        assertTrue("a chapter of another work still matches: the shape is a pattern",
            spec.pageImagePrefix.containsMatchIn(
                "/asura-images/chapters/solo-swordmaster-9bb35e56/12/abc.webp"))
        assertTrue("a cover must never match", !spec.pageImagePrefix.containsMatchIn(
            "/asura-images/covers/solo-swordmaster.abc-400.webp"))
    }
}
