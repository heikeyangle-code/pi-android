package app.pi.bridge

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 动图（GIF / 动画 WebP / APNG）。
 *
 * 位图那条路只解第一帧，所以 URL 动图一直是静止的。这里在**取到字节时**嗅探是不是动图，
 * 是就把原始字节留在 [PiAnimatedImages]（小 LRU），[PiGuestImageTransformer.transform]
 * 看到有字节、且 API ≥ 28 就换成 [AnimatedDrawablePainter]；位图仍照常解（第一帧）当
 * 回退——API 26/27 或动图解码失败时画的就是它。
 */
internal object PiAnimatedImages {
    private const val MAX_ENTRIES = 6
    private const val MAX_TOTAL_BYTES = 24 * 1024 * 1024

    private val map = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {}

    fun remember(link: String, bytes: ByteArray) = synchronized(map) {
        if (bytes.size > MAX_TOTAL_BYTES / 2) return@synchronized
        map[link] = bytes
        var total = map.values.sumOf { it.size }
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || total > MAX_TOTAL_BYTES) && it.hasNext()) {
            val e = it.next()
            total -= e.value.size
            it.remove()
        }
    }

    fun get(link: String): ByteArray? = synchronized(map) { map[link] }

    /** GIF（含多帧）/ 带 ANIM 块的 WebP / 带 acTL 块的 PNG。单帧 GIF 也走动图路径，无害。 */
    fun isAnimated(b: ByteArray): Boolean {
        if (b.size < 16) return false
        fun at(i: Int, s: String) = i + s.length <= b.size && s.indices.all { b[i + it].toInt() == s[it].code }
        return when {
            at(0, "GIF8") -> true
            at(0, "RIFF") && at(8, "WEBP") -> indexOf(b, "ANIM", 12, 256) >= 0
            b[0] == 0x89.toByte() && at(1, "PNG") -> pngHasActl(b)
            else -> false
        }
    }

    /** 按 chunk 走：`acTL` 出现在第一个 `IDAT` 之前才是 APNG。O(chunk 数)，不逐字节扫。 */
    private fun pngHasActl(b: ByteArray): Boolean {
        var pos = 8
        while (pos + 8 <= b.size) {
            val len = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            if (len < 0) return false
            val t = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (t == "acTL") return true
            if (t == "IDAT" || t == "IEND") return false
            val next = pos.toLong() + 12L + len
            if (next > b.size) return false
            pos = next.toInt()
        }
        return false
    }

    private fun indexOf(b: ByteArray, s: String, from: Int, to: Int): Int {
        val end = minOf(to, b.size) - s.length
        var i = from
        while (i <= end) {
            var k = 0
            while (k < s.length && b[i + k].toInt() == s[k].code) k++
            if (k == s.length) return i
            i++
        }
        return -1
    }
}

/** 解成 [AnimatedImageDrawable]；超过像素预算会降采样，解不出来（或不是动图）返回 null。 */
internal fun decodeAnimated(bytes: ByteArray, maxWidthPx: Int): AnimatedImageDrawable? {
    if (Build.VERSION.SDK_INT < 28) return null
    return runCatching {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val d = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            var tw = if (maxWidthPx > 0) minOf(w, maxWidthPx) else w
            // 帧面积兜底：动图会常驻解码缓冲，限制在 ~2 MP。
            while (tw.toLong() * (h.toLong() * tw / w) > 2_000_000L && tw > 16) tw = tw * 3 / 4
            if (tw < w) decoder.setTargetSize(tw, (h.toLong() * tw / w).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        (d as? AnimatedImageDrawable)?.also { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
    }.getOrNull()
}

/** 把 [AnimatedImageDrawable] 接进 Compose：每帧 `invalidateDrawable` → 状态自增 → 重画。 */
internal class AnimatedDrawablePainter private constructor(private val drawable: AnimatedImageDrawable) : Painter() {
    companion object {
        /** 同时在动的动图上限：每个都是一条持续解码 + 每帧重画，长表格里几十张会把帧时间吃光。 */
        private const val MAX_ACTIVE = 4
        private val active = java.util.concurrent.atomic.AtomicInteger(0)

        /** 超过上限返回 null（调用方回退静态首帧）。 */
        fun tryCreate(drawable: AnimatedImageDrawable): AnimatedDrawablePainter? {
            if (active.incrementAndGet() > MAX_ACTIVE) {
                active.decrementAndGet()
                return null
            }
            return AnimatedDrawablePainter(drawable)
        }
    }

    private var released = false

    private var tick by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { tick++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, who, `when`)
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what, who)
        }
    }

    init { drawable.callback = callback }

    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    fun start() { if (!drawable.isRunning) drawable.start() }

    fun stop() {
        drawable.stop()
        handler.removeCallbacksAndMessages(drawable)
    }

    fun release() {
        if (released) return
        released = true
        stop()
        drawable.callback = null
        active.decrementAndGet()
    }

    override fun DrawScope.onDraw() {
        @Suppress("UNUSED_VARIABLE") val observed = tick
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/** 取（并在离开组合时停掉）一个动图 painter；非动图 / API < 28 / 解码失败返回 null。 */
@Composable
internal fun rememberAnimatedPainter(link: String, widthPx: Int): Painter? {
    val bytes = PiAnimatedImages.get(link) ?: return null
    if (Build.VERSION.SDK_INT < 28) return null
    val painter by produceState<AnimatedDrawablePainter?>(null, link, widthPx) {
        value = withContext(Dispatchers.Default) { decodeAnimated(bytes, widthPx) }
            ?.let { AnimatedDrawablePainter.tryCreate(it) }
    }
    val p = painter
    AnimatedPainterEffects(p)
    return p
}

/** 查看器等「字节在别处」的场景：后台取字节，是动图且解得出来才给 painter，离开组合时停掉。 */
@Composable
internal fun rememberAnimatedPainterFor(key: Any, widthPx: Int, loadBytes: () -> ByteArray?): Painter? {
    if (Build.VERSION.SDK_INT < 28) return null
    val painter by produceState<AnimatedDrawablePainter?>(null, key, widthPx) {
        value = withContext(Dispatchers.IO) {
            val bytes = loadBytes()?.takeIf { PiAnimatedImages.isAnimated(it) } ?: return@withContext null
            decodeAnimated(bytes, widthPx)?.let { AnimatedDrawablePainter.tryCreate(it) }
        }
    }
    val p = painter
    AnimatedPainterEffects(p)
    return p
}

/** 开始 / 释放，并跟随生命周期：切到后台暂停（不再空转解码与重画），回前台继续。 */
@Composable
private fun AnimatedPainterEffects(p: AnimatedDrawablePainter?) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(p, owner) {
        if (p == null) return@DisposableEffect onDispose {}
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> p.start()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> p.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) p.start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            p.release()
        }
    }
}
