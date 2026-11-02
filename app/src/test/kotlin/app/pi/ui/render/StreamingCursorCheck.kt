package app.pi.ui.render

import kotlin.system.exitProcess

/** `StreamingCursor.kt` 的行为夹具（`tools/run-app-pure-checks.sh` 里的 `streaming-cursor`）。 */
private var failures = 0
private var checks = 0

private fun expect(name: String, actual: String, expected: String) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name\n   expected: ${expected.replace("\n", "⏎")}\n   actual:   ${actual.replace("\n", "⏎")}")
    }
}

fun main() {
    val c = STREAMING_CURSOR
    expect("empty", withStreamingCursor(""), c)
    expect("whitespace only", withStreamingCursor(" \n\n"), c)
    expect("plain", withStreamingCursor("你好"), "你好$c")
    expect("trailing newline", withStreamingCursor("你好\n"), "你好$c")
    expect("trailing blank line", withStreamingCursor("你好\n\n"), "你好$c")
    expect("trailing spaces", withStreamingCursor("hello   "), "hello$c")
    expect("second paragraph", withStreamingCursor("a\n\nbc"), "a\n\nbc$c")
    expect("list item", withStreamingCursor("- a\n- b"), "- a\n- b$c")
    expect("bare bullet", withStreamingCursor("- a\n-"), "- a\n- $c")
    expect("bare ordered", withStreamingCursor("1. a\n2."), "1. a\n2. $c")
    expect("bare heading", withStreamingCursor("文\n\n##"), "文\n\n## $c")
    expect("bare quote", withStreamingCursor(">"), "> $c")
    expect("inside open fence", withStreamingCursor("```kotlin\nval x"), "```kotlin\nval x$c")
    expect("open fence line itself", withStreamingCursor("文\n```kotlin"), "文\n```kotlin")
    expect("closing fence", withStreamingCursor("```\ncode\n```"), "```\ncode\n```")
    expect("closing fence + newline", withStreamingCursor("```\ncode\n```\n"), "```\ncode\n```\n")
    expect("tilde fence", withStreamingCursor("~~~\nx\n~~~"), "~~~\nx\n~~~")
    expect("table row", withStreamingCursor("| a | b |"), "| a | b |")
    expect("table delimiter", withStreamingCursor("a | b\n--- | ---"), "a | b\n--- | ---")
    expect("table delimiter aligned", withStreamingCursor("| a | b |\n|:--|--:|"), "| a | b |\n|:--|--:|")
    expect("hr", withStreamingCursor("文\n\n---"), "文\n\n---")
    expect("setext underline", withStreamingCursor("标题\n==="), "标题\n===")
    expect("block math", withStreamingCursor("$$"), "$$")
    expect("html block", withStreamingCursor("<div>"), "<div>")
    expect("inline math open", withStreamingCursor("设 \$x"), "设 \$x$c")
    expect("code span open", withStreamingCursor("调用 `foo"), "调用 `foo$c")
    expect("emphasis open", withStreamingCursor("这是 **重"), "这是 **重$c")
    expect("link open", withStreamingCursor("见 [文档]("), "见 [文档]($c")
    expect("text with a pipe mid-line", withStreamingCursor("a | b"), "a | b$c")
    expect("indented fence", withStreamingCursor("1. x\n   ```"), "1. x\n   ```")
    expect("idempotent shape", withStreamingCursor(withStreamingCursor("你好").removeSuffix(c)), "你好$c")
    if (failures > 0) {
        println("$failures failure(s)")
        exitProcess(1)
    }
    // 成功行必须是 runner 认的那一句（`harness: OK`）：纯检查这一关的判定就是它在不在，
    // 不是退出码 —— 而 `exit 0` 也可能是"什么都没跑"。
    println("harness: OK ($checks checks)")
}
