package app.shura.manga.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.UiLanguage

/**
 * Settings, arranged the way Mihon arranges them.
 *
 * A name at the top, then groups: a small uppercase header, and inside it a
 * surface of rows, each row a title, what it is currently set to, and an icon
 * on the trailing edge. The controls sit directly under the row they change
 * rather than behind a tap into another screen - a setting you can reach in one
 * tap is a setting that gets used.
 *
 * The rows are not clickable, and deliberately so: the choice is made by the
 * chips beneath it, which is where it has always been made. A tappable row with
 * nothing behind it is the dead button this screen used to have.
 */
@Composable
fun SettingsScreen(
    language: UiLanguage,
    onLanguage: (UiLanguage) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    repositoryNotice: String?,
    onInstallRepository: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        // The wordmark. The app's name as type, because an icon the reader has
        // to recognise is a worse signature than the name they already know.
        Column(Modifier.padding(top = 28.dp, bottom = 8.dp)) {
            Text(
                shura("app.name"),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                shura("settings.tagline"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))

        SettingsHeader(shura("settings.general"))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingsRow(
                    title = shura("settings.language"),
                    summary = shura(
                        if (language == UiLanguage.ARABIC) "settings.language.arabic"
                        else "settings.language.english",
                    ),
                    icon = Icons.Default.Person,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiLanguage.entries.forEach { option ->
                        FilterChip(
                            selected = option == language,
                            onClick = { onLanguage(option) },
                            label = {
                                Text(
                                    shura(
                                        if (option == UiLanguage.ARABIC) "settings.language.arabic"
                                        else "settings.language.english",
                                    ),
                                )
                            },
                        )
                    }
                }
                SettingsDivider()
                SettingsRow(
                    title = shura("settings.theme"),
                    summary = shura(if (dark) "settings.dark" else "settings.light"),
                    icon = Icons.Default.Star,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
        }

        Spacer(Modifier.height(24.dp))
        SettingsHeader(shura("store.repos"))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingsRow(
                    title = shura("settings.repository"),
                    summary = repositoryNotice ?: shura("settings.noRepository"),
                    icon = Icons.Default.Build,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onInstallRepository) {
                        Text(shura("settings.installRepository"), maxLines = 1)
                    }
                    OutlinedButton(onClick = onOpenStore) {
                        Text(shura("store.open"), maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** A group's name: small, quiet, and out of the way of the rows under it. */
@Composable
private fun SettingsHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

/** One setting: what it is, what it is set to, and the icon that names it. */
@Composable
private fun SettingsRow(
    title: String,
    summary: String?,
    icon: ImageVector,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
    )
}
