package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                // `06 §3` 构件 7 + v2's `ThinkRow`: a `3px` stripe as tall as the row's
                // own text block (`height:18`), not the 32 dp status row this used to
                // take from spec §7.4 — the frozen board draws the stripe shorter than
                // the line so it reads as a pen stroke beside the words rather than a
                // band around them (`direction-b-v2.html:905`).
                AccentStripe(pen, THINK_STRIPE_HEIGHT)
                Spacer(Modifier.width(THINK_ROW_GAP))
                Text(
                    text = headline,
                    // v2: `t14 w5` — the row's headline is 14 sp at weight 500.
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                    // F13: this is a body role, so it takes the 4.5:1 variant
                    // (`thinkingText` and `muted` are the same #808080 in pi's dark
                    // theme; 4.47:1 on the canvas is under the floor).
                    color = palette.thinkingBodyOnCanvas,
                )
                if (levelLabel != null) {
                    Spacer(Modifier.width(THINK_ROW_GAP))
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
                // One scope around the body: the thinking text is model prose like any
                // other and the user asked for it to be selectable. This block has no
                // actions of its own, so nothing has to move to a ⋮ here.
                SelectableContent {
                    Text(
                        text = item.text.ifEmpty { "（无思考内容）" },
                        // v2: `t14`, italic, in the tool-body grey, indented past the stripe
                        // (`paddingLeft:11`) and one 6 dp step below the headline row.
                        modifier = Modifier.padding(start = THINK_BODY_INDENT, top = PiSpacing.gutter),
                        style = PiTheme.text.prose.copy(fontStyle = FontStyle.Italic),
                        color = palette.thinkingBodyOnCanvas,
                    )
                }
            }
        }
    }
}

/** v2's `ThinkRow` stripe: `width:3,height:18`. */
private val THINK_STRIPE_HEIGHT = 18.dp

/** v2's `ThinkRow` `gap:8` — between the stripe, the headline and the level word. */
private val THINK_ROW_GAP = 8.dp

/** v2's expanded thinking body sits `paddingLeft:11` — just past the 3 dp stripe. */
private val THINK_BODY_INDENT = 11.dp
