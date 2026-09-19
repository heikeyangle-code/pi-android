package app.pi.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * A **safety net**, not the mechanism: frames for which a floored row is still held when
 * its content never reports a height of its own.
 *
 * The floor is normally dropped by the content's own first non-zero measurement — the
 * frame the markdown parse lands — which is what makes "hold it until the content is
 * ready" a latch rather than a race (see [rememberedRowHeight] for the race this
 * replaced). The cap exists only for the row whose content **never** measures: a hidden
 * thinking block, an empty text, a parse that ends in `State.Error` — anything whose
 * natural height really is zero. 12 frames (200 ms at 60 Hz) is far past any parse this
 * app can produce (measured on the renderer's own parser: 5.3 ms for a 20 KB tool card,
 * 10.5 ms for 50 KB) and bounds the artifact to a fifth of
 * a second instead of "for as long as the composition lives".
 */
private const val REMEMBERED_ROW_HEIGHT_FRAMES = 12

/**
 * `Modifier` for one transcript row: while this **fresh composition** of the row has not
 * yet measured its own content, the row is laid out at the height it last had, instead of
 * at the zero height a new `MarkdownState` reports while it parses.
 *
 * ## Why a floor at all
 *
 * `rememberMarkdownState` builds its state with a plain `remember` and a fresh state is
 * `State.Loading`, whose slot is an empty `Box` — zero height. A row therefore reaches
 * its real height one or more frames *after* its first composition, and every row below
 * it moves when it does. This modifier is what lets the first frame use the height the
 * row had before (`RowHeightCache`), so the geometry does not have to change at all.
 *
 * ## Why the floor is applied at **placement** time and not with `heightIn`
 *
 * The original form of this fix was `Modifier.heightIn(min = floor)`, held for a **fixed
 * three-frame window** (`frames < 3`) regardless of whether the parse had landed. Both
 * halves were wrong in the same direction:
 *
 *  - `heightIn` sets `enforceIncoming = true` (verified in `foundation-layout`'s bytecode:
 *    `SizeKt.heightIn-VpY3zN4` builds `SizeElement(…, enforceIncoming = true)`, and
 *    `SizeNode.measure` then measures the child with the coerced constraints), so the
 *    content is measured **with** `minHeight = floor`. The content's own height is then
 *    unobservable: a floored row reports `floor` whether it has parsed or not, and there
 *    is no way to tell "ready" from "still loading";
 *  - because of that the only possible expiry was the frame count — a race against
 *    `Dispatchers.Default`. Lose it (a parse slower than ~50 ms, which is what a loaded
 *    main thread means) and the floor disappears **while the content is still empty**: the
 *    row collapses to zero at frame 3, the list remeasures and everything below it jumps
 *    up, then the parse lands and it all jumps back down. That is *two* geometry changes
 *    where the fix was meant to remove one — the stutter this file exists to prevent,
 *    produced by the file itself.
 *
 * This version measures the content with the **original** constraints and returns
 * `max(contentHeight, floor)` as the row's size, i.e. a floor on the reported height
 * rather than on the measurement. The content therefore keeps reporting its true,
 * unfloored height to `onSizeChanged` — which sits *after* the layout modifier in the
 * chain, so it is dispatched by the inner coordinator and sees the content's own size
 * (`NodeCoordinator.onMeasured` reports `this.getMeasuredSize()` to the
 * `LayoutAwareModifierNode`s of its own segment) — and the floor is dropped on the frame
 * the content is actually ready, however long that takes.
 *
 * ## When the floor is released
 *
 * Three signals, in the order they normally arrive:
 *
 *  1. **`contentReady`** — the library's own `State.Success`, reported through
 *     `LocalPiMarkdownParsed` by `PiMarkdownText`. This is the mechanism: the floor covers
 *     exactly the window between "composed fresh" and "parsed", and this is that window's end
 *     as the library itself reports it. It is the one signal that is right *whoever won the
 *     race with `Dispatchers.Default`*, which is what removes the collapse-then-grow below
 *     from the mechanism rather than merely making it rarer.
 *  2. **a non-zero measurement** — any row without markdown (a notice, a date separator, an
 *     image grid) never signals, and does not need to: it lays itself out at its natural
 *     height on the first frame, and this releases the floor in the same frame's layout pass.
 *  3. the [REMEMBERED_ROW_HEIGHT_FRAMES] safety net — for content that never parses and never
 *     measures: a hidden thinking block, an empty text, a parse that ends in `State.Error`.
 *     It is a bound, not the mechanism; it fires at most once and only when the other two
 *     never did.
 *
 * So a re-composed row costs one extra recomposition (the write that releases the floor) and
 * one remeasure (the chain loses its floor, at a size the content has already proved equal),
 * and nothing per frame after that. The frame-count window this replaces wrote its counter on
 * every one of its three frames, and each write invalidated the row.
 *
 * The signal also means a row that legitimately **shrinks** (the user collapses a tool card,
 * thinking blocks are switched off, the font scale changes) is corrected on the next
 * measurement instead of being held for the rest of the composition — better than the window
 * it replaces, which held the stale height for three frames *and* then collapsed anyway if the
 * content was not ready.
 *
 * ## What it deliberately does not do
 *
 * It does not make a *never measured* row correct — a row that enters the viewport for the
 * first time (a freshly prepended 「加载更早」 batch) has no remembered height, so it is
 * laid out exactly as it is today, and no `settled` write is paid for it either (the effect
 * returns before its first frame). That failure is `docs/scroll-diagnosis.md` §1.2 P3, and
 * its fix is the eager-parse gate in `ChatScreen` (`PiMarkdownImmediate.kt`), not here.
 *
 * @param rowKey `TranscriptItem.key` — the same identity the `LazyColumn` anchors on.
 *   The returned `Modifier` is `remember`ed on `(rowKey, floor, density)` so an unchanged
 *   row hands the same chain instance to its block: the chain is a parameter of
 *   `BlockRenderer`, and while an equal chain would compare equal element-wise, holding
 *   one instance is what keeps a re-composition of the *list* from touching the row's
 *   layout at all.
 * @param contentReady the row's markdown has reached the library's `State.Success`
 *   (`LocalPiMarkdownParsed`, fired by `PiMarkdownText`). This is **the** release signal: the
 *   floor exists only for the window between "composed fresh" and "content parsed", and this
 *   is that window's end as the library itself reports it. Two backstops cover the cases it
 *   cannot: a row whose content measured a non-zero height (any row without markdown at all,
 *   which never signals), and [REMEMBERED_ROW_HEIGHT_FRAMES] for content that never measures
 *   anything at all.
 */
@Composable
internal fun Modifier.rememberedRowHeight(rowKey: String, contentReady: Boolean): Modifier {
    val remembered = remember(rowKey) { RowHeightCache.shared.of(rowKey) }
    // The measurement backstop: the content laid itself out at a real height, so whatever the
    // floor was holding up is no longer needed.
    var measured by remember(rowKey) { mutableStateOf(false) }
    val settled = contentReady || measured
    // The fallback expiry. It reads `settled` rather than counting frames as the mechanism, so
    // the normal path — the parse landing — ends the loop without a single further frame of
    // work; the write after the loop is a no-op when a latch already fired. Keyed on
    // `contentReady` as well, so the arrival of the library's signal cancels the wait instead
    // of leaving it to run out.
    LaunchedEffect(rowKey, contentReady) {
        if (remembered == null || contentReady) return@LaunchedEffect
        var frames = 0
        while (!measured && frames < REMEMBERED_ROW_HEIGHT_FRAMES) {
            withFrameNanos { }
            frames++
        }
        measured = true
    }
    val density = LocalDensity.current
    val floor = if (remembered != null && !settled) with(density) { remembered.toDp() } else null
    // The row's own modifier chain (the search highlight, when there is one) is applied
    // *outside* the remembered part: it legitimately changes when the highlight changes,
    // while the remembered part must keep one identity. `Modifier.then` returns `this` for
    // the empty modifier, so the common case (no highlight) hands the remembered instance
    // straight through.
    val heightModifier = remember(rowKey, floor, density) {
        val floorPx = floor?.let { with(density) { it.roundToPx() } }
        val base = if (floorPx != null && floorPx > 0) {
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, maxOf(placeable.height, floorPx)) {
                    placeable.placeRelative(0, 0)
                }
            }
        } else {
            Modifier
        }
        // Recorded on every positive measurement. While the floor is up the content may be
        // measured at zero, which `record` drops on purpose — storing it would make the
        // next composition's floor a no-op — and the frame the real content lands
        // overwrites the entry with the same number, so the write is a no-op in the common
        // case.
        base.onSizeChanged { size ->
            if (size.height > 0) {
                RowHeightCache.shared.record(rowKey, size.height)
                if (floorPx != null) measured = true
            }
        }
    }
    return this.then(heightModifier)
}
