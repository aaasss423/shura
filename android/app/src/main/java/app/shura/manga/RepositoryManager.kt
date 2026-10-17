package app.shura.manga

import app.shura.runtime.LoadFailure
import app.shura.runtime.RepositoryMeta
import app.shura.runtime.ShuraRepositoryClient
import app.shura.runtime.ShuraRepositoryInstaller
import app.shura.runtime.TrustPolicy
import java.io.File

/** What adding or refreshing a repository did. */
sealed interface RepositoryOutcome {
    /**
     * It was fetched, its anchor passed, and it is now registered.
     *
     * [added] distinguishes "a repository this app did not have" from "the one
     * that was already there, fetched again", because those are different things
     * to have done and a reader who added an address a second time should not be
     * told a repository appeared when nothing did.
     */
    data class Registered(
        val record: RepositoryRecord,
        val added: Boolean,
    ) : RepositoryOutcome

    /**
     * It was refused.
     *
     * A refusal never registers anything: the files are deleted and the record,
     * if there was one, keeps the previous repository it pointed at. A failed
     * update must not leave a half-written repository where a working one was.
     */
    data class Refused(
        val record: RepositoryRecord?,
        val refusal: RepositoryRefusal,
        val detail: String,
    ) : RepositoryOutcome
}

/**
 * Adding, refreshing and removing repositories that live somewhere else.
 *
 * WHAT THIS IS AND IS NOT
 *
 * This is the part that moves bytes. It decides nothing about whether they are
 * good: the fetched `repo.json` and `index.shura.json` are handed to the
 * existing [ShuraRepositoryClient], which runs the anchor check the build has
 * always run, and only what that accepts is registered. A repository that fails
 * the anchor is refused with the client's own words, never quietly kept.
 *
 * WHY ONLY THE TWO METADATA FILES ARE FETCHED
 *
 * The APK is fetched when the user installs that extension, by the path that
 * already exists: it downloads, compares the digest the index published,
 * checks the signing certificate against the same policy the loader uses, and
 * writes the artifact into *that repository's* directory. Fetching every
 * artifact up front would mean downloading a whole repository - tens of
 * megabytes for the one this project publishes - before the reader has asked
 * for a single extension, and would leave nothing for that check to do.
 *
 * [ShuraRepositoryInstaller] is therefore not on this path: it installs a whole
 * repository at once and refuses a folder that does not carry every artifact
 * its index names. That is the right rule for the folder and asset installs,
 * which are unchanged, and the wrong one for a URL.
 */
class RepositoryManager(
    val registry: RepositoryRegistry,
    private val policy: TrustPolicy,
    private val allowCleartext: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val download: RepositoryDownload = RepositoryDownload(allowCleartext),
) {

    /** Every registered repository, newest first in the order they were added. */
    fun list(): List<RepositoryRecord> = registry.records()

    /**
     * Registers a repository at [rawUrl].
     *
     * Adding an address that is already registered re-fetches it, because the
     * thing the user asked for - a repository at this address - is the same
     * either way and two rows for one address would be one too many.
     */
    fun add(rawUrl: String): RepositoryOutcome {
        val parsed = RepositoryAddress.parse(rawUrl, allowCleartext)
        val address = when (parsed) {
            is RepositoryAddress.Result.Success -> parsed.address
            is RepositoryAddress.Result.Failure -> return RepositoryOutcome.Refused(
                record = null,
                refusal = if (parsed.problem == AddressProblem.NOT_HTTPS) {
                    RepositoryRefusal.NOT_HTTPS
                } else {
                    RepositoryRefusal.BAD_URL
                },
                detail = parsed.problem.name,
            )
        }
        return fetchInto(address, existing = registry.records().firstOrNull { it.id == address.id })
    }

    /**
     * Fetches a registered repository again.
     *
     * The files on disk are only replaced once the new ones have passed the
     * same check, so a repository that stops being fetchable keeps the
     * extensions it already had rather than becoming an empty directory.
     */
    fun update(id: String): RepositoryOutcome {
        val record = registry.records().firstOrNull { it.id == id }
            ?: return RepositoryOutcome.Refused(null, RepositoryRefusal.BAD_URL, "no repository is registered under $id")
        val parsed = RepositoryAddress.parse(record.url, allowCleartext)
        val address = when (parsed) {
            is RepositoryAddress.Result.Success -> parsed.address
            is RepositoryAddress.Result.Failure ->
                // A record's own address no longer parses: it is still on the
                // list, and it is still refused.
                return refuse(record, RepositoryRefusal.BAD_URL, parsed.problem.name)
        }
        return fetchInto(address, existing = record)
    }

    /**
     * Removes a repository: its directory and its line in the list.
     *
     * Its packages stop being offered because the directory the store reads them
     * from is gone, and the extensions installed from it go with it - which is
     * what removing a repository means, and the reason nothing is merged into
     * the bundled directory behind its back.
     *
     * Returns false when the files could not be deleted. Then the record stays,
     * because a row that disappeared while its extensions were still installed
     * is the one lie this whole design exists to avoid.
     */
    fun remove(id: String): Boolean = registry.forget(id)

    // -- the fetch ----------------------------------------------------------

    private fun fetchInto(
        address: RepositoryAddress,
        existing: RepositoryRecord?,
    ): RepositoryOutcome {
        val stagingFor = File(File(registry.root, STAGING), address.id)
        stagingFor.deleteRecursively()
        stagingFor.mkdirs()
        try {
            // The two files the client reads. Nothing else is taken, and the
            // size each may occupy is the same bound the installer applies to a
            // repository's metadata.
            for (name in REPOSITORY_FILES) {
                download.fetch(
                    address, name, File(stagingFor, name),
                    ShuraRepositoryInstaller.MAX_INDEX_BYTES,
                )
            }
            // A file that is not JSON at all is a repository this build cannot
            // read, and reporting it as "the address could not be reached" would
            // be a lie: the bytes arrived, they were simply not the thing the
            // button promised. Checked before the client is asked, so the reason
            // the row shows is the reason the fetch failed.
            for (name in REPOSITORY_FILES) {
                val text = runCatching { File(stagingFor, name).readText() }.getOrNull()
                if (text == null || runCatching { org.json.JSONObject(text) }.isFailure) {
                    return refuse(
                        existing, RepositoryRefusal.UNSUPPORTED_INDEX,
                        "$name did not arrive as JSON this build can read",
                    )
                }
            }
            // The existing check, unchanged, run against the bytes that just
            // arrived: the anchor pinned in the build is checked against what
            // the repository declares before a single entry is looked at.
            val entries = ShuraRepositoryClient(stagingFor, null, policy).entries()
            if (entries.isEmpty()) {
                return refuse(existing, RepositoryRefusal.EMPTY_INDEX, "the repository publishes no extensions")
            }
            val name = runCatching {
                RepositoryMeta.parse(File(stagingFor, "repo.json").readText()).name
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: address.host

            val target = registry.directoryOf(address.id)
            if (!publish(stagingFor, target)) {
                return refuse(existing, RepositoryRefusal.INSTALL_FAILED, "$target could not be written")
            }
            val record = RepositoryRecord(
                id = address.id,
                url = address.base,
                name = name,
                status = RepositoryStatus.OK,
                checkedAt = now(),
                packages = entries.size,
                refusal = null,
                detail = null,
            )
            registry.upsert(record)
            return RepositoryOutcome.Registered(record, added = existing == null)
        } catch (e: RepositoryFetchException) {
            return refuse(existing, e.problem.fetchRefusal(), e.message.orEmpty())
        } catch (e: LoadFailure) {
            return refuse(existing, e.classify(), e.message ?: e::class.java.simpleName)
        } catch (e: Throwable) {
            return refuse(existing, RepositoryRefusal.NETWORK, e.message ?: "the repository could not be added")
        } finally {
            stagingFor.deleteRecursively()
        }
    }

    /** Moves the two verified files into the repository's own directory. */
    private fun publish(from: File, into: File): Boolean {
        return try {
            into.deleteRecursively()
            into.mkdirs()
            for (name in REPOSITORY_FILES) {
                val source = File(from, name)
                if (!source.isFile) return false
                source.copyTo(File(into, name), overwrite = true)
            }
            // A directory with no index in it is not a repository, and leaving one
            // behind would make the store read an empty list as "nothing here".
            File(into, "index.shura.json").isFile
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Records a refusal against [existing].
     *
     * The previous record is kept apart from its status: the check time moves
     * because a check did happen, and the package count does not, because the
     * files it counted are still on disk and still offered.
     */
    private fun refuse(
        existing: RepositoryRecord?,
        refusal: RepositoryRefusal,
        detail: String,
    ): RepositoryOutcome {
        val record = existing?.copy(
            status = RepositoryStatus.FAILED,
            checkedAt = now(),
            refusal = refusal,
            detail = detail,
        )
        if (record != null) registry.upsert(record)
        return RepositoryOutcome.Refused(record, refusal, detail)
    }

    private companion object {
        /** Staging lives under the repositories root so one delete clears it. */
        const val STAGING = ".staging"

        /**
         * The two files a repository is made of.
         *
         * Only these are fetched: `repo.json` carries the trust anchor and
         * `index.shura.json` carries the per-package digests and signers. The
         * APKs are fetched when the reader installs that extension, by the path
         * that already exists.
         */
        val REPOSITORY_FILES = listOf("repo.json", "index.shura.json")
    }
}

/** What a failed transfer means, in the vocabulary the row shows. */
private fun FetchProblem.fetchRefusal(): RepositoryRefusal = when (this) {
    FetchProblem.NETWORK -> RepositoryRefusal.NETWORK
    FetchProblem.REDIRECT -> RepositoryRefusal.NETWORK
    FetchProblem.STATUS -> RepositoryRefusal.HTTP_STATUS
    FetchProblem.TOO_LARGE -> RepositoryRefusal.TOO_LARGE
    FetchProblem.EMPTY -> RepositoryRefusal.UNSUPPORTED_INDEX
}

/**
 * Which refusal a verification failure is.
 *
 * The anchor refusal is told apart by the client's own first words, because
 * `ShuraRepositoryClient` raises the anchor mismatch and every other
 * unverified condition through the same type. The message is kept either way,
 * so the row shows what the client actually said rather than this
 * classification standing in for it.
 */
private fun LoadFailure.classify(): RepositoryRefusal = when {
    message?.startsWith(ANCHOR_REFUSAL) == true -> RepositoryRefusal.ANCHOR
    this is LoadFailure.Manifest -> RepositoryRefusal.UNSUPPORTED_INDEX
    else -> RepositoryRefusal.NOT_VERIFIED
}

private const val ANCHOR_REFUSAL = "repository anchor mismatch"