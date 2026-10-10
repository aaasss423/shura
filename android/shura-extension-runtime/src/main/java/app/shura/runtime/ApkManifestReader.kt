package app.shura.runtime

import java.io.File
import java.util.zip.ZipFile

/** What an APK says about itself. Read from the artifact, never from the index. */
data class ApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val metaData: Map<String, String>,
)

/**
 * Reads `AndroidManifest.xml` out of an APK without an Android runtime.
 *
 * The manifest is compiled Android Binary XML, so the values the loader needs -
 * package, versionCode and the `meta-data` block that names the source class and
 * the ABI - have to come out of the binary form. `aapt2 dump xmltree` agrees with
 * this parser on the shipped artifacts, which is what
 * `ApkManifestReaderTest` asserts.
 *
 * Deliberately dependency-free: the loader runs before any extension code, so it
 * cannot rely on a runtime the APK might also need.
 */
class ApkManifestReader {

    fun read(apk: File): ApkMetadata = readBytes(readManifestBytes(apk))

    private fun readManifestBytes(apk: File): ByteArray = ZipFile(apk).use { z ->
        val entry = z.getEntry("AndroidManifest.xml")
            ?: error("${apk.name} has no AndroidManifest.xml")
        z.getInputStream(entry).readBytes()
    }

    private fun readBytes(data: ByteArray): ApkMetadata {
        val pool = StringPool(data)
        var packageName = ""
        var versionCode = 0L
        var versionName = ""
        var minSdk = 0
        val meta = mutableMapOf<String, String>()

        var offset = 8
        val stringPoolSize = chunkSize(data, offset)
        val resourceMap = HashMap<Int, Int>()
        var cursor = offset + stringPoolSize
        // resource map chunk: resource id per pool index, used for typed values
        while (cursor < data.size - 8) {
            val type = u16(data, cursor)
            val size = u32(data, cursor + 4).toInt()
            if (type == 0x0180) {
                val count = (size - 8) / 4
                for (i in 0 until count) resourceMap[i] = u32(data, cursor + 8 + 4 * i).toInt()
                cursor += size
            } else break
        }

        while (cursor < data.size - 8) {
            val type = u16(data, cursor)
            val headerSize = u16(data, cursor + 2)
            val size = u32(data, cursor + 4).toInt()
            if (size <= 0) break
            if (type == 0x0102) { // START_ELEMENT (0x0100 is START_NAMESPACE)
                val nameIndex = u32(data, cursor + 20)
                val attrStart = u16(data, cursor + 24)
                val attrSize = u16(data, cursor + 26)
                val attrCount = u16(data, cursor + 28)
                val element = pool[nameIndex]
                var name: String? = null
                var value: String? = null
                for (i in 0 until attrCount) {
                    val base = cursor + 16 + attrStart + i * attrSize
                    if (base + 20 > data.size) break
                    val aName = pool[u32(data, base + 4)]
                    val aRaw = u32(data, base + 8)
                    val dataType = data[base + 15].toInt() and 0xFF
                    val aData = u32(data, base + 16)
                    val literal = if (aRaw != 0xFFFFFFFFL && aRaw < pool.size) pool[aRaw] else ""
                    val resolved = when {
                        literal.isNotEmpty() -> literal
                        // 0x04 is TYPE_FLOAT. extensionLib is declared as a bare
                        // number, so it is stored as float bits with no raw string;
                        // reading those bits as an integer yields 1070386381
                        // instead of 1.6.
                        dataType == 0x04 -> java.lang.Float.intBitsToFloat(aData.toInt()).toString()
                        dataType == 0x10 || dataType == 0x12 -> if (aData != 0L) "true" else "false"
                        else -> aData.toString()
                    }
                    when (aName) {
                        "name" -> name = if (literal.isNotEmpty()) literal else resolved
                        "value" -> value = resolved
                        "package" -> if (element == "manifest") packageName = resolved
                        "versionCode" -> if (element == "manifest") versionCode = aData
                        "versionName" -> if (element == "manifest") versionName = resolved
                        "minSdkVersion" -> if (element == "uses-sdk") minSdk = aData.toInt()
                    }
                }
                if (element == "meta-data" && name != null) meta[name] = value ?: ""
            }
            cursor += size
        }
        return ApkMetadata(packageName, versionCode, versionName, minSdk, meta)
    }

    private fun readMetaData(apk: File): Map<String, String> = read(apk).metaData

    // -- DER-ish helpers over the binary chunk stream --
    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, o: Int) =
        (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun chunkSize(d: ByteArray, offset: Int) = u32(d, offset + 4).toInt()

    /** The AXML string pool, which every other chunk indexes into. */
    private class StringPool(d: ByteArray) {
        private val values: List<String>
        init {
            val base = 8
            val count = (d[base + 8].toInt() and 0xFF) or
                ((d[base + 9].toInt() and 0xFF) shl 8) or
                ((d[base + 10].toInt() and 0xFF) shl 16) or
                ((d[base + 11].toInt() and 0xFF) shl 24)
            val flags = (d[base + 16].toInt() and 0xFF) or
                ((d[base + 17].toInt() and 0xFF) shl 8) or
                ((d[base + 18].toInt() and 0xFF) shl 16) or
                ((d[base + 19].toInt() and 0xFF) shl 24)
            val utf8 = flags and (1 shl 8) != 0
            val strStart = (d[base + 20].toInt() and 0xFF) or
                ((d[base + 21].toInt() and 0xFF) shl 8) or
                ((d[base + 22].toInt() and 0xFF) shl 16) or
                ((d[base + 23].toInt() and 0xFF) shl 24)
            val list = ArrayList<String>(count)
            for (i in 0 until count) {
                val rel = (d[base + 28 + 4 * i].toInt() and 0xFF) or
                    ((d[base + 29 + 4 * i].toInt() and 0xFF) shl 8) or
                    ((d[base + 30 + 4 * i].toInt() and 0xFF) shl 16) or
                    ((d[base + 31 + 4 * i].toInt() and 0xFF) shl 24)
                var p = base + strStart + rel
                if (utf8) {
                    var n = d[p].toInt(); p++
                    if (n and 0x80 != 0) p++
                    n = d[p].toInt(); p++
                    if (n and 0x80 != 0) n = ((n and 0x7F) shl 8) or (d[p].toInt() and 0xFF)
                    list.add(String(d, p, n, Charsets.UTF_8))
                } else {
                    val n = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
                    list.add(String(d, p + 2, n * 2, Charsets.UTF_16LE))
                }
            }
            values = list
        }

        val size: Int get() = values.size

        operator fun get(index: Long): String =
            if (index == 0xFFFFFFFFL || index < 0 || index >= values.size) "" else values[index.toInt()]
    }
}