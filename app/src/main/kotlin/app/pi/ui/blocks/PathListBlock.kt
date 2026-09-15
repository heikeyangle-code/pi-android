package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.pi.ui.theme.numeric

/**
 * `find` — pi's renderer for the filename search (`core/tools/renderers/find.ts`).
 *
 * pi's call line is `find <pattern> in <path> (limit N)` (`:17-32`) and its result is the
 * paths `fd` returned, relativised to the search root (`core/tools/find.ts:268-273`),
 * printed one per line and cut at twenty with `... (N more lines, to expand)` (`:42-53`);
 * the result limit and byte truncation get a warning line (`:55-62`).
 *
 * **This block groups the paths by directory.** pi prints one path per row; grouping is the
 * app's arrangement of the same rows, and the argument for it is the screen: twenty full
 * paths (`app/src/main/kotlin/app/pi/ui/blocks/Foo.kt` …) spend most of their width repeating
 * a prefix, so the heading carries the directory once and each row shows the file name. pi's
 * `fd` output carries no file/directory marker, so no row claims to be one.
 */
@Composable
internal fun FindBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) = PathListBlock(
    item = item,
    title = "find",
    subject = findSubject(item.args),
    emptyText = "没有找到匹配的文件",
    preview = FIND_PREVIEW_ENTRIES,
    parse = { ToolOutputParse.findBody(it) },
    modifier = modifier,
    defaultExpanded = defaultExpanded,
    firstOfRun = firstOfRun,
    lastOfRun = lastOfRun,
)

/**
 * `ls` — pi's renderer for the directory listing (`core/tools/renderers/ls.ts`).
 *
 * pi's call line is `ls <path> (limit N)` (`:17-25`), with `"."` for an absent path; the
 * result is the entries `readdir` returned, sorted case-insensitively, with a `"/"` suffix on
 * directories and dotfiles included (`core/tools/ls.ts:108-129`), cut at twenty with
 * `... (N more lines, to expand)` (`renderers/ls.ts:37-45`).
 *
 * **This block splits the entries by that `"/"` suffix** into directories and files. The
 * suffix is pi's own
 * (`if (entryStat.isDirectory()) suffix = "/"`, `core/tools/ls.ts:124`), so the split is a
 * reading of pi's data, not a second lookup — which is why the directory glyph is not
 * invented from anything else.
 */
@Composable
internal fun LsBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) = PathListBlock(
    item = item,
    title = "ls",
    subject = lsSubject(item.args),
    emptyText = "空目录",
    preview = LS_PREVIEW_ENTRIES,
    parse = { ToolOutputParse.lsBody(it) },
    modifier = modifier,
    defaultExpanded = defaultExpanded,
    firstOfRun = firstOfRun,
    lastOfRun = lastOfRun,
)

/**
 * The card `find` and `ls` share: pi gives them separate renderers
 * (`renderers/find.ts:66-77`, `renderers/ls.ts:59-70`) whose bodies differ only in what the
 * rows mean — both print one path per line, both cap the list, both report a limit in the
 * same bracket sentence (`core/tools/find.ts:292`, `ls.ts:155`) — so the app draws one card
 * and passes the four things that differ.
 *
 * @param emptyText pi's own empty answer, in this app's words: "No files found matching
 *   pattern" (`find.ts:261`) or "(empty directory)" (`ls.ts:135`).
 * @param preview entries painted before 「展开全部」; pi's number for that tool.
 * @param parse the one thing that is not shared: which tool's parser reads the result
 *   (`ToolOutputParse.findBody` / `lsBody`). A lambda rather than a flag, so the card cannot
 *   be handed the other tool's rows.
 */
@Composable
private fun PathListBlock(
    item: ToolCall,
    title: String,
    subject: String,
    emptyText: String,
    preview: Int,
    parse: (String) -> PathBody?,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var fullOutput by remember { mutableStateOf(false) }
    val body = remember(item.output) { parse(item.output) }
    if (body == null) {
        ToolCallBlock(item, modifier, defaultExpanded, firstOfRun, lastOfRun)
        return
    }
    val command = remember(item.args) { toolCommandText(item.args) }
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item.details, item.output) }
    val notice = remember(item.details, fullOutputPath) {
        truncationNotice(fullOutputPath, truncationOf(item.details))
    }
    val plan = remember(body, expanded, fullOutput) {
        if (!expanded) {
            emptyList()
        } else {
            capPathGroups(body.groups, if (fullOutput) TOOL_LIST_MAX_ENTRIES else preview)
        }
    }
    val shown = remember(plan) { countEntries(plan) }
    val omitted = (body.entryCount - shown).coerceAtLeast(0)
    val hasBody = body.groups.isNotEmpty() || body.notice != null || body.empty
    val footer = remember(item.output, item.exitCode, item.elapsedMs, item.outputTruncated, state, body.entryCount) {
        if (state == ToolState.Rejected) {
            toolRejectedFooter()
        } else {
            val parts = mutableListOf(toolStateLabel(state))
            if (body.entryCount > 0) parts += "${body.entryCount} 项"
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
                    title = title,
                    subject = subject,
                    // v2's `find` / `ls` cards read out as a *count* at the header's
                    // right end (`right="12 项"`, `right="0 项"`), not as a duration —
                    // the tool's own `limit` answer is what those two cards say first.
                    // It is the same number the footer prints, from the same parse.
                    right = if (state == ToolState.Rejected) null else "${body.entryCount} 项",
                    expanded = expanded,
                    expandable = hasBody,
                )
                if (expanded) {
                    when {
                        body.empty -> Text(text = emptyText, style = PiTheme.text.meta, color = palette.muted)
                        plan.isEmpty() -> Unit
                        else -> Column(modifier = Modifier.padding(top = PiSpacing.tiny)) {
                            plan.forEach { group ->
                                PathGroupHeading(group)
                                group.entries.forEach { entry ->
                                    // pi marks a directory by suffixing it with "/"
                                    // (`core/tools/ls.ts:124`); the heading already says
                                    // "目录", and the row keeps pi's own marker because that
                                    // is the character a user copies out of the list.
                                    MonoText(
                                        text = if (entry.kind == PathKind.Directory) entry.name + "/" else entry.name,
                                        color = palette.bodyOnTool,
                                    )
                                }
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
                            text = "展开全部（还有 $omitted 项）",
                            onClick = { fullOutput = true },
                        )
                    } else if (omitted > 0) {
                        Text(
                            text = "还有 $omitted 项未显示",
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
 * One group's heading: the directory for a `find` (pi's own `"."` fallback from
 * `renderers/ls.ts:19` for the search root itself) or pi's entry kind for an `ls`, each with
 * how many entries sit under it.
 *
 * The count is a quantity rather than a state (`06 §4`), so it is numeric and muted — the
 * same treatment the `grep` card's group headings carry — and it keeps the machine face so
 * the numbers align.
 */
@Composable
private fun PathGroupHeading(group: PathGroup) {
    val palette = PiTheme.palette
    Row(modifier = Modifier.padding(top = PiSpacing.gutter)) {
        MonoText(
            text = group.label?.takeIf { it.isNotEmpty() } ?: ".",
            // `text`: this is a body row naming what the group holds, not the tool's
            // own name (`toolTitle`'s pi meaning), and it keeps the grep card's
            // sibling heading (`GrepBlock.kt`'s `GrepGroupHeading`) on one colour.
            color = palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(text = "${group.entries.size} 项", style = PiTheme.text.numeric, color = palette.muted, maxLines = 1)
    }
}

/** Entries the `find` card paints before 「展开全部」 (pi's twenty, `renderers/find.ts:46`). */
private const val FIND_PREVIEW_ENTRIES = 20

/** Entries the `ls` card paints before 「展开全部」 (pi's twenty, `renderers/ls.ts:39`). */
private const val LS_PREVIEW_ENTRIES = 20
