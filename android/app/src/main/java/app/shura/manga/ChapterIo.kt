package app.shura.manga

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredChapter(
    val dir: File,
    val sourceId: String,
    val chapterId: String,
    val title: String,
    val pageUris: List<Uri>,
)

/** Persistent offline chapter store under app-internal filesDir. */
fun chapterStore(context: Context): File = File(context.filesDir, "chapters")

fun indexStoredChapters(store: File): List<StoredChapter> =
    store.listFiles()?.filter { it.isDirectory }
        ?.mapNotNull { readManifest(it) }
        ?.sortedByDescending { it.title } ?: emptyList()

fun persistStoredChapter(store: File, sourceId: String, chapterId: String, title: String, files: List<File>): StoredChapter {
    val dir = File(store, chapterId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
    dir.mkdirs()
    files.forEach { file -> if (file.parentFile != dir) file.copyTo(File(dir, file.name), overwrite = true) }
    val manifest = JSONObject()
        .put("sourceId", sourceId)
        .put("chapterId", chapterId)
        .put("title", title)
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
    return StoredChapter(dir, json.optString("sourceId"), json.optString("chapterId"), json.optString("title"), uris)
}