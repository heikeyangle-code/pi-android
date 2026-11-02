package app.pi.ui.render.rich.svg

import java.security.MessageDigest

/**
 * ` ```mermaid ` 围栏的**唯一公开入口**（方案 §四、§十三）。
 *
 * 视图层（`PiMermaidView.kt`）只认识这一个函数：给源码、目标宽度和主题，拿一个
 * [PiSvgResult]。它不知道 WebView、队列、缓存、超时的存在；那些全在
 * [PiMermaidRenderEngine] 里。这样 Phase 3 的 MathJax 接同一套宿主时，视图层不需要
 * 再学一遍。
 *
 * ## 为什么 cacheKey 在这里拼
 *
 * [PiSvgRequest.cacheKey] 由调用方提供（契约原文），而"调用方"就是这里。键必须覆盖
 * **所有会改变输出字节**的输入，否则缓存会把一张图换到另一个主题/宽度上——这是这条
 * 缓存唯一能出的静默错误：
 *
 *  - **源码**：同一个 fence 的文本；
 *  - **宽度**：`gantt.useWidth`、`flowchart.wrappingWidth` 由宽度推出，换宽度会换布局；
 *  - **主题**：`background`/`foreground`/`accent`/`muted`/`fontFamily` 全部进 mermaid
 *    的 `themeVariables`，深色主题拿到浅色缓存就是"图是白的"；
 *  - **引擎版本**：[ENGINE_VERSION]。升级 mermaid 会改变输出 SVG 的结构（进而改变
 *    AndroidSVG 的兼容性），旧缓存必须随之失效。
 *
 * 用 SHA-256 而不是 `String.hashCode()`：后者是 32 位，两份不同的图撞车就会**显示错的
 * 图**，而这不是"缓存没命中"那种可以自愈的失败。字段按**长度前缀**串接再哈希，避免
 * `("ab","c")` 与 `("a","bc")` 这类拼接歧义。
 *
 * ## 为什么这里不做上限/空值判断
 *
 * 64 KiB 源码上限、空源码、`PiSvgKind` 分派都在 [PiMermaidRenderEngine.render] 里做：
 * 那是所有调用方共用的地方，判断只写一次。这里提前判会让"引擎自己也安全"这件事失效。
 */
object PiMermaidSvg {

    /**
     * 缓存键里的引擎标识。**升级 `assets/rich/mermaid/mermaid.min.js` 时必须同步改这里**，
     * 否则旧版本的 SVG 会继续从 LRU 里被取出来（见类注释）。
     *
     * 版本号与 `app/src/main/assets/rich/mermaid/LICENSE` 记录的 12.2.0 对齐。
     */
    const val ENGINE_VERSION: String = "mermaid-12.2.0"

    /**
     * 把一段 Mermaid 源码渲染成经过安全校验的 SVG。
     *
     * 永不抛"渲染失败"类异常：失败一律是 [PiSvgResult.Failure]，视图层据此回退源码。
     * 只有**协程取消**会以 `CancellationException` 形式抛出——那是调用方离开/内容变了，
     * 不是渲染错误，也不该被当成失败展示。
     *
     * @param source fence 里的原始 Mermaid 文本；不会被修改，也不会被写回 transcript。
     * @param widthPx 期望的 CSS 像素宽度（引擎用它推 mermaid 的换行/甘特宽度，不改变
     *   输出 SVG 的固有尺寸；固有尺寸由 `viewBox` 决定）。
     * @param theme 由 `PiTheme.palette` 转成的纯值主题。
     */
    suspend fun renderMermaidSvg(
        source: String,
        widthPx: Int,
        theme: PiSvgTheme,
    ): PiSvgResult = PiMermaidRenderEngine.render(
        PiSvgRequest(
            kind = PiSvgKind.Mermaid,
            source = source,
            widthPx = widthPx,
            theme = theme,
            cacheKey = cacheKey(source, widthPx, theme),
        ),
    )

    /**
     * 内容寻址的缓存键：`mermaid:<sha256>`。
     *
     * `internal` 只给 [PiMermaidRenderEngine] 在调用方漏传 `cacheKey` 时兜底用，不是第二
     * 个公开入口。字段顺序本身也是键的一部分（改了顺序旧键自然失效），因此**不要重排**。
     */
    internal fun cacheKey(source: String, widthPx: Int, theme: PiSvgTheme): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.feed("pi-mermaid-svg")
        digest.feed(ENGINE_VERSION)
        digest.feed(PiSvgKind.Mermaid.name)
        digest.feed(widthPx.toString())
        digest.feed(theme.background)
        digest.feed(theme.foreground)
        digest.feed(theme.accent)
        digest.feed(theme.muted)
        digest.feed(theme.fontFamily)
        digest.feed(source)
        return "mermaid:" + digest.digest().toHex()
    }
}

/**
 * 长度前缀写入：4 字节大端长度 + UTF-8 字节。
 *
 * 只写 `source` 而不写长度的话，`("ab","c")` 与 `("a","bc")` 哈希到同一个键——两份不同
 * 的 Mermaid 源码共用一张 SVG，正是这个文件要防的静默错误。
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
