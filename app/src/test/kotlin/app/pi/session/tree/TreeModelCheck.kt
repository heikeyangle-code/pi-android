package app.pi.session.tree

import app.pi.rpc.PiJson
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode
import app.pi.rpc.parseSessionEntry
import kotlin.system.exitProcess

// 会话树视图模型：脊线 + 侧枝布局、三种过滤、搜索、活动路径、上下文编辑标记。纯函数，真跑。
private var failures = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun entry(json: String): SessionEntry = parseSessionEntry(PiJson.parseObjectOrNull(json)!!)
private fun user(id: String, parent: String?, text: String) = entry("""{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" } ?: "null"},"timestamp":"t","message":{"role":"user","content":[{"type":"text","text":"${esc(text)}"}],"timestamp":1}}""")
private fun asst(id: String, parent: String?, text: String, tools: List<String> = emptyList()) = entry(
    """{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" } ?: "null"},"timestamp":"t","message":{"role":"assistant","content":[""" +
        (listOfNotNull(if (text.isNotEmpty()) """{"type":"text","text":"${esc(text)}"}""" else null) + tools.map { """{"type":"toolCall","id":"c","name":"$it","arguments":{}}""" }).joinToString(",") +
        """],"stopReason":"stop","timestamp":2}}""",
)
private fun toolRes(id: String, parent: String?) = entry("""{"type":"message","id":"$id","parentId":"$parent","timestamp":"t","message":{"role":"toolResult","toolCallId":"c","toolName":"read","content":[{"type":"text","text":"ok"}],"isError":false,"timestamp":3}}""")
private fun node(e: SessionEntry, vararg kids: SessionTreeNode, label: String? = null) = SessionTreeNode(e, kids.toList(), label, null)

private fun ids(v: TreeView) = v.rows.map { when (it) { is TreeRow.Node -> it.item.id; is TreeRow.Branch -> "⑂" + it.headId } }
private fun depths(v: TreeView) = v.rows.map { it.depth }

fun main() {
    // 1. 长直线不缩进、不爆栈
    var chain: SessionTreeNode? = null
    for (i in 4999 downTo 0) {
        val e = if (i % 2 == 0) user("n$i", if (i == 0) null else "n${i - 1}", "m$i") else asst("n$i", "n${i - 1}", "r$i")
        chain = node(e, *listOfNotNull(chain).toTypedArray())
    }
    val longView = TreeModel.view(listOf(chain!!), "n4999", TreeFilter.All, "", emptySet())
    check("5000 条直线：全部可见、没爆栈", longView.rows.size, 5000)
    check("直线所有行同一列（不随祖先数缩进）", depths(longView).toSet(), setOf(0))

    // 2. 分叉：主线 = 含当前位置的那支，侧枝折叠成一行，并排在主线继续之前
    //    u1 → a1 → { u2a → a2a(leaf), u2b → a2b → u3b }
    val tree = node(
        user("u1", null, "第一问"),
        node(asst("a1", "u1", "第一答"),
            node(user("u2a", "a1", "走这条"), node(asst("a2a", "u2a", "答A"))),
            node(user("u2b", "a1", "另一条 zebra"), node(asst("a2b", "u2b", "答B"), node(user("u3b", "a2b", "追问B")))),
        ),
    )
    val v = TreeModel.view(listOf(tree), "a2a", TreeFilter.Conversation, "", emptySet())
    check("侧枝折叠：先出侧枝入口，主线继续", ids(v), listOf("u1", "a1", "⑂u2b", "u2a", "a2a"))
    val br = v.rows[2] as TreeRow.Branch
    check("侧枝入口：条数、预览、未展开", Triple(br.nodeCount, br.preview, br.expanded), Triple(3, "另一条 zebra", false))
    check("侧枝入口缩进一级", br.depth, 1)
    val items = v.rows.filterIsInstance<TreeRow.Node>().map { it.item }
    check("活动路径与当前位置", items.map { it.id to (it.onPath to it.isLeaf) }, listOf("u1" to (true to false), "a1" to (true to false), "u2a" to (true to false), "a2a" to (true to true)))
    check("分叉点记录孩子数", items.first { it.id == "a1" }.childCount, 2)
    check("只有用户消息能分叉", items.map { it.forkable }, listOf(true, false, true, false))

    // 3. 展开侧枝：内容缩进一级，位置在入口之后、主线之前
    val ex = TreeModel.view(listOf(tree), "a2a", TreeFilter.Conversation, "", setOf("u2b"))
    check("展开后的顺序", ids(ex), listOf("u1", "a1", "⑂u2b", "u2b", "a2b", "u3b", "u2a", "a2a"))
    check("展开后的层级：侧枝内 +1，主线回到原列", depths(ex), listOf(0, 0, 1, 1, 1, 1, 0, 0))
    check("入口标成已展开", (ex.rows[2] as TreeRow.Branch).expanded, true)

    // 4. 当前位置不在任何一支里 → 主线取最后一个孩子（最新的）
    val noLeaf = TreeModel.view(listOf(tree), null, TreeFilter.All, "", emptySet())
    check("没有当前位置：主线是最后一个孩子", ids(noLeaf).drop(2).first(), "⑂u2a")

    // 5. 过滤
    val rich = node(
        user("r1", null, "问"),
        node(asst("r2", "r1", "", listOf("read")),
            node(toolRes("r3", "r2"),
                node(entry("""{"type":"model_change","id":"r4","parentId":"r3","timestamp":"t","provider":"p","modelId":"m"}"""),
                    node(entry("""{"type":"usage","id":"r5","parentId":"r4","timestamp":"t","kind":"cache_warm","provider":"p","model":"m","usage":{"totalTokens":120}}"""),
                        node(entry("""{"type":"context_edit","id":"r6","parentId":"r5","timestamp":"t","targetId":"r1","replacement":null}"""),
                            node(asst("r7", "r6", "最后"), label = "里程碑"),
                        ),
                    ),
                    label = null,
                ),
            ),
        ),
    )
    check("对话视图：只留对话 + 带标签的 + 当前位置", ids(TreeModel.view(listOf(rich), "r7", TreeFilter.Conversation, "", emptySet())), listOf("r1", "r2", "r7"))
    check("全部视图：含工具结果、模型切换、用量、上下文编辑", ids(TreeModel.view(listOf(rich), "r7", TreeFilter.All, "", emptySet())), listOf("r1", "r2", "r3", "r4", "r5", "r6", "r7"))
    val bm = TreeModel.view(listOf(rich), "r7", TreeFilter.Bookmarks, "", emptySet())
    check("书签视图：只有带标签的（当前位置恰是它）", ids(bm), listOf("r7"))
    check("书签计数", bm.bookmarkCount, 1)
    val all = TreeModel.view(listOf(rich), "r7", TreeFilter.All, "", emptySet()).rows.filterIsInstance<TreeRow.Node>().map { it.item }.associateBy { it.id }
    check("上下文编辑把目标标成「不再发给模型」", all["r1"]?.hiddenFromContext, true)
    check("别的条目没被牵连", all["r2"]?.hiddenFromContext, false)
    check("SDK 1.5 的 usage / context_edit 认得出来，不是未知", all["r5"]?.kind to all["r6"]?.kind, TreeKind.Usage to TreeKind.ContextEdit)
    check("usage 文字带 kind 与 tokens", all["r5"]?.text, "cache_warm · m · 120 tokens")
    check("上下文编辑文字", all["r6"]?.text, "不再发给模型 → r1")
    check("只有消息能被编辑上下文", all.values.filter { it.contextEditable }.map { it.id }.sorted(), listOf("r1", "r2", "r3", "r7"))
    check("助手只调工具时的文字", all["r2"]?.text, "调用 read")
    check("标签文字来自 SDK 解析后的 label", all["r7"]?.label, "里程碑")

    // 6. 搜索：全部词命中、展开全部、平铺
    val sv = TreeModel.view(listOf(tree), "a2a", TreeFilter.Conversation, "zebra  另一条", emptySet())
    check("搜索只留命中的行，且自动展开侧枝", ids(sv), listOf("u2b"))
    check("搜索结果平铺", depths(sv), listOf(0))
    check("多个词必须全部命中", ids(TreeModel.view(listOf(tree), "a2a", TreeFilter.Conversation, "zebra 不存在的词", emptySet())).filter { it != "a2a" }, emptyList<String>())
    check("搜索不区分大小写", ids(TreeModel.view(listOf(tree), "a2a", TreeFilter.All, "ZEBRA", emptySet())), listOf("u2b"))

    // 7. 活动路径遇到环不死循环
    val a = node(user("x", "y", "x")); val b = node(user("y", "x", "y"))
    check("parentId 成环也能停", TreeModel.activePath(mapOf("x" to a, "y" to b), "x"), setOf("x", "y"))

    // 8. 空树
    check("空树", TreeModel.view(emptyList(), null, TreeFilter.All, "", emptySet()).rows, emptyList<TreeRow>())

    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (tree-model)")
}
