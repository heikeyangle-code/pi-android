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
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `compaction` (docs/pi-android-ui-spec.md §7.4): a hairline across the stream
 * with a centred chip in `customMessageLabel`, stating what happened. The
 * summary is collapsed by default and can be expanded inline.
 */
@Composable
fun CompactionBlock(
    item: CompactionMarker,
    modifier: Modifier = Modifier,
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
        if (expanded && item.summary.isNotEmpty()) {
            ProseText(
                text = item.summary,
                color = palette.customMessageText,
            )
        }
    }
}
