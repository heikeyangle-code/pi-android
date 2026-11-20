package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import app.pi.bridge.DeviceCapabilityStore
import app.pi.host.HostAdvanced
import app.pi.packages.EngineRestartCoordinator
import app.pi.rpc.PiResponses
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.runtime.RuntimePreferences
import app.pi.runtime.RuntimeSelection
import app.pi.ui.PromptFileFacts
import app.pi.ui.components.PiSheet
import app.pi.ui.device.DeviceCapabilityScreen
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The whole settings stack in one composable: level 0 home, level 1 group,
 * level 2 search (spec §6.1).
 *
 * It exists so the parent can wire the 设置 destination with a single call and
 * get correct back behaviour for free — predictive back walks the stack instead
 * of leaving the app, which is the rule the spec sets in §3.1. The individual
 * screens are still public, so a nav-graph based host can call them directly.
 *
 * Pass [store] to bind the real runtime store; without it the stack falls back to
 * an in-memory store seeded with pi's defaults.
 *
 * ## The branches that are not pi settings at all
 *
 * 设备能力, 扩展包 and the credential form are **app-side screens with no pi
 * counterpart**: pi has no notion of the phone it runs on, no package UI outside
 * its CLI, and no `models.json` editor. They are branches of this stack rather
 * than groups in the catalog because the catalog is a transcription of pi's own
 * settings documents, and mixing app-only state into it would make the two
 * indistinguishable (`docs/pi-android-app-design.md` §21).
 */
@Composable
fun PiSettingsStack(
    contentPadding: PaddingValues,
    /**
     * 顶栏左侧的返回箭头：底栏拆掉之后，设置首页只能回到对话。
     *
     * 它与内部层级共用下面那一个 `BackHandler`（内层 → 上一级，首页 → 这里），
     * 所以全应用仍然只有一处「导航返回」的注册，不会和覆盖层的返回抢按键。
     */
    onBack: () -> Unit,
    store: PiSettingsStore? = null,
    /** Every theme the app can load, so the picker is not limited to `themes`. */
    knownThemes: List<PiThemeEntry> = emptyList(),
    /** Caveats of the theme in effect, shown with the picker. */
    themeNotes: List<String> = emptyList(),
    themeError: String? = null,
    /** Called after any write, so app-side readers can re-read the key. */
    onSettingWritten: (String) -> Unit = {},
    onRunAction: ((PiSetting) -> Unit)? = null,
    /**
     * The engine's restart, for the two branches that change files pi only reads
     * at startup: the package screen (through `EngineRestartCoordinator`) and the
     * credential form (`models.json`, see [app.pi.ui.models.ModelsScreen]). Null keeps both
     * screens honest: they say the restart is not wired instead of offering a
     * button that cannot work.
     */
    restartEngine: (suspend (reason: String, allowInterrupt: Boolean) -> EngineRestartCoordinator.Outcome)? = null,
    /** Live turn state, so a restart is refused rather than interrupting a turn. */
    isTurnRunning: () -> Boolean = { false },
    /** 扩展 / MCP 页问宿主的入口；引擎没起来时返回 null。 */
    extensionsAccess: () -> app.pi.host.ExtensionsAccess? = { null },
    /**
     * `get_available_models` — pi's own metadata for the models it already has,
     * used to label what a scan matched and what fell back to app defaults. It is
     * the only model list pi exposes over RPC (`rpc-types.ts:20-74`); no command
     * returns the built-in catalog itself.
     */
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    /** 宿主的高级命令入口；引擎没跑时返回 null（「引擎」页据此显示说明）。 */
    advanced: () -> app.pi.host.HostAdvanced? = { null },
    /** 「模型与供应商」页的宿主能力；引擎没跑时返回 null。 */
    modelsHost: () -> app.pi.ui.models.ModelsHost? = { null },
    /**
     * A key to open this stack on, e.g. `enabledModels` for pi's
     * `/scoped-models` (see `NavRequest.SettingsFocus`). Consumed once, through
     * [onFocusConsumed], so re-entering the destination does not re-navigate.
     */
    focusKey: String? = null,
    onFocusConsumed: () -> Unit = {},
    /**
     * Called after a screen wrote pi's files through a layer of its own (the
     * hand-edited files), so the owner of the settings store
     * can drop its cached documents. The store is the ViewModel's, so the
     * invalidation lives there — this stack only reports that a write happened.
     */
    onExternalSettingsWrite: () -> Unit = {},
    /**
     * The engine's last-exit facts, for 导出诊断报告. A lambda rather than a value
     * because the capture happens in the ViewModel the moment the engine dies, and
     * the screen must read the newest one — including a death that happens while
     * the screen is composed.
     *
     * The default ([null]) keeps the screen honest: with no engine owner wired it
     * says the exit is not recorded instead of inventing one.
     */
    engineDiagnostics: () -> EngineDiagnostics? = { null },
    /**
     * The failures the app has already recorded (boot failure, last RPC error,
     * extension notices), for the report's 最近的失败 section. A lambda for the
     * same reason as [engineDiagnostics].
     */
    recentFailures: () -> List<String> = { emptyList() },
    /**
     * Which of pi's two entries this process's engine was launched on
     * (`PiEngineHost.EngineEntry.label`), for the report's 引擎入口 section. A lambda for the
     * same reason as [engineDiagnostics]: the host records it at launch, and the report must
     * read the newest value — including a launch that happened while this screen is composed.
     */
    engineEntry: () -> String? = { null },
    /**
     * 设置首页那一行「终端」要打开的全屏终端页。
     *
     * 终端从底部目的地降为首页一行（`03-navigation-decision.md:24`），但它的屏幕不是
     * 这个栈里的一级：它盖住整屏、由 `PiRoot` 的覆盖层状态承载，而 `PiRoot` 又是唯一
     * 管返回键的地方。所以这里只把「用户点了那一行」转成一个 lambda 交出去，栈自己不开
     * 这一屏，也不新增一个内部层级。
     *
     * `null` 时那一行不画（与 设备能力 / 扩展包 / 开源许可 三行的约定一致）。
     */
    onOpenTerminal: (() -> Unit)? = null,
    /**
     * 「运行时加速（实验性）」开关**当场生效**的进度（`PiSessionViewModel.runtimeSwitch`）。
     *
     * 拨完那个开关要跑一次门禁探针、10~60 秒，通过后再重启引擎。这段时间屏幕上必须说它在
     * 跑，否则用户看到的就是「点了开关什么都没发生」——这个改动要消灭的正是那个形状。用的
     * 是这一屏**已有的**那两个位置：`运行时（实际生效）` 那一行的值（见 [runtimeStatusText]）
     * 和它下面那块逐阶段记录；不新造进度控件、不新造弹窗。
     *
     * 值同时是那个 `LaunchedEffect` 的键：动作结束回到 `Idle` 时它变一次，状态行于是重读
     * `RuntimeSelection.status()` —— 探针的新结论、被回滚的开关值都在那一刻落到行上。
     */
    runtimeSwitch: RuntimeSwitchAction.Step = RuntimeSwitchAction.Step.Idle,
) {
    val activeStore = store ?: rememberInMemoryPiSettingsStore()
    // Every level of this stack is `rememberSaveable`, not `remember`: the settings
    // destination is hosted in a `SaveableStateProvider`, so rotation and process
    // recreation used to drop the user back on 设置首页 with the search text and the open
    // group gone — while the whole point of that provider is that this screen survives
    // (`docs/settings-audit-impl.md` §B15). All of these are String?/Boolean, so they save
    // as they are; editor state that holds a `PiSetting` deliberately does not.
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The device capability screen is the one branch of this stack that is not a
    // group in pi's settings catalog — the switches grant the *phone's* abilities
    // to the agent, and pi has no such settings (docs/pi-android-app-design.md §21).
    // 设置首页的「其他」一屏：终端 / Pi 文件 / 设备能力 / 扩展包 / 安全与隐私 / 开源许可。
    var other by rememberSaveable { mutableStateOf(false) }
    var deviceCapabilities by rememberSaveable { mutableStateOf(false) }
    // 「扩展与资源」枢纽（`ui/resources/ResourcesHubScreen`）：扩展 / 技能 / 提示 / 主题 / 上下文 / 包 / MCP / 项目信任都从这里进。
    // 它跳去 MCP、Pi 文件、引擎、设置组时自己保持 true，所以那些页返回就回到枢纽，不用另记来路。
    var resources by rememberSaveable { mutableStateOf(false) }
    var resourcesProject by rememberSaveable { mutableStateOf(false) }
    // 「模型与供应商」（`ui/models/ModelsScreen`）：统一承载模型选择、订阅/API Key 登录、自定义供应商、推理与缓存设置。
    var modelFlow by rememberSaveable { mutableStateOf(false) }
    // The open-source licence notices. Not a pi screen either — see §L: publishing
    // the licences of what this app redistributes is the distributor's obligation,
    // so the screen is the app's own.
    var licenses by rememberSaveable { mutableStateOf(false) }
    // 设置 → 运行时与诊断 → 导出诊断报告（`app.runtime.diagnostics`）。
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    // 设置首页「MCP 服务器」（`McpServersScreen`）：pi 的 `mcp.json` 编辑器。
    var mcp by rememberSaveable { mutableStateOf(false) }
    // 归位自原 EngineScreen 的轻量动作浮层：查看生效系统提示词 / 问题报告 / 实时工具启停。
    var textSheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var liveToolsOpen by remember { mutableStateOf(false) }
    var actionNote by remember { mutableStateOf<Pair<String, String>?>(null) }
    val clipboard = LocalClipboardManager.current

    // The 运行时 group's four read-only rows: their facts live in the runtime
    // tree and in the engine's foreground service, not in the settings store, so
    // they are read here and passed down as overrides. The read is a directory
    // walk plus a few small files, hence IO, hence a state that starts null
    // (rows show 未读取 for exactly one frame).
    val context = LocalContext.current
    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val runtimeFacts = remember(paths) { RuntimeFacts(paths = paths) }
    var facts by remember { mutableStateOf<RuntimeFacts.Snapshot?>(null) }
    LaunchedEffect(runtimeFacts) {
        facts = withContext(Dispatchers.IO) { runtimeFacts.read() }
    }

    // A restart asked for from the 进程 section. It is not routed through
    // `EngineRestartCoordinator`: that machine answers "a *resource* change is
    // waiting to be picked up", while these are process options that only take
    // effect on a fresh process. The rail that matters — never interrupting a
    // turn — is the engine's own (`PiEngineHost.restart` refuses while `Busy`),
    // and this passes `allowInterrupt = false`, so a running turn is a refusal
    // with the engine's own sentence, not a killed turn.
    var restartPrompt by remember { mutableStateOf(false) }
    var restartNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // External-change epoch. `PiSettingsFileStore` caches both documents until the
    // app itself writes one, so a file edited by pi, a terminal, or the AI kept
    // showing the old value. `PiDirectoryWatch` reports real changes (inotify while
    // this stack is composed, plus one mtime/size comparison on ON_RESUME); each
    // report drops that cache and bumps this epoch, which re-builds the group rows
    // and therefore re-reads the values. No timer, and nothing at all while the
    // settings destination is not on screen.
    var filesEpoch by remember { mutableStateOf(0) }

    // 本 App 自己写完一个设置之后的 epoch —— 行能不能刷新，只由它保证。
    //
    // 行是在 composition 里读 `store.read(...)` 的，而真实 store（`PiSettingsFileStore`）
    // **不是快照状态**：`read()` 只是普通函数，文档缓存在 `@Volatile var cached` 上
    // （`settings/PiSettingsFileStore.kt:260-304`），所以写它谁也订阅不到。对比
    // `InMemoryPiSettingsStore`（`PiSettingsInMemory.kt:20-31` 的 `mutableStateMapOf`），
    // 那里的 KDoc 明说「写一个设置只重组显示它的那些行」（`:11-13`）——预览与测试因此
    // 看不出这个缺陷，真机才看得到。
    //
    // 写完之后的唯一通知是 `onSettingWritten` → `PiSessionViewModel.onSettingWritten`
    // （`PiSessionViewModel.kt:1242-1277`）里的 `refreshPrefs()` / `refreshTheme()`，也就是
    // `_state` / `_theme` 两个 StateFlow。而设置目的地**不读**这两个流：`PiRoot` 只把
    // `uiState` 用在 `navRequest` 上（`PiRoot.kt:587-588`，它自己的说明在 `:843`），行读的是
    // store。strong skipping 下不稳定参数按 `===` 比较、捕获不稳定的 lambda 按捕获值
    // memoize（`docs/scroll-perf-list.md:26`、`:104`、`:394`），而 `PiSettingsStack` 这一层
    // 的参数在 `_state` 发布时逐个不变（`store` 来自 `remember(session)`、`knownThemes` 来自
    // 等值的 StateFlow 不会发布、lambda 捕获的是同一批 state 对象）⇒ **整棵设置树被跳过**，
    // 行上的值停在旧值上，直到离开分组再进来（那时 `SettingsGroupScreen` 重新组合并重读
    // store）。用户报的正是这个：「调了不管事，必须得切个屏再切回去才有变化」。
    //
    // 为什么不能靠上面的 `filesEpoch`：它只由 `PiDirectoryWatch` 报**外部**改动时 ++，而
    // `app.*` 写的是 `app-prefs.json`（`settings/PiSettingsFileStore.kt:349`、`:376`）——它是
    // `agentDir` 的**同级**文件，既不在被监视的目录里（`:292` 只盯 `paths.agentDir` 与工作区
    // 的 `.pi`），也不在 `SETTINGS_WATCHED` 的名字里（`:733-745`）。`theme` 之所以看起来是
    // 即时生效的，恰恰因为它写的是被监视的 `settings.json`：`PiFileWatch.kt:44-45` 自己记着
    // 这个依赖——「以前每个事件都回调一次，于是"改一个开关"会让整页重读两遍」。也就是说，
    // 「改开关 → 页面重读」过去完全靠文件监视，而 `app.*` 的设置没有这一层。
    var writeEpoch by remember { mutableStateOf(0) }

    // 行必须重读的那个 epoch：外部改动与本 App 自己的写入各占一半。在这里（`PiSettingsStack`
    // 自己的作用域里）读它，而不是只在某个分支的实参里现算，是为了让首页也吃到它：停在首页
    // 时分组页与搜索页都不在组合里，若 epoch 只在它们的分支里被读，首页摘要（「主题 dark」
    // 那类）就没有任何东西能把它重读——`docs/settings-audit-impl.md:232` 记的那条
    // 「SettingsHome 靠父级重组，不是显式契约」的缺口。
    val rowsEpoch = filesEpoch + writeEpoch

    // Keyed on the **path**, not just the context: 切换工作区 changes the directory
    // without changing the Activity's context, and a bare `remember(context)` would
    // keep reading the old workspace for the rest of the process's life
    // (`PiSessionViewModel.switchWorkspace`). The path is computed once per
    // composition — it is a settings read, not a directory scan.
    val workspacePath = PtyLauncher.workspaceHost(context).absolutePath
    val workspace = remember(context, workspacePath) { PtyLauncher.workspaceHost(context) }
    PiDirectoryWatch(
        directories = remember(paths, workspace) { listOf(paths.agentDir, PiProjectConfig.root(workspace)) },
        names = remember { SETTINGS_WATCHED },
        onChanged = {
            // Drop the caches, then warm them **on IO** before bumping the epoch. The rows
            // read `store.read(...)` during composition, so a cold cache made the frame that
            // noticed an external change parse the whole document on the main thread — the
            // second half of §B10. `theme` covers the global and project documents (it is
            // read through the merge); the appearance key covers the app-local sidecar.
            onExternalSettingsWrite()
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { activeStore.read("theme") }
                    runCatching { activeStore.read("app.appearance.typography") }
                }
                filesEpoch++
            }
        },
    )

    // ---- 运行时选择（proroot）---------------------------------------------
    // The switch lives in the app's own SharedPreferences and the effective runtime is
    // derived from the probe cache plus the failure counter, so both rows are served by
    // a decorator over the real settings store rather than by pi's `settings.json`
    // (`AppOnlySettingsStore` says why).
    //
    // `status()` reads the digest of five `.so` files plus one small cache file, so it
    // runs once per epoch on IO — never per frame, and never inside `read(key)`.
    //
    // One read feeds **both** strings the row needs: the sentence (which now names the
    // probe stage that refused) and the bounded block of raw per-phase lines under it.
    // Both come out of the same `Status`, so the expensive read above is still paid
    // once; `runtimeDetailOverrides` is pure string work over fields already in hand
    // (see its KDoc), which is why the block cannot turn into a per-frame `.so` hash.
    val runtimePrefs = remember(context) { RuntimePreferences.get(context) }
    val runtimeSelection = remember(paths, runtimePrefs) { RuntimeSelection(paths, runtimePrefs) }
    var runtimeStatusEpoch by remember { mutableStateOf(0) }
    // Null until the first IO read lands: the row shows its own 未读取 for that frame and
    // the detail block renders nothing, rather than a default that looks like a reading.
    var runtimeStatus by remember { mutableStateOf<RuntimeSelection.Status?>(null) }
    var engineStatuses by remember {
        mutableStateOf<Map<app.pi.runtime.GuestEngine, RuntimeSelection.Status>?>(null)
    }
    // `runtimeSwitch` is a key for the reason its KDoc gives: the toggle's own flow changes
    // the switch value (a refused restart rolls it back) and the cached verdict (a probe just
    // ran), and neither bump reaches this screen on its own — the epoch above only fires on
    // the write itself, which happens *before* the probe. It also covers a plain
    // `Probing → Idle` transition for a probe that changed nothing.
    LaunchedEffect(runtimeSelection, filesEpoch, runtimeStatusEpoch, runtimeSwitch) {
        val read = withContext(Dispatchers.IO) {
            runtimeSelection.status() to app.pi.runtime.GuestEngine.entries.associateWith {
                runtimeSelection.statusFor(it)
            }
        }
        runtimeStatus = read.first
        engineStatuses = read.second
    }
    // While the toggle's flow is doing something, the row says so instead of showing the
    // previous verdict: the probe runs for tens of seconds and a row that keeps showing the
    // old sentence is the "switch did nothing" shape this change removes. `null` (Idle) means
    // "show the real reading" — see `RuntimeSwitchAction.statusLine`.
    val runtimeStatusText = RuntimeSwitchAction.statusLine(
        runtimeSwitch,
        RuntimeSwitchAction.Target.valueOf(runtimeSelection.selectedEngine.name),
    )
        ?: runtimeStatus?.summary
        ?: "未读取"

    // 「系统提示词」那一组里那一条**只读读数**：pi 会读的那几份提示词文件（两份名字 × 两个层级）。
    //
    // **一次 stat、两处用**：值给行（`valueOverrides`），存在的文件按 pi 的顺序逐行给行下面的
    // 证据块（`detailOverrides` —— 见 `SettingsGroupScreen`：只读行的值只有一行，多行的东西写在
    // 行下面）。两者必须出自**同一次**读数，否则行与它自己的证据会各说一套。
    //
    // 它读的是**文件**，所以不能放进 store 的 `read(key)`（那是每帧每行都要走的路径），只能在这
    // 里读一次。键是 `PROMPT_SOURCE_KEY`；值只报能证明的事实 —— 哪几份文件存在、在哪一级；那条
    // 「工作区那份受信任时优先」的规则写在行的描述里，不在这里猜一个裁决
    // （`PromptFileFacts` 的 KDoc 写了为什么）。
    //
    // epoch 用 `rowsEpoch`（外部改动 + 本进程写入）而不是只 `filesEpoch`：在设置页里
    // 编辑提示词走的正是本进程写入那一半，`writeEpoch` 必须能把这行重读一次。
    // `SETTINGS_WATCHED` 里的两个文件名管的是另一半（在「Pi 文件」或终端里改）。
    var promptReading by remember { mutableStateOf<PromptFileFacts.Reading?>(null) }
    LaunchedEffect(workspacePath, rowsEpoch, paths) {
        promptReading = withContext(Dispatchers.IO) {
            runCatching { PromptFileFacts.read(paths.agentDir, workspace) }.getOrNull()
        }
    }
    val promptOverrides =
        promptReading?.let { mapOf(PROMPT_SOURCE_KEY to PromptFileFacts.value(it)) } ?: emptyMap()
    val promptDetailOverrides = promptReading?.let { PromptFileFacts.detail(it) }
        ?.let { mapOf(PROMPT_SOURCE_KEY to it) } ?: emptyMap()

    val engineMatrix = engineMatrixOverrides(engineStatuses)
    val effectiveStore = remember(activeStore, runtimeSelection, runtimeStatusText, engineMatrix.first) {
        AppOnlySettingsStore(activeStore, runtimeSelection, runtimeStatusText, engineMatrix.first)
    }
    // Turning the switch must refresh the status row in the same breath; an app-only
    // write touches no pi document, so nothing else in the stack would notice, and the
    // status text is exactly what a stale value would lie about.
    val handleSettingWritten: (String) -> Unit = { key ->
        if (key == AppOnlySettingsStore.KEY_ENGINE) runtimeStatusEpoch++
        // 先让宿主读它要读的（`refreshPrefs` / `refreshTheme` / proroot 的探针流程），再让这一栈
        // 的行重读 store。顺序反过来，proroot 那两行会先用旧的 `runtimeStatusText` 重读一次。
        onSettingWritten(key)
        // 这一行才是「行刷新」的那一半：见上面 `writeEpoch` 的注释 —— store 不可订阅，
        // 而写完之后被通知的 `_state` / `_theme` 不是设置页读的东西，所以必须显式换一个
        // 会让行重读的 epoch。
        writeEpoch++
    }

    val openSetting: (String) -> Unit = { key ->
        val setting = PiSettingsCatalog.byKey[key]
        if (key == "hub:resources" || key == "hub:resources:project") {
            // 项目页的「扩展与资源」入口：不是某一条设置，是枢纽页本身；`:project` 直接落在项目层。
            resourcesProject = key.endsWith(":project")
            resources = true
        } else if (key == "hub:models") {
            modelFlow = true
        } else if (setting != null && setting.group == G_MODELS_SCREEN) {
            modelFlow = true
        } else if (setting != null) {
            groupId = setting.group
            highlightKey = key
        }
    }

    // A requested key arrives from PiRoot as state, not as a callback, because
    // the command that asks for it ends in a coroutine (see `NavRequest`). It is
    // opened once and acknowledged immediately; the stack keeps the position
    // afterwards, which is what "jump to settings and highlight" means.
    LaunchedEffect(focusKey) {
        val key = focusKey ?: return@LaunchedEffect
        openSetting(key)
        onFocusConsumed()
    }

    // Action rows this stack implements itself. Everything else is delegated to
    // [onRunAction]; when the caller supplies nothing, the row keeps its honest
    // "not wired" dialog — which is why this is a per-key map and not a lambda
    // that would have to pretend every key is handled.
    val openModelFlow: () -> Unit = { modelFlow = true }
    val hostActions: Map<String, () -> Unit> = mapOf(
        "app.runtime.restartEngine" to { restartPrompt = true },
        "app.runtime.diagnostics" to { diagnostics = true },
        "app.prompts.viewEffective" to {
            val api = advanced()
            if (api == null) {
                actionNote = "查看系统提示词" to "引擎未运行，启动后再来读取当前生效的完整系统提示词。"
            } else {
                scope.launch {
                    runCatching { api.systemPrompt() }
                        .onSuccess { textSheet = "当前生效系统提示词" to it.ifBlank { "（空）" } }
                        .onFailure { actionNote = "查看系统提示词" to "读取失败：${it.message}" }
                }
            }
        },
        "app.tools.manageLive" to { liveToolsOpen = true },
    )

    BackHandler(enabled = true) {
        if (mcp) {
            mcp = false
        } else if (licenses) {
            licenses = false
        } else if (diagnostics) {
            diagnostics = false
        } else if (modelFlow) {
            modelFlow = false
        } else if (deviceCapabilities) {
            deviceCapabilities = false
        } else if (other) {
            other = false
        } else if (groupId != null || highlightKey != null) {
            groupId = null
            highlightKey = null
        } else if (resources) {
            resources = false
        } else {
            onBack()
        }
    }

    val currentGroup = groupId
    Box(Modifier.fillMaxSize()) {
        when {
            mcp -> McpServersScreen(
                contentPadding = contentPadding,
                onBack = { mcp = false },
                userFile = File(paths.agentDir, "mcp.json"),
                projectFile = File(PiProjectConfig.root(workspace), "mcp.json"),
                access = extensionsAccess,
                onFilesWritten = onExternalSettingsWrite,
            )

            licenses -> LicensesScreen(
                contentPadding = contentPadding,
                onBack = { licenses = false },
            )

            diagnostics -> DiagnosticsScreen(
                contentPadding = contentPadding,
                onBack = { diagnostics = false },
                engine = engineDiagnostics,
                failures = recentFailures,
                engineEntry = engineEntry,
            )

            modelFlow -> app.pi.ui.models.ModelsScreen(
                contentPadding = contentPadding,
                onBack = { modelFlow = false },
                host = modelsHost,
                store = effectiveStore,
                freshness = rowsEpoch,
                onSettingWritten = handleSettingWritten,
                availableModels = availableModels,
                onLoadAvailableModels = onLoadAvailableModels,
            )

            deviceCapabilities -> DeviceCapabilityScreen(
                store = DeviceCapabilityStore.get(LocalContext.current),
                onBack = { deviceCapabilities = false },
                contentPadding = contentPadding,
            )

            other -> OtherSettingsScreen(
                contentPadding = contentPadding,
                onBack = { other = false },
                onOpenTerminal = onOpenTerminal?.let { open ->
                    {
                        other = false
                        open()
                    }
                },
                onOpenDeviceCapabilities = {
                    other = false
                    deviceCapabilities = true
                },
                onOpenSecurity = {
                    other = false
                    groupId = "security"
                    highlightKey = null
                },
                onOpenLicenses = {
                    other = false
                    licenses = true
                },
            )

            currentGroup != null -> SettingsGroupScreen(
                groupId = currentGroup,
                store = effectiveStore,
                freshness = rowsEpoch,
                contentPadding = contentPadding,
                onBack = {
                    groupId = null
                    highlightKey = null
                },
                highlightKey = highlightKey,
                knownThemes = knownThemes,
                themeNotes = themeNotes,
                themeError = themeError,
                onSettingWritten = handleSettingWritten,
                onRunAction = onRunAction,
                hostActions = hostActions,
                valueOverrides = runtimeOverrides(facts) + promptOverrides,
                detailOverrides = (if (runtimeSwitch.inFlight) {
                    emptyMap()
                } else {
                    runtimeDetailOverrides(runtimeStatus)
                }) + promptDetailOverrides +
                    (if (runtimeSwitch.inFlight || engineMatrix.second.isEmpty()) {
                        emptyMap()
                    } else {
                        mapOf(AppOnlySettingsStore.KEY_MATRIX to engineMatrix.second)
                    }),
                onRestartEngine = { restartPrompt = true },
                availableModels = availableModels,
                onLoadAvailableModels = onLoadAvailableModels,
            )

            resources -> app.pi.ui.resources.ResourcesHubScreen(
                contentPadding = contentPadding,
                startOnProject = resourcesProject,
                onBack = { resources = false },
                access = extensionsAccess,
                advanced = advanced,
                onOpenMcp = { mcp = true },
                onOpenCommandSettings = {
                    groupId = "resources"
                    highlightKey = null
                },
                onOpenTrustPolicy = {
                    groupId = "security"
                    highlightKey = "defaultProjectTrust"
                },
            )

            else -> SettingsHome(
                store = effectiveStore,
                contentPadding = contentPadding,
                onBack = onBack,
                freshness = rowsEpoch,
                onOpenGroup = { id ->
                    if (id == "resources") {
                        resources = true
                    } else {
                        groupId = id
                        highlightKey = null
                    }
                },
                onOpenModels = openModelFlow,
                onOpenOther = { other = true },
                onOpenMcp = { mcp = true },
            )
        }
    }

    textSheet?.let { (title, body) ->
        PiSheet(onDismiss = { textSheet = null }, fullHeight = true) {
            Column(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = PiTheme.text.prose, color = PiTheme.palette.text)
                    PiSettingsChip(text = "复制", onClick = { clipboard.setText(AnnotatedString(body)) })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = PiSpacing.small)) {
                    Text(body, style = PiTheme.text.monoSmall, color = PiTheme.palette.text)
                }
            }
        }
    }

    if (liveToolsOpen) {
        LiveToolsSheet(advanced = advanced, onDismiss = { liveToolsOpen = false })
    }

    actionNote?.let { (title, body) ->
        PiSettingsDialog(
            onDismissRequest = { actionNote = null },
            title = title,
            body = body,
            confirmationLabel = "知道了",
            onConfirm = { actionNote = null },
        )
    }

    // The 进程 section's "restart so these take effect" step. Two dialogs, not
    // one: the first states the cost before anything happens, the second reports
    // what the engine answered (it refuses on its own while a turn is running, and
    // its sentence is the one worth showing).
    if (restartPrompt) {
        PiSettingsDialog(
            onDismissRequest = { restartPrompt = false },
            title = "重启引擎",
            body = if (isTurnRunning()) {
                "现在有回合正在运行。重启会终止模型调用、工具调用与正在跑的命令，它们都不会恢复；" +
                    "已写入磁盘的会话不会丢失。"
            } else {
                "重启会终止正在进行的回合，已写入磁盘的会话不会丢失。"
            },
            confirmationLabel = "重启",
            onConfirm = {
                restartPrompt = false
                val engine = restartEngine
                if (engine == null) {
                    restartNote = "重启未接入：设置页还没有拿到引擎的重启入口。文件与设置都已保存。"
                } else {
                    scope.launch {
                        restartNote = when (val outcome = engine("设置里更改了进程开关", false)) {
                            is EngineRestartCoordinator.Outcome.Ok ->
                                "引擎已重启，新的进程设置已生效。"

                            is EngineRestartCoordinator.Outcome.Refused -> outcome.message
                            is EngineRestartCoordinator.Outcome.Failed -> outcome.message
                        }
                    }
                }
            },
            dismissalLabel = "取消",
            onDismissButton = { restartPrompt = false },
        )
    }

    val restartResult = restartNote
    if (restartResult != null) {
        PiSettingsDialog(
            onDismissRequest = { restartNote = null },
            title = "重启引擎",
            body = restartResult,
            confirmationLabel = "知道了",
            onConfirm = { restartNote = null },
        )
    }
}

/**
 * 设置页要盯着的**外部改动**（交给 `PiDirectoryWatch` 做前缀匹配）。
 *
 * 只列 pi 读的文件与目录，不列 `sessions/`：会话目录在一个回合里会被反复写，把它算进来等于
 * 让设置页跟着对话的节奏重组，而设置页里没有任何一行显示会话。
 *
 * `SYSTEM.md` / `APPEND_SYSTEM.md` 是本轮加进来的：设置页有两条读它们的行（可编辑的两个
 * 提示词行）和一条显示它们的行（`PROMPT_SOURCE_KEY`），在「Pi 文件」或终端里改完必须让
 * 这几行重读。它们不是 `settings.json`，所以只能靠名字进这张表 —— 不加的话外部改动会一直
 * 停在旧值上，直到别的原因触发一次重组。
 */
private val SETTINGS_WATCHED = listOf(
    "settings.json",
    "models.json",
    "auth.json",
    "models-store.json",
    "trust.json",
    "mcp.json",
    "SYSTEM.md",
    "APPEND_SYSTEM.md",
    "extensions",
    "skills",
    "prompts",
    "themes",
)

/**
 * 「系统提示词来源」那一行只读读数的键（注册表里的 `app.prompts.effectiveSource`）。
 *
 * 值不在 store 里：它由 `PiSettingsStack` 在 IO 上从两个 `SYSTEM.md` 算出来，作为
 * `valueOverrides` 的一项交给行渲染 —— 与「已发现的主题」那四条同一个分工。常量放在这里
 * 而不是注册表里，是因为它是**宿主**这一侧的键：注册表只声明行。
 */
private const val PROMPT_SOURCE_KEY = "app.prompts.effectiveSource"

/** 当前会话实时工具启停面板（原 `EngineScreen` 的工具段，现归位到「工具」分组）。 */
@Composable
private fun LiveToolsSheet(
    advanced: () -> HostAdvanced?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var tools by remember { mutableStateOf<List<HostAdvanced.ToolDetail>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        val api = advanced()
        if (api == null) {
            tools = emptyList()
            error = "引擎未运行，启动后可查看和即时切换当前会话的工具。"
        } else {
            runCatching { api.tools() }
                .onSuccess { tools = it; error = null }
                .onFailure { error = it.message ?: "读取工具失败" }
        }
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
        ) {
            val list = tools
            Text(
                if (list == null) "当前会话工具" else "当前会话工具（${list.count { it.active }} / ${list.size} 启用）",
                style = PiTheme.text.prose,
                color = PiTheme.palette.text,
            )
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            if (list == null) {
                Text("读取中…", style = PiTheme.text.meta, color = PiTheme.palette.muted)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(list.size) { i ->
                        val t = list[i]
                        if (i > 0) PiSettingsHairline()
                        PiSettingsToggleRow(
                            title = t.name,
                            supporting = listOfNotNull(
                                t.description.lineSequence().firstOrNull()?.take(100),
                                t.source?.let { "来自 $it" },
                                if (t.active && !t.callable) "已启用但当前不可调用" else null,
                            ).joinToString(" · ").ifEmpty { null },
                            checked = t.active,
                            onCheckedChange = { on ->
                                val api = advanced() ?: return@PiSettingsToggleRow
                                val names = list.filter { it.active && it.name != t.name }.map { it.name } +
                                    if (on) listOf(t.name) else emptyList()
                                scope.launch {
                                    runCatching { api.setActiveTools(names) }
                                        .onSuccess { reload++ }
                                        .onFailure { error = it.message ?: "更新工具失败" }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
