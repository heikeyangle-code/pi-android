package app.pi.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import app.pi.ui.blocks.piImageDecodeGate
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 渲染器的图片接缝：把一个 markdown link 换成真正的像素。
 *
 * ## 已经接上了
 *
 * `ui/render/PiMarkdown.kt` 调 [rememberPiGuestImageTransformer]，同一个实例同时装进
 * 两处：本 App 自己的 `LocalPiImageTransformer`（`PiImagePlaceholder` 读它来决定
 * 「有没有得画」），以及库的 `Markdown(imageTransformer = …)`（库自己的 local，
 * `MarkdownImage` / `MarkdownInlineImage` 只读它，
 * `compose/elements/MarkdownImage.kt:17`、`.../MarkdownInlineImage.kt:12`，
 * 0.45.0 `core` artifact 源码已核对）。两处是同一个实例，所以「判断」和「绘制」
 * 不可能对不上。
 *
 * 这条 KDoc 以前以 "## NOT WIRED YET — nothing calls [rememberPiGuestImageTransformer]"
 * 开头，还写着「`ui/render` 仍然提供 `NoOpImageTransformerImpl`，而且是在
 * `PiMarkdownText` 内部提供的，所以外层的 provider 赢不了」。那是接线之前的旧话，
 * 已经不成立，删掉。
 *
 * 库对 [ImageTransformer] 的用法是每个 link 要一个 `Painter`
 * （`model/ImageTransformer.kt:16-21`）。默认实现 `NoOpImageTransformerImpl.transform`
 * 返回 `null`（`model/NoOpImageTransformerImpl.kt:12-18`），
 * `MarkdownImage` 于是什么都不画（`transform(link)?.let { … }`）。本类用一个由
 * [GuestImageBytes] 取字节、`BitmapFactory` 解码的 [BitmapPainter] 作答，库的两条图片
 * 路径就都活了：块级图片走 `ui/render/PiMarkdownComponents.kt` 的 `PiImagePlaceholder`，
 * 段落内的图片走 `inlineImage` 槽的 `PiInlineImage`。
 *
 * ## 按视口采样，不再是全尺寸解码
 *
 * 这里是本轮修的坑：原来是裸的 `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`
 * 全尺寸解码，一张 12 MP 手机照片（4000×3000，ARGB_8888）就是 ~48 MB 常驻，
 * 只为了在正文里画约一个屏幕宽的图。现在按**将要画出来的那个框**解码，两趟：
 * 第一趟 `inJustDecodeBounds` 只读图片头拿到它自己的宽高，第二趟按 `inSampleSize`
 * 解码。采样规则与聊天里那条路径逐条相同（`ui/blocks/ImageGridBlock.kt` 的
 * `decodePiImageBytes`：同样的「两轴都还盖得住就减半」+ [DECODE_AREA_BUDGET_FACTOR]
 * 倍的面积兜底）——两条通道画的是同一种东西，没有理由对「多大算够」有两种说法。
 * 两份实现而不是调用同一个函数，只因为那一份是 `private`、而且在另一个改动的文件里；
 * 把它们合并成一个函数是那个文件所有者的事。
 *
 * 「将要画出来的那个框」= 可用宽度 × 由图片自身比例算出的高度。因为下面返回的
 * [ImageData] 用 [ContentScale.Fit] + `fillMaxWidth`：宽由排版钉死，高完全由比例决定
 * （`PainterNode.modifyConstraints`，compose-ui 1.8.3
 * `commonMain/androidx/compose/ui/draw/PainterModifier.kt:264-296`：只有宽被钉死、
 * 高无上限时，它按 `ContentScale` 算出 `minHeight`，于是框的宽高比与图一致）。
 * 所以采样用的框与真正落屏的框是同一个：既不会欠采样（糊），也不会按原始像素铺出去。
 *
 * **这里没有转写单图单元格那样的 0.35 屏高上限**（`ui/blocks/ImageSize.kt` 的
 * `SINGLE_IMAGE_MAX_HEIGHT_FRACTION`），这是本类与那个单元格唯一的差别。理由：
 * 那个单元格把长截图压到 0.35 屏高，因为点一下有全屏查看器兜底；本轮正文图片
 * **还没有**查看器（查看器是另一个改动的地盘），给一个够不着的上限等于把内容藏起来。
 * 所以正文图片按比例完整画出来，长的图就长。这句话在这里只说一次：它同时决定了
 * 采样框，改这个规则必须连着改解码。
 *
 * ## 线程
 *
 * `ImageTransformer.transform` 是 `@Composable`，读字节不能发生在它里面。用
 * `produceState`：取字节在 [Dispatchers.IO]（包括 `http(s)` 的网络，见
 * [GuestImageBytes]），解码在 [Dispatchers.Default]（CPU 活，而且一张原图几十毫秒）。
 * 所以图片的第一帧是「没有」——正文画的是 alt + 来源那条回退——下一帧才换成图。
 *
 * ## 取消：谁取消谁
 *
 * 真正取字节的那一次跑在 [PiImageRequestPolicy] 的**进程级**作用域里，所以它不再是
 * `produceState` 的取消能停下来的东西：节点离开组合它照样跑完，把字节写进磁盘缓存，用户
 * 滚回来时是**缓存命中、不出网**。这条是有意的（原来那句"节点离开组合下载当场停"已撤销，
 * 理由见 [PiImageRequestPolicy] 的 KDoc）：`LazyColumn` 回收行会让 `produceState` 重跑，
 * 而"失败不被记住、并发不去重"就意味着**一张坏 URL 每滚回来一次就重新出一网**。
 *
 * `produceState` 保留的：等待方取消。等待方等的是共享的那份结果，取消它自己不会取消
 * 生产者（否则滚快一点就把别人正在下的图掐了）。
 *
 * ## 失败一律 `null`，绝不画错的图
 *
 * 解不出来的（文件不存在、响应不是图片、超过 [GuestImageBytes] 的单张上限、网络失败、
 * 超时……）一律返回 `null`——这正是默认 no-op 实现的答案，于是正文保留
 * 「alt + 图片地址」那条回退（`ui/render/PiMarkdownComponents.kt` 的 `PiImageFallback`），
 * 原因留在 `GuestImageBytes.lastFailure`。**失败不进位图缓存**，但也不再"每次组合都重试"：
 * 失败由 [PiImageRequestPolicy] 在内存里记 45 s，见下面 `cached` 的说明。
 */
internal class PiGuestImageTransformer(private val context: Context) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        // 目标框的宽。库的 `transform(link)` 只给一个 link，没有尺寸参数
        // （`model/ImageTransformer.kt:16-21`），而「画多宽」只有排版知道，所以从
        // `LocalWindowInfo` 读一次组合容器的像素尺寸。正文一定比容器窄，所以这是个
        // **上界**：只会多解一点，绝不会解得比画出来的还小。`containerSize` 是
        // `MutableState`（compose-ui 1.8.3 `AndroidWindowInfo.android.kt:66-76`），
        // 尺寸变了会重组，而缓存键里带着它，所以旋转后不会拿旧尺寸的位图去画。
        val widthPx = LocalWindowInfo.current.containerSize.width
        // `initialValue` 是一次内存缓存读（不做 IO），所以滚出去再滚回来，第一帧就有图。
        val bitmap by produceState<Bitmap?>(initialValue = cached(link, widthPx), link, widthPx) {
            if (value == null) value = load(link, widthPx)
        }
        val image = bitmap ?: return null
        return ImageData(
            painter = BitmapPainter(image.asImageBitmap()),
            // 按可用宽度铺开，比例交给 `ContentScale.Fit`。不加这一条的话，库拿
            // `imageData.modifier`（默认是空 `Modifier`）直接量这张位图的本征尺寸
            // (`MarkdownImage.kt:20-29`)，一张 4000 px 宽的图就要一个 4000 px 的框：
            // 宽被父约束夹住、高不受限，`Fit` 把图缩在中间，上下留一大片空白。
            modifier = fillWidthModifier,
            contentScale = ContentScale.Fit,
            // `MarkdownImage` 优先用 alt 当无障碍名，这里只是没有 alt 时的兜底。
            contentDescription = link,
        )
    }

    /**
     * 取一张图：先过 [PiImageRequestPolicy]（正在飞的共享、失败 45 s 内不出网），再真正取字节
     * 与解码。
     *
     * 键与位图缓存**同一个类型**（[PiImageRequestKey] = `(link, 目标宽度)`）：正在飞的那一份
     * 和已经画出来那一份指着同一张图。宽度在键里是因为位图按它采样（旋转/分屏必须重解）。
     *
     * 这里是 `produceState` 的等待方：它被取消只取消自己这一次等待，不会取消生产者 ——
     * 否则滚快一点就把别人正在下的图掐了（见类注释「取消：谁取消谁」）。
     */
    private suspend fun load(link: String, widthPx: Int): Bitmap? {
        val key = PiImageRequestKey(link, widthPx)
        val outcome = requestPolicy.load(key) { requested -> loadUnshared(requested) }
        val bitmap = outcome.getOrNull()
        if (bitmap == null) {
            // 负缓存命中 / 生产者超预算时这次**根本没走到取字节**（或者取消了它），
            // `GuestImageBytes.lastFailure` 里可能还留着别处的旧原因。把这条真实结论写回去，
            // 诊断（以及回退文案的依据）才不撒谎。
            when (val failure = outcome.exceptionOrNull()) {
                is PiImageNegativeCacheHit -> GuestImageBytes.noteFailure(
                    failure.message ?: "远端图片最近失败过，这次不出网",
                )

                is PiImageProducerTimeout -> GuestImageBytes.noteFailure(
                    failure.message ?: "图片加载超过兜底预算，这次没画出来",
                )

                // 其它失败就是取字节/解码那一步写下的原因，`lastFailure` 已经是对的。
                else -> Unit
            }
        }
        return bitmap
    }

    /**
     * 真正取一次：取字节 → 解码 → **成功**才进位图缓存。
     *
     * 这个函数只被 [PiImageRequestPolicy] 的生产者调用一次（同一个 key 上并发的其它调用者
     * 共享它的结果），所以磁盘写入、解码、缓存写入都只发生一次。
     */
    private suspend fun loadUnshared(key: PiImageRequestKey): Result<Bitmap> {
        // 本地那两条路（`data:`、文件）是普通的阻塞读，所以整段仍然要在 IO 上；远端那条
        // 自己也会切一次，这里不重复算账。
        val loaded = withContext(Dispatchers.IO) { GuestImageBytes.load(context, key.link) }
            ?: return Result.failure(
                IllegalStateException(GuestImageBytes.lastFailure ?: "读取图片字节失败（没有留下原因）"),
            )
        val decoded = withContext(Dispatchers.Default) {
            // 解码过的是**另一把**闸门：`piImageDecodeGate` 的两个许可就是「进程里同时在解的
            // 图」的上界（`ui/blocks/ImageSize.kt`，`MAX_CONCURRENT_IMAGE_DECODES = 2`）。
            // 正文图片和聊天里的图片抢的是同一份帧时间与同一份内存，分成两把闸门等于把这个
            // 上界悄悄翻倍。它与 `GuestImageBytes` 那把"同时在开的连接"（3 许可）是两回事。
            piImageDecodeGate.withPermit { decode(loaded.bytes, key.widthPx) }
        } ?: return Result.failure(IllegalStateException("图片解码失败：${loaded.source}"))
        store(key, decoded)
        return Result.success(decoded)
    }

    /**
     * 采样解码：先只读图片头（`inJustDecodeBounds`，不分配像素）拿到它自己的宽高，
     * 再按算出的 `inSampleSize` 解一次。
     *
     * 规则与 `ui/blocks/ImageGridBlock.kt` 的 `decodePiImageBytes` 相同，包括那条面积
     * 兜底：`inSampleSize` 只能整倍数缩小，对「比例与框完全不像」的图（1:5 的长截图）
     * 两轴规则可能一个都不减，所以再按 [DECODE_AREA_BUDGET_FACTOR] 倍的框面积兜一次。
     *
     * 头读不出来（不是 pi 认的五种格式、或内容被截断）返回 `null`：**不猜一个尺寸**，
     * 猜出来的框会画出一张比例错的图。容器宽还没有时（预览、测试）退回全尺寸解码，
     * 也就是加采样之前的行为。
     */
    private fun decode(bytes: ByteArray, availableWidthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        if (availableWidthPx <= 0) return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val boxWidth = availableWidthPx
        val boxHeight = (boxWidth.toLong() * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
        var width = bounds.outWidth
        var height = bounds.outHeight
        var sampleSize = 1
        while (width / 2 >= boxWidth && height / 2 >= boxHeight) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        val budget = boxWidth.toLong() * boxHeight.toLong() * DECODE_AREA_BUDGET_FACTOR
        while (width >= 2 && height >= 2 && width.toLong() * height > budget) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull()

    private companion object {

        /**
         * 按可用宽度铺开。`ImageData.modifier` 是数据类的一个字段，所以要的是一个常量，
         * 而不是每次 `transform` 都新造一个 `Modifier`。
         */
        private val fillWidthModifier = Modifier.fillMaxWidth()

        /**
         * 正文图片缓存的两条上限：条数，以及**解码后**的总字节数。
         *
         * 单位是字节，[MAX_TOTAL_BYTES] = 32 MiB（`32 * 1024 * 1024`）。
         *
         * 两条都要，因为一条不够：转写里可能有几十张图，每张都是一个位图，只限条数的话
         * 24 张全尺寸图就是几百 MB，而这个进程里还跑着引擎；只限字节数的话，一堆小图
         * 会攒出几千项，让缓存读和下面那段线性淘汰都变慢。
         *
         * 采样之后单张一般降到几 MB（4000×3000 的照片解到 2000×1500，12 MB），
         * 所以 32 MiB 装得下几张。上限本身没有跟着采样一起降：它是「图片最多能占多少
         * 内存」这条规则，`ui/blocks/PiImageCache.kt` 的 `MAX_TOTAL_BYTES` 也是同一个数，
         * 两个位图缓存不许对同一件事有两种说法。
         */
        private const val MAX_ENTRIES = 24
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

        /**
         * 面积预算：解出来的位图面积最多是目标框的几倍。4 与
         * `ui/blocks/ImageGridBlock.kt` 的 `DECODE_AREA_BUDGET_FACTOR` 同值，理由也一样：
         * 两轴减半本来就允许到 4 倍，所以 4 只会卡住比例离谱的那种图。
         */
        private const val DECODE_AREA_BUDGET_FACTOR = 4L

        /**
         * **进程级**的请求策略：正在飞的请求按 `(link, 目标宽度)` 去重、失败 45 s 内不再出网。
         *
         * 放在 companion 里而不是实例字段里，是因为它**必须**活得比任何一次组合长：
         * `rememberPiGuestImageTransformer()` 每次 Activity 重建都可能给一个新实例，而
         * "同一张图别并发取两次"这件事跨实例才成立（行内图在同一帧被库调两次、两个页面
         * 同时显示同一张图，都是这个形状）。把实例状态放在这里也是同一个理由 —— 位图缓存
         * 本来就是进程级的。
         *
         * 默认参数就是生产值（45 s 负缓存 / 60 s 兜底预算 / 64 条负缓存 / `Dispatchers.IO`），
         * 全部理由在 [PiImageRequestPolicy] 的 KDoc 里。
         */
        private val requestPolicy = PiImageRequestPolicy<PiImageRequestKey, Bitmap>()

        /**
         * 访问序：再读一张旧图会把它挪到最新端，于是下面的淘汰总是丢最久没用的那张。
         *
         * 键就是 [PiImageRequestKey]，与 [requestPolicy] 的键是**同一个类型**：正在飞的那份
         * 与画出来那份必须指着同一张图。
         */
        private val cache = LinkedHashMap<PiImageRequestKey, Bitmap>(16, 0.75f, true)

        /**
         * **失败不进位图缓存。** 文件可能就在这一轮才被写出来（agent 正在同一个回合里写它），
         * 不可达的 URL 也可能回来 —— 位图缓存没有 TTL，"记住一次失败"在这里就是永久空白。
         *
         * 但失败也不再是"每次组合都重试"：那是 [requestPolicy] 的活，它记 45 s（有 TTL、
         * 到点照旧重试）。所以这里是两件事分开：位图缓存只放成功的像素，失败的记忆归策略层。
         */
        private fun cached(link: String, widthPx: Int): Bitmap? =
            synchronized(cache) { cache[PiImageRequestKey(link, widthPx)] }

        /**
         * 两条上限都在这里执行，而不是靠重写 `removeEldestEntry`：淘汰规则因此只有一个
         * 可读的循环——丢最久没用的那项，直到**两条**上限都满足。位图的字节数取
         * `allocationByteCount` 而不是 `byteCount`：后者是按当前宽高算的理论值，
         * 前者才是这块内存真正占了多少。
         *
         * 只在成功之后调用（见 [loadUnshared]）：策略层共享的是"这一次取字节+解码"的结果，
         * 位图缓存仍然是唯一存像素的地方，不新增第二份。
         */
        private fun store(key: PiImageRequestKey, bitmap: Bitmap) {
            synchronized(cache) {
                cache[key] = bitmap
                var total = cache.values.sumOf { it.allocationByteCount.toLong() }
                val iterator = cache.entries.iterator()
                while ((cache.size > MAX_ENTRIES || total > MAX_TOTAL_BYTES) && iterator.hasNext()) {
                    val entry = iterator.next()
                    total -= entry.value.allocationByteCount.toLong()
                    iterator.remove()
                }
            }
        }
    }
}

/**
 * `ui/render` 装进去的那一个，一行而不是一次构造调用：
 *
 *     LocalPiImageTransformer provides rememberPiGuestImageTransformer()
 *
 * Context 用的是 **application** 的那个：这个组合 local 活得比 Activity 重建长
 * （位图缓存是进程级的），拿 Activity 只会白泄漏它。
 */
@Composable
internal fun rememberPiGuestImageTransformer(): ImageTransformer {
    val context = LocalContext.current.applicationContext
    return remember(context) { PiGuestImageTransformer(context) }
}
