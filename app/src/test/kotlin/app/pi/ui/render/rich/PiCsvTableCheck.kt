package app.pi.ui.render.rich

// `PiCsvTable.kt` 的裸 JVM harness —— RFC 4180 的 CSV/TSV 解析器（§九）。
//
// 为什么这些检查必须存在：表格错在安静的那一侧，编译器一条都看不见。
// `split(",")` 会把带引号的逗号拆成两格、把字段里的换行变成一条新记录；CRLF 会在最后
// 一格留下一个看不见的 `\r`；第 2,001 行仍然排到版面上；一个不合语法的引号被"修好"
// 成一张文件里并不存在的表。它们都不崩溃 —— 只是让对话记录说假话。所以下面的断言是
// **字段级**的（逐格比较），不是形状级的；每条失败路径都从 `parse` 入口走，不绕过前
// 两道闸（settled / 长度），因为闸门的**顺序**本身也是行为（流式块永远不该因为"太长"
// 而报 TooLarge）。
//
// 运行：
//
//   tools/run-app-pure-checks.sh

private var checks = 0
private var failures = 0

private fun check(id: String, what: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $id $what")
    } else {
        failures++
        println("FAIL $id $what\n  expected: $expected\n  actual:   $actual")
    }
}

/** 取表格结果；不是表格时先记一条（fixture）失败，再给一个空表让后面的断言继续跑。 */
private fun table(result: CsvParseResult): CsvParseResult.Table {
    check("(fixture)", "样例应当解析成表格", result is CsvParseResult.Table, true)
    return result as? CsvParseResult.Table
        ?: CsvParseResult.Table(CsvFormat.Csv, emptyList(), emptyList(), 0)
}

private fun failure(result: CsvParseResult): CsvFailure? =
    (result as? CsvParseResult.Failure)?.reason

/**
 * 规范地重新编码一张表（该加引号的格子加引号，`"` 写成 `""`）。
 *
 * 用它是为了钉住"没有一个格子的内容被悄悄丢掉"：解析 → 重新编码 → 再解析，两次的
 * 单元格必须逐字相同。只比较字面量做不到这一点 —— 它只能证明写进检查的那几格是对的，
 * 证明不了别处没有丢东西。
 */
private fun encode(records: List<List<String>>, delimiter: Char): String = buildString {
    records.forEachIndexed { rowIndex, record ->
        if (rowIndex > 0) append('\n')
        record.forEachIndexed { cellIndex, value ->
            if (cellIndex > 0) append(delimiter)
            val mustQuote = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
            if (mustQuote) {
                append('"')
                append(value.replace("\"", "\"\""))
                append('"')
            } else {
                append(value)
            }
        }
    }
}

fun main() {
    // ================================================ A. 语言 → 方言（只认明确标识）
    run {
        check("A1", "csv 映射到逗号方言", PiCsvTable.formatForLanguage("csv"), CsvFormat.Csv)
        check("A2", "CSV 大写也认（先 lower-case）", PiCsvTable.formatForLanguage("CSV"), CsvFormat.Csv)
        check("A3", "tsv 映射到制表符方言", PiCsvTable.formatForLanguage("tsv"), CsvFormat.Tsv)
        check("A4", "TSV 大写也认", PiCsvTable.formatForLanguage("TSV"), CsvFormat.Tsv)
        check("A5", "json 不是表格", PiCsvTable.formatForLanguage("json"), null)
        check("A6", "空语言不猜", PiCsvTable.formatForLanguage(""), null)
        check("A7", "csv2 不是 csv（前缀不算）", PiCsvTable.formatForLanguage("csv2"), null)
        check("A8", "data.csv 是文件名不是语言", PiCsvTable.formatForLanguage("data.csv"), null)
        check("A9", "带空格的语言标识不认（调用方先取第一段）", PiCsvTable.formatForLanguage("csv "), null)
        check("A10", "逗号方言的分隔符是 RFC 的逗号", CsvFormat.Csv.delimiter, ',')
        check("A11", "TSV 的分隔符是制表符", CsvFormat.Tsv.delimiter, '\t')
    }

    // ================================================ B. 普通记录
    run {
        val t = table(PiCsvTable.parse("a,b\n1,2"))
        check("B1", "第一行是表头", t.header, listOf("a", "b"))
        check("B2", "其余行是数据行，保持文档顺序", t.rows, listOf(listOf("1", "2")))
        check("B3", "columnCount 是最宽的一条记录", t.columnCount, 2)
        check("B4", "rowCount 只数数据行（不含表头）", t.rowCount, 1)
        check("B5", "方言原样带在结果里", t.format, CsvFormat.Csv)
        check("B6", "cell(0,1) 读表头", t.cell(0, 1), "b")
        check("B7", "cell(1,0) 读第一行数据", t.cell(1, 0), "1")
        check("B8", "越界的列读成空串，而不是抛异常", t.cell(0, 5), "")
        check("B9", "越界的行读成空串，而不是抛异常", t.cell(99, 0), "")

        val headerOnly = table(PiCsvTable.parse("a,b"))
        check("B10", "只有一行时它就是表头", headerOnly.header, listOf("a", "b"))
        check("B11", "只有一行时没有数据行", headerOnly.rows, emptyList<List<String>>())
        check("B12", "只有一行时 rowCount 为 0", headerOnly.rowCount, 0)

        val trailingNewline = table(PiCsvTable.parse("a,b\n1,2\n"))
        check("B13", "结尾换行不多造一条记录", trailingNewline.rowCount, 1)

        check("B14", "空字段不会被合并掉", table(PiCsvTable.parse("a,,c")).header, listOf("a", "", "c"))
        check("B15", "结尾分隔符多出一个空字段", table(PiCsvTable.parse("a,")).header, listOf("a", ""))
        check("B16", "空格是数据，不是排版", table(PiCsvTable.parse(" a , b ")).header, listOf(" a ", " b "))
        check("B17", "中文表头与数据", table(PiCsvTable.parse("名字,年龄\n小明,18")).header, listOf("名字", "年龄"))
    }

    // ================================================ C. 引号（RFC 4180 的字段语法）
    run {
        check("C1", "引号里的逗号是一格", table(PiCsvTable.parse("\"a,b\",c")).header, listOf("a,b", "c"))
        check("C2", "两个引号解码成一个引号", table(PiCsvTable.parse("\"a\"\"b\",c")).header, listOf("a\"b", "c"))
        check("C3", "引号里的三连引号 = 一个引号 + 收尾", table(PiCsvTable.parse("\"a\"\"\",b")).header, listOf("a\"", "b"))
        check("C4", "引号里只有一个逗号", table(PiCsvTable.parse("\",\",x")).header, listOf(",", "x"))

        val newline = table(PiCsvTable.parse("\"a\nb\",c\n1,2"))
        check("C5", "引号里的换行留在字段里，不另起一条记录", newline.header, listOf("a\nb", "c"))
        check("C6", "它后面的才是下一条记录", newline.rows, listOf(listOf("1", "2")))

        val crlf = table(PiCsvTable.parse("\"a\r\nb\",c\r\n1,2"))
        check("C7", "引号里的 CRLF 逐字保留", crlf.header, listOf("a\r\nb", "c"))
        check("C8", "引号外仍是 CRLF 记录分隔", crlf.rows, listOf(listOf("1", "2")))

        check("C9", "两个空引号字段仍是两个字段", table(PiCsvTable.parse("\"\",\"\"")).header, listOf("", ""))
        check("C10", "引号内的空格是数据", table(PiCsvTable.parse("\" a \",b")).header, listOf(" a ", "b"))
        check("C11", "CSV 的引号里制表符是内容", table(PiCsvTable.parse("\"a\tb\",c")).header, listOf("a\tb", "c"))
    }

    // ================================================ D. 行尾与空行
    run {
        val crlf = table(PiCsvTable.parse("a,b\r\n1,2\r\n"))
        check("D1", "CRLF 分隔记录", crlf.header, listOf("a", "b"))
        check("D2", "CRLF 的数据行", crlf.rows, listOf(listOf("1", "2")))
        check("D3", "结尾 CRLF 不多造记录", crlf.rowCount, 1)
        check("D4", "裸 LF 也认", table(PiCsvTable.parse("a,b\n1,2")).rows, listOf(listOf("1", "2")))
        check("D5", "裸 CR 也认", table(PiCsvTable.parse("a,b\r1,2")).rows, listOf(listOf("1", "2")))

        check("D6", "UTF-8 BOM 不算第一格的内容", table(PiCsvTable.parse("\uFEFFa,b\n1,2")).header, listOf("a", "b"))
        check("D7", "只有开头那个 BOM 被去掉，中间的留着", table(PiCsvTable.parse("a\uFEFF,b")).header, listOf("a\uFEFF", "b"))

        val blanks = table(PiCsvTable.parse("a,b\n\n1,2\n\n"))
        check("D8", "完全空的行被跳过，不是一条单格记录", blanks.rowCount, 1)
        check("D9", "跳过空行后数据行不变", blanks.rows, listOf(listOf("1", "2")))
        check("D10", "只含空格的行是数据（空格是内容）", table(PiCsvTable.parse("a\n  \n")).rows, listOf(listOf("  ")))
    }

    // ================================================ E. TSV 是同一套引号规则、另一个分隔符
    run {
        val tsv = table(PiCsvTable.parse("a\tb\n1\t2", CsvFormat.Tsv))
        check("E1", "TSV 表头", tsv.header, listOf("a", "b"))
        check("E2", "TSV 方言带回结果", tsv.format, CsvFormat.Tsv)
        check("E3", "TSV 里的逗号是内容", table(PiCsvTable.parse("a,b\tc", CsvFormat.Tsv)).header, listOf("a,b", "c"))
        check("E4", "TSV 里引号包住的制表符是内容", table(PiCsvTable.parse("\"a\tb\"\tc", CsvFormat.Tsv)).header, listOf("a\tb", "c"))
        val commasAsTsv = table(PiCsvTable.parse("a,b\n1,2", CsvFormat.Tsv))
        check("E5", "按 TSV 解释时逗号不再是分隔符", commasAsTsv.header, listOf("a,b"))
        check("E6", "于是它只有一列两行", commasAsTsv.rows, listOf(listOf("1,2")))
        check("E7", "TSV 也接受 CRLF", table(PiCsvTable.parse("a\tb\r\n1\t2\r\n", CsvFormat.Tsv)).rows, listOf(listOf("1", "2")))
    }

    // ================================================ F. 参差记录（短行短着留，不长出值）
    run {
        val ragged = table(PiCsvTable.parse("a,b\n1\n1,2,3"))
        check("F1", "columnCount 取最宽记录", ragged.columnCount, 3)
        check("F2", "短行保持短（不补齐、不造值）", ragged.rows[0], listOf("1"))
        check("F3", "宽行整条保留", ragged.rows[1], listOf("1", "2", "3"))
        check("F4", "短行缺失的格子读作空串", ragged.cell(1, 2), "")

        val widerThanHeader = table(PiCsvTable.parse("a,b\n1,2,3"))
        check("F5", "数据行比表头宽时 columnCount 仍然取最宽", widerThanHeader.columnCount, 3)
        check("F6", "表头本身不多长出一格", widerThanHeader.header.size, 2)
        check("F7", "表头缺的那一格读作空串", widerThanHeader.cell(0, 2), "")
        check("F8", "数据行那一格是真值", widerThanHeader.cell(1, 2), "3")
        check("F9", "宽行的字段数不因为表头而截断", widerThanHeader.rows[0].size, 3)
    }

    // ================================================ G. 不合语法：回退，不"修复"
    run {
        check("G1", "字段中间出现的引号是畸形", failure(PiCsvTable.parse("a\"b,c")), CsvFailure.Malformed)
        check("G2", "收尾引号后还有字符是畸形", failure(PiCsvTable.parse("\"a\"x,b")), CsvFailure.Malformed)
        check("G3", "引号没闭合是畸形（不是尽力猜）", failure(PiCsvTable.parse("\"a,b")), CsvFailure.Malformed)
        check("G4", "引号前的空格让它不在字段开头", failure(PiCsvTable.parse(" \"a\",b")), CsvFailure.Malformed)
        check("G5", "收尾引号后的空格也是畸形", failure(PiCsvTable.parse("\"a\" ,b")), CsvFailure.Malformed)
        check("G6", "第二个字段里的裸引号也是畸形", failure(PiCsvTable.parse("a,b\"c\n1,2")), CsvFailure.Malformed)
        check(
            "G7",
            "EOF 处的空引号字段是合法的（一条一格记录）",
            PiCsvTable.parse("\"\""),
            CsvParseResult.Table(CsvFormat.Csv, listOf(""), emptyList(), 1),
        )
    }

    // ================================================ H. 空正文与流式块
    run {
        check("H1", "空串是 Empty", failure(PiCsvTable.parse("")), CsvFailure.Empty)
        check("H2", "只有空白是 Empty", failure(PiCsvTable.parse("   ")), CsvFailure.Empty)
        check("H3", "只有换行是 Empty", failure(PiCsvTable.parse("\n\n\n")), CsvFailure.Empty)
        check("H4", "只有 BOM 是 Empty", failure(PiCsvTable.parse("\uFEFF")), CsvFailure.Empty)
        check("H5", "流式块永远不是表格", failure(PiCsvTable.parse("a,b\n1,2", settled = false)), CsvFailure.NotSettled)
        check("H6", "流式优先于畸形", failure(PiCsvTable.parse("a\"b", settled = false)), CsvFailure.NotSettled)
        check(
            "H7",
            "流式优先于超长（闸门顺序也是行为）",
            failure(PiCsvTable.parse("x".repeat(11), settled = false, limits = CsvLimits(maxChars = 10))),
            CsvFailure.NotSettled,
        )
        check(
            "H8",
            "超长优先于 Empty：空白太长报 TooLarge 而不是 Empty",
            failure(PiCsvTable.parse("   ", limits = CsvLimits(maxChars = 2))),
            CsvFailure.TooLarge,
        )
    }

    // ================================================ I. 三个上限（超出即回退，绝不截断）
    run {
        val charLimits = CsvLimits(maxChars = 10)
        check("I1", "恰好 maxChars 的正文仍然接受", PiCsvTable.parse("a,b\n1,2345", limits = charLimits) is CsvParseResult.Table, true)
        check("I2", "多一个字符就回退", failure(PiCsvTable.parse("a,b\n1,23456", limits = charLimits)), CsvFailure.TooLarge)

        val rowLimits = CsvLimits(maxRows = 2)
        check("I3", "恰好 maxRows 条记录仍然接受", PiCsvTable.parse("a\nb", limits = rowLimits) is CsvParseResult.Table, true)
        check("I4", "多一条记录就回退", failure(PiCsvTable.parse("a\nb\nc", limits = rowLimits)), CsvFailure.TooManyRows)
        check("I5", "表头也算在 maxRows 里（2,000 行是可见行数）", failure(PiCsvTable.parse("a\nb\nc", limits = CsvLimits(maxRows = 2))), CsvFailure.TooManyRows)

        val columnLimits = CsvLimits(maxColumns = 2)
        check("I6", "恰好 maxColumns 个字段仍然接受", PiCsvTable.parse("a,b", limits = columnLimits) is CsvParseResult.Table, true)
        check("I7", "多一个字段就回退", failure(PiCsvTable.parse("a,b,c", limits = columnLimits)), CsvFailure.TooManyColumns)
        check("I8", "引号里的逗号不算多一列", PiCsvTable.parse("\"a,b\",c", limits = columnLimits) is CsvParseResult.Table, true)
        check(
            "I9",
            "超宽的行在读到第 3 格后就报 TooManyColumns（不必读完整个源）",
            failure(PiCsvTable.parse("a,b,c,d", limits = CsvLimits(maxColumns = 2))),
            CsvFailure.TooManyColumns,
        )

        check("I10", "默认字符上限是计划的 256 KiB", PiCsvTable.DEFAULT_LIMITS.maxChars, 256 * 1024)
        check("I11", "默认行上限是任务的 2,000（含表头）", PiCsvTable.DEFAULT_LIMITS.maxRows, 2_000)
        check("I12", "默认列上限是任务的 100", PiCsvTable.DEFAULT_LIMITS.maxColumns, 100)

        val atRowBound = (0 until 2_000).joinToString("\n") { "r$it" }
        check("I13", "2,000 条记录仍然接受", PiCsvTable.parse(atRowBound) is CsvParseResult.Table, true)
        check("I14", "2,001 条记录回退", failure(PiCsvTable.parse(atRowBound + "\nlast")), CsvFailure.TooManyRows)

        val hugeField = "x".repeat(200_000)
        val huge = table(PiCsvTable.parse("h\n$hugeField"))
        check("I15", "上限之内，一个 20 万字符的字段不被截断", huge.rows[0][0].length, 200_000)
        check("I16", "它的内容逐字保留", huge.rows[0][0], hugeField)
        check("I17", "恰好 256 KiB 仍然接受（闸门是严格大于）", PiCsvTable.parse("x".repeat(256 * 1024)) is CsvParseResult.Table, true)
        check("I18", "256 KiB + 1 回退", failure(PiCsvTable.parse("x".repeat(256 * 1024 + 1))), CsvFailure.TooLarge)
        check(
            "I19",
            "比 maxChars 还长的单个字段整段回退，而不是悄悄切短",
            failure(PiCsvTable.parse("h\n" + "x".repeat(256 * 1024))),
            CsvFailure.TooLarge,
        )

        // 0 必须被拒绝：如果 init 不 require，0 会被读成"不限"，一张凶表就能冻住版面。
        check("I20", "maxChars=0 被拒绝", runCatching { CsvLimits(maxChars = 0) }.exceptionOrNull() is IllegalArgumentException, true)
        check("I21", "maxRows=0 被拒绝", runCatching { CsvLimits(maxRows = 0) }.exceptionOrNull() is IllegalArgumentException, true)
        check("I22", "maxColumns=0 被拒绝", runCatching { CsvLimits(maxColumns = 0) }.exceptionOrNull() is IllegalArgumentException, true)
    }

    // ================================================ J. 内容不丢：解码 → 重新编码 → 再解码
    run {
        val source = "h1,h2,h3\n\"a,b\",\"c\"\"d\",\"e\nf\"\nplain,,\" spaced \""
        val t = table(PiCsvTable.parse(source))
        check("J1", "表头", t.header, listOf("h1", "h2", "h3"))
        check("J2", "同一行的三种引号形态各自解对", t.rows[0], listOf("a,b", "c\"d", "e\nf"))
        check("J3", "空字段与带首尾空格的字段都逐字保留", t.rows[1], listOf("plain", "", " spaced "))

        // 逐格拼起来必须等于输入去掉引号/转义后的样子 —— 这正是"没有任何一格被丢掉"。
        check(
            "J4",
            "所有格子的内容加起来就是输入（除引号外一个字节不少）",
            (t.header + t.rows.flatten()).joinToString("\u0001"),
            "h1\u0001h2\u0001h3\u0001a,b\u0001c\"d\u0001e\nf\u0001plain\u0001\u0001 spaced ",
        )

        val reencoded = encode(listOf(t.header) + t.rows, ',')
        val again = table(PiCsvTable.parse(reencoded))
        check("J5", "重新编码后再解析，表头相同", again.header, t.header)
        check("J6", "重新编码后再解析，每一格都相同", again.rows, t.rows)
        check("J7", "重新编码后再解析，列数相同", again.columnCount, t.columnCount)

        // 朴素实现的对照：`split(",")` 在这里给出 3 段，而真正的字段是 2 个。
        check("J8", "朴素的 split(\",\") 会把带引号的逗号拆成 3 段", "\"a,b\",c".split(",").size, 3)
        check("J9", "解析器只给出 2 格，其中一格含逗号", table(PiCsvTable.parse("\"a,b\",c")).header, listOf("a,b", "c"))

        val tsvRoundTrip = table(PiCsvTable.parse("a\t\"b\tc\"\n1\t2", CsvFormat.Tsv))
        check("J10", "TSV 的引号制表符也是一格", tsvRoundTrip.header, listOf("a", "b\tc"))
        check(
            "J11",
            "TSV 重新编码后再解析仍然相同",
            table(PiCsvTable.parse(encode(listOf(tsvRoundTrip.header) + tsvRoundTrip.rows, '\t'), CsvFormat.Tsv)).rows,
            tsvRoundTrip.rows,
        )
    }

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures of $checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
