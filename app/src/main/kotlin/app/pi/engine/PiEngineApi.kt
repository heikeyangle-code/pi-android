package app.pi.engine

import app.pi.rpc.PiCommands
import app.pi.rpc.PiEvent
import app.pi.rpc.PiMessage
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import kotlinx.serialization.json.JsonObject

/**
 * pi answered `success: false`, or answered with a payload that could not be
 * read as the response the command is documented to return.
 *
 * Both cases reach the UI through the same type on purpose: from the caller's
 * point of view "pi rejected the command" and "pi sent something we cannot
 * interpret" are the same outcome — the operation did not produce a usable
 * result — and the [reason] carries pi's own error text verbatim when there was
 * one, so nothing is lost.
 */
class PiRpcException(
    /** The `command` field pi echoed, when it sent one. */
    val command: String?,
    val reason: String,
) : Exception("${command ?: "rpc"}: $reason")

/**
 * The typed façade over pi's RPC command surface.
 *
 * One suspend function per command, taking Kotlin types and returning the typed
 * response from [PiResponses]. Callers never see a `JsonObject` and never build
 * one: the wire contract lives in `:rpc`, the process plumbing in
 * [PiEngineSession], and this class is only the seam between them.
 *
 * Every function throws [PiRpcException] when pi reports failure, and the
 * functions whose response pi documents as nullable return `null` instead — see
 * [cycleModel], [cycleThinkingLevel] and [getLastAssistantText]. The distinction
 * is deliberate: "no model to cycle to" is a normal answer, a rejected command
 * is not.
 *
 * Commands that do not appear here are the three prompting commands (`prompt`,
 * `steer`, `follow_up`). They are fire-and-forget and — more importantly —
 * `PiEngineSession.prompt` mirrors the message into the local transcript before
 * sending it; routing them through a second path would either duplicate that
 * side effect or bypass it. They stay on [PiEngineSession] where the transcript
 * lives, and so does the local echo a queued `steer`/`follow_up` needs
 * (`PiEngineSession.echoUserPrompt`), because a reducer mutation has to be
 * published by the engine to reach the UI (`PiEngineSession.publication`).
 *
 * Timeouts default to [DEFAULT_TIMEOUT_MS]; the commands that can legitimately
 * take minutes use [SLOW_TIMEOUT_MS], and every function lets the caller
 * override both.
 */
class PiEngineApi(private val session: PiEngineSession) {

    // -------------------------------------------------------------------- state

    /** `get_state`. */
    suspend fun getState(timeoutMs: Long = DEFAULT_TIMEOUT_MS): PiResponses.SessionState =
        call(timeoutMs) { PiCommands.getState(it) }.requireData(PiResponses::sessionState)

    /** `get_messages` — every message in the conversation, in order. */
    suspend fun getMessages(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<PiMessage> =
        call(timeoutMs) { PiCommands.getMessages(it) }.requireData(PiResponses::messages)

    /**
     * `get_entries` — the session's append-only entry log.
     *
     * Pass the last entry id already seen as [since] to receive only the entries
     * strictly after it. A [since] that matches no entry makes pi answer
     * `success: false`, which surfaces as [PiRpcException]: a stale cursor must
     * not look like "nothing new".
     */
    suspend fun getEntries(
        since: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.EntriesPage =
        call(timeoutMs) { PiCommands.getEntries(it, since) }
            .requireData(PiResponses::sessionEntries)

    /** `get_tree` — the session as a tree, plus the current leaf. */
    suspend fun getTree(timeoutMs: Long = DEFAULT_TIMEOUT_MS): PiResponses.TreePage =
        call(timeoutMs) { PiCommands.getTree(it) }.requireData(PiResponses::tree)

    /** `get_fork_messages` — user messages a fork can start from. */
    suspend fun getForkMessages(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<PiResponses.ForkMessage> =
        call(timeoutMs) { PiCommands.getForkMessages(it) }
            .requireData(PiResponses::forkMessages)

    /**
     * `get_last_assistant_text`. `null` is a documented answer: pi returns
     * `{"text": null}` when there are no assistant messages.
     */
    suspend fun getLastAssistantText(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? = call(timeoutMs) { PiCommands.getLastAssistantText(it) }
        .optionalData(PiResponses::lastAssistantText)

    /** `get_session_stats` — token, cost and context-window totals. */
    suspend fun getSessionStats(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.SessionStats =
        call(timeoutMs) { PiCommands.getSessionStats(it) }
            .requireData(PiResponses::sessionStats)

    /** `get_available_models`. */
    suspend fun getAvailableModels(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<PiResponses.ModelInfo> =
        call(timeoutMs) { PiCommands.getAvailableModels(it) }
            .requireData(PiResponses::availableModels)

    /**
     * `get_available_thinking_levels`.
     *
     * Levels are wire strings (`off` … `max`), not an enum: pi adds levels in
     * minor versions and derives the set from the selected model, so a client
     * enum would make a newer level impossible to select.
     */
    suspend fun getAvailableThinkingLevels(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<String> =
        call(timeoutMs) { PiCommands.getAvailableThinkingLevels(it) }
            .requireData(PiResponses::thinkingLevels)

    /** `get_commands` — extension commands, prompt templates and skills. */
    suspend fun getCommands(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<PiResponses.SlashCommand> =
        call(timeoutMs) { PiCommands.getCommands(it) }
            .requireData(PiResponses::slashCommands)

    // -------------------------------------------------------------------- model

    /** `set_model`; returns the model pi actually switched to. */
    suspend fun setModel(
        provider: String,
        modelId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.ModelInfo =
        call(timeoutMs) { PiCommands.setModel(it, provider, modelId) }
            .requireData(PiResponses::setModel)

    /**
     * `cycle_model`. `null` is a documented answer: pi returns `data: null` when
     * there is only one model to cycle through.
     */
    suspend fun cycleModel(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.CycleModelResult? =
        call(timeoutMs) { PiCommands.cycleModel(it) }
            .optionalData(PiResponses::cycleModel)

    // ----------------------------------------------------------------- thinking

    /** `set_thinking_level`. The level must come from [getAvailableThinkingLevels]. */
    suspend fun setThinkingLevel(
        level: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setThinkingLevel(it, level) }
    }

    /**
     * `cycle_thinking_level`. `null` is a documented answer: pi returns
     * `data: null` when the current model does not support thinking.
     */
    suspend fun cycleThinkingLevel(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.CycleThinkingResult? =
        call(timeoutMs) { PiCommands.cycleThinkingLevel(it) }
            .optionalData(PiResponses::cycleThinkingLevel)

    // -------------------------------------------------------------------- queue

    /** `set_steering_mode`. */
    suspend fun setSteeringMode(
        mode: QueueMode,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setSteeringMode(it, mode) }
    }

    /** `set_follow_up_mode`. */
    suspend fun setFollowUpMode(
        mode: QueueMode,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setFollowUpMode(it, mode) }
    }

    /**
     * `clear_queue` — removes queued steering and follow-up messages and hands
     * their text back so the composer can restore it (pi's interactive Esc
     * behaviour, docs/rpc.md §clear_queue).
     */
    suspend fun clearQueue(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PiResponses.ClearQueueResult =
        call(timeoutMs) { PiCommands.clearQueue(it) }
            .requireData(PiResponses::clearQueueResult)

    // --------------------------------------------------------------- compaction

    /**
     * `compact` — summarize the conversation to reduce context usage.
     *
     * Uses [SLOW_TIMEOUT_MS]: the command runs a full summarization LLM call, and
     * `compact` first aborts any in-flight turn (`agent-session.ts#compact`).
     */
    suspend fun compact(
        customInstructions: String? = null,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.CompactionResult =
        call(timeoutMs) { PiCommands.compact(it, customInstructions) }
            .requireData(PiResponses::compactionResult)

    /** `set_auto_compaction`. */
    suspend fun setAutoCompaction(
        enabled: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setAutoCompaction(it, enabled) }
    }

    // -------------------------------------------------------------------- retry

    /** `set_auto_retry`. */
    suspend fun setAutoRetry(
        enabled: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setAutoRetry(it, enabled) }
    }

    /** `abort_retry` — cancel the pending retry delay and stop retrying. */
    suspend fun abortRetry(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        call(timeoutMs) { PiCommands.abortRetry(it) }
    }

    // --------------------------------------------------------------------- bash

    /**
     * `bash` — run a shell command whose output joins the conversation context
     * on the *next* prompt (docs/rpc.md §bash).
     *
     * Uses [SLOW_TIMEOUT_MS] because the command is arbitrary and streams
     * `bash_execution_update` events while it runs; the response carries only the
     * final result, so a short timeout would abort a slow command's *reply* even
     * though pi is still working. `fullOutputPath` is set when [PiResponses.BashResult.truncated].
     */
    suspend fun bash(
        command: String,
        excludeFromContext: Boolean? = null,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.BashResult =
        call(timeoutMs) { PiCommands.bash(it, command, excludeFromContext) }
            .requireData(PiResponses::bashResult)

    /** `abort_bash` — abort the bash command that is currently running. */
    suspend fun abortBash(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        call(timeoutMs) { PiCommands.abortBash(it) }
    }

    // ------------------------------------------------------------------ session

    /** `abort` — abort the current operation and wait for the session to go idle. */
    suspend fun abort(timeoutMs: Long = SLOW_TIMEOUT_MS) {
        call(timeoutMs) { PiCommands.abort(it) }
    }

    /**
     * `export_html`; returns the path pi wrote.
     *
     * [SLOW_TIMEOUT_MS] because the export renders the whole session, and
     * [outputPath] is optional — pi picks a default next to the session file when
     * it is omitted.
     */
    suspend fun exportHtml(
        outputPath: String? = null,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): String =
        call(timeoutMs) { PiCommands.exportHtml(it, outputPath) }
            .requireData(PiResponses::exportResult)
            .path
            ?: throw PiRpcException("export_html", "pi returned no path")

    /**
     * `new_session` — start a fresh session.
     *
     * [SLOW_TIMEOUT_MS] and a result rather than `Unit`, because a
     * `session_before_switch` extension handler can block on a user dialog, and
     * the documented answer `cancelled: true` means the switch did **not**
     * happen — the session must not be re-read as if it had.
     */
    suspend fun newSession(
        parentSession: String? = null,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.CancelledResult =
        call(timeoutMs) { PiCommands.newSession(it, parentSession) }
            .requireData(PiResponses::cancelledResult)

    /** `switch_session`; see [newSession] for why the timeout is long. */
    suspend fun switchSession(
        sessionPath: String,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.CancelledResult =
        call(timeoutMs) { PiCommands.switchSession(it, sessionPath) }
            .requireData(PiResponses::cancelledResult)

    /**
     * `fork` — branch a new session from [entryId], returning the text of the
     * message being forked from.
     *
     * pi's `fork` handler returns `{ text: result.selectedText, cancelled }` and
     * `selectedText` is optional in `agent-session-runtime.ts`, so [PiResponses.ForkResult.text]
     * can legitimately be `null` even on success.
     */
    suspend fun fork(
        entryId: String,
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.ForkResult =
        call(timeoutMs) { PiCommands.fork(it, entryId) }
            .requireData(PiResponses::forkResult)

    /**
     * pi's `clone` — duplicate the active branch at the current position.
     *
     * Named `cloneSession` rather than `clone` only to stay clear of
     * `java.lang.Object.clone` at the JVM boundary.
     */
    suspend fun cloneSession(
        timeoutMs: Long = SLOW_TIMEOUT_MS,
    ): PiResponses.CancelledResult =
        call(timeoutMs) { PiCommands.clone(it) }
            .requireData(PiResponses::cancelledResult)

    /**
     * `set_session_name`; the name is readable back from [getState].
     *
     * pi rejects a blank name with `success: false` (`rpc-mode.ts` trims and
     * checks), which surfaces as [PiRpcException] rather than a silent no-op.
     */
    suspend fun setSessionName(
        name: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        call(timeoutMs) { PiCommands.setSessionName(it, name) }
    }

    // ----------------------------------------------------------------- internals

    private suspend fun call(
        timeoutMs: Long,
        build: (String) -> JsonObject,
    ): PiEvent.Response {
        val response = session.request(build, timeoutMs)
        if (!response.success) {
            throw PiRpcException(
                command = response.command,
                // `request` synthesises `success: false` for its own failures
                // (timeout, engine death) with `command = null`; the error text is
                // still the only description of what happened, so keep it.
                reason = response.error ?: "pi rejected the command",
            )
        }
        return response
    }

    /**
     * Read a payload the command is documented to always return. A response
     * whose `data` is absent, JSON `null`, or unreadable in the shape the
     * command documents is an error, because the operation's result cannot be
     * reported to the user.
     */
    private fun <T : Any> PiEvent.Response.requireData(reader: (PiEvent.Response) -> T?): T =
        reader(this) ?: throw PiRpcException(
            command = command,
            reason = "response carried no readable data",
        )

    /**
     * Read a payload pi documents as nullable. `data: null` (or no `data` at
     * all) reads as `null`; a present-but-unreadable payload is still an error,
     * so "no model to cycle to" cannot be confused with "we could not parse the
     * model".
     */
    private fun <T : Any> PiEvent.Response.optionalData(reader: (PiEvent.Response) -> T?): T? =
        if (PiResponses.dataIsNull(this)) null else requireData(reader)

    companion object {
        /** pi's own request/response latency budget for ordinary commands. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /**
         * For commands that run an LLM summarization, an arbitrary shell command,
         * or that can block on an extension UI dialog answered by the user.
         */
        const val SLOW_TIMEOUT_MS = 600_000L
    }
}
