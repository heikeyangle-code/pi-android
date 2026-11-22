package app.pi.ui.render

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private class ParsedMarkdownDoc(
    val content: String,
    val root: ASTNode,
    val referenceLinks: Map<String, String>,
)

/**
 * Threshold below which Markdown is parsed synchronously on frame 0 (takes <0.6 ms on ARM64),
 * guaranteeing zero blank-frame flicker for short/medium chat messages while keeping huge
 * settled transcripts off the main thread.
 */
private const val SYNC_PARSE_CHAR_LIMIT = 4096

/**
 * Pi's native Compose Markdown entry point, backed directly by JetBrains `org.jetbrains:markdown`
 * GFM AST with zero third-party Compose renderer wrappers.
 *
 * Features:
 *  - Zero-flash dual-path parsing: synchronous on frame 0 when [immediate] or `<= 4 KiB`
 *    (billed to [piMarkdownParseBudget]); off-main-thread on `Dispatchers.Default` for huge
 *    documents while retaining the previous rendered AST on screen (no blank loading blink).
 *  - First-class inline & display LaTeX (`$...$`, `\(...\)`, `$$...$$`, `\[...\]`) across
 *    paragraphs, lists, blockquotes, GFM callout alerts, headings, and table cells.
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
        markdown.contains('$') || markdown.contains("\\(") || markdown.contains("\\[")
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
            inlineExtracted != null -> PiLatex.PreparedMath(inlineExtracted.markdown, false)
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

    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }

    // Synchronous fast path for immediate rows or modest documents; retained async path for huge docs.
    val syncParse = immediate || content.length <= SYNC_PARSE_CHAR_LIMIT
    val syncDoc = remember(content, syncParse, parser) {
        if (!syncParse) {
            null
        } else {
            val startedNanos = if (immediate) System.nanoTime() else 0L
            val parsed = parseDocumentSafely(parser, content)
            if (immediate) {
                piMarkdownParseBudget.chargeNanos(
                    nowNanos = System.nanoTime(),
                    costNanos = System.nanoTime() - startedNanos,
                )
            }
            parsed
        }
    }

    var asyncDoc by remember { mutableStateOf<ParsedMarkdownDoc?>(null) }
    var parseFailed by remember(content) { mutableStateOf(false) }

    LaunchedEffect(content, syncParse, parser) {
        if (syncParse) {
            parseFailed = syncDoc == null
            return@LaunchedEffect
        }
        val parsed = withContext(Dispatchers.Default) { parseDocumentSafely(parser, content) }
        if (parsed != null) {
            asyncDoc = parsed
            parseFailed = false
        } else {
            parseFailed = true
        }
    }

    val activeDoc = syncDoc ?: asyncDoc
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
                // First frame of a huge non-immediate doc: show clean resolved text immediately
                // while Dispatchers.Default finishes parsing (never a blank zero-height box).
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
        }
    }
}

private fun parseDocumentSafely(parser: MarkdownParser, content: String): ParsedMarkdownDoc? =
    runCatching {
        val root = parser.buildMarkdownTreeFromString(content)
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
