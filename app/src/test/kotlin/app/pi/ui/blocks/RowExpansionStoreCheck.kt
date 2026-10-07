package app.pi.ui.blocks

import kotlin.system.exitProcess

// A bare-JVM harness for `RowExpansionStore.kt` — the transcript's table of per-row
// expand/collapse choices. Registered in `tools/run-app-pure-checks.sh` as
// `row-expansion-store`.
//
// Why this is worth pinning: the table replaced
// `var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }` in the twelve
// blocks that disclose something. Two of that expression's properties are load-bearing and
// neither is visible to any other check in this repository:
//
//   ① the value must outlive the composition that drew the row — a card the reader opened, and
//      a row that left and re-entered the `LazyColumn`'s window, must come back **open**. That
//      is the whole fix; a table that forgets on read is the old behaviour with more code.
//   ② a **change** of `defaultExpanded` must still discard the value, which is what keeps the
//      AppBar's expand/collapse-all switch exact — including the case the naive implementations
//      get wrong: a manual value chosen before the switch moved must not come back when the
//      switch is moved back to where it was.
//
// What is *not* here, and cannot be: that Compose actually calls this with the row's key and
// that the write-through state recomposes the row (`rememberRowExpanded` needs Compose, which
// this machine cannot compile — no Compose compiler plugin and no APK, because AAPT2 is x86-64
// only), and how any of it looks on a device. Those are the CI build and the device items.
//
// The stored test uses the production capacity where the bound is the point, and small
// capacities everywhere else, so the eviction order is readable by eye.

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
    fallbackChecks()
    restoreChecks()
    defaultMoveChecks()
    recencyChecks()
    boundChecks()

    if (failures != 0) {
        println("row-expansion-store: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}

// ------------------------------------------------------- nothing remembered ----

/**
 * A key the reader has never toggled has no entry, and the caller — `rememberRowExpanded` —
 * then uses the default it was handed. Both defaults matter: the app's blocks are split between
 * `false` (most of them) and the thinking preference / the expand-all switch, either of which
 * can be either value.
 */
private fun fallbackChecks() {
    val store = RowExpansionStore(capacity = 4)
    check("a key that was never toggled answers null (default false)", store.remembered("row", false), null)
    check("a key that was never toggled answers null (default true)", store.remembered("row", true), null)
    check("and reading it did not create an entry", store.size(), 0)
}

// ------------------------------------------------- the value outlives the row ----

/**
 * The property the whole table exists for. `record` is the reader's own toggle (the only
 * writer), `remembered` is what a composition that starts *later* — the row coming back into
 * the window — reads.
 */
private fun restoreChecks() {
    val store = RowExpansionStore(capacity = 4)
    store.record("row", defaultExpanded = false, expanded = true)
    check("an opened card is still open after the row left the window", store.remembered("row", false), true)
    check("the toggle took one entry", store.size(), 1)

    store.record("row", defaultExpanded = false, expanded = true)
    check("re-recording the same value does not add an entry", store.size(), 1)

    store.record("other", defaultExpanded = false, expanded = false)
    check("a collapse is remembered too", store.remembered("other", false), false)
    check("and it is a second entry", store.size(), 2)

    // The rows that follow a *different* default are separate keys, so they cannot collide.
    store.record("thinking", defaultExpanded = true, expanded = false)
    check("a row following a true default keeps its own value", store.remembered("thinking", true), false)
    check("the false-default rows are untouched by it", store.remembered("row", false), true)
}

// ---------------------------------------- the default still discards the value ----

/**
 * The other half of `remember(defaultExpanded)`: the switch (or the thinking preference)
 * **moves** the default, and every row re-reads under the value it has now. The second half of
 * each pair is the part a shadowing implementation gets wrong — the stale entry has to be gone,
 * not merely hidden, or the old manual value comes back the moment the default returns to the
 * value it was chosen under.
 */
private fun defaultMoveChecks() {
    val store = RowExpansionStore(capacity = 4)
    store.record("row", defaultExpanded = false, expanded = true)
    check("before the switch, the manual value is what the row reads", store.remembered("row", false), true)

    check("after 展开全部 the row follows the new default", store.remembered("row", true), null)
    check("and the stale entry was dropped, not shadowed", store.size(), 0)

    check("the switch moved back does not resurrect the manual value", store.remembered("row", false), null)

    // A value chosen under the *new* default is a fresh choice and is kept like any other.
    val second = RowExpansionStore(capacity = 4)
    second.record("row", defaultExpanded = false, expanded = true)
    second.remembered("row", true) // the switch moves
    second.record("row", defaultExpanded = true, expanded = false) // the reader closes it
    check("a value chosen after the switch is remembered under the new default", second.remembered("row", true), false)
    check("and moving the switch back drops it like any other stale entry", second.remembered("row", false), null)
}

/** Access order, the same LRU shape `RowHeightCache` uses: reading a key keeps it. */
private fun recencyChecks() {
    val store = RowExpansionStore(capacity = 2)
    store.record("a", false, true)
    store.record("b", false, true)
    check("both fit", store.size(), 2)

    check("a read returns the value", store.remembered("a", false), true) // touches `a`
    store.record("c", false, true)
    check("the least recently read key is the one evicted", store.remembered("b", false), null)
    check("the key that was read survives the insert", store.remembered("a", false), true)
    check("the newcomer is held", store.remembered("c", false), true)
    check("the table is still at its capacity", store.size(), 2)
}

/**
 * The property that makes the table safe to keep for a whole session: it cannot grow with the
 * session. The last check writes 100 000 rows through an instance sized by the *production*
 * constant, because "the table is bounded" is the one property a re-tune must not quietly
 * delete.
 */
private fun boundChecks() {
    check("the default capacity is 256", RowExpansionStore.DEFAULT_CAPACITY, 256)

    val store = RowExpansionStore()
    for (i in 1..100_000) store.record("row-$i", false, i % 2 == 0)
    check("100 000 toggled rows leave exactly the capacity behind", store.size(), RowExpansionStore.DEFAULT_CAPACITY)
    check("the newest row is held", store.remembered("row-100000", false), true)
    check("the oldest row fell back to its default", store.remembered("row-1", false), null)

    // A capacity of zero would be "remember nothing" while looking like it worked: every row
    // would quietly go back to the behaviour this table was added to fix. It is refused.
    val refused = try {
        RowExpansionStore(capacity = 0)
        "accepted"
    } catch (error: IllegalArgumentException) {
        "refused"
    }
    check("a zero capacity is refused rather than silently degraded", refused, "refused")

    survives("a one-entry table still works") {
        val one = RowExpansionStore(capacity = 1)
        one.record("only", false, true)
        one.remembered("only", false)
    }
}
