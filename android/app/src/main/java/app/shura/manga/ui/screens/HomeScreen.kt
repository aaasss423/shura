package app.shura.manga.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing
import app.shura.source.ShuraSource

/**
 * Home: what to read next, and what was read before that.
 *
 * A feed, in two sections, the way a shelf is: the chapters that are still open
 * first, as a strip of cards you can drag across, then everything else as rows.
 *
 * It used to be a wordmark the top bar already said, one oversized card holding
 * a single chapter, a card whose title said "Library" and whose only content said
 * "Chapter library", and then the same work's title again in a row below. Four
 * headings for two pieces of information. What is here now is the information:
 * a work, the chapter, how far into it you are - once each, never twice.
 *
 * An entry appears in exactly one section. A chapter with pages left in it is in
 * the strip; a finished one is in the list, and the strip never repeats it, so
 * scrolling home does not show the same cover four times.
 */
@Composable
fun HomeScreen(
    progress: List<ReadingProgress>,
    onOpenEntry: (ReadingProgress) -> Unit,
    onBrowse: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Newest first, and split once: what is still open, and what is not. The split
    // is by position in that list, so nothing can be in both sections and the two
    // can never disagree about what belongs where.
    val (unfinished, finished) = remember(progress) {
        val newest = progress.sortedByDescending { it.updatedAt }
        val open = newest.filter { it.pageCount > 1 && it.page < it.pageCount - 1 }
            .take(CONTINUE_LIMIT)
        val openKeys = open.mapTo(mutableSetOf()) { "${it.sourceId}|${it.chapterId}" }
        open to newest
            .filterNot { "${it.sourceId}|${it.chapterId}" in openKeys }
            .take(RECENT_LIMIT)
    }

    if (progress.isEmpty()) {
        // A first run has nothing to continue and nothing to list, so the whole
        // screen is one answer: what this is, and where to go to fill it.
        EmptyState(
            title = shura("home.empty"),
            body = shura("home.emptyHint"),
            icon = ShuraIcons.Books,
            actionLabel = shura("home.emptyAction"),
            onAction = onBrowse,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (unfinished.isNotEmpty()) {
            item {
                SectionHeader(
                    label = shura("home.continue"),
                    modifier = Modifier.padding(horizontal = ShuraSpacing.screen),
                    trailingLabel = shura("library.chapters", unfinished.size),
                )
            }
            item {
                SummaryStrip(
                    shura("home.continueHelp"),
                    Modifier.padding(horizontal = ShuraSpacing.screen),
                )
            }
            item(key = "featured-resume") {
                ResumeHero(unfinished.first()) {
                    onOpenEntry(unfinished.first())
                }
            }
            if (unfinished.size > 1) item {
                // A strip, not a grid: these are chapters you are in the middle of,
                // and the fastest way back into one is to see them side by side and
                // tap the right one.
                LazyRow(
                    contentPadding = PaddingValues(
                        start = ShuraSpacing.screen,
                        end = ShuraSpacing.screen,
                        top = 10.dp,
                        bottom = 4.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
                ) {
                    items(unfinished.drop(1), key = { "open:${it.sourceId}|${it.chapterId}" }) { entry ->
                        ContinueCard(entry) { onOpenEntry(entry) }
                    }
                }
            }
        }

        if (finished.isNotEmpty()) {
            item {
                SectionHeader(
                    label = shura("home.recent"),
                    modifier = Modifier.padding(horizontal = ShuraSpacing.screen),
                    trailingLabel = shura("library.chapters", finished.size),
                )
            }
            item {
                SummaryStrip(
                    shura("home.recentHelp"),
                    Modifier.padding(horizontal = ShuraSpacing.screen),
                )
            }
            itemsIndexed(finished, key = { _, e -> "read:${e.sourceId}|${e.chapterId}" }) { _, entry ->
                RecentRow(entry) { onOpenEntry(entry) }
            }
        }

        item {
            // The way to everything that was saved. Its own row rather than a
            // section heading with nothing under it, which is what it used to be.
            AppRow(
                title = shura("library.title"),
                meta = shura("home.libraryHelp"),
                modifier = Modifier.padding(horizontal = ShuraSpacing.screen, vertical = 8.dp),
                leading = { RowMark(ShuraIcons.Books, contentDescription = null) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = onOpenLibrary,
            )
        }
    }
}

/** How many open chapters the strip offers before it becomes a wall of cards. */
private const val CONTINUE_LIMIT = 8

/** How many finished ones the list shows. */
private const val RECENT_LIMIT = 20

@Composable
private fun ResumeHero(entry: ReadingProgress, onClick: () -> Unit) {
    AppPlate(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ShuraSpacing.screen),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverPlate(
                coverUrl = entry.coverUrl,
                width = 88.dp,
                label = entry.mangaTitle,
                modifier = Modifier.clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    shura("home.continue"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(shura("details.continue"))
                }
            }
        }
    }
}

/**
 * One chapter you are in the middle of.
 *
 * A wide card with the cover on the leading edge rather than a poster: this is a
 * strip of things to resume, and the cover and the place in it are two things the
 * reader checks at a glance, side by side.
 */
@Composable
private fun ContinueCard(entry: ReadingProgress, onClick: () -> Unit) {
    AppPlate(modifier = Modifier.width(252.dp), onClick = onClick) {
        Row(Modifier.padding(10.dp)) {
            CoverPlate(
                coverUrl = entry.coverUrl,
                width = 62.dp,
                label = entry.mangaTitle,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.mangaTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // Where in it you are, as a sentence; the bar under it is the same
                // fact as a shape, for anyone who reads shape faster than number.
                Text(
                    shura(
                        "reader.pages",
                        (entry.page + 1).coerceAtLeast(1),
                        entry.pageCount.coerceAtLeast(1),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { readFraction(entry) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

/** One chapter that is finished, as a row: cover, work, where it got to. */
@Composable
private fun RecentRow(entry: ReadingProgress, onClick: () -> Unit) {
    AppRow(
        title = entry.mangaTitle,
        meta = detailJoin(
            entry.chapterName,
            shura("reader.pages", (entry.page + 1).coerceAtLeast(1), entry.pageCount.coerceAtLeast(1)),
        ),
        modifier = Modifier.padding(horizontal = ShuraSpacing.screen, vertical = 3.dp),
        leading = { CoverPlate(entry.coverUrl, width = 38.dp, label = entry.mangaTitle) },
        trailing = {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = shura("details.continue"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        onClick = onClick,
    )
}

/** How much of a chapter has been read, as a fraction. Zero when it cannot be. */
internal fun readFraction(entry: ReadingProgress): Float =
    if (entry.pageCount <= 1) 0f else (entry.page + 1).toFloat() / entry.pageCount

/** Sources, then a title's chapters. */
@Composable
fun SourcePickerScreen(
    sources: List<ShuraSource>,
    onPick: (ShuraSource) -> Unit,
    /** Sends the reader to where they can actually fix it. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) {
        // Without a source there is nothing to search, so this must offer the way
        // out rather than only state the problem.
        EmptyState(
            title = shura("discover.sourcesEmpty"),
            body = shura("settings.repositoryHelp"),
            icon = ShuraIcons.Target,
            actionLabel = shura("settings.installRepository"),
            onAction = onOpenSettings,
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
    ) {
        item {
            // How many there are, rather than a heading repeating the title in the
            // bar above it.
            SummaryStrip(
                shura("discover.sourceCount", sources.size),
                Modifier.padding(horizontal = ShuraSpacing.screen + 4.dp),
            )
        }
        items(sources, key = { it.id }) { source ->
            AppRow(
                title = source.name,
                meta = source.language,
                modifier = Modifier.padding(horizontal = ShuraSpacing.screen),
                leading = { SourceMark(source.name) },
                trailing = {
                    Icon(
                        // Mirrored by the layout direction, so it points the way the
                        // reader reads rather than always to the right.
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { onPick(source) },
            )
        }
    }
}

/** A source's own initial in a square, the way the store marks an extension. */
@Composable
private fun SourceMark(name: String) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The way back out of a screen that was pushed onto the stack.
 *
 * One definition for every screen: it is drawn once, in the app's top bar, rather
 * than in the body of each pushed screen. A word-sized button used to sit where
 * the arrow goes on some of them, which pushed the content it belonged to down a
 * line and, in Arabic, took as much width as the title it preceded. The arrow
 * mirrors itself, so it points the way out in either direction.
 */
@Composable
fun ScreenBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = shura("common.back"),
        )
    }
}