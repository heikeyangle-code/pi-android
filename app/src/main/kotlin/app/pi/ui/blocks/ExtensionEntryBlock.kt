package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.i18n.tr
import app.pi.protocol.ExtensionEntry
import app.pi.ui.extension.LocalEntryRenderer
import app.pi.ui.extension.RenderedAnsiBlock
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.panelRows
import app.pi.ui.extension.trimEmptyEdgeLines
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 扩展自己的 `custom` 会话条目：按扩展通过 `registerEntryRenderer` 注册的渲染器呈现，
 * 带类型标签、清晰展开/收起指示与长按复制能力。
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
    val cleaned = remember(shown) { trimEmptyEdgeLines(panelRows(shown)) }
    if (cleaned.isEmpty()) return
    val plainCopy = remember(cleaned) { cleaned.joinToString("\n") { chromeText(it) } }
    val collapsible = cleaned.size > COLLAPSED_ENTRY_LINES
    val palette = PiTheme.palette

    ToolActionMenu(command = null, output = plainCopy, fullOutputPath = null) {
        BlockColumn(modifier) {
            BlockCard(
                color = palette.cardBg,
                borderColor = palette.borderMuted.copy(alpha = 0.45f),
            ) {
                ToggleContent(
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    enabled = collapsible,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CardChip(
                                text = item.customType.ifEmpty { "extension" },
                                color = palette.customMessageLabel,
                            )
                        }
                        if (collapsible) ExpandLabel(expanded)
                    }
                    RenderedAnsiBlock(
                        lines = shown,
                        expanded = expanded,
                        collapsedLimit = COLLAPSED_ENTRY_LINES,
                        modifier = Modifier.padding(top = PiSpacing.tiny),
                        expandHint = { hidden -> tr("还有 $hidden 行，点击展开", "$hidden more lines, tap to expand") },
                    )
                }
            }
        }
    }
}

private const val COLLAPSED_ENTRY_LINES = 4
