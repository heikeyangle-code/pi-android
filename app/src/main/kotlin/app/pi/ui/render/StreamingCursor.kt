package app.pi.ui.render

/**
 * 流式输出的光标：把一个 `▍` **接在 Markdown 源码的最后一个字后面**，而不是在正文旁边另放一个方块。
 *
 * 旁边放方块（原做法）有两个毛病：它贴的是整块正文的右边缘，最后一行很短时光标离字很远；
 * 它还占着布局宽度，流式结束、光标消失的那一刻正文变宽，已经断好的行会重排一下。
 * 接在文字里则永远紧跟末字，也不占布局——它本来就是文字。
 *
 * `▍`（U+258D）在随包阅读字体里，但 CJK 字体给它的是 1 em 的占位宽，`tools/build-prose-font.py`
 * 把它的 advance 改成 0.4 em，所以它只占一个光标该占的地方。
 *
 * **只在不会改变解析的地方接。** 末行是下面这些结构行的话，接一个字符会改变它的意思，所以这一帧
 * 不画光标（下一个 token 来了就会有）：
 *  - 围栏行（```` ``` ````、`~~~`）：接上去会把收尾围栏变成「带 info 的围栏」，整块代码就不收尾了；
 *  - `$$` 块定界、`<` 开头的 HTML、表格行（`|` 开头或分隔行）、分隔线 / setext 下划线（`---`、`===`）。
 * 末行只有一个块标记（`-`、`1.`、`#`、`>`）时，先补一个空格再接，否则 `-▍` 不是列表项。
 *
 * 末尾的空白与换行先去掉：`"foo\n"` 接上去是 `"foo▍"`，光标留在 foo 后面，下一段文字到了再另起一行；
 * 若不去掉，光标会在换行出现的那一帧掉到下一行再跳回来。
 */
internal const val STREAMING_CURSOR = "▍"

internal fun withStreamingCursor(text: String): String {
    val body = text.trimEnd()
    if (body.isEmpty()) return STREAMING_CURSOR
    val lastLine = body.substringAfterLast('\n')
    if (STRUCTURAL_LINE.containsMatchIn(lastLine)) return text
    val bareMarker = BARE_BLOCK_MARKER.matches(lastLine)
    return body + (if (bareMarker) " " else "") + STREAMING_CURSOR
}

private val STRUCTURAL_LINE = Regex(
    "^\\s*(?:" +
        "`{3,}|~{3,}" +                  // 围栏
        "|\\$\\$" +                      // 块公式定界
        "|<" +                           // HTML 块
        "|\\|" +                         // 表格行
        "|[-*_=]{3,}\\s*$" +             // 分隔线 / setext 下划线
        "|:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$" + // 无首尾竖线的表格分隔行
        ")",
)

private val BARE_BLOCK_MARKER = Regex("^\\s*(?:[-*+]|\\d{1,9}[.)]|#{1,6}|>)$")
