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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ` ```svg ` 围栏：本地解析后按比例显示，可捏合缩放、可拖动平移（方案 §八）。
 *
 * 与 [PiMermaidView] 的区别只有「SVG 从哪来」：Mermaid 走离屏 WebView 管线拿回一份
 * 主题化的 SVG，这里的 SVG 就是围栏正文本身，所以没有引擎、没有宿主、没有 WebView，
 * 只剩「安全闸门 → AndroidSVG → Picture → Compose Canvas」这条最短路径。
 *
 * ## 状态（永远不空白、永远不显示旧内容）
 *
 * | 条件 | 画什么 |
 * |------|--------|
 * | `settled = false` | 源码（围栏还没闭合，正文是半截的） |
 * | 正文为空 / 超过 [PI_SVG_MAX_SOURCE_CHARS] | 源码 + 一行原因 |
 * | [PiSvgSanitizer] 拒绝 | 源码 + 闸门给出的中文原因（原样转述） |
 * | AndroidSVG 解析/绘制失败 | 源码 + 一行原因 |
 * | 成功 | 图（工具栏可切「源码」） |
 *
 * 最后四种都必须是源码而不是空白区域：一段画不出来的 SVG 在聊天记录里仍然是一份可以
 * 阅读、可以复制的文档，把它变成空白既丢信息又让人以为消息没发完（§十五「超出限制不
 * 崩溃、不冻结，回退源码」）。源码回退不是「错误卡片」：它复用代码块的视觉语言，读起来
 * 就是「这里是一段代码」。
 *
 * ## 为什么先过 sanitizer，再碰 AndroidSVG
 *
 * XML 里的脚本、事件属性、外部引用、`foreignObject` 和 XXE 都在 [PiSvgSanitizer] 里被
 * 拒绝或移除（§八「SVG 安全规则」）。顺序是硬约束：交给 AndroidSVG 的**必须**是清洗后的
 * 字符串，而不是原文 —— 否则「已经检查过」就只是一句注释。正文先 `trim()` 再送入，因为
 * 闸门的输入契约是「完整单根 `<svg>` 文档」，而围栏正文常带首尾空行。
 *
 * ## 为什么是 `Picture` 而不是 `Bitmap`
 *
 * 和 Mermaid 同一个理由：`Picture` 记录的是矢量指令，缩放矩阵写在
 * `nativeCanvas.translate/scale` → `drawPicture` 上，Skia 在最终矩阵下重放路径和文字，
 * 所以 8× 仍然清晰，也不用为 8× 分配一张巨幅位图。代价是记录与每帧重放的开销，因此
 * 基础视口必须有界（见 [PI_SVG_MAX_BASE_WIDTH_PX]）。
 *
 * ## 为什么基础视口是 1600 × 4096 px
 *
 * 和 Mermaid 视图保持一致。1600 px 覆盖高分屏手机上「记录一次、显示按 1:1」所需的宽度，
 * 再大只是让每次捏合的重放更贵（记录按 px 计，显示端反正会按 fit 缩放）；4096 px 给
 * 竖长图（时序图、长流程图）一个上限，避免单帧重放病态。宽度取自身块宽（夹在
 * [PI_SVG_MIN_BASE_WIDTH_PX]…[PI_SVG_MAX_BASE_WIDTH_PX]），于是 1× 时几乎就是 1:1 像素，
 * 小字号 SVG 文字最锐利。
 *
 * ## 交互
 *
 * - **捏合** 以手势质心为锚点缩放到 1×…8×；
 * - **拖动** 只在放大后平移，1× 时的单指拖动**不消费**，让聊天列表照常滚动 —— 图不能
 *   变成滚动陷阱；
 * - **适应** 回到 1× 并居中；
 * - **源码 / 图** 只切显示、不重渲染，且用户手动选择不被渲染结果覆盖；
 * - **复制** 复制围栏的原始正文（§二「复制操作默认复制原始源码」），从不复制渲染结果。
 *
 * ## 已知取舍：不给原始 SVG 注入主题色
 *
 * Mermaid 的管线会把主题色写进它能控制的样式；这里不行 —— 围栏作者用的是什么颜色就是
 * 什么颜色，替一份手写 SVG 猜前景/背景会把有意的配色改错，甚至让浅色底图在深色主题下
 * 变成一块亮砖。所以本文件不构造 `PiSvgTheme`，主题变化只重绘工具栏、不重解析文档
 * （`LaunchedEffect` 的键里刻意没有主题）；深色主题下「黑线黑底」的 SVG 由「源码」按钮兜底。
 *
 * 所有解析与录制都在 [Dispatchers.Default] 上、在 `LaunchedEffect` 里做；`LaunchedEffect`
 * 的键覆盖 `source / settled / 宽度`，正文一变，旧任务立刻取消，旧 `Picture` 不会再被画到
 * 新内容上。
 */
@Composable
fun PiSvgFenceView(
    source: String,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    // `null` 跟渲染结果走；`true` / `false` 是用户显式选的「源码 / 图」。键是 `source`，
    // 所以新的一段围栏从自动模式开始，不会继承上一段的偏好。
    var pinned by remember(source) { mutableStateOf<Boolean?>(null) }
    var draw by remember(source) {
        mutableStateOf<PiSvgDraw>(
            if (settled) PiSvgDraw.Rendering else PiSvgDraw.Streaming,
        )
    }
    var scale by remember(source) { mutableStateOf(PI_SVG_MIN_ZOOM) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedback) {
        val shown = feedback
        if (shown != null) {
            delay(PI_SVG_FEEDBACK_MS)
            // 只清自己那条：复制后立刻又复制，新的提示不能被旧定时器提前抹掉。
            if (feedback == shown) feedback = null
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 记录宽度跟着块宽走（`constraints.maxWidth` 已是 px）；无界父容器（横向滚动
        // 区里）退到一个手机宽度，否则这块本来也放不下。
        val widthPx = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
            constraints.maxWidth.coerceIn(PI_SVG_MIN_BASE_WIDTH_PX, PI_SVG_MAX_BASE_WIDTH_PX)
        } else {
            with(density) { PI_SVG_FALLBACK_WIDTH.toPx() }.roundToInt()
        }

        LaunchedEffect(source, settled, widthPx) {
            if (!settled) {
                draw = PiSvgDraw.Streaming
                return@LaunchedEffect
            }
            if (source.isBlank()) {
                draw = PiSvgDraw.Unavailable("SVG 内容为空")
                return@LaunchedEffect
            }
            if (source.length > PI_SVG_MAX_SOURCE_CHARS) {
                draw = PiSvgDraw.Unavailable(
                    "源码超过 ${PI_SVG_MAX_SOURCE_CHARS / 1024} KiB，未渲染",
                )
                return@LaunchedEffect
            }
            draw = PiSvgDraw.Rendering
            draw = try {
                withContext(Dispatchers.Default) {
                    // 安全检查 + XML 解析 + `Picture` 录制，三件都是纯 CPU 且可能很重，
                    // 一律离开主线程。`LaunchedEffect` 被取消时 `withContext` 抛出
                    // `CancellationException`，下面原样重抛，绝不吞成「失败」。
                    piSvgPrepare(source = source, requestedWidthPx = widthPx)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 引擎之外的意外（含 AndroidSVG 内部抛出的 Error）：同样回退源码，
                // 一条消息里的围栏不能把整个会话带崩。
                PiSvgDraw.Unavailable(
                    "SVG 渲染失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }

        // `Column` 的 scope 带 `@LayoutScopeMarker`，从它里面读不到外层 `BoxWithConstraintsScope`
        // 的 `maxWidth`（DslMarker 挡住隐式外层接收者）。进 `Column` 之前先取出来。
        val blockWidth = maxWidth
        val ready = draw as? PiSvgDraw.Ready
        // 没有图 / 还在流式 / 用户选了源码 → 显示源码。成功的渲染不会被自动藏起来。
        val showSource = ready == null || !settled || pinned == true
        val image = ready?.takeIf { !showSource }

        val status: String? = feedback ?: when (draw) {
            PiSvgDraw.Streaming -> "生成中…"
            PiSvgDraw.Rendering -> "渲染中…"
            // 原因整句写在源码块上（那里有整行宽度）；工具栏只留一行状态，放不下。
            is PiSvgDraw.Unavailable -> null
            is PiSvgDraw.Ready -> if (showSource) "源码" else null
        }

        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiSvgModeLabel(
                    label = "源码",
                    selected = showSource,
                    enabled = true,
                    onClickLabel = "查看 SVG 源码",
                    onClick = { pinned = true },
                )
                Spacer(Modifier.width(12.dp))
                PiSvgModeLabel(
                    label = "图",
                    selected = !showSource,
                    enabled = ready != null,
                    onClickLabel = "查看渲染后的 SVG",
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
                    PiSvgActionLabel(
                        label = "适应",
                        onClickLabel = "适应屏幕，重置缩放",
                        onClick = {
                            scale = PI_SVG_MIN_ZOOM
                            offset = Offset.Zero
                        },
                    )
                }
                PiSvgActionLabel(
                    // 提示走工具栏状态位（「已复制源码」），按钮字面量保持不变，复制时
                    // 工具栏不会因为文字变长而抖动。
                    label = "复制",
                    onClickLabel = "复制 SVG 源码",
                    onClick = {
                        // 原始正文，不是 `trim()` 后的、更不是渲染结果。
                        clipboard.setText(AnnotatedString(source))
                        feedback = PI_SVG_COPIED
                    },
                )
            }

            if (image != null) {
                val aspect = image.aspect.takeIf { it.isFinite() && it > 0f }
                    ?: PI_SVG_FALLBACK_ASPECT
                val imageHeight = (blockWidth / aspect)
                    .coerceIn(PI_SVG_MIN_IMAGE_HEIGHT, PI_SVG_MAX_IMAGE_HEIGHT)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imageHeight)
                        .clipToBounds()
                        .pointerInput(image) {
                            piSvgTransformGestures(
                                image = image,
                                readScale = { scale },
                                readOffset = { offset },
                            ) { nextScale, nextOffset ->
                                scale = nextScale
                                offset = nextOffset
                            }
                        }
                        .semantics { contentDescription = "SVG 渲染图" },
                ) {
                    val fit = piSvgFitScale(
                        viewWidth = size.width,
                        viewHeight = size.height,
                        baseWidthPx = image.baseWidthPx,
                        baseHeightPx = image.baseHeightPx,
                    ) * scale
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        // 变换写在 native canvas 上而不是 `graphicsLayer`：layer 会把记录
                        // 好的栅格放大、文字发虚，这里是在最终矩阵下重放矢量指令。
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
                val unavailable = draw as? PiSvgDraw.Unavailable
                PiSvgSourceBlock(
                    source = source,
                    notice = unavailable?.reason,
                )
            }
        }
    }
}

/** 一次渲染请求走到哪一步。绝不持有「解析了一半」的文档。 */
private sealed interface PiSvgDraw {
    /** 围栏没闭合：只显示源码。 */
    data object Streaming : PiSvgDraw

    /** 已经排进后台、还没出结果：源码 + `渲染中…`。 */
    data object Rendering : PiSvgDraw

    /** 录制完成、可重放的图。 */
    data class Ready(
        val picture: Picture,
        val baseWidthPx: Int,
        val baseHeightPx: Int,
        val aspect: Float,
    ) : PiSvgDraw

    /** 这份源码不会出图；原因印在源码块上。 */
    data class Unavailable(val reason: String) : PiSvgDraw
}

// ---------------------------------------------------------------------------------
// 源码 → Picture
// ---------------------------------------------------------------------------------

/**
 * 把围栏正文变成可重放的 [Picture]：先 sanitize，再 AndroidSVG。
 *
 * 调用方保证在 [Dispatchers.Default] 上执行。**任何**问题都返回
 * [PiSvgDraw.Unavailable]，不抛异常 —— 画不出来的图必须显示源码，而不是让一条消息
 * 把整个会话拖垮（§十五）。
 *
 * 先 `trim()` 是因为围栏正文通常以换行开头/结尾，而 [PiSvgSanitizer] 的输入契约是
 * 「完整的单根 `<svg>` 文档」；`trim()` 只去掉首尾空白，不改变文档内容。之后**只**把
 * sanitizer 返回的字符串交给 AndroidSVG，原文永远不会到达渲染器。
 */
private fun piSvgPrepare(source: String, requestedWidthPx: Int): PiSvgDraw {
    val body = source.trim()
    val sanitized = try {
        PiSvgSanitizer.sanitize(body)
    } catch (cancellation: CancellationException) {
        // 纯函数里不会有挂起点，但保持和调用方一致的约定：取消永远上抛。
        throw cancellation
    } catch (error: Throwable) {
        return PiSvgDraw.Unavailable(
            "SVG 安全检查失败：${error.message ?: error::class.java.simpleName}",
        )
    }
    val safe = when (sanitized) {
        // 闸门的拒绝原因是中文整句，原样转述，不再包一层「渲染失败」。
        is PiSvgSanitizeResult.Rejected -> return PiSvgDraw.Unavailable(sanitized.reason)
        is PiSvgSanitizeResult.Ok -> sanitized.svg
    }

    val svg = try {
        SVG.getFromString(safe)
    } catch (error: Exception) {
        return PiSvgDraw.Unavailable("SVG 解析失败：${error.message ?: error::class.java.simpleName}")
    }

    val aspect = piSvgAspect(svg)
    // 记录视口：跟着块宽（有界），再按长宽比推高度。记录得比屏幕大只会让每次捏合的
    // 重放更贵；记录得再小也不丢细节，因为重放仍然是矢量的。
    var baseWidth = requestedWidthPx.coerceIn(PI_SVG_MIN_BASE_WIDTH_PX, PI_SVG_MAX_BASE_WIDTH_PX)
    var baseHeight = (baseWidth / aspect).roundToInt()
    if (baseHeight > PI_SVG_MAX_BASE_HEIGHT_PX) {
        baseHeight = PI_SVG_MAX_BASE_HEIGHT_PX
        baseWidth = (baseHeight * aspect).roundToInt()
    }
    baseWidth = baseWidth.coerceIn(1, PI_SVG_MAX_BASE_WIDTH_PX)
    baseHeight = baseHeight.coerceIn(1, PI_SVG_MAX_BASE_HEIGHT_PX)

    val picture = try {
        // 例外即失败：`renderToPicture` 抛异常时不会有半张图交回来。
        svg.renderToPicture(baseWidth, baseHeight)
    } catch (error: Exception) {
        return PiSvgDraw.Unavailable("SVG 绘制失败：${error.message ?: error::class.java.simpleName}")
    }
    return PiSvgDraw.Ready(picture, baseWidth, baseHeight, aspect)
}

/**
 * 文档的宽高比，优先用 SVG 自己声明的值。
 *
 * 手写 SVG 可能只有 `viewBox`，也可能只有 `width`/`height`，所以走一条回退链；
 * 这里算错只会让图上下留白（视口与 `preserveAspectRatio` 不一致），不会拉伸变形。
 */
private fun piSvgAspect(svg: SVG): Float {
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
    return PI_SVG_FALLBACK_ASPECT
}

// ---------------------------------------------------------------------------------
// 手势
// ---------------------------------------------------------------------------------

/**
 * 捏合缩放 + 拖动平移，手写而不 `detectTransformGestures`。
 *
 * `detectTransformGestures` 一旦有手指移动就消费整条指针流，于是每一张图都会吞掉竖直
 * 拖动、让下面的聊天列表滚不动。这个检测器只在**真的改变了什么**时才消费：两指以上
 * （缩放），或者已经放大后的单指（平移）。1× 下的单指拖动原样留给 `LazyColumn`。
 *
 * 缩放围绕手势质心，手指底下的那一点保持不动；缩放与偏移都过 [piSvgClampOffset]，
 * 图不会被甩出可视框。
 */
private suspend fun PointerInputScope.piSvgTransformGestures(
    image: PiSvgDraw.Ready,
    readScale: () -> Float,
    readOffset: () -> Offset,
    onTransform: (scale: Float, offset: Offset) -> Unit,
) {
    awaitEachGesture {
        // 手势从「适应」或上一次手势留下的状态开始；本地副本避免在每个指针事件里读
        // Compose state，同时跨手势也不会丢更新。
        awaitFirstDown(requireUnconsumed = false)
        var scale = readScale().coerceIn(PI_SVG_MIN_ZOOM, PI_SVG_MAX_ZOOM)
        var offset = readOffset()
        // 视口尺寸取自指针自己的坐标空间，手势中途改尺寸也不会拿旧框去夹。
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            val zoomChange = if (pressed >= 2) event.calculateZoom() else 1f
            val panChange = event.calculatePan()

            var nextScale = scale
            if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                nextScale = (scale * zoomChange).coerceIn(PI_SVG_MIN_ZOOM, PI_SVG_MAX_ZOOM)
            }
            val panAllowed = panChange != Offset.Zero && (nextScale > PI_SVG_MIN_ZOOM)
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
                offset = piSvgClampOffset(
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

/** `baseWidthPx × baseHeightPx` 的图在 1× 时铺满视口的缩放比。 */
private fun piSvgFitScale(
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Float {
    if (viewWidth <= 0f || viewHeight <= 0f || baseWidthPx <= 0 || baseHeightPx <= 0) return 1f
    return min(viewWidth / baseWidthPx, viewHeight / baseHeightPx)
}

/**
 * 把画出来的图夹在框内。
 *
 * 放大后的图在某个轴上比视口小时，唯一合法的偏移是 0（居中）；否则图可以一直移动到
 * 它的边缘碰到视口边缘，这样平移不会在框里留下空白。
 */
private fun piSvgClampOffset(
    wanted: Offset,
    scaleScale: Float,
    viewWidth: Float,
    viewHeight: Float,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Offset {
    val fit = piSvgFitScale(viewWidth, viewHeight, baseWidthPx, baseHeightPx) * scaleScale
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
// 工具栏与源码回退
// ---------------------------------------------------------------------------------

@Composable
private fun PiSvgModeLabel(
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
private fun PiSvgActionLabel(
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
            .padding(start = 12.dp, vertical = 2.dp),
    )
}

/**
 * 原始源码回退：围栏正文逐字显示，视觉语言与 App 的代码块一致
 * （`mdCodeBlockBorder` 6 % 填充、28 % 描边、12 dp 圆角），让「画不出来」读起来是
 * 「这里是一段代码」，而不是一张错误卡片。
 *
 * 显示上限 [PI_SVG_MAX_VISIBLE_SOURCE_LINES] 行并写明剩余行数；复制仍然是全文。设上限
 * 是因为一段失败的大 SVG 否则会变成聊天列表里一个巨大的 `Text`。
 */
@Composable
private fun PiSvgSourceBlock(source: String, notice: String?) {
    val palette = PiTheme.palette
    val lines = remember(source) { source.lineSequence().toList() }
    val hidden = (lines.size - PI_SVG_MAX_VISIBLE_SOURCE_LINES).coerceAtLeast(0)
    val shown = remember(source, hidden) {
        if (hidden == 0) {
            source
        } else {
            lines.take(PI_SVG_MAX_VISIBLE_SOURCE_LINES).joinToString("\n")
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

// ---------------------------------------------------------------------------------
// 上限与常量
// ---------------------------------------------------------------------------------

/**
 * 围栏正文的字符上限：256 KiB。
 *
 * 取 256 KiB 而不是闸门的 2 MiB，有两个理由：一是聊天里一段超过 256 KiB 的 SVG 已经
 * 不是「图」，逐字阅读和逐帧重放都没有意义；二是这个值换算成 UTF-8 最多 768 KiB，永远
 * 低于 [PiSvgSanitizer.MAX_BYTES]，于是用户看到的是「源码超过 256 KiB」这条**关于围栏**
 * 的提示，而不是一条关于字节数的、和围栏无关的闸门提示。真正的结构/尺寸上限仍由闸门的
 * [PiSvgSanitizer.MAX_NODES] / [PiSvgSanitizer.MAX_DEPTH] / [PiSvgSanitizer.MAX_DIMENSION]
 * 负责。
 */
private const val PI_SVG_MAX_SOURCE_CHARS = 256 * 1024

/**
 * 记录用的基础视口上限（px），与 Mermaid 视图共用同一组数字。
 *
 * 1600 px 覆盖高分屏手机按 1:1 记录所需的宽度；再大只让每次捏合的重放更贵。4096 px
 * 给竖长图一个上限，避免单帧重放病态 —— 更高的图在显示端被 `height` 夹住并靠缩放/平移
 * 查看。
 */
private const val PI_SVG_MAX_BASE_WIDTH_PX = 1600
private const val PI_SVG_MAX_BASE_HEIGHT_PX = 4096

/** 记录宽度下限；只有极窄的父容器才会用到。 */
private const val PI_SVG_MIN_BASE_WIDTH_PX = 240

/** 无界父容器（横向滚动区）：按一个手机宽度记录，而不是失败。 */
private val PI_SVG_FALLBACK_WIDTH = 360.dp

private const val PI_SVG_MIN_ZOOM = 1f
private const val PI_SVG_MAX_ZOOM = 8f

private val PI_SVG_MIN_IMAGE_HEIGHT = 120.dp

/** 比这个高的图靠缩放/平移看，不逼着页面变长。 */
private val PI_SVG_MAX_IMAGE_HEIGHT = 460.dp

/** SVG 既没有 `viewBox` 也没有可用宽高时的兜底比例。 */
private const val PI_SVG_FALLBACK_ASPECT = 1.6f

/** 工具栏状态位停留时间：够读，不赖着不走。 */
private const val PI_SVG_FEEDBACK_MS = 1500L
private const val PI_SVG_COPIED = "已复制源码"

/** 失败回退的源码最多显示这么多行；复制不受影响。 */
private const val PI_SVG_MAX_VISIBLE_SOURCE_LINES = 200
