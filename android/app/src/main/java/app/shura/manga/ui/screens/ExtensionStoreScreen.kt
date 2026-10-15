package app.shura.manga.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shura.manga.ExtensionCatalog
import app.shura.manga.ExtensionEntry
import app.shura.manga.ExtensionTrust
import app.shura.manga.ui.shura

/**
 * The extension store.
 *
 * Lists what the installed repository offers and, for each one, the trust
 * verdict that decides whether this build will run it. The verdict comes from
 * [ExtensionCatalog], which uses the same [app.shura.manga.ShuraExtensions.policy]
 * the loader enforces - so a row never promises something load will refuse.
 *
 * Remove is the only action. Installing an extension needs bytes this app
 * cannot fetch on its own today, and a button that does nothing is worse than
 * its absence: it would say the app agreed to run something it has not.
 */
@Composable
fun ExtensionStoreScreen(
    entries: List<ExtensionEntry>,
    catalog: ExtensionCatalog,
    onRemove: (ExtensionEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(shura("store.title"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                // The honest summary: how much of the repository this build can
                // actually use, said before the user reads the rows.
                Text(
                    shura("store.runnable", catalog.runnable(entries).size, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    shura("store.empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(entries, key = { it.pkg }) { entry ->
            ExtensionRow(
                entry = entry,
                whyNot = catalog.whyNot(entries, entry.pkg),
                onRemove = { onRemove(entry) },
            )
        }
    }
}

@Composable
private fun ExtensionRow(
    entry: ExtensionEntry,
    whyNot: String?,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (entry.trust == ExtensionTrust.TRUSTED)
                MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.identity, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entry.version} · ${entry.lang}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TrustBadge(entry.trust)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                // The reason is always shown when there is one. A greyed row
                // with no explanation is the bug this screen exists to avoid.
                whyNot ?: entry.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onRemove) { Text(shura("store.remove")) }
            }
        }
    }
}

@Composable
private fun TrustBadge(trust: ExtensionTrust) {
    val label = when (trust) {
        ExtensionTrust.TRUSTED -> shura("store.trusted")
        ExtensionTrust.OUT_OF_SCOPE -> shura("store.outOfScope")
        ExtensionTrust.NOT_DOWNLOADED -> shura("store.notDownloaded")
        ExtensionTrust.UNKNOWN_PUBLISHER -> shura("store.unknownPublisher")
    }
    val colour = when (trust) {
        ExtensionTrust.TRUSTED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colour,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}