package app.pi.ui.blocks

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * 查看器里那两半**纯算术**：放大后要解哪一块（[zoomDecodeWindow]），以及拖动时平移该落在哪里
 * （[panAfterDrag]）。
 *
 * 「放大之后要重新解码」这一半：当前视口对应源图上的哪一个像素矩形、那块要按几倍采样、以及它在
 * 1× 画面上贴在哪儿。
 *
 * ## 为什么这值得一个单独的文件
 *
 * 查看器放大到 8× 时看到的是**被拉大的采样位图**：`decodePiImage` 按视口取样，一张 4000×3000
 * 的照片在 1080 宽的窗口里被 `inSampleSize=2` 解成 2000×1500（≈12 MB），放大时每个采样像素撑
 * 8 个屏幕像素 —— 采样时丢掉的细节再也回不来。用户的话是
 * 「放大了肯定要看到更多细节呀，不然放大还有啥用？」。
 *
 * 放大还有一个**上界**问题：倍率超过「1 源像素 = 1 屏幕像素」之后，源里就没有更多信息了，
 * 任何解码都救不了（一张和屏幕一样宽的截图放到 8× 就是 8 倍放大）。所以最大倍率按图算，见
 * [maxZoomForSource]；而「该多细」由 [detailBudgetPixels] 从视口面积推出来。
 *
 * 修法只有在放大时按源图坐标重解**可见的那一块**。而「可见的那一块」是一段纯粹的算术：视口
 * 盒、1× 的落点、倍率、平移、两个尺寸（基础位图与源图）之间来回换算。这段算术如果长在 Compose
 * 文件里，本机就一行都跑不了（这台机器没有 android.jar、也没有 Compose 编译器插件）；搬到这里
 * 之后它可以被 `app/src/test/kotlin/app/pi/ui/blocks/PiZoomDecodeWindowCheck.kt` **真的执行**
 * —— 边界、极端宽高比、被夹过的平移、以及与 `decodePiImageBytes` 采样规则配合出来的内存上界，
 * 都是跑出来的，不是读出来的。
 *
 * 另一半是**平移那一层换算**（[panStepFromGesture] / [panLimit] / [panAfterDrag]）：用户报的
 * 「放大了之后左右上下滑动，滑的特别特别慢」的根因就在那里 —— 手势的位移与 `graphicsLayer` 的
 * translation 差一个倍率，差在哪、为什么差，写在那三个函数的 KDoc 里（它不是审美问题，是两个
 * 坐标系的问题，所以它必须在能被断言的地方）。落定闸 [settledProbes] 也在这里：拖动期间一次
 * 解码都不许发生，而「拖动中为关、静止后为开」这件事只有真的跑一遍协程才算验过。
 *
 * 本文件只 import `kotlin.math` 与 `kotlinx.coroutines` 的 flow/delay（前者是算术、后者是那道
 * 落定闸），Android 与 Compose 一行都没有 —— 和 [ImageSize] 的 `Semaphore` 同一个道理：它长出
 * Android 或 Compose 的 import，`tools/run-app-pure-checks.sh` 就会编译失败，这正是目的。
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
 *  1. **一块 ≤ [detailBudgetPixels]（= 视口面积，被 [DETAIL_ABSOLUTE_MAX_PIXELS] = 4 000 000 像素
 *     = 16 000 000 B 压住）**，与源图多大无关 —— 由 [zoomDecodeWindow] 的采样倍率循环强制，超预算
 *     就整倍降采样（[ZoomDecodeWindow.sample]），而不是让它涨上去。手机上这个预算就是视口面积
 *     （1080×2400 → 2.59 M 像素 = 10.4 MB），4 K 平板才会撞到那道 16 MB 的保险。
 *     旧值是写死的 2 000 000 像素：1080×2400 的屏幕比它大，于是「源和屏幕分辨率差不多」的图
 *     （例如 50 MP 放大到 1:1）会被预算先把块降一半采样 —— 屏幕上看到一块被拉大 2× 的图。
 *  2. **只在「比现在屏幕上那张更清楚」时才要块**：块的采样倍率必须**严格小于**基础图自己的
 *     抽稀倍数（[baseDecimation]），否则这块和屏幕上已经有的东西一样清楚，花掉一次解码换一张
 *     看不出区别的图 —— 那就不要。这条是 [zoomDecodeWindow] 返回 `null` 的唯一理由，也是
 *     「50 MP 图在小倍率下仍然糊」这句老实话的来源。**它同时取代了旧的那条 `scale × fit ≥ 2`
 *     阈值**：阈值在 1× 与低倍率下做的判断与它逐条一致（扫描断言钉着这件事），也就是那条阈值
 *     从来没在做决定。
 *  3. **1× 一个字节都不多要**：基础图一旦抽稀过，它的面积就大于视口，于是一整张源图按预算降采样
 *     之后恰好落到基础图那一档 —— `zoomDecodeWindow` 返回 `null`，1× 恒不取块。缩回 1× 时调用方
 *     当场丢掉上一块（见 `PiImageViewer`），1× 的内存与改动前逐字节相同。

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
 * 块在可见区之外多要的余量，按可见区的边长比例（0.25 = 每边 25%）。
 *
 * 平移时先用上一块顶着，走出这块余量才重新解 —— 余量太小会每拖一下就重解，太大则白占内存
 * （面积是 `(1+2m)² = 2.25` 倍）。但它只是**余量**：预算紧时它是第一个被收掉的东西（见
 * [zoomDecodeWindow]），因为用户抱怨的是清晰度，而余量换来的只是「平移中块外那一圈更软」。
 */
internal const val DETAIL_MARGIN_FRACTION = 0.25f

/**
 * 一块细节位图的**内存**上限（4 000 000 像素 = 16 000 000 B）。
 *
 * 它不是「块该多大」—— 块真正该多细由 [detailBudgetPixels] 从视口面积算出来。这道保险只对**大屏**
 * 生效，而 1440×2900 及以下的手机视口（≤4.2 M 像素）都在它以内，也就是说**手机上它从不生效**。
 * 它的用途是把 4K 平板（3840×2160 = 8.3 M 视口）的块压住：那块最多比屏幕粗 1.44×（肉眼接近
 * 1:1），而不是一次分配 33 MB。
 */
internal const val DETAIL_ABSOLUTE_MAX_PIXELS: Long = 4_000_000L

/**
 * 一块细节位图的像素预算：**视口面积**，被 [DETAIL_ABSOLUTE_MAX_PIXELS] 压住。
 *
 * 这个数不是调出来的，是从「块要干什么」推出来的：
 *
 *  - 块的职责是让**当前这一屏**看到它该有的细节。比源图分辨率更细是浪费（源里没有更多像素），
 *    比屏幕分辨率更粗则一眼可见（块被放大）。所以目标分辨率 = `min(源图分辨率, 屏幕分辨率)`。
 *  - 屏幕这一侧的上界就是**可见的那块屏幕面积**，它不超过视口面积 ⇒ 块的像素数 ≤ 视口面积。
 *  - 源图那一侧更细只发生在「源被放大」时，而此时可见的源区域本来就小于可见的屏幕面积 ——
 *    还是同一条上界。源被**缩小**时可见源区域比屏幕大，那正是不该按原生解的时候（按屏幕解就够）。
 *
 * 于是 `min(视口面积, 4 M)` 同时是「该多细」和「最多多少」。旧值是写死的 2 000 000 像素：
 * 1080×2400 的屏幕（2.59 M）比它大，于是在「源和屏幕分辨率差不多」的图上（例如 50 MP 的图放大
 * 到 1:1 时）预算先把块降了一半采样，屏幕于是看到一块被拉大 2× 的图 —— 那是用户这次抱怨的一部分。
 */
internal fun detailBudgetPixels(boxWidthPx: Int, boxHeightPx: Int): Long {
    if (boxWidthPx <= 0 || boxHeightPx <= 0) return 0L
    return min(DETAIL_ABSOLUTE_MAX_PIXELS, boxWidthPx.toLong() * boxHeightPx.toLong())
}

/**
 * 最大倍率相对「1 源像素 = 1 屏幕像素」那一点允许的余量（2×）。
 *
 * 1 个源像素被摊到 2 个屏幕像素（面积 4 倍）时，双线性插值还只是「软」，再往上就是「糊成一片」
 * —— 用户这次说的「放大怎么感觉不清晰」正是这一段。取 1 会让「双击放到最大」对一张和屏幕一样宽
 * 的截图变成**空操作**（它 1× 就已经是 1:1），那不是用户要的；取 2 是各家相册的常见手感，也是
 * 「双击一定看得出变化」的下限。
 */
internal const val SOURCE_ZOOM_HEADROOM = 2f

/**
 * 最大倍率的下限（也是「双击一定看得见效果」的保证）。
 *
 * `SOURCE_ZOOM_HEADROOM × 1:1 倍率` 对一张比屏幕还小的图会算出小于 1 的值（它 1× 时就已经在被
 * 放大），那时最大倍率取 2×，而不是「不能放大」。
 */
private const val MIN_DETAIL_MAX_SCALE = 2f

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
 * `ceil(矩形边长 / sample)`，面积 ≤ 调用方给的预算（见 [detailBudgetPixels]）。
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
 * 这张图**值得**放到的最大倍率：`min(硬上限, `[SOURCE_ZOOM_HEADROOM]` × 1:1 倍率)`，且不低于 2×。
 *
 * 1:1 倍率 = 源图宽度 ÷ 它在 1× 时的屏幕宽度（两个轴取小的那个）。倍率超过它以后，一个源像素被
 * 摊到不止一个屏幕像素 —— 源里就那么多信息，**任何解码都救不了**，用户看到的就是「放到 8× 但只有
 * 1/8 的真实像素」。所以上限按图算，而不是对所有图都写 8：
 *
 *  - 4000×3000 的照片投在 1080 宽的屏上 → 1:1 = 3.7× → 最大 7.4×（几乎还是 8×，看不出少了）；
 *  - pi 送出去的 2000 px 长边附件 → 1:1 = 1.85× → 最大 3.7×（原来放到 8× = 4.3 倍放大，纯糊）；
 *  - 和屏幕一样宽的截图（1080 px）→ 1:1 = 1.0× → 最大 2×（原来 8× = 8 倍放大，最糊的一类）；
 *  - 50 MP 的图 → 1:1 = 7.6× → 最大仍是硬上限 8×（它放大之后还有源像素可用）。
 *
 * 「够大吗」的判断：**手机照片这一档几乎没变**（7.4× vs 8×），被砍掉的只有那些本来就糊的档位 ——
 * 也就是用户抱怨的那些。要更保守或更大，只动 [SOURCE_ZOOM_HEADROOM] 一个数。
 *
 * @param hardCeiling 应用自己的绝对上限（`MAX_SCALE`）。源尺寸未知（`null`/非正）时返回它，
 *   也就是**退回今天的行为**。
 */
internal fun maxZoomForSource(
    fittedWidthPx: Float,
    fittedHeightPx: Float,
    sourceWidthPx: Int?,
    sourceHeightPx: Int?,
    hardCeiling: Float,
): Float {
    if (fittedWidthPx <= 0f || fittedHeightPx <= 0f) return hardCeiling
    if (sourceWidthPx == null || sourceHeightPx == null) return hardCeiling
    if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return hardCeiling
    if (hardCeiling < MIN_DETAIL_MAX_SCALE) return hardCeiling
    val oneToOne = min(sourceWidthPx / fittedWidthPx, sourceHeightPx / fittedHeightPx)
    if (!oneToOne.isFinite() || oneToOne <= 0f) return hardCeiling
    return (SOURCE_ZOOM_HEADROOM * oneToOne).coerceIn(MIN_DETAIL_MAX_SCALE, hardCeiling)
}

/**
 * 当前视口对应源图上的哪一个矩形，或者 `null`（只有**输入不成形**才会 null）。
 *
 * 它是一个**几何**函数：只回答「这一屏落在源图的哪里」，**不回答「该不该解」** —— 那件事由
 * [zoomDecodeWindow] 按预算与「有没有比基础图更清楚」决定。这样分开之后，原来那条
 * `scale × fit ≥ 2` 的阈值就没有立足点了：它在 1× 与低倍率下做的判断，与「按预算降采样之后还
 * 能不能比基础图更清楚」逐条一致（`PiZoomDecodeWindowCheck` 有一条扫描断言把这件事钉住），
 * 也就是说**它从来没在做决定**。
 *
 * 返回 `null` 的情况：非正的盒/尺寸，非有限或非正的倍率与平移 —— 都是退化输入，调用方此时不该
 * 有任何细节块（它们在 1× 与解码失败时本来也是「没有块」）。
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

    val fit = viewerFit(boxWidthPx, boxHeightPx, baseWidthPx, baseHeightPx)
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
 * 要解的那一块，或者 `null`（= **不该解**：解了也不会比屏幕上那张更清楚）。
 *
 * 判据只有两条，都不是魔数：
 *
 *  1. **内存**：块的像素数 ≤ [budgetPixels]（调用方给 [detailBudgetPixels]，也就是视口面积，
 *     被 [DETAIL_ABSOLUTE_MAX_PIXELS] 压住）。超了就整倍降采样，绝不让它涨上去。
 *  2. **有用**：块的采样倍率必须**严格小于**基础图的抽稀倍数（[baseDecimation]）。否则这块和屏幕
 *     上已经有的东西一样清楚 —— 花一次解码换一张看不出区别的图。**这一条同时取代了旧的那条
 *     `scale × fit ≥ 2` 阈值**：在 1× 与低倍率下，可见区（整个源图，而基础图一旦抽稀过它的面积
 *     就大于视口）按预算降采样之后恰好落到基础图那一档，于是这里返回 `null`。
 *
 * 两个候选按**清晰优先**取：
 *  - 带余量（[DETAIL_MARGIN_FRACTION]）的那块让平移时可以用上一块顶着，但它要多花 2.25 倍面积，
 *    预算紧时会把块从 1:1 挤成 1:2。用户这次抱怨的是清晰度，所以**只要收掉余量能换来更低的采样
 *    倍率就收**（代价：平移中块外的部分是基础图 —— 不白屏、不闪，只是那一圈更软，松手 100 ms 后
 *    补上）。余量免费时（常见情形）照留。
 *  - 已经 1:1（`sample == 1`）时不必再算第二个候选：没有更清楚的档位了。
 */
internal fun zoomDecodeWindow(
    visible: SourceRect,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    baseWidthPx: Int,
    baseHeightPx: Int,
    budgetPixels: Long,
): ZoomDecodeWindow? {
    val decimation = baseDecimation(sourceWidthPx, sourceHeightPx, baseWidthPx, baseHeightPx)
    val margined = buildZoomWindow(visible, sourceWidthPx, sourceHeightPx, DETAIL_MARGIN_FRACTION, budgetPixels)
    // 已经 1:1 就没有更清楚的档位：不必再算收掉余量的那个候选（余量因此免费保留）。
    val exact = if (margined.sample > 1) {
        buildZoomWindow(visible, sourceWidthPx, sourceHeightPx, 0f, budgetPixels)
    } else {
        margined
    }
    val best = if (exact.sample < margined.sample) exact else margined
    return if (best.sample < decimation) best else null
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
 * 块的采样倍率必须**严格小于**它，否则块和屏幕上那张一样清楚（见 [zoomDecodeWindow] 第 2 条）。
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
 * 由可见区算出矩形：外扩 [marginFraction]、夹进源图、按 [budgetPixels] 选采样倍率，并把
 * 矩形**对齐到采样倍率的整数倍**。
 *
 * 对齐是为了让结果尺寸没有歧义：`decodeRegion` 的输出是矩形边长除以采样倍率（向上取整），矩形
 * 自己就是 `sample` 的整数倍时，这个除法不会因为取整方向而在两个平台上差一个像素。
 *
 * 预算按**对齐之后**的矩形算（见下面的循环），所以出来那一块的解码面积一定 ≤
 * [budgetPixels]：对齐只会把矩形往外长，先定倍率再对齐就会让上界差几个像素。
 */
private fun buildZoomWindow(
    visible: SourceRect,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    marginFraction: Float,
    budgetPixels: Long,
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
        if (decodedWidth.toLong() * decodedHeight.toLong() <= budgetPixels ||
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

// ------------------------------------------------------------------ 平移 ----
//
// 「放大之后拖不动 / 滑得特别特别慢」的根因全在这一段。两个事实各自都对，凑在一起差一个倍率：

/**
 * 手势的**局部**位移 → `graphicsLayer` 的 translation 增量。
 *
 * ## 为什么不能直接 `pan += drag`（用户原话：「滑的特别特别慢」）
 *
 * 1. **`detectTransformGestures` 交上来的 `drag` 不是屏幕像素，是「本节点自己的坐标」**：指针事件
 *    在派发前会被换算进 pointer input 节点的坐标系 —— `HitPathTracker`（"translate their position
 *    relative to the parent coordinates, to give us a change local to the PointerInputFilter's
 *    coordinates"）走的是 `LayoutCoordinates.localPositionOf`，而 `NodeCoordinator.toParentPosition`
 *    / `fromParentPosition` 会过那一层的矩阵（`layer.mapOffset(..., inverse = …)`）。画布上的
 *    `pointerInput` 落在 `graphicsLayer` **里面**，那一层带着倍率，所以手指每走 `d` 个屏幕像素，
 *    交上来的 `drag` 只有 `d / scale`（8× 时就是八分之一）。
 * 2. **`translationX/Y` 在缩放之外**：hwui 把图层矩阵拼成 `T(translation) · R · S`
 *    （`RenderProperties.cpp` 的 `updateMatrix()`：`setTranslate` 之后 `preScale`，Skia 的 `pre*`
 *    是右乘、且「先作用于点」），所以 translation 的 1 个单位就是屏幕上的 1 个像素。
 *
 * 两条一对，`pan += drag` 时图只走了手指的 `1/scale`。换算就是把它乘回去：
 * **屏幕位移 = 局部位移 × 倍率**。
 *
 * @param gestureScale 这一帧**图层实际用的**倍率（也就是事件派发时的那一个值），不是本帧刚算出来
 *   的新倍率：换算的是「已经发生的那段位移」用的是旧矩阵。
 */
internal fun panStepFromGesture(drag: Float, gestureScale: Float): Float = drag * gestureScale

/**
 * 一个轴上平移的上界（**屏幕像素**，与 translation 同单位）：图比视口多出来的那一半，图不比视口
 * 大时是 0。
 *
 * 1× 时它恒为 0：`viewerFit` 是 `min(box/bitmap)`，所以 `fitted = bitmap·fit ≤ box` 恒成立 ——
 * 「缩回 1× 平移清零」因此不需要单独一条规则（`PiImageViewer` 那条 `if` 只是把这条不变量写成
 * 明文）。
 */
internal fun panLimit(scale: Float, fitted: Float, box: Float): Float =
    max(0f, (scale * fitted - box) / 2f)

/**
 * 一帧拖动之后，一个轴上的平移该落在哪里：先把手势位移换算成屏幕位移（[panStepFromGesture]），
 * 再夹进 [maxPan]。
 *
 * 夹取**只在越界那一下**改数值：范围内的位移原样通过（逐像素跟手），越界时钉在边界（不回弹、不做
 * 动画 —— 平移这条路一次动画都没有）。换算与夹取必须在同一处做，否则两者会各自按不同的倍数算：
 * 「图比视口多出多少」是屏幕像素，而手势位移是局部像素。
 */
internal fun panAfterDrag(pan: Float, gestureDrag: Float, gestureScale: Float, maxPan: Float): Float =
    (pan + panStepFromGesture(gestureDrag, gestureScale)).coerceIn(-maxPan, maxPan)

/**
 * 落定闸：把「每一帧都在变的探针」变成「安静了 [settleMillis] 才发一次」的落定值。
 *
 * 拖动与捏合的每一个事件都会改 `scale`/`pan`，照当前值触发解码就会一次拖动连开好几次区域解码
 * （用户明确说过「不用太费性能」）。`transformLatest` 的语义正好是这件事：新的一帧**取消**上一次
 * 的 `delay`，所以只有真的停下来才轮到下游 —— **解码永远不在这条手势路径上**。
 *
 * `null` 是「这一帧不算数」（双击动画正在跑，见 `PiImageViewer` 的 `snapshotFlow`）：直接丢掉，
 * 连延时都不排。
 *
 * 抽成 Flow 算子不是为了好看，是为了**可验**：写在 Composable 里的 `delay` 只能靠读码相信，而
 * `PiZoomDecodeWindowCheck` 可以拿真的协程喂一串拖动帧，断言「拖动期间一次都不落定、松手之后恰好
 * 落定到最后那一帧、动画帧不算落定」。
 *
 * 为什么 opt-in `ExperimentalCoroutinesApi`：这个「取消上一次等待、只留最后一次」的语义在
 * `transformLatest` 上是**稳定**的实现，而更眼熟的那个 `debounce` 本身带 `@FlowPreview`（预览级）
 * —— 一个进程级的滑动体验不该建在预览 API 上。这里显式 opt-in 一次，而不是靠编译器的警告放过去。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ZoomProbe?>.settledProbes(settleMillis: Long): Flow<ZoomProbe> =
    transformLatest { probe ->
        if (probe == null) return@transformLatest
        delay(settleMillis)
        emit(probe)
    }
