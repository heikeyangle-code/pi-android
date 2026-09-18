package app.pi.ui.extension

// A bare-JVM harness for the notice queue's eviction rule. Registered in
// `tools/run-app-pure-checks.sh` as `notice-queue`.
//
// Why this is worth pinning: the host draws one snackbar at a time, oldest first, and only
// consumes an entry once it has been shown — so the head of the list is, by construction,
// the message on screen. The rule this file checks (never evict the head) is what keeps a
// burst of notices from deleting the sentence the user is in the middle of reading, and
// nothing about it is visible to the compiler.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // Under the cap nothing moves: the queue is append-only and order is the host's
    // display order.
    check("under the cap the list is untouched", trimNoticeQueue(listOf("a", "b"), 3), listOf("a", "b"))
    check("exactly at the cap is not a trim", trimNoticeQueue(listOf("a", "b", "c"), 3), listOf("a", "b", "c"))

    // Over the cap: the **head survives** and the oldest pending entry is the one lost.
    check(
        "one over the cap keeps the head and drops the oldest pending",
        trimNoticeQueue(listOf("a", "b", "c", "d"), 3),
        listOf("a", "c", "d"),
    )
    check(
        "two over the cap still keeps the head",
        trimNoticeQueue(listOf("a", "b", "c", "d", "e"), 3),
        listOf("a", "d", "e"),
    )
    check(
        "the newest entry is never the one dropped",
        trimNoticeQueue((1..8).map { it.toString() }, 3),
        listOf("1", "7", "8"),
    )

    // The property, stated directly: for any queue and any cap, the head is the same object
    // the caller passed in and the size is bounded. This is the whole contract; the exact
    // eviction order above is the implementation of it.
    for (size in 1..12) {
        for (cap in 1..6) {
            val entries = (1..size).map { "n$it" }
            val trimmed = trimNoticeQueue(entries, cap)
            if (trimmed.firstOrNull() != entries.first()) {
                failures++
                println("FAIL head survives (size=$size cap=$cap): ${trimmed.firstOrNull()} vs ${entries.first()}")
            }
            if (trimmed.size > cap) {
                failures++
                println("FAIL bounded (size=$size cap=$cap): ${trimmed.size}")
            }
            if (!entries.containsAll(trimmed)) {
                failures++
                println("FAIL nothing invented (size=$size cap=$cap): $trimmed")
            }
        }
    }
    println("PASS head survives and the queue stays bounded for every (size 1..12, cap 1..6)")

    // Degenerate caps: a zero cap cannot keep the head, and must not pretend to.
    check("a zero cap empties the queue", trimNoticeQueue(listOf("a", "b"), 0), emptyList<String>())
    check("an empty queue stays empty", trimNoticeQueue(emptyList<String>(), 3), emptyList<String>())

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
