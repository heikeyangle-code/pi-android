package app.pi.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.paui.PauiCodec
import app.pi.paui.PauiEvent
import app.pi.paui.PauiNode
import app.pi.paui.PauiTree
import app.pi.ui.settings.PiSettingsSwitch
import app.pi.ui.theme.PiTheme

/**
 * PAUI v1 的原生渲染器（实验 5：词表是否够表达一个真实页面，见 `docs/paui-design.md` §4）。
 *
 * 只认 [PauiNode] 这棵受限的树；颜色一律走 [PiTheme] 的语义令牌，扩展传不了颜色值。
 * 用户的操作以 [PauiEvent] 交给 [onEvent]，由调用方经 `/paui` 命令回传给扩展。
 * **没编译、没上机跑过，也还没接到任何页面**——这一步先把词表和渲染走通。
 *
 * `markdown` / `code` 目前按等宽/正文纯文本显示：接到 App 现成的 `PiMarkdownText` 是下一步，
 * 不在这个实验里重复造。`ansi` 只显示去掉转义后的文本（着色走 `ExtensionSpans` 是后补）。
 */
@Composable
fun PauiView(tree: PauiTree, surface: String, onEvent: (PauiEvent) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { PauiNodeView(tree.root, surface, onEvent) }
}

@Composable
private fun PauiNodeView(node: PauiNode, surface: String, onEvent: (PauiEvent) -> Unit) {
    val palette = PiTheme.palette
    fun toneColor(tone: PauiNode.Tone): Color = when (tone) {
        PauiNode.Tone.Normal -> palette.text
        PauiNode.Tone.Accent -> palette.accent
        PauiNode.Tone.Muted -> palette.muted
        PauiNode.Tone.Dim -> palette.dim
        PauiNode.Tone.Success -> palette.success
        PauiNode.Tone.Warning -> palette.warning
        PauiNode.Tone.Error -> palette.error
    }
    fun emit(node: String?, type: String, value: String? = null) {
        if (node != null) onEvent(PauiEvent(surface, node, type, value))
    }
    when (node) {
        is PauiNode.Column -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(node.gap.dp),
        ) { node.children.forEach { PauiNodeView(it, surface, onEvent) } }
        is PauiNode.Row -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(node.gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { node.children.forEach { PauiNodeView(it, surface, onEvent) } }
        is PauiNode.Box -> Box { node.children.forEach { PauiNodeView(it, surface, onEvent) } }
        is PauiNode.Spacer -> Spacer(Modifier.height(node.size.dp))
        is PauiNode.Divider -> HorizontalDivider(color = palette.border)
        is PauiNode.Text -> Text(
            node.text,
            style = if (node.mono) PiTheme.text.mono else PiTheme.text.prose,
            color = toneColor(node.tone),
            fontWeight = if (node.bold) FontWeight.SemiBold else null,
        )
        is PauiNode.Markdown -> Text(node.text, style = PiTheme.text.prose, color = palette.text)
        is PauiNode.Code -> Text(
            node.text,
            style = PiTheme.text.code,
            color = palette.mdCodeBlock,
            modifier = Modifier.fillMaxWidth().background(palette.cardBg, RoundedCornerShape(8.dp)).padding(10.dp),
        )
        is PauiNode.Ansi -> Column(Modifier.fillMaxWidth()) {
            node.lines.forEach { Text(PauiCodec.plain(it), style = PiTheme.text.mono, color = palette.text) }
        }
        is PauiNode.Button -> TextButton(onClick = { emit(node.id, "press") }, enabled = node.enabled) {
            Text(node.label, color = if (node.primary) palette.accent else palette.text)
        }
        is PauiNode.Toggle -> Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(node.label, style = PiTheme.text.prose, color = palette.text)
                node.detail?.let { Text(it, style = PiTheme.text.meta, color = palette.muted) }
            }
            PiSettingsSwitch(
                checked = node.checked,
                onCheckedChange = { emit(node.id, "toggle", it.toString()) },
                enabled = node.enabled,
            )
        }
        is PauiNode.ListNode -> Column(Modifier.fillMaxWidth()) {
            node.items.forEach { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (item.selected) palette.selectedBg else Color.Transparent)
                        .clickable { emit(node.id, "select", item.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                ) {
                    Text(item.title, style = PiTheme.text.prose, color = palette.text)
                    item.detail?.let { Text(it, style = PiTheme.text.meta, color = palette.muted) }
                }
            }
        }
        is PauiNode.Notice -> Text(
            node.text,
            style = PiTheme.text.meta,
            color = toneColor(if (node.tone == PauiNode.Tone.Normal) PauiNode.Tone.Muted else node.tone),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        is PauiNode.Badge -> Text(
            node.text,
            style = PiTheme.text.monoSmall,
            color = toneColor(node.tone),
            modifier = Modifier
                .background(palette.selectedBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        is PauiNode.Progress -> Column(Modifier.fillMaxWidth()) {
            node.label?.let { Text(it, style = PiTheme.text.meta, color = palette.muted) }
            if (node.fraction != null) {
                LinearProgressIndicator(progress = { node.fraction }, color = palette.accent, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(color = palette.accent, modifier = Modifier.fillMaxWidth())
            }
        }
        is PauiNode.Unsupported -> Text(
            "这个界面需要更新版本的 App（节点 ${node.type}）",
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
