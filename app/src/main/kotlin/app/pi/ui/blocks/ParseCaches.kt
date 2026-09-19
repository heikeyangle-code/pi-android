package app.pi.ui.blocks

import app.pi.rpc.Ansi

/**
 * The transcript's **cross-composition** parse caches: the parses a row must not redo when
 * it scrolls out of the list's reuse pool and back in.
 *
 * ## Why `remember` is not enough, and what this adds
 *
 * `remember(output) { Ansi.strip(output) }` is state of a composition. The `LazyColumn`
 * keeps a small pool of reusable rows; a row that leaves it is *disposed*, and composing
 * it again when the reader scrolls back runs every one of its parses from scratch. That is
 * the user's 「上下滑动出现一些东西的时候会有点顿…出现这些东西的时候，滑动不流畅」 at the item
 * level: the most expensive parses belong to exactly the rows most likely to be recycled —
 * a shell result's strip and tail, a diff's plan. (Measured: 2.4 ms to strip a 2000-line
 * coloured shell result, 1.9 ms for its tail, 3.9–13.2 ms for a diff plan with its
 * row-internal LCS. All numbers: `docs/scroll-perf-items.md` §2.)
 *
 * The caches are keyed on the **content** the parse reads, so a hit and a recomputation are
 * the same string by construction. The `remember` at each call site stays where it is: it
 * is the first-level, allocation-free path, and these memos only answer when it was
 * disposed.
 *
 * ## Budgets, and why these numbers
 *
 * Text parses are cheap relative to a decoded picture (2.4 ms versus tens of ms), so the
 * text budgets are a fraction of `PiImageCache.MAX_TOTAL_BYTES` (32 MiB): [STRIP_MAX_BYTES]
 * is 8 MiB and [TAIL_MAX_BYTES] 4 MiB. Both memos charge the **input and the result**
 * (`TextMemo`'s weigh: `(key + value) * 2`), so a 68 KB shell result costs ~0.27 MiB per
 * entry and the strip memo holds roughly thirty of them — several screens of transcript,
 * which is what the cache is for. Worst case both are full: 12 MiB, a third of what the
 * pictures may hold, in a process that also runs the pi engine.
 *
 * ## Lifetime
 *
 * Process-wide, holding only `String`s: no `Context`, nothing Activity-scoped, no timer, and
 * self-bounding — nothing leaks if no trim callback ever arrives. [clear] exists for a
 * future owner of one (`PiImageCache.clear` has the same note).
 *
 * The rule that a cache may not change what is drawn is stated on [TextMemo]; the eviction
 * and accounting themselves are `ByteBoundedLru`'s, pinned by
 * `app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt` (registered as `text-cache`).
 */
internal object ParseCaches {

    /** Budget for stripped tool bodies. See the class KDoc for the number. */
    internal const val STRIP_MAX_BYTES: Long = 8L * 1024 * 1024

    /** Budget for shell tails (`tailLines`), keyed by text and line count. */
    internal const val TAIL_MAX_BYTES: Long = 4L * 1024 * 1024

    private val stripped = TextMemo(STRIP_MAX_BYTES)
    private val tails = ByteBoundedLru<TailKey, String>(TAIL_MAX_BYTES) { key, value ->
        // Both halves are held by the entry: the text is the key, the window is the result.
        // A `Pair<String, Int>` would do the same job, but this key states the accounting and
        // keeps `hashCode` off the payload for the same reason `PiImageCache.Key` does.
        (key.text.length.toLong() + value.length.toLong()) * 2
    }

    /**
     * `Ansi.strip(raw)`, memoised.
     *
     * A body with **no** escape at all is returned as it is, without touching the memo:
     * `Ansi.strip` already returns its argument in that case, so caching it would store the
     * same instance twice and charge the budget for a copy that does not exist.
     */
    fun stripped(raw: String): String {
        if (!Ansi.containsEscapes(raw)) return raw
        return stripped.getOrCompute(raw) { Ansi.strip(it) }
    }

    /** `tailLines(text, max)`, memoised. The key is the text and the window size. */
    fun tail(text: String, max: Int): String = tails.getOrCompute(TailKey(text, max)) { key ->
        tailLines(key.text, key.max)
    }

    /** Bytes held by both memos together. Diagnostics, and the device criterion in §4. */
    val bytes: Long get() = stripped.bytes + tails.bytes

    /** Entries held by both memos together. */
    val size: Int get() = stripped.size + tails.size

    /** Forgets both memos. See the class KDoc on lifetime. */
    fun clear() {
        stripped.clear()
        tails.clear()
    }

    /**
     * A tail's key: the text, and the window size it is cut to.
     *
     * `equals` compares the text without hashing it (the map never calls `hashCode`),
     * [hashCode] is built from the window size alone so it is O(1) — the same pair of rules
     * `PiImageCache.Key` documents.
     */
    private class TailKey(val text: String, val max: Int) {
        override fun equals(other: Any?): Boolean = other is TailKey && max == other.max && text == other.text

        override fun hashCode(): Int = max
    }
}
