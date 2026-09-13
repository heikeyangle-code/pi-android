package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiStateNode
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiV2Layout
import app.pi.ui.theme.StateTone

/**
 * The execution rail (`06 §3` 构件 1, geometry from `06 §2` 执行轨道).
 *
 * ## It answers
 *
 * 「这几步是同一件事的连续动作，还是我随手跑的三条命令？」 A transcript is a list
 * of turns, and inside a turn a model fires several tool calls back to back. v2
 * draws them as one run: a single 1 dp line down the left gutter with a 17 dp node
 * per card, the node carrying the call's state glyph. The line is the grouping,
 * the node is the state, and the card beside them says what ran.
 *
 * ## The geometry, transcribed
 *
 * ```
 * rail container  padding-left 26
 * line            left 9, width 1, 16 inset top and bottom
 * node            17×17, left 1 / top 8, 1 dp stroke = state colour at 45 %
 * ```
 *
 * The card is inset 26 from the item's left edge, so the node (1 … 18) and the line
 * (9 … 10) sit in the card's left margin and never overlap its content. The node is
 * filled with the page colour so the line does not show through the circle; that
 * fill is the rail's job, the ring and the glyph are [PiStateNode]'s.
 *
 * ## Who decides "one run" — the honest answer
 *
 * **Nobody, in this build.** A run is a property of *consecutive transcript items*,
 * and this app renders each item on its own: `BlockRenderer` receives one
 * [app.pi.rpc.TranscriptItem] and the `LazyColumn` that holds them
 * (`screens/ChatScreen.kt:1018-1060`) gives a block neither its index nor its
 * neighbours — and that file is not part of this batch. So every tool card and
 * every diff card draws **its own segment**, and the segment is drawn from the
 * card's top edge to [RAIL_BRIDGE] past its bottom edge. Two consecutive rail cards
 * therefore meet inside the list's block gap and read as one line, which is the
 * effect v2's run has; what is *not* reproduced is the run's own 16 dp inset at its
 * top and bottom, because a card cannot tell whether it is the first or the last of
 * a run.
 *
 * The follow-up that would complete it is small and belongs to whoever owns the
 * list: pass `firstOfRun` / `lastOfRun` (or a `ToolState?` neighbour pair) down from
 * `itemsIndexed`, computed from `previous is ToolCall || previous is ToolDiff` and
 * the same for the next item, and skip [RAIL_BRIDGE] at those two ends. Until then
 * the visible cost is one 8 dp stub above the first node of a run and a ~16 dp tail
 * below the last card, against v2's clean inset.
 *
 * ## Why [RAIL_BRIDGE] is 16 and not the 8 the block rhythm asks for
 *
 * The transcript spaces its items by `PiSpacing.screen` (16) today
 * (`screens/ChatScreen.kt:971-982`); v2's target rhythm is `PiV2Layout.blockGap`
 * (8), and the change is one line in that same list, which this batch does not own.
 * Overdrawing by 16 keeps the line unbroken at **both** values (the gap is bridged
 * or covered), where 8 would leave an 8 dp hole at every card boundary until the
 * list changes. A longer tail, never a hole.
 */

/** `06 §2`「左内边距 26」: where a rail card's content starts. */
internal val RAIL_INDENT: Dp = 26.dp

/** `06 §2`「竖线 left 9」: the line's x, measured from the transcript item's left edge. */
internal val RAIL_LINE_LEFT: Dp = 9.dp

/** `06 §2`「节点 left 1 / top 8」: the node's offset inside the item. */
internal val RAIL_NODE_LEFT: Dp = 1.dp
internal val RAIL_NODE_TOP: Dp = 8.dp

/** `06 §2`「节点 17×17 圆」. */
internal val RAIL_NODE_SIZE: Dp = 17.dp

/**
 * How far past its own bounds a card draws the line, top and bottom.
 *
 * At least the transcript's block gap, so consecutive rail cards join (see the file
 * KDoc); 16 is `PiSpacing.screen`, which is the value that list spaces its items by
 * today. One constant, one consumer, and it disappears together with the bridge when
 * the list learns about runs.
 */
internal val RAIL_BRIDGE: Dp = PiSpacing.screen

/**
 * One card on the rail: the 1 dp line behind it, the state node beside it, and the
 * card's own content inset by [RAIL_INDENT].
 *
 * @param glyph the state symbol from `06 §4` (`… ✓ ✗ ⊘`, and `±` for a diff).
 * @param tone which state, and therefore which pi token paints the ring and the glyph.
 * @param label what a screen reader hears for the node — a bare glyph is not a state.
 */
@Composable
internal fun ToolRailFrame(
    glyph: String,
    tone: StateTone,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val stroke = PiV2Layout.hairline
    val x = RAIL_LINE_LEFT
    val bridge = RAIL_BRIDGE
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Drawn behind the children, and past the item's own bounds: the list
                // does not clip an item to its own size, so the overdraw lands in the
                // block gap. `borderMuted` is the token v2's rail uses
                // (`background:'var(--border-muted)'`).
                drawRect(
                    color = palette.borderMuted,
                    topLeft = Offset(x.toPx(), 0f),
                    size = Size(stroke.toPx(), size.height + bridge.toPx()),
                )
            },
    ) {
        Column(modifier = Modifier.padding(start = RAIL_INDENT), content = content)
        PiStateNode(
            glyph = glyph,
            tone = tone,
            label = label,
            size = RAIL_NODE_SIZE,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = RAIL_NODE_LEFT, y = RAIL_NODE_TOP)
                // v2's node is opaque in the page colour (`background:'var(--page)'`):
                // without it the line would run through the circle and the glyph.
                .background(palette.pageBg, CircleShape),
        )
    }
}
