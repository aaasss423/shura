package app.shura.manga.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.StoredChapter
import app.shura.manga.directorySize
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.image.CoverImage
import app.shura.manga.ui.shura

/** How the library is ordered. */
enum class LibrarySort { RECENT, TITLE, CHAPTERS }

/**
 * One work in the library.
 *
 * A library of *works*, not of saved chapters. A reader who has chapters of
 * forty series saved is not looking for a list of one hundred and thirty
 * chapter rows; they are looking for the series, with the cover they
 * recognise, and how far in they are.
 */
data class LibraryWork(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val coverUrl: String,
    val chapters: List<StoredChapter>,
    /** Furthest page read across the work's chapters, for the progress badge. */
    val lastRead: ReadingProgress?,
    /** True when the reader chose this work before downloading anything from it. */
    val followed: Boolean = false,
) {
    /** Identity used by selection and by the followed-work check. */
    val key: String get() = "${sourceId}|${mangaId}"
    val chapterCount: Int get() = chapters.size
    /** How much space the whole work occupies, measured not recorded. */
    val sizeOnDisk: Long get() = chapters.sumOf { directorySize(it.dir) }

    /** The chapter to resume: the first saved, which is the library's order. */
    val resumeChapter: StoredChapter? get() = chapters.firstOrNull()
}

/**
 * Groups saved chapters into works.
 *
 * Keyed by (source, manga) rather than title: two sources can carry works with
 * the same name, and merging them would put one series' chapters under another
 * series' cover.
 */
fun buildLibrary(
    stored: List<StoredChapter>,
    history: List<ReadingProgress>,
    /**
     * Works the reader follows, which may have no chapters on the device yet.
     *
     * Without these the library listed only downloaded chapters, so the button that
     * adds a work did something that appeared to do nothing - the exact "dead
     * button" this replaced.
     */
    followed: List<app.shura.manga.LibraryWork> = emptyList(),
): List<LibraryWork> {
    val byProgress = history.associateBy { "${it.sourceId}|${it.mangaId}" }
    val downloaded = stored
        .groupBy { "${it.sourceId}|${it.mangaId}" }
        .map { (_, chapters) ->
            val first = chapters.first()
            LibraryWork(
                sourceId = first.sourceId,
                mangaId = first.mangaId,
                title = first.title,
                coverUrl = chapters.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                chapters = chapters,
                lastRead = byProgress["${first.sourceId}|${first.mangaId}"],
                followed = followed.any { it.key == "${first.sourceId}|${first.mangaId}" },
            )
        }
    val keys = downloaded.mapTo(mutableSetOf()) { "${it.sourceId}|${it.mangaId}" }
    val onlyFollowed = followed
        .filter { "${it.sourceId}|${it.mangaId}" !in keys }
        .map {
            LibraryWork(
                sourceId = it.sourceId,
                mangaId = it.mangaId,
                title = it.title,
                coverUrl = it.coverUrl,
                chapters = emptyList(),
                lastRead = byProgress["${it.sourceId}|${it.mangaId}"],
                followed = true,
            )
        }
    return downloaded + onlyFollowed
}

/** Filters and orders, with the search applied to the title. */
fun libraryRows(
    works: List<LibraryWork>,
    query: String,
    sort: LibrarySort,
): List<LibraryWork> {
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) works
    else works.filter { it.title.contains(needle, ignoreCase = true) }
    return when (sort) {
        LibrarySort.RECENT -> filtered.sortedByDescending {
            it.lastRead?.updatedAt ?: it.chapters.maxOfOrNull { chapter -> chapter.dir.lastModified() } ?: 0L
        }
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.CHAPTERS -> filtered.sortedByDescending { it.chapterCount }
    }
}

/**
 * The library, as a grid of covers.
 *
 * Covers are what a reader recognises a series by, so the grid is the point;
 * the title sits under the cover in its own space rather than over it, and a
 * long title is clipped instead of growing the tile until the row stops
 * lining up.
 */
@Composable
fun LibraryScreen(
    stored: List<StoredChapter>,
    /** Works the reader follows, so the add button is visible where it happened. */
    followed: List<app.shura.manga.LibraryWork> = emptyList(),
    history: List<ReadingProgress>,
    onRead: (StoredChapter) -> Unit,
    onDelete: (StoredChapter) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the download queue. Reached from here rather than the bottom bar. */
    onOpenDownloads: (() -> Unit)? = null,
    /** Deletes a whole work: its record, and the chapters saved from it. */
    onDeleteWork: ((LibraryWork) -> Unit)? = null,
    /** Marks a work's chapters read or unread in one go. */
    onMarkRead: ((LibraryWork, Boolean) -> Unit)? = null,
    /**
     * Opens a work that has no chapter saved yet.
     *
     * A followed work arrives here with an empty chapter list, so tapping it cannot
     * mean "read the first saved chapter" - there is none. Forcing that with `!!`
     * would have taken the process down on the first tap of a new library.
     */
    onOpenWork: ((LibraryWork) -> Unit)? = null,
) {
    val works = remember(stored, history, followed) { buildLibrary(stored, history, followed) }
    // Multi-select, the way Mihon does it: a long press starts it, the action bar
    // replaces the display-mode switch while it is on, and back leaves it.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    Column(modifier.fillMaxSize()) {
        if (selecting) {
            // The bar is the contract: whatever is selected, these act on all of it.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selected = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = shura("common.cancel"))
                }
                Text(
                    "${selected.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                onMarkRead?.let { mark ->
                    TextButton(onClick = {
                        works.filter { it.key in selected }.forEach { mark(it, true) }
                        selected = emptySet()
                    }) { Text(shura("chapters.read")) }
                }
                onDeleteWork?.let { delete ->
                    TextButton(onClick = {
                        works.filter { it.key in selected }.forEach(delete)
                        selected = emptySet()
                    }) { Text(shura("library.delete")) }
                }
            }
        } else {
            LibraryControls(
                query = query,
                onQuery = { query = it },
                sort = sort,
                onSort = { sort = it },
                onOpenDownloads = onOpenDownloads,
            )
        }
        val rows = remember(works, query, sort) { libraryRows(works, query, sort) }

        if (works.isEmpty()) {
            StatePanel(shura("library.empty"), "")
            return@Column
        }

        if (rows.isEmpty()) {
            // Distinct from "no saved chapters": the library has books, the
            // search found none of them. The empty state that says so is the
            // one that keeps a reader from thinking their library emptied.
            Text(
                shura("library.noMatches"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(rows, key = { it.key }) { work ->
                LibraryTile(
                    work = work,
                    selected = work.key in selected,
                    selecting = selecting,
                    onRead = {
                        val resume = work.resumeChapter
                        if (resume != null) onRead(resume) else onOpenWork?.invoke(work)
                    },
                    onLongPress = {
                        selected = if (work.key in selected) selected - work.key else selected + work.key
                    },
                )
            }
        }
    }
}

/**
 * The row above the grid.
 *
 * One compact line, the way a shelf is searched: a slim field that takes the
 * width that is left, and the two controls a reader actually reaches for - the
 * order and the download queue - on the end. The field used to be a full-width
 * outlined box with three chips under it, which is most of a phone screen before
 * the first cover.
 */
@Composable
private fun LibraryControls(
    query: String,
    onQuery: (String) -> Unit,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    onOpenDownloads: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.height(42.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { field ->
                            if (query.isEmpty()) {
                                Text(
                                    shura("library.search"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        },
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = shura("common.cancel"),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = shura("library.sort"))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(shura("library.sort.${option.name.lowercase()}")) },
                        onClick = {
                            onSort(option)
                            menuOpen = false
                        },
                        trailingIcon = if (option == sort) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        onOpenDownloads?.let { open ->
            TextButton(onClick = open) {
                Text(shura("downloads.title"), maxLines = 1)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    work: LibraryWork,
    selected: Boolean,
    selecting: Boolean,
    onRead: () -> Unit,
    onLongPress: () -> Unit,
) {
    // No card behind the tile: Mihon's library is covers on the background, and
    // a panel behind every tile boxed the whole grid in. The shape is the cover's.
    Column(
        Modifier.combinedClickable(
            // A long press starts a selection; a tap acts. While a selection is
            // running, every tap toggles instead of opening, which is what makes
            // a batch of five possible without a mode the reader can forget to leave.
            onClick = { if (!selecting) onRead() else onLongPress() },
            onLongClick = onLongPress,
        ),
    ) {
        val shape = MaterialTheme.shapes.small
        Box(
            Modifier
                .fillMaxWidth()
                // A fixed ratio, so every tile is the same shape and the row lines
                // up. Letting the cover's own ratio decide made a grid with ragged
                // rows and a cover that squashed or cropped.
                .aspectRatio(2f / 3f)
                .clip(shape)
                // Selection is marked on the cover, which is the thing being picked.
                .then(
                    if (selected) Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        shape,
                    ) else Modifier,
                ),
        ) {
            CoverImage(
                source = work.coverUrl,
                modifier = Modifier.fillMaxSize(),
                retryLabel = shura("common.retry"),
            )
            work.lastRead?.let { progress ->
                // How far in, as a hairline under the cover: the number is in the
                // meta line for anyone who wants it, and nobody has to read one.
                val fraction = if (progress.pageCount <= 1) 0f
                else (progress.page + 1).toFloat() / progress.pageCount
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Black.copy(alpha = 0.35f),
                )
            }
            // The count sits on the cover, the way a shelf labels a spine: it is the
            // one number a reader scans a grid for, and under the cover it pushed the
            // titles out of alignment.
            if (work.chapterCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            Color.Black.copy(alpha = 0.72f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${work.chapterCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            work.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        // One meta line: how much of it is here, and how far in - the two numbers
        // a reader scans a shelf for, on one line rather than three. The progress
        // is a bare ratio because the spelled-out form does not fit a tile, and
        // the bar above it already carries the same information as a shape.
        Text(
            buildString {
                append(shura("library.chapters", work.chapterCount))
                work.lastRead?.let { progress ->
                    append(" · ")
                    append(
                        shura(
                            "library.progress",
                            progress.page + 1,
                            progress.pageCount.coerceAtLeast(1),
                        ),
                    )
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
