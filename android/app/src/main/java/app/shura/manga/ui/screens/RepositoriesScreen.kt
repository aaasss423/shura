package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.RepositoryRecord
import app.shura.manga.RepositoryStatusText
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing

/**
 * The repositories this app fetches extensions from.
 *
 * WHY THIS SCREEN HAD TO BE WRITTEN
 *
 * The machinery was all there and none of it was reachable: a typed address is
 * validated by `RepositoryAddress`, fetched and verified by `RepositoryManager`
 * through the same `ShuraRepositoryClient` the loader uses, and listed on disk by
 * `RepositoryRegistry` - and there was no control that called any of it. Settings
 * offered "install a repository from storage" and a button to a store that read
 * one hard-coded directory, so an address could be typed and nothing would come
 * of it.
 *
 * WHAT IT SHOWS
 *
 * One row per registered repository, saying what it is, whether it passed this
 * build's anchor check, how many packages its directory actually yields, and -
 * on a refusal - the reason in the runtime's own words. `RepositoryStatusText`
 * builds those sentences so the judgement is testable off-device, and the screen
 * only draws them.
 *
 * The extension store is where packages get installed. It is one tap away from
 * here and installation goes through the path that already existed; this screen
 * adds no second installer.
 */
@Composable
fun RepositoriesScreen(
    records: List<RepositoryRecord>,
    /** Fetches and verifies an address, then answers with what happened. */
    onAdd: (String) -> Unit,
    /** Fetches a registered repository again. */
    onRefresh: (RepositoryRecord) -> Unit,
    onRemove: (RepositoryRecord) -> Unit,
    /** Opens the store, where the packages this repository offers are listed. */
    onOpenStore: () -> Unit,
    /** Set while an add or a refresh is in flight, so the control cannot be pressed twice. */
    working: Boolean = false,
    /** The last thing that happened, said above the rows. */
    notice: String? = null,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    val canAdd = url.isNotBlank() && !working

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ShuraSpacing.screen, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                shura("repos.url"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The app's own text field, not a new shape: the library's search box
            // is the one piece of text input this design has, and a second one
            // that looks different is how a screen stops looking like the rest.
            SearchField(
                value = url,
                onValueChange = { url = it },
                placeholder = shura("repos.url"),
            )
            Text(
                shura("repos.urlHelp"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = {
                    onAdd(url)
                    // The field clears on submit so the next address is typed into
                    // a box that is not still holding the one that just worked.
                    url = ""
                },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(shura(if (working) "repos.working" else "repos.add"), maxLines = 1)
            }
            // Above the rows whether there are any or not. A refusal has to be
            // readable in both states, because the first address a reader types
            // is very often the one that is refused - and there are no rows then,
            // so a notice that lived inside the list would be the one message
            // they could never see.
            notice?.takeIf { it.isNotBlank() }?.let { message ->
                SummaryStrip(message)
            }
        }

        if (records.isEmpty()) {
            EmptyState(
                title = shura("repos.empty"),
                body = shura("repos.emptyBody"),
                icon = ShuraIcons.Target,
                actionLabel = shura("repos.openStore"),
                onAction = onOpenStore,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ShuraSpacing.screen,
                end = ShuraSpacing.screen,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
        ) {
            item {
                SummaryStrip(RepositoryStatusText.summary(records, uiLanguage()))
            }
            items(records, key = { it.id }) { record ->
                RepositoryRow(
                    record = record,
                    onRefresh = { onRefresh(record) },
                    onRemove = { onRemove(record) },
                )
            }
            item {
                // One way into the store, and it is the same store the Settings
                // button opens - installing a package from here is not a second
                // code path with its own rules.
                OutlinedButton(
                    onClick = onOpenStore,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(shura("repos.openStore"), maxLines = 1)
                }
            }
        }
    }
}

/**
 * One repository, and the two things that can be done to it.
 *
 * Refresh and remove rather than a single control, because they are opposites:
 * one fetches the repository again and the other takes its files away, and a
 * button that did both would be a button nobody could press on purpose.
 */
@Composable
private fun RepositoryRow(
    record: RepositoryRecord,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    AppRow(
        title = record.name.ifBlank { record.url },
        // The judgement, the count, the reason and the time, in that order: on a
        // refusal the reason is the only line worth reading, so `meta` puts it
        // where the eye lands first.
        meta = RepositoryStatusText.meta(record, uiLanguage()),
        metaLines = 4,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
        leading = { RowMark(ShuraIcons.Target, contentDescription = null) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = shura("repos.refresh"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = shura("repos.remove"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

/**
 * The language the sentences are built in.
 *
 * Read here rather than passed down, so every caller builds the same meta line
 * for the same record - which is what makes "the row says why" a fact about the
 * record instead of about which screen drew it.
 */
@Composable
private fun uiLanguage(): app.shura.manga.ui.theme.UiLanguage =
    app.shura.manga.ui.theme.LocalUiLanguage.current