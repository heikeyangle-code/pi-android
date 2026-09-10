package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.DiffHunk
import app.pi.rpc.DiffLine
import app.pi.rpc.DiffLineKind
import app.pi.rpc.ToolDiff
import app.pi.ui.theme.PiTheme

/**
 * `tool-diff` (docs/pi-android-ui-spec.md §7.4): path plus `+N −N` up top, then
 * the hunks. Every line carries a 16dp symbol column (`+`, `-`, space) in
 * addition to its colour and a 8% wash — the spec requires the symbol because
 * colour alone must never be the only signal. Long runs of unchanged lines fold
 * to "… N 行未变", and the whole block folds to a stats line past 200 lines.
 */
@Composable
fun DiffBlock(
    item: ToolDiff,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    onOpenFull: ((ToolDiff) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val plan = remember(item.key, item.diffText) { diffPlan(item.hunks, MAX_DIFF_ROWS) }
    val omitted = item.lineCount > MAX_DIFF_ROWS || item.truncated

    BlockColumn(modifier) {
        BlockCard(
            color = palette.toolPendingBg,
            modifier = Modifier.clickable(
                onClickLabel = if (expanded) "收起差异" else "展开差异",
            ) { expanded = !expanded },
            borderColor = palette.borderMuted.copy(alpha = 0.35f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.toolName.ifEmpty { "diff" },
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.path.ifEmpty { "未命名文件" },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = palette.toolTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text("+${item.added}", style = PiTheme.text.monoSmall, color = palette.toolDiffAdded)
                Spacer(Modifier.width(6.dp))
                Text("−${item.removed}", style = PiTheme.text.monoSmall, color = palette.toolDiffRemoved)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "新增 ${item.added} 行 · 删除 ${item.removed} 行",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                if (onOpenFull != null) {
                    Text(
                        text = "全屏",
                        modifier = Modifier
                            .clickable { onOpenFull(item) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ExpandLabel(expanded)
            }

            if (expanded) {
                if (plan.isEmpty()) {
                    MonoText(
                        text = item.diffText.ifEmpty { "（无差异内容）" },
                        color = palette.toolOutput,
                    )
                } else {
                    for (row in plan) {
                        when (row) {
                            is DiffRow.Header -> Text(
                                text = row.text,
                                style = PiTheme.text.monoSmall,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            is DiffRow.Fold -> Text(
                                text = "… ${row.count} 行未变",
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )

                            is DiffRow.Line -> DiffLineRow(row.line)
                        }
                    }
                    if (omitted) {
                        Text(
                            text = "差异过长，仅显示前 $MAX_DIFF_ROWS 行",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val palette = PiTheme.palette
    val symbol = when (line.kind) {
        DiffLineKind.Added -> "+"
        DiffLineKind.Removed -> "-"
        else -> " "
    }
    val markColor = when (line.kind) {
        DiffLineKind.Added -> palette.toolDiffAdded
        DiffLineKind.Removed -> palette.toolDiffRemoved
        else -> palette.toolDiffContext
    }
    val textColor = when (line.kind) {
        DiffLineKind.Added, DiffLineKind.Removed -> palette.text
        else -> palette.toolDiffContext
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(markColor.copy(alpha = 0.08f))
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = symbol,
            modifier = Modifier.width(16.dp),
            style = PiTheme.text.mono,
            color = markColor,
        )
        Text(
            text = lineNumber(line),
            modifier = Modifier.width(30.dp),
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = line.text.ifEmpty { " " },
            modifier = Modifier.weight(1f),
            style = PiTheme.text.mono,
            color = textColor,
        )
    }
}

private fun lineNumber(line: DiffLine): String = when {
    line.kind == DiffLineKind.Removed -> line.oldLine?.toString().orEmpty()
    line.newLine != null -> line.newLine.toString()
    line.oldLine != null -> line.oldLine.toString()
    else -> ""
}

// ------------------------------------------------------------ render planning

private sealed interface DiffRow {
    data class Header(val text: String) : DiffRow
    data class Fold(val count: Int) : DiffRow
    data class Line(val line: DiffLine) : DiffRow
}

/**
 * Flatten hunks into rows the renderer can emit directly: file headers, hunk
 * headers, folded context runs and real lines, capped at [maxLines].
 */
private fun diffPlan(hunks: List<DiffHunk>, maxLines: Int): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    var budget = maxLines
    for (hunk in hunks) {
        if (budget <= 0) break
        if (hunk.isFileHeader) {
            for (line in hunk.lines) {
                if (budget <= 0) break
                rows += DiffRow.Header(line.text)
                budget--
            }
            continue
        }
        rows += DiffRow.Header(hunk.header)
        var i = 0
        while (i < hunk.lines.size) {
            if (budget <= 0) break
            val line = hunk.lines[i]
            if (line.kind == DiffLineKind.Context) {
                var j = i
                while (j < hunk.lines.size && hunk.lines[j].kind == DiffLineKind.Context) j++
                val run = j - i
                if (run > CONTEXT_FOLD_THRESHOLD) {
                    rows += DiffRow.Line(hunk.lines[i])
                    rows += DiffRow.Fold(run - 2)
                    rows += DiffRow.Line(hunk.lines[j - 1])
                    budget -= 2
                    i = j
                    continue
                }
            }
            rows += DiffRow.Line(line)
            budget--
            i++
        }
    }
    return rows
}

private const val CONTEXT_FOLD_THRESHOLD = 4
private const val MAX_DIFF_ROWS = 200
