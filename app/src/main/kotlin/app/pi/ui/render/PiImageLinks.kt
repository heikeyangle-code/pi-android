package app.pi.ui.render

/**
 * 把「**一整行 / 一整个表格单元格里只有一个图片 URL**」的裸链接改写成 markdown 图片
 * `![](...)`，别的一律不碰。
 *
 * ## 为什么要有这一步
 *
 * 用户报的是「URL 的图倒是能渲染，为什么没法点击」之后的下一条：**同一个 URL，写成
 * `![]()` 就出图，写成裸链接就只有一行蓝字**。同一个回合里模型两种写法都会用（它不一定
 * 记得"要加感叹号"），于是转写里"有的成功、有的不成功"，而用户看到的是同一个链接。
 * 例：`https://upload.wikimedia.org/.../1920px-A.jpg` 与
 * `![](https://upload.wikimedia.org/.../1920px-A.jpg)` 是同一张图，前者今天只是链接。
 *
 * 图片本身的取字节、解码、缓存、点开查看器这一整条路**早就有了**
 * （`bridge/GuestImageBytes.kt` → `bridge/PiGuestImageTransformer.kt` →
 * `PiMarkdownComponents.kt` 的两个图片槽 → `PiImageViewer`），缺的只是"把裸 URL 交给它"
 * 这一步。所以这里只做一件事：**在解析之前把源文本改写成那个已经支持的写法**。
 *
 * ## 这是与 pi 的一处**故意**不同，登记在 `docs/known-gaps.md` §A3
 *
 * pi 的终端渲染器没有 `image` 分支（`packages/tui/src/components/markdown.ts:619-627`），
 * 裸 URL 与 `![]()` 在它那里**都是文本**，所以"把裸 URL 变成图"这件事在 pi 里没有对应
 * 物。它比 pi 强，不是补齐 pi。
 *
 * ## 改写后的形式：为什么是 `![](...)`，为什么括号要转义
 *
 * 用真解析器（`org.jetbrains:markdown` 0.7.9 + GFM，与本 App 同一个 pin）跑过 AST，结论
 * 钉在下面这张表里 —— 它决定了这里只敢认哪几种 URL：
 *
 * | 源文本 | 解析结果 |
 * | --- | --- |
 * | `![](https://a/B_c_d.jpg)` | ✅ `IMAGE` → `LINK_DESTINATION` = 原 URL（下划线没事） |
 * | `![](https://a/Foo_(bar).jpg)` | ✅ 括号**成对**时 `LINK_DESTINATION` = 原 URL |
 * | `![](https://a/Foo_\(bar\).jpg)` | ✅ 转义后仍解析成 `IMAGE`，且库的 `EntityConverter`（`processEscapes = true`）把 `\(` 还原成 `(` |
 * | `![](https://a/Foo_(bar.jpg)` | ❌ 括号不成对 → **连 `IMAGE` 都不是**，`![](` 退化成纯文本、URL 退化成 GFM 自动链接 |
 * | `![](<https://a/Foo_(bar).jpg>)` | ❌ 尖括号目标解析成 `AUTOLINK` 而不是 `LINK_DESTINATION`，库的 `resolveImageLink` 找不到目标 → 返回 `null` |
 *
 * 所以规则是：**括号必须成对**（否则放弃改写、保持今天的样子），成对时**逐对转义**
 * （`\(` `\)`），下划线之类不动。最后一条排除了"用 `<...>` 包住 URL"这条看起来更省事的
 * 路 —— 库那个函数只找 `LINK_DESTINATION`，尖括号写法它认不出来。
 *
 * ## 只认哪几种 URL：**后缀白名单**，不是"看起来像 URL 就当图片"
 *
 * 白名单就是这条通道真的画得出来的五种后缀（`bridge/GuestImageBytes.kt` 的 `sniff`/`mimeOf`
 * 认得 PNG / JPEG / GIF / WEBP / BMP）：`.jpg .jpeg .png .gif .webp .bmp`。判定看
 * **路径**（去掉 `?query` 与 `#fragment` 之后）。
 *
 * 为什么不"先当成图片试一次，失败了再退回链接"：那会把每个指向非图片的 URL（`…/data.json`、
 * API 链接）变成一块「图片 / 图片地址…（当前无法显示）」的回退框，比今天那行可点的链接**更差**。
 * 代价明写：**没有后缀的图片 URL**（`https://picsum.photos/1200/800`、各种 `/image?id=3`）
 * 不在白名单里，仍然只显示成链接 —— 这是保守一侧的取舍，不是遗漏。
 *
 * ## 什么时候不改
 *
 * - **行内不是"只有一个 URL"**：`见 https://a/b.jpg 这里` 原样保留（一句话里嵌一张图是
 *   另一件事，改了会把句子切开）。
 * - **代码**：围栏代码块（``` 与 ~~~，含 info string 与收栏长度规则）、行内代码
 *   （反引号）、以及 ≥4 空格缩进的缩进代码块都不动 —— 那里面的 `https://…` 是**要给人看的
 *   字**，画成图就等于把代码内容改了。
 * - **表格单元格**里只有一格是 URL 时改那一格，别的格子与分格符一个字符都不动；分隔行
 *   （`| --- | --- |`）没有 URL，自然不会命中。
 * - URL 里出现 `\`、反引号、引号、`|`、`<`、`>`、方括号、花括号或空白时放弃（那些字符会让
 *   目标串的边界变得不确定）。
 * - 括号不成对时放弃（见上表）。
 *
 * ## 不变量
 *
 * 除了被改写的那一段，**输出与输入逐字节相同**（换行、行尾空白、CRLF 的 `\r`、缩进、表格
 * 对齐空格都保留）；没有 `://` 的源直接返回**同一个实例**（流式转写的绝大多数帧走这一条）。
 * 幂等：改写结果是 `![](...)`，行首不是 scheme，再跑一次不会二次改写。
 *
 * ## 代价与位置
 *
 * 它是纯字符串处理（不碰 Compose、不碰 Android），由
 * `app/src/test/kotlin/app/pi/ui/render/PiImageLinksCheck.kt` 在裸 JVM 上真跑
 * （`tools/run-app-pure-checks.sh` 的 `image-links` 项）。每段 markdown 只跑一次
 * （`PiMarkdownText` 里 `remember(prepared.text)`），**不在**每帧路径上；流式下每个 token
 * 一次，所以是一趟线性扫描 + 快路径。
 *
 * 顺序上它排在 [PiLatex.prepare] **之后**：公式预处理先看原文，改写写进去的
 * `![](...)` 不会再被公式规则扫到（URL 里出现两个 `$` 时那种误判因此不会因为这次改动
 * 而变多）。反过来也不成立 —— `PiLatex` 写回的网格是多行文本，而这里只认"整行/整格就是
 * 一个 URL"，网格里的 `│`（U+2502）不是 ASCII `|`，不可能被当成表格分格符。
 */
internal object PiImageLinks {

    /** 改写的开关字符：一段文本里连 `://` 都没有时，一个字符都不用看。 */
    private const val SCHEME_MARKER = "://"

    /** 缩进几格以内还算"正文行"；≥4 空格是 markdown 的缩进代码块。 */
    private const val MAX_INDENT = 3

    /**
     * URL 里**不允许**出现的字符。它们要么会打断 markdown 的目标串（空白、`<`、`>`、
     * 反引号、引号），要么在表格单元格里是分格符（`|`），要么本身就是 markdown 的转义
     * 字符（`\`），要么会被解释成配对括号/方括号（`[` `]` `{` `}`）。括号 `(` `)` 不在此列
     * —— 它们允许出现，但要成对（见 [balancedParens]）。
     */
    private const val URL_FORBIDDEN = " \t\r\n<>`\"'|\\[]{}"

    /**
     * 白名单：这条通道真的解得出来的后缀（`GuestImageBytes` 的 `sniff` 与 `mimeOf` 认得
     * PNG / JPEG / GIF / WEBP / BMP 五种）。带点比较，所以 `.jpeg` 不会被 `.jpg` 误命中。
     */
    private val IMAGE_SUFFIXES = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")

    /** 围栏代码块的开栏：≤3 空格缩进 + 三个以上的 `` ` `` 或 `~`。 */
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")

    /**
     * 改写整段 markdown。见类注释的规则与不变量；返回值与入参**逐字节相同**时说明这段
     * 文本里没有可改写的东西（不是错误）。
     */
    fun rewrite(markdown: String): String {
        // 快路径：没有 `://` 的源连行都不用切。流式转写里这是绝大多数帧。
        if (markdown.indexOf(SCHEME_MARKER) < 0) return markdown
        val out = StringBuilder(markdown.length)
        var start = 0
        var fence: Fence? = null
        while (true) {
            val newline = markdown.indexOf('\n', start)
            val end = if (newline < 0) markdown.length else newline
            val line = markdown.substring(start, end)
            if (fence != null) {
                // 围栏里逐行原样拷贝，只找收栏。
                out.append(line)
                if (closesFence(line, fence)) fence = null
            } else {
                val opened = opensFence(line)
                if (opened != null) {
                    out.append(line)
                    fence = opened
                } else {
                    out.append(rewriteLine(line))
                }
            }
            if (newline < 0) break
            out.append('\n')
            start = newline + 1
        }
        return out.toString()
    }

    // ------------------------------------------------------------- 一行之内 ----

    /**
     * 一行。`\r`（CRLF）与行尾空白原样留在外面；**缩进 ≥4 空格、或者缩进里有 Tab** 直接
     * 放弃（缩进代码块）。
     *
     * Tab 那条是 harness 抓出来的真 bug：`\thttps://a/x.jpg` 这种行，按"空格数"数缩进是 0，
     * 于是它会被当成正文改写；而 markdown 里 **一个 Tab 展开到下一个 4 的倍数**（CommonMark：
     * Tab 停在 4 的整数列上），也就是 ≥4 空格 —— 缩进代码块。既然 Tab 出现在缩进段里就说明
     * 它落在第 4 列之前（否则前面那 4 个空格已经把它挡在缩进段之外），所以规则可以很干脆：
     * **缩进段里只要有一个 Tab，就不动这一行。**
     */
    private fun rewriteLine(line: String): String {
        if (line.indexOf(SCHEME_MARKER) < 0) return line
        // 行尾空白（含 CRLF 的 `\r`）不算内容，但要一位不差地留在输出里。
        val bodyEnd = line.indexOfLast { it != ' ' && it != '\t' && it != '\r' } + 1
        if (bodyEnd <= 0) return line
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.any { it == '\t' } || leading.length > MAX_INDENT) return line
        val indent = leading.length
        if (indent >= bodyEnd) return line
        val core = line.substring(indent, bodyEnd)
        // 含 `|` 的行按表格行处理：GFM 里未转义的 `|` **就是**分格符（要在格子里写它
        // 必须转义成 `\|`），所以切开是符合规范的，而不是猜。
        val rewritten = if (core.indexOf('|') >= 0) rewriteCells(core) else rewriteCell(core)
        if (rewritten == core) return line
        return line.substring(0, indent) + rewritten + line.substring(bodyEnd)
    }

    /** 表格行：按**未转义**的 `|` 切格，逐格按 [rewriteCell] 处理，分格符原样拼回。 */
    private fun rewriteCells(row: String): String {
        val out = StringBuilder(row.length)
        var index = 0
        while (true) {
            val bar = nextUnescapedBar(row, index)
            if (bar < 0) {
                out.append(rewriteCell(row.substring(index)))
                return out.toString()
            }
            out.append(rewriteCell(row.substring(index, bar)))
            out.append('|')
            index = bar + 1
        }
    }

    /**
     * 一"格"：内容（去掉首尾空白）正好是一个白名单里的图片 URL 时换成 `![](...)`，
     * 否则原样返回**同一个** `cell`（调用方靠 `==` 判断有没有改动）。
     */
    private fun rewriteCell(cell: String): String {
        val start = cell.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return cell
        val end = cell.indexOfLast { !it.isWhitespace() } + 1
        val image = asImage(cell.substring(start, end)) ?: return cell
        return cell.substring(0, start) + image + cell.substring(end)
    }

    // ------------------------------------------------------------ URL 判定 ----

    /** `text` 是一个可改写的图片 URL 时给出 `![](...)`，否则 `null`。 */
    private fun asImage(text: String): String? {
        val url = bareImageUrl(text) ?: return null
        // 成对的圆括号必须转义，否则 markdown 的目标串会提前结束（见类注释那张表）。
        val destination = url.replace("(", "\\(").replace(")", "\\)")
        return "![]($destination)"
    }

    /** 裸图片 URL：scheme 对、字符集允许、括号成对、后缀在白名单里；否则 `null`。 */
    private fun bareImageUrl(text: String): String? {
        val isHttp = text.regionMatches(0, "https://", 0, 8, ignoreCase = true)
        val isMaybeHttp = isHttp || text.regionMatches(0, "http://", 0, 7, ignoreCase = true)
        if (!isMaybeHttp) return null
        for (char in text) {
            if (char.code < 0x20 || char in URL_FORBIDDEN) return null
        }
        if (!balancedParens(text)) return null
        if (!hasImageSuffix(text)) return null
        return text
    }

    /** 路径（去掉 `?query` 与 `#fragment`）以白名单里的后缀结尾。大小写不敏感。 */
    private fun hasImageSuffix(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return IMAGE_SUFFIXES.any { path.endsWith(it) }
    }

    /**
     * 圆括号成对（且中途不下穿）。**不**处理 `\(`：`\` 在 [URL_FORBIDDEN] 里，带反斜杠的
     * URL 根本走不到这里。
     */
    private fun balancedParens(url: String): Boolean {
        var depth = 0
        for (char in url) {
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    // --------------------------------------------------------------- 工具 ----

    /** 下一个**未转义**的 `|`（前面有奇数个反斜杠就是转义的），没有则 `-1`。 */
    private fun nextUnescapedBar(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            if (text[index] == '|') {
                var backslashes = 0
                var probe = index - 1
                while (probe >= from && text[probe] == '\\') {
                    backslashes++
                    probe--
                }
                if (backslashes % 2 == 0) return index
            }
            index++
        }
        return -1
    }

    /** 一次围栏的开栏：字符（`` ` `` 或 `~`）与它的长度。 */
    private class Fence(val char: Char, val length: Int)

    private fun opensFence(line: String): Fence? {
        val match = FENCE.find(line) ?: return null
        val run = match.groupValues[1]
        return Fence(run[0], run.length)
    }

    /**
     * 收栏：同一种字符、不短于开栏，且后面（info string 的位置）只剩空白。
     * ` ``` ```kotlin ` 因此**不是**收栏（CommonMark 的收栏不许带 info string）。
     */
    private fun closesFence(line: String, fence: Fence): Boolean {
        val match = FENCE.find(line) ?: return false
        val run = match.groupValues[1]
        if (run[0] != fence.char || run.length < fence.length) return false
        return line.substring(match.range.last + 1).isBlank()
    }
}
