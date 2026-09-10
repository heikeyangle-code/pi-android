package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.ui.theme.PiTheme

/**
 * `tool-execution` (docs/pi-android-ui-spec.md §7.4). The container colour is
 * the status (`toolPendingBg` / `toolSuccessBg` / `toolErrorBg`), the title row
 * is the mono tool name plus a one-line argument summary, and the output is
 * machine text (mono, `toolOutput`). Status is a glyph plus Chinese wording, so
 * it survives colour blindness. The card toggles its output.
 */
@Composable
fun ToolCallBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val container = when (item.status) {
        ToolStatus.Pending -> palette.toolPendingBg
        ToolStatus.Success -> palette.toolSuccessBg
        ToolStatus.Error -> palette.toolErrorBg
    }
    val accent = when (item.status) {
        ToolStatus.Pending -> palette.warning
        ToolStatus.Success -> palette.success
        ToolStatus.Error -> palette.error
    }
    val statusLabel = when (item.status) {
        ToolStatus.Pending -> "运行中"
        ToolStatus.Success -> "成功"
        ToolStatus.Error -> "失败"
    }
    val statusGlyph = when (item.status) {
        ToolStatus.Pending -> "…"
        ToolStatus.Success -> "✓"
        ToolStatus.Error -> "✗"
    }

    BlockColumn(modifier) {
        BlockCard(
            color = container,
            modifier = Modifier.clickable(
                onClickLabel = if (expanded) "收起工具输出" else "展开工具输出",
            ) { expanded = !expanded },
            borderColor = accent.copy(alpha = 0.35f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.toolName.ifEmpty { "工具" },
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.argsSummary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.toolTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = statusGlyph, style = PiTheme.text.monoSmall, color = accent)
            }

            if (expanded && item.output.isNotEmpty()) {
                MonoText(
                    text = headLines(item.output, MAX_OUTPUT_LINES),
                    color = palette.toolOutput,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (lineCount(item.output) > MAX_OUTPUT_LINES) {
                    Text(
                        text = "仅显示前 $MAX_OUTPUT_LINES 行，完整输出请前往工作区",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = toolFooter(item, statusLabel),
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.output.isNotEmpty()) {
                    ExpandLabel(expanded, expandText = "输出", collapseText = "收起")
                }
            }
        }
    }
}

/** The card's footer: status, exit code, duration and line count. */
private fun toolFooter(item: ToolCall, statusLabel: String): String {
    val parts = mutableListOf(statusLabel)
    item.exitCode?.let { parts += "退出码 $it" }
    item.elapsedMs?.let { parts += formatDuration(it) }
    val lines = lineCount(item.output)
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

private const val MAX_OUTPUT_LINES = 400
