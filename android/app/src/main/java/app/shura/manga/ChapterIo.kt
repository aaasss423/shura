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
)

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

fun indexStoredChapters(store: File): List<StoredChapter> =
    store.listFiles()?.filter { it.isDirectory }
        ?.mapNotNull { readManifest(it) }
        ?.sortedByDescending { it.title } ?: emptyList()

fun persistStoredChapter(
    store: File,
    sourceId: String,
    mangaId: String,
    chapterId: String,
    title: String,
    files: List<File>,
    coverUrl: String = "",
): StoredChapter {
    val dir = File(store, chapterId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
    dir.mkdirs()
    files.forEach { file -> if (file.parentFile != dir) file.copyTo(File(dir, file.name), overwrite = true) }
    val manifest = JSONObject()
        .put("sourceId", sourceId)
        .put("mangaId", mangaId)
        .put("chapterId", chapterId)
        .put("title", title)
        .put("coverUrl", coverUrl)
        .put("pages", JSONArray(files.map(File::getName)))
    File(dir, "manifest.json").writeText(manifest.toString())
    return readManifest(dir) ?: error("failed to persist chapter")
}

fun deleteStoredChapter(stored: StoredChapter) {
    stored.dir.deleteRecursively()
}

private fun readManifest(dir: File): StoredChapter? {
    val manifest = File(dir, "manifest.json")
    if (!manifest.exists()) return null
    val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
    val pageArray = json.optJSONArray("pages") ?: return null
    val uris = (0 until pageArray.length()).map { Uri.fromFile(File(dir, pageArray.optString(it))) }
    if (uris.isEmpty()) return null
    return StoredChapter(dir, json.optString("sourceId"), json.optString("mangaId"),
        json.optString("chapterId"), json.optString("title"),
        json.optString("coverUrl"), uris)
}