package app.pi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
 * The three top-level destinations, in bottom-bar order.
 *
 * There were four until the v2 refactor (`design/ui-refactor/03-navigation-decision.md`):
 * `会话` was dropped because a session *list* is a selector, not a place — pi
 * itself draws its session tree over the transcript, and the desktop habit is a
 * sheet or a drawer. Two tabs for "the session I am in" and "which session"
 * gave the same thing two homes, and the user could not tell which one they were
 * in. `终端` was dropped for the opposite reason: the terminal is a fallback
 * entry point the user has declared unusable, and the bottom bar is the most
 * visible real estate in the app — it moves to one row on the settings home in a
 * later batch.
 *
 * `ordinal` is the bottom-bar order, as before, so reordering this enum reorders
 * the bar. The start destination stays [Chat]: cold start is a new conversation.
 */
enum class PiDestination(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Filled.ChatBubble),

    /**
     * The project on screen. It is still the old `WorkbenchScreen` (a full-screen
     * terminal) in this batch; the next batch replaces the body with the project
     * overview and keeps this name and position — the destination itself does not
     * move again.
     */
    Workbench("工作区", Icons.Filled.Dashboard),

    Settings("设置", Icons.Filled.Settings),
}

/**
 * What is drawn **over** the current destination, if anything.
 *
 * An overlay is deliberately not a [PiDestination]: a destination is a place the
 * bottom bar goes, and both of these belong to whatever session is on screen.
 * Making the session list a fourth tab is the mistake `03-navigation-decision.md`
 * records; keeping it out of the enum means the bottom bar cannot regress into
 * listing it again.
 *
 * These two are one layer, not two: the list and the tree are two views of the
 * same "which session" question and swap in place (the bar's later batch adds the
 * switch). The full-screen terminal joins them here, from the settings row, for
 * the same reason — it is not a place either.
 */
private enum class PiOverlay {
    /** The session list, opened from the chat screen's app-bar session name. */
    SessionList,

    /** pi's `/tree` — the session tree. */
    SessionTree,
}

/**
 * `rememberSaveable` cannot persist an enum (it is neither a `Bundle` value nor
 * `Serializable`), so the shown overlay is stored as its ordinal and mapped back.
 * Out-of-range values land on `null` rather than throwing: a restored state from a
 * build whose overlay set was smaller must not crash the launch.
 */
private fun overlayAt(index: Int?): PiOverlay? =
    index?.let { PiOverlay.entries.getOrNull(it) }

/**
 * The bottom inset assumed while an overlay covers the screen, when the
 * `Scaffold` reports none.
 *
 * The bottom bar is 80dp (`NavigationBar`'s own height), so the scaffold's
 * bottom padding is 80dp whenever the bar is drawn — which it always is, because
 * the overlay draws *inside* the scaffold's content box rather than replacing it.
 * The fallback is only for the case where that stops being true: a snackbar with
 * a zero inset sits under the system navigation bar, unseen. 80 + 8 is the same
 * 8dp the snackbar host below has always asked for.
 */
private val OVERLAY_SNACKBAR_INSET = 88.dp

@Composable
fun PiRoot() {
    // The destination is saved by **name**, not by ordinal. The ordinal was safe
    // while the enum only grew, but this refactor removed values from the front
    // of it, and a restored `0` would then land on a different destination than
    // the one the user left (`会话` is gone, so an old `0` must not become `对话`
    // silently — it must become the start destination). A name that no longer
    // exists falls back to the start destination on its own.
    var destinationName by rememberSaveable { mutableStateOf(PiDestination.Chat.name) }
    val current = PiDestination.entries.firstOrNull { it.name == destinationName }
        ?: PiDestination.Chat

    // One overlay slot for the whole app. See [PiOverlay] for why it is not a
    // destination; see the single `BackHandler` below for why it lives here and
    // nowhere else.
    var overlayIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val overlay = overlayAt(overlayIndex)

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
    //
    // Consuming it *inside* the effect is what lets the effect be keyed on the
    // request: the handler writes the request back to `null`, so the effect runs
    // once for the request and once for the `null`, and the second run is how the
    // same request can be made again (two `/tree` commands in a row).
    val uiState by session.state.collectAsState()
    val navRequest = uiState.navRequest
    LaunchedEffect(navRequest) {
        when (navRequest) {
            // The session list is an overlay, so the request only raises the
            // layer; it must not also move the destination. Landing on 对话
            // happens when a row is picked, which is what the user asked for by
            // picking it.
            NavRequest.SessionList -> {
                overlayIndex = PiOverlay.SessionList.ordinal
            }
            NavRequest.Chat -> {
                overlayIndex = null
                destinationName = PiDestination.Chat.name
            }
            NavRequest.Workbench -> {
                overlayIndex = null
                destinationName = PiDestination.Workbench.name
            }
            NavRequest.Settings -> {
                overlayIndex = null
                destinationName = PiDestination.Settings.name
            }
            is NavRequest.SettingsFocus -> {
                overlayIndex = null
                settingsFocus = navRequest.key
                destinationName = PiDestination.Settings.name
            }
            // The tree draws over whatever destination is active, so this request
            // deliberately leaves the destination alone.
            NavRequest.SessionTree -> {
                overlayIndex = PiOverlay.SessionTree.ordinal
                session.refreshTree()
            }
            null -> Unit
        }
        session.consumeNav()
    }

    Scaffold(
        // `imePadding()` is what makes the soft keyboard *displace* the UI instead of
        // covering it. The activity is `enableEdgeToEdge()` (`MainActivity.kt:19`) and
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
                        selected = current.ordinal == index,
                        onClick = { destinationName = item.name },
                        // Three entries at 412dp are ~137dp wide each, and each has
                        // to stay a ≥48dp touch target even when a large font scale
                        // clips the label. 48dp is written out rather than taken from
                        // a token because the spacing object has no touch-target
                        // member yet, and the file that holds it is being edited by
                        // another agent in this same round — a token added there
                        // would not be visible to this batch's compile. The later
                        // batch that owns that file can name it.
                        icon = {
                            Box(Modifier.heightIn(min = 48.dp)) {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (current) {
                PiDestination.Chat -> ChatScreen(
                    contentPadding = padding,
                    session = session,
                    // The composer's terminal affordance: one `NavRequest.Workbench`,
                    // which lands on the workbench destination. It used to name a tab
                    // as well ("open pi's TUI"), because the jump had to reach pi
                    // specifically; the terminal is a plain shell now and there is no
                    // tab to name, so this is the whole request. The affordance itself
                    // is due to disappear — the settings row is to be the only way in
                    // — which is a later batch's call, not the navigation's.
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

            // The overlays draw over whatever destination is active. They are
            // checked after the destination switch so they win the draw order, and
            // before the extension host so that a blocking dialog stays on top of
            // them: an extension waiting on an answer must be answerable from the
            // session list too (see the note on the single host below).
            val shown = overlay
            if (shown != null) {
                // Opaque, not translucent: the transcript underneath must not
                // read as part of this layer (the ask that produced this batch's
                // device checklist, §7.2 item 10). `Surface` with the scheme's
                // background is the same backdrop the destinations themselves
                // draw on — the same call `SessionTreeScreen.kt:100` makes.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (shown) {
                        PiOverlay.SessionList -> SessionsScreen(
                            contentPadding = padding,
                            // A pick switches the session and lands on it. The
                            // overlay closes because otherwise the user stays on
                            // the list they just answered.
                            onOpenChat = {
                                overlayIndex = null
                                destinationName = PiDestination.Chat.name
                            },
                            session = session,
                        )

                        PiOverlay.SessionTree -> SessionTreeScreen(
                            state = uiState,
                            onFork = { entryId ->
                                session.forkFrom(entryId)
                                overlayIndex = null
                            },
                            onRefresh = { session.refreshTree() },
                            onClose = { overlayIndex = null },
                        )
                    }
                }
            }

            // The app's **only** overlay-back handler, and the reason it exists:
            // before it, the back key left the app while an overlay was open —
            // the handler the overlay would otherwise need did not exist
            // (`00-screen-inventory.md` §1.4).
            //
            // It is one call site by construction. Compose keeps every
            // `BackHandler` alive at once and hands the press to the
            // **most recently composed** one, so a second handler inside an
            // overlay would silently shadow this one, and two handlers that both
            // claim to close "the overlay" are the exact defect this batch is
            // told to avoid: the press goes to the wrong one and the app exits.
            // It is placed after the overlay in composition order for that same
            // reason — if the destination under an overlay ever registers a
            // handler of its own (the settings stack already has one, for its
            // internal levels), this one still gets the press.
            //
            // `PiSettingsStack.kt:269` keeps its own handler: it is a
            // *navigation* back (search → group → out), not an overlay back, and
            // it is enabled only while an inner level is open, which the overlay
            // covers anyway.
            BackHandler(enabled = overlay != null) { overlayIndex = null }

            // Mounted once, above the destination switch, because an extension
            // dialog is not chat-specific: `notify` and `extension_error` arrive
            // wherever the user happens to be, and a dialog nobody can see is a
            // blocked engine — `editor` has no timer on pi's side at all
            // (packages/coding-agent/src/modes/rpc-mode.ts:254-271).
            //
            // It must not also be mounted by a screen. Two hosts would render
            // two dialogs for one request, and two snackbar hosts would race for
            // the same notice queue; the screens' KDocs say so for that reason.
            //
            // The padding has to survive `calculateBottomPadding()` returning 0.
            // The scaffold knows the bottom bar's height and reports it, so the
            // normal `+ 8.dp` is the bar plus a margin; a `maxOf` with a constant
            // keeps the snackbar above the bar even if that stops being reported.
            ExtensionUiHost(
                session = session,
                snackbarBottomPadding = maxOf(
                    padding.calculateBottomPadding() + 8.dp,
                    OVERLAY_SNACKBAR_INSET,
                ),
            )
        }
    }
}
