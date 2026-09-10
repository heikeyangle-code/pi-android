package app.pi.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.terminal.ScreenLine
import app.pi.terminal.TerminalController
import app.pi.terminal.TerminalPalette
import app.pi.terminal.TerminalText
import kotlin.math.floor

/**
 * The terminal grid, painted from the emulator's own cells.
 *
 * ## Why a Canvas and not Composable text
 *
 * A terminal is absolute addressing: row 7 column 40 means *that* cell. Laying out
 * one `Text` per line and trusting a monospace font to advance exactly one cell
 * per character is how a terminal view ends up a column off on one device and two
 * on another. So the surface measures one character, derives the cell from it, and
 * draws every line itself.
 *
 * That measurement is exact for [FontFamily.Monospace]. The one place it is not is
 * a fallback face for a CJK or emoji glyph — which is precisely why the *emulator*
 * assigns widths and the grid, not the font, decides where the next character
 * starts.
 *
 * ## Selection
 *
 * A phone has no mouse and no drag handles worth having at terminal font sizes, so
 * selection is line-based: long-press picks the line under the finger, dragging
 * extends across lines and columns, and releasing (or tapping while selected)
 * copies. That covers the real need — "get this output into the chat box" —
 * without pretending to have a desktop terminal's word/line/block modes.
 */
@Composable
fun TerminalSurface(
    controller: TerminalController,
    revision: Long,
    fontSize: Float,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onCopy: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    val snapshot = remember(revision, controller) { controller.snapshot() }
    val columns = snapshot.columns
    val viewportRows = snapshot.rows

    // One sample decides the cell size. `M` is used because it is a full-width
    // glyph in every monospace face; a narrow sample would under-report the
    // advance on some fonts.
    val cell: Size = remember(fontSize, measurer) {
        val result = measurer.measure(
            AnnotatedString("M"),
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
        )
        Size(result.size.width.toFloat(), result.size.height.toFloat())
    }

    // Keep the drawn history bounded. `pi`'s TUI rewrites lines constantly and a
    // chatty command can produce tens of thousands of them; unbounded scrollback
    // is how a terminal tab turns into an out-of-memory crash.
    val scrollbackTotal = snapshot.emulator.scrollbackSize
    val scrollbackStart = (scrollbackTotal - MAX_DRAWN_SCROLLBACK).coerceAtLeast(0)
    val drawnScrollback = scrollbackTotal - scrollbackStart
    val totalLines = drawnScrollback + viewportRows

    val contentHeight = with(density) { (cell.height * totalLines).toDp() }
    val style = remember(fontSize) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
    }

    // Lay every line out *before* drawing, not inside the draw scope: `measure`
    // during drawing would run text shaping on the render thread, once per frame.
    val layouts: Array<TextLayoutResult?> = remember(revision, selection, cell, style, drawnScrollback) {
        Array(totalLines) { row ->
            val line = lineFor(controller, snapshot, row, scrollbackStart) ?: return@Array null
            val range = selection?.columnRangeFor(row, scrollbackStart, columns)
            val text = TerminalText.styledLine(line, controller.palette, range)
            measurer.measure(
                text = text,
                style = style,
                constraints = Constraints(maxWidth = (cell.width * columns).toInt().coerceAtLeast(1)),
                maxLines = 1,
            )
        }
    }

    Box(modifier.fillMaxSize().background(controller.palette.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentHeight)
                    .pointerInput(controller, columns, totalLines, cell) {
                        detectTapGestures(
                            onTap = { position ->
                                val line = lineAt(position.y, cell.height, scrollState.value)
                                val column = columnAt(position.x, cell.width, columns)
                                val current = selection
                                if (current != null) {
                                    onCopy(selectedText(controller, snapshot, current, scrollbackStart))
                                    onSelectionChange(null)
                                } else {
                                    val url = linkAt(snapshot, line, column, scrollbackStart)
                                    if (url != null) onOpenLink(url) else onTap()
                                }
                            },
                            onLongPress = { position ->
                                val line = lineAt(position.y, cell.height, scrollState.value)
                                val column = columnAt(position.x, cell.width, columns)
                                onSelectionChange(TerminalSelection(line, column, line, column))
                            },
                        )
                    }
                    .pointerInput(controller, columns, totalLines, cell) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, _ ->
                                val current = selection ?: return@detectDragGesturesAfterLongPress
                                val line = lineAt(change.position.y, cell.height, scrollState.value)
                                val column = columnAt(change.position.x, cell.width, columns)
                                onSelectionChange(current.copy(endLine = line, endColumn = column))
                                change.consume()
                            },
                            onDragEnd = {
                                // Copy on release: "select-to-copy" should not
                                // require finding a button afterwards.
                                val current = selection ?: return@detectDragGesturesAfterLongPress
                                onCopy(selectedText(controller, snapshot, current, scrollbackStart))
                                onSelectionChange(null)
                            },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (row in 0 until totalLines) {
                        val layout = layouts.getOrNull(row) ?: continue
                        drawLayout(layout, row, cell)
                    }
                    // The cursor belongs to the live viewport only; on scrollback
                    // it would point at a line the program never wrote. Its drawn
                    // row is offset by however much history is drawn above.
                    val cursor = snapshot.emulator.cursor
                    if (cursor.visible && !snapshot.emulator.isAlternate) {
                        val cursorRow = snapshot.emulator.scrollbackSize - scrollbackStart + cursor.row
                        drawRect(
                            color = controller.palette.cursor.copy(alpha = 0.45f),
                            topLeft = Offset(cursor.column * cell.width, cursorRow * cell.height),
                            size = Size(cell.width, cell.height),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draw one line, pinning its baseline to the cell.
 *
 * The measured line height is the font's, which need not equal the cell height the
 * grid computed; centring inside the cell keeps rows aligned when it does not.
 */
private fun DrawScope.drawLayout(layout: TextLayoutResult, row: Int, cell: Size) {
    val y = row * cell.height
    val baselineOffset = (cell.height - layout.size.height) / 2f + layout.firstBaseline
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(0f, y + baselineOffset - layout.firstBaseline)
        drawText(layout)
        canvas.restore()
    }
}

/** One end of a line-based selection, in *drawn* rows (scrollback window included). */
data class TerminalSelection(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
) {
    /** The same selection with its ends ordered, so range maths is unambiguous. */
    fun normalized(): TerminalSelection = if (startLine < endLine || (startLine == endLine && startColumn <= endColumn)) {
        this
    } else {
        TerminalSelection(endLine, endColumn, startLine, startColumn)
    }

    /** The columns selected on [row], or null when [row] is outside the selection. */
    fun columnRangeFor(row: Int, scrollbackStart: Int, columns: Int): IntRange? {
        val ordered = normalized()
        if (row < ordered.startLine || row > ordered.endLine) return null
        val from = if (row == ordered.startLine) ordered.startColumn else 0
        val to = if (row == ordered.endLine) ordered.endColumn else columns - 1
        return if (from <= to) from..to else to..from
    }
}

/** Translate a touch's y into a drawn row, including the current scroll offset. */
private fun lineAt(y: Float, cellHeight: Float, scrollOffsetPx: Int): Int {
    if (cellHeight <= 0f) return 0
    return floor((y + scrollOffsetPx) / cellHeight).toInt().coerceAtLeast(0)
}

/** Translate a touch's x into a column. Both are in pixels. */
private fun columnAt(x: Float, cellWidth: Float, columns: Int): Int {
    if (cellWidth <= 0f) return 0
    return floor(x / cellWidth).toInt().coerceIn(0, columns - 1)
}

private fun lineFor(
    controller: TerminalController,
    snapshot: TerminalController.Snapshot,
    row: Int,
    scrollbackStart: Int,
): ScreenLine? {
    val active = snapshot.emulator.active
    return if (row < snapshot.emulator.scrollbackSize - scrollbackStart) {
        controller.scrollbackLine(scrollbackStart + row)
    } else {
        val index = row - (snapshot.emulator.scrollbackSize - scrollbackStart)
        active.lines.getOrNull(index)
    }
}

private fun linkAt(
    snapshot: TerminalController.Snapshot,
    row: Int,
    column: Int,
    scrollbackStart: Int,
): String? {
    val active = snapshot.emulator.active
    val index = row - (snapshot.emulator.scrollbackSize - scrollbackStart)
    return active.lines.getOrNull(index)?.linkAt(column)
}

/** The text a selection covers, with wide characters emitted once. */
private fun selectedText(
    controller: TerminalController,
    snapshot: TerminalController.Snapshot,
    selection: TerminalSelection,
    scrollbackStart: Int,
): String {
    val active = snapshot.emulator.active
    val low = minOf(selection.startLine, selection.endLine)
    val high = maxOf(selection.startLine, selection.endLine)
    val builder = StringBuilder()
    for (row in low..high) {
        val line = lineFor(controller, snapshot, row, scrollbackStart) ?: continue
        if (builder.isNotEmpty()) builder.append('\n')
        val range = selection.columnRangeFor(row, scrollbackStart, active.columns)
        if (range == null) {
            builder.append(TerminalText.plainText(line))
        } else {
            builder.append(TerminalText.textInRange(line, range.first, range.last))
        }
    }
    return builder.toString()
}

/** Hard cap on how much history the view will draw in one scroll container. */
private const val MAX_DRAWN_SCROLLBACK = 2000
