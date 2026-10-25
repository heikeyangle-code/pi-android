package app.pi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The `system` theme: pi's colours derived from the terminal's own theme.
 *
 * A port of `packages/coding-agent/src/modes/interactive/theme/system-theme.ts:1-620`.
 * pi's `system` theme is **not a built-in palette** — it is generated from what the
 * terminal reports about itself, so a phone that talks to a user's terminal can show
 * that terminal's colours instead of pi's defaults. Every token belongs to a colour
 * *family* (its hue) and has *contrast rules* (the lightness it needs on the surfaces
 * it is drawn on):
 *
 *  - **hue and saturation** come from the family's ANSI palette slot when the terminal
 *    reports a palette, or from the family's own hue when it does not;
 *  - **lightness** comes from the rules alone — a polynomial "target lightness" curve
 *    per contrast level, evaluated against the lightness of the surface below the token;
 *  - colours are built in **OKHSL**, whose saturation is relative to the sRGB gamut, so
 *    a family's saturation means the same thing at every hue, and it fades toward grey
 *    near black and white;
 *  - when even the weakest set of levels does not fit (mid-grey backgrounds), the levels
 *    are **relaxed** by the smallest amount that does, found by bisection.
 *
 * The three tiers, as in pi (`system-theme.ts:1-18`):
 *  1. background and palette — hues from the palette, lightness from the background;
 *  2. background only — the families' own hues, lightness from the background;
 *  3. nothing — pi hands out ANSI indices and lets the terminal paint them.
 *
 * **Android has no terminal to paint them**, so tier 3 here becomes "derive from pi's
 * default ANSI 16 colours on pi's default background": a phone always has to produce
 * concrete colours, and that pair is the same fallback pi itself uses when it has to
 * resolve an index (`BASIC_COLORS`, `packages/tui/src/colors.ts:155-171`).
 *
 * Nothing in this file is Android-specific except the final hand-off to
 * [PiPalette] — the derivation works on `0xRRGGBB` ints, so the golden tests run on a
 * bare JVM.
 */

// ============================================================================
// Input: what a terminal reports about itself
// ============================================================================

/**
 * A terminal's own colours: its background, its 16 ANSI colours (`0xRRGGBB`), and
 * optionally the foreground it writes body text in.
 *
 * This is pi's `SystemThemeInput` (`system-theme.ts:332-346`) minus the fields that
 * have no meaning here: `saturation` is a parameter of the derivation functions
 * (pi uses it for the grey first frame before colours arrive) and `appearanceHint` is
 * only needed by tier 3, which on Android is [DEFAULT_BACKGROUND].
 *
 * [ansi] must hold 16 entries for the palette tier; a different length is treated as
 * "no palette" rather than as an error, because that is what pi does
 * (`system-theme.ts:412`: `palette.length === 16 ? … : undefined`) and because a
 * half-reported palette must still produce a theme.
 *
 * [foreground] is optional and defaulted, so `PiAnsiPalette(background, ansi)` is the
 * two-argument shape callers expect. When it is present, pi draws body text
 * (`text`, `userMessageText`, `toolTitle`) in the terminal's own foreground wherever
 * that foreground is clearly stronger than muted text; without it those tokens get the
 * computed neutral emphasis colour instead.
 */
data class PiAnsiPalette(
    val background: Int,
    val ansi: IntArray,
    val foreground: Int? = null,
) {

    /** Whether [ansi] is a full 16-colour palette, i.e. whether tier 1 applies. */
    val hasPalette: Boolean get() = ansi.size == 16

    // `IntArray` in a data class compares by identity, which would make two equal
    // palettes unequal and quietly break any caller that caches or compares one.
    // Content equality is the only sane meaning here.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PiAnsiPalette) return false
        return background == other.background &&
            foreground == other.foreground &&
            ansi.contentEquals(other.ansi)
    }

    override fun hashCode(): Int =
        (background * 31 + (foreground ?: 0)) * 31 + ansi.contentHashCode()

    override fun toString(): String =
        "PiAnsiPalette(background=#${hex(background)}, foreground=${foreground?.let { "#${hex(it)}" }}, " +
            "ansi=[${ansi.joinToString(", ") { "#${hex(it)}" }}])"
}

/** pi's default terminal background for the "nothing reported" tier (`0x1E1E1E`). */
const val DEFAULT_BACKGROUND: Int = 0x1E1E1E

/**
 * pi's default ANSI 16 (`BASIC_COLORS`, `packages/tui/src/colors.ts:155-171`): what pi
 * resolves indices 0-15 to when it has to resolve them itself.
 *
 * These are **pi's table, not the classic xterm one** — they differ at 4 (`#000080`),
 * 7 (`#c0c0c0`), 8 (`#808080`) and 12 (`#0000FF`). Matching pi's table keeps this
 * app's approximation the same approximation pi makes.
 */
val DEFAULT_ANSI: IntArray = intArrayOf(
    0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xC0C0C0,
    0x808080, 0xFF0000, 0x00FF00, 0xFFFF00, 0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
)

/** Tier 3's input: pi's default background with pi's default ANSI palette. */
fun defaultAnsiPalette(): PiAnsiPalette = PiAnsiPalette(DEFAULT_BACKGROUND, DEFAULT_ANSI.copyOf())

/** Whether a terminal is dark or light (`ThemeAppearance` in pi's theme layer). */
enum class PiSystemAppearance { DARK, LIGHT }

// ============================================================================
// Recipe: colour families and their tokens
// ============================================================================

/**
 * A family's OKHSL hue and saturation, and the ANSI slot it takes its hue and
 * saturation from: `max` at mid lightness, falling toward `min` at black and white
 * (`FAMILIES`, `system-theme.ts:47-63`).
 */
internal enum class PiFamily(
    val hue: Double,
    val minSaturation: Double,
    val maxSaturation: Double,
    val slot: Int,
) {
    NEUTRAL(231.49, 0.02, 0.08, 8),
    BLUE(231.49, 0.1, 0.68, 4),
    GREEN(158.68, 0.1, 0.76, 2),
    RED(20.0, 0.1, 0.92, 1),
    YELLOW(82.36, 0.5, 1.0, 3),
    ORANGE(52.0, 0.12, 0.85, 3),
    VIOLET(295.0, 0.2, 0.6, 5),
    CALAMINE(202.43, 0.1, 0.74, 6),
    THINKING_SLATE(231.49, 0.08, 0.2, 4),
    THINKING_BLUE(231.49, 0.2, 0.45, 4),
    THINKING_PERIWINKLE(263.25, 0.3, 0.6, 6),
    THINKING_VIOLET(295.0, 0.4, 0.75, 5),
    THINKING_MAGENTA(337.5, 0.5, 0.85, 13),
    THINKING_RED(20.0, 0.95, 1.0, 1),
}

/**
 * A surface the derivation draws on: the background, or a token that is itself a
 * background for other tokens (`TOOL_PANELS`, `MESSAGE_PANELS`, the scrollbar track).
 */
internal enum class PiSurface {
    BACKGROUND,
    USER_MESSAGE_BG,
    TOOL_PENDING_BG,
    TOOL_SUCCESS_BG,
    TOOL_ERROR_BG,
    SELECTED_BG,
    SEARCH_MATCH_BG,
    CUSTOM_MESSAGE_BG,
    SCROLLBAR_TRACK,
}

/** The token a non-[PiSurface.BACKGROUND] surface is solved as. */
internal fun PiSurface.token(): PiSystemToken? = when (this) {
    PiSurface.BACKGROUND -> null
    PiSurface.USER_MESSAGE_BG -> PiSystemToken.USER_MESSAGE_BG
    PiSurface.TOOL_PENDING_BG -> PiSystemToken.TOOL_PENDING_BG
    PiSurface.TOOL_SUCCESS_BG -> PiSystemToken.TOOL_SUCCESS_BG
    PiSurface.TOOL_ERROR_BG -> PiSystemToken.TOOL_ERROR_BG
    PiSurface.SELECTED_BG -> PiSystemToken.SELECTED_BG
    PiSurface.SEARCH_MATCH_BG -> PiSystemToken.SEARCH_MATCH_BG
    PiSurface.CUSTOM_MESSAGE_BG -> PiSystemToken.CUSTOM_MESSAGE_BG
    PiSurface.SCROLLBAR_TRACK -> PiSystemToken.SCROLLBAR_TRACK
}

/**
 * pi's 56 theme tokens, each with the family that gives it a hue and saturation, the
 * ANSI slot it overrides (only where two tokens would otherwise share a hue,
 * `TOKEN_SLOTS`, `system-theme.ts:171`), and the surface it is solved as.
 *
 * Declaration order is pi's `TOKEN_FAMILIES` order (`system-theme.ts:68-163`), which
 * the golden tests pin: the expected colours for a case are one line in this order, so
 * a reordering here would shift every value against its token.
 */
enum class PiSystemToken(
    /** pi's own name for the token, as it appears in a theme file. */
    val wire: String,
    private val family: PiFamily,
    private val slotOverride: Int? = null,
    private val surface: PiSurface? = null,
) {
    SELECTED_BG("selectedBg", PiFamily.BLUE, surface = PiSurface.SELECTED_BG),
    SEARCH_MATCH_BG("searchMatchBg", PiFamily.ORANGE, slotOverride = 3, surface = PiSurface.SEARCH_MATCH_BG),
    USER_MESSAGE_BG("userMessageBg", PiFamily.BLUE, surface = PiSurface.USER_MESSAGE_BG),
    CUSTOM_MESSAGE_BG("customMessageBg", PiFamily.VIOLET, surface = PiSurface.CUSTOM_MESSAGE_BG),
    TOOL_PENDING_BG("toolPendingBg", PiFamily.NEUTRAL, surface = PiSurface.TOOL_PENDING_BG),
    TOOL_SUCCESS_BG("toolSuccessBg", PiFamily.GREEN, surface = PiSurface.TOOL_SUCCESS_BG),
    TOOL_ERROR_BG("toolErrorBg", PiFamily.RED, surface = PiSurface.TOOL_ERROR_BG),
    TEXT("text", PiFamily.NEUTRAL),
    USER_MESSAGE_TEXT("userMessageText", PiFamily.NEUTRAL),
    CUSTOM_MESSAGE_TEXT("customMessageText", PiFamily.NEUTRAL),
    TOOL_TITLE("toolTitle", PiFamily.NEUTRAL),
    SYNTAX_OPERATOR("syntaxOperator", PiFamily.NEUTRAL),
    SYNTAX_PUNCTUATION("syntaxPunctuation", PiFamily.NEUTRAL),
    MUTED("muted", PiFamily.NEUTRAL),
    DIM("dim", PiFamily.NEUTRAL),
    THINKING_TEXT("thinkingText", PiFamily.NEUTRAL),
    TOOL_OUTPUT("toolOutput", PiFamily.NEUTRAL),
    MD_LINK_URL("mdLinkUrl", PiFamily.NEUTRAL),
    MD_QUOTE("mdQuote", PiFamily.NEUTRAL),
    MD_QUOTE_BORDER("mdQuoteBorder", PiFamily.NEUTRAL),
    MD_HR("mdHr", PiFamily.NEUTRAL),
    MD_CODE_BLOCK_BORDER("mdCodeBlockBorder", PiFamily.NEUTRAL),
    TOOL_DIFF_CONTEXT("toolDiffContext", PiFamily.NEUTRAL),
    SYNTAX_COMMENT("syntaxComment", PiFamily.NEUTRAL),
    SCROLLBAR_TRACK("scrollbarTrack", PiFamily.NEUTRAL, surface = PiSurface.SCROLLBAR_TRACK),
    SCROLLBAR_THUMB("scrollbarThumb", PiFamily.NEUTRAL),
    SEARCH_MATCH_TEXT("searchMatchText", PiFamily.NEUTRAL),
    BORDER_MUTED("borderMuted", PiFamily.NEUTRAL),
    ACCENT("accent", PiFamily.VIOLET),
    BORDER_ACCENT("borderAccent", PiFamily.VIOLET),
    CUSTOM_MESSAGE_LABEL("customMessageLabel", PiFamily.VIOLET),
    MD_CODE("mdCode", PiFamily.VIOLET),
    MD_LIST_BULLET("mdListBullet", PiFamily.VIOLET),
    SYNTAX_TYPE("syntaxType", PiFamily.VIOLET),
    BORDER("border", PiFamily.BLUE),
    MD_LINK("mdLink", PiFamily.BLUE),
    SYNTAX_KEYWORD("syntaxKeyword", PiFamily.BLUE),
    SYNTAX_VARIABLE("syntaxVariable", PiFamily.CALAMINE),
    SUCCESS("success", PiFamily.GREEN),
    MD_CODE_BLOCK("mdCodeBlock", PiFamily.GREEN),
    TOOL_DIFF_ADDED("toolDiffAdded", PiFamily.GREEN),
    BASH_MODE("bashMode", PiFamily.GREEN),
    SYNTAX_NUMBER("syntaxNumber", PiFamily.GREEN, slotOverride = 5),
    ERROR("error", PiFamily.RED),
    TOOL_DIFF_REMOVED("toolDiffRemoved", PiFamily.RED),
    WARNING("warning", PiFamily.YELLOW),
    MD_HEADING("mdHeading", PiFamily.YELLOW),
    SYNTAX_FUNCTION("syntaxFunction", PiFamily.YELLOW),
    SYNTAX_STRING("syntaxString", PiFamily.ORANGE, slotOverride = 2),
    THINKING_OFF("thinkingOff", PiFamily.NEUTRAL),
    THINKING_MINIMAL("thinkingMinimal", PiFamily.THINKING_SLATE),
    THINKING_LOW("thinkingLow", PiFamily.THINKING_BLUE),
    THINKING_MEDIUM("thinkingMedium", PiFamily.THINKING_PERIWINKLE),
    THINKING_HIGH("thinkingHigh", PiFamily.THINKING_VIOLET),
    THINKING_XHIGH("thinkingXhigh", PiFamily.THINKING_MAGENTA),
    THINKING_MAX("thinkingMax", PiFamily.THINKING_RED),
    ;

    /** The ANSI slot this token's hue and saturation come from, honouring the override. */
    internal val slot: Int get() = slotOverride ?: family.slot

    /** The colour family, for the saturation curve. */
    internal val colourFamily: PiFamily get() = family

    /** The surface this token is solved as, if other tokens are drawn on it. */
    internal val surfaceOf: PiSurface? get() = surface
}

// ============================================================================
// Contrast levels and rules
// ============================================================================

/**
 * Target-lightness curve: a polynomial in the surface's OKLab lightness giving the
 * OKLab lightness a token needs on it, plus the range of surface lightness where the
 * level can be reached at all (`Curve`, `system-theme.ts:186-196`). Beyond that range
 * the level is relaxed.
 */
private class PiCurve(val coefficients: DoubleArray, val low: Double, val high: Double)

/**
 * The contrast levels, each with a curve for dark and for light terminals
 * (`LEVELS`, `system-theme.ts:198-286`).
 *
 * The curves were fitted to the reference design pi reviewed ("Pi themes: system and
 * light/dark"): on dark backgrounds they aim for a nearly fixed lightness, on light
 * backgrounds the required difference grows as the background darkens. They are pi's
 * numbers — do not "tidy" them.
 */
private enum class PiLevel(val dark: PiCurve, val light: PiCurve) {
    PANEL(
        PiCurve(doubleArrayOf(0.29131, -0.39746, 2.33185, -0.85524, -1.2076, 0.86276), 0.0, 0.979),
        PiCurve(doubleArrayOf(-3.74073, 27.94549, -78.44258, 112.6798, -79.60015, 22.11277), 0.348, 1.0),
    ),
    TRACK(
        PiCurve(doubleArrayOf(0.39028, -0.23015, 0.83573, 2.43829, -4.38292, 2.01582), 0.0, 0.946),
        PiCurve(doubleArrayOf(-5.24921, 38.37322, -107.28833, 152.10005, -106.17127, 29.18061), 0.368, 1.0),
    ),
    THINKING_0(
        PiCurve(doubleArrayOf(0.52988, -0.05809, -0.30924, 4.63567, -6.52933, 2.89108), 0.0, 0.873),
        PiCurve(
            doubleArrayOf(-28.27749, 182.85284, -469.62416, 603.15916, -384.59976, 97.35147),
            0.51,
            1.0,
        ),
    ),
    THINKING_1(
        PiCurve(doubleArrayOf(0.55278, -0.03667, -0.45659, 4.95347, -6.90265, 3.0706), 0.0, 0.858),
        PiCurve(
            doubleArrayOf(-37.10484, 235.86282, -596.62344, 754.3633, -474.00763, 118.3551),
            0.535,
            1.0,
        ),
    ),
    THINKING_2(
        PiCurve(doubleArrayOf(0.57486, -0.01765, -0.58987, 5.25227, -7.27175, 3.25532), 0.0, 0.842),
        PiCurve(
            doubleArrayOf(-59.89653, 377.05024, -945.07843, 1182.03145, -734.96375, 181.68658),
            0.556,
            1.0,
        ),
    ),
    THINKING_3(
        PiCurve(doubleArrayOf(0.59621, -0.00062, -0.71148, 5.53588, -7.6392, 3.44606), 0.0, 0.827),
        PiCurve(
            doubleArrayOf(-72.07122, 445.84082, -1099.57352, 1353.88793, -829.53392, 202.26164),
            0.58,
            1.0,
        ),
    ),
    THINKING_4(
        PiCurve(doubleArrayOf(0.61691, 0.01462, -0.82288, 5.80651, -8.00641, 3.64333), 0.0, 0.811),
        PiCurve(
            doubleArrayOf(-110.14338, 674.21488, -1645.75941, 2004.32367, -1215.15899, 293.3183),
            0.6,
            1.0,
        ),
    ),
    THINKING_5(
        PiCurve(doubleArrayOf(0.63702, 0.02826, -0.92498, 6.06465, -8.37246, 3.84651), 0.0, 0.795),
        PiCurve(
            doubleArrayOf(-175.47701, 1063.54495, -2570.70594, 3098.80776, -1860.15527, 444.76392),
            0.62,
            1.0,
        ),
    ),
    THINKING_6(
        PiCurve(doubleArrayOf(0.65658, 0.04044, -1.01835, 6.30989, -8.73529, 4.05439), 0.0, 0.779),
        PiCurve(
            doubleArrayOf(-183.81712, 1094.70055, -2602.68539, 3088.71276, -1826.91131, 430.75931),
            0.643,
            1.0,
        ),
    ),
    SUBTLE(
        PiCurve(doubleArrayOf(0.56762, -0.02475, -0.5383, 5.12628, -7.10931, 3.17324), 0.0, 0.848),
        PiCurve(
            doubleArrayOf(-232.85459, 1376.54473, -3249.11801, 3827.91186, -2248.29472, 526.55751),
            0.657,
            1.0,
        ),
    ),
    THUMB(
        PiCurve(doubleArrayOf(0.60323, 0.00278, -0.73328, 5.57157, -7.68067, 3.46933), 0.0, 0.823),
        PiCurve(
            doubleArrayOf(-82.89897, 511.01355, -1255.98095, 1540.76821, -940.68087, 228.58523),
            0.586,
            1.0,
        ),
    ),
    READABLE(
        PiCurve(doubleArrayOf(0.66937, 0.04704, -1.06871, 6.43941, -8.9332, 4.17229), 0.0, 0.77),
        PiCurve(
            doubleArrayOf(-1554.52576, 8733.56817, -19604.93507, 21977.72696, -12300.99599, 2749.81288),
            0.751,
            1.0,
        ),
    ),
    EMPHASIS(
        PiCurve(doubleArrayOf(0.7303, 0.07695, -1.31626, 7.1681, -10.14436, 4.92846), 0.0, 0.712),
        PiCurve(
            doubleArrayOf(-4948.31942, 26870.91986, -58334.48399, 63280.17197, -34298.01053, 7430.30146),
            0.811,
            1.0,
        ),
    ),
    TEXT_ON_PANEL(
        PiCurve(doubleArrayOf(0.86713, 0.05232, -0.89428, 4.79014, -5.5432, 1.75023), 0.0, 0.542),
        PiCurve(
            doubleArrayOf(-8570.89457, 43954.60805, -90084.00702, 92220.6791, -47152.15802, 9632.27113),
            0.867,
            1.0,
        ),
    ),
    TEXT(
        PiCurve(doubleArrayOf(0.89242, 0.02311, -0.44862, 2.34417, -0.06084, -2.63844), 0.0, 0.5),
        PiCurve(
            doubleArrayOf(-2004.67048, 6664.47299, -6060.70202, -1792.61209, 5133.82359, -1939.85583),
            0.894,
            1.0,
        ),
    ),
    ;

    fun curve(appearance: PiSystemAppearance): PiCurve =
        if (appearance == PiSystemAppearance.DARK) dark else light
}

/** One "token must reach `level` on these surfaces" rule (`Rule`, `system-theme.ts:300-304`). */
private data class PiRule(val token: PiSystemToken, val on: List<PiSurface>, val level: PiLevel)

private val TOOL_PANELS: List<PiSurface> = listOf(
    PiSurface.TOOL_PENDING_BG,
    PiSurface.TOOL_SUCCESS_BG,
    PiSurface.TOOL_ERROR_BG,
)
private val MESSAGE_PANELS: List<PiSurface> = listOf(PiSurface.USER_MESSAGE_BG, PiSurface.CUSTOM_MESSAGE_BG)

/** The seven panel backgrounds. Only these are pushed away from unreadable mid-tones. */
private val PANELS: List<PiSystemToken> = listOf(
    PiSystemToken.USER_MESSAGE_BG,
    PiSystemToken.TOOL_PENDING_BG,
    PiSystemToken.TOOL_SUCCESS_BG,
    PiSystemToken.TOOL_ERROR_BG,
    PiSystemToken.SELECTED_BG,
    PiSystemToken.SEARCH_MATCH_BG,
    PiSystemToken.CUSTOM_MESSAGE_BG,
)

/** Text-level tokens that take the terminal's own foreground when it is strong enough. */
private val FOREGROUND_TOKENS: List<PiSystemToken> = listOf(
    PiSystemToken.TEXT,
    PiSystemToken.USER_MESSAGE_TEXT,
    PiSystemToken.TOOL_TITLE,
)

/** Relaxation compresses levels stronger than this one toward it before weakening all levels. */
private fun readableFloor(appearance: PiSystemAppearance): PiLevel =
    if (appearance == PiSystemAppearance.DARK) PiLevel.READABLE else PiLevel.SUBTLE

/** Body text uses the terminal's foreground when it reaches this level, clearly stronger than muted. */
private val FOREGROUND_LEVEL: PiLevel = PiLevel.EMPHASIS

/** WCAG 2 contrast ratio that body text must reach on the surfaces it is drawn on. */
private const val TEXT_MINIMUM_WCAG_CONTRAST: Double = 4.5

private fun each(
    tokens: List<PiSystemToken>,
    on: List<PiSurface>,
    level: PiLevel,
): List<PiRule> = tokens.map { PiRule(it, on, level) }

/** The rules, in pi's order (`RULES`, `system-theme.ts:306-380`). */
private val RULES: List<PiRule> = buildList {
    addAll(each(PANELS, listOf(PiSurface.BACKGROUND), PiLevel.PANEL))
    add(PiRule(PiSystemToken.TEXT, listOf(PiSurface.BACKGROUND), PiLevel.TEXT))
    add(PiRule(PiSystemToken.TEXT, listOf(PiSurface.SELECTED_BG), PiLevel.TEXT_ON_PANEL))
    add(PiRule(PiSystemToken.USER_MESSAGE_TEXT, listOf(PiSurface.USER_MESSAGE_BG), PiLevel.TEXT_ON_PANEL))
    add(PiRule(PiSystemToken.TOOL_TITLE, TOOL_PANELS, PiLevel.TEXT_ON_PANEL))
    addAll(
        each(
            listOf(
                PiSystemToken.ACCENT,
                PiSystemToken.SUCCESS,
                PiSystemToken.ERROR,
                PiSystemToken.WARNING,
            ),
            listOf(PiSurface.BACKGROUND, PiSurface.SELECTED_BG) + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(
        PiRule(
            PiSystemToken.MUTED,
            listOf(PiSurface.BACKGROUND, PiSurface.SELECTED_BG, PiSurface.CUSTOM_MESSAGE_BG) + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(
        PiRule(
            PiSystemToken.DIM,
            listOf(PiSurface.BACKGROUND, PiSurface.SELECTED_BG, PiSurface.CUSTOM_MESSAGE_BG) + TOOL_PANELS,
            PiLevel.SUBTLE,
        ),
    )
    add(PiRule(PiSystemToken.THINKING_TEXT, listOf(PiSurface.BACKGROUND), PiLevel.READABLE))
    add(
        PiRule(
            PiSystemToken.CUSTOM_MESSAGE_TEXT,
            listOf(PiSurface.CUSTOM_MESSAGE_BG) + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(
        PiRule(
            PiSystemToken.CUSTOM_MESSAGE_LABEL,
            listOf(PiSurface.BACKGROUND, PiSurface.CUSTOM_MESSAGE_BG, PiSurface.SELECTED_BG) + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(PiRule(PiSystemToken.TOOL_OUTPUT, listOf(PiSurface.BACKGROUND) + TOOL_PANELS, PiLevel.READABLE))
    addAll(
        each(
            listOf(
                PiSystemToken.MD_HEADING,
                PiSystemToken.MD_LINK,
                PiSystemToken.MD_LINK_URL,
                PiSystemToken.MD_CODE,
                PiSystemToken.MD_QUOTE,
                PiSystemToken.MD_CODE_BLOCK_BORDER,
                PiSystemToken.MD_LIST_BULLET,
            ),
            listOf(PiSurface.BACKGROUND) + MESSAGE_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(
        PiRule(
            PiSystemToken.MD_CODE_BLOCK,
            listOf(PiSurface.BACKGROUND) + MESSAGE_PANELS + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    addAll(
        each(
            listOf(
                PiSystemToken.TOOL_DIFF_ADDED,
                PiSystemToken.TOOL_DIFF_REMOVED,
                PiSystemToken.TOOL_DIFF_CONTEXT,
            ),
            listOf(PiSurface.BACKGROUND) + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    addAll(
        each(
            listOf(
                PiSystemToken.SYNTAX_COMMENT,
                PiSystemToken.SYNTAX_KEYWORD,
                PiSystemToken.SYNTAX_FUNCTION,
                PiSystemToken.SYNTAX_VARIABLE,
                PiSystemToken.SYNTAX_STRING,
                PiSystemToken.SYNTAX_NUMBER,
                PiSystemToken.SYNTAX_TYPE,
                PiSystemToken.SYNTAX_OPERATOR,
                PiSystemToken.SYNTAX_PUNCTUATION,
            ),
            listOf(PiSurface.BACKGROUND) + MESSAGE_PANELS + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(PiRule(PiSystemToken.SEARCH_MATCH_TEXT, listOf(PiSurface.SEARCH_MATCH_BG), PiLevel.READABLE))
    addAll(
        each(
            listOf(PiSystemToken.BASH_MODE, PiSystemToken.BORDER, PiSystemToken.BORDER_ACCENT),
            listOf(PiSurface.BACKGROUND),
            PiLevel.READABLE,
        ),
    )
    add(PiRule(PiSystemToken.BORDER_MUTED, listOf(PiSurface.BACKGROUND), PiLevel.SUBTLE))
    addAll(
        each(
            listOf(PiSystemToken.MD_QUOTE_BORDER, PiSystemToken.MD_HR),
            listOf(PiSurface.BACKGROUND) + MESSAGE_PANELS + TOOL_PANELS,
            PiLevel.READABLE,
        ),
    )
    add(PiRule(PiSystemToken.SCROLLBAR_TRACK, listOf(PiSurface.BACKGROUND), PiLevel.TRACK))
    add(PiRule(PiSystemToken.SCROLLBAR_THUMB, listOf(PiSurface.SCROLLBAR_TRACK), PiLevel.THUMB))
    val thinking = listOf(
        PiSystemToken.THINKING_OFF,
        PiSystemToken.THINKING_MINIMAL,
        PiSystemToken.THINKING_LOW,
        PiSystemToken.THINKING_MEDIUM,
        PiSystemToken.THINKING_HIGH,
        PiSystemToken.THINKING_XHIGH,
        PiSystemToken.THINKING_MAX,
    )
    val thinkingLevels = listOf(
        PiLevel.THINKING_0,
        PiLevel.THINKING_1,
        PiLevel.THINKING_2,
        PiLevel.THINKING_3,
        PiLevel.THINKING_4,
        PiLevel.THINKING_5,
        PiLevel.THINKING_6,
    )
    for (index in thinking.indices) {
        add(PiRule(thinking[index], listOf(PiSurface.BACKGROUND), thinkingLevels[index]))
    }
}

/**
 * Tokens in dependency order: every surface before the tokens drawn on it
 * (`SOLVE_ORDER`, `system-theme.ts:396-410`).
 *
 * Public because it is the one piece of the recipe that is not visible in the output:
 * a rule set that quietly lost an edge would still produce *a* theme, just one whose
 * text was measured against a panel that had not been solved yet. The order is derived
 * from [RULES] (never hand-ordered) and pinned by the golden tests.
 */
val PI_SYSTEM_SOLVE_ORDER: List<PiSystemToken> = run {
    val order = ArrayList<PiSystemToken>(PiSystemToken.entries.size)
    fun visit(token: PiSystemToken) {
        if (order.contains(token)) return
        for (rule in RULES) {
            if (rule.token != token) continue
            for (surface in rule.on) {
                val below = surface.token() ?: continue
                visit(below)
            }
        }
        order.add(token)
    }
    for (rule in RULES) visit(rule.token)
    order
}

// ============================================================================
// Appearance
// ============================================================================

/**
 * Whether a terminal is dark or light, from its reported colours (`terminalAppearance`,
 * `system-theme.ts:271-290`): the direction of its own foreground when text can be
 * readable that way, otherwise dark when white text has more contrast on the background
 * than black text.
 *
 * The same test decides which set of contrast curves applies, so it is not cosmetic:
 * getting it wrong inverts every lightness target. A [PiAnsiPalette] with no
 * foreground (or one whose lightness is within 0.05 of the background's) falls through
 * to the white-vs-black test — which is why a background alone is enough to answer.
 */
fun terminalAppearance(background: Rgb, foreground: Rgb? = null): PiSystemAppearance {
    val white = Rgb(255, 255, 255)
    val black = Rgb(0, 0, 0)
    val whiteContrast = wcagContrast(white, background)
    val blackContrast = wcagContrast(black, background)
    if (foreground != null) {
        val foregroundL = rgbToOklab(foreground).l
        val backgroundL = rgbToOklab(background).l
        if (kotlin.math.abs(foregroundL - backgroundL) > 0.05) {
            val appearance =
                if (foregroundL > backgroundL) PiSystemAppearance.DARK else PiSystemAppearance.LIGHT
            val best = if (appearance == PiSystemAppearance.DARK) whiteContrast else blackContrast
            if (best >= TEXT_MINIMUM_WCAG_CONTRAST) return appearance
        }
    }
    return if (whiteContrast >= blackContrast) PiSystemAppearance.DARK else PiSystemAppearance.LIGHT
}

// ============================================================================
// Generation
// ============================================================================

/** The derived theme: 56 token colours as `0xRRGGBB`, plus how they were reached. */
data class PiSystemThemeColors(
    val colors: Map<PiSystemToken, Int>,
    val appearance: PiSystemAppearance,
    /** How far the contrast levels had to be relaxed, 0 when every level fitted. */
    val relaxation: Double,
) {
    /** The `0xRRGGBB` value of [token]. */
    operator fun get(token: PiSystemToken): Int = colors.getValue(token)
}

/** The solved colours of one attempt: surfaces and tokens, all as sRGB. */
private class PiSolution(
    val tokens: Map<PiSystemToken, Rgb>,
    val surfaces: Map<PiSurface, Rgb>,
)

/**
 * The derivation for one terminal (`generateSystemThemeColors`, `system-theme.ts:400-560`).
 *
 * Holds the closures pi's function defines inline — `paint`, `target`, `limitPanel`,
 * `solve` — because they all share the same six inputs and a class documents that
 * sharing better than six nested functions in a 160-line body.
 */
private class PiSystemSolver(
    private val background: Rgb,
    private val foreground: Rgb?,
    /** The terminal's palette as OKHSL channels, or `null` for the "background only" tier. */
    private val palette: List<OkHsl>?,
    private val appearance: PiSystemAppearance,
    private val saturation: Double,
) {
    private val lighter: Boolean = appearance == PiSystemAppearance.DARK
    private val extreme: Double = if (lighter) 1.0 else 0.0
    private val backgroundL: Double = rgbToOklab(background).l

    /**
     * Saturation weight at a lightness: a Gaussian (centre 0.5, sigma 0.25), 0 at black
     * and white, 1 in the middle (`bellWeight`, `system-theme.ts:392`).
     */
    private fun bellWeight(lightness: Double): Double {
        fun gaussian(x: Double): Double = Math.exp(-((x - 0.5) * (x - 0.5)) / (2.0 * 0.25 * 0.25))
        val atZero = gaussian(0.0)
        return (gaussian(lightness) - atZero) / (1.0 - atZero)
    }

    /** A family's saturation curve relative to its maximum: 1 at mid, `min / max` at the ends. */
    private fun saturationCurve(family: PiFamily, lightness: Double): Double {
        val floor = if (family.maxSaturation > 0.0) {
            family.minSaturation / family.maxSaturation
        } else {
            1.0
        }
        return floor + (1.0 - floor) * bellWeight(lightness)
    }

    /** The target lightness for a level on a surface, or `null` where it cannot be reached. */
    private fun levelTarget(level: PiLevel, surfaceL: Double): Double? {
        val curve = level.curve(appearance)
        if (surfaceL < curve.low || surfaceL > curve.high) return null
        // `coefficients.reduce((sum, c, power) => sum + c * surfaceL ** power, 0)`, in the
        // same order: the sum is part of the fitted curve's reproducibility.
        var sum = 0.0
        for (power in curve.coefficients.indices) {
            sum += curve.coefficients[power] * Math.pow(surfaceL, power.toDouble())
        }
        return sum
    }

    /**
     * A token's colour at an OKLab lightness (`paint`, `system-theme.ts:427-436`): with a
     * palette, that palette colour's saturation at its own lightness, falling off toward
     * black and white along the family's curve, never rising above it.
     */
    private fun paint(token: PiSystemToken, oklabL: Double): Rgb {
        val lightness = oklabToOkhslLightness(oklabL)
        val family = token.colourFamily
        val pal = palette ?: return okhslToRgb(
            family.hue,
            (family.minSaturation + (family.maxSaturation - family.minSaturation) * bellWeight(lightness)) *
                saturation,
            lightness,
        )
        return anchored(pal[token.slot], family, lightness, saturation)
    }

    /**
     * A source colour's hue at another OKHSL lightness (`anchored`, `system-theme.ts:571-582`).
     */
    private fun anchored(source: OkHsl, family: PiFamily, lightness: Double, saturation: Double): Rgb {
        val anchor = saturationCurve(family, source.l)
        val falloff = if (anchor > 0.0) {
            kotlin.math.min(1.0, saturationCurve(family, lightness) / anchor)
        } else {
            1.0
        }
        return okhslToRgb(source.h, source.s * falloff * saturation, lightness)
    }

    /**
     * The lightness a rule needs on a surface, relaxed by `t` (`target`,
     * `system-theme.ts:438-452`): from 0 to 1, levels stronger than the readable floor
     * move toward it; from 1 to 2, all levels move toward the surface itself.
     */
    private fun target(level: PiLevel, surfaceL: Double, t: Double): Double? {
        val reached = levelTarget(level, surfaceL)
        if (reached == null && t == 0.0) return null
        val distance = (reached ?: extreme) - surfaceL
        val floor = (levelTarget(readableFloor(appearance), surfaceL) ?: extreme) - surfaceL
        val compressed = if (kotlin.math.abs(distance) > kotlin.math.abs(floor)) {
            distance - (distance - floor) * kotlin.math.min(t, 1.0)
        } else {
            distance
        }
        return surfaceL + compressed * (1.0 - kotlin.math.max(0.0, t - 1.0))
    }

    /**
     * Keep a panel light enough (or dark enough) that white (or black) text still reaches
     * the body text minimum on it (`limitPanel`, `system-theme.ts:458-470`). This only
     * matters for backgrounds near mid-grey, where it barely does on the background.
     */
    private fun limitPanel(token: PiSystemToken, oklabL: Double): Rgb {
        val extremeText = if (lighter) Rgb(255, 255, 255) else Rgb(0, 0, 0)
        fun readable(color: Rgb): Boolean =
            wcagContrast(extremeText, color) >= TEXT_MINIMUM_WCAG_CONTRAST

        val color = paint(token, oklabL)
        if (readable(color)) return color
        var low = backgroundL
        var high = oklabL
        for (step in 0 until 20) {
            val middle = (low + high) / 2.0
            if (readable(paint(token, middle))) low = middle else high = middle
        }
        return paint(token, low)
    }

    /** Solve every token with the levels relaxed by `t`, or `null` if a level does not fit. */
    private fun solve(t: Double): PiSolution? {
        val surfaces = HashMap<PiSurface, Rgb>()
        val tokens = HashMap<PiSystemToken, Rgb>()
        surfaces[PiSurface.BACKGROUND] = background
        for (token in PI_SYSTEM_SOLVE_ORDER) {
            val targets = ArrayList<Double>(4)
            for (rule in RULES) {
                if (rule.token != token) continue
                for (surface in rule.on) {
                    val below = surfaces[surface] ?: background
                    val value = target(rule.level, rgbToOklab(below).l, t) ?: return null
                    if (value < 0.0 || value > 1.0) return null
                    targets.add(value)
                }
            }
            // Every token has at least one rule (pinned by the tests); the guard keeps a
            // future token without one from crashing on an empty max/min.
            if (targets.isEmpty()) continue
            val lightness = if (lighter) targets.max() else targets.min()
            val color = if (token in PANELS) limitPanel(token, lightness) else paint(token, lightness)
            tokens[token] = color
            token.surfaceOf?.let { surfaces[it] = color }
        }
        return PiSolution(tokens, surfaces)
    }

    /** The surfaces a token is drawn on (`surfacesOf`, `system-theme.ts:527-530`). */
    private fun surfacesOf(token: PiSystemToken, solution: PiSolution): List<Rgb> = RULES
        .filter { it.token == token }
        .flatMap { rule -> rule.on.map { surface -> solution.surfaces[surface] ?: background } }

    /** The whole pipeline: solve, relax if needed, then let body text take the foreground. */
    fun derive(): PiSystemThemeColors {
        var relaxation = 0.0
        val first = solve(0.0)
        val strict: PiSolution
        if (first != null) {
            strict = first
        } else {
            // Mid-grey backgrounds cannot fit every level: relax as little as possible.
            // Full relaxation always fits, because it targets the surface itself.
            var low = 0.0
            var high = 2.0
            var best = solve(high)
                ?: error("system theme relaxation must fit at t=$high")
            for (step in 0 until 20) {
                val middle = (low + high) / 2.0
                val attempt = solve(middle)
                if (attempt != null) {
                    high = middle
                    best = attempt
                } else {
                    low = middle
                }
            }
            strict = best
            relaxation = high
        }

        val tokens = HashMap<PiSystemToken, Rgb>(strict.tokens)
        for (token in FOREGROUND_TOKENS) {
            val surfaces = surfacesOf(token, strict)
            var text = tokens[token]
            val fg = foreground
            if (fg != null) {
                val targets = surfaces.map { target(FOREGROUND_LEVEL, rgbToOklab(it).l, relaxation) }
                if (targets.isNotEmpty() && targets.all { it != null && it >= 0.0 && it <= 1.0 }) {
                    val reached = targets.filterNotNull()
                    val needed = if (lighter) reached.max() else reached.min()
                    val foregroundL = rgbToOklab(fg).l
                    if (if (lighter) foregroundL >= needed else foregroundL <= needed) {
                        // The terminal's own foreground is clearly stronger than muted text:
                        // use it verbatim, which is what pi renders as "the default colour".
                        tokens[token] = fg
                        continue
                    }
                    text = anchored(rgbToOkhsl(fg), PiFamily.NEUTRAL, oklabToOkhslLightness(needed), saturation)
                }
            }
            // Body text keeps at least 4.5:1 on the surfaces it is drawn on, even on
            // relaxed mid-grey backgrounds.
            if (text != null) tokens[token] = withTextContrast(text, surfaces, lighter)
        }

        val colors = LinkedHashMap<PiSystemToken, Int>(PiSystemToken.entries.size)
        for (token in PiSystemToken.entries) {
            // A token the rules never reached is painted as the background: the derivation
            // always defines all 56 (there is no "unset" in a UI palette).
            colors[token] = (tokens[token] ?: background).toHexRgb()
        }
        return PiSystemThemeColors(colors, appearance, relaxation)
    }

    /** Move a text colour toward white or black until it reaches the WCAG minimum. */
    private fun withTextContrast(color: Rgb, surfaces: List<Rgb>, lighter: Boolean): Rgb {
        fun meets(candidate: Rgb): Boolean =
            surfaces.all { wcagContrast(candidate, it) >= TEXT_MINIMUM_WCAG_CONTRAST }

        if (meets(color)) return color
        val source = rgbToOkhsl(color)
        fun at(lightness: Double): Rgb = okhslToRgb(source.h, source.s, lightness)
        val extremeLightness = if (lighter) 1.0 else 0.0
        if (!meets(at(extremeLightness))) return at(extremeLightness)
        var low = source.l
        var high = extremeLightness
        for (step in 0 until 20) {
            val middle = (low + high) / 2.0
            if (meets(at(middle))) high = middle else low = middle
        }
        return at(high)
    }
}

/**
 * Derive pi's `system` theme from what a terminal reports (`generateSystemThemeColors`,
 * `system-theme.ts:400`).
 *
 * [base] `null` is the "nothing reported" tier. On a terminal that means "hand out ANSI
 * indices and let the terminal paint them"; a phone has no terminal to paint them, so it
 * derives from [defaultAnsiPalette] instead — pi's own default ANSI 16 on pi's own
 * default background. Every caller therefore gets concrete colours, and the two
 * palette-ful tiers are pi's, unchanged.
 *
 * [saturation] is pi's `SystemThemeInput.saturation` (1 is normal, 0 is greyscale, used
 * for the first frame before the terminal's colours arrive). It defaults to 1.
 */
fun deriveSystemColors(base: PiAnsiPalette?, saturation: Double = 1.0): PiSystemThemeColors {
    // Tier 3: no report at all. See the KDoc — pi defers to the terminal, this app cannot.
    val terminal = base ?: defaultAnsiPalette()
    val background = Rgb.of(terminal.background)
    val foreground = terminal.foreground?.let { Rgb.of(it) }
    // Tier 1 needs a full palette; anything else falls to tier 2 ("background only").
    val palette = if (terminal.hasPalette) terminal.ansi.map { rgbToOkhsl(Rgb.of(it)) } else null
    val appearance = terminalAppearance(background, foreground)
    return PiSystemSolver(
        background = background,
        foreground = foreground,
        palette = palette,
        appearance = appearance,
        saturation = saturation.coerceIn(0.0, 1.0),
    ).derive()
}

/**
 * [deriveSystemColors] as a [PiPalette], ready to be the app's palette.
 *
 * The 56 tokens are the derivation's; the three canvas tokens (`pageBg`, `cardBg`,
 * `infoBg`) are **not** pi system-theme tokens — pi's HTML exporter derives them from
 * `userMessageBg` (`deriveExportColors`, `core/export-html/index.ts:81-105`), and this
 * repeats that rule exactly as `PiThemeFiles.deriveExportSurfaces` does, so a derived
 * `system` palette and a loaded theme file agree on those three.
 */
fun deriveSystemPalette(base: PiAnsiPalette?, saturation: Double = 1.0): PiPalette {
    val derived = deriveSystemColors(base, saturation)
    val tokens = derived.colors
    fun token(value: PiSystemToken): Color = composeColor(tokens.getValue(value))
    val userMessageBg = token(PiSystemToken.USER_MESSAGE_BG)
    // 画布用**输入底色本身**，不从任何推导出来的 token 反推。pi 的 system 主题里
    // pageBg/cardBg 不是 token：TUI 直接画在终端底色上，`export` 段那两个面是可选的
    // 手写值（`theme.ts:920-944`）。之前这里拿 userMessageBg（蓝族的推导结果）缩放，
    // 于是整页底色被染蓝 —— 用户看到的"背景成蓝的"就是这个，不是推导本身的错。
    val canvas = canvasSurfaces(composeColor(input.background))
    return PiPalette(
        accent = token(PiSystemToken.ACCENT),
        border = token(PiSystemToken.BORDER),
        borderAccent = token(PiSystemToken.BORDER_ACCENT),
        borderMuted = token(PiSystemToken.BORDER_MUTED),
        success = token(PiSystemToken.SUCCESS),
        error = token(PiSystemToken.ERROR),
        warning = token(PiSystemToken.WARNING),
        muted = token(PiSystemToken.MUTED),
        dim = token(PiSystemToken.DIM),
        text = token(PiSystemToken.TEXT),
        thinkingText = token(PiSystemToken.THINKING_TEXT),
        selectedBg = token(PiSystemToken.SELECTED_BG),
        scrollbarTrack = token(PiSystemToken.SCROLLBAR_TRACK),
        scrollbarThumb = token(PiSystemToken.SCROLLBAR_THUMB),
        searchMatchBg = token(PiSystemToken.SEARCH_MATCH_BG),
        searchMatchText = token(PiSystemToken.SEARCH_MATCH_TEXT),
        userMessageBg = userMessageBg,
        userMessageText = token(PiSystemToken.USER_MESSAGE_TEXT),
        customMessageBg = token(PiSystemToken.CUSTOM_MESSAGE_BG),
        customMessageText = token(PiSystemToken.CUSTOM_MESSAGE_TEXT),
        customMessageLabel = token(PiSystemToken.CUSTOM_MESSAGE_LABEL),
        toolPendingBg = token(PiSystemToken.TOOL_PENDING_BG),
        toolSuccessBg = token(PiSystemToken.TOOL_SUCCESS_BG),
        toolErrorBg = token(PiSystemToken.TOOL_ERROR_BG),
        toolTitle = token(PiSystemToken.TOOL_TITLE),
        toolOutput = token(PiSystemToken.TOOL_OUTPUT),
        mdHeading = token(PiSystemToken.MD_HEADING),
        mdLink = token(PiSystemToken.MD_LINK),
        mdLinkUrl = token(PiSystemToken.MD_LINK_URL),
        mdCode = token(PiSystemToken.MD_CODE),
        mdCodeBlock = token(PiSystemToken.MD_CODE_BLOCK),
        mdCodeBlockBorder = token(PiSystemToken.MD_CODE_BLOCK_BORDER),
        mdQuote = token(PiSystemToken.MD_QUOTE),
        mdQuoteBorder = token(PiSystemToken.MD_QUOTE_BORDER),
        mdHr = token(PiSystemToken.MD_HR),
        mdListBullet = token(PiSystemToken.MD_LIST_BULLET),
        toolDiffAdded = token(PiSystemToken.TOOL_DIFF_ADDED),
        toolDiffRemoved = token(PiSystemToken.TOOL_DIFF_REMOVED),
        toolDiffContext = token(PiSystemToken.TOOL_DIFF_CONTEXT),
        syntaxComment = token(PiSystemToken.SYNTAX_COMMENT),
        syntaxKeyword = token(PiSystemToken.SYNTAX_KEYWORD),
        syntaxFunction = token(PiSystemToken.SYNTAX_FUNCTION),
        syntaxVariable = token(PiSystemToken.SYNTAX_VARIABLE),
        syntaxString = token(PiSystemToken.SYNTAX_STRING),
        syntaxNumber = token(PiSystemToken.SYNTAX_NUMBER),
        syntaxType = token(PiSystemToken.SYNTAX_TYPE),
        syntaxOperator = token(PiSystemToken.SYNTAX_OPERATOR),
        syntaxPunctuation = token(PiSystemToken.SYNTAX_PUNCTUATION),
        thinkingOff = token(PiSystemToken.THINKING_OFF),
        thinkingMinimal = token(PiSystemToken.THINKING_MINIMAL),
        thinkingLow = token(PiSystemToken.THINKING_LOW),
        thinkingMedium = token(PiSystemToken.THINKING_MEDIUM),
        thinkingHigh = token(PiSystemToken.THINKING_HIGH),
        thinkingXhigh = token(PiSystemToken.THINKING_XHIGH),
        thinkingMax = token(PiSystemToken.THINKING_MAX),
        bashMode = token(PiSystemToken.BASH_MODE),
        pageBg = canvas.pageBg,
        cardBg = canvas.cardBg,
        infoBg = canvas.infoBg,
    )
}

private class PiCanvasSurfaces(val pageBg: Color, val cardBg: Color, val infoBg: Color)

/**
 * The three tokens pi's HTML exporter derives from `userMessageBg`
 * (`deriveExportColors`, `core/export-html/index.ts:81-105`)，但输入是**终端底色本身**：
 * 页面就是底色，卡片向白混一点点（亮/暗两档的比例对齐 pi 的 `#EFEEEE/#F7F6F6` 与
 * `#21252C/#282C34` 的差值），信息面按深浅偏向 pi 的暖色。之前这里用
 * `userMessageBg`（蓝族推导结果）缩放 —— 那会把整页染蓝，是这轮修掉的 bug。
 */
private fun canvasSurfaces(background: Color): PiCanvasSurfaces {
    /** `background` 向 [toward] 混 [amount]（0..1）。只用于"卡片比页面亮一点点"这种面差。 */
    fun blend(toward: Color, amount: Float): Color = Color(
        red = background.red + (toward.red - background.red) * amount,
        green = background.green + (toward.green - background.green) * amount,
        blue = background.blue + (toward.blue - background.blue) * amount,
    )
    val light = !isDarkSurface(background)
    return if (light) {
        // 对应 pi light 的 `pageBg #EFEEEE` / `cardBg #F7F6F6`：卡片比页面更白一点。
        PiCanvasSurfaces(
            pageBg = background,
            cardBg = blend(Color.White, 0.35f),
            infoBg = blend(Color(0xFFEDE3DD), 0.6f),
        )
    } else {
        // 对应 pi dark 的 `#21252C` / `#282C34`：卡片比页面亮约 6%，与 pi 的差值同级。
        PiCanvasSurfaces(
            pageBg = background,
            cardBg = blend(Color.White, 0.06f),
            infoBg = blend(Color(0xFF4E2F1B), 0.75f),
        )
    }
}

/**
 * Whether a surface is dark, by pi's exporter test (`getLuminance` compared to 0.5,
 * `core/export-html/index.ts:64-71`). pi's exporter uses the 0.03928 sRGB threshold,
 * not the 0.04045 one the WCAG derivation above uses; keeping each faithful to its own
 * source is the point.
 */
private fun isDarkSurface(color: Color): Boolean {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    val luminance = 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    return luminance <= 0.5
}

/** `0xRRGGBB` to an opaque Compose colour. */
private fun composeColor(hexRgb: Int): Color = Color(0xFF000000L or (hexRgb.toLong() and 0xFFFFFFL))

/** Six-hex-digit rendering for the palette's `toString`. */
private fun hex(value: Int): String = (value and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
