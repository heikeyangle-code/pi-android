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
import app.pi.ui.theme.PiSpacing
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
        // F28: the whole block is the toggle target, which is what pi does —
        // `components/assistant-message.ts:160-166` wraps the entire thinking
        // component in the `MouseRegion` that flips its visibility.
        ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // §7.4's thinking-block row is "收起：一行 32dp", so the stripe is
                // that row's height — the spec's value, now named.
                AccentStripe(pen, PiSpacing.statusRow)
                Spacer(Modifier.width(PiSpacing.inner))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    // F13: this is a body role, so it takes the 4.5:1 variant
                    // (`thinkingText` and `muted` are the same #808080 in pi's dark
                    // theme; 4.47:1 on the canvas is under the floor).
                    color = palette.thinkingBodyOnCanvas,
                )
                if (levelLabel != null) {
                    Spacer(Modifier.width(PiSpacing.inline))
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
                    color = palette.thinkingBodyOnCanvas,
                )
            }
        }
    }
}
