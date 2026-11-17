package app.pi.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiMessage
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode
import app.pi.session.tree.TreeFilter
import app.pi.session.tree.TreeItem
import app.pi.session.tree.TreeModel
import app.pi.session.tree.TreeRow
import app.pi.ui.chat.BranchSummaryChoice
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 会话树（SDK 版）：画 [TreeModel] 排好的行，不做任何树逻辑。
 *
 * - 筛选 chip：对话 / 书签 / 全部；搜索框（多词 AND，命中时展开全部分支）。
 * - 行：脊线不缩进；分叉点的侧枝折成「⑂ N 条」，点开才展开。当前位置、活动路径、标签、
 *   「不再发给模型」都是行上的标记。
 * - 点一行 → 底部动作面板：跳到这里 / 从这里分叉 / 标签 / 不再发给模型 / 改写模型看到的内容 / 复制。
 *   「跳到这里」若 `branchSummary.skipPrompt` 未开，先问摘要（不摘要 / 摘要 / 自定义指令）。
 *
 * 所有写操作（跳转、标签、上下文编辑）都是 SDK 的直接方法，经宿主命令完成；这里只回调。
 */
@Composable
fun SessionTreeView(
    roots: List<SessionTreeNode>,
    leafId: String?,
    busy: String?,
    onFork: (String) -> Unit,
    onNavigate: (String, BranchSummaryChoice, String?) -> Unit,
    onLabel: (String, String?) -> Unit,
    onHideFromContext: (String) -> Unit,
    onReplaceInContext: (String, String) -> Unit,
    onAbortSummary: () -> Unit,
    skipSummaryPrompt: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var filterName by rememberSaveable { mutableStateOf(TreeFilter.Conversation.name) }
    val filter = TreeFilter.valueOf(filterName)
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val view = remember(roots, leafId, filter, query, expanded) {
        TreeModel.view(roots, leafId, filter, query, expanded)
    }
    val listState = rememberLazyListState()

    // 第一次拿到树时，滚到「当前位置」：用户进来就是想知道自己在哪。
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(view.rows.size, positioned) {
        if (!positioned && view.rows.isNotEmpty()) {
            val at = view.rows.indexOfFirst { it is TreeRow.Node && it.item.isLeaf }
            if (at >= 0) listState.scrollToItem((at - 2).coerceAtLeast(0))
            positioned = true
        }
    }

    Column(modifier.fillMaxWidth()) {
        FilterBar(
            filter = filter,
            onFilter = { filterName = it.name },
            query = query,
            onQuery = { query = it },
            bookmarkCount = view.bookmarkCount,
        )
        if (busy != null) SummaryBanner(busy, onAbortSummary)
        Text(
            if (view.shownNodes == view.totalNodes) "${view.totalNodes} 条" else "显示 ${view.shownNodes} / ${view.totalNodes}",
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = ROW_GAP),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
        if (view.rows.isEmpty()) {
            PiEmptyState(
                icon = Icons.Filled.Search,
                title = when {
                    query.isNotBlank() -> "没有匹配的条目"
                    filter == TreeFilter.Bookmarks -> "还没有书签"
                    else -> "这个会话还没有内容"
                },
                body = if (filter == TreeFilter.Bookmarks && query.isBlank()) "点开任意一条，给它起个标签，之后在这里一键回到那里。" else "换个筛选或关键词试试。",
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(view.rows, key = { _, r -> rowKey(r) }) { _, row ->
                    when (row) {
                        is TreeRow.Node -> NodeRow(row, onClick = { selectedId = row.item.id })
                        is TreeRow.Branch -> BranchRow(row, onClick = {
                            expanded = if (row.headId in expanded) expanded - row.headId else expanded + row.headId
                        })
                    }
                }
                item(key = "tree:end") { Spacer(Modifier.height(PiSpacing.unit * 2)) }
            }
        }
    }

    val picked = selectedId
    if (picked != null) {
        val item = view.rows.firstNotNullOfOrNull { (it as? TreeRow.Node)?.item?.takeIf { i -> i.id == picked } }
            ?: findItem(roots, leafId, picked)
        if (item == null) {
            selectedId = null
        } else {
            EntrySheet(
                item = item,
                fullText = fullTextOf(roots, picked),
                skipSummaryPrompt = skipSummaryPrompt,
                onDismiss = { selectedId = null },
                onFork = { selectedId = null; onFork(picked) },
                onNavigate = { choice, instructions -> selectedId = null; onNavigate(picked, choice, instructions) },
                onLabel = { label -> selectedId = null; onLabel(picked, label) },
                onHide = { selectedId = null; onHideFromContext(picked) },
                onReplace = { text -> selectedId = null; onReplaceInContext(picked, text) },
            )
        }
    }
}

private fun rowKey(row: TreeRow): String = when (row) {
    is TreeRow.Node -> "n:" + row.item.id
    is TreeRow.Branch -> "b:" + row.headId
}

// ------------------------------------------------------------------ 过滤栏

@Composable
private fun FilterBar(
    filter: TreeFilter,
    onFilter: (TreeFilter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    bookmarkCount: Int,
) {
    Column(Modifier.padding(horizontal = PiSpacing.pageHorizontal)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP), verticalAlignment = Alignment.CenterVertically) {
            for (f in TreeFilter.entries) {
                PiSettingsChip(
                    text = if (f == TreeFilter.Bookmarks && bookmarkCount > 0) "${f.label} $bookmarkCount" else f.label,
                    active = f == filter,
                    onClick = { onFilter(f) },
                )
            }
        }
        Spacer(Modifier.height(ROW_GAP))
        Surface(
            modifier = Modifier.fillMaxWidth().height(SEARCH_HEIGHT),
            shape = PiShapes.chip,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = SEARCH_PAD),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(ICON), tint = PiTheme.palette.muted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索这棵树", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(PiTheme.palette.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Text("清除", modifier = Modifier.clickable { onQuery("") }.padding(ROW_GAP), style = PiTheme.text.meta, color = PiTheme.palette.accent)
                }
            }
        }
    }
}

@Composable
private fun SummaryBanner(label: String, onAbort: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = PiTheme.text.meta, color = PiTheme.palette.warning)
        Text("停止摘要", modifier = Modifier.clickable(onClick = onAbort).padding(ROW_GAP), style = PiTheme.text.meta, color = PiTheme.palette.accent)
    }
}

// ------------------------------------------------------------------ 行

@Composable
private fun NodeRow(row: TreeRow.Node, onClick: () -> Unit) {
    val item = row.item
    val p = PiTheme.palette
    val tint = when {
        item.isLeaf -> p.accent
        item.onPath -> p.text
        else -> p.dim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (item.isLeaf) p.selectedBg else MaterialTheme.colorScheme.background)
            .padding(start = PiSpacing.pageHorizontal + INDENT * row.depth, end = PiSpacing.pageHorizontal, top = ROW_PAD, bottom = ROW_PAD),
        verticalAlignment = Alignment.Top,
    ) {
        // 脊线上的点：当前位置实心，活动路径描边，其余淡色。
        Box(
            modifier = Modifier
                .padding(top = DOT_TOP)
                .size(RAIL)
                .clip(CircleShape)
                .background(if (item.isLeaf) p.accent else if (item.onPath) p.border else p.borderMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.kind.glyph, style = PiTheme.text.meta, color = if (item.isLeaf) MaterialTheme.colorScheme.background else tint)
        }
        Spacer(Modifier.width(ROW_GAP + ROW_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                item.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = PiTheme.text.prose,
                color = if (item.hiddenFromContext) p.dim else tint,
                textDecoration = if (item.hiddenFromContext) TextDecoration.LineThrough else null,
                fontWeight = if (item.isLeaf) FontWeight.SemiBold else null,
            )
            val tags = buildList {
                if (item.isLeaf) add("当前位置")
                if (item.hiddenFromContext) add("不再发给模型")
                if (item.childCount > 1) add("${item.childCount} 个分支")
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), style = PiTheme.text.meta, color = if (item.isLeaf) p.accent else p.muted)
            }
        }
        item.label?.let { label ->
            Spacer(Modifier.width(ROW_GAP))
            Surface(shape = PiShapes.chip, color = p.selectedBg) {
                Text("⚑ $label", modifier = Modifier.padding(horizontal = ROW_GAP, vertical = 2.dp), style = PiTheme.text.meta, color = p.accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BranchRow(row: TreeRow.Branch, onClick: () -> Unit) {
    val p = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PiSpacing.pageHorizontal + INDENT * row.depth, end = PiSpacing.pageHorizontal, top = ROW_PAD, bottom = ROW_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", style = PiTheme.text.meta, color = p.muted, modifier = Modifier.width(RAIL + ROW_GAP + ROW_GAP))
        Text(
            "⑂ ${row.nodeCount} 条 · ${row.preview}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = PiTheme.text.meta,
            color = p.muted,
        )
    }
}

// ------------------------------------------------------------------ 动作面板

private enum class Step { Actions, SummaryChoice, SummaryInstructions, Label, Replace }

@Composable
private fun EntrySheet(
    item: TreeItem,
    fullText: String,
    skipSummaryPrompt: () -> Boolean,
    onDismiss: () -> Unit,
    onFork: () -> Unit,
    onNavigate: (BranchSummaryChoice, String?) -> Unit,
    onLabel: (String?) -> Unit,
    onHide: () -> Unit,
    onReplace: (String) -> Unit,
) {
    var step by remember { mutableStateOf(Step.Actions) }
    var labelText by remember { mutableStateOf(item.label.orEmpty()) }
    var instructions by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf(fullText) }
    val clipboard = LocalClipboardManager.current
    val p = PiTheme.palette

    PiSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = PiSpacing.pageHorizontal).padding(bottom = PiSpacing.unit)) {
            Text("${item.kind.glyph}  ${item.kind.title}", style = PiTheme.text.meta, color = p.muted)
            Spacer(Modifier.height(ROW_GAP))
            Text(fullText, maxLines = 6, overflow = TextOverflow.Ellipsis, style = PiTheme.text.prose, color = p.text)
            Spacer(Modifier.height(PiSpacing.unit))
            when (step) {
                Step.Actions -> {
                    if (!item.isLeaf) {
                        ActionRow("跳到这里", if (item.forkable) "回到这条你的消息之前，从这里重新继续" else "同一个会话文件里把位置移到这里") {
                            if (skipSummaryPrompt()) onNavigate(BranchSummaryChoice.NoSummary, null) else step = Step.SummaryChoice
                        }
                    }
                    if (item.forkable) ActionRow("从这里分叉", "新建一个会话文件，原会话保持不变", onFork)
                    ActionRow(if (item.label == null) "加标签（书签）" else "改标签", item.label?.let { "现在是「$it」" } ?: "之后在「书签」里一键回来") { step = Step.Label }
                    if (item.contextEditable && !item.hiddenFromContext) {
                        ActionRow("不再发给模型", "对话里仍看得见，记录一个字不少；只是之后的上下文里没有它", onHide)
                        ActionRow("改写模型看到的内容", "只改发给模型的版本，原文保留") { step = Step.Replace }
                    }
                    ActionRow("复制全文", null) { clipboard.setText(AnnotatedString(fullText)); onDismiss() }
                }
                Step.SummaryChoice -> {
                    Text("要摘要被放弃的那一段吗？", style = PiTheme.text.prose, color = p.text)
                    Text("摘要是一次模型调用，可能等一会儿；之后可以随时点「停止摘要」。", style = PiTheme.text.meta, color = p.muted)
                    Spacer(Modifier.height(ROW_GAP))
                    ActionRow("不摘要", null) { onNavigate(BranchSummaryChoice.NoSummary, null) }
                    ActionRow("摘要", "用默认的摘要提示词") { onNavigate(BranchSummaryChoice.Summarize, null) }
                    ActionRow("用自定义指令摘要…", "告诉它这次要留住什么") { step = Step.SummaryInstructions }
                    ActionRow("返回", null) { step = Step.Actions }
                }
                Step.SummaryInstructions -> {
                    TextBox(instructions, { instructions = it }, "摘要指令：要留住什么")
                    ActionRow("开始摘要", null) { onNavigate(BranchSummaryChoice.SummarizeWithPrompt, instructions.trim().ifEmpty { null }) }
                    ActionRow("返回", null) { step = Step.SummaryChoice }
                }
                Step.Label -> {
                    TextBox(labelText, { labelText = it }, "标签，例如「方案 A 之前」", singleLine = true)
                    ActionRow("保存", null) { onLabel(labelText.trim().ifEmpty { null }) }
                    if (item.label != null) ActionRow("清除标签", null) { onLabel(null) }
                    ActionRow("返回", null) { step = Step.Actions }
                }
                Step.Replace -> {
                    TextBox(replacement, { replacement = it }, "模型将看到的内容")
                    ActionRow("保存改写", "只影响之后发给模型的上下文") { onReplace(replacement) }
                    ActionRow("返回", null) { step = Step.Actions }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, hint: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ROW_PAD),
    ) {
        Text(title, style = PiTheme.text.prose, color = PiTheme.palette.text)
        if (hint != null) Text(hint, style = PiTheme.text.meta, color = PiTheme.palette.muted)
    }
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, placeholder: String, singleLine: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) SEARCH_HEIGHT else BOX_MIN),
        shape = PiShapes.cardInner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.padding(SEARCH_PAD)) {
            if (value.isEmpty()) Text(placeholder, style = PiTheme.text.meta, color = PiTheme.palette.dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ 取数

/** 完整文字（不截断）：用户/助手消息取正文，其余取摘要。显式栈找节点，几千条也不会爆栈。 */
private fun fullTextOf(roots: List<SessionTreeNode>, id: String): String {
    val node = findNode(roots, id) ?: return ""
    val e = node.entry
    if (e is SessionEntry.Message) {
        val m = e.message
        if (m is PiMessage.User) return m.text
        if (m is PiMessage.Assistant && m.text.isNotBlank()) return m.text
    }
    if (e is SessionEntry.Compaction) return e.summary.orEmpty()
    if (e is SessionEntry.BranchSummary) return e.summary.orEmpty()
    return TreeModel.textOf(e)
}

private fun findNode(roots: List<SessionTreeNode>, id: String): SessionTreeNode? {
    val stack = ArrayDeque<SessionTreeNode>()
    roots.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (n.entry.id == id) return n
        n.children.forEach(stack::addLast)
    }
    return null
}

/** 过滤后行里没有（例如搜索把它滤掉了）但面板还开着时，从树里重建一个条目。 */
private fun findItem(roots: List<SessionTreeNode>, leafId: String?, id: String): TreeItem? {
    val full = TreeModel.view(roots, leafId, TreeFilter.All, "", emptySet())
    // 侧枝折叠时拿不到；展开全部再找一次。
    val all = TreeModel.view(roots, leafId, TreeFilter.All, "", allBranchHeads(roots))
    return (all.rows + full.rows).firstNotNullOfOrNull { (it as? TreeRow.Node)?.item?.takeIf { i -> i.id == id } }
}

private fun allBranchHeads(roots: List<SessionTreeNode>): Set<String> {
    val out = HashSet<String>()
    val stack = ArrayDeque<SessionTreeNode>()
    roots.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (n.children.size > 1) n.children.forEach { c -> c.entry.id?.let(out::add) }
        n.children.forEach(stack::addLast)
    }
    return out
}

private val ROW_GAP = 6.dp
private val ROW_PAD = 8.dp
private val INDENT = 16.dp
private val RAIL = 22.dp
private val DOT_TOP = 2.dp
private val ICON = 15.dp
private val SEARCH_HEIGHT = 36.dp
private val SEARCH_PAD = 10.dp
private val BOX_MIN = 96.dp
