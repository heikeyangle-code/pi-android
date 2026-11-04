package app.pi.ui.render.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * The Compose **CSV/TSV data table** (`§九` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The parser, quoting rules and every bound live in `PiCsvTable.kt`; this file
 * only draws [CsvParseResult.Table]. It is separate from the model because a
 * Compose import cannot be compiled by the bare-JVM harnesses
 * (`PiCsvTableCheck.kt` tests the model).
 *
 * ## Interaction
 *
 * - The toolbar switches between the table and the **original source**. A failed
 *   parse (malformed quoting), a streaming fence or a size/row/column overflow
 *   keeps the source with a one-line reason — the plan's "超出限制…回退源码".
 * - The table scrolls horizontally as one unit; the first record is the header
 *   (`§九` "表头区分"), drawn with a tinted background and a heavier weight.
 * - A tap on any cell copies that cell's **decoded value** (quotes removed,
 *   `""` → `"`) and shows `已复制单元格`. The copy action never copies a visual
 *   rendering; "复制源码" is the source view's own job.
 *
 * ## Column widths
 *
 * Widths are computed once per table from a **bounded sample**: the header plus
 * the first [PI_CSV_WIDTH_SAMPLE_ROWS] data rows, each cell measured with the
 * same code style the cells are drawn with (single line, at most
 * [PI_CSV_WIDTH_SAMPLE_CHARS] characters). A column is then clamped to
 * [PI_CSV_MIN_CELL_WIDTH]..[PI_CSV_MAX_CELL_WIDTH]. That is "列宽自适应" without
 * letting one 200 KB cell demand a 200 KB column, and without an O(rows ×
 * columns) measure of a 2,000-row file.
 *
 * ## Display caps
 *
 * The parse limits are the model's (256 KiB, 2,000 records, 100 columns). Drawing
 * is further capped so a legal-but-huge table cannot freeze composition: at most
 * [PI_CSV_MAX_VISIBLE_CELLS] cells (so a 100-column table shows fewer rows) and
 * at most [PI_CSV_MAX_VISIBLE_ROWS] rows, followed by a stated "还有 N 行" row.
 * Nothing is truncated in the data: the source view and the clipboard still have
 * every byte. Multiline fields are preserved by the parser and wrap up to
 * [PI_CSV_CELL_MAX_LINES] lines in a cell; the full value is still copied.
 */
@Composable
fun PiCsvTableView(
    source: String,
    format: CsvFormat,
    settled: Boolean = true,
    initialMode: PiTreeMode = PiTreeMode.Tree,
    limits: CsvLimits = PiCsvTable.DEFAULT_LIMITS,
    modifier: Modifier = Modifier,
) {
    // 解析放后台并缓存（`rememberTreeParse`）：大表第一次出现不卡主线程，滚回来不重解析。
    // 种类里带上格式与限额，缓存键不会把 CSV 与 TSV、不同限额的结果串起来。
    val result = rememberTreeParse("csv-$format-${limits.hashCode()}", source, settled) {
        PiCsvTable.parse(source, format, settled, limits)
    }
    val table = result as? CsvParseResult.Table
    // 读者的「源码 / 表」选择：滚出屏幕再回来必须还在，所以是 rememberSaveable。
    var mode by rememberSaveable(source, format, initialMode) { mutableStateOf(initialMode) }

    val clipboard = LocalClipboardManager.current
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1500)
            feedback = null
        }
    }

    val currentFeedback: String? = feedback
    val failureNotice = if (result is CsvParseResult.Failure) {
        csvFailureNotice(result.reason, limits)
    } else {
        null
    }
    val status = currentFeedback
        ?: if (table != null) "${table.rowCount} 行 × ${table.columnCount} 列"
        else if (result == null) "解析中…"
        else failureNotice

    Column(modifier.fillMaxWidth()) {
        PiTreeToolbar(
            mode = mode,
            treeAvailable = table != null,
            status = status,
            onSelect = { mode = it },
            treeLabel = "表",
        )
        PiCardHairline()
        if (mode == PiTreeMode.Tree && table != null) {
            PiCsvTableBody(
                table = table,
                onCopyCell = { cell ->
                    clipboard.setText(AnnotatedString(cell))
                    feedback = "已复制单元格"
                },
            )
        } else {
            PiTreeRawSource(
                source = source,
                notice = if (mode == PiTreeMode.Tree) failureNotice else null,
            )
        }
    }
}

/**
 * The table itself, without the toolbar/fallback chrome.
 *
 * Public so a parent that owns its own header can draw just the table;
 * [onCopyCell] receives the decoded cell text so feedback stays with the caller.
 */
@Composable
fun PiCsvTableBody(
    table: CsvParseResult.Table,
    onCopyCell: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val cellStyle = PiTheme.typography.styles.code.copy(color = palette.text)
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(table, cellStyle, measurer, density) {
        computeColumnWidths(table, measurer, cellStyle, density)
    }
    val scroll = rememberScrollState()
    val tableWidth = widths.fold(0.dp) { total, width -> total + width }
    // 数值列右对齐：采样行里每个非空单元格都是数字才算（表头不参与判定）。
    val numeric = remember(table) { numericColumns(table) }
    // 一次只排版一页行：200 行 × 20 列 = 4000 个 Text 同时组合太重。点「显示更多」再加一页。
    var shown by rememberSaveable(table.rowCount, table.columnCount) { mutableStateOf(PI_CSV_PAGE_ROWS) }
    val capRows = visibleRowCount(table)
    val visibleRows = minOf(capRows, shown)
    val hiddenRows = table.rows.size - visibleRows

    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
        ) {
            Column(Modifier.width(tableWidth)) {
                CsvRow(
                    cells = List(table.columnCount) { table.header.getOrNull(it) ?: "" },
                    widths = widths,
                    numeric = numeric,
                    style = headerStyle,
                    background = palette.mdHr.copy(alpha = 0.14f),
                    onCopyCell = onCopyCell,
                )
                CsvDivider()
                for (rowIndex in 0 until visibleRows) {
                    val row = table.rows[rowIndex]
                    CsvRow(
                        cells = List(table.columnCount) { row.getOrNull(it) ?: "" },
                        widths = widths,
                        numeric = numeric,
                        style = cellStyle,
                        // 隔行淡底色，与 Markdown 表格同一套。
                        background = if (rowIndex % 2 == 1) palette.mdHr.copy(alpha = 0.06f) else Color.Transparent,
                        onCopyCell = onCopyCell,
                    )
                }
            }
        }

        if (hiddenRows > 0) {
            if (visibleRows < capRows) {
                PiTreeMoreRow(depth = 0, remaining = hiddenRows) { shown += PI_CSV_PAGE_ROWS }
            } else {
                Text(
                    text = "… 还有 $hiddenRows 行未显示（切换源码查看全部）",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CsvRow(
    cells: List<String>,
    widths: List<Dp>,
    numeric: BooleanArray,
    style: TextStyle,
    background: Color,
    onCopyCell: (String) -> Unit,
) {
    Row(modifier = Modifier.background(background), verticalAlignment = Alignment.Top) {
        cells.forEachIndexed { column, cell ->
            val width = widths.getOrNull(column) ?: return@forEachIndexed
            Text(
                text = cell,
                style = if (column < numeric.size && numeric[column]) {
                    style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End)
                } else {
                    style
                },
                maxLines = PI_CSV_CELL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(width)
                    .then(
                        if (cell.isEmpty()) {
                            Modifier
                        } else {
                            Modifier.clickable(onClickLabel = "复制单元格") { onCopyCell(cell) }
                        },
                    )
                    .padding(
                        horizontal = PI_CSV_CELL_HORIZONTAL_PADDING,
                        vertical = PI_CSV_CELL_VERTICAL_PADDING,
                    ),
            )
        }
    }
}

@Composable
private fun CsvDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PiTheme.palette.borderMuted.copy(alpha = 0.35f)),
    )
}

/**
 * Per-column widths from a bounded sample.
 *
 * The sample is header + the first [PI_CSV_WIDTH_SAMPLE_ROWS] rows, cells longer
 * than [PI_CSV_WIDTH_SAMPLE_CHARS] are measured on a prefix (their column already
 * saturates the cap in practice), and every result is clamped to
 * [PI_CSV_MIN_CELL_WIDTH]..[PI_CSV_MAX_CELL_WIDTH]. Cell text itself is never
 * modified.
 */
private fun computeColumnWidths(
    table: CsvParseResult.Table,
    measurer: TextMeasurer,
    cellStyle: TextStyle,
    density: Density,
): List<Dp> {
    if (table.columnCount <= 0) return emptyList()
    val horizontalPaddingPx = with(density) { PI_CSV_CELL_HORIZONTAL_PADDING.toPx() } * 2f
    val minPx = with(density) { PI_CSV_MIN_CELL_WIDTH.toPx() }
    val maxPx = with(density) { PI_CSV_MAX_CELL_WIDTH.toPx() }
    val widths = FloatArray(table.columnCount) { minPx }

    fun include(text: String, column: Int) {
        if (column >= widths.size) return
        val sample = when {
            text.isEmpty() -> " "
            text.length > PI_CSV_WIDTH_SAMPLE_CHARS -> text.substring(0, PI_CSV_WIDTH_SAMPLE_CHARS)
            else -> text
        }
        val measured = measurer.measure(text = sample, style = cellStyle, maxLines = 1)
            .size.width.toFloat() + horizontalPaddingPx
        if (measured > widths[column]) widths[column] = measured
    }

    for (column in 0 until table.columnCount) {
        include(table.header.getOrNull(column) ?: "", column)
    }
    val sampleRows = minOf(table.rows.size, PI_CSV_WIDTH_SAMPLE_ROWS)
    for (rowIndex in 0 until sampleRows) {
        val row = table.rows[rowIndex]
        for (column in 0 until table.columnCount) {
            include(row.getOrNull(column) ?: "", column)
        }
    }

    return widths.map { px -> with(density) { px.coerceIn(minPx, maxPx).toDp() } }
}

/** 数值列：采样行里所有非空单元格都读得成数字（容忍千分位、%、货币符号、科学计数），且至少一个非空。 */
private fun numericColumns(table: CsvParseResult.Table): BooleanArray {
    val out = BooleanArray(table.columnCount)
    val sample = minOf(table.rows.size, PI_CSV_WIDTH_SAMPLE_ROWS)
    for (column in 0 until table.columnCount) {
        var any = false
        var ok = true
        for (rowIndex in 0 until sample) {
            val cell = table.rows[rowIndex].getOrNull(column)?.trim().orEmpty()
            if (cell.isEmpty()) continue
            if (!CSV_NUMBER.matches(cell)) {
                ok = false
                break
            }
            any = true
        }
        out[column] = ok && any
    }
    return out
}

private val CSV_NUMBER = Regex("[-+]?[\\$¥€£]?\\s?\\d[\\d,]*(\\.\\d+)?([eE][-+]?\\d+)?%?")

/** 一页行数；「显示更多」每次再加一页。 */
private const val PI_CSV_PAGE_ROWS = 40

/** Rows drawn: bounded by both a row cap and a total-cell cap (wide tables show fewer rows). */
private fun visibleRowCount(table: CsvParseResult.Table): Int {
    val byCells = PI_CSV_MAX_VISIBLE_CELLS / table.columnCount.coerceAtLeast(1)
    return table.rows.size.coerceAtMost(PI_CSV_MAX_VISIBLE_ROWS).coerceAtMost(byCells)
}

/** One line's reason, built from the same numbers [PiCsvTable.parse] enforces. */
private fun csvFailureNotice(reason: CsvFailure, limits: CsvLimits): String = when (reason) {
    CsvFailure.NotSettled -> "CSV/TSV 还在生成，先显示源码"
    CsvFailure.Empty -> "CSV/TSV 为空，先显示源码"
    CsvFailure.TooLarge -> "CSV/TSV 超过 ${limits.maxChars / 1024} KiB，已回退源码"
    CsvFailure.TooManyRows -> "CSV/TSV 超过 ${limits.maxRows} 行，已回退源码"
    CsvFailure.TooManyColumns -> "CSV/TSV 超过 ${limits.maxColumns} 列，已回退源码"
    CsvFailure.Malformed -> "CSV/TSV 的引号规则无法解析（RFC 4180），已回退源码"
}

/** Per-cell wrap cap; the full value is still copied by tapping the cell. */
private const val PI_CSV_CELL_MAX_LINES = 6

/** Rows sampled when computing column widths. */
private const val PI_CSV_WIDTH_SAMPLE_ROWS = 50

/** Characters per cell used for width sampling (the rest cannot widen the cap). */
private const val PI_CSV_WIDTH_SAMPLE_CHARS = 120

/** Hard display cap on rows, before the cell cap. */
private const val PI_CSV_MAX_VISIBLE_ROWS = 200

/** Hard display cap on cells; a 100-column table therefore shows fewer rows. */
private const val PI_CSV_MAX_VISIBLE_CELLS = 4_000

private val PI_CSV_MIN_CELL_WIDTH = 64.dp
private val PI_CSV_MAX_CELL_WIDTH = 260.dp
private val PI_CSV_CELL_HORIZONTAL_PADDING = 10.dp
private val PI_CSV_CELL_VERTICAL_PADDING = 6.dp
