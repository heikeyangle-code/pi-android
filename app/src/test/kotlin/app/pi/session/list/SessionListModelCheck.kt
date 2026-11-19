package app.pi.session.list

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// 会话列表行文字 vs pi 官方 `SessionSelectorComponent` 的真渲染。
// 标准答案 app/src/test/resources/pi-session-list-fixtures/list.json 由
// tools/collect-session-list-fixtures.mjs 生成；这里把同一批会话喂给 Kotlin 的规则，逐行比对。
private var failures = 0
private var passes = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passes++ else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

fun main(args: Array<String>) {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    val file = File(root, "app/src/test/resources/pi-session-list-fixtures/list.json")
    if (!file.isFile) { println("FAIL 标准答案缺失：$file"); exitProcess(1) }
    val g = Json.parseToJsonElement(file.readText()).jsonObject
    class S(val path: String, val name: String?, val first: String, val count: Int, val ago: Long, val cwd: String)
    val byPath = g["sessions"]!!.jsonArray.associate {
        val o = it.jsonObject
        val cwd = o["cwd"]!!.jsonPrimitive.content
        val short = if (cwd == "/home/u") "~" else if (cwd.startsWith("/home/u/")) "~" + cwd.removePrefix("/home/u") else cwd
        val path = o["path"]!!.jsonPrimitive.content
        path to S(path, o["name"]?.takeIf { n -> n.jsonPrimitive.isString }?.jsonPrimitive?.content, o["firstMessage"]!!.jsonPrimitive.content,
            o["messageCount"]!!.jsonPrimitive.int, o["agoMs"]!!.jsonPrimitive.long, short)
    }
    for (c in g["cases"]!!.jsonArray) {
        val co = c.jsonObject
        val name = co["name"]!!.jsonPrimitive.content
        val showCwd = co["scope"]!!.jsonPrimitive.content == "all"
        val paths = co["paths"]!!.jsonArray.map { it.jsonPrimitive.content }
        val nodes = co["nodes"]!!.jsonArray.map { it.jsonObject }
        val rows = co["rows"]!!.jsonArray.map { it.jsonObject }
        check("$name 行数", rows.size, paths.size)
        for (i in rows.indices.take(paths.size)) {
            val s = byPath[paths[i]]!!
            val n = nodes[i]
            val prefix = treePrefix(n["depth"]!!.jsonPrimitive.int, n["ancestorContinues"]!!.jsonArray.map { it.jsonPrimitive.boolean }, n["isLast"]!!.jsonPrimitive.boolean)
            check("$name #$i 前缀", prefix, rows[i]["prefix"]!!.jsonPrimitive.content)
            check("$name #$i 文本", displayText(s.name, s.first), rows[i]["text"]!!.jsonPrimitive.content)
            check("$name #$i 右侧", rightText(s.cwd, s.count, s.ago, showCwd), rows[i]["right"]!!.jsonPrimitive.content)
        }
    }
    // 年龄档位边界（官方 formatSessionDate 的分段）
    check("59s", formatSessionDate(59_000), "now"); check("1m", formatSessionDate(60_000), "1m")
    check("59m", formatSessionDate(59 * 60_000L), "59m"); check("1h", formatSessionDate(3_600_000), "1h")
    check("6d", formatSessionDate(6 * 86_400_000L), "6d"); check("1w", formatSessionDate(7 * 86_400_000L), "1w")
    check("4w", formatSessionDate(29 * 86_400_000L), "4w"); check("1mo", formatSessionDate(30 * 86_400_000L), "1mo")
    check("12mo", formatSessionDate(364 * 86_400_000L), "12mo"); check("1y", formatSessionDate(365 * 86_400_000L), "1y")
    println(if (failures == 0) "harness: OK (session-list-model, $passes checks)" else "$failures FAILED")
    if (failures != 0) exitProcess(1)
}
