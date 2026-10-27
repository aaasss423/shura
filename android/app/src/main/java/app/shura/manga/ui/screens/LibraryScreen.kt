package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.StoredChapter
import app.shura.manga.directorySize
import app.shura.manga.formatBytes
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing

/** How the library is ordered. */
enum class LibrarySort { RECENT, TITLE, CHAPTERS }

sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Favorites : LibraryFilter
    data class Category(val name: String) : LibraryFilter
}

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
    val favorite: Boolean = false,
    val category: String? = null,
) {
    /** Identity used by selection and by the followed-work check. */
    val key: String get() = "${sourceId}|${mangaId}"
    val chapterCount: Int get() = chapters.size
    /** How much space the whole work occupies, measured not recorded. */
    val sizeOnDisk: Long get() = chapters.sumOf { directorySize(it.dir) }

    /**
     * The chapter the library would resume from, if it resumed at all.
     *
     * Not what a tap opens. It is here for the one question it can still answer
     * - "is there anything of this work on the device?" - and the tap asks that
     * through [chapterCount] instead. A tap used to hand this to the reader as
     * the chapter to read, which is how a reader who tapped a series to see
     * what they had saved ended up inside one chapter of it, with no list and
     * no way back to the work.
     */
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
                // The work's own name, not a chapter's. The store records it
                // beside every chapter, and a followed work has it too; only a
                // chapter saved before either did is named after itself, which is
                // a grid of covers all reading "Chapter 2".
                title = chapters.firstOrNull { it.mangaTitle.isNotBlank() }?.mangaTitle
                    ?: followed.firstOrNull {
                        it.sourceId == first.sourceId && it.mangaId == first.mangaId
                    }?.title
                    ?: first.title,
                coverUrl = chapters.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                chapters = chapters,
                lastRead = byProgress["${first.sourceId}|${first.mangaId}"],
                followed = followed.any { it.key == "${first.sourceId}|${first.mangaId}" },
                favorite = followed.firstOrNull {
                    it.sourceId == first.sourceId && it.mangaId == first.mangaId
                }?.favorite ?: false,
                category = followed.firstOrNull {
                    it.sourceId == first.sourceId && it.mangaId == first.mangaId
                }?.category,
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
                favorite = it.favorite,
                category = it.category,
            )
        }
    return downloaded + onlyFollowed
}

/** Filters and orders, with the search applied to the title. */
fun libraryRows(
    works: List<LibraryWork>,
    query: String,
    sort: LibrarySort,
    filter: LibraryFilter = LibraryFilter.All,
): List<LibraryWork> {
    val needle = query.trim()
    val filtered = works
        .filter { work ->
            when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Favorites -> work.favorite
                is LibraryFilter.Category -> work.category == filter.name
            }
        }
        .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
    return when (sort) {
        LibrarySort.RECENT -> filtered.sortedByDescending {
            it.lastRead?.updatedAt ?: it.chapters.maxOfOrNull { chapter -> chapter.dir.lastModified() } ?: 0L
        }
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.CHAPTERS -> filtered.sortedByDescending { it.chapterCount }
    }
}

/**
 * The library: a dense grid of covers, one control row, three designed states.
 *
 * Covers are what a reader recognises a series by, so the grid is the point, and
 * it is sized to the screen rather than pinned at two columns. Two columns on a
 * phone means four enormous cards with the reader's name written twice on each
 * of them; the count is derived from the width instead, so the same screen fits
 * three covers a row on a handset and more on anything wider, and a single work
 * no longer sits stranded in the middle of an empty screen.
 *
 * The title sits *on* the cover at its bottom, over a scrim, with the chapter
 * count at the top-end corner opposite it. A caption under a cover pushes the
 * rows apart until half the screen is gutter.
 */
@Composable
fun LibraryScreen(
    stored: List<StoredChapter>,
    /** Works the reader follows, so the add button is visible where it happened. */
    followed: List<app.shura.manga.LibraryWork> = emptyList(),
    history: List<ReadingProgress>,
    onDelete: (StoredChapter) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the download queue. Reached from here rather than the bottom bar. */
    onOpenDownloads: (() -> Unit)? = null,
    /**
     * Deletes a whole work's downloads, removes it from the library, or both -
     * each asked for separately, through a confirmation.
     *
     * One callback rather than three: the three are three answers to one question
     * ("what should happen to this?"), so they belong to one control, and a
     * screen that offered them as three separate rows would have three ways to
     * delete something by accident.
     */
    onManageWork: ((works: List<LibraryWork>, action: app.shura.manga.WorkCleanupAction) -> Unit)? = null,
    /** What the last bulk cleanup did, said above the grid. */
    cleanupNotice: String? = null,
    /** Marks a work's chapters read or unread in one go. */
    onMarkRead: ((LibraryWork, Boolean) -> Unit)? = null,
    /**
     * Opens a work.
     *
     * Always the work's own chapter list, whether it has chapters saved or not:
     * the library is a shelf of books and tapping one asks to see that book,
     * which is a list of its chapters and not one chapter of it. Opening the
     * first saved chapter instead meant the reader could not get from the shelf
     * to the chapter they wanted at all - the only way on was through whichever
     * chapter happened to be at the top.
     */
    onOpenWork: ((LibraryWork) -> Unit)? = null,
    /** Where a chapter gets saved, for the empty state to send the reader to. */
    onBrowse: (() -> Unit)? = null,
    onToggleFavorite: ((LibraryWork, Boolean) -> Unit)? = null,
    onAssignCategory: ((List<LibraryWork>, String?) -> Unit)? = null,
) {
    val works = remember(stored, history, followed) { buildLibrary(stored, history, followed) }
    // Multi-select, the way a shelf is picked over: a long press starts it, the
    // action bar replaces the search row while it is on, and back leaves it.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // The work, or works, whose downloads are about to be managed. Held here
    // rather than acted on where the confirmation appears, so backing out of the
    // dialog really does change nothing.
    var managing by remember { mutableStateOf<List<LibraryWork>?>(null) }
    var organizing by remember { mutableStateOf<List<LibraryWork>?>(null) }
    val selecting = selected.isNotEmpty()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var filter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }

    managing?.let { chosen ->
        WorkCleanupDialog(
            works = chosen,
            onConfirm = { action ->
                onManageWork?.invoke(chosen, action)
                selected = emptySet()
                managing = null
            },
            onDismiss = { managing = null },
        )
    }
    organizing?.let { chosen ->
        CategoryAssignmentDialog(
            categories = works.mapNotNull { it.category }.distinct().sorted(),
            onAssign = { category ->
                onAssignCategory?.invoke(chosen, category)
                selected = emptySet()
                organizing = null
            },
            onDismiss = { organizing = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onOrganize = onAssignCategory?.let {
                    { organizing = works.filter { work -> work.key in selected } }
                },
                onMarkRead = onMarkRead?.let { mark ->
                    {
                        works.filter { it.key in selected }.forEach { mark(it, true) }
                        selected = emptySet()
                    }
                },
                // One confirmation for the whole selection: a selection of five
                // cannot ask three questions five times, and the three acts are
                // the same three acts.
                onDelete = onManageWork?.let {
                    { managing = works.filter { work -> work.key in selected } }
                },
            )
        } else {
            LibraryControls(
                query = query,
                onQuery = { query = it },
                sort = sort,
                onSort = { sort = it },
                onOpenDownloads = onOpenDownloads,
            )
        }

        cleanupNotice?.takeIf { it.isNotBlank() }?.let {
            SummaryStrip(
                it,
                Modifier.padding(horizontal = ShuraSpacing.screen + 4.dp, vertical = 4.dp),
            )
        }

        if (!selecting && works.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ShuraSpacing.screen, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == LibraryFilter.All,
                    onClick = { filter = LibraryFilter.All },
                    label = { Text(shura("library.filter.all")) },
                )
                FilterChip(
                    selected = filter == LibraryFilter.Favorites,
                    onClick = { filter = LibraryFilter.Favorites },
                    label = { Text(shura("library.filter.favorites")) },
                )
                works.mapNotNull { it.category }.distinct().sorted().forEach { category ->
                    val categoryFilter = LibraryFilter.Category(category)
                    FilterChip(
                        selected = filter == categoryFilter,
                        onClick = { filter = categoryFilter },
                        label = { Text(category, maxLines = 1) },
                    )
                }
            }
        }

        val rows = remember(works, query, sort, filter) { libraryRows(works, query, sort, filter) }

        when {
            // Three states, three different sentences. "Nothing saved" says where
            // saving happens; "nothing matches" says the search is what is empty,
            // not the library. Both centred, because a message pinned to the start
            // edge of a wide grid reads as a label somebody forgot to delete.
            works.isEmpty() -> EmptyState(
                title = shura("library.empty"),
                body = shura("library.emptyBody"),
                icon = ShuraIcons.Books,
                actionLabel = onBrowse?.let { shura("home.emptyAction") },
                onAction = onBrowse,
                modifier = Modifier.weight(1f),
            )

            rows.isEmpty() -> EmptyState(
                title = shura(if (filter == LibraryFilter.All) "library.noMatches" else "library.filter.empty"),
                body = shura(if (filter == LibraryFilter.All) "library.noMatchesBody" else "library.filter.emptyBody"),
                icon = ShuraIcons.Books,
                actionLabel = shura("common.clear"),
                onAction = { query = "" },
                modifier = Modifier.weight(1f),
            )

            else -> LazyVerticalGrid(
                // Sized to the width in front of it, not counted on a phone: three
                // covers a row on a handset, more on a tablet, and the gutters stay
                // even either way because both come from the same two numbers.
                columns = GridCells.Adaptive(minSize = TILE_MIN),
                contentPadding = PaddingValues(
                    start = ShuraSpacing.screen,
                    end = ShuraSpacing.screen,
                    top = 6.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(ShuraSpacing.grid),
                verticalArrangement = Arrangement.spacedBy(ShuraSpacing.grid),
            ) {
                items(rows, key = { it.key }) { work ->
                    CoverTile(
                        coverUrl = work.coverUrl,
                        title = work.title,
                        badge = work.chapterCount,
                        progress = work.lastRead?.let { readFraction(it) },
                        selected = work.key in selected,
                        selectable = !selecting,
                        // The work, not a chapter of it: see [onOpenWork].
                        onClick = { onOpenWork?.invoke(work) },
                        onLongClick = {
                            selected =
                                if (work.key in selected) selected - work.key else selected + work.key
                        },
                        meta = detailJoin(
                            shura("library.chapters", work.chapterCount),
                            work.lastRead?.let {
                                shura("library.progress", it.page + 1, it.pageCount.coerceAtLeast(1))
                            }.orEmpty(),
                            work.category.orEmpty(),
                        ),
                        favorite = work.favorite,
                        onFavoriteClick = if (selecting) null else onToggleFavorite?.let { toggle ->
                            { toggle(work, !work.favorite) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The row above the grid.
 *
 * One line, one line's worth of decisions: a slim field that takes the width that
 * is left, and the two controls a reader actually reaches for - the order, and
 * the queue - on its end. It used to be a full-width outlined box with a word
 * button for downloads beside it, which is most of a phone screen before the
 * first cover.
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
        Modifier
            .fillMaxWidth()
            .padding(
                start = ShuraSpacing.screen,
                end = 4.dp,
                top = 6.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = shura("library.search"),
            modifier = Modifier.weight(1f),
            trailing = onOpenDownloads?.let { open ->
                {
                    IconButton(onClick = open, modifier = Modifier.size(44.dp)) {
                        Icon(
                            ShuraIcons.Download,
                            contentDescription = shura("downloads.title"),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = shura("library.sort"),
                )
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
    }
}

/**
 * What a running selection can do to everything in it.
 *
 * One row that replaces the search and the sort while it is up, and says how many
 * things are selected - the count used to be a bare number beside a close button,
 * which is a number nobody can act on.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onMarkRead: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onOrganize: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = shura("common.cancel"))
        }
        Text(
            shura("library.selected", count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onMarkRead?.let {
            androidx.compose.material3.TextButton(onClick = it) {
                Text(shura("chapters.read"), maxLines = 1)
            }
        }
        onOrganize?.let {
            TextButton(onClick = it) {
                Text(shura("library.category"), maxLines = 1)
            }
        }
        onDelete?.let {
            androidx.compose.material3.TextButton(onClick = it) {
                Text(shura("library.manage"), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CategoryAssignmentDialog(
    categories: List<String>,
    onAssign: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newCategory by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(shura("library.category.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAssign(null) }) {
                    Text(shura("library.category.none"))
                }
                categories.forEach { category ->
                    TextButton(onClick = { onAssign(category) }) {
                        Text(category)
                    }
                }
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text(shura("library.category.new")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAssign(newCategory.trim()) },
                enabled = newCategory.isNotBlank(),
            ) {
                Text(shura("library.category.create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(shura("common.cancel")) }
        },
    )
}

/**
 * The smallest a cover tile may be before the grid grows another column.
 *
 * Chosen so a handset fits three across and a title still reads on the artwork:
 * below about 100dp the overlaid title is three characters a line and the grid
 * stops being a shelf.
 */
private val TILE_MIN = 100.dp

/**
 * "What should happen to this?" - three answers, asked before any of them.
 *
 * The three are the ones Mihon keeps apart, because they are three different
 * intentions: the pages go, the shelf entry goes, or both. The screen has to be
 * able to offer them independently, so the dialog offers all three and confirms
 * whichever one was pressed.
 *
 * Each row says what it will do, in the words the reader would use - including
 * how many chapters and how much space - because "delete" on its own does not
 * tell anyone whether their library is about to lose an entry or a hundred
 * megabytes. A confirmation that cannot be read is not a confirmation.
 *
 * Nothing is deleted here. The dialog reports an intent; the work is planned and
 * performed by [app.shura.manga.planWorkCleanup] and
 * [app.shura.manga.applyWorkCleanup] off the main thread, which is also what
 * makes it testable without a device.
 */
@Composable
internal fun WorkCleanupDialog(
    works: List<LibraryWork>,
    onConfirm: (app.shura.manga.WorkCleanupAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val chapters = works.sumOf { it.chapterCount }
    val bytes = works.sumOf { it.sizeOnDisk }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (works.size == 1) works.first().title else shura("library.manageMany", works.size),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (chapters > 0) shura("library.manageSize", chapters, formatBytes(bytes))
                    else shura("library.manageEmpty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppRow(
                    title = shura("library.cleanupDelete"),
                    meta = shura("library.cleanupDeleteHelp"),
                    metaLines = 2,
                    onClick = { onConfirm(app.shura.manga.WorkCleanupAction.DELETE_DOWNLOADS) },
                    leading = { RowMark(ShuraIcons.Download, contentDescription = null) },
                )
                AppRow(
                    title = shura("library.cleanupRemove"),
                    meta = shura("library.cleanupRemoveHelp"),
                    metaLines = 2,
                    onClick = { onConfirm(app.shura.manga.WorkCleanupAction.REMOVE_FROM_LIBRARY) },
                    leading = { RowMark(ShuraIcons.Books, contentDescription = null) },
                )
                AppRow(
                    title = shura("library.cleanupBoth"),
                    meta = shura("library.cleanupBothHelp"),
                    metaLines = 2,
                    onClick = { onConfirm(app.shura.manga.WorkCleanupAction.DELETE_EVERYTHING) },
                    leading = { RowMark(ShuraIcons.Download, contentDescription = null) },
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(shura("common.cancel"), maxLines = 1)
            }
        },
    )
}