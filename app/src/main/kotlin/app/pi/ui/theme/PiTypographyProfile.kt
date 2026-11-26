package app.pi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** One markdown heading slot: size and leading, in `sp`. */
@Immutable
data class PiHeading(val sizeSp: Int, val lineHeightSp: Int)

/**
 * Clean-slate modular editorial typography profile for Pi Android.
 *
 * Built around two strictly orthogonal user controls plus font pairing:
 * 1. [textSizeSp] (`14 | 15 | 16 | 17 | 18`, default `16`, stored in `app.appearance.fontSize`):
 *    drives prose, code, inline code, tables, headings H1–H6, and gentle UI chrome scaling in one
 *    coherent modular ladder — never fighting with spacing density.
 * 2. [density] (`compact | comfortable | relaxed`, default `comfortable`, stored in
 *    `app.appearance.typography`): drives the entire vertical baseline rhythm (prose & code line
 *    height ratios, paragraph gaps, list spacing, heading top spacing, and message turn gaps)
 *    harmonically — never altering font sizes or silently dropping out-of-band steps.
 * 3. Unified whole-app font pairing: [proseFont] (`sans`[IBM Plex Sans SC] / `serif`[Source Han
 *    Serif CN] / `kai`[LXGW WenKai] / `system`), [codeFont] (`jetbrains` / `geist_mono`), and
 *    [codeLigatures].
 */
@Immutable
data class PiTypographyProfile(
    /** One of [COMPACT] / [COMFORTABLE] / [RELAXED]. */
    val density: String,
    /** Explicit prose base size in `sp`: one of `14..18` (default `16`). */
    val textSizeSp: Int,
    /** Prose line-height multiplier (`1.50` / `1.64` / `1.78`). */
    val proseLineRatio: Float,
    /** Code & table line-height multiplier (`1.46` / `1.56` / `1.64`). */
    val codeLineRatio: Float,
    /** The five core text roles with active font families, features, size, and leading applied. */
    val styles: PiTextStyles,
    val h1: PiHeading,
    val h2: PiHeading,
    val h3: PiHeading,
    val h4: PiHeading,
    val h5: PiHeading,
    val h6: PiHeading,
    val inlineCodeSizeSp: Float,
    val inlineCodeLineHeightSp: Float,
    val inlineCodeWeight: FontWeight,
    val tableSizeSp: Float,
    val tableLineHeightSp: Float,
    /** Markdown vertical rhythm tokens. */
    val markdownBlock: Dp,
    val markdownListItemTop: Dp,
    val markdownListItemBottom: Dp,
    val markdownListIndent: Dp,
    val headingTopH1: Dp,
    val headingTopH2: Dp,
    val headingTopH3: Dp,
    val headingTopSmall: Dp,
    /** Gap above the first row of a new message (speaker change). */
    val messageSpacing: Dp,
    /** Intra-message block spacing (`LazyColumn` `spacedBy`). */
    val blockSpacing: Dp,
    /** Gentle UI chrome offset in `sp` (`-1..+2`), keeping buttons/labels legible at all text sizes. */
    val fontSizeOffsetSp: Int,
    /** Whole-app UI & reading font: `sans` (IBM Plex Sans SC), `serif` (Source Han Serif CN), `kai` (LXGW WenKai), or `system`. */
    val proseFont: String = FONT_SANS,
    /** Code, diff, terminal, and TUI monospace font: `jetbrains` or `geist_mono`. */
    val codeFont: String = CODE_JETBRAINS,
    /** Enable OpenType coding ligatures (`=>`, `!=`, `->`) in code fences and code viewers. */
    val codeLigatures: Boolean = true,
) {
    /** Unified whole-app typeface (UI chrome, top bar, settings, composer, chat prose, headings, dialogs, thinking). */
    fun appFontFamily(): FontFamily = when (proseFont) {
        FONT_SERIF -> PiProseFamily
        FONT_KAI -> PiWenKaiFamily
        FONT_SYSTEM -> FontFamily.Default
        else -> PiPlexSansFamily
    }

    /** App-wide UI chrome typeface — unified with [proseFontFamily] so switching fonts transforms 100% of the app. */
    fun uiFontFamily(): FontFamily = appFontFamily()

    /** OpenType feature string for UI chrome text. */
    fun uiFontFeatures(): String? = null

    /** Chat Markdown reading prose typeface — unified with [uiFontFamily]. */
    fun proseFontFamily(): FontFamily = appFontFamily()

    /** OpenType feature string for chat Markdown reading prose. */
    fun proseFontFeatures(): String? = null

    /** Active code, diff, terminal, and TUI monospace typeface (complete 6 static cuts: 400/500/600/700 + 400i/700i). */
    fun codeFontFamily(): FontFamily = when (codeFont) {
        CODE_GEIST_MONO -> PiGeistMonoFamily
        else -> PiJetBrainsMonoFamily
    }

    /** OpenType feature string for monospace text (ligatures opt-in for code blocks, off for diffs/paths/commands). */
    fun codeFontFeatures(allowLigatures: Boolean): String =
        if (allowLigatures && codeLigatures) PiFontFeatures.CODE_LIGATURES_ON else PiFontFeatures.CODE_LIGATURES_OFF

    fun codeFontResRegular(): Int =
        if (codeFont == CODE_GEIST_MONO) app.pi.R.font.geist_mono_regular else app.pi.R.font.jetbrains_mono_regular

    fun codeFontResBold(): Int =
        if (codeFont == CODE_GEIST_MONO) app.pi.R.font.geist_mono_bold else app.pi.R.font.jetbrains_mono_bold

    fun codeFontResItalic(): Int =
        if (codeFont == CODE_GEIST_MONO) app.pi.R.font.geist_mono_italic else app.pi.R.font.jetbrains_mono_italic

    fun codeFontResBoldItalic(): Int =
        if (codeFont == CODE_GEIST_MONO) app.pi.R.font.geist_mono_bold_italic else app.pi.R.font.jetbrains_mono_bold_italic

    /** Subtly scaled-down profile for expanded reasoning blocks so they read cleanly yet stay subordinate to answers. */
    fun forThinking(): PiTypographyProfile {
        val proseSz = (styles.prose.fontSize.value - 1.5f).coerceAtLeast(12.5f)
        val proseLh = roundHalfSp(proseSz * (proseLineRatio - 0.04f).coerceAtLeast(1.44f))
        val codeSz = (styles.code.fontSize.value - 1f).coerceAtLeast(11.5f)
        val codeLh = roundHalfSp(codeSz * (codeLineRatio - 0.04f).coerceAtLeast(1.40f))
        fun PiHeading.shrink(): PiHeading = PiHeading(
            sizeSp = (sizeSp - 2).coerceAtLeast(13),
            lineHeightSp = (lineHeightSp - 2).coerceAtLeast(19),
        )
        return copy(
            styles = styles.copy(
                prose = styles.prose.copy(fontSize = proseSz.sp, lineHeight = proseLh.sp),
                code = styles.code.copy(fontSize = codeSz.sp, lineHeight = codeLh.sp),
            ),
            h1 = h1.shrink(),
            h2 = h2.shrink(),
            h3 = h3.shrink(),
            h4 = h4.shrink(),
            h5 = h5.shrink(),
            h6 = h6.shrink(),
            inlineCodeSizeSp = (inlineCodeSizeSp - 1f).coerceAtLeast(11.5f),
            inlineCodeLineHeightSp = roundHalfSp((inlineCodeSizeSp - 1f).coerceAtLeast(11.5f) * 1.40f),
            tableSizeSp = (tableSizeSp - 1f).coerceAtLeast(12f),
            tableLineHeightSp = roundHalfSp((tableSizeSp - 1f).coerceAtLeast(12f) * (codeLineRatio - 0.04f).coerceAtLeast(1.40f)),
            markdownBlock = (markdownBlock.value * 0.72f).coerceAtLeast(6f).dp,
            markdownListItemTop = (markdownListItemTop.value * 0.75f).coerceAtLeast(2f).dp,
            markdownListItemBottom = (markdownListItemBottom.value * 0.75f).coerceAtLeast(2f).dp,
            headingTopH1 = (headingTopH1.value * 0.75f).coerceAtLeast(10f).dp,
            headingTopH2 = (headingTopH2.value * 0.75f).coerceAtLeast(8f).dp,
            headingTopH3 = (headingTopH3.value * 0.75f).coerceAtLeast(6f).dp,
            headingTopSmall = 3.dp,
        )
    }

    companion object {
        const val COMPACT = "compact"
        const val COMFORTABLE = "comfortable"
        const val RELAXED = "relaxed"

        const val DEFAULT_TEXT_SIZE_SP = 16
        val TEXT_SIZE_STOPS: List<Int> = listOf(14, 15, 16, 17, 18)

        const val FONT_SANS = "sans"
        const val FONT_SERIF = "serif"
        const val FONT_KAI = "kai"
        const val FONT_SYSTEM = "system"

        const val CODE_JETBRAINS = "jetbrains"
        const val CODE_GEIST_MONO = "geist_mono"

        fun proseFontOf(raw: String?): String = when (raw) {
            FONT_SERIF -> FONT_SERIF
            FONT_KAI -> FONT_KAI
            FONT_SYSTEM -> FONT_SYSTEM
            else -> FONT_SANS
        }

        fun codeFontOf(raw: String?): String = when (raw) {
            CODE_GEIST_MONO -> CODE_GEIST_MONO
            else -> CODE_JETBRAINS
        }

        /** Resolve reading density wire value; defaults to [COMFORTABLE]. */
        fun densityOf(raw: String?): String = when (raw) {
            COMPACT -> COMPACT
            RELAXED, "loose" -> RELAXED
            else -> COMFORTABLE
        }

        /** Resolve prose base size (`14..18` sp); defaults to [DEFAULT_TEXT_SIZE_SP]. */
        fun textSizeOf(raw: String?): Int {
            val numeric = raw?.toIntOrNull()
            if (numeric != null) return numeric.coerceIn(TEXT_SIZE_STOPS.first(), TEXT_SIZE_STOPS.last())
            return DEFAULT_TEXT_SIZE_SP
        }

        private data class DensitySpec(
            val proseLineRatio: Float,
            val codeLineRatio: Float,
            val markdownBlock: Int,
            val listItemHalf: Int,
            val listIndent: Int,
            val blockSpacing: Int,
            val messageSpacing: Int,
            val headingTopH1: Int,
            val headingTopH2: Int,
            val headingTopH3: Int,
            val headingTopSmall: Int,
        )

        private data class ScaleSpec(
            val codeSize: Float,
            val inlineCodeSize: Float,
            val tableSize: Float,
            val headings: IntArray,
            val uiOffsetSp: Int,
        )

        private val DENSITIES: Map<String, DensitySpec> = mapOf(
            COMPACT to DensitySpec(
                proseLineRatio = 1.50f,
                codeLineRatio = 1.46f,
                markdownBlock = 10,
                listItemHalf = 3,
                listIndent = 18,
                blockSpacing = 6,
                messageSpacing = 18,
                headingTopH1 = 14,
                headingTopH2 = 10,
                headingTopH3 = 7,
                headingTopSmall = 4,
            ),
            COMFORTABLE to DensitySpec(
                proseLineRatio = 1.64f,
                codeLineRatio = 1.56f,
                markdownBlock = 13,
                listItemHalf = 4,
                listIndent = 20,
                blockSpacing = 8,
                messageSpacing = 24,
                headingTopH1 = 18,
                headingTopH2 = 13,
                headingTopH3 = 9,
                headingTopSmall = 5,
            ),
            RELAXED to DensitySpec(
                proseLineRatio = 1.78f,
                codeLineRatio = 1.64f,
                markdownBlock = 17,
                listItemHalf = 5,
                listIndent = 22,
                blockSpacing = 11,
                messageSpacing = 30,
                headingTopH1 = 22,
                headingTopH2 = 16,
                headingTopH3 = 11,
                headingTopSmall = 6,
            ),
        )

        private val SCALES: Map<Int, ScaleSpec> = mapOf(
            14 to ScaleSpec(
                codeSize = 12.5f,
                inlineCodeSize = 12.5f,
                tableSize = 13.0f,
                headings = intArrayOf(22, 19, 17, 16, 15, 14),
                uiOffsetSp = -1,
            ),
            15 to ScaleSpec(
                codeSize = 13.0f,
                inlineCodeSize = 13.0f,
                tableSize = 13.5f,
                headings = intArrayOf(23, 20, 18, 17, 16, 15),
                uiOffsetSp = 0,
            ),
            16 to ScaleSpec(
                codeSize = 13.5f,
                inlineCodeSize = 13.5f,
                tableSize = 14.0f,
                headings = intArrayOf(24, 21, 19, 18, 17, 16),
                uiOffsetSp = 0,
            ),
            17 to ScaleSpec(
                codeSize = 14.5f,
                inlineCodeSize = 14.5f,
                tableSize = 15.0f,
                headings = intArrayOf(26, 23, 20, 19, 18, 17),
                uiOffsetSp = 1,
            ),
            18 to ScaleSpec(
                codeSize = 15.0f,
                inlineCodeSize = 15.0f,
                tableSize = 15.5f,
                headings = intArrayOf(27, 24, 21, 20, 19, 18),
                uiOffsetSp = 2,
            ),
        )

        private val HEADING_LINE_RATIOS = floatArrayOf(1.26f, 1.29f, 1.32f, 1.36f, 1.40f, 1.44f)

        private fun roundHalfSp(value: Float): Float = (value * 2f).roundToInt() / 2f

        private fun heading(sizeSp: Int, slot: Int): PiHeading =
            PiHeading(sizeSp = sizeSp, lineHeightSp = (sizeSp * HEADING_LINE_RATIOS[slot]).roundToInt())

        /** Build a complete typography profile from the two orthogonal axes and unified font pairing. */
        fun build(
            textSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
            density: String = COMFORTABLE,
            proseFont: String = FONT_SANS,
            codeFont: String = CODE_JETBRAINS,
            codeLigatures: Boolean = true,
        ): PiTypographyProfile {
            val resolvedSize = textSizeSp.coerceIn(TEXT_SIZE_STOPS.first(), TEXT_SIZE_STOPS.last())
            val resolvedDensity = densityOf(density)
            val resolvedProseFont = proseFontOf(proseFont)
            val resolvedCodeFont = codeFontOf(codeFont)

            val d = DENSITIES.getValue(resolvedDensity)
            val s = SCALES.getValue(resolvedSize)

            val appFamily = when (resolvedProseFont) {
                FONT_SERIF -> PiProseFamily
                FONT_KAI -> PiWenKaiFamily
                FONT_SYSTEM -> FontFamily.Default
                else -> PiPlexSansFamily
            }
            val monoFamily = if (resolvedCodeFont == CODE_GEIST_MONO) PiGeistMonoFamily else PiJetBrainsMonoFamily
            val codeFeatures = if (codeLigatures) PiFontFeatures.CODE_LIGATURES_ON else PiFontFeatures.CODE_LIGATURES_OFF

            val proseLineSp = roundHalfSp(resolvedSize * d.proseLineRatio)
            val codeLineSp = roundHalfSp(s.codeSize * d.codeLineRatio)
            val inlineLineSp = roundHalfSp(s.inlineCodeSize * 1.42f)
            val tableLineSp = roundHalfSp(s.tableSize * d.codeLineRatio)

            val uiShift = s.uiOffsetSp
            val styles = PiTextStyles(
                meta = TextStyle(
                    fontFamily = appFamily,
                    fontSize = (12 + uiShift).sp,
                    lineHeight = (18 + uiShift).sp,
                    fontWeight = FontWeight.Normal,
                ).piPrecise(),
                mono = TextStyle(
                    fontFamily = monoFamily,
                    fontSize = (13 + uiShift).sp,
                    lineHeight = (20 + uiShift).sp,
                    fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_OFF,
                ).piPrecise(),
                monoSmall = TextStyle(
                    fontFamily = monoFamily,
                    fontSize = (12 + uiShift).sp,
                    lineHeight = (18 + uiShift).sp,
                    fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_OFF,
                ).piPrecise(),
                prose = TextStyle(
                    fontFamily = appFamily,
                    fontSize = resolvedSize.sp,
                    lineHeight = proseLineSp.sp,
                    fontWeight = FontWeight.Normal,
                ).piPrecise(),
                code = TextStyle(
                    fontFamily = monoFamily,
                    fontSize = s.codeSize.sp,
                    lineHeight = codeLineSp.sp,
                    fontFeatureSettings = codeFeatures,
                ).piPrecise(),
            )

            return PiTypographyProfile(
                density = resolvedDensity,
                textSizeSp = resolvedSize,
                proseLineRatio = d.proseLineRatio,
                codeLineRatio = d.codeLineRatio,
                styles = styles,
                h1 = heading(s.headings[0], 0),
                h2 = heading(s.headings[1], 1),
                h3 = heading(s.headings[2], 2),
                h4 = heading(s.headings[3], 3),
                h5 = heading(s.headings[4], 4),
                h6 = heading(s.headings[5], 5),
                inlineCodeSizeSp = s.inlineCodeSize,
                inlineCodeLineHeightSp = inlineLineSp,
                inlineCodeWeight = FontWeight.Medium,
                tableSizeSp = s.tableSize,
                tableLineHeightSp = tableLineSp,
                markdownBlock = d.markdownBlock.dp,
                markdownListItemTop = d.listItemHalf.dp,
                markdownListItemBottom = d.listItemHalf.dp,
                markdownListIndent = d.listIndent.dp,
                headingTopH1 = d.headingTopH1.dp,
                headingTopH2 = d.headingTopH2.dp,
                headingTopH3 = d.headingTopH3.dp,
                headingTopSmall = d.headingTopSmall.dp,
                messageSpacing = d.messageSpacing.dp,
                blockSpacing = d.blockSpacing.dp,
                fontSizeOffsetSp = uiShift,
                proseFont = resolvedProseFont,
                codeFont = resolvedCodeFont,
                codeLigatures = codeLigatures,
            )
        }

        /** 16sp prose at comfortable editorial density with IBM Plex Sans SC + JetBrains Mono. */
        val Default: PiTypographyProfile = build()
    }
}
