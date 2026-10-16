package app.shura.manga.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.ReaderMode
import app.shura.manga.reader.ReaderPreferences
import app.shura.manga.ui.shura
import app.shura.source.Chapter

/**
 * The panels the reader's tools open.
 *
 * A sheet, not an overlay and not a floating control: the chapter list and the
 * reading settings are part of reading, they sit inside the reader's own
 * surface, and they take the tools' place rather than stacking on top of them.
 * Two sets of controls on one phone screen is how a reader stops being readable.
 *
 * Opening the chapter list scrolls to where the reader is. Arriving at a list
 * of three hundred chapters scrolled to the top is disorienting in a way that
 * is easy to miss until it happens to you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSheetHost(
    sheet: ReaderSheet,
    chapters: List<Chapter>,
    currentChapterId: String,
    totalPages: Int,
    /** The page on screen, so the counter is the reader's position and not a guess. */
    currentPage: Int,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    /** Brightness, preload and screen-awake, kept between sessions. */
    preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    onOpenChapter: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (sheet) {
            ReaderSheet.CHAPTERS -> ChapterPickerSheet(
                chapters = chapters,
                currentChapterId = currentChapterId,
                onOpen = onOpenChapter,
            )
            ReaderSheet.SETTINGS -> ReadingSettingsSheet(
                mode = mode,
                onModeChange = onModeChange,
                totalPages = totalPages,
                currentPage = currentPage,
                preferences = preferences,
                onPreferencesChange = onPreferencesChange,
            )
        }
    }
}

@Composable
private fun ChapterPickerSheet(
    chapters: List<Chapter>,
    currentChapterId: String,
    onOpen: (Chapter) -> Unit,
) {
    val listState = rememberLazyListState()
    val current = chapters.indexOfFirst { it.id == currentChapterId }

    LaunchedEffect(current, chapters.size) {
        // Land on the chapter being read, not on the top of a long list.
        if (current > 0) listState.scrollToItem(current)
    }

    if (chapters.isEmpty()) {
        Text(
            shura("reader.noChapterList"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        Text(
            shura("reader.chapters"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(chapters, key = { index, c -> "${c.id}:$index" }) { index, chapter ->
                val isCurrent = chapter.id == currentChapterId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(chapter) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapter.name,
                        Modifier.weight(1f),
                        maxLines = 2,
                        style = if (isCurrent) MaterialTheme.typography.titleSmall
                        else MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isCurrent) {
                        Text(
                            shura("reader.current"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun ReadingSettingsSheet(
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    totalPages: Int,
    currentPage: Int,
    preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(shura("reader.settings"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        Text(shura("reader.layout"), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ReaderMode.VERTICAL,
                onClick = { onModeChange(ReaderMode.VERTICAL) },
                label = { Text(shura("reader.mode.vertical")) },
            )
            FilterChip(
                selected = mode == ReaderMode.PAGED,
                onClick = { onModeChange(ReaderMode.PAGED) },
                label = { Text(shura("reader.mode.paged")) },
            )
        }
        Spacer(Modifier.height(8.dp))
        // Says what each one is for, because "strip" and "paged" are not
        // self-explanatory and a reader should not have to try both.
        Text(
            shura(if (mode == ReaderMode.VERTICAL) "reader.verticalHelp" else "reader.pagedHelp"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(shura("reader.brightness"), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = if (preferences.overridesBrightness) preferences.brightness else SYSTEM_SLIDER_VALUE,
            onValueChange = { value ->
                onPreferencesChange(
                    preferences.copy(
                        brightness = if (value == SYSTEM_SLIDER_VALUE) 0f else ReaderPreferences.brightnessOf(value),
                    ),
                )
            },
            valueRange = SYSTEM_SLIDER_VALUE..1f,
            steps = 0,
        )
        Text(
            // "System" is an explicit state, not a silent zero: a reader who cannot
            // find the brightness control needs to know the app is not fighting them.
            if (preferences.overridesBrightness) shura("reader.brightness")
            else shura("reader.brightnessSystem"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Text(shura("reader.preload"), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = preferences.preload.toFloat(),
            onValueChange = { onPreferencesChange(preferences.copy(preloadPages = it.toInt())) },
            valueRange = 0f..MAX_PRELOAD.toFloat(),
            steps = MAX_PRELOAD - 1,
        )
        Text(
            shura("reader.preloadPages", preferences.preload),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Text(shura("reader.parallel"), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = preferences.parallelism.toFloat(),
            onValueChange = { onPreferencesChange(preferences.copy(parallelDownloads = it.toInt())) },
            valueRange = 1f..MAX_PARALLEL.toFloat(),
            steps = MAX_PARALLEL - 1,
        )
        Text(
            shura("reader.preloadPages", preferences.parallelism),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shura("reader.keepScreenOn"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = preferences.keepScreenOn,
                onCheckedChange = { onPreferencesChange(preferences.copy(keepScreenOn = it)) },
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            shura("reader.pages", currentPage.coerceAtLeast(1), totalPages.coerceAtLeast(1)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slider position that means "follow the system", outside the real range. */
private const val SYSTEM_SLIDER_VALUE = 0.01f
private const val MAX_PRELOAD = 10
private const val MAX_PARALLEL = 8