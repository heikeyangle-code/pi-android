package app.pi.ui.extension

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtCustom
import app.pi.host.ExtPanel
import app.pi.ui.components.PiSheet
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class SurfaceControlMode { Gamepad, Keyboard }

/**
 * 扩展自绘的整块界面（`ctx.ui.custom()` / `tui.showOverlay()`）——手机全屏与手柄触屏适配版。
 *
 * 针对手机上的展示完整度与操作手感做了四项系统优化：
 *  1. **吃满可用屏高，不再白白空出下半屏**：交互式表面与长表面统一使用 `fullHeight = true`，
 *     视口占满 `Column` 的 `weight(1f)`，按实测像素高度精确换算并回传 `ui.resize(cols, rows)`；
 *  2. **浮点精确字宽 + 真正适配整行宽度，告别左右裁切**：以 10 字符均值浮点测宽消除小字号整型截断误差，
 *     默认将 `max(80, 实际最长行)` 完整缩进视口（下限 0.48×），同时可一键切回「标准字号」；
 *  3. **双手拇指十字手柄 + 长按连发 + 视口手势**：默认「手柄」模式提供真正的十字方向键（支持长按连发与触觉反馈）
 *     与右侧拇指动作键区（确认 / 空格 / Esc / Tab / 更多），画面未溢出时也可直接在视口上滑动触发方向键；
 *     一键切「键盘」模式即可输入文本发给组件；
 *  4. **终端提示音开关**：联动 `ui.bell`（`tui.terminal.write("\x07")` / `process.stdout.write("\x07")` / OSC 通知），
 *     顶部可随时一键静音。
 */
@Composable
fun ExtensionSurfaceHost(
    custom: ExtCustom?,
    stackCount: Int = if (custom != null) 1 else 0,
    minimized: Boolean = false,
    minimizedBottomPadding: androidx.compose.ui.unit.Dp = 72.dp,
    soundMuted: Boolean = false,
    onToggleSound: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onRestore: () -> Unit = {},
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit = { _, _, _, _, _ -> },
    onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onClose: (surfaceId: String) -> Unit,
) {
    if (custom == null) return
    val id = custom.surfaceId
    val inferred = remember(custom.lines) { inferSurfaceTitle(custom.lines) }
    val title = inferred ?: "扩展交互面板"
    if (minimized) {
        // 最小化态：贴边低调半透明小胶囊，不挡对话与输入；点一下秒回原界面，点 ✕ 才真正关闭。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp, bottom = minimizedBottomPadding),
            contentAlignment = Alignment.BottomEnd,
        ) {
            val palette = PiTheme.palette
            val shape = RoundedCornerShape(999.dp)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                    .border(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.7f), shape)
                    .clickable(role = Role.Button, onClickLabel = "恢复扩展面板", onClick = onRestore)
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                )
                Text(
                    text = title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                if (stackCount > 1) {
                    SurfaceMetaBadge(text = "$stackCount", tint = palette.accent)
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "关闭扩展面板") { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = palette.dim,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        return
    }
    val useFullHeight = custom.interactive || !custom.overlay || custom.lines.size > FULL_HEIGHT_LINE_THRESHOLD
    // 下滑把手 / 点遮罩 = 最小化（后台保留扩展运行状态，不杀死进程/游戏）；点右上角 ✕ 才真正关闭。
    PiSheet(onDismiss = onMinimize, fullHeight = useFullHeight) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = stackCount,
            lines = custom.lines,
            interactive = custom.interactive,
            blocked = custom.blocked,
            useFullHeight = useFullHeight,
            soundMuted = soundMuted,
            onToggleSound = onToggleSound,
            onMinimize = onMinimize,
            onInput = onInput,
            onMouse = onMouse,
            onResize = onResize,
            onClose = { onClose(id) },
        )
    }
}

/**
 * 非拦截型悬浮 HUD（官方 `OverlayOptions.nonCapturing = true`）：
 * 贴在右下角（输入框上方），**绝不铺遮罩、绝不抢占聊天滚动与输入焦点**。
 * 最多展示 6 行紧凑 ANSI 输出；若组件支持交互，点卡身可一键展开完整操控面板，点 ✕ 直接关闭。
 */
@Composable
fun ExtensionFloatingOverlayCard(
    custom: ExtCustom?,
    bottomPadding: androidx.compose.ui.unit.Dp = 72.dp,
    onExpand: (() -> Unit)? = null,
    onClose: (surfaceId: String) -> Unit,
) {
    if (custom == null) return
    val palette = PiTheme.palette
    val id = custom.surfaceId
    val rows = remember(custom.lines) { trimEmptyEdgeLines(panelRows(custom.lines)) }
    val title = remember(custom.lines) { inferSurfaceTitle(custom.lines) } ?: "悬浮信息"
    val previewRows = if (rows.size > 6) rows.take(6) else rows
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 12.dp, bottom = bottomPadding),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 320.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                .border(PiSpacing.hairline, palette.borderAccent.copy(alpha = 0.45f), shape)
                .then(
                    if (onExpand != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = "展开悬浮面板", onClick = onExpand)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                )
                Text(
                    text = title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onExpand != null && custom.interactive) {
                    Text(
                        text = "操控",
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.accent,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "关闭悬浮面板") { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = palette.dim,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            if (previewRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    previewRows.forEach { line ->
                        PanelLine(raw = line, wrap = !isPreformatted(line))
                    }
                    if (rows.size > previewRows.size) {
                        Text(
                            text = "…还有 ${rows.size - previewRows.size} 行",
                            style = PiTheme.text.meta,
                            color = palette.dim,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可交互工厂组件（`ctx.ui.setWidget(key, factory)`、`setHeader`、`setFooter` 带 `handleInput` / `handleMouse`）的交互面板。
 */
@Composable
fun ExtensionWidgetSurfaceSheet(
    panel: ExtPanel,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit,
    onResize: (columns: Int, rows: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val id = panel.surfaceId ?: return
    val title = when (panel.key) {
        "header" -> "扩展顶栏"
        "footer" -> "扩展底栏"
        else -> widgetCaption(panel.key) ?: "扩展组件"
    }
    val useFullHeight = panel.interactive || panel.lines.size > FULL_HEIGHT_LINE_THRESHOLD
    PiSheet(onDismiss = onDismiss, fullHeight = useFullHeight) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = 1,
            lines = panel.lines,
            interactive = panel.interactive,
            blocked = null,
            useFullHeight = useFullHeight,
            soundMuted = false,
            onToggleSound = {},
            onMinimize = null,
            onInput = onInput,
            onMouse = onMouse,
            onResize = onResize,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun SurfaceSheetBody(
    surfaceId: String,
    title: String,
    stackCount: Int,
    lines: List<String>,
    interactive: Boolean,
    blocked: String?,
    useFullHeight: Boolean,
    soundMuted: Boolean,
    onToggleSound: () -> Unit,
    onMinimize: (() -> Unit)?,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit,
    onResize: (columns: Int, rows: Int) -> Unit,
    onClose: () -> Unit,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    var fitWidth by remember(surfaceId) { mutableStateOf(true) }
    var controlMode by remember(surfaceId) { mutableStateOf(SurfaceControlMode.Gamepad) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val rows = remember(lines) { panelRows(lines) }
    val maxContentCols = remember(rows) {
        rows.maxOfOrNull { visibleColumns(it) } ?: STANDARD_TERMINAL_COLS
    }

    val viewportPadHPx = with(density) { (ViewportPadH * 2).roundToPx() }
    val viewportPadVPx = with(density) { (ViewportPadV * 2).roundToPx() }
    val baseMono = PiTheme.text.monoSmall
    // 用 10 字符求平均浮点宽度，避免单字取整带来的 5%–15% 误差（小字号下最容易把右侧裁掉几列）。
    val baseCharWidthPx = remember(measurer, baseMono) {
        (measurer.measure("0123456789", baseMono).size.width / 10f).coerceAtLeast(1f)
    }
    val targetCols = maxOf(STANDARD_TERMINAL_COLS, maxContentCols.coerceAtMost(MAX_AUTO_FIT_COLS))
    val fitScale = if (fitWidth && viewportWidthPx > viewportPadHPx) {
        val usablePx = (viewportWidthPx - viewportPadHPx - 2).coerceAtLeast(1).toFloat()
        (usablePx / (targetCols * baseCharWidthPx)).coerceIn(MIN_FIT_SCALE, 1f)
    } else {
        1f
    }
    val monoStyle = remember(baseMono, fitScale) {
        if (fitScale >= 0.999f) baseMono
        else baseMono.copy(fontSize = baseMono.fontSize * fitScale, lineHeight = baseMono.lineHeight * fitScale)
    }
    val cellMetrics = remember(measurer, monoStyle) {
        val m = measurer.measure("0123456789", monoStyle).size
        (m.width / 10f).coerceAtLeast(1f) to m.height.coerceAtLeast(1)
    }
    val charWidthPx = cellMetrics.first
    val lineHeightPx = cellMetrics.second

    var measuredCols by remember { mutableIntStateOf(STANDARD_TERMINAL_COLS) }
    var measuredRows by remember { mutableIntStateOf(24) }

    LaunchedEffect(viewportWidthPx, viewportHeightPx, charWidthPx, lineHeightPx) {
        if (viewportWidthPx <= viewportPadHPx) return@LaunchedEffect
        val cols = columnsForFloat(viewportWidthPx, chromePx = viewportPadHPx + 2, charPx = charWidthPx)
        val usableHeight = (viewportHeightPx - viewportPadVPx).coerceAtLeast(lineHeightPx * 8)
        val rowCount = (usableHeight / lineHeightPx).coerceIn(8, 100)
        if (cols != measuredCols || rowCount != measuredRows) {
            measuredCols = cols
            measuredRows = rowCount
            onResize(cols, rowCount)
        }
    }

    val needsHorizontalScroll = remember(maxContentCols, measuredCols) { maxContentCols > measuredCols }
    val needsVerticalScroll = remember(rows.size, measuredRows) { rows.size > measuredRows }
    val swipeToArrowsEnabled = interactive && controlMode == SurfaceControlMode.Gamepad && !needsHorizontalScroll && !needsVerticalScroll
    val latestOnInput by rememberUpdatedState(onInput)

    val outerModifier = Modifier
        .fillMaxWidth()
        .then(if (useFullHeight) Modifier.fillMaxHeight() else Modifier)
        .padding(horizontal = SurfaceSidePadding)

    Column(
        modifier = outerModifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 顶部标题与快捷工具栏 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (interactive) palette.accent else palette.dim),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (stackCount > 1) {
                        SurfaceMetaBadge(text = "${stackCount}层", tint = palette.accent)
                    }
                }
                Text(
                    text = buildString {
                        append("${measuredCols}×${measuredRows}")
                        if (interactive) {
                            append(if (swipeToArrowsEnabled) " · 可滑动/点按/手柄" else " · 点击或用下方操控区")
                        } else {
                            append(" · 只读视图")
                        }
                    },
                    style = PiTheme.text.meta,
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (interactive) {
                SurfaceToolbarPill(
                    label = if (controlMode == SurfaceControlMode.Gamepad) "键盘" else "手柄",
                    active = controlMode == SurfaceControlMode.Keyboard,
                    onClick = {
                        controlMode = if (controlMode == SurfaceControlMode.Gamepad) {
                            SurfaceControlMode.Keyboard
                        } else {
                            SurfaceControlMode.Gamepad
                        }
                    },
                )
            }
            SurfaceToolbarPill(
                label = if (fitWidth) "原字号" else "适配屏",
                active = !fitWidth,
                onClick = { fitWidth = !fitWidth },
            )
            SurfaceToolbarPill(
                label = if (soundMuted) "静音" else "音效",
                active = !soundMuted,
                onClick = onToggleSound,
            )
            if (onMinimize != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(PiSpacing.hairline, palette.borderMuted, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "最小化", onClick = onMinimize),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "—",
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Bold),
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(PiSpacing.hairline, palette.borderMuted, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "关闭", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = palette.muted,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // ---- 阻塞提示横幅 ----
        blocked?.takeIf { it.isNotBlank() }?.let { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PiShapes.cardInner)
                    .background(palette.infoBg)
                    .border(PiSpacing.hairline, palette.warning.copy(alpha = 0.4f), PiShapes.cardInner)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = palette.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = reason,
                    style = PiTheme.text.meta,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- 沉降式终端内容视口（全高模式下 weight(1f) 完整利用屏幕剩余空间） ----
        val swipeThresholdPx = with(density) { 32.dp.toPx() }
        val viewportModifier = Modifier
            .fillMaxWidth()
            .then(
                if (useFullHeight) {
                    Modifier.weight(1f, fill = true)
                } else {
                    Modifier.heightIn(min = 120.dp, max = 380.dp)
                },
            )
            .clip(PiShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(PiSpacing.hairline, palette.borderMuted, PiShapes.card)
            .onSizeChanged { size ->
                viewportWidthPx = size.width
                viewportHeightPx = size.height
            }
            .then(
                if (swipeToArrowsEnabled) {
                    Modifier.pointerInput(surfaceId, swipeThresholdPx) {
                        var accX = 0f
                        var accY = 0f
                        var fired = false
                        detectDragGestures(
                            onDragStart = {
                                accX = 0f
                                accY = 0f
                                fired = false
                            },
                            onDragEnd = {
                                accX = 0f
                                accY = 0f
                                fired = false
                            },
                            onDragCancel = {
                                accX = 0f
                                accY = 0f
                                fired = false
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            accX += dragAmount.x
                            accY += dragAmount.y
                            if (!fired && (abs(accX) >= swipeThresholdPx || abs(accY) >= swipeThresholdPx)) {
                                fired = true
                                val key = if (abs(accX) >= abs(accY)) {
                                    if (accX > 0) SurfaceKeys.RIGHT else SurfaceKeys.LEFT
                                } else {
                                    if (accY > 0) SurfaceKeys.DOWN else SurfaceKeys.UP
                                }
                                latestOnInput(surfaceId, key)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ViewportPadH, vertical = ViewportPadV)

        Box(modifier = viewportModifier) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "等待扩展输出…",
                        style = PiTheme.text.meta,
                        color = palette.dim,
                    )
                }
            } else if (rows.size > LAZY_SURFACE_THRESHOLD && !needsHorizontalScroll) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows) { rowIndex, line ->
                        SurfaceRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = measuredCols,
                            charWidthPx = charWidthPx,
                            monoStyle = monoStyle,
                            interactive = interactive,
                            onTapCell = { col, row ->
                                onMouse(surfaceId, col, row, measuredCols, rows.size.coerceAtLeast(1))
                            },
                        )
                    }
                }
            } else {
                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                val innerModifier = Modifier
                    .fillMaxWidth()
                    .then(if (needsVerticalScroll) Modifier.verticalScroll(vScroll) else Modifier)
                    .then(if (needsHorizontalScroll) Modifier.horizontalScroll(hScroll) else Modifier)
                Column(modifier = innerModifier) {
                    rows.forEachIndexed { rowIndex, line ->
                        SurfaceRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = measuredCols,
                            charWidthPx = charWidthPx,
                            monoStyle = monoStyle,
                            interactive = interactive,
                            onTapCell = { col, row ->
                                onMouse(surfaceId, col, row, measuredCols, rows.size.coerceAtLeast(1))
                            },
                        )
                    }
                }
            }
        }

        // ---- 手机端操控坞（手柄模式 / 键盘模式） ----
        if (interactive) {
            SurfaceControlDock(
                mode = controlMode,
                onKey = { onInput(surfaceId, it) },
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SurfaceRowItem(
    line: String,
    rowIndex: Int,
    totalRows: Int,
    measuredCols: Int,
    charWidthPx: Float,
    monoStyle: androidx.compose.ui.text.TextStyle,
    interactive: Boolean,
    onTapCell: (col: Int, row: Int) -> Unit,
) {
    val tapModifier = if (interactive) {
        Modifier
            .fillMaxWidth()
            .pointerInput(rowIndex, measuredCols, charWidthPx, totalRows) {
                detectTapGestures { offset ->
                    val col = (offset.x / charWidthPx).toInt().coerceIn(0, (measuredCols - 1).coerceAtLeast(0))
                    onTapCell(col, rowIndex)
                }
            }
    } else {
        Modifier.fillMaxWidth()
    }
    Box(modifier = tapModifier) {
        val spans = remember(line) { chromeSpans(line) }
        ExtensionSpans(
            spans = if (spans.all { it.text.isEmpty() }) listOf(app.pi.rpc.Ansi.Span(" ")) else spans,
            defaultColor = PiTheme.palette.text,
            style = monoStyle,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            richStyle = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceControlDock(
    mode: SurfaceControlMode,
    onKey: (String) -> Unit,
) {
    val palette = PiTheme.palette
    var text by remember { mutableStateOf("") }
    var showMoreKeys by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PiSpacing.small),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mode == SurfaceControlMode.Gamepad) {
            // ---- 双手拇指人体工学手柄：左侧真正十字方向键（长按连发 + 触觉反馈）+ 右侧动作键群 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CrossDPad(
                    onUp = { onKey(SurfaceKeys.UP) },
                    onDown = { onKey(SurfaceKeys.DOWN) },
                    onLeft = { onKey(SurfaceKeys.LEFT) },
                    onRight = { onKey(SurfaceKeys.RIGHT) },
                    onCenter = { onKey(SurfaceKeys.ENTER) },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GamepadActionButton(
                            label = "确认 ⏎",
                            primary = true,
                            modifier = Modifier.weight(1.25f),
                            onClick = { onKey(SurfaceKeys.ENTER) },
                        )
                        GamepadActionButton(
                            label = "空格",
                            repeatable = true,
                            modifier = Modifier.weight(1f),
                            onClick = { onKey(SurfaceKeys.SPACE) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GamepadActionButton(
                            label = "Esc",
                            modifier = Modifier.weight(1f),
                            onClick = { onKey(SurfaceKeys.ESC) },
                        )
                        GamepadActionButton(
                            label = "Tab",
                            modifier = Modifier.weight(1f),
                            onClick = { onKey(SurfaceKeys.TAB) },
                        )
                        GamepadActionButton(
                            label = if (showMoreKeys) "收起" else "更多",
                            active = showMoreKeys,
                            modifier = Modifier.weight(1f),
                            onClick = { showMoreKeys = !showMoreKeys },
                        )
                    }
                }
            }
        } else {
            // ---- 键盘与快捷输入模式：紧凑方向胶囊 + 确认/Esc/Tab + 文本输入框 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(PiSpacing.hairline, palette.borderMuted, RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactRepeatKey("↑", "上") { onKey(SurfaceKeys.UP) }
                    DPadDivider()
                    CompactRepeatKey("↓", "下") { onKey(SurfaceKeys.DOWN) }
                    DPadDivider()
                    CompactRepeatKey("←", "左") { onKey(SurfaceKeys.LEFT) }
                    DPadDivider()
                    CompactRepeatKey("→", "右") { onKey(SurfaceKeys.RIGHT) }
                }

                Spacer(Modifier.weight(1f))

                DockActionButton(label = "Tab", onClick = { onKey(SurfaceKeys.TAB) })
                DockActionButton(label = "Esc", onClick = { onKey(SurfaceKeys.ESC) })
                DockActionButton(label = "⏎", primary = true, onClick = { onKey(SurfaceKeys.ENTER) })
                DockActionButton(
                    label = if (showMoreKeys) "收起" else "更多",
                    active = showMoreKeys,
                    onClick = { showMoreKeys = !showMoreKeys },
                )
            }

            // 快捷输入胶囊
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(PiSpacing.hairline, palette.borderMuted, RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = PiTheme.text.mono.copy(color = palette.text),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotEmpty()) {
                                onKey(text)
                                text = ""
                            } else {
                                onKey(SurfaceKeys.ENTER)
                            }
                        },
                    ),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "输入文字发给组件…",
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                MiniInputButton(label = "⌫", enabled = true, onClick = { onKey(SurfaceKeys.BACKSPACE) })
                MiniInputButton(
                    label = "发送",
                    enabled = text.isNotEmpty(),
                    onClick = {
                        onKey(text)
                        text = ""
                    },
                )
                MiniInputButton(
                    label = "发+⏎",
                    enabled = text.isNotEmpty(),
                    primary = true,
                    onClick = {
                        val payload = text
                        text = ""
                        onKey(payload)
                        onKey(SurfaceKeys.ENTER)
                    },
                )
            }
        }

        // ---- 折叠功能键抽屉（两种模式共用） ----
        AnimatedVisibility(
            visible = showMoreKeys,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PiShapes.cardInner)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(PiSpacing.hairline, palette.borderMuted, PiShapes.cardInner)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SecondaryKeyChip("⇧Tab") { onKey(SurfaceKeys.SHIFT_TAB) }
                SecondaryKeyChip("退格 ⌫") { onKey(SurfaceKeys.BACKSPACE) }
                SecondaryKeyChip("Home") { onKey(SurfaceKeys.HOME) }
                SecondaryKeyChip("End") { onKey(SurfaceKeys.END) }
                SecondaryKeyChip("PgUp") { onKey(SurfaceKeys.PAGE_UP) }
                SecondaryKeyChip("PgDn") { onKey(SurfaceKeys.PAGE_DOWN) }
                SecondaryKeyChip("p 暂停") { onKey("p") }
                SecondaryKeyChip("r 重开") { onKey("r") }
                SecondaryKeyChip("q 退出") { onKey("q") }
                SecondaryKeyChip("y") { onKey("y") }
                SecondaryKeyChip("n") { onKey("n") }
                SecondaryKeyChip("Ctrl+C", danger = true) { onKey(SurfaceKeys.CTRL_C) }
                SecondaryKeyChip("Ctrl+D", danger = true) { onKey(SurfaceKeys.CTRL_D) }
            }
        }
    }
}

/** 真正的十字方向键（3×3 网格），支持按下立刻触发、长按连发与触觉反馈。 */
@Composable
private fun CrossDPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit,
) {
    val cellW = 46.dp
    val cellH = 38.dp
    val gap = 4.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        RepeatableKeySurface(
            glyph = "▲",
            width = cellW,
            height = cellH,
            repeatable = true,
            onTrigger = onUp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepeatableKeySurface(
                glyph = "◀",
                width = cellW,
                height = cellH,
                repeatable = true,
                onTrigger = onLeft,
            )
            RepeatableKeySurface(
                glyph = "●",
                width = cellW,
                height = cellH,
                repeatable = false,
                subtle = true,
                onTrigger = onCenter,
            )
            RepeatableKeySurface(
                glyph = "▶",
                width = cellW,
                height = cellH,
                repeatable = true,
                onTrigger = onRight,
            )
        }
        RepeatableKeySurface(
            glyph = "▼",
            width = cellW,
            height = cellH,
            repeatable = true,
            onTrigger = onDown,
        )
    }
}

@Composable
private fun RepeatableKeySurface(
    glyph: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    repeatable: Boolean,
    subtle: Boolean = false,
    onTrigger: () -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestTrigger by rememberUpdatedState(onTrigger)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        pressed -> palette.accent.copy(alpha = 0.24f)
        subtle -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (pressed) palette.accent.copy(alpha = 0.7f) else palette.borderMuted

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairline, border, shape)
            .pointerInput(repeatable) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestTrigger()
                    val repeatJob = if (repeatable) {
                        scope.launch {
                            delay(KEY_REPEAT_INITIAL_MS)
                            while (true) {
                                latestTrigger()
                                delay(KEY_REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.mono.copy(fontWeight = FontWeight.SemiBold),
            color = if (pressed) palette.accent else if (subtle) palette.muted else palette.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun GamepadActionButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    active: Boolean = false,
    repeatable: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestClick by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        pressed -> palette.accent.copy(alpha = 0.26f)
        primary -> palette.accent.copy(alpha = 0.16f)
        active -> palette.selectedBg
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = when {
        pressed || primary -> palette.accent.copy(alpha = 0.6f)
        active -> palette.borderAccent.copy(alpha = 0.6f)
        else -> palette.borderMuted
    }
    val fg = when {
        pressed || primary -> palette.accent
        else -> palette.text
    }

    Box(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairline, border, shape)
            .pointerInput(repeatable) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestClick()
                    val repeatJob = if (repeatable) {
                        scope.launch {
                            delay(KEY_REPEAT_INITIAL_MS)
                            while (true) {
                                latestClick()
                                delay(KEY_REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob?.cancel()
                        pressed = false
                    }
                }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.prose.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactRepeatKey(glyph: String, label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestClick by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 36.dp)
            .background(if (pressed) PiTheme.palette.accent.copy(alpha = 0.18f) else Color.Transparent)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestClick()
                    val repeatJob = scope.launch {
                        delay(KEY_REPEAT_INITIAL_MS)
                        while (true) {
                            latestClick()
                            delay(KEY_REPEAT_INTERVAL_MS)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
            color = if (pressed) PiTheme.palette.accent else PiTheme.palette.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun DPadDivider() {
    Box(
        modifier = Modifier
            .width(PiSpacing.hairline)
            .height(20.dp)
            .background(PiTheme.palette.borderMuted),
    )
}

@Composable
private fun DockActionButton(
    label: String,
    primary: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        primary -> palette.accent.copy(alpha = 0.16f)
        active -> palette.selectedBg
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = when {
        primary -> palette.accent.copy(alpha = 0.55f)
        active -> palette.borderAccent.copy(alpha = 0.6f)
        else -> palette.borderMuted
    }
    val fg = when {
        primary -> palette.accent
        else -> palette.text
    }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairline, border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun SecondaryKeyChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (danger) palette.toolErrorBg else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                PiSpacing.hairline,
                if (danger) palette.error.copy(alpha = 0.4f) else palette.borderMuted,
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.monoSmall,
            color = if (danger) palette.error else palette.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun MiniInputButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        !enabled -> Color.Transparent
        primary -> palette.accent.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        !enabled -> palette.dim
        primary -> palette.accent
        else -> palette.text
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = if (primary && enabled) FontWeight.SemiBold else FontWeight.Normal),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun SurfaceToolbarPill(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) palette.accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(PiSpacing.hairline, if (active) palette.accent.copy(alpha = 0.5f) else palette.borderMuted, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta,
            color = if (active) palette.accent else palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun SurfaceMetaBadge(text: String, tint: Color) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = PiTheme.text.meta,
            color = tint,
            maxLines = 1,
        )
    }
}

/** 从扩展自绘行里尽量提取首条非纯框线标题，避免顶部永远只写泛泛的「扩展界面」。 */
private fun inferSurfaceTitle(lines: List<String>): String? {
    for (raw in lines.take(4)) {
        val plain = stripAnsiControls(raw)
            .trim()
            .trim('─', '━', '═', '┌', '┐', '└', '┘', '├', '┤', '┬', '┴', '┼', '│', '┃', '║', '╭', '╮', '╰', '╯', ' ')
            .trim()
        if (plain.length in 2..36 && !plain.all { it == '─' || it == '═' || it == '-' || it == '=' }) {
            return plain
        }
    }
    return null
}

private val SurfaceSidePadding = 12.dp
private val ViewportPadH = 8.dp
private val ViewportPadV = 6.dp
private const val STANDARD_TERMINAL_COLS = 80
private const val MAX_AUTO_FIT_COLS = 120
private const val MIN_FIT_SCALE = 0.48f
private const val FULL_HEIGHT_LINE_THRESHOLD = 10
private const val LAZY_SURFACE_THRESHOLD = 48
private const val KEY_REPEAT_INITIAL_MS = 220L
private const val KEY_REPEAT_INTERVAL_MS = 65L
