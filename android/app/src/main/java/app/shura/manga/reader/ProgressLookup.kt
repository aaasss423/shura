package app.shura.manga.reader

/**
 * Remembers the cover of each work the reader has seen.
 *
 * A cover arrives with the search result, but a work may be reopened from the
 * library or from "continue reading" long after that list has scrolled away and
 * the source has been closed. Keeping the URL lets those screens show the real
 * artwork without re-querying a source or storing a second copy on disk.
 *
 * Keyed by source *and* title: the same title under two sources is two works,
 * and showing one source's art on the other's card is worse than none.
 */
class ProgressLookup(covers: Map<String, String> = emptyMap()) {

    private val covers = covers.toMutableMap()

    /** Records a work's cover. Blank and null covers are ignored, not stored. */
    fun remember(sourceId: String, title: String, cover: String?) {
        if (!cover.isNullOrBlank()) covers["$sourceId|$title"] = cover
    }

    /** The work's cover, or empty when it is unknown. Never another work's. */
    fun coverFor(sourceId: String, title: String): String =
        covers["$sourceId|$title"].orEmpty()
}