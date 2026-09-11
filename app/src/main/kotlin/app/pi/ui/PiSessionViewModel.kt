package app.pi.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineApi
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.engine.PiRpcException
import app.pi.rpc.ErrorText
import app.pi.rpc.Notice
import app.pi.rpc.PiCommands
import app.pi.rpc.PiEvent
import app.pi.rpc.PiImage
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import app.pi.rpc.SessionEntry
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem
import app.pi.runtime.RuntimeProvisioner
import app.pi.session.PiSessionStore
import app.pi.service.PiEngineService
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readBoolean
import app.pi.settings.readString
import app.pi.ui.chat.PiCommandAction
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.TuiOnlyExtension
import app.pi.ui.chat.piCommandPalette
import app.pi.ui.chat.tuiOnlyMarkers
import app.pi.ui.extension.ComposerFill
import app.pi.ui.extension.ExtensionAnswer
import app.pi.ui.extension.ExtensionDialog
import app.pi.ui.extension.ExtensionDialogMethod
import app.pi.ui.extension.ExtensionDialogQueue
import app.pi.ui.extension.ExtensionNotice
import app.pi.ui.extension.ExtensionStatus
import app.pi.ui.extension.ExtensionWidget
import app.pi.ui.extension.WidgetPlacement
import app.pi.ui.extension.noticeToneOf
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.theme.PiResolvedTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Boot progress, as a first-class state rather than a boolean.
 *
 * Top-level rather than nested inside the ViewModel because the screens render
 * it: [BootScreen] takes one to draw the unpacking steps and the failure detail,
 * and nesting it would force every call site to qualify through the ViewModel
 * for no benefit.
 */
sealed interface Boot {
    data object Idle : Boot

    /** Unpacking the runtime, with the step to show. */
    data class Working(val step: RuntimeProvisioner.Step) : Boot

    /** The engine process is up; the transcript can be rendered. */
    data object Ready : Boot

    /**
     * Boot failed. [message] is written for a person; [detail] carries the raw
     * stderr or exception text, which is the only thing that lets a failure be
     * diagnosed remotely.
     */
    data class Failed(val message: String, val detail: String?) : Boot
}

/**
 * A destination change the ViewModel asks the UI to perform.
 *
 * Requested through state rather than a callback because the action that needs
 * it usually ends in a coroutine after an RPC answer (`switch_session`, a
 * palette row) — by then there is no composable on the stack to call. pi itself
 * has no navigation API at all, so this vocabulary is the app's own and belongs
 * to the layer that knows the answer arrived.
 */
sealed interface NavRequest {
    data object Sessions : NavRequest
    data object Chat : NavRequest
    data object Workbench : NavRequest

    data object Settings : NavRequest

    /** The session tree overlay (pi's `/tree`). */
    data object SessionTree : NavRequest
}

/**
 * One `bash` run, as the GUI needs to draw it.
 *
 * pi records a finished run as a `BashExecutionMessage` in the session
 * (`agent-session.ts` `recordBashResult`) and streams output as
 * `bash_execution_update` while it runs (`:3027`), but it emits **no event for
 * the result itself** — the only place the final `BashResult` appears is the
 * `bash` command's own response (`rpc-mode.ts:563-584`). This state is
 * therefore assembled from both: deltas from the event stream, the final fields
 * from the awaited response.
 */
data class BashRun(
    val command: String,
    /** pi's `!!` prefix: the output is not added to the model's context. */
    val excludeFromContext: Boolean,
    val output: String = "",
    val running: Boolean = true,
    /** Null while running, and for a run pi reports as cancelled. */
    val exitCode: Int? = null,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    val fullOutputPath: String? = null,
)

/**
 * App-local UI preferences: the settings whose only reader is this app.
 *
 * Every one of these keys is declared by the settings registry and by nothing
 * else, so before this type existed the rows wrote a JSON value that no code
 * ever consulted — a switch that looks like it works and cannot. They are read
 * here once and published through [UiState.prefs] so the transcript and the theme
 * actually follow them.
 *
 * `hideThinkingBlock` is the one exception: it is **pi's own** setting
 * (`core/settings-manager.ts:119`, `:962`), the registry row only mirrored it,
 * and the transcript was never told about it.
 *
 * The four `app.terminal.*` rows are siblings of these and deliberately not
 * repeated here: `ui/terminal/TerminalSettings.kt` is their consumer, so a second
 * reader in the ViewModel would be a second truth about the same key.
 */
data class UiPrefs(
    val fontScaleDelta: Int = 0,
    val messageDensity: String = "comfortable",
    val showTimestamps: Boolean = true,
    val thinkingCollapsedByDefault: Boolean = true,
    val expandToolsByDefault: Boolean = false,
    /** pi's `hideThinkingBlock`. */
    val hideThinkingBlock: Boolean = false,
    /**
     * `app.runtime.keepAlive`: whether the foreground service is started at all
     * (`PiEngineService` owns the wake lock, so this is the only honest meaning
     * the switch can have).
     */
    val keepAlive: Boolean = true,
)

/**
 * Owns the engine for the life of the UI and projects it into renderable state.
 *
 * The app has no "connect" concept and no connection UI: opening the app starts
 * the local engine (docs/pi-android-app-design.md §7). Booting is multi-stage —
 * unpack the runtime the first time, verify it can execute, then spawn pi — and
 * each stage is surfaced rather than hidden behind a spinner, because the first
 * one takes tens of seconds and the second one can legitimately fail.
 *
 * Note what this class does **not** do: it never re-derives conversation state.
 * pi owns the session; the transcript here is a projection, and re-attaching
 * replays pi's own records from `get_entries`. Every RPC call goes through
 * [PiEngineApi] — the typed façade over pi's command surface — so a command pi
 * can answer is never a hand-built `JsonObject` here.
 *
 * It *is* the owner of the extension UI protocol, because that protocol is a
 * request/response conversation on the same stream and needs one authority for
 * "which request is still answerable" — see [onExtensionUi].
 */
class PiSessionViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Live facts about the session, mirrored from pi.
     *
     * Everything here is read back from pi (`get_state`,
     * `get_available_thinking_levels`, `get_session_stats`) rather than tracked
     * optimistically, with one documented exception:
     *
     *  - [autoRetry] — `get_state` has no field for it. pi keeps it in
     *    `settingsManager.getRetryEnabled()` (`settings-manager.ts:914-916`:
     *    `retry?.enabled ?? true`), so the value is seeded from the same settings
     *    document the settings screens read and then updated from what
     *    `set_auto_retry` accepted.
     *
     * [thinkingLevel] is likewise not written optimistically:
     * `set_thinking_level` clamps to the model's supported set and only emits
     * `thinking_level_changed` when the value actually changes
     * (`agent-session.ts` `setThinkingLevel`), so that event is the truth.
     */
    data class EngineMeta(
        val model: PiResponses.ModelInfo? = null,
        /** pi's own default (`core/defaults.ts:3`), until the first `get_state` lands. */
        val thinkingLevel: String = "medium",
        /** `get_available_thinking_levels`; the set is derived from the model. */
        val thinkingLevels: List<String> = emptyList(),
        val steeringMode: QueueMode = QueueMode.OneAtATime,
        val followUpMode: QueueMode = QueueMode.OneAtATime,
        val autoCompaction: Boolean = false,
        val autoRetry: Boolean = true,
        val sessionName: String? = null,
        val sessionId: String? = null,
        val sessionFile: String? = null,
        val messageCount: Int = 0,
    )

    data class UiState(
        val boot: Boot = Boot.Idle,
        val engine: PiEngineSession.EngineState? = null,
        val transcript: List<TranscriptItem> = emptyList(),
        val revision: Int = 0,
        val streaming: Boolean = false,
        val queueSteering: Int = 0,
        val queueFollowUp: Int = 0,
        val lastError: String? = null,
        /**
         * The blocking `extension_ui_request` on screen right now — the head of
         * the ViewModel's FIFO queue. Null when nothing is pending.
         */
        val extensionDialog: ExtensionDialog? = null,
        /** Requests waiting behind [extensionDialog], in arrival order. */
        val extensionDialogBacklog: Int = 0,
        /** `setStatus` entries, oldest key first. */
        val extensionStatuses: List<ExtensionStatus> = emptyList(),
        /** `setWidget` panels. Empty lines cleared the key, so none here are empty. */
        val extensionWidgets: List<ExtensionWidget> = emptyList(),
        /** `setTitle`: the name pi wants this session shown under. */
        val windowTitle: String? = null,
        /** A `set_editor_text` waiting to be applied to the composer, then cleared. */
        val composerFill: ComposerFill? = null,
        /** Snackbar queue for `notify` and `extension_error`, oldest first. */
        val notices: List<ExtensionNotice> = emptyList(),

        // -------------------------------------------------------- session facts
        /** Model, thinking level, queue modes, session identity — see [EngineMeta]. */
        val meta: EngineMeta = EngineMeta(),
        /** `get_available_models`, loaded when the model picker opens. */
        val models: List<PiResponses.ModelInfo> = emptyList(),
        /**
         * The `/` palette: pi's built-ins plus `get_commands`. Empty until the
         * first load — an empty palette is ambiguous, hence [commandsLoaded].
         */
        val commands: List<PiSlashCommand> = emptyList(),
        val commandsLoaded: Boolean = false,
        /** `get_session_stats`, loaded when the stats sheet opens. */
        val stats: PiResponses.SessionStats? = null,
        /** `get_tree` — branches, for the session tree overlay. */
        val tree: PiResponses.TreePage? = null,
        /** `get_entries` — the append-only entry log, for the same overlay. */
        val entries: List<SessionEntry> = emptyList(),
        /** `get_fork_messages` — user messages a fork can start from. */
        val forkMessages: List<PiResponses.ForkMessage> = emptyList(),
        /** The running or last `bash` command, if any. */
        val bash: BashRun? = null,
        /**
         * Installed extensions that use a surface only the original TUI can
         * carry. Heuristic (a source scan) because pi reports nothing — see
         * [tuiOnlyMarkers] for why that is the only option.
         */
        val tuiOnlyExtensions: List<TuiOnlyExtension> = emptyList(),
        /** Label of the long RPC call in flight, for a progress line. */
        val busy: String? = null,
        /** Set by the ViewModel, consumed by `PiRoot`. */
        val navRequest: NavRequest? = null,
        /**
         * App-local UI preferences read from pi's settings documents. Seeded at
         * boot and refreshed whenever the settings stack writes a key that the
         * app itself consumes — see [onSettingWritten].
         */
        val prefs: UiPrefs = UiPrefs(),
    )

    private val host = PiEngineHost(app)
    private var session: PiEngineSession? = null

    /**
     * The typed façade over the live engine, rebuilt whenever an engine is
     * attached. Null exactly when there is no engine to talk to, which is what
     * every action below checks before doing anything.
     */
    private var api: PiEngineApi? = null

    /**
     * pi's settings documents, addressed exactly as a desktop install has them.
     *
     * The engine host owns the path layout, so the store is built from it rather
     * than from a second guess at where things live. Reads merge the project
     * document over the global one the way pi does; writes land in whichever file
     * already carries the key, so a project override is not shadowed.
     */
    val settingsStore: PiSettingsStore by lazy {
        PiSettingsFileStore.forWorkspace(
            agentDir = host.paths().agentDir,
            workspace = defaultWorkspace(),
        )
    }

    /**
     * pi's session index, read straight off disk.
     *
     * pi's RPC surface has no list-sessions command — it can only switch to a path
     * you already know — so the list has to come from the files, exactly as the
     * desktop picker does.
     */
    private val sessionStore: PiSessionStore by lazy {
        PiSessionStore(File(host.paths().agentDir, "sessions"))
    }

    private val _sessions = MutableStateFlow<List<PiSessionStore.Summary>>(emptyList())
    val sessions: StateFlow<List<PiSessionStore.Summary>> = _sessions.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessions.value = runCatching { sessionStore.list() }.getOrDefault(emptyList())
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ------------------------------------------------------- theme and prefs

    /**
     * The palette the whole app paints with, resolved from the `theme` setting.
     *
     * pi's theme loader is the specification: `theme.ts:555` reads a theme file,
     * `theme.ts:228-244` resolves its colour values, and
     * `resource-loader.ts:872-902` decides which files exist at all. Reading it
     * here — rather than keeping two hand-written palettes in `PiPalette` — is
     * what makes a desktop-tuned theme change this app, which is the written
     * intent of `PiPalette.kt`.
     */
    private val _theme = MutableStateFlow(PiResolvedTheme.fallback(systemDark = true))
    val theme: StateFlow<PiResolvedTheme> = _theme.asStateFlow()

    /**
     * Every theme name the picker may offer, with the file behind it.
     *
     * pi discovers `~/.pi/agent/themes` and the project's `.pi/themes` even when
     * the `themes` setting names neither (`resource-loader.ts:872-880`), so the
     * picker must not be limited to the setting's own list.
     */
    private val _themeEntries = MutableStateFlow<List<PiThemeEntry>>(emptyList())
    val themeEntries: StateFlow<List<PiThemeEntry>> = _themeEntries.asStateFlow()

    /** The system appearance, remembered so the `a/b` pair can be re-resolved. */
    private var lastSystemDark: Boolean = true

    /**
     * Seed the app-side preferences and palette before the engine is up.
     *
     * Called by `MainActivity` for the first frame and again whenever the system
     * appearance changes, because the automatic theme form `light/dark` is
     * resolved against it.
     */
    fun startUiPreferences(systemDark: Boolean) {
        lastSystemDark = systemDark
        refreshPrefs()
        refreshTheme(systemDark)
    }

    /** Re-read pi's theme selection and re-resolve the palette. */
    fun refreshTheme(systemDark: Boolean = lastSystemDark) {
        lastSystemDark = systemDark
        viewModelScope.launch {
            val configured = configuredThemePaths()
            val setting = runCatching { settingsStore.readString("theme") }.getOrNull()
            val resolved = withContext(Dispatchers.IO) {
                PiThemeLoader.load(
                    agentDir = host.paths().agentDir,
                    workspace = defaultWorkspace(),
                    configured = configured,
                    setting = setting,
                    systemDark = systemDark,
                )
            }
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    PiThemeLoader.discover(host.paths().agentDir, defaultWorkspace(), configured)
                }.getOrDefault(emptyList())
            }
            _theme.value = resolved
            _themeEntries.value = entries
        }
    }

    /** The `themes` setting, verbatim; used for discovery and for name lookup. */
    private fun configuredThemePaths(): List<String> =
        runCatching {
            (settingsStore.read("themes") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
                .orEmpty()
        }.getOrDefault(emptyList())

    /**
     * Re-read [UiPrefs] from the settings documents.
     *
     * Runs off the main thread because the first read of each document hits the
     * disk; `PiSettingsFileStore` caches afterwards, so the settings stack's own
     * per-frame reads are unaffected.
     */
    fun refreshPrefs() {
        viewModelScope.launch {
            val prefs = withContext(Dispatchers.IO) { readPrefs() }
            _state.value = _state.value.copy(prefs = prefs)
        }
    }

    private fun readPrefs(): UiPrefs {
        fun string(key: String, fallback: String): String =
            settingsStore.readString(key)?.takeIf { it.isNotBlank() } ?: fallback

        fun bool(key: String, fallback: Boolean): Boolean =
            settingsStore.readBoolean(key) ?: fallback

        fun int(key: String, fallback: Int, range: IntRange): Int =
            ((settingsStore.read(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback)
                .coerceIn(range)

        return UiPrefs(
            fontScaleDelta = int("app.appearance.fontScaleDelta", 0, -2..2),
            messageDensity = string("app.appearance.messageDensity", "comfortable"),
            showTimestamps = bool("app.appearance.showTimestamps", true),
            thinkingCollapsedByDefault = bool("app.appearance.thinkingCollapsedByDefault", true),
            expandToolsByDefault = bool("app.tools.expandByDefault", false),
            hideThinkingBlock = bool("hideThinkingBlock", false),
            keepAlive = bool("app.runtime.keepAlive", true),
        )
    }

    /**
     * A setting the settings stack just wrote was read by the app itself.
     *
     * The settings screens write straight to the store (which is what keeps
     * pi's files authoritative), so app-side behaviour has to be told that the
     * document changed. Without this an appearance edit would only take effect
     * after a restart, i.e. the row would still look inert.
     */
    fun onSettingWritten(key: String) {
        if (key == "theme") {
            refreshTheme()
            return
        }
        if (
            key == "hideThinkingBlock" ||
            key.startsWith("app.appearance.") ||
            key.startsWith("app.tools.") ||
            key == "app.runtime.keepAlive" ||
            key == "themes"
        ) {
            refreshPrefs()
            refreshTheme()
        }
    }

    // ----------------------------------------------------- extension UI state

    /**
     * Outstanding blocking requests. The queue — not [UiState] — is the authority
     * on what is still answerable; see [ExtensionDialogQueue] for the policy.
     */
    private val dialogs = ExtensionDialogQueue()

    /** The countdown coroutine for the dialog named by [armedDialogId]. */
    private var dialogTimeoutJob: Job? = null

    /**
     * Which dialog the running [dialogTimeoutJob] belongs to. Without this a
     * countdown tick would re-arm its own timer (cancelling the coroutine that is
     * ticking) and the countdown would never reach zero.
     */
    private var armedDialogId: String? = null

    /** Monotonic ids for snackbar notices, composer fills and live turn rows. */
    private var noticeSeq = 0L
    private var composerFillSeq = 0L

    /**
     * The current turn's outcome, when pi reported one nothing else renders.
     *
     * pi prints a red line under a partial answer for `length`, `aborted` and
     * `error` stop reasons (`components/assistant-message.ts:182-200`). The
     * reducer drops `stopReason` entirely (`rpc/Transcript.kt`, out of this
     * agent's ownership), so the ViewModel keeps the row itself and appends it to
     * the projection until the next turn starts. It is deliberately live-only:
     * the persisted session has no such entry, so a replay cannot reproduce it,
     * and claiming otherwise would be a lie.
     */
    private var liveTurnIssue: TranscriptItem? = null

    /**
     * Start the foreground service that keeps a running turn alive.
     *
     * Android 12+ requires the service to post its notification within a few
     * seconds of being started, which [PiEngineService] does in `onStartCommand`.
     * On Android 13+ the notification itself needs `POST_NOTIFICATIONS`; without
     * the grant the service still runs and the turn still survives, it is simply
     * less visible — which is why this does not block boot on asking.
     */
    private fun startEngineService() {
        val context = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PiEngineService::class.java),
            )
        }
    }

    /** The workspace is app-private for speed; `/sdcard` goes through FUSE. */
    private fun defaultWorkspace(): File = File(getApplication<Application>().filesDir, "pi/workspaces/workspace-1")

    /**
     * Where pi sees [defaultWorkspace].
     *
     * Duplicated from `PiEngineHost.guestPathFor` (private) because the mapping is
     * a contract, not an implementation detail: `PiEngineHost.kt:116` binds
     * `workspace.absolutePath` onto `/workspace/<path under filesDir>` and `:114`
     * starts pi with that path as its cwd. Paths inside a `bash` or `export_html`
     * argument are resolved **in the guest**, so they must be written in the
     * guest's spelling.
     */
    private fun guestWorkspace(): String = "/workspace/pi/workspaces/workspace-1"

    fun boot() {
        if (_state.value.boot is Boot.Working) return
        viewModelScope.launch {
            // Read the preferences first: `app.runtime.keepAlive` decides whether
            // the foreground service is started at all, so it cannot be read
            // after the service would have been. This is the switch's consumer —
            // without it the row wrote a JSON value nothing consulted.
            val prefs = withContext(Dispatchers.IO) { readPrefs() }
            _state.value = _state.value.copy(prefs = prefs)
            // Bring the foreground service up first. A pi turn is a model call plus
            // an unbounded sequence of tool calls; with the screen off and no
            // foreground service, Android is free to kill the whole process tree
            // mid-write. The service is what owns the engine's lifetime — not the
            // Activity, and not this ViewModel. `keepAlive = false` is the user
            // asking for exactly that risk.
            if (prefs.keepAlive) startEngineService()
            val boot = host.boot(workspaceProvider = ::defaultWorkspace) { step ->
                _state.value = _state.value.copy(boot = Boot.Working(step))
            }
            when (boot) {
                is PiEngineHost.Boot.Ready -> attach(boot.session)
                is PiEngineHost.Boot.Failed -> _state.value = _state.value.copy(
                    boot = Boot.Failed(boot.message, boot.detail),
                )
                else -> Unit
            }
        }
    }

    private fun attach(engine: PiEngineSession) {
        // Anything still pending belongs to the *previous* engine (a boot after a
        // failure, or a restart). It can never be answered now, so answer it
        // cancelled against the old session before this one's events arrive —
        // otherwise a dialog from the dead session would be shown against a live
        // engine whose request ids it does not match. Runs before `session` is
        // replaced, which is the whole point.
        cancelAllDialogs()
        clearExtensionChrome()

        session = engine
        api = PiEngineApi(engine)
        _state.value = _state.value.copy(boot = Boot.Ready)
        viewModelScope.launch {
            engine.state.collect { engineState ->
                _state.value = _state.value.copy(engine = engineState)
                if (
                    engineState == PiEngineSession.EngineState.Stopped ||
                    engineState == PiEngineSession.EngineState.Failed
                ) {
                    // A dead engine can never answer a dialog, and its stale
                    // extension chrome would keep claiming that a long-gone
                    // extension is still "工作中". Clear those, drop the façade —
                    // every action checks `api != null`, so this is what makes
                    // them no-ops instead of writes into a closed pipe — and drop
                    // a bash run nobody can observe any more.
                    api = null
                    cancelAllDialogs()
                    clearExtensionChrome()
                    _state.value = _state.value.copy(bash = null, busy = null)
                }
            }
        }
        viewModelScope.launch {
            engine.revision.collect { syncTranscript(engine) }
        }
        viewModelScope.launch {
            engine.events.collect { event -> onEvent(event) }
        }
        // A fresh engine has no transcript in this process. pi owns the session
        // file, so the rows are rebuilt from `get_entries` rather than from memory
        // (audit §6.7) — this is also what makes an extension's `appendEntry`
        // state and its `custom_message`s visible at all.
        viewModelScope.launch {
            replayHistory(engine)
            refreshState()
            refreshCommands()
            refreshTuiOnlyExtensions()
            seedAutoRetryFromSettings()
            // The agent dir exists for sure by now, so a theme dropped into it
            // while the app was closed is discovered here as well.
            refreshPrefs()
            refreshTheme()
        }
    }

    /**
     * Republish the engine's projection into [UiState].
     *
     * Two rendering-review defects are fixed here, both in the app's own layer:
     *
     *  - **F2**: a tool card must not keep claiming "运行中" after the turn it
     *    belonged to has ended. pi closes every pending tool with
     *    `updateResult({isError:true})` when a turn stops for `aborted`/`error`
     *    (`interactive-mode.ts:3294-3302`); the reducer does not (that file is
     *    `rpc/**`, out of this agent's ownership), so the pending rows are
     *    projected as interrupted here — the card then says the turn ended
     *    without a result instead of looking like a hang.
     *  - **F7** (partial): publishing is skipped when no element of the reducer's
     *    list changed identity. The engine bumps its revision for *every*
     *    non-response event, including ones whose `TranscriptChange` is `None`,
     *    so without this check every such event allocated a copy, a new
     *    `UiState`, and recomposed the whole screen. The full fix — publishing
     *    the reducer's change index — needs `PiEngineSession` and is reported
     *    instead of done.
     */
    private fun syncTranscript(engine: PiEngineSession) {
        val streaming = engine.transcript.streaming
        val source = engine.transcript.transcript
        val published = _state.value.transcript
        // An interrupted turn: a tool still pending can never receive its result
        // now.
        val interrupted = !streaming && source.any { it is ToolCall && it.status == ToolStatus.Pending }
        val unchanged = source.size == published.size &&
            source.indices.none { source[it] !== published[it] } &&
            _state.value.streaming == streaming &&
            _state.value.revision == engine.revision.value
        if (unchanged && liveTurnIssue == null) return

        val projected = if (interrupted) {
            source.map { item ->
                if (item is ToolCall && item.status == ToolStatus.Pending) {
                    item.copy(
                        status = ToolStatus.Error,
                        isError = true,
                        output = item.output.ifEmpty { "回合已结束，未收到工具结果（被停止或出错）" },
                    )
                } else {
                    item
                }
            }
        } else {
            source.toList()
        }
        _state.value = _state.value.copy(
            transcript = liveTurnIssue?.let { projected + it } ?: projected,
            revision = engine.revision.value,
            streaming = streaming,
        )
    }

    private fun onEvent(event: PiEvent) {
        when (event) {
            is PiEvent.QueueUpdate -> _state.value = _state.value.copy(
                queueSteering = event.steering.size,
                queueFollowUp = event.followUp.size,
            )

            // F3: a turn that ends for a reason other than "stop"/"toolUse" is not
            // a finished answer, and pi says so under the partial text
            // (`components/assistant-message.ts:182-200`). The reducer ignores
            // `stopReason`, so the row is projected here for the live stream.
            is PiEvent.MessageEnd -> {
                if (event.role == "assistant") {
                    val issue = stopReasonRow(event.stopReason, event.text)
                    if (issue != null) {
                        liveTurnIssue = issue
                        session?.let { syncTranscript(it) }
                    }
                }
            }

            // A new assistant message means the previous turn's issue is history.
            is PiEvent.MessageStart -> if (event.role == "assistant" && liveTurnIssue != null) {
                liveTurnIssue = null
                session?.let { syncTranscript(it) }
            }

            // An extension that throws is otherwise invisible: pi reports it as
            // this event and nothing else, so a broken extension looks exactly
            // like an extension that chose to do nothing. The transcript reducer
            // already turns it into an `ErrorText` row (rpc/Transcript.kt), which
            // is only visible on the Chat destination — hence the snackbar too.
            is PiEvent.ExtensionError -> {
                _state.value = _state.value.copy(lastError = event.message)
                pushNotice(
                    message = "扩展出错：${event.message?.takeIf { it.isNotBlank() } ?: "（pi 没有给出详情）"}",
                    tone = Notice.Tone.Error,
                )
            }

            is PiEvent.ExtensionUiRequest -> onExtensionUi(event)

            // `set_thinking_level` clamps silently (`agent-session.ts`
            // `setThinkingLevel`), so this event — not the requested value — is
            // the truth. pi advertises the twin `thinking_level_changed` on the
            // wire precisely because `thinking_level_select` is extension-only.
            is PiEvent.ThinkingLevelChanged -> {
                val level = event.level
                if (!level.isNullOrBlank()) {
                    _state.value = _state.value.copy(
                        meta = _state.value.meta.copy(thinkingLevel = level),
                    )
                }
            }

            // pi's rename path: `set_session_name` emits this, and it also fires
            // when an extension renames the session (`agent-session.ts:159`).
            is PiEvent.SessionInfoChanged -> _state.value = _state.value.copy(
                meta = _state.value.meta.copy(sessionName = event.name?.takeIf { it.isNotBlank() }),
            )

            // An extension's own entry (`pi.appendEntry`) — the documented way to
            // keep extension state across restarts. Nothing to do here: the engine
            // already routes `entry_appended` through the reducer
            // (`PiEngineSession.kt:138-140` → `Transcript.onEvent`), and it bumps
            // its revision for every non-response event, so the projection below
            // republishes on its own. Projecting a second time from this branch
            // (F5 in `docs/rendering-review.md`) would render every such entry
            // twice the moment the reducer gains a case for `custom` entries.
            is PiEvent.EntryAppended -> Unit

            // pi streams a running `bash` command as deltas and emits nothing else
            // until the response; accumulate here so the panel grows live.
            is PiEvent.BashExecutionUpdate -> {
                val delta = event.delta.orEmpty()
                val current = _state.value.bash ?: return
                if (delta.isNotEmpty()) {
                    _state.value = _state.value.copy(bash = current.copy(output = current.output + delta))
                }
            }

            // The only ground truth for "the model changed behind our back":
            // `model_select` is emitted to extensions only, never to session
            // listeners (`agent-session.ts:1659-1670`, audit §5.3). Commands are
            // re-read here too: an extension can register one at any time.
            is PiEvent.AgentSettled -> {
                viewModelScope.launch {
                    refreshState()
                    refreshCommands()
                }
            }

            is PiEvent.Response -> if (!event.success && event.error != null && event.command != null) {
                _state.value = _state.value.copy(lastError = "${event.command}: ${event.error}")
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------- extension UI

    /**
     * Route one `extension_ui_request`.
     *
     * Four methods block the extension until this app replies (`docs/rpc.md`
     * §"Extension UI Protocol"), so they are queued and answered by id; every
     * other method is fire-and-forget and updates chrome. An unknown method is
     * ignored on purpose: `docs/rpc.md` lists the RPC-mode no-ops (`custom`,
     * `setWorkingMessage`, `setFooter`, ...) which pi toggles directly, and a
     * method from a newer pi must degrade to "not displayed", never to an error.
     */
    private fun onExtensionUi(request: PiEvent.ExtensionUiRequest) {
        val method = ExtensionDialogMethod.fromWire(request.method)
        if (method == null) {
            onExtensionChrome(request)
            return
        }

        // Every response is addressed by the request's id, so a request without
        // one can never be answered and showing a dialog for it would only hide
        // pi's hang behind a button that cannot work. Say so instead.
        if (request.uiId.isEmpty()) {
            pushNotice(
                message = "扩展发来一个没有 id 的「${request.method}」对话框，无法回复；请改用终端模式运行该扩展。",
                tone = Notice.Tone.Warning,
            )
            return
        }

        val dialog = ExtensionDialog(
            id = request.uiId,
            method = method,
            title = request.title.orEmpty(),
            message = request.message,
            options = request.options,
            placeholder = request.placeholder,
            // `docs/rpc.md`: `editor` carries its initial content in `prefill`.
            prefill = request.text,
            timeoutMs = request.timeoutMs,
            // Seed the countdown so the first frame shows the full duration
            // before the timer's first tick lands.
            remainingMs = request.timeoutMs,
        )
        // A duplicate id is a protocol violation: answering it would resolve the
        // wrong request (see ExtensionDialogQueue).
        if (dialogs.enqueue(dialog)) publishDialogs()
    }

    /** The fire-and-forget methods, plus a no-op for everything else. */
    private fun onExtensionChrome(request: PiEvent.ExtensionUiRequest) {
        when (request.method) {
            "notify" -> pushNotice(
                message = request.message.orEmpty(),
                // rpc.md: notifyType is info | warning | error, default info.
                tone = noticeToneOf(request.notifyType),
            )

            "setStatus" -> setExtensionStatus(request.statusKey, request.statusText)

            "setWidget" -> setExtensionWidget(
                key = request.widgetKey,
                lines = request.widgetLines,
                placement = request.widgetPlacement,
            )

            "setTitle" -> _state.value = _state.value.copy(
                windowTitle = request.title?.takeIf { it.isNotBlank() },
            )

            "set_editor_text" -> request.text?.let { text ->
                // Last write wins, mirrored into the composer on the next frame.
                // `windowTitle`/`set_editor_text` with a missing field are ignored
                // rather than treated as "clear": the parser cannot tell an absent
                // `text` from an empty one, and guessing "clear" would silently
                // wipe whatever the user had typed.
                composerFillSeq += 1
                _state.value = _state.value.copy(composerFill = ComposerFill(composerFillSeq, text))
            }

            else -> Unit
        }
    }

    /**
     * `setStatus`: upsert by key, or remove it. `docs/rpc.md`: "Send
     * `statusText: undefined` (or omit it) to clear the status entry" — the
     * parser turns both into null, and an empty string renders as nothing at all,
     * so both clear here too.
     */
    private fun setExtensionStatus(key: String?, text: String?) {
        if (key.isNullOrEmpty()) return
        val current = _state.value.extensionStatuses
        val next = when {
            text.isNullOrEmpty() -> current.filterNot { it.key == key }
            current.any { it.key == key } -> current.map {
                if (it.key == key) ExtensionStatus(key, text) else it
            }
            else -> current + ExtensionStatus(key, text)
        }
        _state.value = _state.value.copy(extensionStatuses = next)
    }

    /**
     * `setWidget`: upsert by key, or remove it. An empty `widgetLines` clears —
     * `docs/rpc.md` "Send `widgetLines: undefined` (or omit it) to clear the
     * widget", and an empty array renders zero lines, so both are the same state.
     * Order is first-seen stable so a widget does not jump around the composer
     * when an extension updates it every turn.
     */
    private fun setExtensionWidget(key: String?, lines: List<String>, placement: String?) {
        if (key.isNullOrEmpty()) return
        val current = _state.value.extensionWidgets
        val next = when {
            lines.isEmpty() -> current.filterNot { it.key == key }
            current.any { it.key == key } -> current.map {
                if (it.key == key) {
                    ExtensionWidget(key, lines, WidgetPlacement.fromWire(placement))
                } else {
                    it
                }
            }
            else -> current + ExtensionWidget(key, lines, WidgetPlacement.fromWire(placement))
        }
        _state.value = _state.value.copy(extensionWidgets = next)
    }

    /**
     * Answer the dialog with [id] using exactly one wire response.
     *
     * The id is not decoration. A timeout can resolve a dialog in the same frame
     * the user taps its button, and [settleDialog] promotes the next request
     * immediately — so without this guard the tap would land on the *next*
     * extension's promise and resolve it with the wrong value. Any answer whose
     * id is not the current head is therefore dropped: one response per id, and
     * only for the request that is actually on screen.
     *
     * The answer type is checked against the method because the two are not
     * interchangeable on the wire (`docs/rpc.md` §"Extension UI Responses"): a
     * `value` for a confirm, or `confirmed` for an input, is a protocol error pi
     * cannot recover from, and the only safe handling is to refuse to send it.
     */
    fun answerDialog(id: String, answer: ExtensionAnswer) {
        val dialog = dialogs.active ?: return
        if (dialog.id != id) return
        val command = buildAnswer(dialog, answer) ?: return
        settleDialog(dialog, command)
    }

    private fun buildAnswer(dialog: ExtensionDialog, answer: ExtensionAnswer): JsonObject? = when (answer) {
        is ExtensionAnswer.Value ->
            if (dialog.method == ExtensionDialogMethod.Confirm) {
                null
            } else {
                PiCommands.extensionUiValue(dialog.id, answer.value)
            }

        is ExtensionAnswer.Confirmed ->
            if (dialog.method == ExtensionDialogMethod.Confirm) {
                PiCommands.extensionUiConfirmed(dialog.id, answer.confirmed)
            } else {
                null
            }

        ExtensionAnswer.Cancelled -> PiCommands.extensionUiCancelled(dialog.id)
    }

    private fun settleDialog(dialog: ExtensionDialog, command: JsonObject) {
        // Remove the entry FIRST. The queue is the single source of truth for
        // "still answerable", so a second tap, a Compose frame already queued
        // behind this one, or the countdown firing in the same millisecond can no
        // longer produce a second response for the same id. One response per id
        // is the contract (`docs/rpc.md`: "The `id` must match the request").
        if (dialogs.remove(dialog.id) == null) return
        // Promoting the next request also re-arms the countdown for it.
        publishDialogs()
        sendExtensionResponse(command)
    }

    /** Apply one `set_editor_text` to the composer, then forget it. */
    fun consumeComposerFill(seq: Long) {
        if (_state.value.composerFill?.seq == seq) {
            _state.value = _state.value.copy(composerFill = null)
        }
    }

    /** Drop the snackbar that was just shown, promoting the next notice. */
    fun consumeNotice(seq: Long) {
        _state.value = _state.value.copy(notices = _state.value.notices.filterNot { it.seq == seq })
    }

    // ------------------------------------------------------- extension timers

    /** Project the queue into [UiState] and (re)arm the countdown if the head changed. */
    private fun publishDialogs() {
        publishDialogState()
        val active = dialogs.active
        if (active?.id != armedDialogId) armDialogTimer(active)
    }

    private fun publishDialogState() {
        _state.value = _state.value.copy(
            extensionDialog = dialogs.active,
            extensionDialogBacklog = dialogs.backlog,
        )
    }

    /**
     * Start the live countdown for a timed dialog.
     *
     * The tick publishes once per *displayed second*, not once per poll: the
     * dialog shows whole seconds (pi's TUI shows "Title (5s)"), so four state
     * writes a second would recompose the transcript for no visible difference.
     *
     * pi runs its own timer for the same deadline (`rpc-mode.ts`
     * `createDialogPromise`), so this countdown is presentation; the answer at
     * zero is belt-and-braces, not the mechanism pi relies on.
     */
    private fun armDialogTimer(dialog: ExtensionDialog?) {
        dialogTimeoutJob?.cancel()
        dialogTimeoutJob = null
        armedDialogId = dialog?.id
        val timeout = dialog?.timeoutMs ?: return
        if (timeout <= 0L) return
        val id = dialog.id
        dialogTimeoutJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var shownSeconds = (timeout + 999L) / 1000L
            while (true) {
                val remaining = timeout - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining <= 0L) break
                val seconds = (remaining + 999L) / 1000L
                if (seconds != shownSeconds) {
                    shownSeconds = seconds
                    if (dialogs.tick(id, remaining)) publishDialogState()
                }
                delay(TIMER_POLL_MS)
            }
            onDialogTimedOut(id)
        }
    }

    /**
     * Resolve a dialog whose countdown reached zero with pi's documented default
     * (`docs/extensions.md` §"Timed Dialogs with Countdown"): `confirm()` returns
     * `false`, `select()`/`input()`/`editor()` return `undefined`.
     *
     * `undefined` has no JSON form — `cancelled: true` is how the protocol
     * carries it, and `rpc-mode.ts` parses a cancellation back into `undefined`
     * for exactly those three, so cancelling is not an approximation of the
     * default, it *is* the default's wire encoding.
     */
    private fun onDialogTimedOut(id: String) {
        // Guard against a stale timer: the dialog may have been answered, or
        // cancelled by an engine death, while this coroutine was suspended.
        val dialog = dialogs.active ?: return
        if (dialog.id != id) return
        when (dialog.method) {
            ExtensionDialogMethod.Confirm -> answerDialog(id, ExtensionAnswer.Confirmed(false))
            ExtensionDialogMethod.Select,
            ExtensionDialogMethod.Input,
            ExtensionDialogMethod.Editor -> answerDialog(id, ExtensionAnswer.Cancelled)
        }
    }

    /**
     * Dismiss everything pending and answer each one `cancelled`, oldest first.
     *
     * Called when the engine dies or a new session replaces the old one. The
     * write may be undeliverable (a dead pipe), which is fine — nobody is
     * blocked then — but on a *session reset* pi is still alive and would
     * otherwise sit forever on a dialog whose UI no longer exists.
     */
    private fun cancelAllDialogs() {
        val pending = dialogs.drain()
        dialogTimeoutJob?.cancel()
        dialogTimeoutJob = null
        armedDialogId = null
        publishDialogState()
        pending.forEach { sendExtensionResponse(PiCommands.extensionUiCancelled(it.id)) }
    }

    /** Forget per-session chrome that a gone engine can no longer maintain. */
    private fun clearExtensionChrome() {
        _state.value = _state.value.copy(
            extensionStatuses = emptyList(),
            extensionWidgets = emptyList(),
            windowTitle = null,
            composerFill = null,
        )
    }

    private fun sendExtensionResponse(command: JsonObject) {
        val engine = session ?: return
        // Best effort by design: the engine can die between a request and its
        // answer, and `send` writes to a pipe that is then closed. A broken pipe
        // must not crash the UI thread — the response only matters to a live pi.
        //
        // Racing pi's own timer is safe in both directions. pi deletes the
        // pending entry before resolving (`rpc-mode.ts` `createDialogPromise`),
        // and a response whose id is no longer pending is looked up, missed and
        // silently dropped (`rpc-mode.ts` input handler) — so a late reply cannot
        // corrupt the stream, and an early one resolves to the same documented
        // default pi would have chosen.
        runCatching { engine.send(command) }
    }

    /**
     * Queue a snackbar. Bounded because notifications are advisory — `docs/rpc.md`
     * says the client "can display the information or ignore it" — so the oldest
     * is dropped when nothing is on screen to show them. Dialog requests are
     * never dropped: they are the blocking half and live in their own queue.
     */
    private fun pushNotice(message: String, tone: Notice.Tone) {
        if (message.isBlank()) return
        noticeSeq += 1
        val notice = ExtensionNotice(seq = noticeSeq, message = message, tone = tone)
        _state.value = _state.value.copy(
            notices = (_state.value.notices + notice).takeLast(MAX_PENDING_NOTICES),
        )
    }

    /** A user-visible failure: a snackbar plus the AppBar's error line. */
    private fun fail(message: String) {
        _state.value = _state.value.copy(lastError = message)
        pushNotice(message, Notice.Tone.Error)
    }

    /**
     * Run one RPC call, surfacing failure the way a user can act on it.
     *
     * [label] doubles as the progress line and as the "who owns busy" token, so a
     * finished call cannot clear a newer call's label. `CancellationException` is
     * rethrown: it means the ViewModel is going away, not that the command
     * failed.
     */
    private fun call(label: String, block: suspend (PiEngineApi) -> Unit) {
        val api = this.api ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = label)
            try {
                block(api)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.message?.takeIf { it.isNotBlank() } ?: "$label 失败")
            } finally {
                if (_state.value.busy == label) _state.value = _state.value.copy(busy = null)
            }
        }
    }

    // ----------------------------------------------------------- session facts

    /**
     * Rebuild the transcript from pi's own records.
     *
     * `get_entries` is the documented re-attach path (`docs/rpc.md` §get_entries)
     * and `TranscriptReducer.seedFromHistory` is the reducer's matching entry
     * point — it resets first, so this is also exactly what a session switch
     * needs: pi's `switch_session`/`new_session`/`fork`/`clone` replace the
     * session in the same process and emit no replay, so without this the screen
     * would keep showing the previous session's rows (audit §6.7).
     *
     * The response is read through [PiResponses.entries], the raw-object reader,
     * because the reducer projects pi's own record shape — the typed
     * [SessionEntry] tree is for the tree screen, not for the reducer.
     */
    private suspend fun replayHistory(engine: PiEngineSession) {
        val response = runCatching {
            engine.request({ PiCommands.getEntries(it) })
        }.getOrNull() ?: return
        if (!response.success) {
            // A brand-new in-memory session answers with an empty page, not an
            // error; a real error means we cannot rebuild, and saying nothing is
            // better than clearing a transcript we cannot repopulate.
            return
        }
        engine.transcript.seedFromHistory(PiResponses.entries(response))
        // The replay replaces the stream wholesale; a live-only turn-outcome row
        // from the previous session must not survive it.
        liveTurnIssue = null
        syncTranscript(engine)
    }

    /**
     * Re-read everything `get_state` carries.
     *
     * Polled rather than event-driven on purpose: `model_select` never reaches
     * session listeners (`agent-session.ts:1659-1670`), so an extension that
     * switches the model would otherwise leave the app's indicator lying. Called
     * on attach and after every `agent_settled` (audit §6.8).
     */
    fun refreshState() {
        call("读取会话状态") { api ->
            val remote = api.getState()
            val current = _state.value.meta
            val level = remote.thinkingLevel?.takeIf { it.isNotBlank() } ?: current.thinkingLevel
            _state.value = _state.value.copy(
                meta = current.copy(
                    model = remote.model,
                    thinkingLevel = level,
                    steeringMode = queueModeOf(remote.steeringMode) ?: current.steeringMode,
                    followUpMode = queueModeOf(remote.followUpMode) ?: current.followUpMode,
                    autoCompaction = remote.autoCompactionEnabled,
                    sessionName = remote.sessionName?.takeIf { it.isNotBlank() },
                    sessionId = remote.sessionId,
                    sessionFile = remote.sessionFile,
                    messageCount = remote.messageCount,
                ),
            )
            refreshThinkingLevels()
        }
    }

    /**
     * `get_available_thinking_levels`. The set is a function of the selected
     * model (`agent-session.ts` `getAvailableThinkingLevels`), so it is reloaded
     * after every model change as well as on attach.
     */
    fun refreshThinkingLevels() {
        call("读取思考等级") { api ->
            val levels = api.getAvailableThinkingLevels()
            _state.value = _state.value.copy(
                meta = _state.value.meta.copy(thinkingLevels = levels),
            )
        }
    }

    /** `get_available_models`, for the model picker. */
    fun refreshModels() {
        call("读取模型列表") { api ->
            _state.value = _state.value.copy(models = api.getAvailableModels())
        }
    }

    /**
     * `get_commands` plus pi's built-ins — the `/` palette.
     *
     * Reloaded on attach and after every `agent_settled`: extensions register
     * commands at load time and `resources_discover` can add skills and templates
     * later, both of which change this list with no dedicated wire event.
     */
    fun refreshCommands() {
        call("读取命令列表") { api ->
            _state.value = _state.value.copy(
                commands = piCommandPalette(api.getCommands()),
                commandsLoaded = true,
            )
        }
    }

    /** `get_session_stats`, for the stats sheet and the context-usage line. */
    fun refreshStats() {
        call("读取会话统计") { api ->
            _state.value = _state.value.copy(stats = api.getSessionStats())
        }
    }

    /**
     * `get_tree` + `get_entries`, for the session tree overlay.
     *
     * Both are read because they answer different questions: the tree is the
     * branch structure with pi-resolved labels, while the entry log is the
     * append-only record — the only place an extension's `custom` entries are
     * visible at all (audit §1.5, §5.8).
     */
    fun refreshTree() {
        call("读取会话树") { api ->
            val tree = api.getTree()
            val entries = api.getEntries().entries
            _state.value = _state.value.copy(tree = tree, entries = entries)
        }
    }

    /** `get_fork_messages` — the user messages pi will let a fork start from. */
    fun refreshForkMessages() {
        call("读取可分叉消息") { api ->
            _state.value = _state.value.copy(forkMessages = api.getForkMessages())
        }
    }

    /**
     * Scan installed extensions for TUI-only surfaces.
     *
     * pi exposes no signal for this: `custom()` returns `undefined` without a
     * word and every setter is a no-op, so the app cannot observe the call — it
     * can only read the extension's source, which is what the audit's §6.11
     * proposes. Both locations pi auto-discovers are scanned
     * (`docs/extensions.md` "Extension locations": the user dir and the project
     * dir); the project one may be trust-gated and therefore not loaded at all,
     * which is itself worth knowing.
     */
    fun refreshTuiOnlyExtensions() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { scanTuiOnlyExtensions() }
            _state.value = _state.value.copy(tuiOnlyExtensions = found)
        }
    }

    private fun scanTuiOnlyExtensions(): List<TuiOnlyExtension> {
        val roots = listOf(
            File(host.paths().agentDir, "extensions") to "全局",
            File(defaultWorkspace(), ".pi/extensions") to "项目",
        )
        val found = mutableListOf<TuiOnlyExtension>()
        for ((root, scope) in roots) {
            if (!root.isDirectory) continue
            for (file in root.walkTopDown()) {
                if (!file.isFile) continue
                if (file.extension.lowercase() !in EXTENSION_SOURCE_SUFFIXES) continue
                if (file.length() > MAX_EXTENSION_SOURCE_BYTES) continue
                val source = runCatching { file.readText() }.getOrNull() ?: continue
                val markers = tuiOnlyMarkers(source)
                if (markers.isEmpty()) continue
                found += TuiOnlyExtension(
                    name = file.name,
                    scope = scope,
                    path = file.absolutePath,
                    markers = markers,
                )
            }
        }
        return found
    }

    /**
     * Seed [EngineMeta.autoRetry] from pi's settings document.
     *
     * `get_state` has no retry field, and pi's own source of truth is the setting
     * (`settings-manager.ts:914-916`: `retry?.enabled ?? true`), which lives in
     * the same `settings.json` the settings screens already read. The `true`
     * default is pi's, not a preference of this app.
     */
    private fun seedAutoRetryFromSettings() {
        val value = runCatching { settingsStore.read("retry.enabled") }.getOrNull()
        val enabled = (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return
        _state.value = _state.value.copy(meta = _state.value.meta.copy(autoRetry = enabled))
    }

    // ------------------------------------------------------------------ actions

    fun send(text: String, images: List<PiImage> = emptyList()) {
        val engine = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        // A new user message supersedes the previous turn's outcome row.
        liveTurnIssue = null
        if (engine.transcript.streaming) {
            // Mid-turn the delivery choice is the command itself: `steer` lands
            // after this turn's tool calls, `follow_up` only once pi stops.
            //
            // F1 (`docs/rendering-review.md`): echo the queued text locally, the
            // way `prompt` already does through `PiEngineSession.prompt`. pi's own
            // TUI adds the row on `message_start` for a user message
            // (`interactive-mode.ts:3224-3226`), but the reducer here appends
            // nothing for that role (`rpc/Transcript.kt:587-593`) — so without
            // this echo a steered message is invisible until the session is
            // reopened. If that reducer branch is ever fixed, this echo must be
            // removed or the message renders twice.
            engine.transcript.onUserPrompt(trimmed, images)
            engine.send(
                PiCommands.steer(
                    id = "steer-${System.nanoTime()}",
                    message = trimmed,
                    images = images,
                ),
            )
        } else {
            engine.prompt(trimmed, images)
        }
        syncTranscript(engine)
    }

    /**
     * Invoke one palette command whose action is [PiCommandAction.Prompt].
     *
     * Always through `prompt`, **never** `steer`, even mid-turn: pi's
     * `_queueUserInput` calls `_throwIfExtensionCommand` first, so a `steer` or
     * `follow_up` carrying `/name` fails with an error instead of running the
     * command (`agent-session.ts:1390-1400`). `prompt` executes extension
     * commands immediately, "even during streaming" (`:1181-1190`), and expands
     * skill commands and prompt templates for the same text.
     */
    fun runPromptCommand(command: PiSlashCommand, args: String) {
        val engine = session ?: return
        val text = if (args.isBlank()) command.invocation else "${command.invocation} ${args.trim()}"
        engine.prompt(text)
        syncTranscript(engine)
    }

    /**
     * `follow_up` — pi's `alt+enter` (`interactive-mode.ts:4126-4155`).
     *
     * The delivery choice *is* the difference between the two queueing commands:
     * `steer` lands after this turn's tool calls (`_queueSteer`), `follow_up`
     * only once the agent would otherwise stop (`_queueFollowUp`,
     * `agent-session.ts:1453-1468`), and `set_follow_up_mode` configures the
     * second one. pi's TUI queues a follow-up only while the agent is streaming;
     * when it is idle alt+enter is a normal submit, so the composer offers this
     * only while streaming and [send] handles the idle case.
     */
    fun sendFollowUp(text: String) {
        val engine = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        liveTurnIssue = null
        // Same local echo as [send]: a follow-up is invisible on the wire until pi
        // delivers it, which is the whole point of queueing it.
        engine.transcript.onUserPrompt(trimmed)
        engine.send(
            PiCommands.followUp(
                id = "follow-${System.nanoTime()}",
                message = trimmed,
            ),
        )
        syncTranscript(engine)
    }

    /** pi's Escape: abort, and hand the queued text back so the composer can restore it. */
    fun stop(onRestored: (List<String>) -> Unit = {}) {
        val engine = session ?: return
        viewModelScope.launch {
            val restored = runCatching { engine.stopAndDrainQueue() }.getOrDefault(emptyList())
            syncTranscript(engine)
            // viewModelScope already runs on the main dispatcher, so this is
            // called from the UI thread without needing Dispatchers.Main.
            onRestored(restored)
        }
    }

    /**
     * `set_thinking_level`.
     *
     * pi clamps to what the model supports and only emits
     * `thinking_level_changed` when the level actually changes
     * (`agent-session.ts` `setThinkingLevel`), so the state update is left to that
     * event rather than written optimistically here — a level the model cannot
     * use would otherwise be displayed as if it had taken effect.
     */
    fun setThinkingLevel(level: String) {
        call("切换思考等级") { it.setThinkingLevel(level) }
    }

    /** `cycle_thinking_level`. `null` is pi's documented answer. */
    fun cycleThinkingLevel() {
        call("切换思考等级") { api ->
            val result = api.cycleThinkingLevel()
            if (result == null) {
                pushNotice("当前模型不支持思考等级", Notice.Tone.Warning)
                return@call
            }
            val level = result.level
            if (!level.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    meta = _state.value.meta.copy(thinkingLevel = level),
                )
            }
        }
    }

    /** `set_model`. pi answers with the model it actually switched to. */
    fun setModel(model: PiResponses.ModelInfo) {
        val provider = model.provider
        if (provider.isNullOrBlank()) {
            fail("该模型没有 provider，pi 无法按 provider/id 定位它")
            return
        }
        call("切换模型") { api ->
            val applied = api.setModel(provider, model.id)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(model = applied))
            // Thinking levels are a property of the model, and pi applies a
            // per-model default on a model switch
            // (`_getThinkingLevelForModelSwitch`), so re-read both.
            refreshState()
        }
    }

    /** `cycle_model`. `null` means there is nothing to cycle to. */
    fun cycleModel() {
        call("切换模型") { api ->
            val result = api.cycleModel()
            if (result == null) {
                pushNotice("只有一个可用模型，无法循环切换", Notice.Tone.Warning)
                return@call
            }
            _state.value = _state.value.copy(
                meta = _state.value.meta.copy(
                    model = result.model ?: _state.value.meta.model,
                    thinkingLevel = result.thinkingLevel ?: _state.value.meta.thinkingLevel,
                ),
            )
            refreshThinkingLevels()
        }
    }

    /**
     * `set_steering_mode` / `set_follow_up_mode`.
     *
     * Both persist to pi's own settings (`settings-manager.ts:758-774`), and
     * `get_state` is the only readback — so the mirror is updated with the value
     * this app just sent, and the next `get_state` corrects it if pi disagreed.
     */
    fun setSteeringMode(mode: QueueMode) {
        call("设置穿插模式") { api ->
            api.setSteeringMode(mode)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(steeringMode = mode))
        }
    }

    fun setFollowUpMode(mode: QueueMode) {
        call("设置后续模式") { api ->
            api.setFollowUpMode(mode)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(followUpMode = mode))
        }
    }

    /**
     * `compact`. pi first aborts any in-flight turn and then runs a full
     * summarization call, so it is one of the few commands with a slow timeout and
     * a progress label the user can see.
     */
    fun compact(customInstructions: String? = null) {
        call("压缩上下文") { api ->
            val result = api.compact(customInstructions?.takeIf { it.isNotBlank() })
            val before = result.tokensBefore
            val after = result.estimatedTokensAfter
            val detail = if (before != null && after != null) "（$before → $after tokens）" else ""
            pushNotice("上下文已压缩$detail", Notice.Tone.Info)
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        call("设置自动压缩") { api ->
            api.setAutoCompaction(enabled)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(autoCompaction = enabled))
        }
    }

    fun setAutoRetry(enabled: Boolean) {
        call("设置自动重试") { api ->
            api.setAutoRetry(enabled)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(autoRetry = enabled))
        }
    }

    /** `abort_retry` — cancel the pending retry delay and stop retrying. */
    fun abortRetry() {
        call("取消重试") { it.abortRetry() }
    }

    // -------------------------------------------------------------------- bash

    /**
     * pi's `!` / `!!` composer mode (`interactive-mode.ts:3106-3116`: `!` runs the
     * command, `!!` additionally keeps the output out of the model's context).
     *
     * One command at a time, as in pi: a second run while one is in flight is
     * refused instead of interleaving two commands in one panel. The output joins
     * the model's context on the next prompt unless [excludeFromContext]
     * (`agent-session.ts` `executeBash` → `recordBashResult`).
     */
    fun runBash(command: String, excludeFromContext: Boolean) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.bash?.running == true) {
            pushNotice("已有 bash 命令在运行，先停止再执行新的命令", Notice.Tone.Warning)
            return
        }
        _state.value = _state.value.copy(
            bash = BashRun(command = trimmed, excludeFromContext = excludeFromContext),
        )
        call("执行命令") { api ->
            val result = api.bash(trimmed, excludeFromContext = excludeFromContext.takeIf { it })
            _state.value = _state.value.copy(
                bash = BashRun(
                    command = trimmed,
                    excludeFromContext = excludeFromContext,
                    // The response repeats the whole output, so it replaces the
                    // accumulated deltas rather than appending to them.
                    output = result.output,
                    running = false,
                    exitCode = result.exitCode,
                    cancelled = result.cancelled,
                    truncated = result.truncated,
                    fullOutputPath = result.fullOutputPath,
                ),
            )
        }
    }

    fun abortBash() {
        call("停止命令") { it.abortBash() }
    }

    fun dismissBash() {
        _state.value = _state.value.copy(bash = null)
    }

    // --------------------------------------------------------- session actions

    /** `new_session`. pi answers `cancelled` when an extension vetoes it. */
    fun newSession() {
        call("新建会话") { api ->
            val result = api.newSession()
            if (result.cancelled) {
                pushNotice("扩展取消了新建会话", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
        }
    }

    /**
     * `switch_session` with a path from the on-disk index.
     *
     * The path has to be the one **pi** can open: pi resolves it inside the guest,
     * and the app's `agentDir` is bind-mounted there verbatim
     * (`PiEngineHost.kt:123`, `guestAgentDir`), so host-to-guest is a prefix
     * substitution. Switching through the command rather than by editing files
     * under pi is what lets a `session_before_switch` extension veto it
     * (audit §6.9).
     */
    fun switchSession(summary: PiSessionStore.Summary) {
        val path = guestSessionPath(summary.file)
        if (path == null) {
            fail("找不到会话文件：${summary.file.name}")
            return
        }
        call("切换会话") { api ->
            val result = api.switchSession(path)
            if (result.cancelled) {
                pushNotice("扩展取消了切换会话", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
        }
    }

    /** `fork` from one of the user messages `get_fork_messages` offered. */
    fun forkFrom(entryId: String) {
        call("创建分支") { api ->
            val result = api.fork(entryId)
            if (result.cancelled) {
                pushNotice("扩展取消了分支", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
        }
    }

    /** `clone` — duplicate the active branch at the current position. */
    fun cloneSession() {
        call("复制会话") { api ->
            val result = api.cloneSession()
            if (result.cancelled) {
                pushNotice("扩展取消了复制", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
            pushNotice("已复制为新会话", Notice.Tone.Info)
        }
    }

    /**
     * `set_session_name`. pi trims and rejects an empty name with
     * `success: false` (`rpc-mode.ts:661-668`), which the façade turns into an
     * error, so no empty-name check is duplicated here.
     */
    fun renameSession(name: String) {
        call("重命名会话") { api ->
            api.setSessionName(name)
            _state.value = _state.value.copy(
                meta = _state.value.meta.copy(sessionName = name.trim()),
            )
            refreshSessions()
        }
    }

    /**
     * `/export <path>` — the format follows the extension, exactly as pi does it.
     *
     * pi's TUI branches on the argument: `interactive-mode.ts:6062-6066` calls
     * `exportToJsonl` when the path ends in `.jsonl` and `exportToHtml`
     * otherwise. The RPC surface only exposes `export_html`
     * (`rpc-types.ts:60`), so the JSONL branch is reproduced here from the same
     * records pi would write — see [exportJsonl].
     *
     * HTML still goes through `export_html`, written into the workspace so the
     * file is reachable. pi's default destination is its cwd —
     * `pi-session-<basename>.html` (`export-html/index.ts:284-288`) — which *is*
     * the workspace, but the response returns the guest spelling of the path.
     * Passing an explicit path keeps both spellings known to this class, so the
     * notice can name a path the app can actually resolve. This is also the only
     * non-TUI path by which an extension's `renderCall`/`renderResult` output
     * reaches a client (audit §5.11).
     */
    fun exportSession(fileName: String? = null) {
        val name = fileName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && name.endsWith(JSONL_SUFFIX, ignoreCase = true)) {
            exportJsonl(name)
            return
        }
        val htmlName = name ?: "pi-session-${System.currentTimeMillis()}.html"
        val guestPath = "${guestWorkspace()}/$htmlName"
        call("导出会话") { api ->
            val written = api.exportHtml(guestPath)
            val hostPath = File(defaultWorkspace(), htmlName)
            pushNotice(
                message = if (hostPath.isFile) {
                    "会话已导出：${hostPath.absolutePath}"
                } else {
                    "会话已导出：$written"
                },
                tone = Notice.Tone.Info,
            )
        }
    }

    /**
     * Write the active branch as JSONL, byte-for-byte the shape of pi's
     * `exportToJsonl` (`core/session-export.ts:7-42`).
     *
     * That function emits, in order: a fresh session header, then every entry on
     * the current branch with `parentId` **re-chained to the previously written
     * entry** (which is how an export of a branch becomes a linear file), then a
     * trailing newline. The branch is `getBranch()`: walk `parentId` from the
     * leaf to the root and reverse (`session-manager.ts:1274-1285`). `version` is
     * `CURRENT_SESSION_VERSION` (`session-manager.ts:30`), not the copied file's.
     *
     * This is the app-side half of an RPC gap, not a re-implementation of pi's
     * data: the entries come from `get_entries` verbatim, so the bytes differ
     * only in JSON key order and in `parentId`, which pi itself rewrites.
     */
    private fun exportJsonl(fileName: String) {
        call("导出会话") { _ ->
            val engine = session ?: return@call
            val response = engine.request({ PiCommands.getEntries(it) })
            if (!response.success) {
                throw PiRpcException("get_entries", response.error ?: "读取会话条目失败")
            }
            val raw = PiResponses.entries(response)
            val leafId = PiResponses.sessionEntries(response)?.leafId
            val branch = branchPath(raw, leafId)
            val meta = _state.value.meta
            val source = sessionHeaderOf(meta.sessionFile)
            val header = buildJsonObject {
                put("type", JsonPrimitive("session"))
                put("version", JsonPrimitive(CURRENT_SESSION_VERSION))
                put("id", JsonPrimitive(source?.first ?: meta.sessionId.orEmpty()))
                put("timestamp", JsonPrimitive(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                put("cwd", JsonPrimitive(source?.second ?: guestWorkspace()))
            }
            val body = StringBuilder(header.toString())
            var previousId: String? = null
            for (entry in branch) {
                val reChained = JsonObject(
                    entry + ("parentId" to (previousId?.let { JsonPrimitive(it) } ?: JsonNull)),
                )
                body.append('\n').append(reChained)
                previousId = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            body.append('\n')

            val target = File(defaultWorkspace(), fileName)
            target.parentFile?.mkdirs()
            withContext(Dispatchers.IO) { target.writeText(body.toString()) }
            pushNotice("会话已导出：${target.absolutePath}", Notice.Tone.Info)
        }
    }

    /** `SessionManager.getBranch` (`session-manager.ts:1274-1285`), old to new. */
    private fun branchPath(entries: List<JsonObject>, leafId: String?): List<JsonObject> {
        if (leafId == null) return emptyList()
        val byId = entries.mapNotNull { entry ->
            val id = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            id?.let { it to entry }
        }.toMap()
        val path = ArrayDeque<JsonObject>()
        var current = byId[leafId]
        while (current != null) {
            path.addFirst(current)
            val parent = (current["parentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            current = parent?.let { byId[it] }
        }
        return path.toList()
    }

    /**
     * `id` and `cwd` out of the session file's header line — the two header
     * fields `exportSessionToJsonl` copies from the live session
     * (`session-export.ts:22-28`). The mapped host file is preferred because a
     * session switched from another project carries that project's cwd, which
     * the app cannot otherwise know.
     */
    private fun sessionHeaderOf(guestPath: String?): Pair<String, String>? {
        val path = guestPath ?: return null
        val prefix = "${host.guestAgentDir}/"
        if (!path.startsWith(prefix)) return null
        val file = File(host.paths().agentDir, path.removePrefix(prefix))
        val line = runCatching { file.useLines { it.firstOrNull() } }.getOrNull() ?: return null
        val header = runCatching { app.pi.rpc.PiJson.parseObjectOrNull(line) }.getOrNull() ?: return null
        fun field(key: String) = (header[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return (field("id") ?: return null) to (field("cwd") ?: guestWorkspace())
    }

    /**
     * `get_last_assistant_text`, handed to [onText] for the clipboard.
     *
     * pi answers `{"text": null}` when there is no assistant message yet
     * (`rpc-mode.ts:656-659`) — a normal answer, not a failure, so it is reported
     * as "nothing to copy" rather than as an error.
     */
    fun copyLastAssistantText(onText: (String) -> Unit) {
        call("读取最后一条回复") { api ->
            val text = api.getLastAssistantText()
            if (text.isNullOrBlank()) {
                pushNotice("还没有模型回复可以复制", Notice.Tone.Warning)
            } else {
                onText(text)
            }
        }
    }

    /**
     * Everything that must happen when pi replaces the session in place.
     *
     * `switch_session`, `new_session`, `fork` and `clone` all rebind the session
     * inside the same process (`rpc-mode.ts:437-444`, `:605-631`) and emit no
     * replay, so the app re-reads pi's records itself. Nothing is reset
     * optimistically: the transcript is rebuilt from the session file and the
     * facts come back from `get_state`.
     */
    private suspend fun afterSessionReplaced() {
        val engine = session ?: return
        replayHistory(engine)
        refreshState()
        refreshCommands()
        refreshSessions()
    }

    /** Ask `PiRoot` to change destination or open the tree overlay. */
    fun requestNav(request: NavRequest) {
        _state.value = _state.value.copy(navRequest = request)
    }

    fun consumeNav() {
        if (_state.value.navRequest != null) {
            _state.value = _state.value.copy(navRequest = null)
        }
    }

    /**
     * Tell the user why a built-in command they know from pi's TUI does nothing
     * here: pi exposes no RPC path for it and `prompt()` cannot dispatch it
     * (built-ins are excluded from `get_commands` and `_tryExecuteExtensionCommand`
     * only matches extension commands), so the honest answer names the one surface
     * that can — the original TUI in the workbench terminal.
     */
    fun notifyTerminalOnly(command: PiSlashCommand) {
        pushNotice(
            message = "pi 的 RPC 模式没有实现 /${command.name}，只能在工作区 → pi TUI（原版）里执行",
            tone = Notice.Tone.Warning,
        )
    }

    /** Unknown `/name`: audit §6.4 — never let it reach the model as prose. */
    fun notifyUnknownCommand(name: String) {
        pushNotice(
            message = "「/$name」不是已安装的扩展命令、模板或技能；" +
                "如果要把它作为消息发给模型，请去掉开头的 /",
            tone = Notice.Tone.Warning,
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(lastError = null)
    }

    /**
     * The one-line status the AppBar shows. Deliberately terse and in pi's own
     * vocabulary (working / queued / idle) rather than inventing new states.
     */
    fun engineLabel(current: UiState = _state.value): String = when {
        current.busy != null -> current.busy
        current.streaming -> "工作中"
        current.boot !is Boot.Ready -> "启动中"
        current.engine == PiEngineSession.EngineState.Failed -> "引擎已退出"
        current.engine == PiEngineSession.EngineState.Stopped -> "引擎已停止"
        current.queueSteering > 0 || current.queueFollowUp > 0 -> "排队中"
        else -> "就绪"
    }

    override fun onCleared() {
        // Answer anything outstanding before the engine is torn down, so a
        // still-live pi is not left blocked on a dialog whose UI just vanished.
        cancelAllDialogs()
        session?.close()
        session = null
        api = null
        super.onCleared()
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The row pi prints under a partial answer when a turn did not finish
     * normally (`components/assistant-message.ts:182-200`): `length` means the
     * output-token limit cut it off, `aborted` that the user stopped it, `error`
     * that the provider call failed. Null when the turn ended as a normal
     * `stop`/`toolUse`.
     *
     * The reducer drops `stopReason` (that is `rpc/**`, owned by another agent),
     * so this is the app's own honest report of the outcome; the detail carries
     * pi's wire value rather than invented wording.
     */
    private fun stopReasonRow(stopReason: String?, partialText: String?): TranscriptItem? {
        val message = when (stopReason) {
            "length" -> "回复被输出令牌上限截断，内容不完整"
            "aborted" -> "回合被中断"
            "error" -> "模型调用失败"
            else -> return null
        }
        val detail = listOfNotNull(
            "stopReason: $stopReason",
            partialText?.takeIf { it.isNotBlank() }?.let { "部分输出：${it.takeLast(200)}" },
        ).joinToString("\n")
        return ErrorText(
            key = "turn-issue-${System.nanoTime()}",
            ts = System.currentTimeMillis(),
            message = message,
            detail = detail.ifBlank { null },
        )
    }

    /**
     * pi's wire spelling of a queue mode back into the enum. An unknown value maps
     * to `null` rather than a default, so a newer pi's new mode is not silently
     * displayed as `one-at-a-time`.
     */
    private fun queueModeOf(wire: String?): QueueMode? =
        QueueMode.entries.firstOrNull { it.wire == wire }

    /**
     * The guest spelling of a session file the app found on disk.
     *
     * Null when the file is outside the agent dir, which would mean the index and
     * pi disagree about where sessions live — better to refuse than to hand pi a
     * path it will fail to open.
     */
    private fun guestSessionPath(file: File): String? {
        val sessionsRoot = File(host.paths().agentDir, "sessions").absolutePath
        val path = file.absolutePath
        if (!path.startsWith(sessionsRoot)) return null
        val relative = path.removePrefix(sessionsRoot).trimStart('/')
        return "${host.guestAgentDir}/sessions/$relative"
    }

    private companion object {
        /** Poll interval for the countdown; publishes only on second boundaries. */
        const val TIMER_POLL_MS = 200L

        /** Notifications held for the snackbar before the oldest is dropped. */
        const val MAX_PENDING_NOTICES = 8

        /** Extensions pi loads are TypeScript or JavaScript modules. */
        val EXTENSION_SOURCE_SUFFIXES = setOf("ts", "js", "mts", "mjs", "cts", "cjs")

        /** A 1 MiB source file is not an extension a phone should be parsing. */
        const val MAX_EXTENSION_SOURCE_BYTES = 1L shl 20

        /** The one extension pi's exporter treats as JSONL (`interactive-mode.ts:6064`). */
        const val JSONL_SUFFIX = ".jsonl"

        /** `CURRENT_SESSION_VERSION` (`core/session-manager.ts:30`). */
        const val CURRENT_SESSION_VERSION = 3
    }
}
