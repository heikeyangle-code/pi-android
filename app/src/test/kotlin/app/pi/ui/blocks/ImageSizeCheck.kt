package app.pi.ui.blocks

import java.io.File
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// A bare-JVM harness for `ImageSize.kt` — the arithmetic behind a transcript image's box.
// Registered in `tools/run-app-pure-checks.sh` as `image-size`.
//
// Why this is worth pinning: the two defects it exists for are both invisible to every
// other check in this repository.
//
//   ① The single image was too big: 「占大半个屏幕了都。让它再缩小 1/3~1/2」. The size is a
//      fraction ([SINGLE_IMAGE_MAX_HEIGHT_FRACTION]) applied by arithmetic that no build
//      step evaluates — `ImageGridBlock.kt` is Compose, and this machine cannot compile
//      Compose at all (no Compose compiler plugin in `tools/typecheck.sh`, no APK: AAPT2 is
//      x86-64 only). A silent re-tune of that constant is a user-visible regression that
//      nothing else would notice, so the constant and the height rule are pinned here.
//
//   ② 「往上滑有图片的时候不流畅」 / 「图片会从底部直接闪现到全面露出来」: the row changed
//      height once per picture, because the cell sized its box from the *decoded* bitmap.
//      The fix states the height from the payload's own header before anything is decoded,
//      which makes "the same picture never changes the row's height" a property of this
//      file's functions. Their inputs are bytes and integers, so a bare JVM can execute
//      them — and it does, over every real format plus a sweep of hostile ones.
//
// What is *not* here, and cannot be: whether the composable actually reads these values on
// the right frame, whether a decode really finishes before the user sees the box, the real
// decode cost, and how the letterboxed picture looks. Those are device items
// (`docs/device-verification.md` §H2).
//
// The real payloads below were produced by ffmpeg and their sizes confirmed by ffprobe
// (`6,2` each) — an oracle outside this file's own arithmetic. The synthetic ones are
// header-shaped byte arrays built here: they exercise the part of each format this file
// actually reads (the fields and the marker chain), and each one says so where it is built.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** A call that must return something rather than raise, whatever it was handed. */
private fun survives(name: String, block: () -> Any?) {
    val outcome = try {
        block()
        "returned"
    } catch (error: Throwable) {
        "threw ${error::class.java.simpleName}: ${error.message}"
    }
    check(name, outcome, "returned")
}

// ------------------------------------------------------------------ fixtures ----

/** A real 6×2 png (ffmpeg), 95 bytes — the common case in this app (screenshots). */
private const val PNG_6X2 =
    "iVBORw0KGgoAAAANSUhEUgAAAAYAAAACCAIAAAD0PzoJAAAACXBIWXMAAAABAAAAAQBPJcTWAAAAEUlEQVR4nGP4w8CA" +
        "htD5QAQA7GYL0WL3dYAAAAAASUVORK5CYII="

/**
 * A real 6×2 jpeg (ffmpeg), 225 bytes. Its frame header is at offset **185**, i.e. behind
 * an APP0, a comment and the quantisation/Huffman tables — which is the whole reason
 * [naturalImageAspect] needs a second, larger read for jpeg and a marker walk instead of a
 * field read.
 */
private const val JPEG_6X2 =
    "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjMxLjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgH" +
        "BwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQ" +
        "AQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAACAAYDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAFF/f//Z"

/** A real 6×2 gif (ffmpeg), 830 bytes — 256-colour palette included. */
private const val GIF_6X2 =
    "R0lGODlhBgACAPcfMQAAACQAAEgAAGwAAJAAALQAANgAAPwAAAAkACQkAEgkAGwkAJAkALQkANgkAPwkAABIACRIAEhI" +
        "AGxIAJBIALRIANhIAPxIAABsACRsAEhsAGxsAJBsALRsANhsAPxsAACQACSQAEiQAGyQAJCQALSQANiQAPyQAAC0ACS0" +
        "AEi0AGy0AJC0ALS0ANi0APy0AADYACTYAEjYAGzYAJDYALTYANjYAPzYAAD8ACT8AEj8AGz8AJD8ALT8ANj8APz8AAAA" +
        "VSQAVUgAVWwAVZAAVbQAVdgAVfwAVQAkVSQkVUgkVWwkVZAkVbQkVdgkVfwkVQBIVSRIVUhIVWxIVZBIVbRIVdhIVfxI" +
        "VQBsVSRsVUhsVWxsVZBsVbRsVdhsVfxsVQCQVSSQVUiQVWyQVZCQVbSQVdiQVfyQVQC0VSS0VUi0VWy0VZC0VbS0Vdi0" +
        "Vfy0VQDYVSTYVUjYVWzYVZDYVbTYVdjYVfzYVQD8VST8VUj8VWz8VZD8VbT8Vdj8Vfz8VQAAqiQAqkgAqmwAqpAA" +
        "qrQAqtgAqvwAqgAkqiQkqkgkqmwkqpAkqrQkqtgkqvwkqgBIqiRIqkhIqmxIqpBIqrRIqthIqvxIqgBsqiRsqkhsqmxs" +
        "qpBsqrRsqthsqvxsqgCQqiSQqkiQqmyQqpCQqrSQqtiQqvyQqgC0qiS0qki0qmy0qpC0qrS0qti0qvy0qgDYqiTYqkjY" +
        "qmzYqpDYqrTYqtjYqvzYqgD8qiT8qkj8qmz8qpD8qrT8qtj8qvz8qgAA/yQA/0gA/2wA/5AA/7QA/9gA//wA/wAk/yQk" +
        "/0gk/2wk/5Ak/7Qk/9gk//wk/wBI/yRI/0hI/2xI/5BI/7RI/9hI//xI/wBs/yRs/0hs/2xs/5Bs/7Rs/9hs//xs/wCQ" +
        "/ySQ/0iQ/2yQ/5CQ/7SQ/9iQ//yQ/wC0/yS0/0i0/2y0/5C0/7S0/9i0//y0/wDY/yTY/0jY/2zY/5DY/7TY/9jY" +
        "//zY/wD8/yT8/0j8/2z8/5D8/7T8/9j8//z8/yH/C05FVFNDQVBFMi4wAwEAAAAh+QQEBAAfACwAAAAABgACAAAICAAP" +
        "CBxIUGBAADs="

/** A real 6×2 lossy (`VP8 `) webp (ffmpeg), 68 bytes. */
private const val WEBP_6X2 =
    "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoGAAIAAgA0JaACdLoB+AADsAD+8Oj3/yC5YXXI1/8gP+QH/ID/+PIAAAA="

/** A real 6×2 bmp (ffmpeg), 94 bytes, with a 40-byte `BITMAPINFOHEADER`. */
private const val BMP_6X2 =
    "Qk1eAAAAAAAAADYAAAAoAAAABgAAAAIAAAABABgAAAAAACgAAAAAAAAAAAAAAAAAAAAAAAAAAAD8AAD8AAD8AAD8AAD8" +
        "AAD8AAAAAPwAAPwAAPwAAPwAAPwAAPwAAA=="

private fun bytes(text: String): ByteArray = Base64.getDecoder().decode(text)

/**
 * A png whose IHDR states [width] × [height]. Header-shaped on purpose: only the 8-byte
 * signature, the `IHDR` chunk type and the two big-endian int32s are real, and those are
 * exactly the bytes [readImageHeader] reads (the CRC is not consulted — neither by this
 * parser nor by anything that matters).
 */
private fun pngHeader(width: Int, height: Int): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D,
    'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
    (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
    (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
    0x08, 0x06, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
)

/** A gif whose logical screen descriptor states [width] × [height]. */
private fun gifHeader(signature: String, width: Int, height: Int): ByteArray {
    val out = ByteArray(13)
    for (index in signature.indices) out[index] = signature[index].code.toByte()
    out[6] = width.toByte()
    out[7] = (width ushr 8).toByte()
    out[8] = height.toByte()
    out[9] = (height ushr 8).toByte()
    return out
}

/** A bmp with a [dibHeader] (12 = core, 40 = info) stating [width] × [height]. */
private fun bmpHeader(dibHeader: Int, width: Int, height: Int): ByteArray {
    val out = ByteArray(26)
    out[0] = 'B'.code.toByte()
    out[1] = 'M'.code.toByte()
    out[14] = dibHeader.toByte()
    fun le32(at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }
    if (dibHeader == 12) {
        out[18] = width.toByte()
        out[19] = (width ushr 8).toByte()
        out[20] = height.toByte()
        out[21] = (height ushr 8).toByte()
    } else {
        le32(18, width)
        le32(22, height)
    }
    return out
}

/** A webp whose `RIFF` container names [chunk]; the chunk payload is the caller's. */
private fun webpContainer(chunk: String, payload: ByteArray): ByteArray {
    val out = ByteArray(20 + payload.size)
    for ((index, character) in "RIFF".withIndex()) out[index] = character.code.toByte()
    for ((index, character) in "WEBP".withIndex()) out[8 + index] = character.code.toByte()
    for ((index, character) in chunk.withIndex()) out[12 + index] = character.code.toByte()
    payload.copyInto(out, 20)
    return out
}

/** A `VP8L` chunk: 0x2F then width-1/height-1 as two 14-bit fields, little-endian. */
private fun webpLossless(width: Int, height: Int): ByteArray {
    val packed = ((height - 1) shl 14) or (width - 1)
    return webpContainer(
        "VP8L",
        byteArrayOf(
            0x2F, 0x00, 0x00, 0x00, 0x00,
        ).also { chunk ->
            for (index in 0 until 4) chunk[1 + index] = (packed ushr (8 * index)).toByte()
        },
    )
}

/** A `VP8X` chunk: four bytes of flags/reserved, then a 24-bit canvas width-1/height-1. */
private fun webpExtended(width: Int, height: Int): ByteArray {
    val chunk = ByteArray(10)
    val w = width - 1
    val h = height - 1
    chunk[4] = w.toByte()
    chunk[5] = (w ushr 8).toByte()
    chunk[6] = (w ushr 16).toByte()
    chunk[7] = h.toByte()
    chunk[8] = (h ushr 8).toByte()
    chunk[9] = (h ushr 16).toByte()
    return webpContainer("VP8X", chunk)
}

/** One JPEG segment: `FF` + marker + big-endian length (payload + 2) + payload. */
private fun segment(marker: Int, payloadSize: Int): ByteArray {
    val length = payloadSize + 2
    val out = ByteArray(4 + payloadSize)
    out[0] = 0xFF.toByte()
    out[1] = marker.toByte()
    out[2] = (length ushr 8).toByte()
    out[3] = length.toByte()
    return out
}

/**
 * A JPEG that is its marker chain and nothing else: `SOI`, [preamble] (the caller's
 * segments), a complete `SOF0` for [width] × [height], then `EOI`.
 *
 * The entropy-coded scan is deliberately absent — the walk never looks at it, and a real
 * scan would only make the fixture bigger. The frame header itself is complete (length 17,
 * three components), which is what a decoder would need to agree about the size.
 */
private fun jpegWithPreamble(preamble: ByteArray, width: Int, height: Int): ByteArray {
    val sof = byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
        (height ushr 8).toByte(), height.toByte(),
        (width ushr 8).toByte(), width.toByte(),
        0x03,
        0x01, 0x11, 0x00,
        0x02, 0x11, 0x01,
        0x03, 0x11, 0x01,
        0xFF.toByte(), 0xD9.toByte(),
    )
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + preamble + sof
}

// ------------------------------------------------------------------ the checks ----

private fun realPayloadChecks() {
    // The five formats, read through the same entry point the composable uses. Each
    // payload is the whole file as the wire would carry it, and its size (6 × 2) comes
    // from ffprobe, not from this parser.
    val real = listOf("png" to PNG_6X2, "jpeg" to JPEG_6X2, "gif" to GIF_6X2, "webp" to WEBP_6X2, "bmp" to BMP_6X2)
    for ((format, payload) in real) {
        check("$format: a real 6x2 payload states 6x2", readImageHeader(decoded(payload)), ImagePixels(6, 2))
        check("$format: and its aspect reaches the cell as 3.0", naturalImageAspect(payload), 3f)
    }

    // The prologue is what makes this affordable on a composition frame: four of the five
    // formats are fully read from 32 decoded bytes.
    for ((format, payload) in real.filter { it.first != "jpeg" }) {
        check(
            "$format: 32 bytes of payload are enough for the header",
            readImageHeader(base64PrefixBytes(payload, HEADER_PROLOGUE_BYTES)),
            ImagePixels(6, 2),
        )
    }
    // …and the jpeg is the exception that pays for the walk: its frame header is at 185.
    check(
        "jpeg: 32 bytes are not enough (its SOF is behind its comment and tables)",
        readImageHeader(base64PrefixBytes(JPEG_6X2, HEADER_PROLOGUE_BYTES)),
        null,
    )
    check(
        "jpeg: the longer search finds the frame header anyway",
        naturalImageAspect(JPEG_6X2),
        3f,
    )

    check(
        "the header read never needs the whole payload",
        base64PrefixBytes(PNG_6X2, HEADER_PROLOGUE_BYTES).size,
        HEADER_PROLOGUE_BYTES,
    )
}

/** The full decoded bytes of a base64 payload — the JDK's decoder, not this file's. */
private fun decoded(base64: String): ByteArray = Base64.getDecoder().decode(base64)

private fun jpegSearchChecks() {
    // A realistic EXIF thumbnail (~8 KB) must not hide the size.
    val withThumbnail = jpegWithPreamble(segment(0xE1, 8_000), 900, 2000)
    check(
        "jpeg: an 8 KB EXIF thumbnail before the SOF is walked past",
        naturalImageAspect(Base64.getEncoder().encodeToString(withThumbnail)),
        900f / 2000f,
    )

    // Past the budget the answer is "unknown", never a guess: the fallback aspect takes
    // over and the row is still stable, which is the trade the KDoc states. A single
    // segment cannot even express this — its 16-bit length caps it at ~64 KiB — so the
    // preamble is three of them, which is also how a real file carries that much metadata.
    val huge = jpegWithPreamble(segment(0xE1, 30_000) + segment(0xE1, 30_000) + segment(0xE1, 10_000), 900, 2000)
    check(
        "jpeg: a SOF past the search budget is unknown, not guessed",
        naturalImageAspect(Base64.getEncoder().encodeToString(huge)),
        null,
    )

    // The scan begins before any frame header: there is no size in this file, and reading
    // entropy-coded bytes as if they were markers is how a parser invents one.
    val noFrameHeader = jpegWithPreamble(segment(0xDA, 10), 900, 2000)
    check(
        "jpeg: the walk stops at SOS instead of reading the scan",
        naturalImageAspect(Base64.getEncoder().encodeToString(noFrameHeader)),
        null,
    )

    // Fill bytes (`FF FF C0`) are legal and must not be read as a marker code.
    val filled = jpegWithPreamble(segment(0xE0, 4) + byteArrayOf(0xFF.toByte()), 320, 240)
    check(
        "jpeg: a fill byte before the SOF marker is skipped",
        naturalImageAspect(Base64.getEncoder().encodeToString(filled)),
        320f / 240f,
    )
}

private fun headerShapeChecks() {
    // The shape the cap exists for: a phone screenshot. 900 × 2000 is 0.45, and the row it
    // asks for (2400 px at a 1080 px width) is three times the cap a 2400 px window allows.
    check("png: a 900x2000 portrait screenshot states 0.45", pixels(pngHeader(900, 2000)), ImagePixels(900, 2000))
    check("gif: GIF87a is a gif too", readImageHeader(gifHeader("GIF87a", 320, 240)), ImagePixels(320, 240))
    check("gif: GIF89a reads the screen descriptor", readImageHeader(gifHeader("GIF89a", 320, 240)), ImagePixels(320, 240))
    check("bmp: a 12-byte core header states uint16 fields", readImageHeader(bmpHeader(12, 300, 200)), ImagePixels(300, 200))
    check("bmp: a 40-byte info header states int32 fields", readImageHeader(bmpHeader(40, 300, 200)), ImagePixels(300, 200))
    check(
        "bmp: a top-down bitmap (negative height) states its magnitude",
        readImageHeader(bmpHeader(40, 300, -200)),
        ImagePixels(300, 200),
    )
    check("webp: VP8L packs width-1/height-1 into 14 bits each", readImageHeader(webpLossless(100, 50)), ImagePixels(100, 50))
    check("webp: VP8X states its canvas", readImageHeader(webpExtended(1920, 1080)), ImagePixels(1920, 1080))
    check("webp: a VP8X canvas of 1x1 adds one back to each field", readImageHeader(webpExtended(1, 1)), ImagePixels(1, 1))

    // Guards. Each of these would otherwise become a row height — the reason the parser is
    // allowed to say "I do not know" instead of returning a number.
    check("a png signature without an IHDR is not a png", readImageHeader(pngHeader(6, 2).copyOf(16)), null)
    check(
        "an implausible dimension is refused rather than turned into a row",
        readImageHeader(pngHeader(1080, 2_000_000)),
        null,
    )
    check("a plausible long screenshot is still accepted", readImageHeader(pngHeader(1080, 100_000)), ImagePixels(1080, 100_000))
    check("an unknown container is not guessed at", readImageHeader(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26)), null)
    check("an empty payload has no header", readImageHeader(ByteArray(0)), null)
    check("zero is not a dimension", readImageHeader(pngHeader(0, 200)), null)
    check("a negative dimension is not a dimension", readImageHeader(bmpHeader(40, -300, 200)), null)
}

private fun pixels(bytes: ByteArray): ImagePixels? = readImageHeader(bytes)

private fun base64PrefixChecks() {
    val full = decoded(PNG_6X2)

    // The prefix decoder has to agree with the platform decoder — `decodePiImage` hands the
    // whole payload to `Base64.decode(s, Base64.DEFAULT)`, and a header read from different
    // bytes than the ones that get decoded would be a silent mismatch.
    check(
        "the prefix decoder agrees with the platform decoder byte for byte",
        base64PrefixBytes(PNG_6X2, full.size).contentEquals(full),
        true,
    )
    check(
        "and on a prefix that ends mid-group",
        base64PrefixBytes(PNG_6X2, 7).contentEquals(full.copyOf(7)),
        true,
    )
    check("the cap is honoured exactly", base64PrefixBytes(PNG_6X2, 3).size, 3)
    check(
        "the cap cuts where the platform decoder would cut",
        base64PrefixBytes(PNG_6X2, 3).contentEquals(full.copyOf(3)),
        true,
    )

    // Base64 as the wire carries it: the platform decoder ignores line breaks, so this one
    // must too, or a wrapped payload would lose its header.
    val wrapped = PNG_6X2.chunked(8).joinToString("\n")
    check(
        "line breaks are skipped, as the platform decoder skips them",
        base64PrefixBytes(wrapped, HEADER_PROLOGUE_BYTES).contentEquals(full.copyOf(HEADER_PROLOGUE_BYTES)),
        true,
    )
    check(
        "carriage returns and spaces are skipped too",
        base64PrefixBytes(PNG_6X2.chunked(6).joinToString("\r\n "), 8).contentEquals(full.copyOf(8)),
        true,
    )

    check("a truncated tail is dropped, not padded", base64PrefixBytes("AAAAA", 8).size, 3)
    check("padding ends the data", base64PrefixBytes("AA==AAAA", 8).size, 1)
    check("an invalid character ends the data instead of throwing", base64PrefixBytes("AA!!BBBB", 8).size, 1)
    check("text that is not base64 at all decodes to nothing", base64PrefixBytes("!!!!", 8).size, 0)
    check("an empty payload decodes to nothing", base64PrefixBytes("", 8).size, 0)
    check("a zero cap asks for nothing", base64PrefixBytes(PNG_6X2, 0).size, 0)
}

private fun boxHeightChecks() {
    // The user's own case, on a 1080 × 2400 phone at 3× density: an 800 dp window, so the
    // 0.35 cap is 280 dp = 840 px.
    val cap = 840
    check("a 900x2000 screenshot is capped, not stretched to 2400 px", singleImageBoxHeightPx(1080, 900f / 2000f, cap), cap)
    check("a 4:3 picture below the cap keeps its own height", singleImageBoxHeightPx(1080, 4f / 3f, cap), 810)
    check("a square picture keeps its own height", singleImageBoxHeightPx(1080, 1f, cap), cap)
    check("a wide picture is short", singleImageBoxHeightPx(1080, 4f, cap), 270)
    check("the height is a function of the width alone", singleImageBoxHeightPx(720, 1f, cap), 720)
    check("a narrow cell scales with it", singleImageBoxHeightPx(720, 9f / 16f, cap), cap)

    // The user's request, as a number: 0.6 → 0.3–0.4.
    check("the cap is the reduction the user asked for", SINGLE_IMAGE_MAX_HEIGHT_FRACTION in 0.30f..0.40f, true)
    check("and it is below the 0.6 that was called too big", SINGLE_IMAGE_MAX_HEIGHT_FRACTION < 0.6f, true)
    check("the fallback shape is 4:3", SINGLE_IMAGE_FALLBACK_ASPECT, 4f / 3f)

    // Degenerate inputs must not produce a box: a NaN ratio in an aspect box would be a
    // crash or an invisible row, and this is the function whose answer is the row.
    check("a zero width has no box", singleImageBoxHeightPx(0, 1f, cap), 0)
    check("a zero cap has no box", singleImageBoxHeightPx(1080, 1f, 0), 0)
    check("a zero ratio has no box", singleImageBoxHeightPx(1080, 0f, cap), 0)
    check("a NaN ratio has no box", singleImageBoxHeightPx(1080, Float.NaN, cap), 0)
    check("an infinite ratio has no box", singleImageBoxHeightPx(1080, Float.POSITIVE_INFINITY, cap), 0)

    // The property the two defects come down to: for any picture shape, the row is never
    // taller than the cap and never taller than the picture's own height at this width.
    var violations = 0
    for (width in listOf(240, 360, 720, 1080, 1440)) {
        for (tenths in 1..400) {
            val aspect = tenths / 10f
            val height = singleImageBoxHeightPx(width, aspect, cap)
            if (height > cap) violations++
            if (height > (width / aspect).roundToInt()) violations++
            if (height < 1) violations++
        }
    }
    check("no shape can exceed the cap or the picture's own height", violations, 0)
}

private fun fitBoxChecks() {
    // A card is not a picture: what gets decoded is what gets drawn.
    check("a wide picture is drawn at the card's width", fitBoxPx(1080, 840, 2f), ImagePixels(1080, 540))
    check("a 1:5 screenshot is a narrow column, and that is what is decoded", fitBoxPx(1080, 840, 0.2f), ImagePixels(168, 840))
    check("an exact fit is unchanged", fitBoxPx(1080, 540, 2f), ImagePixels(1080, 540))
    check("the aspect is preserved", fitBoxPx(1000, 1000, 4f / 3f), ImagePixels(1000, 750))
    check("an unknown ratio leaves the box alone", fitBoxPx(1080, 840, 0f), ImagePixels(1080, 840))
    check("a NaN ratio leaves the box alone", fitBoxPx(1080, 840, Float.NaN), ImagePixels(1080, 840))
    check("a degenerate box still yields a positive sample", fitBoxPx(0, 0, 1f), ImagePixels(1, 1))

    var violations = 0
    var shapeDrift = 0
    for (tenths in 1..400) {
        val aspect = tenths / 10f
        val fitted = fitBoxPx(1080, 840, aspect)
        if (fitted.width < 1 || fitted.height < 1) violations++
        if (fitted.width > 1080 || fitted.height > 840) violations++
        // The shape is the picture's up to the integer rounding of two dimensions — half a
        // pixel on each axis, i.e. |w/h − aspect| ≤ (aspect + 1) / height. A flat tolerance
        // would be wrong here: a 20:1 panorama drawn 96 px tall is already 0.06 off from
        // rounding alone, and that is not a defect.
        val drawnAspect = fitted.width.toFloat() / fitted.height
        if (kotlin.math.abs(drawnAspect - aspect) > (aspect + 1f) / fitted.height) shapeDrift++
    }
    check("a fitted box never exceeds the card nor collapses", violations, 0)
    check("and it keeps the picture's shape up to a pixel of rounding", shapeDrift, 0)
}

private fun decodeGateChecks() {
    check("the app's bound is the one the harness drives", MAX_CONCURRENT_IMAGE_DECODES, 2)

    // Concurrency, measured rather than asserted in prose: 12 decodes, and the highest
    // number ever inside the gate at once.
    val gate = ImageDecodeGate(MAX_CONCURRENT_IMAGE_DECODES)
    val lock = Any()
    var inFlight = 0
    var peak = 0
    runBlocking {
        (1..12).map {
            launch(Dispatchers.Default) {
                gate.withPermit {
                    synchronized(lock) {
                        inFlight++
                        peak = max(peak, inFlight)
                    }
                    Thread.sleep(5)
                    synchronized(lock) { inFlight-- }
                }
            }
        }.joinAll()
    }
    check("no more than the bound runs at once", peak, MAX_CONCURRENT_IMAGE_DECODES)
    check("and every decode ran", inFlight, 0)

    // A decode that fails must not take its permit with it, or one bad payload would stall
    // every later picture.
    val single = ImageDecodeGate(1)
    runBlocking {
        val failed = runCatching { single.withPermit { error("decode blew up") } }.isFailure
        check("a decode that throws is reported to its caller", failed, true)
        val afterFailure = runCatching { withTimeout(2_000) { single.withPermit { "ran" } } }.getOrNull()
        check("and its permit is back, so the next picture still decodes", afterFailure, "ran")
    }

    // The fling case: a cell that scrolls away is cancelled while it holds a permit.
    val cancel = ImageDecodeGate(1)
    runBlocking {
        val held = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            cancel.withPermit {
                held.complete(Unit)
                awaitCancellation()
            }
        }
        held.await()
        holder.cancelAndJoin()
        val afterCancel = runCatching { withTimeout(2_000) { cancel.withPermit { "ran" } } }.getOrNull()
        check("a decode cancelled by a fling releases its permit", afterCancel, "ran")
    }
}

/**
 * The wiring: a gate nobody calls is the same as no gate, and a row sized from a bitmap
 * nobody waited for is the defect this change removed.
 *
 * Only source text can be checked here — the call sites are composables, and this machine
 * cannot compile Compose. Two things are counted. Every `decodePiImage(image.…)` in these
 * two files has to sit inside a `piImageDecodeGate.withPermit` (`ChatScreen.kt`'s composer
 * preview is deliberately not in the list: it decodes one picture the user just picked, not
 * a scrolling list, and that file is not this change's), and the transcript's cell has to
 * get its single-image height from `singleImageBoxHeightPx` — never from `decoded.width` /
 * `decoded.height`, which is exactly how the row came to change height once per picture.
 * (The viewer may look at the decoded bitmap: it fits and zooms that picture, it does not
 * size a scrolling row with it.)
 */
private fun wiringChecks(repoRoot: File?) {
    val cell = "app/src/main/kotlin/app/pi/ui/blocks/ImageGridBlock.kt"
    for (relative in listOf(
        cell,
        "app/src/main/kotlin/app/pi/ui/blocks/PiImageViewer.kt",
    )) {
        val file = repoRoot?.let { File(it, relative) }
        val text = if (file != null && file.isFile) file.readText() else ""
        val calls = Regex("""decodePiImage\(image\.base64""").findAll(text).count()
        val gated = Regex("""piImageDecodeGate\.withPermit""").findAll(text).count()
        check(
            "every payload decode in ${relative.substringAfterLast('/')} is under the shared gate",
            "$calls/$gated",
            "1/1",
        )
    }

    val cellFile = repoRoot?.let { File(it, cell) }
    val cellText = if (cellFile != null && cellFile.isFile) cellFile.readText() else ""
    // Comments are stripped first: this file's KDoc *names* the expression it removed
    // (`decoded.width / decoded.height`), and prose about a defect must not read as one.
    val cellCode = cellText.lines()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")
    val sizedFromHeader = Regex("""singleImageBoxHeightPx\(""").findAll(cellCode).count()
    val sizedFromBitmap = Regex("""decoded\.(width|height)""").findAll(cellCode).count()
    check(
        "the single image's box is sized from the header, never from the decoded bitmap",
        "$sizedFromHeader/$sizedFromBitmap",
        "1/0",
    )
}

/**
 * Nothing in the header path may throw.
 *
 * Truncation at *every* offset is the realistic input, not a hostile one: the parser is
 * handed a fixed-size prefix of a payload of unknown length, so a payload shorter than the
 * prologue is the normal case for a small image and a sliced one is what every JPEG read
 * looks like.
 */
private fun robustnessChecks() {
    val fixtures = listOf(
        bytes(PNG_6X2),
        bytes(JPEG_6X2),
        bytes(GIF_6X2),
        bytes(WEBP_6X2),
        bytes(BMP_6X2),
        pngHeader(6, 2),
        jpegWithPreamble(segment(0xE1, 64), 10, 20),
    )
    for ((index, fixture) in fixtures.withIndex()) {
        survives("fixture $index: every truncation is answered, not thrown") {
            for (length in 0..fixture.size) {
                readImageHeader(fixture.copyOf(length))
                naturalImageAspect(Base64.getEncoder().encodeToString(fixture.copyOf(length)))
            }
        }
    }
    survives("a megabyte of 0xFF is answered, not thrown") {
        readImageHeader(ByteArray(1 shl 20) { 0xFF.toByte() })
    }
    survives("a megabyte of zeros is answered, not thrown") {
        readImageHeader(ByteArray(1 shl 20))
    }
    survives("a 1 KB run of 0xFF after a jpeg SOI is answered, not thrown") {
        readImageHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(1024) { 0xFF.toByte() })
    }
    survives("a jpeg whose segment length runs off the end is answered, not thrown") {
        readImageHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte()))
    }
    survives("a webp chunk type with no chunk is answered, not thrown") {
        readImageHeader(webpContainer("VP8X", ByteArray(0)))
    }
    survives("the payload decoder accepts arbitrary text") {
        base64PrefixBytes("\u0000\u0001=\n\uFFFF", 16)
    }
    survives("the height arithmetic accepts a degenerate width") {
        singleImageBoxHeightPx(Int.MAX_VALUE, 1f / 3f, Int.MAX_VALUE)
    }
    survives("and a degenerate fit box") {
        fitBoxPx(Int.MAX_VALUE, Int.MAX_VALUE, 0.001f)
    }
}

fun main() {
    realPayloadChecks()
    jpegSearchChecks()
    headerShapeChecks()
    base64PrefixChecks()
    boxHeightChecks()
    fitBoxChecks()
    decodeGateChecks()
    // `pi.repo.root` is what `tools/run-app-pure-checks.sh` passes; the `user.dir`
    // fallback is the same one `PiSettingsAuditCheck` and `PiPreSpawnCheck` use, so a
    // hand-run from the repository root reads the same sources rather than reporting
    // `0/0` for wiring checks it could not perform.
    wiringChecks(
        System.getProperty("pi.repo.root")?.let { File(it) }
            ?: File(System.getProperty("user.dir") ?: "."),
    )
    robustnessChecks()

    if (failures != 0) {
        println("image-size: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}
