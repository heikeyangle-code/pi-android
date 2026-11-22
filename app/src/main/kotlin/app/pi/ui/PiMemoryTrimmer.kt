package app.pi.ui

import android.content.ComponentCallbacks2
import app.pi.bridge.PiAnimatedImages
import app.pi.bridge.PiGuestImageTransformer
import app.pi.highlight.PiNodeCodeHighlighter
import app.pi.highlight.PiNodeMermaidRenderer
import app.pi.ui.blocks.ParseCaches
import app.pi.ui.blocks.PiImageCache
import app.pi.ui.render.RowHeightCache
import app.pi.ui.render.rich.PiTreeParseCache
import app.pi.ui.render.rich.svg.PiMathDrawCache
import app.pi.ui.render.rich.svg.PiMathRenderEngine
import app.pi.ui.render.rich.svg.PiMermaidRenderEngine
import app.pi.ui.render.rich.svg.clearPiMermaidDrawCache
import app.pi.ui.render.rich.svg.clearPiSvgDrawCache

/**
 * 进程级统一内存裁剪器：接入 `MainActivity.onTrimMemory` / `onLowMemory` 与会话切换。
 *
 * 旧架构里 `PiImageCache.clear()` / `ParseCaches.clear()` 没有任何调用方，
 * 后台驻留时 ~76+ MiB 的位图和文本解析缓存一直占着堆内存，极易在切换应用后被 Android LMK 杀进程连带干掉 Node 引擎。
 */
internal object PiMemoryTrimmer {

    /** 切换或新建会话时释放旧会话的文本解析、行高与代码高亮缓存（位图缓存靠自身 32 MiB LRU 保留）。 */
    fun onSessionReplaced() {
        ParseCaches.clear()
        PiTreeParseCache.clear()
        RowHeightCache.shared.clear()
        PiNodeCodeHighlighter.clear()
    }

    /**
     * 响应系统 `ComponentCallbacks2.onTrimMemory(level)` 分级释放：
     *  - 进入后台（UI 不可见）：先释动图原始字节、文本解析缓存、高亮/Mermaid 缓存；
     *  - 内存告警或深度后台：连带释放全部解码位图缓存与行高记忆，把堆压到最低以保活引擎进程。
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            PiAnimatedImages.clear()
            ParseCaches.clear()
            PiTreeParseCache.clear()
            PiNodeMermaidRenderer.clear()
            PiNodeCodeHighlighter.clear()
        }
        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            PiImageCache.clear()
            PiGuestImageTransformer.clearBitmapCache()
            clearPiMermaidDrawCache()
            clearPiSvgDrawCache()
            PiMermaidRenderEngine.clearCaches()
            PiMathRenderEngine.clearCaches()
            PiMathDrawCache.clear()
            RowHeightCache.shared.clear()
        }
    }

    fun onLowMemory() {
        PiAnimatedImages.clear()
        ParseCaches.clear()
        PiTreeParseCache.clear()
        PiNodeMermaidRenderer.clear()
        PiNodeCodeHighlighter.clear()
        PiImageCache.clear()
        PiGuestImageTransformer.clearBitmapCache()
        clearPiMermaidDrawCache()
        clearPiSvgDrawCache()
        PiMermaidRenderEngine.clearCaches()
        PiMathRenderEngine.clearCaches()
        PiMathDrawCache.clear()
        RowHeightCache.shared.clear()
    }
}
