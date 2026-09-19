package app.pi.ui.blocks

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic of a transcript image's box: how tall a lone picture may be, the shape
 * its own bytes declare, the pixels that actually get drawn, and how many decodes may
 * run at once.
 *
 * ## Why this is a separate, Android-free file
 *
 * `ImageGridBlock.kt` is a Compose file, and Compose cannot be compiled on this machine
 * at all (`tools/typecheck.sh` does not run the Compose compiler plugin, and no APK can
 * be built here — Google ships AAPT2 as an x86-64 binary). Anything that only exists
 * inside a composable can therefore be verified **on a phone only**. The row-height rule
 * below is the one the user reported twice, so it lives where a bare JVM can execute it:
 * `tools/run-app-pure-checks.sh` → `image-size`. Everything here is bytes, integers and
 * floats — a header parser, a cap, and two boxes.
 *
 * ## What else lives here, and why
 *
 * The file is also the module's home for the **Android-free, JVM-verifiable** helpers the
 * transcript's caches and counters are built from, because a class that references
 * Android or Compose cannot be compiled by a bare-JVM harness and would therefore need a
 * phone to test:
 *
 *  - [ByteBoundedLru], the bounded map both image and text caches use;
 *  - [TextMemo], a typed memo of `String -> String` parses (the cross-composition cache
 *    behind `ParseCaches`);
 *  - [IncrementalLineCount], the append-only line counter the streaming tool footer uses.
 *
 * None of them knows anything about pictures; they are here so the same harness can
 * execute them.
 *
 * ## The rule the whole file exists for
 *
 * **A single image's row has its final height before its bitmap exists.** That height is
 * [singleImageBoxHeightPx] of the payload's own header ([naturalImageAspect] —
 * O(prefix), synchronous) and the cap ([SINGLE_IMAGE_MAX_HEIGHT_FRACTION]); nothing
 * about the row is read off the decoded bitmap. The old cell sized its box from
 * `decoded.width / decoded.height` and fell back to `Modifier.fillMaxSize()` until the
 * decode returned, so the row changed height once per picture — the reported
 * 「图片会从底部直接闪现到全面露出来」. Sizing from the header makes that a property of
 * the arithmetic rather than a hope about decode timing.
 *
 * The header is cheap because the four container formats with a fixed prologue (png,
 * gif, webp, bmp) put the size in their first ~30 bytes, and base64 decodes in order:
 * [base64PrefixBytes] gives us those bytes without touching the rest of a payload that
 * is routinely megabytes long. A JPEG is the one format whose size can sit behind
 * arbitrary metadata (an EXIF thumbnail is itself a JPEG), which is why it gets a second,
 * larger budget — [HEADER_JPEG_SEARCH_BYTES] — and a marker walk instead of a field read.
 *
 * ## Which formats have to be understood, and why exactly those
 *
 * The five the app can ever put on screen: png, jpeg, gif, webp, bmp. That set is not a
 * guess — it is pi's inline set (`image-process.ts:33-47`, transcribed in
 * `AttachmentBudget.piInlineSupported`, and everything else is converted to png before it
 * is sent, `:49-65`), and it is the set the bridge sniffs by magic bytes
 * (`bridge/GuestImageBytes.kt:250-283`, from `docs/pi-android-app-design.md:507`). An
 * image whose header is *not* one of these five gets
 * [SINGLE_IMAGE_FALLBACK_ASPECT] — see that constant for the cost, stated rather than
 * hidden.
 */

/**
 * How tall a **single** transcript image may be, as a fraction of the window.
 *
 * The cap exists for the extreme case the user named: a long screenshot (1:5 or worse) at
 * its natural ratio would take several screens and bury the conversation around it. Past
 * the cap the picture stops growing and `ContentScale.Fit` shows it whole and small,
 * which is 看个大概 — and the tap target is unchanged, so the full view ([PiImageViewer])
 * is one tap away.
 *
 * **0.35, and the number is the user's**: 「占大半个屏幕了都。让它再缩小 1/3~1/2。美观
 * 一点」 against the 0.6 this replaced. It is a fraction and not a `Dp` so that a phone in
 * landscape (or a tablet) scales with its own height instead of keeping a portrait
 * ceiling.
 *
 * It is stated here, in the pure file, because the harness pins it: this constant is the
 * *only* handle the user has on how big a transcript picture is, and it has already moved
 * once by request. A silent re-tune is a user-visible regression that no other check in
 * this repository would see.
 */
internal const val SINGLE_IMAGE_MAX_HEIGHT_FRACTION = 0.35f

/**
 * The shape a single image commits to when [naturalImageAspect] cannot read its header
 * (4:3).
 *
 * **Why a commitment and not a wait.** A row that is sized after its bitmap arrives is
 * the defect; if the header is unreadable the box still has to be known *now*, so this is
 * the shape it gets — and it keeps it. The picture is drawn with `ContentScale.Fit` inside
 * it, so the whole image is always visible: a wider picture gets bars on top and bottom, a
 * taller one bars on the left and right. The cost is real and is the price of the row not
 * jumping, which is what was asked for; it is confined to a payload that is either not a
 * picture at all (undecodable bytes, where the cell shows its labelled fallback) or a
 * format outside pi's five, which `AttachmentBudget.piInlineSupported` and
 * `bridge/GuestImageBytes.kt`'s sniffer both say cannot reach this cell.
 *
 * 4:3 specifically: it is the shape of an ordinary phone photo, so when this path *is*
 * reached by a real camera image it is the least wrong guess for the picture's own shape.
 */
internal const val SINGLE_IMAGE_FALLBACK_ASPECT = 4f / 3f

/**
 * How many image decodes may be in flight at once, process-wide.
 *
 * **Why a bound at all.** `Dispatchers.IO` runs up to 64 blocking calls in parallel, and
 * every visible cell used to start its own decode the moment it entered composition. On a
 * fast fling that is a burst of full-resolution screenshot decodes — tens of megabytes —
 * plus the base64 decode and the allocator work for each, all competing with the frame
 * that is trying to scroll. The user's report was 「往上滑有图片的时候不流畅」. Two is the
 * bound: a phone screen shows at most a couple of newly-entered pictures per frame, and a
 * queue that never grows longer than the visible cells cannot starve the one the user is
 * looking at. Both the cell and the viewer go through the same gate
 * ([piImageDecodeGate]) so opening the viewer mid-scroll does not add a decode on top of
 * the cells'.
 */
internal const val MAX_CONCURRENT_IMAGE_DECODES = 2

/**
 * Bytes needed for the fixed-prologue formats: png (24), gif (10), bmp (26), webp (30).
 *
 * Deliberately one buffer for all four — the biggest of them plus slack — because the
 * point is a single cheap read for the common case, and 32 bytes of base64 is 44
 * characters.
 */
internal const val HEADER_PROLOGUE_BYTES = 32

/**
 * The second budget, spent only when the prologue says "jpeg" and did not contain the
 * frame header.
 *
 * A camera JPEG carries an EXIF segment, and an EXIF segment carries a thumbnail: the
 * `SOF` marker that states the size therefore sits *behind* data of an unbounded length
 * (a few kilobytes in practice, and the segment's own 16-bit length caps it at 64 KiB+2).
 * 64 KiB of bytes is 88 Ki base64 characters, so the search is still O(prefix) and not
 * O(payload) — the point of decoding a prefix at all.
 */
internal const val HEADER_JPEG_SEARCH_BYTES = 64 * 1024

/**
 * The largest side [readImageHeader] will believe, in pixels.
 *
 * A guard against a *misread* header, not against a big picture: a plausible long
 * screenshot is 100k px tall, far under this. Without it, a byte sequence that matches one
 * format's shape but not its meaning (say a `BM` that is not a bitmap) could yield a
 * nonsense ratio, and a nonsense ratio is a nonsense row.
 */
private const val MAX_PLAUSIBLE_DIMENSION = 1 shl 20

/** Pixel width and height, as an encoded image's own header states them. */
internal data class ImagePixels(val width: Int, val height: Int)

/**
 * The picture's shape from its header alone: width / height, or null when the bytes are
 * not one of pi's five formats (or are truncated inside the prologue).
 *
 * Called during composition, so its cost is the whole story: the prologue read is 44
 * base64 characters for the four fixed formats, and only a JPEG pays for the bigger walk.
 * The `remember(image.base64)` at the call site keys on the payload *string identity* —
 * Compose compares keys with `equals`, and `String.equals` short-circuits on identity — so
 * a recomposition of the same item costs nothing while a re-created but equal payload
 * costs the prologue again. The obvious alternative, a process-wide LRU keyed on the
 * payload, was rejected: `String.hashCode()` is O(payload) on its first call, which would
 * put a multi-megabyte hash (milliseconds) back on the frame that is scrolling.
 */
internal fun naturalImageAspect(base64: String): Float? {
    if (base64.isEmpty()) return null
    val prologue = base64PrefixBytes(base64, HEADER_PROLOGUE_BYTES)
    readImageHeader(prologue)?.let { return aspectOf(it) }
    // A JPEG whose frame header is behind its EXIF thumbnail is the one case the prologue
    // can fail on while the payload is fine. Anything else that failed here has already
    // answered "not a picture I understand".
    if (!isJpegStart(prologue)) return null
    if (base64.length <= base64CharsFor(HEADER_PROLOGUE_BYTES)) return null
    val search = base64PrefixBytes(base64, HEADER_JPEG_SEARCH_BYTES)
    return readImageHeader(search)?.let { aspectOf(it) }
}

/**
 * The final height of a **single** image's box, in pixels: the picture's height at the
 * available width, capped at [capPx].
 *
 * This is the one height the row ever has. Its inputs are the width the layout already
 * knows, the ratio the header already stated and a constant — so the value is identical on
 * every frame, before and after the bitmap lands. Returns 0 for an input that cannot
 * describe a box (a non-positive width or cap, a non-finite or non-positive ratio), which
 * the cell can only reach for a payload it will draw as a placeholder anyway.
 */
internal fun singleImageBoxHeightPx(widthPx: Int, aspect: Float, capPx: Int): Int {
    if (widthPx <= 0 || capPx <= 0 || !aspect.isFinite() || aspect <= 0f) return 0
    val natural = (widthPx.toFloat() / aspect).roundToInt()
    return if (natural > capPx) capPx else natural
}

/**
 * The pixels that will actually be drawn when `aspect` is fitted inside a box of
 * [boxWidthPx] × [boxHeightPx] — `ContentScale.Fit`'s own arithmetic, and therefore the
 * honest size to hand `decodePiImage`.
 *
 * **Why the decode is sampled to this and not to the box.** A box is a card; the picture
 * inside it can be much smaller (a 1:5 screenshot in a full-width card is a narrow column
 * in the middle). Sampling to the box decodes four times the pixels that get drawn for
 * exactly the shapes the cap exists for, which is the memory the sampling was added to
 * save. A non-positive or non-finite `aspect` means "unknown": the box is returned
 * unchanged, which is the previous behaviour.
 */
internal fun fitBoxPx(boxWidthPx: Int, boxHeightPx: Int, aspect: Float): ImagePixels {
    if (boxWidthPx <= 0 || boxHeightPx <= 0 || !aspect.isFinite() || aspect <= 0f) {
        return ImagePixels(max(1, boxWidthPx), max(1, boxHeightPx))
    }
    val scale = min(boxWidthPx.toFloat() / aspect, boxHeightPx.toFloat())
    return ImagePixels(
        width = (aspect * scale).roundToInt().coerceIn(1, boxWidthPx),
        height = scale.roundToInt().coerceIn(1, boxHeightPx),
    )
}

/**
 * Decodes at most [maxBytes] bytes from the head of a **standard** base64 payload.
 *
 * Android's `Base64.decode(s, Base64.DEFAULT)` — what `decodePiImage` uses on the whole
 * payload — ignores line breaks and stops at the padding; this has to agree with it on
 * everything a payload may contain, or the header would be read from the wrong offsets:
 * whitespace is skipped, `=` ends the data, and a character outside the alphabet ends the
 * data too (the platform decoder would have thrown on it, and `decodePiImage` turns that
 * into the placeholder — the same verdict, reached earlier and without the allocation).
 *
 * Truncated input is the *normal* case here, not an error: the tail of the last group is
 * simply dropped. Never throws.
 */
internal fun base64PrefixBytes(text: String, maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    val out = ByteArray(maxBytes)
    var written = 0
    var buffer = 0
    var bits = 0
    for (character in text) {
        val value = base64Value(character)
        if (value < 0) {
            if (character == '=' || !character.isWhitespace()) break
            continue
        }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[written++] = ((buffer shr bits) and 0xFF).toByte()
            if (written == maxBytes) return out
        }
    }
    return if (written == out.size) out else out.copyOf(written)
}

/**
 * The size a `BitmapFactory`-decodable payload states about itself, read from [bytes].
 *
 * Header only: no pixels are needed and none of these formats keep the size anywhere but
 * in the prologue (or, for JPEG, in the frame header), so the caller can hand a truncated
 * prefix. Null means "not one of pi's five, or the header is not in what I was given" —
 * never a guess.
 */
internal fun readImageHeader(bytes: ByteArray): ImagePixels? = when {
    // png: 8-byte signature, then the IHDR chunk's width/height as big-endian int32
    // (16 and 20 — the chunk type is at 12, which is also checked so a stray signature
    // cannot be read as an IHDR).
    bytes.size >= 24 && u8(bytes, 0) == 0x89 && u8(bytes, 1) == 0x50 &&
        u8(bytes, 2) == 0x4E && u8(bytes, 3) == 0x47 && fourCc(bytes, 12) == "IHDR" ->
        plausible(be32(bytes, 16), be32(bytes, 20))

    // gif: the logical screen descriptor, little-endian uint16 at 6 and 8. This is the
    // canvas, which is the size `BitmapFactory` returns for the first frame; a frame that
    // is smaller than the canvas is drawn inside it by the decoder either way. The
    // signature is six characters — the one sniff in this app that is not four
    // (`bridge/GuestImageBytes.kt:277`) — so it is compared whole.
    bytes.size >= 10 && String(bytes, 0, 6, Charsets.US_ASCII) in GIF_SIGNATURES ->
        plausible(le16(bytes, 6), le16(bytes, 8))

    bytes.size >= 26 && u8(bytes, 0) == 0x42 && u8(bytes, 1) == 0x4D -> bmpSize(bytes)

    bytes.size >= 22 && fourCc(bytes, 0) == "RIFF" && fourCc(bytes, 8) == "WEBP" -> webpSize(bytes)

    bytes.size >= 6 && isJpegStart(bytes) -> jpegSize(bytes)

    else -> null
}

/**
 * Bounds how many image decodes may run at once — see [MAX_CONCURRENT_IMAGE_DECODES].
 *
 * The counter is a `Semaphore` and not a thread pool because the work it guards is a
 * suspendable blocking call: a cell that scrolls away is cancelled while it waits (or
 * while it decodes), and `withPermit` releases on cancellation, so a fling cannot leak a
 * permit and stall every later picture.
 */
internal class ImageDecodeGate(permits: Int) {
    private val semaphore = Semaphore(permits.coerceAtLeast(1))

    /** Runs [block] once a permit is free, releasing it however [block] ends. */
    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}

/** The gate every image decode in the app shares. See [MAX_CONCURRENT_IMAGE_DECODES]. */
internal val piImageDecodeGate = ImageDecodeGate(MAX_CONCURRENT_IMAGE_DECODES)

/**
 * A least-recently-used map bounded by the **weight of its entries**, in bytes.
 *
 * Written by hand rather than taken from the platform for two reasons, and both are
 * properties of the keys this app has:
 *
 *  - **It never hashes a key.** The keys are transcript image payloads: base64 strings
 *    of megabytes. `String.hashCode()` is O(payload) and was measured at 22.8 ms for a
 *    fresh 4 MiB string on the phone (`docs/scroll-perf-items.md` §2.2), which is the
 *    reason `ImageSize.kt`'s `naturalImageAspect` KDoc already refuses to key anything
 *    on a payload hash. A `LinkedHashMap` — the shape the markdown image cache uses —
 *    hashes on every `get`, so it cannot be used here. This map scans a handful of
 *    entries with `==`/`equals` instead: an identity hit is one reference comparison,
 *    and a re-created but equal payload is one `memcmp`.
 *  - **It charges for the key's own bytes too.** The caller's [weigh] decides what an
 *    entry costs; for a payload-keyed image cache that has to include the payload the
 *    map is keeping alive, or the bound would be a lie.
 *
 * The scan is O(entries) on purpose, and the entries are few by construction: the
 * budget is a byte budget, so a 32 MiB bound over multi-megabyte entries holds a
 * handful. `get` marks the entry most recently used; `put` replaces and then evicts
 * from the least recently used end until the total is inside the budget again.
 *
 * Pure Kotlin (no Android), so the eviction rule and the accounting are pinned by the
 * bare-JVM harness `app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt`.
 *
 * @param maxBytes the budget; `<= 0` disables the map (nothing is stored). An entry
 *   heavier than the whole budget is **not stored**: evicting everything else could not
 *   make room for it, and storing it would evict the cache for one item.
 * @param weigh what one `(key, value)` pair costs, in bytes. Must be non-negative;
 *   a negative weight is clamped to zero rather than trusted.
 */
internal class ByteBoundedLru<K, V>(private val maxBytes: Long, private val weigh: (K, V) -> Long) {

    private class Entry<K, V>(val key: K, val value: V, val bytes: Long)

    /** Index 0 is the most recently used entry; the last index is the eviction end. */
    private val entries = ArrayList<Entry<K, V>>()
    private var totalBytes = 0L

    /** Entries currently held. */
    val size: Int get() = entries.size

    /** Bytes currently accounted for, never above [maxBytes]. */
    val bytes: Long get() = totalBytes

    /**
     * The keys held, **most recently used first**.
     *
     * Diagnostics and the harness's model-based fuzz; the cache itself never iterates. It
     * exists because "which entry is evicted next" is the one piece of this class's state
     * that [size] and [bytes] cannot express.
     */
    @Synchronized
    fun keys(): List<K> = entries.map { it.key }

    /** The value for [key], marking it most recently used, or null. */
    @Synchronized
    fun get(key: K): V? {
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.key == key) {
                if (index > 0) {
                    entries.removeAt(index)
                    entries.add(0, entry)
                }
                return entry.value
            }
        }
        return null
    }

    /**
     * Stores [value] under [key], evicting the least recently used entries as needed.
     *
     * A `null` [value] is not representable: a caller that must not cache a failure
     * simply does not call this (see `PiImageCache`).
     */
    @Synchronized
    fun put(key: K, value: V) {
        if (maxBytes <= 0) return
        val weight = weigh(key, value).coerceAtLeast(0L)
        if (weight > maxBytes) {
            // Cannot ever fit; drop it and leave the rest of the cache alone.
            remove(key)
            return
        }
        remove(key)
        entries.add(0, Entry(key, value, weight))
        totalBytes += weight
        while (totalBytes > maxBytes && entries.size > 1) {
            val evicted = entries.removeAt(entries.size - 1)
            totalBytes -= evicted.bytes
        }
    }

    /** Forgets everything. A process-wide cache calls this when its owner goes away. */
    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0L
    }

    /**
     * [key]'s value, computed by [compute] and stored when the map does not have it.
     *
     * This is the shape every process-wide parse cache needs — "a hit must not recompute" —
     * so it lives here, where the bare-JVM harness can execute it, rather than being spelled
     * out at each cache.
     *
     * **Not atomic, on purpose.** Two threads may find the same key missing and both compute
     * it; the second `put` replaces the first with an equal value. A per-key lock would cost
     * more than the duplicate work it prevents, and every caller's [compute] is a
     * deterministic function of its key. A [compute] that throws propagates and stores
     * nothing (the entry was never inserted).
     */
    fun getOrCompute(key: K, compute: (K) -> V): V {
        get(key)?.let { return it }
        val value = compute(key)
        put(key, value)
        return value
    }

    /** Drops [key] if present, keeping the accounting exact. */
    @Synchronized
    private fun remove(key: K): Boolean {
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.key == key) {
                entries.removeAt(index)
                totalBytes -= entry.bytes
                return true
            }
        }
        return false
    }
}

// ------------------------------------------------------------------ internals ----

private val GIF_SIGNATURES = setOf("GIF87a", "GIF89a")

private fun aspectOf(pixels: ImagePixels): Float? =
    if (pixels.width > 0 && pixels.height > 0) pixels.width.toFloat() / pixels.height else null

private fun plausible(width: Int, height: Int): ImagePixels? = ImagePixels(width, height)
    .takeIf { it.width in 1..MAX_PLAUSIBLE_DIMENSION && it.height in 1..MAX_PLAUSIBLE_DIMENSION }

/** `SOI` and the first marker byte: a JPEG always has both, and nothing else does. */
private fun isJpegStart(bytes: ByteArray): Boolean =
    bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF

private fun base64CharsFor(bytes: Int): Int = ((bytes + 2) / 3) * 4

private fun bmpSize(bytes: ByteArray): ImagePixels? {
    // The DIB header's own size tells the two layouts apart: 12 is the original
    // BITMAPCOREHEADER (uint16 fields), 40 and up the BITMAPINFOHEADER family (int32).
    return when (val dibHeader = le32(bytes, 14)) {
        12 -> plausible(le16(bytes, 18), le16(bytes, 20))
        in 40..124 -> {
            // A negative height means a top-down bitmap (`biHeight` < 0); the magnitude
            // is the height either way.
            plausible(le32(bytes, 18), abs(le32(bytes, 22)))
        }
        else -> null
    }
}

private fun webpSize(bytes: ByteArray): ImagePixels? = when (val chunk = fourCc(bytes, 12)) {
    // Lossy: `VP8 ` keeps the size in the frame header's 3-byte start code plus two
    // 14-bit fields, little-endian, after the chunk header.
    "VP8 " -> if (bytes.size >= 30 &&
        u8(bytes, 23) == 0x9D && u8(bytes, 24) == 0x01 && u8(bytes, 25) == 0x2A
    ) {
        plausible(le16(bytes, 26) and 0x3FFF, le16(bytes, 28) and 0x3FFF)
    } else {
        null
    }

    // Lossless: 0x2F, then 14 bits of width-1 and 14 bits of height-1 packed little-endian.
    "VP8L" -> if (bytes.size >= 25 && u8(bytes, 20) == 0x2F) {
        val packed = le32(bytes, 21)
        plausible((packed and 0x3FFF) + 1, ((packed ushr 14) and 0x3FFF) + 1)
    } else {
        null
    }

    // Extended (animation/alpha): a 24-bit canvas width-1/height-1 after the chunk header.
    // The canvas, not a frame: the same choice `BitmapFactory` makes for gif.
    "VP8X" -> if (bytes.size >= 30) {
        plausible(le24(bytes, 24) + 1, le24(bytes, 27) + 1)
    } else {
        null
    }

    else -> null
}

/**
 * Walks the JPEG's segment chain to the frame header.
 *
 * Not a field read because there is no fixed offset to read: the size lives in the first
 * `SOFn` marker, and the segments before it (APP0/APP1, quantisation tables, comments) are
 * arbitrary in number and length. The walk is the format's own grammar — a marker is
 * `0xFF` + code, then a big-endian length that includes the two length bytes — and it stops
 * at whichever comes first: the frame header, the start of scan (`SOS`, past which there
 * are no more headers), the end of image, or the end of what we were given.
 */
private fun jpegSize(bytes: ByteArray): ImagePixels? {
    var index = 2
    while (index + 3 < bytes.size) {
        if (u8(bytes, index) != 0xFF) return null
        var marker = u8(bytes, index + 1)
        var cursor = index + 2
        // Fill bytes: any number of 0xFF may precede the code.
        while (marker == 0xFF && cursor < bytes.size) {
            marker = u8(bytes, cursor)
            cursor++
        }
        when {
            // Standalone markers carry no length.
            marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> index = cursor
            // EOI, or the scan itself: no frame header was found before them.
            marker == 0xD9 || marker == 0xDA -> return null
            else -> {
                if (cursor + 1 >= bytes.size) return null
                val length = be16(bytes, cursor)
                if (length < 2) return null
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    // SOFn: length(2) precision(1) height(2) width(2). `C4`/`C8`/`CC` share
                    // the range but are tables and a reserved code, not frame headers.
                    if (cursor + 7 > bytes.size) return null
                    return plausible(be16(bytes, cursor + 5), be16(bytes, cursor + 3))
                }
                index = cursor + length
            }
        }
    }
    return null
}

private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

private fun fourCc(bytes: ByteArray, index: Int): String =
    String(bytes, index, 4, Charsets.US_ASCII)

private fun be16(bytes: ByteArray, index: Int): Int = (u8(bytes, index) shl 8) or u8(bytes, index + 1)

private fun le16(bytes: ByteArray, index: Int): Int = u8(bytes, index) or (u8(bytes, index + 1) shl 8)

private fun be32(bytes: ByteArray, index: Int): Int =
    (u8(bytes, index) shl 24) or (u8(bytes, index + 1) shl 16) or
        (u8(bytes, index + 2) shl 8) or u8(bytes, index + 3)

private fun le32(bytes: ByteArray, index: Int): Int =
    u8(bytes, index) or (u8(bytes, index + 1) shl 8) or
        (u8(bytes, index + 2) shl 16) or (u8(bytes, index + 3) shl 24)

private fun le24(bytes: ByteArray, index: Int): Int =
    u8(bytes, index) or (u8(bytes, index + 1) shl 8) or (u8(bytes, index + 2) shl 16)

private fun base64Value(character: Char): Int = when (character) {
    in 'A'..'Z' -> character - 'A'
    in 'a'..'z' -> character - 'a' + 26
    in '0'..'9' -> character - '0' + 52
    '+' -> 62
    '/' -> 63
    else -> -1
}

// ---------------------------------------------------------------- parse memos ----

/**
 * A bounded memo of a **pure** `String -> String` parse — the cross-composition half of
 * what `remember` does inside one composition.
 *
 * ## Why this exists
 *
 * `remember(output) { Ansi.strip(output) }` is state of a *composition*: a row the
 * `LazyColumn` disposes takes its remembered parse with it, and scrolling back to it
 * re-runs the scan. The user's report — 「上下滑动出现一些东西的时候会有点顿…出现这些东西的
 * 时候，滑动不流畅」 — has this as its single largest item-level cause, because the most
 * expensive parses in the transcript are exactly the ones on the rows most likely to be
 * recycled: a shell result's strip (2.4 ms at pi's 2000-line cap), its tail split
 * (1.9 ms), a diff's plan (3.9–13.2 ms, the row-internal LCS included).
 *
 * ## The rules it follows (the same ones as `PiImageCache`)
 *
 *  - **Bounded, and the bound is bytes.** The budget is enforced by [ByteBoundedLru],
 *    which charges `key.length * 2 + value.length * 2` — the input *and* the result are
 *    held, so both count. An entry that cannot fit is not stored.
 *  - **Honest accounting**: UTF-16 characters are two bytes each, which is what a
 *    `String` actually costs here.
 *  - **A hit cannot change what is drawn**: every caller memoises a deterministic
 *    function of its key (`Ansi.strip`, `tailLines`), so a hit and a recomputation
 *    produce equal strings. The one thing that could differ is *identity*, and no caller
 *    compares strings by identity.
 *  - **Nothing is cached on failure**, because these functions cannot fail: they answer a
 *    string for every input. (The cache is also not a place for exceptions — a `compute`
 *    lambda that throws propagates and stores nothing.)
 *  - **`getOrCompute` is not atomic.** Two threads may compute the same key at once, both
 *    storing the same value; that is deliberate — a per-key lock would cost more than the
 *    duplicate work it prevents, and the result is identical either way.
 *
 * The key is compared with `==`/`equals` and **never hashed** ([ByteBoundedLru]'s
 * contract), which is what keeps a 200 KB result off `String.hashCode()`.
 *
 * Pure Kotlin, so `app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt` executes its
 * eviction, accounting and hit semantics on a bare JVM.
 */
internal class TextMemo(maxBytes: Long) {

    private val lru = ByteBoundedLru<String, String>(maxBytes) { key, value ->
        (key.length.toLong() + value.length.toLong()) * 2
    }

    /** The remembered parse of [key], or null. Marks the entry most recently used. */
    fun get(key: String): String? = lru.get(key)

    /** Remembers [value] as [key]'s parse. */
    fun put(key: String, value: String) {
        lru.put(key, value)
    }

    /**
     * [key]'s parse: the memo's answer when it has one, otherwise `compute(key)` — which
     * is then stored. Delegates to [ByteBoundedLru.getOrCompute], which states the
     * not-atomic-but-idempotent rule.
     */
    fun getOrCompute(key: String, compute: (String) -> String): String = lru.getOrCompute(key, compute)

    /** Bytes currently accounted for. Diagnostics and the harness. */
    val bytes: Long get() = lru.bytes

    /** Entries currently held. Diagnostics and the harness. */
    val size: Int get() = lru.size

    /** Forgets everything. See `PiImageCache.clear` for the lifetime argument. */
    fun clear() {
        lru.clear()
    }
}

/**
 * Line count of a growing blob, without re-counting the part that did not change.
 *
 * pi republishes a streaming tool row on every `tool_execution_update`, throttled to
 * 200 ms (`rpc/.../Transcript.kt:618`), and the footer's 「N 行」 reading is
 * `1 + count('\n')` over the whole result — 0.3 ms for a 2000-line result, 4.1 ms for a
 * 200 KB one, per publication, on the frame thread. A tool result only ever **grows**
 * (`output` is appended to), so the count can be carried forward: the new text starts
 * with the previous text, and the number of new lines is the number of newlines in the
 * suffix.
 *
 * Three cases, and the exactness of the third is what the harness pins:
 *
 *  - the same instance (or equal text) → the count in hand;
 *  - the previous text is a **prefix** of the new one → the count plus the newlines in
 *    the appended suffix, which is exact because `lineCount` is `1 + newlines` and no
 *    newline is ever *removed* by appending;
 *  - anything else (a replaced or truncated result, a recycled row that now holds a
 *    different item) → a full recount.
 *
 * Not thread-safe, and it must not be shared between rows: it is one counter per row,
 * held in a `remember`. A counter that is asked about a text it has never seen recounts,
 * so being wrong about ownership costs time, never correctness — the same property the
 * harness asserts against [lineCount] over random growth sequences.
 */
internal class IncrementalLineCount {

    private var previous: String? = null
    private var count = 0

    /** `lineCount(text)` — via the carried count whenever [text] grew from the last one. */
    fun of(text: String): Int {
        // `lineCount("")` is 0, not 1: an empty body is no lines, and the counter keeps that
        // case exact rather than deriving it.
        if (text.isEmpty()) return remember(text, 0)
        val last = previous
        // Anything that is not a continuation (a replaced result, a truncated one, a recycled
        // row holding a different item) is counted from scratch. An empty prefix belongs here
        // too: there is no carried newline count to add to.
        if (last == null || last.isEmpty() || !text.startsWith(last)) {
            return remember(text, 1 + newlinesIn(text, 0, text.length))
        }
        // `startsWith` already compared the prefix; only the appended suffix is new. Exact
        // because the count is `1 + newlines` and appending can only add newlines.
        return remember(text, count + newlinesIn(text, last.length, text.length))
    }

    private fun remember(text: String, lines: Int): Int {
        previous = text
        count = lines
        return lines
    }

    private fun newlinesIn(text: String, from: Int, to: Int): Int {
        var lines = 0
        for (index in from until to) if (text[index] == '\n') lines++
        return lines
    }
}
