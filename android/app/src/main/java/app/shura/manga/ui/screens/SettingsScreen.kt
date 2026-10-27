package app.shura.manga.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.ShuraSpacing
import app.shura.manga.ui.theme.UiLanguage

/**
 * Settings: a name, then three groups of rows.
 *
 * Each group is a heading and a plate of rows, and every row is the same row -
 * a leading mark, a title, a summary line saying what it is or what it is set
 * to, and a control on the trailing edge where there is one. That uniformity is
 * the point: the screen used to be two large boxes whose contents were decided
 * one box at a time, so nothing in them matched anything else, and a setting was
 * either a switch or a button with no way of telling which from across the room.
 *
 * A yes-or-no setting is a switch and says which way it is set. A choice between
 * names is its own row with the names under it, in the order they are read. A
 * button is only there where pressing it does something - every control on this
 * screen is one the app answers, which is why there are no rows that merely look
 * tappable.
 */
@Composable
fun SettingsScreen(
    language: UiLanguage,
    onLanguage: (UiLanguage) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    dynamicColor: Boolean = false,
    onDynamicColor: (Boolean) -> Unit = {},
    repositoryNotice: String?,
    onInstallRepository: () -> Unit,
    onOpenStore: () -> Unit,
    /** Opens the repositories screen, where an address can be added by URL. */
    onOpenRepositories: () -> Unit = onOpenStore,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // The wordmark. The app's name as type, because an icon the reader has to
        // recognise is a worse signature than the name they already know.
        Column(Modifier.padding(start = ShuraSpacing.screen, end = ShuraSpacing.screen, top = 22.dp)) {
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

        Group(shura("settings.general")) {
            AppRow(
                title = shura("settings.language"),
                meta = shura("settings.languageHelp"),
                leading = { RowMark(ShuraIcons.Letter, contentDescription = null) },
            )
            // The names, in the order they are read, under the row they change. A
            // pair of chips is the whole control: there is no third language to
            // page through and no apply button to remember.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = ShuraSpacing.screen, bottom = 12.dp),
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
            RowDivider()
            // A boolean, so a switch. The summary still says which way it is set,
            // because a switch is a position and a position is not a sentence.
            SettingsRow(
                title = shura("settings.theme"),
                summary = shura(if (dark) "settings.dark" else "settings.light"),
                help = shura("settings.themeHelp"),
                icon = ShuraIcons.Contrast,
                onToggle = { onDark(!dark) },
                checked = dark,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RowDivider()
                SettingsRow(
                    title = shura("settings.dynamicColor"),
                    summary = shura(if (dynamicColor) "settings.enabled" else "settings.disabled"),
                    help = shura("settings.dynamicColorHelp"),
                    icon = ShuraIcons.Contrast,
                    onToggle = { onDynamicColor(!dynamicColor) },
                    checked = dynamicColor,
                )
            }
        }

        Group(shura("settings.translationGroup")) {
            SettingsRow(
                title = shura("settings.translation"),
                summary = shura("settings.translationUnavailable"),
                help = shura("settings.translationHelp"),
                icon = ShuraIcons.Letter,
            )
        }

        Group(shura("settings.sourcesGroup")) {
            SettingsRow(
                title = shura("settings.repository"),
                summary = repositoryNotice ?: shura("settings.noRepository"),
                help = shura("settings.repositoryHelp"),
                icon = Icons.Default.Build,
                // The repositories screen, which is where a repository is added
                // by URL. This row used to lead nowhere: it was drawn and
                // labelled, and only the button under it did anything - and only
                // for a folder already on the device.
                onOpen = onOpenRepositories,
            )
            SettingsRow(
                title = shura("repos.title"),
                summary = shura("repos.emptyBody"),
                icon = ShuraIcons.Target,
                onOpen = onOpenRepositories,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = ShuraSpacing.screen, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onInstallRepository, modifier = Modifier.weight(1f)) {
                    Text(shura("settings.installRepository"), maxLines = 2)
                }
                OutlinedButton(onClick = onOpenStore, modifier = Modifier.weight(1f)) {
                    Text(shura("store.open"), maxLines = 2)
                }
            }
            RowDivider()
            // Extensions as a row in their own right rather than only a button in
            // the row above: it is its own destination, and the row says what
            // pressing it leads to.
            SettingsRow(
                title = shura("settings.extensions"),
                summary = shura("settings.extensionsHelp"),
                icon = ShuraIcons.Series,
                onOpen = onOpenStore,
            )
        }

        Group(shura("settings.aboutGroup")) {
            SettingsRow(
                title = shura("settings.version"),
                summary = shura("settings.versionValue", app.shura.manga.BuildConfig.VERSION_NAME),
                icon = Icons.Default.Info,
            )
            RowDivider()
            // Not a control: reading settings exist, they are saved, and they are
            // reached from the reader itself. Saying where beats a row that looks
            // tappable and is not.
            SettingsRow(
                title = shura("reader.settings"),
                summary = shura("settings.readerSettingsHelp"),
                icon = ShuraIcons.Series,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** A group's heading and its plate of rows. One definition, three groups. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    SectionHeader(
        label = title,
        modifier = Modifier.padding(start = ShuraSpacing.screen, end = ShuraSpacing.screen, top = 14.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ShuraSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        content()
    }
}

/**
 * One setting: what it is, what it is set to, and the mark that names it.
 *
 * A row is only tappable when there is something behind it - a switch, or a
 * destination. The language row is not: the choice is made by the chips under it,
 * and a tappable row with nothing behind it is the dead button this screen used
 * to have.
 */
@Composable
private fun SettingsRow(
    title: String,
    summary: String?,
    icon: ImageVector,
    /** Present for a yes-or-no setting, which is the only kind with a switch. */
    onToggle: (() -> Unit)? = null,
    checked: Boolean = false,
    /** A row that leads somewhere rather than toggling. */
    onOpen: (() -> Unit)? = null,
    /** A second line saying what the setting is for, under what it is set to. */
    help: String? = null,
) {
    AppRow(
        title = title,
        meta = listOfNotNull(summary?.takeIf { it.isNotBlank() }, help)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n"),
        // Three lines when there is a summary and a help line, because they are
        // two separate sentences and squashing them into one loses whichever is
        // longer.
        metaLines = if (help != null) 2 else 1,
        leading = { RowMark(icon, contentDescription = null) },
        trailing = {
            when {
                onToggle != null -> Switch(checked = checked, onCheckedChange = { onToggle() })
                onOpen != null -> androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        onClick = onToggle ?: onOpen,
        titleLines = 1,
    )
}