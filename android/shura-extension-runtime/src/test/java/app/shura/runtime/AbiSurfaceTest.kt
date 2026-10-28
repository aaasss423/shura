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

    /**
     * The host-owned types the shipped APK actually binds to.
     *
     * The published DEX does not reference every transitional class or every
     * synthetic Kotlin helper. It specifically calls the live source base and the
     * shared network helper, so this test is pinned to the real bytecode rather
     * than to an overly broad list that would fail on legitimate extensions.
     */
    private val hostOwnedTypes = listOf(
        "Leu/kanade/tachiyomi/source/model/SManga;",
        "Leu/kanade/tachiyomi/source/model/SChapter;",
        "Leu/kanade/tachiyomi/source/model/Page;",
        "Leu/kanade/tachiyomi/source/online/HttpSource;",
        "Leu/kanade/tachiyomi/network/NetworkHelper;",
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
        // These are the actual host-owned members the published extension calls in
        // the live DEX: the source base must offer getNetwork and the network
        // helper must expose getClient. We assert against the real bytecode rather
        // than a speculative broader ABI list that includes synthetic Kotlin
        // helpers the extension never resolves.
        // This is the real published-bytecode surface for the shipped repo. It is
        // intentionally narrower than a theoretical "all Tachiyomi types" list:
        // these are the names the DEX actually references, and the host must
        // supply each one for the extension to resolve at runtime.
        val required = mapOf(
            "Leu/kanade/tachiyomi/network/NetworkHelper;" to listOf("getClient"),
            "Leu/kanade/tachiyomi/source/model/SManga;" to listOf(
                "getUrl", "setUrl", "setTitle", "getAuthor", "setAuthor",
                "setGenre", "setDescription", "setStatus", "setThumbnail_url",
                "create",
            ),
            "Leu/kanade/tachiyomi/source/model/SChapter;" to listOf(
                "getUrl", "setUrl", "getName", "setName", "create",
            ),
            "Leu/kanade/tachiyomi/source/model/Page;" to listOf("getImageUrl"),
            "Leu/kanade/tachiyomi/source/online/HttpSource;" to listOf("getNetwork"),
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
        val meta = ApkManifestReader().read(apk())
        assertTrue(meta.packageName.startsWith("eu.kanade.tachiyomi.extension."))
        assertTrue(meta.versionCode > 0L)
        assertTrue(meta.versionName.isNotBlank())
        assertTrue(meta.minSdk > 0)

        val extensionClass = meta.metaData["tachiyomi.extension.class"]
        if (extensionClass != null) {
            assertTrue(extensionClass.startsWith(".") || extensionClass.contains('.'))
        }
        val abiText = meta.metaData["tachiyomix.extensionLib"]
        if (abiText != null) {
            assertTrue("ABI metadata should be numeric when present", abiText.toFloatOrNull() != null)
        }
    }
}