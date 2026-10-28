package app.shura.manga

import android.content.Context
import app.shura.runtime.ShuraRepositoryInstaller
import java.io.File
import java.io.IOException

/**
 * Puts the repository Shura ships with onto the device, once.
 *
 * WHY THIS EXISTS
 *
 * The store listed what the repository index offered and had no way to make
 * any of it real: there was no download, and `filesDir/repo` started empty on
 * every install, so a fresh app showed an empty store and the only way to
 * populate it was to pick the folder by hand through the system picker. The
 * extensions existed, the index named them, and the user could not reach them.
 *
 * So the repository ships inside the APK's assets and is unpacked on first run.
 * It is a real repository, not a stub: the same index, the same artifacts,
 * installed through the same directory the loader and the installer read.
 *
 * WHAT THIS IS NOT
 *
 * Not a trust shortcut. Every artifact still goes through
 * [app.shura.runtime.ShuraRepositoryClient] before it can load, against the
 * same pinned publisher policy, reading the certificate out of the APK. Being
 * bundled changes where the bytes come from, not whether they are checked.
 *
 * Not an update path. A newer version of the app carries a newer index and the
 * unpack runs again when the shipped index differs from the installed one, so
 * an app update does upgrade the extensions it ships. Anything newer than what
 * this build carries still needs the store's install action.
 */
object RepositorySeed {

    private const val ASSET_ROOT = "repository"

    /** True when this build carries a repository at all. */
    fun isPresent(context: Context): Boolean = try {
        context.assets.list(ASSET_ROOT)?.contains("index.shura.json") == true
    } catch (_: Exception) {
        false
    }

    /**
     * Unpacks the shipped repository if it is not already installed.
     *
     * Compared by index content rather than a flag file, so a build that ships
     * a different index replaces what is there instead of being skipped - the
     * flag-file approach silently kept a stale repository forever.
     *
     * Never overwrites a repository the user installed themselves: a shipped
     * repository is a floor, not an authority over what the user chose.
     */
    fun seed(context: Context): Boolean {
        if (!isPresent(context)) return false
        val target = ShuraRepository.directory(context)
        val shipped = readAsset(context, "index.shura.json")
            ?: throw IOException("the bundled repository index could not be read")
        val installed = File(target, ExtensionCatalog.INDEX_FILE)
            .takeIf { it.isFile }?.readText()
        if (installed == shipped) return false

        // Anything the user put there wins: they may have installed a newer
        // repository by hand, and silently reverting it would take their
        // extensions away.
        if (installed != null) return false

        val staging = File(context.cacheDir, "repository-seed")
        staging.deleteRecursively()
        return try {
            if (!copyAssetTree(context, ASSET_ROOT, staging)) {
                throw IOException("the bundled repository could not be copied")
            }
            val report = ShuraRepositoryInstaller.install(staging, target)
            if (!report.ok) {
                throw IOException(report.refused ?: "the bundled repository was refused")
            }
            true
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyAssetTree(context: Context, path: String, into: File): Boolean {
        into.mkdirs()
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // A leaf: copy it across.
            context.assets.open(path).use { input ->
                into.outputStream().use { output -> input.copyTo(output) }
            }
            return true
        }
        for (child in children) {
            if (!copyAssetTree(context, "$path/$child", File(into, child))) return false
        }
        return true
    }

    private fun readAsset(context: Context, path: String): String? = try {
        context.assets.open("$ASSET_ROOT/$path").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }
}