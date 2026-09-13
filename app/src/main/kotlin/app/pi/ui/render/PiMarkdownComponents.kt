package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.highlight.PiNodeMermaidRenderer
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * The renderer's component set: pi's code-block chrome, pi's math, and the
 * image fallback.
 *
 * Everything except those three keeps the library default, because the default
 * reads its colours and type from the [com.mikepenz.markdown.model.MarkdownColors]
 * and [com.mikepenz.markdown.model.MarkdownTypography] built in
 * `PiMarkdownTheme.kt` — which are pi's tokens. Overriding a component that
 * already renders in pi's colours would only be a chance to get it wrong.
 *
 * Code blocks are the first exception: pi draws a border in `mdCodeBlockBorder`
 * around every fence and prints the fence language above it. The library's
 * default code block has a background but no border, so the border is added.
 *
 * Math is the second; [piMathComponent] explains why it claims only two element
 * types.
 *
 * Images are the third, and they take **two** slots, because the library splits the
 * surface: a block image reaches `image` ([PiImagePlaceholder]), one written inside a
 * paragraph reaches `inlineImage` ([PiInlineImage]). Real bytes now arrive through
 * `bridge/PiGuestImageTransformer.kt` (installed by `PiMarkdown.kt`), so both components
 * draw them and both keep saying "an image was here, and here is where it pointed" when
 * the link cannot be resolved — the library's own inline default would draw nothing at
 * all there. See each component's comment for why the decision is made on
 * `transform`'s *result* rather than on the transformer's type.
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
    custom = { type, model -> piMathComponent(type, model) },
)

/**
 * pi's math nodes.
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
 * exactly what this function does — a `when` with no `else` branch. It returns
 * for the two node types it was written for and does nothing for everything
 * else, leaving the dispatch's own conclusion alone rather than trying to
 * re-implement the recursion it cannot reach.
 *
 * In normal rendering this function is in fact unreachable for math, because
 * [piMarkdownSource] rewrites `$...$` / `$$...$$` before the parser runs; see
 * [PiMarkdownText]. It is kept because it is the AST-level statement of what
 * this app supports, and because the renderer's dispatch reaches it for *any*
 * node build it is handed — including a partial document, which is what every
 * streaming token produces — so a math node that survives to a component call
 * must land here rather than silently vanishing.
 *
 * (The library also ships a streaming parser that keeps a stable AST prefix and
 * re-parses only its tail — `StreamingMarkdownState`, `model/StreamingMarkdownState.kt`
 * in 0.45.0 — but this app does not use it: it is append-only (`append(chunk)`)
 * while the App's transcript carries the accumulated text, so adopting it needs a
 * delta channel out of the reducer first. See `docs/streaming-review.md` §4.2.)
 *
 * A node that reaches this point is rendered as the formula's own source in
 * `mdCode`, which is the phone's equivalent of pi's pending formula
 * (`packages/tui/src/components/markdown.ts:508`): unmistakably a formula,
 * visibly not typeset.
 */
@Composable
private fun piMathComponent(type: org.intellij.markdown.IElementType, model: MarkdownComponentModel) {
    when (type) {
        GFMElementTypes.INLINE_MATH -> PiFormulaText(model, block = false)
        GFMElementTypes.BLOCK_MATH -> PiFormulaText(model, block = true)
    }
}

/** One un-rewritten formula, in pi's "shown as written" style. */
@Composable
private fun PiFormulaText(model: MarkdownComponentModel, block: Boolean) {
    val palette = PiTheme.palette
    val text = remember(model.content, model.node) {
        PiLatex.formulaText(
            content = model.content,
            text = model.content.substring(model.node.startOffset, model.node.endOffset),
            block = block,
            fallback = "（公式）",
        )
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (block) 6.dp else 2.dp),
        style = model.typography.code,
        color = palette.mdCode,
        textAlign = if (block) TextAlign.Center else TextAlign.Start,
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
 * decodes `data:` URIs and maps `file:`/bare paths from the guest's spelling to the host
 * file. `PiMarkdown.kt` installs it through [LocalPiImageTransformer] *and* through
 * `Markdown(imageTransformer = …)`, so both this component and the library's own image
 * components see it.
 *
 * **Why the decision is made on the result, not on the transformer's type.** The
 * library drops the whole node when `transform` returns `null` — `MarkdownImage` reads
 * `LocalImageTransformer.current.transform(link)?.let { … }`
 * (`.../compose/elements/MarkdownImage.kt:17-29`), so a `null` means the image vanishes
 * from the transcript with no trace. Asking *first* and keeping the alt + source text
 * for a `null` is what keeps a link that cannot be resolved — `http(s)` is refused by
 * design (`bridge/GuestImageBytes.kt`, "What it deliberately does not resolve"), a file
 * may be missing, a body may exceed the size cap, and the first frame of any real load
 * is still empty — strictly better than the no-op default's silence.
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
        // Bytes in hand; let the library draw them (it reuses the same decoded bitmap).
        MarkdownImage(content, node)
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
        MarkdownInlineImage(link, model.node)
        return
    }
    PiImageFallback(alt = null, link = link, typography = model.typography)
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
    if (mermaid != null) {
        Text(
            text = piMermaidText(mermaid, palette),
            // pi hands the art back as inline code, so an unclassed run is
            // `mdCode`; the class colours sit on top of it — see `PiMermaid.kt`.
            style = style.copy(color = palette.mdCode),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        piMermaidWarning(mermaid)?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelMedium,
                color = palette.warning,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
        return
    }
    val highlighted = rememberPiHighlightedCode(code, normalized)
    MarkdownCodeBackground(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.mdCodeBlockBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
            style = MaterialTheme.typography.labelMedium,
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
 * immediately for any other fence. The request has the same shape as the
 * highlighter's — off the main thread, debounced while the fence is still changing,
 * and never able to throw — and `null` is pi's own "no art to show": the caller
 * draws the fence's source instead, exactly as pi keeps `token.raw` (`:75-76`).
 *
 * Two of pi's conditions are deliberately not reproduced, because this app cannot
 * see what they depend on: pi skips mermaid inside an assistant *thinking* block and
 * while streaming unless the setting allows it (`:63-69`). A settled transcript has
 * neither state to read here, and a streaming fence is debounced anyway.
 *
 * The language test is pi's, word for word: the **first whitespace-separated token**
 * of the info string, lower-cased. That is not the same rule the highlighter uses —
 * pi hands the whole info string to highlight.js — and the difference is pi's own:
 * ` ```mermaid x ` is drawn as a diagram, ` ```js x ` is not highlighted at all.
 */
@Composable
private fun rememberPiMermaidArt(code: String, language: String?): PiMermaidArt? {
    val isMermaid = remember(language) {
        language?.trim()?.split(WHITESPACE)?.firstOrNull()?.lowercase() == MERMAID_LANGUAGE
    }
    if (!isMermaid) return null
    val hasStreamed = remember { booleanArrayOf(false) }
    val art = produceState<PiMermaidArt?>(initialValue = null, code, language) {
        val isUpdate = hasStreamed[0]
        hasStreamed[0] = true
        if (isUpdate) delay(STREAM_SETTLE_MS)
        value = withContext(Dispatchers.Default) { PiNodeMermaidRenderer.render(code) }
    }
    return art.value
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
