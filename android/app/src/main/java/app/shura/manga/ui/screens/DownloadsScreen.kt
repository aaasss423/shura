package app.shura.manga.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.DownloadJob
import app.shura.manga.DownloadState
import app.shura.manga.ui.shura

/**
 * What the downloads are doing.
 *
 * Every row says one of four things and never anything else: it is downloading
 * N of M pages, it is saved, it failed with the reason it failed, or it was
 * cancelled. A progress bar with no failure text is the thing this screen exists
 * to avoid, because a download that silently stops looks exactly like one that
 * is nearly finished.
 */
@Composable
fun DownloadsScreen(
    jobs: List<DownloadJob>,
    onCancel: (String) -> Unit,
    /** Starts the download again, from the row that reports it failed. */
    onRetry: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenBackButton(onBack)
            Text(shura("downloads.title"), style = MaterialTheme.typography.titleMedium)
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { StatePanel(shura("downloads.empty"), "") }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(jobs, key = { it.key }) { job -> DownloadRow(job, onCancel, onRetry, onForget) }
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
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                job.chapterTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${job.mangaTitle} · ${job.sourceName}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (job.state) {
                DownloadState.QUEUED, DownloadState.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        shura("downloads.progress", job.done, job.total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onCancel(job.key) }) {
                        Text(shura("downloads.cancel"))
                    }
                }
                DownloadState.DONE -> {
                    Text(
                        shura("downloads.done"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onForget(job.key) }) {
                        Text(shura("downloads.remove"))
                    }
                }
                DownloadState.FAILED -> {
                    Text(
                        // The reason, not a generic failure: "the source returned no
                        // pages" and "page 7 did not arrive" are different problems.
                        "${shura("downloads.failed")}: ${job.detail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = { onRetry(job.key) }) { Text(shura("downloads.retry")) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onRetry(job.key) }) {
                            Text(shura("downloads.retry"))
                        }
                    }
                }
                DownloadState.CANCELLED -> {
                    Text(
                        shura("downloads.cancelled"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onForget(job.key) }) {
                        Text(shura("downloads.remove"))
                    }
                }
            }
        }
    }
}
