package app.pi.ui.render.rich.svg

import com.caverock.androidsvg.SVG

/**
 * AndroidSVG 解析前后的两处兼容修正，Mermaid / 数学公式 / SVG 围栏共用。
 *
 * ## 1. 文本层：AndroidSVG 不认识的取值
 *
 * - `orient="auto-start-reverse"`（Mermaid 时序图、状态图的箭头标记）：AndroidSVG 把 `orient` 当
 *   浮点数解析，遇到这个关键字直接抛 `Invalid float value: auto-start-reverse`，整张图回退成
 *   源码 / ASCII。换成 `auto`：箭头起点那一端不再翻转，但图能画出来 —— 远好于整张图丢失。
 *
 * ## 2. 文档层：让图铺满视口
 *
 * 根 `<svg>` 带**绝对**的 `width` / `height`（Mermaid 的 `max-width` 风格、手写 SVG、MathJax 的 `ex`
 * 单位）时，AndroidSVG 把文档按它自己的自然尺寸画在视口左上角，**不会**拉伸去填
 * `renderToPicture(w, h)` 给的视口 —— 于是一张图只占视口的一小块，而外层按宽高比留出的整块高度
 * 全是空的（真机上看到的「小图缩在角落、下面一大片空白」）。
 *
 * 修法：先拿到文档自己的 `viewBox`（没有就用 `width × height` 补一个），再把根的 `width` /
 * `height` 改成 `100%`。这样 `viewBox` 按 `preserveAspectRatio`（默认 xMidYMid meet）缩放到视口，
 * 图居中、等比、铺满。拿不到任何尺寸时什么都不改（保持原样，不比以前差）。
 */
internal object PiSvgFit {

    /** 解析前的文本修正。 */
    fun compat(svgText: String): String =
        if (svgText.contains("auto-start-reverse")) svgText.replace("auto-start-reverse", "auto") else svgText

    /**
     * 解析后让文档铺满视口。[fallbackWidth] / [fallbackHeight] 是文档自己没有 `viewBox` 且
     * `width/height` 也不是绝对数值时的兜底（管线报告的像素尺寸），≤ 0 表示没有。
     */
    fun fill(svg: SVG, fallbackWidth: Int = 0, fallbackHeight: Int = 0) {
        try {
            val viewBox = svg.documentViewBox
            val hasViewBox = viewBox != null && viewBox.width() > 0f && viewBox.height() > 0f
            if (!hasViewBox) {
                val w = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
                    ?: fallbackWidth.takeIf { it > 0 }?.toFloat()
                val h = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
                    ?: fallbackHeight.takeIf { it > 0 }?.toFloat()
                if (w == null || h == null) return
                svg.setDocumentViewBox(0f, 0f, w, h)
            }
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
        } catch (_: Exception) {
            // 修正是尽力而为：任何一步抛异常都保持文档原样，绘制照旧。
        }
    }
}
