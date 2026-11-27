package app.pi.ui.render

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Announcement
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.bridge.PiImageLoadState
import app.pi.bridge.PiImageLoader
import app.pi.bridge.PiNoOpImageLoader
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.i18n.tr
import app.pi.protocol.PiImage
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readString
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.render.rich.LinkCardResult
import app.pi.ui.render.rich.CsvParseResult
import app.pi.ui.render.rich.JsonTreeResult
import app.pi.ui.render.rich.PiCsvTable
import app.pi.ui.render.rich.PiCsvTableView
import app.pi.ui.render.rich.PiJsonTree
import app.pi.ui.render.rich.PiJsonTreeView
import app.pi.ui.render.rich.PiLinkCard
import app.pi.ui.render.rich.PiLinkPreview
import app.pi.ui.render.rich.PiStackTraceView
import app.pi.ui.render.rich.PiTreeMode
import app.pi.ui.render.rich.PiXmlTreeParser
import app.pi.ui.render.rich.PiXmlTreeView
import app.pi.ui.render.rich.PiYamlTree
import app.pi.ui.render.rich.PiYamlTreeView
import app.pi.ui.render.rich.XmlTreeResult
import app.pi.ui.render.rich.YamlTreeResult
import app.pi.ui.render.rich.rememberTreeParse
import app.pi.ui.render.rich.RichBlock
import app.pi.ui.render.rich.RichBlockKind
import app.pi.ui.render.rich.svg.PiMathView
import app.pi.ui.render.rich.svg.PiMermaidView
import app.pi.ui.render.rich.svg.PiSvgFenceView
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Fallback code highlighter for languages outside [PiFastCodeHighlighter]'s 35+ native grammars. */
internal val LocalPiCodeHighlighter = staticCompositionLocalOf<PiCodeHighlighter> {
    PiPlainCodeHighlighter
}

/** Active Markdown image loader (installed by [PiMarkdownText] and overridden per-cell by [PiTable]). */
internal val LocalPiImageTransformer = staticCompositionLocalOf<PiImageLoader> {
    PiNoOpImageLoader
}

/** Tap callback to open an inline or block image in `PiImageViewer`. */
internal val LocalPiImageClick = compositionLocalOf<((PiImage) -> Unit)?> { null }

// ------------------------------------------------------------------ Document & Block Dispatch

/**
 * Renders the top-level children of a parsed `org.jetbrains:markdown` GFM AST with clean
 * inter-block rhythm (zero leading spacer above the first block, matching Pi's terminal layout).
 */
@Composable
internal fun PiMarkdownDocument(
    content: String,
    root: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var renderedCount = 0
        root.children.forEachIndexed { index, child ->
            if (!isVisibleBlockNode(child)) return@forEachIndexed
            key(index, child.type) {
                if (renderedCount > 0) {
                    Spacer(Modifier.height(spacing.block))
                }
                PiMarkdownBlock(
                    content = content,
                    node = child,
                    colors = colors,
                    typography = typography,
                    spacing = spacing,
                    referenceLinks = referenceLinks,
                    eolAsNewLine = eolAsNewLine,
                    listDepth = 0,
                    isFirstBlock = renderedCount == 0,
                )
            }
            renderedCount++
        }
    }
}

private fun isVisibleBlockNode(node: ASTNode): Boolean = when (node.type) {
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE,
    MarkdownElementTypes.LINK_DEFINITION -> false
    else -> true
}

@Composable
internal fun PiMarkdownBlock(
    content: String,
    node: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    listDepth: Int,
    isFirstBlock: Boolean,
    quoteDepth: Int = 0,
) {
    when (node.type) {
        MarkdownElementTypes.ATX_1 -> PiMarkdownHeading(
            content, node, level = 1, setext = false, extraTop = spacing.headingTopH1,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.ATX_2 -> PiMarkdownHeading(
            content, node, level = 2, setext = false, extraTop = spacing.headingTopH2,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.ATX_3 -> PiMarkdownHeading(
            content, node, level = 3, setext = false, extraTop = spacing.headingTopH3,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.ATX_4 -> PiMarkdownHeading(
            content, node, level = 4, setext = false, extraTop = spacing.headingTopSmall,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.ATX_5 -> PiMarkdownHeading(
            content, node, level = 5, setext = false, extraTop = spacing.headingTopSmall,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.ATX_6 -> PiMarkdownHeading(
            content, node, level = 6, setext = false, extraTop = spacing.headingTopSmall,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.SETEXT_1 -> PiMarkdownHeading(
            content, node, level = 1, setext = true, extraTop = spacing.headingTopH1,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.SETEXT_2 -> PiMarkdownHeading(
            content, node, level = 2, setext = true, extraTop = spacing.headingTopH2,
            colors = colors, typography = typography, referenceLinks = referenceLinks, isFirstBlock = isFirstBlock,
        )
        MarkdownElementTypes.PARAGRAPH -> PiParagraph(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
        )
        MarkdownElementTypes.BLOCK_QUOTE,
        GFMElementTypes.ALERT -> PiBlockQuoteOrAlert(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
            quoteDepth = quoteDepth,
        )
        MarkdownElementTypes.UNORDERED_LIST -> PiListBlock(
            content = content,
            node = node,
            ordered = false,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
            depth = listDepth,
            quoteDepth = quoteDepth,
        )
        MarkdownElementTypes.ORDERED_LIST -> PiListBlock(
            content = content,
            node = node,
            ordered = true,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
            depth = listDepth,
            quoteDepth = quoteDepth,
        )
        GFMElementTypes.TABLE -> PiTable(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            referenceLinks = referenceLinks,
            quoteDepth = quoteDepth,
            listDepth = listDepth,
        )
        MarkdownElementTypes.CODE_FENCE -> PiCodeFence(
            content = content,
            node = node,
            typography = typography,
        )
        MarkdownElementTypes.CODE_BLOCK -> PiIndentedCodeBlock(
            content = content,
            node = node,
            typography = typography,
        )
        MarkdownTokenTypes.HORIZONTAL_RULE -> PiMarkdownDivider(colors = colors)
        MarkdownElementTypes.HTML_BLOCK -> PiHtmlBlock(content = content, node = node)
        GFMElementTypes.BLOCK_MATH -> PiFormulaBlock(content = content, node = node, display = true)
        GFMElementTypes.INLINE_MATH -> PiFormulaBlock(content = content, node = node, display = false)
        MarkdownElementTypes.IMAGE -> PiBlockImage(
            content = content,
            node = node,
            typography = typography,
            referenceLinks = referenceLinks,
        )
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE -> Unit
        else -> {
            if (node.children.isNotEmpty()) {
                PiInlineMarkdownText(
                    content = content,
                    node = node,
                    style = typography.text,
                    colors = colors,
                    typography = typography,
                    referenceLinks = referenceLinks,
                    eolAsNewLine = eolAsNewLine,
                )
            } else {
                val raw = safeSlice(content, node)
                if (raw.isNotBlank()) {
                    val table = LocalPiInlineMathTable.current
                    Text(
                        text = if (table != null) PiInlineMath.resolveToUnicode(raw, table) else raw,
                        style = typography.text,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Headings

@Composable
private fun PiMarkdownHeading(
    content: String,
    node: ASTNode,
    level: Int,
    setext: Boolean,
    extraTop: Dp,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
    isFirstBlock: Boolean,
) {
    val palette = PiTheme.palette
    val baseStyle = when (level) {
        1 -> typography.h1
        2 -> typography.h2
        3 -> typography.h3
        4 -> typography.h4
        5 -> typography.h5
        else -> typography.h6
    }
    val contentNode = remember(node, setext) {
        val targetType = if (setext) MarkdownTokenTypes.SETEXT_CONTENT else MarkdownTokenTypes.ATX_CONTENT
        node.findChildOfType(targetType) ?: node
    }
    val headingSlice = safeSlice(content, contentNode)
    val style = remember(baseStyle, level, headingSlice) {
        if (level <= 3 && isLatinDominantHeading(headingSlice)) {
            val tightenSp = when (level) {
                1 -> -0.32f
                2 -> -0.22f
                else -> -0.10f
            }
            baseStyle.copy(letterSpacing = tightenSp.sp)
        } else {
            baseStyle
        }
    }
    val skipTop = isFirstBlock || headingFollowsHeading(node)
    val topPad = if (skipTop) 0.dp else extraTop
    val showRule = level == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad)
            .semantics { heading() },
    ) {
        PiInlineMarkdownText(
            content = content,
            node = contentNode,
            style = style,
            colors = colors,
            typography = typography,
            referenceLinks = referenceLinks,
            trimLeadingWhitespace = true,
        )
        if (showRule) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.borderMuted.copy(alpha = 0.52f)),
            )
        }
    }
}

/** True when a heading is predominantly Latin/ASCII letters so subtle editorial negative tracking enhances legibility without squeezing CJK ideographs. */
internal fun isLatinDominantHeading(text: String): Boolean {
    var latin = 0
    var cjk = 0
    for (ch in text) {
        val code = ch.code
        when {
            (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) || (code in 0x3040..0x30FF) || (code in 0xAC00..0xD7AF) -> cjk++
            (ch in 'A'..'Z') || (ch in 'a'..'z') -> latin++
        }
    }
    return latin > 0 && cjk == 0
}

private fun headingFollowsHeading(node: ASTNode): Boolean {
    if (node.startOffset == 0) return true
    val siblings = node.parent?.children ?: return false
    val index = siblings.indexOfFirst { it === node }
    if (index <= 0) return true
    for (i in index - 1 downTo 0) {
        val type = siblings[i].type
        if (type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.WHITE_SPACE) continue
        return type in HEADING_TYPES
    }
    return true
}

private val HEADING_TYPES = setOf(
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
)

// ------------------------------------------------------------------ Paragraphs

@Composable
private fun PiParagraph(
    content: String,
    node: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    skipLeadingPrefixChars: Int = 0,
) {
    // 1. Standalone block image paragraph: `![alt](url)` with nothing else
    if (skipLeadingPrefixChars == 0) {
        val soloImage = remember(node) { soloImageChild(node) }
        if (soloImage != null) {
            PiBlockImage(
                content = content,
                node = soloImage,
                typography = typography,
                referenceLinks = referenceLinks,
            )
            return
        }
    }

    val rawSlice = safeSlice(content, node)
    val trimmed = remember(rawSlice) {
        stripBlockQuotePrefixes(rawSlice).trim()
    }

    // 2. Standalone display math: `$$ ... $$`
    if (skipLeadingPrefixChars == 0) {
        val mathSource = remember(trimmed) {
            if (trimmed.length > 4 && trimmed.startsWith("$$") && trimmed.endsWith("$$") &&
                countDisplayDelimiters(trimmed) == 2
            ) {
                trimmed.substring(2, trimmed.length - 2).trim().ifEmpty { null }
            } else {
                null
            }
        }
        if (mathSource != null) {
            val fallback = remember(mathSource) {
                runCatching { PiLatex.toUnicode(mathSource, display = true) }.getOrNull() ?: mathSource
            }
            PiMathView(
                source = mathSource,
                display = true,
                settled = !LocalPiMarkdownStreaming.current,
                fallbackText = fallback,
            )
            return
        }
    }

    val streaming = LocalPiMarkdownStreaming.current
    val writingTail = streaming && content.substring(node.endOffset.coerceIn(0, content.length)).isBlank()

    // 3. Standalone audio/video link -> media card
    if (skipLeadingPrefixChars == 0 && !writingTail) {
        val mediaUrl = remember(trimmed) { piMediaFromParagraph(trimmed) }
        if (mediaUrl != null) {
            piMediaKind(mediaUrl)?.let { kind ->
                PiMediaCard(mediaUrl, kind)
                return
            }
        }
    }

    // 4. Standalone HTTP(S) URL -> direct image or link preview card
    if (skipLeadingPrefixChars == 0 && !writingTail) {
        val linkInfo = remember(trimmed) {
            if (trimmed.length > BARE_URL_MAX_CHARS || trimmed.any { it.isWhitespace() }) {
                null
            } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                null
            } else {
                (PiLinkCard.parse(trimmed) as? LinkCardResult.Info)?.info
            }
        }
        if (linkInfo != null) {
            if (linkInfo.kind == app.pi.ui.render.rich.LinkKind.Image) {
                val loadState = LocalPiImageTransformer.current.loadState(linkInfo.url)
                if (loadState !is PiImageLoadState.Failed) {
                    PiBlockImageSurface(
                        link = linkInfo.url,
                        alt = null,
                        loadState = loadState,
                    )
                    return
                }
            }
            val uriHandler = LocalUriHandler.current
            PiLinkPreview(
                url = linkInfo.url,
                onOpen = { url -> runCatching { uriHandler.openUri(url) } },
            )
            return
        }
    }

    // 5. Standard rich paragraph (with inline LaTeX, inline images, formatting, links, safe HTML)
    PiInlineMarkdownText(
        content = content,
        node = node,
        style = typography.paragraph,
        colors = colors,
        typography = typography,
        referenceLinks = referenceLinks,
        eolAsNewLine = eolAsNewLine,
        skipLeadingPrefixChars = skipLeadingPrefixChars,
    )
}

private fun soloImageChild(paragraph: ASTNode): ASTNode? {
    var found: ASTNode? = null
    for (child in paragraph.children) {
        when (child.type) {
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownTokenTypes.EOL -> continue
            MarkdownElementTypes.IMAGE -> {
                if (found != null) return null
                found = child
            }
            else -> return null
        }
    }
    return found
}

// ------------------------------------------------------------------ Blockquotes & GFM Alerts

internal enum class PiAlertKind(val marker: String) {
    Note("[!NOTE]"),
    Tip("[!TIP]"),
    Important("[!IMPORTANT]"),
    Warning("[!WARNING]"),
    Caution("[!CAUTION]"),
}

internal data class DetectedAlert(
    val kind: PiAlertKind,
    val firstParagraph: ASTNode?,
    val skipCharsInFirstParagraph: Int,
    val firstParagraphHasRemainingBody: Boolean,
)

/**
 * Detects GitHub Flavored Markdown alert callouts (`> [!NOTE]` / `TIP` / `IMPORTANT` / `WARNING` /
 * `CAUTION`). Supports both native `GFMElementTypes.ALERT` nodes emitted by
 * `org.jetbrains:markdown:0.7.9+` and fallback `MarkdownElementTypes.BLOCK_QUOTE` nodes.
 */
internal fun detectGfmAlert(content: String, quoteNode: ASTNode): DetectedAlert? {
    if (quoteNode.type == GFMElementTypes.ALERT) {
        val titleNode = quoteNode.children.firstOrNull { it.type == GFMTokenTypes.ALERT_TITLE }
            ?: return null
        val markerText = safeSlice(content, titleNode).trim()
        val matchedKind = PiAlertKind.entries.firstOrNull {
            markerText.equals(it.marker, ignoreCase = true)
        } ?: return null
        val firstBody = quoteNode.children.firstOrNull {
            it.type != MarkdownTokenTypes.BLOCK_QUOTE &&
                it.type != GFMTokenTypes.ALERT_TITLE &&
                it.type != MarkdownTokenTypes.EOL &&
                it.type != MarkdownTokenTypes.WHITE_SPACE
        }
        return DetectedAlert(
            kind = matchedKind,
            firstParagraph = null,
            skipCharsInFirstParagraph = 0,
            firstParagraphHasRemainingBody = firstBody != null,
        )
    }

    val firstBlock = quoteNode.children.firstOrNull {
        it.type != MarkdownTokenTypes.BLOCK_QUOTE &&
            it.type != MarkdownTokenTypes.EOL &&
            it.type != MarkdownTokenTypes.WHITE_SPACE
    } ?: return null
    if (firstBlock.type != MarkdownElementTypes.PARAGRAPH) return null

    val raw = safeSlice(content, firstBlock)
    val leadWs = raw.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) return null else it }
    val rest = raw.substring(leadWs)
    val matchedKind = PiAlertKind.entries.firstOrNull { kind ->
        rest.startsWith(kind.marker, ignoreCase = true) &&
            (rest.length == kind.marker.length || rest[kind.marker.length] == '\n' || rest[kind.marker.length] == '\r' || rest[kind.marker.length] == ' ')
    } ?: return null

    var after = leadWs + matchedKind.marker.length
    // Only treat as a GFM alert when the marker stands alone on its line (whitespace + optional newline)
    while (after < raw.length && (raw[after] == ' ' || raw[after] == '\t' || raw[after] == '\r')) after++
    val hasMore: Boolean
    if (after == raw.length) {
        hasMore = false
    } else if (raw[after] == '\n') {
        after++
        hasMore = stripBlockQuotePrefixes(raw.substring(after)).isNotBlank()
    } else {
        return null
    }
    return DetectedAlert(
        kind = matchedKind,
        firstParagraph = firstBlock,
        skipCharsInFirstParagraph = after,
        firstParagraphHasRemainingBody = hasMore,
    )
}

@Composable
private fun PiBlockQuoteOrAlert(
    content: String,
    node: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    quoteDepth: Int,
) {
    val quoteSlice = safeSlice(content, node)
    val alert = remember(quoteSlice, node.type) { detectGfmAlert(content, node) }
    if (alert != null) {
        PiGfmAlertCard(
            content = content,
            node = node,
            alert = alert,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
            quoteDepth = quoteDepth,
        )
    } else {
        PiBlockQuote(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
            quoteDepth = quoteDepth,
        )
    }
}

@Composable
private fun PiGfmAlertCard(
    content: String,
    node: ASTNode,
    alert: DetectedAlert,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    quoteDepth: Int,
) {
    val accent: Color
    val icon: ImageVector
    val title: String
    when (alert.kind) {
        PiAlertKind.Note -> {
            accent = colors.alertNote
            icon = Icons.Outlined.Info
            title = tr("说明", "Note")
        }
        PiAlertKind.Tip -> {
            accent = colors.alertTip
            icon = Icons.Outlined.Lightbulb
            title = tr("提示", "Tip")
        }
        PiAlertKind.Important -> {
            accent = colors.alertImportant
            icon = Icons.AutoMirrored.Outlined.Announcement
            title = tr("重要", "Important")
        }
        PiAlertKind.Warning -> {
            accent = colors.alertWarning
            icon = Icons.Outlined.WarningAmber
            title = tr("注意", "Warning")
        }
        PiAlertKind.Caution -> {
            accent = colors.alertCaution
            icon = Icons.Outlined.ErrorOutline
            title = tr("警告", "Caution")
        }
    }

    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    val bgAlpha = if (colors.darkTheme) 0.11f else 0.08f
    val borderAlpha = if (colors.darkTheme) 0.28f else 0.22f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = bgAlpha))
            .border(0.5.dp, accent.copy(alpha = borderAlpha), shape)
            .drawBehind {
                val railW = 3.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.88f),
                    topLeft = Offset.Zero,
                    size = Size(railW, size.height),
                    cornerRadius = CornerRadius(railW / 2f, railW / 2f),
                )
            }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = title,
                style = typography.alertTitle.copy(color = accent),
                maxLines = 1,
            )
        }

        var renderedAny = false
        node.children.forEachIndexed { index, child ->
            if (child.type == MarkdownTokenTypes.BLOCK_QUOTE ||
                child.type == GFMTokenTypes.ALERT_TITLE ||
                child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.WHITE_SPACE
            ) return@forEachIndexed
            key(index, child.type) {
                if (alert.firstParagraph != null && child === alert.firstParagraph) {
                    if (alert.firstParagraphHasRemainingBody) {
                        Spacer(Modifier.height(6.dp))
                        PiParagraph(
                            content = content,
                            node = child,
                            colors = colors,
                            typography = typography,
                            referenceLinks = referenceLinks,
                            eolAsNewLine = eolAsNewLine,
                            skipLeadingPrefixChars = alert.skipCharsInFirstParagraph,
                        )
                        renderedAny = true
                    }
                } else {
                    Spacer(Modifier.height(if (renderedAny) spacing.block else 6.dp))
                    PiMarkdownBlock(
                        content = content,
                        node = child,
                        colors = colors,
                        typography = typography,
                        spacing = spacing,
                        referenceLinks = referenceLinks,
                        eolAsNewLine = eolAsNewLine,
                        listDepth = 0,
                        isFirstBlock = !renderedAny,
                        quoteDepth = quoteDepth + 1,
                    )
                    renderedAny = true
                }
            }
        }
    }
}

@Composable
private fun PiBlockQuote(
    content: String,
    node: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    quoteDepth: Int,
) {
    val palette = PiTheme.palette
    val quoteColor = colors.quoteText
    val nested = quoteDepth > 0
    val railColor = colors.quoteBorder.copy(alpha = if (nested) 0.50f else 0.78f)
    val bgColor = if (nested) {
        Color.Transparent
    } else {
        lerp(palette.cardBg, palette.pageBg, 0.25f)
    }
    val quoteTypo = remember(typography, quoteColor) {
        val qSize = typography.quote.fontSize
        val qLine = typography.quote.lineHeight
        typography.copy(
            text = typography.text.copy(color = quoteColor, fontSize = qSize, lineHeight = qLine),
            quote = typography.quote.copy(color = quoteColor),
            paragraph = typography.paragraph.copy(color = quoteColor, fontSize = qSize, lineHeight = qLine),
            ordered = typography.ordered.copy(color = quoteColor, fontSize = qSize, lineHeight = qLine),
            list = typography.list.copy(color = quoteColor, fontSize = qSize, lineHeight = qLine),
        )
    }
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    val quoteDesc = tr("引用内容", "Block quote")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (nested) Modifier else Modifier.clip(shape).background(bgColor))
            .drawBehind {
                val railW = (if (nested) 2.dp else 2.5.dp).toPx()
                drawRoundRect(
                    color = railColor,
                    topLeft = Offset.Zero,
                    size = Size(railW, size.height),
                    cornerRadius = CornerRadius(railW / 2f, railW / 2f),
                )
            }
            .padding(
                start = if (nested) 12.dp else 14.dp,
                end = if (nested) 0.dp else 12.dp,
                top = if (nested) 2.dp else 8.dp,
                bottom = if (nested) 2.dp else 8.dp,
            )
            .semantics { contentDescription = quoteDesc },
    ) {
        var renderedAny = false
        node.children.forEachIndexed { index, child ->
            if (child.type == MarkdownTokenTypes.BLOCK_QUOTE ||
                child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.WHITE_SPACE
            ) return@forEachIndexed
            key(index, child.type) {
                if (renderedAny) Spacer(Modifier.height(6.dp))
                PiMarkdownBlock(
                    content = content,
                    node = child,
                    colors = colors,
                    typography = quoteTypo,
                    spacing = spacing,
                    referenceLinks = referenceLinks,
                    eolAsNewLine = eolAsNewLine,
                    listDepth = 0,
                    isFirstBlock = !renderedAny,
                    quoteDepth = quoteDepth + 1,
                )
                renderedAny = true
            }
        }
    }
}

// ------------------------------------------------------------------ Lists & Task Checkboxes

@Composable
private fun PiListBlock(
    content: String,
    node: ASTNode,
    ordered: Boolean,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    depth: Int,
    quoteDepth: Int,
) {
    val items = remember(node) {
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (depth > 0) spacing.listIndent else 0.dp),
    ) {
        items.forEachIndexed { idx, item ->
            key(idx) {
                if (idx > 0) Spacer(Modifier.height(spacing.listItem))
                PiListItemRow(
                    content = content,
                    item = item,
                    ordered = ordered,
                    fallbackIndex = idx + 1,
                    depth = depth,
                    colors = colors,
                    typography = typography,
                    spacing = spacing,
                    referenceLinks = referenceLinks,
                    eolAsNewLine = eolAsNewLine,
                    quoteDepth = quoteDepth,
                )
            }
        }
    }
}

@Composable
private fun PiListItemRow(
    content: String,
    item: ASTNode,
    ordered: Boolean,
    fallbackIndex: Int,
    depth: Int,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    spacing: PiMarkdownSpacing,
    referenceLinks: Map<String, String>,
    eolAsNewLine: Boolean,
    quoteDepth: Int,
) {
    val checkboxNode = item.findChildOfType(GFMTokenTypes.CHECK_BOX)
    val numberText = if (!ordered) {
        null
    } else {
        val numNode = item.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
        val raw = numNode?.let { safeSlice(content, it).trim() }.orEmpty()
        raw.ifEmpty { "$fallbackIndex." }
    }
    val bodyChildren = item.children.filter {
        it.type != MarkdownTokenTypes.LIST_BULLET &&
            it.type != MarkdownTokenTypes.LIST_NUMBER &&
            it.type != GFMTokenTypes.CHECK_BOX &&
            it.type != MarkdownTokenTypes.EOL &&
            it.type != MarkdownTokenTypes.WHITE_SPACE
    }

    val density = LocalDensity.current
    val firstChildStyle = when (bodyChildren.firstOrNull()?.type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> typography.h1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> typography.h2
        MarkdownElementTypes.ATX_3 -> typography.h3
        MarkdownElementTypes.ATX_4 -> typography.h4
        MarkdownElementTypes.ATX_5 -> typography.h5
        MarkdownElementTypes.ATX_6 -> typography.h6
        else -> typography.paragraph
    }
    val textMeasurer = rememberTextMeasurer()
    // Measure the real first-line baseline and line box for the active prose/heading font so
    // vector bullets and task checkboxes sit at the true optical center of CJK + Latin glyphs
    // (Android's default proportional line-height puts ~75% of extra leading above the baseline,
    // which previously placed naive `0.5f * lineHeight` bullets noticeably above the text center).
    val firstLineMetrics = remember(firstChildStyle, density, textMeasurer) {
        val layout = textMeasurer.measure(
            text = "中Ag",
            style = firstChildStyle,
            maxLines = 1,
        )
        val lineH = (layout.getLineBottom(0) - layout.getLineTop(0)).coerceAtLeast(1f)
        val fontSizePx = with(density) {
            (firstChildStyle.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 15f).sp.toPx()
        }
        val opticalCenterY = (layout.firstBaseline - fontSizePx * 0.35f)
            .coerceIn(lineH * 0.28f, lineH * 0.78f)
        lineH to opticalCenterY
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Leading marker: checkbox (optically centered to first line), right-aligned numeral, or depth bullet
        if (checkboxNode != null) {
            val checked = piTaskCheckboxChecked(safeSlice(content, checkboxNode))
            val checkboxTopDp = with(density) {
                (firstLineMetrics.second - TASK_CHECKBOX_SIZE.toPx() / 2f).coerceAtLeast(0f).toDp()
            }
            Box(
                modifier = Modifier.padding(top = checkboxTopDp, end = TASK_CHECKBOX_END_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                PiTaskCheckboxGraphic(checked = checked)
            }
        } else if (ordered) {
            Text(
                text = numberText ?: "$fallbackIndex.",
                style = typography.ordered.copy(
                    color = typography.bullet.color,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.End,
                ),
                modifier = Modifier
                    .widthIn(min = 24.dp)
                    .padding(end = 6.dp),
            )
        } else {
            val markerHeightDp = with(density) { firstLineMetrics.first.toDp() }
            val opticalCenterPx = firstLineMetrics.second
            val bulletColor = typography.bullet.color
            Canvas(
                modifier = Modifier
                    .width(18.dp)
                    .height(markerHeightDp),
            ) {
                val cx = 6.dp.toPx()
                val cy = opticalCenterPx.coerceIn(0f, size.height)
                when (depth % 3) {
                    0 -> drawCircle(
                        color = bulletColor,
                        radius = 2.35.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                    1 -> drawCircle(
                        color = bulletColor,
                        radius = 2.35.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                    else -> {
                        val side = 4.2.dp.toPx()
                        drawRoundRect(
                            color = bulletColor,
                            topLeft = Offset(cx - side / 2f, cy - side / 2f),
                            size = Size(side, side),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            bodyChildren.forEachIndexed { idx, child ->
                key(idx, child.type) {
                    if (idx > 0) Spacer(Modifier.height(spacing.listItem.coerceAtLeast(4.dp)))
                    PiMarkdownBlock(
                        content = content,
                        node = child,
                        colors = colors,
                        typography = typography,
                        spacing = spacing,
                        referenceLinks = referenceLinks,
                        eolAsNewLine = eolAsNewLine,
                        listDepth = depth + 1,
                        isFirstBlock = idx == 0,
                        quoteDepth = quoteDepth,
                    )
                }
            }
        }
    }
}

@Composable
private fun PiTaskCheckboxGraphic(checked: Boolean) {
    val palette = PiTheme.palette
    val desc = if (checked) tr("已完成", "Completed") else tr("未完成", "Not completed")
    Canvas(
        modifier = Modifier
            .size(TASK_CHECKBOX_SIZE)
            .semantics { contentDescription = desc },
    ) {
        val radius = TASK_CHECKBOX_CORNER.toPx()
        val stroke = TASK_CHECKBOX_STROKE.toPx()
        if (checked) {
            drawRoundRect(
                color = palette.accent,
                cornerRadius = CornerRadius(radius, radius),
            )
            val tick = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.72f)
                lineTo(size.width * 0.77f, size.height * 0.30f)
            }
            drawPath(
                path = tick,
                color = palette.pageBg,
                style = Stroke(width = stroke * 1.35f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            drawRoundRect(
                color = palette.muted.copy(alpha = 0.75f),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke),
            )
        }
    }
}

internal fun piTaskCheckboxChecked(text: CharSequence): Boolean {
    val s = text.toString()
    val open = s.indexOf('[')
    if (open < 0 || open + 1 >= s.length) return false
    val mark = s[open + 1]
    return mark == 'x' || mark == 'X'
}

private val TASK_CHECKBOX_SIZE = 16.dp
private val TASK_CHECKBOX_CORNER = 4.dp
private val TASK_CHECKBOX_STROKE = 1.3.dp
private val TASK_CHECKBOX_END_PADDING = 7.dp

// ------------------------------------------------------------------ Dividers

@Composable
private fun PiMarkdownDivider(colors: PiMarkdownColors) {
    Box(Modifier.padding(vertical = 8.dp)) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.dividerColor,
        )
    }
}

// ------------------------------------------------------------------ Block Images & Tap Viewer

@Composable
private fun PiBlockImage(
    content: String,
    node: ASTNode,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
) {
    val imageLoader = LocalPiImageTransformer.current
    val imgSlice = safeSlice(content, node)
    val link = remember(imgSlice, referenceLinks) {
        resolveAstImageLink(content, node, referenceLinks)
    }
    val alt = remember(imgSlice) { resolveAstImageAlt(content, node) }

    piMediaKind(link)?.let { kind ->
        PiMediaCard(link!!, kind)
        return
    }

    val loadState = if (!link.isNullOrBlank()) imageLoader.loadState(link) else PiImageLoadState.Failed
    if (loadState is PiImageLoadState.Failed) {
        PiImageFailureCard(
            alt = alt,
            link = link,
            altStyle = typography.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
        return
    }
    PiBlockImageSurface(
        link = link,
        alt = alt,
        loadState = loadState,
    )
}

private val BLOCK_IMAGE_MAX_HEIGHT = 420.dp

private fun meaningfulImageCaption(alt: String?): String? {
    val trimmed = alt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = trimmed.lowercase()
    if (lower == "image" || lower == "img" || lower == "photo" || lower == "screenshot") return null
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) return null
    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg")
    ) {
        return null
    }
    return trimmed
}

@Composable
private fun PiBlockImageSurface(
    link: String?,
    alt: String?,
    loadState: PiImageLoadState,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val caption = remember(alt) { meaningfulImageCaption(alt) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .compensateHeightAboveViewport(link ?: ""),
    ) {
    when (loadState) {
        is PiImageLoadState.Ready -> {
            val decoded = loadState.data
            val ratio = decoded.widthOverHeight?.takeIf { it.isFinite() && it in 0.1f..10f }
            val frameModifier = when {
                ratio != null && ratio < 0.78f -> Modifier
                    .heightIn(max = BLOCK_IMAGE_MAX_HEIGHT)
                    .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                ratio != null -> decoded.modifier.aspectRatio(ratio)
                else -> decoded.modifier.heightIn(max = BLOCK_IMAGE_MAX_HEIGHT)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                PiImageViewerTap(link) {
                    Image(
                        painter = decoded.painter,
                        contentDescription = alt ?: decoded.contentDescription,
                        contentScale = decoded.contentScale,
                        modifier = frameModifier
                            .clip(shape)
                            .background(palette.cardBg)
                            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.42f), shape),
                    )
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
        is PiImageLoadState.Loading -> {
            val aspectWOverH = loadState.heightOverWidth
                ?.takeIf { it.isFinite() && it in 0.1f..10f }
                ?.let { (1f / it).coerceIn(0.4f, 4f) }
                ?: 1.6f
            val placeholderModifier = if (aspectWOverH < 0.78f) {
                Modifier
                    .padding(vertical = 4.dp)
                    .heightIn(max = BLOCK_IMAGE_MAX_HEIGHT)
                    .aspectRatio(aspectWOverH, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .aspectRatio(aspectWOverH)
            }
            Box(
                modifier = placeholderModifier
                    .clip(shape)
                    .background(palette.cardBg)
                    .border(0.5.dp, palette.borderMuted.copy(alpha = 0.28f), shape),
            )
        }
        PiImageLoadState.Failed -> Unit
    }
    }
}

@Composable
internal fun PiImageViewerTap(link: String?, content: @Composable () -> Unit) {
    val onImageClick = LocalPiImageClick.current
    val openLink = link?.takeIf { it.isNotBlank() }
    if (onImageClick == null || openLink == null) {
        content()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.clickable(onClickLabel = tr("查看图片", "View image")) {
            scope.launch {
                piMarkdownImageViewerImage(context, openLink)?.let(onImageClick)
            }
        },
    ) {
        content()
    }
}

// ------------------------------------------------------------------ Tables (Proportional + Inline Math!)

@Composable
private fun PiTable(
    content: String,
    node: ASTNode,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
    quoteDepth: Int = 0,
    listDepth: Int = 0,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val inlineMath = LocalPiInlineMathTable.current
    val tableSlice = safeSlice(content, node)
    val headerNode = node.findChildOfType(GFMElementTypes.HEADER) ?: return
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }
    val columns = headerNode.children.count { it.type == GFMTokenTypes.CELL }
    if (columns <= 0) return

    val alignments = remember(tableSlice) { piTableAlignments(content, node) }
    val columnDisplayChars = remember(tableSlice, columns, inlineMath) {
        val maxChars = IntArray(columns)
        fun sampleRow(r: ASTNode) {
            val cells = r.children.filter { it.type == GFMTokenTypes.CELL }
            for (c in 0 until minOf(columns, cells.size)) {
                val w = estimateTableCellDisplayChars(safeSlice(content, cells[c]), inlineMath)
                if (w > maxChars[c]) maxChars[c] = w
            }
        }
        sampleRow(headerNode)
        val sampleLimit = minOf(rowNodes.size, 12)
        for (i in 0 until sampleLimit) sampleRow(rowNodes[i])
        maxChars.toList()
    }

    val nestedInsetDp = (quoteDepth * 26 + listDepth * 22).dp
    val available = (LocalConfiguration.current.screenWidthDp.dp - 32.dp - nestedInsetDp).coerceAtLeast(180.dp)

    val tableFontSp = typography.table.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 14f
    val charWidthDp = (tableFontSp * 0.58f).coerceIn(6.8f, 11.5f)
    val colWidthsDp = remember(columnDisplayChars, available, charWidthDp) {
        computePiTableColumnWidthsDp(
            columnDisplayChars = columnDisplayChars,
            availableDp = available.value,
            charWidthDp = charWidthDp,
            horizontalPaddingDp = 24f,
            minColDp = 56f,
            maxColDp = 240f,
        ).map { it.dp }
    }
    val totalWidth = colWidthsDp.fold(0.dp) { acc, w -> acc + w }.coerceAtLeast(available)
    val maxColWidthPx = with(density) {
        (colWidthsDp.maxOfOrNull { it } ?: 120.dp).roundToPx()
    }.coerceAtLeast(1)
    val cellImageLoader = rememberPiGuestImageTransformer(maxColWidthPx)

    val tableShape = RoundedCornerShape(12.dp)
    val scrollState = rememberScrollState()
    val surfaceBg = colors.tableBackground
    val headerBg = remember(palette, colors.darkTheme) {
        lerp(palette.cardBg, palette.text, if (colors.darkTheme) 0.08f else 0.055f)
    }
    val zebraBg = remember(palette, colors.darkTheme) {
        lerp(palette.cardBg, palette.text, if (colors.darkTheme) 0.032f else 0.025f)
    }
    val borderStrokeColor = palette.borderMuted.copy(alpha = 0.56f)
    val rowHairlineColor = palette.borderMuted.copy(alpha = 0.30f)
    val colHairlineColor = palette.borderMuted.copy(alpha = 0.18f)

    CompositionLocalProvider(LocalPiImageTransformer provides cellImageLoader) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tableShape)
                .background(surfaceBg)
                .border(0.5.dp, borderStrokeColor, tableShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        val fadePx = TABLE_FADE_WIDTH.toPx().coerceAtMost(size.width * 0.2f)
                        if (fadePx > 1f && scrollState.value > 0) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(surfaceBg, surfaceBg.copy(alpha = 0f)),
                                    startX = 0f,
                                    endX = fadePx,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(fadePx, size.height),
                            )
                        }
                        if (fadePx > 1f && scrollState.value < scrollState.maxValue) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(surfaceBg.copy(alpha = 0f), surfaceBg),
                                    startX = size.width - fadePx,
                                    endX = size.width,
                                ),
                                topLeft = Offset(size.width - fadePx, 0f),
                                size = Size(fadePx, size.height),
                            )
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .width(totalWidth)
                        // Draw all vertical column hairlines in a single pass across the full table height:
                        // eliminates IntrinsicSize.Max double-measurement AND keeps gridlines continuous on 100+ row tables.
                        .drawWithContent {
                            drawContent()
                            if (colWidthsDp.size > 1) {
                                val strokePx = 0.5.dp.toPx()
                                var xPx = 0f
                                for (col in 0 until colWidthsDp.size - 1) {
                                    xPx += colWidthsDp[col].toPx()
                                    drawLine(
                                        color = colHairlineColor,
                                        start = Offset(xPx, 0f),
                                        end = Offset(xPx, size.height),
                                        strokeWidth = strokePx,
                                    )
                                }
                            }
                        },
                ) {
                    // Header row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(headerBg),
                    ) {
                        val headerCells = headerNode.children.filter { it.type == GFMTokenTypes.CELL }
                        for (col in 0 until columns) {
                            val colW = colWidthsDp[col]
                            val cell = headerCells.getOrNull(col)
                            val align = alignments.alignAt(col)
                            Box(
                                modifier = Modifier
                                    .width(colW)
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                                    .semantics {
                                        heading()
                                        collectionItemInfo = CollectionItemInfo(
                                            rowIndex = 0, rowSpan = 1,
                                            columnIndex = col, columnSpan = 1,
                                        )
                                    },
                                contentAlignment = align.centerBoxAlignment,
                            ) {
                                if (cell != null) {
                                    PiTableCellContent(
                                        content = content,
                                        cell = cell,
                                        style = typography.table.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.text,
                                        ),
                                        align = align,
                                        colors = colors,
                                        typography = typography,
                                        referenceLinks = referenceLinks,
                                        cellContentWidthDp = (colW.value - 24f).coerceAtLeast(32f),
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(borderStrokeColor),
                    )

                    // Data rows
                    rowNodes.forEachIndexed { rowIdx, rowNode ->
                        if (rowIdx > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(rowHairlineColor),
                            )
                        }
                        val rowCells = rowNode.children.filter { it.type == GFMTokenTypes.CELL }
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (rowIdx % 2 == 1) zebraBg else Color.Transparent),
                        ) {
                            for (col in 0 until columns) {
                                val colW = colWidthsDp[col]
                                val cell = rowCells.getOrNull(col)
                                val align = alignments.alignAt(col)
                                Box(
                                    modifier = Modifier
                                        .width(colW)
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                        .semantics {
                                            collectionItemInfo = CollectionItemInfo(
                                                rowIndex = rowIdx + 1, rowSpan = 1,
                                                columnIndex = col, columnSpan = 1,
                                            )
                                        },
                                    contentAlignment = align.boxAlignment,
                                ) {
                                    if (cell != null) {
                                        PiTableCellContent(
                                            content = content,
                                            cell = cell,
                                            style = typography.table,
                                            align = align,
                                            colors = colors,
                                            typography = typography,
                                            referenceLinks = referenceLinks,
                                            cellContentWidthDp = (colW.value - 24f).coerceAtLeast(32f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PiTableCellContent(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    align: PiTableAlign,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
    cellContentWidthDp: Float,
) {
    val textAlign = align.textAlign
    PiInlineMarkdownText(
        content = content,
        node = cell,
        style = if (textAlign != null) style.copy(textAlign = textAlign) else style,
        colors = colors,
        typography = typography,
        referenceLinks = referenceLinks,
        maxLines = TABLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        trimLeadingWhitespace = true,
        cellImageMaxWidthDp = cellContentWidthDp,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun List<PiTableAlign>.alignAt(column: Int): PiTableAlign =
    getOrElse(column) { PiTableAlign.Default }

private val PiTableAlign.textAlign: TextAlign?
    get() = when (this) {
        PiTableAlign.Default -> null
        PiTableAlign.Left -> TextAlign.Start
        PiTableAlign.Center -> TextAlign.Center
        PiTableAlign.Right -> TextAlign.End
    }

private val PiTableAlign.boxAlignment: Alignment
    get() = when (this) {
        PiTableAlign.Default, PiTableAlign.Left -> Alignment.TopStart
        PiTableAlign.Center -> Alignment.TopCenter
        PiTableAlign.Right -> Alignment.TopEnd
    }

private val PiTableAlign.centerBoxAlignment: Alignment
    get() = when (this) {
        PiTableAlign.Default, PiTableAlign.Left -> Alignment.CenterStart
        PiTableAlign.Center -> Alignment.Center
        PiTableAlign.Right -> Alignment.CenterEnd
    }

private fun piTableAlignments(content: String, table: ASTNode): List<PiTableAlign> {
    val separator = table.findChildOfType(GFMTokenTypes.TABLE_SEPARATOR) ?: return emptyList()
    return parsePiTableAlignments(safeSlice(content, separator))
}

private val TABLE_FADE_WIDTH = 26.dp
private const val TABLE_MAX_LINES = 50

// ------------------------------------------------------------------ HTML & Math Blocks

@Composable
private fun PiHtmlBlock(content: String, node: ASTNode) {
    val text = remember(content, node) {
        PiHtml.blockTextAt(content, node.startOffset, node.endOffset)
    }
    if (text.isEmpty() || isPureHtmlComment(text)) return
    if (isSingleSvgRoot(text)) {
        PiSvgFenceView(source = text, settled = !LocalPiMarkdownStreaming.current)
        return
    }
    PiHtmlSegments(text = text, settled = !LocalPiMarkdownStreaming.current)
}

/** Per CommonMark/GFM, bare HTML comments (`<!-- ... -->`) are non-visible and should not render fallback cards. */
private fun isPureHtmlComment(raw: String): Boolean {
    var i = 0
    val n = raw.length
    var sawComment = false
    while (i < n) {
        while (i < n && raw[i].isWhitespace()) i++
        if (i >= n) break
        if (!raw.startsWith("<!--", i)) return false
        val end = raw.indexOf("-->", i + 4)
        if (end < 0) return false
        sawComment = true
        i = end + 3
    }
    return sawComment
}

@Composable
private fun PiFormulaBlock(content: String, node: ASTNode, display: Boolean) {
    val raw = remember(content, node) { safeSlice(content, node) }
    val tex = remember(raw) { piMathTex(raw) }
    val fallback = remember(content, raw, display) {
        PiLatex.formulaText(content = content, text = raw, block = display, fallback = tr("（公式）", "(formula)"))
    }
    PiMathView(
        source = tex,
        display = display,
        settled = !LocalPiMarkdownStreaming.current,
        fallbackText = fallback,
    )
}

private fun piMathTex(raw: String): String {
    var text = raw.trim()
    for ((open, close) in listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")) {
        if (text.length >= open.length + close.length && text.startsWith(open) && text.endsWith(close)) {
            text = text.substring(open.length, text.length - close.length).trim()
            break
        }
    }
    return text
}

// ------------------------------------------------------------------ Fenced & Indented Code Blocks

@Composable
private fun PiCodeFence(
    content: String,
    node: ASTNode,
    typography: PiMarkdownTypography,
) {
    val streaming = LocalPiMarkdownStreaming.current
    val fenceSlice = safeSlice(content, node)
    val extracted = remember(fenceSlice) { extractCodeFence(content, node) }
    val settled = !streaming || extracted.closed
    PiCodeSurface(
        code = extracted.code,
        language = extracted.language,
        style = typography.code,
        settled = settled,
    )
}

@Composable
private fun PiIndentedCodeBlock(
    content: String,
    node: ASTNode,
    typography: PiMarkdownTypography,
) {
    val blockSlice = safeSlice(content, node)
    val code = remember(blockSlice) { extractIndentedCodeBlock(content, node) }
    PiCodeSurface(
        code = code,
        language = null,
        style = typography.code,
        settled = !LocalPiMarkdownStreaming.current,
    )
}

internal data class ExtractedCodeFence(
    val language: String?,
    val code: String,
    val closed: Boolean,
)

/**
 * Extracts fenced code body strictly from `CODE_FENCE_CONTENT` and `EOL` AST tokens so fences
 * nested inside blockquotes (`> ```kotlin`) or lists never leak leading `> ` prefixes into code.
 */
internal fun extractCodeFence(content: String, node: ASTNode): ExtractedCodeFence {
    val startNode = node.findChildOfType(MarkdownTokenTypes.CODE_FENCE_START)
    val langNode = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
    val language = langNode?.let { safeSlice(content, it).trim().ifEmpty { null } }
    val closeNode = node.children.lastOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }

    // CommonMark §4.5: compute the opening fence's column (after any leading blockquote markers)
    // so both top-level indented fences and list-nested fences strip up to the exact baseline indent
    // without touching deliberate deeper indentation inside the code body.
    val fenceOpenCol = startNode?.let { openingFenceDelimiterColumn(content, it) } ?: 0

    var seenFirstEol = false
    val sb = StringBuilder()
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_START,
            MarkdownTokenTypes.FENCE_LANG,
            MarkdownTokenTypes.BLOCK_QUOTE,
            MarkdownTokenTypes.WHITE_SPACE -> Unit
            MarkdownTokenTypes.CODE_FENCE_END -> break
            MarkdownTokenTypes.EOL -> {
                if (!seenFirstEol) {
                    seenFirstEol = true
                } else {
                    sb.append('\n')
                }
            }
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                seenFirstEol = true
                val line = safeSlice(content, child)
                if (fenceOpenCol > 0) {
                    val alreadyEatenCols = columnsAfterQuotePrefix(content, child.startOffset)
                    val remainingToStrip = (fenceOpenCol - alreadyEatenCols).coerceAtLeast(0)
                    sb.append(if (remainingToStrip > 0) stripLeadingIndentColumns(line, remainingToStrip) else line)
                } else {
                    sb.append(line)
                }
            }
            else -> if (seenFirstEol) sb.append(safeSlice(content, child))
        }
    }
    // Strip the single trailing newline immediately preceding the closing fence marker.
    val raw = sb.toString()
    val code = if (closeNode != null && raw.endsWith('\n')) raw.dropLast(1) else raw
    return ExtractedCodeFence(language = language, code = code, closed = closeNode != null)
}

/** Column of the opening fence delimiter (` ``` ` / `~~~`) measured after any blockquote `>` prefix. */
private fun openingFenceDelimiterColumn(content: String, startNode: ASTNode): Int {
    val start = startNode.startOffset.coerceIn(0, content.length)
    val end = startNode.endOffset.coerceIn(start, content.length)
    var delimPos = start
    while (delimPos < end && (content[delimPos] == ' ' || content[delimPos] == '\t')) {
        delimPos++
    }
    return columnsAfterQuotePrefix(content, delimPos)
}

/** Visual column of [offset] relative to the end of any blockquote `> ` prefix on its line. */
private fun columnsAfterQuotePrefix(content: String, offset: Int): Int {
    val safeOffset = offset.coerceIn(0, content.length)
    var lineStart = safeOffset
    while (lineStart > 0 && content[lineStart - 1] != '\n') lineStart--
    var i = lineStart
    var cols = 0
    // Skip any leading blockquote markers (`> `) on this line; reset column counter after the last `>`.
    while (i < safeOffset) {
        when (content[i]) {
            ' ' -> {
                cols++
                i++
            }
            '\t' -> {
                cols += 4 - (cols % 4)
                i++
            }
            '>' -> {
                i++
                // CommonMark §5.1: at most one optional space after `>` belongs to the quote marker.
                if (i < safeOffset && content[i] == ' ') i++
                cols = 0
            }
            else -> {
                // Non-quote characters before fence (e.g. list marker `- ` on same line): count their width.
                cols++
                i++
            }
        }
    }
    return cols
}

private fun stripLeadingIndentColumns(line: String, maxCols: Int): String {
    if (maxCols <= 0 || line.isEmpty()) return line
    var i = 0
    var cols = 0
    while (i < line.length && cols < maxCols) {
        when (line[i]) {
            ' ' -> {
                cols++
                i++
            }
            '\t' -> {
                val tabWidth = 4 - (cols % 4)
                if (cols + tabWidth <= maxCols) {
                    cols += tabWidth
                    i++
                } else {
                    val remain = (cols + tabWidth) - maxCols
                    return " ".repeat(remain) + line.substring(i + 1)
                }
            }
            else -> break
        }
    }
    return if (i == 0) line else line.substring(i)
}

internal fun extractIndentedCodeBlock(content: String, node: ASTNode): String {
    val sb = StringBuilder()
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.BLOCK_QUOTE -> Unit
            MarkdownTokenTypes.EOL -> sb.append('\n')
            MarkdownTokenTypes.CODE_LINE -> {
                val line = safeSlice(content, child)
                sb.append(
                    when {
                        line.startsWith("    ") -> line.substring(4)
                        line.startsWith("\t") -> line.substring(1)
                        else -> line
                    },
                )
            }
            else -> sb.append(safeSlice(content, child))
        }
    }
    return sb.toString().trimEnd('\n')
}

private fun stripBlockQuotePrefixes(text: String): String {
    if (!text.contains('>')) return text
    return text.lineSequence().joinToString("\n") { line ->
        var p = 0
        while (p < line.length) {
            var q = p
            while (q < line.length && q - p < 3 && line[q] == ' ') q++
            if (q < line.length && line[q] == '>') {
                p = q + 1
                if (p < line.length && line[p] == ' ') p++
            } else {
                break
            }
        }
        line.substring(p)
    }
}

@Composable
private fun PiCodeSurface(
    code: String,
    language: String?,
    style: TextStyle,
    settled: Boolean = true,
) {
    val palette = PiTheme.palette
    val info = remember(language, code, settled) {
        val parsed = FenceInfo.parse(language)
        val effective = when {
            parsed.language.isNotEmpty() -> app.pi.ui.render.rich.PiFenceSniff.alias(parsed.language)
            settled -> app.pi.ui.render.rich.PiFenceSniff.sniff(code) { text ->
                PiJsonTree.parse(text, settled = true) is JsonTreeResult.Tree
            }
            else -> null
        }
        if (effective != null && effective != parsed.language) parsed.copy(language = effective) else parsed
    }
    val normalized = info.language.takeIf { it.isNotEmpty() }
    val kind = remember(info, code, settled) { RichBlock.classify(info, code, settled) }

    val mermaidMode = rememberPiMermaidMode()
    if (normalized?.lowercase() == MERMAID_LANGUAGE && mermaidMode != PiMermaidMode.Off) {
        val mermaidSettled = if (mermaidMode == PiMermaidMode.Final) !LocalPiMarkdownStreaming.current else settled
        PiMermaidView(source = code, settled = mermaidSettled) {
            PiMermaidAsciiFallback(code = code, style = style)
        }
        return
    }
    val csvFormat = normalized?.let { PiCsvTable.formatForLanguage(it) }
    if (csvFormat != null && settled) {
        val csvLimits = PiCsvTable.DEFAULT_LIMITS
        val csvParsed = rememberTreeParse("csv-$csvFormat-${csvLimits.hashCode()}", code, settled) {
            PiCsvTable.parse(code, csvFormat, settled, csvLimits)
        }
        if (csvParsed is CsvParseResult.Table) {
            PiRichCardFrame(code, info, palette) {
                PiCsvTableView(
                    source = code,
                    format = csvFormat,
                    settled = settled,
                    initialMode = PiTreeMode.Tree,
                )
            }
            return
        }
    }
    if (normalized?.lowercase() in STACK_TRACE_LANGUAGES) {
        PiStackTraceView(source = code, settled = settled)
        return
    }
    if (normalized?.lowercase() == SVG_LANGUAGE) {
        PiSvgFenceView(source = code, settled = settled)
        return
    }
    if (normalized?.lowercase() == "math" && (code.isNotBlank() || !settled)) {
        val trimmed = code.trim()
        val src = when {
            trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 ->
                trimmed.removePrefix("$$").removeSuffix("$$").trim()
            trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 ->
                trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
            else -> trimmed
        }
        val fallback = remember(src) {
            runCatching { PiLatex.toUnicode(src, display = true) }.getOrNull() ?: src
        }
        PiMathView(source = src, display = true, settled = settled, fallbackText = fallback)
        return
    }
    when (kind) {
        RichBlockKind.Json -> {
            val parsed = rememberTreeParse("json", code, settled) { PiJsonTree.parse(code, settled) }
            if (parsed is JsonTreeResult.Tree) {
                PiRichCardFrame(code, info, palette) {
                    PiJsonTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
                }
                return
            }
        }
        RichBlockKind.Xml -> {
            val parsed = rememberTreeParse("xml", code, settled) { PiXmlTreeParser.parse(code, settled) }
            if (parsed is XmlTreeResult.Tree) {
                PiRichCardFrame(code, info, palette) {
                    PiXmlTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
                }
                return
            }
        }
        RichBlockKind.Yaml -> {
            val parsed = rememberTreeParse("yaml", code, settled) { PiYamlTree.parse(code, settled) }
            if (parsed is YamlTreeResult.Tree) {
                PiRichCardFrame(code, info, palette) {
                    PiYamlTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
                }
                return
            }
        }
        else -> Unit
    }
    val highlighted = rememberPiHighlightedCode(code, normalized)
    if (settled && code.isNotBlank() && (normalized?.lowercase() == "html" || normalized?.lowercase() == "htm")) {
        PiHtmlFence(code, info, highlighted, style, palette)
        return
    }
    PiRichCodeBlock(
        code = code,
        info = info,
        highlighted = highlighted,
        style = style,
        palette = palette,
    )
}

@Composable
private fun PiMermaidAsciiFallback(code: String, style: TextStyle) {
    val palette = PiTheme.palette
    Text(
        text = code,
        style = style.copy(color = palette.mdCode),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ------------------------------------------------------------------ Code Highlighting

internal data class PiHighlightedCode(val text: AnnotatedString, val languageKnown: Boolean)

/**
 * Instant, flicker-free code highlighting shared by Markdown fences and Tool Cards (`ReadBlock` / `WriteBlock`).
 *
 * For all 35+ everyday languages owned natively by [PiFastCodeHighlighter], colors are computed
 * synchronously in <1 ms with zero IPC latency and zero 200 ms repaint flash. For obscure languages
 * outside the native table, falls back asynchronously to [LocalPiCodeHighlighter] (plain, uncoloured by default).
 */
private data class PiCodeHighlightKey(val code: String, val language: String)

private object PiCodeHighlightCache {
    private const val MAX_ENTRIES = 128
    private const val MAX_CHARS = 512 * 1024
    private val map = LinkedHashMap<PiCodeHighlightKey, PiCodeHighlight>(MAX_ENTRIES, 0.75f, true)
    private var totalChars = 0

    @Synchronized
    fun get(key: PiCodeHighlightKey): PiCodeHighlight? = map[key]

    @Synchronized
    fun put(key: PiCodeHighlightKey, value: PiCodeHighlight) {
        if (key.code.length > 64 * 1024) return
        val prev = map.put(key, value)
        if (prev == null) totalChars += key.code.length
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || totalChars > MAX_CHARS) && it.hasNext()) {
            val evicted = it.next()
            totalChars = (totalChars - evicted.key.code.length).coerceAtLeast(0)
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        totalChars = 0
    }
}

internal fun clearPiCodeHighlightCache() {
    PiCodeHighlightCache.clear()
}

@Composable
internal fun rememberPiHighlightedCode(code: String, language: String?): PiHighlightedCode {
    val palette = PiTheme.palette
    val unspecified = PiCodeLanguage.isUnspecified(language)
    val nativeResult = remember(code, language, unspecified) {
        if (unspecified) {
            PiCodeHighlight()
        } else {
            val key = PiCodeHighlightKey(code, language!!.lowercase())
            PiCodeHighlightCache.get(key) ?: PiFastCodeHighlighter.highlight(code, language).also {
                PiCodeHighlightCache.put(key, it)
            }
        }
    }
    return remember(code, nativeResult, palette) {
        PiHighlightedCode(
            text = buildPiCodeText(code, nativeResult.spans, palette),
            languageKnown = nativeResult.languageKnown,
        )
    }
}

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
            PiSyntaxToken.Link -> SpanStyle(color = palette.mdLink, textDecoration = TextDecoration.Underline)
            PiSyntaxToken.Comment -> SpanStyle(
                color = palette.syntaxComment,
                fontStyle = FontStyle.Italic,
            )
            PiSyntaxToken.Keyword -> SpanStyle(
                color = palette.syntaxKeyword,
                fontWeight = FontWeight.Medium,
            )
            else -> span.token.color(palette)?.let { SpanStyle(color = it) } ?: SpanStyle()
        }
        addStyle(style, start, end)
    }
}

// ------------------------------------------------------------------ Mermaid Mode & Helpers

@Composable
private fun rememberPiMermaidMode(): PiMermaidMode {
    val context = LocalContext.current
    val epoch = PiMermaidModeSource.epoch
    return remember(context, epoch) { PiMermaidModeSource.mode(context) }
}

internal object PiMermaidModeSource {
    private class Cached(
        val globalFile: File,
        val projectFile: File,
        val stamp: String,
        val mode: PiMermaidMode,
    )

    private val lock = Any()
    private var cached: Cached? = null
    private val _epoch = mutableIntStateOf(0)
    val epoch: Int get() = _epoch.intValue

    fun invalidate() {
        synchronized(lock) { cached = null }
        _epoch.intValue += 1
    }

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

private fun piMermaidModeOf(raw: String?): PiMermaidMode = when (raw?.trim()?.lowercase()) {
    "off" -> PiMermaidMode.Off
    "final" -> PiMermaidMode.Final
    else -> PiMermaidMode.Streaming
}

private fun countDisplayDelimiters(text: String): Int {
    var count = 0
    var index = text.indexOf("$$")
    while (index >= 0) {
        count++
        index = text.indexOf("$$", index + 2)
    }
    return count
}

private fun isSingleSvgRoot(text: String): Boolean {
    val head = text.trimStart()
    if (!head.startsWith("<svg", ignoreCase = true)) return false
    if (head.length > 4 && !(head[4].isWhitespace() || head[4] == '>' || head[4] == '/')) return false
    val close = head.lastIndexOf("</svg>", ignoreCase = true)
    return close < 0 || head.substring(close + 6).isBlank()
}

private const val MERMAID_LANGUAGE = "mermaid"
private const val SVG_LANGUAGE = "svg"
private val STACK_TRACE_LANGUAGES = setOf("stacktrace", "stack", "stack-trace", "traceback")
private val WHITESPACE = Regex("\\s+")
private const val MERMAID_SETTING_KEY = "markdown.mermaid"
private const val BARE_URL_MAX_CHARS = 8192
