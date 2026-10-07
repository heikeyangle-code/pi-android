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
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `read` — pi's renderer for the file reader (`core/tools/renderers/read.ts:150-175`).
 *
 * What pi draws, and where each part comes from here:
 *
 *  - the call line `read <path>:<start>-<end>` (`:34-37`; the range is
 *    `formatReadLineRange`, `:28-33`, built from `args.offset`/`args.limit`) —
 *    [ToolHeader] plus [readRange];
 *  - the body, coloured by the path's language (`:126-127`) with trailing blank lines
 *    trimmed (`:38-44`) — [SourceLines], whose line numbers come from that same `offset`;
 *  - pi caps a merely-expanded result at ten lines (`:129`: `maxLines = options.expanded ?
 *    lines.length : 10`) and its second expand shows the rest, with
 *    `... (N more lines, to expand)` in between (`:133-135`);
 *  - pi's own `[Showing lines X-Y of N …]` sentence, which the *tool* appends to its text
 *    (`core/tools/read.ts:64`/`:66`/`:73`), and the truncation report the renderer adds
 *    (`renderers/read.ts:137-146`). Both are printed, in pi's order: the sentence is body
 *    text in pi, the report is its warning line.
 *
 * The body is parsed once per (args, output) — a tool row recomposes on every 200 ms
 * publication while anything streams (`rpc/.../Transcript.kt:618`), so nothing in here may
 * scan a result (F31/F8 in `docs/rendering-review.md`).
 */
@Composable
internal fun ReadBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var fullOutput by remember { mutableStateOf(false) }
    val body = remember(item.args, item.output) { ToolOutputParse.readBody(item.args, item.output) }
    val range = remember(item.args) { readRange(item.args) }
    val command = remember(item.args) { toolCommandText(item.args) }
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item.details, item.output) }
    val notice = remember(item.details, fullOutputPath) {
        truncationNotice(fullOutputPath, truncationOf(item.details))
    }
    val pending = state == ToolState.Running
    val shown = if (fullOutput) body.lines else body.lines.take(READ_PREVIEW_LINES)
    val hidden = (body.totalLines - shown.size).coerceAtLeast(0)
    val hasBody = body.lines.isNotEmpty() || body.footer != null || body.scanCapped || notice != null
    val footer = remember(item.output, item.exitCode, item.elapsedMs, item.outputTruncated, state) {
        toolFooterText(item, state, body.totalLines)
    }
    ToolActionMenu(command, item.output, fullOutputPath) {
        BlockColumn(modifier) {
            ToolCard(
                item,
                expanded,
                { expanded = !expanded },
                firstOfRun = firstOfRun,
                lastOfRun = lastOfRun,
            ) {
                ToolHeader(
                    item = item,
                    title = "read",
                    // pi's `formatReadCall` (`renderers/read.js:24-28`):
                    //   `${fg("toolTitle", bold("read"))} ${pathDisplay}${formatReadLineRange(args, theme)}`
                    // `pathDisplay` is `renderToolPath` → `fg("accent", shortenPath(path))`
                    // (`core/tools/render-utils.js:57-63`), and the range is
                    // `fg("warning", ":1-50")` (`renderers/read.js:19-23`). Two tokens on one
                    // line, so the subject is the two runs pi emits — text and order untouched.
                    subject = buildList {
                        add(toolPathPart(body.path, fallback = "文件"))
                        if (range.isNotEmpty()) add(ToolCallPart(range, ToolCallToken.Warning))
                    },
                    expanded = expanded,
                    expandable = hasBody,
                )
                if (expanded) {
                    if (body.lines.isEmpty()) {
                        // A settled `read` with no lines is an empty file; while the call is
                        // still running the same state only means "nothing yet".
                        if (!pending) {
                            Text(text = "文件为空", style = PiTheme.text.meta, color = palette.muted)
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
                                text = "展开全部（还有 $hidden 行）",
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
                    }
                    // pi's own continuation sentence: the tool's text, so it is not coloured
                    // as a warning (pi prints it as body).
                    body.footer?.let {
                        Text(
                            text = it,
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                    if (body.scanCapped) {
                        Text(text = TOOL_SCAN_CAPPED_HINT, style = PiTheme.text.meta, color = palette.muted)
                    }
                    if (notice != null) ToolNotice(text = notice, copyOnTap = fullOutputPath)
                }
                ToolFooter(
                    text = footer,
                    state = state,
                    elapsedMs = item.elapsedMs,
                )
            }
        }
    }
}

/** How long a `read` body may be when the card is merely expanded (pi's ten, `read.ts:129`). */
private const val READ_PREVIEW_LINES = 10
