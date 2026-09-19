package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiImage
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `message-images` (docs/pi-android-ui-spec.md §7.4): one image fills the width,
 * two to four make a 2x2 grid, more than four show a grid with a `+N` badge.
 * 12dp radius.
 *
 * **Every cell is a tap target that opens the picture.** The viewer
 * ([PiImageViewer]) is the surface F19 (`docs/rendering-review.md`) recorded as
 * missing — the `onImageClick` parameter it deleted is back because the thing it
 * promised now exists. The tap is handled *here*, on the cell, not by any ancestor:
 * Compose hit-tests the deepest node first, so a cell's `clickable` claims the tap
 * before a card's 展开/收起 can see it. That is the mechanism the tool card depends
 * on (see `ToolCallBlock`'s note), and it is why "tap the image" and "tap the rest
 * of the card" can be two different actions without any gesture arbitration.
 *
 * ## A thumbnail shows the whole picture
 *
 * The cells used to be a fixed 180 dp / square box with `ContentScale.Crop`, which
 * fills the box by **cutting the picture down** — the user's report was
 * 「缩略图显示的不全，缩略图也得是完整的吧」. Crop is the wrong default here: this is a
 * record of what was sent or produced, so a thumbnail that hides part of it is
 * misreporting the content, and the one thing the reader wants to check ("is this the
 * screenshot I meant?") is exactly what got trimmed.
 *
 * So the two shapes answer the two cases differently, on purpose:
 *
 *  - **one image** — the payload's own ratio ([naturalImageAspect], read out of the
 *    bytes' header before anything is decoded), capped at
 *    [SINGLE_IMAGE_MAX_HEIGHT_FRACTION] of the window's height. A lone picture is the
 *    main content of its row and letterboxing it inside a full-width square would
 *    waste the row; the cap is what keeps a 1:5 screenshot from becoming five screens
 *    tall, and past the cap `ContentScale.Fit` shows it whole, small, still tappable
 *    for the full view ([PiImageViewer]).
 *  - **two or more** — the grid keeps its equal square cells (`06 §3` 构件 12 图片网格 is
 *    a grid, and ragged row heights would stop it reading as one), and the scale moves
 *    from `Crop` to `Fit`: every picture is complete inside its cell, with the cell's
 *    own `cardBg` as the letterbox. Tidiness is what the grid is for; a complete
 *    picture is the hard requirement, and `Fit` satisfies both.
 *
 * ## The row has its final height before the picture does
 *
 * 「我速度稍稍微微快一点，这个图片会从底部直接闪现到全面露出来」 is a **row height
 * change**, and the cell used to make one every time a picture entered the viewport: its
 * box came from the *decoded* bitmap (`decoded.width / decoded.height`) and, until that
 * decode returned, from `Modifier.fillMaxSize()`. On the height axis the constraint is
 * unbounded and `fillMaxSize` passes it straight through (`FillNode.measure` computes a
 * fixed size only `if (constraints.hasBoundedHeight)`, `compose/foundation/foundation-layout
 * .../Size.kt`; checked in the `fillMaxSize` implementation this build resolves), so the
 * pre-decode height was whatever the contents measured — the labelled fallback's two
 * lines — and then the picture landed and the row jumped to the full natural height.
 * Both halves are fixed here:
 *
 *  - the box is stated from the payload's header, so it is final on the first frame and
 *    the decode cannot move it: [singleImageBoxHeightPx] is the only place a
 *    single-image row's height comes from;
 *  - the labelled fallback is no longer what sits under a running decode — the box is
 *    empty until the bitmap (or the codec's refusal) arrives, so a picture does not flash
 *    「图片 1 / image/png」 on its way in.
 *
 * The cap is an explicit `height`, not `aspectRatio(...).heightIn(max = …)`. The latter
 * reads as if it capped the row and does not: modifiers are measured outside-in, so
 * `aspectRatio` — handed an unbounded height by `fillMaxWidth` — took `width / ratio`
 * (`AspectRatioNode.findSize` → `tryMaxWidth`, which accepts any height the incoming
 * constraints allow, and `Infinity` allows every one of them) and `heightIn` only shrank
 * what was drawn *inside* that too-tall box. A 1080×2400 screenshot therefore made a row
 * as tall as the window itself with a 0.6-window card at its top: the picture was capped,
 * the row was not. Stating the height directly makes the row exactly as tall as
 * [singleImageBoxHeightPx] says — and since both of its inputs are known before the
 * decode, that height never changes either.
 *
 * The bytes are already here: `PiImage` carries base64 inline
 * (`rpc/Commands.kt:13`), so the cell decodes them with the platform codec and
 * only falls back to a labelled placeholder when the payload is not decodable.
 * Decoding runs on [Dispatchers.IO] — a base64 image is not composition work — under
 * [piImageDecodeGate], so a fling cannot start a dozen screenshot decodes at once, and is
 * sampled to the pixels that will be drawn ([fitBoxPx]) instead of to the card: a 1:5
 * screenshot in a full-width card is a narrow column in the middle, and sampling to the
 * card would decode four times the pixels that reach the screen. Each cell decodes
 * **once** per (payload, box) pair: `produceState` is keyed on both, and the
 * single-image cell's box comes from the payload's header rather than from the decoded
 * bitmap, so the "box depends on the ratio depends on the box" cycle cannot form. The
 * viewer decodes its own copy at screen size — a *second* decode, deliberately: it is a
 * different resolution for a different surface, not a repeat of the same one (its box
 * stays the whole window because pinching magnifies those pixels up to 8×).
 *
 * @param onImageClick null keeps the cells inert, which is what a preview or a test
 *   that has no viewer to raise passes.
 */
@Composable
fun ImageGridBlock(
    images: List<PiImage>,
    modifier: Modifier = Modifier,
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val spacing = PiSpacing.gutter

    if (images.size == 1) {
        // No `aspectRatio` here: the cell takes its height from the payload's own header
        // (see [ImageCell]'s `naturalAspect`). The caller's modifier is applied like it is
        // for the grid below — it used to be dropped on this branch, which is how a tool
        // card's lone screenshot lost the `top` padding every other block kept.
        ImageCell(
            image = images[0],
            index = 0,
            modifier = modifier.fillMaxWidth(),
            onClick = onImageClick,
            naturalAspect = true,
        )
        return
    }

    val visible = if (images.size > MAX_GRID_IMAGES) MAX_GRID_IMAGES else images.size
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        var index = 0
        while (index < visible) {
            val remaining = visible - index
            val inRow = if (remaining >= 2) 2 else 1
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (slot in 0 until inRow) {
                    val position = index + slot
                    val overflow = if (position == visible - 1 && images.size > MAX_GRID_IMAGES) {
                        images.size - MAX_GRID_IMAGES
                    } else {
                        0
                    }
                    ImageCell(
                        image = images[position],
                        index = position,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        overflow = overflow,
                        onClick = onImageClick,
                    )
                }
                if (inRow == 1) Spacer(Modifier.weight(1f))
            }
            index += inRow
        }
    }
}

@Composable
private fun ImageCell(
    image: PiImage,
    index: Int,
    modifier: Modifier = Modifier,
    overflow: Int = 0,
    onClick: ((PiImage) -> Unit)? = null,
    /**
     * Size the box from the payload's own header (the single-image case) and cap that
     * height at [SINGLE_IMAGE_MAX_HEIGHT_FRACTION] of the window, instead of filling the
     * box the caller imposed. The picture is drawn with `Fit` either way, so the cap
     * letterboxes rather than crops.
     */
    naturalAspect: Boolean = false,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    // The cap, in pixels. A single image's cell is `fillMaxWidth()` inside a `Column`, so
    // its incoming height constraint is infinite and cannot be the box's height; the
    // window is the honest ceiling, and no transcript picture is ever drawn taller than
    // this fraction of it.
    val capPx = with(density) {
        (LocalConfiguration.current.screenHeightDp * SINGLE_IMAGE_MAX_HEIGHT_FRACTION).dp.roundToPx()
    }
    // The picture's shape, read out of its own bytes before anything is decoded: this is
    // what lets the box below be final on the first frame. The four fixed-prologue formats
    // need 32 decoded bytes; only a JPEG pays for the walk behind its EXIF segment
    // ([naturalImageAspect]).
    val headerAspect = remember(image.base64) { naturalImageAspect(image.base64) }
    // The cell's own pixel size decides the sample, so a grid cell never allocates a
    // full-screen bitmap. `BoxWithConstraints` is the only place the real size is known
    // before layout; the decode then runs off the main thread.
    BoxWithConstraints(modifier) {
        val targetWidthPx = constraints.maxWidth
        // The single-image box: the full width is the caller's, the height is stated here
        // from data already in hand. A grid cell is the other way round — the caller fixed
        // the square (`weight(1f).aspectRatio(1f)`), so the cell fills what it was given.
        val boxAspect = if (naturalAspect) headerAspect ?: SINGLE_IMAGE_FALLBACK_ASPECT else 0f
        val boxHeightPx = if (naturalAspect) {
            singleImageBoxHeightPx(targetWidthPx, boxAspect, capPx)
        } else {
            constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: capPx
        }
        // Sampled to what will be drawn, not to the card it sits in (see [fitBoxPx]).
        val sample = fitBoxPx(targetWidthPx, boxHeightPx, headerAspect ?: boxAspect)
        // Decoded off the main thread, keyed on the payload *and* the box: a streaming
        // transcript recomposes often, and the decode must not repeat for the same
        // bytes at the same size. The keys come from the box stated above, never from the
        // decoded bitmap below, so an image-sized box cannot re-key its own decode; the
        // decode also waits on the shared gate, so a fling cannot start a dozen of them.
        //
        // The decode also goes through [PiImageCache], because `produceState` is state of a
        // *composition*: without the cache, a row the list disposes and later re-creates —
        // the ordinary result of scrolling past a picture and back — decoded the same bytes
        // again. A hit neither waits on the gate nor decodes; a failure is not cached, so it
        // is re-tried exactly as before, and the cell's `Pending`/`Ready` geometry is
        // unchanged (the cache only fills the value in sooner).
        val state by produceState<CellImage>(
            CellImage.Pending,
            image.base64,
            targetWidthPx,
            boxHeightPx,
        ) {
            value = CellImage.Ready(
                withContext(Dispatchers.IO) {
                    PiImageCache.readThrough(image.base64, sample.width, sample.height) {
                        piImageDecodeGate.withPermit {
                            decodePiImage(image.base64, sample.width, sample.height)
                        }
                    }
                },
            )
        }
        val decoded = (state as? CellImage.Ready)?.bitmap
        Surface(
            modifier = Modifier
                .then(
                    if (naturalAspect) {
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { boxHeightPx.toDp() })
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            onClickLabel = "查看大图",
                            onClick = { onClick(image) },
                        )
                    },
                ),
            shape = PiShapes.cardInner,
            color = palette.cardBg,
            border = BorderStroke(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.5f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (decoded != null) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = "第 ${index + 1} 张图片，点击查看大图",
                        // `Fit`, never `Crop`: a thumbnail that hides part of the picture
                        // is the defect this replaces. The box's own `cardBg` is the
                        // letterbox.
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state is CellImage.Ready) {
                    // Only for bytes the platform codec refuses, and only once it has
                    // actually refused them: while the decode is still running the box
                    // above is already its final size and stays empty, which is what keeps
                    // a picture from flashing this label on its way in. The label states
                    // the mime type rather than showing an unlabelled empty box.
                    Column(
                        modifier = Modifier.padding(PiSpacing.inline),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "图片 ${index + 1}",
                            style = PiTheme.text.monoSmall,
                            // The whole placeholder is pi's image **fallback** — the text a terminal
                            // that cannot show a picture prints in its place — and pi paints that
                            // string with `toolOutput`:
                            //   `new Image(…, { fallbackColor: (s) => theme.fg("toolOutput", s) }, …)`
                            // (`modes/interactive/components/tool-execution.js:307`). Both lines take
                            // it; leaving one of them `muted` would split one label across two colours
                            // that neither v2 nor pi draws.
                            color = palette.toolOutput,
                        )
                        Text(
                            text = image.mimeType.ifEmpty { "image" },
                            // A MIME type is a machine identifier, and the placeholder above is
                            // already in the machine face: v2 draws both halves of this label in
                            // one monospace span (`direction-b-v2.html:854`, `className="mono
                            // t12 c-muted"` — 「第 N 张图片<br/>image/png」). `meta` split one label
                            // across two voices.
                            style = PiTheme.text.monoSmall,
                            // Same fallback token as the line above (pi's `fallbackColor`), which is
                            // also what the derived `metaOnCard` it replaced was standing in for.
                            color = palette.toolOutput,
                        )
                    }
                }
                if (overflow > 0) {
                    Text(
                        text = "+$overflow",
                        // The overflow count is a reading, and it is machine language: v2 draws it
                        // `mono t14` (`direction-b-v2.html:855`). It used to take M3's
                        // `titleMedium` — 17 sp of the *UI* face, i.e. the largest step on the
                        // screen for a "+1", two families and four sizes away from the board.
                        // The board's step is stated inline because `PiTextStyles.mono` is the
                        // app's 13 sp machine body and this card's tile label is the one place
                        // v2 draws the count at 14.
                        style = PiTheme.text.mono.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = palette.text,
                    )
                }
            }
        }
    }
}

/**
 * One cell's picture, with "not decoded yet" told apart from "the platform codec refused
 * these bytes".
 *
 * The distinction is a sizing requirement, not bookkeeping. The labelled fallback belongs
 * to bytes that cannot be a picture, and showing it while a decode is still running is
 * what the cell used to do — on a decode that takes a tenth of a second that is a visible
 * flash of 「图片 1 / image/png」 exactly where the picture is about to appear. A
 * `produceState<Bitmap?>` whose initial value is `null` cannot express the difference
 * (both "in flight" and "failed" read as null), so the cell's state is three-valued and
 * the pending case draws an empty box of the final size.
 */
private sealed interface CellImage {
    /** The decode is still running — or still queued behind [piImageDecodeGate]. */
    data object Pending : CellImage

    /** The decode finished; [bitmap] is null when the bytes are not decodable. */
    data class Ready(val bitmap: Bitmap?) : CellImage
}

/**
 * Decoded from the wire's inline base64; null keeps the labelled placeholder.
 *
 * Shared with the composer's attachment preview (`ChatScreen`) **and** with the
 * full-screen viewer: both directions of the same wire value (`PiImage`) must use
 * one decoder, or a picture that renders in the transcript could fail in the
 * thumbnail for no visible reason — and a viewer with its own decoder is how the
 * two would end up disagreeing about what an image is.
 *
 * [targetWidth]/[targetHeight] are a **bounding box in pixels**; `0` (the default,
 * and what the composer's preview passes) means "the natural size". When a box is
 * given the decode is sampled with Android's `inSampleSize`, so the result stays
 * within 2× of the box on each axis. That matters because these bytes are a
 * full-resolution screenshot as often as not: a 1080×2400 ARGB result is ~10 MB
 * decoded, and the viewer (one screen) would otherwise pay for all of it. The cell
 * hands in [fitBoxPx] — the pixels that will actually be drawn inside its card, which is
 * smaller than the card whenever the picture is much taller or wider than it — so the
 * transcript never decodes a full-width bitmap to paint a narrow column.
 *
 * The box rule is Android's canonical one (halve while **both** axes still cover the
 * box), which never decodes below what will be drawn — but it is blind to aspect
 * ratio, and `inSampleSize` is uniform. A 1:5 screenshot in a square grid cell would
 * therefore decode at full resolution: 1080×5400 is ~23 MB, and four of them in one
 * grid is an out-of-memory on a mid-range phone. So one more rule bounds the **area**
 * at [DECODE_AREA_BUDGET_FACTOR] box-areas; it can only ever fire for a picture whose
 * aspect ratio is nothing like its box, and it does not touch the normal case at all.
 */
internal fun decodePiImage(base64: String, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? =
    runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (targetWidth <= 0 || targetHeight <= 0) {
            return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // Pass one reads the header only (`inJustDecodeBounds`), which is how the
        // sampling factor is chosen before any pixels are allocated.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var width = bounds.outWidth
        var height = bounds.outHeight
        var sample = 1
        while (width / 2 >= targetWidth && height / 2 >= targetHeight) {
            width /= 2
            height /= 2
            sample *= 2
        }
        // The aspect guard (see the KDoc): a decoded bitmap may not exceed
        // [DECODE_AREA_BUDGET_FACTOR] times the box's area, whatever its shape.
        val budget = targetWidth.toLong() * targetHeight.toLong() * DECODE_AREA_BUDGET_FACTOR
        while (width >= 2 && height >= 2 && width.toLong() * height > budget) {
            width /= 2
            height /= 2
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

/**
 * How much area a decoded bitmap may cover, in multiples of its requested box.
 *
 * Android's box rule already allows up to 4× (it halves only while *both* axes still
 * cover the box), so 4 is the value that binds the aspect-ratio case without ever
 * changing a normal one — see [decodePiImage].
 */
private const val DECODE_AREA_BUDGET_FACTOR = 4L

/** Spec cap: more than four images collapse into a grid with a `+N` badge. */
private const val MAX_GRID_IMAGES = 4
