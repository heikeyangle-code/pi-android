package app.pi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.extension.ExtensionUiHost
import app.pi.ui.screens.ChatScreen
import app.pi.ui.screens.SessionsScreen
import app.pi.ui.screens.WorkbenchScreen
import app.pi.ui.settings.PiSettingsStack

/**
 * The four top-level destinations.
 *
 * pi itself has one surface (the transcript) and reaches everything else through
 * slashes; on a phone those need permanent homes. These four are the mapping of
 * "pick a session / talk / work / configure" onto a bottom bar
 * (docs/pi-android-ui-spec.md §3.1). Deliberately four — more than four
 * destinations on a phone means mis-taps.
 */
enum class PiDestination(val label: String, val icon: ImageVector) {
    Sessions("会话", Icons.Filled.Forum),
    Chat("对话", Icons.Filled.ChatBubble),
    Workbench("工作区", Icons.Filled.Terminal),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
fun PiRoot() {
    var destination by rememberSaveable { mutableStateOf(PiDestination.Chat.ordinal) }
    val current = PiDestination.entries[destination]

    // The session tree is an overlay rather than a fifth destination: it belongs
    // to the session, not to a place in the app, and pi's own TUI opens it over
    // the transcript for the same reason. Four bottom-bar entries stays true.
    var treeOpen by rememberSaveable { mutableStateOf(false) }

    // A settings key a command asked to open (pi's `/scoped-models` — see
    // `NavRequest.SettingsFocus`). It lives here rather than inside the stack
    // because the stack is composed only while 设置 is the current destination,
    // so the request has to survive that switch.
    var settingsFocus by rememberSaveable { mutableStateOf<String?>(null) }

    // One engine for the whole app. Owned by the ViewModel rather than an
    // Activity so that a running turn survives the user leaving the screen, and
    // started here because the app has no "connect" concept: opening it starts
    // the local engine.
    val session: PiSessionViewModel = viewModel()
    LaunchedEffect(Unit) { session.boot() }

    // The theme picker lists what pi would load, not only what the `themes`
    // setting names, and the active theme's caveats are shown with it. Both come
    // from the ViewModel, which is the one place that reads pi's theme files.
    val theme by session.theme.collectAsState()
    val themeEntries by session.themeEntries.collectAsState()

    // Commands that need a destination are requested by the ViewModel through
    // state, because they finish inside a coroutine after an RPC answer — by then
    // there is no composable left to call back into. Consuming the request here
    // keeps the ViewModel free of Compose navigation knowledge.
    val uiState by session.state.collectAsState()
    val navRequest = uiState.navRequest
    LaunchedEffect(navRequest) {
        when (navRequest) {
            NavRequest.Sessions -> {
                treeOpen = false
                destination = PiDestination.Sessions.ordinal
            }
            NavRequest.Chat -> {
                treeOpen = false
                destination = PiDestination.Chat.ordinal
            }
            NavRequest.Workbench -> {
                treeOpen = false
                destination = PiDestination.Workbench.ordinal
            }
            NavRequest.Settings -> {
                treeOpen = false
                destination = PiDestination.Settings.ordinal
            }
            is NavRequest.SettingsFocus -> {
                treeOpen = false
                settingsFocus = navRequest.key
                destination = PiDestination.Settings.ordinal
            }
            NavRequest.SessionTree -> {
                treeOpen = true
                session.refreshTree()
            }
            null -> Unit
        }
        if (navRequest != null) session.consumeNav()
    }

    Scaffold(
        // `imePadding()` is what makes the soft keyboard *displace* the UI instead of
        // covering it. The activity is `enableEdgeToEdge()` (`MainActivity.kt:18`) and
        // the manifest asks for `adjustResize` (`AndroidManifest.xml:118`), but with
        // edge-to-edge the framework stops resizing the window for the IME and reports
        // it as an inset instead — so `adjustResize` alone lifts nothing, and the
        // composer, its toolbar and the bottom bar all ended up behind the keyboard
        // (seen on device, docs/known-gaps.md §M2). Applied here, once, for every
        // destination: the terminal used to carry its own `imePadding()` and would now
        // pad twice, so that one was removed.
        modifier = Modifier.imePadding(),
        bottomBar = {
            NavigationBar {
                PiDestination.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = destination == index,
                        onClick = { destination = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (current) {
                PiDestination.Sessions -> SessionsScreen(
                    contentPadding = padding,
                    onOpenChat = { destination = PiDestination.Chat.ordinal },
                    session = session,
                )

                PiDestination.Chat -> ChatScreen(
                    contentPadding = padding,
                    session = session,
                    // The composer's terminal affordance: one `NavRequest.Workbench`,
                    // which lands on the workbench's terminal segment. It used to
                    // name a tab as well ("open pi's TUI"), because the jump had to
                    // reach pi specifically; the terminal is a plain shell now and
                    // there is no tab to name, so this is the whole request.
                    onOpenTerminal = { session.requestNav(NavRequest.Workbench) },
                )

                PiDestination.Workbench -> WorkbenchScreen(
                    contentPadding = padding,
                )

                PiDestination.Settings -> PiSettingsStack(
                    contentPadding = padding,
                    // The real store: pi's own settings.json files, merged the way
                    // pi merges them. Without it the stack would read and write an
                    // in-memory map and the app would appear to accept changes
                    // that never reach the engine.
                    store = session.settingsStore,
                    knownThemes = themeEntries,
                    themeNotes = theme.notes,
                    themeError = theme.error,
                    // A written setting the app itself consumes (appearance, the
                    // transcript's thinking toggle, the theme) has to be noticed:
                    // the store is pi's file, and it has no change notification.
                    onSettingWritten = { key -> session.onSettingWritten(key) },
                    // The Action rows the engine has to run. The settings stack
                    // implements the ones that need a screen of its own (the
                    // credential form, `PiSettingsStack.hostActions`); everything
                    // else arrives here.
                    //
                    // There used to be a `TERMINAL_ONLY_ACTIONS` map holding three
                    // signposts to pi's own TUI (`/login`, `/import`, `/changelog`).
                    // All three are gone now: a settings row whose only content is "go
                    // somewhere else" is not a setting. The last survivor — the OAuth
                    // row — used to move the user to the terminal tab; the terminal is
                    // not a usable surface, so that action could not be completed and
                    // the row now answers with what the app actually supports.
                    onRunAction = { setting ->
                        when (setting.key) {
                            // pi keeps OAuth in its own interactive shell
                            // (`interactive-mode.ts:3052-3055` → `handleLoginCommand`
                            // `:5485`) and the `RpcCommand` union has no login command
                            // (`modes/rpc/rpc-types.ts:20-74`), so there is no surface
                            // in this app that can start it. Say that, and say what
                            // this app does support (an API key, on the row above);
                            // do not navigate anywhere. Protocol detail stays in the
                            // KDoc, not on screen.
                            "app.credentials.oauth" -> {
                                session.notifyUser(
                                    "订阅登录要在 pi 的原版 TUI 里做，本应用没有入口。" +
                                        "用 API Key 的厂商可以在上面的「API Key」里配置。",
                                    warning = true,
                                )
                            }
                            // pi's `/compact` — the RPC command is `compact`
                            // (`modes/rpc/rpc-types.ts:44`). The custom-instruction
                            // form of `/compact` needs a text field and lives in the
                            // chat palette (`ChatScreen.kt:341`), which this row's
                            // description now names; this entry triggers the bare one.
                            "app.compaction.runNow" -> session.compact()
                            // pi's Escape, as a button: `clear_queue` + `abort`
                            // (`PiEngineSession.stopAndDrainQueue` `:480-486`) plus
                            // `abort_bash` (`PiEngineApi.kt:262`), which aborts
                            // *every* running bash command, not just one
                            // (`agent-session.ts:3073-3077`). Nothing is killed
                            // outside those RPC commands, which is exactly what the
                            // row's description promises.
                            "app.security.emergencyStop" -> {
                                session.stop()
                                session.abortBash()
                            }
                            // Reachable for no `PiRowKind.Action` row in the registry
                            // today: the credential rows are handled by
                            // `PiSettingsStack.hostActions`, and the three above are
                            // the ones this host runs. It stays as a regression guard
                            // so a row added later without a handler is loud instead of
                            // silent — the failure `docs/known-gaps.md` §I2 records.
                            // The text names no internal cause on purpose: a user
                            // cannot act on "this row has no handler".
                            else -> session.notifyUser(
                                "「${setting.title}」当前不可用，请把这一步报告给我们。",
                                warning = true,
                            )
                        }
                    },
                    // The engine restart the package and credential screens ask
                    // for. `allowInterrupt` stays whatever the coordinator passes
                    // (always false); a turn can never be killed by a settings tap.
                    restartEngine = { reason, allowInterrupt ->
                        session.restartEngine(reason, allowInterrupt)
                    },
                    isTurnRunning = { session.isTurnRunning() },
                    // 导出诊断报告的两个事实来源。都是 lambda：报告必须在**打开那一屏
                    // 的时刻**读到最新的抓取，而引擎可能在那一屏开着的时候死掉。
                    // `engineDiagnostics` 来自引擎退出瞬间的捕获（ViewModel 在丢弃死引擎
                    // 之前存下的退出码与 stderr），`recentFailures` 来自 App 已经记下的失败。
                    // 没有这两条接线，报告里那两节永远只会写"还没有记录"。
                    engineDiagnostics = { session.engineDiagnostics() },
                    recentFailures = { session.recentFailures() },
                    // `get_available_models`: the only model list pi exposes over
                    // RPC, used to mark a scanned id as pi metadata or as an app
                    // default (`PiCredentialScreen`).
                    availableModels = uiState.models,
                    onLoadAvailableModels = { session.refreshModels() },
                    // `/scoped-models`: open the group and highlight the key.
                    focusKey = settingsFocus,
                    onFocusConsumed = { settingsFocus = null },
                    // The credential form writes settings.json through the packages
                    // layer, which invalidates a store instance of its own; the store
                    // this app reads belongs to the ViewModel, so dropping its cache
                    // is the ViewModel's job.
                    onExternalSettingsWrite = { session.invalidateSettingsCache() },
                )
            }

            // The session tree draws over whatever destination is active, and
            // before the extension host so a blocking dialog stays on top of it:
            // an extension waiting on an answer must be answerable from the tree
            // screen too (see the note on the single host below).
            if (treeOpen) {
                SessionTreeScreen(
                    state = uiState,
                    onFork = { entryId ->
                        session.forkFrom(entryId)
                        treeOpen = false
                    },
                    onRefresh = { session.refreshTree() },
                    onClose = { treeOpen = false },
                )
            }

            // Mounted once, above the destination switch, because an extension
            // dialog is not chat-specific: `notify` and `extension_error` arrive
            // wherever the user happens to be, and a dialog nobody can see is a
            // blocked engine — `editor` has no timer on pi's side at all
            // (packages/coding-agent/src/modes/rpc-mode.ts:254-271).
            //
            // It must not also be mounted by a screen. Two hosts would render
            // two dialogs for one request, and two snackbar hosts would race for
            // the same notice queue; the screens' KDocs say so for that reason.
            ExtensionUiHost(
                session = session,
                snackbarBottomPadding = padding.calculateBottomPadding() + 8.dp,
            )
        }
    }
}
