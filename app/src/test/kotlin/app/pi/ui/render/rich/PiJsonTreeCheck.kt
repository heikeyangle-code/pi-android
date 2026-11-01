package app.pi.ui.render.rich

// Bare-JVM harness for `PiJsonTree.kt` — the JSON model behind the Phase-1 tree
// view (§七.1).
//
// The view cannot be compiled here (Compose), so this file pins the half that can
// lie quietly: the tree's *shape* (kinds, paths, child order, node counts), the
// four limits, and the hostile-input paths (deep nesting, oversized bodies,
// malformed documents). `kotlinx.serialization.json` is the project's existing
// dependency, and the same parser runs under Gradle, so a passing check here is a
// statement about the real parser rather than about a test double.
//
// Paths are part of the contract because "复制路径" copies them verbatim: an object
// key containing a dot must not turn into two path segments, and a key containing
// a quote must remain one.
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

private fun tree(
    source: String,
    settled: Boolean = true,
    limits: JsonTreeLimits = JsonTreeLimits(),
): JsonTreeResult.Tree {
    val result = PiJsonTree.parse(source, settled, limits)
    if (result !is JsonTreeResult.Tree) error("expected a tree, got $result")
    return result
}

private fun failure(
    source: String,
    settled: Boolean = true,
    limits: JsonTreeLimits = JsonTreeLimits(),
): JsonTreeFailure {
    val result = PiJsonTree.parse(source, settled, limits)
    if (result !is JsonTreeResult.Failure) error("expected a failure, got $result")
    return result.reason
}

private fun objectEntries(source: String): Map<String, JsonTreeNode> {
    val root = tree(source).root
    if (root !is JsonObjectNode) error("expected an object root, got $root")
    return root.entries.associate { it.key to it.value }
}

fun main() {
    // ====================================================== A. scalar kinds
    run {
        val entries = objectEntries(
            """{"s":"hi","n":1.5,"b":true,"nf":false,"z":null,"snull":"null"}""",
        )
        val s = entries["s"] as JsonScalarNode
        check("A1 string kind", s.kind, JsonScalarKind.String)
        check("A2 string text is decoded", s.text, "hi")
        check("A3 string literal keeps quotes", s.literal, "\"hi\"")
        check("A4 string path", s.path, "$.s")
        check("A5 string depth", s.depth, 1)

        check("A6 number kind", (entries["n"] as JsonScalarNode).kind, JsonScalarKind.Number)
        check("A7 number text", (entries["n"] as JsonScalarNode).text, "1.5")
        check("A8 true is Boolean", (entries["b"] as JsonScalarNode).kind, JsonScalarKind.Boolean)
        check("A9 false is Boolean", (entries["nf"] as JsonScalarNode).kind, JsonScalarKind.Boolean)
        check("A10 null kind", (entries["z"] as JsonScalarNode).kind, JsonScalarKind.Null)
        check("A11 null text", (entries["z"] as JsonScalarNode).text, "null")
        check("A12 null literal", (entries["z"] as JsonScalarNode).literal, "null")
        check("A13 the string \"null\" is a String", (entries["snull"] as JsonScalarNode).kind, JsonScalarKind.String)
        check("A14 the string \"null\" text", (entries["snull"] as JsonScalarNode).text, "null")
        check("A15 a leaf is not a container", (entries["s"] as JsonTreeNode).isContainer, false)
        check("A16 a leaf has no children", (entries["s"] as JsonTreeNode).childCount, 0)
    }

    // ====================================================== B. arrays and paths
    run {
        val result = tree("""[1,{"a":[]},[true]]""")
        check("B1 node count", result.nodeCount, 6)
        val root = result.root as JsonArrayNode
        check("B2 root path", root.path, "$")
        check("B3 root depth", root.depth, 0)
        check("B4 item count", root.items.size, 3)
        check("B5 first item index", root.items[0].index, 0)
        check("B6 first item path", root.items[0].value.path, "$[0]")
        check("B7 second item path", root.items[1].value.path, "$[1]")
        check("B8 second item is an object", root.items[1].value is JsonObjectNode, true)
        val inner = root.items[1].value as JsonObjectNode
        check("B9 nested object member path", inner.entries[0].value.path, "$[1].a")
        check("B10 nested object member is an array", inner.entries[0].value is JsonArrayNode, true)
        check("B11 nested array path", (root.items[2].value as JsonArrayNode).items[0].value.path, "$[2][0]")
        check("B12 the root is a container", root.isContainer, true)
        check("B13 the root child count", root.childCount, 3)
    }

    // ====================================================== C. path escaping
    run {
        val entries = objectEntries(
            """{"a.b":1,"a b":2,"":3,"quote'":4,"back\\slash":5,"中文":6,"_ok":7}""",
        )
        check("C1 dot key is bracketed", entries["a.b"]!!.path, "$['a.b']")
        check("C2 space key is bracketed", entries["a b"]!!.path, "$['a b']")
        check("C3 empty key is bracketed", entries[""]!!.path, "$['']")
        check("C4 quote key is escaped", entries["quote'"]!!.path, "$['quote\\'']")
        check("C5 backslash key is escaped", entries["back\\slash"]!!.path, "$['back\\\\slash']")
        check("C6 unicode identifier stays dotted", entries["中文"]!!.path, "$.中文")
        check("C7 underscore identifier stays dotted", entries["_ok"]!!.path, "$._ok")
        check("C8 childPath is the same rule", PiJsonTree.childPath("$", "a.b"), "$['a.b']")
        check("C9 childPath for a simple key", PiJsonTree.childPath("$.u", "name"), "$.u.name")
        check("C10 itemPath", PiJsonTree.itemPath("$.items", 3), "$.items[3]")
        check("C11 itemPath at the root", PiJsonTree.itemPath("$", 0), "$[0]")
    }

    // ====================================================== D. root scalars
    run {
        check("D1 a number root", tree("42").root !is JsonScalarNode, false)
        val number = tree("42").root as JsonScalarNode
        check("D2 number root kind", number.kind, JsonScalarKind.Number)
        check("D3 number root path", number.path, "$")
        check("D4 number root depth", number.depth, 0)
        check("D5 a string root", (tree("\"x\"").root as JsonScalarNode).kind, JsonScalarKind.String)
        check("D6 a boolean root", (tree("true").root as JsonScalarNode).kind, JsonScalarKind.Boolean)
        check("D7 a null root", (tree("null").root as JsonScalarNode).kind, JsonScalarKind.Null)
        check("D8 a scalar root counts as one node", tree("42").nodeCount, 1)
    }

    // ====================================================== E. empty / streaming
    run {
        check("E1 an empty body", failure(""), JsonTreeFailure.Empty)
        check("E2 a whitespace body", failure("  \n\t "), JsonTreeFailure.Empty)
        check("E3 a streaming body", failure("""{"a":1}""", settled = false), JsonTreeFailure.NotSettled)
        check("E4 streaming wins over an otherwise valid body", failure("[]", settled = false), JsonTreeFailure.NotSettled)
    }

    // ====================================================== F. malformed input
    run {
        check("F1 an open brace", failure("{"), JsonTreeFailure.Malformed)
        check("F2 a missing value", failure("""{"a":}"""), JsonTreeFailure.Malformed)
        check("F3 a trailing comma", failure("""{"a":1,}"""), JsonTreeFailure.Malformed)
        check("F4 an unterminated string", failure("\"abc"), JsonTreeFailure.Malformed)
        check("F5 plain prose", failure("hello"), JsonTreeFailure.Malformed)
        check("F6 a JSONC comment (no comment parser in this build)", failure("{\n  // note\n  \"a\": 1\n}"), JsonTreeFailure.Malformed)
        check("F7 NaN is refused", failure("NaN"), JsonTreeFailure.Malformed)
        check("F8 Inf is refused", failure("Infinity"), JsonTreeFailure.Malformed)
        // kotlinx's `parseToJsonElement` accepts each of these as a bare literal;
        // they are not JSON, and the model refuses them by validating every
        // non-string primitive's token.
        check("F9 a leading-zero number is refused", failure("01"), JsonTreeFailure.Malformed)
        check("F10 a bare word is refused", failure("hello"), JsonTreeFailure.Malformed)
        check("F11 a single-quoted literal is refused", failure("'x'"), JsonTreeFailure.Malformed)
        check("F12 a plus sign is refused", failure("+1"), JsonTreeFailure.Malformed)
        check("F13 a leading dot is refused", failure(".5"), JsonTreeFailure.Malformed)
        check("F14 a trailing dot is refused", failure("1."), JsonTreeFailure.Malformed)
        check("F15 a doubled fraction is refused", failure("1.2.3"), JsonTreeFailure.Malformed)
        check("F16 a boolean prefix is refused", failure("truex"), JsonTreeFailure.Malformed)
        check("F17 an unquoted object value is refused", failure("""{"a": hello}"""), JsonTreeFailure.Malformed)
        check("F18 a raw newline inside a string is refused", failure("{\"a\":\"x\ny\"}"), JsonTreeFailure.Malformed)
        check("F19 a raw tab inside a string is refused", failure("{\"a\":\"x\ty\"}"), JsonTreeFailure.Malformed)
        check("F20 an escaped newline is fine", tree("""{"a":"x\ny"}""").nodeCount, 2)
    }

    // ====================================================== L. valid numbers
    run {
        val entries = objectEntries(
            """{"a":0,"b":-0,"c":12,"d":-12,"e":1.5,"f":1e3,"g":1E-2,"h":-0.5e+7,"i":123456789012345678901234567890}""",
        )
        for (key in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")) {
            check("L1 `$key` is a Number", (entries[key] as JsonScalarNode).kind, JsonScalarKind.Number)
        }
        check("L2 a big integer keeps its literal", (entries["i"] as JsonScalarNode).text, "123456789012345678901234567890")
        check("L3 an exponent keeps its literal", (entries["h"] as JsonScalarNode).text, "-0.5e+7")
    }

    // ====================================================== G. size limits
    run {
        check("G1 at maxChars is accepted", tree("""{"a":1}""", limits = JsonTreeLimits(maxChars = 7)).nodeCount, 2)
        check("G2 over maxChars is rejected", failure("""{"a":11}""", limits = JsonTreeLimits(maxChars = 7)), JsonTreeFailure.TooLarge)
        check("G3 a huge body is rejected before parsing", failure("x".repeat(300 * 1024)), JsonTreeFailure.TooLarge)
        check("G4 maxChars is the plan's 256 KiB", JsonTreeLimits().maxChars, 256 * 1024)
        check("G5 maxDepth is the plan's 64", JsonTreeLimits().maxDepth, 64)
        check("G6 maxNodes is the plan's 20,000", JsonTreeLimits().maxNodes, 20_000)
    }

    // ====================================================== H. depth limits
    run {
        check("H1 two levels fit a maxDepth of 2", tree("[[0]]", limits = JsonTreeLimits(maxDepth = 2)).nodeCount, 3)
        check("H2 three levels do not", failure("[[[0]]]", limits = JsonTreeLimits(maxDepth = 2)), JsonTreeFailure.TooDeep)
        check("H3 64 nested arrays are allowed", tree("[".repeat(64) + "0" + "]".repeat(64)).nodeCount, 65)
        check("H4 65 nested arrays are not", failure("[".repeat(65) + "0" + "]".repeat(65)), JsonTreeFailure.TooDeep)
        check(
            "H5 a 100,000-deep hostile body fails fast instead of overflowing",
            failure("[".repeat(100_000)),
            JsonTreeFailure.TooDeep,
        )
        check(
            "H6 braces inside strings do not count as depth",
            tree("""{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}""").nodeCount,
            2,
        )
    }

    // ====================================================== I. node limits
    run {
        check("I1 two nodes fit a maxNodes of 2", tree("[1]", limits = JsonTreeLimits(maxNodes = 2)).nodeCount, 2)
        check("I2 three nodes do not", failure("[1,2,3]", limits = JsonTreeLimits(maxNodes = 2)), JsonTreeFailure.TooManyNodes)
        check(
            "I3 the failure is a failure, not a partial tree",
            PiJsonTree.parse("[1,2,3]", limits = JsonTreeLimits(maxNodes = 2)) is JsonTreeResult.Failure,
            true,
        )
        check(
            "I4 a big but legal array stays within the default node bound",
            tree("[" + (1..500).joinToString(",") + "]").nodeCount,
            501,
        )
    }

    // ====================================================== J. strings and escapes
    run {
        val entries = objectEntries("""{"a":"line\nnext","b":"quote\"q","c":"\u00e9","d":""}""")
        check("J1 a decoded newline stays in text", (entries["a"] as JsonScalarNode).text, "line\nnext")
        check("J2 a decoded quote stays in text", (entries["b"] as JsonScalarNode).text, "quote\"q")
        check("J3 a unicode escape is decoded", (entries["c"] as JsonScalarNode).text, "é")
        check("J4 an empty string is a String", (entries["d"] as JsonScalarNode).kind, JsonScalarKind.String)
        check("J5 an empty string literal is quoted", (entries["d"] as JsonScalarNode).literal, "\"\"")
        check("J6 the literal keeps the escape", (entries["a"] as JsonScalarNode).literal, "\"line\\nnext\"")
    }

    // ====================================================== K. hostile shapes
    run {
        val polluted = tree("""{"__proto__":{"polluted":true},"constructor":1}""").root as JsonObjectNode
        check("K1 __proto__ is data, not a prototype write", polluted.entries.size, 2)
        check("K2 the nested object is present", polluted.entries[0].value is JsonObjectNode, true)
        val template = objectEntries("""{"a":"${'$'}{jndi:ldap://x}","b":"<script>"}""")
        check("K3 template syntax is just text", (template["a"] as JsonScalarNode).text, "\${jndi:ldap://x}")
        check("K4 HTML is just text", (template["b"] as JsonScalarNode).text, "<script>")
        check(
            "K5 a duplicate key is one object entry",
            (tree("""{"a":1,"a":2}""").root as JsonObjectNode).entries.size,
            1,
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
