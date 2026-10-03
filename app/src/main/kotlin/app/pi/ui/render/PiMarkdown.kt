package app.pi.ui.render

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 行内裸 HTML 的认领钩子（`MarkdownAnnotator.annotate`）。
 *
 * ## 为什么必须走这一条缝
 *
 * 行内 HTML 是**段落里的一个 token**（`MarkdownTokenTypes.HTML_TAG`，见
 * `PiHtml.INLINE_TYPE`），不是一个能被组件分派到的节点：段落有自己的组件
 * （`compose/MarkdownExtension.kt` 的 `PARAGRAPH` 分支），而段落里的粗体、链接、行内代码
 * 都是 annotator 拼进同一个 `AnnotatedString` 的。想从组件层拿到行内 HTML 就得把整个
 * `paragraph` 槽接过来自己解析一遍（`docs/known-gaps.md` A2 的 ①「候选 A 不可行」记着
 * 这次失败：组件层拿不到那个 annotator，自己拆会把同段其它文字的格式丢掉 —— 那一段同时
 * 也是本文件这个钩子只认领一种节点的理由）。
 *
 * 库把 annotator 作为 `Markdown(annotator = …)` 的参数暴露，而它的 `annotate` 钩子
 * **就是** `AnnotatedString.Builder` 的扩展函数
 * （`model/MarkdownAnnotator.kt`：`AnnotatedString.Builder.(content, child) -> Boolean`），
 * 返回 `true` 表示"这个 child 我处理了"、`false` 表示"交回默认处理"。于是这里可以做到
 * **只认领一种节点、别的一个都不碰**：
 *
 *     if (annotate == null || !annotate(content, child)) { …库自己的 switch… }
 *     （`annotator/AnnotatedStringKtx.kt` 的 `buildMarkdownAnnotatedString`）
 *
 * 返回 `false` 时库走的还是原来那一整段 switch，所以**不含 HTML_TAG 的消息，
 * `AnnotatedString` 一个字节都不变**。这一点不是靠样例撑着的：`PiHtml.handlesInline`
 * 只对 `"HTML_TAG"` 为真，而 `PiHtmlCheck` 拿 pin 住的解析器（`org.jetbrains:markdown`
 * 0.7.9 + GFM）**全部 77 个节点类型名**逐个跑过这个判定（夹具的 `typeUniverse`），
 * 所以"其它任何节点都不会被认领"是一条被穷举过的断言。
 *
 * ## 为什么是顶层 `val`
 *
 * 这个 lambda 的**身份**是 `DefaultMarkdownAnnotator.equals` 的一个字段，而 annotator 又是
 * `Markdown(...)` 的参数。顶层 `val` 让它整个进程只有一个实例，`remember` 不会因为
 * lambda 每次重组都新建而失效；它本身**不持有任何状态**，也不做分配（`PiHtml.inlineTextAt`
 * 只在切片里有 `\t`/`\r` 时才新建字符串）。
 *
 * ## 代价（明写，不含糊）
 *
 * 库的那行判定从 `annotate == null` 的短路变成一次虚调用 + 一次字符串比较，
 * 作用范围是"每个被拼接的 inline 节点"，时机是 annotator 生成 `AnnotatedString` 的那条路
 * （每段文本一次，不在重组/绘制的每帧路径上）。pi 自己的 `renderInlineTokens` 也是逐
 * token 一个 switch，这一层的量级与它相同。
 */
private val piInlineHtmlAnnotate: AnnotatedString.Builder.(String, ASTNode) -> Boolean =
    { content, child ->
        if (PiHtml.handlesInline(child.type.name)) {
            // pi 的行内分支：`result += applyTextWithNewlines(token.raw)`（原文照排）。
            append(PiHtml.inlineTextAt(content, child.startOffset, child.endOffset))
            true
        } else {
            // 不是 HTML_TAG：交回库的默认处理，与今天逐字节相同。
            false
        }
    }

/**
 * Renders pi's markdown natively.
 *
 * This is the app's only markdown entry point. Everything above it in the
 * transcript — [app.pi.ui.blocks.AssistantTextBlock], notifications, error
 * bodies — hands a raw markdown string to this function and gets back the same
 * document pi's terminal UI would have drawn, in pi's own colours.
 *
 * Parsing and rendering are third-party (`org.jetbrains:markdown` for the GFM
 * AST, `com.mikepenz:multiplatform-markdown-renderer-m3` for the Compose tree).
 * What lives in this package is only the part no library can know: pi's ten
 * `md*` colour tokens, pi's `syntax*` tokens, and pi's refusal to guess a code
 * language it was not told.
 *
 * Code fences are coloured by the guest engine: the highlighter is installed
 * here so every markdown surface gets it, and attached to this composition's
 * `Context` because that is the only place that knows where the runtime lives.
 * Attaching only computes paths — the request itself happens later, off the main
 * thread, once a fence has settled.
 *
 * A note on the modifier: the library defaults to `Modifier.fillMaxSize()`,
 * which is wrong inside a transcript column — it would make every message claim
 * the whole viewport. The default here is a plain [Modifier] for that reason.
 *
 * LaTeX is handled on the way *in*, through [piMarkdownSource]. pi installs two
 * `marked` tokenizers and renders every math token through `renderLatex`
 * (`packages/tui/src/components/markdown.ts:123`, `:509`, `:649`); this app's
 * parser already produces the matching nodes (`GFMElementTypes.INLINE_MATH` /
 * `BLOCK_MATH`, from its own `MathGeneratingProvider`) but draws them as their
 * literal text, so a formula would reach the screen as `$x^2$`. Rewriting the
 * formula before the parser sees it is the only place the substitution can
 * happen without duplicating the text: the renderer's annotator appends each
 * math node's raw source to the enclosing paragraph's `AnnotatedString`
 * (`com.mikepenz.markdown.annotator.AnnotatedStringKtxKt`), so a math component
 * would sit next to the raw `$x^2$` it was meant to replace.
 *
 * **This function owns the library's parser objects** ([flavour], [parser], [references]
 * below) because the library does not: its `Markdown(content, …)` builds its own on every
 * call, which defeats its `rememberMarkdownState` and re-parses the whole document on
 * every recomposition. The mechanism and its measured cost are in
 * `design/ui-refactor/08-hang-diagnosis.md`; the short version is on the `remember`s.
 *
 * @param markdown the source text, exactly as the engine emitted it. Streaming,
 *   half-finished markdown is expected and handled: the parser is given the
 *   partial document, and an unterminated fence renders as an open code block
 *   rather than as an error — the same thing pi does while a model is typing.
 *   A fence that is still being written is not highlighted until it settles; see
 *   `rememberPiHighlightedCode`.
 * @param textColor pi's base foreground for this surface, or `null` for
 *   `palette.text`. It exists for the one caller whose pi component draws its
 *   markdown in a token that is not `text`: the skill card passes
 *   `customMessageText` (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:43`),
 *   which pi's `Markdown` takes as `defaultTextStyle.color`
 *   (`components/markdown.ts:385`) — a base colour that the token colours for
 *   headings, links and code are then drawn on top of, not a replacement for
 *   them. A hand-written theme may set the two tokens differently, which is why
 *   this is a parameter rather than a constant.
 */
@Composable
internal fun PiMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    // Defaulted from the composition so the eight block call sites do not have to carry it:
    // who is allowed to pay for a synchronous parse is a property of the *row* (is this row
    // entering the viewport from above, has it ever been measured, is its text still
    // arriving), and the row's owner is `ChatScreen`. See `PiMarkdownImmediate.kt` for the
    // defect, the bound and the cost.
    immediate: Boolean = LocalPiMarkdownImmediate.current,
) {
    val context = LocalContext.current
    // `remember(context)`: attach() is idempotent and cheap, but this keeps it to
    // once per Activity rather than once per composition.
    remember(context) { PiNodeCodeHighlighter.attach(context) }
    // Keyed on the source: rewriting is a linear scan with a handful of regex
    // matches, but a streaming block re-parses on every token, so it is cached.
    // 预处理还要报告"这次有没有写进一块多行网格"：网格靠换行与空格对齐，而库的
    // annotator 默认把段落内换行压成空格，所以含网格的这条消息要按下一段的两个
    // 开关渲染 —— 别的消息一个字节都不变（`PiLatex.prepare` 的注释）。
    val prepared = remember(markdown) { PiLatex.prepare(markdown) }
    val content = prepared.text
    val hasGrid = prepared.hasDisplayGrid
    // F32 / RR-P9: everything the five markdown objects depend on is read here,
    // once per composition, and the objects themselves are built by the pure
    // functions in `PiMarkdownTheme.kt` — the library's own builders are
    // `@Composable` and therefore illegal inside `remember`'s calculation lambda
    // (see that file's header for the failed attempt this replaced). Streaming a
    // long block recomposes this function per token; without these three
    // `remember`s every token rebuilt all five objects and the component set.
    val palette = PiTheme.palette
    val darkTheme = isSystemInDarkTheme()
    val baseText = PiTheme.text.prose
    val monoText = PiTheme.text.mono
    // `06 §2`: 码块与 diff 固定 13/19 — a fence's body is the one machine role v2 gives
    // its own leading, so it does not borrow `monoText`'s 13/20.
    val codeText = PiTheme.text.code
    val colors = remember(palette, darkTheme, textColor) {
        piMarkdownColors(palette, darkTheme, textColor)
    }
    val typography = remember(palette, baseText, monoText, codeText, textColor, hasGrid) {
        // 网格用**空格**对齐（`renderLayout` 逐行补齐），比例字体里空格与字形的宽度
        // 不同、整块会歪，所以含网格的这条消息把段落样式换成等宽。改写只作用于
        // `paragraph`：标题/列表/引用的角色保持原样，其它消息完全不受影响。
        //
        // 走 `piMarkdownTypography` 的参数而不是对返回值 `copy`：那个类型是库的
        // `MarkdownTypography`，只有 `DefaultMarkdownTypography` 那个构造器能造它，
        // **没有 `copy`**（CI 的 `:app:compileReleaseKotlin` 在 `Unresolved reference
        // 'copy'` 上抓过一次）。段落的三个兄弟角色（`text`/`ordered`/`list`）保持比例，
        // 所以这不是"整条消息换成代码字体"，是"段落这一档换成等宽"。
        piMarkdownTypography(
            palette = palette,
            base = baseText,
            mono = monoText,
            code = codeText,
            textColor = textColor,
            monoParagraph = hasGrid,
        )
    }
    // `piMarkdownComponents()` is an ordinary function — `markdownComponents(...)`
    // is not composable — so it can be remembered directly. The lambdas it holds
    // are composable, but only *created* here; the library does the same thing in
    // its own non-composable `CurrentComponentsBridge` (`.../components/MarkdownComponents.kt`).
    val components = remember { piMarkdownComponents() }
    // The library's three parser objects, remembered here because it does **not** remember
    // them itself. `Markdown(content, …)` declares `flavour = GFMFlavourDescriptor()`,
    // `parser = MarkdownParser(flavour)` and `referenceLinkHandler = ReferenceLinkHandlerImpl()`
    // as default parameter values, and Kotlin evaluates a default inside the callee on
    // *every* call — the bytecode says so (`MarkdownKt.Markdown(String, …)` constructs all
    // three in its own body, between `Composer.startDefaults()` and `endDefaults()`, so the
    // Compose compiler wrapped none of them in a `remember`).
    //
    // That would be harmless if the library ignored them. It does the opposite: they are
    // exactly the keys of `rememberMarkdownState`'s
    // `remember(content, lookupLinks, flavour, parser, referenceLinkHandler, retainState)`
    // **and** three of the fields `Input.equals` compares, and none of the three overrides
    // `equals`. A fresh instance per execution therefore defeats that `remember`, builds a
    // new `Input`, wakes `snapshotFlow { currentInput }`, and calls `updateInput` +
    // `parse()` again — a whole-document re-parse plus, when the parse lands, a
    // `MarkdownSuccess` that rebuilds every node of the document in a plain `Column`. On
    // every recomposition of this composable, even when the text is byte-for-byte the same.
    //
    // Measured cost of one such pass (`design/ui-refactor/08-hang-diagnosis.md` §2.2):
    // 8.95 ms / 1026 AST nodes for a 5 KB document, 22.97 ms / 9976 nodes for 50 KB,
    // 58.02 ms / 29811 nodes for 150 KB. pi publishes the transcript once per streamed
    // event, so with a few markdown rows on screen the frame thread never goes idle — the
    // reported 「卡死 / 没有响应」, and the reason it gets worse the more content a session
    // holds. Passing stable instances keeps `Input` equal, so unchanged text never
    // re-parses and never re-renders.
    //
    // Three separate `remember`s rather than one shared instance, because
    // `ReferenceLinkHandlerImpl` is **stateful**: `lookupLinks` defaults to true, and the
    // parse stores every reference-link definition in the handler. One handler shared by
    // all rows would let a `[x]: url` written in one message resolve a `[x]` in another.
    // A per-row `remember` keeps that isolation and is still stable across this row's
    // recompositions, which is all the fix needs.
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val references = remember { ReferenceLinkHandlerImpl() }
    // The library's parse state, built here instead of inside `Markdown(content = …)` for one
    // reason: **the state is the only place the parse's completion can be seen**, and the
    // row's height floor has to be released by it (see `LocalPiMarkdownParsed`, and
    // `TranscriptRowHeight` for the collapse-then-grow the frame-count window it replaces
    // could produce).
    //
    // The arguments are exactly the ones the library's `content` overload would have built
    // its own state with — `rememberMarkdownState(content, lookupLinks, retainState, flavour,
    // parser, referenceLinkHandler, immediate)` (renderer 0.45.0 metadata; `lookupLinks`
    // defaults to `true`, which the parse's reference-link handler relies on, so it is passed
    // explicitly rather than inherited) — and `retainState = true` / `immediate = immediate`
    // are the two behavioural switches the KDoc below argues for. Nothing else changes: the
    // rendering call below is the library's own `Markdown(state = …)` overload, which is what
    // the `content` overload delegates to anyway.
    val markdownState = rememberMarkdownState(
        content = content,
        lookupLinks = true,
        retainState = true,
        flavour = flavour,
        parser = parser,
        referenceLinkHandler = references,
        immediate = immediate,
    )
    // The completion signal for the row above. `collectAsState` on the library's own
    // `StateFlow`; the effect is keyed on the state so it fires once per transition into
    // `State.Success` (a content change puts the state back to `Loading` — `retainState` only
    // keeps the *previous content* visible — so this correctly fires again for the new
    // document).
    val parsedState by markdownState.state.collectAsState()
    val onParsed = LocalPiMarkdownParsed.current
    LaunchedEffect(parsedState, onParsed) {
        if (onParsed != null && parsedState is State.Success) onParsed()
    }
    // A3, the image seam — three wiring points, and all three are needed:
    //
    //  1. this provider fills **our** local, which is what [PiImagePlaceholder]
    //     reads to decide whether an image can be drawn at all;
    //  2. `imageTransformer = imageTransformer` on `Markdown(...)` fills the
    //     **library's** `LocalImageTransformer`, which is the only seam its own
    //     components read — `MarkdownImage`/`MarkdownInlineImage` call
    //     `LocalImageTransformer.current` (`.../elements/MarkdownImage.kt:17`,
    //     `.../elements/MarkdownInlineImage.kt:12`) and the core `Markdown` provides
    //     it from this very parameter (`.../compose/Markdown.kt:258`, `:347`).
    //     Without it, the "decoded fine, now let the library draw it" hand-off in
    //     [PiImagePlaceholder] would call `MarkdownImage` and get the default no-op
    //     transformer, i.e. a node that draws nothing — worse than the placeholder
    //     it replaced;
    //  3. both come from one `rememberPiGuestImageTransformer()` instance, so the
    //     decision and the drawing can never disagree about which transformer is
    //     installed.
    //
    // `http(s)` links really load now — `bridge/GuestImageBytes.kt` was changed this round
    // from "refuse, because the render layer has no network I/O" to "fetch, under a
    // no-headers / 8 MiB / bounded-timeout / bounded-disk-cache contract". That is the one
    // behavioural deviation this round takes on, and it is the render layer's first
    // outbound request; the KDoc there states the price. A link that fails (offline,
    // timeout, over the cap, non-2xx) still keeps the alt + source fallback, because
    // `transform` answers `null` for exactly the same reasons it always did.
    val imageTransformer = rememberPiGuestImageTransformer()
    // What one eager row actually costs the frame, measured rather than estimated: the parse
    // (`parseBlocking`, run inside `rememberMarkdownState`) **and** the composition of the
    // node tree it produces, both of which are inside the `Markdown` call below. It is billed
    // to the shared frame budget that decided this row was allowed to be eager — see
    // `MarkdownParseBudget` for the measurements the 4 ms figure rests on and for why the bill
    // is elapsed time and not a character count.
    val parseStartedNanos = if (immediate) System.nanoTime() else 0L
    CompositionLocalProvider(
        LocalPiCodeHighlighter provides PiNodeCodeHighlighter,
        LocalPiImageTransformer provides imageTransformer,
    ) {
        Markdown(
            // The state built above, not the `content` overload: the state is what makes the
            // parse's completion observable to the row. Every other argument is the same one
            // the content overload would have received.
            markdownState = markdownState,
            colors = colors,
            typography = typography,
            padding = piMarkdownPadding,
            dimens = piMarkdownDimens,
            imageTransformer = imageTransformer,
            components = components,
            // 网格必须**保住换行**：annotator 的 `EOL -> if (eolAsNewLine) append('\n')
            // else append(' ')`（`annotator/AnnotatedStringKtx.kt`）默认走 `else`，一块
            // 网格会被压成一行 —— 比不画更糟。只在含网格的消息里打开（见 `hasGrid`）。
            // pi 自己就是保留换行的（`components/markdown.js:367-369` 把段落文本整串
            // 推进 `lines`），所以这是往 pi 靠；**不全量打开**是因为那会改掉所有消息的
            // 段落观感（App 的软换行今天渲染成空格），那是另一笔交易。
            //
            // `annotate` 是行内裸 HTML 的认领钩子（`piInlineHtmlAnnotate` 的注释里有它
            // 为什么只能落在这一层、以及它的代价）：库拿它当"这个 child 我处理了吗"，
            // 返回 `false` 就原样走库自己的 switch，所以只有 `HTML_TAG` 会被认领。
            // 两个参数共用同一个 `remember`：`markdownAnnotator` 造出来的
            // `DefaultMarkdownAnnotator` 按 `config` + `annotate` 判等，而这两者都是稳定
            // 的（`hasGrid` 是这次渲染的一个常量，lambda 是顶层 `val`）。
            annotator = remember(hasGrid) {
                markdownAnnotator(
                    config = markdownAnnotatorConfig(eolAsNewLine = hasGrid),
                    annotate = piInlineHtmlAnnotate,
                )
            },
            // Two library defaults this renderer must not inherit. Both are about the
            // *streaming* case, and both were inherited silently until
            // `docs/streaming-review.md` §2.3/§2.4 — the streaming row is the one row
            // that recomposes on every token, so a default is a per-token decision
            // here whether or not anyone made it.
            //
            // 1. `animations`: upstream defaults `MarkdownAnimations.animateTextSize`
            //    to `Modifier.animateContentSize()` (renderer 0.45.0,
            //    `model/MarkdownAnimations.kt:40-49`), which every markdown text
            //    segment is drawn with (`compose/elements/MarkdownText.kt:214`). On a
            //    growing answer that starts a size animation on *every* token, and what
            //    it animates is the *measured height* the row reports
            //    (`androidx.compose.animation:animation-android:1.8.3`,
            //    `SizeAnimationModifierNode.measure`: the child is placed inside the
            //    animated bounds, and 1.8.3 has no `clip` parameter). So the row's
            //    height lags the text it holds: the newest lines overflow into the
            //    next row, the transcript's own viewport clips them while the row is
            //    the last one, and the follow's geometry ("are we at the bottom?") is
            //    computed from the lagging height - the follow chases an animation
            //    that every token restarts. It is also the only animation anywhere in
            //    this app's UI (`grep -rn "animate" app/src/main/kotlin/app/pi/ui`
            //    finds none of our own), which is the trade the user has already
            //    decided against: it animates a size, carries no information, and
            //    hides text while it runs. The library documents `{ this }` as the way
            //    to switch it off (`MarkdownAnimations.kt:44-47`), which is exactly the
            //    identity.
            //
            // 2. `retainState`: with the default `false`, every content change puts the
            //    row back into `State.Loading` (`model/MarkdownState.kt:186-187`) — and
            //    the loading slot renders an empty `Box` by default. The parse then
            //    finishes on `Dispatchers.Default`, so whether a frame is drawn with
            //    the row blanked is a race between that parse and the next vsync: on a
            //    quiet frame the row keeps its text, on a slow one it collapses and
            //    comes back. That is the reported "有时候会乱闪、在底部反复刷新", and
            //    `retainState = true` is the library's own answer ("the previous content
            //    remains visible while new content is being parsed"). Nothing else
            //    changes: with it, a row only ever moves forwards.
            //
            // `retainState` is only reachable on the core `content: String` entry
            // point — the m3 wrapper does not forward it (`markdown-renderer-m3`,
            // `m3/Markdown.kt:62-104`) — which is why this call is
            // `com.mikepenz.markdown.compose.Markdown` and not the m3 overload. The m3
            // wrapper contributed nothing else here: this call already passes its own
            // colours, typography, dimens, padding, components and image transformer,
            // which is everything the wrapper would have supplied.
            // (Now an argument of `rememberMarkdownState` above: it is a property of the
            // *state*, not of the rendering call.)
            animations = markdownAnimations(animateTextSize = { this }),
            // 3. `immediate`: upstream defaults it to `false`, i.e. every first composition
            //    of a row draws `State.Loading` — an **empty `Box`**, zero height — for one
            //    frame or more while the parse runs on `Dispatchers.Default`
            //    (`model/MarkdownState.kt`; the `true` branch is the `parseBlocking()` call
            //    right after the state is constructed). A row entering the viewport from
            //    *below* grows under the fold and nobody notices; a row entering from
            //    *above* — which is what 「加载更早」 hands the reader — pushes every visible
            //    row down by its own height when it arrives. `RowHeightCache` covers a row
            //    that has been measured before; this covers the one that has not, and the
            //    caller decides which rows those are (`PiMarkdownImmediate.kt`, where the
            //    frame budget that thins a crowd of eager rows is also described).
            //    (Now an argument of `rememberMarkdownState` above, like `retainState`.)
            modifier = modifier,
        )
        if (immediate) {
            piMarkdownParseBudget.chargeNanos(
                nowNanos = System.nanoTime(),
                costNanos = System.nanoTime() - parseStartedNanos,
            )
        }
    }
}

/**
 * 数学预处理：**只转发**到 [PiLatex.preprocess]，实现与全部理由都在那里。
 *
 * 为什么实现不在这里：这段逻辑是纯字符串与正则（不碰 Compose），而它恰恰是
 * 数学这一路里最容易悄悄画错的一段 —— 哪种定界符在什么位置算公式、要不要吃掉
 * 收尾的换行。放在 [PiLatex] 里就能被 `tools/run-app-pure-checks.sh` 编译并对着
 * pi 自己的 tokenizer 生成夹具比对（`app/src/test/kotlin/app/pi/ui/render/PiLatexCheck.kt`），
 * 而留在这个文件里（它有 Compose import）一个断言都跑不了。
 *
 * 名字与签名保持原样：`PiMarkdownComponents.kt` 的文档引用着它。
 */
internal fun piMarkdownSource(markdown: String): String = PiLatex.preprocess(markdown)
