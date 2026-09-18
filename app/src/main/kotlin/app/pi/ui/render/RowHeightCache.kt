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
