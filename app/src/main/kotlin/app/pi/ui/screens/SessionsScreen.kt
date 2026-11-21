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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
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
import app.pi.i18n.tr
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
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
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSheet
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.tree.SessionTreeView

/** 覆盖层里的两个视图，同一条分段控件切换（`03-navigation-decision.md`）。 */
enum class SessionsView {
    List,
    Tree;

    val label: String
        get() = when (this) {
            List -> tr("会话", "Sessions")
            Tree -> tr("会话树", "Session Tree")
        }
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
 *  - 加载进度（官方头部的 `Loading n/m`）与部分结果；
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
    // 搜索框默认收起，但如果 ViewModel 里还保留着上一次的搜索词（例如从会话回到列表），
    // 自动展开搜索栏，避免出现「搜索框是关的、列表却被隐藏过滤词筛空」的幽灵过滤 bug。
    var searchOpen by rememberSaveable { mutableStateOf(list.query.query.isNotEmpty()) }
    LaunchedEffect(list.query.query) {
        if (list.query.query.isNotEmpty() && !searchOpen) searchOpen = true
    }
    val view = SessionsView.entries.getOrNull(viewIndex) ?: SessionsView.List
    LaunchedEffect(initialView) { viewIndex = initialView.ordinal }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) session.importSession(uri)
    }

    // 读一次、之后秒开：列表由 VM 在引擎就绪时后台读好并常驻（`PiSessionViewModel.sessionList`），
    // 这一屏挂载时只在「还没读过」时才读；会话文件 / 工作区变了由 VM 在后台静默补读，不依赖这一屏开着。
    // 官方 `/resume` 每次打开都重读磁盘——手机上读一遍要几秒，所以这一点有意不同。
    LaunchedEffect(Unit) { session.ensureSessionList() }

    val activeFile = state.meta.sessionFile?.substringAfterLast('/')

    // 平铺视图里「来自 X」：`parentSession` 是官方记在文件头里的事实；父会话不在当前这批行里就不猜名字。
    val forkParentNames = remember(list.rows) {
        // 文件名 → 显示名只建一次（首个同名行优先，同 `forkParentNameOf`），逐行查表是 O(n)，不是 O(n²)。
        val nameOf = HashMap<String, String>(list.rows.size)
        for (r in list.rows) nameOf.putIfAbsent(r.file.name, r.displayName)
        list.rows.associate { r ->
            r.file.absolutePath to r.parentSession?.takeIf { it.isNotBlank() }?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let(nameOf::get)
        }
    }

    // 分组头用工作区页同一套名字（`WorkspaceChoice.sessionGroupLabel`）。读设置文件有成本，
    // 只在列表换了一批行时重取，不随每次重组重算。
    val context = LocalContext.current
    val labelsKey = list.rows.size
    val workspaceLabels by produceState(emptyMap<String, String>(), labelsKey) {
        value = withContext(Dispatchers.IO) { WorkspaceStore.labels(context) }
    }
    val workspacesRoot = GuestWorkspacePath.GUEST_ROOT + "/" + GuestWorkspacePath.ROOT_RELATIVE
    // 当前工作区在会话文件头里的 cwd 写法：自带工作区是 guest 路径，设备目录是它自己的路径。
    val currentCwds = remember(state.workspace.relative, state.workspace.hostPath) {
        listOfNotNull(GuestWorkspacePath.GUEST_ROOT + "/" + state.workspace.relative, state.workspace.hostPath)
            .map { it.trimEnd('/') }
    }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    val groups = remember(list.rows, currentCwds, workspaceLabels, activeFile, list.query.query, list.query.namedOnly) {
        buildSessionGroups(list.rows, currentCwds, workspacesRoot, workspaceLabels, activeFile, withEmptyCurrent = list.query.query.isBlank() && !list.query.namedOnly)
    }

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
                        contentDescription = if (searchOpen) tr("收起搜索", "Hide search") else tr("搜索会话", "Search sessions"),
                        icon = Icons.Filled.Search,
                    )
                }
                PiTopBarIcon(onClick = onOpenWorkspace, contentDescription = tr("工作区", "Workspace"), icon = Icons.Filled.FolderOpen)
                PiTopBarIcon(onClick = onOpenSettings, contentDescription = tr("设置", "Settings"), icon = Icons.Filled.Settings)
                Box {
                    PiTopBarIcon(onClick = { menuOpen = true }, contentDescription = tr("更多", "More"), icon = Icons.Filled.MoreVert)
                    if (menuOpen) PiMenu(
                        expanded = true,
                        onDismiss = { menuOpen = false },
                        placement = PiMenuPlacement.Below,
                        items = buildList {
                            if (view == SessionsView.List) {
                                // 官方 Ctrl+P：每行多印一条完整路径。
                                add(PiMenuItem(if (list.showPath) tr("隐藏路径", "Hide paths") else tr("显示路径", "Show paths")) {
                                    menuOpen = false
                                    session.toggleSessionPath()
                                })
                            }
                            add(PiMenuItem(tr("导入会话", "Import session")) {
                                menuOpen = false
                                importPicker.launch("*/*")
                            })
                            add(PiMenuItem(if (view == SessionsView.List) tr("刷新会话列表", "Refresh sessions") else tr("刷新会话树", "Refresh session tree")) {
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
                    color = PiTheme.palette.muted,
                    maxLines = 1,
                )
            }
        }

        if (view == SessionsView.List) {
            // 第二行：排序 / 仅命名。会横向滚动（大字号下放不下，滚动比截断诚实）。
            // 范围（官方 Tab 的 current / all）没有单独的开关：列表永远是「全部」，按工作区分组，
            // 当前工作区那一组置顶 —— 切范围要重读，分组则只是同一批行的另一种摆法。
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
                // 官方 Ctrl+S 的循环顺序：threaded → recent → relevance → threaded。
                SessionFilterPill(
                    text = when (list.query.sort) {
                        SessionSort.Threaded -> tr("按树", "Threaded")
                        SessionSort.Recent -> tr("按时间", "Recent")
                        SessionSort.Relevance -> tr("按相关度", "Relevance")
                    },
                    glyph = "↕",
                    active = false,
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
                SessionFilterPill(
                    text = tr("仅命名", "Named only"),
                    glyph = if (list.query.namedOnly) "✓" else "○",
                    active = list.query.namedOnly,
                    onClick = { session.setSessionNamedOnly(!list.query.namedOnly) },
                )
                if (collapsedGroups.isNotEmpty() && list.query.query.isBlank()) {
                    SessionFilterPill(text = tr("展开全部", "Expand all"), glyph = "▾", active = false, onClick = { collapsedGroups = emptySet() })
                } else if (groups.size > 1 && list.query.query.isBlank()) {
                    SessionFilterPill(
                        text = tr("折叠其他", "Collapse others"),
                        glyph = "▸",
                        active = false,
                        onClick = { collapsedGroups = groups.filterNot { it.current }.map { it.cwd }.toSet() },
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
                    loading = list.loading && list.query.query.isNotBlank(),
                    onChange = session::setSessionQuery,
                    error = list.queryError,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val rows = list.rows
                val noQuery = list.query.query.isBlank()
                val searching = !noQuery
                when {
                    rows.isEmpty() && list.loading -> SessionsLoading(list.loaded, list.loadTotal)
                    rows.isEmpty() && list.failed -> SessionsEmpty(
                        icon = Icons.Filled.Warning,
                        title = tr("读不到会话列表", "Failed to load sessions"),
                        body = tr("这一次读取失败了，不是没有会话。可以再试一次。", "Loading failed this time — it does not mean there are no sessions. Please try again."),
                        actionLabel = tr("重试", "Retry"),
                        onAction = { session.refreshSessions() },
                    )
                    rows.isEmpty() && list.queryError != null -> SessionsEmpty(
                        icon = Icons.Filled.Warning,
                        title = tr("搜索写法有误", "Invalid search syntax"),
                        body = list.queryError.orEmpty(),
                    )
                    // 官方空态（`SessionList.render`）：各自带官方给的下一步。
                    rows.isEmpty() && noQuery && list.query.namedOnly -> SessionsEmpty(
                        icon = Icons.Filled.Forum,
                        title = tr("没有已命名的会话", "No named sessions"),
                        body = tr("所有会话都还没有名字。", "None of your sessions have a name yet."),
                        actionLabel = tr("显示全部会话", "Show all sessions"),
                        onAction = { session.setSessionNamedOnly(false) },
                    )
                    rows.isEmpty() && noQuery -> SessionsEmpty(
                        icon = Icons.Filled.Forum,
                        title = tr("还没有会话", "No sessions yet"),
                        body = tr("发出第一条消息后，会话就会出现在这里。", "Your sessions will appear here after you send your first message."),
                    )
                    rows.isEmpty() -> SessionsEmpty(
                        icon = Icons.Filled.Search,
                        title = tr("没有匹配的会话", "No matching sessions"),
                        body = tr("换一个关键词，或关掉「仅命名」。", "Try a different keyword or turn off \"Named only\"."),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = PiSpacing.blockGap,
                            bottom = SESSIONS_LIST_BOTTOM_RESERVE,
                        ),
                    ) {
                        if (searching) {
                            // 对照官方 SDK `session-selector.js:306`：搜索态平铺展示，严格保留官方相关度/时间排序，
                            // 不按工作区重新打散分组，并在每行显示所属工作区标签。
                            itemsIndexed(rows, key = { _, r -> r.file.absolutePath }) { index, summary ->
                                val wsLabel = WorkspaceChoice.sessionGroupLabel(
                                    summary.cwd.trimEnd('/'),
                                    workspacesRoot,
                                    workspaceLabels,
                                )
                                SessionListRow(
                                    summary = summary,
                                    active = summary.isCurrent || (activeFile != null && summary.file.name == activeFile),
                                    tree = false,
                                    first = index == 0,
                                    last = index == rows.lastIndex,
                                    showPath = list.showPath,
                                    forkedFrom = forkParentNames[summary.file.absolutePath],
                                    workspaceTag = wsLabel,
                                    onOpen = {
                                        session.switchSession(summary)
                                        onOpenChat()
                                    },
                                    onLongPress = { actions = summary },
                                )
                            }
                        } else {
                            groups.forEach { group ->
                                val collapsed = group.cwd in collapsedGroups
                                item(key = "hdr:${group.cwd}") {
                                    WorkspaceGroupHeader(
                                        label = group.label,
                                        count = group.rows.size,
                                        current = group.current,
                                        collapsed = collapsed,
                                        // 卡里没有行（空的当前工作区 / 折叠）时头自己就是整张卡。
                                        alone = collapsed || group.rows.isEmpty(),
                                        onToggle = {
                                            collapsedGroups = if (group.cwd in collapsedGroups) collapsedGroups - group.cwd else collapsedGroups + group.cwd
                                        },
                                        onNewSession = if (group.current) {
                                            {
                                                session.newSession()
                                                onOpenChat()
                                            }
                                        } else null,
                                    )
                                }
                                if (collapsed) {
                                    item(key = "gap:${group.cwd}") { Spacer(Modifier.height(PiSettingsMetrics.rowGap)) }
                                    return@forEach
                                }
                                if (group.rows.isEmpty()) {
                                    item(key = "empty:${group.cwd}") {
                                        Text(
                                            tr("这个工作区还没有会话", "No sessions in this workspace yet"),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                                                .clip(sessionCardShape(first = false, last = true))
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .padding(
                                                    start = PiSettingsMetrics.rowPaddingHorizontal,
                                                    end = PiSettingsMetrics.rowPaddingHorizontal,
                                                    bottom = PiSettingsMetrics.rowPaddingVertical,
                                                ),
                                            style = PiTheme.text.meta,
                                            color = PiTheme.palette.muted,
                                        )
                                    }
                                }
                                itemsIndexed(group.rows, key = { _, r -> r.file.absolutePath }) { index, summary ->
                                    SessionListRow(
                                        summary = summary,
                                        active = summary.isCurrent || (activeFile != null && summary.file.name == activeFile),
                                        tree = tree,
                                        first = false,
                                        last = index == group.rows.lastIndex,
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
                                item(key = "gap:${group.cwd}") { Spacer(Modifier.height(PiSettingsMetrics.rowGap)) }
                            }
                        }
                        item(key = "hint") {
                            Text(
                                tr("长按一行：重命名、删除。当前会话要切换到别的会话后才能删除。", "Long-press a row to rename or delete. Switch away from the active session before deleting it."),
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
                        append(" · ").append(acting.messageCount).append(tr(" 条", if (acting.messageCount == 1) " msg" else " msgs"))
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
            SessionActionRow(
                icon = Icons.Filled.Edit,
                label = tr("重命名", "Rename"),
                tone = MaterialTheme.colorScheme.onSurface,
                iconTint = PiTheme.palette.accent,
            ) {
                actions = null
                renaming = acting
            }
            SessionActionRow(
                icon = Icons.Filled.Delete,
                label = if (actingIsCurrent) tr("删除（当前会话不能删，先切换到别的会话）", "Delete (switch away from active session first)") else tr("删除会话", "Delete session"),
                tone = if (actingIsCurrent) PiTheme.palette.muted else PiTheme.palette.error,
                iconTint = if (actingIsCurrent) PiTheme.palette.muted else PiTheme.palette.error,
                enabled = !actingIsCurrent,
            ) {
                actions = null
                confirming = acting
            }
            SessionActionRow(
                icon = Icons.Filled.Close,
                label = tr("取消", "Cancel"),
                tone = PiTheme.palette.muted,
                iconTint = PiTheme.palette.muted,
            ) { actions = null }
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
                title = tr("删除会话", "Delete Session"),
                glyph = if (isActive) "⊘" else "!",
                glyphTone = if (isActive) PiTheme.palette.muted else PiTheme.palette.error,
            )
            PiDialogBody(
                if (isActive) {
                    tr("「${pending.displayName}」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。", "\"${pending.displayName}\" is the active session and pi is writing to it. Switch to another session before deleting.")
                } else {
                    tr("删除「${pending.displayName}」？有回收站时会移到回收站，没有就直接删除，无法恢复。", "Delete \"${pending.displayName}\"? It will be moved to Trash if available, or permanently deleted.")
                },
            )
            PiDialogActions {
                PiDialogAction(
                    label = tr("删除", "Delete"),
                    primary = true,
                    enabled = !isActive,
                    tone = PiTheme.palette.error,
                    onClick = {
                        session.deleteSession(pending)
                        confirming = null
                    },
                )
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { confirming = null })
            }
        }
    }

    // 官方 `/resume` 的缺失 cwd 流程（`session-cwd.js`）：会话记录的目录在这台设备上不存在时先问，
    // 再按用户的选择以当前工作区重试或分支。状态在 VM（`cwdMissing`），这里只画确认框。
    val missingPrompt = cwdMissing
    if (missingPrompt != null) {
        PiDialog(onDismissRequest = { session.dismissCwdMissing() }) {
            PiDialogTitle(
                title = tr("原工作目录不可用", "Original Directory Unavailable"),
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody(
                buildString {
                    append(missingPrompt.reason)
                    missingPrompt.issue?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    append(tr("\n\n可在当前工作区继续打开，或分支一份新会话到当前工作区。", "\n\nYou can resume it in the current workspace, or fork a new session here."))
                },
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { session.dismissCwdMissing() })
                PiDialogAction(label = tr("继续打开", "Resume"), primary = false, onClick = { session.resolveCwdMissingContinue() })
                PiDialogAction(label = tr("分支到此", "Fork here"), primary = true, onClick = { session.resolveCwdMissingFork() })
            }
        }
    }
}

/** 顶栏右侧的文字：搜索加载中给出明确提示，首次读取展示进度，平时展示匹配数/总数。 */
private fun sessionCountText(list: app.pi.ui.PiSessionViewModel.SessionListState): String = when {
    list.loading && list.query.query.isNotBlank() && list.loadTotal > 0 -> tr("搜索 ${list.loaded}/${list.loadTotal}", "Searching ${list.loaded}/${list.loadTotal}")
    list.loading && list.query.query.isNotBlank() -> tr("搜索中…", "Searching…")
    list.rows.isEmpty() && list.loading && list.loadTotal > 0 -> tr("读取 ${list.loaded}/${list.loadTotal}", "Loading ${list.loaded}/${list.loadTotal}")
    list.rows.isEmpty() && list.loading -> tr("读取中…", "Loading…")
    list.total > list.rows.size -> tr("${list.rows.size}/${list.total} 条", "${list.rows.size}/${list.total}")
    else -> tr("${list.rows.size} 条", "${list.rows.size}")
}

/** 带可选动作按钮的空态。动作就是官方在同一处给出的下一步（按 Tab 看全部 / 显示全部）。 */
@Composable
private fun SessionsEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    progress: Float? = null,
) {
    val actionSlot: (@Composable () -> Unit)? = when {
                actionLabel != null -> { { SessionFilterPill(text = actionLabel, glyph = "↻", active = true, onClick = onAction) } }
                progress != null -> { {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height(SESSIONS_PROGRESS_HEIGHT)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(SESSIONS_PROGRESS_HEIGHT)
                                .background(PiTheme.palette.accent),
                        )
                    }
                } }
        else -> null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PiEmptyState(
            icon = icon,
            title = title,
            body = body,
            modifier = Modifier.padding(top = SESSIONS_EMPTY_TOP),
            action = actionSlot,
        )
    }
}

/**
 * 搜索框：填充底、无描边、整圆胶囊。语法提示就是官方头部那一行（`re:<pattern> regex · "phrase" exact`），
 * 语法有误时（坏正则）把原因写在同一位置，官方此时列表为空，这里多说一句为什么。
 */
@Composable
private fun SessionSearchField(query: String, loading: Boolean, onChange: (String) -> Unit, error: String?) {
    val focus = remember { FocusRequester() }
    // 本地持有输入文本，只在外部清空时同步：避免后台全文搜索结果回流触发重组时打断中文拼音 IME 组合态。
    var localInput by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isEmpty() && localInput.isNotEmpty()) localInput = ""
    }
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
                    if (localInput.isEmpty()) {
                        Text(tr("搜索名字、内容、目录", "Search name, content, or folder"), style = PiTheme.text.monoSmall, color = PiTheme.palette.muted)
                    }
                    BasicTextField(
                        value = localInput,
                        onValueChange = { next ->
                            localInput = next
                            onChange(next)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        textStyle = PiTheme.text.monoSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(PiTheme.palette.accent),
                    )
                }
                if (loading) {
                    Text(
                        tr("索引中…", "Indexing…"),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.accent,
                    )
                }
                if (localInput.isNotEmpty()) {
                    Text(
                        tr("清除", "Clear"),
                        modifier = Modifier.clickable {
                            localInput = ""
                            onChange("")
                        },
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
        }
        Text(
            error ?: tr("re:<正则> 正则匹配 · \"短语\" 精确匹配", "re:<pattern> regex · \"phrase\" exact"),
            modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap, start = SESSIONS_SEARCH_PADDING),
            style = PiTheme.text.meta,
            color = if (error != null) PiTheme.palette.error else PiTheme.palette.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 会话行（工作区卡片里的一行）。
 *
 * ```
 * 第 1 行  会话名（最多两行）
 * 第 2 行  11分钟前 · 9 条                      [已命名] [来自 X]
 * ```
 *
 * 树视图（官方 threaded）：层级画成**真线条**，层数与连线取自宿主算好的 depth / isLast /
 * ancestorContinues（官方 3 字符前缀的手机版）。树里行间不画分隔线（会把竖线切断），平铺视图才画。
 * 当前会话：淡底 + 左侧竖条；有名字的会话标题用 warning 色（官方 `render`：hasName → warning，当前 → accent）。
 * 目录不在行里说：行在哪张卡里，卡头已经说了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListRow(
    summary: SessionSummary,
    active: Boolean,
    tree: Boolean,
    first: Boolean = false,
    last: Boolean,
    showPath: Boolean,
    forkedFrom: String?,
    workspaceTag: String? = null,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val levels = if (tree) summary.depth else 0
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline
    val named = !summary.name.isNullOrBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(sessionCardShape(first = first, last = last))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    end = 6.dp,
                    top = PiSettingsMetrics.rowPaddingVertical,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                            sessionAgeLabel(System.currentTimeMillis() - summary.lastActivityAt) + " · " + summary.messageCount + tr(" 条", if (summary.messageCount == 1) " msg" else " msgs"),
                            style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                            color = PiTheme.palette.muted,
                            maxLines = 1,
                        )
                        if (active) {
                            Text(
                                text = tr("当前", "Current"),
                                modifier = Modifier
                                    .clip(PiShapes.tag)
                                    .background(accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = accent,
                                maxLines = 1,
                            )
                        }
                        if (named) SessionTag(tr("已命名", "Named"))
                        if (!workspaceTag.isNullOrBlank()) SessionTag(workspaceTag)
                        if (forkedFrom != null) {
                            SessionTag(if (forkedFrom.length > SESSIONS_TAG_NAME_MAX) tr("分支", "Fork") else tr("来自 $forkedFrom", "From $forkedFrom"))
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClickLabel = tr("会话操作", "Session actions"), onClick = onLongPress),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = tr("更多操作", "More actions"),
                        tint = PiTheme.palette.dim,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        if (!tree && !last) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal),
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * 一个工作区的一组会话。**这是 App 的呈现，不是 pi 的概念**：官方「全部」范围每行右侧写 cwd，
 * 手机上改成按 cwd 归成卡片 —— 同一批行、同一个顺序（组内顺序就是宿主给的），只是分了堆。
 * 当前工作区置顶，其余按组内最近活动降序。
 */
private data class SessionGroup(
    val cwd: String,
    val label: String,
    val rows: List<SessionSummary>,
    val current: Boolean,
)

private fun buildSessionGroups(
    rows: List<SessionSummary>,
    currentCwds: List<String>,
    workspacesRoot: String,
    labels: Map<String, String>,
    activeFile: String?,
    withEmptyCurrent: Boolean,
): List<SessionGroup> {
    val byCwd = LinkedHashMap<String, MutableList<SessionSummary>>()
    for (row in rows) byCwd.getOrPut(row.cwd.trimEnd('/')) { mutableListOf() }.add(row)
    val groups = byCwd.map { (cwd, list) ->
        SessionGroup(
            cwd = cwd,
            label = WorkspaceChoice.sessionGroupLabel(cwd, workspacesRoot, labels),
            rows = list,
            current = cwd in currentCwds || list.any { it.isCurrent || (activeFile != null && it.file.name == activeFile) },
        )
    }.toMutableList()
    // 当前工作区还没有会话时也留一张空卡：「当前」要有个落脚处，而不是凭空消失。
    if (withEmptyCurrent && groups.none { it.current }) {
        currentCwds.firstOrNull()?.let { cwd ->
            groups += SessionGroup(cwd, WorkspaceChoice.sessionGroupLabel(cwd, workspacesRoot, labels), emptyList(), current = true)
        }
    }
    return groups.sortedWith(
        compareByDescending<SessionGroup> { it.current }.thenByDescending { g -> g.rows.maxOfOrNull { it.lastActivityAt } ?: 0L },
    )
}

/** 卡片的圆角：卡头是顶、最后一行是底，中间的行是直边；只有一块（折叠 / 空组）时四角都圆。 */
private fun sessionCardShape(first: Boolean, last: Boolean): androidx.compose.ui.graphics.Shape {
    val r = PiSettingsMetrics.cardRadius
    val zero = androidx.compose.ui.unit.Dp.Hairline
    return androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (first) r else zero,
        topEnd = if (first) r else zero,
        bottomEnd = if (last) r else zero,
        bottomStart = if (last) r else zero,
    )
}

/**
 * 卡头：目录徽章 + 名字 + 条数 + 折叠箭头（带转动）。当前工作区的徽章与「当前工作区」字样用 accent。
 * 整个头是一个 ≥48dp 的点击区，点一下折叠 / 展开这一组。
 */
@Composable
private fun WorkspaceGroupHeader(
    label: String,
    count: Int,
    current: Boolean,
    collapsed: Boolean,
    alone: Boolean,
    onToggle: () -> Unit,
    onNewSession: (() -> Unit)? = null,
) {
    val angle by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 0f else 90f, label = "group-chevron")
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(sessionCardShape(first = true, last = alone))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Box(
            modifier = Modifier
                .size(PiSpacing.unit * 1.75f)
                .clip(PiShapes.tag)
                .background(if (current) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                tint = if (current) accent else PiTheme.palette.muted,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current) {
                Text(tr("当前工作区", "Current workspace"), style = PiTheme.text.meta, color = accent, maxLines = 1)
            }
        }
        if (onNewSession != null) {
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .clickable(onClick = onNewSession)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = tr("在当前工作区新建会话", "New session in current workspace"),
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = tr("新建", "New"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        Text(
            "$count",
            style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
            color = PiTheme.palette.muted,
        )
        Text(
            "›",
            modifier = Modifier.rotate(angle),
            style = PiType.rowTitle,
            color = PiTheme.palette.muted,
        )
    }
}

/** 筛选行里的一枚胶囊：点一下切换；开着的用 accent 淡底。触控高度 ≥ 36dp。 */
@Composable
private fun SessionFilterPill(text: String, glyph: String, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by androidx.compose.animation.animateColorAsState(
        if (active) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "pill-bg",
    )
    Row(
        modifier = Modifier
            .heightIn(min = PiSpacing.unit * 2)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.supportingGap * 2),
    ) {
        Text(glyph, style = PiTheme.text.meta, color = if (active) accent else PiTheme.palette.muted)
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 手上一行都没有、正在读：说在读多少，进度线放在文字下面（有行可看时整块都不出现）。 */
@Composable
private fun SessionsLoading(loaded: Int, total: Int) {
    SessionsEmpty(
        icon = Icons.Filled.Refresh,
        title = tr("正在读取会话…", "Loading sessions…"),
        body = if (total > 0) {
            tr("已读 $loaded/$total 个会话文件，读到的会陆续出现。只有这一次会慢，之后打开列表是即时的。", "Loaded $loaded/$total session files; results appear as they load. Only this first load is slow — reopening is instant.")
        } else {
            tr("正在打开会话目录。会话多的时候需要几秒，之后打开列表是即时的。", "Opening session directory. This may take a few seconds with many sessions; reopening is instant.")
        },
        progress = if (total > 0) (loaded.toFloat() / total).coerceIn(0f, 1f) else null,
    )
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
private fun SessionActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tone: Color,
    iconTint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = SESSIONS_ACTION_ROW_PADDING,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = if (enabled) 0.14f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = label,
            style = PiType.rowTitle,
            color = tone,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .shadow(elevation = 6.dp, shape = PiShapes.badge, clip = false)
            .height(SESSIONS_NEW_SESSION_HEIGHT)
            .clip(PiShapes.badge)
            .background(PiTheme.palette.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = SESSIONS_NEW_SESSION_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSIONS_NEW_SESSION_GAP),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(SESSIONS_NEW_SESSION_ICON),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            tr("新建会话", "New Session"),
            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
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
