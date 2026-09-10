package app.pi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing and shape scale.
 *
 * [unit] is pi's own rhythm: its terminal UI and its HTML exporter both derive
 * every block gap from one `--line-height` unit (18px). Keeping that number as
 * the block gap is what makes the stream "feel" like pi even though the
 * typography and surfaces are native Android. See docs/pi-android-ui-spec.md §2.3.
 */
@Immutable
object PiSpacing {
    val unit = 18.dp
    val screen = 16.dp
    val card = 12.dp
    val touchTarget = 48.dp
    val appBar = 56.dp
    val statusRow = 32.dp
    val bottomBar = 64.dp
    val listItem = 72.dp
}

@Immutable
object PiShapes {
    val card = RoundedCornerShape(16.dp)
    val cardInner = RoundedCornerShape(12.dp)
    val input = RoundedCornerShape(24.dp)
    val inputMultiline = RoundedCornerShape(16.dp)
    val fab = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val dialog = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(percent = 50)
    val badge = RoundedCornerShape(percent = 50)
}

/** Motion tokens. Only these durations are used anywhere in the app. */
@Immutable
object PiMotion {
    const val Instant = 0
    const val FastEffects = 100
    const val FastSpatial = 150
    const val Base = 200
    const val MediumSpatial = 250
    const val SlowSpatial = 400
}

/**
 * Text styles that Material 3's [Typography] has no slot for: pi's footer/meta
 * line and the two monospace roles. Everything a *human* wrote uses the system
 * font; everything a *machine* emitted uses monospace — that split is the app's
 * rule #7 (docs/pi-android-ui-spec.md §1).
 */
@Immutable
data class PiTextStyles(
    val meta: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
) {
    companion object {
        val Default = PiTextStyles(
            meta = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            mono = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
            monoSmall = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            ),
        )
    }
}

/**
 * Numbers in the status row must not jitter as they tick. Compose's
 * `FontFeatureSetting("tnum")` is unavailable on all API levels we support via
 * FontFamily.Default, so token/cost text uses the monospace family instead.
 */
val PiTextStyles.numeric: TextStyle get() = mono

val LocalPiPalette = staticCompositionLocalOf { PiPalette.Dark }
val LocalPiTextStyles = staticCompositionLocalOf { PiTextStyles.Default }

/** Access the raw pi token set: `PiTheme.palette.toolSuccessBg`. */
object PiTheme {
    val palette: PiPalette
        @Composable @ReadOnlyComposable get() = LocalPiPalette.current

    val text: PiTextStyles
        @Composable @ReadOnlyComposable get() = LocalPiTextStyles.current
}

/** Blend helper for the surface ladder below. */
private fun Color.elevate(toward: Color, amount: Float): Color = lerp(this, toward, amount)

private fun PiPalette.colorScheme(): androidx.compose.material3.ColorScheme {
    val scheme = if (pageBg.luminanceIsDark()) darkColorScheme() else lightColorScheme()
    val onAccent = if (accent.luminanceIsDark()) Color.White else Color.Black
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = selectedBg,
        onPrimaryContainer = text,
        inversePrimary = accent,

        secondary = borderAccent,
        onSecondary = onAccent,
        secondaryContainer = cardBg,
        onSecondaryContainer = text,

        tertiary = customMessageLabel,
        onTertiary = onAccent,
        tertiaryContainer = customMessageBg,
        onTertiaryContainer = customMessageText,

        background = pageBg,
        onBackground = text,
        surface = pageBg,
        onSurface = text,
        surfaceVariant = cardBg,
        onSurfaceVariant = muted,
        surfaceTint = accent,

        // Elevation ladder: pi only defines pageBg and cardBg, so the middle
        // tones are derived by blending the card toward the foreground. Works
        // for both themes and keeps every surface inside pi's palette.
        surfaceContainerLowest = pageBg,
        surfaceContainerLow = cardBg,
        surfaceContainer = cardBg.elevate(text, 0.03f),
        surfaceContainerHigh = cardBg.elevate(text, 0.06f),
        surfaceContainerHighest = cardBg.elevate(text, 0.10f),
        surfaceBright = cardBg.elevate(text, 0.12f),
        surfaceDim = pageBg.elevate(text, 0.02f),

        outline = borderMuted,
        outlineVariant = borderMuted.copy(alpha = 0.55f),

        error = error,
        onError = onAccent,
        errorContainer = toolErrorBg,
        onErrorContainer = text,

        scrim = Color.Black,
        inverseSurface = text,
        inverseOnSurface = pageBg,
    )
}

private fun Color.luminanceIsDark(): Boolean {
    val r = red
    val g = green
    val b = blue
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 0.5f
}

private fun piTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = base.titleSmall.copy(
            fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
        ),
        labelLarge = base.labelLarge.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        ),
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
        ),
    )
}

private fun piShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = PiShapes.card,
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PiTheme(
    dark: Boolean = true,
    palette: PiPalette = if (dark) PiPalette.Dark else PiPalette.Light,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPiPalette provides palette,
        LocalPiTextStyles provides PiTextStyles.Default,
    ) {
        MaterialTheme(
            colorScheme = palette.colorScheme(),
            typography = piTypography(),
            shapes = piShapes(),
            content = content,
        )
    }
}
