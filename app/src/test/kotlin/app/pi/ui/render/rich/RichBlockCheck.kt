package app.pi.ui.render.rich

// Bare-JVM harness for `RichBlock.kt` — the Phase-1 classifier that decides whether
// a fence gets a structured view or keeps the existing code block.
//
// The whole point of this file is that classification is **language-only**. The
// failures that would matter on a device are quiet ones: an object-looking `text`
// fence switching to a JSON tree, a streaming fence switching mid-stream, an
// over-limit payload reaching the tree parser. None of those crash; they just make
// the transcript lie or stall. So every gate is exercised here, including the
// reserved kinds that Phase 1 must keep as `Code`.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private val JSON_SOURCE = """{"a":1}"""
private val XML_SOURCE = "<root><item/></root>"
private val YAML_SOURCE = "a: 1"

fun main() {
    // =================================================== A. the language table
    run {
        check("A1 json", RichBlock.kindForLanguage("json"), RichBlockKind.Json)
        check("A2 JSON is lower-cased", RichBlock.kindForLanguage("JSON"), RichBlockKind.Json)
        check("A3 jsonc", RichBlock.kindForLanguage("jsonc"), RichBlockKind.Json)
        check("A4 xml", RichBlock.kindForLanguage("xml"), RichBlockKind.Xml)
        check("A5 yaml", RichBlock.kindForLanguage("yaml"), RichBlockKind.Yaml)
        check("A6 yml", RichBlock.kindForLanguage("yml"), RichBlockKind.Yaml)
        check("A7 mermaid is not Phase 1", RichBlock.kindForLanguage("mermaid"), RichBlockKind.Code)
        check("A8 svg is not Phase 1", RichBlock.kindForLanguage("svg"), RichBlockKind.Code)
        check("A9 html is not Phase 1", RichBlock.kindForLanguage("html"), RichBlockKind.Code)
        check("A10 other is not Phase 1", RichBlock.kindForLanguage("other"), RichBlockKind.Code)
        check("A11 diff stays code", RichBlock.kindForLanguage("diff"), RichBlockKind.Code)
        check("A12 no language stays code", RichBlock.kindForLanguage(""), RichBlockKind.Code)
        check("A13 unknown language stays code", RichBlock.kindForLanguage("brainfuck"), RichBlockKind.Code)
        check("A14 json5 is not json", RichBlock.kindForLanguage("json5"), RichBlockKind.Code)
        check("A15 ndjson is not json", RichBlock.kindForLanguage("ndjson"), RichBlockKind.Code)
        check("A16 a file extension is not a language", RichBlock.kindForLanguage("application/json"), RichBlockKind.Code)
    }

    // =================================================== B. classify: the happy path
    run {
        check(
            "B1 an explicit json fence becomes a tree",
            RichBlock.classify("json", JSON_SOURCE, settled = true),
            RichBlockKind.Json,
        )
        check(
            "B2 an explicit xml fence becomes a tree",
            RichBlock.classify("xml", XML_SOURCE, settled = true),
            RichBlockKind.Xml,
        )
        check(
            "B3 an explicit yaml fence is recognized",
            RichBlock.classify("yaml", YAML_SOURCE, settled = true),
            RichBlockKind.Yaml,
        )
        check(
            "B4 an explicit yml fence is recognized",
            RichBlock.classify("yml", YAML_SOURCE, settled = true),
            RichBlockKind.Yaml,
        )
        check(
            "B5 upper-case language works",
            RichBlock.classify("JSON", JSON_SOURCE, settled = true),
            RichBlockKind.Json,
        )
        check(
            "B6 a title attribute does not break recognition",
            RichBlock.classify("""json title="data.json"""", JSON_SOURCE, settled = true),
            RichBlockKind.Json,
        )
        check(
            "B7 a filename on an unknown language stays code",
            RichBlock.classify("""ts title="src/main.ts"""", "const a = 1", settled = true),
            RichBlockKind.Code,
        )
        check(
            "B8 null info means code",
            RichBlock.classify(null, JSON_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "B9 an empty info string means code",
            RichBlock.classify("   ", JSON_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "B10 the FenceInfo overload agrees",
            RichBlock.classify(FenceInfo.parse("json"), JSON_SOURCE, settled = true),
            RichBlockKind.Json,
        )
    }

    // =================================================== C. no content sniffing
    run {
        check(
            "C1 JSON text in a plain text fence stays code",
            RichBlock.classify("text", JSON_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "C2 JSON text with no language stays code",
            RichBlock.classify("", JSON_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "C3 XML text in a kotlin fence stays code",
            RichBlock.classify("kotlin", XML_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "C4 a yaml body in a text fence stays code",
            RichBlock.classify("text", YAML_SOURCE, settled = true),
            RichBlockKind.Code,
        )
        check(
            "C5 a json-tagged prose body is still Json (classification is language-only)",
            RichBlock.classify("json", "not json at all", settled = true),
            RichBlockKind.Json,
        )
    }

    // =================================================== D. completeness gate
    run {
        check(
            "D1 a streaming fence stays code",
            RichBlock.classify("json", JSON_SOURCE, settled = false),
            RichBlockKind.Code,
        )
        check(
            "D2 streaming wins over a perfectly valid body",
            RichBlock.classify("xml", XML_SOURCE, settled = false),
            RichBlockKind.Code,
        )
        check(
            "D3 settled code fences are unaffected",
            RichBlock.classify("kotlin", "val a = 1", settled = true),
            RichBlockKind.Code,
        )
    }

    // =================================================== E. the size gate
    run {
        val limits = RichBlockLimits(maxChars = 10, maxLines = 3)
        check("E1 a body exactly at maxChars is allowed", RichBlock.exceedsLimits("x".repeat(10), limits), false)
        check("E2 one character over is rejected", RichBlock.exceedsLimits("x".repeat(11), limits), true)
        check(
            "E3 over-limit json falls back to code",
            RichBlock.classify("json", "x".repeat(11), settled = true, limits = limits),
            RichBlockKind.Code,
        )
        check("E4 three lines are allowed", RichBlock.exceedsLimits("a\nb\nc", limits), false)
        check("E5 four lines are rejected", RichBlock.exceedsLimits("a\nb\nc\nd", limits), true)
        check(
            "E6 a trailing newline does not add a line",
            RichBlock.exceedsLimits("a\nb\nc\n", limits),
            false,
        )
        check(
            "E7 over the line limit falls back to code",
            RichBlock.classify("json", "a\nb\nc\nd", settled = true, limits = limits),
            RichBlockKind.Code,
        )
        check(
            "E8 the defaults are the plan's 256 KiB",
            RichBlock.DEFAULT_LIMITS.maxChars,
            256 * 1024,
        )
        check(
            "E9 the default line cap is this app's own",
            RichBlock.DEFAULT_LIMITS.maxLines,
            20_000,
        )
        check(
            "E10 exactly 256 KiB is still accepted (the gate is strictly greater)",
            RichBlock.exceedsLimits("x".repeat(256 * 1024)),
            false,
        )
        check(
            "E11 one byte over 256 KiB is rejected",
            RichBlock.exceedsLimits("x".repeat(256 * 1024 + 1)),
            true,
        )
    }

    // =================================================== F. empty and failed bodies
    run {
        check("F1 empty json body stays code", RichBlock.classify("json", "", settled = true), RichBlockKind.Code)
        check("F2 whitespace json body stays code", RichBlock.classify("json", "  \n ", settled = true), RichBlockKind.Code)
        check(
            "F3 a rejected parse forces code",
            RichBlock.classify("json", JSON_SOURCE, settled = true, bodyValid = false),
            RichBlockKind.Code,
        )
        check(
            "F4 a rejected xml parse forces code",
            RichBlock.classify("xml", XML_SOURCE, settled = true, bodyValid = false),
            RichBlockKind.Code,
        )
        check(
            "F5 a rejected body cannot turn a code fence into a tree",
            RichBlock.classify("text", JSON_SOURCE, settled = true, bodyValid = false),
            RichBlockKind.Code,
        )
    }

    // =================================================== G. reserved kinds
    run {
        check("G1 Code has a renderer", RichBlock.hasRenderer(RichBlockKind.Code), true)
        check("G2 Json has a renderer", RichBlock.hasRenderer(RichBlockKind.Json), true)
        check("G3 Xml has a renderer", RichBlock.hasRenderer(RichBlockKind.Xml), true)
        check("G4 Yaml is recognized but unsupported (see PiYamlTree)", RichBlock.hasRenderer(RichBlockKind.Yaml), true)
        check("G5 Other is not rendered", RichBlock.hasRenderer(RichBlockKind.Other), false)
        check("G6 Mermaid is not rendered in Phase 1", RichBlock.hasRenderer(RichBlockKind.Mermaid), false)
        check("G7 Svg is not rendered in Phase 1", RichBlock.hasRenderer(RichBlockKind.Svg), false)
        check("G8 Html is not rendered in Phase 1", RichBlock.hasRenderer(RichBlockKind.Html), false)

        val reserved = listOf("other", "mermaid", "svg", "html", "vega-lite", "dot", "plantuml")
        for (language in reserved) {
            check(
                "G9 Phase 1 never classifies `$language` into a reserved kind",
                RichBlock.classify(language, "whatever", settled = true),
                RichBlockKind.Code,
            )
        }
    }

    // =================================================== H. the YAML placeholder
    run {
        check("H1 this build has no safe YAML parser", PiYamlTree.isSupported, false)
        check(
            "H2 a settled YAML body reports Unsupported",
            PiYamlTree.parse(YAML_SOURCE),
            YamlTreeResult.Failure(YamlTreeFailure.Unsupported),
        )
        check(
            "H3 a streaming YAML body reports NotSettled",
            PiYamlTree.parse(YAML_SOURCE, settled = false),
            YamlTreeResult.Failure(YamlTreeFailure.NotSettled),
        )
        check(
            "H4 an empty YAML body reports Empty",
            PiYamlTree.parse("  "),
            YamlTreeResult.Failure(YamlTreeFailure.Empty),
        )
        check(
            "H5 an oversized YAML body reports TooLarge before Unsupported",
            PiYamlTree.parse("x".repeat(11), limits = YamlTreeLimits(maxChars = 10)),
            YamlTreeResult.Failure(YamlTreeFailure.TooLarge),
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
