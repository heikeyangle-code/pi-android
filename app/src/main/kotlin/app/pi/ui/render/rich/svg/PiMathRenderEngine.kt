package app.pi.ui.render.rich.svg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import app.pi.PiApplication
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/*
 * 这里的所有资源路径都与 `assets/rich/mathjax/` 绑死。`WebViewAssetLoader` 只注册
 * `/rich-mathjax/` 这一个前缀（和 Mermaid 的 `/rich-mermaid/` 是两个互不相干的前缀），
 * handler 又只放行白名单里的两个文件，所以页面能看到的资产面就是这两个文件——不是整个
 * assets，也不是 APK 里的任何其它东西。
 *
 * 为什么不复用 Mermaid 的前缀：`WebViewAssetLoader` 的路径 handler 是"前缀 → 目录"的
 * 一对一映射，两个引擎共用一个前缀要么互相覆盖 handler、要么把两个目录混在一个白名单里。
 * 各用各的前缀，一个引擎的资产永远不会被另一个引擎的页面请求到。
 */
private const val TAG = "PiMath"
private const val ASSET_URL_PREFIX = "/rich-mathjax/"
private const val ASSET_DIRECTORY = "rich/mathjax/"
private const val HOST_PAGE = "host.html"
private const val HOST_PAGE_URL = "https://appassets.androidplatform.net" + ASSET_URL_PREFIX + HOST_PAGE
private val MATH_ASSET_FILES = setOf(HOST_PAGE, "tex-svg.js")

/** 轮询间隔。只在有任务在飞的时候才发生，一次排版通常 1–10 次。 */
private const val POLL_INTERVAL_MS = 64L

/** 主题色只接受 `#RRGGBB`；`PiSvgTheme` 是纯字符串，契约不保证格式。 */
private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

/** `theme.foreground` 不合法时的兜底色（与 `PiPalette` 浅色主题的正文色一致）。 */
private const val FALLBACK_FOREGROUND = "#202124"

/**
 * `viewBox` 超过这个跨度就整体缩放（见 [fitViewBox]）。
 *
 * 取 4096 而不是直接贴着 `PiSvgSanitizer.MAX_DIMENSION`（8192）：MathJax 的 `viewBox` 单位
 * 是 **1/1000 em**（一条 8.7ex 宽的公式 `viewBox` 是 `0 -883.9 3847.1 894.9`），而 sanitizer
 * 的 `MAX_DIMENSION` 是给 Mermaid 那种"单位≈CSS px"的图设计的。一条 10em 宽的常见公式
 * 就是 10000 单位，会被 sanitizer 当成病态画布拒绝——缩放留出余量，让 20em 以上的公式也有
 * 地方放（缩放是等比的，屏幕上的像素结果完全不变，见 [fitViewBox] 的推导）。
 */
private const val MAX_VIEWBOX_SPAN = 4096.0

/**
 * ` ```math ` / `$…$` 的渲染引擎：**一个进程级共享的离屏 WebView 宿主 + 串行有界队列 +
 * LRU 缓存**，结构与 [PiMermaidRenderEngine] 逐项对齐（方案 §四、§十三、§十五）。
 *
 * 它实现 [PiSvgEngine]，所以两个引擎可以共用同一套契约、主题和容量纪律；区别只在宿主页
 * 和"输出怎么从 MathJax 的 DOM 里抠出来"。
 *
 * ## 线程模型（为什么所有状态都只属于主线程）
 *
 * `WebView` 的构造函数、`destroy()`、`evaluateJavascript()` 都要求在**创建它的线程**上调用。
 * 所以宿主在主线程建、主线程用、主线程销毁；队列、in-flight 表、LRU 也全部只在主线程读写
 * （`enqueue()` 通过 `Dispatchers.Main.immediate` 进入，其余入口都是 WebView 回调或主线程
 * scope 里的协程）。**主线程只做"派发 + 轮询 + 记账"**：JSON 解析、SVG 抽取、缩放、sanitizer、
 * 尺寸测量都在 `Dispatchers.Default` 上做（见 [accept]），重活不占 UI 线程。
 *
 * ## 队列：串行、有界、可合并、可取消
 *
 * - **串行**：同一时刻只有一个任务在飞。MathJax 的 `typesetPromise` 会改宿主页的 DOM
 *   （字体缓存、数学项记录），并行只会互相污染。
 * - **有界**：[MAX_PENDING] 个等待位。满了直接返回 [PiSvgResult.Failure]，调用方回退原文。
 * - **合并**：同一个 `cacheKey` 在队列里/在飞时，后来的调用挂到同一个 [MathTask] 上（各自
 *   `await` 同一个 `CompletableDeferred`）。`waiters` 记录还有几个人在等，最后一个离开才
 *   把任务标记为作废。`cacheKey` 已包含 `display`（见 [PiMathSvg.cacheKey]），所以合并
 *   不会把行内/块级混在一起。
 * - **取消**：调用方（Compose 的 `LaunchedEffect`）离开时 `await()` 抛 `CancellationException`，
 *   这里**不打断正在执行的 JS**（打断会污染共享宿主），只把任务标记为 `abandoned`：结果
 *   回来时**丢弃、也不进缓存**。还没开始的直接出队。
 *
 * ## 超时
 *
 * - [HOST_BOOT_TIMEOUT_MS]：从建 WebView 到页面 + `tex-svg.js` 就绪 + 文本度量探针通过。
 *   `tex-svg.js` 是 1.85 MB（选型文档 §3.2），比 Mermaid 的 5.5 MB 小，但 MathJax 的
 *   `startup.promise` 还要等 `document.readyState` 走完，所以仍按十秒级给。
 * - [RENDER_TIMEOUT_MS]：一次 `typesetPromise` 从提交到拿到结果。
 *
 * 超时**一律销毁宿主并重建**：一次挂死的 `typesetPromise` 会让同一渲染进程里后续所有
 * `evaluateJavascript` 也排队挂住，留在原地等于让整个队列永久卡死。`onRenderProcessGone`
 * 同理：返回 `true`（不崩 App）→ 丢弃宿主 → 下一任务重建。
 *
 * ## 宿主窗口：为什么同样不是"纯离屏"
 *
 * MathJax 的输出尺寸不是拍脑袋的常量：`createSVG` 用 `this.math.metrics.em` / `..ex` 把
 * TeX 的 em 换算成 `viewBox` 的 1/1000 单位，还用它算定界符的 `scale(...)`。这两个值来自
 * 真实布局（`getBBox`/`offsetWidth`）。一个从未 attach 的 WebView 在部分版本上量出零宽
 * 文本盒子，MathJax 不会报错，只会产出一个 `em=0` 的病态 SVG——所以这里沿用 Mermaid 的
 * 做法：挂进当前 Activity 的 `decorView`、给固定视口、再整体平移到窗口左侧之外。页面就绪
 * 时同样跑一次**文本度量探针**，量不出宽度就显式失败、回退原文，而不是画一张悄悄错位的
 * 公式。
 *
 * ## 安全边界（第 1 层在这里，第 2 层在 PiSvgSanitizer）
 *
 * - `javaScriptEnabled = true` 是唯一必须打开的开关；`allowFileAccess`/`allowContentAccess`
 *   /`allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs` 全部关闭。
 * - 网络边界由三件事承担：① `shouldInterceptRequest` 对**所有**非白名单 URL 直接返回空
 *   响应（请求根本到不了网络栈）；② `shouldOverrideUrlLoading` 一律返回 `true`；③ 宿主页
 *   的 CSP `default-src 'none'; connect-src 'none'`。特别地，MathJax 4 对冷僻字形会尝试
 *   动态加载 `@mathjax/mathjax-newcm-font/js/svg/dynamic/… .js`，这些请求**同样**被 ① 挡掉：
 *   常见公式的字形数据已经内联在 `tex-svg.js` 里（`defaultChars` 含 ASCII、希腊字母、
 *   `∑`/`∫`/`√`/`∞` 等），只有扩展拉丁、双线体等冷僻变体可能缺字形——缺字形只影响那个
 *   字符，不会变成网络请求，也不会越出白名单。
 * - 源码只经 `JSONObject.quote` 变成 JS 字符串字面量，再交给宿主页的 `textContent`，
 *   **没有字符串拼接进脚本、没有 `eval`**（MathJax 自己的 TeX 解析不算 eval）。
 * - 产出交给 [PiSvgSanitizer.sanitize]，未通过就是 [PiSvgResult.Failure]。
 * - 主题色只接受 `#RRGGBB`（[foregroundOf]），避免把任意字符串拼进 `fill`/`stroke`。
 *
 * ## 输出抽取（MathJax 与 Mermaid 最大的不同）
 *
 * MathJax 的类型是"容器 + SVG"：`<mjx-container …><svg …>…</svg></mjx-container>`，容器的
 * `innerHTML` 里还可能有 assistive MathML（已用 `enableAssistiveMml:false` 关掉）。而
 * [PiSvgSanitizer] 的输入契约是**完整单根 `<svg>` 文档**，它会拒绝 `<mjx-container>` 开头
 * 的任何东西。所以 [accept] 依次做：
 *
 * 1. 从 `innerHTML` 里抽出第一个完整的 `<svg>…</svg>`（按标签深度配对，不是正则截断）；
 * 2. 把开始标签里**属性值内部**的裸 `<` 转义成 `&lt;`（MathJax 会把原始 TeX 写进
 *    `data-latex="…"`，一条含小于号的公式会让 sanitizer 的标签解析直接失败，见
 *    [escapeLessThanInAttributes]）；
 * 3. 补回 `xmlns` / `xmlns:xlink`，并把裸 `href="#…"` 提升成 `xlink:href`——浏览器把 DOM
 *    再序列化成 HTML 时可能丢掉命名空间前缀，而 AndroidSVG 的 `use` 走的是 SVG 1.1 的
 *    `xlink:href`（选型文档 §3.3 第 5 条明确"裸 href 是否认需实测"，这里不走那条未验证路径）；
 * 4. `viewBox` 跨度超过 [MAX_VIEWBOX_SPAN] 时整体等比缩放（见 [fitViewBox]）；
 * 5. 把 `currentColor` 换成 `theme.foreground`（AndroidSVG 不解析 `currentColor`，缺省按
 *    黑色画——深色主题下就是黑字贴黑底，选型文档 R7）；
 * 6. 才交给 sanitizer，再从**已清洗**的 SVG 里取固有尺寸。
 *
 * ## 缓存
 *
 * LRU 同时限制**条数**（[MAX_CACHE_ENTRIES]）与**字节数**（[MAX_CACHE_BYTES]），键由
 * [PiMathSvg.cacheKey] 内容寻址。只缓存成功结果：失败可能是引擎当下状态（超时、宿主崩溃），
 * 缓存它等于让一次抖动永久占位。
 *
 * ## 已知未验证项（必须真机确认，见选型文档 §5）
 *
 * 1. 目标机型的 WebView 上 MathJax 4.1.3 能否冷启动并量出正确的 em/ex（探针会给出明确答案）；
 * 2. 2 MiB 级 `innerHTML` 通过 `evaluateJavascript` 回传的 IPC 上限（本实现按 2 MiB 截断）；
 * 3. MathJax SVG 的 `<use xlink:href>` + `<defs><path>` 在 AndroidSVG 1.4 上的绘制结果
 *    （选型文档称支持，但未在真机逐条对照）；
 * 4. `\text{中文}` 走设备 serif 字体的 CJK 覆盖（选型文档 D 组，唯一可能推翻选型的因素）。
 */
internal object PiMathRenderEngine : PiSvgEngine {

    /**
     * 源码上限，按**字符数**算（32 KiB）。MathJax 的输入几乎全是 ASCII，按字符算与按字节
     * 算最多差到 3 倍；这里的目的是"在把 2 MiB 字符串塞进 `evaluateJavascript` 之前挡住"，
     * 32 K 字符已经远超一条正常公式（实测语料最长 10 KB 级的 SVG 对应几百字符的 TeX）。
     */
    private const val MAX_SOURCE_CHARS = 32 * 1024

    /** 单个输出上限；与 [PiSvgSanitizer.MAX_BYTES] 一致，超了不送进 sanitizer。 */
    private const val MAX_SVG_BYTES = PiSvgSanitizer.MAX_BYTES

    /** 等待位（不含正在渲染的那个）。 */
    private const val MAX_PENDING = 8

    private const val MAX_CACHE_ENTRIES = 48
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

    /** 一次 `typesetPromise` 的上限。MathJax 单式实测 1–30 ms，给足宿主抖动余量。 */
    private const val RENDER_TIMEOUT_MS = 5_000L

    /** 冷启动（WebView + 1.85 MB bundle + startup + 探针）的上限。 */
    private const val HOST_BOOT_TIMEOUT_MS = 12_000L

    /** 传给宿主页的期望宽度上限（CSS px）。MathJax 不换行，它只用来定容器宽度。 */
    private const val MAX_RENDER_WIDTH_PX = 4_096

    /**
     * 宿主视口，单位是 **CSS px**（WebView 里 1 CSS px = 1 dp）。固定而不跟随每条公式：
     * MathJax 的输出尺寸由它自己的 `viewBox` 决定，容器宽度只影响 display 公式是否被
     * "缩到能放下"的兜底逻辑，而每次改宽度都要等一次布局 pass，得不偿失。
     */
    private const val HOST_VIEWPORT_CSS_WIDTH = 360.0
    private const val HOST_VIEWPORT_CSS_HEIGHT = 640.0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 等待中的任务，FIFO。只在主线程访问。 */
    private val pending = ArrayDeque<MathTask>()

    /** `cacheKey` → 正在排队或正在渲染的任务，用于同键合并。 */
    private val inFlight = HashMap<String, MathTask>()

    private val cache = MathSvgCache(MAX_CACHE_ENTRIES, MAX_CACHE_BYTES)

    private var running: MathTask? = null

    private var host: MathRenderHost? = null

    private var activityRef: WeakReference<Activity>? = null

    private var lifecycleRegistered = false
    private var memoryRegistered = false

    // ---------------------------------------------------------------- PiSvgEngine

    /**
     * 契约里的单参数入口。共享契约（[PiSvgRequest]）没有 `display` 字段，而这个方法又不能
     * 不带实现（[PiSvgEngine] 是 `internal interface`，实现类必须给），所以它按**块级**处理，
     * 并注明真正的入口是 [render] 的带 `display` 重载。
     *
     * 视图层不经过这里：`PiMathSvg.renderMathSvg` 显式传 `display`。留着它只是为了让
     * "以后有谁只拿一个 `PiSvgRequest` 来调 Math 引擎"时行为可预期（块级），而不是抛错。
     */
    override suspend fun render(request: PiSvgRequest): PiSvgResult = render(request, display = true)

    /**
     * 真正的入口：[PiMathSvg.renderMathSvg] 走这个重载。
     *
     * 参数校验放在这里而不是 [PiMathSvg]：那是所有调用方共用的地方，判断只写一次；而且
     * 引擎自己要能独立成立——即使有人绕过 `PiMathSvg` 直接拿 `PiSvgRequest` 调它，32 KiB
     * 上限、空源码、kind 检查照样生效。
     */
    suspend fun render(request: PiSvgRequest, display: Boolean): PiSvgResult {
        if (request.kind != PiSvgKind.Math) {
            return PiSvgResult.Failure("这个请求不是 Math 类型（实际是 ${request.kind.name}）")
        }
        if (request.source.isBlank()) return PiSvgResult.Failure("公式源码为空")
        if (request.source.length > MAX_SOURCE_CHARS) {
            return PiSvgResult.Failure("公式源码超过 ${MAX_SOURCE_CHARS / 1024} KiB 上限")
        }
        val key = request.cacheKey.ifBlank {
            // 调用方通常会带键；漏传时按同一份内容寻址规则兜底，而不是退化成"无缓存"。
            PiMathSvg.cacheKey(request.source, display, request.widthPx, request.theme)
        }
        return withContext(Dispatchers.Main.immediate) { enqueue(key, request, display) }
    }

    // ---------------------------------------------------------------- queue

    /**
     * 主线程上的入队/合并点。
     *
     * 顺序很重要：先查缓存（命中直接返回，连队列都不进），再查 in-flight（同键合并），
     * 最后才是队列容量检查。反过来会让"同一条公式的第二个请求"在队列满时被拒绝，尽管
     * 它本来可以免费共享第一个请求的结果。
     */
    private suspend fun enqueue(key: String, request: PiSvgRequest, display: Boolean): PiSvgResult {
        assertMainThread()
        cache.get(key)?.let { return it }
        inFlight[key]?.let { return awaitTask(it) }
        if (running != null && pending.size >= MAX_PENDING) {
            return PiSvgResult.Failure("公式渲染队列已满（$MAX_PENDING 个等待中）")
        }
        val task = MathTask(key, request, display)
        inFlight[key] = task
        pending.addLast(task)
        pump()
        return awaitTask(task)
    }

    /**
     * 等一个任务，并在**最后一个等待者离开**时把任务作废。
     *
     * `waiters` 必须在 `finally` 里减：调用方取消走的是异常路径，只有 `finally` 两个分支
     * 都覆盖得到。`task.result.isCompleted` 为真说明任务已经有定论，此时离开不算作废。
     */
    private suspend fun awaitTask(task: MathTask): PiSvgResult {
        task.waiters += 1
        try {
            return task.result.await()
        } finally {
            task.waiters -= 1
            if (task.waiters == 0 && !task.result.isCompleted) abandon(task)
        }
    }

    private fun abandon(task: MathTask) {
        if (task.started) {
            // 已经在排版：不打断 JS（会污染共享宿主），只丢弃结果、不写缓存。
            task.abandoned = true
            return
        }
        pending.remove(task)
        if (inFlight[task.key] === task) inFlight.remove(task.key)
        task.result.complete(PiSvgResult.Failure("公式渲染已取消"))
        pump()
    }

    private fun pump() {
        if (running != null) return
        while (true) {
            val next = pending.pollFirst() ?: return
            if (next.result.isCompleted) continue
            running = next
            next.started = true
            scope.launch { runTask(next) }
            return
        }
    }

    private fun finish(task: MathTask, result: PiSvgResult) {
        if (running === task) running = null
        if (inFlight[task.key] === task) inFlight.remove(task.key)
        // 只缓存"还有人要"的成功结果：调用方已经走了的那次（abandoned）是过期内容，
        // 让它进缓存只会把真正要看的公式挤出去。
        if (result is PiSvgResult.Ok && !task.abandoned && task.waiters > 0) {
            cache.put(task.key, result)
        }
        task.result.complete(result)
        pump()
    }

    // ---------------------------------------------------------------- one task

    private suspend fun runTask(task: MathTask) {
        // 宿主在这次渲染进行中被系统回收（内存紧张时主动释放）或崩了：这不是这条内容的错，
        // 重建一次宿主再试一次；第二次还失效才报失败。以前直接报「宿主失效」，那一格就
        // 一直停在源码上，直到滚出去再滚回来。
        var retried = false
        var result: PiSvgResult
        while (true) {
            var hostLost = false
            result = try {
            val renderHost = ensureHost()
            val rawReply = withTimeout(RENDER_TIMEOUT_MS) {
                renderHost.render { taskId -> renderScript(taskId, task.request, task.display) }
            }
            accept(rawReply, task.request)
        } catch (timeout: TimeoutCancellationException) {
            disposeHost("渲染超时")
            PiSvgResult.Failure("公式渲染超时（${RENDER_TIMEOUT_MS} ms）")
        } catch (gone: MathHostLostException) {
            disposeHost("宿主失效：${gone.message}")
            hostLost = true
            PiSvgResult.Failure("公式渲染宿主失效：${gone.message ?: "未知原因"}")
        } catch (unavailable: MathHostUnavailableException) {
            PiSvgResult.Failure(unavailable.message ?: "公式渲染宿主不可用")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PiSvgResult.Failure("公式渲染失败：${error.message ?: error::class.java.simpleName}")
        }
            if (hostLost && !retried) {
                retried = true
                continue
            }
            break
        }
        finish(task, result)
    }

    /**
     * 拿一个已经就绪的宿主；没有就建一个并等它加载完。
     *
     * 冷启动失败要区分两种：加载**超时**（销毁重建）与加载**报错**（同样销毁，因为半截
     * 页面没有用）。两者都转成 [MathHostUnavailableException]，由 [runTask] 统一变成
     * `Failure`，而不是把异常丢给视图层。
     */
    private suspend fun ensureHost(): MathRenderHost {
        val existing = host
        if (existing != null && existing.isUsable()) return existing
        disposeHost("重建宿主")

        val created = try {
            createHost()
        } catch (error: Throwable) {
            throw MathHostUnavailableException(
                "无法创建公式渲染宿主：${error.message ?: error::class.java.simpleName}",
            )
        }
        host = created
        try {
            created.load()
        } catch (error: Throwable) {
            disposeHost("宿主加载失败")
            throw MathHostUnavailableException(
                "公式宿主加载失败：${error.message ?: error::class.java.simpleName}",
            )
        }

        val booted = try {
            withTimeoutOrNull(HOST_BOOT_TIMEOUT_MS) { created.awaitLoaded() }
        } catch (gone: MathHostLostException) {
            throw gone
        } catch (unavailable: MathHostUnavailableException) {
            disposeHost("宿主不可用")
            throw unavailable
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            disposeHost("宿主加载失败")
            throw MathHostUnavailableException(
                "公式宿主加载失败：${error.message ?: error::class.java.simpleName}",
            )
        }
        if (booted == null) {
            disposeHost("宿主加载超时")
            throw MathHostUnavailableException("公式宿主在 ${HOST_BOOT_TIMEOUT_MS} ms 内未就绪")
        }
        return created
    }

    private fun createHost(): MathRenderHost {
        assertMainThread()
        registerLifecycleCallbacks()
        registerMemoryCallbacks()
        val activity = currentActivity()
        val context: Context = activity ?: PiApplication.context
        val density = context.resources.displayMetrics.density
        val viewWidthPx = (HOST_VIEWPORT_CSS_WIDTH * density).roundToInt().coerceAtLeast(1)
        val viewHeightPx = (HOST_VIEWPORT_CSS_HEIGHT * density).roundToInt().coerceAtLeast(1)
        val webView = WebView(context)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(ASSET_URL_PREFIX, MathAssetHandler(context))
            .build()
        return MathRenderHost(webView, activity, assetLoader, viewWidthPx, viewHeightPx) { gone, reason ->
            handleHostGone(gone, reason)
        }
    }

    private fun disposeHost(reason: String) {
        val current = host ?: return
        host = null
        current.dispose()
        Log.d(TAG, "公式宿主已释放：$reason")
    }

    /**
     * `onRenderProcessGone` 的收尾。**不能**在回调里 `destroy()`：方法契约要求先移出视图、
     * 并让回调正常返回 `true`，所以在主线程消息队列里延后一拍销毁；`host` 立刻置空，
     * 下一个任务会重建。
     */
    private fun handleHostGone(gone: MathRenderHost, reason: String) {
        if (host === gone) host = null
        mainHandler.post { gone.dispose() }
        Log.w(TAG, "公式宿主失效：$reason")
    }

    // ---------------------------------------------------------------- activity

    private fun registerLifecycleCallbacks() {
        if (lifecycleRegistered) return
        val application = applicationOrNull() ?: return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    activityRef = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (activityRef?.get() === activity) activityRef = null
                    val attached = host
                    if (attached != null && attached.activity === activity) {
                        host = null
                        attached.dispose()
                    }
                }
            },
        )
        lifecycleRegistered = true
    }

    /**
     * 当前可用于挂窗口的 Activity。
     *
     * 先看生命周期回调记下的弱引用（覆盖"注册之后又 resume 过"的情况），再用一次
     * **best-effort** 反射兜住"注册之前就已经 resume"的首次渲染。反射失败在预期之内：
     * 非 SDK 接口限制、字段改名、或系统实现不同都会让这里返回 `null`，此时宿主退化为
     * 纯离屏布局，由文本度量探针决定它到底能不能用。
     */
    /**
     * 系统说内存紧张时**主动**关掉 WebView 宿主。
     *
     * 一个离屏 WebView 不是"一个 View"那么便宜：它带一个 Chromium 渲染进程（也是本 App
     * 进程树里的一个子进程）和几十 MB 的堆。这台设备上同时活着的 Mermaid 与 MathJax 两个
     * 宿主，在跑 `npm run build` 的时候正好站在系统的内存线上 —— 而 Android 的回收不会先
     * 问你，它直接杀进程树（用户看到的就是「引擎被系统结束了」那一屏）。
     *
     * `onTrimMemory` 是系统在动手**之前**给的机会：RUNNING_LOW 以上（含 CRITICAL、
     * COMPLETE、以及后台档位）就把宿主释放掉，下一次有围栏要画时再懒重建（约一秒）。
     * 释放比被杀好：对话、引擎和正在跑的命令都还在。
     */
    private fun registerMemoryCallbacks() {
        if (memoryRegistered) return
        val application = applicationOrNull() ?: return
        memoryRegistered = true
        application.registerComponentCallbacks(
            object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    // 只看"系统现在真的缺内存"的那几档：RUNNING_LOW(10)、RUNNING_CRITICAL(15)、
                    // 以及后台档位 BACKGROUND(40)/MODERATE(60)/COMPLETE(80)。
                    // **跳过 UI_HIDDEN(20)**：它只表示"App 切到后台"，几乎每次切应用都发，
                    // 在那里拆宿主会让用户切回来时看到一次"源码"闪一下，而当时并不缺内存。
                    val pressured = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                        level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                    if (pressured) disposeHost("系统内存紧张（onTrimMemory=$level）")
                }

                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

                override fun onLowMemory() {
                    disposeHost("系统内存不足（onLowMemory）")
                }
            },
        )
    }

    private fun currentActivity(): Activity? {
        val tracked = activityRef?.get()
        if (tracked != null && !tracked.isFinishing && !tracked.isDestroyed) return tracked
        return reflectCurrentActivity()
    }

    private fun reflectCurrentActivity(): Activity? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        val records = activityThreadClass.getDeclaredField("mActivities").let { field ->
            field.isAccessible = true
            field.get(activityThread) as? Map<*, *>
        }
        records?.values?.filterNotNull()?.firstNotNullOfOrNull { record ->
            val activity = record.javaClass.getDeclaredField("activity").let { field ->
                field.isAccessible = true
                field.get(record) as? Activity
            }
            activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        }
    } catch (error: Throwable) {
        Log.d(TAG, "读取当前 Activity 失败，宿主将走离屏布局：${error.message}")
        null
    }

    private fun applicationOrNull(): Application? = runCatching { PiApplication.instance }.getOrNull()

    // ---------------------------------------------------------------- bridge

    /**
     * 拼 `evaluateJavascript` 的脚本。
     *
     * TeX 一律经 [JSONObject.quote] 变成 JS 字符串字面量：它转义引号、反斜杠、控制字符，
     * 并把 U+2000–U+2100 区间（含 JSON 合法但旧版 JS 字符串非法的 U+2028/U+2029）写成
     * `\uXXXX`。所以模型源码里的 `"`、换行、`$$`、`</script>` 都不会越出字符串——TeX 本身
     * 会包含大量反斜杠，这是必须的，不是防御性冗余。
     */
    private fun renderScript(taskId: Int, request: PiSvgRequest, display: Boolean): String =
        "window.__piRender(" +
            taskId + "," +
            quote(request.source) + "," +
            (if (display) "true" else "false") +
            ")"

    private fun quote(value: String): String = JSONObject.quote(value)

    /**
     * 把 JS 的回复变成结果。**运行在 `Dispatchers.Default` 上**：`JSONObject` 解析 2 MiB
     * 字符串、抽 `<svg>`、等比缩放、`currentColor` 替换、sanitizer 的逐字符扫描、正则取尺寸，
     * 都不该占着主线程。
     */
    private suspend fun accept(rawReply: String, request: PiSvgRequest): PiSvgResult =
        withContext(Dispatchers.Default) {
            val reply = try {
                JSONObject(rawReply)
            } catch (error: JSONException) {
                return@withContext PiSvgResult.Failure("公式宿主返回了无法解析的结果")
            }
            if (!reply.optBoolean("ok", false)) {
                val reason = reply.optString("error").ifBlank { "公式渲染失败" }
                return@withContext PiSvgResult.Failure(reason)
            }
            // 宿主回的是 `<mjx-container>` 的 innerHTML，不是 SVG 文档本身：MathJax 4 的
            // 输出形态就是这样（选型文档 §3.3）。这里先按标签配对抽出来，别的内容全部丢弃。
            val markup = reply.optString("svg")
            when {
                markup.isEmpty() -> PiSvgResult.Failure("MathJax 没有产出 SVG")
                markup.length > MAX_SVG_BYTES -> PiSvgResult.Failure("公式输出超过 2 MiB 上限")
                else -> {
                    val document = extractSvgDocument(markup)
                    if (document == null) {
                        PiSvgResult.Failure("MathJax 的输出里没有完整的 <svg> 文档")
                    } else {
                        val prepared = fitViewBox(
                            ensureNamespaces(
                                promoteHrefAttributes(escapeLessThanInAttributes(document)),
                            ),
                        )
                        val colored = replaceCurrentColor(prepared, foregroundOf(request.theme))
                        when (val sanitized = PiSvgSanitizer.sanitize(colored)) {
                            is PiSvgSanitizeResult.Rejected ->
                                PiSvgResult.Failure("公式 SVG 未通过安全校验：${sanitized.reason}")

                            is PiSvgSanitizeResult.Ok -> {
                                val size = mathSvgSize(sanitized.svg)
                                if (size == null) {
                                    PiSvgResult.Failure("公式 SVG 缺少可用的 viewBox，无法确定固有尺寸")
                                } else {
                                    PiSvgResult.Ok(sanitized.svg, size.first, size.second)
                                }
                            }
                        }
                    }
                }
            }
        }

    /** 主题前景色；只接受 `#RRGGBB`，其余一律回落到 [FALLBACK_FOREGROUND]。 */
    private fun foregroundOf(theme: PiSvgTheme): String {
        val candidate = theme.foreground.trim()
        return if (HEX_COLOR.matches(candidate)) candidate else FALLBACK_FOREGROUND
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PiMathRenderEngine 只能在主线程访问"
        }
    }
}

// ---------------------------------------------------------------- task / cache

private class MathTask(
    val key: String,
    val request: PiSvgRequest,
    val display: Boolean,
) {
    val result = CompletableDeferred<PiSvgResult>()

    /** 当前还有几个调用方在 `await`；最后一个离开时任务作废。 */
    var waiters = 0

    var started = false

    /** 调用方已全部离开：结果回来时丢弃，也不写缓存。 */
    var abandoned = false
}

/**
 * 条数 + 字节双上限的访问序 LRU（与 `PiMermaidRenderEngine.kt` 的 `SvgCache` 同一种纪律）。
 *
 * 只在主线程访问。`LinkedHashMap(accessOrder = true)` 连 `get` 都会改结构，所以这个类
 * 不声明线程安全，也不假装线程安全。
 */
private class MathSvgCache(private val maxEntries: Int, private val maxBytes: Int) {

    private class Held(val result: PiSvgResult.Ok, val bytes: Int)

    private val entries = LinkedHashMap<String, Held>(16, 0.75f, true)

    private var bytes = 0

    fun get(key: String): PiSvgResult.Ok? = entries[key]?.result

    fun put(key: String, result: PiSvgResult.Ok) {
        if (maxEntries <= 0 || maxBytes <= 0) return
        // 按字符数近似字节数：MathJax 输出以 ASCII 的 path/`use` 为主，近似足够定界，
        // 又省掉一次整串 UTF-8 编码（2 MiB 字符串的编码本身就不该在插入路径上做两遍）。
        val weight = result.svg.length
        if (weight <= 0 || weight > maxBytes) return
        entries.remove(key)?.let { previous -> bytes -= previous.bytes }
        entries[key] = Held(result, weight)
        bytes += weight
        while (entries.size > maxEntries || bytes > maxBytes) {
            val eldest = entries.keys.firstOrNull() ?: break
            val evicted = entries.remove(eldest) ?: break
            bytes -= evicted.bytes
        }
    }
}

// ---------------------------------------------------------------- host

private class MathHostLostException(message: String) : Exception(message)

private class MathHostUnavailableException(message: String) : Exception(message)

/**
 * 一个共享的离屏 WebView 宿主。**所有方法都必须在主线程调用**（由引擎保证）。
 *
 * 生命周期：建 WebView → 配置 → 挂窗口/离屏布局 → `loadUrl(host.html)` → 等
 * `onPageFinished` → 量文本探针 → 可以排版 → （崩溃/超时/Activity 销毁）→ [dispose]。
 *
 * `dispose()` 之后这个对象不可再用：`webView` 置空，`dead = true`，任何在飞的任务都会在
 * 下一次轮询时抛 [MathHostLostException]。
 */
private class MathRenderHost(
    initialWebView: WebView,
    val activity: Activity?,
    private val assetLoader: WebViewAssetLoader,
    private val viewWidthPx: Int,
    private val viewHeightPx: Int,
    private val onHostGone: (MathRenderHost, String) -> Unit,
) {

    private var webView: WebView? = initialWebView

    private var loaded = false
    private var loadFailure: String? = null
    private var loadWaiter: CancellableContinuation<Unit>? = null
    private var readyProbed = false
    private var dead = false
    private var deadReason: String? = null
    private var nextTaskId = 1

    init {
        initialWebView.webViewClient = Client()
        configure(initialWebView)
        attachOrLayout(initialWebView)
    }

    /**
     * 开始加载宿主页。单独一步（而不是塞在 `init` 里）：挂窗口已经发生，如果加载这一步
     * 抛错，引擎要能拿到宿主引用去 [dispose]，否则会漏一个挂在 Activity 上的 WebView。
     */
    fun load() {
        val view = webView ?: throw MathHostLostException("公式宿主 WebView 已释放")
        view.loadUrl(HOST_PAGE_URL)
    }

    fun isUsable(): Boolean = !dead && webView != null

    /**
     * 挂进当前 Activity 的 `decorView`，再把整个 WebView 平移到窗口左侧之外。
     *
     * 不挂 `android.R.id.content` 才更安全：`decorView` 恒为 `FrameLayout`，不会因为
     * 内容根被换掉（Compose 的 `ComposeView`、`setContentView` 多次调用）而失配。挂上之后
     * 还要**再显式 layout 一次**，这样页面在第一次布局 pass 之前就有确定的视口尺寸，
     * 不必等下一次 vsync 才去 load。
     *
     * 没有 Activity（后台、或反射失败）时退化为纯离屏：手动 measure/layout 到同一尺寸。
     * 这条路的文本度量能力由 `host.html` 的探针判定，量不出来就显式失败。
     */
    private fun attachOrLayout(view: WebView) {
        val owner = activity
        if (owner != null) {
            val parent = owner.window?.decorView as? ViewGroup
            if (parent != null) {
                try {
                    parent.addView(view, FrameLayout.LayoutParams(viewWidthPx, viewHeightPx))
                    view.translationX = -(viewWidthPx + 1).toFloat()
                    layoutNow(view)
                    return
                } catch (error: RuntimeException) {
                    Log.w(TAG, "公式宿主无法挂到 Activity 窗口，退化为离屏布局", error)
                }
            }
        }
        layoutNow(view)
    }

    private fun layoutNow(view: WebView) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(viewWidthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(viewHeightPx, View.MeasureSpec.EXACTLY)
        try {
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, viewWidthPx, viewHeightPx)
        } catch (error: RuntimeException) {
            Log.w(TAG, "公式宿主离屏布局失败", error)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        // 它永远不该拿到焦点/无障碍焦点：用户看不见它，也不该被它"跳"走。
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val settings = view.settings
        // 唯一必须打开的能力：MathJax 是 JS。
        settings.setJavaScriptEnabled(true)
        settings.setAllowFileAccess(false)
        settings.setAllowContentAccess(false)
        settings.setAllowFileAccessFromFileURLs(false)
        settings.setAllowUniversalAccessFromFileURLs(false)
        settings.setDomStorageEnabled(false)
        settings.setDatabaseEnabled(false)
        settings.setSupportZoom(false)
        settings.setSupportMultipleWindows(false)
        settings.setMediaPlaybackRequiresUserGesture(true)
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE)
    }

    // ------------------------------------------------------------ readiness

    suspend fun awaitLoaded() {
        if (dead) throw MathHostLostException(deadReason ?: "公式宿主已失效")
        loadFailure?.let { throw MathHostUnavailableException(it) }
        if (!loaded) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val failure = loadFailure
                if (failure != null || dead) {
                    continuation.resumeWithException(
                        MathHostUnavailableException(failure ?: deadReason ?: "公式宿主已失效"),
                    )
                } else {
                    loadWaiter = continuation
                    continuation.invokeOnCancellation {
                        if (loadWaiter === continuation) loadWaiter = null
                    }
                }
            }
        }
        if (dead) throw MathHostLostException(deadReason ?: "公式宿主已失效")
        if (!readyProbed) {
            val view = webView ?: throw MathHostLostException("公式宿主 WebView 已释放")
            val reply = evaluate(view, "window.__piReady()")
            val status = try {
                reply?.let { JSONObject(it) }
            } catch (error: JSONException) {
                null
            }
            if (status == null || !status.optBoolean("ok", false)) {
                throw MathHostUnavailableException(
                    "MathJax 宿主页面未就绪：" + (status?.optString("error") ?: "无回复"),
                )
            }
            // 探针结果决定这台宿主到底能不能用：MathJax 的 em/ex 来自真实布局，
            // 量不出文本就会产出 viewBox 病态的 SVG（见类注释"宿主窗口"一节）。
            if (!status.optBoolean("metrics", false)) {
                throw MathHostUnavailableException(
                    "公式宿主无法测量文本（离屏布局未生效），已回退源码",
                )
            }
            readyProbed = true
        }
    }

    // ------------------------------------------------------------ render

    /**
     * 提交一次排版并轮询到结果，返回 JS 回复的原始 JSON。
     *
     * 解析/校验在引擎侧（`Dispatchers.Default`）做，这里只负责"提交 + 等到有回复"。
     * 轮询而不是等回调：`evaluateJavascript` 只能同步返回值，没有 Promise 直通；用
     * `WebMessagePort` 又要处理 origin 与丢首帧消息的握手，这里宁可多几次廉价调用。
     */
    suspend fun render(scriptFor: (Int) -> String): String {
        if (dead) throw MathHostLostException(deadReason ?: "公式宿主已失效")
        awaitLoaded()
        val view = webView ?: throw MathHostLostException("公式宿主 WebView 已释放")
        val taskId = nextTaskId++
        view.evaluateJavascript(scriptFor(taskId), null)
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (dead) throw MathHostLostException(deadReason ?: "公式宿主已失效")
            val reply = evaluate(view, "window.__piPoll($taskId)")
            if (reply != null && reply != "null" && reply.isNotEmpty()) return reply
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            val answered = AtomicBoolean(false)
            try {
                view.evaluateJavascript(script) { value ->
                    if (answered.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(value)
                    }
                }
            } catch (error: RuntimeException) {
                if (answered.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(
                        MathHostLostException("evaluateJavascript 失败：${error.message}"),
                    )
                }
            }
        }

    // ------------------------------------------------------------ teardown

    fun dispose() {
        dead = true
        val view = webView
        webView = null
        resumeWaiter(MathHostLostException("公式宿主已释放"))
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        try {
            view.stopLoading()
        } catch (error: RuntimeException) {
            Log.w(TAG, "公式宿主 stopLoading 失败", error)
        }
        try {
            // 先摘掉客户端，再 destroy：destroy 之后不允许再回调进我们的状态机。
            view.webViewClient = WebViewClient()
        } catch (error: RuntimeException) {
            Log.w(TAG, "公式宿主解绑 WebViewClient 失败", error)
        }
        try {
            view.destroy()
        } catch (error: RuntimeException) {
            Log.w(TAG, "公式宿主 destroy 失败", error)
        }
    }

    // ------------------------------------------------------------ client callbacks

    private fun markLoaded() {
        loaded = true
        val waiter = loadWaiter ?: return
        loadWaiter = null
        if (waiter.isActive) waiter.resume(Unit)
    }

    private fun markLoadFailure(reason: String) {
        if (loadFailure == null) loadFailure = reason
        val waiter = loadWaiter ?: return
        loadWaiter = null
        if (waiter.isActive) {
            waiter.resumeWithException(MathHostUnavailableException(reason))
        }
    }

    private fun resumeWaiter(error: Exception) {
        val waiter = loadWaiter ?: return
        loadWaiter = null
        if (waiter.isActive) waiter.resumeWithException(error)
    }

    private fun markHostGone(reason: String) {
        dead = true
        if (deadReason == null) deadReason = reason
        resumeWaiter(MathHostLostException(reason))
        onHostGone(this, reason)
    }

    private inner class Client : WebViewClient() {

        /**
         * 所有子资源都从这里过。命中白名单资产 → 本地流；其余一律返回空响应，**绝不**落回
         * 网络栈（`default-src 'none'` 的 CSP 是第二道）。MathJax 对冷僻字形的动态字体
         * 请求也走这里，因此同样被挡，不会变成一次网络访问。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return mathBlockedResponse()
            val response = try {
                assetLoader.shouldInterceptRequest(url)
            } catch (error: RuntimeException) {
                Log.w(TAG, "公式资产拦截失败：$url", error)
                null
            }
            return response ?: mathBlockedResponse()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = true

        override fun onPageFinished(view: WebView?, url: String?) {
            markLoaded()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                markLoadFailure(
                    "MathJax 宿主页面加载失败（${error?.errorCode}）：${error?.description}",
                )
            }
        }

        /**
         * 返回 `true` 表示"我们处理了"：不返回会按文档让 App 崩溃/被杀。真正的销毁延后到
         * 主线程消息里（见引擎 `handleHostGone`）。
         */
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            markHostGone("公式渲染进程崩溃（crashed=${detail?.didCrash() ?: false}）")
            return true
        }
    }
}

private fun mathBlockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/**
 * `/rich-mathjax/…` → `assets/rich/mathjax/…`，**只放行两个文件**。
 *
 * 不直接用 `WebViewAssetLoader.AssetsPathHandler`：它把整个 assets 暴露在一个前缀下，
 * 而这里的要求是"只加载 assets/rich/mathjax/"。白名单 + 目录前缀让越界路径（`..`、
 * 绝对路径、URL 编码）没有落点。
 *
 * 注意：`handle()` 由 WebView 在**工作线程**调用，所以这里不能碰任何视图状态；只读
 * `AssetManager` 与常量，天然线程安全。
 */
private class MathAssetHandler(private val context: Context) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.removePrefix("/")
        if (name !in MATH_ASSET_FILES) {
            Log.w(TAG, "公式宿主：拒绝未列入白名单的资产路径 $path")
            return null
        }
        val stream: InputStream = try {
            context.assets.open(ASSET_DIRECTORY + name, AssetManager.ACCESS_STREAMING)
        } catch (error: IOException) {
            Log.w(TAG, "公式宿主：资产 $name 读取失败", error)
            return null
        }
        return WebResourceResponse(mathMimeTypeOf(name), "utf-8", stream)
    }
}

private fun mathMimeTypeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

// ---------------------------------------------------------------- svg post-processing

private val SVG_OPEN_TAG = Regex("""<svg(?=[\s/>])""", RegexOption.IGNORE_CASE)
private val SVG_ANY_TAG = Regex("""</?svg(?=[\s/>])""", RegexOption.IGNORE_CASE)
private val SVG_ROOT_TAG = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE)
private val VIEW_BOX_ATTR = Regex("""\bviewBox\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val WIDTH_ATTR = Regex("""\swidth\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val HEIGHT_ATTR = Regex("""\sheight\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val XMLNS_ATTR = Regex("""(?:\s|^)xmlns\s*=""", RegexOption.IGNORE_CASE)
private val XMLNS_XLINK_ATTR = Regex("""(?:\s|^)xmlns:xlink\s*=""", RegexOption.IGNORE_CASE)
private val CURRENT_COLOR = Regex("""currentColor""", RegexOption.IGNORE_CASE)
private val BARE_HREF_ATTR = Regex("""(\s)href\s*=\s*(["'])(#[^"']*)\2""")
private val VIEWBOX_SPLIT = Regex("""[\s,]+""")

/**
 * 从 MathJax 容器的 `innerHTML` 里抽出第一个**完整**的 `<svg>…</svg>` 文档。
 *
 * 为什么不用一条正则：`<svg>` 里可以再嵌 `<svg>`（SVG2 允许），而且属性值里可能出现
 * `>`（`style` 里没有，但 `aria-label` 可以）。所以这里按标签配对数深度，并且用
 * [mathTagEnd] 跳过引号里的 `>`。`<svg/>`（空文档）直接判失败——没有内容的 SVG
 * 交给 sanitizer 也只会得到一份画不出东西的结果。
 */
private fun extractSvgDocument(markup: String): String? {
    val open = SVG_OPEN_TAG.find(markup) ?: return null
    val start = open.range.first
    val startEnd = mathTagEnd(markup, start) ?: return null
    if (mathTagIsSelfClosing(markup, startEnd)) return null
    var depth = 1
    var cursor = startEnd + 1
    while (cursor < markup.length) {
        val tag = SVG_ANY_TAG.find(markup, cursor) ?: return null
        val tagStart = tag.range.first
        val tagEnd = mathTagEnd(markup, tagStart) ?: return null
        if (markup[tagStart + 1] == '/') {
            depth -= 1
            if (depth == 0) return markup.substring(start, tagEnd + 1)
        } else if (!mathTagIsSelfClosing(markup, tagEnd)) {
            depth += 1
        }
        cursor = tagEnd + 1
    }
    return null
}

/** 标签结束 `>` 的下标，跳过引号里的 `>`。开始/结束标签都适用。 */
private fun mathTagEnd(text: String, start: Int): Int? {
    var quote: Char? = null
    var index = start + 1
    while (index < text.length) {
        val char = text[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '>' -> return index
        }
        index++
    }
    return null
}

private fun mathTagIsSelfClosing(text: String, gtIndex: Int): Boolean {
    var index = gtIndex - 1
    while (index >= 0 && text[index].isWhitespace()) index--
    return index >= 0 && text[index] == '/'
}

/**
 * 把开始标签里**属性值内部**的裸 `<` 转义成 `&lt;`。
 *
 * 为什么需要：XML 属性值里不允许裸 `<`，而 [PiSvgSanitizer] 的 `parseStartTag` 遇到这种
 * 值会整条属性解析失败、整份 SVG 以"开始标签格式错误"拒绝。MathJax 会把原始 TeX 写进
 * `data-latex="…"`，所以一条含小于号的公式（`x < 0`、`\text{a < b}`）正好会踩到这一点。
 * 浏览器把 DOM 序列化回 `innerHTML` 时按 HTML 规范会把 `<` 写成 `&lt;`（已用 Chromium
 * 同款实现核对过），但那是**序列化器**的义务，不是我们这条管线能替它保证的；Node 侧的
 * MathJax 适配器就确实会漏转义（实测 `data-latex="x < 0"`），换一个 WebView 版本也可能。
 *
 * 安全性：这个函数只会改写**本来就非法**的字符，对合法 XML 是恒等变换——它只能把一份
 * 解析不了的文档变成能解析的，不会把好文档改坏。它不碰文本节点里的 `<`（那由序列化器
 * 负责转义，且 `<text>` 的内容在 MathJax 输出里本来就是转义过的）。
 */
private fun escapeLessThanInAttributes(svg: String): String {
    if (svg.indexOf('<') < 0) return svg
    val out = StringBuilder(svg.length + 16)
    var quote: Char? = null
    var inTag = false
    for (char in svg) {
        if (!inTag) {
            if (char == '<') inTag = true
            out.append(char)
            continue
        }
        if (quote != null) {
            when {
                char == quote -> {
                    quote = null
                    out.append(char)
                }

                char == '<' -> out.append("&lt;")
                else -> out.append(char)
            }
            continue
        }
        when (char) {
            '"', '\'' -> {
                quote = char
                out.append(char)
            }

            '>' -> {
                inTag = false
                out.append(char)
            }

            else -> out.append(char)
        }
    }
    return out.toString()
}

/**
 * 把裸 `href="#…"` 补成 `xlink:href="#…"`。
 *
 * MathJax 4 用 `setAttributeNS(xlink, "href", …)` 写引用（`useXlink:true` 是默认值）。DOM
 * 再被序列化成 HTML 时，带命名空间但没有前缀的属性会退化成裸 `href`。AndroidSVG 1.4 的
 * `use` 解析走 SVG 1.1 的 `xlink:href`，裸 `href` 是否被接受没有实测过（选型文档 §3.3
 * 第 5 条）。补回前缀 + 声明命名空间，走的是确定被支持的那条路。
 *
 * 只改**文档片段内**的引用（值以 `#` 开头）；外部 URL 的 `href` 原样留着，交给 sanitizer
 * 整条丢弃。`xlink:href` 本身不会再被匹配（`href` 前面是 `:`，不是空白）。
 */
private fun promoteHrefAttributes(svg: String): String {
    if (svg.indexOf("href") < 0) return svg
    return BARE_HREF_ATTR.replace(svg) { match ->
        val prefix = match.groupValues[1]
        val quote = match.groupValues[2]
        val target = match.groupValues[3]
        prefix + "xlink:href=" + quote + target + quote
    }
}

/**
 * 保证根标签带 `xmlns`（缺了它 AndroidSVG 就不是在解析 SVG 文档），并在正文用到
 * `xlink:` 时补 `xmlns:xlink`。MathJax 自己会写这两个属性，这里是给"序列化丢了前缀/
 * 命名空间"留的兜底，代价是两次不起眼的正则判断。
 */
private fun ensureNamespaces(svg: String): String {
    val startEnd = mathTagEnd(svg, 0) ?: return svg
    val root = svg.substring(0, startEnd + 1)
    var additions = ""
    if (!XMLNS_ATTR.containsMatchIn(root)) {
        additions += " xmlns=\"http://www.w3.org/2000/svg\""
    }
    if (!XMLNS_XLINK_ATTR.containsMatchIn(root) && svg.contains("xlink:")) {
        additions += " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
    }
    if (additions.isEmpty()) return svg
    return svg.substring(0, startEnd) + additions + svg.substring(startEnd)
}

/**
 * 把 MathJax 的 `viewBox` 归一化到 [MAX_VIEWBOX_SPAN] 以内，**在屏幕上等价**。
 *
 * 为什么需要：MathJax 的坐标系是 1/1000 em（实测 `width="8.7ex"` 对应
 * `viewBox="0 -883.9 3847.1 894.9"`），而 [PiSvgSanitizer] 的 `MAX_DIMENSION`（8192）是给
 * Mermaid 那种"单位≈px"的图定的。换算下来，一条超过约 8 em（十几到二十个字符）的公式就会
 * 被当成病态画布拒绝——这会把绝大多数块级公式挡在门外。
 *
 * 处理方式是把整棵内容包进 `<g transform="scale(q)">`、并把 `viewBox` 的四个数同乘 `q`
 * （`q < 1`）。设原 `viewBox=(x,y,w,h)`、视口宽 `V`，则原映射是
 * `screen = (u - x) * V / w`；平移量 `q` 之后是 `(q*u - q*x) * V / (q*w)`，与前者逐像素相等。
 * 描边宽度、`transform`、`<use>` 都跟着同一个 `q` 缩放，所以**渲染结果不变**；变的只是
 * 坐标的书写尺度，节点数、字节数、嵌套深度都不受影响（sanitizer 的其它上限照常生效）。
 *
 * 只在超限时才做（常见公式走不到这里），并且只在能完整识别根标签、`viewBox`、`</svg>`
 * 时做；任何一步对不上就原样返回，让 sanitizer 给出明确的拒绝原因，而不是猜。
 */
private fun fitViewBox(svg: String): String {
    val startEnd = mathTagEnd(svg, 0) ?: return svg
    val root = svg.substring(0, startEnd + 1)
    val match = VIEW_BOX_ATTR.find(root) ?: return svg
    val parts = match.groupValues[1].trim().split(VIEWBOX_SPLIT).filter { it.isNotEmpty() }
    if (parts.size != 4) return svg
    val values = DoubleArray(4)
    for (index in 0 until 4) {
        values[index] = parts[index].toDoubleOrNull() ?: return svg
        if (!values[index].isFinite()) return svg
    }
    val span = maxOf(values[2], values[3])
    if (span <= 0.0 || span <= MAX_VIEWBOX_SPAN) return svg
    val factor = MAX_VIEWBOX_SPAN / span
    val scaled = (0 until 4).joinToString(" ") { formatDecimal(values[it] * factor, 4) }
    val newRoot = root.replaceRange(match.range, "viewBox=\"" + scaled + "\"")
    // 最后再确认一次结构：`</svg>` 必须在这个根标签之后，否则不动。
    val closeAt = svg.lastIndexOf("</svg")
    if (closeAt <= startEnd) return svg
    return newRoot +
        "<g transform=\"scale(" + formatDecimal(factor, 6) + ")\">" +
        svg.substring(startEnd + 1, closeAt) +
        "</g>" +
        svg.substring(closeAt)
}

/** 不写科学计数法、固定小数位：SVG 解析器对 `1.0E-5` 这种写法没有义务支持。 */
private fun formatDecimal(value: Double, digits: Int): String =
    "%.${digits}f".format(Locale.US, value)

/**
 * 把 `currentColor` 换成主题前景色。
 *
 * AndroidSVG 会把 `currentColor` 解析成样式里的 `color`，而 MathJax 的根 `<g>` 只有
 * `stroke="currentColor" fill="currentColor"`、没有 `color`，于是缺省按**黑色**画：深色
 * 主题下公式直接看不见（选型文档 R7）。MathJax 输出里 `currentColor` 只出现在颜色的位置
 * （属性值、`style` 里的 `fill`/`stroke`/`color`），所以整体替换是安全的；颜色本身已用
 * `#RRGGBB` 校验过，不可能借这一步注入 CSS。
 */
private fun replaceCurrentColor(svg: String, color: String): String {
    if (svg.indexOf("currentColor", ignoreCase = true) < 0) return svg
    return CURRENT_COLOR.replace(svg, color)
}

/**
 * 从**已 sanitize** 的 SVG 里取固有尺寸，返回 `(width, height)`。
 *
 * 顺序：`viewBox` → 根 `width`/`height` 的绝对长度。
 *
 * `viewBox` 优先：MathJax 的根尺寸是 `width="8.704ex"` / `height="2.025ex"`（相对单位），
 * 而 AndroidSVG 在根尺寸路径上不换算 `ex`（选型文档 R3）。`viewBox` 是唯一可靠的尺寸来源；
 * 它给的是 MathJax 的 1/1000 em 单位，对视图层来说**比例**才是要用的东西（`PiMathView`
 * 用 `getDocumentViewBox()` 的宽高比排版），数值本身不必是 CSS px。绝对长度分支只覆盖
 * 手写成 `width="320"` 的情况。
 */
private fun mathSvgSize(svg: String): Pair<Int, Int>? {
    val root = SVG_ROOT_TAG.find(svg)?.value ?: return null
    VIEW_BOX_ATTR.find(root)?.groupValues?.get(1)?.let { viewBox ->
        val parts = viewBox.trim().split(VIEWBOX_SPLIT).filter { it.isNotEmpty() }
        if (parts.size == 4) {
            val width = parts[2].toDoubleOrNull()
            val height = parts[3].toDoubleOrNull()
            if (width != null && height != null && width.isFinite() && height.isFinite() &&
                width > 0.0 && height > 0.0
            ) {
                return mathClampDimension(width) to mathClampDimension(height)
            }
        }
    }
    val width = mathAbsoluteLength(root, WIDTH_ATTR) ?: return null
    val height = mathAbsoluteLength(root, HEIGHT_ATTR) ?: return null
    return width to height
}

private fun mathAbsoluteLength(tag: String, pattern: Regex): Int? {
    val raw = pattern.find(tag)?.groupValues?.get(1)?.trim() ?: return null
    // 只接受 px（或无单位数字）：`ex`/`em`/`%` 在这里没有可靠基准，交给 viewBox 分支。
    val numberText = when {
        raw.endsWith("px", ignoreCase = true) -> raw.dropLast(2).trim()
        else -> raw
    }
    val value = numberText.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value <= 0.0) return null
    return mathClampDimension(value)
}

private fun mathClampDimension(value: Double): Int =
    value.roundToInt().coerceIn(1, PiSvgSanitizer.MAX_DIMENSION)
