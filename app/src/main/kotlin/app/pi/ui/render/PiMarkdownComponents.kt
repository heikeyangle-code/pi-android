package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.model.ImageTransformer
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
 * runs, and whenever the engine is down, it returns no spans and every block
 * renders in `mdCodeBlock` — which is what pi does for an unknown fence. A caller
 * that wants to be sure of that (or to test the renderer without an engine)
 * provides [PiPlainCodeHighlighter] explicitly.
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
 * Images are the third. Real bytes now arrive through
 * `bridge/PiGuestImageTransformer.kt` (installed by `PiMarkdown.kt`), so this
 * component draws them and keeps saying "an image was here, and here is where it
 * pointed" only for a link that could not be resolved. See its comment for why the
 * decision is made on `transform`'s *result* rather than on the transformer's type.
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
 * this app supports, and because the streaming renderer
 * (`StreamingMarkdownState`) renders an unstable AST tail built from the same
 * parse — a math node that ever survives to a component call must land here
 * rather than silently vanishing.
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
    val palette = PiTheme.palette
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
    val alt = remember(content, node) { node.resolveImageAlt(content) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = alt?.takeIf { it.isNotBlank() } ?: "图片",
            style = model.typography.text,
            color = palette.text,
        )
        Text(
            text = link?.takeIf { it.isNotBlank() }?.let { "图片地址：$it（当前无法显示）" }
                ?: "图片地址缺失",
            modifier = Modifier.padding(top = 2.dp),
            style = model.typography.code,
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

@Composable
private fun PiCodeSurface(code: String, language: String?, style: TextStyle) {
    val palette = PiTheme.palette
    val highlighted = rememberPiHighlightedCode(code, PiCodeLanguage.normalize(language))
    MarkdownCodeBackground(
        color = palette.cardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.mdCodeBlockBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        // The header carries the language and a copy button. pi prints the
        // language as the fence's opening line; a copy affordance is the one
        // thing a touch screen can do that a terminal cannot, so it is kept.
        showHeader = true,
        language = language,
        code = code,
    ) {
        Text(
            text = highlighted,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Highlighting runs off the main thread and only when the fence names a
 * language: pi skips auto-detection entirely, so a fence without a language
 * costs nothing here either.
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
 */
@Composable
private fun rememberPiHighlightedCode(code: String, language: String?): AnnotatedString {
    val palette = PiTheme.palette
    val highlighter = LocalPiCodeHighlighter.current
    // A one-element array rather than state on purpose: this is a marker for the
    // producer, not something the UI reads, so it must not participate in
    // snapshot invalidation. Not keyed on `code` either — it exists to tell "this
    // block changed (streaming)" apart from "this block just appeared (final)".
    val hasStreamed = remember { booleanArrayOf(false) }
    val spans = produceState(
        initialValue = emptyList<PiCodeSpan>(),
        code,
        language,
        highlighter,
    ) {
        value = if (PiCodeLanguage.isPlaintext(language)) {
            emptyList()
        } else {
            val isUpdate = hasStreamed[0]
            hasStreamed[0] = true
            if (isUpdate) delay(STREAM_SETTLE_MS)
            withContext(Dispatchers.Default) { highlighter.highlight(code, language) }
        }
    }
    return remember(code, spans.value, palette) {
        buildPiCodeText(code, spans.value, palette)
    }
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
    palette: app.pi.ui.theme.PiPalette,
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
