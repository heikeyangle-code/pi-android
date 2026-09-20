package app.pi.ui.settings

import androidx.activity.compose.BackHandler
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
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PiPackagesHost
import app.pi.rpc.PiResponses
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.runtime.RuntimePreferences
import app.pi.runtime.RuntimeSelection
import app.pi.ui.device.DeviceCapabilityScreen
import app.pi.ui.screens.PiFilesScreen
import app.pi.ui.screens.WorkspaceResource
import app.pi.ui.screens.WorkspaceResourceKind
import app.pi.ui.screens.WorkspaceResourceScan
import app.pi.ui.screens.WorkspaceSource
import app.pi.ui.theme.PiThemeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
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
     * credential form (`models.json`, see [PiCredentialScreen]). Null keeps both
     * screens honest: they say the restart is not wired instead of offering a
     * button that cannot work.
     */
    restartEngine: (suspend (reason: String, allowInterrupt: Boolean) -> EngineRestartCoordinator.Outcome)? = null,
    /** Live turn state, so a restart is refused rather than interrupting a turn. */
    isTurnRunning: () -> Boolean = { false },
    /**
     * `get_available_models` — pi's own metadata for the models it already has,
     * used to label what a scan matched and what fell back to app defaults. It is
     * the only model list pi exposes over RPC (`rpc-types.ts:20-74`); no command
     * returns the built-in catalog itself.
     */
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    /**
     * A key to open this stack on, e.g. `enabledModels` for pi's
     * `/scoped-models` (see `NavRequest.SettingsFocus`). Consumed once, through
     * [onFocusConsumed], so re-entering the destination does not re-navigate.
     */
    focusKey: String? = null,
    onFocusConsumed: () -> Unit = {},
    /**
     * Called after a screen wrote pi's files through a layer of its own (the
     * credential form's `PiEnginePreferences`), so the owner of the settings store
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
    var searching by rememberSaveable { mutableStateOf(false) }
    var backReturnsToSearch by rememberSaveable { mutableStateOf(false) }
    // The device capability screen is the one branch of this stack that is not a
    // group in pi's settings catalog — the switches grant the *phone's* abilities
    // to the agent, and pi has no such settings (docs/pi-android-app-design.md §21).
    var deviceCapabilities by rememberSaveable { mutableStateOf(false) }
    // pi installs resources with `pi install`, which has no RPC command, so the
    // app owns this UI; the screen is `app.pi.packages.PiPackagesHost`.
    var packages by rememberSaveable { mutableStateOf(false) }
    // The credential form. `app.credentials.apiKey` (`PiSettingsRegistry.kt:358`)
    // and `app.localModels.manage` (`:389`) are Action rows whose work is a
    // multi-step flow, so they own a screen instead of a confirm dialog.
    var credentials by rememberSaveable { mutableStateOf(false) }
    var credentialPreset by rememberSaveable { mutableStateOf<String?>(null) }
    // The open-source licence notices. Not a pi screen either — see §L: publishing
    // the licences of what this app redistributes is the distributor's obligation,
    // so the screen is the app's own.
    var licenses by rememberSaveable { mutableStateOf(false) }
    // 设置 → 模型：App 侧的一页，列出这台设备上配好的厂商与模型（`PiModelsScreen`）。
    var models by rememberSaveable { mutableStateOf(false) }
    // 设置 → 运行时与诊断 → 导出诊断报告（`app.runtime.diagnostics`）。报告的正文是
    // 被交付的那件东西，所以它先被看到、再被送出：一页屏幕而不是一次静默的保存 ——
    // 用户能亲眼看到退出码是空的、stderr 是空的、哪个载荷读不出来，再决定发不发。
    // 它不碰引擎，因此引擎已经退出时同样可用（这正是它存在的场景）。
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    // 设置首页「其他」里的「Pi 文件」（`app.pi.ui.screens.PiFilesScreen`）。它既不是 pi
    // 的设置键也不是 pi 的功能，而是 **pi 的文件**（`docs/settings-audit-pi-gap.md` §6.3），
    // 所以与上面几页同一档：这个栈里的一个层级，而不是首页自己托管的一屏 —— 层级的
    // 返回语义与 `rememberSaveable` 都归这里（自托管那条退路只在没有宿主时用，见
    // `SettingsHome` 的 `onOpenPiFiles`）。
    var piFiles by rememberSaveable { mutableStateOf(false) }

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

    // One lifecycle and one coordinator for the whole stack, so the two screens
    // that write pi's startup-read files (厂商凭证 and 模型) share one answer to
    // "is something waiting for a restart". They used to build one each, and a
    // second machine that cannot see the first is how a screen ends up saying
    // "没有待重启的变更" while the other says "需要重启".
    val lifecycle = remember { ExtensionLifecycle() }
    val coordinator = remember(lifecycle, restartEngine) {
        restartEngine?.let { engine ->
            EngineRestartCoordinator(
                lifecycle = lifecycle,
                isTurnRunning = isTurnRunning,
                restartEngine = engine,
            )
        }
    }

    // External-change epoch. `PiSettingsFileStore` caches both documents until the
    // app itself writes one, so a file edited by pi, a terminal, or the AI kept
    // showing the old value. `PiDirectoryWatch` reports real changes (inotify while
    // this stack is composed, plus one mtime/size comparison on ON_RESUME); each
    // report drops that cache and bumps this epoch, which re-builds the group rows
    // and therefore re-reads the values. No timer, and nothing at all while the
    // settings destination is not on screen.
    var filesEpoch by remember { mutableStateOf(0) }
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
                    runCatching { activeStore.read("app.appearance.messageDensity") }
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
    // `runtimeSwitch` is a key for the reason its KDoc gives: the toggle's own flow changes
    // the switch value (a refused restart rolls it back) and the cached verdict (a probe just
    // ran), and neither bump reaches this screen on its own — the epoch above only fires on
    // the write itself, which happens *before* the probe. It also covers a plain
    // `Probing → Idle` transition for a probe that changed nothing.
    LaunchedEffect(runtimeSelection, filesEpoch, runtimeStatusEpoch, runtimeSwitch) {
        runtimeStatus = withContext(Dispatchers.IO) { runtimeSelection.status() }
    }
    // While the toggle's flow is doing something, the row says so instead of showing the
    // previous verdict: the probe runs for tens of seconds and a row that keeps showing the
    // old sentence is the "switch did nothing" shape this change removes. `null` (Idle) means
    // "show the real reading" — see `RuntimeSwitchAction.statusLine`.
    val runtimeStatusText = RuntimeSwitchAction.statusLine(runtimeSwitch)
        ?: runtimeStatus?.summary
        ?: "未读取"

    // 「扩展与资源」那四个只读事实行的读数。**复用项目页资源段那个扫描器**
    // （`WorkspaceResourceScan`，纯磁盘读取，不需要引擎在跑），不新写一份扫描：两份实现会
    // 各自漂移，而"到底发现了什么"是最不能漂移的那种数。
    //
    // 键里有 `filesEpoch`：外部往 `skills/`/`themes/`/… 里放了东西时，监视器会 bump 它，
    // 这一屏的数字跟着变。读数在 IO 上跑；未扫出结果前是 `null`，行显示「未读取」而不是 0。
    var resourceScan by remember { mutableStateOf<ResourceScan?>(null) }
    LaunchedEffect(workspacePath, filesEpoch, paths) {
        val scanned = withContext(Dispatchers.IO) {
            runCatching {
                WorkspaceResourceScan.scan(
                    workspace = workspace,
                    configDir = PiProjectConfig.root(workspace),
                    agentDir = paths.agentDir,
                )
            }
        }
        val outcome = scanned.fold(
            onSuccess = { resources ->
                ResourceScan.Found(
                    resources.mapNotNull { resource ->
                        val kind = when (resource.kind) {
                            WorkspaceResourceKind.Skill -> DiscoveredKind.Skills
                            WorkspaceResourceKind.Theme -> DiscoveredKind.Themes
                            WorkspaceResourceKind.Prompt -> DiscoveredKind.Prompts
                            WorkspaceResourceKind.Extension -> DiscoveredKind.Extensions
                        }
                        val source = when (resource.source) {
                            WorkspaceSource.ProjectPi -> DiscoverySource.ProjectPi
                            WorkspaceSource.Agents -> DiscoverySource.Agents
                            WorkspaceSource.Global -> DiscoverySource.Global
                            WorkspaceSource.Package -> DiscoverySource.Package
                            WorkspaceSource.Extension -> DiscoverySource.Extension
                        }
                        DiscoveredResource(kind, resource.name, source)
                    },
                )
            },
            // 读不到就说读不到：这是 `ResourceScan.Unreadable` 存在的全部理由。把它吞成
            // `Found(emptyList())` 会让行显示「还没有发现任何资源」——一个看起来像真读数的假话。
            onFailure = { error ->
                ResourceScan.Unreadable(error.message ?: error::class.java.simpleName)
            },
        )
        resourceScan = outcome
        // 摘要（`SettingsHome` 画的那一行）拿不到这个 LaunchedEffect 的结果，只能读缓存。
        PiResourceFactsCache.put(workspacePath, outcome)
    }
    // 扫描落地前显示「未读取」；落地后按三种读数之一显示，绝不显示一个假的 0。
    val resourceOverrides = resourceScan?.let { resourceFactOverrides(it) } ?: resourceFactPlaceholders()
    val effectiveStore = remember(activeStore, runtimeSelection, runtimeStatusText) {
        AppOnlySettingsStore(activeStore, runtimeSelection, runtimeStatusText)
    }
    // Turning the switch must refresh the status row in the same breath; an app-only
    // write touches no pi document, so nothing else in the stack would notice, and the
    // status text is exactly what a stale value would lie about.
    val handleSettingWritten: (String) -> Unit = { key ->
        if (key == AppOnlySettingsStore.KEY_PROROOT) runtimeStatusEpoch++
        onSettingWritten(key)
    }

    val openSetting: (String) -> Unit = { key ->
        val setting = PiSettingsCatalog.byKey[key]
        if (setting != null) {
            backReturnsToSearch = searching
            groupId = setting.group
            highlightKey = key
            searching = false
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
    val openCredentialForm: () -> Unit = {
        credentialPreset = null
        credentials = true
    }
    // `app.localModels.manage` promises llama.cpp router management, which pi's
    // own `/llama` cannot do over RPC at all: its handler returns early unless
    // `ctx.mode === "tui"` (`extensions/llama/index.ts:186-189`), and
    // `ctx.ui.custom()` is a documented no-op in RPC mode. What *is* reachable is
    // configuring the endpoint pi reads: `models.json` plus a placeholder
    // credential (`docs/models.md:37`). The screen says the rest is TUI-only.
    val openLocalModelForm: () -> Unit = {
        credentialPreset = "llamacpp"
        credentials = true
    }
    // 设置 → 模型：App 侧的一页（`PiModelsScreen`）。它读的是文件与引擎，不是一个设置值，
    // 所以和凭证表单一样是 Action 行 + 自己的界面，而不是一条可编辑的键。
    val openModels: () -> Unit = { models = true }
    val hostActions: Map<String, () -> Unit> = mapOf(
        "app.models.inventory" to openModels,
        "app.credentials.apiKey" to openCredentialForm,
        "app.localModels.manage" to openLocalModelForm,
        // The `packages` row is a read-only *view* of what `pi install` wrote
        // (`PiSettingsRegistry.kt` says so on the row). Editing the array by hand
        // bypasses the download/resolve step and can leave a spec that looks
        // installed but was never fetched, so the row opens the manager status quo
        // screen instead of a list editor — the same screen 设置首页 already links.
        "packages" to { packages = true },
        // The 进程 section's three switches are pi's process configuration, so
        // they can only take effect in a new `pi --mode rpc` process. This row is
        // the next step the rows above point at.
        "app.runtime.restartEngine" to { restartPrompt = true },
        // 线上事故的入口：界面只显示 "rpc: engine exited with code 1"，而用户没有 ADB、
        // 看不到 logcat。这一行把 App 此刻还能读到的一切汇总成一份纯文本交给用户，报告
        // 的抓取发生在引擎退出的一瞬间（`engineDiagnostics`），所以引擎死了也能导。
        "app.runtime.diagnostics" to { diagnostics = true },
        // 「查看资源文件」：这一屏的出口，指向「Pi 文件」屏。它走的是本栈**已有**的层级
        // `piFiles`（`SettingsHome` 的入口行用的是同一个），不新造导航；那一屏是目录浏览器，
        // `skills/`、`prompts/`、`themes/`、`extensions/` 都在里面能看到和编辑。
        "app.resources.openFiles" to { piFiles = true },
    )

    BackHandler(
        enabled = searching || groupId != null || deviceCapabilities || packages || credentials || licenses || models || diagnostics || piFiles,
    ) {
        // `piFiles` 排在最前：它是这一栈里最深的一层，返回键先关它（回到设置首页），
        // 而不是继续往外退。正常情况下 `PiFilesScreen` 自己那个 `BackHandler` 会先拿到
        // 按键（组合顺序在后 → `OnBackPressedDispatcher` 的 LIFO），在那里"返回"是**退到
        // 上一级目录**、到根才是 `onBack()`；这一支是它不在组合里时的兜底。
        if (piFiles) {
            piFiles = false
        } else if (searching) {
            searching = false
        } else if (licenses) {
            licenses = false
        } else if (diagnostics) {
            diagnostics = false
        } else if (models) {
            models = false
        } else if (credentials) {
            credentials = false
        } else if (packages) {
            packages = false
        } else if (deviceCapabilities) {
            deviceCapabilities = false
        } else {
            if (backReturnsToSearch) searching = true
            groupId = null
            highlightKey = null
        }
    }

    // `SettingsManager.getDefaultProjectTrust()` reads this key and coerces a
    // missing value to `"ask"` (`settings-manager.ts:85`, `:1014-1017`); the
    // package screen asks the same question when a project declares resources.
    val defaultProjectTrust =
        (effectiveStore.read("defaultProjectTrust") as? JsonPrimitive)?.content ?: "ask"

    val currentGroup = groupId
    Box(Modifier.fillMaxSize()) {
        when {
            // 设置首页「其他 → Pi 文件」的目的地。放在最前与 BackHandler 的判定同序：
            // 它是这一栈里最深的一层。入口由 `SettingsHome` 的 `onOpenPiFiles` 传来
            // （见下面那处 `else ->`），所以这一屏的返回语义、层级与 `rememberSaveable`
            // 状态都留在这个栈里，与搜索 / 分组页同一套。
            piFiles -> PiFilesScreen(
                contentPadding = contentPadding,
                onBack = { piFiles = false },
            )

            licenses -> LicensesScreen(
                contentPadding = contentPadding,
                onBack = { licenses = false },
            )

            // The engine's last exit, the runtime tree, the APK's payloads and the
            // failures the app recorded — assembled into one text file the user can
            // send. Both lambdas read the **newest** capture every time the screen
            // rebuilds, because the engine can die while this screen is open.
            diagnostics -> DiagnosticsScreen(
                contentPadding = contentPadding,
                onBack = { diagnostics = false },
                engine = engineDiagnostics,
                failures = recentFailures,
                engineEntry = engineEntry,
            )

            credentials -> PiCredentialScreen(
                contentPadding = contentPadding,
                onBack = { credentials = false },
                restartEngine = restartEngine,
                isTurnRunning = isTurnRunning,
                initialPresetId = credentialPreset,
                availableModels = availableModels,
                onLoadAvailableModels = onLoadAvailableModels,
                onFilesWritten = onExternalSettingsWrite,
                // 与 设置 → 模型 共用一份"需要重启"状态，见上面 lifecycle 的注释。
                lifecycle = lifecycle,
                coordinator = coordinator,
            )

            models -> PiModelsScreen(
                contentPadding = contentPadding,
                onBack = { models = false },
                lifecycle = lifecycle,
                coordinator = coordinator,
                availableModels = availableModels,
                onLoadAvailableModels = onLoadAvailableModels,
                // 打开设置里已有的那一行，而不是在这里重做编辑器：默认模型与循环列表的
                // 编辑规则（通配符、pi 的默认值）已经在 `PiSettingsRegistry` 里写过一遍。
                onOpenSetting = { key ->
                    models = false
                    openSetting(key)
                },
                onOpenCredentials = { presetId ->
                    models = false
                    credentialPreset = presetId
                    credentials = true
                },
            )

            packages -> PiPackagesHost(
                contentPadding = contentPadding,
                onBack = { packages = false },
                restartEngine = restartEngine,
                isTurnRunning = isTurnRunning,
                defaultProjectTrust = defaultProjectTrust,
            )

            deviceCapabilities -> DeviceCapabilityScreen(
                store = DeviceCapabilityStore.get(LocalContext.current),
                onBack = { deviceCapabilities = false },
                contentPadding = contentPadding,
            )

            searching -> SettingsSearchScreen(
                store = effectiveStore,
                freshness = filesEpoch,
                contentPadding = contentPadding,
                onBack = { searching = false },
                onOpenSetting = openSetting,
                valueOverrides = runtimeOverrides(facts) + resourceOverrides,
            )

            currentGroup != null -> SettingsGroupScreen(
                groupId = currentGroup,
                store = effectiveStore,
                // 外部改了 settings.json 之后，值必须在界面上变。store 的缓存由
                // `onExternalSettingsWrite` 丢掉，而这个 epoch 才是让行重新读它的东西
                // （行是在 composition 里读 store 的，缓存失效本身不会触发重组）。
                freshness = filesEpoch,
                contentPadding = contentPadding,
                onBack = {
                    if (backReturnsToSearch) searching = true
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
                valueOverrides = runtimeOverrides(facts) + resourceOverrides,
                // The raw per-phase evidence under 运行时（实际生效）: read once with the
                // sentence above, rendered as a bounded string. See the state's comment.
                //
                // Suppressed while the switch's own flow is running: the reading in hand was
                // taken before the probe started, so it says 「探针尚未运行」 under a value that
                // says 「正在测 proroot 探针」. Two sentences contradicting each other is worse
                // than the block arriving one step later — it does, at `Idle`, with the verdict
                // the probe just recorded.
                detailOverrides = if (runtimeSwitch.inFlight) {
                    emptyMap()
                } else {
                    runtimeDetailOverrides(runtimeStatus)
                },
                // The same restart the 进程 section's action row asks for, offered
                // from the badge explanation of a 需重启引擎 row.
                onRestartEngine = { restartPrompt = true },
                // The top bar's search icon (v2's group page has one). It raises the
                // same level-2 search the home's search row does, and the back
                // handler already knows to return to the group afterwards.
                onOpenSearch = {
                    backReturnsToSearch = true
                    searching = true
                },
            )

            else -> SettingsHome(
                store = effectiveStore,
                contentPadding = contentPadding,
                onOpenGroup = { id ->
                    groupId = id
                    highlightKey = null
                    backReturnsToSearch = false
                },
                onOpenSearch = { searching = true },
                onOpenSetting = openSetting,
                onOpenDeviceCapabilities = { deviceCapabilities = true },
                onOpenPackages = { packages = true },
                onOpenLicenses = { licenses = true },
                onOpenTerminal = onOpenTerminal,
                // 首页「其他」里的模型行与「关于」里的诊断报告行和分组页里那两条 Action
                // 行是同一个目的地（`openModels` / `diagnostics`），所以这里只是把已有的
                // 两个状态开关接上去，不多开任何一页。
                onOpenModels = openModels,
                onOpenDiagnostics = { diagnostics = true },
                // 首页「其他」里的第三行。走这个栈自己的层级（`piFiles`），所以它的返回
                // 语义与状态恢复与上面几页完全一致；`SettingsHome` 只是把点击转上来。
                onOpenPiFiles = { piFiles = true },
            )
        }
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
 */
private val SETTINGS_WATCHED = listOf(
    "settings.json",
    "models.json",
    "auth.json",
    "models-store.json",
    "trust.json",
    "extensions",
    "skills",
    "prompts",
    "themes",
)
