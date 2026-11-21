package app.pi.ui.blocks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.protocol.ExtensionEntry
import app.pi.ui.extension.LocalEntryRenderer
import app.pi.ui.extension.RenderedAnsiBlock
import app.pi.ui.theme.PiTheme

/**
 * 扩展自己的 `custom` 会话条目：只按扩展用 `registerEntryRenderer` 注册的渲染器画（宿主的官方
 * `CustomEntryComponent` 出行，手机把行画成原生文本）。没有渲染器的条目转录列表里根本不会放进来
 * （`PiSessionViewModel.entryRenderable`），所以这里只处理「有渲染器」的情况。
 */
@Composable
fun ExtensionEntryBlock(
    item: ExtensionEntry,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val renderer = LocalEntryRenderer.current
    var expanded by rememberSaveable(item.key, defaultExpanded) { mutableStateOf(defaultExpanded) }
    val lines by produceState<List<String>?>(null, item.key, item.data, expanded, renderer) {
        value = renderer?.render(item, expanded) ?: value
    }
    val shown = lines ?: return
    val palette = PiTheme.palette
    BlockColumn(modifier) {
        BlockCard(
            color = palette.cardBg,
            borderColor = null,
        ) {
            ToggleContent(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                enabled = true,
            ) {
                RenderedAnsiBlock(
                    lines = shown,
                    expanded = expanded,
                    collapsedLimit = Int.MAX_VALUE,
                )
            }
        }
    }
}
