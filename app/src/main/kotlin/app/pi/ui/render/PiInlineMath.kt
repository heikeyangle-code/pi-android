package app.pi.ui.render

/**
 * 行内与独立块级 LaTeX 预处理（零 Compose 依赖，便于纯 JVM 单元测试）。
 *
 * ## 为什么需要哨兵编码（Sentinel Encoding）
 *
 * 如果把 `$x_1 + x_2$` 原样交给 GFM 解析器，解析器会把 `_` / `*` / `\` 解析成斜体或转义，
 * 破坏原始 TeX；而在送进解析器之前先替换成**纯 ASCII 字母数字哨兵**（如 `PiMathTok0EndTok`），可以同时做到：
 * 1. 保留段落内所有普通 Markdown 语法（`**粗体**`、`*斜体*`、`` `代码` ``、`[链接](url)`）；
 * 2. 保护公式内部的 `_` / `*` / `^` / `\` 不被 Markdown 吃掉；
 * 3. **绝不被 `org.intellij.markdown` 的 `GFMLexer` 切碎**：`gfm.flex` 把 `{ALPHANUM}+` 合并为单个
 *    `TEXT` 叶节点，而私有区字符（如 `\uE100`）会触发 `{ANY_CHAR}` 被切成 3 个独立叶节点，导致单叶匹配全部失效！
 *    纯 ASCII `[A-Za-z0-9]+` 哨兵从词法层彻底保证完整落在同一叶节点内，并在提取前自动避开原文已出现的串；
 * 4. 段落渲染层（`PiInlineMarkdownText`）通过 `InlineTextContent` 把哨兵升级成真 MathJax 矢量 SVG，
 *    而标题/表格/错误兜底等非段落表面通过 `resolveToUnicode` 把哨兵安全还原为 `PiLatex.toUnicode` 近似文本，
 *    **保证屏幕上永远不会泄漏原始哨兵字符**。
 *
 * 同时把整行独立的 `\[ ... \]` 与引用块内的 `> $$ ... $$` / `> \[ ... \]` 规范化为 `$$\n...\n$$`，
 * 让 `PiParagraph` 也能将其交给 MathJax 块级渲染，而不再退化成终端 Unicode 近似。
 */
internal object PiInlineMath {

    /** 默认纯 ASCII 字母数字哨兵前缀与后缀（保证被 GFM 词法器整块识别为单个 `{ALPHANUM}+` TEXT 叶节点）。 */
    const val DEFAULT_MARKER_PREFIX: String = "PiMathTok"
    const val DEFAULT_MARKER_SUFFIX: String = "EndTok"

    data class Entry(
        val tex: String,
        val unicodeFallback: String,
        val raw: String,
    )

    data class Extracted(
        val markdown: String,
        val entries: List<Entry>,
        val hasBlockMath: Boolean,
        val markerPrefix: String = DEFAULT_MARKER_PREFIX,
        val markerSuffix: String = DEFAULT_MARKER_SUFFIX,
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
     * 严格跳过围栏代码块（含顶层、引用块内 `> ``` ` 与列表缩进围栏）、4 空格缩进代码块（CommonMark §4.4）
     * 与行内代码（`` `...` ``），避免任何代码块内 `$var` 被误替换成哨兵。
     * 对于货币金额（如 `$5`、`$100 and $200`、`$ 10`）或未闭合/转义 `\$`，原样保留不误判。
     */
    fun extract(markdown: String): Extracted {
        if (!markdown.contains('$') && !markdown.contains("\\(") && !markdown.contains("\\[")) {
            return Extracted(markdown, emptyList(), false)
        }
        val markerPrefix = chooseCollisionFreePrefix(markdown)
        val markerSuffix = DEFAULT_MARKER_SUFFIX
        val entries = ArrayList<Entry>()
        var hasBlockMath = false
        val out = StringBuilder(markdown.length + 32)
        val n = markdown.length
        var i = 0
        var prevLineWasBlank = true
        var inIndentedCode = false
        var activeListIndent = 0

        while (i < n) {
            if (isLineStart(markdown, i)) {
                val lineEnd = findLineEnd(markdown, i)
                val prefix = parseContainerPrefix(markdown, i, lineEnd)
                val lineIsBlank = isBlankRange(markdown, prefix.contentStart, lineEnd)

                if (lineIsBlank) {
                    val afterLine = if (lineEnd < n) lineEnd + 1 else n
                    out.append(markdown, i, afterLine)
                    prevLineWasBlank = true
                    i = afterLine
                    continue
                }

                // Update list-item indentation tracking (for distinguishing list continuation paragraphs
                // from 4-space indented code blocks and for recognizing list-nested fences).
                if (prefix.quoteDepth > 0) {
                    activeListIndent = 0
                } else {
                    val listContentCol = matchListItemContentColumn(markdown, i, lineEnd)
                    if (listContentCol != null) {
                        activeListIndent = listContentCol
                    } else if (prefix.leadingColumns < activeListIndent && prefix.leadingColumns < 2) {
                        activeListIndent = 0
                    }
                }

                // 1. 围栏代码块（顶层、引用块内 `> ``` `、或列表缩进围栏）：整段原样复制直到闭合。
                val maxFenceIndent = if (prefix.quoteDepth == 0 && activeListIndent > 0) {
                    activeListIndent + 3
                } else {
                    3
                }
                val fence = matchFenceOpen(markdown, i, lineEnd, prefix, maxFenceIndent)
                if (fence != null) {
                    val end = findFenceCloseEnd(markdown, fence.afterLine, fence.char, fence.len, fence.quoteDepth)
                    out.append(markdown, i, end)
                    i = end
                    prevLineWasBlank = false
                    inIndentedCode = false
                    continue
                }

                // 2. CommonMark §4.4 缩进代码块（4 空格或 Tab，且位于空行之后或延续前一行缩进代码块，
                //    不在活跃列表项的常规续段缩进范围内）：整行原样复制，避免 `$var` 泄漏哨兵。
                val indentedThreshold = if (prefix.quoteDepth == 0) activeListIndent + 4 else 4
                val isIndentedCodeLine = prefix.leadingColumns >= indentedThreshold &&
                    (prevLineWasBlank || inIndentedCode)
                if (isIndentedCodeLine) {
                    val afterLine = if (lineEnd < n) lineEnd + 1 else n
                    out.append(markdown, i, afterLine)
                    i = afterLine
                    prevLineWasBlank = false
                    inIndentedCode = true
                    continue
                }
                inIndentedCode = false

                // 3. 独立块级公式：整行/多行 `$$...$$` 或 `\[...\]`（同时支持 `> $$` 引用块内公式）
                val blockMatch = matchStandaloneBlockMath(markdown, i, lineEnd, prefix)
                if (blockMatch != null) {
                    hasBlockMath = true
                    val qPrefix = blockMatch.quotePrefix
                    if (qPrefix.isEmpty()) {
                        // 统一写成 `$$\n<body>\n$$`，并在前后确保空行（`\n\n`），防止与紧邻段落粘连成一个 PARAGRAPH
                        if (out.isNotEmpty()) {
                            val trailingNewlines = countTrailingNewlines(out)
                            repeat((2 - trailingNewlines).coerceAtLeast(0)) { out.append('\n') }
                        }
                        out.append("$$\n")
                        out.append(blockMatch.body)
                        out.append("\n$$")
                        i = blockMatch.end
                        if (i < n) {
                            var peek = i
                            var leadingNewlines = 0
                            while (peek < n && markdown[peek] == '\n') {
                                leadingNewlines++
                                peek++
                            }
                            if (peek < n && leadingNewlines < 2) {
                                repeat(2 - leadingNewlines) { out.append('\n') }
                            }
                        }
                    } else {
                        // 引用块内的独立公式：保留 `> ` 前缀防止打断引用块，同时在前后紧邻非空引用行时
                        // 补一行纯引用空行（`>`），避免 GFM 把公式与前后正文合并进同一 PARAGRAPH。
                        val bareQuote = qPrefix.trimEnd()
                        if (out.isNotEmpty() && !prevLineWasBlank && !endsWithBlankQuoteLine(out)) {
                            if (!out.endsWith('\n')) out.append('\n')
                            out.append(bareQuote).append('\n')
                        }
                        out.append(qPrefix).append("$$\n")
                        val bodyLines = blockMatch.body.split('\n')
                        for (idx in bodyLines.indices) {
                            out.append(qPrefix).append(bodyLines[idx]).append('\n')
                        }
                        out.append(qPrefix).append("$$")
                        i = blockMatch.end
                        if (i < n && markdown[i] == '\n') {
                            val nextStart = i + 1
                            if (nextStart < n) {
                                val nextEnd = findLineEnd(markdown, nextStart)
                                if (!isBlankOrQuoteOnlyLine(markdown, nextStart, nextEnd)) {
                                    out.append('\n').append(bareQuote)
                                }
                            }
                        }
                    }
                    prevLineWasBlank = false
                    continue
                }

                prevLineWasBlank = false
            }

            val c = markdown[i]

            // 4. 行内代码 span（一个或多个反引号）：整段原样复制。
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

            // 5. 反斜杠转义与 `\(...\)` / 行内 `\[...\]` 公式
            if (c == '\\' && i + 1 < n) {
                val next = markdown[i + 1]
                if (next == '(' || next == '[') {
                    val close = if (next == '(') {
                        findInlineParenClose(markdown, i + 2)
                    } else {
                        findInlineBracketClose(markdown, i + 2)
                    }
                    if (close >= 0) {
                        val tex = markdown.substring(i + 2, close).trim()
                        if (tex.isNotEmpty()) {
                            val raw = markdown.substring(i, close + 2)
                            val idx = addEntry(entries, tex, raw)
                            out.append(markerPrefix).append(idx).append(markerSuffix)
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

            // 6. 行内 `$...$`（排除 `$$`）
            if (c == '$') {
                if (i + 1 < n && markdown[i + 1] == '$') {
                    // 非独立行内的 `$$...$$`：当作单行行内公式提取（避免留裸 `$$`）
                    val close = findInlineDoubleDollarClose(markdown, i + 2)
                    if (close >= 0) {
                        val tex = markdown.substring(i + 2, close).trim()
                        if (tex.isNotEmpty()) {
                            val raw = markdown.substring(i, close + 2)
                            val idx = addEntry(entries, tex, raw)
                            out.append(markerPrefix).append(idx).append(markerSuffix)
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
                    out.append(markerPrefix).append(idx).append(markerSuffix)
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
            markerPrefix = markerPrefix,
            markerSuffix = markerSuffix,
        )
    }

    /** 把一段可能包含哨兵的文本拆分为普通文本与数学引用片段。 */
    fun splitSegments(
        text: String,
        markerPrefix: String = DEFAULT_MARKER_PREFIX,
        markerSuffix: String = DEFAULT_MARKER_SUFFIX,
    ): List<Segment> {
        if (markerPrefix.isEmpty() || text.indexOf(markerPrefix) < 0) return listOf(Segment.Text(text))
        val result = ArrayList<Segment>(4)
        var cursor = 0
        val n = text.length
        while (cursor < n) {
            val start = text.indexOf(markerPrefix, cursor)
            if (start < 0) {
                result.add(Segment.Text(text.substring(cursor)))
                break
            }
            if (start > cursor) {
                result.add(Segment.Text(text.substring(cursor, start)))
            }
            val digitsStart = start + markerPrefix.length
            var digitsEnd = digitsStart
            while (digitsEnd < n && text[digitsEnd] in '0'..'9') digitsEnd++
            if (digitsEnd > digitsStart && text.startsWith(markerSuffix, digitsEnd)) {
                val idx = text.substring(digitsStart, digitsEnd).toIntOrNull()
                if (idx != null && idx >= 0) {
                    result.add(Segment.MathRef(idx))
                    cursor = digitsEnd + markerSuffix.length
                    continue
                }
            }
            // 未完整匹配时消费前缀首字符继续向前扫描，避免死循环
            result.add(Segment.Text(text.substring(start, digitsStart)))
            cursor = digitsStart
        }
        return result
    }

    /** 把任意含哨兵的字符串安全替换成可读 Unicode 文本（用于错误兜底/标题/表格等）。 */
    fun resolveToUnicode(text: String, extracted: Extracted): String =
        resolveToUnicode(text, extracted.entries, extracted.markerPrefix, extracted.markerSuffix)

    fun resolveToUnicode(
        text: String,
        entries: List<Entry>,
        markerPrefix: String = DEFAULT_MARKER_PREFIX,
        markerSuffix: String = DEFAULT_MARKER_SUFFIX,
    ): String {
        if (entries.isEmpty() || text.indexOf(markerPrefix) < 0) return text
        val segments = splitSegments(text, markerPrefix, markerSuffix)
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

    private fun chooseCollisionFreePrefix(markdown: String): String {
        var candidate = DEFAULT_MARKER_PREFIX
        var nonce = 1
        while (markdown.contains(candidate)) {
            candidate = "PiMath${nonce}Tok"
            nonce++
        }
        return candidate
    }

    private fun endsWithBlankQuoteLine(sb: StringBuilder): Boolean {
        var end = sb.length
        if (end == 0) return true
        if (sb[end - 1] == '\n') end--
        var start = end
        while (start > 0 && sb[start - 1] != '\n') start--
        return isBlankOrQuoteOnlyLine(sb, start, end)
    }

    private fun isBlankOrQuoteOnlyLine(text: CharSequence, start: Int, end: Int): Boolean {
        for (idx in start until end) {
            val ch = text[idx]
            if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '>') return false
        }
        return true
    }

    private fun countTrailingNewlines(sb: StringBuilder): Int {
        var count = 0
        var idx = sb.length - 1
        while (idx >= 0 && sb[idx] == '\n') {
            count++
            idx--
        }
        return count
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

    private fun findLineEnd(s: String, start: Int): Int {
        val nl = s.indexOf('\n', start)
        return if (nl >= 0) nl else s.length
    }

    private fun isBlankRange(s: String, start: Int, end: Int): Boolean {
        var p = start
        while (p < end) {
            val c = s[p]
            if (c != ' ' && c != '\t' && c != '\r') return false
            p++
        }
        return true
    }

    private class ContainerPrefix(
        val quoteDepth: Int,
        val quotePrefixEnd: Int,
        val leadingColumns: Int,
        val contentStart: Int,
    )

    /**
     * Parses leading blockquote markers (`(?:[ ]{0,3}>[ ]?)*`) and measures remaining indentation
     * columns (tab expanded to 4 columns) before the first non-whitespace content character.
     */
    private fun parseContainerPrefix(s: String, lineStart: Int, lineEnd: Int): ContainerPrefix {
        var p = lineStart
        var quoteDepth = 0
        while (p < lineEnd) {
            var sp = 0
            var probe = p
            while (probe < lineEnd && sp < 3 && s[probe] == ' ') {
                probe++
                sp++
            }
            if (probe < lineEnd && s[probe] == '>') {
                quoteDepth++
                p = probe + 1
                if (p < lineEnd && s[p] == ' ') p++
            } else {
                break
            }
        }
        val quotePrefixEnd = p
        var cols = 0
        while (p < lineEnd) {
            when (s[p]) {
                ' ' -> {
                    cols++
                    p++
                }
                '\t' -> {
                    cols += 4 - (cols % 4)
                    p++
                }
                else -> break
            }
        }
        return ContainerPrefix(
            quoteDepth = quoteDepth,
            quotePrefixEnd = quotePrefixEnd,
            leadingColumns = cols,
            contentStart = p,
        )
    }

    /** Returns the content start column if the line starts a CommonMark bullet or ordered list item. */
    private fun matchListItemContentColumn(s: String, lineStart: Int, lineEnd: Int): Int? {
        var p = lineStart
        var cols = 0
        while (p < lineEnd && s[p] == ' ') {
            cols++
            p++
        }
        if (p >= lineEnd) return null
        val ch = s[p]
        if (ch == '-' || ch == '*' || ch == '+') {
            if (p + 1 < lineEnd && (s[p + 1] == ' ' || s[p + 1] == '\t')) {
                return cols + 2
            }
            return null
        }
        if (ch in '0'..'9') {
            var d = p
            while (d < lineEnd && d - p < 9 && s[d] in '0'..'9') d++
            if (d < lineEnd && (s[d] == '.' || s[d] == ')') &&
                d + 1 < lineEnd && (s[d + 1] == ' ' || s[d + 1] == '\t')
            ) {
                return cols + (d - p) + 2
            }
        }
        return null
    }

    private class FenceOpen(val char: Char, val len: Int, val quoteDepth: Int, val afterLine: Int)

    private fun matchFenceOpen(
        s: String,
        lineStart: Int,
        lineEnd: Int,
        prefix: ContainerPrefix,
        maxIndentColumns: Int,
    ): FenceOpen? {
        if (prefix.leadingColumns > maxIndentColumns) return null
        val p = prefix.contentStart
        if (p >= lineEnd) return null
        val ch = s[p]
        if (ch != '`' && ch != '~') return null
        var len = 0
        while (p + len < lineEnd && s[p + len] == ch) len++
        if (len < 3) return null
        // CommonMark: backtick fence info string cannot contain backtick.
        if (ch == '`' && s.substring(p + len, lineEnd).contains('`')) return null
        val after = if (lineEnd < s.length) lineEnd + 1 else s.length
        return FenceOpen(ch, len, prefix.quoteDepth, after)
    }

    private fun findFenceCloseEnd(
        s: String,
        from: Int,
        fenceChar: Char,
        minLen: Int,
        quoteDepth: Int,
    ): Int {
        val n = s.length
        var cursor = from
        while (cursor < n) {
            val lineEnd = findLineEnd(s, cursor)
            val prefix = parseContainerPrefix(s, cursor, lineEnd)
            // If the fence opened inside a blockquote and this line leaves that blockquote,
            // CommonMark closes the fence immediately before this line.
            if (quoteDepth > 0 && prefix.quoteDepth < quoteDepth) {
                return cursor
            }
            val p = prefix.contentStart
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

    private class BlockMathMatch(val body: String, val end: Int, val quotePrefix: String)

    /**
     * 识别独占行的块级公式：`$$...$$` 或 `\[...\]`（支持单行、多行与引用块内 `> $$ ... $$`，
     * 跳过含表格分隔行的未闭合误配）。
     */
    private fun matchStandaloneBlockMath(
        s: String,
        lineStart: Int,
        lineEnd: Int,
        prefix: ContainerPrefix,
    ): BlockMathMatch? {
        if (prefix.leadingColumns > 3) return null
        val p = prefix.contentStart
        val quotePrefix = if (prefix.quoteDepth > 0) {
            s.substring(lineStart, prefix.quotePrefixEnd)
        } else {
            ""
        }

        // 情况 0：独立数学环境 `\begin{equation/align/aligned/gather/cases/matrix/...} ... \end{...}`
        if (s.startsWith("\\begin{", p)) {
            matchStandaloneLatexEnv(s, p, lineEnd, prefix, quotePrefix)?.let { return it }
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
        val firstLineRest = s.substring(afterOpen, lineEnd).trimEnd('\r')

        // 情况 A：单行独立 `$$ body $$` 或 `\[ body \]`
        val trimmedRest = firstLineRest.trim()
        if (trimmedRest.endsWith(close) && trimmedRest.length > close.length) {
            val body = trimmedRest.dropLast(close.length).trim()
            if (body.isNotEmpty() && !body.contains(close)) {
                return BlockMathMatch(body, lineEnd, quotePrefix)
            }
        }
        // 开行已包含闭合定界符但后面还有非空白正文（例如 `$$x$$ 后面文字`），交给行内路径处理。
        if (trimmedRest.contains(close)) return null

        // 情况 B：多行独立块，既支持开定界符独占一行，也支持 `$$ a + b\n c + d $$` 首行直接写公式
        val n = s.length
        if (lineEnd >= n) return null

        var cursor = lineEnd + 1
        val bodyBuilder = StringBuilder()
        if (trimmedRest.isNotEmpty()) {
            bodyBuilder.append(firstLineRest.trimStart())
        }
        while (cursor < n) {
            val nextEnd = findLineEnd(s, cursor)
            val linePrefix = parseContainerPrefix(s, cursor, nextEnd)
            if (prefix.quoteDepth > 0 && linePrefix.quoteDepth < prefix.quoteDepth) {
                return null
            }
            val contentSlice = if (prefix.quoteDepth > 0) {
                s.substring(linePrefix.quotePrefixEnd, nextEnd).trimEnd('\r')
            } else {
                s.substring(cursor, nextEnd).trimEnd('\r')
            }
            val trimmed = contentSlice.trim()
            if (trimmed == close) {
                val body = bodyBuilder.toString().trim()
                if (body.isEmpty() || looksLikeMarkdownTable(body)) return null
                return BlockMathMatch(body, nextEnd, quotePrefix)
            }
            if (trimmed.endsWith(close) && trimmed.length > close.length) {
                val beforeClose = trimmed.dropLast(close.length).trimEnd()
                if (beforeClose.isNotEmpty() && !beforeClose.contains(close)) {
                    if (bodyBuilder.isNotEmpty()) bodyBuilder.append('\n')
                    bodyBuilder.append(beforeClose)
                    val body = bodyBuilder.toString().trim()
                    if (body.isEmpty() || looksLikeMarkdownTable(body)) return null
                    return BlockMathMatch(body, nextEnd, quotePrefix)
                }
            }
            // 过滤多行块级公式内部的纯空行：TeX 数学模式本就忽略空行，保留空行会被 GFM 拆成两个段落导致卡片失效
            if (trimmed.isNotEmpty()) {
                if (bodyBuilder.isNotEmpty()) bodyBuilder.append('\n')
                bodyBuilder.append(contentSlice)
            }
            cursor = if (nextEnd < n) nextEnd + 1 else n
        }
        return null
    }

    private fun matchStandaloneLatexEnv(
        s: String,
        p: Int,
        lineEnd: Int,
        prefix: ContainerPrefix,
        quotePrefix: String,
    ): BlockMathMatch? {
        val firstLine = s.substring(p, lineEnd).trimEnd('\r')
        val envMatch = BEGIN_ENV_REGEX.find(firstLine) ?: return null
        val envName = envMatch.groupValues[1]
        if (envName !in STANDALONE_MATH_ENVS) return null
        val endTag = "\\end{$envName}"
        val trimmedFirst = firstLine.trim()
        if (trimmedFirst.endsWith(endTag) && trimmedFirst.length > envMatch.value.length) {
            return BlockMathMatch(trimmedFirst, lineEnd, quotePrefix)
        }
        val n = s.length
        if (lineEnd >= n) return null
        val bodyBuilder = StringBuilder(firstLine)
        var cursor = lineEnd + 1
        while (cursor < n) {
            val nextEnd = findLineEnd(s, cursor)
            val linePrefix = parseContainerPrefix(s, cursor, nextEnd)
            if (prefix.quoteDepth > 0 && linePrefix.quoteDepth < prefix.quoteDepth) {
                return null
            }
            val contentSlice = if (prefix.quoteDepth > 0) {
                s.substring(linePrefix.quotePrefixEnd, nextEnd).trimEnd('\r')
            } else {
                s.substring(cursor, nextEnd).trimEnd('\r')
            }
            val trimmed = contentSlice.trim()
            if (trimmed.isNotEmpty()) {
                bodyBuilder.append('\n').append(contentSlice)
            }
            if (trimmed.endsWith(endTag)) {
                val body = bodyBuilder.toString().trim()
                if (body.isEmpty() || looksLikeMarkdownTable(body)) return null
                return BlockMathMatch(body, nextEnd, quotePrefix)
            }
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

    private fun findInlineParenClose(s: String, from: Int): Int =
        findInlineBackslashClose(s, from, ')')

    private fun findInlineBracketClose(s: String, from: Int): Int =
        findInlineBackslashClose(s, from, ']')

    private fun findInlineBackslashClose(s: String, from: Int, closingChar: Char): Int {
        val n = s.length
        var p = from
        while (p < n) {
            val c = s[p]
            if (c == '\n') return -1
            if (c == '\\' && p + 1 < n) {
                if (s[p + 1] == closingChar) return p
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
     * - 内容非空，且对货币短语和词内 `$`（如 `foo$bar$`）保守放过。
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
    private val BEGIN_ENV_REGEX = Regex("""^\\begin\{([A-Za-z]+\*?)\}""")
    private val STANDALONE_MATH_ENVS = setOf(
        "equation", "equation*",
        "align", "align*", "aligned",
        "gather", "gather*", "gathered",
        "multline", "multline*",
        "split", "cases",
        "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix",
        "array", "eqnarray", "eqnarray*",
    )
}
