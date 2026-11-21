package app.pi.ui

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pi.host.ExtUiState
import app.pi.engine.EngineCrashRecovery
import app.pi.engine.EngineFailureClassify
import app.pi.engine.RetryMode
import app.pi.engine.EngineExitCause
import app.pi.host.HostEngineApi
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.host.HostCommandException
import app.pi.i18n.AppLanguage
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.asOutcome
import app.pi.protocol.Notice
import app.pi.protocol.PiEvent
import app.pi.protocol.PiImage
import app.pi.protocol.PiLaunchOptions
import app.pi.protocol.PiResponses
import app.pi.protocol.parseSessionEntry
import app.pi.protocol.QueueMode
import app.pi.protocol.SessionEntry
import app.pi.protocol.StreamingBehavior
import app.pi.runtime.PiPaths
import app.pi.protocol.TOOL_UPDATE_THROTTLE_MS
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.protocol.UserMessage
import app.pi.protocol.AssistantText
import app.pi.ui.chat.EntryText
import app.pi.ui.chat.MessageEntryResolver
import app.pi.ui.chat.MessageRole
import app.pi.protocol.extensionErrorHeadline
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PtyLauncher
import app.pi.runtime.RuntimePreferences
import app.pi.runtime.RuntimeSelection
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.session.SessionLibrary
import app.pi.session.SessionListQuery
import app.pi.session.SessionSort
import app.pi.session.SessionSummary
import app.pi.session.ResumeTarget
import app.pi.session.SessionExportNaming
import app.pi.session.SessionFileReader
import app.pi.session.SessionImport
import app.pi.session.countIdLookupCandidates
import app.pi.session.resumeTargetFor
import app.pi.service.PiEngineController
import app.pi.service.PiEngineLifecyclePolicy
import app.pi.service.PiEngineService
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readBoolean
import app.pi.settings.readString
import app.pi.ui.chat.BASH_OUTPUT_MAX_CHARS
import app.pi.ui.chat.BranchSummaryChoice
import app.pi.ui.chat.NavigateOutcome
import app.pi.ui.chat.ChatAction
import app.pi.ui.chat.PiCommandSource
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.MentionLookup
import app.pi.ui.chat.PiMentionSource
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.activeBranch
import app.pi.ui.chat.appendTailBounded
import app.pi.ui.chat.navigateOutcome
import app.pi.ui.chat.builtinRows
import app.pi.ui.chat.piCommandPalette
import app.pi.ui.chat.wantsSummary
import app.pi.ui.extension.ComposerFill
import app.pi.protocol.ExtensionEntry
import app.pi.ui.extension.EntryRenderer
import app.pi.ui.extension.EntryRendererCache
import app.pi.ui.extension.MarkdownTransform
import app.pi.ui.extension.MarkdownTransformCache
import app.pi.ui.extension.ExtensionAnswer
import app.pi.ui.extension.ExtensionDialog
import app.pi.ui.extension.ExtensionDialogMethod
import app.pi.ui.extension.ExtensionDialogQueue
import app.pi.ui.extension.ExtensionNotice
import app.pi.ui.extension.MessageRenderer
import app.pi.ui.extension.MessageRendererCache
import app.pi.ui.extension.ToolRenderer
import app.pi.ui.extension.ToolRendererCache
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.noticeToneOf
import app.pi.ui.extension.trimNoticeQueue
import app.pi.ui.settings.EngineDiagnostics
import app.pi.ui.settings.PiSettingsCatalog
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.settings.RuntimeSwitchAction
import app.pi.ui.settings.boolIn
import app.pi.ui.theme.PiResolvedTheme
import app.pi.ui.theme.PiSystemPaletteSource
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeLoader
import app.pi.ui.theme.PiTypographyProfile
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
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
 * [HostEngineApi] — the typed façade over pi's command surface — so a command pi
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
    /** 命令参数补全的一次应答：对 [command] 的 [prefix] 给出的候选（`catalog.commandCompletions`）。 */
    data class ArgCompletions(
        val command: String,
        val prefix: String,
        val items: List<PiResponses.CommandCompletion>,
    )

    /** 扩展 `addAutocompleteProvider` 补全链的一次应答。 */
    data class ExtensionCompletions(
        val draft: String,
        val prefix: String,
        val items: List<app.pi.host.HostCommands.ExtensionCompletion>,
    )

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
        /**
         * 所有「已运行 N 秒」读数共用的那个粗时钟；转写里没有 pending 的行时为 null。
         *
         * **为什么必须由这里发布，而不是在组合里现读。** 工具卡的耗时原来是在组合时算的
         * （`ShellBlock`：`System.currentTimeMillis() - item.ts`），于是那个数字是「这一行
         * 上一次重组是什么时候」的函数。安静跑着的命令既没有流式文本也没有输出块推过来，
         * 没人重组它，读数就冻在那儿，直到点一下卡或切屏逼出一次新组合才跳一下。时钟得
         * **被推**，不能被读。
         *
         * **为什么是 1 秒。** 读数用的是 pi 自己的写法（`ToolOutputParse.formatDuration`：
         * `0.9s` / `1m 30s`），而 pi 的 bash 渲染器就是**每秒刷一次**同一个读数
         * （`renderers/bash.ts:122`）。跟它同拍最省：更密的心跳只会为同一个数字多重组几次。
         *
         * null 是**空闲**态，也是消费者的回退契约：为 null 时没有任何东西在滴答（见
         * ViewModel 里的发布者），消费者保持自己原来的行为，而不是编一个读数出来。
         */
        val nowMs: Long? = null,
        val queueSteering: Int = 0,
        val queueFollowUp: Int = 0,
        /**
         * The queued messages themselves, in pi's own order, as `queue_update`
         * reported them.
         *
         * pi sends the text, not just a count — `queue_update` is
         * `{type, steering: string[], followUp: string[]}`
         * (`core/agent-session.js:655`, emitted at `:1767` on a steer enqueue, `:1783`
         * on a follow-up, `:1920` on `clearQueue`) and RPC forwards the whole event
         * verbatim (`modes/rpc/rpc-mode.js:265-270`; `modes/json-event.js:17-19` only
         * rewrites `message_update`). The app used to keep `.size` alone, which made
         * the two counts below the only trace of a queue whose *content* the user
         * cannot otherwise see — a queued message is deliberately not in the
         * transcript until pi delivers it (see `PiEngineSession.prompt`), and
         * official pi prints the text (`interactive-mode.js:3879` `Steering: …`,
         * `:3883` `Follow-up: …`).
         *
         * The counts are kept beside them because the engine line reads them on its
         * own ([engineLabel]) and a count is the honest reading of "is anything
         * waiting"; the lists are what the pending row draws.
         */
        val queueSteeringTexts: List<String> = emptyList(),
        val queueFollowUpTexts: List<String> = emptyList(),
        val lastError: String? = null,
        /**
         * The blocking `extension_ui_request` on screen right now — the head of
         * the ViewModel's FIFO queue. Null when nothing is pending.
         */
        val extensionDialog: ExtensionDialog? = null,
        /** Requests waiting behind [extensionDialog], in arrival order. */
        val extensionDialogBacklog: Int = 0,
        /** `setStatus` entries, oldest key first. */
        /** 扩展 UI 的全部状态（状态行、小组件、header/footer、custom 表面、工作中提示……），来自宿主 `ui.*` 事件。 */
        val ext: ExtUiState = ExtUiState(),
        /** `setWidget` panels. Empty lines cleared the key, so none here are empty. */
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
        /** 分支跳转或会话替换后自增，让对话页清空旧锚点并重新触底到新分支末尾。 */
        val transcriptResetSeq: Long = 0L,
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
        val argCompletions: ArgCompletions? = null,
        /** 扩展通过 `ctx.ui.addAutocompleteProvider` 返回的补全候选。 */
        val extCompletions: ExtensionCompletions? = null,
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
         * [navRequest] 的序号 —— "这一条请求"的身份。`PiRoot` 拿它去
         * [PiSessionViewModel.claimNav] 领处理权：只处理**新的**请求，`0` 表示没有请求。
         */
        val navSeq: Long = 0,
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
        val lastUsage: app.pi.protocol.TokenUsage? = null,
        /**
         * **This turn's** usage: every assistant reply since the turn started, summed
         * (`TranscriptReducer.turnUsage` — read its KDoc for the definition, for why
         * only settled `message_end` figures are added, and for what it deliberately
         * leaves out).
         *
         * `null` when the turn reported no usage at all, or before any turn has run;
         * a reader hides its group rather than printing zeros.
         */
        val turnUsage: app.pi.protocol.TokenUsage? = null,
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
    ) {
        /**
         * [nowMs] 的发布判据，全 App 只有这一处：转写里有没有一行工具调用还在等结果。
         *
         * **为什么是 `ToolStatus.Pending` 而不是 [streaming] 或 `bash.running`。** 它是
         * 「谁真的会去读这个时钟」的那个集合：一行工具卡是不是「运行中」只看它自己的
         * `status`（`toolStateOf`，`ui/blocks/ToolBlockChrome.kt:152-153`），而只有「运行中」
         * 的行会要一个走动的耗时（`ShellBlock`）。`streaming` 覆盖同一件事但**更宽**——
         * 它从 `agent_start` 一直真到 `agent_end`（`TranscriptReducer`），所以一段几分钟
         * 没有工具卡的纯文本回答也会命中了，而那时屏幕上没有任何读数在走。
         *
         * **安静跑着的 bash 掉不出去。** 一条命令的 `ToolCall` 行会一直停在 Pending，直到
         * `tool_execution_end` 把它翻成成功/失败（`rpc/.../Transcript.kt:1650` →
         * `finalizeTool`，`:1685`）；`tool_execution_update` 的 200 ms 节流省掉的是**发布**，
         * 不是行的状态，而一条什么都不打印的命令更是一条发布都没有——这正是时钟必须按自己的
         * 节拍重新问这个问题、而不是等某次发布顺手把答案带过来的原因。
         *
         * `state.bash`（`BashRun.running`）故意**不在**判据里：它确实也 pending，但没有读者
         * 会为它读时钟——`BashRun` 没有开始时间戳（`ui/screens/ProjectScreen.kt:1571-1573`
         * 记着这件事），`BashPanel` 自己写着「没有耗时读数」（`ui/chat/BashPanel.kt:73`），
         * 收进来就是每秒白白发一次没人看的滴答。哪天 `BashRun` 有了时间戳，往这里加一项。
         *
         * 这一次 O(行数) 扫描的代价和 `syncTranscript` 为它的 `interrupted` 投影每次发布
         * 都要跑的那次判据是同一条（`:2659`），而且它跑在时钟自己的节拍上，不是每帧。
         */
        val hasPendingToolClock: Boolean
            get() = transcript.any { it is ToolCall && it.status == ToolStatus.Pending }
    }

    internal val host = PiEngineHost(app)
    internal var session: PiEngineSession? = null

    /**
     * The entries the current transcript was seeded from, held as the windows they were
     * read in and **newest window first**.
     *
     * Held **only to be re-seeded**: [expandEarlierHistory] rebuilds the transcript
     * from an older window plus this list, and the reducer cannot give the list back
     * because it stores rows (day separators, merged tool cards, optimistic echoes),
     * not entries. It is therefore bounded by what the user has actually scrolled
     * through — never by the session — and it is replaced wholesale, never appended
     * to, so a session switch cannot leave one session's entries in the next one's
     * rebuild. [replayHistory] is the only writer.
     *
     * **It is a copy.** The rows it was folded into live in the reducer; this list only
     * exists so a later rebuild can reproduce them. That is what makes it the one thing
     * an eviction may drop, and why an eviction costs no row: the dropped range is
     * `[evictedFrom, loadedUntil)`, and the next rebuild reads it back from the file.
     *
     * The **chunk** boundaries (one per window the reader walked through) are what
     * `evictablePrefix` needs: eviction has to drop whole windows, and it has to know
     * where a window starts in the file to read it back.
     */
    internal var retainedChunks: List<RetainedChunk> = emptyList()

    /**
     * Retained characters in [retainedChunks], against [HISTORY_RETAINED_CHARS].
     *
     * Kept **incrementally** — a read adds its own window's count and an eviction
     * subtracts the dropped chunks' — rather than re-summed, because the measure
     * serialises every entry ([entryChars]) and re-summing would make each step cost
     * the whole retained range. Additivity is what makes that the same number, and the
     * `history-retention` harness pins it.
     */
    internal var loadedHistoryChars: Long = 0L

    /**
     * The byte range of the loaded session that is **no longer in RAM**:
     * `[evictedFrom, loadedUntil)`, read back on the next rebuild.
     *
     * `evictedFrom == loadedUntil` means nothing has been evicted. Both are line-start
     * byte offsets of the session file, and both stay valid while pi appends to it,
     * because [loadedUntil] is fixed at the file's size when it was replayed.
     */
    internal var evictedFrom: Long = 0L
    internal var loadedUntil: Long = 0L

    /** Entries in `[evictedFrom, loadedUntil)`, so a read-back that disagrees is refused. */
    internal var evictedEntries: Int = 0

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
    internal fun retainedChars(entries: List<JsonObject>): Long = entryCharsOf(entries)

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
     * Only the paths a user can reach without an engine are held: [send], [sendFollowUp],
     * [runPromptCommand] and [newSession] (the last one is the 「我新建对话，点第一下新建不了，
     * 再点第二下」 defect — it used to go straight to `call`'s not-ready branch and never ran).
     * `stop`, `restoreQueue` and the rest are *about* a live engine and mean nothing without one.
     */
    private val pendingPrompts = mutableListOf<() -> Unit>()

    /**
     * Park [action] unless this engine can actually execute it now; `true` when it was parked.
     *
     * **The predicate is [api], not `session`, and that difference is the 「新建会话点 1/2/3 次」
     * defect.** [api]'s own KDoc says it is "null exactly when there is no engine to talk to,
     * which is what every action below checks before doing anything" — [call], the only thing
     * that actually runs an action, does check it. This function checked `session` instead, so
     * the two asked *different questions*: `attach` sets both fields together, but engine death
     * clears them **in the opposite order** (`api = null` before `session = null`). A click
     * landing in that window was therefore **neither parked nor executed** — it fell through to
     * [call]'s not-ready branch and left one notice, which is precisely the user-visible shape
     * this mechanism was built for (「我新建对话，点第一下新建不了，再点第二下」). The old
     * predicate narrowed that window; this one closes it, because the field it asks about is
     * the field that decides.
     *
     * The closure re-enters the public function it came from, so the replay runs the
     * same checks (compaction window, streaming behavior, optimistic echo) as a live
     * call rather than a copy of them that could drift.
     */
    internal fun parkUntilAttached(action: () -> Unit): Boolean {
        if (api != null) return false
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
    internal var api: HostEngineApi? = null

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
    internal var engineTransition = false

    /**
     * 这条会话里已经替用户**静默重启**过几次引擎（见 [EngineCrashRecovery]）。
     *
     * 不清零会在一个长会话里把"三次抖动"误判成"引擎坏了"：只要引擎重新起来，这个
     * 计数就归零（[attach]），所以它数的是**连续**失败。用户从失败页点「重试」也走
     * [boot]，那条路上计数不归零没关系 —— 下一次起来时 [attach] 会清。
     */
    private var autoEngineRestarts = 0

    /**
     * 这条启动链上是否已经试过**不删数据的修复**（`ensureReady(repair = true)`）。
     *
     * 「重试」曾经对"树里少文件/坏文件"这一类失败完全无效：快路径只比戳记，戳记相符就
     * 立刻返回成功，于是点一百次也是同一个结果，用户只能点「重建运行时」——而它会删掉
     * guest 里装的一切。现在第一次重试走修复（重新解包、不 wipe），再失败回到普通重试，
     * 重建仍然只由用户显式点。
     */
    private var provisioningRepairAttempted = false

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
    internal var settingsStoreCache: PiSettingsStore? = null

    val settingsStore: PiSettingsStore
        get() = settingsStoreCache ?: PiSettingsFileStore.forWorkspace(
            agentDir = host.paths().agentDir,
            workspace = defaultWorkspace(),
        ).also { settingsStoreCache = it }

    /**
     * The store the **settings screen** must be handed: [settingsStore] with the
     * app-only sidecar for `app.extensions.args` ([ExtensionArgsStoreDecorator]).
     * System prompt & context files are now managed directly via SDK Host
     * (`resources.loaded` + `ContextScreen`).
     */
    fun settingsStoreForSettingsUi(): PiSettingsStore =
        ExtensionArgsStoreDecorator(settingsStore, extensionArgsStore)

    /**
     * The app-only sidecar behind `app.extensions.args`.
     *
     * The launch mapping reads it ([launchOptions]) and the settings stack writes it
     * through [ExtensionArgsStoreDecorator]; this is the one instance the mapping
     * needs. App-only on purpose — pi has no settings key for extension flags, so a
     * value in `settings.json` would be a key pi never reads (see [ExtensionArgsStore]).
     */
    private val extensionArgsStore: ExtensionArgsStore by lazy { ExtensionArgsStore(getApplication()) }

    /**
     * 会话库：列表 / 搜索 / 改名 / 删除，全部经宿主命令（SDK 的 `SessionManager`）。
     * 不再自己扫目录、解析 JSONL（docs/sdk-host/sessions.md）。需要引擎已打开。
     */
    internal val sessionLibrary: SessionLibrary by lazy {
        SessionLibrary(
            host.paths().agentDir,
            host.guestAgentDir,
            java.io.File(getApplication<Application>().filesDir, "pi/session-list-cache.json"),
        ) { session?.commands }
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
    internal var mentionSourceCache: PiMentionSource? = null

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

    /**
     * 会话列表（官方 `/resume` 选择器）的整个状态。排序 / 成树 / 过滤 / 搜索都由宿主用官方实现算好
     * （`commands/sessionlist.mjs`），这里只存「现在是什么视图、手上是哪些行、读到哪了」。
     *
     * 对照 `session-selector.js`：
     *  - [rows] 与 [loading] 分开：加载中也先画已有的行（官方 `onProgress` 的部分结果、上次的缓存），
     *    读完无声替换 —— 已经有行可看时不盖一层转圈；
     *  - [loaded] / [loadTotal] 就是官方头部的 `Loading n/m`；
     *  - [failed] 只在这一次没读到**且手上没有可显示的行**时才让界面说「读不到」，不把「没读到」说成「没有会话」；
     *  - [queryError]：搜索语法错（坏正则）。官方此时列表为空，这里多带一句原因。
     */
    data class SessionListState(
        val query: SessionListQuery = SessionListQuery(),
        val showPath: Boolean = false,
        val rows: List<SessionSummary> = emptyList(),
        /** 当前范围内、过滤前的会话总数（空态要区分「一个都没有」与「被过滤光了」）。 */
        val total: Int = 0,
        val loading: Boolean = false,
        val loaded: Int = 0,
        val loadTotal: Int = 0,
        /** [rows] 是上次存下来的（宿主还没读完），读完会换掉。 */
        val fromCache: Boolean = false,
        /** 无缓存首次扫描时正在流式接收 `sessions.progress` 部分行（1 → 10 → 20 …）。 */
        val streamingPartial: Boolean = false,
        val failed: Boolean = false,
        val queryError: String? = null,
    )

    internal val _sessionList = MutableStateFlow(SessionListState())
    val sessionList: StateFlow<SessionListState> = _sessionList.asStateFlow()

    /** 每发一次请求 +1；旧请求回来时发现号变了就丢弃（官方 `isActive` 守卫）。主线程读写。 */
    private var sessionListGeneration = 0L
    private var sessionListJob: kotlinx.coroutines.Job? = null
    private var sessionListPrimed = false

    /** 成功读完过一次：之后打开列表屏直接用手上的行（秒开），变更靠 [watchSessionListChanges] 在后台补。 */
    internal var sessionListLoadedOnce = false

    /**
     * 列表屏挂载时立刻把当前活动会话叠加到列表头部（即使刚新建、尚无助手回复未落盘），
     * 并在引擎就绪时走增量索引静默刷新一次（~10ms，有缓存时不转圈、不清行），保证列表永远与现状同步。
     */
    fun ensureSessionList() {
        applyActiveRow()
        val up = session?.state?.value.let { it == PiEngineSession.EngineState.Ready || it == PiEngineSession.EngineState.Busy }
        if (up && !_sessionList.value.loading) loadSessionList(refresh = sessionListLoadedOnce)
    }

    /**
     * 「现在在哪个会话 / 工作区」变了，列表要**立刻**跟上，而不是等一遍整盘重读：
     *  - 工作区换了：后台重读（别的目录的会话要进来）。
     *  - 当前会话换了 / 新会话新建或命名 / 首轮对话落盘：立刻通过 [applyActiveRow] 更新当前行，
     *    再安排一次后台增量重读换成宿主算的真数据。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    internal fun watchSessionListChanges() {
        viewModelScope.launch {
            var lastRevision = _state.value.workspace.revision
            _state
                .map {
                    listOf(
                        it.meta.sessionFile,
                        it.meta.sessionId,
                        it.meta.sessionName,
                        it.workspace.revision,
                        it.meta.messageCount.coerceAtMost(2),
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(120)
                .collect {
                    val revision = _state.value.workspace.revision
                    if (revision != lastRevision) {
                        lastRevision = revision
                        if (sessionListLoadedOnce) refreshSessions() else applyActiveRow()
                    } else {
                        val changed = applyActiveRow()
                        if (changed && sessionListLoadedOnce) {
                            sessionListReconcileJob?.cancel()
                            sessionListReconcileJob = viewModelScope.launch {
                                kotlinx.coroutines.delay(500)
                                refreshSessions()
                            }
                        }
                    }
                }
        }
    }

    private var sessionListReconcileJob: kotlinx.coroutines.Job? = null

    /**
     * 把当前会话反映到手上的行里：重算 `isCurrent` 与实时标题/消息数；
     * 即使刚点击「新建会话」尚无消息（官方懒落盘，磁盘上还没有 `.jsonl` 文件），也立刻在当前工作区置顶展示，
     * 避免出现「点了新建对话，列表里却找不到，重启 App 才能看见」的问题。
     * @return 是否有新增或更新（调用方据此决定是否安排后台对账重读）。
     */
    internal fun applyActiveRow(): Boolean {
        val st = _state.value
        val cur = _sessionList.value
        val guestFile = st.meta.sessionFile
        val hostFile = guestFile?.let { sessionLibrary.hostFileOf(it) }
        val activeId = st.meta.sessionId?.takeIf { it.isNotBlank() }
        if (hostFile == null && activeId == null) return false

        fun isRowCurrent(row: SessionSummary): Boolean =
            (hostFile != null && row.file.absolutePath == hostFile.absolutePath) ||
                (activeId != null && row.id.isNotBlank() && row.id == activeId)

        var rows = cur.rows.map { row ->
            val current = isRowCurrent(row)
            if (current) {
                row.copy(
                    isCurrent = true,
                    name = st.meta.sessionName ?: row.name,
                    messageCount = maxOf(row.messageCount, st.meta.messageCount),
                )
            } else if (row.isCurrent) {
                row.copy(isCurrent = false)
            } else {
                row
            }
        }

        // 在非搜索状态下（以及未开启「仅命名」或当前会话已命名时），确保当前活动会话始终可见。
        val visibleUnderFilter = cur.query.query.isBlank() &&
            (!cur.query.namedOnly || !st.meta.sessionName.isNullOrBlank())
        var added = false
        if (visibleUnderFilter && rows.none { it.isCurrent }) {
            val now = System.currentTimeMillis()
            val first = st.transcript.firstNotNullOfOrNull { (it as? UserMessage)?.text }?.trim().orEmpty()
            val cwd = guestWorkspace()
            val syntheticFile = hostFile ?: File(File(host.paths().agentDir, "sessions"), "${activeId ?: "current"}.jsonl")
            rows = listOf(
                SessionSummary(
                    file = syntheticFile,
                    id = activeId.orEmpty(),
                    cwd = cwd,
                    cwdShort = cwd,
                    pathShort = guestFile ?: syntheticFile.name,
                    startedAt = now,
                    lastActivityAt = now,
                    name = st.meta.sessionName,
                    firstMessage = first.ifEmpty { tr("新对话", "New Chat") },
                    messageCount = st.meta.messageCount,
                    parentSession = null,
                    isCurrent = true,
                ),
            ) + rows
            added = true
        }
        if (rows != cur.rows) {
            _sessionList.value = cur.copy(rows = rows, total = if (added) cur.total + 1 else cur.total)
            return true
        }
        return false
    }

    /**
     * 冷启动：宿主要几秒才起得来、SDK 还要逐个解析会话文件，这段时间先画**上次的列表**
     * （`SessionLibrary.writeCache`）。只在手上还没有行时用；宿主读完会换成新的。
     */
    private fun primeSessionList() {
        if (sessionListPrimed) return
        sessionListPrimed = true
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { sessionLibrary.readCache(_sessionList.value.query) } ?: return@launch
            if (_sessionList.value.rows.isEmpty() && !_sessionList.value.loading) {
                _sessionList.value = _sessionList.value.copy(rows = cached.rows, total = cached.total, fromCache = true)
            }
        }
    }

    /** 重读磁盘（引擎就绪、会话 / 工作区变了、手动刷新）；手上有行时静默，读完无声替换。 */
    fun refreshSessions() = loadSessionList(refresh = true)

    /** 切排序 / 命名过滤：宿主有缓存就只重算视图，没有才读磁盘。范围固定为全部，列表按工作区分组（见 SessionsScreen）。 */
    fun setSessionSort(sort: SessionSort) = changeSessionQuery(_sessionList.value.query.copy(sort = sort))
    fun setSessionNamedOnly(named: Boolean) = changeSessionQuery(_sessionList.value.query.copy(namedOnly = named))
    fun toggleSessionPath() {
        _sessionList.value = _sessionList.value.copy(showPath = !_sessionList.value.showPath)
    }

    private var sessionQueryJob: kotlinx.coroutines.Job? = null

    /** 搜索词（官方语法：`re:<正则>`、`"短语"`、模糊词）。输入防抖，宿主读的是缓存，所以很快。 */
    fun setSessionQuery(text: String) {
        val next = _sessionList.value.query.copy(query = text)
        _sessionList.value = _sessionList.value.copy(query = next)
        sessionQueryJob?.cancel()
        sessionQueryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180)
            loadSessionList(refresh = false)
        }
    }

    private fun changeSessionQuery(next: SessionListQuery) {
        if (next == _sessionList.value.query) return
        // 不清行、不转圈：宿主有缓存，换排序 / 过滤只是重算视图，行读完无声替换。
        _sessionList.value = _sessionList.value.copy(query = next)
        loadSessionList(refresh = false)
    }

    private fun loadSessionList(refresh: Boolean) {
        val generation = ++sessionListGeneration
        val requested = _sessionList.value.query
        sessionListJob?.cancel()
        // 手上有行时是后台刷新 / 换视图：不盖转圈、不清行，读完无声替换。
        _sessionList.value = _sessionList.value.copy(loading = true, loaded = 0, loadTotal = 0)
        sessionListJob = viewModelScope.launch {
            val read = runCatching { sessionLibrary.list(requested, refresh) }
            // 取消不是「读失败」；被新请求取代也不是（宿主对被取代的加载回 busy）。
            read.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (generation != sessionListGeneration) return@launch
            read.fold(
                onSuccess = { page ->
                    sessionListLoadedOnce = true
                    _sessionList.value = _sessionList.value.copy(
                        rows = page.rows,
                        total = page.total,
                        loading = false,
                        fromCache = false,
                        streamingPartial = false,
                        failed = false,
                        queryError = page.queryError,
                    )
                    applyActiveRow()
                    withContext(Dispatchers.IO) { sessionLibrary.writeCache(requested, page.raw, page.total) }
                },
                onFailure = {
                    // **保留手上那份列表**：读失败不该把已经显示出来的行也拿走。
                    _sessionList.value = _sessionList.value.copy(loading = false, streamingPartial = false, failed = true)
                },
            )
        }
    }

    /** 宿主的 `sessions.progress`：官方 `onProgress(loaded, total, partial)`。 */
    private fun onSessionLoadProgress(p: app.pi.session.SessionLoadProgress) {
        val cur = _sessionList.value
        if (!cur.loading || p.scope != cur.query.scope) return
        // 已有上次缓存的完整列表时，绝不用 loaded=1 的部分结果把整页替换成 1 条，
        // 保持缓存完整可见直到扫描结束再无声替换；无缓存首次扫描时则持续接收每一批进度行（1→10→20…）。
        val adoptPartial = p.rows != null && (cur.rows.isEmpty() || cur.streamingPartial)
        _sessionList.value = cur.copy(
            loaded = p.loaded,
            loadTotal = p.total,
            rows = if (adoptPartial) p.rows!! else cur.rows,
            fromCache = if (adoptPartial) false else cur.fromCache,
            streamingPartial = if (adoptPartial) true else cur.streamingPartial,
        )
    }

    /**
     * 官方 `/resume` 的「会话记录的 cwd 在这台设备上不存在」流程（`session-cwd.js`
     * `MissingSessionCwdError` / `formatMissingSessionCwdPrompt`）：宿主回 `cwd_missing`，
     * 这里把它变成一个确认框，选「继续」就以当前工作区为 `cwdOverride` 重试，选「分支」走 `forkFrom`。
     */
    sealed interface CwdMissingAction {
        data class Switch(val summary: SessionSummary, val guestPath: String) : CwdMissingAction
        data class Import(val stagedFile: File, val guestPath: String, val displayName: String) : CwdMissingAction
    }

    data class CwdMissingPrompt(val reason: String, val issue: String?, val action: CwdMissingAction)

    internal val _cwdMissing = MutableStateFlow<CwdMissingPrompt?>(null)
    val cwdMissing: StateFlow<CwdMissingPrompt?> = _cwdMissing.asStateFlow()

    internal val sessionOpSeq = java.util.concurrent.atomic.AtomicLong(0L)

    internal val _state = MutableStateFlow(UiState())
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
    /**
     * 记下这次工作区版本。
     *
     * **不在这里清草稿。** 以前 revision 一变就 `composerDraft.clear()`，切工作区时用户
     * 正在打的字和已经选好的图片会**无声消失** —— 而切工作区本身已经是"换项目"这件
     * 大事，不该顺带吃掉输入。草稿留在输入框里由用户自己决定：接着发（新工作区里发）、
     * 或者手动清掉。两个工作区的草稿互相覆盖并不比"被清空"更糟，因为清空是**静默的**、
     * 而覆盖是用户自己按了发送。
     */
    internal fun alignComposerToWorkspace(revision: Int) {
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
    internal val _theme = MutableStateFlow(PiResolvedTheme.fallback(systemDark = systemDarkAtStartup(app)))
    val theme: StateFlow<PiResolvedTheme> = _theme.asStateFlow()

    /**
     * Every theme name the picker may offer, with the file behind it.
     *
     * pi discovers `~/.pi/agent/themes` and the project's `.pi/themes` even when
     * the `themes` setting names neither (`resource-loader.ts:872-880`), so the
     * picker must not be limited to the setting's own list.
     */
    internal val _themeEntries = MutableStateFlow<List<PiThemeEntry>>(emptyList())
    val themeEntries: StateFlow<List<PiThemeEntry>> = _themeEntries.asStateFlow()

    /** The system appearance, remembered so the `a/b` pair can be re-resolved. */
    internal var lastSystemDark: Boolean = true

    fun startUiPreferences(systemDark: Boolean) = startUiPreferencesImpl(systemDark)
    fun refreshTheme(systemDark: Boolean = lastSystemDark) = refreshThemeImpl(systemDark)
    fun refreshPrefs() = refreshPrefsImpl()
    fun onSettingWritten(key: String) = onSettingWrittenImpl(key)


    // ------------------------------------------------- 运行时加速开关（当场生效）
    //
    // 用户原话：「只要在开关里点开开关，就自动重启切换。为什么还要退出软件重进呢？」
    //
    // 「写完这个开关该做什么」是纯逻辑，在 `ui/settings/RuntimeSwitchAction.kt`：判定、
    // 回滚目标、「只要还有引擎在跑，开关的值就必须等于它的运行时」这条不变量，以及状态行
    // 的那句话，都由 `runtime-switch` harness 在裸 JVM 上真跑。这里只有 IO 与重启。
    //
    // 为什么这一层在 ViewModel 而不是 `PiSettingsStack`（它已经持有 `RuntimeSelection`、
    // 状态行与重启入口）：探针要 10~60 秒，而设置目的地一旦离开组合就被销毁，
    // `rememberCoroutineScope()` 随之取消 —— 拨完开关就回对话页是完全正常的动作，那会把
    // 探针连同「要么切过去、要么回滚」的收尾一起丢掉，留下一个开着却什么都没发生的开关。
    // `viewModelScope` 活到 Activity 结束；这里也是唯一同时拿得到 `restartEngine`（私有的
    // `host`）与偏好存储的地方。

    /** 开关切换的进度，[RuntimeSwitchAction.Step.Idle] 表示没有动作在跑。 */
    internal val _runtimeSwitch = MutableStateFlow(RuntimeSwitchAction.Step.Idle)

    /**
     * 给设置页看的进度：`true == inFlight` 的取值让「运行时（实际生效）」那一行显示
     * 「正在测探针 / 正在重启」，值回到 [RuntimeSwitchAction.Step.Idle] 时那一行重新读
     * `RuntimeSelection.status()`——探针最长一分钟，没有这个状态，屏幕在那一分钟里什么都
     * 不说（用户会以为开关没反应，这正是这个改动要消灭的形状）。
     */
    val runtimeSwitch: StateFlow<RuntimeSwitchAction.Step> = _runtimeSwitch.asStateFlow()

    /** 最后一次写入的序号：探针跑完时用它判断这次写入是否已被后来的写入取代。 */
    internal var runtimeSwitchGeneration = 0

    /** 手上那一次切换。新的一次写入会取消它；取消不打断阻塞中的探针，见 [applyRuntimeSwitch]。 */
    internal var runtimeSwitchJob: Job? = null

    /**
     * 用户在设置里拨动了「运行时加速（实验性）」。
     *
     * 这个键**不在** pi 的 `settings.json` 里（`AppOnlySettingsStore` 拦下了它），它的
     * 权威副本是 `RuntimePreferences`；写入本身已经清了失败计数与缓存的探针结论
     * （`RuntimeSelection.setProrootEnabled` → `ProrootRetry`），也就是「关掉再打开＝重试
     * 一次」那件事 —— 这个改动把它从「下一次启动 guest 时重测」变成「现在重测、现在切换」。
     */
    fun activeRuntimeEngine(): app.pi.runtime.GuestEngine? = activeRuntimeEngineImpl()
    fun liveEngineState(): PiEngineSession.EngineState? = liveEngineStateImpl()
    fun selectRuntimeEngine(engine: app.pi.runtime.GuestEngine) = selectRuntimeEngineImpl(engine)
    fun repairRuntime() = repairRuntimeImpl()
    fun invalidateSettingsCache() = invalidateSettingsCacheImpl()

    // ----------------------------------------------------- extension UI state

    /**
     * Outstanding blocking requests. The queue — not [UiState] — is the authority
     * on what is still answerable; see [ExtensionDialogQueue] for the policy.
     */
    internal val dialogs = ExtensionDialogQueue()

    /** The countdown coroutine for the dialog named by [armedDialogId]. */
    internal var dialogTimeoutJob: Job? = null

    /**
     * Which dialog the running [dialogTimeoutJob] belongs to. Without this a
     * countdown tick would re-arm its own timer (cancelling the coroutine that is
     * ticking) and the countdown would never reach zero.
     */
    internal var armedDialogId: String? = null

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
     * publication through ([TOOL_UPDATE_THROTTLE_MS]), and the `bash` response replaces
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
    internal var composerFillSeq = 0L

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
    internal fun syncEngineService(engineAttached: Boolean, bootInProgress: Boolean) {
        val keepAlive = _state.value.prefs.keepAlive
        // 回合进行中也算"需要保活"：长构建被系统回收是用户报的那一屏最常见的来源，
        // 而 `app.runtime.keepAlive` 描述的是空闲时要不要继续挂着，不是"干活时要不要保护"。
        if (PiEngineLifecyclePolicy.shouldServiceRun(keepAlive, engineAttached, bootInProgress, turnRunning())) {
            return
        }
        // Only dismiss the foreground service & WakeLock; do NOT tear down a live engine!
        PiEngineService.stopServiceOnly()
    }

    /** 与 [reportWakeLockNeed] 同一组输入：引擎在忙、正在流式、或有一个工具在跑。 */
    internal fun turnRunning(): Boolean {
        val current = _state.value
        return current.engine == PiEngineSession.EngineState.Busy ||
            current.streaming ||
            current.busy != null
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
    internal fun reportWakeLockNeed() {
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

    fun workspaceEntries(): List<WorkspaceStore.Entry> = workspaceEntriesImpl()
    fun createWorkspace(name: String? = null): WorkspaceStore.Create = createWorkspaceImpl(name)
    fun createProjectInDirectory(parentDir: File, folderName: String): WorkspaceStore.Create = createProjectInDirectoryImpl(parentDir, folderName)
    fun registerProjectDirectory(dir: File): WorkspaceStore.Create = registerProjectDirectoryImpl(dir)
    fun renameWorkspace(name: String, label: String): WorkspaceStore.Rename = renameWorkspaceImpl(name, label)
    fun previewWorkspaceDelete(name: String): WorkspaceStore.Preview = previewWorkspaceDeleteImpl(name)
    fun deleteWorkspace(confirmation: WorkspaceStore.DeleteConfirmation): WorkspaceStore.Delete = deleteWorkspaceImpl(confirmation)
    suspend fun switchWorkspace(name: String, allowInterrupt: Boolean = true): WorkspaceSwitch = switchWorkspaceImpl(name, allowInterrupt)
    fun wouldInterruptTurn(): Boolean = wouldInterruptTurnImpl()


    /**
     * pi's **process** configuration, assembled from the App-side keys of
     * 设置 → 运行时与诊断 → 进程.
     *
     * pi has no settings keys for these — its `Settings` interface
     * (`core/settings-manager.ts:110-163` in 0.86.1) carries none of `offline`,
     * `cacheRetention`, `noContextFiles`, `noExtensions`, `noSkills`,
     * `noPromptTemplates` or `noThemes`; they exist only as CLI flags and environment
     * variables (`--offline` / `PI_OFFLINE=1`, `src/cli/args.ts:223`, `:433`;
     * `--no-context-files`, `:194`; `--no-extensions` / `--no-skills` /
     * `--no-prompt-templates` / `--no-themes`, `:169`, `:188`, `:190`, `:192`;
     * `PI_CACHE_RETENTION=long`, `packages/ai/src/api/anthropic-messages.ts:64`). So
     * the *keys* are ours and the *values* are pi's, handed over in [PiLaunchOptions]
     * because that is the only moment pi reads them. The authoritative table —
     * including which CLI knobs pi already covers with a settings key, and which are
     * meaningless on a phone — is `rpc/PiPreSpawnConfig.kt`
     * (`docs/pre-spawn-config.md`).
     *
     * **The two prompt rows are not here.** They were `--system-prompt` /
     * `--append-system-prompt` once; pi reads the same two values from
     * `<agentDir>/SYSTEM.md` / `<agentDir>/APPEND_SYSTEM.md`, and a flag on the command
     * line suppresses that discovery (`dist/core/resource-loader.js:475`). The rows edit
     * those files through [PromptFileSettingsStoreDecorator], which is also why changing
     * one does not need this process restarted: pi re-reads them when a session's
     * resource loader is rebuilt (`dist/core/agent-session-services.js:63-69`,
     * `dist/core/agent-session-runtime.js:116`).
     *
     * The four `--no-*` are passed 1:1 with pi and nothing else: opening
     * 「停用扩展发现」 also turns off the app's own shipped extensions, because pi's
     * `--no-extensions` stops discovery and the app deliberately passes no `-e`
     * (`rpc/PiLaunchOptions.kt` KDoc, `docs/pre-spawn-config.md` §2.2). Since pi
     * 0.99.0 it also turns off pi's **own built-in** extensions (`mcp`, `codemode`,
     * `tool-search`, `llama.cpp`), which used to sit outside that gate — see
     * [PiLaunchOptions.noExtensions] for the two measured releases.
     *
     * Only the file IO is here; the normalisation (`long` means long retention, a
     * missing boolean is false) lives in [PiLaunchOptions.fromSettingValues], where
     * the bare-JVM harness can execute it.
     *
     * Read fresh on every boot and every restart: a restart that replayed the
     * boot-time set would silently ignore a change made since.
     */
    /**
     * 这一次启动要传给 pi 的进程参数。
     *
     * @param continueSessionId 这一次启动**必须落在哪条对话**上（pi 的 session id）。只有
     *   `restartEngine`（工作区没有变的重启）会传它 —— 那是唯一一处「App 知道用户此刻在哪条
     *   对话上、而且这条对话在同一个 cwd 里」的时刻。冷启动（`boot`）与切工作区
     *   （`switchWorkspace`）都传 null：前者的进程里没有这个知识，后者换了 cwd、旧 id 不是
     *   `findById` 的答案（它的 cwd 过滤会用同一个 id 再建一个文件）。两条路都退到
     *   [PiLaunchOptions.continueMostRecent]（`-c`，受 `app.sessions.resumeLast` 管辖）。
     * @param resumeSessionPath 这一次启动要打开的**文件**（pi 的 `--session <path>`）。只有
     *   [resumeTarget] 判定「id 形式注定解析不到、而那个文件确实就是这一段会话」时才给 ——
     *   也就是 `findById` 的 cwd 过滤会漏掉、从而**再建一个同 id 文件**的那一格。
     *   与 [continueSessionId] 二选一，两者都给时 pi 先看路径（`PiLaunchOptions.resumeSessionPath`）。
     */
    internal fun launchOptions(
        continueSessionId: String? = null,
        resumeSessionPath: String? = null,
    ): PiLaunchOptions = PiLaunchOptions.fromSettingValues(
        offline = null,
        cacheRetention = settingsStore.readString("app.runtime.cacheRetention")?.takeIf { it.isNotBlank() },
        noContextFiles = null,
        noExtensions = null,
        noSkills = null,
        noPromptTemplates = null,
        noThemes = null,
        extensionArgs = extensionArgsStore.read(),
        continueSessionId = continueSessionId,
        resumeSessionPath = resumeSessionPath,
        continueMostRecent = resumeLastEnabled(),
    )

    /**
     * `app.sessions.resumeLast`：**冷启动是否接回同一工作区的最近一段**（argv 里的 `-c`）。
     *
     * 默认值只有一处：注册表那一行（`PiSettingsCatalog`，`defaultValue = bool(true)`，用户裁决）。
     * 老写法是「`readBoolean` 加一个 `?: false` 兜底」—— 而 `readBoolean` 对**没写过**的键回 null，
     * 所以「默认翻成开」如果只改注册表，运行时仍按关处理：设置页显示「开」、行为却是「每次启动
     * 新对话」。`boolIn` 正是「显式值优先、否则用注册表默认值」的那一处实现。
     *
     * 它只在**冷启动**这条路上说话：进程内重启由 [launchOptions] 的 `continueSessionId` 精确
     * 钉住，与这个开关无关（id 优先于 `-c`）。
     */
    private fun resumeLastEnabled(): Boolean = runCatching {
        PiSettingsCatalog.settings
            .firstOrNull { it.key == RESUME_LAST_SETTING_KEY }
            ?.boolIn(settingsStore)
    }.getOrNull() ?: false

    /**
     * 这一次（重新）启动该把哪一段对话交出去 —— **id 形式，还是那个文件**。
     *
     * `--session-id` 的前提比它看上去强：pi 的 `SessionManager.findById` 要求
     * **① id 相等 ∧ ② header 的 `cwd` 经 `resolvePath` 后等于这个即将启动的进程的 cwd ∧
     * ③ 文件是 `--session-dir` 那一层的直接子文件**（`core/session-manager.js:1421-1441`、
     * `:441-443`）。三条里漏一条它不是报错，而是**用同一个 id 新建一个文件**
     * （`dist/main.js:344-351`），此后每条消息都写进那个新文件 —— 于是一段对话变成两个文件、
     * 两个都顶着同一个 id。列表**两行都留**（SDK `SessionManager.list` 一个文件一行、不按 id 去重），名字各取自己文件里的第一条 user 消息 —— 这正是用户看到的「名字变成半截开头的
     * 那条」；而在这一条 argv 修好之前，旧版本的列表按 id 只留一行，剩下的那半截看上去就是整段对话
     * （「往上翻，翻到头也没有了」「转录顶部没有『加载更早』」—— 那个文件确实是从那儿开始的）。
     *
     * 第 ② 条在真机上真的会漏：proroot 的 `getcwd()` 会泄漏宿主拼写
     * （`docs/proroot-mode-audit.md:20-48`），所以「同一个目录的两种拼法」写入的会话，在另一种拼法
     * 下 `findById` 的**字符串**比较不过（pi 的 `resolvePath` 不做 realpath）。切换运行时、
     * 重建运行时、换工作区名字，都会翻转这个拼写。
     *
     * 所以这里把「App 知道的文件事实」读出来，交给纯逻辑
     * `app.pi.session.resumeTargetFor` 判定（判定与断言都在 `SessionResume.kt` /
     * `SessionResumeCheck.kt`）：**只有当 pi 的 `findById` 一定、且唯一地命中这个文件时才留在 id
     * 形式**（那一格 argv 与以前逐字相同），其余「App 能为这个文件担保、而 pi 会找不到或会给出
     * 另一份」的格子改成把文件路径交给 pi。**唯一性**是第二条理由：`findById` 取的是 `readdirSync`
     * 顺序里的第一个命中，而不是 mtime 最新的那个 —— 同一个 id 有两份（真劈开的两半都躺在平铺层）
     * 时它可能给出另一份，用户重启后会看到另一个半截、新消息也写进那一份（`countIdLookupCandidates`
     * 数这个，见它的 KDoc）。
     *
     * 与 [resolveSessionFile] 同一个纪律：**不许拿一个 App 说不清的文件去开**。映射不出 host 文件、
     * header 读不出来、header 的 id 不是本机 `get_state` 报的那一个 —— 一律退回 id 形式
     * （也就是今天的行为），因为拿错文件去开等于把用户换到另一段对话里。
     */
    private suspend fun resumeTarget(): ResumeTarget {
        val meta = _state.value.meta
        val guestPath = meta.sessionFile
        return withContext(Dispatchers.IO) {
            val file = hostSessionFile(guestPath)
            val header = file?.let { runCatching { SessionFileReader.readHeader(it) }.getOrNull() }
            fun field(key: String): String? =
                (header?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val root = File(host.paths().agentDir, "sessions")
            val flat = file?.parentFile?.absolutePath == root.absolutePath
            val launchCwd = guestWorkspace()
            val id = meta.sessionId
            // 「pi 的 `findById` 会不会唯一地命中这个文件」只在文件确实躺在那一层里时才需要问；
            // 其余情况 [resumeTargetFor] 的 flat 判据已经定了路径形式。**算不出来就按 `false` 传**
            // （走路径）—— 那是安全的一边：多走一次精确形式，不会藏掉任何东西。
            val unique = id != null && flat &&
                countIdLookupCandidates(root, id, launchCwd) { SessionFileReader.readHeader(it) } == 1
            resumeTargetFor(
                requestedId = id,
                guestPath = guestPath,
                fileExists = file != null,
                flatChildOfSessionDir = flat,
                headerId = field("id"),
                headerCwd = field("cwd"),
                launchCwd = launchCwd,
                idResolvesUniquely = unique,
            )
        }
    }

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
     * Which of pi's two entries this process's engine was last launched on, as
     * `PiEngineHost.EngineEntry.label` (`dist/bundle/index.js` or the unpacked
     * `dist/index.js` fallback), or null before the first launch.
     *
     * The host records it at launch; the diagnostic report prints it **whether or not the
     * engine has exited**, which is the whole point — a startup-time figure with no entry
     * beside it cannot be read (the two entries differ by an order of magnitude).
     */
    fun engineEntryLabel(): String? = host.lastEngineEntry?.label

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

    /**
     * Boot the engine, optionally through the **explicit repair path**.
     *
     * @param rebuild true only for the 「重建运行时」 action on the boot-failure card:
     *        it deletes the volatile runtime tree and extracts every payload again, so
     *        everything installed inside the guest (apt/pip packages, `npm -g`,
     *        `/usr/local/bin`, `/root` outside the bound agent dir) goes with it. The
     *        workspace, sessions, settings and credentials are outside that tree and
     *        survive. Forwarded verbatim to `PiEngineHost.boot(rebuild = …)`, which is
     *        the only path in the app that may delete the tree; a payload change never
     *        reaches it.
     */
    fun boot(rebuild: Boolean = false, forceRepair: Boolean = false) {
        if (_state.value.boot is Boot.Working) return
        // 「重试」不是"再跑一遍同样的东西"：运行环境那类失败第一次重试要走不删数据的修复
        // （重新解包），否则戳记相符的快路径会让它永远返回成功、引擎永远起不来。
        val retryMode = if (rebuild) {
            RetryMode.Plain
        } else {
            (_state.value.boot as? Boot.Failed)?.failure?.retryMode(provisioningRepairAttempted)
                ?: RetryMode.Plain
        }
        val repair = forceRepair || retryMode == RetryMode.Repair
        if (repair) provisioningRepairAttempted = true
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
            // **安装运行时/冷启动这一段无条件开前台服务**：它是整条链上最容易被系统打断的
            // 窗口（解包几十秒、node 冷启动），被打断就留下半棵树 —— 那正是下一次"重建"
            // 的来由。`keepAlive` 只决定**空闲之后**还留不留着。
            startEngineService()
            // Unpacking the runtime and pi's cold start are CPU-bound with nothing on
            // screen, so the wake lock has to be held across them; the service takes
            // it when it starts (`startForegroundWithNotification`), and the engine
            // state collector releases it once the engine is up and idle
            // (`reportWork`).
            engineTransition = true
            // **冷启动不钉 id**：这里交给 pi 的是 `-c`（[launchOptions] 的 `continueMostRecent`，
            // 受 `app.sessions.resumeLast` 管辖），由 pi 自己按「同一 cwd + mtime 最近」挑一段
            // （`session-manager.ts:636-653`）。要钉 id 的是 `restartEngine`：只有进程内重启那一刻，
            // App 才既知道用户在哪条对话上、又保证 cwd 没变。这条 `boot()` 同时是崩溃后「重试」按钮
            // 走的路，那条路也交给 `-c` —— 与这一行开关对用户的说法一致（关掉就是"每次启动新对话"）。
            val launchOptions = launchOptions()
            reportExtensionArgsRefusal(launchOptions)
            val boot = try {
                host.boot(
                    workspaceProvider = ::defaultWorkspace,
                    launch = launchOptions,
                    rebuild = rebuild,
                    repair = repair,
                ) { step ->
                    _state.value = _state.value.copy(boot = Boot.Working(step))
                }
            } finally {
                engineTransition = false
            }
            when (boot) {
                is PiEngineHost.Boot.Ready -> attach(boot.session)
                is PiEngineHost.Boot.Failed -> {
                    _state.value = _state.value.copy(
                        boot = Boot.Failed(EngineFailureClassify.provisioning(boot.message, boot.detail)),
                    )
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
        // **接回只有一条路：argv。** `host.restart` 的 `launch` 实参在进程被换掉之前求值
        // （下面 `launchOptions(continueSessionId = …)` / `launchOptions(resumeSessionPath = …)`），
        // pi 就带着 `--session-id <id>` 或 `--session <path>` 起来：前者在 `findById` 找到那个 id 时
        // **追加**、找不到就用同一个 id 新建（`main.ts:435-447`、`core/session-manager.ts:938-961`），
        // 后者直接打开那个文件、**没有 cwd 过滤**（`main.ts:337-345`）⇒ 重启用的是同一条对话，
        // 不需要事后再发一条 `switch_session`。用户报的「一个对话被切成好几段」正是从"每个重启
        // 入口各自补一次 `switch_session`"的缝隙里来的，所以这里删除那套机制、只留 argv。
        //
        // **两种形式二选一，由 [resumeTarget] 判定**，因为「找不到就用同一个 id 新建」在
        // `findById` 的三条判据（id ∧ header 的 cwd 等于引擎 cwd ∧ 文件在平铺层）漏掉一条时
        // 会**再建一个同 id 的文件**：一段对话变成两个文件、两个都顶着同一个 id，App 的列表按 id
        // 去重后只剩其中一个 —— 用户报的「重装完只剩后面这一半截、名字也改成半截开头的名字」。
        // 那一格必须走路径形式（`--session <path>`），其余情况一律保持 `--session-id` 逐字不变。
        //
        // **抓在手里再换进程**：`host.restart` 一进去旧引擎就退场（`Stopped` 那一支会清
        // `api`/`session`），期间的 `meta` 谁先写没有约束；把 `_state.value` 读在 `host.restart`
        // 的实参位置上，就多了一处「求值顺序由别人决定」的假设。所以 [resumeTarget] 在这里求值，
        // 它读的 `meta` 就是「换进程之前的那一份」。
        //
        // 覆盖范围：本函数是进程内重启的唯一入口（全仓对该 host 调用的只有两处，另一处在
        // `switchWorkspace` —— 换了 cwd 就该是新会话，那里**不**传 id），运行时开关（proroot
        // 开/关）与装包走的是 `EngineRestartCoordinator` → `PiRoot.kt:787` → 这里。
        //
        // The old engine publishes `Stopped` while it is being retired, and
        // `session` still points at it — the state collector cannot tell that apart
        // from a crash. [engineTransition] is what tells it: without this the
        // collector would stop the foreground service in the middle of a restart,
        // i.e. drop the wake lock across the *new* engine's cold start.
        //
        // 这一行在 `engineTransition = true` **之前**、也在 `host.restart` 之前，见上面那段
        // 「抓在手里再换进程」。**它现在回答的是「用哪种形式钉住」而不是「哪个 id」**：
        // 只有 pi 的 `findById` 真的能找到那个文件时才发 `--session-id`
        // （`app.pi.session.SessionResume.kt` 的 KDoc 与 [resumeTarget]），否则发文件路径 ——
        // 因为那一格 pi 的行为是「用同一个 id 再建一个文件」，也就是用户报的「只剩后面这一半截」。
        val resume = resumeTarget()
        engineTransition = true
        val result = try {
            host.restart(
                reason = reason,
                workspaceProvider = ::defaultWorkspace,
                allowInterrupt = allowInterrupt,
                launch = when (resume) {
                    is ResumeTarget.ById -> launchOptions(continueSessionId = resume.id)
                    is ResumeTarget.ByPath -> launchOptions(resumeSessionPath = resume.guestPath)
                    ResumeTarget.None -> launchOptions()
                },
            )
        } finally {
            engineTransition = false
        }
        if (result is PiEngineHost.Restart.Ok) {
            // 接回**不需要在这里做**：这一次 `host.restart` 的 argv 里已经带了 `--session-id`
            // 或 `--session`（上面那次 `launchOptions(…)`），新引擎启动时就落在同一条对话上。
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

    internal fun attach(engine: PiEngineSession) {
        // Anything still pending belongs to the *previous* engine (a boot after a
        // failure, or a restart). It can never be answered now, so answer it
        // cancelled against the old session before this one's events arrive —
        // otherwise a dialog from the dead session would be shown against a live
        // engine whose request ids it does not match. Runs before `session` is
        // replaced, which is the whole point.
        cancelAllDialogs()
        clearExtensionChrome()

        session = engine
        api = engine.api
        // 扩展 UI 的状态直接来自引擎的归并器；换引擎时 [clearExtensionChrome] 已经清空了上一份。
        extUiJob?.cancel()
        extUiJob = viewModelScope.launch {
            engine.extUi.collect { ext -> _state.value = _state.value.copy(ext = ext) }
        }
        // 宿主状态快照（debounced `state` 事件 + 所有变更命令的 `withState` 应答）直接同步上下文用量、
        // 累计 token、费用与消息数，不再等用户点开详情页或靠 `delay(700)` 补读 `session.stats`。
        hostStateJob?.cancel()
        hostStateJob = viewModelScope.launch {
            engine.commands.stateStore.state.collect { hostState ->
                if (session !== engine || hostState == null) return@collect
                val prev = _state.value.stats
                val next = hostState.toSessionStats(previousToolResults = prev?.toolResults ?: 0)
                if (next != prev) {
                    _state.value = _state.value.copy(stats = next)
                }
            }
        }
        // 官方渲染组件自己要求重绘（`ui.requestRender()`：动画、定时刷新）→ 清缓存、换一版，卡片重取。
        entryProbeJob?.cancel()
        entryProbeJob = viewModelScope.launch {
            _state.map { it.transcript }.distinctUntilChanged().collect { probeEntryTypes(it) }
        }
        renderInvalidateJob?.cancel()
        renderInvalidateJob = viewModelScope.launch {
            engine.hostEvents.collect { ev ->
                if (ev.name == "ui.cancel") {
                    // 宿主收回一个挂起对话框（超时 / AbortSignal / 会话替换）：立刻移出队列，避免死弹窗滞留屏幕。
                    val cancelId = ((ev.data as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (cancelId != null && dialogs.remove(cancelId) != null) {
                        publishDialogs()
                    }
                }
                if (ev.name == "project.trust.required") {
                    // 官方弹窗：项目里有要信任才加载的东西、又没人表态。事件只发一次，
                    // 错过（界面还没挂上）就再也问不到，所以状态存在这里，由 [ProjectTrustHost] 显示。
                    refreshTrustPrompt()
                }
                if (ev.name == "sessions.progress") {
                    sessionLibrary.parseProgress(ev.data)?.let(::onSessionLoadProgress)
                }
                if (ev.name == "catalog.changed") {
                    // 宿主重载了（扩展 runner 重建、技能 / 模板 / 主题重读）：命令表、工具表、资源与渲染器都可能变了。
                    // 任何入口触发的重载都走到这里，面板不再只在 attach / agent_settled 才更新。
                    toolRenderCache.reset()
                    messageRenderCache.reset()
                    entryRenderCache.reset()
                    markdownTransformCache.reset()
                    _renderRev.value += 1
                    refreshCommands()
                    refreshModels() // 扩展可以注册供应商 / 模型
                    refreshState() // 工具表、扩展绑定都在宿主的状态快照里
                }
                if (ev.name == "render.invalidate") {
                    val toolCallId = ((ev.data as? JsonObject)?.get("toolCallId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (toolCallId != null) {
                        // 定向失效单张工具卡，避免一个异步动画把全会话 200 张卡的缓存清空。
                        toolRenderCache.invalidateTool(toolCallId)
                    } else {
                        toolRenderCache.reset()
                        messageRenderCache.reset()
                        entryRenderCache.reset()
                    }
                    _renderRev.value += 1
                }
                if (ev.name == "settings.changed") {
                    invalidateSettingsCache()
                    refreshPrefs()
                    refreshTheme()
                    refreshCommands()
                    seedAutoRetryFromSettings()
                }
            }
        }
        // 事件可能在界面订阅之前就发过了：引擎就绪后延迟问一次信任状态，避免与 replayHistory 争抢单线程 Node。
        viewModelScope.launch {
            engine.state.first { it == PiEngineSession.EngineState.Ready || it == PiEngineSession.EngineState.Busy }
            if (session === engine) {
                if (_overlay.value?.slot == OverlaySlot.SessionList) refreshSessions()
            }
        }
        // A fresh engine is exactly the moment the settings documents may have
        // changed on disk, so the cache is dropped here rather than left showing
        // pre-write values. See [invalidateSettingsCache].
        invalidateSettingsCache()
        // 引擎真的起来了：连续失败的计数归零。放在 `Boot.Ready` 这一句旁边，是因为
        // "起来了"就是它的判据 —— 不能放在 `boot()` 的开头（那时还不知道会不会失败）。
        autoEngineRestarts = 0
        provisioningRepairAttempted = false
        // **换了引擎就是换了会话：先把上一段对话的投影清空。**
        //
        // 以前这里只把 `boot` 改成 Ready，转录（`transcript`/`revision`/`streaming`）留着
        // 等新引擎的第一条 publication 来覆盖。那条路依赖 `pub.replaced`/`appliedRevision`
        // 的时序：只要新引擎的第一帧没有以 `replaced = true` 抵达（或者被 `session !== engine`
        // 的守卫吞掉），屏幕上就会**一直留着旧对话**，而模型那边是全新的会话 —— 用户报的
        // 「切完工作区，老对话还在屏幕上，发消息却像新会话」就是这一格。
        //
        // 现在无条件清空：宁可让新会话先空一帧（紧接着 `get_entries` 就把它的内容补上），
        // 也不能让一段已经不属于当前引擎的对话继续显示。
        _state.value = _state.value.copy(
            boot = Boot.Ready,
            transcript = emptyList(),
            revision = 0,
            streaming = false,
            busy = null,
            bash = null,
            nowMs = null,
            lastUsage = null,
            turnUsage = null,
        )
        appliedRevision = 0
        // The queue counters describe **this engine process's** queues, and the engine in
        // hand is brand new, so its queues are empty. pi sends `queue_update` only when
        // the queue changes — `_emitQueueUpdate` (`core/agent-session.js:655`) is called
        // on a steer enqueue (`:1767`), a follow-up enqueue (`:1783`), the removal of a
        // message as it is delivered (`:740` / `:747`) and by `clearQueue` (`:1920`),
        // which is the whole set — so a fresh process
        // never re-states the empty queue — and the counters would keep saying 「排队中 N」
        // about messages no pi process is holding. Reachable by: queue a message while a
        // turn runs, switch workspace (the switch kills that turn), land in the new one.
        //
        // Why this does **not** duplicate [afterSessionReplaced]'s copy of the same reset,
        // and cannot conflict with it: the two answer different facts. That one is 「the
        // engine's session was replaced」 — the five RPC commands (`newSession` /
        // `switchSession` / `importSession` / fork / clone): same process, new session
        // file, and pi re-binds the queues without the app hearing about it. This one is
        // 「the engine is a different process」 — workspace switch, restart, rollback, cold
        // boot — where the queue cannot survive by construction. The two sets of events are
        // disjoint (no caller of [afterSessionReplaced] restarts the engine, and [attach] is
        // never reached by a session-only switch), and even if a future path triggered both,
        // this is an idempotent write of the same value. Dropping either one re-opens its own
        // leak; the session half's argument is on the [UiState.queueSteering] note there.
        if (_state.value.queueSteering != 0 || _state.value.queueFollowUp != 0) {
            // The texts go with the counts: both are written by the same
            // `queue_update` arm ([onEvent]), so clearing one without the other
            // would leave the pending row drawing a message pi no longer holds.
            _state.value = _state.value.copy(
                queueSteering = 0,
                queueFollowUp = 0,
                queueSteeringTexts = emptyList(),
                queueFollowUpTexts = emptyList(),
            )
        }
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
            // 本次收集里**上一个**状态。用收集器自己的局部量而不是 `_state.value.engine`：引擎重启时
            // 旧引擎的收尾（`Stopped`）会因为 `session !== engine` 被丢掉，`_state` 里留下的可能是旧
            // 引擎的 `Ready`，那样「刚起来」这件事就看不出来了。局部量从现在这个引擎的第一条发射
            // 开始数，语义就是「这个引擎刚起来的第一次」。
            var previousState: PiEngineSession.EngineState? = null
            engine.state.collect { engineState ->
                // Only the engine that is still current may write state. A
                // restart attaches a new engine while the old one's collector is
                // still alive, and `PiEngineSession.close()` publishes `Stopped`
                // — that value can be delivered after
                // `attach` has already installed the new session, which would
                // null out the fresh `api` and leave the whole UI inert.
                if (session !== engine) return@collect
                val wasEngineState = previousState
                previousState = engineState
                _state.value = _state.value.copy(engine = engineState)
                // 引擎**刚起来**时补一次 `get_session_stats`（`Starting` → `Ready`，以及引擎重启
                // 之后的第一帧）。
                //
                // **这不是「每轮一次」的那一次，也没有让它变成两次。** 每轮结束的那一次在
                // `PiEvent.AgentSettled` 那一段（`:2740` 的 `refreshStats()`，F10 本来就有的）；
                // 这里必须把 `Busy → Ready` 排除掉 —— 那**也是**「变成 Ready」（`PiEngineSession.kt:673`
                // 在 `agent_settled`/`agent_end` 上把状态写回 `Ready`），不排除就是每轮读两次。
                // 所以条件是「进入 Ready，且上一个状态不是 Busy」，也就是只有这个引擎的
                // `Starting` → `Ready`（收集器里的第一条 `Ready`）才命中：**每个引擎一次**。`engine.state` 是 StateFlow，相等的值
                // 不会重复发射，所以也不存在「每一帧一次」。
                //
                // 为什么非取不可：环与详情页那些数字唯一的源就是它。`get_state` 不带 contextUsage
                // （`modes/rpc/rpc-mode.ts:450-466` 的 `RpcSessionState` 里没有这个字段），唯一的
                // 提供者是 `getSessionStats`（`core/agent-session.ts:3407` 的
                // `contextUsage: this.getContextUsage()`）。而这个值原来只在**用户打开详情面板**时
                // 才取，于是没点过面板的人看到的百分比一直是 `?`，点一次（哪怕立刻关掉）之后环才开始
                // 工作 —— 用户报的就是这个：「原来不点不工作，点一次在外面才正常工作」。
                //
                // 修好之后 `?` 只剩 D27 那**一个**含义：压缩之后到下次回复之前 pi 真的报不出 percent
                // （`agent-session.ts:3441-3445` 返回 `{tokens:null, percent:null}`，而 contextWindow
                // 仍在）。把「还没取过 stats」也用 `?` 表示，是把 pi 的拼法扩用到了另一个含义上。
                //
                // 频率：`getSessionStats` 要遍历整份会话算累计（`agent-session.ts:3359-3408`），
                // 所以「每次引擎启动一次 + 每轮一次」是它能承受的上限，也是它一直以来的口径。
                if (engineState == PiEngineSession.EngineState.Ready &&
                    wasEngineState != PiEngineSession.EngineState.Busy &&
                    _state.value.stats == null
                ) {
                    refreshStats()
                }
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
                    val capturedStderr = engine.stderrSnapshot()
                    lastEngineExit = EngineDiagnostics(
                        state = engineState.name,
                        exitCode = engine.lastExitCode,
                        stderr = capturedStderr.ifBlank { null },
                        startupMs = PiEngineSession.lastServingMs,
                        droppedEvents = engine.droppedEvents,
                        reducerFailures = engine.reducerFailures,
                        lastRecordProblem = engine.lastRecordProblem,
                    )
                    bashStream.setLength(0)
                    lastBashPublishAt = 0L
                    bashStreamTrimmed = false
                    val crashedMidSession = !engineTransition
                    val recovery = if (crashedMidSession) {
                        EngineCrashRecovery.decide(
                            attempts = autoEngineRestarts,
                            exitCode = engine.lastExitCode,
                            stderr = capturedStderr,
                        )
                    } else {
                        EngineCrashRecovery.Decision.GiveUp
                    }
                    val autoRestarting = recovery == EngineCrashRecovery.Decision.Restart
                    _state.value = _state.value.copy(
                        bash = null,
                        busy = null,
                        streaming = false,
                        boot = if (autoRestarting) {
                            _state.value.boot
                        } else Boot.Failed(
                            EngineFailureClassify.exit(
                                exitCode = engine.lastExitCode,
                                stderr = capturedStderr,
                                detail = EngineExitCause.detail(
                                    engine.lastExitCode,
                                    capturedStderr,
                                    extensionArgsStore.read(),
                                ),
                            ),
                        ),
                    )
                    if (autoRestarting) {
                        autoEngineRestarts += 1
                        pushNotice(
                            tr("引擎已退出，正在自动重启（第 $autoEngineRestarts 次）…", "Engine exited; auto-restarting (attempt $autoEngineRestarts)…"),
                            Notice.Tone.Warning,
                        )
                        viewModelScope.launch {
                            delay(EngineCrashRecovery.RESTART_DELAY_MS)
                            // 等一下再起：立刻 fork 一个 pi 会在同一份内存压力下再死一次。
                            // 只有此刻仍然没有引擎时才起 —— 期间用户可能已经点了重试、
                            // 换了工作区，或者别的路径把它拉起来了。
                            // 这一条是**没人看着**的路径：失败也必须变成状态，而不是一个
                            // 未捕获异常（`viewModelScope` 的默认处理器会直接把 App 带走）。
                            if (session == null) runCatching { boot() }
                        }
                    } else if (!engineTransition) {
                        // Not a restart: nothing is replacing this engine, so the
                        // foreground service has nothing left to protect a retry
                        // (the new failure screen's button) is a *new* engine.
                        // During a restart that is not true, which is what
                        // `engineTransition` is for.
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
            // 见 [awaitServing]：宿主 `session.open` 应答前所有读取都会被拒。
            if (!awaitServing(engine)) return@launch
            // The replay projects the whole session. `seedHistory` suspends now, so
            // this no longer blocks the frame thread — but the screen would sit on
            // the previous (usually empty) transcript with no explanation. `busy` is
            // already the AppBar's second line (`engineLabel`), and the switch /
            // fork / clone replays get it from `call`; this attach-time replay is the
            // one that never did. Cleared token-matched, exactly like `call`'s
            // `finally`, so a newer operation's label is never wiped.
            // **刷新状态在 replay 之前**，与 [afterSessionReplaced] 同一条规则（那句
            // KDoc 就是权威版本：replay 的源文件只能由 `get_state` 刚报出来的那个名字决定）。
            //
            // 这一条以前在 attach 里是**反的**（replay 先、refreshState 后），后果正是「一个对话
            // 被切成好几段」：进程内重启时 `_state.meta` 还指着**上一个引擎**的会话文件，而
            // `resolveSessionFile()` 信任 meta（`meta.sessionId` 与那个文件的 header id 一致，
            // 所以校验通过）⇒ 屏幕重新画的是**老对话**，pi 却已经在一个**全新的空会话**上。
            // 用户继续打字，消息落进新文件，列表里于是多出一段标题不同的「版本」。
            //
            // 冷启动的代价是**中性**的：那时 meta 本来就是空的，replay 会走 `get_entries` 那条
            // RPC 回落；现在先问一次 `get_state`、读的是新会话自己的文件（多半只有一行头），仍然
            // 回落一次 —— 一次 RPC 换一次 RPC。
            //
            // 它在 `busy = "正在加载会话"` **之前**：`refreshState` 走 `call`，会先把自己的标签
            // （「读取会话状态」）写上、再在 finally 里清掉；放在它后面写，外层那句会被那次清理
            // 抹掉，replay 期间顶栏什么都不说。
            // **必须 await**：`refreshState` 只是排了一次协程，紧接着的 `replayHistory`
            // 会用**旧 meta** 去挑会话文件 —— 切工作区后重新画出老对话、要发一句消息才切换，
            // 就是这一格。`refreshStateNow` 的 KDoc 是权威版本。
            api?.let { refreshStateNow(it) }
            // 同一个 `claimBusy`/`releaseBusy` 令牌（见那对函数的 KDoc）：这条路的标签也是
            // **这一次 replay 的**，所以只有它还握着这行时才允许清掉。
            val replayToken = claimBusy(tr("正在加载会话", "Loading session"))
            try {
                replayHistory(engine)
            } finally {
                releaseBusy(replayToken)
            }
            refreshCommands()
            seedAutoRetryFromSettings()
            // The agent dir exists for sure by now, so a theme dropped into it
            // while the app was closed is discovered here as well.
            refreshPrefs()
            refreshTheme()
            // 主对话已就绪：稍作错峰再问信任状态与预热会话列表，避免与后台 bindExtensions 争抢单线程 Node；
            // 若用户提前点开会话列表，ensureSessionList 会立即拉取（且冷启动已有磁盘缓存瞬时展示）。
            viewModelScope.launch {
                delay(400)
                if (session === engine) refreshTrustPrompt()
                delay(800)
                if (session === engine && !sessionListLoadedOnce && !_sessionList.value.loading) {
                    refreshSessions()
                }
            }
            // **没有"接回"这一步了。** 会话由 argv 钉住（[launchOptions] 的
            // `continueSessionId` / `continueMostRecent`）：进程内重启带 `--session-id` 起来，
            // 冷启动带 `-c`（受 `app.sessions.resumeLast` 管辖）。所以这里既不需要事后
            // `switch_session`，也不需要清理"这次新建的空会话"——pi 在没有 assistant 消息时根本
            // 不落盘（`_persist` 的 `hasAssistant` 守卫，`session-manager.ts:1029-1052`）。
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
    internal fun syncTranscript(engine: PiEngineSession, pub: PiEngineSession.TranscriptPublication) {
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
                output = item.output.ifEmpty { tr("回合已结束，未收到工具结果（被停止或出错）", "Turn ended without a tool result (aborted or failed)") },
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

    internal fun onEvent(event: PiEvent) {
        when (event) {
            is PiEvent.QueueUpdate -> _state.value = _state.value.copy(
                queueSteering = event.steering.size,
                queueFollowUp = event.followUp.size,
                queueSteeringTexts = event.steering,
                queueFollowUpTexts = event.followUp,
            )

            // The window in which pi refuses a plain `prompt`
            // (`core/agent-session.js:1619`). Kept
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
                        (event.message?.takeIf { it.isNotBlank() } ?: tr("pi 没有给出详情", "No details provided by pi")),
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
                if (lastBashPublishAt != 0L && now - lastBashPublishAt < TOOL_UPDATE_THROTTLE_MS) {
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

    fun answerDialog(id: String, answer: ExtensionAnswer) = answerDialogImpl(id, answer)
    fun consumeComposerFill(seq: Long) = consumeComposerFillImpl(seq)
    fun consumeNotice(seq: Long) = consumeNoticeImpl(seq)

    /**
     * Queue a snackbar. Bounded because notifications are advisory — `docs/rpc.md`
     * says the client "can display the information or ignore it" — so the oldest
     * is dropped when nothing is on screen to show them. Dialog requests are
     * never dropped: they are the blocking half and live in their own queue.
     */
    /**
     * Say, in words, when `app.extensions.args` could not be handed to pi.
     *
     * The parser refuses the same texts pi would have ended the run over — a
     * single-dash option, an `@file`, `--`, a stray word (`ExtensionFlagArgs.Refusal`)
     * — and the honest consequence is that **nothing** from the row is passed
     * (`PiLaunchOptions.extensionFlags` is empty). Saying so here, at the moment the
     * engine is asked to start, is what keeps that from looking like "my flag was
     * ignored". It is a notice rather than a boot failure: the engine is fine.
     */
    private fun reportExtensionArgsRefusal(launch: PiLaunchOptions) {
        launch.extensionArgsRefusal?.let { pushNotice(message = it, tone = Notice.Tone.Warning) }
    }

    internal fun pushNotice(message: String, tone: Notice.Tone) {
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
            tr("这个会话还太小，没有可压缩的内容（pi：$reason）", "Session is too small to compact (pi: $reason)")

        reason.contains("Already compacted", ignoreCase = true) ->
            tr("这个会话已经压缩过了，没有新的内容可压（pi：$reason）", "Session is already compacted with nothing new to compact (pi: $reason)")

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
    internal fun fail(message: String) {
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
    internal enum class NoEngine {
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
    /**
     * 引擎的"会话已建好"闸门。
     *
     * SDK 宿主在 `session.open` 应答之前拒绝一切读写（`NOT_OPEN`，「会话还没有建立」）；RPC 时代命令
     * 排在管道里自然等着，现在没有这个缓冲。所以**所有以会话为前提的读取/命令**都经过这里：
     * 引擎就绪（Ready/Busy）返回 true，引擎已死（Failed/Stopped）返回 false，其余状态一直等
     * （手机冷启动可能很慢，那是慢不是坏）。
     */
    private suspend fun awaitServing(engine: PiEngineSession): Boolean {
        val reached = engine.state.first {
            it == PiEngineSession.EngineState.Ready || it == PiEngineSession.EngineState.Busy ||
                it == PiEngineSession.EngineState.Failed || it == PiEngineSession.EngineState.Stopped
        }
        if (session !== engine) return false
        return reached == PiEngineSession.EngineState.Ready || reached == PiEngineSession.EngineState.Busy
    }

    internal fun call(
        label: String,
        noEngine: NoEngine = NoEngine.Notify,
        /**
         * Optional translation of a failure into something the user can act on.
         *
         * The default is [Throwable.message], which for a [HostCommandException] is pi's
         * own English sentence prefixed with the command name — correct, but it
         * names the *symptom* and not one thing to do next. A command whose
         * failures have a small, knowable set of causes passes a mapper here
         * instead; pi's own text is kept inside the sentence, never replaced.
         */
        errorText: ((Throwable) -> String)? = null,
        block: suspend (HostEngineApi) -> Unit,
    ) {
        val api = this.api
        if (api == null) {
            if (noEngine == NoEngine.Notify) {
                val message = tr("引擎未就绪：pi 现在不在运行，$label 没有执行。", "Engine not ready: pi is not running; $label skipped.")
                if (_state.value.notices.lastOrNull()?.message != message) fail(message)
            }
            return
        }
        val engine = session
        viewModelScope.launch {
            // 命令先排队，等宿主打开会话再发；引擎死了就悄悄放弃（死因已有专门的界面）。
            if (engine != null && !awaitServing(engine)) return@launch
            val token = claimBusy(label)
            try {
                block(api)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(
                    errorText?.invoke(error)
                        ?: error.message?.takeIf { it.isNotBlank() }
                        ?: tr("$label 失败", "$label failed"),
                )
            } finally {
                releaseBusy(token)
            }
        }
    }

    // -------------------------------------------------------- the status line
    //
    // One line, one owner — the ownership rule itself lives in [EngineStatusLine],
    // where `app/src/test/kotlin/app/pi/ui/EngineStatusLineCheck.kt` pins it without a
    // device (see `tools/run-app-pure-checks.sh`). These two are only the `UiState`
    // half of it.
    private val statusLine = EngineStatusLine()

    /** Take the status line for [label] and return the token [releaseBusy] needs. */
    internal fun claimBusy(label: String): Long {
        val token = statusLine.claim()
        _state.value = _state.value.copy(busy = label)
        return token
    }

    /**
     * Give the status line back — only if [token] still owns it.
     *
     * A `false` from [EngineStatusLine.release] means a newer claim owns the line now,
     * so `busy` is left alone: the operation that is still running is not this one.
     *
     * **这就是「读取会话树 ↔ 就绪」疯狂来回刷的根因修复。** 旧规则拿**标签**当所有权
     * （`busy == label` 才允许清），而 `refreshTree()` 的合并补读是在前一次读的 block
     * `finally` 里派发的、两次标签都是「读取会话树」——前一次结束会**把后一次仍在跑的
     * 那一行抹掉**，顶栏在没读完时就先跳到「就绪」；后一次自己的清理再什么都找不到。
     * 一对合并读就产生一次假翻转，用户看到的就是字面上的「读取会话树 ↔ 就绪」。令牌规则
     * 下这种翻转**结构上不可能**：无论期间开了多少次读，行一直归最新的那次，只有它释放
     * 才回到「就绪」。
     *
     * A writer that clears `busy` outright (the engine dying, `:2486`) leaves the token
     * stale on purpose: the next claim supersedes it, and the dead call's release is then
     * a no-op — the correct reading of "that operation's status is moot".
     */
    internal fun releaseBusy(token: Long) {
        if (statusLine.release(token) && _state.value.busy != null) {
            _state.value = _state.value.copy(busy = null)
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
    /**
     * @param brandNew the session was created **in this engine, in place** — `new_session`,
     *   fork and clone rebind without emitting an event, so there is no file to read. pi
     *   writes a session on its first `message_end`, so a brand-new one either has no file
     *   at all or, worse, `meta.sessionFile` still names **the session we just left**.
     *
     *   Reading that file is the 「新建会话点 1/2/3 次，直到成功为止」 defect: `readTail`
     *   succeeds against the *old* session, `seedHistory` puts the old conversation back on
     *   screen, and the `return` below means **the engine is never asked what it actually
     *   holds**. When the timing happens to work the file is absent, `resolveSessionFile`
     *   answers null, and the RPC path below produces the blank conversation the user
     *   expects — which is why the symptom looked random.
     *
     *   For a brand-new session the engine is the only authority: `get_entries` answers an
     *   empty page for it (`replayHistoryOverRpc`, whose KDoc says so). So this flag skips
     *   the file question entirely rather than trying to answer it.
     */
    internal suspend fun replayHistory(engine: PiEngineSession, brandNew: Boolean = false) {
        // Cleared first: the replay either replaces it or fails, and a stale list
        // would let the next scroll-up prepend one session's entries onto another's.
        loadedHistoryChars = 0L
        retainedChunks = emptyList()
        // No evicted copy and no defined end until a file is actually read: the whole
        // range is in RAM, so `evictedFrom == loadedUntil` and nothing is re-read.
        evictedFrom = 0L
        loadedUntil = 0L
        evictedEntries = 0
        val file = if (brandNew) null else resolveSessionFile()
        if (file != null) {
            // The read and the retained-size accounting are one IO hop, not two: the
            // accounting serialises every entry to measure it ([entryChars]) and it is
            // the same decision ("are we about to retain this window?") as the read
            // itself. It also keeps the count and the rows it describes in one
            // publication: a scroll-up on the next frame must not read a stale 0 and
            // take an extra window.
            //
            // `sizeOf` is in the same hop because it is the *end* of the loaded range
            // ([loadedUntil]): it is where the first eviction range starts from, and it
            // must be the size the read above saw, not a later one.
            val (window, windowChars, until) = withContext(Dispatchers.IO) {
                val read = SessionFileReader.readTail(file, HISTORY_WINDOW_CHARS, HISTORY_WINDOW_ENTRIES)
                val chars =
                    if (read != null && read.entries.isNotEmpty() && read.complete) {
                        retainedChars(read.entries)
                    } else {
                        0L
                    }
                Triple(read, chars, SessionFileReader.sizeOf(file))
            }
            if (window != null && window.entries.isNotEmpty() && window.complete) {
                engine.seedHistory(window.entries)
                loadedHistoryChars = windowChars
                retainedChunks = listOf(RetainedChunk(window.entries, window.startOffset, windowChars))
                // The tail window ends at the file's end, so the evicted range is empty:
                // `[until, until)`.
                loadedUntil = until
                evictedFrom = until
                evictedEntries = 0
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
     *     from disk ([switchSession] holds the `SessionSummary` the user
     *     tapped, and [importSession] the file it just copied), so the common case —
     *     opening a conversation from the list — needs no round trip at all.
     */
    private suspend fun resolveSessionFile(): File? {
        val meta = _state.value.meta
        val hinted = SessionHints.file
        return withContext(Dispatchers.IO) {
            if (hinted != null && hinted.isFile) {
                return@withContext hinted
            }
            val file = hostSessionFile(meta.sessionFile) ?: return@withContext null
            val sessionId = meta.sessionId
            if (sessionId != null) {
                val header = SessionFileReader.readHeader(file) ?: return@withContext null
                val headerId = (header["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (headerId != null && headerId != sessionId) return@withContext null
            }
            file
        }
    }

    /** The one file a session command already knows, until the meta catches up. */
    internal object SessionHints {
        var file: File? = null
    }

    /**
     * The whole-session `get_entries` replay — the fallback, and the path the tree
     * overlay and export still take.
     *
     * Kept in one place so the two callers cannot drift, and so the reason it is now
     * the *second* choice is written down next to it.
     */
    internal suspend fun replayHistoryOverRpc(engine: PiEngineSession) {
        val page = try {
            // 原始条目而不是类型化的 `SessionEntry`：`seedHistory` 折的是 pi 那一层 JSON。
            engine.api.getEntriesRaw()
        } catch (e: HostCommandException) {
            // `reason` 是宿主/pi 自己的原话（例如超大记录的那句），原样带给用户。
            fail(tr("读取会话内容失败：${e.reason}。", "Failed to read session: ${e.reason}."))
            return
        } catch (e: Exception) {
            fail(tr("引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。", "Engine did not return session contents. Reopen the session and try again."))
            return
        }
        // `seedHistory`, not `transcript.seedFromHistory`: the reducer path mutates
        // the rows without publishing, and a publication is what carries the new
        // list (and its `replaced` flag) to this consumer. It **suspends**.
        val entries = page.entries
        // This path is a **whole session** in one answer (`get_entries` has no
        // pagination), so the retained-size accounting is the largest of the three
        // callers and the one that used to be worst on the frame thread. See
        // [entryChars] for the measurement; the sum runs on IO for the same reason the
        // read above does.
        val chars = withContext(Dispatchers.IO) { retainedChars(entries) }
        engine.seedHistory(entries)
        loadedHistoryChars = chars
        // This path has no byte offsets at all (`get_entries` answers entries, not
        // ranges), so there is no evicted range to read back: `0 == 0` is "nothing was
        // evicted", and the cursor below says the walk is over, so nothing ever asks.
        retainedChunks = listOf(RetainedChunk(entries, 0L, chars))
        evictedFrom = 0L
        loadedUntil = 0L
        evictedEntries = 0
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
     * concatenation: `newlyRead + retained + readBack`. That is O(loaded), which is why
     * the load is gated on the engine being idle — during a turn the live rows in
     * the reducer are not reproducible from a file read, and dropping them to add
     * older history would remove something the user is watching.
     *
     * ## What each answer means, and what it may not mean
     *
     * The step is reduced to a [BackwardRead] in one place ([backwardRead]) and turned
     * into a cursor in another ([cursorAfter]), because that pair is the defect this
     * replaces: 「聊天内容多了，回到聊天顶部，之前的内容都被截掉了，都没了。」 A step that
     * delivered nothing — because the range above was unreadable, because a line was
     * too long to keep, or because the read threw — used to fall into the same branch
     * as "the file has nothing above it" and set `reachedStart = true`. That made
     * `HistoryCursor.hasEarlier` false, deleted the 「加载更早」 row, and left every
     * entry above the loaded range unreachable for the rest of the session. Only
     * [BackwardRead.Window] with the reader's own `reachedStart`, or a range already at
     * byte 0, may claim the start; a failure is *reported* ([HistoryStop]) instead of
     * ending the walk.
     *
     * ## The budget is a soft, silent knob, not a stop
     *
     * `HISTORY_RETAINED_CHARS` bounds the retained **copy** of the loaded entries, and
     * nothing else: the transcript keeps every row it has, and the reader can keep
     * walking up for as long as the file has anything above. When the copy would go over
     * it, the window **farthest from the reader** is dropped
     * (`evictablePrefix`, never the chunk under the reader's eyes) and its byte range is
     * read back from the file on the next rebuild — so nothing is lost and no row
     * moves. The reader is never told about it: the row above the transcript has exactly
     * three things to say (a count, a read in progress, or why a read could not happen),
     * and the budget is not one of them.
     */
    fun expandEarlierHistory() {
        val engine = session ?: return
        val cursor = _state.value.history ?: return
        if (cursor.reachedStart || cursor.loading) return
        if (engine.transcript.streaming) return
        if (_state.value.engine != PiEngineSession.EngineState.Ready) return
        _state.value = _state.value.copy(history = cursor.copy(loading = true, stop = null))
        viewModelScope.launch {
            val file = resolveSessionFile()
            if (file == null) {
                _state.value = _state.value.copy(history = _state.value.history?.copy(loading = false))
                return@launch
            }
            // One IO hop, and **nothing in it touches `_state`**: the window above, the
            // evicted range read back, and the measures. The old budget branch wrote the
            // cursor from inside this hop, which is how it beat the caller's own update
            // and how a "stop" became invisible to the code path that reported it.
            val step = withContext(Dispatchers.IO) { backwardStep(file, cursor.startOffset) }
            val decision = cursorAfter(step.read)
            if (step.window == null) {
                // Nothing to fold. The cursor carries the reading: a real failure gets a
                // reason on screen and keeps `reachedStart` false, so the row stays and
                // the reader can press it again; `AtFileStart` is the only empty-handed
                // step that may claim the start, and it does so through `cursorAfter`.
                _state.value = _state.value.copy(
                    history = _state.value.history?.copy(
                        loading = false,
                        reachedStart = decision.reachedStart,
                        stop = decision.stop,
                    ),
                )
                return@launch
            }
            // A rebuild needs the *whole* retained range: the projection is folded from
            // one list, so `combined` is the window just read, the retained chunks (oldest
            // first), and the evicted range read back. The read-back's entry count is
            // checked against what was evicted before we get here ([readEvictedRange]),
            // because a rebuild with a hole in it would silently drop rows — the same
            // loss this change exists to remove.
            val combined = step.window.entries +
                retainedChunks.asReversed().flatMap { it.entries } +
                step.readback
            engine.seedHistory(combined)
            // The **increment**, not a re-sum over `combined`: the measure serialises
            // every entry to take its length ([entryChars]), so a re-sum would make each
            // step cost the whole retained range. Both halves are measured on the IO hop
            // above, and the count is only the same number because the measure is
            // additive — which is what the `history-retention` harness pins.
            loadedHistoryChars += step.windowChars + step.readbackChars
            retainedChunks = buildList {
                if (step.readback.isNotEmpty()) {
                    add(RetainedChunk(step.readback, evictedFrom, step.readbackChars))
                }
                addAll(retainedChunks)
                add(RetainedChunk(step.window.entries, step.window.startOffset, step.windowChars))
            }
            // The read-back is in RAM again, so the evicted range is empty unless the
            // trim below empties it out; `evictRetainedCopy` owns both fields.
            evictedFrom = loadedUntil
            evictedEntries = 0
            evictRetainedCopy()
            _state.value = _state.value.copy(
                history = HistoryCursor(
                    startOffset = step.window.startOffset,
                    reachedStart = step.window.reachedStart,
                ),
            )
            syncTranscript(engine, engine.publication.value)
        }
    }

    /**
     * One backward step's IO hop: the window above, and the evicted copy read back.
     *
     * All of it is wrapped, because every one of these failures is a reading the cursor
     * has to report rather than an exception the scope should swallow: a file pi deleted
     * under us ([SessionFileReader.readBetween] throws from `RandomAccessFile`), a
     * read-back whose entry count disagrees with what was evicted, and a read-back with a
     * withheld line — the last two are refused on purpose, because rebuilding from a list
     * with a hole would drop rows from the transcript.
     */
    private fun backwardStep(file: File, startOffset: Long): BackwardStep = runCatching {
        val window = SessionFileReader.readBefore(
            file,
            startOffset,
            HISTORY_WINDOW_CHARS,
            HISTORY_WINDOW_ENTRIES,
        )
        val read = backwardRead(startOffset, window)
        // `backwardRead` only answers `Window` for a non-null window, and the check is
        // spelled out here so the compiler knows it too.
        if (read !is BackwardRead.Window || window == null) return@runCatching BackwardStep(read)
        val readback = readEvictedRange(file)
        BackwardStep(
            read = read,
            window = window,
            readback = readback.entries,
            windowChars = retainedChars(window.entries),
            readbackChars = readback.chars,
        )
    }.getOrElse { error ->
        BackwardStep(BackwardRead.ReadFailed(readFailureDetail(file, error)))
    }

    /**
     * Which of [BackwardRead]'s readings `readBefore`'s answer is.
     *
     * `null` is two different answers and the whole defect was treating them as one:
     * `startOffset <= 0L` means the loaded range already begins at byte 0, so nothing can
     * be above it; any other null means there are bytes above that the reader did not
     * deliver, which is *cannot read*. A window that withheld a line
     * (`Window.complete == false`, `SessionFileReader.kt:258`) is cannot-read too: there
     * is an entry above it and it did not arrive.
     */
    private fun backwardRead(startOffset: Long, window: SessionFileReader.Window?): BackwardRead = when {
        window == null && startOffset <= 0L -> BackwardRead.AtFileStart
        window == null -> BackwardRead.UnreadableRange
        !window.complete -> BackwardRead.WithheldLine
        window.entries.isEmpty() -> BackwardRead.UnreadableRange
        else -> BackwardRead.Window(window.reachedStart)
    }

    /**
     * The evicted range `[evictedFrom, loadedUntil)`, read back exactly, or a refusal.
     *
     * Exactness is the point: [SessionFileReader.readBetween] reads a byte range whose
     * low end is a line start, so it cannot pick up a retained entry above it, and the
     * entry count is compared with what [evictRetainedCopy] dropped. A disagreement means
     * the bookkeeping is wrong, and the honest response is to refuse the step (the row
     * says the content cannot be read) rather than to rebuild the transcript from a list
     * with a hole in it.
     */
    private fun readEvictedRange(file: File): ReadBack {
        if (evictedFrom >= loadedUntil) return ReadBack(emptyList(), 0L)
        val range = SessionFileReader.readBetween(file, evictedFrom, loadedUntil)
            ?: throw IOException(tr("被淘汰的那一段读不回来", "Could not read back evicted history"))
        if (!range.complete) throw IOException(tr("被淘汰的那一段里有一条太长，读不出来", "An evicted entry is too large to read back"))
        if (range.entries.size != evictedEntries) {
            throw IOException(tr("被淘汰的那一段读回来对不上（${range.entries.size} ≠ $evictedEntries）", "Evicted entry count mismatch (${range.entries.size} != $evictedEntries)"))
        }
        return ReadBack(range.entries, retainedChars(range.entries))
    }

    /**
     * Bring the retained copy back inside [HISTORY_RETAINED_CHARS] by dropping whole
     * windows from the end **farther from the reader**, and remember the byte range so
     * the next rebuild can read it back.
     *
     * The rule itself is [evictablePrefix] — a pure function in `HistoryRetention.kt`,
     * pinned by the `history-retention` harness including the counterexample that no
     * chunk the reader is on may ever appear among the victims. This function is only the
     * bookkeeping around it, and it runs *after* the read, on the caller's coroutine:
     * never on the scroll gesture's path, and never before the rows it describes have
     * been published.
     */
    private fun evictRetainedCopy() {
        val dropped = evictablePrefix(retainedChunks, HISTORY_RETAINED_CHARS)
        if (dropped == 0) {
            evictedFrom = loadedUntil
            evictedEntries = 0
            return
        }
        val victims = retainedChunks.take(dropped)
        retainedChunks = retainedChunks.drop(dropped)
        loadedHistoryChars -= victims.sumOf { it.chars }
        // The victims are the newest windows, so their range is contiguous and its low end
        // is the oldest victim's first line — which is exactly what `readBetween` needs.
        evictedFrom = victims.last().startOffset
        evictedEntries = victims.sumOf { it.entries.size }
    }

    /**
     * A short, user-facing reason for a thrown read.
     *
     * The row prints it, so it says what happened in the reader's terms: the file is gone,
     * it is no longer a session, or reading it failed. The exception's own message is
     * appended when there is one — it is the device's wording (a permission failure, an
     * I/O error) and inventing a translation for it would be a claim this code cannot
     * check.
     */
    private fun readFailureDetail(file: File, error: Throwable): String {
        val kind = when {
            !file.isFile -> tr("文件不在了", "File no longer exists")
            runCatching { SessionFileReader.isSessionFile(file) }.getOrDefault(false) -> tr("文件读取出错", "File read error")
            else -> tr("它已不是一个会话文件", "No longer a valid session file")
        }
        val message = error.message?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        return if (message == null) kind else "$kind（$message）"
    }

    /** What one backward step's IO hop produced: the reading, and what to fold. */
    private class BackwardStep(
        val read: BackwardRead,
        val window: SessionFileReader.Window? = null,
        val readback: List<JsonObject> = emptyList(),
        val windowChars: Long = 0L,
        val readbackChars: Long = 0L,
    )

    /** The evicted range read back, with its measured size. */
    private class ReadBack(val entries: List<JsonObject>, val chars: Long)

    /**
     * Host path of a guest session path, or null.
     *
     * The inverse of [guestSessionPath], and the same mapping the export's byte copy applies
     * ([exportedSessionFile]). The guards matter: `get_state`'s `sessionFile`
     * comes from pi, so a malformed or escaping value must not become an arbitrary
     * read — only a path under the guest's session directory that resolves inside
     * the host's is accepted, and only when it is a regular file.
     */
    internal fun hostSessionFile(guestPath: String?): File? {
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
        call(tr("读取会话状态", "Load session state"), NoEngine.Quiet) { api -> loadState(api) }
    }

    /**
     * [refreshState] 的**可等待**版本：发一次 `get_state`、写进 meta，然后才返回。
     *
     * ## 为什么必须有它（2026-11-04 用户报的「切完工作区还是老会话」）
     *
     * `call` 是 fire-and-forget —— 它内部 `viewModelScope.launch`，**立刻返回**。于是
     *
     *     refreshState()          // 只是排了一次协程（fire-and-forget）
     *     replayHistory(engine)   // 用的仍然是**旧** meta
     *
     * 两句写在相邻行也毫无顺序保证。`replayHistory` 的源文件由 `meta.sessionFile` 决定，
     * 所以它读的是**上一个引擎那段会话的文件**：切工作区之后，新引擎在一个全新的空会话上，
     * 屏幕上却重新画出了旧对话；而 clearing 那一帧要等到新会话的第一条 publication
     * (`replaced = true`) 才到 —— 用户看到的就是「表面上还是老会话，发一句话才切换」。
     *
     * 所以凡是以 meta 为输入的后续动作（`attach` 的 replay、`afterSessionReplaced` 的
     * replay）都必须先 await 这一句。失败不抛：`get_state` 拿不到时 replay 会走
     * `get_entries` 回落，而 `call` 版本的报错行为一点没变。
     */
    internal suspend fun refreshStateNow(api: HostEngineApi) {
        runCatching { loadState(api) }
    }

    private suspend fun loadState(api: HostEngineApi) {
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

    /**
     * `get_available_thinking_levels`. The set is a function of the selected
     * model (`agent-session.ts` `getAvailableThinkingLevels`), so it is reloaded
     * after every model change as well as on attach.
     */
    fun refreshThinkingLevels() {
        call(tr("读取思考等级", "Load thinking levels")) { api ->
            val levels = api.getAvailableThinkingLevels()
            _state.value = _state.value.copy(
                meta = _state.value.meta.copy(thinkingLevels = levels),
            )
        }
    }

    /** `get_available_models`, for the model picker. */
    fun refreshModels() {
        call(tr("读取模型列表", "Load models")) { api ->
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
        call(tr("读取命令列表", "Load commands")) { api ->
            // pi's own default is true (`settings-manager.ts:1165`).
            val skillCommands = settingsStore.readBoolean("enableSkillCommands") ?: true
            _state.value = _state.value.copy(
                commands = api.getCommandCatalog().let { catalog ->
                    piCommandPalette(
                        catalog.commands,
                        builtins = builtinRows(catalog.builtins),
                        skillCommandsEnabled = skillCommands,
                        shortcuts = catalog.shortcuts,
                    )
                },
                commandsLoaded = true,
            )
        }
    }

    /** `get_session_stats`, for the stats sheet and the context-usage line. */
    fun refreshStats() {
        call(tr("读取会话统计", "Load session stats")) { api ->
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
    // 合并连发：`call()` 没有 in-flight 守卫，N 次 refreshTree = N 个并发 get_tree
    // 排进引擎、每个完成都翻一次 `busy`/`state` —— 打开会话树后「疯狂刷新好几秒、
    // 期间返回键像被吞掉」就是这个形状（入口个个单发，但 ⋮→树 + 分段切换 + 刷新钮
    // 可以叠在同一瞬）。飞行中再来的要求折叠成**一次**补读：树是当前状态的快照，
    // 晚一拍的这次读回答得同样好。
    //
    // **守卫的两个标志只有一个清除点，而 `call()` 有一条不会执行 block 的早退**
    // （`:api == null` 时它只推一条通知就返回）。`refreshTree` 上面那次 `api == null`
    // 判断进的是另一条路、不落守卫，所以这个洞要两个 `api` 读数之间被换掉才成立 ——
    // 但代价不对称：一旦 `treeRefreshInFlight` 留在 `true`，此后每次 `refreshTree()`
    // 都只置 `treeRefreshQueued` 就返回，而唯一会排空那个标志的地方正是**不会执行的
    // block** ⇒ 这棵树在这一代 ViewModel 里再也不会被读（顶栏可能永远停在「读取会话树」
    // 或永远停在「就绪」，而树本身不再更新）。所以派发本身被包住：block 没跑起来，
    // 标志在这里释放。
    private var treeRefreshInFlight = false
    private var treeRefreshQueued = false

    /**
     * 给会话树上的条目打标签（书签）；`label` 为 null/空 = 清除。SDK 的 `appendLabelChange`：
     * 只追加一条 `label` 条目，不改写已有记录。树随后重读，标签就出现在那一行上。
     */
    fun setEntryLabel(entryId: String, label: String?) {
        val engine = session ?: return fail(tr("引擎未就绪：pi 现在不在运行，标签没有保存。", "Engine not ready: label not saved."))
        call(tr("保存标签", "Save label")) { _ ->
            engine.commands.setLabel(entryId, label)
            refreshTree()
        }
    }

    /**
     * 把一条用户消息从**模型上下文**里隐藏（SDK 的 `appendContextEdit`）：对话页仍然看得见、记录一个字不少，
     * 只是之后发给模型的上下文里不再有它。宿主在流式生成中会拒绝，原话经 [call] 的失败通道告诉用户。
     */
    fun hideEntryFromContext(entryId: String) {
        val engine = session ?: return fail(tr("引擎未就绪：pi 现在不在运行，没有执行。", "Engine not ready: action not executed."))
        call(tr("从上下文隐藏", "Hide from context")) { _ ->
            engine.commands.hideFromContext(entryId)
            refreshTree()
            refreshStats()
        }
    }

    // ------------------------------------------------------ 消息长按面的动作（见 chat/MessageActions.kt）

    /**
     * `/bug`：让当前模型描述「这个会话哪里不对」，成文后交给 [deliver]（界面负责复制到剪贴板）。
     * 宿主的 `session.bugReport` 要等一轮模型调用，所以先说一声在生成；失败如实说。
     */
    fun generateBugReport(hint: String?, deliver: (String) -> Unit) {
        val adv = advanced() ?: return pushNotice(tr("引擎未就绪：pi 现在不在运行，没有生成问题报告。", "Engine not ready: bug report not generated."), Notice.Tone.Warning)
        pushNotice(tr("正在生成问题报告，要等模型回一轮…", "Generating bug report…"), Notice.Tone.Info)
        viewModelScope.launch {
            runCatching { adv.bugReport(hint) }
                .onSuccess { text ->
                    if (text.isBlank()) pushNotice(tr("模型没有给出报告内容。", "Model returned an empty report."), Notice.Tone.Warning) else deliver(text)
                }
                .onFailure { pushNotice(tr("生成问题报告失败：${it.message ?: it::class.simpleName}", "Bug report failed: ${it.message ?: it::class.simpleName}"), Notice.Tone.Warning) }
        }
    }

    private var reloadingResources = false

    /**
     * 重新加载扩展、技能、提示词模板和主题（pi 的 `/reload`，宿主的 `session.reload`）。
     * 不重启引擎：host 在进程内重建资源加载器，对话和进行中的会话都不受影响。
     * 启动尚未完成时自动排队（避免要点两次），执行期间带明确忙碌提示并主动刷新命令表与状态。
     */
    fun reloadResources() {
        if (parkUntilAttached { reloadResources() }) {
            pushNotice(tr("引擎还在启动：重新加载已排队，启动完成后会自动执行。", "Engine is starting: reload queued and will run once ready."), Notice.Tone.Info)
            return
        }
        if (reloadingResources) return
        val ext = extensionsAccess()?.ext ?: return pushNotice(tr("引擎未就绪：pi 现在不在运行，没有重新加载。", "Engine not ready: reload skipped."), Notice.Tone.Warning)
        reloadingResources = true
        viewModelScope.launch {
            val token = claimBusy(tr("正在重新加载扩展与资源", "Reloading extensions & resources"))
            try {
                runCatching { ext.reload() }
                    .onSuccess {
                        refreshCommands()
                        refreshState()
                        refreshPrefs()
                        refreshTheme()
                        pushNotice(tr("已重新加载扩展、技能、提示词和主题。", "Reloaded extensions, skills, prompts, and themes."), Notice.Tone.Info)
                    }
                    .onFailure { pushNotice(tr("重新加载失败：${it.message ?: it::class.simpleName}", "Reload failed: ${it.message ?: it::class.simpleName}"), Notice.Tone.Warning) }
            } finally {
                reloadingResources = false
                releaseBusy(token)
            }
        }
    }

    /**
     * 错误卡的「重试」：把最后一条用户消息（文字和图片）原样再发一次。
     *
     * pi 的 SDK 没有「立刻重试」——`session.retry` 只管自动重试的开关和中止；它自己的自动重试
     * 内部是把出错的助手消息从上下文摘掉再 `agent.continue()`，那条路不对外。出错的助手消息
     * （stopReason=error）在发给模型前会被 pi-ai 的 transformMessages 丢掉，所以重发同一句
     * 提示，模型看到的上下文和第一次是一致的；代价是会话里多一条重复的用户消息。
     */
    fun retryLastPrompt() {
        val last = _state.value.transcript.lastOrNull { it is UserMessage } as? UserMessage
            ?: return pushNotice(tr("没有可以重发的上一条消息。", "No previous message to resend."), Notice.Tone.Warning)
        send(last.text, last.images)
    }

    /** 把引用块接在输入框草稿后面（和扩展的 `pasteToEditor` 同一条通道）。 */
    fun quoteIntoComposer(text: String) {
        composerFillSeq += 1
        _state.value = _state.value.copy(composerFill = ComposerFill(composerFillSeq, text, append = true))
    }

    /**
     * 找出这行消息在 pi 会话里的条目 id，再交给 [then]；找不到就如实说，不猜。
     * 匹配规则与它的 harness 在 [MessageEntryResolver]。pi 只在第一条助手消息写完后才把会话落盘，
     * 所以刚发出的第一轮里这里可能还找不到——话术里写明原因，而不是报一个泛泛的「失败」。
     */
    private fun resolveEntryThen(item: TranscriptItem, what: String, then: (String) -> Unit) {
        val api = this.api ?: return fail(tr("引擎未就绪：pi 现在不在运行，$what 没有执行。", "Engine not ready: $what skipped."))
        val (role, text) = when (item) {
            is UserMessage -> MessageRole.User.wire to item.text
            is AssistantText -> MessageRole.Assistant.wire to item.text
            else -> return
        }
        viewModelScope.launch {
            val id = runCatching {
                val raw = api.getEntriesRaw().entries
                MessageEntryResolver.resolve(raw.mapNotNull(::entryText), item.key, role, text)
            }.getOrNull()
            if (id == null) {
                pushNotice(tr("这条消息还没写进 pi 的会话文件（pi 在第一轮回答结束后才落盘），$what 暂时做不了。", "This message is not persisted to the session file yet; $what is unavailable until the first turn finishes."), Notice.Tone.Warning)
            } else {
                then(id)
            }
        }
    }

    /** 原始条目 → 只剩角色与文本块；不是消息条目的返回 null。 */
    private fun entryText(entry: kotlinx.serialization.json.JsonObject): EntryText? {
        val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if ((entry["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "message") return null
        val message = entry["message"] as? kotlinx.serialization.json.JsonObject ?: return null
        val role = (message["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase() ?: return null
        val content = message["content"]
        val texts = when (content) {
            is kotlinx.serialization.json.JsonPrimitive -> listOf(content.content)
            is kotlinx.serialization.json.JsonArray -> content.mapNotNull { block ->
                val o = block as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                if ((o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "text") null
                else (o["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            else -> emptyList()
        }
        return EntryText(id, role, texts)
    }

    /** 「从这里继续」：把叶子移到这条消息，不做摘要（要摘要走会话树页）。 */
    fun continueFromMessage(item: TranscriptItem) =
        resolveEntryThen(item, tr("从这里继续", "Continue from here")) { navigateTo(it, BranchSummaryChoice.NoSummary) }

    fun labelMessage(item: TranscriptItem, label: String?) =
        resolveEntryThen(item, tr("加标签", "Add label")) { setEntryLabel(it, label) }

    fun hideMessageFromContext(item: TranscriptItem) =
        resolveEntryThen(item, tr("从上下文隐藏", "Hide from context")) { hideEntryFromContext(it) }

    /** 宿主的高级命令入口；引擎没跑时 null。 */
    fun advanced(): app.pi.host.HostAdvanced? = session?.advanced

    private var extUiJob: Job? = null
    private var hostStateJob: Job? = null
    private var panelColumns = 0
    private var panelResizeJob: Job? = null

    /**
     * 面板能放多少列等宽字符 → 宿主，让组件工厂按这个宽度重绘。布局抖动时防抖 100 ms，
     * 同一个值不重发；引擎换了（重启 / 新进程）下一次布局变化会再发——宿主只在内存里存这一个数。
     */
    fun setPanelColumns(columns: Int) {
        if (columns == panelColumns) return
        panelColumns = columns
        panelResizeJob?.cancel()
        panelResizeJob = viewModelScope.launch {
            delay(PANEL_RESIZE_DEBOUNCE_MS)
            runCatching { session?.commands?.uiResize(columns) }
        }
    }

    /** 「扩展 / MCP」界面的宿主入口；引擎没起来为 null（界面据此说明，而不是假装有数据）。 */
    fun extensionsAccess(): app.pi.host.ExtensionsAccess? {
        val s = session ?: return null
        return app.pi.host.ExtensionsAccess(
            ext = app.pi.host.HostExtensions(s.commands),
            resources = app.pi.host.HostResources { s.commands },
            events = s.hostEvents,
            requests = s.hostRequests,
            respond = { id, value, cancelled -> s.client.respond(id, value, cancelled) },
        )
    }

    private val _trustPrompt = MutableStateFlow<app.pi.packages.PiProjectTrust.State?>(null)

    /** 要问用户「信任这个项目吗」时非空（项目里有 `.pi` 资源、没人表态、默认策略是 ask）。 */
    val trustPrompt: StateFlow<app.pi.packages.PiProjectTrust.State?> = _trustPrompt.asStateFlow()

    private fun refreshTrustPrompt() {
        val c = session?.commands ?: return
        viewModelScope.launch {
            val st = runCatching { app.pi.packages.PiProjectTrust.parse(c.trustState().data) }.getOrNull()
            _trustPrompt.value = st?.takeIf { it.undecided }
        }
    }

    /** 用户在弹窗里选了一项。失败时返回宿主原话（弹窗保留，用户可重试）。 */
    suspend fun answerTrust(option: app.pi.packages.PiProjectTrust.Option): String? {
        val c = session?.commands ?: return tr("引擎没在运行。", "Engine is not running.")
        return try {
            c.setTrust(option.trusted, option.remember.key)
            _trustPrompt.value = null
            null
        } catch (e: app.pi.host.HostException) {
            e.message ?: e.code
        }
    }

    /** 先不决定：这次不再问（下次开这个项目还会问）。 */
    fun dismissTrustPrompt() { _trustPrompt.value = null }

    /** 宿主命令入口（引擎没起来为 null）。资源 / 信任这类「问宿主」的界面用它，不自己开 guest 进程。 */
    fun hostCommands(): app.pi.host.HostCommands? = session?.commands

    /**
     * 把 App 当前色板交给宿主，让扩展组件的 `theme.fg(...)` 跟着 App 主题走。
     * 宿主进程重启后色板回到默认，所以界面在引擎每次就绪时再推一次。失败不打扰用户：
     * 最坏只是扩展组件用默认暗色。
     */
    fun pushExtensionPalette(palette: Map<String, String>) {
        val commands = hostCommands() ?: return
        viewModelScope.launch { runCatching { commands.uiPalette(palette) } }
    }

    private var renderInvalidateJob: Job? = null
    private var entryProbeJob: Job? = null
    private val _renderRev = MutableStateFlow(0)

    /** 宿主说「渲染结果变了」时加一；聊天页据此换一个 [ToolRenderer] 实例，卡片的 produceState 就重取。 */
    val renderRev: StateFlow<Int> = _renderRev.asStateFlow()

    internal val toolRenderCache = ToolRendererCache { item, expanded, width ->
        val commands = session?.commands ?: throw IllegalStateException("no session")
        commands.renderTool(
            toolName = item.toolName,
            toolCallId = item.toolCallId,
            args = item.args,
            resultText = if (item.status == ToolStatus.Pending && item.output.isEmpty()) null else item.output,
            // 已结束取结果的 details，运行中取最新的进度快照（`tool_execution_update.partialResult.details`）。
            details = item.details ?: item.liveDetails,
            images = item.images,
            argsComplete = item.args != null,
            isError = item.isError,
            expanded = expanded,
            isPartial = item.status == ToolStatus.Pending,
            durationMs = item.durationMs,
            width = width,
        )
    }

    internal val messageRenderCache = MessageRendererCache { item, expanded ->
        val commands = session?.commands ?: throw IllegalStateException("no session")
        commands.renderMessage(item.customType, item.markdown, item.details, expanded, panelColumns.takeIf { it > 0 } ?: 80)
    }

    /** 扩展自定义消息的渲染入口：没有渲染器 / 宿主不可达回 null，消息回落 markdown。 */
    val messageRenderer = MessageRenderer { item, expanded ->
        runCatching { messageRenderCache.render(item, expanded) }.getOrNull()
    }

    internal val entryRenderCache = EntryRendererCache { item, expanded ->
        val commands = session?.commands ?: throw IllegalStateException("no session")
        commands.renderEntry(item.customType, item.key, item.data, expanded, panelColumns.takeIf { it > 0 } ?: 80)
    }

    /** 扩展自定义会话条目的渲染入口（`registerEntryRenderer`）：没有渲染器 / 宿主不可达回 null。 */
    val entryRenderer = EntryRenderer { item, expanded ->
        runCatching { entryRenderCache.render(item, expanded) }.getOrNull()
    }

    internal val _entryRenderable = MutableStateFlow<Set<String>>(emptySet())

    /** 有扩展渲染器的条目 customType：转录里只有这些 `custom` 条目会画出来（pi：没渲染器就不添加任何子组件）。 */
    val entryRenderable: StateFlow<Set<String>> = _entryRenderable.asStateFlow()

    internal val markdownTransformCache = MarkdownTransformCache { text, type, id ->
        val commands = session?.commands ?: throw IllegalStateException("no session")
        commands.renderMarkdown(text, type, isStreaming = false, width = panelColumns.takeIf { it > 0 } ?: 80, messageId = id)
    }

    /** 已完成消息的 markdown 经扩展 transformer 改写；没有 transformer / 宿主不可达就原样返回。 */
    val markdownTransform = MarkdownTransform { text, type, id ->
        runCatching { markdownTransformCache.transform(text, type, id) }.getOrDefault(text)
    }

    /** 转录里新出现的 `custom` 条目类型各问宿主一次「有没有渲染器」，结果驱动 [entryRenderable]。 */
    private fun probeEntryTypes(transcript: List<TranscriptItem>) {
        val fresh = transcript.filterIsInstance<ExtensionEntry>()
            .filter { !entryRenderCache.known(it.customType) }
            .distinctBy { it.customType }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            for (item in fresh) runCatching { entryRenderCache.render(item, false) }
            _entryRenderable.value = fresh.map { it.customType }.filter(entryRenderCache::hasRenderer).toSet() + _entryRenderable.value
        }
    }

    /** 工具卡的扩展渲染入口：宿主不可达 / 没有渲染器都回 null，卡片回落通用样式。 */
    val toolRenderer = ToolRenderer { item, expanded ->
        runCatching { toolRenderCache.render(item, expanded, panelColumns.takeIf { it > 0 } ?: 80) }.getOrNull()
    }


    /** 键盘 / 文本 → 一个交互式表面（`ctx.ui.custom()` / 工厂 widget）。宿主喂给组件的 `handleInput` 后重绘。 */
    fun sendSurfaceInput(surfaceId: String, data: String) {
        val commands = session?.commands ?: return
        viewModelScope.launch { runCatching { commands.uiInput(surfaceId, data) } }
    }

    /** 触控点击 → 组件 `handleMouse`（`ui.mouse`）。 */
    fun sendSurfaceMouse(surfaceId: String, x: Int, y: Int, width: Int, height: Int) {
        val commands = session?.commands ?: return
        viewModelScope.launch { runCatching { commands.uiMouse(surfaceId, x, y, width = width, height = height) } }
    }

    /** 表面面板尺寸变化 → 同步列数与行数到宿主（`ui.resize`）。 */
    fun resizeSurface(columns: Int, rows: Int) {
        if (columns == panelColumns && rows <= 0) return
        panelColumns = columns
        panelResizeJob?.cancel()
        panelResizeJob = viewModelScope.launch {
            delay(PANEL_RESIZE_DEBOUNCE_MS)
            runCatching { session?.commands?.uiResize(columns, rows.takeIf { it > 0 }) }
        }
    }

    private var editorSyncJob: Job? = null
    private var lastSyncedEditorText: String? = null

    /** 把聊天输入框内容同步到宿主，让扩展调用 `ctx.ui.getEditorText()` 能拿到真实草稿。 */
    fun syncEditorState(text: String) {
        if (text == lastSyncedEditorText) return
        editorSyncJob?.cancel()
        editorSyncJob = viewModelScope.launch {
            delay(150)
            val commands = session?.commands ?: return@launch
            lastSyncedEditorText = text
            runCatching { commands.uiEditorState(text) }
        }
    }

    /** 将用户在 App 中切换的工具展开态同步回宿主（`ctx.ui.getToolsExpanded()` 一致）。 */
    fun setToolsExpandedRemote(expanded: Boolean) {
        val commands = session?.commands ?: return
        viewModelScope.launch { runCatching { commands.uiSetToolsExpanded(expanded) } }
    }

    /** 触屏触发扩展通过 `pi.registerShortcut(shortcut, ...)` 注册的快捷键。 */
    fun triggerExtensionShortcut(shortcut: String) {
        call(tr("执行快捷键", "Trigger shortcut")) { api -> api.triggerShortcut(shortcut) }
    }

    /** 将终端按键序列送过扩展 `ctx.ui.onTerminalInput` 监听器链。 */
    fun sendTerminalInput(data: String) {
        val commands = session?.commands ?: return
        viewModelScope.launch { runCatching { commands.uiTerminalInput(data) } }
    }

    /** 用户点 ✕：先 Esc（组件自己收尾、自己 `done()`），它不理就强关；强关会让扩展的 await 以 undefined 返回。 */
    fun closeSurface(surfaceId: String) {
        val commands = session?.commands ?: return
        viewModelScope.launch {
            runCatching { commands.uiInput(surfaceId, "\u001b") }
            delay(SURFACE_ESC_GRACE_MS)
            if (_state.value.ext.customStack.any { it.surfaceId == surfaceId }) {
                runCatching { commands.uiClose(surfaceId) }
            }
        }
    }

    /** 「模型与供应商」页要的全部宿主能力；引擎没跑时 null。 */
    fun modelsHost(): app.pi.ui.models.ModelsHost? {
        val engine = session ?: return null
        val api = HostEngineApi(engine.commands)
        return app.pi.ui.models.ModelsHost(
            auth = engine.auth,
            advanced = engine.advanced,
            events = engine.client.signals.filterIsInstance<app.pi.host.HostSignal.Event>(),
            requests = engine.client.requests,
            answer = { id, value, cancelled ->
                engine.client.respond(id, value?.let { kotlinx.serialization.json.JsonPrimitive(it) }, cancelled)
            },
            models = { api.getAvailableModels().also { m -> _state.value = _state.value.copy(models = m) } },
            catalog = { provider -> api.getModelCatalog(provider) },
            selectModel = { provider, id, persist ->
                val applied = api.setModel(provider, id, persist)
                _state.value = _state.value.copy(meta = _state.value.meta.copy(model = applied))
            },
            currentModel = { _state.value.meta.model?.let { m -> m.provider?.let { it to m.id } } },
            currentThinking = { _state.value.meta.thinkingLevel },
            thinkingLevels = {
                api.getAvailableThinkingLevels().also { lv ->
                    _state.value = _state.value.copy(meta = _state.value.meta.copy(thinkingLevels = lv))
                }
            },
            setThinking = { level ->
                api.setThinkingLevel(level)
                _state.value = _state.value.copy(meta = _state.value.meta.copy(thinkingLevel = level))
            },
            setDefaultThinkingLevel = { level ->
                engine.commands.settingsSet("setDefaultThinkingLevel", listOf(JsonPrimitive(level)))
                invalidateSettingsCache()
            },
            setModelThinkingLevel = { provider, modelId, level ->
                engine.commands.settingsSet(
                    "setModelThinkingLevel",
                    listOf(JsonPrimitive(provider), JsonPrimitive(modelId), JsonPrimitive(level)),
                )
                invalidateSettingsCache()
            },
            removeModelThinkingLevel = { provider, modelId ->
                engine.commands.settingsSet(
                    "removeModelThinkingLevel",
                    listOf(JsonPrimitive(provider), JsonPrimitive(modelId)),
                )
                invalidateSettingsCache()
            },
            setEnabledModels = { patterns ->
                val params = if (patterns.isNullOrEmpty()) {
                    emptyList()
                } else {
                    listOf(JsonArray(patterns.map { JsonPrimitive(it) }))
                }
                engine.commands.settingsSet("setEnabledModels", params)
                invalidateSettingsCache()
            },
            setShowCacheMissNotices = { show ->
                engine.commands.settingsSet("setShowCacheMissNotices", listOf(JsonPrimitive(show)))
                invalidateSettingsCache()
                refreshPrefs()
            },
        )
    }

    /** 只改「发给模型的版本」（SDK `appendContextEdit` 带替换内容），原文保留。 */
    fun replaceEntryInContext(entryId: String, text: String) {
        val engine = session ?: return fail(tr("引擎未就绪：pi 现在不在运行，没有执行。", "Engine not ready: action not executed."))
        call(tr("改写上下文", "Edit context")) { _ ->
            engine.commands.replaceInContext(entryId, text)
            refreshTree()
            refreshStats()
        }
    }

    /** 停止正在进行的上下文压缩（SDK `abortCompaction`）；聊天页的状态卡直接用。 */
    fun abortCompaction() {
        val engine = session ?: return
        viewModelScope.launch { runCatching { engine.advanced.abortCompaction() } }
    }

    /** 停止正在进行的分支摘要（SDK `abortBranchSummary`）。 */
    fun abortBranchSummary() {
        val engine = session ?: return
        viewModelScope.launch { runCatching { engine.advanced.abortBranchSummary() } }
    }

    fun refreshTree() {
        if (treeRefreshInFlight) {
            treeRefreshQueued = true
            return
        }
        if (api == null) {
            // 引擎没跑：照旧走 call() 自己的「引擎未就绪」提示；block 不会执行，
            // 所以这条路不落守卫（否则 flag 永远清不掉）。
            call(tr("读取会话树", "Load session tree")) { live -> _state.value = _state.value.copy(tree = live.getTree()) }
            return
        }
        treeRefreshInFlight = true
        // `call` 的 block 在 `Main.immediate` 上是**同步跑起来**的（调用点在主线程），
        // 所以它返回时 `started` 已经如实回答了「block 到底进没进」。进了，标志属于这次
        // 读，由 block 自己的 `finally` 释放；没进，只能在这里释放，否则标志永远不落。
        var started = false
        try {
            call(tr("读取会话树", "Load session tree")) { live ->
                started = true
                try {
                    _state.value = _state.value.copy(tree = live.getTree())
                } finally {
                    treeRefreshInFlight = false
                    if (treeRefreshQueued) {
                        treeRefreshQueued = false
                        refreshTree()
                    }
                }
            }
        } finally {
            if (!started) {
                // 同一条排空规则，只是从「block 的 finally」搬到了「派发的 finally」。
                treeRefreshInFlight = false
                if (treeRefreshQueued) {
                    treeRefreshQueued = false
                    refreshTree()
                }
            }
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
        call(tr("读取会话条目", "Load session entries"), NoEngine.Quiet) {
            val file = resolveSessionFile()
            if (file == null) {
                refreshEntriesOverRpc()
                return@call
            }
            val parsed = withContext(Dispatchers.IO) {
                val out = ArrayList<SessionEntry>()
                val complete = SessionFileReader.readEntries(file) { raw ->
                    out += parseSessionEntry(raw)
                }
                if (complete) out else null
            }
            if (parsed == null) {
                refreshEntriesOverRpc()
                return@call
            }
            _state.value = _state.value.copy(entries = parsed)
        }
    }

    /** `get_entries` for the entry log — see [refreshEntries] for when this is used. */
    private fun refreshEntriesOverRpc() {
        call(tr("读取会话条目", "Load session entries"), NoEngine.Quiet) { api ->
            _state.value = _state.value.copy(entries = api.getEntries().entries)
        }
    }

    /** `get_fork_messages` — the user messages pi will let a fork start from. */
    fun refreshForkMessages() {
        call(tr("读取可分叉消息", "Load forkable messages")) { api ->
            _state.value = _state.value.copy(forkMessages = api.getForkMessages())
        }
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
        // Wait for compaction to finish and retry.` (`core/agent-session.js:1619`,
        // checked after the extension-command branch at `:1611` and before the
        // streaming branch). The bare `steer`/`follow_up` commands have no such
        // check: they go through `_queueUserInput` (`:1722-1740`), which runs the
        // extension `input` handlers and the skill/template expansion and then
        // pushes the text onto pi's own `_steeringMessages`/`_followUpMessages`. So
        // during a compaction the message is **queued by pi** (visible in the
        // pending row, which reads `queue_update`), the submission does not fail,
        // and the app needs no local hold-and-flush of its own.
        //
        // Worst case if this flag is ever stale-true: the text lands in pi's
        // steering queue and is delivered at the next agent step. Slower, not lost,
        // not an error — which is why the trade is worth taking. (The flag is
        // reconciled from `get_state` in [refreshState], so a dropped
        // `compaction_end` heals at the next attach or settle.)
        //
        // No echo here: this path *always* queues, so the row belongs to pi's
        // delivery, not to this submission (see `PiEngineSession.prompt`). The
        // pending row is what shows the message in the meantime.
        if (_state.value.meta.compacting) {
            engine.fire("session.steer", app.pi.host.sendArgs(trimmed, images))
            syncTranscript(engine, engine.publication.value)
            // pi answers this window with its own status line — `Queued message for
            // after compaction` (`interactive-mode.js:3916`, in
            // `queueCompactionMessage`). The pending row will show the text too once
            // pi's `queue_update` lands (`core/agent-session.js:1767`); the notice is
            // what says *why* it is waiting, which the row does not.
            pushNotice(tr("已排队，压缩结束后投递", "Queued; will deliver after compaction"), Notice.Tone.Info)
            return
        }
        // Otherwise: one entry point, two delivery choices — pi's own shape. Its TUI
        // submits every non-built-in message through `session.prompt(text, {
        // streamingBehavior: "steer" })` while a turn is running
        // (`interactive-mode.js:2717-2723`, the call itself at `:2720`) and through
        // an ordinary submit when the agent is idle (`:2726-2734`).
        //
        // What `prompt` adds over the bare `steer` command: pi's own pre-flight —
        // the compaction check above, the model/credential validation and the rest
        // of `prompt`'s checks — and it is the path pi's TUI exercises, which is why
        // `docs/feature-gaps.md:96` recorded the old app as never sending
        // `streamingBehavior` on `prompt` ("used only through `follow_up`"). It is
        // **not** "the only path that runs the input handlers": `_queueUserInput`
        // runs those (`core/agent-session.js:1722-1740`) for the bare commands too.
        //
        // The echo is `PiEngineSession.prompt`'s own, and it is no longer done here:
        // it publishes the row *and* registers it in the reducer's pending-echo
        // queue, which is what makes pi's later `message_end(role="user")` a
        // confirmation instead of a second bubble (`rpc/.../Transcript.kt:1036`
        // records the echo, `:1065-1068` matches it by text). Echoing here as well
        // would render the row twice and leave a stale pending echo behind — the same
        // failure the queue fix removes for queued messages.
        //
        // `streamingBehavior` now goes out **always**. pi reads it only inside its
        // `if (this.isStreaming)` branch (`core/agent-session.js:1635`) and ignores
        // it otherwise, falling through to an ordinary turn at `:1663`, so no state
        // makes sending it wrong. What the app's own flag still decides is only the
        // echo: `queued` says whether this message will wait in pi's queue rather
        // than be delivered now, which is precisely what official pi draws in its
        // pending area instead of the chat (`interactive-mode.js:3873-3889`).
        engine.prompt(
            message = trimmed,
            images = images,
            streamingBehavior = StreamingBehavior.Steer,
            queued = engine.transcript.streaming,
        )
        // Published by `prompt` only when it echoed; a queued message publishes when
        // pi confirms it instead.
        syncTranscript(engine, engine.publication.value)
    }

    /**
     * Invoke one palette command whose action is [ChatAction.Prompt].
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
            engine.fire("session.prompt", app.pi.host.sendArgs(text, emptyList()))
        } else {
            // Templates and skills are expanded inside pi into a real user message
            // (`core/agent-session.js:1631-1632`), so the optimistic echo is confirmed
            // by that event and replaced by pi's own projection of it.
            //
            // Mid-turn the submission needs pi's `streamingBehavior`, exactly as
            // pi's own TUI sends it (`interactive-mode.js:2717-2723`, the call at
            // `:2720`: `session.prompt(text, { streamingBehavior: "steer" })` for
            // every non-built-in submit while streaming). Without it pi rejects the
            // `prompt` outright — "Agent is already processing. Specify
            // streamingBehavior ('steer' or 'followUp') to queue the message"
            // (`core/agent-session.js:1637`) — so selecting a template or a skill
            // from the palette during a turn raised that error instead of queueing,
            // while the same selection works in pi's TUI. `steer` is pi's own choice
            // for Enter; the follow-up gesture is separate and still goes through
            // [sendFollowUp].
            //
            // `queued` is this app's belief, and it gates the echo only: a palette
            // skill or template picked mid-turn waits in pi's queue like any other
            // mid-turn message, so it belongs in the pending row until pi delivers it.
            engine.prompt(
                message = text,
                streamingBehavior = StreamingBehavior.Steer,
                queued = engine.transcript.streaming,
            )
        }
        syncTranscript(engine, engine.publication.value)
    }

    /**
     * `follow_up` — pi's `alt+enter` (`interactive-mode.js:3653-3682`).
     *
     * The delivery choice *is* the difference between the two queueing commands:
     * `steer` lands after this turn's tool calls (`_queueSteer`,
     * `core/agent-session.js:1765`), `follow_up` only once the agent would otherwise
     * stop (`_queueFollowUp`, `:1781`), and `set_follow_up_mode` configures the
     * second one. pi's TUI queues a follow-up only while the agent is streaming
     * (`interactive-mode.js:3671-3677`); when it is idle alt+enter is an ordinary
     * submit (`:3679-3682`), so the composer offers this only while streaming and
     * [send] handles the idle case.
     *
     * [images] travels the whole way because pi's follow-up carries them: the
     * TUI hands its editor attachments to `session.prompt(text, {
     * streamingBehavior: "followUp" })` (`interactive-mode.js:3674`), which
     * reaches `_queueFollowUp(expandedText, currentImages)`
     * (`core/agent-session.js:1639-1642`). F21's investigation in
     * `docs/gap-disposition.md` §10 found that the wire command already had the
     * parameter and this signature did not, so the 后续 chip silently dropped an
     * attachment the user had added — a visible action with no effect, the same
     * class of bug F19 removed elsewhere.
     */
    fun sendFollowUp(text: String, images: List<PiImage> = emptyList()) {
        if (parkUntilAttached { sendFollowUp(text, images) }) return
        val engine = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Compacting: [send] documents the window — pi's `prompt` throws
        // (`core/agent-session.js:1619`) and the bare `follow_up` command queues the
        // text instead (`_queueUserInput`, `:1722-1740`, no compaction check). Same
        // trade, same worst case. No echo, for the same reason as [send].
        if (_state.value.meta.compacting) {
            engine.fire("session.followUp", app.pi.host.sendArgs(trimmed, images))
            syncTranscript(engine, engine.publication.value)
            // Same status line as [send]'s compaction branch, and pi's own wording
            // for it (`interactive-mode.js:3916`).
            pushNotice(tr("已排队，压缩结束后投递", "Queued; will deliver after compaction"), Notice.Tone.Info)
            return
        }
        // pi's `alt+enter` is the *same* submit with the other delivery choice:
        // `session.prompt(text, { streamingBehavior: "followUp" })`
        // (`interactive-mode.js:3671-3677`), not the bare `follow_up` command — see
        // [send] for what the command form gives up (pi's pre-flight checks). The
        // echo is `PiEngineSession.prompt`'s, for the same single-render reason.
        //
        // `streamingBehavior` goes out always: it is what pi consults *if* it is
        // streaming (`core/agent-session.js:1635`), and it is ignored otherwise, so
        // with the agent idle alt+enter still becomes an ordinary submit — pi's own
        // `:3679-3682` behavior — without this app having to guess. `queued` carries
        // this app's guess separately, because only the echo depends on it: a
        // follow-up that pi queues must not be drawn in the transcript, since
        // official draws it in the pending area (`interactive-mode.js:3873-3889`) and
        // adds the chat row when it is delivered.
        engine.prompt(
            message = trimmed,
            images = images,
            streamingBehavior = StreamingBehavior.FollowUp,
            queued = engine.transcript.streaming,
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
     * (`interactive-mode.js:3684-3693` — `handleDequeue`, wired to the keybinding at
     * `:2463` — → `restoreQueuedMessagesToEditor()` with no options,
     * `:3891-3910`).
     *
     * The difference from [stop] is exactly the missing `abort`, and it is pi's
     * whole point of the action: the current turn keeps running, only the queue
     * moves back into the editor. `clear_queue` is all-or-nothing
     * (`rpc-types.d.ts:35`), so "one message at a time" is not on offer here — nor in
     * pi, whose pending-messages list is read-only and whose hint restores them all.
     *
     * Both answers are reported, matching pi's own two status lines: an empty one is
     * `No queued messages to restore` (`interactive-mode.js:3687`) and it is the only
     * signal that the dequeue raced the turn's own consumption of the queue; a
     * non-empty one is `Restored N queued messages to editor` (`:3690`), which this
     * app was missing entirely — the text reappeared in the composer with nothing
     * saying that it had come *back* rather than been typed.
     */
    fun restoreQueue(onRestored: (List<String>) -> Unit = {}) {
        val engine = session ?: return
        viewModelScope.launch {
            val restored = runCatching { engine.drainQueue() }.getOrDefault(emptyList())
            syncTranscript(engine, engine.publication.value)
            if (restored.isEmpty()) {
                pushNotice(tr("队列里没有待收回的消息", "No queued messages to restore"), Notice.Tone.Warning)
            } else {
                pushNotice(tr("已收回 ${restored.size} 条排队消息到输入框", "Restored ${restored.size} queued message(s) to composer"), Notice.Tone.Info)
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
    fun setThinkingLevel(level: String, persist: Boolean = false) {
        call(tr("切换思考等级", "Change thinking level")) { api ->
            api.setThinkingLevel(level, persist)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(thinkingLevel = level))
            refreshState()
        }
    }

    /** `cycle_thinking_level`. `null` is pi's documented answer. */
    fun cycleThinkingLevel() {
        call(tr("切换思考等级", "Change thinking level")) { api ->
            val result = api.cycleThinkingLevel()
            if (result == null) {
                pushNotice(tr("当前模型不支持思考等级", "Current model does not support thinking levels"), Notice.Tone.Warning)
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

    /**
     * `model.cycle`：按作用域顺序切到下一个模型（pi 的 Ctrl+P）。
     * 只有一个可选模型时宿主答 null，给一句提示而不是静默。
     */
    fun cycleModel() {
        call(tr("切换模型", "Switch model")) { api ->
            val result = api.cycleModel()
            val model = result?.model
            if (model == null) {
                pushNotice(tr("没有其他可切换的模型", "No other models available to cycle"), Notice.Tone.Warning)
                return@call
            }
            _state.value = _state.value.copy(meta = _state.value.meta.copy(model = model))
            refreshState()
            refreshStats()
        }
    }

    /** 模型选择器打开时预填的搜索词（`/model <词>` 没有精确匹配时）。 */
    private val _modelPickerSeed = MutableStateFlow("")
    val modelPickerSeed: StateFlow<String> = _modelPickerSeed.asStateFlow()

    fun seedModelPicker(text: String) {
        _modelPickerSeed.value = text
    }

    /**
     * `/model <引用>`（官方 `handleModelCommand`）：精确匹配就直接切（只改本会话，`persist:false`），
     * 否则打开选择器并预填 [term]。[openPicker] 在没有精确匹配时回到界面去开弹层。
     */
    fun runModelCommand(term: String, openPicker: () -> Unit) {
        call(tr("切换模型", "Switch model")) { api ->
            val ref = api.resolveModel(term)
            if (ref == null) {
                _modelPickerSeed.value = term
                openPicker()
                return@call
            }
            val applied = api.setModel(ref.first, ref.second)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(model = applied))
            refreshState()
            refreshStats()
        }
    }

    /** `set_model`. pi answers with the model it actually switched to. */
    fun setModel(model: PiResponses.ModelInfo, persist: Boolean = false) {
        val provider = model.provider
        if (provider.isNullOrBlank()) {
            fail(tr("该模型没有 provider，pi 无法按 provider/id 定位它", "Model has no provider and cannot be resolved by provider/id"))
            return
        }
        call(tr("切换模型", "Switch model")) { api ->
            val applied = api.setModel(provider, model.id, persist = persist)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(model = applied))
            // Thinking levels are a property of the model, and pi applies a
            // per-model default on a model switch
            // (`_getThinkingLevelForModelSwitch`), so re-read both.
            refreshState()
            // 上下文窗口是模型自己的：换模型之后 `contextUsage.contextWindow` 必须重读，
            // 否则环上的总量会停在旧模型上，直到下一轮结束。
            refreshStats()
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
        call(tr("设置引导模式", "Set steering mode")) { api ->
            api.setSteeringMode(mode)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(steeringMode = mode))
        }
    }

    fun setFollowUpMode(mode: QueueMode) {
        call(tr("设置后续模式", "Set follow-up mode")) { api ->
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
        call(tr("压缩上下文", "Compact context")) { api ->
            val result = try {
                api.compact(customInstructions?.takeIf { it.isNotBlank() })
            } catch (error: HostCommandException) {
                // pi 用英文原文说明「没什么可压」的两种情形
                // (`core/agent-session.ts:1985-1992`)，原样弹出来就是中文界面里的一句
                // 英文。只映射这两条**已知**原因，其余 reason 一字不改地透出：这里不是
                // 一个把 pi 的话吞掉的翻译层，用户拿它去搜/去报错仍然要对得上。
                throw HostCommandException(error.command, compactReasonInChinese(error.reason))
            }
            val before = result.tokensBefore
            val after = result.estimatedTokensAfter
            val detail = if (before != null && after != null) "（$before → $after tokens）" else ""
            pushNotice(tr("上下文已压缩$detail", "Context compacted$detail"), Notice.Tone.Info)
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        call(tr("设置自动压缩", "Set auto-compaction")) { api ->
            api.setAutoCompaction(enabled)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(autoCompaction = enabled))
        }
    }

    fun setAutoRetry(enabled: Boolean) {
        call(tr("设置自动重试", "Set auto-retry")) { api ->
            api.setAutoRetry(enabled)
            _state.value = _state.value.copy(meta = _state.value.meta.copy(autoRetry = enabled))
        }
    }

    /** `abort_retry` — cancel the pending retry delay and stop retrying. */
    fun abortRetry() {
        call(tr("取消重试", "Cancel retry")) { it.abortRetry() }
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

    private var argCompletionId = 0

    /**
     * 向扩展命令要参数候选（pi 的 `getArgumentCompletions`）。后发先至：旧请求的应答一律丢弃；
     * 失败不弹通知（补全是锦上添花，扩展里抛了就当没有候选）。
     */
    fun requestArgCompletions(name: String, prefix: String) {
        val id = ++argCompletionId
        val engine = session ?: return
        val api = this.api ?: return
        viewModelScope.launch {
            if (!awaitServing(engine)) return@launch
            val items = runCatching { api.getCommandCompletions(name, prefix) }.getOrNull() ?: return@launch
            if (id != argCompletionId) return@launch
            _state.value = _state.value.copy(argCompletions = ArgCompletions(name, prefix, items))
        }
    }

    fun dismissArgCompletions() {
        argCompletionId++
        if (_state.value.argCompletions != null) {
            _state.value = _state.value.copy(argCompletions = null)
        }
    }

    private var extCompletionId = 0

    /** 查询扩展通过 `ctx.ui.addAutocompleteProvider` 注册的补全候选（`ui.autocomplete`）。 */
    fun requestExtensionAutocomplete(draft: String) {
        val id = ++extCompletionId
        val commands = session?.commands ?: return
        viewModelScope.launch {
            val lines = draft.split('\n')
            val cursorLine = (lines.size - 1).coerceAtLeast(0)
            val cursorCol = lines.lastOrNull()?.length ?: 0
            val reply = runCatching { commands.uiAutocomplete(lines, cursorLine, cursorCol) }.getOrNull() ?: return@launch
            if (id != extCompletionId) return@launch
            _state.value = _state.value.copy(
                extCompletions = if (reply.items.isNotEmpty()) {
                    ExtensionCompletions(draft = draft, prefix = reply.prefix, items = reply.items)
                } else {
                    null
                },
            )
        }
    }

    fun dismissExtensionAutocomplete() {
        extCompletionId++
        if (_state.value.extCompletions != null) {
            _state.value = _state.value.copy(extCompletions = null)
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
            pushNotice(tr("已有 bash 命令在运行，先停止再执行新的命令", "A bash command is already running; stop it before running another"), Notice.Tone.Warning)
            return
        }
        bashStream.setLength(0)
        lastBashPublishAt = 0L
        bashStreamTrimmed = false
        _state.value = _state.value.copy(
            bash = BashRun(command = trimmed, excludeFromContext = excludeFromContext),
        )
        call(tr("执行命令", "Run command")) { api ->
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
        call(tr("停止命令", "Stop command")) { it.abortBash() }
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

    fun newSession() = newSessionImpl()
    fun deleteSession(summary: SessionSummary) = deleteSessionImpl(summary)
    fun switchSession(summary: SessionSummary) = switchSessionImpl(summary)
    fun forkSessionToCurrentWorkspace(summary: SessionSummary) = forkSessionToCurrentWorkspaceImpl(summary)
    fun resolveCwdMissingContinue() = resolveCwdMissingContinueImpl()
    fun resolveCwdMissingFork() = resolveCwdMissingForkImpl()
    fun dismissCwdMissing() = dismissCwdMissingImpl()
    fun importSession(source: Uri) = importSessionImpl(source)
    fun forkFrom(entryId: String) = forkFromImpl(entryId)
    fun forkFromMessage(key: String, ordinal: Int, text: String) = forkFromMessageImpl(key, ordinal, text)
    fun cloneSession() = cloneSessionImpl()
    fun navigateTo(
        entryId: String,
        choice: BranchSummaryChoice,
        customInstructions: String? = null,
        onAborted: () -> Unit = {},
    ) = navigateToImpl(entryId, choice, customInstructions, onAborted)
    fun treeFilterMode(): app.pi.session.tree.TreeFilter = treeFilterModeImpl()
    fun branchSummarySkipPrompt(): Boolean = branchSummarySkipPromptImpl()
    fun renameSession(name: String) = renameSessionImpl(name)
    fun renameSessionFromList(summary: SessionSummary, name: String) = renameSessionFromListImpl(summary, name)
    fun exportSession(fileName: String? = null) = exportSessionImpl(fileName)
    fun dismissExport() = dismissExportImpl()
    fun copyLastAssistantText(onText: (String) -> Unit) = copyLastAssistantTextImpl(onText)


    /**
     * 导航请求的序号：**「这一条请求」的身份**，`requestNav` 每次分配一个、从不复用。
     *
     * 为什么需要它：请求是在 `PiRoot` 的 `LaunchedEffect(navRequest)` 里落地的，而**效果
     * 体要等下一帧才跑**。主线程被别的工作占满时（用户读数：消息越多越明显、有时要等
     * 一秒多），那条已经被点下去的请求会**在用户关掉覆盖层之后才落地**，把会话树又举
     * 回来 —— 用户原话：「点进会话树，一直显示读取会话树，这个时候我点叉，点出去，
     * 1 秒内就给我拉回来」。`NavRequest.SessionTree` 是 `data object`，"这一次请求"与
     * "上一次"在状态里长得一模一样，所以**值本身不足以判断一条请求该不该被执行**。
     *
     * 高水位 [navHandled] 放在**普通字段**里、不属于 `UiState`：任何过期状态快照都带不
     * 走它。[discardPendingNav] 把它推到 [navIssued]，于是"关掉覆盖层"同时作废**所有
     * 还没落地的请求** —— 一条请求最多被处理一次，而且关掉之后永远不会再被处理。
     */
    private var navIssued = 0L
    private var navHandled = 0L

    /** Ask `PiRoot` to change destination, raise an overlay, or focus a screen. */
    fun requestNav(request: NavRequest) {
        navIssued += 1
        _state.value = _state.value.copy(navRequest = request, navSeq = navIssued)
    }

    /**
     * 领取 [seq] 这条请求的处理权 —— **只对还没被处理、也没被作废的请求返回一次 `true`**。
     *
     * `PiRoot` 先问这一句，再执行请求：关掉覆盖层之后才轮到的那条请求会在这里被拒
     * （[discardPendingNav] 已经把高水位推过去了），所以"点叉就退出"由构造保证。
     */
    fun claimNav(seq: Long): Boolean {
        if (seq <= navHandled || seq > navIssued) return false
        navHandled = seq
        if (_state.value.navSeq == seq) {
            _state.value = _state.value.copy(navRequest = null)
        }
        return true
    }

    /**
     * 覆盖层此刻是否显示、显示哪一屏 —— **单独一条流，绝不放进 `UiState`**。
     *
     * 用户的要求是硬的：「点叉就必须退出，**不管打断还是怎么的**，不能给我拉回来。」
     * 于是"覆盖层开着"这件事只能由 [raiseOverlay] / [closeOverlay] 这一对动作拥有。
     * 为什么必须与 `UiState` 分开：`_state.value = _state.value.copy(...)` 有七十多处，
     * 任何一次晚到的写、任何一份 restored state / 过期快照都可能把"覆盖层开着"再送回
     * 来 —— 而这条流它们一个都碰不到。[closeOverlay] 之后，只有**新的** [raiseOverlay]
     * 能让它再显示；排队中的导航请求同时被 [discardPendingNav] 作废。
     */
    private val _overlay = MutableStateFlow<OverlayState?>(null)
    val overlay: StateFlow<OverlayState?> = _overlay.asStateFlow()

    /** 举起覆盖层；重复调用就是改 [OverlayState.view]（覆盖层开着时再来一次 `/tree`）。 */
    fun raiseOverlay(slot: OverlaySlot, view: OverlayView) {
        _overlay.value = OverlayState(slot, view)
    }

    /**
     * 关掉覆盖层 —— **并且作废排队中的导航请求**。
     *
     * 两件事必须一起做：请求是在 `PiRoot` 的效果体里落地的，而效果体要等下一帧才跑，
     * 所以一条已经点下去的 `SessionTree` 可能在 ✕ 之后才轮到（用户读数：消息越多越明显、
     * 有时要等一秒多，于是"点出去 1 秒内又被拉回来"）。关掉之后它再也过不了
     * [claimNav]，覆盖层也不会再显示。
     */
    fun closeOverlay() {
        _overlay.value = null
        discardPendingNav()
    }

    /**
     * 作废**所有尚未落地**的导航请求：用户把覆盖层关掉了，`PiRoot` 里那条排着队、
     * 还没跑到的效果不许再执行（见 [navIssued] 的 KDoc）。
     */
    fun discardPendingNav() {
        navHandled = navIssued
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
            message = tr("「/$name」不是已安装的扩展命令、模板或技能；如果要把它作为消息发给模型，请去掉开头的 /", "\"/$name\" is not an installed command, template, or skill. Remove the leading / to send as text."),
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
        // 扩展用 ctx.ui.setWorkingMessage 给的一句话（"索引中 3/12"）取代泛泛的「工作中」；只取第一行、去掉颜色码。
        current.streaming -> current.ext.working.message?.takeIf { current.ext.working.visible }?.let { chromeText(it).lineSequence().firstOrNull()?.trim() }
            ?.takeIf { it.isNotEmpty() } ?: tr("工作中", "Working")
        current.boot !is Boot.Ready -> tr("启动中", "Starting")
        current.engine == PiEngineSession.EngineState.Failed -> tr("引擎已退出", "Engine exited")
        current.engine == PiEngineSession.EngineState.Stopped -> tr("引擎已停止", "Engine stopped")
        current.engine == PiEngineSession.EngineState.Starting -> tr("启动中", "Starting")
        current.queueSteering > 0 || current.queueFollowUp > 0 -> tr("排队中", "Queued")
        else -> tr("就绪", "Ready")
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
        primeSessionList()
        watchSessionListChanges()
    }

    /**
     * 那个粗时钟：只要还有 pending 的工具行，就每秒往 [UiState.nowMs] 写一次；没有就一次
     * 都不写。
     *
     * 放在类体最后，理由和上面那个 `init` 一样——它在 `init` 里被启动，读的是 [_state]，
     * 声明序在它之前的字段才保证已经初始化。`viewModelScope` 是 `Main.immediate`，构造在
     * 主线程时 `launch` 的循环体是**同步**跑起来的：首帧那次 `state` 的重放值判为「没有
     * pending」，于是直接挂在收集上，构造期不会碰任何后声明的字段。
     *
     * **空闲零发布 / 有 pending 每秒一次，两半都在这里保证**：
     *  - 空闲：判据为假时走 `if (!pending)` 那一支，只有 `nowMs` 还不是 null（也就是刚从
     *    pending 落回来）才写一次收尾；此后没有任何 `delay` 在跑，这条协程挂在 StateFlow
     *    的收集上，转写里的 200 ms 发布也只是重新过一次 [UiState.hasPendingToolClock] 的
     *    判断，不再写状态；
     *  - pending：`while (true)` 的每一次迭代**恰好写一次**再 `delay(TOOL_CLOCK_TICK_MS)`，
     *    即一秒一个发布。`delay` 刻意不放在前面：先写第一跳，`nowMs` 从 pending 出现的那一刻
     *    起就是非 null；反过来会留一段「行已经 pending、时钟还没 armed」的窗口，只能靠
     *    `ShellBlock` 自己的回退读数兜着。
     *
     * `collectLatest` 而不是 `collect`：pending 翻回 false 的时候要把还在 `delay` 的那个
     * 循环连人带 `delay` 一起取消掉，否则它会继续每秒写 nowMs——那正是「空闲零发布」的反面。
     * `distinctUntilChanged` 挂在**布尔**上，所以 pending 期间流式文本每 200 ms 推来的
     * 发布不会重启这个循环（重启会让节拍跟着发布走，又回到「有人推才走」的老毛病）。
     */
    init {
        viewModelScope.launch {
            state
                .map { it.hasPendingToolClock }
                .distinctUntilChanged()
                .collectLatest { pending ->
                    if (!pending) {
                        if (_state.value.nowMs != null) {
                            _state.value = _state.value.copy(nowMs = null)
                        }
                        return@collectLatest
                    }
                    while (true) {
                        _state.value = _state.value.copy(nowMs = System.currentTimeMillis())
                        delay(TOOL_CLOCK_TICK_MS)
                    }
                }
        }
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
        val myGen = viewModelGeneration
        teardownScope.launch {
            // Only shut down the process-wide engine/service if a newer ViewModel has not already
            // replaced this one during Activity recreation.
            if (activeViewModelGeneration.get() == myGen) {
                host.shutdown()
                if (activeViewModelGeneration.get() == myGen) {
                    PiEngineService.stopServiceOnly()
                }
            }
        }
        super.onCleared()
    }

    // ----------------------------------------------------------------- helpers

    /**
     * pi's wire spelling of a queue mode back into the enum. An unknown value maps
     * to `null` rather than a default, so a newer pi's new mode is not silently
     * displayed as `one-at-a-time`.
     */
    internal fun queueModeOf(wire: String?): QueueMode? =
        QueueMode.entries.firstOrNull { it.wire == wire }

    /**
     * The guest spelling of a session file the app found on disk.
     *
     * Null when the file is outside the agent dir, which would mean the index and
     * pi disagree about where sessions live — better to refuse than to hand pi a
     * path it will fail to open.
     */
    @Volatile internal var sessionOpenGeneration = 0L

    internal fun guestSessionPath(file: File): String? {
        val sessionsRoot = runCatching { File(host.paths().agentDir, "sessions").canonicalPath }.getOrNull() ?: return null
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (path != sessionsRoot && !path.startsWith(sessionsRoot + File.separator)) return null
        val relative = path.removePrefix(sessionsRoot).trimStart('/')
        return "${host.guestAgentDir}/sessions/$relative"
    }

    private val viewModelGeneration: Long = activeViewModelGeneration.incrementAndGet()

    private companion object {
        private val activeViewModelGeneration = java.util.concurrent.atomic.AtomicLong(0L)
        /**
         * 「启动续接最近会话」那一行的键。默认值不在这里 —— 它在注册表那一行
         * （`PiSettingsCatalog`，`defaultValue = bool(true)`），这里只放键名，免得两处各写一遍。
         */
        const val RESUME_LAST_SETTING_KEY = "app.sessions.resumeLast"

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
         *    raised to `SessionFileReader.DEFAULT_MAX_LINE_CHARS` (64 MiB): one
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
         * How much of a session's history this process keeps **as a second copy**, in
         * measured entry characters: 64 MiB, eight windows' worth.
         *
         * 「为什么有 64 兆的上限呢？没有上限不行吗？」 — the session file has no limit; every
         * entry pi wrote is on disk and nothing here deletes any of it. What this bounds
         * is how much of it is held **in RAM at once**, because an unbounded transcript is
         * how the app gets OOM-killed, and that is how the reader really loses the
         * conversation.
         *
         * It is not a stop and it is not a message. It used to be both: past this the
         * expansion simply ended, and — because the empty-handed branch below conflated
         * "over budget" with "nothing above" — the cursor claimed the start of the file,
         * the 「加载更早」 row disappeared, and everything above the loaded range became
         * unreachable. That is the reported 「回到聊天顶部，之前的内容都被截掉了，都没了」.
         *
         * What it does now: the copy is trimmed by dropping the window **farthest from the
         * reader** (`evictablePrefix`, which never drops the window under the reader's
         * eyes), the dropped range is read back from the file on the next rebuild, and the
         * walk up continues for as long as the file has anything above. Nothing on screen
         * moves, and the reader is not told: where the copy lives is an internal matter.
         *
         * It is a target, not a hard ceiling. A single window may be up to
         * `HISTORY_WINDOW_CHARS` plus one indivisible entry, and the protected chunk is
         * never dropped, so the retained copy can sit one window over this number — a
         * correct screen beats a smaller number.
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

    }
}

/**
 * The extension command that reaches pi's in-session tree navigation.
 *
 * `pi-android-bridge` registers it; see [PiSessionViewModel.navigateTo] for why a command
 * is the only way in. Not a user-facing name: it never appears in the composer, because it
 * is dispatched straight to the session rather than through the prompt path that echoes.
 */

/**
 * What the status line says while a navigation is in flight.
 *
 * It can legitimately last minutes: the leaf move is instant, but a requested branch
 * summary is a full model call (`core/agent-session.ts:3229-3240`). Saying what is being
 * waited on — rather than the generic 读取 — is the difference between "stuck" and
 * "summarizing".
 */

/**
 * [UiState.nowMs] 的节拍：一秒。它不是随手取的数——pi 给同一个读数用的就是这个周期
 * （`ShellBlock` 的 KDoc：pi 的 bash 渲染器 `:122` 每秒刷新一次 `Elapsed`），而我们的读数
 * 用的也是 pi 自己的写法（`ToolOutputParse.formatDuration`）。比 1 s 更密的心跳除了多重组
 * 几次什么都不改。只在有 pending 行时存在，见发布者那段 KDoc。
 */
private const val TOOL_CLOCK_TICK_MS = 1_000L

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


/**
 * 覆盖层的**槽位**：这个 App 只有一个覆盖层，会话列表与 pi 的 `/tree` 是它的两种视图
 * （见 [OverlayView]），终端是另一个槽位。
 */
enum class OverlaySlot { SessionList, Terminal }

/** 会话覆盖层打开时停在哪个视图：`SessionsScreen` 的列表，或 pi 的 `/tree`。 */
enum class OverlayView { List, Tree }

/** [PiSessionViewModel.overlay] 的读数：哪一层、哪一屏。 */
data class OverlayState(val slot: OverlaySlot, val view: OverlayView)

/** 跳转进行中的忙碌文案；会话树据此显示「停止摘要」。 */
val NAVIGATE_BUSY_LABEL: String get() = tr("跳转到会话位置…", "Navigating session…")

private const val PANEL_RESIZE_DEBOUNCE_MS = 100L
private const val SURFACE_ESC_GRACE_MS = 250L
