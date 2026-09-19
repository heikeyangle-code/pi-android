package app.pi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiResponses
import app.pi.rpc.TokenUsage
import app.pi.ui.theme.PiMark
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * pi's own token formatter, transcribed from
 * `packages/coding-agent/src/modes/interactive/components/footer.ts:24-30`.
 *
 * It is *not* the same rounding as `ui/blocks/BlockChrome.kt`'s
 * `formatTokens` (that one prints `3k` where pi prints `3.2k` for 3 200). The
 * summarization billing line uses pi's, because its text is pi's verbatim.
 */
fun piFormatTokens(count: Long): String = when {
    count < 1_000L -> count.toString()
    count < 10_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
    count < 1_000_000L -> "${Math.round(count / 1_000.0)}k"
    count < 10_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    else -> "${Math.round(count / 1_000_000.0)}M"
}

/**
 * The transcript's status line — v2's `StateLine` (`06 §3` 构件 3, `04 §1.2`).
 *
 * One row, read left to right: the context percentage, an eight-segment progress bar,
 * the tokens in use against the window, the two readings direction A contributed
 * (`输出`, `缓存读`), and the session's cost pinned to the right:
 *
 * ```
 * 上下文 52.3% [▮▮▮▮▯▯▯▯] 104k / 200k (auto) 输出 8.2k 缓存读 61.4k $0.420
 * ```
 *
 * `52.3` and `$0.420` are **pi's own precision**, not this row's choice:
 * `contextPercentValue.toFixed(1)` and `usageTotals.cost.toFixed(3)`
 * (`components/footer.ts:111`, `:143`). v2's `StateLine` prints `{p.pct}%` — the
 * value it was *passed* — so its sample `52` says nothing about rounding, and the
 * numbers it was passed are pi's. The `%` is there in both: pi's footer is
 * `` `${contextPercent}%` `` (`footer.ts:150-156`) and v2's row is
 * `上下文 {p.pct}%` (`direction-b-v2.html:794`).
 *
 * **There are no `·` separators on this row.** v2's `StateLine` is one
 * `className="rw"` row with `gap:5` whose parts are separate spans — `输出` and
 * `缓存读` included (`direction-b-v2.html:798-803`) — and phone01 renders it with
 * the gaps only. The app used to join the readings with a literal `·`, which is a
 * character v2 draws nowhere on this row.
 *
 * ## Where each figure comes from (all of them pi's)
 *
 *  - `上下文 N%` — `stats.contextUsage.percent`, i.e. pi's own `getContextUsage()`,
 *    which is `estimate.tokens / contextWindow * 100`
 *    (`core/agent-session.ts:3446-3450`); `?` when pi reports none, never a
 *    substituted 0 (`footer.ts:108-110`).
 *  - the bar — the same percentage across eight segments of `3×8` with a 1px gap,
 *    filled in `accent` (`06 §2` 状态行). It is drawn only when pi reports a
 *    percentage: an empty bar would claim "0 %".
 *  - `used / window` — `contextUsage.tokens` / `contextUsage.contextWindow`; the
 *    window falls back to the model's own (`footer.ts:109`).
 *  - `输出` / `缓存读` — `stats.tokens.output` / `stats.tokens.cacheRead`, the two
 *    fields A moved in (`04 §1.2`: 两个读数字段与「会话信息」sheet 里的 Token 行同源),
 *    formatted by [piFormatTokens] — pi's rounding (`footer.ts:24-30`), **not**
 *    `ui/blocks/BlockChrome.kt`'s `formatTokens`, which prints `3k` where pi prints
 *    `3.2k`.
 *  - `$…` — `stats.cost` at pi's three decimals (`footer.ts:142-146`), only when
 *    non-zero, and it is the row's right-hand anchor.
 *
 * The ` (auto)` suffix belongs to the **window** reading, not to the percentage:
 * pi's line is `` `${percent}%/${window}${auto}` `` (`footer.ts:150-156`), so it
 * rides `used / window` here as `13k / 1.0M (auto)`. The app used to hang it off the
 * percentage, which reads as if the *percentage* were the automatic part.
 *
 * ## What is no longer on this row, and why nothing is lost
 *
 * pi's footer also prints `↑input ↓output RcacheRead WcacheWrite CH…%`
 * (`footer.ts:106-146`). v2's status line is a *reading*, not a copy of that footer:
 * `04 §1.2` adds the two A readings to B's line (`上下文 52% [分段进度] 104k / 200k
 * $0.42`) and stops there, so the input total, the cache-write total and the
 * cache-hit rate leave this row. None of them is dropped from the app: 会话信息
 * (`ui/chat/ChatSheets.kt`'s stats sheet) prints 输入 / 输出 / 缓存读 / 缓存写 / 合计
 * in full. B7 deleted the `latestUsage` parameter that used to carry them here: with
 * no reading on this row that took a `TokenUsage`, it had become a dead argument
 * whose only effect was to keep the caller importing `TokenUsage`.
 *
 * The percentage keeps pi's own colour thresholds (`>90` error, `>70` warning —
 * `footer.ts:154-156`); every other figure is muted or body text. No ring, no sweep
 * animation, no glow: pi's footer is static text and this row is too.
 *
 * @param stats `get_session_stats`; null before the first read, in which case the
 *   row renders nothing rather than zeros.
 * @param contextWindowFallback the model's window, pi's own fallback when
 *   `getContextUsage()` reports none (`footer.ts:109`).
 * @param autoCompaction pi's `autoCompactEnabled` → the ` (auto)` suffix.
 */
@Composable
fun PiStatusLine(
    stats: PiResponses.SessionStats?,
    contextWindowFallback: Long?,
    autoCompaction: Boolean,
    modifier: Modifier = Modifier,
) {
    if (stats == null) return
    val totals = stats.tokens
    val usage = stats.contextUsage
    val contextWindow = usage?.contextWindow ?: contextWindowFallback
    val percent = usage?.percent
    val used = usage?.tokens
    // pi: `?` when the percentage is unknown, never a substituted 0. One decimal is
    // pi's own `toFixed(1)` (`footer.ts:111`) — see this function's KDoc.
    val percentText = percent?.let { String.format(Locale.US, "%.1f", it) } ?: "?"
    val auto = if (autoCompaction) " (auto)" else ""
    val contextColor = when {
        percent != null && percent > 90.0 -> PiTheme.palette.error
        percent != null && percent > 70.0 -> PiTheme.palette.warning
        else -> PiTheme.palette.muted
    }
    // A reading appears only once it has a figure (pi's own rule for these parts,
    // `footer.ts:130-133`): "输出 0" before the first reply would be a claim about a
    // model that has not spoken yet.
    val readings = buildList {
        totals?.output?.takeIf { it > 0L }?.let { add("输出 ${piFormatTokens(it)}") }
        totals?.cacheRead?.takeIf { it > 0L }?.let { add("缓存读 ${piFormatTokens(it)}") }
    }
    // Three decimals, pi's own `toFixed(3)` (`footer.ts:143`): this is what keeps a
    // real `$0.006` from being rounded to `$0.01` and a `$0.004` from `$0.00`.
    val cost = stats.cost?.takeIf { it != 0.0 }?.let { "$${String.format(Locale.US, "%.3f", it)}" }
    val windowText = contextWindow?.takeIf { it > 0L }?.let { piFormatTokens(it) }
    // "No data, no row": with no percentage, no window and no readings there is
    // nothing to read, and an empty status line would still cost 32dp.
    if (percent == null && windowText == null && readings.isEmpty() && cost == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PiSpacing.statusRow)
            .padding(horizontal = PiSpacing.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // pi's footer and v2's row both print the unit; see the KDoc.
            text = "上下文 $percentText%",
            style = PiTheme.text.meta,
            color = contextColor,
            maxLines = 1,
        )
        if (percent != null) {
            Spacer(Modifier.width(PiSpacing.small))
            ContextSegments(percent)
        }
        if (windowText != null) {
            Spacer(Modifier.width(PiSpacing.small))
            Text(
                // pi reports no token count after a compaction until the next
                // response (`agent-session.ts:3450`), and `?` is the honest spelling
                // it uses for exactly that window (`footer.ts:110`).
                // ` (auto)` rides the window, as it does in pi's own concatenation.
                text = "${used?.let { piFormatTokens(it) } ?: "?"} / $windowText$auto",
                // `06 §2` 状态行 + `06 §2` 字号: every reading on this row is a machine
                // reading at the 12 sp step (`mono t12 tab` in v2's `StateLine`), which
                // is what keeps the figures aligned as they tick.
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
        }
        if (readings.isNotEmpty()) {
            // One span per reading, separated by the row's own gap — v2's shape
            // (`direction-b-v2.html:798-803`), with no `·` between them. The group
            // absorbs the slack so the cost stays pinned to the right (`06 §2` leaves
            // the row's tail to the cost).
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
            ) {
                readings.forEach { reading ->
                    Text(
                        text = reading,
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.text,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (cost != null) {
            Spacer(Modifier.width(PiSpacing.small))
            Text(
                text = cost,
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.text,
                maxLines = 1,
            )
        }
    }
}

/**
 * pi's context reading as a **ring**, the composer's and the detail sheet's form of
 * the status row's eight-segment bar.
 *
 * ## What it is
 *
 * An arc of [diameter] with a [stroke]-wide pen and **no number inside**: the fill
 * runs from twelve o'clock clockwise for `percent × 3.6°`, so a glance reads the
 * proportion without reading a figure. The transcript's own reading is not lost —
 * the detail sheet prints the same percentage in words, and the ring's own
 * `contentDescription` is set by the caller through its click label.
 *
 * ## The two colours are pi's thresholds, not this component's taste
 *
 * The track is `muted`; the fill is `accent` up to 70 %, `warning` above it and
 * `error` above 90 % — the same three bands pi colours its footer percentage with
 * (`components/footer.ts:154-156`), which the app's status row also uses. Colour is
 * never the only channel here either: the percentage is spelled out in the detail
 * sheet one tap away, and the ring's own click label says what it opens.
 *
 * ## `percent == null` is a real state, not zero
 *
 * pi reports no context usage between a compaction and the next reply. The ring then
 * draws the **empty track** and a centred `?` — pi's own spelling for that window
 * (`footer.ts:110`, and the app's status row used it) — rather than an empty arc that
 * would read as "0 %".
 *
 * @param percent 0–100, straight from pi's `getContextUsage().percent`; null when pi
 *   has not reported one.
 * @param diameter the ring's outer size.
 * @param stroke the pen width. `06 §2`'s bar has no analogue; 2 is the weight that
 *   keeps a 24 dp ring legible as a ring rather than a dot.
 * @param placeholderStyle the style of the `?`. It is a parameter because the ring
 *   is drawn at two very different sizes (24 dp in the composer, ~52 dp in the sheet)
 *   and one glyph size cannot serve both.
 */
@Composable
fun PiContextRing(
    percent: Double?,
    diameter: Dp,
    modifier: Modifier = Modifier,
    stroke: Dp = 2.dp,
    // Required rather than defaulted: the two call sites draw the ring at very
    // different sizes (24 dp in the composer's key row, 52 dp in the sheet), and a
    // default would have to read `PiTheme.text` inside a parameter list — a
    // composable call in a default argument works, but this project has no other
    // instance of it and the two sizes want two different styles anyway.
    placeholderStyle: TextStyle,
) {
    val palette = PiTheme.palette
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val pen = stroke.toPx().coerceAtMost(size.minDimension / 2f)
            val inset = pen / 2f
            val box = Size(size.width - pen, size.height - pen)
            val arc = Offset(inset, inset)
            drawArc(
                color = palette.muted,
                startAngle = RING_START_ANGLE,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arc,
                size = box,
                style = Stroke(width = pen),
            )
            if (percent != null) {
                drawArc(
                    color = contextProgressColor(percent, palette),
                    startAngle = RING_START_ANGLE,
                    sweepAngle = (percent * 3.6).toFloat().coerceIn(0f, 360f),
                    useCenter = false,
                    topLeft = arc,
                    size = box,
                    style = Stroke(width = pen),
                )
            }
        }
        if (percent == null) {
            Text(
                text = "?",
                style = placeholderStyle,
                color = palette.bodyOnTool,
                maxLines = 1,
            )
        }
    }
}

/** Twelve o'clock: the angle Compose measures arcs from is 3 o'clock. */
private const val RING_START_ANGLE = -90f

/**
 * The colour pi gives a context percentage: `accent`, `warning` past 70, `error` past
 * 90 (`components/footer.ts:154-156`). Shared by the ring's fill and the detail
 * sheet's percentage, so the two cannot disagree.
 *
 * [PiStatusLine] keeps its own three-band expression rather than calling this one,
 * and deliberately: that row prints the percentage as a *label* in `muted` at rest,
 * where this function's at-rest colour is `accent` because it is painting a **fill**.
 * The two share the two thresholds, not the at-rest colour, and the thresholds are
 * spelled once here and once there — a reader changing one must change both.
 *
 * A null [percent] is not a colour: the caller draws no fill at all and says `?`.
 */
fun contextProgressColor(percent: Double, palette: PiPalette): Color = when {
    percent > 90.0 -> palette.error
    percent > 70.0 -> palette.warning
    else -> palette.accent
}

/**
 * The app's own menu, in v2's container rather than Material's.
 *
 * ## Why not `DropdownMenu`
 *
 * M3's default menu draws a 2 dp shadow, a 4 dp corner and its own surface colour.
 * `04 §2.3` and all of v2 say the opposite: hierarchy is 1 px lines and the surface
 * ladder, **no shadows**, and the corners in play are 10 and 14. A menu is a surface
 * like any other, so it takes v2's: `surf-high`, a 1 px `borderMuted` ring, radius
 * 10, zero elevation — and, for the same reason, **no ripple**: the pressed row
 * simply becomes `surf-highest`, which is a colour swap and not an animation.
 *
 * ## It has to be a `Popup`
 *
 * The composer's own box is a 14 dp-radius container that clips its content, so a
 * menu laid over it as a sibling `Box` would be cut off at the corner. `Popup` is a
 * separate window (and it dismisses on an outside tap for free).
 *
 * ## Placement
 *
 * [PiMenuPlacement.Above] is the composer's: the menu's left edge lines up with the
 * anchor's and its **bottom** sits 8 dp above the anchor's top, because the anchor is
 * a chip in a row that lives at the bottom of the screen. [PiMenuPlacement.Below] is
 * for an anchor near the top of the screen (the AppBar's ⋮), where "above" would be
 * off-screen. Neither flips itself: both callers know where they are, and a menu that
 * silently moved would be harder to reason about than one that is placed.
 *
 * ## The scroll bound
 *
 * [maxHeight] exists for the AppBar's menu, which has seventeen rows and would be
 * ~680 dp tall — taller than the phone. v2's spec for this container has no bound
 * because the menu it was drawn for has four rows (169 dp); the bound is this app's
 * adaptation for the long one, and the composer's menu never reaches it.
 *
 * @param items the rows, in order. A row with [PiMenuItem.dividerBefore] gets a
 *   1 px `borderMuted` rule at 55 % above it.
 */
@Composable
fun PiMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<PiMenuItem>,
    placement: PiMenuPlacement,
    modifier: Modifier = Modifier,
    maxHeight: Dp = PI_MENU_MAX_HEIGHT,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val height = PI_MENU_ROW_HEIGHT * items.size +
        PI_MENU_VERTICAL_PADDING * 2 +
        (if (items.any { it.dividerBefore }) PI_MENU_DIVIDER_HEIGHT else 0.dp)
    // `Popup`'s offset is from the anchor's top-left, in pixels.
    val offsetY = when (placement) {
        PiMenuPlacement.Above -> -(height + PI_MENU_ANCHOR_GAP)
        PiMenuPlacement.Below -> PI_MENU_ANCHOR_GAP
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = with(density) { IntOffset(0, offsetY.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .width(PI_MENU_WIDTH)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(PI_MENU_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = PiTheme.palette.borderMuted,
                    shape = RoundedCornerShape(PI_MENU_RADIUS),
                )
                .verticalScroll(rememberScrollState())
                .padding(vertical = PI_MENU_VERTICAL_PADDING),
        ) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = PiTheme.palette.borderMuted.copy(alpha = 0.55f),
                    )
                }
                PiMenuRow(item = item, onDismiss = onDismiss)
            }
        }
    }
}

/** One row of a [PiMenu]: `[symbol 24] + 10 + label + slack + right-aligned note`. */
@Composable
private fun PiMenuRow(item: PiMenuItem, onDismiss: () -> Unit) {
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // What a screen reader hears. The rule the designer set: wherever a symbol is
    // visible there must be Chinese beside it, and whatever is announced must *be*
    // that Chinese — so the label and the note are joined, and a row whose spoken
    // name is not just those two (「清空」 → 「清空草稿」) overrides it.
    val spoken = item.a11yLabel ?: item.note?.let { "${item.label}，$it" } ?: item.label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                // No ripple: v2's menus have no press animation, and M3's would tint
                // the row with `primary`, which this app reserves for real actions.
                indication = null,
                onClickLabel = spoken,
            ) {
                onDismiss()
                item.onSelect()
            }
            .height(PI_MENU_ROW_HEIGHT)
            .padding(horizontal = PI_MENU_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(PI_MENU_SYMBOL_WIDTH), contentAlignment = Alignment.Center) {
            if (item.symbol != null) {
                Text(
                    text = item.symbol,
                    style = PiTheme.text.monoSmall.copy(fontSize = 13.sp),
                    color = item.symbolColor ?: palette.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(PI_MENU_SYMBOL_GAP))
        Text(
            text = item.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.note != null) {
            Text(
                text = item.note,
                style = PiTheme.text.meta,
                color = palette.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * One row of a [PiMenu].
 *
 * [onSelect] is **last** so a caller can write `PiMenuItem("新建会话") { … }` — a
 * trailing lambda binds to the final parameter, and putting it anywhere else means
 * every one-line row has to name an argument it does not otherwise need. The rows
 * that carry a symbol or a note use named arguments and are unaffected by the order.
 */
data class PiMenuItem(
    val label: String,
    /** The trigger character or glyph, centred in its own 24 dp column. */
    val symbol: String? = null,
    /** The right-aligned explanation of *which* variant this is (`进上下文`). */
    val note: String? = null,
    /** The symbol's colour; the label is always the text colour. */
    val symbolColor: Color? = null,
    /** Overrides what a screen reader hears; defaults to `label，note`. */
    val a11yLabel: String? = null,
    /** Draw the 1 px rule above this row. */
    val dividerBefore: Boolean = false,
    val onSelect: () -> Unit,
)

/** Where a [PiMenu] opens relative to its anchor. */
enum class PiMenuPlacement { Above, Below }

/** v2's menu container: 240 wide, radius 10, 4 dp of vertical padding, 40 dp rows. */
private val PI_MENU_WIDTH = 240.dp
private val PI_MENU_RADIUS = 10.dp
private val PI_MENU_VERTICAL_PADDING = 4.dp
private val PI_MENU_ROW_HEIGHT = 40.dp
private val PI_MENU_ROW_PADDING = 12.dp
private val PI_MENU_SYMBOL_WIDTH = 24.dp
private val PI_MENU_SYMBOL_GAP = 10.dp
private val PI_MENU_DIVIDER_HEIGHT = 1.dp

/** The gap between the anchor and the menu's near edge. */
private val PI_MENU_ANCHOR_GAP = 8.dp

/** See [PiMenu]'s KDoc: the long AppBar menu scrolls inside this. */
private val PI_MENU_MAX_HEIGHT = 320.dp

/** `06 §2` 状态行: the context bar's segment count. */
private const val CONTEXT_SEGMENTS = 8

/** `06 §2` 状态行: each segment is `3×8` with a 1px gap and a 1px corner. */
private val CONTEXT_SEGMENT_WIDTH = 3.dp
private val CONTEXT_SEGMENT_HEIGHT = 8.dp

/**
 * The context bar: [CONTEXT_SEGMENTS] segments, `accent` up to the percentage and
 * `borderMuted` after it.
 *
 * The bar is a *reading* of the percentage printed beside it, never the only copy of
 * it (`06 §4`: 颜色不能是唯一信号), which is why the number and the bar are one
 * glance apart and the number carries the colour thresholds.
 */
@Composable
private fun ContextSegments(percent: Double, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    val filled = (CONTEXT_SEGMENTS * percent / 100.0).roundToInt().coerceIn(0, CONTEXT_SEGMENTS)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.hairline),
    ) {
        repeat(CONTEXT_SEGMENTS) { index ->
            Box(
                Modifier
                    .width(CONTEXT_SEGMENT_WIDTH)
                    .height(CONTEXT_SEGMENT_HEIGHT)
                    .clip(RoundedCornerShape(PiSpacing.hairline))
                    .background(if (index < filled) palette.accent else palette.borderMuted),
            )
        }
    }
}

/**
 * The summarization billing line pi prints for a compaction or a branch summary.
 *
 * **pi's text, verbatim** (`modes/interactive/interactive-mode.ts:3802-3812`):
 *
 * ```
 * const tokens = usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
 * const cost = usage.cost.total >= 0.01 ? ` (~$${usage.cost.total.toFixed(2)})` : "";
 * const label = notice.kind === "compaction" ? "Compaction" : "Branch summary";
 * new Text(theme.fg("warning", `${label}: ${formatTokens(tokens)} tokens billed${cost}`), 1, 0)
 * ```
 *
 * so the English labels and the `(~$0.03)` threshold are pi's, not an app
 * translation — a translated label would stop matching the line the desktop TUI
 * shows for the same session.
 *
 * Shown only when pi's `showCacheMissNotices` is on: pi guards the call with
 * `if (!this.settingsManager.getShowCacheMissNotices()) return;` (`:3803`), and
 * that setting defaults to `false` (`core/settings-manager.ts:120`, `:966`).
 */
@Composable
fun PiBilledCostLine(
    label: String,
    usage: TokenUsage?,
    modifier: Modifier = Modifier,
) {
    if (usage == null) return
    val tokens = (usage.input ?: 0L) + (usage.output ?: 0L) +
        (usage.cacheRead ?: 0L) + (usage.cacheWrite ?: 0L)
    val cost = usage.cost?.takeIf { it >= 0.01 }?.let {
        " (~$${String.format(Locale.US, "%.2f", it)})"
    }.orEmpty()
    Text(
        text = "$label: ${piFormatTokens(tokens)} tokens billed$cost",
        modifier = modifier,
        // pi's own English billing line, and the only place the app prints a token count
        // *and* a cost together: v2 draws it `mono t12` (`direction-b-v2.html:2597`,
        // 「Compaction: 42k tokens billed (~$0.03)」), `06 §3` 构件 8 calls it the
        // 「可选英文计费行」, and `05 §4.2` keeps token and 费用 on the machine face
        // （「耗时/退出码/行数/token/费用/秒数 六类改成 `numeric`」）. `meta` was the UI
        // face, so the two readings this line exists for were the one part of it not
        // set as machine language.
        style = PiTheme.text.monoSmall,
        // pi paints it with `theme.fg("warning", …)`.
        color = PiTheme.palette.warning,
    )
}

/**
 * One line, **two voices**: the app's own wording in the UI face and the machine value it
 * carries in the machine face.
 *
 * Rule #7 (`docs/pi-android-ui-spec.md` §1) splits text by *who wrote it*, and a line like
 * `审计日志：/data/user/0/app.pi/files/audit.log` has both authors on it: the four Chinese
 * characters are ours and the path is the engine's. One face for the whole line is wrong
 * whichever face is picked — the UI face puts a path in the reading font, the machine face
 * puts a Chinese label in the machine's. The frozen board draws this shape by nesting spans,
 * both with the machine half first and ours after:
 *
 *  - the workspace viewer's meta line is `<span className="mono">{path2}</span><span> · {size}
 *    · {time}</span>` (`design-demos/workspace-final.html:1081-1085`, handed to a `TopBar`
 *    whose meta slot is `t12 c-muted`);
 *  - a session row is `<span className="mono t12 c-muted">{row.time}</span>` beside
 *    `<span className="t12 c-muted">{row.sub}</span>` (`direction-b-v2.html:1817-1824`).
 *
 * [machine] takes the machine face; [prefix] and [suffix] and [style] are ours. There is no
 * "machine == empty" case: a line with nothing machine-produced on it is a plain `Text`, and
 * the callers branch rather than pass `""` (see `PiPackagesScreen.ResourceOriginText`).
 *
 * Every parameter is required rather than defaulted: `PiContextRing`'s `placeholderStyle` set
 * this project's precedent that it does not read `PiTheme.text` inside a default argument — it
 * compiles, but nothing else here does it.
 *
 * @param prefix our wording before the value (`审计日志：`, `保存为 `).
 * @param machine the machine-produced value: a path, an id, a pair of ids.
 * @param suffix our wording after it (`）`), or `""`.
 */
@Composable
fun PiMixedLine(
    prefix: String,
    machine: String,
    suffix: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val machineFamily = PiTheme.text.monoSmall.fontFamily
    // Built with the stdlib: `AnnotatedString.Builder` implements `Appendable`, so a chained
    // `append(…).append(…)` on it resolves through `Appendable.append(CharSequence?)` and
    // stops returning a `Builder` (`WorkspaceViewer.ViewerTopBar` hit the same thing).
    val value = machine
    val text = buildAnnotatedString {
        append(prefix)
        withStyle(SpanStyle(fontFamily = machineFamily)) { append(value) }
        append(suffix)
    }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Empty states never say "no data". They say what the screen is for and offer
 * the one action that fills it (docs/pi-android-ui-spec.md §7.3).
 *
 * The mark is v2's: either the π glyph (`06 §2` 空态「π 字形 34」，`brand-spec.md` §1
 * allows it as an empty-state identifier) or the screen's own icon. v2's prototype
 * keeps both spellings and chooses per screen — the chat's two engine states carry
 * the mark, because they are the app's own empty surface, while a search that
 * matched nothing keeps its icon.
 *
 * **Both branches are bare glyphs.** [markPi]'s branch is the 34 dp `muted` mark;
 * the other is the screen's icon at the board's 30 dp in the same `muted`. It used
 * to sit inside a 68 dp `surfaceContainerHigh` disc, which no v2 empty state has
 * (`direction-b-v2.html:821-823`; phone21/22 and phone28 are all a bare glyph over
 * the title), so the container is gone.
 *
 * [markPi] defaults to `true`, which is the chat's two engine states. The call
 * sites that draw a *finding* rather than the app's own surface — the session
 * list's two, the settings search and the session tree's three — pass
 * `markPi = false` and keep the icon v2 gives them.
 *
 * Geometry follows `06 §2` where the container allows it: the horizontal inset is
 * the board's 34, the title is the 17/600 title role, and the body is 14 with the
 * board's 1.6 leading, left-aligned under the centred title. The board's `86px`
 * *vertical* inset is not repeated: it is how the prototype centres this block in a
 * fixed-height frame, and in the app the host hands the state a `weight(1f)` box
 * that this column already centres in (`Arrangement.Center`).
 *
 * @param icon the screen's own icon; ignored while [markPi] is on.
 */
@Composable
fun PiEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    markPi: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = EMPTY_STATE_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (markPi) {
            PiMark(size = 34.dp, tint = PiTheme.palette.muted)
        } else {
            // A **bare** glyph, 30 and `muted`, exactly as the board draws this branch:
            // `EmptyState` (`direction-b-v2.html:821-823`) puts the screen's own icon in
            // a plain `inline-flex` coloured `--muted` and sizes the chat mark at 34
            // against the others' 30 (`:819`). The app used to wrap it in a 68 dp
            // `surfaceContainerHigh` disc, which is a container v2's empty states have
            // nowhere — see phone21/22 (the session list's two) and phone28 (the tree's),
            // all three a bare glyph over the title.
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(EMPTY_STATE_ICON),
                tint = PiTheme.palette.muted,
            )
        }
        Spacer(Modifier.height(EMPTY_STATE_TITLE_GAP))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            body,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = EMPTY_STATE_BODY_LEADING),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        if (action != null) {
            Spacer(Modifier.height(PiSpacing.unit))
            action()
        }
    }
}

/** `06 §2` 空态「padding:86px 34px」: the horizontal half, the only one Compose needs. */
private val EMPTY_STATE_INSET = 34.dp

/**
 * `06 §2` 空态「padding:86px 34px」的**纵向**那一半：空态块从内容区顶下移 86dp，而不是
 * 在内容区里垂直居中。
 *
 * [PiEmptyState] 自己只做横向的 34，并把纵向这半交给宿主居中——它的 KDoc 认为 86 只是
 * 原型在固定高度画框里居中用的。`shots-v2/phone21`（还没有会话）与 `phone28`（没有条目）
 * 说明不是：两张图里那块空态离筛选行的下沿都是 ~86px，下面留着几百像素的空白，是**顶对齐**
 * 而不是居中。这个包一层 `Box` 的构件把那半个取值还回来，**只给要照稿子的那一屏用**；
 * [PiEmptyState] 本身不动，别的屏的居中原样保留。
 *
 * @param icon the screen's own icon; ignored while [markPi] is on.
 */
@Composable
fun PiEmptyStateTopAnchored(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    markPi: Boolean = true,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PiEmptyState(
            icon = icon,
            title = title,
            body = body,
            modifier = Modifier.padding(top = EMPTY_STATE_TOP_INSET),
            markPi = markPi,
        )
    }
}

/** `06 §2` 空态「padding:86px 34px」: the vertical half [PiEmptyStateTopAnchored] restores. */
private val EMPTY_STATE_TOP_INSET = 86.dp

/** `06 §2` 空态: the mark-to-title gap is v2's `marginTop:12`. */
private val EMPTY_STATE_TITLE_GAP = 12.dp

/** `06 §2` 空态: a screen's own icon is the board's `s=30`, the π mark's `s=34` its sibling. */
private val EMPTY_STATE_ICON = 30.dp

/** `06 §2` 空态「正文 14/1.6」: 14 sp × 1.6, stated as leading rather than as a ratio. */
private val EMPTY_STATE_BODY_LEADING = 22.sp

/** Small uppercase-ish group label. Used by the settings stack and pickers. */
@Composable
fun PiSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(
            start = PiSpacing.pageHorizontal,
            end = PiSpacing.pageHorizontal,
            top = PiSpacing.unit,
            bottom = 6.dp,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One settings row. pi's semantics are carried by [supporting]; the trailing
 * slot shows the *current value* so a group screen is readable without opening
 * anything (docs/pi-android-ui-spec.md §6.2 — six row types, this is the
 * Switch/Value pair that covers most of them).
 */
@Composable
fun PiSwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A settings row whose value is chosen elsewhere. [value] is the summary.
 *
 * [onClick] is applied to the whole row rather than only the trailing text: a
 * row that advertises a value but ignores taps is worse than one that offers no
 * affordance at all.
 */
@Composable
fun PiValueRow(
    title: String,
    supporting: String?,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * When a written value starts to apply.
 *
 * `Reload` and `RestartEngine` are **different** things on purpose:
 *
 *  - [Reload] — the app (or pi's own resource loader) re-reads the value/file
 *    while the engine keeps running: the terminal key bar and the shortcuts are
 *    applied by this app the moment they are written, and pi re-reads a theme
 *    selection when its own UI asks for it.
 *  - [RestartEngine] — the value is read **only when `pi --mode rpc` starts**:
 *    pi's process configuration (`--offline`, `--system-prompt`,
 *    `PI_CACHE_RETENTION`) and the resources its loader caches at startup.
 *    Nothing short of a new process applies it, so the badge must not say
 *    "重载" — a word that promises the change is one tap away.
 *  - [AutoRestartEngine] — the same timing as [RestartEngine] (a new process is
 *    still what makes the value true) **but the app performs it itself** as part
 *    of the write: the row is `app.runtime.proroot`, whose toggle runs the probe
 *    and then restarts the engine onto the chosen runtime
 *    (`RuntimeSwitchAction`). The badge says 「自动重启引擎」 rather than
 *    「需重启引擎」 so it does not ask the user for a step that already happened —
 *    and the row is never left claiming a value the running engine does not have
 *    (a refused restart is rolled back). Use this kind only where the write path
 *    really does the restart; a row without that wiring must stay
 *    [RestartEngine], or its badge tells the user the change is applied while
 *    nothing has happened.
 *  - [RestartApp] — read while *this* app starts (the foreground-service switch).
 *  - [Immediate] — nothing to wait for.
 *
 * pi itself has only two of these timings in its TUI (a settings write is either
 * live or needs `/reload`); the split exists because the app must not promise
 * pi's `/reload` over RPC, where it does not exist.
 *
 * **The badge composable that used to render this enum is gone** (B7, the
 * 死代码 收尾项): `PiEffectiveBadge` had zero call sites — `PiSettingsRegistry`
 * carries [EffectiveKind] per setting and the settings package draws the 生效徽标
 * itself, in v2's badge shape — so it was deleted rather than left as a second,
 * unreachable spelling of the same pill. The enum stays: it is the registry's
 * type and the settings package references it, and this batch does not own that
 * package.
 *
 * Every value here has a reader in `ui/settings`: the badge label
 * (`PiSettingsStyle.PiSettingsEffectiveBadge`), the explanation dialog
 * (`PiSettingsEditors.PiEffectiveDialog`, which is a `when` — exhaustive on
 * purpose, so a new kind cannot be added without deciding what it means and
 * whether it offers an action), and the badge's action
 * (`SettingsGroupScreen`, where only [RestartEngine] routes to the manual
 * restart).
 */
enum class EffectiveKind { Immediate, Reload, RestartEngine, AutoRestartEngine, NewSession, RestartApp }
