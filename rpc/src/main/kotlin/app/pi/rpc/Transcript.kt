package app.pi.rpc

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
     * [output] carries (F16). The parser and both entry points keep them
     * (`Events.kt:454`, `Transcript.kt:1137` live, `:1547` replay) so the
     * transcript is not where the bytes are lost — pi draws every `image` block
     * (`components/tool-execution.ts:379-388`). Painting them needs a consumer:
     * as of the F16 review no file under `ui/` reads this list (`ToolCallBlock`
     * never references `images`), so the row still shows `[image]` text.
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
     * Usage of the summarization call(s) that produced this compaction (F18),
     * from `compaction_end.result.usage` live and the persisted entry's `usage`
     * on replay. pi prints what it cost
     * (`interactive-mode.ts:3812` — `Compaction: 1.2k tokens billed (~$0.03)`),
     * and the transcript should not be where that figure disappears.
     *
     * **This row is deliberately not produced here**: pi gates the cost notice on
     * the user setting `showCacheMissNotices`
     * (`interactive-mode.ts:3804`, default **false** at
     * `core/settings-manager.ts:120`), and the reducer is constructed with a clock
     * and nothing else — it cannot read a setting, so appending a row here would
     * show something pi hides by default. The gate belongs to the app, which
     * already owns that switch (`ui/settings/PiSettingsRegistry.kt:331`); the
     * reducer's job is only to carry the figure. See [BranchSummary.usage].
     */
    val usage: TokenUsage? = null,
) : TranscriptItem {
    enum class Status { Running, Done, Aborted, Failed }
}

/**
 * The `branch-summary` block: a summary written when navigating the tree.
 */
data class BranchSummary(
    override val key: String,
    override val ts: Long,
    val summary: String = "",
    val branchId: String? = null,
    /**
     * Usage of the summarization call that wrote this summary (F18). pi bills it
     * exactly like a compaction: `interactive-mode.ts:3791-3792` builds the same
     * `compaction_cost` item for `branch_summary`, `:3708-3711` dispatches it and
     * `:3812` labels it "Branch summary". Carried here so a replayed session holds
     * the figure; why no row is appended by the reducer is stated on
     * [CompactionMarker.usage].
     */
    val usage: TokenUsage? = null,
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

/**
 * The `system-prompt` block: collapsed to a size line, expandable to full text.
 *
 * **Unreachable in production (F22).** pi has no `system_prompt` session entry
 * (`core/session-manager.ts:145-155`) and no RPC command returns the prompt
 * (`rpc-types.ts:20-71`; `get_state`'s `RpcSessionState`, `:96-109`, carries no
 * prompt field), so no wire data can produce this row. It exists because
 * `ui/blocks/SystemPromptBlock.kt` renders it; the whole kind is slated for
 * deletion together with that block and [TranscriptReducer.onSystemPrompt].
 */
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

/** Cap for [compactEntryData]: one transcript row, not a payload dump. */
private const val CUSTOM_ENTRY_DATA_MAX = 200

/**
 * One-line, bounded rendering of a `custom` entry's `data` (F6).
 *
 * `data` is `unknown` in pi (`core/session-manager.ts:104-108`), so it is a
 * string, a number, an array or an arbitrary nested object; the compact JSON
 * form is the closest thing to what the extension's own renderer would have
 * drawn. Newlines are flattened because the row is a single line, and the
 * result is capped so a payload the UI cannot lay out cannot take over the
 * transcript.
 */
private fun compactEntryData(data: JsonElement?, max: Int = CUSTOM_ENTRY_DATA_MAX): String {
    val text = when (data) {
        null, is JsonNull -> ""
        is JsonPrimitive -> data.content
        else -> data.toString()
    }.replace('\n', ' ').replace('\r', ' ').trim()
    return if (text.length <= max) text else text.take(max - 1) + "…"
}

/**
 * Whether pi truncated this tool's output, from the tool-result `details`.
 *
 * pi's tools report truncation as `details.truncation`, a `TruncationResult`
 * whose `truncated` boolean is the signal (`core/tools/truncate.ts:18-30`):
 * bash sets it only when it actually truncated
 * (`core/tools/bash.ts:268` — `truncation: snapshot.truncation.truncated ? … :
 * undefined`) and `read` sets `details = { truncation }` on both its truncation
 * branches (`core/tools/read.ts:156`, `:165`). A flat `details.truncated` is
 * accepted too, but no pi tool writes that today — it costs one lookup and keeps
 * a differently-shaped result from reading as "not truncated". JSON `null` —
 * which `docs/rpc.md`'s example shows — means "not truncated".
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
 * The `stopReason`s that mean a turn did **not** finish normally, and that pi's
 * own TUI reports: `length` = truncated by the output-token limit,
 * `aborted` = stopped by the user, `error` = the provider failed.
 *
 * Values are checked against pi's own union, not against a document:
 * `packages/ai/src/types.ts:406` declares
 * `StopReason = "pending" | "stop" | "length" | "toolUse" | "error" | "aborted"
 * | "deferred"`. The four left out are `stop` and `toolUse` (a result still
 * follows), `pending` (a message still streaming) and `deferred` (pi keeps the
 * turn alive for a deferred handle, so nothing has failed).
 *
 * The row each one prints is
 * `packages/tui/src/components/assistant-message.ts:180-199` (`length` always,
 * `aborted`/`error` only when the message carries no tool call), and the cards
 * each one closes are `interactive-mode.ts:3295`/`:3735` (`aborted`/`error`
 * only, never `length`). Both conditions live in the reducer's `failTurn` and in
 * nothing else.
 *
 * File-level on purpose: a `private companion` member is reachable from the
 * class's own members, but a file-level `private val` is reachable from every
 * scope in this file, so a future lambda or nested declaration cannot lose it.
 */
private val TURN_FAILURE_REASONS = setOf("length", "aborted", "error")

/**
 * F8's publication window for `tool_execution_update`, in milliseconds.
 *
 * The number is the app's own UI spec — `docs/pi-android-ui-spec.md:369` §4.3,
 * the `tool_execution_update` row: "**节流 200ms** 追加输出（避免抖动）".
 *
 * pi coalesces for the same reason, one layer lower: every chunk really is
 * emitted on the wire — `packages/agent/src/agent-loop.ts:677-717` emits one
 * `tool_execution_update` per `partialResult` (`:688-704` is the callback), and
 * tools are executed strictly one call at a time (`:497-545`, one `await` per
 * call), so a chatty tool floods stdout exactly like it floods the app's log —
 * but the TUI never repaints per chunk: `TuiBase.requestRender` queues a single
 * frame and `scheduleRender` waits out `MIN_RENDER_INTERVAL_MS = 16`
 * (`packages/tui/src/tui.ts:477`, `:952-1005`, `:986-1005`). The app's 200 ms is
 * that same coalescing at a phone-appropriate interval.
 *
 * Only the *publication* is coalesced; the stored row is always current (see
 * [TranscriptReducer.onToolUpdate]), which is what keeps the throttle from
 * becoming data loss.
 */
private const val TOOL_UPDATE_THROTTLE_MS = 200L

/**
 * What the last event did to the stream, so the UI can update one row instead
 * of recomposing the list. [Updated] is the hot path during streaming.
 *
 * One event reports one index. When an event both rewrites a row and appends one
 * (a tool end that also yields a [ToolDiff]) the append is reported, because the
 * appended index is the one the caller has to insert.
 *
 * [None] does **not** imply "no row changed": a `tool_execution_update` inside
 * F8's 200 ms window merges its chunk into the stored row and answers [None]
 * on purpose, so the row list is ahead of the publication until the next update
 * or the turn boundary (where [TranscriptReducer.finishStreaming] flushes it).
 * A consumer that renders from a snapshot must therefore not treat [None] as
 * "the previous snapshot is still current" without comparing the rows — see
 * `PiEngineSession.publish`, which is the one place that decides.
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
     * Wall-clock of the last **published** update, per tool call id (F8).
     *
     * Per call rather than one shared stamp: pi executes the calls of a single
     * message strictly one after another (`agent-loop.ts:497-545`), and a shared
     * stamp would swallow the first chunk of the next call. Cleared for a call id
     * when that call finalises ([finalizeTool]) and wholesale on [reset].
     */
    private val lastToolPublishAt = mutableMapOf<String, Long>()

    /**
     * The tool row whose newest chunk is in the transcript but was deliberately
     * not published yet (F8).
     *
     * It exists so the throttle cannot lose the tail of a burst: [finishStreaming]
     * — which every turn-ending event funnels through (`message_end`, `agent_end`,
     * `agent_settled`) — reports this row when nothing else was touched, so the
     * last chunk reaches the screen even if no further update arrives. A tool end
     * reports its own row unconditionally, so the normal path never needs it.
     */
    private var suppressedToolUpdate: Int? = null

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
     * The [ErrorText.key] for a failed turn found in history.
     *
     * Derived from pi's entry id so a re-seed of the same entries produces the
     * same key (history keys are pi's ids, see [seedFromHistory]); the prefix
     * cannot collide with [onHistoryMessage]'s `id`, `id-1`, `id-2` block keys
     * because those are pi's id followed by a digit or nothing. It also must not
     * collide with the `-diff` suffix [appendToolDiff] uses, and does not.
     */
    private fun failureKey(entryId: String?) = entryId?.let { "$it-turn-failed" } ?: nextKey("turn-failed")

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
            // forever. pi reports both here
            // (`packages/tui/src/components/assistant-message.ts:180-199` for the
            // row and its `hasToolCalls` gate,
            // `interactive-mode.ts:3294-3304` for the closed tool cards); see
            // [failTurn], which the persisted-entry path in [onHistoryAssistant]
            // also uses so replay matches this.
            if (event.role == "assistant" && event.stopReason in TURN_FAILURE_REASONS) {
                failTurn(event.stopReason, event.errorMessage, event.hasToolCalls)
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

            // F24: the same authoritative-overwrite contract as [TextEnd], plus
            // the one case [TextEnd] cannot have. pi documents that "Redacted
            // thinking may be complete at start and emit no deltas"
            // (`packages/ai/src/types.ts:542-543`), and the cumulative `message`
            // its TUI renders is stripped from the wire
            // (`modes/json-event.ts:40-45`, `:56-60`) — so for such a block this
            // event is the only thing that can put the text on screen. Ignoring it
            // does not merely risk a corrupt row, it loses the block entirely.
            is AssistantDelta.ThinkingEnd -> {
                val content = delta.content
                val index = thinkingIndexByContentIndex[delta.contentIndex]
                val current = if (index == null) null else items.getOrNull(index) as? ThinkingBlock
                if (content == null || (current == null && content.isEmpty())) {
                    TranscriptChange.None
                } else if (current != null) {
                    if (current.text == content) {
                        TranscriptChange.None
                    } else {
                        items[index!!] = current.copy(text = content)
                        TranscriptChange.Updated(index)
                    }
                } else {
                    thinkingIndexByContentIndex[delta.contentIndex] = items.size
                    append(
                        ThinkingBlock(
                            key = nextKey("thinking"),
                            ts = now(),
                            text = content,
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
        // F8: the row above is the truth; only the repaint is coalesced. Before
        // this, every chunk published a whole transcript, so a `bash`/`grep`
        // emitting hundreds of chunks a second drove hundreds of publications a
        // second — the visible symptom is a jittering output area exactly during
        // the long operations the user is watching (docs/rendering-review.md F8;
        // spec §4.3 `tool_execution_update` → "节流 200ms").
        val now = now()
        val last = lastToolPublishAt[event.toolCallId]
        if (last != null && now - last < TOOL_UPDATE_THROTTLE_MS) {
            suppressedToolUpdate = index
            return TranscriptChange.None
        }
        lastToolPublishAt[event.toolCallId] = now
        suppressedToolUpdate = null
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
        // F8: a card that finalises always publishes its final state — the
        // throttle is on the stream, never on the end of it — and its stamp is
        // dropped so the id cannot outlive the card in this map.
        lastToolPublishAt.remove(callId)
        if (index != null && suppressedToolUpdate == index) suppressedToolUpdate = null
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
     * Both of the app's entry points — the live `message_end` event and the
     * persisted `message` entry that [seedFromHistory] projects — funnel through
     * here, so a reopened session shows exactly what the live stream showed. The
     * two decisions are pi's own, each taken from one condition rather than a
     * local invention:
     *
     *  - **Which cards close.** `aborted` and `error` only.
     *    `packages/coding-agent/src/modes/interactive/interactive-mode.ts:3294`
     *    (live) and `:3735` (replay) both read
     *    `message.stopReason === "aborted" || message.stopReason === "error"`
     *    before `updateResult({content:[{type:"text",text:errorMessage}],
     *    isError:true})` — `:3298-3303` / `:3745-3746`. A `length` stop runs
     *    through the *else* branch (`:3305-3315`) and closes nothing, because the
     *    turn may still be resumed; its signal is the separate truncation row
     *    below.
     *  - **Whether the row is appended.**
     *    `packages/tui/src/components/assistant-message.ts:180-199`: `length`
     *    always prints; `aborted`/`error` print only while
     *    `!hasToolCalls` (`:180` = `content.some(c => c.type === "toolCall")`),
     *    because with a tool call pi lets the closed card carry the error
     *    instead of printing it twice.
     *
     * The wording is the app's own: pi's English strings are "Response was
     * truncated before completion." (`:185`), the abort message (`:189-193`) and
     * "Error: …" (`:196-198`), and the whole app speaks Chinese. One pi literal is
     * honoured rather than translated — the `errorMessage === "Request was
     * aborted"` case at `:190`, which pi replaces with its fixed "Operation
     * aborted" — so the app's generic sentence covers it instead of echoing a
     * provider string that pi itself refuses to echo. For `error`, `errorMessage`
     * (or pi's own `"Unknown error"` fallback at `:196`) becomes the row's whole
     * sentence, and a closed card's [ToolCall.output] gets that same text,
     * exactly like pi's `updateResult`.
     *
     * The reason set is `StopReason`'s non-normal members
     * (`packages/ai/src/types.ts:406`: `pending | stop | length | toolUse |
     * error | aborted | deferred`) minus the four that are not failures.
     *
     * [key] is passed in rather than synthesised so the history path can derive
     * it from pi's entry id, which keeps a re-seed from stacking a second row
     * (the live path uses `null` and gets a fresh synthetic key per turn).
     */
    private fun failTurn(
        reason: String?,
        errorMessage: String?,
        hasToolCalls: Boolean,
        key: String? = null,
    ): TranscriptChange {
        val ts = now()
        val truncated = reason == "length"
        // pi: `length` prints unconditionally; aborted/error print only when the
        // message carried no tool call (assistant-message.ts:182-199).
        val shouldAppend = truncated || !hasToolCalls
        // The sentence is pi's own content in every branch, so it never needs a
        // second "detail" line repeating it (`errorMessage` is exactly what pi
        // prints). Only one pi literal is replaced rather than carried through:
        // `assistant-message.ts:190` discards `errorMessage === "Request was
        // aborted"` in favour of a fixed line, and the app's generic Chinese
        // sentence is the equivalent of that fixed line.
        val message = when {
            truncated -> "回复被令牌上限截断"
            reason == "aborted" -> errorMessage
                ?.takeIf { it.isNotBlank() && it != "Request was aborted" }
                ?: "回合已中止"
            else -> errorMessage?.takeIf { it.isNotBlank() } ?: "Unknown error"
        }

        var change: TranscriptChange = if (shouldAppend) {
            append(ErrorText(key = key ?: nextKey("turn-failed"), ts = ts, message = message))
        } else {
            TranscriptChange.None
        }

        // pi closes the cards on `aborted`/`error` only — never on `length`.
        if (reason == "aborted" || reason == "error") {
            for (i in items.indices) {
                val call = items.getOrNull(i) as? ToolCall ?: continue
                if (call.status != ToolStatus.Pending) continue
                items[i] = call.copy(
                    status = ToolStatus.Error,
                    isError = true,
                    endedAt = ts,
                    output = call.output.ifEmpty { message },
                )
                // F8: closing the card publishes it, so a chunk the throttle was
                // still holding is delivered with it.
                if (suppressedToolUpdate == i) suppressedToolUpdate = null
                change = TranscriptChange.Updated(i)
            }
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
     * Entry types that are not conversation content — `label`, `session_info`,
     * the `session` header and anything newer than this build — are inert rather
     * than fatal. A `custom` entry is extension state and never enters the
     * model's context, but pi still draws it (see [onCustomEntry]), so it is
     * projected rather than dropped.
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
            // (`core/agent-session.ts:1658-1670`). A record with that type is
            // therefore inert here; the app's live model indicator comes from the
            // `get_state` poll after `agent_settled`, and the row below is the
            // app's own (pi's TUI draws nothing for `model_change`:
            // `sessionEntryToContextMessages` returns `[]` for it,
            // `core/session-manager.ts:383-408`).
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

            // An extension's own persisted state (`{ type, customType, data }`,
            // core/session-manager.ts:104-108, docs/session-format.md:279). pi
            // renders it through the renderer the extension registered for that
            // `customType`: live from `entry_appended` (`interactive-mode.ts:3202-3207`
            // → `:3557-3562`) and on replay (`renderSessionEntries`, `:3786-3789`,
            // whose item list really does carry `custom` entries — `RenderSessionItem`
            // at `:228`, dispatched at `:3703-3707`). Before this case the app
            // dropped it on both paths.
            "custom" -> onCustomEntry(entry, entryId, ts)

            // F26: there is deliberately **no** `skill` / `skill_invocation` case
            // here. pi has neither an entry type nor an event with those names —
            // the persisted union is exactly message, thinking_level_change,
            // model_change, compaction, branch_summary, custom, custom_message,
            // label and session_info (`core/session-manager.ts:145-155`) —
            // because `_expandSkillCommand` rewrites the user message itself
            // (`core/agent-session.ts`). The real projection is the `<skill …>`
            // split in [projectUser] from that user message, which is reachable
            // from a live `message` entry and from history. The aliases that used
            // to live here were reachable only by hand-feeding a `JsonObject`.

            // pi has no `system_prompt` or `error` entry type — the persisted
            // union is exactly message, thinking_level_change, model_change,
            // compaction, branch_summary, custom, custom_message, label and
            // session_info (`core/session-manager.ts:145-155`). The system prompt
            // is only reachable through `getSystemPrompt()`, which no RPC command
            // exposes (`rpc-types.ts:20-71`), and a failure arrives as a
            // `stopReason`/delta event, so neither is handled here. A row for the
            // system prompt comes only from the app-side [onSystemPrompt] hook.
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

            "assistant" -> onHistoryAssistant(message, entryId, ts, blockKey)

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
        entryId: String?,
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
        // A persisted assistant entry keeps the whole assistant message including
        // `stopReason`/`errorMessage` (`packages/ai/src/types.ts:440,443`; the
        // session file stores `SessionMessageEntry.message` verbatim, and
        // `core/session-manager.ts:386-394` hands that same object back as an
        // `AgentMessage` on replay). So a turn that aborted, errored or was cut
        // off by the token limit is decided here exactly as pi decides it on
        // replay: `interactive-mode.ts:3735-3746` closes every tool component the
        // aborted assistant message opened, and `assistant-message.ts:180-199`
        // prints the truncation / abort / error line under the partial content.
        //
        // `hasToolCalls` is the same predicate on the same array pi reads
        // (`assistant-message.ts:180`), and `failTurn` applies the same two
        // conditions as the live path. Without this branch the live stream and a
        // reopened session disagree: the live `message_end` path already reports
        // the failure (see [failTurn]), and the replay path silently produced a
        // clean-looking transcript — F2/F3's root cause.
        val stopReason = message.str("stopReason")?.lowercase()
        if (stopReason in TURN_FAILURE_REASONS) {
            val errorMessage = message.str("errorMessage")
            return failTurn(stopReason, errorMessage, hasToolCallBlock(message["content"]), key = failureKey(entryId))
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
                // F18: the persisted entry carries what the summarization call
                // cost (`core/session-manager.ts` `BranchSummaryEntry.usage`), and
                // `SessionEntries.kt` already parses it for the tree screen — this
                // is the same figure reaching the transcript, live and on replay.
                usage = entry.obj("usage")?.let(PiEvents::parseUsage),
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

    /**
     * The row for a `custom` session entry — extension state that deliberately
     * never enters the model's context (`core/session-manager.ts:95-108`,
     * `docs/session-format.md:279-282`). F6: both paths used to drop it, so an
     * extension keeping visible state with `pi.appendEntry()` showed nothing.
     *
     * pi hands the entry to the renderer the extension registered for its
     * `customType` (`interactive-mode.ts:3557-3562` →
     * `components/custom-entry.ts:40-46`). That renderer is a live TUI component
     * and cannot cross the RPC boundary, so the honest fallback is the one pi's
     * own failure branch also prints — the type and the payload
     * (`custom-entry.ts:48-52`: `[customType] renderer failed: …`).
     *
     * It is deliberately **not** a [HookMessage]: `custom` state is not context
     * (`sessionEntryToContextMessages`, `core/session-manager.ts:383-408`, skips
     * every `custom` entry), while `custom_message` — the card [onHookEntry]
     * builds — *is* context (`:124-142`); reusing that card would assert
     * something false.
     *
     * When no renderer is registered for a `customType`, pi is silent
     * (`interactive-mode.ts:3558-3561` returns before constructing a component),
     * and when the renderer produces nothing it adds no child either
     * (`components/custom-entry.ts:54-56`). The RPC wire carries no renderer
     * registry, so the app cannot take that same decision, and the entry only
     * reaches here because an extension explicitly appended it — showing the
     * metadata is strictly closer to pi than the invisible row this replaces.
     *
     * The wording is the app's own: pi has no text for this row at all.
     */
    private fun onCustomEntry(entry: JsonObject, entryId: String?, ts: Long): TranscriptChange {
        // pi's `CustomEntry` has no `display` field (`core/session-manager.ts:104-108`);
        // only `CustomMessageEntry` has one (`:136-142`), and [onHookEntry]
        // honours that one. A newer pi that adds it here is obeyed rather than
        // ignored, so state an extension marks hidden cannot become a row.
        if (entry.bool("display") == false) return TranscriptChange.None
        val customType = entry.str("customType")
            ?: entry.str("custom_type")
            ?: "extension"
        val data = compactEntryData(entry["data"])
        val text = if (data.isEmpty()) "扩展状态：$customType" else "扩展状态：$customType · $data"
        return append(
            Notice(
                key = keyFor(entryId, "custom"),
                ts = ts,
                text = text,
                tone = Notice.Tone.Info,
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

    /**
     * Append a [SystemPrompt] block. **App-only hook with no producer** (F22).
     *
     * pi has no `system_prompt` session entry — the persisted union is
     * `core/session-manager.ts:145-155` — and no RPC command exposes the prompt:
     * `rpc-types.ts:20-71` has none, and `get_state`'s `RpcSessionState`
     * (`:96-109`) carries no prompt field. pi never renders the prompt text at
     * all: its `/context` listing prints only the prompt's *source path*
     * (`interactive-mode.ts:1715-1722`) and the only reader of the text is the
     * extension-runner hook (`:2068`). So **no wire data can ever reach this
     * method**: it is not called from `app/` either (grep: declaration + tests
     * only).
     *
     * It is kept, rather than deleted, only because [SystemPrompt] is still
     * rendered by `ui/blocks/SystemPromptBlock.kt` through `BlockRenderer`; the
     * honest fix is a coordinated deletion of item + block + this method + the
     * spec §7.4 row, which cannot be done from `rpc/` alone. Recorded in
     * `docs/gap-disposition.md` §10 (F22).
     */
    fun onSystemPrompt(text: String, entryId: String? = null): TranscriptChange {
        if (text.isEmpty()) return TranscriptChange.None
        return append(SystemPrompt(key = keyFor(entryId, "system"), ts = now(), fullText = text))
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

    // ------------------------------------------------------------------ helpers

    private fun finishStreaming(): TranscriptChange {
        streaming = false
        // Content-block numbering restarts with the next assistant message.
        textIndexByContentIndex.clear()
        thinkingIndexByContentIndex.clear()
        // F8: the tail of a throttled burst must not be lost. Every turn-ending
        // event comes through here (`message_end`, `agent_end`, `agent_settled`),
        // so the row the throttle was still holding is reported now — otherwise a
        // chunk that arrived inside the 200 ms window could stay unpublished for
        // as long as the tool is silent.
        val flushed = suppressedToolUpdate?.takeIf { items.getOrNull(it) is ToolCall }
        suppressedToolUpdate = null
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
        return when {
            touched >= 0 -> TranscriptChange.Updated(touched)
            flushed != null -> TranscriptChange.Updated(flushed)
            else -> TranscriptChange.None
        }
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
        lastToolPublishAt.clear()
        suppressedToolUpdate = null
        lastUsage = null
        currentDay = null
        streaming = false
    }

    private companion object {
        /**
         * Entry-shaped events that a newer pi may emit directly on stdout. They
         * are projectable through [onEntry]; everything else unknown is inert.
         *
         * Only **real** pi entry types belong here — this is the list's whole
         * point, because a name that pi cannot emit makes an unknown event look
         * projectable and lets a hand-fed record masquerade as wire data. The
         * persisted union is `core/session-manager.ts:145-155`: message,
         * thinking_level_change, model_change, compaction, branch_summary,
         * custom, custom_message, label, session_info. So `model_select`,
         * `system_prompt` and `error` are absent (the first is extension-only,
         * the other two are not entry types), and F26 removed the former
         * `skill`/`skill_invocation` aliases for the same reason: pi has no such
         * entry, it rewrites the user message instead (`core/agent-session.ts`).
         * `custom` is in the union and is projectable. `hook_message` is the one
         * deliberate extra: it is the alias [onEntry] accepts for
         * `custom_message`'s shape, kept so an app-side or older record still
         * lands.
         */
        val ENTRY_EVENT_TYPES = setOf(
            "model_change",
            "thinking_level_change",
            "compaction",
            "branch_summary",
            "custom",
            "custom_message",
            "hook_message",
        )
    }
}
