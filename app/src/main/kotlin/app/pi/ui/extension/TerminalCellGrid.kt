package app.pi.ui.extension

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import app.pi.protocol.Ansi
import app.pi.ui.theme.PiTheme

/**
 * 终端固定网格单元格（1 列或 2 列宽），用于精确渲染 TUI 表面（棋盘、框线、表格、游戏画面、反显光标）。
 *
 * 为什么不能用普通 Compose `Text(AnnotatedString)` 画 TUI 表面：
 * Android 上等宽字体不包含国际象棋符号（♔♕♖♗♘♙♚♛♜♝♞♟）、部分 Box Drawing / Emoji / CJK 字形，
 * 系统回退字体的字宽不是 `0` 字符宽度的整数倍，流式排版会把同一行后续所有背景色块推偏，导致棋盘/表格右边缘参差不齐。
 *
 * 这里用 Canvas 按严格单元格坐标 `[col * cellWidthPx, (col + colSpan) * cellWidthPx]` 先铺满背景色，
 * 再将字形等比居中绘在单元格内（过宽回退字形按比例微缩保持不形变、不越界），保证：
 *  1. 棋盘、表格、框线 100% 笔直齐整；
 *  2. 24-bit Truecolor 前景/背景与 SGR 反显/粗体/斜体/下划线/删除线/dim 完整还原；
 *  3. 触控坐标 `col = floor(x / cellWidthPx)` 像素级精确命中。
 */
internal data class TerminalGridCell(
    val col: Int,
    val colSpan: Int,
    val glyph: String,
    val fgArgb: Int,
    val bgArgb: Int?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
)

internal data class TerminalGridRowData(
    val cells: List<TerminalGridCell>,
    val totalCols: Int,
)

internal fun buildTerminalGridRow(
    spans: List<Ansi.Span>,
    tokens: IntArray,
    defaultFg: Color,
    surfaceBg: Color,
    linkColor: Color,
): TerminalGridRowData {
    if (spans.isEmpty()) return TerminalGridRowData(emptyList(), 0)
    val cells = ArrayList<TerminalGridCell>(64)
    var col = 0
    val defaultFgArgb = defaultFg.toArgb()
    val surfaceBgArgb = surfaceBg.toArgb()
    val linkArgb = linkColor.toArgb()

    for (span in spans) {
        val resolvedFg = richTokenColorFor(span, tokens)?.toArgb()
            ?: if (span.link != null) linkArgb else defaultFgArgb
        val resolvedBg = backgroundColorFor(span, tokens)?.toArgb()
        val rawFg = if (span.inverse) (resolvedBg ?: surfaceBgArgb) else resolvedFg
        val finalBg = if (span.inverse) resolvedFg else resolvedBg
        val finalFg = if (span.dim) {
            val a = ((rawFg ushr 24) and 0xFF) * 65 / 100
            (a shl 24) or (rawFg and 0x00FFFFFF)
        } else {
            rawFg
        }
        val wantUnderline = span.underline || span.link != null

        val text = span.text
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            val w = terminalCharWidth(cp)
            when {
                cp == 9 -> {
                    // Tab 展开到下一个 4 列对齐点，保留背景色
                    val spaces = (4 - (col % 4)).coerceAtLeast(1)
                    if (finalBg != null) {
                        cells += TerminalGridCell(
                            col = col,
                            colSpan = spaces,
                            glyph = "",
                            fgArgb = finalFg,
                            bgArgb = finalBg,
                            bold = span.bold,
                            italic = span.italic,
                            underline = wantUnderline,
                            strikethrough = span.strikethrough,
                        )
                    }
                    col += spaces
                }
                w == 0 -> {
                    // 组合音标 / 变体选择符（FE0E/FE0F）/ 零宽连接符：附着到前一单元格
                    if (cells.isNotEmpty() && cp >= 0x20 && cp != 0x7F) {
                        val lastIdx = cells.lastIndex
                        val prev = cells[lastIdx]
                        if (prev.glyph.isNotEmpty()) {
                            cells[lastIdx] = prev.copy(glyph = prev.glyph + text.substring(i, i + charCount))
                        }
                    }
                }
                else -> {
                    val ch = text.substring(i, i + charCount)
                    val isSpace = ch == " "
                    if (!isSpace || finalBg != null || wantUnderline || span.strikethrough) {
                        cells += TerminalGridCell(
                            col = col,
                            colSpan = w,
                            glyph = if (isSpace) "" else ch,
                            fgArgb = finalFg,
                            bgArgb = finalBg,
                            bold = span.bold,
                            italic = span.italic,
                            underline = wantUnderline,
                            strikethrough = span.strikethrough,
                        )
                    }
                    col += w
                }
            }
            i += charCount
        }
    }
    return TerminalGridRowData(cells = cells, totalCols = col)
}

@Composable
internal fun TerminalGridRow(
    line: String,
    cellWidthPx: Float,
    cellHeightPx: Float,
    monoStyle: TextStyle,
    minCols: Int,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val tokens = remember(palette) { palette.themeTokenArgb() }
    val defaultFg = palette.text
    val surfaceBg = palette.cardBg
    val linkColor = palette.mdLink

    val rowData = remember(line, tokens, defaultFg, surfaceBg, linkColor) {
        buildTerminalGridRow(
            spans = chromeSpans(line),
            tokens = tokens,
            defaultFg = defaultFg,
            surfaceBg = surfaceBg,
            linkColor = linkColor,
        )
    }
    val renderCols = maxOf(minCols, rowData.totalCols).coerceAtLeast(1)
    val fontSizePx = with(density) { monoStyle.fontSize.toPx() }
    val rowWidthDp = with(density) { (renderCols * cellWidthPx).toDp() }
    val rowHeightDp = with(density) { cellHeightPx.toDp() }

    val monoRegular = Typeface.MONOSPACE
    val monoBold = remember { Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    val monoItalic = remember { Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) }
    val monoBoldItalic = remember { Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC) }
    val paint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = monoRegular
            textSize = fontSizePx
        }
    }
    val bgPaint = remember { Paint().apply { style = Paint.Style.FILL } }
    val linePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }

    Canvas(
        modifier = modifier
            .then(
                if (rowData.totalCols > minCols) {
                    Modifier.width(rowWidthDp)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            .height(rowHeightDp),
    ) {
        val cells = rowData.cells
        if (cells.isEmpty()) return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // 1) 背景色批绘制：微扩 0.5px 避免小数 DPR 下棋盘相邻色块出现发丝缝隙
            for (i in 0 until cells.size) {
                val cell = cells[i]
                val bg = cell.bgArgb ?: continue
                val left = cell.col * cellWidthPx
                val right = (cell.col + cell.colSpan) * cellWidthPx + 0.5f
                bgPaint.color = bg
                native.drawRect(left, 0f, right, size.height + 0.5f, bgPaint)
            }
            // 2) 前景字形与装饰线绘制
            var lastBold = false
            var lastItalic = false
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = fontSizePx
            var baseFm = paint.fontMetrics
            var baseBaseline = (size.height - (baseFm.ascent + baseFm.descent)) / 2f

            for (i in 0 until cells.size) {
                val cell = cells[i]
                val cellLeft = cell.col * cellWidthPx
                val cellAvailW = cell.colSpan * cellWidthPx

                if (cell.glyph.isNotEmpty()) {
                    if (cell.bold != lastBold || cell.italic != lastItalic) {
                        lastBold = cell.bold
                        lastItalic = cell.italic
                        paint.typeface = when {
                            cell.bold && cell.italic -> monoBoldItalic
                            cell.bold -> monoBold
                            cell.italic -> monoItalic
                            else -> monoRegular
                        }
                        paint.textSize = fontSizePx
                        baseFm = paint.fontMetrics
                        baseBaseline = (size.height - (baseFm.ascent + baseFm.descent)) / 2f
                    }
                    paint.color = cell.fgArgb
                    val glyphW = paint.measureText(cell.glyph)
                    if (glyphW > cellAvailW * 0.98f && glyphW > 0f) {
                        // 回退字形（如棋子、Nerd Font、特殊符号）略宽于单元格时，按比例微缩字号，
                        // 既不拉扁变形也不越界侵占相邻格子。
                        val scaledSize = (fontSizePx * (cellAvailW * 0.94f / glyphW)).coerceAtLeast(fontSizePx * 0.55f)
                        paint.textSize = scaledSize
                        val fm = paint.fontMetrics
                        val scaledW = paint.measureText(cell.glyph)
                        val drawX = cellLeft + ((cellAvailW - scaledW) / 2f).coerceAtLeast(0f)
                        val drawY = (size.height - (fm.ascent + fm.descent)) / 2f
                        native.drawText(cell.glyph, drawX, drawY, paint)
                        paint.textSize = fontSizePx
                    } else {
                        val drawX = cellLeft + ((cellAvailW - glyphW) / 2f).coerceAtLeast(0f)
                        native.drawText(cell.glyph, drawX, baseBaseline, paint)
                    }
                }

                if (cell.underline || cell.strikethrough) {
                    linePaint.color = cell.fgArgb
                    linePaint.strokeWidth = (fontSizePx * 0.08f).coerceAtLeast(1f)
                    if (cell.underline) {
                        val uy = (size.height - linePaint.strokeWidth).coerceAtLeast(0f)
                        native.drawLine(cellLeft, uy, cellLeft + cellAvailW, uy, linePaint)
                    }
                    if (cell.strikethrough) {
                        val sy = size.height * 0.52f
                        native.drawLine(cellLeft, sy, cellLeft + cellAvailW, sy, linePaint)
                    }
                }
            }
        }
    }
}
