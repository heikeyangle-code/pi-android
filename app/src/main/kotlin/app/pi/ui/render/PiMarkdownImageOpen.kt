package app.pi.ui.render

import android.content.Context
import app.pi.bridge.GuestImageBytes
import app.pi.rpc.PiImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 正文里的一张图被点了一下：**还是原来那条字节通道**取字节，然后交给查看器。
 *
 * ## 走的是哪条路（一行都不新开）
 *
 * [GuestImageBytes.load] 就是渲染那张图时用的同一个入口：`data:` 就地解码、`file:`/裸路径
 * 走 guest→host 映射、`http(s)` 命中 `<files>/pi/image-cache/` 的有界磁盘缓存，未命中才
 * 出网。出网那一段的每一个数字都**没有动**：3 个取字节许可（`MAX_CONCURRENT_REMOTE_FETCHES`）、
 * 8 MiB 单张上限（`MAX_BYTES`）、5 s 连接 / 5 s 读 / 15 s 总预算、64 MiB 磁盘 LRU
 * （`bridge/GuestImageBytes.kt:237-265`）。这次改动**没有**新增任何网络代码，也没有第二份
 * 缓存 —— 点一次图只是把已经画出来的那张图的字节再取一次，而它已经在磁盘缓存里。
 *
 * **为什么不用 transformer 那条缝。** 渲染器的 `ImageTransformer.transform` 是
 * **像素**接缝：它 `@Composable`，回答的是 `ImageData`（一个 `Painter`）
 * （`bridge/PiGuestImageTransformer.kt:103-128`），拿不到字节。字节在手的是它下面那一层，
 * 也就是这里直接复用的 [GuestImageBytes]。
 *
 * ## 45 s 负缓存（`PiImageRequestPolicy`）：为什么这里不问它也不会被绕过
 *
 * 负缓存是**渲染**那条路的调度器：它记的是"这张图上次取失败了，45 s 内别再出网"
 * （`bridge/PiImageRequestPolicy.kt:45-48`、`:130-141`）。它只在**失败**时写入
 * （`:194-201` 的 `finish` 只对 `isFailure` 记），而点按这个动作只可能发生在
 * **已经画出来**的图上 —— 取字节失败时画的是 `PiMarkdownComponents.kt` 里 `PiImageFallback`
 * 那两行文字（块的、行内的两个槽都走它），它是**不可点**的（见 `PiMarkdownComponents.kt` 的
 * `PiImageViewerTap`）。
 *
 * 于是"点得动"这件事本身就已经证明了：这个 `(link, 目标宽度)` 上有过一次**成功**。而负缓存
 * 里不可能同时留着它的失败记录 —— `load` 只在**动手之前**查表，命中就直接失败（那次失败会
 * 继续写表、继续画回退文案，图依旧画不出来），任何一次成功都必然先走过
 * `:139-140` 的"到期就删"。所以点按时这张表里对它是空的，这里直接取字节与"先问一次负缓存"
 * 结论完全一样，不是绕过。
 *
 * 剩下的唯一情形是磁盘缓存被 LRU 淘汰（滚过很多大图之后）——那时这次取字节会走
 * `GuestImageBytes` 里那条**同一个**出网路径（同一把 3 许可闸门、同一套超时）。这是对的：
 * 用户明确要求看这张图，而它此刻就在屏幕上。
 *
 * ## 为什么整段在 [Dispatchers.IO] 上
 *
 * 本地那两条是阻塞的 `File.readBytes()`/`Base64.decode`，远端那条自己会再切一次 IO，但调用方
 * 不能把一次文件读或一次几 MB 的 base64 编码放进帧里。编码那一步在
 * [PiMarkdownImageTap.viewerImage] 里，8 MiB 上限下最坏几十毫秒 —— 同样是 IO 上的活。
 *
 * 不加宽度参数：采样是**渲染**那一侧的事（`PiGuestImageTransformer.decode` 按将要画的框
 * 估 `inSampleSize`），而查看器按自己的窗口重新解码（`PiImageViewerSurface` 的
 * `produceState`）。这里要的是**源字节**，与画多大无关。
 *
 * @return 交给 `PiImageViewer` 的 wire 值；取字节失败（离线、超时、不是图片、文件不在……）
 *   返回 `null`，调用点什么都不做 —— 原因留在 `GuestImageBytes.lastFailure`，与渲染那条路
 *   同一份诊断。
 */
internal suspend fun piMarkdownImageViewerImage(context: Context, link: String): PiImage? =
    withContext(Dispatchers.IO) {
        val loaded = GuestImageBytes.load(context, link) ?: return@withContext null
        PiMarkdownImageTap.viewerImage(loaded.bytes, loaded.mimeType)
    }
