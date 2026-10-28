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
import app.shura.host.SourceChapterDownloader
import app.shura.host.MangaDexSource
import app.shura.host.SourceHost
import app.shura.manga.reader.ProgressivePageLoader
import app.shura.manga.reader.ChapterDeletion
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
        // Before anything is composed, so the report of a crash that happened on
        // the way out of the last launch is the first thing on screen.
        CrashReporter.install(this)
        setContent { ShuraApp() }
        CrashReporter.show(this)
    }
}

private const val KEY_LANGUAGE = "ui-language"
private const val KEY_DYNAMIC_COLOR = "ui-dynamic-color"

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

/** Stands in until the first off-thread index read lands. */
private fun emptyDirectory() = File("/nonexistent-shura-library")
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
    var dynamicColor by remember {
        mutableStateOf(preferences.getBoolean(KEY_DYNAMIC_COLOR, false))
    }

    val translationStore = remember { TranslationSettingsStore(context) }
    val progressStore = remember { ReadingProgressStore(context) }
    val translationCache = remember { TranslationCache(context) }

    val sourceHost = remember {
        SourceHost().apply {
            // The built-in source is ready immediately. Reading and loading
            // extension APKs is deferred to the background startup effect.
            register(MangaDexSource())
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
            // Fetched from, and written into, the repository that published it.
            // A package a reader added by URL is not on shura.app, so the
            // built-in address could only ever produce a download failure for
            // everything a URL repository offers.
            val origin = entry.origin
            val result = installer.install(
                entry = repositoryPackage,
                baseUrl = origin?.url ?: REPOSITORY_BASE_URL,
                directory = origin?.directory ?: app.shura.manga.ShuraRepository.directory(context),
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
    // One page per call, so the reader can show page 1 while the rest
    // download. The cache directory is keyed by chapter and page name, so
    // re-opening a chapter reuses what already landed.
    //
    // Every source, not just MangaDex: an extension publishes on its own CDN, and
    // the MangaDex-only rule meant no page of theirs could ever be fetched. The
    // rule that replaced it still refuses loopback, private and link-local hosts.
    val chapterReader = remember { SourceChapterDownloader() }
    val downloader = remember {
        ChapterDownloader { _, chapterId, page ->
            withContext(Dispatchers.IO) {
                val dir = File(
                    context.cacheDir,
                    "reader-$chapterId".replace(Regex("[^A-Za-z0-9_.-]"), "_"),
                )
                Uri.fromFile(chapterReader.downloadPage(page, dir))
            }
        }
    }
    // Chapter saves. The queue outlives any screen, so a download survives
    // navigating away from the chapter list, and it tells the activity when the
    // library on disk has actually changed.
    val downloads = remember { DownloadQueue(context, scope, downloader) }
    DisposableEffect(downloads) {
        downloads.onLibraryChanged = { libraryVersion += 1 }
        onDispose { downloads.onLibraryChanged = null }
    }
    val nav = remember {
        ShuraNav(
            sourceHost = sourceHost,
            downloader = downloader,
            settingsStore = translationStore,
            progressStore = progressStore,
            translationCache = translationCache,
            context = context,
            downloads = downloads,
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

    // Repository extraction and extension APK verification/class loading can
    // involve several megabytes of disk I/O. Keep the built-in source available
    // while this runs away from the UI thread.
    LaunchedEffect(Unit) {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                RepositorySeed.seed(context)
                ShuraRepository.reload(sourceHost, context)
            }
        }
        outcome.onSuccess { failures ->
            repositoryNotice = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            storeVersion += 1
            sourcesVersion += 1
        }.onFailure { error ->
            repositoryNotice = "The bundled extensions could not be prepared: ${error.message}"
            sourcesVersion += 1
        }
    }


    var catalog by remember { mutableStateOf<ExtensionCatalog?>(null) }
    var extensions by remember {
        mutableStateOf<List<app.shura.manga.ui.screens.ExtensionRowState>>(emptyList())
    }
    LaunchedEffect(storeVersion) {
        val loaded = withContext(Dispatchers.IO) {
            val current = ExtensionCatalog.forBuild(context)
            current to ExtensionCatalog.rowStates(current.describeAll())
        }
        catalog = loaded.first
        extensions = loaded.second
    }
    // Progress and failure text for the row being installed, keyed by package.
    val installState = remember { mutableStateMapOf<String, InstallProgress>() }

    val history = remember(historyVersion) { progressStore.history() }
    // The library index reads a directory listing and a JSON manifest per chapter.
    // That is disk I/O, and a `remember` runs it on the main thread - so opening
    // the library blocked the UI for as long as the disk took, which is exactly how
    // Android decides an app is not responding and closes it. Read once off the
    // thread instead, and show what was last known until the new answer lands.
    var library by remember { mutableStateOf(indexStoredChapters(emptyDirectory())) }
    // The same store, read in the shape the reader asks its questions in.
    //
    // This was the whole of "a downloaded chapter will not open offline":
    // `storedRecords` on the navigation side defaulted to an empty list and nobody
    // ever passed a value, so the offline check answered "not on this device" for
    // every chapter - including the ones sitting on disk - and the reader went to
    // the source, which cannot be reached with the network off. The chapter list
    // still worked because that route opens a stored record directly.
    var storedRecords by remember { mutableStateOf(emptyList<StoredChapterRecord>()) }
    LaunchedEffect(libraryVersion) {
        val store = chapterStore(context)
        val indexed = withContext(Dispatchers.IO) { indexStoredChapters(store) }
        val records = withContext(Dispatchers.IO) { indexStoredChapterRecords(store) }
        library = indexed
        storedRecords = records
    }

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
    var readerPreferences by remember { mutableStateOf(readerSettingsStore.loadPreferences()) }

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
                        // From the reader's own settings, so "preload ahead" and
                        // "parallel downloads" are settings rather than constants
                        // buried in the loader's signature.
                        lookAhead = readerPreferences.preload,
                        parallelism = readerPreferences.parallelism,
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
        dynamicColor = dynamicColor,
        onDynamicColor = { chosen ->
            dynamicColor = chosen
            preferences.edit().putBoolean(KEY_DYNAMIC_COLOR, chosen).apply()
        },
        // A chapter that is still downloading renders from the loader's own
        // list, which grows as pages land. A stored or saved chapter has no
        // loader, so it renders from the local list. Both are snapshot lists,
        // so the reader redraws as either one changes.
        pages = pages,
        storedRecords = storedRecords,
        onPageVisible = { index -> loader?.ensure(index) },
        onRetryPage = { index -> loader?.retry(index) },
        totalPages = { chapterPageTotal.takeIf { it > 0 } },
        readerMode = readerMode,
        onReaderModeChange = { chosen ->
            readerMode = chosen
            readerSettingsStore.save(ReaderSettings(chosen))
        },
        readerPreferences = readerPreferences,
        onReaderPreferencesChange = { chosen ->
            readerPreferences = chosen
            readerSettingsStore.savePreferences(chosen)
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
            // The record's page files, resolved the same way the offline check does:
            // a record whose named pages are gone is not a chapter the reader can open.
            val localPages = LocalChapterRead.pageFiles(entry)
            pages.addAll(localPages.map { Uri.fromFile(it) })
            chapterPageTotal = localPages.size
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
        // Mihon's "delete the chapter after reading", decided here because this
        // is where the library is known: the reader reports reaching the last
        // page and what the preference was, and whether anything is actually
        // saved under that chapter id is the one fact only this side can answer.
        onChapterFinished = { finished ->
            val chapter = library.firstOrNull { it.chapterId == finished.chapterId }
            val wanted = ChapterDeletion.shouldDelete(
                lastPageReached = finished.lastPageReached,
                preferenceOn = finished.deleteAfterReading,
                chapterStored = chapter != null,
            )
            if (chapter == null || !wanted) return@ShuraRoot
            scope.launch {
                // Off the main thread, like every other touch of the store: a
                // recursive delete of a chapter's pages is disk work, and the
                // reader is still drawing.
                val gone = withContext(Dispatchers.IO) {
                    runCatching { deleteStoredChapter(chapter) }.isSuccess && !chapter.dir.exists()
                }
                // Only a real deletion re-reads the index. A failed one leaves
                // the library exactly as it was, and saying so by doing nothing
                // is better than a list that flickers.
                if (gone) libraryVersion += 1
            }
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
        onRepositoriesChanged = {
            // Both the store and the source list are read from disk and cached
            // behind these two counters, so a repository being added, refreshed
            // or removed has to bump both or nothing on screen changes.
            storeVersion++
            sourcesVersion++
        },
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
