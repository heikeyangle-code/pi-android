package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.rpc.CompactionMarker
import app.pi.ui.components.PiBilledCostLine
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `compaction` (docs/pi-android-ui-spec.md §7.4): a full-width `customMessageBg` card whose
 * label row states what happened. The summary is collapsed to a two-line plain-text preview
 * and expands to the full markdown document in place — the split pi's own
 * `compaction-summary-message.ts:40-57` makes, for the same reason.
 *
 * The card shape (and its `customMessageBg` fill) is pi's own
 * (`compaction-summary-message.js:13`), shared with the app's other three
 * `customMessageBg` messages; v2's prototype drew this one as a chip between two hairlines
 * on the canvas, which is the shape this block used to have — see the note at the card below.
 *
 * [showBilledCost] is pi's `showCacheMissNotices` (`core/settings-manager.ts:120`,
 * default `false`). pi prints a separate `compaction_cost` row for the
 * summarization's own usage when it is on
 * (`modes/interactive/interactive-mode.ts:3430-3436` live, `:3791-3793` on
 * replay); the row is rendered here instead of as its own transcript item
 * because the app keeps that usage on [CompactionMarker.usage] rather than as a
 * separate entry. The text is pi's, verbatim — see [PiBilledCostLine].
 */
@Composable
fun CompactionBlock(
    item: CompactionMarker,
    modifier: Modifier = Modifier,
    showBilledCost: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val label = when (item.status) {
        CompactionMarker.Status.Running -> "正在压缩上下文…"
        CompactionMarker.Status.Done -> "上下文已压缩"
        CompactionMarker.Status.Aborted -> "压缩已中止"
        CompactionMarker.Status.Failed -> "压缩失败"
    }
    val tokensFreed = item.tokensFreed
    val caption = when {
        item.status == CompactionMarker.Status.Failed && !item.errorMessage.isNullOrBlank() ->
            item.errorMessage.orEmpty()
        item.status == CompactionMarker.Status.Running ->
            "pi 正在总结更早的对话" + (item.reason?.let { " · $it" } ?: "")
        tokensFreed != null -> "释放 ${formatTokens(tokensFreed)} tokens"
        else -> item.reason?.let { "原因：$it" } ?: ""
    }

    // F28: the whole block is the content region that toggles (pi's rule).
    //
    // It is also a **card now**, because pi's is: the compaction component's constructor paints
    // the whole box with `customMessageBg` —
    //   `super(1, 1, (t) => theme.bg("customMessageBg", t))`
    // (`modes/interactive/components/compaction-summary-message.js:13`) — full width, not a
    // chip. This block was the only one of the four `customMessageBg` messages that was not a
    // card (a chip between two hairlines drawn on the canvas), which is where the "5.6 pp of
    // colour" the area audit measured had gone. The two hairlines and the chip's own `Surface`
    // go with it: a chip of the card's own colour inside the card is invisible, and a hairline
    // across a card cuts it in half.
    BlockColumn(modifier) {
        BlockCard(
            color = palette.customMessageBg,
            // The same 35 % `customMessageLabel` frame the three sibling cards carry
            // (`ui/blocks/BranchSummaryBlock.kt`, `SkillInvocationBlock.kt`, `HookMessageBlock.kt`).
            // pi's own box declares no border (`super(1, 1, …)` sets a background only), so this
            // is the family's convention in this app — applied for the same reason the other
            // three have it, so the four cards are one component.
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
            modifier = Modifier.toggleContent(expanded, { expanded = !expanded }),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentStripe(palette.customMessageLabel, PiSpacing.accentStripe)
                Spacer(Modifier.width(PiSpacing.inner))
                Text(
                    text = label,
                    // The custom card's label slot is the machine face — `06 §3` 构件 9
                    //「左 3px customLabel 条 + **等宽标签**」, which is the rule the other three
                    // cards' labels already follow (`BranchSummaryBlock.kt`, `SkillInvocationBlock.kt`,
                    // `HookMessageBlock.kt`). pi's own label is `customMessageLabel` + bold.
                    style = PiTheme.text.monoSmall,
                    color = palette.customMessageLabel,
                )
            }
            if (caption.isNotEmpty()) {
                Text(
                    // Left-aligned: it used to be centred under the chip, and with the chip gone
                    // there is nothing left in the card for it to be centred against (pi's own
                    // collapsed line sits under the label, at the card's left edge).
                    text = caption,
                    modifier = Modifier.fillMaxWidth(),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            if (item.summary.isNotEmpty()) {
                // Collapsed: plain text cut to a preview. Expanded: markdown.
                //
                // pi makes the same split for the same reason: its collapsed branch
                // is a plain `Text` carrying the expand hint
                // (`packages/coding-agent/src/modes/interactive/components/compaction-summary-message.ts:47-57`)
                // and only the expanded branch builds a `Markdown`
                // (`compaction-summary-message.ts:40-46`). The renderer has no
                // `maxLines`, so the collapsed branch staying a `Text` is exactly
                // what keeps the preview alive.
                // Both branches are one selection scope: the summary is model prose the
                // user may want to quote. It sits outside the `if` because the expanded
                // branch paints several `Text` nodes through the markdown renderer (a
                // per-paragraph scope could not be dragged across), while the collapsed
                // branch is a single [ProseText] — one scope covers both shapes, and
                // neither branch contains another one, so nothing is nested.
                SelectableContent {
                    if (expanded) {
                        PiMarkdownText(
                            markdown = item.summary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = PiSpacing.tiny),
                            // pi's `Markdown` base colour for this card is `customMessageText`
                            // (`compaction-summary-message.js:35`), the same token its three
                            // siblings use.
                            textColor = palette.customMessageText,
                        )
                    } else {
                        ProseText(
                            text = item.summary,
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.customMessageText,
                            maxLines = COLLAPSED_SUMMARY_LINES,
                        )
                    }
                }
            }
            // F18: the summarization's own billing, gated by pi's switch.
            if (showBilledCost) {
                PiBilledCostLine(
                    label = "Compaction",
                    usage = item.usage,
                    modifier = Modifier.fillMaxWidth().padding(top = PiSpacing.tiny),
                )
            }
        }
    }
}

/**
 * How much of the summary the collapsed block previews.
 *
 * pi's collapsed compaction line is one line plus the expand hint
 * (`compaction-summary-message.ts:47-57`); on a phone the summary itself is
 * worth a two-line peek, and two lines is what this block's neighbour
 * (`BranchSummaryBlock.kt`) uses for the same affordance.
 */
private const val COLLAPSED_SUMMARY_LINES = 2
