package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiMessage
import app.pi.rpc.SessionEntry
import app.pi.rpc.SessionTreeNode
import app.pi.ui.PiSeg
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
 * A tree node offers 分叉 only where pi accepts one: `fork` without options
 * requires a **user** `message` entry and throws "Invalid entry ID for forking"
 * for anything else (`agent-session-runtime.ts:274-287`). Offering it on every
 * row would produce a guaranteed error, so the rule is enforced in the UI with
 * pi's own condition.
 *
 * ## Two actions, two different pi behaviours
 *
 * They are not two spellings of one thing, and this screen keeps them apart:
 *
 *  - **跳转** is `navigateTree`: the leaf moves **inside this session file** — nothing new
 *    is created, and the conversation continues from the chosen point
 *    (`core/agent-session.ts:3126-3127`). It is offered on **every** row, because
 *    `navigateTree` accepts any entry id; where the leaf actually lands depends on the
 *    entry, and that rule is pinned in [NavigateLanding].
 *  - **分叉** is `fork`: it writes a **new session file**
 *    (`agent-session-runtime.ts:289-352`), leaving the original untouched.
 *
 * 跳转 reaches pi through the shipped bridge extension's `pi-android-navigate` command,
 * because RPC exposes no navigate command (`rpc-types.ts:20-74`) while `rpc-mode.ts` does
 * wire `ctx.navigateTree` for extensions (`:329-335`).
 *
 * The filter modes are pi's own (`interactive-mode.ts`'s tree selector,
 * `FilterMode` in `components/tree-selector.ts:95`): default, no-tools,
 * user-only, labeled-only, all — cycled here because a phone has no Ctrl+O.
 *
 * ## Embedded mode
 *
 * [embedded] is the one shape change this batch makes (`05-compose-migration-plan.md`
 * §3.6), and it carries no tree logic with it. The session tree is no longer a second
 * overlay: it is the second **view** of the session-list overlay, switched by one
 * segmented control (`SessionsScreen`), because two overlays stacked on each other
 * leaves only the top one closable by the back key — and there is exactly one
 * `BackHandler` in this app (`PiRoot`), by construction.
 *
 * When true, the caller already owns the top bar, the opaque backdrop and the
 * padding, so this composable draws only its own filter row and list. When false it
 * is still the whole screen, which is what the earlier overlay shape needed and what
 * a preview or a test can render on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTreeScreen(
    state: PiSessionViewModel.UiState,
    onFork: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    /**
     * Read the raw entry log (the 条目 tab).
     *
     * Supplied by the overlay's owner, which holds the ViewModel. The log is a
     * whole-session `get_entries` — by far the most expensive read on this screen —
     * so it is requested when the tab that shows it is opened rather than when the
     * tree is, and the tree therefore paints without waiting for it.
     */
    onLoadEntries: () -> Unit = {},
    /**
     * 跳转 — pi's `navigateTree` (see [NavigateLanding] for where the leaf lands). The
     * caller dispatches it; this screen only asks pi's own "Summarize branch?" question
     * first and hands the answer over.
     */
    onNavigate: (String, BranchSummaryChoice, String?) -> Unit = { _, _, _ -> },
    /**
     * `branchSummary.skipPrompt`. Read lazily at tap time rather than passed as a value:
     * it is a pi setting, the ViewModel owns the settings document, and a copy in this
     * screen's state would go stale the moment the user changed it in pi's own terminal.
     */
    skipSummaryPrompt: () -> Boolean = { false },
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    // The 条目 tab's data on first sight of it: the tree view is what opens, and the
    // raw record is a second, heavier read (`PiSessionViewModel.refreshEntries`).
    LaunchedEffect(tab) { if (tab != 0) onLoadEntries() }
    var filter by rememberSaveable { mutableStateOf(TreeFilter.Default) }
    var query by rememberSaveable { mutableStateOf("") }
    if (embedded) {
        // No `Surface`: the caller's overlay already paints the opaque backdrop, and
        // a second one would be a surface over a surface for no visual gain. No top
        // bar either — the overlay's own bar is the only one on screen, which is
        // also what keeps the back key's owner single.
        TreeContent(
            state = state,
            tab = tab,
            onTabChange = { tab = it },
            filter = filter,
            onCycleFilter = { filter = filter.next() },
            query = query,
            onQueryChange = { query = it },
            onFork = onFork,
            onNavigate = onNavigate,
            skipSummaryPrompt = skipSummaryPrompt,
            modifier = modifier,
        )
    } else {
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
                TreeContent(
                    state = state,
                    tab = tab,
                    onTabChange = { tab = it },
                    filter = filter,
                    onCycleFilter = { filter = filter.next() },
                    query = query,
                    onQueryChange = { query = it },
                    onFork = onFork,
                    onNavigate = onNavigate,
                    skipSummaryPrompt = skipSummaryPrompt,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The two tabs, in pi's order: the resolved tree (`get_tree`) and the raw entries. */
private val TREE_TABS = listOf("分支", "条目")

/**
 * 筛选框的取值，全部照 v2 的 tree 台（`direction-b-v2.html` 的 `SessionsOverlay` tree
 * 分支）：高 36、圆角 9、内 10、`gap:8`、图标 15。筛选档位那句用的是同一个 `gap` 作为
 * 它与框之间的 `marginTop:8`（v2 那句说明文字的位置）。
 */
private val TREE_FILTER_HEIGHT = 36.dp
private val TREE_FILTER_RADIUS = 9.dp
private val TREE_FILTER_PADDING = 10.dp
private val TREE_FILTER_GAP = 8.dp
private val TREE_FILTER_ICON = 15.dp

/**
 * 树本身：分支 / 条目两个 tab + 筛选行 + 列表。
 *
 * 抽出来只为了 [SessionTreeScreen] 的两种外壳（整屏 / 嵌进覆盖层）共用同一份内容——
 * 里面没有一行逻辑是新的。
 */
@Composable
private fun TreeContent(
    state: PiSessionViewModel.UiState,
    tab: Int,
    onTabChange: (Int) -> Unit,
    filter: TreeFilter,
    onCycleFilter: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onFork: (String) -> Unit,
    onNavigate: (String, BranchSummaryChoice, String?) -> Unit,
    skipSummaryPrompt: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    // The row whose 跳转 is being decided. pi asks its own question before navigating
    // (`interactive-mode.ts:5236-5263`), so the answer is collected here and the caller is
    // told once — the dialog is dismissed first, because pi closes its selector before
    // asking (`:5229`).
    var navigateTarget by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        // 分支 / 条目 是真功能（分支 = pi 的 `get_tree`，条目 = entries），**不删**；
        // 换的是组件语言：v2 的分段控件是 `Seg`（`direction-b-v2.html:644-657`），
        // 由乙在 `ui/PiRoot.kt` 新建为 `PiSeg`，与它会话列表里换的那套是同一个。
        // v2 的 tree 台（phone27/28）静态图上没有这个控件（那两台只画了筛选行）——
        // 保留功能、统一组件语言，是父代理批准的刻意差异。
        //
        // 位置照 v2 画 `Seg` 的那一行（`:1853`：`padding:10px 14px 6px`，`Seg` 靠左，
        // 不撑满）——M3 的 `SingleChoiceSegmentedButtonRow` 是 `fillMaxWidth` 的，
        // 而 board 上这个控件是 hug content 的。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSpacing.pageHorizontal,
                    end = PiSpacing.pageHorizontal,
                    top = PiSpacing.inner,
                    bottom = PiSpacing.gutter,
                ),
        ) {
            PiSeg(
                options = TREE_TABS,
                selectedIndex = tab,
                onSelect = onTabChange,
            )
        }
        if (tab == 0) {
            // 筛选行：v2 在 tree 台上画的是**和会话列表同一个**方框（`SessionsOverlay`
            // 的 tree 分支：`height:36`、圆角 9、`surf-low` 底、1px `borderMuted`、内
            // `padding:0 10px`、`gap:8`、图标 15、文字 `mono t12`），筛选档位是框内**右端
            // 那句 12 号灰字**（点它循环下一档）。旧实现是 M3 的 `OutlinedTextField` +
            // `TextButton`：56 高、带浮动 label、按 Material 自己的描边画，夹在 v2 的框与
            // 卡片之间，两样的语言都不一样 —— `phone27`/`phone28` 两张图里都是一个扁框加
            // 一个「默认」。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(TREE_FILTER_HEIGHT),
                    shape = RoundedCornerShape(TREE_FILTER_RADIUS),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(PiSpacing.hairline, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = TREE_FILTER_PADDING),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TREE_FILTER_GAP),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(TREE_FILTER_ICON),
                            tint = PiTheme.palette.muted,
                        )
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    "筛选条目文字",
                                    style = PiTheme.text.monoSmall,
                                    color = PiTheme.palette.muted,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = PiTheme.text.monoSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(PiTheme.palette.accent),
                            )
                        }
                        Text(
                            filter.label,
                            modifier = Modifier.clickable(onClick = onCycleFilter),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // 说明句连同它下面那道 18dp 间距**都搬进了列表**（`TreeForkHint` /
        // `TREE_HINT_ITEM_KEY`），所以固定 chrome 里只剩「条目」页签还需要这段间距。
        // 取舍写在 `TREE_FORK_HINT` 的 KDoc 里：手机竖屏最贵的是列表视口，而那句
        // 只在第一次进树时有用，让它跟列表一起滚走就能把这约 62dp 还给树。
        if (tab != 0) Spacer(Modifier.height(PiSpacing.unit))
        if (tab == 0) {
            BranchTab(
                state = state,
                filter = filter,
                query = query,
                onFork = onFork,
                onJump = { entryId ->
                    // pi's `/tree` answers a pick on the current leaf with "Already at this
                    // point" and does nothing (`interactive-mode.ts:5221-5226`), so the
                    // question is not asked at all; the ViewModel says the sentence.
                    if (entryId == state.tree?.leafId) {
                        onNavigate(entryId, BranchSummaryChoice.NoSummary, null)
                    } else if (skipSummaryPrompt()) {
                        // `branchSummary.skipPrompt` = "always default to no summary"
                        // (`interactive-mode.ts:5235-5236`), so the question is skipped
                        // entirely rather than answered for the user.
                        onNavigate(entryId, BranchSummaryChoice.NoSummary, null)
                    } else {
                        navigateTarget = entryId
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            EntriesTab(entries = state.entries, modifier = Modifier.weight(1f))
        }
    }

    // pi's own question, asked after the pick and before the navigation
    // (`interactive-mode.ts:5229-5262`). Composed outside the `Column` above so it is not
    // a layout child of the list — it draws in its own window.
    navigateTarget?.let { target ->
        NavigateSummaryDialog(
            onDismiss = { navigateTarget = null },
            onChoose = { choice, instructions ->
                navigateTarget = null
                onNavigate(target, choice, instructions)
            },
        )
    }
}

/**
 * "Summarize branch?" — pi's three answers, verbatim
 * (`interactive-mode.ts:5238-5242`: "No summary" / "Summarize" / "Summarize with custom
 * prompt"), plus the instructions editor the third one opens (`:5252-5258`).
 *
 * The labels are translated because this is a question *this app* is asking, not a string
 * pi sent us — pi's own sentences are only ever quoted where they arrive from the wire
 * (see `navigateFailureText`). The three answers map one-to-one onto [BranchSummaryChoice]
 * and nothing is added: no "always do this" checkbox, no default that pi does not have.
 * pi's fourth behaviour — Escape at this question abandons the whole navigation rather than
 * answering it (`:5244-5248`) — is what 取消 does here.
 */
@Composable
private fun NavigateSummaryDialog(
    onDismiss: () -> Unit,
    onChoose: (BranchSummaryChoice, String?) -> Unit,
) {
    // Which answer is being refined: null = the question, non-null = the instructions editor
    // for the custom form. One dialog with two steps rather than two, because pi's loop
    // returns to the question if the editor is cancelled (`interactive-mode.ts:5254-5257`).
    var instructing by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf("") }

    if (instructing) {
        AlertDialog(
            onDismissRequest = { instructing = false },
            title = { Text("摘要指令") },
            text = {
                Column {
                    Text(
                        "告诉 pi 这次摘要要留住什么。它会替换掉默认的摘要提示词。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(PiSpacing.inner))
                    BasicTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        textStyle = PiTheme.text.monoSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(PiTheme.palette.accent),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onChoose(BranchSummaryChoice.SummarizeWithPrompt, instructions) }) {
                    Text("开始摘要")
                }
            },
            // Back to the question, exactly as pi loops (`:5254-5257`) — not out of the
            // navigation.
            dismissButton = { TextButton(onClick = { instructing = false }) { Text("返回") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("要摘要被放弃的那一段吗？") },
        text = {
            Text(
                "pi 可以把你要离开的那段对话总结成一条分支摘要，留在新的位置上。" +
                    "摘要是一次模型调用，可能等一会儿。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoose(BranchSummaryChoice.Summarize, null) }) { Text("摘要") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { instructing = true }) { Text("用自定义指令") }
                TextButton(onClick = { onChoose(BranchSummaryChoice.NoSummary, null) }) { Text("不摘要") }
            }
        },
    )
}

/**
 * 树页签那句说明的**唯一一份文案**，以及它现在画在哪里。
 *
 * ## 文案改过一次（D1，2025 会话内跳转）
 *
 * 它从前写的是「pi 的 RPC 没有『跳到这一点』的命令（那是终端界面的内部功能），所以这里只能
 * 新建分支。」——**当时是对的，现在不是**：`pi-android-navigate` 命令（随包扩展
 * `pi-android-bridge`）把 `ctx.navigateTree` 接了进来（`rpc-mode.ts:329-335`），树行上因此
 * 多了一个**真的会跳**的动作。留着旧句子就等于在界面上说一个谎，所以这句换成了现在这两件事
 * 的分工说明：点一行是跳转（同一个会话文件内换 leaf），「分叉」是新建一个会话文件。
 *
 * 它替掉的仍是 v2 那句「分叉会新建一个会话文件，原会话保持不变。」——`11-designer-adjudication.md`
 * 的 A-08 裁定「删掉等于把这些能力从界面上抹掉」，所以这句在，只是把「两个动作分别是什么」
 * 说全；间距也仍是一个 `TREE_FILTER_GAP` + 一个 `PiSpacing.unit`。
 *
 * ## 为什么从固定行搬进列表（**相对稿子的有意偏离**）
 *
 * 稿子里这句是**固定行**：`direction-b-v2.html:1918`，紧跟筛选框（`marginTop:8`，和这里
 * 的 `TREE_FILTER_GAP` 同一个值）。但同一个稿子在 `:1946` 又画了它一遍——**在滚动区里**
 * （`b-scroll`，列表/空态之后，`padding:16px 14px 4px`）。也就是说「这句话是可以滚走的
 * 正文」本来就是稿子的意思；这里只是换了一头：放在**列表第一条**而不是末尾，因为手机竖屏
 * 最贵的资源就是列表视口，而这句话只在第一次进树时有用——放末尾要滑到底才看得见，也就不
 * 可能把视口还回来。（另：`workspace-final.html` 是工作区那台，里面没有会话树，那份稿子
 * 对这条没有发言权。）
 *
 * ## 回收了多少
 *
 * 滚走的是它的**整条**：8dp 上间距（`TREE_FILTER_GAP`）+ 两行 18sp 行高（`PiTheme.text.meta`
 * 是 12sp/18sp，360dp 屏上这句 44 个字符正好两行，≈36dp）+ 18dp 下间距（`PiSpacing.unit`，
 * 搬进来的那道，见下）≈ **62dp**。列表没滚动时（scroll 0）它与搬运前逐像素一致：固定
 * chrome 里删掉的那 18dp 间距正好被这条的下间距补上。
 *
 * 空态仍留在原位（说明在上、空态在下，与搬运前逐像素一致）：空态没有东西可滚，而这两句
 * 正文都不重复「跳转 ≠ 分叉」这个区分，删掉它等于把 A-08 的措辞又抹一次。
 *
 * 也就是说：**只有真有一条条节点时**这句才滚得走，滚走的正是那 62dp。
 *
 * 长度与旧句相当（旧句 44 个字符、两行；这句 48 个字符，同样是两行），所以上面那笔 62dp
 * 的账不用重算。
 */
private const val TREE_FORK_HINT =
    "点一行是跳转：在同一个会话文件里回到那一点继续。分叉则会新建一个会话文件。"

/**
 * 说明句在列表里的 key：**常量**，不随筛选档位、查询词或会话变化。
 *
 * `LazyColumn` 靠 key 认条目，key 一变就等于换了一批内容——用 `rows.size`、索引或会话 id
 * 当 key 都会在筛选/搜索时把这行判成"新条目"，连带把滚动位置重置。一个常量 key 让这行在
 * 整屏生命周期里始终是同一条，滚动位置因此不会因为它的存在而跳。
 */
private const val TREE_HINT_ITEM_KEY = "tree:fork-hint"

/** 说明句本体：样式、颜色、水平边距与从前的固定行完全相同，只有位置变了。 */
@Composable
private fun TreeForkHint(modifier: Modifier = Modifier) {
    Text(
        TREE_FORK_HINT,
        modifier = modifier.padding(
            start = PiSpacing.pageHorizontal,
            end = PiSpacing.pageHorizontal,
            top = TREE_FILTER_GAP,
        ),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BranchTab(
    state: PiSessionViewModel.UiState,
    filter: TreeFilter,
    query: String,
    onFork: (String) -> Unit,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree = state.tree?.tree.orEmpty()
    val leafId = state.tree?.leafId
    val rows = remember(tree, leafId, filter, query) {
        // pi splits the query on whitespace and requires **every** token
        // (`components/tree-selector.ts:349-395`: `searchTokens.every(...)`, with the
        // tokens built from `searchQuery.toLowerCase().split(/\s+/)`). A single
        // `contains` — what this used to do — matched the whole query as one phrase,
        // so "read file" found nothing in a row that says "file … read" and a
        // two-word search behaved differently here than in pi for no reason.
        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        flattenTree(tree, leafId).filter { row ->
            passesTreeFilter(row, leafId, filter) &&
                tokens.all { treeRowText(row).contains(it, ignoreCase = true) }
        }
    }
    if (tree.isEmpty()) {
        // 空态不滚动，所以说明句留在原位（上），空态占满剩下的空间（下）——与搬运前
        // 逐像素一致。`modifier` 是父级给这一层的 `weight(1f)`，所以它落在外面这个
        // `Column` 上，里面的空态再自己吃一份 `weight(1f)`。
        Column(modifier) {
            TreeForkHint(Modifier.padding(bottom = PiSpacing.unit))
            PiEmptyState(
                icon = BRANCH_GLYPH,
                // v2's session-tree empty states use their own icon, not the π mark
                // (`direction-b-v2.html:1922`: `icon="branch"`). `PiEmptyState` defaults
                // to the mark because the *chat's* two engine empty states carry it.
                markPi = false,
                title = if (state.busy != null) "正在读取…" else "还没有分支",
                body = "会话有第一条消息后，这里会显示分支结构。",
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    if (rows.isEmpty()) {
        Column(modifier) {
            TreeForkHint(Modifier.padding(bottom = PiSpacing.unit))
            PiEmptyState(
                icon = BRANCH_GLYPH,
                // v2's session-tree empty states use their own icon, not the π mark
                // (`direction-b-v2.html:1922`: `icon="branch"`). `PiEmptyState` defaults
                // to the mark because the *chat's* two engine empty states carry it.
                markPi = false,
                title = "没有匹配的条目",
                body = "当前筛选是「${filter.label}」。换一个关键词，或再按一次筛选按钮循环到下一种模式。",
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PiSpacing.pageHorizontal,
            end = PiSpacing.pageHorizontal,
            // **The bottom inset is already reserved above this list, and this is the
            // proof, not a guess.** `SessionsScreen`'s overlay is one
            // `Column(Modifier.fillMaxSize().padding(contentPadding))` fed by `PiRoot`'s
            // `Scaffold`, and the embedded tree is a weighted child of it — so the tree
            // never reaches the window's bottom edge. Material3 makes that padding's
            // *bottom* the bottom bar's height whenever the `bottomBar` slot is
            // non-empty, and `PiRoot`'s slot is always composed: `PiBottomBar`, a
            // `height(BOTTOM_BAR_HEIGHT = 56.dp)` column. The list's viewport therefore
            // already ends at the bar's top edge, clear of the gesture area.
            //
            // So this value is the list's *own* breathing room, not the inset: the
            // design's 「滚动区底部留 14–18px」 (`06 §2`, `PiSpacing.scrollBottom`).
            // The sibling session list is padded by that same Column and then adds
            // `SESSIONS_LIST_BOTTOM = 14.dp` of its own — i.e. exactly this pattern.
            // Reserving the 56dp here *again* would leave a 56dp dead band under the
            // last row: the same "框住" complaint, mirrored. All the last row needs is a
            // scroll range that runs past it, and a `LazyColumn`'s `contentPadding`
            // extends the range by exactly this much — scrolling to the end puts the
            // last row fully in view with this 18dp, plus the row's own 6dp, below it.
            bottom = PiSpacing.scrollBottom,
        ),
    ) {
        // 说明句 = 列表第一条（取舍与回收量见 `TREE_FORK_HINT` 的 KDoc）。它是**滚动
        // 内容**，所以往下一滑就整条滚走，把这 62dp 还给树；`key` 是常量，筛选/搜索不会
        // 把它判成新条目、也就不会重置滚动位置。下间距 18dp 接着的是行自己的 6dp 上间距。
        item(key = TREE_HINT_ITEM_KEY) {
            TreeForkHint(Modifier.padding(bottom = PiSpacing.unit))
        }
        items(rows, key = { it.node.entry.id ?: it.path }) { row ->
            BranchRow(
                row = row,
                isLeaf = row.node.entry.id != null && row.node.entry.id == state.tree?.leafId,
                onFork = onFork,
                onJump = onJump,
            )
        }
    }
}

/** One tree node with its depth and a stable path, for a flat list. */
data class TreeRow(
    val node: SessionTreeNode,
    val depth: Int,
    val path: String,
    /**
     * True when this node is on the **root → leaf** path — pi's `activePathIds`
     * (`components/tree-selector.ts:180-195`, drawn from `:...`'s `isActivePath`
     * branch). It is what makes "where am I in this tree" answerable at all: the
     * leaf alone is one row, and in a session with branches the leaf's id says
     * nothing about which of the earlier forks is the live one.
     */
    val onActivePath: Boolean,
)

/**
 * The ids on the root → leaf path, walked through `parentId` exactly as pi's
 * `buildActivePath` does (`components/tree-selector.ts:180-195`).
 *
 * Frozen against a loop: a session file whose `parentId` chain cycles is
 * malformed, and pi's own walk would spin on it too, so the visited set is the
 * app's one addition (it costs one `add` and turns a hang into a short tree).
 */
fun activePathIds(roots: List<SessionTreeNode>, leafId: String?): Set<String> {
    if (leafId == null) return emptySet()
    val byId = HashMap<String, SessionTreeNode>()
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        node.entry.id?.let { byId[it] = node }
        pending.addAll(node.children)
    }
    val path = LinkedHashSet<String>()
    var current: String? = leafId
    while (current != null && path.add(current)) {
        current = byId[current]?.entry?.parentId
    }
    return path
}

/**
 * Depth-first flattening. pi's tree is an ordered forest (`getTree` returns the
 * roots in creation order, children sorted by timestamp), and drawing it as a flat
 * list keeps every row's height uniform — a phone cannot render the TUI's
 * box-drawing branches legibly, so indentation carries the structure instead.
 *
 * **The indent rule is pi's, not "one step per generation".** A child of a
 * single-child parent stays at the parent's indent; only a **branch point** (a node
 * with more than one child, and the one generation right after it) steps in
 * (`components/tree-selector.ts:288-297`). Stepping on every generation — what this
 * used to do — meant a plain linear conversation, which is the common case and has
 * no structure to show, walked 12 dp further right per message and left the screen
 * after ~25 rows: the deeper the session, the less of it was readable, and every
 * row carried an indent that encoded nothing. With pi's rule the indent *is* the
 * information: a step to the right means "a second branch starts here".
 *
 * **Order is pi's too.** The branch containing the current leaf is listed first at
 * every level (`containsActive`, `tree-selector.ts:236-285`), so the live path is
 * the first thing read top to bottom, and sibling order under it is still pi's
 * timestamps — `sortedByDescending` is stable, so this only lifts the active
 * subtree and never reshuffles what pi sent. With more than one root the forest is
 * pi's "virtual root that branches": every root indents one step
 * (`tree-selector.ts:262-266`).
 *
 * **Iterative, not recursive.** pi says why in `session-manager.ts:1350-1352`
 * ("Use iterative approach to avoid stack overflow on deep trees"); a session
 * resumed a few thousand times is deep enough to matter, and the recursion this
 * replaces would have died on it with a `StackOverflowError` in the middle of a
 * recomposition.
 */
fun flattenTree(roots: List<SessionTreeNode>, leafId: String?): List<TreeRow> {
    val active = activePathIds(roots, leafId)
    val multipleRoots = roots.size > 1

    // (node, indent, justBranched, path). Pushed in reverse so the pop order is the
    // reading order, the way pi's own stack works.
    data class Frame(val node: SessionTreeNode, val indent: Int, val justBranched: Boolean, val path: String)

    val orderedRoots = roots.sortedByDescending { it.entry.id != null && it.entry.id in active }
    val stack = ArrayDeque<Frame>()
    for (index in orderedRoots.indices.reversed()) {
        stack.addLast(
            Frame(
                node = orderedRoots[index],
                indent = if (multipleRoots) 1 else 0,
                justBranched = multipleRoots,
                path = "/$index",
            ),
        )
    }

    val rows = ArrayList<TreeRow>(stack.size)
    while (stack.isNotEmpty()) {
        val (node, indent, justBranched, path) = stack.removeLast()
        val id = node.entry.id
        rows += TreeRow(node, indent, path, onActivePath = id != null && id in active)

        val children = node.children.sortedByDescending { it.entry.id != null && it.entry.id in active }
        val childIndent = when {
            children.size > 1 -> indent + 1
            justBranched && indent > 0 -> indent + 1
            else -> indent
        }
        for (index in children.indices.reversed()) {
            stack.addLast(
                Frame(
                    node = children[index],
                    indent = childIndent,
                    justBranched = children.size > 1,
                    path = "$path/$index",
                ),
            )
        }
    }
    return rows
}

/** pi's query tokenizer: `searchQuery.toLowerCase().split(/\s+/)`. */
private val WHITESPACE = Regex("\\s+")

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

// ------------------------------------------------------------ the row's indent
//
// The indent *rate* is the design's (`direction-b-v2.html:3390`: 「缩进 = depth ×
// 14px」). What the design cannot have known is how deep a real session gets: this
// app forks session files constantly, and `flattenTree` steps the indent in at every
// branch point, so `depth` is unbounded in practice. Multiply an unbounded depth by a
// per-level step and the row's own text is what pays.
//
// The budget on a 360dp phone, with the old numbers:
//
//   item width        360 − 2×14 (LazyColumn's page margins)          = 332dp
//   fixed children    2dp rule + 8dp gap + M3 TextButton「分叉新会话」
//                     (5×14sp text + 2×12dp content padding)          = 104dp
//   text column       332 − 12·depth − 104  →  228dp at depth 0
//                                              156dp at depth 6
//                                               96dp at depth 11
//                                                0dp at depth 19
//
// Two defects live in that line, and they are the two the user reported. (1) The
// column shrinks *before* anything else does — every extra level is taken out of the
// label and the two-line summary, so the deeper the row, the more of its text is
// ellipsized away at the right edge ("右边那截看不到"), with no horizontal affordance
// because the list scrolls vertically only ("只能上下滑"). (2) Past ~depth 19 the
// weighted `Column` is allocated nothing at all; the row still occupies its full
// height and draws its rule, so it renders as a **blank band**. The deepest nodes are
// at the end of the depth-first flatten, i.e. exactly what the user scrolls to — which
// is what "往下滑…下面就空了" actually is. It is not a missing bottom inset: the
// overlay's `Column` has already reserved the bottom bar (see the note on
// `BranchTab`'s `LazyColumn`).
//
// So the step is capped, in two stages rather than with a hard clamp:
//
//   depth 0..4   12dp per level   (the design's rate, where it is legible)
//   depth 5..10   4dp per level   (still a visible step, so 6 and 7 stay tellable
//                                  apart; a hard clamp would draw them identically)
//   depth ≥ 10    flat at 72dp    (the ceiling is reached at depth 10)
//
// Worst case, the row at the ceiling on a 360dp phone: 332 − 72 − 10 − 58 = **192dp**
// of text column; on a 320dp phone, 152dp — still positive, so no row can collapse and
// nothing needs to be reached by dragging sideways. That is why this fix is "make the
// content stop overflowing" and **not** a horizontal scroll: a two-axis scroll inside
// a vertically-scrolling `LazyColumn` needs a nested-scroll arrangement to keep the
// vertical drag, and getting it wrong reproduces "只能上下滑" in the other direction.
//
// Only the *drawn* indent is compressed. `flattenTree` still computes pi's own depth,
// so ordering, filters, the active-path highlight and the fork action are untouched.

/** Levels drawn at the full [TREE_INDENT_STEP] before the step shortens. */
private const val TREE_INDENT_FULL_LEVELS = 4

/** The design's rate: 12dp per branch point, while it is still legible. */
private val TREE_INDENT_STEP = 12.dp

/** The shortened rate past [TREE_INDENT_FULL_LEVELS]: a step that still reads as one. */
private val TREE_INDENT_TAIL_STEP = 4.dp

/** How many levels past [TREE_INDENT_FULL_LEVELS] the shortened step keeps growing. */
private const val TREE_INDENT_TAIL_LEVELS = 6

/**
 * The deepest indent the row will ever draw: 4×12 + 6×4 = **72dp**.
 *
 * `internal` alongside [treeRowIndent] so both the function's shape and the ceiling it
 * promises can be asserted in a unit test — the 192dp text column of the worst case on
 * a 360dp phone is `360 − 2×14 − 72 − 10 − 58`.
 */
internal val TREE_INDENT_MAX =
    TREE_INDENT_STEP * TREE_INDENT_FULL_LEVELS + TREE_INDENT_TAIL_STEP * TREE_INDENT_TAIL_LEVELS

/**
 * The left inset a row at [depth] draws, in two stages (see the block above):
 * 0/12/24/36/48dp for depths 0–4, then +4dp per level up to a 72dp ceiling at depth 10.
 *
 * `internal` rather than `private` so the shape is testable without a device — the
 * stages and the ceiling are the whole fix, and a phone is not needed to see that
 * depth 40 draws the same 72dp as depth 10.
 */
internal fun treeRowIndent(depth: Int): Dp {
    if (depth <= 0) return 0.dp
    val full = depth.coerceAtMost(TREE_INDENT_FULL_LEVELS)
    val tail = (depth - TREE_INDENT_FULL_LEVELS).coerceIn(0, TREE_INDENT_TAIL_LEVELS)
    return TREE_INDENT_STEP * full + TREE_INDENT_TAIL_STEP * tail
}

@Composable
private fun BranchRow(
    row: TreeRow,
    isLeaf: Boolean,
    onFork: (String) -> Unit,
    onJump: (String) -> Unit,
) {
    val entry = row.node.entry
    val id = entry.id
    val canFork = entry is SessionEntry.Message && entry.message.role == "user" && id != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `treeRowIndent`, not `depth × 12dp`: an unbounded depth took the row's own
            // text off the right edge (see the block above). The *structure* still comes
            // from the depth — only the drawn step is capped.
            .padding(start = treeRowIndent(row.depth), top = 6.dp, bottom = 6.dp)
            // 跳转 is the row's own action, on the whole row rather than a third button:
            // the row already carries a label, an optional badge, 「当前」and 分叉, and the
            // KDoc on the 分叉 button records what a second full-width action cost the
            // summary's width. `navigateTree` accepts **any** entry id
            // (`agent-session.ts:3161-3164`), so unlike 分叉 this is offered everywhere —
            // and a row whose entry has no id cannot be named to pi at all, so it is not
            // clickable.
            .clickable(enabled = id != null) { id?.let(onJump) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A thin rule instead of box-drawing characters: it survives the phone's
        // proportional UI font, which pi's `├──` glyphs do not.
        //
        // The rule's colour is now pi's `activePathIds` reading, not the leaf's: an
        // ancestor of the leaf is "where this conversation actually came from" just
        // as much as the leaf is, and colouring only the leaf left the user unable to
        // tell the live branch from an abandoned one in a tree with three forks. It
        // is not decoration — it is the one mark that says which path is in effect.
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(
                    if (row.onActivePath) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The label row is *bounded* now, and that is part of the same fix: its
                // children used to be unweighted `Text`s, and a squeezed row made them
                // wrap instead of ellipsize — a 20dp-wide box turns 「toolResult」 into a
                // ten-line column and the row's height with it, which reads as "the list
                // went empty below". `weight(1f, fill = false)` on the two variable-length
                // pieces gives each a share it cannot exceed, "当前" is unweighted so it is
                // measured first and always keeps its room, and `maxLines = 1` +
                // `Ellipsis` means the worst case is a truncated word, never a taller row.
                Text(
                    entryLabel(entry),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.node.label?.let { label ->
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        shape = PiShapes.badge,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
            // "分叉" and not "分支": the action writes a new session file, it does not
            // move the leaf inside this session. That distinction is the load-bearing
            // part of the label and it is kept.
            //
            // What is gone is the full-width M3 button. 「分叉新会话」 at `labelLarge`
            // measured ~94dp (5×14sp + 2×12dp of `TextButton`'s default content padding),
            // a third of a 360dp row before the indent was even counted — this action was
            // the second-largest consumer of the width the row's own summary needed. The
            // compact form is the branch glyph plus the verb at `labelMedium` with 6dp of
            // content padding: ~58dp, `ButtonDefaults.MinWidth` being the floor. 「新会话」
            // is the part that is dropped, and the header sentence above the list already
            // says a fork writes a new session file — the row does not have to say it five
            // times over. The `TextButton` is kept (rather than a bare `Text` link, which
            // the design draws) because it carries the 48dp touch target, and the action
            // itself is unchanged: `onFork(id)`.
            TextButton(
                onClick = { onFork(id) },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Icon(
                    BRANCH_GLYPH,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "分叉",
                    // One line, always: a wrapped action label is what blew the row's
                    // height up on a narrow row before.
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun EntriesTab(entries: List<SessionEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        PiEmptyState(
            icon = BRANCH_GLYPH,
            // v2's session-tree empty states use their own icon, not the π mark
            // (`direction-b-v2.html:1922`: `icon="branch"`). `PiEmptyState` defaults
            // to the mark because the *chat's* two engine empty states carry it.
            markPi = false,
            title = "没有条目",
            body = "这个会话还没有写入任何条目。",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PiSpacing.pageHorizontal,
            end = PiSpacing.pageHorizontal,
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

/**
 * The session tree's own icon — v2's `branch` glyph, path for path.
 *
 * `direction-b-v2.html:425`, the `Icon` function's `branch` arm, inside the board's
 * `S` wrapper (`:402-403`: an `18x18` viewBox, `fill:none`, `stroke-width 1.5`,
 * round caps and joins) and drawn at `s=30` by the empty states there
 * (`:1922`: `EmptyState icon="branch"`):
 *
 * ```
 * <path d="M5 3.6v8.4M5 12h5.6a2.4 2.4 0 002.4-2.4V8"/>
 * <circle cx="5" cy="3" r="1.4"/><circle cx="13" cy="6.4" r="1.4"/>
 * ```
 *
 * It replaces the Material `Close` glyph the three empty states used to show, which
 * was the wrong drawing language *and* the wrong meaning (nothing is being closed
 * here). The two `<circle>` elements become the two half-arc pairs that are the same
 * circles, because a Compose `ImageVector` path builder has no `circle()` helper —
 * the same one-notation difference `ui/components/PiNavGlyph.kt` documents for the
 * settings glyph, and the centre and radius are unchanged.
 *
 * The stroke colour below is an unreachable placeholder: `PiEmptyState` draws this
 * through `material3.Icon` with a `tint`, and this vector is left exactly the shape
 * every Material icon has — built without its own `tintColor`, which
 * [ImageVector.Builder] leaves unspecified — so that tint reaches it the same way.
 */
private val BRANCH_GLYPH: ImageVector =
    ImageVector.Builder(
        name = "PiSessionTreeBranch",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 18f,
        viewportHeight = 18f,
    ).addPath(
        // `PathParser` is the only entry point that reads SVG path syntax; the
        // builder's `addPath` takes the parsed node list, not the string.
        pathData = PathParser().parsePathString(
            "M5 3.6v8.4M5 12h5.6a2.4 2.4 0 002.4-2.4V8" +
                "M3.6 3a1.4 1.4 0 102.8 0a1.4 1.4 0 10-2.8 0" +
                "M11.6 6.4a1.4 1.4 0 102.8 0a1.4 1.4 0 10-2.8 0",
        ).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
