package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.ThinkingBlock
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * `thinking-block` (docs/pi-android-ui-spec.md §7.4): collapsed to a 32dp line
 * with the thinking-level colour stripe (the app's signature visual), expanding
 * to italic text in `thinkingText`. The level is always spelled out in words as
 * well, because colour must never be the only channel.
 */
@Composable
fun ThinkingBlockBlock(
    item: ThinkingBlock,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val pen = palette.thinking(item.level ?: "medium")
    val levelLabel = item.level?.takeIf { it.isNotBlank() }?.let { PiThinkingLevel.fromWire(it).label }
    val headline = if (item.streaming) {
        "思考中…"
    } else {
        "思考" + (item.elapsedMs?.let { " ${formatDuration(it)}" } ?: "")
    }

    BlockColumn(modifier) {
        ToggleRow(expanded = expanded, onToggle = { expanded = !expanded }) {
            AccentStripe(pen, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
            if (levelLabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = levelLabel,
                    style = PiTheme.text.meta,
                    color = pen,
                )
            }
            Spacer(Modifier.weight(1f))
            ExpandLabel(expanded)
        }
        if (expanded) {
            Text(
                text = item.text.ifEmpty { "（无思考内容）" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontStyle = FontStyle.Italic,
                    lineHeight = 22.sp,
                ),
                color = palette.thinkingText,
            )
        }
    }
}
