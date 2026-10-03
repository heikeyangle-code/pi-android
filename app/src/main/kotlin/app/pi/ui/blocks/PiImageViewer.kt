package app.pi.ui.blocks

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

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

        override val cacheKey: String
            get() = "file:${file.absolutePath}:${file.length()}:${file.lastModified()}"
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
 * ## Decoding
 *
 * The bitmap is decoded **once**, sampled to this view's own pixel size
 * ([decodePiImage] for the wire, [decodePiImageFile] for a host file, both with a target
 * box), on [Dispatchers.IO] and under [piImageDecodeGate] — the same bound the
 * transcript's cells wait on, because this viewer is opened *from* a scroll and its
 * full-screen decode must not be one more allocation competing with a list that is
 * still filling its own cells in. Three things follow:
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
 *  - **double tap** → 1× ⇄ [MAX_SCALE]（到顶），清掉平移；
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
                // stays the whole window — see the class KDoc on the zoom ceiling.
                //
                // And through [PiImageCache], keyed on the same "these exact bytes"
                // identity: closing and reopening the viewer (or a rotation) used to
                // decode the whole picture again. The viewer's entry is deliberately
                // separate from a cell's — a different box for a different surface (the
                // gate's KDoc, the class KDoc above, and `decodePiImage`'s sampling rule
                // all say why). Neither source reads the file or the payload twice
                // either: that is the one call below, and it is the only decode site.
                PiImageCache.readThrough(image.cacheKey, boxWidthPx, boxHeightPx) {
                    piImageDecodeGate.withPermit {
                        decodePiImageSource(image, boxWidthPx, boxHeightPx)
                    }
                }
            }
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
            // and give the Image node exactly that size. Two consequences: the
            // letterboxed ground stays the backdrop's (so a tap there dismisses,
            // as asked), and the pan clamp below has real bounds to work with.
            val fit = min(
                boxWidthPx.toFloat() / decoded.width,
                boxHeightPx.toFloat() / decoded.height,
            ).takeIf { it.isFinite() && it > 0f } ?: 1f
            val fittedWidth = decoded.width * fit
            val fittedHeight = decoded.height * fit

            var scale by remember(image.cacheKey) { mutableStateOf(1f) }
            var pan by remember(image.cacheKey) { mutableStateOf(Offset.Zero) }

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
                        .pointerInput(image.cacheKey) {
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
                        .pointerInput(image.cacheKey) {
                            detectTapGestures(
                                // A tap on the picture is consumed and ignored: it
                                // must not reach the backdrop's dismiss detector.
                                onTap = {},
                                onDoubleTap = {
                                    scale = if (scale > 1f) 1f else MAX_SCALE
                                    pan = Offset.Zero
                                },
                            )
                        },
                )
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
 * [MAX_SCALE] rather than infinity because past it a sampled bitmap is only being
 * magnified as blur.
 *
 * 双击直接跳到 [MAX_SCALE]，不再有第三个数字：用户要的是「双击放到最大、再双击回到原样」
 * （相册里最基本的那个动作），而一个介于 1 与上限之间的中间档只会让人怀疑「为什么双击只放大
 * 一点点」。它同时意味着双击之后一定还能再放大 —— 上限就是双击到的那一档，捏合的范围与它完全
 * 重合（1×–8×）。
 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
