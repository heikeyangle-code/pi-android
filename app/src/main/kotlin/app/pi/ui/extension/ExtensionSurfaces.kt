package app.pi.ui.extension

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.host.ExtCustom
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 扩展自绘的整块界面（`ctx.ui.custom()`）。
 *
 * 终端里它占满屏幕、读键盘；手机上的对应物是一张底部面板：
 *  - **画**：宿主把组件渲染成带 ANSI 的行（`ui.surface`），面板按终端网格原样画——等宽、不折行、横纵都能滚，
 *    列对齐就是内容。浮层（`overlay`）用内容高度，整页用全高。
 *  - **按键**：组件只认终端字节（`handleInput`）。可交互时给一条按键条（方向、回车、Esc、Tab、翻页、退格、空格）
 *    加一个文字框——文字原样送进去，每个字符就是一次按键。键值取自 [SurfaceKeys]，界面里不写转义码。
 *  - **关**：✕ / 下滑 / 返回都先发 Esc，让组件自己收尾；它不理才强关（强关会让扩展的 await 以 undefined 返回）。
 *
 * 这不是对「某个扩展」的适配：任何用 `custom()` 的扩展，菜单、表单、选择器、小游戏，走的都是这一条路。
 */
@Composable
fun ExtensionSurfaceHost(
    custom: ExtCustom?,
    onInput: (surfaceId: String, data: String) -> Unit,
    onClose: (surfaceId: String) -> Unit,
) {
    if (custom == null) return
    val id = custom.surfaceId
    PiSheet(onDismiss = { onClose(id) }, fullHeight = !custom.overlay) {
        Column(
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("扩展界面", style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.weight(1f))
                PiSettingsChip(text = "关闭", onClick = { onClose(id) })
            }
            val rows = remember(custom.lines) { panelRows(custom.lines) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SURFACE_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                rows.forEach { line -> PanelLine(line, wrap = false) }
            }
            if (custom.interactive) SurfaceKeyBar(onKey = { onInput(id, it) })
        }
    }
}

@Composable
private fun SurfaceKeyBar(onKey: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small), modifier = Modifier.padding(bottom = PiSpacing.small)) {
        KeyRow(listOf("↑" to SurfaceKeys.UP, "↓" to SurfaceKeys.DOWN, "←" to SurfaceKeys.LEFT, "→" to SurfaceKeys.RIGHT, "⏎" to SurfaceKeys.ENTER, "Esc" to SurfaceKeys.ESC), onKey)
        KeyRow(listOf("Tab" to SurfaceKeys.TAB, "⇧Tab" to SurfaceKeys.SHIFT_TAB, "⌫" to SurfaceKeys.BACKSPACE, "␣" to SurfaceKeys.SPACE, "PgUp" to SurfaceKeys.PAGE_UP, "PgDn" to SurfaceKeys.PAGE_DOWN), onKey)
        Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), verticalAlignment = Alignment.CenterVertically) {
            PiEditorField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = "输入文字（逐字作为按键发送）",
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
