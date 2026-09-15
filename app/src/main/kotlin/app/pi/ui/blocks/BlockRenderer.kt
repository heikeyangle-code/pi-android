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
import app.pi.rpc.ThinkingBlock
import app.pi.rpc.ToolCall
import app.pi.rpc.PiImage
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
 * **[ToolCall] is two levels deep** (P2-1, `docs/capability-gap.md` §4.9). pi's
 * `tool-execution` component renders a tool call through a per-tool renderer pair
 * (`packages/coding-agent/src/core/tools/renderers/index.ts:34-44`), and this function
 * makes the same dispatch on `item.toolName`: the eight built-ins get their own block, a
 * result carrying images and every other tool keep [ToolCallBlock]. The fallback is not a
 * gap: pi's own `withBuiltInRenderers` (`:51-63`) hands a tool with no built-in renderer
 * the definition's own renderer or nothing at all.
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
 * unreachable while the blocks kept rendering labels for them. One has a target in
 * the app and is supplied (`onBranchClick`); the others had no target at all — no
 * image viewer, no full-screen diff route, no retry action, and (since the model
 * row stopped rendering, below) no model row to tap — so their parameters and the
 * labels they gated were deleted (`onImageClick`, `onDiffOpenFull`, `onErrorRetry`,
 * `onModelClick`) rather than left claiming a feature. Adding one back means adding
 * the surface it opens.
 *
 * @param hideThinking when true the thinking blocks are hidden entirely
 *   (pi's `hideThinkingBlock`).
 * @param thinkingDefaultExpanded collapsed by default, per spec principle 4.
 * @param toolsDefaultExpanded maps to pi's `app.tools.expand`.
 * @param onBranchClick the branch-summary row's tap; the host opens the session
 *   tree (the app's nearest equivalent of pi's branch jump).
 * @param showBilledCost pi's `showCacheMissNotices` (`core/settings-manager.ts:120`,
 *   default `false`). On, the compaction and branch-summary blocks print the
 *   summarization's own usage exactly as pi does
 *   (`modes/interactive/interactive-mode.ts:3802-3812`); off, they print nothing
 *   extra — F18 in `docs/rendering-review.md`.
 * @param firstOfRun true when the row before this one is **not** a `ToolCall` /
 *   `ToolDiff`; [lastOfRun] is the same for the row after it. Only the tool and
 *   diff blocks consume them, and only to place the execution rail's two ends: a
 *   run is a property of *consecutive transcript items*, so the list that owns the
 *   order is the only place that can compute them (`06 §2` 执行轨道「竖线上下各缩进
 *   16」, and `ToolRail.kt`'s KDoc for the whole argument). Both default to `true`,
 *   which is the shape of a one-card run: a caller that passes nothing gets v2's
 *   clean inset rather than a stub and a tail.
 */
@Composable
fun BlockRenderer(
    item: TranscriptItem,
    modifier: Modifier = Modifier,
    hideThinking: Boolean = false,
    thinkingDefaultExpanded: Boolean = false,
    toolsDefaultExpanded: Boolean = false,
    showBilledCost: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
    onBranchClick: ((BranchSummary) -> Unit)? = null,
    /** §4.8: 编辑并从此分叉 — pi forks a session from a user message (`fork`, rpc-types.ts:62). */
    onForkFromMessage: ((String) -> Unit)? = null,
    /**
     * A tap on any image in the transcript — the user's attachment, a `read` of a
     * picture, or a tool's screenshot. It is the target F19
     * (`docs/rendering-review.md`) said was missing when it deleted the old
     * `onImageClick`; now that [PiImageViewer] exists, the callback has somewhere to
     * go and is supplied by `ChatScreen`. Null keeps every image inert.
     */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    when (item) {
        is UserMessage -> UserMessageBlock(item, modifier, onForkFromMessage, onImageClick)

        is AssistantText -> AssistantTextBlock(item, modifier)

        is ThinkingBlock -> if (!hideThinking) {
            ThinkingBlockBlock(item, modifier, thinkingDefaultExpanded)
        }

        // P2-1 (`docs/capability-gap.md` §4.9): pi gives every built-in tool its own
        // renderer pair (`core/tools/renderers/index.ts:34-44` — read, bash, powershell,
        // edit, write, grep, find, ls), so each one gets its own block here rather than one
        // generic card. A result that came back with images keeps the generic card, because
        // that is where pi's `content[type=image]` blocks are painted (F16 in
        // `docs/rendering-review.md`); so does every other tool, which is exactly the set pi
        // itself has no built-in renderer for — its own fallback is
        // `withBuiltInRenderers` (`index.ts:51-63`).
        is ToolCall -> if (item.images.isNotEmpty()) {
            // The one branch whose card can carry images, so the one that needs the
            // viewer: `onImageClick` reaches the grid from `ToolCallBlock`.
            ToolCallBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun, onImageClick)
        } else {
            when (item.toolName) {
                "read" -> ReadBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                "write" -> WriteBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                "edit" -> EditBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                "grep" -> GrepBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                "find" -> FindBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                "ls" -> LsBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                // pi's two shells share one renderer factory (`index.ts:35-36`): the prompt
                // is the only difference between them, so they share one block here too.
                "bash", "powershell" -> ShellBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
                else -> ToolCallBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)
            }
        }

        is ToolDiff -> DiffBlock(item, modifier, toolsDefaultExpanded, firstOfRun, lastOfRun)

        is CompactionMarker -> CompactionBlock(item, modifier, showBilledCost)

        is BranchSummary -> BranchSummaryBlock(item, modifier, onBranchClick, showBilledCost)

        is HookMessage -> HookMessageBlock(item, modifier)

        // **pi does not draw `model_change` in the transcript, so neither does this.**
        // The entry exists in the session file (`core/session-manager.ts:64`, written
        // at `:1099`) and the reducer keeps projecting it, but pi's own view of a
        // model switch is a *transient* one: its `showStatus("Model: <id>")` line
        // (`modes/interactive/interactive-mode.ts:4850`, `:4996`), which appends one
        // `theme.fg("dim", …)` line at the end of the chat container and **replaces
        // its text** on the next status update (`:3537-3553`) — it is not a row of the
        // transcript, and it is never persisted. The only surface that shows the entry
        // as a durable row is pi's session-tree selector
        // (`components/tree-selector.ts:600` labels it `[model: <id>]`, `:830`).
        //
        // The row is deliberately **not** replaced with a notice: pi's sentence is
        // English, momentary, and belongs to a status area this app does not have, so
        // inventing 「模型已切换」 here would be new copy claiming to be pi's. Where the
        // current model *is* shown is the AppBar's chip, which reads the same
        // `ModelChange.modelId` this row used to (`screens/ChatScreen.kt`); the item
        // stays in the list because it is that chip's fallback and one of the search
        // and export inputs.
        //
        // v2's 构件 10 (`06 §3`) draws this as a row; the user asked for it to go
        // (design decision D19), and pi agrees with the user.
        is ModelChange -> Unit

        is SkillInvocation -> SkillInvocationBlock(item, modifier)

        is ErrorText -> ErrorBlock(item, modifier)

        is DateSeparator -> DateSeparatorBlock(item, modifier)

        is Notice -> NoticeBlock(item, modifier)
    }
}
