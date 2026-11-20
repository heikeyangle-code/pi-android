package app.pi.ui.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.pi.host.ExtCustom
import app.pi.host.ExtPanel
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 扩展自绘的整块界面（`ctx.ui.custom()` / `tui.showOverlay()`）。
 *
 * 终端里它占满屏幕、读键盘与鼠标；手机上的对应物是一张底部面板：
 *  - **尺寸同步**：量出面板可用宽度/高度（字符列数与行数），经 [onResize] 告诉宿主，让组件按真实手机尺寸排版。
 *  - **画**：宿主把组件渲染成带 ANSI 的行（`ui.surface`），启用完整 SGR 样式（选中行背景、反显光标、粗体、斜体、下划线、删除线、dim），
 *    等宽不折行、横纵都能滚。
 *  - **触控 + 按键**：支持点击任意行触发 [onMouse]（转为 `TuiMouseEvent` 交给 `handleMouse`）；同时提供完整移动端按键条
 *    （方向 / 回车 / Esc / Tab / ⇧Tab / 空格 / 退格 / Home / End / 翻页 / Ctrl+C / Ctrl+D）与文本输入框。
 *  - **叠栈与关闭**：[stackCount] > 1 时显示层数；✕ / 下滑 / 返回先发 Esc，不理才强关（扩展的 await 返回 undefined）。
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
    val title = if (stackCount > 1) "扩展界面（$stackCount 层）" else "扩展界面"
    PiSheet(onDismiss = { onClose(id) }, fullHeight = !custom.overlay) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
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
 * 可交互工厂 widget（`ctx.ui.setWidget(key, factory)` 且组件带 `handleInput` / `handleMouse`）的交互面板。
 * 常驻聊天页只负责紧凑预览；点「交互」展开到这里，即可完整操控组件而不退出当前对话。
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
    val title = widgetCaption(panel.key)?.let { "扩展组件 · $it" } ?: "扩展组件"
    PiSheet(onDismiss = onDismiss, fullHeight = false) {
        SurfaceSheetBody(
            surfaceId = id,
            title = title,
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
    lines: List<String>,
    interactive: Boolean,
    blocked: String?,
    onInput: (surfaceId: String, data: String) -> Unit,
    onMouse: (surfaceId: String, x: Int, y: Int, width: Int, height: Int) -> Unit,
    onResize: (columns: Int, rows: Int) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val mono = PiTheme.text.monoSmall
    val cellSize = remember(measurer, mono) { measurer.measure("0", mono).size }
    val charPx = cellSize.width.coerceAtLeast(1)
    val linePx = cellSize.height.coerceAtLeast(1)
    val maxHeightPx = with(density) { SURFACE_MAX_HEIGHT.roundToPx() }
    var measuredCols by remember { mutableIntStateOf(40) }
    var measuredRows by remember { mutableIntStateOf(24) }

    Column(
        modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.weight(1f))
            PiSettingsChip(text = "关闭", onClick = onClose)
        }
        blocked?.takeIf { it.isNotBlank() }?.let { reason ->
            PiInfoNote(reason, tone = PiTheme.palette.warning)
        }
        val rows = remember(lines) { panelRows(lines) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    val cols = columnsFor(size.width, chromePx = 0, charPx = charPx)
                    val rowCount = (maxHeightPx / linePx).coerceIn(8, 80)
                    if (cols != measuredCols || rowCount != measuredRows) {
                        measuredCols = cols
                        measuredRows = rowCount
                        onResize(cols, rowCount)
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SURFACE_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                rows.forEachIndexed { rowIndex, line ->
                    val lineModifier = if (interactive) {
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onMouse(surfaceId, 0, rowIndex, measuredCols, rows.size.coerceAtLeast(1))
                            }
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    Box(modifier = lineModifier) {
                        PanelLine(line, wrap = false)
                    }
                }
            }
        }
        if (interactive) SurfaceKeyBar(onKey = { onInput(surfaceId, it) })
    }
}

@Composable
private fun SurfaceKeyBar(onKey: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small), modifier = Modifier.padding(bottom = PiSpacing.small)) {
        KeyRow(
            listOf(
                "↑" to SurfaceKeys.UP,
                "↓" to SurfaceKeys.DOWN,
                "←" to SurfaceKeys.LEFT,
                "→" to SurfaceKeys.RIGHT,
                "⏎" to SurfaceKeys.ENTER,
                "Esc" to SurfaceKeys.ESC,
            ),
            onKey,
        )
        KeyRow(
            listOf(
                "Tab" to SurfaceKeys.TAB,
                "⇧Tab" to SurfaceKeys.SHIFT_TAB,
                "␣" to SurfaceKeys.SPACE,
                "⌫" to SurfaceKeys.BACKSPACE,
                "Home" to SurfaceKeys.HOME,
                "End" to SurfaceKeys.END,
                "PgUp" to SurfaceKeys.PAGE_UP,
                "PgDn" to SurfaceKeys.PAGE_DOWN,
                "Ctrl+C" to SurfaceKeys.CTRL_C,
                "Ctrl+D" to SurfaceKeys.CTRL_D,
            ),
            onKey,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), verticalAlignment = Alignment.CenterVertically) {
            PiEditorField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = "输入文字发送给组件（点 ⏎ 确认）",
            )
            PiSettingsChip(text = "发送", enabled = text.isNotEmpty(), onClick = {
                onKey(text)
                text = ""
            })
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, onKey: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
    ) {
        keys.forEach { (label, data) -> PiSettingsChip(text = label, onClick = { onKey(data) }) }
    }
}

private val SURFACE_MAX_HEIGHT = 420.dp
