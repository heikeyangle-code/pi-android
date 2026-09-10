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
                            color = palette.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ExpandLabel(expanded)
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
