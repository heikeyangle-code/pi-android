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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.SessionTreeNode
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
            if (view.rows.size == view.totalEntries) {
                tr("${view.totalEntries} 条 · ${filter.hint}", "${view.totalEntries} entries · ${filter.hint}")
            } else {
                tr("显示 ${view.rows.size} / ${view.totalEntries} · ${filter.hint}", "Showing ${view.rows.size} / ${view.totalEntries} · ${filter.hint}")
            },
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = ROW_GAP),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
        if (view.rows.isEmpty()) {
            PiEmptyState(
                icon = Icons.Filled.Search,
                title = when {
                    query.isNotBlank() -> tr("没有匹配的条目", "No matching entries")
                    filter == TreeFilter.Labeled -> tr("还没有书签", "No labeled entries yet")
                    else -> tr("这个过滤下没有条目", "No entries in this filter")
                },
                body = if (filter == TreeFilter.Labeled && query.isBlank()) {
                    tr("点开任意一条，给它起个标签，之后在这里一键回到那里。", "Tap any entry to add a label, then jump back to it from here.")
                } else {
                    tr("换个过滤或关键词试试。", "Try a different filter or search query.")
                },
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
                PiSettingsChip(text = tr("标签时间", "Timestamps"), glyph = if (showStamps) "✓" else "○", active = showStamps, onClick = onToggleStamps)
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
                    if (query.isEmpty()) Text(tr("搜索这棵树（空格分词，全部命中）", "Search tree (space-separated, match all)"), style = PiTheme.text.meta, color = PiTheme.palette.dim)
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
                    Text(tr("清除", "Clear"), modifier = Modifier.clickable { onQuery("") }.padding(ROW_GAP), style = PiTheme.text.meta, color = PiTheme.palette.accent)
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
        Text(tr("停止摘要", "Abort summary"), modifier = Modifier.clickable(onClick = onAbort).padding(ROW_GAP), style = PiTheme.text.meta, color = PiTheme.palette.accent)
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
            .background(if (item.isLeaf) p.selectedBg.copy(alpha = 0.55f) else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val unit = LEVEL.toPx()
                val pageStart = PiSpacing.pageHorizontal.toPx()
                val mid = size.height / 2f
                if (item.isLeaf) {
                    val barW = 3.dp.toPx()
                    drawRect(
                        color = p.accent,
                        topLeft = Offset(0f, 4.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(barW, (size.height - 8.dp.toPx()).coerceAtLeast(0f)),
                    )
                }
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
            Text(tr("不发给模型", "Hidden"), modifier = Modifier.padding(start = ROW_GAP), style = PiTheme.text.meta, color = p.dim, maxLines = 1)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = PiSpacing.unit),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.typeTitle,
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                    color = p.text,
                )
                if (item.isLeaf) {
                    Text(
                        tr("当前位置", "Current"),
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(p.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = p.accent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    fullText,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = PiTheme.text.prose,
                    color = p.text,
                )
            }
            when (step) {
                Step.Actions -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        if (!item.isLeaf) {
                            val hint = buildString {
                                append(
                                    if (item.rewinds) tr("回到此消息之前并放回输入框改写重发", "Rewind before this message and restore it to the editor")
                                    else tr("切换当前会话分支位置到这里继续对话", "Switch the active session branch to this point"),
                                )
                                if (streaming) append(tr("（将先停止本轮）", " (will abort current turn)"))
                            }
                            ActionRow(
                                title = tr("跳转到这里", "Navigate here"),
                                hint = hint,
                                icon = Icons.Filled.PlayArrow,
                                tint = p.accent,
                            ) {
                                if (skipSummaryPrompt()) onNavigate(BranchSummaryChoice.NoSummary, null) else step = Step.SummaryChoice
                            }
                        }
                        if (item.forkable) {
                            ActionRow(
                                title = tr("从这里分叉", "Fork from here"),
                                hint = tr("新建独立会话文件，原会话不变", "Create a new session file; original stays intact"),
                                icon = Icons.Filled.AccountTree,
                                tint = p.success,
                                onClick = onFork,
                            )
                        }
                        ActionRow(
                            title = if (item.label == null) tr("加标签（书签）", "Add label") else tr("改标签", "Edit label"),
                            hint = item.label?.let { tr("当前：「$it」", "Current: \"$it\"") } ?: tr("方便在「书签」里一键定位", "Quickly locate under Labeled only"),
                            icon = Icons.Filled.BookmarkBorder,
                            tint = p.warning,
                        ) { step = Step.Label }
                        if (item.contextEditable && !item.hiddenFromContext) {
                            ActionRow(
                                title = tr("不再发给模型", "Exclude from context"),
                                hint = tr("对话记录保留，后续上下文不再携带", "Keep in transcript, omit from future model context"),
                                icon = Icons.Filled.VisibilityOff,
                                tint = p.muted,
                                onClick = onHide,
                            )
                            ActionRow(
                                title = tr("改写模型看到的内容", "Edit in context"),
                                hint = tr("仅改上下文版本，原文不变", "Modify context version only; original stays intact"),
                                icon = Icons.Filled.Edit,
                                tint = p.mdLink,
                            ) { step = Step.Replace }
                        }
                        if (item.copyText != null) {
                            ActionRow(
                                title = tr("复制文本", "Copy text"),
                                hint = null,
                                icon = Icons.Filled.ContentCopy,
                                tint = p.muted,
                            ) { clipboard.setText(AnnotatedString(fullText)); onDismiss() }
                        }
                    }
                }
                Step.SummaryChoice -> {
                    Text(tr("要摘要被放弃的那一段吗？", "Summarize abandoned branch?"), style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                    Text(tr("摘要会调用一次模型，可随时中止；关闭面板回到树。", "Summarizing calls the model and can be aborted; dismiss to return to tree."), style = PiTheme.text.meta, color = p.muted)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        ActionRow(tr("不摘要", "No summary"), tr("直接切换，不消耗 token", "Switch immediately without using tokens")) { onNavigate(BranchSummaryChoice.NoSummary, null) }
                        ActionRow(tr("自动摘要", "Summarize"), tr("使用默认摘要提示词", "Use default summary prompt")) { onNavigate(BranchSummaryChoice.Summarize, null) }
                        ActionRow(tr("用自定义指令摘要…", "Summarize with custom prompt…"), tr("指定需要保留的关键信息", "Specify key details to preserve")) { step = Step.SummaryInstructions }
                    }
                }
                Step.SummaryInstructions -> {
                    TextBox(instructions, { instructions = it }, tr("摘要指令：要留住什么", "Summary instructions: what to preserve"))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TreePillButton(tr("返回", "Back"), primary = false) { step = Step.SummaryChoice }
                        TreePillButton(tr("开始摘要", "Summarize"), primary = true) { onNavigate(BranchSummaryChoice.SummarizeWithPrompt, instructions.trim().ifEmpty { null }) }
                    }
                }
                Step.Label -> {
                    TextBox(labelText, { labelText = it }, tr("标签，例如「方案 A 之前」", "Label, e.g. \"Before plan A\""), singleLine = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        if (item.label != null) {
                            TreePillButton(tr("清除标签", "Clear label"), primary = false) { onLabel(null) }
                        }
                        TreePillButton(tr("返回", "Back"), primary = false) { step = Step.Actions }
                        TreePillButton(tr("保存", "Save"), primary = true) { onLabel(labelText.trim().ifEmpty { null }) }
                    }
                }
                Step.Replace -> {
                    TextBox(replacement, { replacement = it }, tr("模型将看到的内容", "Content sent to model"))
                    Text(tr("只影响之后发给模型的上下文，不改原始记录。", "Only affects future model context; original transcript is unchanged."), style = PiTheme.text.meta, color = p.muted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TreePillButton(tr("返回", "Back"), primary = false) { step = Step.Actions }
                        TreePillButton(tr("保存改写", "Save edit"), primary = true) { onReplace(replacement) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreePillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val p = PiTheme.palette
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (primary) p.accent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) MaterialTheme.colorScheme.onPrimary else p.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    hint: String?,
    icon: ImageVector? = null,
    tint: Color = PiTheme.palette.accent,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = PiTheme.palette.text)
            if (hint != null) Text(hint, style = PiTheme.text.meta, color = PiTheme.palette.muted)
        }
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
