package app.pi.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
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
 *
 * [paragraphGap] and [listIndent] are §2.2's two derived spacing rules
 * (`docs/pi-android-ui-spec.md:117`): paragraph gap = 0.55 × line height and
 * list indent = 1 × line height, both taken from the body role (15 / 23, `:98`).
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

    /** §2.2 细则 (`:117`): 0.55 × body line height (23sp) = 12.65dp. */
    val paragraphGap = 12.65.dp

    /** §2.2 细则 (`:117`): 1 × body line height (23sp) = 23dp. */
    val listIndent = 23.dp

    /** §7.1 列表项: 56dp single-line row. */
    val listItemSingle = 56.dp

    /** §7.1 主按钮 40dp inline / 56dp primary. */
    val buttonInline = 40.dp
    val buttonPrimary = 56.dp

    /** §7.1 Chip / Tab 40dp, Chip本身 32dp. */
    val tab = 40.dp
    val chip = 32.dp

    /** §7.3 上下文环 20dp / 2dp stroke; §2.5 error dot 8dp. */
    val contextRing = 20.dp
    val contextRingStroke = 2.dp
    val errorDot = 8.dp

    // ------------------------------------------------- the block-interior scale
    //
    // F29 (`docs/rendering-review.md`): the blocks under `ui/blocks/**` carried 60
    // raw `dp` literals, so two rows that should read as the same component
    // differed by 2dp for no stated reason. These are *the app's own* names for
    // the values those literals already had — no number changes, no spec row is
    // claimed for them (§2.2 and §2.3 define the type and radius scales, not an
    // interior spacing scale). A replacement is therefore a pure rename; a
    // literal that does *not* match one of these is a real deviation and must be
    // reported rather than rounded into a token.

    /** 1dp: hairlines (`HorizontalDivider`, a 1dp border stroke). */
    val hairline = 1.dp

    /** 2dp: the tightest gap — a label to its glyph, a caption to its body. */
    val tiny = 2.dp

    /** 3dp: the state stripe's width in the block chrome. */
    val stripe = 3.dp

    /** 4dp: icon-to-text inside a chip, an error's inline spacer. */
    val small = 4.dp

    /** 6dp: the interior gap of a card (`BlockChrome`'s `Arrangement`). */
    val gutter = 6.dp

    /** 8dp: inline gap between two chips, or a label after a glyph. */
    val inline = 8.dp

    /** 10dp: the accent stripe to its first line of text. */
    val inner = 10.dp

    /** 14dp: the user-message container's padding (§2.3's radius sibling). */
    val bubble = 14.dp

    /** 16dp: a diff's symbol column (§7.4's own number). */
    val symbolColumn = 16.dp

    /** 30dp: a diff's line-number column. */
    val lineNumberColumn = 30.dp

    /**
     * 20dp: the height of the 3dp `AccentStripe` on every *titled card* —
     * hook-message, skill-invocation and branch-summary, whose stripe runs down
     * their header row (spec §7.4 gives those rows no height; 20dp is the row the
     * three of them already drew).
     *
     * F28 (`docs/rendering-review.md`): the error card passed 36dp for the same
     * element and the thinking block passes 32dp. The 32dp one is **spec**, not a
     * choice — §7.4's thinking-block row is "收起：一行 32dp" — so it uses
     * [statusRow]; the 36dp matched nothing (pi draws no stripe at all, so there
     * is no upstream value to copy) and is therefore unified onto this token.
     */
    val accentStripe = 20.dp
}

/**
 * The corner scale of §2.3 (`docs/pi-android-ui-spec.md:122-133`) plus the four
 * component radii that section's sibling tables add (§7.1 buttons and text
 * fields, §7.2 snackbar, §7.3 thumbnail).
 *
 * Every entry names the row it comes from. A component that needs a radius not
 * listed here should add it here rather than inline the number.
 */
@Immutable
object PiShapes {
    /** §2.3 card row (`:126`): tool card, compaction card, extension message. */
    val card = RoundedCornerShape(16.dp)

    /** §2.3 embedded-block row (`:127`) and thumbnail row (`:133`). */
    val cardInner = RoundedCornerShape(12.dp)

    /** §2.3 input row (`:128`): the capsule form. */
    val input = RoundedCornerShape(24.dp)

    /** §2.3 input row (`:128`): the multiline form. */
    val inputMultiline = RoundedCornerShape(16.dp)

    /** §2.3 FAB row (`:130`). */
    val fab = RoundedCornerShape(16.dp)

    /** §2.3 Modal Sheet row (`:131`): top corners only. */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** §2.3 Dialog row (`:132`). */
    val dialog = RoundedCornerShape(28.dp)

    /** §2.3 Chip/badge row (`:129`): fully round. */
    val chip = RoundedCornerShape(percent = 50)
    val badge = RoundedCornerShape(percent = 50)

    /** §7.1 主按钮/次按钮/SegmentedButton: fully round. */
    val button = RoundedCornerShape(percent = 50)

    /** §7.1 文本框: filled field, 16dp. */
    val field = RoundedCornerShape(16.dp)

    /** §7.2 Snackbar (`:753`): 12dp. */
    val snackbar = RoundedCornerShape(12.dp)
}

/**
 * The elevation ladder of §2.4 (`docs/pi-android-ui-spec.md:141-148`).
 *
 * M3's tonal surfaces already carry the level a component sits at; these are the
 * shadow depths that go with it. §2.4 also says not to use pure black — pass the
 * ambient `shadow` colour and keep the alpha low, or in a dark theme prefer the
 * next surface tone instead of a shadow.
 */
@Immutable
object PiElevation {
    /** Level 0 — page background. No shadow (`:143`). */
    val level0 = 0.dp

    /** Level 1 — list card / message block (`:144`). */
    val level1 = 1.dp

    /** Level 2 — floating composer while scrolling (`:145`). */
    val level2 = 2.dp

    /** Level 3 — top bar once scrolled, Chip (`:146`). */
    val level3 = 4.dp

    /** Level 4 — bottom sheet, dialog (`:147`). */
    val level4 = 6.dp

    /** Level 5 — FAB pressed, full-screen viewer; §2.4 pairs it with 32% scrim (`:148`). */
    val level5 = 8.dp

    /** §2.4 (`:148`): the scrim alpha level 5 is specified with. */
    const val level5ScrimAlpha = 0.32f
}

/** Sizes §2.5 and §7.3 fix for icons and illustration (`:155-159`, `:773`). */
@Immutable
object PiIcons {
    /** §2.5 (`:155`): 20dp inline. */
    val inline = 20.dp

    /** §2.5 (`:155`): 24dp navigation. */
    val nav = 24.dp

    /** §2.5 (`:157`): the one dot, shown only for an error. */
    val errorDot = PiSpacing.errorDot

    /** §7.3 空态 (`:772`): 120dp line illustration. */
    val emptyIllustration = 120.dp

    /** §2.5 (`:159`): monochrome line illustration stroke. */
    val illustrationStroke = 1.5.dp
}

/**
 * Motion tokens — the six rows of docs/pi-android-ui-spec.md §2.6
 * (`:165-172`), verbatim, plus the spring damping each spatial row names.
 *
 * | spec §2.6 row | symbol | value |
 * |---|---|---|
 * | `instant` | [instant] | 0 ms |
 * | `fast.spatial` | [fastSpatialMs] / [fastSpatial] | 150 ms, spring damping 0.9 |
 * | `medium.spatial` | [mediumSpatialMs] / [mediumSpatial] | 250 ms, spring damping 0.85 |
 * | `slow.spatial` | [slowSpatialMs] / [slowSpatial] | 400 ms, spring damping 0.8 |
 * | `fast.effects` | [fastEffectsMs] / [fastEffects] | 100 ms linear |
 * | `medium.effects` | [mediumEffectsMs] / [mediumEffects] | 200 ms ease-in-out |
 *
 * The spec's dotted names cannot be Kotlin identifiers, so `fast.spatial` is
 * [fastSpatial]. Each token is exposed three ways for whoever needs it: a `*Ms`
 * number, a ready-made [AnimationSpec], and (for the two effect tokens) the
 * spec's easing. No caller should re-derive the curve.
 *
 * **Overshoot.** §11 (`docs/pi-android-ui-spec.md:874`) forbids `overshoot` above
 * 1.1. A spring overshoots by `exp(-πζ/√(1-ζ²))`, i.e. 0.15 % at ζ=0.9,
 * 0.63 % at 0.85 and 1.5 % at 0.8 — all far inside the limit. Do not damp below
 * ~0.59, which is where 10 % overshoot begins.
 */
@Immutable
object PiMotion {
    /** `instant` — input feedback, press states, scrolling (`:167`). */
    const val instant = 0

    /** `fast.spatial` — block entrance, chip expansion (`:168`). */
    const val fastSpatialMs = 150

    /** `medium.spatial` — sheet slide-up, card expansion (`:169`). */
    const val mediumSpatialMs = 250

    /** `slow.spatial` — full-screen transitions, shared elements (`:170`). */
    const val slowSpatialMs = 400

    /** `fast.effects` — background / alpha transitions (`:171`). */
    const val fastEffectsMs = 100

    /** `medium.effects` — state colours, theme switch (`:172`). */
    const val mediumEffectsMs = 200

    /** `spring(damping 0.9)`, the `fast.spatial` row (`:168`). */
    const val fastSpatialDamping = 0.9f

    /** `spring(damping 0.85)`, the `medium.spatial` row (`:169`). */
    const val mediumSpatialDamping = 0.85f

    /** `spring(damping 0.8)`, the `slow.spatial` row (`:170`). */
    const val slowSpatialDamping = 0.8f

    /** `fast.spatial`: 150 ms spring, damping 0.9 (`:168`). */
    fun <T> fastSpatial(): AnimationSpec<T> = spatial(fastSpatialDamping)

    /** `medium.spatial`: 250 ms spring, damping 0.85 (`:169`). */
    fun <T> mediumSpatial(): AnimationSpec<T> = spatial(mediumSpatialDamping)

    /** `slow.spatial`: 400 ms spring, damping 0.8 (`:170`). */
    fun <T> slowSpatial(): AnimationSpec<T> = spatial(slowSpatialDamping)

    /** `fast.effects`: 100 ms linear (`:171`). */
    fun <T> fastEffects(): AnimationSpec<T> = tween(fastEffectsMs, easing = LinearEasing)

    /** `medium.effects`: 200 ms ease-in-out (`:172`). */
    fun <T> mediumEffects(): AnimationSpec<T> = tween(mediumEffectsMs, easing = FastOutSlowInEasing)

    private fun <T> spatial(dampingRatio: Float): AnimationSpec<T> = spring(
        dampingRatio = dampingRatio,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = null,
    )

    // ------------------------------------------------------------- deprecated
    // Pre-§2.6 spellings, kept only so an older call site still compiles.

    @Deprecated("Use PiMotion.instant (§2.6).", ReplaceWith("PiMotion.instant"))
    const val Instant = 0

    @Deprecated("Use PiMotion.fastEffectsMs (§2.6).", ReplaceWith("PiMotion.fastEffectsMs"))
    const val FastEffects = 100

    @Deprecated("Use PiMotion.fastSpatialMs (§2.6).", ReplaceWith("PiMotion.fastSpatialMs"))
    const val FastSpatial = 150

    @Deprecated("Use PiMotion.mediumEffectsMs (§2.6).", ReplaceWith("PiMotion.mediumEffectsMs"))
    const val Base = 200

    @Deprecated("Use PiMotion.mediumSpatialMs (§2.6).", ReplaceWith("PiMotion.mediumSpatialMs"))
    const val MediumSpatial = 250

    @Deprecated("Use PiMotion.slowSpatialMs (§2.6).", ReplaceWith("PiMotion.slowSpatialMs"))
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
    /**
     * The same roles with every size shifted by [deltaSp].
     *
     * This is what the `app.appearance.fontScaleDelta` setting drives: the base
     * sizes above are pi's own rhythm (body 15/23, meta 11.5/16), and the setting
     * only nudges them, exactly as its description promises. Line heights move
     * with the size so the transcript's leading does not collapse.
     */
    fun scaled(deltaSp: Int): PiTextStyles = if (deltaSp == 0) this else PiTextStyles(
        meta = meta.shifted(deltaSp),
        mono = mono.shifted(deltaSp),
        monoSmall = monoSmall.shifted(deltaSp),
    )

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

/** Shift a role's size and its leading together, never below 1sp. */
private fun TextStyle.shifted(deltaSp: Int): TextStyle = copy(
    fontSize = (fontSize.value + deltaSp).coerceAtLeast(1f).sp,
    lineHeight = (lineHeight.value + deltaSp).coerceAtLeast(1f).sp,
)

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

private fun piTypography(textScaleDelta: Int = 0): Typography {
    fun TextStyle.shift(): TextStyle = copy(
        fontSize = (fontSize.value + textScaleDelta).coerceAtLeast(1f).sp,
        lineHeight = (lineHeight.value + textScaleDelta).coerceAtLeast(1f).sp,
    )
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
        ).shift(),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
        ).shift(),
        titleMedium = base.titleMedium.copy(
            fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        ).shift(),
        titleSmall = base.titleSmall.copy(
            fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ).shift(),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal,
        ).shift(),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
        ).shift(),
        labelLarge = base.labelLarge.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        ).shift(),
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        ).shift(),
        labelSmall = base.labelSmall.copy(
            fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
        ).shift(),
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
    /** `app.appearance.fontScaleDelta`: pi's sizes nudged by the user. */
    textScaleDelta: Int = 0,
    content: @Composable () -> Unit,
) {
    val styles = remember(textScaleDelta) { PiTextStyles.Default.scaled(textScaleDelta) }
    CompositionLocalProvider(
        LocalPiPalette provides palette,
        LocalPiTextStyles provides styles,
    ) {
        MaterialTheme(
            colorScheme = palette.colorScheme(),
            typography = piTypography(textScaleDelta),
            shapes = piShapes(),
            content = content,
        )
    }
}
