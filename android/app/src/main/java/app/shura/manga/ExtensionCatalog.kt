package app.shura.manga

import app.shura.manga.ui.screens.ExtensionAction
import app.shura.manga.ui.screens.ExtensionRowState
import app.shura.runtime.RepositoryIndex
import app.shura.runtime.RepositoryPackage
import app.shura.runtime.TrustPolicy
import java.io.File

/** What the store can say about one extension. */
enum class ExtensionTrust {
    /** Signed by a publisher this build trusts, within its granted scope. */
    TRUSTED,

    /** Signed by a key this build does not know. Not runnable here. */
    UNKNOWN_PUBLISHER,

    /**
     * Signed by a publisher this build trusts, but for a package outside its
     * scope. The signature is real; the authority does not cover this package.
     */
    OUT_OF_SCOPE,

    /** Present in the index, but its artifact is not on the device. */
    NOT_DOWNLOADED,
}

/** One row in the extension store. */
data class ExtensionEntry(
    val pkg: String,
    val version: String,
    /** Comparable release number from the index; 0 when the index omits it. */
    val versionCode: Long = 0L,
    val lang: String,
    val identity: String,
    val trust: ExtensionTrust,
    val artifactPresent: Boolean,
    val signer: String,
    /** Absolute path of the installed artifact, or null when it is not there. */
    val artifactPath: String?,
    /** The file name the index records, which is also what a download fetches. */
    val artifactName: String,
    /** The hash the index published for the artifact, checked on download. */
    val publishedSha256: String,
)

/**
 * What the extension store shows, and why.
 *
 * The store lists the extensions the installed repository offers, each with the
 * trust verdict that decides whether this build will run it. The verdict comes
 * from the same [TrustPolicy] the loader enforces, so a row cannot read
 * "trusted" for an extension that later refuses to load.
 *
 * Deliberately separate from the UI: a store that shows an install button for
 * something the trust policy refuses is the exact bug this models - the button
 * looks real and does nothing.
 */
class ExtensionCatalog(
    private val policy: TrustPolicy,
    val directory: File,
) {

    /**
     * Reads a repository's index and describes every extension in it.
     *
     * A missing, unreadable or malformed index is an empty catalogue, not a
     * crash: an absent repository is a state the user can fix by installing
     * one, and it must not take the screen down.
     */
    fun describe(repositoryDir: File): List<ExtensionEntry> {
        val index = File(repositoryDir, INDEX_FILE)
        if (!index.isFile) return emptyList()
        val packages = runCatching {
            RepositoryIndex.parse(index.readText())
        }.getOrElse { return emptyList() }
        return packages.map { describe(it) }
            .sortedWith(compareBy({ it.identity.lowercase() }, { it.pkg }))
    }

    /**
     * Where an artifact actually is on disk.
     *
     * The index records a bare file name, and every writer puts artifacts in
     * `apk/` beneath the repository - the installer, the client and the
     * publisher all agree on that. Resolving the index value against the
     * repository root instead made every row read "not on device", including
     * fully installed and fully trusted ones, which is worse than no store.
     */
    private fun artifactOf(pkg: RepositoryPackage): File? {
        val name = pkg.apk
        if (name.isBlank()) return null
        // A name may not escape the apk/ directory. The index is remote input.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(File(directory, APK_DIR), name).takeIf { it.isFile }
    }

    private fun describe(pkg: RepositoryPackage): ExtensionEntry {
        val signer = pkg.signingCertificateSha256
        // Not TrustPolicy.publisherFor: that throws when nothing is pinned for
        // a package, and this screen exists precisely to show that case.
        val publisher = policy.publishers.firstOrNull { it.covers(pkg.pkg) }
        val knownSigner = policy.publishers.firstOrNull { it.digest.equals(signer, true) }
        val artifact = artifactOf(pkg)
        val present = artifact != null
        val trust = when {
            !present -> ExtensionTrust.NOT_DOWNLOADED
            signer.isBlank() -> ExtensionTrust.UNKNOWN_PUBLISHER
            publisher != null && publisher.digest.equals(signer, true) -> ExtensionTrust.TRUSTED
            knownSigner != null -> ExtensionTrust.OUT_OF_SCOPE
            else -> ExtensionTrust.UNKNOWN_PUBLISHER
        }
        return ExtensionEntry(
            pkg = pkg.pkg,
            version = pkg.version,
            versionCode = pkg.indexVersionCode,
            lang = pkg.lang,
            identity = pkg.identity.ifBlank { pkg.pkg },
            trust = trust,
            artifactPresent = present,
            signer = signer.take(12),
            /** Null when the index names something unsafe or absent. */
            artifactPath = artifact?.path,
            artifactName = pkg.apk,
            publishedSha256 = pkg.artifactSha256,
        )
    }

    /** Extensions this build will actually run. */
    fun runnable(entries: List<ExtensionEntry>): List<ExtensionEntry> =
        entries.filter { it.trust == ExtensionTrust.TRUSTED }

    /** The reason this row is not runnable, in the user's terms. */
    fun whyNot(entries: List<ExtensionEntry>, wanted: ExtensionEntry): String? {
        val entry = entries.firstOrNull {
            it.pkg == wanted.pkg && it.version == wanted.version
        } ?: wanted
        return when (entry.trust) {
            ExtensionTrust.TRUSTED -> null
            ExtensionTrust.NOT_DOWNLOADED ->
                "Its file is not on this device yet. Install the repository folder again to fetch it."
            ExtensionTrust.OUT_OF_SCOPE ->
                "Its publisher is trusted, but not for ${entry.pkg}. This build will not run it."
            ExtensionTrust.UNKNOWN_PUBLISHER ->
                "Signed by a key this build does not trust (${entry.signer}). Refused."
        }
    }

    companion object {
        const val INDEX_FILE = "index.shura.json"

        /** Where the installer, the client and the publisher all put APKs. */
        const val APK_DIR = "apk"

        /** Builds a catalogue against the policy this build actually enforces. */
        fun forBuild(context: android.content.Context): ExtensionCatalog =
            ExtensionCatalog(ShuraExtensions.policy, ShuraRepository.directory(context))

        /** The repository this catalogue reads. */
        fun defaultDirectory(context: android.content.Context): File =
            ShuraRepository.directory(context)

        /**
         * Every version the index offers, newest first per package.
         *
         * The index carries one row per release, so two rows for the same
         * package are an update, not two extensions. Grouped here so the store
         * can offer "update" rather than showing the same extension twice.
         */
        /**
         * What each row can be asked to do, decided from the index and what is
         * actually on the device.
         *
         * One action per row, and always the true one: install when nothing is
         * installed, update when the index offers more than the row on show,
         * remove when what is installed is current. Offering "install" for an
         * extension that is already installed is how a store starts telling
         * the user something untrue.
         */
        fun rowStates(entries: List<ExtensionEntry>): List<ExtensionRowState> =
            newestPerPackage(entries).map { entry ->
                val versions = entries.count { it.pkg == entry.pkg }
                val installed = entry.artifactPresent
                ExtensionRowState(
                    entry = entry,
                    action = when {
                        !installed -> ExtensionAction.INSTALL
                        versions > 1 -> ExtensionAction.UPDATE
                        else -> ExtensionAction.REMOVE
                    },
                    installedVersion = if (installed) entry.version else null,
                    progress = null,
                    message = null,
                    failed = false,
                )
            }

        fun newestPerPackage(entries: List<ExtensionEntry>): List<ExtensionEntry> =
            entries.groupBy { it.pkg }
                .map { (_, versions) ->
                    versions.maxByOrNull { it.versionCode } ?: versions.first()
                }
                .sortedWith(compareBy({ it.identity.lowercase() }, { it.pkg }))
    }
}