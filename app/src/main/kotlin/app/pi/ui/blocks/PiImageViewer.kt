package app.pi.ui.blocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.rpc.PiImage
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 查看器要画的那一张图：两种来源，**一块画布**。
 *
 * ## 为什么需要一个来源类型
 *
 * 这张图以前只可能来自聊天 wire —— `PiImage` 的 inline base64 是 App 唯一的来源。工作区
 * 的文件查看器也要把图片交给同一个查看器，而它手里是磁盘上的一个文件（`<rootfs>` 里的
 * `screenshot.png`，没有 base64 这一段）。来源的差别如果留在调用点上，解码就会长成第二条路：
 * 第二把闸门、第二种采样、第二份缓存 —— 同一张图在聊天里能画、在工作区里画不出来就不再是
 * 不可能的事。所以差别收在这里，画布只认它。
 *
 * [cacheKey] 是"这批字节"的身份：[Wire] 就是它的 base64，[HostFile] 是路径 + 字节数 + mtime
 * （改过的文件是另一个键，不会被当成同一张画出来）。`file:` 前缀不可能是 base64（`:` 不在
 * 那个字母表里），两类键不会撞。
 */
internal sealed interface PiImageViewerSource {

    /** 同一个调用点该不该重新解码、缓存里是不是同一张图，都看它。 */
    val cacheKey: String

    /** 解码失败那句提示里说的类型；写进 Download 时也是它。 */
    val mimeType: String

    /** 聊天 wire：字节在 inline base64 里。 */
    data class Wire(val base64: String, override val mimeType: String) : PiImageViewerSource {
        override val cacheKey: String get() = base64
    }

    /** 宿主文件：工作区（guest `/` 那一棵）里的图片。 */
    data class HostFile(val file: File) : PiImageViewerSource {
        // 类型按后缀问平台那张表（`DeviceSystemActions.mimeTypeForFileName`，与"用其他应用
        // 打开"同一个判据）；答不出来时是 `*/*`，那句"无法解码"会照实说出来。
        override val mimeType: String get() = DeviceSystemActions.mimeTypeForFileName(file.name)

        /**
         * 键在**构造时**算好，不是每次读的时候算。
         *
         * 这里原来是一个 getter，而画布一次组合要读它六处（`produceState` 的键、`remember` 的键、
         * 两个 `pointerInput` 的键、保存路径……）：每读一次两次 `File` 系统调用（`length()` 与
         * `lastModified()`），在 `/sdcard` 那种 FUSE 上能到毫秒级。捏合时这份成本曾经是**每帧
         * 十几遍**（画布当时每帧重组合），也就是用户直接感觉到的那一档"拖动跟不跟手"。键是一个
         * 物体的身份，它不该在每次被问的时候重新去问一遍文件系统。
         *
         * `size:mtime` 的语义一字不变：文件被改过 → 长度或时间戳变了 → 另一个键 → 重新解码，
         * 旧的那份不会被当成同一张画出来。代价是键只反映**构造这一刻**的文件状态；调用点每次
         * 重新构造它，所以"改过的文件"在下一次组合就看到新键。
         */
        override val cacheKey: String =
            "file:${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
}

/**
 * The full-screen viewer for one transcript image: pi's answer to "open the
 * picture", and the surface `docs/rendering-review.md`'s F19 recorded as missing.
 *
 * 这是一个**窗口**，所以它只是聊天那一侧的入口：真正的画布是 [PiImageViewerSurface]，
 * 工作区的文件查看器把同一块画布画在自己的正文里（它已经在一个全屏 `Dialog` 里，再套一层
 * 窗口只为一张图没有意义）。两个宿主共用一次解码、一把闸门、一套手势 —— 这也是"同一个组件"
 * 的判据。
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
 * @param image the wire value: an attachment, a screenshot a tool returned, a `read`
 *   of a picture.
 * @param onDismiss closes the viewer. The caller clears its own "which image" state
 *   here, so no other code path may dismiss without calling it.
 * @param onMessage 「保存到下载目录」的结果（成功含文件名，失败含原因）—— 这一屏自己不画
 *   snackbar，和全应用一样由 `PiRoot` 的宿主显示。
 */
@Composable
fun PiImageViewer(
    image: PiImage,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
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
        PiImageViewerSurface(
            image = PiImageViewerSource.Wire(image.base64, image.mimeType),
            onDismiss = onDismiss,
            onMessage = onMessage,
        )
    }
}

/**
 * 一块画布：一张图片 + 手势 + 右上角两颗按钮（保存到下载目录 / 关闭）。
 *
 * ## 子节点的顺序就是语义本身
 *
 * 这个 `BoxWithConstraints` 里的三个子节点是**按 z 序**写的：谁在后面谁在上面，而 Compose 的
 * 命中测试从最上面的兄弟开始、命中即止 —— 也就是说**谁在上面谁收指针**。
 *
 * 顺序以前是「图片 → 右上角按钮 → 点外面关闭的背景层」，背景层是最后一个 = 最上面。于是
 * 图片上的每一次按下（包括两根手指）都被那一层的 `clickable` 吃掉：捏合、拖动、双击一个都
 * 到不了图片，查看器在真机上只剩"点哪都关闭"，同时和这个文件自己的 KDoc（"点图片什么都不做、
 * 点图片周围关闭"）矛盾 —— 点图片也会关。现在背景层是**第一个**子节点，图片与按钮在它上面。
 *
 * 三条后果必须同时成立，而且各自都是这一层顺序的直接结果：
 *
 *  - **点图片**：被图片自己的 `detectTapGestures` 消费，什么都不做，也传不到背景层；
 *  - **点图片周围**（letterbox 那圈空白）：那里没有别的指针节点，事件落到背景层 → 关闭；
 *  - **捏合 / 拖动 / 双击**：图片上的 `detectTransformGestures` 现在真的收得到，
 *    `graphicsLayer` 上的 `scale`/`pan` 因此生效。
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
 * ## 解码：1× 按视口采样，放大之后按源图坐标重解可见的那一块
 *
 * 1× 那张位图仍然是**一次**视口采样解码（[decodePiImage] / [decodePiImageFile]，目标框是整个
 * 窗口），在 `Dispatchers.IO` 上、走 [piImageDecodeGate] 同一把闸门 —— 查看器是从滚动里点开的，
 * 它那次全屏解码不该变成又一个和还在填自己单元格的列表抢帧的分配。它缓存在 [PiImageCache] 里
 * （键含目标框），所以关掉再打开、旋转、以及双击缩回 1× 都是命中，不重解。
 *
 * 放大之后**就是另一回事**：那张位图是按视口采样的，`inSampleSize` 丢掉的细节再也回不来，把
 * 2000 px 的位图放到 8× 只是把小图拉大 —— 用户的原话是「放大了肯定要看到更多细节呀，不然放大
 * 还有啥用？」。所以倍率越过阈值之后，这里按**源图坐标**只解当前可见的那一块
 * （`BitmapRegionDecoder`，见 [decodePiImageRegion]），并把它画在图片节点**里面**：那一层
 * `graphicsLayer` 会连它一起缩放平移，所以块跟着图一起动，拖动时不会跟丢。
 *
 * 这笔账的每一个数字都在 [PiZoomDecodeWindow] 的文件头里，算术也都在那个文件里（本机能跑）：
 *
 *  - 一块 ≤ [DETAIL_MAX_PIXELS] 像素 = **8 000 000 B**，**与源图多大无关**。12 MP 整张解出来是
 *    ≈48 MB、50 MP ≈192 MB（`ARGB_8888`），区域解码是唯一不付这笔账的读法；超过预算就整倍降
 *    采样，而不是让它涨上去。
 *  - **1× 一个字节都不多要**：阈值是「基础图一个像素摊到 2 个屏幕像素以上」，而基础图一旦被抽稀
 *    过 `fit` 就 ≤ 1，所以 1× 恒不取块；缩回 1× 时上一块**当场丢掉**，1× 的内存与改动前一样。
 *  - **只在真的更清楚时才解**：块的采样倍率必须严格小于基础图自己的抽稀倍数，否则这块和屏幕上
 *    已经有的东西一样清楚 —— 没有这条，4× 会为一张和基础图一样糊的块白花 8 MB。
 *  - **整张图永远不为了放大而重解**：双击 8× ⇄ 1× 反复时，1× 是缓存命中，8× 是一次 ≤8 MB 的
 *    区域解码（异步、期间一直画着 1× 那张顶着），不是「整张重解」。
 *  - 块**不进** [PiImageCache]：它是「当前这一屏」的东西，随平移换掉，而且有一个恒定上界；塞进
 *    那个按 payload 计费的缓存只会为了几 MB 的块多留一份几 MB 的 base64 字符串。
 *
 * 诚实的残留模糊（不装作用户看不出来）：源图**不比**基础图多像素时（例如一张 1080 px 宽的截图
 * 放大到 8×）本来就没有更多细节，屏幕上仍然是被拉大的采样位图；50 MP 那种图在小倍率下预算只买
 * 得到一块不比基础图更清楚的块，于是干脆不解（[zoomDecodeWindow] 第 3 步），也是糊的。
 *
 * 编码字节超过 [MAX_INLINE_IMAGE_BYTES]（8 MiB，App 里那条图片通道唯一的尺子）的图在这里不画：
 * 这条路上最贵的错法是一个几百 MB 的文件名以 `.png` 结尾，`readBytes()` 会照单全收。它走的是
 * 既有的「无法解码」文案，与平台不认这些字节时一模一样。
 *
 * ## Gestures
 *
 *  - **pinch** → scale, **drag while scaled** → pan (`detectTransformGestures`);
 *  - **double tap** → 1× ⇄ [MAX_SCALE]（到顶），200 ms 的缓动过渡、倍率与平移同时到达，结束
 *    时平移清零；
 *  - **tap on the picture** → nothing (it consumes the tap, so it cannot fall
 *    through to the backdrop);
 *  - **tap on the ground around the picture** → dismiss; the picture is laid out at
 *    its fitted size rather than filling the box precisely so that this
 *    distinction is real;
 *  - **⤓** (top-right) → 保存到 Download; **✕** → dismiss; **back** → dismiss, via
 *    the dialog when there is one.
 */
@Composable
internal fun PiImageViewerSurface(
    image: PiImageViewerSource,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val palette = PiTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 一次保存还没落地时不再接第二次点按：`export` 是同步写盘，连点两下会写两份同名文件
    // （`safeName` 不含序号，第二份是"（1）"还是覆盖由系统说了算）。
    var saving by remember(image.cacheKey) { mutableStateOf(false) }

    fun saveToDownloads() {
        if (saving) return
        saving = true
        scope.launch {
            val sentence = withContext(Dispatchers.IO) { saveImage(context, image) }
            saving = false
            onMessage(sentence)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.pageBg),
    ) {
        val density = LocalDensity.current
        val boxWidthPx = constraints.maxWidth
        val boxHeightPx = constraints.maxHeight

        val bitmap by produceState<Bitmap?>(null, image.cacheKey, boxWidthPx, boxHeightPx) {
            value = withContext(Dispatchers.IO) {
                // Through the same gate the transcript's cells use: the viewer is opened
                // *from* a scroll (the user taps a picture the list may still be
                // decoding), so this decode queues behind those instead of becoming one
                // more full-resolution allocation competing with the frame. The box
                // stays the whole window: at 1× this bitmap *is* the picture, and the
                // zoom path below re-decodes from the source rather than from it.
                //
                // And through [PiImageCache], keyed on the same "these exact bytes"
                // identity: closing and reopening the viewer (or a rotation, or a double
                // tap back to 1×) used to decode the whole picture again. The viewer's
                // entry is deliberately separate from a cell's — a different box for a
                // different surface (the gate's KDoc, the class KDoc above, and
                // `decodePiImage`'s sampling rule all say why). Neither source reads the
                // file or the payload twice either: that is the one call below, and it is
                // the only *whole-picture* decode site.
                PiImageCache.readThrough(image.cacheKey, boxWidthPx, boxHeightPx) {
                    underImageDecodeGate { decodePiImageSource(image, boxWidthPx, boxHeightPx) }
                }
            }
        }

        // 源图自己的像素尺寸：放大后那一块要按源图坐标算，所以需要它。只读头部（四个定长前言的
        // 格式 32 字节，JPEG 最多 64 KiB），在 IO 上做 —— 宿主文件那一支是一次真实的文件读，不能
        // 放在主线程。读不出来（不是这五种格式、文件打不开）就是 null：这一屏退回上面那张采样
        // 位图，一个字节都不多要。
        val sourcePixels by produceState<ImagePixels?>(null, image.cacheKey) {
            value = withContext(Dispatchers.IO) { readSourcePixels(image) }
        }

        // ① 背景：点它 = 关闭。**第一个子节点 = 最下面**（见上面那段 z 序的理由）。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )

        // ② 图片本身。
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
            // and give the picture node exactly that size. Two consequences: the
            // letterboxed ground stays the backdrop's (so a tap there dismisses,
            // as asked), and the pan clamp below has real bounds to work with.
            // [viewerFit] is the same rule the zoom arithmetic uses, so the 1×
            // placement and "what is on screen at `scale`" can never disagree.
            val fit = viewerFit(boxWidthPx, boxHeightPx, decoded.width, decoded.height)
            val fittedWidth = decoded.width * fit
            val fittedHeight = decoded.height * fit

            // 缩放与平移是 `Animatable`，不是普通 state：双击要有过渡（[ZOOM_ANIMATION_MILLIS]、
            // ease 收尾），而捏合/拖动必须**跟手**（`snapTo`，手指走多远图就走多远）。两者写同一
            // 个 Animatable，谁在写由它自己的互斥决定 —— 手指一动就取消正在跑的双击动画，这正是
            // 想要的。不用 `animateFloatAsState`：那个会让捏合与动画互相打架。
            val scale = remember(image.cacheKey) { Animatable(MIN_SCALE) }
            val panX = remember(image.cacheKey) { Animatable(0f) }
            val panY = remember(image.cacheKey) { Animatable(0f) }
            // 双击的**目标**倍率：连着双击两次往哪走由它定，而不是动画途中的当前值（4.2 这种中间
            // 值既不是 1 也不是上限，拿它判方向会让第二次双击看起来是随机的）。
            var zoomIntent by remember(image.cacheKey) { mutableStateOf(MIN_SCALE) }
            // 双击动画正在跑？—— 它同时是解码触发的**开关**（见下面那段）。
            var animating by remember(image.cacheKey) { mutableStateOf(false) }
            // 双击动画的代次，见 `onDoubleTap` 里的用法。
            var animationEpoch by remember(image.cacheKey) { mutableStateOf(0) }
            // 放大后那一块细节。null = 现在不画细节：1×、源图没有更多像素、或者预算只买得到一块
            // 不比基础图更清楚的块（[zoomDecodeWindow]）。
            var block by remember(image.cacheKey) { mutableStateOf<DetailBlock?>(null) }

            val source = sourcePixels
            // 重解只认**落定的**倍率与平移，不看动画中的当前值。
            //
            // 为什么必须这样：双击的 200 ms 里 `scale` 会连续经过 1×…8×，照「当前倍率跨过阈值」
            // 触发就会一次双击连开好几次解码（用户明确说了「不用太费性能」）。两道闸：
            // ① 动画帧这个 block 直接发 `null`，**动画中一帧都不重算**（所以一帧都不会触发解码，
            //    连分配都没有）；② 手势停下来 [ZOOM_SETTLE_MILLIS] 才算落定，`collectLatest` 会把
            //    还没落定的那一次连同它已经发出去的解码一起取消 —— 取消是真的（节点离开组合、缩放
            //    变了、快速来回缩放都属于这一条）。双击的代价因此是：动画走完再过 100 ms 细节出现。
            LaunchedEffect(image.cacheKey, decoded, source) {
                if (source == null) return@LaunchedEffect
                snapshotFlow {
                    if (animating) null else ZoomProbe(scale.value, panX.value, panY.value)
                }.collectLatest { lived ->
                    val settled = lived ?: return@collectLatest
                    delay(ZOOM_SETTLE_MILLIS)
                    val visible = zoomVisibleSourceRect(
                        boxWidthPx = boxWidthPx,
                        boxHeightPx = boxHeightPx,
                        baseWidthPx = decoded.width,
                        baseHeightPx = decoded.height,
                        sourceWidthPx = source.width,
                        sourceHeightPx = source.height,
                        scale = settled.scale,
                        panX = settled.panX,
                        panY = settled.panY,
                    )
                    if (visible == null) {
                        // 回到 1×（或这张源图本来就没有更多像素）：**当场把那一块放掉**，1× 的内存
                        // 与改动前一样。
                        block = null
                        return@collectLatest
                    }
                    val held = block
                    if (held != null && zoomDecodeWindowCovers(held.window, visible)) {
                        // 上一块还盖着这一屏：继续用它，不重解（平移时图不会闪）。
                        return@collectLatest
                    }
                    val window = zoomDecodeWindow(
                        visible = visible,
                        sourceWidthPx = source.width,
                        sourceHeightPx = source.height,
                        baseWidthPx = decoded.width,
                        baseHeightPx = decoded.height,
                    ) ?: return@collectLatest
                    val decoded2 = withContext(Dispatchers.IO) {
                        underImageDecodeGate { decodePiImageRegion(image, window, source) }
                    } ?: return@collectLatest
                    block = DetailBlock(window, decoded2)
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(
                            width = with(density) { fittedWidth.toDp() },
                            height = with(density) { fittedHeight.toDp() },
                        )
                        // 倍率与平移在 **layer 的 block 里**读：动画每一帧只重画这一层，不重组合这个
                        // surface —— 组合里的每一个输入（`image.cacheKey`、源图头部、块的位置）都
                        // 不必每帧重算，这也正是"动画期间不做任何别的事"的实现方式。
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = panX.value
                            translationY = panY.value
                        }
                        // Pinch and drag. Declared before the tap detector so a
                        // gesture that becomes a transform is claimed by this one
                        // and the tap detector only ever sees real taps.
                        .pointerInput(image.cacheKey) {
                            detectTransformGestures { _, drag, zoom, _ ->
                                // 手势写 `snapTo`（挂起）而不是动画：手指走多远图就走多远，没有滞后。
                                // 放进 `launch` 是 Animatable 的用法要求；它的写入会取消正在跑的双击
                                // 动画 —— 手指一动，动画当场交给手指。
                                scope.launch {
                                    val next = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                    zoomIntent = next
                                    scale.snapTo(next)
                                    val panned = if (next <= MIN_SCALE) {
                                        Offset.Zero
                                    } else {
                                        clampPan(
                                            pan = Offset(panX.value, panY.value) + drag,
                                            scale = next,
                                            fittedWidth = fittedWidth,
                                            fittedHeight = fittedHeight,
                                            boxWidth = boxWidthPx.toFloat(),
                                            boxHeight = boxHeightPx.toFloat(),
                                        )
                                    }
                                    panX.snapTo(panned.x)
                                    panY.snapTo(panned.y)
                                }
                            }
                        }
                        .pointerInput(image.cacheKey) {
                            detectTapGestures(
                                // A tap on the picture is consumed and ignored: it
                                // must not reach the backdrop's dismiss detector.
                                onTap = {},
                                onDoubleTap = {
                                    // 1× ⇄ 上限，看清的是**目标态**而不是动画途中的值。
                                    val target = if (zoomIntent > MIN_SCALE) MIN_SCALE else MAX_SCALE
                                    zoomIntent = target
                                    // 这一趟动画的「代次」：第二次双击会取消第一次的三个动画，而第一次的
                                    // `finally` **不能**把 `animating` 清掉 —— 那会让解码触发在第二次动画
                                    // 中途以为动画已经结束。只有当代的那一次有资格清。
                                    val epoch = animationEpoch + 1
                                    animationEpoch = epoch
                                    scope.launch {
                                        animating = true
                                        try {
                                            // 倍率与平移**同时**到达：只动倍率的话，缩回时图会先停在
                                            // 半空再滑回中心。200 ms、默认的 FastOutSlowInEasing，没有
                                            // 弹簧、没有物理曲线 —— 用户要的只是「慢慢过渡」。
                                            coroutineScope {
                                                launch {
                                                    scale.animateTo(
                                                        target,
                                                        tween(ZOOM_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
                                                    )
                                                }
                                                launch {
                                                    panX.animateTo(
                                                        0f,
                                                        tween(ZOOM_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
                                                    )
                                                }
                                                launch {
                                                    panY.animateTo(
                                                        0f,
                                                        tween(ZOOM_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
                                                    )
                                                }
                                            }
                                        } finally {
                                            // 手指打断（`snapTo` 取消了这里的三个 animateTo）也必须落回来，
                                            // 否则解码触发会一直以为动画还在跑，就再也不解了。只有当代的
                                            // 那一次有资格清（见上面 `epoch`）。
                                            if (animationEpoch == epoch) animating = false
                                        }
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.TopStart,
                ) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = "放大的图片",
                        // The node is exactly the bitmap's aspect, so `Fit` fills it.
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 放大后那一块：贴在画面的对应位置上，由上面那层 `graphicsLayer` 跟着一起放大，
                    // 所以它与倍率、平移无关（拖动时不会跟丢），也不需要每帧重算。
                    val held = block
                    if (source != null && held != null) {
                        val at = zoomDecodePlacement(
                            window = held.window,
                            boxWidthPx = boxWidthPx,
                            boxHeightPx = boxHeightPx,
                            baseWidthPx = decoded.width,
                            baseHeightPx = decoded.height,
                            sourceWidthPx = source.width,
                            sourceHeightPx = source.height,
                        )
                        Image(
                            bitmap = held.bitmap.asImageBitmap(),
                            // 装饰性的：它就是上面那张图更清楚的一部分，读屏不必读两遍。
                            contentDescription = null,
                            // 节点尺寸就是这一块在源图上的比例，所以 FillBounds 与 Fit 等价；
                            // 写 FillBounds 是为了让"这块要铺满它自己的位置"这件事在代码里是明确的。
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .offset(
                                    x = with(density) { at.left.toDp() },
                                    y = with(density) { at.top.toDp() },
                                )
                                .size(
                                    width = with(density) { at.width.toDp() },
                                    height = with(density) { at.height.toDp() },
                                ),
                        )
                    }
                }
            }
        }

        // ③ 右上角两颗按钮。最后一个子节点 = 最上面，所以它们自己的点按不会被背景层截走。
        //    保存键与 ✕ 同一种形状（`IconButton` + 同一枚 `tint`），不引入新的按钮样式或颜色。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(PiSpacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { saveToDownloads() },
                enabled = !saving,
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "保存到下载目录",
                    tint = palette.text,
                )
            }
            IconButton(onClick = onDismiss) {
                // v2's icon language: the same `Close` glyph the session tree's top
                // bar uses, in the palette's own `text` token.
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = palette.text,
                )
            }
        }
    }
}

/**
 * 两种来源的差别只在这一个 `when` 里：wire 解 base64，宿主文件把路径交给文件那一版。
 *
 * 闸门、缓存、"只解码一次"都在调用点上（[PiImageViewerSurface]），所以这里换实现不会
 * 绕开它们。写成参数名是 `image`：`image.base64` / `image.file` 就是这两种来源各自的形状。
 */
private fun decodePiImageSource(image: PiImageViewerSource, width: Int, height: Int): Bitmap? =
    when (image) {
        is PiImageViewerSource.Wire -> decodePiImage(image.base64, width, height)
        is PiImageViewerSource.HostFile -> decodePiImageFile(image.file, width, height)
    }

/**
 * 两种解码共用的一把闸门。
 *
 * 1× 那张基础图与放大后的细节块走的是同一个 `withPermit`（[piImageDecodeGate]，进程级 2 许可），
 * 所以放大时的块不会和转写里正在解码的单元格抢帧 —— 查看器本来就是从滚动里点开的。写成函数是
 * 为了让"这个文件里只有一处闸门"是**结构性**的，而不是靠两处调用点长得一样。
 */
private suspend fun <T> underImageDecodeGate(block: suspend () -> T): T =
    piImageDecodeGate.withPermit { block() }

/**
 * 放大之后那一块：位图 + 它是从源图哪个矩形解出来的。
 *
 * 矩形（而不只是像素尺寸）必须一起留着：画的时候要用它在源图上的位置把这个节点贴到画面的对应
 * 位置，那一层 `graphicsLayer` 再把它和整张图一起缩放平移。
 */
private data class DetailBlock(val window: ZoomDecodeWindow, val bitmap: Bitmap)

/**
 * 区域解码：只解源图上 [window] 那一块，1 个位图像素对 1 个源像素（或按 [ZoomDecodeWindow.sample]
 * 整倍降采样）。相册就是这么做的，也是唯一一种"位图大小只由那一块决定、与源图多大无关"的读法。
 *
 * ## 任何一步失败都返回 null
 *
 * 调用方那一侧的画面**等同于没有这一块**（1× 那张还在下面画着），所以这里不抛、不报错、不新增
 * 白屏 —— 区域解码在这套代码里做不动的那些情况（少见的格式、坏头部、解码器内部失败）全都落回
 * 今天的行为。
 *
 * 平台解码器自己说的尺寸（`decoder.width/height`）是权威的：与头部读到的不一致时不画这一块，
 * 免得按错误的坐标把它贴到画面上。
 *
 * ## 为什么用那个已废弃的 `newInstance(byte[], int, int, boolean)`
 *
 * API 31 把 `newInstance` 换成了不带 `isShareable` 的形状，但 `minSdk = 26`：换过去在 26–30 的
 * 设备上是 `NoSuchMethodError`（lint 的 `NewApi` 也会拦）。四个参数的版本从 API 10 起就有，是
 * 唯一能在整条 `minSdk..compileSdk` 上跑的入口 —— 这里的废弃警告是刻意的，不是漏看。
 * `isShareable = false` 是老文档里"不要保留我这份数组"的那一档（现实现本来也会复制一份）。
 *
 * ## 这一次解码的内存（与 [PiZoomDecodeWindow] 的文件头对齐）
 *
 * 常驻的只有块位图（≤ 8 000 000 B）。这一次解码的**峰值**还要算上**源字节**，两份：
 * 这里自己解出来的那一份（wire 是 base64 解码、宿主文件是 `readBytes`，两者都被
 * [MAX_INLINE_IMAGE_BYTES] 那把 8 MiB 的尺子挡在解码之前）+ 平台解码器内部自己那一份。两份都
 * 在 `finally` 的 `recycle()` 之后由回收器释放；块位图由调用方在缩回 1× 时丢掉。也就是说峰值
 * ≤ 8 MiB + 8 MiB + 8 MB，**与原图多大无关**（超 8 MiB 的图根本走不到这里）。
 */
private fun decodePiImageRegion(
    image: PiImageViewerSource,
    window: ZoomDecodeWindow,
    source: ImagePixels,
): Bitmap? = runCatching {
    if (window.width <= 0 || window.height <= 0 || window.sample <= 0) return null
    val bytes = when (image) {
        is PiImageViewerSource.Wire -> Base64.decode(image.base64, Base64.DEFAULT)
        is PiImageViewerSource.HostFile -> image.file.readBytes()
    }
    @Suppress("DEPRECATION")
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
    try {
        if (decoder.width != source.width || decoder.height != source.height) return null
        decoder.decodeRegion(
            Rect(window.left, window.top, window.right, window.bottom),
            BitmapFactory.Options().apply { inSampleSize = window.sample },
        )
    } finally {
        // 解到一半抛异常也不漏掉解码器手里的那份编码数据。
        decoder.recycle()
    }
}.getOrNull()

/**
 * 源图自己的像素尺寸，读头部；读不出来就是 null（这一屏退回那张采样位图，不多要一个字节）。
 *
 * 两条来源都只读**前缀**：wire 是 [naturalImagePixels]（32 字节，JPEG 最多 64 KiB 的标记搜索，
 * 全在内存里的字符串上），宿主文件读 [HEADER_JPEG_SEARCH_BYTES] 那么多个字节 —— 一次真实的文件
 * 读，所以调用点在 `Dispatchers.IO` 上（见 [PiImageViewerSurface] 的 `produceState`）。
 */
private fun readSourcePixels(image: PiImageViewerSource): ImagePixels? = when (image) {
    is PiImageViewerSource.Wire -> naturalImagePixels(image.base64)
    is PiImageViewerSource.HostFile -> readImageHeader(hostFileHeaderBytes(image.file))
}

/**
 * 宿主文件的前 [HEADER_JPEG_SEARCH_BYTES] 字节（读不满就少给），失败给空数组。
 *
 * 长度选的是头部解析自己的第二个预算：四个定长前言的格式 32 字节就够，只有 JPEG 的帧头可能藏在
 * EXIF 缩略图后面，那才需要这 64 KiB。用 `HEADER_JPEG_SEARCH_BYTES` 而不是自己再写一个数，就是
 * 为了让"读多少"与"解析器最多会往前找多少"永远是同一个数。
 */
private fun hostFileHeaderBytes(file: File): ByteArray = runCatching {
    file.inputStream().use { input ->
        val buffer = ByteArray(HEADER_JPEG_SEARCH_BYTES)
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) break
            read += count
        }
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }
}.getOrDefault(ByteArray(0))

/**
 * 保存到公共 Download —— 走 App 唯一那条写公共目录的路（[DeviceSystemActions.export]，
 * 设置里的「导出诊断报告」、工作区查看器的「导出到 Download」都是它），不新开第二个写入器。
 *
 * 两种来源的差别只有"字节从哪里来"：wire 的 base64 原样交给它（不在这里先解一次再编一次），
 * 宿主文件把路径交给它（它自己读，不经过 base64 —— 一张照片几 MB，那趟编解码只是多一份
 * 峰值内存）。成功与失败都回一句给人看的话：失败带 `DeviceDenial` 自己的原因与提示
 * （权限被拒、目录不可写、内容为空各有各的说法）。
 */
private fun saveImage(context: Context, image: PiImageViewerSource): String = try {
    when (image) {
        is PiImageViewerSource.Wire -> {
            val name = wireImageName(image.mimeType, System.currentTimeMillis())
            DeviceSystemActions.export(
                context = context,
                name = name,
                text = null,
                base64 = image.base64,
                mimeType = image.mimeType,
            )
            "已保存到 Download/$name"
        }

        is PiImageViewerSource.HostFile -> {
            DeviceSystemActions.export(
                context = context,
                name = image.file.name,
                text = null,
                base64 = null,
                mimeType = image.mimeType,
                sourceFile = image.file,
            )
            "已保存到 Download/${image.file.name}"
        }
    }
} catch (error: DeviceActionException) {
    "保存失败：${error.denial.reason}" + (error.denial.hint?.let { " $it" } ?: "")
} catch (error: Exception) {
    "保存失败：${error::class.java.simpleName}: ${error.message}"
}

/**
 * wire 的图片没有名字 —— 它是会话里的一段 base64。落进 Download 时得有一个：用 MIME 的
 * 子类型当后缀，加一个时间戳（同一张图存两次不会互相覆盖，文件管理器里也看得出是什么）。
 * 子类型里的非字母数字一律去掉：它是从 wire 上来的字符串，不该有任何机会走进文件名。
 */
private fun wireImageName(mimeType: String, nowMs: Long): String {
    val subtype = mimeType.substringBefore(';').substringAfter('/', "").lowercase(Locale.US)
    val extension = subtype.filter { it.isLetterOrDigit() }.ifEmpty { "img" }
    return "pi-image-$nowMs.$extension"
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

/**
 * The zoom range. 1× is "fitted to the window" by construction, and the ceiling is
 * [MAX_SCALE] rather than infinity because past it the screen has run out of source pixels:
 * 8× is already more than the 1:1 magnification of an ordinary 12 MP photo in a 1080 px
 * window, and the detail block above cannot invent more.
 *
 * 双击直接跳到 [MAX_SCALE]，不再有第三个数字：用户要的是「双击放到最大、再双击回到原样」
 * （相册里最基本的那个动作），而一个介于 1 与上限之间的中间档只会让人怀疑「为什么双击只放大
 * 一点点」。它同时意味着双击之后一定还能再放大 —— 上限就是双击到的那一档，捏合的范围与它完全
 * 重合（1×–8×）。
 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f

/**
 * 双击的过渡时长（ms）。
 *
 * 用户的说法是「慢慢过渡」，200 ms 上下配一条 ease 收尾就是"看得出在动"与"不用等"之间的那一点
 * ——再长会显得迟钝，再短就还是瞬移。它只改 `graphicsLayer` 的倍率与平移（几个浮点），动画期间
 * 一次解码都不发生（见 [PiImageViewerSurface] 里那段"重解只认落定的倍率"）。
 */
private const val ZOOM_ANIMATION_MILLIS = 200

/**
 * 手势/动画静默多久才算「落定」，可以按落定的倍率决定要不要重解（ms）。
 *
 * 捏合的每一个事件都会改倍率，如果按当前值触发，一次捏合会连开好几次解码。等这一段时间没有新
 * 变化，才是用户真正停下来的那一档倍率；代价是手指停稳（或双击的 200 ms 动画走完）之后约
 * 100 ms 细节才补上，而这段时间里画面上一直是 1× 那张（以及可能还在的那一块），不会闪、不会白。
 */
private const val ZOOM_SETTLE_MILLIS = 100L
