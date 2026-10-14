package app.shura.manga.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.translation.TranslationMode
import app.shura.manga.reader.translation.TranslationSettings
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.Ink
import app.shura.manga.ui.theme.LocalUiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.shura.manga.ui.image.ImageStore

/**
 * The manga reader.
 *
 * Vertical, one column, zoomable, with the controls in a bar that the reader
 * can dismiss - never a floating window over the page and never an overlay
 * service. The page owns the screen; the bar is an affordance over it.
 *
 * Translation is a mode, not a separate rendering path. It is chosen from this
 * bar, it survives a chapter change, and it only ever adds text beside a page -
 * it never intercepts a touch, so pinch, scroll and tap keep working with
 * translation on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    pages: List<Uri>,
    sourceId: String,
    chapterId: String,
    chapterTitle: String,
    savedPage: Int,
    onProgress: (Int) -> Unit,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    onBack: () -> Unit,
    /** Opens the next chapter, offered when this one turned out to have no pages. */
    onOpenNextChapter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    translationSettings: TranslationSettings = TranslationSettings(),
    onTranslationSettingsChange: (TranslationSettings) -> Unit = {},
    translationCache: TranslationCache? = null,
    /** Whether a translation engine is actually reachable. */
    translationEngineAvailable: Boolean = false,
    isLoading: Boolean = false,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var barVisible by remember { mutableStateOf(true) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    )

    LaunchedEffect(listState, pages.size) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { onProgress(it) }
    }

    // Keep the chapter's own place when the page list arrives late: the reader
    // opens at the stored page, not at page 1.
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && savedPage in 0 until pages.size &&
            listState.firstVisibleItemIndex == 0
        ) {
            listState.scrollToItem(savedPage)
        }
    }

    Box(modifier.fillMaxSize().background(Ink)) {
        when {
            loadError != null -> ReaderMessage(
                title = shura("common.error"),
                body = loadError,
                actionLabel = onRetry?.let { shura("common.retry") },
                onAction = onRetry ?: {},
            )
            isLoading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(shura("common.loading"), color = Color(0xB3FFFFFF))
            }
            pages.isEmpty() -> ReaderMessage(
                title = shura("nav.reader"),
                body = shura("reader.noPages"),
                actionLabel = onOpenNextChapter?.let { shura("reader.next") },
                onAction = { onOpenNextChapter?.invoke() },
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("reader-pages"),
                contentPadding = PaddingValues(bottom = if (barVisible) 96.dp else 24.dp),
            ) {
                items(count = pages.size, key = { index -> "$index:${pages[index]}" }) { index ->
                    ReaderPage(
                        uri = pages[index],
                        index = index,
                        pageCount = pages.size,
                        translation = translationCache?.pagesFor(sourceId, chapterId)
                            ?.getOrNull(index),
                        translationPending = translationSettings.mode != TranslationMode.OFF &&
                            !translationEngineAvailable,
                        onTap = { barVisible = !barVisible },
                    )
                }
            }
        }

        // The bar. Dismissable so the page can be read unobstructed, and it is
        // a sibling of the pages rather than something drawn on top of them.
        AnimatedVisibility(
            visible = barVisible && pages.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBar(
                chapterTitle = chapterTitle,
                currentPage = listState.firstVisibleItemIndex + 1,
                pageCount = pages.size,
                rtl = rtl,
                onBack = onBack,
                onProgress = { index ->
                    scope.launch { listState.scrollToItem(index.coerceIn(0, pages.lastIndex)) }
                },
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                translationSettings = translationSettings,
                onTranslationSettingsChange = onTranslationSettingsChange,
                engineAvailable = translationEngineAvailable,
            )
        }
    }
}

/** One page, its label, and any translated text beside it. */
@Composable
private fun ReaderPage(
    uri: Uri,
    index: Int,
    pageCount: Int,
    translation: String?,
    translationPending: Boolean,
    onTap: () -> Unit,
) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                shura("reader.pages", index + 1, pageCount),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x61FFFFFF),
            )
            if (translationPending) {
                Text(
                    shura("reader.translationPending"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0x61FFFFFF),
                )
            }
        }
        PageImage(uri, index, pageCount, scale, transform, onRetry = { uri })
        if (!translation.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    translation,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * One page, decoded once and kept.
 *
 * Three things this fixes, all of them the difference between a reader that
 * works and one that does not:
 *
 *  - **The displayed image must match the page number.** The state is keyed by
 *    the page's own URI, so scrolling from page 1 to page 2 shows page 2's
 *    bytes. Keyed by position instead, a recycled row keeps the previous
 *    bitmap and the number runs ahead of the picture - which is exactly the
 *    "the number changes but the image does not" symptom.
 *  - **Work must stop when the reader moves on.** Decoding is cancelled when
 *    the key changes, so a fast scroll does not queue every page it passed.
 *  - **Coming back must be instant.** Decoded bitmaps live in [ImageStore], so
 *    a page shown once is not decoded again.
 *
 * The box keeps the page's own aspect ratio once it is known, so the list does
 * not jump and no page is stretched.
 */
@Composable
private fun PageImage(
    uri: Uri,
    index: Int,
    pageCount: Int,
    scale: Float,
    transform: androidx.compose.foundation.gestures.TransformableState,
    onRetry: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    var failed by remember(uri) { mutableStateOf(false) }
    var ratio by remember(uri) { mutableFloatStateOf(0f) }
    var attempt by remember(uri) { mutableStateOf(0) }

    LaunchedEffect(uri, attempt) {
        loading = true
        failed = false
        val cached: android.graphics.Bitmap? = ImageStore.cached(uri.toString())
        val decoded: android.graphics.Bitmap? =
            cached ?: ImageStore.decode(context, uri.toString(), MAX_EDGE)
                ?.also { ImageStore.put(uri.toString(), it) }
        ensureActive()
        if (decoded != null) {
            bitmap = decoded.asImageBitmap()
            ratio = if (decoded.height > 0) decoded.width.toFloat() / decoded.height else 0f
        } else {
            failed = true
        }
        loading = false
    }

    val image = bitmap
    Box(
        Modifier.fillMaxWidth()
            .then(
                if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.heightIn(min = 200.dp)
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .transformable(transform),
                contentScale = ContentScale.Fit,
            )
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    shura("common.error"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { attempt++ }) { Text(shura("common.retry")) }
            }
            else -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val MAX_EDGE = 1600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBar(
    chapterTitle: String,
    currentPage: Int,
    pageCount: Int,
    rtl: Boolean,
    onBack: () -> Unit,
    onProgress: (Int) -> Unit,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    translationSettings: TranslationSettings,
    onTranslationSettingsChange: (TranslationSettings) -> Unit,
    engineAvailable: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(shura("common.back")) }
                Text(
                    chapterTitle,
                    Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // Chapter navigation follows reading order, which is the reverse
                // of the visual direction in RTL - so the arrows are placed by
                // reading order, not by text direction.
                if (onPrevChapter != null) TextButton(onClick = onPrevChapter) { Text("◀") }
                if (onNextChapter != null) TextButton(onClick = onNextChapter) { Text("▶") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shura("reader.pages", currentPage, pageCount),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                TranslationControl(
                    settings = translationSettings,
                    onChange = onTranslationSettingsChange,
                    engineAvailable = engineAvailable,
                )
            }
            if (pageCount > 1) {
                val fraction = if (pageCount <= 1) 0f else (currentPage - 1).toFloat() / (pageCount - 1)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Translation, inside the reader bar.
 *
 * Deliberately inline rather than a dialog: the reader keeps the page it is on
 * and the mode is one tap away. With no engine reachable the control says so
 * rather than pretending, and the modes can still be chosen - the choice is a
 * reader preference and survives to the moment an engine exists.
 */
@Composable
private fun TranslationControl(
    settings: TranslationSettings,
    onChange: (TranslationSettings) -> Unit,
    engineAvailable: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!engineAvailable) {
            Text(
                shura("reader.translationUnavailable"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        FilterChip(
            selected = settings.mode != TranslationMode.OFF,
            onClick = {
                onChange(settings.copy(mode = if (settings.mode == TranslationMode.OFF)
                    TranslationMode.FULL else TranslationMode.OFF))
            },
            label = { Text(shura("reader.translate")) },
        )
        Spacer(Modifier.width(6.dp))
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }, enabled = settings.mode != TranslationMode.OFF) {
                Text(settings.mode.label())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(
                    TranslationMode.OFF to shura("reader.translationOff"),
                    TranslationMode.FULL to shura("reader.translationFull"),
                    TranslationMode.FOLLOW_READING to shura("reader.translationFollow"),
                ).forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onChange(settings.copy(mode = mode)); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationMode.label(): String = when (this) {
    TranslationMode.OFF -> shura("reader.translationOff")
    TranslationMode.FULL -> shura("reader.translationFull")
    TranslationMode.FOLLOW_READING -> shura("reader.translationFollow")
}

@Composable
private fun ReaderMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = Color(0xB3FFFFFF),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
