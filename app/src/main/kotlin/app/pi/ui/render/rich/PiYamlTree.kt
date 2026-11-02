package app.pi.ui.render.rich

import java.util.Base64
import java.util.Date
import java.util.IdentityHashMap
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.inspector.TagInspector
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/**
 * The **pure model and safe parser** behind the YAML tree (`§七.3` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose component is `PiYamlTreeView.kt`; this file only parses and builds
 * [YamlTreeNode]s. The split is the same one `PiJsonTree.kt` explains: the bare-JVM
 * harnesses (`tools/run-app-pure-checks.sh`) compile `app/src/main/kotlin` with
 * `kotlinc` and no `android.jar`, so anything that imports Compose cannot be tested
 * there. SnakeYAML is a plain JVM dependency and *is* available to those harnesses
 * once the runner stages its jar (see the file header of
 * `tools/run-app-pure-checks.sh`).
 *
 * ## Safety (`§七.3`「必须使用安全加载方式，禁止任意类型实例化和危险自定义标签」)
 *
 * YAML's danger is not its syntax, it is its **tags and aliases**:
 *
 *  - a tag such as `!!java/object:…` (or a local `!ruby/object:…`) can, with an
 *    unsafe `Constructor`, instantiate an arbitrary class or call a setter;
 *  - an anchor/alias cycle (`&a {b: *a}`) can turn the document into a graph with
 *    cycles, and a "billion laughs" chain of aliases can expand exponentially;
 *  - indentation that nests thousands of collections can exhaust the stack.
 *
 * This build answers each with an explicit layer, not with trust:
 *
 *  1. **`SafeConstructor` only.** It is the only constructor this file ever
 *     creates. [Yaml.load] is called with the document as a `String`; `loadAs` /
 *     `Constructor` / `BeanConstructor` — the APIs that name a target type — are
 *     never used, so the document cannot choose a class to instantiate. Even a
 *     standard tag only produces the plain JDK values `SafeConstructor` registers
 *     (`Map`, `List`, `Set`, `String`, numbers, `Boolean`, `null`, `Date`,
 *     `byte[]`).
 *  2. **A rejecting `TagInspector`.** `LoaderOptions.setTagInspector` is set to an
 *     inspector that refuses global tags. Unknown local tags are refused by
 *     `SafeConstructor` itself ("could not determine a constructor for the tag"),
 *     and the inspector closes the global-tag path even if a future SnakeYAML
 *     version changed that default. Note that this does *not* disable the standard
 *     `!!str` / `!!int` / `!!set` / `!!binary` / `!!timestamp` tags — those resolve
 *     through `SafeConstructor`'s fixed table, which is exactly the point.
 *  3. **`maxAliasesForCollections`.** Aliases are legal YAML and are expected in
 *     real configuration files, so they are not banned; they are bounded by
 *     [YamlTreeLimits.maxAliasesForCollections] (SnakeYAML's counter for non-scalar
 *     aliases). A billion-laughs document therefore fails as
 *     [YamlTreeFailure.Malformed] instead of expanding.
 *  4. **Parser depth and size.** `LoaderOptions` also gets
 *     `nestingDepthLimit = maxDepth + 2` (so the conversion's own depth check can
 *     report [YamlTreeFailure.TooDeep] for the first violating level) and
 *     `codePointLimit = maxChars`.
 *  5. **Our own bounds.** [YamlTreeLimits.maxChars] is checked on the raw source
 *     before SnakeYAML is constructed; the converter then counts every node
 *     ([YamlTreeLimits.maxNodes]) and every level ([YamlTreeLimits.maxDepth]).
 *     Exceeding a bound is a stated failure. Nothing is truncated: a partial tree
 *     would be a quietly false document, so the caller keeps the source view.
 *  6. **No exception reaches the UI.** Parser errors, conversion aborts and a
 *     defensive `StackOverflowError` all become [YamlTreeResult.Failure].
 *
 * Duplicate mapping keys are refused (`allowDuplicateKeys = false`): a tree drawn
 * from the two-key document would silently show only one of the values, and YAML
 * mappings are specified to have unique keys. Recursive keys are refused too.
 *
 * ## What the tree can represent
 *
 * [YamlMapNode], [YamlListNode] and [YamlScalarNode] cover the `Map` / `List` /
 * `Scalar` / `Null` vocabulary `§七.3` names. Standard tags that resolve to other
 * safe JDK values are projected, not dropped:
 *
 *  - `!!timestamp` → a string leaf (ISO-8601 instant);
 *  - `!!binary` → a string leaf (Base64);
 *  - `!!set` → a list leaf of its members, as SnakeYAML yields a `Set`;
 *  - `!!pairs` → a list of two-element lists, as SnakeYAML yields `Object[]` pairs.
 *
 * A mapping whose **key** is a collection (`? [a, b]`) has no `String` label in
 * this model; it is refused as [YamlTreeFailure.Unsupported] rather than rendered
 * with a fabricated key. The source view still shows it.
 *
 * ## Paths
 *
 * [YamlTreeNode.path] is JSONPath-like, computed while the tree is built:
 * `$`, `$.server.port`, `$['a.b']`, `$.items[2]`. It is a copy/display aid, not a
 * formal YAMLPath; a document that has both `1:` and `"1":` as keys would produce
 * the same text for both (YAML itself keeps them distinct). That is a documented
 * limitation of the path string, not of the tree.
 *
 * [PiYamlTree] is **not** a content sniffer: [parse] assumes the caller already
 * classified the fence as `yaml`/`yml` (see [RichBlock.classify]).
 */

/**
 * The bounds for one YAML tree.
 *
 * Defaults are the plan's `§十五` numbers: 256 KiB source, depth 64, 20,000 nodes.
 * [maxAliasesForCollections] has no plan number; it is SnakeYAML's own default
 * (50) and exists so the alias limit is a parameter the tests can drive, not a
 * library default that could change under us.
 */
data class YamlTreeLimits(
    /** Maximum source length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum nesting: 64 allows 64 nested collections below the root. */
    val maxDepth: Int = 64,
    /** Maximum number of [YamlTreeNode]s, root included. */
    val maxNodes: Int = 20_000,
    /** Maximum non-scalar aliases, passed straight to SnakeYAML's loader. */
    val maxAliasesForCollections: Int = 50,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxAliasesForCollections > 0) { "maxAliasesForCollections must be positive" }
    }
}

/** The scalar flavour a YAML leaf carries. */
enum class YamlScalarKind {
    String,
    Number,
    Boolean,

    /** YAML's `null` / `~` / empty value. */
    Null,
}

/**
 * One node of the parsed tree. Untagged: YAML tags are deliberately not modelled,
 * because the only safe policy for a non-standard tag is "refuse", which the
 * parser does before a node is built.
 */
sealed interface YamlTreeNode {
    /** JSONPath-like location, rooted at `$`. */
    val path: String

    /** 0 for the root, 1 for its immediate children, … */
    val depth: Int
}

/** A mapping: ordered `key → node` entries, as the document wrote them. */
data class YamlMapNode(
    override val path: String,
    override val depth: Int,
    val entries: List<YamlEntry>,
) : YamlTreeNode

/** A sequence: index → node, in document order. */
data class YamlListNode(
    override val path: String,
    override val depth: Int,
    val items: List<YamlItem>,
) : YamlTreeNode

/**
 * A scalar leaf.
 *
 * [text] is the resolved value — SnakeYAML's safe types rendered as text (an
 * `!!timestamp` as an ISO instant, an `!!binary` as Base64, a number's `toString`,
 * `true`/`false`, `null`). The original lexical spelling (`yes`, `0x10`, a quoted
 * string) is **not** kept here; the source view is the place that preserves it.
 */
data class YamlScalarNode(
    override val path: String,
    override val depth: Int,
    val kind: YamlScalarKind,
    val text: String,
) : YamlTreeNode

/** One mapping member: its display key and the value node (whose path is `parent.key`). */
data class YamlEntry(val key: String, val value: YamlTreeNode)

/** One sequence element: its index and the value node. */
data class YamlItem(val index: Int, val value: YamlTreeNode)

/** How many direct children a node has; `0` for a scalar. */
val YamlTreeNode.childCount: Int
    get() = when (this) {
        is YamlMapNode -> entries.size
        is YamlListNode -> items.size
        is YamlScalarNode -> 0
    }

/** True for mappings and sequences, i.e. the nodes that fold. */
val YamlTreeNode.isContainer: Boolean get() = this !is YamlScalarNode

/** Why [PiYamlTree.parse] returned a failure instead of a tree. */
enum class YamlTreeFailure {
    /** The producer said the fence is still streaming. */
    NotSettled,

    /** Empty or whitespace-only source. */
    Empty,

    /** Longer than [YamlTreeLimits.maxChars]. */
    TooLarge,

    /** Nested deeper than [YamlTreeLimits.maxDepth]. */
    TooDeep,

    /** More than [YamlTreeLimits.maxNodes] nodes. */
    TooManyNodes,

    /**
     * A document construct this tree cannot represent honestly: a mapping key that
     * is itself a collection, or an alias cycle (`&a {b: *a}`) that would make the
     * "tree" a graph. The source view still has it.
     */
    Unsupported,

    /**
     * SnakeYAML refused the document: malformed YAML, a dangerous/unknown tag, a
     * duplicate key, an undefined alias, too many collection aliases, a second
     * document, or the parser's own depth/codepoint limit.
     */
    Malformed,
}

/** The outcome of [PiYamlTree.parse]: a bounded tree, or a stated failure. */
sealed interface YamlTreeResult {
    /** A complete tree within every bound. */
    data class Tree(val root: YamlTreeNode, val nodeCount: Int) : YamlTreeResult

    /** No tree; the caller should keep showing the source. */
    data class Failure(val reason: YamlTreeFailure) : YamlTreeResult
}

/**
 * Parses an explicitly-tagged `yaml` / `yml` fence into [YamlTreeNode]s with
 * SnakeYAML's `SafeConstructor` only.
 *
 * Never throws for bad or hostile input; every rejection is a
 * [YamlTreeResult.Failure]. A new [Yaml] instance is built per call because
 * `LoaderOptions` and `Yaml` are not documented as thread-safe and the caller may
 * parse on any thread; the object itself holds no mutable state.
 */
object PiYamlTree {
    /**
     * `true`: this build has a safe YAML parser. The value is part of the public
     * contract because earlier phases used it to keep the code view; callers may
     * still prefer `parse(...).let { it is YamlTreeResult.Tree }` when they need
     * the actual document's verdict.
     */
    const val isSupported: Boolean = true

    private const val ROOT_PATH = "$"

    /**
     * @param source the fence body, verbatim.
     * @param settled `false` while the model is still writing the fence
     *   (`§十四`: only a complete fence may switch to a tree).
     * @param limits the size/depth/node/alias bounds; see [YamlTreeLimits].
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: YamlTreeLimits = YamlTreeLimits(),
    ): YamlTreeResult {
        if (!settled) return YamlTreeResult.Failure(YamlTreeFailure.NotSettled)
        if (source.isBlank()) return YamlTreeResult.Failure(YamlTreeFailure.Empty)
        if (source.length > limits.maxChars) return YamlTreeResult.Failure(YamlTreeFailure.TooLarge)

        // The parser itself enforces the same character budget (in code points) and
        // a nesting cap only slightly above our own, so the conversion below is the
        // layer that reports the first over-limit level.
        //
        // `Yaml.load` is a Java generic method (`<T> T load(String)`). The explicit
        // type argument is required: with only a try-expression target type, Kotlin
        // 2.2 infers `java.lang.Void` and the returned Map fails with a
        // ClassCastException (observed while compiling this harness).
        val data: Any? = try {
            newLoader(limits).load<Any?>(source)
        } catch (_: StackOverflowError) {
            return YamlTreeResult.Failure(YamlTreeFailure.TooDeep)
        } catch (_: Exception) {
            return YamlTreeResult.Failure(YamlTreeFailure.Malformed)
        }

        val counter = intArrayOf(0)
        val root = try {
            convert(
                value = data,
                path = ROOT_PATH,
                depth = 0,
                limits = limits,
                counter = counter,
                onPath = IdentityHashMap(),
            )
        } catch (abort: YamlAbort) {
            return YamlTreeResult.Failure(abort.reason)
        } catch (_: StackOverflowError) {
            // Defense in depth: depth is bounded before this can happen, but a
            // cyclic alias must never take the UI thread down with it.
            return YamlTreeResult.Failure(YamlTreeFailure.TooDeep)
        }
        return YamlTreeResult.Tree(root = root, nodeCount = counter[0])
    }

    /**
     * The **only** loader this file builds.
     *
     * The 4-argument `Yaml` constructor is required: the 1/2/3-argument overloads
     * create their own `LoaderOptions` and would silently drop every limit set
     * here.
     */
    private fun newLoader(limits: YamlTreeLimits): Yaml {
        val options = LoaderOptions()
        options.setAllowDuplicateKeys(false)
        options.setAllowRecursiveKeys(false)
        options.setMaxAliasesForCollections(limits.maxAliasesForCollections)
        options.setNestingDepthLimit(parserNestingDepth(limits.maxDepth))
        options.setCodePointLimit(limits.maxChars)
        // Global tags (e.g. `!!java/object:…`) are never allowed; standard tags still
        // resolve through SafeConstructor's fixed table. See the file KDoc.
        options.setTagInspector(
            object : TagInspector {
                override fun isGlobalTagAllowed(tag: Tag): Boolean = false
            },
        )
        return Yaml(
            SafeConstructor(options),
            Representer(DumperOptions()),
            DumperOptions(),
            options,
        )
    }

    /**
     * How deep SnakeYAML's composer may go.
     *
     * SnakeYAML allows `n` nested collections when the limit is `> n`; +2 lets the
     * converter, not the parser, produce [YamlTreeFailure.TooDeep] for the first
     * level past [YamlTreeLimits.maxDepth] (same reason `PiJsonTree` pre-scans
     * instead of letting the parser overflow).
     */
    private fun parserNestingDepth(maxDepth: Int): Int =
        if (maxDepth >= Int.MAX_VALUE - 2) Int.MAX_VALUE else maxDepth + 2

    /** The path for a mapping key, JSONPath-style; see the file KDoc. */
    fun childPath(parentPath: String, key: String): String {
        val simple = key.isNotEmpty() &&
            (key[0].isLetter() || key[0] == '_') &&
            key.all { it.isLetterOrDigit() || it == '_' }
        if (simple) return "$parentPath.$key"
        val escaped = key
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return "$parentPath['$escaped']"
    }

    /** The path for a sequence element: `$[0]`, `$.items[2]`. */
    fun itemPath(parentPath: String, index: Int): String = "$parentPath[$index]"

    /**
     * Converts one `SafeConstructor` value into the tree.
     *
     * The bounds are applied **before** the node is materialised, so a hostile
     * document cannot allocate the tree it is not allowed to show. Aliases arrive
     * here as shared JDK objects and are converted independently; a cyclic alias is
     * refused by [withCycleGuard] as [YamlTreeFailure.Unsupported] rather than
     * followed forever (the depth bound would also stop it, but "too deep" would be
     * the wrong reason for a cycle).
     */
    private fun convert(
        value: Any?,
        path: String,
        depth: Int,
        limits: YamlTreeLimits,
        counter: IntArray,
        onPath: IdentityHashMap<Any, Boolean>,
    ): YamlTreeNode {
        if (depth > limits.maxDepth) throw YamlAbort(YamlTreeFailure.TooDeep)
        counter[0]++
        if (counter[0] > limits.maxNodes) throw YamlAbort(YamlTreeFailure.TooManyNodes)

        return when (value) {
            null -> YamlScalarNode(path, depth, YamlScalarKind.Null, "null")

            is Map<*, *> -> withCycleGuard(value, onPath) {
                val entries = ArrayList<YamlEntry>(value.size)
                for ((key, child) in value) {
                    val keyText = keyText(key) ?: throw YamlAbort(YamlTreeFailure.Unsupported)
                    entries += YamlEntry(
                        key = keyText,
                        value = convert(
                            child,
                            childPath(path, keyText),
                            depth + 1,
                            limits,
                            counter,
                            onPath,
                        ),
                    )
                }
                YamlMapNode(path = path, depth = depth, entries = entries)
            }

            is Collection<*> -> withCycleGuard(value, onPath) {
                val items = ArrayList<YamlItem>(value.size)
                var index = 0
                for (child in value) {
                    items += YamlItem(
                        index = index,
                        value = convert(
                            child,
                            itemPath(path, index),
                            depth + 1,
                            limits,
                            counter,
                            onPath,
                        ),
                    )
                    index++
                }
                YamlListNode(path = path, depth = depth, items = items)
            }

            // `!!pairs` is the one standard tag whose value is an array of pairs.
            is Array<*> -> withCycleGuard(value, onPath) {
                val items = ArrayList<YamlItem>(value.size)
                value.forEachIndexed { index, child ->
                    items += YamlItem(
                        index = index,
                        value = convert(
                            child,
                            itemPath(path, index),
                            depth + 1,
                            limits,
                            counter,
                            onPath,
                        ),
                    )
                }
                YamlListNode(path = path, depth = depth, items = items)
            }

            // `!!binary` is a scalar by YAML's own model; keep its content (Base64)
            // rather than a byte-array identity string.
            is ByteArray -> YamlScalarNode(
                path = path,
                depth = depth,
                kind = YamlScalarKind.String,
                text = Base64.getEncoder().encodeToString(value),
            )

            // `!!timestamp` resolves to java.util.Date. An ISO instant is stable and
            // copyable; the original offset/format stays in the source view.
            is Date -> YamlScalarNode(
                path = path,
                depth = depth,
                kind = YamlScalarKind.String,
                text = value.toInstant().toString(),
            )

            is Boolean -> YamlScalarNode(path, depth, YamlScalarKind.Boolean, value.toString())
            is Number -> YamlScalarNode(path, depth, YamlScalarKind.Number, value.toString())
            is String -> YamlScalarNode(path, depth, YamlScalarKind.String, value)

            // SafeConstructor's vocabulary is closed (see the file KDoc). This branch
            // keeps the model total without ever instantiating a document-named type.
            else -> YamlScalarNode(path, depth, YamlScalarKind.String, value.toString())
        }
    }

    /**
     * Runs one container's conversion under the cycle guard.
     *
     * Aliases are shared JDK objects, so a document can make a container its own
     * descendant (`a: &a {b: *a}`). The tree is a *tree*, not a graph; visiting an
     * ancestor again is refused as [YamlTreeFailure.Unsupported] instead of being
     * followed until the depth bound turns a cycle into a misleading "too deep".
     * The identity map holds only the current path, so a non-cyclic repeated alias
     * still converts normally (as two independent subtrees).
     */
    private fun withCycleGuard(
        value: Any,
        onPath: IdentityHashMap<Any, Boolean>,
        build: () -> YamlTreeNode,
    ): YamlTreeNode {
        if (onPath.put(value, true) != null) throw YamlAbort(YamlTreeFailure.Unsupported)
        try {
            return build()
        } finally {
            onPath.remove(value)
        }
    }

    /**
     * The display label of a mapping key, or `null` for a key this model cannot
     * represent (a collection key, i.e. `? [a, b]`).
     */
    private fun keyText(key: Any?): String? = when (key) {
        null -> "null"
        is String -> key
        is Boolean, is Number -> key.toString()
        is Date -> key.toInstant().toString()
        is ByteArray -> Base64.getEncoder().encodeToString(key)
        else -> null
    }

    /** Internal abort: converted to [YamlTreeResult.Failure] by [parse], never escapes. */
    private class YamlAbort(val reason: YamlTreeFailure) : RuntimeException()
}
