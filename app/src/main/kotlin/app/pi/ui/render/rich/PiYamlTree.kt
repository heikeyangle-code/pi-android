package app.pi.ui.render.rich

/**
 * The **model and placeholder** for the YAML tree (`§七.3` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * ## Why there is no YAML tree in Phase 1
 *
 * The task's own rule is "YAML 若没有安全解析器就先只做模型/占位并明确报告，不要引入
 * SnakeYAML 等新依赖". This build has no YAML parser at all: `gradle/libs.versions.toml`
 * declares no YAML library, and adding one would be a new third-party dependency with
 * a licence review, an APK-size cost and a fresh attack surface — which the plan
 * forbids in Phase 1 ("这一阶段尽量不引入新重型依赖", `§十六`).
 *
 * Hand-writing a YAML parser is not an acceptable substitute:
 *
 *  - YAML is not a small grammar. Anchors/aliases (`&a` / `*a`) allow unbounded
 *    expansion (the "billion laughs" shape), and merge keys plus flow syntax make a
 *    naive line parser silently wrong on real documents — a wrong tree is worse
 *    than no tree.
 *  - YAML tags (`!!java/object:…`, `!ruby/object:…`) are the format's own
 *    deserialization vector. A parser that does not implement tags must still
 *    understand them well enough to **refuse** them, which is parser work, not
 *    string work.
 *
 * So [parse] performs only the checks that do not require a parser (settled, empty,
 * size) and then returns [YamlTreeFailure.Unsupported]. The caller keeps the code
 * view. [YamlTreeNode] exists so that a later phase can plug a reviewed parser into
 * the same shape without changing the UI contract.
 *
 * ## What is deliberately *not* claimed
 *
 * [isSupported] is `false`. The renderer must key its fallback on that, not on the
 * presence of [PiYamlTree] — a recognized language with no parser is exactly the
 * case where a "helpful" empty tree would be a lie.
 */

/** The bounds a future YAML parser would have to honour; the plan's `§十五` numbers. */
data class YamlTreeLimits(
    val maxChars: Int = 256 * 1024,
    val maxDepth: Int = 64,
    val maxNodes: Int = 20_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
    }
}

/** The scalar flavour a YAML leaf would carry. */
enum class YamlScalarKind {
    String,
    Number,
    Boolean,

    /** YAML's `null` / `~` / empty value. */
    Null,
}

/**
 * One node of the (future) YAML tree. Untagged: YAML's tags are deliberately not
 * modelled, because the only safe policy for them is "refuse", which the parser
 * does before building a node.
 */
sealed interface YamlTreeNode {
    /** JSONPath-like location, rooted at `$`; the future parser fills this in. */
    val path: String
    val depth: Int
}

data class YamlMapNode(
    override val path: String,
    override val depth: Int,
    val entries: List<YamlEntry>,
) : YamlTreeNode

data class YamlListNode(
    override val path: String,
    override val depth: Int,
    val items: List<YamlItem>,
) : YamlTreeNode

data class YamlScalarNode(
    override val path: String,
    override val depth: Int,
    val kind: YamlScalarKind,
    val text: String,
) : YamlTreeNode

data class YamlEntry(val key: String, val value: YamlTreeNode)

data class YamlItem(val index: Int, val value: YamlTreeNode)

/** How many direct children a node has; `0` for a scalar. */
val YamlTreeNode.childCount: Int
    get() = when (this) {
        is YamlMapNode -> entries.size
        is YamlListNode -> items.size
        is YamlScalarNode -> 0
    }

/** Why no YAML tree can be shown. */
enum class YamlTreeFailure {
    /** The producer said the fence is still streaming. */
    NotSettled,

    /** Empty or whitespace-only source. */
    Empty,

    /** Longer than [YamlTreeLimits.maxChars]. */
    TooLarge,

    /** Reserved for a future parser: nesting exceeded [YamlTreeLimits.maxDepth]. */
    TooDeep,

    /** Reserved for a future parser: more than [YamlTreeLimits.maxNodes] nodes. */
    TooManyNodes,

    /** This build has no safe YAML parser; see the file KDoc. */
    Unsupported,

    /** Reserved for a future parser: the document is not well-formed YAML. */
    Malformed,
}

/** The outcome of [PiYamlTree.parse]. */
sealed interface YamlTreeResult {
    data class Tree(val root: YamlTreeNode, val nodeCount: Int) : YamlTreeResult
    data class Failure(val reason: YamlTreeFailure) : YamlTreeResult
}

/**
 * The Phase-1 YAML placeholder.
 *
 * Always [YamlTreeFailure.Unsupported] for a non-empty, bounded, settled document;
 * the gates before that keep the caller's reasons accurate (a streaming fence is
 * [YamlTreeFailure.NotSettled], an oversized one is [YamlTreeFailure.TooLarge]).
 */
object PiYamlTree {
    /** No safe YAML parser is available in this build. See the file KDoc. */
    const val isSupported: Boolean = false

    /**
     * @return always a [YamlTreeResult.Failure] in Phase 1.
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: YamlTreeLimits = YamlTreeLimits(),
    ): YamlTreeResult {
        if (!settled) return YamlTreeResult.Failure(YamlTreeFailure.NotSettled)
        if (source.isBlank()) return YamlTreeResult.Failure(YamlTreeFailure.Empty)
        if (source.length > limits.maxChars) return YamlTreeResult.Failure(YamlTreeFailure.TooLarge)
        return YamlTreeResult.Failure(YamlTreeFailure.Unsupported)
    }
}
