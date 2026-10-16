package app.shura.manga

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Works the reader chose to keep.
 *
 * The chapter store answers "which chapters are on this device", which is a
 * different question from "which works do I follow". A reader adds a title long
 * before they download a chapter of it, and until this existed the button on a
 * work's page did nothing at all - it was drawn, it was labelled, and it was a
 * no-op with a comment saying so.
 *
 * One file per work rather than a single index: adding a title must not rewrite
 * every other title, and a corrupt file costs one entry instead of the library.
 */
data class LibraryWork(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val coverUrl: String = "",
    val addedAt: Long = 0L,
) {
    val key: String get() = keyOf(sourceId, mangaId)

    companion object {
        /** Stable across sources, so the same title from two sources stays distinct. */
        fun keyOf(sourceId: String, mangaId: String): String = "$sourceId|$mangaId"
    }
}

/** Where the followed works live, under app-internal storage. */
fun workLibrary(context: Context): File = File(context.filesDir, "works").apply { mkdirs() }

private fun entryFile(dir: File, work: LibraryWork): File =
    File(dir, work.key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

fun listLibraryWorks(dir: File): List<LibraryWork> =
    dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.mapNotNull { readWork(it) }
        ?.sortedByDescending { it.addedAt }
        ?: emptyList()

fun readWork(file: File): LibraryWork? {
    if (!file.isFile) return null
    val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
    val sourceId = json.optString("sourceId")
    val mangaId = json.optString("mangaId")
    val title = json.optString("title")
    // A row without these three cannot be opened again, so it is not an entry.
    if (sourceId.isBlank() || mangaId.isBlank() || title.isBlank()) return null
    return LibraryWork(
        sourceId = sourceId,
        mangaId = mangaId,
        title = title,
        coverUrl = json.optString("coverUrl"),
        addedAt = json.optLong("addedAt"),
    )
}

/** Adds or replaces a work. Replacing is what makes "add" idempotent. */
fun storeWork(dir: File, work: LibraryWork): LibraryWork {
    dir.mkdirs()
    val record = work.copy(addedAt = if (work.addedAt > 0L) work.addedAt else System.currentTimeMillis())
    val json = JSONObject()
        .put("sourceId", record.sourceId)
        .put("mangaId", record.mangaId)
        .put("title", record.title)
        .put("coverUrl", record.coverUrl)
        .put("addedAt", record.addedAt)
    entryFile(dir, record).writeText(json.toString())
    return record
}

/** Removes a work. False when it was not there, so a double tap cannot lie. */
fun removeWork(dir: File, work: LibraryWork): Boolean = entryFile(dir, work).delete()

fun isWorkInLibrary(dir: File, sourceId: String, mangaId: String): Boolean =
    entryFile(dir, LibraryWork(sourceId, mangaId, "")).isFile