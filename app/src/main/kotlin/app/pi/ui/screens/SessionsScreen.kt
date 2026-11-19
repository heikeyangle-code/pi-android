// `fontFeatureSettings = "tnum"`：等宽数字让「11分钟前」那一列不抖。这个属性在
// Compose 的文本 API 里仍带实验标记，`PiTheme.kt` 也是同一个理由在文件级 opt-in。
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.session.SessionScope
import app.pi.session.SessionSort
import app.pi.session.SessionSummary
import app.pi.session.list.sessionAgeLabel
import app.pi.ui.NAVIGATE_BUSY_LABEL
import app.pi.ui.PiSeg
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.chat.SessionFileRow
import app.pi.ui.chat.forkParentNameOf
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSheet
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.tree.SessionTreeView

/** 覆盖层里的两个视图，同一条分段控件切换（`03-navigation-decision.md`）。 */
enum class SessionsView(val label: String) {
    List("会话"),
    Tree("会话树"),
}

/**
 * 会话列表 —— 对话页左上角进入的**覆盖层**（与会话树同一层、同一条分段控件切换）。
 *
 * ## 数据与规则来自官方，不在这里
 *
 * 这一屏是 pi `/resume` 选择器（SDK `session-selector.js`）的手机版。**排序、成树、过滤、搜索**都由宿主
 * 用官方实现算好（`commands/sessionlist.mjs`，行文字规则见 `session/list/SessionListModel.kt`，两者都有
 * 对照官方真组件渲染的标准答案），这一屏只投影 `PiSessionViewModel.sessionList`：
 *
 *  - 范围 current / all（官方 Tab）、排序 threaded / recent / relevance（Ctrl+S）、仅命名（Ctrl+N）、
 *    显示路径（Ctrl+P）、搜索语法 `re:正则` / `"短语"` / 模糊词；
 *  - 加载进度（官方头部的 `Loading n/m`）与部分结果；离开时中止加载（官方 `cancelLoads`）；
 *  - 官方的三种空态各有自己的话，并带上官方给的下一步（「按 Tab 看全部」→ 一个「查看全部」按钮）；
 *  - 单个会话的操作只有官方那三件：恢复（点一行）、重命名、删除（先确认；当前会话不可删）。
 *    官方选择器没有的动作不加。
 *
 * ## 手机上怎么排
 *
 *  - threaded 下父子会话用**真线条**画成树（层数与 `isLast` / `ancestorContinues` 取自宿主，与官方 `│ ├─ └─` 同源）；
 *    其余排序平铺、行间一条发丝线；
 *  - 一行两层：标题（最多两行）+ 一行小字（年龄 · 条数，范围为「全部」时加目录名，有名字加「已命名」，
 *    平铺视图里有来源加「来自 X」）；当前会话 = 淡底 + 左侧竖条；
 *  - 冷启动先画上次的列表，宿主读完无声替换；有行可看时读取只在小字位置显示进度，不盖转圈。
 *
 * @param onOpenChat 选中一行之后：关掉覆盖层，并落在对话页。
 * @param onBack 顶栏左侧的返回箭头。系统返回键走 `PiRoot` 那一处 `BackHandler`，同一个动作。
 * @param initialView 打开时停在哪个视图（`/tree` 与「分支摘要」行要求直接落在树上）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    onOpenChat: () -> Unit,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
    initialView: SessionsView = SessionsView.List,
) {
    val list by session.sessionList.collectAsState()
    val cwdMissing by session.cwdMissing.collectAsState()
    val state by session.state.collectAsState()
    var treeReselect by remember { mutableStateOf<String?>(null) }

    var confirming by remember { mutableStateOf<SessionSummary?>(null) }
    var actions by remember { mutableStateOf<SessionSummary?>(null) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var viewIndex by rememberSaveable { mutableIntStateOf(initialView.ordinal) }
    var menuOpen by remember { mutableStateOf(false) }
    // 搜索框默认收起；收起时清掉查询 —— 否则列表会被一个看不见的过滤条件筛着。
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val view = SessionsView.entries.getOrNull(viewIndex) ?: SessionsView.List
    LaunchedEffect(initialView) { viewIndex = initialView.ordinal }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) session.importSession(uri)
    }

    // 进入这一屏、以及「现在在哪个会话 / 工作区」变了（新会话落盘、切了工作区）就让宿主重读；
    // `messageCount` 只取到 2：每条消息都重读整个目录太贵，而「文件出现」只发生在最开头那一两条。
    LaunchedEffect(
        state.meta.sessionFile,
        state.workspace.revision,
        state.meta.messageCount.coerceAtMost(2),
    ) { session.refreshSessions() }
    // 官方 `cancelLoads`：离开列表就中止宿主在途的全量读取。
    DisposableEffect(Unit) { onDispose { session.cancelSessionListLoad() } }

    val activeFile = state.meta.sessionFile?.substringAfterLast('/')

    // 平铺视图里「来自 X」：`parentSession` 是官方记在文件头里的事实；父会话不在当前这批行里就不猜名字。
    val forkParentNames = remember(list.rows) {
        val rows = list.rows.map { SessionFileRow(it.file.name, it.displayName) }
        list.rows.associate { it.file.absolutePath to forkParentNameOf(it.parentSession, rows) }
    }

    // 「全部」范围里每行要说目录：用工作区页同一套名字（`WorkspaceChoice.sessionGroupLabel`）。
    // 读设置文件有成本，所以只在范围 / 读取状态变化时重取，不随流式的部分结果每次重算。
    val context = LocalContext.current
    val workspaceLabels = remember(list.query.scope, list.loading) { WorkspaceStore.labels(context) }
    val workspacesRoot = GuestWorkspacePath.GUEST_ROOT + "/" + GuestWorkspacePath.ROOT_RELATIVE

    val tree = list.query.sort == SessionSort.Threaded && list.query.query.isBlank()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PiTopBar(
            onBack = onBack,
            title = view.label,
            statusLabel = session.engineLabel(state),
            actions = {
                if (view == SessionsView.List) {
                    PiTopBarIcon(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) session.setSessionQuery("")
                        },
                        contentDescription = if (searchOpen) "收起搜索" else "搜索会话",
                        icon = Icons.Filled.Search,
                    )
                }
                PiTopBarIcon(onClick = onOpenWorkspace, contentDescription = "工作区", icon = Icons.Filled.FolderOpen)
                PiTopBarIcon(onClick = onOpenSettings, contentDescription = "设置", icon = Icons.Filled.Settings)
                Box {
                    PiTopBarIcon(onClick = { menuOpen = true }, contentDescription = "更多", icon = Icons.Filled.MoreVert)
                    if (menuOpen) PiMenu(
                        expanded = true,
                        onDismiss = { menuOpen = false },
                        placement = PiMenuPlacement.Below,
                        items = buildList {
                            if (view == SessionsView.List) {
                                // 官方 Ctrl+P：每行多印一条完整路径。
                                add(PiMenuItem(if (list.showPath) "隐藏路径" else "显示路径") {
                                    menuOpen = false
                                    session.toggleSessionPath()
                                })
                            }
                            add(PiMenuItem("导入会话") {
                                menuOpen = false
                                importPicker.launch("*/*")
                            })
                            add(PiMenuItem(if (view == SessionsView.List) "刷新会话列表" else "刷新会话树") {
                                menuOpen = false
                                if (view == SessionsView.List) session.refreshSessions() else session.refreshTree()
                            })
                        },
                    )
                }
            },
        )

        // 第一行：视图分段（会话 / 会话树）+ 右端的数量或读取进度。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = SESSIONS_VIEW_ROW_TOP,
                    bottom = SESSIONS_VIEW_ROW_BOTTOM,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                PiSeg(
                    options = SessionsView.entries.map { it.label },
                    selectedIndex = view.ordinal,
                    onSelect = { index ->
                        val item = SessionsView.entries[index]
                        viewIndex = item.ordinal
                        if (item == SessionsView.Tree) session.refreshTree()
                    },
                )
            }
            if (view == SessionsView.List) {
                Text(
                    sessionCountText(list),
                    modifier = Modifier.padding(start = SESSIONS_VIEW_ROW_GAP),
                    style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                    color = if (list.loading) PiTheme.palette.accent else PiTheme.palette.muted,
                    maxLines = 1,
                )
            }
        }

        if (view == SessionsView.List) {
            // 第二行：范围 / 排序 / 仅命名。会横向滚动（1.3 倍字号下放不下，滚动比截断诚实）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        bottom = SESSIONS_VIEW_ROW_BOTTOM,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_VIEW_ROW_GAP),
            ) {
                PiSeg(
                    options = listOf("当前文件夹", "全部"),
                    selectedIndex = list.query.scope.ordinal,
                    onSelect = { session.setSessionScope(SessionScope.entries[it]) },
                )
                // 官方 Ctrl+S 的循环顺序：threaded → recent → relevance → threaded。
                PiSettingsChip(
                    text = when (list.query.sort) {
                        SessionSort.Threaded -> "按树"
                        SessionSort.Recent -> "按时间"
                        SessionSort.Relevance -> "按相关度"
                    },
                    glyph = "↕",
                    active = true,
                    onClick = {
                        session.setSessionSort(
                            when (list.query.sort) {
                                SessionSort.Threaded -> SessionSort.Recent
                                SessionSort.Recent -> SessionSort.Relevance
                                SessionSort.Relevance -> SessionSort.Threaded
                            },
                        )
                    },
                )
                PiSettingsChip(
                    text = "仅命名",
                    glyph = if (list.query.namedOnly) "✓" else "○",
                    active = list.query.namedOnly,
                    onClick = { session.setSessionNamedOnly(!list.query.namedOnly) },
                )
            }
            if (list.loading && list.loadTotal > 0) {
                // 官方头部的 `Loading n/m`，手机上画成一根细进度线。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SESSIONS_PROGRESS_HEIGHT)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((list.loaded.toFloat() / list.loadTotal).coerceIn(0f, 1f))
                            .height(SESSIONS_PROGRESS_HEIGHT)
                            .background(PiTheme.palette.accent),
                    )
                }
            }
        }

        if (view == SessionsView.Tree) {
            SessionTreeView(
                roots = state.tree?.tree.orEmpty(),
                leafId = state.tree?.leafId,
                busy = state.busy?.takeIf { it == NAVIGATE_BUSY_LABEL },
                streaming = state.streaming,
                homeDir = app.pi.packages.AgentLayout.GUEST_HOME,
                initialFilter = remember { session.treeFilterMode() },
                reselect = treeReselect,
                onReselected = { treeReselect = null },
                onFork = { entryId ->
                    session.forkFrom(entryId)
                    onBack()
                },
                onNavigate = { entryId, choice, instructions ->
                    session.navigateTo(entryId, choice, instructions, onAborted = { treeReselect = entryId })
                },
                onLabel = { entryId, label -> session.setEntryLabel(entryId, label) },
                onHideFromContext = { entryId -> session.hideEntryFromContext(entryId) },
                onReplaceInContext = { entryId, text -> session.replaceEntryInContext(entryId, text) },
                onAbortSummary = { session.abortBranchSummary() },
                skipSummaryPrompt = { session.branchSummarySkipPrompt() },
                modifier = Modifier.weight(1f),
            )
        } else {
            AnimatedVisibility(
                visible = searchOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SessionSearchField(
                    query = list.query.query,
                    onChange = session::setSessionQuery,
                    error = list.queryError,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val rows = list.rows
                val noQuery = list.query.query.isBlank()
                when {
                    rows.isEmpty() && list.loading -> SessionsEmpty(
                        icon = Icons.Filled.Refresh,
                        title = "正在读取会话…",
                        body = if (list.loadTotal > 0) {
                            "已读 ${list.loaded}/${list.loadTotal} 个会话文件。会话多的时候需要几秒，读到的会陆续出现在这里。"
                        } else {
                            "正在打开会话目录。会话多的时候需要几秒。"
                        },
                    )
                    rows.isEmpty() && list.failed -> SessionsEmpty(
                        icon = Icons.Filled.Warning,
                        title = "读不到会话列表",
                        body = "这一次读取失败了，不是没有会话。可以再试一次。",
                        actionLabel = "重试",
                        onAction = { session.refreshSessions() },
                    )
                    rows.isEmpty() && list.queryError != null -> SessionsEmpty(
                        icon = Icons.Filled.Warning,
                        title = "搜索写法有误",
                        body = list.queryError.orEmpty(),
                    )
                    // 官方三种空态（`SessionList.render`）：各自带官方给的下一步。
                    rows.isEmpty() && noQuery && list.query.namedOnly -> SessionsEmpty(
                        icon = Icons.Filled.Forum,
                        title = "没有已命名的会话",
                        body = if (list.query.scope == SessionScope.Current) "当前文件夹里没有已命名的会话。" else "所有会话都还没有名字。",
                        actionLabel = "显示全部会话",
                        onAction = { session.setSessionNamedOnly(false) },
                    )
                    rows.isEmpty() && noQuery && list.query.scope == SessionScope.Current -> SessionsEmpty(
                        icon = Icons.Filled.Forum,
                        title = "当前文件夹还没有会话",
                        body = "会话按工作目录记录；其他文件夹里的会话在「全部」里。",
                        actionLabel = "查看全部",
                        onAction = { session.setSessionScope(SessionScope.All) },
                    )
                    rows.isEmpty() && noQuery -> SessionsEmpty(
                        icon = Icons.Filled.Forum,
                        title = "还没有会话",
                        body = "发出第一条消息后，会话就会出现在这里。",
                    )
                    rows.isEmpty() -> SessionsEmpty(
                        icon = Icons.Filled.Search,
                        title = "没有匹配的会话",
                        body = "换一个关键词，或关掉「仅命名」。",
                        actionLabel = if (list.query.scope == SessionScope.Current) "在全部里搜索" else null,
                        onAction = { session.setSessionScope(SessionScope.All) },
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = SESSIONS_LIST_BOTTOM_RESERVE),
                    ) {
                        items(rows, key = { it.file.absolutePath }) { summary ->
                            SessionListRow(
                                summary = summary,
                                active = summary.isCurrent || (activeFile != null && summary.file.name == activeFile),
                                tree = tree,
                                // 「全部」范围才说目录（官方：右侧 cwd 只在 all 范围出现）。
                                cwdLabel = if (list.query.scope == SessionScope.All && summary.cwd.isNotEmpty()) {
                                    WorkspaceChoice.sessionGroupLabel(summary.cwd, workspacesRoot, workspaceLabels)
                                } else {
                                    null
                                },
                                showPath = list.showPath,
                                // 树里父子关系已经由缩进说了，不再重复写「来自」。
                                forkedFrom = if (tree) null else forkParentNames[summary.file.absolutePath],
                                onOpen = {
                                    session.switchSession(summary)
                                    onOpenChat()
                                },
                                onLongPress = { actions = summary },
                            )
                        }
                        item(key = "hint") {
                            Text(
                                "长按一行：重命名、删除。当前会话要切换到别的会话后才能删除。",
                                modifier = Modifier.padding(
                                    start = PiSettingsMetrics.pageHorizontal,
                                    end = PiSettingsMetrics.pageHorizontal,
                                    top = SESSIONS_HINT_TOP,
                                    bottom = SESSIONS_HINT_BOTTOM,
                                ),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = SESSIONS_NEW_SESSION_INSET, bottom = SESSIONS_NEW_SESSION_BOTTOM),
                ) {
                    NewSessionButton {
                        session.newSession()
                        onOpenChat()
                    }
                }
            }
        }
    }

    // 长按面板：官方选择器对单个会话只有三件事 —— 恢复（点一行）、重命名（Ctrl+R）、删除（Ctrl+D）
    // （`keybindings.js`：app.session.rename / app.session.delete）。面板里不加官方没有的动作。
    val acting = actions
    if (acting != null) {
        val actingIsCurrent = acting.isCurrent || (activeFile != null && acting.file.name == activeFile)
        PiSettingsSheet(onDismiss = { actions = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        top = PiSettingsMetrics.sheetHeadTop,
                        bottom = PiSettingsMetrics.sheetHeadBottom,
                    ),
            ) {
                Text(
                    acting.displayName,
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 官方 Ctrl+P 想让人看到的东西：这个会话在哪个目录、文件在哪。
                Text(
                    buildString {
                        append(sessionAgeLabel(System.currentTimeMillis() - acting.lastActivityAt))
                        append(" · ").append(acting.messageCount).append(" 条")
                        if (acting.cwdShort.isNotEmpty()) append(" · ").append(acting.cwdShort)
                    },
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    acting.pathShort,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SessionActionRow(label = "重命名", tone = MaterialTheme.colorScheme.onSurface) {
                actions = null
                renaming = acting
            }
            // 官方：当前会话不能删（直接报「Cannot delete the currently active session」）。
            // 手机上把原因写在行里，而不是点了才被拒。
            SessionActionRow(
                label = if (actingIsCurrent) "删除（当前会话不能删，先切换到别的会话）" else "删除",
                tone = if (actingIsCurrent) PiTheme.palette.muted else PiTheme.palette.error,
                enabled = !actingIsCurrent,
            ) {
                actions = null
                confirming = acting
            }
            SessionActionRow(label = "取消", tone = MaterialTheme.colorScheme.onSurface) { actions = null }
        }
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameSessionDialog(
            initial = renameTarget.name,
            onConfirm = { name ->
                session.renameSessionFromList(renameTarget, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // 官方删除一定先确认（`Delete session? Enter confirm · Esc cancel`）。当前会话直接拒绝：
    // pi 正在往那个文件追加，删掉它会让引擎写进一个已移除的文件。
    // 动作顺序 [删除, 取消]、前置符号（`!` 错误色 / `⊘` 静音色）沿用 v2 的删除确认。
    val pending = confirming
    if (pending != null) {
        val isActive = pending.isCurrent || (activeFile != null && pending.file.name == activeFile)
        PiDialog(onDismissRequest = { confirming = null }) {
            PiDialogTitle(
                title = "删除会话",
                glyph = if (isActive) "⊘" else "!",
                glyphTone = if (isActive) PiTheme.palette.muted else PiTheme.palette.error,
            )
            PiDialogBody(
                if (isActive) {
                    "「${pending.displayName}」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。"
                } else {
                    "删除「${pending.displayName}」？有回收站时会移到回收站，没有就直接删除，无法恢复。"
                },
            )
            PiDialogActions {
                PiDialogAction(
                    label = "删除",
                    primary = true,
                    enabled = !isActive,
                    tone = PiTheme.palette.error,
                    onClick = {
                        session.deleteSession(pending)
                        confirming = null
                    },
                )
                PiDialogAction(label = "取消", primary = false, onClick = { confirming = null })
            }
        }
    }

    // 官方 `/resume` 的缺失 cwd 流程（`session-cwd.js`）：会话记录的目录在这台设备上不存在时先问，
    // 再按用户的选择以当前工作区重试或分支。状态在 VM（`cwdMissing`），这里只画确认框。
    val missingPrompt = cwdMissing
    if (missingPrompt != null) {
        PiDialog(onDismissRequest = { session.dismissCwdMissing() }) {
            PiDialogTitle(
                title = "原工作目录不可用",
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody(
                buildString {
                    append(missingPrompt.reason)
                    missingPrompt.issue?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    append("\n\n可在当前工作区继续打开，或分支一份新会话到当前工作区。")
                },
            )
            PiDialogActions {
                PiDialogAction(label = "取消", primary = false, onClick = { session.dismissCwdMissing() })
                PiDialogAction(label = "继续打开", primary = false, onClick = { session.resolveCwdMissingContinue() })
                PiDialogAction(label = "分支到此", primary = true, onClick = { session.resolveCwdMissingFork() })
            }
        }
    }
}

/** 顶栏右侧的文字：读取中说进度，否则说条数（被过滤时带上总数）。 */
private fun sessionCountText(list: app.pi.ui.PiSessionViewModel.SessionListState): String = when {
    list.loading && list.fromCache -> "更新中…"
    list.loading && list.loadTotal > 0 -> "读取 ${list.loaded}/${list.loadTotal}"
    list.loading -> "读取中…"
    list.total > list.rows.size -> "${list.rows.size}/${list.total} 条"
    else -> "${list.rows.size} 条"
}

/** 带可选动作按钮的空态。动作就是官方在同一处给出的下一步（按 Tab 看全部 / 显示全部）。 */
@Composable
private fun SessionsEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PiEmptyState(
            icon = icon,
            title = title,
            body = body,
            modifier = Modifier.padding(top = SESSIONS_EMPTY_TOP),
            action = actionLabel?.let { label -> { PiSettingsChip(text = label, active = true, onClick = onAction) } },
        )
    }
}

/**
 * 搜索框：填充底、无描边、整圆胶囊。语法提示就是官方头部那一行（`re:<pattern> regex · "phrase" exact`），
 * 语法有误时（坏正则）把原因写在同一位置，官方此时列表为空，这里多说一句为什么。
 */
@Composable
private fun SessionSearchField(query: String, onChange: (String) -> Unit, error: String?) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = SESSIONS_SEARCH_TOP,
                bottom = SESSIONS_SEARCH_BOTTOM,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(SESSIONS_SEARCH_HEIGHT),
            shape = PiShapes.chip,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = SESSIONS_SEARCH_PADDING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_SEARCH_GAP),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(SESSIONS_SEARCH_ICON),
                    tint = PiTheme.palette.muted,
                )
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索名字、内容、目录", style = PiTheme.text.monoSmall, color = PiTheme.palette.muted)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        textStyle = PiTheme.text.monoSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(PiTheme.palette.accent),
                    )
                }
                if (query.isNotEmpty()) {
                    Text(
                        "清除",
                        modifier = Modifier.clickable { onChange("") },
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
        }
        Text(
            error ?: "re:<正则> 正则匹配 · \"短语\" 精确匹配",
            modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap, start = SESSIONS_SEARCH_PADDING),
            style = PiTheme.text.meta,
            color = if (error != null) PiTheme.palette.error else PiTheme.palette.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一行会话。两层：标题（最多两行）+ 小字（年龄 · 条数 [· 目录] [已命名] [来自 X]），`showPath` 时再多一行路径。
 *
 * **树**（threaded 且无搜索）：层级线是真线条，不是 `│ ├─ └─` 字符。数据同官方
 * `flattenSessionTree`：depth 层、[SessionSummary.isLast]、[SessionSummary.ancestorContinues]。
 * 官方每行最左有一列永远为空（根那一级不画延续线），手机上把这一列去掉，所以第 i 个祖先列画在 `i - 1`，
 * 本行的 `├` / `└` 在 `depth - 1` 列，文字缩进 `depth` 级。树里行间不画分隔线（否则会把竖线切断）。
 *
 * **当前会话**：淡底 + 左侧竖条 + 标题 SemiBold。**有名字**的会话标题用 warning 色（官方 `render`：
 * `hasName` → `warning`，当前 → `accent`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListRow(
    summary: SessionSummary,
    active: Boolean,
    tree: Boolean,
    cwdLabel: String?,
    showPath: Boolean,
    forkedFrom: String?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val levels = if (tree) summary.depth else 0
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline
    val named = !summary.name.isNullOrBlank()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.gutter)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(PiSettingsMetrics.cardRadius))
                .then(if (active) Modifier.background(PiTheme.palette.selectedBg.copy(alpha = 0.35f)) else Modifier)
                .drawBehind {
                    val unit = SESSIONS_TREE_UNIT.toPx()
                    val stroke = PiSpacing.hairlineThin.toPx()
                    val start = PiSettingsMetrics.rowPaddingHorizontal.toPx()
                    if (levels > 0) {
                        val mid = size.height / 2f
                        // 祖先列：第 i 个（i ≥ 1）在 i - 1 列；只有「还有下一个兄弟」才画竖线。
                        summary.ancestorContinues.forEachIndexed { i, continues ->
                            if (i >= 1 && continues) {
                                val x = start + (i - 1 + 0.5f) * unit
                                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                            }
                        }
                        val x = start + (levels - 1 + 0.5f) * unit
                        drawLine(lineColor, Offset(x, 0f), Offset(x, if (summary.isLast) mid else size.height), stroke)
                        drawLine(lineColor, Offset(x, mid), Offset(start + levels * unit, mid), stroke)
                    }
                    if (active) {
                        val inset = PiSpacing.cardPadding.toPx()
                        val width = PiSpacing.stripe.toPx()
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(0f, inset),
                            size = Size(width, (size.height - inset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(width / 2f),
                        )
                    }
                }
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal + SESSIONS_TREE_UNIT * levels,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    top = PiSettingsMetrics.rowPaddingVertical,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    summary.displayName,
                    style = if (active) PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold) else PiType.rowTitle,
                    color = when {
                        active -> MaterialTheme.colorScheme.primary
                        named -> PiTheme.palette.warning
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SESSIONS_ROW_PROP_GAP),
                ) {
                    Text(
                        sessionAgeLabel(System.currentTimeMillis() - summary.lastActivityAt) + " · " + summary.messageCount + " 条",
                        style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                        color = PiTheme.palette.muted,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f))
                    if (cwdLabel != null) SessionTag(cwdLabel)
                    if (named) SessionTag("已命名")
                    if (forkedFrom != null) {
                        SessionTag(if (forkedFrom.length > SESSIONS_TAG_NAME_MAX) "分支" else "来自 $forkedFrom")
                    }
                }
                if (showPath) {
                    Text(
                        summary.pathShort,
                        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!tree) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal + PiSpacing.gutter),
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** 行内小标签：`surfaceContainerHighest` 底、圆角、12sp。 */
@Composable
private fun SessionTag(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(PiShapes.tag)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = PiSpacing.gutter, vertical = PiSpacing.tiny),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 长按菜单里的一行（v2 `SessionsOverlay` 的 sheet 内容：`padding:12px 14px`、
 * 行标题 `PiType.rowTitle`（t15/500）、色由调用方按语义给 —— 删除是 error）。
 */
@Composable
private fun SessionActionRow(label: String, tone: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = SESSIONS_ACTION_ROW_PADDING,
            ),
        style = PiType.rowTitle,
        color = tone,
    )
}

/**
 * 「+ 新建会话」：胶囊（`height:42; padding:0 16px; border-radius:999; gap:7`，
 * 图标 16 + 14/600 文字）。
 *
 * 底色是**主题容器色** `primaryContainer`（= pi 的 `selectedBg`，用户裁决）：原来的
 * 实心 accent 比主题色更深更重，压过了列表；容器色仍然是这一屏最显眼的一件动作，
 * 但安静一档。文字与图标用它的 `on*` 对色。仍然**没有阴影**（`06 §5`）。
 */
@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(SESSIONS_NEW_SESSION_HEIGHT)
            .clip(PiShapes.badge)
            // **主题容器色**（用户裁决）：`primaryContainer` = pi 的 `selectedBg`，
            // 也就是 accent 的低饱和底，比原来的实心 accent 安静一档，而仍然读成
            // 「这一屏最显眼的那一件动作」。文字与图标用它的 `on*` 对色。
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = SESSIONS_NEW_SESSION_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSIONS_NEW_SESSION_GAP),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(SESSIONS_NEW_SESSION_ICON),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            "新建会话",
            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}


// ---------------------------------------------------------------- 尺寸
//
// 沿用 v2 `SessionsOverlay` 的取值；新增的三个（进度线、空态顶距、树缩进）是这次重做加的。

private val SESSIONS_VIEW_ROW_TOP = 10.dp
private val SESSIONS_VIEW_ROW_BOTTOM = 6.dp
private val SESSIONS_VIEW_ROW_GAP = 8.dp

private val SESSIONS_SEARCH_TOP = 4.dp
private val SESSIONS_SEARCH_BOTTOM = 8.dp
private val SESSIONS_SEARCH_HEIGHT = 36.dp
private val SESSIONS_SEARCH_PADDING = 10.dp
private val SESSIONS_SEARCH_GAP = 8.dp
private val SESSIONS_SEARCH_ICON = 15.dp

private val SESSIONS_LIST_BOTTOM = 14.dp
private val SESSIONS_HINT_TOP = 16.dp
private val SESSIONS_HINT_BOTTOM = 4.dp

private val SESSIONS_NEW_SESSION_HEIGHT = 42.dp
private val SESSIONS_NEW_SESSION_PADDING = 16.dp
private val SESSIONS_NEW_SESSION_GAP = 7.dp
private val SESSIONS_NEW_SESSION_ICON = 16.dp
private val SESSIONS_NEW_SESSION_INSET = 16.dp
private val SESSIONS_NEW_SESSION_BOTTOM = 14.dp

/** 列表自己的底部留白：最后一条永远能滚到浮动按钮上方（位置与可达性分开给）。 */
private val SESSIONS_LIST_BOTTOM_RESERVE =
    SESSIONS_NEW_SESSION_BOTTOM + SESSIONS_NEW_SESSION_HEIGHT + SESSIONS_LIST_BOTTOM

private val SESSIONS_ACTION_ROW_PADDING = 12.dp
private const val SESSIONS_TAG_NAME_MAX = 8
private val SESSIONS_ROW_PROP_GAP = 8.dp

/** 读取进度线的粗细：细到不抢列表，粗到能看见在走。 */
private val SESSIONS_PROGRESS_HEIGHT = 2.dp

/** 空态离顶栏的距离（与 `PiEmptyStateTopAnchored` 同一档，它没有 action 槽所以这里自己摆）。 */
private val SESSIONS_EMPTY_TOP = 86.dp

/** 树里每一级的缩进宽度，也是连线列宽。 */
private val SESSIONS_TREE_UNIT = 16.dp
