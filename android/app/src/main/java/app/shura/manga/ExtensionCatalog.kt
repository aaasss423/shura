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

/**
 * The repository an extension came from.
 *
 * Carried on the entry rather than looked up later, because the whole point of
 * having more than one repository is that each package is fetched from the one
 * that published it: an entry that did not say where it came from could only be
 * resolved against a directory that holds everything, which is the single merged
 * store that makes "remove repository" untrue.
 */
data class RepositoryOrigin(
    val id: String,
    val url: String,
    val directory: File,
)

/** One repository the catalogue reads: where it is, and whether it is named. */
data class CatalogRepository(
    val directory: File,
    /** Null for the repository that ships with the app. */
    val origin: RepositoryOrigin? = null,
)

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
    /** Where this package was published, or null for the shipped repository. */
    val origin: RepositoryOrigin? = null,
)

/**
 * What the extension store shows, and why.
 *
 * The store lists the extensions the installed repositories offer, each with the
 * trust verdict that decides whether this build will run it. The verdict comes
 * from the same [TrustPolicy] the loader enforces, so a row cannot read
 * "trusted" for an extension that later refuses to load.
 *
 * Deliberately separate from the UI: a store that shows an install button for
 * something the trust policy refuses is the exact bug this models - the button
 * looks real and does nothing.
 *
 * MORE THAN ONE REPOSITORY
 *
 * [extraRepositories] are read as well as [directory], and nothing is merged:
 * each repository keeps its own directory, each entry keeps the [RepositoryOrigin]
 * it was read from, and an artifact is only ever looked for in the repository
 * that published it. So removing one repository removes exactly its packages -
 * there is no copy of them anywhere else for the store to fall back to.
 */
class ExtensionCatalog(
    private val policy: TrustPolicy,
    val directory: File,
    private val extraRepositories: List<CatalogRepository> = emptyList(),
) {

    /**
     * Every repository this catalogue reads: the one that ships with the app
     * first, then the registered ones in the order they were added.
     */
    fun sources(): List<CatalogRepository> =
        listOf(CatalogRepository(directory)) + extraRepositories

    /**
     * Reads a repository's index and describes every extension in it.
     *
     * Every row the index offers, not one row per package: collapsing happens
     * where a decision about it is made ([rowStates], and [merge] across
     * repositories), so the catalogue keeps answering "what does this index
     * say" truthfully even when the answer is two versions of one thing.
     *
     * A missing, unreadable or malformed index is an empty catalogue, not a
     * crash: an absent repository is a state the user can fix by installing
     * one, and it must not take the screen down.
     */
    fun describe(repositoryDir: File): List<ExtensionEntry> =
        describeOne(CatalogRepository(repositoryDir))

    /**
     * Reads every repository and describes every extension in all of them.
     *
     * One malformed index costs that repository its rows and nothing else: the
     * others are still described, because a repository that has stopped being
     * readable is not a reason to empty the store.
     */
    fun describeAll(repositories: List<CatalogRepository> = sources()): List<ExtensionEntry> =
        merge(repositories.map { describeOne(it) })

    /** One repository's rows, on their own, before anything is merged. */
    private fun describeOne(repository: CatalogRepository): List<ExtensionEntry> {
        val index = File(repository.directory, INDEX_FILE)
        if (!index.isFile) return emptyList()
        val packages = runCatching {
            RepositoryIndex.parse(index.readText())
        }.getOrElse { return emptyList() }
        return packages.map { describe(it, repository) }
            .sortedWith(compareBy({ it.identity.lowercase() }, { it.pkg }))
    }

    /**
     * The store's list, out of every repository's list.
     *
     * One row per package, the newest version offered by any of them. Two
     * repositories publishing the same package is not two extensions: the
     * higher version wins, and a tie goes to the repository that comes first -
     * the shipped one, which is also the one the loader will have read first.
     *
     * Pure over its arguments, so the rule can be pinned without a device.
     */
    fun merge(perRepository: List<List<ExtensionEntry>>): List<ExtensionEntry> {
        val all = perRepository.flatten()
        val newest = all.groupBy { it.pkg }
            .map { (_, versions) ->
                versions.maxWithOrNull(compareBy({ it.versionCode }, { 0 }))
                    ?: versions.first()
            }
        return newest.sortedWith(compareBy({ it.identity.lowercase() }, { it.pkg }))
    }

    /**
     * Where an artifact actually is on disk.
     *
     * The index records a bare file name, and every writer puts artifacts in
     * `apk/` beneath the repository - the installer, the client and the
     * publisher all agree on that. Resolving the index value against the
     * repository root instead made every row read "not on device", including
     * fully installed and fully trusted ones, which is worse than no store.
     *
     * Resolved against the repository that published the entry, never against
     * the catalogue's first directory: a package offered by two repositories is
     * on the device if *its* repository has the file.
     */
    private fun artifactOf(pkg: RepositoryPackage, repository: CatalogRepository): File? {
        val name = pkg.apk
        if (name.isBlank()) return null
        // A name may not escape the apk/ directory. The index is remote input.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(File(repository.directory, APK_DIR), name).takeIf { it.isFile }
    }

    private fun describe(pkg: RepositoryPackage, repository: CatalogRepository): ExtensionEntry {
        val signer = pkg.signingCertificateSha256
        // Not TrustPolicy.publisherFor: that throws when nothing is pinned for
        // a package, and this screen exists precisely to show that case.
        val publisher = policy.publishers.firstOrNull { it.covers(pkg.pkg) }
        val knownSigner = policy.publishers.firstOrNull { it.digest.equals(signer, true) }
        val artifact = artifactOf(pkg, repository)
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
            origin = repository.origin,
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
            ExtensionCatalog(
                ShuraExtensions.policy,
                ShuraRepository.directory(context),
                ShuraRepository.remoteSources(context),
            )

        /** The repository this catalogue reads. */
        fun defaultDirectory(context: android.content.Context): File =
            ShuraRepository.directory(context)

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

        /**
         * One row per package: the newest version the index offers.
         *
         * The index carries one row per release, so two rows for the same
         * package are an update, not two extensions. Grouped here so the store
         * can offer "update" rather than showing the same extension twice.
         */
        fun newestPerPackage(entries: List<ExtensionEntry>): List<ExtensionEntry> =
            entries.groupBy { it.pkg }
                .map { (_, versions) ->
                    versions.maxByOrNull { it.versionCode } ?: versions.first()
                }
                .sortedWith(compareBy({ it.identity.lowercase() }, { it.pkg }))
    }
}