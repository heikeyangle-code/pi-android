package app.pi.ui.blocks

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.pi.rpc.PiImage
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The full-screen viewer for one transcript image: pi's answer to "open the
 * picture", and the surface `docs/rendering-review.md`'s F19 recorded as missing.
 *
 * ## Why a `Dialog` and not a `Box` overlay or a `Popup`
 *
 * A `Dialog` is a **separate window**, and that is the whole argument:
 *
 *  - **The scroll position cannot move.** The requirement is "close it and come back
 *    to where I was". A `Box` overlay drawn over the `LazyColumn` keeps the list in
 *    the composition (safe), but it needs a host at the top of the screen — `PiRoot`,
 *    which owns the app's only `BackHandler` and every other overlay — and the
 *    transcript screen cannot raise one on its own. Swapping the list *out* for a
 *    viewer would dispose the list's state and is exactly the jump this must avoid.
 *    A dialog window never touches the list's composition at all, so "no jump" is
 *    structural rather than something the code has to remember to preserve.
 *  - **The back key, for free and correctly ordered.** `onDismissRequest` is what
 *    Compose calls for the back gesture while the dialog is up, so the viewer closes
 *    before `PiRoot`'s `BackHandler` would see the press — which is the correct
 *    nesting, and the alternative is a second `BackHandler` in a screen that
 *    deliberately has none (see `SessionTreeScreen`'s note on the single
 *    `BackHandler`).
 *  - **The image's own touches do not leak.** A `Popup` shares the activity's input
 *    and needs `focusable = true` plus manual outside-tap handling to behave the
 *    same; the dialog's window gives that and a scrim, and `PiDialog` already
 *    established the idiom (`ui/components/PiDialog.kt`) that this follows.
 *
 * ## Colour, and where it comes from
 *
 * The ground is `palette.pageBg` — v2's own canvas token, the same surface the
 * transcript itself sits on. Nothing new is invented: the viewer is "the page,
 * with one picture on it", not a new material. v2's `rgba(0,0,0,.32)` scrim
 * (`06-v2-construction-reference.md:89`) is deliberately *not* reused: it exists to
 * darken content behind a dialog that is opaque anyway, and here it would be either
 * invisible (over an opaque ground) or would leave transcript text legible through
 * the picture. The platform's own dim is cleared for the same reason `PiDialog`
 * clears it — so the app's backdrop is a value someone chose.
 *
 * ## Decoding
 *
 * The bitmap is decoded **once**, sampled to this view's own pixel size
 * ([decodePiImage] with a target box), on [Dispatchers.IO]. Three things follow:
 * the viewer never holds a second full-resolution copy of what the grid cell
 * already decoded, a 4000 px screenshot cannot OOM the app, and pinching to 1:1
 * magnifies the *sampled* pixels — the honest ceiling, because decoding the
 * original a second time purely to zoom is the memory the sampling exists to
 * save. (The decode itself honours a target box by halving, Android's
 * `inSampleSize`, so the returned bitmap is at most 2× the box on each axis.)
 *
 * ## Gestures
 *
 *  - **pinch** → scale, **drag while scaled** → pan (`detectTransformGestures`);
 *  - **double tap** → 1× ⇄ [DOUBLE_TAP_SCALE], clearing the pan;
 *  - **tap on the picture** → nothing (it consumes the tap, so it cannot fall
 *    through to the backdrop);
 *  - **tap on the ground around the picture** → dismiss; the picture is laid out at
 *    its fitted size rather than filling the box precisely so that this
 *    distinction is real;
 *  - **✕** (top-right) → dismiss; **back** → dismiss, via the dialog.
 *
 * @param image the wire value; its inline base64 is the only source this app has.
 * @param onDismiss closes the viewer. The caller clears its own "which image" state
 *   here, so no other code path may dismiss without calling it.
 */
@Composable
fun PiImageViewer(
    image: PiImage,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            // Kept on even though the content fills the window: it costs nothing and
            // documents the intent (`PiDialog` spells out the same pair).
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        ClearViewerPlatformDim()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.pageBg),
        ) {
            val density = LocalDensity.current
            val boxWidthPx = constraints.maxWidth
            val boxHeightPx = constraints.maxHeight

            val bitmap by produceState<Bitmap?>(null, image.base64, boxWidthPx, boxHeightPx) {
                value = withContext(Dispatchers.IO) {
                    decodePiImage(image.base64, boxWidthPx, boxHeightPx)
                }
            }

            val decoded = bitmap
            if (decoded == null) {
                // The same labelled fallback the grid cell shows, for the same case:
                // bytes the platform codec refuses. It is not an error screen — the
                // viewer simply has nothing to draw.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(PiSpacing.pageHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = "这张图片无法解码（${image.mimeType.ifEmpty { "image" }}）。",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
            } else {
                // Fit the picture into the window at 1×, preserving its aspect ratio,
                // and give the Image node exactly that size. Two consequences: the
                // letterboxed ground stays the backdrop's (so a tap there dismisses,
                // as asked), and the pan clamp below has real bounds to work with.
                val fit = min(
                    boxWidthPx.toFloat() / decoded.width,
                    boxHeightPx.toFloat() / decoded.height,
                ).takeIf { it.isFinite() && it > 0f } ?: 1f
                val fittedWidth = decoded.width * fit
                val fittedHeight = decoded.height * fit

                var scale by remember(image.base64) { mutableStateOf(1f) }
                var pan by remember(image.base64) { mutableStateOf(Offset.Zero) }

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = "放大的图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(
                                width = with(density) { fittedWidth.toDp() },
                                height = with(density) { fittedHeight.toDp() },
                            )
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = pan.x,
                                translationY = pan.y,
                            )
                            // Pinch and drag. Declared before the tap detector so a
                            // gesture that becomes a transform is claimed by this one
                            // and the tap detector only ever sees real taps.
                            .pointerInput(image.base64) {
                                detectTransformGestures { _, drag, zoom, _ ->
                                    val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                    scale = next
                                    pan = if (next <= 1f) {
                                        Offset.Zero
                                    } else {
                                        clampPan(
                                            pan = pan + drag,
                                            scale = next,
                                            fittedWidth = fittedWidth,
                                            fittedHeight = fittedHeight,
                                            boxWidth = boxWidthPx.toFloat(),
                                            boxHeight = boxHeightPx.toFloat(),
                                        )
                                    }
                                }
                            }
                            .pointerInput(image.base64) {
                                detectTapGestures(
                                    // A tap on the picture is consumed and ignored: it
                                    // must not reach the backdrop's dismiss detector.
                                    onTap = {},
                                    onDoubleTap = {
                                        scale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                                        pan = Offset.Zero
                                    },
                                )
                            },
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(PiSpacing.pageHorizontal),
            ) {
                // v2's icon language: the same `Close` glyph the session tree's top
                // bar uses, in the palette's own `text` token.
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = palette.text,
                )
            }

            // The ground itself: everything the picture does not cover. Taps here
            // dismiss, which is the half `dismissOnClickOutside` cannot do once the
            // content fills the window (`PiDialog` composes the same box for the same
            // reason).
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss),
            )
        }
    }
}

/**
 * Keeps the picture's edge from being dragged inside the window: at scale `s` the
 * picture overflows the box by `(s·fitted − box)` on each axis, so half of that is
 * the furthest the centre may move before a blank strip appears where the picture's
 * own edge should be.
 *
 * At 1× (or on an axis that still fits) the limit is zero, so the picture stays
 * centred — which is why the caller resets the pan to zero as soon as scale drops
 * back to 1.
 */
private fun clampPan(
    pan: Offset,
    scale: Float,
    fittedWidth: Float,
    fittedHeight: Float,
    boxWidth: Float,
    boxHeight: Float,
): Offset {
    val maxX = max(0f, (scale * fittedWidth - boxWidth) / 2f)
    val maxY = max(0f, (scale * fittedHeight - boxHeight) / 2f)
    return Offset(
        x = pan.x.coerceIn(-maxX, maxX),
        y = pan.y.coerceIn(-maxY, maxY),
    )
}

/**
 * Clears the platform's dialog dim so the one backdrop above is the only one.
 *
 * Same technique as `ui/components/PiDialog.kt`'s `ClearPlatformDim` (that helper is
 * private to its file and `ui/components` is not this batch's to edit): the dialog
 * window is reached through `DialogWindowProvider`, which is the parent view Compose
 * installs for it.
 */
@Composable
private fun ClearViewerPlatformDim() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }
}

/** One pinch-out step from 1× to the detail view, and the double-tap's target. */
private const val DOUBLE_TAP_SCALE = 3f

/**
 * The zoom range. 1× is "fitted to the window" by construction, and the ceiling is
 * [MAX_SCALE] rather than infinity because past it a sampled bitmap is only being
 * magnified as blur.
 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
