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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
 *  - **one image** — the natural aspect ratio of the decoded picture, capped at
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
 * The bytes are already here: `PiImage` carries base64 inline
 * (`rpc/Commands.kt:13`), so the cell decodes them with the platform codec and
 * only falls back to a labelled placeholder when the payload is not decodable.
 * Decoding runs on [Dispatchers.IO] — a base64 image is not composition work — and is
 * sampled to the cell's own box, not the image's. Each cell decodes **once** per
 * (payload, box) pair: `produceState` is keyed on both, and the single-image cell's
 * box comes from its incoming constraints rather than from the decoded bitmap, so the
 * "box depends on the ratio depends on the box" cycle cannot form. The viewer decodes
 * its own copy at screen size — a *second* decode, deliberately: it is a different
 * resolution for a different surface, not a repeat of the same one.
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
        // No `height`/`aspectRatio` here: the cell takes its height from the picture's
        // own ratio (see [ImageCell]'s `naturalAspect`).
        ImageCell(
            image = images[0],
            index = 0,
            modifier = Modifier.fillMaxWidth(),
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
     * Size the box from the decoded picture's own ratio (the single-image case) and
     * cap that height at [SINGLE_IMAGE_MAX_HEIGHT_FRACTION] of the window, instead of
     * filling the box the caller imposed. The picture is drawn with `Fit` either way,
     * so the cap letterboxes rather than crops.
     */
    naturalAspect: Boolean = false,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    // A single image's cell is `fillMaxWidth()` inside a `Column`, so its incoming
    // height constraint is infinite and cannot be the sampling box's height. The
    // window is the honest ceiling for it: nothing larger than a screen is ever shown.
    val windowHeightPx = with(density) {
        (LocalConfiguration.current.screenHeightDp * SINGLE_IMAGE_MAX_HEIGHT_FRACTION).dp.roundToPx()
    }
    // The cell's own pixel size decides the sample, so a 180 dp row never allocates a
    // full-screen bitmap. `BoxWithConstraints` is the only place the real size is known
    // before layout; the decode then runs off the main thread.
    BoxWithConstraints(modifier) {
        val targetWidthPx = constraints.maxWidth
        val targetHeightPx = constraints.maxHeight
            .takeIf { it != Constraints.Infinity }
            ?: windowHeightPx
        // Decoded off the main thread, keyed on the payload *and* the box: a streaming
        // transcript recomposes often, and the decode must not repeat for the same
        // bytes at the same size. The keys come from the *incoming* constraints, never
        // from the decoded bitmap below, so an image-sized box cannot re-key its own
        // decode.
        val bitmap by produceState<Bitmap?>(null, image.base64, targetWidthPx, targetHeightPx) {
            value = withContext(Dispatchers.IO) {
                decodePiImage(image.base64, targetWidthPx, targetHeightPx)
            }
        }
        val decoded = bitmap
        val drawnAspect = if (decoded != null && decoded.height > 0) {
            decoded.width.toFloat() / decoded.height
        } else {
            null
        }
        Surface(
            modifier = Modifier
                .then(
                    if (naturalAspect && drawnAspect != null && drawnAspect > 0f) {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(drawnAspect)
                            .heightIn(max = with(density) { windowHeightPx.toDp() })
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
                } else {
                    // Only for bytes the platform codec refuses: the label states the
                    // mime type rather than showing an unlabelled empty box.
                    Column(
                        modifier = Modifier.padding(PiSpacing.inline),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "图片 ${index + 1}",
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                        )
                        Text(
                            text = image.mimeType.ifEmpty { "image" },
                            style = PiTheme.text.meta,
                            // F13/F14: `dim` on a card is 2.89:1 in pi's dark theme;
                            // `metaOnCard` is the palette's corrected meta token.
                            color = palette.metaOnCard,
                        )
                    }
                }
                if (overflow > 0) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.text,
                    )
                }
            }
        }
    }
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
 * decoded, and both the grid cell (180 dp) and the viewer (one screen) would
 * otherwise pay for all of it — the same picture twice.
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

/**
 * How tall a **single** transcript image may be, as a fraction of the window.
 *
 * The cap exists for the extreme case the user named: a long screenshot (1:5 or
 * worse) at its natural ratio would take several screens and bury the conversation
 * around it. Past the cap the cell stops growing and `ContentScale.Fit` shows the whole
 * picture smaller, which is "看个大概" — and the tap target is unchanged, so the full
 * view is one tap away.
 */
private const val SINGLE_IMAGE_MAX_HEIGHT_FRACTION = 0.6f
