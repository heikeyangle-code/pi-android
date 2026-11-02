package app.pi.ui.render.rich.html

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * 裸 HTML 的**文档模型**（方案 §六、§十五）。
 *
 * 这一层只做三件事：
 *
 * 1. 把调用方给的 HTML 交给并行实现的 [PiHtmlPolicy] 做安全清理；
 * 2. 用 jsoup 的 HTML 解析器（[Parser.htmlParser]）读清理后的片段，修复异常闭合；
 * 3. 把 DOM 转成与 Compose 无关的 [PiHtmlNode] 树，并执行节点数 / 深度 / 字符上限。
 *
 * 它**不执行**任何脚本、样式或外部资源；不读文件、不联网。图片节点只保存 URL，
 * 由 [PiHtmlRenderer] 交给现有的图片通道（`LocalPiImageTransformer`）。
 *
 * ## 与 PiHtmlPolicy 的分工
 *
 * 白名单、危险标签、URL 协议、属性清理都属于 [PiHtmlPolicy]。这个文件**不重复**
 * 那套策略：策略返回 [PiHtmlPolicyResult.Rejected] 时，[parse] 把它的
 * [PiHtmlRejectReason] 映射成同一套 [PiHtmlFailure]（TooLarge / TooDeep /
 * TooManyNodes / Malformed），调用方（[PiHtmlBlockView]）回退源码；返回 `Ok` 时，
 * 这里才解析 `cleanedHtml`。
 *
 * 这里仍然不会执行任何东西：即使策略漏掉了某个标签，本文件也只把它转成纯节点
 * （未知标签保留子节点文字），不会生成可执行组件。
 *
 * ## 失败与回退
 *
 * [parse] 从不抛异常：HTML 不完整、嵌套异常、策略拒绝、超限、jsoup 内部异常，
 * 都以 [PiHtmlParseResult.Failure] 返回。失败时**不**在这里保存源码；调用方手里
 * 有原始 [source]，由它回退。
 */
object PiHtmlDocument {

    /** 方案 §十五 的初始预算：256 KiB / 20,000 节点 / 64 层。 */
    val DEFAULT_LIMITS: PiHtmlLimits = PiHtmlLimits()

    /**
     * 解析一个 HTML 块。
     *
     * @param source 模型输出的原始 HTML，未做任何改写。
     * @param settled `false` 表示仍在流式生成：直接返回 [PiHtmlFailure.NotSettled]，
     *   不解析、不生成交互组件（方案 §十四.3）。
     * @param limits 字符、节点、深度上限；超限返回 [PiHtmlFailure.TooLarge] /
     *   [PiHtmlFailure.TooManyNodes] / [PiHtmlFailure.TooDeep]。
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: PiHtmlLimits = DEFAULT_LIMITS,
    ): PiHtmlParseResult {
        if (!settled) return PiHtmlParseResult.Failure(PiHtmlFailure.NotSettled)
        if (source.isBlank()) return PiHtmlParseResult.Failure(PiHtmlFailure.Empty)
        if (source.length > limits.maxChars) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.TooLarge)
        }

        // 安全策略先于解析。策略的 Ok 只说明“清理后的串可以进入解析”，不说明本文件
        // 可以信任它的结构；下面仍然只构造白名单节点。
        val cleaned = try {
            when (
                val policy = PiHtmlPolicy.sanitize(
                    source,
                    PiHtmlPolicyLimits(
                        maxChars = limits.maxChars,
                        maxDepth = limits.maxDepth,
                        maxNodes = limits.maxNodes,
                    ),
                )
            ) {
                is PiHtmlPolicyResult.Ok -> policy.cleanedHtml
                // 策略的拒绝原因是一个枚举（TooLarge / TooDeep / TooManyNodes /
                // Malformed），映射成同一套 PiHtmlFailure，调用方只需处理一种失败词表。
                is PiHtmlPolicyResult.Rejected -> return PiHtmlParseResult.Failure(
                    when (policy.reason) {
                        PiHtmlRejectReason.TooLarge -> PiHtmlFailure.TooLarge
                        PiHtmlRejectReason.TooDeep -> PiHtmlFailure.TooDeep
                        PiHtmlRejectReason.TooManyNodes -> PiHtmlFailure.TooManyNodes
                        PiHtmlRejectReason.Malformed -> PiHtmlFailure.Malformed
                    },
                )
            }
        } catch (_: StackOverflowError) {
            // 防御性：策略自己的递归不该溢出，但溢出也必须回退源码而不是崩 UI。
            return PiHtmlParseResult.Failure(PiHtmlFailure.TooDeep)
        } catch (t: Exception) {
            // 策略自身失败（含 jsoup 清理异常）也按拒绝处理：回退原文是唯一安全选项。
            return PiHtmlParseResult.Failure(
                PiHtmlFailure.Rejected(t.message?.takeIf { it.isNotBlank() } ?: "安全清理失败"),
            )
        }

        if (cleaned.isBlank()) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.EmptyAfterPolicy)
        }
        if (cleaned.length > limits.maxChars) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.TooLarge)
        }

        val document = try {
            // `Parser.htmlParser()` 是 HTML 模式：允许隐式闭合、会修 b/p/li 等错配，
            // 这正是“异常闭合由 jsoup 修复”的入口。不要换成 XML 解析器。
            Jsoup.parse(cleaned, "", Parser.htmlParser())
        } catch (_: StackOverflowError) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.TooDeep)
        } catch (_: Exception) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.Malformed)
        }

        val body = document.body() ?: return PiHtmlParseResult.Failure(PiHtmlFailure.Malformed)
        val builder = PiHtmlTreeBuilder(limits)
        val nodes = builder.buildBody(body)
        if (nodes == null) {
            return PiHtmlParseResult.Failure(builder.failure ?: PiHtmlFailure.Malformed)
        }
        if (nodes.isEmpty()) {
            return PiHtmlParseResult.Failure(PiHtmlFailure.Empty)
        }
        return PiHtmlParseResult.Success(nodes)
    }
}

/** [PiHtmlNode] 的子节点列表。放在接口外，避免与 [PiHtmlNode.List] 的简单名冲突。 */
typealias PiHtmlNodes = List<PiHtmlNode>

/** 列表项；[children] 是该 `<li>` 内的块序列。 */
data class PiHtmlListItem(val children: PiHtmlNodes)

/** 别名只用于避开 [PiHtmlNode.List] 在接口内部的简单名遮蔽。 */
typealias PiHtmlListItems = List<PiHtmlListItem>

/** HTML 表格的一行。[header] 为 `true` 时整行按表头绘制。 */
data class PiHtmlTableRow(val header: Boolean, val cells: List<PiHtmlTableCell>)

/** 别名只用于避开 [PiHtmlNode.List] 在接口内部的简单名遮蔽。 */
typealias PiHtmlTableRows = List<PiHtmlTableRow>

/** HTML 表格的一个单元格。`rowspan` 只被保存，当前 Compose 布局未实现跨行。 */
data class PiHtmlTableCell(
    val header: Boolean,
    val colspan: Int,
    val rowspan: Int,
    val children: PiHtmlNodes,
)

/**
 * 纯节点树。所有节点都可比较、可缓存，不持有 jsoup 对象，也不持有 Compose 对象。
 *
 * 标签只保存规范化后的小写名（`div`、`p`、`strong`……）。未知标签也会成为
 * [Block] 或 [Inline]，渲染器对未知标签只画子节点，绝不丢文字。
 */
sealed interface PiHtmlNode {

    /** 已折叠空白的文本。`pre/code` 内的文本单独放在 [Code] 里，不经过这里。 */
    data class Text(val text: String) : PiHtmlNode

    /** 块级容器。`tag` 是 `p`、`div`、`blockquote`、`figure` 等。 */
    data class Block(val tag: String, val children: PiHtmlNodes) : PiHtmlNode

    /** 行内容器。`tag` 是 `strong`、`em`、`span`、`mark` 等。 */
    data class Inline(val tag: String, val children: PiHtmlNodes) : PiHtmlNode

    /**
     * `<details>`：`summary` 是标题行，`body` 是展开后的块序列。
     * [open] 来自 `open` 属性；默认展开状态严格跟随它。
     */
    data class Details(
        val open: Boolean,
        val summary: PiHtmlNodes,
        val body: PiHtmlNodes,
    ) : PiHtmlNode

    /** `<ul>` / `<ol>`；[start] 对无序列表也为 1（不参与绘制）。 */
    data class List(
        val ordered: Boolean,
        val start: Int,
        val items: PiHtmlListItems,
    ) : PiHtmlNode

    /** `<table>`；[caption] 可为 `null`。当前不绘制 `rowspan` 的跨行合并。 */
    data class Table(
        val caption: PiHtmlNodes?,
        val rows: PiHtmlTableRows,
    ) : PiHtmlNode

    /** `<img>`。只保存 URL/替代文本，不在这里取字节。 */
    data class Image(
        val src: String,
        val alt: String,
        val title: String?,
    ) : PiHtmlNode

    /** `<a>`。[href] 保留原文，是否可点由渲染层按协议再次判断。 */
    data class Link(
        val href: String,
        val title: String?,
        val children: PiHtmlNodes,
    ) : PiHtmlNode

    /** `<pre>` / `<code>`；[block] 区分块级代码和行内代码。 */
    data class Code(
        val text: String,
        val language: String?,
        val block: Boolean,
    ) : PiHtmlNode
}

/** 资源上限；超出后由调用方回退源码。 */
data class PiHtmlLimits(
    /** 原始 HTML 的字符上限（方案 §十五：256 KiB）。 */
    val maxChars: Int = 256 * 1024,
    /** 生成的 [PiHtmlNode] 总数上限。 */
    val maxNodes: Int = 20_000,
    /** 嵌套深度上限（顶层块为 1）。 */
    val maxDepth: Int = 64,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
    }
}

/** [PiHtmlDocument.parse] 的失败原因。每一种都对应 [PiHtmlBlockView] 的一行提示。 */
sealed class PiHtmlFailure {
    /** 仍在流式生成，未到 settled。 */
    object NotSettled : PiHtmlFailure()

    /** 原始内容为空。 */
    object Empty : PiHtmlFailure()

    /** 原始内容或清理后的内容超过 [PiHtmlLimits.maxChars]。 */
    object TooLarge : PiHtmlFailure()

    /** 嵌套超过 [PiHtmlLimits.maxDepth]。 */
    object TooDeep : PiHtmlFailure()

    /** 节点数超过 [PiHtmlLimits.maxNodes]。 */
    object TooManyNodes : PiHtmlFailure()

    /**
     * 防御性拒绝：策略自身抛错，或返回了无法识别的结果。正常的资源上限拒绝
     * 已经在上面的 [TooLarge] / [TooDeep] / [TooManyNodes] 中；[reason] 是原始
     * 错误信息，可能为空。
     */
    data class Rejected(val reason: String) : PiHtmlFailure()

    /** 安全清理后没有可见内容。 */
    object EmptyAfterPolicy : PiHtmlFailure()

    /** jsoup 解析失败（极少见；不是“标签不闭合”，那种情况会被修复）。 */
    object Malformed : PiHtmlFailure()
}

/** 解析结果。失败不带源码，调用方自己保存着原文。 */
sealed interface PiHtmlParseResult {
    data class Success(val nodes: PiHtmlNodes) : PiHtmlParseResult
    data class Failure(val reason: PiHtmlFailure) : PiHtmlParseResult
}

/**
 * DOM → [PiHtmlNode] 的构建器。
 *
 * ## 为什么还要“块/行内”两套遍历
 *
 * HTML 允许 `<div>文字<p>段落</p>文字</div>` 这种混排。直接按 DOM 顺序转节点会让
 * Compose 把三段文字拆成三个段落。这里按浏览器默认显示模型分组：
 *
 * - 连续的文本/行内标签先攒进一个缓冲区，遇到块标签时变成一个合成的
 *   `Block("p", ...)`；
 * - 块标签递归成块序列；
 * - 未知标签**透明化**：如果它含块级子元素，就当块容器递归；否则当行内包装，
 *   只保留它的子节点。两种情况都保留文字，不做白名单删除（白名单在 Policy）。
 *
 * ## 失败传播
 *
 * 不用异常。`failure` 一旦置位，所有构建函数立即返回空列表；顶层看到
 * `failure != null` 就返回 [PiHtmlParseResult.Failure]。这样深层输入即使触发上限
 * 也不会把栈展开成异常。
 */
private class PiHtmlTreeBuilder(private val limits: PiHtmlLimits) {

    private var nodeCount = 0
    private var charCount = 0

    var failure: PiHtmlFailure? = null
        private set

    fun buildBody(body: Element): PiHtmlNodes? {
        val nodes = blockSequence(body.childNodes(), depth = 1)
        if (failure != null) return null
        return nodes
    }

    // ---------------------------------------------------------------- blocks

    private fun blockSequence(rawNodes: List<Node>, depth: Int): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (depth > limits.maxDepth) {
            failure = PiHtmlFailure.TooDeep
            return emptyList()
        }

        val out = ArrayList<PiHtmlNode>()
        val inlineBuffer = ArrayList<PiHtmlNode>()

        fun flushInline() {
            if (failure != null || inlineBuffer.isEmpty()) return
            val hasVisibleContent = inlineBuffer.any {
                it !is PiHtmlNode.Text || it.text.isNotBlank()
            }
            if (hasVisibleContent && tick()) {
                out.add(PiHtmlNode.Block("p", inlineBuffer.toList()))
            }
            inlineBuffer.clear()
        }

        for (node in rawNodes) {
            if (failure != null) break
            when (node) {
                is TextNode -> {
                    val text = normalizeWhitespace(node.wholeText)
                    if (text.isNotEmpty()) inlineBuffer.add(textNode(text))
                }

                // `script`/`style` 的正文在 jsoup 里是 DataNode，不是 TextNode。策略通常
                // 会删掉它们；如果漏了，也按普通文字保留，绝不执行。
                is DataNode -> {
                    val text = normalizeWhitespace(node.wholeData)
                    if (text.isNotEmpty()) inlineBuffer.add(textNode(text))
                }

                is Element -> {
                    if (isBlockElement(node) || hasBlockChild(node)) {
                        flushInline()
                        if (failure == null) out.addAll(blockElement(node, depth + 1))
                    } else {
                        inlineBuffer.addAll(inlineElement(node, depth + 1))
                    }
                }

                // 注释、Doctype、XML 声明都不是可见内容，也没有可渲染的文字节点；
                // HTML 语义下注释不显示（fixture ht-comment 的口径仍待父代理裁定）。
                else -> Unit
            }
        }

        if (failure == null) flushInline()
        return out
    }

    private fun blockElement(element: Element, depth: Int): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (depth > limits.maxDepth) {
            failure = PiHtmlFailure.TooDeep
            return emptyList()
        }

        return when (element.tagName()) {
            "p" -> block("p", inlineSequence(element.childNodes(), depth + 1))
            "div", "blockquote", "figure", "figcaption", "li" ->
                block(element.tagName(), blockSequence(element.childNodes(), depth + 1))
            "details" -> details(element, depth + 1)
            "ul", "ol" -> list(element, depth + 1)
            "table" -> table(element, depth + 1)
            "pre" -> codeBlock(element, depth + 1)
            "hr" -> block("hr", emptyList())
            else -> blockSequence(element.childNodes(), depth + 1)
        }
    }

    private fun details(element: Element, depth: Int): PiHtmlNodes {
        val summaryElement = element.children().firstOrNull { it.tagName() == "summary" }
        val summary = if (summaryElement == null) {
            emptyList()
        } else {
            inlineSequence(summaryElement.childNodes(), depth + 1)
        }
        val bodyNodes = ArrayList<Node>()
        for (child in element.childNodes()) {
            if (child !== summaryElement) bodyNodes.add(child)
        }
        val body = blockSequence(bodyNodes, depth + 1)
        if (failure != null) return emptyList()
        return node(PiHtmlNode.Details(element.hasAttr("open"), summary, body))
    }

    private fun list(element: Element, depth: Int): PiHtmlNodes {
        val ordered = element.tagName() == "ol"
        val start = if (ordered) {
            // Policy 只复制合法的 HTML 整数；这里保留它的值（包括负数），渲染层按
            // `start + index` 显示。超长/非法的 start 在策略层已被丢弃。
            element.attr("start").toIntOrNull() ?: 1
        } else {
            1
        }
        val items = ArrayList<PiHtmlListItem>()

        for (child in element.childNodes()) {
            if (failure != null) break
            when (child) {
                is Element -> {
                    val children = blockSequence(child.childNodes(), depth + 1)
                    if (children.isNotEmpty() && tick()) {
                        items.add(PiHtmlListItem(children))
                    }
                }

                is TextNode -> {
                    val text = normalizeWhitespace(child.wholeText)
                    if (text.isNotBlank()) {
                        val children = blockSequence(listOf(child), depth + 1)
                        if (children.isNotEmpty() && tick()) {
                            items.add(PiHtmlListItem(children))
                        }
                    }
                }

                else -> Unit
            }
        }

        if (failure != null) return emptyList()
        return node(PiHtmlNode.List(ordered, start, items))
    }

    private fun table(element: Element, depth: Int): PiHtmlNodes {
        val captionNodes = ArrayList<PiHtmlNode>()
        val captionElement = element.children().firstOrNull { it.tagName() == "caption" }
        if (captionElement != null) {
            captionNodes.addAll(inlineSequence(captionElement.childNodes(), depth + 1))
        }
        // Policy 的白名单不含 `caption`：它会丢掉标签、保留文字，于是说明文字变成
        // `<table>` 的直接文本节点。这里把它收进 caption，避免表格说明静默消失。
        for (child in element.childNodes()) {
            if (failure != null) break
            if (child is TextNode) {
                val text = normalizeWhitespace(child.wholeText)
                if (text.isNotBlank()) captionNodes.add(textNode(text))
            }
        }
        val caption = captionNodes.takeIf { it.isNotEmpty() }

        val rows = ArrayList<PiHtmlTableRow>()

        fun appendRow(row: Element, header: Boolean) {
            if (failure != null) return
            val cells = ArrayList<PiHtmlTableCell>()
            for (cellElement in row.children()) {
                if (failure != null) break
                val cellTag = cellElement.tagName()
                if (cellTag != "td" && cellTag != "th") continue
                val children = blockSequence(cellElement.childNodes(), depth + 1)
                val colspan = cellElement.attr("colspan")
                    .toIntOrNull()?.coerceIn(1, 16) ?: 1
                val rowspan = cellElement.attr("rowspan")
                    .toIntOrNull()?.coerceIn(1, 16) ?: 1
                if (tick()) {
                    cells.add(
                        PiHtmlTableCell(
                            header = header || cellTag == "th",
                            colspan = colspan,
                            rowspan = rowspan,
                            children = children,
                        ),
                    )
                }
            }
            if (cells.isNotEmpty() && tick()) {
                rows.add(PiHtmlTableRow(header = header, cells = cells))
            }
        }

        for (child in element.children()) {
            if (failure != null) break
            when (child.tagName()) {
                "thead" -> child.children().forEach { row ->
                    if (row.tagName() == "tr") appendRow(row, header = true)
                }

                "tbody", "tfoot" -> child.children().forEach { row ->
                    if (row.tagName() == "tr") appendRow(row, header = false)
                }

                "tr" -> appendRow(child, header = false)
            }
        }

        if (failure != null) return emptyList()
        return node(PiHtmlNode.Table(caption, rows))
    }

    private fun codeBlock(element: Element, depth: Int): PiHtmlNodes {
        if (failure != null) return emptyList()
        val codeElement = element.children().firstOrNull { it.tagName() == "code" }
        val sourceElement = codeElement ?: element
        val text = normalizePre(sourceElement.wholeText())
        val language = codeLanguage(sourceElement) ?: codeLanguage(element)
        if (text.isBlank()) {
            // 空 <pre> 没有可显示内容，但也不能因此报失败。
            return block("pre", emptyList())
        }
        return node(PiHtmlNode.Code(text = text, language = language, block = true))
    }

    // --------------------------------------------------------------- inlines

    private fun inlineSequence(rawNodes: List<Node>, depth: Int): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (depth > limits.maxDepth) {
            failure = PiHtmlFailure.TooDeep
            return emptyList()
        }

        val out = ArrayList<PiHtmlNode>()
        for (node in rawNodes) {
            if (failure != null) break
            when (node) {
                is TextNode -> {
                    val text = normalizeWhitespace(node.wholeText)
                    if (text.isNotEmpty()) out.add(textNode(text))
                }

                is DataNode -> {
                    val text = normalizeWhitespace(node.wholeData)
                    if (text.isNotEmpty()) out.add(textNode(text))
                }

                is Element -> out.addAll(inlineElement(node, depth + 1))
                else -> Unit
            }
        }
        return out
    }

    private fun inlineElement(element: Element, depth: Int): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (depth > limits.maxDepth) {
            failure = PiHtmlFailure.TooDeep
            return emptyList()
        }

        return when (element.tagName()) {
            "br" -> node(PiHtmlNode.Inline("br", emptyList()))
            "img" -> node(
                PiHtmlNode.Image(
                    src = element.attr("src").trim(),
                    alt = element.attr("alt"),
                    title = element.attr("title").takeIf { it.isNotBlank() },
                ),
            )

            "code" -> node(
                PiHtmlNode.Code(
                    text = normalizeWhitespace(element.wholeText()),
                    language = codeLanguage(element),
                    block = false,
                ),
            )

            "a" -> {
                val children = inlineSequence(element.childNodes(), depth + 1)
                node(
                    PiHtmlNode.Link(
                        href = element.attr("href").trim(),
                        title = element.attr("title").takeIf { it.isNotBlank() },
                        children = children,
                    ),
                )
            }

            // 已知行内标签和未知标签走同一条路：包装成 Inline，渲染器只对认识的
            // 标签加样式，其余标签透明地画子节点。文字不会因为“不认识”而消失。
            else -> {
                val children = inlineSequence(element.childNodes(), depth + 1)
                node(PiHtmlNode.Inline(element.tagName(), children))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun block(tag: String, children: PiHtmlNodes): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (children.isEmpty() && tag != "hr") return emptyList()
        if (!tick()) return emptyList()
        return listOf(PiHtmlNode.Block(tag, children))
    }

    private fun node(value: PiHtmlNode): PiHtmlNodes {
        if (failure != null) return emptyList()
        if (!tick()) return emptyList()
        return listOf(value)
    }

    private fun textNode(text: String): PiHtmlNode {
        charCount += text.length
        if (charCount > limits.maxChars) failure = PiHtmlFailure.TooLarge
        tick()
        return PiHtmlNode.Text(text)
    }

    private fun tick(): Boolean {
        if (failure != null) return false
        nodeCount++
        if (nodeCount > limits.maxNodes) {
            failure = PiHtmlFailure.TooManyNodes
            return false
        }
        return true
    }

    private fun isBlockElement(element: Element): Boolean =
        element.tagName() in BLOCK_TAGS

    /**
     * 未知标签是否至少有一个直接的块级子元素。只查一层就够了：更深的未知标签会在
     * 递归里自己再判一次；查深了反而会在 256 KiB 输入上做 O(n²) 的扫描。
     */
    private fun hasBlockChild(element: Element): Boolean =
        element.children().any { it.tagName() in BLOCK_TAGS }

    private fun codeLanguage(element: Element): String? {
        for (className in element.classNames()) {
            val raw = when {
                className.startsWith("language-") -> className.removePrefix("language-")
                className.startsWith("lang-") -> className.removePrefix("lang-")
                else -> null
            } ?: continue
            val clean = cleanLanguage(raw)
            if (clean != null) return clean
        }
        val attr = element.attr("data-language").trim()
        return cleanLanguage(attr)
    }

    private fun cleanLanguage(raw: String): String? {
        if (raw.isEmpty()) return null
        val out = StringBuilder(minOf(raw.length, 32))
        for (ch in raw) {
            if (out.length >= 32) break
            if (ch.isLetterOrDigit() || ch in "+#._-") out.append(ch.lowercaseChar())
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * 普通 HTML 文本的空白折叠：连续空白 → 一个空格。**不** trim 两端：
     * `<b>a</b> b` 的文本节点以空格开头，去掉会把两个词粘在一起；块首/块尾
     * 多一个空格由 Text 排版吸收，视觉上不出现。
     */
    private fun normalizeWhitespace(raw: String): String {
        val out = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw) {
            if (ch == '\u0000') continue
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    out.append(' ')
                    lastWasSpace = true
                }
            } else {
                out.append(ch)
                lastWasSpace = false
            }
        }
        return out.toString()
    }

    /** `<pre>` 内文本：只统一换行，不折叠空白。浏览器会忽略 `pre` 开头紧跟的一个换行。 */
    private fun normalizePre(raw: String): String {
        val normalized = if (raw.indexOf('\r') >= 0) {
            raw.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            raw
        }
        return if (normalized.startsWith("\n")) normalized.substring(1) else normalized
    }

    private companion object {
        /**
         * 浏览器默认块级（含方案白名单和少量标准块元素）。不在表里的标签按行内
         * 包装处理；未知块标签若含这些子元素，会先被 [hasBlockChild] 识别成块，
         * 再在 [blockElement] 的 `else` 分支里透明展开。
         */
        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "details", "div",
            "dl", "dd", "dt", "fieldset", "figcaption", "figure", "footer",
            "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
            "li", "main", "nav", "ol", "p", "pre", "section", "table",
            "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
        )
    }
}
