package app.shura.manga

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.ShuraSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browse a Shura source: search -> manga -> chapters -> (read/download). */
@Composable
internal fun MangaBrowserScreen(
    sources: List<ShuraSource>,
    onOpenChapter: (ShuraSource, String, Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (ShuraSource, String, Chapter) -> Unit,
    notice: String? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(sources.firstOrNull()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MangaSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var manga by remember { mutableStateOf<MangaSummary?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var chapterLoading by remember { mutableStateOf<String?>(null) }

    if (sources.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp)) {
            Text("No source adapters are installed yet. Configure a Shura source host to browse manga.")
        }
        return
    }

    fun searchNow() {
        val active = source ?: return
        scope.launch {
            searching = true
            browseError = null
            runCatching { withContext(Dispatchers.IO) { active.search(query.trim(), 1) } }
                .onSuccess {
                    results = it
                    manga = null
                    chapters = null
                }
                .onFailure { browseError = it.message ?: "search failed" }
            searching = false
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        var showPicker by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Source:", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { showPicker = true }) { Text(source?.name ?: "Pick a source ▾") }
            AlertDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {},
                text = {
                    LazyColumn {
                        items(sources.size, key = { sources[it].id }) { index ->
                            Surface(Modifier.fillMaxWidth().clickable {
                                source = sources[index]
                                results = emptyList()
                                chapters = null
                                manga = null
                                showPicker = false
                            }) { Text("${sources[index].name} (${sources[index].language})", Modifier.padding(10.dp)) }
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search title") },
            modifier = Modifier.fillMaxWidth().testTag("source-search"),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { searchNow() }, modifier = Modifier.fillMaxWidth()) {
            Text("Search")
        }
        if (searching) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        browseError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        chapterLoading?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(it)
            }
        }
        Spacer(Modifier.height(12.dp))
        val current = manga
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { manga = null }) { Text("◀ back") }
                Text(current.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            val chapterList = chapters
            if (chapterList == null) {
                LaunchedEffect(current.id) {
                    runCatching { withContext(Dispatchers.IO) { source?.chapters(current.id).orEmpty() } }
                        .onSuccess { chapters = it }
                        .onFailure { browseError = it.message ?: "chapter list failed" }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("chapter-list")) {
                    items(chapterList.size, key = { chapterList[it].id }) { index ->
                        val chapter = chapterList[index]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(chapter.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDownloadChapter(source!!, current.title, chapter) }) { Text("Download") }
                            Button(onClick = { onOpenChapter(source!!, current.title, chapter, chapterList) }) { Text("Read") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                if (results.isEmpty() && query.trim().isEmpty()) {
                    item { Text("Search a title or open a source to browse latest manga.", Modifier.padding(vertical = 16.dp)) }
                }
                items(results.size, key = { results[it].id }) { index ->
                    val item = results[index]
                    Column(Modifier.fillMaxWidth().clickable { manga = item }.padding(vertical = 10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.id.take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun DownloadsScreen(
    context: Context,
    onRead: (StoredChapter) -> Unit,
    refresh: Int = 0,
    modifier: Modifier = Modifier,
) {
    val store = remember { chapterStore(context) }
    var version by remember { mutableIntStateOf(refresh) }
    val chapters = remember(version) { indexStoredChapters(store) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Offline chapters", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (chapters.isEmpty()) {
            Text("Downloaded chapters appear here and read fully offline.")
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("downloads-list")) {
                items(chapters.size, key = { chapters[it].chapterId }) { index ->
                    val stored = chapters[index]
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stored.title, modifier = Modifier.weight(1f))
                        Text("${stored.pageUris.size}p", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { deleteStoredChapter(stored); version++ }) { Text("Delete") }
                        Button(onClick = { onRead(stored) }) { Text("Read offline") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}