package app.pi.ui.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.render.rich.svg.PiMathDraw
import app.pi.ui.render.rich.svg.piMathInlineCached
import app.pi.ui.render.rich.svg.piMathRenderInline
import app.pi.ui.render.rich.svg.piMathThemeFor
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandler
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.min
import kotlin.math.roundToInt

/** 当前 Markdown 文档提取出的行内 LaTeX 查找表（流式期间或无行内公式时为 null）。 */
internal val LocalPiInlineMathTable = staticCompositionLocalOf<PiInlineMath.Extracted?> { null }

/**
 * 含行内 LaTeX（`$...$` / `\(...\)`）的段落渲染器：
 * - 完整保留同一段落内的普通 Markdown 格式（粗体、斜体、删除线、行内代码、可点击链接、行内 HTML、换行）；
 * - 每个行内公式先同步查 [app.pi.ui.render.rich.svg.PiMathDrawCache]：命中则首帧直接画矢量 MathJax SVG（零闪烁、零跳高）；
 * - 未命中时首帧先显示干净的 `PiLatex.toUnicode` 近似文本，后台由 MathJax 引擎渲染完成后自动升级为矢量 SVG；
 * - 依据 MathJax 原始 `1/1000 em` 度量与基线比例对齐正文基线，短公式自然等高、分式/根号不截断、超宽公式等比收进屏宽。
 */
@Composable
internal fun PiInlineMathParagraph(
    model: MarkdownComponentModel,
    table: PiInlineMath.Extracted,
) {
    val entries = table.entries
    val markerPrefix = table.markerPrefix
    val markerSuffix = table.markerSuffix
    val palette = PiTheme.palette
    val colors = LocalMarkdownColors.current
    val typography = LocalMarkdownTypography.current
    val referenceLinks = LocalReferenceLinkHandler.current
    val style = typography.paragraph
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val widthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
            .roundToInt()
            .coerceIn(240, 1600)
    }
    val fgColor = if (style.color.alpha > 0f) style.color else colors.text
    val theme = remember(palette, fgColor) {
        piMathThemeFor(
            background = palette.pageBg,
            foreground = fgColor,
            accent = palette.accent,
            muted = palette.muted,
        )
    }

    val paragraphRaw = remember(model.content, model.node) {
        val start = model.node.startOffset.coerceIn(0, model.content.length)
        val end = model.node.endOffset.coerceIn(start, model.content.length)
        model.content.substring(start, end)
    }
    val neededIndices = remember(paragraphRaw, markerPrefix, markerSuffix) {
        PiInlineMath.splitSegments(paragraphRaw, markerPrefix, markerSuffix)
            .mapNotNull { (it as? PiInlineMath.Segment.MathRef)?.index }
            .distinct()
    }

    // 合并高频行内公式完成通知（每 ~90ms 最多刷新一次 + 末尾补一次），避免单段 K 个公式触发 O(K²) 次重建。
    var renderEpoch by remember(paragraphRaw, theme) { mutableIntStateOf(0) }
    LaunchedEffect(neededIndices, widthPx, theme, entries) {
        var lastBumpMs = 0L
        var dirty = false
        for (idx in neededIndices) {
            val entry = entries.getOrNull(idx) ?: continue
            if (piMathInlineCached(entry.tex, widthPx, theme) != null) continue
            val rendered = piMathRenderInline(entry.tex, widthPx, theme)
            if (rendered != null) {
                dirty = true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastBumpMs >= 90L) {
                    renderEpoch++
                    lastBumpMs = now
                    dirty = false
                }
            }
        }
        if (dirty) renderEpoch++
    }

    val readyByIndex = remember(neededIndices, widthPx, theme, entries, renderEpoch) {
        val map = HashMap<Int, PiMathDraw.Ready>(neededIndices.size)
        for (idx in neededIndices) {
            val entry = entries.getOrNull(idx) ?: continue
            val cached = piMathInlineCached(entry.tex, widthPx, theme)
            if (cached != null) map[idx] = cached
        }
        map
    }

    val bodyFontSizeSp = style.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 16f
    val maxInlineWidthSp = remember(configuration.screenWidthDp, density) {
        with(density) { (configuration.screenWidthDp - 36).coerceAtLeast(160).dp.toSp().value }
    }

    val annotated = remember(model.content, model.node, table, readyByIndex, typography, colors, referenceLinks) {
        buildAnnotatedString {
            appendInlineMathAst(
                content = model.content,
                node = model.node,
                table = table,
                readyIndices = readyByIndex.keys,
                typography = typography,
                colors = colors,
                referenceLinks = referenceLinks,
                htmlStack = ArrayList(),
            )
        }
    }

    val inlineContent = remember(readyByIndex, entries, bodyFontSizeSp, maxInlineWidthSp) {
        if (readyByIndex.isEmpty()) {
            emptyMap()
        } else {
            val map = HashMap<String, InlineTextContent>(readyByIndex.size)
            for ((idx, ready) in readyByIndex) {
                val entry = entries.getOrNull(idx) ?: continue
                val metrics = computeInlineBoxSp(
                    ready = ready,
                    bodyFontSizeSp = bodyFontSizeSp,
                    maxWidthSp = maxInlineWidthSp,
                )
                val desc = tr("行内公式：${entry.tex}", "Inline formula: ${entry.tex}")
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
                        // 对齐数学基线到行内正文基线（TextCenter 中心大约在基线上方 0.28em）
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
            map
        }
    }

    Text(
        text = annotated,
        style = if (style.color.alpha > 0f) style else style.copy(color = fgColor),
        inlineContent = inlineContent,
    )
}

private fun inlineMathSlotId(index: Int): String = "pi_math_$index"

private class InlineBoxMetrics(
    val widthSp: Float,
    val heightSp: Float,
    val horizontalPadFraction: Float,
    val contentHeightFraction: Float,
    val baselineOffsetFromCenterFraction: Float,
)

private fun computeInlineBoxSp(
    ready: PiMathDraw.Ready,
    bodyFontSizeSp: Float,
    maxWidthSp: Float,
): InlineBoxMetrics {
    // TeX 数学字体光学缩放系数（1.06× 让 x-height 与中英正文和谐一致）
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
    // 两侧留极轻微呼吸间距（约 0.08em），让紧贴中英文字符的公式不拥挤
    val padSp = (bodyFontSizeSp * 0.08f).coerceIn(1f, 3f)
    val paddedW = rawW + padSp * 2f

    // 数学基线对齐：让 Placeholder 盒子对称包住上伸部与下伸部，避免 TextCenter 把下标公式顶上去或把上标压下来
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
    return InlineBoxMetrics(
        widthSp = finalW,
        heightSp = finalH,
        horizontalPadFraction = padFraction,
        contentHeightFraction = contentHeightFraction,
        baselineOffsetFromCenterFraction = baselineOffsetFraction,
    )
}

/**
 * 递归遍历 `PARAGRAPH` AST，保留所有行内 Markdown 格式，并将哨兵替换为
 * 已就绪的 `appendInlineContent` 或 Unicode 近似回退。
 *
 * 连续的普通文本叶节点会先合并进缓冲区再解析哨兵，作为词法器单叶保证之外的双重兜底。
 */
private fun AnnotatedString.Builder.appendInlineMathAst(
    content: String,
    node: ASTNode,
    table: PiInlineMath.Extracted,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
) {
    val textRun = StringBuilder()
    for (child in node.children) {
        if (isPlainLeafTextNode(child)) {
            textRun.append(safeSlice(content, child))
        } else {
            if (textRun.isNotEmpty()) {
                appendTextWithMathSentinels(textRun.toString(), table, readyIndices)
                textRun.setLength(0)
            }
            appendInlineMathNode(
                content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
            )
        }
    }
    if (textRun.isNotEmpty()) {
        appendTextWithMathSentinels(textRun.toString(), table, readyIndices)
    }
}

private fun isPlainLeafTextNode(node: ASTNode): Boolean {
    if (node.children.isNotEmpty()) return false
    return when (node.type) {
        MarkdownTokenTypes.HARD_LINE_BREAK,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.HTML_TAG,
        GFMTokenTypes.GFM_AUTOLINK -> false
        else -> true
    }
}

private fun AnnotatedString.Builder.appendInlineMathNode(
    content: String,
    child: ASTNode,
    table: PiInlineMath.Extracted,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
) {
    when (child.type) {
            MarkdownElementTypes.STRONG -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineChildrenSkippingDelimiters(
                        content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
                        skipPredicate = { it.type == MarkdownTokenTypes.EMPH },
                    )
                }
            }

            MarkdownElementTypes.EMPH -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineChildrenSkippingDelimiters(
                        content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
                        skipPredicate = { it.type == MarkdownTokenTypes.EMPH },
                    )
                }
            }

            GFMElementTypes.STRIKETHROUGH -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineChildrenSkippingDelimiters(
                        content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
                        skipPredicate = { it.type.name == "~" },
                    )
                }
            }

            MarkdownElementTypes.CODE_SPAN -> {
                val raw = safeSlice(content, child)
                val trimmed = stripCodeSpanBackticks(raw)
                val codeStyle = typography.inlineCode
                withStyle(
                    SpanStyle(
                        fontFamily = codeStyle.fontFamily ?: PiMonoFamily,
                        fontSize = codeStyle.fontSize,
                        fontWeight = codeStyle.fontWeight,
                        color = codeStyle.color,
                        background = colors.inlineCodeBackground,
                    ),
                ) {
                    append(PiInlineMath.resolveToUnicode(trimmed, table))
                }
            }

            MarkdownElementTypes.INLINE_LINK -> {
                val dest = child.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.let { safeSlice(content, it).trim().removePrefix("<").removeSuffix(">") }
                val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                appendInlineLink(dest, labelNode, content, table, readyIndices, typography, colors, referenceLinks, htmlStack)
            }

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val textNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: labelNode
                val labelText = labelNode?.let { safeSlice(content, it) }
                val dest = labelText?.let { referenceLinks.find(it) }
                appendInlineLink(dest, textNode, content, table, readyIndices, typography, colors, referenceLinks, htmlStack)
            }

            MarkdownElementTypes.IMAGE -> {
                val linkNode = child.children.firstOrNull {
                    it.type == MarkdownElementTypes.INLINE_LINK ||
                        it.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
                        it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
                }
                val altText = linkNode?.children
                    ?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.let { safeSlice(content, it).removePrefix("[").removeSuffix("]").trim() }
                    .orEmpty()
                if (altText.isNotEmpty()) {
                    append(PiInlineMath.resolveToUnicode(altText, table))
                }
            }

            MarkdownElementTypes.AUTOLINK,
            GFMTokenTypes.GFM_AUTOLINK -> {
                val raw = safeSlice(content, child).trim().removePrefix("<").removeSuffix(">")
                if (raw.isNotEmpty()) {
                    withLink(LinkAnnotation.Url(raw, styles = typography.textLink)) {
                        append(raw)
                    }
                }
            }

            MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')
            MarkdownTokenTypes.EOL -> append(' ')
            MarkdownTokenTypes.BACKTICK -> append('`')

            MarkdownTokenTypes.HTML_TAG -> {
                val raw = PiHtml.inlineTextAt(content, child.startOffset.coerceIn(0, content.length), child.endOffset.coerceIn(0, content.length))
                val trimmedRaw = raw.trim()
                val selfClosing = trimmedRaw.endsWith("/>")
                val tag = trimmedRaw.removePrefix("<").removePrefix("/").trimStart()
                    .takeWhile { it.isLetterOrDigit() }.lowercase()
                when {
                    tag == "br" -> append('\n')
                    selfClosing -> Unit
                    tag.isEmpty() -> append(raw)
                    else -> {
                        val span = when (tag) {
                            "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                            "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                            "del", "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                            "code", "kbd" -> SpanStyle(fontFamily = PiMonoFamily)
                            "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript)
                            "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript)
                            "span" -> SpanStyle()
                            else -> null
                        }
                        if (span == null) {
                            append(raw)
                        } else if (trimmedRaw.startsWith("</")) {
                            if (htmlStack.isNotEmpty()) {
                                htmlStack.removeAt(htmlStack.lastIndex)
                                runCatching { pop() }
                            }
                        } else {
                            pushStyle(span)
                            htmlStack.add(span)
                        }
                    }
                }
            }

            else -> {
                if (child.children.isNotEmpty()) {
                    appendInlineMathAst(
                        content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
                    )
                } else {
                    val raw = safeSlice(content, child)
                    appendTextWithMathSentinels(raw, table, readyIndices)
                }
            }
    }
}

private fun AnnotatedString.Builder.appendInlineChildrenSkippingDelimiters(
    content: String,
    node: ASTNode,
    table: PiInlineMath.Extracted,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
    skipPredicate: (ASTNode) -> Boolean,
) {
    val textRun = StringBuilder()
    for (child in node.children) {
        if (skipPredicate(child)) continue
        if (isPlainLeafTextNode(child)) {
            textRun.append(safeSlice(content, child))
        } else {
            if (textRun.isNotEmpty()) {
                appendTextWithMathSentinels(textRun.toString(), table, readyIndices)
                textRun.setLength(0)
            }
            appendInlineMathNode(
                content, child, table, readyIndices, typography, colors, referenceLinks, htmlStack,
            )
        }
    }
    if (textRun.isNotEmpty()) {
        appendTextWithMathSentinels(textRun.toString(), table, readyIndices)
    }
}

private fun AnnotatedString.Builder.appendInlineLink(
    dest: String?,
    labelNode: ASTNode?,
    content: String,
    table: PiInlineMath.Extracted,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
) {
    val body: AnnotatedString.Builder.() -> Unit = {
        if (labelNode != null) {
            appendInlineChildrenSkippingDelimiters(
                content, labelNode, table, readyIndices, typography, colors, referenceLinks, htmlStack,
                skipPredicate = { it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET },
            )
        } else if (!dest.isNullOrEmpty()) {
            append(dest)
        }
    }
    if (!dest.isNullOrBlank()) {
        withLink(LinkAnnotation.Url(dest, styles = typography.textLink)) { body() }
    } else {
        body()
    }
}

private fun AnnotatedString.Builder.appendTextWithMathSentinels(
    raw: String,
    table: PiInlineMath.Extracted,
    readyIndices: Set<Int>,
) {
    if (raw.isEmpty()) return
    if (raw.indexOf(table.markerPrefix) < 0) {
        append(raw)
        return
    }
    for (seg in PiInlineMath.splitSegments(raw, table.markerPrefix, table.markerSuffix)) {
        when (seg) {
            is PiInlineMath.Segment.Text -> append(seg.text)
            is PiInlineMath.Segment.MathRef -> {
                val entry = table.entries.getOrNull(seg.index) ?: continue
                if (seg.index in readyIndices) {
                    appendInlineContent(inlineMathSlotId(seg.index), entry.unicodeFallback)
                } else {
                    // MathJax 渲染完成前或失败时显示干净的 Unicode 近似（绝不闪烁或留白）
                    append(entry.unicodeFallback)
                }
            }
        }
    }
}

private fun safeSlice(content: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}

private fun stripCodeSpanBackticks(raw: String): String {
    var open = 0
    while (open < raw.length && raw[open] == '`') open++
    var close = 0
    while (close < raw.length - open && raw[raw.length - 1 - close] == '`') close++
    val inner = raw.substring(open, (raw.length - close).coerceAtLeast(open))
    return if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.any { it != ' ' }) {
        inner.substring(1, inner.length - 1)
    } else {
        inner
    }
}
