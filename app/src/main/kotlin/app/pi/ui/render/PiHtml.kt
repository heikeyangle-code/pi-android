package app.pi.ui.render

/**
 * 裸 HTML：**pi 从不渲染 HTML，它把源码当一个字符都不丢的文字排出来。**
 *
 * ## pi 的两条分支（1.0.1，`packages/tui/src/components/markdown.ts`）
 *
 * 块级（`:622`）：
 * ```ts
 * case "html":
 *     // Render HTML as plain text (escaped for terminal)
 *     if ("raw" in token && typeof token.raw === "string") {
 *         lines.push(this.applyDefaultStyle(token.raw.trim()));
 *     }
 *     break;
 * ```
 * 行内（`:731`）：
 * ```ts
 * case "html":
 *     // Render inline HTML as plain text
 *     if ("raw" in token && typeof token.raw === "string") {
 *         result += applyTextWithNewlines(token.raw);
 *     }
 *     break;
 * ```
 * 也就是说：块级是 `raw.trim()`（内部换行**原样保留**，`renderToken` 把整串推进 `lines`
 * 之后由 `wrapTextWithAnsi` 按 `\n` 拆行），行内是 `raw`（原样，连标签一起当可见文字）。
 * pi 的效果是「一个字都不丢」—— `<div>\nhello\n</div>` 在终端上就是三行。
 *
 * ## 这里只放**规则**，不放调用点
 *
 * 两个函数分别对应上面两条分支，调用点各自在需要它的那一层：
 *  - [blockTextAt] 由 `ui/render/PiMarkdownComponents.kt` 的块级组件调用（库的分派里
 *    `HTML_BLOCK` 没有内建分支，只有 `custom` 槽能接住它）；
 *  - [inlineTextAt] 由 `ui/render/PiMarkdown.kt` 的 annotator 认领钩子调用（行内 HTML
 *    是段落里的一个 token，只有 annotator 能把它 append 进去）。
 *
 * 拆出来放在这个**零 import** 的文件里只有一个理由：`PiMarkdown.kt` 与
 * `PiMarkdownComponents.kt` 都带 Compose import，编译不了，而"哪个字符会被丢掉"恰恰是
 * 错了不会报错、只会安静地少画一段的那种事。放在这里就能被
 * `app/src/test/kotlin/app/pi/ui/render/PiHtmlCheck.kt` 编译并对着 **pi 自己渲染出来的
 * 字节**断言（夹具 `app/src/test/resources/pi-html-fixtures/cases.json`，期望值由
 * 同目录的 `collect-pi-html-pitext.mjs` 跑 pi 的 `Markdown.render` 生成，不是手抄）。
 *
 * ## 为什么 `trim` 要自己写
 *
 * pi 用的是 JS 的 `String.prototype.trim`，它去掉的是
 * **WhiteSpace ∪ LineTerminator**（ES 规范：`\t \n \v \f \r`、空格、NBSP、ZWNBSP
 * `\uFEFF`、以及全部 `Zs` 空格）。Kotlin 的 `String.trim()` 用的是
 * `Char.isWhitespace()`，集合**不一样**：
 *  - Kotlin 会吃掉 `\u001C`–`\u001F`，JS 不会；
 *  - JS 会吃掉 `\uFEFF`（ZWNBSP，也就是文件开头的 BOM），Kotlin 不会。
 * 分歧点**只有这五个码位**（本机对 0..0xFFFF 逐码位比过：两边共有 24 个，JS 多 `FEFF`、
 * Kotlin 多 `001C`–`001F`）。用 `trim()` 会在"块级 HTML 的开头正好是一个 BOM"这类输入上
 * 差一个字符，而夹具把两边都钉死了：`trimCases` 是样本，`jsTrimRanges` 是 pi 那侧的**全集**
 * （`PiHtmlCheck` 对着它逐个码位断言 [isJsTrimChar]，并同时断言我们**不等于** Kotlin 的
 * `trim()` —— 真有人把它换掉，这两条会一起红）。
 *
 * ## 为什么 `\t` / `\r` 要归一
 *
 * pi 在词法分析之前对**整篇**源文本做了两步归一：`render()` 里的
 * `text.replace(/\t/g, "   ")`（`components/markdown.js:194`）与 marked 词法器自己的
 * `\r\n|\r` → `\n`。所以 pi 拿到的 `token.raw` 已经是归一后的字节，而 App 手里的源文本
 * 是原样的。块级 HTML 这一个 token 要逐字节等于 pi，就得在**这一片切片**上补同样的两步
 * （夹具的 `b-tab-indent` / `b-crlf` / `b-tab-and-crlf` 是三条端到端的断言）。
 *
 * 注意这条只作用在 HTML 的切片上：段落里的 `\t`/`\r` 仍是既有的全局偏差（pi 整篇归一、
 * App 不归一），不属这一次的范围，也没有被这里碰。行内 HTML 同理补上（夹具的
 * `i-tab-in-tag`：pi 把标签里的 `\t` 写成三个空格）。
 */
internal object PiHtml {

    /**
     * 行内裸 HTML 在 `org.jetbrains:markdown` 里的节点类型名。它出现在**段落**（以及
     * 行内代码、链接文本）的子节点里，所以只有 annotator 这个缝能接住它；库的 annotator
     * 对 `HTML_TAG` 落进 `else` 分支、什么都不 append
     * （`com.mikepenz.markdown.annotator.AnnotatedStringKtxKt.buildMarkdownAnnotatedString`
     * 的 `else`），今天这些标签字符会**整段消失**。
     */
    const val INLINE_TYPE = "HTML_TAG"

    /**
     * 块级裸 HTML 的节点类型名。它是顶层节点，走的是库的组件分派而不是 annotator ——
     * 分派表里二十个内建 case 没有它，于是落进 `else -> components.custom?.invoke(...)`，
     * 而 App 的 `custom` 用无 `else` 的 `when` 只认 math，于是它被判为「已处理」、
     * 连子节点都不再递归，整块从转写里消失（`docs/render-inventory.md` §8.3 记的就是
     * 这个陷阱）。
     */
    const val BLOCK_TYPE = "HTML_BLOCK"

    /** 行内分支是否认领这个节点类型。**只认这一个名字**，别的一律交回库的默认处理。 */
    fun handlesInline(typeName: String): Boolean = typeName == INLINE_TYPE

    /** 块级分支是否认领这个节点类型。**只认这一个名字**。 */
    fun handlesBlock(typeName: String): Boolean = typeName == BLOCK_TYPE

    /**
     * pi 的块级分支：`applyDefaultStyle(token.raw.trim())`，`applyDefaultStyle` 在默认文本
     * 样式下是恒等（只有颜色/粗斜体等下划线才会动它）。
     *
     * 不做任何"看起来更像 HTML"的事：不解析、不折叠空白、不改大小写、不补换行。
     */
    fun blockTextAt(content: String, startOffset: Int, endOffset: Int): String =
        trimJs(normalizeJsSource(content.substring(startOffset, endOffset)))

    /**
     * pi 的行内分支：`applyTextWithNewlines(token.raw)` —— `applyText` 是默认文本样式，
     * 在 App 这一侧对应的就是"原样 append 进这一段的 `AnnotatedString`"。
     *
     * 与块级不同的是**不 trim**：行内标签两侧的空格属于周围的文本，不是标签的。
     */
    fun inlineTextAt(content: String, startOffset: Int, endOffset: Int): String =
        normalizeJsSource(content.substring(startOffset, endOffset))

    /**
     * pi 词法分析前对整篇源文本的两步归一，作用在一片切片上：
     * `\t` → 三个空格（`components/markdown.js:194`），`\r\n`/`\r` → `\n`（marked 的词法
     * 器）。没有任何 `\t`/`\r` 时**返回原对象**，不分配。
     */
    fun normalizeJsSource(raw: String): String {
        val firstTab = raw.indexOf('\t')
        val firstCr = raw.indexOf('\r')
        if (firstTab < 0 && firstCr < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '\t' -> out.append("   ")
                '\r' -> {
                    out.append('\n')
                    // JS 的 `/ \r\n | \r /`：两字符的候选优先，所以 `\r\n` 只吃一次。
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * JS 的 `String.prototype.trim`。两端的字符逐个按 [isJsTrimChar] 判，中间一律不动；
     * 不需要裁时**返回原对象**（块级切片通常两端本来就没有空白，这一步不分配）。
     */
    fun trimJs(raw: String): String {
        var start = 0
        var end = raw.length
        while (start < end && isJsTrimChar(raw[start])) start++
        while (end > start && isJsTrimChar(raw[end - 1])) end--
        if (start == 0 && end == raw.length) return raw
        return raw.substring(start, end)
    }

    /**
     * ES 规范的 WhiteSpace ∪ LineTerminator，一个不多一个不少：
     * TAB/VT/FF/SP/NBSP/ZWNBSP + 全部 `Zs`（`\u1680`、`\u2000`–`\u200A`、`\u202F`、
     * `\u205F`、`\u3000`）+ LF/CR/LS/PS。
     *
     * **不要换成 `Char.isWhitespace()`**：那是 Kotlin/JVM 的集合，多 `\u001C`–`\u001F`、
     * 少 `\uFEFF`（实测的完整差异集就这五个码位）。
     */
    fun isJsTrimChar(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ',
        '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
        -> true
        else -> c in '\u2000'..'\u200A'
    }
}
