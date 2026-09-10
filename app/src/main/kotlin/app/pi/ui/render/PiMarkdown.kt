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
 * pi's tokenizers are `marked` extensions
 * (`packages/tui/src/components/markdown.ts:123-172`), which means they run
 * **after** marked's own rules have already claimed code spans and fenced code:
 * a `$` inside either is never seen by `tokenizeInlineLatex`. Reproducing that
 * here means the scan has to skip code explicitly, which is what the fence and
 * inline-code states in [piMarkdownSource] do. Without them `a `$y$` b` would be
 * rewritten inside its own code span — a formula substitution the renderer would
 * then display as literal code.
 *
 * Order also matters, and it mirrors marked: **block first, inline second**. A
 * block token consumes its whole `$$...$$` run before any inline rule can look
 * inside it, so the inline pass never sees the body of a display formula. Doing
 * it the other way round would eat `$$` as two adjacent inline formulas.
 *
 * pi's tokenizer additionally refuses to open on a delimiter that follows a word
 * character (`markdown.ts:52-99`, `isEscaped` plus the boundary checks), which
 * is what keeps prose like `costs $5 and $10` out of the math path. That rule is
 * a lookbehind here.
 *
 * Anything [PiLatex] cannot reduce to Unicode is left exactly as written —
 * `latexToken.raw` is pi's own fallback when `renderLatex` returns `undefined`
 * (`markdown.ts:509` and `:649`).
 */
internal fun piMarkdownSource(markdown: String): String {
    // Fast path: no delimiter, nothing to rewrite. This is the common case for
    // a streaming transcript.
    if (!markdown.contains('$')) return markdown
    val out = StringBuilder(markdown.length)
    var index = 0
    while (index < markdown.length) {
        val fence = FENCE.find(markdown, index)
        if (fence != null && fence.range.first == index) {
            // A fenced block: copy up to the closing fence of the same
            // character and at least the same length, so a shorter inner run of
            // backticks cannot end it early.
            out.append(fence.value)
            val closing = "\n" + fence.groupValues[1]
            val end = markdown.indexOf(closing, fence.range.last + 1)
            if (end < 0) return out.append(markdown, fence.range.last + 1, markdown.length).toString()
            out.append(markdown, fence.range.last + 1, end + closing.length)
            index = end + closing.length
            continue
        }
        val code = INLINE_CODE.find(markdown, index)
        if (code != null && code.range.first == index) {
            out.append(code.value)
            index = code.range.last + 1
            continue
        }
        val start = when {
            fence == null -> code?.range?.first ?: markdown.length
            code == null -> fence.range.first
            else -> minOf(fence.range.first, code.range.first)
        }
        out.append(applyMath(markdown, index, start))
        index = start
    }
    return out.toString()
}

/** Math substitution for one code-free run `[start, end)`. */
private fun applyMath(text: String, start: Int, end: Int): String {
    val run = text.substring(start, end)
    if (!run.contains('$')) return run
    val block = BLOCK_MATH.replace(run) { match ->
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
 * An opening code fence at the current position: up to three spaces, then three
 * or more backticks or tildes, as CommonMark specifies. The info string is not
 * captured — the closing fence only needs the marker itself.
 */
private val FENCE = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\n]*")

/**
 * A code span: a backtick run, then the first run of exactly as many backticks.
 * `org.intellij.markdown` uses the same greedy rule, so the scanner and the
 * parser agree about where a span ends.
 */
private val INLINE_CODE = Regex("(`{1,3})(?:(?!\\1)[\\s\\S])*?\\1")

/**
 * `$$`-delimited display math: pi's `tokenizeBlockLatex`
 * (`packages/tui/src/components/markdown.ts:101-121`), including its rule that
 * the opening `$$` cannot follow a word character and that a backslash escapes
 * the delimiter (`markdown.ts:31-39`, `isEscaped`).
 */
private val BLOCK_MATH = Regex(
    pattern = """(?<![\p{L}\p{N}\\])\$\$([^$]+?)\$\$\n?""",
    option = RegexOption.DOT_MATCHES_ALL,
)

/**
 * `$`-delimited inline math: pi's `tokenizeInlineLatex`
 * (`packages/tui/src/components/markdown.ts:52-99`). The body may not contain a
 * bare `$` or a line break, and may not start or end on whitespace — the same
 * constraints pi's tokenizer enforces, which is why `$` stays ordinary
 * punctuation almost everywhere it appears in prose.
 *
 * The `\p{L}` lookahead after the opening delimiter is the one place this port
 * is deliberately stricter than pi's regex: pi's tokenizer walks the string and
 * checks `looksLikePendingDollarMath` (`markdown.ts:46-48`) before it accepts a
 * non-whitespace body, which is what keeps a sentence like
 * `costs $5 and $10 today` out of the math path. Requiring the first body
 * character not to be a letter or digit is the cheap equivalent, and it cannot
 * hide a real formula: `$\alpha$` opens with a backslash.
 */
private val INLINE_MATH = Regex(
    pattern = """(?<![\p{L}\p{N}\\])\$(?![\s\p{L}\p{N}])([^$\n]+?)(?<!\s)\$(?![\p{L}\p{N}])""",
)

