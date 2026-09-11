package app.pi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiMessage
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The session tree (pi's `/tree`) plus the raw entry log.
 *
 * Two tabs, because pi's own tree view and its session file answer different
 * questions and the app can only reach one of them:
 *
 *  - **分支** is `get_tree` — pi's resolved branch structure with labels applied
 *    (`session-manager.ts` `getTree`, `label`/`labelTimestamp` resolved before the
 *    answer is sent).
 *  - **条目** is `get_entries` — the append-only record. This is the only place
 *    an extension's `pi.appendEntry(customType, data)` state is visible at all:
 *    those entries are deliberately outside the model's context, the reducer
 *    treats them as inert, and `entry_appended` carries them only live
 *    (`agent-session.ts:2616-2621`; audit §1.5, §5.8).
 *
 * A tree node offers 分支 only where pi accepts one: `fork` without options
 * requires a **user** `message` entry and throws "Invalid entry ID for forking"
 * for anything else (`agent-session-runtime.ts:274-287`). Offering it on every
 * row would produce a guaranteed error, so the rule is enforced in the UI with
 * pi's own condition.
 *
 * **What 分支 is not.** pi's `/tree` moves the active leaf to a previous point
 * and lets you continue there *without creating a file* (`docs/sessions.md:71`,
 * `interactive-mode.ts:5216-5322`); the RPC protocol exposes no command for that
 * — the only tree commands are `get_tree` and `fork` (`rpc-types.ts:20-74`). The
 * button on each row is therefore a **fork**: it writes a *new* session file
 * (`docs/sessions.md:118-127`). The header above the tree says so, because a
 * button labelled 分支 next to a tree view otherwise reads as "jump to this
 * point", which it cannot do.
 *
 * The filter modes are pi's own (`interactive-mode.ts`'s tree selector,
 * `FilterMode` in `components/tree-selector.ts:95`): default, no-tools,
 * user-only, labeled-only, all — cycled here because a phone has no Ctrl+O.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTreeScreen(
    state: PiSessionViewModel.UiState,
    onFork: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(TreeFilter.Default) }
    var query by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("会话树") },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen),
            ) {
                listOf("分支", "条目").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = tab == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }
            if (tab == 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("筛选条目文字") },
                        textStyle = PiTheme.text.mono,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { filter = filter.next() }) { Text(filter.label) }
                }
                Text(
                    "分叉会新建一个会话文件，原会话保持不变。",
                    modifier = Modifier.padding(horizontal = PiSpacing.screen),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(PiSpacing.unit))
            if (tab == 0) {
                BranchTab(
                    state = state,
                    filter = filter,
                    query = query,
                    onFork = onFork,
                    modifier = Modifier.weight(1f),
                )
            } else {
                EntriesTab(entries = state.entries, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BranchTab(
    state: PiSessionViewModel.UiState,
    filter: TreeFilter,
    query: String,
    onFork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree = state.tree?.tree.orEmpty()
    val rows = remember(tree, state.tree?.leafId, filter, query) {
        flattenTree(tree).filter { row ->
            passesTreeFilter(row, state.tree?.leafId, filter) &&
                (query.isBlank() || treeRowText(row).contains(query, ignoreCase = true))
        }
    }
    if (tree.isEmpty()) {
        PiEmptyState(
            icon = Icons.Filled.Close,
            title = if (state.busy != null) "正在读取…" else "还没有分支",
            body = "会话有第一条消息后，这里会显示分支结构。",
            modifier = modifier,
        )
        return
    }
    if (rows.isEmpty()) {
        PiEmptyState(
            icon = Icons.Filled.Close,
            title = "没有匹配的条目",
            body = "当前筛选是「${filter.label}」。换一个关键词，或再按一次筛选按钮循环到下一种模式。",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PiSpacing.screen,
            end = PiSpacing.screen,
            bottom = PiSpacing.unit,
        ),
    ) {
        items(rows, key = { it.node.entry.id ?: it.path }) { row ->
            BranchRow(
                row = row,
                isLeaf = row.node.entry.id != null && row.node.entry.id == state.tree?.leafId,
                onFork = onFork,
            )
        }
    }
}

/** One tree node with its depth and a stable path, for a flat list. */
data class TreeRow(val node: SessionTreeNode, val depth: Int, val path: String)

/**
 * Depth-first flattening. pi's tree is an ordered forest (`getTree` returns the
 * roots in creation order), and drawing it as a flat list keeps every row's
 * height uniform — a phone cannot render the TUI's box-drawing branches legibly,
 * so indentation carries the structure instead.
 */
fun flattenTree(roots: List<SessionTreeNode>, depth: Int = 0, prefix: String = ""): List<TreeRow> =
    roots.flatMapIndexed { index, node ->
        val path = "$prefix/$index"
        listOf(TreeRow(node, depth, path)) + flattenTree(node.children, depth + 1, path)
    }

/**
 * pi's five tree filter modes, in the order its own selector cycles them
 * (`components/tree-selector.ts:1066-1073`).
 */
enum class TreeFilter(val label: String) {
    Default("默认"),
    NoTools("无工具"),
    UserOnly("仅提问"),
    LabeledOnly("仅有标签"),
    All("全部");

    fun next(): TreeFilter = entries[(ordinal + 1) % entries.size]
}

/**
 * `TreeSelectorComponent`'s visibility rules (`components/tree-selector.ts:340-395`),
 * reproduced:
 *
 *  - an assistant message with no text is hidden unless it is the current leaf or
 *    it stopped with an error/abort — the latter needs `stopReason`, which the
 *    wire model does carry (`PiMessage.Assistant.stopReason`);
 *  - `default` hides the settings/bookkeeping entries (labels, custom, model and
 *    thinking changes, session info);
 *  - `no-tools` additionally hides tool results, `user-only` keeps user messages,
 *    `labeled-only` keeps labelled nodes, `all` keeps everything.
 */
private fun passesTreeFilter(row: TreeRow, leafId: String?, mode: TreeFilter): Boolean {
    val entry = row.node.entry
    val isCurrentLeaf = entry.id != null && entry.id == leafId
    if (entry is SessionEntry.Message && entry.message.role == "assistant" && !isCurrentLeaf) {
        val assistant = entry.message as? PiMessage.Assistant
        val hasText = assistant?.text?.isNotBlank() == true
        val stoppedBadly = assistant?.stopReason != null &&
            assistant.stopReason != "stop" &&
            assistant.stopReason != "toolUse"
        if (!hasText && !stoppedBadly) return false
    }
    val isSettingsEntry = entry is SessionEntry.Label ||
        entry is SessionEntry.Custom ||
        entry is SessionEntry.ModelChange ||
        entry is SessionEntry.ThinkingLevelChange ||
        entry is SessionEntry.SessionInfo
    return when (mode) {
        TreeFilter.UserOnly -> entry is SessionEntry.Message && entry.message.role == "user"
        TreeFilter.NoTools ->
            !isSettingsEntry && !(entry is SessionEntry.Message && entry.message.role == "toolResult")

        TreeFilter.LabeledOnly -> row.node.label != null
        TreeFilter.All -> true
        TreeFilter.Default -> !isSettingsEntry
    }
}

/** What a row's search matches on: its summary plus its pi-resolved label. */
private fun treeRowText(row: TreeRow): String =
    listOfNotNull(entryLabel(row.node.entry), entrySummary(row.node.entry), row.node.label)
        .joinToString("\n")

@Composable
private fun BranchRow(row: TreeRow, isLeaf: Boolean, onFork: (String) -> Unit) {
    val entry = row.node.entry
    val id = entry.id
    val canFork = entry is SessionEntry.Message && entry.message.role == "user" && id != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 12).dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A thin rule instead of box-drawing characters: it survives the phone's
        // proportional UI font, which pi's `├──` glyphs do not.
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(
                    if (isLeaf) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entryLabel(entry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                row.node.label?.let { label ->
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = PiShapes.badge, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (isLeaf) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                entrySummary(entry),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canFork && id != null) {
            // "分叉为新会话" and not "分支": the action writes a new session file,
            // it does not move the leaf inside this session.
            TextButton(onClick = { onFork(id) }) { Text("分叉新会话") }
        }
    }
}

@Composable
private fun EntriesTab(entries: List<SessionEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        PiEmptyState(
            icon = Icons.Filled.Close,
            title = "没有条目",
            body = "这个会话还没有写入任何条目。",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PiSpacing.screen,
            end = PiSpacing.screen,
            bottom = PiSpacing.unit,
        ),
    ) {
        items(entries, key = { it.id ?: it.hashCode().toString() }) { entry ->
            EntryRow(entry)
        }
    }
}

@Composable
private fun EntryRow(entry: SessionEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.type,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.id.orEmpty(),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // `custom` is the extension-state entry: its payload is the whole point of
        // showing the log, so it is printed rather than summarised away.
        val detail = entryDetail(entry)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Short kind label for a tree row, using pi's entry `type` names. */
private fun entryLabel(entry: SessionEntry): String = when (entry) {
    is SessionEntry.Message -> entry.message.role
    is SessionEntry.Compaction -> "compaction"
    is SessionEntry.BranchSummary -> "branch_summary"
    else -> entry.type
}

/** A one-line gist of an entry, for the branch view. */
private fun entrySummary(entry: SessionEntry): String = when (entry) {
    is SessionEntry.Message -> entry.message.text.lineSequence().firstOrNull().orEmpty().take(160)
    is SessionEntry.Compaction -> entry.summary.orEmpty().take(160).ifEmpty { "（无摘要）" }
    is SessionEntry.BranchSummary -> entry.summary.orEmpty().take(160).ifEmpty { "（无摘要）" }
    is SessionEntry.ModelChange -> "${entry.provider.orEmpty()}/${entry.modelId.orEmpty()}"
    is SessionEntry.ThinkingLevelChange -> entry.thinkingLevel.orEmpty()
    is SessionEntry.Label -> "${entry.targetId.orEmpty()} → ${entry.label.orEmpty()}"
    is SessionEntry.SessionInfo -> entry.name.orEmpty()
    is SessionEntry.CustomMessage -> entry.customType.orEmpty()
    is SessionEntry.Custom -> entry.customType.orEmpty()
    is SessionEntry.Unknown -> entry.type
}

/** The detail block in the entry log. `custom` payloads are shown in full. */
private fun entryDetail(entry: SessionEntry): String = when (entry) {
    is SessionEntry.Message -> entry.message.text.take(400)
    is SessionEntry.Compaction -> entry.summary.orEmpty().take(400)
    is SessionEntry.BranchSummary -> entry.summary.orEmpty().take(400)
    is SessionEntry.ModelChange -> "provider=${entry.provider.orEmpty()} model=${entry.modelId.orEmpty()}"
    is SessionEntry.ThinkingLevelChange -> "level=${entry.thinkingLevel.orEmpty()}"
    is SessionEntry.Label -> "target=${entry.targetId.orEmpty()} label=${entry.label.orEmpty()}"
    is SessionEntry.SessionInfo -> "name=${entry.name.orEmpty()}"
    is SessionEntry.CustomMessage -> "customType=${entry.customType.orEmpty()} display=${entry.display}"
    // The extension's own state, verbatim: this is the audit's §5.14 item 1.
    is SessionEntry.Custom -> "customType=${entry.customType.orEmpty()}\n${entry.data?.toString().orEmpty()}"
    is SessionEntry.Unknown -> entry.raw.toString()
}
