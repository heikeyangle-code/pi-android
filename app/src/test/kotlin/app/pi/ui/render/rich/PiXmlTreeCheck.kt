package app.pi.ui.render.rich

// Bare-JVM harness for `PiXmlTree.kt` — the XML model behind the Phase-1 tree view
// (§七.2), plus its two security gates.
//
// The real `XmlPullParser` adapter (`PiXmlTreeParser.kt`) imports Android and
// therefore cannot be compiled here; what *can* be compiled is everything it
// delegates to, and that is where all the decisions live:
//
//  - `PiXmlTree.checkSource` — the size gate and the DOCTYPE/ENTITY refusal. This
//    is the file's security boundary, so the tests cover `<!DOCTYPE` outside and
//    inside comments, CDATA, processing instructions and attribute values: only
//    the first is a declaration, and a false positive would silently downgrade a
//    legitimate document.
//  - `XmlTreeBuilder` — the streaming builder, fed here exactly as the adapter
//    feeds it. Mismatched tags, multiple roots, text outside the root, duplicate
//    attributes, the depth/node caps and the failure latch are all behaviours the
//    adapter cannot add; they have to be right here.
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

private fun build(
    limits: XmlTreeLimits = XmlTreeLimits(),
    block: XmlTreeBuilder.() -> Unit,
): XmlTreeResult {
    val builder = XmlTreeBuilder(limits)
    builder.block()
    return builder.finish()
}

private fun tree(limits: XmlTreeLimits = XmlTreeLimits(), block: XmlTreeBuilder.() -> Unit): XmlTreeResult.Tree {
    val result = build(limits, block)
    if (result !is XmlTreeResult.Tree) error("expected a tree, got $result")
    return result
}

private fun failure(limits: XmlTreeLimits = XmlTreeLimits(), block: XmlTreeBuilder.() -> Unit): XmlTreeFailure {
    val result = build(limits, block)
    if (result !is XmlTreeResult.Failure) error("expected a failure, got $result")
    return result.reason
}

fun main() {
    // ===================================================== A. the declaration gate
    run {
        check("A1 a well-formed document passes", PiXmlTree.checkSource("<root><item/></root>"), null)
        check("A2 a DOCTYPE is refused", PiXmlTree.checkSource("<!DOCTYPE root><root/>"), XmlTreeFailure.Dtd)
        check("A3 a lower-case doctype is refused", PiXmlTree.checkSource("<!doctype root><root/>"), XmlTreeFailure.Dtd)
        check("A4 an ENTITY declaration is refused", PiXmlTree.checkSource("""<!ENTITY xxe "file:///etc/passwd"><root/>"""), XmlTreeFailure.Dtd)
        check("A5 an unknown declaration is refused", PiXmlTree.checkSource("<!FOO><root/>"), XmlTreeFailure.Dtd)
        check("A6 XXE is refused before parsing", PiXmlTree.checkSource("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"), XmlTreeFailure.Dtd)
        check(
            "A7 billion laughs is refused before parsing",
            PiXmlTree.checkSource(
                "<!DOCTYPE lolz [<!ENTITY lol \"lol\">" +
                    "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]><lolz>&lol2;</lolz>",
            ),
            XmlTreeFailure.Dtd,
        )
        check("A8 DOCTYPE inside a comment is not a declaration", PiXmlTree.checkSource("<root><!-- <!DOCTYPE x> --></root>"), null)
        check("A9 DOCTYPE inside CDATA is not a declaration", PiXmlTree.checkSource("<root><![CDATA[<!DOCTYPE x>]]></root>"), null)
        check("A10 DOCTYPE inside a PI is not a declaration", PiXmlTree.checkSource("""<?xml-stylesheet href="<!DOCTYPE x>"?><root/>"""), null)
        check("A11 DOCTYPE inside an attribute value is not a declaration", PiXmlTree.checkSource("""<root a="<!DOCTYPE x>"/>"""), null)
        check("A12 the scanner names DOCTYPE", PiXmlTree.findDangerousDeclaration("<!DOCTYPE root>"), "DOCTYPE")
        check("A13 the scanner names ENTITY", PiXmlTree.findDangerousDeclaration("""<!ENTITY x "y">"""), "ENTITY")
        check("A14 the scanner names other declarations", PiXmlTree.findDangerousDeclaration("<!FOO>"), "DECLARATION")
        check("A15 a clean document returns null", PiXmlTree.findDangerousDeclaration("<root a=\"<\"/>"), null)
        check("A16 a `<` in text is not a declaration", PiXmlTree.findDangerousDeclaration("a < b"), null)
        check("A17 an unterminated comment is left to the parser", PiXmlTree.findDangerousDeclaration("<root><!--"), null)
        check("A18 normal attributes are skipped", PiXmlTree.findDangerousDeclaration("""<root a="1" b='2'><child/></root>"""), null)
    }

    // ===================================================== B. empty / size gates
    run {
        check("B1 an empty source is Empty", PiXmlTree.checkSource(""), XmlTreeFailure.Empty)
        check("B2 a whitespace source is Empty", PiXmlTree.checkSource("  \n\t "), XmlTreeFailure.Empty)
        check(
            "B3 over maxChars is TooLarge",
            PiXmlTree.checkSource("<root/>", XmlTreeLimits(maxChars = 6)),
            XmlTreeFailure.TooLarge,
        )
        check(
            "B4 at maxChars is allowed",
            PiXmlTree.checkSource("<root/>", XmlTreeLimits(maxChars = 7)),
            null,
        )
        check("B5 the default limit is the plan's 256 KiB", XmlTreeLimits().maxChars, 256 * 1024)
        check("B6 the default depth is 64", XmlTreeLimits().maxDepth, 64)
        check("B7 the default node cap is 20,000", XmlTreeLimits().maxNodes, 20_000)
        check(
            "B8 256 KiB exactly is allowed by checkSource",
            PiXmlTree.checkSource("x".repeat(256 * 1024)),
            null,
        )
        check(
            "B9 one character over is TooLarge",
            PiXmlTree.checkSource("x".repeat(256 * 1024 + 1)),
            XmlTreeFailure.TooLarge,
        )
    }

    // ===================================================== C. the happy path
    run {
        val result = tree {
            startElement("root", listOf(XmlAttribute("id", "1"), XmlAttribute("lang", "zh")))
            startElement("item", emptyList())
            text("hello")
            endElement("item")
            startElement("item", listOf(XmlAttribute("n", "2")))
            text("world")
            endElement("item")
            endElement("root")
        }
        val root = result.root
        check("C1 node count", result.nodeCount, 3)
        check("C2 root name", root.name, "root")
        check("C3 root path", root.path, "/root")
        check("C4 root depth", root.depth, 0)
        check("C5 root attributes", root.attributes.map { it.name to it.value }, listOf("id" to "1", "lang" to "zh"))
        check("C6 root has no direct text", root.text, null)
        check("C7 root children", root.children.size, 2)
        check("C8 first child path", root.children[0].path, "/root/item")
        check("C9 second child path gets XPath's index", root.children[1].path, "/root/item[2]")
        check("C10 first child text", root.children[0].text, "hello")
        check("C11 second child text", root.children[1].text, "world")
        check("C12 second child depth", root.children[1].depth, 1)
        check("C13 child attribute path", root.children[1].attributePath(XmlAttribute("n", "2")), "/root/item[2]/@n")
        check("C14 child count", root.childCount, 2)
        check("C15 root is not empty", root.isEmptyElement, false)
        check("C16 the first item is not empty", root.children[0].isEmptyElement, false)
    }

    // ===================================================== D. text and paths
    run {
        val result = tree {
            startElement("root", emptyList())
            text("  padded \n text  ")
            startElement("a", emptyList())
            startElement("b", emptyList())
            text("deep")
            endElement("b")
            endElement("a")
            endElement("root")
        }
        val root = result.root
        check("D1 direct text is trimmed", root.text, "padded \n text")
        check("D2 a deep path", root.children[0].children[0].path, "/root/a/b")
        check("D3 deep text", root.children[0].children[0].text, "deep")
        check("D4 deep depth", root.children[0].children[0].depth, 2)
        check("D5 node count", result.nodeCount, 3)
    }

    run {
        val result = tree {
            startElement("root", emptyList())
            text("\n   ")
            startElement("empty", emptyList())
            endElement("empty")
            text("\n")
            endElement("root")
        }
        val root = result.root
        check("D6 indentation-only text is dropped", root.text, null)
        check("D7 an empty element has no text", root.children[0].text, null)
        check("D8 an empty element is empty", root.children[0].isEmptyElement, true)
        check("D9 an empty element has no children", root.children[0].childCount, 0)
    }

    // ===================================================== E. malformed documents
    run {
        check(
            "E1 a mismatched end tag",
            failure {
                startElement("root", emptyList())
                endElement("other")
            },
            XmlTreeFailure.Malformed,
        )
        check(
            "E2 an unclosed element at finish",
            failure {
                startElement("root", emptyList())
                startElement("child", emptyList())
            },
            XmlTreeFailure.Malformed,
        )
        check(
            "E3 text outside the root",
            failure {
                text("hello")
                startElement("root", emptyList())
                endElement("root")
            },
            XmlTreeFailure.Malformed,
        )
        check(
            "E4 whitespace outside the root is fine",
            tree {
                text("\n  ")
                startElement("root", emptyList())
                endElement("root")
                text("\n")
            }.root.path,
            "/root",
        )
        check(
            "E5 two roots",
            failure {
                startElement("root", emptyList())
                endElement("root")
                startElement("second", emptyList())
            },
            XmlTreeFailure.Malformed,
        )
        check(
            "E6 duplicate attributes",
            failure {
                startElement("root", listOf(XmlAttribute("a", "1"), XmlAttribute("a", "2")))
            },
            XmlTreeFailure.Malformed,
        )
        check(
            "E7 an invalid element name",
            failure {
                startElement("1root", emptyList())
            },
            XmlTreeFailure.Malformed,
        )
        check("E8 an empty builder is Empty", build { }.let { (it as? XmlTreeResult.Failure)?.reason }, XmlTreeFailure.Empty)
        check(
            "E9 an end tag with nothing open",
            failure { endElement("root") },
            XmlTreeFailure.Malformed,
        )
        check(
            "E10 the failure latches",
            build {
                startElement("root", emptyList())
                endElement("wrong")
                startElement("child", emptyList())
                endElement("child")
            }.let { (it as? XmlTreeResult.Failure)?.reason },
            XmlTreeFailure.Malformed,
        )
        check(
            "E11 a colon is a legal name character (namespace prefix)",
            tree {
                startElement("ns:root", emptyList())
                endElement("ns:root")
            }.root.name,
            "ns:root",
        )
        check(
            "E12 an underscore is a legal name start",
            tree {
                startElement("_root", emptyList())
                endElement("_root")
            }.root.name,
            "_root",
        )
    }

    // ===================================================== F. bounds
    run {
        check(
            "F1 two levels fit a maxDepth of 2",
            tree(XmlTreeLimits(maxDepth = 2)) {
                startElement("root", emptyList())
                startElement("child", emptyList())
                endElement("child")
                endElement("root")
            }.nodeCount,
            2,
        )
        check(
            "F2 a third level is TooDeep",
            failure(XmlTreeLimits(maxDepth = 2)) {
                startElement("root", emptyList())
                startElement("child", emptyList())
                startElement("grandchild", emptyList())
            },
            XmlTreeFailure.TooDeep,
        )
        check(
            "F3 two elements fit maxNodes 2",
            tree(XmlTreeLimits(maxNodes = 2)) {
                startElement("root", emptyList())
                startElement("child", emptyList())
                endElement("child")
                endElement("root")
            }.nodeCount,
            2,
        )
        check(
            "F4 a third element is TooManyNodes",
            failure(XmlTreeLimits(maxNodes = 2)) {
                startElement("root", emptyList())
                startElement("a", emptyList())
                endElement("a")
                startElement("b", emptyList())
            },
            XmlTreeFailure.TooManyNodes,
        )
        check(
            "F5 64 nested elements fit the default depth",
            tree {
                for (depth in 0 until 64) startElement("n$depth", emptyList())
                for (depth in 63 downTo 0) endElement("n$depth")
            }.nodeCount,
            64,
        )
        check(
            "F6 a 65th nested element is TooDeep",
            failure {
                for (depth in 0 until 65) startElement("n$depth", emptyList())
            },
            XmlTreeFailure.TooDeep,
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
