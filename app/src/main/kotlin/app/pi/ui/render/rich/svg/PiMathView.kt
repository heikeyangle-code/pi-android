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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
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
 * 行内 / 块级公式的显示层（方案 §四、§十三），与 [PiMermaidView] 同构，但**行内和块级走两条
 * 完全不同的界面**。
 *
 * 这个 composable 只负责显示。它不碰 WebView：源码交给共享管线
 * （[PiMathSvg.renderMathSvg]）拿回一段已 sanitize 的 SVG，AndroidSVG 把它解析成
 * `android.graphics.Picture`，再由这里的 Compose `Canvas` 回放。既有的 `PiLatex`（ASCII/网格
 * 回退）一个字节都不改，仍是父级在"没接这个 view"时的兜底。
 *
 * ## 两种形态（为什么行内不能有工具栏）
 *
 * | 条件 | 块级（`display = true`） | 行内（`display = false`） |
 * |---|---|---|
 * | `settled = false`（围栏还没闭合） | 源码块 | 原始 TeX 文字 |
 * | 排版中 | 源码块 + 工具栏"渲染中…" | 原始 TeX 文字 |
 * | 失败/超限 | 源码块 + 一行原因 | 原始 TeX 文字 |
 * | 成功 | `Picture` + 源码/公式切换 + 捏合缩放 1×–8× | 只画公式，按行高缩放 |
 *
 * 行内公式出现在**一句话中间**。如果那里冒出一张带边框的源码卡片、或者一个"源码 | 公式"
 * 切换器，段落会被从中间劈开——这不是"多了一点 chrome"，是排版坏了。所以行内分支没有
 * 工具栏、没有缩放、没有卡片：没准备好就退回原始 TeX 文字（单行、省略号），准备好就画一条
 * 行高那么高的公式。代价是行内公式失败时看不到原因（那行原因会把段落撑乱），这是有意的
 * 取舍：原因在日志/块级回退里能看到。
 *
 * ## `fallbackText`：升级前的文本，只用在行内兜底
 *
 * 调用方（`PiMarkdownComponents`）把 `PiLatex.formulaText` 的 Unicode 结果传进来：行内公式
 * 还没画出来或失败时，屏幕上先显示这段文本——也就是"没接这个 view 之前"逐字相同的样子，
 * 而不是一串带反斜杠的 TeX。块级公式失败时走的是源码块（[PiMathSourceBlock]）：那里有整行
 * 宽度，既能显示原文也能显示一行原因，比一行被截断的 Unicode 近似更有用，所以块级分支
 * 不用 `fallbackText`。这个差异是有意的，不是漏接参数。
 *
 * ## 状态与失效
 *
 * 新 `source` 会在同一次组合里重置渲染状态（`remember(source, display)`），所以绝不会出
 * 现"这段文字已经换了、屏幕上还是上一张图"。渲染是 `LaunchedEffect(source, display,
 * settled, widthPx, theme)`：其中任意一个变化都会取消上一个请求，引擎侧也会丢弃已经没人
 * 等的任务。`theme` 是纯值 [PiSvgTheme]，主题切换会真的重新渲染，而不是继续用旧颜色。
 *
 * ## 为什么是 `Picture` 而不是 `Bitmap`
 *
 * 与 [PiMermaidView] 相同的理由：`Picture` 保留矢量指令，捏合时把变换交给 native canvas，
 * Skia 按最终矩阵重新栅格化，8× 仍然清晰；代价是每帧回放记录，所以基础视口是有界的
 * （[PI_MATH_MAX_BASE_WIDTH_PX] × [PI_MATH_MAX_BASE_HEIGHT_PX]）。公式比整张流程图小得多，
 * 这里的上限只是为了让"一条病态超长公式"不至于把每帧成本放大。
 *
 * ## 交互
 *
 * - **捏合**缩放 1×…8×，围绕手势质心；
 * - **拖动**只在已放大时平移；1× 时不消费垂直拖动，`LazyColumn` 仍能滚过公式（公式不能
 *   变成滚动陷阱，理由同 Mermaid 视图）；
 * - **适应**重置到 1×；
 * - **源码 / 公式**只切换显示，不重新渲染；
 * - **复制**复制围栏里的原始 TeX（方案 §二"复制操作默认复制原始源码"），从不复制渲染结果。
 *
 * 颜色来自 `PiTheme.palette`，转成 `#RRGGBB` 传给 [PiSvgTheme]（共享管线必须保持
 * Compose-free，见 `PiSvgContract.kt`）。`foreground` 会替换 MathJax 输出里的
 * `currentColor`，所以深色主题下公式是亮的，不是黑色的。
 */
@Composable
fun PiMathView(
    source: String,
    display: Boolean,
    settled: Boolean,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    // 纯值主题：`PiSvgTheme` 是 data class，所以这个 `remember` 只在调色板变化时重建，
    // 下面 equals 语义的 LaunchedEffect 键才会真正触发重渲染。
    val theme = remember(palette) {
        PiSvgTheme(
            background = palette.pageBg.piMathHex(),
            foreground = palette.text.piMathHex(),
            accent = palette.accent.piMathHex(),
            muted = palette.muted.piMathHex(),
            fontFamily = PI_MATH_FONT_FAMILY,
        )
    }

    // `null` 跟随渲染结果；`true` / `false` 是用户显式选的 源码 / 公式。按 `source` 记忆，
    // 换一段源码就回到自动模式。
    var pinned by remember(source) { mutableStateOf<Boolean?>(null) }
    var draw by remember(source, display) {
        mutableStateOf<PiMathDraw>(
            if (settled) PiMathDraw.Rendering else PiMathDraw.Streaming,
        )
    }
    var scale by remember(source) { mutableStateOf(PI_MATH_MIN_ZOOM) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedback) {
        val shown = feedback
        if (shown != null) {
            delay(PI_MATH_FEEDBACK_MS)
            // 只清自己发的那条；更新的一条不能被提前抹掉。
            if (feedback == shown) feedback = null
        }
    }

    // 块级占满一行（和 Mermaid 视图一致）；行内不加 `fillMaxWidth`，宽度跟随父级约束，
    // 这样放进段落/行内槽位时不会把整段撑断。
    BoxWithConstraints(modifier = if (display) modifier.fillMaxWidth() else modifier) {
        // 引擎按 CSS 宽度排版；这里先把宽度量出来再请求。行内公式的宽度只进缓存键和宿主
        // 视口边界，MathJax 不按宽度换行。
        val widthPx = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
            constraints.maxWidth.coerceIn(PI_MATH_MIN_WIDTH_PX, PI_MATH_MAX_BASE_WIDTH_PX)
        } else {
            with(density) { PI_MATH_FALLBACK_WIDTH.toPx() }.roundToInt()
        }

        LaunchedEffect(source, display, settled, widthPx, theme) {
            if (!settled) {
                draw = PiMathDraw.Streaming
                return@LaunchedEffect
            }
            if (source.isBlank()) {
                draw = PiMathDraw.Unavailable("公式源码为空")
                return@LaunchedEffect
            }
            if (source.length > PI_MATH_MAX_SOURCE_CHARS) {
                draw = PiMathDraw.Unavailable(
                    "源码超过 ${PI_MATH_MAX_SOURCE_CHARS / 1024} KiB，未渲染",
                )
                return@LaunchedEffect
            }
            draw = PiMathDraw.Rendering
            val result = try {
                PiMathSvg.renderMathSvg(source, display, widthPx, theme)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 引擎抛异常也按失败处理：源码必须留着。
                PiSvgResult.Failure(error.message ?: error::class.java.simpleName)
            }
            draw = when (result) {
                is PiSvgResult.Ok -> withContext(Dispatchers.Default) {
                    // XML 解析 + `Picture` 录制离主线程；换源码时 LaunchedEffect 被取消。
                    piMathDrawSvg(result, widthPx)
                }

                is PiSvgResult.Failure -> PiMathDraw.Unavailable(result.reason)
            }
        }

        // `Column` 的 scope 带 `@LayoutScopeMarker`，从它里面读不到外层 `BoxWithConstraintsScope`
        // 的 `maxWidth`（DslMarker 挡住隐式外层接收者）。进 `Column` 之前先取出来。
        val blockWidth = maxWidth
        val ready = draw as? PiMathDraw.Ready

        if (!display) {
            // ---------------------------------------------------------- 行内公式
            if (ready == null) {
                Text(
                    // 升级前的那条路径：`PiLatex` 已经能把这行公式排成 Unicode 文本，
                    // 调用方把它传进来，于是引擎不可用时行内公式**逐字**还是旧样子，
                    // 而不是退成一段 `x^2` 的 TeX。没有它才退回源码。
                    text = fallbackText?.takeIf { it.isNotBlank() }
                        ?: source.ifBlank { PI_MATH_INLINE_FALLBACK },
                    style = PiTheme.typography.styles.code,
                    color = palette.mdCode,
                    // 行内兜底只占一行：一段话中间不能因为一条坏公式长出第二行。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val aspect = piMathAspectOrFallback(ready)
                val naturalWidth = PI_MATH_INLINE_HEIGHT * aspect
                val availableWidth = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
                    with(density) { constraints.maxWidth.toDp() }
                } else {
                    // 无界父级（例如行内槽位）：按公式自身的自然宽度排，别去猜上限。
                    naturalWidth
                }
                val formulaWidth = minOf(naturalWidth, availableWidth).coerceAtLeast(1.dp)
                Canvas(
                    modifier = Modifier
                        .width(formulaWidth)
                        // 行高固定：公式在行高里居中，宽公式按宽度缩、高公式按行高缩。
                        .height(PI_MATH_INLINE_HEIGHT)
                        .semantics { contentDescription = "行内公式" },
                ) {
                    val fit = piMathFitScale(
                        viewWidth = size.width,
                        viewHeight = size.height,
                        baseWidthPx = ready.baseWidthPx,
                        baseHeightPx = ready.baseHeightPx,
                    )
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(size.width / 2f, size.height / 2f)
                        native.scale(fit, fit)
                        native.translate(-ready.baseWidthPx / 2f, -ready.baseHeightPx / 2f)
                        native.drawPicture(ready.picture)
                        native.restore()
                    }
                }
            }
        } else {
            // ---------------------------------------------------------- 块级公式
            // 源码是"还没画出来"和"用户显式要源码"时的回退；它永远不会自动盖住成功结果。
            val showSource = ready == null || !settled || pinned == true
            val image = ready?.takeIf { !showSource }

            val status: String? = feedback ?: when (draw) {
                PiMathDraw.Streaming -> "生成中…"
                PiMathDraw.Rendering -> "渲染中…"
                // 原因印在源码块上（那里有整行的位置），工具栏这一格放不下。
                is PiMathDraw.Unavailable -> null
                is PiMathDraw.Ready -> if (showSource) "源码" else null
            }

            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PiMathModeLabel(
                        label = "源码",
                        selected = showSource,
                        enabled = true,
                        onClickLabel = "查看 TeX 源码",
                        onClick = { pinned = true },
                    )
                    Spacer(Modifier.width(12.dp))
                    PiMathModeLabel(
                        label = "公式",
                        selected = !showSource,
                        enabled = ready != null,
                        onClickLabel = "查看渲染后的公式",
                        onClick = { pinned = false },
                    )
                    if (status != null) {
                        Text(
                            text = status,
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (image != null) {
                        PiMathActionLabel(
                            label = "适应",
                            onClickLabel = "适应宽度，重置缩放",
                            onClick = {
                                scale = PI_MATH_MIN_ZOOM
                                offset = Offset.Zero
                            },
                        )
                    }
                    PiMathActionLabel(
                        // 工具栏状态位负责"已复制源码"；动作本身的标签保持稳定，不随复制抖动。
                        label = "复制",
                        onClickLabel = "复制 TeX 源码",
                        onClick = {
                            clipboard.setText(AnnotatedString(source))
                            feedback = PI_MATH_COPIED
                        },
                    )
                }

                if (image != null) {
                    val aspect = piMathAspectOrFallback(image)
                    val imageHeight = (blockWidth / aspect)
                        .coerceIn(PI_MATH_MIN_IMAGE_HEIGHT, PI_MATH_MAX_IMAGE_HEIGHT)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(imageHeight)
                            .clipToBounds()
                            .pointerInput(image) {
                                piMathTransformGestures(
                                    image = image,
                                    readScale = { scale },
                                    readOffset = { offset },
                                ) { nextScale, nextOffset ->
                                    scale = nextScale
                                    offset = nextOffset
                                }
                            }
                            .semantics { contentDescription = "数学公式" },
                    ) {
                        val fit = piMathFitScale(
                            viewWidth = size.width,
                            viewHeight = size.height,
                            baseWidthPx = image.baseWidthPx,
                            baseHeightPx = image.baseHeightPx,
                        ) * scale
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            // 变换放在 native canvas 上，不用 `graphicsLayer`：图层会缩放
                            // 已经录制好的位图，8× 时字会糊；这里交给矢量回放。
                            native.save()
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
                } else {
                    PiMathSourceBlock(
                        source = source,
                        notice = (draw as? PiMathDraw.Unavailable)?.reason,
                    )
                }
            }
        }
    }
}

/** 一次请求目前产出的东西。永远不持有"解析到一半"的文档。 */
private sealed interface PiMathDraw {
    /** 围栏还没闭合：只显示源码。 */
    data object Streaming : PiMathDraw

    /** 已经问过管线、还没回答：源码 + "渲染中…"。 */
    data object Rendering : PiMathDraw

    /** 已录制的矢量图。 */
    data class Ready(
        val picture: Picture,
        val baseWidthPx: Int,
        val baseHeightPx: Int,
        val aspect: Float,
    ) : PiMathDraw

    /** 这段源码不会出图；原因印在源码块上。 */
    data class Unavailable(val reason: String) : PiMathDraw
}

// ---------------------------------------------------------------------------------
// SVG → Picture
// ---------------------------------------------------------------------------------

/**
 * 把管线已经 sanitize 过的 SVG 解析并录制到有界视口。调用方在 [Dispatchers.Default] 上跑。
 *
 * 每个失败都是 [PiMathDraw.Unavailable]，绝不抛异常：一条画不出来的公式必须显示它的源码，
 * 而不是把整条消息拖垮（方案 §十五"超出限制不崩溃、不冻结，回退源码"）。
 *
 * 用 `renderToPicture(w, h)` 而不是 `renderToCanvas`：picture 可以每帧回放而不重新跑
 * AndroidSVG 的渲染器，调用方把捏合矩阵套在外面即可。
 */
private fun piMathDrawSvg(result: PiSvgResult.Ok, requestedWidthPx: Int): PiMathDraw {
    if (result.svg.length > PI_MATH_MAX_SVG_CHARS) {
        return PiMathDraw.Unavailable("公式 SVG 超过 ${PI_MATH_MAX_SVG_CHARS / (1024 * 1024)} MiB，未渲染")
    }
    val svg = try {
        SVG.getFromString(result.svg)
    } catch (error: Exception) {
        return PiMathDraw.Unavailable("公式 SVG 解析失败：${error.message ?: error::class.java.simpleName}")
    }

    val aspect = piMathAspect(svg, result)
    // 基础视口：块/行内都按"请求宽度（有界）"录制。录制得比屏幕大只会让每次回放更贵，
    // 录制得小也不会丢清晰度——回放仍是矢量缩放。
    var baseWidth = requestedWidthPx.coerceIn(PI_MATH_MIN_WIDTH_PX, PI_MATH_MAX_BASE_WIDTH_PX)
    var baseHeight = (baseWidth / aspect).roundToInt()
    if (baseHeight > PI_MATH_MAX_BASE_HEIGHT_PX) {
        baseHeight = PI_MATH_MAX_BASE_HEIGHT_PX
        baseWidth = (baseHeight * aspect).roundToInt()
    }
    baseWidth = baseWidth.coerceIn(1, PI_MATH_MAX_BASE_WIDTH_PX)
    baseHeight = baseHeight.coerceIn(1, PI_MATH_MAX_BASE_HEIGHT_PX)

    val picture = try {
        svg.renderToPicture(baseWidth, baseHeight)
    } catch (error: Exception) {
        return PiMathDraw.Unavailable("公式 SVG 绘制失败：${error.message ?: error::class.java.simpleName}")
    }
    return PiMathDraw.Ready(picture, baseWidth, baseHeight, aspect)
}

/**
 * 文档宽高比，优先用 SVG 自己声明的 `viewBox`。
 *
 * MathJax 一定会写 `viewBox`（选型文档 §3.3），而它的根 `width`/`height` 是 `ex` 单位、
 * AndroidSVG 会按错误基准换算（R3），所以 `viewBox` 是唯一可靠的形状来源。后面的回退链只是
 * 为了"手写 svg 围栏可能既没有 viewBox 也没有 px 尺寸"。比例算错只会留白（视口与
 * `preserveAspectRatio` 不一致），不会把公式拉变形。
 */
private fun piMathAspect(svg: SVG, result: PiSvgResult.Ok): Float {
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
    return PI_MATH_FALLBACK_ASPECT
}

private fun piMathAspectOrFallback(image: PiMathDraw.Ready): Float {
    val aspect = image.aspect
    return if (aspect.isFinite() && aspect > 0f) aspect else PI_MATH_FALLBACK_ASPECT
}

// ---------------------------------------------------------------------------------
// Gestures
// ---------------------------------------------------------------------------------

/**
 * 捏合缩放 + 拖动平移，手写而不是 `detectTransformGestures`。
 *
 * `detectTransformGestures` 在一根手指移动时就消费整个指针流，于是每张图都会吞掉垂直拖动、
 * 让消息列表滚不过去。这个检测器只在真的改变了什么时消费：两指以上（缩放），或者已经
 * 放大后的一指（平移）。1× 时的单指拖动完全不管，`LazyColumn` 照常工作。
 *
 * 缩放围绕手势质心，scale/offset 都经过 [piMathClampOffset] 收边，公式不会被甩出框外。
 */
private suspend fun PointerInputScope.piMathTransformGestures(
    image: PiMathDraw.Ready,
    readScale: () -> Float,
    readOffset: () -> Offset,
    onTransform: (scale: Float, offset: Offset) -> Unit,
) {
    awaitEachGesture {
        // 一次手势从"适应"或上一次手势留下的值开始；本地副本让循环不必每个指针事件都读
        // Compose state，同时不会跨手势丢更新。
        awaitFirstDown(requireUnconsumed = false)
        var scale = readScale().coerceIn(PI_MATH_MIN_ZOOM, PI_MATH_MAX_ZOOM)
        var offset = readOffset()
        // 视口尺寸取自指针自己的坐标空间：手势中途发生尺寸变化也不会按旧框收边。
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            val zoomChange = if (pressed >= 2) event.calculateZoom() else 1f
            val panChange = event.calculatePan()

            var nextScale = scale
            if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                nextScale = (scale * zoomChange).coerceIn(PI_MATH_MIN_ZOOM, PI_MATH_MAX_ZOOM)
            }
            val panAllowed = panChange != Offset.Zero && (nextScale > PI_MATH_MIN_ZOOM)
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
                offset = piMathClampOffset(
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

/** 1× 时把 `baseWidthPx × baseHeightPx` 的 picture 放进视口所需的缩放。 */
private fun piMathFitScale(
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Float {
    if (viewWidth <= 0f || viewHeight <= 0f || baseWidthPx <= 0 || baseHeightPx <= 0) return 1f
    return min(viewWidth / baseWidthPx, viewHeight / baseHeightPx)
}

/**
 * 把画出来的公式收在框内。
 *
 * 缩放后小于视口的轴只有"居中"这一种合法偏移；大于视口时可以一直移到边缘对齐，所以平移
 * 不会在框里留下空白。
 */
private fun piMathClampOffset(
    wanted: Offset,
    scaleScale: Float,
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Offset {
    val fit = piMathFitScale(viewWidth, viewHeight, baseWidthPx, baseHeightPx) * scaleScale
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
private fun PiMathModeLabel(
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

@Composable
private fun PiMathActionLabel(
    label: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Text(
        text = label,
        style = PiTheme.text.monoSmall,
        color = palette.muted,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * 源码回退块：围栏里的 TeX 原样，用与 App 代码块同一套视觉语言（`mdCodeBlockBorder`
 * 6 % 填充、28 % 边框、12 dp 圆角），失败时读起来像"这里是代码"，而不是一张错误卡片。
 *
 * 显示上限 [PI_MATH_MAX_VISIBLE_SOURCE_LINES] 行并明说剩余行数；复制动作仍然给完整源码。
 * 上限的意义：一条 32 KiB 的坏公式如果整段塞进 `LazyColumn` 会成为一整个巨大的 `Text`。
 */
@Composable
private fun PiMathSourceBlock(source: String, notice: String?) {
    val palette = PiTheme.palette
    val lines = remember(source) { source.lineSequence().toList() }
    val hidden = (lines.size - PI_MATH_MAX_VISIBLE_SOURCE_LINES).coerceAtLeast(0)
    val shown = remember(source, hidden) {
        if (hidden == 0) {
            source
        } else {
            lines.take(PI_MATH_MAX_VISIBLE_SOURCE_LINES).joinToString("\n")
        }
    }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .background(color = palette.mdCodeBlockBorder.copy(alpha = 0.06f), shape = shape)
            .border(
                width = 1.dp,
                color = palette.mdCodeBlockBorder.copy(alpha = 0.28f),
                shape = shape,
            )
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

/** `#RRGGBB` 给 [PiSvgTheme]；契约文件不能出现 Compose 类型。 */
private fun Color.piMathHex(): String =
    "#%06X".format(Locale.US, toArgb() and 0xFFFFFF)

// ---------------------------------------------------------------------------------
// Bounds and constants
// ---------------------------------------------------------------------------------

/** 方案 §十五的源码上限，与 [PiMathRenderEngine] 的 `MAX_SOURCE_CHARS` 保持一致。 */
private const val PI_MATH_MAX_SOURCE_CHARS = 32 * 1024

/** 一条 SVG 超过这个就只显示源码（sanitizer 本该已经拒绝）。 */
private const val PI_MATH_MAX_SVG_CHARS = 2 * 1024 * 1024

/**
 * `Picture` 的录制视口上限。
 *
 * 1600 px 覆盖 2–3× 密度的大屏手机而不至于让一条公式按平板的宽度录制；4096 px 高度是给
 * 多行 `aligned`/`cases` 留的余量，再高就该靠捏合而不是靠更大的录制视口。
 */
private const val PI_MATH_MAX_BASE_WIDTH_PX = 1600
private const val PI_MATH_MAX_BASE_HEIGHT_PX = 4096

/** 低于这个宽度引擎的宿主视口就没有意义；只作为请求的下界。 */
private const val PI_MATH_MIN_WIDTH_PX = 240

/** 无界父级（行内槽位/横向滚动容器）：按一个手机宽度请求，而不是失败。 */
private val PI_MATH_FALLBACK_WIDTH = 360.dp

private const val PI_MATH_MIN_ZOOM = 1f
private const val PI_MATH_MAX_ZOOM = 8f

/**
 * 行内公式的行高。行内公式"按行排"就意味着它得有一个行高做基准；这里取 20 dp（正文
 * 14–16 sp 行高附近）。真正把公式嵌进文本流时，父级可以用 `InlineTextContent` 给更高的
 * 槽位，本节只是保证"单独放出来"时不会把段落撑开。
 */
private val PI_MATH_INLINE_HEIGHT = 20.dp

private val PI_MATH_MIN_IMAGE_HEIGHT = 24.dp

/** 块级公式高于这个就靠捏合/平移看，而不是逼整页继续变高。 */
private val PI_MATH_MAX_IMAGE_HEIGHT = 360.dp

/** 既没有 viewBox、也没有可用尺寸时用的比例。 */
private const val PI_MATH_FALLBACK_ASPECT = 1.6f

/** 必须与宿主页/引擎一致：主题字体只影响 MathJax 的 `\text{}`。 */
private const val PI_MATH_FONT_FAMILY = "sans-serif"

private const val PI_MATH_FEEDBACK_MS = 1500L
private const val PI_MATH_COPIED = "已复制公式源码"

/** 行内公式在没准备好时显示的占位（与 `PiLatex` 的回退同一措辞）。 */
private const val PI_MATH_INLINE_FALLBACK = "（公式）"

/** 失败的源码最多显示这么多行；复制不受影响。 */
private const val PI_MATH_MAX_VISIBLE_SOURCE_LINES = 80
