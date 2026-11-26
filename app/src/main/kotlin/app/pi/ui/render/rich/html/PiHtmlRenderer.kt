package app.pi.ui.render.rich.html

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.render.LocalPiImageTransformer
import app.pi.ui.render.PiCodeLanguage
import app.pi.ui.render.PiRichCardFrame
import app.pi.ui.render.rememberPiHighlightedCode
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiTheme

/**
 * 安全 HTML 的 Compose 渲染器（方案 §六）。
 *
 * 输入是 [PiHtmlDocument] 产出的纯节点树，因此这里**不解析 HTML**、不碰 WebView、
 * 不执行脚本或 CSS。每个节点只映射到 Compose 原生组件：
 *
 * - [PiHtmlNode.Block] → 段落 / 容器 / 引用 / 分隔线；
 * - [PiHtmlNode.Inline] → `AnnotatedString` 的 `SpanStyle`；
 * - [PiHtmlNode.Details] → 可折叠的 [HtmlDetails]，嵌套时每层独立 `remember`；
 * - [PiHtmlNode.List] / [PiHtmlNode.Table] → 原生列表 / 表格；
 * - [PiHtmlNode.Image] → 只把 URL 交给现有图片通道（`LocalPiImageTransformer`），
 *   拿不到字节时显示 alt + URL，不静默消失；
 * - [PiHtmlNode.Link] → `LocalUriHandler`；只打开 `http/https/mailto`，
 *   其他协议（`javascript:`、`file:`、相对路径……）只显示原文，不点击；
 * - [PiHtmlNode.Code] → 等宽文本；块级代码横向滚动。
 *
 * 未知标签不会走到这里（文档层已经透明展开或包成 [PiHtmlNode.Inline]），但渲染器
 * 对所有未知 tag 仍然只画子节点，确保文字不会因为“不认识”而消失。
 */
@Composable
fun PiHtmlRenderer(
    nodes: PiHtmlNodes,
    modifier: Modifier = Modifier,
) {
    RenderBlocks(
        nodes = nodes,
        style = piHtmlBodyStyle(),
        modifier = modifier,
    )
}

/**
 * 块序列。顶层和每个块容器共用一个入口，所以嵌套 `div`/`blockquote` 的间距一致。
 */
@Composable
private fun RenderBlocks(
    nodes: PiHtmlNodes,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (nodes.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        nodes.forEach { node -> RenderNode(node, style) }
    }
}

@Composable
private fun RenderNode(node: PiHtmlNode, style: TextStyle) {
    when (node) {
        is PiHtmlNode.Text -> Text(text = node.text, style = style)
        is PiHtmlNode.Block -> RenderBlock(node, style)
        is PiHtmlNode.Inline -> InlineArea(node.children, style)
        is PiHtmlNode.Details -> HtmlDetails(node, style)
        is PiHtmlNode.List -> HtmlList(node, style)
        is PiHtmlNode.Table -> HtmlTable(node, style)
        is PiHtmlNode.Image -> HtmlImage(node)
        is PiHtmlNode.Link -> InlineArea(listOf(node), style)
        is PiHtmlNode.Code -> {
            if (node.block) {
                HtmlCode(node)
            } else {
                InlineArea(listOf(node), style)
            }
        }
    }
}

@Composable
private fun RenderBlock(node: PiHtmlNode.Block, style: TextStyle) {
    val palette = PiTheme.palette
    when (node.tag) {
        "p" -> InlineArea(node.children, style)

        "div", "li", "figure" -> RenderBlocks(node.children, style)

        "blockquote" -> {
            val quoteShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
            val railColor = palette.mdQuoteBorder.copy(alpha = 0.78f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(quoteShape)
                    .background(palette.cardBg.copy(alpha = 0.72f))
                    .drawBehind {
                        val railW = 2.5.dp.toPx()
                        drawRoundRect(
                            color = railColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(railW, size.height),
                            cornerRadius = CornerRadius(railW / 2f, railW / 2f),
                        )
                    }
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                RenderBlocks(
                    nodes = node.children,
                    style = style.copy(color = palette.mdQuote),
                )
            }
        }

        "figcaption" -> RenderBlocks(
            nodes = node.children,
            style = style.copy(color = palette.muted),
            modifier = Modifier.padding(start = 4.dp),
        )

        "hr" -> HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = palette.mdHr.copy(alpha = 0.28f),
        )

        // 未知块：不丢内容，透明地画子节点。文档层对未知标签通常已经展开；这里
        // 是调用方手工构造节点时的兜底。
        else -> RenderBlocks(node.children, style)
    }
}

/**
 * 一段行内内容：文字和图片分开画。
 *
 * 普通 HTML 段落几乎只有文字，所以最常见的情况是一个 `AnnotatedString`、一个
 * `Text`。只有段落里真的出现 `<img>` 时才退化成“文字段 / 图片 / 文字段”的列：
 * Compose 的 `Text` 内联图片需要预先知道尺寸，而图片解码是异步的，硬塞进
 * `InlineTextContent` 会让图片在测量阶段反复改变段落高度。拆开画虽然牺牲了这一处
 * 的行内流式排版，但保证了顺序、可见性和不阻塞测量。
 */
@Composable
private fun InlineArea(
    nodes: PiHtmlNodes,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val runs = remember(nodes) { splitInlineRuns(nodes) }
    if (runs.size == 1) {
        when (val run = runs[0]) {
            is InlineRun.Text -> HtmlInlineText(run.nodes, style, modifier)
            is InlineRun.Picture -> HtmlImage(run.node, modifier)
        }
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        runs.forEach { run ->
            when (run) {
                is InlineRun.Text -> HtmlInlineText(run.nodes, style)
                is InlineRun.Picture -> HtmlImage(run.node)
            }
        }
    }
}

private sealed interface InlineRun {
    data class Text(val nodes: PiHtmlNodes) : InlineRun
    data class Picture(val node: PiHtmlNode.Image) : InlineRun
}

private fun splitInlineRuns(nodes: PiHtmlNodes): List<InlineRun> {
    val out = ArrayList<InlineRun>()
    var text = ArrayList<PiHtmlNode>()

    fun flushText() {
        if (text.isNotEmpty()) {
            out.add(InlineRun.Text(text))
            text = ArrayList()
        }
    }

    for (node in nodes) {
        if (node is PiHtmlNode.Image) {
            flushText()
            out.add(InlineRun.Picture(node))
        } else {
            text.add(node)
        }
    }
    flushText()
    if (out.isEmpty()) out.add(InlineRun.Text(emptyList()))
    return out
}

@Composable
private fun HtmlInlineText(
    nodes: PiHtmlNodes,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val codeFamily = PiTheme.typography.codeFontFamily()
    val uriHandler = LocalUriHandler.current
    val annotated = remember(nodes, palette, codeFamily) {
        buildHtmlAnnotatedString(nodes, palette, codeFamily)
    }
    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    val hasLink = remember(annotated) {
        annotated.length > 0 &&
            annotated.getStringAnnotations(PI_HTML_URL_TAG, 0, annotated.length).isNotEmpty()
    }

    val clickModifier = if (hasLink) {
        modifier.pointerInput(annotated, uriHandler) {
            detectTapGestures { position ->
                val textLayout = layout ?: return@detectTapGestures
                if (annotated.length == 0) return@detectTapGestures
                val offset = textLayout
                    .getOffsetForPosition(position)
                    .coerceIn(0, annotated.length - 1)
                annotated
                    .getStringAnnotations(PI_HTML_URL_TAG, offset, offset + 1)
                    .firstOrNull()
                    ?.let { annotation ->
                        // 只打开已通过 [isSafeOpenableUrl] 的协议；这里再包一层
                        // runCatching，避免没有对应 Activity 时把点击变成崩溃。
                        runCatching { uriHandler.openUri(annotation.item) }
                    }
            }
        }
    } else {
        modifier
    }

    Text(
        text = annotated,
        style = style,
        modifier = clickModifier,
        onTextLayout = { layout = it },
    )
}

/**
 * 链接标签。行内文字用 `StringAnnotation` 保存 URL，点击时由 [HtmlInlineText] 的
 * `pointerInput` 读出并交给 `LocalUriHandler`；这样不依赖 Compose 版本的
 * `LinkAnnotation` API，也不会把 URL 拼进可见文字。
 */
private const val PI_HTML_URL_TAG = "pi.html.url"

private fun buildHtmlAnnotatedString(
    nodes: PiHtmlNodes,
    palette: PiPalette,
    codeFamily: androidx.compose.ui.text.font.FontFamily = PiMonoFamily,
): AnnotatedString = buildAnnotatedString {
    appendHtmlInline(nodes, palette, codeFamily)
}

private fun AnnotatedString.Builder.appendHtmlInline(
    nodes: PiHtmlNodes,
    palette: PiPalette,
    codeFamily: androidx.compose.ui.text.font.FontFamily,
) {
    for (node in nodes) {
        when (node) {
            is PiHtmlNode.Text -> append(node.text)

            is PiHtmlNode.Inline -> {
                if (node.tag == "br") {
                    append('\n')
                } else {
                    val span = inlineSpanFor(node.tag, palette, codeFamily)
                    if (span == null) {
                        appendHtmlInline(node.children, palette, codeFamily)
                    } else {
                        withStyle(span) { appendHtmlInline(node.children, palette, codeFamily) }
                    }
                }
            }

            is PiHtmlNode.Link -> appendHtmlLink(node, palette, codeFamily)

            is PiHtmlNode.Code -> {
                if (node.block) {
                    // 块级代码被误放进行内缓冲时，仍然保留文字和换行，不丢。
                    append('\n')
                    withStyle(inlineCodeSpan(palette, codeFamily)) { append(node.text) }
                    append('\n')
                } else {
                    withStyle(inlineCodeSpan(palette, codeFamily)) { append(node.text) }
                }
            }

            is PiHtmlNode.Image -> append(inlineImageLabel(node))

            // 正常路径不会把块节点放进行内缓冲；真出现时按纯文字降级。
            is PiHtmlNode.Block,
            is PiHtmlNode.Details,
            is PiHtmlNode.List,
            is PiHtmlNode.Table -> append(plainText(node))
        }
    }
}

private fun AnnotatedString.Builder.appendHtmlLink(
    node: PiHtmlNode.Link,
    palette: PiPalette,
    codeFamily: androidx.compose.ui.text.font.FontFamily,
) {
    val href = node.href.trim()
    val openable = isSafeOpenableUrl(href)
    val linkStyle = SpanStyle(
        color = palette.mdLink,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
    )
    val content: AnnotatedString.Builder.() -> Unit = {
        if (node.children.isEmpty()) {
            if (href.isNotEmpty()) append(href)
        } else {
            appendHtmlInline(node.children, palette, codeFamily)
        }
    }

    if (openable) {
        pushStringAnnotation(PI_HTML_URL_TAG, href)
        withStyle(linkStyle) { content() }
        pop()
    } else {
        // 非白名单协议：链接文字按普通文字显示，不画下划线、不生成可点链接；
        // 有 URL 时以暗色附在后面，保证协议和地址本身也不被吞掉。
        content()
        if (href.isNotEmpty() && node.children.isNotEmpty()) {
            withStyle(SpanStyle(color = palette.dim)) { append("（$href）") }
        }
    }
}

private fun inlineSpanFor(
    tag: String,
    palette: PiPalette,
    codeFamily: androidx.compose.ui.text.font.FontFamily,
): SpanStyle? = when (tag) {
    "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
    "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
    "del", "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "mark" -> SpanStyle(background = palette.warning.copy(alpha = 0.28f))
    "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript)
    "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript)
    "code", "kbd" -> inlineCodeSpan(palette, codeFamily)
    else -> null
}

private fun inlineCodeSpan(
    palette: PiPalette,
    codeFamily: androidx.compose.ui.text.font.FontFamily,
): SpanStyle = SpanStyle(
    fontFamily = codeFamily,
    color = palette.mdCode,
    background = palette.borderMuted.copy(alpha = 0.34f),
    fontFeatureSettings = app.pi.ui.theme.PiFontFeatures.CODE_LIGATURES_OFF,
)

/**
 * 只允许明确许可的协议。这个检查是渲染兜底，不是 HTML 策略：Policy 负责把
 * `javascript:` 等从文档里清掉；即使漏掉，这里也不会生成可点击链接。
 */
private fun isSafeOpenableUrl(url: String): Boolean {
    val scheme = safeScheme(url) ?: return false
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}

/**
 * 图片 URL 的渲染兜底：只交给现有图片通道 http(s)。
 *
 * Policy 已经只保留 http(s) 图片；这里再挡一次，是因为现有图片通道也认识
 * `file:` / guest 路径，而 HTML 层绝不该因为策略漏项去读本地文件。
 */
private fun isSafeImageUrl(url: String): Boolean {
    val scheme = safeScheme(url) ?: return false
    return scheme == "http" || scheme == "https"
}

/** 返回小写 scheme；没有 scheme、含空白/控制字符或超长时返回 `null`。 */
private fun safeScheme(url: String): String? {
    if (url.isEmpty() || url.length > 4096) return null
    if (url.any { it.isWhitespace() || it.isISOControl() }) return null
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val scheme = url.substring(0, colon).lowercase()
    if (scheme.isEmpty() || !scheme.first().isLetter()) return null
    if (!scheme.all { it.isLetterOrDigit() || it in "+.-" }) return null
    return scheme
}

private fun inlineImageLabel(node: PiHtmlNode.Image): String {
    val alt = node.alt.ifBlank { tr("图片", "Image") }
    return if (node.src.isBlank()) {
        "[$alt]"
    } else {
        "[$alt: ${node.src}]"
    }
}

/**
 * `<details>`：默认状态严格取 `open` 属性；读者的展开/收起用 `rememberSaveable`，滚出屏幕
 * 再回来不丢。画成统一卡片（12 dp 圆角、淡底 + 28% 边线、展开时头与正文之间一条发丝线），
 * 嵌套的 details 各自一张卡、各自的状态。
 */
@Composable
private fun HtmlDetails(node: PiHtmlNode.Details, style: TextStyle) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    var expanded by rememberSaveable(node.summary.hashCode(), node.body.hashCode()) {
        mutableStateOf(node.open)
    }
    val headerBg = remember(palette) { lerp(palette.cardBg, palette.text, 0.03f) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardBg)
            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.56f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .background(if (expanded) headerBg else Color.Transparent)
                .clickable(
                    onClickLabel = if (expanded) tr("收起详情", "Collapse details") else tr("展开详情", "Expand details"),
                ) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp),
            )
            if (node.summary.isEmpty()) {
                Text(
                    text = tr("详情", "Details"),
                    style = style.copy(
                        color = palette.mdHeading,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            } else {
                InlineArea(
                    nodes = node.summary,
                    style = style.copy(
                        color = palette.mdHeading,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.borderMuted.copy(alpha = 0.42f)),
            )
            RenderBlocks(
                nodes = node.body,
                style = style,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun HtmlList(node: PiHtmlNode.List, style: TextStyle) {
    val palette = PiTheme.palette
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineHeightSp = style.lineHeight.value
        .takeIf { it.isFinite() && it > 0f }
        ?: ((style.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 15f) * 1.48f)
    val markerHeightDp = with(density) { (lineHeightSp * density.fontScale).dp }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        node.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (node.ordered) {
                    Text(
                        text = "${node.start.toLong() + index}.",
                        style = style.copy(color = palette.mdListBullet),
                        modifier = Modifier
                            .widthIn(min = 22.dp)
                            .padding(end = 4.dp),
                    )
                } else {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .width(18.dp)
                            .height(markerHeightDp),
                    ) {
                        drawCircle(
                            color = palette.mdListBullet,
                            radius = 2.35.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(6.dp.toPx(), size.height * 0.5f),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    RenderBlocks(item.children, style)
                }
            }
        }
    }
}

/** 132 dp 让两列在 360 dp 手机上放得下，三列起横向滚动。 */
private val PI_HTML_TABLE_CELL_WIDTH = 132.dp

/**
 * 与 Markdown 表格同一套外观：12 dp 圆角外框（28% 边线）、表头淡底（14%）、行与行之间一条
 * 发丝线、数据行隔行淡底；没有单元格四周的网格线。整行用 `IntrinsicSize.Min` 取最高的那格，
 * 行底色因此铺满整行，不会在矮格下面留缝。
 */
@Composable
private fun HtmlTable(node: PiHtmlNode.Table, style: TextStyle) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        val caption = node.caption
        if (caption != null && caption.isNotEmpty()) {
            InlineArea(
                nodes = caption,
                style = style.copy(color = palette.muted),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (node.rows.isNotEmpty()) {
            // 横向滚动容器里 `fillMaxWidth` 没有有限宽可填（发丝线会变成 0 宽），所以列显式给出
            // 表宽 = 最宽那一行各格宽度之和；外框不 fillMaxWidth：窄表紧贴内容，宽表由滚动容器收到屏宽。
            val tableWidth = node.rows.maxOf { row ->
                row.cells.sumOf { (PI_HTML_TABLE_CELL_WIDTH.value * it.colspan.coerceIn(1, 4)).toDouble() }
            }.toFloat().dp
            val headerBg = lerp(palette.cardBg, palette.text, 0.055f)
            val zebraBg = lerp(palette.cardBg, palette.text, 0.025f)
            val borderColor = palette.borderMuted.copy(alpha = 0.56f)
            val dividerColor = palette.borderMuted.copy(alpha = 0.30f)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(palette.cardBg)
                    .border(0.5.dp, borderColor, shape),
            ) {
                Column(Modifier.horizontalScroll(scroll).width(tableWidth)) {
                    var dataIndex = 0
                    node.rows.forEachIndexed { rowIndex, row ->
                        val headerRow = row.header || row.cells.all { it.header }
                        val background = when {
                            headerRow -> headerBg
                            dataIndex++ % 2 == 1 -> zebraBg
                            else -> Color.Transparent
                        }
                        if (rowIndex > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(dividerColor),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .background(background),
                        ) {
                            row.cells.forEach { cell ->
                                HtmlTableCell(cell, row.header, style)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlTableCell(
    cell: PiHtmlTableCell,
    rowHeader: Boolean,
    style: TextStyle,
) {
    val header = cell.header || rowHeader
    val width = (PI_HTML_TABLE_CELL_WIDTH.value * cell.colspan.coerceIn(1, 4)).dp
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        RenderBlocks(
            nodes = cell.children,
            style = if (header) style.copy(fontWeight = FontWeight.Bold) else style,
        )
    }
}

@Composable
private fun HtmlCode(node: PiHtmlNode.Code) {
    val palette = PiTheme.palette
    // 与 Markdown 围栏代码块同一张卡：语言胶囊 + 复制 + 发丝线（`PiRichCardFrame`）。
    val info = remember(node.language) { FenceInfo.parse(node.language) }
    val normalizedLang = remember(info.language) { PiCodeLanguage.normalize(info.language) }
    val highlighted = rememberPiHighlightedCode(node.text, normalizedLang)
    PiRichCardFrame(node.text, info, palette) {
        Text(
            text = highlighted.text,
            style = PiTheme.text.code.copy(
                color = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * 图片：只把 URL 交给现有通道，不在 HTML 层读文件/联网。
 *
 * `transform` 是 `@Composable` 且内部有缓存，所以这里不会为每帧重复解码。拿不到
 * 字节（默认 NoOp、离线、超时、文件不在）时显示 alt + URL，与 Markdown 图片的
 * 回退保持一致：绝不静默消失。
 */
@Composable
private fun HtmlImage(node: PiHtmlNode.Image, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    val transformer = LocalPiImageTransformer.current
    val image = if (isSafeImageUrl(node.src)) transformer.transform(node.src) else null

    if (image != null) {
        Image(
            painter = image.painter,
            contentDescription = node.alt.ifBlank { node.title ?: node.src },
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentScale = ContentScale.Fit,
        )
        return
    }

    app.pi.ui.render.PiImageFailureCard(
        alt = node.alt,
        link = node.src,
        altStyle = piHtmlBodyStyle(),
        modifier = modifier,
    )
}

@Composable
private fun piHtmlBodyStyle(): TextStyle {
    val palette = PiTheme.palette
    val profile = PiTheme.typography
    return profile.styles.prose.copy(
        fontFamily = profile.proseFontFamily(),
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = profile.proseFontFeatures(),
        lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
        color = palette.text,
    )
}

/** 块节点被误放进行内缓冲时的纯文字降级。 */
private fun plainText(node: PiHtmlNode): String = when (node) {
    is PiHtmlNode.Text -> node.text
    is PiHtmlNode.Block -> node.children.joinToString(" ") { plainText(it) }
    is PiHtmlNode.Inline -> node.children.joinToString("") { plainText(it) }
    is PiHtmlNode.Details -> buildString {
        append(node.summary.joinToString("") { plainText(it) })
        if (node.summary.isNotEmpty() && node.body.isNotEmpty()) append('\n')
        append(node.body.joinToString("\n") { plainText(it) })
    }
    is PiHtmlNode.List -> node.items.joinToString("\n") { item ->
        item.children.joinToString(" ") { plainText(it) }
    }
    is PiHtmlNode.Table -> buildString {
        if (!node.caption.isNullOrEmpty()) {
            append(node.caption.joinToString(" ") { plainText(it) })
            if (node.rows.isNotEmpty()) append('\n')
        }
        append(
            node.rows.joinToString("\n") { row ->
                row.cells.joinToString(" ") { cell ->
                    cell.children.joinToString(" ") { plainText(it) }
                }
            },
        )
    }
    is PiHtmlNode.Image -> inlineImageLabel(node)
    is PiHtmlNode.Link -> node.children.joinToString("") { plainText(it) }
        .ifEmpty { node.href }
    is PiHtmlNode.Code -> node.text
}
