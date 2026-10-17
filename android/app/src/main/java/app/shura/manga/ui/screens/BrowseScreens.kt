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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.shura.manga.ui.ExploreLoadGate
import app.shura.manga.ui.Strings
import app.shura.manga.ui.image.CoverImage
import app.shura.manga.ui.theme.ShuraSpacing
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

    // Entering the screen is the request.
    //
    // It used to be nothing but the search button: `results` started empty,
    // `searching` was false and `error` was null, so the screen opened onto the
    // one branch that draws nothing at all - no list, no spinner, no error and
    // nothing that said a request had never been made. A reader could not tell
    // that from a source with no results until they had pressed a button.
    //
    // Keyed by the source, so it starts once per entry into this source's
    // catalogue and again when a different source is opened, and not once per
    // recomposition. The gate says the same thing in a form a test can hold on
    // to: one request per entry, however many times the screen is recomposed.
    val loadGate = remember(source.id) { ExploreLoadGate() }
    LaunchedEffect(source.id) {
        if (loadGate.shouldStart(source.id)) run()
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
        // One control row: the query, and the button that runs it. The back arrow
        // is in the app's bar now, so this row is entirely about searching.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShuraSpacing.screen, end = ShuraSpacing.screen, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = shura("discover.search"),
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(
                        onClick = { run() },
                        enabled = !searching,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = shura("search.submit"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }

        when {
            searching -> LoadingPanel(shura("discover.searching"), Modifier.fillMaxSize())
            error != null -> EmptyState(
                title = shura("common.error"),
                body = error,
                icon = Icons.Default.Warning,
                actionLabel = shura("common.retry"),
                onAction = { run() },
                modifier = Modifier.fillMaxSize(),
            )
            searched && visible.isEmpty() -> EmptyState(
                title = shura("discover.noResults"),
                body = shura("discover.noResultsHelp"),
                icon = Icons.Default.Search,
                actionLabel = shura("common.clear"),
                onAction = { query = "" },
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ShuraSpacing.screen,
                    end = ShuraSpacing.screen,
                    top = 6.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
            ) {
                if (searched && results.isNotEmpty()) {
                    item {
                        SummaryStrip(shura("search.results", visible.size))
                    }
                    item {
                        // Narrowing and ordering, in one line. These used to be a
                        // full-width outlined field with a label floating above it
                        // and a chip beside it - two rows of chrome above the first
                        // result, for two settings.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SearchField(
                                value = filter,
                                onValueChange = { filter = it },
                                placeholder = shura("search.filter"),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { ascending = !ascending }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = shura(
                                        if (ascending) "search.sortAz" else "search.sortZa",
                                    ),
                                )
                            }
                        }
                    }
                }
                items(visible, key = { it.id }) { manga ->
                    SearchResultRow(
                        manga = manga,
                        sourceName = detailJoin(source.name, source.language),
                        onClick = { onOpenManga(manga) },
                    )
                }
                if (searched && !exhausted) {
                    item {
                        OutlinedButton(
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
 * One search result.
 *
 * A fixed 2:3 cover on the leading edge - the same plate the library and the
 * work's page use - and everything else stacked beside it, so the eye runs down
 * one column of titles instead of across a row of floating words. The way onward
 * is a chevron on the trailing edge, which mirrors with the layout direction.
 */
@Composable
private fun SearchResultRow(
    manga: MangaSummary,
    sourceName: String,
    onClick: () -> Unit,
) {
    AppRow(
        title = manga.title,
        meta = sourceName,
        titleLines = 2,
        onClick = onClick,
        modifier = Modifier.padding(vertical = 1.dp),
        leading = { CoverPlate(manga.coverUrl, width = 52.dp, label = manga.title) },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
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
    /**
     * Saves every chapter of this work that is left, in one step.
     *
     * A different action from the one-chapter button on each row, and a
     * different one from the section's own "all chapters" control - that one
     * reveals the rest of the list, this one saves what is in it. Null hides the
     * action rather than offering a batch that cannot be started.
     */
    onDownloadAll: ((List<Chapter>) -> Unit)? = null,
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
    // downloads out of the same chapters - and deliberately asks it for the other
    // end, so a batch saves the story from the start while this list shows the
    // newest update. One arrangement of the data, two honest orders.
    //
    // Repeated chapter ids are collapsed on the way through, by the same
    // function: a source that hands back one chapter twice must not produce two
    // rows here - two download buttons for one chapter, two entries in the
    // reader's step-through, and a lazy list that cannot hold one key twice.
    val visible = remember(chapters, unreadOnly, ascending, readChapterIds) {
        val filtered = chapters.orEmpty().filter { !unreadOnly || it.id !in readChapterIds }
        ChapterListOrder.reading(filtered, ascending)
    }
    // What "download all chapters" would take right now, decided the same way the
    // work's page decides its batch, from the same three sets - so the control
    // here and the buttons there can never disagree about what is left.
    //
    // From the work's own chapters, not from what the list is currently showing:
    // "download all chapters" is a statement about the work, not about the
    // filter the reader happens to have on.
    val pendingAll = remember(chapters, readChapterIds, downloadedChapterIds, queuedChapterIds) {
        BatchDownload.downloadAll(
            chapters.orEmpty(),
            BatchState(read = readChapterIds, downloaded = downloadedChapterIds, queued = queuedChapterIds),
        )
    }

    Column(modifier.fillMaxSize()) {
        // The two things a reader changes about a chapter list, on one line, above
        // the list. No back arrow: that is the bar's job now, on every pushed
        // screen, instead of a row of its own here.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShuraSpacing.screen, end = ShuraSpacing.screen, top = 8.dp, bottom = 2.dp),
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
            error != null -> EmptyState(
                // No button here: the bar's arrow is the way out, and a "retry"
                // that re-reads state this screen no longer fetches would be the
                // dead control this app keeps trying not to ship.
                title = shura("common.error"),
                body = error,
                icon = Icons.Default.Warning,
                modifier = Modifier.fillMaxSize(),
            )
            chapters == null -> LoadingPanel(shura("common.loading"), Modifier.fillMaxSize())
            visible.isEmpty() -> EmptyState(
                title = shura("discover.noChapters"),
                body = mangaTitle,
                icon = ShuraIcons.Series,
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ShuraSpacing.screen,
                    end = ShuraSpacing.screen,
                    top = 6.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
            ) {
                item {
                    // How many chapters this work has, above the rows, rather than
                    // a heading that repeats what the bar already says.
                    SectionHeader(
                        label = detailJoin(
                            shura("discover.chapters"),
                            shura("discover.chapterCount", visible.size),
                        ),
                        // Saving the whole work, on the header of its own chapter
                        // list. Separate from this screen's per-row button, and
                        // separate from the "all chapters" control on the work's
                        // page, which reveals the rest of the list instead of
                        // saving it. Disabled when there is nothing left to take,
                        // which is the same rule the work's page's batch buttons
                        // use.
                        trailingLabel = onDownloadAll?.let { shura("downloads.downloadAll") },
                        onTrailing = onDownloadAll?.takeIf { pendingAll.isNotEmpty() }
                            ?.let { batch -> { batch(pendingAll) } },
                    )
                }
                items(visible, key = { it.id }) { chapter ->
                    // The row is drawn by the same code the work's own page uses,
                    // so a chapter looks the same wherever it is listed.
                    ChapterRow(
                        chapter = chapter,
                        sourceName = source.name,
                        read = chapter.id in readChapterIds,
                        downloaded = chapter.id in downloadedChapterIds,
                        queued = chapter.id in queuedChapterIds,
                        onOpen = { onOpenChapter(chapter) },
                        onDownload = onDownloadChapter?.let { download ->
                            { download(chapter) }
                        },
                    )
                }
            }
        }
    }
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
    /**
     * Saves a batch of chapters in one step. Null falls back to one call per
     * chapter, which is correct and slow on a long series.
     */
    onDownloadBatch: ((List<Chapter>) -> Unit)? = null,
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

    // Reading order, from chapter one. Not the chapter list's own newest-first
    // order: a batch is a decision about what to save, and the first chapters of
    // the story are what a reader who has just picked a work up wants saved. The
    // list screen keeps its own order and its own sort chips - this page never
    // shows a list, so there is nothing here for the chips to disagree with.
    val batchOrder = remember(chapters) { chapters?.let { BatchDownload.inReadingOrder(it) } }
    val lastRead = remember(history, source.id, manga.id, chapters) {
        DetailsResume.hintFor(history, source.id, manga.id, chapters.orEmpty())
    }
    // Nothing is offered while the fetch is still in flight: a "Start" that
    // appears before chapter one is known is a button that cannot act yet.
    val settled = chapters != null || chaptersError != null
    val target = remember(lastRead, chapters) { DetailsResume.target(lastRead, chapters) }

    // Read state is asked about this work's own chapters, and it is a set: which
    // order the batch walks them in makes no difference to which are read.
    val readIds = remember(batchOrder, isRead) {
        batchOrder.orEmpty().filter { isRead(it.id) }.mapTo(mutableSetOf()) { it.id }
    }
    val batch = remember(readIds, downloadedChapterIds, queuedChapterIds) {
        BatchState(read = readIds, downloaded = downloadedChapterIds, queued = queuedChapterIds)
    }
    val nextFive = remember(batchOrder, batch) { BatchDownload.take(batchOrder.orEmpty(), batch, 5) }
    val nextTen = remember(batchOrder, batch) { BatchDownload.take(batchOrder.orEmpty(), batch, 10) }
    val rest = remember(batchOrder, batch) { BatchDownload.take(batchOrder.orEmpty(), batch, null) }
    // What the buttons will actually do right now. Empty means every chapter is
    // read, saved or already queued, and saying so beats offering a button that
    // would only re-download what the reader already has.
    val canQueue = batchOrder != null && nextFive.isNotEmpty()
    // The newest chapters, to show on the page itself. Newest first, the way the
    // reader arrives at a series; a batch still walks in reading order, because a
    // batch is a decision about what to save rather than a view of the list.
    val preview = remember(chapters) {
        chapters?.let { ChapterListOrder.reading(it, ascending = false).take(PREVIEW_CHAPTERS) }
    }
    // The whole work's chapter count, read once. `chapters` is a delegated
    // property so it cannot be smart-cast, and the count has to be the real one:
    // heading the preview with "5 chapters" on a work that has four hundred is
    // the kind of small lie that makes a reader think they have read the lot.
    val chapterCount = chapters?.size ?: 0

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            // The cover block: artwork on the leading edge at a fixed 2:3, and
            // beside it the only three facts the source contract actually knows -
            // the title, where it came from, and how long it is.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ShuraSpacing.screen,
                        end = ShuraSpacing.screen,
                        top = 10.dp,
                    ),
            ) {
                CoverImage(
                    source = manga.coverUrl,
                    modifier = Modifier
                        .width(116.dp)
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.small),
                    contentDescription = manga.title,
                    retryLabel = shura("common.retry"),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        manga.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (sourceName.isNotBlank() || sourceLanguage.isNotBlank()) {
                        Text(
                            detailJoin(sourceName, sourceLanguage),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // How long the work is, once the chapter list has said. Absent
                    // until then rather than guessed at as "…" - it is a count, and
                    // a made-up count is worse than no count.
                    if (chapters != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            shura("discover.chapterCount", chapterCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // What can be done to this work, as a row of actions under the block.
            // They used to be two outlined circles squeezed against the title,
            // which left the third thing - the full chapter list - with nowhere to
            // go and no label on either of them.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ShuraSpacing.screen, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onShare?.let { share ->
                    ActionTile(
                        icon = Icons.Default.Share,
                        label = shura("common.share"),
                        onClick = share,
                    )
                }
                // The favourite is the library toggle: filled and crimson exactly
                // when the work is in the library, so the state is readable without
                // reading the label.
                ActionTile(
                    icon = if (inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = shura(if (inLibrary) "details.inLibrary" else "details.addLibrary"),
                    onClick = onToggleLibrary,
                    selected = inLibrary,
                )
                ActionTile(
                    icon = ShuraIcons.Series,
                    label = shura("discover.chapters"),
                    onClick = onOpenChapters,
                )
            }

            when {
                error != null -> EmptyState(
                    title = shura("common.error"),
                    body = error,
                    icon = Icons.Default.Warning,
                    modifier = Modifier.height(260.dp),
                )
                chaptersError != null -> EmptyState(
                    title = shura("common.error"),
                    body = chaptersError,
                    icon = Icons.Default.Warning,
                    modifier = Modifier.height(260.dp),
                )
                loading || chapters == null -> LoadingPanel(
                    shura("common.loading"),
                    Modifier.height(200.dp),
                )
                // The chapters themselves, in the row shape every chapter list in
                // the app uses, with the way to all of them on the section's end.
                preview.isNullOrEmpty() -> EmptyState(
                    title = shura("discover.noChapters"),
                    body = manga.title,
                    icon = ShuraIcons.Series,
                    modifier = Modifier.height(260.dp),
                )
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ShuraSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
                ) {
                    SectionHeader(
                        detailJoin(
                            shura("discover.chapters"),
                            shura("discover.chapterCount", chapterCount),
                        ),
                        trailingLabel = shura("discover.allChapters"),
                        onTrailing = onOpenChapters,
                    )
                    preview.forEach { chapter ->
                        ChapterRow(
                            chapter = chapter,
                            sourceName = sourceName,
                            read = isRead(chapter.id),
                            downloaded = chapter.id in downloadedChapterIds,
                            queued = chapter.id in queuedChapterIds,
                            onOpen = { onReadLatest(chapter) },
                            onDownload = onDownloadChapter?.let { download ->
                                { download(chapter) }
                            },
                        )
                    }
                }
            }
            val download = onDownloadChapter
            if (download != null && batchOrder != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ShuraSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionHeader(shura("details.download"))
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
                            //
                            // The whole list goes to the queue in one call where it
                            // can: "download the rest" on a long series is hundreds
                            // of chapters, and asking for them one at a time made
                            // the tap itself the expensive part.
                            OutlinedButton(
                                onClick = {
                                    onDownloadBatch?.invoke(targets)
                                        ?: targets.forEach { chapter -> download(chapter) }
                                },
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
            // Room for the pill, so the last control is never behind it.
            Spacer(Modifier.height(PILL_CLEARANCE))
        }

        // "Start", floating.
        //
        // The one thing almost every reader came to this page to do, and it was a
        // full-width button in the middle of a column that had to be scrolled
        // past first. As a pill at the bottom it is under the thumb, in the place
        // every reader expects it, and it only appears once the chapter list has
        // settled - a pill that cannot act yet is worse than no pill.
        if (settled) {
            target?.let { opening ->
                StartPill(
                    label = shura(
                        if (DetailsResume.isContinuing(lastRead)) "details.continue"
                        else "details.start",
                    ),
                    onClick = { onReadLatest(opening.chapter) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = ShuraSpacing.screen),
                )
            }
        }
    }
}

/** How many chapters the work's own page shows before offering the whole list. */
private const val PREVIEW_CHAPTERS = 5

/** Height kept clear at the bottom of the page for the floating pill. */
private val PILL_CLEARANCE = 104.dp

/**
 * The floating way into a work: one pill, one action, under the thumb.
 *
 * The glyph leads, so in Arabic the pill reads "ابدأ ▶" - the word first, the way
 * it is written, with the play mark pointing the way the reader reads rather than
 * always to the right.
 */
@Composable
private fun StartPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
