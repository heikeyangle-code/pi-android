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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiStateNode
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone

/**
 * The execution rail (`06 §3` 构件 1, geometry from `06 §2` 执行轨道) and the **run shell**
 * (`差异表` §2 第 8/9/10/11 行).
 *
 * ## It answers
 *
 * 「这几步是同一件事的连续动作，还是我随手跑的三条命令？」 A transcript is a list
 * of turns, and inside a turn a model fires several tool calls back to back. v2
 * draws them as one run: a single 1 dp line down the left gutter with a node
 * per card, the node carrying the call's state glyph. The line is the grouping,
 * the node is the state, and the card beside them says what ran.
 *
 * ## The geometry, transcribed
 *
 * ```
 * rail container  padding-left 26
 * line            left 9, width 1, 16 inset top and bottom
 * node            15×15, left 2 / top 4.5, 1 dp stroke = state colour at 45 %
 * shell           radius 10 at the run's two ends, 1 px ring, 1 px interior dividers
 * ```
 *
 * The card is inset 26 from the item's left edge, so the node (2 … 17) and the line
 * (9 … 10) sit in the card's left margin and never overlap its content, and the node's centre
 * (9.5) lands exactly on the line's (9 + ½ of the 1 dp stroke) — see [RAIL_NODE_LEFT]. The node
 * is filled with the page colour so the line does not show through the circle; that
 * fill is the rail's job, the ring and the glyph are [PiStateNode]'s.
 *
 * **The node is 15 dp and its `top` follows the row's centre** (`差异表` §2 第 10/11 行).
 * It was 17×17 at `top 8`, both computed for the 39.5 dp two-row card: with the card at 24 dp the
 * row's centre is 12, so a 15 dp node starts at `12 − 7.5 = 4.5` — v5's own
 * `.ng{top:4.5px;width:15px}`. `06 §2` says 17; what is *not* negotiable is the centre, because a
 * node that misses the row's centre drifts into the row below it inside a run. The ring (45 %)
 * and the `pageBg` fill are unchanged.
 *
 * `06 §2`'s「竖线上下各缩进 16」 also still holds: 16 lands well inside the node (4.5 … 19.5),
 * which is exactly the point — the segment's visible start is the circle.
 *
 * ## Who decides "one run", and where its 1 px ring comes from
 *
 * v2 wraps a run in **one** `RailRun` element
 * (`direction-b-v2.html:722-730`: `position:relative;padding-left:26` with one line
 * at `top:16;bottom:16`), so the line's two ends are properties of the *run*, not
 * of a card. This app renders one transcript item at a time, so the list hands each
 * card whether it is the run's first and/or last row *and* the run's ring state
 * ([ToolRunSlot], computed in one pass by `toolRunPlan`), and the card draws the matching end:
 *
 *  - **first of the run** — the segment starts at [RAIL_LINE_INSET] (16), not at the
 *    card's top edge, so there is no stub above the first node (v2's `top:16`), and the
 *    shell ring rounds its two top corners;
 *  - **last of the run** — it stops at [RAIL_LINE_INSET] above the card's bottom edge
 *    (v2's `bottom:16`), so no tail hangs under the last card, and the ring rounds its two
 *    bottom corners;
 *  - **in between** — it runs edge to edge and overdraws past the bottom by **this row's own
 *    bottom air** ([LocalRowGap]), which is what joins it to the next card across the gap the
 *    list leaves.
 *
 * A card that is both (a lone tool call) therefore draws a line that exists only behind its node,
 * and a shell that is a single rounded rectangle — what v2's single-card runs look like, and
 * byte-for-byte what the card drew before the merge.
 *
 * ## 壳（一个 run 一块板）不是发明：它是「每卡 1px 状态色 @35% 描边」的合并形态
 *
 * pi's TUI has no run grouping, so this is new *shape* — but every channel in it is pi's: the
 * outline is the card's own status border (@35 %) moved out to the run, the boundary between two
 * rows is that same border compressed to the 1 px the **lower** row paints in its own state
 * colour, and when the rows disagree the shell refuses to speak for any of them and takes
 * `borderMuted` (`railRingColor`). What it buys is the thing the user asked for: a run of calls
 * reads as one block of work, and the row that changed state is still visible as a line, a fill
 * and a glyph.
 *
 * ## Why the overdraw is read back from the row and not from a constant
 *
 * It used to be `RAIL_BRIDGE = PiSpacing.blockGap` — a compile-time 8 while the actual spacing is
 * the `app.appearance.messageDensity` setting (4 / 8 / 16), equal in the default tier only. That
 * is the whole bug behind the rail visibly breaking apart under 宽松, and the shell would have
 * inherited it as a seam. Both read **[LocalRowGap]**, the very value `BlockColumn` padded this
 * row with: one source, nothing to synchronise (`BlockChrome.kt` has the argument,
 * `BlockRhythm.kt` the numbers).
 */

/** `06 §2`「左内边距 26」: where a rail card's content starts. */
internal val RAIL_INDENT: Dp = 26.dp

/** `06 §2`「竖线 left 9」: the line's x, measured from the transcript item's left edge. */
internal val RAIL_LINE_LEFT: Dp = 9.dp

/**
 * The node's x: **2 dp, so the 15 dp circle stays concentric with the 1 dp line**
 * (`06 §2`「节点 left 1」).
 *
 * `left 1` was arithmetic for the **17 dp** node: `1 + 17/2 = 9.5`, which is the line's own
 * centre ([RAIL_LINE_LEFT] 9 + half of the 1 dp stroke). When the node became 15 dp
 * (`差异表` §2 第 10 行) the same `1` put its centre at `8.5` — exactly 1 dp of eccentricity, the
 * circle sitting off its own wire, and visible on any run long enough for the line to read as a
 * wire at all. `2 + 15/2 = 9.5` restores it: the same centre, so
 * `差异表` §2 第 13 行's 「线 `left 9`」 stays untouched. v5 measures the same box — 节点绝对
 * x `16..31` against a line whose centre is `23.5` (page edge 14 + 9.5).
 *
 * The size is [RAIL_NODE_SIZE]'s business; these two are one decision and are asserted together
 * (`ToolCardTextCheck`), because a size change that forgets this number is exactly how the
 * eccentricity got in.
 */
internal val RAIL_NODE_LEFT: Dp = 2.dp

/**
 * The node's `top`: **the 24 dp row's centre minus half the node** (`差异表` §2 第 11 行; v5's
 * `.ng{top:4.5px}` for its 15 px node).
 *
 * It was 8, which was `39.5/2 − 17/2` for the two-row card. The card is 24 dp now, so 8 would put
 * the node's centre at 15.5 — 3.5 dp below the row it belongs to, i.e. visibly drifting toward
 * the next row inside a run.
 */
internal val RAIL_NODE_TOP: Dp = 4.5.dp

/** `差异表` §2 第 10 行 / v5: 「节点 15×15 圆」 (`06 §2` says 17 — the centre is what moved). */
internal val RAIL_NODE_SIZE: Dp = 15.dp

/**
 * `06 §2`「上下各缩进 16」: how far inside the run's own ends the line starts and
 * stops. Only the run's first and last cards use it; see the file KDoc.
 *
 * 16 keeps the line behind the node — the node spans 4.5 … 19.5 from the card's top, so a segment
 * starting at 16 is hidden by the node's opaque page-coloured fill and the visible line begins at
 * the circle, exactly as v2's `top:16` does.
 */
internal val RAIL_LINE_INSET: Dp = 16.dp

/**
 * One card on the rail: the 1 dp line behind it, the state node beside it, the **run's shell**
 * around it, and the card's own content inset by [RAIL_INDENT].
 *
 * @param glyph the state symbol from `06 §4` (`… ✓ ✗ ⊘`, and `±` for a diff).
 * @param tone which state, and therefore which pi token paints the ring and the glyph.
 * @param label what a screen reader hears for the node — a bare glyph is not a state.
 * @param firstOfRun true when no `ToolCall`/`ToolDiff` precedes this row in the
 *   transcript: the line then starts [RAIL_LINE_INSET] inside the card instead of at
 *   its top edge, and the shell's top corners are rounded.
 * @param lastOfRun true when no `ToolCall`/`ToolDiff` follows it: the line then stops
 *   [RAIL_LINE_INSET] above the card's bottom edge instead of overdrawing past it, and the
 *   shell's bottom corners are rounded.
 * @param ringColor/glyphColor see [PiStateNode]; the diff node needs two tokens.
 * @param shellFill the card's own container colour — what this row paints the gap below it with,
 *   so a run reads as one plate instead of a row of cards. Null draws no shell (a caller outside
 *   the transcript).
 * @param shellRing the run's 1 px outline colour ([railRingColor]); null draws none.
 * @param divider the 1 px boundary between this row and the one above it, in **this** row's state
 *   colour (`ToolCard` passes `状态色 @35%`, `DiffBlock` the diff's neutral). Drawn at this row's
 *   top edge, over the fill overdraw the row above left there.
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
    shellFill: Color? = null,
    shellRing: Color? = null,
    divider: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val stroke = PiSpacing.hairline
    val x = RAIL_LINE_LEFT
    val top = if (firstOfRun) RAIL_LINE_INSET else 0.dp
    // The row's own bottom air, the same value `BlockColumn` padded this row with — see the file
    // KDoc for why it is read back rather than passed down or written down here.
    val gap = LocalRowGap.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Drawn behind the children, and (in the middle of a run) past the item's own
                // bounds: the list does not clip an item to its own size, so the overdraw lands
                // in the row's own bottom air. `borderMuted` is the token v2's rail uses
                // (`background:'var(--border-muted)'`).
                val from = top.toPx()
                val to = if (lastOfRun) size.height - RAIL_LINE_INSET.toPx() else size.height + gap.toPx()
                if (to > from) {
                    drawRect(
                        color = palette.borderMuted,
                        topLeft = Offset(x.toPx(), from),
                        size = Size(stroke.toPx(), to - from),
                    )
                }
                // The shell's **fill overdraw**: below a row that is not the run's last, the gap
                // is painted in this row's own container colour, so two rows meet with no stripe
                // of page between them. The next row's divider lands exactly at the end of this
                // band, because the next row starts exactly one gap below this one.
                if (shellFill != null && !lastOfRun) {
                    drawRect(
                        color = shellFill,
                        topLeft = Offset(RAIL_INDENT.toPx(), size.height),
                        size = Size(size.width - RAIL_INDENT.toPx(), gap.toPx()),
                    )
                }
            }
            // The 1 px outline goes **over** the card: the card's own background fills the same
            // pixels (its edge *is* the outline), so a ring drawn behind it would be invisible.
            .drawWithContent {
                drawContent()
                if (shellRing != null) {
                    drawShellRing(
                        color = shellRing,
                        gap = gap.toPx(),
                        firstOfRun = firstOfRun,
                        lastOfRun = lastOfRun,
                    )
                }
                if (divider != null && !firstOfRun) {
                    drawRect(
                        color = divider,
                        topLeft = Offset(RAIL_INDENT.toPx(), 0f),
                        size = Size(size.width - RAIL_INDENT.toPx(), stroke.toPx()),
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

/**
 * The run's outline: a horizontal cap — with its two corner arcs — at each end that **is** an end
 * of the run. There are deliberately **no vertical edges** (see the note at the path below): the
 * sides of the run are not something this app draws any more, so a run of one card reads as a cap
 * above and a cap below, and a longer run as a cap at each end with the 1 dp interior dividers
 * carrying the row boundaries in between.
 *
 * The caps a run's interior must *not* draw are simply absent from the path rather than drawn and
 * clipped away.
 *
 * The path is inset by half the stroke, so a 1 dp line's *outer* edge sits exactly on the card's
 * boundary: the same pixel a CSS `border` occupies.
 */
private fun DrawScope.drawShellRing(
    color: Color,
    gap: Float,
    firstOfRun: Boolean,
    lastOfRun: Boolean,
) {
    val stroke = PiSpacing.hairline.toPx()
    val half = stroke / 2f
    val left = RAIL_INDENT.toPx() + half
    val right = size.width - half
    val radius = BLOCK_CARD_RADIUS.toPx()
    val top = if (firstOfRun) half else -(gap - half)
    val bottom = if (lastOfRun) size.height - half else size.height + gap - half
    val path = Path()
    // **No vertical edges.** The run's two sides used to be drawn here (one 1 dp line at
    // `RAIL_INDENT`, one at the right edge) and they are the two lines the user rejected:
    // "左侧竖着那条细线和右侧那条细线我不要" — on a merged run they run the whole length
    // beside every row, and on a single-card run they are the card's own left/right border.
    // What is left is the run's identity in the *horizontal* channel only: the cap (with its
    // two corner arcs) at each end of the run, and the interior 1 dp divider between rows.
    if (firstOfRun) {
        path.moveTo(left, top + radius)
        path.arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        path.lineTo(right - radius, top)
        path.arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), 270f, 90f, false)
    }
    if (lastOfRun) {
        path.moveTo(right, bottom - radius)
        path.arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 0f, 90f, false)
        path.lineTo(left + radius, bottom)
        path.arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 90f, 90f, false)
    }
    drawPath(path, color = color, style = Stroke(width = stroke))
}
