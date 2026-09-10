package app.pi.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Lifecycle state of one tool invocation, driving the card's container colour. */
enum class ToolStatus { Pending, Success, Error }

/**
 * One rendered block in the conversation stream.
 *
 * [key] is stable for the life of a block — it is either pi's own entry id or a
 * synthesised id for a live block — which is what lets the UI keep scroll
 * position and animate the right row. See docs/pi-android-ui-spec.md §4.2.
 */
sealed interface TranscriptItem {
    val key: String
    val ts: Long
}

data class UserMessage(
    override val key: String,
    override val ts: Long,
    val text: String,
    val images: List<PiImage> = emptyList(),
) : TranscriptItem

data class AssistantText(
    override val key: String,
    override val ts: Long,
    val text: String,
    val streaming: Boolean = false,
) : TranscriptItem

data class ThinkingBlock(
    override val key: String,
    override val ts: Long,
    val text: String,
    val streaming: Boolean = true,
) : TranscriptItem

data class ToolCall(
    override val key: String,
    override val ts: Long,
    val toolCallId: String,
    val toolName: String,
    val args: JsonObject? = null,
    val status: ToolStatus = ToolStatus.Pending,
    val output: String = "",
    val isError: Boolean = false,
    val details: JsonElement? = null,
    val endedAt: Long? = null,
) : TranscriptItem

/** A compaction marker, a retry notice, a model switch — anything single-line. */
data class Notice(
    override val key: String,
    override val ts: Long,
    val text: String,
    val tone: Tone = Tone.Info,
) : TranscriptItem {
    enum class Tone { Info, Warning, Error }
}

/**
 * What the last event did to the stream, so the UI can update one row instead
 * of recomposing the list. [Updated] is the hot path during streaming.
 */
sealed interface TranscriptChange {
    data object None : TranscriptChange
    data class Appended(val index: Int) : TranscriptChange
    data class Updated(val index: Int) : TranscriptChange
    data class RemovedFrom(val index: Int) : TranscriptChange
}

/**
 * Folds pi's event stream into an ordered list of [TranscriptItem]s.
 *
 * This is a pure state machine over events — no Android, no coroutines, no
 * clock of its own ([now] is injected) — which is why it lives in the `:rpc`
 * module and is covered by unit tests without a device.
 *
 * pi remains the single source of truth for a session; this projection exists
 * only to drive the screen. Re-attaching after a reconnect replays from
 * `get_entries { since }` (see [seedFromHistory]) rather than re-deriving state.
 */
class TranscriptReducer(private val now: () -> Long = { System.currentTimeMillis() }) {

    private val items = mutableListOf<TranscriptItem>()
    private val toolIndexByCallId = mutableMapOf<String, Int>()
    private var seq = 0

    /** Live, read-only view of the stream. */
    val transcript: List<TranscriptItem> get() = items

    /** True while assistant text or thinking is still growing. */
    var streaming: Boolean = false
        private set

    private fun nextKey(prefix: String) = "$prefix-${now()}-${seq++}"

    /**
     * Record the prompt the user just sent. Done locally (not from an event) so
     * the bubble appears the instant they hit send.
     */
    fun onUserPrompt(text: String, images: List<PiImage> = emptyList()): TranscriptChange {
        items += UserMessage(
            key = nextKey("user"),
            ts = now(),
            text = text,
            images = images,
        )
        return TranscriptChange.Appended(items.lastIndex)
    }

    /** Fold one engine event. Never throws. */
    fun onEvent(event: PiEvent): TranscriptChange = when (event) {
        is PiEvent.MessageUpdate -> onMessageUpdate(event)
        is PiEvent.MessageEnd -> finishStreaming()
        PiEvent.AgentStart -> {
            streaming = true
            TranscriptChange.None
        }
        is PiEvent.AgentEnd -> finishStreaming()
        PiEvent.AgentSettled -> finishStreaming()

        is PiEvent.ToolExecutionStart -> onToolStart(event)
        is PiEvent.ToolExecutionUpdate -> onToolUpdate(event)
        is PiEvent.ToolExecutionEnd -> onToolEnd(event)

        is PiEvent.CompactionStart -> append(
            Notice(
                key = nextKey("compaction"),
                ts = now(),
                text = "正在压缩上下文…" + (event.reason?.let { " ($it)" } ?: ""),
            ),
        )

        is PiEvent.CompactionEnd -> {
            val text = when {
                event.errorMessage != null -> "上下文压缩失败：${event.errorMessage}"
                event.aborted -> "上下文压缩已中止"
                event.willRetry -> "上下文压缩将重试"
                else -> "上下文已压缩"
            }
            append(
                Notice(
                    key = nextKey("compaction-done"),
                    ts = now(),
                    text = text,
                    tone = if (event.errorMessage != null) Notice.Tone.Error else Notice.Tone.Info,
                ),
            )
        }

        is PiEvent.AutoRetryStart -> append(
            Notice(
                key = nextKey("retry"),
                ts = now(),
                text = "正在重试${event.attempt?.let { " $it" } ?: ""}" +
                    (event.maxAttempts?.let { "/$it" } ?: "") +
                    (event.delayMs?.let { "，${it / 1000}s 后" } ?: "") +
                    (event.errorMessage?.let { "：$it" } ?: ""),
                tone = Notice.Tone.Warning,
            ),
        )

        is PiEvent.AutoRetryEnd -> if (event.success == false) {
            append(
                Notice(
                    key = nextKey("retry-failed"),
                    ts = now(),
                    text = "重试失败，已停止",
                    tone = Notice.Tone.Error,
                ),
            )
        } else {
            TranscriptChange.None
        }

        is PiEvent.ExtensionError -> append(
            Notice(
                key = nextKey("ext-error"),
                ts = now(),
                text = "扩展出错：${event.message ?: "未知错误"}",
                tone = Notice.Tone.Error,
            ),
        )

        else -> TranscriptChange.None
    }

    private fun onMessageUpdate(event: PiEvent.MessageUpdate): TranscriptChange {
        val delta = event.delta ?: return TranscriptChange.None
        streaming = true
        return when (delta) {
            is AssistantDelta.TextDelta -> {
                val index = items.indexOfLast { it is AssistantText && it.streaming }
                if (index >= 0) {
                    val current = items[index] as AssistantText
                    items[index] = current.copy(text = current.text + delta.delta)
                    TranscriptChange.Updated(index)
                } else {
                    append(
                        AssistantText(
                            key = nextKey("assistant"),
                            ts = now(),
                            text = delta.delta,
                            streaming = true,
                        ),
                    )
                }
            }

            is AssistantDelta.ThinkingDelta -> {
                val index = items.indexOfLast { it is ThinkingBlock && it.streaming }
                if (index >= 0) {
                    val current = items[index] as ThinkingBlock
                    items[index] = current.copy(text = current.text + delta.delta)
                    TranscriptChange.Updated(index)
                } else {
                    append(
                        ThinkingBlock(
                            key = nextKey("thinking"),
                            ts = now(),
                            text = delta.delta,
                            streaming = true,
                        ),
                    )
                }
            }

            // A tool call announces itself before its arguments finish streaming,
            // so the card can show the tool name immediately.
            is AssistantDelta.ToolCallStart -> {
                val callId = delta.id ?: nextKey("toolcall")
                if (toolIndexByCallId.containsKey(callId)) {
                    TranscriptChange.None
                } else {
                    toolIndexByCallId[callId] = items.size
                    append(
                        ToolCall(
                            key = callId,
                            ts = now(),
                            toolCallId = callId,
                            toolName = delta.toolName.orEmpty(),
                            status = ToolStatus.Pending,
                        ),
                    )
                }
            }

            is AssistantDelta.Error -> append(
                Notice(
                    key = nextKey("provider-error"),
                    ts = now(),
                    text = "模型调用失败：${delta.reason ?: "未知原因"}",
                    tone = Notice.Tone.Error,
                ),
            )

            else -> TranscriptChange.None
        }
    }

    private fun onToolStart(event: PiEvent.ToolExecutionStart): TranscriptChange {
        streaming = true
        val existing = toolIndexByCallId[event.toolCallId]
        if (existing != null) {
            val current = items[existing]
            if (current is ToolCall) {
                items[existing] = current.copy(
                    toolName = event.toolName.ifEmpty { current.toolName },
                    args = event.args ?: current.args,
                    status = ToolStatus.Pending,
                )
                return TranscriptChange.Updated(existing)
            }
        }
        toolIndexByCallId[event.toolCallId] = items.size
        return append(
            ToolCall(
                key = event.toolCallId,
                ts = now(),
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                args = event.args,
                status = ToolStatus.Pending,
            ),
        )
    }

    private fun onToolUpdate(event: PiEvent.ToolExecutionUpdate): TranscriptChange {
        val index = toolIndexByCallId[event.toolCallId] ?: return TranscriptChange.None
        val current = items.getOrNull(index) as? ToolCall ?: return TranscriptChange.None
        val chunk = event.partialText ?: return TranscriptChange.None
        // Engine updates are cumulative snapshots for some tools and deltas for
        // others; take the longer of "append" and "replace" so neither regresses.
        val merged = when {
            chunk.isEmpty() -> current.output
            current.output.isEmpty() -> chunk
            chunk.length >= current.output.length && chunk.startsWith(current.output.take(64)) -> chunk
            else -> current.output + chunk
        }
        items[index] = current.copy(output = merged)
        return TranscriptChange.Updated(index)
    }

    private fun onToolEnd(event: PiEvent.ToolExecutionEnd): TranscriptChange {
        val index = toolIndexByCallId[event.toolCallId]
        if (index == null) {
            toolIndexByCallId[event.toolCallId] = items.size
            return append(
                ToolCall(
                    key = event.toolCallId,
                    ts = now(),
                    toolCallId = event.toolCallId,
                    toolName = event.toolName.orEmpty(),
                    status = if (event.isError) ToolStatus.Error else ToolStatus.Success,
                    output = event.resultText.orEmpty(),
                    isError = event.isError,
                    details = event.details,
                    endedAt = now(),
                ),
            )
        }
        val current = items.getOrNull(index) as? ToolCall ?: return TranscriptChange.None
        items[index] = current.copy(
            toolName = event.toolName ?: current.toolName,
            status = if (event.isError) ToolStatus.Error else ToolStatus.Success,
            output = event.resultText ?: current.output,
            isError = event.isError,
            details = event.details ?: current.details,
            endedAt = now(),
        )
        return TranscriptChange.Updated(index)
    }

    private fun finishStreaming(): TranscriptChange {
        streaming = false
        var touched = -1
        for (i in items.indices) {
            when (val item = items[i]) {
                is AssistantText -> if (item.streaming) {
                    items[i] = item.copy(streaming = false); touched = i
                }
                is ThinkingBlock -> if (item.streaming) {
                    items[i] = item.copy(streaming = false); touched = i
                }
                else -> Unit
            }
        }
        return if (touched >= 0) TranscriptChange.Updated(touched) else TranscriptChange.None
    }

    private fun append(item: TranscriptItem): TranscriptChange {
        items += item
        return TranscriptChange.Appended(items.lastIndex)
    }

    /** Drop everything (new session, or a different session was opened). */
    fun reset() {
        items.clear()
        toolIndexByCallId.clear()
        streaming = false
    }
}
