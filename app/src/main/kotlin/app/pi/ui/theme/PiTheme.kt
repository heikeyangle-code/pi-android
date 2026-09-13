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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.R

/**
 * Spacing and shape scale.
 *
 * [unit] is pi's own rhythm: its terminal UI and its HTML exporter both derive
 * every block gap from one `--line-height` unit (18px). Keeping that number as
 * the block gap is what makes the stream "feel" like pi even though the
 * typography and surfaces are native Android.
 */
@Immutable
object PiSpacing {
    val unit = 18.dp
    val statusRow = 32.dp

    val errorDot = 8.dp

    // ------------------------------------------------------- the v2 page scale
    //
    // These lived in `theme/PiLayout.kt` as a second object until the final audit
    // folded them in here and deleted that file: two objects declaring the same
    // page numbers is two answers to "how wide is the margin". Every value is
    // `06 §2` 的原文，一行一个出处：
    //
    //   pageHorizontal     「屏水平 14px」
    //   cardPadding        「卡片内 12px」
    //   cardPaddingLoose   「当前目录卡 / 设备桥卡 14px」
    //   groupHeaderGap     「分组头 padding:0 14px; margin-bottom:7px」
    //   groupGap           「分组块 marginTop:18px」
    //   scrollBottom       「滚动区底部留 14–18px」取上端
    //   blockGap           「块间距 8」
    //   topBarHeight       「顶栏：高 48」
    //   bottomBarHeight    「底栏：高 56」

    /** `06 §2`「屏水平 14px」: the page margin every v2 screen uses. */
    val pageHorizontal = 14.dp

    /** `06 §2`「卡片内 12px」: the interior padding of a default card. */
    val cardPadding = 12.dp

    /** `06 §2`「当前目录卡 / 设备桥卡 14px」. */
    val cardPaddingLoose = 14.dp

    /** `06 §2`「分组头 padding:0 14px; margin-bottom:7px」. */
    val groupHeaderGap = 7.dp

    /** `06 §2`「分组块 marginTop:18px」. */
    val groupGap = 18.dp

    /** `06 §2`「滚动区底部留 14–18px」. */
    val scrollBottom = 18.dp

    /** `06 §2`: the transcript's block rhythm (`块间距 8`). */
    val blockGap = 8.dp

    /** `06 §2`「顶栏：高 48」. */
    val topBarHeight = 48.dp

    /** `06 §2`「底栏：高 56」. */
    val bottomBarHeight = 56.dp

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
 * The bundled machine-language face: JetBrains Mono v2.304, static `Regular` +
 * `Bold` TTFs from the upstream release (`app/src/main/res/font/`), licensed
 * OFL-1.1 (the text ships in the in-app licence list).
 *
 * Static instances rather than the release's
 * `fonts/variable/JetBrainsMono[wght].ttf`: this app asks for exactly two weights,
 * and a static pair needs no variation-axis resolution at runtime.
 *
 * Bold is not decorative — `buildPiCodeText` marks emphasised code spans
 * `FontWeight.Bold`, so a family without a Bold entry would have those spans
 * synthesised (faux-bold, wrong advance width) inside the code block.
 *
 * Use this (or the [PiTextStyles] `mono` / `monoSmall` roles, which are built on
 * it) for anything a *machine* emitted. `FontFamily.Monospace` is deliberately not
 * used outside the terminal: it resolves to whatever face the ROM picked, which is
 * why fixed columns sized from an advance width used to shift between devices.
 */
val PiMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * Text styles that Material 3's [Typography] has no slot for: the transcript's
 * prose role, pi's footer/meta line and the machine roles. Everything a *human*
 * wrote uses the system font; everything a *machine* emitted uses monospace —
 * that split is the app's rule #7 (docs/pi-android-ui-spec.md §1).
 *
 * ## The five sizes, and why they are these five (`06 §2` 字号 5 档)
 *
 * v2 fixes five sizes for the whole product — 12 / 13 / 14 / 15 / 17 — and
 * `01-design-spec.md` §2 makes the bottom two a floor (「正文 ≥14px、标签/注释
 * ≥12px」). The roles below cover the transcript's share of that scale; the two
 * larger steps (15 row titles, 17 screen titles) come from M3's
 * `titleSmall`/`titleMedium` (`piTypography`).
 *
 *   meta       12/18  labels, meta lines, machine readings (v2 `t12`)
 *   monoSmall  12/18  the same size in the machine face (v2 `mono t12`)
 *   mono       13/20  machine body: commands, tool output, paths (v2 `t13`)
 *   code       13/19  fences and diffs — v2 overrides those two to a fixed 19
 *   prose      14/23  human body: chat text, error sentences (v2 `t14`, 1.62)
 *
 * B7 (`numeric` / 字号档位收敛): [meta] and [monoSmall] used to be 11.5sp, which
 * is **under** the spec's own 12px label floor and is a sixth step v2 never
 * draws; [prose] is new because the transcript's human body was borrowing M3's
 * `bodyLarge` (15sp), which is v2's *row-title* step, not its chat-text step.
 */
@Immutable
data class PiTextStyles(
    val meta: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
    /** v2 `t14`: the transcript's human body (chat prose, an error's sentence). */
    val prose: TextStyle,
    /** v2's code/diff override: `mono` at a fixed 13/19. */
    val code: TextStyle,
) {
    /**
     * The same roles with every size shifted by [deltaSp].
     *
     * This is what the `app.appearance.fontScaleDelta` setting drives: the base
     * sizes above are v2's own five steps, and the setting only nudges them,
     * exactly as its description promises. Line heights move with the size so the
     * transcript's leading does not collapse.
     */
    fun scaled(deltaSp: Int): PiTextStyles = if (deltaSp == 0) this else PiTextStyles(
        meta = meta.shifted(deltaSp),
        mono = mono.shifted(deltaSp),
        monoSmall = monoSmall.shifted(deltaSp),
        prose = prose.shifted(deltaSp),
        code = code.shifted(deltaSp),
    )

    companion object {
        val Default = PiTextStyles(
            meta = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
            ),
            mono = TextStyle(
                fontFamily = PiMonoFamily,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
            monoSmall = TextStyle(
                fontFamily = PiMonoFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            prose = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Normal,
            ),
            code = TextStyle(
                fontFamily = PiMonoFamily,
                fontSize = 13.sp,
                lineHeight = 19.sp,
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
