package app.pi.ui.chat

/**
 * The transcript's "follow the newest row" state machine.
 *
 * **Why it is not in `ChatScreen.kt`.** The behaviour it encodes ("am I following the
 * tail, and if so where do I have to scroll to *be* at the tail") was a handful of
 * `LaunchedEffect`s reading `LazyListState` inline, and every one of the defects this
 * machine replaces came from a rule that could not be stated, let alone tested,
 * without a device (see `docs/streaming-review.md` §2.1). Nothing here touches
 * Compose, Android or coroutines: the Compose side reads `LazyListState`, fills a
 * [TailViewport], and applies the [TailDecision] it gets back. That is what lets
 * `tools/run-app-pure-checks.sh` pin the rules on a bare JVM.
 *
 * ## What pi does, because the rules below are pi's, not ours
 *
 * pi's TUI transcript is a `ScrollView` opened with `follow: "end"`
 * (`packages/coding-agent/src/modes/interactive/chat-viewport.ts:22-25`, and pi's
 * implicit alt-screen document at `packages/tui/src/tui-alt-screen.ts:262`). Its
 * rule, in `packages/tui/src/components/scroll-view.ts`, is:
 *
 *  - `scrollTo`/`scrollBy` set `followingEnd = followEnd && next === maxScrollTop`
 *    (`:127-157`, `:163-186`) — i.e. **any** position short of the very end pauses
 *    the follow, and **returning to the very end resumes it**, no matter how the
 *    user got there;
 *  - `scrollTo(..., { disableFollow: true })` pauses *and* keeps it paused even when
 *    the target is the end (`:19`, `:131`) — the flag a programmatic navigation
 *    (search reveal, `packages/tui/src/tui-alt-screen.ts:636`) uses so that jumping
 *    to a row at the bottom does not silently re-arm the follow;
 *  - `updateLayout(contentHeight, viewportHeight)` re-pins to the end when the
 *    follow is armed (`:189-201`) — so **content growth follows, and content growth
 *    never pauses the follow**. There is no code path in pi where a taller
 *    transcript turns the follow off; only a scroll position does;
 *  - the "jump to the latest" affordance is drawn only while the follow is paused
 *    (`packages/tui/src/tui-alt-screen.ts:1620`: `!scrollView.followEnd ||
 *    scrollView.isFollowingEnd` returns early) and clicking it calls
 *    `scrollToBottom()` → `scrollToEnd()` (`:1015-1021`, `:477-480`) — a **direct**
 *    position change, no animation.
 *
 * The App used to break the third bullet — and had no equivalent of the second at
 * all: it paused the follow on a single frame in which a two-row "is it at the
 * bottom" approximation was false (which content growth and layout churn produce all
 * the time) and then never re-armed, and a search hit in the last row re-armed it
 * instead of staying put. That is the reported "流式时不会自动触底跟随"; see
 * [onSnapshot] for the replacement rules.
 *
 * ## The three signals, and why each one is the one that can be trusted
 *
 * [TailViewport] carries what `LazyListState` reports. The interesting entries:
 *
 *  - [TailViewport.atBottom] is `!LazyListState.canScrollForward`. Compose computes
 *    `canScrollForward = index < itemsCount || currentMainAxisOffset > maxOffset`
 *    (`compose/foundation/.../LazyListMeasure.kt:441`), i.e. false exactly when the
 *    last row is fully visible — including the tail of a row **taller than the
 *    viewport**. The old check (`lastVisibleIndex >= totalItemsCount - 2`) called a
 *    half-scrolled 40-line answer "at the bottom" and then refused to scroll, which
 *    is the other half of the same report.
 *  - [TailViewport.isScrollInProgress] is only true for a real scroll session (a
 *    drag, a fling, or an animation). `requestScrollToItem` — the follow's own
 *    primitive — deliberately does not start one
 *    (`compose/foundation/.../LazyListState.kt:456-475`), so it can never be
 *    mistaken for the user's hand.
 *  - the anchor ([TailAnchor] = `firstVisibleItemIndex` +
 *    `firstVisibleItemScrollOffset`) only moves *backwards* (towards older rows)
 *    when the viewport does. Content growth at the tail, a `renderWindow` prepend
 *    (which shifts the anchor forward, and is anchored by key anyway), a session
 *    switch and an inset change all leave the anchor alone or move it forward.
 *
 * A pause therefore requires a **backwards anchor movement observed during a scroll
 * session**: a fact, not a snapshot of "where the list is this frame".
 *
 * @param initiallyFollowing false when a restored state was paused (see
 *   [fromSavedState] and [savedState]).
 */
internal class TailFollow(initiallyFollowing: Boolean = true) {

    /** True while the transcript should stay pinned to its newest row. */
    var following: Boolean = initiallyFollowing
        private set

    /**
     * Rows appended since the follow was paused (`0` while following).
     *
     * Counted from the *whole* transcript, not from the rendered window: a row the
     * window has not materialised yet is still new content the user has not seen.
     * The number is what spec §4.5's 「↓ 回到最新（N）」 shows; pi's own indicator
     * prints no count (`modes/interactive/tui-renderer.ts:29-33`), so this is an App
     * addition, not parity.
     */
    var unseenRows: Int = 0
        private set

    /**
     * Set by [pause] while the viewport is at the end, and cleared by the user's own
     * scrolling (or [reArm]).
     *
     * pi's `followSuppressedAtEnd` (`scroll-view.ts:35`, `:131`, `:196`) with the
     * same job: a navigation that lands on the last row — a search hit in the last
     * block, say — must not re-arm the follow just because the end is where it
     * stopped. Only the user's hand (or the explicit affordance) re-arms that.
     */
    private var pausedByNavigation = false

    private var previousRows: Int = -1
    private var previousAnchor: TailAnchor? = null

    /**
     * The pin the *current position* wants — whether or not the last call handed it
     * out (see the repeat guard in [onSnapshot]).
     *
     * Only the pin is remembered, not the geometry it came from: an identical pin is
     * the same request, and remembering the geometry as well is what let an
     * unsatisfiable one be re-issued every frame.
     */
    private var lastPin: TailPin? = null

    /**
     * Re-arm the follow. The "back to latest" affordance and the send button both
     * mean this; the caller is expected to poke its snapshot flow afterwards so a
     * pin is issued even if no layout changed (see `ChatScreen`'s `tailPoke`).
     */
    fun reArm() {
        following = true
        unseenRows = 0
        pausedByNavigation = false
        // An explicit "go to the newest" (the affordance, the send button) must not be
        // suppressed by the repeat guard: the remembered pin describes what the
        // *previous* position wanted, and the user has just asked for the tail.
        lastPin = null
    }

    /**
     * Pause the follow for a programmatic navigation: a search hit, 「跳到上一条提问」
     * /「下一条」, any "reveal this row for me" request. pi's `disableFollow`
     * (`scroll-view.ts:131`, used by the search reveal at
     * `tui-alt-screen.ts:636`), including the "stays paused at the end" half.
     *
     * The caller owns the *pixels*; this owns the *flag*. In particular a caller must
     * never pause from its own reading of "is a scroll in flight": an
     * `isScrollInProgress` that a layout pass started (an optimistic row landing, an
     * inset change, a fling settling) is not the user's hand, and once this method has
     * set `pausedByNavigation` rule 3 below cannot undo it — which is the shape of the
     * reported 「明明就在屏幕底部给它发消息，发完了它就不跟随」. Only the anchor
     * movement [onSnapshot] can see is evidence of a hand.
     */
    fun pause() {
        following = false
        unseenRows = 0
        pausedByNavigation = true
        // Symmetry with [reArm]: the remembered pin describes the position the machine
        // was following, and a paused machine must not let it suppress the pin of the
        // position the user comes back to. Harmless today — a paused machine issues no
        // pin at all, which clears the memory on the next observation — and it keeps
        // "a pause forgets the tail" true without relying on that.
        lastPin = null
    }

    /**
     * Fold one observation of the list, and answer what the caller should do about it.
     *
     * The order of the rules is the whole argument:
     *
     *  1. **A rebuilt transcript re-arms.** Fewer rows than last time cannot happen by
     *     streaming (the reducer only appends, and `renderWindow` never drops rows
     *     from the *transcript*), so it means a different session, a fork, a clone or
     *     a replay. A new conversation opens at its newest row (spec §4.5), and the
     *     `unseenRows` count belongs to the old one.
     *  2. **A user gesture decides, and only a user gesture.** A backwards anchor
     *     movement during a scroll session pauses the follow — regardless of how far
     *     it went, because "the user dragged towards history" is the fact the design
     *     is built on, and a 1-screen threshold (spec §4.5's 「上滑 > 1 屏」) would
     *     mean the next token yanks a user who had visibly started scrolling back to
     *     the bottom, which is the very defect that paragraph exists to forbid. At
     *     the end, a gesture re-arms instead: pi's `scrollTo`/`scrollBy` rule above.
     *  3. **Otherwise, being at the end re-arms** — but only if the pause was not a
     *     navigation's (the `pausedByNavigation` guard). This is what makes
     *     "new row arrives while the user happens to be at the bottom" follow, and
     *     what makes "the user scrolled back down" resume, both without a gesture
     *     being observed in the same frame.
     *  4. **Nothing else changes the state.** Content growth, a taller streaming row,
     *     a window prepend or a shrink, an inset change: none of them can pause the
     *     follow, which is the regression `docs/streaming-review.md` §2.1 records.
     *
     * Then the pin: while following, not mid-gesture and not already at the end, the
     * returned [TailDecision.pin] is the argument pair for
     * `LazyListState.requestScrollToItem` ([TailPin]). It is `null` in every other
     * case, so a paused transcript is never scrolled by the streaming code.
     *
     * ## The caller's obligation, because rules 2 and 3 are observations
     *
     * Rules 2 and 3 are facts about a *sequence* of snapshots, so a caller that stops
     * calling this method while paused freezes the machine: the pause can then only be
     * undone by an explicit [reArm]. A paused machine must therefore still be fed —
     * every publication, plus both edges of a scroll session and the moment the
     * viewport reaches the end. That is what makes "the user scrolled back down"
     * resume on its own, and it is why `ChatScreen`'s follow effect has no
     * `if (!following) return` and is not keyed on `following`.
     */
    fun onSnapshot(snapshot: TailSnapshot): TailDecision {
        val viewport = snapshot.viewport
        val rows = snapshot.transcriptRows

        if (previousRows >= 0 && rows < previousRows) {
            // Rule 1: a rebuilt transcript.
            reArm()
        }

        val anchor = viewport.anchor
        val previous = previousAnchor
        val movedBackwards = previous != null && anchor.isBefore(previous)
        val gesture = viewport.isScrollInProgress && anchor != previous

        // Rule 2, then rule 3. `atBottom` is checked first so that a fling that ends
        // at the end resumes the follow rather than pausing it for the frames it
        // spent travelling there.
        if (viewport.atBottom) {
            if (!pausedByNavigation || gesture) {
                following = true
                pausedByNavigation = false
            }
        } else if (gesture) {
            pausedByNavigation = false
            if (movedBackwards) following = false
        }

        unseenRows = when {
            following -> 0
            // A restored machine with no baseline (`previousRows < 0`, i.e. a caller
            // that built a paused machine by hand) keeps the count it came back with:
            // resetting it would lose the pause's whole point, and treating `-1` as a
            // real count would invent `rows + 1` new ones.
            previousRows < 0 -> unseenRows
            else -> unseenRows + (rows - previousRows).coerceAtLeast(0)
        }

        val pin = when {
            !following -> null
            viewport.isScrollInProgress -> null
            viewport.totalItems <= 0 -> null
            else -> viewport.pinToTail()
        }
        // Never re-issue a pin. `requestScrollToItem` schedules a remeasure even when
        // the position it is given is the one already in place
        // (`compose/foundation/.../LazyListState.kt:469-475`), and that remeasure
        // writes a new `LazyListLayoutInfo`, which re-emits the `snapshotFlow` that
        // feeds this machine — so repeating a pin is a per-frame remeasure loop.
        //
        // Compare the **pin**, not the geometry it came from. Requiring an identical
        // viewport too defeated this guard in the one case it exists for: when the
        // tail row is taller than the viewport, `pinToTail()` returns the fixed
        // `TailPin(tail, 0)` — a value that does not depend on the offsets — and the
        // list cannot satisfy it (the requested position is clamped). The remeasure
        // that `requestScrollToItem` schedules therefore produces a *different*
        // geometry with the **same** pin, the old condition let it through, and the
        // follow re-issued the unsatisfiable request on every frame: a remeasure loop
        // with nothing left to fix, which is the reported "卡住不动 / 没有响应". An
        // identical pin is the same request by definition, so skipping it cannot lose
        // a scroll — a geometry that really moved asks for a different pin (see the
        // harness's "new geometry is pinned again").
        val repeated = pin != null && pin == lastPin
        val issued = if (repeated) null else pin
        // The memory follows what the *position* wants, not what was sent this time: a
        // skip means "already asked for", and forgetting it would let the next
        // identical emission ask again - which is the loop this exists to stop.
        lastPin = pin

        previousRows = rows
        previousAnchor = anchor
        return TailDecision(following = following, unseenRows = unseenRows, pin = issued)
    }

    /**
     * The state to persist across a configuration change.
     *
     * Deliberately not the anchors: they describe one layout pass. Persisting them
     * would let a restored machine compare a rotation's fresh layout against a
     * pre-rotation position and read a 0→1 item move as a user gesture. The row count
     * *is* persisted: it is the baseline [unseenRows] is counted from, so the rows
     * that arrived while the process was being recreated are neither lost nor
     * invented.
     */
    fun savedState(): List<Int> = listOf(if (following) 1 else 0, unseenRows, previousRows)

    companion object {
        /**
         * `rememberSaveable`'s saver. Rotation must not resurrect a paused follow as
         * "keep following" — `LazyListState` restores its own scroll position
         * (`LazyListState.Saver`), so a machine that came back armed would yank a
         * reader who had scrolled up into history back to the bottom on the next
         * token.
         */
        fun fromSavedState(saved: List<Int>): TailFollow? {
            val following = saved.getOrNull(0) ?: return null
            val unseen = saved.getOrNull(1) ?: return null
            return TailFollow(initiallyFollowing = following != 0).also {
                // Not `reArm()`: the restored count belongs to the pause the user
                // came back to.
                if (following == 0) it.unseenRows = unseen.coerceAtLeast(0)
                it.previousRows = saved.getOrNull(2) ?: -1
            }
        }
    }
}

/** `LazyListState.firstVisibleItemIndex` + `firstVisibleItemScrollOffset`. */
internal data class TailAnchor(val itemIndex: Int, val itemOffsetPx: Int) {
    /**
     * The viewport moved towards older rows.
     *
     * A smaller index is unambiguous. An unchanged index with a smaller offset is
     * the same movement inside one row, and it is how a tall streaming row behaves
     * once its start has passed the top of the viewport.
     */
    fun isBefore(other: TailAnchor): Boolean =
        itemIndex < other.itemIndex || (itemIndex == other.itemIndex && itemOffsetPx < other.itemOffsetPx)
}

/**
 * What `LazyListState` reports, in a form that has never seen Compose.
 *
 * @param totalItems `LazyListLayoutInfo.totalItemsCount` — the `LazyColumn`'s items,
 *   which includes the「加载更早的 N 条」row when the transcript window is smaller
 *   than the session. The machine's indices are therefore in the list's own space,
 *   which is what `requestScrollToItem` takes; the old code's `headerRows`
 *   arithmetic disappears with it.
 * @param viewportEndOffsetPx `LazyListLayoutInfo.viewportEndOffset`. Item offsets
 *   are relative to the viewport, and this is the line the last row's bottom has to
 *   reach for the list to be at its end (`LazyListLayoutInfo.kt`: "the size of the
 *   lazy list layout minus beforeContentPadding"). With the transcript's symmetric
 *   vertical content padding the two agree exactly; the pin below never depends on
 *   that agreement, because reaching the end is detected by [atBottom].
 * @param isScrollInProgress `LazyListState.isScrollInProgress`.
 * @param atBottom `!LazyListState.canScrollForward`.
 */
internal data class TailViewport(
    val totalItems: Int,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int,
    val lastVisibleIndex: Int,
    val lastVisibleOffsetPx: Int,
    val lastVisibleSizePx: Int,
    val viewportEndOffsetPx: Int,
    val isScrollInProgress: Boolean,
    val atBottom: Boolean,
) {
    val anchor: TailAnchor get() = TailAnchor(firstVisibleIndex, firstVisibleOffsetPx)

    /** The last row — the one a follow has to keep visible. */
    val tailIndex: Int get() = totalItems - 1

    /**
     * How to reach the tail with `requestScrollToItem`, or null when the tail's
     * bottom is already inside the viewport.
     *
     * **The bug this exists for.** `scrollToItem(totalItems - 1)` puts the last
     * row's *top* at the top of the viewport. When that row is taller than the
     * viewport — a long streaming answer, which is the normal case — the newest text
     * (its bottom) is then one viewport *below* the fold, so "follow the tail"
     * scrolled to a place where the tail is invisible. Two cases:
     *
     *  - the tail row is visible: move the viewport down by exactly the number of
     *    pixels of it that are below the fold, keeping the first visible row. The
     *    measure pass clamps an overshoot at the content end by scrolling back
     *    (`LazyListMeasure.kt:246-269`), so this lands at the end, never past it;
     *  - the tail row is not visible at all (its whole body is below the fold): ask
     *    for its top, [TailPin.offsetPx] 0. The next layout reports its measured
     *    size, so the *next* snapshot aligns its bottom. One extra frame, only ever
     *    for a row that has just appeared or just outgrown the viewport.
     */
    fun pinToTail(): TailPin? {
        if (totalItems <= 0) return null
        val tail = tailIndex
        if (lastVisibleIndex < tail) return TailPin(index = tail, offsetPx = 0)
        if (lastVisibleIndex > tail) return null
        val hidden = lastVisibleOffsetPx + lastVisibleSizePx - viewportEndOffsetPx
        if (hidden <= 0) return null
        return TailPin(index = firstVisibleIndex, offsetPx = firstVisibleOffsetPx + hidden)
    }
}

/**
 * The argument pair for `LazyListState.requestScrollToItem(index, offsetPx)`.
 *
 * `offsetPx` is positive-forward, as that API documents it (positive "will scroll
 * the item further upward"), which is why the pin's arithmetic is a sum of pixels
 * and never needs the row heights.
 */
internal data class TailPin(val index: Int, val offsetPx: Int)

/** One observation of the list. See [TailFollow.onSnapshot]. */
internal data class TailSnapshot(
    /**
     * Rows in the *whole* transcript (`ChatScreen`'s `state.transcript.size`), not
     * the rendered window. Drives [TailFollow.unseenRows] and the rebuilt-transcript
     * rule.
     */
    val transcriptRows: Int,
    val viewport: TailViewport,
    /**
     * A caller-owned counter with no meaning to the machine: it is part of the key
     * of the effect that feeds this machine, so bumping it makes the machine
     * re-decide even when nothing about the transcript changed. `ChatScreen` bumps it
     * when the "back to latest" affordance is tapped or a message is sent, which is
     * the only way a pin can be issued between two tokens.
     */
    val poke: Long = 0L,
)

/**
 * What [TailFollow.onSnapshot] decided.
 *
 * @param pin non-null exactly when the caller should call
 *   `LazyListState.requestScrollToItem(pin.index, pin.offsetPx)`.
 */
internal data class TailDecision(
    val following: Boolean,
    val unseenRows: Int,
    val pin: TailPin?,
)

/**
 * Whether "load earlier rows" may run on this frame.
 *
 * ## The defect this rule exists for
 *
 * The transcript renders a **window** of its last N rows (`ChatScreen`'s
 * `renderWindow` / `hiddenCount`) and grows it by one step whenever the viewport
 * reaches the window's top. Growing it *prepends* rows.
 *
 * The old rule was "the window's first item is visible while the user is at the
 * top", with no condition on a scroll being in flight — and that made a **fling
 * unbounded**. A fling is momentum that keeps applying after the finger leaves; each
 * time it reached the top of the loaded rows, another step was prepended *at the
 * head*, i.e. the list grew in the direction the fling was travelling, so the fling
 * could never reach an end. On a long session the loop ran to exhaustion: one firm
 * flick toward history landed on the very first row of the conversation. That is the
 * user's 「不管聊天有多长，我往下稍微用力划一下，它直接回到聊天最顶部」 — and 「往下滑就
 * 很正常」 for the same reason: a slow drag is released long before the loop can
 * compound, a flick is not.
 *
 * [isScrollInProgress] is the whole fix: a batch is loaded when the gesture is over,
 * so a flick travels through the rows it was given and stops. Every input here is a
 * plain value the Compose side reads off `LazyListState` — nothing in this file
 * touches Compose — which is what lets `TailFollowCheck` pin the rule on a bare JVM.
 *
 * @param atWindowTop the window's first item is the viewport's first item.
 * @param armed true once the user has been away from the window top, so a viewport
 *   that merely *starts* there does not load on the first frame.
 * @param hiddenRows rows the window is holding back (`hiddenCount`).
 */
internal fun mayLoadEarlier(
    atWindowTop: Boolean,
    armed: Boolean,
    hiddenRows: Int,
    isScrollInProgress: Boolean,
): Boolean = atWindowTop && armed && hiddenRows > 0 && !isScrollInProgress

/**
 * The index the list has to be moved to so that prepending [prependedRows] rows at
 * its head does **not** move the rows the user is reading — the second half of the
 * same defect.
 *
 * A `LazyColumn` anchors its scroll position on the **key of its first visible
 * item**, and this list's head row is the synthetic "load earlier" row: it keeps the
 * same key (`transcript-earlier`) at index 0 for as long as anything is hidden. So
 * when the first visible item is that row, the anchoring sees the same key at the
 * same index and the viewport stays at index 0 while the *content* under it changes
 * by the whole prepended batch — the list appears to jump towards the beginning even
 * though the fling has stopped. When the first visible item is a real transcript row
 * the anchoring is correct, which is why only the window's head needed this.
 *
 * The arithmetic, in one line: a row's index is `headerRows + its content index`, and
 * prepending shifts every content index by [prependedRows]. A first visible item that
 * *is* the header (`firstVisibleIndex - headerRowsBefore` negative) has no content row
 * to preserve, so the batch's first row becomes the anchor instead — that is the
 * `coerceAtLeast(0)`, and it is what makes "read from the oldest loaded row" the
 * landing point rather than "stay on the sentinel".
 *
 * @param firstVisibleIndex `LazyListState.firstVisibleItemIndex` before the prepend.
 * @param headerRowsBefore/After whether the "load earlier" row is present before and
 *   after (it disappears once nothing is hidden, shifting everything by one).
 */
internal fun prependAnchoredIndex(
    firstVisibleIndex: Int,
    prependedRows: Int,
    headerRowsBefore: Int,
    headerRowsAfter: Int,
): Int {
    val contentIndex = (firstVisibleIndex - headerRowsBefore).coerceAtLeast(0)
    return headerRowsAfter + contentIndex + prependedRows
}
