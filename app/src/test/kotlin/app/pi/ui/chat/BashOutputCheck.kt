package app.pi.ui.chat

// A bare-JVM harness for the `!` panel's output window. Registered in
// `tools/run-app-pure-checks.sh` as `bash-output`.
//
// Why this is worth pinning: a running `!` command streams chunks into one accumulator
// that a single `Text` paints, and the panel is on screen the whole time. The three
// properties that keep that bounded are all invisible to the compiler and all
// user-visible when they break:
//
//  1. the bound holds (otherwise a chatty command lays out megabytes per frame);
//  2. it is the **tail** that survives (pi's own choice, `bash-execution.js:95-96`
//     `truncateTail` — a command's outcome is at the end);
//  3. the caller is told when the front was dropped, so the panel's existing
//     "输出被截断" sentence is reachable mid-run instead of only after it.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** Feed [deltas] through the helper and report the buffer and whether it trimmed. */
private fun feed(deltas: List<String>, maxChars: Int): Pair<String, Boolean> {
    val builder = StringBuilder()
    var trimmed = false
    for (delta in deltas) {
        if (appendTailBounded(builder, delta, maxChars)) trimmed = true
    }
    return builder.toString() to trimmed
}

fun main() {
    // Under the bound nothing is touched: the accumulation is exactly the concatenation,
    // in order, with every newline the command wrote.
    check("under the bound the text is untouched", feed(listOf("a\n", "b\n", "c"), 100), "a\nb\nc" to false)
    check("exactly at the bound is not a trim", feed(listOf("x".repeat(10)), 10), "x".repeat(10) to false)
    check("an empty delta changes nothing", feed(listOf("", "a"), 10), "a" to false)

    // Over the bound: the result is never longer than the bound, and it is the **end**
    // of the stream that survives.
    val line = "0123456789\n"
    val many = List(50) { line }
    val (kept, trimmed) = feed(many, 40)
    check("over the bound, a trim is reported", trimmed, true)
    check("the kept text is within the bound", kept.length <= 40, true)
    check("the kept text is a tail of the stream", many.joinToString("").endsWith(kept), true)

    // The cut lands on a line boundary, so a painted window never begins mid-line. Every
    // line of the kept text is a whole line of the input.
    val input = (1..20).joinToString("") { "line-$it\n" }
    val (windowed, _) = feed(listOf(input), 30)
    check("the window starts at a line boundary", windowed.startsWith("line-"), true)
    check("every kept line is whole", windowed.split('\n').dropLast(1).all { it.startsWith("line-") }, true)

    // A single line longer than the whole bound keeps its own tail (the same choice
    // `tailLines` makes for a tool card), and still reports the trim.
    val (oneLong, longTrimmed) = feed(listOf("z".repeat(1000)), 64)
    check("a single over-long line keeps its tail", oneLong, "z".repeat(64))
    check("and reports the trim", longTrimmed, true)

    // Many small chunks — what pi actually emits — and one big chunk of the same text
    // must agree about the **end** of the stream, which is the half the panel is for. They
    // are not byte-identical and cannot be: the window slides every time it crosses the
    // bound, so a stream fed in small chunks has slid more often and still holds the last
    // line or two the single-chunk feed has already cut. What must hold for both is the
    // contract the panel relies on: within the bound, a suffix of the stream, ending on the
    // stream's last line.
    val chunked = (1..200).map { "chunk-$it\n" }
    val whole = chunked.joinToString("")
    val (chunkedWindow, _) = feed(chunked, 100)
    val (wholeWindow, _) = feed(listOf(whole), 100)
    check("a chunk-fed window is within the bound", chunkedWindow.length <= 100, true)
    check("a whole-fed window is within the bound", wholeWindow.length <= 100, true)
    check("a chunk-fed window is a suffix of the stream", whole.endsWith(chunkedWindow), true)
    check("a whole-fed window is a suffix of the stream", whole.endsWith(wholeWindow), true)
    check(
        "both windows end on the stream's last line",
        chunkedWindow.trimEnd('\n').lines().last() to wholeWindow.trimEnd('\n').lines().last(),
        "chunk-200" to "chunk-200",
    )

    // pi's own number: 50 KiB, and the harness pins it as a value so a silent re-tune of
    // the panel's ceiling cannot pass review.
    check("the bound is pi's DEFAULT_MAX_BYTES", BASH_OUTPUT_MAX_CHARS, 50 * 1024)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
