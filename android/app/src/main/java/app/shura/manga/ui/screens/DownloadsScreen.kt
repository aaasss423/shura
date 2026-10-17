package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.DownloadJob
import app.shura.manga.DownloadState
import app.shura.manga.DownloadedWork
import app.shura.manga.StoredChapter
import app.shura.manga.groupDownloads
import app.shura.manga.isActive
import app.shura.source.Chapter
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing

/**
 * What the downloads are doing, one row per *work*.
 *
 * The row is a book, not a chapter. A reader who saved five chapters of one
 * series was looking at five rows and had to read all five to notice they were
 * one book; a list of chapters with covers on them is a chapter list that
 * happens to be in the wrong place. So each row is the work - its cover, its
 * name, how many of its chapters are on this device and what is happening to
 * the rest - and tapping it opens *its* saved chapters rather than dropping the
 * reader into whichever chapter happens to be first.
 *
 * The row is the app's row: the same plate, the same leading cover and the same
 * trailing chevron the search results use, because it is the same question -
 * which book, and how do I get into it.
 */
@Composable
fun DownloadsScreen(
    jobs: List<DownloadJob>,
    /** Chapters on the device, which is what makes a work a row here. */
    stored: List<StoredChapter> = emptyList(),
    onCancel: (String) -> Unit,
    /**
     * Stops every download that is running or waiting.
     *
     * Null hides it, which is the honest thing to do when nothing is moving: an
     * action that is always on screen and does nothing is the dead control this
     * app keeps replacing.
     */
    onCancelAll: (() -> Unit)? = null,
    /** Starts the download again, from the row that reports it failed. */
    onRetry: (String) -> Unit,
    onForget: (String) -> Unit,
    /** Opens one work's saved chapters. */
    onOpenWork: (DownloadedWork) -> Unit = {},
    /** Where a chapter gets saved, for the empty state to send the reader to. */
    onBrowse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val works = remember(stored, jobs) { groupDownloads(stored, jobs) }
    if (works.isEmpty()) {
        // Not one line of text floating in a blank screen: what this screen is for,
        // what a download looks like when there is one, and where to start it.
        EmptyState(
            title = shura("downloads.empty"),
            body = shura("downloads.emptyBody"),
            icon = ShuraIcons.Download,
            actionLabel = onBrowse?.let { shura("home.emptyAction") },
            onAction = onBrowse,
            modifier = modifier,
        )
        return
    }
    val running = jobs.count { it.isActive }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShuraSpacing.screen,
            end = ShuraSpacing.screen,
            top = 10.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
    ) {
        item {
            // What is in flight and what is not, above the rows: the count is the
            // only thing a reader wants from a queue before reading the queue.
            SummaryStrip(shura("downloads.summary", running, jobs.size - running))
        }
        if (running > 0 && onCancelAll != null) {
            // One action for everything still moving.
            //
            // Per-row cancel is right for one chapter and useless for the two
            // hundred "download the rest" queues on a long series - and the only
            // way to stop those today is to press cancel two hundred times. It
            // sits in the section header's own trailing slot, which is what that
            // slot is for: one label, one action, at the top of the thing it acts
            // on rather than buried among the rows.
            item {
                SectionHeader(
                    label = shura("downloads.inFlight"),
                    trailingLabel = shura("downloads.cancelAll"),
                    onTrailing = onCancelAll,
                )
            }
        }
        items(works, key = { it.key }) { work ->
            DownloadWorkRow(work, onOpen = { onOpenWork(work) })
        }
    }
}

/**
 * One work in the downloads list.
 *
 * Cover on the leading edge and the way onward on the trailing edge, exactly as
 * a search result is drawn - this row is the same question with the same answer
 * shape, and the two drifting apart is what makes an app look like two apps.
 *
 * The meta line carries the three numbers a reader asks for: how much of it is
 * on this device, how much is still arriving, and whether anything failed. It is
 * built by [workMeta] so what it says is decided in one place rather than by a
 * string expression inside a layout.
 */
@Composable
private fun DownloadWorkRow(work: DownloadedWork, onOpen: () -> Unit) {
    AppRow(
        title = work.title,
        meta = workMeta(work),
        titleLines = 2,
        onClick = onOpen,
        leading = { CoverPlate(work.coverUrl, width = 44.dp, label = work.title) },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/**
 * What one work's row says underneath its name.
 *
 * The saved count is always there: it is the fact that makes the row exist. What
 * is happening to the rest of the work is added only while it is happening -
 * "0 downloading" and "0 failed" are noise, and a line of noise on every row is
 * how a real number stops being noticed.
 */
@Composable
internal fun workMeta(work: DownloadedWork): String = detailJoin(
    shura("downloads.workChapters", work.localCount),
    if (work.inProgress) shura("downloads.workActive", work.activeCount) else "",
    if (work.failedCount > 0) shura("downloads.failed") else "",
)

/**
 * One work's saved chapters, and whatever is still arriving for it.
 *
 * This is what a row on the downloads screen opens, and it is deliberately a
 * *list of chapters*: the reader tapped a book, and a book is opened by choosing
 * a chapter of it - the same as opening it from the library. Tapping a chapter
 * here still opens the reader, on that chapter's local files.
 *
 * The queue rows for the work come first, because a download in flight is what
 * the reader came to watch; the saved chapters are below them, in the order they
 * actually landed, which is the only order the store can know.
 */
@Composable
fun StoredChaptersScreen(
    work: DownloadedWork,
    onRead: (StoredChapter) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShuraSpacing.screen,
            end = ShuraSpacing.screen,
            top = 6.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
    ) {
        item {
            // The work itself, once: which book these chapters belong to, how
            // much of it is here, and what is still on its way. The bar above
            // names it, but a list of chapters under no cover at all is a list
            // of chapters under the wrong book.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverPlate(work.coverUrl, width = 48.dp, label = work.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        work.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        workMeta(work),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (work.jobs.isNotEmpty()) {
            item {
                SectionHeader(
                    label = shura("downloads.title"),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(work.jobs, key = { it.key }) { job ->
                DownloadRow(job, onCancel, onRetry, onForget)
            }
        }
        item {
            SectionHeader(
                label = detailJoin(
                    shura("downloads.savedChapters"),
                    shura("discover.chapterCount", work.localCount),
                ),
            )
        }
        items(work.stored, key = { it.dir.name }) { chapter ->
            // The app's chapter row, marked saved: a chapter that is on the
            // device says so with a tick and a description rather than by
            // offering a download button for itself.
            ChapterRow(
                chapter = Chapter(chapter.chapterId, chapter.title, null),
                sourceName = "",
                read = false,
                downloaded = true,
                queued = false,
                onOpen = { onRead(chapter) },
                onDownload = null,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    job: DownloadJob,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    val state = downloadStateOf(job)
    AppPlate(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowMark(state.look.icon(), contentDescription = null, tint = state.look.tint())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // The work is the title, the chapter is the line under it: a
                    // queue is read by the book it belongs to.
                    Text(
                        job.mangaTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        job.chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detailJoin(job.sourceName, state.line),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (job.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                DownloadAction(job, onCancel, onRetry, onForget)
            }
            if (job.state == DownloadState.QUEUED || job.state == DownloadState.RUNNING) {
                // Under the row rather than beside the text, so the row's three
                // lines stay three lines and the bar reads as the progress of the
                // row it belongs to.
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

/** The one action this row can be asked to take, on the trailing edge. */
@Composable
private fun DownloadAction(
    job: DownloadJob,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    when (job.state) {
        DownloadState.QUEUED, DownloadState.RUNNING -> IconButton(
            onClick = { onCancel(job.key) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = shura("downloads.cancel"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DownloadState.FAILED -> IconButton(
            onClick = { onRetry(job.key) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = shura("downloads.retry"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        DownloadState.DONE, DownloadState.CANCELLED -> IconButton(
            onClick = { onForget(job.key) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = shura("downloads.remove"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Which of the four states a download row is in. */
private enum class DownloadLook { RUNNING, DONE, FAILED, CANCELLED }

/**
 * Everything the four states differ by, in one place.
 *
 * Built once per row rather than switched on twice, because the mark and the
 * sentence are the same fact: a row whose tick says "cancelled" is worse than no
 * row at all.
 */
private class DownloadRowState(val look: DownloadLook, val line: String)

@Composable
private fun downloadStateOf(job: DownloadJob): DownloadRowState = when (job.state) {
    DownloadState.QUEUED, DownloadState.RUNNING -> DownloadRowState(
        DownloadLook.RUNNING,
        shura("downloads.progress", job.done, job.total),
    )
    DownloadState.DONE -> DownloadRowState(DownloadLook.DONE, shura("downloads.done"))
    DownloadState.FAILED -> DownloadRowState(
        DownloadLook.FAILED,
        // The reason, not a generic failure: "the source returned no pages" and
        // "page 7 did not arrive" are different problems.
        "${shura("downloads.failed")}: ${job.detail}",
    )
    DownloadState.CANCELLED -> DownloadRowState(
        DownloadLook.CANCELLED,
        shura("downloads.cancelled"),
    )
}

@Composable
private fun DownloadLook.icon(): ImageVector = when (this) {
    DownloadLook.RUNNING -> Icons.Default.Refresh
    DownloadLook.DONE -> Icons.Default.Check
    DownloadLook.FAILED -> Icons.Default.Warning
    DownloadLook.CANCELLED -> Icons.Default.Close
}

@Composable
private fun DownloadLook.tint(): Color = when (this) {
    DownloadLook.RUNNING -> MaterialTheme.colorScheme.primary
    DownloadLook.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadLook.FAILED -> MaterialTheme.colorScheme.error
    DownloadLook.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}