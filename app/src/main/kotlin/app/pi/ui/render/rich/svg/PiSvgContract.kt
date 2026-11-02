package app.pi.ui.render.rich.svg

/**
 * 共享 SVG 渲染管线的输入/输出契约。
 *
 * Mermaid（Phase 2）和 MathJax（Phase 3）都只通过这里交换数据：渲染引擎不知道
 * 调用方是图还是公式，调用方也不碰 WebView。这样两个功能共用同一套任务队列、
 * 缓存、主题和资源上限，而不是各写一套。
 *
 * 颜色用 `#RRGGBB` 字符串而不是 Compose `Color`：这个包要能被纯 JVM 的 sanitizer
 * 测试引用，不能 import Compose。主题值由宿主从 `PiTheme.palette` 转成字符串后传入。
 */
data class PiSvgTheme(
    val background: String,
    val foreground: String,
    val accent: String,
    val muted: String,
    val fontFamily: String = "sans-serif",
)

enum class PiSvgKind {
    Mermaid,
    Math,
}

/**
 * 一次渲染请求。
 *
 * [cacheKey] 由调用方提供（源码 + 主题 + 宽度 + 引擎版本），引擎不重新拼；
 * [widthPx] 是期望的 CSS 像素宽度，引擎按它给 mermaid/mathjax 设置 viewport。
 */
data class PiSvgRequest(
    val kind: PiSvgKind,
    val source: String,
    val widthPx: Int,
    val theme: PiSvgTheme,
    val cacheKey: String,
)

sealed interface PiSvgResult {
    /** 渲染成功。[svg] 已经过 [PiSvgSanitizer] 校验，可直接交给 AndroidSVG。 */
    data class Ok(
        val svg: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : PiSvgResult

    /** 失败必须回退源码/现有 PiLatex，不允许空白。 */
    data class Failure(val reason: String) : PiSvgResult
}

/** 引擎实现方（Mermaid/MathJax 共用一个宿主）。 */
internal interface PiSvgEngine {
    suspend fun render(request: PiSvgRequest): PiSvgResult
}
