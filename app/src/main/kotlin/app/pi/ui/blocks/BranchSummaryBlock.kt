package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.BranchSummary
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme

/**
 * `branch-summary` (docs/pi-android-ui-spec.md §7.4): a `customMessageBg` card
 * labelled 分支摘要 with the branch id. Clicking either jumps to that branch
 * (when the host provides [onClick]) or expands the summary in place.
 *
 * The summary is a two-line plain-text preview while collapsed and markdown once
 * expanded — the same split pi's `branch-summary-message.ts:41-56` makes.
 */
@Composable
fun BranchSummaryBlock(
    item: BranchSummary,
    modifier: Modifier = Modifier,
    onClick: ((BranchSummary) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }

    BlockColumn(modifier) {
        BlockCard(
            color = palette.customMessageBg,
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (onClick != null) "跳转分支" else "展开摘要") {
                        if (onClick != null) onClick(item) else expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentStripe(palette.customMessageLabel, 20.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "分支摘要",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.customMessageLabel,
                    )
                    val branchId = item.branchId
                    if (!branchId.isNullOrBlank()) {
                        Text(
                            text = branchId,
                            style = PiTheme.text.monoSmall,
                            // F12 (`docs/rendering-review.md`): `dim` is 2.89:1 on
                            // this card's `customMessageBg`, under spec §9's 3:1
                            // metadata floor. `muted` is 4.20:1 on a card surface,
                            // and the token stays pi's own.
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // F19 (`docs/rendering-review.md`): now that the host supplies
                // `onClick`, the row itself jumps to the session tree (spec §7.4
                // branch-summary 点击跳转), so the label has to carry the expand —
                // otherwise the summary body would become unreachable. A nested
                // `clickable` consumes the tap before the row's.
                ExpandLabel(
                    expanded = expanded,
                    modifier = Modifier.clickable(
                        onClickLabel = if (expanded) "收起摘要" else "展开摘要",
                    ) { expanded = !expanded },
                )
            }
            if (expanded) {
                // Expanded: markdown. pi's expanded branch is a `Markdown` over
                // the summary (`packages/coding-agent/src/modes/interactive/components/branch-summary-message.ts:41-45`),
                // so headings, lists and fences in a model-written summary read
                // as structure instead of as source.
                PiMarkdownText(
                    markdown = item.summary.ifEmpty { "（无摘要）" },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Collapsed: plain text cut to the same preview this block has
                // always shown. pi's collapsed branch is likewise plain
                // (`branch-summary-message.ts:46-56`), and a markdown renderer
                // has no `maxLines` to carry the preview with.
                ProseText(
                    text = item.summary.ifEmpty { "（无摘要）" },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.customMessageText,
                    maxLines = COLLAPSED_SUMMARY_LINES,
                )
            }
        }
    }
}

/**
 * The collapsed line count, unchanged from this block's original preview. pi has
 * no numeric equivalent here — a terminal line is not a wrapped phone line — so
 * the existing value is kept rather than invented.
 */
private const val COLLAPSED_SUMMARY_LINES = 2
