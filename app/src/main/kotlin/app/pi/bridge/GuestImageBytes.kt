package app.pi.bridge

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 一个 markdown 图片 link 背后的字节，以及这些字节从哪来。
 *
 * [source] 只用于诊断（[GuestImageBytes.lastFailure] 那套），从不外发：它让维护者分得清
 * "link 是个 data: URI" 和 "link 是个不存在的 guest 路径"，也分得清"这份字节来自网络缓存"
 * 还是"这次真出了网"。
 */
internal class GuestImage(
    val bytes: ByteArray,
    val mimeType: String,
    val source: String,
)

/**
 * 把一个 markdown 图片 `link` 变成字节。
 *
 * ## 先读这一段：渲染层**已经会出网了**
 *
 * 本轮之前，这个对象的 KDoc 把 `http(s)` 写成一类被**故意拒绝**的 link，理由是
 * "渲染层没有网络 I/O"、"一次静默请求会把用户的 IP 泄漏给模型随手写的主机"。
 * 那条性质**已经撤销**：用户要求「全面加强」，`http(s)` 现在真的去取字节。
 * 这是本轮唯一的行为性偏离，也是本 App 与 pi 的又一处故意不同——pi 的终端渲染器
 * **从不为 markdown 图片取字节**，它连 `image` 分支都没有，`![]()` 落到 `default`
 * 分支只打印 alt 文本（`packages/tui/src/components/markdown.ts:619-627`）。
 * 偏离的价钱写在下面「远端图片」一节里，一条不省，并且登记在
 * `docs/known-gaps.md` §A3。
 *
 * 除 `http(s)` 之外的所有路径（`data:`、`file:`、guest 路径）仍然是纯本地读，
 * 一行网络代码都不经过，也仍然没有 loopback 服务（见「为什么不用 loopback HTTP」）。
 *
 * "NOT WIRED YET — A3 does not take effect until this is called" 那段也删了：
 * 接线早就完成（`ui/render/PiMarkdown.kt` 调 `rememberPiGuestImageTransformer()`，
 * 同一个实例同时进本 App 的 local 与库的 `Markdown(imageTransformer = …)`），
 * 原来列的 P-A / P-B 两处都不再是待办。
 *
 * ## 一个 `link` 到底是什么 —— 读 pi，不猜
 *
 * pi 自己**没有**定义任何图片 link 语义，所以这里必须自己决定而不是照抄：
 *
 *  - pi 的终端 markdown 渲染器没有 `image` 分支。它的 `switch` 落到 `default`，
 *    只打印 token 的 `text`，也就是 alt 文本
 *    （`packages/tui/src/components/markdown.ts:619-627`）。所以 pi 里 markdown 图片
 *    是**文本**，不是一次取字节。
 *  - RPC 上的图片是 inline base64，从来不是路径：`prompt`/`steer`/`follow_up` 带
 *    `images?: ImageContent[]`，形状是 `{type:"image", data:<base64>, mimeType}`
 *    （`packages/coding-agent/docs/rpc.md:78`），文档里的会话 `Attachment` 也把
 *    `content` 作为 base64 内嵌、磁盘上没有文件（`docs/rpc.md:1510-1521`）。
 *    所以"pi 会话附件"**不是**这条通道能读的位置——那些字节在消息里，由
 *    `ui/blocks/ImageGridBlock.kt` 那块表面渲染。
 *  - pi 唯一把图片变成路径的地方是 TUI 的剪贴板粘贴：它写
 *    `/tmp/pi-clipboard-<uuid>.<ext>` 并把那个**路径当文本**插进编辑器
 *    （`src/modes/interactive/interactive-mode.ts:2934-2946`）。这段文本可能被引用进
 *    助手的消息里，这才是"guest 路径值得解析"的 pi 依据。
 *
 * 其余一切都是模型自己写的文本：LLM 写 `![chart](/tmp/x.png)` 并不是在遵守 pi 的契约。
 * 所以这里接受那些**能解析**的形状，其它一律 `null`（正文保留占位与来源文本），
 * 从不自己编一条路径出来。
 *
 * ## 它解析什么
 *
 *  - `data:` URI，就地解码。自包含，不牵涉任何通道。
 *  - `file:` URI 与裸文件路径，从 guest 的写法映射到宿主文件（下面那张表）。
 *  - `http:` / `https:`，取字节 + 有界磁盘缓存（下一节）。**只**这两个 scheme：
 *    别的一律继续走文件那条路并失败（例如 `ftp:` 会得到"找不到图片文件"）。
 *
 * ## 远端图片（`http(s)`）：取什么、上限多少、缓存在哪、失败怎么办
 *
 * ### 取的是什么
 *
 * 一次 `GET`，用 `HttpURLConnection`（不新增依赖，和 `packages/PiModelScanner.kt`
 * 已经出网那条路同一个栈）。**一个请求头都不设**：没有 cookie、没有 `Authorization`、
 * 没有自定义 header。URL 里内嵌凭据（`http://user:pass@host/x.png`）直接拒绝，
 * 并且**不回显**这个 link（不然密码就进了 [lastFailure]）。
 *
 * `CookieHandler` 是个例外，而且必须当硬条件处理：进程里只要装了一个
 * （`android.webkit.CookieManager` 一构造就会 `CookieHandler.setDefault`），
 * `HttpURLConnection` 就会自己把 cookie 加进请求，而且**没有开关**能关掉它。
 * 所以这里不是"承诺不带 cookie"，而是：**装了 cookie handler 就拒绝远端图片**，
 * 原因写进 [lastFailure]。本 App 目前没装（`grep -rn "CookieManager\|CookieHandler"
 * app/src/main` 只命中注释），所以正常情况下这个守卫不触发。
 *
 * ### 上限与超时（都是硬数字）
 *
 *  - 单张 [MAX_BYTES] = 8 MiB，与 `data:` URI、本地文件同一个数。`Content-Length`
 *    先看一眼，读取循环再按实际字节数卡一遍（声明可以没有，也可以撒谎）。
 *  - 连接 [CONNECT_TIMEOUT_MS] = 5 s，单次读 [READ_TIMEOUT_MS] = 5 s，
 *    一整张图的总预算 [TOTAL_TIMEOUT_MS] = 45 s。前两个管"多久没有进展"，
 *    第三个管"每块都读得到、但慢慢喂一整张"——循环在**每块之间**查它，所以一次远端
 *    取字节的最坏耗时是总预算再加一个读取超时，不是无限。
 *
 * ### 同时打开的 socket 有几个
 *
 * 取字节那一段有**自己**的信号量 [remoteFetchGate]，[MAX_CONCURRENT_REMOTE_FETCHES] = 3 个
 * 许可。它与 `ui/blocks/ImageSize.kt` 的 `piImageDecodeGate`（2 个许可）是**两把不同的
 * 闸门、管两件不同的事**，不是重复：那一把限的是"进程里同时在**解的位图**"（内存与帧时间，
 * 而且它只包 decode），这一把限的是"同时在**开的连接**"（socket/FD）。合成一把就会让其中
 * 一个上限被另一个的许可数无意中决定 —— 解码 2 许可会变成"网络也只能同时 2 条"，反过来
 * 也一样。
 *
 * 许可在**磁盘缓存未命中之后**才申请：命中缓存不出网，也就不需要许可。而
 * [TOTAL_TIMEOUT_MS] 从**拿到许可之后**才开始算，所以 45 s 仍然是"这一次取字节的网络时间"，
 * 排队不占它（最坏总耗时因此是"排队 + 45 s + 一个读超时"；外层
 * `PiImageRequestPolicy` 的兜底预算在同一件事上也已经改成"排队不算在内"，见那边
 * `PI_IMAGE_PRODUCER_BUDGET_MS` 的 KDoc —— 两层对"排队到底算谁的时间"必须是同一个答案）。
 *
 * ### 缓存在哪、多大
 *
 * 有界磁盘缓存 + LRU，目录是 `<files>/pi/image-cache/`（[PiPaths.home] 下面）。
 * **不在 rootfs 里**：rootfs 是 guest 自己的树，升级/prune 的故事必须保持简单，
 * 一个可以随时丢掉的图片缓存不该进去。文件名是 URL 的 SHA-256 前 16 字节的
 * URL-safe base64（22 个字符，文件系统安全，也不泄漏 URL 本身）。
 * 总大小上限 [MAX_DISK_BYTES] = 64 MiB；超了按 `lastModified` 从最旧开始删，
 * 而命中缓存会 `setLastModified` 把它顶到最新——这就是 LRU 的全部实现，没有索引文件。
 * **命中缓存不出网**。
 *
 * 写入是"先写 `.tmp` 再 rename"，所以不会留下一份写了一半的缓存文件；写盘本身失败
 * 不致命（字节已经在内存里，图照画，只是下次还得再出一次网）。
 *
 * **没有 revalidation**：这一份字节的身份就是 URL，同一个 URL 的内容在服务端换了不会
 * 自动重取（没有 `If-None-Match` / `If-Modified-Since`，因为那要再出一次网，也就
 * 抵消了缓存的意义）。要强制重取就清掉 App 存储，或者等 LRU 把它淘汰。
 *
 * ### 失败怎么办
 *
 * 一律 `null` + [lastFailure] 写明原因，于是正文走那条既有的「alt + 图片地址」回退
 * （`ui/render/PiMarkdownComponents.kt` 的 `PiImageFallback`）——**不新增一种"破图"外观**。
 * 离线、超时、非 2xx、超过上限、不是图片，都是这一条路。失败**不写磁盘**（磁盘缓存里
 * 只有成功取到的完整字节），但也**不再是"下次组合会再试一次"**：`PiImageRequestPolicy`
 * 把一次失败在内存里记住 45 s，这段时间内同一个 `(link, 目标宽度)` 根本走不到这里。
 *
 * 为什么是 TTL 而不是永久负缓存：图片文件可能就在这一轮才被 agent 写出来，URL 可能只是
 * 瞬断，502 也可能下一秒就好 —— 永久记住会把一次瞬时失败变成永久空白。要的只是"滚动
 * 期间别反复重打"。完整理由在那条策略的 KDoc 里。
 *
 * ### 线程与取消
 *
 * 阻塞 I/O，**绝不在主线程**：远端那条整段包在 `withContext(Dispatchers.IO)` 里，
 * 调用方就算忘了也不会把它读进帧里（本 App 的调用方是
 * `PiGuestImageTransformer`，它本来就在 IO 上）。读取循环每读一块查一次取消
 * （`currentCoroutineContext().ensureActive()`）。
 *
 * **"节点离开组合下载当场停"这条已经撤销**（R1）。下载现在跑在 [PiImageRequestPolicy] 的
 * **进程级**作用域里：节点离开组合它不会停，而是跑完并把字节写进磁盘缓存，用户滚回来时
 * 那是**缓存命中、不出网**。原因是 `LazyColumn` 回收行会让 `produceState` 重跑，而"失败
 * 不被记住"意味着**一张坏 URL 每滚回来一次就重新出一网**；取舍、预算与谁取消谁写在
 * [PiImageRequestPolicy] 的 KDoc 里，这里只说后果。
 *
 * **取消只在每 64 KiB 分块之间检查**（[readBounded]）：`InputStream.read` 阻塞在 socket 上
 * 时协程取消是进不去的 —— 这是 JDK 阻塞 I/O 的性质，根治不了。所以一次 `read` 的最坏耗时
 * 由 [READ_TIMEOUT_MS] 兜底、整段由 [TOTAL_TIMEOUT_MS] 与策略层的兜底预算兜底：
 * "取消不进去"不等于"停不下来"。
 *
 * **同一帧的两个调用者不再各取一次。** 库对一张**行内**图片会在同一帧调用 `transform`
 * 两次：`MarkdownInlineImageWithSize` 自己调一次
 * （`.../compose/elements/MarkdownText.kt:375`，用来量本征尺寸），随后
 * `components.inlineImage`（也就是 `PiInlineImage`）经 `MarkdownInlineImage` 又调一次
 * （`.../elements/MarkdownInlineImage.kt:13`）。两次都是各自 `produceState`（键相同），
 * 而第一个请求现在按 `(link, 目标宽度)` 进了 [PiImageRequestPolicy] 的进程级 in-flight
 * 表，第二个**共享**它的结果，不再另开一条连接。写磁盘仍然用**唯一的临时文件名**
 * （见 [rememberOnDisk]）：那是另一条独立的正确性要求（两个 writer 共用 `.tmp` 会互相
 * 截断，半份文件会被当成命中），去重不能替代它。
 *
 * ## 字节从哪来（guest 路径 → 宿主文件）
 *
 * 桥跑在 App 进程里，guest 是同一个 app-private 树里的 proot rootfs，所以引擎建的每个
 * bind 都是**这个进程本来就能读的宿主目录**。映射逐条都是一次 `ProotCommand.build` 的
 * bind：
 *
 *  - `/workspace/<reg>` → `<filesDir>/<reg>`——`PiEngineHost.guestPathFor`
 *    (`engine/PiEngineHost.kt:552-556`) 去掉 `<filesDir>` 前缀并挂到 `/workspace`
 *    (`:286`)。终端的 `PtyLauncher` 把**同一个**宿主目录绑到上一级、也就是 `plain
 *    /workspace`（`runtime/PtyLauncher.kt:146,264`），所以两种拼法都会试。
 *  - `/root/.pi/agent` → `<rootfs>/root/.pi/agent`——`PiPaths.agentDir`。从 2026-09-23 起
 *    这**就是** guest 的路径（bind 没了），所以走下面那条 rootfs 规则。
 *  - `/tmp` 同理：2026-09-23 起它不再是 bind，guest 的 `/tmp/...` 就是 `<rootfs>/tmp`。
 *  - `/sdcard` 与 `/storage/emulated/0` → 设备共享存储——`runtime/PiRuntime.kt:92-93`。
 *  - 其它一切（`/etc`、`/opt`、`/usr`、`/root/...`）→ rootfs：
 *    `--rootfs=<files>/pi/runtime/rootfs`（`runtime/PiRuntime.kt:114`）。
 *
 * 宿主路径也可以被直接写出来（`/data/user/0/<pkg>/...`、`/storage/emulated/0/...`），
 * 所以裸绝对路径是最后一项。顺序不是随手定的——[GuestPathMapping] 把它当作安全论证写下来，
 * `app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt` 钉着它。
 *
 * ## 为什么不用 loopback HTTP
 *
 * 本 App 其它 loopback 服务（`DeviceBridgeHttp`、高亮服务）存在是因为**guest** 要够到
 * **宿主**进程。这里字节走的是相反方向、而且从不离开 App 进程（远端图片那一路是出网，
 * 不是 loopback），所以加一个 socket 只会给一次 `File.readBytes()` 添上 token、端口和
 * 一种失败模式。不新增任何端点。
 *
 * ## 明说的限制
 *
 * 只解 pi 自己的 `read` 工具接受的五种格式（jpg/png/gif/webp/bmp——
 * `docs/pi-android-app-design.md:507`；解码器是 transformer 里的 `BitmapFactory`）。
 * 动图 GIF 只画第一帧。`data:` URI 照它带的 base64 画。共享存储靠 App 自己的权限读，
 * 所以在 scoped storage 的设备上那里的路径可能根本读不到——那会报成失败，不会是一张空图。
 * pi 的 `images.blockImages` 设置**不看**，因为 pi 把它定义成"阻止所有图片发给 LLM 提供方"
 * （`core/settings-manager.ts:64`），那是一个出站请求的开关，不是渲染开关。
 * 另外：远端图片只接受 http/https。**明文** `http` 曾经会被平台策略拦住——本 App 的
 * `targetSdk` 默认 28（`app/build.gradle.kts:38`），API 28 起平台默认拒绝明文，而当时 manifest
 * 里既没有 `usesCleartextTraffic`、也没有 network security config。现在 `<application>` 上明确
 * 写了 `android:usesCleartextTraffic="true"`（用户拍板「明文开」），所以 `http://` 与
 * `https://` 都能取。代价要明写：那一条是**整个应用进程**的网络策略，不是「只放行图片」——
 * 它为这条渲染路径而开，但 App 内所有 Java 网络栈都受影响。
 */
internal object GuestImageBytes {

    /**
     * 8 MiB。这是一条**显示**上限，不是 pi 的提供方上限：pi 发给模型的图会先缩到
     * 2000x2000 / 4.5 MB base64（`docs/pi-android-app-design.md:580`），而这条路径只需要
     * 放得进手机屏幕。超过上限不是"截断后画"，而是带着原因拒绝——解码一张超限的图
     * 会直接把转写 OOM 掉。同一把尺子量四处：`data:` URI 解出的字节、本地文件的长度、
     * 远端响应的实际字节数、以及磁盘缓存里已有的那一项。
     */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 远端连接超时；见类注释「上限与超时」。 */
    private const val CONNECT_TIMEOUT_MS = 5_000

    /** 远端两次读之间没有进展就算失败。 */
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * 一次远端取字节的总预算，循环在每块之间查它。
     *
     * **15 s → 45 s**，理由是可算的、不是感觉：用户自己截图里那批 Wikimedia 图
     * （`…/1920px-….jpg`）实测 **45 KB – 540 KB**（本机用与 App 相同的"不设任何请求头"
     * 取过：Tatry 483 852 B、Hong Kong 538 758 B、gstatic 44 891 B），而他截图里的状态栏
     * 是 **4–18 KB/s**（VPN）—— 15 s 里最多到 270 KB，所以**那些图在 15 s 预算下无论
     * 如何都取不全**，表现就是"有的成功、有的不成功"（小的成、大的败）。45 s 在
     * 11–18 KB/s 上够到 500 KB 那一档。
     *
     * 代价明写：只有"服务端在响应、但很慢"的连接才吃得到这个 45 s（连接失败 5 s 就结束、
     * 卡住 5 s 就结束），而且它换来的字节会进磁盘缓存，下次是命中不出网。
     */
    private const val TOTAL_TIMEOUT_MS = 45_000L

    /** 磁盘缓存的总大小上限，单位字节。 */
    private const val MAX_DISK_BYTES = 64L * 1024 * 1024

    /** 磁盘缓存目录名，落在 [PiPaths.home]（`<files>/pi`）下面。 */
    private const val DISK_CACHE_DIR = "image-cache"

    /** 远端响应体的读取分块大小。 */
    private const val READ_CHUNK_BYTES = 64 * 1024

    /**
     * 同时在取字节的远端请求上界，3 条。见类注释「同时打开的 socket 有几个」：
     * 它和 `piImageDecodeGate`（2 许可、只包解码）是两把不同的闸门。
     *
     * 3 而不是 2：解码闸门是"进程里同时在解的位图"，取字节闸门是"同时在开的连接"。
     * 一屏里有 5 张坏 URL 时，3 条并发已经足够快（一条最坏 45 s + 5 s，剩下的排队），
     * 又不至于让 5 张图同时各占一个 socket、各自的 8 MiB 缓冲和 `Dispatchers.IO` 的线程。
     */
    private const val MAX_CONCURRENT_REMOTE_FETCHES = 3

    /**
     * 取字节的闸门。见类注释「同时打开的 socket 有几个」。
     *
     * 对象是 `GuestImageBytes`（一个进程级 object），所以这把闸门是**进程级**的：两个
     * 组合、两个页面、两个 transformer 抢的是同一份许可。许可在磁盘缓存未命中之后才拿。
     */
    private val remoteFetchGate = Semaphore(MAX_CONCURRENT_REMOTE_FETCHES)

    /**
     * 上一次取字节为什么失败，供诊断用。成功后是 `null`。与
     * `PiHighlightClient.lastFailure` 同形，两条字节通道可以用同一种方式报告。
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 记一条**不是这次读字节产生**的失败原因（来自 [PiImageRequestPolicy]：负缓存命中、
     * 生产者超预算）。与 [fail] 共用"失败 = null + lastFailure"这条规矩，只是原因由那条
     * 状态机给 —— 不然"这次没出网"会顶着一句上一次别处的下载失败，诊断就撒谎了。
     */
    fun noteFailure(reason: String) {
        lastFailure = reason
    }

    /**
     * 取字节。阻塞：远端那条路自己切到 [Dispatchers.IO]，本地两条路是普通的文件读。
     *
     * `suspend` 只为了取消——远端读取循环用它查 `ensureActive()`（见类注释「线程与取消」：
     * 取消只在每块之间生效，节点离开组合**不再**让下载当场停 —— 那件事现在归
     * [PiImageRequestPolicy] 的进程级作用域管）。
     */
    suspend fun load(context: Context, rawLink: String): GuestImage? {
        val link = rawLink.trim().removeSurrounding("<", ">").trim()
        if (link.isEmpty()) return fail("图片链接为空")
        val remote = httpUriOrNull(link)
        return try {
            when {
                remote != null -> fromRemote(context, remote)
                link.startsWith("data:", ignoreCase = true) -> fromDataUri(link)
                else -> fromFile(context, link)
            }
        } catch (cancel: CancellationException) {
            // 取消不是失败，是"这一帧不要了"：照原样抛出，别被下面的 catch 变成一条假的
            // 失败原因，也别让已经取消的协程继续跑下去。
            throw cancel
        } catch (error: Exception) {
            fail("读取图片失败：${error::class.java.simpleName}: ${error.message}")
        }
    }

    /**
     * `link` 的 `URI`，**只**在 scheme 是 `http`/`https` 时给出；别的（包括解析失败）
     * 一律 `null`，交给文件那条路。
     *
     * 用 `URI` 而不是 `startsWith("http")`：后者会把 `httpx:` 也当成远端，而
     * 「只接受 http/https」这条规矩应该只有一处说了算。scheme 大小写不敏感，
     * 所以 `HTTP://` 也走这里。
     */
    private fun httpUriOrNull(link: String): URI? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        return if (scheme == "http" || scheme == "https") uri else null
    }

    // --------------------------------------------------------------- sources ----

    /** `data:image/png;base64,...` —— 自包含，不牵涉任何通道。 */
    private fun fromDataUri(link: String): GuestImage? {
        val comma = link.indexOf(',')
        if (comma < 0) return fail("data: URI 缺少逗号分隔符")
        val header = link.substring("data:".length, comma)
        val payload = link.substring(comma + 1)
        val base64 = header.split(';').any { it.trim().equals("base64", ignoreCase = true) }
        val bytes = if (base64) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty()) return fail("data: URI 解出 0 字节")
        if (bytes.size > MAX_BYTES) return fail("data: URI 超过 ${MAX_BYTES / 1024 / 1024} MiB 上限")
        val declared = header.substringBefore(';').trim()
        return GuestImage(bytes, mimeOf(bytes, declared, null), "data: URI（${bytes.size} 字节）")
    }

    private fun fromFile(context: Context, link: String): GuestImage? {
        val path = when {
            link.startsWith("file:", ignoreCase = true) -> fileUriToPath(link)
                ?: return fail("不支持的 file: URI：$link")

            else -> link
        }
        for (candidate in hostCandidates(context, path)) {
            if (!candidate.isFile) continue
            if (candidate.length() > MAX_BYTES) {
                return fail("图片文件 ${candidate.name} 超过 ${MAX_BYTES / 1024 / 1024} MiB 上限")
            }
            val bytes = candidate.readBytes()
            if (bytes.isEmpty()) return fail("图片文件 ${candidate.name} 是空文件")
            return GuestImage(bytes, mimeOf(bytes, "", candidate.name), candidate.absolutePath)
        }
        return fail("找不到图片文件：$path（已尝试 guest 到 host 的全部绑定映射）")
    }

    /**
     * 远端图片。整段在 [Dispatchers.IO] 上，所以调用方在哪个线程都不会把网络读进帧里。
     *
     * 顺序是「守卫 → 磁盘缓存 → 出网 → 写缓存」：三个守卫（凭据、主机名、cookie handler）
     * 靠前是因为它们决定这次请求**允不允许发生**，而不是它成功还是失败。
     */
    private suspend fun fromRemote(context: Context, uri: URI): GuestImage? = withContext(Dispatchers.IO) {
        // 凭据：不回显 link，不然密码就进了 lastFailure。
        if (uri.userInfo != null) {
            return@withContext fail("远端图片链接内嵌了用户名/密码，拒绝：不会发送任何凭据")
        }
        if (uri.host.isNullOrEmpty()) return@withContext fail("远端图片链接没有主机名，拒绝")
        // 见类注释：装了 CookieHandler 就一定会带上 cookie，而且关不掉，所以只能拒绝。
        if (CookieHandler.getDefault() != null) {
            return@withContext fail("进程里装了 CookieHandler，远端图片可能带上会话 cookie，拒绝")
        }

        val cacheFile = diskCacheFile(context, uri)
        // 长度比较写开而不是用 `in 1..MAX_BYTES.toLong()`：Int/Long 混写的区间在这里没有
        // 可读性的收益，而 `Long <= Int` 是 Kotlin 允许的数值比较。
        val cacheLength = cacheFile?.length() ?: 0L
        if (cacheFile != null && cacheFile.isFile && cacheLength > 0 && cacheLength <= MAX_BYTES) {
            val cached = runCatching { cacheFile.readBytes() }.getOrNull()
            // `sniff` 不是可有可无的一步：缓存里那份如果被写坏/换掉，直接交给解码器会
            // 变成一张**永远**画不出来的图（失败**不写磁盘缓存**，但缓存命中不会再出网）。
            // 认得出是 pi 的五种格式之一才用它，否则当成未命中、重新取。
            if (cached != null && cached.isNotEmpty() && sniff(cached) != null) {
                // LRU：命中的这份变成最新，下次淘汰不会先丢它。
                runCatching { cacheFile.setLastModified(System.currentTimeMillis()) }
                return@withContext GuestImage(
                    cached,
                    mimeOf(cached, "", null),
                    "远端缓存（$uri，${cached.size} 字节）",
                )
            }
        }

        // 缓存未命中才申请取字节的许可：见类注释「同时打开的 socket 有几个」。45 s 的总预算
        // 也从**拿到许可之后**才算（`fetchOverNetwork` 内部），所以排队不占网络预算。
        val bytes = fetchOverNetwork(uri) ?: return@withContext null

        cacheFile?.let { file -> rememberOnDisk(file, bytes) }
        GuestImage(bytes, mimeOf(bytes, "", null), "远端 $uri（${bytes.size} 字节）")
    }

    /**
     * 一次真正的出网取字节：拿一个 [remoteFetchGate] 的许可 → `GET` → 按 [MAX_BYTES] 与
     * [TOTAL_TIMEOUT_MS] 读完 → 断开。失败一律 `null`（原因写进 [lastFailure]），
     * 取消照原样抛。
     *
     * 许可只包这一段（不含磁盘缓存读写、不含解码），所以"同时打开的 socket"这个上界就是
     * [MAX_CONCURRENT_REMOTE_FETCHES]。`deadlineNanos` 在这里算而不是在调用方：它量的是
     * "这一次取字节"的时间，排队等许可的时间不该从网络预算里扣。
     */
    private suspend fun fetchOverNetwork(uri: URI): ByteArray? = remoteFetchGate.withPermit {
        val deadlineNanos = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 默认就是 true，写出来是因为它决定了 http→https 这类跳转要不要跟：
            // 平台不会把 https 重定向到 http，所以这里不需要额外判断。
            instanceFollowRedirects = true
            useCaches = false
            // 故意不调 setRequestProperty：见类注释「取的是什么」。
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return@withPermit fail("远端图片返回 HTTP $status：$uri")
            }
            // 声明的大小先挡一道：一个 500 MB 的响应不必先下载 8 MiB 才知道它太大。
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) {
                return@withPermit fail("远端图片声明 $declared 字节，超过 ${MAX_BYTES / 1024 / 1024} MiB 上限")
            }
            readBounded(connection.inputStream, declared, deadlineNanos)
        } catch (cancel: CancellationException) {
            // 与 `load` 里同一条规矩：取消照原样抛，别变成一条假的失败原因。
            throw cancel
        } catch (error: Exception) {
            return@withPermit fail("下载远端图片失败：${error::class.java.simpleName}: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 按 [MAX_BYTES] 与 [deadlineNanos] 读一个响应体，超了就 `null`（原因写进 [lastFailure]）。
     *
     * **每读一块都查取消，但 `read` 本身阻塞时取消进不去**（JDK 阻塞 I/O 的性质）：所以
     * 一次 `read` 的最坏耗时靠 [READ_TIMEOUT_MS] 兜底，不是靠取消。`declared` 只用来
     * 预分配缓冲区（-1 表示服务端没给长度），不参与判定——真话是实际读到的字节数。
     */
    private suspend fun readBounded(stream: InputStream, declared: Long, deadlineNanos: Long): ByteArray? {
        val capacity = if (declared > 0 && declared <= MAX_BYTES) declared.toInt() else READ_CHUNK_BYTES
        val out = ByteArrayOutputStream(capacity)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        stream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadlineNanos) {
                    return fail("远端图片超过 ${TOTAL_TIMEOUT_MS / 1000} 秒预算")
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BYTES) {
                    return fail("远端图片超过 ${MAX_BYTES / 1024 / 1024} MiB 上限")
                }
                out.write(buffer, 0, read)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return fail("远端图片是空响应")
        return bytes
    }

    // ---------------------------------------------------- remote disk cache ----

    /**
     * 这份 URL 在磁盘缓存里的文件，目录不存在就建。
     *
     * 文件名是 URL 的摘要而不是 URL：URL 里有 `/`、`?`、`&`，还有长度；而摘要还顺手
     * 避免了把"用户看了哪些 URL"明文留在文件名里。不带扩展名：`mimeOf` 先按魔数认，
     * 认得出 pi 的五种格式，扩展名在这里没用，带上反而会让同一张图的两种拼法变成两个文件。
     */
    private fun diskCacheFile(context: Context, uri: URI): File? = runCatching {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val directory = File(paths.home, DISK_CACHE_DIR)
        directory.mkdirs()
        File(directory, digestOf(uri.toASCIIString()))
    }.getOrNull()

    /** URL 的 SHA-256 前 16 字节，URL-safe base64、不去掉也不用 padding（22 个字符）。 */
    private fun digestOf(value: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
        0,
        16,
        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
    )

    /**
     * 把字节放进磁盘缓存，并把总大小压回 [MAX_DISK_BYTES] 以内。
     *
     * 先写一个 `.tmp` 再 rename，所以缓存目录里**只有完整的文件**——半份文件会被当成
     * 命中，于是那张图永远解不出、又永远不出网（失败**不写磁盘缓存**，但缓存命中不再出网）。
     * 临时名里带 `System.nanoTime()` 是防御性的：同一个 URL 的两个调用者现在由
     * [PiImageRequestPolicy] 去了重，但去重是调度层的事，写盘的原子性不该依赖它
     * （见类注释「同一帧的两个调用者不再各取一次」）。
     *
     * rename 失败就放弃这次缓存，**不做非原子写**：宁可不缓存，也不留一份可能是半份的
     * 文件。整段 `runCatching`：**写失败不致命**——字节已经在内存里，图照画，这次的代价
     * 只是下次还得再出一次网；一个只读的文件系统不该让正文图片消失。
     */
    private fun rememberOnDisk(file: File, bytes: ByteArray) = runCatching {
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        if (temporary.renameTo(file)) {
            file.setLastModified(System.currentTimeMillis())
        } else {
            temporary.delete()
        }
        evict(file.parentFile)
    }

    /**
     * 把缓存目录压回 [MAX_DISK_BYTES]：按 `lastModified` 从最旧的开始删。
     *
     * 只在**写入**时算一次，所以缓存命中是零额外成本；`lastModified` 由命中时的
     * `setLastModified` 顶新，这就是 LRU 的全部实现。没有索引文件，也就没有
     * "索引和文件对不上"这种状态。
     */
    private fun evict(directory: File?) {
        val files = directory?.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (oldest in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES) break
            total -= oldest.length()
            oldest.delete()
        }
    }

    // ------------------------------------------------------- guest to host ----

    /**
     * 一个 guest（或宿主）拼法可能指的每一个宿主文件，按优先级排序。
     *
     * 规则与理由在 [GuestPathMapping] 里；这里只负责提供这次安装的各个根，以及 link 的
     * 两种拼法。
     */
    private fun hostCandidates(context: Context, path: String): List<File> {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val roots = GuestPathRoots(
            workspaceBase = paths.workspaceBase.absolutePath,
            rootfs = paths.rootfs.absolutePath,
            agentDir = paths.agentDir.absolutePath,
            workspaceHost = runCatching { PtyLauncher.workspaceHost(context).absolutePath }.getOrNull(),
            storage = Environment.getExternalStorageDirectory().absolutePath,
        )
        // 百分号转义会活着穿过 markdown 解析器，所以两种拼法都试。
        val spellings = listOf(path, runCatching { Uri.decode(path) }.getOrDefault(path)).distinct()
        return GuestPathMapping.candidates(spellings, roots).map { File(it) }
    }

    /** `file:///a/b`、`file:/a/b`、`file://localhost/a/b`；远端 authority 一律拒绝。 */
    private fun fileUriToPath(link: String): String? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "file") return null
        val authority = uri.authority
        if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
        return uri.path ?: return null
    }

    // -------------------------------------------------------------- helpers ----

    /**
     * 先按魔数认，再认来源声明的类型，最后看扩展名。pi 认的集合是 jpg/png/gif/webp/bmp
     * （`docs/pi-android-app-design.md:507`），所以这里就是这几个签名。
     */
    private fun mimeOf(bytes: ByteArray, declared: String, fileName: String?): String {
        sniff(bytes)?.let { return it }
        declared.trim().takeIf { it.startsWith("image/") }?.let { return it }
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/*"
        }
    }

    private fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"

        bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"

        else -> null
    }

    /**
     * 记下原因并返回 `null`。泛型只为了让三个「读字节」的出口
     * （[GuestImage]、[readBounded] 的 `ByteArray?`、以及内联在分支里的返回）共用同一处
     * 「失败 = null + lastFailure」的规矩，而不是为每种类型各写一个函数。
     */
    private fun <T> fail(reason: String): T? {
        lastFailure = reason
        return null
    }
}
