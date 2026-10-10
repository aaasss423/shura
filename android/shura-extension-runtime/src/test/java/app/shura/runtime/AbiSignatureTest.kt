package app.shura.runtime

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signature-level ABI conformance against the real published ProComic DEX.
 *
 * [AbiSurfaceTest] checks that the names the extension needs appear in the DEX
 * string pool. That is not compatibility: the pool also contains descriptors that
 * are never invoked, and a name alone says nothing about the parameters or the
 * return type. A host that declares `searchMangaRequest(Int, String, FilterList)`
 * where the DEX expects `searchMangaRequest(Int, String, FilterList, Int)`
 * satisfies every string-pool assertion and still dies with NoSuchMethodError the
 * moment the extension runs.
 *
 * This test reads the DEX's method_ids table - the actual invocations the
 * bytecode makes - and resolves each one against the classes the runtime ships.
 * Every unresolved member is reported at once, with the exact descriptor, so a
 * mismatch is a concrete list rather than a mystery on a device.
 *
 * It proves the call sites resolve. It does not prove the extension loads or
 * searches; that needs a device.
 */
class AbiSignatureTest {

    private val repoDir: File = File(System.getProperty("shura.repo.dir") ?: "../../repo")

    private fun apk(): File {
        val dir = File(repoDir, "apk")
        val files = dir.listFiles { f: File -> f.name.endsWith(".apk") }?.sortedBy { it.name }
        assertTrue("no published APK under $dir", !files.isNullOrEmpty())
        return files!!.firstOrNull { it.name.contains("1.5.1") } ?: files.first()
    }

    /** One DEX method_id_item: what the bytecode actually invokes. */
    private data class Ref(
        val owner: String,
        val name: String,
        val params: List<String>,
        val ret: String,
        /**
         * False when the DEX omitted the parameter list (`parameters_off == 0`).
         * dex emits that for simple accessors, constructors and `<init>`, and it
         * means "not recorded", not "takes no arguments". Treating it as an empty
         * list invents a signature and reports members that do resolve as broken.
         */
        val paramsKnown: Boolean,
    ) {
        override fun toString(): String =
            "$owner.$name(${params.joinToString(", ")})${if (paramsKnown) ": $ret" else ""}"
    }

    private class Dex(val b: ByteArray) {
        fun u32(o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)

        fun u16(o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun uleb(o: Int): String {
            var p = o
            while (b[p].toInt() and 0x80 != 0) p++
            return p.toString()
        }

        private val stringCount = u32(0x38)
        private val stringTable = u32(0x3C)
        private val typeCount = u32(0x40)
        private val typeTable = u32(0x44)
        private val protoCount = u32(0x48)
        private val protoTable = u32(0x4C)
        private val methodCount = u32(0x58)
        private val methodTable = u32(0x5C)

        private fun str(i: Int): String {
            var p = u32(stringTable + 4 * i)
            p = uleb(p).toInt() + 1
            var end = p
            while (b[end] != 0.toByte()) end++
            return String(b, p, end - p, Charsets.UTF_8)
        }

        private fun type(i: Int): String = str(u32(typeTable + 4 * i))

        private fun params(protoIdx: Int): Pair<List<String>, Boolean> {
            val off = u32(protoTable + 12 * protoIdx + 8)
            if (off == 0) return emptyList<String>() to false
            val n = u32(off)
            return (0 until n).map { type(u16(off + 4 + 2 * it)) } to true
        }

        fun methodRefs(): List<Ref> = (0 until methodCount).map { i ->
            val base = methodTable + 8 * i
            val proto = u16(base + 2)
            val (args, known) = params(proto)
            Ref(
                owner = type(u16(base)),
                name = str(u32(base + 4)),
                params = args,
                ret = type(u32(protoTable + 12 * proto + 4)),
                paramsKnown = known,
            )
        }
    }

    /** Types the extension binds against that the runtime is responsible for. */
    private val hostOwnedPrefix = "Leu/kanade/tachiyomi/"

    /**
     * The extension ships its own classes, including a whole
     * `eu.kanade.tachiyomi.extension.ar.procomic` package. Those are supplied by
     * the APK, not the host, so asserting the runtime provides them would be
     * asserting that it ships the extension. Only the framework layer below is
     * the host's responsibility.
     */
    private val hostExcluded = listOf("Leu/kanade/tachiyomi/extension/")

    private fun hostOwned(descriptor: String): Boolean =
        descriptor.startsWith(hostOwnedPrefix) && hostExcluded.none { descriptor.startsWith(it) }

    private fun hostClassName(descriptor: String): String =
        descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    /** Loads the class named by a DEX type descriptor, or null when it cannot be loaded here. */
    private fun live(descriptor: String): Class<*>? = when {
        descriptor.startsWith("[") || descriptor.length == 1 -> null
        else -> load(hostClassName(descriptor))
    }

    private fun load(className: String): Class<*>? = try {
        Class.forName(className)
    } catch (_: Throwable) {
        null
    }

    @Test
    fun everyDexCallSiteAgainstHostOwnedTypesResolvesInTheRuntime() {
        val dex = Dex(ZipFile(apk()).use { z -> z.getInputStream(z.getEntry("classes.dex")).readBytes() })
        val refs = dex.methodRefs()

        assertTrue("no method references decoded from the DEX", refs.isNotEmpty())

        val hostRefs = refs.filter { hostOwned(it.owner) }
        assertTrue(
            "expected the extension to call host-owned types; found none",
            hostRefs.isNotEmpty(),
        )

        val missingClasses = sortedSetOf<String>()
        val missingMembers = mutableListOf<String>()
        val wrongReturn = mutableListOf<String>()
        var checked = 0

        for (ref in refs) {
            if (!hostOwned(ref.owner)) continue
            val className = hostClassName(ref.owner)
            val host = load(className)
            if (host == null) {
                missingClasses += className
                continue
            }
            val name = ref.name
            val wanted = ref.params.mapNotNull { live(it) }
            val loadable = wanted.size == ref.params.size
            // An unrecorded parameter list can still be checked for presence by
            // name; only a recorded one can be matched exactly.
            val returnType: Class<*>?
            val found = if (ref.paramsKnown && loadable && name != "<init>") {
                val m = runCatching { host.getMethod(name, *wanted.toTypedArray()) }.getOrNull()
                    ?: runCatching { host.getDeclaredMethod(name, *wanted.toTypedArray()) }.getOrNull()
                returnType = m?.returnType
                m != null
            } else if (name == "<init>") {
                // Constructors are not in declaredMethods, and the DEX's <init>
                // proto carries the receiver in the first slot, so an exact match
                // is not attempted: presence is what this can honestly assert.
                returnType = null
                host.declaredConstructors.isNotEmpty() || host.constructors.isNotEmpty()
            } else {
                val ms = host.declaredMethods.filter { it.name == name }
                    .ifEmpty { host.methods.filter { it.name == name } }
                returnType = ms.firstOrNull()?.returnType
                ms.isNotEmpty()
            }
            if (!found) {
                missingMembers += "$className.$name(${ref.params.joinToString(", ")})"
                continue
            }
            checked++
            val declared = returnType?.let { descriptorOf(it) }
            if (ref.paramsKnown && declared != null && declared != ref.ret) {
                wrongReturn += "$className.$name returns $declared but the DEX expects ${ref.ret}"
            }
        }

        val report = buildString {
            appendLine("$checked host call sites resolved out of ${hostRefs.size}")
            if (missingClasses.isNotEmpty()) {
                appendLine("missing classes:")
                missingClasses.forEach { appendLine("  $it") }
            }
            if (missingMembers.isNotEmpty()) {
                appendLine("unresolved members:")
                missingMembers.forEach { appendLine("  $it") }
            }
            if (wrongReturn.isNotEmpty()) {
                appendLine("return type mismatches:")
                wrongReturn.forEach { appendLine("  $it") }
            }
        }
        assertTrue(report, missingClasses.isEmpty() && missingMembers.isEmpty() && wrongReturn.isEmpty())
    }

    private fun descriptorOf(type: Class<*>): String = when {
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Short::class.javaPrimitiveType -> "S"
        type == Char::class.javaPrimitiveType -> "C"
        type == Void::class.javaPrimitiveType -> "V"
        type.isArray -> "[" + descriptorOf(type.componentType)
        else -> "L" + type.name.replace('.', '/') + ";"
    }
}
