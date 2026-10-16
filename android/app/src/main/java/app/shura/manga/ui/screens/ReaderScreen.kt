package app.shura.manga.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.translation.TranslationCache
import app.shura.manga.reader.ReaderMode
import app.shura.manga.reader.ReaderPreferences
import app.shura.source.Chapter
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
    /** The work's cover, for the reader's own header. */
    cover: String = "",
    savedPage: Int,
    onProgress: (Int) -> Unit,
    /**
     * The page now on screen.
     *
     * Downloads follow this: the reader asks for what it is showing plus a few
     * pages ahead, so scrolling forward never waits and scrolling back is warm.
     */
    onPageVisible: (Int) -> Unit = {},
    /** Re-fetches one page that failed, without disturbing the rest. */
    onRetryPage: ((Int) -> Unit)? = null,
    /**
     * The chapter's page count, which may exceed the pages downloaded so far.
     *
     * Falls back to the loaded count, so a stored chapter with nothing pending
     * counts itself correctly.
     */
    totalPages: Int? = null,
    /** Vertical strip, or one page at a time. Kept between sessions. */
    mode: ReaderMode = ReaderMode.VERTICAL,
    onModeChange: (ReaderMode) -> Unit = {},
    /** The chapter list the reader can open, so it can be stepped through. */
    chapters: List<Chapter> = emptyList(),
    /** Opens a different chapter from the reader's own chapter list. */
    onJumpToChapter: ((Chapter) -> Unit)? = null,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    onBack: () -> Unit,
    /** Opens the next chapter, offered when this one turned out to have no pages. */
    onOpenNextChapter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    translationSettings: TranslationSettings = TranslationSettings(),
    onTranslationSettingsChange: (TranslationSettings) -> Unit = {},
    translationCache: TranslationCache? = null,
    /** Brightness, preload, screen-awake and download parallelism. */
    readerPreferences: ReaderPreferences = ReaderPreferences(),
    onReaderPreferencesChange: (ReaderPreferences) -> Unit = {},
    /** Whether a translation engine is actually reachable. */
    translationEngineAvailable: Boolean = false,
    isLoading: Boolean = false,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Tools are hidden until asked for. They used to start visible, which put
    // a fixed bar over the bottom of every page from the moment a chapter
    // opened - the opposite of a reading surface.
    var barVisible by remember { mutableStateOf(false) }
    // A sheet, when open, takes the tools' place: two overlapping sets of
    // controls is how a reader becomes unusable on a phone.
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    // While the reader is being configured, the tools must not fade out from
    // under the finger that is using them.
    var interacting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    )
    val pagerState = rememberPagerState(
        initialPage = savedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    ) { pages.size.coerceAtLeast(1) }

    // Both modes report through one path, so progress is saved and downloads
    // are pulled in identically whichever layout is showing.
    val paged = mode == ReaderMode.PAGED
    val currentIndex = if (paged) pagerState.currentPage else listState.firstVisibleItemIndex
    val seek: (Int) -> Unit = { index ->
        val target = index.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        if (paged) scope.launch { pagerState.scrollToPage(target) }
        else scope.launch { listState.scrollToItem(target) }
    }

    LaunchedEffect(paged, pages.size) {
        if (paged) {
            snapshotFlow { pagerState.currentPage }.collect {
                onProgress(it)
                onPageVisible(it)
            }
        } else {
            snapshotFlow { listState.firstVisibleItemIndex }.collect {
                onProgress(it)
                onPageVisible(it)
            }
        }
    }

    // Keep the chapter's own place when the page list arrives late: the reader
    // opens at the stored page, not at page 1.
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && savedPage in 0 until pages.size && currentIndex == 0) {
            seek(savedPage)
        }
    }

    // Auto-hide, but only while nobody is using the tools. Fading them out in
    // the middle of a tap on a setting is the kind of small betrayal that
    // makes an app feel broken rather than considered.
    LaunchedEffect(barVisible, sheet, interacting) {
        if (barVisible && sheet == null && !interacting) {
            kotlinx.coroutines.delay(TOOLS_LINGER_MS)
            barVisible = false
        }
    }

    // The tap detector. Deliberately on the container rather than on each page
    // or on a transparent overlay above them: an overlay would swallow the
    // drags the scroll container and the pager need, which is what made
    // scrolling feel broken. This one does not consume movement, so a drag is
    // still a drag and only a tap toggles the tools.
    val surfaceModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                if (sheet != null) sheet = null else barVisible = !barVisible
            },
        )
    }

    Box(modifier.fillMaxSize().background(Ink).then(surfaceModifier)) {
        when {
            loadError != null -> WayOut(
                message = {
                    ReaderMessage(
                        title = shura("common.error"),
                        body = loadError,
                        actionLabel = onRetry?.let { shura("common.retry") },
                        onAction = onRetry ?: {},
                    )
                },
                onBack = onBack,
            )
            isLoading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(shura("common.loading"), color = Color(0xB3FFFFFF))
                // Loading that never finishes must still be leaveable. The toolbar
                // hides itself after a few seconds, so relying on its arrow means a
                // chapter that hangs is a chapter the reader cannot get out of.
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) {
                    Text(shura("common.back"), color = Color(0xFFFFFFFF))
                }
            }
            pages.isEmpty() -> WayOut(
                message = {
                    ReaderMessage(
                        title = shura("nav.reader"),
                        body = shura("reader.noPages"),
                        actionLabel = onOpenNextChapter?.let { shura("reader.next") },
                        onAction = { onOpenNextChapter?.invoke() },
                    )
                },
                onBack = onBack,
            )
            paged -> HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag("reader-pages"),
            ) { index ->
                ReaderPage(
                    uri = pages[index],
                    index = index,
                    pageCount = totalPages ?: pages.size,
                    translation = translationCache?.pagesFor(sourceId, chapterId)
                        ?.getOrNull(index),
                    translationPending = translationSettings.mode != TranslationMode.OFF &&
                        !translationEngineAvailable,
                    onTap = { barVisible = !barVisible },
                    onRetry = { onRetryPage?.invoke(index) },
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("reader-pages"),
                // Nothing is reserved. The tools float over the page and are
                // gone when not wanted, so the strip runs edge to edge; a
                // permanent inset here is the black band the reader had.
                contentPadding = PaddingValues(bottom = 0.dp),
            ) {
                items(count = pages.size, key = { index -> "$index:${pages[index]}" }) { index ->
                    ReaderPage(
                        uri = pages[index],
                        index = index,
                        pageCount = totalPages ?: pages.size,
                        translation = translationCache?.pagesFor(sourceId, chapterId)
                            ?.getOrNull(index),
                        translationPending = translationSettings.mode != TranslationMode.OFF &&
                            !translationEngineAvailable,
                        onRetry = { onRetryPage?.invoke(index) },
                    )
                }
            }
        }

        // The tools. Shown on a tap and gone again on the next, and they are a
        // sibling of the pages rather than something drawn over them, so
        // hiding them gives the page the whole screen back.
        AnimatedVisibility(
            visible = barVisible && sheet == null && pages.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBar(
                onOpenChapters = { sheet = ReaderSheet.CHAPTERS },
                onOpenSettings = { sheet = ReaderSheet.SETTINGS },
                onInteractionChanged = { interacting = it },
                cover = cover,
                chapterTitle = chapterTitle,
                currentPage = currentIndex + 1,
                pageCount = totalPages ?: pages.size,
                rtl = rtl,
                onBack = onBack,
                onProgress = seek,
                layoutMode = mode,
                onLayoutModeChange = onModeChange,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                translationSettings = translationSettings,
                onTranslationSettingsChange = onTranslationSettingsChange,
                engineAvailable = translationEngineAvailable,
            )
        }

        // A sheet replaces the tools rather than stacking on them. Two sets of
        // controls on one phone screen is how a reader stops being readable.
        sheet?.let { open ->
            ReaderSheetHost(
                sheet = open,
                chapters = chapters,
                currentChapterId = chapterId,
                totalPages = totalPages ?: pages.size,
                currentPage = currentIndex + 1,
                mode = mode,
                onModeChange = onModeChange,
                preferences = readerPreferences,
                onPreferencesChange = onReaderPreferencesChange,
                onOpenChapter = { chapter ->
                    sheet = null
                    onJumpToChapter?.invoke(chapter)
                },
                onDismiss = { sheet = null },
            )
        }
    }
}

/**
 * A message with a way out under it.
 *
 * Every state where there is no page to read gets one. A chapter that failed, hung
 * or turned out to be empty showed a single action - Retry, or Next - and when
 * neither existed the reader was stuck: the only exit was the arrow in a toolbar
 * that hides itself after three seconds. Leaving is never the thing a reader should
 * have to discover on their own.
 */
@Composable
private fun WayOut(message: @Composable () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        message()
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) {
            Text(shura("common.back"), color = Color(0xFFFFFFFF))
        }
    }
}

/** The panels the tools can open, one at a time. */
enum class ReaderSheet { CHAPTERS, SETTINGS }

/** How long the tools stay up when nobody is using them. */
private const val TOOLS_LINGER_MS = 3_500L

/** One page, its label, and any translated text beside it. */
@Composable
private fun ReaderPage(
    uri: Uri,
    index: Int,
    pageCount: Int,
    translation: String?,
    translationPending: Boolean,
    /** Tapping the page, as opposed to dragging it. */
    onTap: () -> Unit = {},
    /**
     * Re-fetches this one page.
     *
     * A request rather than an index so the page does not have to know who owns the
     * loader; the reader passes the retry the activity already wired.
     */
    onRetry: () -> Unit = {},
) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    // Zoom is per page and never inherited from the one before it: opening a
    // new page at 3x because the last one was zoomed reads as a bug.
    val transform = rememberTransformableState { zoom, _, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
    }
    Column(
        Modifier
            .fillMaxWidth()
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
        PageImage(
            uri = uri,
            index = index,
            pageCount = pageCount,
            scale = scale,
            transform = transform,
            onToggleZoom = { scale = if (scale > 1.01f) 1f else 2.5f },
            onTap = onTap,
            onRetry = onRetry,
        )
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
    /** Zoom belongs to the page, so the toggle is a request rather than a write. */
    onToggleZoom: () -> Unit,
    onTap: () -> Unit,
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
                    // The zoom gesture is attached ONLY while zoomed in.
                    //
                    // It used to be attached unconditionally, which meant every
                    // drag anywhere on a page was claimed as a zoom and the
                    // scroll container and the pager never saw it. That is why
                    // the strip would not scroll and a horizontal swipe would
                    // not turn the page: not a layout problem, a gesture one.
                    // At normal size the page hands every drag back, so
                    // scrolling, paging and the back edge-swipe all work.
                    .then(
                        if (scale > 1.01f) Modifier.transformable(transform) else Modifier
                    )
                    // A double tap zooms, and a second one puts it back. It
                    // only exists while there is something to zoom out of.
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onToggleZoom() },
                            onTap = { onTap() },
                        )
                    },
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

/**
 * Switches between the continuous strip and one page at a time.
 *
 * Offered inside the reader rather than in settings: the moment someone wants
 * to change it is the moment they are reading, and burying it in settings means
 * they simply do not.
 */
@Composable
private fun LayoutToggle(current: ReaderMode, onChange: (ReaderMode) -> Unit) {
    val other = if (current == ReaderMode.VERTICAL) ReaderMode.PAGED else ReaderMode.VERTICAL
    TextButton(onClick = { onChange(other) }) {
        Text(
            shura(if (current == ReaderMode.VERTICAL) "reader.mode.paged" else "reader.mode.vertical"),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBar(
    cover: String,
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
    /** The layout the reader is currently in, so the control can offer the other. */
    layoutMode: ReaderMode,
    onLayoutModeChange: (ReaderMode) -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Lets the tools know a finger is on them, so they do not fade away. */
    onInteractionChanged: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                // While a finger is down on the tools they must not vanish
                // under it. Released again on lift, so they can fade once
                // reading has resumed - a flag that is only ever set would
                // pin them open for good.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onInteractionChanged(true)
                        waitForUpOrCancellation()
                        onInteractionChanged(false)
                    }
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The in-reader arrow. Independent of the system back button:
                // both must work, and neither substitutes for the other.
                TextButton(onClick = onBack) { Text("←") }
                Text(
                    chapterTitle,
                    Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // The chapter list, reachable without leaving the chapter.
                TextButton(onClick = onOpenChapters) { Text(shura("reader.chapters")) }
                TextButton(onClick = onOpenSettings) { Text("⚙") }
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
                Spacer(Modifier.width(10.dp))
                LayoutToggle(layoutMode, onLayoutModeChange)
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
