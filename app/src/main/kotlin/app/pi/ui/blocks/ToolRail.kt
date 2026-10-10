package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The left edge a tool card and a diff card share (`06 §2` 执行轨道 左内边距).
 *
 * ## What used to be here
 *
 * This file drew the **execution rail** (`06 §3` 构件 1): one 1 dp line down the left
 * gutter of a run of consecutive tool calls, with a 17 dp state node per card carrying
 * the call's own glyph. Both are gone:
 *
 *  - the **node**'s glyph is now a permanent cell at the head of the card's header row
 *    ([ToolHeader]), which is where the eye lands first — drawing it on the rail as well
 *    was the same state said twice;
 *  - the **line** went with the node. It was the run's grouping, and with the glyph in
 *    the header it no longer says anything the header does not, while it cost a
 *    per-row run computation (`firstOfRun` / `lastOfRun`) that only it consumed: the
 *    list had to look at both neighbours of every tool row to place the line's two ends
 *    (see `screens/ChatScreen.kt`, and `ToolRailFrame`'s old KDoc argument).
 *
 * So a tool card (and the diff card beside it) is no longer *on* a rail; it is a card
 * whose container starts at [RAIL_INDENT], and this file owns that one number.
 *
 * Nothing here parses, formats or counts, and nothing here looks at a neighbour row.
 */

/**
 * `06 §2`「左内边距 26」: where a tool (or diff) card's container starts.
 *
 * This is `06 §2`'s own rail inset, kept as the card's left edge after the rail itself
 * was removed. The cards were offset by it so the node (1 … 18) and the line (9 … 10)
 * could sit in their left margin; that margin is kept, so this removal decides nothing
 * about where the container begins.
 */
internal val RAIL_INDENT: Dp = 26.dp

/**
 * The one place that says how far in a tool card and a diff card start: their content is
 * a `Column` inset by [RAIL_INDENT], so the two cards cannot drift apart.
 *
 * The name is the rail's and survives the rail (see the file KDoc for what was removed
 * and why): this is still the frame the two cards are drawn in, it simply no longer
 * draws a line or a node behind them. The `firstOfRun` / `lastOfRun` parameters are gone
 * with the line, so a card's own shape no longer depends on which rows happen to sit
 * beside it in the transcript.
 */
@Composable
internal fun ToolRailFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = RAIL_INDENT), content = content)
}
