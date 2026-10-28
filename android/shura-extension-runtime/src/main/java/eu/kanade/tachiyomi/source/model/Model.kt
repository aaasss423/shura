// Transitional model types.
//
// Signatures are NOT guesses. Every one was read out of a shipped extension
// APK's DEX (see app.shura.runtime.AbiSurfaceTest), which records:
//
//   SManga   getUrl/setUrl String, getTitle/setTitle String,
//            getAuthor/setAuthor String, getGenre/setGenre String,
//            getDescription/setDescription String,
//            getStatus/setStatus INT, getThumbnail_url/setThumbnail_url String,
//            SManga$Companion.create() -> SManga
//   SChapter setChapter_number(FLOAT), setName/setUrl/setScanlator(String),
//            SChapter$Companion.create() -> SChapter
//   Page     <init>(int, String, String, android.net.Uri, int, marker),
//            getImageUrl() -> String
//   MangasPage <init>(List, boolean)
//   Filter$Select <init>(String, Object[], int)
//   FilterList    <init>(Filter[])
//
// Deviating from these produces NoSuchMethodError the moment an extension is
// loaded, so AbiSurfaceTest asserts them against the real artifact.
package eu.kanade.tachiyomi.source.model

import android.net.Uri
import kotlinx.serialization.json.JsonObject

/** Series metadata. `status` is an int on the wire; see the file header. */
open class SManga {
    var url: String = ""
    var title: String = ""
    var author: String = ""
    var artist: String = ""
    var genre: String = ""
    var description: String = ""
    var thumbnail_url: String = ""
    var status: Int = 0
    var initialized: Boolean = false
    private var memoValue: JsonObject = JsonObject(emptyMap())

    fun setMemo(value: JsonObject) {
        memoValue = value
    }

    fun getMemo(): JsonObject = memoValue

    companion object {
        @JvmStatic fun create(): SManga = SManga()
    }
}

/** Chapter metadata. `chapter_number` is a float, matching the DEX. */
open class SChapter {
    var url: String = ""
    var name: String = ""
    var date_upload: Long = 0
    var chapter_number: Float = -1f
    private var memoValue: Int = 0
    var scanlator: String = ""

    fun setMemo(value: JsonObject) {
        memoValue = value.toString().hashCode()
    }

    fun getMemo(): Int = memoValue

    companion object {
        @JvmStatic fun create(): SChapter = SChapter()
    }
}

/**
 * One page of a chapter. The synthetic `int` before the marker is Kotlin's
 * default-argument mask, so every parameter after `index` needs a default.
 */
open class Page(
    val index: Int,
    val imageUrl: String? = null,
    var url: String? = null,
    var image: Uri? = null,
) {
    val number: Int get() = index + 1
}

/** One page of search results. */
open class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean = false,
)

/** Filter header. */
open class Filter(val name: String) {
    /**
     * Single-select filter. The DEX signature is exactly `(String, Object[], int)`:
     * a name, an array of candidate values, and an int in third position. No vararg
     * overload is offered on purpose - a second constructor would only add a
     * descriptor nothing calls.
     *
     * Nested so the binary name is `Filter$Select`, which is the descriptor the
     * extension invokes. A top-level `Select` compiles to the name `Select`, and
     * the string-pool assertions in AbiSurfaceTest would not have caught that.
     */
    class Select(
        name: String,
        val values: Array<Any?>,
        selectedIndex: Int = 0,
    ) : Filter(name) {
        var state: Any? = values.getOrNull(selectedIndex)
    }

    class Header(name: String) : Filter(name)

    class Separator(name: String = "") : Filter(name)

    override fun equals(other: Any?): Boolean = other is Filter && other.name == name
    override fun hashCode(): Int = name.hashCode()
}


/** The filters applied to one request. The DEX calls `FilterList(Filter[])`. */
class FilterList {
    val list: List<Filter>

    constructor() {
        list = emptyList()
    }

    constructor(filters: Array<Filter>) {
        list = filters.toList()
    }

    constructor(filters: List<Filter>) {
        list = filters.toList()
    }

    operator fun iterator(): Iterator<Filter> = list.iterator()
}

class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
) {
    @Suppress("unused")
    val mangaValue: SManga get() = manga
    @Suppress("unused")
    val chaptersValue: List<SChapter> get() = chapters
}