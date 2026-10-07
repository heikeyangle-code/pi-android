package app.pi.ui.render

/**
 * A bounded, keyed record of how tall each rendered transcript row was the last time it
 * was measured — the piece of the markdown layer that "put the reader back where they
 * were" needs.
 *
 * ## The defect it stands behind
 *
 * `com.mikepenz:multiplatform-markdown-renderer`'s `MarkdownState` starts in
 * `State.Loading`, and the `loading` slot it draws is an **empty `Box`** (`Markdown.kt:112`,
 * `Box(it)`), i.e. zero height. `rememberMarkdownState` builds that state with a plain
 * `remember` (no saveable), so **every** time a row is composed for the first time — the
 * first frame after a destination switch, and every row that scrolls back into the
 * viewport — the row is measured at zero and only reaches its real height once the parse
 * (dispatched to `Dispatchers.Default`) lands. `retainState = true` (`PiMarkdown.kt`)
 * cannot help there: it keeps the *previous* content visible while a new input parses,
 * and a first composition has no previous content.
 *
 * The visible consequence is the one the user reports as 「切到别的屏，再切回来，为什么会跳
 * 一下」: the rows under the anchored row are laid out against a collapsed transcript and
 * then slide down as the parses land. `TranscriptRowHeights` is what lets the first frame
 * use the height the row had before, so the geometry does not have to change at all.
 *
 * ## The bound, and why it is not optional
 *
 * The cache is a fixed-capacity LRU (access-ordered `LinkedHashMap`), so it cannot grow
 * with the session, the transcript, or the number of rows the user has scrolled through.
 * Entries are dropped in least-recently-*used* order, and a dropped entry costs exactly
 * one row's worth of the old behaviour (one frame at zero height), never correctness.
 *
 * ## Threading
 *
 * Read from composition, written from `Modifier.onSizeChanged` — both on the UI thread
 * during a frame. It is deliberately **not** synchronised: a lock here would be paid on
 * every layout of every row to guard against a caller that cannot exist.
 *
 * This file is Android-free and Compose-free on purpose, so the LRU's own bound can be
 * pinned by `tools/run-app-pure-checks.sh` if a harness is registered for it (the
 * registration line is in the change's report — nothing here needs an emulator either
 * way).
 */
internal class RowHeightCache(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val heights = object : LinkedHashMap<String, Int>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > capacity
    }

    /** The height [key] was last measured at, or null when it has never been measured. */
    fun of(key: String): Int? = heights[key]

    /**
     * Remember [heightPx] for [key].
     *
     * A non-positive height is **dropped rather than recorded**: a row measured at zero
     * is the `State.Loading` frame this cache exists to work around, and storing it would
     * make the next composition's floor a no-op — i.e. it would silently undo the fix.
     */
    fun record(key: String, heightPx: Int) {
        if (heightPx > 0) heights[key] = heightPx
    }

    /** Entries currently held. For diagnostics and the harness's bound check. */
    fun size(): Int = heights.size

    fun clear() = heights.clear()

    companion object {
        /**
         * How many rows are remembered.
         *
         * The floor is only read for a row that is being composed fresh, and it is only
         * *used* for the first few frames of that composition (see `rememberedRowHeight`).
         * A phone screen shows on the order of ten rows and a prepended batch is fifty, so
         * this holds several screens' worth — more than any transition can consume in the
         * frames where the floor applies — while staying a few kilobytes.
         */
        const val DEFAULT_CAPACITY = 96

        /**
         * The process-wide cache the transcript rows use.
         *
         * Process-wide and not `remember`ed: the rows it has to answer for are exactly the
         * ones whose `remember`s are gone (the composition that owned them was disposed by
         * the destination switch), which is the whole point. Keyed by `TranscriptItem.key`,
         * which is stable for the life of a block and the same key the `LazyColumn` itself
         * anchors on.
         */
        val shared = RowHeightCache()
    }
}

/**
 * The **frame budget** for the synchronous markdown parse a row may pay for — the second
 * half of the bound `PiMarkdownImmediate.kt` describes, and the reason 「加载更早」's rows do
 * not all parse on the frame thread in one frame.
 *
 * ## What it is
 *
 * A token bucket: [budgetNanos] of synchronous-parse time is available and **one frame's
 * worth** is added back for every whole [periodNanos] that has passed (not continuously —
 * see [refill] for the microsecond case that would otherwise take the bound away). Within one
 * frame the balance only falls, so "the balance is positive" means "this frame has not spent
 * its parse budget yet". When it reaches zero the remaining rows of that frame take the
 * **asynchronous** path — the library's normal `Dispatchers.Default` parse — and may
 * therefore be laid out at zero height until it lands (see `rememberedRowHeight`).
 *
 * Why a token bucket and not a literal per-frame counter: composition runs inside a frame
 * but cannot *synchronously* ask the frame clock which frame that is (`withFrameNanos`
 * suspends). A bucket keyed on `System.nanoTime()` gives the same bound — capacity is one
 * frame's budget, the refill interval is one frame, and two calls inside the same frame are
 * ~0 ns apart and therefore cannot refill — while staying a pure function of the clock,
 * which is what lets a bare-JVM harness pin it (this file is Android-free and Compose-free
 * on purpose, and `tools/run-app-pure-checks.sh` already compiles it for the height-cache
 * checks).
 *
 * ## Why the bill is measured time and never characters
 *
 * The cost of one eager row is not a function of its character count alone: it is the
 * library's `parseBlocking()` **plus** composing the resulting node tree into the row, and
 * the caller can measure exactly that by timing the `Markdown(state = …)` call. So
 * [chargeNanos] is fed the measured elapsed time and nothing else — no per-kilobyte
 * estimate is charged and no size threshold ever decides a row's fate.
 *
 * The measurements that justify [DEFAULT_BUDGET_NANOS] (bare-JVM, this device class,
 * `parseBlocking()` on the renderer's own parser — `docs/scroll-perf-list.md` §1.1):
 * 5.2 KB → 3.31 ms, 20 KB → 5.27 ms, 50 KB → 10.46 ms, i.e. 0.2–0.6 ms/KB. The render half
 * of an eager row is **not** in those numbers and is charged as well, so on device the
 * budget is spent faster, never slower.
 *
 * ## The two deliberate properties
 *
 *  - **The debt is not carried across frames.** An oversized row (5.27 ms against a 4 ms
 *    budget) clamps the balance at zero instead of going negative, so the overshoot costs
 *    the frame it happened in and the next frame starts full again. A negative balance
 *    would suspend eager parsing for several frames after one big card, turning one slow
 *    frame into a run of rows that all arrive at zero height.
 *  - **The first row of a frame is always allowed.** The first call of a frame refills to
 *    capacity, so the common case — one row entering the viewport — never changes
 *    behaviour; only a *crowd* of expensive rows in one frame is thinned out.
 *
 * @param budgetNanos how much synchronous parse time one frame may spend.
 * @param periodNanos the frame the budget belongs to (60 Hz).
 */
internal class MarkdownParseBudget(
    private val budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    private val periodNanos: Long = DEFAULT_PERIOD_NANOS,
) {

    init {
        require(budgetNanos > 0) { "budget must be positive, was $budgetNanos" }
        require(periodNanos > 0) { "period must be positive, was $periodNanos" }
    }

    private var availableNanos = budgetNanos
    private var lastNanos = Long.MIN_VALUE

    /** Whether this frame may still afford one more synchronous parse. */
    fun allow(nowNanos: Long): Boolean {
        refill(nowNanos)
        return availableNanos > 0
    }

    /**
     * Bill [costNanos] of measured synchronous-parse time.
     *
     * A non-positive cost is ignored, and the balance never goes below zero — see "the debt
     * is not carried across frames" above.
     */
    fun chargeNanos(nowNanos: Long, costNanos: Long) {
        refill(nowNanos)
        if (costNanos <= 0) return
        availableNanos = (availableNanos - costNanos).coerceAtLeast(0L)
    }

    /** The balance after refilling for the time passed. For diagnostics and the harness. */
    fun availableNanos(nowNanos: Long): Long {
        refill(nowNanos)
        return availableNanos
    }

    fun reset() {
        availableNanos = budgetNanos
        lastNanos = Long.MIN_VALUE
    }

    private fun refill(nowNanos: Long) {
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = nowNanos
            availableNanos = budgetNanos
            return
        }
        val elapsed = nowNanos - lastNanos
        if (elapsed < periodNanos) return
        // Refilled in **whole frames**, not continuously, and the timestamp advances by those
        // whole frames only: a call a microsecond after the budget ran out must still see an
        // empty balance, or every later row of that same frame would find a few hundred
        // nanoseconds of "budget" and be allowed eagerly — the per-frame bound would not
        // exist. Only a real frame boundary (or several) refills.
        val frames = elapsed / periodNanos
        lastNanos += frames * periodNanos
        availableNanos = (availableNanos + frames * budgetNanos).coerceAtMost(budgetNanos)
    }

    companion object {
        /**
         * One frame's share of synchronous markdown parsing: **4 ms**.
         *
         * The frame is 16.7 ms at 60 Hz and a scrolling transcript already spends several of
         * those on composition, layout and draw, so a quarter of the frame is the most that
         * can be handed to parses without missing the deadline on its own. Against the
         * measured table that is one 20 KB card (5.27 ms — the one allowed overshoot), or one
         * 5 KB prose row (3.31 ms; two do not fit), or a handful of the sub-kilobyte rows the
         * transcript mostly holds. The alternative — a per-row character cap — decides a
         * row's *fate* from a guess about its cost; this decides it from what the frame has
         * actually spent.
         */
        const val DEFAULT_BUDGET_NANOS = 4_000_000L

        /** One 60 Hz frame: the interval the budget refills over. */
        const val DEFAULT_PERIOD_NANOS = 16_666_667L
    }
}

/**
 * The budget every transcript row shares.
 *
 * Process-wide and not `remember`ed, like [RowHeightCache.shared] and for a stronger
 * reason: the resource it protects is the **frame thread**, which is shared by every row,
 * every list and every screen. The gate reads it in `ChatScreen`'s item lambda and the
 * measurement that bills it is taken in `PiMarkdownText`, so both ends must be looking at
 * the same object.
 */
internal val piMarkdownParseBudget = MarkdownParseBudget()
