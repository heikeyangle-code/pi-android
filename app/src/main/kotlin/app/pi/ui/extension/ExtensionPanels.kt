package app.pi.ui.extension

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.ExpandLabel
import app.pi.ui.blocks.toggleContent
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 扩展面板：`ctx.ui.setWidget` 的落点——输入框上下各一摞，一个 key 一张卡，按首次出现的顺序。
 *
 * SDK 给扩展的 widget 只有两种内容：字符串行，和一个会自己重绘的终端组件（工厂）。宿主已把后者
 * 渲染成同样的「带 ANSI 的行」（`ui.surface`，见 `host/SurfaceWidgets`），所以这里**只有一条绘制路径**，
 * 不区分来源，也不认识任何具体扩展：
 *
 *  - 收起：前 [COLLAPSED_ROWS] 行，每行一行不折行。面板是常驻的，一次推送不该把对话挤出屏幕。
 *  - 展开（点卡片）：全部行，高度封顶 [EXPANDED_MAX_HEIGHT]、内部可纵向滚；
 *    表格 / 框线（[isPreformatted]）整块一起横向滚，列对齐就是内容，折行等于毁掉它；普通句子照常折行。
 *  - 颜色：扩展用 `theme.fg(…)` 写的 SGR 经 `Ansi.parse` → 主题令牌映射，跟着用户主题走。
 *  - 标题：key 的人话版（`web-activity` → `web activity`）。宿主不知道它「是什么」，所以只换分隔符、不翻译。
 *
 * 宽度：组件按「字符列数」渲染。这里量出一行等宽字符能放多少列，经 [onColumns] 告诉宿主，
 * 宿主据此重绘所有表面——否则工厂组件永远按默认 40 列排版。
 */
@Composable
fun ExtensionWidgetStack(
    widgets: List<ExtensionWidget>,
    modifier: Modifier = Modifier,
    onColumns: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val mono = PiTheme.text.monoSmall
    val charPx = remember(measurer, mono) { measurer.measure("0", mono).size.width.coerceAtLeast(1) }
    // 左右：页边距各一份 + 卡片左右内边距各一份 + 描边。取整偏小一点没关系，偏大才会被截。
    val chromePx = with(density) { (PiSpacing.composerPageMargin * 2 + CARD_SIDE_PADDING * 2 + 2.dp).roundToPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        // 一个高 0 的探针：没有面板时也能量出宽度，所以第一个面板出现时宿主手里已经是对的列数。
        if (onColumns != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .onSizeChanged { size -> onColumns(columnsFor(size.width, chromePx, charPx)) },
            )
        }
        if (widgets.isEmpty()) return@Column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            widgets.forEach { widget -> key(widget.key) { WidgetPanel(widget) } }
        }
    }
}

@Composable
private fun WidgetPanel(widget: ExtensionWidget) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(widget.lines) { panelRows(widget.lines) }
    if (rows.isEmpty()) return
    val canExpand = rows.size > COLLAPSED_ROWS
    val shown = if (expanded) rows else rows.take(COLLAPSED_ROWS)
    val wide = remember(rows) { rows.any { isPreformatted(it) } }
    val caption = remember(widget.key) { widgetCaption(widget.key) }

    BlockCard(
        color = palette.cardBg,
        modifier = Modifier
            .animateContentSize()
            .toggleContent(expanded, { if (canExpand) expanded = !expanded }),
        borderColor = palette.borderMuted.copy(alpha = 0.35f),
        padding = BlockCardRowPadding,
        shape = PiShapes.card,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (caption != null || canExpand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = caption.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.monoSmall,
                        color = palette.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (canExpand) ExpandLabel(expanded = expanded, expandText = "展开 ${rows.size} 行", collapseText = "收起")
                }
            }
            val side = rememberScrollState()
            val body = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.heightIn(max = EXPANDED_MAX_HEIGHT) else Modifier)
            Column(
                modifier = if (expanded) body.verticalScroll(rememberScrollState()) else body,
            ) {
                Column(modifier = if (expanded && wide) Modifier.horizontalScroll(side) else Modifier) {
                    shown.forEach { line -> PanelLine(line, wrap = expanded && !isPreformatted(line)) }
                }
            }
        }
    }
}

@Composable
private fun PanelLine(raw: String, wrap: Boolean) {
    val spans = remember(raw) { chromeSpans(raw) }
    ExtensionSpans(
        spans = if (spans.all { it.text.isEmpty() }) listOf(app.pi.rpc.Ansi.Span(" ")) else spans,
        defaultColor = PiTheme.palette.muted,
        style = PiTheme.text.monoSmall,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val CARD_SIDE_PADDING = 10.dp
private val EXPANDED_MAX_HEIGHT = 280.dp
