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
 * covers App chrome. Callers put the items in a `LazyColumn` keyed by `item.key`
 * and pass the changes the reducer reports.
 *
 * **No `else` (F25 in `docs/rendering-review.md`).** [TranscriptItem] is a sealed
 * interface with exactly the 14 kinds above, so the
 * `else -> NoticeBlock("暂不支持的内容块")` this used to carry was unreachable: an
 * unknown *item* cannot exist, and the case that can — a newer pi's *event* —
 * never reaches this function, it is dropped in the reducer (`PiEvent.Unknown`,
 * `rpc/Transcript.kt:961-965`). The honest place for the "upgrade the app" row is
 * therefore that reducer branch, not here; leaving the branch in place only made
 * a newer item subtype compile silently instead of failing the build. A future
 * kind must be added to this `when` (and the `else` kept absent) so the compiler
 * names every place that needs it.
 *
 * F19 (`docs/rendering-review.md`): this function used to declare five optional
 * callbacks and its only caller supplied none of them, so five affordances were
 * unreachable while the blocks kept rendering labels for them. Two have a target
 * in the app and are now supplied (`onBranchClick`, `onModelClick`); the other
 * three had no target at all — no image viewer, no full-screen diff route, no
 * retry action — so their parameters and the labels they gated were deleted
 * (`onImageClick`, `onDiffOpenFull`, `onErrorRetry`) rather than left claiming a
 * feature. Adding one back means adding the surface it opens.
 *
 * @param hideThinking when true the thinking blocks are hidden entirely
 *   (pi's `hideThinkingBlock`).
 * @param thinkingDefaultExpanded collapsed by default, per spec principle 4.
 * @param toolsDefaultExpanded maps to pi's `app.tools.expand`.
 * @param onBranchClick the branch-summary row's tap; the host opens the session
 *   tree (the app's nearest equivalent of pi's branch jump).
 * @param onModelClick the model-change row's tap; the host opens the model
 *   picker sheet.
 */
@Composable
fun BlockRenderer(
    item: TranscriptItem,
    modifier: Modifier = Modifier,
    hideThinking: Boolean = false,
    thinkingDefaultExpanded: Boolean = false,
    toolsDefaultExpanded: Boolean = false,
    onBranchClick: ((BranchSummary) -> Unit)? = null,
    onModelClick: (() -> Unit)? = null,
) {
    when (item) {
        is UserMessage -> UserMessageBlock(item, modifier)

        is AssistantText -> AssistantTextBlock(item, modifier)

        is ThinkingBlock -> if (!hideThinking) {
            ThinkingBlockBlock(item, modifier, thinkingDefaultExpanded)
        }

        is ToolCall -> ToolCallBlock(item, modifier, toolsDefaultExpanded)

        is ToolDiff -> DiffBlock(item, modifier, toolsDefaultExpanded)

        is CompactionMarker -> CompactionBlock(item, modifier)

        is BranchSummary -> BranchSummaryBlock(item, modifier, onBranchClick)

        is HookMessage -> HookMessageBlock(item, modifier)

        is ModelChange -> ModelChangeBlock(item, modifier, onModelClick)

        is SkillInvocation -> SkillInvocationBlock(item, modifier)

        is SystemPrompt -> SystemPromptBlock(item, modifier)

        is ErrorText -> ErrorBlock(item, modifier)

        is DateSeparator -> DateSeparatorBlock(item, modifier)

        is Notice -> NoticeBlock(item, modifier)
    }
}
