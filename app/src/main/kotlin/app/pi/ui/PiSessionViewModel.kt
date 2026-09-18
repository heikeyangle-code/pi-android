package app.pi.ui

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pi.engine.EngineExitCause
import app.pi.engine.PiEngineApi
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.engine.PiRpcException
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.asOutcome
import app.pi.rpc.Notice
import app.pi.rpc.PiCommands
import app.pi.rpc.PiEvent
import app.pi.rpc.PiImage
import app.pi.rpc.PiLaunchOptions
import app.pi.rpc.PiResponses
import app.pi.rpc.parseSessionEntry
import app.pi.rpc.QueueMode
import app.pi.rpc.SessionEntry
import app.pi.rpc.StreamingBehavior
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem
import app.pi.rpc.extensionErrorHeadline
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.WorkspaceStore
import app.pi.session.PiSessionStore
import app.pi.session.SessionExportNaming
import app.pi.session.SessionFileReader
import app.pi.session.SessionImport
import app.pi.service.PiEngineController
import app.pi.service.PiEngineLifecyclePolicy
import app.pi.service.PiEngineService
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readBoolean
import app.pi.settings.readString
import app.pi.ui.chat.BASH_OUTPUT_MAX_CHARS
import app.pi.ui.chat.PiCommandAction
import app.pi.ui.chat.PiCommandSource
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.MentionLookup
import app.pi.ui.chat.PiMentionSource
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.TuiOnlyExtension
import app.pi.ui.chat.appendTailBounded
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
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.noticeToneOf
import app.pi.ui.extension.trimNoticeQueue
import app.pi.ui.settings.EngineDiagnostics
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.theme.PiResolvedTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Which workspace the engine is in, and the one signal the composer needs about a
 * switch.
 *
 * Top-level for the same reason as [Boot]: the chat screen renders it, and the
 * screen is not allowed to know how a workspace is chosen — only that one was.
 *
 * ## [revision] is the draft-clearing signal
 *
 * The user's ruling on the composer is "草稿就清空就行": a draft typed for one
 * workspace must not be sendable to another. [revision] is the *fact* that a new
 * session context has begun; the composer's unsent content now lives in this
 * ViewModel (`PiSessionViewModel.composerDraft`, [ComposerDraft]), so the clearing
 * happens here too — at the one place this state is published,
 * [PiSessionViewModel.publishWorkspaceState], through
 * `PiSessionViewModel.alignComposerToWorkspace`. The `>` rule in that method is the
 * whole ruling: **a switch clears, an alignment does not.**
 *
 * (Until this moved, the screen keyed its own `rememberSaveable` on this field. The
 * field is unchanged; only its reader is. What forced the move is in [ComposerDraft]:
 * the pending images' base64 text was being written into the saved instance state,
 * and a phone photo is past Binder's transaction limit on its own.)
 *
 * It is bumped **once per successful switch** — and also when the startup
 * reconcile has to move the process back to the default workspace, because that is
 * a different session context too even though the user did not press anything. It
 * is never bumped for a switch that failed or was refused, because nothing about
 * the session changed.
 */
data class WorkspaceState(
    /** The current workspace's directory name — the identity, e.g. `workspace-1`. */
    val name: String = WorkspaceStore.DEFAULT_NAME,
    /** [name] as a path relative to the files directory (`pi/workspaces/…`). */
    val relative: String = GuestWorkspacePath.DEFAULT_RELATIVE,
    /** The host directory, for a screen that wants to show it. Null if not resolvable. */
    val hostPath: String? = null,
    /** Bumped on every successful switch; see the class KDoc. Starts at 0. */
    val revision: Int = 0,
    /**
     * Why the current workspace is not the stored choice, when the stored choice
     * was unusable. Shown to the user once; null on the normal path.
     */
    val note: String? = null,
)

/**
 * The outcome of a workspace switch.
 *
 * Four shapes, and they exist so the screen can say the right thing without
 * inspecting engine internals: a refusal changed nothing, a failure changed the
 * engine but not the workspace, and only [Ok] means "you are now in the new one".
 *
 * @see PiSessionViewModel.switchWorkspace
 */
sealed interface WorkspaceSwitch {
    /**
     * The engine is now running in [name].
     *
     * @param interruptedTurn true when a turn was in flight and the switch
     *        deliberately killed it (`allowInterrupt`). The screen asked first
     *        ([PiSessionViewModel.wouldInterruptTurn]) and the ViewModel posts the
     *        warning notice after, so this is only the machine-readable half of a
     *        warning the user has already had twice.
     */
    data class Ok(val name: String, val interruptedTurn: Boolean) : WorkspaceSwitch

    /** [name] was already current; the engine was not touched. */
    data class AlreadyCurrent(val name: String) : WorkspaceSwitch

    /**
     * **Nothing changed.** The target is not a workspace we own, is not writable,
     * or a turn is running and the caller did not accept interrupting it. The old
     * engine is still running in the old workspace.
     */
    data class Refused(val message: String) : WorkspaceSwitch

    /**
     * The switch was attempted and did not complete. [rolledBackTo] names the
     * workspace the engine was put back into, or null when it could not be
     * restarted at all — in which case [message] and [detail] are the only
     * description of a stopped engine, and the persisted choice was never changed.
     */
    data class Failed(
        val message: String,
        val detail: String? = null,
        val rolledBackTo: String? = null,
    ) : WorkspaceSwitch
}

/**
 * A navigation the ViewModel asks the UI to perform.
 *
 * Requested through state rather than a callback because the action that needs
 * it usually ends in a coroutine after an RPC answer (`switch_session`, a
 * palette row) — by then there is no composable on the stack to call. pi itself
 * has no navigation API at all, so this vocabulary is the app's own and belongs
 * to the layer that knows the answer arrived.
 *
 * Only [Chat] and [Settings] name a top-level destination, and they are two of the
 * three the bottom bar draws — the vocabulary and `PiDestination` are kept in step
 * on purpose, so a request can never ask for a destination the bar does not have.
 * Everything else here raises an overlay or focuses a screen.
 *
 * 工作区 is deliberately absent for that reason: nothing in the app asks to *go*
 * there (the bar is the only way), so there is no request for it. It used to be
 * named by a `Workbench` member whose one consumer was the chat composer's
 * terminal chip — removed with the chip when the terminal became a settings row.
 */
sealed interface NavRequest {
    /**
     * Open the session list over the current destination — **and nothing else**.
     *
     * This replaced a `Sessions` member that switched to a 会话 *destination*.
     * The list is a selector, not a place (`03-navigation-decision.md`), and once
     * it became an overlay the request had to stop moving the destination: which
     * session the user is in is answered by picking a row, not by the picker
     * appearing.
     *
     * ## Why `/resume` does not switch to 对话 first either
     *
     * `/resume` is one of this member's callers, and it was worth deciding once
     * rather than per call site: the palette row is typed **in the composer**, so
     * the user is already on 对话 with the session they intend to leave in front of
     * them. Switching destinations first would rebuild the transcript and move the
     * ground under the list they are about to read, for no gain — and if they pick a
     * row, the pick moves them then, which is exactly the moment the move carries
     * information. The app-bar entry does the same thing for the same reason.
     */
    data object SessionList : NavRequest

    data object Chat : NavRequest

    /**
     * The full-screen terminal, over whatever destination is active.
     *
     * This is what was `Workbench`. The rename is the point: the old name meant
     * 「去工作区」, which was true while the terminal *was* the 工作区 destination,
     * and became a lie the moment that destination turned into the project
     * overview. The terminal is not a place in the app; it is the fallback surface
     * for the handful of extension APIs the RPC path cannot carry, and the settings
     * home's one row is now the only door to it (`03-navigation-decision.md`).
     *
     * Reached from the settings row, which is why it is a request at all rather
     * than a callback: the row lives several composables down inside
     * [app.pi.ui.settings.PiSettingsStack], and the overlay it raises belongs to
     * `PiRoot`.
     */
    data object Terminal : NavRequest

    data object Settings : NavRequest

    /**
     * The settings destination, positioned on one key.
     *
     * pi has no navigation API, so this vocabulary is the app's own. It exists
     * for the one built-in command that opens a *selector* pi implements inside
     * its TUI overlay: `/scoped-models` calls `showModelsSelector()`
     * (`interactive-mode.ts:2975-2978`; the method at `:5024`), which is a
     * different component from the plain `/model` picker (`showModelSelector`,
     * `:4987`) and what it toggles is persisted as `settings.enabledModels`
     * (`settings-manager.ts:1316-1326`). That key is a row in this app
     * (`PiSettingsRegistry.kt:355`), so the faithful mapping of "open the
     * scoped-models selector" is "open settings on that row".
     */
    data class SettingsFocus(val key: String) : NavRequest

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
 * A finished `/export` the user has not taken delivery of yet.
 *
 * pi writes an export into the user's own cwd and reports the path
 * (`interactive-mode.ts:6060-6075`, default destination
 * `core/export-html/index.ts:274-281`); this app's equivalent destination is its
 * private workspace, where nothing outside the app can open it. The file is the
 * deliverable, so the screen needs one more thing than "it worked": something to
 * hand to Download or the share sheet. This value is that handle — it exists
 * between the export and the user's choice, and it is the only reason the app
 * keeps a path to its own artifact in state.
 *
 * [path] is the file's absolute host path and is **never** rendered: user-visible
 * copy in this app names no internal directory. [name] is what the user sees and
 * what the delivery uses as the Download file name.
 */
data class ExportedSession(
    val name: String,
    /** What the delivery channel calls the file — see `SessionExportNaming`. */
    val mimeType: String,
    /** Absolute path inside the app's private workspace. Not user-visible. */
    val path: String,
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
/**
 * How much of a session's history the transcript currently holds.
 *
 * The transcript is no longer "the whole session": it is the **newest window** of
 * it, extended backwards on demand. This cursor is what makes that progressive, and
 * it is deliberately *not* part of `get_entries` — pi's `since` cursor only moves
 * forwards (`modes/rpc/rpc-mode.ts:638-648`), so the backward direction has to be
 * answered by the session file, which [SessionFileReader] reads in bounded windows.
 * The `session-replay-cost` harness pins that the two agree.
 *
 * **Not a second source of truth for the session.** It is not itself an entry list
 * and nothing outside the ViewModel's transcript path reads it: the tree overlay,
 * fork messages, export and search still go through pi. It is the *reader's*
 * position, the same way a file offset is, and it is dropped whenever the
 * transcript comes from somewhere else ([UiState.history] becomes null).
 */
data class HistoryCursor(
    /**
     * Byte offset in the session file of the **first** entry [UiState.transcript]
     * holds. Zero means the loaded range starts at the file's first entry.
     *
     * A byte offset rather than an entry index on purpose: the reader has to seek to
     * it, and pi's `get_entries` cursor is an *entry id* whose index pi computes by
     * scanning its own list — which is the O(history) work this whole path exists to
     * avoid.
     */
    val startOffset: Long,
    /** True when [startOffset] is the beginning of the file: no earlier history. */
    val reachedStart: Boolean,
    /** A backward window is being read right now; the screen must not ask again. */
    val loading: Boolean = false,
) {
    /** Whether the screen should offer to load more when scrolled to the top. */
    val hasEarlier: Boolean get() = !reachedStart && !loading
}

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
    /**
     * pi's `showCacheMissNotices` (`core/settings-manager.ts:120`, read at
     * `:965-967` as `?? false`). It is the switch that gates the summarization
     * cost line, and pi reads it for exactly three notices: the compaction /
     * branch-summary billing line (`modes/interactive/interactive-mode.ts:3802-3812`),
     * the assistant diagnostics (`:3814`) and the provider-recovery notices.
     *
     * Default `false` **is pi's default** — the row exists in the settings stack
     * (`ui/settings/PiSettingsRegistry.kt:331`) and, until this field existed, no
     * code read it, so the switch looked wired and was not.
     */
    val showCacheMissNotices: Boolean = false,
)

/**
 * The composer's unsent content: the text in the editor, and the images staged for
 * the message that has not been sent yet.
 *
 * ## Why this is not `rememberSaveable` — the crash this type exists to prevent
 *
 * Both halves used to be `rememberSaveable` in `ChatScreen`, and the attachments
 * went into the saved state as their **base64 text** (the `AttachmentListSaver` that
 * was deleted with this move). What `ChatScreen` stages is now compressed to pi's own
 * inline limits — longest edge 2000, base64 under 4.5 MB per image — and one message's
 * images are budgeted **together** against the framing cap (`AttachmentBudget`:
 * `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` − 64 KiB ≈ **31.94 MiB of base64**, i.e.
 * ≈ 23.95 MB of bytes, or 7 pi-maximum images). That budget is what makes this type
 * necessary rather than optional: the smallest contribution one image can make to the
 * Bundle is still megabytes of text, and a legal message is up to seven of them, while
 * Binder's per-transaction limit is ~1 MB — so the *first* image was already over it.
 *
 * `rememberSaveable` does not write those strings to a file. They go to
 * `androidx.compose.ui.platform.DisposableSaveableStateRegistry`, which registers a
 * `SavedStateRegistry` provider whose answer is
 * `SaveableStateRegistry.performSave().toBundle()` — `Bundle.putParcelableArrayList`
 * of exactly those strings (verified in the bytecode of `ui-android:1.12.1`,
 * `DisposableSaveableStateRegistry_androidKt.toBundle`) — and that Bundle is the
 * Activity's saved instance state, which `ActivityThread` hands to the system server
 * **over Binder**. Binder's per-transaction limit is ~1 MB, so as soon as the screen
 * was stopped with one image staged, the write blew the limit and the process was
 * killed. The picker is what stops it, which is the reported shape exactly:
 * 选一张不发送没事 (staged state is still empty when the picker opens the first time),
 * 再选第二张就退出软件 (the first image's base64 is in the saved state when the picker
 * opens the second time). A rotation with one image staged crashed the same way.
 *
 * ## Why the ViewModel holds it
 *
 * Everything the user keeps survives here, and **none of it is ever serialised**.
 * `AndroidViewModel` lives as long as the Activity's `ViewModelStore`, so
 *  - 切到工作区 / 设置 and back keeps the text and the images — which is the ruling
 *    D28/D30 actually makes ("切走之前是什么样，切回来什么样就可以了",
 *    `design/ui-refactor/07-construction-decisions.md:184`, `:194`), now kept by the
 *    ViewModel instead of by `PiRoot`'s `SaveableStateHolder`; and
 *  - a rotation keeps them (a config change does not clear the store); and
 *  - the saved instance state never sees them, whatever their size.
 *
 * What is given up is the restore **after process death**. That part was incidental:
 * D28's ruling is about a destination switch, and the Bundle was only ever the
 * mechanism, not the requirement. It is also the half that cannot be kept — the
 * Bundle is the one thing that survives process death, and it is exactly the thing
 * that cannot hold these bytes. Saving the *text* alone (it is small) was considered
 * and rejected: it would leave the staged images to vanish while the text stayed,
 * which reads as the app having eaten the pictures.
 *
 * ## Clearing
 *
 * "草稿就清空就行": a workspace switch ends the session context the text was typed
 * for, so both halves are emptied on a switch and only on a switch —
 * [PiSessionViewModel.alignComposerToWorkspace].
 *
 * The two values are Compose snapshot state rather than `StateFlow`s because they are
 * per-keystroke: a `UiState` copy per character would recompose the whole transcript
 * for a text field that lives above it.
 */
class ComposerDraft {
    /** The editor's text. */
    val text: MutableState<String> = mutableStateOf("")

    /** The images staged for the next message, in the order the user picked them. */
    val attachments: MutableState<List<PiImage>> = mutableStateOf(emptyList())

    /** Both halves — what a workspace switch clears. */
    fun clear() {
        text.value = ""
        attachments.value = emptyList()
    }
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
     * The `@` mention candidates for one open token.
     *
     * [query] is the prefix they answer, kept so the composer can tell a list that
     * belongs to what is on screen right now from the previous keystroke's answer
     * arriving late. Empty [items] is a real answer (fd matched nothing, or there is
     * no fd) and the composer shows no list for it, exactly as pi's autocomplete
     * does not appear when it has no suggestions
     * (`packages/tui/src/autocomplete.ts:305`).
     */
    data class MentionList(
        val query: String,
        val items: List<PiFileMentions.Item>,
    )

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
        /**
         * pi is compacting the context right now.
         *
         * This flag exists because of one hard behaviour in pi: `session.prompt()`
         * **throws** while a compaction is in progress —
         * `Cannot submit a prompt while compaction is in progress…`
         * (`core/agent-session.ts:1192-1196`) — so a message sent in that window
         * must not go through `prompt` at all (see [send]). It is tracked from the
         * two events and reconciled against `get_state`, because the event channel
         * is documented to drop events under load (`docs/hang-and-crash-review.md`
         * B6): a missed `compaction_end` would otherwise leave this true forever.
         */
        val compacting: Boolean = false,
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
        /**
         * How much of the session's history the transcript currently holds.
         *
         * Null means "the transcript was not built from the session file" (no
         * session yet, or a `get_entries` replay in flight), which the UI reads as
         * "no earlier history to fetch" rather than as an error. See [HistoryCursor].
         */
        val history: HistoryCursor? = null,
        /** `get_fork_messages` — user messages a fork can start from. */
        val forkMessages: List<PiResponses.ForkMessage> = emptyList(),
        /** The running or last `bash` command, if any. */
        val bash: BashRun? = null,
        /** The `@` mention list for the token in the composer, if one is open. */
        val mentions: MentionList? = null,
        /**
         * Installed extensions that use a surface only the original TUI can
         * carry. Heuristic (a source scan) because pi reports nothing — see
         * [tuiOnlyMarkers] for why that is the only option.
         */
        val tuiOnlyExtensions: List<TuiOnlyExtension> = emptyList(),
        /** Label of the long RPC call in flight, for a progress line. */
        val busy: String? = null,
        /**
         * The `/export` that just finished, until the user dismisses it or the
         * session changes. See [ExportedSession] for why the app keeps it: the file
         * is the deliverable and it lives where no other app can open it.
         */
        val exported: ExportedSession? = null,
        /** Set by the ViewModel, consumed by `PiRoot`. */
        val navRequest: NavRequest? = null,
        /**
         * pi's latest provider-reported usage, straight off the reducer
         * (`TranscriptReducer.lastUsage`, fed by `message_update.usage` and
         * `message_end.message.usage`). F10: every one of those payloads was
         * parsed and then dropped.
         *
         * **It is the last message's figure, not the turn's** — pi's cache-hit rate
         * is defined on one message (`components/footer.ts:94-100`:
         * `cacheRead ÷ (input + cacheRead + cacheWrite)`), so a reader that wants
         * 命中率 wants this field, and a reader that wants "what did this turn cost"
         * wants [turnUsage] instead.
         */
        val lastUsage: app.pi.rpc.TokenUsage? = null,
        /**
         * **This turn's** usage: every assistant reply since the turn started, summed
         * (`TranscriptReducer.turnUsage` — read its KDoc for the definition, for why
         * only settled `message_end` figures are added, and for what it deliberately
         * leaves out).
         *
         * `null` when the turn reported no usage at all, or before any turn has run;
         * a reader hides its group rather than printing zeros.
         */
        val turnUsage: app.pi.rpc.TokenUsage? = null,
        /**
         * App-local UI preferences read from pi's settings documents. Seeded at
         * boot and refreshed whenever the settings stack writes a key that the
         * app itself consumes — see [onSettingWritten].
         */
        val prefs: UiPrefs = UiPrefs(),
        /**
         * Which workspace this session is in, and [WorkspaceState.revision] — the
         * draft-clearing signal the composer keys on. Seeded from
         * [WorkspaceStore] when the ViewModel is built, moved by
         * [switchWorkspace].
         */
        val workspace: WorkspaceState = WorkspaceState(),
    )

    private val host = PiEngineHost(app)
    private var session: PiEngineSession? = null

    /**
     * The entries the current transcript was seeded from, in file order.
     *
     * Held **only to be re-seeded**: [expandEarlierHistory] rebuilds the transcript
     * from an older window plus this list, and the reducer cannot give the list back
     * because it stores rows (day separators, merged tool cards, optimistic echoes),
     * not entries. It is therefore bounded by what the user has actually scrolled
     * through — never by the session — and it is replaced wholesale, never appended
     * to, so a session switch cannot leave one session's entries in the next one's
     * rebuild. [replayHistory] is the only writer.
     */
    private var loadedHistory: List<JsonObject> = emptyList()

    /** Retained characters in [loadedHistory], against [HISTORY_RETAINED_CHARS]. */
    private var loadedHistoryChars: Long = 0L

    /**
     * The retained size of one entry, as the bound in [HISTORY_RETAINED_CHARS] counts it.
     *
     * The measurement itself lives in `HistoryRetention.kt` — the same package, and a
     * file with no Android dependency — because its cost is what moved every caller onto
     * `Dispatchers.IO` and `tools/run-app-pure-checks.sh` pins its arithmetic there. Read
     * that file's KDoc before changing how a window is counted here.
     *
     * **Every call site must be off the frame thread.** `viewModelScope` is
     * `Dispatchers.Main.immediate`, and the measure renders each entry back to JSON: ~135
     * ms for one 6 MiB base64 entry, ~460 ms for a 4000-entry text window, ~830 ms for the
     * same window with twenty 2 MiB images (desktop JVM, measured). All three loaders used
     * to sum it inline on the frame thread, which is the freeze this fixes.
     */
    private fun retainedChars(entries: List<JsonObject>): Long = entryCharsOf(entries)

    /**
     * Prompts typed before an engine attached, replayed in order by [attach].
     *
     * `Boot.Idle` is the window between "the app is up" and "pi answered the first
     * `get_state`" — 1–2 s on this device, every launch. The chat page is fully
     * usable there (D31: the boot surface is drawn for `Boot.Working` and
     * `Boot.Failed` only), so the composer is live and Enter reaches [send] while
     * [session] is still `null`. The old code answered that with
     * `val engine = session ?: return` — **the message vanished, with no bubble and
     * no error**, the exact class of silent drop this round exists to remove. So the
     * call is remembered and replayed verbatim once a session exists; a boot that
     * fails leaves it parked until a retry succeeds.
     *
     * Only the three paths a user can reach without an engine are held: [send],
     * [sendFollowUp] and [runPromptCommand]. `stop`, `restoreQueue` and the rest are
     * *about* a live engine and mean nothing without one.
     */
    private val pendingPrompts = mutableListOf<() -> Unit>()

    /**
     * Park [action] if no engine is attached yet; `true` when it was parked.
     *
     * The closure re-enters the public function it came from, so the replay runs the
     * same checks (compaction window, streaming behavior, optimistic echo) as a live
     * call rather than a copy of them that could drift.
     */
    private fun parkUntilAttached(action: () -> Unit): Boolean {
        if (session != null) return false
        pendingPrompts += action
        return true
    }

    /**
     * The engine's last exit, captured at the instant the state collector sees it
     * die.
     *
     * Kept as a field and not read back off the session because the death branch
     * drops that session on purpose (a dead object must not be left where the UI can
     * talk to it). Without this capture the evidence is unreachable by the time
     * anything wants it — which is what made 设置 → 导出诊断报告 say the exit code was
     * "not recorded" for the one failure it exists to explain, and what left the
     * failure screen with an empty detail line while the user's only report was
     * `rpc: engine exited with code 1`.
     */
    private var lastEngineExit: EngineDiagnostics? = null

    /**
     * The typed façade over the live engine, rebuilt whenever an engine is
     * attached. Null exactly when there is no engine to talk to, which is what
     * every action below checks before doing anything.
     */
    private var api: PiEngineApi? = null

    /**
     * The engine's own teardown, handed to the foreground service.
     *
     * The service cannot stop the engine itself: the process is a child of this app
     * process, spawned by [host], and the service has no reference to it. Until this
     * hook existed, the notification's 「停止」 walked
     * `PiEngineService.stopEngineAndSelf` → `PiEngineController.stop()`, which only
     * wrote an enum **no code ever read** — so it removed the notification, released
     * the wake lock and left pi running with nothing keeping its process alive.
     *
     * It runs on [teardownScope], not on the caller's thread: the caller is a service
     * callback on the main thread, and `PiEngineHost.shutdown` settles a running turn
     * (up to `SETTLE_TIMEOUT_MS`) before closing pi — the same ordering a restart
     * uses, because a closed stdin makes pi exit without writing the turn it is in
     * (`PiEngineSession.closeAfterSettling`).
     */
    private val stopEngineHook: () -> Unit = { teardownScope.launch { host.shutdown() } }

    init {
        PiEngineController.registerStopHandler(stopEngineHook)
    }

    /**
     * True while [PiEngineHost.boot] or [restart] is replacing the engine.
     *
     * Only read and written on the main dispatcher (`viewModelScope`, the engine
     * state collector), which is why it is a plain field. It exists because "the
     * engine went away" and "we are deliberately putting a new one in its place" look
     * identical from the state collector's seat: both publish `Stopped` while
     * `session` still points at the engine being retired. Acting on the first
     * (stopping the service, resetting the resume marker) during a restart would
     * remove the foreground protection the *new* engine is about to need and can
     * silently switch the user's session — see [attach] and [restartEngine].
     */
    private var engineTransition = false

    /**
     * pi's settings documents, addressed exactly as a desktop install has them.
     *
     * The engine host owns the path layout, so the store is built from it rather
     * than from a second guess at where things live. Reads merge the project
     * document over the global one the way pi does; writes land in whichever file
     * already carries the key, so a project override is not shadowed.
     *
     * **Rebuildable, not `by lazy`.** The project document is
     * `<workspace>/.pi/settings.json`, so the store is bound to a workspace, and a
     * workspace switch moves it. A `lazy` value would keep reading and writing the
     * *old* project's settings while the engine runs in the new workspace — the
     * exact split this batch exists to remove. [rebuildWorkspaceScopedCaches] drops
     * this field, and the next read builds a store bound to the new cwd. Between
     * switches the instance is stable, so a reader that holds it sees no churn.
     */
    @Volatile
    private var settingsStoreCache: PiSettingsStore? = null

    val settingsStore: PiSettingsStore
        get() = settingsStoreCache ?: PiSettingsFileStore.forWorkspace(
            agentDir = host.paths().agentDir,
            workspace = defaultWorkspace(),
        ).also { settingsStoreCache = it }

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

    /**
     * The `@` mention candidate source: the guest's own `fd`, run through the
     * app-side guest command channel. Built lazily because it touches the runtime
     * layout, and with the same workspace the engine is given
     * (`PtyLauncher.workspaceHost`, the one authority the terminal tab, the package
     * commands and the engine already share) rather than a second spelling of that
     * path.
     *
     * **Dropped on a workspace switch** by [rebuildWorkspaceScopedCaches]: its
     * `AgentLayout` carries `hostWorkspace` and `guestWorkspace` as fields, so a
     * kept instance would keep completing `@` paths out of the previous workspace
     * while the model works in the new one. Rebuilding is one `File` and one
     * `proot` argv; the first query after a switch pays for it once.
     */
    @Volatile
    private var mentionSourceCache: PiMentionSource? = null

    private val mentionSource: PiMentionSource
        get() = mentionSourceCache ?: PiMentionSource(
            getApplication(),
            PtyLauncher.workspaceHost(getApplication()),
        ).also { mentionSourceCache = it }

    /**
     * Which mention request is the current one. Only this class writes it, and only
     * the main thread does, so `@Volatile` is enough for the coroutine that reads it
     * from `Dispatchers.IO` to decide a request is stale.
     */
    @Volatile
    private var mentionRequestId: Int = 0

    /**
     * The last `@` failure the user was told about, so the 150 ms debounce cannot turn one
     * broken runtime into a notice per keystroke. Main-dispatcher only, like the state it
     * gates; cleared by any successful lookup.
     */
    private var lastMentionFailure: String? = null

    private val _sessions = MutableStateFlow<List<PiSessionStore.Summary>>(emptyList())
    val sessions: StateFlow<List<PiSessionStore.Summary>> = _sessions.asStateFlow()

    /**
     * Whether a [refreshSessions] scan is in flight **and has nothing to show yet**.
     *
     * The scan itself is cached per file and serialised inside [PiSessionStore], but
     * the *first* one still walks every session file, which is seconds on a phone.
     * Without this flag the screen had exactly two states — the list and "还没有会话"
     * — so the honest "still reading" moment rendered as **"you have no sessions"**,
     * and then the list appeared on its own.
     *
     * Deliberately false once [sessions] is non-empty: a refresh of a list that is
     * already on screen must not replace it with a spinner.
     */
    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionsLoading.value = _sessions.value.isEmpty()
            try {
                _sessions.value = runCatching { sessionStore.list() }.getOrDefault(emptyList())
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The composer's unsent text and staged images — see [ComposerDraft] for why the
     * ViewModel owns them rather than the screen and the Bundle.
     *
     * Declared before the `init` block at the end of this class on purpose:
     * `publishWorkspaceState` calls [alignComposerToWorkspace], which touches this
     * value, and property initialisers run in declaration order.
     */
    val composerDraft = ComposerDraft()

    /**
     * The workspace [WorkspaceState.revision] the content in [composerDraft] was
     * written in, or null until the first publication.
     *
     * The marker is what makes the clearing a *switch* rule rather than a
     * *revision-changed* rule. It replaces the `draftRevision` `rememberSaveable` the
     * screen used to carry, and it keeps that marker's semantics: it is aligned (never
     * cleared against) the first time this ViewModel publishes a workspace, which is
     * the startup reconcile — a fresh process has nothing to delete, and a draft typed
     * in the second before that reconcile lands must not be treated as belonging to an
     * old workspace.
     */
    private var composerRevision: Int? = null

    /**
     * Apply the user's ruling to the composer: **only a real workspace switch empties
     * it.**
     *
     * `>` rather than `!=`, from the screen version of this rule and for the same
     * reason: `revision` only ever grows inside one process, so a value below the
     * marker cannot be a switch. (In the screen that case was a marker restored from a
     * previous process, where the counter had restarted from 0; here the marker never
     * outlives the process, but the comparison is kept as the readable form of "a
     * switch moves the number forward".) A `false` [WorkspaceState.revision] bump —
     * the startup reconcile of a workspace the user did not choose — is an alignment,
     * and an alignment must not delete what the user wrote.
     */
    private fun alignComposerToWorkspace(revision: Int) {
        val mark = composerRevision
        if (mark != null && revision > mark) composerDraft.clear()
        composerRevision = revision
    }

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
     *
     * The value before the settings have been read follows the **system** night
     * mode, which is the same input `PiThemeLoader.load` uses when the `theme`
     * setting names nothing (`defaultSetting(systemDark)`) or names the automatic
     * pair. Hard-coding `true` here is what made a light-theme device paint one
     * dark frame and then swap: the composition's first frame read this flow, and
     * the resolved theme only arrived after a file read on `Dispatchers.IO`.
     */
    private val _theme = MutableStateFlow(PiResolvedTheme.fallback(systemDark = systemDarkAtStartup(app)))
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
            // pi's own key, at the top level of the document, default false
            // (`core/settings-manager.ts:120`, `:965-967`). Gates the
            // compaction / branch-summary cost line (F18).
            showCacheMissNotices = bool("showCacheMissNotices", false),
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
     *
     * Two things happen here, and they are different in kind: the app re-reads
     * what *it* renders, and the four keys pi also keeps in memory for the live
     * session are pushed over RPC (see the `when` below). Everything else the
     * settings page writes is picked up by pi the next time it reads a settings
     * document — a new session, or a new process.
     */
    fun onSettingWritten(key: String) {
        if (key == "theme") {
            refreshTheme()
            return
        }
        // The four agent-level switches whose settings rows are a second editor of
        // a value the chat screen already changes live. Writing `settings.json`
        // alone would not reach the running process: pi loads its `SettingsManager`
        // once per process **and** once per session (`settings-manager.ts:311-355`
        // `create` → `fromStorageWithPaths`; `agent-session-runtime.ts:236-252`
        // rebuilds the runtime, `main.ts:731` builds a fresh manager), so a file
        // write is invisible to the process that is talking to us. The RPC surface
        // is the only live path — `set_steering_mode`/`set_follow_up_mode`
        // (`rpc-mode.ts:521-530`) and `set_auto_compaction`/`set_auto_retry`
        // (`rpc-mode.ts:532-540`) — and pi persists the same key it was given
        // (`agent-session.ts:1902-1917`), so the two writers cannot drift apart.
        when (key) {
            "steeringMode" -> queueModeOf(settingsStore.readString(key))?.let(::setSteeringMode)
            "followUpMode" -> queueModeOf(settingsStore.readString(key))?.let(::setFollowUpMode)
            "compaction.enabled" -> settingsStore.readBoolean(key)?.let(::setAutoCompaction)
            "retry.enabled" -> settingsStore.readBoolean(key)?.let(::setAutoRetry)
        }
        if (
            key == "hideThinkingBlock" ||
            key.startsWith("app.appearance.") ||
            key.startsWith("app.tools.") ||
            key == "app.runtime.keepAlive" ||
            // pi's `showCacheMissNotices` gates the summarization billing line the
            // transcript prints (F18), so a write has to reach `UiPrefs` before the
            // next frame or the row would look inert.
            key == "showCacheMissNotices" ||
            key == "themes"
        ) {
            refreshPrefs()
            refreshTheme()
        }
        if (key == "enableSkillCommands") {
            // The palette is built with this flag, so the list in `UiState` is now
            // stale — and it is the only place the setting has any effect at all
            // (`piCommandPalette`).
            refreshCommands()
        }
    }

    /**
     * Drop the settings store's cached documents after a write that did not go
     * through it.
     *
     * `PiSettingsFileStore` caches each document after its first read
     * (`global`/`project`), and every other writer bypasses that cache: pi itself,
     * `pi install` (guest-side), and the credential form's `PiEnginePreferences`.
     * That last one invalidates a store instance of its *own*
     * (`PiConfigFiles.kt:579`), so the app's reader kept the pre-write values —
     * which is why this lives here, next to [settingsStore], rather than in the
     * settings UI, and why [attach] calls it as well: a restarted engine is
     * exactly the moment those files may have changed underneath us.
     */
    fun invalidateSettingsCache() {
        (settingsStore as? PiSettingsFileStore)?.invalidate()
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

    /** `pi -c` is attempted once per process, not on every engine attach. */
    private var resumeAttempted = false

    /**
     * The engine revision this consumer has already applied to [UiState]; `0` is
     * "nothing seen".
     *
     * A local marker rather than [UiState.revision] because the counter belongs to
     * one engine's publication stream: on [attach] a *new* engine starts again at
     * 1, and a leftover number from the previous one could make the gap check below
     * accept a publication whose `changedIndices` describe rows this consumer never
     * held — applying them to the old session's list. It is reset with the engine
     * for exactly that reason. See [syncTranscript].
     */
    private var appliedRevision = 0

    /**
     * The three collectors [attach] starts for one engine, so the next [attach] can
     * stop them.
     *
     * `viewModelScope` is the ViewModel's scope, not the engine's, so without these
     * handles every engine ever attached left a permanent subscriber behind: three per
     * restart, three per workspace switch, each one still reading its retired engine's
     * flows. Cancelling them is the first half of the fix; the `session !== engine`
     * guards inside each collector are the second, because cancellation is
     * cooperative — a `PiEngineSession` reader already inside `handle()` finishes that
     * record and publishes (see the guards for the full chain).
     */
    private var engineStateJob: Job? = null
    private var enginePublicationJob: Job? = null
    private var engineEventsJob: Job? = null

    /**
     * The running `bash` command's output, accumulated in place.
     *
     * A builder rather than `String` concatenation because a chatty command streams
     * hundreds of chunks: `output + delta` is quadratic in the output's length (measured:
     * ~2.4 s to accumulate 1 MiB from 200-char chunks on a desktop JVM), while an append
     * is amortised O(1). It is published as a `String` only when the throttle lets a
     * publication through ([BASH_UPDATE_THROTTLE_MS]), and the `bash` response replaces
     * the whole output at the end of the run, so the builder never has to be handed out
     * mid-stream.
     *
     * Written and read on the main dispatcher only: the deltas arrive on the events
     * collector and the response replaces the state in `runBash`, both on
     * `viewModelScope`.
     */
    private val bashStream = StringBuilder()

    /**
     * Whether [bashStream] has had its front dropped for the run in flight.
     *
     * Sticky per run, and reset with the builder: the panel's 「输出被截断」 sentence is
     * driven by `BashRun.truncated`, and a run that once passed the bound must keep
     * saying so even on a publication that happens not to trim.
     */
    private var bashStreamTrimmed = false

    /**
     * When [bashStream] was last published, as [SystemClock.elapsedRealtime], or `0` for
     * "the run has not published yet" — the first chunk of a command must appear at once
     * rather than after the first window.
     */
    private var lastBashPublishAt = 0L

    /** Monotonic ids for snackbar notices and composer fills. */
    private var noticeSeq = 0L
    private var composerFillSeq = 0L

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

    /**
     * Stop the foreground service when it has nothing left to protect.
     *
     * The service exists to keep *the engine's* process tree alive
     * (`app.runtime.keepAlive`), so "there is no engine and none is coming" is the
     * state in which it must not keep a notification saying 「PI 正在运行」 over
     * nothing. The decision itself is [PiEngineLifecyclePolicy.shouldServiceRun] —
     * a pure function, so the truth table lives in the bare-JVM harness instead of
     * in this class.
     *
     * Never stops the service when the answer is "it should run": starting it is
     * [startEngineService]'s job, called from [boot] and [attach] where the intent is
     * unambiguous.
     */
    private fun syncEngineService(engineAttached: Boolean, bootInProgress: Boolean) {
        val keepAlive = _state.value.prefs.keepAlive
        if (PiEngineLifecyclePolicy.shouldServiceRun(keepAlive, engineAttached, bootInProgress)) return
        PiEngineService.stopIfRunning()
    }

    /**
     * Tell the service whether anything needs the CPU awake right now.
     *
     * Called at every engine-state transition and from [syncTranscript] (the only
     * other place the answer can change), both change-gated by [reportedWork] — this
     * is a decision that flips a few times per turn, not per streamed token. The
     * inputs are the CPU-bound windows
     * ([PiEngineLifecyclePolicy.shouldHoldWakeLock]): the engine starting (unpacking,
     * node cold start: nothing on screen, all CPU) and a turn running — where "a turn"
     * is the union of the three signals this class already tracks, because no single
     * one of them covers every long operation:
     *
     *  - `engine == Busy` — pi's own `agent_start` … `agent_settled`;
     *  - `streaming` — the reducer's flag, which also survives a state emission the
     *    main dispatcher never got to see (`StateFlow` conflates: a `Busy` that came
     *    and went between two frame-thread resumes is simply not delivered);
     *  - `busy != null` — an in-flight long RPC call, which is what covers compaction
     *    (`PiEngineSession.closeAfterSettling`'s own note, `PiEngineSession.kt:757-760`:
     *    `agent_end` maps to `Ready` while "a compaction may still be running", so
     *    the engine state alone under-reports it).
     */
    private fun reportWakeLockNeed() {
        val current = _state.value
        val active = PiEngineLifecyclePolicy.shouldHoldWakeLock(
            // `engineTransition` counts as booting: while a restart is replacing the
            // engine, the old one's `Stopped` would otherwise release the lock for the
            // whole of the new engine's cold start — the one window where losing the
            // CPU means the restart stalls until the screen comes back.
            booting = engineTransition ||
                current.boot is Boot.Working ||
                current.engine == PiEngineSession.EngineState.Starting,
            turnRunning = current.engine == PiEngineSession.EngineState.Busy ||
                current.streaming ||
                current.busy != null,
        )
        if (reportedWork == active) return
        reportedWork = active
        PiEngineService.reportWork(active)
    }

    /**
     * The last answer handed to [PiEngineService.reportWork], so the decision above
     * can be evaluated on the publication path without posting a notification per
     * streamed token. `null` means "nothing reported yet in this ViewModel's life".
     */
    private var reportedWork: Boolean? = null

    /**
     * The workspace is app-private for speed; `/sdcard` goes through FUSE. The
     * directory comes from the runtime layer's accessor rather than from a second
     * copy of `workspace-1`, so the terminal, this ViewModel and the device
     * shell's write boundary cannot end up pointing at different directories.
     */
    private fun defaultWorkspace(): File = PtyLauncher.workspaceHost(getApplication())

    /**
     * Where pi sees [defaultWorkspace].
     *
     * The rule is `GuestWorkspacePath`'s — `PiEngineHost` binds
     * `workspace.absolutePath` at that path and starts pi with it as its cwd.
     * Paths inside a `bash` or `export_html` argument are resolved **in the
     * guest**, so they must be written in the guest's spelling. This used to be a
     * literal here, which is one more place the engine's cwd could drift.
     */
    private fun guestWorkspace(): String = GuestWorkspacePath.under(
        getApplication<Application>().filesDir.absolutePath,
        defaultWorkspace().absolutePath,
    )

    // ------------------------------------------------------------ workspaces

    /**
     * The workspaces the user can choose between, for a screen to render.
     *
     * A pass-through to [WorkspaceStore] on purpose: the store owns the layout and
     * the safety rules, and this ViewModel method exists so a screen mounted with a
     * `PiSessionViewModel` needs no second import to list them. Blocking file IO,
     * called from a composition-driven refresh path — the same shape the settings
     * stack already uses for pi's own directories.
     */
    fun workspaceEntries(): List<WorkspaceStore.Entry> = WorkspaceStore.list(getApplication())

    /** Create the next `workspace-N`. Does **not** switch to it; see [switchWorkspace]. */
    fun createWorkspace(): WorkspaceStore.Create = WorkspaceStore.create(getApplication())

    /** Give a workspace a display label. Never moves the directory — see [WorkspaceStore.rename]. */
    fun renameWorkspace(name: String, label: String): WorkspaceStore.Rename =
        WorkspaceStore.rename(getApplication(), name, label)

    /**
     * Count what deleting [name] would destroy. The returned
     * [WorkspaceStore.DeleteConfirmation] is the only thing [deleteWorkspace]
     * accepts, which is how "report the file count and ask again" stops being a
     * convention and becomes the API shape.
     */
    fun previewWorkspaceDelete(name: String): WorkspaceStore.Preview =
        WorkspaceStore.previewDelete(getApplication(), name)

    /** Delete what [confirmation] described. Never the current workspace. */
    fun deleteWorkspace(confirmation: WorkspaceStore.DeleteConfirmation): WorkspaceStore.Delete =
        WorkspaceStore.delete(getApplication(), confirmation)

    /**
     * Move the engine into another workspace: persist the choice, stop the current
     * engine, boot a new one in the target directory.
     *
     * ## Ordering, and every failure in it
     *
     * 1. **Resolve the target first, with the old engine still alive.** A name we do
     *    not own, a directory that is gone, or one this app cannot write into is a
     *    refusal ([WorkspaceSwitch.Refused]) and changes nothing at all.
     * 2. **Move the engine through [PiEngineHost.restart]**, not by hand: that is
     *    the process-wide `PROCESS_LOCK` and the stop-before-start ordering the host
     *    exists to enforce, and it is the same path the extension-install restart
     *    uses. The provider passed is the **target directory**, so the engine's cwd
     *    comes from this decision rather than from a settings file that has not been
     *    written yet — which is what makes the next step safe.
     * 3. **Persist only after the new engine is up** ([WorkspaceStore.setCurrent]).
     *    A crash between 2 and 3 therefore leaves the stored choice pointing at the
     *    previous workspace, which is the one a fresh boot will use; the app is never
     *    left with a setting that claims a workspace the engine never entered.
     * 4. **Re-key everything bound to the old cwd** ([rebuildWorkspaceScopedCaches]).
     * 5. **Bump [WorkspaceState.revision]**, which clears the composer's draft and
     *    staged images alongside it ([afterWorkspaceChanged] →
     *    [publishWorkspaceState] → [alignComposerToWorkspace]) — see [WorkspaceState]
     *    and [ComposerDraft].
     *
     * When [PiEngineHost.restart] fails it has already stopped the old engine. The
     * old workspace's engine is then booted again, so the outcome is either "back to
     * where you were" ([WorkspaceSwitch.Failed.rolledBackTo] non-null) or an explicit
     * stopped state with the reason (null) — never "setting says A, engine runs B".
     * A refusal changes nothing by construction.
     *
     * ## A running turn
     *
     * [allowInterrupt] = false turns a busy engine into a refusal
     * ([PiEngineHost.Restart.RefusedTurnRunning]), which is what a caller that wants
     * to confirm first should pass. The default is true — this is an explicit "move
     * me to that workspace" tap, and a composer that silently did nothing while the
     * tap looked accepted would be worse. The "before" half of the warning is
     * [wouldInterruptTurn], which the screen asks before offering the choice; the
     * "after" half is the notice this method pushes plus
     * [WorkspaceSwitch.Ok.interruptedTurn] — so the user is never left wondering why
     * the answer they were waiting for stopped arriving.
     *
     * Call from a main-dispatcher coroutine (`viewModelScope`), like
     * [restartEngine].
     */
    suspend fun switchWorkspace(name: String, allowInterrupt: Boolean = true): WorkspaceSwitch {
        val app = getApplication<Application>()
        val previous = WorkspaceStore.refresh(app).name
        if (name == previous) return WorkspaceSwitch.AlreadyCurrent(name)

        val target = WorkspaceStore.existing(app, name)
            ?: return WorkspaceSwitch.Refused("工作区「$name」不存在或不是本应用创建的，没有切换。")
        if (!target.host.isDirectory || !target.host.canWrite()) {
            return WorkspaceSwitch.Refused(
                "工作区「${target.displayName}」的目录不可写（${target.host.absolutePath}），没有切换。",
            )
        }
        val oldHost = WorkspaceStore.currentHost(app)
        // Captured *before* the restart: `host.turnRunning` reads the live engine's
        // pi state, and the live engine after a successful restart is a different
        // process that has never run a turn.
        val wasBusy = host.turnRunning

        engineTransition = true
        val result = try {
            host.restart(
                reason = "切换工作区到「${target.displayName}」",
                // The target itself, not `::defaultWorkspace`: the stored choice is
                // written *after* this succeeds (step 3 above), so the provider
                // cannot be the thing that decides where the new engine starts.
                workspaceProvider = { target.host },
                allowInterrupt = allowInterrupt,
                launch = launchOptions(),
            )
        } finally {
            engineTransition = false
        }

        return when (result) {
            is PiEngineHost.Restart.Ok -> {
                // Persist, then adopt the new engine — that order, not the reverse:
                // `attach` (and everything after it) resolves `defaultWorkspace()`,
                // which reads the store, so the stored choice has to name the new
                // workspace by the time those run.
                val stored = WorkspaceStore.setCurrent(app, name)
                afterWorkspaceChanged(name, result.session)
                when {
                    wasBusy -> pushNotice(
                        "切换工作区中断了正在运行的回合：新引擎在「${target.displayName}」里，" +
                            "上一轮未完成的模型调用与工具都被停掉了。",
                        Notice.Tone.Warning,
                    )

                    else -> pushNotice("已切换到工作区「${target.displayName}」。", Notice.Tone.Info)
                }
                if (!stored) {
                    pushNotice(
                        "工作区已切换，但这次选择没有保存成功（设置文件不可写）；" +
                            "重启应用后会回到「$previous」。",
                        Notice.Tone.Warning,
                    )
                }
                WorkspaceSwitch.Ok(name, interruptedTurn = wasBusy)
            }

            is PiEngineHost.Restart.RefusedTurnRunning -> {
                // Nothing changed: `restart` refuses before it stops anything.
                pushNotice(result.detail, Notice.Tone.Warning)
                WorkspaceSwitch.Refused(result.detail)
            }

            is PiEngineHost.Restart.RefusedNeedsProvisioning -> {
                pushNotice(result.detail, Notice.Tone.Warning)
                WorkspaceSwitch.Refused(result.detail)
            }

            is PiEngineHost.Restart.Failed -> rollBackWorkspace(app, previous, oldHost, target, result)
        }
    }

    /**
     * Whether a workspace switch right now would kill a turn. The screen asks this
     * before offering the choice, so `allowInterrupt = true` is an informed tap
     * rather than a surprise.
     */
    fun wouldInterruptTurn(): Boolean = host.turnRunning

    /**
     * Everything that was resolved against the old cwd, refreshed for the new one.
     *
     * Four things are bound to a workspace and are easy to miss:
     *  - [settingsStoreCache] — pi's *project* settings are `<workspace>/.pi/settings.json`;
     *  - [mentionSourceCache] — `fd` runs inside the workspace;
     *  - the theme and its entries — project themes come from the workspace;
     *  - the session list — pi records a session's cwd, and the list is filtered by it
     *    (`SessionsScreen` compares against [GuestWorkspacePath.RELATIVE], which
     *    [WorkspaceStore.setCurrent] has already moved).
     */
    private fun afterWorkspaceChanged(name: String, engine: PiEngineSession?) {
        // The caches first: `attach` reads `settingsStore` (to invalidate it), and
        // that getter would otherwise hand it a store still bound to the previous
        // workspace's project document.
        rebuildWorkspaceScopedCaches()
        // `attach` is not optional and not cosmetic: `PiEngineHost.restart` closed
        // the old engine before starting the new one, and every action here checks
        // `api != null`, so without it the UI would be inert until the next boot.
        if (engine != null) attach(engine)
        refreshPrefs()
        refreshTheme()
        refreshSessions()
        publishWorkspaceState(name, note = null, bumped = true)
    }

    private fun rebuildWorkspaceScopedCaches() {
        settingsStoreCache = null
        mentionSourceCache = null
    }

    /**
     * Put the workspace into [UiState], re-reading the directory for [hostPath].
     *
     * [bumped] is what tells the composer "this is a freshly switched session"; a call
     * that only re-reads the path (or reports a fallback) passes true only when the
     * workspace genuinely moved. Either way [alignComposerToWorkspace] runs here,
     * because this is the only place [WorkspaceState.revision] changes — see
     * [ComposerDraft] for why the clearing lives in the ViewModel now.
     */
    private fun publishWorkspaceState(name: String, note: String?, bumped: Boolean) {
        val current = _state.value.workspace
        val host = runCatching { WorkspaceStore.existing(getApplication(), name)?.host?.absolutePath }
            .getOrNull()
        val revision = current.revision + if (bumped) 1 else 0
        _state.value = _state.value.copy(
            workspace = current.copy(
                name = name,
                relative = WorkspaceStore.relativeOf(name),
                hostPath = host,
                revision = revision,
                note = note,
            ),
        )
        alignComposerToWorkspace(revision)
        if (note != null) pushNotice(note, Notice.Tone.Warning)
    }

    /**
     * The switch failed after the old engine was already stopped. Put the app back
     * on its feet: boot the **previous** workspace again, and report honestly which
     * of the two outcomes happened.
     *
     * Nothing here writes the workspace setting — it still names [previous], and the
     * point of this recovery is to make that true of the running engine as well.
     */
    private suspend fun rollBackWorkspace(
        app: Application,
        previous: String,
        oldHost: File,
        target: WorkspaceStore.Entry,
        failure: PiEngineHost.Restart.Failed,
    ): WorkspaceSwitch {
        engineTransition = true
        val back = try {
            host.boot(workspaceProvider = { oldHost }, launch = launchOptions()) { step ->
                _state.value = _state.value.copy(boot = Boot.Working(step))
            }
        } finally {
            engineTransition = false
        }
        val headline = "切换到「${target.displayName}」失败：${failure.message}"
        return when (back) {
            is PiEngineHost.Boot.Ready -> {
                // No cache rebuild here: the workspace never moved — `setCurrent`
                // was not reached, so the stored choice and `GuestWorkspacePath` both
                // still name [previous], and every cwd-bound cache is still correct.
                attach(back.session)
                val message = "$headline，已回到「$previous」并重新启动引擎。"
                pushNotice(message, Notice.Tone.Warning)
                WorkspaceSwitch.Failed(message, failure.detail, rolledBackTo = previous)
            }

            else -> {
                val detail = (back as? PiEngineHost.Boot.Failed)?.detail ?: failure.detail
                val why = (back as? PiEngineHost.Boot.Failed)?.message ?: "引擎未能重新启动"
                _state.value = _state.value.copy(boot = Boot.Failed(headline, detail))
                syncEngineService(engineAttached = false, bootInProgress = false)
                reportWakeLockNeed()
                val message = "$headline；在「$previous」重新启动也失败了（$why）。" +
                    "引擎现在是停止的，工作区设置仍然是「$previous」。"
                pushNotice(message, Notice.Tone.Error)
                WorkspaceSwitch.Failed(message, detail, rolledBackTo = null)
            }
        }
    }

    /**
     * pi's **process** configuration, assembled from the five App-side keys of
     * 设置 → 运行时 → 进程.
     *
     * pi has no settings keys for these — its `Settings` interface
     * (`core/settings-manager.ts:106-158`) carries no `offline`, `systemPrompt`,
     * `systemPrompt` append, `cacheRetention` or `noContextFiles`; they exist
     * only as CLI flags and environment variables (`--offline` / `PI_OFFLINE=1`,
     * `src/cli/args.ts:223`, `:433`; `--system-prompt` / `--append-system-prompt`,
     * `:110`, `:112`; `--no-context-files`, `:194`; `PI_CACHE_RETENTION=long`,
     * `packages/ai/src/api/anthropic-messages.ts:57`). So the *keys* are ours and
     * the *values* are pi's, handed over in [PiLaunchOptions] because that is the
     * only moment pi reads them. The authoritative table — including which CLI
     * knobs pi already covers with a settings key, and which are meaningless on a
     * phone — is `rpc/PiPreSpawnConfig.kt` (`docs/pre-spawn-config.md`).
     *
     * Only the file IO is here; the normalisation (blank means unset, only `long`
     * means long retention) lives in [PiLaunchOptions.fromSettingValues], where
     * the bare-JVM harness can execute it.
     *
     * Read fresh on every boot and every restart: a restart that replayed the
     * boot-time set would silently ignore a change made since.
     */
    private fun launchOptions(): PiLaunchOptions = PiLaunchOptions.fromSettingValues(
        offline = settingsStore.readBoolean("app.runtime.offline"),
        cacheRetention = settingsStore.readString("app.runtime.cacheRetention"),
        // Blank means "use pi's own prompt", not "pass an empty prompt": pi only
        // takes `--system-prompt` when there is text to pass.
        systemPrompt = settingsStore.readString("app.runtime.systemPrompt"),
        appendSystemPrompt = settingsStore.readString("app.runtime.appendSystemPrompt"),
        noContextFiles = settingsStore.readBoolean("app.runtime.noContextFiles"),
    )

    /**
     * The engine's last exit, for 设置 → 运行时与诊断 → 导出诊断报告.
     *
     * A getter rather than the field itself because the diagnostic screen must read
     * the **newest** capture: the engine can die while that screen is open, and the
     * screen rebuilds from this lambda. Null until an engine has actually exited in
     * this process — the report says so in words rather than showing a fabricated
     * default.
     */
    fun engineDiagnostics(): EngineDiagnostics? = lastEngineExit

    /**
     * What this app has already recorded going wrong, newest first, for the
     * report's 最近的失败 section.
     *
     * Two sources, because the app records failures in two places: the AppBar's
     * error line ([UiState.lastError], which `fail()` sets on every failed RPC call
     * — that is where `rpc: engine exited with code 1` itself lands) and the error
     * notices the transcript shows. Distinct and bounded, so the same sentence does
     * not appear four times in one report.
     */
    fun recentFailures(): List<String> {
        val state = _state.value
        val seen = LinkedHashSet<String>()
        state.lastError?.takeIf { it.isNotBlank() }?.let { seen += it }
        state.notices
            .filter { it.tone == Notice.Tone.Error }
            .map { it.message }
            .filter { it.isNotBlank() }
            .forEach { seen += it }
        return seen.toList().take(MAX_REPORTED_FAILURES)
    }

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
            // mid-write. The service is what keeps that process tree alive — the
            // engine itself is a child of this process (`host`). `keepAlive = false`
            // is the user asking for exactly that risk.
            if (prefs.keepAlive) startEngineService()
            // Unpacking the runtime and pi's cold start are CPU-bound with nothing on
            // screen, so the wake lock has to be held across them; the service takes
            // it when it starts (`startForegroundWithNotification`), and the engine
            // state collector releases it once the engine is up and idle
            // (`reportWork`).
            engineTransition = true
            val boot = try {
                host.boot(workspaceProvider = ::defaultWorkspace, launch = launchOptions()) { step ->
                    _state.value = _state.value.copy(boot = Boot.Working(step))
                }
            } finally {
                engineTransition = false
            }
            when (boot) {
                is PiEngineHost.Boot.Ready -> attach(boot.session)
                is PiEngineHost.Boot.Failed -> {
                    _state.value = _state.value.copy(boot = Boot.Failed(boot.message, boot.detail))
                    // There is no engine and none is coming, so the service has
                    // nothing to protect: leaving it up would keep a notification
                    // saying 「PI 正在运行」 over a process that failed to start.
                    // The retry button on the same screen calls `boot()` again, and
                    // that starts the service again if it is still wanted. Both
                    // reports are needed here because the boot is over: the wake lock
                    // was held for it (`engineTransition`), and the engine state
                    // collector never ran (there is no engine to collect).
                    syncEngineService(engineAttached = session != null, bootInProgress = false)
                    reportWakeLockNeed()
                }
                else -> Unit
            }
        }
    }

    /**
     * Restart the engine, then re-attach this ViewModel to the new process.
     *
     * This is the callback the package and credential screens call through
     * [EngineRestartCoordinator], whose KDoc names the real restart as
     * `{ reason, allow -> engineHost.restart(reason, ...) }`. It lives here
     * because `host` is private, and because the ViewModel — not the settings
     * screen — is what has to keep talking to the engine afterwards.
     *
     * `allowInterrupt` is forwarded verbatim. The coordinator always passes
     * `false`, and pi's own `Busy` state is re-checked independently by
     * [PiEngineHost.restart] (`PiEngineHost.kt:367-372`), so a turn that started
     * between the user's tap and this call cannot be interrupted by accident.
     *
     * Re-attaching on success is not optional: `restart` closes the old process
     * before starting the new one (`PiEngineHost.kt:389-393`) and every action
     * here checks `api != null`, so without it the UI would be inert until the
     * next `boot()`.
     *
     * The launch options are re-read here rather than replayed: the 进程 switches
     * are pi's process configuration, so a restart is exactly the moment they are
     * supposed to take effect (`PiEngineHost.restart`'s `launch` parameter).
     */
    suspend fun restartEngine(
        reason: String,
        allowInterrupt: Boolean,
    ): EngineRestartCoordinator.Outcome {
        // The old engine publishes `Stopped` while it is being retired, and
        // `session` still points at it — the state collector cannot tell that apart
        // from a crash. [engineTransition] is what tells it: without this the
        // collector would stop the foreground service and reset the resume marker in
        // the middle of a restart, i.e. drop the wake lock across the *new* engine's
        // cold start and silently switch sessions when `app.sessions.resumeLast` is on.
        engineTransition = true
        val result = try {
            host.restart(
                reason = reason,
                workspaceProvider = ::defaultWorkspace,
                allowInterrupt = allowInterrupt,
                launch = launchOptions(),
            )
        } finally {
            engineTransition = false
        }
        if (result is PiEngineHost.Restart.Ok) {
            attach(result.session)
        } else {
            // A refused restart leaves the old engine running (nothing changes); a
            // failed one leaves none. `session` may lag behind by one collector
            // dispatch, which is why the death branch below is the authority — this
            // call is only here so the service does not outlive a failed restart, and
            // the wake-lock report so the lock taken across the transition is
            // released when the transition ends with no engine.
            syncEngineService(engineAttached = session != null, bootInProgress = false)
            reportWakeLockNeed()
        }
        return result.asOutcome()
    }

    /**
     * Live turn state for callers that must not interrupt a turn.
     * [EngineRestartCoordinator.isTurnRunning] is documented as "normally
     * `{ engineHost.turnRunning }`"; this is that answer without exposing the
     * host.
     */
    fun isTurnRunning(): Boolean = host.turnRunning

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
        // A fresh engine is exactly the moment the settings documents may have
        // changed on disk, so the cache is dropped here rather than left showing
        // pre-write values. See [invalidateSettingsCache].
        invalidateSettingsCache()
        _state.value = _state.value.copy(boot = Boot.Ready)
        // Anything typed before this engine attached is deliverable now: replay it in
        // order ([pendingPrompts]). The list is copied and cleared **before** the
        // closures run, so a replay that re-reaches `send` cannot append to the list
        // being iterated — and so a replay that fails cannot leave the queue holding
        // an entry that already ran.
        if (pendingPrompts.isNotEmpty()) {
            val queued = pendingPrompts.toList()
            pendingPrompts.clear()
            queued.forEach { it() }
        }
        engineStateJob?.cancel()
        viewModelScope.launch {
            engine.state.collect { engineState ->
                // Only the engine that is still current may write state. A
                // restart attaches a new engine while the old one's collector is
                // still alive, and `PiEngineSession.close()` publishes `Stopped`
                // — that value can be delivered after
                // `attach` has already installed the new session, which would
                // null out the fresh `api` and leave the whole UI inert.
                if (session !== engine) return@collect
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
                    //
                    // `session = null` is part of the same statement, and it is not
                    // cosmetic: `send`/`sendFollowUp`/`runPromptCommand` read
                    // `session` directly (that is what echoes a queued message into
                    // the transcript), so leaving the dead object in place let the
                    // composer write into a closed pipe. `PiEngineSession.send` no
                    // longer throws, but a message that silently goes nowhere is not
                    // an acceptable answer either — hence the failure state below,
                    // which is what replaces the composer with 重试
                    // (`ChatScreen.kt:176-183`).
                    api = null
                    session = null
                    cancelAllDialogs()
                    clearExtensionChrome()
                    // Capture the exit facts **now**, while the dead session is still
                    // in hand: the next statement drops the last reference to it, and
                    // these four values (exit code, captured stderr, the startup
                    // measurement, and which of Stopped/Failed it ended in) are the
                    // only evidence the failure leaves behind. `DiagnosticsReport`
                    // renders them; nothing else can still read them once this
                    // function returns.
                    lastEngineExit = EngineDiagnostics(
                        state = engineState.name,
                        exitCode = engine.lastExitCode,
                        // The engine's own last words. Without this the failure screen
                        // said "引擎异常退出" and the report said the exit code was not
                        // recorded — for the one failure both exist to explain.
                        stderr = engine.stderr.toString().ifBlank { null },
                        startupMs = PiEngineSession.lastServingMs,
                        // The reader-thread losses: an event the engine's flow could not
                        // hand to this ViewModel, or a transcript fold that threw. Both
                        // used to be invisible; a non-zero count here is what turns
                        // "引擎好像没回话" into a number and a sentence.
                        droppedEvents = engine.droppedEvents,
                        reducerFailures = engine.reducerFailures,
                        lastRecordProblem = engine.lastRecordProblem,
                    )
                    // The dead engine can never deliver the `bash` response that would
                    // have replaced this accumulation, and the panel is dropped just
                    // below — so the builder goes with it.
                    bashStream.setLength(0)
                    lastBashPublishAt = 0L
                    bashStreamTrimmed = false
                    _state.value = _state.value.copy(
                        bash = null,
                        busy = null,
                        // The engine is gone, so nothing can still be streaming —
                        // and this flag is one of the inputs to `reportWakeLockNeed`,
                        // so leaving it true would keep the CPU wake lock held for a
                        // turn that no longer exists.
                        streaming = false,
                        boot = Boot.Failed(
                            if (engineState == PiEngineSession.EngineState.Stopped) {
                                "引擎已停止，可以重新启动后继续。"
                            } else {
                                "引擎异常退出，可以重新启动后继续。"
                            },
                            // The engine's own reason, in one sentence plus the stderr
                            // line it came from. `BootScreen` renders this under the
                            // title (`BootScreen.kt:129-133`), and its own last line
                            // asks the user to send exactly this text on. `null` when
                            // there is nothing attributable, in which case the generic
                            // sentence above is the honest answer.
                            EngineExitCause.detail(engine.lastExitCode, engine.stderr.toString()),
                        ),
                    )
                    if (!engineTransition) {
                        // Not a restart: nothing is replacing this engine, so the
                        // foreground service has nothing left to protect, and a
                        // retry (the new failure screen's button) is a *new*
                        // engine — which is a new chance to honour
                        // `app.sessions.resumeLast`. During a restart neither is
                        // true, which is what `engineTransition` is for.
                        resumeAttempted = false
                        syncEngineService(engineAttached = false, bootInProgress = false)
                    }
                }
                reportWakeLockNeed()
            }
        }.also { engineStateJob = it }
        // The publication stream starts over with this engine (a fresh
        // `PiEngineSession` counts from 1), so the consumer's marker must too:
        // otherwise the first publication of the new engine could look like the
        // continuation of the old one's revisions, and its `changedIndices` would
        // be applied to the previous session's rows.
        appliedRevision = 0
        // Every collector this function starts is torn down here, before the next one
        // replaces it. They used to live for the whole ViewModel (`viewModelScope`
        // outlives an engine), so a restart or a workspace switch left one set per
        // retired engine running; each of those sets keeps folding its own engine's
        // values, and the guard below is what makes that harmless rather than
        // visible.
        enginePublicationJob?.cancel()
        engineEventsJob?.cancel()
        viewModelScope.launch {
            // The publication, not `revision`: it carries the rows that moved
            // (`TranscriptPublication.changedIndices`), which is the whole point
            // of F7/RR-P7. A `revision` collector can only re-read the reducer's
            // list and diff it here — an O(n) scan per streamed delta.
            engine.publication.collect { pub ->
                // Only the engine that is still current may write state — the same
                // rule the state collector below states for `Stopped`/`Failed`, and
                // the reason it is needed here too: `PiEngineSession.close()` cancels
                // its reader cooperatively (`PiEngineSession.kt:1018`), so a `handle()`
                // already in flight still folds its record and publishes. Without this
                // guard `syncTranscript` would take that publication — the *previous*
                // session's rows — and write it into the state the new engine has
                // already installed, with `appliedRevision` moved to the retired
                // engine's revision as well.
                if (session !== engine) return@collect
                syncTranscript(engine, pub)
            }
        }.also { enginePublicationJob = it }
        viewModelScope.launch {
            engine.events.collect { event ->
                // Same guard, same reason: `onEvent` writes `queueSteering`,
                // `queueFollowUp`, `bash`, `lastError` and the compaction flag
                // unconditionally, and `afterSessionReplaced` has just zeroed the
                // queue counts for the new session.
                if (session !== engine) return@collect
                onEvent(event)
            }
        }.also { engineEventsJob = it }
        // A fresh engine has no transcript in this process. pi owns the session
        // file, so the rows are rebuilt from `get_entries` rather than from memory
        // (audit §6.7) — this is also what makes an extension's `appendEntry`
        // state and its `custom_message`s visible at all.
        viewModelScope.launch {
            // The replay projects the whole session. `seedHistory` suspends now, so
            // this no longer blocks the frame thread — but the screen would sit on
            // the previous (usually empty) transcript with no explanation. `busy` is
            // already the AppBar's second line (`engineLabel`), and the switch /
            // fork / clone replays get it from `call`; this attach-time replay is the
            // one that never did. Cleared token-matched, exactly like `call`'s
            // `finally`, so a newer operation's label is never wiped.
            _state.value = _state.value.copy(busy = "正在加载会话")
            try {
                replayHistory(engine)
            } finally {
                if (_state.value.busy == "正在加载会话") {
                    _state.value = _state.value.copy(busy = null)
                }
            }
            refreshState()
            refreshCommands()
            refreshTuiOnlyExtensions()
            seedAutoRetryFromSettings()
            // The agent dir exists for sure by now, so a theme dropped into it
            // while the app was closed is discovered here as well.
            refreshPrefs()
            refreshTheme()
            maybeResumeLastSession()
        }
    }

    /**
     * pi's `-c` / `--continue` (`cli/args.ts:100`): on launch, pick up where the
     * last session **for this cwd** left off.
     *
     * The app's engine always starts a fresh session and its argv is fixed
     * (`PiEngineHost.kt:275-280`), so the resume is a `switch_session` right after
     * attach — the same command the session picker sends, which also lets a
     * `session_before_switch` extension veto it. Behind
     * `app.sessions.resumeLast` because "opening the app starts a new session" is
     * a deliberate default that a fix must not silently flip (pi's own default too:
     * `createSessionManager` returns `SessionManager.create` unless `-c`/`--resume`
     * is passed, `main.ts:426-443`).
     *
     * The *selection* is pi's, not ours: [PiSessionStore.mostRecentForResume] is
     * `findMostRecentSession(sessionDir, cwd)` (`session-manager.ts:636-653`) — the
     * function `-c` itself uses (`:1589-1598`). It is deliberately not
     * `list(limit = 1)`: the picker sorts by "last message activity"
     * (`:1675`) while `-c` sorts by file mtime and filters the header's `cwd`
     * against the engine's, so the two can name different sessions. The cwd filter
     * is what keeps the chat from resuming a session that the guest terminal wrote
     * under `/root` (`PtyLauncher.kt:321` puts those in the same directory).
     *
     * Every way this can end is named: a failure to read the directory is an error
     * notice, "there are sessions but none for this workspace" is a warning, and
     * `switchSession` reports pi's own rejection (a `session_before_switch` veto, a
     * session whose recorded working directory no longer exists — `session-cwd.ts:54-59`
     * throws `MissingSessionCwdError`, which `rpc-mode.ts:605-611` turns into a
     * failed response).
     */
    private suspend fun maybeResumeLastSession() {
        if (resumeAttempted) return
        resumeAttempted = true
        val enabled = runCatching { settingsStore.readBoolean("app.sessions.resumeLast") }.getOrNull() ?: false
        if (!enabled) return
        val recent = runCatching { sessionStore.mostRecentForResume(guestWorkspace()) }
            .getOrElse { error ->
                fail("读取会话目录失败：${error.message ?: error::class.simpleName}")
                return
            }
        if (recent == null) {
            // pi's `-c` answers this by starting a new session, silently, and so does
            // the app. There is nothing here the user could act on, and a snackbar at
            // every launch for a state that did not change is exactly the
            // bottom-of-screen noise this app should not have.
            return
        }
        val current = _state.value.meta.sessionFile?.substringAfterLast('/')
        if (current != null && recent.file.name == current) return
        switchSession(recent)
    }

    /**
     * Republish the engine's projection into [UiState].
     *
     * Two rendering-review defects are fixed here, both in the app's own layer:
     *
     *  - **F2**: a tool card must not keep claiming "运行中" after the turn it
     *    belonged to has ended. pi closes every pending tool with
     *    `updateResult({isError:true})` when a turn stops for `aborted`/`error`
     *    (`interactive-mode.ts:3294-3302`), and the reducer now does the same on
     *    `message_end` (the `PiEvent.MessageEnd` arm of `TranscriptReducer.onEvent`
     *    calls `TranscriptReducer.failTurn`),
     *    so what remains uncovered is the path pi never models: **the engine was
     *    killed or restarted mid-turn**, where no `message_end` ever arrives. The
     *    pending rows are therefore closed here, and the card says the turn ended
     *    without a result instead of looking like a hang. Scope, exactly: only a
     *    row still at `ToolStatus.Pending` is touched — `status` becomes
     *    `ToolStatus.Error` / `isError = true`, and `output` is filled **only when
     *    it is empty** (`ifEmpty`), so a result the reducer already wrote is never
     *    replaced.
     *  - **F7**: the engine publishes the rows that actually moved
     *    (`PiEngineSession.TranscriptPublication.changedIndices`, an identity diff
     *    it already has to compute), so this function updates those indices instead
     *    of copying the whole list and diffing it here. The old check — "scan every
     *    row, skip the copy when none moved" — was an O(n) scan per streamed delta,
     *    which is what F7/RR-P7 asked to remove.
     *
     * ## Adopting wholesale, and why four cases do
     *
     * `changedIndices` describes the engine's *previous* snapshot, so it is only
     * usable while this consumer is exactly one publication behind holding those
     * same rows ([appliedRevision] is that bookkeeping):
     *
     *  1. [PiEngineSession.TranscriptPublication.replaced] — the list was rebuilt
     *     (`seedHistory`) and indices mean nothing;
     *  2. `revision != pub.revision - 1` — a `StateFlow` conflates and the engine
     *     publishes from its own reader thread, so a gap is normal and the only
     *     safe move is to adopt the rows again;
     *  3. the streaming flag flipped — the F2 projection below rewrites *every*
     *     pending tool card, which no per-index list can describe;
     *  4. this list is shorter than `pub.rows` — it is behind, and the missing
     *     tail would otherwise be dropped.
     *
     * **Work is decided by `changedIndices`, never by `change`.** F8's throttled
     * `tool_execution_update` mutates its row and answers `TranscriptChange.None`
     * on purpose (the throttle branch of `TranscriptReducer.onToolUpdate`), so a
     * `null`-looking change can
     * still carry a row: branching on the change kind would freeze that row's
     * streamed output forever.
     *
     * When there is no row work at all, the list is left alone but [UiState] is
     * still rewritten: the revision always advances, so a publication that carried
     * only `usage` or `thinkingLevel` still wakes its readers (that wake-up is what
     * a future usage row depends on). `StateFlow` drops an assignment that is
     * `equals` to the current value, so this costs a comparison, not a
     * recomposition, when nothing moved.
     *
     * No turn-outcome row lives here any more: the reducer appends it from
     * `stopReason` itself (`TranscriptReducer.failTurn`), and every event publishes
     * except the tool updates F8 throttles away (`PiEngineSession.foldEvent`), so
     * keeping a second copy in the ViewModel
     * would have rendered the same failure twice, with different wording.
     */
    private fun syncTranscript(engine: PiEngineSession, pub: PiEngineSession.TranscriptPublication) {
        val previous = _state.value
        // An interrupted turn: a tool still pending can never receive its result
        // now.
        val interrupted = !pub.streaming && pub.rows.any { it is ToolCall && it.status == ToolStatus.Pending }
        val adopt = pub.replaced ||
            appliedRevision != pub.revision - 1 ||
            previous.streaming != pub.streaming ||
            previous.transcript.size < pub.rows.size

        val transcript = when {
            // `projectRow` is the identity unless `interrupted`, and adopting the
            // engine's snapshot as-is is safe because `rows` is documented
            // immutable and nothing below mutates a list in place (`applyChanged`
            // copies). It saves one N-element copy per replay, session switch, fork,
            // clone or revision gap — the adopt path is the common one.
            adopt -> if (interrupted) pub.rows.map { row -> projectRow(row, true) } else pub.rows

            pub.changedIndices.isNotEmpty() -> applyChanged(
                current = previous.transcript,
                rows = pub.rows,
                changedIndices = pub.changedIndices,
                interrupted = interrupted,
            )

            else -> previous.transcript
        }

        // The marker only moves once the list above is built from this revision:
        // if anything between here and the assignment could throw, the next
        // publication would still be treated as a gap and adopt wholesale.
        _state.value = _state.value.copy(
            transcript = transcript,
            revision = pub.revision,
            streaming = pub.streaming,
            // The latest assistant usage, published so the UI can read it.
            //
            // It has **no reader in the transcript any more**: the status row's
            // `latestUsage` parameter — which this comment used to name — was deleted
            // when the row was re-laid out to v2's own readings, and the row itself is
            // now folded into the composer's context ring and the 会话信息 sheet. Its
            // three fields (`input` / `cacheRead` / `cacheWrite`) are pi's inputs to
            // the cache-hit rate (`components/footer.ts:95-98`: `cacheRead ÷ (input +
            // cacheRead + cacheWrite)`), which is what the sheet's 命中率 row reads.
            // The reducer is still its writer (`TranscriptReducer.lastUsage`).
            lastUsage = engine.transcript.lastUsage,
            // The turn's own accounting, on the same publication (see `turnUsage`).
            turnUsage = engine.transcript.turnUsage,
        )
        appliedRevision = pub.revision
        // `streaming` moved, which is one of the two inputs to "does the CPU have to
        // stay awake" (`reportWakeLockNeed`). Change-gated there, so this costs one
        // field comparison per publication — not a notification per streamed token.
        reportWakeLockNeed()
    }

    /**
     * The F2 projection for one row: a tool card the engine never closed (the
     * process died mid-turn) is reported as interrupted rather than left saying
     * "运行中". See [syncTranscript].
     */
    private fun projectRow(item: TranscriptItem, interrupted: Boolean): TranscriptItem =
        if (interrupted && item is ToolCall && item.status == ToolStatus.Pending) {
            item.copy(
                status = ToolStatus.Error,
                isError = true,
                output = item.output.ifEmpty { "回合已结束，未收到工具结果（被停止或出错）" },
            )
        } else {
            item
        }

    /**
     * Apply [changedIndices] to [current], projecting each row first.
     *
     * The engine's indices are ascending and always valid in [rows]
     * (`PiEngineSession.diffIndices` diffs against its own previous snapshot): an
     * index below the consumer's length is a replacement, one at or past it is an
     * append — which is why the list is grown rather than indexed blindly.
     */
    private fun applyChanged(
        current: List<TranscriptItem>,
        rows: List<TranscriptItem>,
        changedIndices: List<Int>,
        interrupted: Boolean,
    ): List<TranscriptItem> {
        val next = current.toMutableList()
        for (index in changedIndices) {
            val row = projectRow(rows[index], interrupted)
            if (index < next.size) next[index] = row else next.add(row)
        }
        return next
    }

    private fun onEvent(event: PiEvent) {
        when (event) {
            is PiEvent.QueueUpdate -> _state.value = _state.value.copy(
                queueSteering = event.steering.size,
                queueFollowUp = event.followUp.size,
            )

            // The window in which pi refuses a plain `prompt` (`:1192-1196`). Kept
            // here rather than read off the transcript reducer (which draws the
            // compaction card) because the *send* path is what needs it, and the
            // reconciliation in [refreshState] is what heals a dropped event.
            is PiEvent.CompactionStart -> setCompacting(true)
            is PiEvent.CompactionEnd -> setCompacting(false)

            // A turn that ends for a reason other than "stop"/"toolUse" is not a
            // finished answer, and pi says so under the partial text
            // (`components/assistant-message.ts:182-200`). The reducer renders that
            // row itself from `stopReason` now (`TranscriptReducer.failTurn`), and
            // every event publishes — except the `tool_execution_update` chunks F8
            // throttles away (`PiEngineSession.foldEvent`), whose rows the reducer
            // already holds — so nothing is projected here. This branch exists only
            // so the decision is visible where the old duplicate row used to be
            // built.
            is PiEvent.MessageEnd -> Unit

            // An extension that throws is otherwise invisible: pi reports it as
            // this event and nothing else, so a broken extension looks exactly
            // like an extension that chose to do nothing. The transcript reducer
            // already turns it into an `ErrorText` row (rpc/Transcript.kt), which
            // is only visible on the Chat destination — hence the snackbar too.
            // Both name the extension from `extensionPath` so a failure is
            // attributable; the reducer and this notice share the wording.
            is PiEvent.ExtensionError -> {
                _state.value = _state.value.copy(lastError = event.message)
                pushNotice(
                    message = extensionErrorHeadline(event.extensionPath) + "：" +
                        (event.message?.takeIf { it.isNotBlank() } ?: "pi 没有给出详情"),
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
            // (`PiEngineSession.foldEvent` folds it), and it bumps its revision for
            // every event it publishes, so the projection below republishes on its
            // own. Projecting a second time from this branch
            // (F5 in `docs/rendering-review.md`) would render every such entry
            // twice the moment the reducer gains a case for `custom` entries.
            is PiEvent.EntryAppended -> Unit

            // pi streams a running `bash` command as deltas and emits nothing else
            // until the response; accumulate here so the panel grows live.
            //
            // **The accumulation is a builder and the publication is throttled**, for the
            // two costs that made a chatty command (`yes`, a build log) stall the frame
            // thread:
            //
            //  - `current.output + delta` rebuilt the whole accumulated string per chunk,
            //    which is quadratic in the output's length: measured on a desktop JVM,
            //    ~2.4 s of CPU to accumulate 1 MiB from 200-char chunks. A builder appends
            //    in place and only `toString()`s at publication time.
            //  - every chunk published a new `UiState`, so the panel's single `Text` was
            //    re-laid out over the whole output per chunk. `TranscriptReducer` already
            //    has this exact rule for tool output (F8,
            //    `rpc/.../Transcript.kt:811` `TOOL_UPDATE_THROTTLE_MS`); bash had none.
            //
            // Nothing is lost by waiting: the deltas all land in the builder, and the
            // `bash` response replaces the whole output when the command ends
            // (`runBash`), so the tail cannot be dropped by a coalesced publication.
            //
            // The builder is also **bounded at the tail**, at the same number pi truncates
            // its own panel and its `bash` response at ([BASH_OUTPUT_MAX_CHARS],
            // `bash-execution.js:93-98`). Without that, a long-running command
            // (`!yes`, a build log) grew a `String` and a `Text` without any ceiling at
            // all; with it the panel shows the same window pi would, and nothing is
            // hidden about it — a trim sets `truncated`, which is the flag `BashPanel`
            // already turns into its 「输出被截断」 sentence.
            is PiEvent.BashExecutionUpdate -> {
                val delta = event.delta.orEmpty()
                val current = _state.value.bash ?: return
                if (delta.isEmpty()) return
                val trimmed = appendTailBounded(bashStream, delta, BASH_OUTPUT_MAX_CHARS)
                if (trimmed) bashStreamTrimmed = true
                val now = SystemClock.elapsedRealtime()
                if (lastBashPublishAt != 0L && now - lastBashPublishAt < BASH_UPDATE_THROTTLE_MS) {
                    return
                }
                lastBashPublishAt = now
                _state.value = _state.value.copy(
                    bash = current.copy(
                        output = bashStream.toString(),
                        // Sticky for this run: once the front has been dropped the panel
                        // is showing a window, and a later publication that happens not to
                        // trim must not take the sentence away again. The `bash` response
                        // replaces the whole run at the end, so pi's own answer (and its
                        // own `fullOutputPath`) wins then.
                        truncated = current.truncated || bashStreamTrimmed,
                    ),
                )
            }

            // The only ground truth for "the model changed behind our back":
            // `model_select` is emitted to extensions only, never to session
            // listeners (`agent-session.ts:1659-1670`, audit §5.3). Commands are
            // re-read here too: an extension can register one at any time.
            is PiEvent.AgentSettled -> {
                viewModelScope.launch {
                    refreshState()
                    refreshCommands()
                    // pi recomputes the footer's totals and context percentage on
                    // every render (`components/footer.ts:106-111`). The app's
                    // source for the same figures is `get_session_stats`, whose
                    // `contextUsage` is literally `getContextUsage()`
                    // (`core/agent-session.ts:3407`, the call the footer makes at
                    // `footer.ts:108`), so it is re-read whenever a run settles —
                    // F10.
                    refreshStats()
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
                message = "扩展发来一个没有编号的「${request.method}」对话框，无法回复；" +
                    "这个扩展的弹窗在当前引擎上无法使用。",
                tone = Notice.Tone.Warning,
            )
            return
        }

        val dialog = ExtensionDialog(
            id = request.uiId,
            method = method,
            // Labels keep their bytes: an extension colours them with
            // `ctx.ui.theme.fg(...)`, whose SGR bytes are the only colour channel
            // the wire has (`rpc-types.ts:246-281` carries one plain string each).
            // The dialog host turns them back into colour with `chromeSpans` +
            // `PiPalette.tokenColorFor`; stripping here would destroy the colour
            // before anything could paint it.
            title = request.title.orEmpty(),
            message = request.message,
            options = request.options,
            placeholder = request.placeholder,
            // `docs/rpc.md`: `editor` carries its initial content in `prefill`.
            // A value, not a label: the app hands it back on confirm, and it is
            // drawn in a `BasicTextField`, which takes one plain string. See
            // `ExtField`'s KDoc — no stripping and no painting, both deliberate.
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
                // Chrome, so the message keeps the extension's escapes for the
                // snackbar to paint (`ExtensionUiHost`). `chromeText` would be the
                // wrong call: it removes the colour rather than rendering it.
                message = request.message.orEmpty(),
                // rpc.md: notifyType is info | warning | error, default info.
                tone = noticeToneOf(request.notifyType),
            )

            // Collected but not drawn: the status row was deleted by adjudication
            // D-3. See setExtensionStatus for why the data is kept anyway.
            "setStatus" -> setExtensionStatus(request.statusKey, request.statusText)

            "setWidget" -> setExtensionWidget(
                key = request.widgetKey,
                lines = request.widgetLines,
                placement = request.widgetPlacement,
            )

            "setTitle" -> _state.value = _state.value.copy(
                // The one chrome surface that *is* stripped, because it has no
                // span host: `windowTitle` becomes the Chat AppBar's `Text`, which
                // lives in `ChatScreen.kt` and takes one string. Stripped before
                // the blank check, so a title that is nothing but escapes falls
                // back to the screen's own name instead of occupying the bar
                // invisibly.
                windowTitle = request.title?.let { chromeText(it) }?.takeIf { it.isNotBlank() },
            )

            "set_editor_text" -> request.text?.let { text ->
                // Last write wins, mirrored into the composer on the next frame.
                // `windowTitle`/`set_editor_text` with a missing field are ignored
                // rather than treated as "clear": the parser cannot tell an absent
                // `text` from an empty one, and guessing "clear" would silently
                // wipe whatever the user had typed.
                //
                // Neither stripped nor painted, for the same reason as `editor`'s
                // prefill: this text is a draft the user may send to the model.
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
     *
     * **Nothing renders this today.** The user's adjudication deleted the status
     * row (`design/ui-refactor/11-designer-adjudication.md` D-3 — v2's dialogue
     * shell draws no extension status line), so the `ExtensionStatus` list has no
     * composable any more. The data is kept deliberately rather than left behind:
     * it is the only copy of what an extension asked to show, and the adjudication
     * names the 「会话与队列」 sheet as where it could reappear — which is a call
     * site, not a re-plumb.
     *
     * [text] keeps its escapes instead of being stripped here: whoever draws it
     * next has to be able to paint the colour (`chromeSpans` +
     * `tokenColorFor`), and the state should stay equal to the wire payload.
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
     *
     * [lines] keep their escapes, and are neither capped nor trimmed here: the
     * row budget and the colour both belong to the drawing site
     * (`ExtensionWidgetStack`), so this state stays equal to the wire payload.
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

    /**
     * The one place a wire response is built, and therefore the one place that
     * decides what an answer *is*.
     *
     * A `select` answer is sent **plain** (`chromeText`): the option label is
     * painted with whatever colour the extension wrapped it in, but the answer
     * must be the label, not the escape bytes that painted it. `input` and
     * `editor` values are *not* stripped — those are text the user typed or kept
     * from a `prefill`, and editing them here would change the user's own answer
     * rather than decline to paint a label.
     */
    private fun buildAnswer(dialog: ExtensionDialog, answer: ExtensionAnswer): JsonObject? = when (answer) {
        is ExtensionAnswer.Value ->
            if (dialog.method == ExtensionDialogMethod.Confirm) {
                null
            } else {
                val value = if (dialog.method == ExtensionDialogMethod.Select) {
                    chromeText(answer.value)
                } else {
                    answer.value
                }
                PiCommands.extensionUiValue(dialog.id, value)
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
     * The tick publishes once per *displayed second*, and it now also **sleeps** to
     * the next displayed second ([PiEngineLifecyclePolicy.nextCountdownDelayMs]):
     * the dialog shows whole seconds (pi's TUI shows "Title (5s)"), so a fixed
     * 200 ms poll wrote the same number five times and woke the process five times
     * for each visible change. pi's own countdown for the same thing is
     * `setInterval(…, 1000)` (`modes/interactive/components/countdown-timer.ts:21`,
     * disposed at the zero crossing `:26-29`), i.e. one wake-up per displayed
     * second — which is what this now costs, with the deadline still hit exactly
     * (the loop breaks on `remaining <= 0`, and the sleeps sum to the deadline).
     *
     * pi runs its own timer for the same deadline (`rpc-mode.ts:115-120`
     * `createDialogPromise` → `setTimeout` → resolve with the default), so this
     * countdown is presentation; the answer at zero is belt-and-braces, not the
     * mechanism pi relies on. That is also why a coarse tick is acceptable here: a
     * late answer is dropped by pi (the request is no longer pending), and only
     * `editor` has no agent-side timer at all (`rpc-mode.ts:254-271`) — but an
     * `editor` request carries no `timeout`, so it never reaches this loop.
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
                delay(PiEngineLifecyclePolicy.nextCountdownDelayMs(remaining))
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
     * Called when the engine dies (`Stopped`/`Failed`), when a new session
     * replaces the old one (`attach`), and at ViewModel teardown (`onCleared`).
     *
     * **Load-bearing — do not remove as redundant.** A request can be queued
     * while no host is composed (app backgrounded, teardown mid-request).
     * `select`/`confirm`/`input` are also resolved by pi's own timer, but
     * `editor` has **no agent-side timer at all**
     * (`packages/coding-agent/src/modes/rpc/rpc-mode.ts:254-271`: an un-timed
     * promise with no timeout options in its signature), so an unanswered
     * `editor` hangs pi forever. Answering on teardown is the only thing between
     * "the UI went away" and "pi is wedged". The write may be undeliverable
     * (a dead pipe), which is fine — nobody is blocked then.
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
            // The eviction rule lives in `ui/extension/NoticeQueue.kt` and is pinned by a
            // bare-JVM harness: an overflowing queue drops the oldest **pending** notice,
            // never the head — the head is the one `ExtensionUiHost` is showing, and
            // `takeLast` used to delete it out from under the reader.
            notices = trimNoticeQueue(_state.value.notices + notice, MAX_PENDING_NOTICES),
        )
    }

    /**
     * The two "nothing to compact" reasons in the user's language.
     *
     * pi's own words stay in parentheses: the sentence has to be readable in a
     * Chinese UI, and the original has to survive for a bug report
     * (`core/agent-session.ts:1985-1992`). Anything else comes back untouched —
     * a reason this app has never seen is more valuable verbatim than guessed at.
     */
    private fun compactReasonInChinese(reason: String): String = when {
        reason.contains("Nothing to compact", ignoreCase = true) ->
            "这个会话还太小，没有可压缩的内容（pi：$reason）"

        reason.contains("Already compacted", ignoreCase = true) ->
            "这个会话已经压缩过了，没有新的内容可压（pi：$reason）"

        else -> reason
    }

    /**
     * Track pi's compaction window.
     *
     * Change-gated so a duplicated `compaction_start` does not republish state, and
     * so the two sources ([PiEvent.CompactionStart]/[PiEvent.CompactionEnd] and the
     * `get_state` reconciliation in [refreshState]) can both write it.
     */
    private fun setCompacting(value: Boolean) {
        if (_state.value.meta.compacting == value) return
        _state.value = _state.value.copy(meta = _state.value.meta.copy(compacting = value))
    }

    /** A user-visible failure: a snackbar plus the AppBar's error line. */
    private fun fail(message: String) {
        _state.value = _state.value.copy(lastError = message)
        pushNotice(message, Notice.Tone.Error)
    }

    /**
     * What a command does when there is no engine to run it on.
     *
     * This used to be "nothing", and that silence was a lie with two faces: an
     * action the user just tapped appeared to do nothing, and a sheet that had
     * opened on a read (会话信息 → `get_session_stats`, 分支 → `get_fork_messages`)
     * kept its own wording — 「正在读取…」, 「没有可分叉的用户消息。」 — about data that
     * was never read. Both are statements about the session, and both were false.
     */
    private enum class NoEngine {
        /**
         * The user asked for this just now (a tap, or a screen that opened on this
         * data). The truthful sentence goes through [fail], i.e. a snackbar: it is
         * the only channel this app has for "that did not happen".
         */
        Notify,

        /**
         * A background re-read nobody asked for — the state refresh after boot, a
         * settle or a session swap. The engine's own state already says it is gone
         * (`引擎已退出` in the AppBar, the boot screen), and one snackbar per
         * internal refresh would be noise rather than information. The read still
         * does **not** fabricate a value: it simply leaves the previous one, which
         * is why every caller of this kind is a refresh rather than a first read.
         */
        Quiet,
    }

    /**
     * Run one RPC call, surfacing failure the way a user can act on it.
     *
     * [label] doubles as the progress line and as the "who owns busy" token, so a
     * finished call cannot clear a newer call's label. `CancellationException` is
     * rethrown: it means the ViewModel is going away, not that the command
     * failed.
     *
     * With no engine attached, [noEngine] decides between a snackbar and silence —
     * see [NoEngine]. An identical message is not stacked twice: several refreshes
     * can run in the same frame when an engine dies, and four copies of one
     * sentence read as four failures.
     */
    private fun call(
        label: String,
        noEngine: NoEngine = NoEngine.Notify,
        /**
         * Optional translation of a failure into something the user can act on.
         *
         * The default is [Throwable.message], which for a [PiRpcException] is pi's
         * own English sentence prefixed with the command name — correct, but it
         * names the *symptom* and not one thing to do next. A command whose
         * failures have a small, knowable set of causes passes a mapper here
         * instead; pi's own text is kept inside the sentence, never replaced.
         */
        errorText: ((Throwable) -> String)? = null,
        block: suspend (PiEngineApi) -> Unit,
    ) {
        val api = this.api
        if (api == null) {
            if (noEngine == NoEngine.Notify) {
                val message = "引擎未就绪：pi 现在不在运行，$label 没有执行。"
                if (_state.value.notices.lastOrNull()?.message != message) fail(message)
            }
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = label)
            try {
                block(api)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(
                    errorText?.invoke(error)
                        ?: error.message?.takeIf { it.isNotBlank() }
                        ?: "$label 失败",
                )
            } finally {
                if (_state.value.busy == label) _state.value = _state.value.copy(busy = null)
            }
        }
    }

    // ----------------------------------------------------------- session facts

    /**
     * Rebuild the transcript from the session's own records, **newest first**.
     *
     * ## Why the file and not `get_entries`
     *
     * `get_entries` is the documented re-attach path (`docs/rpc.md` §get_entries),
     * but it has no pagination: it answers with the **whole session inside one
     * JSONL record** and its `since` cursor only moves forwards
     * (`modes/rpc/rpc-mode.ts:638-648`). Opening a conversation that way therefore
     * cost O(entire history) on the frame path — and past
     * `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` it cost nothing at all, because the
     * record was discarded, which is the user's "两张图片就进不去聊天历史".
     *
     * The session file is the same truth pi reads: `SessionManager.open` loads this
     * exact file and `getEntries()` returns its non-header entries in file order
     * (`core/session-manager.ts:659-663`, `:1315-1317`). [SessionFileReader] reads
     * the **tail** of it inside a fixed character budget, so the first paint is
     * bounded by the window and not by the session. Everything older is loaded by
     * [expandEarlierHistory] when the user scrolls up, and the `session-replay-cost`
     * harness asserts the windowed read reproduces the whole-file read exactly
     * (order, ids, types, branches, compaction and `custom` entries).
     *
     * ## The fallback is not dead code
     *
     * A file this reader cannot use — not a session, unreadable, or a line past
     * [SessionFileReader.DEFAULT_MAX_LINE_CHARS] — falls back to the old
     * whole-session `get_entries`. That path still exists for the tree overlay and
     * for export, so it is exercised either way; and a session pi holds in memory
     * before writing a header is answered by `get_entries` and not by disk.
     *
     * ## Failure is reported, not swallowed
     *
     * Both failure paths used to `return` silently, which is what "tapping an old
     * conversation does nothing" looked like from the user's seat
     * (`docs/hang-and-crash-review.md` §A1). The transcript is still not cleared on
     * failure: a rebuild that cannot repopulate its rows must not destroy them.
     */
    private suspend fun replayHistory(engine: PiEngineSession) {
        // Cleared first: the replay either replaces it or fails, and a stale list
        // would let the next scroll-up prepend one session's entries onto another's.
        loadedHistory = emptyList()
        loadedHistoryChars = 0L
        val file = resolveSessionFile()
        if (file != null) {
            // The read and the retained-size accounting are one IO hop, not two: the
            // accounting serialises every entry to measure it ([entryChars]) and it is
            // the same decision ("are we about to retain this window?") as the read
            // itself. It also keeps the count and the rows it describes in one
            // publication: a scroll-up on the next frame must not read a stale 0 and
            // take an extra window.
            val (window, windowChars) = withContext(Dispatchers.IO) {
                val read = SessionFileReader.readTail(file, HISTORY_WINDOW_CHARS, HISTORY_WINDOW_ENTRIES)
                val chars =
                    if (read != null && read.entries.isNotEmpty() && read.complete) {
                        retainedChars(read.entries)
                    } else {
                        0L
                    }
                read to chars
            }
            if (window != null && window.entries.isNotEmpty() && window.complete) {
                engine.seedHistory(window.entries)
                loadedHistory = window.entries
                loadedHistoryChars = windowChars
                _state.value = _state.value.copy(
                    history = HistoryCursor(
                        startOffset = window.startOffset,
                        reachedStart = window.reachedStart,
                    ),
                )
                syncTranscript(engine, engine.publication.value)
                return
            }
        }
        replayHistoryOverRpc(engine)
    }

    /**
     * The host file pi is on, or null when it cannot be resolved *safely*.
     *
     * Two independent identifications, because a wrong file here would show the
     * previous conversation in the new session's place:
     *
     *  1. [UiState.meta]'s `sessionFile` must be pi's own path for the session pi is
     *     on **right now**, so it is only trusted once the header's `id` matches the
     *     `sessionId` from the same `get_state`. `switch_session` / `new_session` /
     *     `fork` / `clone` rebind in place and emit no replay, so between the command
     *     and the next `get_state` the meta still describes the session the user just
     *     left — reading its file would replay the wrong history, which is worse than
     *     the slow open this path replaces.
     *  2. [SessionHints.file] is set by the one caller that already knows the answer
     *     from disk ([switchSession] holds the `PiSessionStore.Summary` the user
     *     tapped, and [importSession] the file it just copied), so the common case —
     *     opening a conversation from the list — needs no round trip at all.
     */
    private fun resolveSessionFile(): File? {
        val meta = _state.value.meta
        val hinted = SessionHints.file
        if (hinted != null && hinted.isFile) {
            return hinted
        }
        val file = hostSessionFile(meta.sessionFile) ?: return null
        val sessionId = meta.sessionId
        if (sessionId != null) {
            val header = SessionFileReader.readHeader(file) ?: return null
            val headerId = (header["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (headerId != null && headerId != sessionId) return null
        }
        return file
    }

    /** The one file a session command already knows, until the meta catches up. */
    private object SessionHints {
        var file: File? = null
    }

    /**
     * The whole-session `get_entries` replay — the fallback, and the path the tree
     * overlay and export still take.
     *
     * Kept in one place so the two callers cannot drift, and so the reason it is now
     * the *second* choice is written down next to it.
     */
    private suspend fun replayHistoryOverRpc(engine: PiEngineSession) {
        val response = runCatching {
            engine.request({ PiCommands.getEntries(it) })
        }.getOrNull() ?: run {
            fail("引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。")
            return
        }
        if (!response.success) {
            // A brand-new in-memory session answers with an empty page, not an
            // error (that is the success branch below); a real error means the
            // transcript cannot be rebuilt — and `response.error` already carries
            // the engine's own reason, e.g. the oversized-record sentence.
            fail("读取会话内容失败：${response.error ?: "原因未知"}。")
            return
        }
        // `seedHistory`, not `transcript.seedFromHistory`: the reducer path mutates
        // the rows without publishing, and a publication is what carries the new
        // list (and its `replaced` flag) to this consumer. It **suspends**: the
        // projection runs on `Dispatchers.Default` and takes over the engine's
        // reducer under its transcript lock, so this await is where the frame thread
        // is released.
        val entries = PiResponses.entries(response)
        // This path is a **whole session** in one answer (`get_entries` has no
        // pagination), so the retained-size accounting is the largest of the three
        // callers and the one that used to be worst on the frame thread. See
        // [entryChars] for the measurement; the sum runs on IO for the same reason the
        // read above does.
        val chars = withContext(Dispatchers.IO) { retainedChars(entries) }
        engine.seedHistory(entries)
        loadedHistory = entries
        loadedHistoryChars = chars
        // A whole-session replay has nothing above it, so there is nothing for the
        // scroll path to fetch — the cursor says so rather than staying null, which
        // the UI would have to read as "unknown".
        _state.value = _state.value.copy(
            history = HistoryCursor(startOffset = 0L, reachedStart = true),
        )
        // The collector would wake on its own, but not until this coroutine
        // suspends; syncing here makes the rebuilt session visible in the same
        // frame.
        syncTranscript(engine, engine.publication.value)
    }

    /**
     * Load the window of history that sits **above** what is on screen.
     *
     * Called when the transcript is scrolled near its top (the screen owns the
     * gesture; this owns the read). Each call moves [HistoryCursor.startOffset]
     * backwards by a fixed budget, so one step costs a constant and reaching the
     * top of a 40 000-turn session costs that constant times the number of windows
     * the user actually passed — never one unbounded read.
     *
     * ## Why a rebuild rather than a prepend
     *
     * The reducer's projection is order- and prefix-sensitive: day separators
     * depend on the preceding timestamp, `turnUsage` is "assistant entries after the
     * last `user` entry", and a `compaction`/`branch_summary` is resolved against
     * what came before it. Appending a *newer* segment is safe; prepending an
     * *older* one is not, because the older entries must be folded in before the
     * rows they contextualise. [PiEngineSession.seedHistory] already does exactly
     * that (reset, then fold the list in order), so the expansion hands it the
     * concatenation: `newlyRead + loadedHistory`. That is O(loaded), which is why
     * the load is gated on the engine being idle — during a turn the live rows in
     * the reducer are not reproducible from a file read, and dropping them to add
     * older history would remove something the user is watching.
     */
    fun expandEarlierHistory() {
        val engine = session ?: return
        val cursor = _state.value.history ?: return
        if (cursor.reachedStart || cursor.loading) return
        if (engine.transcript.streaming) return
        if (_state.value.engine != PiEngineSession.EngineState.Ready) return
        val file = resolveSessionFile() ?: return
        _state.value = _state.value.copy(history = cursor.copy(loading = true))
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // The retention cap: a user who keeps scrolling up through a session
                // of 3 MB image entries would otherwise accumulate every window they
                // passed, which is the unbounded-memory failure this whole change
                // exists to remove. Past the cap the expansion simply stops —
                // `reachedStart` stays false, so the row above keeps offering the next
                // window and the user stays in control of what is held.
                if (loadedHistoryChars >= HISTORY_RETAINED_CHARS) {
                    _state.value = _state.value.copy(
                        history = cursor.copy(loading = false),
                    )
                    return@withContext null
                }
                SessionFileReader.readBefore(
                    file,
                    cursor.startOffset,
                    HISTORY_WINDOW_CHARS,
                    HISTORY_WINDOW_ENTRIES,
                )
            }
            // Nothing readable above: stop asking. This is not a failure to report —
            // the conversation is not damaged, it simply starts where the loaded
            // range starts (an unreadable header, a file that is not a session, or a
            // range with no complete line all mean the same thing here, and saying so
            // would be noise on a session that renders fine).
            //
            // **A withheld line is the exception, and it is not the same statement.**
            // `Window.complete = !range.droppedLine` (`SessionFileReader.kt:258`): false
            // means the range scanned a line it could not keep — one longer than
            // `HISTORY_WINDOW_CHARS`/`DEFAULT_MAX_LINE_CHARS` — so there *is* more above
            // it, and the reader's own KDoc says the next window starts past it
            // (`:242-257`), which is why continuing makes progress instead of looping.
            // Marking `reachedStart` there told the reader the whole conversation had been
            // loaded: `HistoryCursor.hasEarlier` went false, the row that offers the next
            // batch disappeared for the rest of the session, and the entries above that
            // line became unreachable. The sentence stays absent either way — a capped
            // line is not a failure to report — but the cursor must not claim the start
            // of the file. (The sentinel row therefore stays on screen after such a batch,
            // which is the correct state: there is still history above.)
            val moreAbove = loaded != null && !loaded.complete
            if (loaded == null || !loaded.complete || loaded.entries.isEmpty()) {
                _state.value = _state.value.copy(
                    history = _state.value.history?.copy(loading = false, reachedStart = !moreAbove),
                )
                return@launch
            }
            val combined = loaded.entries + loadedHistory
            engine.seedHistory(combined)
            loadedHistory = combined
            // The **increment**, not a re-sum over `combined`: this used to measure every
            // retained entry again for each batch, so the cost grew with how far the user
            // had scrolled — and the measure serialises each entry to take its length
            // ([entryChars]). `loadedHistory`'s own count is already in the field and
            // `combined` is exactly `loaded.entries` plus that list, so adding the new
            // window's count is the same number the old sum produced, at one window's
            // cost instead of all of them. It runs on IO for the same reason.
            loadedHistoryChars += withContext(Dispatchers.IO) { retainedChars(loaded.entries) }
            _state.value = _state.value.copy(
                history = HistoryCursor(
                    startOffset = loaded.startOffset,
                    reachedStart = loaded.reachedStart,
                ),
            )
            syncTranscript(engine, engine.publication.value)
        }
    }

    /**
     * Host path of a guest session path, or null.
     *
     * The inverse of [guestSessionPath], and the same mapping [sessionHeaderOf] and
     * `exportJsonl` already apply. The guards matter: `get_state`'s `sessionFile`
     * comes from pi, so a malformed or escaping value must not become an arbitrary
     * read — only a path under the guest's session directory that resolves inside
     * the host's is accepted, and only when it is a regular file.
     */
    private fun hostSessionFile(guestPath: String?): File? {
        val path = guestPath ?: return null
        val prefix = "${host.guestAgentDir}/sessions/"
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix)
        if (relative.isEmpty() || relative.contains("..")) return null
        val root = File(host.paths().agentDir, "sessions")
        val file = File(root, relative)
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        if (filePath != rootPath && !filePath.startsWith(rootPath + File.separator)) return null
        if (!file.isFile) return null
        return file
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
        call("读取会话状态", NoEngine.Quiet) { api ->
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
                    // `get_state` is the reconciliation for the event-tracked flag:
                    // if `compaction_end` was dropped, the next attach/settle puts
                    // this back to pi's own answer. `isCompacting` was parsed all
                    // along (`rpc/.../Responses.kt:91`, `:276`) and only the engine's
                    // teardown ever read it.
                    compacting = remote.isCompacting,
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
     *
     * Also reloaded when `enableSkillCommands` is written ([onSettingWritten]): the
     * filter that implements it lives in `piCommandPalette`, so the cached list has
     * to be rebuilt for the switch to be anything but decorative.
     */
    fun refreshCommands() {
        call("读取命令列表") { api ->
            // pi's own default is true (`settings-manager.ts:1165`).
            val skillCommands = settingsStore.readBoolean("enableSkillCommands") ?: true
            _state.value = _state.value.copy(
                commands = piCommandPalette(api.getCommands(), skillCommandsEnabled = skillCommands),
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
     * `get_tree` for the session tree overlay.
     *
     * Only the tree. The overlay's other tab is the raw entry log, and that is a
     * **whole-session** read either way — from the file now ([refreshEntries]), not
     * from `get_entries`. Keeping the two apart is what lets the branch view, which
     * is what the overlay opens on, paint as soon as pi answers without waiting on a
     * read proportional to the conversation.
     */
    fun refreshTree() {
        call("读取会话树") { api ->
            _state.value = _state.value.copy(tree = api.getTree())
        }
    }

    /**
     * The raw entry log, for the tree screen's 条目 tab — read from the **session
     * file**, streamed, and sanitised.
     *
     * This was the last whole-session `get_entries` in the app, and it was the one
     * that needed the framer's record cap raised to survive a session with pictures
     * in it. It does not, now: [SessionFileReader.readEntries] walks the file line by
     * line and reduces each entry to the small part this screen reads (identity,
     * `type`, the printed fields, and a bounded text preview), so a session whose
     * file is 40 MB of inline images produces an entry list of a few hundred
     * kilobytes and never holds an image in memory.
     *
     * That is also why this is **not** the transcript's window: the tab is a list of
     * the whole session (that is what it is for — including the `label`,
     * `session_info` and `custom` entries the reducer ignores), so a window of it
     * would present a partial log as the whole one. All of it is read; none of the
     * bulk is kept.
     *
     * `get_entries` remains the fallback for the case the file cannot answer — a
     * session pi holds in memory before it has written a header — and it is
     * deliberately reached only then. When even that is unreadable the screen says so
     * through [fail] instead of showing an empty log, because an empty log and an
     * unreadable one look identical and only one of them is the truth.
     */
    fun refreshEntries() {
        val file = resolveSessionFile()
        if (file == null) {
            refreshEntriesOverRpc()
            return
        }
        call("读取会话条目", NoEngine.Quiet) {
            val parsed = withContext(Dispatchers.IO) {
                val out = ArrayList<SessionEntry>()
                val complete = SessionFileReader.readEntries(file) { raw ->
                    out += parseSessionEntry(raw)
                }
                if (complete) out else null
            }
            if (parsed == null) {
                // The scan hit [SessionFileReader.DEFAULT_MAX_SCAN_LINE_CHARS], so
                // this list is missing an entry — which for this screen is worse than
                // a slower read, because it presents a partial log as the whole one.
                // pi's own whole-session answer is the only other source; that path is
                // why `JsonlFramer`'s cap exists.
                refreshEntriesOverRpc()
                return@call
            }
            _state.value = _state.value.copy(entries = parsed)
        }
    }

    /** `get_entries` for the entry log — see [refreshEntries] for when this is used. */
    private fun refreshEntriesOverRpc() {
        call("读取会话条目", NoEngine.Quiet) { api ->
            _state.value = _state.value.copy(entries = api.getEntries().entries)
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
            PiProjectConfig.extensionsDir(defaultWorkspace()) to "项目",
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
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        // No engine yet: hold the call instead of dropping it ([pendingPrompts]).
        if (parkUntilAttached { send(text, images) }) return
        val engine = session ?: return
        // **Compacting: the one window in which `prompt` is not an option.** pi
        // throws there — `Cannot submit a prompt while compaction is in progress.
        // Wait for compaction to finish and retry.` (`core/agent-session.ts:1192-1196`,
        // checked after the extension-command branch and before the streaming
        // branch). The bare `steer`/`follow_up` commands have no such check: they go
        // through `_queueUserInput` (`:1388-1413`), which runs the extension `input`
        // handlers and the skill/template expansion and then pushes the text onto
        // pi's own `_steeringMessages`/`_followUpMessages`. So during a compaction
        // the message is **queued by pi** (visible in the queue row, which reads
        // `queue_update`), the submission does not fail, and the app needs no local
        // hold-and-flush of its own.
        //
        // Worst case if this flag is ever stale-true: the text lands in pi's
        // steering queue and is delivered at the next agent step. Slower, not lost,
        // not an error — which is why the trade is worth taking. (The flag is
        // reconciled from `get_state` in [refreshState], so a dropped
        // `compaction_end` heals at the next attach or settle.)
        if (_state.value.meta.compacting) {
            engine.echoUserPrompt(trimmed, images)
            engine.send(
                PiCommands.steer(
                    id = "steer-${System.nanoTime()}",
                    message = trimmed,
                    images = images,
                ),
            )
            syncTranscript(engine, engine.publication.value)
            return
        }
        // Otherwise: one entry point, two delivery choices — pi's own shape. Its TUI
        // submits every non-built-in message through `session.prompt(text, {
        // streamingBehavior: "steer" })` while a turn is running
        // (`interactive-mode.ts:3137-3143`) and through a plain `prompt` when the
        // agent is idle, so the app does the same: `streamingBehavior` only while
        // streaming, never on an idle engine.
        //
        // What `prompt` adds over the bare `steer` command: pi's own pre-flight —
        // the compaction check above, the model/credential validation and the rest
        // of `prompt`'s checks — and it is the path pi's TUI exercises, which is why
        // `docs/feature-gaps.md:96` recorded the old app as never sending
        // `streamingBehavior` on `prompt` ("used only through `follow_up`"). It is
        // **not** "the only path that runs the input handlers": `_queueUserInput`
        // runs those (`:1388-1413`) for the bare commands too.
        //
        // The optimistic echo is `PiEngineSession.prompt`'s own (`:763`), and it is
        // no longer done here: it publishes the row *and* registers it in the
        // reducer's pending-echo queue, which is what makes pi's later
        // `message_end(role="user")` a confirmation instead of a second bubble
        // (`rpc/.../Transcript.kt:883-897` records the echo, `:919-1010` matches
        // it). Echoing here as well would render the row twice and leave a stale
        // pending echo behind, which would then swallow the next unrelated message.
        engine.prompt(
            message = trimmed,
            images = images,
            streamingBehavior = if (engine.transcript.streaming) StreamingBehavior.Steer else null,
        )
        // Already published by `prompt`; reading it here is what makes the echoed
        // row visible without waiting for the collector's dispatch.
        syncTranscript(engine, engine.publication.value)
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
        // Same hold as [send]: a palette command picked in the first second of a
        // launch is remembered, not swallowed.
        if (parkUntilAttached { runPromptCommand(command, args) }) return
        val engine = session ?: return
        val text = if (args.isBlank()) command.invocation else "${command.invocation} ${args.trim()}"
        if (command.source == PiCommandSource.Extension) {
            // pi answers an extension command inside its own process and emits no
            // user message at all: `prompt()` calls `_tryExecuteExtensionCommand`
            // and returns on success (`agent-session.ts:1181-1190`, `:1331-1356`).
            // pi's TUI therefore shows only what the handler sends — usually a
            // `pi.sendMessage(...)`, which this app already renders from the
            // `role: "custom"` event. Echoing here would leave a bubble pi never
            // confirms, and that unconfirmed row would then be mistaken for the
            // confirmation of a later message.
            //
            // `send`, not `prompt`: only `PiEngineSession.prompt` echoes, and this
            // is the one `/` path that must not.
            engine.send(PiCommands.prompt("cmd-${System.nanoTime()}", text))
        } else {
            // Templates and skills are expanded inside pi into a real user message
            // (`agent-session.ts:1211-1216`), so the optimistic echo is confirmed by
            // that event and replaced by pi's own projection of it.
            //
            // Mid-turn the submission needs pi's `streamingBehavior`, exactly as
            // pi's own TUI sends it (`interactive-mode.ts:3137-3142`:
            // `session.prompt(text, { streamingBehavior: "steer" })` for every
            // non-built-in submit while streaming). Without it pi rejects the
            // `prompt` outright — "Agent is already processing. Specify
            // streamingBehavior ('steer' or 'followUp') to queue the message"
            // (`agent-session.ts:1211-1217`) — so selecting a template or a skill
            // from the palette during a turn raised that error instead of queueing,
            // while the same selection works in pi's TUI. `steer` is pi's own choice
            // for Enter; the follow-up chip is a separate gesture and still goes
            // through [sendFollowUp].
            engine.prompt(
                message = text,
                streamingBehavior = if (engine.transcript.streaming) StreamingBehavior.Steer else null,
            )
        }
        syncTranscript(engine, engine.publication.value)
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
     *
     * [images] travels the whole way because pi's follow-up carries them: the
     * TUI hands its editor attachments to `session.prompt(text, {
     * streamingBehavior: "followUp" })` (`interactive-mode.ts:4146`), which
     * reaches `_queueFollowUp(expandedText, currentImages)` (`agent-session.ts:1225-1226`).
     * F21's investigation in `docs/gap-disposition.md` §10 found that
     * `PiCommands.followUp` already had the parameter and this signature did not,
     * so the 后续 chip silently dropped an attachment the user had added — a
     * visible action with no effect, the same class of bug F19 removed elsewhere.
     */
    fun sendFollowUp(text: String, images: List<PiImage> = emptyList()) {
        if (parkUntilAttached { sendFollowUp(text, images) }) return
        val engine = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Compacting: [send] documents the window — pi's `prompt` throws
        // (`agent-session.ts:1192-1196`) and the bare `follow_up` command queues the
        // text instead (`_queueUserInput`, `:1388-1413`, no compaction check). Same
        // trade, same worst case.
        if (_state.value.meta.compacting) {
            engine.echoUserPrompt(trimmed, images)
            engine.send(
                PiCommands.followUp(
                    id = "follow-${System.nanoTime()}",
                    message = trimmed,
                    images = images,
                ),
            )
            syncTranscript(engine, engine.publication.value)
            return
        }
        // pi's `alt+enter` is the *same* submit with the other delivery choice:
        // `session.prompt(text, { streamingBehavior: "followUp" })`
        // (`interactive-mode.ts:4143-4150`), not the bare `follow_up` command — see
        // [send] for what the command form gives up (pi's pre-flight checks). The
        // echo is `PiEngineSession.prompt`'s, for the same single-render reason.
        //
        // `streamingBehavior` is only sent while a turn is running, because that is
        // the only case pi's TUI queues a follow-up in: with the agent idle
        // alt+enter is an ordinary submit (`interactive-mode.ts:4126-4155` is the
        // streaming half; the idle half goes through the normal submit path), and
        // this method's chip is only offered while streaming anyway.
        engine.prompt(
            message = trimmed,
            images = images,
            streamingBehavior = if (engine.transcript.streaming) StreamingBehavior.FollowUp else null,
        )
        syncTranscript(engine, engine.publication.value)
    }

    /** pi's Escape: abort, and hand the queued text back so the composer can restore it. */
    fun stop(onRestored: (List<String>) -> Unit = {}) {
        val engine = session ?: return
        viewModelScope.launch {
            val restored = runCatching { engine.stopAndDrainQueue() }.getOrDefault(emptyList())
            syncTranscript(engine, engine.publication.value)
            // viewModelScope already runs on the main dispatcher, so this is
            // called from the UI thread without needing Dispatchers.Main.
            onRestored(restored)
        }
    }

    /**
     * pi's `app.message.dequeue` (`alt+up`): take the queued messages back out of
     * pi and hand them to the composer **without aborting the turn**
     * (`interactive-mode.ts:4157-4164` → `restoreQueuedMessagesToEditor()` with no
     * options, `:4387-4406`).
     *
     * The difference from [stop] is exactly the missing `abort`, and it is pi's
     * whole point of the action: the current turn keeps running, only the queue
     * moves back into the editor. `clear_queue` is all-or-nothing
     * (`rpc-types.ts:26`), so "one message at a time" is not on offer here — nor in
     * pi, whose pending-messages list is read-only and whose hint restores them all.
     *
     * An empty answer is reported rather than silently ignored: it is what pi's
     * `handleDequeue` says too ("No queued messages to restore"), and it is the only
     * signal that the dequeue raced the turn's own consumption of the queue.
     */
    fun restoreQueue(onRestored: (List<String>) -> Unit = {}) {
        val engine = session ?: return
        viewModelScope.launch {
            val restored = runCatching { engine.drainQueue() }.getOrDefault(emptyList())
            syncTranscript(engine, engine.publication.value)
            if (restored.isEmpty()) {
                pushNotice("队列里没有待收回的消息", Notice.Tone.Warning)
            }
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
            val result = try {
                api.compact(customInstructions?.takeIf { it.isNotBlank() })
            } catch (error: PiRpcException) {
                // pi 用英文原文说明「没什么可压」的两种情形
                // (`core/agent-session.ts:1985-1992`)，原样弹出来就是中文界面里的一句
                // 英文。只映射这两条**已知**原因，其余 reason 一字不改地透出：这里不是
                // 一个把 pi 的话吞掉的翻译层，用户拿它去搜/去报错仍然要对得上。
                throw PiRpcException(error.command, compactReasonInChinese(error.reason))
            }
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

    // ---------------------------------------------------------------- mentions

    /**
     * Refresh the `@` mention list for [prefix], the mention token the composer is
     * typing (see `PiFileMentions.prefixOf`).
     *
     * pi resolves these candidates with `fd` in its own process
     * (`packages/tui/src/autocomplete.ts:289-311`); this app has no such process, so
     * the lookup is a guest command (see [PiMentionSource]) and therefore costs a
     * process. Three consequences, all of them the caller's contract:
     *
     *  - the composer debounces before calling this, so a burst of keystrokes is not
     *    a burst of proot spawns;
     *  - only the newest request publishes a result ([mentionRequestId]); an answer
     *    that arrives after the user typed on is dropped rather than replacing the
     *    list with one for a prefix that is no longer on screen;
     *  - [PiMentionSource] runs them one at a time, so a superseded request that is
     *    still queued is skipped instead of being executed.
     */
    fun requestMentions(prefix: String) {
        val id = ++mentionRequestId
        viewModelScope.launch {
            val answer = mentionSource.query(prefix) { id != mentionRequestId }
            if (id != mentionRequestId) return@launch
            when (answer) {
                // Superseded between the run and this line; the id check above is the
                // authority, this branch only keeps the `when` exhaustive.
                null -> Unit

                is MentionLookup.Candidates -> {
                    lastMentionFailure = null
                    _state.value =
                        _state.value.copy(mentions = MentionList(query = prefix, items = answer.items))
                }

                is MentionLookup.Unavailable -> {
                    // The lookup did not happen, so no list may stay on screen claiming to
                    // be one — and the reason is said **once per distinct failure**: the
                    // composer asks on a 150 ms debounce, and one notice per keystroke
                    // would bury the message the user needs to read. Cleared when a lookup
                    // succeeds, so a later failure of the same shape is reported again.
                    if (_state.value.mentions != null) {
                        _state.value = _state.value.copy(mentions = null)
                    }
                    if (lastMentionFailure != answer.sentence) {
                        lastMentionFailure = answer.sentence
                        pushNotice(answer.sentence, Notice.Tone.Warning)
                    }
                }
            }
        }
    }

    /** Close the mention list: the token stopped being a mention, or the screen left. */
    fun dismissMentions() {
        mentionRequestId++
        if (_state.value.mentions != null) {
            _state.value = _state.value.copy(mentions = null)
        }
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
        bashStream.setLength(0)
        lastBashPublishAt = 0L
        bashStreamTrimmed = false
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
        // The panel is gone, so its accumulation has no reader. Dropping it here is what
        // keeps a dismissed 50 MiB output from sitting in the heap until the next run.
        bashStream.setLength(0)
        lastBashPublishAt = 0L
        bashStreamTrimmed = false
        _state.value = _state.value.copy(bash = null)
    }

    // --------------------------------------------------------- session actions

    /**
     * `new_session`.
     *
     * [parentSession] is pi's optional field (`rpc-types.ts:27`): the **guest path**
     * of an existing session file, written verbatim into the new session's header as
     * `parentSession` (`session-manager.ts:938` in `SessionManager.new`). pi does
     * nothing else with it — its session selector rebuilds a tree from that field
     * (`components/session-selector.ts:206-231`), and our own [PiSessionStore.Summary]
     * already reads it back (`parentSession`), which is why a child session shows up
     * with the 分支 marker. No grouping or tag of our own is involved.
     *
     * pi answers `cancelled` when an extension vetoes it.
     */
    fun newSession(parentSession: String? = null) {
        call("新建会话") { api ->
            val result = api.newSession(parentSession)
            if (result.cancelled) {
                pushNotice("扩展取消了新建会话", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
        }
    }

    /**
     * `new_session` parented to one of the sessions in the on-disk index.
     *
     * The conversion host path → guest path is the same one [switchSession] uses;
     * pi resolves `parentSession` inside the guest, so handing it a host path would
     * record a parent that does not exist from pi's side.
     */
    fun newChildSession(summary: PiSessionStore.Summary) {
        val path = guestSessionPath(summary.file)
        if (path == null) {
            fail("找不到会话文件：${summary.file.name}")
            return
        }
        newSession(path)
    }

    /**
     * Delete one session file from the on-disk index.
     *
     * pi's picker offers delete behind a confirmation (`docs/sessions.md:48`) and
     * its store has no delete command either — the file *is* the session — so this
     * removes the JSONL the same way the desktop UI does. The screen refuses the
     * active session before ever calling this: pi is appending to that file, and
     * unlinking it under the engine would keep it writing to a removed inode.
     *
     * The unlink itself goes through [PiSessionStore.delete] rather than
     * `summary.file.delete()` so the store's containment guard
     * (`PiSessionStore.kt:161-167` — only a regular `.jsonl` directly inside the
     * session root) is the one that runs; a `Summary` is data, and data must not be
     * able to name an arbitrary path to unlink.
     */
    fun deleteSession(summary: PiSessionStore.Summary) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                sessionStore.delete(summary.file)
            }
            if (removed) {
                pushNotice("已删除：${summary.displayName}", Notice.Tone.Info)
                refreshSessions()
            } else {
                pushNotice("删除失败：${summary.file.name}", Notice.Tone.Warning)
            }
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
            // The file is known here and nowhere else on this path: pi's `get_state`
            // only reports it after the switch has been answered, and waiting for
            // that would add a round trip to every open. `replayHistory` consumes
            // the hint, and `resolveSessionFile` still cross-checks the header's id
            // against `meta.sessionId` before trusting any *other* path.
            SessionHints.file = summary.file
            val result = api.switchSession(path)
            if (result.cancelled) {
                SessionHints.file = null
                pushNotice("扩展取消了切换会话", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            requestNav(NavRequest.Chat)
        }
    }

    /**
     * `/import <path.jsonl>` — adopt a session file the user picked, then continue
     * in it.
     *
     * pi's own `/import` is a TUI command (`interactive-mode.ts:6107-6119`) and RPC
     * has no import command, but it does not need one: `switch_session` takes a
     * session **file path** (`rpc-types.ts:61`) and its handler runs the same
     * pipeline (`rpc-mode.ts:605-611` → `agent-session-runtime.ts:197-224`):
     * `emitBeforeSwitch` (an extension can veto), `SessionManager.open` (the header
     * is validated, `session-manager.ts:905-908`) and `assertSessionCwdExists`
     * (the recorded cwd must exist, `session-cwd.ts:54-58`). So this method does
     * only what pi's `importFromJsonl` does *before* that call
     * (`agent-session-runtime.ts:361-405`): check the file, choose the destination
     * name, copy it into the session directory.
     *
     * **One deliberate deviation**: pi copies first and validates afterwards, so a
     * file it rejects stays in the session directory; this app validates the head
     * before copying, so a wrong pick leaves nothing behind. The observable
     * difference is only that (the file is not a session either way).
     *
     * The cwd case is the one this app cannot fully reproduce: pi's TUI offers
     * "continue in current cwd" (`interactive-mode.ts:2545` →
     * `formatMissingSessionCwdPrompt`) and re-imports with a `cwdOverride`, while
     * `switch_session` has no such parameter (`rpc-types.ts:61`). That path fails
     * with a sentence that says so and what to do instead — never silently.
     *
     * All work that touches the picked document or the session directory happens on
     * [Dispatchers.IO], because the source can be a cloud provider and the session
     * file can be hundreds of megabytes.
     */
    fun importSession(source: Uri) {
        // `this.api` is null exactly while no engine is attached. Bound to a non-null
        // local so the coroutine below captures a value the compiler can see is
        // never null.
        val api = this.api ?: run {
            fail("引擎还没有就绪，等它启动完成后再导入。")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "导入会话")
            try {
                when (val prepared = withContext(Dispatchers.IO) { prepareImport(source) }) {
                    is ImportPrep.Rejected -> fail(prepared.sentence)

                    is ImportPrep.Ready -> {
                        SessionHints.file = hostSessionFile(prepared.guestPath)
                        val result = api.switchSession(prepared.guestPath)
                        if (result.cancelled) {
                            pushNotice("扩展取消了导入会话", Notice.Tone.Warning)
                        } else {
                            afterSessionReplaced()
                            requestNav(NavRequest.Chat)
                            pushNotice("已导入会话：${prepared.displayName}", Notice.Tone.Info)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // pi's raw text is not echoed: two of its three shapes carry a
                // filesystem path, and user-visible copy here never shows one.
                fail(SessionImport.failureSentence((error as? PiRpcException)?.reason ?: error.message))
            } finally {
                if (_state.value.busy == "导入会话") _state.value = _state.value.copy(busy = null)
            }
        }
    }

    /** What [importSession] decided before it touches pi. */
    private sealed interface ImportPrep {
        data class Ready(val guestPath: String, val displayName: String) : ImportPrep
        data class Rejected(val sentence: String) : ImportPrep
    }

    /**
     * Read the picked document's head, decide whether it is a session, and copy it
     * into pi's session directory under pi's own naming rule.
     *
     * Runs on IO; see [importSession] for the pipeline this reproduces.
     */
    private fun prepareImport(source: Uri): ImportPrep {
        val resolver = getApplication<Application>().contentResolver
        val sessionsRoot = File(host.paths().agentDir, "sessions")
        val sourceName = displayNameOf(source)

        // pi's usage string is `/import <path.jsonl>` (`interactive-mode.ts:6109`)
        // and a session file that is not named `.jsonl` is not listed by pi's own
        // picker (`session-manager.ts:825`), so a name that would produce an
        // invisible session is refused with what to pick instead. A provider that
        // exposes no display name at all gets a generated one and is judged on its
        // content alone.
        if (sourceName != null && !sourceName.endsWith(SessionImport.SUFFIX, ignoreCase = true)) {
            return ImportPrep.Rejected(SessionImport.wrongSuffixSentence(sourceName))
        }
        val destinationSeed = sourceName
            ?: "session-import-${System.currentTimeMillis()}${SessionImport.SUFFIX}"

        // Step 1: pi's header rule, applied to a bounded head (`SessionImport`).
        val head = runCatching {
            resolver.openInputStream(source)?.use { input ->
                String(readBoundedBytes(input, SessionImport.HEAD_SCAN_CHARS), Charsets.UTF_8)
            }
        }.getOrNull() ?: return ImportPrep.Rejected(SessionImport.unreadableSentence())
        if (SessionImport.verdictOf(head) is SessionImport.Verdict.NotASession) {
            return ImportPrep.Rejected(SessionImport.failureSentence("not a valid"))
        }

        // Step 2: pi's destination name (`agent-session-runtime.ts:371-379`).
        sessionsRoot.mkdirs()
        val destination = SessionImport.destinationName(destinationSeed) { candidate ->
            File(sessionsRoot, candidate).exists()
        } ?: return ImportPrep.Rejected(SessionImport.noFreeNameSentence(destinationSeed))
        val target = File(sessionsRoot, destination)

        // Step 3: the copy pi does with `COPYFILE_EXCL`
        // (`agent-session-runtime.ts:387`). The name was chosen not to exist, so a
        // plain create is the same guarantee; a partial write is removed rather than
        // left where the session list would offer an unopenable row.
        val copied = runCatching {
            val input = resolver.openInputStream(source) ?: return@runCatching false
            input.use { from ->
                target.outputStream().use { to -> from.copyTo(to) }
            }
            true
        }.getOrDefault(false)
        if (!copied) {
            runCatching { target.delete() }
            return ImportPrep.Rejected(SessionImport.unreadableSentence())
        }

        val guestPath = guestSessionPath(target)
        return if (guestPath == null) {
            runCatching { target.delete() }
            ImportPrep.Rejected(SessionImport.unreadableSentence())
        } else {
            ImportPrep.Ready(guestPath = guestPath, displayName = destination)
        }
    }

    /**
     * The picked document's name, or null when the provider does not publish one.
     *
     * `OpenableColumns.DISPLAY_NAME` is the only name a content URI has —
     * `Uri.lastPathSegment` is a provider-internal id (`…/document/1234`) and using
     * it as a file name was the reason a first attempt at this rejected every pick
     * that came from the Downloads provider.
     */
    private fun displayNameOf(source: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val published = runCatching {
            resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val cleaned = published
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return cleaned
    }

    /**
     * Read at most [limit] bytes off [input].
     *
     * Bounded because this reads a file the app did not choose the size of: a
     * `.jsonl` whose first line is a multi-megabyte tool result or an inline image
     * would otherwise be allocated in full before any budget could be consulted —
     * the same defect `docs/hang-and-crash-review.md` §A4 fixed for the session
     * list. The bytes are decoded as UTF-8 afterwards; a multi-byte character split
     * by the limit can only appear at the very end, by which point the header line
     * has been seen.
     */
    private fun readBoundedBytes(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * `fork` from an entry id pi itself handed us: the fork picker
     * ([refreshForkMessages] → `get_fork_messages`) and the session tree
     * (`get_tree` node ids). Both are already pi's entry ids, so nothing is
     * resolved here.
     */
    fun forkFrom(entryId: String) = forkAt(entryId, row = null)

    /**
     * `fork` for a row the user long-pressed in the transcript.
     *
     * **Why this needs its own resolver.** The block-action menu only knows the
     * row's *transcript key* (`UserMessage.key`), and that key is pi's entry id
     * only on the replay path: `TranscriptReducer.seedFromHistory` keys a
     * projected block off the entry it came from (`rpc/Transcript.kt:2020-2029`),
     * but the live path that draws the bubble the instant the user hits send
     * mints a synthetic one — `nextKey("user")` = `user-<epochMillis>-<n>`
     * (`Transcript.kt:982`, `:1010`). pi's `fork` looks the id up in the session
     * manager and throws `Invalid entry ID for forking` for anything it does not
     * know (`core/agent-session-runtime.ts:274-284`), so long-pressing a message
     * sent in *this* app run used to fail while long-pressing a message that came
     * back from `get_entries` worked. That is the whole of "有时候报错，有时候不报错":
     * it is deterministic, keyed on which of the two paths produced the row.
     *
     * The fix does not need a new wire field. pi already publishes the complete,
     * authoritative list of forkable points ([PiEngineApi.getForkMessages] →
     * `session.getUserMessagesForForking()`, `core/agent-session.ts:3309-3327`),
     * so the row is resolved against that list — see [resolveForkPoint].
     *
     * @param key the row's transcript key, used as an exact entry id when it is one.
     * @param ordinal the row's 0-based index among the transcript's user-message
     *   rows, or -1 when the caller cannot supply it.
     * @param text the row's own text, the last handle on a live (synthetic) row.
     */
    fun forkFromMessage(key: String, ordinal: Int, text: String) =
        forkAt(key, row = ForkRow(key = key, ordinal = ordinal, text = text))

    /**
     * A long-pressed transcript row, as the three things [resolveForkPoint] can use.
     *
     * `ordinal` is the same order pi lists the messages in, because
     * `getUserMessagesForForking` walks `getEntries()` in file order and the
     * transcript's user rows are projected from those same entries in the same
     * order — the one exception is a `/skill:` prompt with no trailing user text,
     * which projects no user row at all (`Transcript.kt:2353-2368`). That is why
     * the ordinal is only trusted together with the text.
     */
    private data class ForkRow(val key: String, val ordinal: Int, val text: String)

    /**
     * The one fork path: resolve (when the caller had no id), fork, rebuild.
     *
     * pi's `/fork` forks **before** the chosen message — `position: "before"` is
     * the default and sets the new leaf to that entry's `parentId`
     * (`agent-session-runtime.ts:264-287`) — and returns the message's text as
     * `selectedText` (`rpc-mode.ts:613-618`). pi's own picker then puts that text
     * back in the editor so it can be edited and resent
     * (`interactive-mode.ts:5157-5165`), which is what the block menu's label
     * 编辑并从此分叉 promises and what this now does through the same composer
     * fill channel `set_editor_text` uses.
     */
    private fun forkAt(entryId: String, row: ForkRow?) {
        call("创建分支", errorText = ::forkFailureText) { api ->
            val target = if (row == null) {
                entryId
            } else {
                val messages = api.getForkMessages()
                resolveForkPoint(row, messages) ?: run {
                    fail(forkPointNotFoundText(messages.isEmpty()))
                    return@call
                }
            }
            val result = api.fork(target)
            if (result.cancelled) {
                pushNotice("扩展取消了分支", Notice.Tone.Warning)
                return@call
            }
            afterSessionReplaced()
            result.text?.takeIf { it.isNotBlank() }?.let(::fillComposer)
            requestNav(NavRequest.Chat)
        }
    }

    /**
     * Which forkable message a long-pressed transcript row means, or null.
     *
     * Three handles, tried in decreasing strength. The first is exact; the other
     * two exist only because pi gives the app no way to learn the entry id of a
     * message it has just sent — `message_end` carries the message but no entry id
     * (`core/agent-session.ts:1524`, `:791-793`) and `entry_appended` is emitted
     * for extension `appendEntry` calls alone (`agent-session.ts:2593-2599`).
     *
     *  1. the transcript key **is** an entry id (the replay path) — exact;
     *  2. the row's position among the user rows, accepted only when pi's text for
     *     that position is the row's text, because a skipped row (`/skill:` with no
     *     trailing text) shifts every later ordinal by one;
     *  3. the row's text, preferring the **last** match: for a live row the last
     *     occurrence is the one just sent, and for a duplicated text either
     *     occurrence is a legal fork point, so the nearest to the leaf is the
     *     useful one.
     */
    private fun resolveForkPoint(row: ForkRow, messages: List<PiResponses.ForkMessage>): String? {
        messages.firstOrNull { it.entryId == row.key }?.let { return it.entryId }
        if (row.ordinal >= 0) {
            messages.getOrNull(row.ordinal)
                ?.takeIf { it.text == row.text }
                ?.let { return it.entryId }
        }
        return messages.lastOrNull { it.text == row.text }?.entryId
    }

    /** Nothing to fork from at all is a different answer from "not this row". */
    private fun forkPointNotFoundText(noForkableMessages: Boolean): String = if (noForkableMessages) {
        "这个会话还没有可分叉的用户消息（pi：No messages to fork from）。" +
            "先发一条消息，等 pi 把它写进会话文件后再分叉。"
    } else {
        "这条消息不在 pi 的可分叉点里。pi 只允许从已经写进会话文件的用户消息分叉" +
            "（getUserMessagesForForking），刚发出去还没落盘的一条就是这种情况：" +
            "等这一轮结束，或从 ⋮ 菜单的「从历史消息分支」里选一条。"
    }

    /**
     * pi's failures on the fork path, said with the next step attached.
     *
     * The reasons are pi's own, verbatim and quoted, because the app cannot
     * translate a fact it does not own — but each one has a known cause and a
     * known way out (`core/agent-session-runtime.ts:274-324`), and leaving that
     * out is what made the old failure read as a bare "engine said no".
     */
    private fun forkFailureText(error: Throwable): String {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: "原因未知"
        val next = when {
            reason.contains("Invalid entry ID for forking") ->
                "请从 ⋮ 菜单的「从历史消息分支」里选一条消息；那条列表就是 pi 认可的全部分叉点。"

            reason.contains("has not been saved yet") ->
                "pi 还没有把这个会话写到磁盘上的会话文件。先发一条消息、等模型回复一句，再分叉。"

            reason.contains("Persisted session is missing a session file") ->
                "pi 认为这个会话是持久化的，却没有会话文件路径。新开一个会话再试。"

            reason.contains("Failed to create forked session") ->
                "pi 没能写出分叉后的会话文件。检查 pi 工作目录是否可写（空间、权限），再试一次。"

            else -> "可以重新打开这个会话再试一次。"
        }
        return "创建分支失败：$reason。$next"
    }

    /**
     * Hand [text] to the composer, exactly as an extension's `set_editor_text`
     * does — one channel, one sequence counter, so a later fill always wins.
     */
    private fun fillComposer(text: String) {
        composerFillSeq += 1
        _state.value = _state.value.copy(composerFill = ComposerFill(composerFillSeq, text))
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
     * HTML still goes through `export_html`, written into the workspace where the
     * app can find it again. pi's default destination is its cwd —
     * `pi-session-<会话文件 basename>.html` (`core/export-html/index.ts:274-281`) —
     * which *is* the workspace, but the response returns the guest spelling of the
     * path. Passing an explicit path (with the name `SessionExportNaming` derives
     * from pi's rule) keeps both spellings known to this class, which is what lets
     * the app check the file it actually looks for.
     *
     * The confirmation names the **file**, not its path: the destination is this
     * app's private storage, so the only path the app could print is one the user
     * cannot open in any file manager — and it was the last place in the app's own
     * copy that showed an internal directory. That sentence is exactly why the
     * export now ends with a delivery handle instead of a path ([ExportedSession]):
     * the user gets the file (Download or the share sheet), not a location they
     * cannot reach. When the file is not there at all, the app says so rather than
     * falling back to pi's guest path.
     *
     * This is also the only non-TUI path by which an extension's
     * `renderCall`/`renderResult` output reaches a client (audit §5.11).
     */
    fun exportSession(fileName: String? = null) {
        val name = fileName?.trim()?.takeIf { it.isNotEmpty() }
        val jsonlName = name?.takeIf { SessionExportNaming.isJsonl(it) }
        if (jsonlName != null) {
            exportJsonl(jsonlName)
            return
        }
        val htmlName = name
            ?: SessionExportNaming.defaultHtmlName(_state.value.meta.sessionFile, System.currentTimeMillis())
        val guestPath = "${guestWorkspace()}/$htmlName"
        call("导出会话") { api ->
            api.exportHtml(guestPath)
            val hostPath = File(defaultWorkspace(), htmlName)
            if (hostPath.isFile) {
                publishExport(hostPath)
            } else {
                pushNotice("导出没有写出文件，请重试。", Notice.Tone.Warning)
            }
        }
    }

    /**
     * Register a finished export and say what the user can now do with it.
     *
     * One place for both writers, so the sentence and the state cannot disagree
     * about whether an export happened. The tone is Info on purpose: the export
     * succeeded, and what follows is an action, not a warning.
     */
    private fun publishExport(file: File) {
        _state.value = _state.value.copy(
            exported = ExportedSession(
                name = file.name,
                mimeType = SessionExportNaming.mimeTypeFor(file.name),
                path = file.absolutePath,
            ),
        )
        pushNotice("会话已导出，可以保存到 Download 或分享出去。", Notice.Tone.Info)
    }

    /** The user is done with the export row. */
    fun dismissExport() {
        _state.value = _state.value.copy(exported = null)
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
            // A failed write used to surface as the raw exception sentence, which
            // carries the path it failed on. The consequence is what the user needs,
            // and the path is the one thing this app never prints.
            val written = withContext(Dispatchers.IO) {
                runCatching { target.writeText(body.toString()) }.isSuccess
            }
            if (!written) {
                pushNotice("导出没有写出文件，请重试。", Notice.Tone.Warning)
                return@call
            }
            // The file, not its path — see [exportSession] for why the app does not
            // print paths into its own private storage.
            publishExport(target)
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
     *
     * ## Which session facts have to be re-read here, and which must not be
     *
     * Re-read:
     *
     *  - the transcript and its `streaming` flag ([replayHistory] → `seedHistory`,
     *    then [syncTranscript]). The reducer resets, so `lastUsage` comes back as the
     *    new session's too — `:1376` reads it off the reducer.
     *  - [UiState.meta] ([refreshState]), the `/` palette ([refreshCommands]), the
     *    session list ([refreshSessions]).
     *  - **[UiState.stats]** ([refreshStats]). It is per-session: pi's
     *    `getSessionStats` sums every entry **in this session file**
     *    (`session-manager.ts:1315-1316`), so leaving the old value on screen made
     *    the status row report the previous session's 输出 / 缓存读 / 费用 until the
     *    next turn finished — which is what made the user ask whether those figures
     *    were "all my sessions added up". (The token totals are cumulative *within* a
     *    session; `contextUsage` is the current branch's `getBranch()` reading. Two
     *    different scopes, one row.)
     *  - [UiState.queueSteering] / [UiState.queueFollowUp]. They are written only by
     *    `queue_update`, so without a reset a message queued in the session the user
     *    just left keeps its chip on the new session — a count of messages pi is not
     *    holding for this conversation. Zero is the honest value; pi's next
     *    `queue_update` (if it has anything queued) corrects it.
     *
     * Deliberately *not* re-read, with the reason:
     *
     *  - `tree` / `entries` / `forkMessages`: per-session, but each is read by the
     *    screen that shows it every time that screen opens ([refreshTree],
     *    [refreshForkMessages]) and the overlays close on a pick, so a stale copy is
     *    never the authority. Re-reading them here would add two RPC round trips to
     *    every switch for data nobody is looking at.
     *  - `bash`: a `!` command is a *workspace* job, not a session one — pi keeps
     *    running it across a switch and the app's row reports that process, so
     *    clearing it would hide a command that is still running.
     *  - the extension surfaces (`extensionStatuses`, `extensionWidgets`,
     *    `windowTitle`, `notices`) and above all `extensionDialog`: pi re-sends them
     *    when they change and there is no RPC to re-read them. Dropping the dialog
     *    would be worse than stale — pi is *blocked* on that answer
     *    (`modes/rpc-mode.ts:254-271`), so clearing it would wedge the engine.
     *  - `exported`, which is already handled just below.
     */
    private suspend fun afterSessionReplaced() {
        val engine = session ?: return
        // `refreshState` **before** the replay, not after: the replay's source is the
        // session file, and the only trustworthy name for that file is the one
        // `get_state` just reported. Reading it first would use the meta of the
        // session the user just left on the `new_session` / `fork` / `clone` paths,
        // which rebind in place and emit no event — i.e. it would replay the wrong
        // conversation. `switchSession` / `importSession` set [SessionHints] so the
        // common path is unaffected, and this ordering costs one round trip that
        // replaces a whole-session read.
        refreshState()
        replayHistory(engine)
        refreshStats()
        refreshCommands()
        refreshSessions()
        // One-shot: a hint is only valid for the command that set it. Left behind, it
        // would let a later attach replay the file of a session pi is no longer on.
        SessionHints.file = null
        if (_state.value.queueSteering != 0 || _state.value.queueFollowUp != 0) {
            _state.value = _state.value.copy(queueSteering = 0, queueFollowUp = 0)
        }
        // A different session's export is no longer the thing on screen: the row
        // would offer to save a file that belongs to the conversation the user just
        // left. The file itself stays on disk — this only drops the handle.
        if (_state.value.exported != null) {
            _state.value = _state.value.copy(exported = null)
        }
    }

    /** Ask `PiRoot` to change destination, raise an overlay, or focus a screen. */
    fun requestNav(request: NavRequest) {
        _state.value = _state.value.copy(navRequest = request)
    }

    /**
     * Clear the request the UI has just handled.
     *
     * `PiRoot` consumes the request from inside its effect and runs that effect
     * again on the `null` it writes back, so the value must actually change: the
     * write is skipped when it is already `null` to avoid emitting a state change
     * that carries none.
     */
    fun consumeNav() {
        if (_state.value.navRequest != null) {
            _state.value = _state.value.copy(navRequest = null)
        }
    }

    /**
     * A user-visible note from the UI layer, for failures the ViewModel does not
     * own (a picker that returned nothing readable). Same channel as every other
     * notice, so it cannot be missed silently.
     */
    fun notifyUser(message: String, warning: Boolean = false) {
        pushNotice(message, if (warning) Notice.Tone.Warning else Notice.Tone.Info)
    }

    /** Unknown `/name`: audit §6.4 — never let it reach the model as prose. */
    fun notifyUnknownCommand(name: String) {
        pushNotice(
            message = "「/$name」不是已安装的扩展命令、模板或技能；" +
                "如果要把它作为消息发给模型，请去掉开头的 /",
            tone = Notice.Tone.Warning,
        )
    }

    /**
     * The one-line status the AppBar shows. Deliberately terse and in pi's own
     * vocabulary (working / queued / idle) rather than inventing new states.
     *
     * `Starting` is listed separately from the boot's own "启动中" because they are
     * different waits with the same word: the boot one ends when the process is
     * spawned, and this one ends when pi has answered its first command — under
     * proot on a phone the second is by far the longer of the two. Both say 启动中
     * because that is what the user is waiting for; the difference is what the chat's
     * empty state explains (docs/known-gaps.md §M1).
     */
    fun engineLabel(current: UiState = _state.value): String = when {
        current.busy != null -> current.busy
        current.streaming -> "工作中"
        current.boot !is Boot.Ready -> "启动中"
        current.engine == PiEngineSession.EngineState.Failed -> "引擎已退出"
        current.engine == PiEngineSession.EngineState.Stopped -> "引擎已停止"
        current.engine == PiEngineSession.EngineState.Starting -> "启动中"
        current.queueSteering > 0 || current.queueFollowUp > 0 -> "排队中"
        else -> "就绪"
    }

    /**
     * True while the engine exists but has not yet answered its first command.
     *
     * Kept though nothing calls it today: it is the single readable statement of the
     * "engine up, not serving yet" window, and the *reason* a message sent there is
     * safe is that pi does not read stdin until its startup is over, so the bubble
     * appears at once and the answer follows. The empty-state paragraph that used to
     * ask this question is deleted (D31), and the strictly earlier window — no engine
     * at all — is now handled by [pendingPrompts] instead of by a boot screen. Both
     * are still this one question's neighbours, so the predicate stays until
     * something else needs to ask it.
     */
    fun engineStarting(current: UiState = _state.value): Boolean =
        current.boot is Boot.Ready && current.engine == PiEngineSession.EngineState.Starting

    /**
     * Resolve the current workspace before anything can read it, and publish it.
     *
     * Placed at the **end** of the class body on purpose: an `init` block runs in
     * declaration order, and this one pushes a notice, which touches state declared
     * further down ([noticeSeq]). Running it up next to [_state] would read that
     * counter before its initializer and let a later notice reuse a sequence number.
     *
     * What this does beyond [WorkspaceStore.refresh]: if the stored workspace was
     * deleted or is not one of ours, the store has already moved the process to the
     * default and written that correction back — this records it in the UI state
     * and, when the answer was *not* what the user chose, pushes the sentence
     * explaining the move. "明确回退，不要静默换目录" is this notice plus the write in
     * [WorkspaceStore.refresh]; neither half stands alone.
     *
     * Synchronous and tiny (one cached JSON read, one `isDirectory`), on the same
     * thread the ViewModel is constructed on — the settings stack's own stores are
     * built the same way.
     */
    init {
        val resolved = WorkspaceStore.reconcile(getApplication())
        publishWorkspaceState(resolved.name, resolved.note, bumped = resolved.note != null)
    }

    override fun onCleared() {
        // Answer anything outstanding before the engine is torn down, so a
        // still-live pi is not left blocked on a dialog whose UI just vanished.
        cancelAllDialogs()
        session = null
        api = null
        // Give the service its "no engine" answer *after* the settle, and give up
        // this ViewModel's claim on the engine's stop hook so a later one can own it.
        PiEngineController.unregisterStopHandler(stopEngineHook)
        // A turn that is still streaming has not reached pi's session file yet
        // (persistence happens on `message_end`), and a hard close drops it — so the
        // teardown settles the turn first. That cannot run on `viewModelScope`
        // because `onCleared` is what cancels it; see [teardownScope].
        //
        // Through `host.shutdown()`, not `engine.closeAfterSettling()`: the host holds
        // the **process-wide** lifecycle lock, and closing the session directly
        // bypassed it. That bypass was the one window in which two engines could
        // genuinely be alive on one cwd — this teardown settling for up to a minute
        // while the next ViewModel (a relaunch) boots a new engine on the same session
        // file (`PiEngineHost.PROCESS_LOCK`). The service is stopped after the settle,
        // so a backgrounded app does not keep a notification claiming 「PI 正在运行」.
        teardownScope.launch {
            host.shutdown()
            PiEngineService.stopIfRunning()
        }
        super.onCleared()
    }

    // ----------------------------------------------------------------- helpers

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
        /**
         * Where [onCleared]'s teardown runs.
         *
         * `viewModelScope` is cancelled *by* `onCleared`, so the graceful stop cannot
         * run there: the abort would be cancelled mid-flight and pi would be killed
         * with the turn still unwritten (`PiEngineSession.closeAfterSettling` has the
         * chain). This scope belongs to the process, which is what a teardown needs —
         * the engine has to outlive the ViewModel long enough to finish writing. IO,
         * and supervised so one failed stop cannot take the scope down before a later
         * teardown uses it.
         */
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Notifications held for the snackbar before the oldest is dropped. */
        const val MAX_PENDING_NOTICES = 8

        /**
         * How often a running `bash` command's accumulated output is published.
         *
         * The same number and the same rule as the transcript's tool-output throttle
         * (`rpc/.../Transcript.kt` `TOOL_UPDATE_THROTTLE_MS`), because it answers the
         * same problem for the same reason: pi emits a chunk per read and a chatty
         * command emits hundreds a second, while a panel of that shape has nothing to
         * say about the difference between two chunks 5 ms apart. It is a bound on the
         * *repaint* only — every chunk is kept in `bashStream`, and the `bash` response
         * replaces the output when the run ends, so the panel still shows everything.
         */
        const val BASH_UPDATE_THROTTLE_MS = 200L

        /**
         * How much of a session file one history window may read, in characters.
         *
         * 8 MiB. The number is a phone trade, and both directions matter:
         *
         *  - **Large enough** that the first window covers what the screen shows, a
         *    comfortable scroll beyond it, *and* the image case that used to fail
         *    outright. A session entry carries an inline base64 image at four
         *    characters per three bytes, so two 2.5 MB photos in one turn are 6.7 MB
         *    of a single window — the shape that used to be an over-cap record and an
         *    unopenable conversation. At ~500 characters per text entry the same
         *    budget is on the order of 16 000 entries. It is deliberately **not**
         *    raised to `SessionFileReader.DEFAULT_MAX_LINE_CHARS` (32 MiB): one
         *    oversized entry must not cost every text session its first-paint bound.
         *  - **Small enough** to stay a background blur rather than a stall. The whole
         *    open on this window is measured in the `session-replay-cost` harness;
         *    the phone's share beyond it is one `LazyColumn` measure pass over the
         *    rows produced.
         *
         * The newest entry of a window is admitted even when it alone exceeds the
         * budget — `SessionFileReader.readTail`/`readBefore` keep the newest line whole
         * and evict older ones, and the boundary snap goes back to the start of the line
         * it lands in — so a window is never empty merely because one entry is large,
         * and a big entry is never *skipped* by the window that follows it either. That
         * second half is what the harness's `img-1msg-2x5MB` fixture pins: a message
         * over one window (up to 31.94 MiB of base64 images is legal) arrives in a
         * window of its own instead of falling between two. The caps bound memory
         * without turning a big entry into a missing one.
         *
         * The entry cap is the second bound: a session of tiny entries (a `/mode` ping
         * per turn) would otherwise put tens of thousands of rows through the
         * projection for a window a screen shows three of.
         */
        const val HISTORY_WINDOW_CHARS = 8 * 1024 * 1024

        /** Entries in one history window. See [HISTORY_WINDOW_CHARS]. */
        const val HISTORY_WINDOW_ENTRIES = 4_000

        /**
         * Upper bound on the entries the transcript keeps from progressive loading:
         * 64 MiB of measured entry text.
         *
         * Eight windows' worth. It exists because "load earlier on demand" is bounded
         * per *step* but not per *session*: a reader who scrolls to the top of an
         * image-heavy conversation would otherwise hold every window passed. Past this
         * the expansion stops and the row above says there is more, so the failure
         * mode is "you have to scroll down and up again" rather than an OOM.
         */
        const val HISTORY_RETAINED_CHARS = 64L * 1024 * 1024

        /**
         * How many recorded failures the diagnostic report carries.
         *
         * The report is a text file a person reads and sends back; the first few
         * failures are the ones that matter, and `lastError` plus the error notices
         * can otherwise repeat the same sentence for every failed call in a long
         * session.
         */
        const val MAX_REPORTED_FAILURES = 12

        /** Extensions pi loads are TypeScript or JavaScript modules. */
        val EXTENSION_SOURCE_SUFFIXES = setOf("ts", "js", "mts", "mjs", "cts", "cjs")

        /** A 1 MiB source file is not an extension a phone should be parsing. */
        const val MAX_EXTENSION_SOURCE_BYTES = 1L shl 20

        /** `CURRENT_SESSION_VERSION` (`core/session-manager.ts:30`). */
        const val CURRENT_SESSION_VERSION = 3
    }
}

/**
 * The system night mode at the moment this view model was built.
 *
 * Read synchronously from the application's configuration so the very first
 * composition — which happens before any theme file has been read — can already
 * paint the right polarity. `isSystemInDarkTheme()` is the same value, but it is
 * `@Composable` and therefore unavailable here; the caller supplies it again on
 * every `LaunchedEffect(systemDark)` so a later switch re-resolves the theme.
 */
private fun systemDarkAtStartup(application: Application): Boolean =
    (application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
