package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.pi.rpc.ToolCall
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.numeric

/**
 * `grep` — pi's renderer for the content search (`core/tools/renderers/grep.ts`).
 *
 * pi's call line is `grep /pattern/ in <path> (glob) limit N` (`:17-35`) and its result is
 * the tool's own rows, printed in order and cut at fifteen lines with
 * `... (N more lines, to expand)` (`:36-56`); truncation and the match limit are reported in
 * a warning line (`:58-67`). Every row keeps its line number and file, because the tool
 * prints them (`core/tools/grep.ts:211`: `${relativePath}:${line}: ${text}`, and
 * `${relativePath}-${line}- ${text}` for the context lines a `context` argument asks for,
 * `:212`).
 *
 * **This block groups those rows by file**, which pi's terminal renderer does not do — it
 * prints one line per match. Grouping is the app's arrangement of the same rows, and the
 * reason is the phone: a hundred matches over a dozen files read as a list of files with
 * counts, not as a hundred undifferentiated lines. No row is dropped, reordered or invented;
 * the group heading is the path pi printed and the number beside it is a count of pi's rows.
 * A row that matches neither shape is kept as-is, and a result with no recognised row at all
 * answers `null` so the caller draws the generic card instead.
 *
 * Parsed once per result (`remember`), because a tool row recomposes every 200 ms while
 * anything streams (`rpc/.../Transcript.kt:618`); the painted list is capped at
 * [TOOL_LIST_MAX_ENTRIES] and says how many matches it left out.
 */
@Composable
internal fun GrepBlock(
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
    val body = remember(item.output) { ToolOutputParse.grepBody(item.output) }
    if (body == null) {
        ToolCallBlock(item, modifier, defaultExpanded, firstOfRun, lastOfRun)
        return
    }
    val subject = remember(item.args) { grepSubject(item.args) }
    val command = remember(item.args) { toolCommandText(item.args) }
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item.details, item.output) }
    val notice = remember(item.details, fullOutputPath) {
        truncationNotice(fullOutputPath, truncationOf(item.details))
    }
    val plan = remember(body, expanded, fullOutput) {
        if (!expanded) {
            emptyList()
        } else {
            capGrepGroups(body.groups, if (fullOutput) TOOL_LIST_MAX_ENTRIES else GREP_PREVIEW_MATCHES)
        }
    }
    val shownMatches = remember(plan) { countMatches(plan) }
    val omitted = (body.matchCount - shownMatches).coerceAtLeast(0)
    val hasBody = body.groups.isNotEmpty() || body.notice != null || body.empty
    val footer = remember(item.output, item.exitCode, item.elapsedMs, item.outputTruncated, state, body.matchCount) {
        if (state == ToolState.Rejected) {
            toolRejectedFooter()
        } else {
            val parts = mutableListOf(toolStateLabel(state))
            if (body.matchCount > 0) parts += "${body.matchCount} 处"
            // pi's own count of files, not the capped group list's size (which would undercount
            // a 500-file search as "200 个文件").
            if (body.fileCount > 1) parts += "${body.fileCount} 个文件"
            item.exitCode?.let { parts += "退出码 $it" }
            item.elapsedMs?.let { parts += formatDuration(it) }
            if (item.outputTruncated) parts += "已截断"
            parts.joinToString(" · ")
        }
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
                    title = "grep",
                    subject = subject,
                    expanded = expanded,
                    expandable = hasBody,
                )
                if (expanded) {
                    when {
                        body.empty -> Text(
                            text = "没有匹配",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )

                        plan.isEmpty() -> Unit
                        else -> Column(modifier = Modifier.padding(top = PiSpacing.tiny)) {
                            plan.forEach { group ->
                                GrepGroupHeading(group.path, group.matches.size)
                                group.matches.forEach { match -> GrepRow(match) }
                            }
                            body.raw.forEach { raw ->
                                MonoText(
                                    text = raw,
                                    color = palette.muted,
                                    modifier = Modifier.padding(top = PiSpacing.tiny),
                                )
                            }
                        }
                    }
                    if (omitted > 0 && !fullOutput) {
                        ExpandAllLabel(
                            text = "展开全部（还有 $omitted 处）",
                            onClick = { fullOutput = true },
                        )
                    } else if (omitted > 0) {
                        Text(
                            text = "还有 $omitted 处未显示",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                    if (body.scanCapped) {
                        Text(text = TOOL_SCAN_CAPPED_HINT, style = PiTheme.text.meta, color = palette.muted)
                    }
                    body.notice?.let { ToolNotice(text = it, copyOnTap = null) }
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

/**
 * One file's heading: the path pi printed, plus how many of its rows are below.
 *
 * The count is a **quantity, not a state** (`06 §4`: a number belongs in plain numeric
 * text, `theme/PiStateChip.kt`), so it is mono and muted — v2's own treatment
 * (`mono t12 c-muted`) — rather than a chip. It keeps the machine face so the counts
 * line up down the card and do not jitter as a live search grows.
 */
@Composable
private fun GrepGroupHeading(path: String, matches: Int) {
    val palette = PiTheme.palette
    Row(modifier = Modifier.padding(top = PiSpacing.gutter)) {
        MonoText(text = path, color = palette.toolTitle, modifier = Modifier.weight(1f), maxLines = 1)
        Text(text = "$matches 处", style = PiTheme.text.numeric, color = palette.muted, maxLines = 1)
    }
}

/**
 * One match: pi's line number in the gutter, its text beside it.
 *
 * `context` rows — the lines a `context` argument pulled in around a match — take pi's
 * `contextOnTool` colour, so a hit is still distinguishable from its surroundings without
 * relying on the glyph.
 */
@Composable
private fun GrepRow(match: GrepMatch) {
    val palette = PiTheme.palette
    Row {
        Text(
            text = match.line?.toString().orEmpty(),
            modifier = Modifier.width(PiSpacing.lineNumberColumn),
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        MonoText(
            text = match.text,
            color = if (match.context) palette.contextOnTool else palette.bodyOnTool,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How many matches the card paints before 「展开全部」 (pi's fifteen, `renderers/grep.ts:49`). */
private const val GREP_PREVIEW_MATCHES = 15
