package app.pi.ui.chat.activity

import app.pi.protocol.DateSeparator
import app.pi.protocol.PiImage
import app.pi.protocol.ThinkingBlock
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.protocol.UserMessage
import app.pi.ui.blocks.shortenToolPathForPhone
import app.pi.ui.blocks.toolBlocked
import app.pi.ui.render.TranscriptGap
import kotlinx.serialization.json.JsonPrimitive

/**
 * Presentation-layer row projected from [TranscriptItem]s.
 *
 * Pure read-only projection: never mutates `TranscriptReducer` or `TranscriptItem`.
 */
sealed interface ChatRow {
    val key: String

    /** The first underlying transcript item, used for speaker-change spacing. */
    val firstItem: TranscriptItem

    data class Single(val item: TranscriptItem) : ChatRow {
        override val key: String get() = item.key
        override val firstItem: TranscriptItem get() = item
    }

    data class ActivityGroup(
        override val key: String,
        val items: List<TranscriptItem>,
        val summary: ActivitySummary,
    ) : ChatRow {
        override val firstItem: TranscriptItem get() = items.first()
    }
}

/** Category of the currently running step inside a live [ChatRow.ActivityGroup]. */
enum class ActiveStepKind {
    Thinking,
    Read,
    Edit,
    Write,
    Search,
    Browse,
    Shell,
    Other,
}

/** Pre-computed semantic summary for one collapsed [ChatRow.ActivityGroup]. */
data class ActivitySummary(
    val stepCount: Int,
    val thinkingCount: Int,
    val readCount: Int,
    val editCount: Int,
    val writeCount: Int,
    val searchCount: Int,
    val shellCount: Int,
    val otherToolCount: Int,
    val failedCount: Int,
    val rejectedCount: Int,
    val isRunning: Boolean,
    val hasPendingTool: Boolean,
    val activeStepKind: ActiveStepKind?,
    val activeToolName: String?,
    val activeTarget: String?,
    val singleToolName: String?,
    val singleToolTarget: String?,
    val firstErrorTitle: String?,
    val firstErrorSnippet: String?,
    val totalAdded: Int,
    val totalRemoved: Int,
    val totalElapsedMs: Long?,
    val images: List<PiImage>,
)

/**
 * Pairs each `edit` [ToolCall] with its emitted [ToolDiff] (matched by `toolCallId`) so the UI
 * renders one unified edit card instead of two duplicate cards, without modifying `TranscriptReducer`.
 */
fun pairEditDiffs(transcript: List<TranscriptItem>): Map<String, ToolDiff> {
    val editCallIds = HashSet<String>()
    for (item in transcript) {
        if (item is ToolCall && item.toolName == "edit" && item.toolCallId.isNotEmpty()) {
            editCallIds += item.toolCallId
        }
    }
    if (editCallIds.isEmpty()) return emptyMap()
    val paired = LinkedHashMap<String, ToolDiff>()
    for (item in transcript) {
        if (item is ToolDiff) {
            val id = item.toolCallId
            if (id != null && id in editCallIds) {
                paired[id] = item
            }
        }
    }
    return paired
}

/**
 * Pure presentation-layer projector for Compact Trace mode (`app.chat.toolDisplayMode == "compact"`).
 *
 * Groups consecutive process items (`ThinkingBlock`, `ToolCall`, standalone `ToolDiff`) into a
 * single [ChatRow.ActivityGroup] with a stable key anchored to the run's first item, while leaving
 * prose (`AssistantText`), user turns, errors, notices, compaction markers, and lone thinking blocks
 * untouched as [ChatRow.Single].
 */
object ActivityGroupProjector {
    fun project(
        items: List<TranscriptItem>,
        pairedDiffByCallId: Map<String, ToolDiff> = emptyMap(),
        hideThinking: Boolean = false,
    ): List<ChatRow> {
        if (items.isEmpty()) return emptyList()
        val rows = ArrayList<ChatRow>(items.size)
        val run = ArrayList<TranscriptItem>()

        fun flushRun() {
            if (run.isEmpty()) return
            val hasToolOrDiff = run.any { it is ToolCall || it is ToolDiff }
            if (!hasToolOrDiff && run.size == 1) {
                // Keep a lone thinking block as its own ultra-clean single line (no extra wrapper box).
                rows += ChatRow.Single(run[0])
            } else {
                val snapshot = run.toList()
                rows += ChatRow.ActivityGroup(
                    key = "activity-group:${snapshot.first().key}",
                    items = snapshot,
                    summary = summarize(snapshot, pairedDiffByCallId),
                )
            }
            run.clear()
        }

        for (item in items) {
            if (hideThinking && item is ThinkingBlock) continue
            if (isProcessItem(item)) {
                run += item
            } else {
                flushRun()
                rows += ChatRow.Single(item)
            }
        }
        flushRun()
        return rows
    }

    private fun isProcessItem(item: TranscriptItem): Boolean = when (item) {
        is ThinkingBlock, is ToolCall, is ToolDiff -> true
        else -> false
    }

    internal fun summarize(
        items: List<TranscriptItem>,
        pairedDiffByCallId: Map<String, ToolDiff>,
    ): ActivitySummary {
        var thinkingCount = 0
        var readCount = 0
        var editCount = 0
        var writeCount = 0
        var searchCount = 0
        var shellCount = 0
        var otherToolCount = 0
        var failedCount = 0
        var rejectedCount = 0
        var isRunning = false
        var hasPendingTool = false
        var activeStepKind: ActiveStepKind? = null
        var activeToolName: String? = null
        var activeTarget: String? = null
        var firstErrorTitle: String? = null
        var firstErrorSnippet: String? = null
        var totalAdded = 0
        var totalRemoved = 0
        var elapsedSum = 0L
        var hasElapsed = false
        val images = ArrayList<PiImage>()
        val toolCalls = ArrayList<ToolCall>()

        for (item in items) {
            when (item) {
                is ThinkingBlock -> {
                    thinkingCount++
                    if (item.streaming) {
                        isRunning = true
                        activeStepKind = ActiveStepKind.Thinking
                        activeToolName = null
                        activeTarget = null
                    }
                    item.elapsedMs?.takeIf { it > 0L }?.let {
                        elapsedSum += it
                        hasElapsed = true
                    }
                }

                is ToolCall -> {
                    toolCalls += item
                    when (item.toolName) {
                        "read" -> readCount++
                        "edit" -> editCount++
                        "write" -> writeCount++
                        "grep", "find", "ls" -> searchCount++
                        "bash", "powershell" -> shellCount++
                        else -> otherToolCount++
                    }
                    val paired = pairedDiffByCallId[item.toolCallId]
                    if (paired != null) {
                        totalAdded += paired.added
                        totalRemoved += paired.removed
                    }
                    if (item.images.isNotEmpty()) {
                        images.addAll(item.images)
                    }
                    when (item.status) {
                        ToolStatus.Pending -> {
                            isRunning = true
                            hasPendingTool = true
                            activeStepKind = activeKindOf(item.toolName)
                            activeToolName = item.toolName
                            activeTarget = extractTarget(item, paired)
                        }

                        ToolStatus.Error -> {
                            if (toolBlocked(item.output)) {
                                rejectedCount++
                            } else {
                                failedCount++
                                if (firstErrorSnippet == null) {
                                    val target = extractTarget(item, paired)?.takeIf { it.isNotBlank() }
                                    firstErrorTitle = listOfNotNull(
                                        item.toolName.takeIf { it.isNotBlank() },
                                        target,
                                    ).joinToString(" ")
                                    firstErrorSnippet = app.pi.ui.blocks.extractCleanErrorSnippet(item.output, maxChars = 110)
                                }
                            }
                        }

                        ToolStatus.Success -> Unit
                    }
                    item.elapsedMs?.takeIf { it > 0L }?.let {
                        elapsedSum += it
                        hasElapsed = true
                    }
                }

                is ToolDiff -> {
                    editCount++
                    totalAdded += item.added
                    totalRemoved += item.removed
                }

                else -> Unit
            }
        }

        val onlyTool = if (toolCalls.size == 1 && items.none { it is ToolDiff }) toolCalls[0] else null
        val singleTarget = onlyTool?.let { extractTarget(it, pairedDiffByCallId[it.toolCallId]) }

        return ActivitySummary(
            stepCount = items.size,
            thinkingCount = thinkingCount,
            readCount = readCount,
            editCount = editCount,
            writeCount = writeCount,
            searchCount = searchCount,
            shellCount = shellCount,
            otherToolCount = otherToolCount,
            failedCount = failedCount,
            rejectedCount = rejectedCount,
            isRunning = isRunning,
            hasPendingTool = hasPendingTool,
            activeStepKind = activeStepKind,
            activeToolName = activeToolName,
            activeTarget = activeTarget,
            singleToolName = onlyTool?.toolName,
            singleToolTarget = singleTarget,
            firstErrorTitle = firstErrorTitle,
            firstErrorSnippet = firstErrorSnippet,
            totalAdded = totalAdded,
            totalRemoved = totalRemoved,
            totalElapsedMs = if (hasElapsed) elapsedSum else null,
            images = images,
        )
    }

    private fun activeKindOf(toolName: String): ActiveStepKind = when (toolName) {
        "read" -> ActiveStepKind.Read
        "edit" -> ActiveStepKind.Edit
        "write" -> ActiveStepKind.Write
        "grep", "find" -> ActiveStepKind.Search
        "ls" -> ActiveStepKind.Browse
        "bash", "powershell" -> ActiveStepKind.Shell
        else -> ActiveStepKind.Other
    }

    private fun extractTarget(call: ToolCall, pairedDiff: ToolDiff?): String? {
        val args = call.args
        fun str(vararg keys: String): String? {
            if (args == null) return null
            for (k in keys) {
                val v = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return null
        }
        return when (call.toolName) {
            "read", "write" -> str("path", "file_path", "filePath")?.let { shortTail(it) }
            "edit" -> (str("file_path", "path", "filePath") ?: pairedDiff?.path?.takeIf { it.isNotBlank() })?.let { shortTail(it) }
            "grep", "find" -> str("pattern")?.take(24) ?: str("path")?.let { shortTail(it) }
            "ls" -> str("path")?.let { shortTail(it) }
            "bash", "powershell" -> str("command", "cmd")
                ?.let { app.pi.ui.blocks.formatShellCommandPreview(it, maxChars = 34) }
                ?.takeIf { it.isNotEmpty() }
            else -> app.pi.ui.blocks.toolParamSummary(args)
                ?.let { app.pi.ui.blocks.truncatePreviewCleanly(it, 34) }
                ?: call.argsSummary.takeIf { it.isNotBlank() }?.let { app.pi.ui.blocks.truncatePreviewCleanly(it, 34) }
        }
    }

    private fun shortTail(path: String): String {
        val trimmed = path.replace('\\', '/').trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        val base = if (slash >= 0 && slash < trimmed.lastIndex) trimmed.substring(slash + 1) else trimmed
        return if (base.length <= 28) base else shortenToolPathForPhone(trimmed, maxChars = 28)
    }
}

/** Speaker-change spacing helper over projected [ChatRow]s, mirroring `transcriptGap`. */
internal fun chatRowGap(rows: List<ChatRow>, index: Int): TranscriptGap {
    val item = rows.getOrNull(index)?.firstItem ?: return TranscriptGap.SameMessage
    if (item is DateSeparator) return TranscriptGap.SameMessage
    if (item is UserMessage) return TranscriptGap.UserMessage
    var prev = index - 1
    while (prev >= 0 && rows[prev].firstItem is DateSeparator) prev--
    return when (rows.getOrNull(prev)?.firstItem) {
        null -> TranscriptGap.SameMessage
        is UserMessage -> TranscriptGap.AssistantMessage
        else -> TranscriptGap.SameMessage
    }
}
