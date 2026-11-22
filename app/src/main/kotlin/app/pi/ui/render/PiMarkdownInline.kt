package app.pi.ui.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.bridge.PiImageLoadState
import app.pi.ui.render.rich.svg.PiMathDraw
import app.pi.ui.render.rich.svg.piMathInlineCached
import app.pi.ui.render.rich.svg.piMathRenderInline
import app.pi.ui.render.rich.svg.piMathThemeFor
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiTheme
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Active inline LaTeX lookup table for the current settled Markdown document (null while streaming). */
internal val LocalPiInlineMathTable = staticCompositionLocalOf<PiInlineMath.Extracted?> { null }

private const val INLINE_CODE_PILL_TAG = "pi_inline_code_pill"
private const val INLINE_CODE_KIND_CODE = "code"
private const val INLINE_CODE_KIND_KBD = "kbd"
private const val INLINE_MATH_BATCH_MS = 240L

/**
 * Extracts all CommonMark `[label]: url` link definitions from a parsed document AST into an
 * immutable normalized map, so reference links work cleanly across every inline surface without
 * mutable shared state.
 */
internal fun extractPiReferenceLinks(content: String, root: ASTNode): Map<String, String> {
    val map = HashMap<String, String>()
    fun walk(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val labelNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val destNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            if (labelNode != null && destNode != null) {
                val key = normalizeReferenceLabel(safeSlice(content, labelNode))
                val dest = safeSlice(content, destNode).trim().removePrefix("<").removeSuffix(">")
                if (key.isNotEmpty() && dest.isNotEmpty() && key !in map) {
                    map[key] = dest
                }
            }
            return
        }
        for (child in node.children) walk(child)
    }
    walk(root)
    return map
}

internal fun normalizeReferenceLabel(raw: String): String =
    raw.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trim()
        .replace(REF_WS_REGEX, " ")
        .lowercase()

private val REF_WS_REGEX = Regex("\\s+")
private val HTML_HREF_REGEX = Regex("""(?i)\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
private val CODE_SPAN_QUOTE_BREAK_REGEX = Regex("""\r?\n[ ]{0,3}(?:>[ ]?)+""")

/**
 * Unified native inline Markdown renderer for paragraphs, headings, lists, blockquotes,
 * callouts, and table cells.
 *
 * Supports:
 *  - Bold, italic, strikethrough, rounded inline code & kbd pills (zero copy-text pollution),
 *    links (inline, reference, autolink, and safe HTML `<a href="...">`)
 *  - First-class inline LaTeX (`$...$` / `\(...\)`) rendered as vector MathJax SVGs with
 *    exact optical scaling & baseline alignment (with instant `PiLatex.toUnicode` pre-render)
 *  - Inline images (`![alt](url)`) decoded via 3-state [LocalPiImageTransformer] (smooth loading
 *    tonal placeholder -> crisp image with tap-to-viewer -> failure card)
 *  - Safe inline HTML (`a`, `b`, `strong`, `i`, `em`, `del`, `s`, `u`, `code`, `kbd`, `mark`,
 *    `sup`, `sub`, `small`, `br`, `span`, comments stripped, mismatched/unclosed tags never crash)
 *  - Content-slice keyed caching so completed blocks above a streaming tail skip rebuilds in O(1)
 */
@Composable
internal fun PiInlineMarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
    referenceLinks: Map<String, String>,
    modifier: Modifier = Modifier,
    eolAsNewLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    skipLeadingPrefixChars: Int = 0,
    trimLeadingWhitespace: Boolean = false,
    cellImageMaxWidthDp: Float? = null,
) {
    val palette = PiTheme.palette
    val inlineMath = LocalPiInlineMathTable.current
    val imageLoader = LocalPiImageTransformer.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val fgColor = if (style.color.alpha > 0f) style.color else colors.text
    val resolvedStyle = if (style.color.alpha > 0f) style else style.copy(color = fgColor)

    // Key inline work on the block's exact source slice instead of full document `content` + `node`,
    // so settled paragraphs above a streaming tail achieve 100% cache hits on every token.
    val rawSlice = safeSlice(content, node)
    val nodeType = node.type

    // Inline math indices present inside this AST subtree.
    val neededMathIndices = remember(rawSlice, inlineMath) {
        if (inlineMath == null || rawSlice.indexOf(inlineMath.markerPrefix) < 0) {
            emptyList()
        } else {
            PiInlineMath.splitSegments(rawSlice, inlineMath.markerPrefix, inlineMath.markerSuffix)
                .mapNotNull { (it as? PiInlineMath.Segment.MathRef)?.index }
                .distinct()
        }
    }

    val widthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
            .roundToInt()
            .coerceIn(240, 1600)
    }
    val mathTheme = remember(palette, fgColor) {
        piMathThemeFor(
            background = palette.pageBg,
            foreground = fgColor,
            accent = palette.accent,
            muted = palette.muted,
        )
    }

    var mathEpoch by remember(rawSlice, mathTheme) { mutableIntStateOf(0) }
    LaunchedEffect(neededMathIndices, widthPx, mathTheme, inlineMath) {
        val entries = inlineMath?.entries ?: return@LaunchedEffect
        if (neededMathIndices.isEmpty()) return@LaunchedEffect
        var lastBumpMs = android.os.SystemClock.uptimeMillis()
        var dirty = false
        for (idx in neededMathIndices) {
            val entry = entries.getOrNull(idx) ?: continue
            if (piMathInlineCached(entry.tex, widthPx, mathTheme) != null) continue
            val rendered = piMathRenderInline(entry.tex, widthPx, mathTheme)
            if (rendered != null) {
                dirty = true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastBumpMs >= INLINE_MATH_BATCH_MS) {
                    mathEpoch++
                    lastBumpMs = now
                    dirty = false
                }
            }
        }
        if (dirty) mathEpoch++
    }

    val readyMathByIndex = remember(neededMathIndices, widthPx, mathTheme, inlineMath, mathEpoch) {
        val entries = inlineMath?.entries
        if (entries == null || neededMathIndices.isEmpty()) {
            emptyMap()
        } else {
            val map = HashMap<Int, PiMathDraw.Ready>(neededMathIndices.size)
            for (idx in neededMathIndices) {
                val entry = entries.getOrNull(idx) ?: continue
                piMathInlineCached(entry.tex, widthPx, mathTheme)?.let { map[idx] = it }
            }
            map
        }
    }

    // Collect inline images inside this subtree using slice-relative offsets for stable caching.
    val inlineImages = remember(rawSlice, nodeType, referenceLinks) {
        collectInlineImages(content, node, referenceLinks)
    }

    val bodyFontSizeSp = resolvedStyle.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 16f
    val maxInlineWidthSp = remember(configuration.screenWidthDp, density, cellImageMaxWidthDp) {
        val capDp = cellImageMaxWidthDp ?: (configuration.screenWidthDp - 36).coerceAtLeast(160).toFloat()
        with(density) { capDp.dp.toSp().value }
    }

    val annotated = remember(
        rawSlice,
        nodeType,
        inlineMath,
        readyMathByIndex,
        inlineImages,
        typography,
        colors,
        referenceLinks,
        eolAsNewLine,
        skipLeadingPrefixChars,
        trimLeadingWhitespace,
    ) {
        buildAnnotatedString {
            val ctx = InlineBuildContext(
                content = content,
                rootStartOffset = node.startOffset,
                table = inlineMath,
                readyMathIndices = readyMathByIndex.keys,
                imageSlots = inlineImages,
                typography = typography,
                colors = colors,
                referenceLinks = referenceLinks,
                eolAsNewLine = eolAsNewLine,
                skipRemaining = skipLeadingPrefixChars,
                trimLeading = trimLeadingWhitespace,
            )
            appendInlineChildren(node, ctx)
        }
    }

    // Extract inline code / kbd pill spans once per rebuilt AnnotatedString (zero work on prose-only blocks).
    val codePillRanges = remember(annotated) {
        annotated.getStringAnnotations(INLINE_CODE_PILL_TAG, 0, annotated.length)
    }

    // Build unified InlineTextContent map (math vector slots + 3-state inline image slots).
    val inlineContent: Map<String, InlineTextContent> = if (readyMathByIndex.isEmpty() && inlineImages.isEmpty()) {
        emptyMap()
    } else {
        val map = HashMap<String, InlineTextContent>(readyMathByIndex.size + inlineImages.size)

        // 1. Inline MathJax vector slots
        val entries = inlineMath?.entries
        if (entries != null) {
            for ((idx, ready) in readyMathByIndex) {
                val entry = entries.getOrNull(idx) ?: continue
                val metrics = computeInlineMathBoxSp(ready, bodyFontSizeSp, maxInlineWidthSp)
                val desc = entry.unicodeFallback.ifBlank { entry.tex }
                map[inlineMathSlotId(idx)] = InlineTextContent(
                    placeholder = Placeholder(
                        width = metrics.widthSp.sp,
                        height = metrics.heightSp.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = desc },
                    ) {
                        val padX = metrics.horizontalPadFraction * size.width
                        val drawAreaW = (size.width - padX * 2f).coerceAtLeast(1f)
                        val drawAreaH = (size.height * metrics.contentHeightFraction).coerceAtLeast(1f)
                        val fit = min(
                            drawAreaW / ready.baseWidthPx.coerceAtLeast(1),
                            drawAreaH / ready.baseHeightPx.coerceAtLeast(1),
                        )
                        val drawnW = ready.baseWidthPx * fit
                        val drawnH = ready.baseHeightPx * fit
                        val targetBaselineY = size.height / 2f + metrics.baselineOffsetFromCenterFraction * size.height
                        val topY = (targetBaselineY - drawnH * ready.baselineRatio)
                            .coerceIn(0f, (size.height - drawnH).coerceAtLeast(0f))
                        val leftX = (size.width - drawnW) / 2f
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(0f, 0f, size.width, size.height)
                            native.translate(leftX, topY)
                            native.scale(fit, fit)
                            native.drawPicture(ready.picture)
                            native.restore()
                        }
                    }
                }
            }
        }

        // 2. Inline image slots (3-state: Loading skeleton -> Ready image -> Failure card)
        for (img in inlineImages) {
            val mediaKind = piMediaKind(img.url)
            val loadState = if (mediaKind == null && img.url.isNotBlank()) {
                imageLoader.loadState(img.url)
            } else {
                PiImageLoadState.Failed
            }
            val targetWSp = cellImageMaxWidthDp?.let { with(density) { it.dp.toSp().value } }
                ?: min(maxInlineWidthSp, bodyFontSizeSp * 12f)
            val readyData = (loadState as? PiImageLoadState.Ready)?.data
            val aspectHOverW = when (loadState) {
                is PiImageLoadState.Ready -> {
                    val woh = loadState.data.widthOverHeight
                    if (woh != null && woh > 0f) (1f / woh).coerceIn(0.15f, 4f) else 0.65f
                }
                is PiImageLoadState.Loading ->
                    (loadState.heightOverWidth ?: 0.62f).coerceIn(0.25f, 2.5f)
                PiImageLoadState.Failed -> 0.35f
            }
            val boxW = when {
                mediaKind != null -> targetWSp
                loadState is PiImageLoadState.Ready || loadState is PiImageLoadState.Loading -> targetWSp
                else -> min(targetWSp, bodyFontSizeSp * 10f)
            }
            val boxH = when {
                mediaKind != null -> bodyFontSizeSp * 3.4f
                loadState is PiImageLoadState.Ready || loadState is PiImageLoadState.Loading ->
                    (boxW * aspectHOverW).coerceIn(bodyFontSizeSp * 1.2f, bodyFontSizeSp * 18f)
                else -> bodyFontSizeSp * 2.2f
            }

            map[img.slotId] = InlineTextContent(
                placeholder = Placeholder(
                    width = boxW.sp,
                    height = boxH.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) {
                when {
                    mediaKind != null -> PiMediaCard(img.url, mediaKind)
                    readyData != null -> {
                        PiImageViewerTap(img.url) {
                            Image(
                                painter = readyData.painter,
                                contentDescription = img.alt.ifBlank { readyData.contentDescription },
                                contentScale = readyData.contentScale,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                        }
                    }
                    loadState is PiImageLoadState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.cardBg),
                        )
                    }
                    else -> {
                        Box(Modifier.fillMaxSize()) {
                            PiImageFailureCard(
                                alt = img.alt.ifBlank { null },
                                link = img.url.ifBlank { null },
                                altStyle = typography.text,
                            )
                        }
                    }
                }
            }
        }
        map
    }

    if (codePillRanges.isEmpty()) {
        Text(
            text = annotated,
            style = resolvedStyle,
            inlineContent = inlineContent,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
    } else {
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val pillBg = colors.inlineCodeBackground
        val kbdBorder = colors.dividerColor.copy(alpha = 0.85f)
        Text(
            text = annotated,
            style = resolvedStyle,
            inlineContent = inlineContent,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { textLayout = it },
            modifier = modifier.drawBehind {
                val layout = textLayout ?: return@drawBehind
                val textLen = layout.layoutInput.text.length
                val padX = 2.dp.toPx()
                val padY = 0.75.dp.toPx()
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                val kbdStroke = Stroke(width = 0.75.dp.toPx())

                for (ann in codePillRanges) {
                    val start = ann.start.coerceIn(0, textLen)
                    val end = ann.end.coerceIn(start, textLen)
                    if (start >= end) continue
                    val startLine = layout.getLineForOffset(start)
                    val endLine = layout.getLineForOffset(end - 1)
                    val isKbd = ann.item == INLINE_CODE_KIND_KBD

                    for (line in startLine..endLine) {
                        val segStart = if (line == startLine) start else layout.getLineStart(line)
                        val lineVisibleEnd = layout.getLineEnd(line, visibleEnd = true)
                        val segEnd = min(if (line == endLine) end else lineVisibleEnd, textLen)
                        if (segStart >= segEnd) continue
                        val firstBox = layout.getBoundingBox(segStart)
                        val lastBox = layout.getBoundingBox(segEnd - 1)
                        val left = (min(firstBox.left, lastBox.left) - padX).coerceAtLeast(0f)
                        val right = (max(firstBox.right, lastBox.right) + padX).coerceAtMost(size.width)
                        val top = layout.getLineTop(line) + padY
                        val bottom = layout.getLineBottom(line) - padY
                        val w = right - left
                        val h = bottom - top
                        if (w <= 0f || h <= 0f) continue
                        val topLeft = Offset(left, top)
                        val pillSize = Size(w, h)
                        drawRoundRect(
                            color = pillBg,
                            topLeft = topLeft,
                            size = pillSize,
                            cornerRadius = radius,
                        )
                        if (isKbd) {
                            drawRoundRect(
                                color = kbdBorder,
                                topLeft = topLeft,
                                size = pillSize,
                                cornerRadius = radius,
                                style = kbdStroke,
                            )
                        }
                    }
                }
            },
        )
    }
}

// ------------------------------------------------------------------ Inline AST builder

private data class InlineImageSlot(
    val slotId: String,
    val relativeStart: Int,
    val url: String,
    val alt: String,
)

private fun collectInlineImages(
    content: String,
    root: ASTNode,
    referenceLinks: Map<String, String>,
): List<InlineImageSlot> {
    val result = ArrayList<InlineImageSlot>()
    val base = root.startOffset
    fun walk(node: ASTNode) {
        if (node.type == MarkdownElementTypes.IMAGE) {
            val url = resolveAstImageLink(content, node, referenceLinks).orEmpty()
            val alt = resolveAstImageAlt(content, node).orEmpty()
            val rel = node.startOffset - base
            result.add(
                InlineImageSlot(
                    slotId = "pi_img_${result.size}_$rel",
                    relativeStart = rel,
                    url = url,
                    alt = alt,
                ),
            )
            return
        }
        for (child in node.children) walk(child)
    }
    walk(root)
    return result
}

private class HtmlTagEntry(
    val tag: String,
    val style: SpanStyle,
    val pillKind: String? = null,
)

private class InlineBuildContext(
    val content: String,
    val rootStartOffset: Int,
    val table: PiInlineMath.Extracted?,
    val readyMathIndices: Set<Int>,
    val imageSlots: List<InlineImageSlot>,
    val typography: PiMarkdownTypography,
    val colors: PiMarkdownColors,
    val referenceLinks: Map<String, String>,
    val eolAsNewLine: Boolean,
    var skipRemaining: Int = 0,
    var trimLeading: Boolean = false,
    val htmlTagStack: ArrayList<HtmlTagEntry> = ArrayList(),
    var activeHtmlLinkUrl: String? = null,
)

private fun AnnotatedString.Builder.appendInlineChildren(
    node: ASTNode,
    ctx: InlineBuildContext,
    skipPredicate: ((ASTNode) -> Boolean)? = null,
) {
    val textRun = StringBuilder()
    for (child in node.children) {
        if (skipPredicate != null && skipPredicate(child)) continue
        if (isPlainLeafTextNode(child)) {
            textRun.append(safeSlice(ctx.content, child))
        } else {
            if (textRun.isNotEmpty()) {
                appendProcessedText(textRun.toString(), ctx)
                textRun.setLength(0)
            }
            appendInlineNode(child, ctx)
        }
    }
    if (textRun.isNotEmpty()) {
        appendProcessedText(textRun.toString(), ctx)
    }
}

private fun isPlainLeafTextNode(node: ASTNode): Boolean {
    if (node.children.isNotEmpty()) return false
    return when (node.type) {
        MarkdownTokenTypes.HARD_LINE_BREAK,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.HTML_TAG,
        GFMTokenTypes.GFM_AUTOLINK -> false
        else -> true
    }
}

private fun AnnotatedString.Builder.appendInlineNode(
    child: ASTNode,
    ctx: InlineBuildContext,
) {
    when (child.type) {
        MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineChildren(child, ctx) { it.type == MarkdownTokenTypes.EMPH }
            }
        }

        MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineChildren(child, ctx) { it.type == MarkdownTokenTypes.EMPH }
            }
        }

        GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlineChildren(child, ctx) { it.type.name == "~" }
            }
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val raw = safeSlice(ctx.content, child)
            val normalized = normalizeCodeSpanContent(raw)
            val display = if (ctx.table != null) PiInlineMath.resolveToUnicode(normalized, ctx.table) else normalized
            val codeStyle = ctx.typography.inlineCode
            val pillSpan = SpanStyle(
                fontFamily = codeStyle.fontFamily ?: PiMonoFamily,
                fontSize = codeStyle.fontSize,
                fontWeight = codeStyle.fontWeight,
                color = codeStyle.color,
            )
            val text = consumePrefixSkip(display, ctx)
            if (text.isNotEmpty()) {
                withHtmlDecorations(ctx) {
                    pushStringAnnotation(INLINE_CODE_PILL_TAG, INLINE_CODE_KIND_CODE)
                    withStyle(pillSpan) { append(text) }
                    pop()
                }
            }
        }

        MarkdownElementTypes.INLINE_LINK -> {
            val dest = child.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.let { safeSlice(ctx.content, it).trim().removePrefix("<").removeSuffix(">") }
            val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            appendLinkNode(dest, labelNode, ctx)
        }

        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val textNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: labelNode
            val dest = resolveReferenceDestination(ctx.content, labelNode, textNode, ctx.referenceLinks)
            appendLinkNode(dest, textNode, ctx)
        }

        MarkdownElementTypes.IMAGE -> {
            val rel = child.startOffset - ctx.rootStartOffset
            val slot = ctx.imageSlots.firstOrNull { it.relativeStart == rel }
            if (slot != null) {
                appendInlineContent(slot.slotId, slot.alt.ifBlank { "[image]" })
            }
        }

        MarkdownElementTypes.AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK -> {
            val raw = safeSlice(ctx.content, child).trim().removePrefix("<").removeSuffix(">")
            if (raw.isNotEmpty()) {
                val url = if (raw.contains("://") || raw.startsWith("mailto:")) raw else "https://$raw"
                withLink(LinkAnnotation.Url(url, styles = ctx.typography.textLink)) {
                    appendConsumeSkip(raw, ctx)
                }
            }
        }

        MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

        MarkdownTokenTypes.EOL -> {
            if (ctx.skipRemaining > 0) {
                val eolLen = (child.endOffset - child.startOffset).coerceAtLeast(1)
                ctx.skipRemaining = (ctx.skipRemaining - eolLen).coerceAtLeast(0)
            } else {
                append(if (ctx.eolAsNewLine) '\n' else ' ')
            }
        }

        MarkdownTokenTypes.BACKTICK -> appendConsumeSkip("`", ctx)
        MarkdownTokenTypes.BLOCK_QUOTE -> Unit

        MarkdownTokenTypes.HTML_TAG -> {
            handleInlineHtmlTag(child, ctx)
        }

        else -> {
            if (child.children.isNotEmpty()) {
                appendInlineChildren(child, ctx)
            } else {
                appendProcessedText(safeSlice(ctx.content, child), ctx)
            }
        }
    }
}

private fun AnnotatedString.Builder.handleInlineHtmlTag(
    child: ASTNode,
    ctx: InlineBuildContext,
) {
    val raw = PiHtml.inlineTextAt(
        ctx.content,
        child.startOffset.coerceIn(0, ctx.content.length),
        child.endOffset.coerceIn(0, ctx.content.length),
    )
    val trimmed = raw.trim()
    // Strip HTML comments (`<!-- ... -->`) silently so they never leak into rendered prose.
    if (trimmed.startsWith("<!--")) return

    val isClosing = trimmed.startsWith("</")
    val selfClosing = trimmed.endsWith("/>")
    val tag = trimmed.removePrefix("<").removePrefix("/").trimStart()
        .takeWhile { it.isLetterOrDigit() }.lowercase()

    when {
        tag == "br" -> append('\n')
        selfClosing -> Unit
        tag.isEmpty() -> appendConsumeSkip(raw, ctx)
        tag == "a" -> {
            if (isClosing) {
                ctx.activeHtmlLinkUrl = null
            } else {
                extractSafeHtmlHref(trimmed)?.let { ctx.activeHtmlLinkUrl = it }
            }
        }
        else -> {
            val entry = inlineHtmlTagEntry(tag, ctx.colors, ctx.typography)
            if (entry != null) {
                if (isClosing) {
                    // Remove the nearest matching open tag from our independent stack;
                    // mismatched or unclosed tags never throw or corrupt outer Markdown styles.
                    for (idx in ctx.htmlTagStack.lastIndex downTo 0) {
                        if (ctx.htmlTagStack[idx].tag == tag) {
                            ctx.htmlTagStack.removeAt(idx)
                            break
                        }
                    }
                } else {
                    ctx.htmlTagStack.add(entry)
                }
            } else if (isClosing || tag in IGNORED_HTML_TAGS) {
                // Silently drop unknown/layout closing tags (`</div>`, `</p>`, `</font>`, etc.).
                Unit
            } else {
                // Preserve unrecognized angle-bracketed tokens (e.g. `<T>`, `<String>`) as text.
                appendConsumeSkip(raw, ctx)
            }
        }
    }
}

private val IGNORED_HTML_TAGS = setOf(
    "div", "p", "font", "center", "section", "article", "header", "footer",
    "details", "summary", "figure", "figcaption", "picture", "source", "abbr",
    "cite", "q", "var", "samp", "time", "ins",
)

private fun extractSafeHtmlHref(openTag: String): String? {
    val match = HTML_HREF_REGEX.find(openTag) ?: return null
    val rawUrl = (match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }).trim()
    if (rawUrl.isEmpty()) return null
    val lower = rawUrl.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
        rawUrl
    } else {
        null
    }
}

private fun inlineHtmlTagEntry(
    tag: String,
    colors: PiMarkdownColors,
    typography: PiMarkdownTypography,
): HtmlTagEntry? = when (tag) {
    "b", "strong" -> HtmlTagEntry(tag, SpanStyle(fontWeight = FontWeight.Bold))
    "i", "em" -> HtmlTagEntry(tag, SpanStyle(fontStyle = FontStyle.Italic))
    "del", "s" -> HtmlTagEntry(tag, SpanStyle(textDecoration = TextDecoration.LineThrough))
    "u" -> HtmlTagEntry(tag, SpanStyle(textDecoration = TextDecoration.Underline))
    "code" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(
            fontFamily = PiMonoFamily,
            fontSize = typography.inlineCode.fontSize,
            color = typography.inlineCode.color,
        ),
        pillKind = INLINE_CODE_KIND_CODE,
    )
    "kbd" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(
            fontFamily = PiMonoFamily,
            fontSize = typography.inlineCode.fontSize,
            fontWeight = FontWeight.Medium,
            color = colors.text,
        ),
        pillKind = INLINE_CODE_KIND_KBD,
    )
    "mark" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(
            background = colors.alertWarning.copy(alpha = if (colors.darkTheme) 0.26f else 0.22f),
        ),
    )
    "sup" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(
            baselineShift = BaselineShift.Superscript,
            fontSize = (typography.text.fontSize.value * 0.78f).sp,
        ),
    )
    "sub" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(
            baselineShift = BaselineShift.Subscript,
            fontSize = (typography.text.fontSize.value * 0.78f).sp,
        ),
    )
    "small" -> HtmlTagEntry(
        tag = tag,
        style = SpanStyle(fontSize = (typography.text.fontSize.value * 0.86f).sp),
    )
    "span" -> HtmlTagEntry(tag, SpanStyle())
    else -> null
}

private inline fun AnnotatedString.Builder.withHtmlDecorations(
    ctx: InlineBuildContext,
    crossinline block: AnnotatedString.Builder.() -> Unit,
) {
    val stack = ctx.htmlTagStack
    val linkUrl = ctx.activeHtmlLinkUrl
    if (stack.isEmpty() && linkUrl == null) {
        block()
        return
    }
    var mergedStyle = SpanStyle()
    var pillKind: String? = null
    for (entry in stack) {
        mergedStyle = mergedStyle.merge(entry.style)
        if (entry.pillKind != null) pillKind = entry.pillKind
    }
    val inner: AnnotatedString.Builder.() -> Unit = {
        if (pillKind != null) {
            pushStringAnnotation(INLINE_CODE_PILL_TAG, pillKind)
            withStyle(mergedStyle) { block() }
            pop()
        } else if (stack.isNotEmpty()) {
            withStyle(mergedStyle) { block() }
        } else {
            block()
        }
    }
    if (linkUrl != null) {
        withLink(LinkAnnotation.Url(linkUrl, styles = ctx.typography.textLink)) { inner() }
    } else {
        inner()
    }
}

private fun AnnotatedString.Builder.appendLinkNode(
    dest: String?,
    labelNode: ASTNode?,
    ctx: InlineBuildContext,
) {
    val body: AnnotatedString.Builder.() -> Unit = {
        if (labelNode != null) {
            appendInlineChildren(labelNode, ctx) {
                it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET
            }
        } else if (!dest.isNullOrEmpty()) {
            appendConsumeSkip(dest, ctx)
        }
    }
    if (!dest.isNullOrBlank()) {
        withLink(LinkAnnotation.Url(dest, styles = ctx.typography.textLink)) { body() }
    } else {
        body()
    }
}

private fun AnnotatedString.Builder.appendProcessedText(raw: String, ctx: InlineBuildContext) {
    if (raw.isEmpty()) return
    val skipped = consumePrefixSkip(raw, ctx)
    if (skipped.isEmpty()) return
    val text = piUnescapeMarkdownText(skipped)
    if (text.isEmpty()) return
    val table = ctx.table
    withHtmlDecorations(ctx) {
        if (table == null || text.indexOf(table.markerPrefix) < 0) {
            append(text)
            return@withHtmlDecorations
        }
        for (seg in PiInlineMath.splitSegments(text, table.markerPrefix, table.markerSuffix)) {
            when (seg) {
                is PiInlineMath.Segment.Text -> append(seg.text)
                is PiInlineMath.Segment.MathRef -> {
                    val entry = table.entries.getOrNull(seg.index) ?: continue
                    if (seg.index in ctx.readyMathIndices) {
                        appendInlineContent(inlineMathSlotId(seg.index), entry.unicodeFallback)
                    } else {
                        append(entry.unicodeFallback)
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendConsumeSkip(raw: String, ctx: InlineBuildContext) {
    val text = consumePrefixSkip(raw, ctx)
    if (text.isNotEmpty()) {
        withHtmlDecorations(ctx) { append(text) }
    }
}

private fun consumePrefixSkip(raw: String, ctx: InlineBuildContext): String {
    var s = raw
    if (ctx.skipRemaining > 0) {
        if (s.length <= ctx.skipRemaining) {
            ctx.skipRemaining -= s.length
            return ""
        }
        s = s.substring(ctx.skipRemaining)
        ctx.skipRemaining = 0
    }
    if (ctx.trimLeading) {
        val trimmed = s.trimStart(' ', '\t')
        if (trimmed.isEmpty()) return ""
        ctx.trimLeading = false
        s = trimmed
    }
    return s
}

/**
 * CommonMark §2.4 backslash unescaping (`\*` -> `*`, `\$` -> `$`, `\\` -> `\`, preserving
 * non-punctuation backslashes like `C:\Users\pi`) plus common HTML entity decoding (`&amp;`,
 * `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`, numeric `&#65;` / `&#x42;`).
 */
internal fun piUnescapeMarkdownText(input: String): String {
    if (input.indexOf('\\') < 0 && input.indexOf('&') < 0) return input
    val n = input.length
    val out = StringBuilder(n)
    var i = 0
    while (i < n) {
        val c = input[i]
        if (c == '\\' && i + 1 < n && isAsciiPunctuation(input[i + 1])) {
            out.append(input[i + 1])
            i += 2
            continue
        }
        if (c == '&') {
            val semi = input.indexOf(';', i + 2)
            if (semi in (i + 2)..(i + 10)) {
                val decoded = decodeHtmlEntity(input.substring(i + 1, semi))
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun isAsciiPunctuation(c: Char): Boolean =
    c in "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun decodeHtmlEntity(body: String): String? = when (body) {
    "amp" -> "&"
    "lt" -> "<"
    "gt" -> ">"
    "quot" -> "\""
    "apos", "#39" -> "'"
    "nbsp" -> "\u00A0"
    "ndash" -> "–"
    "mdash" -> "—"
    "hellip" -> "…"
    "copy" -> "©"
    else -> if (body.startsWith("#")) {
        val code = if (body.startsWith("#x", ignoreCase = true)) {
            body.substring(2).toIntOrNull(16)
        } else {
            body.substring(1).toIntOrNull(10)
        }
        if (code != null && code in 1..0x10FFFF && !Character.isISOControl(code)) {
            String(Character.toChars(code))
        } else null
    } else null
}

// ------------------------------------------------------------------ Inline Math Box Metrics

private fun inlineMathSlotId(index: Int): String = "pi_math_$index"

private class InlineMathBoxMetrics(
    val widthSp: Float,
    val heightSp: Float,
    val horizontalPadFraction: Float,
    val contentHeightFraction: Float,
    val baselineOffsetFromCenterFraction: Float,
)

private fun computeInlineMathBoxSp(
    ready: PiMathDraw.Ready,
    bodyFontSizeSp: Float,
    maxWidthSp: Float,
): InlineMathBoxMetrics {
    val scaleEm = bodyFontSizeSp * 1.06f
    val rawW: Float
    val rawH: Float
    if (ready.emWidth > 0f && ready.emHeight > 0f) {
        rawW = ready.emWidth * scaleEm
        rawH = ready.emHeight * scaleEm
    } else {
        val aspect = ready.aspect.takeIf { it.isFinite() && it > 0f } ?: 1.4f
        rawH = bodyFontSizeSp * 1.2f
        rawW = rawH * aspect
    }
    val padSp = (bodyFontSizeSp * 0.08f).coerceIn(1f, 3f)
    val paddedW = rawW + padSp * 2f

    val ascent = rawH * ready.baselineRatio
    val descent = rawH * (1f - ready.baselineRatio)
    val textCenterAboveBaseline = bodyFontSizeSp * 0.28f
    val aboveCenter = (ascent - textCenterAboveBaseline).coerceAtLeast(0f)
    val belowCenter = (descent + textCenterAboveBaseline).coerceAtLeast(0f)
    val symmetricH = (maxOf(aboveCenter, belowCenter) * 2f)
        .coerceIn(rawH, bodyFontSizeSp * 2.8f)

    val finalW: Float
    val finalH: Float
    val scaledRawH: Float
    if (paddedW > maxWidthSp && paddedW > 0f) {
        val ratio = maxWidthSp / paddedW
        finalW = maxWidthSp
        finalH = (symmetricH * ratio).coerceAtLeast(bodyFontSizeSp * 0.95f)
        scaledRawH = rawH * ratio
    } else {
        finalW = paddedW.coerceAtLeast(bodyFontSizeSp * 0.45f)
        finalH = symmetricH.coerceAtLeast(bodyFontSizeSp * 0.95f)
        scaledRawH = rawH
    }
    val baselineOffsetFraction = (textCenterAboveBaseline / finalH).coerceIn(0f, 0.45f)
    val padFraction = (padSp / finalW).coerceIn(0f, 0.12f)
    val contentHeightFraction = (scaledRawH / finalH).coerceIn(0.25f, 1f)
    return InlineMathBoxMetrics(
        widthSp = finalW,
        heightSp = finalH,
        horizontalPadFraction = padFraction,
        contentHeightFraction = contentHeightFraction,
        baselineOffsetFromCenterFraction = baselineOffsetFraction,
    )
}

// ------------------------------------------------------------------ AST utilities

internal fun safeSlice(content: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}

internal fun resolveReferenceDestination(
    content: String,
    labelNode: ASTNode?,
    textNode: ASTNode?,
    referenceLinks: Map<String, String>,
): String? {
    val labelKey = labelNode?.let { normalizeReferenceLabel(safeSlice(content, it)) }.orEmpty()
    if (labelKey.isNotEmpty()) return referenceLinks[labelKey]
    val fallbackKey = textNode?.let { normalizeReferenceLabel(safeSlice(content, it)) }.orEmpty()
    return if (fallbackKey.isNotEmpty()) referenceLinks[fallbackKey] else null
}

/**
 * Pure helper (JVM-testable) that flattens a paragraph/inline AST node the same way
 * [appendInlineChildren] strips blockquote markers and decodes escapes/entities.
 */
internal fun extractInlinePlainTextForTest(content: String, node: ASTNode): String = buildString {
    fun walk(n: ASTNode) {
        if (n.children.isEmpty()) {
            when (n.type) {
                MarkdownTokenTypes.BLOCK_QUOTE -> Unit
                MarkdownTokenTypes.EOL -> append(' ')
                MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')
                else -> append(piUnescapeMarkdownText(safeSlice(content, n)))
            }
        } else {
            for (c in n.children) walk(c)
        }
    }
    walk(node)
}

internal fun resolveAstImageLink(
    content: String,
    imageNode: ASTNode,
    referenceLinks: Map<String, String>,
): String? {
    val linkNode = imageNode.children.firstOrNull {
        it.type == MarkdownElementTypes.INLINE_LINK ||
            it.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
            it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
    } ?: return null
    return when (linkNode.type) {
        MarkdownElementTypes.INLINE_LINK -> linkNode.children
            .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            ?.let { safeSlice(content, it).trim().removePrefix("<").removeSuffix(">") }
            ?.takeIf { it.isNotEmpty() }
        else -> {
            val labelNode = linkNode.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val textNode = linkNode.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: labelNode
            resolveReferenceDestination(content, labelNode, textNode, referenceLinks)
                ?.takeIf { it.isNotEmpty() }
        }
    }
}

internal fun resolveAstImageAlt(content: String, imageNode: ASTNode): String? {
    val linkNode = imageNode.children.firstOrNull {
        it.type == MarkdownElementTypes.INLINE_LINK ||
            it.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
            it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
    } ?: return null
    return linkNode.children
        .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        ?.let { safeSlice(content, it).removePrefix("[").removeSuffix("]").trim() }
        ?.takeIf { it.isNotEmpty() }
}

/**
 * CommonMark §6.1 code span normalization:
 *  1. Strip matching opening & closing backtick runs.
 *  2. Strip any blockquote `> ` continuation markers inside multi-line code spans.
 *  3. Convert all line endings (`\r\n`, `\r`, `\n`) to single ASCII spaces.
 *  4. If the result starts and ends with a space and is not all spaces, trim one space from each end.
 */
internal fun normalizeCodeSpanContent(raw: String): String {
    var open = 0
    while (open < raw.length && raw[open] == '`') open++
    var close = 0
    while (close < raw.length - open && raw[raw.length - 1 - close] == '`') close++
    var inner = raw.substring(open, (raw.length - close).coerceAtLeast(open))
    if (inner.indexOf('\n') >= 0 || inner.indexOf('\r') >= 0) {
        inner = inner.replace(CODE_SPAN_QUOTE_BREAK_REGEX, "\n")
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
    }
    return if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.any { it != ' ' }) {
        inner.substring(1, inner.length - 1)
    } else {
        inner
    }
}
