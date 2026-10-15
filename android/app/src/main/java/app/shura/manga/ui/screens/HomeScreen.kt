package app.shura.manga.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.reader.ReadingProgress
import app.shura.manga.ui.shura
import app.shura.manga.ui.image.CoverImage
import androidx.compose.foundation.layout.size

/**
 * Home: what to read next, and what was read recently.
 *
 * Continue is first and only appears when there is something to continue - an
 * empty "Continue" button is a dead end on a first run, so the empty state
 * points at Discover instead.
 */
@Composable
fun HomeScreen(
    progress: List<ReadingProgress>,
    onOpenEntry: (ReadingProgress) -> Unit,
    onBrowse: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val continueEntry = progress.firstOrNull()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                shura("app.name"),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (continueEntry != null) {
            item {
                SectionCard(
                    title = shura("home.continue"),
                    accent = true,
                    onClick = { onOpenEntry(continueEntry) },
                ) {
                    Row {
                        // The work's real cover. Keyed by its own URL, so a fast
                        // scroll cannot leave one work's cover on another's card.
                        CoverImage(
                            source = continueEntry.coverUrl,
                            modifier = Modifier.size(width = 64.dp, height = 92.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            retryLabel = shura("common.retry"),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                continueEntry.mangaTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                continueEntry.chapterName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            // Where the reader stopped, as a bar: at a glance,
                            // without reading a number.
                            val fraction = if (continueEntry.pageCount <= 1) 0f
                            else (continueEntry.page + 1).toFloat() / continueEntry.pageCount
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                shura("reader.pages", (continueEntry.page + 1).coerceAtLeast(1),
                                    continueEntry.pageCount.coerceAtLeast(1)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = shura("nav.library"), onClick = onOpenLibrary) {
                Text(
                    shura("library.title"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text(shura("home.recent"), style = MaterialTheme.typography.titleMedium)
        }
        if (progress.isEmpty()) {
            item {
                Column(Modifier.padding(vertical = 24.dp)) {
                    Text(
                        shura("home.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        shura("home.emptyHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBrowse) { Text(shura("nav.discover")) }
                }
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(progress) { entry ->
                        RecentCard(entry) { onOpenEntry(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = if (accent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun RecentCard(entry: ReadingProgress, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(190.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            CoverImage(
                source = entry.coverUrl,
                modifier = Modifier.fillMaxWidth().height(190.dp)
                    .clip(RoundedCornerShape(8.dp)),
                retryLabel = shura("common.retry"),
            )
            Spacer(Modifier.height(8.dp))
            // Progress as a filled bar: at a glance where the reader stopped,
            // without reading a number.
            LinearProgressIndicator(
                progress = {
                    if (entry.pageCount <= 1) 0f
                    else (entry.page + 1).toFloat() / entry.pageCount
                },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                entry.mangaTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                entry.chapterName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Sources, then a title's chapters. */
@Composable
fun SourcePickerScreen(
    sources: List<app.shura.source.ShuraSource>,
    onPick: (app.shura.source.ShuraSource) -> Unit,
    /** Sends the reader to where they can actually fix it. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) {
        // Without a source there is nothing to search, so this must offer the
        // way out rather than just stating the problem.
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                shura("discover.sourcesEmpty"),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text(shura("settings.installRepository")) }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(shura("discover.sources"), style = MaterialTheme.typography.titleLarge)
        }
        items(sources, key = { it.id }) { source ->
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(source) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            source.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
fun StatePanel(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
