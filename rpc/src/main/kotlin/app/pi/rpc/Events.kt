package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Token / cost accounting as pi reports it. */
data class TokenUsage(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val reasoning: Long? = null,
    val totalTokens: Long? = null,
    val cost: Double? = null,
)

/** Streaming granularity inside `message_update`. */
sealed interface AssistantDelta {
    val kind: String

    data object Start : AssistantDelta { override val kind = "start" }
    data object TextStart : AssistantDelta { override val kind = "text_start" }
    data class TextDelta(val contentIndex: Int, val delta: String) : AssistantDelta {
        override val kind = "text_delta"
    }
    /**
     * The completed text block. [content] is pi's authoritative text for that
     * block (`packages/ai/src/types.ts`: `{ type: "text_end"; contentIndex;
     * content; partial }`), so a dropped or reordered delta cannot corrupt the
     * final row.
     */
    data class TextEnd(val contentIndex: Int, val content: String?) : AssistantDelta {
        override val kind = "text_end"
    }
    data object ThinkingStart : AssistantDelta { override val kind = "thinking_start" }
    data class ThinkingDelta(val contentIndex: Int, val delta: String) : AssistantDelta {
        override val kind = "thinking_delta"
    }
    data object ThinkingEnd : AssistantDelta { override val kind = "thinking_end" }
    data class ToolCallStart(val id: String?, val toolName: String?) : AssistantDelta {
        override val kind = "toolcall_start"
    }
    data class ToolCallDelta(val contentIndex: Int, val delta: String) : AssistantDelta {
        override val kind = "toolcall_delta"
    }

    /**
     * The completed tool call. pi sends the whole `toolCall` object here
     * (`json-event.ts` passes it through unchanged), which is the first place the
     * fully-assembled arguments appear — `tool_execution_start` usually carries
     * them too, but this closes the window while arguments stop streaming.
     */
    data class ToolCallEnd(
        val contentIndex: Int,
        val id: String?,
        val name: String?,
        val arguments: JsonObject?,
    ) : AssistantDelta {
        override val kind = "toolcall_end"
    }
    data class Done(val reason: String?) : AssistantDelta { override val kind = "done" }
    data class Error(val reason: String?) : AssistantDelta { override val kind = "error" }

    /** An event kind a newer pi introduced. Rendered as a generic notice. */
    data class Unknown(override val kind: String) : AssistantDelta
}

/**
 * Everything pi can push on stdout.
 *
 * Parsing never throws: an unrecognised `type`, a missing field, or a shape
 * change in a newer pi degrades to [Unknown] carrying the raw object. That is a
 * hard product requirement — an engine upgrade must degrade the UI, never blank
 * it (docs/pi-android-ui-spec.md §1 principle 5 / AGENTS-style rule "未知即兜底").
 */
sealed interface PiEvent {
    val type: String

    /** Reply to a command. `success` is pi's own verdict, not "we got a packet". */
    data class Response(
        val id: String?,
        val command: String?,
        val success: Boolean,
        val error: String?,
        val data: JsonElement?,
    ) : PiEvent {
        override val type = "response"
    }

    data object AgentStart : PiEvent { override val type = "agent_start" }
    data class AgentEnd(val willRetry: Boolean?) : PiEvent { override val type = "agent_end" }
    data object AgentSettled : PiEvent { override val type = "agent_settled" }

    /**
     * pi's `turn_start` / `turn_end` carry **no** turn index on the RPC wire.
     *
     * This is easy to get wrong, because two *other* shapes do have one, and a
     * reviewer who finds them will flip this comment back:
     *
     *  - `packages/agent/src/types.ts` declares the agent-level events as
     *    `{ type: "turn_start" }` and
     *    `{ type: "turn_end"; message; toolResults }` — no `turnIndex`.
     *  - `core/extensions/types.ts` declares `TurnStartEvent`/`TurnEndEvent`
     *    **with** `turnIndex` (plus `timestamp`), and
     *    `AgentSession._emitExtensionEvent` builds exactly those.
     *
     * The chain decides it: `_handleAgentEvent` first calls
     * `_emitExtensionEvent(event)` (extension handlers only) and then
     * `this._emit(event)` with the **original** `AgentEvent`. `RpcMode` prints
     * `session.subscribe(...)` through `toJsonEvent`, which returns every
     * non-`message_update` record unchanged. So the `turnIndex`-bearing objects
     * never reach stdout, and the app is right to model the count-free shape.
     *
     * The transcript does not need a turn index: turns are delimited by the
     * entries between them.
     */
    data object TurnStart : PiEvent { override val type = "turn_start" }
    data class TurnEnd(val toolResultCount: Int) : PiEvent { override val type = "turn_end" }

    data class MessageStart(val role: String?) : PiEvent { override val type = "message_start" }
    data class MessageUpdate(
        val delta: AssistantDelta?,
        val usage: TokenUsage?,
    ) : PiEvent {
        override val type = "message_update"
    }

    data class MessageEnd(
        val role: String?,
        val text: String?,
        val stopReason: String?,
        val usage: TokenUsage?,
        /**
         * `customType` and `display` of a `role: "custom"` message — an
         * extension's injected context (`core/messages.ts#CustomMessage`).
         *
         * The transcript renders those through [TranscriptReducer.onHookMessage],
         * which history replay already reaches via `onEntry` (`custom_message`
         * entries). Without these two fields the *live* chain had nothing to
         * call it with, so injected context appeared only after a reload.
         */
        val customType: String? = null,
        val display: Boolean? = null,
        /**
         * pi's own failure text for this message. It is what
         * `components/assistant-message.ts` prints under an aborted/errored
         * answer, and the only detail available when `stopReason` is `error`.
         */
        val errorMessage: String? = null,
    ) : PiEvent {
        override val type = "message_end"
    }

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject?,
    ) : PiEvent {
        override val type = "tool_execution_start"
    }

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String?,
        val partialText: String?,
    ) : PiEvent {
        override val type = "tool_execution_update"
    }

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String?,
        val resultText: String?,
        val isError: Boolean,
        val details: JsonElement?,
        /**
         * Image blocks of the result, kept as images instead of being flattened
         * into [resultText] as the literal `[image]`.
         *
         * pi renders every `content` block of type `image`
         * (`components/tool-execution.ts`), so a screenshot tool or a `read` of a
         * PNG is a picture there. The bytes cannot be decoded without an image
         * loader (`docs/known-gaps.md` A3), but the payload is preserved here so
         * the transcript can at least list what came back.
         */
        val resultImages: List<PiImage> = emptyList(),
    ) : PiEvent {
        override val type = "tool_execution_end"
    }

    /** Incremental output of a user-initiated `bash` command. */
    data class BashExecutionUpdate(val commandId: String?, val delta: String?) : PiEvent {
        override val type = "bash_execution_update"
    }

    data class QueueUpdate(
        val steering: List<String>,
        val followUp: List<String>,
    ) : PiEvent {
        override val type = "queue_update"
    }

    data class CompactionStart(val reason: String?) : PiEvent {
        override val type = "compaction_start"
    }

    data class CompactionEnd(
        val reason: String?,
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String?,
        /**
         * pi's `compaction_end` carries the full `CompactionResult`
         * (`core/agent-session.ts`), which is the only live source of the
         * summary: the `compaction` session entry that also holds it is
         * appended by the session manager, and `entry_appended` fires only for
         * the extension `appendEntry` path. Without this the live compaction
         * block stays unlabelled until the session is reopened.
         *
         * Null on abort/failure, where pi sends `result: undefined`
         * (`docs/rpc.md` §compaction_end).
         */
        val result: PiResponses.CompactionResult? = null,
    ) : PiEvent {
        override val type = "compaction_end"
    }

    data class AutoRetryStart(
        val attempt: Int?,
        val maxAttempts: Int?,
        val delayMs: Long?,
        val errorMessage: String?,
    ) : PiEvent {
        override val type = "auto_retry_start"
    }

    data class AutoRetryEnd(
        val success: Boolean?,
        val attempt: Int?,
        val finalError: String?,
    ) : PiEvent {
        override val type = "auto_retry_end"
    }

    /**
     * Summarization retries are a separate path from `auto_retry_*`: they fire
     * when compaction or branch summarisation fails, and pi emits three shapes.
     * Ignoring them would silently hide a stalling context — they are rare
     * enough that a user seeing nothing would assume the agent had hung.
     */
    data class SummarizationRetryScheduled(
        val attempt: Int?,
        val maxAttempts: Int?,
        val delayMs: Long?,
        val errorMessage: String?,
    ) : PiEvent {
        override val type = "summarization_retry_scheduled"
    }

    data class SummarizationRetryAttemptStart(
        /** `branchSummary` or `compaction`. */
        val source: String?,
        /** Present only when [source] is `compaction`. */
        val reason: String?,
    ) : PiEvent {
        override val type = "summarization_retry_attempt_start"
    }

    data object SummarizationRetryFinished : PiEvent {
        override val type = "summarization_retry_finished"
    }

    /**
     * A blocking dialog from an extension (`ctx.ui.select/confirm/input/editor`)
     * or a fire-and-forget notification.
     *
     * Field names are pi's own (`rpc-types.ts` `RpcExtensionUIRequest`), including
     * the ones only some methods carry: `prefill` on `editor`, `notifyType` on
     * `notify`, `statusKey`/`statusText` on `setStatus`, and
     * `widgetKey`/`widgetLines`/`widgetPlacement` on `setWidget`. Modelling them
     * as one wide type keeps the parser total (a request never fails to parse
     * because a field it did not expect was missing).
     *
     * Blocking methods (`select`/`confirm`/`input`/`editor`) must be answered
     * with `PiCommands.extensionUi*`; note [timeoutMs] — pi resolves on its own
     * when it elapses, so the UI has to count down.
     */
    data class ExtensionUiRequest(
        val uiId: String,
        val method: String,
        val title: String?,
        val message: String?,
        val options: List<String>,
        val placeholder: String?,
        /** `editor`'s initial text; `set_editor_text`'s payload. */
        val text: String?,
        /** `notify`'s severity: `info` / `warning` / `error`. */
        val notifyType: String?,
        /** `setStatus` key, and its text (null clears the row). */
        val statusKey: String?,
        val statusText: String?,
        /** `setWidget` key, its lines, and `aboveEditor` / `belowEditor`. */
        val widgetKey: String?,
        val widgetLines: List<String>,
        val widgetPlacement: String?,
        val timeoutMs: Long?,
        val raw: JsonObject,
    ) : PiEvent {
        override val type = "extension_ui_request"
    }

    /**
     * An extension threw. Carries enough to say *which* one: without
     * [extensionPath] a failure is unattributable, and [event] names the pi hook
     * it blew up in. Both are pi's own field names (`docs/rpc.md` §extension_error;
     * `rpc-mode.ts` emits `{ extensionPath, event, error }`).
     */
    data class ExtensionError(
        val message: String?,
        val extensionPath: String? = null,
        val event: String? = null,
    ) : PiEvent {
        override val type = "extension_error"
    }

    /**
     * An entry pi appended to the session. The payload **is** on the wire:
     * `agent-session.ts` emits `{ type: "entry_appended"; entry: SessionEntry }`
     * and `modes/json-event.ts` passes every non-`message_update` event through
     * unchanged, so the full entry object arrives here. It is currently emitted
     * only by the extension `appendEntry` path, which is precisely why the app
     * must project it rather than wait for a refetch.
     */
    data class EntryAppended(
        val entryId: String?,
        val entryType: String?,
        /** The whole `SessionEntry`; null for a malformed record. */
        val entry: JsonObject? = null,
    ) : PiEvent {
        override val type = "entry_appended"
    }

    data class SessionInfoChanged(val name: String?) : PiEvent {
        override val type = "session_info_changed"
    }

    data class ThinkingLevelChanged(val level: String?) : PiEvent {
        override val type = "thinking_level_changed"
    }

    data class Unknown(override val type: String, val raw: JsonObject) : PiEvent
}

object PiEvents {

    /** Parse one wire record. Never throws. */
    fun parse(record: String): PiEvent {
        val obj = try {
            app.pi.rpc.internal.Json.parseObject(record)
        } catch (t: Throwable) {
            return PiEvent.Unknown("__unparsable__", JsonObject(emptyMap()))
        } ?: return PiEvent.Unknown("__unparsable__", JsonObject(emptyMap()))

        val type = obj.str("type") ?: return PiEvent.Unknown("", obj)
        return try {
            dispatch(type, obj)
        } catch (t: Throwable) {
            // A field we expected had an unexpected shape. Keep the payload.
            PiEvent.Unknown(type, obj)
        }
    }

    private fun dispatch(type: String, o: JsonObject): PiEvent = when (type) {
        "response" -> PiEvent.Response(
            id = o.str("id"),
            command = o.str("command"),
            success = o.bool("success") ?: false,
            error = o.str("error"),
            data = o["data"],
        )

        "agent_start" -> PiEvent.AgentStart
        "agent_end" -> PiEvent.AgentEnd(o.bool("willRetry"))
        "agent_settled" -> PiEvent.AgentSettled

        // pi sends no turn index; `toolResults` is an array we only need a count of.
        "turn_start" -> PiEvent.TurnStart
        "turn_end" -> PiEvent.TurnEnd(
            toolResultCount = (o["toolResults"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0,
        )

        "message_start" -> PiEvent.MessageStart(o.obj("message")?.str("role") ?: o.str("role"))
        "message_update" -> {
            val ame = o.obj("assistantMessageEvent")
            PiEvent.MessageUpdate(
                delta = ame?.let { parseDelta(it) },
                usage = o.obj("usage")?.let { parseUsage(it) },
            )
        }

        "message_end" -> {
            val msg = o.obj("message")
            PiEvent.MessageEnd(
                role = msg?.str("role") ?: o.str("role"),
                text = msg?.let { contentText(it["content"]) } ?: o.str("text"),
                stopReason = msg?.str("stopReason") ?: o.str("stopReason"),
                usage = msg?.obj("usage")?.let { parseUsage(it) } ?: o.obj("usage")?.let { parseUsage(it) },
                customType = msg?.str("customType") ?: o.str("customType"),
                display = msg?.bool("display") ?: o.bool("display"),
                errorMessage = msg?.str("errorMessage") ?: o.str("errorMessage"),
            )
        }

        "tool_execution_start" -> PiEvent.ToolExecutionStart(
            toolCallId = o.str("toolCallId").orEmpty(),
            toolName = o.str("toolName").orEmpty(),
            args = o["args"] as? JsonObject,
        )

        "tool_execution_update" -> PiEvent.ToolExecutionUpdate(
            toolCallId = o.str("toolCallId").orEmpty(),
            toolName = o.str("toolName"),
            partialText = o.obj("partialResult")?.let { contentText(it["content"]) },
        )

        "tool_execution_end" -> {
            val result = o.obj("result")
            PiEvent.ToolExecutionEnd(
                toolCallId = o.str("toolCallId").orEmpty(),
                toolName = o.str("toolName"),
                resultText = result?.let { contentText(it["content"]) },
                isError = o.bool("isError") ?: false,
                details = result?.get("details") ?: o["details"],
                resultImages = imageBlocks(result?.get("content")),
            )
        }

        "bash_execution_update" -> PiEvent.BashExecutionUpdate(
            commandId = o.str("id"),
            delta = o.str("delta") ?: o.obj("partialResult")?.let { contentText(it["content"]) },
        )

        "queue_update" -> PiEvent.QueueUpdate(
            steering = o.strList("steering"),
            followUp = o.strList("followUp") + o.strList("follow_up"),
        )

        "compaction_start" -> PiEvent.CompactionStart(o.str("reason"))
        "compaction_end" -> PiEvent.CompactionEnd(
            reason = o.str("reason"),
            aborted = o.bool("aborted") ?: false,
            willRetry = o.bool("willRetry") ?: false,
            errorMessage = o.str("errorMessage"),
            // `result` is `undefined` (field omitted) on abort/failure and a
            // full `CompactionResult` on success.
            result = PiResponses.compactionResult(o["result"]),
        )

        "auto_retry_start" -> PiEvent.AutoRetryStart(
            attempt = o.int("attempt"),
            maxAttempts = o.int("maxAttempts"),
            delayMs = o.long("delayMs"),
            errorMessage = o.str("errorMessage"),
        )

        "auto_retry_end" -> PiEvent.AutoRetryEnd(
            success = o.bool("success"),
            attempt = o.int("attempt"),
            finalError = o.str("finalError"),
        )

        "summarization_retry_scheduled" -> PiEvent.SummarizationRetryScheduled(
            attempt = o.int("attempt"),
            maxAttempts = o.int("maxAttempts"),
            delayMs = o.long("delayMs"),
            errorMessage = o.str("errorMessage"),
        )

        "summarization_retry_attempt_start" -> PiEvent.SummarizationRetryAttemptStart(
            source = o.str("source"),
            reason = o.str("reason"),
        )

        "summarization_retry_finished" -> PiEvent.SummarizationRetryFinished

        "extension_ui_request" -> PiEvent.ExtensionUiRequest(
            uiId = o.str("id").orEmpty(),
            method = o.str("method").orEmpty(),
            title = o.str("title"),
            message = o.str("message"),
            options = o.strList("options"),
            placeholder = o.str("placeholder"),
            // `editor` sends its initial content as `prefill`; `set_editor_text`
            // sends `text`.
            text = o.str("prefill") ?: o.str("text") ?: o.str("value"),
            notifyType = o.str("notifyType"),
            statusKey = o.str("statusKey"),
            statusText = o.str("statusText"),
            widgetKey = o.str("widgetKey"),
            widgetLines = o.strList("widgetLines"),
            widgetPlacement = o.str("widgetPlacement"),
            timeoutMs = o.long("timeout") ?: o.long("timeoutMs"),
            raw = o,
        )

        "extension_error" -> PiEvent.ExtensionError(
            message = o.str("message") ?: o.str("error"),
            extensionPath = o.str("extensionPath"),
            event = o.str("event"),
        )

        "entry_appended" -> {
            val entry = o.obj("entry")
            PiEvent.EntryAppended(
                entryId = entry?.str("id") ?: o.str("entryId"),
                entryType = entry?.str("type") ?: o.str("entryType"),
                entry = entry,
            )
        }

        "session_info_changed" -> PiEvent.SessionInfoChanged(o.str("name"))
        "thinking_level_changed" -> PiEvent.ThinkingLevelChanged(o.str("level"))

        // Everything newer than this build. Never fatal.
        else -> PiEvent.Unknown(type, o)
    }

    private fun parseDelta(e: JsonObject): AssistantDelta {
        val kind = e.str("type").orEmpty()
        return when (kind) {
            "start" -> AssistantDelta.Start
            "text_start" -> AssistantDelta.TextStart
            "text_delta" -> AssistantDelta.TextDelta(
                contentIndex = e.int("contentIndex") ?: 0,
                delta = e.str("delta").orEmpty(),
            )
            "text_end" -> AssistantDelta.TextEnd(
                contentIndex = e.int("contentIndex") ?: 0,
                content = e.str("content"),
            )
            "thinking_start" -> AssistantDelta.ThinkingStart
            "thinking_delta" -> AssistantDelta.ThinkingDelta(
                contentIndex = e.int("contentIndex") ?: 0,
                delta = e.str("delta").orEmpty(),
            )
            "thinking_end" -> AssistantDelta.ThinkingEnd
            "toolcall_start" -> AssistantDelta.ToolCallStart(
                id = e.str("id") ?: e.str("toolCallId"),
                toolName = e.str("toolName"),
            )
            "toolcall_delta" -> AssistantDelta.ToolCallDelta(
                contentIndex = e.int("contentIndex") ?: 0,
                delta = e.str("delta").orEmpty(),
            )
            "toolcall_end" -> {
                // `json-event.ts` forwards the whole `toolCall` object for this
                // delta, so the id/name/arguments are available here.
                val call = e.obj("toolCall")
                AssistantDelta.ToolCallEnd(
                    contentIndex = e.int("contentIndex") ?: 0,
                    id = call?.str("id") ?: e.str("id") ?: e.str("toolCallId"),
                    name = call?.str("name") ?: e.str("toolName"),
                    arguments = call?.obj("arguments") ?: e.obj("arguments"),
                )
            }
            // pi's `done` delta is `{ type: "done"; reason; message }`
            // (`packages/ai/src/types.ts`), and `toJsonEvent` strips only
            // `partial`, so the wire field is `reason` — never `stopReason`.
            "done" -> AssistantDelta.Done(e.str("reason"))
            "error" -> AssistantDelta.Error(e.str("reason"))
            else -> AssistantDelta.Unknown(kind)
        }
    }

    fun parseUsage(o: JsonObject): TokenUsage = TokenUsage(
        input = o.long("input"),
        output = o.long("output"),
        cacheRead = o.long("cacheRead"),
        cacheWrite = o.long("cacheWrite"),
        reasoning = o.long("reasoning"),
        totalTokens = o.long("totalTokens"),
        cost = o.dbl("cost"),
    )
}

// ------------------------------------------------------------------ JSON access

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = long(key)?.let {
    if (it > Int.MAX_VALUE || it < Int.MIN_VALUE) null else it.toInt()
}

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.dbl(key: String): Double? = when (val v = this[key]) {
    is JsonPrimitive -> v.content.toDoubleOrNull()
    is JsonObject -> v.dbl("total") // pi sometimes nests { total, ... }
    else -> null
}

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/**
 * Image blocks of a pi content array, in pi's `ImageContent` shape.
 *
 * Kept separate from [contentText] — which is display text and turns an image
 * into the four characters `[image]` — so a tool result's images survive as
 * payload instead of being replaced by a marker.
 */
internal fun imageBlocks(element: JsonElement?): List<PiImage> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { block ->
        val obj = block as? JsonObject ?: return@mapNotNull null
        if (obj.str("type") != "image") return@mapNotNull null
        val data = obj.str("data") ?: return@mapNotNull null
        PiImage(base64 = data, mimeType = obj.str("mimeType") ?: "image/png")
    }
}

/**
 * Flatten a pi content array (`[{"type":"text","text":"…"}, {"type":"image",…}]`)
 * into display text. Images become a short marker rather than base64 noise.
 */
internal fun contentText(element: JsonElement?): String? {
    val text: String = when (element) {
        null -> ""
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString("") { block ->
            when (block) {
                is JsonPrimitive -> block.content
                is JsonObject -> when (block.str("type")) {
                    "text" -> block.str("text").orEmpty()
                    "image" -> "[image]"
                    else -> block.str("text").orEmpty()
                }
                else -> ""
            }
        }
        is JsonObject -> element.str("text") ?: contentText(element["content"]).orEmpty()
    }
    return text.takeIf { it.isNotEmpty() }
}
