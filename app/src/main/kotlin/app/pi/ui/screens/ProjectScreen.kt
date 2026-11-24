package app.pi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.packages.AgentLayout
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.WorkspaceSwitch
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Workspace & project files screen (`ProjectScreen`).
 *
 * Manages host file browsing, session running/changed indicators, project resources,
 * and workspace switching/creation/deletion sheets (see `ProjectRows.kt` and `ProjectSheets.kt`).
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
    // 仅在工作区修订号或名称变化时解析一次，不在每次重组上触发磁盘 stat/mkdirs。
    val workspace = remember(context, state.workspace.revision, state.workspace.name) {
        PtyLauncher.workspaceHost(context)
    }
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

    // 手势/物理返回键与面包屑零冲突分工：
    // - 侧滑返回按层级退回（收起筛选 → 回到上一级目录 → 回到项目根后再退回对话）；
    // - 面包屑负责跨多级一键直达任意祖先目录。
    val canStepUp = (insideWorkspace && crumbs.size > homeCrumbs.size) || (!insideWorkspace && crumbs.isNotEmpty())
    BackHandler(enabled = true) {
        when {
            filterOpen -> {
                filterOpen = false
                filterText = ""
            }
            canStepUp -> crumbs = crumbs.dropLast(1)
            else -> onBack()
        }
    }
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
        listingError = null
        // 切换目录时保留旧列表最多 150ms：普通本地目录 2–5ms 即返回，彻底避免每次点文件夹都闪一下「正在读这个目录…」。
        val clearJob = if (listedDir != currentDir.absolutePath) {
            launch {
                delay(150)
                if (listedDir != currentDir.absolutePath) listing = null
            }
        } else {
            null
        }
        val result = withContext(Dispatchers.IO) { WorkspaceFiles.list(currentDir) }
        clearJob?.cancel()
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

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
            PiTopBar(
                onBack = onBack,
                title = tr("项目与文件", "Project & Files"),
                scrolled = scrolled,
                actions = {
                    PiTopBarIcon(
                        onClick = {
                            val targetGuestCwd = when {
                                insideWorkspace -> if (parentRelative.isEmpty()) {
                                    guestWorkspace
                                } else {
                                    guestWorkspace.trimEnd('/') + "/" + parentRelative
                                }
                                browseRoot == guestRootfs -> "/" + crumbs.joinToString("/")
                                else -> currentDir.absolutePath
                            }
                            session.requestNav(NavRequest.TerminalAt(targetGuestCwd))
                        },
                        contentDescription = tr("在当前目录打开终端", "Open terminal here"),
                        icon = Icons.Filled.Terminal,
                    )
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
                                if (crumbs.isNotEmpty()) {
                                    add(
                                        PiMenuItem(tr("浏览容器根目录 (/)", "Browse Container Root (/)")) {
                                            overflow = false
                                            crumbs = emptyList()
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
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
                        // 文件系统检查移至 IO 线程，避免在组合期阻塞主线程。
                        val canUseHereAsProject by androidx.compose.runtime.produceState(
                            initialValue = false,
                            key1 = currentDir.absolutePath,
                            key2 = atProjectRoot,
                        ) {
                            value = if (atProjectRoot) {
                                false
                            } else {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        currentDir.isDirectory && currentDir.canWrite() &&
                                            currentDir.absolutePath != "/" &&
                                            currentDir.canonicalPath != guestRootfs.canonicalPath
                                    }.getOrDefault(false)
                                }
                            }
                        }
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
                if (searchWholeProject && filterText.isNotBlank()) {
                    crumbs = homeCrumbs + target.path.split('/').filter { it.isNotEmpty() }
                    filterText = ""
                    filterOpen = false
                } else {
                    crumbs = crumbs + target.file.name
                }
            },
            onOpenAsProject = if (target.isDirectory) {
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
