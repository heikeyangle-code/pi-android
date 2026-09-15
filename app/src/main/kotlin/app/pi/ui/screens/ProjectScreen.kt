package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.engine.PiEngineSession
import app.pi.packages.AgentLayout
import app.pi.packages.ProjectTrust
import app.pi.packages.TrustRepository
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem
import app.pi.runtime.PtyLauncher
import app.pi.ui.BashRun
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.blocks.DiffBlock
import app.pi.ui.blocks.argString
import app.pi.ui.blocks.lineCount
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.settings.PiSettingsMetrics
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
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工作区 —— 这个会话的**项目现场**，而且现在能动手。
 *
 * ## 这一屏回答什么
 *
 * 「agent 在这个目录里到底在干什么，我自己又能在这里做什么？」用户在对话页看的是**过程**，
 * 在这里看的是**现场**。这一版按
 * `design/ui-refactor/design-demos/workspace-final.html`（工作区最终方案，17 个状态齐全）
 * 把这一屏从「只读」补成「现场 + 能动手」，**一屏五段**：
 *
 * | 段 | 内容 | 数据从哪来 |
 * |---|---|---|
 * | ① 当前目录卡 | 工作区名 + 现场摘要 + 「切换」 | 会话 cwd（`PiProject.workspaceName`）+ 下两段的读数 |
 * | ② 正在跑 | 没有在跑的命令就整段不画 | `UiState.bash` + 转录里 `Pending` 的 shell 卡 |
 * | ③ 本次会话改过 | 点行就地开 diff；行尾 ⋮ = 打开 / 编辑 / 重命名 / 删除 / 定位到对话 | 转录里 `write`/`edit` 的路径 + `ToolDiff` 的 `+N −M` |
 * | ④ 全部文件 | 面包屑 + 目录在前 + 行尾 ⋮ + 段头「新建」 | **宿主 File I/O**，`java.io.File` |
 * | ⑤ 这个目录的资源 | 四类分段 × 五种来源 × 四种状态 | `pi` 自己的收集规则（见 `WorkspaceResources.kt`） |
 *
 * ## 文件能力不依赖引擎
 *
 * 顶栏下面那一行就是在兑现这句话：工作区是 App 私有目录里的一个文件夹
 * （`GuestWorkspacePath.RELATIVE`），所以 ④⑤ 与查看器 / 编辑器 / 新建 / 重命名 / 删除
 * 全部走宿主 File I/O —— 引擎没起来时它们照常可用。②③ 依赖转录，引擎没起来时按
 * `engineDown` 显示成「无数据」，而不是一个看起来像「什么都没发生」的空表。
 *
 * ## 没有 git
 *
 * v2 那张卡上的「分支 main · 3 个文件有改动」在这一版里**删掉了**：pi 没有 git 通道
 * （`rpc-types.ts:20-74` 里没有任何 branch/status/diff 命令），工作区本身也不是 git 仓库。
 * 拿不到就不显示 —— 理由完整地写在 `ProjectResources.kt` 的 KDoc 里。
 *
 * ## 它不是什么
 *
 * 它**不挂** `ExtensionUiHost`（全局只挂一次，在 `PiRoot`），它**不注册** `BackHandler`
 * （全应用只有 `PiRoot` 那一处；这一屏的浮层都是 `Dialog` / `ModalBottomSheet`，返回键由
 * 它们各自的窗口处理），它也**不碰** `ui/terminal`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
) {
    val state by session.state.collectAsState()
    val sessions by session.sessions.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 工作区主目录由应用固定（`GuestWorkspacePath.RELATIVE`），`AgentLayout` 的
    // `hostProjectConfigDir()` 就是 `<workspace>/.pi`——pi 的项目资源根。
    val workspace = remember(context) { PtyLauncher.workspaceHost(context) }
    val layout = remember(context, workspace) {
        AgentLayout(context.applicationContext, workspace)
    }
    val configDir = remember(layout) { layout.hostProjectConfigDir() }
    val agentDir = layout.agentMirrorDir
    val guestWorkspace = layout.guestWorkspace

    // 当前会话的 cwd。`sessionFile` 是唯一说明「pi 现在在写哪个文件」的线上字段
    // （`SessionsScreen.kt:117` 用同一招判当前行），而 cwd 只存在于会话列表的摘要里——
    // 那是磁盘上会话头的读数，不是猜的。
    val activeFile = state.meta.sessionFile?.substringAfterLast('/')
    val currentCwd = remember(sessions, activeFile) {
        activeFile?.let { name -> sessions.firstOrNull { it.file.name == name }?.cwd }
    }
    val workspaceName = currentCwd?.let { PiProject.workspaceName(it) } ?: WorkspaceFiles.ROOT_LABEL

    // 引擎没起来：②③ 没有数据，④⑤ 与全部文件操作照常。判据是引擎状态本身，不是转录的
    // 长度——转录为空既可能是引擎没起来，也可能是这个会话真的什么都没做。
    val engine = state.engine
    val engineDown = engine == null ||
        engine == PiEngineSession.EngineState.Stopped ||
        engine == PiEngineSession.EngineState.Failed

    var refreshTick by remember { mutableIntStateOf(0) }

    // ---------------------------------------------------------------- ④ 浏览
    var crumbs by remember { mutableStateOf<List<String>>(emptyList()) }
    var listing by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var listingError by remember { mutableStateOf<WorkspaceOpen.Failed?>(null) }
    val currentDir = remember(workspace, crumbs) {
        crumbs.fold(workspace) { dir, segment -> File(dir, segment) }
    }
    LaunchedEffect(refreshTick, currentDir.absolutePath) {
        listing = null
        listingError = null
        val result = withContext(Dispatchers.IO) { WorkspaceFiles.list(currentDir) }
        result.fold(
            onSuccess = { listing = it },
            onFailure = { error ->
                listing = emptyList()
                listingError = WorkspaceOpen.Failed(
                    reason = "这个目录读不到了，可能被重命名或删掉；工作区里别的地方不受影响。",
                    detail = "${error::class.java.name}: ${error.message}\n  ${currentDir.absolutePath}",
                )
            },
        )
    }

    // ------------------------------------------------------------ ⑤ 资源扫描
    var resources by remember { mutableStateOf<List<WorkspaceResource>>(emptyList()) }
    var resourceKind by remember { mutableStateOf(WorkspaceResourceKind.Skill) }
    var projectUntrusted by remember { mutableStateOf(false) }
    // 工作区的兄弟目录列表：它是一次真实的磁盘读取，所以和资源扫描一样在 IO 线程上跑，
    // 不能放在 composition 里（`listFiles()` 在组合期间就是一次掉帧）。
    var workspaces by remember { mutableStateOf<List<SiblingWorkspace>>(emptyList()) }
    LaunchedEffect(refreshTick, workspace) {
        workspaces = withContext(Dispatchers.IO) { siblingWorkspaces(workspace) }
    }
    LaunchedEffect(refreshTick, workspace, agentDir) {
        withContext(Dispatchers.IO) {
            resources = runCatching {
                WorkspaceResourceScan.scan(workspace, configDir, agentDir)
            }.getOrDefault(emptyList())
            // 信任：pi 只加载**受信任**项目的 `.pi` 资源（`interactive-mode.ts:3877`、
            // `:3888-3907`），未受信任时它照旧启动但忽略这些目录 —— 而这一段照常把它们
            // 列出来，所以必须说清哪些不会生效。
            //
            // 判据与设置里的「扩展包与项目信任」卡同源（`PiPackagesHost.readTrust`）：
            // `trust.json` 的存档决定 + 设置里的 `defaultProjectTrust`，交给纯逻辑
            // `ProjectTrust.resolve` 裁决。`hasTrustRequiringResources` 在这里用**宿主侧
            // 同一批目录**判断（`.pi/` 下是否有东西），pi 还会额外看 `<cwd>/.agents/skills`；
            // 漏掉那一处只会让提示**少出现**，不会凭空出现。
            val requiresTrust = ProjectTrust.TRUST_REQUIRING_CONFIG_ENTRIES.any {
                File(configDir, it).exists()
            }
            if (requiresTrust) {
                val repository = TrustRepository(layout)
                val saved = runCatching { repository.decisionFor(layout.guestWorkspace) }.getOrNull()
                val defaultTrust = runCatching {
                    (session.settingsStore.read("defaultProjectTrust") as? JsonPrimitive)?.content
                }.getOrNull() ?: "ask"
                projectUntrusted = runCatching {
                    !ProjectTrust.resolve(
                        ProjectTrust.Inputs(
                            cwd = layout.guestWorkspace,
                            hasTrustRequiringResources = true,
                            savedDecision = saved,
                            defaultProjectTrust = defaultTrust,
                            // 这一屏不是那个对话框：不给答案（null），让 resolve 按存档
                            // 与默认值裁决，而不是替用户点一个。
                            hasUI = true,
                            userAnswer = null,
                        ),
                    ).trusted
                }.getOrDefault(false)
            } else {
                projectUntrusted = false
            }
        }
    }

    // ---------------------------------------------------------------- ②③
    val runs = remember(state.transcript, state.bash) { runningCommands(state.transcript, state.bash) }
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

    fun say(text: String, tone: StateTone = StateTone.Muted) {
        snack = WorkspaceSnack(text, tone)
    }

    LaunchedEffect(snack) {
        if (snack != null) {
            delay(2600)
            snack = null
        }
    }

    /** 相对路径 → 一个可以打开的文件；路径越界（`..`）或不在工作区里就返回 null。 */
    fun resolveInside(path: String): File? {
        val clean = path.trimStart('/')
        if (clean.isEmpty()) return null
        val file = File(workspace, clean)
        val root = workspace.canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            target
        } else {
            null
        }
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
                        "操作失败：" + (error.message ?: error::class.java.simpleName),
                        StateTone.Error,
                    )
                    onDone?.invoke(false)
                },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            PiTopBar(
                title = "工作区",
                // 稿子的顶栏副行是**引擎状态**（`statusMeta` + `Engine`），不是一句解释：
                // 「文件走宿主 File I/O」那句话挪到了顶栏下面那一行 Notice 里，两处不重复。
                engineMeta = session.engineLabel(state),
                actions = {
                    PiTopBarIcon(
                        onClick = { refreshTick++ },
                        contentDescription = "重新读一遍这个工作区",
                        icon = Icons.Filled.Refresh,
                    )
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = PiSpacing.scrollBottom),
            ) {
                // 顶栏下一行细提示：这一屏的文件能力不依赖引擎。
                item {
                    WsNotice(
                        text = if (engineDown) {
                            "引擎没起来：②③ 暂时没有数据；④ 全部文件与 ⑤ 目录资源照常可用，" +
                                "文件照样能打开 · 编辑 · 删除。"
                        } else {
                            "这个会话的项目现场。文件走宿主 File I/O，引擎没起来也能打开 · 编辑 · 删除。"
                        },
                        tone = if (engineDown) StateTone.Warning else StateTone.Muted,
                        glyph = if (engineDown) "!" else "·",
                    )
                }

                // -------------------------------------------------------- ①
                item {
                    CurrentDirectoryCard(
                        name = workspaceName,
                        summary = summaryText(engineDown, visibleChanged.size, runs.size),
                        onClick = { rootSheet = true },
                    )
                }

                // -------------------------------------------------------- ②
                if (runs.isNotEmpty()) {
                    item {
                        WsSectionHeader(
                            label = "正在跑",
                            count = "${runs.size} 条命令",
                            aside = {
                                Text(
                                    "进入上下文",
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    runs.forEach { run ->
                        item(key = "run:${run.key}") { RunningCommandRow(run) }
                    }
                }

                // -------------------------------------------------------- ③
                item {
                    WsSectionHeader(
                        label = "本次会话改过",
                        count = when {
                            engineDown -> "无数据"
                            visibleChanged.isEmpty() && pendingWrite == null -> "0 个文件"
                            else -> "${visibleChanged.size} 个文件"
                        },
                        aside = if (engineDown) {
                            null
                        } else {
                            {
                                Text(
                                    "最近改动在上",
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (engineDown) {
                    item {
                        WsRow(
                            title = "引擎没起来，读不到本次会话的改动",
                            lead = { FolderGlyph() },
                            meta = "④ 全部文件与 ⑤ 目录资源照常可用；改写过的文件自己打开也一样能看。",
                        )
                    }
                } else if (visibleChanged.isEmpty() && pendingWrite == null) {
                    item {
                        WsRow(
                            title = "这次会话还没动过文件",
                            lead = {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                    tint = PiTheme.palette.muted,
                                )
                            },
                            meta = "下面照旧能浏览、打开、编辑、删除工作区里的文件。",
                        )
                    }
                } else {
                    if (pendingWrite != null) {
                        item(key = "pending:${pendingWrite.path}") {
                            PendingWriteRow(pendingWrite)
                        }
                    }
                    visibleChanged.forEach { file ->
                        item(key = "changed:${file.path}") {
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
                                    val resolved = diffPathFor(file.path, guestWorkspace)
                                    val target = resolveInside(resolved)
                                    if (target != null) {
                                        openRelative(resolved, target)
                                    } else {
                                        say("这个文件不在工作区里，打不开。", StateTone.Warning)
                                    }
                                },
                                onMenu = {
                                    val resolved = diffPathFor(file.path, guestWorkspace)
                                    val target = resolveInside(resolved)
                                    if (target == null) {
                                        say("这个文件不在工作区里。", StateTone.Warning)
                                    } else {
                                        menuFor =
                                            menuTargetFor(resolved, target, fromSession = true)
                                    }
                                },
                            )
                        }
                    }
                }

                // -------------------------------------------------------- ④
                item {
                    WsSectionHeader(
                        label = "全部文件",
                        count = if (listingError != null) null else "${listing?.size ?: 0} 项",
                        aside = if (listingError != null) {
                            null
                        } else {
                            {
                                WsChip(
                                    text = "新建",
                                    glyph = "+",
                                    onClick = { newSheet = true },
                                )
                            }
                        },
                    )
                }
                item {
                    BreadcrumbRow(
                        crumbs = crumbs,
                        onGo = { index -> crumbs = crumbs.take(index) },
                    )
                }
                val error = listingError
                val entries = listing
                if (error != null) {
                    item {
                        WsErrBlock(
                            title = "读不到这个目录",
                            message = error.reason,
                            detail = error.detail,
                            actions = {
                                WsErrAction("重试") { refreshTick++ }
                                WsErrAction("回到工作区") { crumbs = emptyList() }
                            },
                        )
                    }
                } else if (entries == null) {
                    item {
                        WsRow(
                            title = "正在读这个目录…",
                            lead = { FolderGlyph() },
                        )
                    }
                } else if (entries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = PiSettingsMetrics.groupGap),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "这个目录是空的",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiTheme.palette.muted,
                            )
                            Spacer(Modifier.height(PiSettingsMetrics.cardPadding))
                            WsChip(
                                text = "新建文件",
                                glyph = "+",
                                active = true,
                                onClick = {
                                    inputDialog = WorkspaceInputDialog(
                                        kind = WorkspaceInputKind.NewFile,
                                        title = "新建文件",
                                        where = "工作区" + crumbs.joinToString("") { " / $it" },
                                        relativeParent = crumbs.joinToString("/"),
                                        absoluteParent = null,
                                        initial = "",
                                    )
                                },
                            )
                            Text(
                                "也可以从段头的「新建」里建一个文件夹。",
                                modifier = Modifier.padding(top = PiSettingsMetrics.cardPadding),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                    }
                } else {
                    entries.forEach { entry ->
                        item(key = "entry:${entry.path}") {
                            val matched = if (engineDown) {
                                null
                            } else {
                                changedPaths.firstOrNull {
                                    WorkspaceFiles.sameFile(entry.path, it, guestWorkspace)
                                }
                            }
                            val entryDiff = matched?.let { path ->
                                remember(state.transcript, path) { diffFor(state.transcript, path) }
                            }
                            WorkspaceEntryRow(
                                entry = entry,
                                sessionChanged = matched,
                                diff = entryDiff,
                                onOpen = {
                                    if (entry.isDirectory) {
                                        crumbs = crumbs + entry.name
                                    } else {
                                        val file = File(currentDir, entry.name)
                                        openRelative(entry.path, file)
                                    }
                                },
                                onMenu = {
                                    menuFor = menuTargetFor(
                                        path = entry.path,
                                        file = File(currentDir, entry.name),
                                        fromSession = false,
                                    )
                                },
                            )
                        }
                    }
                }

                // -------------------------------------------------------- ⑤
                item {
                    val shown = resources.filter { it.kind == resourceKind }
                    WsSectionHeader(
                        label = "这个目录的资源",
                        count = null,
                        aside = {
                            Text(
                                "${resourceKind.label} ${shown.size} 项",
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                if (projectUntrusted) {
                    item {
                        WsNotice(
                            text = "项目未受信任：pi 不会加载这个项目里的 .pi 资源。",
                            tone = StateTone.Warning,
                            glyph = "!",
                        )
                    }
                    item {
                        Text(
                            "要改就去「设置 → 扩展包与项目信任」。",
                            modifier = Modifier.padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                bottom = PiSettingsMetrics.cardPadding,
                            ),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.muted,
                        )
                    }
                } else {
                    item {
                        Text(
                            "来源按 pi 的优先级排：项目 .pi → .agents → 全局 → 包。",
                            modifier = Modifier.padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                bottom = PiSettingsMetrics.cardPadding,
                            ),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.muted,
                        )
                    }
                }
                item {
                    WsSeg(
                        items = WorkspaceResourceKind.order.map {
                            WsSegment(it, it.label)
                        },
                        value = resourceKind,
                        onChange = { resourceKind = it },
                        modifier = Modifier.padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            bottom = PiSettingsMetrics.cardPadding,
                        ),
                    )
                }
                val shownResources = resources.filter { it.kind == resourceKind }
                if (shownResources.isEmpty()) {
                    item {
                        WsRow(
                            title = "这一类还没有资源",
                            lead = { FolderGlyph() },
                            meta = "把「${resourceKind.label}」放进 .pi、.agents 或全局目录之后，" +
                                "点这一屏的刷新键就能看到它。",
                        )
                    }
                } else {
                    item {
                        WsCard {
                            shownResources.forEachIndexed { index, resource ->
                                if (index > 0) WsHairline()
                                Box(
                                    Modifier.alpha(if (projectUntrusted && resource.source == WorkspaceSource.ProjectPi) 0.5f else 1f),
                                ) {
                                    ResourceRow(
                                        resource = resource,
                                        untrusted = projectUntrusted,
                                        onOpen = {
                                            viewer = WorkspaceViewerTarget(
                                                relativePath = resource.displayPath,
                                                file = resource.file,
                                                kind = WorkspaceFiles.kindOf(resource.file),
                                                sizeBytes = if (resource.file.isFile) resource.file.length() else 0L,
                                                modifiedAt = resource.file.lastModified(),
                                                // 稿子第 5 条：资源行只有「打开」与「在对话里用」，
                                                // 工作区屏里不动 .pi 的东西。
                                                editable = false,
                                                startInEdit = false,
                                            )
                                        },
                                        onUse = {
                                            val copied = copyToClipboard(context, resource.slashCommand)
                                            say(
                                                if (copied) {
                                                    "已复制 ${resource.slashCommand} · 粘到对话输入框即可用"
                                                } else {
                                                    "剪贴板不可用；${resource.slashCommand} 可以直接手打"
                                                },
                                                if (copied) StateTone.Muted else StateTone.Warning,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "工作区在 App 私有目录里，路径由 App 固定；文件读写走宿主 File I/O，" +
                            "不需要额外授权。",
                        modifier = Modifier.padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = PiSettingsMetrics.groupGap,
                        ),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
        }

        snack?.let { message ->
            WorkspaceSnackBar(
                message = message,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // ------------------------------------------------------------ 浮层：切换工作区
    if (rootSheet) {
        WorkspaceRootSheet(
            workspaces = workspaces,
            currentName = workspace.name,
            onClose = { rootSheet = false },
            onPick = { picked ->
                rootSheet = false
                if (picked == workspace.name) {
                    say("已经在这个工作区里。")
                } else {
                    // 切换工作区要动引擎的 cwd 与挂载（`PiEngineHost.kt:548-555`），
                    // 那是本批之后的事。这里给一个明确的不可用状态，不假装做到了。
                    say("切换工作区要重开引擎并换挂载，这一批还没接上。", StateTone.Warning)
                }
            },
            onNew = {
                rootSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewWorkspace,
                    title = "新建工作区",
                    where = "App 私有的工作区目录",
                    relativeParent = null,
                    absoluteParent = workspace.parentFile,
                    initial = "",
                )
            },
            onPickDeviceDir = {
                rootSheet = false
                say("从设备目录选择需要「所有文件访问」权限，这一批没接上。", StateTone.Warning)
            },
        )
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
            onEnter = {
                menuFor = null
                crumbs = crumbs + target.file.name
            },
            onRename = {
                menuFor = null
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.Rename,
                    title = "重命名",
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
                    if (copied) "已复制 · ${target.path}" else "剪贴板不可用，路径：${target.path}",
                    if (copied) StateTone.Muted else StateTone.Warning,
                )
            },
            onLocateInChat = {
                menuFor = null
                // 稿子的原型也只是关掉菜单；正式版要切到「对话」tab 并定位到那一步，
                // 而这一屏拿不到导航句柄（`ProjectScreen` 只有 contentPadding + session）。
                say("定位到对话需要切 tab 并滚动转录，这一批没接上。", StateTone.Warning)
            },
        )
    }

    // ------------------------------------------------------------ 浮层：新建（文件 / 文件夹）
    if (newSheet) {
        WorkspaceNewSheet(
            where = workspaceName + crumbs.joinToString("") { " / $it" },
            onClose = { newSheet = false },
            onNewFile = {
                newSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewFile,
                    title = "新建文件",
                    where = "工作区" + crumbs.joinToString("") { " / $it" },
                    relativeParent = crumbs.joinToString("/"),
                    absoluteParent = null,
                    initial = "",
                )
            },
            onNewDir = {
                newSheet = false
                inputDialog = WorkspaceInputDialog(
                    kind = WorkspaceInputKind.NewDir,
                    title = "新建文件夹",
                    where = "工作区" + crumbs.joinToString("") { " / $it" },
                    relativeParent = crumbs.joinToString("/"),
                    absoluteParent = null,
                    initial = "",
                )
            },
        )
    }

    // ------------------------------------------------------------ 浮层：输入框
    inputDialog?.let { dialog ->
        WorkspaceNameDialog(
            dialog = dialog,
            onClose = { inputDialog = null },
            onSubmit = { name ->
                val problem = WorkspaceFiles.nameProblem(name)
                if (problem == null) {
                    inputDialog = null
                    when (dialog.kind) {
                        WorkspaceInputKind.NewFile -> {
                            val parent = File(workspace, dialog.relativeParent.orEmpty())
                            val relative = listOf(dialog.relativeParent, name)
                                .filterNotNull()
                                .filter { it.isNotEmpty() }
                                .joinToString("/")
                            runFileOp(
                                what = "已新建文件 · $name",
                                onDone = { ok ->
                                    // 稿子：「建完直接进编辑态，写什么由你。」
                                    if (ok) {
                                        openRelative(relative, File(parent, name), startInEdit = true)
                                    }
                                },
                            ) {
                                WorkspaceFiles.createFile(parent, name)
                            }
                        }

                        WorkspaceInputKind.NewDir -> runFileOp("已新建文件夹 · $name") {
                            WorkspaceFiles.createDir(File(workspace, dialog.relativeParent.orEmpty()), name)
                        }

                        WorkspaceInputKind.NewWorkspace -> runFileOp("已新建工作区 · $name") {
                            WorkspaceFiles.createDir(
                                dialog.absoluteParent ?: workspace.parentFile ?: workspace,
                                name,
                            )
                        }

                        WorkspaceInputKind.Rename -> {
                            val target = File(dialog.absoluteParent ?: workspace, dialog.initial)
                            runFileOp("已重命名 · $name") { WorkspaceFiles.rename(target, name) }
                        }
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
                title = if (target.isDirectory) "删除文件夹" else "删除文件",
                glyph = "!",
                glyphTone = PiTheme.palette.error,
            )
            PiDialogBody(target.path)
            PiDialogBody(
                "删除「${target.file.name}」？文件会从工作区里永久删除，不进回收站，也恢复不了。" +
                    if (target.fromSession) {
                        " pi 在本次会话里对它做过的改动不会保留。"
                    } else {
                        ""
                    },
            )
            PiDialogActions {
                PiDialogAction(label = "取消", primary = false, onClick = { deleteFor = null })
                PiDialogAction(
                    label = "永久删除",
                    primary = true,
                    tone = PiTheme.palette.error,
                    onClick = {
                        val victim = target
                        deleteFor = null
                        runFileOp("已删除 · ${victim.file.name}") {
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

// ================================================================ ① 当前目录卡

/**
 * 当前目录卡（v2：`surf-low` 底、圆角 10、左缘 2dp accent 条、标题 17/600、副行 12）。
 *
 * 这一版两处改动，都是稿子的：整张卡**可点**（右端一枚「切换」徽标 + chevron，开工作区
 * 切换面板），副行只写能真实取到的两件事 —— 本次会话改过几个文件、正在跑几条命令。
 * v2 那张卡上的「分支 main · 3 个文件有改动」删掉了：pi 没有 git 这条通道。
 */
@Composable
private fun CurrentDirectoryCard(name: String, summary: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPadding,
            )
            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(PiSettingsMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左缘 2dp accent 条，上下各缩进 8。
            Box(
                Modifier
                    .padding(
                        top = PiSettingsMetrics.currentBarInset,
                        bottom = PiSettingsMetrics.currentBarInset,
                    )
                    .width(PiSettingsMetrics.currentBarWidth)
                    .height(CURRENT_BAR_HEIGHT)
                    .clip(RoundedCornerShape(1.dp))
                    .background(PiTheme.palette.accent),
            )
            Row(
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.cardPadding,
                    end = PiSettingsMetrics.cardPaddingLoose,
                    top = PiSettingsMetrics.cardPadding,
                    bottom = PiSettingsMetrics.cardPadding,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
                    tint = PiTheme.palette.muted,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        summary,
                        modifier = Modifier.padding(top = 2.dp),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WsBadge(text = "切换", tone = StateTone.Muted)
                Text("›", style = PiTheme.text.mono, color = PiTheme.palette.muted, maxLines = 1)
            }
        }
    }
}

/** 左缘 accent 条的高度（v2 的 `top:8;bottom:8` 撑出来的那一段）。 */
private val CURRENT_BAR_HEIGHT = 40.dp

/** ① 的现场摘要，三句话对应三种真实状态，没有一句是编的。 */
private fun summaryText(engineDown: Boolean, changedCount: Int, runningCount: Int): String = when {
    engineDown -> "引擎没起来，现场摘要暂时没有数据"
    runningCount > 0 -> "本次会话改过 $changedCount 个文件 · 正在跑 $runningCount 条命令"
    else -> "本次会话改过 $changedCount 个文件 · 没有命令在跑"
}

@Composable
private fun FolderGlyph() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}

// ================================================================ ② 正在跑

/** One command the session has running, from either of the two places it can be. */
private data class RunningCommand(
    val key: String,
    val command: String,
    /** Null while pi has reported no duration (a `!` run never reports one). */
    val elapsedMs: Long?,
    val lines: Int,
    val inContext: Boolean,
)

/**
 * 正在跑的命令，取自**两个**真实来源：`state.bash`（`!` / `!!` 前缀的 [BashRun]）与转录里
 * `status = Pending` 的 `bash`/`powershell` 工具卡。时长只来自 `ToolCall.elapsedMs`
 * （= `endedAt - ts`），这一屏**不自己计时** —— 那量的是手机的重组，不是命令。
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
            elapsedMs = null,
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
                elapsedMs = item.elapsedMs,
                lines = lineCount(item.output),
                inContext = true,
            )
        }
    }
    return out.take(MAX_RUNNING_ROWS)
}

/** 顶多这么多条——「正在跑」超过四条的时候，它已经不是「正在跑」了。 */
private const val MAX_RUNNING_ROWS = 4

/** 一条正在跑的命令：标题行 + 状态徽章 + 页脚读数 + 进度段（稿子 ②）。 */
@Composable
private fun RunningCommandRow(run: RunningCommand) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
    ) {
        WsCard {
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
                    StateChip(label = "运行中", tone = StateTone.Warning, glyph = "…")
                },
                meta = buildString {
                    append("运行中")
                    run.elapsedMs?.let { append(" · 已运行 ").append(secondsText(it)) }
                    if (run.lines > 0) append(" · ").append(run.lines).append(" 行")
                    append(" · ").append(if (run.inContext) "进入上下文" else "不进上下文")
                },
                metaMono = true,
                trail = {
                    app.pi.ui.theme.DurationMeter(
                        ms = run.elapsedMs,
                        color = PiTheme.palette.warning,
                        contentDescription = "这条命令已运行 " + (run.elapsedMs?.let { secondsText(it) } ?: "未知"),
                    )
                },
            )
        }
        val elapsed = run.elapsedMs
        if (elapsed != null) {
            Row(
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    top = PiSettingsMetrics.rowPaddingVertical,
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
    String.format(Locale.US, "%.1f 秒", ms / 1000.0)

// ================================================================ ③ 本次会话改过

/**
 * pi 正在写的那个文件（稿子 ③ 里的 pending 行）：`--tool-pending` 底 + 一行说明。
 *
 * 「已收到 24 行」那半句只在 pi 真的流出了行的时候写 —— 给不出就只留「正在写入」，
 * 右侧的刻度也一并撤掉（稿子底部第 3 条自己就是这么要求的）。
 */
@Composable
private fun PendingWriteRow(pending: WorkspacePendingWrite) {
    var expanded by rememberSaveable(pending.path) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(PiTheme.palette.toolPendingBg)
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
    ) {
        WsRow(
            title = pending.path,
            mono = true,
            strong = true,
            lead = { FolderGlyph() },
            badge = {
                WsBadge(text = "本次会话", tone = StateTone.Accent, glyph = "●")
            },
            meta = null,
            trail = {
                if (pending.receivedLines > 0) {
                    app.pi.ui.theme.DurationMeter(
                        ms = MID_WRITE_TICK_MS,
                        color = PiTheme.palette.warning,
                        contentDescription = "pi 正在写入这个文件",
                    )
                }
            },
            onClick = { expanded = !expanded },
        )
        Row(
            modifier = Modifier.padding(
                start = PiSettingsMetrics.rowPaddingHorizontal,
                bottom = PiSettingsMetrics.rowPaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text("…", style = PiTheme.text.monoSmall, color = PiTheme.palette.warning, maxLines = 1)
            Text(
                buildString {
                    append("pi 正在")
                    append(if (pending.action == ProjectFileAction.Edited) "编辑" else "写入")
                    append("这个文件")
                    if (pending.receivedLines > 0) {
                        append(" · 已收到 ").append(pending.receivedLines).append(" 行")
                    }
                },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Text(
                "pi 还在写这个文件；落盘之后这块 diff 才会出现。",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        WsHairline(inset = false)
    }
}

/** 写入中的刻度用它的时长（稿子画的是 `Tick ms={1800}`）。 */
private const val MID_WRITE_TICK_MS = 1_800L

/**
 * 一行「本次会话改过」。
 *
 * 点行**就地展开那块 diff**（`blocks/DiffBlock`，这一批不许改它，所以只调用）；行尾 ⋮
 * 才是打开 / 编辑 / 重命名 / 删除 / 定位到对话。状态是三重编码：`本次会话` 徽标 + `+N −M`
 * 两色 + 符号，颜色从来不是唯一信号。
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
            title = file.path,
            mono = true,
            strong = true,
            lead = {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                    tint = PiTheme.palette.muted,
                )
            },
            badge = {
                WsBadge(text = "本次会话", tone = StateTone.Accent, glyph = "●")
            },
            meta = buildString {
                append(file.action.label)
                // pi 报过耗时才写它 —— 稿子的副行是 `edit · 214ms · 12 分钟前`。
                durationMs?.takeIf { it > 0 }?.let {
                    append(" · ").append(app.pi.ui.blocks.formatDuration(it))
                }
                append(" · ").append(relativeTime(file.at))
                if (diff != null) append(if (expanded) " · 收起 diff" else " · 就地看这次 diff")
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
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                defaultExpanded = true,
            )
        }
        WsHairline()
    }
}

/**
 * 这次会话为某个路径留下的 diff，或 null。
 *
 * `edit` 卡与 `diff` 卡在转录里是**两条** item（`rpc/Transcript.kt:1405-1440`），所以这里
 * 按路径把后者找回来；取最后一条：同一路径被改过两次时，用户要看的是最新那次。
 */
private fun diffFor(items: List<TranscriptItem>, path: String): ToolDiff? =
    items.filterIsInstance<ToolDiff>().lastOrNull { it.path == path && it.diffText.isNotBlank() }

/** pi 的参数可能是 guest 拼法；把它折成工作区相对路径，好在工作区里找到那个文件。 */
private fun diffPathFor(path: String, guestWorkspace: String): String {
    val prefix = guestWorkspace.trimEnd('/') + "/"
    return if (path.startsWith(prefix)) path.removePrefix(prefix) else path.trimStart('/')
}

// ================================================================ ④ 全部文件

/**
 * 面包屑：「工作区 / src / session」。
 *
 * 每一段可点回那一层（最后一段是当前目录，点了不动）；长名字中间省略（稿子的
 * `mid(n, 16)`）—— 省略中间而不是末尾，因为路径的两端才是有信息的那两端。
 */
@Composable
private fun BreadcrumbRow(crumbs: List<String>, onGo: (Int) -> Unit) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                bottom = PiSettingsMetrics.cardPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(
            text = WorkspaceFiles.ROOT_LABEL,
            mono = false,
            current = crumbs.isEmpty(),
            onClick = { onGo(0) },
        )
        crumbs.forEachIndexed { index, segment ->
            Text(
                "/",
                modifier = Modifier.padding(horizontal = 3.dp),
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
            )
            Crumb(
                text = WorkspaceFiles.middleEllipsis(segment, CRUMB_MAX_CHARS),
                mono = true,
                current = index == crumbs.lastIndex,
                onClick = { onGo(index + 1) },
            )
        }
    }
}

/** 稿子的 `mid(n, 16)`。 */
private const val CRUMB_MAX_CHARS = 16

@Composable
private fun Crumb(text: String, mono: Boolean, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            .width(CRUMB_MAX_WIDTH),
        style = if (mono) PiTheme.text.monoSmall else PiTheme.text.meta,
        color = if (current) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 稿子给每一段面包屑 `maxWidth:170`。 */
private val CRUMB_MAX_WIDTH = 170.dp

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
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        WsRow(
            title = WorkspaceFiles.middleEllipsis(entry.name, ENTRY_NAME_MAX_CHARS),
            mono = true,
            strong = true,
            lead = { EntryGlyph(entry.kind) },
            badge = if (sessionChanged != null) {
                {
                    WsBadge(text = "本次会话", tone = StateTone.Accent, glyph = "●")
                }
            } else {
                null
            },
            meta = if (entry.isDirectory) {
                "${entry.childCount} 项 · ${WorkspaceFiles.formatTime(entry.modifiedAt)}"
            } else {
                "${WorkspaceFiles.formatSize(entry.sizeBytes)} · ${WorkspaceFiles.formatTime(entry.modifiedAt)}"
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
            } else {
                null
            },
            trail = { WsMoreButton(onClick = onMenu) },
            onClick = onOpen,
        )
        WsHairline()
    }
}

/** 目录名太长时中间省略（与面包屑同一条规则）。 */
private const val ENTRY_NAME_MAX_CHARS = 28

/** 文件夹 / 文本 / 二进制三种图标：稿子为二进制单加了一个图标态。 */
@Composable
private fun EntryGlyph(kind: WorkspaceEntryKind) {
    when (kind) {
        WorkspaceEntryKind.Directory -> Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = PiTheme.palette.muted,
        )

        WorkspaceEntryKind.Binary -> Text(
            "▤",
            style = PiTheme.text.mono,
            color = PiTheme.palette.muted,
            maxLines = 1,
        )

        WorkspaceEntryKind.Html, WorkspaceEntryKind.Text -> Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = PiTheme.palette.muted,
        )
    }
}

// ================================================================ ⑤ 资源

/**
 * 一行资源：名字 + 来源徽标 + 状态点（只有异常才标记）。
 *
 * 四种状态都是**三重编码**：`!` + 「与「项目 .pi」同名」/ `✗` + 「解析失败」/ `⊘` + 「已禁用」，
 * 正常什么都不加（v2 的纪律：只有异常才留痕迹）。技能与提示词那一行多一个「在对话里用」。
 */
@Composable
private fun ResourceRow(
    resource: WorkspaceResource,
    untrusted: Boolean,
    onOpen: () -> Unit,
    onUse: () -> Unit,
) {
    val isExtension = resource.kind == WorkspaceResourceKind.Extension
    val piRow = resource.source == WorkspaceSource.ProjectPi
    WsRow(
        title = resource.name,
        mono = isExtension,
        strong = isExtension,
        lead = { FolderGlyph() },
        badge = {
            WsBadge(
                text = resource.sourceLabel,
                mono = resource.sourceMono,
                tone = if (resource.source == WorkspaceSource.ProjectPi) {
                    StateTone.Accent
                } else {
                    StateTone.Muted
                },
                dot = true,
            )
        },
        meta = buildString {
            val description = resource.description
            if (!description.isNullOrBlank()) append(description)
            when (resource.status) {
                WorkspaceResourceStatus.ParseFail -> {
                    if (isNotEmpty()) append(" · ")
                    append(resource.reason.orEmpty())
                }

                WorkspaceResourceStatus.Disabled -> {
                    if (isNotEmpty()) append(" · ")
                    append(resource.reason.orEmpty())
                }

                else -> Unit
            }
            if (untrusted && piRow) {
                if (isNotEmpty()) append(" · ")
                append("不会加载（项目未受信任）")
            }
        }.ifEmpty { resource.displayPath },
        value = {
            when (resource.status) {
                WorkspaceResourceStatus.Normal -> Unit
                WorkspaceResourceStatus.Conflict -> StatusWord(
                    glyph = "!",
                    text = "与「${resource.conflictWith.orEmpty()}」同名",
                    color = PiTheme.palette.warning,
                )

                WorkspaceResourceStatus.ParseFail -> StatusWord(
                    glyph = "✗",
                    text = "解析失败",
                    color = PiTheme.palette.error,
                )

                WorkspaceResourceStatus.Disabled -> StatusWord(
                    glyph = "⊘",
                    text = "已禁用",
                    color = PiTheme.palette.muted,
                )
            }
        },
        trail = if (resource.usableInChat) {
            {
                Text(
                    "在对话里用",
                    modifier = Modifier
                        .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
                        .clickable(onClick = onUse)
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.accent,
                    maxLines = 1,
                )
            }
        } else {
            null
        },
        onClick = { if (resource.openable) onOpen() },
    )
}

/** 状态那半格：符号 + 词，同一个字色。 */
@Composable
private fun StatusWord(glyph: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        Text(glyph, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        Text(text, style = PiTheme.text.meta, color = color, maxLines = 1)
    }
}

// ================================================================ 浮层：切换工作区

/** 工作区切换面板里的一条现有工作区。 */
private data class SiblingWorkspace(val name: String, val fileCount: Int, val modifiedAt: Long)

/**
 * `<files>/pi/workspaces/` 下的兄弟目录。工作区的根由 App 固定
 * （`GuestWorkspacePath.RELATIVE`），所以「现有工作区」在磁盘上就是那些兄弟目录 ——
 * 数出来的是真的，不是编的一张表。
 */
private fun siblingWorkspaces(workspace: File): List<SiblingWorkspace> {
    val parent = workspace.parentFile ?: return listOf(
        SiblingWorkspace(workspace.name, 0, workspace.lastModified()),
    )
    val siblings = parent.listFiles().orEmpty()
        .filter { it.isDirectory }
        .map { dir ->
            SiblingWorkspace(
                name = dir.name,
                fileCount = dir.listFiles()?.size ?: 0,
                modifiedAt = dir.lastModified(),
            )
        }
        .sortedBy { it.name }
    return if (siblings.any { it.name == workspace.name }) {
        siblings
    } else {
        listOf(SiblingWorkspace(workspace.name, 0, workspace.lastModified())) + siblings
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceRootSheet(
    workspaces: List<SiblingWorkspace>,
    currentName: String,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onPickDeviceDir: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "切换工作区",
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "工作区就是 pi 的现场目录；它就在 App 私有目录里。",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.supportingGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "现有工作区",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.groupGap,
                    bottom = PiSettingsMetrics.groupHeaderGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
            WsCard {
                workspaces.forEachIndexed { index, item ->
                    if (index > 0) WsHairline()
                    WsRow(
                        title = item.name,
                        mono = true,
                        strong = true,
                        lead = { FolderGlyph() },
                        badge = if (item.name == currentName) {
                            {
                                WsBadge(text = "当前", tone = StateTone.Accent, glyph = "●")
                            }
                        } else {
                            null
                        },
                        meta = "${item.fileCount} 项 · ${WorkspaceFiles.formatTime(item.modifiedAt)}",
                        onClick = { onPick(item.name) },
                    )
                }
            }
            Text(
                "加一个",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.groupGap,
                    bottom = PiSettingsMetrics.groupHeaderGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
            WsCard {
                WsRow(
                    title = "新建工作区",
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
                    meta = "在 App 私有目录里建一个空目录，不需要任何权限。",
                    onClick = onNew,
                )
                WsHairline()
                WsRow(
                    title = "从设备目录选择（需授权）",
                    strong = true,
                    titleColor = PiTheme.palette.accent,
                    lead = { FolderGlyph() },
                    meta = "需要「所有文件访问」权限；只有从设备目录里选工作区时才需要。",
                    onClick = onPickDeviceDir,
                )
            }
            Text(
                "新建的工作区落在 App 私有目录里，不需要授权；只有「从设备目录选择」那一条要系统权限。",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.cardPaddingLoose,
                    bottom = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
            Text(
                "换工作区等于换一个现场：会新建一个 pi 会话，当前会话不会被删除。",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    bottom = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
    }
}

// ================================================================ 浮层：文件菜单

/** 行尾 ⋮ 的目标。 */
private data class WorkspaceMenuTarget(
    val path: String,
    val file: File,
    val isDirectory: Boolean,
    /** 从 ③ 来的行多一项「看它在对话里的那一步」。 */
    val fromSession: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

private fun menuTargetFor(path: String, file: File, fromSession: Boolean): WorkspaceMenuTarget =
    WorkspaceMenuTarget(
        path = path,
        file = file,
        isDirectory = file.isDirectory,
        fromSession = fromSession,
        sizeBytes = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified(),
    )

/**
 * 行尾 ⋮ 的底部菜单。
 *
 * 稿子的动作表：③ 的行是「打开 / 编辑 / 重命名 / 删除 / 看它在对话里的那一步」，④ 的文件是
 * 「打开 / 编辑 / 重命名 / 删除 / 复制路径」，④ 的目录是「进入 / 重命名 / 删除 / 复制路径」。
 * 删除用错误色（它是唯一不可逆的那一个）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceMenuSheet(
    target: WorkspaceMenuTarget,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onEnter: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onLocateInChat: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                Text(
                    WorkspaceFiles.middleEllipsis(target.file.name, MENU_TITLE_MAX_CHARS),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    target.path,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.cardPadding))
            if (target.isDirectory) {
                MenuAction("进入", onEnter)
                MenuAction("重命名", onRename)
                MenuAction("删除", onDelete, tone = PiTheme.palette.error)
                MenuAction("复制路径", onCopyPath)
            } else {
                MenuAction("打开", onOpen)
                MenuAction("编辑", onEdit)
                MenuAction("重命名", onRename)
                MenuAction("删除", onDelete, tone = PiTheme.palette.error)
                if (target.fromSession) {
                    MenuAction("看它在对话里的那一步", onLocateInChat)
                } else {
                    MenuAction("复制路径", onCopyPath)
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.groupGap))
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.cardPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = tone ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ================================================================ 浮层：新建

/** 「新建」先问文件还是文件夹（稿子的 `dlg.kind === 'new'`）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceNewSheet(
    where: String,
    onClose: () -> Unit,
    onNewFile: () -> Unit,
    onNewDir: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                Text(
                    "新建",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "建在「$where」里",
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.cardPadding))
            NewChoice(
                title = "新建文件",
                supporting = "建完直接进编辑态，写什么由你。",
                glyph = { EntryGlyph(WorkspaceEntryKind.Text) },
                onClick = onNewFile,
            )
            WsHairline()
            NewChoice(
                title = "新建文件夹",
                supporting = "放在当前目录下。",
                glyph = { EntryGlyph(WorkspaceEntryKind.Directory) },
                onClick = onNewDir,
            )
            Spacer(Modifier.height(PiSettingsMetrics.groupGap))
        }
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
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.cardPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.searchIconGap),
    ) {
        glyph()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
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

private enum class WorkspaceInputKind { NewFile, NewDir, NewWorkspace, Rename }

/** 新建文件 / 新建文件夹 / 新建工作区 / 重命名共用的一支输入框（稿子就是这么复用的）。 */
private data class WorkspaceInputDialog(
    val kind: WorkspaceInputKind,
    val title: String,
    /** 副行的「建在哪里」文案。 */
    val where: String,
    /** 相对工作区的父目录；只有新建文件 / 文件夹用它。 */
    val relativeParent: String?,
    /** 绝对父目录；重命名与新建工作区用它。 */
    val absoluteParent: File?,
    val initial: String,
    val dialogSub: String? = null,
)

@Composable
private fun WorkspaceNameDialog(
    dialog: WorkspaceInputDialog,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var name by remember(dialog) { mutableStateOf(dialog.initial) }
    val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
    PiAutoFocus(focus)
    PiDialog(onDismissRequest = onClose) {
        PiDialogTitle(title = dialog.title)
        PiDialogBody(dialog.dialogSub ?: dialog.where)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = PiSettingsMetrics.cardPadding)
                .height(NAME_FIELD_HEIGHT)
                .clip(RoundedCornerShape(NAME_FIELD_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    PiSettingsMetrics.hairline,
                    PiTheme.palette.borderAccent,
                    RoundedCornerShape(NAME_FIELD_RADIUS),
                )
                .padding(horizontal = PiSettingsMetrics.rowGap),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                textStyle = PiTheme.text.mono.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PiTheme.palette.accent),
            )
        }
        Text(
            if (dialog.kind == WorkspaceInputKind.Rename) {
                "只改文件名，内容不动。"
            } else {
                "建在当前目录下。"
            },
            modifier = Modifier.padding(top = PiSpacing.inline),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
        PiDialogActions {
            PiDialogAction(label = "取消", primary = false, onClick = onClose)
            PiDialogAction(
                label = "确定",
                primary = true,
                onClick = { onSubmit(name.trim()) },
            )
        }
    }
}

/** 稿子给输入框的 `height:38; border-radius:9`。 */
private val NAME_FIELD_HEIGHT = 38.dp
private val NAME_FIELD_RADIUS = 9.dp

// ================================================================ Snackbar

private data class WorkspaceSnack(val text: String, val tone: StateTone)

/**
 * 底部一条提示（稿子的 `Snack`）：三档 tone，2.6 秒后自己走（稿子的
 * `setTimeout(…, 2600)`）。它是这一屏唯一的反馈通道 —— 复制、导出、保存、文件操作的结果
 * 都在这里说，而不是弹一个对话框要用户点掉。
 */
@Composable
private fun WorkspaceSnackBar(message: WorkspaceSnack, modifier: Modifier = Modifier) {
    val glyph = when (message.tone) {
        StateTone.Error -> "✗"
        StateTone.Warning -> "!"
        else -> "·"
    }
    val color = app.pi.ui.theme.stateToneColor(message.tone, PiTheme.palette)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                bottom = PiSettingsMetrics.cardPadding,
            )
            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
            .background(
                when (message.tone) {
                    StateTone.Error -> PiTheme.palette.toolErrorBg
                    StateTone.Warning -> PiTheme.palette.infoBg
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .border(
                PiSettingsMetrics.hairline,
                PiTheme.palette.borderMuted,
                RoundedCornerShape(PiSettingsMetrics.cardRadius),
            )
            .padding(
                horizontal = PiSettingsMetrics.cardPaddingLoose,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Text(glyph, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        Text(
            message.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ================================================================ 通用

/**
 * 粗粒度相对时间，与会话列表**同一套措辞**（`SessionsScreen.kt:391-402`：`刚刚` /
 * `N 分钟前` / `N 小时前` / `N 天前` / `N 周前`）。规则重复一次而不是复用，因为那个函数
 * 是私有的、而且那个文件属于 B4（本轮不许碰）。
 */
private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 7)} 周前"
    }
}
