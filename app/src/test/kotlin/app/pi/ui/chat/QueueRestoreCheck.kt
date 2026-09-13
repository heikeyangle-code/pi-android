package app.pi.ui.chat

// A bare-JVM harness for pi's queue/editor merge rule. Registered in
// `tools/run-app-pure-checks.sh` as `queue-restore`.
//
// Why this is worth pinning: two different user actions end in this one function —
// Stop (`restoreQueuedMessagesToEditor({ abort: true })`,
// `modes/interactive/interactive-mode.ts:2854`) and the queue row's dequeue
// (`handleDequeue` → `restoreQueuedMessagesToEditor()`, `:4157-4164`, no abort) —
// and pi implements both with the same three lines (`:4397-4400`):
//
//   const combinedText = [queuedText, currentText].filter((t) => t.trim()).join("\n\n");
//
// The failure this guards is not "it throws": it is a merge that quietly drops the
// draft (the bug Stop used to have, one layer down), inserts a blank line between a
// queued message and the draft, or appends the draft *before* the queue. None of
// those can be seen by the compiler, and all of them are visible to the user.

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
    // The plain case: one queued message, one line already in the editor.
    check(
        "a queued message goes in front of the draft, separated by one blank line",
        mergeRestoredQueue(listOf("queued"), "draft"),
        "queued\n\ndraft",
    )

    // Two queued messages keep their order and are separated the same way: the
    // caller passes `steering + followUp` in that order (`PiEngineSession.drainQueue`).
    check(
        "several queued messages keep their order",
        mergeRestoredQueue(listOf("first", "second"), "draft"),
        "first\n\nsecond\n\ndraft",
    )

    // An empty editor is pi's `currentText` that trims to nothing: the join filter
    // then yields no trailing separator. Without the filter the result would be
    // "queued\n\n", which is a composer holding a stray blank line after every Stop.
    check(
        "an empty draft leaves no trailing separator",
        mergeRestoredQueue(listOf("queued"), ""),
        "queued",
    )
    check(
        "a whitespace-only draft counts as empty",
        mergeRestoredQueue(listOf("queued"), "   "),
        "queued",
    )

    // A blank queued message (pi can queue an empty expansion) must not produce a
    // leading separator around the draft.
    check(
        "a blank queued message is dropped",
        mergeRestoredQueue(listOf("", "  "), "draft"),
        "draft",
    )

    // Nothing queued and nothing typed: the composer stays empty rather than
    // becoming "\n\n".
    check("both halves empty stays empty", mergeRestoredQueue(emptyList(), ""), "")

    // The queue text itself is not trimmed by the merge — only blank *halves* are
    // filtered. pi's `filter((t) => t.trim())` tests, it does not rewrite, so a
    // message that ends in a newline keeps it.
    check(
        "a message's own whitespace is preserved",
        mergeRestoredQueue(listOf("  padded  "), "draft"),
        "  padded  \n\ndraft",
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
