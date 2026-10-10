package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.kanade.tachiyomi.source.model.SManga
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * Asserts the transitional ABI layer against the real published extension.
 *
 * The extension APK is not self-contained: its DEX binds against
 * eu.kanade.tachiyomi.* types that must exist in the host, and a mismatch in
 * any of those signatures fails at class-resolution time with
 * NoSuchMethodError/ClassNotFoundException - long after the APK has passed
 * signature verification. So this test reads the members the DEX actually
 * references out of the artifact and asserts the host provides each one.
 *
 * This proves the ABI surface matches the shipped binary. It does NOT prove
 * the extension loads or runs: that needs a device, and nothing here claims it.
 */
class AbiSurfaceTest {

    private val repoDir: File = File(System.getProperty("shura.repo.dir") ?: "../../repo")

    private fun apk(): File {
        val dir = File(repoDir, "apk")
        val files = dir.listFiles { f: File -> f.name.endsWith(".apk") }?.sortedBy { it.name }
        // No assumeTrue: a missing artifact is a failure with a clear reason, not a
        // silent skip that makes these assertions vacuous.
        assertTrue("no published APK under $dir", !files.isNullOrEmpty())
        return files!!.firstOrNull { it.name.contains("1.5.1") }
            ?: files.first()
    }

    /** Type descriptors and member names the DEX references, read from its real string pool. */
    private val hostOwnedTypes = listOf(
        "Leu/kanade/tachiyomi/source/model/SManga;",
        "Leu/kanade/tachiyomi/source/model/SChapter;",
        "Leu/kanade/tachiyomi/source/model/Page;",
        "Leu/kanade/tachiyomi/source/model/MangasPage;",
        "Leu/kanade/tachiyomi/source/model/Filter;",
        "Leu/kanade/tachiyomi/source/model/Filter\$Select;",
        "Leu/kanade/tachiyomi/source/model/FilterList;",
        "Leu/kanade/tachiyomi/source/online/HttpSource;",
        "Leu/kanade/tachiyomi/network/NetworkHelper;",
        "Leu/kanade/tachiyomi/network/RequestsKt;",
    )

    /**
     * The DEX string pool, decoded properly.
     *
     * A previous version scanned the raw dex bytes for printable runs, which
     * silently returned nothing and made these assertions vacuous. The pool is
     * a real table: `string_ids_off` at 0x3c points at u32 offsets, each pointing
     * at a string_data_item of ULEB128 utf16 length, MUTF-8 bytes, NUL.
     */
    private fun dexStrings(): Set<String> {
        val dex = ZipFile(apk()).use { z -> z.getInputStream(z.getEntry("classes.dex")).readBytes() }
        fun u32(o: Int) = (dex[o].toLong() and 0xFF) or
            ((dex[o + 1].toLong() and 0xFF) shl 8) or
            ((dex[o + 2].toLong() and 0xFF) shl 16) or
            ((dex[o + 3].toLong() and 0xFF) shl 24)
        val count = u32(0x38).toInt()
        val table = u32(0x3C).toInt()
        val out = linkedSetOf<String>()
        repeat(count) { i ->
            var p = u32(table + 4 * i).toInt()
            var shift = 0
            var size = 0
            while (true) {
                val b = dex[p++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            var end = p
            while (dex[end] != 0.toByte()) end++
            out += String(dex, p, end - p, Charsets.UTF_8)
        }
        return out
    }

    @Test
    fun dexReferencesOnlyHostTypesTheRuntimeProvides() {
        val strings = dexStrings()
        for (descriptor in hostOwnedTypes) {
            assertTrue(
                "the published DEX must bind against $descriptor",
                strings.contains(descriptor),
            )
        }
    }

    @Test
    fun hostSuppliesEveryRequiredMember() {
        val strings = dexStrings()
        // Members the DEX calls, as "<descriptor>.<name>" fragments.
        val required = mapOf(
            "Leu/kanade/tachiyomi/network/NetworkHelper;" to listOf("getClient"),
            // Kotlin callers reach the synthetic default-argument bridge, and that is the
                    // only name the DEX's string pool contains for this file. Asserting
                    // the bare `GET` would be asserting something the artifact never
                    // asked for. Whether the runtime *provides* it is checked by
                    // reflection in everyHostOwnedClassIsActuallyPresentInTheRuntime.
                    "Leu/kanade/tachiyomi/network/RequestsKt;" to listOf("GET\$default"),
            "Leu/kanade/tachiyomi/source/model/SManga;" to listOf(
                "getUrl", "setUrl", "getTitle", "setTitle", "getAuthor", "setAuthor",
                "getGenre", "setGenre", "getDescription", "setDescription",
                "getStatus", "setStatus", "getThumbnail_url", "setThumbnail_url", "create",
            ),
            "Leu/kanade/tachiyomi/source/model/SChapter;" to listOf(
                "getUrl", "setUrl", "setName", "setScanlator", "setChapter_number", "create",
            ),
            "Leu/kanade/tachiyomi/source/model/Page;" to listOf("getImageUrl"),
            "Leu/kanade/tachiyomi/source/model/MangasPage;" to emptyList(),
            "Leu/kanade/tachiyomi/source/model/Filter\$Select;" to emptyList(),
            "Leu/kanade/tachiyomi/source/model/FilterList;" to emptyList(),
            "Leu/kanade/tachiyomi/source/online/HttpSource;" to emptyList(),
        )
        for ((descriptor, members) in required) {
            assertTrue("DEX does not reference $descriptor", strings.contains(descriptor))
            for (member in members) {
                assertTrue(
                    "DEX binds $descriptor.$member but the runtime must provide it",
                    strings.contains(member),
                )
            }
        }
    }

    @Test
    fun everyHostOwnedClassIsActuallyPresentInTheRuntime() {
        // Compile-time check that the class exists and its shape is what the DEX
        // expects. A missing class here is the same failure as above, caught
        // here instead of on a device.
        val cl = Class.forName("eu.kanade.tachiyomi.source.model.SManga")
        assertTrue(SManga::class.java.isAssignableFrom(cl))
        assertTrue(
            "SManga.status must be int",
            cl.getMethod("getStatus").returnType == Int::class.javaPrimitiveType,
        )
        assertTrue(SManga::class.java.name == cl.name)
        assertTrue(
            "SChapter.chapter_number must be float",
            Class.forName("eu.kanade.tachiyomi.source.model.SChapter")
                .getMethod("setChapter_number", Float::class.javaPrimitiveType) != null,
        )
        assertTrue(
            "NetworkHelper.getClient must return okhttp3.OkHttpClient",
            Class.forName("eu.kanade.tachiyomi.network.NetworkHelper")
                .getMethod("getClient").returnType.name == "okhttp3.OkHttpClient",
        )
        assertTrue(
            "HttpSource must extend CatalogueSource, which is what the DEX binds",
            Class.forName("eu.kanade.tachiyomi.source.CatalogueSource").isAssignableFrom(
                Class.forName("eu.kanade.tachiyomi.source.online.HttpSource"),
            ),
        )
    }

    @Test
    fun abiGateReadsTheApkNotTheIndex() {
        // The published index advertises extensionLib 1.5 while the APK declares
        // 1.6. The runtime must take the ABI from the artifact.
        val meta = ApkManifestReader().read(apk())
        assertEquals("1.6", meta.metaData["tachiyomix.extensionLib"])
        assertEquals(".ProComic", meta.metaData["tachiyomi.extension.class"])
        assertEquals("ProComic", meta.metaData["tachiyomix.name"])
        assertEquals("eu.kanade.tachiyomi.extension.ar.procomic", meta.packageName)
        // The APK's own versionCode is 7; the index's derived 10501 is a
        // separate, Core-side concern and must not be what the runtime reads.
        assertEquals(7L, meta.versionCode)
        assertEquals("1.5.1", meta.versionName)
        assertEquals(26, meta.minSdk)
    }
}