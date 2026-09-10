package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

/** Images are the renderer's other pluggable seam; same default as upstream. */
internal val LocalPiImageTransformer = staticCompositionLocalOf<ImageTransformer> {
    NoOpImageTransformerImpl()
}

/**
 * The renderer's component set, with pi's code-block chrome.
 *
 * Everything except code blocks keeps the library default, because the default
 * reads its colours and type from the [com.mikepenz.markdown.model.MarkdownColors]
 * and [com.mikepenz.markdown.model.MarkdownTypography] built in
 * `PiMarkdownTheme.kt` — which are pi's tokens. Overriding a component that
 * already renders in pi's colours would only be a chance to get it wrong.
 *
 * Code blocks are the exception: pi draws a border in `mdCodeBlockBorder` around
 * every fence and prints the fence language above it. The library's default code
 * block has a background but no border, so the border is added here.
 */
@Composable
internal fun piMarkdownComponents(): MarkdownComponents = markdownComponents(
    codeFence = { model -> PiCodeFence(model) },
    codeBlock = { model -> PiCodeBlock(model) },
)

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
 * The producer waits [STREAM_SETTLE_MS] before asking. A fence that a model is
 * still writing changes on every token, and each change cancels this coroutine
 * and starts a new one — so the delay means a block is only ever highlighted
 * once it has stopped changing, and a long answer does not fire a request per
 * token. The cost is one deliberate pause before colour appears on a finished
 * block; the alternative is streaming text that stutters behind a queue of
 * highlight requests for text nobody will ever see.
 *
 * The spans are computed once per (code, language, highlighter) and the colours
 * applied separately, so switching theme repaints without re-running a
 * highlighter that may be expensive.
 */
@Composable
private fun rememberPiHighlightedCode(code: String, language: String?): AnnotatedString {
    val palette = PiTheme.palette
    val highlighter = LocalPiCodeHighlighter.current
    val spans = produceState(
        initialValue = emptyList<PiCodeSpan>(),
        code,
        language,
        highlighter,
    ) {
        value = if (PiCodeLanguage.isPlaintext(language)) {
            emptyList()
        } else {
            delay(STREAM_SETTLE_MS)
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
 * request itself is measurable and a settled block is worth more than a fast
 * colour on a block that is still being rewritten.
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
