package app.pi.ui.render

import kotlin.system.exitProcess

// A bare-JVM harness for the synchronous-parse gate — `wantsImmediateMarkdown` in
// `RowHeightCache.kt`. Registered in `tools/run-app-pure-checks.sh` as `markdown-parse-gate`.
//
// Why this is worth pinning: this predicate decides whether a transcript row's markdown is
// parsed on the frame it is composed (the library's `immediate = true`, handed to the row
// through `LocalPiMarkdownImmediate`) or one or more frames later, on `Dispatchers.Default`.
// The asynchronous path draws the library's `State.Loading` slot, which is an empty `Box`: the
// row has no text for those frames, and `rememberedRowHeight` holds its *geometry* through
// them, so the reader sees the row blank **in place** — 「收起一张卡，被推开的内容回到原位时原地
// 闪一下」.
//
// The latch used to be `RowHeightCache.of(rowKey) == null`, i.e. "this row has never been
// measured". That refused the eager parse to every row returning into the viewport — such a row
// has a remembered height, which is what the old term keyed on — so the blank frame was a
// certainty rather than a race. The latch is now the row's own parse state,
// `parsedInThisComposition`, and these three properties are the contract:
//
//   ① a non-streaming row with nothing parsed in this composition asks for the eager parse,
//      whether the window has just gained it (no remembered height) or it is returning into the
//      viewport (a remembered height). The returning half is the reported symptom;
//   ② a row whose text is still streaming never asks: its content changes on every token, and a
//      synchronous parse per token is the one thing this must not become;
//   ③ a row that has already parsed in this composition never asks again.
//
// The 4 ms frame budget is deliberately **not** an input: it is `MarkdownParseBudget.allow`,
// left as the last term of the `&&` at the call site precisely so the budget is not consulted
// for rows these facts already refuse.
//
// Deliberately still `false`, and asserted below so it cannot drift silently: a row with no
// remembered height that is not new to the window. Those are rows that have never been on
// screen in this process — the window-gained ones are `freshRowKeys`, and a restored screen's
// rows are `restoring`, and neither applies to a row the reader is merely scrolling into for
// the first time. Giving those the eager parse as well would mean parsing every row on the way
// down the transcript, which is a different change from this one.
//
// What cannot be checked here: whether the floor and this gate actually reach the frame the
// reader sees (that needs Compose, which this machine cannot compile), and how the transition
// looks on a device. Those are the device items in the change's report.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** The gate with every input named, so a reader sees which fact each case turns on. */
private fun gate(
    streaming: Boolean,
    parsed: Boolean,
    remembered: Boolean,
    firstSighting: Boolean,
): Boolean = wantsImmediateMarkdown(
    streaming = streaming,
    parsedInThisComposition = parsed,
    hasRememberedHeight = remembered,
    firstSightingForWindow = firstSighting,
)

fun main() {
    // ① The case the reader reported: the rows a long card had pushed away come back into the
    // viewport. They have a remembered height — that is how the height floor holds them still —
    // and nothing parsed in this composition, so the eager parse is what keeps them from
    // blanking on the frame they return.
    check(
        "a returning, non-streaming row parses eagerly",
        gate(streaming = false, parsed = false, remembered = true, firstSighting = false),
        true,
    )
    // The same row, when the window gained it in the same breath.
    check(
        "a returning row the window also just gained parses eagerly",
        gate(streaming = false, parsed = false, remembered = true, firstSighting = true),
        true,
    )
    // The half of the contract the old gate did cover, and which must keep working: a row a
    // batch has just brought in has no remembered height at all, so the eager parse is the only
    // way its first frame is not a zero-height one.
    check(
        "a window-gained row with no remembered height parses eagerly",
        gate(streaming = false, parsed = false, remembered = false, firstSighting = true),
        true,
    )

    // ② Never a streaming row: every other fact is arranged in its favour here.
    check(
        "a streaming row never parses eagerly",
        gate(streaming = true, parsed = false, remembered = true, firstSighting = true),
        false,
    )
    check(
        "a streaming row with no remembered height never parses eagerly",
        gate(streaming = true, parsed = false, remembered = false, firstSighting = true),
        false,
    )

    // ③ Never a row that has already parsed in this composition — that is what makes the ask one
    // per composition rather than one per recomposition.
    check(
        "an already parsed returning row never parses eagerly",
        gate(streaming = false, parsed = true, remembered = true, firstSighting = false),
        false,
    )
    check(
        "an already parsed window-gained row never parses eagerly",
        gate(streaming = false, parsed = true, remembered = false, firstSighting = true),
        false,
    )

    // The boundary this change does not touch: a row that has never been measured and is not new
    // to the window, i.e. one that is on screen for the first time in this process.
    check(
        "a row with no remembered height that is not new to the window does not ask",
        gate(streaming = false, parsed = false, remembered = false, firstSighting = false),
        false,
    )

    // The whole table, written out cell by cell, so moving any single cell has to be an explicit
    // edit here too. The order is the loop order below: `streaming = false` first, and inside it
    // `parsed = false` first, then `remembered = false` first, then `firstSighting = false`
    // first. The expected values are the rule itself, not a re-evaluation of the implementation.
    val expectedCells = listOf(
        // streaming = false, nothing parsed in this composition yet.
        false, // remembered = false, not new to the window: never measured, no first sighting.
        true, // remembered = false, first sighting for the window.
        true, // remembered = true, not new to the window: the returning row.
        true, // remembered = true, first sighting: the returning row the reader reported.
        false, // parsed = true, remembered = false, not new to the window.
        false, // parsed = true, remembered = false, first sighting.
        false, // parsed = true, remembered = true, not new to the window.
        false, // parsed = true, remembered = true, first sighting.
        // streaming = true: never, whatever else is true of the row.
        false, false, false, false, false, false, false, false,
    )
    var cell = 0
    for (streaming in listOf(false, true)) {
        for (parsed in listOf(false, true)) {
            for (remembered in listOf(false, true)) {
                for (firstSighting in listOf(false, true)) {
                    check(
                        "table[$cell] streaming=$streaming parsed=$parsed " +
                            "remembered=$remembered firstSighting=$firstSighting",
                        gate(streaming, parsed, remembered, firstSighting),
                        expectedCells[cell],
                    )
                    cell++
                }
            }
        }
    }

    if (failures != 0) {
        println("markdown-parse-gate: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}
