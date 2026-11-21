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

/** 当前 Markdown 文档提取出的行内 LaTeX 查找表（流式期间为空列表）。 */
internal val LocalPiInlineMathTable = staticCompositionLocalOf<List<PiInlineMath.Entry>> { emptyList() }

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
    entries: List<PiInlineMath.Entry>,
) {
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
    val neededIndices = remember(paragraphRaw) {
        PiInlineMath.splitSegments(paragraphRaw)
            .mapNotNull { (it as? PiInlineMath.Segment.MathRef)?.index }
            .distinct()
    }

    // 每次后台有一批新公式渲染完成并写入共享 LRU，递增版本号以重算 inlineContent map。
    var renderEpoch by remember(paragraphRaw, theme) { mutableIntStateOf(0) }
    LaunchedEffect(neededIndices, widthPx, theme, entries) {
        var anyNew = false
        for (idx in neededIndices) {
            val entry = entries.getOrNull(idx) ?: continue
            if (piMathInlineCached(entry.tex, widthPx, theme) != null) continue
            val rendered = piMathRenderInline(entry.tex, widthPx, theme)
            if (rendered != null) {
                anyNew = true
                // 每完成一条即可平滑升级，不必等整段最后一条才更新
                renderEpoch++
            }
        }
        if (anyNew) renderEpoch++
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

    val annotated = remember(model.content, model.node, entries, readyByIndex, typography, colors, referenceLinks) {
        buildAnnotatedString {
            appendInlineMathAst(
                content = model.content,
                node = model.node,
                entries = entries,
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
                        val drawAreaH = size.height
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
        style = style,
        inlineContent = inlineContent,
    )
}

private fun inlineMathSlotId(index: Int): String = "pi_math_$index"

private class InlineBoxMetrics(
    val widthSp: Float,
    val heightSp: Float,
    val horizontalPadFraction: Float,
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
    if (paddedW > maxWidthSp && paddedW > 0f) {
        val ratio = maxWidthSp / paddedW
        finalW = maxWidthSp
        finalH = (symmetricH * ratio).coerceAtLeast(bodyFontSizeSp * 0.95f)
    } else {
        finalW = paddedW.coerceAtLeast(bodyFontSizeSp * 0.45f)
        finalH = symmetricH.coerceAtLeast(bodyFontSizeSp * 0.95f)
    }
    val baselineOffsetFraction = (textCenterAboveBaseline / finalH).coerceIn(0f, 0.45f)
    val padFraction = (padSp / finalW).coerceIn(0f, 0.12f)
    return InlineBoxMetrics(
        widthSp = finalW,
        heightSp = finalH,
        horizontalPadFraction = padFraction,
        baselineOffsetFromCenterFraction = baselineOffsetFraction,
    )
}

/**
 * 递归遍历 `PARAGRAPH` AST，保留所有行内 Markdown 格式，并将哨兵 `\uE100i\uE101` 替换为
 * 已就绪的 `appendInlineContent` 或 Unicode 近似回退。
 */
private fun AnnotatedString.Builder.appendInlineMathAst(
    content: String,
    node: ASTNode,
    entries: List<PiInlineMath.Entry>,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
) {
    for (child in node.children) {
        appendInlineMathNode(
            content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
        )
    }
}

private fun AnnotatedString.Builder.appendInlineMathNode(
    content: String,
    child: ASTNode,
    entries: List<PiInlineMath.Entry>,
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
                        content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
                        skipPredicate = { it.type == MarkdownTokenTypes.EMPH },
                    )
                }
            }

            MarkdownElementTypes.EMPH -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineChildrenSkippingDelimiters(
                        content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
                        skipPredicate = { it.type == MarkdownTokenTypes.EMPH },
                    )
                }
            }

            GFMElementTypes.STRIKETHROUGH -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineChildrenSkippingDelimiters(
                        content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
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
                    append(PiInlineMath.resolveToUnicode(trimmed, entries))
                }
            }

            MarkdownElementTypes.INLINE_LINK -> {
                val dest = child.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.let { safeSlice(content, it).trim().removePrefix("<").removeSuffix(">") }
                val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                appendInlineLink(dest, labelNode, content, entries, readyIndices, typography, colors, referenceLinks, htmlStack)
            }

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val labelNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val textNode = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: labelNode
                val labelText = labelNode?.let { safeSlice(content, it) }
                val dest = labelText?.let { referenceLinks.find(it) }
                appendInlineLink(dest, textNode, content, entries, readyIndices, typography, colors, referenceLinks, htmlStack)
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
                val tag = raw.removePrefix("<").removePrefix("/").trimStart()
                    .takeWhile { it.isLetterOrDigit() }.lowercase()
                when {
                    tag == "br" -> append('\n')
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
                        } else if (raw.startsWith("</")) {
                            if (htmlStack.isNotEmpty()) {
                                htmlStack.removeAt(htmlStack.lastIndex)
                                pop()
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
                        content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
                    )
                } else {
                    val raw = safeSlice(content, child)
                    appendTextWithMathSentinels(raw, entries, readyIndices)
                }
            }
    }
}

private fun AnnotatedString.Builder.appendInlineChildrenSkippingDelimiters(
    content: String,
    node: ASTNode,
    entries: List<PiInlineMath.Entry>,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
    skipPredicate: (ASTNode) -> Boolean,
) {
    for (child in node.children) {
        if (skipPredicate(child)) continue
        appendInlineMathNode(
            content, child, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
        )
    }
}

private fun AnnotatedString.Builder.appendInlineLink(
    dest: String?,
    labelNode: ASTNode?,
    content: String,
    entries: List<PiInlineMath.Entry>,
    readyIndices: Set<Int>,
    typography: MarkdownTypography,
    colors: MarkdownColors,
    referenceLinks: ReferenceLinkHandler,
    htmlStack: ArrayList<SpanStyle>,
) {
    val body: AnnotatedString.Builder.() -> Unit = {
        if (labelNode != null) {
            for (c in labelNode.children) {
                if (c.type == MarkdownTokenTypes.LBRACKET || c.type == MarkdownTokenTypes.RBRACKET) continue
                appendInlineMathNode(
                    content, c, entries, readyIndices, typography, colors, referenceLinks, htmlStack,
                )
            }
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
    entries: List<PiInlineMath.Entry>,
    readyIndices: Set<Int>,
) {
    if (raw.isEmpty()) return
    if (raw.indexOf(PiInlineMath.SENTINEL_START) < 0) {
        append(raw)
        return
    }
    for (seg in PiInlineMath.splitSegments(raw)) {
        when (seg) {
            is PiInlineMath.Segment.Text -> append(seg.text)
            is PiInlineMath.Segment.MathRef -> {
                val entry = entries.getOrNull(seg.index) ?: continue
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
