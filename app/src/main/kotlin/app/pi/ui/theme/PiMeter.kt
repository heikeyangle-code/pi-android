package app.pi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 耗时刻度 — the one thing v2 takes from direction A (`04 §1.1`, `06 §2`).
 *
 * ## It answers
 *
 * 「132ms 和 6.4 秒，哪个更长？」 A tool footer already prints the number
 * (`成功 · 132ms · 13 行`, `blocks/ToolBlockChrome.kt:273-281`), but a number is only
 * comparable to another number you can see at the same time, and a transcript is
 * scrolled one card at a time. The tick is a second, *ordinal* channel: length
 * against a fixed 6–34 px range, split into one band per order of magnitude, so two
 * cards three screens apart still read as "small" and "large". It is a visual
 * **supplement** — `04 §1.1` is explicit that the text reading stays
 * (「刻度是它的视觉补充，不是替代」).
 *
 * ## The formula, and why it is logarithmic
 *
 * From the frozen board (`design-demos/direction-b-v2.html`, `function Tick`),
 * transcribed rather than re-derived:
 *
 * ```
 * band  = ms < 1000 ? 1 : ms < 10000 ? 2 : 3
 * total = clamp(round(log2(ms) * 3.2), 6, 34)          // px
 * segW  = max(2, round((total - (band - 1) * 2) / band))
 * ```
 *
 * The bands are separated by 2 px, so `total` includes the gaps; the widths below
 * are the board's own measured values, and the three helpers in this file reproduce
 * them exactly:
 *
 * | ms | total | bands | each |
 * |---|---|---|---|
 * | 40 | 17 | 1 | 17 |
 * | 96 | 21 | 1 | 21 |
 * | 132 | 23 | 1 | 23 |
 * | 214 | 25 | 1 | 25 |
 * | 6400 | 34 | 2 | 16 |
 * | 12300 | 34 | 3 | 10 |
 *
 * `log2` is written as `ln(x) / ln(2)` because Kotlin has no `log2`; the board's
 * `Math.log2` is the same double-precision function, so the `round` lands on the
 * same integer.
 *
 * ## When it is not shown
 *
 * [DurationMeter] draws nothing when its duration is null or ≤ 0 — `06 §2`「只在有
 * 真实 ms 的卡上显示」. In the app that means `read` / `write` / `edit` / `bash` /
 * `powershell` / the generic fallback card, all of which carry `ToolCall.elapsedMs`;
 * the workspace's running command line; and **not** the diff card (`06 §2`「diff 卡
 * 不显示」 — a diff has no runtime of its own, and it is already the densest row in
 * the stream), nor a pending tool that has not reported an elapsed time yet.
 *
 * ## Why the colour is a parameter and not a [StateTone]
 *
 * Because the caller already has the right token in hand: the tool chrome resolves
 * its state colour through `toolAccentColor(status, palette)`
 * (`blocks/ToolBlockChrome.kt:51-55`) for the border, the glyph and the stripe.
 * Passing [StateTone] here would add a second mapping that could disagree with the
 * first.
 */

/** `06 §2`: the shortest tick, for a call fast enough that `log2` underflows the range. */
internal const val PI_METER_MIN_PX: Int = 6

/** `06 §2`: the longest tick. 34 px keeps the widest meter narrower than the footer's text. */
internal const val PI_METER_MAX_PX: Int = 34

/** `06 §2`: the gap between two bands, included in the total length. */
internal const val PI_METER_GAP_PX: Int = 2

/** `06 §2`: a band never disappears — a three-band meter still shows three marks. */
internal const val PI_METER_MIN_SEGMENT_PX: Int = 2

/** The tick's stroke height. `06 §2`: 「高 1px 横线」 — one dp, like every other line. */
internal const val PI_METER_THICKNESS_DP: Int = 1

/**
 * The meter's total length in px, before it is converted to dp.
 *
 * Kept separate from the composable, and free of Compose types, so a bare-JVM
 * harness can pin the six measured rows of the table above — the same arrangement
 * `blocks/ToolOutputParse.kt` has with its own check (`05 §6.1`).
 */
fun piMeterTotalPx(elapsedMs: Long): Int {
    val ms = max(elapsedMs, 1L)
    val scaled = ln(ms.toDouble()) / ln(2.0) * 3.2
    return min(PI_METER_MAX_PX, max(PI_METER_MIN_PX, scaled.roundToLong().toInt()))
}

/**
 * How many bands the meter is split into: one under a second, two under ten, three
 * at or above ten — `06 §2`「<1s 一段、<10s 两段、≥10s 三段」.
 *
 * The band count is the *coarse* reading (an order of magnitude) and the total
 * length is the *fine* one; neither alone separates 132 ms from 6.4 s as reliably
 * as the pair does, which is why the board splits at exactly these thresholds.
 *
 * A pending tool reports its elapsed time as it runs, so `ms` grows across the two
 * thresholds while the card is on screen — the bands are the visible step, and they
 * change the reading from "under a second" to "seconds" to "tens of seconds"
 * without the number being read.
 */
fun piMeterBandCount(elapsedMs: Long): Int = when {
    elapsedMs < 1_000L -> 1
    elapsedMs < 10_000L -> 2
    else -> 3
}

/**
 * The width of one band, in px, given a total and a band count.
 *
 * `max(2, round((total - (bands - 1) * gap) / bands))` — the board's own expression,
 * so the three-band case cannot lose its gaps to rounding.
 */
fun piMeterSegmentPx(totalPx: Int, bands: Int): Int {
    val usable = totalPx - (bands - 1) * PI_METER_GAP_PX
    return max(PI_METER_MIN_SEGMENT_PX, (usable.toDouble() / bands).roundToInt())
}

/**
 * The meter itself: 1 dp tall, length ∝ `log2(ms)`, coloured [color].
 *
 * Draws nothing at all for a null or non-positive duration rather than falling back
 * to a minimum-length tick: an unknown duration and a very fast one must not look
 * alike (`06 §2`「只在有真实 ms 的卡上显示」), and an empty `Row` inside the footer's
 * arrangement would leave a gap the footer did not ask for.
 *
 * @param ms the tool's own measured duration — `ToolCall.elapsedMs` in the app, i.e.
 *   pi's `Elapsed`/`Took` figure, never a locally timed interval. Timing it here
 *   would measure the phone's recompositions rather than the tool.
 * @param color the state colour; see this file's KDoc for why it is a [Color].
 * @param modifier placed by the caller. The footer puts the meter at its **right
 *   end**, so the text keeps the leading position and the tick reads as a suffix.
 * @param contentDescription what a screen reader hears in place of the tick. The
 *   default is the raw millisecond count, because that is the only content this
 *   `Row` has; pass a sentence where the surrounding footer does not already spell
 *   the duration out (the pending shell card says 「已运行 12.3 秒」, so its meter can
 *   keep the default).
 */
@Composable
fun DurationMeter(
    ms: Long?,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (ms == null || ms <= 0L) return
    val total = piMeterTotalPx(ms)
    val bands = piMeterBandCount(ms)
    val segmentPx = piMeterSegmentPx(total, bands)
    val thickness = PI_METER_THICKNESS_DP.dp
    Row(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription ?: "$ms ms"
        },
        horizontalArrangement = Arrangement.spacedBy(PI_METER_GAP_PX.dp),
    ) {
        repeat(bands) {
            Box(
                Modifier
                    .width(segmentPx.dp)
                    .height(thickness)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
