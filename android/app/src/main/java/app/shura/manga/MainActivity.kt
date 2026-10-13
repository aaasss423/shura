package app.shura.manga

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.shura.host.MangaDexChapterDownloader
import app.shura.host.MangaDexSource
import app.shura.host.SourceHost
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationSettingsStore
import app.shura.manga.ui.ChapterDownloader
import app.shura.manga.ui.ShuraNav
import app.shura.manga.ui.ShuraRoot
import app.shura.manga.ui.theme.UiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShuraApp() }
    }
}

private const val KEY_LANGUAGE = "ui-language"
private const val KEY_DARK = "ui-dark"

@androidx.compose.runtime.Composable
private fun ShuraApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("shura-ui", 0) }
    val scope = rememberCoroutineScope()

    var language by remember {
        mutableStateOf(
            runCatching { UiLanguage.valueOf(preferences.getString(KEY_LANGUAGE, "en") ?: "en") }
                .getOrDefault(UiLanguage.ENGLISH)
        )
    }
    var dark by remember { mutableStateOf(preferences.getBoolean(KEY_DARK, true)) }

    val translationStore = remember { TranslationSettingsStore(context) }
    val progressStore = remember { ReadingProgressStore(context) }
    val translationCache = remember { TranslationCache(context) }

    val sourceHost = remember {
        SourceHost().apply {
            // MangaDex stays as the built-in adapter; extensions from the
            // repository are added beside it, never instead of it.
            register(MangaDexSource())
            ShuraExtensions.load(context, File(context.filesDir, "repo"))
                .forEach { outcome -> outcome.source?.let { register(it) } }
        }
    }

    // Local chapters currently open in the reader, and how they got there.
    val pages = remember { mutableStateListOf<Uri>() }
    var readerMeta by remember { mutableStateOf<ReaderMeta?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var libraryVersion by remember { mutableIntStateOf(0) }
    var repositoryNotice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selected ->
        if (selected.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    selected.map { uri ->
                        val target = File(context.filesDir, "import/${System.currentTimeMillis()}_${uri.hashCode()}")
                        target.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(uri)?.use { i ->
                            target.outputStream().use { o -> i.copyTo(o) }
                        }
                        Uri.fromFile(target)
                    }
                }
            }
            outcome.onSuccess { imported ->
                pages.clear()
                pages.addAll(imported)
                readerMeta = ReaderMeta("local", "Local chapter", null, "device")
                loadError = null
            }.onFailure {
                loadError = it.message ?: "Import failed"
            }
        }
    }

    val repositoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ShuraRepository.installFromTree(context, tree)
            }
            val failures = withContext(Dispatchers.IO) {
                ShuraRepository.reload(sourceHost, context)
            }
            repositoryNotice = failures.ifEmpty {
                listOf(if (outcome.ok) "Repository installed: ${outcome.installed.size} files"
                else outcome.refused ?: "the repository could not be installed")
            }.joinToString("\n")
        }
    }

    // onInstallRepository has to be read lazily: the picker launcher is
    // declared after this call site, so capturing it eagerly reads a null.
    var installRequested by remember { mutableIntStateOf(0) }
    val nav = remember {
        val downloader = ChapterDownloader { source, chapterId ->
            withContext(Dispatchers.IO) {
                val pageRefs = source.pages(chapterId)
                val dir = File(context.cacheDir, "reader-${chapterId}".replace(Regex("[^A-Za-z0-9_.-]"), "_"))
                MangaDexChapterDownloader().downloadChapter(pageRefs, dir).map { Uri.fromFile(it) }
            }
        }
        ShuraNav(
            sourceHost = sourceHost,
            downloader = downloader,
            settingsStore = translationStore,
            progressStore = progressStore,
            translationCache = translationCache,
            context = context,
            // No translation engine is reachable; the reader says so rather than
            // pretending the control works.
            translationEngineAvailable = BuildConfig.TRANSLATION_ENABLED,
        )
    }

    val history = remember(historyVersion) { progressStore.history() }
    val library = remember(libraryVersion) { indexStoredChapters(chapterStore(context)) }

    // The reader's data source: the pages of whatever is open.
    val openChapter: (app.shura.source.ShuraSource, app.shura.source.Chapter) -> Unit =
        { source, chapter ->
            val sourceId = source.id
            val mangaTitle = readerMeta?.mangaTitle ?: source.name
            scope.launch {
                isLoading = true
                loadError = null
                pages.clear()
                runCatching { nav.downloader.pages(source, chapter.id) }
                    .onSuccess { fetched ->
                        pages.clear()
                        pages.addAll(fetched)
                        readerMeta = ReaderMeta(sourceId, mangaTitle, chapter, source.name)
                        isLoading = false
                        if (fetched.isEmpty()) {
                            loadError = "This chapter returned no pages from ${source.name}."
                        }
                    }
                    .onFailure {
                        isLoading = false
                        loadError = it.message ?: "Could not open this chapter."
                    }
            }
        }

    ShuraRoot(
        nav = nav,
        language = language,
        onLanguage = { chosen ->
            language = chosen
            preferences.edit().putString(KEY_LANGUAGE, chosen.code).apply()
        },
        dark = dark,
        onDark = { chosen ->
            dark = chosen
            preferences.edit().putBoolean(KEY_DARK, chosen).apply()
        },
        pages = pages,
        isLoading = isLoading,
        loadError = loadError,
        onFetchChapter = { source, chapter -> openChapter(source, chapter) },
        onOpenStored = { entry ->
            // The pages are local files; the reader points at them directly.
            pages.clear()
            pages.addAll(entry.pageUris)
            readerMeta = ReaderMeta(entry.sourceId, entry.title, null, "library")
            loadError = null
            isLoading = false
        },
        onRetry = {
            val meta = readerMeta
            val source = meta?.sourceId?.let { sourceHost.find(it) }
            val chapter = meta?.chapter
            if (source != null && chapter != null) {
                openChapter(source, chapter)
            }
        },
        onExitReader = {
            readerMeta = null
            pages.clear()
            historyVersion++
        },
        history = history,
        onProgressChanged = { page ->
            // Progress is recorded per chapter, so switching chapters does not
            // overwrite the other one's place.
            val meta = readerMeta
            val chapter = meta?.chapter
            if (chapter != null && pages.isNotEmpty()) {
                progressStore.save(
                    ReadingProgress(
                        sourceId = meta.sourceId,
                        mangaTitle = meta.mangaTitle,
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        page = page,
                        pageCount = pages.size,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                historyVersion++
            }
        },
        library = library,
        repositoryNotice = repositoryNotice,
        onImport = { picker.launch(arrayOf("image/*")) },
        onInstallRepository = { repositoryPicker.launch(null) },
    )
}

/** What the reader is currently showing, and how it got its pages. */
private data class ReaderMeta(
    val sourceId: String,
    val mangaTitle: String,
    val chapter: app.shura.source.Chapter?,
    val sourceName: String,
)
