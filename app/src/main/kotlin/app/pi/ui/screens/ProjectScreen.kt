package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.packages.AgentLayout
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.ui.BashRun
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.WorkspaceSwitch
import app.pi.ui.blocks.DiffBlock
import app.pi.ui.blocks.argString
import app.pi.ui.blocks.lineCount
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateChip
import app.pi.ui.theme.StateTone
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区 —— 这个会话的**项目现场**，而且现在能动手。
 *
 * ## 这一屏回答什么
 *
 * 「agent 在这个目录里到底在干什么，我自己又能在这里做什么？」用户在对话页看的是**过程**，
 * 在这里看的是**现场**。这一版按
 * `design/ui-refactor/design-demos/workspace-final.html`（工作区最终方案，17 个状态齐全）
 * 把这一屏从「只读」补成「现场 + 能动手」。**现在分成两页**（用户裁决）：
 *
 * | 分段 | 内容 | 数据从哪来 |
 * |---|---|---|
 * | 文件 | 工作区芯片 + 正在跑 + 本次改动（>0 才出现）+ 面包屑 + 全部文件 | **宿主 File I/O** + 转录里 `write`/`edit` 的路径（`ToolDiff` 的 `+N −M`） |
 * | 资源 | 类型 chip × 来源分组 | `pi` 自己的收集规则（见 `WorkspaceResources.kt`） |
 *
 * 两页原来摞在**一条**列表里：往下滚才看见资源，资源段里还套着一层类型筛选 ——
 * 页面像两个功能硬拼的。顶栏也不再有状态行，引擎状态在对话页顶栏已经有了。
 *
 * ## 文件能力不依赖引擎
 *
 * 工作区是 App 私有目录里的一个文件夹
 * （`GuestWorkspacePath.RELATIVE`），所以文件页与查看器 / 编辑器 / 新建 / 重命名 / 删除
 * 全部走宿主 File I/O —— 引擎没起来时它们照常可用。「正在跑」与「本次改动」依赖转录，
 * 引擎没起来时那两段不出现（顶栏下一条警告说明原因），而不是一张「什么都没发生」的空表。
 *
 * ## 没有 git
 *
 * v2 那张卡上的「分支 main · 3 个文件有改动」在这一版里**删掉了**：pi 没有 git 通道
 * （`rpc-types.ts:20-74` 里没有任何 branch/status/diff 命令），工作区本身也不是 git 仓库。
 * 拿不到就不显示 —— 理由完整地写在 `ProjectResources.kt` 的 KDoc 里。
 *
 * ## 它不是什么
 *
 * 它**不挂** `ExtensionUiHost`（全局只挂一次，在 `PiRoot`），它也**不碰** `ui/terminal`。
 *
 * 返回键：这一屏**仍然没有**自己的 `BackHandler`。三处底部面板换成了自绘的 `WsSheet`，
 * 它去掉了 `ModalBottomSheet` 那副壳、但**仍然是一层窗口**（Compose 的 `Dialog`）：稿子的
 * `.b-sheetwrap` 要连屏底那条 tab bar 一起盖住，而 `Scaffold` 的底栏是画在 body 之上的，
 * 画在屏内的 Box 浮层会被底栏压掉一截。窗口这一层顺带把返回键也带回来了 ——
 * `dismissOnBackPress` 走 `onDismissRequest`，与 `ModalBottomSheet` 同一条路径，也正是
 * `PiImageViewer` 记下的全应用惯例：浮层用窗口，屏里不再出现第二个 `BackHandler`。
 */
@Composable
fun ProjectScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    /** 顶栏左侧的返回箭头：底栏拆掉之后，这一屏只能回到对话。 */
    onBack: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 工作区主目录由应用固定（`GuestWorkspacePath.RELATIVE`），`AgentLayout` 的
    // `hostProjectConfigDir()` 就是 `<workspace>/.pi`——pi 的项目资源根。
    //
    // 键是**工作区路径**，不只是 context：切换工作区换的是目录
    // （`PtyLauncher.workspaceHost` 走 `WorkspaceStore.currentHost`，`WorkspaceStore.setCurrent`
    // 只改设置里的名字），而 Activity 的 context 不变 —— `remember(context)` 会把这一屏
    // 钉在首次组合时那个工作区上，切换之后一路读旧目录。`PiSettingsStack.kt:206-212` /
    // `ModelsScreen.kt` 开头是同一手法（原来分属 PiModelsScreen / PiCredentialScreen
    // 两屏，现已合并成一屏）。
    // 路径是一次设置读取，不是一次目录扫描。
    val workspacePath = PtyLauncher.workspaceHost(context).absolutePath
    val workspace = remember(context, workspacePath) { PtyLauncher.workspaceHost(context) }
    val layout = remember(context, workspace) {
        AgentLayout(context.applicationContext, workspace)
    }
    val configDir = remember(layout) { layout.hostProjectConfigDir() }
    val agentDir = layout.agentMirrorDir
    val guestWorkspace = layout.guestWorkspace

    var refreshTick by remember { mutableIntStateOf(0) }

    // 工作区清单 —— 切换面板里那份「现有工作区」，也是工作区芯片上那个名字的 label 来源。
    // 取自引擎的真实入口（`session.workspaceEntries()` → `WorkspaceStore.list`），不是拿当前
    // 工作区的兄弟目录凑出来的 —— 名字、label、`isCurrent` 与切换时 `switchWorkspace` 用的是
    // 同一份判据。它是一次真实的磁盘读取，所以在 IO 线程上跑，不放在 composition 里。
    // 键里的 `revision` 是「换过工作区」的信号（`WorkspaceState`），切换之后重读一遍。
    var workspaceEntries by remember { mutableStateOf<List<WorkspaceStore.Entry>>(emptyList()) }
    LaunchedEffect(refreshTick, state.workspace.revision) {
        workspaceEntries = withContext(Dispatchers.IO) { session.workspaceEntries() }
    }
    // 每个项目各自的信任结论（SDK 的信任库说了算，宿主 `project.trustOf`）：键是 guest 路径。
    // 引擎没起就是空表，切换列表照常显示、只是不带徽标。
    var projectTrust by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    LaunchedEffect(workspaceEntries, state.workspace.revision) {
        val access = session.extensionsAccess()
        projectTrust = if (access == null) emptyMap() else access.resources.trustOf(workspaceEntries.map { it.guestPath })
    }

    // 当前工作区**叫什么**：一份真相，就是 ViewModel 发布的那份身份 —— 工作区**目录名**
    // （`WorkspaceState.name`，由 `WorkspaceStore.reconcile` 与 `switchWorkspace` 写），label 从
    // 上面那份 `workspaceEntries` 里按这个名字取（也就是切换面板里同一行印的名字）。
    //
    // 这里**原来**是从会话 cwd 推的（`state.meta.sessionFile` → 会话列表的 `cwd` →
    // `PiProject.workspaceName`）。那是第二份真相，而且是会过期的那一份：改过显示名之后
    // 芯片印目录名、切换面板印 label（同一个工作区两个名字）；刚切完的头一两秒还会印着
    // **上一个**工作区的名字（新会话头要等 pi 起来才写，`state.meta.sessionFile` 在那之前
    // 还是旧值）。会话头里的 `cwd` 是「那一次会话在哪儿跑过」的记录，不是「现在在哪儿」——
    // pi 那边只有一个答案（`process.cwd()`，`main.ts:580`），本应用对这个答案的唯一权威是
    // `WorkspaceStore`。
    //
    // 引擎没起来时这一行照样对：它读的是设置，不是引擎。`workspaceEntries` 还没读回来
    // （第一帧）时先印目录名 —— `displayName` 在没有 label 时就是这个答案，所以不会印出
    // 一个错名字，只会晚一帧变成显示名。
    val workspaceName = WorkspaceChoice.displayName(
        name = state.workspace.name,
        label = workspaceEntries.firstOrNull { it.name == state.workspace.name }?.label,
    )

    // 引擎没起来：「正在跑」与「本次改动」没有数据，文件与资源照常。判据是引擎状态本身，不是转录的
    // 长度——转录为空既可能是引擎没起来，也可能是这个会话真的什么都没做。
    val engine = state.engine
    val engineDown = engine == null ||
        engine == PiEngineSession.EngineState.Stopped ||
        engine == PiEngineSession.EngineState.Failed

    // ---------------------------------------------------------------- ④ 浏览
    //
    // ## 浏览的根是 **guest 的 `/`**（`<rootfs>`），不是当前工作区
    //
    // 用户的原话：「工作区现在只能看工作区的文件。但是 AI 很多东西它不写到工作区里，它写到
    // 工作区之外的路径了……就像文件管理一样，再加几层呗。」以当前工作区为根时，`/root`、
    // `/tmp`、别的 `workspaces/<name>`、`/workspace/<别的目录>` —— 引擎**启动**的地方不等于
    // 它**写**的地方 —— 这些一律看不到，而 ③ 那条「本次会话改过」又诚实地列着它们，
    // 于是两半对不上：列得出来、打不开、也标不上。
    //
    // 所以这一屏现在是一台文件管理器：根是 guest 的 `/`，初始停在当前工作区（第一屏看到的
    // 和以前一样），往上走几层就到根。**能看的地方就能改** —— 用户裁定「全都一样，全都能看
    // 能编辑」：多一层「这里只读、那里能写」的规则，就会多一处「为什么这个点不动」的解释，
    // 而 pi 自己（终端里）本来就没有这层边界。
    //
    // 一个诚实的提醒，写在这里而不是变成一条限制：`/opt/pi`、`/opt/node`、`/usr`、`/etc`
    // 这些地方的文件是**载荷装的**，每份载荷带一份自己的路径清单；下次升级会覆盖同名文件、
    // 并剪掉旧版多出来的那些。手改它们不会被拦，但升级之后会变回去 —— 要长期留住的东西放
    // `/workspace` 里（那份清单不包含它）。
    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val guestRootfs = remember(paths) { paths.rootfs }
    // 根到当前工作区的那几段：`workspace / pi / workspaces / workspace-1`。
    //
    // **两边都取规范路径再相减**：`filesDir` 的两个拼法（`/data/user/0/…` 与 `/data/data/…`）
    // 在这里会相遇 —— `PiPaths` 用 `context.filesDir`，而 `workspace` 来自 `PtyLauncher`，
    // 前缀一旦对不上，`removePrefix` 什么也不删，crumbs 就变成整条绝对路径的第一段。
    // `DeviceWorkspace.canonicalize` 的 KDoc 记着同一个坑。
    //
    // **浏览根跟着工作区走**：自带工作区在 rootfs 里（根 = rootfs，原样不变）；外部工作区
    // （`/storage/emulated/0/…`）在 rootfs **之外**，它在 guest 里只是引擎启动时临时绑上去的
    // 一条路径，宿主侧的 `<rootfs>/storage/emulated/0/…` 并不存在 —— 以前不管三七二十一都从
    // rootfs 拼，于是一选手机本地目录就是「无法读取目录 …/rootfs/storage/emulated/0/…」。
    // 外部工作区的浏览根改成宿主的 `/`，面包屑与路径都是真实路径。
    val browseRoot = remember(guestRootfs, workspace) {
        val root = runCatching { guestRootfs.canonicalPath }.getOrNull() ?: guestRootfs.absolutePath
        val here = runCatching { workspace.canonicalPath }.getOrNull() ?: workspace.absolutePath
        if (here == root || here.startsWith(root.trimEnd('/') + "/")) guestRootfs else File("/")
    }
    val homeCrumbs = remember(browseRoot, workspace) {
        val root = runCatching { browseRoot.canonicalPath }.getOrNull() ?: browseRoot.absolutePath
        val here = runCatching { workspace.canonicalPath }.getOrNull() ?: workspace.absolutePath
        here.removePrefix(root).trimStart('/').split('/').filter { it.isNotEmpty() }
    }
    var crumbs by remember(workspace, browseRoot) { mutableStateOf(homeCrumbs) }
    var listing by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var listingError by remember { mutableStateOf<WorkspaceOpen.Failed?>(null) }
    val currentDir = remember(browseRoot, crumbs) {
        crumbs.fold(browseRoot) { dir, segment -> File(dir, segment) }
    }
    // 面包屑是**从 rootfs 根算起**的，而 `relativeParent` 是给 `File(workspace, …)` 用的，
    // 只能相对**工作区**。两者混用会把 `workspace/pi/workspaces/<name>` 再拼到 workspace 后面，
    // 新建的文件因此落在 `…/workspace/workspace/pi/workspaces/…`（用户报的“新建不落在当前层”）。
    val insideWorkspace = crumbs.size >= homeCrumbs.size && crumbs.subList(0, homeCrumbs.size) == homeCrumbs
    val parentRelative = if (insideWorkspace) crumbs.drop(homeCrumbs.size).joinToString("/") else ""
    val whereLabel = if (insideWorkspace) {
        workspaceName + parentRelative.split('/').filter { it.isNotEmpty() }.joinToString("") { " / $it" }
    } else {
        currentDir.absolutePath
    }
    // 只有「换了目录」才清成加载态；同一目录里的刷新（agent 写了文件、自己新建/重命名之后）
    // 保留旧列表直到新结果回来，列表不闪空、滚动位置不丢。
    var listedDir by remember { mutableStateOf<String?>(null) }
    var filterText by remember(currentDir.absolutePath) { mutableStateOf("") }
    // 排序：默认最近修改在最上面；其余三种目录在文件前面。记在 rememberSaveable 里（只存序号）。
    var filterOpen by remember(currentDir.absolutePath) { mutableStateOf(false) }
    var searchWholeProject by rememberSaveable { mutableStateOf(false) }
    var recursiveHits by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var recursiveSearching by remember { mutableStateOf(false) }
    var sortIndex by rememberSaveable { mutableIntStateOf(WorkspaceSort.Newest.ordinal) }
    var sortMenu by remember { mutableStateOf(false) }
    // pi 的 find / grep / `@` 补全都带 `--hidden`（点开头的照列），但**排除 `.git`**（补全里显式 `--exclude .git`，
    // `find` 的兜底路径还排除 `node_modules`，`core/tools/find.js:79`），并遵守 .gitignore。这里照这个来：
    // `.git` 与 `node_modules` 默认收起，其余（含 `.pi`、`.env`）照列；要看被忽略的，在排序菜单里打开。
    // 不逐条解析 .gitignore —— 那是对 fd/rg 的近似，不是它们本身。
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    val sortMode = WorkspaceSort.entries[sortIndex.coerceIn(0, WorkspaceSort.entries.lastIndex)]
    val sortedEntries = remember(listing, sortMode, filterText, showIgnored) {
        val all = listing.orEmpty().filter { showIgnored || !(it.isDirectory && it.name in PI_IGNORED_DIRS) }
        val q = filterText.trim()
        val filtered = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        filtered.sortedWith(sortMode.comparator)
    }
    LaunchedEffect(searchWholeProject, filterText, showIgnored, workspace.absolutePath, refreshTick) {
        val q = filterText.trim()
        if (!searchWholeProject || q.isEmpty()) {
            recursiveHits = emptyList()
            recursiveSearching = false
            return@LaunchedEffect
        }
        recursiveSearching = true
        delay(140)
        recursiveHits = withContext(Dispatchers.IO) {
            WorkspaceFiles.searchRecursive(workspace, q, includeIgnored = showIgnored, limit = 120)
        }
        recursiveSearching = false
    }
    LaunchedEffect(refreshTick, currentDir.absolutePath) {
        if (listedDir != currentDir.absolutePath) listing = null
        listingError = null
        val result = withContext(Dispatchers.IO) { WorkspaceFiles.list(currentDir) }
        result.fold(
            onSuccess = {
                listing = it
                listedDir = currentDir.absolutePath
            },
            onFailure = { error ->
                listing = emptyList()
                listedDir = currentDir.absolutePath
                listingError = WorkspaceOpen.Failed(
                    reason = tr("这个目录读不到了，可能被重命名或删掉；工作区里别的地方不受影响。", "Unable to read this directory — it may have been renamed or deleted. The rest of the workspace is unaffected."),
                    detail = "${error::class.java.name}: ${error.message}\n  ${currentDir.absolutePath}",
                )
            },
        )
    }

    // ------------------------------------------------------------ ⑤ 资源
    // 资源（扩展 / 技能 / 提示 / 主题 / 包 / 项目信任）统一在「设置 → 扩展与资源」，这里只放入口，不再复制一份。
    // 入口行的副标题读宿主的项目信任结论（`project.trustState`，SDK 的信任库说了算）；读不到就退回静态文案。
    var trustLoaded by remember { mutableStateOf(false) }
    var trustTrusted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(refreshTick, state.workspace.revision) {
        val r = session.extensionsAccess()?.resources?.trustState()
        val parsed = (r as? app.pi.packages.PiResourceCatalog.Parsed.Ok)?.value
        trustLoaded = parsed != null
        trustTrusted = parsed?.let { if (it.trusted) true else if (it.undecided) null else false }
    }
    val trustMeta = if (!trustLoaded) {
        null
    } else {
        when (trustTrusted) {
            true -> tr("已信任", "Trusted")
            null -> tr("待表态", "Pending")
            false -> tr("未信任", "Untrusted")
        }
    }
    var overflow by remember { mutableStateOf(false) }
    // ---------------------------------------------------------------- ②③
    val runs = remember(state.transcript, state.bash) { runningCommands(state.transcript, state.bash) }
    // ② 里那几条「已运行 N 秒」用的读数：ViewModel 推来的 1 Hz 粗时钟（`UiState.nowMs`，判据
    // `UiState.hasPendingToolClock`，收的正好是 `runningCommands` 那一批 `Pending` 的
    // bash/powershell 行）。这一屏**不自己读表**：`System.currentTimeMillis() - startedAt` 量的是
    // 「这一行上一次重组是什么时候」，而一条什么都不打印的命令既没有发布、也就没有重组，读数会冻在
    // 那儿 —— 这正是 `blocks/ShellBlock` 修掉的那个错。`null` 只覆盖两件事：时钟首跳还没落地的
    // 那一次组合，以及调用方没传时钟；那时按老办法现读一次表给出的仍是正确的数，不是「没有读数」。
    val nowMs = state.nowMs
    val pendingWrite = remember(state.transcript) { WorkspaceFiles.pendingWrite(state.transcript) }
    // 「本次会话改过」= write/edit + 这一屏自己保存过的那些（稿子底部第 2 条的口径）。
    var localEdits by remember { mutableStateOf<Set<String>>(emptySet()) }
    val changed = remember(state.transcript, localEdits) {
        WorkspaceFiles.changedFiles(state.transcript, localEdits)
    }
    // 正在写的那一条只出现在 pending 行里（稿子 `running` 状态就是
    // `W_CHANGED.filter(c => !c.pending)`），不要在 ③ 里出现两次。
    val visibleChanged = if (engineDown) {
        emptyList()
    } else {
        changed.filter { it.path != pendingWrite?.path }
    }
    val changedPaths = remember(visibleChanged) { visibleChanged.map { it.path } }

    // ------------------------------------------------------------ 浮层状态
    var viewer by remember { mutableStateOf<WorkspaceViewerTarget?>(null) }
    var menuFor by remember { mutableStateOf<WorkspaceMenuTarget?>(null) }
    var rootSheet by remember { mutableStateOf(false) }
    var newSheet by remember { mutableStateOf(false) }
    var inputDialog by remember { mutableStateOf<WorkspaceInputDialog?>(null) }
    var deleteFor by remember { mutableStateOf<WorkspaceMenuTarget?>(null) }
    var snack by remember { mutableStateOf<WorkspaceSnack?>(null) }
    /** 切换面板里某个工作区行尾 ⋮ 选中的那一行（重命名 / 删除）。 */
    var workspaceMenuFor by remember { mutableStateOf<WorkspaceStore.Entry?>(null) }
    /** 删除确认框：`previewWorkspaceDelete` 的 `Ok`，里面既有要展示的读数，也有删除凭据。 */
    var workspaceDelete by remember { mutableStateOf<WorkspaceStore.Preview.Ok?>(null) }
    /** 这一轮回合正跑着、用户选了别的工作区时，先问一次的那一格。 */
    var workspaceInterrupt by remember { mutableStateOf<WorkspaceStore.Entry?>(null) }
    /** 「从设备目录选择」那一层（权限说明 + 真目录浏览器）。 */
    var externalPicker by remember { mutableStateOf(false) }

    fun say(text: String, tone: StateTone = StateTone.Muted) {
        snack = WorkspaceSnack(text, tone)
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val destDir = currentDir
        scope.launch {
            val (count, err) = withContext(Dispatchers.IO) {
                importWorkspaceUris(context, destDir, uris)
            }
            if (count > 0) {
                refreshTick++
                say(
                    if (err == null) {
                        tr("已导入 $count 个文件到当前目录", if (count == 1) "Imported 1 file" else "Imported $count files")
                    } else {
                        tr("已导入 $count 个文件，部分失败：$err", "Imported $count, some failed: $err")
                    },
                    if (err == null) StateTone.Muted else StateTone.Warning,
                )
            } else if (err != null) {
                say(tr("导入失败：$err", "Import failed: $err"), StateTone.Error)
            }
        }
    }

    LaunchedEffect(snack) {
        if (snack != null) {
            delay(2600)
            snack = null
        }
    }

    /**
     * 转录里那条路径 → 要打开的文件 + 该显示的名字，或者 null。
     *
     * 两种拼法各试一次，**存在的那一个赢**（顺序固定，所以结果可复现）：
     *
     *  1. **工作区相对** —— [WorkspaceFiles.workspaceRelative] 之后从**工作区**起算（不是从
     *     `currentDir`：这条路径是 pi 在它自己的 cwd 里写的，跟用户此刻翻到哪一层无关）。
     *     引擎写的东西绝大多数落在这一支。
     *  2. **guest 的 `/` 之下** —— 绝对路径按 guest 根折一次。pi 并不是只往 cwd 里写：
     *     `/tmp` 里的中间产物、`/root/.pi` 里的东西、`/opt` 里的脚本都真的在 guest 里，
     *     以前这一屏**从来没有**办法看到它们（用户的原话：「AI 很多东西它不写到工作区里」）。
     *
     * 显示名跟着赢的那一支走：工作区相对的那支显示相对路径，另一支显示 pi 写下的**guest
     * 原拼法** —— 那才是那个文件真实的名字，把它显示成别的东西会让人找不到它。
     */
    fun resolveSession(raw: String, guestWorkspace: String): WorkspaceSessionFile? {
        val rootfsCanonical = runCatching { guestRootfs.canonicalFile }.getOrDefault(guestRootfs.absoluteFile)
        val workspaceCanonical = runCatching { workspace.canonicalFile }.getOrDefault(workspace.absoluteFile)
        fun isUnder(file: File, base: File): Boolean {
            val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
            return target.path == base.path || target.path.startsWith(base.path + File.separator)
        }
        fun allowed(file: File): File? {
            val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
            if (isUnder(target, workspaceCanonical) || isUnder(target, rootfsCanonical)) return target
            if (target.path.startsWith("/storage/") || target.path.startsWith("/sdcard/")) return target
            return null
        }
        val relative = WorkspaceFiles.workspaceRelative(raw, guestWorkspace)
        if (relative.isNotEmpty()) {
            val candidate = allowed(File(workspace, relative))
            if (candidate != null && candidate.exists()) return WorkspaceSessionFile(candidate, relative)
        }
        if (raw.startsWith("/")) {
            // Direct device storage path (e.g. `/storage/emulated/0/...` or `/sdcard/...`)
            if (raw.startsWith("/storage/") || raw.startsWith("/sdcard/")) {
                val direct = allowed(File(raw))
                if (direct != null && direct.exists()) return WorkspaceSessionFile(direct, workspaceDisplayPath(workspace, direct))
            }
            // Legacy `/workspace/storage/...` guest spelling
            if (raw.startsWith("/workspace/storage/") || raw.startsWith("/workspace/sdcard/")) {
                val direct = allowed(File(raw.removePrefix("/workspace")))
                if (direct != null && direct.exists()) return WorkspaceSessionFile(direct, workspaceDisplayPath(workspace, direct))
            }
            val guest = raw.trimStart('/')
            if (guest.isNotEmpty()) {
                val candidate = allowed(File(guestRootfs, guest))
                if (candidate != null && candidate.exists()) return WorkspaceSessionFile(candidate, raw)
            }
        }
        return null
    }

    fun targetFor(
        displayPath: String,
        file: File,
        editable: Boolean,
        startInEdit: Boolean = false,
    ): WorkspaceViewerTarget = WorkspaceViewerTarget(
        relativePath = displayPath,
        file = file,
        kind = WorkspaceFiles.kindOf(file),
        sizeBytes = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified(),
        editable = editable,
        startInEdit = startInEdit,
    )

    fun openRelative(path: String, file: File, startInEdit: Boolean = false) {
        viewer = targetFor(path, file, editable = true, startInEdit = startInEdit)
    }

    /**
     * 跑一次文件操作。
     *
     * 写盘在 IO 线程，`say`/`refreshTick`/`onDone` 回到主线程 —— 从 IO 线程改 Compose
     * 状态是一次竞态，而不是一次优化。
     */
    fun runFileOp(what: String, onDone: ((Boolean) -> Unit)? = null, block: () -> Result<*>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            result.fold(
                onSuccess = {
                    say(what)
                    refreshTick++
                    onDone?.invoke(true)
                },
                onFailure = { error ->
                    say(
                        tr("操作失败：", "Operation failed: ") + (error.message ?: error::class.java.simpleName),
                        StateTone.Error,
                    )
                    onDone?.invoke(false)
                },
            )
        }
    }

    // ------------------------------------------------------ 工作区级操作（非文件）

    fun switchWorkspaceTo(entry: WorkspaceStore.Entry) {
        session.viewModelScope.launch {
            when (val result = session.switchWorkspace(entry.name, allowInterrupt = true)) {
                is WorkspaceSwitch.Ok -> {
                    crumbs = homeCrumbs
                    localEdits = emptySet()
                    refreshTick++
                }
                is WorkspaceSwitch.AlreadyCurrent -> say(tr("已经在这个项目里。", "Already in this project."))
                is WorkspaceSwitch.Refused -> Unit
                is WorkspaceSwitch.Failed -> Unit
            }
        }
    }

    /** 将任意浏览到的文件夹设为当前 Pi 项目（cwd）并立即切入。 */
    fun openDirectoryAsProject(dir: File) {
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { session.registerProjectDirectory(dir) }) {
                is WorkspaceStore.Create.Ok -> {
                    val entry = result.entry
                    refreshTick++
                    when (
                        WorkspaceChoice.decidePick(
                            isCurrent = entry.isCurrent,
                            turnRunning = session.wouldInterruptTurn(),
                            available = entry.available,
                        )
                    ) {
                        WorkspaceChoice.Pick.AlreadyHere -> say(tr("已经在这个项目里。", "Already in this project."))
                        WorkspaceChoice.Pick.ConfirmInterrupt -> workspaceInterrupt = entry
                        WorkspaceChoice.Pick.Switch -> {
                            say(tr("已切换项目 · ${entry.displayName}", "Switched to project · ${entry.displayName}"))
                            switchWorkspaceTo(entry)
                        }
                        WorkspaceChoice.Pick.Unavailable ->
                            say(tr("目录「${entry.displayName}」当前不可读。", "Folder \"${entry.displayName}\" is currently unreadable."), StateTone.Warning)
                    }
                }
                is WorkspaceStore.Create.Failed -> say(result.message, StateTone.Warning)
            }
        }
    }

    /**
     * 新建项目并直接切入（若当前回合正跑着则先弹出统一的打断确认，避免建完还要再点一次切换）。
     */
    fun createNewWorkspace(dirName: String) {
        val clean = dirName.trim()
        if (clean.isEmpty()) return
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { session.createWorkspace(clean) }) {
                is WorkspaceStore.Create.Ok -> {
                    val entry = result.entry
                    say(tr("已新建项目 · ${entry.displayName}", "Created project · ${entry.displayName}"))
                    refreshTick++
                    when (
                        WorkspaceChoice.decidePick(
                            isCurrent = entry.isCurrent,
                            turnRunning = session.wouldInterruptTurn(),
                            available = entry.available,
                        )
                    ) {
                        WorkspaceChoice.Pick.Switch -> switchWorkspaceTo(entry)
                        WorkspaceChoice.Pick.ConfirmInterrupt -> workspaceInterrupt = entry
                        else -> rootSheet = true
                    }
                }
                is WorkspaceStore.Create.Failed -> say(result.message, StateTone.Warning)
            }
        }
    }

    fun renameWorkspaceTo(name: String, label: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { session.renameWorkspace(name, label) }
            when (result) {
                is WorkspaceStore.Rename.Ok -> {
                    say(tr("已重命名项目 · ${result.entry.displayName}", "Renamed project · ${result.entry.displayName}"))
                    refreshTick++
                }
                is WorkspaceStore.Rename.Failed -> say(result.message, StateTone.Warning)
            }
        }
    }

    /**
     * 删除前的计数。
     *
     * `previewWorkspaceDelete` 是唯一能拿到 `WorkspaceStore.DeleteConfirmation` 的入口，
     * 也就是「先把里面有多少东西报告给用户、用户同意的是那一次统计」这条规矩的 API 形状；
     * 所以删除不可能绕过这一格。`Preview.Refused` 的原话（比如「是当前工作区……请先切换」）
     * 照显示，不吞也不改写。
     */
    fun previewWorkspaceDeleteFor(entry: WorkspaceStore.Entry) {
        scope.launch {
            val preview = withContext(Dispatchers.IO) { session.previewWorkspaceDelete(entry.name) }
            when (preview) {
                is WorkspaceStore.Preview.Ok -> workspaceDelete = preview
                is WorkspaceStore.Preview.Refused -> say(preview.message, StateTone.Warning)
            }
        }
    }

    fun deleteWorkspaceNow(preview: WorkspaceStore.Preview.Ok) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { session.deleteWorkspace(preview.confirmation) }
            when (result) {
                is WorkspaceStore.Delete.Ok -> {
                    val doneText = if (preview.target.removesFiles) {
                        tr("已删除项目 · ${preview.target.label}（${result.files} 个文件）", "Deleted project · ${preview.target.label} (${result.files} ${if (result.files == 1) "file" else "files"})")
                    } else {
                        tr("已从项目列表移除 · ${preview.target.label}", "Removed from projects · ${preview.target.label}")
                    }
                    say(doneText)
                    refreshTick++
                }
                is WorkspaceStore.Delete.Refused -> say(result.message, StateTone.Error)
                is WorkspaceStore.Delete.Failed -> say(result.message, StateTone.Error)
            }
        }
    }

    fun useExternalDirectory(path: String) {
        scope.launch {
            val app = context.applicationContext ?: context
            when (val result = withContext(Dispatchers.IO) { WorkspaceStore.registerExternal(app, path) }) {
                is WorkspaceStore.Create.Ok -> {
                    val entry = result.entry
                    say(tr("已打开项目 · ${entry.displayName}", "Opened project · ${entry.displayName}"))
                    refreshTick++
                    when (
                        WorkspaceChoice.decidePick(
                            isCurrent = entry.isCurrent,
                            turnRunning = session.wouldInterruptTurn(),
                            available = entry.available,
                        )
                    ) {
                        WorkspaceChoice.Pick.Unavailable ->
                            say(tr("「${entry.displayName}」现在读不到，已登记但未切换。", "\"${entry.displayName}\" is currently unreadable; registered without switching."), StateTone.Warning)

                        WorkspaceChoice.Pick.AlreadyHere -> Unit
                        WorkspaceChoice.Pick.ConfirmInterrupt -> workspaceInterrupt = entry
                        WorkspaceChoice.Pick.Switch -> switchWorkspaceTo(entry)
                    }
                }

                is WorkspaceStore.Create.Failed -> say(result.message, StateTone.Warning)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            PiTopBar(
                onBack = onBack,
                title = tr("项目与文件", "Project & Files"),
                actions = {
                    PiTopBarIcon(
                        onClick = { refreshTick++ },
                        contentDescription = tr("刷新项目文件", "Refresh project files"),
                        icon = Icons.Filled.Refresh,
                    )
                    Box {
                        PiTopBarIcon(
                            onClick = { overflow = true },
                            contentDescription = tr("更多", "More"),
                            icon = Icons.Filled.MoreVert,
                        )
                        if (overflow) PiMenu(
                            expanded = true,
                            onDismiss = { overflow = false },
                            placement = PiMenuPlacement.Below,
                            items = buildList {
                                add(
                                    PiMenuItem(tr("导入手机文件到当前目录", "Import files into this folder")) {
                                        overflow = false
                                        importFilesLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                                add(
                                    PiMenuItem(tr("从设备目录打开或新建项目", "Open or create in device folder")) {
                                        overflow = false
                                        externalPicker = true
                                    },
                                )
                                if (!insideWorkspace || crumbs != homeCrumbs) {
                                    add(
                                        PiMenuItem(tr("回到项目根目录", "Go to project root")) {
                                            overflow = false
                                            crumbs = homeCrumbs
                                        },
                                    )
                                }
                                add(
                                    PiMenuItem(tr(".pi 扩展、资源与信任", ".pi extensions, resources & trust")) {
                                        overflow = false
                                        session.requestNav(NavRequest.SettingsFocus("hub:resources:project"))
                                    },
                                )
                                add(
                                    PiMenuItem(tr("系统提示词与上下文", "System Prompt & Context")) {
                                        overflow = false
                                        session.requestNav(NavRequest.SettingsFocus("hub:prompts"))
                                    },
                                )
                            },
                        )
                    }
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = PiSpacing.scrollBottom),
            ) {
                run {
                    // 项目 Hero 卡：当前项目、位置/信任/改动状态、以及切换项目 / 新建项目。
                    item(key = "project-hero") {
                        val currentEntry = remember(workspaceEntries, state.workspace.name) {
                            workspaceEntries.firstOrNull { it.name == state.workspace.name }
                        }
                        val prettyPath = remember(currentEntry, state.workspace.relative, state.workspace.guestPath) {
                            when {
                                currentEntry?.external == true -> currentEntry.host.absolutePath
                                state.workspace.isExternal -> state.workspace.hostPath ?: state.workspace.relative
                                else -> "~/projects/" + state.workspace.name
                            }
                        }
                        WorkspaceHeroCard(
                            workspaceName = workspaceName,
                            relativePath = prettyPath,
                            isExternal = currentEntry?.external == true || state.workspace.isExternal,
                            trustLabel = trustMeta,
                            trustTrusted = trustTrusted,
                            changedCount = if (engineDown) 0 else changed.size,
                            runningCount = if (engineDown) 0 else runs.size,
                            onSwitchProject = { rootSheet = true },
                            onNewProject = {
                                inputDialog = WorkspaceInputDialog(
                                    kind = WorkspaceInputKind.NewWorkspace,
                                    title = tr("新建项目", "New Project"),
                                    where = tr("选择保存位置并输入项目名称", "Choose location and enter a project name"),
                                    relativeParent = null,
                                    absoluteParent = null,
                                    initial = "",
                                )
                            },
                            onOpenTrust = { session.requestNav(NavRequest.SettingsFocus("hub:resources:project")) },
                        )
                    }
                    // 精简路径面包屑条：不再与 Hero 卡重复显示项目切换胶囊或改动计数；
                    // 浏览到子目录或外部目录时，可直接一键「设为项目」切换 Pi 的 cwd。
                    item(key = "path-bar") {
                        val atProjectRoot = insideWorkspace && crumbs.size == homeCrumbs.size
                        val canUseHereAsProject = !atProjectRoot && runCatching {
                            currentDir.isDirectory && currentDir.canWrite() &&
                                currentDir.absolutePath != "/" &&
                                currentDir.canonicalPath != guestRootfs.canonicalPath
                        }.getOrDefault(false)
                        WorkspacePathBar(
                            workspaceName = workspaceName,
                            inside = insideWorkspace,
                            homeSize = homeCrumbs.size,
                            crumbs = crumbs,
                            rootLabel = ROOTFS_CRUMB_LABEL,
                            canSetAsProject = canUseHereAsProject,
                            onHome = { crumbs = homeCrumbs },
                            onGo = { index -> crumbs = crumbs.take(index) },
                            onSetAsProject = { openDirectoryAsProject(currentDir) },
                        )
                    }
                    if (engineDown) {
                        item {
                            WsNotice(
                                text = tr("引擎未运行：文件仍可直接浏览、编辑与新建。", "Engine is not running: you can still browse, edit, and create files."),
                                tone = StateTone.Warning,
                                glyph = "!",
                            )
                        }
                    }
                    state.workspace.note?.let { note ->
                        item { WsNotice(text = note, tone = StateTone.Warning, glyph = "!") }
                    }

                    if (runs.isNotEmpty()) {
                        item {
                            WsSectionHeader(
                                label = tr("正在跑", "Running"),
                                count = tr("${runs.size} 条命令", if (runs.size == 1) "1 command" else "${runs.size} commands"),
                                aside = runningAside(runs),
                            )
                        }
                        for (index in runs.indices) {
                            val run = runs[index]
                            item(key = "run:${run.key}") {
                              Box(Modifier.animateItem()) {
                                RunningCommandRow(
                                    run = run,
                                    sliceIndex = index,
                                    lastSliceIndex = runs.lastIndex,
                                    nowMs = nowMs,
                                    hairlineStart = WsIconHairlineStart,
                                )
                              }
                            }
                        }
                    }

                    // 「本次会话改过」：**改动数为 0 时整块不出现**（用户裁决）。
                    // 以前固定占一行段头 + 一张空卡，屏幕上 40% 的面积在说「没有」。
                    if (!engineDown && changed.isNotEmpty()) {
                        item {
                            WsSectionHeader(
                                label = tr("本次会话改过", "Changed This Session"),
                                count = tr("${changed.size} 个文件", if (changed.size == 1) "1 file" else "${changed.size} files"),
                                aside = {
                                    Text(
                                        tr("最近改动在上", "Most recent first"),
                                        style = PiTheme.text.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        val pending = pendingWrite
                        val totalSlices = (if (pending != null) 1 else 0) + visibleChanged.size
                        if (pending != null) {
                            item(key = "pending:${pending.path}") {
                              Box(Modifier.animateItem()) {
                                WsCardSlice(
                                    index = 0,
                                    lastIndex = totalSlices - 1,
                                    hairlineStart = WsIconHairlineStart,
                                ) {
                                    PendingWriteRow(pending)
                                }
                              }
                            }
                        }
                        visibleChanged.forEachIndexed { index, file ->
                            val slice = (if (pending != null) 1 else 0) + index
                            item(key = "changed:${file.path}") {
                              Box(Modifier.animateItem()) {
                                WsCardSlice(
                                    index = slice,
                                    lastIndex = totalSlices - 1,
                                    hairlineStart = WsIconHairlineStart,
                                ) {
                                    ChangedFileRow(
                                        file = file,
                                        diff = remember(state.transcript, file.path) {
                                            diffFor(state.transcript, file.path)
                                        },
                                        durationMs = remember(state.transcript, file.path, guestWorkspace) {
                                            WorkspaceFiles.durationFor(
                                                state.transcript,
                                                file.path,
                                                guestWorkspace,
                                            )
                                        },
                                        onClick = {
                                            val resolved = resolveSession(file.path, guestWorkspace)
                                            if (resolved != null) {
                                                openRelative(resolved.displayPath, resolved.file)
                                            } else {
                                                say(tr("找不到这个文件：${file.path}", "File not found: ${file.path}"), StateTone.Warning)
                                            }
                                        },
                                        onMenu = {
                                            val resolved = resolveSession(file.path, guestWorkspace)
                                            if (resolved == null) {
                                                say(tr("找不到这个文件：${file.path}", "File not found: ${file.path}"), StateTone.Warning)
                                            } else {
                                                menuFor = menuTargetFor(
                                                    resolved.displayPath,
                                                    resolved.file,
                                                    fromSession = true,
                                                )
                                            }
                                        },
                                    )
                                }
                              }
                            }
                        }
                    }

                    item {
                        WsSectionHeader(
                            label = tr("全部文件", "Files"),
                            count = if (listingError != null) null else "${listing?.size ?: 0}",
                            aside = if (listingError != null) {
                                null
                            } else {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(WsShape.small))
                                                    .clickable { sortMenu = true }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    sortMode.label,
                                                    style = PiTheme.text.meta,
                                                    color = PiTheme.palette.muted,
                                                    maxLines = 1,
                                                )
                                                Icon(
                                                    Icons.Filled.KeyboardArrowDown,
                                                    contentDescription = tr("排序方式", "Sort order"),
                                                    modifier = Modifier.size(14.dp),
                                                    tint = PiTheme.palette.muted,
                                                )
                                            }
                                            if (sortMenu) PiMenu(
                                                expanded = true,
                                                onDismiss = { sortMenu = false },
                                                placement = PiMenuPlacement.Below,
                                                items = WorkspaceSort.entries.map { mode ->
                                                    PiMenuItem(mode.label) {
                                                        sortIndex = mode.ordinal
                                                        sortMenu = false
                                                    }
                                                } + PiMenuItem(if (showIgnored) tr("收起 .git / node_modules", "Hide .git / node_modules") else tr("显示 .git / node_modules", "Show .git / node_modules")) {
                                                    showIgnored = !showIgnored
                                                    sortMenu = false
                                                },
                                            )
                                        }
                                        Box(
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(WsShape.medium))
                                                .clickable {
                                                    filterOpen = !filterOpen
                                                    if (!filterOpen) filterText = ""
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.Search,
                                                contentDescription = tr("筛选文件名", "Filter files"),
                                                modifier = Modifier.size(20.dp),
                                                tint = if (filterOpen || filterText.isNotEmpty()) {
                                                    PiTheme.palette.accent
                                                } else {
                                                    PiTheme.palette.muted
                                                },
                                            )
                                        }
                                        WsChip(
                                            text = tr("新建", "New"),
                                            glyphIcon = Icons.Filled.Add,
                                            solid = true,
                                            onClick = { newSheet = true },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    val error = listingError
                    val entries = listing
                    if (error != null) {
                        item {
                            WsErrBlock(
                                title = tr("读不到这个目录", "Unable to Read Directory"),
                                message = error.reason,
                                detail = error.detail,
                                actions = {
                                    WsErrAction(tr("重试", "Retry")) { refreshTick++ }
                                    WsErrAction(tr("回到项目根目录", "Back to Project Root")) { crumbs = homeCrumbs }
                                },
                            )
                        }
                    } else if (entries == null) {
                        item {
                            WsCardSlice(index = 0, lastIndex = 0) {
                                WsRow(
                                    title = tr("正在读这个目录…", "Reading directory…"),
                                    lead = { FolderGlyph() },
                                )
                            }
                        }
                    } else if (entries.isEmpty()) {
                        item {
                            WsCardSlice(index = 0, lastIndex = 0) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = WS_EMPTY_DIR_INSET,
                                            end = WS_EMPTY_DIR_INSET,
                                            top = WS_EMPTY_DIR_TOP,
                                            bottom = WS_EMPTY_DIR_BOTTOM,
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = PiTheme.palette.muted.copy(alpha = 0.45f),
                                    )
                                    Text(
                                        tr("这个目录是空的", "This folder is empty"),
                                        modifier = Modifier.padding(top = 8.dp),
                                        style = PiTheme.text.meta,
                                        color = PiTheme.palette.muted,
                                    )
                                    Spacer(Modifier.height(WS_EMPTY_DIR_BUTTON_GAP))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        WsChip(
                                            text = tr("新建文件", "New File"),
                                            glyphIcon = Icons.Filled.Add,
                                            solid = true,
                                            onClick = {
                                                inputDialog = WorkspaceInputDialog(
                                                    kind = WorkspaceInputKind.NewFile,
                                                    title = tr("新建文件", "New File"),
                                                    where = whereLabel,
                                                    relativeParent = parentRelative,
                                                    absoluteParent = currentDir,
                                                    initial = "",
                                                )
                                            },
                                        )
                                        WsChip(
                                            text = tr("导入文件", "Import"),
                                            glyphIcon = Icons.Filled.UploadFile,
                                            solid = false,
                                            onClick = { importFilesLauncher.launch(arrayOf("*/*")) },
                                        )
                                    }
                                    Text(
                                        tr("也可以从右上「新建」里建文件夹或导入文件。", "You can also create folders or import files from \"New\" above."),
                                        modifier = Modifier.padding(top = WS_EMPTY_DIR_HINT_GAP),
                                        style = PiTheme.text.meta,
                                        color = PiTheme.palette.muted,
                                    )
                                }
                            }
                        }
                    } else {
                        // 就地筛选框：支持「当前目录」与「全项目（递归）」两种范围一键切换。
                        val filtering = filterOpen || filterText.isNotEmpty()
                        if (filtering) {
                            item(key = "entry-filter") {
                                WorkspaceFilterField(
                                    value = filterText,
                                    onChange = { filterText = it },
                                    wholeProject = searchWholeProject,
                                    onToggleWholeProject = { searchWholeProject = !searchWholeProject },
                                )
                            }
                        }
                        val isRecursiveActive = searchWholeProject && filterText.isNotBlank()
                        val shown = if (isRecursiveActive) recursiveHits else sortedEntries
                        if (isRecursiveActive && recursiveSearching) {
                            item(key = "entry-recursive-loading") {
                                Text(
                                    tr("正在全项目搜索「${filterText.trim()}」…", "Searching project for \"${filterText.trim()}\"…"),
                                    modifier = Modifier.padding(
                                        horizontal = PiSettingsMetrics.pageHorizontal,
                                        vertical = 14.dp,
                                    ),
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                            }
                        } else if (shown.isEmpty()) {
                            item(key = "entry-filter-empty") {
                                Text(
                                    tr("没有名字里带「${filterText.trim()}」的项。", "No items matching \"${filterText.trim()}\"."),
                                    modifier = Modifier.padding(
                                        horizontal = PiSettingsMetrics.pageHorizontal,
                                        vertical = 16.dp,
                                    ),
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                            }
                        }
                        shown.forEachIndexed { index, entry ->
                            val itemKey = if (isRecursiveActive) "rec:${entry.path}" else "entry:${entry.path}"
                            item(key = itemKey) {
                              Box(Modifier.animateItem()) {
                                val entryFile = if (isRecursiveActive) File(workspace, entry.path) else File(currentDir, entry.name)
                                val displayPath = remember(workspace.absolutePath, entryFile.absolutePath) {
                                    workspaceDisplayPath(workspace, entryFile)
                                }
                                // 这一行的**工作区相对**路径，与「本次会话」徽标比对用。
                                val entryRelative = remember(entryFile.absolutePath, workspace.absolutePath) {
                                    val root = workspace.canonicalFile
                                    val canonical = runCatching { entryFile.canonicalFile }.getOrNull()
                                    when {
                                        canonical == null -> null
                                        canonical.path == root.path -> null
                                        canonical.path.startsWith(root.path + File.separator) ->
                                            canonical.path.removePrefix(root.path + File.separator)
                                        else -> null
                                    }
                                }
                                val matched = if (engineDown || entryRelative == null) {
                                    null
                                } else {
                                    changedPaths.firstOrNull {
                                        WorkspaceFiles.sameFile(entryRelative, it, guestWorkspace)
                                    }
                                }
                                val entryDiff = matched?.let { path ->
                                    remember(state.transcript, path) { diffFor(state.transcript, path) }
                                }
                                WsCardSlice(
                                    index = index,
                                    lastIndex = shown.lastIndex,
                                    hairlineStart = WsIconHairlineStart,
                                ) {
                                    WorkspaceEntryRow(
                                        entry = entry,
                                        sessionChanged = matched,
                                        diff = entryDiff,
                                        subtitlePrefix = if (isRecursiveActive) entry.path.substringBeforeLast('/', "").ifEmpty { null } else null,
                                        onOpen = {
                                            if (entry.isDirectory) {
                                                if (isRecursiveActive) {
                                                    val segs = entry.path.split('/').filter { it.isNotEmpty() }
                                                    crumbs = homeCrumbs + segs
                                                    filterText = ""
                                                    filterOpen = false
                                                } else {
                                                    crumbs = crumbs + entry.name
                                                }
                                            } else {
                                                openRelative(displayPath, entryFile)
                                            }
                                        },
                                        onMenu = {
                                            menuFor = menuTargetFor(
                                                path = displayPath,
                                                file = entryFile,
                                                fromSession = false,
                                            )
                                        },
                                    )
                                }
                              }
                            }
                        }
                        // 收起了东西就当面说出来、一点就开：文件管理不能让人找不到文件。
                        val foldedAway = if (showIgnored) 0 else entries.count { it.isDirectory && it.name in PI_IGNORED_DIRS }
                        if (foldedAway > 0 && filterText.isBlank()) {
                            item(key = "folded-ignored") {
                                WsCardSlice(index = 0, lastIndex = 0) {
                                    WsRow(
                                        title = tr("已收起 $foldedAway 项（.git / node_modules，pi 的文件发现也不列）", "$foldedAway hidden (.git / node_modules, also excluded by pi file discovery)"),
                                        trail = { Text(tr("显示", "Show"), style = PiTheme.text.meta, color = PiTheme.palette.accent) },
                                        onClick = { showIgnored = true },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "resources-footer") {
                        WsSectionHeader(label = tr(".pi 项目配置", ".pi Project Config"))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProjectConfigQuickCard(
                                title = tr("扩展与资源", "Resources"),
                                subtitle = trustMeta ?: tr(".pi 技能、扩展与信任", "Skills, extensions & trust"),
                                modifier = Modifier.weight(1f),
                                onClick = { session.requestNav(NavRequest.SettingsFocus("hub:resources:project")) },
                            )
                            ProjectConfigQuickCard(
                                title = tr("系统提示词", "System Prompt"),
                                subtitle = tr("AGENTS.md / SYSTEM.md", "AGENTS.md / SYSTEM.md"),
                                modifier = Modifier.weight(1f),
                                onClick = { session.requestNav(NavRequest.SettingsFocus("hub:prompts")) },
                            )
                        }
                    }
                }
            }
        }

        snack?.let { message ->
            WorkspaceSnackBar(
                message = message,
                // **必须让开系统导航条。** 这一段挂在下面那个 `Box` 的直接子级上，
                // 而那个 `Box` **没有**吃 `contentPadding`（吃它的是上面那根页面 Column），
                // 于是 `BottomCenter` 指的是**屏幕**底边 —— 手势条会压住这条提示的下沿
                // （用户看到的就是「底部出现个框，看不清说的啥」，而且它 2600ms 就没了）。
                // 内边距用**同一个** `contentPadding`，页面与这条提示因此永远对齐；再加 8dp
                // 是稿子里那条「提示在底边上方留一线」的间距。
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
            )
        }
    }

    // ------------------------------------------------------------ 浮层：切换工作区
    if (rootSheet) {
        WorkspaceRootSheet(
            entries = workspaceEntries,
            trust = projectTrust,
            onClose = { rootSheet = false },
            // 面板先关，再决定切不切：`switchWorkspace` 的 `Refused` / `Failed` 那句话是
            // VM 推的 notice（主窗口里那一条 snackbar），面板是一层 `Dialog` 窗口，开着就
            // 把它盖住了 —— 先关才看得见。`Ok` 也因此不用再关一次面板。
            onPick = { entry ->
                rootSheet = false
                // 三条分支的顺序是判定的一部分，所以它住在纯函数
                // `WorkspaceChoice.decidePick` 里、由 harness 钉死，而不是这个 lambda 的
                // `when` 顺序里：**先问是不是当前工作区**。理由在那一支的 KDoc 上（回合正跑着时
                // 点当前那一行不该得到「会中断回合」）。
                when (
                    WorkspaceChoice.decidePick(
                        isCurrent = entry.isCurrent,
                        turnRunning = session.wouldInterruptTurn(),
                        available = entry.available,
                    )
                ) {
                    // 目录现在拿不到（SD 卡拔出 / 权限被撤销 / 被别的文件管理器删了）：说实话，
                    // 不动引擎。这一支排在「当前工作区」之前，因为一个读不到的当前工作区会让
                    // 「已经在这个工作区里」变成一句假话 —— 启动时的回退规则早就把引擎挪回默认
                    // 工作区了。
                    WorkspaceChoice.Pick.Unavailable ->
                        say(tr("「${entry.displayName}」现在读不到（可能是卡被拔出、正在卸载，或者权限被撤销了），没有切换。", "\"${entry.displayName}\" is currently unreadable (storage unmounted or permission revoked); did not switch."), StateTone.Warning)

                    WorkspaceChoice.Pick.AlreadyHere -> say(tr("已经在这个项目里。", "Already in this project."))
                    WorkspaceChoice.Pick.ConfirmInterrupt -> workspaceInterrupt = entry
                    WorkspaceChoice.Pick.Switch -> switchWorkspaceTo(entry)
                }
            },
            onNew = {
                rootSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewWorkspace,
                    title = tr("新建项目", "New Project"),
                    where = tr("选择保存位置并输入项目名称", "Choose location and enter a project name"),
                    relativeParent = null,
                    absoluteParent = null,
                    initial = "",
                )
            },
            onPickExternal = {
                rootSheet = false
                externalPicker = true
            },
            onMenu = { entry ->
                rootSheet = false
                workspaceMenuFor = entry
            },
        )
    }

    // ---------------------------------------------- 浮层：从设备目录选择（真目录）
    if (externalPicker) {
        WorkspaceExternalPicker(
            onClose = { externalPicker = false },
            onPick = { path ->
                externalPicker = false
                useExternalDirectory(path)
            },
        )
    }

    // ------------------------------------------------------------ 浮层：工作区行 ⋮
    workspaceMenuFor?.let { entry ->
        WorkspaceRowMenuSheet(
            entry = entry,
            onClose = { workspaceMenuFor = null },
            onRename = {
                workspaceMenuFor = null
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.RenameWorkspace,
                    title = tr("重命名项目", "Rename Project"),
                    where = if (entry.external) entry.host.absolutePath else "~/projects/${entry.name}",
                    relativeParent = null,
                    absoluteParent = null,
                    initial = entry.displayName,
                    workspaceName = entry.name,
                )
            },
            onDelete = {
                workspaceMenuFor = null
                previewWorkspaceDeleteFor(entry)
            },
        )
    }

    // ------------------------------------------------------ 浮层：删除/移除项目的确认
    workspaceDelete?.let { preview ->
        val target = preview.target
        PiDialog(onDismissRequest = { workspaceDelete = null }) {
            PiDialogTitle(
                title = if (target.removesFiles) tr("删除项目", "Delete Project") else tr("从列表移除项目", "Remove Project from List"),
                glyph = "!",
                glyphTone = if (target.removesFiles) PiTheme.palette.error else PiTheme.palette.warning,
            )
            PiDialogBody(target.host.absolutePath, mono = true)
            if (target.removesFiles) {
                PiDialogBody(
                    tr(
                        "这个项目包含 ${target.files} 个文件 / ${target.dirs} 个目录（共 ${WorkspaceFiles.formatSize(target.bytes)}）。删除后无法恢复。",
                        "This project contains ${target.files} ${if (target.files == 1) "file" else "files"} and ${target.dirs} ${if (target.dirs == 1) "folder" else "folders"} (${WorkspaceFiles.formatSize(target.bytes)} total). Deletion is permanent.",
                    ),
                )
            } else {
                PiDialogBody(
                    tr(
                        "这仅将设备目录从项目列表中移除，不会删除或修改手机上的任何文件。",
                        "This only removes the folder from the project list; your files on device storage will not be touched.",
                    ),
                )
            }
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { workspaceDelete = null })
                PiDialogAction(
                    label = if (target.removesFiles) tr("永久删除", "Delete Permanently") else tr("从列表移除", "Remove"),
                    primary = true,
                    tone = if (target.removesFiles) PiTheme.palette.error else null,
                    onClick = {
                        workspaceDelete = null
                        deleteWorkspaceNow(preview)
                    },
                )
            }
        }
    }

    // -------------------------------------------------------- 浮层：中断回合的询问
    workspaceInterrupt?.let { entry ->
        PiDialog(onDismissRequest = { workspaceInterrupt = null }) {
            PiDialogTitle(
                title = tr("切换会中断正在运行的回合", "Switch Will Abort Running Turn"),
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody(tr("即将切换到项目「${entry.displayName}」。", "Switching to project \"${entry.displayName}\"."))
            PiDialogBody(
                tr("切换会停止当前正在进行的模型生成与工具调用，并在新项目中开启空白会话。", "Switching stops active model/tool calls and starts a fresh session in the new project."),
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { workspaceInterrupt = null })
                PiDialogAction(
                    label = tr("仍然切换", "Switch Anyway"),
                    primary = true,
                    onClick = {
                        workspaceInterrupt = null
                        switchWorkspaceTo(entry)
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------ 浮层：行尾 ⋮ 菜单
    menuFor?.let { target ->
        WorkspaceMenuSheet(
            target = target,
            onClose = { menuFor = null },
            onOpen = {
                menuFor = null
                openRelative(target.path, target.file)
            },
            onEdit = {
                menuFor = null
                openRelative(target.path, target.file, startInEdit = true)
            },
            // 两件事都不能占主线程，但要占的段不一样：
            //
            //  - **导出**是阻塞的（读文件 + 写 Download），所以整个调用放到 IO 上；
            //  - **用其他应用打开**自己就是 `suspend` 的，它内部把"读盘定 MIME"放在 IO 上、
            //    回来**在主线程**上 `startActivity`（起界面必须在主线程那一侧）。所以这里
            //    **不要**再套一层 `withContext(IO)` —— 套上就把 startActivity 一起推到后台了。
            //
            // 结果统一走这一屏的提示通道（`say`），语气按结果的 `ok` 选 —— 不去猜句子的头几个字。
            //
            // 文件先落成一个局部量：`menuFor = null` 之后 `target` 仍然可用，但这个 lambda
            // 可能在协程里晚一步跑，钉住它比让读者去推"这时 target 还在不在"好。
            onOpenWithApp = {
                menuFor = null
                val file = target.file
                scope.launch {
                    val result = openWithOtherApp(context, file)
                    say(result.sentence, if (result.ok) StateTone.Muted else StateTone.Warning)
                }
            },
            onShareFile = {
                menuFor = null
                val file = target.file
                scope.launch {
                    val result = shareWorkspaceFile(context, file)
                    say(result.sentence, if (result.ok) StateTone.Muted else StateTone.Warning)
                }
            },
            onDuplicate = {
                menuFor = null
                val source = target.file
                runFileOp(tr("已创建副本 · ${source.name}", "Duplicated · ${source.name}")) {
                    WorkspaceFiles.duplicate(source)
                }
            },
            onExport = {
                menuFor = null
                val file = target.file
                scope.launch {
                    val result = withContext(Dispatchers.IO) { exportWorkspaceFile(context, file) }
                    say(result.sentence, if (result.ok) StateTone.Muted else StateTone.Warning)
                }
            },
            onEnter = {
                menuFor = null
                crumbs = crumbs + target.file.name
            },
            onOpenAsProject = if (target.isDirectory && runCatching { target.file.canWrite() }.getOrDefault(false)) {
                {
                    menuFor = null
                    openDirectoryAsProject(target.file)
                }
            } else {
                null
            },
            onRename = {
                menuFor = null
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.Rename,
                    title = tr("重命名", "Rename"),
                    where = target.path,
                    relativeParent = null,
                    absoluteParent = target.file.parentFile,
                    initial = target.file.name,
                    dialogSub = target.path,
                )
            },
            onDelete = {
                menuFor = null
                deleteFor = target
            },
            onCopyPath = {
                menuFor = null
                val copied = copyToClipboard(context, target.path)
                say(
                    if (copied) tr("已复制 · ${target.path}", "Copied · ${target.path}") else tr("剪贴板不可用，路径：${target.path}", "Clipboard unavailable, path: ${target.path}"),
                    if (copied) StateTone.Muted else StateTone.Warning,
                )
            },
            onMention = {
                menuFor = null
                // pi 的「引用文件」手势就是输入框里的 `@路径`（`PiFileMentions`）：写进草稿再切到对话，
                // 用户接着打字就行。路径是相对项目的，与 `@` 补全给出的同一个写法。
                val token = app.pi.ui.chat.PiFileMentions.buildCompletionValue(
                    target.path + if (target.isDirectory) "/" else "",
                    quoted = false,
                )
                val draft = session.composerDraft.text
                draft.value = if (draft.value.isBlank()) "$token " else draft.value.trimEnd() + " $token "
                session.requestNav(NavRequest.Chat)
            },
        )
    }

    // ------------------------------------------------------------ 浮层：新建（文件 / 文件夹）
    if (newSheet) {
        WorkspaceNewSheet(
            where = whereLabel,
            onClose = { newSheet = false },
            onNewFile = {
                newSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewFile,
                    title = tr("新建文件", "New File"),
                    where = whereLabel,
                    relativeParent = parentRelative,
                    absoluteParent = currentDir,
                    initial = "",
                )
            },
            onNewDir = {
                newSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewDir,
                    title = tr("新建文件夹", "New Folder"),
                    where = whereLabel,
                    relativeParent = parentRelative,
                    absoluteParent = currentDir,
                    initial = "",
                )
            },
            onImportFiles = {
                newSheet = false
                importFilesLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    // ------------------------------------------------------------ 浮层：输入框
    inputDialog?.let { dialog ->
        WorkspaceNameDialog(
            dialog = dialog,
            onClose = { inputDialog = null },
            onPickDeviceLocation = {
                inputDialog = null
                externalPicker = true
            },
            onSubmit = { typed ->
                // 工作区显示名不是文件名：它可以是带空格的一句话，`nameProblem` 那套规矩
                // （不许 `/`、不许 `..`、不许为空…）在这里不适用，交给 `renameWorkspace`
                // 裁决 —— 它的 `Failed.message` 就是给人看的那句话。
                val problem = if (dialog.kind == WorkspaceInputKind.RenameWorkspace ||
                    dialog.kind == WorkspaceInputKind.NewWorkspace
                ) { // 新建项目的名字在对话框里已按目录名规则当场检查过，这里不再重复
                    null
                } else {
                    WorkspaceFiles.nameProblem(typed)
                }
                if (problem == null) {
                    inputDialog = null
                    when (dialog.kind) {
                        WorkspaceInputKind.NewFile -> {
                            val parent = dialog.absoluteParent ?: File(workspace, dialog.relativeParent.orEmpty())
                            val created = File(parent, typed)
                            val display = workspaceDisplayPath(workspace, created)
                            runFileOp(
                                what = tr("已新建文件 · $typed", "Created file · $typed"),
                                onDone = { ok ->
                                    // 稿子：「建完直接进编辑态，写什么由你。」
                                    if (ok) {
                                        openRelative(display, created, startInEdit = true)
                                    }
                                },
                            ) {
                                WorkspaceFiles.createFile(parent, typed)
                            }
                        }

                        WorkspaceInputKind.NewDir -> runFileOp(tr("已新建文件夹 · $typed", "Created folder · $typed")) {
                            WorkspaceFiles.createDir(
                                dialog.absoluteParent ?: File(workspace, dialog.relativeParent.orEmpty()),
                                typed,
                            )
                        }

                        WorkspaceInputKind.Rename -> {
                            val target = File(dialog.absoluteParent ?: workspace, dialog.initial)
                            val renamed = File(target.parentFile, typed)
                            runFileOp(
                                what = tr("已重命名 · $typed", "Renamed · $typed"),
                                onDone = { ok ->
                                    if (ok && viewer?.file?.absolutePath == target.absolutePath) {
                                        viewer = targetFor(
                                            displayPath = workspaceDisplayPath(workspace, renamed),
                                            file = renamed,
                                            editable = true,
                                        )
                                    }
                                },
                            ) { WorkspaceFiles.rename(target, typed) }
                        }

                        WorkspaceInputKind.RenameWorkspace -> dialog.workspaceName?.let { target ->
                            renameWorkspaceTo(target, typed)
                        }

                        WorkspaceInputKind.NewWorkspace -> createNewWorkspace(typed)
                    }
                } else {
                    say(problem, StateTone.Warning)
                }
            },
        )
    }

    // ------------------------------------------------------------ 浮层：删除确认
    deleteFor?.let { target ->
        PiDialog(onDismissRequest = { deleteFor = null }) {
            PiDialogTitle(
                title = if (target.isDirectory) tr("删除文件夹", "Delete Folder") else tr("删除文件", "Delete File"),
                glyph = "!",
                glyphTone = PiTheme.palette.error,
            )
            // 被删的那个路径：等宽（规则 #7）。
            PiDialogBody(target.path, mono = true)
            PiDialogBody(
                tr(
                    "删除「${target.file.name}」？文件会从磁盘永久删除，无法恢复。",
                    "Delete \"${target.file.name}\"? It will be permanently removed from disk and cannot be recovered.",
                ) + if (target.fromSession) {
                    tr(" pi 在本次会话里对它做过的改动不会保留。", " Changes made by pi in this session will not be kept.")
                } else {
                    ""
                },
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { deleteFor = null })
                PiDialogAction(
                    label = tr("永久删除", "Delete Permanently"),
                    primary = true,
                    tone = PiTheme.palette.error,
                    onClick = {
                        val victim = target
                        deleteFor = null
                        runFileOp(
                            what = tr("已删除 · ${victim.file.name}", "Deleted · ${victim.file.name}"),
                            onDone = { ok ->
                                if (ok && viewer?.file?.absolutePath == victim.file.absolutePath) {
                                    viewer = null
                                }
                            },
                        ) {
                            WorkspaceFiles.delete(victim.file)
                        }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------ 浮层：查看器
    viewer?.let { target ->
        WorkspaceViewer(
            target = target,
            onClose = { viewer = null },
            onMessage = { say(it) },
            onSaved = { path ->
                localEdits = localEdits + path
                refreshTick++
            },
            onMention = {
                viewer = null
                val token = app.pi.ui.chat.PiFileMentions.buildCompletionValue(
                    target.relativePath,
                    quoted = false,
                )
                val draft = session.composerDraft.text
                draft.value = if (draft.value.isBlank()) "$token " else draft.value.trimEnd() + " $token "
                session.requestNav(NavRequest.Chat)
            },
            onMore = {
                menuFor = menuTargetFor(
                    path = target.relativePath,
                    file = target.file,
                    fromSession = false,
                )
            },
        )
    }
}

// ================================================================ 工作区路径条

/** 芯片那一条的上下留白。 */
private val WS_CHIP_TOP = 10.dp
private val WS_CHIP_BOTTOM = 6.dp

/** 「文件 / 资源」分段那一条的上下留白。 */


@Composable
private fun FolderGlyph() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}

/**
 * 文件图标。稿子给「读不到改动」那条空态与视图里的文件行都是 `file` 图标
 * （`workspace-final.html:1476`），它说的是「这是一份文件」，不是一个目录。
 */
@Composable
private fun FileGlyph() {
    Icon(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}

/** 稿子空目录块的 `padding:'20px 14px 18px'` + 内部 `marginTop:12` / `:10`。 */
private val WS_EMPTY_DIR_TOP = 20.dp
private val WS_EMPTY_DIR_BOTTOM = 18.dp
private val WS_EMPTY_DIR_INSET = 14.dp
private val WS_EMPTY_DIR_BUTTON_GAP = 12.dp
private val WS_EMPTY_DIR_HINT_GAP = 10.dp

// ================================================================ ② 正在跑

/** One command the session has running, from either of the two places it can be. */
private data class RunningCommand(
    val key: String,
    val command: String,
    /**
     * 行开始的那一刻，用来算**流式中的**读数。
     *
     * `ToolCall.elapsedMs` 是 `endedAt - ts`（`rpc/.../Transcript.kt:89`），调用还在跑时
     * `endedAt` 为 null，所以它**在流式中恒为 null** —— 稿子那种 running 态一定要有点阵与
     * 刻度，不能只靠它。转录里的 `bash` / `powershell` 调用带着自己的 `ts`，于是耗时按
     * **ViewModel 推来的那个粗时钟**算（`UiState.nowMs - ts`，见 [RunningCommandRow]）。
     * **不要在组合里现读表**：`System.currentTimeMillis() - ts` 算出来的是「这一行上一次重组
     * 是什么时候」，一条什么都不打印的命令既没有输出块也没有发布，也就没有重组，读数会冻在
     * 那儿直到有人点一下 —— 这正是 `blocks/ShellBlock` 修掉的那个错，别把它搬回来。
     * `!` / `!!` 那条来自 `BashRun`，它没有时间戳可算，所以那一条仍然没有读数（见
     * [runningCommands]）。
     */
    val startedAt: Long?,
    /** pi 报过的时长（调用结束后才有值）；有值时以它为准，不再算。 */
    val reportedMs: Long?,
    val lines: Int,
    val inContext: Boolean,
) {
    /**
     * 该显示给用户的时长：pi 报过的优先，其次按 [startedAt] 与传进来的读数算。
     *
     * `now` 由 [RunningCommandRow] 从 `UiState.nowMs` 取得，**不是**本帧现读的表。
     */
    fun elapsedMs(now: Long): Long? = reportedMs ?: startedAt?.let { (now - it).coerceAtLeast(0) }
}

/**
 * 段头右侧那句话（稿子 `aside="进入上下文"`）。
 *
 * 稿子只有一条命令，所以那句话是常量；这一屏同时可能有四条，而「进不进上下文」是**每一条
 * 各自的**事实。所以这里只在**全部一致**时才把它提到段头（这时行里不再重复写 —— 稿子的行副行
 * 本来也没有这半句：`:1471` 是 `'运行中 · 已运行 12.3 秒 · ' + RUNNING.out`），不一致时
 * 段头不表态，各行自己说自己的。这样既不会同一信息写两遍，也不会在混着 `!` 与普通 bash 时
 * 出现「段头说进入上下文、行里说不进」那种直接矛盾。
 */
private fun runningAside(runs: List<RunningCommand>): (@Composable () -> Unit)? = when {
    runs.all { it.inContext } -> {
        {
            Text(
                tr("进入上下文", "In context"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    runs.none { it.inContext } -> {
        {
            Text(
                tr("不进上下文", "Excluded from context"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    else -> null
}

/**
 * 正在跑的命令，取自**两个**真实来源：`state.bash`（`!` / `!!` 前缀的 [BashRun]）与转录里
 * `status = Pending` 的 `bash`/`powershell` 工具卡。时长优先用 `ToolCall.elapsedMs`
 * （= `endedAt - ts`，结束后才有），流式中则按行自己的 `ts` 与 **ViewModel 每秒推一次**的
 * `UiState.nowMs` 算（判据 `UiState.hasPendingToolClock` 收的正是这里这批 Pending 行）——
 * 这一屏**不自己读表**，现读出来的只是「上一次重组到现在」。
 */
private fun runningCommands(
    items: List<TranscriptItem>,
    bash: BashRun?,
): List<RunningCommand> {
    val out = mutableListOf<RunningCommand>()
    if (bash != null && bash.running) {
        out += RunningCommand(
            key = "bash",
            command = bash.command,
            // `BashRun`（`ui/PiSessionViewModel.kt`）没有时间戳字段：这一条算不出「已运行
            // 多久」，于是它没有刻度也没有进度段（拿不到就不画，不编一个数）。要补上它需要
            // 给 `BashRun` 加一个 `startedAt`，那个文件不在这一批的边界里。
            startedAt = null,
            reportedMs = null,
            lines = lineCount(bash.output),
            inContext = !bash.excludeFromContext,
        )
    }
    items.forEach { item ->
        if (item is ToolCall &&
            (item.toolName == "bash" || item.toolName == "powershell") &&
            item.status == ToolStatus.Pending
        ) {
            out += RunningCommand(
                key = item.key,
                command = argString(item.args, "command") ?: item.argsSummary,
                startedAt = item.ts,
                reportedMs = item.elapsedMs,
                lines = lineCount(item.output),
                inContext = true,
            )
        }
    }
    return out.take(MAX_RUNNING_ROWS)
}

/** 顶多这么多条——「正在跑」超过四条的时候，它已经不是「正在跑」了。 */
private const val MAX_RUNNING_ROWS = 4

/**
 * 一条正在跑的命令（稿子 ②）：行 + 卡下的 24 段进度条。
 *
 * 多条命令时每一条占**同一张卡的一片**（[WsCardSlice]）：稿子是一个 `Rows` 一张卡带行间
 * 线（`workspace-final.html:1455-1466`），原来每条命令各起一张卡，于是三四条命令会画出三四张
 * 圆角卡片，而它们其实是同一段信息。
 *
 * @param sliceIndex 这一条在 ② 里的序号（决定卡的上/下圆角与上方的行间线）。
 * @param nowMs 那个粗时钟（`UiState.nowMs`）：耗时 = 行的时间戳到 `nowMs`。`null` 时退回
 *   「这一帧现读一次表」，只在时钟首跳还没落地的那一次组合、或调用方没传时钟时发生 —— 它是
 *   回退读数，不是「没有读数」。**改这里时不要把回退当成主路**：直接在这儿
 *   `System.currentTimeMillis()` 会把读数变回「上一次重组」的函数，也就是这一屏原来那个
 *   「安静跑着的命令数字冻住」的错（见 [RunningCommand] 的 KDoc）。
 */
@Composable
private fun RunningCommandRow(
    run: RunningCommand,
    sliceIndex: Int,
    lastSliceIndex: Int,
    nowMs: Long? = null,
    hairlineStart: Dp = PiSettingsMetrics.rowPaddingHorizontal,
) {
    val elapsed = run.elapsedMs(nowMs ?: System.currentTimeMillis())
    Column(Modifier.fillMaxWidth()) {
        WsCardSlice(index = sliceIndex, lastIndex = lastSliceIndex, hairlineStart = hairlineStart) {
            WsRow(
                title = run.command,
                mono = true,
                strong = true,
                lead = {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                        tint = PiTheme.palette.muted,
                    )
                },
                badge = {
                    StateChip(label = tr("运行中", "Running"), tone = StateTone.Warning, glyph = "…")
                },
                // 稿子的副行只有这三个读数（`workspace-final.html:1471`）：`运行中 · 已运行
                // 12.3 秒 · 18 行`。「进入上下文/不进上下文」不在这里 —— 它由段头的 aside 说一次
                // （见 [runningAside]）。
                meta = buildString {
                    append(tr("运行中", "Running"))
                    elapsed?.let { append(tr(" · 已运行 ", " · ")).append(secondsText(it)) }
                    if (run.lines > 0) append(" · ").append(run.lines).append(tr(" 行", if (run.lines == 1) " line" else " lines"))
                },
                metaMono = true,
                trail = {
                    app.pi.ui.theme.DurationMeter(
                        ms = elapsed,
                        color = PiTheme.palette.warning,
                        contentDescription = tr("这条命令已运行 ", "Command running for ") +
                            (elapsed?.let { secondsText(it) } ?: tr("未知", "unknown")),
                    )
                },
            )
        }
        if (elapsed != null) {
            // 进度条在**卡外**，是它的兄弟：稿子把它画在 `Rows` 之后
            // （`workspace-final.html:1467-1475`），`padding:'10px 14px 0'`。
            Row(
                modifier = Modifier.padding(
                    start = WS_RUNNING_METER_INSET,
                    end = WS_RUNNING_METER_INSET,
                    top = WS_RUNNING_METER_TOP,
                ),
                horizontalArrangement = Arrangement.spacedBy(PROGRESS_SEGMENT_GAP),
            ) {
                val filled = progressSegments(elapsed)
                repeat(TOTAL_PROGRESS_SEGMENTS) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(PROGRESS_SEGMENT_HEIGHT)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (index < filled) {
                                    PiTheme.palette.warning
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** 稿子进度条的 `padding:'10px 14px 0'`。 */
private val WS_RUNNING_METER_INSET = 14.dp
private val WS_RUNNING_METER_TOP = 10.dp

/** v2 的进度条是 24 段（`direction-b-v2.html` 的 `Array.from({length:24})`）。 */
private const val TOTAL_PROGRESS_SEGMENTS = 24

/** 段高 3px、段间 3px（v2 的 `height:3` + `gap:3`）。 */
private val PROGRESS_SEGMENT_HEIGHT = 3.dp

/** 段间距，v2 用 `gap:3`。 */
private val PROGRESS_SEGMENT_GAP = 3.dp

/**
 * 已填充的段数：v2 的原型是 12.3 秒填 9 段，即每段约 1.5 秒，这里照这个比例推。
 * 它**不是**进度百分比——pi 不报这个命令跑到哪了——所以注满之后不再动。
 */
private fun progressSegments(elapsedMs: Long): Int =
    (elapsedMs / 1500L).toInt().coerceIn(0, TOTAL_PROGRESS_SEGMENTS)

/** v2 的页脚写 `已运行 12.3 秒`：一位小数 + 「秒」。 */
private fun secondsText(ms: Long): String =
    String.format(Locale.US, tr("%.1f 秒", "%.1fs"), ms / 1000.0)

// ================================================================ ③ 本次会话改过

/**
 * pi 正在写的那个文件（稿子 ③ 里的 pending 行）：`--tool-pending` 底 + 上下各 1px 状态色
 * + 一行说明。
 *
 * 「已收到 24 行」那半句只在 pi 真的流出了行的时候写 —— 给不出就只留「正在写入」，
 * 右侧的刻度也一并撤掉（稿子底部第 3 条自己就是这么要求的）。
 *
 * 卡壳与行间线由调用方的 [WsCardSlice] 提供，所以这里既不自己加页边、也不在末尾收一条
 * hairline（原来那条会把 ③ 的最后一行下面多画一条线，稿子只在行之间画）。
 */
@Composable
private fun PendingWriteRow(pending: WorkspacePendingWrite) {
    var expanded by rememberSaveable(pending.path) { mutableStateOf(false) }
    // 稿子这一块是 `bg:--tool-pending` + `borderTop/Bottom:1px solid rgba(255,255,0,.35)`
    // （`workspace-final.html:1303-1304`）。`rgba(255,255,0,…)` 就是 pi 的 `warning`
    // （深色主题里 `warning = #FFFF00`），`.35` 也正是本 App 的状态描边 alpha
    // （`06 §2`「描边 1px 状态色 35%」），所以不写死颜色字面量。
    val pendingEdge = PiTheme.palette.warning.copy(alpha = 0.35f)
    val palette = PiTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.toolPendingBg)
            .drawBehind {
                val line = PiSettingsMetrics.hairline.toPx()
                drawRect(pendingEdge, size = Size(size.width, line))
                drawRect(
                    pendingEdge,
                    topLeft = Offset(0f, size.height - line),
                    size = Size(size.width, line),
                )
            },
    ) {
        WsRow(
            title = pending.path,
            mono = true,
            strong = true,
            lead = { FileGlyph() },
            meta = null,
            trail = {
                if (pending.receivedLines > 0) {
                    app.pi.ui.theme.DurationMeter(
                        ms = MID_WRITE_TICK_MS,
                        color = palette.warning,
                        contentDescription = tr("pi 正在写入这个文件", "pi is writing this file"),
                    )
                }
            },
            onClick = { expanded = !expanded },
        )
        Row(
            // 稿子的说明行是 `padding:'0 12px 9px'`。
            modifier = Modifier.padding(
                start = PiSettingsMetrics.rowPaddingHorizontal,
                bottom = WS_PENDING_NOTE_BOTTOM,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text("…", style = PiTheme.text.monoSmall, color = palette.warning, maxLines = 1)
            Text(
                buildString {
                    append(
                        if (pending.action == ProjectFileAction.Edited) tr("pi 正在编辑这个文件", "pi is editing this file")
                        else tr("pi 正在写入这个文件", "pi is writing this file"),
                    )
                    if (pending.receivedLines > 0) {
                        append(tr(" · 已收到 ", " · ")).append(pending.receivedLines).append(tr(" 行", if (pending.receivedLines == 1) " line received" else " lines received"))
                    }
                },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Text(
                tr("pi 还在写这个文件；落盘之后这块 diff 才会出现。", "pi is still writing this file; the diff will appear once saved."),
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
                style = PiTheme.text.meta,
                color = palette.muted,
            )
        }
    }
}

/** 稿子 pending 说明行的 `padding:'0 12px 9px'`。 */
private val WS_PENDING_NOTE_BOTTOM = 9.dp

/** 写入中的刻度用它的时长（稿子画的是 `Tick ms={1800}`）。 */
private const val MID_WRITE_TICK_MS = 1_800L

/**
 * 一行「本次会话改过」。
 *
 * 点行**就地展开那块 diff**（`blocks/DiffBlock`，这一批不许改它，所以只调用）；行尾 ⋮
 * 才是打开 / 编辑 / 重命名 / 删除 / 定位到对话。状态是三重编码：`本次会话` 徽标 + `+N −M`
 * 两色 + 符号，颜色从来不是唯一信号。
 *
 * 标题是**叶子名**、目录写进副行 —— 稿子的 `changedRow` 就是
 * `title={baseName(c.p)}` + `meta={目录 + ' · ' + tool + ' · ' + ms + ' · ' + when}`
 * （`workspace-final.html:1300-1309`）。整条内部路径当标题会把行挤成一句没人读完的长串，
 * 而且和 ④ 的目录树读起来是两种东西。
 *
 * 行末**不画** hairline：行间线由调用方的 [WsCardSlice] 在卡内画（稿子只在行**之间**画），
 * 否则这一段最后一行下面会多出一条悬空的线。
 */
@Composable
private fun ChangedFileRow(
    file: ProjectFile,
    diff: ToolDiff?,
    durationMs: Long?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    var expanded by rememberSaveable(file.path) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        WsRow(
            // 文件名无衬线 15/Medium；等宽只留给路径（第二行那段目录）。
            title = changedTitle(file.path),
            mono = false,
            strong = false,
            lead = { FileGlyph() },
            // **没有「本次会话」徽标**：整段就叫「本次会话改过」，每行再说一遍是同一个词
            // 印 N 次 —— 与资源页去掉重复来源徽标同一条规矩。
            meta = buildString {
                // 目录前缀（稿子的 `c.p.split('/').slice(0,-1).join('/') + ' · '`）。pi 的
                // path 可能是 guest 拼法（`ProjectResources.kt:165-166`），所以这里只按分隔符
                // 取，不改写它。
                changedDirectory(file.path)?.let { append(it).append(" · ") }
                append(file.action.label)
                // pi 报过耗时才写它 —— 稿子的副行是 `edit · 214ms · 12 分钟前`。
                durationMs?.takeIf { it > 0 }?.let {
                    append(" · ").append(app.pi.ui.blocks.formatDuration(it))
                }
                append(" · ").append(relativeTime(file.at))
                // 这里原来自造过一句「· 就地看这次 diff / · 收起 diff」：稿子的副行没有它
                // （`workspace-final.html:1303-1305` 只写 `目录 · tool · ms · when`），而
                // 「这点一下会展开」已经由整行可点与 `+N −M` 说出了。删除于本轮审查。
            },
            value = if (file.hasDiff) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap)) {
                        Text(
                            "+${file.added}",
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.toolDiffAdded,
                        )
                        // 稿子的 `diffValue`：只有真的删了行才写 −N，不画 `−0`。
                        if (file.removed > 0) {
                            Text(
                                "−${file.removed}",
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.toolDiffRemoved,
                            )
                        }
                    }
                }
            } else {
                null
            },
            trail = { WsMoreButton(onClick = onMenu) },
            onClick = { if (diff != null) expanded = !expanded else onClick() },
        )
        if (expanded && diff != null) {
            DiffBlock(
                item = diff,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.cardPadding),
                defaultExpanded = true,
            )
        }
    }
}

/** 稿子的 `baseName(c.p)`。 */
private fun changedTitle(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** 稿子把路径的目录那半写进副行；工作区根部没有目录，返回 null。 */
private fun changedDirectory(path: String): String? =
    path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }

/**
 * 这次会话为某个路径留下的 diff，或 null。
 *
 * `edit` 卡与 `diff` 卡在转录里是**两条** item（`rpc/Transcript.kt:1405-1440`），所以这里
 * 按路径把后者找回来；取最后一条：同一路径被改过两次时，用户要看的是最新那次。
 */
private fun diffFor(items: List<TranscriptItem>, path: String): ToolDiff? =
    items.filterIsInstance<ToolDiff>().lastOrNull { it.path == path && it.diffText.isNotBlank() }

/** [ProjectScreen.resolveSession] 的结果：打开谁，以及把它叫什么。 */
private class WorkspaceSessionFile(val file: File, val displayPath: String)

// ================================================================ ④ 全部文件

@Composable
private fun Crumb(text: String, mono: Boolean, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            // 面包屑原来只有字那么高，手指几乎点不中；上下补到约 32dp 的点击高度。
            .padding(vertical = 8.dp)
            // `widthIn(max = …)`，不是 `width(…)`：稿子每一段是 `maxWidth:170`
            // （`workspace-final.html:1500` + `.w-crumb`）：短名字按内容宽排，一行放不下就折行。
            // 定宽 170 让「工作区 / src / session」恒占 534dp，当前目录必然被挤出屏外。
            .widthIn(max = CRUMB_MAX_WIDTH),
        style = (if (mono) PiTheme.text.monoSmall else PiTheme.text.meta).let {
            if (current) it.copy(fontWeight = FontWeight.SemiBold) else it
        },
        color = if (current) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 稿子给每一段面包屑 `maxWidth:170`。 */
private val CRUMB_MAX_WIDTH = 170.dp

/**
 * 面包屑第一段的字：浏览的根是 **guest 的 `/`**（`<rootfs>`），所以它不叫「工作区」——
 * 工作区只是它下面 `workspace/pi/workspaces/<name>` 那一层 —— 所以它不叫「工作区」，
 * 那会把根和根下面的一层混成一个词。
 */
private val ROOTFS_CRUMB_LABEL: String get() = tr("根", "Root")

/**
 * 目录树里的一行：目录在前、文件夹行点进去、文件行点开查看。
 *
 * 本次会话改过的行同样带「本次会话」徽标与 `+N −M` —— 这正是用户点名要的那条
 * （「最近文件的 diff，之前都没有」）：标记复用 ③ 的同一份数据，图标按
 * 文件夹 / 文本 / 二进制分三种（稿子为二进制单加了一个图标态）。
 */
@Composable
private fun WorkspaceEntryRow(
    entry: WorkspaceEntry,
    sessionChanged: String?,
    diff: ToolDiff?,
    subtitlePrefix: String? = null,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    // 最近 10 分钟内动过的行：左缘一条 3dp 强调色细条（卡片圆角会把它裁成贴边的样子）。
    val recent = remember(entry.modifiedAt) {
        System.currentTimeMillis() - entry.modifiedAt in 0..RECENT_ROW_MS
    }
    val barColor = PiTheme.palette.accent
    val iconTintBg = entryIconTint(entry.kind, entry.name)?.copy(alpha = 0.13f)
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (recent) {
                    Modifier.drawBehind {
                        drawRect(
                            barColor,
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        WsRow(
            title = WorkspaceFiles.middleEllipsis(entry.name, ENTRY_NAME_MAX_CHARS),
            mono = false,
            strong = false,
            lead = { EntryGlyph(entry.kind, entry.name) },
            leadBackground = iconTintBg,
            badge = if (sessionChanged != null) {
                {
                    WsBadge(text = tr("本次会话", "This session"), tone = StateTone.Accent, glyph = "●")
                }
            } else {
                null
            },
            meta = buildString {
                if (!subtitlePrefix.isNullOrEmpty()) append(subtitlePrefix).append(" · ")
                if (entry.isDirectory) {
                    append(tr("${entry.childCount}项 · ${relativeTime(entry.modifiedAt)}", "${entry.childCount} ${if (entry.childCount == 1) "item" else "items"} · ${relativeTime(entry.modifiedAt)}"))
                } else {
                    append(relativeTime(entry.modifiedAt))
                }
            },
            value = if (diff != null && (diff.added > 0 || diff.removed > 0)) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap)) {
                        Text(
                            "+${diff.added}",
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.toolDiffAdded,
                        )
                        // 稿子的 `diffValue`：只有真的删了行才写 −N，不画 `−0`。
                        if (diff.removed > 0) {
                            Text(
                                "−${diff.removed}",
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.toolDiffRemoved,
                            )
                        }
                    }
                }
            } else if (entry.isDirectory) {
                // 目录行尾的 ›：告诉人「点一下是走进去」，与文件行（点一下是打开）区分。
                {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = PiTheme.palette.muted,
                    )
                }
            } else {
                {
                    Text(
                        WorkspaceFiles.formatSize(entry.sizeBytes),
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.muted,
                        maxLines = 1,
                    )
                }
            },
            trail = { WsMoreButton(onClick = onMenu) },
            onClick = onOpen,
        )
    }
}

/** 文件列表的排序方式。除「最近修改」外，目录排在文件前面。 */
/** pi 的文件发现（`@` 补全、`find`）默认排除的目录；其余点开头的项 pi 照列。 */
private val PI_IGNORED_DIRS: Set<String> = setOf(".git", "node_modules")

private enum class WorkspaceSort(comparatorOf: () -> Comparator<WorkspaceEntry>) {
    Name({ compareBy<WorkspaceEntry>({ !it.isDirectory }, { it.name.lowercase(java.util.Locale.ROOT) }) }),
    // 最近修改：目录和文件混排，真正最近动过的在最上面（默认）。
    Newest({ compareByDescending<WorkspaceEntry> { it.modifiedAt }.thenBy { it.name.lowercase(java.util.Locale.ROOT) } }),
    Largest({ compareBy<WorkspaceEntry> { !it.isDirectory }.thenByDescending { it.sizeBytes } }),
    Type({
        compareBy<WorkspaceEntry>(
            { !it.isDirectory },
            { it.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) },
            { it.name.lowercase(java.util.Locale.ROOT) },
        )
    }),
    ;

    val label: String
        get() = when (this) {
            Name -> tr("名称", "Name")
            Newest -> tr("最近修改", "Modified")
            Largest -> tr("大小", "Size")
            Type -> tr("类型", "Type")
        }

    val comparator: Comparator<WorkspaceEntry> by lazy(comparatorOf)
}


@Composable
private fun WorkspaceFilterField(
    value: String,
    onChange: (String) -> Unit,
    wholeProject: Boolean? = null,
    onToggleWholeProject: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = 6.dp,
            )
            .height(44.dp)
            .clip(RoundedCornerShape(WsShape.medium))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = if (wholeProject != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = palette.muted,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = PiTheme.text.mono.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            if (wholeProject == true) {
                                tr("全项目递归搜索文件名…", "Search all project files…")
                            } else {
                                tr("筛选这一层的文件名", "Filter files in this folder")
                            },
                            style = PiTheme.text.mono,
                            color = palette.muted,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = tr("清除筛选", "Clear filter"),
                    modifier = Modifier.size(16.dp),
                    tint = palette.muted,
                )
            }
        }
        if (wholeProject != null && onToggleWholeProject != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (wholeProject) palette.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onToggleWholeProject)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (wholeProject) tr("全项目", "Project") else tr("当前层", "Folder"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = if (wholeProject) palette.accent else palette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 「最近动过」的判据：10 分钟。 */
private const val RECENT_ROW_MS = 10 * 60 * 1000L

/**
 * 路径条：工作区药丸 + 工作区内的子路径，横向可滚，进到深处时自动滚到当前层。
 * 工作区之外（根目录下别处）则是药丸 + 「根 / a / b」整条路径，点药丸回工作区。
 */
@Composable
private fun WorkspaceHeroCard(
    workspaceName: String,
    relativePath: String,
    isExternal: Boolean,
    trustLabel: String?,
    trustTrusted: Boolean?,
    changedCount: Int,
    runningCount: Int,
    onSwitchProject: () -> Unit,
    onNewProject: () -> Unit,
    onOpenTrust: () -> Unit,
) {
    val palette = PiTheme.palette
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 6.dp)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, palette.borderMuted.copy(alpha = 0.55f), cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = workspaceName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    WsBadge(
                        text = if (isExternal) tr("设备存储", "Device") else tr("应用沙盒", "Sandbox"),
                        tone = StateTone.Muted,
                        dot = false,
                    )
                }
                Text(
                    text = relativePath,
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trustLabel != null) {
                val trustTone = when (trustTrusted) {
                    true -> StateTone.Success
                    false -> StateTone.Error
                    null -> StateTone.Warning
                }
                Box(modifier = Modifier.clickable(onClick = onOpenTrust)) {
                    WsBadge(text = trustLabel, tone = trustTone)
                }
            }
        }
        if (changedCount > 0 || runningCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (runningCount > 0) {
                    WsBadge(text = tr("$runningCount 条命令运行中", if (runningCount == 1) "1 command running" else "$runningCount commands running"), tone = StateTone.Warning)
                }
                if (changedCount > 0) {
                    WsBadge(text = tr("本次改动 $changedCount 项", if (changedCount == 1) "1 change" else "$changedCount changes"), tone = StateTone.Accent)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroActionPill(
                icon = Icons.Filled.SwapHoriz,
                label = tr("切换项目", "Switch Project"),
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = onSwitchProject,
            )
            HeroActionPill(
                icon = Icons.Filled.Add,
                label = tr("新建项目", "New Project"),
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onNewProject,
            )
        }
    }
}

@Composable
private fun ProjectConfigQuickCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, palette.borderMuted.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = PiType.rowTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (primary) palette.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (primary) palette.accent.copy(alpha = 0.35f) else palette.borderMuted.copy(alpha = 0.45f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (primary) palette.accent else palette.text,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (primary) palette.accent else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspacePathBar(
    workspaceName: String,
    inside: Boolean,
    homeSize: Int,
    crumbs: List<String>,
    rootLabel: String,
    canSetAsProject: Boolean,
    onHome: () -> Unit,
    onGo: (Int) -> Unit,
    onSetAsProject: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) {
        delay(60)
        scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = 4.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 上层目录跳转按钮（任何层都可用，随时向上浏览到整个容器或设备目录）。
            if (crumbs.isNotEmpty()) {
                var upMenu by remember { mutableStateOf(false) }
                Box {
                    PathBarIconButton(Icons.Filled.ArrowUpward, tr("上层目录", "Parent directories"), { upMenu = true })
                    if (upMenu) PiMenu(
                        expanded = true,
                        onDismiss = { upMenu = false },
                        placement = PiMenuPlacement.Below,
                        items = (crumbs.size - 1 downTo 0).map { keep ->
                            val label = if (keep == 0) rootLabel else crumbs[keep - 1]
                            PiMenuItem(if (keep == crumbs.size - 1) tr("$label（上一级）", "$label (Up)") else label) {
                                upMenu = false
                                onGo(keep)
                            }
                        },
                    )
                }
            }
            val atRoot = inside && crumbs.size == homeSize
            // 回到当前项目根目录
            if (!atRoot) {
                PathBarIconButton(Icons.Filled.Home, tr("回到项目根目录", "Back to project root"), onHome)
            }
            val sep: @Composable () -> Unit = {
                Text(
                    "›",
                    modifier = Modifier.padding(horizontal = 5.dp),
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.muted,
                    maxLines = 1,
                )
            }
            if (inside) {
                Crumb(
                    text = workspaceName,
                    mono = false,
                    current = atRoot,
                    onClick = onHome,
                )
                crumbs.drop(homeSize).forEachIndexed { i, segment ->
                    sep()
                    Crumb(
                        text = segment,
                        mono = true,
                        current = homeSize + i == crumbs.lastIndex,
                        onClick = { onGo(homeSize + i + 1) },
                    )
                }
            } else {
                Crumb(text = rootLabel, mono = true, current = crumbs.isEmpty(), onClick = { onGo(0) })
                crumbs.forEachIndexed { i, segment ->
                    sep()
                    Crumb(
                        text = segment,
                        mono = true,
                        current = i == crumbs.lastIndex,
                        onClick = { onGo(i + 1) },
                    )
                }
            }
        }
        // 浏览到任意其他目录时，一键将其设为当前 Pi 项目（cwd）
        if (canSetAsProject) {
            WsChip(
                text = tr("设为项目", "Use as CWD"),
                solid = true,
                onClick = onSetAsProject,
            )
        }
    }
}

@Composable
private fun PathBarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp), tint = PiTheme.palette.muted)
    }
}

/** 目录名太长时中间省略（与面包屑同一条规则）。 */
private const val ENTRY_NAME_MAX_CHARS = 28

/**
 * 四种图标：文件夹 / 文本（含 HTML）/ 图片 / 二进制。稿子为二进制单加了一个图标态。
 *
 * 图片借的是 Material 自己的 `Image` 笔画：设计稿那套图标里没有图片态（它只有
 * folder/file/bin 三种文件图标），而一张图在列表里和一份文本长得一模一样，就等于又把
 * 「能渲染」这件事藏回「文件」里 —— 这一批修的就是它。
 */
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "ts", "tsx", "js", "mjs", "cjs", "jsx", "py", "rs", "go",
    "c", "h", "cpp", "hpp", "cc", "sh", "bash", "zsh", "css", "scss", "sql", "rb", "php", "swift", "dart",
)
private val CONFIG_EXTS = setOf("json", "jsonc", "yaml", "yml", "toml", "xml", "properties", "gradle", "env", "ini", "lock")
private val DOC_EXTS = setOf("md", "markdown", "mdx", "txt", "rst")

@Composable
private fun entryIconTint(kind: WorkspaceEntryKind, name: String): androidx.compose.ui.graphics.Color? {
    val palette = PiTheme.palette
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return when {
        kind == WorkspaceEntryKind.Directory -> palette.accent
        kind == WorkspaceEntryKind.Image -> palette.accent
        kind == WorkspaceEntryKind.Html || ext == "svg" -> palette.mdLink
        kind == WorkspaceEntryKind.Binary -> null
        ext in DOC_EXTS -> palette.success
        ext in CONFIG_EXTS -> palette.warning
        ext in CODE_EXTS -> palette.mdLink
        else -> null
    }
}

@Composable
private fun EntryGlyph(kind: WorkspaceEntryKind, name: String = "") {
    val palette = PiTheme.palette
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    val tint = entryIconTint(kind, name) ?: palette.muted
    when {
        kind == WorkspaceEntryKind.Directory -> Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Binary -> Icon(
            imageVector = WsBinaryFileGlyph,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Image -> Icon(
            Icons.Filled.Image,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Html || ext == "svg" -> Icon(
            Icons.Filled.Language,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in DOC_EXTS -> Icon(
            Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in CONFIG_EXTS -> Icon(
            Icons.Filled.DataObject,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in CODE_EXTS -> Icon(
            Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        else -> Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )
    }
}

// ================================================================ 浮层：切换工作区

/**
 * 切换工作区：这一屏的「其它底部面板」之一，用同一支自绘的 [WsSheet]。
 *
 * 头与页脚的归属照稿子（`workspace-final.html:1577-1608`）：副标题是那句「工作区就是
 * pi 的现场目录」，正文末尾那句「新建的工作区落在 App 私有目录里……」留在滚动区里，
 * 而「换工作区等于换一个现场」是**页脚**（`p.footer`），钉在面板底部不随内容滚。
 * 正文上限是稿子给这一处的 `maxBody={520}`（默认那档是 420）。
 *
 * ## 清单来自引擎，不是兄弟目录
 *
 * [entries] 是 `session.workspaceEntries()` 的读数（`WorkspaceStore.Entry`）：`displayName`
 * 是显示名（没改过就是目录名）、`isCurrent` 是当前那一格。稿子的行副行是
 * 「N 个文件 · 最后打开 X」—— 那两个数**这一版没有**：目录的 mtime 只说明里面有东西被
 * 加/删过（改内容不动它），而完整数一遍要遍历整棵树，引擎只在**删除前的统计**
 * （`previewWorkspaceDelete`）里提供 —— 那也正是需要那个数的地方。副行改印工作区
 * 相对 files 目录的路径（`pi/workspaces/workspace-1`），与副标题那句「它就在 App
 * 私有目录里」对得上。这是与稿子的一处**有意偏离**。
 *
 * ## 行尾 ⋮
 *
 * 稿子的每一行只有「当前」徽标与一次整行点击。重命名与删除是引擎侧（`renameWorkspace`
 * / `previewWorkspaceDelete`）新开的两条路，这一版给每行加了一颗 ⋮（[WsMoreButton]，
 * 与 ③④ 的文件行同一个构件、同一支菜单面板），点击整行仍然是切换。这是第二处**有意
 * 偏离**：行里并排两个按钮比一颗 ⋮ 更挤，也不像这一屏别处的做法。
 */
@Composable
private fun WorkspaceRootSheet(
    entries: List<WorkspaceStore.Entry>,
    /** guest 路径 → 信任结论（`true` 信任 / `false` 不信任 / `null` 待表态）；没有这一项 = 不知道，不画徽标。 */
    trust: Map<String, Boolean?>,
    onClose: () -> Unit,
    onPick: (WorkspaceStore.Entry) -> Unit,
    onNew: () -> Unit,
    onPickExternal: () -> Unit,
    onMenu: (WorkspaceStore.Entry) -> Unit,
) {
    WsSheet(
        title = tr("切换项目", "Switch Project"),
        onClose = onClose,
        subtitle = tr("项目就是一个目录：App 私有目录和设备目录里的都可以。", "A project is just a directory — either app-private or on your device."),
        footer = tr("换项目会新建一个 pi 会话，当前会话不会被删除。项目信任按目录记在 pi 的信任库里。", "Switching starts a new pi session without deleting the current one. Project trust is tracked per directory in pi's trust store."),
        maxBodyHeight = ROOT_SHEET_BODY_MAX,
    ) {
        var projectQuery by remember { mutableStateOf("") }
        val filteredEntries = remember(entries, projectQuery) {
            val q = projectQuery.trim()
            if (q.isEmpty()) entries
            else entries.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.relative.contains(q, ignoreCase = true)
            }
        }
        WsCard {
            WsRow(
                title = tr("新建项目", "New Project"),
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                        tint = PiTheme.palette.accent,
                    )
                },
                meta = tr("在应用沙盒或手机存储中创建新项目目录。", "Create a new project folder in sandbox or device storage."),
                onClick = onNew,
            )
            WsHairline(startInset = WsIconHairlineStart)
            WsRow(
                title = tr("打开设备文件夹", "Open Device Folder"),
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = { FolderGlyph() },
                meta = tr("直接打开或新建手机存储里的目录，不复制文件。", "Open or create a folder on device storage directly."),
                onClick = onPickExternal,
            )
        }
        if (entries.size >= 5) {
            WorkspaceFilterField(
                value = projectQuery,
                onChange = { projectQuery = it },
            )
        }

        // 一行项目：当前与其它共用同一份画法。
        val entryRow: @Composable (WorkspaceStore.Entry) -> Unit = { item ->
            val pathMeta = if (item.external) item.host.absolutePath else "~/projects/${item.name}"
            WsRow(
                title = item.displayName,
                mono = false,
                strong = true,
                lead = { FolderGlyph() },
                leadBackground = if (item.isCurrent) PiTheme.palette.accent.copy(alpha = 0.14f) else null,
                badge = if (item.isCurrent || item.external || !item.available || (item.guestPath in trust && trust[item.guestPath] != true)) {
                    {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
                        ) {
                            if (item.external) {
                                WsBadge(text = tr("设备", "Device"), tone = StateTone.Muted, dot = false)
                            }
                            if (!item.available) {
                                WsBadge(text = tr("不可读", "Unreadable"), tone = StateTone.Warning)
                            }
                            if (item.guestPath in trust) {
                                when (trust[item.guestPath]) {
                                    false -> WsBadge(text = tr("未信任", "Untrusted"), tone = StateTone.Warning)
                                    null -> WsBadge(text = tr("待表态", "Pending"), tone = StateTone.Warning)
                                    true -> Unit
                                }
                            }
                            if (item.isCurrent) {
                                WsBadge(text = tr("当前", "Current"), tone = StateTone.Accent, glyph = "●")
                            }
                        }
                    }
                } else {
                    null
                },
                meta = pathMeta,
                metaMono = true,
                trail = { WsMoreButton(onClick = { onMenu(item) }) },
                onClick = { onPick(item) },
            )
        }
        // 「当前」与「其它」分成两段：当前那一个单独成卡、不再混在列表里靠颜色认。
        val current = remember(filteredEntries) { filteredEntries.firstOrNull { it.isCurrent } }
        val others = remember(filteredEntries) { filteredEntries.filter { !it.isCurrent } }
        if (current != null) {
            WsSectionHeader(label = tr("当前", "Current"))
            WsCard { entryRow(current) }
        }
        if (others.isNotEmpty()) {
            WsSectionHeader(label = tr("其它项目", "Other Projects"), count = "${others.size}")
            WsCard {
                others.forEachIndexed { index, item ->
                    if (index > 0) WsHairline(startInset = WsIconHairlineStart)
                    entryRow(item)
                }
            }
        }
        if (current == null && others.isEmpty() && projectQuery.isNotBlank()) {
            Text(
                tr("没有匹配「${projectQuery.trim()}」的项目。", "No projects matching \"${projectQuery.trim()}\"."),
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        Text(
            tr("设备目录不会被复制或移动；取消登记只是不再列在这里，文件留在设备上。设备目录需要「所有文件访问」授权。", "Device folders are never copied or moved; unregistering only removes them from this list while keeping files on your device. Device folders require \"All files access\"."),
            modifier = Modifier.padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPaddingLoose,
                bottom = PiSettingsMetrics.groupGap,
            ),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}

/** 稿子给这一处的 `maxBody={520}`（`Sheet` 的默认那档是 420）。 */
private val ROOT_SHEET_BODY_MAX = 520.dp

/**
 * 工作区行尾 ⋮ 的菜单：重命名 / 删除。
 *
 * 与文件行的 [WorkspaceMenuSheet] 同一副壳、同一支 [MenuAction]：标题是显示名，副标题是
 * **等宽**的相对路径（这个对象是路径型的，与那支菜单的规矩一致），删除用错误色（它是唯一
 * 不可逆的那一个）。不在这里放「切换」—— 整行点击就是切换。
 *
 * 稿子的 rootsheet 每一行只有一个「当前」徽标和一次整行点击，没有行级动作；这两条是引擎
 * 接口带来的新入口（见 [WorkspaceRootSheet] 的 KDoc）。
 */
@Composable
private fun WorkspaceRowMenuSheet(
    entry: WorkspaceStore.Entry,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    WsSheet(
        title = entry.displayName,
        onClose = onClose,
        subtitle = if (entry.external) entry.host.absolutePath else "~/projects/${entry.name}",
        subtitleMono = true,
    ) {
        MenuAction(
            tr("重命名显示名", "Rename"),
            onRename,
            icon = Icons.Filled.DriveFileRenameOutline,
        )
        WsHairline()
        MenuAction(
            if (entry.external) tr("从列表移除", "Remove from list") else tr("删除项目", "Delete project"),
            onDelete,
            tone = PiTheme.palette.error,
            icon = Icons.Filled.Delete,
        )
    }
}

// ================================================================ 浮层：文件菜单

/** 行尾 ⋮ 的目标。 */
private data class WorkspaceMenuTarget(
    val path: String,
    val file: File,
    val isDirectory: Boolean,
    /**
     * 这个条目是哪种文件（`WorkspaceFiles.kindOf`）。
     *
     * 菜单要它只为一件事：给不给「编辑」（判据 `WorkspaceFiles.editableAsText`）。以前每个
     * 文件行都画一个「编辑」，点开一张图/一个 `.so` 却是空的编辑态 —— 保存下去就是拿 UTF-8
     * 文本覆盖那些字节。
     */
    val kind: WorkspaceEntryKind,
    /** 这一行是从 ③「本次会话改过」点进来的（保留给以后区分来路用）。 */
    val fromSession: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

private fun menuTargetFor(path: String, file: File, fromSession: Boolean): WorkspaceMenuTarget =
    WorkspaceMenuTarget(
        path = path,
        file = file,
        isDirectory = file.isDirectory,
        // 与目录树/查看器同一个判据（`WorkspaceFiles.kindOf`），菜单据此决定给不给「编辑」。
        kind = if (file.isDirectory) WorkspaceEntryKind.Directory else WorkspaceFiles.kindOf(file),
        fromSession = fromSession,
        sizeBytes = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified(),
    )

/**
 * 行尾 ⋮ 的底部菜单。
 *
 * 稿子的动作表：③ 的行与 ④ 一样，④ 的文件是
 * 「打开 / 编辑 / 重命名 / 删除 / 复制路径」，④ 的目录是「进入 / 重命名 / 删除 / 复制路径」。
 * 删除用错误色（它是唯一不可逆的那一个）。
 *
 * ## 「用其他应用打开」与「导出到 Download」是这张菜单加的两条（稿子的动作表里没有）
 *
 * 放在这里而不是查看器的正文里，正因为这张 sheet 是**两个入口共用**的：文件列表行的 ⋮，
 * 以及查看器的 ⋮（查看器的 `onMore` 落回这一屏的 `menuFor`）。加一次，文本、代码、markdown、
 * HTML、空文件、超大、图片、二进制 —— **每一种**都拿得到这两条路；各支正文再各画一遍就会有
 * 七八个说法，第一个改的人只会改自己看见的那一个。
 *
 * 两条都只对**文件**存在（目录没有"用别的应用打开"这回事）。这两个 lambda 的实现都在
 * `WorkspaceViewer.kt` 里（`openWithOtherApp` / `exportWorkspaceFile`）—— 查看器二进制那一支
 * 用的是同一对函数，所以两条入口的行为不可能漂移。「导出到 Download」也是真的一条路：
 * 「用其他应用打开」在没人能接时说的那句「先导出到 Download」，指的就是同一张菜单里的这一条。
 *
 * 壳是 [WsSheet]：标题是 `mid(文件名, 22)`（稿子 `:1613`），副标题是**等宽**的完整路径
 * （稿子那儿是一个 `span.mono`），动作行自己带 `10px 14px` 的内边距（见 [MenuAction]），
 * 所以头下面不再垫一个空档 —— 头的 8 加上行的 10 就是面板头到第一条动作的那道缝。
 */
@Composable
private fun WorkspaceMenuSheet(
    target: WorkspaceMenuTarget,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onOpenWithApp: () -> Unit,
    onShareFile: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onEnter: () -> Unit,
    onOpenAsProject: (() -> Unit)? = null,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onMention: () -> Unit,
) {
    val palette = PiTheme.palette
    WsSheet(
        title = WorkspaceFiles.middleEllipsis(target.file.name, MENU_TITLE_MAX_CHARS),
        onClose = onClose,
        subtitle = target.path,
        subtitleMono = true,
    ) {
        if (target.isDirectory) {
            MenuAction(tr("进入文件夹", "Open Folder"), onEnter, icon = Icons.Filled.FolderOpen)
            if (onOpenAsProject != null) {
                MenuAction(
                    tr("设为当前项目（切换工作目录）", "Open as Active Project (cwd)"),
                    onOpenAsProject,
                    tone = palette.accent,
                    icon = Icons.Filled.SwapHoriz,
                )
            }
            MenuAction(tr("在对话里引用（@）", "Mention in chat (@)"), onMention, icon = Icons.Filled.AlternateEmail)
            WsHairline()
            MenuAction(tr("复制副本", "Duplicate"), onDuplicate, icon = Icons.Filled.FileCopy)
            MenuAction(tr("重命名", "Rename"), onRename, icon = Icons.Filled.DriveFileRenameOutline)
            MenuAction(tr("复制路径", "Copy path"), onCopyPath, icon = Icons.Filled.ContentCopy)
            WsHairline()
            MenuAction(tr("删除文件夹", "Delete folder"), onDelete, tone = palette.error, icon = Icons.Filled.Delete)
        } else {
            MenuAction(tr("打开查看", "Open"), onOpen, icon = Icons.Filled.Visibility)
            if (WorkspaceFiles.editableAsText(target.kind)) {
                MenuAction(tr("编辑", "Edit"), onEdit, icon = Icons.Filled.Edit)
            }
            MenuAction(tr("在对话里引用（@）", "Mention in chat (@)"), onMention, tone = palette.accent, icon = Icons.Filled.AlternateEmail)
            WsHairline()
            MenuAction(tr("分享文件", "Share file"), onShareFile, icon = Icons.Filled.Share)
            MenuAction(tr("用其他应用打开", "Open with another app"), onOpenWithApp, icon = Icons.Filled.OpenInNew)
            MenuAction(tr("导出到 Download", "Export to Downloads"), onExport, icon = Icons.Filled.Download)
            WsHairline()
            MenuAction(tr("复制副本", "Duplicate"), onDuplicate, icon = Icons.Filled.FileCopy)
            MenuAction(tr("重命名", "Rename"), onRename, icon = Icons.Filled.DriveFileRenameOutline)
            MenuAction(tr("复制路径", "Copy path"), onCopyPath, icon = Icons.Filled.ContentCopy)
            WsHairline()
            MenuAction(tr("删除文件", "Delete file"), onDelete, tone = palette.error, icon = Icons.Filled.Delete)
        }
    }
}

/** 稿子给这个标题的 `mid(…, 22)`。 */
private const val MENU_TITLE_MAX_CHARS = 22

@Composable
private fun MenuAction(
    label: String,
    onClick: () -> Unit,
    tone: androidx.compose.ui.graphics.Color? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val fg = tone ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = tone ?: PiTheme.palette.muted,
            )
        }
        Text(
            label,
            style = PiType.rowTitle,
            color = fg,
        )
    }
}

// ================================================================ 浮层：新建

/**
 * 「新建」先问文件还是文件夹（稿子的 `dlg.kind === 'new'`，`:1623`）：同一支 [WsSheet]，
 * 标题「新建」、副标题「建在「…」里」、两条选择各自带 `10px 14px` 内边距（见 [NewChoice]）。
 */
@Composable
private fun WorkspaceNewSheet(
    where: String,
    onClose: () -> Unit,
    onNewFile: () -> Unit,
    onNewDir: () -> Unit,
    onImportFiles: () -> Unit,
) {
    WsSheet(
        title = tr("新建与导入", "New & Import"),
        onClose = onClose,
        subtitle = tr("当前目录：$where", "Directory: $where"),
    ) {
        NewChoice(
            title = tr("新建文件", "New File"),
            supporting = tr("建完直接进编辑态，写什么由你。", "Opens directly in the editor after creation."),
            glyph = { EntryGlyph(WorkspaceEntryKind.Text) },
            onClick = onNewFile,
        )
        WsHairline()
        NewChoice(
            title = tr("新建文件夹", "New Folder"),
            supporting = tr("放在当前目录下。", "Created in the current directory."),
            glyph = { EntryGlyph(WorkspaceEntryKind.Directory) },
            onClick = onNewDir,
        )
        WsHairline()
        NewChoice(
            title = tr("从手机导入文件", "Import Files from Device"),
            supporting = tr("支持多选任意文档、图片或代码，自动防重名。", "Select one or more files from your phone; auto-renames on conflict."),
            glyph = {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                    tint = PiTheme.palette.accent,
                )
            },
            onClick = onImportFiles,
        )
    }
}

@Composable
private fun NewChoice(
    title: String,
    supporting: String,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.searchIconGap),
    ) {
        glyph()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                // 与 `MenuAction` / 设置面的行标题同一个角色：`PiType.rowTitle`（15/23 Medium）。
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                supporting,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        Text("›", style = PiTheme.text.mono, color = PiTheme.palette.muted, maxLines = 1)
    }
}

// ================================================================ 浮层：名字输入

private enum class WorkspaceInputKind { NewFile, NewDir, Rename, RenameWorkspace, NewWorkspace }

/** 新建文件 / 新建文件夹 / 重命名 / 重命名工作区共用的一支输入框（稿子就是这么复用的）。 */
private data class WorkspaceInputDialog(
    val kind: WorkspaceInputKind,
    val title: String,
    /** 副行的「建在哪里」文案。 */
    val where: String,
    /** 相对工作区的父目录；只有新建文件 / 文件夹用它。 */
    val relativeParent: String?,
    /** 绝对父目录；文件重命名用它。 */
    val absoluteParent: File?,
    val initial: String,
    val dialogSub: String? = null,
    /** 要改显示名的那个工作区（目录名 = identity）；只有重命名工作区用它。 */
    val workspaceName: String? = null,
)

@Composable
private fun WorkspaceNameDialog(
    dialog: WorkspaceInputDialog,
    onClose: () -> Unit,
    onPickDeviceLocation: () -> Unit = {},
    onSubmit: (String) -> Unit,
) {
    var field by remember(dialog) {
        val dot = dialog.initial.lastIndexOf('.')
        val end = if (dialog.kind == WorkspaceInputKind.Rename && dot > 0) dot else dialog.initial.length
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                dialog.initial,
                if (dialog.kind == WorkspaceInputKind.RenameWorkspace || dialog.kind == WorkspaceInputKind.Rename) {
                    androidx.compose.ui.text.TextRange(0, end)
                } else {
                    androidx.compose.ui.text.TextRange(dialog.initial.length)
                },
            ),
        )
    }
    var newInDevice by remember(dialog) { mutableStateOf(false) }
    val name = field.text
    val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
    if (!(dialog.kind == WorkspaceInputKind.NewWorkspace && newInDevice)) {
        PiAutoFocus(focus)
    }
    PiDialog(onDismissRequest = onClose) {
        PiDialogTitle(title = dialog.title)
        if (dialog.kind == WorkspaceInputKind.NewWorkspace) {
            val palette = PiTheme.palette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    false to tr("应用沙盒（高速）", "App Sandbox (Fast)"),
                    true to tr("设备存储（自选位置）", "Device Storage"),
                ).forEach { (isDevice, label) ->
                    val selected = newInDevice == isDevice
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) palette.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { newInDevice = isDevice }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (selected) palette.accent else palette.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            PiDialogBody(dialog.dialogSub ?: dialog.where, mono = true)
        }
        if (dialog.kind == WorkspaceInputKind.NewWorkspace && newInDevice) {
            Text(
                text = tr(
                    "进入手机存储浏览器后，可导航到任意位置并点击「新建文件夹」创建项目，或直接将已有文件夹设为项目。",
                    "Browse to any location on your device, tap \"New Folder\" to create a project there, or use an existing folder.",
                ),
                modifier = Modifier.padding(top = 12.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onClose)
                PiDialogAction(
                    label = tr("浏览设备目录", "Browse Device"),
                    primary = true,
                    onClick = onPickDeviceLocation,
                )
            }
        } else {
        val trimmed = name.trim()
        val problem = when (dialog.kind) {
            WorkspaceInputKind.RenameWorkspace -> if (trimmed.isEmpty()) tr("名字不能为空。", "Name cannot be empty.") else null
            WorkspaceInputKind.NewWorkspace ->
                if (trimmed.isEmpty()) null
                else WorkspaceChoice.nameProblem(trimmed)
                    ?: if (java.io.File(WorkspaceStore.root(LocalContext.current), trimmed).exists()) tr("已经有叫「$trimmed」的项目了。", "A project named \"$trimmed\" already exists.") else null
            else -> WorkspaceFiles.nameProblem(name) ?: run {
                val parent = dialog.absoluteParent
                if (parent != null && trimmed.isNotEmpty() &&
                    !(dialog.kind == WorkspaceInputKind.Rename && trimmed == dialog.initial) &&
                    java.io.File(parent, trimmed).exists()
                ) {
                    tr("这里已经有叫「$trimmed」的文件或文件夹了。", "A file or folder named \"$trimmed\" already exists here.")
                } else {
                    null
                }
            }
        }
        val canSubmit = trimmed.isNotEmpty() && problem == null
        val showProblem = problem != null && name.isNotEmpty()
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = PiSettingsMetrics.cardPadding)
                .height(NAME_FIELD_HEIGHT)
                .clip(RoundedCornerShape(NAME_FIELD_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    PiSettingsMetrics.hairline,
                    if (showProblem) PiTheme.palette.error else PiTheme.palette.borderAccent,
                    RoundedCornerShape(NAME_FIELD_RADIUS),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (canSubmit) onSubmit(trimmed) },
                ),
                textStyle = PiTheme.text.mono.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PiTheme.palette.accent),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = NAME_FIELD_PADDING),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                when (dialog.kind) {
                                    WorkspaceInputKind.NewFile -> tr("文件名，例如 notes.md", "File name, e.g. notes.md")
                                    WorkspaceInputKind.NewDir -> tr("文件夹名", "Folder name")
                                    WorkspaceInputKind.Rename -> tr("新名字", "New name")
                                    WorkspaceInputKind.RenameWorkspace -> tr("项目显示名", "Project display name")
                                    WorkspaceInputKind.NewWorkspace -> tr("项目名称，例如 my-app", "Project name, e.g. my-app")
                                },
                                style = PiTheme.text.mono.copy(fontSize = 17.sp),
                                color = PiTheme.palette.muted,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Text(
            if (showProblem) {
                problem.orEmpty()
            } else {
                when (dialog.kind) {
                    WorkspaceInputKind.Rename -> tr("只改文件名，内容不动。", "Renames the file only; contents remain unchanged.")
                    WorkspaceInputKind.NewWorkspace -> tr("保存在应用高速沙盒内（原生 ext4 速度，支持完整 git/npm，无需权限）。", "Created in fast app sandbox (native speed, full git/npm support, no permissions needed).")
                    WorkspaceInputKind.RenameWorkspace -> tr("只改显示名，目录路径不变，会话记录与项目信任不会丢失。", "Changes display name only; folder path, sessions, and project trust stay intact.")
                    else -> tr("建在当前目录下。", "Created in the current directory.")
                }
            },
            modifier = Modifier.padding(top = PiSpacing.inline),
            style = PiTheme.text.meta,
            color = if (showProblem) PiTheme.palette.error else PiTheme.palette.muted,
        )
        PiDialogActions {
            PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onClose)
            PiDialogAction(
                label = if (dialog.kind == WorkspaceInputKind.NewWorkspace) tr("创建并切换", "Create & Open") else tr("确定", "OK"),
                primary = true,
                enabled = canSubmit,
                onClick = { onSubmit(trimmed) },
            )
        }
        }
    }
}

/** 名字输入框：从 38 抬到 48（38 在手机上又矮又难戳），左右留 12 的内边距。 */
private val NAME_FIELD_HEIGHT = 58.dp
private val NAME_FIELD_RADIUS = WsShape.medium
private val NAME_FIELD_PADDING = 16.dp

// ================================================================ Snackbar

private data class WorkspaceSnack(val text: String, val tone: StateTone)

/** Snack 右侧那个可选动作：一个标签 + 一下点击（稿子 `Snack` 的 `p.action`）。 */
private data class WorkspaceSnackAction(val label: String, val onClick: () -> Unit)

/**
 * 底部一条提示（稿子的 `Snack`，`workspace-final.html:627-640`）：三档 tone，2.6 秒后自己走
 * （稿子的 `setTimeout(…, 2600)`）。它是这一屏唯一的反馈通道 —— 复制、导出、保存、文件操作
 * 的结果都在这里说，而不是弹一个对话框要用户点掉。
 *
 * ## 三档的底与字（照稿子）
 *
 * ```
 * Error    toolErrorBg    error     ✗
 * Warning  infoBg         warning   !
 * Info     cardBg         text      ·
 * ```
 *
 * Info 那一档原来用 `surfaceContainerHigh` 画底、`stateToneColor(Muted)` 画符号，两处各偏一
 * 格（`--card` 是 `cardBg`；`·` 的颜色在稿子里是 `--text`），这里收回来。符号与动作用
 * `ui/extension/ExtensionUiHost.kt` 的 `ExtensionSnack` 同一套角色（等宽 `mono`、动作
 * 14 + 500）—— 那是全应用同一套「符号 + 颜色 + 一句话」的编码，只是那一支挂在
 * `PiRoot` 上管全局通知，这一支属于工作区一屏。
 *
 * @param action 右侧可选动作（稿子 `p.action`），可空。这一屏目前**一个调用点都没接**：
 *   现在这些提示（复制、新建、删除的结果）都不需要用户再点一下，「删除后撤销」才需要，
 *   所以构件先把槽留出来。
 */
@Composable
private fun WorkspaceSnackBar(
    message: WorkspaceSnack,
    modifier: Modifier = Modifier,
    action: WorkspaceSnackAction? = null,
) {
    val palette = PiTheme.palette
    val glyph = when (message.tone) {
        StateTone.Error -> "✗"
        StateTone.Warning -> "!"
        else -> "·"
    }
    val glyphColor = when (message.tone) {
        StateTone.Error -> palette.error
        StateTone.Warning -> palette.warning
        else -> palette.text
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // `.b-snack{left:12px;right:12px;border-radius:12px;padding:10px 12px;gap:10px}`
            // （`workspace-final.html:185-186`，与 `06 §2`「Snackbar：左右 12、圆角 12、
            // `padding:10px 12px` gap 10」同一组数）。**没有描边** —— 那一圈
            // `1px borderMuted` 是本屏早期自造的，稿子里三种语气都只有底色。
            .padding(
                start = PiSettingsMetrics.cardPadding,
                end = PiSettingsMetrics.cardPadding,
                bottom = PiSettingsMetrics.cardPadding,
            )
            .clip(PiShapes.snackbar)
            .background(
                when (message.tone) {
                    StateTone.Error -> palette.toolErrorBg
                    StateTone.Warning -> palette.infoBg
                    else -> palette.cardBg
                },
            )
            .padding(
                horizontal = PiSettingsMetrics.cardPadding,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        // 稿子的 Snack 符号是 `mono t14`（`workspace-final.html:635`）：比正文大一号的
        // 等宽符号，与 14 的消息正文同档；`PiTheme.text.mono` 是 13 的机器正文档。
        Text(
            glyph,
            style = PiTheme.text.mono.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = glyphColor,
            maxLines = 1,
        )
        Text(
            message.text,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.prose,
            color = palette.text,
        )
        if (action != null) {
            Text(
                action.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(SNACK_ACTION_RADIUS))
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .padding(vertical = SNACK_ACTION_PADDING_VERTICAL),
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
            )
        }
    }
}

/** 动作标签的按压面：稿子只给了它一个 `press`，圆角与内边距取 `ExtensionSnack` 那一档。 */
private val SNACK_ACTION_RADIUS = 8.dp
private val SNACK_ACTION_PADDING_VERTICAL = 4.dp

// ================================================================ 通用

/**
 * 粗粒度相对时间，与会话列表**同一套措辞**（`SessionsScreen.kt:391-402`：`刚刚` /
 * `N分钟前` / `N小时前` / `N天前` / `N周前`）。规则重复一次而不是复用，因为那个函数
 * 是私有的、而且那个文件属于 B4（本轮不许碰）。
 */
private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return tr("刚刚", "Just now")
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> tr("刚刚", "Just now")
        minutes < 60 -> tr("${minutes}分钟前", "${minutes}m ago")
        minutes < 60 * 24 -> tr("${minutes / 60}小时前", "${minutes / 60}h ago")
        minutes < 60 * 24 * 7 -> tr("${minutes / (60 * 24)}天前", "${minutes / (60 * 24)}d ago")
        else -> tr("${minutes / (60 * 24 * 7)}周前", "${minutes / (60 * 24 * 7)}w ago")
    }
}

/**
 * 新建之后要打开的那条「显示路径」：在工作区里就是相对工作区的路径（`src/a.md`），
 * 面包屑已经走到工作区外（rootfs 的 `/opt` 等）就用绝对路径 —— viewer 的 displayPath
 * 只是标签，真正读写的是传入的 [file]。
 */
private fun workspaceDisplayPath(workspace: File, file: File): String {
    val base = runCatching { workspace.canonicalPath }.getOrDefault(workspace.absolutePath).trimEnd('/')
    val created = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    return if (base.isNotEmpty() && created.startsWith("$base/")) created.removePrefix("$base/") else created
}
