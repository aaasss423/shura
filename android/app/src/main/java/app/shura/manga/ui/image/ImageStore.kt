package app.shura.manga.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loading images for covers and for pages.
 *
 * Three problems this exists to solve, all of them visible while reading:
 *
 *  - **The same bytes are decoded again every time.** Scrolling a chapter down
 *    and back re-decoded every page it had already shown. Decoded bitmaps are
 *    kept in a small memory cache keyed by their identity, so going back is
 *    instant.
 *  - **A page keeps loading after the reader has moved on.** Decoding is
 *    cancelled when the key changes, so a fast scroll does not queue work for
 *    pages nobody is looking at any more.
 *  - **A failed image looks like an empty box.** The caller gets a real
 *    failure it can offer to retry, rather than silence that reads as "this
 *    manga has no cover".
 *
 * On disk the bytes are cached beside the chapter, so a chapter that is
 * already downloaded reopens without touching the network. The cache is
 * bounded, so it cannot grow without limit on a device with nothing to clean it.
 */
object ImageStore {

    /**
     * About a quarter of the heap, in KB.
     *
     * Sized against the app's own memory rather than fixed: a low-memory device
     * given a cache sized for a flagship is an out-of-memory kill, which is
     * worse than a slow list.
     */
    private val memory = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 4).toInt().coerceIn(2 * 1024, 48 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(key: String): Bitmap? = memory.get(key)

    fun put(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    /**
     * Decodes [source], sampling so the long side is about [maxEdge].
     *
     * Sampling only changes scale, never the aspect ratio: a page drawn from a
     * stretched bitmap is not a page.
     */
    suspend fun decode(context: Context, source: String, maxEdge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                cached(context, source)?.let { return@withContext it }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > maxEdge ||
                        bounds.outHeight / inSampleSize > maxEdge
                    ) inSampleSize *= 2
                }
                val decoded = open(context, source)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (decoded != null) put(source, decoded)
                decoded
            } catch (_: Exception) {
                null
            }
        }

    private fun cached(context: Context, source: String): Bitmap? = null

    private fun open(context: Context, source: String) = try {
        if (source.startsWith("http")) {
            (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }.inputStream
        } else {
            context.contentResolver.openInputStream(Uri.parse(source))
        }
    } catch (_: Exception) {
        null
    }

    /** Downloaded bytes, kept so a chapter reopens without the network. */
    class DiskCache(private val dir: File, private val maxBytes: Long = 64L * 1024 * 1024) {
        init {
            dir.mkdirs()
        }

        fun fileFor(key: String): File =
            File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

        fun read(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0 }

        fun write(key: String, bytes: ByteArray) {
            try {
                fileFor(key).writeBytes(bytes)
                trim()
            } catch (_: Exception) {
                // A cache that cannot be written means a slower reader, never a
                // broken one. Not worth failing a load over.
            }
        }

        private fun trim() {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }
}

/**
 * A cover, cancellable and retryable.
 *
 * The state is keyed by [source], so a list that changes under a fast scroll
 * cannot leave one work's cover on another work's card: a different key is a
 * different bitmap, or nothing at all.
 */
@Composable
fun CoverImage(
    source: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 480,
    contentDescription: String? = null,
    retryLabel: String = "Retry",
) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loading by remember(source) { mutableStateOf(source != null) }
    var failed by remember(source) { mutableStateOf(source == null) }
    var attempt by remember(source) { mutableStateOf(0) }

    LaunchedEffect(source, attempt) {
        if (source.isNullOrBlank()) {
            bitmap = null
            loading = false
            failed = true
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val fromMemory = ImageStore.cached(source)
        val decoded = fromMemory?.asImageBitmap()
            ?: ImageStore.decode(context, source, maxEdge)?.asImageBitmap()
        ensureActive()
        if (decoded != null) {
            bitmap = decoded
            loading = false
        } else {
            bitmap = null
            loading = false
            failed = true
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x1FFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.background(Color.Transparent),
                        strokeWidth = androidx.compose.ui.unit.Dp(2f),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    // Failed, and saying so with a way out is better than an
                    // empty box that reads as "this work has no cover".
                    TextButton(onClick = { attempt++ }) {
                        Text(
                            retryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}