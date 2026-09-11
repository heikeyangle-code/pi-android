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
    /**
     * Images the tool returned, kept as payload instead of the `[image]` marker
     * [output] carries (F16). Painting them needs an image loader the project
     * does not have yet (`docs/known-gaps.md` A3), but the transcript must not be
     * the place the bytes are lost.
     */
    val images: List<PiImage> = emptyList(),
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
    /**
     * Usage of the summarization call(s) that produced this compaction (F18).
     * pi prints what it cost (`interactive-mode.ts` formats "Compaction … (~$0.03)"),
     * and the transcript should not be where that figure disappears.
     */
    val usage: TokenUsage? = null,
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

/**
 * Whether pi truncated this tool's output, from the tool-result `details`.
 *
 * pi's bash tool sets `details.truncation` only when it actually truncated
 * (`core/tools/bash.ts`: `truncation: snapshot.truncation.truncated ? … :
 * undefined`, and the `read` tool uses a plain `truncated`), so the presence of
 * either form is the signal. JSON `null` — which `docs/rpc.md`'s example shows —
 * means "not truncated".
 */
private fun detailsTruncated(details: JsonElement?): Boolean {
    val obj = details as? JsonObject ?: return false
    obj.bool("truncated")?.let { return it }
    return when (val truncation = obj["truncation"]) {
        null, is kotlinx.serialization.json.JsonNull -> false
        // A boolean or a marker string: presence means it was truncated.
        is kotlinx.serialization.json.JsonPrimitive -> truncation.content.toBooleanStrictOrNull() ?: true
        is JsonObject -> truncation.bool("truncated") ?: true
        else -> false
    }
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
 * and live events fill in what the wire carries: `entry_appended` includes the
 * whole entry object (projected through [onEntry]), and the streaming events
 * that have no entry counterpart (`compaction_end.result`, the summarization
 * retries) are folded directly.
 */
class TranscriptReducer(private val now: () -> Long = { System.currentTimeMillis() }) {

    private val items = mutableListOf<TranscriptItem>()
    private val toolIndexByCallId = mutableMapOf<String, Int>()
    private val diffIndexByCallId = mutableMapOf<String, Int>()
    private var runningCompactionIndex: Int? = null

    /**
     * The [Notice] row a `summarization_retry_scheduled` opened, so the matching
     * `summarization_retry_attempt_start` / `summarization_retry_finished` can
     * update that same row instead of stacking a new one per attempt.
     */
    private var summarizationNoticeIndex: Int? = null

    /**
     * The most recent usage pi reported, from `message_update.usage` (cumulative
     * during a turn) or `message_end.message.usage` (the final figure) — F10.
     *
     * Kept on the reducer because all three usage payloads were parsed and then
     * dropped: the transcript is the only object the UI already observes, so this
     * is where the status row (spec §4.1's `↑24.1k ↓3.2k · ◐ 52%`) can read it
     * without polling `get_session_stats` after the fact.
     */
    var lastUsage: TokenUsage? = null
        private set

    /**
     * Row index of the streaming block for each `contentIndex` within the
     * current assistant message.
     *
     * pi addresses content blocks positionally. Verified against pi's own
     * `anthropic-messages` adapter (fed a provider stream of
     * text(0) → tool_use(1) → text(2)): it emits `text_start` at 0, then
     * `toolcall_start` at 1, then **a second `text_start` at 2**. Looking up
     * "the last streaming text row" would append block 2 into block 0's row,
     * which sits before the tool card, so the transcript would read
     * "First part. Second part." followed by the tool — not pi's order.
     */
    private val textIndexByContentIndex = mutableMapOf<Int, Int>()
    private val thinkingIndexByContentIndex = mutableMapOf<Int, Int>()
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
     *
     * A `/skill:name` prompt arrives here as the raw text the user typed, which
     * is not a skill block — the expansion happens inside pi. The split is still
     * applied so a caller that echoes the expanded form (or a re-render of one)
     * gets the same card as history replay.
     */
    fun onUserPrompt(text: String, images: List<PiImage> = emptyList()): TranscriptChange {
        val ts = now()
        maybeDaySeparator(ts)
        return projectUser(text, images, ts) { prefix -> nextKey(prefix) }
    }

    /** Fold one engine event. Never throws. */
    fun onEvent(event: PiEvent): TranscriptChange = when (event) {
        // A new assistant message restarts content-block numbering, so the
        // per-index row map must not carry over.
        is PiEvent.MessageStart -> {
            if (event.role == "assistant") {
                textIndexByContentIndex.clear()
                thinkingIndexByContentIndex.clear()
            }
            TranscriptChange.None
        }

        is PiEvent.MessageUpdate -> onMessageUpdate(event)
        is PiEvent.MessageEnd -> {
            val finished = finishStreaming()
            event.usage?.let { lastUsage = it }
            // A truncated or aborted answer used to look exactly like a finished
            // one, and an aborted turn left its tool card spinning "运行中"
            // forever. pi reports both here (`components/assistant-message.ts`);
            // see [failTurn].
            if (event.role == "assistant" && event.stopReason in TURN_FAILURE_REASONS) {
                failTurn(event.stopReason, event.errorMessage)
            } else if (event.role == "custom" && event.display != false && !event.text.isNullOrEmpty()) {
                // A live `role: "custom"` message is an extension's injected context.
                // History replay already renders it (`custom_message` entries reach
                // [onHookEntry]); the live chain used to drop it, so injected context
                // only appeared after a reload. `display: false` means pi keeps it in
                // context but hides it, exactly as [onHookEntry] treats it.
                onHookMessage(event.customType ?: "extension", event.text)
            } else {
                finished
            }
        }
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

        // The whole entry object is on the wire (`agent-session.ts` emits
        // `{ type: "entry_appended"; entry: SessionEntry }` and `json-event.ts`
        // passes it through untouched), so it is projected directly. pi emits
        // this only from the extension `appendEntry` path; a refetch would be
        // the alternative and would leave a gap until it completed.
        is PiEvent.EntryAppended -> event.entry?.let(::onEntry) ?: TranscriptChange.None

        // Summarization retries are pi's own three-event retry loop for
        // compaction / branch-summary LLM calls. They have no session entry, so
        // they must be folded here or the retry loop is invisible and a stalling
        // context looks like a hang.
        is PiEvent.SummarizationRetryScheduled -> {
            val text = "摘要重试" +
                (event.attempt?.let { " $it" } ?: "") +
                (event.maxAttempts?.let { "/$it" } ?: "") +
                (event.delayMs?.let { "，${it / 1000}s 后" } ?: "") +
                (event.errorMessage?.let { "：$it" } ?: "")
            val open = summarizationNoticeIndex?.takeIf { items.getOrNull(it) is Notice }
            if (open == null) {
                val change = append(
                    Notice(
                        key = nextKey("summary-retry"),
                        ts = now(),
                        text = text,
                        tone = Notice.Tone.Warning,
                    ),
                )
                if (change is TranscriptChange.Appended) summarizationNoticeIndex = change.index
                change
            } else {
                // One row for the whole retry loop: a second attempt replaces
                // the first attempt's countdown instead of stacking a warning.
                items[open] = (items[open] as Notice).copy(text = text)
                TranscriptChange.Updated(open)
            }
        }

        is PiEvent.SummarizationRetryAttemptStart -> {
            val label = when (event.source) {
                "compaction" -> "压缩摘要"
                "branchSummary" -> "分支摘要"
                else -> "摘要"
            }
            val open = summarizationNoticeIndex?.takeIf { items.getOrNull(it) is Notice }
            if (open == null) {
                // Attached mid-retry: the scheduled event that opened the loop
                // was missed, so open the row now rather than dropping the state.
                val change = append(
                    Notice(
                        key = nextKey("summary-retry"),
                        ts = now(),
                        text = "正在重试生成$label",
                        tone = Notice.Tone.Warning,
                    ),
                )
                if (change is TranscriptChange.Appended) summarizationNoticeIndex = change.index
                change
            } else {
                val current = items[open] as Notice
                if (current.text.contains(label)) {
                    TranscriptChange.None
                } else {
                    items[open] = current.copy(text = "${current.text}（$label）")
                    TranscriptChange.Updated(open)
                }
            }
        }

        is PiEvent.SummarizationRetryFinished -> {
            val index = summarizationNoticeIndex?.takeIf { items.getOrNull(it) is Notice }
            summarizationNoticeIndex = null
            if (index == null) {
                TranscriptChange.None
            } else {
                // pi sends no success flag here; the outcome arrives as
                // `compaction_end.errorMessage` (or the branch-summary failure),
                // so the row only stops looking like a pending retry.
                val current = items[index] as Notice
                items[index] = current.copy(text = "${current.text}，重试结束", tone = Notice.Tone.Info)
                TranscriptChange.Updated(index)
            }
        }

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
        // Latest accounting for the status row (F10); `message_update.usage` is
        // cumulative, so the newest value simply replaces the previous one.
        event.usage?.let { lastUsage = it }
        val delta = event.delta ?: return TranscriptChange.None
        streaming = true
        return when (delta) {
            is AssistantDelta.TextDelta -> {
                // One row per pi content block, keyed by `contentIndex`, so an
                // interleaved block lands after the tool card rather than inside
                // the first text row.
                val index = textIndexByContentIndex[delta.contentIndex]
                if (index != null && items.getOrNull(index) is AssistantText) {
                    val current = items[index] as AssistantText
                    items[index] = current.copy(text = current.text + delta.delta)
                    TranscriptChange.Updated(index)
                } else {
                    textIndexByContentIndex[delta.contentIndex] = items.size
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
                val index = thinkingIndexByContentIndex[delta.contentIndex]
                if (index != null && items.getOrNull(index) is ThinkingBlock) {
                    val current = items[index] as ThinkingBlock
                    items[index] = current.copy(text = current.text + delta.delta)
                    TranscriptChange.Updated(index)
                } else {
                    thinkingIndexByContentIndex[delta.contentIndex] = items.size
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

            // `text_end.content` is pi's authoritative text for the block
            // (`packages/ai/src/types.ts`). Replacing rather than ignoring it means
            // a dropped or reordered delta cannot leave the row wrong.
            is AssistantDelta.TextEnd -> {
                val content = delta.content
                val index = textIndexByContentIndex[delta.contentIndex]
                val current = if (index == null) null else items.getOrNull(index) as? AssistantText
                if (content == null || current == null || current.text == content) {
                    TranscriptChange.None
                } else {
                    items[index!!] = current.copy(text = content)
                    TranscriptChange.Updated(index)
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

            // `toolcall_end` carries the assembled `toolCall`, so the placeholder
            // card opened by `toolcall_start` can gain its name and arguments
            // before the tool actually runs.
            is AssistantDelta.ToolCallEnd -> {
                val index = delta.id?.let { toolIndexByCallId[it] }
                    ?: items.indexOfLast { it is ToolCall && it.status == ToolStatus.Pending }
                val current = items.getOrNull(index) as? ToolCall
                if (current == null) {
                    TranscriptChange.None
                } else {
                    toolIndexByCallId[current.toolCallId] = index
                    items[index] = current.copy(
                        toolName = delta.name?.takeIf { it.isNotEmpty() } ?: current.toolName,
                        args = delta.arguments ?: current.args,
                    )
                    TranscriptChange.Updated(index)
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

            // A delta kind a newer pi introduced. Made visible on purpose: the
            // class's own contract is that an engine upgrade degrades the UI
            // rather than blanking it, and a silently swallowed kind is
            // indistinguishable from a stall. Deduplicated by kind so a repeated
            // delta cannot flood the transcript.
            is AssistantDelta.Unknown -> {
                val text = "未知的流式事件：${delta.kind}"
                if (items.any { it is Notice && it.text == text }) {
                    TranscriptChange.None
                } else {
                    append(Notice(key = nextKey("delta"), ts = now(), text = text))
                }
            }

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
        images = event.resultImages,
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
        images: List<PiImage> = emptyList(),
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
                // F23: pi marks a truncated tool result in `details.truncation`
                // (or `details.truncated`); nothing ever set this flag, so the
                // UI's "已截断" label was unreachable.
                outputTruncated = current.outputTruncated || detailsTruncated(details),
                images = images.ifEmpty { current.images },
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
                outputTruncated = detailsTruncated(details),
                images = images,
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

    /**
     * Report a turn pi ended abnormally, and close every tool card that can no
     * longer finish (F3 + F2).
     *
     * pi's TUI does both: `components/assistant-message.ts` prints a red line for
     * a `length`/`aborted`/`error` stop reason, and `interactive-mode.ts` hands
     * every pending tool component `updateResult({…, isError:true})` before
     * clearing it. Without this a response cut off by the output-token limit
     * reads as a complete answer, and a stopped turn shows a live "运行中" card
     * forever — both silent, both indistinguishable from success or a hang.
     */
    private fun failTurn(reason: String?, errorMessage: String?): TranscriptChange {
        val ts = now()
        val message = when (reason) {
            "length" -> "回复被令牌上限截断"
            "aborted" -> "回合已中止"
            else -> "模型调用失败"
        }
        val detail = errorMessage?.takeIf { it.isNotBlank() }
        var change = append(
            ErrorText(key = nextKey("turn-failed"), ts = ts, message = message, detail = detail),
        )
        // Nothing will ever finalise a pending tool now.
        for (i in items.indices) {
            val call = items.getOrNull(i) as? ToolCall ?: continue
            if (call.status != ToolStatus.Pending) continue
            items[i] = call.copy(
                status = ToolStatus.Error,
                isError = true,
                endedAt = ts,
                output = call.output.ifEmpty { detail ?: message },
            )
            change = TranscriptChange.Updated(i)
        }
        return change
    }

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
        // `compaction_end.result` is the only live source of the summary: the
        // `compaction` session entry that also carries it is appended by the
        // session manager and never announced as `entry_appended`. Without this
        // the finished block is an unlabelled row until the session is reopened.
        val result = event.result
        val summary = result?.summary.orEmpty()
        // `tokensFreed` mirrors `tokensBefore`, which is what `onCompactionEntry`
        // stores from the persisted entry, so live and replayed rows agree.
        val tokens = result?.tokensBefore
        val firstKept = result?.firstKeptEntryId
        // F18: what the summarization call cost. pi prints it as a notice after a
        // compacted session; the marker carries it here so the number survives.
        val usage = result?.usage
        if (index < 0) {
            // Reconnected mid-compaction: synthesise the finished marker.
            return append(
                CompactionMarker(
                    key = nextKey("compaction-done"),
                    ts = now(),
                    summary = summary,
                    tokensFreed = tokens,
                    firstKeptEntryId = firstKept,
                    status = status,
                    errorMessage = event.errorMessage,
                    reason = event.reason,
                    usage = usage,
                ),
            )
        }
        val current = items[index] as CompactionMarker
        items[index] = current.copy(
            summary = summary.ifEmpty { current.summary },
            tokensFreed = tokens ?: current.tokensFreed,
            firstKeptEntryId = firstKept ?: current.firstKeptEntryId,
            status = status,
            errorMessage = event.errorMessage,
            usage = usage ?: current.usage,
        )
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

            // `model_change` is the persisted entry type. There is no
            // `model_select` entry (session-manager.ts) and no `model_select`
            // event on RPC stdout either — `_emitModelSelect` calls
            // `_extensionRunner.emit(...)`, never `_emit`/`subscribe`
            // (agent-session.ts). A record with that type is therefore inert
            // here; the live signal is the `get_state` poll after
            // `agent_settled`, and [onModelChange] is the app-synthesised API.
            "model_change" -> onModelEntry(entry, entryId, ts)

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

            // `skill` / `skill_invocation` are **app-level aliases, not pi entry
            // types**: pi expands `/skill:name` into an ordinary user message and
            // the real projection is the `<skill …>` split in [projectUser]. The
            // aliases stay so a caller can feed an entry-shaped skill record in.
            "skill", "skill_invocation" -> onSkillEntry(entry, entryId, ts)

            // pi has no `system_prompt` or `error` entry type — the persisted
            // union is exactly message, thinking_level_change, model_change,
            // compaction, branch_summary, custom, custom_message, label and
            // session_info (`core/session-manager.ts`). The system prompt is only
            // reachable through `getSystemPrompt()`, and a failure arrives as a
            // `stopReason`/delta event, so neither is handled here. Rows for them
            // come from the app-synthesised [onSystemPrompt] / [onError] APIs.
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
                projectUser(text, imageBlocks(message["content"]), ts, blockKey)
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
                    // Reload must not lose what the live path keeps (F16).
                    images = imageBlocks(message["content"]),
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
        // The persisted entry carries the summarization usage too, so a replayed
        // session shows the same cost as the live one (F18).
        val usage = entry.obj("usage")?.let(PiEvents::parseUsage)
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
                usage = usage ?: current.usage,
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
                usage = usage,
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

    /**
     * Text blocks of a pi content array. Unlike [contentText] this drops the
     * `[image]` marker, because the images are rendered by their own block.
     *
     * Image blocks themselves come from the shared `imageBlocks` in `Events.kt`,
     * so the live `tool_execution_end` path and history replay use one parser.
     */
    private fun userText(element: JsonElement?): String {
        val array = element as? JsonArray ?: return contentText(element).orEmpty()
        return array.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "text") obj.str("text") else null
        }.joinToString("")
    }

    /**
     * Emit the rows for a user message: a [SkillInvocation] card plus the
     * trailing user text when pi expanded `/skill:name`, otherwise one
     * [UserMessage].
     *
     * pi keeps no `skill` entry — `_expandSkillCommand` rewrites the user
     * message itself (`core/agent-session.ts`) and its TUI splits the text back
     * out with `parseSkillBlock` (`modes/interactive/interactive-mode.ts`).
     * Replaying history without the same split leaves the literal `<skill …>`
     * wrapper and the whole SKILL.md body in the user's bubble, and makes the
     * skill card unreachable.
     */
    private fun projectUser(
        text: String,
        images: List<PiImage>,
        ts: Long,
        key: (String) -> String,
    ): TranscriptChange {
        val skill = parsePiSkillBlock(text)
        if (skill == null) {
            if (text.isEmpty() && images.isEmpty()) return TranscriptChange.None
            return append(UserMessage(key = key("user"), ts = ts, text = text, images = images))
        }
        var change = append(
            SkillInvocation(
                key = key("skill"),
                ts = ts,
                skillName = skill.name,
                body = skill.content,
            ),
        )
        skill.userMessage?.let {
            change = append(UserMessage(key = key("user"), ts = ts, text = it, images = images))
        }
        return change
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
        // Content-block numbering restarts with the next assistant message.
        textIndexByContentIndex.clear()
        thinkingIndexByContentIndex.clear()
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
        summarizationNoticeIndex = null
        textIndexByContentIndex.clear()
        thinkingIndexByContentIndex.clear()
        lastUsage = null
        currentDay = null
        streaming = false
    }

    private companion object {
        /**
         * The `stopReason`s that mean a turn did **not** finish normally, and
         * that pi's own TUI reports (`components/assistant-message.ts`):
         * `length` = truncated by the output-token limit, `aborted` = stopped by
         * the user, `error` = the provider failed.
         */
        val TURN_FAILURE_REASONS = setOf("length", "aborted", "error")

        /**
         * Entry-shaped events that a newer pi may emit directly on stdout. They
         * are projectable through [onEntry]; everything else unknown is inert.
         *
         * Only real pi entry types belong here. `model_select`, `system_prompt`
         * and `error` were removed because pi can emit none of them: the first is
         * extension-only (`agent-session.ts`), and the other two are not in the
         * persisted union (`session-manager.ts`).
         */
        val ENTRY_EVENT_TYPES = setOf(
            "model_change",
            "thinking_level_change",
            "compaction",
            "branch_summary",
            "custom_message",
            "hook_message",
            "skill",
            "skill_invocation",
        )
    }
}
