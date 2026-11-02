package app.pi.ui.render.rich

// Bare-JVM harness for `PiFootnote.kt` — the independent Markdown footnote model.
//
// The library's AST has no footnote node, so this model has to be right on its
// own. The failures that matter are all quiet: `[x]` (a link) becoming a
// footnote, a definition inside a fenced code block being lifted out of the code,
// an undefined `[^id]` losing its brackets, duplicate definitions duplicating the
// list, continuation indentation eating the next paragraph, or an over-limit
// document being partially rewritten instead of falling back to source. Each of
// those is checked below by comparing the rewritten body text, not just the
// definition count.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun textPart(text: String) = FootnoteBodyPart.Text(text)

private fun marker(id: String, number: Int) = FootnoteBodyPart.Marker(id, number)

fun main() {
    // =================================================== A. the basic shape
    run {
        val doc = PiFootnote.parse("这里是一段内容[^1]。\n\n[^1]: 这是脚注解释。")
        check("A1 has footnotes", doc.hasFootnotes, true)
        check("A2 no failure", doc.failure, null)
        check(
            "A3 the marker replaced the reference",
            doc.parts,
            listOf(textPart("这里是一段内容"), marker("1", 1), textPart("。\n")),
        )
        check("A4 the body text carries the superscript", doc.bodyText(), "这里是一段内容¹。\n")
        check("A5 one definition", doc.definitions.size, 1)
        check("A6 definition id", doc.definitions[0].id, "1")
        check("A7 definition number", doc.definitions[0].number, 1)
        check("A8 definition text", doc.definitions[0].text, "这是脚注解释。")
        check("A9 definition marked referenced", doc.definitions[0].referenced, true)
        check("A10 definition source line", doc.definitions[0].line, 3)
        check("A11 one reference", doc.references, listOf(FootnoteReference("1", 1, true)))
        check("A12 definitionFor", doc.definitionFor("1")?.text, "这是脚注解释。")
        check("A13 definitionFor unknown", doc.definitionFor("nope"), null)
    }

    // =================================================== B. numbering
    run {
        val doc = PiFootnote.parse("a[^x] b[^x]\n\n[^x]: note")
        check("B1 the same id reuses its number", doc.references.map { it.number }, listOf(1, 1))
        check("B2 two references recorded", doc.references.size, 2)
        check("B3 body text", doc.bodyText(), "a¹ b¹\n")

        val ordered = PiFootnote.parse("b[^b] a[^a]\n\n[^b]: B\n[^a]: A")
        check("B4 numbers follow first reference order", ordered.definitions.map { it.id to it.number }, listOf("b" to 1, "a" to 2))
        check("B5 body text", ordered.bodyText(), "b¹ a²\n")
        check("B6 the list is sorted by number", ordered.definitions.map { it.number }, listOf(1, 2))

        val beforeReference = PiFootnote.parse("[^a]: first\n\ntext[^a]")
        check("B7 a definition before its reference is fine", beforeReference.bodyText(), "\ntext¹")
        check("B8 its number", beforeReference.definitions[0].number, 1)

        val ten = (1..10).joinToString("") { "[^$it]" } + "\n\n" +
            (1..10).joinToString("\n") { "[^$it]: note $it" }
        val tenDoc = PiFootnote.parse(ten)
        check("B9 two-digit numbers become two superscript digits", tenDoc.bodyText().contains("¹⁰"), true)
        check("B10 ten markers", tenDoc.references.size, 10)
        check("B11 ten definitions", tenDoc.definitions.size, 10)
    }

    // =================================================== C. undefined and plain brackets
    run {
        val onlyUndefined = PiFootnote.parse("text[^missing]\n")
        check("C1 undefined references leave the body untouched", onlyUndefined.parts, listOf(textPart("text[^missing]\n")))
        check("C2 no definitions", onlyUndefined.definitions, emptyList<FootnoteDefinition>())
        check("C3 no footnotes", onlyUndefined.hasFootnotes, false)
        check("C4 body text is the source", onlyUndefined.bodyText(), "text[^missing]\n")

        val mixed = PiFootnote.parse("a[^1] b[^missing]\n\n[^1]: note")
        check("C5 a defined reference is a marker", mixed.bodyText(), "a¹ b[^missing]\n")
        check("C6 the undefined one keeps its brackets", mixed.bodyText().contains("[^missing]"), true)
        check(
            "C7 both are reported",
            mixed.references,
            listOf(FootnoteReference("1", 1, true), FootnoteReference("missing", null, false)),
        )

        val plain = PiFootnote.parse("普通 [x] 和 [1]，还有 [x]: 不是定义")
        check("C8 ordinary brackets are not footnotes", plain.parts, listOf(textPart("普通 [x] 和 [1]，还有 [x]: 不是定义")))
        check("C9 no references", plain.references, emptyList<FootnoteReference>())
        check("C10 no definitions", plain.definitions, emptyList<FootnoteDefinition>())

        val linkDefinition = PiFootnote.parse("[x]: https://example.com\n\nsee [x]")
        check("C11 a link definition is not a footnote definition", linkDefinition.definitions, emptyList<FootnoteDefinition>())
        check("C12 its body is untouched", linkDefinition.parts, listOf(textPart("[x]: https://example.com\n\nsee [x]")))

        val malformed = PiFootnote.parse("[^] [^1 ] [^a[b] [^2]\n\n[^2]: note")
        check("C13 malformed references are literal", malformed.bodyText(), "[^] [^1 ] [^a[b] ¹\n")
        check("C14 only the well-formed reference is a marker", malformed.references.size, 1)
        check(
            "C15 the well-formed one is the one with a definition",
            malformed.references,
            listOf(FootnoteReference("2", 1, true)),
        )
    }

    // =================================================== D. escaping and code
    run {
        val escaped = PiFootnote.parse("\\[^1] 和 [^1]\n\n[^1]: note")
        check("D1 an escaped reference is literal", escaped.bodyText(), "\\[^1] 和 ¹\n")
        check("D2 it still counts as one reference", escaped.references.size, 1)

        val inlineCode = PiFootnote.parse("`[^1]` 和 [^1]\n\n[^1]: note")
        check("D3 an inline code span is not scanned", inlineCode.bodyText(), "`[^1]` 和 ¹\n")

        val fenced = PiFootnote.parse("```\n[^1]\n```\n\n[^1]: note\n\ntext[^1]")
        check("D4 a fenced reference is not replaced", fenced.bodyText().contains("```\n[^1]\n```"), true)
        check("D5 the reference outside the fence is", fenced.bodyText().endsWith("text¹"), true)

        val fenceDefinition = PiFootnote.parse("```\n[^1]: not a definition\n```\n\ntext[^1]")
        check("D6 a definition inside a fence is code", fenceDefinition.definitions, emptyList<FootnoteDefinition>())
        check("D7 the whole document is untouched", fenceDefinition.parts, listOf(textPart("```\n[^1]: not a definition\n```\n\ntext[^1]")))

        val tilde = PiFootnote.parse("~~~\n[^1]\n~~~\n\n[^1]: note\n\ntext[^1]")
        check("D8 tilde fences behave the same", tilde.bodyText().contains("~~~\n[^1]\n~~~"), true)

        val unclosedFence = PiFootnote.parse("```\n[^1]\n\n[^1]: note")
        check("D9 an unclosed fence keeps the rest as code", unclosedFence.definitions, emptyList<FootnoteDefinition>())
    }

    // =================================================== E. definitions in detail
    run {
        val duplicate = PiFootnote.parse("[^1]: one\n[^1]: two\n\ntext[^1]")
        check("E1 the first definition wins", duplicate.definitions.size, 1)
        check("E2 its text", duplicate.definitions[0].text, "one")
        check("E3 the body keeps only text", duplicate.bodyText(), "\ntext¹")

        val multiline = PiFootnote.parse("text[^1]\n\n[^1]: first line\n  second line\n    third")
        check("E4 continuation lines are stripped", multiline.definitions[0].text, "first line\nsecond line\nthird")
        check("E5 the definition is removed from the body", multiline.bodyText(), "text¹\n")

        val paragraph = PiFootnote.parse("[^1]: para one\n\n  para two\n\ntext[^1]")
        check("E6 a blank line continues the definition when the next line is indented", paragraph.definitions[0].text, "para one\n\npara two")
        check("E7 the body after it", paragraph.bodyText(), "\ntext¹")

        val threeSpaces = PiFootnote.parse("   [^1]: note\n\ntext[^1]")
        check("E8 three leading spaces are still a definition", threeSpaces.definitions.size, 1)
        check("E9 its body", threeSpaces.bodyText(), "\ntext¹")

        val fourSpaces = PiFootnote.parse("    [^1]: not a definition\n\ntext[^1]")
        check("E10 four leading spaces is indented code, not a definition", fourSpaces.definitions, emptyList<FootnoteDefinition>())
        check("E11 the document is untouched", fourSpaces.parts, listOf(textPart("    [^1]: not a definition\n\ntext[^1]")))

        val emptyText = PiFootnote.parse("text[^1]\n\n[^1]:")
        check("E12 an empty definition is still a definition", emptyText.definitions.size, 1)
        check("E13 its text is empty", emptyText.definitions[0].text, "")
        check("E14 the body", emptyText.bodyText(), "text¹\n")

        val idShape = PiFootnote.parse("see[^note-1.a]\n\n[^note-1.a]: note")
        check("E15 ids may contain letters, digits, dots and dashes", idShape.bodyText(), "see¹\n")
        check("E16 the id is kept", idShape.definitions[0].id, "note-1.a")

        val unreferenced = PiFootnote.parse("text\n\n[^u]: unused")
        check("E17 an unreferenced definition is still listed", unreferenced.definitions.size, 1)
        check("E18 it is marked unreferenced", unreferenced.definitions[0].referenced, false)
        check("E19 it still gets a number", unreferenced.definitions[0].number, 1)
        check("E20 the body has no marker", unreferenced.bodyText(), "text\n")

        val onlyDefinitions = PiFootnote.parse("[^1]: note")
        check("E21 a definitions-only source has an empty body", onlyDefinitions.bodyText(), "")
        check("E22 but still lists the definition", onlyDefinitions.definitions.size, 1)
    }

    // =================================================== F. bounds and fallback
    run {
        val source = "text[^1]\n\n[^1]: note"
        val tooLarge = PiFootnote.parse(source, limits = FootnoteLimits(maxChars = source.length - 1))
        check("F1 over maxChars reports TooLarge", tooLarge.failure, FootnoteFailure.TooLarge)
        check("F2 and falls back to the exact source", tooLarge.parts, listOf(textPart(source)))
        check("F3 no definitions on the fallback", tooLarge.definitions, emptyList<FootnoteDefinition>())
        check("F4 not footnotes", tooLarge.hasFootnotes, false)
        check("F5 bodyText is the source", tooLarge.bodyText(), source)

        val exactly = PiFootnote.parse(source, limits = FootnoteLimits(maxChars = source.length))
        check("F6 exactly maxChars still parses", exactly.hasFootnotes, true)

        val manyDefinitions = PiFootnote.parse(
            "[^1]: one\n[^2]: two\n\ntext[^1]",
            limits = FootnoteLimits(maxDefinitions = 1),
        )
        check("F7 over maxDefinitions reports TooManyDefinitions", manyDefinitions.failure, FootnoteFailure.TooManyDefinitions)
        check("F8 and falls back", manyDefinitions.parts, listOf(textPart("[^1]: one\n[^2]: two\n\ntext[^1]")))

        val tooManyReferences = PiFootnote.parse(
            "a[^1] b[^1]\n\n[^1]: note",
            limits = FootnoteLimits(maxReferences = 1),
        )
        check("F9 over maxReferences reports TooManyReferences", tooManyReferences.failure, FootnoteFailure.TooManyReferences)
        check("F10 and falls back", tooManyReferences.parts, listOf(textPart("a[^1] b[^1]\n\n[^1]: note")))

        check("F11 the default char bound is 256 KiB", PiFootnote.DEFAULT_LIMITS.maxChars, 256 * 1024)
    }

    // =================================================== G. untouched documents
    run {
        val crlf = "a\r\n\r\nb\r\n"
        val doc = PiFootnote.parse(crlf)
        check("G1 no definitions means the source is untouched byte for byte", doc.parts, listOf(textPart(crlf)))

        val noDefinitions = PiFootnote.parse("plain text with no footnotes [x]")
        check(
            "G2 plain text is one text part",
            noDefinitions.parts,
            listOf(textPart("plain text with no footnotes [x]")),
        )

        val blank = PiFootnote.parse("")
        check("G3 the empty document", blank.parts, listOf(textPart("")))
        check("G4 no failure", blank.failure, null)
    }

    // =================================================== H. id 的上限、锚点的稳定性与编号顺序
    // 为什么必须钉：
    //  - id 上限（1–64）只写在 KDoc 里，实现是两条不同的循环（定义用正则 `{1,64}`、
    //    引用用手写扫描的 `<= 64`）。两边差一位，就会出现"定义成立、引用却不是引用"的
    //    单向失配 —— 正文里留着一个方括号、脚注列表里却有一条永远到不了的定义；
    //  - 模型**不清洗** id（这是有意的：它是文档里的原文，不是生成的 slug），所以带引号、
    //    尖括号、中文的 id 会原样进 Marker。钉住这一点，是为了让"渲染层必须自己转义成
    //    安全的锚点"成为一个有测试的事实，而不是靠人记得；
    //  - 编号顺序决定列表的长相：被引用的按首次引用顺序在前，从未被引用的按源码顺序排在
    //    后面。写成"按源码顺序"或"按字母顺序"都不会报错，只是脚注列表和正文对不上号。
    run {
        val id64 = "a".repeat(64)
        val id65 = "a".repeat(65)

        check("H1 64 字符的 id 是合法定义", PiFootnote.parse("x[^$id64]\n[^$id64]: note").definitions.size, 1)
        check("H2 65 字符的 id 连定义都不成立", PiFootnote.parse("x[^$id65]\n[^$id65]: note").definitions.size, 0)
        check(
            "H3 65 字符的 id 因此整份文档原样保留",
            PiFootnote.parse("x[^$id65]\n[^$id65]: note").bodyText(),
            "x[^$id65]\n[^$id65]: note",
        )

        val mixed = PiFootnote.parse("ok[^$id64] bad[^$id65]\n[^$id64]: note")
        check("H4 64 字符的引用被替换成标记", mixed.bodyText().startsWith("ok¹"), true)
        check("H5 65 字符的引用是普通文本", mixed.bodyText().contains("[^$id65]"), true)
        check("H6 只有那一个引用被记下", mixed.references.size, 1)

        // 锚点：模型给出的是原文 id，渲染层要自己把它变成安全的锚点。
        val rawId = "a\"b<c"
        val raw = PiFootnote.parse("see[^$rawId]\n[^$rawId]: note")
        check("H7 带引号和尖括号的 id 仍是定义（模型不做清洗）", raw.definitions.size, 1)
        check("H8 Marker 带的是原文 id，不是生成的 slug", raw.parts.filterIsInstance<FootnoteBodyPart.Marker>().single().id, rawId)
        check("H9 定义 id 与引用 id 逐字一致", raw.definitions[0].id, rawId)

        val chinese = PiFootnote.parse("见[^注1]\n[^注1]: 说明")
        check("H10 中文 id 逐字保留", chinese.definitions[0].id, "注1")
        check("H11 中文 id 的正文替换", chinese.bodyText(), "见¹")

        val order = PiFootnote.parse("ref[^b]\n[^z]: Z\n[^a]: A\n[^b]: B\n[^y]: Y")
        check("H12 被引用的定义排在最前（首次引用顺序）", order.definitions.map { it.id }, listOf("b", "z", "a", "y"))
        check("H13 未引用定义按源码顺序排在后面", order.definitions.map { it.number }, listOf(1, 2, 3, 4))
        check("H14 只有 b 被标记为已引用", order.definitions.map { it.referenced }, listOf(true, false, false, false))
        check("H15 正文只替换被引用的那一个", order.bodyText(), "ref¹")
        check(
            "H16 同一份源码解析两次给出同样的 id 与编号（锚点稳定）",
            PiFootnote.parse("ref[^b]\n[^z]: Z\n[^a]: A\n[^b]: B\n[^y]: Y").definitions,
            order.definitions,
        )
    }

    // ========================================== I. the source-level rewrite
    //
    // `PiFootnoteMarkdown` 是真正接线的那一层：把 `[^id]` 变成上标、定义块变成末尾列表。
    // 它错的方式全是安静的 —— 编号错、未引用的定义也列进去、或者"没有脚注"的文档被丢回
    // 一份被改过的文本。所以这里断言的是**整份输出逐字节**，不是某个字段。
    run {
        check(
            "I1 a referenced footnote becomes a superscript plus a trailing list",
            PiFootnoteMarkdown.rewrite("正文[^1]\n\n[^1]: 说明"),
            "正文¹\n\n---\n\n1. 说明\n",
        )
        check(
            "I2 a document with no footnote is not rewritten",
            PiFootnoteMarkdown.rewrite("普通的一段 [x] 与 [1]"),
            null,
        )
        check(
            "I3 an unreferenced definition is not listed",
            PiFootnoteMarkdown.rewrite("正文[^a]\n\n[^a]: A\n[^b]: B"),
            "正文¹\n\n---\n\n1. A\n",
        )
        check(
            "I4 two references keep document order and the original body",
            PiFootnoteMarkdown.rewrite("一[^x] 二[^y]\n\n[^x]: X\n[^y]: Y"),
            "一¹ 二²\n\n---\n\n1. X\n2. Y\n",
        )
        check(
            "I5 an over-limit document falls back to null, not a half rewrite",
            PiFootnoteMarkdown.rewrite("a[^1]\n[^1]: b", FootnoteLimits(maxChars = 4)),
            null,
        )
        check(
            "I6 a multi-paragraph definition is flattened into one list item",
            PiFootnoteMarkdown.rewrite("a[^1]\n\n[^1]: 第一段\n\n    第二段"),
            "a¹\n\n---\n\n1. 第一段 第二段\n",
        )
        check(
            "I7 the rewrite is idempotent on its own output",
            PiFootnoteMarkdown.rewrite(
                PiFootnoteMarkdown.rewrite("a[^1]\n\n[^1]: note").orEmpty(),
            ),
            null,
        )
    }

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures of $checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
