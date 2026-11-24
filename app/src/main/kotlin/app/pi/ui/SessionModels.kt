package app.pi.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import app.pi.engine.EngineFailure
import app.pi.engine.EngineFailureClassify
import app.pi.protocol.PiImage
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.WorkspaceStore
import app.pi.session.SessionExportNaming
import app.pi.session.SessionFileReader
import app.pi.service.PiEngineService
import app.pi.ui.theme.PiTypographyProfile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** 会话页的数据模型：启动态、工作区、导航请求、输入草稿、偏好等。从 PiSessionViewModel.kt 拆出（同包，无行为变化）。 */

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
    /**
     * 失败页的内容，由 [EngineFailureClassify] 按**真实原因**算出来。
     *
     * 以前这里只有 `message`/`detail` 两个字符串，三种完全不同的失败共用一句
     * 「引擎没有在运行」和同一组按钮（含会删掉 guest 安装物的「重建运行时」）。现在
     * 每一类有自己的标题、解释和该不该给重建按钮。
     */
    data class Failed(val failure: EngineFailure) : Boot
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
    /** The current project's identity (directory name under `/root/projects`, or absolute host path). */
    val name: String = WorkspaceStore.DEFAULT_NAME,
    /** Human-facing display name (`Default Project` / real folder name / custom label). */
    val displayName: String = app.pi.runtime.WorkspaceChoice.displayName(WorkspaceStore.DEFAULT_NAME, null),
    /** Canonical guest cwd as seen by Pi (`/root/projects/default`, `/storage/emulated/0/...`, etc.). */
    val guestPath: String = GuestWorkspacePath.DEFAULT_GUEST,
    /** The host directory, for a screen that wants to show it. Null if not resolvable. */
    val hostPath: String? = null,
    /** True when the project lives outside `/root/projects`. */
    val isExternal: Boolean = false,
    /** Bumped on every successful switch; see the class KDoc. Starts at 0. */
    val revision: Int = 0,
    /**
     * Why the current project is not the stored choice, when the stored choice
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
 * Only [Chat] and [Settings] name a top-level destination — the vocabulary and
 * `PiDestination` are kept in step on purpose, so a request can never ask for a
 * destination the app does not have. Everything else here raises an overlay or
 * focuses a screen.
 *
 * 工作区 is deliberately absent for that reason: nothing in the app asks to *go*
 * there (the sessions page and the chat 「⋯」 menu are the two doors), so there is
 * no request for it. It used to be named by a `Workbench` member whose one
 * consumer was the chat composer's
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

    /** Open or focus a terminal tab directly inside a specific guest directory (e.g. from Project & Files). */
    data class TerminalAt(val guestCwd: String) : NavRequest

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
 * ## 一次导出是两份
 *
 * HTML 给人看/分享，**导不回来**（不是 pi 的会话格式）；所以同一份导出里还带着**会话文件本身**
 * —— 逐字节复制、连名字都不改，那份才是能 `importFromJsonl` 导回来的。
 *
 * [ExportedFile.path] 是 App 私有目录里的绝对路径，**从不渲染**：用户可见文案不出现内部目录。
 * [ExportedFile.name] 是用户看得到的文件名，也是交付时用的 Download 文件名。
 */
data class ExportedSession(val files: List<ExportedFile>) {
    /** 行上显示的那一行：两份的文件名并排，`.html` / `.jsonl` 一眼分清。 */
    val name: String get() = files.joinToString(" ＋ ") { it.name }
}

/** [ExportedSession] 里的一份产物（HTML 或那份逐字节复制的会话文件）。 */
data class ExportedFile(
    /** 文件名，也是交付时的 Download 文件名。 */
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
 * The four `app.terminal.*` keys are siblings of these and deliberately not
 * repeated here: `ui/terminal/TerminalSettings.kt` is their consumer, so a second
 * reader in the ViewModel would be a second truth about the same key. (Their 设置
 * rows were deleted with the 终端与 Shell group; the keys still work when a
 * `settings.json` names them.)
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
    /**
     * True when [startOffset] is the beginning of the file: no earlier history.
     *
     * **Only a read that establishes it may set this** — a window
     * `SessionFileReader.readBefore` flagged `reachedStart`, or a range that already
     * begins at byte 0 ([BackwardRead]). Neither a read that failed nor a step that
     * delivered nothing may set it: doing so deletes the 「加载更早」 row and makes every
     * entry above the loaded range unreachable for the rest of the session, which is the
     * reported 「之前的内容都被截掉了，都没了」.
     */
    val reachedStart: Boolean,
    /** A backward window is being read right now; the screen must not ask again. */
    val loading: Boolean = false,
    /**
     * Why the last backward step delivered nothing, or null when it delivered.
     *
     * The reason the screen shows. A non-null stop means "do not ask again by yourself,
     * but the reader may press the row" — [hasEarlier] is false for the scroll effect
     * (`ChatScreen.kt:1312`), while the row itself stays because [showsEarlierRow] is
     * about whether there is anything to say, not about whether a step is automatic.
     */
    val stop: HistoryStop? = null,
) {
    /** Whether the scroll effect may ask for another window without the reader's press. */
    val hasEarlier: Boolean get() = !reachedStart && !loading && stop == null

    /** Whether the 「加载更早」 row belongs on screen at all. */
    val showsEarlierRow: Boolean get() = !reachedStart
}

data class UiPrefs(
    /**
     * The whole 排版 preset, resolved once here from
     * `app.appearance.typography` / `.lineHeight` / `.fontSize`.
     *
     * Built in [readPrefs] rather than exposed as three raw strings with a
     * computed property, because the three consumers (`MainActivity`'s
     * `PiTheme`, `PiMarkdownText`'s five library objects, `ChatScreen`'s
     * `spacedBy`) each need a different part of it and a property would rebuild
     * the five `TextStyle`s on every read. Being a data-class field also lets
     * `remember` key on a value that compares structurally, so an equal preset
     * does not invalidate anything.
     *
     * It replaces `fontScaleDelta: Int` and `messageDensity: String`. Those two
     * keys are gone from the registry and are read only by the migration in
     * `readPrefs`; see [PiTypographyProfile.migratedTypography] and
     * [PiTypographyProfile.migratedFontSize].
     */
    val typographyProfile: PiTypographyProfile = PiTypographyProfile.Default,
    /**
     * `app.appearance.showTimestamps`, **default off**. It gates three things: the clock on
     * the user's own bubble, the clock at the end of a notice line, and the date separators
     * `ChatScreen` inserts between days. The first two were unconditional until this switch
     * was made to mean something — which is what made the row look broken.
     */
    val showTimestamps: Boolean = false,
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
 * `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` − 64 KiB ≈ **63.94 MiB of base64**, i.e.
 * ≈ 47.95 MB of bytes, or 14 pi-maximum images). That budget is what makes this type
 * necessary rather than optional: the smallest contribution one image can make to the
 * Bundle is still megabytes of text, and a legal message is now up to fourteen of them,
 * while Binder's per-transaction limit is ~1 MB — so the *first* image was already over it.
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

    /** 文字与待发图片一起清空；`发送` 与用户主动清空走它，**工作区切换不走**。 */
    fun clear() {
        text.value = ""
        attachments.value = emptyList()
    }
}
