package app.pi.ui.extension

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtPanel
import app.pi.host.ExtStatus
import app.pi.host.ExtWorking
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.ExpandLabel
import app.pi.ui.blocks.toggleContent
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * 扩展面板：`ctx.ui.setWidget` 的落点——输入框上下各一摞，一个 key 一张卡，按首次出现的顺序。
 *
 * 视觉与交互：
 *  - 卡片顶部用淡色点缀提示可交互性；可交互工厂组件可直接点「操控」胶囊呼出全功能交互面板；
 *  - 收起只占 [COLLAPSED_ROWS] 行，点卡片平滑展开（带高度上限与内部滚动）；
 *  - 表格/框线（[isPreformatted]）横向可滚、普通句子自动换行，兼顾手机阅读和对齐内容。
 */
@Composable
fun ExtensionWidgetStack(
    widgets: List<ExtPanel>,
    modifier: Modifier = Modifier,
    onColumns: ((Int) -> Unit)? = null,
    onInteract: ((ExtPanel) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val mono = PiTheme.text.monoSmall
    val charPx = remember(measurer, mono) { measurer.measure("0", mono).size.width.coerceAtLeast(1) }
    val chromePx = with(density) { (PiSpacing.composerPageMargin * 2 + CARD_SIDE_PADDING * 2 + 2.dp).roundToPx() }

    Column(modifier = modifier.fillMaxWidth()) {
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            widgets.forEach { widget ->
                key(widget.key) {
                    ExtensionPanelCard(
                        caption = widgetCaption(widget.key),
                        lines = widget.lines,
                        onInteract = if (widget.interactive && widget.surfaceId != null && onInteract != null) {
                            { onInteract(widget) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * 扩展工作指示器（`ctx.ui.setWorkingMessage` / `ctx.ui.setWorkingIndicator`）：
 * 紧凑色调胶囊条，按扩展声明的 `frames` 与 `intervalMs` 轮播指示帧，并呈现完整 ANSI 工作提示。
 */
@Composable
fun ExtensionWorkingStrip(
    working: ExtWorking,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!streaming || !working.visible) return
    val frames = working.frames?.filter { it.isNotEmpty() }.orEmpty()
    val message = working.message?.takeIf { it.isNotBlank() }
    if (frames.isEmpty() && message == null) return

    var frameIndex by remember(frames, working.intervalMs) { mutableIntStateOf(0) }
    LaunchedEffect(frames, working.intervalMs) {
        if (frames.size <= 1) return@LaunchedEffect
        val stepMs = (working.intervalMs ?: 120).coerceIn(50, 2000).toLong()
        while (true) {
            delay(stepMs)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    val palette = PiTheme.palette
    val glyph = if (frames.isNotEmpty()) frames[frameIndex % frames.size] else "◌"
    val pillShape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin, vertical = 3.dp)
            .clip(pillShape)
            .background(palette.infoBg)
            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.5f), pillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionSpans(
            spans = remember(glyph) { chromeSpans(glyph) },
            defaultColor = palette.accent,
            style = PiTheme.text.monoSmall,
            maxLines = 1,
            richStyle = true,
        )
        if (message != null) {
            ExtensionSpans(
                spans = remember(message) { chromeSpans(message) },
                defaultColor = palette.text,
                style = PiTheme.text.meta,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                richStyle = true,
            )
        }
    }
}

/**
 * 扩展状态（`ctx.ui.setStatus`）：精致色调徽章胶囊条，按 key 排序。
 * 横向单行紧凑滑动不挤占对话；点一下展开为自动换行的完整胶囊组。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionStatusStrip(
    statuses: List<ExtStatus>,
    editorTakeover: Boolean,
    modifier: Modifier = Modifier,
) {
    val rows = remember(statuses) { statusRows(statuses.map { it.key to it.text }) }
    if (rows.isEmpty() && !editorTakeover) return
    var expanded by remember { mutableStateOf(false) }
    val palette = PiTheme.palette
    val chipShape = RoundedCornerShape(8.dp)

    val pill: @Composable (String, String?, Boolean) -> Unit = { keyText, valueText, isNotice ->
        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(if (isNotice) palette.infoBg else palette.cardBg)
                .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.45f), chipShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (keyText.isNotEmpty()) {
                Text(
                    text = keyText,
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                )
            }
            if (valueText != null) {
                ExtensionSpans(
                    spans = remember(valueText) { chromeSpans(valueText) },
                    defaultColor = if (isNotice) palette.muted else palette.text,
                    style = PiTheme.text.monoSmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    richStyle = true,
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin, vertical = 3.dp)
            .toggleContent(expanded, { expanded = !expanded }),
    ) {
        if (expanded) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach { pill(it.key, it.text, false) }
                if (editorTakeover) pill("", "扩展编辑器已由原生输入框替代", true)
            }
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach { pill(it.key, it.text, false) }
                if (editorTakeover) pill("", "扩展编辑器已由原生输入框替代", true)
            }
        }
    }
}

/**
 * 扩展顶栏/底栏（`ctx.ui.setHeader` / `ctx.ui.setFooter`）的轻量横幅。
 * 相比普通 widget 卡片更贴边紧凑，支持展开与可交互扩展打开全功能交互面板。
 */
@Composable
internal fun ExtensionChromeBanner(
    lines: List<String>,
    collapsedRows: Int,
    modifier: Modifier = Modifier,
    onInteract: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(lines) { trimEmptyEdgeLines(panelRows(lines)) }
    if (rows.isEmpty()) return
    val canExpand = rows.size > collapsedRows
    val shown = if (expanded) rows else rows.take(collapsedRows)
    val wide = remember(rows) { rows.any { isPreformatted(it) } }
    val shape = RoundedCornerShape(9.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(palette.cardBg)
            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.4f), shape)
            .then(if (canExpand) Modifier.toggleContent(expanded, { expanded = !expanded }) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (expanded) Modifier.heightIn(max = EXPANDED_MAX_HEIGHT).verticalScroll(rememberScrollState()) else Modifier),
        ) {
            Column(modifier = if (expanded && wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
                shown.forEach { line -> PanelLine(line, wrap = expanded && !isPreformatted(line)) }
            }
            if (canExpand) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpandLabel(expanded = expanded, expandText = "展开 ${rows.size} 行", collapseText = "收起")
                }
            }
        }
        if (onInteract != null) {
            InteractPill(onClick = onInteract)
        }
    }
}

/**
 * 一块面板：标题（可无）+ 左侧微accent竖条 + 渲染行。
 * [collapsedRows] 行以内不出现「展开」；点卡片在原地展开，高度封顶、内部滚动。
 */
@Composable
internal fun ExtensionPanelCard(
    caption: String?,
    lines: List<String>,
    modifier: Modifier = Modifier,
    collapsedRows: Int = COLLAPSED_ROWS,
    onInteract: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(lines) { trimEmptyEdgeLines(panelRows(lines)) }
    if (rows.isEmpty()) return
    val canExpand = rows.size > collapsedRows
    val shown = if (expanded) rows else rows.take(collapsedRows)
    val wide = remember(rows) { rows.any { isPreformatted(it) } }

    BlockCard(
        color = palette.cardBg,
        modifier = modifier
            .animateContentSize()
            .then(
                when {
                    canExpand -> Modifier.toggleContent(expanded, { expanded = !expanded })
                    onInteract != null -> Modifier.clickable(role = Role.Button, onClick = onInteract)
                    else -> Modifier
                },
            ),
        borderColor = if (onInteract != null) palette.borderAccent.copy(alpha = 0.35f) else palette.borderMuted.copy(alpha = 0.4f),
        padding = BlockCardRowPadding,
        shape = PiShapes.card,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (caption != null || canExpand || onInteract != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (onInteract != null) palette.accent else palette.dim.copy(alpha = 0.7f)),
                    )
                    Text(
                        text = caption.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onInteract != null) {
                        InteractPill(onClick = onInteract)
                    }
                    if (canExpand) ExpandLabel(expanded = expanded, expandText = "展开 ${rows.size} 行", collapseText = "收起")
                }
            }
            val body = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.heightIn(max = EXPANDED_MAX_HEIGHT) else Modifier)
            Column(modifier = if (expanded) body.verticalScroll(rememberScrollState()) else body) {
                Column(modifier = if (expanded && wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
                    shown.forEach { line -> PanelLine(line, wrap = expanded && !isPreformatted(line)) }
                }
            }
        }
    }
}

/**
 * 扩展渲染行公共块（用于工具卡 `renderCall`/`renderResult`、自定义消息 `registerMessageRenderer`、
 * 自定义条目 `registerEntryRenderer`）：
 *  - 自动裁掉首尾无内容空行，避免卡片上下留出难看的空白；
 *  - 智能换行：普通说明文字自动换行适配手机窄屏，仅对表格/框线（[isPreformatted]）开启横向滚动；
 *  - 折叠态按 [collapsedLimit] 裁剪，并给出清晰的展开提示。
 */
@Composable
internal fun RenderedAnsiBlock(
    lines: List<String>,
    expanded: Boolean,
    collapsedLimit: Int,
    modifier: Modifier = Modifier,
    expandHint: ((hidden: Int) -> String)? = null,
) {
    val cleaned = remember(lines) { trimEmptyEdgeLines(panelRows(lines)) }
    if (cleaned.isEmpty()) return
    val visible = if (expanded || cleaned.size <= collapsedLimit) cleaned else cleaned.take(collapsedLimit)
    val hidden = cleaned.size - visible.size
    val hasWide = remember(visible) { visible.any { isPreformatted(it) } }
    val palette = PiTheme.palette

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (hasWide) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                visible.forEach { line ->
                    PanelLine(line, wrap = false)
                }
            }
        } else {
            visible.forEach { line ->
                PanelLine(line, wrap = true)
            }
        }
        if (hidden > 0 && expandHint != null) {
            Text(
                text = expandHint(hidden),
                style = PiTheme.text.meta,
                color = palette.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun InteractPill(onClick: () -> Unit) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(palette.accent.copy(alpha = 0.14f))
            .border(PiSpacing.hairline, palette.accent.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = "操控",
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = palette.accent,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PanelLine(raw: String, wrap: Boolean) {
    val spans = remember(raw) { chromeSpans(raw) }
    ExtensionSpans(
        spans = if (spans.all { it.text.isEmpty() }) listOf(app.pi.rpc.Ansi.Span(" ")) else spans,
        defaultColor = PiTheme.palette.muted,
        style = PiTheme.text.monoSmall,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        richStyle = true,
    )
}

/** 裁掉首尾全是空白或仅含 ANSI 转义的空行，中间空行保留（段落呼吸）。 */
internal fun trimEmptyEdgeLines(rows: List<String>): List<String> {
    val first = rows.indexOfFirst { stripAnsiControls(it).isNotBlank() }
    if (first < 0) return emptyList()
    val last = rows.indexOfLast { stripAnsiControls(it).isNotBlank() }
    return rows.subList(first, last + 1)
}

private val CARD_SIDE_PADDING = 10.dp
private val EXPANDED_MAX_HEIGHT = 280.dp
