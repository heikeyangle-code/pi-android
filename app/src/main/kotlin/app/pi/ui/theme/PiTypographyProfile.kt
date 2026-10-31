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
 * 经典 keeps today's **numbers**: [styles] for `classic` is literally
 * [PiTextStyles.Default], the heading sizes are the ones `PiMarkdownTheme.kt`
 * already hard-coded (22/20/18/16/15/14), the paragraph gap is today's 2 dp, the
 * indent is today's 12 dp, and 行距 does not move it. Those numbers are also this
 * whole file's **anchor**: 14/23 is where the non-classic leadings' rule comes
 * from (below), so re-cutting them would be re-cutting the scale rather than
 * perfecting it.
 *
 * The one thing 经典 does *not* keep is the platform's text metrics: the
 * app-wide `TextStyle.piPrecise()` (`PiTheme.kt`) — no `includeFontPadding`, leading
 * centred in the line box, first/last line trimmed — applies to `PiTextStyles.Default`
 * and therefore to 经典 too. It is a metric, not a preset number, and giving it to
 * every preset but one would leave two different text engines in one app.
 *
 * The other three come from the approved direction sheet
 * (`/root/design-preview/typography-preview.html`, screenshots `s1.png`–`s3.png`)
 * and its sources: Tailwind Typography for the prose scale and Android M3's 16 sp
 * body for the comfortable step.
 *
 * ## 阅读字体（2026-10-31 字体升级）
 *
 * 聊天 Markdown 的阅读正文用随包的思源宋体（`PiProseFamily`，Source Han Serif CN
 * 2.003R 可变字体，OFL-1.1），但**不新增排版角色**：`render/PiMarkdownTheme.kt`
 * 在自己的消费点把本档 `styles.prose` 的字族覆盖成 `PiProseFamily`、字重 400。
 * 四档的字号/行高仍就是下表的 `proseSize`/`proseLine`（经典 14/23、紧凑 15/24、
 * 舒适 16/25、宽松 17/26）；`prose` 本身仍是系统 Sans，设置页、Boot、文件页、
 * 诊断页不受影响。粗体由 Markdown 的 `**strong**` 落到 `PiProseFamily` 里注册为
 * `Bold` 的真实 SemiBold 实例上，不出现合成粗体。
 *
 * **The sheet's 1.75–1.8 leading for 舒适 was not kept, and that is the one place
 * this file now contradicts the direction sheet.** The number came from Latin
 * reading practice (and from Tailwind's `prose` scale, which is a web-Latin scale),
 * and it was applied here to CJK body text. The two are not the same quantity: a
 * Latin line box has to clear ascenders and descenders, while a CJK glyph fills
 * ~0.87 em of its em box with almost no variation above and below, so **the same
 * ratio leaves visibly more air on Chinese text** — which is what "舒适和松都有点太松垮"
 * is. The app's own board draws its body at **1.62** (v2 `.t14{font-size:14px;
 * line-height:1.62}`, `direction-b-v2.html:157`) and 经典 — the look the user is
 * happy with — is **1.643**; the preset's 1.75/1.82 were the outliers, not the
 * board. The three non-classic leadings are therefore CJK ratios (1.53–1.60) and
 * they **fall as the size rises**, which is the ordinary typographic rule
 * (larger type needs proportionally less leading) and the reason the largest
 * preset used to feel loose twice over: it grew the glyphs *and* the ratio.
 * **No colour moves with a preset** —
 * the sheet only re-uses `PiPalette` tokens, and everything here is a size or a
 * gap. (The draft's "unify the card/code-block radius to 10" was **dropped**: the
 * cards and the code block are already 12 today, so there was no inconsistency to
 * fix, and 10 is not a multiple of 8 while 12 is — see the file's git history and
 * `PiMarkdownDimens`. The table's 8 was never in question.)
 *
 * |                    | classic | compact | comfortable | loose |
 * |--------------------|---------|---------|-------------|-------|
 * | prose size/leading |  14/23  |  15/24  |   16/25     | 17/26 |
 * | markdown `block`   |    2    |    8    |     12      |  20   |
 * | list item top/bot  |   2/2   |   3/3   |     4/4     |  5/5  |
 * | list indent        |   12    |   16    |     20      |  24   |
 * | inline code size/w | 12.5/400|  13/500 |    14/500   | 15/500|
 * | code size/leading  |  13/19  |  13/20  |    14/22    | 15/23 |
 * | table size/leading | 12.5/18 |  13/20  |    14/22    | 15/23 |
 * | headings size/leading | 22/30 20/28 18/26 16/24 15/22 14/21 | 24/29 22/27 20/25 18/23 17/22 16/21 | 25/30 23/28 21/26 19/24 18/23 17/22 | 26/31 24/29 22/27 20/25 19/24 18/23 |
 * | transcript gap     |    8    |    4    |      8      |  12   |
 * | 换人：消息之前       |    8    |   16    |     20      |  32   |
 *
 * 2026-10-26 修订（经典档不动）：列表项上下 padding 改为 **3 / 4 / 5**
 * （项间实际 6 / 8 / 10；库的 `list` 是整组上下 padding，非经典收到 0），
 * 行内代码字重 600 → 500（与正文一致，等宽本来就显重）。下面的段落里
 * 「列表项 4 / 8 / 8」和「600」是修订前的历史数字。
 *
 * The body leading ratios are **1.643 / 1.600 / 1.563 / 1.529** — flat to within
 * 7 %, and monotonically **falling** as the size rises (the leading itself still
 * grows: 23 / 24 / 25 / 26 sp). The paragraph step is **8 / 12 / 20**: each level
 * of the transcript's hierarchy is one 4 dp grid unit away from the next
 * (同一条消息内部 + 8 = 段间距; 段间距 + 8 or + 12 = 换人间距), which is the
 * arithmetic behind "these two gaps are actually distinguishable":
 * 紧凑 4 < 8 < 16, 舒适 8 < 12 < 20, 宽松 12 < 20 < 32 — every pair is ≥ 1.5×
 * apart **and** ≥ 8 dp apart. 经典 was 2/8/8 for 段间距/块间距/换人, i.e. the
 * levels did not exist there; that is what makes it today, and it is untouched.
 *
 * 舒适's paragraph step is **12**, not the 16 this feature first shipped: at
 * 16 sp body that is 0.75 em, and 0.55 × its own line height (25 × 0.55 = 13.75)
 * is the rule the design spec states (`docs/pi-android-ui-spec.md:123`
 * 「段落间距 = 0.55 × 行高」) — 12 is the nearest 4 dp multiple. The 16 also made
 * the gap *equal* to what a card between two paragraphs already produces
 * (同一条消息内部 8 + 8 = 16), so a tool card read exactly like a paragraph
 * break; at 12 the card reads as the bigger break it is.
 * 宽松's 24 → 20 has the same two reasons (a 24 dp gap on a 17 sp body is 1.41 em,
 * and it left only 8 dp of difference from its own 换人间距).
 * 紧凑's 8 is left alone: its three levels are already 2× apart (the one residue is
 * that 2 × its 4 dp 同一条消息内部 = 8 = 段间距, so a card between two paragraphs is
 * still exactly as wide as a paragraph break there; raising the paragraph step to
 * 12 would make 紧凑's own 换人间距 collide with its 换人 layer and dropping the
 * block gap to 2 dp would leave the 4 dp grid — both are worse than the collision).
 *
 * 列表项与缩进跟着上了 4 dp 网格：紧凑 16（1.07 em）、舒适 20（1.25）、宽松 24（1.41），
 * 列表项 4 / 8 / 8（0.27 / 0.50 / 0.47 em，Tailwind 的 `li` 台阶就是 0.5 em）。
 * 改动只有三处：紧凑缩进 18→16、宽松缩进 22→24、宽松列表项 10→8 ——
 * 18/22/10 都不在 4 dp 网格上，而这是全表唯一一处「不是网格值」的地方。
 *
 * **The heading ladder is shifted up by the body's growth.** Every non-classic
 * ladder has 经典's exact step shape (2/2/2/1/1) and the bottom rung clears the
 * body by 1 sp — 紧凑 24…16 over a 15 sp body, 舒适 25…17 over 16, 宽松 26…18
 * over 17. The previous numbers put **h6 below the body** (舒适 15 < 16) and made
 * **h4 and h5 the same size** (16/16) with 1 dp of leading between them: two of
 * six levels were not a level at all, and the smallest was smaller than a
 * paragraph. 经典 keeps its own 22/20/18/16/15/14 (its h6 == body — the one place
 * the frozen档 is below this file's own rule, and it is left alone rather than
 * re-cut: see below).
 *
 * **标题行高按任务书重排（2026-10-31）**：舒适档 h1…h6 为 25/30、23/28、21/26、
 * 19/24、18/23、17/22；紧凑/宽松用**同一组比例**（30/25、28/23、26/21、24/19、
 * 23/18、22/17）乘各自字号四舍五入，得紧凑 24/29…16/21、宽松 26/31…18/23。
 * 经典保持它自己的 30/28/26/24/22/21 不变 —— 那是它「保留改前密度」的承诺。
 * 这组比例是 1.20–1.29，**低于正文的 CJK 行距地板 1.40**，所以「行距」档的
 * ×0.92/×1.08 落在带外被丢弃：标题行高只按本表，不随「行距」移动；正文、行内
 * 代码、代码块与表格仍照旧随「行距」移动（见下节）。
 *
 * 换人那一行 takes the single number above, and 同一条消息内部 keeps the transcript
 * gap. **One** 换人 number, not one per speaker: that is what every chat UI does
 * (`chatview`'s `messageGroupSpacing` is a single parameter), and an asymmetry
 * between "before a user message" and "before an assistant message" would be a
 * difference no user can see the reason for. It also keeps the three gaps ordered
 * for the three non-classic presets — 同一条消息内部 < 段间距 < 换人间距
 * (紧凑 4 < 8 < 16, 舒适 8 < 12 < 20, 宽松 12 < 20 < 32). 经典 sets all three to 8,
 * the single uniform gap the transcript had before this existed. pi's terminal has
 * no such notion, so the number is the app's own. 舒适's 20 — not 24 — is a
 * deliberate down-adjustment: 20 is still on the 4 dp grid, is still strictly larger
 * than 舒适's own 12 dp paragraph step (so a new message still reads as a new
 * message), and no longer collides with 紧凑's 16.
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
 * **A table follows the preset's type, and only its type.** [tableSizeSp] and
 * [tableLineHeightSp] are the two numbers this profile carries for a table
 * (经典 12.5/18 — the `mono` override the renderer had hard-coded before this
 * feature). Everything else about a table is today's at **every** preset: the cell
 * width is 160 dp, the horizontal scroll is the library's
 * (`render/PiMarkdownComponents.kt`'s `PiTable` only adds a header tint and a
 * right-edge fade on top of it, and does not touch either), the corner size is
 * 8 dp, the cell padding is 8 dp and the surface is `cardBg`. The preset carries
 * no other table field for that reason. Both multipliers do reach the table's two
 * numbers now, along with every other role: a cell is a monospace box whose leading
 * only decides how tall the cell is (the column width it has to fit into is the
 * renderer's 160 dp), so stepping its leading changes no line count — but that is an
 * argument for it being *harmless*, not for it being the one role that ignores the
 * two rows. Left out, the table was the only place where `字号 = 更大` moved 正文 to
 * 18 sp and left the cell at 14 (0.78 em, i.e. fine print at an 18 sp body).
 *
 * Why those four sizes: Tailwind Typography draws a `prose` table at `0.875em`,
 * and on a phone whose body is at least 16 sp that is 14 sp — 舒适's number. The
 * leading ratio stays at or above 1.53 for every new step (13/20 = 1.538,
 * 14/22 = 1.571, 15/23 = 1.533), the documented floor for small CJK text being
 * 1.5. 经典's 12.5/18 is 1.44 and deliberately below that floor: it is the value
 * that was already on screen, not a number chosen now.
 *
 * **The code fence's leading is now the table's, number for number** (13/20,
 * 14/22, 15/23). Both are machine output inside a fixed-width box; there was no
 * argument for them to disagree, and the old code leadings (13/21, 14/24, 15/26 —
 * ratios 1.615 / 1.714 / 1.733) were *looser than the body they sat in*, which is
 * the opposite of what a scanned, monospaced block wants. Keeping the two equal
 * also means the body's own ratio (1.53–1.60) is now the ratio every
 * fixed-width role uses, so "how many roles does this system have" has the answer
 * two (body/fixed-width at ~1.55, headings at 1.36–1.53) instead of five.
 *
 * ## The two multipliers
 *
 * `app.appearance.lineHeight` multiplies every **body, code and table leading**
 * (never a size) by 0.92 / 1.0 / 1.08, and the result must stay inside
 * [MIN_LEADING_RATIO]…[MAX_LEADING_RATIO] (1.40–1.85): a step that would leave
 * that band is **dropped entirely** and the base leading stands. 1.40 is the floor
 * below which CJK glyphs start to collide (a CJK glyph fills ~0.87 em of its box,
 * so 1.40 still leaves ~0.5 em of clearance — the old 1.5 came from Latin
 * ascender/descender clearance, which Chinese does not have); 1.85 is where a
 * body column starts to come apart. Because the presets now sit at 1.53–1.60,
 * **both steps apply at every non-classic preset** — the row used to be a no-op in
 * half its combinations:
 *
 * * 紧凑 is 15/24 (1.60) — `tight` is 22 (1.47); `loose` is 26 (1.73).
 * * 舒适 is 16/25 (1.56) — `tight` is 23 (1.44); `loose` is 27 (1.69).
 * * 宽松 is 17/26 (1.53) — `tight` is 24 (1.41); `loose` is 28 (1.65).
 * * 经典 is 14/23 (1.64) and, as below, exempt altogether.
 *
 * **Classic is exempt from the leading multiplier**: it is forced to 1.0 there,
 * which is what keeps 经典's numbers the ones the app had before this feature.
 * (That is now a *design* choice rather than a floor: 经典 is the preset a user
 * picks to get the old scale back, so a row that re-times every leading would
 * delete the thing that preset promises. The app-wide text metrics do reach it —
 * see the note at the top of this KDoc.)
 *
 * `app.appearance.fontSize` adds −1 / 0 / +1 / +2 sp to **every role in
 * [PiTextStyles]**, and now to the markdown profile's own roles as well (the six
 * heading slots, the inline-code size, and the table's two numbers) — all by the
 * same flat `±sp` on size *and* leading, which is [PiTextStyles.scaled]'s rule.
 * Leaving the markdown roles out was the earlier behaviour and it broke this
 * file's own rule: at `字号 = 更大` the body (18 sp) overtook h5 and h6 (18/17),
 * so three of the six heading levels were no longer headings. Because every slot
 * gets the **same** offset, the heading ladder's shape (2/2/2/1/1) and the
 * invariant **h6 = 正文 + 1** hold at all four steps.
 *
 * The row **changes the ratio** (by exactly `offset / size`), and that is
 * deliberate: a flat ±sp on both numbers is what `fontScaleDelta` did and this row
 * is that row with four named steps instead of five anonymous ones. The drift is
 * harmless: across all three presets and all four steps the body ratio only ranges
 * over **1.47…1.64**, inside the band above. (Before the leading was de-Latinised
 * it reached **1.88** at 宽松 + 小, outside the band this file claims to enforce —
 * the band is checked against the 行距 step only, never against this offset, so
 * the hole was real and closing it by shrinking the preset leadings was cheaper
 * than redefining the row.)
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
    /**
     * 表格单元格的字号/行高，`sp`。阅读字族（思源宋体）与 `body` 配色由
     * `render/PiMarkdownTheme.kt` 给，这里只有两个数。
     *
     * 这两个数与别的角色同待遇：`app.appearance.lineHeight` 按 [steppedLeading] 乘行高、
     * `app.appearance.fontSize` 平移两者（见 `forPreset`）。理由写在类注释那句
     * 「A table follows the preset's type」里。
     *
     * 表格的其余一切（列宽按可用宽拟合、120 dp 下限、横滑、圆角 8 dp、内边距 8 dp、
     * `cardBg` 底色）不随预设动；这一笔没有碰它们。
     */
    val tableSizeSp: Float,
    val tableLineHeightSp: Float,
    /** Markdown paragraph spacing (`MarkdownPadding.block`). */
    val markdownBlock: Dp,
    val markdownListItemTop: Dp,
    val markdownListItemBottom: Dp,
    val markdownListIndent: Dp,
    /**
     * The gap above the first row of a **new** message (换人间距) — user and
     * assistant alike, one number.
     *
     * `render/TranscriptSpacing.kt` decides which row starts a message, and
     * `screens/ChatScreen.kt` applies the difference against [blockSpacing] as that
     * row's own top padding; the list's `spacedBy` stays [blockSpacing], so 同一条
     * 消息内部 and 换人间距 cannot disagree about the baseline.
     */
    val messageSpacing: Dp,
    /** The transcript `LazyColumn`'s `Arrangement.spacedBy`, i.e. 同一条消息内部. */
    val blockSpacing: Dp,
    /** The `sp` this profile's [fontSize] adds to every text role, headings included. */
    val fontSizeOffsetSp: Int,
) {
    /**
     * True for 经典. The preset that keeps the pre-feature scale (and the only one
     * the leading multiplier does not touch); its text metrics still go through
     * `TextStyle.piPrecise()` like every other preset's.
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
                blockSpacing = 8,
                messageGap = 8,
                inlineSize = 12.5f, inlineWeight = 400,
                tableSize = 12.5f, tableLine = 18,
                headings = intArrayOf(22, 20, 18, 16, 15, 14),
            ),
            COMPACT to Preset(
                proseSize = 15, proseLine = 24,
                // 2026-10-26: 列表项 padding 4 → 3。库的 listItemTop/Bottom 是
                // 每项上下 padding，不是「项间 4 + 两倍 padding」；2×3 = 6 才是
                // 目标项间距（舒适 2×4=8、宽松 2×5=10）。
                block = 8, listItem = 3, indent = 16,
                codeSize = 13, codeLine = 20,
                blockSpacing = 4,
                messageGap = 16,
                // 行内代码不再比正文重一档：等宽本来就显重，500 = 正文。
                inlineSize = 13f, inlineWeight = 500,
                tableSize = 13f, tableLine = 20,
                headings = intArrayOf(24, 22, 20, 18, 17, 16),
            ),
            COMFORTABLE to Preset(
                proseSize = 16, proseLine = 25,
                block = 12, listItem = 4, indent = 20,
                codeSize = 14, codeLine = 22,
                blockSpacing = 8,
                messageGap = 20,
                inlineSize = 14f, inlineWeight = 500,
                tableSize = 14f, tableLine = 22,
                headings = intArrayOf(25, 23, 21, 19, 18, 17),
            ),
            LOOSE to Preset(
                proseSize = 17, proseLine = 26,
                block = 20, listItem = 5, indent = 24,
                codeSize = 15, codeLine = 23,
                blockSpacing = 12,
                messageGap = 32,
                inlineSize = 15f, inlineWeight = 500,
                tableSize = 15f, tableLine = 23,
                headings = intArrayOf(26, 24, 22, 20, 19, 18),
            ),
        )

        /**
         * 经典档每个标题槽的行高比例：与 `PiMarkdownTheme.kt` 改造前写死的
         * 22/30、20/28、18/26、16/24、15/22、14/21 逐值相同（1.36 → 1.50）。
         * 经典档的 factor 恒为 1.0，所以这组比例原样落地，密度不动。
         */
        private val CLASSIC_HEADING_LEADING_RATIO = doubleArrayOf(
            30.0 / 22.0, 28.0 / 20.0, 26.0 / 18.0, 24.0 / 16.0, 22.0 / 15.0, 21.0 / 14.0,
        )

        /**
         * 非经典档每个标题槽的行高比例，取自任务书舒适档的
         * 25/30、23/28、21/26、19/24、18/23、17/22（即 30/25 … 22/17）。
         * 紧凑/宽松用**同一组比例**乘各自字号四舍五入，不再各自手写一张行高表 ——
         * 四档的标题节奏因此是同一个形状（比例落 1.20–1.29）。
         */
        private val HEADING_LEADING_RATIO = doubleArrayOf(
            30.0 / 25.0, 28.0 / 23.0, 26.0 / 21.0, 24.0 / 19.0, 23.0 / 18.0, 22.0 / 17.0,
        )

        private fun heading(
            numbers: Preset,
            slot: Int,
            leading: Double,
            offsetSp: Int = 0,
            classic: Boolean = false,
        ): PiHeading {
            val size = numbers.headings[slot]
            val ratio = (if (classic) CLASSIC_HEADING_LEADING_RATIO else HEADING_LEADING_RATIO)[slot]
            val stepped = ratio * leading
            // 行距乘子只在结果落回 1.40–1.85 时才生效。经典档 factor 恒为 1.0（不乘）；
            // 非经典档的标题比例是任务书的 1.20–1.29，×0.92/×1.08 后为 1.10–1.40，
            // 仍落在正文的 CJK 行距地板之外 —— 紧/松两档都被丢弃，标题行高固定按本表。
            // 这是把标题表落成任务书数字的直接结果：比例低于 1.40 的标题本来就不该再
            // 被「行距」压低；正文/代码/表格仍照旧随「行距」移动。
            val effective = if (stepped < MIN_LEADING_RATIO || stepped > MAX_LEADING_RATIO) ratio else stepped
            val base = PiHeading(size, (size * effective).roundToInt())
            // `字号` 的平移与 [PiTextStyles.shifted] 同一口径：字号与行高各 +offsetSp。
            // 六个槽加同一个数，所以阶梯形状（2/2/2/1/1）与「h6 = 正文 + 1」在四个
            // `字号` 档上都不变。
            return if (offsetSp == 0) {
                base
            } else {
                PiHeading(base.sizeSp + offsetSp, base.lineHeightSp + offsetSp)
            }
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
                        ).piPrecise(),
                        mono = PiTextStyles.Default.mono,
                        // 最次：12/18 400 — today's role, unchanged (the row says so).
                        monoSmall = PiTextStyles.Default.monoSmall,
                        // 正文：the preset's size and leading, at 字重 500 (400 in classic).
                        prose = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = numbers.proseSize.sp,
                            lineHeight = numbers.proseLine.sp,
                            fontWeight = FontWeight.Medium,
                        ).piPrecise(),
                        code = TextStyle(
                            fontFamily = PiMonoFamily,
                            fontSize = numbers.codeSize.sp,
                            lineHeight = numbers.codeLine.sp,
                        ).piPrecise(),
                    )
                }
                    .leadingScaled(leading)
                    .scaled(offset),
                // 标题槽也吃 `字号` 偏移（与 [PiTextStyles.scaled] 同一机制：字号与行高
                // 各平移 offset）。不这样做的话，`字号 = 更大` 时正文（18）会超过 h6（17）
                // —— 「标题严格大于正文」这条系统不变量必须对四个 `字号` 档都成立，
                // 而 h6 = 正文 + 1 是在**同一个偏移下**成立的（两个数各加同一个 offset）。
                h1 = heading(numbers, 0, leading, offset, classic = resolved == CLASSIC),
                h2 = heading(numbers, 1, leading, offset, classic = resolved == CLASSIC),
                h3 = heading(numbers, 2, leading, offset, classic = resolved == CLASSIC),
                h4 = heading(numbers, 3, leading, offset, classic = resolved == CLASSIC),
                h5 = heading(numbers, 4, leading, offset, classic = resolved == CLASSIC),
                h6 = heading(numbers, 5, leading, offset, classic = resolved == CLASSIC),
                // 行内代码的字号跟着走，行高跟着平移（它与标题槽同一处理）。
                // 注意：这一对行高**进不了渲染** —— Compose 的 `SpanStyle` 没有
                // `lineHeight`（库把 `inlineCode` 当 span 压进 AnnotatedString，
                // 见 `docs/render-inventory.md` §2.9），真正生效的只有字号与字重。
                // 仍然一起平移，是为了这一对数字自己不自相矛盾。
                inlineCodeSizeSp = numbers.inlineSize + offset,
                inlineCodeLineHeightSp =
                    (if (numbers.inlineSize >= 14f) 19f else 18f) + offset,
                inlineCodeWeight = when {
                    numbers.inlineWeight >= 600 -> FontWeight.SemiBold
                    numbers.inlineWeight >= 500 -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                // 表格与别的角色同待遇：两个乘子都吃。列宽是渲染器写死的 160 dp，
                // 所以行高只决定单元格多高、不改变它要挤进几列 —— 但「唯一的例外」
                // 本身就是系统里的一个洞：`字号 = 更大` 时正文 18 而表格停在 14，
                // 单元格相对正文只有 0.78 em。这里按同一机制平移（`steppedLeading`
                // 处理行距、`+ offset` 处理字号），表格的两个数因此和同档代码块一致。
                tableSizeSp = numbers.tableSize + offset,
                tableLineHeightSp = steppedLeading(
                    numbers.tableLine.toFloat(),
                    numbers.tableSize,
                    leading,
                ) + offset,
                markdownBlock = numbers.block.dp,
                markdownListItemTop = numbers.listItem.dp,
                markdownListItemBottom = numbers.listItem.dp,
                markdownListIndent = numbers.indent.dp,
                messageSpacing = numbers.messageGap.dp,
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
    val blockSpacing: Int,
    /** 换人间距: above the first row of a new message (one number, either speaker). */
    val messageGap: Int,
    val inlineSize: Float,
    val inlineWeight: Int,
    /** 表格单元格的字号/行高；经典 12.5/18 是改前写死的那个 `mono.copy(…)`。 */
    val tableSize: Float,
    val tableLine: Int,
    /** h1…h6, in that order. */
    val headings: IntArray,
)

/**
 * The band a **leading ratio** (`lineHeight / fontSize`) may live in, applied to
 * the 行距 step (and to each heading slot, by the same rule).
 *
 * **1.40** is the floor below which CJK glyphs start to collide. It used to be
 * 1.50, and that number was a **Latin** one — a Latin line box has to keep
 * ascenders and descenders of one line clear of the next, which is why 1.5 is the
 * familiar English floor. A CJK glyph fills ~0.87 em of its em box with no
 * ascender/descender variation, so at 1.40 there is still ~0.5 em of clear air
 * between two lines. The floor only exists so that 行距 = 紧 cannot produce
 * colliding text; with the presets at 1.53–1.60 (see the class KDoc), 1.40 is what
 * lets 紧 actually do something at **every** preset instead of being dropped.
 *
 * 1.85 is where a single body column starts to come apart: at 1.85 the gap between
 * two CJK lines is nearly a full character, and a 16 sp body starts to read as
 * 1.5-line-spaced rather than as prose.
 *
 * The step is all-or-nothing: a 紧/松 that would leave the band is **dropped**, and
 * the preset's own leading stands — see `PiTypographyProfile`'s KDoc for the four
 * presets' answers. 经典 is unaffected either way (its factor is forced to 1.0).
 */
private const val MIN_LEADING_RATIO = 1.40
private const val MAX_LEADING_RATIO = 1.85

/**
 * [lineHeightSp] in [fontSizeSp] stepped by [factor], or **unchanged** when the
 * result would leave [MIN_LEADING_RATIO]…[MAX_LEADING_RATIO]; [factor] 1.0 is
 * identity.
 *
 * The band is checked **twice**: once on the ideal (`lineHeight × factor`) and once
 * on the value that is actually returned after `roundToInt`. The second check is
 * not paranoia — it is a defect this file had: the rounded ratio can fall *out* of
 * the band the ideal one sat in. At 13/20 and `factor = 0.92` the ideal ratio is
 * 1.4154 (inside), the rounded leading is `round(18.4) = 18`, and 18/13 = **1.385**
 * — below the CJK floor, i.e. exactly what the band exists to prevent. A step whose
 * rounded result leaves the band is therefore dropped, like any other out-of-band
 * step, and the base leading stands.
 */
private fun steppedLeading(lineHeightSp: Float, fontSizeSp: Float, factor: Double): Float {
    if (factor == 1.0 || fontSizeSp <= 0f) return lineHeightSp
    val ratio = lineHeightSp * factor / fontSizeSp
    if (ratio < MIN_LEADING_RATIO || ratio > MAX_LEADING_RATIO) return lineHeightSp
    val stepped = (lineHeightSp * factor).roundToInt().toFloat()
    val steppedRatio = stepped / fontSizeSp
    if (steppedRatio < MIN_LEADING_RATIO || steppedRatio > MAX_LEADING_RATIO) return lineHeightSp
    return stepped
}

/**
 * Every role's leading stepped by [factor], sizes untouched — the 行距 row.
 *
 * `factor == 1.0` is identity (every `normal` profile, and every classic one), so
 * a normal preset allocates nothing here and classic is byte-for-byte its base.
 */
private fun PiTextStyles.leadingScaled(factor: Double): PiTextStyles {
    if (factor == 1.0) return this
    fun TextStyle.lead(): TextStyle =
        copy(lineHeight = steppedLeading(lineHeight.value, fontSize.value, factor).sp)
    return PiTextStyles(
        meta = meta.lead(),
        mono = mono.lead(),
        monoSmall = monoSmall.lead(),
        prose = prose.lead(),
        code = code.lead(),
    )
}
