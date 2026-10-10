package app.shura.runtime

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.source.CatalogueSource
import java.io.File
import java.util.zip.ZipFile

/** Why a load failed. Load/link/creation problems are never swallowed. */
sealed class LoadFailure(message: String) : Exception(message) {
    class NotVerified(override val message: String) : LoadFailure(message)
    class Manifest(override val message: String) : LoadFailure(message)
    class AbiUnsupported(val abi: String, val min: Float, val max: Float) :
        LoadFailure("extension declares extensionLib $abi, this runtime supports $min..$max")
    class ClassNotFound(val className: String, val underlying: String) :
        LoadFailure("source class $className is not in the APK: $underlying")
    class NotInstantiable(val className: String, val reason: String) :
        LoadFailure("could not instantiate $className: $reason")
    class WrongType(val className: String, val actual: String) :
        LoadFailure("$className loaded but is a $actual, not a Source")
}

/** A verified extension, ready to be handed to [ExtensionLoader]. */
data class VerifiedExtension(
    val apk: File,
    val packageName: String,
    val metadata: ApkMetadata,
    val abi: Float,
    val sourceClassName: String,
)

/** An extension that has been loaded and instantiated. */
class LoadedExtension(
    val descriptor: VerifiedExtension,
    val source: CatalogueSource,
    private val classLoader: ClassLoader,
) {
    val id: String get() = descriptor.packageName
    val name: String get() = source.name
    val language: String get() = source.lang

    /** Releases the class loader's own optimized dex cache. */
    fun close() {
        runCatching {
            val method = classLoader.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
            method?.invoke(classLoader)
        }
    }
}

/**
 * Loads extension APKs published by Shura Core.
 *
 * SECURITY: a per-extension ClassLoader separates class *names*. It is not a
 * sandbox. Loaded code runs with Shura's own uid, permissions and filesystem
 * access, and can reach anything Shura can. Signature verification proves which
 * certificate signed an artifact; it says nothing about what that artifact does
 * once its code is running in this process.
 *
 * The parent is Shura's own class loader on purpose: the extension binds against
 * eu.kanade.tachiyomi.* types and okhttp/kotlinx which live in the host. Parent
 * delegation keeps exactly one copy of each of those types, so a class defined
 * here can never be shadowed by a second copy loaded out of the APK.
 */
class ExtensionLoader(
    private val context: Context,
    private val minExtensionLib: Float = 1.0f,
    private val maxExtensionLib: Float = 1.6f,
) {

    /** Where verified APKs live. Internal storage, never the public cache. */
    fun extensionsDir(): File = File(context.filesDir, "extensions").apply { mkdirs() }

    /**
     * Stages a verified APK into internal storage and prepares its native library.
     *
     * The APK is copied rather than referenced in place so the file the class
     * loader opens is the one that was hashed.
     */
    fun stage(verified: VerifiedExtension): File {
        val dir = File(extensionsDir(), verified.packageName).apply { mkdirs() }
        val target = File(dir, "base.apk")
        verified.apk.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        extractNativeLibrary(verified, dir)
        return target
    }

    /**
     * Picks the `.so` matching the device ABI and extracts it to a directory the
     * loader can be pointed at.
     *
     * The shipped extensions carry libavif_android.so for arm64-v8a,
     * armeabi-v7a, x86 and x86_64. Whether the library can actually be loaded
     * also depends on the app packaging its own ABIs, which is separate from
     * abiFilters alone; this returns null rather than guessing when no ABI
     * matches, and the caller reports it.
     */
    fun extractNativeLibrary(verified: VerifiedExtension, dir: File): File? {
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        ZipFile(verified.apk).use { zip ->
            val entry = zip.getEntry("lib/$deviceAbi/libavif_android.so") ?: return null
            val out = File(dir, "lib").apply { mkdirs() }
            val target = File(out, "libavif_android.so")
            zip.getInputStream(entry).use { i -> target.outputStream().use { i.copyTo(it) } }
            return target.parentFile
        }
    }

    /** Loads and instantiates the source class named by the manifest. */
    fun load(verified: VerifiedExtension): LoadedExtension {
        val abi = verified.abi
        if (abi < minExtensionLib || abi > maxExtensionLib) {
            throw LoadFailure.AbiUnsupported(abi.toString(), minExtensionLib, maxExtensionLib)
        }
        val apkFile = stage(verified)
        val nativeDir = extractNativeLibrary(verified, apkFile.parentFile!!)
        val optimized = File(apkFile.parentFile, "dex").apply { mkdirs() }

        val loader = dalvik.system.DexClassLoader(
            apkFile.absolutePath,
            optimized.absolutePath,
            nativeDir?.absolutePath,
            ExtensionLoader::class.java.classLoader!!,
        )

        val clazz = try {
            loader.loadClass(verified.sourceClassName)
        } catch (e: ClassNotFoundException) {
            throw LoadFailure.ClassNotFound(verified.sourceClassName, e.toString())
        }

        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: NoSuchMethodException) {
            throw LoadFailure.NotInstantiable(verified.sourceClassName, "no no-arg constructor")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw LoadFailure.NotInstantiable(verified.sourceClassName, e.targetException?.toString() ?: "constructor threw")
        } catch (e: Exception) {
            throw LoadFailure.NotInstantiable(verified.sourceClassName, e.toString())
        }

        if (instance !is CatalogueSource) {
            throw LoadFailure.WrongType(verified.sourceClassName, instance.javaClass.name)
        }
        return LoadedExtension(verified, instance, loader)
    }

    /**
     * Turns the manifest's `tachiyomi.extension.class` into a binary class name.
     *
     * The value is relative to the package (`.ProComic`), so it is resolved
     * against the APK's own package rather than guessed.
     */
    fun resolveSourceClass(metadata: ApkMetadata): String {
        val raw = metadata.metaData["tachiyomi.extension.class"]
            ?: throw LoadFailure.Manifest("no tachiyomi.extension.class in the manifest")
        val relative = raw.removePrefix(".")
        return if (relative.contains(".")) relative else "${metadata.packageName}.$relative"
    }
}