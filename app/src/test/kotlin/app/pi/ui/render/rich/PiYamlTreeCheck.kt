package app.pi.ui.render.rich

// Bare-JVM harness for `PiYamlTree.kt` — the safe YAML model behind the Phase-1
// tree view (§七.3), plus the parser configuration that makes it safe.
//
// This file deliberately runs `PiYamlTree.parse` (the public entry point) rather
// than poking at `LoaderOptions`: the point is that the *observable* result of a
// hostile document is a stated Failure, not that some option was assigned. The
// SnakeYAML jar is the only dependency beyond the Kotlin stdlib; the runner must
// stage it (see `tools/run-app-pure-checks.sh`), because `PiYamlTree.kt` imports
// `org.yaml.snakeyaml.*`.
//
// Coverage:
//  - gates: streaming, empty, size, limits' defaults;
//  - the happy path: Map / List / Scalar / Null, kinds, paths, depths, node count;
//  - anchors, aliases and merge keys (legal YAML, must work);
//  - alias *bombs* and cycles (must fail, never expand forever);
//  - dangerous tags (`!!java/object:…`, `!ruby/object:…`, `!custom`) — refused;
//    standard tags (`!!str`, `!!int`, `!!binary`, `!!timestamp`, `!!set`) — kept;
//  - depth, node-count and character bounds;
//  - malformed YAML, duplicate keys, undefined aliases, multiple documents;
//  - path escaping and the values "复制路径/复制值" would produce.
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

private fun parse(source: String, limits: YamlTreeLimits = YamlTreeLimits()): YamlTreeResult =
    PiYamlTree.parse(source, settled = true, limits = limits)

private fun tree(source: String, limits: YamlTreeLimits = YamlTreeLimits()): YamlTreeResult.Tree {
    val result = parse(source, limits)
    if (result !is YamlTreeResult.Tree) error("expected a tree for <$source>, got $result")
    return result
}

private fun failure(source: String, limits: YamlTreeLimits = YamlTreeLimits()): YamlTreeFailure {
    val result = parse(source, limits)
    if (result !is YamlTreeResult.Failure) error("expected a failure for <$source>, got $result")
    return result.reason
}

private fun scalar(source: String): YamlScalarNode {
    val root = tree(source).root
    if (root !is YamlScalarNode) error("expected a scalar root, got $root")
    return root
}

private fun entries(root: YamlTreeNode): Map<String, YamlTreeNode> {
    if (root !is YamlMapNode) error("expected a mapping, got $root")
    return root.entries.associate { it.key to it.value }
}

/** `levels` nested mappings below the root, then a leaf. */
private fun nestedMappings(levels: Int): String {
    val out = StringBuilder()
    for (index in 0 until levels) {
        out.append("  ".repeat(index)).append("k$index:\n")
    }
    out.append("  ".repeat(levels)).append("leaf: 1\n")
    return out.toString()
}

fun main() {
    // ===================================================== A. the gates
    run {
        check("A1 this build has a safe YAML parser", PiYamlTree.isSupported, true)
        check(
            "A2 a streaming fence reports NotSettled",
            PiYamlTree.parse("a: 1", settled = false),
            YamlTreeResult.Failure(YamlTreeFailure.NotSettled),
        )
        check("A3 an empty body reports Empty", parse(""), YamlTreeResult.Failure(YamlTreeFailure.Empty))
        check("A4 a whitespace body reports Empty", parse(" \n\t\n "), YamlTreeResult.Failure(YamlTreeFailure.Empty))
        check("A5 the default limit is the plan's 256 KiB", YamlTreeLimits().maxChars, 256 * 1024)
        check("A6 the default depth is 64", YamlTreeLimits().maxDepth, 64)
        check("A7 the default node cap is 20,000", YamlTreeLimits().maxNodes, 20_000)
        check("A8 the default alias cap is SnakeYAML's 50", YamlTreeLimits().maxAliasesForCollections, 50)
        check(
            "A9 over maxChars is TooLarge before parsing",
            failure("a: 123456", YamlTreeLimits(maxChars = 5)),
            YamlTreeFailure.TooLarge,
        )
        check("A10 exactly maxChars is allowed", tree("a: 12", YamlTreeLimits(maxChars = 5)).nodeCount, 2)
        check(
            "A11 a zero bound is rejected by the limits' own invariant",
            runCatching { YamlTreeLimits(maxDepth = 0) }.isFailure,
            true,
        )
        check(
            "A12 a 300 KiB body is rejected before parsing",
            failure("a: " + "x".repeat(300 * 1024)),
            YamlTreeFailure.TooLarge,
        )
    }

    // ===================================================== B. the happy path
    run {
        val source = """
            name: pi
            version: 1.2
            enabled: true
            missing: null
            tags: [a, b]
            server:
              host: localhost
              ports:
                - 8080
                - 9090
        """.trimIndent()
        val result = tree(source)
        check("B1 node count", result.nodeCount, 13)
        check("B2 root path", result.root.path, "$")
        check("B3 root depth", result.root.depth, 0)
        check("B4 root is a mapping", result.root is YamlMapNode, true)
        check("B5 root child count", result.root.childCount, 6)

        val map = entries(result.root)
        check("B6 mapping keys keep document order", map.keys.toList(), listOf("name", "version", "enabled", "missing", "tags", "server"))
        check("B7 a string leaf", (map["name"] as YamlScalarNode).text, "pi")
        check("B8 a string leaf is a String", (map["name"] as YamlScalarNode).kind, YamlScalarKind.String)
        check("B9 a number leaf", (map["version"] as YamlScalarNode).kind, YamlScalarKind.Number)
        check("B10 a number leaf's text", (map["version"] as YamlScalarNode).text, "1.2")
        check("B11 a boolean leaf", (map["enabled"] as YamlScalarNode).kind, YamlScalarKind.Boolean)
        check("B12 a null leaf", (map["missing"] as YamlScalarNode).kind, YamlScalarKind.Null)
        check("B13 a null leaf's text", (map["missing"] as YamlScalarNode).text, "null")

        val tags = map["tags"] as YamlListNode
        check("B14 a sequence path", tags.path, "$.tags")
        check("B15 a sequence item count", tags.items.size, 2)
        check("B16 a sequence item path", tags.items[1].value.path, "$.tags[1]")
        check("B17 a sequence item depth", tags.items[1].value.depth, 2)
        check("B18 a sequence item value", (tags.items[1].value as YamlScalarNode).text, "b")

        val server = map["server"] as YamlMapNode
        check("B19 a nested mapping path", server.path, "$.server")
        check("B20 a nested mapping depth", server.depth, 1)
        check("B21 a nested mapping's key", server.entries[0].key, "host")
        check("B22 a deep path", server.entries[1].value.path, "$.server.ports")
        check("B23 a nested sequence path", (server.entries[1].value as YamlListNode).items[1].value.path, "$.server.ports[1]")
        check("B24 scalar childCount", map["name"]!!.childCount, 0)
        check("B25 mapping childCount", result.root.childCount, 6)
        check("B26 a scalar is not a container", map["name"]!!.isContainer, false)
        check("B27 a mapping is a container", result.root.isContainer, true)
    }

    // ===================================================== C. anchors and aliases
    run {
        val source = """
            defaults: &defaults
              adapter: postgres
              pool: 5
            development:
              <<: *defaults
              database: dev
            copy: *defaults
            scalar: &v 42
            again: *v
        """.trimIndent()
        val result = tree(source)
        check("C1 node count", result.nodeCount, 13)
        val map = entries(result.root)

        val defaults = map["defaults"] as YamlMapNode
        check("C2 the anchored mapping", defaults.entries.map { it.key }, listOf("adapter", "pool"))
        check("C3 the anchored mapping path", defaults.path, "$.defaults")

        val development = map["development"] as YamlMapNode
        check("C4 a merge key is flattened, not exposed as <<", development.entries.map { it.key }, listOf("adapter", "pool", "database"))
        check("C5 merged value", (development.entries[0].value as YamlScalarNode).text, "postgres")
        check("C6 merged key path", development.entries[1].value.path, "$.development.pool")
        check("C7 the alias is an independent subtree", development.entries[0].value !== (map["copy"] as YamlMapNode).entries[0].value, true)

        val copy = map["copy"] as YamlMapNode
        check("C8 a mapping alias", copy.path, "$.copy")
        check("C9 a mapping alias's keys", copy.entries.map { it.key }, listOf("adapter", "pool"))
        check("C10 a mapping alias's depth", copy.depth, 1)

        check("C11 a scalar anchor", (map["scalar"] as YamlScalarNode).text, "42")
        check("C12 a scalar alias", (map["again"] as YamlScalarNode).text, "42")
        check("C13 a scalar alias keeps the number kind", (map["again"] as YamlScalarNode).kind, YamlScalarKind.Number)
    }

    // ===================================================== D. dangerous tags
    run {
        check(
            "D1 a java/object tag is refused",
            failure("a: !!java/object:java.lang.Object {x: 1}"),
            YamlTreeFailure.Malformed,
        )
        check(
            "D2 a ruby/object tag is refused",
            failure("a: !ruby/object:Object {}"),
            YamlTreeFailure.Malformed,
        )
        check("D3 an unknown local tag is refused", failure("a: !custom value"), YamlTreeFailure.Malformed)
        check(
            "D4 the ScriptEngineManager gadget chain is refused",
            failure(
                "!!javax.script.ScriptEngineManager " +
                    "[!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]",
            ),
            YamlTreeFailure.Malformed,
        )
        check(
            "D5 an unknown type tag is refused",
            failure("a: !!com.example.Evil {x: 1}"),
            YamlTreeFailure.Malformed,
        )

        // Standard tags are exactly the SafeConstructor's fixed table; they must
        // still work, or the safety gate would have turned into a YAML subset.
        check("D6 !!str", scalar("!!str 123").let { it.text to it.kind }, "123" to YamlScalarKind.String)
        check("D7 !!int", scalar("!!int '42'").let { it.text to it.kind }, "42" to YamlScalarKind.Number)
        check("D8 !!binary is kept as Base64", scalar("!!binary aGVsbG8=").text, "aGVsbG8=")
        check("D9 !!timestamp is kept as an ISO instant", scalar("!!timestamp 2020-01-01T00:00:00Z").text, "2020-01-01T00:00:00Z")
        check("D10 !!set becomes a sequence of members", scalarOrList("!!set {a, b}"), listOf("a", "b"))
        check("D11 !!pairs becomes a sequence of pairs", pairShape("!!pairs [a: 1, b: 2]"), listOf(listOf("a", "1"), listOf("b", "2")))
    }

    // ===================================================== E. bounds
    run {
        // Root at depth 0, then a/b/c mappings at 1/2/3 and the leaf at 4.
        check(
            "E1 the fourth nested level fits maxDepth 4",
            tree("a:\n  b:\n    c:\n      d: 1\n", YamlTreeLimits(maxDepth = 4)).nodeCount,
            5,
        )
        check(
            "E2 the same leaf is TooDeep at maxDepth 3",
            failure("a:\n  b:\n    c:\n      d: 1\n", YamlTreeLimits(maxDepth = 3)),
            YamlTreeFailure.TooDeep,
        )
        check(
            "E3 63 nested mappings fit the default depth",
            tree(nestedMappings(63)).nodeCount,
            65,
        )
        check(
            "E4 the 64th nested mapping's leaf is TooDeep",
            failure(nestedMappings(64)),
            YamlTreeFailure.TooDeep,
        )
        check(
            "E5 two nodes fit maxNodes 2",
            tree("a: 1", YamlTreeLimits(maxNodes = 2)).nodeCount,
            2,
        )
        check(
            "E6 a third node is TooManyNodes",
            failure("a: 1\nb: 2", YamlTreeLimits(maxNodes = 2)),
            YamlTreeFailure.TooManyNodes,
        )
        check(
            "E7 an alias bomb is refused by the collection-alias cap",
            failure(
                "base: &b [1, 2]\ncopy: [*b, *b, *b, *b, *b, *b]\n",
                YamlTreeLimits(maxAliasesForCollections = 5),
            ),
            YamlTreeFailure.Malformed,
        )
        check(
            "E8 a self-referential alias is refused, not followed",
            failure("a: &a\n  b: *a\n"),
            YamlTreeFailure.Unsupported,
        )
    }

    // ===================================================== F. malformed documents
    run {
        check("F1 an unclosed flow sequence", failure("a: [1, 2"), YamlTreeFailure.Malformed)
        check("F2 a tab used for indentation", failure("a:\n\tb: 1\n"), YamlTreeFailure.Malformed)
        check("F3 a duplicate key", failure("a: 1\na: 2\n"), YamlTreeFailure.Malformed)
        check("F4 an undefined alias", failure("a: *missing\n"), YamlTreeFailure.Malformed)
        check("F5 a second document", failure("---\na: 1\n---\nb: 2\n"), YamlTreeFailure.Malformed)
        check("F6 two colons on one line", failure("a: b: c\n"), YamlTreeFailure.Malformed)
        check("F7 an unclosed quoted scalar", failure("a: \"unterminated\n"), YamlTreeFailure.Malformed)
    }

    // ===================================================== G. paths
    run {
        check("G1 a simple key", PiYamlTree.childPath("$", "server"), "$.server")
        check("G2 a dotted key is bracketed", PiYamlTree.childPath("$", "a.b"), "$['a.b']")
        check("G3 an empty key", PiYamlTree.childPath("$", ""), "$['']")
        check("G4 a single quote is escaped", PiYamlTree.childPath("$", "a'b"), "$['a\\'b']")
        check("G5 a backslash is escaped", PiYamlTree.childPath("$", "a\\b"), "$['a\\\\b']")
        check("G6 a sequence index", PiYamlTree.itemPath("$.items", 2), "$.items[2]")
        check("G7 a nested simple path", PiYamlTree.childPath("$.a", "b"), "$.a.b")
        check("G8 a unicode key is a simple identifier", PiYamlTree.childPath("$", "名字"), "$.名字")
        check(
            "G9 a dotted key keeps its text",
            (entries(tree("\"a.b\": 1\n").root).keys.first()),
            "a.b",
        )
        check(
            "G10 the value node's path is the bracketed one",
            (entries(tree("\"a.b\": 1\n").root).values.first()).path,
            "$['a.b']",
        )
    }

    // ===================================================== H. roots and the model
    run {
        check("H1 a scalar document", scalar("hello").let { it.text to it.depth }, "hello" to 0)
        check("H2 an explicit null document", scalar("null").kind, YamlScalarKind.Null)
        check("H3 a tilde null document", scalar("~").kind, YamlScalarKind.Null)
        check("H4 a number document", scalar("42").kind, YamlScalarKind.Number)
        check("H5 a boolean document", scalar("true").kind, YamlScalarKind.Boolean)
        check(
            "H6 a sequence document",
            (tree("- a\n- b\n").root as YamlListNode).items.map { (it.value as YamlScalarNode).text },
            listOf("a", "b"),
        )
        check("H7 a sequence document's index path", tree("- a\n- b\n").root.let { (it as YamlListNode).items[1].value.path }, "$[1]")
        check(
            "H8 a collection key is unsupported",
            failure("? [a, b]\n: 1\n"),
            YamlTreeFailure.Unsupported,
        )
        check("H9 a root mapping counts itself", tree("a: 1\nb: 2\n").nodeCount, 3)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}

/** Helper for D10: a root sequence of scalar texts. */
private fun scalarOrList(source: String): List<String> {
    val root = tree(source).root
    if (root !is YamlListNode) error("expected a sequence, got $root")
    return root.items.map { (it.value as YamlScalarNode).text }
}

/** Helper for D11: `!!pairs` is a sequence of two-element sequences. */
private fun pairShape(source: String): List<List<String>> {
    val root = tree(source).root
    if (root !is YamlListNode) error("expected a sequence, got $root")
    return root.items.map { item ->
        val pair = item.value
        if (pair !is YamlListNode) error("expected a pair sequence, got $pair")
        pair.items.map { (it.value as YamlScalarNode).text }
    }
}
