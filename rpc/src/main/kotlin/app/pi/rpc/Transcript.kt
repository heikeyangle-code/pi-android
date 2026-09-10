package app.pi.rpc

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lifecycle state of one tool invocation, driving the card's container colour. */
enum class ToolStatus { Pending, Success, Error }

/**
 * One rendered block in the conversation stream.
 *
 * [key] is stable for the life of a block — it is either pi's own entry id or a
 * synthesised id for a live block — which is what lets the UI keep scroll
 * position and animate the right row. See docs/pi-android-ui-spec.md §4.2.
 *
 * The sealed hierarchy covers pi's 14 conversation block kinds
 * (docs/pi-android-ui-spec.md §7.4): user-message, assistant-text,
 * thinking-block, tool-execution, tool-diff, compaction, branch-summary,
 * hook-message, model-change, skill-invocation, system-prompt, message-images
 * (carried by [UserMessage.images]), error-text and date-separator. [Notice]
 * stays for the few single-line status rows (auto-retry, extension errors) that
 * are App chrome rather than pi content.
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
    /** pi's thinking level at the time, naming the left colour bar's token. */
    val level: String? = null,
    /** Filled in when the block closes; drives the collapsed "思考 12s" line. */
    val elapsedMs: Long? = null,
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
    val exitCode: Int? = null,
    val outputTruncated: Boolean = false,
) : TranscriptItem {
    /** Wall-clock duration pi's card shows in its footer. */
    val elapsedMs: Long? get() = endedAt?.let { (it - ts).coerceAtLeast(0) }

    /** One-line argument summary for the collapsed card. Never throws. */
    val argsSummary: String get() = summarizeToolArgs(args)

    /** Unified diff carried by this call's `details`, if any. */
    val diffText: String? get() = detailsDiffText(details)
}

/**
 * The `tool-diff` block: a unified diff lifted out of a [ToolCall]'s `details`
 * (`details.diff`, per docs/pi-android-ui-spec.md §7.4) and parsed once here so
 * the UI never has to sniff strings.
 */
data class ToolDiff(
    override val key: String,
    override val ts: Long,
    val path: String = "",
    val diffText: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val truncated: Boolean = false,
    val hunks: List<DiffHunk> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String = "",
) : TranscriptItem {
    /** Total body lines, used by the renderer's "折叠为统计行" rule. */
    val lineCount: Int get() = hunks.sumOf { it.lines.size }
}

/**
 * The `compaction` block. Replaces the old generic [Notice] so the UI can show
 * the summary, the tokens freed and the in-flight state.
 */
data class CompactionMarker(
    override val key: String,
    override val ts: Long,
    val summary: String = "",
    val tokensFreed: Long? = null,
    val firstKeptEntryId: String? = null,
    val status: Status = Status.Done,
    val errorMessage: String? = null,
    val reason: String? = null,
) : TranscriptItem {
    enum class Status { Running, Done, Aborted, Failed }
}

/** The `branch-summary` block: a summary written when navigating the tree. */
data class BranchSummary(
    override val key: String,
    override val ts: Long,
    val summary: String = "",
    val branchId: String? = null,
) : TranscriptItem

/**
 * The `hook-message` block: a message an extension injected into the context
 * (`custom_message` entries). Visually distinct from user and assistant text —
 * it has a custom label and its own surface colour.
 */
data class HookMessage(
    override val key: String,
    override val ts: Long,
    val customType: String = "",
    val markdown: String = "",
) : TranscriptItem

/** The `model-change` block: a single muted line in the stream. */
data class ModelChange(
    override val key: String,
    override val ts: Long,
    val provider: String? = null,
    val modelId: String = "",
) : TranscriptItem

/** The `skill-invocation` block: a skill was expanded, with its body. */
data class SkillInvocation(
    override val key: String,
    override val ts: Long,
    val skillName: String = "",
    val body: String = "",
) : TranscriptItem

/** The `system-prompt` block: collapsed to a size line, expandable to full text. */
data class SystemPrompt(
    override val key: String,
    override val ts: Long,
    val fullText: String = "",
) : TranscriptItem

/** The `error-text` block: one human sentence plus optional raw detail. */
data class ErrorText(
    override val key: String,
    override val ts: Long,
    val message: String = "",
    val detail: String? = null,
) : TranscriptItem

/** The `date-separator` block: a day boundary in the stream. */
data class DateSeparator(
    override val key: String,
    override val ts: Long,
    val label: String = "",
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

// ---------------------------------------------------------------- unified diff

/** Classification of one unified-diff line, so the renderer never sniffs text. */
enum class DiffLineKind { Added, Removed, Context, Header }

/**
 * One line of a diff. [text] has the `+`/`-`/space marker stripped; the renderer
 * rebuilds the 16dp symbol column from [kind] (colour-blind safety: the symbol
 * is always present, never only the colour). Line numbers are 1-based and null
 * on the side where the line does not exist.
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

/**
 * One `@@` hunk. A hunk with a blank [header] carries the file-level header
 * lines (`diff --git`, `index`, `---`, `+++`, `new file mode`, …) that preceded
 * the next hunk — that is how [DiffLineKind.Header] stays representable without
 * a second list.
 */
data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
) {
    /** True when this pseudo-hunk holds only file-level header lines. */
    val isFileHeader: Boolean get() = header.isEmpty()

    val added: Int get() = lines.count { it.kind == DiffLineKind.Added }
    val removed: Int get() = lines.count { it.kind == DiffLineKind.Removed }
}

data class DiffStats(val added: Int, val removed: Int)

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/**
 * Parse a unified diff into hunks. Never throws; text that is not a diff comes
 * back as a single header hunk so the UI still has something honest to show.
 */
fun parseUnifiedDiff(text: String): List<DiffHunk> {
    if (text.isEmpty()) return emptyList()
    // A diff ends with a newline; that newline must not become a phantom blank
    // context line inside the last hunk.
    val body = if (text.endsWith("\n")) text.dropLast(1) else text
    val hunks = mutableListOf<DiffHunk>()
    var headerLines = mutableListOf<DiffLine>()
    var current: DiffHunkBuilder? = null

    fun flushHeader() {
        if (headerLines.isNotEmpty()) {
            hunks += DiffHunk("", 0, 0, 0, 0, headerLines)
            headerLines = mutableListOf()
        }
    }

    fun flushHunk() {
        current?.let { hunks += it.build() }
        current = null
    }

    for (raw in body.split('\n')) {
        val line = raw.removeSuffix("\r")

        if (line.startsWith("@@")) {
            val match = HUNK_HEADER.find(line)
            if (match != null) {
                flushHunk()
                flushHeader()
                current = DiffHunkBuilder(
                    header = line,
                    oldStart = match.groupValues[1].toIntOrNull() ?: 0,
                    oldCount = match.groupValues[2].toIntOrNull() ?: 1,
                    newStart = match.groupValues[3].toIntOrNull() ?: 0,
                    newCount = match.groupValues[4].toIntOrNull() ?: 1,
                )
                continue
            }
        }

        // A new file section: the previous hunk is over even if its counts were
        // short (multi-file diffs).
        if (line.startsWith("diff --git ") || line.startsWith("diff --")) {
            flushHunk()
            headerLines += DiffLine(DiffLineKind.Header, line)
            continue
        }

        val open = current
        if (open != null) open.add(line) else headerLines += DiffLine(DiffLineKind.Header, line)
    }

    flushHunk()
    flushHeader()
    return hunks
}

private class DiffHunkBuilder(
    private val header: String,
    private val oldStart: Int,
    private val oldCount: Int,
    private val newStart: Int,
    private val newCount: Int,
) {
    private val lines = mutableListOf<DiffLine>()
    private var oldNo = oldStart
    private var newNo = newStart

    fun add(raw: String) {
        val line = raw.removeSuffix("\r")
        when {
            line.startsWith("+") -> {
                lines += DiffLine(DiffLineKind.Added, line.drop(1), null, newNo)
                newNo++
            }

            line.startsWith("-") -> {
                lines += DiffLine(DiffLineKind.Removed, line.drop(1), oldNo, null)
                oldNo++
            }

            // "\ No newline at end of file" belongs to the hunk but advances no
            // line counter on either side.
            line.startsWith("\\") -> lines += DiffLine(DiffLineKind.Context, line)

            line.startsWith(" ") -> {
                lines += DiffLine(DiffLineKind.Context, line.drop(1), oldNo, newNo)
                oldNo++
                newNo++
            }

            line.isEmpty() -> {
                lines += DiffLine(DiffLineKind.Context, "", oldNo, newNo)
                oldNo++
                newNo++
            }

            else -> lines += DiffLine(DiffLineKind.Header, line)
        }
    }

    fun build(): DiffHunk = DiffHunk(header, oldStart, oldCount, newStart, newCount, lines)
}

/** Count added / removed lines across every hunk. */
fun diffStats(hunks: List<DiffHunk>): DiffStats {
    var added = 0
    var removed = 0
    for (hunk in hunks) {
        for (line in hunk.lines) {
            when (line.kind) {
                DiffLineKind.Added -> added++
                DiffLineKind.Removed -> removed++
                else -> Unit
            }
        }
    }
    return DiffStats(added, removed)
}

/**
 * Best-effort file path of a unified diff: the first `--- a/path` or
 * `+++ b/path` line wins. Git's `a/` and `b/` prefixes and any tab-separated
 * timestamp are stripped; `/dev/null` (new / deleted file) is not a path.
 */
fun diffPath(text: String): String? {
    for (raw in text.split('\n')) {
        val line = raw.removeSuffix("\r").trimEnd()
        val rawPath = when {
            line.startsWith("+++ ") -> line.removePrefix("+++ ")
            line.startsWith("--- ") -> line.removePrefix("--- ")
            else -> continue
        }
        val path = rawPath.substringBefore('\t').trim().trim('"')
        if (path.isNotEmpty() && path != "/dev/null") {
            return if (path.startsWith("b/") || path.startsWith("a/")) path.drop(2) else path
        }
    }
    return null
}

/** Build a [ToolDiff] row from raw diff text: parse once, expose everything. */
fun buildToolDiff(
    key: String,
    ts: Long,
    text: String,
    path: String? = null,
    toolCallId: String? = null,
    toolName: String = "",
    added: Int? = null,
    removed: Int? = null,
    truncated: Boolean = false,
): ToolDiff {
    val hunks = parseUnifiedDiff(text)
    val stats = diffStats(hunks)
    return ToolDiff(
        key = key,
        ts = ts,
        path = path?.takeIf { it.isNotBlank() } ?: diffPath(text).orEmpty(),
        diffText = text,
        added = added ?: stats.added,
        removed = removed ?: stats.removed,
        truncated = truncated,
        hunks = hunks,
        toolCallId = toolCallId,
        toolName = toolName,
    )
}

// --------------------------------------------------------------- tool helpers

private val ARG_SUMMARY_KEYS = listOf(
    "command", "cmd", "path", "filePath", "file_path", "pattern", "query", "url", "name",
)

/** One collapsed-card argument summary. Never throws, may be empty. */
fun summarizeToolArgs(args: JsonObject?): String {
    if (args == null || args.isEmpty()) return ""
    for (key in ARG_SUMMARY_KEYS) {
        val value = args.str(key)
        if (!value.isNullOrBlank()) return value.replace('\n', ' ').trim()
    }
    val first = args.entries.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }
    if (first != null) return (first.value as JsonPrimitive).content.replace('\n', ' ').trim()
    return ""
}

/** The diff text inside a tool's `details`, under any of pi's spellings. */
fun detailsDiffText(details: JsonElement?): String? = when (details) {
    is JsonObject -> {
        val text = details.str("diff") ?: details.str("patch") ?: details.str("unifiedDiff")
        text?.takeIf { it.isNotBlank() }
    }
    // Some extensions hand back a bare string result that happens to be a diff.
    is JsonPrimitive -> details.content.takeIf { details.isString && it.contains("@@") }
    else -> null
}

private fun detailsString(details: JsonElement?, vararg keys: String): String? {
    val obj = details as? JsonObject ?: return null
    for (key in keys) obj.str(key)?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun detailsExitCode(details: JsonElement?): Int? {
    val obj = details as? JsonObject ?: return null
    return obj.int("exitCode") ?: obj.int("exit_code") ?: obj.int("code")
}

// ------------------------------------------------------------------- day labels

/** Epoch day of [ts] in the device's zone, used only for day-boundary detection. */
private fun epochDay(ts: Long): Long =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * A date-separator label in pi's own vocabulary: 今天 / 昨天 / a real date.
 * [now] is the reducer's clock, so tests stay deterministic.
 */
fun dayLabel(ts: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        day.year == today.year -> "${day.monthValue} 月 ${day.dayOfMonth} 日"
        else -> "${day.year} 年 ${day.monthValue} 月 ${day.dayOfMonth} 日"
    }
}

/** pi writes ISO-8601 timestamps; accept epoch numbers too. Never throws. */
internal fun parseTimestamp(raw: String): Long? {
    val text = raw.trim()
    text.toLongOrNull()?.let { return it }
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Throwable) {
        try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun JsonObject.timestamp(fallback: Long): Long {
    val primitive = this["timestamp"] as? JsonPrimitive ?: return fallback
    if (!primitive.isString) return primitive.content.toLongOrNull() ?: fallback
    return parseTimestamp(primitive.content) ?: fallback
}

// --------------------------------------------------------------------- changes

/**
 * What the last event did to the stream, so the UI can update one row instead
 * of recomposing the list. [Updated] is the hot path during streaming.
 *
 * One event reports one index. When an event both rewrites a row and appends one
 * (a tool end that also yields a [ToolDiff]) the append is reported, because the
 * appended index is the one the caller has to insert.
 */
sealed interface TranscriptChange {
    data object None : TranscriptChange
    data class Appended(val index: Int) : TranscriptChange
    data class Updated(val index: Int) : TranscriptChange
    data class RemovedFrom(val index: Int) : TranscriptChange
}

// -------------------------------------------------------------------- reducer

/**
 * Folds pi's event stream into an ordered list of [TranscriptItem]s.
 *
 * This is a pure state machine over events — no Android, no coroutines, no
 * clock of its own ([now] is injected) — which is why it lives in the `:rpc`
 * module and is covered by unit tests without a device.
 *
 * pi remains the single source of truth for a session; this projection exists
 * only to drive the screen. Re-attaching after a reconnect replays from
 * `get_entries { since }` via [seedFromHistory] rather than re-deriving state,
 * and live events only fill in what the wire actually carries (the entry
 * payloads that `entry_appended` omits arrive through [onEntry] / [seedFromHistory]).
 */
class TranscriptReducer(private val now: () -> Long = { System.currentTimeMillis() }) {

    private val items = mutableListOf<TranscriptItem>()
    private val toolIndexByCallId = mutableMapOf<String, Int>()
    private val diffIndexByCallId = mutableMapOf<String, Int>()
    private var runningCompactionIndex: Int? = null
    private var currentDay: Long? = null
    private var seq = 0

    /** Live, read-only view of the stream. */
    val transcript: List<TranscriptItem> get() = items

    /** True while assistant text or thinking is still growing. */
    var streaming: Boolean = false
        private set

    /** pi's thinking level, stamped onto each new [ThinkingBlock]. */
    var thinkingLevel: String? = null
        private set

    private fun nextKey(prefix: String) = "$prefix-${now()}-${seq++}"

    private fun keyFor(entryId: String?, prefix: String) = entryId ?: nextKey(prefix)

    /**
     * Record the prompt the user just sent. Done locally (not from an event) so
     * the bubble appears the instant they hit send.
     */
    fun onUserPrompt(text: String, images: List<PiImage> = emptyList()): TranscriptChange {
        val ts = now()
        maybeDaySeparator(ts)
        items += UserMessage(
            key = nextKey("user"),
            ts = ts,
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

        is PiEvent.ThinkingLevelChanged -> {
            event.level?.takeIf { it.isNotBlank() }?.let { thinkingLevel = it }
            TranscriptChange.None
        }

        is PiEvent.CompactionStart -> onCompactionStart(event)
        is PiEvent.CompactionEnd -> onCompactionEnd(event)

        // A turn ended; if the clock crossed midnight while running, the stream
        // needs a day boundary (docs/pi-android-ui-spec.md §4.3).
        is PiEvent.TurnEnd -> if (maybeDaySeparator(now())) {
            TranscriptChange.Appended(items.lastIndex)
        } else {
            TranscriptChange.None
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
                ErrorText(
                    key = nextKey("retry-failed"),
                    ts = now(),
                    message = "重试失败，已停止",
                    detail = null,
                ),
            )
        } else {
            TranscriptChange.None
        }

        is PiEvent.ExtensionError -> append(
            ErrorText(
                key = nextKey("ext-error"),
                ts = now(),
                message = "扩展出错",
                detail = event.message,
            ),
        )

        // The payload of an appended entry is not on the wire; the App requests
        // `get_entries { since }` and feeds the result to [onEntry].
        is PiEvent.EntryAppended -> TranscriptChange.None

        // Anything newer than this build. A handful of entry-shaped types are
        // still projectable, so try those instead of dropping them.
        is PiEvent.Unknown -> if (event.type in ENTRY_EVENT_TYPES) {
            if (event.raw.isEmpty()) TranscriptChange.None else onEntry(event.raw)
        } else {
            TranscriptChange.None
        }

        else -> TranscriptChange.None
    }

    // ------------------------------------------------------------- live blocks

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
                            level = thinkingLevel,
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
                ErrorText(
                    key = nextKey("provider-error"),
                    ts = now(),
                    message = "模型调用失败",
                    detail = delta.reason,
                ),
            )

            else -> TranscriptChange.None
        }
    }

    private fun onToolStart(event: PiEvent.ToolExecutionStart): TranscriptChange {
        streaming = true
        val existing = toolIndexByCallId[event.toolCallId]
        if (existing != null) {
            val current = items.getOrNull(existing)
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

    private fun onToolEnd(event: PiEvent.ToolExecutionEnd): TranscriptChange = finalizeTool(
        callId = event.toolCallId,
        toolName = event.toolName,
        output = event.resultText,
        isError = event.isError,
        details = event.details,
        ts = now(),
    )

    /**
     * Close a tool call and, when its `details` carry a diff, append the
     * `tool-diff` block that follows the card. Shared by the live event path and
     * the history projection so both produce identical rows.
     */
    private fun finalizeTool(
        callId: String,
        toolName: String?,
        output: String?,
        isError: Boolean,
        details: JsonElement?,
        ts: Long,
    ): TranscriptChange {
        val index = toolIndexByCallId[callId]
        val change: TranscriptChange
        if (index != null && items.getOrNull(index) is ToolCall) {
            val current = items[index] as ToolCall
            items[index] = current.copy(
                toolName = toolName?.takeIf { it.isNotEmpty() } ?: current.toolName,
                status = if (isError) ToolStatus.Error else ToolStatus.Success,
                output = output ?: current.output,
                isError = isError,
                details = details ?: current.details,
                endedAt = ts,
                exitCode = detailsExitCode(details) ?: current.exitCode,
            )
            change = TranscriptChange.Updated(index)
        } else {
            toolIndexByCallId[callId] = items.size
            items += ToolCall(
                key = callId,
                ts = ts,
                toolCallId = callId,
                toolName = toolName.orEmpty(),
                status = if (isError) ToolStatus.Error else ToolStatus.Success,
                output = output.orEmpty(),
                isError = isError,
                details = details,
                endedAt = ts,
                exitCode = detailsExitCode(details),
            )
            change = TranscriptChange.Appended(items.lastIndex)
        }
        return appendToolDiff(callId, toolName, details, ts) ?: change
    }

    private fun appendToolDiff(
        callId: String,
        toolName: String?,
        details: JsonElement?,
        ts: Long,
    ): TranscriptChange? {
        val text = detailsDiffText(details) ?: return null
        val path = detailsString(details, "path", "file", "filePath", "relativePath").orEmpty()
        val added = details?.let { (it as? JsonObject)?.int("added") }
        val removed = details?.let { (it as? JsonObject)?.int("removed") }
        val truncated = (details as? JsonObject)?.bool("truncated") ?: false
        val diff = buildToolDiff(
            key = "$callId-diff",
            ts = ts,
            text = text,
            path = path,
            toolCallId = callId,
            toolName = toolName.orEmpty(),
            added = added,
            removed = removed,
            truncated = truncated,
        )
        val existing = diffIndexByCallId[callId]
        if (existing != null) {
            val current = items.getOrNull(existing)
            if (current is ToolDiff) {
                items[existing] = diff.copy(
                    key = current.key,
                    ts = current.ts,
                    path = diff.path.ifEmpty { current.path },
                )
                return TranscriptChange.Updated(existing)
            }
        }
        diffIndexByCallId[callId] = items.size
        items += diff
        return TranscriptChange.Appended(items.lastIndex)
    }

    // -------------------------------------------------------------- compaction

    private fun onCompactionStart(event: PiEvent.CompactionStart): TranscriptChange {
        val ts = now()
        maybeDaySeparator(ts)
        items += CompactionMarker(
            key = nextKey("compaction"),
            ts = ts,
            status = CompactionMarker.Status.Running,
            reason = event.reason,
        )
        runningCompactionIndex = items.lastIndex
        return TranscriptChange.Appended(items.lastIndex)
    }

    private fun onCompactionEnd(event: PiEvent.CompactionEnd): TranscriptChange {
        val index = runningCompactionIndex?.takeIf { items.getOrNull(it) is CompactionMarker }
            ?: items.indexOfLast { it is CompactionMarker && it.status == CompactionMarker.Status.Running }
        val status = when {
            event.errorMessage != null -> CompactionMarker.Status.Failed
            event.aborted -> CompactionMarker.Status.Aborted
            event.willRetry -> CompactionMarker.Status.Running
            else -> CompactionMarker.Status.Done
        }
        if (index < 0) {
            // Reconnected mid-compaction: synthesise the finished marker.
            return append(
                CompactionMarker(
                    key = nextKey("compaction-done"),
                    ts = now(),
                    status = status,
                    errorMessage = event.errorMessage,
                ),
            )
        }
        val current = items[index] as CompactionMarker
        items[index] = current.copy(status = status, errorMessage = event.errorMessage)
        if (status != CompactionMarker.Status.Running) runningCompactionIndex = null
        return TranscriptChange.Updated(index)
    }

    // ----------------------------------------------------------- entry seeding

    /**
     * Project one persisted pi entry (a JSONL record from the session file, or
     * the same object carried by a `get_entries` response) onto the stream.
     *
     * Entry types that are not conversation content — `custom` (extension state,
     * deliberately not in the model's context), `label`, `session_info`, the
     * `session` header and anything newer than this build — are inert rather
     * than fatal.
     */
    fun onEntry(entry: JsonObject): TranscriptChange {
        val type = entry.str("type").orEmpty()
        val entryId = entry.str("id")
        val ts = entry.timestamp(now())
        val separator = maybeDaySeparator(ts)
        val change = when (type) {
            "message" -> entry.obj("message")?.let { onHistoryMessage(it, entryId, ts) }
                ?: TranscriptChange.None

            "model_change", "model_select" -> onModelEntry(entry, entryId, ts)

            "thinking_level_change" -> {
                val level = entry.str("thinkingLevel") ?: entry.str("level")
                if (!level.isNullOrBlank()) thinkingLevel = level
                TranscriptChange.None
            }

            "compaction" -> onCompactionEntry(entry, entryId, ts)
            "branch_summary" -> onBranchEntry(entry, entryId, ts)

            // `custom_message` is pi's name; `hook_message` is an alias accepted
            // for the same shape.
            "custom_message", "hook_message" -> onHookEntry(entry, entryId, ts)

            // The next three are **app-level block kinds, not pi entry types.**
            // pi's persisted union is exactly: message, thinking_level_change,
            // model_change, compaction, branch_summary, custom, custom_message,
            // label, session_info (`core/session-manager.ts`). A skill command is
            // expanded into an ordinary user message (`agent-session.ts`), the
            // system prompt is only reachable through `getSystemPrompt()`, and a
            // failure arrives as a `stopReason: "error"` event rather than an
            // entry. These branches are inert during JSONL replay and exist so the
            // same entry-shaped records can be fed in by an extension or the app.
            "system_prompt" -> onSystemPromptEntry(entry, entryId, ts)
            "skill", "skill_invocation" -> onSkillEntry(entry, entryId, ts)
            "error" -> onErrorEntry(entry, entryId, ts)

            else -> TranscriptChange.None
        }
        return if (change == TranscriptChange.None && separator) {
            TranscriptChange.Appended(items.lastIndex)
        } else {
            change
        }
    }

    /**
     * Rebuild the whole stream from pi's persisted entries (the `get_entries`
     * result). Reopening a session therefore produces exactly the same rows as
     * watching it live, and keys are pi's entry ids so scroll position can be
     * restored. Day separators are re-inserted here, which is the only place
     * that knows the full time span.
     */
    fun seedFromHistory(entries: List<JsonObject>): TranscriptChange {
        reset()
        for (entry in entries) {
            maybeDaySeparator(entry.timestamp(now()), force = true)
            onEntry(entry)
        }
        // A separator with nothing after it is noise (e.g. an empty session).
        while (items.isNotEmpty() && items.last() is DateSeparator) {
            items.removeAt(items.lastIndex)
        }
        return if (items.isEmpty()) TranscriptChange.None else TranscriptChange.Appended(items.lastIndex)
    }

    private fun onHistoryMessage(
        message: JsonObject,
        entryId: String?,
        ts: Long,
    ): TranscriptChange {
        // One entry can carry several blocks (thinking + text + tool calls), so
        // keys hang off pi's entry id: `a1`, `a1-1`, `a1-2`. Tool cards keep the
        // tool call id, which is already stable in pi's own records.
        var blockSeq = 0
        val blockKey: (String) -> String = { prefix ->
            if (entryId == null) {
                nextKey(prefix)
            } else if (blockSeq == 0) {
                blockSeq++
                entryId
            } else {
                entryId + "-" + blockSeq++
            }
        }

        return when (message.str("role")?.lowercase()) {
            "user" -> {
                // Images have their own block, so the text is text blocks only.
                val text = userText(message["content"]).ifEmpty {
                    message.str("text").orEmpty()
                }
                val images = imageBlocks(message["content"])
                if (text.isEmpty() && images.isEmpty()) {
                    TranscriptChange.None
                } else {
                    append(UserMessage(key = blockKey("user"), ts = ts, text = text, images = images))
                }
            }

            "assistant" -> onHistoryAssistant(message, ts, blockKey)

            "toolresult", "tool_result", "tool" -> {
                val callId = message.str("toolCallId")
                    ?: message.str("tool_call_id")
                    ?: message.str("id")
                    ?: return TranscriptChange.None
                finalizeTool(
                    callId = callId,
                    toolName = message.str("toolName") ?: message.str("name"),
                    output = contentText(message["content"]) ?: message.str("text"),
                    isError = message.bool("isError") ?: message.bool("is_error") ?: false,
                    details = message["details"],
                    ts = ts,
                )
            }

            else -> TranscriptChange.None
        }
    }

    private fun onHistoryAssistant(
        message: JsonObject,
        ts: Long,
        blockKey: (String) -> String,
    ): TranscriptChange {
        var change: TranscriptChange = TranscriptChange.None
        val blocks = message["content"] as? JsonArray
        if (blocks == null) {
            val text = contentText(message["content"]) ?: message.str("text")
            if (!text.isNullOrEmpty()) {
                change = append(AssistantText(key = blockKey("assistant"), ts = ts, text = text))
            }
        } else {
            for (block in blocks) {
                val obj = block as? JsonObject ?: continue
                change = when (obj.str("type")) {
                    "text" -> obj.str("text")?.takeIf { it.isNotEmpty() }
                        ?.let { append(AssistantText(key = blockKey("assistant"), ts = ts, text = it)) }
                        ?: change

                    "thinking", "reasoning" -> {
                        val text = obj.str("thinking") ?: obj.str("text")
                        if (text.isNullOrEmpty()) {
                            change
                        } else {
                            append(
                                ThinkingBlock(
                                    key = blockKey("thinking"),
                                    ts = ts,
                                    text = text,
                                    streaming = false,
                                    level = thinkingLevel,
                                ),
                            )
                        }
                    }

                    "toolcall", "toolCall", "tool_call", "tool_use" -> {
                        val callId = obj.str("id") ?: obj.str("toolCallId") ?: nextKey("toolcall")
                        val name = obj.str("name") ?: obj.str("toolName").orEmpty()
                        val args = obj["arguments"] as? JsonObject
                            ?: obj["args"] as? JsonObject
                            ?: obj["input"] as? JsonObject
                        if (toolIndexByCallId.containsKey(callId)) {
                            change
                        } else {
                            toolIndexByCallId[callId] = items.size
                            append(
                                ToolCall(
                                    key = callId,
                                    ts = ts,
                                    toolCallId = callId,
                                    toolName = name,
                                    args = args,
                                    status = ToolStatus.Pending,
                                ),
                            )
                        }
                    }

                    else -> change
                }
            }
        }
        // Some pi versions keep reasoning outside `content`.
        val reasoning = message.str("reasoning") ?: message.str("thinking")
        if (!reasoning.isNullOrEmpty()) {
            change = append(
                ThinkingBlock(
                    key = blockKey("thinking"),
                    ts = ts,
                    text = reasoning,
                    streaming = false,
                    level = thinkingLevel,
                ),
            )
        }
        return change
    }

    private fun onModelEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val nested = entry.obj("model")
        val provider = entry.str("provider") ?: nested?.str("provider")
        val modelId = entry.str("modelId")
            ?: entry.str("model")
            ?: nested?.str("id")
            ?: nested?.str("modelId")
        if (provider.isNullOrBlank() && modelId.isNullOrBlank()) return TranscriptChange.None
        return append(
            ModelChange(
                key = keyFor(entryId, "model"),
                ts = ts,
                provider = provider,
                modelId = modelId.orEmpty(),
            ),
        )
    }

    private fun onCompactionEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val summary = entry.str("summary")
            ?: entry.str("text")
            ?: contentText(entry["content"]).orEmpty()
        val tokens = entry.long("tokensFreed")
            ?: entry.long("tokensBefore")
            ?: entry.long("tokens")
        val firstKept = entry.str("firstKeptEntryId")
        val index = runningCompactionIndex?.takeIf { items.getOrNull(it) is CompactionMarker }
            ?: items.indexOfLast { it is CompactionMarker && it.status == CompactionMarker.Status.Running }
        if (index >= 0) {
            val current = items[index] as CompactionMarker
            items[index] = current.copy(
                summary = summary.ifEmpty { current.summary },
                tokensFreed = tokens ?: current.tokensFreed,
                firstKeptEntryId = firstKept ?: current.firstKeptEntryId,
                status = CompactionMarker.Status.Done,
                errorMessage = null,
            )
            runningCompactionIndex = null
            return TranscriptChange.Updated(index)
        }
        return append(
            CompactionMarker(
                key = keyFor(entryId, "compaction"),
                ts = ts,
                summary = summary,
                tokensFreed = tokens,
                firstKeptEntryId = firstKept,
                status = CompactionMarker.Status.Done,
            ),
        )
    }

    private fun onBranchEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val summary = entry.str("summary") ?: contentText(entry["content"]).orEmpty()
        val branchId = entry.str("branchId") ?: entry.str("fromId") ?: entry.str("parentId")
        if (summary.isEmpty() && branchId == null) return TranscriptChange.None
        return append(
            BranchSummary(
                key = keyFor(entryId, "branch"),
                ts = ts,
                summary = summary,
                branchId = branchId,
            ),
        )
    }

    private fun onHookEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        // `display: false` marks a custom message pi keeps in context but hides
        // from the transcript.
        if (entry.bool("display") == false) return TranscriptChange.None
        val customType = entry.str("customType")
            ?: entry.str("custom_type")
            ?: entry.str("name")
            ?: "extension"
        val text = entry.str("content")
            ?: contentText(entry["content"])
            ?: entry.str("text")
            ?: entry.str("markdown")
            ?: ""
        if (customType.isBlank() && text.isEmpty()) return TranscriptChange.None
        return append(
            HookMessage(
                key = keyFor(entryId, "hook"),
                ts = ts,
                customType = customType,
                markdown = text,
            ),
        )
    }

    private fun onSystemPromptEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val text = entry.str("text")
            ?: entry.str("systemPrompt")
            ?: entry.str("prompt")
            ?: contentText(entry["content"])
            ?: ""
        if (text.isEmpty()) return TranscriptChange.None
        return append(SystemPrompt(key = keyFor(entryId, "system"), ts = ts, fullText = text))
    }

    private fun onSkillEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val name = entry.str("skillName")
            ?: entry.str("skill")
            ?: entry.str("name")
            ?: ""
        val body = entry.str("body")
            ?: entry.str("text")
            ?: contentText(entry["content"])
            ?: entry.str("content")
            ?: ""
        if (name.isBlank() && body.isEmpty()) return TranscriptChange.None
        return append(
            SkillInvocation(
                key = keyFor(entryId, "skill"),
                ts = ts,
                skillName = name,
                body = body,
            ),
        )
    }

    private fun onErrorEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        val message = entry.str("message") ?: entry.str("error") ?: "出错了"
        val detail = contentText(entry["content"]) ?: entry.str("detail")
        return append(
            ErrorText(key = keyFor(entryId, "error"), ts = ts, message = message, detail = detail),
        )
    }

    private fun imageBlocks(element: JsonElement?): List<PiImage> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") != "image") return@mapNotNull null
            val data = obj.str("data") ?: return@mapNotNull null
            PiImage(base64 = data, mimeType = obj.str("mimeType") ?: "image/png")
        }
    }

    /**
     * Text blocks of a pi content array. Unlike [contentText] this drops the
     * `[image]` marker, because the images are rendered by their own block.
     */
    private fun userText(element: JsonElement?): String {
        val array = element as? JsonArray ?: return contentText(element).orEmpty()
        return array.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "text") obj.str("text") else null
        }.joinToString("")
    }

    // ------------------------------------------------------- public single-row

    /** Append a [SystemPrompt] block (pi's assembled system prompt). */
    fun onSystemPrompt(text: String, entryId: String? = null): TranscriptChange {
        if (text.isEmpty()) return TranscriptChange.None
        return append(SystemPrompt(key = keyFor(entryId, "system"), ts = now(), fullText = text))
    }

    /** Append a [SkillInvocation] block (`/skill:name` expansion). */
    fun onSkill(name: String, body: String = "", entryId: String? = null): TranscriptChange {
        if (name.isBlank() && body.isEmpty()) return TranscriptChange.None
        return append(
            SkillInvocation(key = keyFor(entryId, "skill"), ts = now(), skillName = name, body = body),
        )
    }

    /** Append a [ModelChange] block (`set_model` / `model_select`). */
    fun onModelChange(provider: String?, modelId: String, entryId: String? = null): TranscriptChange {
        if (provider.isNullOrBlank() && modelId.isBlank()) return TranscriptChange.None
        return append(
            ModelChange(key = keyFor(entryId, "model"), ts = now(), provider = provider, modelId = modelId),
        )
    }

    /** Append a [HookMessage] block (an extension-injected custom message). */
    fun onHookMessage(
        customType: String,
        text: String,
        entryId: String? = null,
    ): TranscriptChange {
        if (customType.isBlank() && text.isEmpty()) return TranscriptChange.None
        return append(
            HookMessage(
                key = keyFor(entryId, "hook"),
                ts = now(),
                customType = customType,
                markdown = text,
            ),
        )
    }

    /** Append an [ErrorText] block. */
    fun onError(message: String, detail: String? = null): TranscriptChange = append(
        ErrorText(key = nextKey("error"), ts = now(), message = message, detail = detail),
    )

    // ------------------------------------------------------------------ helpers

    private fun finishStreaming(): TranscriptChange {
        streaming = false
        var touched = -1
        val ts = now()
        for (i in items.indices) {
            when (val item = items[i]) {
                is AssistantText -> if (item.streaming) {
                    items[i] = item.copy(streaming = false); touched = i
                }

                is ThinkingBlock -> if (item.streaming) {
                    items[i] = item.copy(
                        streaming = false,
                        elapsedMs = item.elapsedMs ?: (ts - item.ts).coerceAtLeast(0),
                    )
                    touched = i
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

    /**
     * Insert a day boundary before [ts] when the stream's last block is from an
     * earlier day. [force] is used by history seeding, where the leading
     * separator is wanted even though the stream is still empty.
     */
    private fun maybeDaySeparator(ts: Long, force: Boolean = false): Boolean {
        val day = epochDay(ts)
        if (currentDay == day) return false
        if (currentDay == null && !force) {
            currentDay = day
            return false
        }
        items += DateSeparator(key = nextKey("date"), ts = ts, label = dayLabel(ts, now()))
        currentDay = day
        return true
    }

    /** Drop everything (new session, or a different session was opened). */
    fun reset() {
        items.clear()
        toolIndexByCallId.clear()
        diffIndexByCallId.clear()
        runningCompactionIndex = null
        currentDay = null
        streaming = false
    }

    private companion object {
        /**
         * Entry-shaped events that a newer pi may emit directly on stdout. They
         * are projectable through [onEntry]; everything else unknown is inert.
         */
        val ENTRY_EVENT_TYPES = setOf(
            "model_change",
            "model_select",
            "thinking_level_change",
            "compaction",
            "branch_summary",
            "custom_message",
            "hook_message",
            "system_prompt",
            "skill",
            "skill_invocation",
            "error",
        )
    }
}
