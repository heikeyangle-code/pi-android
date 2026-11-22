package app.pi.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import app.pi.ui.blocks.piImageDecodeGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded image payload handed to Pi's native Markdown image renderer.
 */
internal data class PiImageData(
    val painter: Painter,
    val modifier: Modifier = Modifier.fillMaxWidth(),
    val contentScale: ContentScale = ContentScale.Fit,
    val contentDescription: String? = null,
    /** Intrinsic `width / height` ratio for stable first-frame sizing. */
    val widthOverHeight: Float? = null,
)

/**
 * Decoupled image loader contract for Pi's native Markdown renderer (zero third-party
 * markdown library types).
 */
internal interface PiImageLoader {
    @Composable
    fun transform(link: String): PiImageData?

    /** Remembered `height / width` ratio for stable table/inline placeholder sizing. */
    fun rememberedHeightOverWidth(link: String): Float? = null
}

internal object PiNoOpImageLoader : PiImageLoader {
    @Composable
    override fun transform(link: String): PiImageData? = null
}

/**
 * Resolves markdown image links (`data:`, guest paths, `http(s)`) into viewport-sampled
 * bitmaps (or animated painters for GIF/WebP) with bounded 32 MiB process-wide LRU caching,
 * request deduplication, 45 s negative-cache backoff, and remembered aspect ratios.
 */
internal class PiGuestImageTransformer(
    private val context: Context,
    private val boxWidthPx: Int? = null,
) : PiImageLoader {

    @Composable
    override fun transform(link: String): PiImageData? {
        val widthPx = boxWidthPx ?: LocalWindowInfo.current.containerSize.width
        val bitmap by produceState<Bitmap?>(initialValue = cached(link, widthPx), link, widthPx) {
            if (value == null) value = load(link, widthPx)
        }
        val image = bitmap ?: return null
        val animated = rememberAnimatedPainter(link, widthPx)
        val ratio = if (image.width > 0 && image.height > 0) {
            image.width.toFloat() / image.height.toFloat()
        } else {
            null
        }
        return PiImageData(
            painter = animated ?: BitmapPainter(image.asImageBitmap()),
            modifier = fillWidthModifier,
            contentScale = ContentScale.Fit,
            contentDescription = link,
            widthOverHeight = ratio,
        )
    }

    override fun rememberedHeightOverWidth(link: String): Float? = rememberAspect(link)

    private suspend fun load(link: String, widthPx: Int): Bitmap? {
        val key = PiImageRequestKey(link, widthPx)
        val outcome = requestPolicy.load(key) { requested -> loadUnshared(requested) }
        val bitmap = outcome.getOrNull()
        if (bitmap == null) {
            when (val failure = outcome.exceptionOrNull()) {
                is PiImageNegativeCacheHit -> GuestImageBytes.noteFailure(
                    failure.message ?: "远端图片最近失败过，这次不出网",
                )
                is PiImageProducerTimeout -> GuestImageBytes.noteFailure(
                    failure.message ?: "图片加载超过兜底预算，这次没画出来",
                )
                else -> Unit
            }
        }
        return bitmap
    }

    private suspend fun loadUnshared(key: PiImageRequestKey): Result<Bitmap> {
        val loaded = withContext(Dispatchers.IO) { GuestImageBytes.load(context, key.link) }
            ?: return Result.failure(
                IllegalStateException(GuestImageBytes.lastFailure ?: "读取图片字节失败（没有留下原因）"),
            )
        val decoded = withContext(Dispatchers.Default) {
            piImageDecodeGate.withPermit { decode(loaded.bytes, key.widthPx) }
        } ?: return Result.failure(IllegalStateException("图片解码失败：${loaded.source}"))
        if (PiAnimatedImages.isAnimated(loaded.bytes)) PiAnimatedImages.remember(key.link, loaded.bytes)
        store(key, decoded)
        return Result.success(decoded)
    }

    private fun decode(bytes: ByteArray, availableWidthPx: Int): Bitmap? {
        if (GuestImageBytes.looksLikeSvg(bytes)) return decodeSvg(bytes, availableWidthPx)
        return decodeRaster(bytes, availableWidthPx)
    }

    private fun decodeSvg(bytes: ByteArray, availableWidthPx: Int): Bitmap? =
        rasterizeSvg(bytes, availableWidthPx)

    private fun decodeRaster(bytes: ByteArray, availableWidthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        if (availableWidthPx <= 0) return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val boxWidth = availableWidthPx
        val boxHeight = (boxWidth.toLong() * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
        var width = bounds.outWidth
        var height = bounds.outHeight
        var sampleSize = 1
        while (width / 2 >= boxWidth && height / 2 >= boxHeight) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        val budget = boxWidth.toLong() * boxHeight.toLong() * DECODE_AREA_BUDGET_FACTOR
        while (width >= 2 && height >= 2 && width.toLong() * height > budget) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull()

    companion object {
        internal fun clearBitmapCache() {
            synchronized(cache) { cache.clear() }
        }

        private val fillWidthModifier = Modifier.fillMaxWidth()
        private const val MAX_ENTRIES = 64
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        private const val MAX_ASPECT_ENTRIES = 128
        private const val DECODE_AREA_BUDGET_FACTOR = 4L

        private val aspectRatios = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>): Boolean =
                size > MAX_ASPECT_ENTRIES
        }

        private fun rememberAspect(link: String): Float? = synchronized(aspectRatios) { aspectRatios[link] }

        private fun recordAspect(link: String, bitmap: Bitmap) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
            synchronized(aspectRatios) { aspectRatios[link] = aspect }
        }

        private val requestPolicy = PiImageRequestPolicy<PiImageRequestKey, Bitmap>()
        private val cache = LinkedHashMap<PiImageRequestKey, Bitmap>(16, 0.75f, true)

        private fun cached(link: String, widthPx: Int): Bitmap? =
            synchronized(cache) { cache[PiImageRequestKey(link, widthPx)] }

        private fun store(key: PiImageRequestKey, bitmap: Bitmap) {
            recordAspect(key.link, bitmap)
            synchronized(cache) {
                cache[key] = bitmap
                var total = cache.values.sumOf { it.allocationByteCount.toLong() }
                val iterator = cache.entries.iterator()
                while ((cache.size > MAX_ENTRIES || total > MAX_TOTAL_BYTES) && iterator.hasNext()) {
                    val entry = iterator.next()
                    total -= entry.value.allocationByteCount.toLong()
                    iterator.remove()
                }
            }
        }
    }
}

@Composable
internal fun rememberPiGuestImageTransformer(): PiImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { PiGuestImageTransformer(context) }
}

@Composable
internal fun rememberPiGuestImageTransformer(widthPx: Int): PiImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context, widthPx) { PiGuestImageTransformer(context, widthPx) }
}
