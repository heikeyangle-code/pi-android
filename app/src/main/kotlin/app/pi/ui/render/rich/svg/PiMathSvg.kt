package app.pi.ui.render.rich.svg

import java.security.MessageDigest

/**
 * 行内 / 块级 TeX 的**唯一公开入口**（方案 §四、§十三），与 [PiMermaidSvg] 对称。
 *
 * 视图层（`PiMathView.kt`）只认识这一个函数：给源码、是否块级、目标宽度和主题，拿一个
 * [PiSvgResult]。WebView、队列、缓存、超时、`currentColor` 替换、`viewBox` 归一化全在
 * [PiMathRenderEngine] 里，和 Mermaid 共用同一套纪律（同样的串行有界队列、同一种按字节
 * 回收的 LRU、同样把解析/清洗放到 `Dispatchers.Default`）。
 *
 * ## 为什么 `display` 单独传
 *
 * 同一个 TeX 在行内和块级下的排版**不一样**：`\sum_{i=0}^{n}` 的上下限在块级排在符号
 * 上下、在行内排成右侧上下标，MathJax 的 `display` 参数决定走哪条分支。所以它既是渲染
 * 输入，也必须进缓存键——否则先渲染过的行内结果会被块级请求命中，公式"上下限位置不对"。
 *
 * ## 为什么 cacheKey 在这里拼
 *
 * [PiSvgRequest.cacheKey] 由调用方提供（契约原文），而"调用方"就是这里。键必须覆盖**所有
 * 会改变输出字节**的输入；这个缓存唯一能出的静默错误就是"把另一个主题/另一段源码的公式
 * 画到这里"。五个字段的理由：
 *
 *  - **源码**：同一个公式的文本；
 *  - **display**：见上，行内/块级是两份不同的 SVG；
 *  - **宽度**：MathJax 本身不按宽度换行，但宿主视口和缓存回收都按它分桶；把它放进键里
 *    让"转屏后重新请求"不会读到按旧视口排版的结果；
 *  - **主题**：`currentColor` 会在引擎里被替换成 `foreground`，所以颜色**直接写进了 SVG
 *    字节**。深色主题拿到浅色缓存不是"颜色略偏"，是黑字贴黑底；
 *  - **引擎版本**：[ENGINE_VERSION]。升级 `assets/rich/mathjax/tex-svg.js` 会改变输出结构
 *    （进而改变 AndroidSVG 的兼容性），旧缓存必须随之失效。
 *
 * 用 SHA-256 而不是 `String.hashCode()`：后者是 32 位，两份不同的公式撞车就会**显示错的
 * 公式**，而这不是"缓存没命中"那种可以自愈的失败。字段按**长度前缀**串接再哈希，避免
 * `("ab","c")` 与 `("a","bc")` 这类拼接歧义。
 *
 * ## 为什么这里不做上限/空值判断
 *
 * 32 KiB 源码上限、空源码、`PiSvgKind` 分派都在 [PiMathRenderEngine.render] 里做：那是所有
 * 调用方共用的地方，判断只写一次。这里提前判会让"引擎自己也安全"这件事失效。
 *
 * ## 与 Mermaid 入口的差异
 *
 * [PiMermaidSvg.renderMermaidSvg] 直接把请求交给 `PiSvgEngine.render(request)`；这里走的是
 * [PiMathRenderEngine.render] 的**带 `display` 重载**。原因很直接：[PiSvgRequest] 是 Phase 2
 * 定下的共享契约，没有 `display` 字段，而本次改动不允许修改 `PiSvgContract.kt`。所以 TeX
 * 原文仍放在 `source` 里（引擎的 32 KiB 上限、引用转义、日志都按它算），`display` 只作为
 * 引擎的第二个参数传下去；`PiSvgEngine` 那个单参数方法照样实现，按块级处理。
 */
object PiMathSvg {

    /**
     * 缓存键里的引擎标识。**升级 `assets/rich/mathjax/tex-svg.js` 时必须同步改这里**，
     * 否则旧版本的 SVG 会继续从 LRU 里被取出来（见类注释）。
     *
     * 版本号与 `docs/plans/rich-rendering-engine-eval.md` §3 记录并实测的 4.1.3 对齐
     * （npm `mathjax@4.1.3`，Apache-2.0）。
     */
    const val ENGINE_VERSION: String = "mathjax-4.1.3"

    /**
     * 把一段 TeX 渲染成经过安全校验的 SVG。
     *
     * 永不抛"渲染失败"类异常：失败一律是 [PiSvgResult.Failure]，视图层据此回退原文。只有
     * **协程取消**会以 `CancellationException` 形式抛出——那是调用方离开/内容变了，不是渲染
     * 错误，也不该被当成失败展示。
     *
     * @param source TeX 原文（不含 `$`/`$$`/`\(` 定界符）；不会被修改，也不会被写回 transcript。
     * @param display `true` 表示块级公式（`$$…$$`），`false` 表示行内（`\(…\)`）。它决定
     *   MathJax 的 `display` 分支，因此同一个源码的两种形态是两份不同的缓存条目。
     * @param widthPx 期望的 CSS 像素宽度；MathJax 不按宽度换行，它只进缓存键、进宿主视口
     *   的边界检查（输出尺寸由 `viewBox` 决定）。
     * @param theme 由 `PiTheme.palette` 转成的纯值主题；`foreground` 会替换 MathJax 输出里的
     *   `currentColor`（AndroidSVG 不解析 `currentColor`，见选型文档 R7）。
     */
    suspend fun renderMathSvg(
        source: String,
        display: Boolean,
        widthPx: Int,
        theme: PiSvgTheme,
    ): PiSvgResult = PiMathRenderEngine.render(
        PiSvgRequest(
            kind = PiSvgKind.Math,
            source = source,
            widthPx = widthPx,
            theme = theme,
            cacheKey = cacheKey(source, display, widthPx, theme),
        ),
        display = display,
    )

    /**
     * 内容寻址的缓存键：`math:<sha256>`。
     *
     * `internal` 只给 [PiMathRenderEngine] 在调用方漏传 `cacheKey` 时兜底用，不是第二个公开
     * 入口。字段顺序本身也是键的一部分（改了顺序旧键自然失效），因此**不要重排**。
     */
    internal fun cacheKey(
        source: String,
        display: Boolean,
        widthPx: Int,
        theme: PiSvgTheme,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.feed("pi-mathjax-svg")
        digest.feed(ENGINE_VERSION)
        digest.feed(PiSvgKind.Math.name)
        digest.feed(widthPx.toString())
        digest.feed(if (display) "display" else "inline")
        digest.feed(theme.background)
        digest.feed(theme.foreground)
        digest.feed(theme.accent)
        digest.feed(theme.muted)
        digest.feed(theme.fontFamily)
        digest.feed(source)
        return "math:" + digest.digest().toHex()
    }
}

/**
 * 长度前缀写入：4 字节大端长度 + UTF-8 字节。
 *
 * 只写 `source` 而不写长度的话，`("ab","c")` 与 `("a","bc")` 哈希到同一个键——两份不同的
 * 公式共用一张 SVG，正是这个文件要防的静默错误。
 *
 * 与 `PiMermaidSvg.kt` 里的同名函数是**复制**而不是共用：它们是 `private` 的文件级函数，
 * 另一个文件看不见，而为了这一份 6 行的实现去动共享契约/工具文件不在本次改动范围内。
 */
private fun MessageDigest.feed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}
