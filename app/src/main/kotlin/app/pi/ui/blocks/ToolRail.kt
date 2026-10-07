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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiStateNode
import app.pi.ui.theme.PiTheme
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
 * ## Who decides "one run"
 *
 * v2 wraps a run in **one** `RailRun` element
 * (`direction-b-v2.html:722-730`: `position:relative;padding-left:26` with one line
 * at `top:16;bottom:16`), so the line's two ends are properties of the *run*, not
 * of a card. This app renders one transcript item at a time, so the list hands each
 * card whether it is the run's first and/or last row
 * (`screens/ChatScreen.kt`, computed from the neighbouring items being `ToolCall`
 * or `ToolDiff`), and the card draws the matching end:
 *
 *  - **first of the run** — the segment starts at [RAIL_LINE_INSET] (16), not at the
 *    card's top edge, so there is no stub above the first node (v2's `top:16`);
 *  - **last of the run** — it stops at [RAIL_LINE_INSET] above the card's bottom edge
 *    (v2's `bottom:16`), so no tail hangs under the last card;
 *  - **in between** — it runs from edge to edge and [RAIL_BRIDGE] past the bottom,
 *    which is what joins it to the next card across the list's own block gap.
 *
 * A card that is both (a lone tool call) therefore draws a line that exists only
 * behind its node, which is what v2's single-card runs look like.
 *
 * ## Why [RAIL_BRIDGE] is the block gap and nothing more
 *
 * The bridging overdraw has to cover exactly the space the list leaves between two
 * items — no more (a tail under the last card) and no less (a hole at every
 * boundary). That space is [PiSpacing.blockGap] (8), v2's block rhythm
 * (`06 §2`「块间距 8」), and the list that produces it is `ChatScreen.kt`. When
 * both were 16 the bridge also covered the old 16 dp rhythm; now that the rhythm
 * and the bridge are the same number, the two ends are cut by [RAIL_LINE_INSET]
 * instead of by an overlong rule.
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
 * `06 §2`「上下各缩进 16」: how far inside the run's own ends the line starts and
 * stops. Only the run's first and last cards use it; see the file KDoc.
 *
 * 16 keeps the line behind the node — the node spans 8 … 25 from the card's top,
 * so a segment starting at 16 is hidden by the node's opaque page-coloured fill
 * and the visible line begins at the circle, exactly as v2's `top:16` does.
 */
internal val RAIL_LINE_INSET: Dp = 16.dp

/**
 * How far past its own bounds a *middle* card draws the line.
 *
 * The transcript's block rhythm, so consecutive rail cards join: the list spaces
 * its items by `PiSpacing.blockGap` (`06 §2`「块间距 8」) and this overdraw is the
 * same number. One constant, one consumer per direction.
 */
internal val RAIL_BRIDGE: Dp = PiSpacing.blockGap

/**
 * One card on the rail: the 1 dp line behind it, the state node beside it, and the
 * card's own content inset by [RAIL_INDENT].
 *
 * @param glyph the state symbol from `06 §4` (`… ✓ ✗ ⊘`, and `±` for a diff).
 * @param tone which state, and therefore which pi token paints the ring and the glyph.
 * @param label what a screen reader hears for the node — a bare glyph is not a state.
 * @param firstOfRun true when no `ToolCall`/`ToolDiff` precedes this row in the
 *   transcript: the line then starts [RAIL_LINE_INSET] inside the card instead of at
 *   its top edge.
 * @param lastOfRun true when no `ToolCall`/`ToolDiff` follows it: the line then stops
 *   [RAIL_LINE_INSET] above the card's bottom edge instead of bridging past it.
 * @param ringColor/glyphColor see [PiStateNode]; the diff node needs two tokens.
 */
@Composable
internal fun ToolRailFrame(
    glyph: String,
    tone: StateTone,
    label: String,
    modifier: Modifier = Modifier,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
    strokeAlpha: Float = 0.45f,
    ringColor: Color? = null,
    glyphColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val stroke = PiSpacing.hairline
    val x = RAIL_LINE_LEFT
    val top = if (firstOfRun) RAIL_LINE_INSET else 0.dp
    val bottom = if (lastOfRun) RAIL_LINE_INSET else -RAIL_BRIDGE
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Drawn behind the children, and (in the middle of a run) past the
                // item's own bounds: the list does not clip an item to its own size,
                // so the overdraw lands in the block gap. `borderMuted` is the token
                // v2's rail uses (`background:'var(--border-muted)'`).
                val from = top.toPx()
                val to = size.height - bottom.toPx()
                if (to > from) {
                    drawRect(
                        color = palette.borderMuted,
                        topLeft = Offset(x.toPx(), from),
                        size = Size(stroke.toPx(), to - from),
                    )
                }
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
            strokeAlpha = strokeAlpha,
            ringColor = ringColor,
            glyphColor = glyphColor,
        )
    }
}
