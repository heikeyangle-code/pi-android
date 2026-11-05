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
import android.os.SystemClock
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
 * 这里的所有资源路径都与 `assets/rich/mermaid/` 绑死。`WebViewAssetLoader` 只注册
 * `/rich-mermaid/` 这一个前缀，handler 又只放行白名单里的两个文件，所以页面能看到的
 * 资产面就是这两个文件——不是整个 assets，也不是 APK 里的任何其它东西。
 */
private const val TAG = "PiMermaid"
private const val ASSET_URL_PREFIX = "/rich-mermaid/"
private const val ASSET_DIRECTORY = "rich/mermaid/"
private const val HOST_PAGE = "host.html"
private const val HOST_PAGE_URL = "https://appassets.androidplatform.net" + ASSET_URL_PREFIX + HOST_PAGE
private val MERMAID_ASSET_FILES = setOf(HOST_PAGE, "mermaid.min.js")

/** 轮询间隔。只在有任务在飞的时候才发生，一次渲染通常 1–10 次。 */
private const val POLL_INTERVAL_MS = 16L

/** `%%{init: ...}%%`（以及其它 `%%{...}%%` 指令）在进 mermaid 之前就被删掉。 */
private val MERMAID_DIRECTIVE = Regex("""%%\{[\s\S]*?\}%%""")

/**
 * ` ```mermaid ` 的渲染引擎：**一个进程级共享的离屏 WebView 宿主 + 串行有界队列 + LRU 缓存**
 * （方案 §四、§十三、§十五）。
 *
 * 它实现 [PiSvgEngine]，所以 Phase 3 的 MathJax 可以复用同一套宿主、队列、缓存、超时和
 * 主题，而不是再长一个 WebView。视图层不认识这个类；公开入口只有
 * [PiMermaidSvg.renderMermaidSvg]。
 *
 * ## 线程模型（为什么所有状态都只属于主线程）
 *
 * `WebView` 的构造函数、`destroy()`、`evaluateJavascript()` 都要求在**创建它的线程**上调用
 * （Android 官方 `WebView` 文档："This method must be called on the thread that originally
 * created this UI element"）。所以宿主在主线程建、主线程用、主线程销毁；队列、in-flight
 * 表、LRU 也全部只在主线程读写（`enqueue()` 通过 `Dispatchers.Main.immediate` 进入，
 * 其余入口都是 WebView 回调或主线程 scope 里的协程）。这样就不需要一把锁去保护一个
 * 本来只能单线程碰的对象。**主线程只做"派发 + 轮询 + 记账"**：JSON 解析、sanitizer、
 * 尺寸测量都在 `Dispatchers.Default` 上做（见 [accept]），重活不占 UI 线程。
 *
 * ## 队列：串行、有界、可合并、可取消
 *
 * - **串行**：同一时刻只有一个任务在飞。多个 WebView 同时初始化既慢又占内存，而
 *   mermaid 的 `render()` 本身就会往 DOM 里插临时节点，并行只会互相污染。
 * - **有界**：[MAX_PENDING] 个等待位。满了直接返回 [PiSvgResult.Failure]，调用方回退
 *   源码。宁可少画一张图，也不让一次快速滚动把整篇消息的图都排进队里。
 * - **合并**：同一个 `cacheKey` 在队列里/在飞时，后来的调用挂到同一个 [Task] 上（各自
 *   `await` 同一个 `CompletableDeferred`），不会重复渲染。`Task.waiters` 记录还有几个人
 *   在等，最后一个离开才把任务标记为作废。
 * - **取消**：调用方（Compse 的 `LaunchedEffect`）离开时 `await()` 抛
 *   `CancellationException`，这里**不打断正在执行的 JS**（打断会污染共享宿主），只把任务
 *   标记为 `abandoned`：结果回来时**丢弃、也不进缓存**。还没开始的直接出队。
 * - **过期**：任务与 `cacheKey` 一一对应；序列化的队列保证不会有两个任务的回调交错，
 *   而一次任务结束后宿主不会再被它触碰（`webView` 已被释放时 `evaluateJavascript` 无从
 *   调用）。超时/宿主失效时任务立即收尾，晚到的 JS 结果不会覆盖任何东西。
 *
 * ## 超时
 *
 * - [HOST_BOOT_TIMEOUT_MS]：从建 WebView 到页面 + `mermaid.min.js` 就绪 + 文本度量探针
 *   通过。冷启动要解析 5.5 MB 的 bundle，所以这个值比单次渲染宽松。
 * - [RENDER_TIMEOUT_MS]：一次 `mermaid.render()` 从提交到拿到结果。
 *
 * 超时**一律销毁宿主并重建**，而不是继续用：一次挂死的 `render()` 会让同一渲染进程里
 * 后续所有 `evaluateJavascript` 也排队挂住，留在原地等于让整个队列永久卡死。代价是下一次
 * 请求重新冷启动；这是"卡死"与"慢一次"之间的选择。`onRenderProcessGone` 同理：返回
 * `true`（不崩 App）→ 丢弃宿主 → 下一任务重建。
 *
 * 宿主**结构性**失败（建不出来 / 页面加载失败 / 文本度量探针不通过）另有一条
 * [HOST_FAILURE_BACKOFF_MS] 的退避：否则一串消息里的每张图都会白等一次 15 s 冷启动。
 * 单纯的**超时**不进退避（慢设备下一次可能就好了）。
 *
 * ## 宿主窗口：为什么不是"纯离屏"
 *
 * mermaid 的布局依赖 `getBBox()` 这类**文本度量**。一个从未 attach 到窗口的 WebView 在
 * 部分 WebView 版本上会给出全零的文本盒子：图不报错，但节点重叠、连线穿过文字——这是
 * 选型文档 §2.4 标注的第一号技术风险。
 *
 * 这里采用的"等价可靠方案"是：把 WebView **挂进当前 Activity 的 `decorView`，用固定
 * 视口尺寸（[HOST_VIEWPORT_CSS_WIDTH] × [HOST_VIEWPORT_CSS_HEIGHT] CSS px）真实布局，再用
 * `translationX` 整体平移到窗口左侧之外**。它因此有真实的窗口、真实的首帧布局和
 * `isShown() == true`（Chromium 才会正常跑 rAF/计时器），但父容器 `clipChildren` 会裁掉
 * 绘制，用户看不见，也收不到触摸（已移出父边界）。
 *
 * **为什么不用 `WindowManager` 1×1 窗口**：`TYPE_APPLICATION_OVERLAY` 需要
 * `SYSTEM_ALERT_WINDOW`（本 App 没有声明，本次改动也不允许改清单），而 `TYPE_APPLICATION`
 * 需要 Activity 的 window token——绕回同一个 Activity。挂 `decorView` 用的是已有窗口，
 * 零权限、零清单变更。
 *
 * Activity 从 `Application.registerActivityLifecycleCallbacks` 拿（`onActivityResumed`
 * 记弱引用，`onActivityDestroyed` 连带销毁宿主）。**首次渲染可能早于我们注册回调**
 * （用户开 App 后直接进会话），那时用一次 best-effort 反射读 `ActivityThread.mActivities`
 * 找当前 Activity；反射失败（非 SDK 接口限制/字段改名）就退化为**纯离屏 + 手动
 * measure/layout**。
 *
 * 无论走哪条路，页面就绪时都会跑一次**文本度量探针**（`__piReady` 里的
 * `metricsUsable()`）：用一段 16px 文本量 `getBBox()`，量不出宽度就直接判定宿主不可用并
 * 回退源码。也就是说，"离屏量不出文本"这件事在本实现里是**显式失败**，不是一张悄悄画歪
 * 的图。
 *
 * ## 安全边界（第 1 层在这里，第 2 层在 PiSvgSanitizer）
 *
 * - `javaScriptEnabled = true` 是唯一必须打开的开关；`allowFileAccess`/`allowContentAccess`
 *   /`allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs` 全部关闭。
 * - **不使用 `setBlockNetworkLoads(true)`**：它作用在 WebView 的网络栈上，而本地资产走的是
 *   `shouldInterceptRequest`。两者叠加时本地 `mermaid.min.js` 会不会被一起挡掉，只能在
 *   真机上验证；本次不拿"宿主起不来"去换一个未经验证的开关。网络边界改由三件事承担：
 *   ① `shouldInterceptRequest` 对**所有**非白名单 URL 直接返回空响应（请求根本到不了
 *   网络栈）；② `shouldOverrideUrlLoading` 一律返回 `true`（页面不能导航）；③ 宿主页的
 *   CSP `default-src 'none'; connect-src 'none'`。
 * - `securityLevel: "strict"`、`htmlLabels: false`、`startOnLoad: false`、
 *   `suppressErrorRendering: true`、`maxTextSize: 64 KiB`、`maxEdges: 500` 在 `host.html`
 *   里配置（`htmlLabels:false` 是硬要求：`foreignObject` 在 AndroidSVG 里完全不支持）。
 * - 模型源码里的 `%%{init: ...}%%` 在这里被整段删除（mermaid 的 `secure` 默认白名单本来
 *   就禁止指令改 `securityLevel`/`maxTextSize`/`maxEdges`，但 `themeCSS`/`themeVariables`
 *   不在白名单里；删掉指令是最省事、可证明的做法）。
 * - 产出交给 [PiSvgSanitizer.sanitize]，未通过就是 [PiSvgResult.Failure]。
 * - 主题颜色/字体只接受 `#RRGGBB` 与 `[A-Za-z0-9 ,_-]`，Kotlin 与 JS 各校验一次，避免把
 *   任意字符串拼进 CSS。
 *
 * ## 缓存
 *
 * LRU 同时限制**条数**（[MAX_CACHE_ENTRIES]）与**字节数**（[MAX_CACHE_BYTES]），键由
 * [PiMermaidSvg.cacheKey] 内容寻址（源码 + 宽度 + 主题 + 引擎版本）。只缓存成功结果：
 * 失败可能是引擎当下状态（超时、宿主崩溃），缓存它等于让一次抖动永久占位。
 *
 * ## 已知未验证项（必须真机确认，见选型文档 §5）
 *
 * 1. 离屏/挂窗口的 WebView 在目标机型上 `getBBox` 是否可用——探针会给出明确答案；
 * 2. `onRenderProcessGone` 后重建能否连续成功（`chrome://crash` 注入）；
 * 3. mermaid 12.2.0 在 API 26 的最低 WebView 上能否解析执行。已知 mermaid 内部的
 *    `pie`/`venn` 会用到 `structuredClone`（Chromium 98+/WebView 98+）；更老的 WebView 上
 *    这两类图会走 `{ok:false}` → 回退源码，不会画错；
 * 4. 冷启动耗时与宿主常驻内存（一次 5.5 MB bundle 解析 + 一个 ~屏宽×屏高的合成层）；
 * 5. 2 MiB 级 SVG 通过 `evaluateJavascript` 回传的 IPC 上限（本实现按 2 MiB 截断）；
 * 6. 挂 `decorView` 的宿主在分屏/画中画/多窗口下的表现；
 * 7. `journey` 即使 `htmlLabels:false` 也会输出 `foreignObject`（本机 jsdom 实测 4 个），
 *    sanitizer 会连同子树删除，因此该图只剩图形没有文字——它不在方案 §四.3 的必测清单里，
 *    这里记录为已知损失，而不是把它当成功。
 */
internal object PiMermaidRenderEngine : PiSvgEngine {

    /** 方案 §15 的源码上限，按 **UTF-8 字节**算（一个中文字 3 字节，按字符算会放进来 3 倍）。 */
    private const val MAX_SOURCE_BYTES = 64 * 1024

    /** 单个 SVG 上限；与 [PiSvgSanitizer.MAX_BYTES] 一致，超了不送进 sanitizer。 */
    private const val MAX_SVG_BYTES = 2 * 1024 * 1024

    /** 等待位（不含正在渲染的那个）。 */
    private const val MAX_PENDING = 64

    private const val MAX_CACHE_ENTRIES = 48
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024

    /** 一次 `mermaid.render()` 的上限。选型文档建议 3–5 s，这里取上界。 */
    private const val RENDER_TIMEOUT_MS = 5_000L

    /** 冷启动（WebView + 5.5 MB bundle + 探针）的上限。 */
    private const val HOST_BOOT_TIMEOUT_MS = 15_000L

    /**
     * 宿主**结构性**失败（建不出来、页面加载失败、文本度量探针不通过）之后的退避时间。
     * 没有它，一个坏掉的宿主会让每张图都白等一次 15 s 冷启动——一串消息里的 8 张图就是
     * 两分钟。超时不算结构性失败（慢设备下一次可能就好了），所以不记录。
     */
    private const val HOST_FAILURE_BACKOFF_MS = 30_000L

    /**
     * 布局宽度的边界，单位 **CSS px**。mermaid 的 `gantt.useWidth` / `flowchart.wrappingWidth`
     * 只认这个宽度；超过它的值只会让图和字号一起被缩小。
     */
    private const val MIN_LAYOUT_WIDTH_CSS = 240
    private const val MAX_LAYOUT_WIDTH_CSS = 720

    /**
     * 超过这个值时把 `widthPx` 当成**设备 px** 而不是 CSS px（见 [layoutWidthCss]）。
     * 720 CSS px 已覆盖所有手机与常见平板列宽。
     */
    private const val MAX_DIRECT_CSS_WIDTH = 720

    /**
     * 宿主视口，单位是 **CSS px**（WebView 里 1 CSS px = 1 dp）。固定而不跟随每张图：
     * mermaid 的输出尺寸由它自己的 `viewBox` 决定，容器宽度只影响 `useMaxWidth`/换行，
     * 而每次改宽度都要等一次布局 pass，得不偿失。
     */
    private const val HOST_VIEWPORT_CSS_WIDTH = 360.0
    private const val HOST_VIEWPORT_CSS_HEIGHT = 640.0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 等待中的任务，FIFO。只在主线程访问。 */
    private val pending = ArrayDeque<Task>()

    /** `cacheKey` → 正在排队或正在渲染的任务，用于同键合并。 */
    private val inFlight = HashMap<String, Task>()

    private val cache = SvgCache(MAX_CACHE_ENTRIES, MAX_CACHE_BYTES)

    private var running: Task? = null

    private var host: RenderHost? = null

    private var activityRef: WeakReference<Activity>? = null

    private var lifecycleRegistered = false
    private var memoryRegistered = false

    private var hostFailureReason: String? = null

    private var hostFailureAtUptime = 0L

    /**
     * 只在拼脚本时用来把设备 px 换算成 CSS px；取不到（Application 还没 onCreate）就按 1 处理，
     * 那时渲染本来也不会发生。
     */
    private val density: Float by lazy {
        runCatching { PiApplication.context.resources.displayMetrics.density }.getOrDefault(1f)
    }

    // ---------------------------------------------------------------- PiSvgEngine

    override suspend fun render(request: PiSvgRequest): PiSvgResult {
        if (request.kind != PiSvgKind.Mermaid) return PiSvgResult.Failure("math not wired")
        if (request.source.isBlank()) return PiSvgResult.Failure("Mermaid 源码为空")
        if (exceedsSourceLimit(request.source)) {
            return PiSvgResult.Failure("Mermaid 源码超过 ${MAX_SOURCE_BYTES / 1024} KiB 上限")
        }
        val key = request.cacheKey.ifBlank {
            PiMermaidSvg.cacheKey(request.source, request.widthPx, request.theme)
        }
        return withContext(Dispatchers.Main.immediate) { enqueue(key, request) }
    }

    // ---------------------------------------------------------------- queue

    /**
     * 主线程上的入队/合并点。
     *
     * 顺序很重要：先查缓存（命中直接返回，连队列都不进），再查 in-flight（同键合并），
     * 最后才是队列容量检查。反过来会让"同一张图的第二个请求"在队列满时被拒绝，尽管
     * 它本来可以免费共享第一个请求的结果。
     */
    private suspend fun enqueue(key: String, request: PiSvgRequest): PiSvgResult {
        assertMainThread()
        cache.get(key)?.let { return it }
        inFlight[key]?.let { return awaitTask(it) }
        if (running != null && pending.size >= MAX_PENDING) {
            return PiSvgResult.Failure("Mermaid 渲染队列已满（$MAX_PENDING 个等待中）")
        }
        val task = Task(key, request)
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
    private suspend fun awaitTask(task: Task): PiSvgResult {
        task.waiters += 1
        try {
            return task.result.await()
        } finally {
            task.waiters -= 1
            if (task.waiters == 0 && !task.result.isCompleted) abandon(task)
        }
    }

    private fun abandon(task: Task) {
        if (task.started) {
            // 已经在渲染：不打断 JS（会污染共享宿主），只丢弃结果、不写缓存。
            task.abandoned = true
            return
        }
        pending.remove(task)
        if (inFlight[task.key] === task) inFlight.remove(task.key)
        task.result.complete(PiSvgResult.Failure("Mermaid 渲染已取消"))
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

    private fun finish(task: Task, result: PiSvgResult) {
        if (running === task) running = null
        if (inFlight[task.key] === task) inFlight.remove(task.key)
        // 只缓存"还有人要"的成功结果：调用方已经走了的那次（abandoned）是过期内容，
        // 让它进缓存只会把真正要看的图挤出去。
        if (result is PiSvgResult.Ok && !task.abandoned && task.waiters > 0) {
            cache.put(task.key, result)
        }
        task.result.complete(result)
        pump()
    }

    // ---------------------------------------------------------------- one task

    private suspend fun runTask(task: Task) {
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
                renderHost.render { taskId -> renderScript(taskId, task.request) }
            }
            accept(rawReply)
        } catch (timeout: TimeoutCancellationException) {
            disposeHost("渲染超时")
            PiSvgResult.Failure("Mermaid 渲染超时（${RENDER_TIMEOUT_MS} ms）")
        } catch (gone: MermaidHostLostException) {
            disposeHost("宿主失效：${gone.message}")
            hostLost = true
            PiSvgResult.Failure("Mermaid 渲染宿主失效：${gone.message ?: "未知原因"}")
        } catch (unavailable: MermaidHostUnavailableException) {
            PiSvgResult.Failure(unavailable.message ?: "Mermaid 渲染宿主不可用")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PiSvgResult.Failure("Mermaid 渲染失败：${error.message ?: error::class.java.simpleName}")
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
     * 页面没有用）。两者都转成 [MermaidHostUnavailableException]，由 [runTask] 统一变成
     * `Failure`，而不是把异常丢给视图层。
     */
    private suspend fun ensureHost(): RenderHost {
        recentHostFailure()?.let { reason ->
            throw MermaidHostUnavailableException("Mermaid 宿主暂不可用（退避中）：$reason")
        }
        val existing = host
        if (existing != null && existing.isUsable()) return existing
        disposeHost("重建宿主")

        val created = try {
            createHost()
        } catch (error: Throwable) {
            recordHostFailure(error.message ?: error::class.java.simpleName)
            throw MermaidHostUnavailableException(
                "无法创建 Mermaid 渲染宿主：${error.message ?: error::class.java.simpleName}",
            )
        }
        host = created
        try {
            created.load()
        } catch (error: Throwable) {
            disposeHost("宿主加载失败")
            recordHostFailure(error.message ?: error::class.java.simpleName)
            throw MermaidHostUnavailableException(
                "Mermaid 宿主加载失败：${error.message ?: error::class.java.simpleName}",
            )
        }

        val booted = try {
            withTimeoutOrNull(HOST_BOOT_TIMEOUT_MS) { created.awaitLoaded() }
        } catch (gone: MermaidHostLostException) {
            throw gone
        } catch (unavailable: MermaidHostUnavailableException) {
            // 页面加载失败 / 探针判定宿主不可用：这是结构性的，记下来退避。
            disposeHost("宿主不可用")
            recordHostFailure(unavailable.message ?: "宿主不可用")
            throw unavailable
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            disposeHost("宿主加载失败")
            recordHostFailure(error.message ?: error::class.java.simpleName)
            throw MermaidHostUnavailableException(
                "Mermaid 宿主加载失败：${error.message ?: error::class.java.simpleName}",
            )
        }
        if (booted == null) {
            // 只是慢：不记退避，下一次仍允许重试（冷启动解析 5.5 MB bundle 可能偶尔超时）。
            disposeHost("宿主加载超时")
            throw MermaidHostUnavailableException("Mermaid 宿主在 ${HOST_BOOT_TIMEOUT_MS} ms 内未就绪")
        }
        hostFailureReason = null
        return created
    }

    /** 结构性失败后 [HOST_FAILURE_BACKOFF_MS] 内直接失败，不再白等冷启动。 */
    private fun recentHostFailure(): String? {
        val reason = hostFailureReason ?: return null
        if (SystemClock.uptimeMillis() - hostFailureAtUptime >= HOST_FAILURE_BACKOFF_MS) {
            hostFailureReason = null
            return null
        }
        return reason
    }

    private fun recordHostFailure(reason: String) {
        hostFailureReason = reason
        hostFailureAtUptime = SystemClock.uptimeMillis()
    }

    private fun createHost(): RenderHost {
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
            .addPathHandler(ASSET_URL_PREFIX, MermaidAssetHandler(context))
            .build()
        return RenderHost(webView, activity, assetLoader, viewWidthPx, viewHeightPx) { gone, reason ->
            handleHostGone(gone, reason)
        }
    }

    private fun disposeHost(reason: String) {
        val current = host ?: return
        host = null
        current.dispose()
        Log.d(TAG, "mermaid 宿主已释放：$reason")
    }

    /**
     * `onRenderProcessGone` 的收尾。**不能**在回调里 `destroy()`：方法契约要求先移出视图、
     * 并让回调正常返回 `true`，所以在主线程消息队列里延后一拍销毁；`host` 立刻置空，
     * 下一个任务会重建。
     */
    private fun handleHostGone(gone: RenderHost, reason: String) {
        if (host === gone) host = null
        mainHandler.post { gone.dispose() }
        Log.w(TAG, "mermaid 宿主失效：$reason")
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
     * 源码/主题一律经 [JSONObject.quote] 变成 JS 字符串字面量：它转义引号、反斜杠、控制字符
     * 和 `<` 后紧跟的 `/`，所以模型源码里出现 `"`、换行或 `</script>` 都不会越出字符串
     * （这里不是 HTML 上下文，但少一个可注入面总是好的）。
     */
    private fun renderScript(taskId: Int, request: PiSvgRequest): String {
        val theme = request.theme
        val themeLiteral = buildString {
            append("{background:").append(quote(theme.background))
            append(",foreground:").append(quote(theme.foreground))
            append(",accent:").append(quote(theme.accent))
            append(",muted:").append(quote(theme.muted))
            append(",fontFamily:").append(quote(theme.fontFamily))
            append('}')
        }
        val width = layoutWidthCss(request.widthPx)
        return "window.__piRender(" + taskId + "," +
            quote(stripDirectives(request.source)) + "," + themeLiteral + "," + width + ")"
    }

    /**
     * `PiSvgRequest.widthPx` 按契约是 **CSS px**，但现存调用方（`PiMermaidView`）传的是
     * `constraints.maxWidth`，也就是**设备 px**。这个差别不是无关紧要的：
     *
     *  - mermaid 按这个宽度算甘特图宽度和标签换行；
     *  - 输出 SVG 的用户单位随后由 AndroidSVG 按 1 单位 = 1 设备 px 绘制；
     *  - 所以若按 1080（而不是 360）布局，字号 16 单位画出来只有 ~16 设备 px（≈6 dp），
     *    整张图"能看但读不清"，而按密度缩小后缩放回去才是正常字号。
     *
     * 于是这里只信任 ≤ [MAX_DIRECT_CSS_WIDTH] 的值（任何手机/平板的列宽都落在这个区间，
     * 按 CSS px 传的调用方不会被误换算），更大的值按 density 换算。两种约定下都能得到
     * 接近容器宽度的布局宽度。
     */
    private fun layoutWidthCss(widthPx: Int): Int {
        if (widthPx <= 0) return MIN_LAYOUT_WIDTH_CSS
        val css = if (widthPx <= MAX_DIRECT_CSS_WIDTH) {
            widthPx
        } else {
            (widthPx / density.coerceAtLeast(0.1f)).roundToInt()
        }
        return css.coerceIn(MIN_LAYOUT_WIDTH_CSS, MAX_LAYOUT_WIDTH_CSS)
    }

    private fun stripDirectives(source: String): String =
        if (source.contains("%%{")) MERMAID_DIRECTIVE.replace(source, "") else source

    private fun quote(value: String): String = JSONObject.quote(value)

    /**
     * 把 JS 的回复变成结果。**运行在 `Dispatchers.Default` 上**：`JSONObject` 解析 2 MiB
     * 字符串、sanitizer 的逐字符扫描、正则取尺寸，都不该占着主线程。
     */
    private suspend fun accept(rawReply: String): PiSvgResult = withContext(Dispatchers.Default) {
        val reply = try {
            JSONObject(rawReply)
        } catch (error: JSONException) {
            return@withContext PiSvgResult.Failure("Mermaid 宿主返回了无法解析的结果")
        }
        if (!reply.optBoolean("ok", false)) {
            val reason = reply.optString("error").ifBlank { "Mermaid 渲染失败" }
            return@withContext PiSvgResult.Failure(reason)
        }
        val svg = reply.optString("svg")
        when {
            svg.isEmpty() -> PiSvgResult.Failure("Mermaid 没有产出 SVG")
            svg.length > MAX_SVG_BYTES -> PiSvgResult.Failure("SVG 超过 2 MiB 上限")
            else -> when (val sanitized = PiSvgSanitizer.sanitize(svg)) {
                is PiSvgSanitizeResult.Rejected ->
                    PiSvgResult.Failure("SVG 未通过安全校验：${sanitized.reason}")

                is PiSvgSanitizeResult.Ok -> {
                    val size = svgSize(sanitized.svg)
                    if (size == null) {
                        PiSvgResult.Failure("SVG 缺少可用的 viewBox，无法确定固有尺寸")
                    } else {
                        PiSvgResult.Ok(sanitized.svg, size.first, size.second)
                    }
                }
            }
        }
    }

    private fun exceedsSourceLimit(source: String): Boolean {
        // 一个字符最多 3 个 UTF-8 字节：长度不超过这个值就一定放得下，省掉一次 64 KiB 编码。
        if (source.length <= MAX_SOURCE_BYTES / 3) return false
        return source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PiMermaidRenderEngine 只能在主线程访问"
        }
    }
}

// ---------------------------------------------------------------- task / cache

private class Task(val key: String, val request: PiSvgRequest) {
    val result = CompletableDeferred<PiSvgResult>()

    /** 当前还有几个调用方在 `await`；最后一个离开时任务作废。 */
    var waiters = 0

    var started = false

    /** 调用方已全部离开：结果回来时丢弃，也不写缓存。 */
    var abandoned = false
}

/**
 * 条数 + 字节双上限的访问序 LRU（形状同 `PiMermaidMemo`）。
 *
 * 只在主线程访问。`LinkedHashMap(accessOrder = true)` 连 `get` 都会改结构，所以这个类
 * 不声明线程安全，也不假装线程安全。
 */
private class SvgCache(private val maxEntries: Int, private val maxBytes: Int) {

    private class Held(val result: PiSvgResult.Ok, val bytes: Int)

    private val entries = LinkedHashMap<String, Held>(16, 0.75f, true)

    private var bytes = 0

    fun get(key: String): PiSvgResult.Ok? = entries[key]?.result

    fun put(key: String, result: PiSvgResult.Ok) {
        if (maxEntries <= 0 || maxBytes <= 0) return
        // 按字符数近似字节数：mermaid 输出以 ASCII 的 path/样式为主，近似足够定界，
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

private class MermaidHostLostException(message: String) : Exception(message)

private class MermaidHostUnavailableException(message: String) : Exception(message)

/**
 * 一个共享的离屏 WebView 宿主。**所有方法都必须在主线程调用**（由引擎保证）。
 *
 * 生命周期：建 WebView → 配置 → 挂窗口/离屏布局 → `loadUrl(host.html)` → 等
 * `onPageFinished` → 量文本探针 → 可以渲染 → （崩溃/超时/Activity 销毁）→ [dispose]。
 *
 * `dispose()` 之后这个对象不可再用：`webView` 置空，`dead = true`，任何在飞的任务都会在
 * 下一次轮询时抛 [MermaidHostLostException]。
 */
private class RenderHost(
    initialWebView: WebView,
    val activity: Activity?,
    private val assetLoader: WebViewAssetLoader,
    private val viewWidthPx: Int,
    private val viewHeightPx: Int,
    private val onHostGone: (RenderHost, String) -> Unit,
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
        // 用 setter 方法而不是 `webViewClient =` 属性：`getWebViewClient()` 是 API 26 才有的，
        // setter 从 API 1 就在；属性语法会白依赖一个本实现并不需要的 getter。
        initialWebView.setWebViewClient(Client())
        configure(initialWebView)
        attachOrLayout(initialWebView)
    }

    /**
     * 开始加载宿主页。单独一步（而不是塞在 `init` 里）：挂窗口已经发生，如果加载这一步
     * 抛错，引擎要能拿到宿主引用去 [dispose]，否则会漏一个挂在 Activity 上的 WebView。
     */
    fun load() {
        val view = webView ?: throw MermaidHostLostException("Mermaid 宿主 WebView 已释放")
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
                    Log.w(TAG, "mermaid 宿主无法挂到 Activity 窗口，退化为离屏布局", error)
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
            Log.w(TAG, "mermaid 宿主离屏布局失败", error)
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
        // 唯一必须打开的能力：mermaid 是 JS。
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
        if (dead) throw MermaidHostLostException(deadReason ?: "Mermaid 宿主已失效")
        loadFailure?.let { throw MermaidHostUnavailableException(it) }
        if (!loaded) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val failure = loadFailure
                if (failure != null || dead) {
                    continuation.resumeWithException(
                        MermaidHostUnavailableException(failure ?: deadReason ?: "Mermaid 宿主已失效"),
                    )
                } else {
                    loadWaiter = continuation
                    continuation.invokeOnCancellation {
                        if (loadWaiter === continuation) loadWaiter = null
                    }
                }
            }
        }
        if (dead) throw MermaidHostLostException(deadReason ?: "Mermaid 宿主已失效")
        if (!readyProbed) {
            val view = webView ?: throw MermaidHostLostException("Mermaid 宿主 WebView 已释放")
            val reply = evaluate(view, "window.__piReady()")
            val status = try {
                reply?.let { JSONObject(it) }
            } catch (error: JSONException) {
                null
            }
            if (status == null || !status.optBoolean("ok", false)) {
                throw MermaidHostUnavailableException(
                    "Mermaid 宿主页面未就绪：" + (status?.optString("error") ?: "无回复"),
                )
            }
            // 探针结果决定这台宿主到底能不能用（见类注释"宿主窗口"一节）。
            if (!status.optBoolean("metrics", false)) {
                throw MermaidHostUnavailableException(
                    "Mermaid 宿主无法测量文本（离屏布局未生效），已回退源码",
                )
            }
            readyProbed = true
        }
    }

    // ------------------------------------------------------------ render

    /**
     * 提交一次渲染并轮询到结果，返回 JS 回复的原始 JSON。
     *
     * 解析/校验在引擎侧（`Dispatchers.Default`）做，这里只负责"提交 + 等到有回复"。
     * 轮询而不是等回调：`evaluateJavascript` 只能同步返回值，没有 Promise 直通；用
     * `WebMessagePort` 又要处理 origin 与丢首帧消息的握手，这里宁可多几次廉价调用。
     */
    suspend fun render(scriptFor: (Int) -> String): String {
        if (dead) throw MermaidHostLostException(deadReason ?: "Mermaid 宿主已失效")
        awaitLoaded()
        val view = webView ?: throw MermaidHostLostException("Mermaid 宿主 WebView 已释放")
        val taskId = nextTaskId++
        view.evaluateJavascript(scriptFor(taskId), null)
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (dead) throw MermaidHostLostException(deadReason ?: "Mermaid 宿主已失效")
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
                        MermaidHostLostException("evaluateJavascript 失败：${error.message}"),
                    )
                }
            }
        }

    // ------------------------------------------------------------ teardown

    fun dispose() {
        dead = true
        val view = webView
        webView = null
        resumeWaiter(MermaidHostLostException("Mermaid 宿主已释放"))
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        try {
            view.stopLoading()
        } catch (error: RuntimeException) {
            Log.w(TAG, "mermaid 宿主 stopLoading 失败", error)
        }
        try {
            // 先摘掉客户端，再 destroy：destroy 之后不允许再回调进我们的状态机。
            view.setWebViewClient(WebViewClient())
        } catch (error: RuntimeException) {
            Log.w(TAG, "mermaid 宿主解绑 WebViewClient 失败", error)
        }
        try {
            view.destroy()
        } catch (error: RuntimeException) {
            Log.w(TAG, "mermaid 宿主 destroy 失败", error)
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
            waiter.resumeWithException(MermaidHostUnavailableException(reason))
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
        resumeWaiter(MermaidHostLostException(reason))
        onHostGone(this, reason)
    }

    private inner class Client : WebViewClient() {

        /**
         * 所有子资源都从这里过。命中白名单资产 → 本地流；其余一律返回空响应，**绝不**落回
         * 网络栈（`default-src 'none'` 的 CSP 是第二道）。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return blockedResponse()
            val response = try {
                assetLoader.shouldInterceptRequest(url)
            } catch (error: RuntimeException) {
                Log.w(TAG, "mermaid 资产拦截失败：$url", error)
                null
            }
            return response ?: blockedResponse()
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
                    "Mermaid 宿主页面加载失败（${error?.errorCode}）：${error?.description}",
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
            markHostGone("Mermaid 渲染进程崩溃（crashed=${detail?.didCrash() ?: false}）")
            return true
        }
    }
}

private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/**
 * URL 前缀 `/rich-mermaid/` 映射到 `assets/rich/mermaid/` 目录，而且**只放行两个文件**。
 *
 * 不直接用 `WebViewAssetLoader.AssetsPathHandler`：它把整个 assets 暴露在一个前缀下，
 * 而这里的要求是"只加载 assets/rich/mermaid/"。白名单 + 目录前缀让越界路径（`..`、
 * 绝对路径、URL 编码）没有落点。
 *
 * 注意：`handle()` 由 WebView 在**工作线程**调用，所以这里不能碰任何视图状态；只读
 * `AssetManager` 与常量，天然线程安全。
 */
private class MermaidAssetHandler(private val context: Context) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.removePrefix("/")
        if (name !in MERMAID_ASSET_FILES) {
            Log.w(TAG, "mermaid 宿主：拒绝未列入白名单的资产路径 $path")
            return null
        }
        val stream: InputStream = try {
            context.assets.open(ASSET_DIRECTORY + name, AssetManager.ACCESS_STREAMING)
        } catch (error: IOException) {
            Log.w(TAG, "mermaid 宿主：资产 $name 读取失败", error)
            return null
        }
        return WebResourceResponse(mimeTypeOf(name), "utf-8", stream)
    }
}

private fun mimeTypeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

// ---------------------------------------------------------------- svg size

private val SVG_ROOT_TAG = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE)
private val VIEW_BOX_ATTR = Regex("""\bviewBox\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val WIDTH_ATTR = Regex("""\bwidth\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val HEIGHT_ATTR = Regex("""\bheight\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

/**
 * 从**已 sanitize** 的 SVG 里取固有尺寸，返回 `(width, height)`（px）。
 *
 * 顺序：`viewBox` → 根 `width`/`height` 绝对长度 → `null`。`viewBox` 优先是因为 mermaid
 * 每张图都输出它，而且根尺寸经常是 `100%`（AndroidSVG 会把 `100%` 当 100px，见选型文档
 * R3）。尺寸也决定视图层怎么给 AndroidSVG 设 viewport，所以拿不到就**失败**，不猜。
 *
 * 只解析根标签，不做 XML 解析：sanitizer 已经保证结构合法，这里只需要四个数字。
 */
private fun svgSize(svg: String): Pair<Int, Int>? {
    val root = SVG_ROOT_TAG.find(svg)?.value ?: return null
    VIEW_BOX_ATTR.find(root)?.groupValues?.get(1)?.let { viewBox ->
        val parts = viewBox.trim().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
        if (parts.size == 4) {
            val width = parts[2].toDoubleOrNull()
            val height = parts[3].toDoubleOrNull()
            if (width != null && height != null && width > 0.0 && height > 0.0) {
                return clampDimension(width) to clampDimension(height)
            }
        }
    }
    val width = absoluteLength(root, WIDTH_ATTR)
    val height = absoluteLength(root, HEIGHT_ATTR)
    if (width != null && height != null) return width to height
    return null
}

private fun absoluteLength(tag: String, pattern: Regex): Int? {
    val raw = pattern.find(tag)?.groupValues?.get(1)?.trim() ?: return null
    val value = raw.removeSuffix("px").toDoubleOrNull() ?: return null
    if (!value.isFinite() || value <= 0.0) return null
    return clampDimension(value)
}

private fun clampDimension(value: Double): Int =
    value.roundToInt().coerceIn(1, PiSvgSanitizer.MAX_DIMENSION)
