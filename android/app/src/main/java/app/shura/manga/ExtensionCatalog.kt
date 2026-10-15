package app.shura.manga

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
    val lang: String,
    val identity: String,
    val trust: ExtensionTrust,
    val artifactPresent: Boolean,
    val signer: String,
    /** The index-relative path of the artifact, as the repository recorded it. */
    val apkPath: String,
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

    private fun describe(pkg: RepositoryPackage): ExtensionEntry {
        val signer = pkg.signingCertificateSha256
        // Not TrustPolicy.publisherFor: that throws when nothing is pinned for
        // a package, and this screen exists precisely to show that case.
        val publisher = policy.publishers.firstOrNull { it.covers(pkg.pkg) }
        val knownSigner = policy.publishers.firstOrNull { it.digest.equals(signer, true) }
        val present = File(directory, pkg.apk).isFile
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
            lang = pkg.lang,
            identity = pkg.identity.ifBlank { pkg.pkg },
            trust = trust,
            artifactPresent = present,
            signer = signer.take(12),
            apkPath = pkg.apk,
        )
    }

    /** Extensions this build will actually run. */
    fun runnable(entries: List<ExtensionEntry>): List<ExtensionEntry> =
        entries.filter { it.trust == ExtensionTrust.TRUSTED }

    /** The reason a row is not runnable, in the user's terms. */
    fun whyNot(entries: List<ExtensionEntry>, pkg: String): String? {
        val entry = entries.firstOrNull { it.pkg == pkg } ?: return null
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

        /** Builds a catalogue against the policy this build actually enforces. */
        fun forBuild(context: android.content.Context): ExtensionCatalog =
            ExtensionCatalog(ShuraExtensions.policy, ShuraRepository.directory(context))

        /** The repository this catalogue reads. */
        fun defaultDirectory(context: android.content.Context): File =
            ShuraRepository.directory(context)
    }
}