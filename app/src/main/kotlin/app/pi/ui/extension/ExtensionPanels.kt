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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

const val HIDE_KEY_HEADER = "__ext_header__"
const val HIDE_KEY_FOOTER = "__ext_footer__"
const val HIDE_KEY_STATUSES = "__ext_statuses__"

/**
 * 官方 footer 状态条（`ctx.ui.setStatus`）：当无自定义 `setFooter` 接管时，默认在输入框最底部
 * 以紧凑单行展示所有扩展状态，并支持右侧一键收进扩展芯片、随时在扩展中心恢复。
 */
@Composable
fun ExtensionFooterStatusStrip(
    statuses: List<ExtStatus>,
    onHide: () -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (statuses.isEmpty()) return
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            statuses.forEach { st ->
                val text = remember(st.text) { chromeSpans(st.text) }
                Row(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), pillShape)
                        .clickable(role = Role.Button, onClickLabel = tr("打开扩展中心", "Open extensions"), onClick = onOpenHub)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    ExtensionSpans(
                        spans = text,
                        defaultColor = palette.text,
                        style = PiTheme.text.monoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        richStyle = true,
                    )
                }
            }
        }
        val hideLabel = tr("收起状态栏", "Hide status bar")
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                .clickable(role = Role.Button, onClickLabel = hideLabel) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onHide()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = hideLabel,
                modifier = Modifier.size(13.dp),
                tint = palette.muted,
            )
        }
    }
}

/**
 * 扩展面板堆栈：`ctx.ui.setWidget` / `setHeader` / `setFooter` 的落点。
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
    onHide: ((ExtPanel) -> Unit)? = null,
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
                        onHide = if (onHide != null) { { onHide(widget) } } else null,
                    )
                }
            }
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
 *  - 收纳实时状态（`setStatus`，完整多行不截断，支持一键复制）、扩展面板（`setHeader`/`setWidget`/`setFooter`，
 *    支持一键「收纳/恢复」或「操控」打开交互表面）以及扩展快捷键（`registerShortcut`）。
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
    hiddenWidgetKeys: Set<String> = emptySet(),
    onToggleHideWidget: ((String) -> Unit)? = null,
    onTriggerShortcut: ((String) -> Unit)? = null,
    onOpenCustomEditor: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
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
                        .size(34.dp)
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
                            .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.5f), noticeShape)
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
                            modifier = Modifier.weight(1f),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                        if (onOpenCustomEditor != null) {
                            InteractPill(
                                label = tr("打开", "Open"),
                                onClick = {
                                    onDismiss()
                                    onOpenCustomEditor()
                                },
                            )
                        }
                    }
                }

                if (statusEntries.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            HubSectionHeader(
                                title = tr("实时状态", "Live Status"),
                                count = statusEntries.size,
                            )
                        }
                        if (footer == null && onToggleHideWidget != null) {
                            VisibilityTogglePill(
                                hidden = HIDE_KEY_STATUSES in hiddenWidgetKeys,
                                onClick = { onToggleHideWidget(HIDE_KEY_STATUSES) },
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        statusEntries.forEach { entry ->
                            val rowShape = RoundedCornerShape(12.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), rowShape)
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
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
                                        text = entry.key.ifEmpty { tr("状态", "Status") },
                                        modifier = Modifier.weight(1f),
                                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                                        color = palette.dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val copyLabel = tr("复制状态", "Copy status")
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button, onClickLabel = copyLabel) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                clipboard.setText(AnnotatedString(chromeText(entry.text)))
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = copyLabel,
                                            modifier = Modifier.size(14.dp),
                                            tint = palette.dim,
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
                        hint = if (onToggleHideWidget != null) {
                            tr("默认显示在官方位置，可按需一键收起进芯片", "Shown in official slots by default; hide any to chip")
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
                                hiddenInChat = HIDE_KEY_HEADER in hiddenWidgetKeys,
                                onToggleHide = if (onToggleHideWidget != null) {
                                    { onToggleHideWidget(HIDE_KEY_HEADER) }
                                } else {
                                    null
                                },
                                onInteract = if (h.interactive && h.surfaceId != null) {
                                    { onDismiss(); onInteract(h) }
                                } else {
                                    null
                                },
                            )
                        }
                        widgets.forEach { w ->
                            key(w.key) {
                                val isHidden = w.key in hiddenWidgetKeys
                                val posTag = if (w.placement == PLACEMENT_BELOW) tr("下方", "Below") else tr("上方", "Above")
                                val baseCaption = widgetCaption(w.key) ?: w.key
                                ExtensionPanelCard(
                                    caption = "$baseCaption · $posTag",
                                    lines = w.lines,
                                    collapsedRows = 6,
                                    hiddenInChat = isHidden,
                                    onToggleHide = if (onToggleHideWidget != null) {
                                        { onToggleHideWidget(w.key) }
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
                                hiddenInChat = HIDE_KEY_FOOTER in hiddenWidgetKeys,
                                onToggleHide = if (onToggleHideWidget != null) {
                                    { onToggleHideWidget(HIDE_KEY_FOOTER) }
                                } else {
                                    null
                                },
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
    val pillShape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin, vertical = 3.dp)
            .clip(pillShape)
            .background(palette.infoBg)
            .border(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.28f), pillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            frames.isNotEmpty() -> {
                val glyph = frames[frameIndex % frames.size]
                ExtensionSpans(
                    spans = remember(glyph) { chromeSpans(glyph) },
                    defaultColor = palette.accent,
                    style = PiTheme.text.monoSmall,
                    maxLines = 1,
                    richStyle = true,
                )
            }
            !explicitEmptyFrames -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = palette.accent,
                    trackColor = palette.accent.copy(alpha = 0.16f),
                )
            }
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

@Composable
internal fun ExtensionPanelCard(
    caption: String?,
    lines: List<String>,
    modifier: Modifier = Modifier,
    collapsedRows: Int = COLLAPSED_ROWS,
    hiddenInChat: Boolean = false,
    onToggleHide: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onInteract: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(lines) { trimEmptyEdgeLines(panelRows(lines)) }
    if (rows.isEmpty()) return
    val canExpand = rows.size > collapsedRows
    val shown = if (expanded) rows else rows.take(collapsedRows)
    val geometric = remember(shown) { hasGeometricBoxChars(shown) }
    val wide = remember(shown) { shown.any { isPreformatted(it) } }

    BlockCard(
        color = palette.cardBg,
        modifier = modifier
            .then(
                when {
                    canExpand -> Modifier.toggleContent(expanded, { expanded = !expanded })
                    onInteract != null -> Modifier.clickable(role = Role.Button, onClick = onInteract)
                    else -> Modifier
                },
            ),
        borderColor = if (onInteract != null) palette.borderAccent.copy(alpha = 0.30f) else palette.borderMuted.copy(alpha = 0.45f),
        padding = BlockCardRowPadding,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (caption != null || canExpand || onInteract != null || onToggleHide != null || onHide != null) {
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
                    if (expanded) {
                        val copyLabel = tr("复制内容", "Copy content")
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = copyLabel) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    clipboard.setText(AnnotatedString(rows.joinToString("\n") { chromeText(it) }))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = copyLabel,
                                tint = palette.muted,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (onToggleHide != null) {
                        VisibilityTogglePill(hidden = hiddenInChat, onClick = onToggleHide)
                    } else if (onHide != null) {
                        HideWidgetPill(onClick = onHide)
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
                if (geometric) {
                    GeometricAnsiLines(lines = shown, modifier = Modifier.fillMaxWidth())
                } else if (expanded && wide) {
                    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        shown.forEach { line -> PanelLine(line, wrap = false, clipOverflow = true) }
                    }
                } else {
                    shown.forEach { line -> PanelLine(line, wrap = expanded && !isPreformatted(line)) }
                }
            }
        }
    }
}

/**
 * 渲染扩展输出的 ANSI 文本块（自定义工具 `renderCall`/`renderResult`、自定义消息、自定义条目）：
 *  - 当输出包含 Box Drawing / Block Elements（表格、边框、进度条、终端图表）时，自动切至像素级
 *    [TerminalGridRow] 几何网格渲染，杜绝手机字体回退造成的断线与列错位；
 *  - 普通文本与宽预格式化文本走 [ExtensionSpans]（完整保留 24-bit 色、样式与 OSC 8 可点链接），
 *    并在横向滚动容器里取消强制省略号，避免宽行尾端被无故截断。
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
    val hasGeometric = remember(visible) { hasGeometricBoxChars(visible) }
    val hasWide = remember(visible) { !hasGeometric && visible.any { isPreformatted(it) } }
    val palette = PiTheme.palette

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        when {
            hasGeometric -> {
                GeometricAnsiLines(lines = visible, modifier = Modifier.fillMaxWidth())
            }
            hasWide -> {
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    visible.forEach { line ->
                        PanelLine(line, wrap = false, clipOverflow = true)
                    }
                }
            }
            else -> {
                visible.forEach { line ->
                    PanelLine(line, wrap = true)
                }
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

/**
 * 像素级终端几何网格行组：不依赖 `BoxWithConstraints`（聊天 `LazyColumn` 行内 100% 安全），
 * 通过 `rememberTextMeasurer` 计算单格宽高并支持横向滑动，保证扩展输出的框线、表格与进度条笔直无缝。
 */
@Composable
private fun GeometricAnsiLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val monoStyle = PiTheme.text.monoSmall
    val fontSizePx = with(density) { monoStyle.fontSize.toPx() }
    val cellMetrics = remember(measurer, monoStyle, fontSizePx) {
        val cw = (measurer.measure("0123456789", monoStyle).size.width / 10f).coerceAtLeast(1f)
        val ch = (cw * 2.0f).coerceIn(fontSizePx * 1.14f, fontSizePx * 1.32f).coerceAtLeast(1f)
        cw to ch
    }
    val maxCols = remember(lines) { lines.maxOfOrNull { visibleColumns(it) }?.coerceAtLeast(1) ?: 1 }
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            lines.forEach { line ->
                TerminalGridRow(
                    line = line,
                    cellWidthPx = cellMetrics.first,
                    cellHeightPx = cellMetrics.second,
                    monoStyle = monoStyle,
                    minCols = maxCols,
                )
            }
        }
    }
}

internal fun hasGeometricBoxChars(lines: List<String>): Boolean {
    for (line in lines) {
        var i = 0
        while (i < line.length) {
            val cp = Character.codePointAt(line, i)
            if (isGeometricTerminalGlyph(cp)) return true
            i += Character.charCount(cp)
        }
    }
    return false
}

@Composable
private fun InteractPill(
    onClick: () -> Unit,
    label: String = tr("操控", "Interact"),
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier
            .heightIn(min = 28.dp)
            .clip(shape)
            .background(palette.accent.copy(alpha = 0.14f))
            .border(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.38f), shape)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.TouchApp,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = palette.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun VisibilityTogglePill(hidden: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(7.dp)
    val neutralBg = remember(palette) { lerp(palette.cardBg, palette.text, 0.05f) }
    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(if (!hidden) palette.accent.copy(alpha = 0.11f) else neutralBg)
            .border(
                PiSpacing.hairlineThin,
                if (!hidden) palette.accent.copy(alpha = 0.34f) else palette.borderMuted.copy(alpha = 0.5f),
                shape,
            )
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = if (!hidden) palette.accent else palette.muted,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = if (hidden) tr("已收纳 · 恢复", "Hidden · Show") else tr("显示中 · 收纳", "Shown · Hide"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (!hidden) palette.accent else palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun HideWidgetPill(onClick: () -> Unit) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(7.dp)
    val neutralBg = remember(palette) { lerp(palette.cardBg, palette.text, 0.05f) }
    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(neutralBg)
            .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = tr("收纳", "Hide"),
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PanelLine(raw: String, wrap: Boolean, clipOverflow: Boolean = false) {
    val spans = remember(raw) { chromeSpans(raw) }
    ExtensionSpans(
        spans = if (spans.all { it.text.isEmpty() }) listOf(app.pi.protocol.Ansi.Span(" ")) else spans,
        defaultColor = PiTheme.palette.muted,
        style = PiTheme.text.monoSmall,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = if (clipOverflow) TextOverflow.Clip else TextOverflow.Ellipsis,
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
private val EXPANDED_MAX_HEIGHT = 220.dp
private val DEFAULT_STACK_MAX_HEIGHT = 136.dp
