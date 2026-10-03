package app.pi.ui

// A bare-JVM harness for the AppBar's status-line ownership
// (`ui/EngineStatusLine.kt`). It imports only the Kotlin stdlib, so
// `tools/run-app-pure-checks.sh` can compile it next to the class — no android.jar, no
// Compose, no coroutines.
//
// Why the mechanism needs pinning: the bug it replaces was **invisible in every
// single-call reading** — `busy = label` / "clear when the label is still mine" is correct
// until two calls carry the same label, and the app produces exactly that pair on a
// coalesced `refreshTree` (the follow-up read is dispatched from inside the previous read's
// block `finally`, so the previous `call`'s own `finally` runs *after* the newer claim).
// The user-visible result was the AppBar answering 「就绪」 while a read was still running.
// A harness can hold the two claims in the order the app makes them; a device cannot be
// asked to.
//
// The assertions below are that order, the stale release after it, and the two ways a
// release can be repeated or forged.
//
// Run it by hand (the same recipe the registered harnesses use):
//
//   see the command line in docs/pi-surface-audit-tools.md §9 D1

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ------------------------------------------- the shape the app really produces
    val line = EngineStatusLine()
    check("a fresh line is held by nobody", line.held, false)
    val first = line.claim()
    check("a claim holds the line", line.held, true)

    // The coalesced `refreshTree` pair: the follow-up read claims **before** the first read
    // has released. Both carry 「读取会话树」, which is why the label could not tell them
    // apart — and why the first release must not clear the line.
    val second = line.claim()
    check("a newer claim is a different token", second == first, false)
    check("the stale release is refused", line.release(first), false)
    check("the line survives the stale release", line.held, true)

    // Only the owner may release, and the line falls with it.
    check("the owner releases", line.release(second), true)
    check("the line is free afterwards", line.held, false)

    // ------------------------------------------- the rule that must not be softened
    // A token is one-shot: the same release twice cannot take a line a later claim owns.
    val third = line.claim()
    check("a token that already released cannot release again", line.release(second), false)
    check("a third claim still owns the line", line.release(third), true)
    check("nothing is held at the end", line.held, false)

    // A release for a claim that was never made (0 is the "no owner" sentinel, not a token).
    check("a forged token releases nothing", line.release(0L), false)
    check("a forged token cannot steal ownership", line.release(-1L), false)
    val fourth = line.claim()
    check("the live owner still releases after the forgeries", line.release(fourth), true)

    // Claim after claim after claim: every release works from new to old only for the
    // current owner; the older ones are all refusals. This is the property the old
    // label-compare could not have.
    val a = line.claim()
    val b = line.claim()
    val c = line.claim()
    check("the oldest of three is refused", line.release(a), false)
    check("the middle of three is refused", line.release(b), false)
    check("the newest of three releases", line.release(c), true)
    check("all three are gone", line.held, false)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
