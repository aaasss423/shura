package app.shura.manga.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.shura.host.SourceHost
import app.shura.manga.StoredChapter
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
import app.shura.source.ShuraSource

/**
 * The screens, and the path between them.
 *
 * One linear flow - source, title, chapters, reader - with the reader able to
 * step to the next chapter without going back through the list. Each screen is
 * handed the state it needs and returns a decision, so navigation state lives
 * here rather than being smeared across the composables.
 */
/** Fetches a chapter's pages as local files the reader can decode. */
fun interface ChapterDownloader {
    suspend fun pages(source: ShuraSource, chapterId: String): List<Uri>
}

class ShuraNav(
    val sourceHost: SourceHost,
    val downloader: ChapterDownloader,
    val settingsStore: TranslationSettingsStore,
    val progressStore: ReadingProgressStore,
    val translationCache: TranslationCache,
    val context: android.content.Context,
    /** Whether a translation engine is reachable. False today. */
    val translationEngineAvailable: Boolean = false,
) 


sealed interface ShuraScreen {
    data object Home : ShuraScreen
    data object Sources : ShuraScreen
    data object Library : ShuraScreen
    data object Settings : ShuraScreen
    data class Search(val sourceId: String) : ShuraScreen
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
    /** Opens a chapter: fetch its pages, then show the reader. */
    onOpenChapter: (sourceId: String, mangaTitle: String, ShuraSource, Chapter) -> Unit = { _, _, _, _ -> },
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
            val sourceOf = { id: String -> nav.sourceHost.find(id) }

            androidx.compose.material3.Scaffold(
                bottomBar = {
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
                                sourceOf(entry.sourceId)?.let { source ->
                                    route = ShuraScreen.Reader(
                                        entry.sourceId, entry.mangaTitle,
                                        Chapter(entry.chapterId, entry.chapterName, null),
                                        emptyList(),
                                    )
                                }
                            },
                            onBrowse = { route = ShuraScreen.Sources },
                            onOpenLibrary = { route = ShuraScreen.Library },
                        )

                        is ShuraScreen.Sources -> SourcePickerScreen(
                            sources = nav.sourceHost.all(),
                            onPick = { route = ShuraScreen.Search(it.id) },
                        )

                        is ShuraScreen.Library -> LibraryScreen(
                            stored = libraryState,
                            onRead = { entry ->
                                route = ShuraScreen.Reader(entry.sourceId, entry.title,
                                    Chapter(entry.chapterId, entry.title, null), emptyList())
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
                        )

                        is ShuraScreen.Search -> {
                            val source = sourceOf(screen.sourceId)
                            if (source == null) StatePanel(shura("common.error"), screen.sourceId)
                            else SearchScreen(
                                source = source,
                                onOpenManga = { manga ->
                                    route = ShuraScreen.Chapters(screen.sourceId, manga)
                                },
                                onBack = { route = ShuraScreen.Sources },
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
                                    onOpenChapter(screen.sourceId, screen.manga.title, source, chapter)
                                },
                                onBack = { route = ShuraScreen.Search(screen.sourceId) },
                            )
                        }

                        is ShuraScreen.Reader -> {
                            val saved = nav.progressStore.pageFor(
                                screen.sourceId, screen.mangaTitle, screen.chapter.id,
                                pages.size.coerceAtLeast(1),
                            )
                            ReaderScreen(
                                pages = pages,
                                sourceId = screen.sourceId,
                                chapterId = screen.chapter.id,
                                chapterTitle = screen.mangaTitle + " · " + screen.chapter.name,
                                savedPage = saved,
                                onProgress = onProgressChanged,
                                onPrevChapter = null,
                                onNextChapter = null,
                                onBack = onExitReader,
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
