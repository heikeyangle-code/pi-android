package app.pi.ui.render.rich.html

/** HTML 文本里的一段：[svg] 为真表示这是一个完整的 `<svg>…</svg>`（含嵌套）。 */
internal data class PiHtmlPart(val svg: Boolean, val text: String)

/**
 * 把夹着 `<svg>` 的 HTML 按 SVG 切开：SVG 交给 SVG 渲染器，其余仍走 HTML 渲染器
 * （HTML 策略会把 `svg` 标签整个剥掉，只剩里面的文字）。没有完整的 `<svg>…</svg>` 返回 null。
 * 未闭合的 `<svg` 不切（原样留给 HTML 路径，由它按自己的规则处理）。
 */
internal fun splitHtmlSvg(html: String): List<PiHtmlPart>? {
    val parts = ArrayList<PiHtmlPart>()
    var cursor = 0
    var from = 0
    while (true) {
        val start = indexOfSvgOpen(html, from)
        if (start < 0) break
        val end = matchingSvgClose(html, start)
        if (end < 0) break
        if (start > cursor) parts += PiHtmlPart(false, html.substring(cursor, start))
        parts += PiHtmlPart(true, html.substring(start, end))
        cursor = end
        from = end
    }
    if (parts.none { it.svg }) return null
    if (cursor < html.length) parts += PiHtmlPart(false, html.substring(cursor))
    return parts
}

private fun indexOfSvgOpen(s: String, from: Int): Int {
    var i = from
    while (true) {
        i = s.indexOf("<svg", i, ignoreCase = true)
        if (i < 0) return -1
        val n = s.getOrNull(i + 4)
        if (n == null || n.isWhitespace() || n == '>' || n == '/') return i
        i += 4
    }
}

/** 从 [start]（一个 `<svg` 开始）数嵌套，返回匹配的 `</svg>` 之后的位置；找不到 -1。 */
private fun matchingSvgClose(s: String, start: Int): Int {
    var depth = 0
    var i = start
    // 下一个 `<svg` 的位置只在被越过时才重算：否则每个 `</svg>` 迭代都会扫到文末，最坏 O(n²)。
    var open = indexOfSvgOpen(s, i)
    while (i < s.length) {
        if (open in 0 until i) open = indexOfSvgOpen(s, i)
        val close = s.indexOf("</svg>", i, ignoreCase = true)
        if (close < 0) return -1
        if (open in 0 until close) {
            // 自闭合的 `<svg …/>` 不增加深度
            val gt = s.indexOf('>', open)
            if (gt < 0) return -1
            if (s[gt - 1] != '/') depth++
            i = gt + 1
        } else {
            depth--
            i = close + 6
            if (depth <= 0) return i
        }
    }
    return -1
}
