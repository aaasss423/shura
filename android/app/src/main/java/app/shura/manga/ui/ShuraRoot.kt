package app.shura.manga.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
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
import app.shura.manga.chapterStore
import app.shura.manga.ChapterFeed
import app.shura.manga.DownloadedWork
import app.shura.manga.LocalChapterRead
import app.shura.manga.StoredChapterRecord
import app.shura.manga.WorkCleanupAction
import app.shura.manga.applyWorkCleanup
import app.shura.manga.formatBytes
import app.shura.manga.groupDownloads
import app.shura.manga.planWorkCleanup
import app.shura.manga.storedChaptersOf
import app.shura.manga.isWorkInLibrary
import app.shura.manga.listLibraryWorks
import app.shura.manga.removeWork
import app.shura.manga.storeWork
import app.shura.manga.workLibrary
import app.shura.manga.ui.screens.*
import app.shura.manga.ui.Strings
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

    /**
     * The repositories extensions are fetched from.
     *
     * Its own screen rather than another panel inside Settings, because it is a
     * destination with something to do in it: an address is typed, fetched and
     * verified, and the packages a repository offers are listed from it.
     */
    data object Repositories : ShuraScreen
    data class Search(val sourceId: String) : ShuraScreen
    data class Details(val sourceId: String, val manga: MangaSummary) : ShuraScreen
    data class Chapters(val sourceId: String, val manga: MangaSummary) : ShuraScreen

    /**
     * One work's chapters that are on this device.
     *
     * A screen of its own because the two places that list saved chapters are
     * both lists of *works*: the library grid and the downloads list. Tapping a
     * work in either of them has to land somewhere, and what it lands on is the
     * work's chapters - not the first saved one, which is where a tap used to
     * end up, and not the source's own chapter list, which is about chapters
     * this device does not have.
     *
     * Keyed by (source, manga) rather than by a title, because two sources can
     * carry works with the same name and this screen must not merge them.
     */
    data class SavedChapters(
        val sourceId: String,
        val mangaId: String,
        val title: String,
        val coverUrl: String = "",
    ) : ShuraScreen

    data class Reader(
        val sourceId: String,
        val mangaTitle: String,
        val chapter: Chapter,
        val chapters: List<Chapter>,
    ) : ShuraScreen
}

/**
 * Where tapping a work in the library or the downloads screen goes.
 *
 * One rule, in one place, because the two screens were doing it differently and
 * one of them was doing it wrong: a work with chapters saved opened the first
 * one of them - straight into a reader, with no list of the rest and no way to
 * choose. A work is opened by its chapters; a *chapter* is opened by tapping the
 * chapter.
 *
 * A work with nothing saved has no local list to open, so it opens the source's
 * chapter list instead - the one place its chapters can still come from.
 *
 * Public so a test can pin it: this is the user's "tapping a work jumps into a
 * chapter", and it cannot be checked by reading a layout.
 */
fun openTargetFor(work: DownloadedWork): ShuraScreen =
    if (work.localCount > 0) {
        ShuraScreen.SavedChapters(work.sourceId, work.mangaId, work.title, work.coverUrl)
    } else {
        ShuraScreen.Chapters(
            work.sourceId,
            MangaSummary(work.mangaId, work.title, work.coverUrl.takeIf { it.isNotBlank() }),
        )
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
    /**
     * The same chapters as [library], in the form the offline decision is made in.
     *
     * [library] hands the reader `android.net.Uri`, which a JVM test cannot even
     * construct - so the "is this chapter on disk?" question, answered over
     * [StoredChapterRecord] instead, is the first part of this that can be pinned
     * without a device. It is the same single directory read, converted once.
     */
    storedRecords: List<StoredChapterRecord> = emptyList(),
    repositoryNotice: String?,
    onInstallRepository: () -> Unit,
    extensions: List<app.shura.manga.ui.screens.ExtensionRowState> = emptyList(),
    /** Downloads and verifies one extension, then makes it available. */
    onInstallExtension: (app.shura.manga.ExtensionEntry) -> Unit = {},
    /**
     * Bumped after a repository is added, refreshed or removed.
     *
     * Adding one changes two things this screen does not own: what the store
     * offers, and which sources the app can reach. Both are read from disk by
     * the activity, so it is told rather than reached into.
     */
    onRepositoriesChanged: () -> Unit = {},
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
     *
     * A [StoredChapterRecord] rather than a [StoredChapter], so the callback is
     * handed files: the reader is given `file://` URIs it can decode and nothing
     * that has to be resolved here, and the JVM can drive this whole path -
     * which is the point, because it is the path that has to work with no
     * network.
     */
    onOpenStored: (StoredChapterRecord) -> Unit = {},
    /**
     * Refetches a title's chapter list so a resumed chapter knows its
     * neighbours. Null means the source could not be reached.
     */
    fetchChapters: suspend (ShuraSource, String) -> List<Chapter>? = { _, _ -> null },
    /**
     * The reader reached the end of a chapter and says what it saw.
     *
     * The reader cannot know whether that chapter is in the library or whether
     * deleting it is wanted, so it reports and the activity that owns the
     * library decides - the same shape as the download callbacks above: the
     * screen asks, the owner answers, and neither reads the other's state.
     */
    onChapterFinished: (app.shura.manga.reader.ChapterFinished) -> Unit = {},
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
            // Keyed by the library itself, so a chapter saved or deleted anywhere - by the
            // download queue, or by the reader finishing one - shows up here
            // without waiting for the reader to leave the screen and come back.
            var libraryState by remember(library) { mutableStateOf(library) }

            /**
             * Chapter ids already on the device.
             *
             * Read off the index the activity already keeps rather than off the
             * store again: three screens were asking the same question, and each
             * ask was another directory listing with a manifest read per chapter
             * on the thread that was drawing them.
             */
            val downloadedIds = remember(libraryState) { libraryState.map { it.chapterId }.toSet() }

            /** Chapters with a download row, whatever state that row is in. */
            val queuedIds = remember(downloadJobs) { downloadJobs.map { it.chapterId }.toSet() }
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
            // Captured once here: a local function called from a callback cannot read
            // a CompositionLocal itself, and the cleanup notice needs the language.
            val uiLanguage = LocalUiLanguage.current
            /** What the last bulk cleanup did, said above the library rather than nowhere. */
            var cleanupNotice by remember { mutableStateOf<String?>(null) }

            // -- repositories added by URL ------------------------------------
            //
            // Three pieces of state and one function, all here because the screen
            // itself is a view: the list is a file on disk, so it is re-read after
            // every action rather than remembered across one, and every action is
            // a network fetch - so it runs off the main thread and the screen is
            // told it is working rather than being left to answer a second press
            // while the first is still in flight.
            var repositoryVersion by remember { mutableIntStateOf(0) }
            var repositoryWorking by remember { mutableStateOf(false) }
            var repositoryNotice by remember { mutableStateOf<String?>(null) }

            fun repositoryRecords(): List<app.shura.manga.RepositoryRecord> =
                runCatching {
                    app.shura.manga.ShuraRepository.registry(nav.context).records()
                }.getOrDefault(emptyList())

            /**
             * One add or refresh, told plainly what happened.
             *
             * A refusal is answered with the reason the runtime gave, in the
             * reader's language - and nothing is registered on that path, which
             * is [app.shura.manga.RepositoryManager]'s promise rather than
             * something this function has to remember to keep.
             */
            fun repositoryAction(action: (app.shura.manga.RepositoryManager) -> app.shura.manga.RepositoryOutcome) {
                if (repositoryWorking) return
                repositoryWorking = true
                repositoryNotice = null
                resumeScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        val manager = app.shura.manga.ShuraRepository.manager(nav.context)
                        action(manager)
                    }
                    repositoryNotice = app.shura.manga.RepositoryStatusText.notice(outcome, uiLanguage)
                    repositoryWorking = false
                    repositoryVersion += 1
                    if (outcome is app.shura.manga.RepositoryOutcome.Registered) {
                        // A verified repository changes what the store offers and
                        // which sources the app can reach; both are read from disk
                        // by the activity, so it is told rather than reached into.
                        onRepositoriesChanged()
                    }
                }
            }

            /**
             * Removes downloads, a library entry, or both - independently.
             *
             * Three things have to be true at once and none of them is the delete
             * itself: the plan is built from the work's *own* chapters, so another
             * work's files are not in it; anything still downloading for this work
             * is really cancelled, or it would land its chapter straight back into a
             * directory that was just emptied; and every queue row for the work is
             * dropped, or the downloads screen keeps offering a download of files
             * that are not there.
             *
             * The whole thing is off the main thread - a chapter is hundreds of
             * page images, and walking them on the UI thread is the ANR this app
             * already had once.
             */

/**
     * Opens a chapter that is already on this device.
     *
     * The reader is pointed at the local files and nothing else is asked for. Its
     * previous/next come out of the store too, because a neighbour list that can
     * only be produced by the source is a neighbour list that costs a request -
     * and "continue reading a downloaded chapter" is exactly the thing that has to
     * work with the radio off.
     *
     * Shared by the library, by a work's list of saved chapters, and by *every*
     * other route into the reader, because they were all reaching for it through
     * the same question: "read this chapter of this work".
     */
    fun openStoredChapter(entry: StoredChapterRecord) {
        // The work's cover, recorded so the reader's own header shows it rather
        // than only the chapter's name.
        nav.rememberCover(entry.sourceId, entry.mangaTitle.ifBlank { entry.title }, entry.coverUrl)
        onOpenStored(entry)
        val stored = Chapter(entry.chapterId, entry.title, null)
        val neighbours = LocalChapterRead.readerChapters(
            storedRecords, entry.sourceId, entry.mangaId,
        )
        go(ShuraScreen.Reader(
            entry.sourceId,
            entry.mangaTitle.ifBlank { entry.title },
            stored,
            neighbours,
        ))
    }

            fun cleanupWorks(works: List<LibraryWork>, action: WorkCleanupAction) {
                if (works.isEmpty()) return
                val group = groupDownloads(libraryState, downloadJobs)
                resumeScope.launch {
                    val results = withContext(Dispatchers.IO) {
                        works.map { work ->
                            val downloaded = group.firstOrNull {
                                it.sourceId == work.sourceId && it.mangaId == work.mangaId
                            } ?: DownloadedWork(
                                sourceId = work.sourceId,
                                mangaId = work.mangaId,
                                title = work.title,
                                coverUrl = work.coverUrl,
                                stored = emptyList(),
                                jobs = emptyList(),
                            )
                            val plan = planWorkCleanup(downloaded, action, work)
                            // Really stopped, not hidden: a download still running
                            // would otherwise write the chapter back.
                            plan.cancelJobs.forEach { nav.downloads.cancel(it) }
                            plan.dropJobs.forEach { nav.downloads.forget(it) }
                            applyWorkCleanup(plan, workDir, chapterStore(nav.context))
                        }
                    }
                    libraryVersion += 1
                    workVersion += 1
                    val deleted = results.sumOf { it.deletedChapters }
                    val bytes = results.sumOf { it.deletedBytes }
                    val kept = results.sumOf { it.failedChapters.size }
                    val removed = results.count { it.removedFromLibrary }
                    // shura() is a composable call and this runs inside a coroutine,
                    // so the language is captured above and the plain lookup is used
                    // here. Same strings, no composable call off the composition.
                    cleanupNotice = listOfNotNull(
                        Strings.t("library.cleanupDone", uiLanguage, deleted, formatBytes(bytes)),
                        Strings.t("library.cleanupLibrary", uiLanguage, removed).takeIf { removed > 0 },
                        Strings.t("library.cleanupKept", uiLanguage, kept).takeIf { kept > 0 },
                    ).joinToString(" · ")
                }
            }

            // Move the reader onto a chapter and start loading it, as one step.
            // Fetching and navigating separately let the route keep naming the
            // old chapter while the new one's pages were already on screen.
            //
            // THE STORE IS ASKED FIRST, ON EVERY ROUTE IN
            //
            // This is the bug: opening a downloaded chapter went through
            // `source.pages()` like every other chapter, so a chapter whose files
            // were sitting on the device was re-fetched over a network the reader
            // may not have. One question - [LocalChapterRead.feedFor], answered
            // from the store index the library was just read from - decides it,
            // and every way into the reader now goes through here.
            fun openChapter(
                sourceId: String,
                mangaTitle: String,
                source: ShuraSource?,
                chapter: Chapter,
                chapters: List<Chapter>,
                cover: String = "",
            ) {
                when (val feed = LocalChapterRead.feedFor(storedRecords, sourceId, chapter.id)) {
                    // A local function has to be declared before the line that calls
                    // it; this one sits below the composition it serves.
                    is ChapterFeed.OnDevice -> openStoredChapter(feed.record)
                    ChapterFeed.Network -> {
                        if (source == null) return
                        go(ShuraScreen.Reader(sourceId, mangaTitle, chapter, chapters))
                        onFetchChapter(source, chapter, cover)
                    }
                }
            }


            androidx.compose.material3.Scaffold(
                // The title on top and the destinations under the content, where a
                // thumb rests. Both bars are the reader's chrome and both come off
                // for the reader itself: a chapter owns the whole screen, and a bar
                // over a page is a band the last third of it has to be read around.
                topBar = {
                    if (route is ShuraScreen.Reader) return@Scaffold
                    ShuraTopBar(
                        title = titleOf(nav, route),
                        // Only where there is something behind this screen to go
                        // back to. Arriving at a tab by pressing it leaves nothing,
                        // and an arrow that cannot act is a dead control.
                        onBack = if (backStack.size > 1) ({ back() }) else null,
                        actions = { TopBarActions(route) },
                    )
                },
                bottomBar = {
                    if (route is ShuraScreen.Reader) return@Scaffold
                    ShuraBottomBar(
                        current = route,
                        // A destination is a root, so it replaces the stack rather
                        // than growing it; otherwise back would walk out of the app
                        // through every destination ever visited.
                        onNavigate = { target ->
                            backStack.switchTab(target)
                            readVersion += 1
                            libraryVersion += 1
                        },
                        activeDownloads = downloadJobs.count {
                            it.state == app.shura.manga.DownloadState.RUNNING ||
                                it.state == app.shura.manga.DownloadState.QUEUED
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
                                //
                                // A chapter whose pages are on the device is not
                                // re-fetched at all, and neither is its chapter
                                // list: asking the source for the neighbours first
                                // is the request that cannot be made with no
                                // network, so it was the reason "continue reading"
                                // failed on a downloaded chapter.
                                when (val feed = LocalChapterRead.feedFor(
                                    storedRecords, entry.sourceId, entry.chapterId,
                                )) {
                                    is ChapterFeed.OnDevice -> openStoredChapter(feed.record)
                                    ChapterFeed.Network -> {
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
                            // The empty library says where to fill it, so this is a
                            // destination rather than a sentence.
                            onBrowse = { go(ShuraScreen.Sources) },
                            // A tap opens the work's chapters - the ones on this
                            // device when there are any, and the source's own list
                            // when there are none. It never opens a reader: the
                            // reader is reached by choosing a chapter, and there
                            // was no way to choose one from here before.
                            onOpenWork = { work ->
                                go(
                                    openTargetFor(
                                        DownloadedWork(
                                            sourceId = work.sourceId,
                                            mangaId = work.mangaId,
                                            title = work.title,
                                            coverUrl = work.coverUrl,
                                            stored = storedChaptersOf(
                                                libraryState, work.sourceId, work.mangaId,
                                            ),
                                            jobs = emptyList(),
                                        ),
                                    ),
                                )
                            },
                            onManageWork = { works, action ->
                                cleanupWorks(
                                    works.map {
                                        app.shura.manga.LibraryWork(it.sourceId, it.mangaId, it.title, it.coverUrl)
                                    },
                                    action,
                                )
                            },
                            cleanupNotice = cleanupNotice,
                            onDelete = { entry ->
                                // Recursive delete on the main thread is the same ANR
                                // that froze the library before: a chapter is hundreds of
                                // page images, and walking them blocks the UI for as long
                                // as the disk takes. The row disappears at once; the bytes
                                // go in the background.
                                resumeScope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.shura.manga.deleteStoredChapter(entry)
                                    }
                                }
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
                            // Where repositories added by URL are added. Settings
                            // could only install one from a folder, which is a
                            // different act: a folder is already on the device, an
                            // address has to be fetched and verified before it is
                            // anything at all.
                            onOpenRepositories = { go(ShuraScreen.Repositories) },
                        )

                        is ShuraScreen.Repositories -> {
                            RepositoriesScreen(
                                // Re-read from disk whenever an action lands, and
                                // not remembered across one: the list is a file, and
                                // a file is what survives the app being closed.
                                records = remember(repositoryVersion) { repositoryRecords() },
                                working = repositoryWorking,
                                notice = repositoryNotice,
                                onAdd = { url -> repositoryAction { it.add(url) } },
                                onRefresh = { record ->
                                    repositoryAction { it.update(record.id) }
                                },
                                onRemove = { record ->
                                    repositoryWorking = true
                                    resumeScope.launch {
                                        val manager = withContext(Dispatchers.IO) {
                                            app.shura.manga.ShuraRepository.manager(nav.context)
                                        }
                                        // A repository whose files could not be
                                        // deleted keeps its row, because a row that
                                        // disappeared while its extensions were
                                        // still on disk is the one thing this screen
                                        // must never do.
                                        val gone = withContext(Dispatchers.IO) { manager.remove(record.id) }
                                        repositoryNotice = app.shura.manga.RepositoryStatusText.removed(
                                            record, gone, uiLanguage,
                                        )
                                        repositoryWorking = false
                                        repositoryVersion += 1
                                        onRepositoriesChanged()
                                    }
                                },
                                // The store, which is where packages from these
                                // repositories are installed - through the path that
                                // already existed.
                                onOpenStore = { go(ShuraScreen.Extensions) },
                            )
                        }

                        is ShuraScreen.Extensions -> {
                            val catalog = extensionCatalog
                            // The way out of this screen is the app's top bar now, so
                            // the body is either the store or the reason there is none.
                            if (catalog == null) {
                                EmptyState(
                                    title = shura("common.error"),
                                    body = shura("settings.repositoryHelp"),
                                    icon = ShuraIcons.Target,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ExtensionStoreScreen(
                                    rows = extensions,
                                    catalog = catalog,
                                    onInstall = onInstallExtension,
                                    onRemove = onRemoveExtension,
                                )
                            }
                        }

                        is ShuraScreen.Search -> {
                            val source = sourceOf(screen.sourceId)
                            // Named, not a bare error: "the source is not installed"
                            // and "the source failed" are different problems, and a
                            // panel that says "error" with no way out reads as a dead app.
                            if (source == null) {
                                // Named, not a bare error: "the source is not installed"
                                // and "the source failed" are different problems, and a
                                // panel that says "error" with no way out reads as a dead
                                // app. The way out is the arrow in the bar above.
                                EmptyState(
                                    title = shura("source.missing"),
                                    body = screen.sourceId,
                                    icon = ShuraIcons.Target,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            else SearchScreen(
                                source = source,
                                onOpenManga = { manga ->
                                    nav.rememberCover(screen.sourceId, manga.title, manga.coverUrl)
                                    go(ShuraScreen.Details(screen.sourceId, manga))
                                },
                            )
                        }

                        is ShuraScreen.Details -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) EmptyState(
                                title = shura("common.error"),
                                body = screen.sourceId,
                                icon = ShuraIcons.Target,
                                modifier = Modifier.fillMaxSize(),
                            )
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
                                    downloadedChapterIds = downloadedIds,
                                    queuedChapterIds = queuedIds,
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
                                    // The whole batch in one call: "download the rest"
                                    // on a long series is hundreds of chapters, and the
                                    // queue serialises them anyway. Same chapters, same
                                    // rule for what is left - only the way in changed.
                                    onDownloadBatch = { batch ->
                                        nav.downloads.downloadAll(
                                            source = source,
                                            mangaId = screen.manga.id,
                                            mangaTitle = screen.manga.title,
                                            chapters = batch,
                                            coverUrl = screen.manga.coverUrl.orEmpty(),
                                            onDisk = downloadedIds,
                                        )
                                    },
                                    onShare = { shareWork(nav.context, source.name, screen.manga.title) },
                                    onOpenChapters = {
                                        go(ShuraScreen.Chapters(screen.sourceId, screen.manga))
                                    },
                                )
                            }
                        }

                        is ShuraScreen.Chapters -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) EmptyState(
                                title = shura("common.error"),
                                body = screen.sourceId,
                                icon = ShuraIcons.Target,
                                modifier = Modifier.fillMaxSize(),
                            )
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
                                downloadedChapterIds = downloadedIds,
                                queuedChapterIds = queuedIds,
                                onDownloadChapter = { chapter ->
                                    nav.downloads.download(
                                        source = source,
                                        mangaId = screen.manga.id,
                                        mangaTitle = screen.manga.title,
                                        chapter = chapter,
                                        coverUrl = screen.manga.coverUrl.orEmpty(),
                                    )
                                },
                                // "Download all chapters", on the chapter list
                                // itself. Every chapter that is left, in one call
                                // to the queue, which serialises them.
                                onDownloadAll = { batch ->
                                    nav.downloads.downloadAll(
                                        source = source,
                                        mangaId = screen.manga.id,
                                        mangaTitle = screen.manga.title,
                                        chapters = batch,
                                        coverUrl = screen.manga.coverUrl.orEmpty(),
                                        onDisk = downloadedIds,
                                    )
                                },
                            )
                        }

                        is ShuraScreen.SavedChapters -> {
                            // Built from the store, not remembered from where the
                            // tap came from: a download that finished while this
                            // screen was open has added a chapter to it, and the
                            // library it was opened from may not have been re-read.
                            val work = remember(libraryState, downloadJobs, screen.sourceId, screen.mangaId) {
                                groupDownloads(libraryState, downloadJobs)
                                    .firstOrNull { it.sourceId == screen.sourceId && it.mangaId == screen.mangaId }
                                    ?: DownloadedWork(
                                        sourceId = screen.sourceId,
                                        mangaId = screen.mangaId,
                                        title = screen.title,
                                        coverUrl = screen.coverUrl,
                                        stored = emptyList(),
                                        jobs = emptyList(),
                                    )
                            }
                            StoredChaptersScreen(
                                work = work,
                                // Straight from the store's own record, so the
                                // reader is handed the page files that are really
                                // there rather than a list of URIs to try.
                                onRead = { entry ->
                                    LocalChapterRead.feedFor(
                                        storedRecords, screen.sourceId, entry.chapterId,
                                    ).let { feed ->
                                        when (feed) {
                                            is ChapterFeed.OnDevice -> openStoredChapter(feed.record)
                                            // The row is on screen but its pages are
                                            // not: say so rather than opening a reader
                                            // over nothing.
                                            ChapterFeed.Network -> cleanupNotice =
                                                Strings.t("library.chapterGone", uiLanguage, entry.title)
                                        }
                                    }
                                },
                                onCancel = { nav.downloads.cancel(it) },
                                onRetry = { nav.downloads.retry(it, sourceOf) },
                                onForget = { nav.downloads.forget(it) },
                            )
                        }

                        is ShuraScreen.Downloads -> DownloadsScreen(
                            jobs = downloadJobs,
                            stored = libraryState,
                            onCancel = { nav.downloads.cancel(it) },
                            // One action for everything still moving. Per-row
                            // cancel is right for one chapter and useless for the
                            // two hundred a long series queues, which is where
                            // "stop" has to mean stop.
                            onCancelAll = { nav.downloads.cancelAll() },
                            onRetry = { nav.downloads.retry(it, sourceOf) },
                            onForget = { nav.downloads.forget(it) },
                            // A work, not a chapter: tapping a row here opens the
                            // chapters of that work, and tapping one of those
                            // chapters is what opens the reader.
                            onOpenWork = { go(openTargetFor(it)) },
                            onBrowse = { go(ShuraScreen.Sources) },
                        )

                        is ShuraScreen.Reader -> {
                            // The chapter's own page count when the activity
                            // knows it, and how many pages have actually landed
                            // when it does not. One rule, in [ReaderCounter], and
                            // the reader is handed the *unresolved* total so the
                            // same rule is applied where the counter is drawn:
                            // collapsing it to `pages.size` here is what made a
                            // forty page chapter read "1 / 1" until its last
                            // page arrived.
                            val total = app.shura.manga.reader.ReaderCounter.total(
                                totalPages(), pages.size,
                            )
                            val saved = nav.progressStore.pageFor(
                                screen.sourceId, screen.mangaTitle, screen.chapter.id,
                                total,
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
                                // The work and its chapter, joined with a pinned
                                // separator. Both sides are usually Arabic and the
                                // separator is a neutral middot, which is exactly
                                // the case where an unpinned one puts the two
                                // halves of the title on screen in the opposite
                                // order to the one they are written in.
                                chapterTitle = detailJoin(
                                    screen.mangaTitle,
                                    screen.chapter.name,
                                ),
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
                                totalPages = totalPages(),
                                mode = readerMode,
                                onModeChange = onReaderModeChange,
                                readerPreferences = readerPreferences,
                                onReaderPreferencesChange = onReaderPreferencesChange,
                                onChapterFinished = onChapterFinished,

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
 * The screen's own bar: the way out, and the name of what is on screen.
 *
 * The arrow lives here rather than in each pushed screen's body, which is where
 * it used to be drawn four separate times - four times taking a whole row above
 * the content, and four times being slightly out of line with the title beside
 * it. It is offered only where there is somewhere to go back to: arriving at a
 * tab by pressing it leaves nothing behind the screen, and an arrow that cannot
 * act is the dead button this app keeps trying not to ship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShuraTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = { onBack?.let { ScreenBackButton(it) } },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = actions,
    )
}

/**
 * The destinations, at the bottom, where a thumb already is.
 *
 * Each one is an icon with its name under it, the way every phone app a reader
 * already has in their hand does it. They used to be five words in a tab row
 * across the top, directly above the screen's title - which gave the top of the
 * screen two headings competing for it, and in Arabic, where four of those words
 * are long, it did not fit a phone at all. The top of the screen belongs to the
 * screen.
 *
 * The selected destination gets a filled pill behind its icon, so the state is a
 * shape as well as a colour, and the downloads destination carries a count while
 * anything is actually in flight.
 *
 * Only the chrome moved. Which screen a destination opens, what back does, and
 * which tab a pushed screen belongs to are untouched - that is [ScreenStack]'s
 * job and this function never sees it.
 */
@Composable
private fun ShuraBottomBar(
    current: ShuraScreen,
    onNavigate: (ShuraScreen) -> Unit,
    /** Downloads in flight, badged on the downloads destination. */
    activeDownloads: Int,
) {
    NavigationBar {
        listOf(
            ShuraScreen.Home,
            ShuraScreen.Sources,
            ShuraScreen.Library,
            ShuraScreen.Downloads,
            ShuraScreen.Settings,
        ).forEach { target ->
            val selected = tabIndexOf(current) == tabIndexOf(target)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(target) },
                icon = {
                    Box(contentAlignment = Alignment.Center) {
                        // The pill sits behind the icon rather than around the label,
                        // so all five destinations keep one size whether or not one
                        // of them is selected.
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                tabIconOf(target),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (target is ShuraScreen.Downloads && activeDownloads > 0) {
                            CountBadge(
                                activeDownloads,
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 4.dp),
                            )
                        }
                    }
                },
                label = {
                    Text(
                        tabLabelOf(target),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

/** The glyph for a destination. Drawn once per tab, beside its own label. */
@Composable
private fun tabIconOf(tab: ShuraScreen) = when (tab) {
    ShuraScreen.Home -> Icons.Default.Home
    ShuraScreen.Sources -> ShuraIcons.Target
    ShuraScreen.Library -> ShuraIcons.Books
    ShuraScreen.Downloads -> ShuraIcons.Download
    else -> Icons.Default.Settings
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
    is ShuraScreen.Extensions, is ShuraScreen.Repositories -> ShuraScreen.Settings
    is ShuraScreen.Reader -> ShuraScreen.Home
    // A work's saved chapters are the library's, opened one level down: the tab
    // that owns them is the one that should stay lit behind them.
    is ShuraScreen.SavedChapters -> ShuraScreen.Library
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

/**
 * The icons on the trailing edge of the title.
 *
 * Nothing is drawn here.
 *
 * There used to be a search, a sort and a refresh icon on three of these
 * screens, each with an empty click handler: three dead controls in the place a
 * reader looks first. The library's real search and sort are on the library
 * screen itself, and the extension store's and downloads' real actions are on
 * their own rows. A screen with no icon in its top bar is honest; a screen with
 * three that do nothing is worse than either.
 *
 * It stays as a function so that putting a *live* action here is one edit rather
 * than a redesign, and it keeps [ShuraTopBar]'s signature unchanged. The search
 * and the sort stay on the library screen itself, where the results they change
 * are, rather than moving up into a bar that is the same on five screens.
 */
@Composable
private fun TopBarActions(screen: ShuraScreen) {
    // Intentionally empty: see above.
}

/**
 * What the bar says.
 *
 * A tab names itself. A pushed screen names the thing it was pushed onto: the
 * work's own title, or - for the catalogue - the source being searched, which is
 * the fact a reader needs when results come back from somewhere unexpected.
 */
@Composable
private fun titleOf(nav: ShuraNav, screen: ShuraScreen): String = when (screen) {
    is ShuraScreen.Search -> nav.sourceHost.find(screen.sourceId)?.name
        ?.takeIf { it.isNotBlank() } ?: shura("nav.discover")
    is ShuraScreen.Details -> screen.manga.title
    is ShuraScreen.Chapters -> screen.manga.title
    is ShuraScreen.SavedChapters -> screen.title
    is ShuraScreen.Extensions -> shura("store.title")
    is ShuraScreen.Repositories -> shura("repos.title")
    is ShuraScreen.Downloads -> shura("downloads.title")
    is ShuraScreen.Home -> shura("nav.home")
    is ShuraScreen.Sources -> shura("nav.discover")
    is ShuraScreen.Library -> shura("nav.library")
    is ShuraScreen.Settings -> shura("nav.settings")
    is ShuraScreen.Reader -> ""
}
