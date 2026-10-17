package app.shura.manga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shura's identity: crimson on near-black, the way a page reads in a dark room.
 *
 * The accent is crimson and the surfaces are almost black, and that is the whole
 * idea. A manga page is ink and paper, so the app that holds it should not be a
 * bright surface with a coloured button on it; the page owns the screen and the
 * app is the frame around it. Crimson is the one colour that reads as ink that
 * spilled - it marks state without the "this needs your attention" alarm a
 * saturated red would raise on every screen at once, and against a near-black
 * ramp it separates cards by hue as well as by depth.
 *
 * The ramp is deliberately narrow and deliberately neutral. Nothing here is
 * tinted towards the accent except the accent itself: a violet-tinted "neutral"
 * grey is how a palette ends up looking washed out once a photograph is on
 * screen, and the covers are the screen. Bone is warm because paper is warm.
 *
 * Colour is not load-bearing on its own: every accent below is paired with a
 * label, an icon or a shape, so the UI still reads with the colour taken away.
 */

/** The accent as a fill: progress bars, pills, selection borders. */
val Crimson = Color(0xFFE0323F)

/** The accent as text and icons on ink. Bright enough to clear 4.5:1 on [Ink]. */
val CrimsonBright = Color(0xFFFF5C6B)

/** A pressed or container crimson: deep enough for bone text to sit on. */
val CrimsonDeep = Color(0xFF8C1622)

/** Crimson thinned to a wash, for a selected row on a near-black surface. */
val CrimsonWash = Color(0xFF2A1116)

/** The background, and the reader's own black. */
val Ink = Color(0xFF0A0A0C)

/** A raised surface: cards, rows, the search field. */
val InkRaised = Color(0xFF15151A)

/** Outlines and dividers: visible, never a hairline that disappears. */
val InkLine = Color(0xFF2B2B33)

/** Primary text: warm white, so it reads as paper rather than as a backlight. */
val Bone = Color(0xFFF3F0ED)

/** Secondary text, and everything said quietly. */
val BoneDim = Color(0xFFA5A09A)

/**
 * The near-black ramp, stepped.
 *
 * Material's own surface containers are derived from the default palette and
 * carry its hue, so they are set here: without this, a `Card` or a sheet would
 * come out a shade of violet-grey that does not exist anywhere else in the app.
 */
private val ShuraDark = darkColorScheme(
    primary = CrimsonBright,
    onPrimary = Color(0xFF26060B),
    primaryContainer = CrimsonDeep,
    onPrimaryContainer = Color(0xFFFFD8DC),
    inversePrimary = Crimson,
    secondary = BoneDim,
    onSecondary = Ink,
    secondaryContainer = InkRaised,
    onSecondaryContainer = Bone,
    tertiary = Crimson,
    onTertiary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Ink,
    onSurface = Bone,
    surfaceVariant = InkRaised,
    onSurfaceVariant = BoneDim,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Color(0xFF101014),
    surfaceContainer = Color(0xFF141419),
    surfaceContainerHigh = Color(0xFF1A1A20),
    surfaceContainerHighest = Color(0xFF212128),
    surfaceTint = Crimson,
    outline = InkLine,
    outlineVariant = Color(0xFF1F1F26),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0707),
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFF232328),
    inverseOnSurface = Color(0xFFEDEDF0),
    scrim = Color.Black,
)

/**
 * The light scheme is not a pale inversion of the dark one.
 *
 * The reader is a dark room by default and this scheme exists for daylight and
 * for the accessibility setting that asks for it. It is warm paper rather than
 * white, and the crimson is deepened rather than brightened: a light accent has
 * to carry more weight against a light surface or it disappears, and the same
 * crimson that reads as ink on black reads as a stamp on paper.
 */
private val ShuraLight = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    inversePrimary = Color(0xFFFF5C6B),
    secondary = Color(0xFF7A716E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2EDEA),
    onSecondaryContainer = Color(0xFF33201D),
    tertiary = Color(0xFF8C1622),
    onTertiary = Color.White,
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF1B1B1D),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFFF2EDEA),
    onSurfaceVariant = Color(0xFF4C4542),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF7F5),
    surfaceContainer = Color(0xFFF9F2EF),
    surfaceContainerHigh = Color(0xFFF4ECE9),
    surfaceContainerHighest = Color(0xFFEEE5E2),
    surfaceTint = Color(0xFFB3261E),
    outline = Color(0xFFCFC7C3),
    outlineVariant = Color(0xFFE6DFDB),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF4F1EE),
    scrim = Color.Black,
)

/**
 * The rhythm every screen measures itself against.
 *
 * One set of numbers rather than a dp literal in each file: "consistent gutters"
 * and "the same rhythm" are only true if the values are shared, and the moment
 * each screen picks its own they start to differ by a dp nobody can see but
 * everybody can feel.
 */
object ShuraSpacing {
    /** Screen edge to content. */
    val screen = 16.dp

    /** Between two covers in the library grid. */
    val grid = 14.dp

    /** Between two stacked rows. */
    val row = 10.dp

    /** Inside a card or a row. */
    val inside = 14.dp
}

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
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp),
            titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
            labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
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
