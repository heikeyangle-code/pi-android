package app.pi.ui.blocks

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 「放大之后要重新解码」这一步的**全部算术**：当前视口对应源图上的哪一个像素矩形、那块要按
 * 几倍采样、以及它在 1× 画面上贴在哪儿。
 *
 * ## 为什么这值得一个单独的文件
 *
 * 查看器放大到 8× 时看到的是**被拉大的采样位图**：`decodePiImage` 按视口取样，一张 4000×3000
 * 的照片在 1080 宽的窗口里被 `inSampleSize=2` 解成 2000×1500（≈12 MB），放大时每个采样像素撑
 * 8 个屏幕像素 —— 采样时丢掉的细节再也回不来。用户的话是
 * 「放大了肯定要看到更多细节呀，不然放大还有啥用？」。
 *
 * 修法只有在放大时按源图坐标重解**可见的那一块**。而「可见的那一块」是一段纯粹的算术：视口
 * 盒、1× 的落点、倍率、平移、两个尺寸（基础位图与源图）之间来回换算。这段算术如果长在 Compose
 * 文件里，本机就一行都跑不了（这台机器没有 android.jar、也没有 Compose 编译器插件）；搬到这里
 * 之后它可以被 `app/src/test/kotlin/app/pi/ui/blocks/PiZoomDecodeWindowCheck.kt` **真的执行**
 * —— 边界、极端宽高比、被 `clampPan` 夹过的平移、以及与 `decodePiImageBytes` 采样规则配合出来
 * 的内存上界，都是跑出来的，不是读出来的。
 *
 * 本文件除 `kotlin.math` 外零 import，和 [ImageSize] 一样：它长出 Android 或 Compose 的 import，
 * `tools/run-app-pure-checks.sh` 就会编译失败，这正是目的。
 *
 * ## 为什么是区域解码，而不是「按更大的目标重解整张」
 *
 * 12 MP 原图整张解出来是 4000×3000×4 B ≈ **48 MB**，50 MP 是 ≈ **192 MB**。而 `inSampleSize`
 * 只能整倍减半，所以「重解整张」对 12 MP 照片只有两个结局：要么和今天一模一样（采样仍是 2，
 * 一点细节都不多），要么一次性付 48 MB。48 MB 在 `PiImageCache.MAX_TOTAL_BYTES`（32 MiB）旁边
 * 站不住，也是这台手机上真会 OOM 的量。区域解码是唯一两条都不占的路：**位图内存与原图多大
 * 无关**。
 *
 * ## 内存上界（这三条就是这次改动的硬约束，数字不是形容词）
 *
 *  1. **一块 ≤ [DETAIL_MAX_PIXELS] = 2 000 000 像素 = 8 000 000 B**（`ARGB_8888`，4 B/像素），
 *     与源图多大、屏幕多大都无关 —— 由 [zoomDecodeWindow] 的采样倍率循环强制，超预算就整倍
 *     降采样（[ZoomDecodeWindow.sample]），而不是让它涨上去。1080×2400 的屏幕上常见照片根本
 *     用不到倍率（余量后的块 ≈ 1.0–1.5 M 像素）；4K 平板上倍率会真的顶上。
 *  2. **只在「比现在屏幕上那张更清楚」时才要块**：块的采样倍率必须**严格小于**基础图自己的
 *     抽稀倍数（[baseDecimation]），否则这块和屏幕上已经有的东西一样清楚，花掉 8 MB 换一次
 *     解码、换一张看不出区别的图 —— 那就不要。这条是 [zoomDecodeWindow] 返回 `null` 的主要
 *     理由，也是「50 MP 图在 4× 时仍然糊」（预算只能给它一个不比基础图更清楚的块）这句
 *     老实话的来源。
 *  3. **1× 一个字节都不多要**：`scale × fit ≥` [DETAIL_SCALE_DEFICIT] 才可能取块，而
 *     `base > 源图` 的抽稀一旦发生，`fit ≤ 1`（`decodePiImageBytes` 的采样规则保证基础图面积
 *     大于视口面积，或两个轴都不小于视口），所以 1× 恒不取块。缩回 1× 时调用方当场丢掉上一块
 *     （见 `PiImageViewer`），1× 的内存与改动前逐字节相同。
 *
 * 区域解码**不**走 [PiImageCache]：块是「当前这一屏」的东西，随平移换掉，而且它有一个恒定的
 * 字节上界；塞进那个按 payload 计费的缓存只会为了几 MB 的块多留一份几 MB 的 base64 字符串。
 * 块的寿命就是查看器的寿命。
 *
 * ## 编码字节仍然与源图成正比（明写，不含糊）
 *
 * 上面那几条说的是**位图**。区域解码本身要把整份**编码**字节拿在手里（`BitmapRegionDecoder`
 * 的 byte[] 入口内部还会再复制一份），而编码字节是源图的函数：一张 50 MP 的 JPEG 大约
 * 15–25 MB，转瞬即逝（解完 `recycle()` 就释放）。常驻的只有那块 ≤8 MB 的位图 —— 这是格式的
 * 代价，不是采样能省掉的。
 */

/**
 * 基础图的一个像素被摊到多少屏幕像素，才值得为细节再解一次（2）。
 *
 * `scale × fit < 2` 时屏幕上每个基础图像素还摊不到 2 个 —— 基础图本身就是清晰的那一份，重解
 * 只会换来一块和它一样糊的图。用户要的是「双击放到最大」，所以这条阈值的实际作用点是 4×–8×
 * （常见手机 + 常见照片），也就是用户真正在抱怨的那一段。
 */
internal const val DETAIL_SCALE_DEFICIT = 2f

/**
 * 块在可见区之外多要的余量，按可见区的边长比例（0.25 = 每边 25%）。
 *
 * 平移时先用上一块顶着，走出这块余量才重新解 —— 余量太小会每拖一下就重解，太大则白占内存
 * （面积是 `(1+2m)² = 2.25` 倍）。预算吃紧时这个余量是第一个被收掉的东西（见 [zoomDecodeWindow]）：
 * 一块「更清楚但没有余量」的图，比一块「有余量但和屏幕上一样糊」的图有用得多。
 */
internal const val DETAIL_MARGIN_FRACTION = 0.25f

/**
 * 一块细节位图的像素面积上限（2 000 000 像素 = 8 000 000 B）。见文件头第 1 条：这是**硬**上限，
 * 超了降采样，而不是让它涨上去。
 */
internal const val DETAIL_MAX_PIXELS = 2_000_000L

/**
 * 采样倍率的加倍次数上限（64×）。
 *
 * 预算永远够用：`sample` 每翻一倍，块面积除以 4，所以从 8 M 像素降到 2 M 只需要一倍。这个上限
 * 只是防御 —— 没有它，一段坏输入（比如源图尺寸读成天文数字）能让这个循环转很久。
 */
private const val MAX_DETAIL_SAMPLE = 64

/**
 * 解码驱动在一次快照里看到的那一帧：**落定的**倍率与平移。
 *
 * 为什么不直接把 `scale`/`pan` 传给算术：双击是动画（1× → 8× 会连续经过中间每一档），照「当前
 * 倍率跨过阈值」触发的话一次双击会连开好几次解码 —— 用户明确说了「不用太费性能」。所以驱动只看
 * 静默之后的值。
 *
 * **「动画正在跑」不进这个类**（它曾经在这里，作为第四个字段，但调用点在构造之前就已经用
 * `if (animating) null else …` 把它过滤掉了，于是那个字段永远是 `false`、也没有任何读者 ——
 * 一个只会误导下一个人的常量）。动画中驱动的做法是**不发值**，而不是发一位 `false`。
 *
 * `equals` 就是数据类的逐字段比较 —— `snapshotFlow` 靠它决定要不要重新发一个值，浮点逐位相等
 * 正是「手势真的动了」的判据。
 */
internal data class ZoomProbe(val scale: Float, val panX: Float, val panY: Float)

/** 源图上的一个像素矩形，半开区间 `[left, right) × [top, bottom)`。 */
internal data class SourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 一次区域解码的请求：源图矩形 + 采样倍率。
 *
 * [sample] 是给 `BitmapRegionDecoder` 的 `inSampleSize`，所以解码结果的像素尺寸是
 * `ceil(矩形边长 / sample)`，面积 ≤ [DETAIL_MAX_PIXELS]。
 */
internal data class ZoomDecodeWindow(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sample: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 块在 1× 画面坐标系里的位置与大小（px）。那一层 `graphicsLayer` 会把它和基础图一起缩放、平移，
 * 所以这里只需要知道「贴在画面的哪个位置」，不需要知道当前倍率 —— 这也是它在拖动时不会跟丢的
 * 原因。
 */
internal data class ZoomDecodePlacement(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * 1× 把图放进视口的那条比例，与画布上 `ContentScale.Fit` 的落点一致（也是 `PiImageViewer` 给
 * 图片节点的 `size`）。
 *
 * 退化输入（非正的盒或位图尺寸）返回 1，与画布原来那句 `takeIf { … } ?: 1f` 同一个意思：这一层
 * 不该让一个 0 去乘出 NaN。
 */
internal fun viewerFit(boxWidthPx: Int, boxHeightPx: Int, baseWidthPx: Int, baseHeightPx: Int): Float {
    if (boxWidthPx <= 0 || boxHeightPx <= 0 || baseWidthPx <= 0 || baseHeightPx <= 0) return 1f
    val fit = min(boxWidthPx.toFloat() / baseWidthPx, boxHeightPx.toFloat() / baseHeightPx)
    return if (fit.isFinite() && fit > 0f) fit else 1f
}

/**
 * 当前视口对应源图上的哪一个矩形，或者 `null`（= 画面上就该是今天那张基础图，不必取块）。
 *
 * 返回 `null` 的三种情况，每一种都有它自己的理由，而且都必须真的退化成今天的行为：
 *
 *  - **源图不比基础图多像素**（两个轴都不超）：屏幕再大也没有更多细节可解。原图比视口还小时
 *    走的就是这一条 —— 那种图在 1× 就已经 1:1（甚至被放大），采样倍率只能是 1。
 *  - **倍率不够**：`scale × fit <` [DETAIL_SCALE_DEFICIT]，基础图的像素还没有被摊到 2 个屏幕
 *    像素以上。
 *  - **输入不成形**：非正的盒/尺寸，非有限或非正的倍率与平移。
 */
internal fun zoomVisibleSourceRect(
    boxWidthPx: Int,
    boxHeightPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    scale: Float,
    panX: Float,
    panY: Float,
): SourceRect? {
    if (boxWidthPx <= 0 || boxHeightPx <= 0) return null
    if (baseWidthPx <= 0 || baseHeightPx <= 0) return null
    if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
    if (!scale.isFinite() || scale <= 0f) return null
    if (!panX.isFinite() || !panY.isFinite()) return null
    if (sourceWidthPx <= baseWidthPx && sourceHeightPx <= baseHeightPx) return null

    val fit = viewerFit(boxWidthPx, boxHeightPx, baseWidthPx, baseHeightPx)
    if (scale * fit < DETAIL_SCALE_DEFICIT) return null

    val fittedWidth = baseWidthPx * fit
    val fittedHeight = baseHeightPx * fit
    // 视口落在「1× 画面」上的矩形：图心在画面中心，`graphicsLayer` 的 translation 是屏幕像素，
    // 除回 scale 才是画面坐标。图比视口小时取整个图（`min`），所以它永远在画面内。
    val visibleWidth = min(fittedWidth, boxWidthPx / scale)
    val visibleHeight = min(fittedHeight, boxHeightPx / scale)
    if (visibleWidth <= 0f || visibleHeight <= 0f) return null
    val centeredX = (fittedWidth - visibleWidth) / 2f
    val centeredY = (fittedHeight - visibleHeight) / 2f
    // 夹取是安全网：`clampPan` 已经保证图盖住视口，但一段越界的平移（手势写入与解码之间的竞态、
    // 或将来换了夹取规则）不能在这里产出一个跑到源图外面的矩形。
    val leftF = (centeredX - panX / scale).coerceIn(0f, max(0f, fittedWidth - visibleWidth))
    val topF = (centeredY - panY / scale).coerceIn(0f, max(0f, fittedHeight - visibleHeight))

    // 画面坐标 → 源图像素。向外取整：宁可多要一行像素，不能少要一行（少要 = 屏幕边缘一条缝上
    // 露出下面那张更糊的图）。
    val perFittedX = sourceWidthPx / fittedWidth
    val perFittedY = sourceHeightPx / fittedHeight
    val left = floor(leftF * perFittedX).toInt().coerceIn(0, sourceWidthPx - 1)
    val top = floor(topF * perFittedY).toInt().coerceIn(0, sourceHeightPx - 1)
    val right = ceil((leftF + visibleWidth) * perFittedX).toInt().coerceIn(left + 1, sourceWidthPx)
    val bottom = ceil((topF + visibleHeight) * perFittedY).toInt().coerceIn(top + 1, sourceHeightPx)
    return SourceRect(left, top, right, bottom)
}

/**
 * 要解的那一块，或者 `null`（= **不该解**：解出来不会比屏幕上那张更清楚，或者一块装不下）。
 *
 * 三步，每一步都在回答「不加它用户会看到什么」：
 *
 *  1. 先按 [DETAIL_MARGIN_FRACTION] 的余量要一块（平移时可以用它顶着）。够清楚就用它。
 *  2. 预算把余量吃掉时**收掉余量**再要一次：可见区本身往往还装得下。代价是平移要重解（每停
 *     一次解一块），换来的是「真的更清楚」——不加这一步，4× 的照片就永远只有一张和基础图一样
 *     糊的块（余量让它多要 2.25 倍面积，正好把 1:1 挤成 1:2）。
 *  3. 连可见区都要降采样才装得下（`sample ≥` [baseDecimation]）：这块和屏幕上已经有的东西一样
 *     清楚，**返回 `null`**，一个字节都不解。50 MP 的图在小倍率下就是这一条 —— 如实承认它仍然
 *     是糊的，而不是花 8 MB 假装有细节。
 */
internal fun zoomDecodeWindow(
    visible: SourceRect,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
): ZoomDecodeWindow? {
    val decimation = baseDecimation(sourceWidthPx, sourceHeightPx, baseWidthPx, baseHeightPx)
    val margined = buildZoomWindow(visible, sourceWidthPx, sourceHeightPx, DETAIL_MARGIN_FRACTION)
    if (margined.sample < decimation) return margined
    val exact = buildZoomWindow(visible, sourceWidthPx, sourceHeightPx, 0f)
    if (exact.sample < decimation) return exact
    return null
}

/**
 * 手里那块 [held] 是否仍然盖住 [visible]：盖住就继续画它，不必重新解码。
 *
 * 这就是「平移时先用上一块顶着」的判据，也是「双击 8× ⇄ 1× 反复不重解整张图」的一半 ——
 * 另一半是：1× 那张基础图本来就缓存在 [PiImageCache] 里（键含目标框），回来时是命中。
 */
internal fun zoomDecodeWindowCovers(held: ZoomDecodeWindow, visible: SourceRect): Boolean =
    held.sample > 0 &&
        held.width > 0 &&
        held.height > 0 &&
        held.left <= visible.left &&
        held.top <= visible.top &&
        held.right >= visible.right &&
        held.bottom >= visible.bottom

/**
 * [window] 在 1× 画面坐标系里的位置与大小。
 *
 * 只由「块在源图上的位置」与「源图 → 画面」这一个比例决定，所以它与当前倍率、平移**无关**：
 * 拖动时块自己会跟着 `graphicsLayer` 走，不需要每帧重算。
 */
internal fun zoomDecodePlacement(
    window: ZoomDecodeWindow,
    boxWidthPx: Int,
    boxHeightPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
): ZoomDecodePlacement {
    if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return ZoomDecodePlacement(0f, 0f, 0f, 0f)
    val fit = viewerFit(boxWidthPx, boxHeightPx, baseWidthPx, baseHeightPx)
    val perSourceX = baseWidthPx * fit / sourceWidthPx
    val perSourceY = baseHeightPx * fit / sourceHeightPx
    return ZoomDecodePlacement(
        left = window.left * perSourceX,
        top = window.top * perSourceY,
        width = window.width * perSourceX,
        height = window.height * perSourceY,
    )
}

/**
 * 基础图自己的抽稀倍数：源图的每个像素被几个基础图像素代表（`decodePiImageBytes` 用的是
 * `inSampleSize`，所以它是 2 的幂，这里按上取整算以防非整倍）。
 *
 * 块的采样倍率必须**严格小于**它，否则块和屏幕上那张一样清楚（见 [zoomDecodeWindow] 第 3 步）。
 */
private fun baseDecimation(
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
): Int {
    if (baseWidthPx <= 0 || baseHeightPx <= 0 || sourceWidthPx <= 0 || sourceHeightPx <= 0) return 1
    val byWidth = (sourceWidthPx + baseWidthPx - 1) / baseWidthPx
    val byHeight = (sourceHeightPx + baseHeightPx - 1) / baseHeightPx
    // 取大的那个：只要有一个轴上块更细，这块就比屏幕上那张多了东西（均匀采样下两个轴相等，
    // 这条只是让非均匀的坏输入有一个确定的答案）。
    return max(1, max(byWidth, byHeight))
}

/**
 * 由可见区算出矩形：外扩 [marginFraction]、夹进源图、按 [DETAIL_MAX_PIXELS] 选采样倍率，并把
 * 矩形**对齐到采样倍率的整数倍**。
 *
 * 对齐是为了让结果尺寸没有歧义：`decodeRegion` 的输出是矩形边长除以采样倍率（向上取整），矩形
 * 自己就是 `sample` 的整数倍时，这个除法不会因为取整方向而在两个平台上差一个像素。
 *
 * 预算按**对齐之后**的矩形算（见下面的循环），所以出来那一块的解码面积一定 ≤
 * [DETAIL_MAX_PIXELS]：对齐只会把矩形往外长，先定倍率再对齐就会让上界差几个像素。
 */
private fun buildZoomWindow(
    visible: SourceRect,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    marginFraction: Float,
): ZoomDecodeWindow {
    // 防御性夹取：调用方只该给 [zoomVisibleSourceRect] 的结果。真给了越界的矩形，这里把它裁到
    // 源图边界内 —— 区域解码在源图外面解会返回 null，那就连块都没有了。
    val left0 = visible.left.coerceIn(0, max(0, sourceWidthPx - 1))
    val top0 = visible.top.coerceIn(0, max(0, sourceHeightPx - 1))
    val right0 = visible.right.coerceIn(left0 + 1, max(left0 + 1, sourceWidthPx))
    val bottom0 = visible.bottom.coerceIn(top0 + 1, max(top0 + 1, sourceHeightPx))

    val spanX = right0 - left0
    val spanY = bottom0 - top0
    val marginX = if (marginFraction <= 0f) 0 else max(1, (spanX * marginFraction).toInt())
    val marginY = if (marginFraction <= 0f) 0 else max(1, (spanY * marginFraction).toInt())
    val left1 = (left0 - marginX).coerceAtLeast(0)
    val top1 = (top0 - marginY).coerceAtLeast(0)
    val right1 = (right0 + marginX).coerceAtMost(sourceWidthPx)
    val bottom1 = (bottom0 + marginY).coerceAtMost(sourceHeightPx)

    // 采样倍率：翻倍是唯一的调节手段（`inSampleSize` 只有整倍），`sample²` 就是面积被除掉的
    // 倍数。
    var sample = 1
    while (true) {
        val left = alignDown(left1, sample)
        val top = alignDown(top1, sample)
        val right = min(sourceWidthPx, alignUp(right1, sample))
        val bottom = min(sourceHeightPx, alignUp(bottom1, sample))
        val decodedWidth = (right - left + sample - 1) / sample
        val decodedHeight = (bottom - top + sample - 1) / sample
        if (decodedWidth.toLong() * decodedHeight.toLong() <= DETAIL_MAX_PIXELS ||
            sample >= MAX_DETAIL_SAMPLE
        ) {
            return ZoomDecodeWindow(left, top, right, bottom, sample)
        }
        sample *= 2
    }
}

/** `v` 向下取到 [sample] 的整数倍（非负输入，所以就是整除）。 */
private fun alignDown(v: Int, sample: Int): Int = (v / sample) * sample

/** `v` 向上取到 [sample] 的整数倍。 */
private fun alignUp(v: Int, sample: Int): Int = ((v + sample - 1) / sample) * sample
