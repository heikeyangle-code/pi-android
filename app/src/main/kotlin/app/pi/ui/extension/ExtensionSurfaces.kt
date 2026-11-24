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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtCustom
import app.pi.host.ExtOverlayOptions
import app.pi.host.ExtPanel
import app.pi.i18n.tr
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiSheet
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class SurfaceControlMode { Gamepad, Keyboard }

/** 沉降式终端画布底色：基于当前主题 bg 与 cardBg 混合，脱离默认 M3 surfaceContainer。 */
private fun surfaceWellBg(palette: app.pi.ui.theme.PiPalette): Color =
    lerp(palette.pageBg, palette.cardBg, 0.32f)

/** 控制按钮 / 键帽底色：在卡片底色上轻提 4.5% 前景对比，层次清晰而不刺眼。 */
private fun surfaceKeyBg(palette: app.pi.ui.theme.PiPalette): Color =
    lerp(palette.cardBg, palette.text, 0.048f)

/** 悬浮卡片 / 最小化收起条底色。 */
private fun surfaceFloatBg(palette: app.pi.ui.theme.PiPalette): Color =
    lerp(palette.cardBg, palette.pageBg, 0.14f)

/**
 * 终端视口嵌套滚动护栏：
 * 当视口内容支持原生垂直滚动时，吃掉到达顶/底后的余量滚动，防止在画布内滑动阅读时
 * 误把整个底部弹层拖拽关闭（顶栏把手区域仍可正常下滑最小化）。
 */
private val ViewportScrollAbsorber = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

/**
 * 扩展自绘交互表面（`ctx.ui.custom()` / `tui.showOverlay()`）——手机端全能力适配版：
 *
 *  1. **真终端 Cell Grid 渲染（`TerminalGridRow`）**：严格按等宽单元格坐标铺背景与居中绘字，
 *     棋盘/表格/框线 100% 笔直齐整，24-bit 前景/背景原样保留，触控坐标像素级精确；
 *  2. **智能视口尺寸 & 零黑洞**：不再强按 80 列缩成小块，也不再给 20 行浮层强撑 71 行黑空洞；
 *     按内容宽度与官方 `OverlayOptions` 自适应，且通过 `surfaceId` 独立同步 `ui.resize`；
 *  3. **系统输入法无缝直通**：自动识别官方 `CURSOR_MARKER`/`FAKE_CURSOR`（`hasTextCursor`）切入键盘模式、
 *     自动弹起系统输入法并遵循 `imePadding()`；支持「直通打字（含中文 IME 组合保护与退格直达）」与「整句发送」；
 *  4. **触屏鼠标 + 连续滑屏 + 自动快捷条**：支持 `handleMouse` 的组件直发 `press`/`click`/`release`/`wheel`；
 *     纯键盘组件支持连续滑屏移动多格、十字手柄与自动从底部图例提取的一键快捷芯片（如 Chess 的 F/N/G/Q）。
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
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int, type: String, wheelDelta: Int?) -> Unit = { _, _, _, _, _, _, _ -> },
    onResize: (surfaceId: String, columns: Int, rows: Int) -> Unit = { _, _, _ -> },
    onClose: (surfaceId: String) -> Unit,
) {
    if (custom == null) return
    val id = custom.surfaceId
    val title = remember(custom.lines, custom.overlay) {
        inferSurfaceTitle(custom.lines)
            ?: if (custom.overlay) tr("扩展浮层", "Extension Overlay") else tr("扩展界面", "Extension Surface")
    }
    if (minimized) {
        val palette = PiTheme.palette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = minimizedBottomPadding),
            contentAlignment = Alignment.BottomEnd,
        ) {
            val pillShape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .shadow(elevation = 3.dp, shape = pillShape, clip = false)
                    .clip(pillShape)
                    .background(surfaceKeyBg(palette))
                    .border(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.50f), pillShape)
                    .clickable(role = Role.Button, onClickLabel = tr("恢复扩展界面", "Restore extension surface"), onClick = onRestore)
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                )
                Text(
                    text = title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
                Text(
                    text = tr("继续", "Resume"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.accent,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(surfaceKeyBg(palette))
                        .clickable(role = Role.Button, onClickLabel = tr("关闭扩展面板", "Close extension panel")) { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Close"),
                        tint = palette.muted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        return
    }

    val useFullHeight = shouldUseFullHeight(
        overlay = custom.overlay,
        rowCount = panelRows(custom.lines).size,
        overlayOptions = custom.overlayOptions,
    )
    // 下滑把手 / 点遮罩 = 最小化（后台保留扩展运行状态，方便随时回聊天框和 AI 对话）；点右上角 ✕ 才真正关闭。
    PiSheet(onDismiss = onMinimize, fullHeight = useFullHeight) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = stackCount,
            lines = custom.lines,
            interactive = custom.interactive,
            supportsMouse = custom.supportsMouse,
            hasTextCursor = custom.hasTextCursor,
            overlayOptions = custom.overlayOptions,
            blocked = custom.blocked,
            blockedKind = custom.blockedKind,
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

/** 官方 `OverlayAnchor` 映射到手机屏幕安全对齐位（默认右下角）。 */
internal fun overlayAnchorAlignment(anchor: String?): Alignment = when (anchor?.trim()?.lowercase()) {
    "top-left" -> Alignment.TopStart
    "top-center" -> Alignment.TopCenter
    "top-right" -> Alignment.TopEnd
    "left-center" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "right-center" -> Alignment.CenterEnd
    "bottom-left" -> Alignment.BottomStart
    "bottom-center" -> Alignment.BottomCenter
    "bottom-right" -> Alignment.BottomEnd
    else -> Alignment.BottomEnd
}

/**
 * 非拦截型悬浮 HUD（官方 `OverlayOptions.nonCapturing = true`）：
 *  - 遵循官方 `overlayOptions.anchor` 对齐位，同时避让状态栏与软键盘（`statusBarsPadding()` + `imePadding()`）；
 *  - **绝不铺遮罩、绝不抢占聊天滚动与输入焦点**；
 *  - 支持一键收起为角落胶囊（不杀死扩展运行），点卡片或「操控」升格为全功能交互表面。
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
    val rows = remember(custom.lines) { panelRows(custom.lines) }
    val previewRows = remember(rows) { rows.take(6) }
    val title = remember(rows) { inferSurfaceTitle(rows) ?: tr("扩展悬浮层", "Extension HUD") }
    val alignment = remember(custom.overlayOptions?.anchor) {
        overlayAnchorAlignment(custom.overlayOptions?.anchor)
    }
    var collapsed by remember(id) { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp)
            .padding(top = 60.dp, bottom = bottomPadding),
        contentAlignment = alignment,
    ) {
        if (collapsed) {
            val pillShape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .shadow(elevation = 3.dp, shape = pillShape, clip = false)
                    .clip(pillShape)
                    .background(surfaceFloatBg(palette).copy(alpha = 0.97f))
                    .border(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.45f), pillShape)
                    .clickable(role = Role.Button, onClickLabel = tr("展开扩展悬浮层", "Restore floating HUD")) {
                        collapsed = false
                    }
                    .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (custom.interactive) palette.accent else palette.muted),
                )
                Text(
                    text = title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Text(
                    text = tr("展开", "Show"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.accent,
                    maxLines = 1,
                )
                val hudBtnShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(hudBtnShape)
                        .background(surfaceKeyBg(palette))
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.5f), hudBtnShape)
                        .clickable(role = Role.Button, onClickLabel = tr("关闭悬浮层", "Close overlay")) { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Close"),
                        tint = palette.muted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .widthIn(min = 196.dp, max = 312.dp)
                .shadow(elevation = 3.dp, shape = cardShape, clip = false)
                .clip(cardShape)
                .background(surfaceFloatBg(palette).copy(alpha = 0.97f))
                .border(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.40f), cardShape)
                .then(
                    if (onExpand != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = tr("展开扩展悬浮层", "Expand extension overlay"),
                            onClick = onExpand,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        .background(if (custom.interactive) palette.accent else palette.muted),
                )
                Text(
                    text = title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onExpand != null) {
                    Text(
                        text = if (custom.interactive) tr("操控", "Open") else tr("展开", "Expand"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = palette.accent,
                        maxLines = 1,
                    )
                }
                val hudBtnShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(hudBtnShape)
                        .background(surfaceKeyBg(palette))
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.5f), hudBtnShape)
                        .clickable(role = Role.Button, onClickLabel = tr("收起悬浮层", "Collapse floating HUD")) {
                            collapsed = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = tr("收起", "Collapse"),
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(hudBtnShape)
                        .background(surfaceKeyBg(palette))
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.5f), hudBtnShape)
                        .clickable(role = Role.Button, onClickLabel = tr("关闭悬浮层", "Close overlay")) { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Close"),
                        tint = palette.muted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (previewRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 148.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    previewRows.forEach { line ->
                        PanelLine(raw = line, wrap = !isPreformatted(line))
                    }
                    if (rows.size > previewRows.size) {
                        Text(
                            text = tr("…还有 ${rows.size - previewRows.size} 行", "…${rows.size - previewRows.size} more line(s)"),
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
 * 可交互工厂组件（`ctx.ui.setWidget(key, factory)`、`setHeader`、`setFooter`）的交互面板。
 */
@Composable
fun ExtensionWidgetSurfaceSheet(
    panel: ExtPanel,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int, type: String, wheelDelta: Int?) -> Unit,
    onResize: (surfaceId: String, columns: Int, rows: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val id = panel.surfaceId ?: return
    val title = when (panel.key) {
        "header" -> tr("扩展顶栏", "Extension Header")
        "footer" -> tr("扩展底栏", "Extension Footer")
        else -> widgetCaption(panel.key) ?: tr("扩展组件", "Extension Widget")
    }
    val useFullHeight = panelRows(panel.lines).size > COMPACT_OVERLAY_MAX_ROWS
    PiSheet(onDismiss = onDismiss, fullHeight = useFullHeight) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = 1,
            lines = panel.lines,
            interactive = panel.interactive,
            supportsMouse = panel.supportsMouse,
            hasTextCursor = panel.hasTextCursor,
            overlayOptions = null,
            blocked = null,
            blockedKind = null,
            useFullHeight = useFullHeight,
            soundMuted = ExtensionSound.muted,
            onToggleSound = { ExtensionSound.toggleMuted() },
            onMinimize = null,
            onInput = onInput,
            onMouse = onMouse,
            onResize = onResize,
            onClose = onDismiss,
        )
    }
}

private fun shouldUseFullHeight(
    overlay: Boolean,
    rowCount: Int,
    overlayOptions: ExtOverlayOptions?,
): Boolean {
    if (!overlay) return true
    if (rowCount > COMPACT_OVERLAY_MAX_ROWS) return true
    val maxH = overlayOptions?.maxHeightSpec?.trim()
    if (maxH != null) {
        if (maxH.endsWith("%")) {
            val pct = maxH.removeSuffix("%").toIntOrNull()
            if (pct != null && pct >= 80) return true
        } else {
            val rows = maxH.toIntOrNull()
            if (rows != null && rows > COMPACT_OVERLAY_MAX_ROWS) return true
        }
    }
    return false
}

@Composable
private fun SurfaceSheetBody(
    surfaceId: String,
    title: String,
    stackCount: Int,
    lines: List<String>,
    interactive: Boolean,
    supportsMouse: Boolean,
    hasTextCursor: Boolean,
    overlayOptions: ExtOverlayOptions?,
    blocked: String?,
    blockedKind: String?,
    useFullHeight: Boolean,
    soundMuted: Boolean,
    onToggleSound: () -> Unit,
    onMinimize: (() -> Unit)?,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int, type: String, wheelDelta: Int?) -> Unit,
    onResize: (surfaceId: String, columns: Int, rows: Int) -> Unit,
    onClose: () -> Unit,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()

    var fitWidth by remember(surfaceId) { mutableStateOf(true) }
    var userPinnedMode by remember(surfaceId) { mutableStateOf(false) }
    var controlMode by remember(surfaceId) {
        mutableStateOf(if (hasTextCursor) SurfaceControlMode.Keyboard else SurfaceControlMode.Gamepad)
    }
    // 支持鼠标触屏的组件允许折叠底部大手柄，留出最大画布视野
    var padHidden by remember(surfaceId) { mutableStateOf(false) }
    var copiedFlash by remember(surfaceId) { mutableStateOf(false) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(copiedFlash) {
        if (copiedFlash) {
            delay(1400L)
            copiedFlash = false
        }
    }

    // 组件渲染出官方 CURSOR_MARKER / FAKE_CURSOR（等待文本输入）时自动切到键盘模式并拉起系统输入法
    LaunchedEffect(surfaceId, hasTextCursor) {
        if (hasTextCursor && !userPinnedMode) {
            controlMode = SurfaceControlMode.Keyboard
            padHidden = false
        }
    }

    val rows = remember(lines) { panelRows(lines) }
    val maxContentCols = remember(rows) {
        rows.maxOfOrNull { visibleColumns(it) } ?: 0
    }
    val quickShortcuts = remember(rows, interactive) {
        if (interactive) extractSurfaceShortcuts(rows) else emptyList()
    }

    val viewportPadHPx = with(density) { (ViewportPadH * 2).roundToPx() }
    val viewportPadVPx = with(density) { (ViewportPadV * 2).roundToPx() }
    // 基准字号采用终端标准等宽字（mono）而非偏小的 monoSmall；宽组件自动按比例缩小适配，
    // 适中组件（如 Tetris/Snake/菜单）则以与原生终端一致的清晰字号铺满屏幕。
    val baseMono = PiTheme.text.mono
    val baseCharWidthPx = remember(measurer, baseMono) {
        (measurer.measure("0123456789", baseMono).size.width / 10f).coerceAtLeast(1f)
    }
    val usablePx = (viewportWidthPx - viewportPadHPx - 2).coerceAtLeast(1).toFloat()
    val naturalCols = if (viewportWidthPx > viewportPadHPx) {
        kotlin.math.floor(usablePx / baseCharWidthPx).toInt().coerceIn(MIN_COLUMNS, STANDARD_TERMINAL_COLS)
    } else {
        56
    }

    // 仅当内容确实超过手机自然列数（或官方 overlayOptions 指定了更宽列数）时才缩小字号；
    // 适中画面保持 1:1 清晰终端比例，不再硬逼 80 列缩成小块。
    val requestedOverlayCols = remember(overlayOptions) {
        val raw = overlayOptions?.widthSpec?.trim()
        val parsed = raw?.takeIf { !it.endsWith("%") }?.toIntOrNull()
        val minW = overlayOptions?.minWidth
        listOfNotNull(parsed, minW).maxOrNull()?.coerceIn(MIN_COLUMNS, MAX_AUTO_FIT_COLS)
    }
    val targetCols = when {
        rows.isEmpty() -> requestedOverlayCols ?: maxOf(naturalCols, 56)
        else -> maxOf(naturalCols, maxContentCols.coerceAtMost(MAX_AUTO_FIT_COLS))
    }
    val fitScale = if (fitWidth && viewportWidthPx > viewportPadHPx && targetCols > naturalCols) {
        (usablePx / (targetCols * baseCharWidthPx)).coerceIn(MIN_FIT_SCALE, 1f)
    } else {
        1f
    }
    val monoStyle = remember(baseMono, fitScale) {
        if (fitScale >= 0.999f) baseMono
        else baseMono.copy(fontSize = baseMono.fontSize * fitScale, lineHeight = baseMono.lineHeight * fitScale)
    }
    val fontSizePx = with(density) { monoStyle.fontSize.toPx() }
    val cellMetrics = remember(measurer, monoStyle, fontSizePx) {
        val cw = (measurer.measure("0123456789", monoStyle).size.width / 10f).coerceAtLeast(1f)
        // 真实终端单元格高宽比约为 2:1（使 `██` 双列方块恰好呈 1:1 正方形、框线紧凑无缝），
        // 避免 Compose 正文 1.5x lineHeight 把方块拉扁并在上下行间留下空隙。
        val ch = (cw * 2.0f).coerceIn(fontSizePx * 1.14f, fontSizePx * 1.30f).coerceAtLeast(1f)
        cw to ch
    }
    val charWidthPx = cellMetrics.first
    val lineHeightPx = cellMetrics.second

    var measuredCols by remember(surfaceId) { mutableIntStateOf(0) }
    var measuredRows by remember(surfaceId) { mutableIntStateOf(0) }

    LaunchedEffect(surfaceId, viewportWidthPx, viewportHeightPx, charWidthPx, lineHeightPx, useFullHeight) {
        if (viewportWidthPx <= viewportPadHPx) return@LaunchedEffect
        val cols = columnsForFloat(viewportWidthPx, chromePx = viewportPadHPx + 2, charPx = charWidthPx)
        val rowCount = if (useFullHeight && viewportHeightPx > viewportPadVPx) {
            val usableH = (viewportHeightPx - viewportPadVPx).coerceAtLeast((lineHeightPx * 10).toInt())
            (usableH / lineHeightPx).toInt().coerceIn(10, 100)
        } else {
            // 紧凑浮层：按内容或合理高度报告，不再虚报 71 行导致空出半屏
            maxOf(24, rows.size + 2).coerceAtMost(42)
        }
        if (cols != measuredCols || rowCount != measuredRows) {
            measuredCols = cols
            measuredRows = rowCount
            onResize(surfaceId, cols, rowCount)
        }
    }

    val effectiveCols = if (measuredCols > 0) measuredCols else naturalCols
    val effectiveRows = if (measuredRows > 0) measuredRows else 24
    val needsHorizontalScroll = remember(maxContentCols, effectiveCols) { maxContentCols > effectiveCols }
    val availViewportHeightPx = (viewportHeightPx - viewportPadVPx).coerceAtLeast(0)
    // 以实测像素高度为准判断溢出（软键盘弹起压缩视口高度时也能立刻启用原生滚动，不再被硬编码行数卡死）。
    val needsVerticalScroll = remember(rows.size, effectiveRows, useFullHeight, availViewportHeightPx, lineHeightPx) {
        if (availViewportHeightPx > 0) {
            rows.size * lineHeightPx > availViewportHeightPx + 2f
        } else if (useFullHeight) {
            rows.size > effectiveRows
        } else {
            rows.size > COMPACT_VIEWPORT_SCROLL_ROWS
        }
    }
    // 纯 2D 游戏手柄模式（无文本光标、非鼠标、无溢出）保留四向连续滑屏走格；
    // 其余所有交互式虚拟视口组件（如 BTW / 列表 / 日志 / 编辑器，无论是否处于键盘模式或是否声明 handleMouse）
    // 一律开启「手指自然上下滑动滚动」——先发 wheel 给组件，组件未处理时宿主自动回退 ↑/↓！
    val swipeToArrowsEnabled = interactive &&
        !supportsMouse &&
        !hasTextCursor &&
        controlMode == SurfaceControlMode.Gamepad &&
        !needsHorizontalScroll &&
        !needsVerticalScroll
    val touchScrollEnabled = interactive && !needsVerticalScroll && !swipeToArrowsEnabled

    val latestOnInput by rememberUpdatedState(onInput)
    val latestOnMouse by rememberUpdatedState(onMouse)

    val outerModifier = Modifier
        .fillMaxWidth()
        .then(if (useFullHeight) Modifier.fillMaxHeight() else Modifier)
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = SurfaceSidePadding)

    Column(
        modifier = outerModifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 顶部双行结构（手机窄屏标题绝不被挤碎）：Row 1 标题 + 最小化/关闭；Row 2 状态副标 + 横滑控制胶囊 ----
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (interactive) palette.accent else palette.dim),
                )
                Row(
                    modifier = Modifier.weight(1f),
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
                        SurfaceMetaBadge(text = tr("${stackCount}层", "${stackCount}L"), tint = palette.accent)
                    }
                }
                val iconBtnShape = RoundedCornerShape(8.dp)
                if (onMinimize != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(iconBtnShape)
                            .background(surfaceKeyBg(palette))
                            .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.42f), iconBtnShape)
                            .clickable(role = Role.Button, onClickLabel = tr("最小化去对话", "Minimize to chat"), onClick = onMinimize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = tr("最小化", "Minimize"),
                            tint = palette.muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                val closeLabel = tr("关闭", "Close")
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(iconBtnShape)
                        .background(surfaceKeyBg(palette))
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.42f), iconBtnShape)
                        .clickable(role = Role.Button, onClickLabel = closeLabel, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = closeLabel,
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = buildString {
                        append("${effectiveCols}×${rows.size.coerceAtLeast(1)}")
                        when {
                            !interactive -> append(tr(" · 只读", " · Read-only"))
                            touchScrollEnabled -> append(tr(" · 滑屏滚动", " · Touch scroll"))
                            swipeToArrowsEnabled -> append(tr(" · 滑屏操控", " · Swipe"))
                            needsVerticalScroll -> append(tr(" · 可滑动", " · Scroll"))
                            else -> append(tr(" · 交互", " · Active"))
                        }
                    },
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 2.dp),
                )
                if (interactive) {
                    SurfaceToolbarPill(
                        label = if (controlMode == SurfaceControlMode.Gamepad) tr("键盘", "Keys") else tr("手柄", "Pad"),
                        active = controlMode == SurfaceControlMode.Keyboard,
                        onClick = {
                            userPinnedMode = true
                            padHidden = false
                            controlMode = if (controlMode == SurfaceControlMode.Gamepad) {
                                SurfaceControlMode.Keyboard
                            } else {
                                SurfaceControlMode.Gamepad
                            }
                        },
                    )
                }
                if (maxContentCols > naturalCols) {
                    SurfaceToolbarPill(
                        label = if (fitWidth) tr("原字号", "1:1") else tr("适配", "Fit"),
                        active = !fitWidth,
                        onClick = { fitWidth = !fitWidth },
                    )
                }
                SurfaceToolbarPill(
                    label = if (soundMuted) tr("已静音", "Muted") else tr("提示音", "Bell"),
                    active = soundMuted,
                    leadingIcon = if (soundMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    onClick = onToggleSound,
                )
                if (rows.isNotEmpty()) {
                    SurfaceToolbarPill(
                        label = if (copiedFlash) tr("已复制", "Copied") else tr("复制文本", "Copy"),
                        active = copiedFlash,
                        leadingIcon = if (copiedFlash) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            clipboard.setText(AnnotatedString(trimEmptyEdgeLines(rows).joinToString("\n") { chromeText(it) }))
                            copiedFlash = true
                        },
                    )
                }
            }
        }

        // ---- 阻塞提示横幅（按 blockedKind 区分等待交互 vs 警告/受限） ----
        blocked?.takeIf { it.isNotBlank() }?.let { reason ->
            val isWaiting = blockedKind == "dialog" || blockedKind == "waiting" || blockedKind == "input"
            val bannerTint = if (isWaiting) palette.accent else palette.warning
            val bannerIcon = if (isWaiting) Icons.Filled.HourglassTop else Icons.Filled.Warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PiShapes.cardInner)
                    .background(palette.infoBg)
                    .border(PiSpacing.hairlineThin, bannerTint.copy(alpha = 0.42f), PiShapes.cardInner)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = bannerIcon,
                    contentDescription = null,
                    tint = bannerTint,
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

        // ---- 沉降式终端 Cell Grid 视口 ----
        val swipeStepPx = with(density) { 28.dp.toPx() }
        val wheelStepPx = with(density) { 18.dp.toPx() }
        val viewportShape = RoundedCornerShape(10.dp)
        val viewportModifier = Modifier
            .fillMaxWidth()
            .then(
                if (useFullHeight) {
                    Modifier.weight(1f, fill = true)
                } else {
                    Modifier.heightIn(min = 120.dp, max = 440.dp)
                },
            )
            .clip(viewportShape)
            .background(surfaceWellBg(palette))
            .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), viewportShape)
            .nestedScroll(ViewportScrollAbsorber)
            .onSizeChanged { size ->
                viewportWidthPx = size.width
                viewportHeightPx = size.height
            }
            .then(
                when {
                    swipeToArrowsEnabled -> Modifier.pointerInput(surfaceId, swipeStepPx) {
                        var accX = 0f
                        var accY = 0f
                        detectDragGestures(
                            onDragStart = { accX = 0f; accY = 0f },
                            onDragEnd = { accX = 0f; accY = 0f },
                            onDragCancel = { accX = 0f; accY = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            accX += dragAmount.x
                            accY += dragAmount.y
                            // 连续滑屏走格：每滑过一个步距即发出一次方向键 + 轻触觉，单手滑动即可连移多格
                            while (abs(accX) >= swipeStepPx || abs(accY) >= swipeStepPx) {
                                if (abs(accX) >= abs(accY)) {
                                    val key = if (accX > 0f) SurfaceKeys.RIGHT else SurfaceKeys.LEFT
                                    accX += if (accX > 0f) -swipeStepPx else swipeStepPx
                                    accY *= 0.35f
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnInput(surfaceId, key)
                                } else {
                                    val key = if (accY > 0f) SurfaceKeys.DOWN else SurfaceKeys.UP
                                    accY += if (accY > 0f) -swipeStepPx else swipeStepPx
                                    accX *= 0.35f
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnInput(surfaceId, key)
                                }
                            }
                        }
                    }
                    touchScrollEnabled -> Modifier.pointerInput(surfaceId, wheelStepPx, effectiveCols, rows.size) {
                        var accY = 0f
                        // 仅拦截垂直拖拽，保留水平滚动自由；同时消费事件避免 ModalBottomSheet 误触发下滑收起。
                        detectVerticalDragGestures(
                            onDragStart = { accY = 0f },
                            onDragEnd = { accY = 0f },
                            onDragCancel = { accY = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            accY += dragAmount
                            if (abs(accY) >= wheelStepPx) {
                                val steps = (abs(accY) / wheelStepPx).toInt().coerceIn(1, 4)
                                // 自然触屏方向：手指上滑 (accY < 0) 向下看内容 (+steps)；手指下滑 (accY > 0) 向上翻历史 (-steps)
                                val delta = if (accY > 0f) -steps else steps
                                accY += if (accY > 0f) -steps * wheelStepPx else steps * wheelStepPx
                                val col = (change.position.x / charWidthPx).toInt().coerceIn(0, (effectiveCols - 1).coerceAtLeast(0))
                                val row = (change.position.y / lineHeightPx).toInt().coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                                latestOnMouse(surfaceId, col, row, effectiveCols, rows.size.coerceAtLeast(1), "wheel", delta)
                            }
                        }
                    }
                    else -> Modifier
                },
            )
            .padding(horizontal = ViewportPadH, vertical = ViewportPadV)

        // 当全高视口内容明显短于可用高度且不需要滚动时，内容居中展示而非在下方留出大片空洞感
        val centerShortContent = useFullHeight && !needsVerticalScroll && rows.isNotEmpty() && rows.size < effectiveRows - 6
        Box(
            modifier = viewportModifier,
            contentAlignment = if (centerShortContent) Alignment.Center else Alignment.TopStart,
        ) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = tr("等待扩展输出…", "Waiting for extension output…"),
                        style = PiTheme.text.meta,
                        color = palette.dim,
                    )
                }
            } else if (rows.size > LAZY_SURFACE_THRESHOLD && !needsHorizontalScroll) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows) { rowIndex, line ->
                        SurfaceGridRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = if (needsHorizontalScroll) maxOf(effectiveCols, maxContentCols) else effectiveCols,
                            charWidthPx = charWidthPx,
                            lineHeightPx = lineHeightPx,
                            monoStyle = monoStyle,
                            interactive = interactive,
                            supportsMouse = supportsMouse,
                            onMouseCell = { col, row, type ->
                                latestOnMouse(surfaceId, col, row, effectiveCols, rows.size.coerceAtLeast(1), type, null)
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
                        SurfaceGridRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = if (needsHorizontalScroll) maxOf(effectiveCols, maxContentCols) else effectiveCols,
                            charWidthPx = charWidthPx,
                            lineHeightPx = lineHeightPx,
                            monoStyle = monoStyle,
                            interactive = interactive,
                            supportsMouse = supportsMouse,
                            onMouseCell = { col, row, type ->
                                latestOnMouse(surfaceId, col, row, effectiveCols, rows.size.coerceAtLeast(1), type, null)
                            },
                        )
                    }
                }
            }
        }

        // ---- 自动提取的组件快捷单键条（如 Chess 的 F flip / N new / G games / Q quit） ----
        if (quickShortcuts.isNotEmpty() && !padHidden) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickShortcuts.forEach { sc ->
                    QuickLegendChip(
                        shortcut = sc,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onInput(surfaceId, sc.sendData)
                        },
                    )
                }
            }
        }

        // ---- 手机端操控坞（手柄模式 / 直通键盘模式） ----
        if (interactive) {
            if (supportsMouse && padHidden) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = PiSpacing.small),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SurfaceToolbarPill(
                        label = tr("展开操控区", "Show controls"),
                        active = false,
                        onClick = { padHidden = false },
                    )
                }
            } else {
                SurfaceControlDock(
                    surfaceId = surfaceId,
                    mode = controlMode,
                    canHidePad = supportsMouse,
                    onHidePad = { padHidden = true },
                    onKey = { onInput(surfaceId, it) },
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SurfaceGridRowItem(
    line: String,
    rowIndex: Int,
    totalRows: Int,
    measuredCols: Int,
    charWidthPx: Float,
    lineHeightPx: Float,
    monoStyle: androidx.compose.ui.text.TextStyle,
    interactive: Boolean,
    supportsMouse: Boolean,
    onMouseCell: (col: Int, row: Int, type: String) -> Unit,
) {
    val tapModifier = if (interactive && supportsMouse) {
        Modifier
            .fillMaxWidth()
            .pointerInput(rowIndex, measuredCols, charWidthPx, totalRows) {
                detectTapGestures(
                    onPress = { offset ->
                        val col = (offset.x / charWidthPx).toInt().coerceIn(0, (measuredCols - 1).coerceAtLeast(0))
                        onMouseCell(col, rowIndex, "press")
                        val released = tryAwaitRelease()
                        if (released) {
                            onMouseCell(col, rowIndex, "click")
                        }
                        onMouseCell(col, rowIndex, "release")
                    },
                )
            }
    } else if (interactive) {
        Modifier
            .fillMaxWidth()
            .pointerInput(rowIndex, measuredCols, charWidthPx, totalRows) {
                detectTapGestures { offset ->
                    val col = (offset.x / charWidthPx).toInt().coerceIn(0, (measuredCols - 1).coerceAtLeast(0))
                    onMouseCell(col, rowIndex, "click")
                }
            }
    } else {
        Modifier.fillMaxWidth()
    }
    Box(modifier = tapModifier) {
        TerminalGridRow(
            line = line,
            cellWidthPx = charWidthPx,
            cellHeightPx = lineHeightPx,
            monoStyle = monoStyle,
            minCols = measuredCols,
        )
    }
}

@Composable
private fun QuickLegendChip(
    shortcut: SurfaceQuickShortcut,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(surfaceKeyBg(palette))
            .border(PiSpacing.hairline, palette.borderMuted, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(palette.accent.copy(alpha = 0.16f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = shortcut.keyLabel,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                color = palette.accent,
                maxLines = 1,
            )
        }
        Text(
            text = shortcut.description,
            style = PiTheme.text.meta,
            color = palette.text,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceControlDock(
    surfaceId: String,
    mode: SurfaceControlMode,
    canHidePad: Boolean,
    onHidePad: () -> Unit,
    onKey: (String) -> Unit,
) {
    val palette = PiTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    var liveTyping by remember(surfaceId) { mutableStateOf(true) }
    var batchText by remember(surfaceId) { mutableStateOf("") }
    // 直通模式使用零宽哨兵，使系统软键盘在空框按退格也能立刻向组件发送 BACKSPACE（\u007f）
    var liveField by remember(surfaceId) {
        mutableStateOf(TextFieldValue(text = LIVE_SENTINEL, selection = TextRange(1)))
    }
    var showMoreKeys by remember(surfaceId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PiSpacing.small),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mode == SurfaceControlMode.Gamepad) {
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
                            label = tr("确认 ⏎", "Enter ⏎"),
                            primary = true,
                            modifier = Modifier.weight(1.25f),
                            onClick = { onKey(SurfaceKeys.ENTER) },
                        )
                        GamepadActionButton(
                            label = tr("空格", "Space"),
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
                            label = if (showMoreKeys) tr("收起", "Less") else tr("更多", "More"),
                            active = showMoreKeys,
                            modifier = Modifier.weight(1f),
                            onClick = { showMoreKeys = !showMoreKeys },
                        )
                    }
                }
            }
        } else {
            // ---- 键盘与系统 IME 直通模式：切换即自动拉起系统输入法 ----
            val focusRequester = remember(surfaceId) { FocusRequester() }
            PiAutoFocus(focusRequester)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(surfaceKeyBg(palette))
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactRepeatKey(Icons.Filled.KeyboardArrowUp, tr("上", "Up")) { onKey(SurfaceKeys.UP) }
                    DPadDivider()
                    CompactRepeatKey(Icons.Filled.KeyboardArrowDown, tr("下", "Down")) { onKey(SurfaceKeys.DOWN) }
                    DPadDivider()
                    CompactRepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("左", "Left")) { onKey(SurfaceKeys.LEFT) }
                    DPadDivider()
                    CompactRepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("右", "Right")) { onKey(SurfaceKeys.RIGHT) }
                }

                Spacer(Modifier.weight(1f))

                DockActionButton(label = "Tab", onClick = { onKey(SurfaceKeys.TAB) })
                DockActionButton(label = "Esc", onClick = { onKey(SurfaceKeys.ESC) })
                DockActionButton(label = "⏎", primary = true, icon = Icons.AutoMirrored.Filled.KeyboardReturn, onClick = { onKey(SurfaceKeys.ENTER) })
                DockActionButton(
                    label = if (showMoreKeys) tr("收起", "Less") else tr("更多", "More"),
                    active = showMoreKeys,
                    onClick = { showMoreKeys = !showMoreKeys },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(surfaceWellBg(palette))
                    .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 直通 / 整句 切换胶囊
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (liveTyping) palette.accent.copy(alpha = 0.16f) else surfaceKeyBg(palette))
                        .clickable(role = Role.Button) {
                            liveTyping = !liveTyping
                            liveField = TextFieldValue(LIVE_SENTINEL, TextRange(1))
                            keyboard?.show()
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (liveTyping) tr("直通", "Live") else tr("整句", "Line"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = if (liveTyping) palette.accent else palette.muted,
                        maxLines = 1,
                    )
                }

                if (liveTyping) {
                    BasicTextField(
                        value = liveField,
                        onValueChange = { updated ->
                            // 中文 / 日文 IME 组合输入期间暂存，不把未确认拼音发给终端组件
                            if (updated.composition != null) {
                                liveField = updated
                                return@BasicTextField
                            }
                            val raw = updated.text
                            val cleaned = raw.replace(LIVE_SENTINEL, "")
                            when {
                                raw.isEmpty() -> {
                                    // 用户按了系统键盘退格键
                                    onKey(SurfaceKeys.BACKSPACE)
                                    liveField = TextFieldValue(LIVE_SENTINEL, TextRange(1))
                                }
                                cleaned.isNotEmpty() -> {
                                    // 换行转为终端回车 \r，其余文字立刻直通发给组件
                                    val normalized = cleaned.replace("\r\n", "\r").replace("\n", "\r")
                                    onKey(normalized)
                                    liveField = TextFieldValue(LIVE_SENTINEL, TextRange(1))
                                }
                                else -> {
                                    liveField = TextFieldValue(LIVE_SENTINEL, TextRange(1))
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = PiTheme.text.mono.copy(color = palette.text),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { onKey(SurfaceKeys.ENTER) },
                        ),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (liveField.text == LIVE_SENTINEL) {
                                    Text(
                                        text = tr("实时键入直达组件（退格/回车即发）…", "Type live to component (Bksp/Enter instant)…"),
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
                    MiniInputButton(label = tr("收键盘", "Hide"), enabled = true, onClick = { keyboard?.hide() })
                } else {
                    BasicTextField(
                        value = batchText,
                        onValueChange = { batchText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = PiTheme.text.mono.copy(color = palette.text),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (batchText.isNotEmpty()) {
                                    onKey(batchText)
                                    batchText = ""
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
                                if (batchText.isEmpty()) {
                                    Text(
                                        text = tr("输入整句后发送给组件…", "Compose text and send…"),
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
                        label = tr("发送", "Send"),
                        enabled = batchText.isNotEmpty(),
                        onClick = {
                            onKey(batchText)
                            batchText = ""
                        },
                    )
                    MiniInputButton(
                        label = tr("发+⏎", "Send+⏎"),
                        enabled = batchText.isNotEmpty(),
                        primary = true,
                        onClick = {
                            val payload = batchText
                            batchText = ""
                            onKey(payload)
                            onKey(SurfaceKeys.ENTER)
                        },
                    )
                }
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
                    .background(surfaceWellBg(palette))
                    .border(PiSpacing.hairline, palette.borderMuted, PiShapes.cardInner)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SecondaryKeyChip("⇧Tab") { onKey(SurfaceKeys.SHIFT_TAB) }
                SecondaryKeyChip(tr("退格 ⌫", "Bksp ⌫")) { onKey(SurfaceKeys.BACKSPACE) }
                SecondaryKeyChip("Home") { onKey(SurfaceKeys.HOME) }
                SecondaryKeyChip("End") { onKey(SurfaceKeys.END) }
                SecondaryKeyChip("PgUp") { onKey(SurfaceKeys.PAGE_UP) }
                SecondaryKeyChip("PgDn") { onKey(SurfaceKeys.PAGE_DOWN) }
                SecondaryKeyChip(tr("p 暂停", "p Pause")) { onKey("p") }
                SecondaryKeyChip(tr("r 重开", "r Restart")) { onKey("r") }
                SecondaryKeyChip(tr("q 退出", "q Quit")) { onKey("q") }
                SecondaryKeyChip("y") { onKey("y") }
                SecondaryKeyChip("n") { onKey("n") }
                SecondaryKeyChip("Ctrl+C", danger = true) { onKey(SurfaceKeys.CTRL_C) }
                SecondaryKeyChip("Ctrl+D", danger = true) { onKey(SurfaceKeys.CTRL_D) }
                if (canHidePad) {
                    SecondaryKeyChip(tr("隐藏手柄", "Hide pad")) { onHidePad() }
                }
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
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = tr("上", "Up"),
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
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = tr("左", "Left"),
                width = cellW,
                height = cellH,
                repeatable = true,
                onTrigger = onLeft,
            )
            RepeatableKeySurface(
                icon = Icons.Filled.Check,
                contentDescription = tr("确认", "Confirm"),
                width = cellW,
                height = cellH,
                repeatable = false,
                subtle = true,
                onTrigger = onCenter,
            )
            RepeatableKeySurface(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = tr("右", "Right"),
                width = cellW,
                height = cellH,
                repeatable = true,
                onTrigger = onRight,
            )
        }
        RepeatableKeySurface(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = tr("下", "Down"),
            width = cellW,
            height = cellH,
            repeatable = true,
            onTrigger = onDown,
        )
    }
}

@Composable
private fun RepeatableKeySurface(
    icon: ImageVector,
    contentDescription: String,
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
        subtle -> surfaceWellBg(palette)
        else -> surfaceKeyBg(palette)
    }
    val border = if (pressed) palette.accent.copy(alpha = 0.7f) else palette.borderMuted.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairlineThin, border, shape)
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
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (pressed) palette.accent else if (subtle) palette.muted else palette.text,
            modifier = Modifier.size(if (subtle) 17.dp else 20.dp),
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
        else -> surfaceKeyBg(palette)
    }
    val border = when {
        pressed || primary -> palette.accent.copy(alpha = 0.55f)
        active -> palette.borderAccent.copy(alpha = 0.55f)
        else -> palette.borderMuted.copy(alpha = 0.55f)
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
            .border(PiSpacing.hairlineThin, border, shape)
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
private fun CompactRepeatKey(icon: ImageVector, label: String, onClick: () -> Unit) {
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
                        repeatJob?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (pressed) PiTheme.palette.accent else PiTheme.palette.text,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DPadDivider() {
    Box(
        modifier = Modifier
            .width(PiSpacing.hairlineThin)
            .height(20.dp)
            .background(PiTheme.palette.borderMuted.copy(alpha = 0.5f)),
    )
}

@Composable
private fun DockActionButton(
    label: String,
    primary: Boolean = false,
    active: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        primary -> palette.accent.copy(alpha = 0.16f)
        active -> palette.selectedBg
        else -> surfaceKeyBg(palette)
    }
    val border = when {
        primary -> palette.accent.copy(alpha = 0.50f)
        active -> palette.borderAccent.copy(alpha = 0.55f)
        else -> palette.borderMuted.copy(alpha = 0.55f)
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
            .border(PiSpacing.hairlineThin, border, shape)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = label,
                style = PiTheme.text.meta.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SecondaryKeyChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (danger) palette.toolErrorBg else surfaceKeyBg(palette))
            .border(
                PiSpacing.hairlineThin,
                if (danger) palette.error.copy(alpha = 0.4f) else palette.borderMuted.copy(alpha = 0.55f),
                shape,
            )
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
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
        else -> surfaceKeyBg(palette)
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (label == "⌫") {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = tr("退格", "Backspace"),
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
        } else {
            Text(
                text = label,
                style = PiTheme.text.meta.copy(fontWeight = if (primary && enabled) FontWeight.SemiBold else FontWeight.Normal),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SurfaceToolbarPill(
    label: String,
    active: Boolean,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(if (active) palette.accent.copy(alpha = 0.13f) else surfaceKeyBg(palette))
            .border(
                PiSpacing.hairlineThin,
                if (active) palette.accent.copy(alpha = 0.42f) else palette.borderMuted.copy(alpha = 0.40f),
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (active) palette.accent else palette.muted,
                modifier = Modifier.size(13.dp),
            )
        }
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
    val shape = RoundedCornerShape(6.dp)
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


private const val LIVE_SENTINEL = "\u200B"
private val SurfaceSidePadding = 12.dp
private val ViewportPadH = 8.dp
private val ViewportPadV = 6.dp
private const val STANDARD_TERMINAL_COLS = 80
private const val MAX_AUTO_FIT_COLS = 120
private const val MIN_FIT_SCALE = 0.48f
private const val COMPACT_OVERLAY_MAX_ROWS = 28
private const val COMPACT_VIEWPORT_SCROLL_ROWS = 24
private const val LAZY_SURFACE_THRESHOLD = 48
private const val KEY_REPEAT_INITIAL_MS = 220L
private const val KEY_REPEAT_INTERVAL_MS = 65L
