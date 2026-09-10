package app.pi.packages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The install → needs-restart → restart → ready state machine.
 *
 * ## Why a restart is the only way, from the source
 *
 * pi loads extensions with `jiti` **into the pi process** at startup. There are
 * exactly two things that pick up a new extension:
 *
 *  1. `/reload` — a *built-in* command (`slash-commands.ts:41`). Built-ins are
 *     excluded from `get_commands` (`docs/rpc.md:853`), are not dispatched by
 *     `prompt` (`agent-session.ts:1331-1343`, which only routes *extension*
 *     commands), and there is no `reload` command in `RpcCommand`
 *     (`modes/rpc/rpc-types.ts:20-74`). The reload plumbing *does* exist over RPC
 *     — `rpc-mode.ts:341-343` wires `reload: async () => { await session.reload() }`
 *     into the extension `commandContextActions` — so an **extension command** that
 *     calls `ctx.reload()` is reachable via `prompt`. That is the only in-process
 *     route, and it needs an extension the app owns to already be loaded.
 *  2. A fresh engine process.
 *
 * Neither is a silent operation. `session.reload()` emits
 * `session_shutdown {reason:"reload"}`, clears the jiti cache, re-resolves
 * packages/skills/prompts/themes, and emits `session_start {reason:"reload"}`
 * (`agent-session.ts:2841-2866`, `resource-loader.ts:388-547`). Killing the
 * process is blunter still.
 *
 * ## Why the app never does it silently
 *
 * A restart **kills any in-flight turn**: the model call, the tool calls, the
 * foreground service holding the wake lock — all of it. So the rule is:
 *
 *  - a successful install or remove **never** restarts anything; it moves the
 *    machine to [State.NeedsRestart] and says so;
 *  - a restart requires an explicit `requestRestart` **and** a confirmation
 *    ([State.AwaitingConfirmation] carries the question, not the action);
 *  - if a turn is running, even a confirmed restart waits: the machine parks in
 *    [State.AwaitingIdle] and does **not** restart. There is no timeout and no
 *    forcing path, because there is no correct moment to kill a turn that the user
 *    has not chosen. The user either stops the turn or waits, and the app re-offers.
 *
 * ## The same requirement covers settings
 *
 * `PiSettingsRegistry` marks every resource row `EffectiveKind.Reload`
 * (`PiCommon.kt:204` for the enum; `PiSettingsRegistry.kt:760`, `:773`, `:797`,
 * `:810`, `:823` for `extensions`, `packages`, `skills`, `prompts`, `themes`), and
 * `EffectiveKind.Reload` has no consumer. This machine is the consumer: a settings
 * write to any of those keys goes through [noteExternalChange] and lands in exactly
 * the same [State.NeedsRestart]. That is also the honest answer to "do skills and
 * prompt templates need a restart" — they do; see [RefreshSemantics].
 */
class ExtensionLifecycle {

    /** How long a restart is expected to take, so the UI can say something true. */
    val expectedRestartSeconds: IntRange = 1..3

    sealed interface State {
        val label: String

        /** Nothing pending; the engine, if running, has the current resources. */
        data object Idle : State {
            override val label: String get() = "空闲"
        }

        /** A package command is running. No restart may be started from here. */
        data class Installing(val action: String, val source: String) : State {
            override val label: String get() = "正在$action $source"
        }

        /**
         * Something changed on disk that pi will not see until it reloads. This is
         * the state the user must be told about every single time.
         */
        data class NeedsRestart(
            val changes: List<String>,
            val detail: String,
            /** Set after a failed restart attempt, so the reason survives. */
            val lastError: String? = null,
        ) : State {
            override val label: String get() = "需要重启"
        }

        /** The user asked to restart and the app is waiting for confirmation. */
        data class AwaitingConfirmation(val changes: List<String>, val question: String) : State {
            override val label: String get() = "等待确认"
        }

        /**
         * The user confirmed, but a turn is in flight, so nothing happens yet.
         * [turnNote] is what the user is told; there is no auto-continue.
         */
        data class AwaitingIdle(val changes: List<String>, val turnNote: String) : State {
            override val label: String get() = "等待回合结束"
        }

        /** The restart is actually happening. */
        data class Restarting(val changes: List<String>) : State {
            override val label: String get() = "正在重启"
        }

        /** The engine came back and has the new resources. */
        data class Ready(val note: String) : State {
            override val label: String get() = "已就绪"
        }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val current: State get() = _state.value

    /** True while a restart is genuinely pending, in any of its waiting shapes. */
    val pendingRestart: Boolean
        get() = when (_state.value) {
            is State.NeedsRestart, is State.AwaitingConfirmation, is State.AwaitingIdle -> true
            else -> false
        }

    // ------------------------------------------------------------- transitions

    /** A package command started. Only legal from a settled state. */
    fun beginInstall(action: String, source: String): Boolean {
        when (_state.value) {
            is State.Installing, is State.Restarting -> return false
            else -> {}
        }
        _state.value = State.Installing(action, source)
        return true
    }

    /**
     * A command finished successfully and changed something pi reads at startup.
     * This is where "restart is required" becomes visible — never later, never
     * implicitly.
     */
    fun installSucceeded(changes: List<String>, detail: String) {
        _state.value = State.NeedsRestart(changes = changes, detail = detail)
    }

    /**
     * A command failed. Nothing was persisted by pi's `installAndPersist`
     * (`package-manager.ts:1029-1032` runs the install *before* the settings
     * write, so a throw leaves settings untouched), so there is nothing to reload.
     */
    fun installFailed(message: String) {
        _state.value = State.Idle
        lastMessage = message
    }

    /** A settings row with `EffectiveKind.Reload` was written. Same requirement. */
    fun noteExternalChange(changes: List<String>, detail: String) {
        when (_state.value) {
            is State.Installing, is State.Restarting -> return
            else -> _state.value = State.NeedsRestart(changes, detail)
        }
    }

    /**
     * The user pressed 重启. This does **not** restart: it produces the question.
     *
     * @param turnRunning the caller's live answer from the engine, not a guess.
     */
    fun requestRestart(
        turnRunning: Boolean,
        /**
         * Why the wait, when the refusal came from the engine rather than from this
         * machine. Passing the engine's own sentence matters: it is the difference
         * between "等回合结束" and "重启会中断正在跑的 bash 命令，所以没有重启".
         */
        turnNote: String = TURN_RUNNING_NOTE,
    ): RequestOutcome = when (val now = _state.value) {
        is State.NeedsRestart -> if (turnRunning) {
            _state.value = State.AwaitingIdle(changes = now.changes, turnNote = turnNote)
            RequestOutcome.WaitingForTurn
        } else {
            _state.value = State.AwaitingConfirmation(changes = now.changes, question = confirmQuestion(now.changes))
            RequestOutcome.NeedsConfirmation
        }

        is State.Ready -> {
            // Idempotent: asking to restart a ready engine is a no-op, not an error.
            RequestOutcome.AlreadyReady
        }

        is State.AwaitingIdle -> {
            // Re-asked while still busy: re-check the turn rather than assume.
            if (turnRunning) {
                RequestOutcome.WaitingForTurn
            } else {
                _state.value = State.AwaitingConfirmation(now.changes, confirmQuestion(now.changes))
                RequestOutcome.NeedsConfirmation
            }
        }

        is State.AwaitingConfirmation -> RequestOutcome.NeedsConfirmation

        is State.Installing -> RequestOutcome.BusyWithPackageCommand

        is State.Restarting -> RequestOutcome.BusyWithPackageCommand

        State.Idle -> RequestOutcome.NothingPending
    }

    sealed interface RequestOutcome {
        /** The app must show [State.AwaitingConfirmation.question] and wait. */
        data object NeedsConfirmation : RequestOutcome

        /** A turn is in flight; nothing will happen until it ends. */
        data object WaitingForTurn : RequestOutcome

        data object AlreadyReady : RequestOutcome
        data object NothingPending : RequestOutcome
        data object BusyWithPackageCommand : RequestOutcome
    }

    /** The user declined. Back to the honest pending state. */
    fun cancelRestart() {
        val now = _state.value
        if (now is State.AwaitingConfirmation || now is State.AwaitingIdle) {
            val changes = when (now) {
                is State.AwaitingConfirmation -> now.changes
                is State.AwaitingIdle -> now.changes
                else -> emptyList()
            }
            _state.value = State.NeedsRestart(
                changes = changes,
                detail = "重启已取消；新装的资源仍未生效。",
            )
        }
    }

    /** The user confirmed and the caller is now performing the restart. */
    fun restartStarted(): Boolean {
        val now = _state.value
        if (now !is State.AwaitingConfirmation) return false
        _state.value = State.Restarting(now.changes)
        return true
    }

    fun restartSucceeded() {
        _state.value = State.Ready("引擎已重启，新资源已加载。")
        lastMessage = null
    }

    fun restartFailed(message: String) {
        val now = _state.value
        val changes = (now as? State.Restarting)?.changes ?: emptyList()
        _state.value = State.NeedsRestart(
            changes = changes,
            detail = "重启失败；资源仍未生效。",
            lastError = message,
        )
    }

    /** Last one-shot message for the UI to surface; not part of [state]. */
    var lastMessage: String? = null
        private set

    fun clearMessage() {
        lastMessage = null
    }

    private fun confirmQuestion(changes: List<String>): String = buildString {
        append("重启引擎以加载新资源？\n\n")
        if (changes.isNotEmpty()) {
            append(changes.joinToString("\n") { "· $it" })
            append("\n\n")
        }
        append("重启需要约 ${expectedRestartSeconds.first}–${expectedRestartSeconds.last} 秒。")
        append("它会终止当前正在进行的回合（模型调用、工具调用、bash 命令都不会恢复），")
        append("但已写入磁盘的会话内容不会丢失——会话是 JSONL 追加写的，重启后用 get_entries 重新挂载即可。")
    }

    companion object {
        /**
         * Deliberately not "稍后自动重启". The app has no way to know when the turn
         * will end, and a restart that fires on a guess is the silent restart this
         * machine exists to prevent.
         */
        const val TURN_RUNNING_NOTE: String =
            "有回合正在运行，暂不重启。重启会中断模型调用和正在跑的工具调用；" +
                "请先停止该回合或等它结束，然后再次点击重启。"

        /**
         * The refresh answer, shown next to every restart prompt. It lives here
         * rather than in [PackageStrings] because it is a statement about what pi
         * does, not a label: that adding a *skill* needs a restart is not obvious
         * to anyone who has not read `resource-loader.ts`.
         */
        val ANSWER_HINT: String = RefreshSemantics.ANSWER
    }
}

/**
 * What the app must tell the user about resource refresh, with the code path that
 * decides each case.
 *
 * This answers the "one uncertain fact" directly, and the answer is *no*: skills and
 * prompt templates are **not** re-discovered on every `get_commands` call. They are
 * scanned once, into a cached array, and that array is only rebuilt by a reload.
 *
 * The chain, every step in pi's source:
 *
 *  - `get_commands` builds its reply from three **cached reads**
 *    (`modes/rpc/rpc-mode.ts:682-713`):
 *    `session.extensionRunner.getRegisteredCommands()`,
 *    `session.promptTemplates`, and
 *    `session.resourceLoader.getSkills().skills`.
 *  - `session.promptTemplates` is a getter over `this._resourceLoader.getPrompts().prompts`
 *    (`core/agent-session.ts:1033-1035`).
 *  - `getPrompts()` returns `{ prompts: this.prompts }` and `getSkills()` returns
 *    `{ skills: this.skills }` — plain fields of the loader
 *    (`core/resource-loader.ts:308-314`), initialised to `[]` in the constructor
 *    (`:285-288`).
 *  - Those fields are assigned only by `updateSkillsFromPaths`
 *    (`:672-693`) and `updatePromptsFromPaths` (`:695-717`), which are called only
 *    from `reload()` (`:472-473` and `:487-488`, inside the method starting at
 *    `:388`). `reload()` sets `this.loaded = true` at `:546` and nothing else
 *    assigns either field.
 *  - `reload()` runs once at startup, when the loader is built
 *    (`core/sdk.ts:185-188`: `resourceLoader = new DefaultResourceLoader(...)` then
 *    `await resourceLoader.reload()`), and again only from `session.reload()`
 *    (`core/agent-session.ts:2849`).
 *
 * `loadSkills` (`resource-loader.ts:677-682`) does walk the filesystem on each
 * call — the *scan* is live; the **result is cached**. So dropping a new `SKILL.md`
 * into `.pi/skills` or editing a prompt template changes nothing the RPC client can
 * observe until a reload. Adding a skill therefore needs the same restart as adding
 * an extension. This contradicts any claim that `get_commands` re-discovers
 * resources per call, and the citations above are why.
 */
object RefreshSemantics {

    /** Which resource kinds a reload re-resolves (`resource-loader.ts:388-547`). */
    val reloadScannedResources = listOf(
        "extensions",
        "skills",
        "prompt templates",
        "themes",
        "AGENTS.md",
        "SYSTEM.md / APPEND_SYSTEM.md",
    )

    /** Read by `get_commands` from a cache, so a new file is invisible until reload. */
    val cachedAcrossGetCommands = listOf("extensions", "skills", "prompt templates")

    /** One sentence, for the report and for the UI. */
    const val ANSWER: String =
        "skills 与 prompt 模板不是每次 get_commands 重新扫描的：它们在 resource-loader 的 " +
            "reload() 里扫描一次并缓存（resource-loader.ts:472-473、:487-488），get_commands 直接读缓存 " +
            "（rpc-mode.ts:694-710）。新增技能或模板同样需要重启（或扩展命令里调用 ctx.reload()）。"
}
