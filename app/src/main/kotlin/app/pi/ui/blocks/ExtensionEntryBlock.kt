package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.ExtensionEntry
import app.pi.ui.extension.LocalEntryRenderer
import app.pi.ui.extension.RenderedAnsiBlock
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.panelRows
import app.pi.ui.extension.trimEmptyEdgeLines
import app.pi.ui.theme.PiTheme

/**
 * 扩展自己的 `custom` 会话条目：按扩展通过 `registerEntryRenderer` 注册的渲染器呈现，
 * 与 `HookMessageBlock` 共用精简顶栏 + 细竖色标 + 清晰正文区的统一视觉语言。
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
    val cardShape = RoundedCornerShape(10.dp)
    val headerBg = remember(palette) { lerp(palette.cardBg, palette.text, 0.03f) }

    ToolActionMenu(command = null, output = plainCopy, fullOutputPath = null) {
        BlockColumn(modifier) {
            SelectableContent {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(palette.cardBg)
                        .border(0.5.dp, palette.borderMuted.copy(alpha = 0.50f), cardShape),
                ) {
                    ToggleContent(
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        enabled = collapsible,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(headerBg)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(palette.customMessageLabel.copy(alpha = 0.78f)),
                            )
                            Text(
                                text = item.customType.ifEmpty { "extension" },
                                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                                color = palette.customMessageLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (collapsible) ExpandLabel(expanded)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(palette.borderMuted.copy(alpha = 0.34f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            RenderedAnsiBlock(
                                lines = shown,
                                expanded = expanded,
                                collapsedLimit = COLLAPSED_ENTRY_LINES,
                                expandHint = { hidden -> tr("还有 $hidden 行，点击展开", "$hidden more lines, tap to expand") },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val COLLAPSED_ENTRY_LINES = 4
