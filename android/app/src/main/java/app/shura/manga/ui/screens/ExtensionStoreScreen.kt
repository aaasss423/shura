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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.ExtensionCatalog
import app.shura.manga.ExtensionEntry
import app.shura.manga.ExtensionTrust
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing

/** Where a row is right now in its own lifecycle. */
enum class ExtensionAction { INSTALL, UPDATE, REMOVE }

/** One row's action, plus where it is in progress. */
data class ExtensionRowState(
    val entry: ExtensionEntry,
    val action: ExtensionAction,
    val installedVersion: String?,
    val progress: Float?,
    val message: String?,
    val failed: Boolean,
) {
    /** The one thing this row can be asked to do right now. */
    val isBusy: Boolean get() = progress != null
}

/** Which of the two lists of the same rows is showing. */
private enum class StoreTab { EXTENSIONS, UPDATES }

/**
 * The extension store.
 *
 * Lists what the installed repository offers and, for each one, the trust
 * verdict that decides whether this build will run it. The verdict comes from
 * [ExtensionCatalog], which uses the same [app.shura.manga.ShuraExtensions.policy]
 * the loader enforces - so a row never promises something load will refuse.
 *
 * Every row states one action, and it is the action that is actually true:
 * install when nothing is on the device, update when what is there is older
 * than the index, remove when it is current. A row that offers "install" for
 * something already installed is how a store starts lying.
 *
 * Two tabs over one list of rows, the way Mihon splits it: the whole repository,
 * and only the rows that actually have a newer version waiting. Updates is a
 * filter over the same [ExtensionRowState]s, not a second source of truth, so
 * the two can never disagree about what is installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionStoreScreen(
    rows: List<ExtensionRowState>,
    catalog: ExtensionCatalog,
    onInstall: (ExtensionEntry) -> Unit,
    onRemove: (ExtensionEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(StoreTab.EXTENSIONS) }
    val entries = rows.map { it.entry }
    val visible = if (tab == StoreTab.EXTENSIONS) rows
    else rows.filter { it.action == ExtensionAction.UPDATE }

    Column(modifier.fillMaxSize()) {
        // A literal index, never a lookup that can fail: a tab row handed -1 is
        // what took this app down before.
        TabRow(selectedTabIndex = if (tab == StoreTab.EXTENSIONS) 0 else 1) {
            StoreTab.entries.forEach { entry ->
                Tab(
                    selected = entry == tab,
                    onClick = { tab = entry },
                    text = {
                        Text(
                            shura(
                                if (entry == StoreTab.EXTENSIONS) "store.tab.extensions"
                                else "store.tab.updates",
                            ),
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = ShuraSpacing.screen),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ShuraSpacing.row),
        ) {
            item {
                // The honest summary: how much of the repository this build can
                // actually use, said before the user reads the rows.
                Text(
                    shura("store.runnable", catalog.runnable(entries).size, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (visible.isEmpty()) {
                // Centred in the space the rows would have taken, so an empty
                // store reads as "nothing here yet" rather than as a truncated
                // list pinned to one edge.
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (tab == StoreTab.EXTENSIONS) shura("store.empty")
                            else shura("store.updatesEmpty"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = ShuraSpacing.screen),
                        )
                    }
                }
            }

            items(visible, key = { row -> "${row.entry.pkg}@${row.entry.version}" }) { row ->
                ExtensionRow(
                    row = row,
                    // The reason for this exact row, not for whichever version of
                    // the same package happened to sort first.
                    whyNot = catalog.whyNot(entries, row.entry),
                    onInstall = { onInstall(row.entry) },
                    onRemove = { onRemove(row.entry) },
                )
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    row: ExtensionRowState,
    whyNot: String?,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val entry = row.entry
    // The app's row, like every list in the app: the extension's mark on the
    // leading edge, what it is in the middle, and the one action it can take on
    // the trailing edge. It used to be its own shape - a raised plate for
    // trusted rows and a flat one for the rest, with three stacked lines of meta
    // beside a button - so the store was the one screen that looked like nothing
    // else on the device.
    AppRow(
        title = ExtensionCatalog.displayName(entry),
        // Three facts, in the order a reader asks for them: which version, from
        // where, and - when this build will not run it - why not. The reason is
        // always shown when there is one: a greyed row with no explanation is the
        // bug this screen exists to avoid.
meta = listOfNotNull(
            // The verdict is a word, not only a colour: "trusted" next to the
            // version is the one thing a reader checking an index actually needs,
            // and a badge that only differs by hue is unreadable to anyone who
            // cannot see it.
            detailJoin(trustLabel(entry.trust), detailJoin(entry.version, entry.lang)),
            whyNot ?: entry.pkg,
            // The failure reason is shown where it happened, beside the control
            // that retries it, rather than only in a banner that may be scrolled
            // past.
            row.message,
            row.installedVersion?.let { shura("store.installed", it) },
        ).joinToString("\n"),
        metaLines = 4,
        modifier = Modifier.fillMaxWidth(),
        leading = { ExtensionAvatar(entry) },
        trailing = {
            ExtensionActionSlot(row = row, onInstall = onInstall, onRemove = onRemove)
        },
    )
}

/**
 * The one thing this row can be asked to do, on the trailing edge.
 *
 * While it is working there is no action to take, so the slot holds the progress
 * instead - which is why a download in flight cannot also be cancelled or
 * started again underneath it.
 */
@Composable
private fun ExtensionActionSlot(
    row: ExtensionRowState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val progress = row.progress
    if (progress != null) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Remove stays available while an extension is installed, so an unwanted
        // source can always be taken off the list. It is one quiet icon beside
        // the row's real action rather than a second word competing with it.
        if (row.action != ExtensionAction.REMOVE && row.entry.artifactPresent) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = shura("store.remove"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (row.action) {
            ExtensionAction.INSTALL -> Button(onClick = onInstall) {
                Text(shura("store.install"), maxLines = 1)
            }
            ExtensionAction.UPDATE -> Button(onClick = onInstall) {
                Text(shura("store.update"), maxLines = 1)
            }
            ExtensionAction.REMOVE -> OutlinedButton(onClick = onRemove) {
                Text(shura("store.remove"), maxLines = 1)
            }
        }
    }
}

/** The leading square: the extension's own initial, coloured by its verdict. */
@Composable
private fun ExtensionAvatar(entry: ExtensionEntry) {
    val trusted = entry.trust == ExtensionTrust.TRUSTED
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (trusted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text(
                ExtensionCatalog.displayName(entry).trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = if (trusted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the index says about this row, in words rather than in hue. */
@Composable
private fun trustLabel(trust: ExtensionTrust): String = when (trust) {
    ExtensionTrust.TRUSTED -> shura("store.trusted")
    ExtensionTrust.OUT_OF_SCOPE -> shura("store.outOfScope")
    ExtensionTrust.NOT_DOWNLOADED -> shura("store.notDownloaded")
    ExtensionTrust.UNKNOWN_PUBLISHER -> shura("store.unknownPublisher")
}