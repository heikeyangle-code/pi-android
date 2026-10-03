package app.pi.ui.blocks

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// A bare-JVM harness for `PiZoomDecodeWindow.kt` — the arithmetic behind 「放大之后按可见的那一块
// 重新解码」. Registered in `tools/run-app-pure-checks.sh` as `zoom-decode-window`, with
// `PiZoomDecodeWindow.kt` **and** `ImageSize.kt` in its closure (the same pure file the `image-size`
// and `pi-image-cache` harnesses compile): the last section executes this change's one input ceiling
// — `base64DecodedBytes` / `encodedImageWithinBudget` — rather than reading it. That registration
// line belongs to another batch; this file is written so it can be dropped in.
//
// Why this is worth pinning: the whole change answers 「放大了肯定要看到更多细节呀，不然放大还有啥
// 用？」 —— the viewer magnifies a bitmap that was sampled to the viewport, so zooming shows *upscaled
// sampling loss*. The fix decodes the visible part of the source at (near) 1:1 once the zoom passes a
// threshold, and the only part of that this machine can execute is the arithmetic: which source
// rectangle, at which sample factor, where it lands in the picture's own coordinate space, and — the
// hard requirement — that the block can never exceed a fixed number of pixels. Everything it gets
// wrong is invisible in a diff and shows up as a blurry patch with a seam, a rectangle outside the
// image (the platform decoder answers null, or decodes the wrong area), a pointless decode on every
// pan, or a memory ceiling that stops being true. So the ceiling is asserted as an inequality over a
// sweep of viewports, sources and zooms, not written as a comment.
//
// The call site (`PiImageViewer.kt`) cannot be compiled here at all — this machine has no android.jar
// and no Compose compiler plugin — so the last section also reads that file as source text and pins
// the wiring the interaction depends on: gestures `snapTo` (they must follow the finger), the double
// tap `animateTo`s (it must glide, and must not decode on every frame it passes through), and the
// detail decode sits under the same process-wide gate as the base one.
//
// `pi.repo.root` is what `tools/run-app-pure-checks.sh` passes; a plain `java` invocation falls back
// to this file's own location.

private var checks = 0
private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name: actual=$actual expected=$expected")
    } else {
        println("PASS  $name")
    }
}

private fun checkTrue(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    } else {
        println("PASS  $name")
    }
}

/** [checkTrue] without its `PASS` line: the sweeps run thousands of these. */
private fun checkSilent(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    }
}

// ---------------------------------------------------------------------------------------------
// The canonical case, spelled out: a 1080×2000 window and a 12 MP photo (4000×3000), whose
// viewport-sampled decode is 2000×1500 (`inSampleSize=2` — the area budget in `decodePiImageBytes`).
// ---------------------------------------------------------------------------------------------

private const val BOX_W = 1080
private const val BOX_H = 2000
private const val BASE_W = 2000
private const val BASE_H = 1500
private const val SRC_W = 4000
private const val SRC_H = 3000

/**
 * What `decodePiImageBytes` produces for this pair: halve while **both** axes still cover the box,
 * then halve again while the area is over [DECODE_AREA_BUDGET_FACTOR] box-areas. Mirroring it here is
 * the point — the memory argument is about the base bitmap and the source together, so the harness
 * has to know what the base actually is.
 */
private fun baseOf(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
    var w = srcW
    var h = srcH
    while (w / 2 >= boxW && h / 2 >= boxH) {
        w /= 2
        h /= 2
    }
    val budget = boxW.toLong() * boxH.toLong() * 4
    while (w >= 2 && h >= 2 && w.toLong() * h.toLong() > budget) {
        w /= 2
        h /= 2
    }
    return w to h
}

/** The base's own decimation: source pixels per base pixel. */
private fun decimationOf(srcW: Int, srcH: Int, baseW: Int, baseH: Int): Int =
    max(1, max((srcW + baseW - 1) / baseW, (srcH + baseH - 1) / baseH))

/** The pixels a [ZoomDecodeWindow] actually decodes to: the rectangle divided by its sample factor. */
private fun decodedPixels(w: ZoomDecodeWindow): Long {
    val width = (w.width + w.sample - 1) / w.sample
    val height = (w.height + w.sample - 1) / w.sample
    return width.toLong() * height.toLong()
}

/** Confinement: the one property a rectangle handed to a region decoder may never violate. */
private fun insideSource(r: SourceRect, srcW: Int, srcH: Int): Boolean =
    r.left >= 0 && r.top >= 0 && r.right <= srcW && r.bottom <= srcH && r.width > 0 && r.height > 0

private fun insideSource(w: ZoomDecodeWindow, srcW: Int, srcH: Int): Boolean =
    w.left >= 0 && w.top >= 0 && w.right <= srcW && w.bottom <= srcH && w.width > 0 && w.height > 0

private fun visible(
    boxW: Int,
    boxH: Int,
    srcW: Int,
    srcH: Int,
    scale: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): SourceRect? {
    val (bw, bh) = baseOf(srcW, srcH, boxW, boxH)
    return zoomVisibleSourceRect(boxW, boxH, bw, bh, srcW, srcH, scale, panX, panY)
}

private fun window(
    boxW: Int,
    boxH: Int,
    srcW: Int,
    srcH: Int,
    scale: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): ZoomDecodeWindow? {
    val (bw, bh) = baseOf(srcW, srcH, boxW, boxH)
    val r = zoomVisibleSourceRect(boxW, boxH, bw, bh, srcW, srcH, scale, panX, panY) ?: return null
    return zoomDecodeWindow(r, srcW, srcH, bw, bh)
}

/**
 * The one **input** ceiling this change adds, and the header read the arithmetic needs.
 *
 * `decodePiImage` used to base64-decode whatever payload it was handed and `decodePiImageFile`
 * called `file.readBytes()` on any file with an image suffix — the one image path in the app with no
 * bound on it. Both are now behind `ImageSize.MAX_INLINE_IMAGE_BYTES`, which is a *mirror* of
 * `GuestImageBytes.MAX_BYTES` (that one is `private` in a file this change does not own), so the
 * equality of the two numbers is pinned here as source text — the same way `ShellPolicyMirrorCheck`
 * keeps the two copies of the shell policy honest.
 *
 * This section needs `ImageSize.kt` in the harness's compile closure (the pure file, exactly as the
 * `image-size` harness compiles it): `base64DecodedBytes` and `encodedImageWithinBudget` are pure
 * arithmetic and are executed here, not read.
 */
private fun yardstickChecks() {
    check("an empty payload decodes to nothing", base64DecodedBytes(0), 0L)
    check("4 base64 chars are 3 bytes", base64DecodedBytes(4), 3L)
    check("11.2 M chars (an 8 MiB payload) is 8 MiB of bytes", base64DecodedBytes(11_184_812) / 1024 / 1024, 8L)
    checkTrue("a payload over 8 MiB is refused", !encodedImageWithinBudget(base64DecodedBytes(11_184_816))) {
        "actual=${base64DecodedBytes(11_184_816)}"
    }
    checkTrue("a payload at 8 MiB is still allowed", encodedImageWithinBudget(base64DecodedBytes(11_184_806)))
    checkTrue("a 300 MB file is refused before anything is read", !encodedImageWithinBudget(300L * 1024 * 1024))
    checkTrue("a 2 MB screenshot is allowed", encodedImageWithinBudget(2L * 1024 * 1024))
    checkTrue("an empty file is not a picture", !encodedImageWithinBudget(0L))

    // The header path the visible-block arithmetic rests on: the same one `naturalImageAspect` uses,
    // which is asserted by the `image-size` harness. A png is a field read at a fixed offset, so this
    // is also a check that the two functions cannot disagree about "is this a picture".
    val png = java.util.Base64.getEncoder().encodeToString(pngHeader(4000, 3000))
    check("the source's own pixel size comes back from the header", naturalImagePixels(png), ImagePixels(4000, 3000))
    check("and so does the aspect the 1x box uses", naturalImageAspect(png), 4000f / 3000f)
    check("bytes that are not a picture have no size", naturalImagePixels(java.util.Base64.getEncoder().encodeToString(ByteArray(32))), null)

    val root = repoRoot()
    val guest = root?.let { File(it, "app/src/main/kotlin/app/pi/bridge/GuestImageBytes.kt") }
    val grid = root?.let { File(it, "app/src/main/kotlin/app/pi/ui/blocks/ImageGridBlock.kt") }
    val guestText = if (guest != null && guest.isFile) guest.readText() else ""
    val gridText = if (grid != null && grid.isFile) grid.readText() else ""
    checkTrue("the app's 8 MiB image yardstick is still in GuestImageBytes", guestText.contains("MAX_BYTES = 8 * 1024 * 1024")) {
        "this harness mirrors that number; moving it there has to move ImageSize.MAX_INLINE_IMAGE_BYTES too"
    }
    check(
        "both whole-picture decode entries are behind the one gate",
        Regex("""encodedImageWithinBudget\(""").findAll(gridText).count(),
        2,
    )
}

/** A png whose IHDR states [width]×[height] — the CRC is not consulted, so it can be zeros. */
private fun pngHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(24)
    bytes[0] = 0x89.toByte()
    bytes[1] = 0x50
    bytes[2] = 0x4E
    bytes[3] = 0x47
    bytes[4] = 0x0D
    bytes[5] = 0x0A
    bytes[6] = 0x1A
    bytes[7] = 0x0A
    bytes[12] = 'I'.code.toByte()
    bytes[13] = 'H'.code.toByte()
    bytes[14] = 'D'.code.toByte()
    bytes[15] = 'R'.code.toByte()
    for (index in 0 until 4) {
        bytes[16 + index] = (width ushr (24 - 8 * index)).toByte()
        bytes[20 + index] = (height ushr (24 - 8 * index)).toByte()
    }
    return bytes
}

// ---------------------------------------------------------------------------------------------

private fun fitChecks() {    // `viewerFit` is also the 1× placement the viewer hands to its picture node, so a change here
    // moves the picture itself.
    checkTrue("a wide picture in a tall box fits by width", abs(viewerFit(BOX_W, BOX_H, BASE_W, BASE_H) - 0.54f) < 1e-6f) {
        "min(1080/2000, 2000/1500) = 0.54"
    }
    checkTrue("a tall picture in a wide box fits by height", abs(viewerFit(2000, 1000, 800, 2000) - 0.5f) < 1e-6f)
    checkTrue("a degenerate box is 1x rather than NaN", viewerFit(0, BOX_H, BASE_W, BASE_H) == 1f)
    checkTrue("a degenerate bitmap is 1x rather than NaN", viewerFit(BOX_W, BOX_H, 0, 0) == 1f)
}

private fun thresholdChecks() {
    // 1×: the whole picture is on screen at the sampled resolution — there is nothing to re-earn, and
    // requirement 2 is that this case keeps today's bitmap *and today's memory*.
    check("1x asks for no block", visible(BOX_W, BOX_H, SRC_W, SRC_H, 1f), null)
    check("1.5x asks for no block", visible(BOX_W, BOX_H, SRC_W, SRC_H, 1.5f), null)
    check("2x asks for no block", visible(BOX_W, BOX_H, SRC_W, SRC_H, 2f), null)

    // The threshold is on `scale·fit` (screen pixels per base pixel). `fit` is 0.54 here, so the block
    // is only useful from ~3.8× on: below that the sampled bitmap is still the sharper one, and the
    // budget would only buy a block as coarse as the base.
    check("3x is still below the deficit threshold", visible(BOX_W, BOX_H, SRC_W, SRC_H, 3f), null)
    checkTrue("4x crosses it", visible(BOX_W, BOX_H, SRC_W, SRC_H, 4f) != null)

    // 原图比视口还小: an 800×600 source in a 1080×2000 window decodes 1:1 (no sampling), so
    // `source ≤ base` and *nothing* is ever re-decoded — the strongest form of 「倍率只能是 1」:
    // 这里连一次解码都不发生.
    for (scale in listOf(1f, 2f, 4f, 8f)) {
        check("a source smaller than its decode (${scale}x) asks for no block", visible(BOX_W, BOX_H, 800, 600, scale), null)
        check("and no window either (${scale}x)", window(BOX_W, BOX_H, 800, 600, scale), null)
    }
    // Same source, huge window: still nothing to ask for.
    check("a 400x300 source in a huge box asks for no block", visible(4000, 4000, 400, 300, 8f), null)
}

private fun canonicalCaseChecks() {
    val r = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f)
    checkTrue("8x finds a block to decode", r != null)
    val v = r ?: return
    checkTrue("8x: the block is inside the source", insideSource(v, SRC_W, SRC_H), { "$v" })
    // Closed form: the visible *screen* width at 8× is boxW/scale = 135 fitted px, and the source is
    // 4000/1080 = 3.7037 source px per fitted px → 500 source px. ±1 because both ends are floor/ceil
    // on floats.
    checkTrue("8x: the visible block is one screen's worth - 500 source px wide", v.width in 499..501) {
        "actual=${v.width}"
    }
    // Visible screen height 2000/8 = 250 of the 810 fitted px → 925.9 source px.
    checkTrue("8x: and 926 source px tall", v.height in 925..927) { "actual=${v.height}" }
    checkTrue("8x: it is centred while the pan is zero", abs(v.left - 1750) <= 1 && abs(v.top - 1037) <= 1) {
        "actual=$v"
    }

    val w = window(BOX_W, BOX_H, SRC_W, SRC_H, 8f)
    checkTrue("8x: a window is asked for", w != null)
    val block = w ?: return
    check("8x: the margin still fits the budget, so the sample factor is 1", block.sample, 1)
    checkTrue("8x: the window is a quarter wider on each side", block.width == 750 && block.height in 1387..1389) {
        "actual=$block"
    }
    checkTrue("8x: the window decodes to ~1.0 M px = ~4 MB", decodedPixels(block) in 1_000_000..1_100_000) {
        "actual=${decodedPixels(block)}"
    }
    checkTrue("8x: and it is strictly sharper than the base", block.sample < decimationOf(SRC_W, SRC_H, BASE_W, BASE_H)) {
        "base decimation is 2, block sample is ${block.sample}"
    }
}

/**
 * The margin is the first thing the budget takes away. At 4× the margined block would have to be
 * sampled 2× — exactly as coarse as the base, i.e. worthless — while the visible block itself still
 * fits at 1:1. Without this fallback 4×–5× (a pinch the user can stop at) would show nothing new.
 */
private fun marginFallbackChecks() {
    val v = visible(BOX_W, BOX_H, SRC_W, SRC_H, 4f) ?: run {
        checkTrue("4x has a visible block", false)
        return
    }
    val w = window(BOX_W, BOX_H, SRC_W, SRC_H, 4f) ?: run {
        checkTrue("4x gets a window instead of nothing", false)
        return
    }
    check("4x: the margin is dropped rather than the resolution", w.sample, 1)
    checkTrue("4x: the window is the visible block itself, not a quarter wider", w.left == v.left && w.right == v.right && w.width in 999..1001) {
        "window=$w visible=$v"
    }
    checkTrue("4x: it still fits the 2 M px budget", decodedPixels(w) <= DETAIL_MAX_PIXELS) {
        "actual=${decodedPixels(w)}"
    }
    checkTrue("4x: and it is strictly sharper than the base", w.sample < decimationOf(SRC_W, SRC_H, BASE_W, BASE_H))
}

private fun aspectChecks() {
    // A 20:1 panorama, decoded as `decodePiImageBytes` would: the area budget halves it to 10000×500,
    // whose fit is 0.108 — at 8× each base pixel is still under one screen pixel, so the sampled
    // bitmap is *already* sharper than the screen and no block is asked for.
    check("a 20:1 panorama needs no block at 8x", visible(BOX_W, BOX_H, 20000, 1000, 8f), null)
    // A 1:20 column (500×10000 at sample 2): fit 0.2 → 1.6 screen px per base px at 8×, just under
    // the threshold. Nothing is decoded, and nothing has to be: this is the documented edge of
    // 「够用且简单」.
    check("a 1:20 column needs no block at 8x", visible(BOX_W, BOX_H, 1000, 20000, 8f), null)

    // A 8:3 source that *is* magnified past the threshold: 8000×3000 → base 4000×1500 (sample 2),
    // fit 0.27 → 8×·0.27 = 2.16 > 2.
    val wide = window(BOX_W, BOX_H, 8000, 3000, 8f)
    checkTrue("a 8:3 source at 8x gets a confined block", wide != null && insideSource(wide, 8000, 3000)) {
        "$wide"
    }
    checkTrue("a 8:3 source at 8x stays under the pixel budget", wide != null && decodedPixels(wide) <= DETAIL_MAX_PIXELS)

    // A 1:8 source in a square box, magnified past the threshold (base 600x4800 at sample 2, fit
    // 0.3 -> 8x*0.3 = 2.4). A *bigger* 1:8 source is deliberately not asserted here: at 12000x96000
    // the base comes out 750x6000, fit 0.24, and 8x*0.24 = 1.92 is still below the threshold -- the
    // sampled bitmap is the sharper one, and the arithmetic says so.
    val tallBase = baseOf(1200, 9600, 1440, 1440)
    val tall = window(1440, 1440, 1200, 9600, 8f)
    checkTrue("a 1:8 source at 8x gets a confined block", tall != null && insideSource(tall, 1200, 9600)) {
        "$tall base=$tallBase"
    }
    checkTrue("a 1:8 source at 8x stays under the pixel budget", tall != null && decodedPixels(tall) <= DETAIL_MAX_PIXELS)
    checkTrue("a 1:8 source at 8x is still sharper than its base", tall != null &&
        tall.sample < decimationOf(1200, 9600, tallBase.first, tallBase.second))

    // A 1:12 column in a tall phone window: as extreme as it gets while still earning a block.
    val column = window(BOX_W, BOX_H, 1000, 12000, 8f)
    checkTrue("a 1:12 source at 8x gets a confined block", column != null && insideSource(column, 1000, 12000)) {
        "$column"
    }
    checkTrue("a 1:12 source at 8x stays under the pixel budget", column != null && decodedPixels(column) <= DETAIL_MAX_PIXELS)
}

private fun panChecks() {
    // The pan the decode arithmetic sees is the one the gesture path produces, so the sweep drives the
    // app's own rule (`panLimit` + `panAfterDrag`) with absurd *screen* finger travel: at every extreme
    // the block must still be a legal, non-empty rectangle inside the source, and the far edge must be
    // reachable.
    val fit = viewerFit(BOX_W, BOX_H, BASE_W, BASE_H)
    val fittedWidth = BASE_W * fit
    val fittedHeight = BASE_H * fit
    val maxPanX = panLimit(8f, fittedWidth, BOX_W.toFloat())
    val maxPanY = panLimit(8f, fittedHeight, BOX_H.toFloat())
    var farRight = 0
    var farBottom = 0
    for (rawX in listOf(-1e5f, -5000f, -1080f, -500f, 0f, 500f, 1080f, 5000f, 1e5f)) {
        for (rawY in listOf(-1e5f, -5000f, -800f, 0f, 800f, 5000f, 1e5f)) {
            // `rawX` is the *screen* travel of the finger; the gesture layer hands over `rawX / 8`.
            val px = panAfterDrag(0f, rawX / 8f, 8f, maxPanX)
            val py = panAfterDrag(0f, rawY / 8f, 8f, maxPanY)
            val r = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f, px, py)
                ?: run { checkSilent("a clamped pan still yields a block", false) { "$rawX,$rawY" }; continue }
            checkSilent("a clamped pan yields a legal block", insideSource(r, SRC_W, SRC_H)) { "$r (pan $px,$py)" }
            farRight = max(farRight, r.right)
            farBottom = max(farBottom, r.bottom)
        }
    }
    check("dragging to the clamp reaches the source's right edge", farRight, SRC_W)
    check("dragging to the clamp reaches the source's bottom edge", farBottom, SRC_H)

    // An unclamped, absurd pan must not produce an illegal rectangle either: `zoomVisibleSourceRect`
    // clamps into the picture, because between a gesture write and the decode there is a race.
    val absurd = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f, 99_999f, -99_999f)
    checkTrue("an absurd pan is clamped into the source", absurd != null && insideSource(absurd, SRC_W, SRC_H)) { "$absurd" }
}

/**
 * 拖动跟不跟手 —— **这就是用户报的「放大了之后左右上下滑动，滑的特别特别慢」**。
 *
 * The mechanism is quoted in [panStepFromGesture]'s KDoc and it was read out of the platform's own
 * sources: the gesture layer hands the finger's travel over in the *local* space inside
 * `graphicsLayer` (so divided by the scale), while `translationX/Y` is applied *outside* the scale
 * (hwui composes `T·R·S`). Feeding `drag` straight into `pan` therefore moved the picture by
 * `1/scale` of the finger — 1/8 at 8×.
 *
 * What is pinned here: the round trip (screen travel in → the same screen travel out), that the
 * conversion is a no-op at 1×, that the clamp only ever changes an out-of-range value, and that
 * sixty dragged frames accumulate without drift.
 */
private fun panMappingChecks() {
    // 1×: the conversion must be a no-op, so nothing about the un-zoomed picture changes.
    for (travel in listOf(0f, 1f, 7.5f, -40f, 1234.5f)) {
        checkTrue("at 1x the gesture step is the finger's own travel ($travel)", panStepFromGesture(travel, 1f) == travel)
    }

    // The round trip: the finger travels `screen` px, the gesture layer delivers `screen / scale`, and
    // the picture must end up exactly `screen` px further along. The 0.01 px allowance is the float
    // error of divide-then-multiply, not slack in the requirement (a pixel is 100x bigger).
    var worst = 0f
    var roundTrips = 0
    for (scale in listOf(1f, 1.5f, 2f, 2.5f, 4f, 5.5f, 8f)) {
        for (screen in listOf(-500f, -37.5f, -1f, 0f, 1f, 12.5f, 80f, 333.25f)) {
            val delivered = screen / scale
            val moved = panStepFromGesture(delivered, scale)
            val error = abs(moved - screen)
            if (error > worst) worst = error
            roundTrips++
            checkSilent("the picture follows the finger 1:1 (${screen}px at ${scale}x)", error <= 0.01f) {
                "moved=$moved wanted=$screen error=$error"
            }
        }
    }
    check("the 1:1 round trip was checked across the zoom range", roundTrips, 56)
    checkTrue("and its worst float error is under a hundredth of a pixel", worst <= 0.01f) { "worst=$worst" }

    // The defect itself, so that dropping the conversion cannot pass this file: an 80 px drag at 8×
    // used to move the picture 10 px.
    check("unconverted, an 80px drag at 8x moves 10px (the bug)", 80f / 8f, 10f)
    checkTrue("converted, 80px of finger is 80px of picture", abs(panStepFromGesture(80f / 8f, 8f) - 80f) <= 0.01f)

    // Clamp: in range the value passes through untouched (that is "逐像素跟手"), out of range it is
    // pinned to the limit (no bounce, no animation, no damping).
    val limit = 500f
    // In range: the value must come out exactly as it went in. These pairs are chosen so that
    // `pan + travel` stays inside ±500 (the clamp is allowed to touch anything outside that).
    for ((pan, travel) in listOf(
        -400f to -50f,
        -100f to 0f,
        0f to 1f,
        100f to 25f,
        450f to 50f,
        499f to 0f,
        -499f to 0f,
    )) {
        val next = panAfterDrag(pan, travel, 1f, limit)
        checkSilent("an in-range drag is passed through untouched", next == pan + travel) { "$pan + $travel -> $next" }
    }
    check("a drag past the edge is pinned to the limit", panAfterDrag(490f, 50f, 1f, limit), 500f)
    check("and so is one past the other edge", panAfterDrag(-490f, -50f, 1f, limit), -500f)
    check("a drag that lands exactly on the edge is not altered", panAfterDrag(480f, 20f, 1f, limit), 500f)
    checkTrue("a drag inside the range is never moved by the clamp", panAfterDrag(120f, 3f, 1f, limit) == 123f)

    // 1× never pans: `viewerFit` is `min(box / bitmap)`, so the fitted picture never overflows the box,
    // so the limit is zero — the "reset to centre at 1×" is the clamp's own result.
    val fit = viewerFit(BOX_W, BOX_H, BASE_W, BASE_H)
    check("at 1x the pan limit is zero on x", panLimit(1f, BASE_W * fit, BOX_W.toFloat()), 0f)
    check("at 1x the pan limit is zero on y", panLimit(1f, BASE_H * fit, BOX_H.toFloat()), 0f)
    check(
        "so a drag at 1x leaves the picture centred",
        panAfterDrag(0f, 999f, 1f, panLimit(1f, BASE_W * fit, BOX_W.toFloat())),
        0f,
    )
    // An axis that still fits at any zoom has no slack either (a very wide picture).
    check("an axis that still fits has no slack", panLimit(8f, 100f, BOX_W.toFloat()), 0f)
    check("and one that does not has the expected half-overflow", panLimit(8f, 300f, 400f), 1000f)

    // Sixty frames of continuous dragging accumulate exactly: no drift, no damping, no lag.
    var pan = 0f
    repeat(60) { pan = panAfterDrag(pan, 6f / 8f, 8f, limit) }
    checkTrue("60 frames of 6px screen travel accumulate to 360px", abs(pan - 360f) <= 0.05f) { "pan=$pan" }
}

/**
 * 落定闸：拖动期间**一次解码都不许开始**，松手之后恰好落定一次 —— 「解码不在手势路径上」这句话
 * 只有真的跑一遍协程才算验过（写在 Composable 里的 `delay` 只能靠读码相信）。
 *
 * Real time, with a 15x margin: the gate is 120 ms of quiet and the simulated finger moves every 8 ms,
 * so the "shut for the whole drag" assertion only breaks if this machine stalls for fifteen frames in
 * a row.
 */
private fun settleGateChecks() {
    val settle = 120L
    runBlocking {
        val probes = MutableStateFlow<ZoomProbe?>(null)
        val settled = ArrayList<ZoomProbe>()
        val collector = launch { probes.settledProbes(settle).collect { settled.add(it) } }
        try {
            // ① 拖动：每 8 ms 一帧、连续 40 帧（320 ms），从来安静不到 120 ms
            var pan = 0f
            repeat(40) {
                pan += 8f
                probes.value = ZoomProbe(8f, pan, 0f)
                delay(8)
            }
            check("the settle gate stays shut for the whole drag", settled.size, 0)

            // ② 松手：安静之后恰好落定一次，值是最后一帧
            delay(settle + 200)
            check("releasing the finger settles exactly once", settled.size, 1)
            check("and the settled view is the last frame of the drag", settled.lastOrNull(), ZoomProbe(8f, 320f, 0f))

            // ③ 双击动画帧（null）：不算落定，连延时都不排队
            probes.value = null
            delay(settle + 200)
            check("an animation frame is not a settled view", settled.size, 1)

            // ④ 动画结束、图停在 1×：再落定一次新值（这就是「缩回 1× 放掉那一块」的触发）
            probes.value = ZoomProbe(1f, 0f, 0f)
            delay(settle + 200)
            check("the view the animation ended on settles once", settled.size, 2)
            check("with the value it ended on", settled.lastOrNull(), ZoomProbe(1f, 0f, 0f))
        } finally {
            collector.cancel()
        }
    }
}

private fun tilingChecks() {
    // 平移时先用上一块顶着：余量 25% 的意思是「平移不到屏幕宽度的四分之一就不必重解」。
    val visibleAtZero = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f) ?: return
    val held = window(BOX_W, BOX_H, SRC_W, SRC_H, 8f) ?: return
    checkTrue("the block covers the view it was decoded for", zoomDecodeWindowCovers(held, visibleAtZero))

    val small = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f, BOX_W * 0.2f, 0f) ?: return
    checkTrue("a small pan stays inside the held block (no re-decode)", zoomDecodeWindowCovers(held, small)) {
        "held=$held visible=$small"
    }
    val large = visible(BOX_W, BOX_H, SRC_W, SRC_H, 8f, BOX_W * 0.5f, 0f) ?: return
    checkTrue("a large pan leaves it and asks for a new block", !zoomDecodeWindowCovers(held, large)) {
        "held=$held visible=$large"
    }
    checkTrue("the new block covers the new view", zoomDecodeWindowCovers(window(BOX_W, BOX_H, SRC_W, SRC_H, 8f, BOX_W * 0.5f, 0f)!!, large))

    // 缩回 1×：没有可见区，也就没有块 —— 1× 的内存与今天逐字节相同。
    check("zooming back to 1x releases the block (no rect)", visible(BOX_W, BOX_H, SRC_W, SRC_H, 1f), null)
}

/**
 * The hard ceiling, as an inequality rather than a comment.
 *
 * Three claims, over a sweep of viewports × sources × zooms:
 *
 *  1. every window decodes to at most [DETAIL_MAX_PIXELS] pixels, whatever the source is — this is
 *     what makes 「内存与原图多大无关」 true for the bitmaps;
 *  2. a window is only ever handed back when it is *strictly* sharper than the base bitmap, so the
 *     budget is never spent on something the screen already has;
 *  3. **1× never asks for anything** — the property requirement 2 rests on.
 */
private fun budgetChecks() {
    val viewports = listOf(
        BOX_W to BOX_H,
        1080 to 2400,
        1440 to 2960,
        2560 to 1600,
        3840 to 2160,
        720 to 1280,
    )
    val sources = listOf(
        4000 to 3000, // 12 MP photo
        8160 to 6120, // 50 MP photo
        1080 to 2400, // a phone screenshot
        8000 to 6000,
        1200 to 9600, // 1:8
        9600 to 1200, // 8:1
        20000 to 15000, // 300 MP, the 「绝不能整张解」 case
        400 to 300, // smaller than the viewport
    )
    var decisions = 0
    var biggest = 0L
    var biggestAt = ""
    var decodesAtOne = 0
    for ((vw, vh) in viewports) {
        for ((sw, sh) in sources) {
            val (bw, bh) = baseOf(sw, sh, vw, vh)
            val decimation = decimationOf(sw, sh, bw, bh)
            for (scale in listOf(1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f)) {
                val r = zoomVisibleSourceRect(vw, vh, bw, bh, sw, sh, scale, 0f, 0f)
                if (scale == 1f && r != null) decodesAtOne++
                if (r == null) continue
                checkSilent("the visible block is inside the source", insideSource(r, sw, sh)) { "$r" }
                val w = zoomDecodeWindow(r, sw, sh, bw, bh) ?: continue
                decisions++
                val pixels = decodedPixels(w)
                if (pixels > biggest) {
                    biggest = pixels
                    biggestAt = "src=${sw}x$sh box=${vw}x$vh scale=$scale window=${w.width}x${w.height}/${w.sample}"
                }
                checkSilent("every window decodes to <= DETAIL_MAX_PIXELS", pixels <= DETAIL_MAX_PIXELS) {
                    "src=${sw}x$sh box=${vw}x$vh scale=$scale → $pixels"
                }
                checkSilent("a window is only asked for when it is sharper than the base", w.sample < decimation) {
                    "src=${sw}x$sh box=${vw}x$vh scale=$scale → sample=${w.sample}, base decimation=$decimation"
                }
                checkSilent("the window is inside the source", insideSource(w, sw, sh)) { "$w" }
                checkSilent("the window is aligned to its sample factor", w.left % w.sample == 0 && w.top % w.sample == 0) { "$w" }
                checkSilent("the sample factor is a power of two", w.sample >= 1 && (w.sample and (w.sample - 1)) == 0) { "$w" }
                checkSilent("the margin never pushes the window outside the budget", pixels <= DETAIL_MAX_PIXELS) { "$w" }
            }
        }
    }
    checkTrue("the sweep actually made decode decisions", decisions > 40) { "decisions=$decisions" }
    check("1x never asks for a block, over the whole sweep", decodesAtOne, 0)
    println("      biggest block over the sweep: $biggest px = ${biggest * 4} B ($biggestAt)")
    checkTrue("the biggest block over the sweep is under 8 MB", biggest * 4 <= 8_000_000L) {
        "actual=${biggest * 4} B"
    }
}

private fun placementChecks() {
    // The patch is placed in the picture's own (1×) coordinate space and the parent's `graphicsLayer`
    // magnifies it, so the placement must be inside the fitted picture at every zoom — and its two
    // axes must keep the block's own aspect ratio.
    val fit = viewerFit(BOX_W, BOX_H, BASE_W, BASE_H)
    val fittedWidth = BASE_W * fit
    val fittedHeight = BASE_H * fit
    var placements = 0
    for (scale in listOf(4f, 5f, 6f, 8f)) {
        val w = window(BOX_W, BOX_H, SRC_W, SRC_H, scale) ?: continue
        val at = zoomDecodePlacement(w, BOX_W, BOX_H, BASE_W, BASE_H, SRC_W, SRC_H)
        placements++
        checkSilent("the placement is inside the fitted picture", at.left >= -0.5f && at.top >= -0.5f) { "$at" }
        checkSilent("the placement's far edge is inside the picture", at.left + at.width <= fittedWidth + 0.5f && at.top + at.height <= fittedHeight + 0.5f) { "$at" }
        val windowAspect = w.width.toFloat() / w.height
        val placementAspect = at.width / at.height
        checkSilent("the placement keeps the block's aspect ratio", abs(windowAspect - placementAspect) < 1e-3f) {
            "$windowAspect vs $placementAspect"
        }
    }
    check("the placement checks ran", placements, 4)
}

/**
 * Why this section exists: the numbers in the change report have to be reproducible, and the claim the
 * user asked to be able to trust — 「放大真的出细节」 with a memory ceiling — is a set of pixel counts.
 * Printing them is the raw evidence.
 */
private class Evidence(val label: String, val boxW: Int, val boxH: Int, val srcW: Int, val srcH: Int)

private fun evidence() {
    println()
    println("kind                                    box         source       1× decode        8× block: source rect  sample  decode px    bytes")
    for (case in listOf(
        Evidence("12 MP photo (user's case)", 1080, 2000, 4000, 3000),
        Evidence("50 MP photo", 1080, 2400, 8160, 6120),
        Evidence("phone screenshot (source=viewport)", 1080, 2400, 1080, 2400),
        Evidence("20:1 panorama", 1080, 2000, 20000, 1000),
        Evidence("300 MP (must never be decoded whole)", 1080, 2400, 20000, 15000),
    )) {
        val (bw, bh) = baseOf(case.srcW, case.srcH, case.boxW, case.boxH)
        val w = window(case.boxW, case.boxH, case.srcW, case.srcH, 8f)
        val block = if (w == null) {
            "none (nothing to earn)"
        } else {
            val at = zoomDecodePlacement(w, case.boxW, case.boxH, bw, bh, case.srcW, case.srcH)
            "%-12s %-19s %6d  %10d B  %8.1f KB for the decode"
                .format("${w.width}x${w.height}", "${w.sample}×", decodedPixels(w), decodedPixels(w) * 4, decodedPixels(w) * 4 / 1024.0)
                .let { "$it  (placed ${"%.0f,%.0f %.0fx%.0f".format(at.left, at.top, at.width, at.height)} in picture px)" }
        }
        println("%-39s %-11s %-12s %-16s %s".format(case.label, "${case.boxW}x${case.boxH}", "${case.srcW}x${case.srcH}", "${bw}x$bh", block))
    }
    println("      1× decode = 按视口采样出来的基础图（今天就有，缓存在 PiImageCache 里）")
    println("      8× block  = 区域解码：只解可见的一块，sample× 是它的 inSampleSize")
    println("      limit: DETAIL_MAX_PIXELS=${DETAIL_MAX_PIXELS} px = ${DETAIL_MAX_PIXELS * 4} B = ${DETAIL_MAX_PIXELS * 4 / (1024 * 1024)} MiB per block")
    println()
}

/**
 * The wiring, read as source text: `PiImageViewer.kt` cannot be compiled on this machine (no
 * android.jar, no Compose compiler), and these are the facts of the interaction no numeric check can
 * reach.
 */
private fun wiringChecks() {
    val file = repoRoot()?.let { File(it, "app/src/main/kotlin/app/pi/ui/blocks/PiImageViewer.kt") }
    val text = if (file != null && file.isFile) file.readText() else ""

    checkTrue("the viewer file was found", text.isNotEmpty())
    // 手势必须跟手：`snapTo`（不是动画）。双击必须过渡：`animateTo`。
    checkTrue("pinch and drag write the value with snapTo", text.contains(".snapTo(")) {
        "a gesture animated through a spring would lag the finger"
    }
    checkTrue("the double tap animates with animateTo", text.contains(".animateTo("))
    // 注释要先剥掉：这份文件的 KDoc **点名**了它拒掉的那个 API（"不用 animateFloatAsState"），
    // 而"关于一个缺陷的散文"不该被当成那个缺陷（`ImageSizeCheck` 剥注释时踩过同一个坑）。
    val code = text.lines()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")
    checkTrue("no animateFloatAsState in the viewer", !code.contains("animateFloatAsState")) {
        "a single animatable written by both a gesture and an animation is the documented way to make them fight"
    }
    // 重解只认落定的倍率：驱动是一个 snapshotFlow + 去抖的 delay，并且动画帧直接发 null。
    checkTrue("the detail decode is driven by a snapshotFlow", text.contains("snapshotFlow {"))
    checkTrue("and waits for the view to settle before decoding", text.contains(".settledProbes(ZOOM_SETTLE_MILLIS)"))
    checkTrue("and does not even evaluate the probe while the animation runs", text.contains("if (animating) null else ZoomProbe(")) {
        "an animation frame must not reach the decode decision, let alone allocate for it"
    }
    // 键不该每帧重算：HostFile 的 cacheKey 是构造时的 val（getter 会每读一次 stat 一次）。
    checkTrue("the host-file key is computed once, not per read", text.contains("override val cacheKey: String =")) {
        "a `get() =` here is two file-system calls per read, six reads per composition"
    }
    checkTrue("and it is not a getter any more", !text.contains("get() = \"file:"))
    // 一把闸门：两种解码（1× 的基础图、放大后的块）都从同一个 withPermit 走。
    check(
        "both decodes share the one process-wide gate",
        Regex("""piImageDecodeGate\.withPermit""").findAll(text).count(),
        1,
    )
    checkTrue("and the region decode goes through it too", text.contains("underImageDecodeGate")) {
        "a second gate (or none) would let zoom decodes crowd out the transcript's cells"
    }
    checkTrue("the base decode still goes through PiImageCache", text.contains("PiImageCache.readThrough("))
    // 兜底：解不出块时保持上一块 / 退回基础图，而不是白屏。
    checkTrue("a failed region decode leaves the block that is on screen alone", text.contains("?: return@collectLatest")) {
        "null from the decoder must keep the previous patch (or the base bitmap) visible"
    }
    checkTrue("and the region decode is wrapped so it cannot throw", text.contains("BitmapRegionDecoder")) {
        "the platform decoder throws IOException on bytes it does not understand"
    }
    // 拖动跟手：两个轴都必须走 [panAfterDrag]，而旧的原地夹取必须真的没了（留着一份 = 两份规则）。
    check(
        "both axes pan through the pure conversion + clamp",
        Regex("""panAfterDrag\(""").findAll(code).count(),
        2,
    )
    checkTrue("the in-file clamp is gone (one rule, one place)", !code.contains("clampPan(")) {
        "a second copy of the clamp is how the conversion and the bound drift apart"
    }
    checkTrue("and the pan limit comes from the pure function too", code.contains("panLimit("))
    // 落定闸：解码只能从落定流里来（`.settledProbes(` 在 `decodePiImageRegion` 之前）。
    checkTrue("the decode is driven by the settle gate", code.contains(".settledProbes("))
    checkTrue(
        "and only downstream of it",
        code.indexOf("decodePiImageRegion(") > code.indexOf(".settledProbes("),
    ) { "the decode must not be reachable from a gesture frame" }
    // 变换读在绘制期（layer 的 block），不是组合期的参数：拖动一帧只重画那一层，不重组合。
    checkTrue("the transform is read inside the graphicsLayer block", code.contains("graphicsLayer {"))
    check(
        "and not through graphicsLayer's parameters (which would recompose every frame)",
        Regex("""graphicsLayer\(\s*(scaleX|scaleY|translationX|translationY|alpha|rotationZ)""").findAll(code).count(),
        0,
    )
    // 手势那一帧路径上不许出现解码：只有换算与 `snapTo`。
    // 从**调用**处切（`detectTransformGestures {`），不是从那一行 import —— 后者会把整个文件的
    // 上半截当成"手势块"。
    val gesture = code.substringAfter("detectTransformGestures {", "").substringBefore(".pointerInput(", "")
    checkTrue(
        "the drag path contains no decode and no I/O",
        gesture.isNotEmpty() &&
            !gesture.contains("decodePiImageRegion") &&
            !gesture.contains("zoomDecodeWindow") &&
            !gesture.contains("withContext") &&
            !gesture.contains("PiImageCache"),
    ) { "a decode on the frame path is exactly the slow the user reported" }
    checkTrue(
        "it goes through the pure pan arithmetic instead",
        gesture.contains("panAfterDrag(") && gesture.contains("panLimit("),
    )
}

private fun repoRoot(): File? {
    System.getProperty("pi.repo.root")?.let { return File(it) }
    var dir: File? = File(System.getProperty("user.dir") ?: ".")
    repeat(6) {
        val candidate = dir ?: return null
        if (File(candidate, "tools/run-app-pure-checks.sh").isFile) return candidate
        dir = candidate.parentFile
    }
    return null
}

fun main() {
    yardstickChecks()
    panMappingChecks()
    settleGateChecks()
    fitChecks()
    thresholdChecks()
    canonicalCaseChecks()
    marginFallbackChecks()
    aspectChecks()
    panChecks()
    tilingChecks()
    budgetChecks()
    placementChecks()
    evidence()
    wiringChecks()
    println()
    if (failures == 0) {
        println("harness: OK ($checks checks)")
    } else {
        println("harness: FAILED ($failures of $checks checks failed)")
        exitProcess(1)
    }
}
