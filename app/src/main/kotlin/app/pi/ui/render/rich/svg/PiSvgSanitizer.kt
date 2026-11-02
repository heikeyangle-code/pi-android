package app.pi.ui.render.rich.svg

/**
 * `svg` 围栏、Mermaid 和 MathJax 共用的 **SVG 安全闸门**（方案 §八、§十三、§十五）。
 *
 * 这个文件是纯 Kotlin：没有 Compose、没有 Android、没有第三方 XML 库。这样它才能被
 * `tools/run-app-pure-checks.sh` 在裸 JVM 上编译和执行（`PiSvgSanitizerCheck.kt`），
 * 而不是只能等真机或 Gradle 构建来发现问题。真正的 XML/DOM 解析器在 Android 上不可用，
 * 也**不应该**在安全闸门里再引入一个：下面是一个只做「分类 + 重写」的小型扫描器，
 * 不认识 SVG 的绘制语义，只负责回答两个问题：
 *
 *  1. 这段文本里有没有**不允许出现在任何 SVG 里**的东西（DOCTYPE/ENTITY、超限、坏结构）？
 *     有就 [PiSvgSanitizeResult.Rejected]，由调用方回退源码。
 *  2. 单个元素/属性/样式规则是否危险？危险的东西**移除**，其余原样保留，交给 AndroidSVG。
 *
 * ## 为什么是「拒绝」和「移除」两种
 *
 * - **拒绝**：结构性的、无法安全重写的问题。`<!DOCTYPE`/`<!ENTITY`（XXE、billion laughs）、
 *   超过 2 MiB、节点/深度/画布超限、根元素不是 `<svg>`、标签不闭合。半截输出比源码回退
 *   更危险，所以这些一律整体 [PiSvgSanitizeResult.Rejected]。
 * - **移除**：局部的危险内容。`<script>`/`<foreignObject>` 及其整个子树（含其中文本，避免
 *   把脚本正文当普通文字画出来）、`on*` 事件属性、外部 `href`/`xlink:href`、危险 URL、
 *   外部 `url()`、`@import`。移除后图形通常仍可绘制；画不出来时由 AndroidSVG 的解析失败
 *   兜底回退源码。
 *
 * ## 明确保留的东西
 *
 * `path`、`defs`、`use`、`marker`、`clipPath`、`linearGradient`/`radialGradient`、`symbol`、
 * `mask`、`pattern`、`text`/`tspan`、`g`、`rect`/`circle`/`ellipse`/`line`/`polyline`/`polygon`
 * 以及 `<style>` **整段保留**。Mermaid 的样式表是图形的颜色来源，整段删掉会让图变黑/无色，
 * 所以 [filterCss] 只按声明/规则删危险片段，不删整个 `<style>`。AndroidSVG 的能力清单与
 * 已知兼容性风险见 `docs/plans/rich-rendering-engine-eval.md` §2.6。
 *
 * ## 大小写
 *
 * XML 是大小写敏感的（`<linearGradient>` 不能写成 `<lineargradient>`），所以**输出保留
 * 原始大小写**；但安全判断一律按小写比较：`<SCRIPT>`、`OnClick`、`XLINK:HREF`、`VIEWBOX`
 * 与全小写形式等价对待。这样既不会把 `linearGradient` 改写坏，也不会漏掉大写形式的攻击。
 *
 * ## 输入契约
 *
 * 输入必须是**完整 SVG 文档**（根元素 `<svg>`，文档前后只允许空白/注释/处理指令）。MathJax
 * 的 `<mjx-container>` 外层、markdown 上下文的包装标签都由引擎先剥掉再调用这里，本函数不会
 * 从别的内容里「找」 `<svg>`：找到一半再渲染比直接拒绝更难排查。
 *
 * ## 与 AndroidSVG 的分工
 *
 * 这里不做根尺寸重写（Mermaid/MathJax 的根 `width="100%"`、`ex` 单位仍需宿主管线按
 * `viewBox` 重算，见选型文档 R3），也不处理 `dominant-baseline`/`currentColor`。本文件只
 * 负责安全边界和资源上限；[MAX_DIMENSION] 只用于拒绝明显病态的 `viewBox`/绝对宽高。
 */
sealed interface PiSvgSanitizeResult {
    /** 已通过检查（可能移除了危险片段），可以交给 AndroidSVG。 */
    data class Ok(val svg: String) : PiSvgSanitizeResult

    /** 拒绝渲染。`reason` 是给日志/用户看的中文原因；调用方必须回退原始源码。 */
    data class Rejected(val reason: String) : PiSvgSanitizeResult
}

object PiSvgSanitizer {
    /** 单个 SVG 的 UTF-8 字节上限（2 MiB，方案 §十五）。 */
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    /** 元素总数上限（方案 §十五：20,000）。超限整份拒绝，不返回半棵树。 */
    const val MAX_NODES: Int = 20_000

    /** 元素嵌套上限（方案 §十五：64）。根 `<svg>` 算第 1 层。 */
    const val MAX_DEPTH: Int = 64

    /** 画布边长上限（px）。只约束能换算成绝对长度的值；`100%`/`em`/`ex` 留给 viewport。 */
    const val MAX_DIMENSION: Int = 8192

    /**
     * 校验并清洗一份 SVG 文本。
     *
     * 纯函数，无 IO、无全局状态；同一输入永远得到同一结果。返回的 [PiSvgSanitizeResult.Ok.svg]
     * 仍是 XML 文本（不是重新序列化的对象），未改动的片段逐字保留。
     */
    fun sanitize(svg: String): PiSvgSanitizeResult {
        // 文件/剪贴板来源的 SVG 可能带 UTF-8 BOM；它不是根元素之前的文本，先剥掉。
        val source = if (svg.isNotEmpty() && svg[0] == '\uFEFF') svg.substring(1) else svg
        if (source.isBlank()) return PiSvgSanitizeResult.Rejected("SVG 内容为空")
        if (exceedsByteLimit(source)) {
            return PiSvgSanitizeResult.Rejected("SVG 超过 2 MiB 上限（$MAX_BYTES 字节）")
        }
        return SvgScanner(source).scan()
    }

    /**
     * 先比字符数再比字节数：UTF-8 字节数永远不小于 UTF-16 字符数，所以字符数已经超限时
     * `toByteArray` 都不必分配。只有字符数没超时才做一次（≤2 MiB 的）精确编码。
     */
    private fun exceedsByteLimit(text: String): Boolean {
        if (text.length > MAX_BYTES) return true
        return text.toByteArray(Charsets.UTF_8).size > MAX_BYTES
    }
}

/** 元素被整体移除的名单，按**本地名**（去掉 `svg:` 前缀后）小写比较。 */
private val FORBIDDEN_ELEMENTS: Set<String> = setOf(
    // 可执行脚本。AndroidSVG 不执行脚本，但脚本正文可能被当文字画出来，且绝不能
    // 让「这里没有脚本」这一前提失守。
    "script",
    "handler",
    // 任意 HTML：AndroidSVG 完全不支持，且是绕过 SVG 白名单的经典入口。
    "foreignobject",
    // 外部文档/资源容器，可能触发网络或文件访问。
    "iframe",
    "object",
    "embed",
    // 媒体与位图脚本表面：不是 SVG 静态图形的一部分，且可能引用外部资源。
    "audio",
    "video",
    "canvas",
)

/**
 * 属性值里一出现就整条属性移除的协议。`data:`/`file:` 是方案 §八明确禁止的本地/内联
 * 资源入口；`blob:`/`filesystem:`/`mhtml:` 属于同一类「不是内联片段就不是资源」的写法，
 * 一并拒绝，避免以后换实现时留下口子。
 */
private val DANGEROUS_SCHEMES: List<String> = listOf(
    "javascript:",
    "vbscript:",
    "data:",
    "file:",
    "filesystem:",
    "blob:",
    "mhtml:",
)

/** `<svg width="12.5cm">` 这类绝对长度；`%`/`em`/`ex` 依赖 viewport，分组里返回 null。 */
private val LENGTH_PATTERN =
    Regex("""^\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*([a-zA-Z%]*)\s*$""")

private class SvgAttr(
    /** 原始拼写，输出时保留。 */
    val name: String,
    /** 小写、去掉命名空间前缀后的名字，只用于判断。 */
    val localName: String,
    val value: String,
    /** 原始引号；未加引号时记录首字符，输出时再决定。 */
    val quote: Char,
)

private class SvgTag(
    val name: String,
    val localName: String,
    val attrs: List<SvgAttr>,
    val selfClosing: Boolean,
    /** 标签结束 `>`（或 `/>`）之后的下标。 */
    val end: Int,
)

private class SvgCloseTag(val name: String, val end: Int)

/**
 * 单次扫描的状态机。所有方法都是纯文本操作，不依赖解析器；遇到无法安全重写的情况直接
 * 返回 [PiSvgSanitizeResult.Rejected]。
 */
private class SvgScanner(private val src: String) {
    private val out = StringBuilder(src.length)
    private val stack = ArrayList<String>(16)
    private var nodeCount = 0
    private var rootSeen = false
    private var rootClosed = false

    fun scan(): PiSvgSanitizeResult {
        var i = 0
        while (i < src.length) {
            if (src[i] != '<') {
                val next = src.indexOf('<', i).let { if (it < 0) src.length else it }
                val text = src.substring(i, next)
                if (stack.isEmpty() && text.isNotBlank()) {
                    return wrong("根元素 <svg> 之外出现文本")
                }
                out.append(text)
                i = next
                continue
            }

            when {
                src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i + 4)
                    if (end < 0) return wrong("注释未闭合")
                    out.append(src, i, end + 3)
                    i = end + 3
                }

                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i + 9)
                    if (end < 0) return wrong("CDATA 未闭合")
                    if (stack.isEmpty()) return wrong("根元素 <svg> 之外出现 CDATA")
                    out.append(src, i, end + 3)
                    i = end + 3
                }

                // DOCTYPE / ENTITY / 其他 <!...> 声明：解析前拒绝，绝不让解析器看到。
                src.startsWith("<!", i) -> {
                    return wrong("包含 DOCTYPE/ENTITY 或其他 XML 声明，已拒绝（位置 $i）")
                }

                // 处理指令：`<?xml-stylesheet href="https://…"?>` 可以引用外部样式表，
                // 因此整条丢弃；XML 声明本身也可以安全丢弃（解析器不要求它存在）。
                src.startsWith("<?", i) -> {
                    val end = src.indexOf("?>", i + 2)
                    if (end < 0) return wrong("处理指令未闭合")
                    i = end + 2
                }

                src.startsWith("</", i) -> {
                    val close = parseCloseTag(i) ?: return wrong("结束标签格式错误（位置 $i）")
                    if (stack.isEmpty()) return wrong("多余的结束标签 </${close.name}>")
                    val name = close.name.lowercase()
                    val open = stack.removeAt(stack.lastIndex)
                    if (open != name) {
                        return wrong("标签嵌套不匹配：<$open> 对应 </${close.name}>")
                    }
                    out.append(src, i, close.end)
                    if (stack.isEmpty()) rootClosed = true
                    i = close.end
                }

                else -> {
                    val tag = parseStartTag(i) ?: return wrong("开始标签格式错误（位置 $i）")
                    if (FORBIDDEN_ELEMENTS.contains(tag.localName)) {
                        if (tag.selfClosing) {
                            i = tag.end
                            continue
                        }
                        val end = skipForbiddenElement(tag.end, tag.localName)
                        if (end < 0) return wrong("<${tag.name}> 元素未闭合")
                        i = end
                        continue
                    }

                    if (stack.isEmpty()) {
                        if (rootSeen || rootClosed) return wrong("存在多个根元素")
                        if (tag.localName != "svg") {
                            return wrong("根元素不是 <svg>，实际是 <${tag.name}>")
                        }
                        rootSeen = true
                        rootSizeFailure(tag)?.let { return wrong(it) }
                    }

                    if (stack.size + 1 > PiSvgSanitizer.MAX_DEPTH) {
                        return wrong("节点嵌套超过 ${PiSvgSanitizer.MAX_DEPTH} 层")
                    }
                    nodeCount++
                    if (nodeCount > PiSvgSanitizer.MAX_NODES) {
                        return wrong("节点数量超过 ${PiSvgSanitizer.MAX_NODES}")
                    }

                    emitStartTag(tag, keepAttributes(tag))

                    when {
                        tag.selfClosing -> {
                            if (stack.isEmpty()) rootClosed = true
                            i = tag.end
                        }

                        // `<style>` 的内容是 CSS 文本，不是 XML 元素；必须整段交给
                        // filterCss 而不是逐元素扫描，否则 Mermaid 的样式表会被拆坏。
                        tag.localName == "style" -> {
                            val close = findClosingTag(tag.end, "style")
                            if (close < 0) return wrong("<style> 未闭合")
                            val closeEnd = src.indexOf('>', close)
                            if (closeEnd < 0) return wrong("<style> 未闭合")
                            val css = src.substring(tag.end, close)
                            // `<style>` 的内容绕过元素扫描，所以 DOCTYPE/ENTITY 必须在这里
                            // 单独挡住：XML 解析器会把其中的 `<!...>` 当声明，不能放过去。
                            // 注释里的文本不是声明，先剥掉 CSS 注释再判断。
                            val uncommented = stripCssComments(css)
                            if (uncommented.contains("<!DOCTYPE", ignoreCase = true) ||
                                uncommented.contains("<!ENTITY", ignoreCase = true)
                            ) {
                                return wrong("style 内容中包含 DOCTYPE/ENTITY 声明，已拒绝")
                            }
                            out.append(filterCss(css))
                            out.append(src, close, closeEnd + 1)
                            i = closeEnd + 1
                        }

                        else -> {
                            stack.add(tag.name.lowercase())
                            i = tag.end
                        }
                    }
                }
            }
        }

        if (stack.isNotEmpty()) return wrong("根元素 <svg> 未闭合")
        if (!rootSeen) return wrong("缺少 <svg> 根元素")
        if (!rootClosed) return wrong("根元素 <svg> 未闭合")
        return PiSvgSanitizeResult.Ok(out.toString())
    }

    private fun wrong(reason: String): PiSvgSanitizeResult.Rejected =
        PiSvgSanitizeResult.Rejected(reason)

    // ------------------------------------------------------------------ tags

    private fun parseStartTag(start: Int): SvgTag? {
        var i = start + 1
        val nameStart = i
        while (i < src.length && !isNameTerminator(src[i])) i++
        if (i == nameStart) return null
        val name = src.substring(nameStart, i)
        if (name.isEmpty() || name[0] == '!' || name[0] == '?') return null

        val attrs = ArrayList<SvgAttr>(4)
        val seen = HashSet<String>()
        var selfClosing = false
        while (true) {
            while (i < src.length && src[i].isWhitespace()) i++
            if (i >= src.length) return null
            when (src[i]) {
                '>' -> {
                    i++
                    break
                }

                '/' -> {
                    if (i + 1 < src.length && src[i + 1] == '>') {
                        selfClosing = true
                        i += 2
                        break
                    }
                    return null
                }

                else -> {
                    val attrStart = i
                    while (i < src.length && !isNameTerminator(src[i]) && src[i] != '=') i++
                    if (i == attrStart) return null
                    val attrName = src.substring(attrStart, i)
                    while (i < src.length && src[i].isWhitespace()) i++
                    if (i >= src.length || src[i] != '=') return null
                    i++
                    while (i < src.length && src[i].isWhitespace()) i++
                    if (i >= src.length) return null

                    val quote = src[i]
                    val value: String
                    when (quote) {
                        '"', '\'' -> {
                            i++
                            val valueStart = i
                            while (i < src.length && src[i] != quote) i++
                            if (i >= src.length) return null
                            value = src.substring(valueStart, i)
                            i++
                        }

                        else -> {
                            // XML 要求属性值加引号；这里仍容忍裸值（输出会补引号），
                            // 否则一个 HTML 味的 `<rect width=10>` 会让整份 SVG 回退。
                            val valueStart = i
                            while (i < src.length && !src[i].isWhitespace() && src[i] != '>') i++
                            value = src.substring(valueStart, i)
                        }
                    }
                    // `<` 在 XML 属性值里非法；放过去只会让解析器失败得更晚。
                    if (value.indexOf('<') >= 0) return null

                    val lowerName = attrName.lowercase()
                    if (!seen.add(lowerName)) return null
                    attrs.add(
                        SvgAttr(
                            name = attrName,
                            localName = localNameOf(lowerName),
                            value = value,
                            quote = quote,
                        ),
                    )
                }
            }
        }
        return SvgTag(
            name = name,
            localName = localNameOf(name.lowercase()),
            attrs = attrs,
            selfClosing = selfClosing,
            end = i,
        )
    }

    private fun parseCloseTag(start: Int): SvgCloseTag? {
        var i = start + 2
        while (i < src.length && src[i].isWhitespace()) i++
        val nameStart = i
        while (i < src.length && !src[i].isWhitespace() && src[i] != '>') i++
        if (i == nameStart) return null
        val name = src.substring(nameStart, i)
        while (i < src.length && src[i].isWhitespace()) i++
        if (i >= src.length || src[i] != '>') return null
        return SvgCloseTag(name, i + 1)
    }

    private fun isNameTerminator(c: Char): Boolean =
        c.isWhitespace() || c == '/' || c == '>' || c == '<' || c == '='

    private fun localNameOf(lowerName: String): String = lowerName.substringAfterLast(':')

    /**
     * 跳过被移除元素的**整个子树**。只统计同名开始/结束标签的深度；子树里的其他元素
     * 全部丢弃。这样 `<foreignObject><div>文字</div></foreignObject>` 不会把「文字」
     * 漏成根下的普通文本。
     */
    private fun skipForbiddenElement(from: Int, name: String): Int {
        var depth = 1
        var i = from
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt < 0) return -1
            when {
                src.startsWith("<!--", lt) -> {
                    val end = src.indexOf("-->", lt + 4)
                    if (end < 0) return -1
                    i = end + 3
                }

                src.startsWith("<![CDATA[", lt) -> {
                    val end = src.indexOf("]]>", lt + 9)
                    if (end < 0) return -1
                    i = end + 3
                }

                src.startsWith("<?", lt) -> {
                    val end = src.indexOf("?>", lt + 2)
                    if (end < 0) return -1
                    i = end + 2
                }

                src.startsWith("</", lt) -> {
                    val gt = src.indexOf('>', lt)
                    if (gt < 0) return -1
                    val closeName = src.substring(lt + 2, gt).trim().lowercase()
                    if (closeName == name) {
                        depth--
                        if (depth == 0) return gt + 1
                    }
                    i = gt + 1
                }

                src.startsWith("<!", lt) -> return -1

                else -> {
                    val gt = findMarkupEnd(lt)
                    if (gt < 0) return -1
                    val tagName = rawTagName(lt + 1)
                    if (tagName != null && tagName.lowercase() == name) {
                        val beforeGt = src.substring(lt, gt).trimEnd()
                        if (!beforeGt.endsWith("/")) depth++
                    }
                    i = gt + 1
                }
            }
        }
        return -1
    }

    /** 找到 `</name>` 的 `<` 下标；跳过注释、CDATA 和处理指令里的假结束标签。 */
    private fun findClosingTag(from: Int, name: String): Int {
        var i = from
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt < 0) return -1
            when {
                src.startsWith("<!--", lt) -> {
                    val end = src.indexOf("-->", lt + 4)
                    if (end < 0) return -1
                    i = end + 3
                }

                src.startsWith("<![CDATA[", lt) -> {
                    val end = src.indexOf("]]>", lt + 9)
                    if (end < 0) return -1
                    i = end + 3
                }

                src.startsWith("<?", lt) -> {
                    val end = src.indexOf("?>", lt + 2)
                    if (end < 0) return -1
                    i = end + 2
                }

                src.startsWith("</", lt) -> {
                    var j = lt + 2
                    while (j < src.length && src[j].isWhitespace()) j++
                    if (j + name.length <= src.length &&
                        src.substring(j, j + name.length).equals(name, ignoreCase = true)
                    ) {
                        val after = j + name.length
                        if (after < src.length && (src[after] == '>' || src[after].isWhitespace())) {
                            return lt
                        }
                    }
                    i = lt + 1
                }

                else -> i = lt + 1
            }
        }
        return -1
    }

    private fun findMarkupEnd(lt: Int): Int {
        var i = lt + 1
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun rawTagName(from: Int): String? {
        var i = from
        while (i < src.length && !isNameTerminator(src[i])) i++
        if (i == from) return null
        return src.substring(from, i)
    }

    // ------------------------------------------------------------- attributes

    private class SafeAttr(val name: String, val value: String, val quote: Char)

    /**
     * 属性白名单是**否定式**的（只去掉明确危险的名字/值），因为 AndroidSVG 的绘制依赖
     * `fill`/`stroke`/`marker-end`/`clip-path`/`style` 等大量属性；正向白名单会悄悄让
     * Mermaid 的连线/箭头/颜色失效。
     */
    private fun keepAttributes(tag: SvgTag): List<SafeAttr> {
        val kept = ArrayList<SafeAttr>(tag.attrs.size)
        for (attr in tag.attrs) {
            // 事件属性：onclick/onload/onbegin/…（XML 命名空间前缀也算）。
            if (attr.localName.startsWith("on")) continue
            // xml:base 会改变所有相对 URL 的解析基准，直接丢弃。
            if (attr.name.equals("xml:base", ignoreCase = true)) continue

            val keptValue: String? = when (attr.localName) {
                "href" -> {
                    // 只允许文档内片段：use/gradient/marker 的全部正常用法都是 `#id`。
                    val trimmed = attr.value.trim()
                    if (trimmed.length > 1 && trimmed.startsWith("#")) attr.value else null
                }

                "style" -> {
                    val filtered = filterCss(attr.value)
                    if (filtered.isBlank()) null else filtered
                }

                else -> {
                    if (attributeValueIsDangerous(attr.value)) null else attr.value
                }
            }
            if (keptValue != null) kept.add(SafeAttr(attr.name, keptValue, attr.quote))
        }
        return kept
    }

    private fun emitStartTag(tag: SvgTag, attrs: List<SafeAttr>) {
        out.append('<').append(tag.name)
        for (attr in attrs) {
            out.append(' ').append(attr.name).append('=')
            if (attr.quote == '"' || attr.quote == '\'') {
                out.append(attr.quote).append(attr.value).append(attr.quote)
            } else if (!attr.value.contains('"')) {
                out.append('"').append(attr.value).append('"')
            } else if (!attr.value.contains('\'')) {
                out.append('\'').append(attr.value).append('\'')
            } else {
                out.append('"').append(attr.value.replace("\"", "&quot;")).append('"')
            }
        }
        if (tag.selfClosing) out.append("/>") else out.append('>')
    }

    private fun attributeValueIsDangerous(raw: String): Boolean {
        val normalized = normalizeForScreening(raw)
        if (normalized.isEmpty()) return false
        if (DANGEROUS_SCHEMES.any { normalized.contains(it) }) return true
        return hasExternalUrlFunction(normalized)
    }

    // ------------------------------------------------------------- root bounds

    private fun rootSizeFailure(tag: SvgTag): String? {
        val viewBox = tag.attrs.firstOrNull { it.name.equals("viewBox", ignoreCase = true) }?.value
        if (viewBox != null) {
            // 上限本来就是拒绝病态输入；超长 viewBox 在 split 之前就挡掉，避免把
            // 2 MiB 的分隔符拆成上百万个空串。
            if (viewBox.length > 1024) return "viewBox 格式不正确"
            val parts = viewBox.trim().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
            if (parts.size != 4) return "viewBox 必须包含 4 个数值"
            val width = parts[2].toDoubleOrNull()
            val height = parts[3].toDoubleOrNull()
            if (width == null || height == null) return "viewBox 不是有效数值"
            if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) {
                return "viewBox 宽高必须为正数"
            }
            if (width > PiSvgSanitizer.MAX_DIMENSION || height > PiSvgSanitizer.MAX_DIMENSION) {
                return "SVG 画布尺寸超过 ${PiSvgSanitizer.MAX_DIMENSION}（viewBox 为 $width x $height）"
            }
        }

        for (attr in tag.attrs) {
            val name = attr.name.lowercase()
            if (name != "width" && name != "height") continue
            val px = parseAbsoluteLength(attr.value) ?: continue
            if (!px.isFinite()) return "SVG 根元素 $name 不是有限数值"
            if (px > PiSvgSanitizer.MAX_DIMENSION) {
                return "SVG 根元素 $name 超过 ${PiSvgSanitizer.MAX_DIMENSION}"
            }
        }
        return null
    }

    private fun parseAbsoluteLength(raw: String): Double? {
        val match = LENGTH_PATTERN.matchEntire(decodeXmlRefs(raw)) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "", "px" -> value
            "pt" -> value * 96.0 / 72.0
            "pc" -> value * 16.0
            "in" -> value * 96.0
            "cm" -> value * 96.0 / 2.54
            "mm" -> value * 96.0 / 25.4
            "q" -> value * 96.0 / 101.6
            // `%`、`em`、`ex` 等相对单位由 viewport 决定，这里无法定界；
            // 它们不会让节点数/文本量越界，交给宿主按目标宽度重写。
            else -> null
        }
    }
}

/**
 * 过滤一段 CSS，返回其**子序列**（只删不改）：删除注释、`@import`、外部 `url()` 所在的
 * 声明，以及含 `javascript:` 等危险协议的声明。不动 `<style>` 标签本身，也不重排剩余规则。
 *
 * 分段的依据是未被引号/括号包裹的 `;`、`{`、`}`。这样 `@media{…}` 里的声明、`url()` 里的
 * 分号、字符串里的花括号都不会被误切。判定用 [normalizeForScreening] 处理过的形式（解码
 * XML 实体数值、CSS 反斜杠转义、去空白），输出仍是原文切片，因此不会引入新的 `&`/引号。
 */
private fun filterCss(raw: String): String {
    if (raw.isEmpty()) return raw
    val css = stripCssComments(raw)
    if (css.isEmpty()) return css

    val out = StringBuilder(css.length)
    var start = 0
    var i = 0
    var paren = 0
    var quote: Char? = null
    while (i < css.length) {
        val c = css[i]
        if (quote != null) {
            if (c == '\\') {
                if (i + 1 < css.length) i += 2 else i++
            } else {
                if (c == quote) quote = null
                i++
            }
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '(' -> paren++
            ')' -> if (paren > 0) paren--
            ';', '{', '}' -> if (paren == 0) {
                appendCssSegment(out, css.substring(start, i))
                out.append(c)
                start = i + 1
            }
        }
        i++
    }
    if (start < css.length) appendCssSegment(out, css.substring(start))
    return out.toString()
}

private fun appendCssSegment(out: StringBuilder, segment: String) {
    if (!cssSegmentIsDangerous(segment)) out.append(segment)
}

private fun cssSegmentIsDangerous(segment: String): Boolean {
    if (segment.isBlank()) return false
    val normalized = normalizeForScreening(segment)
    if (normalized.isEmpty()) return false
    if (normalized.contains("@import")) return true
    if (normalized.contains("expression(")) return true
    if (normalized.contains("-moz-binding")) return true
    if (normalized.contains("behavior:")) return true
    if (DANGEROUS_SCHEMES.any { normalized.contains(it) }) return true
    return hasExternalUrlFunction(normalized)
}

private fun stripCssComments(css: String): String {
    if (css.indexOf("/*") < 0) return css
    val out = StringBuilder(css.length)
    var i = 0
    while (i < css.length) {
        val open = css.indexOf("/*", i)
        if (open < 0) {
            out.append(css, i, css.length)
            break
        }
        out.append(css, i, open)
        val close = css.indexOf("*/", open + 2)
        if (close < 0) break
        i = close + 2
    }
    return out.toString()
}

/**
 * 在已归一化的文本里检查每个 `url(...)`：只有 `#fragment` 是允许的（渐变/裁剪/箭头引用）。
 * 其余（绝对 URL、协议相对 `//host`、相对路径、`data:`/`file:`）都算外部引用。
 */
private fun hasExternalUrlFunction(normalized: String): Boolean {
    var i = normalized.indexOf("url(")
    while (i >= 0) {
        val close = normalized.indexOf(')', i + 4)
        val target = if (close < 0) normalized.substring(i + 4) else normalized.substring(i + 4, close)
        if (!(target.length > 1 && target.startsWith("#"))) return true
        if (close < 0) return true
        i = normalized.indexOf("url(", close + 1)
    }
    return false
}

/**
 * 安全判定用的归一化形式：解码 XML 字符引用（`&#106;`）、CSS 反斜杠转义（`\6a`）、去掉
 * 引号、空白和控制字符，最后转小写。这样 `java&#x0A;script:`、`u\72 l(…)`、`URL(#a)` 都
 * 会落到同一个判定上。**只用于判断**，不进入输出。
 */
private fun normalizeForScreening(raw: String): String {
    if (raw.isEmpty()) return ""
    val decoded = cssUnescape(decodeXmlRefs(raw))
    val out = StringBuilder(decoded.length)
    for (c in decoded) {
        when {
            c == '"' || c == '\'' -> Unit
            c.isWhitespace() || c.code < 0x20 || c.code == 0x7F -> Unit
            else -> out.append(c.lowercaseChar())
        }
    }
    return out.toString()
}

private fun decodeXmlRefs(raw: String): String {
    if (raw.indexOf('&') < 0) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        val semi = raw.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 12) {
            out.append(c)
            i++
            continue
        }
        val decoded = decodeXmlEntity(raw.substring(i + 1, semi))
        if (decoded != null) {
            out.append(decoded)
            i = semi + 1
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private fun decodeXmlEntity(body: String): String? {
    if (body.isEmpty()) return null
    if (body[0] == '#') {
        val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
            body.substring(2).toIntOrNull(16)
        } else {
            body.substring(1).toIntOrNull(10)
        }
        if (code == null || code <= 0 || code > 0x10FFFF) return null
        return try {
            String(Character.toChars(code))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    // XML 只预定义这五个；其余名字在没有 DOCTYPE 时本来就是非法实体。
    return when (body) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> null
    }
}

private fun cssUnescape(raw: String): String {
    if (raw.indexOf('\\') < 0) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            out.append(c)
            i++
            continue
        }
        var j = i + 1
        if (raw[j] == '\n' || raw[j] == '\r' || raw[j] == '\u000C') {
            i = j + 1
            if (raw[j] == '\r' && i < raw.length && raw[i] == '\n') i++
            continue
        }
        var value = 0
        var digits = 0
        while (j < raw.length && digits < 6) {
            val digit = hexValue(raw[j]) ?: break
            value = value * 16 + digit
            digits++
            j++
        }
        if (digits == 0) {
            out.append(raw[j])
            i = j + 1
        } else {
            if (j < raw.length && raw[j].isWhitespace()) j++
            if (value > 0 && value <= 0x10FFFF) {
                try {
                    out.append(String(Character.toChars(value)))
                } catch (_: IllegalArgumentException) {
                    // 非法码点：丢弃该转义，不影响安全判定。
                }
            }
            i = j
        }
    }
    return out.toString()
}

private fun hexValue(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}
