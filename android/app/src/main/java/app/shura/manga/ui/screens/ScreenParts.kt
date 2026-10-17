package app.shura.manga.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shura.manga.ui.ChapterMeta
import app.shura.manga.ui.ChapterTitle
import app.shura.manga.ui.shura
import app.shura.source.Chapter

/**
 * The pieces more than one screen is built from.
 *
 * They live here rather than in the screen that happened to need them first,
 * because "the same rhythm" is only real if the row that opens a chapter and
 * the row that lists a download are drawn by the same code. Duplicated a row
 * shape, the two copies drift by a dp within a release and the grid stops
 * lining up.
 */

/** Marks a direction in text that has more than one. */
private const val RLM = "\u200F"

/**
 * Joins the parts of a line with a middot, pinning the separator's direction.
 *
 * A middot is a neutral character. Between a right-to-left part and a
 * left-to-right one it resolves to whichever side the paragraph runs, and the
 * two parts end up on screen in the opposite order to the one they were written
 * in - which is how "MangaDex · الفصل ١٢" comes out reading "الفصل ١٢ ·
 * MangaDex". A right-to-left mark around the separator gives it a direction of
 * its own, so the parts keep the order they were written in whatever the
 * neighbours are.
 *
 * This is the only thing here that touches text direction, and it is a
 * paragraph-level fix: the order of characters *inside* a word is the text
 * engine's, and no string built here can mirror a word.
 *
 * Public rather than internal because the chapter row's own meta line is
 * assembled outside a composable, where it is a decision about facts rather than
 * about drawing.
 */
fun detailJoin(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString("$RLM · $RLM")

/**
 * A number on a cover or a header, as a small dark pill.
 *
 * One definition, because a count is the one thing a reader scans a grid for,
 * and it has to look the same on a library tile and on a list header or it is
 * decoration rather than information.
 */
@Composable
internal fun CountPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * The scrim a title is read against when it sits on a cover.
 *
 * A gradient rather than a flat panel: a flat one cuts the cover in half and
 * looks like a caption bar, while the gradient darkens only where the type is
 * and leaves the artwork alone above it.
 */
internal fun coverScrim(): Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.45f to Color.Black.copy(alpha = 0.28f),
    1f to Color.Black.copy(alpha = 0.88f),
)

/**
 * Something is loading, said in the middle of the space it is loading into.
 *
 * Every screen used to decide for itself where its spinner went, and the results
 * were a spinner pinned to the top of a short column, one floating in the middle
 * of nowhere, and one off to the side. One centred panel, in the middle of the
 * area being filled, is the difference between "working" and "broken".
 */
@Composable
internal fun LoadingPanel(
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(26.dp),
        )
        label?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A leading mark for a row: an icon in a tinted square.
 *
 * The square is what makes it a rhythm rather than a floating glyph - every row
 * on every screen starts at the same place and takes the same amount of room,
 * so the eye can run down the column instead of hunting for it.
 */
@Composable
internal fun RowMark(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One chapter, the way a shelf labels a spine.
 *
 * The shape is fixed across the two screens that list chapters: an unread dot on
 * the leading edge, the number in a column of its own so four hundred rows can
 * be scanned for "12" without reading four hundred titles, the name and a
 * two-part meta line beneath it, and the one action this chapter can take on the
 * trailing edge.
 *
 * State is never carried by colour alone. A read chapter is dimmer *and* says
 * "read", and a saved one is a tick *and* has a description, because a row that
 * can only be read by its colour is unreadable to anyone who cannot see it.
 */
@Composable
internal fun ChapterRow(
    chapter: Chapter,
    sourceName: String,
    read: Boolean,
    downloaded: Boolean,
    queued: Boolean,
    onOpen: () -> Unit,
    /** Null hides the action entirely rather than offering a dead control. */
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * Overrides the meta line.
     *
     * The same row is drawn by the chapter list and by a work's own page, and
     * the two know different things: the list knows what the source is, the
     * page knows when the chapter was read. One row, two summaries, chosen by
     * the caller rather than by a second row shape.
     */
    meta: String? = null,
) {
    AppPlate(modifier = modifier.fillMaxWidth(), onClick = { onOpen() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnreadDot(read)
            Spacer(Modifier.width(10.dp))
            // The number in a column of its own, because sources are loose about
            // chapter numbers - some are null, some are 12.5 - so the column is
            // a fixed width and never moves.
            Text(
                chapterNumberLabel(chapter.number),
                Modifier.width(38.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    // The name the source gave, else the number it gave, else the
                    // id - and never the word "null", which is what four hundred
                    // rows were showing because the JSON layer stringifies a
                    // missing title that way. See [ChapterTitle].
                    chapterTitleLine(chapter),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    // Read chapters recede instead of disappearing: the filter is
                    // there for that, and hiding history by default is how a
                    // reader loses their place.
                    color = if (read) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // What this chapter can truthfully be said to be. The parts are
                    // decided by ChapterMeta rather than here, so that no screen can
                    // reach for a word it does not have a fact for.
                    meta ?: chapterMetaLine(
                        sourceName = sourceName,
                        read = read,
                        saved = downloaded,
                        queued = queued,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChapterActionSlot(
                downloaded = downloaded,
                queued = queued,
                onDownload = onDownload,
            )
        }
    }
}

/**
 * The meta line under a chapter's name, in the reader's language.
 *
 * The thin edge between [ChapterRow] and [ChapterMeta]: the row knows the facts,
 * the pure object knows which of them may be said, and this is the only place the
 * words and the facts meet. The labels are read here because this is inside a
 * composition and language is a property of the one.
 */
@Composable
internal fun chapterMetaLine(
    sourceName: String,
    read: Boolean,
    saved: Boolean = false,
    queued: Boolean = false,
): String = ChapterMeta.line(
    sourceName = sourceName,
    read = read,
    saved = saved,
    queued = queued,
    labels = ChapterMeta.Labels(
        unread = shura("chapters.unread"),
        read = shura("chapters.read"),
        saved = shura("chapters.saved"),
        queued = shura("chapters.queued"),
    ),
)

/**
 * A chapter's own line, in the reader's language.
 *
 * The second thin edge between [ChapterRow] and the pure objects: the row knows
 * the chapter, [ChapterTitle] decides which fact about it may be said, and this
 * is where the words meet. Read here because it is inside a composition and the
 * language is a property of the one.
 */
@Composable
internal fun chapterTitleLine(chapter: Chapter): String = ChapterTitle.titled(
    name = chapter.name,
    number = chapter.number,
    id = chapter.id,
    chapterWord = shura("chapters.number"),
)

/**
 * A chapter number as a reader would write it.
 *
 * Sources report numbers loosely: absent, whole, or "Chapter 12.5". Rendering the
 * Float directly put "1.0" in the leading column of every list, so whole numbers
 * lose the decimal and the rest keep only their real fraction. Empty rather than
 * a dash when there is no number at all - a fixed-width column is what keeps
 * four hundred rows scannable, and an empty cell is a fact this app actually
 * holds.
 */
internal fun chapterNumberLabel(number: Float?): String = ChapterTitle.numberLabel(number).orEmpty()

/** The unread mark: a dot on the leading edge, and nothing more. */
@Composable
private fun UnreadDot(read: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(
                if (read) Color.Transparent
                else MaterialTheme.colorScheme.primary,
            ),
    )
}

/**
 * The one thing this chapter can be asked to do.
 *
 * A tick when it is already saved, so the row says so without a button that
 * would offer the same work twice; a spinner while it is queued, so a download
 * in flight cannot also be started again underneath it.
 */
@Composable
private fun ChapterActionSlot(
    downloaded: Boolean,
    queued: Boolean,
    onDownload: (() -> Unit)?,
) {
    when {
        downloaded -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Check,
                contentDescription = shura("chapters.saved"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        queued -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        }
        onDownload == null -> Unit
        else -> IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
            Icon(
                ShuraIcons.Download,
                contentDescription = shura("chapters.download"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
