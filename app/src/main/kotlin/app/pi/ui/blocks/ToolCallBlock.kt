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
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // F17 (`docs/rendering-review.md`): the card's own toggle and the spec's
    // 「展开全部」 are two different questions — the first asks "show the output at
    // all", the second "stop cutting it at 200 lines". Collapsing the card resets
    // nothing: the user asked to see the tail once and it stays visible.
    var fullOutput by remember { mutableStateOf(false) }
    // F31 (`docs/rendering-review.md`): these are O(output) scans on a row that
    // recomposes for every streamed chunk (F8's 200 ms throttle bounds how often),
    // so they are keyed on the value they scan rather than re-run per composition.
    val outputLineCount = remember(item.output) { lineCount(item.output) }
    val outputOversize = remember(item.output) { item.output.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES }
    // F17 + spec §4.2: 默认渲染前 200 行 + 「展开全部」; 单块 >200 KB 直接给「前往工作区」.
    val previewText = remember(item.output, expanded, fullOutput, outputOversize) {
        when {
            !expanded || outputOversize -> ""
            fullOutput -> item.output
            else -> headLines(item.output, COLLAPSED_OUTPUT_LINES)
        }
    }
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
    // F31 (`docs/rendering-review.md`): the footer's parts list + `joinToString`
    // used to be rebuilt on every composition, and its `lineCount` scanned the
    // whole output each time. It depends only on the row's scalar fields, so it is
    // built once per output change. `outputLineCount` is deliberately not a key —
    // it is derived from `item.output`, which is.
    val footer = remember(
        item.output,
        item.exitCode,
        item.elapsedMs,
        item.outputTruncated,
        statusLabel,
    ) {
        toolFooter(item, statusLabel, outputLineCount)
    }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // §4.8: 工具卡长按 → 复制命令 / 复制输出. The command is the tool's own argument
    // (`command` for bash, `file_path`/`path` for the file tools); a tool whose
    // arguments are neither gets no command entry rather than a wrong one.
    val commandText = remember(item.args) {
        listOf("command", "file_path", "path", "pattern")
            .firstNotNullOfOrNull { key ->
                (item.args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            }
    }
    BlockColumn(modifier) {
        BlockActionMenu(
            actions = buildList {
                if (commandText != null) {
                    add(BlockAction("复制命令") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(commandText))
                    })
                }
                if (item.output.isNotEmpty()) {
                    add(BlockAction("复制输出") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.output))
                    })
                }
            },
        ) {
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
                if (outputOversize) {
                    // Spec §4.2: 单块 >200 KB 直接给「前往工作区查看完整日志」. The label
                    // is the spec's own; the app has no log view to route to (the
                    // workspace is the terminal destination the other rows name).
                    Text(
                        text = "输出超过 200 KB，请前往工作区查看完整日志",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                } else {
                    MonoText(
                        text = previewText,
                        color = palette.toolOutput,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (!fullOutput && outputLineCount > COLLAPSED_OUTPUT_LINES) {
                        Text(
                            text = "展开全部（共 $outputLineCount 行）",
                            modifier = Modifier
                                .clickable(onClickLabel = "展开全部输出") { fullOutput = true }
                                .padding(vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = footer,
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
}

/**
 * The card's footer: status, exit code, duration and line count.
 *
 * [lines] is passed in rather than recomputed (F31 in `docs/rendering-review.md`):
 * the caller already holds a remembered [lineCount] for the same output, and this
 * function used to scan the whole (possibly megabyte) string on every
 * composition.
 */
private fun toolFooter(item: ToolCall, statusLabel: String, lines: Int): String {
    val parts = mutableListOf(statusLabel)
    item.exitCode?.let { parts += "退出码 $it" }
    item.elapsedMs?.let { parts += formatDuration(it) }
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/**
 * Spec §4.2's collapsed budget for a long tool result, and its hard ceiling for
 * the inline surface. F17 (`docs/rendering-review.md`): this used to be 400 with
 * no path to the remainder — a `bash` log past the cap was silently incomplete.
 * The card's toggle now shows these lines and 「展开全部」 shows the rest; past
 * [MAX_OUTPUT_BYTES] the block points at the workspace instead of painting it.
 */
private const val COLLAPSED_OUTPUT_LINES = 200
private const val MAX_OUTPUT_BYTES = 200 * 1024
