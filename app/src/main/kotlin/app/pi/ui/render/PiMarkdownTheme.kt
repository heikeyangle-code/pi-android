package app.pi.ui.render

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.ui.theme.PiHeading
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTypographyProfile
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownAlertColors
import com.mikepenz.markdown.model.MarkdownAlertDimens
import com.mikepenz.markdown.model.MarkdownAlertPadding
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors
import com.mikepenz.markdown.model.markdownAlertDimens
import com.mikepenz.markdown.model.markdownAlertPadding

/**
 * The five objects handed to `Markdown(...)`, built as **pure functions of the
 * pi palette** instead of through the library's own builders.
 *
 * Why not the builders: in `com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0`
 * four of the five are composable functions, because their defaults read
 * `MaterialTheme` — `multiplatform-markdown-renderer-m3/.../m3/MarkdownColors.kt`
 * declares `@Composable fun markdownColor(...)`, its sibling
 * `MarkdownTypography.kt` the same, and in the core artifact
 * `.../model/MarkdownPadding.kt` and `.../model/MarkdownDimens.kt` are
 * `@Composable fun markdownPadding(...)` / `markdownDimens(...)`. A composable
 * function cannot be called from `remember`'s calculation lambda — that lambda
 * is `@DisallowComposableCalls` — so `remember { piMarkdownColors() }` is
 * rejected by the Compose compiler with "Composable invocations can only happen
 * from the context of a @Composable function". That is exactly how the previous
 * attempt at this patch (recorded in `docs/gap-disposition.md` section 10.1)
 * broke the build.
 *
 * The fix is to keep the *reads* composable and the *construction* pure:
 * `PiMarkdown.kt` reads `PiTheme.palette`, `isSystemInDarkTheme()` and the
 * resolved 排版 profile (`PiTheme.typography`) at the composition point and
 * passes them here, so every function in this file is an ordinary function and
 * `remember(inputs) { ... }` is legal.
 *
 * Two of the five then need a detail of their own, because the library keeps the
 * implementation it returns private:
 *
 * * `markdownColor` returns the public `DefaultMarkdownColors` data class
 *   (`.../model/MarkdownColors.kt`), so [piMarkdownColors] constructs it
 *   directly. Its `alert` slot comes from `markdownAlertColors`, a plain
 *   (non-composable) function in the same artifact
 *   (`.../model/MarkdownAlertColors.kt`), so [piAlertColors] is pure too.
 * * `markdownTypography` returns the public `DefaultMarkdownTypography` data
 *   class (`.../model/MarkdownTypography.kt`), so [piMarkdownTypography]
 *   constructs it directly.
 * * `markdownPadding` / `markdownDimens` return **private** data classes
 *   (`private data class DefaultMarkdownPadding`, `.../model/MarkdownPadding.kt`;
 *   `private data class DefaultMarkdownDimens`, `.../model/MarkdownDimens.kt`),
 *   so they cannot be constructed from here. Their interfaces are public and
 *   carry no behaviour, so this file implements them ([PiMarkdownPadding],
 *   [PiMarkdownDimens]) with the same field values the builders were being
 *   called with. [piMarkdownPadding] is a function of the 排版 preset and
 *   `PiMarkdown.kt` caches its result with `remember(profile)`: one instance per
 *   preset, not one per frame. [piMarkdownDimens] depends on nothing — the preset
 *   does **not** move any radius — so it is still a top-level `val`, allocated
 *   once per process. The library defaults the app does not override
 *   (`blockQuoteBar`, the alert paddings/dimens, `tableCellWidth`,
 *   `tableCornerSize`) are transcribed from the 0.45.0
 *   sources named in each KDoc below — if that dependency is ever bumped, those
 *   numbers have to be re-checked against the new artifact: an interface change
 *   fails the build loudly, a changed default would not.
 *
 * The fifth object, `MarkdownComponents`, is not built here: its builder
 * `markdownComponents(...)` is **not** composable (see `PiMarkdownComponents.kt`),
 * so it is cached with `remember` as it stands.
 */

/**
 * pi's markdown tokens, mapped onto the renderer's colour slots.
 *
 * pi draws markdown in a terminal, so its `MarkdownTheme` has exactly ten colour
 * hooks (`packages/coding-agent/src/modes/interactive/theme/theme.ts`,
 * `getMarkdownTheme`). The renderer here has five colour slots plus typography,
 * so the mapping is not one-to-one, and the places where it cannot be are called
 * out rather than papered over:
 *
 * | pi token            | goes to                                              |
 * |---------------------|------------------------------------------------------|
 * | `mdHeading`         | colour of every heading style                        |
 * | `mdLink`            | `TextLinkStyles.style`                               |
 * | `mdLinkUrl`         | not painted — the app is in pi's *hyperlink-capable* branch, where pi prints only the link text too (see below) |
 * | `mdCode`            | `inlineCode` style colour                            |
 * | `mdCodeBlock`       | body colour of a fence **highlight.js does not know** — pi's uncoloured branch, never a coloured block's base |
 * | `mdCodeBlockBorder` | the code block's border stroke and its language label |
 * | `mdQuote`           | `quote` style colour (and the quote bar — see below)  |
 * | `mdQuoteBorder`     | the quote bar, which shares the quote colour          |
 * | `mdHr`              | `dividerColor`                                       |
 * | `mdListBullet`      | `bullet` style colour                                |
 *
 * The quote bar is the one genuine compromise: the renderer's block quote paints
 * its bar and its text from a single colour, where pi has two tokens. Both of
 * pi's built-in themes set `mdQuote` and `mdQuoteBorder` to the same value, so
 * for the shipped themes the output is identical; a hand-written pi theme that
 * chooses two different values loses the distinction.
 *
 * Three more deserve their reasoning written down, because each is a place where
 * the phone must *not* add something pi does not have.
 *
 * **`mdLinkUrl` is not painted, and that is pi's behaviour here.** pi has one
 * branch for it: when the terminal can carry a hyperlink, the URL is *not*
 * printed (the link text carries an OSC 8 sequence instead), and only a
 * hyperlink-incapable consumer gets `text + linkUrl(" (href)")`
 * (`packages/tui/src/components/markdown.ts:689-709`). The library's default
 * annotator wires every link to `LocalUriHandler.openUri`
 * (`multiplatform-markdown-renderer` 0.45.0, `annotator/AnnotatorSettingsKt`), so
 * this app *is* a hyperlink-capable consumer: a tapped link opens. Printing the
 * URL as well would be a second, invented branch. (An earlier revision of this
 * comment promised the URL "still resolvable for the link sheet" — no such sheet
 * exists, and none is needed.)
 *
 * **A code block has no background.** pi's terminal draws a fence as its three
 * backticks plus the code, on the transcript's own surface
 * (`packages/tui/src/components/markdown.ts:520-540`), and its HTML export says
 * the same in CSS — `.markdown-content pre { background: transparent }` and
 * `.markdown-content pre code { background: none }`
 * (`core/export-html/template.css:900-909`). This app used to paint `cardBg`
 * behind a fence and `infoBg` behind inline code; `infoBg` is pi's *export info
 * panel* colour (the `export` section of `theme-schema.json`) and was never a code
 * colour. Both are now transparent, so no pi token is used for a surface pi has
 * no concept of.
 *
 * **A coloured block's base colour is `text`, not `mdCodeBlock`.** When
 * highlight.js knows the language, pi colours exactly what highlight.js wrapped
 * and leaves every other character at the terminal's default foreground — no
 * `theme.fg` is applied to it at all (`theme.ts:1186-1205`) — and the export says
 * it twice: `.hljs { color: var(--text) }` and `.markdown-content pre code {
 * color: var(--text) }` (`template.css:959`, `:906-909`). `mdCodeBlock` is
 * reserved for pi's *uncoloured* branch — a fence with no language, or one
 * highlight.js does not know (`theme.ts:1085`, `:1193`) — so using it as the base
 * of a coloured block would paint every un-tokenised character in the theme's
 * code-block colour (green, in pi's built-in dark theme).
 * `render/PiMarkdownComponents.kt` makes that choice and `render/PiCodeHighlight.kt`
 * carries the engine's answer (`PiCodeHighlight.languageKnown`) that decides it.
 *
 * GFM alerts (`> [!NOTE]`, a 0.45.0 feature pi's terminal does not have) are
 * mapped onto pi's own semantic tokens rather than left to Material 3, so no
 * alert accent can drift with the device wallpaper — see [piAlertColors].
 *
 * This is the body of the library's `markdownColor` (0.45.0,
 * `multiplatform-markdown-renderer-m3/.../m3/MarkdownColors.kt`), with the
 * composable part — the `MaterialTheme` defaults — moved to the call site.
 *
 * @param palette pi's resolved token set, read from `PiTheme.palette`.
 * @param darkTheme the renderer's own light/dark flag, read once per
 *   composition by the caller.
 * @param textColor pi's base foreground for one specific surface, or `null` for
 *   `palette.text`. pi's markdown takes such an override as a *base* colour
 *   (`Markdown`'s `defaultTextStyle.color`, `packages/tui/src/components/markdown.ts:385`,
 *   applied by `applyDefaultStyle` at `:377-403`), which the token colours
 *   (headings, links, code) are drawn on top of — so the skill card, whose
 *   renderer passes `theme.fg("customMessageText", …)`
 *   (`components/skill-invocation-message.ts:43`), gets that colour for body
 *   text while its headings keep `mdHeading`. The slot matters beyond the
 *   body styles: the library also reads `MarkdownColors.text` for the code
 *   fence header label (`.../elements/MarkdownCodeTopBar.kt:35`) and as the
 *   last-resort text colour (`.../elements/material/TextWrapper.kt:41-47`), so
 *   overriding both keeps one surface one colour.
 */
internal fun piMarkdownColors(
    palette: PiPalette,
    darkTheme: Boolean,
    textColor: Color? = null,
): MarkdownColors =
    DefaultMarkdownColors(
        text = textColor ?: palette.text,
        // pi has no code background on either surface: the terminal's fence is its
        // own text, and the export is explicit about it (`pre { background:
        // transparent }`, `pre code { background: none }` — `template.css:900-909`).
        // Transparent is therefore the faithful value, not a pi token borrowed for
        // a surface pi does not have.
        codeBackground = Color.Transparent,
        inlineCodeBackground = Color.Transparent,
        dividerColor = palette.mdHr,
        tableBackground = palette.cardBg,
        // One value for both slots: the renderer uses this flag for its own
        // derived container/on-container pairs, and the alert accents below
        // override every accent this app draws.
        alert = piAlertColors(palette, darkTheme),
    )

/**
 * GFM alert accents, taken from pi's semantic tokens instead of the library's
 * Material 3 defaults.
 *
 * The library defaults to `MarkdownAlertColorDefaults`, which is GitHub's own
 * blue/green/purple/amber/red palette (`.../model/MarkdownAlertColors.kt`);
 * this app derives its scheme from dynamic colour on Android 12+, so an alert
 * bar would be the only markdown element whose colour is not pi's. `PiPalette`'s
 * own doc says exactly why that is not acceptable: a state colour that drifts
 * with the wallpaper is a state colour you cannot trust.
 *
 * pi has no GFM alerts (its terminal cannot render them), so there is no token
 * to be faithful *to*. Each accent is therefore the closest pi token by meaning:
 *
 * | GitHub alert | pi token      | why |
 * |--------------|---------------|-----|
 * | `NOTE`       | `mdQuote`     | the calm informational emphasis |
 * | `TIP`        | `success`     | positive, actionable |
 * | `IMPORTANT`  | `mdHeading`   | the most prominent neutral token |
 * | `WARNING`    | `warning`     | the token's literal name |
 * | `CAUTION`    | `error`       | the token's literal name |
 *
 * `markdownAlertColors` itself is not composable — only the Material 3
 * `markdownColor` default that calls it is — so this stays pure and cacheable.
 */
internal fun piAlertColors(palette: PiPalette, darkTheme: Boolean): MarkdownAlertColors =
    markdownAlertColors(
        darkTheme = darkTheme,
        note = palette.mdQuote,
        tip = palette.success,
        important = palette.mdHeading,
        warning = palette.warning,
        caution = palette.error,
    )

/**
 * pi's markdown type hierarchy, expressed in pi's colours.
 *
 * A terminal has one font and no size scale, so pi's headings differ only by
 * weight and underline. The app keeps the *colour* faithful and adds the size
 * scale every Compose surface already has — a heading in a phone-sized column
 * that is the same size as body text is unreadable, and pi's own HTML export
 * makes the same trade for the same reason.
 *
 * Body of the library's `markdownTypography` (0.45.0,
 * `multiplatform-markdown-renderer-m3/.../m3/MarkdownTypography.kt`), with its
 * two Material inputs lifted to parameters.
 *
 * @param palette pi's resolved token set.
 * @param profile the resolved 排版 preset: every size and leading here is the
 *   preset's, and the **body role is the preset's [PiTypographyProfile.styles]`prose`
 *   metrics with the bundled reading face substituted** —
 *   `prose.copy(fontFamily = PiProseFamily, fontWeight = Normal)` — i.e. 思源宋体
 *   400 at 14/23 in 经典 and 16/25 in the default 舒适. `prose` itself stays the
 *   UI's system Sans (settings/boot/file/diagnostics pages share it), so only the
 *   Markdown body gets the serif. Every prose slot below — `h1`…`h6`,
 *   `alertTitle`, `text`, `paragraph`, `ordered`, `list`, `quote`, `textLink` and
 *   `table` — is derived from that one style, so the whole reading hierarchy
 *   shares one family. `mono` and `code` stay the machine roles
 *   (`PiMonoFamily`): inline code, fences, diffs, shell, commands and paths. The
 *   one exception is the `bullet` **marker glyph**: only its colour and size come
 *   from the reading style; its face is the system UI one, because Source Han
 *   Serif's U+2022 is a full-width (1 em) glyph with a 0.10 em dot (see the slot).
 *
 *   Bold is real, not synthesised: the library's annotator always emits
 *   `SpanStyle(fontWeight = FontWeight.Bold)`, and `PiProseFamily` registers its
 *   600 face under **both** `SemiBold` and `Bold`, so `**strong**` selects the
 *   real 600 file and the headings' explicit `SemiBold` lands on the same one.
 *
 *   The table's **type** is the preset's too — [PiTypographyProfile.tableSizeSp] /
 *   `tableLineHeightSp`, 12.5/18 in 经典 (which is exactly the pair this file used
 *   to hard-code) and 14/22 in 舒适 — but the table's *geometry* is not: column
 *   width (fitted to the column count, with a 120 dp floor), horizontal scroll,
 *   corner size (8 dp) and cell padding (8 dp) are all outside the preset and
 *   unchanged by this feature (`PiMarkdownComponents.kt`'s `PiTable` only adds a
 *   header tint and a right-edge fade). The colour is still pi's: headings keep
 *   `mdHeading`, links `mdLink`, inline code `mdCode`, fences `mdCodeBlock`,
 *   quotes `mdQuote` and bullets `mdListBullet`, drawn on top of the body colour
 *   exactly as they sit on top of pi's base (`markdown.ts:377-403`).
 * @param textColor the base foreground override described on [piMarkdownColors];
 *   it replaces `palette.text` in the body slots only (`text`, `paragraph`,
 *   `ordered`, `list`, `table`). Headings (`mdHeading`), links (`mdLink`), inline
 *   code (`mdCode`), fences (`mdCodeBlock`), quotes (`mdQuote`) and bullets
 *   (`mdListBullet`) keep pi's own tokens, exactly as they sit on top of the base
 *   colour in pi (`markdown.ts:377-403`).
 */
internal fun piMarkdownTypography(
    palette: PiPalette,
    profile: PiTypographyProfile,
    textColor: Color? = null,
    /**
     * 段落用等宽（而不是正文角色）。
     *
     * 只有**含显示式网格**的消息才为真：网格是靠空格对齐的，比例字体里空格与字形的宽度
     * 不同，整块会歪。做成参数而不是"造完之后 `copy`"——库的 `MarkdownTypography` 没有
     * `copy`（能造它的只有 `DefaultMarkdownTypography` 这个构造器），上一版就是因此在
     * CI 上红了 `Unresolved reference 'copy'`。
     */
    monoParagraph: Boolean = false,
): MarkdownTypography {
    // Markdown 正文字体：字号/行高/字距等一律取排版档的 `prose` 度量，只把字族换成
    // 随包的思源宋体、字重固定 400（非经典档的 `prose` 是给 UI 用的 500，不搬进来）。
    // `prose` 本身仍是系统 Sans —— 设置页/Boot/文件页/诊断页共用它，只有 Markdown
    // 正文换成宋体。`mono`/`code` 只留给机器输出（代码、命令行、路径）。
    val base = profile.styles.prose.copy(
        fontFamily = PiProseFamily,
        fontWeight = FontWeight.Normal,
    )
    val mono = profile.styles.mono
    val code = profile.styles.code
    // 标题/强调都落在真实 600：`PiProseFamily` 把 600 同时注册为 `SemiBold` 与
    // `Bold`，所以这里的 `SemiBold` 与库 annotator 给 `**strong**` 的 `Bold`
    // 取到的是同一个字体文件 —— 不是合成粗体。
    val heading = base.copy(color = palette.mdHeading, fontWeight = FontWeight.SemiBold)
    val body = textColor ?: palette.text

    return DefaultMarkdownTypography(
        h1 = heading.headingSlot(profile.h1, underline = true),
        h2 = heading.headingSlot(profile.h2),
        h3 = heading.headingSlot(profile.h3),
        h4 = heading.headingSlot(profile.h4),
        h5 = heading.headingSlot(profile.h5),
        h6 = heading.headingSlot(profile.h6),
        // GFM alert titles (`> [!NOTE]`) read as a small heading, not as body
        // text — same slot as h4, since the alert body is already inset. That is
        // what this line has always been; the preset moves both together.
        alertTitle = heading.headingSlot(profile.h4),
        text = base.copy(color = body),
        code = code.copy(color = palette.mdCodeBlock),
        inlineCode = mono.copy(
            fontSize = profile.inlineCodeSizeSp.sp,
            lineHeight = profile.inlineCodeLineHeightSp.sp,
            fontWeight = profile.inlineCodeWeight,
            color = palette.mdCode,
        ),
        // pi's markdown renderer puts **two** decorations on a blockquote, not one: the theme
        // supplies the colour (`quote: (text) => theme.fg("mdQuote", text)`,
        // `modes/interactive/theme/theme.js:936`) and the renderer's blockquote case wraps that
        // in italic —
        //   `case"blockquote":{let quoteStyle=text=>this.theme.quote(this.theme.italic(text)) …`
        // (bundled pi-tui, `@earendil-works/pi-tui/dist/components/markdown.js:417`; the bundle
        // copy is `dist/bundle/chunks/chunk-JVUZSMYM.js:584`, which is where this was verified).
        // The colour was here and the italic was not, so a quote read as body text in a slightly
        // different hue instead of as a quote.
        //
        // 审计备注（2026-10-31）：库 0.45.0 的 `MarkdownBlockQuote` 只把这份 style 用在
        // **竖条颜色**与**空行高度**上 —— 块内文字是经 `MarkdownElement` 派发到
        // `paragraph` 槽渲染的（`MarkdownBlockQuote.kt:90-96`），所以这里的 mdQuote/斜体
        // 目前落在竖条上，引文正文仍是 paragraph 的颜色与字形。这是库的实现，不是本文件
        // 的笔误；要不要把引文真正染成 mdQuote+斜体，需要另立一项（会改变引用的观感）。
        quote = base.copy(color = palette.mdQuote, fontStyle = FontStyle.Italic),
        paragraph = if (monoParagraph) mono.copy(color = body) else base.copy(color = body),
        ordered = base.copy(color = body),
        // 2026-10-26: 圆点只放大字形（1.4×），行高保持正文档，不把列表项撑高；
        // 经典档冻结，仍是正文 14。
        //
        // 2026-10-31（宋体）：圆点**只**是标记符号，标记字回到系统 UI 字。思源宋体的
        // U+2022 是 CJK 全宽字形（advance 1.000 em，墨迹只有 0.10 em），`"• "` 在
        // 1.4× 下要占 1.76 em：圆点缩成 2 dp 左右、列表正文比改前右移 ~15 dp，多一层
        // 嵌套再叠一次。标记不是正文，列表**文字**仍是宋体；换成系统 UI 字的比例宽度
        // 就能保住 10-26 定下的 1.4× 几何。（若要彻底与字体无关，下一步可以把圆点
        // 画成板上那个 4 dp 圆点，见 v2 `width:4,height:4,borderRadius:999`。）
        bullet = if (profile.isClassic) {
            base.copy(color = palette.mdListBullet, fontFamily = FontFamily.Default)
        } else {
            base.copy(
                color = palette.mdListBullet,
                fontFamily = FontFamily.Default,
                fontSize = (base.fontSize.value * 1.4f).sp,
                lineHeight = base.lineHeight,
            )
        },
        list = base.copy(color = body),
        textLink = TextLinkStyles(
            style = SpanStyle(color = palette.mdLink, textDecoration = TextDecoration.Underline),
        ),
        // 表格属于"正文阅读体系"（任务书 §7.6），不是等宽表面：阅读字族 + 预设的
        // 字号/行高 + 正文底色。两个数字从 profile **原样**取 —— 行距/字号乘子特意
        // 跳过它们（`PiTypographyProfile`），因为单元格的行高只决定格子多高，而它要
        // 挤进的列宽由 `PiTable` 决定。经典是 12.5/18，正是这行以前写死的那一对。
        // 表头由库自己在这份 style 上 `copy(fontWeight = Bold)`，经字族的 Bold 别名
        // 命中真实 600。**格子里**的行内代码仍走 `inlineCode`（mono）：只有单元格的
        // 正文换成阅读字体。
        table = base.copy(
            fontSize = profile.tableSizeSp.sp,
            lineHeight = profile.tableLineHeightSp.sp,
            color = body,
        ),
    )
}

/** One heading slot at the profile's size and leading; [underline] only for h1. */
private fun TextStyle.headingSlot(slot: PiHeading, underline: Boolean = false): TextStyle =
    copy(
        fontSize = slot.sizeSp.sp,
        lineHeight = slot.lineHeightSp.sp,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )

/**
 * pi's rhythm, as far as the renderer exposes it: block spacing stays tight so a
 * long answer does not become a column of whitespace, and the code/quote inset
 * is the same [PiSpacing.cardPadding] every other card in the app uses.
 *
 * Implements the library's public `MarkdownPadding` interface, because the
 * builder's own implementation is private and the builder itself is composable
 * (`multiplatform-markdown-renderer/.../model/MarkdownPadding.kt`). The four
 * values the 排版 preset decides — `block`, `listItemTop`, `listItemBottom` and
 * `listIndent` — come from [profile]; everything else is what the app already
 * passed to `markdownPadding(...)` at every preset:
 *
 * * `list` (the gap *between* items of one list) is not one of the preset's rows;
 *   it keeps today's 4 dp.
 * * `codeBlock` / `blockQuote` keep [PiSpacing.cardPadding] and the vertical 10/0/4.
 * * `blockQuoteBar` and `alert` keep the 0.45.0 defaults,
 *   `PaddingValues.Absolute(left = 4.dp, top = 2.dp, right = 4.dp, bottom = 2.dp)`
 *   and `markdownAlertPadding()`, which is itself a plain function and is called
 *   here with its own defaults.
 *
 * A function of [PiTypographyProfile] rather than a top-level `val` (which is what
 * this was before the preset existed): the caller caches it with
 * `remember(profile)` so it is still allocated once per preset, never per frame.
 */
@Immutable
private data class PiMarkdownPadding(
    override val block: Dp,
    override val list: Dp,
    override val listItemTop: Dp,
    override val listItemBottom: Dp,
    override val listIndent: Dp,
    override val codeBlock: PaddingValues,
    override val blockQuote: PaddingValues,
    override val blockQuoteText: PaddingValues,
    override val blockQuoteBar: PaddingValues.Absolute,
    override val alert: MarkdownAlertPadding,
) : MarkdownPadding

/** The one [PiMarkdownPadding] per preset; see its KDoc for which fields move. */
internal fun piMarkdownPadding(profile: PiTypographyProfile): MarkdownPadding = PiMarkdownPadding(
    block = profile.markdownBlock,
    // 库的 `list` 是整组列表的上下 padding，不是项间；项间距由
    // listItemTop + listItemBottom 决定（舒适 2×4 = 8）。非经典把整组 padding
    // 收成 0，列表上/下沿只由 item padding 给，block 之后不再叠 4。
    list = if (profile.isClassic) 4.dp else 0.dp,
    listItemTop = profile.markdownListItemTop,
    listItemBottom = profile.markdownListItemBottom,
    listIndent = profile.markdownListIndent,
    codeBlock = PaddingValues(horizontal = PiSpacing.cardPadding, vertical = 10.dp),
    blockQuote = PaddingValues(horizontal = PiSpacing.cardPadding, vertical = 0.dp),
    blockQuoteText = PaddingValues(vertical = 4.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 4.dp, top = 2.dp, right = 4.dp, bottom = 2.dp),
    alert = markdownAlertPadding(),
)

/**
 * 12 dp corners keep code blocks recognisably cards rather than slabs.
 *
 * **No preset moves this.** The 排版 draft proposed unifying the card/code-block
 * radius to 10, and it was dropped: the cards (`BlockCardShape`) and the code
 * block are *already* both 12, so there was no inconsistency to unify, and 10 is
 * not a multiple of 8 while 12 is — it would leave the page's own grid for no
 * gain. The table's 8 was never in question.
 *
 * Same construction as [piMarkdownPadding]: the interface is public, the
 * builder's implementation is private and the builder is composable
 * (`multiplatform-markdown-renderer/.../model/MarkdownDimens.kt`). The values the
 * app passes are kept; `tableCellWidth = 160.dp`,
 * `tableCornerSize = 8.dp` and `alert = markdownAlertDimens()` are the 0.45.0
 * defaults, transcribed.
 *
 * `tableCellPadding` is **not** the library default: v2 draws a markdown table
 * `padding:6px 8px` per cell (`direction-b-v2.html:300-303`, `.b-tbl th,.b-tbl td`),
 * where the library's default is a uniform 16. The interface carries one `Dp` for
 * both axes, so the cell takes v2's horizontal 8 — the axis that decides how much
 * room a column's text gets — and the vertical step is the same number rather than
 * two values the interface cannot express.
 */
@Immutable
private data class PiMarkdownDimens(
    override val dividerThickness: Dp,
    override val codeBackgroundCornerSize: Dp,
    override val blockQuoteThickness: Dp,
    override val tableMaxWidth: Dp,
    override val tableCellWidth: Dp,
    override val tableCellPadding: Dp,
    override val tableCornerSize: Dp,
    override val alert: MarkdownAlertDimens,
) : MarkdownDimens

/** The one [PiMarkdownDimens] instance; constant, like the app has always had. */
internal val piMarkdownDimens: MarkdownDimens = PiMarkdownDimens(
    dividerThickness = 1.dp,
    codeBackgroundCornerSize = 12.dp,
    blockQuoteThickness = 3.dp,
    tableMaxWidth = Dp.Unspecified,
    tableCellWidth = 160.dp,
    // v2's `6px 8px`; the same number the app has always passed.
    tableCellPadding = 8.dp,
    tableCornerSize = 8.dp,
    alert = markdownAlertDimens(),
)
