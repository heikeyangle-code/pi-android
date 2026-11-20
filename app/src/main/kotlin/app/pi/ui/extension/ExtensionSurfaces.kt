package app.pi.ui.extension

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

/**
 * 扩展自绘的整块界面（`ctx.ui.custom()` / `tui.showOverlay()`）——手机原生触屏适配版。
 *
 * 相比旧版「纯终端输出 + 两排 16 枚平铺按钮」：
 *  - **顶部信息栏**：状态圆点、智能提取标题、多层计数徽标、**适配宽度 / 标准字号切换**（默认把 80 列缩进视口，动态重算列数并回传 `ui.resize`，
 *    让 60–80 列的宽 TUI 界面也能一屏放下，不再左右乱滚）、圆形关闭钮。
 *  - **沉降式视口容器**：圆角描边卡片承载扩展渲染行，完整 SGR 样式；按实际触控点像素坐标精确换算 `(col, row)`
 *    触发 `ui.mouse`；仅在存在超宽行时开启横向滚动，普通宽度界面纵向顺滑不晃动；超长表面走 `LazyColumn`。
 *  - **手机人体工学操控区**：
 *    1) 主控一行：左侧分组方向键胶囊（`↑ ↓ ← →`）+ 右侧高亮主操作「确认 ⏎」+「返回」+「Tab」+「更多」抽屉开关；
 *    2) 快捷输入胶囊：单行输入，软键盘确认即发，旁置一键「发送并⏎」与「⌫」；
 *    3) 折叠功能键抽屉：默认收起保持界面清爽，点「更多」展开 `⇧Tab / 空格 / Home / End / PgUp / PgDn / Ctrl+C / Ctrl+D / y / n`。
 */
@Composable
fun ExtensionSurfaceHost(
    custom: ExtCustom?,
    stackCount: Int = if (custom != null) 1 else 0,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit = { _, _, _, _, _ -> },
    onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onClose: (surfaceId: String) -> Unit,
) {
    if (custom == null) return
    val id = custom.surfaceId
    val inferred = remember(custom.lines) { inferSurfaceTitle(custom.lines) }
    val title = inferred ?: "扩展交互面板"
    PiSheet(onDismiss = { onClose(id) }, fullHeight = !custom.overlay) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = stackCount,
            lines = custom.lines,
            interactive = custom.interactive,
            blocked = custom.blocked,
            onInput = onInput,
            onMouse = onMouse,
            onResize = onResize,
            onClose = { onClose(id) },
        )
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
    PiSheet(onDismiss = onDismiss, fullHeight = false) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
            stackCount = 1,
            lines = panel.lines,
            interactive = panel.interactive,
            blocked = null,
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
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit,
    onResize: (columns: Int, rows: Int) -> Unit,
    onClose: () -> Unit,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 默认「适配宽度」：把官方终端的标准宽度（80 列）塞进手机视口，字号按需缩小到下限；
    // 再窄就让扩展按实际能放下的列数自己排版（宿主回传 `ui.resize`）。扩展拿到多少列就按多少列画，
    // 列数不够它会自己截断成 "..."（pi-tui 的 truncateToWidth），所以「少报列数」才是内容被切的根因。
    var fitWidth by remember(surfaceId) { mutableStateOf(true) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val viewportPadHPx = with(density) { (ViewportPadH * 2).roundToPx() }
    val baseMono = PiTheme.text.monoSmall
    val baseCharPx = remember(measurer, baseMono) { measurer.measure("0", baseMono).size.width.coerceAtLeast(1) }
    val fitScale = if (fitWidth && viewportWidthPx > 0) {
        ((viewportWidthPx - viewportPadHPx).toFloat() / (STANDARD_TERMINAL_COLS * baseCharPx)).coerceIn(MIN_FIT_SCALE, 1f)
    } else {
        1f
    }
    val monoStyle = remember(baseMono, fitScale) {
        if (fitScale >= 0.999f) baseMono
        else baseMono.copy(fontSize = baseMono.fontSize * fitScale, lineHeight = baseMono.lineHeight * fitScale)
    }
    val cellSize = remember(measurer, monoStyle) { measurer.measure("0", monoStyle).size }
    val charPx = cellSize.width.coerceAtLeast(1)
    val linePx = cellSize.height.coerceAtLeast(1)
    // 视口高度 = 屏幕高度扣掉标题栏 / 操控坞 / 把手等固定部分，而不是写死的一个数：
    // 官方终端里组件的高度不受内部滚动限制（overlay 的 maxHeight 默认是终端行数），
    // 写死 430dp 会让一块本来放得下的棋盘（≈30 行）在屏幕下半截空着的情况下仍要上下滚。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxViewport = (screenHeight - (if (interactive) SURFACE_CHROME_INTERACTIVE else SURFACE_CHROME_READONLY))
        .coerceAtLeast(SURFACE_MIN_HEIGHT)
    val maxHeightPx = with(density) { maxViewport.roundToPx() }

    var measuredCols by remember { mutableIntStateOf(40) }
    var measuredRows by remember { mutableIntStateOf(24) }

    // 切换标准/紧凑字号时立即按新字符宽度重算列数并通知宿主重排。
    LaunchedEffect(viewportWidthPx, charPx, linePx) {
        if (viewportWidthPx <= 0) return@LaunchedEffect
        val cols = columnsFor(viewportWidthPx, chromePx = viewportPadHPx, charPx = charPx)
        val rowCount = (maxHeightPx / linePx).coerceIn(8, 80)
        if (cols != measuredCols || rowCount != measuredRows) {
            measuredCols = cols
            measuredRows = rowCount
            onResize(cols, rowCount)
        }
    }

    val rows = remember(lines) { panelRows(lines) }
    val needsHorizontalScroll = remember(rows, measuredCols) {
        rows.any { visibleColumns(it) > measuredCols }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 顶部标题栏 ----
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
                    text = if (interactive) {
                        "${measuredCols} 列 · 点击内容或用下方控制区操作"
                    } else {
                        "${measuredCols} 列 · 只读视图"
                    },
                    style = PiTheme.text.meta,
                    color = palette.dim,
                    maxLines = 1,
                )
            }
            // 适配宽度（默认）/ 标准字号 切换
            SurfaceToolbarPill(
                label = if (fitWidth) "标准字号" else "适配宽度",
                active = !fitWidth,
                onClick = { fitWidth = !fitWidth },
            )
            // 圆形关闭按钮
            Box(
                modifier = Modifier
                    .size(32.dp)
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
                    modifier = Modifier.size(16.dp),
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
                    .padding(horizontal = 10.dp, vertical = 7.dp),
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

        // ---- 沉降式终端内容视口 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PiShapes.card)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(PiSpacing.hairline, palette.borderMuted, PiShapes.card)
                .onSizeChanged { size -> viewportWidthPx = size.width }
                .padding(horizontal = ViewportPadH, vertical = ViewportPadV),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = "等待扩展输出…",
                    style = PiTheme.text.meta,
                    color = palette.dim,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else if (rows.size > LAZY_SURFACE_THRESHOLD && !needsHorizontalScroll) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxViewport),
                ) {
                    itemsIndexed(rows) { rowIndex, line ->
                        SurfaceRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = measuredCols,
                            charPx = charPx,
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
                    .heightIn(max = maxViewport)
                    .verticalScroll(vScroll)
                    .then(if (needsHorizontalScroll) Modifier.horizontalScroll(hScroll) else Modifier)
                Column(modifier = innerModifier) {
                    rows.forEachIndexed { rowIndex, line ->
                        SurfaceRowItem(
                            line = line,
                            rowIndex = rowIndex,
                            totalRows = rows.size,
                            measuredCols = measuredCols,
                            charPx = charPx,
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

        // ---- 手机端操控坞 ----
        if (interactive) {
            SurfaceControlDock(onKey = { onInput(surfaceId, it) })
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SurfaceRowItem(
    line: String,
    rowIndex: Int,
    totalRows: Int,
    measuredCols: Int,
    charPx: Int,
    monoStyle: androidx.compose.ui.text.TextStyle,
    interactive: Boolean,
    onTapCell: (col: Int, row: Int) -> Unit,
) {
    val tapModifier = if (interactive) {
        Modifier
            .fillMaxWidth()
            .pointerInput(rowIndex, measuredCols, charPx, totalRows) {
                detectTapGestures { offset ->
                    val col = (offset.x / charPx.toFloat()).toInt().coerceIn(0, (measuredCols - 1).coerceAtLeast(0))
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
private fun SurfaceControlDock(onKey: (String) -> Unit) {
    val palette = PiTheme.palette
    var text by remember { mutableStateOf("") }
    var showMoreKeys by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PiSpacing.small),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 主控行：分组方向胶囊 + 确认 / 返回 / Tab / 更多 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 紧凑分组方向键胶囊
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(PiSpacing.hairline, palette.borderMuted, RoundedCornerShape(10.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DPadCell("↑", "上") { onKey(SurfaceKeys.UP) }
                DPadDivider()
                DPadCell("↓", "下") { onKey(SurfaceKeys.DOWN) }
                DPadDivider()
                DPadCell("←", "左") { onKey(SurfaceKeys.LEFT) }
                DPadDivider()
                DPadCell("→", "右") { onKey(SurfaceKeys.RIGHT) }
            }

            Spacer(Modifier.weight(1f))

            DockActionButton(
                label = "Tab",
                onClick = { onKey(SurfaceKeys.TAB) },
            )
            DockActionButton(
                label = "Esc",
                onClick = { onKey(SurfaceKeys.ESC) },
            )
            DockActionButton(
                label = "确认 ⏎",
                primary = true,
                onClick = { onKey(SurfaceKeys.ENTER) },
            )
            DockActionButton(
                label = if (showMoreKeys) "收起" else "更多",
                active = showMoreKeys,
                onClick = { showMoreKeys = !showMoreKeys },
            )
        }

        // ---- 折叠功能键抽屉 ----
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
                SecondaryKeyChip("空格") { onKey(SurfaceKeys.SPACE) }
                SecondaryKeyChip("退格 ⌫") { onKey(SurfaceKeys.BACKSPACE) }
                SecondaryKeyChip("Home") { onKey(SurfaceKeys.HOME) }
                SecondaryKeyChip("End") { onKey(SurfaceKeys.END) }
                SecondaryKeyChip("PgUp") { onKey(SurfaceKeys.PAGE_UP) }
                SecondaryKeyChip("PgDn") { onKey(SurfaceKeys.PAGE_DOWN) }
                SecondaryKeyChip("y") { onKey("y") }
                SecondaryKeyChip("n") { onKey("n") }
                SecondaryKeyChip("Ctrl+C", danger = true) { onKey(SurfaceKeys.CTRL_C) }
                SecondaryKeyChip("Ctrl+D", danger = true) { onKey(SurfaceKeys.CTRL_D) }
            }
        }

        // ---- 快捷输入胶囊 ----
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
            MiniInputButton(
                label = "⌫",
                enabled = true,
                onClick = { onKey(SurfaceKeys.BACKSPACE) },
            )
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
}

@Composable
private fun DPadCell(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 36.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.text,
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
        active -> palette.text
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
            .padding(horizontal = 10.dp, vertical = 5.dp),
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

private val ViewportPadH = 10.dp
private val ViewportPadV = 8.dp
/** 视口之外的固定部分（把手 + 标题栏 + 间距；可交互的再加操控坞两行）。 */
private val SURFACE_CHROME_INTERACTIVE = 300.dp
private val SURFACE_CHROME_READONLY = 190.dp
private val SURFACE_MIN_HEIGHT = 320.dp

/** 官方终端的标准宽度；适配宽度时以它为目标列数。 */
private const val STANDARD_TERMINAL_COLS = 80

/** 适配宽度时字号缩放的下限，再小就读不清了（此时列数不足 80，由扩展按实际列数重排）。 */
private const val MIN_FIT_SCALE = 0.62f
private const val LAZY_SURFACE_THRESHOLD = 48
