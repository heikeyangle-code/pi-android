package app.pi.ui.render

/**
 * 行内与独立块级 LaTeX 预处理（零 Compose 依赖，便于纯 JVM 单元测试）。
 *
 * ## 为什么需要哨兵编码（Sentinel Encoding）
 *
 * 如果把 `$x_1 + x_2$` 原样交给 GFM 解析器，解析器会把 `_` / `*` / `\` 解析成斜体或转义，
 * 破坏原始 TeX；而在送进解析器之前先替换成私有区数字哨兵 `\uE100<idx>\uE101`，可以同时做到：
 * 1. 保留段落内所有普通 Markdown 语法（`**粗体**`、`*斜体*`、`` `代码` ``、`[链接](url)`）；
 * 2. 保护公式内部的 `_` / `*` / `^` / `\` 不被 Markdown 吃掉；
 * 3. 段落渲染层（`PiInlineMathParagraph`）通过 `InlineTextContent` 把哨兵升级成真 MathJax 矢量 SVG，
 *    而标题/表格/错误兜底等非段落表面通过 `annotator` 把哨兵安全还原为 `PiLatex.toUnicode` 近似文本，
 *    **保证屏幕上永远不会泄漏原始哨兵字符**。
 *
 * 同时把整行独立的 `\[ ... \]` 规范化为 `$$\n...\n$$`，让 `PiParagraph` 也能把 `\[...\]` 交给
 * MathJax 块级渲染，而不再退化成终端 Unicode 近似。
 */
internal object PiInlineMath {

    /** 哨兵起止码位（BMP 私有区，与 PiLatex 的 E000..E002 不冲突）。 */
    const val SENTINEL_START: Char = '\uE100'
    const val SENTINEL_END: Char = '\uE101'

    data class Entry(
        val tex: String,
        val unicodeFallback: String,
        val raw: String,
    )

    data class Extracted(
        val markdown: String,
        val entries: List<Entry>,
        val hasBlockMath: Boolean,
    ) {
        val hasInlineMath: Boolean get() = entries.isNotEmpty()
    }

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class MathRef(val index: Int) : Segment
    }

    /**
     * 对已落定（`!streaming`）的 Markdown 提取行内 LaTeX 并规范化独立 `\[...\]` 块级公式。
     *
     * 严格跳过围栏代码块（```` ``` ```` / `~~~`）与行内代码（`` `...` ``），避免误伤代码里的 `$`。
     * 对于货币金额（如 `$5`、`$100 and $200`、`$ 10`）或未闭合/转义 `\$`，原样保留不误判。
     */
    fun extract(markdown: String): Extracted {
        if (!markdown.contains('$') && !markdown.contains("\\(") && !markdown.contains("\\[")) {
            return Extracted(markdown, emptyList(), false)
        }
        val entries = ArrayList<Entry>()
        var hasBlockMath = false
        val out = StringBuilder(markdown.length + 16)
        val n = markdown.length
        var i = 0

        while (i < n) {
            // 1. 围栏代码块（行首 ≤3 空格 + ``` 或 ~~~）：整段原样复制直到闭合。
            if (isLineStart(markdown, i)) {
                val fence = matchFenceOpen(markdown, i)
                if (fence != null) {
                    val end = findFenceCloseEnd(markdown, fence.afterLine, fence.char, fence.len)
                    out.append(markdown, i, end)
                    i = end
                    continue
                }
                // 2. 独立块级公式：整行 `$$...$$`、多行 `$$ ... $$`、整行 `\[...\]`、多行 `\[ ... \]`
                val blockMatch = matchStandaloneBlockMath(markdown, i)
                if (blockMatch != null) {
                    hasBlockMath = true
                    // 统一写成 `$$\n<body>\n$$`（前后保持独立块，避免和紧邻段落粘连）
                    if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
                    out.append("$$\n")
                    out.append(blockMatch.body)
                    out.append("\n$$")
                    i = blockMatch.end
                    continue
                }
            }

            val c = markdown[i]

            // 3. 行内代码 span（一个或多个反引号）：整段原样复制。
            if (c == '`') {
                var run = 0
                while (i + run < n && markdown[i + run] == '`') run++
                val close = findClosingBackticks(markdown, i + run, run)
                if (close >= 0) {
                    val end = close + run
                    out.append(markdown, i, end)
                    i = end
                } else {
                    out.append(markdown, i, i + run)
                    i += run
                }
                continue
            }

            // 4. 反斜杠转义与 `\(...\)` 行内公式
            if (c == '\\' && i + 1 < n) {
                val next = markdown[i + 1]
                if (next == '(') {
                    val close = findInlineParenClose(markdown, i + 2)
                    if (close >= 0) {
                        val tex = markdown.substring(i + 2, close).trim()
                        if (tex.isNotEmpty()) {
                            val raw = markdown.substring(i, close + 2)
                            val idx = addEntry(entries, tex, raw)
                            out.append(SENTINEL_START).append(idx).append(SENTINEL_END)
                            i = close + 2
                            continue
                        }
                    }
                }
                // 普通转义（如 `\$`、`\\`）：两个字符原样跳过，防止 `\$` 触发行内公式。
                out.append(c).append(next)
                i += 2
                continue
            }

            // 5. 行内 `$...$`（排除 `$$`）
            if (c == '$') {
                if (i + 1 < n && markdown[i + 1] == '$') {
                    // 非独立行内的 `$$...$$`：交给原样或当作单行行内公式提取（避免留裸 `$$`）
                    val close = findInlineDoubleDollarClose(markdown, i + 2)
                    if (close >= 0) {
                        val tex = markdown.substring(i + 2, close).trim()
                        if (tex.isNotEmpty()) {
                            val raw = markdown.substring(i, close + 2)
                            val idx = addEntry(entries, tex, raw)
                            out.append(SENTINEL_START).append(idx).append(SENTINEL_END)
                            i = close + 2
                            continue
                        }
                    }
                    out.append("$$")
                    i += 2
                    continue
                }
                val match = matchInlineDollar(markdown, i)
                if (match != null) {
                    val idx = addEntry(entries, match.tex, match.raw)
                    out.append(SENTINEL_START).append(idx).append(SENTINEL_END)
                    i = match.end
                    continue
                }
            }

            out.append(c)
            i++
        }

        return Extracted(
            markdown = out.toString(),
            entries = entries,
            hasBlockMath = hasBlockMath,
        )
    }

    /** 把一段可能包含哨兵的文本拆分为普通文本与数学引用片段。 */
    fun splitSegments(text: String): List<Segment> {
        if (text.indexOf(SENTINEL_START) < 0) return listOf(Segment.Text(text))
        val result = ArrayList<Segment>(4)
        var cursor = 0
        val n = text.length
        while (cursor < n) {
            val start = text.indexOf(SENTINEL_START, cursor)
            if (start < 0) {
                result.add(Segment.Text(text.substring(cursor)))
                break
            }
            if (start > cursor) {
                result.add(Segment.Text(text.substring(cursor, start)))
            }
            val end = text.indexOf(SENTINEL_END, start + 1)
            if (end < 0) {
                result.add(Segment.Text(text.substring(start)))
                break
            }
            val idx = text.substring(start + 1, end).toIntOrNull()
            if (idx != null && idx >= 0) {
                result.add(Segment.MathRef(idx))
            } else {
                result.add(Segment.Text(text.substring(start, end + 1)))
            }
            cursor = end + 1
        }
        return result
    }

    /** 把任意含哨兵的字符串安全替换成可读 Unicode 文本（用于错误兜底/标题/表格等）。 */
    fun resolveToUnicode(text: String, entries: List<Entry>): String {
        if (entries.isEmpty() || text.indexOf(SENTINEL_START) < 0) return text
        val segments = splitSegments(text)
        return buildString(text.length) {
            for (seg in segments) {
                when (seg) {
                    is Segment.Text -> append(seg.text)
                    is Segment.MathRef -> {
                        val entry = entries.getOrNull(seg.index)
                        if (entry != null) append(entry.unicodeFallback)
                    }
                }
            }
        }
    }

    private fun addEntry(entries: MutableList<Entry>, tex: String, raw: String): Int {
        val fallback = runCatching { PiLatex.toUnicode(tex, display = false) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: tex
        entries.add(Entry(tex = tex, unicodeFallback = fallback, raw = raw))
        return entries.lastIndex
    }

    // ---------------------------------------------------------------- helpers

    private fun isLineStart(s: String, index: Int): Boolean =
        index == 0 || s[index - 1] == '\n'

    private class FenceOpen(val char: Char, val len: Int, val afterLine: Int)

    private fun matchFenceOpen(s: String, lineStart: Int): FenceOpen? {
        val n = s.length
        var p = lineStart
        var spaces = 0
        while (p < n && spaces < 3 && s[p] == ' ') {
            p++
            spaces++
        }
        if (p >= n) return null
        val ch = s[p]
        if (ch != '`' && ch != '~') return null
        var len = 0
        while (p + len < n && s[p + len] == ch) len++
        if (len < 3) return null
        var lineEnd = s.indexOf('\n', p + len)
        if (lineEnd < 0) lineEnd = n
        // CommonMark: backtick fence info string cannot contain backtick.
        if (ch == '`' && s.substring(p + len, lineEnd).contains('`')) return null
        val after = if (lineEnd < n) lineEnd + 1 else n
        return FenceOpen(ch, len, after)
    }

    private fun findFenceCloseEnd(s: String, from: Int, fenceChar: Char, minLen: Int): Int {
        val n = s.length
        var cursor = from
        while (cursor < n) {
            var lineEnd = s.indexOf('\n', cursor)
            if (lineEnd < 0) lineEnd = n
            var p = cursor
            var spaces = 0
            while (p < lineEnd && spaces < 3 && s[p] == ' ') {
                p++
                spaces++
            }
            var run = 0
            while (p + run < lineEnd && s[p + run] == fenceChar) run++
            if (run >= minLen) {
                var tail = p + run
                while (tail < lineEnd && (s[tail] == ' ' || s[tail] == '\t' || s[tail] == '\r')) tail++
                if (tail == lineEnd) {
                    return if (lineEnd < n) lineEnd + 1 else n
                }
            }
            cursor = if (lineEnd < n) lineEnd + 1 else n
        }
        return n
    }

    private fun findClosingBackticks(s: String, from: Int, len: Int): Int {
        val n = s.length
        var p = from
        while (p < n) {
            if (s[p] == '`') {
                var run = 0
                while (p + run < n && s[p + run] == '`') run++
                if (run == len) return p
                p += run
            } else {
                p++
            }
        }
        return -1
    }

    private class BlockMathMatch(val body: String, val end: Int)

    /**
     * 识别独占行的块级公式：`$$...$$` 或 `\[...\]`（支持单行和多行，跳过含表格分隔行的未闭合误配）。
     */
    private fun matchStandaloneBlockMath(s: String, lineStart: Int): BlockMathMatch? {
        val n = s.length
        var p = lineStart
        var spaces = 0
        while (p < n && spaces < 3 && s[p] == ' ') {
            p++
            spaces++
        }
        val open: String
        val close: String
        when {
            s.startsWith("$$", p) -> {
                open = "$$"
                close = "$$"
            }
            s.startsWith("\\[", p) -> {
                open = "\\["
                close = "\\]"
            }
            else -> return null
        }
        val afterOpen = p + open.length
        var lineEnd = s.indexOf('\n', afterOpen)
        if (lineEnd < 0) lineEnd = n
        val firstLineRest = s.substring(afterOpen, lineEnd).trimEnd('\r')

        // 情况 A：单行独立 `$$ body $$` 或 `\[ body \]`
        val trimmedRest = firstLineRest.trim()
        if (trimmedRest.endsWith(close) && trimmedRest.length > close.length) {
            val body = trimmedRest.dropLast(close.length).trim()
            if (body.isNotEmpty() && !body.contains(close)) {
                return BlockMathMatch(body, lineEnd)
            }
        }

        // 情况 B：多行独立块，开定界符独占一行（允许行尾空白）
        if (trimmedRest.isNotEmpty()) return null
        if (lineEnd >= n) return null

        var cursor = lineEnd + 1
        val bodyBuilder = StringBuilder()
        while (cursor < n) {
            var nextEnd = s.indexOf('\n', cursor)
            if (nextEnd < 0) nextEnd = n
            val line = s.substring(cursor, nextEnd).trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed == close) {
                val body = bodyBuilder.toString().trim()
                if (body.isEmpty() || looksLikeMarkdownTable(body)) return null
                return BlockMathMatch(body, nextEnd)
            }
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append('\n')
            bodyBuilder.append(line)
            cursor = if (nextEnd < n) nextEnd + 1 else n
        }
        return null
    }

    private fun looksLikeMarkdownTable(body: String): Boolean {
        val lines = body.split('\n')
        for (idx in 0 until lines.size - 1) {
            val sep = lines[idx + 1]
            if ((sep.contains('|') || lines[idx].contains('|')) && TABLE_SEP_REGEX.matches(sep)) {
                return true
            }
        }
        return false
    }

    private fun findInlineParenClose(s: String, from: Int): Int {
        val n = s.length
        var p = from
        while (p < n) {
            val c = s[p]
            if (c == '\n') return -1
            if (c == '\\' && p + 1 < n) {
                if (s[p + 1] == ')') return p
                p += 2
                continue
            }
            p++
        }
        return -1
    }

    private fun findInlineDoubleDollarClose(s: String, from: Int): Int {
        val n = s.length
        var p = from
        while (p < n) {
            val c = s[p]
            if (c == '\n') return -1
            if (c == '\\' && p + 1 < n) {
                p += 2
                continue
            }
            if (c == '$' && p + 1 < n && s[p + 1] == '$') return p
            p++
        }
        return -1
    }

    private class InlineDollarMatch(val tex: String, val raw: String, val end: Int)

    /**
     * 严格 Pandoc / KaTeX 规则匹配行内 `$...$`：
     * - 左 `$` 前不能是 `\`，且不能紧跟空白或换行；
     * - 右 `$` 前不能是空白或 `\`，且右 `$` 后不能紧跟 ASCII 数字（排除 `$5 and $10` 货币误判）；
     * - 不能跨换行（防止未闭合 `$` 吞掉后文多个段落）；
     * - 内容非空，且如果内容纯数字开头+含空格+逗号等纯价格句子（如 `$50 to $100`），右 `$` 后跟数字会被排除；
     *   同时对纯字母单词前紧贴左 `$`（如 `foo$bar$`）保持保守不误伤。
     */
    private fun matchInlineDollar(s: String, openIndex: Int): InlineDollarMatch? {
        val n = s.length
        if (openIndex + 2 >= n) return null
        // 左 `$` 紧邻前面是 ASCII 字母数字（如 `USD$10` / `var$x`）时不认作公式起点；
        // 注意中文字符后紧贴 `$x$`（如 `当$x>0$`）是合法且常见的！
        if (openIndex > 0) {
            val prev = s[openIndex - 1]
            if (prev in '0'..'9' || prev in 'a'..'z' || prev in 'A'..'Z' || prev == '\\') return null
        }
        val firstChar = s[openIndex + 1]
        if (firstChar.isWhitespace() || firstChar == '$') return null

        var p = openIndex + 1
        while (p < n) {
            val c = s[p]
            if (c == '\n') return null
            if (c == '\\' && p + 1 < n) {
                p += 2
                continue
            }
            if (c == '`') return null
            if (c == '$') {
                // 不允许 `$$` 作为行内闭合
                if (p + 1 < n && s[p + 1] == '$') return null
                // 闭合 `$` 前不能是空白
                if (s[p - 1].isWhitespace()) return null
                // 闭合 `$` 后不能紧跟数字（如 `$5 and $10`）
                if (p + 1 < n && s[p + 1] in '0'..'9') return null
                val tex = s.substring(openIndex + 1, p)
                if (tex.isEmpty()) return null
                // 额外防货币误判：若内容以数字开头、含空白，且不含任何数学特征符（`\` `^` `_` `=` `+` `-` `*` `/` `<` `>`），则更可能是两段价格间的自然语言（如 `$50 to $100` -> `50 to ` 已由尾部空白拦住，这里是如 `$50 per item and $100`）。
                if (looksLikeCurrencySpan(tex)) return null
                return InlineDollarMatch(
                    tex = tex,
                    raw = s.substring(openIndex, p + 1),
                    end = p + 1,
                )
            }
            p++
        }
        return null
    }

    private fun looksLikeCurrencySpan(tex: String): Boolean {
        if (tex[0] !in '0'..'9') return false
        if (!tex.any { it == ' ' }) return false
        return tex.none { it in "\\^_=+-*/<>|{}[]()" }
    }

    private val TABLE_SEP_REGEX = Regex("""^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*(?::?-{3,}:?\s*)?\|?\s*$""")
}
