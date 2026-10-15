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
import app.shura.manga.reader.ProgressivePageLoader
import app.shura.manga.reader.ReaderSettings
import app.shura.manga.reader.ReaderSettingsStore
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.reader.ReadingProgressStore
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationSettingsStore
import app.shura.manga.ui.ChapterDownloader
import app.shura.manga.ui.ShuraNav
import app.shura.manga.ui.ShuraRoot
import app.shura.manga.ui.Strings
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

/**
 * What an install row is doing right now.
 *
 * Named rather than a triple because it is read in a `when` in the store and
 * a positional `Triple` there would be unreadable at exactly the moment it
 * matters - deciding what a row is showing and why.
 */
private data class InstallProgress(
    /** 0..1 while downloading; null when nothing is in flight. */
    val fraction: Float? = null,
    /** The real failure reason, shown next to the button that retries it. */
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * Where extension artifacts are fetched from.
 *
 * A repository served over plain HTTP would be refused by the app's own
 * `usesCleartextTraffic="false"`, so the base URL must be HTTPS. Nothing is
 * installed until the bytes have been hashed and the certificate read out of
 * the APK, so a wrong or hostile host here costs the user a failed download
 * and nothing else.
 */
private const val REPOSITORY_BASE_URL = "https://shura.app/repository"
private const val KEY_DARK = "ui-dark"

@androidx.compose.runtime.Composable
private fun ShuraApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("shura-ui", 0) }
    val scope = rememberCoroutineScope()

    var language by remember {
        mutableStateOf(
            // Arabic is Shura's first language. English is chosen, not
            // defaulted: a user who wants it switches in Settings once.
            runCatching {
                UiLanguage.valueOf(preferences.getString(KEY_LANGUAGE, "ar") ?: "ar")
            }.getOrDefault(UiLanguage.ARABIC)
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
    // The cover of the work currently open, recorded so Home and the reader can
    // show it without going back to a search list that has scrolled away.
    var coverUrl by remember { mutableStateOf("") }

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
                readerMeta = ReaderMeta("local", "Local chapter", "", null, "device", "")
                loadError = null
            }.onFailure {
                loadError = it.message ?: "Import failed"
            }
        }
    }


    /**
     * Downloads, verifies and installs one extension, reporting progress on
     * its own row.
     *
     * The row is cleared of progress whatever happens, and a failure leaves the
     * reason next to the button rather than only in a banner: a spinner that
     * stops with no explanation is the least useful thing an install can do.
     */
    fun installExtension(
        entry: app.shura.manga.ExtensionEntry,
        state: MutableMap<String, InstallProgress>,
        onChanged: () -> Unit,
    ) {
        val repositoryPackage = app.shura.runtime.RepositoryPackage(
            pkg = entry.pkg,
            version = entry.version,
            apk = entry.artifactName,
            lang = entry.lang,
            identity = entry.identity,
            artifactSha256 = entry.publishedSha256,
            signingCertificateSha256 = entry.signer,
            indexVersionCode = entry.versionCode,
            indexExtensionLib = null,
        )
        scope.launch {
            state[entry.pkg] = InstallProgress(fraction = 0f)
            val installer = ExtensionInstaller(context, ShuraExtensions.policy)
            val result = installer.install(
                repositoryPackage,
                REPOSITORY_BASE_URL,
            ) { done, total ->
                if (total > 0) {
                    state[entry.pkg] = InstallProgress(
                        fraction = (done.toFloat() / total).coerceIn(0f, 1f),
                    )
                }
            }
            state[entry.pkg] = when (result) {
                is app.shura.manga.InstallResult.Installed -> {
                    // Only now, after the bytes are on disk and verified, is
                    // the source actually registered. Saying "installed"
                    // before this point is the lie the store must not tell.
                    val failures = withContext(Dispatchers.IO) {
                        ShuraRepository.reload(sourceHost, context)
                    }
                    onChanged()
                    if (failures.isEmpty()) InstallProgress()
                    else InstallProgress(message = failures.joinToString("\n"), failed = true)
                }
                is app.shura.manga.InstallResult.DownloadFailed ->
                    InstallProgress(message = result.reason, failed = true)
                is app.shura.manga.InstallResult.Refused ->
                    InstallProgress(message = result.reason, failed = true)
            }
            onChanged()
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
        // One page per call, so the reader can show page 1 while the rest
        // download. The cache directory is keyed by chapter and page name, so
        // re-opening a chapter reuses what already landed.
        val chapterReader = MangaDexChapterDownloader()
        val downloader = ChapterDownloader { _, chapterId, page ->
            withContext(Dispatchers.IO) {
                val dir = File(
                    context.cacheDir,
                    "reader-$chapterId".replace(Regex("[^A-Za-z0-9_.-]"), "_"),
                )
                Uri.fromFile(chapterReader.downloadPage(page, dir))
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

    // The extension store reads the installed repository's index and applies
    // this build's trust policy to each entry. Rebuilt whenever the repository
    // changes or a screen is entered, so a removal shows up immediately.
    var storeVersion by remember { mutableIntStateOf(0) }
    // Bumped when extensions are removed, so the source list is re-read.
    var sourcesVersion by remember { mutableIntStateOf(0) }

    // The repository this build ships, unpacked once so the store has real
    // extensions to show instead of an empty list.
    LaunchedEffect(Unit) {
        if (RepositorySeed.seed(context)) {
            val failures = withContext(Dispatchers.IO) {
                ShuraRepository.reload(sourceHost, context)
            }
            repositoryNotice = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            storeVersion++
        }
    }


    val catalog = remember(storeVersion) { ExtensionCatalog.forBuild(context) }
    val extensions = remember(storeVersion, catalog) {
        ExtensionCatalog.rowStates(catalog.describe(catalog.directory))
    }
    // Progress and failure text for the row being installed, keyed by package.
    val installState = remember { mutableStateMapOf<String, InstallProgress>() }

    val history = remember(historyVersion) { progressStore.history() }
    val library = remember(libraryVersion) { indexStoredChapters(chapterStore(context)) }

    // The reader's data source: the pages of whatever is open.
    // The loader for the chapter currently open. Kept so switching chapters
    // can cancel the previous one's downloads instead of racing it.
    var loader by remember { mutableStateOf<ProgressivePageLoader<Uri>?>(null) }
    // The chapter's real page count. The reader shows "3 of 40" even though
    // only 3 have downloaded: the count is the chapter's, not the download's.
    var chapterPageTotal by remember { mutableIntStateOf(0) }
    // The reading layout, kept between launches - see ReaderSettings.
    val readerSettingsStore = remember { ReaderSettingsStore(context) }
    var readerMode by remember { mutableStateOf(readerSettingsStore.load().mode) }

    val openChapter: (app.shura.source.ShuraSource, app.shura.source.Chapter, String) -> Unit =
        { source, chapter, cover ->
            val sourceId = source.id
            val mangaTitle = readerMeta?.mangaTitle ?: source.name
            val mangaId = readerMeta?.mangaId ?: ""
            if (cover.isNotBlank()) coverUrl = cover
            // Whatever the last chapter was still downloading stops here; its
            // pages would otherwise land in the new chapter's list.
            loader?.cancel()
            loader = null
            scope.launch {
                isLoading = true
                loadError = null
                pages.clear()
                val pageRefs = runCatching {
                    withContext(Dispatchers.IO) { source.pages(chapter.id) }
                }
                pageRefs.onFailure {
                    isLoading = false
                    loadError = it.message ?: "Could not open this chapter."
                }.onSuccess { refs ->
                    if (refs.isEmpty()) {
                        isLoading = false
                        loadError = "This chapter returned no pages from ${source.name}."
                        return@onSuccess
                    }
                    // The reader opens as soon as the page list is known, then
                    // fills in underneath: page 1 shows while 2..N download.
                    // Not a real path, so decoding fails and the reader shows
                    // its own retryable error rather than a blank tile.
                    val failedPage = Uri.parse("shura://page-unavailable")
                    val fresh = ProgressivePageLoader<Uri>(
                        pageCount = refs.size,
                        failedPage = failedPage,
                        fetch = { index ->
                            runCatching {
                                nav.downloader.pageAt(source, chapter.id, refs[index])
                            }.getOrNull()
                        },
                    )
                    // The loader grows its own page list; copy it into the
                    // snapshot list the reader observes, whenever it changes.
                    fresh.onChanged = {
                        val snapshot = fresh.available
                        pages.clear()
                        pages.addAll(snapshot)
                    }
                    loader = fresh
                    chapterPageTotal = refs.size
                    fresh.start(scope)
                    readerMeta = ReaderMeta(sourceId, mangaTitle, mangaId, chapter, source.name, cover)
                    isLoading = false
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
        // A chapter that is still downloading renders from the loader's own
        // list, which grows as pages land. A stored or saved chapter has no
        // loader, so it renders from the local list. Both are snapshot lists,
        // so the reader redraws as either one changes.
        pages = pages,
        onPageVisible = { index -> loader?.ensure(index) },
        onRetryPage = { index -> loader?.retry(index) },
        totalPages = { chapterPageTotal.takeIf { it > 0 } },
        readerMode = readerMode,
        onReaderModeChange = { chosen ->
            readerMode = chosen
            readerSettingsStore.save(ReaderSettings(chosen))
        },
        isLoading = isLoading,
        loadError = loadError,
        onFetchChapter = { source, chapter, cover -> openChapter(source, chapter, cover) },
        // Off the main thread: a resume refetches the whole list, and doing
        // that inline freezes the tap that asked for it.
        fetchChapters = { source, mangaId ->
            withContext(Dispatchers.IO) {
                runCatching { source.chapters(mangaId) }.getOrNull()
            }
        },
        onOpenStored = { entry ->
            // The pages are local files; the reader points at them directly.
            loader?.cancel()
            loader = null
            pages.clear()
            pages.addAll(entry.pageUris)
            chapterPageTotal = entry.pageUris.size
            readerMeta = ReaderMeta(entry.sourceId, entry.title, entry.mangaId, null, "library", entry.coverUrl)
            loadError = null
            isLoading = false
        },
        onRetry = {
            val meta = readerMeta
            val source = meta?.sourceId?.let { sourceHost.find(it) }
            val chapter = meta?.chapter
            if (source != null && chapter != null) {
                openChapter(source, chapter, readerMeta?.coverUrl.orEmpty())
            }
        },
        onExitReader = {
            loader?.cancel()
            loader = null
            chapterPageTotal = 0
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
                        mangaId = meta.mangaId,
                        coverUrl = meta.coverUrl.ifBlank { coverUrl },
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
        extensions = remember(extensions, installState) {
            extensions.map { row ->
                val progress = installState[row.entry.pkg] ?: InstallProgress()
                row.copy(
                    progress = progress.fraction,
                    message = progress.message,
                    failed = progress.failed,
                )
            }
        },
        onInstallExtension = { entry -> installExtension(entry, installState) { storeVersion += 1 } },
        sourcesVersion = sourcesVersion,
        extensionCatalog = catalog,
        onRemoveExtension = { entry ->
            scope.launch {
                val name = entry.identity
                // Deletes the artifact the catalogue actually resolved, not a
                // path rebuilt from the index - which would silently miss.
                val removed = withContext(Dispatchers.IO) {
                    entry.artifactPath?.let { File(it).delete() } ?: false
                }
                val failures = withContext(Dispatchers.IO) {
                    ShuraRepository.reload(sourceHost, context)
                }
                repositoryNotice = buildString {
                    val key = if (removed) "store.removed" else "error.generic"
                    append(Strings.t(key, language, name))
                    failures.forEach { append("\n$it") }
                }
                // Bumping both re-reads the store and the source list, so a
                // removal is visible immediately rather than at next launch.
                storeVersion++
                sourcesVersion++
            }
        },
        onInstallRepository = { repositoryPicker.launch(null) },
    )
}

/** What the reader is currently showing, and how it got its pages. */
private data class ReaderMeta(
    val sourceId: String,
    val mangaTitle: String,
    val mangaId: String,
    val chapter: app.shura.source.Chapter?,
    val sourceName: String,
    val coverUrl: String = "",
)
