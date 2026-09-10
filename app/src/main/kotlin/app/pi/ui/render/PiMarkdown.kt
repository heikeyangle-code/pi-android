package app.pi.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.highlight.PiNodeCodeHighlighter
import com.mikepenz.markdown.m3.Markdown

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
 * @param markdown the source text, exactly as the engine emitted it. Streaming,
 *   half-finished markdown is expected and handled: the parser is given the
 *   partial document, and an unterminated fence renders as an open code block
 *   rather than as an error — the same thing pi does while a model is typing.
 *   A fence that is still being written is not highlighted until it settles; see
 *   `rememberPiHighlightedCode`.
 */
@Composable
internal fun PiMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // `remember(context)`: attach() is idempotent and cheap, but this keeps it to
    // once per Activity rather than once per composition.
    remember(context) { PiNodeCodeHighlighter.attach(context) }
    CompositionLocalProvider(
        LocalPiCodeHighlighter provides PiNodeCodeHighlighter,
        LocalPiImageTransformer provides com.mikepenz.markdown.model.NoOpImageTransformerImpl(),
    ) {
        Markdown(
            content = markdown,
            colors = piMarkdownColors(),
            typography = piMarkdownTypography(),
            padding = piMarkdownPadding(),
            dimens = piMarkdownDimens(),
            components = piMarkdownComponents(),
            modifier = modifier,
        )
    }
}
