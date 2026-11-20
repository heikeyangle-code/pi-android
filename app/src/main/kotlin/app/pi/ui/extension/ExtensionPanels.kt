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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.host.ExtPanel
import app.pi.host.ExtStatus
import app.pi.host.ExtWorking
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.ExpandLabel
import app.pi.ui.blocks.toggleContent
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * 扩展面板堆栈：`ctx.ui.setWidget` 的落点。
 *
 * 为彻底杜绝扩展组件堆积后挤占聊天视口与输入区：
 *  - 整摞面板带有严格的 [maxStackHeight] 高度上限并支持内部纵向滚动；
 *  - 单张卡片默认折叠为紧凑摘要，展开有高度封顶；
 *  - 可交互工厂组件直接给出「操控」胶囊呼出全功能交互表面。
 */
@Composable
fun ExtensionWidgetStack(
    widgets: List<ExtPanel>,
    modifier: Modifier = Modifier,
    onColumns: ((Int) -> Unit)? = null,
    onInteract: ((ExtPanel) -> Unit)? = null,
    maxStackHeight: Dp = DEFAULT_STACK_MAX_HEIGHT,
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
        val scrollModifier = if (maxStackHeight < Dp.Infinity) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxStackHeight)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = scrollModifier
                .padding(horizontal = PiSpacing.composerPageMargin, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
 * 紧凑单行扩展收起条：
 *  - 当软键盘弹出时自动折叠所有大块扩展面板（above/below widgets、footer），绝不遮挡消息或挤爆输入区；
 *  - 当用户手动折叠扩展区时，用这一行保留实时状态预览、计数、一键展开和直接「操控」入口。
 */
@Composable
fun ExtensionDockSummaryBar(
    working: ExtWorking,
    streaming: Boolean,
    statusCount: Int,
    widgetCount: Int,
    hasChrome: Boolean,
    interactivePanel: ExtPanel?,
    collapsedByUser: Boolean,
    imeVisible: Boolean,
    onToggleCollapse: () -> Unit,
    onOpenInspector: () -> Unit,
    onInteract: ((ExtPanel) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val totalItems = widgetCount + (if (hasChrome) 1 else 0) + (if (statusCount > 0) 1 else 0)
    val workingMessage = if (streaming && working.visible) working.message?.takeIf { it.isNotBlank() } else null
    if (totalItems == 0 && workingMessage == null) return

    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin, vertical = 3.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, palette.borderMuted.copy(alpha = 0.55f), shape)
            .clickable(
                onClickLabel = if (imeVisible) "查看全部扩展面板" else if (collapsedByUser) "展开扩展面板" else "收起扩展面板",
                onClick = if (imeVisible) onOpenInspector else onToggleCollapse,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (workingMessage != null) palette.warning else palette.accent),
        )
        if (workingMessage != null) {
            ExtensionSpans(
                spans = remember(workingMessage) { chromeSpans(workingMessage) },
                defaultColor = palette.text,
                style = PiTheme.text.meta,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                richStyle = true,
            )
        } else {
            val summary = buildString {
                append("扩展面板")
                val parts = buildList {
                    if (widgetCount > 0) add("$widgetCount 组件")
                    if (statusCount > 0) add("$statusCount 状态")
                    if (hasChrome) add("顶/底栏")
                }
                if (parts.isNotEmpty()) append(" · ").append(parts.joinToString(" · "))
            }
            Text(
                text = summary,
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (interactivePanel != null && onInteract != null) {
            InteractPill(onClick = { onInteract(interactivePanel) })
        }
        if (imeVisible) {
            Text(
                text = "查看",
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = palette.accent,
            )
        } else {
            Icon(
                imageVector = if (collapsedByUser) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsedByUser) "展开" else "收起",
                modifier = Modifier.size(16.dp),
                tint = palette.muted,
            )
        }
    }
}

/**
 * 扩展面板完整检视抽屉：软键盘开启或面板过多时一键查看全部 header / status / widgets / footer。
 */
@Composable
fun ExtensionWidgetsSheet(
    header: ExtPanel?,
    footer: ExtPanel?,
    statuses: List<ExtStatus>,
    widgets: List<ExtPanel>,
    editorTakeover: Boolean,
    onInteract: (ExtPanel) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "扩展面板",
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "关闭", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                        tint = palette.muted,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (statuses.isNotEmpty() || editorTakeover) {
                    ExtensionStatusStrip(statuses = statuses, editorTakeover = editorTakeover)
                }
                header?.let { h ->
                    ExtensionPanelCard(
                        caption = "顶栏 · ${widgetCaption(h.key) ?: h.key}",
                        lines = h.lines,
                        collapsedRows = 6,
                        onInteract = if (h.interactive && h.surfaceId != null) {
                            { onDismiss(); onInteract(h) }
                        } else {
                            null
                        },
                    )
                }
                widgets.forEach { w ->
                    key(w.key) {
                        ExtensionPanelCard(
                            caption = widgetCaption(w.key) ?: w.key,
                            lines = w.lines,
                            collapsedRows = 6,
                            onInteract = if (w.interactive && w.surfaceId != null) {
                                { onDismiss(); onInteract(w) }
                            } else {
                                null
                            },
                        )
                    }
                }
                footer?.let { f ->
                    ExtensionPanelCard(
                        caption = "底栏 · ${widgetCaption(f.key) ?: f.key}",
                        lines = f.lines,
                        collapsedRows = 6,
                        onInteract = if (f.interactive && f.surfaceId != null) {
                            { onDismiss(); onInteract(f) }
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
 * 扩展工作指示器（`ctx.ui.setWorkingMessage` / `ctx.ui.setWorkingIndicator`）。
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
            .border(PiSpacing.hairline, palette.warning.copy(alpha = 0.28f), pillShape)
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
 * 扩展状态（`ctx.ui.setStatus`）：单行紧凑可横滑徽章胶囊，点击展开自动换行。
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
    val chipShape = RoundedCornerShape(9.dp)

    val pill: @Composable (String, String?, Boolean) -> Unit = { keyText, valueText, isNotice ->
        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(if (isNotice) palette.infoBg else MaterialTheme.colorScheme.surfaceContainer)
                .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.5f), chipShape)
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
                if (editorTakeover) pill("", "扩展编辑器已由原生输入框接管", true)
            }
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach { pill(it.key, it.text, false) }
                if (editorTakeover) pill("", "扩展编辑器已由原生输入框接管", true)
            }
        }
    }
}

/**
 * 扩展顶栏/底栏（`ctx.ui.setHeader` / `ctx.ui.setFooter`）轻量横幅。
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
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.45f), shape)
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .animateContentSize()
            .then(
                when {
                    canExpand -> Modifier.toggleContent(expanded, { expanded = !expanded })
                    onInteract != null -> Modifier.clickable(role = Role.Button, onClick = onInteract)
                    else -> Modifier
                },
            ),
        borderColor = if (onInteract != null) palette.borderAccent.copy(alpha = 0.42f) else palette.borderMuted.copy(alpha = 0.45f),
        padding = BlockCardRowPadding,
        shape = RoundedCornerShape(12.dp),
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
            .background(palette.accent.copy(alpha = 0.16f))
            .border(PiSpacing.hairline, palette.accent.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = "操控",
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
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

internal fun trimEmptyEdgeLines(rows: List<String>): List<String> {
    val first = rows.indexOfFirst { stripAnsiControls(it).isNotBlank() }
    if (first < 0) return emptyList()
    val last = rows.indexOfLast { stripAnsiControls(it).isNotBlank() }
    return rows.subList(first, last + 1)
}

private val CARD_SIDE_PADDING = 10.dp
private val EXPANDED_MAX_HEIGHT = 200.dp
private val DEFAULT_STACK_MAX_HEIGHT = 136.dp
