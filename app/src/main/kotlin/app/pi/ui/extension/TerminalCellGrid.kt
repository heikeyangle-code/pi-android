package app.pi.ui.extension

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
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
 *  1. Android 上等宽字体不包含部分棋子 / Box Drawing / Emoji / CJK 字形，系统回退字体的字宽不是 `0` 的整数倍，
 *     流式排版会把同一行后续所有背景色块推偏，导致棋盘/表格右边缘参差不齐；
 *  2. 字体里的框线（`─│┌┐└┘╭╮╰╯═║`）与块元素（`█▀▄▌▐░▒▓`）字形高度小于行高，直接用 `drawText` 会出现
 *     断续虚线框与扁平/带缝隙的方块，与真实终端模拟器差距明显。
 *
 * 这里用 Canvas 按严格单元格坐标 `[col * cellWidthPx, (col + colSpan) * cellWidthPx]` 先铺满背景色，
 * 对 Box Drawing（U+2500..U+257F）与 Block Elements（U+2580..U+259F）做像素级几何矢量直绘（满格无缝衔接），
 * 其余字符等比居中绘制（过宽回退字形按比例微缩保持不形变、不越界），与真实终端渲染视觉一致。
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

/** 是否由几何矢量直绘替代字体字形：覆盖全部 Box Drawing（U+2500..U+257F）与 Block Elements（U+2580..U+259F）。 */
internal fun isGeometricTerminalGlyph(cp: Int): Boolean =
    cp in 0x2500..0x259F

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
                            val promoted = cp == 0xFE0F && prev.colSpan == 1
                            cells[lastIdx] = prev.copy(
                                glyph = prev.glyph + text.substring(i, i + charCount),
                                colSpan = if (promoted) 2 else prev.colSpan,
                            )
                            if (promoted) col += 1
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

internal fun terminalGridRowWidthPx(
    renderCols: Int,
    cellWidthPx: Float,
): Int = kotlin.math.ceil(renderCols.coerceAtLeast(1) * cellWidthPx.coerceAtLeast(1f)).toInt()

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
    val rowWidthDp = with(density) { terminalGridRowWidthPx(renderCols, cellWidthPx).toDp() }
    val rowHeightDp = with(density) { cellHeightPx.toDp() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val typography = PiTheme.typography
    val codeFontKey = typography.codeFont
    val monoRegular = remember(context, codeFontKey) {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, typography.codeFontResRegular())
        }.getOrNull() ?: Typeface.MONOSPACE
    }
    val monoBold = remember(context, codeFontKey) {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, typography.codeFontResBold())
        }.getOrNull() ?: Typeface.create(monoRegular, Typeface.BOLD)
    }
    val monoItalic = remember(context, codeFontKey) {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, typography.codeFontResItalic())
        }.getOrNull() ?: Typeface.create(monoRegular, Typeface.ITALIC)
    }
    val monoBoldItalic = remember(context, codeFontKey) {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, typography.codeFontResBoldItalic())
        }.getOrNull() ?: Typeface.create(monoRegular, Typeface.BOLD_ITALIC)
    }

    val paint = remember(fontSizePx, monoRegular) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = monoRegular
            textSize = fontSizePx
        }
    }
    val fillPaint = remember { Paint().apply { style = Paint.Style.FILL; isAntiAlias = false } }
    val linePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }
    }
    val arcRect = remember { RectF() }

    // 必须显式指定确定宽高（width + height），严禁改回 widthIn(min = ...) + fillMaxWidth()：
    // Canvas 底层是 Spacer，其 MeasurePolicy 在 !hasFixedWidth 时返回 width = 0；
    // 处于 horizontalScroll（maxWidth = Infinity）内时，fillMaxWidth() 会失效，
    // Placeable 把 0 夹紧到 minWidth 后会产生 apparentToRealOffset.x = +(rowWidth / 2)，
    // 把整行 TUI 画面向右平移半屏并裁掉右半边。
    Canvas(
        modifier = modifier
            .width(rowWidthDp)
            .height(rowHeightDp),
    ) {
        val cells = rowData.cells
        if (cells.isEmpty()) return@Canvas
        val rowH = size.height
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // 1) 背景色批绘制：微扩 0.5px 避免小数 DPR 下相邻色块出现发丝缝隙
            for (i in 0 until cells.size) {
                val cell = cells[i]
                val bg = cell.bgArgb ?: continue
                val left = cell.col * cellWidthPx
                val right = (cell.col + cell.colSpan) * cellWidthPx + 0.5f
                fillPaint.color = bg
                native.drawRect(left, 0f, right, rowH + 0.5f, fillPaint)
            }

            // 2) 前景字形与几何框线/方块绘制
            var lastBold = false
            var lastItalic = false
            paint.typeface = monoRegular
            paint.textSize = fontSizePx
            var baseFm = paint.fontMetrics
            var baseBaseline = (rowH - (baseFm.ascent + baseFm.descent)) / 2f

            for (i in 0 until cells.size) {
                val cell = cells[i]
                val cellLeft = cell.col * cellWidthPx
                val cellAvailW = cell.colSpan * cellWidthPx

                if (cell.glyph.isNotEmpty()) {
                    val cp = Character.codePointAt(cell.glyph, 0)
                    val isSingleCp = Character.charCount(cp) == cell.glyph.length
                    if (isSingleCp && isGeometricTerminalGlyph(cp)) {
                        drawGeometricGlyph(
                            canvas = native,
                            cp = cp,
                            left = cellLeft,
                            width = cellAvailW,
                            height = rowH,
                            fgArgb = cell.fgArgb,
                            fillPaint = fillPaint,
                            linePaint = linePaint,
                            arcRect = arcRect,
                        )
                    } else {
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
                            baseBaseline = (rowH - (baseFm.ascent + baseFm.descent)) / 2f
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
                            val drawY = (rowH - (fm.ascent + fm.descent)) / 2f
                            native.drawText(cell.glyph, drawX, drawY, paint)
                            paint.textSize = fontSizePx
                        } else {
                            val drawX = cellLeft + ((cellAvailW - glyphW) / 2f).coerceAtLeast(0f)
                            native.drawText(cell.glyph, drawX, baseBaseline, paint)
                        }
                    }
                }

                if (cell.underline || cell.strikethrough) {
                    linePaint.color = cell.fgArgb
                    linePaint.strokeWidth = (fontSizePx * 0.08f).coerceAtLeast(1f)
                    linePaint.strokeCap = Paint.Cap.BUTT
                    if (cell.underline) {
                        val uy = (rowH - linePaint.strokeWidth).coerceAtLeast(0f)
                        native.drawLine(cellLeft, uy, cellLeft + cellAvailW, uy, linePaint)
                    }
                    if (cell.strikethrough) {
                        val sy = rowH * 0.52f
                        native.drawLine(cellLeft, sy, cellLeft + cellAvailW, sy, linePaint)
                    }
                }
            }
        }
    }
}

// ---- 终端几何字形直绘：Block Elements (U+2580..U+259F) 与 Box Drawing (U+2500..U+257F) ----

private const val ARM_NONE = 0
private const val ARM_LIGHT = 1
private const val ARM_HEAVY = 2
private const val ARM_DOUBLE = 3

private fun drawGeometricGlyph(
    canvas: android.graphics.Canvas,
    cp: Int,
    left: Float,
    width: Float,
    height: Float,
    fgArgb: Int,
    fillPaint: Paint,
    linePaint: Paint,
    arcRect: RectF,
) {
    if (cp in 0x2580..0x259F) {
        drawBlockElement(canvas, cp, left, width, height, fgArgb, fillPaint)
    } else {
        drawBoxDrawing(canvas, cp, left, width, height, fgArgb, linePaint, arcRect)
    }
}

private fun drawBlockElement(
    canvas: android.graphics.Canvas,
    cp: Int,
    left: Float,
    w: Float,
    h: Float,
    fgArgb: Int,
    fillPaint: Paint,
) {
    fillPaint.color = fgArgb
    val pad = 0.5f
    fun rect(rx0: Float, ry0: Float, rx1: Float, ry1: Float) {
        canvas.drawRect(
            left + w * rx0,
            h * ry0,
            left + w * rx1 + pad,
            h * ry1 + pad,
            fillPaint,
        )
    }
    when (cp) {
        0x2580 -> rect(0f, 0f, 1f, 0.5f) // ▀
        in 0x2581..0x2587 -> {
            // ▁..▇ 下方 1/8..7/8
            val frac = (cp - 0x2580) / 8f
            rect(0f, 1f - frac, 1f, 1f)
        }
        0x2588 -> rect(0f, 0f, 1f, 1f) // █ 满格方块（无缝拼接）
        in 0x2589..0x258F -> {
            // ▉..▏ 左侧 7/8..1/8
            val frac = (0x2590 - cp) / 8f
            rect(0f, 0f, frac, 1f)
        }
        0x2590 -> rect(0.5f, 0f, 1f, 1f) // ▐ 右半块
        0x2591 -> drawShadeStipple(canvas, left, w, h, fgArgb, fillPaint, level = 1) // ░ 轻点阵阴影
        0x2592 -> drawShadeStipple(canvas, left, w, h, fgArgb, fillPaint, level = 2) // ▒ 中点阵阴影
        0x2593 -> drawShadeStipple(canvas, left, w, h, fgArgb, fillPaint, level = 3) // ▓ 重阴影
        0x2594 -> rect(0f, 0f, 1f, 0.125f) // ▔ 顶 1/8
        0x2595 -> rect(0.875f, 0f, 1f, 1f) // ▕ 右 1/8
        // 四象限拼块（U+2596..U+259F）：bit0=UL, bit1=UR, bit2=LL, bit3=LR
        0x2596 -> rect(0f, 0.5f, 0.5f, 1f)
        0x2597 -> rect(0.5f, 0.5f, 1f, 1f)
        0x2598 -> rect(0f, 0f, 0.5f, 0.5f)
        0x2599 -> { rect(0f, 0f, 0.5f, 1f); rect(0.5f, 0.5f, 1f, 1f) }
        0x259A -> { rect(0f, 0f, 0.5f, 0.5f); rect(0.5f, 0.5f, 1f, 1f) }
        0x259B -> { rect(0f, 0f, 1f, 0.5f); rect(0f, 0.5f, 0.5f, 1f) }
        0x259C -> { rect(0f, 0f, 1f, 0.5f); rect(0.5f, 0.5f, 1f, 1f) }
        0x259D -> rect(0.5f, 0f, 1f, 0.5f)
        0x259E -> { rect(0.5f, 0f, 1f, 0.5f); rect(0f, 0.5f, 0.5f, 1f) }
        0x259F -> { rect(0.5f, 0f, 1f, 1f); rect(0f, 0.5f, 0.5f, 1f) }
    }
}

/**
 * 终端风格点阵阴影（`░` / `▒` / `▓`）：满格均匀棋盘微点，避免字体自带斜杠或留白导致幽灵块/进度条失真。
 */
private fun drawShadeStipple(
    canvas: android.graphics.Canvas,
    left: Float,
    w: Float,
    h: Float,
    fgArgb: Int,
    fillPaint: Paint,
    level: Int,
) {
    val cols = 4
    val rows = 8
    val dx = w / cols
    val dy = h / rows
    if (level == 3) {
        // ▓ 重阴影：先铺 60% 淡底，再加高密点阵
        val baseA = ((fgArgb ushr 24) and 0xFF) * 60 / 100
        fillPaint.color = (baseA shl 24) or (fgArgb and 0x00FFFFFF)
        canvas.drawRect(left, 0f, left + w + 0.5f, h + 0.5f, fillPaint)
    }
    val dotA = when (level) {
        1 -> ((fgArgb ushr 24) and 0xFF) * 68 / 100
        2 -> ((fgArgb ushr 24) and 0xFF) * 85 / 100
        else -> (fgArgb ushr 24) and 0xFF
    }
    fillPaint.color = (dotA shl 24) or (fgArgb and 0x00FFFFFF)
    val dotW = (dx * 0.62f).coerceAtLeast(1f)
    val dotH = (dy * 0.62f).coerceAtLeast(1f)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val drawDot = when (level) {
                1 -> (r % 2 == 0 && c % 2 == (r / 2) % 2)
                2 -> (r + c) % 2 == 0
                else -> (r + c) % 2 == 0 || (r % 2 == 0)
            }
            if (drawDot) {
                val x = left + c * dx + (dx - dotW) * 0.5f
                val y = r * dy + (dy - dotH) * 0.5f
                canvas.drawRect(x, y, x + dotW, y + dotH, fillPaint)
            }
        }
    }
}

private fun drawBoxDrawing(
    canvas: android.graphics.Canvas,
    cp: Int,
    left: Float,
    w: Float,
    h: Float,
    fgArgb: Int,
    linePaint: Paint,
    arcRect: RectF,
) {
    val cx = left + w * 0.5f
    val cy = h * 0.5f
    val right = left + w
    val lightW = (w * 0.14f).coerceIn(1.25f, 3.0f)
    val heavyW = (lightW * 2.0f).coerceIn(2.2f, 5.2f)
    val dblGap = (lightW * 1.45f).coerceIn(1.8f, w * 0.24f)

    linePaint.color = fgArgb

    // 圆角框线 ╭ ╮ ╰ ╯
    if (cp in 0x256D..0x2570) {
        linePaint.strokeWidth = lightW
        linePaint.strokeCap = Paint.Cap.BUTT
        val r = minOf(w * 0.5f, h * 0.5f)
        when (cp) {
            0x256D -> { // ╭ right + down
                arcRect.set(cx, cy, cx + 2f * r, cy + 2f * r)
                canvas.drawArc(arcRect, 180f, 90f, false, linePaint)
                if (cx + r < right) canvas.drawLine(cx + r, cy, right, cy, linePaint)
                if (cy + r < h) canvas.drawLine(cx, cy + r, cx, h, linePaint)
            }
            0x256E -> { // ╮ left + down
                arcRect.set(cx - 2f * r, cy, cx, cy + 2f * r)
                canvas.drawArc(arcRect, 270f, 90f, false, linePaint)
                if (cx - r > left) canvas.drawLine(left, cy, cx - r, cy, linePaint)
                if (cy + r < h) canvas.drawLine(cx, cy + r, cx, h, linePaint)
            }
            0x256F -> { // ╯ left + up
                arcRect.set(cx - 2f * r, cy - 2f * r, cx, cy)
                canvas.drawArc(arcRect, 0f, 90f, false, linePaint)
                if (cx - r > left) canvas.drawLine(left, cy, cx - r, cy, linePaint)
                if (cy - r > 0f) canvas.drawLine(cx, 0f, cx, cy - r, linePaint)
            }
            0x2570 -> { // ╰ right + up
                arcRect.set(cx, cy - 2f * r, cx + 2f * r, cy)
                canvas.drawArc(arcRect, 90f, 90f, false, linePaint)
                if (cx + r < right) canvas.drawLine(cx + r, cy, right, cy, linePaint)
                if (cy - r > 0f) canvas.drawLine(cx, 0f, cx, cy - r, linePaint)
            }
        }
        return
    }

    // 斜线 ╱ ╲ ╳
    if (cp in 0x2571..0x2573) {
        linePaint.strokeWidth = lightW
        linePaint.strokeCap = Paint.Cap.ROUND
        if (cp == 0x2571 || cp == 0x2573) canvas.drawLine(left, h, right, 0f, linePaint)
        if (cp == 0x2572 || cp == 0x2573) canvas.drawLine(left, 0f, right, h, linePaint)
        return
    }

    // 虚线（┄┅┆┇┈┉┊┋）与双短虚线（╌╍╎╏）
    when (cp) {
        0x2504, 0x2505, 0x2508, 0x2509, 0x254C, 0x254D -> {
            val isHeavy = cp == 0x2505 || cp == 0x2509 || cp == 0x254D
            val segs = when (cp) {
                0x254C, 0x254D -> 2
                0x2504, 0x2505 -> 3
                else -> 4
            }
            linePaint.strokeWidth = if (isHeavy) heavyW else lightW
            linePaint.strokeCap = Paint.Cap.BUTT
            val step = w / segs
            for (s in 0 until segs) {
                val x0 = left + s * step + step * 0.14f
                val x1 = left + (s + 1) * step - step * 0.14f
                canvas.drawLine(x0, cy, x1, cy, linePaint)
            }
            return
        }
        0x2506, 0x2507, 0x250A, 0x250B, 0x254E, 0x254F -> {
            val isHeavy = cp == 0x2507 || cp == 0x250B || cp == 0x254F
            val segs = when (cp) {
                0x254E, 0x254F -> 2
                0x2506, 0x2507 -> 3
                else -> 4
            }
            linePaint.strokeWidth = if (isHeavy) heavyW else lightW
            linePaint.strokeCap = Paint.Cap.BUTT
            val step = h / segs
            for (s in 0 until segs) {
                val y0 = s * step + step * 0.14f
                val y1 = (s + 1) * step - step * 0.14f
                canvas.drawLine(cx, y0, cx, y1, linePaint)
            }
            return
        }
    }

    // 常规四臂编码：bits [L:2][R:2][U:2][D:2]
    val arms = boxArmsFor(cp)
    if (arms == 0) return
    val armL = (arms ushr 6) and 3
    val armR = (arms ushr 4) and 3
    val armU = (arms ushr 2) and 3
    val armD = arms and 3

    linePaint.strokeCap = Paint.Cap.BUTT

    fun strokeFor(style: Int): Float = if (style == ARM_HEAVY) heavyW else lightW

    // 双线外/内连接偏移
    val hasDblV = armU == ARM_DOUBLE || armD == ARM_DOUBLE
    val hasDblH = armL == ARM_DOUBLE || armR == ARM_DOUBLE

    // 左臂
    when (armL) {
        ARM_LIGHT, ARM_HEAVY -> {
            linePaint.strokeWidth = strokeFor(armL)
            val endX = if (hasDblV && armR == ARM_NONE) cx - dblGap else cx + linePaint.strokeWidth * 0.5f
            canvas.drawLine(left - 0.5f, cy, endX.coerceAtMost(right + 0.5f), cy, linePaint)
        }
        ARM_DOUBLE -> {
            linePaint.strokeWidth = lightW
            val topEnd = if (armU == ARM_DOUBLE && armR == ARM_NONE) cx - dblGap else if (armR == ARM_NONE && armD == ARM_DOUBLE) cx + dblGap else cx
            val botEnd = if (armD == ARM_DOUBLE && armR == ARM_NONE) cx - dblGap else if (armR == ARM_NONE && armU == ARM_DOUBLE) cx + dblGap else cx
            canvas.drawLine(left, cy - dblGap, topEnd, cy - dblGap, linePaint)
            canvas.drawLine(left, cy + dblGap, botEnd, cy + dblGap, linePaint)
        }
    }
    // 右臂
    when (armR) {
        ARM_LIGHT, ARM_HEAVY -> {
            linePaint.strokeWidth = strokeFor(armR)
            val startX = if (hasDblV && armL == ARM_NONE) cx + dblGap else cx - linePaint.strokeWidth * 0.5f
            canvas.drawLine(startX.coerceAtLeast(left - 0.5f), cy, right + 0.5f, cy, linePaint)
        }
        ARM_DOUBLE -> {
            linePaint.strokeWidth = lightW
            val topStart = if (armU == ARM_DOUBLE && armL == ARM_NONE) cx + dblGap else if (armL == ARM_NONE && armD == ARM_DOUBLE) cx - dblGap else cx
            val botStart = if (armD == ARM_DOUBLE && armL == ARM_NONE) cx + dblGap else if (armL == ARM_NONE && armU == ARM_DOUBLE) cx - dblGap else cx
            canvas.drawLine(topStart, cy - dblGap, right, cy - dblGap, linePaint)
            canvas.drawLine(botStart, cy + dblGap, right, cy + dblGap, linePaint)
        }
    }
    // 上臂
    when (armU) {
        ARM_LIGHT, ARM_HEAVY -> {
            linePaint.strokeWidth = strokeFor(armU)
            val endY = if (hasDblH && armD == ARM_NONE) cy - dblGap else cy + linePaint.strokeWidth * 0.5f
            canvas.drawLine(cx, -0.5f, cx, endY.coerceAtMost(h + 0.5f), linePaint)
        }
        ARM_DOUBLE -> {
            linePaint.strokeWidth = lightW
            val leftEnd = if (armL == ARM_DOUBLE && armD == ARM_NONE) cy - dblGap else if (armD == ARM_NONE && armR == ARM_DOUBLE) cy + dblGap else cy
            val rightEnd = if (armR == ARM_DOUBLE && armD == ARM_NONE) cy - dblGap else if (armD == ARM_NONE && armL == ARM_DOUBLE) cy + dblGap else cy
            canvas.drawLine(cx - dblGap, 0f, cx - dblGap, leftEnd, linePaint)
            canvas.drawLine(cx + dblGap, 0f, cx + dblGap, rightEnd, linePaint)
        }
    }
    // 下臂
    when (armD) {
        ARM_LIGHT, ARM_HEAVY -> {
            linePaint.strokeWidth = strokeFor(armD)
            val startY = if (hasDblH && armU == ARM_NONE) cy + dblGap else cy - linePaint.strokeWidth * 0.5f
            canvas.drawLine(cx, startY.coerceAtLeast(-0.5f), cx, h + 0.5f, linePaint)
        }
        ARM_DOUBLE -> {
            linePaint.strokeWidth = lightW
            val leftStart = if (armL == ARM_DOUBLE && armU == ARM_NONE) cy + dblGap else if (armU == ARM_NONE && armR == ARM_DOUBLE) cy - dblGap else cy
            val rightStart = if (armR == ARM_DOUBLE && armU == ARM_NONE) cy + dblGap else if (armU == ARM_NONE && armL == ARM_DOUBLE) cy - dblGap else cy
            canvas.drawLine(cx - dblGap, leftStart, cx - dblGap, h, linePaint)
            canvas.drawLine(cx + dblGap, rightStart, cx + dblGap, h, linePaint)
        }
    }
}

private fun arms(l: Int, r: Int, u: Int, d: Int): Int =
    (l shl 6) or (r shl 4) or (u shl 2) or d

/** 将 Unicode Box Drawing（U+2500..U+257F）解码为 (left, right, up, down) 四臂线型。 */
private fun boxArmsFor(cp: Int): Int {
    val N = ARM_NONE
    val L = ARM_LIGHT
    val H = ARM_HEAVY
    val D = ARM_DOUBLE
    return when (cp) {
        0x2500 -> arms(L, L, N, N) // ─
        0x2501 -> arms(H, H, N, N) // ━
        0x2502 -> arms(N, N, L, L) // │
        0x2503 -> arms(N, N, H, H) // ┃
        0x250C -> arms(N, L, N, L) // ┌
        0x250D -> arms(N, H, N, L) // ┍
        0x250E -> arms(N, L, N, H) // ┎
        0x250F -> arms(N, H, N, H) // ┏
        0x2510 -> arms(L, N, N, L) // ┐
        0x2511 -> arms(H, N, N, L) // ┑
        0x2512 -> arms(L, N, N, H) // ┒
        0x2513 -> arms(H, N, N, H) // ┓
        0x2514 -> arms(N, L, L, N) // └
        0x2515 -> arms(N, H, L, N) // ┕
        0x2516 -> arms(N, L, H, N) // ┖
        0x2517 -> arms(N, H, H, N) // ┗
        0x2518 -> arms(L, N, L, N) // ┘
        0x2519 -> arms(H, N, L, N) // ┙
        0x251A -> arms(L, N, H, N) // ┚
        0x251B -> arms(H, N, H, N) // ┛
        0x251C -> arms(N, L, L, L) // ├
        0x251D -> arms(N, H, L, L) // ┝
        0x251E -> arms(N, L, H, L) // ┞
        0x251F -> arms(N, L, L, H) // ┟
        0x2520 -> arms(N, L, H, H) // ┠
        0x2521 -> arms(N, H, H, L) // ┡
        0x2522 -> arms(N, H, L, H) // ┢
        0x2523 -> arms(N, H, H, H) // ┣
        0x2524 -> arms(L, N, L, L) // ┤
        0x2525 -> arms(H, N, L, L) // ┥
        0x2526 -> arms(L, N, H, L) // ┦
        0x2527 -> arms(L, N, L, H) // ┧
        0x2528 -> arms(L, N, H, H) // ┨
        0x2529 -> arms(H, N, H, L) // ┩
        0x252A -> arms(H, N, L, H) // ┪
        0x252B -> arms(H, N, H, H) // ┫
        0x252C -> arms(L, L, N, L) // ┬
        0x252D -> arms(H, L, N, L) // ┭
        0x252E -> arms(L, H, N, L) // ┮
        0x252F -> arms(H, H, N, L) // ┯
        0x2530 -> arms(L, L, N, H) // ┰
        0x2531 -> arms(H, L, N, H) // ┱
        0x2532 -> arms(L, H, N, H) // ┲
        0x2533 -> arms(H, H, N, H) // ┳
        0x2534 -> arms(L, L, L, N) // ┴
        0x2535 -> arms(H, L, L, N) // ┵
        0x2536 -> arms(L, H, L, N) // ┶
        0x2537 -> arms(H, H, L, N) // ┷
        0x2538 -> arms(L, L, H, N) // ┸
        0x2539 -> arms(H, L, H, N) // ┹
        0x253A -> arms(L, H, H, N) // ┺
        0x253B -> arms(H, H, H, N) // ┻
        0x253C -> arms(L, L, L, L) // ┼
        0x253D -> arms(H, L, L, L)
        0x253E -> arms(L, H, L, L)
        0x253F -> arms(H, H, L, L)
        0x2540 -> arms(L, L, H, L)
        0x2541 -> arms(L, L, L, H)
        0x2542 -> arms(L, L, H, H)
        0x2543 -> arms(H, L, H, L)
        0x2544 -> arms(L, H, H, L)
        0x2545 -> arms(H, L, L, H)
        0x2546 -> arms(L, H, L, H)
        0x2547 -> arms(H, H, H, L)
        0x2548 -> arms(H, H, L, H)
        0x2549 -> arms(H, L, H, H)
        0x254A -> arms(L, H, H, H)
        0x254B -> arms(H, H, H, H) // ╋
        0x2550 -> arms(D, D, N, N) // ═
        0x2551 -> arms(N, N, D, D) // ║
        0x2552 -> arms(N, D, N, L) // ╒
        0x2553 -> arms(N, L, N, D) // ╓
        0x2554 -> arms(N, D, N, D) // ╔
        0x2555 -> arms(D, N, N, L) // ╕
        0x2556 -> arms(L, N, N, D) // ╖
        0x2557 -> arms(D, N, N, D) // ╗
        0x2558 -> arms(N, D, L, N) // ╘
        0x2559 -> arms(N, L, D, N) // ╙
        0x255A -> arms(N, D, D, N) // ╚
        0x255B -> arms(D, N, L, N) // ╛
        0x255C -> arms(L, N, D, N) // ╜
        0x255D -> arms(D, N, D, N) // ╝
        0x255E -> arms(N, D, L, L) // ╞
        0x255F -> arms(N, L, D, D) // ╟
        0x2560 -> arms(N, D, D, D) // ╠
        0x2561 -> arms(D, N, L, L) // ╡
        0x2562 -> arms(L, N, D, D) // ╢
        0x2563 -> arms(D, N, D, D) // ╣
        0x2564 -> arms(D, D, N, L) // ╤
        0x2565 -> arms(L, L, N, D) // ╥
        0x2566 -> arms(D, D, N, D) // ╦
        0x2567 -> arms(D, D, L, N) // ╧
        0x2568 -> arms(L, L, D, N) // ╨
        0x2569 -> arms(D, D, D, N) // ╩
        0x256A -> arms(D, D, L, L) // ╪
        0x256B -> arms(L, L, D, D) // ╫
        0x256C -> arms(D, D, D, D) // ╬
        0x2574 -> arms(L, N, N, N) // ╴
        0x2575 -> arms(N, N, L, N) // ╵
        0x2576 -> arms(N, L, N, N) // ╶
        0x2577 -> arms(N, N, N, L) // ╷
        0x2578 -> arms(H, N, N, N) // ╸
        0x2579 -> arms(N, N, H, N) // ╹
        0x257A -> arms(N, H, N, N) // ╺
        0x257B -> arms(N, N, N, H) // ╻
        0x257C -> arms(L, H, N, N) // ╼
        0x257D -> arms(N, N, L, H) // ╽
        0x257E -> arms(H, L, N, N) // ╾
        0x257F -> arms(N, N, H, L) // ╿
        else -> 0
    }
}
