package app.shura.manga.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.ShuraSource
import app.shura.manga.ui.Strings
import app.shura.manga.ui.shura
import app.shura.manga.ui.theme.LocalUiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search a source's catalogue. Loading and error states are explicit. */
@Composable
fun SearchScreen(
    source: ShuraSource,
    onOpenManga: (MangaSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Captured so the non-composable callbacks below can format too; a
    // composable call inside runCatching{} is not allowed.
    val language = LocalUiLanguage.current
    var query by remember { mutableStateOf("") }
    var results by remember(source.id) { mutableStateOf<List<MangaSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    var searched by remember(source.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run() {
        searching = true
        error = null
        scope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { source.search(query.trim(), 1) } }
            outcome.onSuccess {
                results = it
                searched = true
                searching = false
            }.onFailure {
                // The reason is shown, not swallowed: a source that cannot be
                // reached should say so rather than look empty.
                error = it.message ?: Strings.t("error.generic", language)
                searching = false
                searched = true
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(shura("common.back")) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(shura("discover.search")) },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run() }, enabled = !searching) { Text(shura("nav.discover")) }
        }

        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { run() }) { Text(shura("common.retry")) }
            }
            searched && results.isEmpty() -> StatePanel(shura("discover.noResults"), source.name)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.id }) { manga ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenManga(manga) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                manga.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            manga.coverUrl?.let { cover ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    cover,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A title's chapters, newest first, which is the order they are read in. */
@Composable
fun ChapterListScreen(
    mangaTitle: String,
    source: ShuraSource,
    mangaId: String,
    onOpenChapter: (Chapter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalUiLanguage.current
    var chapters by remember(mangaId) { mutableStateOf<List<Chapter>?>(null) }
    var error by remember(mangaId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mangaId) {
        runCatching { withContext(Dispatchers.IO) { source.chapters(mangaId) } }
            .onSuccess { chapters = it }
            .onFailure { error = it.message ?: Strings.t("error.generic", language) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(shura("common.back")) }
            Text(mangaTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        when {
            error != null -> StatePanel(shura("common.error"), error!!)
            chapters == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            chapters!!.isEmpty() -> StatePanel(shura("discover.noChapters"), mangaTitle)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(shura("discover.chapters"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(chapters!!, key = { it.id }) { chapter ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenChapter(chapter) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chapter.name.ifBlank { chapter.id },
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            chapter.number?.let {
                                Text(
                                    it.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Saved chapters, with read and delete. */
@Composable
fun LibraryScreen(
    stored: List<app.shura.manga.StoredChapter>,
    onRead: (app.shura.manga.StoredChapter) -> Unit,
    onDelete: (app.shura.manga.StoredChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stored.isEmpty()) {
        StatePanel(shura("library.empty"), "")
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(shura("library.title"), style = MaterialTheme.typography.titleLarge) }
        items(stored, key = { it.chapterId }) { entry ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(
                            "${entry.pageUris.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRead(entry) }) { Text(shura("library.read")) }
                    IconButton(onClick = { onDelete(entry) }) {
                        Text("✕", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
