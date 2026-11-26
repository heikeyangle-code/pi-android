@file:OptIn(ExperimentalTextApi::class)

package app.pi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
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


    // ------------------------------------------------------- the v2 page scale
    //
    // These lived in `theme/PiLayout.kt` as a second object until the final audit
    // folded them in here and deleted that file: two objects declaring the same
    // page numbers is two answers to "how wide is the margin". Every value is
    // `06 §2` 的原文，一行一个出处：
    //
    //   pageHorizontal     「屏水平 14px」
    //   cardPadding        「卡片内 12px」
    //   scrollBottom       「滚动区底部留 14–18px」取上端
    //   blockGap           「块间距 8」
    //   topBarHeight       「顶栏：高 48」
    //
    // 这里只剩**真的有人在用**的值：`cardPaddingLoose` / `groupHeaderGap` / `groupGap` /
    // `bottomBarHeight` 都曾是第二批从 `PiSettingsMetrics` 抄过来的同一组数，两个对象
    // 各说一遍；现在一律走 `PiSettingsMetrics`，底栏本身也已经拆掉。

    /** `06 §2`「屏水平 14px」: the page margin every v2 screen uses. */
    val pageHorizontal = 14.dp

    /** `06 §2`「卡片内 12px」: the interior padding of a default card. */
    val cardPadding = 12.dp

    /** `06 §2`「滚动区底部留 14–18px」. */
    val scrollBottom = 18.dp

    /** `06 §2`: the transcript's block rhythm (`块间距 8`). */
    val blockGap = 8.dp

    /** 统一顶栏高度：56dp（所有屏含对话、文件查看器、设置、会话共用同一高度）。 */
    val topBarHeight = 56.dp

    // ------------------------------------------------------ 悬浮输入框 (composer)
    //
    // 用户 2026-10-26 的裁决：输入框悬浮、圆角 26、左右 12、底 8；工具行 30dp
    // 圆形热区 / 20dp 字形（放得下流式时才出现的两枚送达 chip）；发送 40dp；
    // 上下文环 20dp / 2dp 描边、30dp 热区；模型 chip 28 高、72–140 宽、纯文字无底色。
    // 数字集中在这里，屏幕侧只引用 token —— 也让
    // `tools/check-ui-consistency.sh` 的裸 dp 棘轮不必为这一次设计上调。
    //
    // 没有投影 token：6dp 的 elevation 投影会落在输入框下面那行卡片上，浅色主题里
    // 就是输入框与导航条之间的一条灰带（用户当天晚些时候要求去掉）；悬浮感由
    // `surfaceContainerLow` 的卡片底色和 1.5 / 2dp 描边承担。

    /** 悬浮输入框的圆角。 */
    val composerRadius = 22.dp

    /** 输入框与屏幕底边（导航条 / 键盘之上）的距离。 */
    val composerBottomGap = 8.dp

    /** 悬浮输入框与它上方那些卡共用的左右边距。 */
    val composerPageMargin = 12.dp

    /** 工具行里的模型 chip：高 28、宽 72–140（上限还会被剩余宽度压小）。 */
    val composerModelChipHeight = 28.dp
    val composerModelChipMinWidth = 72.dp
    val composerModelChipMaxWidth = 140.dp

    /** 环 ↔ 模型 chip ↔ 发送之间的间距。 */
    val composerChipGap = 4.dp
    val composerChipSendGap = 8.dp

    // ------------------------------------------------------ 通用顶栏图标 / 状态点（文件查看器等共用）

    val chatTopBarAction = 44.dp
    val chatTopBarGlyph = 22.dp
    val chatTopBarStatusDot = 7.dp

    /** 转录回到底部按钮：视觉圆 36，点击框 48。 */
    val scrollArrowVisual = 36.dp
    val scrollArrowTap = 48.dp

    /** 顶部悬浮胶囊与底部悬浮工作台共用的渐隐羽化带长度（上下镜像对称）。 */
    val composerGradientFade = 18.dp

    /** 工具行圆形热区；30 统一 `/`、回形针、⋮、引导 / 后续和环。 */
    val composerToolSize = 30.dp

    /** 工具行（`/`、回形针、⋮）的字形尺寸。 */
    val composerToolIcon = 20.dp

    /** 发送 / 停止圆钮。 */
    val composerSendSize = 40.dp
    val composerSendIcon = 18.dp

    /** 上下文环的直径与笔宽；热区略大一圈，视觉仍是 20dp 的环。 */
    val composerRingDiameter = 20.dp
    val composerRingStroke = 2.dp
    val composerRingTouch = 30.dp

    /** 思考等级的文字按钮：只留 13sp 名字，高度 28。 */
    val composerThinkingHeight = 28.dp

    /** 外圈描边：平时 / 聚焦（宽度，颜色仍来自 thinking 等级）。 */
    val composerBorderIdle = 1.5.dp
    val composerBorderFocused = 2.dp

    // ------------------------------------------------- the block-interior scale
    //
    // F29: the blocks under `ui/blocks/**` carried 60
    // raw `dp` literals, so two rows that should read as the same component
    // differed by 2dp for no stated reason. These are *the app's own* names for
    // the values those literals already had — no number changes, no spec row is
    // claimed for them (§2.2 and §2.3 define the type and radius scales, not an
    // interior spacing scale). A replacement is therefore a pure rename; a
    // literal that does *not* match one of these is a real deviation and must be
    // reported rather than rounded into a token.

    /** 1dp: hairlines (`HorizontalDivider`, a 1dp border stroke). */
    val hairline = 1.dp

    /**
     * 0.5dp: 卡内行与行之间的那条线（设计稿 §6「0.5dp、`outlineVariant`、40% 透明」）。
     *
     * 它比 [hairline] 细一半，因为在**卡片内部**：1dp 的不透明线在一块只比页面亮 6% 的
     * 卡上，深色主题里会读成一条压过卡片的黑线。1dp 留给页面级的分栏（顶栏、snackbar
     * 之外的整屏分隔）。
     */
    val hairlineThin = 0.5.dp

    /** 2dp: the tightest gap — a label to its glyph, a caption to its body. */
    val tiny = 2.dp

    /** 命令/参数面板列表的最大高度。 */
    val paletteMaxHeight = 260.dp

    /** 3dp: the state stripe's width in the block chrome. */
    val stripe = 3.dp

    /**
     * 32dp：列表行左侧那颗**图标底**（工作区页的文件行、资源行）。
     *
     * 图标本身仍是 16/18 那一档（[PiSettingsMetrics.searchIconSize] / `cardIconSize`），
     * 外面这层圆角方块负责把「图标列」对齐出来 —— 一行里图标大小不一，比图标本身长什么
     * 样更显乱。
     */
    val glyphBox = 32.dp

    /** 34dp：一个紧凑控件的高度（工作区芯片、分段之外的胶囊）。 */
    val controlHeight = 34.dp

    /** 16dp：紧凑控件里的小图标与箭头。 */
    val controlGlyph = 16.dp

    /** 4dp: icon-to-text inside a chip, an error's inline spacer. */
    val small = 4.dp

    /** 6dp: the interior gap of a card (`BlockChrome`'s `Arrangement`). */
    val gutter = 6.dp

    /** 8dp: inline gap between two chips, or a label after a glyph. */
    val inline = 8.dp

    /** 10dp: the accent stripe to its first line of text. */
    val inner = 10.dp

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
     * F28: the error card passed 36dp for the same
     * element and the thinking block passes its own 32dp (spec §7.4: 「收起：一行
     * 32dp」). The 36dp matched nothing (pi draws no stripe at all, so there is no
     * upstream value to copy) and was therefore unified onto this token.
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

    /** 行内小标签（「已命名」「来自 X」）：设计稿 §6 的 6dp。 */
    val tag = RoundedCornerShape(6.dp)
}

/**
 * OpenType feature presets shared across the Studio typography system:
 *  - [TABULAR]: Tabular lining figures so live counters, timestamps, and tables never jitter horizontally.
 *  - [CODE_LIGATURES_ON]: Coding ligatures (`=>`, `!=`, `->`, `<=`) + slashed zero for code fences and viewers.
 *  - [CODE_LIGATURES_OFF]: Literal character fidelity + slashed zero + tabular numerals for diffs, paths, commands, and inline code.
 */
object PiFontFeatures {
    const val TABULAR: String = "tnum"
    const val CODE_LIGATURES_ON: String = "calt, liga, zero, tnum"
    /** Literal machine code (diffs, paths, commands, line numbers, token counters): tabular + slashed zero, no ligatures. */
    const val CODE_LIGATURES_OFF: String = "calt 0, liga 0, zero, tnum"
}

/**
 * Primary whole-app CJK + Latin sans: IBM Plex Sans SC 1.1.0 (OFL-1.1), 4 complete static weights
 * (`Regular 400`, `Medium 500`, `SemiBold 600`, `Bold 700`) subsetted to GB2312 + everyday hanzi +
 * full ASCII/Latin/punctuation via `tools/build-cjk-fonts.py` (OFL §3 renamed to "Pi Studio Sans").
 */
val PiPlexSansFamily: FontFamily = FontFamily(
    Font(R.font.plex_sans_sc_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.plex_sans_sc_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.plex_sans_sc_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.plex_sans_sc_bold, FontWeight.Bold, FontStyle.Normal),
)

/**
 * Humanist whole-app CJK + Latin kai face: LXGW WenKai 1.530 (OFL-1.1), 2 real static weights
 * (`Regular 400`, `Medium 500` mapped to SemiBold/Bold) subsetted via `tools/build-cjk-fonts.py`
 * (OFL §3 renamed to "Pi Studio Kai").
 */
val PiWenKaiFamily: FontFamily = FontFamily(
    Font(R.font.lxgw_wenkai_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.lxgw_wenkai_regular, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.lxgw_wenkai_medium, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.lxgw_wenkai_medium, FontWeight.Bold, FontStyle.Normal),
)

/**
 * Primary machine-language face: JetBrains Mono v2.304 (OFL-1.1), 6 complete static cuts
 * (`Regular 400`, `Medium 500`, `SemiBold 600`, `Bold 700`, `Italic 400i`, `BoldItalic 700i`).
 * Zero faux-bold or faux-italic synthesis across code fences, comments, `<kbd>`, diffs, and headers.
 */
val PiJetBrainsMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/**
 * Geometric machine-language face: Vercel Geist Mono v1.9.2 (OFL-1.1), 6 complete static cuts
 * (`Regular 400`, `Medium 500`, `SemiBold 600`, `Bold 700`, `Italic 400i`, `BoldItalic 700i`).
 */
val PiGeistMonoFamily: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.geist_mono_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.geist_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.geist_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/** Default static monospace alias; runtime code widgets should prefer `PiTheme.typography.codeFontFamily()`. */
val PiMonoFamily: FontFamily = PiJetBrainsMonoFamily

/**
 * Editorial whole-app CJK + Latin serif: Source Han Serif CN 2.003R (OFL-1.1), 2 real static
 * weights (`wght 450` Text mapped to Normal/Medium, `wght 700` Bold mapped to SemiBold/Bold)
 * built via `tools/build-prose-font.py` (OFL §3 renamed to "Pi Reading Serif").
 */
val PiProseFamily: FontFamily = FontFamily(
    Font(R.font.source_han_serif_cn_text, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.source_han_serif_cn_text, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.source_han_serif_cn_bold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.source_han_serif_cn_bold, FontWeight.Bold, FontStyle.Normal),
)

/**
 * The text metrics every role in this app is built on: **no font padding, leading
 * centred in the line box, first-line-top / last-line-bottom trimmed**.
 *
 * ## Why this is not the default, and why it is written down here
 *
 * Compose's line box is not the glyph box. `PlatformTextStyle.includeFontPadding`
 * (true by default on Android for a long time) adds the font's own ascent/descent
 * padding *on top of* the line height, and `LineHeightStyle` decides how the
 * leftover leading is distributed and whether the first/last line keeps it. Left
 * at the platform default, three things happen to this app's text:
 *
 *  * **A single Chinese line sits low in its box.** The extra space is split by
 *    the font's ascent:descent ratio, and a CJK face's ascent is much the larger
 *    half, so most of the slack lands *above* the glyphs. In a message bubble with
 *    symmetric 12 dp padding the text therefore reads as off-centre — the same
 *    family of symptom this repo already paid for once (`d0c70df`, where the last
 *    1.1 dp of the "bubble text sits low" report was exactly this ratio).
 *  * **The gaps this app sets are not the gaps the user sees.** With untrimmed
 *    first/last lines, a paragraph carries half its leading above and half below,
 *    so a `block` of 12 dp paints 12 + slack, and two adjacent blocks look
 *    further apart than the number says. `Trim.Both` makes the paragraph gap mean
 *    what it says.
 *  * **It can change under the app's feet.** `LineHeightStyle.Default`'s own value
 *    has moved between Compose releases (androidx-main is
 *    `Alignment.Proportional` + `Trim.Both`; the 1.11.x notes record the default
 *    constructor being reworked), so "what the platform picks" is not a stable
 *    contract for a layout that is tuned to the dp.
 *
 * `Center` (not `Proportional`) is right for CJK: a Chinese glyph fills ~0.87 em
 * of its box with no ascender/descender variation, so the leading is meant to be
 * split evenly above and below the glyph rather than by Latin font metrics.
 * `Trim.Both` then removes the halves that hang outside the first and last line.
 *
 * `@file:OptIn(ExperimentalTextApi::class)` at the top of this file covers the
 * API-level question: if a Compose bump makes one of these types experimental
 * again, the annotation is already there (an unnecessary opt-in is a warning, not
 * an error); if it renames one, the build fails loudly here rather than silently
 * reverting the app to the platform default.
 */
internal val piLineHeightStyle: LineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/**
 * [TextStyle] with [piLineHeightStyle] and `includeFontPadding = false` applied.
 *
 * Every role in this app goes through this: [PiTextStyles]'s five slots,
 * `piTypography`'s M3 slots, and the markdown profile's own styles. `copy` (which
 * `shifted` / `leadingScaled` / `PiMarkdownTheme`'s `base.copy(...)` all use)
 * preserves both fields, so a scaled or recoloured role keeps them.
 */
internal fun TextStyle.piPrecise(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = piLineHeightStyle,
)

/**
 * Text styles that Material 3's [Typography] has no slot for: the transcript's
 * prose role, pi's footer/meta line and the machine roles. Everything a *human*
 * wrote uses the system font — except the transcript's Markdown body, whose
 * family the renderer overrides with the bundled serif at the consumption site
 * (`render/PiMarkdownTheme.kt`) — and everything a *machine* emitted uses
 * monospace; that split is the app's rule #7.
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
    /** Shift every text role by [deltaSp] while preserving font families and OpenType features. */
    fun scaled(deltaSp: Int): PiTextStyles = if (deltaSp == 0) this else PiTextStyles(
        meta = meta.shifted(deltaSp),
        mono = mono.shifted(deltaSp),
        monoSmall = monoSmall.shifted(deltaSp),
        prose = prose.shifted(deltaSp),
        code = code.shifted(deltaSp),
    )

    companion object {
        /** Baseline text roles matching [PiTypographyProfile.Default] (16sp prose, comfortable density). */
        val Default = PiTextStyles(
            meta = TextStyle(
                fontFamily = PiPlexSansFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
            ).piPrecise(),
            mono = TextStyle(
                fontFamily = PiJetBrainsMonoFamily,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_OFF,
            ).piPrecise(),
            monoSmall = TextStyle(
                fontFamily = PiJetBrainsMonoFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_OFF,
            ).piPrecise(),
            prose = TextStyle(
                fontFamily = PiPlexSansFamily,
                fontSize = 16.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Normal,
            ).piPrecise(),
            code = TextStyle(
                fontFamily = PiJetBrainsMonoFamily,
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_ON,
            ).piPrecise(),
        )
    }
}

/**
 * Numbers in the status row must not jitter as they tick; always uses the active monospace
 * family with tabular numerals, slashed zero, and literal characters (no ligatures).
 */
val PiTextStyles.numeric: TextStyle
    get() = mono.copy(fontFeatureSettings = PiFontFeatures.CODE_LIGATURES_OFF)

/** Shift a role's size and its leading together, never below 1sp. */
private fun TextStyle.shifted(deltaSp: Int): TextStyle = copy(
    fontSize = (fontSize.value + deltaSp).coerceAtLeast(1f).sp,
    lineHeight = (lineHeight.value + deltaSp).coerceAtLeast(1f).sp,
)

val LocalPiPalette = staticCompositionLocalOf { PiPalette.Dark }
val LocalPiTextStyles = staticCompositionLocalOf { PiTypographyProfile.Default.styles }

/**
 * The resolved 排版 preset, for the parts of the render path that need more than
 * [PiTextStyles]: the markdown rhythm and heading scale (`render/PiMarkdown.kt`,
 * `render/PiMarkdownTheme.kt`), the code block's corner radius
 * (`render/PiMarkdownComponents.kt`) and the transcript's block gap
 * (`screens/ChatScreen.kt` reads it through `UiPrefs` instead).
 *
 * A composition local rather than another `PiTheme` parameter read at each call
 * site, because those call sites are seven blocks deep in the transcript and are
 * reached through the renderer's own dispatch — the same reason
 * `LocalPiTextStyles` is a local. Its default is the unthemed one, matching
 * [LocalPiTextStyles]'s.
 */
val LocalPiTypographyProfile = staticCompositionLocalOf { PiTypographyProfile.Default }

private val PiPalette.textSelectionColors: TextSelectionColors
    get() = TextSelectionColors(handleColor = accent, backgroundColor = selectedBg)

/** Access the raw pi token set: `PiTheme.palette.toolSuccessBg`. */
object PiTheme {
    val palette: PiPalette
        @Composable @ReadOnlyComposable get() = LocalPiPalette.current

    val text: PiTextStyles
        @Composable @ReadOnlyComposable get() = LocalPiTextStyles.current

    /** The resolved 排版 preset behind [text]: gaps, headings and radii. */
    val typography: PiTypographyProfile
        @Composable @ReadOnlyComposable get() = LocalPiTypographyProfile.current
    /** True when the active theme palette is dark (`pageBg` luminance < 0.5). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = PiContrast.luminance(palette.pageBg) < 0.5
}

/** Blend helper for the surface ladder below. */
private fun Color.elevate(toward: Color, amount: Float): Color = lerp(this, toward, amount)

/**
 * WCAG-aware foreground ink on any solid theme token background (`accent`, `error`, `bashMode`, etc.).
 * Guarantees 100% readability across every official Pi JSON theme, including pastel dark/light themes.
 */
internal fun PiPalette.contrastOn(background: Color): Color =
    if (PiContrast.luminance(background) < 0.34) Color.White else Color(0xFF111318)

private fun PiPalette.colorScheme(): androidx.compose.material3.ColorScheme {
    val isDark = PiContrast.luminance(pageBg) < 0.5
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    val onAccent = contrastOn(accent)
    val onSecondary = contrastOn(borderAccent)
    val onTertiary = contrastOn(customMessageLabel)
    val onErr = contrastOn(error)
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = selectedBg,
        onPrimaryContainer = text,
        inversePrimary = accent,

        secondary = borderAccent,
        onSecondary = onSecondary,
        secondaryContainer = cardBg,
        onSecondaryContainer = text,

        tertiary = customMessageLabel,
        onTertiary = onTertiary,
        tertiaryContainer = customMessageBg,
        onTertiaryContainer = customMessageText,

        background = pageBg,
        onBackground = text,
        surface = pageBg,
        onSurface = text,
        surfaceVariant = cardBg,
        onSurfaceVariant = muted,
        // Disable M3 tonal tint wash so elevated surfaces stay pure neutral grayscale.
        surfaceTint = Color.Transparent,

        // Neutral surfaceContainer ladder:
        //  - Dark: step lighter from pageBg -> cardBg -> container -> high -> highest.
        //  - Light: keep floating sheets/dialogs crisp cardBg/white while recessed tracks/pills
        //    use cool neutral tint, preventing muddy gray sheets over white cards.
        surfaceContainerLowest = if (isDark) pageBg else Color.White,
        surfaceContainerLow = cardBg,
        surfaceContainer = if (isDark) cardBg.elevate(text, 0.035f) else pageBg.elevate(text, 0.025f),
        surfaceContainerHigh = if (isDark) cardBg.elevate(text, 0.068f) else cardBg,
        surfaceContainerHighest = if (isDark) cardBg.elevate(text, 0.11f) else pageBg.elevate(text, 0.055f),
        surfaceBright = if (isDark) cardBg.elevate(text, 0.14f) else Color.White,
        surfaceDim = if (isDark) pageBg.elevate(Color.Black, 0.16f) else pageBg.elevate(text, 0.045f),

        outline = borderMuted,
        outlineVariant = borderMuted.copy(alpha = if (isDark) 0.65f else 0.72f),

        error = error,
        onError = onErr,
        errorContainer = toolErrorBg,
        onErrorContainer = text,

        scrim = Color.Black,
        inverseSurface = text,
        inverseOnSurface = pageBg,
    )
}

/**
 * All 15 M3 Typography slots aligned to Pi's active Studio UI font family, OpenType features,
 * balanced heading/paragraph line breaks, precise font metrics, and `textScaleDelta` (−1/0/+1/+2 sp).
 */
private fun piTypography(
    textScaleDelta: Int = 0,
    uiFontFamily: FontFamily = PiPlexSansFamily,
    uiFontFeatures: String? = null,
): Typography {
    fun TextStyle.shift(lineBreak: LineBreak = LineBreak.Simple): TextStyle = copy(
        fontFamily = uiFontFamily,
        fontSize = (fontSize.value + textScaleDelta).coerceAtLeast(1f).sp,
        lineHeight = (lineHeight.value + textScaleDelta).coerceAtLeast(1f).sp,
        fontFeatureSettings = uiFontFeatures,
        lineBreak = lineBreak,
    ).piPrecise()
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(
            fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        displayMedium = base.displayMedium.copy(
            fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        displaySmall = base.displaySmall.copy(
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
        ).shift(LineBreak.Heading),
        headlineLarge = base.headlineLarge.copy(
            fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        headlineMedium = base.headlineMedium.copy(
            fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        titleLarge = base.titleLarge.copy(
            fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        titleMedium = base.titleMedium.copy(
            fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        titleSmall = base.titleSmall.copy(
            fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        ).shift(LineBreak.Heading),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal,
        ).shift(LineBreak.Paragraph),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
        ).shift(LineBreak.Paragraph),
        bodySmall = base.bodySmall.copy(
            fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
        ).shift(LineBreak.Paragraph),
        labelLarge = base.labelLarge.copy(
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        ).shift(),
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        ).shift(),
        labelSmall = base.labelSmall.copy(
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
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
    typographyProfile: PiTypographyProfile = PiTypographyProfile.Default,
    content: @Composable () -> Unit,
) {
    val styles = typographyProfile.styles
    val colorScheme = androidx.compose.runtime.remember(palette) { palette.colorScheme() }
    val uiFontFamily = typographyProfile.uiFontFamily()
    val uiFontFeatures = typographyProfile.uiFontFeatures()
    val typography = androidx.compose.runtime.remember(
        typographyProfile.fontSizeOffsetSp,
        uiFontFamily,
        uiFontFeatures,
    ) {
        piTypography(
            textScaleDelta = typographyProfile.fontSizeOffsetSp,
            uiFontFamily = uiFontFamily,
            uiFontFeatures = uiFontFeatures,
        )
    }
    val shapes = androidx.compose.runtime.remember { piShapes() }
    CompositionLocalProvider(
        LocalPiPalette provides palette,
        LocalPiTextStyles provides styles,
        LocalPiTypographyProfile provides typographyProfile,
        LocalTextSelectionColors provides palette.textSelectionColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
        ) {
            androidx.compose.material3.ProvideTextStyle(
                value = typography.bodyLarge,
                content = content,
            )
        }
    }
}
