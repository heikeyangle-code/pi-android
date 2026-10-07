package app.pi.ui.blocks

/**
 * The transcript's expand/collapse overrides, remembered **by the row's own key** so they
 * outlive the composition that drew the row.
 *
 * ## The defect it stands behind
 *
 * Every block with a disclosure held its state in
 * `var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }`. The
 * `defaultExpanded` key is deliberate — it is how the top bar's expand/collapse-all switch
 * (pi's `app.tools.expand`, `setToolsExpanded` in `interactive-mode.ts`) reaches every row —
 * but a `remember`'s value dies with the composition that owns it, and a `LazyColumn` disposes
 * the composition of any row that leaves its window: scrolled far enough, or pushed out by a
 * layout shift (a card's own collapse changing the content height is one). The row is then
 * composed again and re-initialised from `defaultExpanded`, so **a card the reader had opened
 * comes back collapsed**. The reader's words: 「几张卡片都展开着，我只收起一张，有时候别的也跟着
 * 收起来了」.
 *
 * ## What one entry holds, and why the default is part of it
 *
 * An entry is keyed by the row's stable identity (`TranscriptItem.key` — the same key the
 * `LazyColumn` anchors on and `RowHeightCache` records heights under), and it stores the
 * `defaultExpanded` the value was chosen under, because the *other* half of the old
 * `remember(defaultExpanded)` has to be kept: a **change** of default still discards the state.
 *
 *  - [remembered] answers `null` when the key has no entry *or* when its entry was written
 *    under another default — and in the second case it **drops** that entry. The caller then
 *    falls back to the default it was handed *now*, which is what `remember(defaultExpanded)`
 *    produced on the frame the switch moved. Dropping rather than shadowing is what keeps a
 *    manual value from coming back if the switch is later moved back to the value it was made
 *    under.
 *  - [record] is the reader's own toggle, stored under the default in force at that moment.
 *
 * ## The bound, and why it is not optional
 *
 * A fixed-capacity LRU (access-ordered `LinkedHashMap`), the same shape as `RowHeightCache`'s:
 * the table cannot grow with the session. A row enters it on its first toggle and stays until
 * [capacity] other keys have been read or written more recently; the evicted row simply goes
 * back to following its default, which is the behaviour every row had before this table
 * existed. Nothing but the reader's own taps ever writes an entry, so this is a ceiling on how
 * many *opened* cards one session remembers — not something a long transcript can reach by
 * scrolling.
 *
 * ## Threading
 *
 * Read once per row (when its composition is created) and written by that same row's toggle —
 * both on the UI thread during a frame. It is deliberately **not** Compose state: nothing
 * outside a row ever changes that row's disclosure, so all a `SnapshotStateMap` would add is an
 * invalidation of every row that had read it. The row's own `MutableState`
 * ([rememberRowExpanded]) is what turns a toggle into a recomposition.
 *
 * This file is Android-free and Compose-free on purpose, so the fallback and the bound can be
 * pinned by `tools/run-app-pure-checks.sh` (registered as `row-expansion-store`).
 */
internal class RowExpansionStore(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    /**
     * The value [key] was left at **under [defaultExpanded]**, or `null` when there is nothing
     * to restore: the key has never been toggled, its entry has been evicted, or the entry was
     * written under a default that has since moved — in which case it is dropped here, because
     * it describes a state the switch has already discarded.
     */
    fun remembered(key: String, defaultExpanded: Boolean): Boolean? {
        val entry = entries[key] ?: return null
        if (entry.defaultExpanded == defaultExpanded) return entry.expanded
        entries.remove(key)
        return null
    }

    /** The reader's own choice for [key], recorded under the default in force when they made it. */
    fun record(key: String, defaultExpanded: Boolean, expanded: Boolean) {
        entries[key] = Entry(defaultExpanded, expanded)
    }

    /** Entries currently held. For diagnostics and the harness's bound check. */
    fun size(): Int = entries.size

    private class Entry(val defaultExpanded: Boolean, val expanded: Boolean)

    companion object {
        /**
         * How many rows' disclosures are remembered.
         *
         * An entry is only ever created by a tap on a card the reader has on screen, so this
         * holds every card a reader is likely to open in one sitting and then scroll back to,
         * several times over: the bound is there so that the table cannot grow with the session,
         * not because this many entries are expected. Each entry is one short key (already
         * interned — it is the transcript's own row key) and two booleans behind a map node, so
         * the whole table is a few tens of kilobytes in the worst case. Reaching it costs the
         * *oldest* opened card its state: it comes back following its default, which is exactly
         * the behaviour every row had before this table existed.
         */
        const val DEFAULT_CAPACITY = 256
    }
}
