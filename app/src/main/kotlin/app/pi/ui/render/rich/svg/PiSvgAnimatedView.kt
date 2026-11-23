package app.pi.ui.render.rich.svg

import android.annotation.SuppressLint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 带动画的 SVG（SMIL `<animate>` / `<animateTransform>` / `<set>`、CSS `@keyframes`）。
 *
 * AndroidSVG 只画静态首帧，所以动画 SVG 一直是“不动”。这里把**已经过 [PiSvgSanitizer]**
 * 的 SVG 交给系统 WebView 播放，而且 WebView 被锁死：
 *  - 不跑 JS、不读文件/内容、不联网（`blockNetworkLoads`）；
 *  - SVG 以 `<img src="data:image/svg+xml;base64,…">` 载入——图片模式的 SVG 本身就禁止
 *    脚本与外部资源，即使清理器漏了什么也执行不了；
 *  - 触摸被一层空的 `pointerInput` 兄弟盖住，所以列表仍可滚动（WebView 不抢手势）。
 */
/**
 * 同时在播的动画 SVG 上限。每个都是一个 WebView（几十 MB、独立渲染管线），长对话里十几个
 * 动画图同时存在会直接顶爆内存；超出上限的显示静态图 + 「▶ 播放」，点了才抢一个名额
 * （最早占用的那个退回静态）。滚出屏幕的卡片离开组合时自动归还名额。
 */
internal object PiSvgAnimationSlots {
    private const val MAX_ACTIVE = 3
    private val active = androidx.compose.runtime.mutableStateListOf<Any>()

    fun isActive(key: Any): Boolean = active.any { it === key }

    /** 现在还有空位（只读，不占）：让第一帧就能直接画动画，不先闪一下静态图。 */
    fun hasRoom(): Boolean = active.size < MAX_ACTIVE

    /** 有空位就占上并返回 true；没有空位返回 false（保持静态，等用户点）。 */
    fun tryAcquire(key: Any): Boolean {
        if (isActive(key)) return true
        if (active.size >= MAX_ACTIVE) return false
        active.add(key)
        return true
    }

    /** 用户明确要播：必要时顶掉最早的那个。 */
    fun steal(key: Any) {
        if (isActive(key)) return
        while (active.size >= MAX_ACTIVE) active.removeAt(0)
        active.add(key)
    }

    fun release(key: Any) {
        active.removeAll { it === key }
    }
}

internal fun piSvgIsAnimated(svg: String): Boolean = ANIMATED.containsMatchIn(svg)

private val ANIMATED = Regex(
    """<\s*(?:svg:)?(?:animate|animateTransform|animateMotion|set)\b|@keyframes|animation\s*:""",
    RegexOption.IGNORE_CASE,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PiSvgAnimatedView(svg: String, modifier: Modifier = Modifier) {
    val html = remember(svg) {
        val b64 = Base64.encodeToString(svg.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}" +
            "img{display:block;width:100%;height:100%;object-fit:contain}</style></head>" +
            "<body><img src=\"data:image/svg+xml;base64,$b64\"></body></html>"
    }
    var renderGone by remember(html) { mutableStateOf(false) }
    Box(modifier) {
        if (!renderGone) {
            // 绑定到当前 AndroidView 实例的生命周期（不随 html 变更重置），确保 factory/update/onRelease 共用同一标记。
            val destroyed = remember { booleanArrayOf(false) }
            val lastLoadedHtml = remember { arrayOfNulls<String>(1) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PiWebViewBootstrap.ensureWholeDocumentDraw()
                    WebView(ctx).apply {
                        setBackgroundColor(0)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        settings.apply {
                            javaScriptEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            blockNetworkLoads = true
                            blockNetworkImage = false // data: 图不算网络；该开关只管 http(s)
                            domStorageEnabled = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            setSupportZoom(false)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                if (!destroyed[0]) {
                                    destroyed[0] = true
                                    runCatching {
                                        (view.parent as? ViewGroup)?.removeView(view)
                                        view.destroy()
                                    }
                                }
                                renderGone = true
                                return true
                            }
                        }
                        lastLoadedHtml[0] = html
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                update = { view ->
                    if (!destroyed[0] && lastLoadedHtml[0] != html) {
                        lastLoadedHtml[0] = html
                        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                onRelease = { view ->
                    if (!destroyed[0]) {
                        destroyed[0] = true
                        runCatching {
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                        }
                    }
                    lastLoadedHtml[0] = null
                },
            )
        }
        // 盖住 WebView 的触摸，让列表的滚动手势照常走；不消费事件。
        Box(Modifier.fillMaxSize().pointerInput(Unit) {})
    }
}
