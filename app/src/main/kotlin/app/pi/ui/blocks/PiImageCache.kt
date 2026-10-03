package app.pi.ui.blocks

import android.graphics.Bitmap

/**
 * The process-wide cache of **decoded transcript images** — the pictures the transcript
 * itself draws: a user's attachment, a tool's screenshot, a `read` of a picture. (The
 * *markdown* image channel has its own cache in `bridge/PiGuestImageTransformer.kt`,
 * keyed on a link; this one is for inline base64 payloads.)
 *
 * ## The defect this exists for
 *
 * `ImageGridBlock` and `PiImageViewer` decode with `produceState`, which is state of a
 * *composition*: a row the `LazyColumn` disposes takes its decoded bitmap with it. So
 * every time a picture scrolled out and came back — or a viewer was closed and reopened
 * — the same bytes were base64-decoded and handed to `BitmapFactory` again, under the
 * two-permit [piImageDecodeGate], while the frame that was scrolling waited for the
 * cheap path to finish. There was no cache anywhere on this path
 * (`grep -rn "LruCache\|bitmapCache" app/src/main/kotlin` found none).
 *
 * ## What is cached, and what is not
 *
 *  - **Only successful decodes.** A payload the platform codec refuses is re-tried the
 *    next time it is composed, exactly as before: a failure can be a decode that ran out
 *    of memory, and a negative cache would make one transient refusal permanent. This is
 *    the same rule `PiGuestImageTransformer` states for a missing file.
 *  - **Both boxes.** The key carries the requested width and height, so a grid cell's
 *    downsample and the viewer's window-sized decode are separate entries. They are
 *    different resolutions for different surfaces, so a hit from one may not serve the
 *    other. What this cache stores is still **one decode per (bytes, box)** — the viewer's
 *    box is the whole window, and that entry stays the 1× picture; the sharper block the
 *    zoom path decodes is *not* here (see below).
 *
 * ## What is deliberately *not* in here: the zoom block
 *
 * 放大之后查看器会按源图坐标再解一块（`PiZoomDecodeWindow` / `decodePiImageRegion`），那块位图
 * **不进这个缓存**，理由有三条，每条都会在别处变成代价：
 *
 *  - 它的身份是「这几个字节 + 源图上这个矩形 + 倍率」，而不是「这几个字节」；放进这张按
 *    payload 计费的键里，就得把矩形也塞进键（键立刻变复杂），而它随平移换掉、命中率极低。
 *  - 它有一个**恒定**的字节上界（`DETAIL_MAX_PIXELS` = 2 000 000 像素 = 8 000 000 B），与源图
 *    多大无关，所以它不需要这张缓存的字节预算来兜底；真正需要兜底的是「一张图只解一次」这件事。
 *  - 塞进来还要**多留一份几 MB 的 base64 键** —— [ByteBoundedLru] 把键的字节也算进去（见下），
 *    于是为了缓存一块 4 MB 的位图，得让一张照片的 payload 一直活着。查看器自己拿着那块（一个
 *    `remember`），缩回 1× 时当场丢掉，这才是它该有的寿命。
 *
 * [MAX_TOTAL_BYTES] 因此**不变**（32 MiB）：这次改动没有往这张缓存里多放任何东西，1× 的常驻
 * 内存与之前逐字节相同（查看器在 1× 时一块细节都不拿）。
 *  - **The payload is charged to the entry.** The key holds the base64 string, so the
 *    cache keeps those megabytes alive; [ByteBoundedLru] is told to count them
 *    (`payload.length * 2`) on top of the bitmap's own `allocationByteCount`. Without
 *    that the bound would hide half of what the cache costs.
 *  - **A host file too, keyed by the file.** The viewer also draws workspace images,
 *    whose bytes are on disk rather than in a payload (`PiImageViewerSource.HostFile`);
 *    its key is `file:<路径>:<字节数>:<mtime>` — the same "these exact bytes" identity,
 *    spelled by the file. Reopening the same picture costs no read and no decode, and an
 *    edited file is a different key, so a stale decode cannot be served for it. A `file:`
 *    key can never collide with a payload: `:` is not in base64's alphabet. Such a key is
 *    tens of bytes, so it adds nothing worth accounting for.
 *
 * ## Bound, and why it is this number
 *
 * [MAX_TOTAL_BYTES] is **32 MiB**, the same bound the app's other bitmap cache uses
 * (`PiGuestImageTransformer.MAX_TOTAL_BYTES`), so the two cannot disagree about how much
 * of the process's memory pictures may hold. Because payloads are charged too, that is a
 * *small* number of entries rather than a large one: a 2.8 M-character base64 screenshot
 * costs ~5.6 MB of key plus a few MB of bitmap, so a couple of pictures fit and the
 * least recently used one is evicted. That is the honest trade for keying on the payload
 * itself; the alternative — hashing the payload to shrink the key — costs 22.8 ms per
 * 4 MiB on the frame thread (`docs/scroll-perf-items.md` §2.2), which is the very thing
 * this cache exists to avoid.
 *
 * ## Lifetime
 *
 * A process-wide `object` holding only `Bitmap`s and payload strings: no `Context`, no
 * `View`, nothing Activity-scoped, so an Activity recreation leaks nothing. Entries are
 * evicted by the byte bound alone — no timer, no trim callback, and therefore nothing to
 * leak if the process never receives one. [clear] exists for a caller that wants to drop
 * the lot (a future `onTrimMemory` hook would be the natural owner); nothing calls it
 * today, and nothing needs to: the cache is self-bounding.
 *
 * The eviction rule and the accounting are pinned by the bare-JVM harness
 * `app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt`; its registration line for
 * `tools/run-app-pure-checks.sh` (`pi-image-cache`) is in
 * `docs/scroll-perf-items.md` §2.5 and is **not** applied yet — that script belongs to
 * another batch this window.
 */
internal object PiImageCache {

    /**
     * How much the decoded pictures may hold, in bytes, keys included. See the KDoc for
     * why it matches `PiGuestImageTransformer`.
     */
    internal const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024

    /**
     * The payload, plus the box it was decoded for.
     *
     * `equals` compares the payload **without hashing it** — the map that uses this key
     * never calls `hashCode`, so an identity hit is one reference comparison and a
     * re-created but equal payload is one `memcmp` over its characters. [hashCode] is
     * still implemented (the language requires the contract) but is deliberately built
     * from the box alone: it is O(1), never O(payload), and equal keys always have equal
     * hashes.
     */
    private class Key(val payload: String, val width: Int, val height: Int) {
        val bytes: Long = payload.length.toLong() * 2

        override fun equals(other: Any?): Boolean = other is Key &&
            width == other.width &&
            height == other.height &&
            payload == other.payload

        override fun hashCode(): Int = 31 * width + height
    }

    private val lru = ByteBoundedLru<Key, Bitmap>(MAX_TOTAL_BYTES) { key, bitmap ->
        key.bytes + bitmap.allocationByteCount.toLong()
    }

    /** The decode this payload already has for this box, or null. */
    fun cached(payload: String, width: Int, height: Int): Bitmap? =
        lru.get(Key(payload, width, height))

    /** Remembers a successful decode. Never called with a failure. */
    fun store(payload: String, width: Int, height: Int, bitmap: Bitmap) {
        lru.put(Key(payload, width, height), bitmap)
    }

    /**
     * Reads a cached decode or produces one with [decode], which is only invoked on a miss.
     *
     * **Callers run this on a background dispatcher** (`ImageGridBlock` and `PiImageViewer`
     * wrap it in `withContext(Dispatchers.IO)`): an equal-but-not-identical payload costs a
     * `memcmp` over megabytes, and a miss costs a decode — neither belongs on the frame
     * thread. A hit therefore reaches the UI one main-thread hop after composition instead
     * of on the first frame: the same `CellImage.Pending` first frame the cell always drew,
     * now followed by a bitmap that costs no decode.
     *
     * `null` means "these bytes are not a picture"; a failure is not cached, so the next
     * composition tries again.
     */
    suspend fun readThrough(
        payload: String,
        width: Int,
        height: Int,
        decode: suspend () -> Bitmap?,
    ): Bitmap? {
        cached(payload, width, height)?.let { return it }
        val decoded = decode() ?: return null
        store(payload, width, height, decoded)
        return decoded
    }

    /** Drops every entry. Nothing calls it today; see the KDoc on lifetime. */
    fun clear() {
        lru.clear()
    }
}
