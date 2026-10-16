package app.shura.manga.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.ShuraSource
import app.shura.manga.StoredChapter
import app.shura.manga.directorySize
import app.shura.manga.formatBytes
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.BatchDownload
import app.shura.manga.ui.BatchState
import app.shura.manga.ui.ChapterListOrder
import app.shura.manga.ui.DetailsResume
import app.shura.manga.ui.Strings
import app.shura.manga.ui.image.CoverImage
import androidx.compose.foundation.layout.size
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.LocalUiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search a source's catalogue. Loading and error states are explicit. */
@Composable
fun SearchScreen(
    source: ShuraSource,
    onOpenManga: (MangaSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Captured so the non-composable callbacks below can format too; a
    // composable call inside runCatching{} is not allowed.
    val language = LocalUiLanguage.current
    var query by remember { mutableStateOf("") }
    var results by remember(source.id) { mutableStateOf<List<MangaSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    var searched by remember(source.id) { mutableStateOf(false) }
    // The source contract is page-based and the UI used to ask for page 1 and
    // stop, so a title on page three was unreachable however much the reader
    // typed. Paging is what the API was already shaped for.
    var page by remember(source.id) { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var exhausted by remember(source.id) { mutableStateOf(false) }
    var ascending by remember(source.id) { mutableStateOf(true) }
    var filter by remember(source.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun fetch(target: Int, append: Boolean) {
        searching = !append
        loadingMore = append
        error = null
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { source.search(query.trim(), target) }
            }
            outcome.onSuccess {
                // A short page is the only end-of-catalogue signal the contract
                // offers; merging keeps what the reader already has.
                if (!append || it.isNotEmpty()) {
                    results = if (append) mergeResults(results, it) else it
                }
                exhausted = it.isEmpty()
                page = target
                searched = true
                searching = false
                loadingMore = false
            }.onFailure {
                // The reason is shown, not swallowed: a source that cannot be
                // reached should say so rather than look empty.
                error = it.message ?: Strings.t("error.generic", language)
                searching = false
                loadingMore = false
                searched = true
            }
        }
    }

    fun run() {
        page = 1
        exhausted = false
        fetch(1, append = false)
    }

    // Sorted and narrowed for reading, not for the network: the contract has no
    // filters, and inventing a query syntax a source cannot honour would be a
    // promise nothing keeps.
    val visible = remember(results, filter, ascending) {
        val needle = filter.trim().lowercase()
        results
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) }
            .sortedBy { it.title.lowercase() }
            .let { if (ascending) it else it.reversed() }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenBackButton(onBack)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(shura("discover.search")) },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run() }, enabled = !searching) { Text(shura("nav.discover")) }
        }

        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { run() }) { Text(shura("common.retry")) }
            }
            searched && visible.isEmpty() -> StatePanel(shura("discover.noResults"), source.name)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (searched && results.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(shura("search.filter")) },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = ascending,
                                onClick = { ascending = !ascending },
                                label = {
                                    Text(
                                        if (ascending) shura("search.sortAz")
                                        else shura("search.sortZa"),
                                    )
                                },
                            )
                        }
                    }
                }
                items(visible, key = { it.id }) { manga ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenManga(manga) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            // The cover is an image. It was rendered as its own
                            // URL as text, which put a long
                            // https://... string on every result card.
                            Row {
                                CoverImage(
                                    source = manga.coverUrl,
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 80.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    retryLabel = shura("common.retry"),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    manga.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (searched && !exhausted) {
                    item {
                        TextButton(
                            onClick = { fetch(page + 1, append = true) },
                            enabled = !loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (loadingMore) shura("common.loading")
                                else shura("search.loadMore"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Appends a page without duplicating titles.
 *
 * Sources paginate by offset, so a title can appear on two pages when the
 * catalogue changes underneath a slow search. The first sighting wins: the
 * reader asked for it earlier, and a duplicated card reads as a bug.
 */
private fun mergeResults(
    existing: List<MangaSummary>,
    more: List<MangaSummary>,
): List<MangaSummary> {
    val seen = existing.mapTo(mutableSetOf()) { it.id }
    return existing + more.filter { seen.add(it.id) }
}

/** A title's chapters, newest first, which is the order they are read in. */
@Composable
fun ChapterListScreen(
    mangaTitle: String,
    source: ShuraSource,
    mangaId: String,
    onOpenChapter: (Chapter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The list that was actually fetched, handed upward so the reader can step
     * between chapters without a second round trip.
     */
    onChaptersLoaded: (List<Chapter>) -> Unit = {},
    /**
     * Chapters with reading progress, by chapter id.
     *
     * Read state is derived from what the reader actually did rather than from a
     * separate flag: a chapter with a saved page is one the reader has been in,
     * and a flag nothing writes would only ever say "unread".
     */
    readChapterIds: Set<String> = emptySet(),
    /** Chapter ids already saved to the library. */
    downloadedChapterIds: Set<String> = emptySet(),
    /** Chapters with a download row, whatever its state. */
    queuedChapterIds: Set<String> = emptySet(),
    /** Saves the chapter for offline reading. Null hides the button entirely. */
    onDownloadChapter: ((Chapter) -> Unit)? = null,
) {
    val language = LocalUiLanguage.current
    var chapters by remember(mangaId) { mutableStateOf<List<Chapter>?>(null) }
    var error by remember(mangaId) { mutableStateOf<String?>(null) }
    var unreadOnly by remember(mangaId) { mutableStateOf(false) }
    var ascending by remember(mangaId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mangaId) {
        runCatching { withContext(Dispatchers.IO) { source.chapters(mangaId) } }
            .onSuccess {
                chapters = it
                onChaptersLoaded(it)
            }
            .onFailure { error = it.message ?: Strings.t("error.generic", language) }
    }

    // Newest first unless the reader asks otherwise: a chapter list that opens on
    // chapter 1 of 400 is the reason readers tap "next chapter" instead of reading.
    // The order itself lives in ChapterListOrder, because the work's page batches
    // downloads out of the same list and the two must not disagree about it.
    val visible = remember(chapters, unreadOnly, ascending, readChapterIds) {
        val filtered = chapters.orEmpty().filter { !unreadOnly || it.id !in readChapterIds }
        ChapterListOrder.reading(filtered, ascending)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            ScreenBackButton(onBack)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = unreadOnly,
                onClick = { unreadOnly = !unreadOnly },
                label = { Text(shura("chapters.unreadOnly")) },
            )
            FilterChip(
                selected = ascending,
                onClick = { ascending = !ascending },
                label = { Text(if (ascending) shura("chapters.oldestFirst") else shura("chapters.newestFirst")) },
            )
        }
        when {
            error != null -> StatePanel(shura("common.error"), error!!)
            chapters == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            visible.isEmpty() -> StatePanel(shura("discover.noChapters"), mangaTitle)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text(
                        shura("discover.chapters"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(visible, key = { it.id }) { chapter ->
                    val read = chapter.id in readChapterIds
                    val downloaded = chapter.id in downloadedChapterIds
                    val queued = chapter.id in queuedChapterIds
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpenChapter(chapter) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Mihon's shape: the number in a column of its own, so a
                            // list of four hundred lines can be scanned for "12"
                            // without reading four hundred titles. Sources are loose
                            // about chapter numbers - some are null, some are 12.5 -
                            // so the column is a fixed width and never moves.
                            Text(
                                chapterNumberLabel(chapter.number),
                                Modifier.width(46.dp).padding(end = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapter.name.ifBlank { chapter.id },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    // Read chapters recede instead of disappearing: the
                                    // filter is there for that, and hiding history by
                                    // default is how a reader loses their place.
                                    color = if (read) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            // The word sits on the trailing edge rather than under
                            // the title, so a read row is not taller than the one
                            // above it and the column still scans as a column.
                            if (read) {
                                Text(
                                    shura("chapters.read"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            when {
                                // Saved already: a tick, so the row says it without
                                // a button that would offer the same work twice.
                                downloaded -> Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.padding(horizontal = 10.dp).size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                onDownloadChapter == null -> Unit
                                else -> TextButton(
                                    onClick = { onDownloadChapter(chapter) },
                                    enabled = !queued,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        if (queued) shura("chapters.queued") else shura("chapters.download"),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A chapter number as a reader would write it.
 *
 * Sources report numbers loosely: absent, whole, or "Chapter 12.5". Rendering the
 * Float directly put "1.0" in the leading column of every list, so whole numbers
 * lose the decimal and the rest keep only their real fraction.
 */
private fun chapterNumberLabel(number: Float?): String {
    if (number == null) return "—"
    val whole = number.toLong()
    return if (whole.toFloat() == number) whole.toString() else number.toString()
}

/** Saved chapters, with read and delete. */
/**
 * A work's own page.
 *
 * Details and chapters used to be the same screen, so there was nowhere to put
 * a cover, a source, or a "start reading" action without burying them under a
 * chapter list. This is that page, and the chapter list is its own thing.
 *
 * The page fetches the chapter list once, off the main thread, the way the
 * chapter list does - and that one fetch is what makes the rest of the page
 * possible: "Start" needs to know chapter one exists, "Continue" needs to find
 * the chapter the reader stopped in, and the batch downloads need to know which
 * chapters are left. None of them can be answered from a title alone.
 */
@Composable
fun WorkDetailsScreen(
    manga: MangaSummary,
    source: ShuraSource,
    sourceName: String,
    /**
     * The source's own language, shown as a chip.
     *
     * There is no genre or author data in the source contract, so this page says
     * what it actually knows: which source this came from and in what language.
     */
    sourceLanguage: String = "",
    inLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onReadLatest: (Chapter) -> Unit,
    onOpenChapters: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    /** Shares the title's name and source. Null hides the action. */
    onShare: (() -> Unit)? = null,
    /**
     * The list that was fetched, handed upward so "Continue" can open the reader
     * with the chapters around it rather than with none.
     */
    onChaptersLoaded: (List<Chapter>) -> Unit = {},
    /**
     * Whether the reader has been through this chapter, by id.
     *
     * A question rather than a set, because the chapter list is only known once
     * this page has fetched it - a set would have to be built somewhere that
     * cannot see the list yet.
     */
    isRead: (chapterId: String) -> Boolean = { false },
    /** What the reader has read recently: what decides Start and Continue. */
    history: List<ReadingProgress> = emptyList(),
    /** Chapter ids already saved to the library. */
    downloadedChapterIds: Set<String> = emptySet(),
    /** Chapters with a download row, whatever its state. */
    queuedChapterIds: Set<String> = emptySet(),
    /** Saves chapters for offline reading. Null hides the batch actions. */
    onDownloadChapter: ((Chapter) -> Unit)? = null,
) {
    val language = LocalUiLanguage.current
    var chapters by remember(manga.id) { mutableStateOf<List<Chapter>?>(null) }
    var chaptersError by remember(manga.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(manga.id) {
        runCatching { withContext(Dispatchers.IO) { source.chapters(manga.id) } }
            .onSuccess {
                chapters = it
                onChaptersLoaded(it)
            }
            .onFailure { chaptersError = it.message ?: Strings.t("error.generic", language) }
    }

    // Newest first: the order the chapter list opens in, so the batch buttons
    // offer the chapters the reader is looking at rather than a second opinion
    // about which end of the work comes next.
    val visible = remember(chapters) {
        chapters?.let { ChapterListOrder.reading(it, ascending = false) }
    }
    val lastRead = remember(history, source.id, manga.id, chapters) {
        DetailsResume.hintFor(history, source.id, manga.id, chapters.orEmpty())
    }
    // Nothing is offered while the fetch is still in flight: a "Start" that
    // appears before chapter one is known is a button that cannot act yet.
    val settled = chapters != null || chaptersError != null
    val target = remember(lastRead, chapters) { DetailsResume.target(lastRead, chapters) }

    val readIds = remember(visible, isRead) {
        visible.orEmpty().filter { isRead(it.id) }.mapTo(mutableSetOf()) { it.id }
    }
    val batch = remember(readIds, downloadedChapterIds, queuedChapterIds) {
        BatchState(read = readIds, downloaded = downloadedChapterIds, queued = queuedChapterIds)
    }
    val nextFive = remember(visible, batch) { BatchDownload.take(visible.orEmpty(), batch, 5) }
    val nextTen = remember(visible, batch) { BatchDownload.take(visible.orEmpty(), batch, 10) }
    val rest = remember(visible, batch) { BatchDownload.take(visible.orEmpty(), batch, null) }
    // What the buttons will actually do right now. Empty means every chapter is
    // read, saved or already queued, and saying so beats offering a button that
    // would only re-download what the reader already has.
    val canQueue = visible != null && nextFive.isNotEmpty()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            ScreenBackButton(onBack)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Mihon's shape: the cover is a fixed 2:3 plate rather than whatever
            // the source's image happens to be, so the page reads the same for
            // every source.
            CoverImage(
                source = manga.coverUrl,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.medium),
                contentDescription = manga.title,
                retryLabel = shura("common.retry"),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    sourceName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onShare?.let { share ->
                        OutlinedIconButton(onClick = share) {
                            Icon(Icons.Default.Share, contentDescription = shura("common.share"))
                        }
                    }
                    // The favourite is the library toggle: the heart is filled and
                    // violet exactly when the work is in the library, so the state
                    // is readable without reading the button's label.
                    OutlinedIconButton(onClick = onToggleLibrary) {
                        Icon(
                            if (inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = shura(
                                if (inLibrary) "library.delete" else "library.title",
                            ),
                            tint = if (inLibrary) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // What is actually known about this title. No genre chips: the source
        // contract carries none, and a made-up one would be a lie in the place a
        // reader looks to decide.
        if (sourceName.isNotBlank() || sourceLanguage.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Weighted but not filling: a source with a long name takes what
                // it needs and gives the rest back, so the language chip is never
                // pushed off the end of the row.
                if (sourceName.isNotBlank()) {
                    Box(Modifier.weight(1f, fill = false)) { MetaChip(sourceName) }
                }
                if (sourceLanguage.isNotBlank()) {
                    Box(Modifier.weight(1f, fill = false)) { MetaChip(sourceLanguage) }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One button, saying which of the two it is. "Start" opens chapter
            // one; "Continue" opens the chapter the reader last read, and the
            // reader restores the page they reached from the progress store.
            if (settled) {
                target?.let { opening ->
                    Button(
                        onClick = { onReadLatest(opening.chapter) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            shura(
                                if (DetailsResume.isContinuing(lastRead))
                                    "details.continue"
                                else "details.start",
                            ),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onOpenChapters,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(shura("discover.chapters")) }
            val download = onDownloadChapter
            if (download != null && visible != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    shura("details.download"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        shura("details.downloadNext", 5) to nextFive,
                        shura("details.downloadNext", 10) to nextTen,
                        shura("details.downloadAll") to rest,
                    ).forEach { (label, targets) ->
                        // Enabled only while the batch has something in it. Once
                        // the chapters are queued the next tap offers the ones
                        // after them, so a second tap queues the next five rather
                        // than refusing them silently.
                        OutlinedButton(
                            onClick = { targets.forEach { chapter -> download(chapter) } },
                            enabled = targets.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (!canQueue) {
                    Text(
                        shura("details.downloadNone"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when {
            error != null -> StatePanel(shura("common.error"), error)
            chaptersError != null -> StatePanel(shura("common.error"), chaptersError!!)
            loading || chapters == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A fact about a title, as a chip rather than a sentence. */
@Composable
private fun MetaChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
