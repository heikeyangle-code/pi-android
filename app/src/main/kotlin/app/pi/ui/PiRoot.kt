package app.pi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
fun PiRoot(
    isDark: Boolean,
) {
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
                )

                PiDestination.Workbench -> WorkbenchScreen(contentPadding = padding)

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
                    // else arrives here, and a row nothing implements says so
                    // instead of doing nothing — a dead row is worse than an
                    // absent one (docs/known-gaps.md §E9 / I2).
                    onRunAction = { setting ->
                        when (setting.key) {
                            // pi's `/compact` — the RPC command is `compact`
                            // (`modes/rpc/rpc-types.ts:44`). The row also promises a
                            // custom instruction, which needs a text field; the chat
                            // palette's `/compact` takes one, this entry does not.
                            "app.compaction.runNow" -> session.compact()
                            // OAuth is not in the protocol at all: `RpcCommand`
                            // (`modes/rpc/rpc-types.ts:20-74`) has no login/logout,
                            // and pi's OAuth flows live in its interactive mode. The
                            // workbench's original-TUI tab is the surface that has it.
                            "app.credentials.oauth" -> session.notifyUser(
                                "OAuth 登录只能在 工作区 → pi TUI（原版）里做：pi 的 RPC 协议里没有 login 命令" +
                                    "（modes/rpc/rpc-types.ts:20-74），OAuth 流程只存在于它的交互模式里。",
                                warning = true,
                            )
                            else -> session.notifyUser(
                                "「${setting.title}」还没有接入实现（docs/known-gaps.md §E9 / I2），" +
                                    "当前点它不会有任何动作。",
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
                    // `get_available_models`: the only model list pi exposes over
                    // RPC, used to mark a scanned id as pi metadata or as an app
                    // default (`PiCredentialScreen`).
                    availableModels = uiState.models,
                    onLoadAvailableModels = { session.refreshModels() },
                    // `/scoped-models`: open the group and highlight the key.
                    focusKey = settingsFocus,
                    onFocusConsumed = { settingsFocus = null },
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
