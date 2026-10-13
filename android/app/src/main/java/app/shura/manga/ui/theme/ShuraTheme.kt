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
import androidx.compose.ui.unit.sp

/**
 * Shura's own identity: ink black, crimson, bone.
 *
 * Deliberately not a reskin of any other reader. The surface is near-black so a
 * page image is the brightest thing on screen, the accent is a single crimson
 * used only for state the reader must notice, and everything else is bone so
 * type stays legible over dark chrome.
 *
 * Colour is not load-bearing on its own: every accent below is paired with a
 * label or an icon, so the UI still reads without it.
 */
val Crimson = Color(0xFFC8102E)
val CrimsonBright = Color(0xFFFF2D55)
val Ink = Color(0xFF0B0B0D)
val InkRaised = Color(0xFF15151A)
val InkLine = Color(0xFF2A2A32)
val Bone = Color(0xFFF2F0EC)
val BoneDim = Color(0xFFA9A6A0)

private val ShuraDark = darkColorScheme(
    primary = Crimson,
    onPrimary = Bone,
    primaryContainer = Crimson,
    onPrimaryContainer = Bone,
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
 * for the accessibility setting that asks for it. It keeps the crimson, which
 * is the identity, on a paper surface instead of washing it out.
 */
private val ShuraLight = lightColorScheme(
    primary = Color(0xFF9B0C24),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF3F0009),
    secondary = Color(0xFF5B5C60),
    background = Color(0xFFFDFBFA),
    onBackground = Color(0xFF17161A),
    surface = Color(0xFFFDFBFA),
    onSurface = Color(0xFF17161A),
    surfaceVariant = Color(0xFFEFEBEA),
    onSurfaceVariant = Color(0xFF4B4748),
    outline = Color(0xFFC9C4C5),
    error = Color(0xFFBA1A1A),
)

/** The language the UI is currently speaking, for layout direction. */
enum class UiLanguage(val code: String) { ARABIC("ar"), ENGLISH("en") }

val LocalUiLanguage = compositionLocalOf { UiLanguage.ENGLISH }

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
        typography = MaterialTheme.typography.copy(
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}
