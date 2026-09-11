package app.pi.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The renderer's image seam, filled in with real bytes.
 *
 * ## NOT WIRED YET — nothing calls [rememberPiGuestImageTransformer]
 *
 * `ui/render` still provides `NoOpImageTransformerImpl`, and it provides it *inside*
 * `PiMarkdownText`, so an outer provider could not win. Until that line changes, A3
 * renders exactly what it renders today (alt + source, or nothing). The two-line
 * wiring is written out in `GuestImageBytes`'s class comment as P-A / P-B — read it
 * before assuming images work.
 *
 * `multiplatform-markdown-renderer` asks an [ImageTransformer] for a `Painter`
 * per `link` (`model/ImageTransformer.kt:16-21`), and its default
 * `NoOpImageTransformerImpl.transform` returns `null`, which makes
 * `MarkdownImage` draw nothing at all (`compose/elements/MarkdownImage.kt:17-29`).
 * This class answers with a [BitmapPainter] decoded from [GuestImageBytes], so
 * both image paths in the library start working: the block component the app
 * already overrides (`ui/render/PiMarkdownComponents.kt:194-225`) delegates to
 * `MarkdownImage` once the injected transformer is not the no-op, and inline
 * images go straight to `LocalImageTransformer`
 * (`compose/elements/MarkdownInlineImage.kt:15-27`).
 *
 * The seam is a **composition local**, and this class is deliberately not the
 * one that installs itself: the installation is one line in `ui/render`, which
 * owns the renderer. The companion factory
 * [rememberPiGuestImageTransformer] is what that line calls.
 *
 * ## Threading
 *
 * `ImageTransformer.transform` is `@Composable`, so the read cannot happen in it
 * directly. It uses `produceState`, which runs the load in the composition's
 * coroutine scope: bytes on [Dispatchers.IO], decode on [Dispatchers.Default]
 * (CPU work, and a full-resolution photo is tens of milliseconds). The first
 * frame of an image is therefore empty and the next one has it — the same shape
 * `ui/blocks/ImageGridBlock.kt` uses for the inline `PiImage` bytes, so both
 * image surfaces behave alike.
 *
 * ## Failure is `null`, never a wrong picture
 *
 * A link that cannot be resolved returns `null`, which is exactly what the no-op
 * transformer returned. That is deliberate: a decoded-but-wrong image is worse
 * than a placeholder, so a file that does not exist, a body that is not an image,
 * or a body larger than `GuestImageBytes.MAX_BYTES` all end as `null` with a
 * reason in `GuestImageBytes.lastFailure`.
 *
 * Because `transform` returning `null` makes the library drop the whole node, the
 * text fallback ("the alt text and where it pointed") only stays reachable if the
 * renderer keeps it for a *failed* link as well as for the no-op default. That is
 * a one-line-shaped change in `ui/render/PiMarkdownComponents.kt` and is reported
 * as a patch rather than made here.
 */
internal class PiGuestImageTransformer(private val context: Context) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        // `initialValue` is a cache read, so a transcript scrolled away from and
        // back into view paints the image on its very first frame.
        val bitmap by produceState<Bitmap?>(initialValue = cached(link), link, context) {
            if (value == null) value = load(link)
        }
        val image = bitmap ?: return null
        return ImageData(
            painter = BitmapPainter(image.asImageBitmap()),
            // `MarkdownImage` prefers the image's alt text when it has one, so this
            // is only the fallback accessible name.
            contentDescription = link,
        )
    }

    private suspend fun load(link: String): Bitmap? {
        val loaded = withContext(Dispatchers.IO) { GuestImageBytes.load(context, link) } ?: return null
        val decoded = withContext(Dispatchers.Default) { decode(loaded.bytes) } ?: return null
        store(link, decoded)
        return decoded
    }

    /**
     * `Bitmaps` are cached, not `ImageBitmap`s, for one reason: only the platform
     * bitmap reports `allocationByteCount`, which is what bounds the cache.
     * `asImageBitmap()` wraps without copying, so the conversion is free.
     */
    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private companion object {

        /**
         * A transcript can hold many images and each one is a decoded bitmap, so the
         * cache is bounded twice: by count and by the total decoded size. Without the
         * byte bound, two dozen 48-megapixel frames would be hundreds of megabytes in
         * a process that also runs the engine.
         */
        private const val MAX_ENTRIES = 24
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

        /**
         * Access-ordered, so re-reading an old image moves it to the youngest slot and
         * the eviction below always drops the least recently used one.
         */
        private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

        /**
         * Failures are **not** cached. A missing file can appear later (the agent
         * may be writing it in the same turn) and an unreachable URL can come back,
         * so a negative cache would turn one transient failure into a permanently
         * blank image. Nothing re-fetches in a loop either: `produceState` keys on
         * the link, so an unchanged link is loaded once per composition.
         */
        private fun cached(link: String): Bitmap? = synchronized(cache) { cache[link] }

        /**
         * Both bounds are enforced here rather than by overriding
         * `removeEldestEntry`, so the eviction rule is one readable loop: drop the
         * least recently used entry until the cache is inside *both* limits.
         */
        private fun store(link: String, bitmap: Bitmap) {
            synchronized(cache) {
                cache[link] = bitmap
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

/**
 * What `ui/render` installs, one line instead of a constructor call:
 *
 *     LocalPiImageTransformer provides rememberPiGuestImageTransformer()
 *
 * The context is the *application* one on purpose — the composition local outlives
 * an Activity recreation (and the bitmap cache is process-wide), so holding an
 * Activity here would leak it for no benefit.
 */
@Composable
internal fun rememberPiGuestImageTransformer(): ImageTransformer {
    val context = LocalContext.current.applicationContext
    return remember(context) { PiGuestImageTransformer(context) }
}
