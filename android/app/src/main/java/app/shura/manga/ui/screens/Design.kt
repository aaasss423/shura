package app.shura.manga.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shura.manga.ui.image.CoverImage
import app.shura.manga.ui.shura

/**
 * The layout vocabulary every screen is built from.
 *
 * The complaint that produced this file was not "the colours are wrong" but
 * "every screen looks like a different app": the same job - a list of things, a
 * thing that is not there yet, a section of a screen - was built three different
 * ways on three screens, so nothing lined up and nothing repeated convincingly.
 *
 * So the shapes that repeat are named once, here, and every screen composes them
 * instead of styling its own:
 *
 *  - [AppPlate] / [AppRow] - one row: leading mark, title, meta line, trailing slot;
 *  - [SectionHeader] - one section heading, with an optional action on its end;
 *  - [EmptyState] - one "there is nothing here" panel: mark, sentence, way out;
 *  - [CoverTile] - one cover-with-title-on-it, for every grid in the app;
 *  - [SearchField] - one search box, on every screen that searches;
 *  - [ActionTile] - one icon-over-label action, for row-of-actions blocks;
 *  - [ShuraIcons] - the glyphs Material's core set does not carry.
 *
 * The point is not fewer lines. It is that changing the rhythm once changes it
 * everywhere, instead of leaving one screen holding the old one.
 */

/** The plate most things sit on: flat, quiet, and the same on every screen. */
@Composable
internal fun AppPlate(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    shape: Shape = MaterialTheme.shapes.small,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = color,
        shape = shape,
        content = content,
    )
}

/**
 * One row, in the order the reference uses everywhere: mark on the leading edge,
 * what it is in the middle, the way onward on the trailing edge.
 *
 * The mark and the trailing slot are both optional because half the rows in the
 * app have no leading mark - a row with a cover already starts with its content,
 * and adding an icon in front of a cover is decoration, not information.
 */
@Composable
internal fun AppRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleLines: Int = 1,
    metaLines: Int = 1,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    AppPlate(modifier = modifier, color = color, onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                meta?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = metaLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
    }
}

/**
 * A section's name, with something on its end.
 *
 * With an action the end is a word button - "all chapters", "sort" are two things
 * an icon cannot tell apart at 24dp. Without one it is a quiet count, which is
 * how a reader knows whether a section is worth scrolling to before they scroll.
 */
@Composable
internal fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    onTrailing: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            trailingLabel == null -> Unit
            onTrailing != null -> androidx.compose.material3.TextButton(
                onClick = onTrailing,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(trailingLabel, style = MaterialTheme.typography.labelSmall)
            }
            else -> Text(
                trailingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * "There is nothing here" - and what to do about it.
 *
 * Every screen that can be empty uses this: a mark, a sentence saying what is
 * missing, a second sentence saying how it gets there, and a button where there
 * is somewhere to go. The empty state used to be one line of text floating in
 * the middle of a screen, which reads as a bug rather than as an answer.
 */
@Composable
internal fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        body?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel, maxLines = 1) }
        }
    }
}

/**
 * A crimson count, on a corner.
 *
 * The count is the one thing a reader scans a cover grid for, so it gets one
 * shape in one colour: filled where it is the accent, outlined where it is only
 * a label.
 */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (outlined) Modifier.background(Color.Black.copy(alpha = 0.55f))
                else Modifier.background(tint),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = if (outlined) Color.White else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** A cover, in a box of the size the caller asks for. Used beside rows of text. */
@Composable
internal fun CoverPlate(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp,
    label: String? = null,
) {
    CoverImage(
        source = coverUrl,
        // 2:3, the plate ratio every source that matters uses for a cover, so a
        // work's cover is the same shape everywhere it appears.
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        contentDescription = label,
        retryLabel = shura("common.retry"),
    )
}

/**
 * One cover with its title on it.
 *
 * The title sits *over* the artwork at the bottom, over a scrim, rather than
 * under it: a caption under a cover pushes the rows apart, so half the screen is
 * gutter and the grid stops reading as a shelf. The count goes to the top-end
 * corner, opposite the title, so the two numbers a reader scans a grid for are
 * never in the same place twice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    badge: Int? = null,
    progress: Float? = null,
    selected: Boolean = false,
    selectable: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    favorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        // While a selection is running every tap toggles instead of
                        // opening, which is what makes picking five of them possible
                        // without a mode the reader can forget to leave.
                        onClick = { if (selectable) onClick() else onLongClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else Modifier.clickable { onClick() },
            )
            .then(
                if (selected) Modifier.border(
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    MaterialTheme.shapes.small,
                ) else Modifier,
            ),
    ) {
        CoverImage(
            source = coverUrl,
            modifier = Modifier.fillMaxSize(),
            retryLabel = shura("common.retry"),
        )
        Box(Modifier.matchParentSize().background(coverScrim()))
        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Black.copy(alpha = 0.35f),
            )
        }
        badge?.takeIf { it > 0 }?.let {
            CountBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp), outlined = true)
        }
        onFavoriteClick?.let { toggle ->
            IconButton(
                onClick = toggle,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(40.dp),
            ) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = shura(
                        if (favorite) "library.favorite.remove" else "library.favorite.add",
                    ),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
            )
            meta?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The search box, in one shape.
 *
 * The library and the catalogue used to have two different search boxes, one a
 * full-width outlined field that took most of a phone screen before the first
 * cover and one a 36dp sliver. This is the middle: a slim plate, an icon, the
 * field, and a way to clear it.
 */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    AppPlate(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { field ->
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    },
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = shura("common.clear"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * One action, as an icon with a word under it.
 *
 * Used where a screen has a handful of things it can do to the thing on it -
 * a work's page, the library's selection. Words, not a row of unlabelled icons:
 * share, add-to-library and chapters are three different taps and a reader
 * should not have to guess which glyph means which.
 */
@Composable
internal fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .widthIn(min = 68.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The hairline between rows of one group, inset so it does not touch the edges. */
@Composable
internal fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = modifier.padding(start = 64.dp, end = 16.dp),
    )
}

/** A quiet line above a list: how much of it is running and how much is done. */
@Composable
internal fun SummaryStrip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * Icons Shura needs that Material's core set does not carry.
 *
 * Core only is a deliberate choice - the extended set is tens of megabytes for
 * a handful of glyphs - so the ones that are missing are drawn here rather than
 * added as a dependency. They are stroke paths, so they take the tint of
 * whatever draws them exactly as a filled icon does.
 */
object ShuraIcons {

    /** An arrow into a tray: save this chapter for offline reading. */
    val Download: ImageVector by lazy {
        stroke("ShuraDownload") {
            moveTo(12f, 3.4f)
            lineTo(12f, 14.6f)
            moveTo(6.8f, 9.4f)
            lineTo(12f, 14.6f)
            lineTo(17.2f, 9.4f)
            moveTo(4.8f, 19.4f)
            lineTo(19.2f, 19.4f)
        }
    }

    /** Two spines on a shelf: the library, wherever it is named. */
    val Books: ImageVector by lazy {
        stroke("ShuraBooks") {
            moveTo(4f, 5.4f); lineTo(9.4f, 5.4f); lineTo(9.4f, 18.2f); lineTo(4f, 18.2f); close()
            moveTo(11.6f, 8.6f); lineTo(20f, 8.6f); lineTo(20f, 18.2f); lineTo(11.6f, 18.2f); close()
            moveTo(3.6f, 20.8f); lineTo(20.4f, 20.8f)
        }
    }

    /** A frame inside a frame: look here for something to read. */
    val Target: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShuraTarget",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathData {
                moveTo(3.6f, 3.6f); lineTo(20.4f, 3.6f); lineTo(20.4f, 20.4f); lineTo(3.6f, 20.4f); close()
                moveTo(8.4f, 8.4f); lineTo(15.6f, 8.4f); lineTo(15.6f, 15.6f); lineTo(8.4f, 15.6f); close()
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = PathData {
                moveTo(10.8f, 10.8f); lineTo(13.2f, 10.8f); lineTo(13.2f, 13.2f); lineTo(10.8f, 13.2f); close()
            },
            fill = SolidColor(Color.Black),
        ).build()
    }

    /** Half a tile filled: the appearance switch, drawn as what it changes. */
    val Contrast: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShuraContrast",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathData {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = PathData {
                moveTo(12f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(12f, 19f); close()
            },
            fill = SolidColor(Color.Black),
        ).build()
    }

    /** A letter over a rule: the language of the interface. */
    val Letter: ImageVector by lazy {
        stroke("ShuraLetter") {
            moveTo(3.2f, 18.6f); lineTo(8.4f, 5.4f); lineTo(13.6f, 18.6f)
            moveTo(5.2f, 14.4f); lineTo(11.6f, 14.4f)
            moveTo(15.4f, 18.6f); lineTo(20.8f, 18.6f); lineTo(20.8f, 10.4f)
        }
    }

    /** Stacked rules: a list of chapters. */
    val Series: ImageVector by lazy {
        stroke("ShuraSeries") {
            moveTo(4f, 5.4f); lineTo(20f, 5.4f)
            moveTo(4f, 10.4f); lineTo(20f, 10.4f)
            moveTo(4f, 15.4f); lineTo(20f, 15.4f)
            moveTo(4f, 20.4f); lineTo(13.4f, 20.4f)
        }
    }

    /** One stroke-based glyph, built the same way every time. */
    private inline fun stroke(
        name: String,
        crossinline path: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathData { path() },
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}