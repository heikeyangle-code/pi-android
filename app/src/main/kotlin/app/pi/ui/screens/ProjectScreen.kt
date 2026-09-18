package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineSession
import app.pi.packages.AgentLayout
import app.pi.packages.ProjectTrust
import app.pi.packages.TrustRepository
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem
import app.pi.runtime.PtyLauncher
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.ui.BashRun
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
 * | ① 当前目录卡 | 工作区名 + 现场摘要 + 「切换」 | 当前工作区（`WorkspaceState.name` + `WorkspaceStore` 的 label）+ 下两段的读数 |
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
) {
    val state by session.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 工作区主目录由应用固定（`GuestWorkspacePath.RELATIVE`），`AgentLayout` 的
    // `hostProjectConfigDir()` 就是 `<workspace>/.pi`——pi 的项目资源根。
    //
    // 键是**工作区路径**，不只是 context：切换工作区换的是目录
    // （`PtyLauncher.workspaceHost` 走 `WorkspaceStore.currentHost`，`WorkspaceStore.setCurrent`
    // 只改设置里的名字），而 Activity 的 context 不变 —— `remember(context)` 会把这一屏
    // 钉在首次组合时那个工作区上，切换之后一路读旧目录。`PiSettingsStack.kt:206-212` /
    // `PiModelsScreen.kt:94-97` / `PiCredentialScreen.kt:122-125` 那三处是同一手法。
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

    // 工作区清单 —— 切换面板里那份「现有工作区」，也是 ① 卡上那个名字的 label 来源。
    // 取自引擎的真实入口（`session.workspaceEntries()` → `WorkspaceStore.list`），不是拿当前
    // 工作区的兄弟目录凑出来的 —— 名字、label、`isCurrent` 与切换时 `switchWorkspace` 用的是
    // 同一份判据。它是一次真实的磁盘读取，所以在 IO 线程上跑，不放在 composition 里。
    // 键里的 `revision` 是「换过工作区」的信号（`WorkspaceState`），切换之后重读一遍。
    var workspaceEntries by remember { mutableStateOf<List<WorkspaceStore.Entry>>(emptyList()) }
    LaunchedEffect(refreshTick, state.workspace.revision) {
        workspaceEntries = withContext(Dispatchers.IO) { session.workspaceEntries() }
    }

    // 当前工作区**叫什么**：一份真相，就是 ViewModel 发布的那份身份 —— 工作区**目录名**
    // （`WorkspaceState.name`，由 `WorkspaceStore.reconcile` 与 `switchWorkspace` 写），label 从
    // 上面那份 `workspaceEntries` 里按这个名字取（也就是切换面板里同一行印的名字）。
    //
    // 这里**原来**是从会话 cwd 推的（`state.meta.sessionFile` → 会话列表的 `cwd` →
    // `PiProject.workspaceName`）。那是第二份真相，而且是会过期的那一份：改过显示名之后
    // ① 卡印目录名、切换面板印 label（同一个工作区两个名字）；刚切完的头一两秒还会印着
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

    // 引擎没起来：②③ 没有数据，④⑤ 与全部文件操作照常。判据是引擎状态本身，不是转录的
    // 长度——转录为空既可能是引擎没起来，也可能是这个会话真的什么都没做。
    val engine = state.engine
    val engineDown = engine == null ||
        engine == PiEngineSession.EngineState.Stopped ||
        engine == PiEngineSession.EngineState.Failed

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

    // ------------------------------------------------------ 工作区级操作（非文件）

    /**
     * 新建工作区。
     *
     * 名字由引擎分配（下一个空闲的 `workspace-N`），**不在这里输入名字**：能改的只有
     * 显示名（label），而那正是重命名那一条。建完也**不切过去** —— 建一个目录和把引擎
     * 搬进它是两个决定，`WorkspaceStore.create` 的 KDoc 把这条写死了。
     */
    fun createNewWorkspace() {
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { session.createWorkspace() }) {
                is WorkspaceStore.Create.Ok -> {
                    say("已新建工作区 · ${result.entry.displayName}")
                    refreshTick++
                }
                is WorkspaceStore.Create.Failed -> say(result.message, StateTone.Warning)
            }
        }
    }

    /**
     * 重命名工作区：只写 label，目录不动（`WorkspaceStore.rename`）。
     *
     * 显示名是 label 而不是文件名，所以**不走** [WorkspaceFiles.nameProblem] 那套文件名
     * 规矩（可以带空格、可以留空）；留空与「填回目录名」由 `renameWorkspace` 自己裁决，
     * 它的 `Failed.message` 就是给人看的那句话。
     */
    fun renameWorkspaceTo(name: String, label: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { session.renameWorkspace(name, label) }
            when (result) {
                is WorkspaceStore.Rename.Ok -> {
                    say("已重命名工作区 · ${result.entry.displayName}")
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

    /** 用户对着那份读数点了「永久删除」：凭据原样交给 `deleteWorkspace`。 */
    fun deleteWorkspaceNow(preview: WorkspaceStore.Preview.Ok) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { session.deleteWorkspace(preview.confirmation) }
            when (result) {
                is WorkspaceStore.Delete.Ok -> {
                    say("已删除工作区 · ${result.name}（${result.files} 个文件）")
                    refreshTick++
                }
                // `Refused` / `Failed` 的 message 都是给人看的一句话，照原话说。
                is WorkspaceStore.Delete.Refused -> say(result.message, StateTone.Error)
                is WorkspaceStore.Delete.Failed -> say(result.message, StateTone.Error)
            }
        }
    }

    /**
     * 真的切过去。
     *
     * 调用点（[WorkspaceRootSheet] 的 `onPick`）**在调它之前就把面板关了**，这不是随手
     * 关窗：`switchWorkspace` 的四种返回里，`Refused` / `Failed` 的那句话是 VM 推进
     * `state.notices` 的，而通知是全应用那一条 M3 snackbar（`ExtensionUiHost`），画在
     * **主窗口**里 —— 面板是一层 `Dialog` 窗口，盖在它上面，面板开着的时候那条通知会
     * 被盖住、然后在 4 秒后照常被消费掉，用户什么都看不到。先关面板，四种返回才都看得见。
     * `Failed` 与 `Refused` 因而在这里无事可做：VM 已经把话说完了，屏幕不再拼一句。
     *
     * 协程跑在**引擎那个 scope**（`session.viewModelScope`）上，不是这一屏的
     * `rememberCoroutineScope()`：切换会把旧引擎停掉、再在新目录里起一个，中间有几秒的
     * 窗口期，而这一屏在用户点「对话」tab 时会被 `PiRoot` 整个卸掉。挂在屏上的 scope 会在
     * 那一刻把 `host.restart` 从中间取消 —— 旧引擎已经停了、新引擎没起来、`Failed` 那条
     * 回滚代码也不会执行。VM 自己的 KDoc 要的也正是「从 `viewModelScope` 里调」
     * （`switchWorkspace` 的 `Call from a main-dispatcher coroutine`），这是同一件事的
     * 屏幕侧写法。下面那几个非 suspend 的工作区增删改留在本屏 scope 上：它们是一次
     * `java.io.File` 操作，没有可被取消的中间态。
     */
    fun switchWorkspaceTo(entry: WorkspaceStore.Entry) {
        session.viewModelScope.launch {
            when (val result = session.switchWorkspace(entry.name, allowInterrupt = true)) {
                is WorkspaceSwitch.Ok -> {
                    // 新工作区是一个新的现场：文件浏览回到根目录，这一屏记的「我手工保存过
                    // 哪些文件」也作废（那些是旧工作区里的路径）。草稿那一半由
                    // `state.workspace.revision` 在 `ChatScreen` 里清（引擎已经把 revision
                    // 加一了）。
                    crumbs = emptyList()
                    localEdits = emptySet()
                    refreshTick++
                }
                is WorkspaceSwitch.AlreadyCurrent -> say("已经在这个工作区里。")
                is WorkspaceSwitch.Refused -> Unit
                is WorkspaceSwitch.Failed -> Unit
            }
        }
    }

    /**
     * 用户在设备目录浏览器里点了「就选这个目录」。
     *
     * 两件事，顺序固定：**先登记，再按已有的切换判定搬引擎**。
     *
     *  - 登记是宿主侧的一次设置写入（`app.workspace.external`，app 自己的 sidecar，**不进
     *    pi 的 `settings.json`**）。它走的是 [WorkspaceStore] 而不是 ViewModel 的转发，因为
     *    这个动作与引擎无关：登记一个目录不会动引擎、不会开会话；ViewModel 那边只有「切工作区」
     *    必须由它做（要 `engineTransition`、要重开会话）。ViewModel 的 settings 缓存与这里读的
     *    是同一个 `PiSettingsFileStore` 文档（按规范路径共享、`(size, mtime)` 变了就重读），
     *    所以这里写下去的值最多 250 ms 后在那边也看得到。
     *  - 搬引擎复用 [switchWorkspaceTo]，也就是**同一个** `decidePick`：正在跑的回合要先问过
     *    用户（`workspaceInterrupt` 那一格），不会因为「刚从选择器里出来」就默认可以杀掉一轮。
     *    用户绕了权限页和一层层目录才选中这个目录，所以登记完直接切过去是他说要的事；但杀回合
     *    仍然是另一件事，仍然要问。
     */
    fun useExternalDirectory(path: String) {
        scope.launch {
            val app = context.applicationContext ?: context
            when (val result = withContext(Dispatchers.IO) { WorkspaceStore.registerExternal(app, path) }) {
                is WorkspaceStore.Create.Ok -> {
                    val entry = result.entry
                    say("已加入工作区 · ${entry.displayName}")
                    refreshTick++
                    when (
                        WorkspaceChoice.decidePick(
                            isCurrent = entry.isCurrent,
                            turnRunning = session.wouldInterruptTurn(),
                            available = entry.available,
                        )
                    ) {
                        WorkspaceChoice.Pick.Unavailable ->
                            say("「${entry.displayName}」现在读不到，已登记但没有切过去。", StateTone.Warning)

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
                            // 稿子的原话（`workspace-final.html:1436-1438`）。**用户可见的文案里
                            // 不出现稿子的段号**（②③④⑤ 只该活在文档与 KDoc 里）；这里原来写的是
                            // 「②③ 暂时没有数据；④ 全部文件与 ⑤ 目录资源照常可用」。
                            "引擎没起来：正在跑与本次会话改过没有数据；下面的文件与资源照常可用，" +
                                "文件照样能打开 · 编辑 · 删除。"
                        } else {
                            "这个会话的项目现场。文件走宿主 File I/O，引擎没起来也能打开 · 编辑 · 删除。"
                        },
                        tone = if (engineDown) StateTone.Warning else StateTone.Muted,
                        glyph = if (engineDown) "!" else "·",
                    )
                }

                // 启动时被迫回退过工作区（设置里那个名字不可用）：VM 已经把这句话推过一次
                // notice，但那是一条 2.6 秒的提示；回退是**一直在**的状态（这一整条会话都跑在
                // 默认工作区里，设置也已被改写），所以在工作区名上面常驻一行。`note` 在切换成功
                // 之后由 VM 置回 null，这行随之消失。
                state.workspace.note?.let { note ->
                    item {
                        WsNotice(text = note, tone = StateTone.Warning, glyph = "!")
                    }
                }

                // -------------------------------------------------------- ①
                item {
                    CurrentDirectoryCard(
                        name = workspaceName,
                        // 计数用 `changed`（**含**正在写的那一条）：稿子的现场摘要是
                        // `'本次会话改过 ' + changed.length + ' 个文件'`，而 `changed` 里就带着
                        // pending 那条（`workspace-final.html:1290-1293`）。原来传的是
                        // `visibleChanged.size` —— 只存在一条 pending 写入时，摘要与 ③ 的段头
                        // 都会写「0 个文件」，而正下方那行写着「pi 正在写入这个文件」。
                        summary = summaryText(engineDown, changed.size, runs.size),
                        onClick = { rootSheet = true },
                    )
                }

                // -------------------------------------------------------- ②
                if (runs.isNotEmpty()) {
                    item {
                        WsSectionHeader(
                            label = "正在跑",
                            count = "${runs.size} 条命令",
                            aside = runningAside(runs),
                        )
                    }
                    for (index in runs.indices) {
                        val run = runs[index]
                        item(key = "run:${run.key}") {
                            RunningCommandRow(
                                run = run,
                                sliceIndex = index,
                                lastSliceIndex = runs.lastIndex,
                            )
                        }
                    }
                }

                // -------------------------------------------------------- ③
                item {
                    WsSectionHeader(
                        label = "本次会话改过",
                        count = when {
                            engineDown -> "无数据"
                            // 同上：段头计数是 `changed.length`，含 pending 那条
                            // （`workspace-final.html:1488` 的 `count={changed.length + ' 个文件'}`）。
                            changed.isEmpty() -> "0 个文件"
                            else -> "${changed.size} 个文件"
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
                // ③ 整段是一张卡：稿子把它放在 `Rows→Card` 里（`workspace-final.html:437-449`），
                // 于是行内容从屏边 14+12=26 起。这里用 [WsCardSlice] 逐片带卡壳与行间线 ——
                // 一个 item 一张整卡会把 ③ 的行数上限绑死在一次组合里（见该构件的 KDoc）。
                if (engineDown) {
                    item {
                        WsCardSlice(index = 0, lastIndex = 0) {
                            WsRow(
                                title = "引擎没起来，读不到本次会话的改动",
                                // 稿子这一行是 `file` 图标（`:1476`），不是文件夹：它说的是
                                // 「读不到改动」，不是「这里是个目录」。
                                lead = { FileGlyph() },
                                meta = "下面的文件与资源照常可用；改写过的文件自己打开也一样能看。",
                            )
                        }
                    }
                } else if (visibleChanged.isEmpty() && pendingWrite == null) {
                    item {
                        WsCardSlice(index = 0, lastIndex = 0) {
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
                    }
                } else {
                    val pending = pendingWrite
                    val totalSlices = (if (pending != null) 1 else 0) + visibleChanged.size
                    if (pending != null) {
                        item(key = "pending:${pending.path}") {
                            WsCardSlice(index = 0, lastIndex = totalSlices - 1) {
                                PendingWriteRow(pending)
                            }
                        }
                    }
                    visibleChanged.forEachIndexed { index, file ->
                        val slice = (if (pending != null) 1 else 0) + index
                        item(key = "changed:${file.path}") {
                            WsCardSlice(index = slice, lastIndex = totalSlices - 1) {
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
                }

                // -------------------------------------------------------- ④
                item {
                    WsSectionHeader(
                        label = "全部文件",
                        count = if (listingError != null) null else "${listing?.size ?: 0} 项",
                        aside = if (listingError != null) {
                            null
                        } else {
                            // 稿子这一颗是**无描边无底的 accent 文本 + 13 的加号**
                            // （`workspace-final.html:1496-1499`），不是胶囊：整屏唯一的主按钮
                            // 语言（实底 accent）在空目录那一颗上，这里只是段头的一个文本动作。
                            { WsSectionAction(label = "新建", onClick = { newSheet = true }) }
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
                        WsCardSlice(index = 0, lastIndex = 0) {
                            WsRow(
                                title = "正在读这个目录…",
                                lead = { FolderGlyph() },
                            )
                        }
                    }
                } else if (entries.isEmpty()) {
                    item {
                        // 空态也在卡里（稿子把它放在 `Rows` 内：`workspace-final.html:1526-1541`），
                        // 内边距是它的 `padding:'20px 14px 18px'`。
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
                                Text(
                                    "这个目录是空的",
                                    // 稿子是 `t13 c-muted`；本 App 取 12 的 `meta`（同 `WsNotice`）。
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                                Spacer(Modifier.height(WS_EMPTY_DIR_BUTTON_GAP))
                                WsChip(
                                    text = "新建文件",
                                    glyphIcon = Icons.Filled.Add,
                                    solid = true,
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
                                    modifier = Modifier.padding(top = WS_EMPTY_DIR_HINT_GAP),
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                            }
                        }
                    }
                } else {
                    entries.forEachIndexed { index, entry ->
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
                            WsCardSlice(index = index, lastIndex = entries.lastIndex) {
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
                                // 稿子这一句是 `padding:'0 14px 10px'`（`:1512-1514`）。
                                bottom = WS_SOURCE_NOTE_BOTTOM,
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
                            // 稿子分段行是 `padding:'0 14px 8px'`（`:1533-1535`）。
                            bottom = WS_SEG_BOTTOM,
                        ),
                    )
                }
                val shownResources = resources.filter { it.kind == resourceKind }
                if (shownResources.isEmpty()) {
                    item {
                        WsCardSlice(index = 0, lastIndex = 0) {
                            WsRow(
                                title = "这一类还没有资源",
                                // 空态说的是「这一类没有」，不是「这里有个目录」。
                                lead = { FileGlyph() },
                                meta = "把「${resourceKind.label}」放进 .pi、.agents 或全局目录之后，" +
                                    "点这一屏的刷新键就能看到它。",
                            )
                        }
                    }
                } else {
                    item {
                        WsCard {
                            shownResources.forEachIndexed { index, resource ->
                                if (index > 0) WsHairline()
                                Box(
                                    // **只压暗「项目 .pi」那一族，这是与稿子的一处有意偏离。**
                                    // 稿子在未受信任时把整段都套了 `opacity:.5`
                                    // （`workspace-final.html:1549`），但受这个信任决定影响的只有
                                    // 从**这个项目**里读来的 .pi 资源：`.agents`、全局目录与已安装
                                    // 资源包里的资源照旧会被 pi 加载（`PiResourceDiscovery` 的来源
                                    // 优先级就是这件事），把它们一起压暗等于谎报「这些也不能用」。
                                    // 每一行仍然另有文字层的说明（`ResourceRow.resourceMeta`），
                                    // 所以颜色不是唯一信号。
                                    Modifier.alpha(
                                        if (projectUntrusted && resource.source == WorkspaceSource.ProjectPi) {
                                            0.5f
                                        } else {
                                            1f
                                        },
                                    ),
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
            entries = workspaceEntries,
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
                        say("「${entry.displayName}」现在读不到（可能是卡被拔出、正在卸载，或者权限被撤销了），没有切换。", StateTone.Warning)

                    WorkspaceChoice.Pick.AlreadyHere -> say("已经在这个工作区里。")
                    // 会中断一轮正在跑的回合：先问一次再切（`workspaceInterrupt` 那一格）。
                    WorkspaceChoice.Pick.ConfirmInterrupt -> workspaceInterrupt = entry
                    WorkspaceChoice.Pick.Switch -> switchWorkspaceTo(entry)
                }
            },
            onNew = {
                rootSheet = false
                createNewWorkspace()
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
                    title = "重命名工作区",
                    where = entry.relative,
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

    // ------------------------------------------------------ 浮层：删除工作区的确认
    workspaceDelete?.let { preview ->
        val target = preview.target
        PiDialog(onDismissRequest = { workspaceDelete = null }) {
            PiDialogTitle(
                title = "删除工作区",
                glyph = "!",
                glyphTone = PiTheme.palette.error,
            )
            // 路径一律等宽（规则 #7）：这是工作区在磁盘上的绝对路径，不是一句话。
            PiDialogBody(target.host.absolutePath, mono = true)
            if (target.removesFiles) {
                // 要展示的就是引擎数出来的那三个数：文件数 / 目录数 / 总字节。用户同意的是
                // **这一次统计**，而 `confirmation` 是它唯一的凭据（`deleteWorkspace` 只收它）。
                PiDialogBody(
                    "这个工作区里有 ${target.files} 个文件 / ${target.dirs} 个目录，" +
                        "共 ${WorkspaceFiles.formatSize(target.bytes)}。" +
                        "删除后永久消失，不进回收站，也恢复不了。",
                )
            } else {
                // 外部工作区：**一个字都不许让用户以为文件会消失**。这不是「删除」，是取消登记
                // （`WorkspaceStore.delete` 的外部那一条只改设置，从不碰磁盘）。
                PiDialogBody(
                    "这是设备上的目录，不是 App 建的：这一个动作只是把它从工作区列表里去掉，" +
                        "**目录和里面的文件一个都不会动**。想切回来，重新「从设备目录选择」同一个目录就行。",
                )
            }
            PiDialogActions {
                PiDialogAction(label = "取消", primary = false, onClick = { workspaceDelete = null })
                PiDialogAction(
                    // 按钮上的字必须与后果一致：外部工作区不删文件，就不该写着「永久删除」。
                    label = if (target.removesFiles) "永久删除" else "取消登记",
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
                title = "切换会中断正在运行的回合",
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody("即将切到「${entry.displayName}」。")
            PiDialogBody(
                "切换会中断正在运行的回合（模型调用与工具都会被停掉），新工作区从空白会话开始。",
            )
            PiDialogActions {
                PiDialogAction(label = "取消", primary = false, onClick = { workspaceInterrupt = null })
                PiDialogAction(
                    label = "仍然切换",
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
            onSubmit = { typed ->
                // 工作区显示名不是文件名：它可以是带空格的一句话，`nameProblem` 那套规矩
                // （不许 `/`、不许 `..`、不许为空…）在这里不适用，交给 `renameWorkspace`
                // 裁决 —— 它的 `Failed.message` 就是给人看的那句话。
                val problem = if (dialog.kind == WorkspaceInputKind.RenameWorkspace) {
                    null
                } else {
                    WorkspaceFiles.nameProblem(typed)
                }
                if (problem == null) {
                    inputDialog = null
                    when (dialog.kind) {
                        WorkspaceInputKind.NewFile -> {
                            val parent = File(workspace, dialog.relativeParent.orEmpty())
                            val relative = listOf(dialog.relativeParent, typed)
                                .filterNotNull()
                                .filter { it.isNotEmpty() }
                                .joinToString("/")
                            runFileOp(
                                what = "已新建文件 · $typed",
                                onDone = { ok ->
                                    // 稿子：「建完直接进编辑态，写什么由你。」
                                    if (ok) {
                                        openRelative(relative, File(parent, typed), startInEdit = true)
                                    }
                                },
                            ) {
                                WorkspaceFiles.createFile(parent, typed)
                            }
                        }

                        WorkspaceInputKind.NewDir -> runFileOp("已新建文件夹 · $typed") {
                            WorkspaceFiles.createDir(File(workspace, dialog.relativeParent.orEmpty()), typed)
                        }

                        WorkspaceInputKind.Rename -> {
                            val target = File(dialog.absoluteParent ?: workspace, dialog.initial)
                            runFileOp("已重命名 · $typed") { WorkspaceFiles.rename(target, typed) }
                        }

                        WorkspaceInputKind.RenameWorkspace -> dialog.workspaceName?.let { target ->
                            renameWorkspaceTo(target, typed)
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
            // 被删的那个路径：等宽（规则 #7）。
            PiDialogBody(target.path, mono = true)
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
                // 稿子这里是 `Icon n="right" s={14}`（`workspace-final.html:1452`）：一颗 14px
                // 的描边尖括号。原来画的是 `Text("›")` —— 一个标点，笔画粗细跟着字重走，
                // 与旁边两个真图标的笔画不是一回事。
                Icon(
                    imageVector = WsChevronRightGlyph,
                    contentDescription = null,
                    modifier = Modifier.size(WS_CARD_CHEVRON),
                    tint = PiTheme.palette.muted,
                )
            }
        }
    }
}

/** 左缘 accent 条的高度（v2 的 `top:8;bottom:8` 撑出来的那一段）。 */
private val CURRENT_BAR_HEIGHT = 40.dp

/** 稿子 ① 卡右端那颗 chevron 的尺寸（`Icon n="right" s={14}`）。 */
private val WS_CARD_CHEVRON = 14.dp

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

/** 稿子「来源按 pi 的优先级排」那一句的 `padding:'0 14px 10px'`。 */
private val WS_SOURCE_NOTE_BOTTOM = 10.dp

/** 稿子资源分段行的 `padding:'0 14px 8px'`。 */
private val WS_SEG_BOTTOM = 8.dp

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
     * 刻度，不能只靠它。转录里的 `bash` / `powershell` 调用带着自己的 `ts`，于是这里照
     * `blocks/ShellBlock` 的既有做法现算（`System.currentTimeMillis() - ts`，**不自己起计时器**，
     * 一次组合读一次）。`!` / `!!` 那条来自 `BashRun`，它没有时间戳可算，所以那一条仍然没有
     * 读数（见 [runningCommands]）。
     */
    val startedAt: Long?,
    /** pi 报过的时长（调用结束后才有值）；有值时以它为准，不再现算。 */
    val reportedMs: Long?,
    val lines: Int,
    val inContext: Boolean,
) {
    /** 该显示给用户的时长：pi 报过的优先，其次按 [startedAt] 现算。 */
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
                "进入上下文",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    runs.none { it.inContext } -> {
        {
            Text(
                "不进上下文",
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
 * （= `endedAt - ts`，结束后才有），流式中则按行自己的 `ts` 现算 —— 这一屏**不自己计时**，
 * 那量的是手机的重组，不是命令。
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
 */
@Composable
private fun RunningCommandRow(run: RunningCommand, sliceIndex: Int, lastSliceIndex: Int) {
    val now = System.currentTimeMillis()
    val elapsed = run.elapsedMs(now)
    Column(Modifier.fillMaxWidth()) {
        WsCardSlice(index = sliceIndex, lastIndex = lastSliceIndex) {
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
                // 稿子的副行只有这三个读数（`workspace-final.html:1471`）：`运行中 · 已运行
                // 12.3 秒 · 18 行`。「进入上下文/不进上下文」不在这里 —— 它由段头的 aside 说一次
                // （见 [runningAside]）。
                meta = buildString {
                    append("运行中")
                    elapsed?.let { append(" · 已运行 ").append(secondsText(it)) }
                    if (run.lines > 0) append(" · ").append(run.lines).append(" 行")
                },
                metaMono = true,
                trail = {
                    app.pi.ui.theme.DurationMeter(
                        ms = elapsed,
                        color = PiTheme.palette.warning,
                        contentDescription = "这条命令已运行 " +
                            (elapsed?.let { secondsText(it) } ?: "未知"),
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
    String.format(Locale.US, "%.1f 秒", ms / 1000.0)

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
            badge = {
                WsBadge(text = "本次会话", tone = StateTone.Accent, glyph = "●")
            },
            meta = null,
            trail = {
                if (pending.receivedLines > 0) {
                    app.pi.ui.theme.DurationMeter(
                        ms = MID_WRITE_TICK_MS,
                        color = palette.warning,
                        contentDescription = "pi 正在写入这个文件",
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
            title = changedTitle(file.path),
            mono = true,
            strong = true,
            lead = { FileGlyph() },
            badge = {
                WsBadge(text = "本次会话", tone = StateTone.Accent, glyph = "●")
            },
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
 *
 * ## 会换行，而且每一段是 `maxWidth` 而不是定宽
 *
 * 稿子这一行是 `className="rw w-crumb"` + `flexWrap:'wrap'`（`workspace-final.html:1500`），
 * 每一段是 `maxWidth:170`（`.w-crumb :189` 的 `min-width:0` 加 inline 的 `maxWidth`）。
 * 这一屏原来把每段钉成 `width(170dp)`：三段就是 534dp，一行的可用宽远小于它，于是**最后一段
 * （当前目录）被推出屏外** —— 一段也看不见当前在哪。这里改成 `widthIn(max = 170)` + `FlowRow`：
 * 名字短就按内容宽、长就截到 170，整行放不下就折到第二行。
 *
 * 选 [FlowRow] 而不是横向滚动：稿子写死的就是 `flexWrap`，而且「当前目录必须在屏内」这条要求
 * 只有换行能无条件满足 —— 横向滚动时最后一段仍然要先滑一下才看得见。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreadcrumbRow(crumbs: List<String>, onGo: (Int) -> Unit) {
    val palette = PiTheme.palette
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                // 稿子这一行的 `padding:'0 14px 8px'`。
                bottom = WS_CRUMB_BOTTOM,
            ),
        verticalArrangement = Arrangement.Center,
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

/** 稿子面包屑那一行的 `padding:'0 14px 8px'`。 */
private val WS_CRUMB_BOTTOM = 8.dp

/** 稿子的 `mid(n, 16)`。 */
private const val CRUMB_MAX_CHARS = 16

@Composable
private fun Crumb(text: String, mono: Boolean, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            // `widthIn(max = …)`，不是 `width(…)`：稿子每一段是 `maxWidth:170`
            // （`workspace-final.html:1500` + `.w-crumb`）：短名字按内容宽排，一行放不下就折行。
            // 定宽 170 让「工作区 / src / session」恒占 534dp，当前目录必然被挤出屏外。
            .widthIn(max = CRUMB_MAX_WIDTH),
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

        // 稿子给二进制**单加了一个图标态**（`workspace-final.html:311` 的 `Icon n="bin"`：
        // 文件轮廓 + 右下角一块实心矩形）。原来这里画的是字符 `▤` —— 一个标点当图标，
        // 与旁边两个真矢量笔画不同源，字号一变就不齐。
        WorkspaceEntryKind.Binary -> Icon(
            imageVector = WsBinaryFileGlyph,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = PiTheme.palette.muted,
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
        // 稿子的资源行**没有 `lead`**（`workspace-final.html:1379-1389` 的 `resRow`：
        // title / badge / meta / value / trail，没有图标）：资源是一份文件，不是目录，而
        // 每行都挂一个文件夹图标既说谎又把来源徽标挤到第二位。删除于本轮审查。
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
        meta = resourceMeta(resource, untrusted = untrusted, piRow = piRow),
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

/**
 * 资源行的副行。
 *
 * 稿子的两条规则不一样（`workspace-final.html:1369-1377`）：
 *
 *  - **解析失败**：副行**换成**原因（`meta = r.reason`）—— 解析不了的时候
 *    `description` 本身就没读到，留着它只会占地方；
 *  - **其余状态**（含**已禁用**）：副行是 `description`，**不追加**原因 —— 禁用的原因是
 *    「包设了 `autoload:false` / 筛选没命中」这类设置事实，而状态词「已禁用」已经说了结论。
 *
 * 原来两种状态都往 description 后面追加原因，副行就成了半句描述 + 半句原因。
 *
 * [untrusted] 只标「项目 .pi」那一族（稿子是整段压暗，见 `WorkspaceResourcesCard` 里
 * `alpha` 的注释）：只有从**这个项目**读来的资源才受项目信任决定影响。
 */
private fun resourceMeta(resource: WorkspaceResource, untrusted: Boolean, piRow: Boolean): String {
    val parts = buildList {
        if (resource.status == WorkspaceResourceStatus.ParseFail) {
            (resource.reason ?: resource.description)?.takeIf { it.isNotBlank() }?.let { add(it) }
        } else {
            resource.description?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (untrusted && piRow) add("不会加载（项目未受信任）")
    }
    return parts.joinToString(" · ").ifEmpty { resource.displayPath }
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
    onClose: () -> Unit,
    onPick: (WorkspaceStore.Entry) -> Unit,
    onNew: () -> Unit,
    onPickExternal: () -> Unit,
    onMenu: (WorkspaceStore.Entry) -> Unit,
) {
    WsSheet(
        title = "切换工作区",
        onClose = onClose,
        subtitle = "工作区就是 pi 的现场目录；App 私有目录里的、设备目录里的都算。",
        footer = "换工作区等于换一个现场：会新建一个 pi 会话，当前会话不会被删除。",
        maxBodyHeight = ROOT_SHEET_BODY_MAX,
    ) {
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
            entries.forEachIndexed { index, item ->
                if (index > 0) WsHairline()
                val renamed = item.label != item.name
                WsRow(
                    // 显示名是 label；它就是 `switchWorkspace` 里那条 notice 印的名字。
                    title = item.displayName,
                    // 稿子的规矩：路径型的东西等宽。目录名是路径段，label 是人取的名字。
                    mono = !renamed,
                    strong = true,
                    lead = { FolderGlyph() },
                    badge = if (renamed || item.isCurrent || item.external || !item.available) {
                        {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
                            ) {
                                // 改过显示名之后，身份（目录名）还得看得见：pi 的会话与信任都挂在
                                // 目录路径上，而不是这个名字上。
                                if (renamed) {
                                    WsBadge(text = "", tone = StateTone.Muted, mono = item.name)
                                }
                                // 外部工作区标出来：它的文件在设备上，删除只是取消登记。
                                if (item.external) {
                                    WsBadge(text = "设备", tone = StateTone.Muted)
                                }
                                if (!item.available) {
                                    WsBadge(text = "读不到", tone = StateTone.Warning)
                                }
                                if (item.isCurrent) {
                                    WsBadge(text = "当前", tone = StateTone.Accent, glyph = "●")
                                }
                            }
                        }
                    } else {
                        null
                    },
                    meta = item.relative,
                    metaMono = true,
                    trail = { WsMoreButton(onClick = { onMenu(item) }) },
                    onClick = { onPick(item) },
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
            // 「从设备目录选择」：把设备上一个**真目录**登记成工作区。
            //
            // 它要的是「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），**不是** SAF：工作区必须是
            // 真实文件路径 —— 引擎把它 bind 进 guest 并当 cwd（`PiEngineHost.kt:407`、`:451`），
            // 而文档选择器给的是内容 URI，绑不了。这一句在说明页里也印（`WorkspaceExternalIntro`），
            // 免得用户以为随便给个授权就够了。
            //
            // 这条行没有「未接」徽标了：它现在是一条真能走的路。有没有权限是**点下去之后**才知道
            // 的事（`WorkspaceExternalPicker` 自己判定并给出说明），所以这里不预判 —— 一行灰着的
            // 徽标会让有权限的用户以为它还没做。
            WsRow(
                title = "从设备目录选择（需授权）",
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = { FolderGlyph() },
                meta = "选一个设备上的目录当工作区：文件留在原处，只有 .pi 项目资源会从那里读。",
                onClick = onPickExternal,
            )
        }
        Text(
            "新建的工作区落在 App 私有目录里，不需要授权；「从设备目录选择」那一条要「所有文件访问」，" +
                "选中的目录不会被复制也不会被移动 —— 取消登记只是不再列在这里，文件一直留在设备上。",
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
        subtitle = entry.relative,
        subtitleMono = true,
    ) {
        MenuAction("重命名", onRename)
        MenuAction("删除", onDelete, tone = PiTheme.palette.error)
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
 *
 * 壳是 [WsSheet]：标题是 `mid(文件名, 22)`（稿子 `:1613`），副标题是**等宽**的完整路径
 * （稿子那儿是一个 `span.mono`），动作行自己带 `12px 14px` 的内边距，所以头下面不再垫
 * 一个空档 —— 头的 8 加上行的 12 就是稿子的那道缝。
 */
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
    WsSheet(
        title = WorkspaceFiles.middleEllipsis(target.file.name, MENU_TITLE_MAX_CHARS),
        onClose = onClose,
        subtitle = target.path,
        subtitleMono = true,
    ) {
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

/**
 * 「新建」先问文件还是文件夹（稿子的 `dlg.kind === 'new'`，`:1623`）：同一支 [WsSheet]，
 * 标题「新建」、副标题「建在「…」里」、两条选择各自带 `12px 14px` 内边距。
 */
@Composable
private fun WorkspaceNewSheet(
    where: String,
    onClose: () -> Unit,
    onNewFile: () -> Unit,
    onNewDir: () -> Unit,
) {
    WsSheet(
        title = "新建",
        onClose = onClose,
        subtitle = "建在「$where」里",
    ) {
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

private enum class WorkspaceInputKind { NewFile, NewDir, Rename, RenameWorkspace }

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
    onSubmit: (String) -> Unit,
) {
    var name by remember(dialog) { mutableStateOf(dialog.initial) }
    val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
    PiAutoFocus(focus)
    PiDialog(onDismissRequest = onClose) {
        PiDialogTitle(title = dialog.title)
        // 这一行永远是路径/位置（`dialogSub` 是重命名时那条绝对路径，`where` 是
        // 「工作区 / src」这种拼法）—— 规则 #7：机器产出的路径走等宽。
        PiDialogBody(dialog.dialogSub ?: dialog.where, mono = true)
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
            when (dialog.kind) {
                WorkspaceInputKind.Rename -> "只改文件名，内容不动。"
                // 显示名（label）与目录名是两回事：改它不移动目录，所以 pi 的会话记录与
                // 项目信任（都挂在目录路径上）一条都不会丢 —— 这是 `WorkspaceStore.rename`
                // 特意选的做法，输入框旁边得把这件事说出来。
                WorkspaceInputKind.RenameWorkspace -> "只改显示名：目录名与路径不动，pi 的会话和项目信任都挂在路径上。"
                else -> "建在当前目录下。"
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
 * `bodyMedium` + 500）—— 那是全应用同一套「符号 + 颜色 + 一句话」的编码，只是那一支挂在
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
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
        )
        if (action != null) {
            Text(
                action.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(SNACK_ACTION_RADIUS))
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .padding(vertical = SNACK_ACTION_PADDING_VERTICAL),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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
