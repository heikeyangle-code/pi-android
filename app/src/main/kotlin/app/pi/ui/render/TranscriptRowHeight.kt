package app.pi.ui.render

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Frames for which a row that has been measured before is held at that height when it is
 * composed again.
 *
 * Three is a target, not a guarantee: the parse that collapses the row runs on
 * `Dispatchers.Default` and is a race against vsync (`docs/streaming-review.md` §2.4), so
 * on a loaded main thread a row can still be laid out blank. What the window buys is the
 * common case — the frame a destination is re-entered, where every visible markdown row's
 * state is rebuilt at once — and what bounds it is the reason it is a frame *count*
 * rather than "until the measurement settles": see [rememberedRowHeight].
 */
private const val REMEMBERED_ROW_HEIGHT_FRAMES = 3

/**
 * `Modifier` for one transcript row: the first frames of a **fresh composition** of a row
 * that has been measured before are laid out at the height it last had, instead of at the
 * zero height a new `MarkdownState` reports while it parses.
 *
 * ## Why a floor, and why it is time-bounded
 *
 * The height is used as `heightIn(min = …)` — a floor, not a fixed size — so a row whose
 * content has *grown* (a streamed answer, a wider screen after a rotation) is never
 * clipped: it measures its natural height, which is larger, and is taken at that height.
 *
 * The reason the floor is dropped after [REMEMBERED_ROW_HEIGHT_FRAMES] frames rather than
 * "whenever the measurement matches" is the other direction: a row that legitimately
 * **shrinks** — the user collapses every tool card, switches 思考 blocks off, changes the
 * font scale — would otherwise be held at its old, taller height for as long as that
 * composition lives. Bounded by frames, the cost of being wrong is at most three frames
 * (50 ms at 60 Hz) of a too-tall row, while the cost of *not* having the floor is the
 * full-height slide the reader sees on every re-entry.
 *
 * ## What it deliberately does not do
 *
 * It does not make a *never measured* row correct — a row that enters the viewport for the
 * first time (a freshly prepended 「加载更早」 batch) has no remembered height, so it is
 * laid out exactly as it is today. That failure is `docs/scroll-diagnosis.md` §1.2 P3, and
 * its fix is in the window arithmetic (keeping the anchor row still), not here.
 *
 * ## The two states it keeps
 *
 * `frames` counts *composition frames* after this row's own composition began, so it is
 * per-item and resets whenever the `LazyColumn` composes the item anew (a scroll past the
 * item, a destination switch). `remembered` is read once per fresh composition from the
 * process-wide [RowHeightCache], which is what survives the switch.
 *
 * @param rowKey `TranscriptItem.key` — the same identity the `LazyColumn` anchors on.
 */
@Composable
internal fun Modifier.rememberedRowHeight(rowKey: String): Modifier {
    val remembered = remember(rowKey) { RowHeightCache.shared.of(rowKey) }
    var frames by remember(rowKey) { mutableIntStateOf(0) }
    // A bounded wait, not an observation: nothing here is re-triggered by the measure that
    // follows it, and the effect ends on its own after the window.
    LaunchedEffect(rowKey) {
        while (frames < REMEMBERED_ROW_HEIGHT_FRAMES) {
            withFrameNanos { }
            frames++
        }
    }
    val density = LocalDensity.current
    val floor = if (remembered != null && frames < REMEMBERED_ROW_HEIGHT_FRAMES) {
        with(density) { remembered.toDp() }
    } else {
        null
    }
    return this
        .then(if (floor != null) Modifier.heightIn(min = floor) else Modifier)
        // Recorded on every measurement, including the frames the floor is holding: a row
        // this composition measured at the floor has the same height as the entry, so the
        // write is a no-op, and the frame that measures the real content overwrites it.
        .onSizeChanged { RowHeightCache.shared.record(rowKey, it.height) }
}
