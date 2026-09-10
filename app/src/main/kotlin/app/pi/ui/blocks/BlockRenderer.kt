package app.pi.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pi.rpc.AssistantText
import app.pi.rpc.BranchSummary
import app.pi.rpc.CompactionMarker
import app.pi.rpc.DateSeparator
import app.pi.rpc.ErrorText
import app.pi.rpc.HookMessage
import app.pi.rpc.ModelChange
import app.pi.rpc.Notice
import app.pi.rpc.SkillInvocation
import app.pi.rpc.SystemPrompt
import app.pi.rpc.ThinkingBlock
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.TranscriptItem
import app.pi.rpc.UserMessage

/**
 * The one place a [TranscriptItem] becomes pixels.
 *
 * Every kind from docs/pi-android-ui-spec.md §7.4 has a renderer; [Notice]
 * covers App chrome and doubles as the fallback for an item kind a newer pi
 * introduces, so an engine upgrade degrades a single row instead of blanking the
 * stream. Callers put the items in a `LazyColumn` keyed by `item.key` and pass
 * the changes the reducer reports.
 *
 * @param hideThinking when true the thinking blocks are hidden entirely
 *   (pi's `hideThinkingBlock`).
 * @param thinkingDefaultExpanded collapsed by default, per spec principle 4.
 * @param toolsDefaultExpanded maps to pi's `app.tools.expand`.
 */
@Composable
fun BlockRenderer(
    item: TranscriptItem,
    modifier: Modifier = Modifier,
    hideThinking: Boolean = false,
    thinkingDefaultExpanded: Boolean = false,
    toolsDefaultExpanded: Boolean = false,
    onImageClick: ((Int) -> Unit)? = null,
    onDiffOpenFull: ((ToolDiff) -> Unit)? = null,
    onBranchClick: ((BranchSummary) -> Unit)? = null,
    onModelClick: (() -> Unit)? = null,
    onErrorRetry: (() -> Unit)? = null,
) {
    when (item) {
        is UserMessage -> UserMessageBlock(item, modifier, onImageClick)

        is AssistantText -> AssistantTextBlock(item, modifier)

        is ThinkingBlock -> if (!hideThinking) {
            ThinkingBlockBlock(item, modifier, thinkingDefaultExpanded)
        }

        is ToolCall -> ToolCallBlock(item, modifier, toolsDefaultExpanded)

        is ToolDiff -> DiffBlock(item, modifier, toolsDefaultExpanded, onDiffOpenFull)

        is CompactionMarker -> CompactionBlock(item, modifier)

        is BranchSummary -> BranchSummaryBlock(item, modifier, onBranchClick)

        is HookMessage -> HookMessageBlock(item, modifier)

        is ModelChange -> ModelChangeBlock(item, modifier, onModelClick)

        is SkillInvocation -> SkillInvocationBlock(item, modifier)

        is SystemPrompt -> SystemPromptBlock(item, modifier)

        is ErrorText -> ErrorBlock(item, modifier, onErrorRetry)

        is DateSeparator -> DateSeparatorBlock(item, modifier)

        is Notice -> NoticeBlock(item, modifier)

        // A newer pi added a block kind this build does not render yet.
        else -> NoticeBlock(
            Notice(
                key = item.key,
                ts = item.ts,
                text = "暂不支持的内容块（升级 App 后可见）",
                tone = Notice.Tone.Info,
            ),
            modifier,
        )
    }
}
