package app.pi.ui.chat

import kotlin.system.exitProcess

private var failures = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name") else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

fun main() {
    val entries = listOf(
        EntryText("u1", "user", listOf("你好")),
        EntryText("a1", "assistant", listOf("第一段", "第二段")),
        EntryText("u2", "user", listOf("你好")),
        EntryText("a2", "assistant", listOf("好的")),
    )
    check("history key is the id", MessageEntryResolver.resolve(entries, "u1", "user", "你好"), "u1")
    check("block suffix is stripped", MessageEntryResolver.resolve(entries, "a1-1", "assistant", "第二段"), "a1")
    check("synthetic key falls back to the NEWEST matching text", MessageEntryResolver.resolve(entries, "user-1700-3", "user", "你好"), "u2")
    check("role must match even when the key hits", MessageEntryResolver.resolve(entries, "u1", "assistant", "你好"), null)
    check("assistant text matches one block", MessageEntryResolver.resolve(entries, "assistant-1700-4", "assistant", "第一段"), "a1")
    check("joined blocks match", MessageEntryResolver.resolve(entries, "assistant-1700-5", "assistant", "第一段\n第二段"), "a1")
    check("unknown text is unresolved", MessageEntryResolver.resolve(entries, "user-1700-6", "user", "没发过"), null)
    check("empty text never matches by text", MessageEntryResolver.resolve(entries, "user-1700-7", "user", ""), null)
    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (message-entry-resolver)")
}
