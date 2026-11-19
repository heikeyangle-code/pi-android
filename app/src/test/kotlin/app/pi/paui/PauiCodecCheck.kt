package app.pi.paui

// Pure-logic checks for `PauiCodec`（PAUI v1 的解析、降级、限额、ANSI 清洗）。跑法见 tools/run-app-pure-checks.sh。

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name") else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun ok(json: String) = (PauiCodec.parse(json) as PauiCodec.Result.Ok).tree

fun main() {
    val page = ok(
        """{"type":"column","children":[
          {"type":"text","text":"资源","tone":"accent","bold":true},
          {"type":"toggle","id":"t1","label":"mcp","checked":true},
          {"type":"list","id":"l","items":[{"id":"a","title":"A","detail":"d","selected":true},{"title":"no id"}]},
          {"type":"progress","value":"1.7"}]}""",
    )
    val col = page.root as PauiNode.Column
    check("column children", col.children.size, 4)
    check("tone token", (col.children[0] as PauiNode.Text).tone, PauiNode.Tone.Accent)
    check("toggle", (col.children[1] as PauiNode.Toggle).checked, true)
    check("list drops item without id", (col.children[2] as PauiNode.ListNode).items.map { it.id }, listOf("a"))
    check("progress clamped", (col.children[3] as PauiNode.Progress).fraction, 1f)
    check("no unsupported", page.unsupported, emptyList<String>())

    // 未知类型：有 fallback 用 fallback，没有就是 Unsupported，整页仍可渲染
    val fb = ok("""{"type":"column","children":[{"type":"hologram","fallback":{"type":"text","text":"plain"}},{"type":"sparkle"}]}""")
    val kids = (fb.root as PauiNode.Column).children
    check("fallback used", (kids[0] as PauiNode.Text).text, "plain")
    check("unsupported placeholder", kids[1], PauiNode.Unsupported(null, "sparkle"))
    check("unsupported reported", fb.unsupported, listOf("hologram", "sparkle"))

    // 限额
    var deep = """{"type":"text","text":"x"}"""
    repeat(30) { deep = """{"type":"box","children":[$deep]}""" }
    check("too deep rejected", PauiCodec.parse(deep) is PauiCodec.Result.Rejected, true)
    val many = """{"type":"column","children":[${List(2100) { """{"type":"divider"}""" }.joinToString(",")}]}"""
    check("too many nodes rejected", PauiCodec.parse(many) is PauiCodec.Result.Rejected, true)
    check("bad json rejected", PauiCodec.parse("{oops") is PauiCodec.Result.Rejected, true)
    check("unknown fields ignored", ok("""{"type":"divider","future":{"a":1}}""").root, PauiNode.Divider(null))

    // ANSI 清洗：保留 SGR 和 OSC8，丢掉光标/清屏/改标题
    val esc = "\u001b"
    check("keeps SGR", PauiCodec.sanitizeAnsi("${esc}[38;5;5mA${esc}[39m"), "${esc}[38;5;5mA${esc}[39m")
    check("drops cursor move", PauiCodec.sanitizeAnsi("a${esc}[2Jb${esc}[10;5Hc"), "abc")
    check("drops title OSC", PauiCodec.sanitizeAnsi("x${esc}]0;evil\u0007y"), "xy")
    check("keeps OSC8 link", PauiCodec.sanitizeAnsi("${esc}]8;;https://a.b\u0007t${esc}]8;;\u0007"), "${esc}]8;;https://a.b\u0007t${esc}]8;;\u0007")
    check("plain text", PauiCodec.plain("${esc}[31mred${esc}[0m"), "red")
    if (failures > 0) { println("$failures failure(s)"); kotlin.system.exitProcess(1) }
}
