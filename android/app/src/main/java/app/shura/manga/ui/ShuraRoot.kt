package app.shura.manga.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.shura.host.SourceHost
import app.shura.manga.StoredChapter
import app.shura.manga.reader.ProgressLookup
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationSettings
import app.shura.manga.reader.translation.TranslationSettingsStore
import app.shura.manga.LibraryWork
import app.shura.manga.isWorkInLibrary
import app.shura.manga.listLibraryWorks
import app.shura.manga.removeWork
import app.shura.manga.storeWork
import app.shura.manga.workLibrary
import app.shura.manga.ui.screens.*
import app.shura.manga.ui.theme.LocalUiLanguage
import app.shura.manga.ui.theme.ShuraTheme
import app.shura.manga.ui.theme.UiLanguage
import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import app.shura.source.ShuraSource

/**
 * The screens, and the path between them.
 *
 * One linear flow - source, title, chapters, reader - with the reader able to
 * step to the next chapter without going back through the list. Each screen is
 * handed the state it needs and returns a decision, so navigation state lives
 * here rather than being smeared across the composables.
 */
/**
 * Fetches a chapter's pages as local files the reader can decode.
 *
 * [pageAt] is the one progressive loading needs: given one page's reference,
 * produce that page's file. [pages] stays for callers that genuinely want the
 * whole chapter at once, such as saving it to the library.
 */
fun interface ChapterDownloader {
    suspend fun pageAt(source: ShuraSource, chapterId: String, page: Page): Uri

    suspend fun pages(source: ShuraSource, chapterId: String): List<Uri> {
        val refs = source.pages(chapterId)
        return refs.map { pageAt(source, chapterId, it) }
    }
}

class ShuraNav(
    val sourceHost: SourceHost,
    val downloader: ChapterDownloader,
    val settingsStore: TranslationSettingsStore,
    val progressStore: ReadingProgressStore,
    val translationCache: TranslationCache,
    val context: android.content.Context,
    /** Chapter saves: the queue behind the per-chapter download button. */
    val downloads: app.shura.manga.DownloadQueue,

    /** Covers of works already seen this session, by (source, title). */
    val covers: ProgressLookup = ProgressLookup(),
    /** Whether a translation engine is reachable. False today. */
    val translationEngineAvailable: Boolean = false,
) {
    fun rememberCover(sourceId: String, title: String, cover: String?) =
        covers.remember(sourceId, title, cover)

    fun coverFor(sourceId: String, title: String): String = covers.coverFor(sourceId, title)
}

sealed interface ShuraScreen {
    data object Home : ShuraScreen
    data object Sources : ShuraScreen
    data object Library : ShuraScreen
    data object Downloads : ShuraScreen
    data object Settings : ShuraScreen
    data object Extensions : ShuraScreen
    data class Search(val sourceId: String) : ShuraScreen
    data class Details(val sourceId: String, val manga: MangaSummary) : ShuraScreen
    data class Chapters(val sourceId: String, val manga: MangaSummary) : ShuraScreen
    data class Reader(
        val sourceId: String,
        val mangaTitle: String,
        val chapter: Chapter,
        val chapters: List<Chapter>,
    ) : ShuraScreen
}

@Composable
fun ShuraRoot(
    nav: ShuraNav,
    language: UiLanguage,
    onLanguage: (UiLanguage) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    pages: List<Uri>,
    /** Called with the page now on screen, so downloads can stay ahead of it. */
    onPageVisible: (Int) -> Unit = {},
    /** Re-fetches one failed page in place. */
    onRetryPage: (Int) -> Unit = {},
    /** The chapter's full page count, while [pages] holds only what has loaded. */
    totalPages: () -> Int? = { null },
    /** The reader's saved layout, vertical or one page at a time. */
    readerMode: app.shura.manga.reader.ReaderMode = app.shura.manga.reader.ReaderMode.VERTICAL,
    onReaderModeChange: (app.shura.manga.reader.ReaderMode) -> Unit = {},
    /** Brightness, preload, screen-awake and download parallelism. */
    readerPreferences: app.shura.manga.reader.ReaderPreferences = app.shura.manga.reader.ReaderPreferences(),
    onReaderPreferencesChange: (app.shura.manga.reader.ReaderPreferences) -> Unit = {},
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onExitReader: () -> Unit,
    history: List<ReadingProgress>,
    onProgressChanged: (Int) -> Unit,
    library: List<StoredChapter>,
    repositoryNotice: String?,
    onInstallRepository: () -> Unit,
    extensions: List<app.shura.manga.ui.screens.ExtensionRowState> = emptyList(),
    /** Downloads and verifies one extension, then makes it available. */
    onInstallExtension: (app.shura.manga.ExtensionEntry) -> Unit = {},
    /** Bumped on removal so the source list is re-read rather than cached. */
    sourcesVersion: Int = 0,
    extensionCatalog: app.shura.manga.ExtensionCatalog? = null,
    onRemoveExtension: (app.shura.manga.ExtensionEntry) -> Unit = {},
    /**
     * Starts fetching a chapter's pages. Navigation is owned here, so the
     * reader's route always names the chapter whose pages are on screen -
     * fetching and navigating are one step, not two that can drift apart.
     */
    onFetchChapter: (ShuraSource, Chapter, String) -> Unit = { _, _, _ -> },
    /**
     * Opens a stored chapter. Its pages are already on disk, so there is
     * nothing to fetch - but the reader still has to be pointed at them, and
     * navigating there without doing so used to show whatever was left over
     * from the last chapter.
     */
    onOpenStored: (StoredChapter) -> Unit = {},
    /**
     * Refetches a title's chapter list so a resumed chapter knows its
     * neighbours. Null means the source could not be reached.
     */
    fetchChapters: suspend (ShuraSource, String) -> List<Chapter>? = { _, _ -> null },
) {
    ShuraTheme(dark = dark) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalUiLanguage provides language,
            androidx.compose.ui.platform.LocalLayoutDirection provides
                if (language == UiLanguage.ARABIC)
                    androidx.compose.ui.unit.LayoutDirection.Rtl
                else androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
            // A real back stack, not one screen with a name.
            //
            // The reader used to be `route = Reader(...)` on a single state
            // variable, which means there was nowhere to go *back* to: Android's
            // back button had no handler at all and either did nothing or left
            // the app, so a chapter could only be left through Shura's own
            // arrow. Every navigation now pushes, and back pops.
            val backStack = remember { ScreenStack() }
            val route = backStack.current

            // Read markers and the library both change behind the chapter list: the
            // reader saves progress as it goes and a download finishes on its own.
            // Bumped on every navigation, so coming back shows what is true now rather
            // than what was true when the list was built.
            var readVersion by remember { mutableIntStateOf(0) }
            // Works the reader follows, and the directory they live in.
            val workDir = remember { workLibrary(nav.context) }
            var workVersion by remember { mutableIntStateOf(0) }
            val followedWorks = remember(workVersion) { listLibraryWorks(workDir) }
            val downloadJobs by nav.downloads.jobs.collectAsState()
            var libraryVersion by remember { mutableIntStateOf(0) }

            fun go(target: ShuraScreen) {
                backStack.go(target)
                readVersion += 1
                libraryVersion += 1
            }

            /** Pops to the previous screen. False when Home is already showing. */
            fun back(): Boolean {
                val popped = backStack.back()
                if (popped) {
                    readVersion += 1
                    libraryVersion += 1
                }
                return popped
            }

            // The system back button and the edge-swipe gesture both arrive
            // here. Reader included, and regardless of whether its tools are
            // showing - the chapter is not a trap either way.
            androidx.activity.compose.BackHandler(enabled = backStack.size > 1) { back() }
            var settings by remember { mutableStateOf(nav.settingsStore.load()) }
            var libraryState by remember { mutableStateOf(library) }
            // The chapters the list screen fetched, carried into the reader so
            // its previous/next have something real to step through.
            var loadedChapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
            // The chapter list a work's own page fetched, with the work it is
            // for. Paired rather than kept alone because "the last list" is not
            // good enough: open a second work while its fetch is still in
            // flight and a bare list would hand that work's reader the first
            // work's chapters to step through.
            var detailsChapters by remember { mutableStateOf<Pair<String, List<Chapter>>?>(null) }
            val sourceOf = { id: String -> nav.sourceHost.find(id) }
            val resumeScope = rememberCoroutineScope()

            // Move the reader onto a chapter and start loading it, as one step.
            // Fetching and navigating separately let the route keep naming the
            // old chapter while the new one's pages were already on screen.
            fun openChapter(
                sourceId: String,
                mangaTitle: String,
                source: ShuraSource?,
                chapter: Chapter,
                chapters: List<Chapter>,
                cover: String = "",
            ) {
                if (source == null) return
                go(ShuraScreen.Reader(sourceId, mangaTitle, chapter, chapters))
                onFetchChapter(source, chapter, cover)
            }

            androidx.compose.material3.Scaffold(
                // Mihon's layout: the title on top, the destinations under it as
                // tabs. A bottom bar put the navigation where a thumb rests and
                // ate the width the title needs; on a narrow phone four long
                // Arabic labels in a bottom bar were already too many.
                topBar = {
                    if (route is ShuraScreen.Reader) return@Scaffold
                    ShuraTabBar(
                        current = route,
                        title = titleOf(route),
                        actions = { TopBarActions(route) },
                        onNavigate = { target ->
                            // A tab is a root, so it replaces the stack rather
                            // than growing it; otherwise back would walk out of
                            // the app through every tab ever visited.
                            backStack.switchTab(target)
                            readVersion += 1
                            libraryVersion += 1
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (val screen = route) {
                        is ShuraScreen.Home -> HomeScreen(
                            progress = history.filter { it.chapterId != "" },
                            onOpenEntry = { entry ->
                                // Continue resumes the chapter for real: the
                                // pages are re-fetched and the route moves with
                                // them, rather than opening a reader over
                                // whatever was last loaded.
                                val source = sourceOf(entry.sourceId)
                                if (source != null) {
                                    // Resume with the real list: without it the
                                    // reader opens with no previous/next at all.
                                    val stored = Chapter(entry.chapterId, entry.chapterName, null)
                                    // Fetched off the main thread: a resume
                                    // refetches the whole list, and doing that
                                    // inline freezes the tap that triggered it.
                                    resumeScope.launch {
                                        val resolved = ChapterResume.resolve(
                                            stored,
                                            if (entry.mangaId.isBlank()) null
                                            else fetchChapters(source, entry.mangaId),
                                        )
                                        openChapter(
                                            entry.sourceId, entry.mangaTitle, source,
                                            resolved.chapter, resolved.chapters,
                                        )
                                    }
                                }
                            },
                            onBrowse = { go(ShuraScreen.Sources) },
                            onOpenLibrary = { go(ShuraScreen.Library) },
                        )

                        is ShuraScreen.Sources -> SourcePickerScreen(
                            sources = remember(sourcesVersion) { nav.sourceHost.all() },
                            onPick = { go(ShuraScreen.Search(it.id)) },
                            onOpenSettings = { go(ShuraScreen.Settings) },
                        )

                        is ShuraScreen.Library -> LibraryScreen(
                            history = history,
                            stored = libraryState,
                            followed = followedWorks,
                            onOpenDownloads = { go(ShuraScreen.Downloads) },
                            onOpenWork = { work ->
                                // A followed work with nothing saved has no chapter to
                                // resume, so it opens its chapter list instead - the
                                // same place the work's own page goes.
                                go(
                                    ShuraScreen.Chapters(
                                        work.sourceId,
                                        app.shura.source.MangaSummary(work.mangaId, work.title, work.coverUrl),
                                    ),
                                )
                            },
                            onDeleteWork = { work ->
                                resumeScope.launch {
                                    withContext(Dispatchers.IO) {
                                        removeWork(workDir, LibraryWork(work.sourceId, work.mangaId, work.title, work.coverUrl))
                                    }
                                    workVersion += 1
                                }
                            },
                            onRead = { entry ->
                                onOpenStored(entry)
                                val source = sourceOf(entry.sourceId)
                                // A stored chapter whose source is still present
                                // gets its list back too, so it opens with the
                                // same navigation a freshly-fetched one does.
                                val stored = Chapter(entry.chapterId, entry.title, null)
                                if (source != null && entry.mangaId.isNotBlank()) {
                                    resumeScope.launch {
                                        val resolved = ChapterResume.resolve(
                                            stored, fetchChapters(source, entry.mangaId),
                                        )
                                        go(ShuraScreen.Reader(
                                            entry.sourceId, entry.title,
                                            resolved.chapter, resolved.chapters,
                                        ))
                                    }
                                } else {
                                    go(ShuraScreen.Reader(
                                        entry.sourceId, entry.title, stored, emptyList(),
                                    ))
                                }
                            },
                            onDelete = { entry ->
                                app.shura.manga.deleteStoredChapter(entry)
                                libraryState = libraryState.filterNot { it.chapterId == entry.chapterId }
                            },
                        )

                        is ShuraScreen.Settings -> SettingsScreen(
                            language = language,
                            onLanguage = onLanguage,
                            dark = dark,
                            onDark = onDark,
                            repositoryNotice = repositoryNotice,
                            onInstallRepository = onInstallRepository,
                            onOpenStore = { go(ShuraScreen.Extensions) },
                        )

                        is ShuraScreen.Extensions -> {
                            val catalog = extensionCatalog
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { back() }) {
                                        Text(shura("common.back"))
                                    }
                                }
                                if (catalog == null) {
                                    StatePanel(shura("common.error"), "")
                                } else {
                                    ExtensionStoreScreen(
                                        rows = extensions,
                                        catalog = catalog,
                                        onInstall = onInstallExtension,
                                        onRemove = onRemoveExtension,
                                    )
                                }
                            }
                        }

                        is ShuraScreen.Search -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) StatePanel(shura("common.error"), screen.sourceId)
                            else SearchScreen(
                                source = source,
                                onOpenManga = { manga ->
                                    nav.rememberCover(screen.sourceId, manga.title, manga.coverUrl)
                                    go(ShuraScreen.Details(screen.sourceId, manga))
                                },
                                onBack = { back() },
                            )
                        }

                        is ShuraScreen.Details -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) StatePanel(shura("common.error"), screen.sourceId)
                            else {
                                // Chapters the reader has been through, on this
                                // source. Read from history rather than through
                                // progressStore.hasProgress(), which is keyed by
                                // (source, title, chapter): a chapter opened from
                                // Discover is recorded against the source's name,
                                // so the title-keyed lookup misses it and every
                                // chapter would look unread.
                                val readChapterIds = remember(history, screen.sourceId) {
                                    history
                                        .filter { it.sourceId == screen.sourceId && it.chapterId.isNotBlank() }
                                        .mapTo(mutableSetOf()) { it.chapterId }
                                }
                                // Remembered so the page's own remember() keys do
                                // not change identity on every recomposition.
                                val isRead: (String) -> Boolean =
                                    remember(readChapterIds) { { id -> id in readChapterIds } }
                                WorkDetailsScreen(
                                    manga = screen.manga,
                                    source = source,
                                    sourceName = source.name,
                                    // The source's own language, which the details page
                                    // states as a chip. The contract carries no genre or
                                    // author, so nothing else is claimed there.
                                    sourceLanguage = source.language,
                                    // Followed works, not downloaded chapters: a title
                                    // belongs in the library when the reader chooses it,
                                    // which is a different question from what is on disk.
                                    inLibrary = followedWorks.any { it.sourceId == screen.sourceId && it.mangaId == screen.manga.id },
                                    onToggleLibrary = {
                                        val work = LibraryWork(
                                            sourceId = screen.sourceId,
                                            mangaId = screen.manga.id,
                                            title = screen.manga.title,
                                            coverUrl = screen.manga.coverUrl.orEmpty(),
                                        )
                                        resumeScope.launch {
                                            withContext(Dispatchers.IO) {
                                                if (isWorkInLibrary(workDir, work.sourceId, work.mangaId)) {
                                                    removeWork(workDir, work)
                                                } else {
                                                    storeWork(workDir, work)
                                                }
                                            }
                                            workVersion += 1
                                        }
                                    },
                                    // The page decides the chapter: chapter one when
                                    // nothing has been read, the chapter the reader
                                    // stopped in when something has. The list travels
                                    // with it so the reader keeps its arrows - and the
                                    // page they reached is restored by the reader from
                                    // the progress store, not from here.
                                    onReadLatest = { chapter ->
                                        val known = detailsChapters
                                            ?.takeIf { it.first == screen.manga.id }
                                            ?.second
                                            .orEmpty()
                                        openChapter(
                                            screen.sourceId, screen.manga.title, source,
                                            chapter, known, screen.manga.coverUrl.orEmpty(),
                                        )
                                    },
                                    onChaptersLoaded = { detailsChapters = screen.manga.id to it },
                                    isRead = isRead,
                                    history = history,
                                    downloadedChapterIds = remember(libraryVersion) {
                                        nav.downloads.downloadedChapters()
                                    },
                                    queuedChapterIds = downloadJobs.map { it.chapterId }.toSet(),
                                    // The same call the chapter list makes, one row
                                    // per chapter: the queue decides the order and
                                    // refuses a chapter it already has, so a batch
                                    // needs nothing of its own.
                                    onDownloadChapter = { chapter ->
                                        nav.downloads.download(
                                            source = source,
                                            mangaId = screen.manga.id,
                                            mangaTitle = screen.manga.title,
                                            chapter = chapter,
                                            coverUrl = screen.manga.coverUrl.orEmpty(),
                                        )
                                    },
                                    onShare = { shareWork(nav.context, source.name, screen.manga.title) },
                                    onOpenChapters = {
                                        go(ShuraScreen.Chapters(screen.sourceId, screen.manga))
                                    },
                                    onBack = { back() },
                                )
                            }
                        }

                        is ShuraScreen.Chapters -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) StatePanel(shura("common.error"), screen.sourceId)
                            else ChapterListScreen(
                                mangaTitle = screen.manga.title,
                                source = source,
                                mangaId = screen.manga.id,
                                onOpenChapter = { chapter ->
                                    openChapter(screen.sourceId, screen.manga.title, source,
                                        chapter, loadedChapters, screen.manga.coverUrl.orEmpty())
                                },
                                onChaptersLoaded = { loadedChapters = it },
                                // Read state comes from the progress store rather than
                                // a separate flag, so a chapter the reader opened shows
                                // as read without anything having to mark it.
                                readChapterIds = remember(screen.manga.id, loadedChapters, readVersion) {
                                loadedChapters.filter { chapter ->
                                    nav.progressStore.hasProgress(
                                        screen.sourceId, screen.manga.title, chapter.id,
                                    )
                                }.map { it.id }.toSet()
                            },
                                downloadedChapterIds = remember(libraryVersion) {
                                    nav.downloads.downloadedChapters()
                                },
                                queuedChapterIds = downloadJobs.map { it.chapterId }.toSet(),
                                onDownloadChapter = { chapter ->
                                    nav.downloads.download(
                                        source = source,
                                        mangaId = screen.manga.id,
                                        mangaTitle = screen.manga.title,
                                        chapter = chapter,
                                        coverUrl = screen.manga.coverUrl.orEmpty(),
                                    )
                                },
                                onBack = { back() },
                            )
                        }

                        is ShuraScreen.Downloads -> DownloadsScreen(
                            jobs = downloadJobs,
                            onCancel = { nav.downloads.cancel(it) },
                            onRetry = { nav.downloads.retry(it, sourceOf) },
                            onForget = { nav.downloads.forget(it) },
                            onBack = { back() },
                        )

                        is ShuraScreen.Reader -> {
                            val total = totalPages() ?: pages.size
                            val saved = nav.progressStore.pageFor(
                                screen.sourceId, screen.mangaTitle, screen.chapter.id,
                                total.coerceAtLeast(1),
                            )
                            // Null where there is nowhere to go, so the bar hides
                            // the arrow rather than offering a dead step.
                            val previous = ChapterNavigation.previous(screen.chapters, screen.chapter.id)
                            val next = ChapterNavigation.next(screen.chapters, screen.chapter.id)
                            val source = sourceOf(screen.sourceId)
                            val cover = nav.coverFor(screen.sourceId, screen.mangaTitle)
                            // No extra back handler here on purpose: a sheet
                            // is a dialog in its own right and consumes back
                            // itself, closing before anything else can see the
                            // press. Adding a handler on top would fight it,
                            // and the usual result is a sheet that ignores back
                            // or a chapter that exits with the sheet still up.
                            // The screen's stack handler is the only one, and
                            // the reader is never a dead end.
                            ReaderScreen(
                                pages = pages,
                                sourceId = screen.sourceId,
                                chapterId = screen.chapter.id,
                                chapterTitle = screen.mangaTitle + " · " + screen.chapter.name,
                                cover = cover,
                                savedPage = saved,
                                onProgress = onProgressChanged,
                                onPageVisible = onPageVisible,
                                chapters = screen.chapters,
                                onJumpToChapter = { chosen ->
                                    openChapter(
                                        screen.sourceId, screen.mangaTitle, source,
                                        chosen, screen.chapters, cover,
                                    )
                                },
                                onRetryPage = onRetryPage,
                                totalPages = total,
                                mode = readerMode,
                                onModeChange = onReaderModeChange,
                                readerPreferences = readerPreferences,
                                onReaderPreferencesChange = onReaderPreferencesChange,

                                onPrevChapter = previous?.let { c ->
                                    { openChapter(screen.sourceId, screen.mangaTitle, source, c, screen.chapters) }
                                },
                                onNextChapter = next?.let { c ->
                                    { openChapter(screen.sourceId, screen.mangaTitle, source, c, screen.chapters) }
                                },
                                onBack = onExitReader,
                                // A listed chapter can still have no images; the
                                // way out of that is the next one, not a dead end.
                                onOpenNextChapter = next?.let { c ->
                                    { openChapter(screen.sourceId, screen.mangaTitle, source, c, screen.chapters, cover) }
                                },
                                translationSettings = settings,
                                onTranslationSettingsChange = {
                                    nav.settingsStore.save(it)
                                    settings = it
                                },
                                translationCache = nav.translationCache,
                                translationEngineAvailable = nav.translationEngineAvailable,
                                isLoading = isLoading,
                                loadError = loadError,
                                onRetry = onRetry,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hands a title's name and source to whatever the reader uses to send text.
 *
 * Nothing here is state: it builds the intent and starts the chooser, and a
 * device with nothing that can receive it simply does nothing. The reader asked
 * to share a title by tapping share, and an exception escaping into the app for
 * a missing handler would turn that into a crash.
 */
private fun shareWork(context: android.content.Context, sourceName: String, title: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "$title — $sourceName")
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, title),
        )
    }
}

/**
 * The title and the destinations, the way Mihon arranges them.
 *
 * The tab row is above the content rather than below it, so the reader's own bar
 * is the only thing near their thumb while reading, and the screen title is the
 * largest text on the page the way it is in every other screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShuraTabBar(
    current: ShuraScreen,
    title: String,
    onNavigate: (ShuraScreen) -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Column {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = actions,
        )
        TabRow(selectedTabIndex = tabIndexOf(current)) {
            listOf(
                ShuraScreen.Home,
                ShuraScreen.Sources,
                ShuraScreen.Library,
                ShuraScreen.Downloads,
                ShuraScreen.Settings,
            ).forEach { target ->
                Tab(
                    selected = tabIndexOf(current) == tabIndexOf(target),
                    onClick = { onNavigate(target) },
                    text = {
                        Text(
                            tabLabelOf(target),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}

private val TABS = listOf(
    ShuraScreen.Home,
    ShuraScreen.Sources,
    ShuraScreen.Library,
    ShuraScreen.Downloads,
    ShuraScreen.Settings,
)

/**
 * The tab a screen belongs to.
 *
 * Never -1: the crash that came out of this was exactly that. A work's page and
 * its chapter list are not tabs, but they belong to Discover - the tab the reader
 * came from and the one they go back to. Handing TabRow a -1 read its tab list at
 * index -1 and took the process down, which is what "pressing a tab closes the
 * app" was.
 */
private fun rootTabOf(screen: ShuraScreen): ShuraScreen = when (screen) {
    is ShuraScreen.Search, is ShuraScreen.Details, is ShuraScreen.Chapters -> ShuraScreen.Sources
    is ShuraScreen.Extensions -> ShuraScreen.Settings
    is ShuraScreen.Reader -> ShuraScreen.Home
    else -> screen
}

private fun tabIndexOf(screen: ShuraScreen): Int = tabIndexFor(screen)

/**
 * The index into [TABS], always valid.
 *
 * Public so a test can pin every screen the app can show: the crash this replaces
 * was an index of -1 reaching Material3's TabRow.
 */
fun tabIndexFor(screen: ShuraScreen): Int {
    val index = TABS.indexOfFirst { it::class == rootTabOf(screen)::class }
    return if (index >= 0) index else 0
}

@Composable
private fun tabLabelOf(tab: ShuraScreen): String = when (tab) {
    ShuraScreen.Home -> shura("nav.home")
    ShuraScreen.Sources -> shura("nav.discover")
    ShuraScreen.Library -> shura("nav.library")
    ShuraScreen.Downloads -> shura("downloads.title")
    else -> shura("nav.settings")
}

/** What the top bar says. A chapter or a work names itself; a tab names the tab. */
/**
 * The icons on the right of the title.
 *
 * They exist for the one job icons are good at: an action the reader wants without
 * reading a label. Everything else stays a labelled control, because an icon that
 * could be two things is worse than a word that is one.
 */
@Composable
private fun TopBarActions(screen: ShuraScreen) {
    when (screen) {
        is ShuraScreen.Library -> {
            IconButton(onClick = {}) { Icon(Icons.Default.Search, contentDescription = shura("library.search")) }
            IconButton(onClick = {}) { Icon(Icons.Default.List, contentDescription = shura("library.sort.title")) }
        }
        is ShuraScreen.Extensions -> {
            IconButton(onClick = {}) { Icon(Icons.Default.Refresh, contentDescription = shura("store.updateAll")) }
        }
        is ShuraScreen.Downloads -> {
            IconButton(onClick = {}) { Icon(Icons.Default.Refresh, contentDescription = shura("common.retry")) }
        }
        else -> Unit
    }
}

@Composable
private fun titleOf(screen: ShuraScreen): String = when (screen) {
    is ShuraScreen.Search -> shura("nav.discover")
    is ShuraScreen.Details -> screen.manga.title
    is ShuraScreen.Chapters -> screen.manga.title
    is ShuraScreen.Extensions -> shura("store.title")
    is ShuraScreen.Downloads -> shura("downloads.title")
    is ShuraScreen.Home -> shura("nav.home")
    is ShuraScreen.Sources -> shura("nav.discover")
    is ShuraScreen.Library -> shura("nav.library")
    is ShuraScreen.Settings -> shura("nav.settings")
    is ShuraScreen.Reader -> ""
}
