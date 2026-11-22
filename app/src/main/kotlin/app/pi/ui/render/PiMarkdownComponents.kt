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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Announcement
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.bridge.PiImageLoader
import app.pi.bridge.PiNoOpImageLoader
import app.pi.bridge.rememberPiGuestImageTransformer
import app.pi.highlight.PiMermaidMode
import app.pi.highlight.PiMermaidReply
import app.pi.highlight.PiNodeMermaidRenderer
import app.pi.i18n.tr
import app.pi.protocol.PiImage
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.render.rich.LinkCardResult
import app.pi.ui.render.rich.PiCsvTable
import app.pi.ui.render.rich.PiCsvTableView
import app.pi.ui.render.rich.PiJsonTreeView
import app.pi.ui.render.rich.PiLinkCard
import app.pi.ui.render.rich.PiLinkPreview
import app.pi.ui.render.rich.PiStackTraceView
import app.pi.ui.render.rich.PiTreeMode
import app.pi.ui.render.rich.PiXmlTreeView
import app.pi.ui.render.rich.PiYamlTreeView
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
            key(index, child.type, child.startOffset) {
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
        MarkdownElementTypes.BLOCK_QUOTE -> PiBlockQuoteOrAlert(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            spacing = spacing,
            referenceLinks = referenceLinks,
            eolAsNewLine = eolAsNewLine,
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
        )
        GFMElementTypes.TABLE -> PiTable(
            content = content,
            node = node,
            colors = colors,
            typography = typography,
            referenceLinks = referenceLinks,
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
    val classic = PiTheme.typography.isClassic
    val style = when (level) {
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
    val skipTop = classic || isFirstBlock || headingFollowsHeading(node)
    val topPad = if (skipTop) 0.dp else extraTop
    val showRule = !classic && level == 1

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

    val trimmed = remember(content, node) { safeSlice(content, node).trim() }

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
                val decoded = LocalPiImageTransformer.current.transform(linkInfo.url)
                if (decoded != null) {
                    PiImageViewerTap(linkInfo.url) {
                        val imageShape = RoundedCornerShape(10.dp)
                        Image(
                            painter = decoded.painter,
                            contentDescription = decoded.contentDescription,
                            contentScale = decoded.contentScale,
                            modifier = decoded.modifier
                                .padding(vertical = 4.dp)
                                .clip(imageShape),
                        )
                    }
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

private enum class PiAlertKind(val marker: String) {
    Note("[!NOTE]"),
    Tip("[!TIP]"),
    Important("[!IMPORTANT]"),
    Warning("[!WARNING]"),
    Caution("[!CAUTION]"),
}

private data class DetectedAlert(
    val kind: PiAlertKind,
    val firstParagraph: ASTNode,
    val skipCharsInFirstParagraph: Int,
    val firstParagraphHasRemainingBody: Boolean,
)

private fun detectGfmAlert(content: String, quoteNode: ASTNode): DetectedAlert? {
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
        hasMore = raw.substring(after).isNotBlank()
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
) {
    val alert = remember(content, node) { detectGfmAlert(content, node) }
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
            icon = Icons.Outlined.Announcement
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
                child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.WHITE_SPACE
            ) return@forEachIndexed
            key(index, child.type, child.startOffset) {
                if (child === alert.firstParagraph) {
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
) {
    val quoteColor = colors.quoteText
    val railColor = colors.quoteBorder.copy(alpha = 0.78f)
    val quoteTypo = remember(typography, quoteColor) {
        typography.copy(
            text = typography.text.copy(color = quoteColor),
            quote = typography.quote.copy(color = quoteColor),
            paragraph = typography.paragraph.copy(color = quoteColor),
            ordered = typography.ordered.copy(color = quoteColor),
            list = typography.list.copy(color = quoteColor),
        )
    }
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    val quoteDesc = tr("引用内容", "Block quote")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
            .drawBehind {
                val railW = 2.5.dp.toPx()
                drawRoundRect(
                    color = railColor,
                    topLeft = Offset.Zero,
                    size = Size(railW, size.height),
                    cornerRadius = CornerRadius(railW / 2f, railW / 2f),
                )
            }
            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = quoteDesc },
    ) {
        var renderedAny = false
        node.children.forEachIndexed { index, child ->
            if (child.type == MarkdownTokenTypes.BLOCK_QUOTE ||
                child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.WHITE_SPACE
            ) return@forEachIndexed
            key(index, child.type, child.startOffset) {
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
            key(idx, item.startOffset) {
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
) {
    val checkboxNode = remember(item) { item.findChildOfType(GFMTokenTypes.CHECK_BOX) }
    val numberText = remember(content, item, ordered, fallbackIndex) {
        if (!ordered) {
            null
        } else {
            val numNode = item.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
            val raw = numNode?.let { safeSlice(content, it).trim() }.orEmpty()
            raw.ifEmpty { "$fallbackIndex." }
        }
    }
    val bodyChildren = remember(item) {
        item.children.filter {
            it.type != MarkdownTokenTypes.LIST_BULLET &&
                it.type != MarkdownTokenTypes.LIST_NUMBER &&
                it.type != GFMTokenTypes.CHECK_BOX &&
                it.type != MarkdownTokenTypes.EOL &&
                it.type != MarkdownTokenTypes.WHITE_SPACE
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Leading marker: checkbox, ordered numeral, or optical depth-aware bullet
        if (checkboxNode != null) {
            val checked = remember(content, checkboxNode) {
                piTaskCheckboxChecked(safeSlice(content, checkboxNode))
            }
            Box(
                modifier = Modifier.padding(top = 3.dp, end = TASK_CHECKBOX_END_PADDING),
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
                ),
                modifier = Modifier
                    .widthIn(min = 22.dp)
                    .padding(end = 6.dp),
            )
        } else {
            val glyph = when (depth % 3) {
                0 -> "•"
                1 -> "◦"
                else -> "▪"
            }
            Text(
                text = glyph,
                style = typography.bullet,
                modifier = Modifier
                    .widthIn(min = 16.dp)
                    .padding(end = 4.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            bodyChildren.forEachIndexed { idx, child ->
                key(idx, child.type, child.startOffset) {
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
    val classic = PiTheme.typography.isClassic
    Box(Modifier.padding(vertical = if (classic) 0.dp else 8.dp)) {
        HorizontalDivider(
            thickness = if (classic) 1.dp else 0.5.dp,
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
    val palette = PiTheme.palette
    val imageLoader = LocalPiImageTransformer.current
    val link = remember(content, node, referenceLinks) {
        resolveAstImageLink(content, node, referenceLinks)
    }
    val alt = remember(content, node) { resolveAstImageAlt(content, node) }

    piMediaKind(link)?.let { kind ->
        PiMediaCard(link!!, kind)
        return
    }

    val decoded = if (!link.isNullOrBlank()) imageLoader.transform(link) else null
    if (decoded != null) {
        val shape = RoundedCornerShape(10.dp)
        PiImageViewerTap(link) {
            val ratioModifier = decoded.widthOverHeight
                ?.takeIf { it.isFinite() && it in 0.1f..10f }
                ?.let { Modifier.aspectRatio(it) }
                ?: Modifier
            Image(
                painter = decoded.painter,
                contentDescription = alt ?: decoded.contentDescription,
                contentScale = decoded.contentScale,
                modifier = decoded.modifier
                    .then(ratioModifier)
                    .padding(vertical = 4.dp)
                    .clip(shape)
                    .border(0.5.dp, palette.borderMuted.copy(alpha = 0.35f), shape),
            )
        }
        return
    }
    PiImageFailureCard(
        alt = alt,
        link = link,
        altStyle = typography.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
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
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val headerNode = remember(node) { node.findChildOfType(GFMElementTypes.HEADER) }
    val rowNodes = remember(node) { node.children.filter { it.type == GFMElementTypes.ROW } }
    val columns = remember(headerNode) {
        headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0
    }
    if (headerNode == null || columns <= 0) return

    val alignments = remember(content, node) { piTableAlignments(content, node) }
    val columnDisplayChars = remember(content, headerNode, rowNodes, columns) {
        val maxChars = IntArray(columns)
        fun sampleRow(r: ASTNode) {
            val cells = r.children.filter { it.type == GFMTokenTypes.CELL }
            for (c in 0 until minOf(columns, cells.size)) {
                val w = estimateTableCellDisplayChars(safeSlice(content, cells[c]))
                if (w > maxChars[c]) maxChars[c] = w
            }
        }
        sampleRow(headerNode)
        val sampleLimit = minOf(rowNodes.size, 12)
        for (i in 0 until sampleLimit) sampleRow(rowNodes[i])
        maxChars.toList()
    }

    val available = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtLeast(220.dp)
    val useIntrinsicRowHeight = rowNodes.size <= 24

    val colWidthsDp = remember(columnDisplayChars, available) {
        computePiTableColumnWidthsDp(
            columnDisplayChars = columnDisplayChars,
            availableDp = available.value,
            charWidthDp = 8.2f,
            horizontalPaddingDp = 24f,
            minColDp = 56f,
            maxColDp = 240f,
        ).map { it.dp }
    }
    val totalWidth = colWidthsDp.fold(0.dp) { acc, w -> acc + w }.coerceAtLeast(available)
    val scrolls = totalWidth > available + 1.dp
    val maxColWidthPx = with(density) {
        (colWidthsDp.maxOfOrNull { it } ?: 120.dp).roundToPx()
    }.coerceAtLeast(1)
    val cellImageLoader = rememberPiGuestImageTransformer(maxColWidthPx)

    val tableShape = RoundedCornerShape(12.dp)
    val scrollState = rememberScrollState()
    val headerBg = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
    val zebraBg = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.28f)
    val borderStrokeColor = palette.borderMuted.copy(alpha = 0.56f)
    val rowHairlineColor = palette.borderMuted.copy(alpha = 0.30f)
    val colHairlineColor = palette.borderMuted.copy(alpha = 0.16f)
    val surfaceBg = MaterialTheme.colorScheme.surfaceContainerLow

    CompositionLocalProvider(LocalPiImageTransformer provides cellImageLoader) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tableShape)
                .background(surfaceBg)
                .border(0.5.dp, borderStrokeColor, tableShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .width(totalWidth),
            ) {
                // Header row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max)
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
                                .fillMaxHeight()
                                .then(columnDividerModifier(col > 0, colHairlineColor))
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
                            .then(if (useIntrinsicRowHeight) Modifier.height(IntrinsicSize.Max) else Modifier)
                            .background(if (rowIdx % 2 == 1) zebraBg else Color.Transparent),
                    ) {
                        for (col in 0 until columns) {
                            val colW = colWidthsDp[col]
                            val cell = rowCells.getOrNull(col)
                            val align = alignments.alignAt(col)
                            Box(
                                modifier = Modifier
                                    .width(colW)
                                    .then(if (useIntrinsicRowHeight) Modifier.fillMaxHeight() else Modifier)
                                    .then(columnDividerModifier(col > 0, colHairlineColor))
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

            if (scrolls && scrollState.value < scrollState.maxValue) {
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(TABLE_FADE_WIDTH)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, surfaceBg))),
                    )
                }
            }
        }
    }
}

private fun columnDividerModifier(show: Boolean, color: Color): Modifier =
    if (!show) {
        Modifier
    } else {
        Modifier.drawBehind {
            drawLine(color, Offset(0f, 0f), Offset(0f, size.height), 0.5.dp.toPx())
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
    if (text.isEmpty()) return
    if (isSingleSvgRoot(text)) {
        PiSvgFenceView(source = text, settled = !LocalPiMarkdownStreaming.current)
        return
    }
    PiHtmlSegments(text = text, settled = !LocalPiMarkdownStreaming.current)
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
    val extracted = remember(content, node) { extractCodeFence(content, node) }
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
    val code = remember(content, node) {
        safeSlice(content, node)
            .lines()
            .joinToString("\n") { line ->
                when {
                    line.startsWith("    ") -> line.substring(4)
                    line.startsWith("\t") -> line.substring(1)
                    else -> line
                }
            }
            .trimEnd('\n')
    }
    PiCodeSurface(
        code = code,
        language = null,
        style = typography.code,
        settled = !LocalPiMarkdownStreaming.current,
    )
}

private data class ExtractedCodeFence(
    val language: String?,
    val code: String,
    val closed: Boolean,
)

private fun extractCodeFence(content: String, node: ASTNode): ExtractedCodeFence {
    val langNode = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
    val language = langNode?.let { safeSlice(content, it).trim().ifEmpty { null } }
    val closeNode = node.children.lastOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    val firstEol = node.children.firstOrNull { it.type == MarkdownTokenTypes.EOL }

    if (firstEol == null) {
        return ExtractedCodeFence(language = language, code = "", closed = closeNode != null)
    }
    val bodyStart = firstEol.endOffset.coerceIn(0, content.length)
    val bodyEnd = (closeNode?.startOffset ?: node.endOffset).coerceIn(bodyStart, content.length)
    val rawBody = content.substring(bodyStart, bodyEnd)
    val code = if (rawBody.endsWith("\r\n")) {
        rawBody.dropLast(2)
    } else if (rawBody.endsWith("\n")) {
        rawBody.dropLast(1)
    } else {
        rawBody
    }
    return ExtractedCodeFence(language = language, code = code, closed = closeNode != null)
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
                runCatching {
                    if (text.startsWith("{")) org.json.JSONObject(text) else org.json.JSONArray(text)
                }.isSuccess
            }
            else -> null
        }
        if (effective != null && effective != parsed.language) parsed.copy(language = effective) else parsed
    }
    val normalized = info.language.takeIf { it.isNotEmpty() }
    val kind = remember(info, code, settled) { RichBlock.classify(info, code, settled) }

    val mermaidMode = rememberPiMermaidMode()
    if (normalized?.lowercase() == MERMAID_LANGUAGE && mermaidMode != PiMermaidMode.Off) {
        PiMermaidView(source = code, settled = settled) {
            PiMermaidAsciiFallback(code = code, language = language, style = style)
        }
        return
    }
    val csvFormat = normalized?.let { PiCsvTable.formatForLanguage(it) }
    if (csvFormat != null) {
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
    if (normalized?.lowercase() in STACK_TRACE_LANGUAGES) {
        PiStackTraceView(source = code, settled = settled)
        return
    }
    if (normalized?.lowercase() == SVG_LANGUAGE) {
        PiSvgFenceView(source = code, settled = settled)
        return
    }
    if (normalized?.lowercase() == "math" && settled && code.isNotBlank()) {
        val src = code.trim().removePrefix("$$").removeSuffix("$$").trim()
        val fallback = remember(src) {
            runCatching { PiLatex.toUnicode(src, display = true) }.getOrNull() ?: src
        }
        PiMathView(source = src, display = true, settled = true, fallbackText = fallback)
        return
    }
    when (kind) {
        RichBlockKind.Json -> {
            PiRichCardFrame(code, info, palette) {
                PiJsonTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
            }
            return
        }
        RichBlockKind.Xml -> {
            PiRichCardFrame(code, info, palette) {
                PiXmlTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
            }
            return
        }
        RichBlockKind.Yaml -> {
            PiRichCardFrame(code, info, palette) {
                PiYamlTreeView(source = code, settled = settled, initialMode = PiTreeMode.Tree)
            }
            return
        }
        else -> Unit
    }
    val highlighted = rememberPiHighlightedCode(code, normalized)
    val classic = PiTheme.typography.isClassic
    if (settled && code.isNotBlank() && (normalized?.lowercase() == "html" || normalized?.lowercase() == "htm")) {
        PiHtmlFence(code, info, highlighted, style, palette, classic)
        return
    }
    PiRichCodeBlock(
        code = code,
        info = info,
        highlighted = highlighted,
        style = style,
        palette = palette,
        classic = classic,
    )
}

@Composable
private fun PiMermaidAsciiFallback(code: String, language: String?, style: TextStyle) {
    val palette = PiTheme.palette
    val mermaid = rememberPiMermaidArt(code, language)
    val art = mermaid?.takeIf { it.warnings.isEmpty() }
    if (art != null) {
        Text(
            text = piMermaidText(art, palette),
            style = style.copy(color = palette.mdCode),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    } else {
        Text(
            text = code,
            style = style.copy(color = palette.mdCode),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    mermaid?.let { drawn ->
        piMermaidWarning(drawn)?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelMedium,
                color = palette.warning,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Code Highlighting

internal data class PiHighlightedCode(val text: AnnotatedString, val languageKnown: Boolean)

/**
 * Instant, flicker-free code highlighting shared by Markdown fences and Tool Cards (`ReadBlock` / `WriteBlock`).
 *
 * For all 35+ everyday languages owned natively by [PiFastCodeHighlighter], colors are computed
 * synchronously in <1 ms with zero IPC latency and zero 200 ms repaint flash. For obscure languages
 * outside the native table, falls back asynchronously to [LocalPiCodeHighlighter] (guest engine).
 */
@Composable
internal fun rememberPiHighlightedCode(code: String, language: String?): PiHighlightedCode {
    val palette = PiTheme.palette
    val highlighter = LocalPiCodeHighlighter.current
    val nativeResult = remember(code, language) {
        if (PiCodeLanguage.isUnspecified(language)) PiCodeHighlight()
        else PiFastCodeHighlighter.highlight(code, language)
    }
    val hasStreamed = remember { booleanArrayOf(false) }
    val answer = produceState(
        initialValue = nativeResult,
        code,
        language,
        highlighter,
        nativeResult,
    ) {
        if (PiCodeLanguage.isUnspecified(language)) {
            value = PiCodeHighlight()
        } else if (nativeResult.languageKnown) {
            // Native 35+ language lexer already produced full semantic tokens on frame 0;
            // skip cross-process highlight.js 10.7.3 round-trip to prevent latency & color flicker.
            value = nativeResult
        } else {
            val isUpdate = hasStreamed[0]
            hasStreamed[0] = true
            if (isUpdate) delay(STREAM_SETTLE_MS)
            val nodeResult = withContext(Dispatchers.Default) { highlighter.highlight(code, language) }
            if (nodeResult.languageKnown) {
                value = nodeResult
            }
        }
    }
    return remember(code, answer.value, palette) {
        PiHighlightedCode(
            text = buildPiCodeText(code, answer.value.spans, palette),
            languageKnown = answer.value.languageKnown,
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
            PiSyntaxToken.Link -> SpanStyle(textDecoration = TextDecoration.Underline)
            else -> span.token.color(palette)?.let { SpanStyle(color = it) } ?: SpanStyle()
        }
        addStyle(style, start, end)
    }
}

// ------------------------------------------------------------------ Mermaid Mode & Helpers

@Composable
private fun rememberPiMermaidArt(code: String, language: String?): PiMermaidArt? {
    val mode = rememberPiMermaidMode()
    if (mode == PiMermaidMode.Off) return null
    val isMermaid = remember(language) {
        language?.trim()?.split(WHITESPACE)?.firstOrNull()?.lowercase() == MERMAID_LANGUAGE
    }
    if (!isMermaid) return null
    val hasStreamed = remember { booleanArrayOf(false) }
    val cached = remember(code, mode) { PiNodeMermaidRenderer.cached(code, mode) }
    val art = produceState<PiMermaidArt?>(initialValue = (cached as? PiMermaidReply.Art)?.art, code, language, mode) {
        if (cached != null) return@produceState
        val isUpdate = hasStreamed[0]
        hasStreamed[0] = true
        if (isUpdate || mode == PiMermaidMode.Final) delay(STREAM_SETTLE_MS)
        var attempt = 0
        while (true) {
            when (val reply = withContext(Dispatchers.Default) { PiNodeMermaidRenderer.render(code, mode) }) {
                is PiMermaidReply.Art -> {
                    value = reply.art
                    return@produceState
                }
                PiMermaidReply.NoArt -> {
                    value = null
                    return@produceState
                }
                PiMermaidReply.Unavailable -> {
                    attempt++
                    if (attempt >= MERMAID_ATTEMPTS) {
                        value = null
                        return@produceState
                    }
                    delay(MERMAID_RETRY_MS)
                }
            }
        }
    }
    return art.value
}

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
private const val MERMAID_ATTEMPTS = 3
private const val MERMAID_RETRY_MS = 400L
private const val MERMAID_SETTING_KEY = "markdown.mermaid"
private const val STREAM_SETTLE_MS = 200L
private const val BARE_URL_MAX_CHARS = 8192
