// `fontFeatureSettings = "tnum"`：等宽数字让「11分钟前」那一列不抖。
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.session.SessionSort
import app.pi.session.SessionSummary
import app.pi.session.list.displayText
import app.pi.session.list.sessionAgeLabel
import app.pi.ui.NAVIGATE_BUSY_LABEL
import app.pi.ui.PiSeg
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.tree.SessionTreeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private data class SessionsOverlaySlice(
    val sessionFile: String?,
    val workspaceGuestPath: String,
    val workspaceHostPath: String?,
    val tree: PiResponses.TreePage?,
    val busy: String?,
    val streaming: Boolean,
)

private fun PiSessionViewModel.UiState.toSessionsOverlaySlice(): SessionsOverlaySlice = SessionsOverlaySlice(
    sessionFile = meta.sessionFile,
    workspaceGuestPath = workspace.guestPath,
    workspaceHostPath = workspace.hostPath,
    tree = tree,
    busy = busy,
    streaming = streaming,
)

/** 覆盖层里的两个视图，嵌入顶栏分段控件直接切换。 */
enum class SessionsView {
    List,
    Tree;

    val label: String
        get() = when (this) {
            List -> tr("会话", "Sessions")
            Tree -> tr("会话树", "Tree")
        }
}

/**
 * 官方 Pi 移动端会话与会话树面板：
 *  - 与 [ChatTopBar] 完全统一的 56dp 顶栏几何与 44dp/22dp 图标节奏；
 *  - 顶栏中置「会话 | 会话树」分段切换，省去重复标题行，搜索键同时服务两个视图；
 *  - 严格保留全部防回退不变量：常驻 VM 缓存秒开、0 消息新会话即时置顶、局部进度不抹除缓存、
 *    非空搜索词自动展开搜索框、中文拼音 IME 独立状态、搜索态保持官方相关度平铺排序、多路径 CWD 兼容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    onOpenChat: () -> Unit,
    onBack: () -> Unit,
    initialView: SessionsView = SessionsView.List,
) {
    val list by session.sessionList.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // 仅订阅会话列表/会话树所需字段，避免后台流式生成逐 token 触发整张覆盖层重组。
    val sessionSlice by remember(session) {
        session.state.map { it.toSessionsOverlaySlice() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = session.state.value.toSessionsOverlaySlice())
    var treeReselect by remember { mutableStateOf<String?>(null) }

    var confirming by remember { mutableStateOf<SessionSummary?>(null) }
    var actions by remember { mutableStateOf<SessionSummary?>(null) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var viewIndex by rememberSaveable { mutableIntStateOf(initialView.ordinal) }
    var menuOpen by remember { mutableStateOf(false) }

    // 防幽灵过滤不变量：VM 仍有残留搜索词时自动展开搜索框。
    var listSearchOpen by rememberSaveable { mutableStateOf(list.query.query.isNotEmpty()) }
    var treeSearchOpen by rememberSaveable { mutableStateOf(false) }
    var treeShowStamps by rememberSaveable { mutableStateOf(false) }
    var treeJumpCurrentTick by remember { mutableIntStateOf(0) }
    var currentProjectOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(list.query.query) {
        if (list.query.query.isNotEmpty() && !listSearchOpen) listSearchOpen = true
    }
    val view = SessionsView.entries.getOrNull(viewIndex) ?: SessionsView.List
    LaunchedEffect(initialView) { viewIndex = initialView.ordinal }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) session.importSession(uri)
    }

    // 读一次、之后秒开：依赖 VM 后台常驻列表与静默刷新。
    LaunchedEffect(Unit) { session.ensureSessionList() }
    LaunchedEffect(view, sessionSlice.sessionFile) {
        if (view == SessionsView.Tree) session.refreshTree()
    }

    val activeFile = sessionSlice.sessionFile?.substringAfterLast('/')
    val zh = PiLocale.isZh

    val uniqueRows = remember(list.rows) { list.rows.distinctBy { it.file.absolutePath } }
    val forkParentNames = remember(uniqueRows, zh) {
        val nameOf = HashMap<String, String>(uniqueRows.size)
        for (r in uniqueRows) nameOf.putIfAbsent(r.file.name, sessionTitleOf(r))
        uniqueRows.associate { r ->
            r.file.absolutePath to r.parentSession
                ?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() }
                ?.let(nameOf::get)
        }
    }

    val context = LocalContext.current
    val labelsKey = Triple(sessionSlice.workspaceGuestPath, uniqueRows.size, uniqueRows.firstOrNull()?.file?.name)
    val workspaceLabels by produceState(emptyMap<String, String>(), labelsKey) {
        value = withContext(Dispatchers.IO) { WorkspaceStore.labels(context) }
    }
    val workspacesRoot = GuestWorkspacePath.PROJECTS_ROOT_GUEST
    val currentCwds = remember(sessionSlice.workspaceGuestPath, sessionSlice.workspaceHostPath) {
        buildList {
            add(sessionSlice.workspaceGuestPath.trimEnd('/'))
            sessionSlice.workspaceHostPath?.let { host ->
                add(host.trimEnd('/'))
            }
        }.distinct()
    }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    val allGroups = remember(uniqueRows, currentCwds, workspaceLabels, activeFile, list.query.query, list.query.namedOnly) {
        buildSessionGroups(
            rows = uniqueRows,
            currentCwds = currentCwds,
            workspacesRoot = workspacesRoot,
            labels = workspaceLabels,
            activeFile = activeFile,
            withEmptyCurrent = list.query.query.isBlank() && !list.query.namedOnly,
        )
    }
    val visibleGroups = remember(allGroups, currentProjectOnly) {
        if (currentProjectOnly) allGroups.filter { it.current } else allGroups
    }
    val visibleRowCount = remember(visibleGroups) {
        visibleGroups.sumOf { it.rows.size }
    }

    val tree = list.query.sort == SessionSort.Threaded && list.query.query.isBlank()
    val listState = rememberLazyListState()
    val listScrolled by remember {
        derivedStateOf { listState.canScrollBackward }
    }
    var treeScrolled by remember { mutableStateOf(false) }
    val activeSearchOpen = if (view == SessionsView.List) listSearchOpen else treeSearchOpen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .settingsPageTopInset(contentPadding),
    ) {
        PiTopBar(
            onBack = onBack,
            scrolled = if (view == SessionsView.List) listScrolled else treeScrolled,
            titleContent = {
                PiSeg(
                    options = SessionsView.entries.map { it.label },
                    selectedIndex = view.ordinal,
                    onSelect = { index ->
                        val item = SessionsView.entries[index]
                        viewIndex = item.ordinal
                        if (item == SessionsView.Tree) session.refreshTree()
                    },
                )
            },
            actions = {
                if (view == SessionsView.List) {
                    PiTopBarIcon(
                        onClick = {
                            session.newSession()
                            onOpenChat()
                        },
                        contentDescription = tr("新建会话", "New session"),
                        icon = Icons.Filled.Add,
                    )
                } else {
                    PiTopBarIcon(
                        onClick = { treeJumpCurrentTick++ },
                        contentDescription = tr("定位当前位置", "Jump to current position"),
                        icon = Icons.Filled.MyLocation,
                    )
                }
                PiTopBarIcon(
                    onClick = {
                        if (view == SessionsView.List) {
                            listSearchOpen = !listSearchOpen
                            if (!listSearchOpen) session.setSessionQuery("")
                        } else {
                            treeSearchOpen = !treeSearchOpen
                        }
                    },
                    contentDescription = if (activeSearchOpen) tr("收起搜索", "Hide search") else tr("搜索", "Search"),
                    icon = Icons.Filled.Search,
                    highlighted = activeSearchOpen,
                )
                Box {
                    PiTopBarIcon(
                        onClick = { menuOpen = true },
                        contentDescription = tr("更多", "More"),
                        icon = Icons.Filled.MoreVert,
                    )
                    if (menuOpen) {
                        PiMenu(
                            expanded = true,
                            onDismiss = { menuOpen = false },
                            placement = PiMenuPlacement.Below,
                            items = buildList {
                                if (view == SessionsView.List) {
                                    add(
                                        PiMenuItem(
                                            if (list.showPath) tr("隐藏文件路径", "Hide file paths") else tr("显示文件路径", "Show file paths"),
                                        ) {
                                            menuOpen = false
                                            session.toggleSessionPath()
                                        },
                                    )
                                    add(
                                        PiMenuItem(tr("导入会话 (.jsonl)", "Import session (.jsonl)")) {
                                            menuOpen = false
                                            importPicker.launch("*/*")
                                        },
                                    )
                                    add(
                                        PiMenuItem(tr("刷新会话列表", "Refresh sessions")) {
                                            menuOpen = false
                                            session.refreshSessions()
                                        },
                                    )
                                } else {
                                    add(
                                        PiMenuItem(
                                            if (treeShowStamps) tr("隐藏书签时间", "Hide label timestamps") else tr("显示书签时间", "Show label timestamps"),
                                        ) {
                                            menuOpen = false
                                            treeShowStamps = !treeShowStamps
                                        },
                                    )
                                    add(
                                        PiMenuItem(tr("刷新会话树", "Refresh session tree")) {
                                            menuOpen = false
                                            session.refreshTree()
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            },
        )

        AnimatedContent(
            targetState = view,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val toTree = targetState == SessionsView.Tree
                val slideSpec = spring<IntOffset>(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
                if (toTree) {
                    (slideInHorizontally(slideSpec) { it / 10 } + fadeIn(tween(170)))
                        .togetherWith(slideOutHorizontally(slideSpec) { -it / 10 } + fadeOut(tween(130)))
                } else {
                    (slideInHorizontally(slideSpec) { -it / 10 } + fadeIn(tween(170)))
                        .togetherWith(slideOutHorizontally(slideSpec) { it / 10 } + fadeOut(tween(130)))
                }
            },
            label = "sessions-view-switch",
        ) { activeView ->
            if (activeView == SessionsView.Tree) {
                SessionTreeView(
                    roots = sessionSlice.tree?.tree.orEmpty(),
                    leafId = sessionSlice.tree?.leafId,
                    loading = sessionSlice.tree == null,
                    busy = sessionSlice.busy?.takeIf { it == NAVIGATE_BUSY_LABEL },
                    streaming = sessionSlice.streaming,
                    searchOpen = treeSearchOpen,
                    showStamps = treeShowStamps,
                    jumpCurrentTick = treeJumpCurrentTick,
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    homeDir = app.pi.packages.AgentLayout.GUEST_HOME,
                    initialFilter = remember { session.treeFilterMode() },
                    reselect = treeReselect,
                    onReselected = { treeReselect = null },
                    onFork = { entryId ->
                        session.forkFrom(entryId)
                    },
                    onNavigate = { entryId, choice, instructions ->
                        session.navigateTo(entryId, choice, instructions, onAborted = { treeReselect = entryId })
                    },
                    onLabel = { entryId, label -> session.setEntryLabel(entryId, label) },
                    onHideFromContext = { entryId -> session.hideEntryFromContext(entryId) },
                    onReplaceInContext = { entryId, text -> session.replaceEntryInContext(entryId, text) },
                    onAbortSummary = { session.abortBranchSummary() },
                    skipSummaryPrompt = { session.branchSummarySkipPrompt() },
                    onScrolledChange = { treeScrolled = it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    // 单行筛选条 + 右端固定计数/进度（彻底去除冗余的双行顶部 Chrome 与 ASCII 符号）
                    val searching = list.query.query.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                top = 4.dp,
                                bottom = 6.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (allGroups.size > 1 || currentProjectOnly) {
                                SessionFilterPill(
                                    text = if (currentProjectOnly) tr("当前项目", "Current") else tr("全部项目", "All projects"),
                                    icon = Icons.Filled.FolderOpen,
                                    active = currentProjectOnly,
                                    onClick = { currentProjectOnly = !currentProjectOnly },
                                )
                            }
                            val effectiveSort = if (!searching && list.query.sort == SessionSort.Relevance) {
                                SessionSort.Threaded
                            } else {
                                list.query.sort
                            }
                            SessionFilterPill(
                                text = when (effectiveSort) {
                                    SessionSort.Threaded -> if (searching) tr("按时间", "Recent") else tr("按分支", "Threaded")
                                    SessionSort.Recent -> tr("按时间", "Recent")
                                    SessionSort.Relevance -> tr("最相关", "Relevance")
                                },
                                icon = when (effectiveSort) {
                                    SessionSort.Threaded -> if (searching) Icons.Filled.Schedule else Icons.Filled.AccountTree
                                    SessionSort.Recent -> Icons.Filled.Schedule
                                    SessionSort.Relevance -> Icons.AutoMirrored.Filled.Sort
                                },
                                active = false,
                                onClick = {
                                    val next = if (searching) {
                                        if (effectiveSort == SessionSort.Relevance) SessionSort.Recent else SessionSort.Relevance
                                    } else {
                                        if (effectiveSort == SessionSort.Threaded) SessionSort.Recent else SessionSort.Threaded
                                    }
                                    session.setSessionSort(next)
                                },
                            )
                            SessionFilterPill(
                                text = tr("已命名", "Named"),
                                icon = if (list.query.namedOnly) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                active = list.query.namedOnly,
                                onClick = { session.setSessionNamedOnly(!list.query.namedOnly) },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val searchFilteredRows = remember(uniqueRows, currentProjectOnly, currentCwds) {
                            if (!currentProjectOnly) uniqueRows
                            else uniqueRows.filter { it.cwd.trimEnd('/') in currentCwds }
                        }
                        Text(
                            text = sessionCountText(
                                list = list,
                                visibleCount = when {
                                    searching -> searchFilteredRows.size
                                    currentProjectOnly -> visibleRowCount
                                    else -> uniqueRows.size
                                },
                            ),
                            style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                            color = if (list.loading) PiTheme.palette.accent else PiTheme.palette.muted,
                            maxLines = 1,
                        )
                    }

                    AnimatedVisibility(
                        visible = listSearchOpen,
                        enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
                    ) {
                        SessionSearchField(
                            query = list.query.query,
                            loading = list.loading && list.query.query.isNotBlank(),
                            onChange = session::setSessionQuery,
                            error = list.queryError,
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val rows = remember(uniqueRows, currentProjectOnly, currentCwds) {
                            if (!currentProjectOnly) uniqueRows
                            else uniqueRows.filter { it.cwd.trimEnd('/') in currentCwds }
                        }
                        val noQuery = list.query.query.isBlank()
                        val searching = !noQuery
                        when {
                            uniqueRows.isEmpty() && list.loading -> SessionsLoading(list.loaded, list.loadTotal)
                            uniqueRows.isEmpty() && list.failed -> SessionsEmpty(
                                icon = Icons.Filled.Warning,
                                title = tr("读不到会话列表", "Failed to load sessions"),
                                body = tr("这一次读取失败了，不是没有会话。可以再试一次。", "Loading failed this time — it does not mean there are no sessions. Please try again."),
                                actionLabel = tr("重试", "Retry"),
                                actionIcon = Icons.Filled.Refresh,
                                onAction = { session.refreshSessions() },
                            )
                            uniqueRows.isEmpty() && list.queryError != null -> SessionsEmpty(
                                icon = Icons.Filled.Warning,
                                title = tr("搜索写法有误", "Invalid search syntax"),
                                body = tr("请调整上方搜索表达式，或清空搜索查看全部会话。", "Adjust the search pattern above or clear search to view all sessions."),
                                actionLabel = tr("清空搜索", "Clear search"),
                                actionIcon = Icons.Filled.Close,
                                onAction = { session.setSessionQuery("") },
                            )
                            uniqueRows.isEmpty() && noQuery && list.query.namedOnly -> SessionsEmpty(
                                icon = Icons.Filled.BookmarkBorder,
                                title = tr("没有已命名的会话", "No named sessions"),
                                body = tr("长按任意会话并重命名后，会出现在这里。", "Rename any session to see it under Named."),
                                actionLabel = tr("显示全部会话", "Show all sessions"),
                                onAction = { session.setSessionNamedOnly(false) },
                            )
                            uniqueRows.isEmpty() && noQuery -> SessionsEmpty(
                                icon = Icons.Filled.Forum,
                                title = tr("还没有会话", "No sessions yet"),
                                body = tr("发出第一条消息后，会话就会出现在这里。", "Your sessions will appear here after you send your first message."),
                                actionLabel = tr("新建会话", "New Session"),
                                actionIcon = Icons.Filled.Add,
                                onAction = {
                                    session.newSession()
                                    onOpenChat()
                                },
                            )
                            searching && rows.isEmpty() -> SessionsEmpty(
                                icon = Icons.Filled.Search,
                                title = tr("没有匹配的会话", "No matching sessions"),
                                body = if (currentProjectOnly) {
                                    tr("当前项目下没有匹配项，可切换到「全部项目」或清空筛选。", "No matches in current project. Switch to All projects or clear filters.")
                                } else {
                                    tr("换一个关键词，或清空筛选条件。", "Try a different keyword or reset filters.")
                                },
                                actionLabel = if (currentProjectOnly) tr("查看全部项目", "Show all projects") else tr("清空筛选", "Clear filters"),
                                actionIcon = if (currentProjectOnly) Icons.Filled.FolderOpen else Icons.Filled.Close,
                                onAction = {
                                    if (currentProjectOnly) {
                                        currentProjectOnly = false
                                    } else {
                                        session.setSessionQuery("")
                                        if (list.query.namedOnly) session.setSessionNamedOnly(false)
                                    }
                                },
                            )
                            noQuery && visibleGroups.isEmpty() -> SessionsEmpty(
                                icon = Icons.Filled.FolderOpen,
                                title = tr("当前项目没有符合条件的会话", "No matching sessions in this project"),
                                body = tr("可以切换到「全部项目」查看其他工作区的历史会话。", "Switch to \"All projects\" to browse sessions from other workspaces."),
                                actionLabel = tr("查看全部项目", "Show all projects"),
                                onAction = { currentProjectOnly = false },
                            )
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = 4.dp,
                                    bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
                                ),
                            ) {
                                if (searching) {
                                    // 对照官方 SDK：搜索态严格保留相关度/时间平铺排序，不打散分组。
                                    itemsIndexed(rows, key = { _, r -> r.file.absolutePath }) { index, summary ->
                                        val isActive = summary.isCurrent || (activeFile != null && summary.file.name == activeFile)
                                        val wsLabel = WorkspaceChoice.sessionGroupLabel(
                                            summary.cwd.trimEnd('/'),
                                            workspacesRoot,
                                            workspaceLabels,
                                        )
                                        SessionListRow(
                                            summary = summary,
                                            active = isActive,
                                            tree = false,
                                            first = index == 0,
                                            last = index == rows.lastIndex,
                                            showPath = list.showPath,
                                            forkedFrom = forkParentNames[summary.file.absolutePath],
                                            workspaceTag = wsLabel,
                                            onOpen = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                if (!isActive) session.switchSession(summary)
                                                onOpenChat()
                                            },
                                            onLongPress = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                actions = summary
                                            },
                                        )
                                    }
                                } else {
                                    visibleGroups.forEach { group ->
                                        val collapsed = group.cwd in collapsedGroups
                                        item(key = "hdr:${group.cwd}") {
                                            WorkspaceGroupHeader(
                                                label = group.label,
                                                count = group.rows.size,
                                                current = group.current,
                                                collapsed = collapsed,
                                                alone = collapsed,
                                                onToggle = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    collapsedGroups = if (group.cwd in collapsedGroups) {
                                                        collapsedGroups - group.cwd
                                                    } else {
                                                        collapsedGroups + group.cwd
                                                    }
                                                },
                                            )
                                        }
                                        if (collapsed) {
                                            item(key = "gap:${group.cwd}") { Spacer(Modifier.height(10.dp)) }
                                            return@forEach
                                        }
                                        if (group.rows.isEmpty()) {
                                            item(key = "empty:${group.cwd}") {
                                                Text(
                                                    tr("这个项目还没有会话", "No sessions in this project yet"),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                                                        .clip(sessionCardShape(first = false, last = true))
                                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                        .padding(
                                                            start = PiSettingsMetrics.rowPaddingHorizontal,
                                                            end = PiSettingsMetrics.rowPaddingHorizontal,
                                                            top = 10.dp,
                                                            bottom = 12.dp,
                                                        ),
                                                    style = PiTheme.text.meta,
                                                    color = PiTheme.palette.muted,
                                                )
                                            }
                                        }
                                        itemsIndexed(group.rows, key = { _, r -> r.file.absolutePath }) { index, summary ->
                                            val isActive = summary.isCurrent || (activeFile != null && summary.file.name == activeFile)
                                            SessionListRow(
                                                summary = summary,
                                                active = isActive,
                                                tree = tree,
                                                first = false,
                                                last = index == group.rows.lastIndex,
                                                showPath = list.showPath,
                                                forkedFrom = if (tree) null else forkParentNames[summary.file.absolutePath],
                                                onOpen = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    if (!isActive) session.switchSession(summary)
                                                    onOpenChat()
                                                },
                                                onLongPress = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    actions = summary
                                                },
                                            )
                                        }
                                        item(key = "gap:${group.cwd}") { Spacer(Modifier.height(10.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 会话操作面板：重命名、跨项目分支到当前项目（官方 SessionManager.forkFrom）、删除。
    val acting = actions
    if (acting != null) {
        val actingIsCurrent = acting.isCurrent || (activeFile != null && acting.file.name == activeFile)
        val actingCwd = acting.cwd.trimEnd('/')
        val isOtherProject = actingCwd.isNotEmpty() && actingCwd !in currentCwds
        PiSheet(onDismiss = { actions = null }) {
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
                    sessionTitleOf(acting),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(sessionAgeLabel(System.currentTimeMillis() - acting.lastActivityAt, zh))
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                SessionActionRow(
                    icon = Icons.Filled.Edit,
                    label = tr("重命名", "Rename"),
                    subtitle = null,
                    tone = MaterialTheme.colorScheme.onSurface,
                    iconTint = PiTheme.palette.accent,
                ) {
                    actions = null
                    renaming = acting
                }
                if (acting.file.isFile || acting.messageCount > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = PiSpacing.hairlineThin,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    SessionActionRow(
                        icon = Icons.Filled.AccountTree,
                        label = if (isOtherProject) tr("分支到当前项目", "Fork into current project") else tr("分支为新会话", "Fork as new session"),
                        subtitle = if (isOtherProject) {
                            tr("保留历史上下文，以当前项目目录继续对话", "Keep history and branch into the current project directory")
                        } else {
                            tr("从当前历史分出一条独立新会话", "Start an independent session from this history")
                        },
                        tone = MaterialTheme.colorScheme.onSurface,
                        iconTint = PiTheme.palette.success,
                    ) {
                        actions = null
                        session.forkSessionToCurrentWorkspace(acting)
                        onOpenChat()
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    thickness = PiSpacing.hairlineThin,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
                SessionActionRow(
                    icon = Icons.Filled.Delete,
                    label = tr("删除会话", "Delete session"),
                    subtitle = if (actingIsCurrent) tr("将自动新建对话并删除当前会话", "Starts a new chat and deletes this active session") else null,
                    tone = PiTheme.palette.error,
                    iconTint = PiTheme.palette.error,
                    enabled = true,
                ) {
                    actions = null
                    confirming = acting
                }
            }
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

    val pending = confirming
    if (pending != null) {
        val isActive = pending.isCurrent || (activeFile != null && pending.file.name == activeFile)
        val pendingTitle = sessionTitleOf(pending)
        PiDialog(onDismissRequest = { confirming = null }) {
            PiDialogTitle(
                title = tr("删除会话", "Delete Session"),
                glyph = "!",
                glyphTone = PiTheme.palette.error,
            )
            PiDialogBody(
                if (isActive) {
                    tr(
                        "将自动新建空白对话并删除当前会话「$pendingTitle」。有回收站时会移到回收站，没有就直接删除，无法恢复。",
                        "A new empty chat will be created and active session \"$pendingTitle\" will be deleted (moved to Trash if available, otherwise permanently deleted).",
                    )
                } else {
                    tr(
                        "删除「$pendingTitle」？有回收站时会移到回收站，没有就直接删除，无法恢复。",
                        "Delete \"$pendingTitle\"? It will be moved to Trash if available, or permanently deleted.",
                    )
                },
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { confirming = null })
                PiDialogAction(
                    label = tr("删除", "Delete"),
                    primary = true,
                    tone = PiTheme.palette.error,
                    onClick = {
                        session.deleteSession(pending)
                        confirming = null
                    },
                )
            }
        }
    }

}

private fun sessionTitleOf(summary: SessionSummary): String =
    displayText(summary.name, summary.firstMessage).ifEmpty { tr("（空会话）", "(Empty session)") }

private fun sessionCountText(
    list: PiSessionViewModel.SessionListState,
    visibleCount: Int = list.rows.size,
): String = when {
    list.loading && list.query.query.isNotBlank() && list.loadTotal > 0 -> tr("搜索 ${list.loaded}/${list.loadTotal}", "${list.loaded}/${list.loadTotal}")
    list.loading && list.query.query.isNotBlank() -> tr("搜索中…", "Searching…")
    list.rows.isEmpty() && list.loading && list.loadTotal > 0 -> tr("读取 ${list.loaded}/${list.loadTotal}", "${list.loaded}/${list.loadTotal}")
    list.rows.isEmpty() && list.loading -> tr("读取中…", "Loading…")
    list.total > visibleCount -> tr("$visibleCount/${list.total} 条", "$visibleCount/${list.total}")
    else -> tr("$visibleCount 条", "$visibleCount")
}

@Composable
private fun SessionsEmpty(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
    progress: Float? = null,
) {
    val actionSlot: (@Composable () -> Unit)? = when {
        actionLabel != null -> {
            {
                SessionFilterPill(
                    text = actionLabel,
                    icon = actionIcon,
                    active = true,
                    onClick = onAction,
                )
            }
        }
        progress != null -> {
            {
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(PiTheme.palette.accent),
                    )
                }
            }
        }
        else -> null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PiEmptyState(
            icon = icon,
            title = title,
            body = body,
            modifier = Modifier.padding(top = 72.dp),
            action = actionSlot,
        )
    }
}

@Composable
private fun SessionSearchField(query: String, loading: Boolean, onChange: (String) -> Unit, error: String?) {
    val focus = remember { FocusRequester() }
    // 本地独立状态：避免后台全文索引回流触发重组时打断中文拼音 IME 组合态。
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
                top = 2.dp,
                bottom = 8.dp,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (error != null) BorderStroke(1.dp, PiTheme.palette.error.copy(alpha = 0.6f)) else null,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = PiTheme.palette.muted,
                )
                Box(Modifier.weight(1f)) {
                    if (localInput.isEmpty()) {
                        Text(
                            tr("搜索名字、内容、目录…", "Search name, content, or folder…"),
                            style = PiTheme.text.prose,
                            color = PiTheme.palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = localInput,
                        onValueChange = { next ->
                            localInput = next
                            onChange(next)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
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
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = tr("清除", "Clear")) {
                                localInput = ""
                                onChange("")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = tr("清除", "Clear"),
                            modifier = Modifier.size(16.dp),
                            tint = PiTheme.palette.muted,
                        )
                    }
                }
            }
        }
        if (error != null || localInput.isEmpty()) {
            Text(
                error ?: tr("支持 re:<正则> 与 \"短语\" 精确匹配", "Supports re:<regex> and \"exact phrase\""),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                style = PiTheme.text.meta,
                color = if (error != null) PiTheme.palette.error else PiTheme.palette.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
    val levels = if (tree) summary.depth.coerceAtMost(4) else 0
    val accent = PiTheme.palette.accent
    val lineColor = PiTheme.palette.borderMuted
    val named = !summary.name.isNullOrBlank()
    val zh = PiLocale.isZh
    val cardShape = sessionCardShape(first = first, last = last)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (active) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)) else Modifier)
                .drawBehind {
                    val unit = SESSIONS_TREE_UNIT.toPx()
                    val stroke = 1.2.dp.toPx()
                    val start = PiSettingsMetrics.rowPaddingHorizontal.toPx()
                    if (levels > 0) {
                        val mid = size.height / 2f
                        val r = 5.dp.toPx()
                        summary.ancestorContinues.forEachIndexed { i, continues ->
                            if (i in 1..levels && continues) {
                                val x = start + (i - 1 + 0.5f) * unit
                                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke, cap = StrokeCap.Round)
                            }
                        }
                        val x = start + (levels - 1 + 0.5f) * unit
                        val endX = start + levels * unit - 2.dp.toPx()
                        if (!summary.isLast) {
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke, cap = StrokeCap.Round)
                            drawLine(lineColor, Offset(x, mid), Offset(endX, mid), stroke, cap = StrokeCap.Round)
                        } else {
                            val elbow = Path().apply {
                                moveTo(x, 0f)
                                lineTo(x, (mid - r).coerceAtLeast(0f))
                                quadraticTo(x, mid, (x + r).coerceAtMost(endX), mid)
                                lineTo(endX, mid)
                            }
                            drawPath(elbow, color = lineColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                        }
                    }
                    if (active) {
                        val inset = 10.dp.toPx()
                        val width = 3.dp.toPx()
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
                    top = 11.dp,
                    bottom = 11.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (named) {
                            Icon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = tr("已命名", "Named"),
                                tint = if (active) accent else accent.copy(alpha = 0.78f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = sessionTitleOf(summary),
                            modifier = Modifier.weight(1f, fill = false),
                            style = if (active || named) PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold) else PiType.rowTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = sessionAgeLabel(System.currentTimeMillis() - summary.lastActivityAt, zh) +
                                " · " + summary.messageCount + tr(" 条", if (summary.messageCount == 1) " msg" else " msgs"),
                            style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                            color = PiTheme.palette.muted,
                            maxLines = 1,
                        )
                        if (active) {
                            Text(
                                text = tr("当前", "Active"),
                                modifier = Modifier
                                    .clip(PiShapes.tag)
                                    .background(accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = accent,
                                maxLines = 1,
                            )
                        }
                        if (!workspaceTag.isNullOrBlank()) {
                            SessionTag(workspaceTag, Modifier.weight(1f, fill = false))
                        }
                        if (forkedFrom != null) {
                            SessionTag(
                                text = if (forkedFrom.length > 10) tr("分支", "Fork") else tr("自 $forkedFrom", "From $forkedFrom"),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    if (showPath) {
                        Text(
                            summary.pathShort,
                            modifier = Modifier.padding(top = 3.dp),
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.dim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("会话操作", "Session actions"), onClick = onLongPress),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = tr("更多操作", "More actions"),
                        tint = PiTheme.palette.dim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (!tree && !last) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal),
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            )
        }
    }
}

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
    if (withEmptyCurrent && groups.none { it.current }) {
        currentCwds.firstOrNull()?.let { cwd ->
            groups += SessionGroup(cwd, WorkspaceChoice.sessionGroupLabel(cwd, workspacesRoot, labels), emptyList(), current = true)
        }
    }
    return groups.sortedWith(
        compareByDescending<SessionGroup> { it.current }
            .thenByDescending { g -> g.rows.maxOfOrNull { it.lastActivityAt } ?: 0L },
    )
}

private fun sessionCardShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val r = PiSettingsMetrics.cardRadius
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
    )
}

@Composable
private fun WorkspaceGroupHeader(
    label: String,
    count: Int,
    current: Boolean,
    collapsed: Boolean,
    alone: Boolean,
    onToggle: () -> Unit,
) {
    val angle by animateFloatAsState(if (collapsed) 0f else 90f, tween(180), label = "group-chevron")
    val accent = PiTheme.palette.accent
    val shape = sessionCardShape(first = true, last = alone)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (current) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (current) accent else PiTheme.palette.muted,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current) {
                    Text(
                        tr("当前", "Current"),
                        modifier = Modifier
                            .clip(PiShapes.tag)
                            .background(accent.copy(alpha = 0.13f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(angle),
                tint = PiTheme.palette.muted,
            )
        }
        if (!alone) {
            HorizontalDivider(
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
        }
    }
}

/** 极简克制筛选片：中性高对比激活态 + 物理弹簧色彩过渡 + 轻触觉反馈。 */
@Composable
private fun SessionFilterPill(
    text: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pill-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pill-fg",
    )
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(bg)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = fg,
            )
        }
        Text(
            text = text,
            style = PiTheme.text.meta.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun SessionsLoading(loaded: Int, total: Int) {
    SessionsEmpty(
        icon = Icons.Filled.Schedule,
        title = tr("正在读取会话…", "Loading sessions…"),
        body = if (total > 0) {
            tr("已读 $loaded/$total 个会话文件，读到的会陆续出现。", "Loaded $loaded/$total session files; results appear as they load.")
        } else {
            tr("正在打开会话目录，首次加载后再次打开即刻呈现。", "Opening session directory; reopening is instant once cached.")
        },
        progress = if (total > 0) (loaded.toFloat() / total).coerceIn(0f, 1f) else null,
    )
}

@Composable
private fun SessionTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(PiShapes.tag)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SessionActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    tone: Color,
    iconTint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
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
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = PiType.rowTitle,
                color = tone,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
            }
        }
    }
}

private val SESSIONS_TREE_UNIT = 16.dp
