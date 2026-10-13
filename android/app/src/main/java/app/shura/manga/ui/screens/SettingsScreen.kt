package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.UiLanguage

@Composable
fun SettingsScreen(
    language: UiLanguage,
    onLanguage: (UiLanguage) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    repositoryNotice: String?,
    onInstallRepository: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(shura("nav.settings"), style = MaterialTheme.typography.titleLarge)

        Column {
            Text(shura("settings.language"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiLanguage.entries.forEach { option ->
                    FilterChip(
                        selected = option == language,
                        onClick = { onLanguage(option) },
                        label = { Text(if (option == UiLanguage.ARABIC) "العربية" else "English") },
                    )
                }
            }
        }

        Column {
            Text(shura("settings.theme"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = dark,
                    onClick = { onDark(true) },
                    label = { Text(shura("settings.dark")) },
                )
                FilterChip(
                    selected = !dark,
                    onClick = { onDark(false) },
                    label = { Text(shura("settings.light")) },
                )
            }
        }

        Column {
            Text(shura("settings.repository"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                repositoryNotice ?: shura("settings.noRepository"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onInstallRepository) {
                Text(shura("settings.installRepository"))
            }
        }
    }
}
