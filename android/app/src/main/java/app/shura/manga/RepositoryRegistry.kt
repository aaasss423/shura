package app.shura.manga

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a registered repository got to the last time it was checked. */
enum class RepositoryStatus {
    /** Added, never fetched. Nothing is claimed about it yet. */
    NEVER_CHECKED,

    /** Fetched, and its own anchor passed the client's existing check. */
    OK,

    /** The last attempt failed. [RepositoryRecord.detail] is why. */
    FAILED,
}

/** Why a repository could not be used, in the words the row shows. */
enum class RepositoryRefusal {
    /** The address is not a usable repository address at all. */
    BAD_URL,

    /** Plain HTTP. The app refuses to talk to it unencrypted. */
    NOT_HTTPS,

    /** The address could not be reached. */
    NETWORK,

    /** The server answered, but not with a file. */
    HTTP_STATUS,

    /** The metadata is bigger than a repository's index is allowed to be. */
    TOO_LARGE,

    /** `index.shura.json` was missing or is not an index this build reads. */
    UNSUPPORTED_INDEX,

    /** The index names no extensions. */
    EMPTY_INDEX,

    /**
     * The repository's own anchor is not the one this build pins.
     *
     * Not a failure to be retried into a success: a repository that fails here
     * is refused, and stays refused however often it is fetched.
     */
    ANCHOR,

    /** The client refused the repository, and said something other than the anchor. */
    NOT_VERIFIED,

    /** The directory could not be written. */
    INSTALL_FAILED,
}

/**
 * One repository the app knows about.
 *
 * [checkedAt] is the last *attempt*, not the last success, so a row whose
 * update failed says when it was tried rather than implying the number beside it
 * is fresh. [packages] is what the directory actually yields right now, which is
 * why a failed update leaves the previous count alone: those files are still
 * there and still offered.
 */
data class RepositoryRecord(
    val id: String,
    val url: String,
    val name: String,
    val status: RepositoryStatus,
    /** Epoch millis of the last check, or 0 when there has never been one. */
    val checkedAt: Long = 0L,
    val packages: Int = 0,
    val refusal: RepositoryRefusal? = null,
    /** The real reason, in the runtime's own words. Never invented. */
    val detail: String? = null,
)

/**
 * The registered repositories, on disk.
 *
 * WHY A FILE AND NOT THE BUNDLED DIRECTORY
 *
 * `filesDir/repo` holds exactly one repository, and the loader reads it as one
 * whole thing: the installer deletes the destination and moves a new copy into
 * its place. Adding a second repository into that directory would make "remove"
 * a lie - the row would disappear while its extensions stayed in the only place
 * the store reads from. So every remote repository gets a directory of its own
 * under `filesDir/repos`, and the list of them is persisted beside those
 * directories. Removing one then means deleting one directory and one line, and
 * its packages stop being offered because there is nothing left to read them
 * from.
 *
 * WHY THE LIST IS ITS OWN FILE
 *
 * Because it has to survive the app being closed. A repository that is only
 * remembered by the fact that its directory exists cannot be told apart from
 * one whose download failed halfway, and re-checking the second would quietly
 * turn it into the first.
 */
class RepositoryRegistry(
    /** The directory holding every remote repository, one per child. */
    val root: File,
) {

    private val file = File(root, LIST_FILE)

    /**
     * Every registered repository, in the order they were added.
     *
     * A file that cannot be read is an empty list, not a crash and not a
     * deletion: a corrupt list is a state to report, and throwing away the
     * directories because their list was unreadable would take installed
     * extensions with it.
     */
    fun records(): List<RepositoryRecord> {
        val text = file.takeIf { it.isFile }?.readText() ?: return emptyList()
        val array = try {
            JSONObject(text).optJSONArray("repositories") ?: JSONArray()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<RepositoryRecord>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val o = array.optJSONObject(index) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            // An id becomes a directory name, so a record that cannot be one is
            // not a record this class will act on.
            if (!isSafeId(id) || url.isBlank() || !seen.add(id)) continue
            out += RepositoryRecord(
                id = id,
                url = url,
                name = o.optString("name").ifBlank { url },
                status = runCatching { RepositoryStatus.valueOf(o.optString("status")) }
                    .getOrDefault(RepositoryStatus.NEVER_CHECKED),
                checkedAt = o.optLong("checkedAt"),
                packages = o.optInt("packages"),
                refusal = o.optString("refusal")
                    .takeIf { it.isNotBlank() }
                    ?.let { name -> runCatching { RepositoryRefusal.valueOf(name) }.getOrNull() },
                detail = o.optString("detail").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /** Writes the list. A failure is reported rather than swallowed. */
    fun save(records: List<RepositoryRecord>): Boolean = try {
        root.mkdirs()
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("url", record.url)
                    .put("name", record.name)
                    .put("status", record.status.name)
                    .put("checkedAt", record.checkedAt)
                    .put("packages", record.packages)
                    .put("refusal", record.refusal?.name ?: "")
                    .put("detail", record.detail ?: ""),
            )
        }
        // Written beside the list and moved into place, so a process that dies
        // mid-write leaves the previous list rather than half of one.
        val temp = File(root, "$LIST_FILE.tmp")
        temp.writeText(JSONObject().put("repositories", array).toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** One repository's own directory. Its name is its id, and nothing else. */
    fun directoryOf(id: String): File = File(root, id)

    /**
     * The directories of every registered repository that is actually there.
     *
     * A record whose directory has gone is skipped rather than handed to the
     * loader: an absent directory is the exact case that once threw out of
     * composition and took the app with it.
     */
    fun roots(): List<File> = records().map { directoryOf(it.id) }.filter { it.isDirectory }

    /** Adds [record], or replaces the one already registered under the same id. */
    fun upsert(record: RepositoryRecord) {
        val current = records().toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) current[index] = record else current.add(record)
        save(current)
    }

    /**
     * Forgets a repository: its directory goes, then its line in the list.
     *
     * Returns whether the directory is really gone afterwards, because the row
     * has to say what happened. A repository whose files could not be deleted is
     * kept in the list - dropping the line would leave its extensions on disk
     * with nothing on screen that can ever remove them.
     */
    fun forget(id: String): Boolean {
        val directory = directoryOf(id)
        val existed = directory.exists()
        if (existed && !directory.deleteRecursively()) {
            return false
        }
        val current = records()
        val remaining = current.filterNot { it.id == id }
        save(remaining)
        // False when there was nothing there to remove, for the same reason
        // `removeWork` is: a second press on a row that is already gone must not
        // claim a success.
        return (existed || current.any { it.id == id }) && !directory.exists()
    }

    companion object {
        private const val LIST_FILE = "repositories.json"

        /** A directory name this class is willing to build from a record. */
        fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= 96 &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '.' } &&
            id != "." && id != ".."
    }
}