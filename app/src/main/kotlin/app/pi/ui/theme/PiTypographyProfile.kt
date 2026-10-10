package app.pi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * One markdown heading slot: size and leading, in `sp`.
 *
 * A pair of plain numbers rather than a `TextStyle`, because the colour
 * (`mdHeading`) and the weight (600) are pi's, not the preset's — the caller
 * (`render/PiMarkdownTheme.kt`) owns those and only the two numbers here move
 * with the preset. Same split as `PiTextStyles`: the profile decides sizes, the
 * theme decides tokens.
 */
@Immutable
data class PiHeading(val sizeSp: Int, val lineHeightSp: Int)

/**
 * The four presets' numbers, as a **pure function of the three settings rows**
 * `app.appearance.typography` / `.lineHeight` / `.fontSize`.
 *
 * ## Why this exists
 *
 * Before this, three sizes were decided in three unrelated places: the chat body
 * came from `PiTextStyles` (14/23), the markdown rhythm from a top-level `val`
 * in `PiMarkdownTheme.kt` (paragraph 2 dp, list item 2 dp, indent 12 dp) and the
 * transcript's block gap from `ChatScreen`'s `when (messageDensity)`. A user who
 * wanted a larger transcript could only move `fontScaleDelta`, which shifts
 * *sizes* and leaves every leading, gap and radius where it was — that is what
 * "字号微调" did and what the density row did, and neither touched the other.
 *
 * This object is the one place that answers "what does 舒适 mean": it resolves
 * the three stored strings into every number the render path needs, so the
 * settings registry, the compose theme and the markdown theme cannot disagree.
 *
 * ## The numbers, and where they come from
 *
 * 经典 is **today, pixel for pixel** — that is a hard requirement, not an
 * aspiration: [styles] for `classic` is literally [PiTextStyles.Default], the
 * heading sizes are the ones `PiMarkdownTheme.kt` already hard-coded
 * (22/20/18/16/15/14), the paragraph gap is today's 2 dp, the indent is today's
 * 12 dp and the code-block radius is today's 12. A user who picks 经典 must see
 * the app they had before this feature existed.
 *
 * The other three come from the approved direction sheet
 * (`/root/design-preview/typography-preview.html`, screenshots `s1.png`–`s3.png`)
 * and its sources: Tailwind Typography for the prose scale, the CJK
 * typesetting convention of 1.75–1.8 leading for the comfortable step, and
 * Android M3's 16 sp body for the same step. **No colour moves with a preset** —
 * the sheet only re-uses `PiPalette` tokens, and everything here is a size, a
 * gap or a corner radius.
 *
 * |                    | classic | compact | comfortable | loose |
 * |--------------------|---------|---------|-------------|-------|
 * | prose size/leading |  14/23  |  15/24  |   16/28     | 17/31 |
 * | markdown `block`   |    2    |    8    |     16      |  24   |
 * | list item top/bot  |   2/2   |   4/4   |    6/6      | 10/10 |
 * | list indent        |   12    |   18    |     20      |  22   |
 * | inline code size/w | 12.5/400|  13/600 |    14/600   | 14/600|
 * | code size/leading  |  13/19  |  13/21  |    14/24    | 14/26 |
 * | code-block radius  |   12    |   10    |     10      |  10   |
 * | headings h1…h6     | 22/20/18/16/15/14 | 21/19/17/15/15/14 | 22/20/18/16/16/15 | 24/21/19/17/17/16 |
 * | transcript gap     |    8    |    6    |      8      |  12   |
 *
 * The paragraph gap (markdown `block`) is 16 for 舒适 and 24 for 宽松 — larger than
 * the direction sheet's first draft (12/20), because the sheet was re-checked
 * against Tailwind Typography's `1.25em` and, above it, WCAG SC 1.4.12's
 * requirement that a layout survive a `2em` user override: a body that grows to
 * 16 sp needs a paragraph step that scales with it rather than a fixed 12. 经典
 * keeps today's 2 dp, which is what makes it today.
 *
 * Two of those rows deserve their reasoning written down, because both are
 * places where the task book and the code disagreed and the code won:
 *
 * * **Classic's headings are not 28/22/17/15/15/14.** The direction sheet lists
 *   those six numbers as "今天", but they are Material 3's
 *   `displaySmall`/`headlineSmall`/`titleMedium`/`titleSmall`/`bodyLarge`/
 *   `bodyMedium` slots (`PiTheme.kt`'s `piTypography`), not the markdown heading
 *   sizes. The renderer's h1…h6 are hard-coded in `render/PiMarkdownTheme.kt` at
 *   22/20/18/16/15/14 and have been since before this feature, so those are what
 *   classic keeps.
 * * **Classic's inline code is 12.5/400, not 13/400.** Today's `inlineCode` slot
 *   is `mono.copy(fontSize = 12.5.sp, lineHeight = 18.sp)` — a half-step off the
 *   `code` role, and the sheet's 13 is that role's number. Classic keeps 12.5.
 *
 * **表格不随预设动.** The table's cell width (160 dp), its horizontal scroll,
 * its corner size (8 dp), its cell padding (8 dp) and its role (12.5/18) are all
 * today's at every preset — the user's own ruling, and the reason this profile
 * carries no table field at all.
 *
 * ## The two multipliers
 *
 * `app.appearance.lineHeight` multiplies every **leading** (never a size) by
 * 0.92 / 1.0 / 1.08. **Classic is exempt**: the multiplier is forced to 1.0
 * there, which is what makes "经典 = 今天" survive the row — otherwise the
 * default would itself be a multi-preset app rather than the previous one.
 *
 * `app.appearance.fontSize` adds −1 / 0 / +1 / +2 sp to **every role in
 * [PiTextStyles]**, exactly where `fontScaleDelta` used to add its ±2. It is
 * applied through [PiTextStyles.scaled], so size and leading move together as
 * they always did. It deliberately does **not** move the markdown headings:
 * `fontScaleDelta` never did, and this row is that row with four named steps
 * instead of five anonymous ones. (The M3 `Typography` slots keep taking the
 * offset, as they did.)
 */
@Immutable
data class PiTypographyProfile(
    /** One of [CLASSIC] / [COMPACT] / [COMFORTABLE] / [LOOSE]. */
    val preset: String,
    /** One of `tight` / `normal` / `loose`; ignored when [preset] is [CLASSIC]. */
    val lineHeight: String,
    /** One of `small` / `normal` / `large` / `xlarge`. */
    val fontSize: String,
    /** The five text roles, with the leading multiplier and the size offset applied. */
    val styles: PiTextStyles,
    val h1: PiHeading,
    val h2: PiHeading,
    val h3: PiHeading,
    val h4: PiHeading,
    val h5: PiHeading,
    val h6: PiHeading,
    /** `inlineCode`: `mdCode`-coloured, so only its size/leading/weight move. */
    val inlineCodeSizeSp: Float,
    val inlineCodeLineHeightSp: Float,
    val inlineCodeWeight: FontWeight,
    /** Markdown paragraph spacing (`MarkdownPadding.block`). */
    val markdownBlock: Dp,
    val markdownListItemTop: Dp,
    val markdownListItemBottom: Dp,
    val markdownListIndent: Dp,
    /** The code block's corner radius (`MarkdownDimens.codeBackgroundCornerSize`). */
    val codeBlockRadius: Dp,
    /** The transcript `LazyColumn`'s `Arrangement.spacedBy`. */
    val blockSpacing: Dp,
    /** The `sp` this profile's [fontSize] adds to every [styles] role. */
    val fontSizeOffsetSp: Int,
) {
    /**
     * True for 经典. The one preset that promises byte-for-byte the previous
     * look, and the only one the leading multiplier does not touch.
     */
    val isClassic: Boolean get() = preset == CLASSIC

    companion object {
        const val CLASSIC = "classic"
        const val COMPACT = "compact"
        const val COMFORTABLE = "comfortable"
        const val LOOSE = "loose"

        /** Unknown or absent → the default preset, never a crash over a text file. */
        fun presetOf(raw: String?): String = when (raw) {
            CLASSIC -> CLASSIC
            COMPACT -> COMPACT
            LOOSE -> LOOSE
            else -> COMFORTABLE
        }

        /** Unknown or absent → `normal`, i.e. today's leading, untouched. */
        fun lineHeightOf(raw: String?): String = when (raw) {
            "tight" -> "tight"
            "loose" -> "loose"
            else -> "normal"
        }

        /** Unknown or absent → `normal`, i.e. no offset. */
        fun fontSizeOf(raw: String?): String = when (raw) {
            "small" -> "small"
            "large" -> "large"
            "xlarge" -> "xlarge"
            else -> "normal"
        }

        /** `−1 / 0 / +1 / +2 sp`; the row's four steps, in one place. */
        fun offsetSp(fontSize: String): Int = when (fontSize) {
            "small" -> -1
            "large" -> 1
            "xlarge" -> 2
            else -> 0
        }

        /**
         * 一次性读时迁移：`app.appearance.messageDensity` → `app.appearance.typography`。
         *
         * The two old keys are **gone from the registry**, so this is the only
         * place in the tree that still reads them — which is exactly the point:
         * leaving the rows registered would be two truths for one decision, and
         * deleting the keys without this would silently reset a preference the
         * user had already expressed. The old wire values were the option names
         * `compact` / `comfortable` / `cozy`; the new set is
         * `compact` / `comfortable` / `loose`, so only the last one is renamed.
         *
         * Read-time, not a one-off rewrite of the file: nothing writes the new
         * key here, so a user who has not touched the row keeps being migrated
         * on every read and the two documents never disagree. When the new key
         * *is* present it always wins (its reader takes it directly), so a later
         * edit is not overwritten by a stale legacy value.
         */
        fun migratedTypography(legacyMessageDensity: String?): String = when (legacyMessageDensity) {
            COMPACT -> COMPACT
            COMFORTABLE -> COMFORTABLE
            "cozy" -> LOOSE
            else -> COMFORTABLE
        }

        /**
         * 一次性读时迁移：`app.appearance.fontScaleDelta` → `app.appearance.fontSize`。
         *
         * The old row was a `Number` (±2 sp), the new one is four named steps:
         * `−2/−1 → small`, `0 → normal`, `1 → large`, `2 → xlarge`. The legacy
         * value arrives as the JSON primitive's own text, because the old row was
         * written as a number and never as a string. Same read-time contract as
         * [migratedTypography].
         */
        fun migratedFontSize(legacyFontScaleDelta: String?): String =
            when (legacyFontScaleDelta?.toIntOrNull()) {
                -2, -1 -> "small"
                1 -> "large"
                2 -> "xlarge"
                else -> "normal"
            }

        // The rows of this class's KDoc table, verbatim. Declared inside the
        // companion, before `Default`, so the object's initialisation order is linear:
        // the constants, then these tables, then `Default`.
        private val PRESETS: Map<String, Preset> = mapOf(
            CLASSIC to Preset(
                proseSize = 14, proseLine = 23,
                block = 2, listItem = 2, indent = 12,
                codeSize = 13, codeLine = 19,
                radius = 12, blockSpacing = 8,
                inlineSize = 12.5f, inlineWeight = 400,
                headings = intArrayOf(22, 20, 18, 16, 15, 14),
            ),
            COMPACT to Preset(
                proseSize = 15, proseLine = 24,
                block = 8, listItem = 4, indent = 18,
                codeSize = 13, codeLine = 21,
                radius = 10, blockSpacing = 6,
                inlineSize = 13f, inlineWeight = 600,
                headings = intArrayOf(21, 19, 17, 15, 15, 14),
            ),
            COMFORTABLE to Preset(
                proseSize = 16, proseLine = 28,
                block = 16, listItem = 6, indent = 20,
                codeSize = 14, codeLine = 24,
                radius = 10, blockSpacing = 8,
                inlineSize = 14f, inlineWeight = 600,
                headings = intArrayOf(22, 20, 18, 16, 16, 15),
            ),
            LOOSE to Preset(
                proseSize = 17, proseLine = 31,
                block = 24, listItem = 10, indent = 22,
                codeSize = 14, codeLine = 26,
                radius = 10, blockSpacing = 12,
                inlineSize = 14f, inlineWeight = 600,
                headings = intArrayOf(24, 21, 19, 17, 17, 16),
            ),
        )

        /**
         * Today's per-slot heading leading, as a ratio of the slot's size.
         *
         * They are not one number: `PiMarkdownTheme.kt` pairs 22/30, 20/28, 18/26,
         * 16/24, 15/22, 14/21, i.e. 1.36 → 1.5 as the size falls. Re-using each
         * slot's own ratio reproduces all six classic leadings exactly (22 × 30/22
         * = 30, …) and gives the other presets a leading that follows their size
         * instead of a second hand-written table.
         */
        private val HEADING_LEADING_RATIO = doubleArrayOf(
            30.0 / 22.0, 28.0 / 20.0, 26.0 / 18.0, 24.0 / 16.0, 22.0 / 15.0, 21.0 / 14.0,
        )

        private fun heading(numbers: Preset, slot: Int, leading: Double): PiHeading {
            val size = numbers.headings[slot]
            return PiHeading(size, (size * HEADING_LEADING_RATIO[slot] * leading).roundToInt())
        }

        /** The whole profile from the three stored strings. */
        fun forPreset(preset: String, lineHeight: String, fontSize: String): PiTypographyProfile {
            val resolved = presetOf(preset)
            val numbers = PRESETS.getValue(resolved)
            val leading = if (resolved == CLASSIC) {
                1.0
            } else {
                when (lineHeightOf(lineHeight)) {
                    "tight" -> 0.92
                    "loose" -> 1.08
                    else -> 1.0
                }
            }
            val offset = offsetSp(fontSizeOf(fontSize))

            return PiTypographyProfile(
                preset = resolved,
                lineHeight = lineHeightOf(lineHeight),
                fontSize = fontSizeOf(fontSize),
                // 经典 hands back `PiTextStyles.Default` itself rather than a copy of its
                // numbers, so "classic is today" cannot drift if one of the two is ever
                // edited. `leadingScaled(1.0)` and `scaled(0)` are both identity.
                styles = if (resolved == CLASSIC) {
                    PiTextStyles.Default
                } else {
                    PiTextStyles(
                        // 次要：13/20 400. The row's own number, and the same leading as the
                        // 13 sp `mono` role so one size has one leading in this app.
                        meta = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        mono = PiTextStyles.Default.mono,
                        // 最次：12/18 400 — today's role, unchanged (the row says so).
                        monoSmall = PiTextStyles.Default.monoSmall,
                        // 正文：the preset's size and leading, at 字重 500 (400 in classic).
                        prose = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = numbers.proseSize.sp,
                            lineHeight = numbers.proseLine.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        code = TextStyle(
                            fontFamily = PiMonoFamily,
                            fontSize = numbers.codeSize.sp,
                            lineHeight = numbers.codeLine.sp,
                        ),
                    )
                }
                    .leadingScaled(leading)
                    .scaled(offset),
                h1 = heading(numbers, 0, leading),
                h2 = heading(numbers, 1, leading),
                h3 = heading(numbers, 2, leading),
                h4 = heading(numbers, 3, leading),
                h5 = heading(numbers, 4, leading),
                h6 = heading(numbers, 5, leading),
                inlineCodeSizeSp = numbers.inlineSize,
                // Inline code keeps a leading close to the body's own box: today it is
                // 12.5/18 next to a 14/23 body. The preset moves the size, not the ratio —
                // a 14 sp inline run in a 24 sp code-block leading would push the paragraph
                // apart, which is the opposite of what an inline span should do.
                inlineCodeLineHeightSp = if (numbers.inlineSize >= 14f) 19f else 18f,
                inlineCodeWeight = if (numbers.inlineWeight >= 600) FontWeight.SemiBold else FontWeight.Normal,
                markdownBlock = numbers.block.dp,
                markdownListItemTop = numbers.listItem.dp,
                markdownListItemBottom = numbers.listItem.dp,
                markdownListIndent = numbers.indent.dp,
                codeBlockRadius = numbers.radius.dp,
                blockSpacing = numbers.blockSpacing.dp,
                fontSizeOffsetSp = offset,
            )
        }

        /** 舒适 at standard leading, no offset — what an unset profile resolves to. */
        val Default: PiTypographyProfile = forPreset(COMFORTABLE, "normal", "normal")
    }
}

/**
 * A preset's numbers, one field per number the render path needs. Private: the
 * public surface is [PiTypographyProfile] and its `forPreset`, so a caller
 * cannot read half a preset.
 */
private class Preset(
    val proseSize: Int,
    val proseLine: Int,
    val block: Int,
    val listItem: Int,
    val indent: Int,
    val codeSize: Int,
    val codeLine: Int,
    val radius: Int,
    val blockSpacing: Int,
    val inlineSize: Float,
    val inlineWeight: Int,
    /** h1…h6, in that order. */
    val headings: IntArray,
)

/**
 * Every role's leading multiplied by [factor], sizes untouched.
 *
 * `factor == 1.0` is identity — the common case (every `normal` profile, and
 * every classic one), so a normal preset allocates nothing here.
 */
private fun PiTextStyles.leadingScaled(factor: Double): PiTextStyles {
    if (factor == 1.0) return this
    fun TextStyle.lead(): TextStyle = copy(lineHeight = (lineHeight.value * factor).roundToInt().sp)
    return PiTextStyles(
        meta = meta.lead(),
        mono = mono.lead(),
        monoSmall = monoSmall.lead(),
        prose = prose.lead(),
        code = code.lead(),
    )
}
