package app.pi.packages

// Pure-logic checks for `PiMcpStatus`（解析 `pi mcp list --json`）。跑法见 tools/run-app-pure-checks.sh。

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name") else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val OUT = """
(node:1) ExperimentalWarning: noise
{"servers":[
 {"name":"fs","scope":"global","enabled":true,"exposure":"codemode","transport":"npx x","state":"connected","tools":["read","write"],"toolExposure":{"write":"hidden"},"resources":true},
 {"name":"docs","scope":"project","enabled":true,"state":"needs-auth","tools":[]},
 {"name":"bad","enabled":true,"state":"failed","tools":[],"error":"spawn ENOENT"},
 {"name":"off","enabled":false,"state":"disabled","tools":[]}],
 "errors":["entry x invalid"],"note":"project untrusted"}
"""

fun main() {
    val r = PiMcpStatus.parse(OUT)!!
    check("count", r.servers.size, 4)
    check("connected", r.of("fs")!!.connected, true)
    check("tools", r.of("fs")!!.tools, listOf("read", "write"))
    check("tool exposure", r.of("fs")!!.toolExposure, mapOf("write" to "hidden"))
    check("resources", r.of("fs")!!.resources, true)
    check("needs auth label", PiMcpStatus.stateLabel(r.of("docs")!!), "需要登录")
    check("failed label", PiMcpStatus.stateLabel(r.of("bad")!!), "连接失败")
    check("error", r.of("bad")!!.error, "spawn ENOENT")
    check("disabled label", PiMcpStatus.stateLabel(r.of("off")!!), "已停用")
    check("connected label", PiMcpStatus.stateLabel(r.of("fs")!!), "已连接 · 2 个工具")
    check("errors/note", r.errors to r.note, listOf("entry x invalid") to "project untrusted")
    check("garbage", PiMcpStatus.parse("boom"), null)
    check("scope default", r.of("bad")!!.scope, "global")
    if (failures > 0) { println("$failures failure(s)"); kotlin.system.exitProcess(1) }
}
