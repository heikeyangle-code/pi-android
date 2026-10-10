package app.pi.ui.render

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.highlight.PiNodeMermaidRenderer
import app.pi.rpc.PiImage
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readString
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme
import java.io.File
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Which highlighter code blocks use. A composition local rather than a parameter
 * because the code components are reached through the renderer's own dispatch,
 * far from the call site that knows whether highlighting is available.
 *
 * The default is the real backend — pi's own highlight.js, served by the guest
 * engine over loopback (`app.pi.highlight.PiNodeCodeHighlighter`). It is safe as
 * a default because it degrades on its own: before [app.pi.highlight.PiNodeCodeHighlighter.attach]
 * runs, and whenever the engine is down, it answers with no spans and
 * `languageKnown = false`, and every block renders in `mdCodeBlock` — which is
 * exactly pi's branch for a fence whose language highlight.js does not know
 * (`theme.ts:1085`). A caller that wants to be sure of that (or to test the
 * renderer without an engine) provides [PiPlainCodeHighlighter] explicitly.
 */
internal val LocalPiCodeHighlighter = staticCompositionLocalOf<PiCodeHighlighter> {
    PiNodeCodeHighlighter
}

/**
 * Images are the renderer's other pluggable seam.
 *
 * The default is upstream's no-op (`NoOpImageTransformerImpl.transform` returns `null`),
 * and it is only reached by a caller that renders this component outside
 * [PiMarkdownText] — a preview, a test. The app's real path installs
 * `bridge/rememberPiGuestImageTransformer()` here (`PiMarkdown.kt`), which is the same
 * instance handed to the library through `Markdown(imageTransformer = …)`. Two
 * composition locals exist because the library reads its own
 * (`com.mikepenz.markdown.compose.LocalImageTransformer`) inside
 * `MarkdownImage`/`MarkdownInlineImage`, while [PiImagePlaceholder] needs the transformer
 * *before* delegating, to keep its text fallback for a link whose bytes are not there.
 */
internal val LocalPiImageTransformer = staticCompositionLocalOf<ImageTransformer> {
    NoOpImageTransformerImpl()
}

/**
 * 正文里的一张图被点了一下时，把**交给查看器的那份 wire 值**送去哪里。
 *
 * ## 为什么是一个组合局部，而不是给组件加参数
 *
 * 两个图片槽（[PiImagePlaceholder] / [PiInlineImage]）是**库按节点分派**到的，离"谁知道查看器
 * 在哪"那个调用点很远：宿主是 `ChatScreen` 的 `viewedImage: PiImage?` → `PiImageViewer`
 * （`ui/screens/ChatScreen.kt:2786-2794`），而它只把 `onImageClick` 交给 `BlockRenderer`
 * （`:2260`）。库的组件集是 `PiMarkdown.kt` 里 `val components = remember { piMarkdownComponents() }`
 * 造一次、全程复用的，参数进不去（那个 `remember` 正是这个文件不是 `@Composable` 的原因）；
 * 和 [LocalPiImageTransformer] 用组合局部是同一个理由。
 *
 * ## 默认 `null` = 没有查看器可开
 *
 * 此时两个图片槽**一个修饰符都不多**、直接调库的组件：预览、测试、以及正文之外那几个
 * markdown 表面（`HookMessageBlock` / `CompactionBlock` / `BranchSummaryBlock` /
 * `SkillInvocationBlock` 都没传回调）与这次改动之前逐字节相同 —— 它们的图仍然不可点。
 *
 * ## 为什么是 `compositionLocalOf` 而不是 `staticCompositionLocalOf`
 *
 * 这个值是一个**回调**，身份由调用点决定（`ChatScreen` 的 `{ viewedImage = it }`）。
 * `staticCompositionLocalOf` 的值一变会重组 provider 的**整个**子树，而正文 markdown 恰好是
 * `PiMarkdown.kt` 一直在避免按上层重组次数付代价的地方（那些 `remember` 的注释就是账本）。
 * 动态那个只让**读者**失效，而读者只有两个图片槽。
 */
internal val LocalPiImageClick = compositionLocalOf<((PiImage) -> Unit)?> { null }

/**
 * The renderer's component set: pi's code-block chrome, pi's math, the
 * image fallback, the one thing the library gets wrong about tables, and bare
 * block HTML.
 *
 * Everything except those five keeps the library default, because the default
 * reads its colours and type from the [com.mikepenz.markdown.model.MarkdownColors]
 * and [com.mikepenz.markdown.model.MarkdownTypography] built in
 * `PiMarkdownTheme.kt` — which are pi's tokens. Overriding a component that
 * already renders in pi's colours would only be a chance to get it wrong.
 *
 * Code blocks are the first exception: pi draws a border in `mdCodeBlockBorder`
 * around every fence and prints the fence language above it. The library's
 * default code block has a background but no border, so the border is added.
 *
 * Math is the second, and bare block HTML is the fifth; both arrive through the
 * `custom` slot, and [piCustomComponent] explains why they have to share it and
 * why that slot can only be widened one named node type at a time (the "claims
 * everything" trap). Line HTML is *not* here — it is an inline token, not a
 * dispatched node, so it is claimed in `PiMarkdown.kt`'s annotator instead
 * (`PiHtml.INLINE_TYPE` has the two halves of pi's branch).
 *
 * Images are the third, and they take **two** slots, because the library splits the
 * surface: a block image reaches `image` ([PiImagePlaceholder]), one written inside a
 * paragraph reaches `inlineImage` ([PiInlineImage]). Real bytes arrive through
 * `bridge/PiGuestImageTransformer.kt` (installed by `PiMarkdown.kt`), so both components
 * draw them and both keep saying "an image was here, and here is where it pointed" when
 * the link cannot be resolved — the library's own inline default would draw nothing at
 * all there. See each component's comment for why the decision is made on
 * `transform`'s *result* rather than on the transformer's type.
 *
 * Tables are the fourth, and they claim the `table` slot for exactly one reason —
 * the library gives every cell one line ([PiTable]). Everything else about a
 * table (the parse, the fixed column width, the horizontal scroll) stays the
 * library's.
 *
 * **Not composable, on purpose.** `markdownComponents(...)` is a plain function
 * in the library (`.../compose/components/MarkdownComponents.kt`) whose
 * parameters are `@Composable` lambdas; the library builds its own default set
 * exactly this way from a non-composable `object CurrentComponentsBridge`. This
 * app therefore declares no `@Composable` here either, which is what lets
 * `PiMarkdownText` cache the whole set with `remember { piMarkdownComponents() }`
 * (F32 / RR-P9). Adding the annotation back would make that `remember` illegal
 * again and rebuild the set — and with it every default component's identity —
 * on every token of a streaming block.
 */
internal fun piMarkdownComponents(): MarkdownComponents = markdownComponents(
    codeFence = { model -> PiCodeFence(model) },
    codeBlock = { model -> PiCodeBlock(model) },
    image = { model -> PiImagePlaceholder(model) },
    inlineImage = { model -> PiInlineImage(model) },
    table = { model -> PiTable(model) },
    custom = { type, model -> piCustomComponent(type, model) },
)

/**
 * The two things the renderer's dispatch would otherwise drop: pi's math nodes, and
 * **bare block HTML**.
 *
 * **The trap.** The renderer's dispatch (upstream
 * `multiplatform-markdown-renderer`, `compose/MarkdownExtension.kt:92-94`) is
 *
 *     else -> {
 *         handled = components.custom?.invoke(node.type, model) != null
 *     }
 *
 * and the `custom` slot's type is
 * `@Composable (IElementType, MarkdownComponentModel) -> Unit`. `Unit` is never
 * `null`, so the moment a `custom` function is supplied, **every node type that
 * is not one of the renderer's twenty built-in cases is reported as handled**
 * and the fallback that recurses into `node.children` (`MarkdownExtension.kt:97-101`)
 * stops running. Supplying a `custom` therefore changes the rendering of node
 * types it never mentions.
 *
 * There is no return value that can undo that: the `handled` flag is computed
 * from the *call*, not from anything the callee can say. The only way to keep
 * the default behaviour for the other types is to not claim them, which is
 * exactly what this function does — every branch names one node type and there is
 * no `else`. It returns for the node types it was written for and does nothing
 * for everything else, leaving the dispatch's own conclusion alone rather than
 * trying to re-implement the recursion it cannot reach. **That is why the two new
 * branches are added inside this `when` and not by widening the claim**: the set of
 * claimed types is exactly `{INLINE_MATH, BLOCK_MATH, HTML_BLOCK}`, and
 * `PiHtmlCheck` pins the HTML half of that set against every node type name the
 * pinned parser can produce (77 of them).
 *
 * Math. In normal rendering these branches are in fact unreachable, because
 * [piMarkdownSource] rewrites `$...$` / `$$...$$` before the parser runs; see
 * [PiMarkdownText]. They are kept because they are the AST-level statement of what
 * this app supports, and because the renderer's dispatch reaches them for *any*
 * node build it is handed — including a partial document, which is what every
 * streaming token produces — so a math node that survives to a component call
 * must land here rather than silently vanishing. A node that reaches this point is
 * rendered as the formula's own source in `mdCode`, which is the phone's equivalent
 * of pi's pending formula (`packages/tui/src/components/markdown.ts:508`):
 * unmistakably a formula, visibly not typeset.
 *
 * Block HTML. This branch **is** reachable, and before it existed the whole block
 * was dropped: `HTML_BLOCK` is not one of the renderer's built-in cases, so it fell
 * to the `custom` slot, the `when` had no branch for it, and — per the trap above —
 * `handled` was already true, so the node was neither drawn nor recursed into. Not
 * a tag vanished; the text inside it vanished too. pi never renders HTML either: it
 * prints the block's own bytes, trimmed (`PiHtml.BLOCK_TYPE` has the source).
 *
 * (The library also ships a streaming parser that keeps a stable AST prefix and
 * re-parses only its tail — `StreamingMarkdownState`, `model/StreamingMarkdownState.kt`
 * in 0.45.0 — but this app does not use it: it is append-only (`append(chunk)`)
 * while the App's transcript carries the accumulated text, so adopting it needs a
 * delta channel out of the reducer first. See `docs/streaming-review.md` §4.2.)
 */
@Composable
private fun piCustomComponent(type: org.intellij.markdown.IElementType, model: MarkdownComponentModel) {
    when {
        type === GFMElementTypes.INLINE_MATH -> PiFormulaText(model, block = false)
        type === GFMElementTypes.BLOCK_MATH -> PiFormulaText(model, block = true)
        // 走 `PiHtml.handlesBlock` 而不是直接 `type === MarkdownElementTypes.HTML_BLOCK`，
        // 是为了让"认领哪些类型"这句能被执行、而不是只被读出来：判定在本机跑得动
        // （`PiHtmlCheck` 对解析器的类型全集逐条断言）。这里多付的只是一次 8 字节
        // 字符串比较，而且只在"库的二十个内建 case 都不认"的节点上付。
        PiHtml.handlesBlock(type.name) -> PiHtmlBlock(model)
    }
}

/**
 * 裸块级 HTML：**照抄 pi，把原文当文字排出来**，不解析、不转义、不改写。
 *
 * pi 的分支是 `lines.push(this.applyDefaultStyle(token.raw.trim()))`
 * （`packages/tui/src/components/markdown.ts:622`）—— 一个字符都不丢：内部换行原样
 * 保留（`renderToken` 把整串推进 `lines`，由 `wrapTextWithAnsi` 按 `\n` 拆行），所以
 * `<div>\nhello\n</div>` 在终端上是三行。这里用同一个 [PiHtml.blockTextAt]，
 * 期望值由 pi 自己渲染的字节钉住（`app/src/test/resources/pi-html-fixtures/cases.json`）。
 *
 * **为什么用 [Text] 而不是库的段落组件。** `MarkdownParagraph` 拼字符串走的是 annotator
 * （`.../elements/MarkdownParagraph.kt:21-25`），而 annotator 对 `HTML_BLOCK_CONTENT`
 * 这类子节点落进 `else`、什么都不 append，于是它会画出一个**空**段落。这个块要的是
 * "把这一片源文本原样画出来"，那就只有直接 `Text` 一条路。
 *
 * **颜色与字号**：只给 `typography.paragraph`，**不另给 `color`**。库的段落走
 * `MarkdownBasicText`，它的取色顺序是「显式 `color` → `style.color` →
 * `LocalMarkdownColors.current.text`」（`.../elements/material/TextWrapper.kt`），而
 * `piMarkdownTypography` 的 `paragraph` 颜色**总是**指定的（`body` =
 * `textColor ?: palette.text`）。所以不传 `color` 得到的就是"它如果真是一个段落时"的
 * 同一个颜色；传 `palette.text` 反而会在 `textColor` 被覆盖的表面（技能卡片传
 * `customMessageText`）上与相邻段落不一致。不加 `maxLines`：pi 不截断。
 */
@Composable
private fun PiHtmlBlock(model: MarkdownComponentModel) {
    val content = model.content
    val node = model.node
    // `remember(content, node)`：切分与 trim 只在节点或源文本变了的时候重做一次，
    // 重组（同一条消息重新测量、主题变化）不重算。它不在每帧路径上。
    val text = remember(content, node) {
        PiHtml.blockTextAt(content, node.startOffset, node.endOffset)
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = model.typography.paragraph,
    )
}

/** One un-rewritten formula, in pi's "shown as written" style. */
@Composable
private fun PiFormulaText(model: MarkdownComponentModel, block: Boolean) {
    val palette = PiTheme.palette
    val source = remember(model.content, model.node) {
        PiLatex.formulaText(
            content = model.content,
            text = model.content.substring(model.node.startOffset, model.node.endOffset),
            block = block,
            fallback = "（公式）",
        )
    }
    // 能归约成多行网格时画网格（`PiLatex` 的 layout 支），否则按 pi 的回退画原文。
    // 正常路径下块级公式根本到不了这里 —— 预处理已经把网格写回源文本了
    // （`PiLatex.prepare` 的注释），这一段是"源里还有漏网的 math 节点"时的兜底。
    val grid = remember(source, block) {
        PiLatex.toUnicode(source, display = block)?.takeIf { it.contains('\n') }
    }
    Text(
        text = grid ?: source,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (block) 6.dp else 2.dp),
        style = model.typography.code,
        color = palette.mdCode,
        // **网格不能居中**：`TextAlign.Center` 是**逐行**居中的，每一行的偏移量都等于
        // (可用宽度 − 这一行宽度) / 2，于是 `⎛ a │ b ⎞` 与 `⎝ c │ d ⎠` 会被推成不同的
        // 起点、整块拆散。网格的对齐全靠它自己的空格（`renderLayout` 逐行补齐），
        // 所以必须左对齐 —— pi 也是这么推行的（`components/markdown.js:384-386`）。
        textAlign = TextAlign.Start,
    )
}

/**
 * Markdown images: draw the bytes when they can be reached, otherwise say what was
 * there.
 *
 * **pi has no counterpart here.** Its terminal renderer has no `image` case at all, so
 * a markdown `![]()` falls to its `default` branch and prints the alt text
 * (`packages/tui/src/components/markdown.ts:619-627`) — a markdown image in pi is
 * *text*, never a fetch. Anything visible in this app is therefore an addition, not
 * parity, and `docs/known-gaps.md` A3 records it as such.
 *
 * **Where the bytes come from now.** `bridge/PiGuestImageTransformer.kt` implements the
 * renderer's image seam (`ImageTransformer.transform(link) -> ImageData?`, upstream
 * `model/ImageTransformer.kt:16-29`) on top of `bridge/GuestImageBytes.kt`, which
 * decodes `data:` URIs, maps `file:`/bare paths from the guest's spelling to the host
 * file, and fetches `http(s)` images into a bounded disk cache.
 * `PiMarkdown.kt` installs it through [LocalPiImageTransformer] *and* through
 * `Markdown(imageTransformer = …)`, so both this component and the library's own image
 * components see it.
 *
 * **Why the decision is made on the result, not on the transformer's type.** The
 * library drops the whole node when `transform` returns `null` — `MarkdownImage` reads
 * `LocalImageTransformer.current.transform(link)?.let { … }`
 * (`.../compose/elements/MarkdownImage.kt:17-29`), so a `null` means the image vanishes
 * from the transcript with no trace. Asking *first* and keeping the alt + source text
 * for a `null` is what keeps a link that cannot be resolved — a file that is missing, a
 * body over the size cap, a remote fetch that is offline or times out
 * (`bridge/GuestImageBytes.kt`), and the first frame of any real load, which is still
 * empty — strictly better than the no-op default's silence.
 *
 * `MarkdownImage` calls `transform` again; that second call is a cache read
 * (`PiGuestImageTransformer.transform` seeds `produceState` from its bitmap cache), so
 * the delegation costs no second IO.
 */
@Composable
private fun PiImagePlaceholder(model: MarkdownComponentModel) {
    val content = model.content
    val node = model.node
    val transformer = LocalPiImageTransformer.current
    val referenceHandler = LocalReferenceLinkHandler.current
    val link = remember(content, node) { node.resolveImageLink(content, referenceHandler) }
    // Ask for the bytes before deciding, for the reason in the doc above: a `null`
    // result must fall through to the text fallback instead of handing the node to a
    // component that would draw nothing.
    val decoded = if (link != null) transformer.transform(link) else null
    if (decoded != null) {
        // Bytes in hand; let the library draw them (it reuses the same decoded bitmap),
        // and put the viewer behind a tap — see [PiImageViewerTap]. The fallback below
        // deliberately stays inert: a link whose bytes are not there has nothing to open.
        PiImageViewerTap(link) { MarkdownImage(content, node) }
        return
    }
    // The block component is the only one that can resolve an alt: its `model.content`
    // is the *whole document* plus the image node, which is what `resolveImageAlt`
    // needs. The inline slot is not so lucky — see [PiInlineImage].
    val alt = remember(content, node) { node.resolveImageAlt(content) }
    PiImageFallback(
        alt = alt,
        link = link,
        typography = model.typography,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

/**
 * An image written *inside* a paragraph (`text ![](…) text`), which the library renders
 * through an `InlineTextContent` placeholder rather than through the `image` slot: its
 * `MarkdownText` builds inline content for every `IMAGE` node and hands each one to
 * `components.inlineImage` (`.../elements/MarkdownText.kt:384`), whose default is
 * `CurrentComponentsBridge.inlineImage` → `MarkdownInlineImage`
 * (`.../compose/components/MarkdownComponents.kt:210-212`).
 *
 * **Why this has to be our own component rather than a configuration.** The library's
 * inline path has no failure branch at all: `MarkdownInlineImage` is
 * `transformer.transform(link)?.let { Image(…) }`
 * (`.../elements/MarkdownInlineImage.kt:13`), so a `null` draws **nothing** while the
 * placeholder box the library already reserved for the link stays on screen as empty
 * space. pi's terminal prints the image token's text instead — the alt text
 * (`packages/tui/src/components/markdown.ts:619-627`) — so this is a real divergence, and
 * the only seam that can fix it is the `inlineImage` slot itself. Nothing else is
 * consulted on this path.
 *
 * **What the model does and does not carry here.** `content` is the resolved **link**
 * (`MarkdownInlineImageWithSize` builds `MarkdownComponentModel(link, node, typography)`,
 * `.../elements/MarkdownText.kt:384-386`), and `node` is the *enclosing text node* whose
 * offsets belong to the full document — which this component never receives. The alt
 * text is therefore unreachable in the inline slot: the annotator only appends
 * `appendInlineContent(tag, imageUrl)` for an image
 * (`.../annotator/AnnotatedStringKtx.kt:280-282`), so neither the alt nor the image node
 * survives. That is why the fallback below passes `alt = null` and renders the same
 * "图片 / 图片地址…" shape the block component uses, through the shared [PiImageFallback];
 * the wording lives in exactly one place.
 *
 * The success path delegates to the library component, which draws
 * `Modifier.fillMaxSize()` inside the box the library sized for this link — the same
 * transformer instance, so `transform` is a cache read.
 */
@Composable
private fun PiInlineImage(model: MarkdownComponentModel) {
    val link = model.content
    val transformer = LocalPiImageTransformer.current
    val decoded = if (link.isNotBlank()) transformer.transform(link) else null
    if (decoded != null) {
        // Same tap as the block slot — one implementation ([PiImageViewerTap]), so the two
        // surfaces cannot drift into two different "what does a tap do" answers.
        PiImageViewerTap(link) { MarkdownInlineImage(link, model.node) }
        return
    }
    PiImageFallback(alt = null, link = link, typography = model.typography)
}

/**
 * 一次点按 = 打开查看器。**只有两件事**：取字节（[piMarkdownImageViewerImage]），把
 * [PiImage] 交给 [LocalPiImageClick]。
 *
 * ## 为什么这一层只在有查看器时才存在
 *
 * `LocalPiImageClick.current` 为 `null` 时直接画 `content()` —— **不多一个 `Box`、不多一个
 * 修饰符**。所以预览、测试、以及正文之外那几个 markdown 表面的渲染与这次改动之前逐字节
 * 相同（见 [LocalPiImageClick]）。
 *
 * ## 为什么整段异步
 *
 * 取字节最坏是一次磁盘读或一次出网（`bridge/GuestImageBytes.kt` 的 5/5/45 s）；点按那一帧
 * 不能等它。所以 `clickable` 只起一个协程，字节到手之后再调回调。
 *
 * 回调拿到的是**已经读出来的字节**，不是 link：查看器要的就是
 * `PiImage(base64, mimeType)`，而 link 到字节的换算（`data:` / guest 路径 / `http(s)`）在
 * 字节通道里已经有一份，不许在这里长第二份。
 *
 * 局部为 `null`、或 link 解析不出来（block 槽的 `resolveImageLink` 可以给 `null`）时，
 * 这一层退化成"没有点按" —— 与改动前一样。
 */
@Composable
private fun PiImageViewerTap(link: String?, content: @Composable () -> Unit) {
    val onImageClick = LocalPiImageClick.current
    // 两个都是局部 `val`：下面的 lambda 里用它们靠的是**局部不可变变量**的智能转换，而不是
    // `isNullOrBlank()` 的契约推断 —— 后者在 `||` 里是否传播到 lambda 里不值得赌。
    val openLink = link?.takeIf { it.isNotBlank() }
    if (onImageClick == null || openLink == null) {
        content()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.clickable(onClickLabel = "查看图片") {
            scope.launch {
                piMarkdownImageViewerImage(context, openLink)?.let(onImageClick)
            }
        },
    ) {
        content()
    }
}

/**
 * The text drawn in place of an image whose bytes are not there: the alt line and the
 * source, so a reader can see that an image was meant to be here and where it pointed.
 *
 * One implementation for both image surfaces — [PiImagePlaceholder] (a block image,
 * which can resolve a real alt) and [PiInlineImage] (an inline one, which cannot) — so
 * the two can never drift into two different wordings for the same situation.
 *
 * @param alt the image's alt text, or `null` when the surface cannot supply one; the
 *   first line falls back to a plain "图片" label rather than inventing text.
 * @param link the resolved source, or `null` when it could not be resolved at all.
 */
@Composable
private fun PiImageFallback(
    alt: String?,
    link: String?,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Column(modifier = modifier) {
        Text(
            text = alt?.takeIf { it.isNotBlank() } ?: "图片",
            style = typography.text,
            color = palette.text,
        )
        Text(
            text = link?.takeIf { it.isNotBlank() }?.let { "图片地址：$it（当前无法显示）" }
                ?: "图片地址缺失",
            modifier = Modifier.padding(top = 2.dp),
            style = typography.code,
            color = palette.dim,
        )
    }
}

/**
 * GFM 表格：解析、列宽、横向滚动都留给库，我们只把库写死的「每格 1 行」换成
 * [TABLE_MAX_LINES]。
 *
 * ## 为什么要覆盖这个槽
 *
 * 库的 `MarkdownComponents.table` 默认是
 * `MarkdownTable(it.content, it.node, style = it.typography.table)`
 * （`compose/components/MarkdownComponents.kt:216-217`，0.45.0 `core` artifact 源码），
 * 而 `MarkdownTable` 给单元格的默认值写死成一行：
 * `MarkdownTableHeader(maxLines: Int = 1, overflow = TextOverflow.Ellipsis, …)`
 * （`compose/elements/MarkdownTable.kt:124-133`）与
 * `MarkdownTableRow(maxLines: Int = 1, …)`（同文件 `:164-175`），两者都把这个值原样交给
 * `MarkdownTableBasicText(maxLines: Int = 1, …)`（同文件 `:217-225`；转交在 `:151-158`、
 * `:192-199`，最终落到 `MarkdownBasicText` 的 `maxLines`，`:258-267`）。
 * `MarkdownTable(content, node, style, annotatorSettings, headerBlock, rowBlock)` 的签名
 * （`:65-81`）**没有** `maxLines` 参数，所以只能从 `headerBlock` / `rowBlock` 两个槽换掉它。
 *
 * 用户看到的是「表格的确很多显示不全，只能显示前几行字，每一格里」：每格只画一行、
 * 其余用「…」截掉，而且没有任何交互能看到被截的部分（表头与数据行都是这样）。
 *
 * ## 这个数字只有一处：[TABLE_MAX_LINES]
 *
 * 表头与数据行分成两个 lambda 只是因为库的签名如此；两边都引用同一个 [TABLE_MAX_LINES]，
 * 而第三个组件（单元格 `MarkdownTableBasicText`）拿到的是库从这两个值转交下去的同一个数
 * ——我们不自己调它（那要重写整行布局），所以表格路径上不存在第二个常量可以漂移。
 *
 * ## 为什么是 50：不是 1，也不是无限
 *
 * - **不能回到 1。** 1 就是用户报的症状（每格一行 + 省略号），那正是这次要修的。
 * - **不能是 `Int.MAX_VALUE`**（本槽加上时用的值，也是这次要换掉的值）。库确实把
 *   `Int.MAX_VALUE` 当作「不限」（`.../elements/material/TextWrapper.kt` 的
 *   `maxLines: Int = Int.MAX_VALUE`），但表格这条路上「不限」是**按内容计费**的：
 *   单元格的排版行数没有上界，而这张表不是 Lazy（见下），所以一个被模型写成整段话的
 *   单元格可以把成本抬到任意高。
 * - **50 行的理由**是「大到任何真实单元格都碰不到，小到病态单元格有上界」。按
 *   `tableCellWidth = 160.dp` 与正文 ≈14sp 反推：一行约 11 个汉字（或约 22 个拉丁
 *   字符），50 行 ≈ 550 个汉字，比任何人在表格单元格里写的一句话都长。`overflow`
 *   仍是库的 `Ellipsis`，所以万一真有单元格超过 50 行，它是「截断 + …」，而不是
 *   无限高、也不是静默丢字。
 *
 * ## 代价：单元格数 × 每格排版行数（算术，不是真机测量）
 *
 * 表格的横向滚动不是 Lazy：`MarkdownTable` 在
 * `scrollable = maxWidth <= tableWidth` 为真时用
 * `Modifier.horizontalScroll(rememberScrollState()).requiredWidth(tableWidth)`
 * （`:99-103`），它只裁剪**绘制**——表里所有行、所有列的单元格照样全部组合、测量、
 * 提交绘制；列宽是 `columnsCount * LocalMarkdownDimens.current.tableCellWidth`
 * （`:82-88`），我们设的 160 dp 不随屏幕压缩。于是成本是
 *
 *     单元格数 = (数据行数 + 1) × 列数        // 30 行 × 4 列 = 124 格
 *     每格排版行数 ≈ min(内容折行数, TABLE_MAX_LINES)
 *
 * 同一张 30×4 的表：每格 1 行时是 124 行文本布局；`Int.MAX_VALUE` 时这个数由内容决定
 * ——一个 2 千字的单元格在 160 dp 列宽下要折 ≈180 行，124 格都这样就是两万行量级，
 * 而且没有上界可写；现在是 ≤ 124 × 50 = 6200 行，且与内容长度无关。
 *
 * 表头那一行还多付一层：`MarkdownTableHeader` 把 `Modifier.height(IntrinsicSize.Max)`
 * 放在表头 `Row` 上（`:135-137`），于是**每个表头格都会额外付一次本征测量**（≈一次
 * 完整文本布局）——4 列的表就是 4 次额外测量，每次同样被 50 行封顶。单元格自己的
 * `Modifier.onPlaced { containerSize.value = … }`（`:237`、`:260-262`）又是上面
 * `remember(text, …, containerSize.value, …)`（`:241-244`）的键，所以首帧每格还会多
 * 一次重组 + 一次文本重测（之后写等值不再失效，会收敛）；这部分由库决定，与本常量无关。
 *
 * ## 不做什么，以及为什么
 *
 * **不动横向，也不动列宽。** `horizontalScroll(requiredWidth(tableWidth))` 就是库自己的
 * 实现（`:99-103`），与 `PiCodeSurface` 给代码块用的 `.horizontalScroll(rememberScrollState())`
 * 是同一个惯用法；`tableCellWidth = 160.dp` 是库默认值、不是用户报的症状，没有证据不改数字。
 *
 * **不加上限高度、不加内部纵向滚动。** 单元格会折行，行数多表格就高，由外层列表滚动
 * 接管——和代码块一样（`PiCodeSurface` 对 200 行的围栏也没有高度上限）。加一个
 * `heightIn(max = …)` + 内部 `verticalScroll` 会把超限的行藏进一个要用户自己发现的内层
 * 滚动条里，那正是这次报的「显示不全」换了个方向。
 *
 * ## 第三个理由：格子里的图（后加）
 *
 * 库里"一格里的 `![]()`"走的是**行内**图片那条路（`MarkdownTableBasicText` 读库自己的
 * `LocalImageTransformer`，`MarkdownTable.kt:218-267`），而这条路的两个尺寸都由**容器**决定：
 * 解码宽度取自窗口（与格子无关 → 一张 6×44 的表要解 40 多张 1080 px 的位图，约 50 MB，
 * 远超 32 MiB 位图缓存），占位框的高还取自"这一格自己的高度"（于是框与行高互相追）。
 * `PiTable` 因此在这里换一个**按单元格内容宽构造成**的 transformer 实例 —— 机制、算术与
 * 价钱在 `bridge/PiGuestImageTransformer.kt` 的类注释里，那条也是这次「滑动卡」的修复。
 */
@Composable
private fun PiTable(model: MarkdownComponentModel) {
    val dimens = LocalMarkdownDimens.current
    val density = LocalDensity.current
    // 单元格**内容**宽 = 列宽 − 两侧内边距。这个数与横向滚动状态无关：
    // `MarkdownTableHeader`/`MarkdownTableRow` 的行是 `Modifier.widthIn(tableWidth)`，
    // 每格是 `Modifier.padding(tableCellPadding).weight(1f)`（库 0.45.0
    // `compose/elements/MarkdownTable.kt:136`、`:141`、`:178`、`:182`），所以格子宽度
    // 恒等于 `tableCellWidth/列数 × 列数` —— 也就是 `tableCellWidth`。正文那份
    // `LocalMarkdownDimens` 只在这里读一次，`tableCellPadding` 是本 App 覆盖过的值
    // （8 dp，v2 的 `padding:6px 8px`，见 `PiMarkdownTheme.kt`）。
    val cellWidthPx = with(density) {
        (dimens.tableCellWidth - dimens.tableCellPadding * 2).roundToPx()
    }.coerceAtLeast(1)
    // 表格里换一个**同一个类**的 transformer 实例：它把"画多宽"钉成上面这个数，
    // 于是 (a) 缩略图按单元格宽解码（不是窗口的 1080 px），(b) 占位框只由宽度决定
    // （不再是"容器高度 = 这一格自己"那个反馈环）。两个理由都在
    // `bridge/PiGuestImageTransformer.kt` 的类注释里，连同代价。
    val transformer = rememberPiGuestImageTransformer(cellWidthPx)
    CompositionLocalProvider(
        // 库的 `MarkdownTableBasicText` 直接读库自己的 `LocalImageTransformer`
        // （`MarkdownTable.kt:232`），单元格里的 `![]()` 因此按上面那个框画；
        // 我们自己的 `LocalPiImageTransformer` 也一起换，两个读它的槽
        // （`PiImagePlaceholder` / `PiInlineImage`）才不会与库看到两份答案。
        LocalImageTransformer provides transformer,
        LocalPiImageTransformer provides transformer,
    ) {
        MarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            headerBlock = { content, header, tableWidth, style ->
                MarkdownTableHeader(content, header, tableWidth, style, maxLines = TABLE_MAX_LINES)
            },
            rowBlock = { content, row, tableWidth, style ->
                MarkdownTableRow(content, row, tableWidth, style, maxLines = TABLE_MAX_LINES)
            },
        )
    }
}

/**
 * 一格最多画几行，表格路径上这个数的**唯一**来源。
 *
 * 选 50 的完整理由（为什么不是 1、为什么不是 `Int.MAX_VALUE`，以及它换来的上界是什么）
 * 写在 [PiTable] 的 KDoc 里；这里只留结论：大到任何真实单元格都碰不到，小到病态单元格
 * 有上界。改这个数只改这里，`MarkdownTableHeader`、`MarkdownTableRow` 与它们转交下去的
 * `MarkdownTableBasicText` 会一起变。
 */
private const val TABLE_MAX_LINES = 50

@Composable
private fun PiCodeFence(model: MarkdownComponentModel) {
    MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
        PiCodeSurface(code, language, style)
    }
}

@Composable
private fun PiCodeBlock(model: MarkdownComponentModel) {
    MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
        PiCodeSurface(code, language, style)
    }
}

/**
 * One code block: pi's two rendering branches, on a phone.
 *
 * pi decides **once**, from the fence's language, and the decision is
 * highlight.js's own answer (`supportsLanguage`, `theme.ts:1080`) — not "did the
 * fence name something". [PiHighlightedCode.languageKnown] carries that answer and
 * picks the base colour:
 *
 *  - **known** → the engine coloured what it could, and every character it did not
 *    wrap keeps `text`, which is what a terminal's default foreground does for pi
 *    (`theme.ts:1186-1205`; the HTML export says it twice, `template.css:959` and
 *    `:906-909`);
 *  - **not known** → pi paints the body itself in `mdCodeBlock` (`theme.ts:1085`),
 *    which is also this app's answer when the engine cannot be reached at all.
 *
 * The chrome follows pi as far as a phone can: the fence's info string is printed
 * in `mdCodeBlockBorder` (pi prints it as the opening fence line,
 * `packages/tui/src/components/markdown.ts:522-535`), there is **no** background
 * (pi has none — `template.css:900-909`), and the border that replaces pi's fence
 * line keeps that same token.
 *
 * A ` ```mermaid ` fence never reaches any of that: pi replaces it with
 * `grok-mermaid`'s art before the code renderer sees it
 * (`components/mermaid.ts:60-88`), so the art is drawn instead — as inline code,
 * which is why the fence's label and border are gone with it.
 */
@Composable
private fun PiCodeSurface(code: String, language: String?, style: TextStyle) {
    val palette = PiTheme.palette
    val normalized = remember(language) { PiCodeLanguage.normalize(language) }
    val mermaid = rememberPiMermaidArt(code, language)
    // pi's rule for an incomplete drawing, and it is **not** "show it anyway": when the
    // diagram is settled and `grok-mermaid` reported warnings, pi keeps the fence's
    // *source* and puts the warning underneath (`components/mermaid.ts:77-82`). The art
    // is deliberately dropped — a cross-checked partial diagram is more misleading than
    // the diagram's own code. "Settled" is what the producer already waited for.
    val art = mermaid?.takeIf { it.warnings.isEmpty() }
    val mermaidWarning = mermaid?.let { piMermaidWarning(it) }
    if (art != null) {
        Text(
            text = piMermaidText(art, palette),
            // pi hands the art back as inline code, so an unclassed run is
            // `mdCode`; the class colours sit on top of it — see `PiMermaid.kt`.
            style = style.copy(color = palette.mdCode),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    if (art == null) {
        val highlighted = rememberPiHighlightedCode(code, normalized)
        MarkdownCodeBackground(
            color = Color.Transparent,
            // 预设的代码块圆角：经典 12（`06 §2`，也是这个值一直以来的数），其余三档 10
            // （定稿方向「圆角统一 10」；见 `PiTypographyProfile.codeBlockRadius`）。
            // `MarkdownDimens.codeBackgroundCornerSize` 是同一个数的另一处读者，
            // 两者都由预设决定，不会各写各的。
            shape = RoundedCornerShape(PiTheme.typography.codeBlockRadius),
            border = BorderStroke(1.dp, palette.mdCodeBlockBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (mermaidWarning != null) 8.dp else 0.dp, bottom = 8.dp),
            // The library's header reads `MarkdownColors.text`, which is the body colour
            // — pi prints the fence's info string in `mdCodeBlockBorder`, so the header
            // is ours ([PiCodeHeader]) and the library's is off. `language`/`code` are
            // still passed: the library uses them for the block's accessibility label,
            // which has nothing to do with the top bar.
            showHeader = false,
            language = normalized.orEmpty(),
            code = code,
        ) {
            Column {
                PiCodeHeader(language = normalized, code = code, palette = palette)
                Text(
                    text = highlighted.text,
                    style = style.copy(
                        color = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock,
                    ),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
    // pi's warning line, in pi's `warning` token and after the source it belongs to.
    mermaidWarning?.let { warning ->
        Text(
            text = warning,
            style = MaterialTheme.typography.labelMedium,
            color = palette.warning,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        )
    }
}

/**
 * The fence's info string and a copy affordance, both in pi's token for that line.
 *
 * pi prints ` ```<lang> ` in `mdCodeBlockBorder` (`components/markdown.ts:522`,
 * `:535`). A card is not a terminal line, so the backticks are not drawn — but the
 * label's colour is pi's, and so is the copy affordance's, which has no counterpart
 * in pi at all and therefore must not bring a colour of its own. A fence with no
 * language shows no label, exactly as pi's line would be a bare fence.
 */
@Composable
private fun PiCodeHeader(language: String?, code: String, palette: PiPalette) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.orEmpty(),
            modifier = Modifier.weight(1f, fill = false),
            // The fence's info string is the machine's own identifier (`kotlin`, `bash`,
            // `json`) and it labels a body that is entirely monospace — `06 §3` 构件 13
            // gives CodeBlock「card 底 + 1px mdCodeBlockBorder + 圆角 12 + 语法令牌着色」
            // over a mono body. Rule #7 (`docs/pi-android-ui-spec.md` §1) puts the
            // identifier on the machine face; `labelMedium` was the UI face, so the
            // label disagreed with the block it labels. 「复制」, the affordance beside
            // it, stays in the UI face on purpose: it is our word, not the machine's.
            style = PiTheme.text.monoSmall,
            color = palette.mdCodeBlockBorder,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "复制",
            style = MaterialTheme.typography.labelMedium,
            color = palette.mdCodeBlockBorder,
            modifier = Modifier
                .clickable(onClickLabel = "复制代码") { clipboard.setText(AnnotatedString(code)) }
                .padding(start = 12.dp),
        )
    }
}

/**
 * A code body ready to draw: the source with the engine's spans applied, plus the
 * engine's answer to the one question that decides the base colour.
 *
 * The two travel together because they come from the same request —
 * [languageKnown] *is* [PiCodeHighlight.languageKnown] out of that request, and the
 * text is that same answer already applied. Splitting them into two pieces of state
 * would let a renderer paint a coloured block with the uncoloured branch's base (or
 * the reverse) for one frame.
 */
internal data class PiHighlightedCode(val text: AnnotatedString, val languageKnown: Boolean)

/**
 * Highlighting runs off the main thread and only when the fence named something:
 * pi skips auto-detection entirely (`theme.ts:1078-1086`), so a fence with no info
 * string costs nothing here either.
 *
 * Note the question this asks is only "did the fence name a language" — the
 * *answer* to "does highlight.js know it" comes back with the spans, because only
 * the engine can answer it ([PiCodeHighlight.languageKnown]). A named language the
 * engine does not know is a normal, cached, definitive answer, not a failure.
 *
 * **Streaming fences are debounced; finished ones are not.** A fence a model is
 * still writing changes on every token, so its producer waits [STREAM_SETTLE_MS]
 * before asking, and each change cancels the pending wait — only text that has
 * stopped changing is ever highlighted, and a long answer does not fire one
 * request per token. The wait is skipped for the first version a given block
 * shows, which is the case that matters for a transcript scrolled back into
 * view: those blocks are already final and should colour immediately.
 *
 * The spans are computed once per (code, language, highlighter) and the colours
 * applied separately, so switching theme repaints without re-running a
 * highlighter that may be expensive.
 *
 * `internal` rather than private because pi highlights *file* bodies with the same
 * backend, not only fences: `read` and `write` resolve a language from the path
 * (`core/tools/renderers/read.ts:126-127`, `write.ts:111-114`), and the app's
 * `ReadBlock`/`WriteBlock` ask for exactly that text. Those callers pass a settled body
 * (a `read` result arrives whole) and a language from `PiCodeLanguage.forPath`, so they
 * inherit both of this function's obligations rather than duplicating them.
 */
@Composable
internal fun rememberPiHighlightedCode(code: String, language: String?): PiHighlightedCode {
    val palette = PiTheme.palette
    val highlighter = LocalPiCodeHighlighter.current
    // A one-element array rather than state on purpose: this is a marker for the
    // producer, not something the UI reads, so it must not participate in
    // snapshot invalidation. Not keyed on `code` either — it exists to tell "this
    // block changed (streaming)" apart from "this block just appeared (final)".
    val hasStreamed = remember { booleanArrayOf(false) }
    val answer = produceState(
        initialValue = PiCodeHighlight(),
        code,
        language,
        highlighter,
    ) {
        value = if (PiCodeLanguage.isUnspecified(language)) {
            PiCodeHighlight()
        } else {
            val isUpdate = hasStreamed[0]
            hasStreamed[0] = true
            if (isUpdate) delay(STREAM_SETTLE_MS)
            withContext(Dispatchers.Default) { highlighter.highlight(code, language) }
        }
    }
    return remember(code, answer.value, palette) {
        PiHighlightedCode(
            text = buildPiCodeText(code, answer.value.spans, palette),
            languageKnown = answer.value.languageKnown,
        )
    }
}

/** The one fence language pi's mermaid transformer claims (`components/mermaid.ts:15`). */
private const val MERMAID_LANGUAGE = "mermaid"

private val WHITESPACE = Regex("\\s+")

/**
 * `grok-mermaid`'s art for a ` ```mermaid ` fence, or `null` for everything else —
 * including every case where the art cannot be had.
 *
 * pi runs its mermaid transformer *before* the code-block renderer and only for that
 * one language (`components/mermaid.ts:14-16`, `:60-88`), which is why this returns
 * immediately for any other fence. `null` is pi's own "no art to show": the caller
 * draws the fence's source instead, exactly as pi keeps `token.raw` (`:75-76`).
 *
 * **pi's mode gate is honoured here** ([PiMermaidMode], from `markdown.mermaid`):
 * [PiMermaidMode.Off] never asks, so the fence stays source — pi's
 * `mode === "off"` branch (`components/mermaid.ts:64-65`).
 *
 * **Cold start, and why this retries.** The guest imports `grok-mermaid` — a real
 * layout engine — on the first `/mermaid` request, and that import can outlast the
 * socket's 150 ms read budget. A single-shot producer therefore answered "no art" for
 * the *first* diagram on screen and only drew it once something recycled the row (the
 * reported "画完了不显示，屏幕必须晃动、滑动才画出来": scrolling the `LazyColumn`
 * disposed and rebuilt this component, whose `produceState` then ran again against a
 * warm engine). [PiMermaidReply] tells the two failures apart, so this retries only
 * [PiMermaidReply.Unavailable] — up to [MERMAID_ATTEMPTS] asks, [MERMAID_RETRY_MS]
 * apart — and never retries [PiMermaidReply.NoArt], which is a definitive answer.
 * Cancellation is free: a changed key or a disposed row cancels this coroutine,
 * including the waits. **Worst case per fence**: 3 requests and ≈1.6 s of a background
 * coroutine (3 × 250 ms of socket budget + 2 × 400 ms of waiting), then it stops — no
 * timer survives it, and a transcript with F mermaid fences never exceeds 3·F requests.
 *
 * **缓存（R3）：滚回来不再问 guest。** `PiNodeMermaidRenderer` 现在带着一个进程级的
 * 有界 LRU（键 = `(code, mode)`），所以同一段源码第二次进入组合时这次询问是 map 命中：
 * 不再出网、不再重跑 guest 的布局引擎、也不会「先闪源码再变成图」。命中时
 * `produceState` 的 `initialValue` 直接就是那张图，且 producer 立即返回——连
 * [STREAM_SETTLE_MS] 都不再等：等待是为了「别画半张图」，而命中的意思是这份源码已经被
 * 完整画过，同一个 `(code, mode)` 出的就是同一张图。失败（`Unavailable`）不进缓存，
 * 所以上面那 3 次重试在冷引擎下仍然是 3 次；`NoArt` 是定论，会进缓存。
 *
 * **`Final` waits for the fence even the first time.** pi's gate there is
 * `context.isStreaming` (`:66-67`), which this component cannot see — `PiMarkdownText`
 * receives markdown, not the message's state, and adding a parameter to it is outside
 * this change. So `Final` treats every ask as "possibly still streaming" and settles
 * first; the cost is [STREAM_SETTLE_MS] before a final diagram appears, and the benefit
 * is that a mid-stream partial diagram is not drawn. `Streaming` keeps pi's behaviour
 * of drawing during the stream (as far as a debounced producer can).
 *
 * The language test is pi's, word for word: the **first whitespace-separated token**
 * of the info string, lower-cased. That is not the same rule the highlighter uses —
 * pi hands the whole info string to highlight.js — and the difference is pi's own:
 * ` ```mermaid x ` is drawn as a diagram, ` ```js x ` is not highlighted at all.
 *
 * **pi's `assistant-thinking` gate is satisfied structurally, not by a check.** pi
 * refuses to transform inside a thinking message (`components/mermaid.ts:65`). In this
 * app a thinking body is drawn by `ThinkingBlockBlock` as one plain `Text` of
 * `item.text` in italic prose (`ui/blocks/ThinkingBlockBlock.kt:79-87`) — it never
 * enters the markdown renderer, so no code-fence component runs there and a
 * ` ```mermaid ` fence inside a thinking block stays literal text, which is what pi
 * shows too.
 */
@Composable
private fun rememberPiMermaidArt(code: String, language: String?): PiMermaidArt? {
    val mode = rememberPiMermaidMode()
    // pi: `mode === "off"` returns the markdown untouched, i.e. no transform at all.
    if (mode == PiMermaidMode.Off) return null
    val isMermaid = remember(language) {
        language?.trim()?.split(WHITESPACE)?.firstOrNull()?.lowercase() == MERMAID_LANGUAGE
    }
    if (!isMermaid) return null
    val hasStreamed = remember { booleanArrayOf(false) }
    // 缓存同步读一次，键 = (code, mode)，与 memo 的键一致。命中时首帧就是图（不再先闪
    // 一次源码），producer 也不再问 guest —— 这正是「滚出去再滚回来」不再重跑布局引擎的
    // 那一步。只读、无副作用，所以可以在组合期做。
    val cached = remember(code, mode) { PiNodeMermaidRenderer.cached(code, mode) }
    val art = produceState<PiMermaidArt?>(initialValue = (cached as? PiMermaidReply.Art)?.art, code, language, mode) {
        // 有定论了（画出图，或 grok-mermaid 明确拒绝）就不再问；null 表示没问过。
        // 跳过下面的 settle：等待是为了别画半张图，而命中说明这份源码已经完整画过。
        if (cached != null) return@produceState
        val isUpdate = hasStreamed[0]
        hasStreamed[0] = true
        if (isUpdate || mode == PiMermaidMode.Final) delay(STREAM_SETTLE_MS)
        var attempt = 0
        while (true) {
            when (val reply = withContext(Dispatchers.Default) { PiNodeMermaidRenderer.render(code, mode) }) {
                is PiMermaidReply.Art -> {
                    value = reply.art
                    return@produceState
                }
                PiMermaidReply.NoArt -> {
                    value = null
                    return@produceState
                }
                PiMermaidReply.Unavailable -> {
                    attempt++
                    if (attempt >= MERMAID_ATTEMPTS) {
                        value = null
                        return@produceState
                    }
                    delay(MERMAID_RETRY_MS)
                }
            }
        }
    }
    return art.value
}

/** How many asks one fence gets while the guest is still importing `grok-mermaid`. */
private const val MERMAID_ATTEMPTS = 3

/** The pause between those asks; long enough for a cold ESM import to land. */
private const val MERMAID_RETRY_MS = 400L

/** pi's key, verbatim: `settings.markdown.mermaid` (`settings-manager.ts:66`). */
private const val MERMAID_SETTING_KEY = "markdown.mermaid"

/**
 * `markdown.mermaid` for this composition.
 *
 * pi reads the mode per markdown render (`components/mermaid.ts:62`) from its own
 * settings object. This app has no settings object in the render tree — the store is
 * passed explicitly to the Settings screens and to the terminal — so the value is read
 * from **the same file-backed store those screens write**
 * (`PiSettingsFileStore.forWorkspace`, exactly as `terminalSettingsStore` builds it:
 * `PiPaths` for the agent dir, `PtyLauncher.workspaceHost` for the workspace), and
 * cached in [PiMermaidModeSource] so that a transcript with twenty code fences does not
 * parse `settings.json` twenty times — once per fence *and again* every time a recycled
 * row recomposes.
 *
 * The cache is invalidated by the settings documents' stamp (mtime + size), which is two
 * `stat` calls per ask: cheap, no timer, and it means flipping the setting is picked up
 * by the next composition instead of needing an app restart.
 */
@Composable
private fun rememberPiMermaidMode(): PiMermaidMode {
    val context = LocalContext.current
    return remember(context) { PiMermaidModeSource.mode(context) }
}

/**
 * The process-wide `markdown.mermaid` cache behind [rememberPiMermaidMode].
 *
 * Deliberately a process-wide singleton rather than composition state: the value is
 * asked for once per code fence, the store's own caches are per instance, and building a
 * store per fence would re-read (and re-parse) `settings.json` per fence — which is
 * exactly the kind of quiet per-row I/O this app avoids elsewhere.
 */
private object PiMermaidModeSource {

    private class Cached(
        val globalFile: File,
        val projectFile: File,
        val stamp: String,
        val mode: PiMermaidMode,
    )

    private val lock = Any()
    private var cached: Cached? = null

    fun mode(context: Context): PiMermaidMode {
        val fresh = cached
        if (fresh != null && stampOf(fresh) == fresh.stamp) return fresh.mode
        synchronized(lock) {
            cached?.let { if (stampOf(it) == it.stamp) return it.mode }
            val app = context.applicationContext ?: context
            val paths = PiPaths(
                filesDir = app.filesDir,
                nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
            )
            val workspace = PtyLauncher.workspaceHost(app)
            val globalFile = File(paths.agentDir, "settings.json")
            val projectFile = PiProjectConfig.settingsFile(workspace)
            val store = PiSettingsFileStore.forWorkspace(agentDir = paths.agentDir, workspace = workspace)
            val mode = piMermaidModeOf(runCatching { store.readString(MERMAID_SETTING_KEY) }.getOrNull())
            cached = Cached(globalFile, projectFile, stampOf(globalFile, projectFile), mode)
            return mode
        }
    }

    private fun stampOf(entry: Cached): String = stampOf(entry.globalFile, entry.projectFile)

    private fun stampOf(globalFile: File, projectFile: File): String =
        "${globalFile.lastModified()}:${globalFile.length()}|${projectFile.lastModified()}:${projectFile.length()}"
}

/**
 * pi's accepted values, and pi's default for everything else.
 *
 * `"streaming"` is the documented default (`settings-manager.ts:66`), so an unset key —
 * or a value pi could not have written — resolves to it. Defaulting to [PiMermaidMode.Off]
 * would look safer and would silently disable a feature the user never turned off.
 */
private fun piMermaidModeOf(raw: String?): PiMermaidMode = when (raw?.trim()?.lowercase()) {
    "off" -> PiMermaidMode.Off
    "final" -> PiMermaidMode.Final
    else -> PiMermaidMode.Streaming
}

/**
 * How long a fence must stop changing before it is worth highlighting. The eval
 * doc suggests 150–250 ms (§4.3); this is the top of that range because the
 * request itself costs time and a settled block is worth more than a fast colour
 * on a block that is still being rewritten.
 */
private const val STREAM_SETTLE_MS = 200L

/**
 * Applies spans to the source text. Offsets are clamped rather than trusted: a
 * highlighter that returns a bad range must produce a plain block, not a crash
 * in the middle of a conversation.
 */
private fun buildPiCodeText(
    code: String,
    spans: List<PiCodeSpan>,
    palette: PiPalette,
): AnnotatedString = buildAnnotatedString {
    append(code)
    for (span in spans) {
        val start = span.start.coerceIn(0, code.length)
        val end = span.end.coerceIn(start, code.length)
        if (start == end) continue
        val style = when (span.token) {
            PiSyntaxToken.Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
            PiSyntaxToken.Strong -> SpanStyle(fontWeight = FontWeight.Bold)
            PiSyntaxToken.Link -> SpanStyle(textDecoration = TextDecoration.Underline)
            else -> span.token.color(palette)?.let { SpanStyle(color = it) } ?: SpanStyle()
        }
        addStyle(style, start, end)
    }
}
