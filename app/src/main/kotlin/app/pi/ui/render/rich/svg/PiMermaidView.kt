package app.pi.ui.render.rich.svg

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import app.pi.ui.render.PiCardMenuItem
import app.pi.ui.render.PiRichCardFrame
import app.pi.ui.render.rich.FenceInfo
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ` ```mermaid ` as a real diagram (`§四` of `docs/plans/rich-rendering-upgrade.md`).
 *
 * This composable owns **display only**. It never touches a WebView: the source is
 * handed to the shared SVG pipeline ([PiMermaidSvg.renderMermaidSvg], Phase 2 of the
 * plan) which returns a sanitised SVG string, and AndroidSVG turns that string into
 * an `android.graphics.Picture` this file replays on a Compose `Canvas`. The old
 * ASCII path (`PiMermaid.kt` + the guest `/mermaid` route) is untouched and stays the
 * caller's secondary fallback until this path has proven itself.
 *
 * ## States (never blank, never stale)
 *
 * | condition                    | what is drawn                                  |
 * |------------------------------|------------------------------------------------|
 * | `settled = false`            | the source in a code block (fence not closed)   |
 * | `settled = true`, rendering  | the source, plus `渲染中…` in the toolbar        |
 * | render failed / too large    | the source, with the reason printed above it    |
 * | render succeeded             | the diagram on a `pageBg`-transparent canvas    |
 *
 * A new `source` resets the render state in the same composition (`remember(source)`),
 * so a diagram can never be shown for text that is no longer in the message. The
 * render is a `LaunchedEffect` keyed on `(source, settled, widthPx, theme)`; Compose
 * cancels the previous request when any of those change, and the engine's own queue
 * drops results whose task is gone.
 *
 * ## Why a `Picture` and not a `Bitmap`
 *
 * The plan allows either (`§四.4`). A `Picture` keeps the SVG as vector ops, so the
 * pinch-zoom transform is applied *inside* the canvas
 * (`nativeCanvas.translate/scale` → `drawPicture`) and Skia re-rasterises paths and
 * text at the device scale — a diagram stays sharp at 8× without re-rendering or
 * allocating a 8×-wide bitmap. hwui's `SkiaCanvas::drawPicture` replays the recording
 * into the display list, which is what makes that work on a hardware-accelerated
 * Compose canvas (Android 6+; this app's `minSdk` is 26). This is checked against
 * AOSP's `SkiaCanvas::drawPicture` (it calls `picture.playback(mCanvas)` on the
 * recording canvas), not yet exercised on a device — see the hand-off notes.
 *
 * The cost is paid on `Picture` *recording*, which is why the base viewport is
 * bounded ([PI_MERMAID_MAX_BASE_WIDTH_PX] × [PI_MERMAID_MAX_BASE_HEIGHT_PX]): the
 * recording is replayed every animation frame while a pinch is in progress, so a
 * 40,000-node graph scaled to phone width 8× would be the one case that janks. A
 * bitmap would trade that away for a fixed raster resolution (blurry past 1×, or a
 * large ARGB allocation at 4×–8×); this file deliberately keeps vector sharpness
 * because the case that matters is "read the labels in a flowchart", not
 * "60 fps on an enormous graph".
 *
 * ## Interaction
 *
 * - **pinch** zooms 1×…8× about the gesture centroid;
 * - **drag** pans, but only once zoomed — at 1× a vertical drag is left unconsumed so
 *   the transcript's `LazyColumn` still scrolls over a diagram (a diagram must not
 *   become a scroll trap the way a full-screen viewer legitimately is);
 * - **适应** resets to 1× / centred;
 * - **源码 / 图** switches the view without re-rendering;
 * - **复制** copies the fence's original source, never the rendered result (`§二`
 *   "复制操作默认复制原始源码").
 *
 * Colours come from [PiTheme.palette] and are converted to `#RRGGBB` for
 * [PiSvgTheme] because the shared pipeline must stay Compose-free (see
 * `PiSvgContract.kt`); the diagram therefore follows the app theme, and a theme
 * change re-renders rather than showing colours from the old palette.
 */
@Composable
fun PiMermaidView(
    source: String,
    settled: Boolean,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current

    // The pipeline's theme is a pure value: `PiSvgTheme` is a data class, so this
    // `remember` only changes when the palette does, and the equals-based
    // `LaunchedEffect` key below then re-renders instead of keeping stale colours.
    val theme = remember(palette) {
        PiSvgTheme(
            // 与卡片正文同色（页面底 + 6% 的卡片淡底）：节点底色与连线标签底色不再是比卡片更亮
            // 的一块块「白块」。
            background = palette.mdCodeBlockBorder.copy(alpha = 0.06f)
                .compositeOver(palette.pageBg).piHex(),
            foreground = palette.text.piHex(),
            accent = palette.accent.piHex(),
            muted = palette.muted.piHex(),
            fontFamily = PI_MERMAID_FONT_FAMILY,
        )
    }

    // `null` follows the render result; `true` / `false` is the user's explicit
    // 源码 / 图 choice. Keying on `source` means a new block starts in auto mode.
    // Int 编码（0 跟随 / 1 源码 / 2 图），`rememberSaveable`：滚出屏幕再回来，读者选过的
    // 「源码」不能弹回去。
    var pinMode by rememberSaveable(source) { mutableStateOf(0) }
    val pinned: Boolean? = when (pinMode) {
        1 -> true
        2 -> false
        else -> null
    }
    // 宽度取屏宽而不是 `BoxWithConstraints` 量出来的宽度：后者是 SubcomposeLayout，放进
    // `LazyColumn` 的行里会让行高在测量期反复变化（滚动跳动）；引擎按这个宽度排版，屏宽
    // 也让缓存键在整个会话里稳定。
    val configuration = LocalConfiguration.current
    val widthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
            .roundToInt()
            .coerceIn(PI_MERMAID_MIN_WIDTH_PX, PI_MERMAID_MAX_BASE_WIDTH_PX)
    }
    // 渲染成功过的图按（源码、宽度、主题）缓存：行滚回来直接是图，不再闪「渲染中」并让行高变两次。
    var draw by remember(source, widthPx, theme) {
        mutableStateOf<PiMermaidDraw>(
            PiMermaidDrawCache.get(PiMermaidDrawKey(source, widthPx, theme))
                ?: if (settled) PiMermaidDraw.Rendering else PiMermaidDraw.Streaming,
        )
    }
    var scale by remember(source) { mutableStateOf(PI_MERMAID_FIT_ZOOM) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }

    Box(modifier.fillMaxWidth()) {
        LaunchedEffect(source, settled, widthPx, theme) {
            if (!settled) {
                draw = PiMermaidDraw.Streaming
                return@LaunchedEffect
            }
            if (source.isBlank()) {
                draw = PiMermaidDraw.Unavailable("Mermaid 源码为空")
                return@LaunchedEffect
            }
            if (source.length > PI_MERMAID_MAX_SOURCE_CHARS) {
                draw = PiMermaidDraw.Unavailable(
                    "源码超过 ${PI_MERMAID_MAX_SOURCE_CHARS / 1024} KiB，未渲染",
                )
                return@LaunchedEffect
            }
            PiMermaidDrawCache.get(PiMermaidDrawKey(source, widthPx, theme))?.let {
                draw = it
                return@LaunchedEffect
            }
            draw = PiMermaidDraw.Rendering
            val result = try {
                PiMermaidSvg.renderMermaidSvg(source, widthPx, theme)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A throwing engine is a failure like any other: the source stays.
                PiSvgResult.Failure(error.message ?: error::class.java.simpleName)
            }
            draw = when (result) {
                is PiSvgResult.Ok -> withContext(Dispatchers.Default) {
                    // XML parse + `Picture` recording are off the main thread; the
                    // `LaunchedEffect` is cancelled if the source changes under us.
                    piMermaidDrawSvg(result, widthPx)
                }

                is PiSvgResult.Failure -> PiMermaidDraw.Unavailable(result.reason)
            }
            (draw as? PiMermaidDraw.Ready)?.let {
                PiMermaidDrawCache.put(PiMermaidDrawKey(source, widthPx, theme), it)
            }
        }

        val ready = draw as? PiMermaidDraw.Ready
        // Source is the fallback for "no picture yet" and the user's explicit
        // choice; it is never used to *hide* a successful render automatically.
        val showSource = ready == null || !settled || pinned == true
        val image = ready?.takeIf { !showSource }

        val status: String? = when (draw) {
            PiMermaidDraw.Streaming -> "生成中…"
            else -> null
        }

        PiRichCardFrame(
            code = source,
            info = PI_MERMAID_CARD_INFO,
            palette = palette,
            status = status,
            menu = buildList {
                if (image != null) {
                    add(
                        PiCardMenuItem("适应屏幕") {
                            scale = PI_MERMAID_FIT_ZOOM
                            offset = Offset.Zero
                        },
                    )
                }
                if (ready != null) {
                    add(
                        PiCardMenuItem(if (showSource) "查看图" else "查看 Mermaid 源码") {
                            pinMode = if (showSource) 2 else 1
                        },
                    )
                }
            },
        ) {
            if (image != null) {
                val aspect = image.aspect.takeIf { it.isFinite() && it > 0f }
                    ?: PI_MERMAID_FALLBACK_ASPECT
                // 画布高度在这里一次算死：宽 ÷ 宽高比，夹进 [最小, 最大]。以前是
                // `heightIn + fillMaxWidth + aspectRatio` 三个约束互相较劲，高度被夹住时
                // 画布比图矮，图的位置和卡片边线就对不上。
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    // 上限跟着屏幕走（屏高的 70%，不低于固定上限）：竖长的流程图以前被固定的
                    // 460dp 压成窄窄一条，字小得看不清；现在在大屏上能画得更高。
                    val heightCap = maxOf(
                        PI_MERMAID_MAX_IMAGE_HEIGHT,
                        (configuration.screenHeightDp * 0.7f).dp,
                    )
                    val canvasHeight = (maxWidth / aspect.coerceIn(0.05f, 60f))
                        .coerceIn(PI_MERMAID_MIN_IMAGE_HEIGHT, heightCap)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(canvasHeight)
                        // 画布自己是一层**离屏图层**：图画在这块缓冲里，超出画布边界的部分根本
                        // 不会被合成到卡片上。以前只有 `clipRect`——`Picture` 回放在部分机型的
                        // 硬件画布上不守当前裁剪，放大的图会盖住卡片标题、画到边线外面。
                        .graphicsLayer {
                            clip = true
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .pointerInput(image) {
                            piMermaidTransformGestures(
                                image = image,
                                readScale = { scale },
                                readOffset = { offset },
                            ) { nextScale, nextOffset ->
                                scale = nextScale
                                offset = nextOffset
                            }
                        }
                        .semantics { contentDescription = "Mermaid 渲染图：$source" },
                ) {
                    val fit = piMermaidFitScale(
                        viewWidth = size.width,
                        viewHeight = size.height,
                        baseWidthPx = image.baseWidthPx,
                        baseHeightPx = image.baseHeightPx,
                    ) * scale
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        // The transform lives on the native canvas, not on a
                        // `graphicsLayer`: a layer would scale the recorded raster
                        // and blur the labels, whereas this replays the vector ops
                        // at the final matrix.
                        native.save()
                        // 显式裁到画布：放大后图不许画进标题栏/卡片边缘（只靠 clipToBounds 不够稳）。
                        native.clipRect(0f, 0f, size.width, size.height)
                        native.translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                        native.scale(fit, fit)
                        native.translate(
                            -image.baseWidthPx / 2f,
                            -image.baseHeightPx / 2f,
                        )
                        native.drawPicture(image.picture)
                        native.restore()
                    }
                }
                }
            } else if (draw == PiMermaidDraw.Rendering && pinned != true) {
                // 已问管线、还没回答：固定高度的占位，而不是先摊一整块源码再缩成图 —— 后者让行高
                // 突变、列表跳动。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PI_MERMAID_PLACEHOLDER_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "渲染中…",
                        style = PiTheme.text.monoSmall,
                        color = palette.dim,
                    )
                }
            } else {
                val unavailable = draw as? PiMermaidDraw.Unavailable
                // 旧 ASCII 渲染器的位置在这里：`fallback` 只在**引擎真的画不出来**时接
                // 管，`pinned == true`（用户主动选了「源码」）不算失败，那时仍然显示源码，
                // 否则点「源码」会跳去 ASCII，与按钮的字面意思相反。
                if (unavailable != null && pinned != true && fallback != null) {
                    fallback()
                    // 队列满不是这张图的错，也不该吓读者：只回退不写字（队列上限已放到 64）。
                    if (!unavailable.reason.contains("队列已满")) Text(
                        text = unavailable.reason,
                        style = PiTheme.text.monoSmall,
                        color = palette.warning,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
                    )
                } else {
                    PiMermaidSourceBlock(
                        source = source,
                        notice = unavailable?.reason,
                    )
                }
            }
        }
    }
}

/** Mermaid 卡片头的「语言」胶囊。 */
private val PI_MERMAID_CARD_INFO = FenceInfo(language = "mermaid", title = null, attributes = emptyMap(), raw = "mermaid")

/** 渲染占位的固定高度：一张典型小流程图的量级，避免「源码 → 图」的行高突变。 */
private val PI_MERMAID_PLACEHOLDER_HEIGHT = 120.dp

/** 缓存键：源码、基准宽度、主题，缺一不可。 */
private data class PiMermaidDrawKey(val source: String, val widthPx: Int, val theme: PiSvgTheme)

/** 最近渲染成功的图（`Picture` 是录制好的矢量）：16 条 LRU，只缓存成功结果。 */
private object PiMermaidDrawCache {
    private const val CAPACITY = 16
    private val entries = object : LinkedHashMap<PiMermaidDrawKey, PiMermaidDraw.Ready>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PiMermaidDrawKey, PiMermaidDraw.Ready>): Boolean =
            size > CAPACITY
    }

    @Synchronized
    fun get(key: PiMermaidDrawKey): PiMermaidDraw.Ready? = entries[key]

    @Synchronized
    fun put(key: PiMermaidDrawKey, value: PiMermaidDraw.Ready) {
        entries[key] = value
    }
}

/** What the render request has produced so far. Never holds a half-parsed document. */
private sealed interface PiMermaidDraw {
    /** The fence is not closed yet: source only. */
    data object Streaming : PiMermaidDraw

    /** The pipeline was asked and has not answered: source plus `渲染中…`. */
    data object Rendering : PiMermaidDraw

    /** A recorded, drawable diagram. */
    data class Ready(
        val picture: Picture,
        val baseWidthPx: Int,
        val baseHeightPx: Int,
        val aspect: Float,
    ) : PiMermaidDraw

    /** No diagram will be shown for this source; the reason goes on the source block. */
    data class Unavailable(val reason: String) : PiMermaidDraw
}

// ---------------------------------------------------------------------------------
// SVG → Picture
// ---------------------------------------------------------------------------------

/**
 * Parse the pipeline's already-sanitised SVG and record it at a bounded viewport.
 *
 * Runs on [Dispatchers.Default] from the caller. Every failure is a
 * [PiMermaidDraw.Unavailable], never an exception: a broken diagram must show its
 * source, not take the message down (`§十五` "超出限制不崩溃、不冻结，回退源码").
 *
 * `renderToPicture(w, h)` is kept rather than `renderToCanvas` because the picture is
 * replayable every frame without re-running androidSVG's renderer; the caller draws
 * it with the gesture matrix applied.
 */
private fun piMermaidDrawSvg(result: PiSvgResult.Ok, requestedWidthPx: Int): PiMermaidDraw {
    if (result.svg.length > PI_MERMAID_MAX_SVG_CHARS) {
        return PiMermaidDraw.Unavailable("SVG 超过 ${PI_MERMAID_MAX_SVG_CHARS / (1024 * 1024)} MiB，未渲染")
    }
    val svg = try {
        SVG.getFromString(PiSvgFit.compat(result.svg))
    } catch (error: Exception) {
        return PiMermaidDraw.Unavailable("SVG 解析失败：${error.message ?: error::class.java.simpleName}")
    }
    // 留白随图大小走：量字宽的字体（WebView）和最终画字的字体（AndroidSVG）不同，大图的
    // 偏差绝对值更大，固定 16 单位在大图上不够，右/下边缘的节点会被视口直接裁掉。
    val rawBox = try { svg.documentViewBox } catch (_: Exception) { null }
    val margin = if (rawBox != null && rawBox.width() > 0f && rawBox.height() > 0f) {
        maxOf(16f, maxOf(rawBox.width(), rawBox.height()) * 0.06f)
    } else {
        24f
    }
    PiSvgFit.fill(svg, result.widthPx, result.heightPx, margin = margin)

    val aspect = piMermaidAspect(svg, result)
    // Base viewport: the block's own width, bounded. Recording larger than the
    // screen only makes each pinch frame more expensive, and recording smaller
    // loses nothing because the replay is still vector-scaled.
    var baseWidth = requestedWidthPx.coerceIn(PI_MERMAID_MIN_WIDTH_PX, PI_MERMAID_MAX_BASE_WIDTH_PX)
    var baseHeight = (baseWidth / aspect).roundToInt()
    if (baseHeight > PI_MERMAID_MAX_BASE_HEIGHT_PX) {
        baseHeight = PI_MERMAID_MAX_BASE_HEIGHT_PX
        baseWidth = (baseHeight * aspect).roundToInt()
    }
    baseWidth = baseWidth.coerceIn(1, PI_MERMAID_MAX_BASE_WIDTH_PX)
    baseHeight = baseHeight.coerceIn(1, PI_MERMAID_MAX_BASE_HEIGHT_PX)

    val picture = try {
        svg.renderToPicture(baseWidth, baseHeight)
    } catch (error: Exception) {
        return PiMermaidDraw.Unavailable("SVG 绘制失败：${error.message ?: error::class.java.simpleName}")
    }
    return PiMermaidDraw.Ready(picture, baseWidth, baseHeight, aspect)
}

/**
 * The document's width/height ratio, preferring what the SVG itself declares.
 *
 * Mermaid and MathJax both emit a `viewBox`, and that is the ratio the renderer is
 * laid out in; the fallback chain exists only because a hand-written svg fence may
 * omit one. A wrong value here would letterbox the diagram (the viewport
 * would disagree with `preserveAspectRatio`), not distort it.
 */
private fun piMermaidAspect(svg: SVG, result: PiSvgResult.Ok): Float {
    val viewBox = try {
        svg.getDocumentViewBox()
    } catch (error: Exception) {
        null
    }
    if (viewBox != null && viewBox.width() > 0f && viewBox.height() > 0f) {
        val ratio = viewBox.width() / viewBox.height()
        if (ratio.isFinite() && ratio > 0f) return ratio.coerceIn(0.02f, 50f)
    }
    val declared = try {
        svg.getDocumentAspectRatio()
    } catch (error: Exception) {
        -1f
    }
    if (declared.isFinite() && declared > 0f) return declared.coerceIn(0.02f, 50f)
    if (result.widthPx > 0 && result.heightPx > 0) {
        return (result.widthPx.toFloat() / result.heightPx).coerceIn(0.02f, 50f)
    }
    return PI_MERMAID_FALLBACK_ASPECT
}

// ---------------------------------------------------------------------------------
// Gestures
// ---------------------------------------------------------------------------------

/**
 * Pinch-to-zoom plus drag-to-pan, hand-written instead of `detectTransformGestures`.
 *
 * `detectTransformGestures` consumes the pointer stream as soon as one finger moves,
 * which would make every diagram swallow vertical drags and stop the transcript from
 * scrolling underneath it. This detector only consumes when it actually changes
 * something: two or more fingers (zoom), or one finger while already zoomed (pan).
 * At 1× a one-finger drag is left alone, so the `LazyColumn` keeps working.
 *
 * Zoom is applied about the gesture centroid so the point under the fingers stays
 * put, and both scale and offset are clamped by [piMermaidClampOffset] so the diagram
 * can never be flung completely out of its box.
 */
private suspend fun PointerInputScope.piMermaidTransformGestures(
    image: PiMermaidDraw.Ready,
    readScale: () -> Float,
    readOffset: () -> Offset,
    onTransform: (scale: Float, offset: Offset) -> Unit,
) {
    awaitEachGesture {
        // A gesture starts from whatever 适应 or a previous gesture left behind;
        // local copies keep the loop from reading Compose state per pointer event
        // while still never losing an update across gestures.
        awaitFirstDown(requireUnconsumed = false)
        var scale = readScale().coerceIn(PI_MERMAID_MIN_ZOOM, PI_MERMAID_MAX_ZOOM)
        var offset = readOffset()
        // The view size comes from the pointer's own coordinate space, so a resize
        // mid-gesture cannot leave the detector clamped against a stale box.
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            val zoomChange = if (pressed >= 2) event.calculateZoom() else 1f
            val panChange = event.calculatePan()

            var nextScale = scale
            if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                nextScale = (scale * zoomChange).coerceIn(PI_MERMAID_MIN_ZOOM, PI_MERMAID_MAX_ZOOM)
            }
            val panAllowed = panChange != Offset.Zero && (nextScale > PI_MERMAID_FIT_ZOOM)
            if (nextScale != scale || panAllowed) {
                val viewWidth = size.width.toFloat()
                val viewHeight = size.height.toFloat()
                var nextOffset = offset
                if (nextScale != scale && viewWidth > 0f && viewHeight > 0f) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.x.isFinite() && centroid.y.isFinite()) {
                        val ratio = nextScale / scale
                        val cx = centroid.x - viewWidth / 2f
                        val cy = centroid.y - viewHeight / 2f
                        nextOffset = Offset(
                            cx + ratio * (offset.x - cx),
                            cy + ratio * (offset.y - cy),
                        )
                    }
                }
                if (panAllowed) nextOffset += panChange
                offset = piMermaidClampOffset(
                    wanted = nextOffset,
                    scaleScale = nextScale,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    baseWidthPx = image.baseWidthPx,
                    baseHeightPx = image.baseHeightPx,
                )
                scale = nextScale
                onTransform(scale, offset)
                event.changes.forEach { it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** The scale at which a `baseWidthPx × baseHeightPx` picture fits the view at 1×. */
private fun piMermaidFitScale(
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Float {
    if (viewWidth <= 0f || viewHeight <= 0f || baseWidthPx <= 0 || baseHeightPx <= 0) return 1f
    return min(viewWidth / baseWidthPx, viewHeight / baseHeightPx)
}

/**
 * Keep the drawn diagram inside its box.
 *
 * When the scaled diagram is smaller than the view on an axis, the only legal offset
 * is zero (centred); otherwise the diagram may move until its edge reaches the view's
 * edge, so panning cannot leave an empty box behind.
 */
private fun piMermaidClampOffset(
    wanted: Offset,
    scaleScale: Float,
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Offset {
    val fit = piMermaidFitScale(viewWidth, viewHeight, baseWidthPx, baseHeightPx) * scaleScale
    val drawnWidth = baseWidthPx * fit
    val drawnHeight = baseHeightPx * fit
    val maxX = ((drawnWidth - viewWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((drawnHeight - viewHeight) / 2f).coerceAtLeast(0f)
    return Offset(
        wanted.x.coerceIn(-maxX, maxX),
        wanted.y.coerceIn(-maxY, maxY),
    )
}

// ---------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------

@Composable
private fun PiMermaidModeLabel(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Text(
        text = label,
        style = PiTheme.text.monoSmall,
        color = when {
            !enabled -> palette.dim
            selected -> palette.accent
            else -> palette.muted
        },
        maxLines = 1,
        modifier = Modifier
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClickLabel = onClickLabel, onClick = onClick)
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp, horizontal = 2.dp)
                },
            ),
    )
}
/**
 * The raw-source fallback: the fence body verbatim, in the same visual language as
 * the app's code blocks (`mdCodeBlockBorder` at 6 % fill, 28 % border, 12 dp radius),
 * so a failed diagram reads as "this is the code" rather than as an error card.
 *
 * Display is capped at [PI_MERMAID_MAX_VISIBLE_SOURCE_LINES] lines with a stated
 * remainder; the copy action still yields the whole source. The cap exists because a
 * 64 KiB fence that failed to render would otherwise become a single enormous `Text`
 * inside the transcript's `LazyColumn`.
 */
@Composable
private fun PiMermaidSourceBlock(source: String, notice: String?) {
    val palette = PiTheme.palette
    val lines = remember(source) { source.lineSequence().toList() }
    val hidden = (lines.size - PI_MERMAID_MAX_VISIBLE_SOURCE_LINES).coerceAtLeast(0)
    val shown = remember(source, hidden) {
        if (hidden == 0) {
            source
        } else {
            lines.take(PI_MERMAID_MAX_VISIBLE_SOURCE_LINES).joinToString("\n")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (!notice.isNullOrEmpty()) {
            Text(
                text = notice,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = shown,
            style = PiTheme.typography.styles.code.copy(color = palette.text),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
        if (hidden > 0) {
            Text(
                text = "… 还有 $hidden 行，复制源码查看完整内容",
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** `#RRGGBB` for [PiSvgTheme]; the contract forbids Compose types in that file. */
private fun Color.piHex(): String =
    "#%06X".format(Locale.US, toArgb() and 0xFFFFFF)

// ---------------------------------------------------------------------------------
// Bounds and constants
// ---------------------------------------------------------------------------------

/** `§十五`: a Mermaid fence over this is source only. */
private const val PI_MERMAID_MAX_SOURCE_CHARS = 64 * 1024

/** `§十五`: one SVG over this is source only (the sanitizer should already refuse). */
private const val PI_MERMAID_MAX_SVG_CHARS = 2 * 1024 * 1024

/**
 * The base viewport the pipeline is asked for and the picture is recorded at.
 *
 * 1600 px covers a large phone at 2–3× density without asking the engine to lay a
 * graph out for a tablet it will be scaled down on anyway; 4096 px bounds a tall
 * gantt/mindmap so one replay frame cannot become pathological.
 */
private const val PI_MERMAID_MAX_BASE_WIDTH_PX = 1600
private const val PI_MERMAID_MAX_BASE_HEIGHT_PX = 4096

/** Below this the engine cannot lay out labels; used only as a request clamp. */
private const val PI_MERMAID_MIN_WIDTH_PX = 240

/** Unbounded parent (horizontal scroller): lay out at a phone width rather than fail. */
private val PI_MERMAID_FALLBACK_WIDTH = 360.dp

// 允许缩到 0.5×：图在默认视图里被裁/偏大时，用户能自己缩小看全，而不是卡死在 1×。
private const val PI_MERMAID_MIN_ZOOM = 0.5f
// 「适应屏幕」的缩放值；平移只在大于它时才吃手势，保证 1× 时列表照常滚动。
private const val PI_MERMAID_FIT_ZOOM = 1f
private const val PI_MERMAID_MAX_ZOOM = 8f

private val PI_MERMAID_MIN_IMAGE_HEIGHT = 120.dp

/** A diagram taller than this is panned/zoomed rather than forcing the page to scroll. */
private val PI_MERMAID_MAX_IMAGE_HEIGHT = 460.dp

/** Ratio used when an SVG declares no `viewBox`, width or height at all. */
private const val PI_MERMAID_FALLBACK_ASPECT = 1.6f

/** Mermaid's own default face; passed through [PiSvgTheme] so the engine can use it. */
private const val PI_MERMAID_FONT_FAMILY = "sans-serif"

/** Shown in the toolbar long enough to read, not long enough to linger. */
private const val PI_MERMAID_FEEDBACK_MS = 1500L

/** A failed render's source is displayed up to this many lines; copy is unaffected. */
private const val PI_MERMAID_MAX_VISIBLE_SOURCE_LINES = 200
