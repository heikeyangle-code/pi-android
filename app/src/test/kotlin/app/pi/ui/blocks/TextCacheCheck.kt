package app.pi.ui.blocks

import kotlin.random.Random
import kotlin.system.exitProcess

// A bare-JVM harness for the parse caches' pure half: `ByteBoundedLru.getOrCompute`,
// `TextMemo`, and `IncrementalLineCount` (all in `ui/blocks/ImageSize.kt`).
// Registered in `tools/run-app-pure-checks.sh` as `text-cache`.
//
// Why this is worth pinning: these three are the answer to "a row scrolled out of the
// list's reuse pool and back re-parses everything", and each of them can be wrong in a way
// nobody would see on screen.
//
//  ① `getOrCompute` — the whole point of a parse cache is that a **hit does not recompute**.
//     If it recomputed and merely replaced the value, every measurement in
//     `docs/scroll-perf-items.md` would still look right while the app stayed slow.
//  ② `TextMemo` — its accounting is what bounds the process's memory. A memo that forgets
//     to charge for the key it is holding (the input string, not just the result) would
//     hold megabytes while reporting kilobytes.
//  ③ `IncrementalLineCount` — the footer's 「N 行」 reading. The whole saving depends on
//     "appending only adds newlines", and the failure mode is a wrong number in the UI, not
//     a crash. So it is compared against `lineCount` over random growth *and* random
//     discontinuities (a replaced result, a recycled row), which is the case the counter
//     has to recognise rather than carry forward.
//
// The caches' wiring (`ParseCaches`, `DiffPlanCache`) imports `app.pi.rpc.Ansi` and
// `DiffRow` respectively and cannot be compiled here; what they add is the choice of key and
// budget, and the pure logic underneath is what this file executes.

private var checks = 0
private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name: actual=$actual expected=$expected")
    } else {
        println("PASS  $name")
    }
}

private fun checkTrue(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    } else {
        println("PASS  $name")
    }
}

/**
 * The expression `BlockChrome.kt`'s `lineCount` is, copied here so this harness can compile
 * without Compose. `wiringChecks` below reads the real file and requires the two to be the
 * same expression, which is what makes this a reference rather than a second opinion.
 */
private fun referenceLineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

/** [checkTrue] without its `PASS` line: the fuzzes run tens of thousands of these. */
private fun checkQuiet(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    }
}

private fun runChecks() {
    println("== 1. getOrCompute: a hit does not recompute ==")
    val memo = ByteBoundedLru<String, String>(1024) { key, value -> (key.length + value.length).toLong() }
    var computed = 0
    val first = memo.getOrCompute("alpha") { key -> computed++; "A:$key" }
    val second = memo.getOrCompute("alpha") { key -> computed++; "A:$key" }
    check("the first call computed", first, "A:alpha")
    check("the second call returned the same value", second, "A:alpha")
    check("and computed exactly once", computed, 1)
    check("bytes charged for key and value", memo.bytes, ("alpha".length + "A:alpha".length).toLong())

    println("\n== 2. getOrCompute: a miss computes, and throws store nothing ==")
    check("a different key computes", memo.getOrCompute("beta") { key -> computed++; "B:$key" }, "B:beta")
    check("computed twice in total", computed, 2)
    val before = memo.bytes
    val threw = runCatching {
        memo.getOrCompute("gamma") { throw IllegalStateException("parse failed") }
    }.isFailure
    checkTrue("the exception propagated", threw)
    check("and nothing was stored", memo.get("gamma"), null)
    check("and the accounting is unchanged", memo.bytes, before)

    println("\n== 3. TextMemo: charges the input it keeps alive ==")
    val text = TextMemo(4096)
    val input = "x".repeat(100)
    val output = "y".repeat(40)
    text.put(input, output)
    check("size", text.size, 1)
    check("bytes = (input + output) * 2", text.bytes, ((input.length + output.length) * 2).toLong())
    check("hit returns the value", text.get(input), output)

    val off = TextMemo(0)
    off.put("k", "v")
    check("a zero budget stores nothing", off.get("k"), null)
    val tooSmall = TextMemo(10)
    tooSmall.put("k".repeat(50), "v")
    check("an over-budget entry is not stored", tooSmall.get("k".repeat(50)), null)
    check("and it did not evict anything", tooSmall.size, 0)

    println("\n== 4. TextMemo: eviction is least-recently-used ==")
    // Weights are (key + value) * 2; "1".."4" with one-character values cost 4 bytes each.
    val small = TextMemo(12)
    small.put("1", "a")
    small.put("2", "b")
    small.put("3", "c") // [3,2,1] = 12
    small.get("1") // [1,3,2]
    small.put("4", "d") // 16 -> evict "2" -> 12
    check("the least recently used went", small.get("2"), null)
    check("the recently read one stayed", small.get("1"), "a")
    check("the newest stayed", small.get("4"), "d")
    check("size", small.size, 3)
    check("bytes", small.bytes, 12L)

    println("\n== 5. IncrementalLineCount agrees with lineCount ==")
    check("an empty body is zero lines", IncrementalLineCount().of(""), 0)
    check("one line", IncrementalLineCount().of("a"), 1)
    check("two lines", IncrementalLineCount().of("a\nb"), 2)
    check("a trailing newline counts its empty last line", IncrementalLineCount().of("a\n"), 2)

    println("\n== 5b. …over random growth ==")
    val random = Random(20240918)
    var compared = 0
    repeat(300) {
        val counter = IncrementalLineCount()
        val builder = StringBuilder()
        repeat(60) {
            // Grow the text by a random run, sometimes with newlines, sometimes without.
            val run = buildString {
                repeat(random.nextInt(1, 12)) {
                    append(if (random.nextInt(4) == 0) '\n' else 'x')
                }
            }
            builder.append(run)
            val text = builder.toString()
            compared++
            checkQuiet("growth agrees with the reference count", counter.of(text) == referenceLineCount(text)) {
                "text=${text.replace("\n", "\\n").take(40)} counter=${counter.of(text)} reference=${referenceLineCount(text)}"
            }
        }
    }
    println("compared $compared growth steps")

    println("\n== 5c. …and over discontinuities (the case it must not carry forward) ==")
    var discontinuities = 0
    repeat(300) {
        val counter = IncrementalLineCount()
        var text = ""
        repeat(40) {
            text = when (random.nextInt(5)) {
                0 -> text + "appended\n" // a continuation
                1 -> "replaced " + text.hashCode() // a whole new body
                2 -> text.substring(0, text.length / 2) // a truncation: not a prefix
                3 -> text.dropLast(1) // another non-prefix
                else -> "" // emptied
            }
            if (text.isEmpty() && random.nextBoolean()) text = "\n\n"
            discontinuities++
            checkQuiet("discontinuity agrees with the reference count", counter.of(text) == referenceLineCount(text)) {
                "text=${text.replace("\n", "\\n").take(40)} counter=${counter.of(text)} reference=${referenceLineCount(text)}"
            }
        }
    }
    println("compared $discontinuities rewritten bodies")

    println("\n== 5d. the counter is exact after a truncated body ==")
    // The failure this guards: carrying the old count into a body that is not an extension of
    // it, which is what a truncated or replaced tool result looks like.
    val one = IncrementalLineCount()
    check("first body", one.of("a\nb\nc"), 3)
    check("truncated to its first line", one.of("a"), 1)
    check("and grown again", one.of("a\nb"), 2)

    println("\n== 6. the wiring these classes cannot see ==")
    val root = repoRoot()
    // `lineCount` lives in a Compose file, so the harness copies its expression instead of
    // compiling it. That is only a reference while the two are the same expression.
    val chrome = root?.let { java.io.File(it, "app/src/main/kotlin/app/pi/ui/blocks/BlockChrome.kt") }
    val chromeText = if (chrome != null && chrome.isFile) chrome.readText() else ""
    checkTrue("BlockChrome.lineCount is the expression this harness copied", chromeText.contains("if (text.isEmpty()) 0 else text.count { it == '\\n' } + 1")) {
        "read ${chrome?.absolutePath}"
    }
    val caches = root?.let { java.io.File(it, "app/src/main/kotlin/app/pi/ui/blocks/ParseCaches.kt") }
    val cachesText = if (caches != null && caches.isFile) caches.readText() else ""
    checkTrue("ParseCaches exists", cachesText.isNotEmpty()) { "file=${caches?.absolutePath}" }
    checkTrue("it has a byte budget for each memo", cachesText.contains("STRIP_MAX_BYTES") && cachesText.contains("TAIL_MAX_BYTES")) {
        "the budgets are what bound the process's memory"
    }
    checkTrue("it does not memoise a body with no escapes", cachesText.contains("if (!Ansi.containsEscapes(raw)) return raw")) {
        "strip already returns its argument there; caching it would charge for a copy that does not exist"
    }
    for (relative in listOf(
        "app/src/main/kotlin/app/pi/ui/blocks/ShellBlock.kt",
        "app/src/main/kotlin/app/pi/ui/blocks/ToolCallBlock.kt",
        "app/src/main/kotlin/app/pi/ui/blocks/DiffBlock.kt",
    )) {
        val file = root?.let { java.io.File(it, relative) }
        val text = if (file != null && file.isFile) file.readText() else ""
        val name = relative.substringAfterLast('/')
        when (name) {
            "ShellBlock.kt" -> checkTrue("$name strips through the cache", text.contains("ParseCaches.stripped("))
            "ToolCallBlock.kt" -> checkTrue("$name counts lines incrementally", text.contains("IncrementalLineCount()"))
            else -> checkTrue("$name plans through the cache", text.contains("DiffPlanCache.plan("))
        }
    }
}

private fun repoRoot(): java.io.File? {
    System.getProperty("pi.repo.root")?.let { return java.io.File(it) }
    var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
    repeat(6) {
        val candidate = dir ?: return null
        if (java.io.File(candidate, "tools/run-app-pure-checks.sh").isFile) return candidate
        dir = candidate.parentFile
    }
    return null
}

fun main() {
    runChecks()
    println()
    if (failures == 0) {
        println("harness: OK ($checks checks)")
    } else {
        println("harness: FAILED ($failures of $checks checks failed)")
        exitProcess(1)
    }
}
