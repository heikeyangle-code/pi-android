package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.bridge.DeviceCapabilityStore
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.packages.EngineRestartCoordinator
import app.pi.protocol.PiResponses
import app.pi.runtime.GuestEngine
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.runtime.RuntimePreferences
import app.pi.ui.device.DeviceCapabilityScreen
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
 * indistinguishable.
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
    runtimeSwitch: RuntimeSwitchAction.Step = RuntimeSwitchAction.Step.Idle,
    activeRuntimeEngine: () -> GuestEngine? = { null },
    liveEngineState: () -> PiEngineSession.EngineState? = { null },
    onSelectRuntimeEngine: (GuestEngine) -> Unit = {},
    onStartEngine: () -> Unit = {},
    onRepairRuntime: () -> Unit = {},
    onRebuildRuntime: () -> Unit = {},
    liveContextUsage: PiResponses.ContextUsage? = null,
    liveModelLabel: String? = null,
    isCompacting: Boolean = false,
    onRunCompaction: (() -> Unit)? = null,
) {
    val activeStore = store ?: rememberInMemoryPiSettingsStore()
    // Every level of this stack is `rememberSaveable`, not `remember`: the settings
    // destination is hosted in a `SaveableStateProvider`, so rotation and process
    // recreation used to drop the user back on 设置首页 with the search text and the open
    // group gone — while the whole point of that provider is that this screen survives
    //. All of these are String?/Boolean, so they save
    // as they are; editor state that holds a `PiSetting` deliberately does not.
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The device capability screen is the one branch of this stack that is not a
    // group in pi's settings catalog — the switches grant the *phone's* abilities
    // to the agent, and pi has no such settings.
    // 设置首页的「其他」一屏：终端 / 设备能力 / 开源许可。
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
    // 「引擎与运行环境」专页（`RuntimeEngineScreen`）：实时状态、容器运行时切换、工具链自检、诊断报告与环境维护。
    var runtimeScreen by rememberSaveable { mutableStateOf(false) }
    // 设置首页「MCP 服务器」（`McpServersScreen`）：pi 的 `mcp.json` 编辑器。
    var mcp by rememberSaveable { mutableStateOf(false) }
    var updateSheetOpen by remember { mutableStateOf(false) }
    var updateOutcome by remember { mutableStateOf(app.pi.update.AppUpdater.peekCached()) }

    val context = LocalContext.current
    LaunchedEffect(context) {
        app.pi.update.AppUpdater.checkForUpdate(context, force = false)
            .onSuccess { updateOutcome = it }
    }
    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
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
    // 仅在设置/工作区 epoch 变化时解析一次，不在每次重组上触发磁盘 stat/mkdirs。
    val workspace = remember(context, rowsEpoch) { PtyLauncher.workspaceHost(context) }
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

    val runtimePrefs = remember(context) { RuntimePreferences.get(context) }
    val runtimeHomeSummary = run {
        val active = activeRuntimeEngine()
        val state = liveEngineState()
        val engineName = (active ?: runtimePrefs.selectedEngine).name.lowercase()
        val stateText = when {
            runtimeSwitch.inFlight -> tr("切换中…", "Switching…")
            state == PiEngineSession.EngineState.Ready -> tr("运行中", "Running")
            state == PiEngineSession.EngineState.Busy -> tr("回合执行中", "Busy")
            state == PiEngineSession.EngineState.Starting -> tr("启动中", "Starting")
            state == PiEngineSession.EngineState.Failed -> tr("异常退出", "Failed")
            else -> tr("未运行", "Stopped")
        }
        tr("$engineName · $stateText · 诊断与维护", "$engineName · $stateText · Diagnostics & repair")
    }

    val handleSettingWritten: (String) -> Unit = { key ->
        onSettingWritten(key)
        writeEpoch++
    }

    var modelsInitialProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var modelsInitialScoped by rememberSaveable { mutableStateOf(false) }

    fun clearRoutes() {
        groupId = null
        highlightKey = null
        deviceCapabilities = false
        resources = false
        resourcesProject = false
        modelFlow = false
        modelsInitialProvider = null
        modelsInitialScoped = false
        licenses = false
        runtimeScreen = false
        mcp = false
    }

    val openSetting: (String) -> Unit = { key ->
        val setting = PiSettingsCatalog.byKey[key]
        when {
            key == "hub:resources" || key == "hub:resources:project" || key == "defaultProjectTrust" -> {
                clearRoutes()
                resourcesProject = key.endsWith(":project") || key == "defaultProjectTrust"
                resources = true
            }
            key == "hub:models" || key.startsWith("hub:models:") || key == "hub:scoped-models" -> {
                clearRoutes()
                modelsInitialScoped = key == "hub:scoped-models"
                modelsInitialProvider = key.removePrefix("hub:models:").takeIf { key.startsWith("hub:models:") && it.isNotBlank() }
                modelFlow = true
            }
            key == "hub:mcp" -> {
                clearRoutes()
                mcp = true
            }
            key == "hub:runtime" -> {
                clearRoutes()
                runtimeScreen = true
            }
            key == "hub:device" -> {
                clearRoutes()
                deviceCapabilities = true
            }
            key == "hub:licenses" -> {
                clearRoutes()
                licenses = true
            }
            key == "hub:update" -> {
                updateSheetOpen = true
            }
            key == "hub:terminal" -> onOpenTerminal?.invoke()
            key == "hub:prompts" -> {
                clearRoutes()
                groupId = "prompts"
            }
            key == "hub:tools" -> {
                clearRoutes()
                groupId = "tools"
            }
            setting != null && setting.group == G_MODELS_SCREEN -> {
                clearRoutes()
                modelFlow = true
            }
            setting != null -> {
                clearRoutes()
                groupId = setting.group
                highlightKey = key
            }
        }
    }

    LaunchedEffect(focusKey) {
        val key = focusKey ?: return@LaunchedEffect
        openSetting(key)
        onFocusConsumed()
    }

    val openModelFlow: () -> Unit = {
        modelsInitialProvider = null
        modelsInitialScoped = false
        modelFlow = true
    }
    val hostActions: Map<String, () -> Unit> = emptyMap()

    BackHandler(enabled = true) {
        if (mcp) {
            mcp = false
        } else if (licenses) {
            licenses = false
        } else if (runtimeScreen) {
            runtimeScreen = false
        } else if (modelFlow) {
            modelFlow = false
            modelsInitialProvider = null
            modelsInitialScoped = false
        } else if (deviceCapabilities) {
            deviceCapabilities = false
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
    val routeKey: Pair<String, Int> = when {
        mcp -> "mcp" to (if (resources) 2 else 1)
        licenses -> "licenses" to 1
        runtimeScreen -> "runtime" to 1
        modelFlow -> "models" to 1
        deviceCapabilities -> "device" to 1
        currentGroup == "tools" -> "tools" to 1
        currentGroup == "prompts" -> "prompts" to 1
        currentGroup == "appearance" -> "appearance" to 1
        currentGroup == "messages" -> "messages" to 1
        currentGroup == "compaction" -> "compaction" to 1
        currentGroup == "retry" -> "retry" to 1
        currentGroup != null -> "group:$currentGroup" to (if (resources) 2 else 1)
        resources -> "resources" to 1
        else -> "home" to 0
    }
    val settingsFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val settingsKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var routeInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(routeKey) {
        if (routeInitialized) {
            settingsFocusManager.clearFocus(force = true)
            settingsKeyboard?.hide()
        } else {
            routeInitialized = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = routeKey,
            transitionSpec = {
                val forward = targetState.second >= initialState.second
                val ease = FastOutSlowInEasing
                if (forward) {
                    (slideInHorizontally(tween(200, easing = ease)) { it / 5 } + fadeIn(tween(170)))
                        .togetherWith(slideOutHorizontally(tween(170, easing = ease)) { -it / 12 } + fadeOut(tween(130)))
                } else {
                    (slideInHorizontally(tween(200, easing = ease)) { -it / 12 } + fadeIn(tween(170)))
                        .togetherWith(slideOutHorizontally(tween(170, easing = ease)) { it / 5 } + fadeOut(tween(130)))
                }
            },
            label = "settings-stack",
        ) { (route, _) ->
            when {
                route == "mcp" -> McpServersScreen(
                    contentPadding = contentPadding,
                    onBack = { mcp = false },
                    userFile = File(paths.agentDir, "mcp.json"),
                    projectFile = File(PiProjectConfig.root(workspace), "mcp.json"),
                    access = extensionsAccess,
                    onFilesWritten = onExternalSettingsWrite,
                )

                route == "licenses" -> LicensesScreen(
                    contentPadding = contentPadding,
                    onBack = { licenses = false },
                )

                route == "runtime" -> RuntimeEngineScreen(
                    contentPadding = contentPadding,
                    onBack = { runtimeScreen = false },
                    store = activeStore,
                    freshness = rowsEpoch,
                    onSettingWritten = handleSettingWritten,
                    activeEngine = activeRuntimeEngine,
                    engineState = liveEngineState,
                    engineDiagnostics = engineDiagnostics,
                    recentFailures = recentFailures,
                    engineEntry = engineEntry,
                    runtimeSwitch = runtimeSwitch,
                    onSelectEngine = onSelectRuntimeEngine,
                    restartEngine = restartEngine,
                    isTurnRunning = isTurnRunning,
                    onStartEngine = onStartEngine,
                    onRepairRuntime = onRepairRuntime,
                    onRebuildRuntime = onRebuildRuntime,
                )

                route == "models" -> app.pi.ui.models.ModelsScreen(
                    contentPadding = contentPadding,
                    onBack = {
                        modelFlow = false
                        modelsInitialProvider = null
                        modelsInitialScoped = false
                    },
                    host = modelsHost,
                    store = activeStore,
                    freshness = rowsEpoch,
                    onSettingWritten = handleSettingWritten,
                    onExternalSettingsWrite = onExternalSettingsWrite,
                    availableModels = availableModels,
                    onLoadAvailableModels = onLoadAvailableModels,
                    initialProviderQuery = modelsInitialProvider,
                    initialOpenScoped = modelsInitialScoped,
                )

                route == "device" -> DeviceCapabilityScreen(
                    store = DeviceCapabilityStore.get(LocalContext.current),
                    onBack = { deviceCapabilities = false },
                    contentPadding = contentPadding,
                )

                route == "tools" -> ToolsScreen(
                    store = activeStore,
                    freshness = rowsEpoch,
                    contentPadding = contentPadding,
                    advanced = advanced,
                    extensionsAccess = extensionsAccess,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                )

                route == "prompts" -> app.pi.ui.resources.SystemPromptHostScreen(
                    contentPadding = contentPadding,
                    access = extensionsAccess,
                    store = activeStore,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                )

                route == "appearance" -> AppearanceScreen(
                    store = activeStore,
                    agentDir = paths.agentDir,
                    freshness = rowsEpoch,
                    contentPadding = contentPadding,
                    knownThemes = knownThemes,
                    themeError = themeError,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                )

                route == "messages" -> ChatMessagesScreen(
                    store = activeStore,
                    freshness = rowsEpoch,
                    contentPadding = contentPadding,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                    highlightKey = highlightKey,
                )

                route == "compaction" -> CompactionScreen(
                    store = activeStore,
                    freshness = rowsEpoch,
                    contentPadding = contentPadding,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                    contextUsage = liveContextUsage,
                    activeModelLabel = liveModelLabel,
                    isCompacting = isCompacting,
                    onRunCompaction = onRunCompaction,
                    availableModels = availableModels,
                    onLoadAvailableModels = onLoadAvailableModels,
                    highlightKey = highlightKey,
                )

                route == "retry" -> NetworkRetryScreen(
                    store = activeStore,
                    freshness = rowsEpoch,
                    contentPadding = contentPadding,
                    onSettingWritten = handleSettingWritten,
                    onBack = {
                        groupId = null
                        highlightKey = null
                    },
                    highlightKey = highlightKey,
                )

                route.startsWith("group:") -> {
                    val gid = route.removePrefix("group:")
                    SettingsGroupScreen(
                        groupId = gid,
                        store = activeStore,
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
                        onRestartEngine = { restartPrompt = true },
                        availableModels = availableModels,
                        onLoadAvailableModels = onLoadAvailableModels,
                    )
                }

                route == "resources" -> app.pi.ui.resources.ResourcesHubScreen(
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
                    onSettingsSynced = {
                        onExternalSettingsWrite()
                        writeEpoch++
                    },
                )

                else -> SettingsHome(
                    store = activeStore,
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
                    onOpenKey = openSetting,
                    onOpenModels = openModelFlow,
                    onOpenRuntime = { runtimeScreen = true },
                    runtimeSummary = runtimeHomeSummary,
                    onOpenMcp = { mcp = true },
                    onOpenUpdate = { updateSheetOpen = true },
                    updateOutcome = updateOutcome,
                    onOpenTerminal = onOpenTerminal,
                    onOpenDeviceCapabilities = { deviceCapabilities = true },
                    onOpenLicenses = { licenses = true },
                )
            }
        }
    }

    if (updateSheetOpen) {
        AppUpdateSheet(
            onDismiss = { updateSheetOpen = false },
            onOutcomeUpdated = { updateOutcome = it },
        )
    }

    // The 进程 section's "restart so these take effect" step. Two dialogs, not
    // one: the first states the cost before anything happens, the second reports
    // what the engine answered (it refuses on its own while a turn is running, and
    // its sentence is the one worth showing).
    if (restartPrompt) {
        PiSettingsDialog(
            onDismissRequest = { restartPrompt = false },
            title = tr("重启引擎", "Restart Engine"),
            body = if (isTurnRunning()) {
                tr(
                    "现在有回合正在运行。重启会终止模型调用、工具调用与正在跑的命令，它们都不会恢复；" +
                        "已写入磁盘的会话不会丢失。",
                    "A turn is currently running. Restarting will abort the active model call, tool calls, and running commands; saved sessions will not be lost.",
                )
            } else {
                tr(
                    "重启会终止正在进行的回合，已写入磁盘的会话不会丢失。",
                    "Restarting interrupts any active turn. Sessions already written to disk will not be lost.",
                )
            },
            confirmationLabel = tr("重启", "Restart"),
            onConfirm = {
                restartPrompt = false
                val engine = restartEngine
                if (engine == null) {
                    restartNote = tr(
                        "重启未接入：设置页还没有拿到引擎的重启入口。文件与设置都已保存。",
                        "Restart unavailable: no engine restart handler is connected. Files and settings have been saved.",
                    )
                } else {
                    scope.launch {
                        restartNote = when (val outcome = engine(tr("设置里更改了进程开关", "Process settings changed"), false)) {
                            is EngineRestartCoordinator.Outcome.Ok ->
                                tr("引擎已重启，新的进程设置已生效。", "Engine restarted. New process settings are now active.")

                            is EngineRestartCoordinator.Outcome.Refused -> outcome.message
                            is EngineRestartCoordinator.Outcome.Failed -> outcome.message
                        }
                    }
                }
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { restartPrompt = false },
        )
    }

    val restartResult = restartNote
    if (restartResult != null) {
        PiSettingsDialog(
            onDismissRequest = { restartNote = null },
            title = tr("重启引擎", "Restart Engine"),
            body = restartResult,
            confirmationLabel = tr("知道了", "OK"),
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
