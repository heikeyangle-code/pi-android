package app.pi.session.tree

import app.pi.rpc.PiContentBlock
import app.pi.rpc.PiMessage
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode

/**
 * 会话树的**视图模型**：纯函数，没有 Android、没有协程，所以能在没有手机的机器上真跑
 * （`tree-model` harness）。界面（`SessionTreeView`）只负责把 [TreeRow] 画出来。
 *
 * ## 为什么重做（旧 `SessionTreeScreen` 的问题）
 *
 * 旧页把树按**祖先数**缩进：一条 300 轮的直线对话缩进 300 级，于是被迫再造一套「缩进封顶」的补丁；
 * 兄弟分支全部平铺展开，分叉一多就看不出主线在哪；条目类型只认 RPC 时代那几种（SDK 1.5 的 `usage`、
 * `context_edit` 被当成「未知」）。这里换成**脊线 + 侧枝**的布局：
 *
 *  - **直线不缩进**：单子节点的链永远在同一列，300 轮就是 300 行，没有缩进。
 *  - **只有分叉点才产生层级**：分叉点下，**主线**（含当前位置的那一支；都不含则取最后一个孩子，即最新的）
 *    继续留在原来的列，**其余支**各自折成一行「⑂ N 条 · 开头预览」，点开才展开并多缩进一级。
 *  - 当前位置（叶）、活动路径（根→叶）、标签（书签）、被隐藏出模型上下文的条目，都是行上的**属性**，
 *    由界面决定怎么画。
 *
 * ## 三种过滤
 *  - [TreeFilter.Conversation]：只看对话（用户、助手、命令、压缩/分支摘要、扩展消息）+ 带标签的任何条目 + 当前位置。
 *  - [TreeFilter.Bookmarks]：只看带标签的条目 + 当前位置，平铺成一个书签列表（不显示分支结构）。
 *  - [TreeFilter.All]：所有条目，包括模型/思考档位变更、用量记录、上下文编辑。
 *
 * 搜索（多个词、全部命中，与 pi 的树选择器同一规则）会展开所有分支，并且只留命中的行。
 *
 * 全部遍历都用显式栈：会话可以有几千个条目，递归会爆栈。
 */
enum class TreeFilter(val label: String) {
    Conversation("对话"),
    Bookmarks("书签"),
    All("全部"),
}

enum class TreeKind(val glyph: String, val title: String, val conversational: Boolean) {
    User("你", "你", true),
    Assistant("π", "助手", true),
    ToolResult("⚙", "工具结果", false),
    Bash("$", "命令", true),
    Compaction("⇲", "压缩", true),
    BranchSummary("⑂", "分支摘要", true),
    CustomMessage("✦", "扩展消息", true),
    ModelChange("◇", "切换模型", false),
    Thinking("∿", "思考档位", false),
    Label("⚑", "标签", false),
    Usage("¤", "用量记录", false),
    ContextEdit("⊘", "上下文编辑", false),
    Custom("·", "扩展状态", false),
    Info("ⓘ", "会话信息", false),
    Unknown("?", "未知条目", false),
}

data class TreeItem(
    val id: String,
    val kind: TreeKind,
    /** 一两行的摘要文字。 */
    val text: String,
    val label: String?,
    val timestamp: String?,
    val onPath: Boolean,
    val isLeaf: Boolean,
    /** 被某条 `context_edit` 从模型上下文里隐藏了（对话页仍有原文）。 */
    val hiddenFromContext: Boolean,
    /** 能被「隐藏出上下文 / 改写」：只有消息类条目可以。 */
    val contextEditable: Boolean,
    /** 能「从这里分叉」：用户消息（与 SDK 的 `getUserMessagesForForking` 一致）。 */
    val forkable: Boolean,
    val childCount: Int,
)

sealed interface TreeRow {
    /** 视觉缩进层级：只有「展开的侧枝」会 +1。 */
    val depth: Int

    data class Node(val item: TreeItem, override val depth: Int) : TreeRow

    /** 折叠/展开的侧枝入口。[headId] 是该支第一个节点的 id，也是展开状态的键。 */
    data class Branch(
        val headId: String,
        val nodeCount: Int,
        val preview: String,
        val expanded: Boolean,
        override val depth: Int,
    ) : TreeRow
}

data class TreeView(
    val rows: List<TreeRow>,
    /** 树里的条目总数 / 过滤后显示的条目数（界面给一句「显示 x / y」）。 */
    val totalNodes: Int,
    val shownNodes: Int,
    /** 书签（带标签的条目）总数，筛选 chip 上可以显示。 */
    val bookmarkCount: Int,
)

object TreeModel {

    fun view(
        roots: List<SessionTreeNode>,
        leafId: String?,
        filter: TreeFilter,
        query: String,
        expandedBranches: Set<String>,
    ): TreeView {
        val index = index(roots)
        val onPath = activePath(index, leafId)
        val hiddenIds = hiddenTargets(index)
        val tokens = query.lowercase().split(WS).filter { it.isNotEmpty() }
        val searching = tokens.isNotEmpty()

        fun itemOf(n: SessionTreeNode): TreeItem {
            val entry = n.entry
            val id = entry.id.orEmpty()
            val kind = kindOf(entry)
            return TreeItem(
                id = id,
                kind = kind,
                text = textOf(entry),
                label = n.label?.takeIf { it.isNotBlank() },
                timestamp = entry.timestamp,
                onPath = id in onPath,
                isLeaf = leafId != null && id == leafId,
                hiddenFromContext = id in hiddenIds,
                contextEditable = entry is SessionEntry.Message && entry.message.let { it is PiMessage.User || it is PiMessage.Assistant || it is PiMessage.ToolResult },
                forkable = entry is SessionEntry.Message && entry.message is PiMessage.User,
                childCount = n.children.size,
            )
        }

        // —— 先按「脊线 + 侧枝」排出全部行（不看过滤），再过滤节点行 ——
        val full = ArrayList<TreeRow>()
        val stack = ArrayDeque<Work>()
        for (r in roots.asReversed()) stack.addLast(Work.Visit(r, 0))
        while (stack.isNotEmpty()) {
            when (val w = stack.removeLast()) {
                is Work.Visit -> {
                    full.add(TreeRow.Node(itemOf(w.node), w.depth))
                    val kids = w.node.children
                    when {
                        kids.isEmpty() -> Unit
                        kids.size == 1 -> stack.addLast(Work.Visit(kids[0], w.depth))
                        else -> {
                            val main = kids.firstOrNull { (it.entry.id ?: "") in onPath } ?: kids.last()
                            val sides = kids.filter { it !== main }
                            // 入栈要反着来：先出「侧枝们」，最后才是主线继续。
                            stack.addLast(Work.Visit(main, w.depth))
                            for (side in sides.asReversed()) {
                                val headId = side.entry.id.orEmpty()
                                val open = searching || headId in expandedBranches
                                if (open) stack.addLast(Work.Visit(side, w.depth + 1))
                                stack.addLast(Work.Header(side, w.depth + 1, open))
                            }
                        }
                    }
                }
                is Work.Header -> full.add(
                    TreeRow.Branch(
                        headId = w.side.entry.id.orEmpty(),
                        nodeCount = subtreeSize(w.side),
                        preview = branchPreview(w.side),
                        expanded = w.open,
                        depth = w.depth,
                    ),
                )
            }
        }

        val totalNodes = full.count { it is TreeRow.Node }
        val bookmarks = full.count { it is TreeRow.Node && it.item.label != null }

        fun keep(item: TreeItem): Boolean {
            // 当前位置在任何过滤下都留着：用户得知道「我现在在哪」。
            if (item.isLeaf) return true
            return when (filter) {
                TreeFilter.All -> true
                TreeFilter.Conversation -> item.kind.conversational || item.label != null
                TreeFilter.Bookmarks -> item.label != null
            }
        }

        val matched = full.filter { row ->
            when (row) {
                is TreeRow.Node -> keep(row.item) && (!searching || matches(row.item, tokens))
                // 搜索时侧枝已全部展开，入口行没有意义；书签视图不画分支结构。
                is TreeRow.Branch -> !searching && filter != TreeFilter.Bookmarks
            }
        }
        // 书签视图：平铺（去掉缩进），否则保持脊线布局。
        val rows = if (filter == TreeFilter.Bookmarks || searching) {
            matched.map { if (it is TreeRow.Node) it.copy(depth = 0) else it }
        } else {
            matched
        }
        return TreeView(rows, totalNodes, rows.count { it is TreeRow.Node }, bookmarks)
    }

    // ---------------------------------------------------------------- 结构

    private sealed interface Work {
        class Visit(val node: SessionTreeNode, val depth: Int) : Work
        class Header(val side: SessionTreeNode, val depth: Int, val open: Boolean) : Work
    }

    private fun index(roots: List<SessionTreeNode>): Map<String, SessionTreeNode> {
        val out = HashMap<String, SessionTreeNode>()
        val stack = ArrayDeque<SessionTreeNode>()
        roots.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            n.entry.id?.let { out[it] = n }
            n.children.forEach(stack::addLast)
        }
        return out
    }

    /** 根→叶的条目 id 集合（沿 `parentId` 往上走）。叶不存在（空会话）就是空集。 */
    fun activePath(index: Map<String, SessionTreeNode>, leafId: String?): Set<String> {
        val out = LinkedHashSet<String>()
        var cur = leafId
        while (cur != null && out.add(cur)) cur = index[cur]?.entry?.parentId
        return out
    }

    /** 所有「被隐藏出模型上下文」的目标 id（`context_edit` 且 replacement 为 null）。 */
    private fun hiddenTargets(index: Map<String, SessionTreeNode>): Set<String> =
        index.values.mapNotNull { (it.entry as? SessionEntry.ContextEdit)?.takeIf { e -> e.omitted }?.targetId }.toSet()

    private fun subtreeSize(root: SessionTreeNode): Int {
        var n = 0
        val stack = ArrayDeque<SessionTreeNode>().also { it.addLast(root) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast(); n++
            c.children.forEach(stack::addLast)
        }
        return n
    }

    /** 侧枝的预览：沿第一个孩子一路走，取第一条用户消息的文字；没有就取头节点的文字。 */
    private fun branchPreview(head: SessionTreeNode): String {
        var cur: SessionTreeNode? = head
        var steps = 0
        while (cur != null && steps++ < 200) {
            val e = cur.entry
            if (e is SessionEntry.Message && e.message is PiMessage.User) return textOf(e)
            cur = cur.children.firstOrNull()
        }
        return textOf(head.entry)
    }

    private fun matches(item: TreeItem, tokens: List<String>): Boolean {
        val hay = (item.text + " " + item.label.orEmpty() + " " + item.kind.title).lowercase()
        return tokens.all { hay.contains(it) }
    }

    // ---------------------------------------------------------------- 条目 → 种类 / 文字

    fun kindOf(entry: SessionEntry): TreeKind = when (entry) {
        is SessionEntry.Message -> when (entry.message) {
            is PiMessage.User -> TreeKind.User
            is PiMessage.Assistant -> TreeKind.Assistant
            is PiMessage.ToolResult -> TreeKind.ToolResult
            is PiMessage.BashExecution -> TreeKind.Bash
            is PiMessage.BranchSummary -> TreeKind.BranchSummary
            is PiMessage.CompactionSummary -> TreeKind.Compaction
            is PiMessage.Custom -> TreeKind.CustomMessage
            else -> TreeKind.Unknown
        }
        is SessionEntry.Compaction -> TreeKind.Compaction
        is SessionEntry.BranchSummary -> TreeKind.BranchSummary
        is SessionEntry.CustomMessage -> TreeKind.CustomMessage
        is SessionEntry.Custom -> TreeKind.Custom
        is SessionEntry.ModelChange -> TreeKind.ModelChange
        is SessionEntry.ThinkingLevelChange -> TreeKind.Thinking
        is SessionEntry.Label -> TreeKind.Label
        is SessionEntry.SessionInfo -> TreeKind.Info
        is SessionEntry.Usage -> TreeKind.Usage
        is SessionEntry.ContextEdit -> TreeKind.ContextEdit
        is SessionEntry.Unknown -> TreeKind.Unknown
    }

    fun textOf(entry: SessionEntry): String = when (entry) {
        is SessionEntry.Message -> messageText(entry.message)
        is SessionEntry.Compaction -> firstLines(entry.summary).ifEmpty { "（无摘要）" }
        is SessionEntry.BranchSummary -> firstLines(entry.summary).ifEmpty { "（无摘要）" }
        is SessionEntry.CustomMessage -> entry.customType.orEmpty().ifEmpty { "扩展消息" }
        is SessionEntry.Custom -> entry.customType.orEmpty().ifEmpty { "扩展状态" }
        is SessionEntry.ModelChange -> "${entry.provider.orEmpty()}/${entry.modelId.orEmpty()}"
        is SessionEntry.ThinkingLevelChange -> entry.thinkingLevel.orEmpty()
        is SessionEntry.Label -> entry.label?.let { "标签「$it」→ ${entry.targetId.orEmpty().take(8)}" } ?: "清除标签 → ${entry.targetId.orEmpty().take(8)}"
        is SessionEntry.SessionInfo -> entry.name?.let { "会话改名为「$it」" } ?: "会话信息"
        is SessionEntry.Usage -> buildString {
            append(entry.kind ?: "usage")
            entry.model?.let { append(" · ").append(it) }
            entry.usage?.let { u -> u.totalTokens?.let { append(" · ").append(it).append(" tokens") } }
            entry.note?.let { append(" · ").append(it) }
        }
        is SessionEntry.ContextEdit -> if (entry.omitted) "不再发给模型 → ${entry.targetId.orEmpty().take(8)}" else "改写了模型看到的内容 → ${entry.targetId.orEmpty().take(8)}"
        is SessionEntry.Unknown -> entry.type
    }

    private fun messageText(m: PiMessage): String = when (m) {
        is PiMessage.User -> firstLines(m.text).ifEmpty { "（空消息）" }
        is PiMessage.Assistant -> {
            val text = firstLines(m.text)
            val tools = m.content.filterIsInstance<PiContentBlock.ToolCall>().mapNotNull { it.name }
            when {
                m.stopReason == "error" || !m.errorMessage.isNullOrBlank() -> "出错：" + (m.errorMessage ?: "").lineSequence().firstOrNull().orEmpty().take(120)
                text.isNotEmpty() && tools.isNotEmpty() -> text + "  → " + tools.distinct().joinToString("、")
                text.isNotEmpty() -> text
                tools.isNotEmpty() -> "调用 " + tools.distinct().joinToString("、")
                else -> "（思考中或空回复）"
            }
        }
        is PiMessage.ToolResult -> (m.toolName ?: "工具") + if (m.isError) " 出错" else " 完成"
        is PiMessage.BashExecution -> "$ " + (m.command ?: "").lineSequence().firstOrNull().orEmpty().take(120)
        is PiMessage.BranchSummary -> firstLines(m.summary)
        is PiMessage.CompactionSummary -> firstLines(m.summary)
        is PiMessage.Custom -> firstLines(m.text)
        else -> m.role
    }

    /** 前两行非空文字，合成一段，最长 160 字。 */
    private fun firstLines(s: String?): String =
        s.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" ").take(160)

    private val WS = Regex("\\s+")
}
