package app.shura.manga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shura's palette: violet on near-black, the way a manga shelf reads.
 *
 * Crimson on pure black was legible and sharp, and it looked like a warning
 * screen: the accent was doing the job of "this needs your attention" on every
 * screen at once. A single violet carries the same state without shouting, and
 * the surfaces are lifted off pure black so cards separate from the background
 * by depth rather than by a hairline.
 *
 * Colour is not load-bearing on its own: every accent below is paired with a
 * label or an icon, so the UI still reads without it.
 */
val Violet = Color(0xFF7C6BE8)
val VioletBright = Color(0xFFC9BCFF)
val Ink = Color(0xFF141218)
val InkRaised = Color(0xFF211F27)
val InkLine = Color(0xFF38343F)
val Bone = Color(0xFFEAE7F0)
val BoneDim = Color(0xFFA9A3B4)

private val ShuraDark = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF1B1236),
    primaryContainer = Color(0xFF4A3C8F),
    onPrimaryContainer = VioletBright,
    secondary = BoneDim,
    onSecondary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Ink,
    onSurface = Bone,
    surfaceVariant = InkRaised,
    onSurfaceVariant = BoneDim,
    outline = InkLine,
    error = Color(0xFFFF6B6B),
    onError = Ink,
)

/**
 * The light scheme is not a pale inversion of the dark one.
 *
 * The reader is a dark room by default and this scheme exists for daylight and
 * for the accessibility setting that asks for it. It keeps the violet, which is
 * the identity, on a paper surface instead of washing it out.
 */
private val ShuraLight = lightColorScheme(
    primary = Color(0xFF5B48C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B1236),
    secondary = Color(0xFF625B71),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFEDE7F4),
    onSurfaceVariant = Color(0xFF4A4458),
    outline = Color(0xFFCAC4D0),
    error = Color(0xFFBA1A1A),
)

/** The language the UI is currently speaking, for layout direction. */
enum class UiLanguage(val code: String) { ARABIC("ar"), ENGLISH("en") }

val LocalUiLanguage = compositionLocalOf { UiLanguage.ARABIC }

/** True when the UI should lay out right-to-left. */
val LocalIsRtl: Boolean
    @Composable get() = LocalUiLanguage.current == UiLanguage.ARABIC

@Composable
fun ShuraTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) ShuraDark else ShuraLight,
        // The palette and the shapes are the identity and are left alone. Only
        // the type is tuned here, and only where the default scale was built for
        // a screen these screens are not: titles get a tighter line, because a
        // work's name is read at a glance and a loose one wraps early in Arabic;
        // and titleSmall is set explicitly because a tile title, a chapter row
        // and a row of settings all use it, and inheriting a scale that was
        // never designed for a cover grid is how those three drifted apart.
        typography = MaterialTheme.typography.copy(
            headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, letterSpacing = (-0.1).sp),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
            titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
