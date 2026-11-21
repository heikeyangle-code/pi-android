package app.pi.session.tree

import app.pi.protocol.PiContentBlock
import app.pi.protocol.PiMessage
import app.pi.protocol.SessionEntry
import app.pi.protocol.SessionTreeNode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话树的视图模型：纯函数，没有 Android、没有协程，所以能在没有手机的机器上真跑
 * （`tree-model` harness）。界面（`SessionTreeView`）只负责把 [TreeRow] 画出来。
 *
 * ## 这是 pi 官方 `/tree` 的逐项移植，不是另一套设计
 *
 * 对照的是 SDK 的 `TreeSelectorComponent` 里的 `TreeList`
 * （`dist/modes/interactive/components/tree-selector.js`），每一条规则都出自那个文件：
 *
 *  - **五种过滤**（`applyFilter`）：default / no-tools / user-only / labeled-only / all。
 *    default 隐藏「设置类」条目（label、context_edit、custom、model_change、thinking_level_change、
 *    session_info）；no-tools 再去掉工具结果；user-only 只留用户消息；labeled-only 只留带标签的；
 *    all 全留。**`usage` 条目在任何模式下都不显示**；**只有工具调用、没有文字的助手消息，在任何模式下
 *    都不显示**（除非它出错 / 被中止，或它就是当前位置）。
 *  - **结构按「可见的树」重算**（`recalculateVisualStructure`）：被过滤掉的条目，它的孩子挂到
 *    **最近的可见祖先**上；缩进、连线、竖线都按这棵可见树算，所以单链永远在同一列，只有分叉点才多一级。
 *  - **活动分支排在最前**（`flattenTree`）：含当前位置的那一支先出现，其余按原顺序（时间旧→新）。
 *  - **折叠**（`isFoldable` / `foldedNodes`）：一个节点有可见的孩子、且是根或「分叉点下的一支的开头」
 *    才能折叠；折叠后它的全部后代不显示。
 *  - **搜索**（`getSearchableText`）：空白分词、全部命中；折叠在搜索时清空。
 *  - **一行的文字**（`getEntryDisplayText` / `formatToolCall`）：工具结果显示成 `[read: 路径]` 这样的调用，
 *    而不是「工具完成」；标签 `[标签]`，活动路径上的行有 `• `。
 *  - **复制**（`getEntryCopyText`）。
 *
 * 全部遍历都用显式栈：会话可以有几千个条目，递归会爆栈。
 *
 * 唯一的差别是**画法**：SDK 在终端里用 `├─ │ └─` 画连线，这里 [TreeRow.prefix] 给的是**同一串字符**
 * （3 字符一级），界面用等宽字画出来；`tree-model` harness 用 SDK 自己的组件生成标准答案
 * （`tools/tree-golden.mjs`），逐行比对这里产出的整行文字。
 */
enum class TreeFilter(val wire: String, val label: String, val hint: String) {
    Default("default", "默认", "隐藏模型 / 思考档位 / 标签这类设置类条目"),
    NoTools("no-tools", "无工具", "默认，再去掉工具结果"),
    UserOnly("user-only", "仅用户", "只看你发的消息"),
    Labeled("labeled-only", "书签", "只看打了标签的条目"),
    All("all", "全部", "除用量记录外的所有条目"),
    ;

    companion object {
        /** pi 的 `settings.treeFilterMode`；非法值回到 default（与 `getTreeFilterMode` 一致）。 */
        fun fromWire(wire: String?): TreeFilter = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** 行首标记 / 正文的着色语义；界面把它们映射成主题色。 */
enum class TreeTone { Plain, User, Assistant, Muted, Dim, Error, Warning, Accent, Custom }

data class TreeGutter(val position: Int, val show: Boolean)

data class TreeItem(
    val id: String,
    /** 条目类型的人话名（动作面板的标题）。 */
    val typeTitle: String,
    /** SDK 一行里的前缀（`user: `、`assistant: `、`[branch summary]: `…），没有就空串；含结尾空格。 */
    val head: String,
    val headTone: TreeTone,
    val text: String,
    val textTone: TreeTone,
    val label: String?,
    val labelTimestamp: String?,
    val onPath: Boolean,
    val isLeaf: Boolean,
    /** 被某条 `context_edit` 从模型上下文里隐藏了（对话页仍有原文）。 */
    val hiddenFromContext: Boolean,
    /** 能被「隐藏出上下文 / 改写」：消息类条目。 */
    val contextEditable: Boolean,
    /** 能「从这里分叉」：有文字的用户消息（与 SDK 的 `getUserMessagesForForking` 一致）。 */
    val forkable: Boolean,
    /**
     * 跳到它会**回到它之前**并把它的文字放回输入框（SDK `navigateTree`：用户消息 / custom_message 的
     * 新叶子是它的父节点，`editorText` 是它的文字）；其余条目的新叶子就是它自己。
     */
    val rewinds: Boolean,
    /** SDK `getEntryCopyText`：没有可复制的文字就是 null。 */
    val copyText: String?,
    val foldable: Boolean,
    val folded: Boolean,
)

data class TreeRow(
    val item: TreeItem,
    val indent: Int,
    val showConnector: Boolean,
    val isLast: Boolean,
    val gutters: List<TreeGutter>,
    val isVirtualRootChild: Boolean,
    /** SDK 的行前缀：每级 3 个字符，竖线 / `├─` / `└─` / 折叠标记 `⊟ ⊞`。 */
    val prefix: String,
    /** 没有连线的行（根）被折叠时，SDK 在前缀后单独画 `⊞ `。 */
    val foldMarker: Boolean,
) {
    /**
     * 这一行在 SDK 里的整行文字（不含最前面的光标列），与 `TreeList.render` 剥掉颜色后的一行逐字相同。
     * 只给 harness 和「复制成文字」用；界面分段着色，不用它。
     */
    fun plainLine(showLabelTimestamps: Boolean = false, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(prefix)
        if (foldMarker) append("⊞ ")
        if (item.onPath) append("• ")
        item.label?.let { append('[').append(it).append("] ") }
        if (showLabelTimestamps && item.label != null && item.labelTimestamp != null) {
            append(TreeModel.formatLabelTimestamp(item.labelTimestamp, now, zone)).append(' ')
        }
        append(item.head).append(item.text)
    }
}

data class TreeView(
    val rows: List<TreeRow>,
    /** 树里的条目总数（含被过滤掉的，不含 usage）/ 过滤后显示的行数。 */
    val totalEntries: Int,
    /** 光标该落的行：当前位置本身，若被过滤掉就是最近的可见祖先（SDK `findNearestVisibleIndex`）。 */
    val nearestToLeafId: String?,
    /** 书签（带标签的条目）总数。 */
    val bookmarkCount: Int,
)

object TreeModel {

    fun view(
        roots: List<SessionTreeNode>,
        leafId: String?,
        filter: TreeFilter,
        query: String,
        folded: Set<String> = emptySet(),
        /** 工具调用里的路径按这个家目录缩成 `~/…`（SDK `shortenHomePath`）；不知道就别缩。 */
        homeDir: String? = null,
    ): TreeView {
        val flat = flatten(roots, leafId)
        val byId = HashMap<String, Flat>(flat.size * 2)
        for (f in flat) byId[f.id] = f
        val onPath = activePath(byId, leafId)
        val hiddenIds = hiddenTargets(flat)
        val toolCalls = HashMap<String, Pair<String, JsonObject?>>()
        for (f in flat) {
            val m = (f.node.entry as? SessionEntry.Message)?.message as? PiMessage.Assistant ?: continue
            for (b in m.content) if (b is PiContentBlock.ToolCall && b.id != null) toolCalls[b.id!!] = (b.name ?: "") to b.arguments
        }

        // ---- applyFilter ----
        val tokens = query.lowercase().split(WS).filter { it.isNotEmpty() }
        var filtered = flat.filter { f -> passes(f, leafId, filter, tokens) }

        // 折叠：被折叠节点（沿原树 parentId）的全部后代不显示。
        if (folded.isNotEmpty()) {
            val skip = HashSet<String>()
            for (f in flat) {
                val p = f.parentId
                if (p != null && (p in folded || p in skip)) skip.add(f.id)
            }
            filtered = filtered.filter { it.id !in skip }
        }

        // ---- recalculateVisualStructure ----
        val visibleIds = HashSet<String>(filtered.size * 2)
        for (f in filtered) visibleIds.add(f.id)
        fun visibleAncestor(id: String): String? {
            var cur = byId[id]?.parentId
            while (cur != null) {
                if (cur in visibleIds) return cur
                cur = byId[cur]?.parentId
            }
            return null
        }
        val visibleParent = HashMap<String, String?>()
        val visibleChildren = HashMap<String?, MutableList<String>>()
        visibleChildren[null] = ArrayList()
        for (f in filtered) {
            val a = visibleAncestor(f.id)
            visibleParent[f.id] = a
            visibleChildren.getOrPut(a) { ArrayList() }.add(f.id)
        }
        val rootIds = visibleChildren[null]!!
        val multipleRoots = rootIds.size > 1
        val layout = HashMap<String, Layout>(filtered.size * 2)

        class Pending(
            val id: String,
            val indent: Int,
            val justBranched: Boolean,
            val showConnector: Boolean,
            val isLast: Boolean,
            val gutters: List<TreeGutter>,
            val virtualRoot: Boolean,
        )

        val stack = ArrayDeque<Pending>()
        for (i in rootIds.indices.reversed()) {
            val isLast = i == rootIds.size - 1
            stack.addLast(Pending(rootIds[i], if (multipleRoots) 1 else 0, multipleRoots, multipleRoots, isLast, emptyList(), multipleRoots))
        }
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            layout[p.id] = Layout(p.indent, p.showConnector, p.isLast, p.gutters, p.virtualRoot)
            val kids = visibleChildren[p.id] ?: emptyList<String>()
            val multipleChildren = kids.size > 1
            val childIndent = when {
                multipleChildren -> p.indent + 1
                p.justBranched && p.indent > 0 -> p.indent + 1
                else -> p.indent
            }
            val connectorDisplayed = p.showConnector && !p.virtualRoot
            val currentDisplayIndent = if (multipleRoots) maxOf(0, p.indent - 1) else p.indent
            val connectorPosition = maxOf(0, currentDisplayIndent - 1)
            val childGutters = if (connectorDisplayed) p.gutters + TreeGutter(connectorPosition, !p.isLast) else p.gutters
            for (i in kids.indices.reversed()) {
                val childIsLast = i == kids.size - 1
                stack.addLast(Pending(kids[i], childIndent, multipleChildren, multipleChildren, childIsLast, childGutters, false))
            }
        }

        fun foldable(id: String): Boolean {
            val kids = visibleChildren[id]
            if (kids == null || kids.isEmpty()) return false
            val parent = visibleParent[id] ?: return true
            val siblings = visibleChildren[parent]
            return siblings != null && siblings.size > 1
        }

        val rows = ArrayList<TreeRow>(filtered.size)
        for (f in filtered) {
            val l = layout[f.id] ?: continue
            val isFolded = f.id in folded
            val canFold = foldable(f.id)
            val displayIndent = if (multipleRoots) maxOf(0, l.indent - 1) else l.indent
            val connector = l.showConnector && !l.virtualRoot
            val connectorPosition = if (connector) displayIndent - 1 else -1
            val prefix = StringBuilder()
            for (i in 0 until displayIndent * 3) {
                val level = i / 3
                val pos = i % 3
                val gutter = l.gutters.firstOrNull { it.position == level }
                prefix.append(
                    when {
                        gutter != null -> if (pos == 0 && gutter.show) '│' else ' '
                        connector && level == connectorPosition -> when (pos) {
                            0 -> if (l.isLast) '└' else '├'
                            1 -> if (isFolded) '⊞' else if (canFold) '⊟' else '─'
                            else -> ' '
                        }
                        else -> ' '
                    },
                )
            }
            rows.add(
                TreeRow(
                    item = itemOf(f, leafId, onPath, hiddenIds, toolCalls, homeDir, canFold, isFolded),
                    indent = l.indent,
                    showConnector = l.showConnector,
                    isLast = l.isLast,
                    gutters = l.gutters,
                    isVirtualRootChild = l.virtualRoot,
                    prefix = prefix.toString(),
                    foldMarker = isFolded && !connector,
                ),
            )
        }

        // findNearestVisibleIndex(currentLeafId)
        var nearest: String? = null
        if (rows.isNotEmpty()) {
            val visible = HashSet<String>()
            for (r in rows) visible.add(r.item.id)
            var cur = leafId
            while (cur != null) {
                if (cur in visible) { nearest = cur; break }
                cur = byId[cur]?.parentId
            }
            if (nearest == null) nearest = rows.last().item.id
        }
        return TreeView(rows, flat.count { it.node.entry !is SessionEntry.Usage }, nearest, flat.count { it.label != null })
    }

    // ------------------------------------------------------------------ 结构

    private class Flat(val node: SessionTreeNode, val id: String, val parentId: String?) {
        val label: String? get() = node.label
    }

    private class Layout(val indent: Int, val showConnector: Boolean, val isLast: Boolean, val gutters: List<TreeGutter>, val virtualRoot: Boolean)

    /** SDK `flattenTree` 的顺序：前序，含活动叶的支排前面（根和孩子都一样），其余保持原顺序。 */
    private fun flatten(roots: List<SessionTreeNode>, leafId: String?): List<Flat> {
        // 后序：每个子树里有没有活动叶。
        val containsActive = IdentityHashMap<SessionTreeNode, Boolean>()
        val pre = ArrayList<SessionTreeNode>()
        val st = ArrayDeque<SessionTreeNode>()
        for (r in roots.asReversed()) st.addLast(r)
        while (st.isNotEmpty()) {
            val n = st.removeLast()
            pre.add(n)
            for (c in n.children.asReversed()) st.addLast(c)
        }
        for (i in pre.indices.reversed()) {
            val n = pre[i]
            var has = leafId != null && n.entry.id == leafId
            for (c in n.children) if (containsActive[c] == true) has = true
            containsActive[n] = has
        }
        val ordered = roots.sortedByDescending { containsActive[it] == true }
        val out = ArrayList<Flat>(pre.size)
        val stack = ArrayDeque<SessionTreeNode>()
        for (r in ordered.asReversed()) stack.addLast(r)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out.add(Flat(n, n.entry.id.orEmpty(), n.entry.parentId))
            val prioritized = n.children.filter { containsActive[it] == true }
            val rest = n.children.filter { containsActive[it] != true }
            val kids = prioritized + rest
            for (c in kids.asReversed()) stack.addLast(c)
        }
        return out
    }

    private fun activePath(byId: Map<String, Flat>, leafId: String?): Set<String> {
        val out = HashSet<String>()
        var cur = leafId
        while (cur != null && out.add(cur)) cur = byId[cur]?.parentId
        return out
    }

    /** 所有「被隐藏出模型上下文」的目标 id（`context_edit` 且 replacement 为 null）。 */
    private fun hiddenTargets(flat: List<Flat>): Set<String> =
        flat.mapNotNull { (it.node.entry as? SessionEntry.ContextEdit)?.takeIf { e -> e.omitted }?.targetId }.toSet()

    // ------------------------------------------------------------------ 过滤

    private fun isSettingsEntry(e: SessionEntry) =
        e is SessionEntry.Label || e is SessionEntry.ContextEdit || e is SessionEntry.Custom ||
            e is SessionEntry.ModelChange || e is SessionEntry.ThinkingLevelChange || e is SessionEntry.SessionInfo

    private fun passes(f: Flat, leafId: String?, filter: TreeFilter, tokens: List<String>): Boolean {
        val entry = f.node.entry
        if (entry is SessionEntry.Usage) return false
        val isCurrentLeaf = f.id == leafId
        if (entry is SessionEntry.Message && entry.message is PiMessage.Assistant && !isCurrentLeaf) {
            val msg = entry.message as PiMessage.Assistant
            val hasText = msg.content.any { it is PiContentBlock.Text && it.text.isNotBlank() }
            val sr = msg.stopReason
            val isErrorOrAborted = !sr.isNullOrEmpty() && sr != "stop" && sr != "toolUse"
            if (!hasText && !isErrorOrAborted) return false
        }
        val ok = when (filter) {
            TreeFilter.UserOnly -> entry is SessionEntry.Message && entry.message is PiMessage.User
            TreeFilter.NoTools -> !isSettingsEntry(entry) && !(entry is SessionEntry.Message && entry.message is PiMessage.ToolResult)
            TreeFilter.Labeled -> f.label != null
            TreeFilter.All -> true
            TreeFilter.Default -> !isSettingsEntry(entry)
        }
        if (!ok) return false
        if (tokens.isNotEmpty()) {
            val hay = searchable(f).lowercase()
            return tokens.all { hay.contains(it) }
        }
        return true
    }

    // ------------------------------------------------------------------ 文字

    private val WS = Regex("\\s+")

    /** SDK `extractFullContent`：只取文字块（图片等忽略）。 */
    private fun fullText(blocks: List<PiContentBlock>): String = buildString {
        for (b in blocks) if (b is PiContentBlock.Text) append(b.text)
    }

    /** SDK `extractContent`：文字的前 200 个字符。 */
    private fun content200(blocks: List<PiContentBlock>): String = fullText(blocks).take(200)

    private fun normalize(s: String) = s.replace('\n', ' ').replace('\t', ' ').trim()

    private fun contentOf(m: PiMessage): List<PiContentBlock>? = when (m) {
        is PiMessage.User -> m.content
        is PiMessage.Assistant -> m.content
        is PiMessage.ToolResult -> m.content
        is PiMessage.Custom -> m.content
        else -> null
    }

    /** SDK `getSearchableText`。 */
    private fun searchable(f: Flat): String {
        val parts = ArrayList<String>()
        f.label?.let { parts.add(it) }
        when (val e = f.node.entry) {
            is SessionEntry.Message -> {
                val m = e.message
                parts.add(m.role)
                contentOf(m)?.let { parts.add(content200(it)) }
                if (m is PiMessage.BashExecution && !m.command.isNullOrEmpty()) parts.add(m.command!!)
            }
            is SessionEntry.CustomMessage -> { parts.add(e.customType.orEmpty()); parts.add(fullText(e.content)) }
            is SessionEntry.Compaction -> parts.add("compaction")
            is SessionEntry.BranchSummary -> { parts.add("branch summary"); parts.add(e.summary.orEmpty()) }
            is SessionEntry.SessionInfo -> { parts.add("title"); e.name?.takeIf { it.isNotEmpty() }?.let(parts::add) }
            is SessionEntry.ModelChange -> { parts.add("model"); parts.add(e.modelId.orEmpty()) }
            is SessionEntry.ThinkingLevelChange -> { parts.add("thinking"); parts.add(e.thinkingLevel.orEmpty()) }
            is SessionEntry.Custom -> { parts.add("custom"); parts.add(e.customType.orEmpty()) }
            is SessionEntry.ContextEdit -> { parts.add("context edit"); parts.add(if (e.omitted) "omit" else "replace"); parts.add(e.targetId.orEmpty()) }
            is SessionEntry.Label -> { parts.add("label"); parts.add(e.label.orEmpty()) }
            else -> Unit
        }
        return parts.joinToString(" ")
    }

    private class Display(val head: String, val headTone: TreeTone, val text: String, val textTone: TreeTone)

    /** SDK `getEntryDisplayText`（颜色换成 [TreeTone]）。 */
    private fun display(e: SessionEntry, toolCalls: Map<String, Pair<String, JsonObject?>>, homeDir: String?): Display = when (e) {
        is SessionEntry.Message -> when (val m = e.message) {
            is PiMessage.User -> Display("user: ", TreeTone.User, normalize(content200(m.content)), TreeTone.Plain)
            is PiMessage.Assistant -> {
                val text = normalize(content200(m.content))
                when {
                    text.isNotEmpty() -> Display("assistant: ", TreeTone.Assistant, text, TreeTone.Plain)
                    m.stopReason == "aborted" -> Display("assistant: ", TreeTone.Assistant, "(aborted)", TreeTone.Muted)
                    !m.errorMessage.isNullOrEmpty() -> Display("assistant: ", TreeTone.Assistant, normalize(m.errorMessage!!).take(80), TreeTone.Error)
                    else -> Display("assistant: ", TreeTone.Assistant, "(no content)", TreeTone.Muted)
                }
            }
            is PiMessage.ToolResult -> {
                val call = m.toolCallId?.takeIf { it.isNotEmpty() }?.let { toolCalls[it] }
                Display("", TreeTone.Plain, if (call != null) formatToolCall(call.first, call.second, homeDir) else "[${m.toolName?.takeIf { it.isNotEmpty() } ?: "tool"}]", TreeTone.Muted)
            }
            is PiMessage.BashExecution -> Display("", TreeTone.Plain, "[bash]: ${normalize(m.command.orEmpty())}", TreeTone.Dim)
            else -> Display("", TreeTone.Plain, "[${m.role}]", TreeTone.Dim)
        }
        is SessionEntry.CustomMessage -> Display("[${e.customType.orEmpty()}]: ", TreeTone.Custom, normalize(fullText(e.content)), TreeTone.Plain)
        is SessionEntry.Compaction -> Display("", TreeTone.Plain, "[compaction: ${e.tokensBefore?.let { Math.floor(it / 1000.0 + 0.5).toLong() }?.toString() ?: "?"}k tokens]", TreeTone.Accent)
        is SessionEntry.BranchSummary -> Display("[branch summary]: ", TreeTone.Warning, normalize(e.summary.orEmpty()), TreeTone.Plain)
        is SessionEntry.ModelChange -> Display("", TreeTone.Plain, "[model: ${e.modelId.orEmpty()}]", TreeTone.Dim)
        is SessionEntry.ThinkingLevelChange -> Display("", TreeTone.Plain, "[thinking: ${e.thinkingLevel.orEmpty()}]", TreeTone.Dim)
        is SessionEntry.Custom -> Display("", TreeTone.Plain, "[custom: ${e.customType.orEmpty()}]", TreeTone.Dim)
        is SessionEntry.ContextEdit -> Display("", TreeTone.Plain, "[context ${if (e.omitted) "omit" else "replace"}: ${e.targetId.orEmpty()}]", TreeTone.Dim)
        is SessionEntry.Label -> Display("", TreeTone.Plain, "[label: ${e.label ?: "(cleared)"}]", TreeTone.Dim)
        is SessionEntry.SessionInfo -> Display("", TreeTone.Plain, "[title: ${e.name?.takeIf { it.isNotEmpty() } ?: "empty"}]", TreeTone.Dim)
        is SessionEntry.Usage, is SessionEntry.Unknown -> Display("", TreeTone.Plain, "", TreeTone.Plain)
    }

    private fun truthy(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.content != "0" && v.content != "false"
        else -> true
    }

    /** JS `String(args.a || args.b || fallback)`。 */
    private fun firstArg(args: JsonObject?, vararg keys: String, fallback: String = ""): String {
        for (k in keys) {
            val v = args?.get(k)
            if (truthy(v)) return if (v is JsonPrimitive) v.content else v.toString()
        }
        return fallback
    }

    private fun shortenHome(path: String, homeDir: String?): String {
        if (homeDir.isNullOrEmpty() || !path.startsWith("/")) return path
        val home = homeDir.trimEnd('/')
        if (path == home) return "~"
        return if (path.startsWith("$home/")) "~/" + path.removePrefix("$home/") else path
    }

    /** SDK `formatReadLineRange`（`core/tools/render-utils.js`）。 */
    private fun readLineRange(offset: JsonElement?, limit: JsonElement?): String {
        fun num(v: JsonElement?): Double? {
            val p = v as? JsonPrimitive ?: return null
            val d = if (p.isString) (if (p.content.isBlank()) null else p.content.trim().toDoubleOrNull()) else p.content.toDoubleOrNull()
            return d?.takeIf { it.isFinite() }
        }
        fun isNil(v: JsonElement?) = v == null || v == JsonNull
        if (isNil(offset) && isNil(limit)) return ""
        val start = if (isNil(offset)) 1.0 else num(offset) ?: return ""
        if (isNil(limit)) return ":${fmt(start)}"
        val count = num(limit) ?: return ""
        val end = start + count - 1
        return ":${fmt(start)}${if (end != 0.0) "-${fmt(end)}" else ""}"
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** SDK `formatToolCall`。 */
    private fun formatToolCall(name: String, args: JsonObject?, homeDir: String?): String = when (name) {
        "read" -> "[read: ${shortenHome(firstArg(args, "path", "file_path"), homeDir)}${readLineRange(args?.get("offset"), args?.get("limit"))}]"
        "write" -> "[write: ${shortenHome(firstArg(args, "path", "file_path"), homeDir)}]"
        "edit" -> "[edit: ${shortenHome(firstArg(args, "path", "file_path"), homeDir)}]"
        "bash" -> {
            val raw = firstArg(args, "command")
            val cmd = raw.replace('\n', ' ').replace('\t', ' ').trim().take(50)
            "[bash: $cmd${if (raw.length > 50) "..." else ""}]"
        }
        "grep" -> "[grep: /${firstArg(args, "pattern")}/ in ${shortenHome(firstArg(args, "path", fallback = "."), homeDir)}]"
        "find" -> "[find: ${firstArg(args, "pattern")} in ${shortenHome(firstArg(args, "path", fallback = "."), homeDir)}]"
        "ls" -> "[ls: ${shortenHome(firstArg(args, "path", fallback = "."), homeDir)}]"
        else -> {
            val json = (args ?: JsonObject(emptyMap())).toString()
            "[$name: ${json.take(40)}${if (json.length > 40) "..." else ""}]"
        }
    }

    /** SDK `getEntryCopyText`。 */
    private fun copyTextOf(e: SessionEntry): String? {
        val text: String? = when (e) {
            is SessionEntry.Message -> when (val m = e.message) {
                is PiMessage.BashExecution -> m.command
                is PiMessage.Assistant -> fullText(m.content).ifEmpty { m.errorMessage }
                else -> contentOf(m)?.let(::fullText)
            }
            is SessionEntry.CustomMessage -> fullText(e.content)
            is SessionEntry.Compaction -> e.summary
            is SessionEntry.BranchSummary -> e.summary
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun titleOf(e: SessionEntry): String = when (e) {
        is SessionEntry.Message -> when (e.message) {
            is PiMessage.User -> "用户消息"
            is PiMessage.Assistant -> "助手消息"
            is PiMessage.ToolResult -> "工具结果"
            is PiMessage.BashExecution -> "命令"
            else -> "消息"
        }
        is SessionEntry.Compaction -> "压缩"
        is SessionEntry.BranchSummary -> "分支摘要"
        is SessionEntry.CustomMessage -> "扩展消息"
        is SessionEntry.Custom -> "扩展状态"
        is SessionEntry.ModelChange -> "切换模型"
        is SessionEntry.ThinkingLevelChange -> "思考档位"
        is SessionEntry.Label -> "标签"
        is SessionEntry.SessionInfo -> "会话信息"
        is SessionEntry.Usage -> "用量记录"
        is SessionEntry.ContextEdit -> "上下文编辑"
        is SessionEntry.Unknown -> "未知条目"
    }

    private fun itemOf(
        f: Flat,
        leafId: String?,
        onPath: Set<String>,
        hidden: Set<String>,
        toolCalls: Map<String, Pair<String, JsonObject?>>,
        homeDir: String?,
        foldable: Boolean,
        folded: Boolean,
    ): TreeItem {
        val e = f.node.entry
        val d = display(e, toolCalls, homeDir)
        val msg = (e as? SessionEntry.Message)?.message
        return TreeItem(
            id = f.id,
            typeTitle = titleOf(e),
            head = d.head,
            headTone = d.headTone,
            text = d.text,
            textTone = d.textTone,
            label = f.label,
            labelTimestamp = f.node.labelTimestamp,
            onPath = f.id in onPath,
            isLeaf = leafId != null && f.id == leafId,
            hiddenFromContext = f.id in hidden,
            contextEditable = msg is PiMessage.User || msg is PiMessage.Assistant || msg is PiMessage.ToolResult,
            forkable = msg is PiMessage.User && fullText(msg.content).isNotEmpty(),
            rewinds = msg is PiMessage.User || e is SessionEntry.CustomMessage,
            copyText = copyTextOf(e),
            foldable = foldable,
            folded = folded,
        )
    }

    /** SDK `formatLabelTimestamp`：今天 `HH:mm`；今年 `M/d HH:mm`；更早 `yy/M/d HH:mm`（本地时区）。 */
    fun formatLabelTimestamp(iso: String, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = runCatching { LocalDateTime.ofInstant(Instant.parse(iso), zone) }.getOrNull() ?: return ""
        val time = "%02d:%02d".format(date.hour, date.minute)
        if (date.year == now.year && date.monthValue == now.monthValue && date.dayOfMonth == now.dayOfMonth) return time
        if (date.year == now.year) return "${date.monthValue}/${date.dayOfMonth} $time"
        return "${(date.year % 100).toString().padStart(2, '0')}/${date.monthValue}/${date.dayOfMonth} $time"
    }
}
