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
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onExitReader: () -> Unit,
    history: List<ReadingProgress>,
    onProgressChanged: (Int) -> Unit,
    library: List<StoredChapter>,
    repositoryNotice: String?,
    onImport: () -> Unit,
    onInstallRepository: () -> Unit,
    extensions: List<app.shura.manga.ExtensionEntry> = emptyList(),
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
            var route by remember { mutableStateOf<ShuraScreen>(ShuraScreen.Home) }
            var settings by remember { mutableStateOf(nav.settingsStore.load()) }
            var libraryState by remember { mutableStateOf(library) }
            // The chapters the list screen fetched, carried into the reader so
            // its previous/next have something real to step through.
            var loadedChapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
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
                route = ShuraScreen.Reader(sourceId, mangaTitle, chapter, chapters)
                onFetchChapter(source, chapter, cover)
            }

            androidx.compose.material3.Scaffold(
                // Reading is not a tab. Leaving the app's navigation on screen
                // under the pages meant the reader could never use the full
                // height, and tapping a tab mid-chapter threw away where the
                // reader was.
                bottomBar = {
                    if (route is ShuraScreen.Reader) return@Scaffold
                    ShuraBottomBar(
                        current = route,
                        onNavigate = { target ->
                            route = when (target) {
                                ShuraScreen.Home -> ShuraScreen.Home
                                ShuraScreen.Sources -> ShuraScreen.Sources
                                ShuraScreen.Library -> ShuraScreen.Library
                                else -> ShuraScreen.Settings
                            }
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
                            onBrowse = { route = ShuraScreen.Sources },
                            onOpenLibrary = { route = ShuraScreen.Library },
                            onImport = onImport,
                        )

                        is ShuraScreen.Sources -> SourcePickerScreen(
                            sources = remember(sourcesVersion) { nav.sourceHost.all() },
                            onPick = { route = ShuraScreen.Search(it.id) },
                            onOpenSettings = { route = ShuraScreen.Settings },
                        )

                        is ShuraScreen.Library -> LibraryScreen(
                            stored = libraryState,
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
                                        route = ShuraScreen.Reader(
                                            entry.sourceId, entry.title,
                                            resolved.chapter, resolved.chapters,
                                        )
                                    }
                                } else {
                                    route = ShuraScreen.Reader(
                                        entry.sourceId, entry.title, stored, emptyList(),
                                    )
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
                            onOpenStore = { route = ShuraScreen.Extensions },
                        )

                        is ShuraScreen.Extensions -> {
                            val catalog = extensionCatalog
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { route = ShuraScreen.Settings }) {
                                        Text(shura("common.back"))
                                    }
                                }
                                if (catalog == null) {
                                    StatePanel(shura("common.error"), "")
                                } else {
                                    ExtensionStoreScreen(
                                        entries = extensions,
                                        catalog = catalog,
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
                                    route = ShuraScreen.Details(screen.sourceId, manga)
                                },
                                onBack = { route = ShuraScreen.Sources },
                            )
                        }

                        is ShuraScreen.Details -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) StatePanel(shura("common.error"), screen.sourceId)
                            else WorkDetailsScreen(
                                manga = screen.manga,
                                sourceName = source.name,
                                inLibrary = libraryState.any { it.sourceId == screen.sourceId },
                                onToggleLibrary = { /* library holds chapters, not works */ },
                                onReadLatest = { },
                                onOpenChapters = {
                                    route = ShuraScreen.Chapters(screen.sourceId, screen.manga)
                                },
                                onBack = { route = ShuraScreen.Search(screen.sourceId) },
                            )
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
                                onBack = { route = ShuraScreen.Search(screen.sourceId) },
                            )
                        }

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
                            ReaderScreen(
                                pages = pages,
                                sourceId = screen.sourceId,
                                chapterId = screen.chapter.id,
                                chapterTitle = screen.mangaTitle + " · " + screen.chapter.name,
                                cover = cover,
                                savedPage = saved,
                                onProgress = onProgressChanged,
                                onPageVisible = onPageVisible,
                                onRetryPage = onRetryPage,
                                totalPages = total,

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShuraBottomBar(current: ShuraScreen, onNavigate: (ShuraScreen) -> Unit) {
    androidx.compose.material3.NavigationBar {
        listOf(
            ShuraScreen.Home to shura("nav.home"),
            ShuraScreen.Sources to shura("nav.discover"),
            ShuraScreen.Library to shura("nav.library"),
            ShuraScreen.Settings to shura("nav.settings"),
        ).forEach { (target, label) ->
            NavigationBarItem(
                selected = current::class == target::class,
                onClick = { onNavigate(target) },
                icon = {},
                label = { Text(label) },
            )
        }
    }
}
