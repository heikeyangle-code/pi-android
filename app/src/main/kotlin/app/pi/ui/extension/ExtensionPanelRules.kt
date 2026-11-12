package app.pi.ui.extension

// 面板的纯规则：只用 Kotlin 标准库，harness `panel-rules` 在裸 JVM 上编译它。

/**
 * 面板的行：数组里的一个元素可以含换行（扩展把一段文件内容整个推过来），按换行拆开，
 * 否则只画第一行就悄悄丢了其余——宿主最不该有的一种失败。总行数封顶，防止病态输入。
 */
internal fun panelRows(lines: List<String>): List<String> {
    val out = ArrayList<String>()
    for (line in lines) {
        if (!line.contains('\n')) out += line else out += line.split('\n')
        if (out.size >= MAX_PANEL_ROWS) return out.take(MAX_PANEL_ROWS)
    }
    return out
}

/** 面板标题：key 的人话版。宿主不知道 `web-activity` 是什么，所以只换分隔符，不翻译。 */
internal fun widgetCaption(key: String): String? =
    key.trim().replace('-', ' ').replace('_', ' ').takeIf { it.isNotEmpty() }

/** 一行等宽字符 [charPx] 宽、可用像素 [widthPx]（含两侧装饰 [chromePx]）→ 列数，限定在宿主能接受的范围。 */
internal fun columnsFor(widthPx: Int, chromePx: Int, charPx: Int): Int =
    ((widthPx - chromePx) / charPx.coerceAtLeast(1)).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

internal const val COLLAPSED_ROWS = 3
internal const val MAX_PANEL_ROWS = 200
internal const val MIN_COLUMNS = 20
internal const val MAX_COLUMNS = 200
