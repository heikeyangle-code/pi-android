package app.pi.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.rpc.PiEvent
import app.pi.rpc.PiImage
import app.pi.rpc.TranscriptItem
import app.pi.runtime.RuntimeProvisioner
import app.pi.session.PiSessionStore
import app.pi.service.PiEngineService
import app.pi.settings.PiSettingsFileStore
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.theme.PiThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 * after a reconnect replays from `get_entries { since }`.
 */
class PiSessionViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val boot: Boot = Boot.Idle,
        val engine: PiEngineSession.EngineState? = null,
        val transcript: List<TranscriptItem> = emptyList(),
        val revision: Int = 0,
        val thinking: PiThinkingLevel = PiThinkingLevel.Medium,
        val streaming: Boolean = false,
        val queueSteering: Int = 0,
        val queueFollowUp: Int = 0,
        val lastError: String? = null,
    )

    private val host = PiEngineHost(app)
    private var session: PiEngineSession? = null

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

    fun boot() {
        if (_state.value.boot is Boot.Working) return
        // Bring the foreground service up first. A pi turn is a model call plus an
        // unbounded sequence of tool calls; with the screen off and no foreground
        // service, Android is free to kill the whole process tree mid-write. The
        // service is what owns the engine's lifetime — not the Activity, and not
        // this ViewModel.
        startEngineService()
        viewModelScope.launch {
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
        session = engine
        _state.value = _state.value.copy(boot = Boot.Ready)
        viewModelScope.launch {
            engine.state.collect { _state.value = _state.value.copy(engine = it) }
        }
        viewModelScope.launch {
            engine.revision.collect { syncTranscript(engine) }
        }
        viewModelScope.launch {
            engine.events.collect { event -> onEvent(event) }
        }
    }

    private fun syncTranscript(engine: PiEngineSession) {
        _state.value = _state.value.copy(
            transcript = engine.transcript.transcript.toList(),
            revision = engine.revision.value,
            streaming = engine.transcript.streaming,
        )
    }

    private fun onEvent(event: PiEvent) {
        when (event) {
            is PiEvent.QueueUpdate -> _state.value = _state.value.copy(
                queueSteering = event.steering.size,
                queueFollowUp = event.followUp.size,
            )
            is PiEvent.ExtensionError -> _state.value = _state.value.copy(lastError = event.message)
            is PiEvent.Response -> if (!event.success && event.error != null && event.command != null) {
                _state.value = _state.value.copy(lastError = "${event.command}: ${event.error}")
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ actions

    fun send(text: String, images: List<PiImage> = emptyList()) {
        val engine = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        if (engine.transcript.streaming) {
            // Mid-turn the delivery choice is the command itself: `steer` lands
            // after this turn's tool calls, `follow_up` only once pi stops.
            engine.send(
                app.pi.rpc.PiCommands.steer(
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

    fun sendFollowUp(text: String) {
        val engine = session ?: return
        engine.send(
            app.pi.rpc.PiCommands.followUp(
                id = "follow-${System.nanoTime()}",
                message = text.trim(),
            ),
        )
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

    fun setThinking(level: PiThinkingLevel) {
        _state.value = _state.value.copy(thinking = level)
        session?.send(app.pi.rpc.PiCommands.setThinkingLevel("think-${System.nanoTime()}", level.wire))
    }

    fun cycleThinking() = setThinking(PiThinkingLevel.next(_state.value.thinking))

    fun dismissError() {
        _state.value = _state.value.copy(lastError = null)
    }

    /**
     * The one-line status the AppBar shows. Deliberately terse and in pi's own
     * vocabulary (working / queued / idle) rather than inventing new states.
     */
    fun engineLabel(current: UiState = _state.value): String = when {
        current.streaming -> "工作中"
        current.boot !is Boot.Ready -> "启动中"
        current.engine == PiEngineSession.EngineState.Failed -> "引擎已退出"
        current.engine == PiEngineSession.EngineState.Stopped -> "引擎已停止"
        current.queueSteering > 0 || current.queueFollowUp > 0 -> "排队中"
        else -> "就绪"
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}
