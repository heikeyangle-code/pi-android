package app.pi.ui.render

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.ui.theme.PiTheme
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
) {
    val context = LocalContext.current
    // `remember(context)`: attach() is idempotent and cheap, but this keeps it to
    // once per Activity rather than once per composition.
    remember(context) { PiNodeCodeHighlighter.attach(context) }
    // Keyed on the source: rewriting is a linear scan with a handful of regex
    // matches, but a streaming block re-parses on every token, so it is cached.
    val content = remember(markdown) { piMarkdownSource(markdown) }
    // F32 / RR-P9: everything the five markdown objects depend on is read here,
    // once per composition, and the objects themselves are built by the pure
    // functions in `PiMarkdownTheme.kt` — the library's own builders are
    // `@Composable` and therefore illegal inside `remember`'s calculation lambda
    // (see that file's header for the failed attempt this replaced). Streaming a
    // long block recomposes this function per token; without these three
    // `remember`s every token rebuilt all five objects and the component set.
    val palette = PiTheme.palette
    val darkTheme = isSystemInDarkTheme()
    val baseText = MaterialTheme.typography.bodyLarge
    val monoText = PiTheme.text.mono
    val colors = remember(palette, darkTheme, textColor) {
        piMarkdownColors(palette, darkTheme, textColor)
    }
    val typography = remember(palette, baseText, monoText, textColor) {
        piMarkdownTypography(palette, baseText, monoText, textColor)
    }
    // `piMarkdownComponents()` is an ordinary function — `markdownComponents(...)`
    // is not composable — so it can be remembered directly. The lambdas it holds
    // are composable, but only *created* here; the library does the same thing in
    // its own non-composable `CurrentComponentsBridge` (`.../components/MarkdownComponents.kt`).
    val components = remember { piMarkdownComponents() }
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
    // `http(s)` links deliberately resolve to `null` (`bridge/GuestImageBytes.kt`,
    // "What it deliberately does not resolve"): pi never fetches an image, and a
    // silent request from composition would leak the user's IP. Those links keep the
    // alt + source fallback.
    val imageTransformer = rememberPiGuestImageTransformer()
    CompositionLocalProvider(
        LocalPiCodeHighlighter provides PiNodeCodeHighlighter,
        LocalPiImageTransformer provides imageTransformer,
    ) {
        Markdown(
            content = content,
            colors = colors,
            typography = typography,
            padding = piMarkdownPadding,
            dimens = piMarkdownDimens,
            imageTransformer = imageTransformer,
            components = components,
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
 * The body is `[^\s$][^$\n]*?`: non-empty, not opening on whitespace, not
 * containing a bare `$`, not containing a line break. `(?!\d)` after the opening
 * delimiter is the other half of pi's rule (`markdown.ts:46-48`,
 * `looksLikePendingDollarMath`): a body that opens on a digit is a price, not a
 * formula, which is what keeps `costs $5 and $10 today` out of the math path.
 * Letters are allowed to open a body — `$x^2$` is the common case.
 *
 * Note the shape of the body: an earlier version used a lazy `([^$\n]+?)` plus a
 * `(?!\p{L})` lookahead at the end, which silently matched **nothing** for
 * `$x^2$`. With the body allowed to shrink, the engine satisfied the trailing
 * constraints by moving the body's start forward; consuming the first character
 * as its own class removes that ambiguity. The same failure mode is why the
 * whitespace class here is written out rather than delegated to a lookahead.
 */
private val INLINE_MATH = Regex(
    pattern = """(?<![\p{L}\p{N}\\])\$(?!\d)([^\s$][^$\n]*?)(?<!\s)\$(?![\p{L}\p{N}])""",
)

