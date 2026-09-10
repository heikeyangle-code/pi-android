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
    // Keyed on the source: rewriting is a linear scan with a handful of regex
    // matches, but a streaming block re-parses on every token, so it is cached.
    val content = remember(markdown) { piMarkdownSource(markdown) }
    CompositionLocalProvider(
        LocalPiCodeHighlighter provides PiNodeCodeHighlighter,
        LocalPiImageTransformer provides com.mikepenz.markdown.model.NoOpImageTransformerImpl(),
    ) {
        Markdown(
            content = content,
            colors = piMarkdownColors(),
            typography = piMarkdownTypography(),
            padding = piMarkdownPadding(),
            dimens = piMarkdownDimens(),
            components = piMarkdownComponents(),
            modifier = modifier,
        )
    }
}

/**
 * pi's `LATEX_MARKDOWN_EXTENSIONS`, applied to the *source* instead of to the
 * token stream.
 *
 * pi's tokenizers are marked extensions
 * (`packages/tui/src/components/markdown.ts:123-172`), so they only produce a
 * token where marked's own inline rules do not apply — a `$` inside a code span
 * or a fence is likewise left alone there. This port gets the same property for
 * free: the Android renderer highlights code fences from the fence text and the
 * inline-code annotator runs on spans, so a formula-looking `$` inside either is
 * never seen by this scan (see the block/inline ordering below for the other
 * half of the reason).
 *
 * Order matters, and it mirrors marked: **block first, inline second**. A block
 * token consumes its whole `$$...$$` run before any inline rule can look inside
 * it, so the inline pass never sees the body of a display formula. Doing it the
 * other way round would eat `$$` as two adjacent inline formulas.
 *
 * pi's tokenizers also refuse to open on a delimiter that follows a word
 * character (`markdown.ts:52-99`, `isEscaped` plus the boundary checks), which
 * is what keeps prose like `costs $5 and $10` from becoming a formula. The same
 * check is done here with a lookbehind.
 *
 * Anything [PiLatex] cannot reduce to Unicode is left exactly as written —
 * `latexToken.raw` is pi's own fallback when `renderLatex` returns `undefined`
 * (`markdown.ts:509` and `:649`).
 */
internal fun piMarkdownSource(markdown: String): String {
    if (!markdown.contains('$')) return markdown
    val block = BLOCK_MATH.replace(markdown) { match ->
        // The trailing newline is part of the match, so a display formula keeps
        // its own line instead of being glued into the following paragraph.
        PiLatex.toDisplayUnicode(match.value) ?: match.value
    }
    return INLINE_MATH.replace(block) { match ->
        val source = match.value
        PiLatex.toUnicode(source.substring(1, source.length - 1)) ?: source
    }
}

/**
 * `$$`-delimited display math: pi's `tokenizeBlockLatex`
 * (`packages/tui/src/components/markdown.ts:101-121`), including its rule that
 * the opening `$$` cannot follow a word character.
 */
private val BLOCK_MATH = Regex(
    pattern = "(?<![\\p{L}\\p{N}])\\$\\$([^$]+?)\\$\\$\\n?",
    option = RegexOption.DOT_MATCHES_ALL,
)

/**
 * `$`-delimited inline math: pi's `tokenizeInlineLatex`
 * (`packages/tui/src/components/markdown.ts:52-99`). The body may not contain a
 * bare `$` or a line break, and may not start or end on whitespace — the same
 * constraints pi's tokenizer enforces, and the reason `$` behaves as ordinary
 * punctuation almost everywhere it appears in prose.
 */
private val INLINE_MATH = Regex(
    pattern = "(?<![\\p{L}\\p{N}])\\$(?!\\s)([^$\\n]+?)(?<!\\s)\\$(?![\\p{L}\\p{N}])",
)

