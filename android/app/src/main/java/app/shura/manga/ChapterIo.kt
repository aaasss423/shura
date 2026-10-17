package app.shura.manga

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredChapter(
    val dir: File,
    val sourceId: String,
    /** The id the source knows the title by, so the chapter list can be refetched. */
    val mangaId: String,
    val chapterId: String,
    val title: String,
    /** The work's cover, when the source provided one. Empty renders a placeholder. */
    val coverUrl: String,
    val pageUris: List<Uri>,
    /**
     * The work's own name, recorded beside the chapter's.
     *
     * The chapter's [title] is "Chapter 12" for almost every chapter of a
     * series, so a store that keeps only that has no way to say which book a
     * saved chapter belongs to - and every screen that groups saved chapters into
     * works was therefore naming the work after one of its chapters. That is
     * what put a row reading "Chapter 2" on a downloads screen where a reader
     * expected a manga.
     *
     * Empty for a chapter saved before this was recorded, which is why the
     * readers of it treat it as unknown rather than as the name.
     */
    val mangaTitle: String = "",
)

/**
 * What the store holds for one chapter, in a form the JVM can build.
 *
 * WHY THIS EXISTS ALONGSIDE [StoredChapter]
 *
 * [StoredChapter] hands the reader `android.net.Uri`, which the JVM cannot
 * construct: every method on the mocked android.jar throws. So every decision
 * that has to be provable without a device - "is this chapter on disk?", "which
 * chapters can the reader step to without a request?", "did the page files
 * survive?" - was previously impossible to test, and the reader's offline path
 * was therefore untested.
 *
 * [StoredChapter] is exactly this record with `file://` URIs on the front. Both
 * are parsed by the same [readChapterRecord]; nothing is written twice and there
 * is no second answer to "what is on this device".
 *
 * [pageFiles] is filtered to files that are actually there. A manifest names the
 * pages it was written with, and one of them being deleted by hand afterwards is
 * exactly the case where the reader must not be handed a list of URIs it cannot
 * decode.
 */
data class StoredChapterRecord(
    val dir: File,
    val sourceId: String,
    val mangaId: String,
    val chapterId: String,
    val title: String,
    val coverUrl: String,
    val mangaTitle: String,
    /** In manifest order. Empty when the chapter has no readable page left. */
    val pageFiles: List<File>,
) {
    /** What the reader is handed: the same files, as `file://` URIs. */
    fun toStoredChapter(): StoredChapter = StoredChapter(
        dir = dir,
        sourceId = sourceId,
        mangaId = mangaId,
        chapterId = chapterId,
        title = title,
        coverUrl = coverUrl,
        pageUris = pageFiles.map(Uri::fromFile),
        mangaTitle = mangaTitle,
    )
}

/**
 * How much space a saved chapter occupies.
 *
 * Walks the files rather than trusting a recorded figure, so a page removed by
 * hand is reflected instead of leaving the library claiming space it no longer
 * holds.
 */
fun directorySize(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** Sizes in units a person reads, not bytes. */
fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

/** Persistent offline chapter store under app-internal filesDir. */
fun chapterStore(context: Context): File = File(context.filesDir, "chapters")

/**
 * The directory a chapter is written into.
 *
 * The name is the chapter id with everything outside a safe set replaced, so
 * "is this downloaded" has to ask the same question the writer answered rather
 * than guess a second layout. Lives here rather than in the queue so the reader,
 * the library and the queue all resolve a chapter id to one directory by one
 * rule.
 */
fun chapterDirIn(store: File, chapterId: String): File =
    File(store, chapterId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

/** Every stored chapter, as records. The one pass the whole app reads. */
fun indexStoredChapterRecords(store: File): List<StoredChapterRecord> =
    store.listFiles()?.filter { it.isDirectory }
        ?.mapNotNull { readChapterRecord(it) }
        ?.sortedByDescending { it.title }
        ?: emptyList()

fun indexStoredChapters(store: File): List<StoredChapter> =
    indexStoredChapterRecords(store).map { it.toStoredChapter() }

/**
 * One chapter, read from its own directory, without listing the whole store.
 *
 * This is the lookup "is this chapter on disk?", answered from the same layout
 * the writer used and from the same manifest reader, so a chapter id means one
 * directory and one manifest everywhere in the app.
 */
fun storedChapterRecordIn(
    store: File,
    sourceId: String,
    chapterId: String,
): StoredChapterRecord? {
    if (chapterId.isBlank()) return null
    val record = readChapterRecord(chapterDirIn(store, chapterId)) ?: return null
    // The directory is keyed by the chapter id alone, so a source that reuses
    // another source's id must not be handed the other source's pages.
    if (record.sourceId != sourceId) return null
    return record
}

/**
 * Writes a chapter into the store and returns its directory.
 *
 * The Uri-free half of [persistStoredChapter]: the bytes are copied and the
 * manifest is written exactly as before, and the caller decides whether it
 * needs the record back with `file://` URIs on it. A manifest is written last,
 * so a process that dies mid-copy leaves a directory the index will not offer -
 * which is the whole point of the store never showing a half-saved chapter.
 */
fun writeChapterManifest(
    store: File,
    sourceId: String,
    mangaId: String,
    chapterId: String,
    title: String,
    files: List<File>,
    coverUrl: String = "",
    /** The work's name, so the store can name the work as well as the chapter. */
    mangaTitle: String = "",
): File {
    val dir = chapterDirIn(store, chapterId)
    dir.mkdirs()
    files.forEach { file -> if (file.parentFile != dir) file.copyTo(File(dir, file.name), overwrite = true) }
    val manifest = JSONObject()
        .put("sourceId", sourceId)
        .put("mangaId", mangaId)
        .put("chapterId", chapterId)
        .put("title", title)
        .put("coverUrl", coverUrl)
        .put("mangaTitle", mangaTitle)
        .put("pages", JSONArray(files.map(File::getName)))
    File(dir, "manifest.json").writeText(manifest.toString())
    return dir
}

fun persistStoredChapter(
    store: File,
    sourceId: String,
    mangaId: String,
    chapterId: String,
    title: String,
    files: List<File>,
    coverUrl: String = "",
    /** The work's name, so the store can name the work as well as the chapter. */
    mangaTitle: String = "",
): StoredChapter {
    val dir = writeChapterManifest(store, sourceId, mangaId, chapterId, title, files, coverUrl, mangaTitle)
    return readChapterRecord(dir)?.toStoredChapter() ?: error("failed to persist chapter")
}

fun deleteStoredChapter(stored: StoredChapter) {
    stored.dir.deleteRecursively()
}

/**
 * Parses one chapter directory.
 *
 * Returns null for anything that is not a complete chapter: no manifest, no
 * `pages` array, or no page file left on disk. That is what keeps a partially
 * written chapter - the state a killed download leaves behind - out of the
 * library, out of the downloads list, and out of the reader.
 */
private fun readChapterRecord(dir: File): StoredChapterRecord? {
    val manifest = File(dir, "manifest.json")
    if (!manifest.exists()) return null
    val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
    val pageArray = json.optJSONArray("pages") ?: return null
    val files = (0 until pageArray.length())
        .map { File(dir, json.pageName(pageArray, it)) }
        .filter { it.isFile && it.name.isNotBlank() }
    if (files.isEmpty()) return null
    return StoredChapterRecord(
        dir = dir,
        sourceId = json.manifestText("sourceId"),
        mangaId = json.manifestText("mangaId"),
        chapterId = json.manifestText("chapterId"),
        title = json.manifestText("title"),
        coverUrl = json.manifestText("coverUrl"),
        mangaTitle = json.manifestText("mangaTitle"),
        pageFiles = files,
    )
}

/**
 * A field of the manifest as text, and "" for one that is not there.
 *
 * The same trap as the source parsers: `optString` on Android stringifies a
 * JSON `null` into the four characters "null". Here that would be worse than a
 * bad title - `chapterId` is the chapter's *identity* everywhere else in the
 * app, and "is this downloaded?", the download key and the reader's neighbours
 * are all resolved through it - so a manifest carrying a null there becomes a
 * chapter nothing can find.
 */
private fun JSONObject.manifestText(name: String): String =
    if (isNull(name)) "" else optString(name).trim()

/** A page file's name out of the manifest's `pages` array. */
private fun JSONObject.pageName(array: JSONArray, index: Int): String =
    if (array.isNull(index)) "" else array.optString(index).trim()