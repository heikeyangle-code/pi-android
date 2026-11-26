package app.pi.ui.chat.activity

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.protocol.BranchSummary
import app.pi.protocol.PiImage
import app.pi.protocol.ThinkingBlock
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.ui.blocks.BlockColumn
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.blocks.DiffStatsBadges
import app.pi.ui.blocks.ToggleContent
import app.pi.ui.blocks.ToolImages
import app.pi.ui.blocks.formatDuration
import app.pi.ui.blocks.toolBlocked
import app.pi.ui.theme.PiTheme

private val ACTIVITY_BAR_SHAPE = RoundedCornerShape(10.dp)
private val SPINE_RAIL_X = 7.dp
private val SPINE_CONTENT_INDENT = 18.dp

/**
 * Compact Trace row (`app.appearance.toolDisplayMode == "compact"`).
 *
 * Keeps the assistant's prose visually dominant while folding intermediate thinking + tool calls
 * into a single serene, hairline-framed summary bar:
 *  - Running: breathing status dot + live active action + step count (constant 1-line height).
 *  - Settled: natural semantic summary + optional `+N −M` diff badge + quiet duration + chevron.
 *  - Safety: failed steps surface an error tint + 1-line inline snippet; returned images stay
 *    visible even while collapsed.
 *  - Expanded: clean vertical timeline spine delegating each step directly to [BlockRenderer]
 *    (zero duplicate tool renderers, zero `animateContentSize` jitter).
 */
@Composable
fun ActivityGroupBlock(
    group: ChatRow.ActivityGroup,
    pairedDiffByCallId: Map<String, ToolDiff>,
    modifier: Modifier = Modifier,
    hideThinking: Boolean = false,
    defaultExpanded: Boolean = false,
    showBilledCost: Boolean = false,
    showTimestamps: Boolean = false,
    onBranchClick: ((BranchSummary) -> Unit)? = null,
    onForkFromMessage: ((String) -> Unit)? = null,
    onImageClick: ((PiImage) -> Unit)? = null,
    nowMs: Long? = null,
) {
    val palette = PiTheme.palette
    val summary = group.summary
    var expanded by rememberSaveable(group.key, defaultExpanded) { mutableStateOf(defaultExpanded) }

    val pulse: State<Float>? = if (summary.isRunning && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "activity-pulse").animateFloat(
            initialValue = 0.25f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
            label = "activity-pulse-alpha",
        )
    } else {
        null
    }
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(160),
        label = "activity-chevron",
    )

    val hasError = summary.failedCount > 0
    val containerBg = when {
        hasError -> palette.toolErrorBg.copy(alpha = 0.72f)
        summary.isRunning -> palette.toolPendingBg.copy(alpha = 0.72f)
        else -> palette.toolSuccessBg.copy(alpha = 0.56f)
    }
    val borderColor = when {
        hasError -> palette.error.copy(alpha = 0.38f)
        summary.isRunning -> palette.accent.copy(alpha = 0.32f)
        expanded -> palette.borderMuted.copy(alpha = 0.72f)
        else -> palette.borderMuted.copy(alpha = 0.50f)
    }
    val dotColor = when {
        hasError -> palette.error
        summary.isRunning -> palette.accent
        else -> palette.muted.copy(alpha = 0.65f)
    }

    val headline = remember(summary, PiLocale.isZh) { formatActivityHeadline(summary) }
    val durationText = remember(summary.totalElapsedMs, summary.isRunning) {
        if (summary.isRunning) null
        else summary.totalElapsedMs?.takeIf { it >= 1_000L }?.let { formatDuration(it) }
    }

    BlockColumn(modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top summary bar: wrapped in ToggleContent so expanding/collapsing holds viewport
            // anchor & tail-follow rock-steady with zero jump.
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ACTIVITY_BAR_SHAPE)
                        .background(containerBg)
                        .border(0.5.dp, borderColor, ACTIVITY_BAR_SHAPE)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .graphicsLayer { alpha = pulse?.value ?: 1f }
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = headline,
                            modifier = Modifier.weight(1f),
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (summary.isRunning || hasError) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = when {
                                hasError -> palette.error
                                summary.isRunning -> palette.text
                                else -> palette.muted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary.totalAdded > 0 || summary.totalRemoved > 0) {
                            Spacer(Modifier.width(8.dp))
                            DiffStatsBadges(summary.totalAdded, summary.totalRemoved)
                        }
                        if (durationText != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = durationText,
                                style = PiTheme.text.monoSmall,
                                color = palette.muted.copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (expanded) tr("收起执行过程", "Collapse activity") else tr("展开执行过程", "Expand activity"),
                            modifier = Modifier
                                .size(15.dp)
                                .graphicsLayer { rotationZ = chevronAngle },
                            tint = palette.muted,
                        )
                    }

                    // Surface the first failed tool's message cleanly even when collapsed so errors are never hidden.
                    if (!expanded && hasError && !summary.firstErrorSnippet.isNullOrEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        val prefix = summary.firstErrorTitle?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
                        Text(
                            text = prefix + summary.firstErrorSnippet,
                            style = PiTheme.text.monoSmall,
                            color = palette.error.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 15.dp),
                        )
                    }
                }
            }

            // Surface screenshots / returned tool images even while the process trace is collapsed.
            if (!expanded && summary.images.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ToolImages(summary.images, onImageClick)
            }

            // Expanded vertical trace spine: clean hairline timeline delegating each step to BlockRenderer.
            // Per-item rail segments stop 2dp before/after each 5dp node dot so the rail never cuts through
            // a node and never extends past the last dot even when the last step card is expanded.
            if (expanded) {
                Spacer(Modifier.height(5.dp))
                val railColor = palette.borderMuted.copy(alpha = 0.60f)
                val lastIdx = group.items.lastIndex
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    group.items.forEachIndexed { idx, child ->
                        val nodeColor = stepNodeColor(child)
                        val hasAbove = idx > 0
                        val hasBelow = idx < lastIdx
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    if (!hasAbove && !hasBelow) return@drawBehind
                                    val x = SPINE_RAIL_X.toPx()
                                    val stroke = 1.dp.toPx()
                                    val dotTop = 13.dp.toPx()
                                    val dotBottom = 18.dp.toPx()
                                    val halo = 2.dp.toPx()
                                    if (hasAbove) {
                                        drawLine(
                                            color = railColor,
                                            start = Offset(x, -4.dp.toPx()),
                                            end = Offset(x, (dotTop - halo).coerceAtLeast(0f)),
                                            strokeWidth = stroke,
                                        )
                                    }
                                    if (hasBelow) {
                                        val startY = dotBottom + halo
                                        if (size.height > startY) {
                                            drawLine(
                                                color = railColor,
                                                start = Offset(x, startY),
                                                end = Offset(x, size.height),
                                                strokeWidth = stroke,
                                            )
                                        }
                                    }
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.5.dp, top = 13.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(nodeColor),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = SPINE_CONTENT_INDENT),
                            ) {
                                BlockRenderer(
                                    item = child,
                                    hideThinking = hideThinking,
                                    thinkingDefaultExpanded = false,
                                    toolsDefaultExpanded = false,
                                    showBilledCost = showBilledCost,
                                    showTimestamps = showTimestamps,
                                    onBranchClick = onBranchClick,
                                    onForkFromMessage = onForkFromMessage,
                                    onImageClick = onImageClick,
                                    nowMs = if (child is ToolCall && child.status == ToolStatus.Pending) nowMs else null,
                                    pairedDiff = (child as? ToolCall)?.let { pairedDiffByCallId[it.toolCallId] },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stepNodeColor(item: TranscriptItem) = when (item) {
    is ThinkingBlock -> if (item.streaming) PiTheme.palette.accent else PiTheme.palette.muted.copy(alpha = 0.65f)
    is ToolCall -> when (item.status) {
        ToolStatus.Pending -> PiTheme.palette.accent
        ToolStatus.Error -> if (toolBlocked(item.output)) PiTheme.palette.muted else PiTheme.palette.error
        ToolStatus.Success -> PiTheme.palette.muted.copy(alpha = 0.72f)
    }
    is ToolDiff -> PiTheme.palette.toolDiffAdded.copy(alpha = 0.8f)
    else -> PiTheme.palette.muted.copy(alpha = 0.6f)
}

internal fun formatActivityHeadline(s: ActivitySummary): String {
    if (s.isRunning) {
        val action = when (s.activeStepKind) {
            ActiveStepKind.Thinking -> tr("正在思考…", "Thinking…")
            ActiveStepKind.Read -> s.activeTarget?.let { tr("正在读取 $it", "Reading $it") }
                ?: tr("正在读取文件", "Reading file")
            ActiveStepKind.Edit -> s.activeTarget?.let { tr("正在修改 $it", "Editing $it") }
                ?: tr("正在修改文件", "Editing file")
            ActiveStepKind.Write -> s.activeTarget?.let { tr("正在写入 $it", "Writing $it") }
                ?: tr("正在写入文件", "Writing file")
            ActiveStepKind.Search -> s.activeTarget?.let { tr("正在搜索 $it", "Searching $it") }
                ?: tr("正在搜索代码", "Searching")
            ActiveStepKind.Browse -> s.activeTarget?.let { tr("正在查看 $it", "Listing $it") }
                ?: tr("正在查看目录", "Listing directory")
            ActiveStepKind.Shell -> tr("正在运行命令", "Running command")
            ActiveStepKind.Other, null -> s.activeToolName?.takeIf { it.isNotBlank() }?.let {
                tr("正在执行 $it", "Running $it")
            } ?: tr("正在处理…", "Working…")
        }
        return if (s.stepCount > 1) {
            "$action · ${tr("第 ${s.stepCount} 步", "step ${s.stepCount}")}"
        } else {
            action
        }
    }

    // Crisp single-tool natural phrasing when only one tool ran in this group.
    if (s.singleToolName != null && s.failedCount == 0 && s.rejectedCount == 0) {
        val t = s.singleToolTarget
        val singlePhrase = when (s.singleToolName) {
            "read" -> t?.let { tr("读取了 $it", "Read $it") } ?: tr("读取了 1 个文件", "Read 1 file")
            "edit" -> t?.let { tr("修改了 $it", "Edited $it") } ?: tr("修改了 1 个文件", "Edited 1 file")
            "write" -> t?.let { tr("写入了 $it", "Wrote $it") } ?: tr("写入了 1 个文件", "Wrote 1 file")
            "grep" -> t?.let { tr("搜索了 $it", "Searched $it") } ?: tr("搜索了代码", "Searched code")
            "find" -> t?.let { tr("查找了 $it", "Found $it") } ?: tr("查找了文件", "Found files")
            "ls" -> t?.let { tr("查看了 $it", "Listed $it") } ?: tr("查看了目录", "Listed directory")
            "bash", "powershell" -> tr("运行了命令", "Ran command")
            else -> tr("执行了 ${s.singleToolName}", "Ran ${s.singleToolName}")
        }
        return if (s.thinkingCount > 0) {
            "${tr("思考", "Thought")} · $singlePhrase"
        } else {
            singlePhrase
        }
    }

    val categories = buildList {
        if (s.readCount > 0) add("read" to s.readCount)
        if (s.searchCount > 0) add("search" to s.searchCount)
        if (s.editCount > 0) add("edit" to s.editCount)
        if (s.writeCount > 0) add("write" to s.writeCount)
        if (s.shellCount > 0) add("shell" to s.shellCount)
        if (s.otherToolCount > 0) add("other" to s.otherToolCount)
    }
    val compactLabels = categories.size >= 3
    val parts = ArrayList<String>()
    if (s.thinkingCount > 0) {
        parts += if (s.thinkingCount == 1) tr("思考", "Thought") else tr("思考 ${s.thinkingCount}", "Thought ${s.thinkingCount}")
    }
    for ((kind, n) in categories) {
        parts += when (kind) {
            "read" -> if (compactLabels) tr("读 $n", "Read $n") else tr("读取 $n 个文件", if (n == 1) "Read 1 file" else "Read $n files")
            "search" -> if (compactLabels) tr("搜 $n", "Search $n") else tr("搜索 $n 次", if (n == 1) "Searched 1×" else "Searched ${n}×")
            "edit" -> if (compactLabels) tr("改 $n", "Edit $n") else tr("修改 $n 处", if (n == 1) "Edited 1" else "Edited $n")
            "write" -> if (compactLabels) tr("写 $n", "Write $n") else tr("写入 $n 个文件", if (n == 1) "Wrote 1 file" else "Wrote $n files")
            "shell" -> if (compactLabels) tr("命令 $n", "Shell $n") else tr("运行 $n 条命令", if (n == 1) "Ran 1 cmd" else "Ran $n cmds")
            else -> if (compactLabels) tr("工具 $n", "Tools $n") else tr("调用 $n 次工具", if (n == 1) "1 tool" else "$n tools")
        }
    }
    if (s.failedCount > 0) {
        parts += tr("${s.failedCount} 项失败", "${s.failedCount} failed")
    } else if (s.rejectedCount > 0) {
        parts += tr("${s.rejectedCount} 项未执行", "${s.rejectedCount} blocked")
    }
    return if (parts.isEmpty()) {
        tr("已完成 ${s.stepCount} 步", "${s.stepCount} steps")
    } else {
        parts.joinToString(" · ")
    }
}
