package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pi.rpc.CompactionMarker
import app.pi.ui.components.PiBilledCostLine
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `compaction` (docs/pi-android-ui-spec.md §7.4): a hairline across the stream
 * with a centred chip in `customMessageLabel`, stating what happened. The
 * summary is collapsed to a two-line plain-text preview and expands to the full
 * markdown document in place — the split pi's own
 * `compaction-summary-message.ts:40-57` makes, for the same reason.
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

    BlockColumn(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = palette.borderMuted.copy(alpha = 0.5f),
            )
            Surface(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clickable(onClickLabel = if (expanded) "收起摘要" else "展开摘要") {
                        expanded = !expanded
                    },
                shape = PiShapes.chip,
                color = palette.customMessageBg,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.customMessageLabel,
                )
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = palette.borderMuted.copy(alpha = 0.5f),
            )
        }
        if (caption.isNotEmpty()) {
            Text(
                text = caption,
                modifier = Modifier.fillMaxWidth(),
                style = PiTheme.text.meta,
                color = palette.muted,
                textAlign = TextAlign.Center,
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
            if (expanded) {
                PiMarkdownText(
                    markdown = item.summary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
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
        // F18: the summarization's own billing, gated by pi's switch.
        if (showBilledCost) {
            PiBilledCostLine(
                label = "Compaction",
                usage = item.usage,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
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
