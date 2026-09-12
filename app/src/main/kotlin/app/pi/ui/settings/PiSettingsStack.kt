package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.bridge.DeviceCapabilityStore
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.PiPackagesHost
import app.pi.rpc.PiResponses
import app.pi.runtime.PiPaths
import app.pi.ui.device.DeviceCapabilityScreen
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
) {
    val activeStore = store ?: rememberInMemoryPiSettingsStore()
    var groupId by remember { mutableStateOf<String?>(null) }
    var highlightKey by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var backReturnsToSearch by remember { mutableStateOf(false) }
    // The device capability screen is the one branch of this stack that is not a
    // group in pi's settings catalog — the switches grant the *phone's* abilities
    // to the agent, and pi has no such settings (docs/pi-android-app-design.md §21).
    var deviceCapabilities by remember { mutableStateOf(false) }
    // pi installs resources with `pi install`, which has no RPC command, so the
    // app owns this UI; the screen is `app.pi.packages.PiPackagesHost`.
    var packages by remember { mutableStateOf(false) }
    // The credential form. `app.credentials.apiKey` (`PiSettingsRegistry.kt:369`)
    // and `app.localModels.manage` (`:387`) are Action rows whose work is a
    // multi-step flow, so they own a screen instead of a confirm dialog.
    var credentials by remember { mutableStateOf(false) }
    var credentialPreset by remember { mutableStateOf<String?>(null) }
    // The open-source licence notices. Not a pi screen either — see §L: publishing
    // the licences of what this app redistributes is the distributor's obligation,
    // so the screen is the app's own.
    var licenses by remember { mutableStateOf(false) }

    // The 运行时 group's four read-only rows: their facts live in the runtime
    // tree and in the engine's foreground service, not in the settings store, so
    // they are read here and passed down as overrides. The read is a directory
    // walk plus a few small files, hence IO, hence a state that starts null
    // (rows show 未读取 for exactly one frame).
    val context = LocalContext.current
    val runtimeFacts = remember(context) {
        RuntimeFacts(
            context = context.applicationContext,
            paths = PiPaths(
                filesDir = context.filesDir,
                nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
            ),
        )
    }
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
    val hostActions: Map<String, () -> Unit> = mapOf(
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
    )

    BackHandler(
        enabled = searching || groupId != null || deviceCapabilities || packages || credentials || licenses,
    ) {
        if (searching) {
            searching = false
        } else if (licenses) {
            licenses = false
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
        (activeStore.read("defaultProjectTrust") as? JsonPrimitive)?.content ?: "ask"

    val currentGroup = groupId
    Box(Modifier.fillMaxSize()) {
        when {
            licenses -> LicensesScreen(
                contentPadding = contentPadding,
                onBack = { licenses = false },
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
                store = activeStore,
                contentPadding = contentPadding,
                onBack = { searching = false },
                onOpenSetting = openSetting,
                valueOverrides = runtimeOverrides(facts),
            )

            currentGroup != null -> SettingsGroupScreen(
                groupId = currentGroup,
                store = activeStore,
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
                onSettingWritten = onSettingWritten,
                onRunAction = onRunAction,
                hostActions = hostActions,
                valueOverrides = runtimeOverrides(facts),
                // The same restart the 进程 section's action row asks for, offered
                // from the badge explanation of a 需重启引擎 row.
                onRestartEngine = { restartPrompt = true },
            )

            else -> SettingsHome(
                store = activeStore,
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
            )
        }
    }

    // The 进程 section's "restart so these take effect" step. Two dialogs, not
    // one: the first states the cost before anything happens, the second reports
    // what the engine answered (it refuses on its own while a turn is running, and
    // its sentence is the one worth showing).
    if (restartPrompt) {
        AlertDialog(
            onDismissRequest = { restartPrompt = false },
            title = { Text("重启引擎") },
            text = {
                Text(
                    if (isTurnRunning()) {
                        "现在有回合正在运行。重启会终止模型调用、工具调用与正在跑的命令，它们都不会恢复；" +
                            "已写入磁盘的会话不会丢失。"
                    } else {
                        "重启会终止正在进行的回合，已写入磁盘的会话不会丢失。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
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
                ) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { restartPrompt = false }) { Text("取消") }
            },
        )
    }

    val restartResult = restartNote
    if (restartResult != null) {
        AlertDialog(
            onDismissRequest = { restartNote = null },
            title = { Text("重启引擎") },
            text = { Text(restartResult) },
            confirmButton = {
                TextButton(onClick = { restartNote = null }) { Text("知道了") }
            },
        )
    }
}
