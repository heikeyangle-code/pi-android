package app.pi.session.tree

import app.pi.rpc.PiJson
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode
import app.pi.rpc.parseSessionEntry
import app.pi.rpc.parseSessionTreeNode
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 会话树视图模型 vs pi 官方 `/tree` 组件。
//
// 标准答案 `app/src/test/resources/pi-tree-fixtures/tree.json` 由 `tools/collect-tree-fixtures.mjs`
// 用 SDK 自己的 TreeSelectorComponent 生成（可见行、顺序、缩进、连线、竖线、整行文字、光标落点），
// 这里把同样的树喂给 Kotlin 的 TreeModel，逐行比对。不是「我觉得 SDK 是这样」，是「SDK 说它是这样」。
private var failures = 0
private var passes = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passes++
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

private fun filterOf(wire: String) = TreeFilter.entries.first { it.wire == wire }

private data class Row(val id: String, val indent: Int, val connector: Boolean, val last: Boolean, val virtualRoot: Boolean, val gutters: List<Pair<Int, Boolean>>, val foldable: Boolean, val line: String)

private fun goldenRows(arr: JsonArray) = arr.map {
    val o = it.jsonObject
    Row(
        o["id"]!!.jsonPrimitive.content, o["indent"]!!.jsonPrimitive.int, o["showConnector"]!!.jsonPrimitive.boolean,
        o["isLast"]!!.jsonPrimitive.boolean, o["virtualRoot"]!!.jsonPrimitive.boolean,
        o["gutters"]!!.jsonArray.map { g -> g.jsonArray[0].jsonPrimitive.int to g.jsonArray[1].jsonPrimitive.boolean },
        o["foldable"]!!.jsonPrimitive.boolean, o["line"]!!.jsonPrimitive.content,
    )
}

private fun goldenCheck(root: File) {
    val file = File(root, "app/src/test/resources/pi-tree-fixtures/tree.json")
    if (!file.isFile) { failures++; println("FAIL 标准答案缺失：$file（node tools/collect-tree-fixtures.mjs --pi <pi 目录>）"); return }
    val g = Json.parseToJsonElement(file.readText()).jsonObject
    val now = LocalDateTime.ofInstant(Instant.parse(g["now"]!!.jsonPrimitive.content), ZoneOffset.UTC)
    val home = g["home"]!!.jsonPrimitive.content
    var scenarios = 0
    for (fx in g["fixtures"]!!.jsonArray) {
        val fo = fx.jsonObject
        val name = fo["name"]!!.jsonPrimitive.content
        val leaf = fo["leafId"]!!.jsonPrimitive.content
        val tree = fo["tree"]!!.jsonArray.map { parseSessionTreeNode(it.jsonObject) }
        for (sc in fo["scenarios"]!!.jsonArray) {
            val so = sc.jsonObject
            val filter = filterOf(so["filter"]!!.jsonPrimitive.content)
            val query = so["query"]!!.jsonPrimitive.content
            val folded = so["folded"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val stamps = so["timestamps"]!!.jsonPrimitive.boolean
            val label = "$name / ${filter.wire} / q='$query' / folded=$folded / ts=$stamps"
            val want = goldenRows(so["rows"]!!.jsonArray)
            val view = TreeModel.view(tree, leaf, filter, query, folded, home)
            val got = view.rows.map {
                Row(
                    it.item.id, it.indent, it.showConnector, it.isLast, it.isVirtualRootChild, it.gutters.map { g2 -> g2.position to g2.show },
                    it.item.foldable, it.plainLine(stamps, now, ZoneOffset.UTC).trimEnd(),
                )
            }
            scenarios++
            check("$label：可见行的 id 与顺序", got.map { it.id }, want.map { it.id })
            if (got.map { it.id } == want.map { it.id }) {
                for ((i, w) in want.withIndex()) check("$label：第 ${i + 1} 行（${w.id}）", got[i], w)
            }
            val nearest = so["nearest"]
            if (nearest is JsonPrimitive && nearest.isString) check("$label：光标落点", view.nearestToLeafId, nearest.content)
        }
    }
    for (t in g["labelTimestamps"]!!.jsonArray) {
        val o = t.jsonObject
        check("标签时间 ${o["iso"]!!.jsonPrimitive.content}", TreeModel.formatLabelTimestamp(o["iso"]!!.jsonPrimitive.content, now, ZoneOffset.UTC), o["text"]!!.jsonPrimitive.content)
    }
    println("  对照 pi ${g["engineVersion"]!!.jsonPrimitive.content}：${g["fixtures"]!!.jsonArray.size} 棵树、$scenarios 个场景")
}

// ---- 下面几项 SDK 组件不直接给出（是 App 在它之上的判断），单独钉住 ----
private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun entry(json: String): SessionEntry = parseSessionEntry(PiJson.parseObjectOrNull(json)!!)
private fun q(s: String?) = s?.let { "\"$it\"" } ?: "null"
private fun user(id: String, parent: String?, text: String) = entry("""{"type":"message","id":"$id","parentId":${q(parent)},"timestamp":"t","message":{"role":"user","content":[{"type":"text","text":"${esc(text)}"}],"timestamp":1}}""")
private fun asst(id: String, parent: String?, text: String) = entry("""{"type":"message","id":"$id","parentId":${q(parent)},"timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"${esc(text)}"}],"stopReason":"stop","timestamp":2}}""")
private fun node(e: SessionEntry, vararg kids: SessionTreeNode, label: String? = null) = SessionTreeNode(e, kids.toList(), label, null)

fun main() {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    goldenCheck(root)

    // 长会话：5000 条直线不爆栈、同一列
    var chain: SessionTreeNode? = null
    for (i in 4999 downTo 0) {
        val e = if (i % 2 == 0) user("n$i", if (i == 0) null else "n${i - 1}", "m$i") else asst("n$i", "n${i - 1}", "r$i")
        chain = node(e, *listOfNotNull(chain).toTypedArray())
    }
    val longView = TreeModel.view(listOf(chain!!), "n4999", TreeFilter.All, "", emptySet())
    check("5000 条直线：全部可见、没爆栈", longView.rows.size, 5000)
    check("直线所有行同一列", longView.rows.map { it.indent }.toSet(), setOf(0))

    // 条目的动作语义（SDK navigateTree / getUserMessagesForForking）
    val t = node(
        user("u1", null, "问"),
        node(asst("a1", "u1", "答"), node(entry("""{"type":"custom_message","id":"cm","parentId":"a1","timestamp":"t","customType":"x","content":"c","display":true}"""))),
    )
    val items = TreeModel.view(listOf(t), "cm", TreeFilter.All, "", emptySet()).rows.map { it.item }.associateBy { it.id }
    check("用户消息：可分叉、跳转会退到它之前", items["u1"]!!.forkable to items["u1"]!!.rewinds, true to true)
    check("助手消息：不分叉、跳转就是它自己", items["a1"]!!.forkable to items["a1"]!!.rewinds, false to false)
    check("扩展消息：不分叉，但跳转同样退到之前", items["cm"]!!.forkable to items["cm"]!!.rewinds, false to true)
    check("复制文字", items["u1"]!!.copyText to items["cm"]!!.copyText, "问" to "c")
    check("当前位置 / 活动路径", items.values.map { it.id to (it.onPath to it.isLeaf) }.toSet(), setOf("u1" to (true to false), "a1" to (true to false), "cm" to (true to true)))

    // 被 context_edit 隐藏的条目
    val ce = node(
        user("u", null, "x"),
        node(asst("a", "u", "y")),
        node(entry("""{"type":"context_edit","id":"e","parentId":"u","timestamp":"t","targetId":"a","replacement":null}""")),
    )
    val hidden = TreeModel.view(listOf(ce), "a", TreeFilter.All, "", emptySet()).rows.associate { it.item.id to it.item.hiddenFromContext }
    check("被省略的消息标记为隐藏出上下文", hidden, mapOf("u" to false, "a" to true, "e" to false))

    // 过滤名与设置值
    check("设置值 → 过滤器", listOf("default", "no-tools", "user-only", "labeled-only", "all", "bogus", null).map { TreeFilter.fromWire(it).wire }, listOf("default", "no-tools", "user-only", "labeled-only", "all", "default", "default"))

    if (failures > 0) { println("tree-model: $failures FAILED / ${passes + failures}"); exitProcess(1) }
    println("harness: OK (tree-model — all $passes checks passed)")
}
