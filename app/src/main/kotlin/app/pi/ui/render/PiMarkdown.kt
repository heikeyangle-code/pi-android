package app.pi.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.protocol.PiImage
import app.pi.ui.render.rich.PiFootnoteMarkdown
import app.pi.ui.theme.PiContrast
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

private class ParsedMarkdownDoc(
    val content: String,
    val root: ASTNode,
    val referenceLinks: Map<String, String>,
)

/**
 * Shared stateless GFM flavour & parser instance.
 * `MarkdownParser.doParse` keeps all parse state in call-local variables, so sharing one instance
 * across every chat message avoids allocating fresh lexer tables and marker-block factories per row.
 */
internal val PiSharedGfmFlavour: GFMFlavourDescriptor = GFMFlavourDescriptor()
internal val PiSharedMarkdownParser: MarkdownParser = MarkdownParser(
    flavour = PiSharedGfmFlavour,
    assertionsEnabled = false,
    cancellationToken = CancellationToken.NonCancellable,
)

/**
 * Thresholds for synchronous frame-0 AST parsing:
 *  - Settled messages (`!streaming`): <= 2 KiB parses in <0.4 ms on ARM64, avoiding async latency.
 *  - Streaming messages (`streaming`): <= 1.5 KiB parses synchronously so initial tokens render on
 *    frame 0; longer streaming messages switch seamlessly to a conflated `Dispatchers.Default`
 *    worker while retaining [retainedDoc] on screen (zero raw-markdown flash, zero starvation).
 */
private const val SYNC_PARSE_SETTLED_LIMIT = 2048
private const val SYNC_PARSE_STREAMING_LIMIT = 1500

/**
 * Pi's native Compose Markdown entry point, backed directly by JetBrains `org.jetbrains:markdown`
 * GFM AST with zero third-party Compose renderer wrappers.
 *
 * Features:
 *  - Zero-flash dual-path parsing: synchronous on frame 0 when [immediate] or under threshold
 *    (billed to [piMarkdownParseBudget]); conflated off-main-thread worker on `Dispatchers.Default`
 *    for large/streaming documents so rapid tokens never starve or cancel in-flight parses.
 *  - First-class inline & display LaTeX (`$...$`, `\(...\)`, `$$...$$`, `\[...\]`, `\begin{...}`)
 *    across paragraphs, lists, blockquotes, GFM callout alerts, headings, and table cells.
 *  - Full rich fence rendering (Chromium Skia Mermaid diagrams, SVG, MathJax, CSV/TSV tables,
 *    JSON/YAML/XML trees, stack traces, safe HTML previews, diffs, and 35+ language native
 *    syntax highlighting).
 *  - 100% theme-driven via [PiTheme.palette] and [PiTheme.typography].
 */
@Composable
internal fun PiMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    immediate: Boolean = LocalPiMarkdownImmediate.current,
    onImageClick: ((PiImage) -> Unit)? = null,
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    remember(context) { PiNodeCodeHighlighter.attach(context) }

    val hasMathSyntax = remember(markdown) {
        markdown.contains('$') ||
            markdown.contains("\\(") ||
            markdown.contains("\\[") ||
            markdown.contains("\\begin{")
    }
    val inlineExtracted = remember(markdown, streaming, hasMathSyntax) {
        if (streaming || !hasMathSyntax) {
            null
        } else {
            runCatching { PiInlineMath.extract(markdown) }.getOrNull()
        }
    }
    val activeInlineMath = inlineExtracted?.takeIf { it.hasInlineMath }
    val prepared = remember(markdown, streaming && hasMathSyntax, hasMathSyntax, inlineExtracted) {
        when {
            !hasMathSyntax -> PiLatex.PreparedMath(markdown, false)
            inlineExtracted != null && (inlineExtracted.hasInlineMath || inlineExtracted.hasBlockMath) ->
                PiLatex.PreparedMath(inlineExtracted.markdown, false)
            else -> {
                val keepBlockMath = !streaming && piDisplayMathIsStandalone(markdown)
                runCatching { PiLatex.prepare(markdown, keepBlockMath = keepBlockMath) }
                    .getOrElse { PiLatex.PreparedMath(markdown, false) }
            }
        }
    }
    val hasFootnoteSyntax = remember(prepared.text) { prepared.text.contains("[^") }
    val footnoteSource = remember(prepared.text, streaming && hasFootnoteSyntax, hasFootnoteSyntax) {
        if (streaming || !hasFootnoteSyntax) {
            null
        } else {
            runCatching { PiFootnoteMarkdown.rewrite(prepared.text) }.getOrNull()
        }
    }
    val content = footnoteSource ?: prepared.text
    val hasGrid = prepared.hasDisplayGrid

    val palette = PiTheme.palette
    val darkTheme = PiContrast.luminance(palette.pageBg) < 0.5
    val profile = PiTheme.typography
    val colors = remember(palette, darkTheme, textColor) {
        piMarkdownColors(palette, darkTheme, textColor)
    }
    val typography = remember(palette, profile, textColor, hasGrid) {
        piMarkdownTypography(
            palette = palette,
            profile = profile,
            textColor = textColor,
            monoParagraph = hasGrid,
        )
    }
    val spacing = remember(profile) { piMarkdownSpacing(profile) }
    val imageLoader = rememberPiGuestImageTransformer()

    // Synchronous fast path for immediate rows or short content; conflated async worker for
    // large/long streams so rapid token bursts never cancel and starve an in-flight AST parse.
    val syncParse = if (streaming) {
        content.length <= SYNC_PARSE_STREAMING_LIMIT
    } else {
        immediate || content.length <= SYNC_PARSE_SETTLED_LIMIT
    }
    val syncDoc = remember(content, syncParse) {
        if (!syncParse) {
            null
        } else {
            val startedNanos = if (immediate) System.nanoTime() else 0L
            val parsed = parseDocumentSafely(PiSharedMarkdownParser, content)
            if (immediate) {
                piMarkdownParseBudget.chargeNanos(
                    nowNanos = System.nanoTime(),
                    costNanos = System.nanoTime() - startedNanos,
                )
            }
            parsed
        }
    }

    // Always retain the most recent valid AST across sync -> async transitions (e.g. a streaming
    // reply crossing 1.5 KiB) so the row never flashes raw Markdown or collapses to zero height.
    var retainedDoc by remember { mutableStateOf<ParsedMarkdownDoc?>(null) }
    var parseFailed by remember { mutableStateOf(false) }

    // Conflated channel decouples incoming streaming tokens from the background parse loop:
    // the worker finishes its current parse uninterrupted and immediately grabs the latest snapshot.
    val asyncQueue = remember { Channel<String>(Channel.CONFLATED) }

    SideEffect {
        if (syncParse) {
            if (syncDoc != null && retainedDoc !== syncDoc) {
                retainedDoc = syncDoc
                parseFailed = false
            } else if (syncDoc == null) {
                parseFailed = true
            }
        } else if (retainedDoc?.content != content) {
            asyncQueue.trySend(content)
        }
    }

    LaunchedEffect(asyncQueue) {
        for (snapshot in asyncQueue) {
            if (retainedDoc?.content == snapshot) continue
            val parsed = withContext(Dispatchers.Default) {
                parseDocumentSafely(PiSharedMarkdownParser, snapshot)
            }
            if (parsed != null) {
                retainedDoc = parsed
                parseFailed = false
            } else {
                parseFailed = true
            }
        }
    }

    val activeDoc = syncDoc ?: retainedDoc
    val isCurrentParsed = activeDoc != null && activeDoc.content == content
    val onParsed = LocalPiMarkdownParsed.current
    LaunchedEffect(isCurrentParsed, content, onParsed) {
        if (onParsed != null && isCurrentParsed) onParsed()
    }

    CompositionLocalProvider(
        LocalPiCodeHighlighter provides PiNodeCodeHighlighter,
        LocalPiImageTransformer provides imageLoader,
        LocalPiImageClick provides onImageClick,
        LocalPiMarkdownStreaming provides streaming,
        LocalPiInlineMathTable provides activeInlineMath,
    ) {
        when {
            activeDoc != null -> {
                PiMarkdownDocument(
                    content = activeDoc.content,
                    root = activeDoc.root,
                    colors = colors,
                    typography = typography,
                    spacing = spacing,
                    referenceLinks = activeDoc.referenceLinks,
                    eolAsNewLine = hasGrid,
                    modifier = modifier,
                )
            }
            parseFailed -> {
                Text(
                    text = if (activeInlineMath != null) {
                        PiInlineMath.resolveToUnicode(content, activeInlineMath)
                    } else {
                        content
                    },
                    style = typography.text,
                    color = colors.text,
                    modifier = modifier,
                )
            }
            else -> {
                // First frame of a large non-immediate row (protected by ChatScreen's
                // rememberedRowHeight floor while Dispatchers.Default finishes in ~3-8 ms):
                // avoid flashing unformatted raw `**markdown**` syntax.
                Box(modifier)
            }
        }
    }
}

private fun parseDocumentSafely(parser: MarkdownParser, content: String): ParsedMarkdownDoc? =
    runCatching {
        val root = parser.buildMarkdownTreeFromString(content as CharSequence)
        val refs = extractPiReferenceLinks(content, root)
        ParsedMarkdownDoc(content = content, root = root, referenceLinks = refs)
    }.getOrNull()

internal fun piMarkdownSource(markdown: String): String = PiLatex.preprocess(markdown)

internal fun piDisplayMathIsStandalone(markdown: String): Boolean {
    if (!markdown.contains("$$")) return false
    var seen = false
    for (line in markdown.lineSequence()) {
        if (!line.contains("$$")) continue
        seen = true
        val trimmed = line.trim()
        if (trimmed == "$$") continue
        val occurrences = countOccurrences(trimmed, "$$")
        if (occurrences != 2) return false
        if (!trimmed.startsWith("$$") || !trimmed.endsWith("$$") || trimmed.length <= 4) return false
    }
    return seen
}

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}
