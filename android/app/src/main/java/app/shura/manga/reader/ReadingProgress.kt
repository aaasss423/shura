package app.shura.manga.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the reader was, and what was read recently.
 *
 * Progress is keyed by (source, manga, chapter) rather than by one global
 * "last page", because a global one is wrong the moment a reader has two
 * chapters from different titles: reopening the second silently moved the first
 * to whatever page they happened to leave the other one on.
 *
 * History is a separate, bounded list. "Recently read" is a convenience, not a
 * record - it is capped, and it degrades to "nothing" rather than growing
 * without limit on a device with no storage policy.
 */
data class ReadingProgress(
    val sourceId: String,
    val mangaTitle: String,
    val chapterId: String,
    val chapterName: String,
    val page: Int,
    val pageCount: Int,
    val updatedAt: Long,
) {
    val key: String get() = "$sourceId|$mangaTitle|$chapterId"
}

/**
 * The narrow store these two classes need.
 *
 * SharedPreferences is the production implementation; the rules about clamping,
 * capping and ordering are the part worth testing, and they cannot be reached
 * through a `Context` on a machine with no Android runtime and no network to
 * fetch one.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun clear()
}

class PreferencesStore(private val prefs: android.content.SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun clear() { prefs.edit().clear().apply() }
}

/** An in-memory store, for tests and for previews. */
class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun clear() = values.clear()
}

class ReadingProgressStore(private val prefs: KeyValueStore) {
    constructor(context: Context) : this(
        PreferencesStore(context.getSharedPreferences("reading-progress", Context.MODE_PRIVATE))
    )

    private fun keyOf(sourceId: String, mangaTitle: String, chapterId: String) =
        "$sourceId|$mangaTitle|$chapterId"

    fun save(progress: ReadingProgress) {
        prefs.putString(keyOf(progress.sourceId, progress.mangaTitle, progress.chapterId),
            JSONObject()
                .put("page", progress.page)
                .put("pageCount", progress.pageCount)
                .put("chapterName", progress.chapterName)
                .put("updatedAt", progress.updatedAt)
                .toString())
        touchHistory(progress)
    }

    /** The page the reader should reopen at, clamped to what the chapter has. */
    fun pageFor(sourceId: String, mangaTitle: String, chapterId: String, pageCount: Int): Int {
        val raw = prefs.getString(keyOf(sourceId, mangaTitle, chapterId)) ?: return 0
        val page = runCatching { JSONObject(raw).optInt("page") }.getOrDefault(0)
        return page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    fun hasProgress(sourceId: String, mangaTitle: String, chapterId: String): Boolean =
        prefs.getString(keyOf(sourceId, mangaTitle, chapterId)) != null

    /** Most recent first, capped. */
    fun history(limit: Int = HISTORY_LIMIT): List<ReadingProgress> {
        val raw = prefs.getString(KEY_HISTORY) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = runCatching { JSONObject(array.optString(index)) }.getOrNull() ?: return@mapNotNull null
            ReadingProgress(
                sourceId = entry.optString("sourceId"),
                mangaTitle = entry.optString("mangaTitle"),
                chapterId = entry.optString("chapterId"),
                chapterName = entry.optString("chapterName"),
                page = entry.optInt("page"),
                pageCount = entry.optInt("pageCount"),
                updatedAt = entry.optLong("updatedAt"),
            )
        }.sortedByDescending { it.updatedAt }.take(limit)
    }

    fun clearHistory() {
        prefs.putString(KEY_HISTORY, null)
    }

    /**
     * History is written on every page change while scrolling, so it is capped
     * and de-duplicated by key. Without the cap a long reading session on one
     * chapter rewrites the list on every frame.
     */
    private fun touchHistory(progress: ReadingProgress) {
        val kept = history(HISTORY_LIMIT * 2)
            .filterNot { it.key == progress.key }
            .toMutableList()
        kept.add(0, progress)
        val array = JSONArray()
        kept.take(HISTORY_LIMIT).forEach { item ->
            array.put(JSONObject()
                .put("sourceId", item.sourceId)
                .put("mangaTitle", item.mangaTitle)
                .put("chapterId", item.chapterId)
                .put("chapterName", item.chapterName)
                .put("page", item.page)
                .put("pageCount", item.pageCount)
                .put("updatedAt", item.updatedAt))
        }
        prefs.putString(KEY_HISTORY, array.toString())
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val HISTORY_LIMIT = 24
    }
}
