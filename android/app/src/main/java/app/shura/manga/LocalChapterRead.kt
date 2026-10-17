package app.shura.manga

import app.shura.source.Chapter
import java.io.File

/**
 * Where the reader gets one chapter's pages from.
 *
 * THE ONE DECISION THAT MAKES OFFLINE READING OFFLINE
 *
 * A chapter whose pages are already on this device has nothing to fetch. Saying
 * so is the whole of [ChapterFeed.OnDevice]; saying [ChapterFeed.Network] is the
 * existing online path, unchanged, for a chapter that is not on the device.
 *
 * Nothing here touches a socket, a source or a context, and that is deliberate:
 * the bug this replaces was that *no* code consulted the store before fetching,
 * so every route into the reader - tapping a downloaded chapter, continuing one
 * from Home, stepping to the next chapter, choosing one from the reader's own
 * list - went to `source.pages()` even for a chapter sitting on disk. The
 * decision was unreachable from a JVM test, so it could not be pinned; putting
 * it here, over the store index alone, is what makes it checkable.
 *
 * `OnDevice` is chosen only when the chapter's page files are all present. A
 * manifest that names a page which is no longer there must not produce a reader
 * full of URIs that cannot be decoded - that failure is what the reader used to
 * show as a chapter with nothing in it.
 */
sealed interface ChapterFeed {
    data class OnDevice(val record: StoredChapterRecord) : ChapterFeed
    data object Network : ChapterFeed
}

/**
 * The store's answer to "can this chapter be read without the network?".
 *
 * Three questions live here, all of them over files:
 *
 *  - [feedFor] - is this one chapter on disk?
 *  - [readerChapters] - which chapters of this work are on disk, in reading
 *    order, so the reader's previous/next can step through them without asking
 *    the source for a list it would have to reach the network to produce;
 *  - [pageFiles] - which of the manifest's pages actually exist.
 *
 * Nothing in this file opens a connection, and that is the property the tests
 * rely on: they pass a source that throws on every call and assert it is never
 * reached.
 */
object LocalChapterRead {

    /** Whether [chapterId] of [sourceId] is readable from disk right now. */
    fun feedFor(index: List<StoredChapterRecord>, sourceId: String, chapterId: String): ChapterFeed {
        if (chapterId.isBlank()) return ChapterFeed.Network
        val record = index.firstOrNull {
            it.sourceId == sourceId && it.chapterId == chapterId && it.pageFiles.isNotEmpty()
        } ?: return ChapterFeed.Network
        return ChapterFeed.OnDevice(record)
    }

    /**
     * The same question, answered by looking at one directory.
     *
     * Used where there is no index in hand - a cold start before the library has
     * been read, or a path that only has the chapter id. Same layout, same
     * manifest reader, so it cannot answer differently from [feedFor].
     */
    fun feedFor(store: File, sourceId: String, chapterId: String): ChapterFeed {
        val record = storedChapterRecordIn(store, sourceId, chapterId) ?: return ChapterFeed.Network
        return if (record.pageFiles.isEmpty()) ChapterFeed.Network else ChapterFeed.OnDevice(record)
    }

    /**
     * This work's chapters that are on the device, newest first.
     *
     * Newest first is the reading order every source here returns, and it is
     * what [app.shura.manga.ui.ChapterNavigation] steps through, so the reader's
     * arrows follow the story rather than reversing it.
     *
     * Only chapters that are really readable are included: a neighbour the
     * reader offers as "next" and then has to fetch anyway is the offline bug
     * wearing a different hat.
     */
    fun readerChapters(
        index: List<StoredChapterRecord>,
        sourceId: String,
        mangaId: String,
    ): List<Chapter> =
        index.asSequence()
            .filter { it.sourceId == sourceId && it.mangaId == mangaId && it.pageFiles.isNotEmpty() }
            .sortedByDescending { it.dir.lastModified() }
            .map { Chapter(it.chapterId, it.title, null) }
            .toList()

    /** The same list, read straight off the store. */
    fun readerChapters(store: File, sourceId: String, mangaId: String): List<Chapter> =
        readerChapters(indexStoredChapterRecords(store), sourceId, mangaId)

    /**
     * The page files of a record the reader is about to open.
     *
     * Re-checked here rather than trusted from the index: the index was read
     * when the screen was built and the file may have been removed since, and a
     * reader handed a URI for a file that is not there shows an error tile where
     * a page should be.
     */
    fun pageFiles(record: StoredChapterRecord): List<File> =
        record.pageFiles.filter { it.isFile && it.length() > 0L }
}