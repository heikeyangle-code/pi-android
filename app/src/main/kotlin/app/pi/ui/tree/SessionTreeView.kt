package app.pi.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.SessionTreeNode
import app.pi.session.tree.TreeFilter
import app.pi.session.tree.TreeItem
import app.pi.session.tree.TreeModel
import app.pi.session.tree.TreeRow
import app.pi.session.tree.TreeTone
import app.pi.ui.chat.BranchSummaryChoice
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 会话树（对照 pi 的 `/tree`）：画 [TreeModel] 排好的行，不做任何树逻辑。
 *
 * 行的内容、顺序、缩进、连线、折叠、过滤、搜索都是 [TreeModel] 从 SDK 的 `tree-selector.js` 移植的
 * （并由 `tools/collect-tree-fixtures.mjs` 用 SDK 自己的组件生成标准答案逐行比对过），
 * 这里只负责把它们画成手机上顺手的样子：
 *
 *  - 五个过滤 chip（默认 / 无工具 / 仅用户 / 书签 / 全部）+ 搜索框（空白分词，全部命中）；
 *    初始过滤来自 pi 的 `treeFilterMode` 设置。
 *  - 每行：连线（`├ └ │ ─` 画成真线）、折叠开关（`⊟` 展开中 / `⊞` 已折叠，点它折叠整支）、
 *    `•`（在当前路径上）、`[标签]`（可选带时间）、`user:` / `assistant:` 着色，工具结果显示成
 *    `[read: 路径]` 这样的调用。当前位置那一行有底色。
 *  - 点一行 → 底部面板：跳转 / 分叉 / 标签 / 不再发给模型 / 改写 / 复制。跳转的说明会区分
 *    「回到这条用户消息之前并把它放回输入框」和「直接移到这里」，与 SDK `navigateTree` 一致。
 *  - 摘要问题：`branchSummary.skipPrompt` 未开时问（不摘要 / 摘要 / 自定义指令）；
 *    关掉面板 = TUI 里按 Escape：回到树。摘要被中止后由上层把选中状态还回来（[reselect]）。
 *
 * 所有写操作都是 SDK 的直接方法，经宿主命令完成；这里只回调。
 */
@Composable
fun SessionTreeView(
    roots: List<SessionTreeNode>,
    leafId: String?,
    busy: String?,
    streaming: Boolean,
    initialFilter: TreeFilter,
    reselect: String?,
    onReselected: () -> Unit,
    onFork: (String) -> Unit,
    onNavigate: (String, BranchSummaryChoice, String?) -> Unit,
    onLabel: (String, String?) -> Unit,
    onHideFromContext: (String) -> Unit,
    onReplaceInContext: (String, String) -> Unit,
    onAbortSummary: () -> Unit,
    skipSummaryPrompt: () -> Boolean,
    modifier: Modifier = Modifier,
    homeDir: String? = null,
) {
    var filterName by rememberSaveable { mutableStateOf(initialFilter.name) }
    val filter = TreeFilter.valueOf(filterName)
    var query by rememberSaveable { mutableStateOf("") }
    var folded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    var showStamps by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    // 摘要被中止：TUI 回到树、选中同一条。
    LaunchedEffect(reselect) {
        if (reselect != null) {
            selectedId = reselect
            onReselected()
        }
    }

    val view = remember(roots, leafId, filter, query, folded, homeDir) {
        TreeModel.view(roots, leafId, filter, query, folded, homeDir)
    }
    val listState = rememberLazyListState()

    // 进来 / 换过滤时滚到「当前位置」（被过滤掉就是最近的可见祖先，同 SDK 的 findNearestVisibleIndex）：
    // 用户进来就是想知道自己在哪。搜索和折叠不抢滚动位置。
    LaunchedEffect(filter, view.rows.isNotEmpty()) {
        val at = view.rows.indexOfFirst { it.item.id == view.nearestToLeafId }
        if (at >= 0) listState.scrollToItem((at - 2).coerceAtLeast(0))
    }

    Column(modifier.fillMaxWidth()) {
        FilterBar(
            filter = filter,
            onFilter = { filterName = it.name; folded = emptySet() },
            query = query,
            onQuery = { query = it; folded = emptySet() },
            bookmarkCount = view.bookmarkCount,
            showStamps = showStamps,
            onToggleStamps = { showStamps = !showStamps },
        )
        if (busy != null) SummaryBanner(busy, onAbortSummary)
        Text(
            if (view.rows.size == view.totalEntries) "${view.totalEntries} 条 · ${filter.hint}" else "显示 ${view.rows.size} / ${view.totalEntries} · ${filter.hint}",
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = ROW_GAP),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
        if (view.rows.isEmpty()) {
            PiEmptyState(
                icon = Icons.Filled.Search,
                title = when {
                    query.isNotBlank() -> "没有匹配的条目"
                    filter == TreeFilter.Labeled -> "还没有书签"
                    else -> "这个过滤下没有条目"
                },
                body = if (filter == TreeFilter.Labeled && query.isBlank()) "点开任意一条，给它起个标签，之后在这里一键回到那里。" else "换个过滤或关键词试试。",
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(view.rows, key = { _, r -> r.item.id }) { _, row ->
                    TreeRowView(
                        row = row,
                        showStamps = showStamps,
                        onClick = { selectedId = row.item.id },
                        onToggleFold = {
                            folded = if (row.item.id in folded) folded - row.item.id else folded + row.item.id
                        },
                    )
                }
                item(key = "tree:end") { Spacer(Modifier.height(PiSpacing.unit * 2)) }
            }
        }
    }

    val picked = selectedId
    if (picked != null) {
        // 行里没有（被过滤 / 折叠掉）但面板还开着：从完整的树里取。
        val item = view.rows.firstOrNull { it.item.id == picked }?.item
            ?: remember(roots, leafId, picked) {
                TreeModel.view(roots, leafId, TreeFilter.All, "", emptySet(), homeDir).rows.firstOrNull { it.item.id == picked }?.item
            }
        if (item == null) {
            selectedId = null
        } else {
            EntrySheet(
                item = item,
                streaming = streaming,
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

// ------------------------------------------------------------------ 过滤栏

@Composable
private fun FilterBar(
    filter: TreeFilter,
    onFilter: (TreeFilter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    bookmarkCount: Int,
    showStamps: Boolean,
    onToggleStamps: () -> Unit,
) {
    Column(Modifier.padding(horizontal = PiSpacing.pageHorizontal)) {
        // 五个 chip 在窄屏上放不下一行：横向滚动，不换行（换行会把列表挤下去一大截）。
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (f in TreeFilter.entries) {
                PiSettingsChip(
                    text = if (f == TreeFilter.Labeled && bookmarkCount > 0) "${f.label} $bookmarkCount" else f.label,
                    active = f == filter,
                    onClick = { onFilter(f) },
                )
            }
            if (bookmarkCount > 0) {
                PiSettingsChip(text = "标签时间", glyph = if (showStamps) "✓" else "○", active = showStamps, onClick = onToggleStamps)
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
                    if (query.isEmpty()) Text("搜索这棵树（空格分词，全部命中）", style = PiTheme.text.meta, color = PiTheme.palette.dim)
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

private fun toneColor(tone: TreeTone, p: PiPalette): Color {
    return when (tone) {
        TreeTone.Plain -> p.text
        TreeTone.User -> p.accent
        TreeTone.Assistant -> p.success
        TreeTone.Muted -> p.muted
        TreeTone.Dim -> p.dim
        TreeTone.Error -> p.error
        TreeTone.Warning -> p.warning
        TreeTone.Accent -> p.borderAccent
        TreeTone.Custom -> p.customMessageLabel
    }
}

/**
 * 一行 = 连线区（每级 [LEVEL] 宽，按 [TreeRow] 的 gutters / connector 画真线）+ 折叠开关 + 文字。
 * 层级数取自 SDK 的 3 字符一级的前缀（`prefix.length / 3`），不另算一遍。
 */
@Composable
private fun TreeRowView(row: TreeRow, showStamps: Boolean, onClick: () -> Unit, onToggleFold: () -> Unit) {
    val item = row.item
    val p = PiTheme.palette
    val levels = row.prefix.length / 3
    val lineColor = p.borderMuted
    val connector = row.showConnector && !row.isVirtualRootChild
    val text = buildAnnotatedString {
        if (item.onPath) withStyle(SpanStyle(color = p.accent)) { append("• ") }
        item.label?.let { withStyle(SpanStyle(color = p.warning)) { append("[$it] ") } }
        if (showStamps && item.label != null && item.labelTimestamp != null) {
            val stamp = TreeModel.formatLabelTimestamp(item.labelTimestamp)
            if (stamp.isNotEmpty()) withStyle(SpanStyle(color = p.muted)) { append("$stamp ") }
        }
        if (item.head.isNotEmpty()) withStyle(SpanStyle(color = toneColor(item.headTone, p))) { append(item.head) }
        withStyle(SpanStyle(color = toneColor(item.textTone, p))) { append(item.text) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (item.isLeaf) p.selectedBg else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val unit = LEVEL.toPx()
                val pageStart = PiSpacing.pageHorizontal.toPx()
                val mid = size.height / 2f
                for (g in row.gutters) {
                    if (!g.show) continue
                    val x = pageStart + (g.position + 0.5f) * unit
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                }
                if (connector) {
                    val x = pageStart + (levels - 1 + 0.5f) * unit
                    drawLine(lineColor, Offset(x, 0f), Offset(x, if (row.isLast) mid else size.height), stroke)
                    drawLine(lineColor, Offset(x, mid), Offset(pageStart + levels * unit, mid), stroke)
                }
            }
            .padding(start = PiSpacing.pageHorizontal + LEVEL * levels, end = PiSpacing.pageHorizontal)
            .heightIn(min = ROW_MIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 折叠开关槽：固定宽度，保证各行文字对齐；SDK 把它画在连线中间（`⊟` / `⊞`），这里放在文字前。
        Box(
            modifier = Modifier.width(FOLD_SLOT).heightIn(min = ROW_MIN).then(
                if (item.foldable || item.folded) Modifier.clickable(onClick = onToggleFold) else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.foldable || item.folded) {
                Text(if (item.folded) "⊞" else "⊟", style = PiTheme.text.meta, color = if (item.folded) p.accent else p.muted)
            }
        }
        Text(
            text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = PiTheme.text.prose,
            textDecoration = if (item.hiddenFromContext) TextDecoration.LineThrough else null,
            fontWeight = if (item.isLeaf) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f).padding(vertical = ROW_PAD),
        )
        if (item.hiddenFromContext) {
            Text("不发给模型", modifier = Modifier.padding(start = ROW_GAP), style = PiTheme.text.meta, color = p.dim, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ 动作面板

private enum class Step { Actions, SummaryChoice, SummaryInstructions, Label, Replace }

@Composable
private fun EntrySheet(
    item: TreeItem,
    streaming: Boolean,
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
    val fullText = item.copyText ?: (item.head + item.text)
    var replacement by remember { mutableStateOf(item.copyText.orEmpty()) }
    val clipboard = LocalClipboardManager.current
    val p = PiTheme.palette

    PiSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = PiSpacing.pageHorizontal).padding(bottom = PiSpacing.unit)) {
            Text(item.typeTitle + if (item.isLeaf) " · 当前位置" else "", style = PiTheme.text.meta, color = p.muted)
            Spacer(Modifier.height(ROW_GAP))
            Text(fullText, maxLines = 6, overflow = TextOverflow.Ellipsis, style = PiTheme.text.prose, color = p.text)
            Spacer(Modifier.height(PiSpacing.unit))
            when (step) {
                Step.Actions -> {
                    if (item.isLeaf) {
                        Text("已在这个位置", style = PiTheme.text.meta, color = p.muted)
                    } else {
                        val hint = buildString {
                            append(
                                if (item.rewinds) "回到这条之前，这条的文字放回输入框（输入框为空时）让你改写后重发"
                                else "把当前位置移到这里，在这里继续",
                            )
                            if (streaming) append("。正在输出：会先停止本轮")
                        }
                        ActionRow("跳转到这里", hint) {
                            if (skipSummaryPrompt()) onNavigate(BranchSummaryChoice.NoSummary, null) else step = Step.SummaryChoice
                        }
                    }
                    if (item.forkable) ActionRow("从这里分叉", "新建一个会话文件，原会话保持不变", onFork)
                    ActionRow(if (item.label == null) "加标签（书签）" else "改标签", item.label?.let { "现在是「$it」" } ?: "之后在「书签」里一键回来") { step = Step.Label }
                    if (item.contextEditable && !item.hiddenFromContext) {
                        ActionRow("不再发给模型", "对话里仍看得见，记录一个字不少；只是之后的上下文里没有它", onHide)
                        ActionRow("改写模型看到的内容", "只改发给模型的版本，原文保留") { step = Step.Replace }
                    }
                    if (item.copyText != null) ActionRow("复制", null) { clipboard.setText(AnnotatedString(fullText)); onDismiss() }
                }
                Step.SummaryChoice -> {
                    Text("要摘要被放弃的那一段吗？", style = PiTheme.text.prose, color = p.text)
                    Text("摘要是一次模型调用，可能等一会儿；之后可以随时点「停止摘要」。关掉这个面板回到树。", style = PiTheme.text.meta, color = p.muted)
                    Spacer(Modifier.height(ROW_GAP))
                    ActionRow("不摘要", null) { onNavigate(BranchSummaryChoice.NoSummary, null) }
                    ActionRow("摘要", "用默认的摘要提示词") { onNavigate(BranchSummaryChoice.Summarize, null) }
                    ActionRow("用自定义指令摘要…", "告诉它这次要留住什么") { step = Step.SummaryInstructions }
                }
                Step.SummaryInstructions -> {
                    TextBox(instructions, { instructions = it }, "摘要指令：要留住什么")
                    ActionRow("开始摘要", null) { onNavigate(BranchSummaryChoice.SummarizeWithPrompt, instructions.trim().ifEmpty { null }) }
                    // TUI：自定义指令编辑器里取消 → 回到摘要选择。
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

private val ROW_GAP = 6.dp
private val ROW_PAD = 7.dp
private val ROW_MIN = 36.dp

/** 一级缩进的宽度（SDK 是 3 个字符）。连线按它画，所以各级竖线在所有行上对得齐。 */
private val LEVEL = 18.dp
private val FOLD_SLOT = 22.dp
private val ICON = 15.dp
private val SEARCH_HEIGHT = 36.dp
private val SEARCH_PAD = 10.dp
private val BOX_MIN = 96.dp
