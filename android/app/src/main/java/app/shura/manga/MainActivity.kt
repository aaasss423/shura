package app.shura.manga

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.shura.host.MangaDexChapterDownloader
import app.shura.host.MangaDexSource
import app.shura.host.SourceHost
import app.shura.manga.reader.translation.TranslationMode
import app.shura.manga.reader.translation.TranslationSettings
import app.shura.manga.reader.translation.TranslationSettingsStore
import app.shura.source.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ShuraApp() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ShuraApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("reader-library", 0) }
    val pages = remember {
        mutableStateListOf<Uri>().apply {
            runCatching {
                val saved = org.json.JSONArray(preferences.getString("chapter-pages", "[]"))
                for (index in 0 until saved.length()) add(Uri.parse(saved.getString(index)))
            }
        }
    }
    var tab by remember { mutableStateOf("Home") }
    val translationStore = remember { TranslationSettingsStore(context) }
    var translationSettings by remember { mutableStateOf(translationStore.load()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) {
            pages.clear()
            selected.forEach { uri ->
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                pages.add(uri)
            }
            preferences.edit().putString("chapter-pages", org.json.JSONArray(pages.map(Uri::toString)).toString()).apply()
            tab = "Reader"
        }
    }

    // Shura's own repository is the only source of extensions. MangaDex stays as
    // the built-in adapter; extensions loaded from the repository are added
    // alongside it, never instead of it, and each load failure is reported.
    var extensionNotice by remember { mutableStateOf<String?>(null) }
    val sourceHost = remember {
        SourceHost().apply {
            register(MangaDexSource())
            val outcomes = ShuraExtensions.load(context, java.io.File(context.filesDir, "repo"))
            outcomes.forEach { outcome ->
                val source = outcome.source
                if (source != null) register(source)
            }
            val failures = outcomes.mapNotNull { it.error }
            if (failures.isNotEmpty()) extensionNotice = failures.joinToString("\n")
        }
    }
    val appScope = rememberCoroutineScope()
    var readerTitle by remember { mutableStateOf("Local chapter") }
    var readerMangaTitle by remember { mutableStateOf("") }
    var readerChapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var readerChapterId by remember { mutableStateOf<String?>(null) }
    var activeSource by remember { mutableStateOf<app.shura.source.ShuraSource?>(null) }
    var downloadNotice by remember { mutableStateOf<String?>(null) }
    var downloadsVersion by remember { mutableIntStateOf(0) }

    fun currentChapterIndex(): Int = readerChapters?.indexOfFirst { it.id == readerChapterId } ?: -1
    fun neighbor(offset: Int): Chapter? {
        val index = currentChapterIndex()
        val list = readerChapters ?: return null
        val target = index + offset
        return if (target in list.indices) list[target] else null
    }

    val openChapter: (app.shura.source.ShuraSource, String, Chapter, List<Chapter>) -> Unit = { source, mangaTitle, chapter, chapterList ->
        appScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pageRefs = source.pages(chapter.id)
                    val dir = File(context.cacheDir, "reader-${chapter.id}".replace(Regex("[^A-Za-z0-9_.-]"), "_"))
                    MangaDexChapterDownloader().downloadChapter(pageRefs, dir).map { Uri.fromFile(it) }
                }
            }.onSuccess { uris ->
                pages.clear()
                pages.addAll(uris)
                readerTitle = "$mangaTitle · ${chapter.name}"
                readerMangaTitle = mangaTitle
                readerChapters = chapterList
                readerChapterId = chapter.id
                activeSource = source
                preferences.edit().putString("chapter-pages", org.json.JSONArray(pages.map(Uri::toString)).toString()).apply()
                tab = "Reader"
            }.onFailure { tab = "Sources"; downloadNotice = it.message ?: "failed to open chapter" }
        }
    }

    fun neighborChapter(source: app.shura.source.ShuraSource, offset: Int): Chapter? {
        val target = neighbor(offset) ?: return null
        openChapter(source, readerMangaTitle.ifBlank { "Chapter" }, target, readerChapters ?: emptyList())
        return target
    }

    val downloadChapter: (app.shura.source.ShuraSource, String, Chapter) -> Unit = { source, mangaTitle, chapter ->
        appScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pageRefs = source.pages(chapter.id)
                    val staging = File(context.cacheDir, "dl-${chapter.id}".replace(Regex("[^A-Za-z0-9_.-]"), "_"))
                    val files = MangaDexChapterDownloader().downloadChapter(pageRefs, staging)
                    persistStoredChapter(chapterStore(context), source.id, chapter.id, "$mangaTitle · ${chapter.name}", files)
                }
            }.onSuccess { downloadNotice = "Downloaded ${source.name} · ${chapter.name}"; downloadsVersion++ }
                .onFailure { downloadNotice = it.message ?: "download failed" }
        }
    }
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Shura · $tab") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Home", "Sources", "Library", "Downloads", "Settings").forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = {}, label = { Text(item) })
                    }
                }
            }
        ) { padding ->
            when (tab) {
                "Reader" -> ReaderScreen(
                    pages,
                    preferences.getInt("reader-page", 0),
                    { page -> preferences.edit().putInt("reader-page", page).apply() },
                    Modifier.padding(padding),
                    translationSettings,
                    onTranslationSettingsChange = { updated -> translationStore.save(updated); translationSettings = updated },
                    chapterTitle = readerTitle,
                    onPrevChapter = { activeSource?.let { source -> neighborChapter(source, -1) } },
                    onNextChapter = { activeSource?.let { source -> neighborChapter(source, +1) } },
                )
                "Sources" -> MangaBrowserScreen(
                    sources = sourceHost.all(),
                    onOpenChapter = openChapter,
                    onDownloadChapter = downloadChapter,
                    notice = extensionNotice ?: downloadNotice,
                    modifier = Modifier.padding(padding),
                )
                "Library" -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                    downloadNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)) }
                    Text(if (pages.isEmpty()) "Your library is empty" else "Local chapter · ${pages.size} pages")
                    if (pages.isNotEmpty()) Button(onClick = { tab = "Reader" }) { Text("Continue reading") }
                    Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Import chapter images") }
                }
                "Downloads" -> DownloadsScreen(
                    context,
                    onRead = { stored ->
                        pages.clear()
                        pages.addAll(stored.pageUris)
                        readerTitle = stored.title
                        activeSource = null
                        tab = "Reader"
                    },
                    refresh = downloadsVersion,
                    modifier = Modifier.padding(padding),
                )
                "Settings" -> PlaceholderScreen("Translation mode (Full chapter / Follow reading) lives in the reader bar and stays on across chapters until disabled. No screen overlay is used; the reader owns all touch input. Offline: imported chapter images are read from device storage.", Modifier.padding(padding))
                else -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                    Text("Read comics stored on this device", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Import chapter images") }
                    if (pages.isNotEmpty()) Button(onClick = { tab = "Reader" }) { Text("Continue · ${pages.size} pages") }
                    TextButton(onClick = { tab = "Sources" }) { Text("Source management") }
                }
            }
        }
    }
}

@Composable private fun PlaceholderScreen(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp)) { Text(text) }
}

@Composable internal fun ReaderScreen(
    pages: List<Uri>,
    savedPage: Int,
    onProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
    translationSettings: TranslationSettings = TranslationSettings(),
    onTranslationSettingsChange: (TranslationSettings) -> Unit = {},
    chapterTitle: String = "Local chapter",
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex }.collect { onProgress(it) } }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$chapterTitle · ${if (pages.isEmpty()) 0 else listState.firstVisibleItemIndex + 1}/${pages.size}")
            Row {
                if (onPrevChapter != null) TextButton(onClick = onPrevChapter) { Text("◀") }
                if (onNextChapter != null) TextButton(onClick = onNextChapter) { Text("▶") }
                TranslationMenu(translationSettings, onTranslationSettingsChange, enabled = BuildConfig.TRANSLATION_ENABLED)
            }
        }
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Import chapter images from Home or Library to read offline.") }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("reader-pages")) {
                itemsIndexed(pages, key = { index, uri -> "$index:$uri" }) { index, uri ->
                    ReaderPage(uri, "Page ${index + 1}")
                }
            }
        }
    }
}

@Composable private fun ReaderPage(uri: Uri, label: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(context, uri) }.getOrNull() }
    }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 4f) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().heightIn(min = 520.dp)) {
            bitmap?.let { image ->
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(), contentDescription = label,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).graphicsLayer { scaleX = scale; scaleY = scale }.transformable(transform),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun decodeSampled(context: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 1800 || bounds.outHeight / inSampleSize > 2600) inSampleSize *= 2
    }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

@Composable private fun TranslationMenu(
    settings: TranslationSettings,
    onChange: (TranslationSettings) -> Unit,
    enabled: Boolean,
) {
    if (!enabled) {
        // Feature-flagged: the control is visible but inert until a translation engine exists.
        TextButton(onClick = {}, enabled = false) { Text("Translate") }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Translate · ${settings.mode.displayName()}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName()) },
                    onClick = { onChange(settings.copy(mode = mode)); expanded = false },
                )
            }
        }
    }
}

@Composable private fun TranslationMode.displayName(): String = when (this) {
    TranslationMode.OFF -> "Off"
    TranslationMode.FULL -> "Full chapter"
    TranslationMode.FOLLOW_READING -> "Follow reading"
}
