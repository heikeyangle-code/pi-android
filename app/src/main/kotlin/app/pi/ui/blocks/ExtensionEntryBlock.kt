package app.pi.ui.blocks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.rpc.ExtensionEntry
import app.pi.ui.extension.LocalEntryRenderer
import app.pi.ui.extension.PanelLine
import app.pi.ui.theme.PiTheme

private const val COLLAPSED_ENTRY_LINES = 5

/**
 * 扩展自己的 `custom` 会话条目：只按扩展用 `registerEntryRenderer` 注册的渲染器画（宿主的官方
 * `CustomEntryComponent` 出行，手机把行画成原生文本）。没有渲染器的条目转录列表里根本不会放进来
 * （`PiSessionViewModel.entryRenderable`），所以这里只处理「有渲染器」的情况。
 */
@Composable
fun ExtensionEntryBlock(item: ExtensionEntry, modifier: Modifier = Modifier) {
    val renderer = LocalEntryRenderer.current
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val lines by produceState<List<String>?>(null, item.key, item.data, expanded, renderer) {
        value = renderer?.render(item, expanded) ?: value
    }
    val shown = lines ?: return
    val visible = if (expanded || shown.size <= COLLAPSED_ENTRY_LINES) shown else shown.take(COLLAPSED_ENTRY_LINES)
    BlockColumn(modifier) {
        ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }, enabled = shown.size > COLLAPSED_ENTRY_LINES) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                visible.forEach { PanelLine(it, wrap = false) }
            }
            val hidden = shown.size - visible.size
            if (hidden > 0) {
                Text(text = "… 还有 $hidden 行，点开展开", style = PiTheme.text.meta, color = PiTheme.palette.muted)
            }
        }
    }
}
