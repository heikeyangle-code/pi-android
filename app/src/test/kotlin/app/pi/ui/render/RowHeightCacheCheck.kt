package app.pi.ui.render

import kotlin.system.exitProcess

// A bare-JVM harness for `RowHeightCache.kt` — the bounded record of how tall each rendered
// transcript row was the last time it was measured. Registered in
// `tools/run-app-pure-checks.sh` as `row-height-cache`.
//
// Why this is worth pinning: the cache is what lets a row that is **composed again** after a
// destination switch be laid out at the height it had before, instead of at the zero height a
// fresh `MarkdownState` reports while its parse runs (`docs/scroll-diagnosis.md` §2.2 S2-c,
// landed as D51). Because it is process-wide and lives outside every composition, two of its
// properties are load-bearing and neither is visible to any other check in this repository:
//
//   ① it must be **bounded**. The transcript is unbounded and 「加载更早」 can walk a reader
//      through tens of thousands of rows, so a cache that grows with the session is a memory
//      leak with a user-facing trigger. The last check writes 100 000 rows through the
//      *production* instance and requires it to still hold exactly its capacity.
//   ② it must never hand back a **stale** height for a key it no longer holds, and never
//      record the zero-height frame it exists to work around: a stored `0` would make the
//      next composition's floor a no-op, i.e. silently undo the fix. A `null` costs one
//      frame of the old behaviour; a stale floor pads or clips a row that has moved on.
//
// What is *not* here, and cannot be: whether the floor actually reaches the first frame of a
// re-entered destination (that needs Compose, which this machine cannot compile — no Compose
// compiler plugin in `tools/typecheck.sh`, and no APK, because AAPT2 is x86-64 only), and how
// the transition looks on the device. Those are D51's S1/S2/S3 device items.
//
// The capacity used by the transcript is asserted against the constant rather than a literal
// where that matters, so a future re-tune of `DEFAULT_CAPACITY` moves both halves at once —
// except in the bound check, which pins the literal *and* the constant, because "the cache
// cannot grow with the session" is the one property a re-tune must not quietly delete.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** A call that must return something rather than raise, whatever it was handed. */
private fun survives(name: String, block: () -> Any?) {
    val outcome = try {
        block()
        "returned"
    } catch (error: Throwable) {
        "threw ${error::class.java.simpleName}: ${error.message}"
    }
    check(name, outcome, "returned")
}

fun main() {
    capacityChecks()
    recencyChecks()
    rejectChecks()
    overwriteChecks()
    stalenessChecks()
    boundChecks()

    if (failures != 0) {
        println("row-height-cache: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}

// -------------------------------------------------------------- the capacity bound ----

/**
 * Small capacities are used everywhere below because the rule is size-independent: at three
 * entries the eviction order is readable by eye, and the same code path is what the
 * 100 000-row check exercises through the production instance.
 */
private fun capacityChecks() {
    val cache = RowHeightCache(capacity = 3)
    cache.record("a", 100)
    cache.record("b", 200)
    cache.record("c", 300)
    check("three entries fit", cache.size(), 3)
    check("a recorded height is read back", cache.of("b"), 200)

    cache.record("d", 400)
    check("a fourth entry does not grow the cache", cache.size(), 3)
    check("the least recently used key is the one evicted", cache.of("a"), null)
    check("the other survivor keeps its value", cache.of("c"), 300)
    check("the newcomer is held", cache.of("d"), 400)
}

/**
 * Access order, which is what makes the LRU useful rather than merely bounded: the rows that
 * matter are the ones on screen, and those are exactly the ones being read (and re-measured)
 * every frame.
 */
private fun recencyChecks() {
    val read = RowHeightCache(capacity = 3)
    read.record("a", 1)
    read.record("b", 2)
    read.record("c", 3)
    check("a read returns the value", read.of("a"), 1) // touches `a`

    read.record("d", 4) // evicts `b`, which is now the least recently used
    check("a read moves its key to most-recently-used", read.of("b"), null)
    check("the key that was read survives the insert", read.of("a"), 1)
    check("the key that was neither read nor inserted is the other survivor", read.of("c"), 3)
    check("the insert kept the cache at its capacity", read.size(), 3)

    val overwritten = RowHeightCache(capacity = 3)
    overwritten.record("a", 1)
    overwritten.record("b", 2)
    overwritten.record("c", 3)
    overwritten.record("a", 11) // an overwrite is an access in an access-ordered map
    overwritten.record("d", 4) // so this evicts `b`, not `a`
    check("an overwrite counts as an access", overwritten.of("b"), null)
    check("the overwritten key is held with its new value", overwritten.of("a"), 11)
    check("an overwrite did not add an entry", overwritten.size(), 3)
}

/**
 * The measurements that must **not** become an entry. A row measured at zero is the
 * `State.Loading` frame this whole cache exists to work around — the `MarkdownState` a fresh
 * composition builds draws an empty `Box` until its parse lands — so storing it would turn the
 * next composition's floor into a no-op, silently.
 */
private fun rejectChecks() {
    val cache = RowHeightCache(capacity = 4)
    cache.record("zero", 0)
    check("a zero height answers null", cache.of("zero"), null)
    check("a zero height did not take an entry", cache.size(), 0)

    cache.record("negative", -5)
    check("a negative height answers null", cache.of("negative"), null)
    check("a negative height did not take an entry", cache.size(), 0)

    cache.record("row", 240)
    cache.record("row", 0)
    check("a zero does not wipe a real height", cache.of("row"), 240)
    check("and it did not add a second entry for the same key", cache.size(), 1)
}

private fun overwriteChecks() {
    val cache = RowHeightCache(capacity = 4)
    cache.record("row", 100)
    cache.record("row", 250)
    check("an overwrite replaces the value", cache.of("row"), 250)
    check("an overwrite does not add an entry", cache.size(), 1)

    repeat(1_000) { cache.record("row", 250) }
    check("a thousand identical writes are still one entry", cache.size(), 1)
    check("and the value is unchanged", cache.of("row"), 250)

    // The rows are re-measured on many frames (a streamed answer grows), so a repeated write
    // of the same height is the common case, not a corner.
    survives("re-measuring a row at its own height is not an error") { cache.record("row", 250) }
}

/**
 * "取值拿不到时不返回过期值": a key the cache no longer holds answers `null`, never the height
 * it used to have. The distinction matters at the call site — `rememberedRowHeight` reads the
 * cache once per fresh composition, so a stale answer is a floor applied to a row whose content
 * has moved on, while `null` is simply today's behaviour for that row.
 */
private fun stalenessChecks() {
    val cache = RowHeightCache(capacity = 2)
    cache.record("gone", 320)
    cache.record("kept", 120)
    cache.record("newer", 40) // capacity 2, nothing read yet: evicts "gone"
    check("an evicted key answers null, not its old height", cache.of("gone"), null)
    check("the other original key is held", cache.of("kept"), 120)
    check("the newest key is held", cache.of("newer"), 40)

    check("a key that was never recorded answers null", cache.of("never"), null)
    check("reading a missing key does not create it", cache.size(), 2)

    // A reused key is the case where a naive implementation *would* answer the old value.
    val reused = RowHeightCache(capacity = 2)
    reused.record("row", 500)
    reused.record("other", 500)
    reused.record("row", 90)
    check("a re-recorded key answers its newest height", reused.of("row"), 90)
    check("and the value it had before is not what comes back", reused.of("row") == 500, false)

    cache.clear()
    check("clear empties the cache", cache.size(), 0)
    check("and nothing survives a clear", cache.of("kept"), null)

    // A capacity of zero would be "remember nothing" while looking like it worked: every row
    // would quietly fall back to the zero-height frame the cache was added to remove. It is
    // refused instead.
    val refused = try {
        RowHeightCache(capacity = 0)
        "accepted"
    } catch (error: IllegalArgumentException) {
        "refused"
    }
    check("a zero capacity is refused rather than silently degraded", refused, "refused")
}

/**
 * The property the class exists to guarantee, on the instance the app actually uses: the
 * transcript can be arbitrarily long and a reader can walk it for hours, and the cache must not
 * grow with either.
 */
private fun boundChecks() {
    check("the default capacity is 96", RowHeightCache.DEFAULT_CAPACITY, 96)

    val shared = RowHeightCache.shared
    check("the production cache starts empty", shared.size(), 0)

    val capacity = RowHeightCache.DEFAULT_CAPACITY
    for (i in 1..100_000) shared.record("row-$i", 10 + (i % 7))
    check("100 000 recorded rows leave exactly the capacity behind", shared.size(), capacity)
    check("the newest row is held", shared.of("row-100000"), 10 + (100000 % 7))
    check("the oldest row is gone", shared.of("row-1"), null)
    check(
        "the entries held are the most recent ones",
        (100_000 - capacity + 1..100_000).count { shared.of("row-$it") != null },
        capacity,
    )
    check("and the row just before that window is not", shared.of("row-${100_000 - capacity}"), null)

    // A second run through the same instance reports the same bound: the eviction is not a
    // one-off that the first pass happened to trigger.
    for (i in 100_001..200_000) shared.record("row-$i", 10 + (i % 7))
    check("another 100 000 rows leave the same capacity behind", shared.size(), capacity)
    check("the window has moved", shared.of("row-200000"), 10 + (200000 % 7))
    check("and the previous window is gone", shared.of("row-100000"), null)
}
