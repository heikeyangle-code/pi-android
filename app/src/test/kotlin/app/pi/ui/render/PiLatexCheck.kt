package app.pi.ui.render

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * `PiLatex.kt` 的**行为**夹具（`tools/run-app-pure-checks.sh` 里的 `latex`）。
 *
 * 为什么需要它，而不是继续靠 `PiLatex.kt` 里那些逐条抄来的表：表的正确性是
 * `tools/check-latex-tables.mjs` 的事（逐 key 比对 pi 的 `latex.js`），**顺序**和
 * **落点**不是 —— `\lim_{n\to\infty}` 是 `lim[n→∞]` 还是 `lim_(n→∞)`，取决于
 * `parseCommand` 里 `LIMIT_OPERATORS`（`latex.ts:1086`）是否排在符号表和具名算子
 * 之前；`2\sin x` 是 `2 sin x` 还是 `2sin x`，取决于哨兵间距
 * （`latex.ts:649-653`）。这类东西编译器完全看不见，而它错了以后手机上只会看到
 * 一条公式画得和 pi 不一样。所以期望值不是读代码写下来的句子，是 **pi 自己的
 * `renderLatex` 对 428 条公式的返回值**，由 `tools/collect-latex-fixtures.mjs`
 * 生成、提交在 `app/src/test/resources/pi-latex-fixtures/cases.json`。
 *
 * 三段，三种断言强度（与生成脚本的注释一一对应）：
 *
 *  ① `cases`（432 条）：`PiLatex.toUnicode(source)` 必须与 pi 的输出**逐字节**相同，
 *    pi 返回 `undefined` 的 16 条必须返回 `null`（原文回退）。行内的 `\begin{…}`
 *    （矩阵/`cases`/`aligned`）也在这里 —— 环境分支不受 `display` 门控。
 *  ② `displayTargets`（114 条）+ `delimiterDisplayTargets`（4 条）：pi 的**块级**输出
 *    （堆叠分数、算子上限、上下两行的脚本、网格环境），逐字节断言。
 *  ③ `delimiterDeviations`（9 条）+ `dollarProse`（220 条）：已知偏差的锁与 prose 语料
 *    的"新误判 = 0"护栏，理由见各自的函数注释。
 *
 * 另外两条容易漏、但漏了会很难看的东西：
 *  - **哨兵不能进输出**：`NAMED_OPERATOR_START/END`（`PiLatex.kt` 里的 `\uE004`/`\uE005`）
 *    是解析期的临时标记。只要有一条公式把它们漏进结果，那个私用码位就会画成豆腐块。
 *  - **夹具与引擎锁一致**：夹具里记着 `piTui` 版本，这里拿 `tools/pi-engine.lock.json`
 *    里锁住的版本比。引擎锁升级而夹具没重生成 = 夹具在测一个已经不存在的 pi。
 *
 * 夹具路径由 `pi.repo.root`（`run-app-pure-checks.sh` 传给每个 harness）解析；夹具
 * 缺失直接 exit 2，不跳过 —— 静默跳过正是这个文件存在的理由的反面。
 */
private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")
private val FIXTURE_FILE: File = ROOT.resolve("app/src/test/resources/pi-latex-fixtures/cases.json")
private val ENGINE_LOCK: File = ROOT.resolve("tools/pi-engine.lock.json")

private val FIXTURE: JsonObject = run {
    if (!FIXTURE_FILE.isFile) {
        println("latex: CANNOT RUN - 缺少夹具 ${FIXTURE_FILE.absolutePath}")
        println("latex:           跑 `node tools/collect-latex-fixtures.mjs` 生成它（需要一份 pi-tui）")
        exitProcess(2)
    }
    Json.parseToJsonElement(FIXTURE_FILE.readText()).jsonObject
}

/** 一条夹具：公式、pi 的输出（`null` = pi 自己也认不出来）。 */
private class Case(val name: String, val source: String, val expected: String?)

/** 每段的字段名不同：`delimiters` 是 `expected`，偏差那段是 `inline`，目标那段是 `display`。 */
private fun cases(key: String, field: String = "expected"): List<Case> = FIXTURE[key]!!.jsonArray.map { element ->
    val entry = element.jsonObject
    Case(
        name = entry["name"]!!.jsonPrimitiveContent(),
        source = entry["source"]!!.jsonPrimitiveContent(),
        expected = (entry[field] as? JsonPrimitive)?.contentOrNull,
    )
}

/**
 * `JsonNull` 也是 `JsonPrimitive`，`content` 会给出字符串 `"null"`；夹具里的 `null`
 * 表示"pi 返回 `undefined`"，必须与字面量 `"null"` 区分开，所以只走 `contentOrNull`。
 */
private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

/** 夹具条数的下限：夹具是生成的，删掉一批不会有任何其它信号。 */
private const val MINIMUM_CASES = 400

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  pi:   ${readable(expected)}\n  端口: ${readable(actual)}")
    }
}

/** 日志里把控制字符写成转义：`\n` 与空格的区别是这一整个文件的一半内容。 */
private fun readable(value: Any?): String = when (value) {
    null -> "null（原文回退）"
    is String -> "“" + value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r") + "”"
    else -> value.toString()
}

fun main() {
    val asserted = cases("cases")

    check("夹具条数不少于 $MINIMUM_CASES（防的是夹具被删空）", asserted.size >= MINIMUM_CASES, true)
    check("夹具里的公式名唯一", asserted.map { it.name }.toSet().size, asserted.size)
    // pi 认不出来（返回 `undefined`）的那一批是**原文回退**路径：端口必须返回 `null`，
    // 否则调用方会把一条画错的公式当成画好的。这一批不能悄悄消失，所以按名字前缀
    // （夹具里的命名规则）挑出来单独钉住，而不是去数一个总数 —— 总数会随着加案例变。
    val failurePaths = asserted.filter { case ->
        case.name.startsWith("unknown-") ||
            case.name.startsWith("unbalanced-") ||
            case.name == "end-without-begin" ||
            case.name == "trailing-backslash"
    }
    check("夹具里有失败路径条目（≥8）", failurePaths.size >= 8, true)
    check("失败路径一律期望 null（pi 认不出来）", failurePaths.count { it.expected == null }, failurePaths.size)
    println("info: 夹具 ${asserted.size} 条里，pi 认不出来（期望 null）的有 ${asserted.count { it.expected == null }} 条")

    fixtureVersionChecks()
    inlineChecks(asserted)
    delimiterChecks()
    displayChecks()
    delimiterDeviationChecks()
    dollarProseChecks()
    sentinelChecks(asserted)

    if (failures != 0) {
        println("latex: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}

/**
 * 夹具是 pi-tui 的产物，而 pi-tui 的版本由 `tools/pi-engine.lock.json` 钉住
 * （`packages["node_modules/@earendil-works/pi-tui"].version`）。两个数字不一样时，
 * 夹具已经在测一个不是本仓库 pin 的引擎 —— 那比夹具过期更糟，因为它会**通过**。
 */
private fun fixtureVersionChecks() {
    val fixtureVersion = FIXTURE["piTui"]!!.jsonPrimitiveContent()
    if (!ENGINE_LOCK.isFile) {
        println("PASS 引擎锁不存在，跳过版本一致性检查（${ENGINE_LOCK.absolutePath}）")
        return
    }
    val locked = Json.parseToJsonElement(ENGINE_LOCK.readText()).jsonObject["packages"]!!.jsonObject[
        "node_modules/@earendil-works/pi-tui"
    ]?.jsonObject?.get("version")?.jsonPrimitiveContent()
    check("夹具记录的 pi-tui 版本与引擎锁一致（$fixtureVersion）", fixtureVersion, locked)
}

/** ① 逐字节断言：这一条是整份文件的目的。 */
private fun inlineChecks(asserted: List<Case>) {
    var matched = 0
    for (case in asserted) {
        val actual = PiLatex.toUnicode(case.source)
        if (actual == case.expected) matched++
        else check("${case.name}（源：${case.source.replace("\n", "\\n")}）", actual, case.expected)
    }
    check("全部 ${asserted.size} 条公式与 pi 的输出逐字节相同", matched, asserted.size)
}

/**
 * 定界符：`PiLatex.preprocess` 的整段输出（不是单个公式），期望值由 pi 自己的
 * tokenizer + `renderLatex` 生成（见 `tools/collect-latex-fixtures.mjs`）。
 *
 * 这一段是**入口**的检查：`\(x\)` 与 `\[x\]` 今天必须走到公式分支
 * （在补这两种定界符之前它们整类走不到），而 `$$…$$`、行内 `$…$`、代码围栏与
 * 行内代码里的定界符必须保持原样。
 */
private fun delimiterChecks() {
    val entries = cases("delimiters")
    var matched = 0
    for (case in entries) {
        val actual = PiLatex.preprocess(case.source)
        if (actual == case.expected) matched++
        else check("定界符 ${case.name}（源：${readable(case.source)}）", actual, case.expected)
    }
    check("全部 ${entries.size} 条定界符用例与 pi 的 token 决策一致", matched, entries.size)
    // 这 4 条（pi 当块级、整段输出里有堆叠网格）由 [displayChecks] 逐字节断言。
}

/**
 * 定界符的**已知偏差**：断言"仍然与 pi 不一样"。三类理由（实测出处与每一条的
 * pi 输出都记在 `tools/collect-latex-fixtures.mjs` 的 `DELIMITER_DEVIATIONS`）：
 *
 *  ① `$` 那条正则比 pi 的 tokenizer 保守（`a$x$b`、`$5$`、`$x$y` 在 pi 里是公式，
 *     这里当普通文本）—— **既有行为，这次没有改**，理由见 `docs/known-gaps.md` §A2；
 *  ② marked 的段落切分是位置相关的，行中 `\[`/`$$` 的位置判定与"行首"规则不同；
 *  ③ `\begin{…}` 那一族的布局支没有移植。
 *
 * 任何一条变成"与 pi 相同"都会红，提醒把它挪进 `delimiters` —— 偏差可以被修掉，
 * 但不许悄悄消失。
 */
private fun delimiterDeviationChecks() {
    val entries = cases("delimiterDeviations", field = "inline")
    for (case in entries) {
        val actual = PiLatex.preprocess(case.source)
        if (actual == case.expected) {
            failures++
            println(
                "FAIL 定界符偏差 ${case.name} 已经与 pi 相同了（${readable(actual)}）——" +
                    "把它从夹具的 DELIMITER_DEVIATIONS 挪进 DELIMITERS",
            )
        } else {
            println("PASS 定界符偏差 ${case.name} 仍然不同（pi：${readable(case.expected)}，端口：${readable(actual)}）")
        }
    }
}

/**
 * `$` 的 **prose 语料**（220 条：184 条取自本仓库自己的 `docs` 下的全部 `.md` 与
 * `res/values` 开头的 `.xml`，36 条手写真实用法，每条一个场景）。
 *
 * 这一段的判据只有一条，而且它是**算出来的**：**不许出现"pi 不改而我们改"的样本**
 * —— 那是"新误判"，也就是正常散文被吃掉。语料里 15 条是 pi 自己会渲染的
 * （shell/Perl 的 `$var` 对、`$5$` 这类），我们**有意地**比 pi 保守，所以那 15 条
 * 里的多数仍然原样保留 —— 那些就是 `delimiterDeviations` 锁住的偏差。
 *
 * 为什么这条断言值得存在：它不阻止将来对齐 `$`（对齐后它依然成立，因为对齐只减少
 * "pi 改而我们不改"），但它把"我们不会比 pi 更爱吃文本"变成一个每次 CI 都验一句的
 * 事实。语料本身与每条的场景写在夹具的 `dollarProse` 里，取自哪个文件也写在
 * `provenance`，可以逐条复核。
 */
private fun dollarProseChecks() {
    val entries = FIXTURE["dollarProse"]!!.jsonArray.map { element ->
        val entry = element.jsonObject
        Triple(
            entry["source"]!!.jsonPrimitiveContent(),
            entry["provenance"]!!.jsonPrimitiveContent(),
            (entry["piRenders"] as JsonPrimitive).content.toBoolean(),
        )
    }
    var falsePositives = 0
    var piAndWe = 0
    var piOnly = 0
    var neither = 0
    val conserved = ArrayList<String>()
    for ((source, provenance, piRenders) in entries) {
        val weChange = PiLatex.preprocess(source) != source
        when {
            weChange && !piRenders -> {
                falsePositives++
                if (falsePositives <= 5) println("  新误判（pi 不改而我们改）：${readable(source)} ← $provenance")
            }
            weChange && piRenders -> piAndWe++
            !weChange && piRenders -> piOnly++
            else -> {
                neither++
                if (conserved.size < 5) conserved.add(provenance)
            }
        }
    }
    check("prose 语料里没有一条「pi 不改而我们改」（新误判）", falsePositives, 0)
    check("prose 语料条数不少于 200（防的是语料被删空）", entries.size >= 200, true)
    println(
        "info: prose 语料 ${entries.size} 条 —— 双方都不改 $neither；pi 改我们也改 $piAndWe；" +
            "pi 改而我们有意不改 $piOnly；新误判 $falsePositives",
    )
    println("  我们有意保守（pi 会渲染、我们保留原文）的样本之一，取自：${conserved.joinToString("、")}")
}

/** 解析期的哨兵不能漏进结果：它们是私用码位，画出来就是豆腐块。 */
private fun sentinelChecks(all: List<Case>) {
    val leaked = all.mapNotNull { case ->
        PiLatex.toUnicode(case.source)?.takeIf { it.contains('\uE004') || it.contains('\uE005') }?.let { case.name }
    }
    check("没有任何输出漏出具名算子哨兵（U+E004/U+E005）", leaked, emptyList<String>())
}

/**
 * **块级排版**：夹具里每条公式的 `renderLatex(source, {display: true})`（114 条
 * `displayTargets`）与每条块级定界符用例的 `preprocess` 输出（4 条
 * `delimiterDisplayTargets`）都取自 pi 的真实输出，这里逐字节断言。
 *
 * 这一段是 Phase B 第一半的验收：堆叠分数、算子上限、`parseScripts` 的上下两行、
 * 八种网格环境 + `cases`/`aligned`/`gather`/`split`，以及 `renderLayout` 的
 * 居中/基线/受保护空格。行内环境（`\begin{…}` 那 4 条）在 `cases` 里断言。
 */
private fun displayChecks() {
    val targets = cases("displayTargets")
    var matched = 0
    for (case in targets) {
        val actual = PiLatex.toUnicode(case.source, display = true)
        if (actual == case.expected) matched++
        else check("display ${case.name}（源：${readable(case.source)}）", actual, case.expected)
    }
    check("全部 ${targets.size} 条 display 目标与 pi 的块级排版逐字节相同", matched, targets.size)

    // 定界符那 4 条记的是 `preprocess` 的整段输出（含独占一段的 `\n` 包裹）。
    val delimiterTargets = FIXTURE["delimiterDisplayTargets"]!!.jsonArray.map { element ->
        val entry = element.jsonObject
        Triple(
            entry["name"]!!.jsonPrimitiveContent(),
            entry["source"]!!.jsonPrimitiveContent(),
            (entry["display"] as JsonPrimitive).contentOrNull,
        )
    }
    var delimiterMatched = 0
    for ((name, source, expected) in delimiterTargets) {
        val actual = PiLatex.preprocess(source)
        if (actual == expected) delimiterMatched++
        else check("定界符 display $name（源：${readable(source)}）", actual, expected)
    }
    check(
        "全部 ${delimiterTargets.size} 条块级定界符用例的整段输出与 pi 一致",
        delimiterMatched,
        delimiterTargets.size,
    )
}
