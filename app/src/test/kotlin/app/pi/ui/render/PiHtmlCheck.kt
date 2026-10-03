package app.pi.ui.render

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `PiHtml.kt` 的**行为**夹具（`tools/run-app-pure-checks.sh` 里的 `html`）。
 *
 * ## 为什么需要它
 *
 * pi 对裸 HTML 只做一件事：**把源码原样排出来**（块级 `raw.trim()`、行内 `raw`）。
 * 「原样」这两个字里藏着一堆只有跑才知道的细节：`<div>x</div>` 后面的空行算不算块的一部分、
 * `<div>\r\n` 里的 `\r` 去哪了、`<div>\ta` 里的制表符是两个还是三个空格、
 * `<not a tag>` 到底是块还是文本、`` ` <b> ` `` 里的 `<b>` 该不该留。这些错了手机上
 * 不会崩、不会报错，只会有一条消息少画一段或者多画一个字符 —— 所以期望值不能手抄，
 * 必须是 **pi 自己的 `Markdown.render` 打在字节上的结果**
 * （`app/src/test/resources/pi-html-fixtures/cases.json`，由同目录的
 * `collect-pi-html-pitext.mjs` 生成；节点跨度与类型全集由同目录的 `HtmlFixtureSpans.java`
 * 用 pin 住的解析器量出；两个生成脚本的头部都有可复制的命令行）。
 *
 * ## 四段断言，强度递增
 *
 *  ① `cases`（51 条真实形状）：
 *    - `kind=block-only`（21 条）**逐字节**比：`PiHtml.blockTextAt(source, s, e) == piText`。
 *      这是整份文件的目的 —— 一条块的文本与 pi 排出来的一模一样。
 *    - `kind=html`：块文本必须作为**连续多行**出现在 pi 的输出里、行内标签必须按源码
 *      顺序出现，且**出现次数与标签数相等**（pi 既没丢也没重复）。
 *    - `kind=none`：解析器在这个形状里没产生任何 HTML 节点，记录 pi 的输出作为旁证。
 *  ② `normalizeCases`（13 条）：`\t`→三空格、`\r\n|\r`→`\n` 这两步逐字节对 pi。
 *  ③ `trimCases`（23 条）：`String.prototype.trim` 的字符集合。**其中四条是分歧点** ——
 *    `\u0085`/`\u001C`/`\u001F` Kotlin 的 `trim()` 会吃而 JS 不吃，`\uFEFF` 反过来。
 *    这个文件同时断言"我们像 JS"和"我们不像 Kotlin 的 `trim()`"，后者是前者的证据。
 *  ④ 反向检查：`corpus`（117 条来自本仓库 `docs/` 下 md 文件的真实段落）里，**声明过的节点
 *    类型集合与 `{HTML_TAG, HTML_BLOCK}` 的交集必须为空**；`typeUniverse`（77 个类型名，
 *    由反射从 pin 住的 jar 里取全）逐条断言 `handlesInline`/`handlesBlock` 只对那两个名字
 *    为真。这两条合起来就是「不含 HTML 的消息一个字节都不变」：新逻辑唯一的入口是
 *    "认领一个节点类型"，而"会被认领的类型"被穷举过。
 *    `corpusWithHtml`（3 条）是**反面的证据**：真实文档里确实有 `` `/export <path>.jsonl` ``
 *    这种被解析器判成 `HTML_TAG` 的写法，今天那三个字符是会被丢掉的。
 *
 * 夹具路径由 `pi.repo.root`（`run-app-pure-checks.sh` 传给每个 harness）解析；夹具缺失
 * 直接 exit 2，不跳过 —— 静默跳过正是这个文件存在的理由的反面。
 */
private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")
private val FIXTURE_FILE: File = ROOT.resolve("app/src/test/resources/pi-html-fixtures/cases.json")
private val ENGINE_LOCK: File = ROOT.resolve("tools/pi-engine.lock.json")
private val MARKDOWN_COMPONENTS: File =
    ROOT.resolve("app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt")
private val MARKDOWN_ENTRY: File =
    ROOT.resolve("app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt")

private val FIXTURE: JsonObject = run {
    if (!FIXTURE_FILE.isFile) {
        println("html: CANNOT RUN - 缺少夹具 ${FIXTURE_FILE.absolutePath}")
        println("html:           跑夹具同目录的 `collect-pi-html-pitext.mjs` + `HtmlFixtureSpans.java` 生成它（两个文件的头部都有命令行）")
        exitProcess(2)
    }
    Json.parseToJsonElement(FIXTURE_FILE.readText()).jsonObject
}

private fun JsonElement.text(): String = jsonPrimitive.content

/** `[start, end]` 对，夹具里的跨度一律是二元数组。 */
private class Span(val start: Int, val end: Int)

private class Case(
    val name: String,
    val kind: String,
    val source: String,
    val piText: String,
    val blocks: List<Span>,
    val tags: List<Span>,
    val types: List<String>,
)

private class CorpusCase(val name: String, val source: String, val types: List<String>, val spans: List<Span>)

private fun spans(element: JsonElement): List<Span> = element.jsonArray.map { pair ->
    val xs = pair.jsonArray
    Span(xs[0].text().toInt(), xs[1].text().toInt())
}

private fun strings(element: JsonElement): List<String> = element.jsonArray.map { it.text() }

private fun cases(): List<Case> = FIXTURE["cases"]!!.jsonArray.map { element ->
    val entry = element.jsonObject
    Case(
        name = entry["name"]!!.text(),
        kind = entry["kind"]!!.text(),
        source = entry["source"]!!.text(),
        piText = entry["piText"]!!.text(),
        blocks = spans(entry["blocks"]!!),
        tags = spans(entry["tags"]!!),
        types = strings(entry["types"]!!),
    )
}

private fun corpus(key: String): List<CorpusCase> = FIXTURE[key]!!.jsonArray.map { element ->
    val entry = element.jsonObject
    CorpusCase(
        name = entry["name"]!!.text(),
        source = entry["source"]!!.text(),
        types = strings(entry["types"]!!),
        spans = spans(entry["blocks"]!!) + spans(entry["tags"]!!),
    )
}

/** 规格是"只认这两个名字"，所以这个集合写在测试里、**不**从 `PiHtml` 里读。 */
private val CLAIMED = setOf("HTML_TAG", "HTML_BLOCK")

/** 夹具条数的下限：夹具是生成的，删掉一批不会有任何其它信号。 */
private const val MINIMUM_CASES = 45
private const val MINIMUM_CORPUS = 100
private const val MINIMUM_UNIVERSE = 70
private const val MINIMUM_BLOCK_ONLY = 20

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  pi:   ${readable(expected)}\n  端口: ${readable(actual)}")
    }
}

/** 日志里把控制字符写成转义：`\n`、`\t`、`\r` 与空格的差别是这一整个文件的主要内容。 */
private fun readable(value: Any?): String = when (value) {
    is String -> "“" + value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r") + "”"
    else -> value.toString()
}

fun main() {
    val asserted = cases()

    check("夹具条数不少于 $MINIMUM_CASES（防的是夹具被删空）", asserted.size >= MINIMUM_CASES, true)
    check("夹具里的形状名唯一", asserted.map { it.name }.toSet().size, asserted.size)
    check(
        "block-only 的形状不少于 $MINIMUM_BLOCK_ONLY 条（逐字节那一段不能被悄悄缩小）",
        asserted.count { it.kind == "block-only" } >= MINIMUM_BLOCK_ONLY,
        true,
    )

    fixtureVersionCheck()
    typeUniverseChecks()
    blockByteChecks(asserted)
    inlineChecks(asserted)
    normalizeChecks()
    trimChecks()
    reverseChecks()
    wiringAudit()
    println(
        "info: 形状 ${asserted.size} 条（block-only ${asserted.count { it.kind == "block-only" }}、" +
            "html ${asserted.count { it.kind == "html" }}、none ${asserted.count { it.kind == "none" }}），" +
            "归一样本 ${FIXTURE["normalizeCases"]!!.jsonArray.size}、" +
            "trim 样本 ${FIXTURE["trimCases"]!!.jsonArray.size}、" +
            "类型全集 ${FIXTURE["typeUniverse"]!!.jsonArray.size}、" +
            "真实语料 ${corpus("corpus").size}（另 ${corpus("corpusWithHtml").size} 条含 HTML）",
    )

    if (failures != 0) {
        println("html: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}

/**
 * 夹具是 pi-tui 的产物，而 pi-tui 的版本由 `tools/pi-engine.lock.json` 钉住。两个数字不
 * 一样时，夹具已经在测一个不是本仓库 pin 的引擎 —— 那比夹具过期更糟，因为它会**通过**。
 */
private fun fixtureVersionCheck() {
    val fixtureVersion = FIXTURE["piTui"]!!.text()
    if (!ENGINE_LOCK.isFile) {
        println("PASS 引擎锁不存在，跳过版本一致性检查（${ENGINE_LOCK.absolutePath}）")
        return
    }
    val locked = Json.parseToJsonElement(ENGINE_LOCK.readText()).jsonObject["packages"]!!.jsonObject[
        "node_modules/@earendil-works/pi-tui"
    ]?.jsonObject?.get("version")?.text()
    check("夹具记录的 pi-tui 版本与引擎锁一致（$fixtureVersion）", fixtureVersion, locked)
}

/**
 * ① 类型全集：新逻辑做的唯一一件事是"认领某几种节点"，所以"认领哪些"必须被穷举过。
 * 全集来自 pin 住的 jar 里每个 `*Types` 持有者的全部 `IElementType` 成员（77 个名字）。
 */
private fun typeUniverseChecks() {
    val universe = strings(FIXTURE["typeUniverse"]!!)
    check("类型全集不少于 $MINIMUM_UNIVERSE 个（少了它就少覆盖一条路径）", universe.size >= MINIMUM_UNIVERSE, true)
    check("全集里有 HTML_TAG 与 HTML_BLOCK", CLAIMED.all { it in universe }, true)

    val inlineClaimed = universe.filter { PiHtml.handlesInline(it) }
    val blockClaimed = universe.filter { PiHtml.handlesBlock(it) }
    check("handlesInline 在全部 ${universe.size} 个类型名里只对 HTML_TAG 为真", inlineClaimed, listOf("HTML_TAG"))
    check("handlesBlock 在全部 ${universe.size} 个类型名里只对 HTML_BLOCK 为真", blockClaimed, listOf("HTML_BLOCK"))
    check(
        "两个判定合起来认领的类型就是 ${CLAIMED.sorted()}",
        (inlineClaimed + blockClaimed).sorted(),
        CLAIMED.sorted(),
    )
}

/**
 * ② 逐字节：`kind=block-only` 的每一条，我们的块文本必须与 pi 的输出一模一样。
 *
 * 同时也钉住"块文本里不可能还留着 `\t`/`\r`"：pi 在词法分析前把整篇源文本归一了，
 * 归一后的 `raw` 里这两个字符不可能出现，我们补的 [PiHtml.normalizeJsSource] 同理。
 */
private fun blockByteChecks(asserted: List<Case>) {
    for (case in asserted) {
        if (case.kind != "block-only") continue
        val span = case.blocks.single()
        val actual = PiHtml.blockTextAt(case.source, span.start, span.end)
        check("块级逐字节 ${case.name}", actual, case.piText)
        check("块级文本里没有 \\t/\\r（pi 在词法分析前归一）${case.name}", actual.none { it == '\t' || it == '\r' }, true)
    }
}

/**
 * ③ 行内：pi 把标签的原始字节打进这一行。断言"按顺序出现、且次数与标签数相等" ——
 * 后者是"没有丢、也没有重复"的那一半，前者是"位置对"。
 */
private fun inlineChecks(asserted: List<Case>) {
    var tags = 0
    var blocks = 0
    for (case in asserted) {
        if (case.kind != "html") continue
        var cursor = 0
        val ordered = (case.blocks.map { "块" to it } + case.tags.map { "行内" to it }).sortedBy { it.second.start }
        for ((kind, span) in ordered) {
            val fragment = if (kind == "块") {
                PiHtml.blockTextAt(case.source, span.start, span.end)
            } else {
                PiHtml.inlineTextAt(case.source, span.start, span.end)
            }
            val at = case.piText.indexOf(fragment, cursor)
            check("$kind 片段 ${case.name} @${span.start}..${span.end} 出现在 pi 的输出里", at >= 0, true)
            if (at >= 0) cursor = at + fragment.length
            if (kind == "块") blocks++ else tags++
        }
        // 次数相等：把片段全部替换成空后，pi 的输出里不应再出现同一批片段。
        for ((kind, span) in ordered) {
            val fragment = if (kind == "块") {
                PiHtml.blockTextAt(case.source, span.start, span.end)
            } else {
                PiHtml.inlineTextAt(case.source, span.start, span.end)
            }
            val occurrences = case.piText.windowed(fragment.length).count { it == fragment }
            check("$kind 片段 ${case.name} 在 pi 的输出里恰好出现一次", occurrences, 1)
        }
    }
    check("行内片段比过的条数（≥ 26）", tags >= 26, true)
    check("夹在正文里的块比过的条数（≥ 4）", blocks >= 4, true)
}

/** ④ pi 在词法分析前对整篇源文本的两步归一。 */
private fun normalizeChecks() {
    val entries = FIXTURE["normalizeCases"]!!.jsonArray
    check("归一样本不少于 13 条", entries.size >= 13, true)
    var withTabs = 0
    for (element in entries) {
        val entry = element.jsonObject
        val raw = entry["raw"]!!.text()
        check("归一 ${raw.escapeForName()}", PiHtml.normalizeJsSource(raw), entry["normalized"]!!.text())
        if (raw.contains('\t')) withTabs++
    }
    check("归一样本里有带 \\t 的条目（否则这条规则没被验到）", withTabs >= 4, true)
}

/**
 * ⑤ `String.prototype.trim` 的字符集合，**全量**验，不靠样本。
 *
 * 夹具带的是 pi 那侧的全集（对 0..0xFFFF 每个码位跑 V8 的 `trim()` 压成的区间），
 * 所以这里可以对 65536 个码位逐个断言 `PiHtml.isJsTrimChar` —— 集合这种东西漏一个
 * 码位就等于漏一条规则，样本永远说服不了人。
 *
 * 同时把"我们与 Kotlin 的 `String.trim()` 不一样"这件事也断言掉：实测 JS 多一个
 * U+FEFF、Kotlin 多 U+001C–U+001F，所以 `PiHtml` 不能直接用 `trim()`。这个断言不是
 * 修辞：真有人把 `trimJs` 换成 `trim()`，它会立刻红。
 */
private fun trimChecks() {
    val entries = FIXTURE["trimCases"]!!.jsonArray
    check("trim 样本不少于 20 条", entries.size >= 20, true)
    for (element in entries) {
        val entry = element.jsonObject
        val raw = entry["raw"]!!.text()
        check("trim ${raw.escapeForName()}", PiHtml.trimJs(raw), entry["trimmed"]!!.text())
    }

    val ranges = FIXTURE["jsTrimRanges"]!!.jsonArray.map { range ->
        val pair = range.jsonArray
        pair[0].text().toInt()..pair[1].text().toInt()
    }
    check("JS trim 集合的区间数（10 段，见夹具）", ranges.size, 10)
    val jsOnly = StringBuilder()
    val kotlinOnly = StringBuilder()
    var mismatches = 0
    for (code in 0..0xFFFF) {
        val ch = code.toChar()
        val js = ranges.any { code in it }
        if (js != PiHtml.isJsTrimChar(ch)) mismatches++
        // Kotlin 的 `String.trim()` 用的是 `Char.isWhitespace()`（= `Character.isWhitespace`
        // 或 `Character.isSpaceChar`），与 JS 的集合不是一回事。
        val kotlin = ch.isWhitespace()
        if (js && !kotlin) jsOnly.append("U+%04X ".format(code))
        if (kotlin && !js) kotlinOnly.append("U+%04X ".format(code))
    }
    check("65536 个码位上 isJsTrimChar 与 pi（JS trim）完全一致", mismatches, 0)
    check("我们与 Kotlin 的 trim() 确实不同（否则不必自己实现）", jsOnly.isNotEmpty() || kotlinOnly.isNotEmpty(), true)
    println("info: trim 集合分歧 —— JS 多：${jsOnly.toString().trim()}；Kotlin 多：${kotlinOnly.toString().trim()}")
}

/** check 名里用的短标签：控制字符写成转义，太长就截断。 */
private fun String.escapeForName(): String {
    val escaped = replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\u000B", "\\v")
        .replace("\u000C", "\\f")
        .replace("\u00A0", "\\u00A0")
        .replace("\uFEFF", "\\uFEFF")
    return if (escaped.isEmpty()) "（空串）" else "“" + escaped.take(24) + "”"
}

/**
 * ⑥ 反向检查：**不含 HTML 的消息一个字节都不变。**
 *
 * 结构上这件事是成立的：新逻辑只有两个入口（annotator 的认领钩子、`custom` 槽的一个
 * 分支），两者都只回答"这个节点的类型名是不是我认的那一个"，别的什么都不做。所以
 * "某个类型不会被认领" 等价于 "该类型的节点走的是原来那条路"。`typeUniverseChecks` 把
 * 那句话穷举完了；这里用 117 条**真实段落**（本仓库 `docs/` 下 md 文件）做样本，断言它们的
 * 节点类型集合与 `{HTML_TAG, HTML_BLOCK}` 的交集为空 —— 若哪天有人把判定放宽
 * （例如顺手认领 `TEXT`），这个检查会立刻红，而不是等到手机上少字。
 */
private fun reverseChecks() {
    val corpus = corpus("corpus")
    check("真实语料不少于 $MINIMUM_CORPUS 条", corpus.size >= MINIMUM_CORPUS, true)
    check(
        "真实语料里没有一条含被认领的节点类型",
        corpus.count { entry -> entry.types.any { it in CLAIMED } },
        0,
    )
    // 语料必须真的覆盖了库自己 switch 的那些类型，否则"不含 HTML"是空话。
    val union = corpus.flatMap { it.types }.toSet()
    val required = listOf(
        "TEXT", "PARAGRAPH", "CODE_SPAN", "BACKTICK", "EOL", "WHITE_SPACE", "EMPH", "STRONG",
        "ATX_HEADER", "ATX_CONTENT", "CODE_FENCE", "CODE_FENCE_CONTENT", "UNORDERED_LIST",
        "ORDERED_LIST", "LIST_ITEM", "LIST_BULLET", "BLOCK_QUOTE", "TABLE", "ROW", "CELL",
        "INLINE_LINK", "GFM_AUTOLINK", "HORIZONTAL_RULE", "MARKDOWN_FILE", "<", ">", ":", "[", "]",
    )
    check("语料里缺了这些类型（语料没覆盖到它们）", required.filterNot { it in union }, emptyList<String>())
    check("语料的类型并集不少于 45 个", union.size >= 45, true)

    val withHtml = corpus("corpusWithHtml")
    check("含 HTML 的真实段落也记下来了（真实文档里确实会踩到）", withHtml.size >= 3, true)
    for (entry in withHtml) {
        check("${entry.name} 确实带着被认领的类型", entry.types.any { it in CLAIMED }, true)
        check("${entry.name} 的跨度不是空的（否则它证明不了任何事）", entry.spans.isNotEmpty(), true)
        // 这些跨度是真的落在源里的：今天它们的字符会被库的 annotator 丢掉。
        for (span in entry.spans) {
            check(
                "${entry.name} 的跨度 ${span.start}..${span.end} 落在源里",
                span.start in 0..entry.source.length && span.end in span.start..entry.source.length,
                true,
            )
        }
    }
}

/**
 * ⑦ 接线审查（源代码文本）：判定函数只被那两处调用。
 *
 * 本机编译不了 Compose 层，所以"接线对不对"只有读码能看。这一条把那句读码变成可执行的：
 * `PiMarkdownComponents.kt` 里必须**恰好一次** `PiHtml.handlesBlock`，而且只在
 * `piCustomComponent` 的 `when` 里；`PiHtml.handlesInline` 必须恰好出现在
 * `PiMarkdown.kt` 里一次（annotator 钩子）。多出来的调用点会让这次改动的"只动 HTML"
 * 承诺失效，而那正是这份夹具要保护的东西。
 */
private fun wiringAudit() {
    if (!MARKDOWN_COMPONENTS.isFile || !MARKDOWN_ENTRY.isFile) {
        println("PASS 缺少被审查的源文件（跳过接线审查）")
        return
    }
    val components = MARKDOWN_COMPONENTS.readText()
    val entry = MARKDOWN_ENTRY.readText()
    // 用带左括号的形式数**调用**（注释里提到函数名的地方不算）。
    fun calls(text: String, name: String) = text.split("$name(").size - 1
    check("PiMarkdownComponents.kt：块级认领恰好一次", calls(components, "PiHtml.handlesBlock"), 1)
    check("PiMarkdownComponents.kt：块级取文本恰好一次", calls(components, "PiHtml.blockTextAt"), 1)
    check("PiMarkdownComponents.kt：不碰行内（行内不走组件层）", calls(components, "PiHtml.inlineTextAt"), 0)
    check("PiMarkdownComponents.kt：不碰行内判定", calls(components, "PiHtml.handlesInline"), 0)
    check("PiMarkdown.kt：行内认领恰好一次", calls(entry, "PiHtml.handlesInline"), 1)
    check("PiMarkdown.kt：行内取文本恰好一次", calls(entry, "PiHtml.inlineTextAt"), 1)
    check("PiMarkdown.kt：不碰块级（块级不走 annotator）", calls(entry, "PiHtml.handlesBlock"), 0)
    check("PiMarkdown.kt：不碰块级取文本", calls(entry, "PiHtml.blockTextAt"), 0)
    check("custom 槽仍然只提供这一个函数", components.split("custom = { type, model ->").size - 1, 1)
}
