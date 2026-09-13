package app.pi.ui.blocks

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `write` — pi's renderer for the file writer (`core/tools/renderers/write.ts:145-180`).
 *
 * pi draws the *content it was asked to write*, taken from the arguments, not from the
 * result: `formatWriteCall` prints `write <path>` and then `args.content`, highlighted by the
 * path's language and cut at ten lines with `... (N more lines, N total, to expand)`
 * (`:96-127`). The *result* half prints nothing at all on success (`:128-133`: `if
 * (!result.isError) return undefined`) — the successful text `Successfully wrote to <path>`
 * (`core/tools/write.ts:86`) never reaches the screen, and this block does not print it
 * either. On failure pi prints the error text in the error colour (`:134-142`).
 *
 * The arguments stream, so the body can be absent for a moment; that is the same state pi's
 * renderer treats as `[invalid content arg - expected string]` (`:108-109`), and a settled
 * call in that state says so in this app's words.
 *
 * Everything is parsed once per `args` — a tool row recomposes every 200 ms while anything
 * streams (`rpc/.../Transcript.kt:618`).
 */
@Composable
internal fun WriteBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var fullOutput by remember { mutableStateOf(false) }
    val body = remember(item.args) { ToolOutputParse.writeBody(item.args) }
    val command = remember(item.args) { toolCommandText(item.args) }
    val pending = item.status == ToolStatus.Pending
    val shown = if (fullOutput) body.lines else body.lines.take(WRITE_PREVIEW_LINES)
    val hidden = (body.totalLines - shown.size).coerceAtLeast(0)
    val failed = item.status == ToolStatus.Error
    val hasBody = body.lines.isNotEmpty() || failed
    val footer = remember(item.output, item.exitCode, item.elapsedMs, item.outputTruncated, item.status) {
        toolFooterText(item, toolStatusLabel(item.status), body.totalLines)
    }
    ToolActionMenu(command, item.output, null) {
        BlockColumn(modifier) {
            ToolCard(item, expanded, { expanded = !expanded }) {
                ToolHeader(
                    title = "write",
                    subject = body.path.ifEmpty { "文件" },
                    status = item.status,
                )
                if (expanded) {
                    if (body.lines.isEmpty()) {
                        if (failed) {
                            // pi's result half on an error (`renderers/write.ts:134-142`).
                            Text(
                                text = item.output,
                                style = PiTheme.text.meta,
                                color = palette.error,
                            )
                        } else if (!pending) {
                            Text(
                                text = TOOL_INVALID_CONTENT,
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )
                        }
                    } else {
                        SourceLines(
                            lines = shown,
                            startLine = body.startLine,
                            path = body.path,
                            color = palette.bodyOnTool,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                        if (hidden > 0 && !fullOutput) {
                            ExpandAllLabel(
                                text = "展开全部（还有 $hidden 行，共 ${body.totalLines} 行）",
                                onClick = { fullOutput = true },
                            )
                        } else if (hidden > 0) {
                            Text(
                                text = "还有 $hidden 行未显示",
                                style = PiTheme.text.meta,
                                color = palette.muted,
                                modifier = Modifier.padding(top = PiSpacing.tiny),
                            )
                        }
                        if (failed) {
                            Text(
                                text = item.output,
                                style = PiTheme.text.meta,
                                color = palette.error,
                                modifier = Modifier.padding(top = PiSpacing.tiny),
                            )
                        }
                    }
                    if (body.scanCapped) {
                        Text(text = TOOL_SCAN_CAPPED_HINT, style = PiTheme.text.meta, color = palette.muted)
                    }
                }
                ToolFooter(text = footer, expanded = expanded, expandable = hasBody)
            }
        }
    }
}

/** How long a `write` body may be when the card is merely expanded (pi's ten, `write.ts:117`). */
private const val WRITE_PREVIEW_LINES = 10
