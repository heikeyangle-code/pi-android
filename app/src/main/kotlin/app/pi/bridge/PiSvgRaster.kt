package app.pi.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG

/**
 * SVG 字节 → 位图（AndroidSVG 静态栅格化）。正文图片和图片查看器共用这一份。
 *
 * [targetWidthPx] ≤ 0 时用 SVG 自身宽度；上限 2048 × 4096，> 2 MiB 的文档不画。
 * [allowUpscaleFactor] 限制小图（徽章）最多放大几倍。
 */
internal fun rasterizeSvg(bytes: ByteArray, targetWidthPx: Int, allowUpscaleFactor: Float = 2f): Bitmap? = runCatching {
    if (bytes.size > 2 * 1024 * 1024) return@runCatching null
    val svg = SVG.getFromString(String(bytes, Charsets.UTF_8).replace("auto-start-reverse", "auto"))
    val viewBox = svg.documentViewBox
    val w = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
        ?: viewBox?.width()?.takeIf { it > 0f } ?: 300f
    val h = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
        ?: viewBox?.height()?.takeIf { it > 0f } ?: 150f
    if (viewBox == null || viewBox.width() <= 0f || viewBox.height() <= 0f) svg.setDocumentViewBox(0f, 0f, w, h)
    val target = (if (targetWidthPx > 0) minOf(targetWidthPx.toFloat(), w * allowUpscaleFactor) else w)
        .coerceIn(16f, 2048f)
    val scale = target / w
    val outW = target.toInt().coerceAtLeast(1)
    val outH = (h * scale).toInt().coerceIn(1, 4096)
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, outW.toFloat(), outH.toFloat()))
    bitmap
}.getOrNull()
