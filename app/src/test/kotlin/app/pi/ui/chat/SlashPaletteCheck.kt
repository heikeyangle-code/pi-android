package app.pi.ui.chat

import app.pi.rpc.PiResponses

// 命令面板的纯逻辑（`PiSlashCommands.kt`：builtinRows / piCommandPalette / filterPalette）。
// 对照的官方规则：自动补全列表顺序 = 内置 → 模板 → 扩展 → 技能（interactive-mode.js
// `setupAutocompleteProvider`）；`/model <引用>` 的参数提示不显示（点一下直接执行）。
// 面板的其余规则是 App 的呈现：已知做不了的内建命令不占列表行、敲全名时才出现；说明里出现关键词的排在名字命中之后。

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name\n  actual:   $actual\n  expected: $expected")
    }
}

private fun wire(name: String, description: String? = null) = PiResponses.BuiltinCommand(name, description, null)

private fun ext(name: String, description: String?, source: PiResponses.CommandSource) =
    PiResponses.SlashCommand(name, description, source, null, null, null)

fun main() {
    val builtins = builtinRows(
        listOf(wire("model"), wire("share", "Share session"), wire("quit"), wire("brand-new", "pi added this")),
    )
    check("known-unsupported built-ins are not listed", builtins.filter { !it.listed }.map { it.name }, listOf("share", "quit"))
    check("a built-in pi added later is still listed (as unsupported, not silently missing)",
        builtins.first { it.name == "brand-new" }.let { it.listed to it.action }, true to ChatAction.Unsupported)
    check("/model maps to the picker", builtins.first { it.name == "model" }.action, ChatAction.PickModel)

    val palette = piCommandPalette(
        commands = listOf(
            ext("review", "Review code", PiResponses.CommandSource.Extension),
            ext("skill:pdf", "Work with PDF files", PiResponses.CommandSource.Skill),
            ext("summarize", "Summarize the model's output", PiResponses.CommandSource.Prompt),
        ),
        builtins = builtins,
    )
    check("group order is the TUI's: built-in, template, extension, skill",
        palette.map { it.source }.distinct(),
        listOf(PiCommandSource.Builtin, PiCommandSource.Prompt, PiCommandSource.Extension, PiCommandSource.Skill))

    check("empty query lists only listed rows", filterPalette(palette, "").none { it.name == "share" || it.name == "quit" }, true)
    check("full name shows an unlisted built-in", filterPalette(palette, "/share").map { it.name }, listOf("share"))
    check("a partial name does not", filterPalette(palette, "sha").any { it.name == "share" }, false)

    // 排序：前缀 → 包含 → 说明；这里用 `su`：summarize 是前缀命中，没有其它名字命中。
    check("prefix hit", filterPalette(palette, "su").map { it.name }, listOf("summarize"))
    check("description-only hit comes after name hits",
        filterPalette(palette, "model").map { it.name }, listOf("model", "summarize"))
    check("matching is case-insensitive", filterPalette(palette, "REV").map { it.name }, listOf("review"))
    val sources = filterPalette(palette, "s").map { it.source }
    val runs = sources.zipWithNext().count { (a, b) -> a != b } + 1
    check("same source stays adjacent after filtering (group headers rely on it)", runs, sources.distinct().size)

    if (failures > 0) {
        println("\n$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("harness: OK (slash-palette)")
}
