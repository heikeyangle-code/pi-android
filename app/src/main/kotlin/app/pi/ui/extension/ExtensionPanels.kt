package app.pi.ui.extension

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
import app.pi.i18n.tr
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
    onUnpin: ((ExtPanel) -> Unit)? = null,
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
                        onUnpin = if (onUnpin != null) { { onUnpin(widget) } } else null,
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
                onClickLabel = if (imeVisible) {
                    tr("查看全部扩展面板", "View all extension panels")
                } else if (collapsedByUser) {
                    tr("展开扩展面板", "Expand extension panels")
                } else {
                    tr("收起扩展面板", "Collapse extension panels")
                },
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
                append(tr("扩展面板", "Extensions"))
                val parts = buildList {
                    if (widgetCount > 0) add(tr("$widgetCount 组件", "$widgetCount widget(s)"))
                    if (statusCount > 0) add(tr("$statusCount 状态", "$statusCount status"))
                    if (hasChrome) add(tr("顶/底栏", "header/footer"))
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
                text = tr("查看", "View"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = palette.accent,
            )
        } else {
            Icon(
                imageVector = if (collapsedByUser) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsedByUser) tr("展开", "Expand") else tr("收起", "Collapse"),
                modifier = Modifier.size(16.dp),
                tint = palette.muted,
            )
        }
    }
}

/** 扩展中心内展示的一键扩展快捷键条目（来自 `pi.registerShortcut`）。 */
data class ExtensionHubShortcut(
    val key: String,
    val label: String,
    val description: String?,
)

/**
 * 扩展中心（Extension Hub）：
 *  - 收纳实时状态（`setStatus`，完整多行不截断）、扩展面板（`setHeader`/`setWidget`/`setFooter`，
 *    支持一键「置顶」常驻输入框上方或「操控」打开交互表面）以及扩展快捷键（`registerShortcut`）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionWidgetsSheet(
    header: ExtPanel?,
    footer: ExtPanel?,
    statuses: List<ExtStatus>,
    widgets: List<ExtPanel>,
    editorTakeover: Boolean,
    onInteract: (ExtPanel) -> Unit,
    onDismiss: () -> Unit,
    shortcuts: List<ExtensionHubShortcut> = emptyList(),
    pinnedWidgetKeys: Set<String> = emptySet(),
    onTogglePinWidget: ((String) -> Unit)? = null,
    onTriggerShortcut: ((String) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val statusEntries = remember(statuses) { statusRows(statuses.map { it.key to it.text }) }
    val panelCount = widgets.size + (if (header != null) 1 else 0) + (if (footer != null) 1 else 0)
    val subtitle = buildList {
        if (statusEntries.isNotEmpty()) add(tr("${statusEntries.size} 状态", "${statusEntries.size} status"))
        if (panelCount > 0) add(tr("$panelCount 面板", "$panelCount panel(s)"))
        if (shortcuts.isNotEmpty()) add(tr("${shortcuts.size} 快捷键", "${shortcuts.size} shortcut(s)"))
    }.joinToString(" · ")

    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tr("扩展中心", "Extensions"),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val closeLabel = tr("关闭", "Close")
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = closeLabel, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = closeLabel,
                        modifier = Modifier.size(18.dp),
                        tint = palette.muted,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editorTakeover) {
                    val noticeShape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(noticeShape)
                            .background(palette.infoBg)
                            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.5f), noticeShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                        )
                        Text(
                            text = tr("扩展编辑器已由原生输入框接管", "Extension editor is handled by the native composer"),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                }

                if (statusEntries.isNotEmpty()) {
                    HubSectionHeader(
                        title = tr("实时状态", "Live Status"),
                        count = statusEntries.size,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        statusEntries.forEach { entry ->
                            val rowShape = RoundedCornerShape(11.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.45f), rowShape)
                                    .padding(horizontal = 11.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (entry.key.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(palette.accent),
                                        )
                                        Text(
                                            text = entry.key,
                                            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                                            color = palette.dim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                ExtensionSpans(
                                    spans = remember(entry.text) { chromeSpans(entry.text) },
                                    defaultColor = palette.text,
                                    style = PiTheme.text.monoSmall,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                    richStyle = true,
                                )
                            }
                        }
                    }
                }

                if (panelCount > 0) {
                    HubSectionHeader(
                        title = tr("扩展面板", "Panels"),
                        count = panelCount,
                        hint = if (widgets.isNotEmpty() && onTogglePinWidget != null) {
                            tr("置顶后会常驻在输入框上方", "Pinned widgets stay above the composer")
                        } else {
                            null
                        },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        header?.let { h ->
                            ExtensionPanelCard(
                                caption = tr("顶栏", "Header") + " · ${widgetCaption(h.key) ?: h.key}",
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
                                val isPinned = w.key in pinnedWidgetKeys
                                ExtensionPanelCard(
                                    caption = widgetCaption(w.key) ?: w.key,
                                    lines = w.lines,
                                    collapsedRows = 6,
                                    pinned = isPinned,
                                    onTogglePin = if (onTogglePinWidget != null) {
                                        { onTogglePinWidget(w.key) }
                                    } else {
                                        null
                                    },
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
                                caption = tr("底栏", "Footer") + " · ${widgetCaption(f.key) ?: f.key}",
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

                if (shortcuts.isNotEmpty() && onTriggerShortcut != null) {
                    HubSectionHeader(
                        title = tr("扩展快捷键", "Shortcuts"),
                        count = shortcuts.size,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        shortcuts.forEach { sc ->
                            val chipShape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.55f), chipShape)
                                    .clickable(role = Role.Button) {
                                        onDismiss()
                                        onTriggerShortcut(sc.key)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = sc.label,
                                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.accent,
                                    maxLines = 1,
                                )
                                val desc = sc.description?.takeIf { it.isNotBlank() }
                                if (desc != null) {
                                    Text(
                                        text = desc,
                                        style = PiTheme.text.meta,
                                        color = palette.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (statusEntries.isEmpty() && panelCount == 0 && shortcuts.isEmpty() && !editorTakeover) {
                    Text(
                        text = tr("当前会话暂无活跃的扩展状态或面板。", "No active extension status or panels in this session."),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubSectionHeader(
    title: String,
    count: Int,
    hint: String? = null,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = palette.muted,
        )
        Text(
            text = count.toString(),
            style = PiTheme.text.monoSmall,
            color = palette.dim,
        )
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = hint,
                style = PiTheme.text.meta,
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val explicitEmptyFrames = working.frames != null && working.frames.isEmpty()
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
    val glyph: String? = when {
        frames.isNotEmpty() -> frames[frameIndex % frames.size]
        explicitEmptyFrames -> null
        else -> "◌"
    }
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
        if (glyph != null) {
            ExtensionSpans(
                spans = remember(glyph) { chromeSpans(glyph) },
                defaultColor = palette.accent,
                style = PiTheme.text.monoSmall,
                maxLines = 1,
                richStyle = true,
            )
        }
        if (message != null) {
            ExtensionSpans(
                spans = remember(message) { chromeSpans(message) },
                defaultColor = palette.text,
                style = PiTheme.text.meta,
                modifier = Modifier.weight(1f),
                maxLines = 1,
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
                if (editorTakeover) {
                    pill("", tr("扩展编辑器已由原生输入框接管", "Extension editor handled by native composer"), true)
                }
            }
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEach { pill(it.key, it.text, false) }
                if (editorTakeover) {
                    pill("", tr("扩展编辑器已由原生输入框接管", "Extension editor handled by native composer"), true)
                }
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
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.45f), shape)
            .then(if (canExpand) Modifier.toggleContent(expanded, { expanded = !expanded }) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            }
            if (canExpand || onInteract != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canExpand) {
                        ExpandLabel(
                            expanded = expanded,
                            expandText = tr("展开 ${rows.size} 行", "Show ${rows.size} lines"),
                            collapseText = tr("收起", "Collapse"),
                        )
                    }
                    if (onInteract != null) {
                        InteractPill(onClick = onInteract)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExtensionPanelCard(
    caption: String?,
    lines: List<String>,
    modifier: Modifier = Modifier,
    collapsedRows: Int = COLLAPSED_ROWS,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
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
            .then(
                when {
                    canExpand -> Modifier.toggleContent(expanded, { expanded = !expanded })
                    onInteract != null -> Modifier.clickable(role = Role.Button, onClick = onInteract)
                    else -> Modifier
                },
            ),
        borderColor = if (onInteract != null) palette.borderAccent.copy(alpha = 0.28f) else null,
        padding = BlockCardRowPadding,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (caption != null || canExpand || onInteract != null || onTogglePin != null || onUnpin != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    if (onTogglePin != null) {
                        PinTogglePill(pinned = pinned, onClick = onTogglePin)
                    } else if (onUnpin != null) {
                        UnpinPill(onClick = onUnpin)
                    }
                    if (onInteract != null) {
                        InteractPill(onClick = onInteract)
                    }
                    if (canExpand) {
                        ExpandLabel(
                            expanded = expanded,
                            expandText = tr("展开 ${rows.size} 行", "Show ${rows.size} lines"),
                            collapseText = tr("收起", "Collapse"),
                        )
                    }
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
            text = tr("操控", "Interact"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = palette.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun PinTogglePill(pinned: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (pinned) palette.accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                PiSpacing.hairline,
                if (pinned) palette.accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.55f),
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (pinned) tr("已置顶", "Pinned") else tr("置顶", "Pin"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (pinned) palette.accent else palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun UnpinPill(onClick: () -> Unit) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
            .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.5f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = tr("收纳", "Hide"),
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PanelLine(raw: String, wrap: Boolean) {
    val spans = remember(raw) { chromeSpans(raw) }
    ExtensionSpans(
        spans = if (spans.all { it.text.isEmpty() }) listOf(app.pi.protocol.Ansi.Span(" ")) else spans,
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
