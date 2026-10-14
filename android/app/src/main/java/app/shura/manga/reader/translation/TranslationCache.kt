package app.shura.manga.reader.translation

import android.content.Context
import app.shura.manga.reader.KeyValueStore
import app.shura.manga.reader.PreferencesStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the translated text of one chapter, so switching chapters does not
 * re-translate what was already paid for.
 *
 * Bounded to a single chapter on purpose. Translation output is large - a long
 * chapter can be megabytes of text - and a cache that grows with reading would
 * fill a device with no policy for cleaning it. One chapter is the whole point:
 * a reader who goes back and forth between two chapters translates once.
 */
class TranslationCache(private val prefs: KeyValueStore, private val capacity: Int = 1) {

    constructor(context: Context, capacity: Int = 1) : this(
        PreferencesStore(context.getSharedPreferences("translation-cache", Context.MODE_PRIVATE)),
        capacity,
    )

    /** The key a chapter's translations are stored under. */
    fun keyOf(sourceId: String, chapterId: String) = "$sourceId|$chapterId"

    /**
     * Translated pages for a chapter, or empty when nothing is cached.
     *
     * A corrupt entry is dropped rather than returned empty and then treated as
     * a cache hit: a reader must be able to tell "not translated" from "the
     * cache entry is broken".
     */
    fun pagesFor(sourceId: String, chapterId: String): List<String> {
        val raw = prefs.getString(keyOf(sourceId, chapterId)) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: run {
            prefs.putString(keyOf(sourceId, chapterId), null)
            return emptyList()
        }
        return (0 until array.length()).map { array.optString(it) }
    }

    fun contains(sourceId: String, chapterId: String): Boolean =
        pagesFor(sourceId, chapterId).isNotEmpty()

    /**
     * Translated text keyed by the page it belongs to.
     *
     * The flat [pagesFor] form cannot answer "which pages are already done",
     * and that is exactly what FOLLOW_READING asks before every batch: without
     * page identity it either re-translates what it has or stops early and
     * never translates the rest of the chapter.
     */
    fun pagesByIndex(sourceId: String, chapterId: String): Map<Int, String> {
        val raw = prefs.getString(indexKeyOf(sourceId, chapterId)) ?: return emptyMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: run {
            prefs.putString(indexKeyOf(sourceId, chapterId), null)
            return emptyMap()
        }
        val result = mutableMapOf<Int, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key)
            key.toIntOrNull()?.let { if (value.isNotBlank()) result[it] = value }
        }
        return result
    }

    fun storePages(sourceId: String, chapterId: String, byPage: Map<Int, String>) {
        if (byPage.isEmpty()) {
            prefs.putString(indexKeyOf(sourceId, chapterId), null)
            forget(sourceId, chapterId)
            return
        }
        val obj = org.json.JSONObject()
        byPage.forEach { (page, text) -> obj.put(page.toString(), text) }
        store(sourceId, chapterId, byPage.values.toList(), extraKey = indexKeyOf(sourceId, chapterId))
        prefs.putString(indexKeyOf(sourceId, chapterId), obj.toString())
    }

    private fun indexKeyOf(sourceId: String, chapterId: String) = keyOf(sourceId, chapterId) + "#pages"

    private fun forget(sourceId: String, chapterId: String) {
        prefs.putString(keyOf(sourceId, chapterId), null)
        prefs.putString(indexKeyOf(sourceId, chapterId), null)
    }

    /**
     * Stores a chapter's pages and evicts the rest.
     *
     * Eviction is by insertion order, which is what makes `capacity` mean
     * something: with one slot, opening a new chapter's translation discards the
     * previous one, so the cache cannot grow behind the reader's back.
     */
    fun store(sourceId: String, chapterId: String, pages: List<String>): Unit =
        store(sourceId, chapterId, pages, null)

    private fun store(
        sourceId: String,
        chapterId: String,
        pages: List<String>,
        extraKey: String?,
    ): Unit = synchronized(this) {
        if (pages.isEmpty()) {
            prefs.putString(keyOf(sourceId, chapterId), null)
            extraKey?.let { prefs.putString(it, null) }
            return
        }
        val array = JSONArray()
        pages.forEach(array::put)
        prefs.putString(keyOf(sourceId, chapterId), array.toString())

        val current = keyOf(sourceId, chapterId)
        val indexKey = extraKey
        val previous = runCatching { JSONArray(prefs.getString(KEY_ORDER) ?: "[]") }.getOrNull()
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?: emptyList()
        // Most-recent-last, trimmed to `capacity`: what falls off the end is
        // what this store is no longer allowed to keep.
        val retained = (previous.filterNot { it == current } + current).takeLast(capacity)
        prefs.putString(KEY_ORDER, JSONArray(retained).toString())
        indexKey?.let { prefs.putString(it, prefs.getString(it)) }
        // Only the entries that actually fell out are deleted. Deleting
        // everything except the current chapter emptied the cache whenever
        // capacity allowed more than one, and let an evicted chapter stay
        // readable because it was no longer in the order to be deleted from.
        previous.filterNot { it in retained }.forEach { stale ->
            prefs.putString(stale, null)
            // The page-indexed half goes with it, or an evicted chapter would
            // still answer from the cache with its translations.
            prefs.putString(stale + "#pages", null)
        }
    }

    fun clear() {
        prefs.clear()
    }

    private companion object {
        const val KEY_ORDER = "order"
    }
}
