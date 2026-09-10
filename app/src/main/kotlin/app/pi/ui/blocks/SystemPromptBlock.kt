package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.SystemPrompt
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `system-prompt` (docs/pi-android-ui-spec.md §7.4): collapsed to a single
 * 系统提示 · 3.2k 字符 line, expanding to the full text. The spec allows either
 * rendering, so both are offered — mono for reading it as the machine sees it,
 * prose for reading it as a document.
 */
@Composable
fun SystemPromptBlock(
    item: SystemPrompt,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var mono by remember { mutableStateOf(true) }
    val body = item.fullText.ifEmpty { "（空系统提示）" }

    BlockColumn(modifier) {
        BlockCard(
            color = palette.cardBg,
            borderColor = palette.borderMuted.copy(alpha = 0.4f),
        ) {
            ToggleRow(expanded = expanded, onToggle = { expanded = !expanded }) {
                AccentStripe(palette.borderAccent, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "系统提示 · ${formatChars(item.fullText.length)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.text,
                )
                ExpandLabel(expanded)
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViewModeChip("等宽", mono) { mono = true }
                    Spacer(Modifier.width(8.dp))
                    ViewModeChip("Markdown", !mono) { mono = false }
                }
                if (mono) {
                    MonoText(text = body, color = palette.toolOutput)
                } else {
                    ProseText(text = body, color = palette.text)
                }
            }
        }
    }
}

@Composable
private fun ViewModeChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier.clickable(onClickLabel = "切换到$label") { onSelect() },
        shape = PiShapes.chip,
        color = if (selected) palette.selectedBg else palette.cardBg,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) palette.text else palette.muted,
            )
        }
    }
}
