package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.StoredChapter
import app.shura.manga.directorySize
import app.shura.manga.formatBytes
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
) {
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
): List<LibraryWork> {
    val byProgress = history.associateBy { "${it.sourceId}|${it.mangaId}" }
    return stored
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
            )
        }
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
    history: List<ReadingProgress>,
    onRead: (StoredChapter) -> Unit,
    onDelete: (StoredChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val works = remember(stored, history) { buildLibrary(stored, history) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    val rows = remember(works, query, sort) { libraryRows(works, query, sort) }

    if (works.isEmpty()) {
        StatePanel(shura("library.empty"), "")
        return
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(shura("library.search")) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibrarySort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { sort = option },
                    label = { Text(shura("library.sort.${option.name.lowercase()}")) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

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
            columns = GridCells.Adaptive(minSize = 116.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(rows, key = { it.sourceId + "|" + it.mangaId }) { work ->
                LibraryTile(work = work, onRead = { onRead(work.resumeChapter!!) })
            }
        }
    }
}

@Composable
private fun LibraryTile(work: LibraryWork, onRead: () -> Unit) {
    Card(
        onClick = onRead,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column {
            Box_cover(work)
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(
                    work.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    shura("library.chapters", work.chapterCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                work.lastRead?.let { progress ->
                    Text(
                        shura("reader.pages", progress.page + 1, progress.pageCount.coerceAtLeast(1)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Box_cover(work: LibraryWork) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            // A fixed ratio, so every tile is the same shape and the row lines
            // up. Letting the cover's own ratio decide made a grid with ragged
            // rows and a cover that squashed or cropped.
            .aspectRatio(2f / 3f),
    ) {
        CoverImage(
            source = work.coverUrl,
            modifier = Modifier.fillMaxSize(),
            retryLabel = shura("common.retry"),
        )
    }
}