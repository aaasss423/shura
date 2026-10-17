package app.shura.manga.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.ReaderCounter
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
    /** Brightness, preload, screen-awake and delete-after-reading, kept between sessions. */
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
        SectionHeader(label = shura("reader.chapters"), modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(chapters, key = { index, c -> "${c.id}:$index" }) { index, chapter ->
                val isCurrent = chapter.id == currentChapterId
                // The app's row, not a bare line of text: a chapter list inside a
                // sheet is still a chapter list, and it should not be the one place
                // in the app where a row is drawn differently.
                AppRow(
                    title = chapter.name,
                    meta = if (isCurrent) shura("reader.current") else null,
                    modifier = Modifier.fillMaxWidth(),
                    titleLines = 2,
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    leading = { UnreadMark(current = isCurrent) },
                    onClick = { onOpen(chapter) },
                )
            }
        }
    }
}

/** The mark on a chapter row: this one, or not. */
@Composable
private fun UnreadMark(current: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .then(
                if (current) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier,
            ),
    )
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

        SectionHeader(label = shura("reader.preload"))
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

        SectionHeader(label = shura("reader.parallel"))
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

        // The two yes-or-no settings, as the app's rows: mark, title, summary,
        // switch. They used to be a line of text with a switch pushed against the
        // edge, which is the one place in the app where a setting has no shape.
        AppRow(
            title = shura("reader.keepScreenOn"),
            modifier = Modifier.fillMaxWidth(),
            leading = { RowMark(Icons.Default.Star, contentDescription = null) },
            trailing = {
                Switch(
                    checked = preferences.keepScreenOn,
                    onCheckedChange = { onPreferencesChange(preferences.copy(keepScreenOn = it)) },
                )
            },
            onClick = {
                onPreferencesChange(preferences.copy(keepScreenOn = !preferences.keepScreenOn))
            },
        )
        Spacer(Modifier.height(8.dp))

        // Mihon's advanced setting, in the same place Mihon keeps it, and off
        // until it is asked for: it removes a chapter the reader has finished,
        // which is worth a switch they can see rather than a rule that surprises
        // them on chapter two.
        AppRow(
            title = shura("reader.deleteAfterReading"),
            meta = shura("reader.deleteAfterReadingHelp"),
            metaLines = 3,
            modifier = Modifier.fillMaxWidth(),
            leading = { RowMark(Icons.Default.Delete, contentDescription = null) },
            trailing = {
                Switch(
                    checked = preferences.deleteChapterAfterReading,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(deleteChapterAfterReading = it))
                    },
                )
            },
            onClick = {
                onPreferencesChange(
                    preferences.copy(deleteChapterAfterReading = !preferences.deleteChapterAfterReading),
                )
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        SummaryStrip(
            // The same two numbers the bar and every page label use, resolved
            // once by [ReaderCounter]. It used to re-clamp here with a second
            // rule, which is how a page above the end of the chapter reached a
            // label.
            shura(
                "reader.pages",
                ReaderCounter.page(currentPage - 1, totalPages),
                ReaderCounter.total(totalPages, totalPages),
            ),
        )
    }
}

/** Slider position that means "follow the system", outside the real range. */
private const val SYSTEM_SLIDER_VALUE = 0.01f
private const val MAX_PRELOAD = 10
private const val MAX_PARALLEL = 8