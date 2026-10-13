package app.shura.runtime

import java.io.File

/** What an install did, or why it did not. */
data class InstallReport(
    val installed: List<String>,
    val refused: String? = null,
) {
    val ok: Boolean get() = refused == null
}

/**
 * Puts a repository into the place the loader reads from.
 *
 * WHY THIS EXISTS
 *
 * `ShuraExtensionLoader.load(context, File(context.filesDir, "repo"), ...)` reads
 * `repo.json`, `index.shura.json` and `apk/<file>.apk` out of that directory, and
 * until now nothing in the app ever wrote to it. The index was never fetched,
 * the APKs were never copied, and the loader's only reachable state was an empty
 * directory - so no extension could ever load, and the failure surfaced as a
 * crash rather than as a message.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *
 * It does not decide what to trust. Trust stays exactly where it was: the
 * anchor is pinned in the build, and `ShuraRepositoryClient` refuses the
 * repository unless its declared key matches that pinned value, then verifies
 * each APK's digest and signature before a single line of its code runs.
 *
 * Installing bytes and believing bytes are different acts, and only the second
 * one is security-relevant. This only does the first, which is why a wrong
 * repository can be copied in and still be refused at load.
 *
 * Only artifacts the index actually references are copied. An archive carrying
 * extra APKs is refused rather than installed, so what lands on disk is exactly
 * what the loader will consider, and a repository cannot ship a payload that no
 * entry points at.
 */
object ShuraRepositoryInstaller {

    /** Per-file ceiling. Matches Shura Core's own artifact limit. */
    const val MAX_FILE_BYTES: Long = 50_000_000

    /** Enough for the index and its metadata; a repository is not an archive of media. */
    const val MAX_INDEX_BYTES: Long = 8_000_000

    fun install(from: File, into: File): InstallReport {
        if (!from.isDirectory) {
            return InstallReport(emptyList(), "no repository directory at $from")
        }
        val meta = File(from, "repo.json")
        val index = File(from, "index.shura.json")
        for (required in listOf(meta, index)) {
            if (!required.isFile) {
                return InstallReport(emptyList(), "${required.name} is missing from $from")
            }
            if (required.length() > MAX_INDEX_BYTES) {
                return InstallReport(emptyList(), "${required.name} is implausibly large")
            }
        }

        // The index is parsed before anything is copied, so a malformed one
        // cannot leave a half-installed repository behind.
        val entries = try {
            RepositoryIndex.parse(index.readText())
        } catch (e: Exception) {
            return InstallReport(emptyList(), "index.shura.json could not be read: ${e.message}")
        }
        if (entries.isEmpty()) {
            return InstallReport(emptyList(), "the repository publishes no extensions")
        }

        val apkDir = File(from, "apk")
        val wanted = entries.map { it.apk }.filter { it.isNotBlank() }.toSet()
        val present = apkDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet()
            ?: emptySet()
        val missing = wanted - present
        if (missing.isNotEmpty()) {
            return InstallReport(emptyList(), "the index references ${missing.size} artifact(s) not in apk/: ${missing.sorted().take(3)}")
        }
        val extra = present - wanted
        if (extra.isNotEmpty()) {
            // Refused, not filtered: a repository carrying payloads no entry
            // points at is saying something about itself.
            return InstallReport(
                emptyList(),
                "apk/ holds ${extra.size} artifact(s) the index does not reference: ${extra.sorted().take(3)}",
            )
        }

        // Staged *beside* the destination, never inside it: the swap below
        // deletes the destination first, which would delete the staged copy
        // with it and leave an install that reported success having copied
        // nothing.
        val parent = into.parentFile
            ?: return InstallReport(emptyList(), "$into has no parent directory")
        val staged = File(parent, into.name + ".staging")
        staged.deleteRecursively()
        try {
            staged.mkdirs()
            File(staged, "apk").mkdirs()
            meta.copyTo(File(staged, "repo.json"), overwrite = true)
            index.copyTo(File(staged, "index.shura.json"), overwrite = true)
            for (name in wanted) {
                val source = File(apkDir, name)
                if (source.length() > MAX_FILE_BYTES) {
                    return InstallReport(emptyList(), "$name exceeds the ${MAX_FILE_BYTES} byte artifact limit")
                }
                source.copyTo(File(File(staged, "apk"), name), overwrite = true)
            }
            // Swapped in whole, so a failure above leaves the previous
            // repository exactly as it was rather than half replaced.
            into.deleteRecursively()
            // renameTo returns false rather than throwing when it cannot move
            // the directory - which it cannot, on some filesystems - and
            // ignoring that reports a successful install of an empty directory:
            // the state the loader already failed on, now with a success
            // message on top of it. So it is checked, and falls back to copying.
            if (!staged.renameTo(into)) {
                into.mkdirs()
                copyTree(staged, into)
            }
            staged.deleteRecursively()
        } catch (e: Exception) {
            staged.deleteRecursively()
            return InstallReport(emptyList(), "install failed: ${e.message}")
        }
        val copied = listOf("repo.json", "index.shura.json") +
            wanted.sorted().map { "apk/$it" }
        val absent = copied.filterNot { File(into, it).isFile }
        if (absent.isNotEmpty()) {
            return InstallReport(
                emptyList(),
                "the install reported success but ${absent.size} file(s) are absent: $absent",
            )
        }
        return InstallReport(copied)
    }

    private fun copyTree(from: File, to: File) {
        val children = from.listFiles() ?: return
        for (child in children) {
            val target = File(to, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target)
            } else {
                child.copyTo(target, overwrite = true)
            }
        }
    }
}