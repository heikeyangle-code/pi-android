package app.pi.ui.render.rich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The **pure model** behind the JSON tree (`§七.1` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose component lives in `PiJsonTreeView.kt`. The split is not cosmetic:
 * `tools/run-app-pure-checks.sh` compiles harnesses with `kotlinc` and the Kotlin
 * stdlib + kotlinx.serialization only — no `android.jar`, no Compose jar — so a
 * file that imports Compose cannot be compiled (or therefore tested) there. All
 * parsing, path building, limits and the node vocabulary are in this file; the
 * view only draws [JsonTreeNode]s and never re-decides any of these rules.
 *
 * ## Safety
 *
 * The body is parsed with the app's existing `kotlinx.serialization.json`
 * dependency (`§七.1` explicitly says not to add another JSON parser). Four bounds
 * stand between a fence and the UI:
 *
 *  1. **Characters** — [JsonTreeLimits.maxChars] (the plan's 256 KiB). Checked on
 *     the raw source *before* the parser is called.
 *  2. **Depth** — a linear pre-scan counts `{`/`[` outside string literals and
 *     rejects anything nested deeper than [JsonTreeLimits.maxDepth] (64). This is
 *     what stops `[[[[…` from reaching the recursive-descent parser / converter at
 *     all, so a hostile depth cannot become a `StackOverflowError`.
 *  3. **Nodes** — the converter refuses to build more than
 *     [JsonTreeLimits.maxNodes] (20,000) nodes. It does **not** truncate: a partial
 *     tree would be a quietly false document, so the result is a failure and the
 *     caller keeps the source view.
 *  4. **Malformed input** — any parser exception (and a defensive
 *     `StackOverflowError` catch) becomes [JsonTreeFailure.Malformed]. Nothing is
 *     thrown to the UI.
 *
 * ## Not a content sniffer
 *
 * [parse] assumes the caller already decided the fence is `json`/`jsonc` (see
 * [RichBlock.classify]); it is not asked to guess whether ordinary prose "looks
 * like JSON".
 */

/**
 * The bounds for one JSON tree.
 *
 * Defaults are the plan's `§十五` numbers: 256 KiB source, depth 64, 20,000 nodes.
 * They are parameters rather than constants so the tests can drive the failure
 * paths with tiny values instead of building a 20,000-node document.
 */
data class JsonTreeLimits(
    /** Maximum source length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum container nesting: 64 allows 64 nested objects/arrays; a leaf may sit at depth 64. */
    val maxDepth: Int = 64,
    /** Maximum number of [JsonTreeNode]s, root included. */
    val maxNodes: Int = 20_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
    }
}

/** The JSON scalar flavour, i.e. the colour the view gives a leaf. */
enum class JsonScalarKind {
    String,
    Number,
    Boolean,

    /** JSON's literal `null`, kept distinct from the *string* `"null"`. */
    Null,
}

/**
 * One node of the parsed tree.
 *
 * Every node carries its own **path** (`$`, `$.user.name`, `$.items[2]`,
 * `$['a.b']`) computed once while the tree is built, so "复制路径" never re-walks
 * the tree and two callers cannot format the same path differently.
 */
sealed interface JsonTreeNode {
    /** JSONPath-like location of this node, rooted at `$`. */
    val path: String

    /** 0 for the root, 1 for its immediate children, … */
    val depth: Int
}

/** An object: ordered `key → node` entries, as the document wrote them. */
data class JsonObjectNode(
    override val path: String,
    override val depth: Int,
    val entries: List<JsonEntry>,
) : JsonTreeNode

/** An array: index → node, in document order. */
data class JsonArrayNode(
    override val path: String,
    override val depth: Int,
    val items: List<JsonItem>,
) : JsonTreeNode

/**
 * A leaf.
 *
 * [text] is the human-facing value: a string without its quotes, a number's
 * literal, `true`/`false`, or `null`. [literal] is the value's JSON token —
 * `"quoted"` for a string, the bare literal otherwise — which is what "复制值"
 * puts on the clipboard so it can be pasted back into JSON.
 */
data class JsonScalarNode(
    override val path: String,
    override val depth: Int,
    val kind: JsonScalarKind,
    val text: String,
    val literal: String,
) : JsonTreeNode

/** One object member: its key and the value node (whose [JsonTreeNode.path] is `parent.key`). */
data class JsonEntry(val key: String, val value: JsonTreeNode)

/** One array element: its index and the value node. */
data class JsonItem(val index: Int, val value: JsonTreeNode)

/** How many direct children a node has; `0` for a scalar. */
val JsonTreeNode.childCount: Int
    get() = when (this) {
        is JsonObjectNode -> entries.size
        is JsonArrayNode -> items.size
        is JsonScalarNode -> 0
    }

/** True for objects and arrays, i.e. the nodes that fold. */
val JsonTreeNode.isContainer: Boolean get() = this !is JsonScalarNode

/** Why [PiJsonTree.parse] returned a failure instead of a tree. */
enum class JsonTreeFailure {
    /** The producer said the fence is still streaming; there is no stable document yet. */
    NotSettled,

    /** The body is empty or only whitespace. */
    Empty,

    /** Longer than [JsonTreeLimits.maxChars]. */
    TooLarge,

    /** Nested deeper than [JsonTreeLimits.maxDepth]. */
    TooDeep,

    /** More than [JsonTreeLimits.maxNodes] nodes. */
    TooManyNodes,

    /** The parser rejected the document (or it is not JSON at all, e.g. JSONC comments). */
    Malformed,
}

/** The outcome of [PiJsonTree.parse]: a bounded tree, or a stated failure. */
sealed interface JsonTreeResult {
    /** A complete tree within every bound. */
    data class Tree(val root: JsonTreeNode, val nodeCount: Int) : JsonTreeResult

    /** No tree; the caller should keep showing the source. */
    data class Failure(val reason: JsonTreeFailure) : JsonTreeResult
}

/**
 * Parses an explicitly-tagged `json` / `jsonc` fence into [JsonTreeNode]s.
 *
 * The parser is strict because the tree's whole value is fidelity: a view that
 * quietly accepts a broken document would show a structure the model did not
 * write. `isLenient = false` plus no trailing commas covers the structural side,
 * but **`kotlinx.serialization.json`'s `parseToJsonElement` is not a strict JSON
 * *token* validator**: it accepts `hello`, `01`, `+1`, `.5`, `1.`, `1.2.3`,
 * `truex`, `NaN`, `Infinity`, and (verified on a bare JVM against 1.9.0) a raw
 * newline inside a string. So this object adds two checks of its own:
 *
 *  - every non-string primitive must be a real JSON number or `true`/`false`
 *    ([isStrictJsonNumber] and [scalar]);
 *  - [scanSource] rejects raw control characters inside strings.
 *
 * `jsonc` is recognized as a *language* by [RichBlock], but
 * `kotlinx.serialization.json` does not read comments, so a commented document
 * fails [JsonTreeFailure.Malformed] and the raw source stays visible. That is the
 * planned fallback, not a regression: Phase 1 does not add a JSONC parser.
 *
 * Never throws for bad input.
 */
object PiJsonTree {
    /** `JsonElement` parser shared by every call; configuration is immutable. */
    private val parser = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    private val ROOT_PATH = "$"

    /**
     * @param source the fence body, verbatim.
     * @param settled `false` while the model is still writing the fence
     *   (`§十四.4`: "JSON 不完整时不切换为树状视图").
     * @param limits the four bounds; see [JsonTreeLimits].
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: JsonTreeLimits = JsonTreeLimits(),
    ): JsonTreeResult {
        if (!settled) return JsonTreeResult.Failure(JsonTreeFailure.NotSettled)
        if (source.isBlank()) return JsonTreeResult.Failure(JsonTreeFailure.Empty)
        if (source.length > limits.maxChars) return JsonTreeResult.Failure(JsonTreeFailure.TooLarge)

        // One linear pass before the parser: it bounds recursion, and it rejects
        // the one malformed shape `Json.parseToJsonElement` accepts silently (a raw
        // control character inside a string, e.g. a literal newline).
        val scan = scanSource(source)
        if (scan.hasRawControlCharacter) return JsonTreeResult.Failure(JsonTreeFailure.Malformed)
        if (scan.maxDepth > limits.maxDepth) {
            return JsonTreeResult.Failure(JsonTreeFailure.TooDeep)
        }

        val element: JsonElement = try {
            parser.parseToJsonElement(source)
        } catch (_: StackOverflowError) {
            // Defense in depth: the pre-scan should have caught this. A library
            // stack overflow must still not escape into the UI thread.
            return JsonTreeResult.Failure(JsonTreeFailure.TooDeep)
        } catch (_: Exception) {
            return JsonTreeResult.Failure(JsonTreeFailure.Malformed)
        }

        val counter = intArrayOf(0)
        val root = try {
            convert(element, ROOT_PATH, depth = 0, limits = limits, counter = counter)
        } catch (abort: JsonAbort) {
            return JsonTreeResult.Failure(abort.reason)
        }
        return JsonTreeResult.Tree(root = root, nodeCount = counter[0])
    }

    /**
     * The path for an object member, JSONPath-style.
     *
     * `$` + `.key` for a simple identifier; `$['key with punctuation']` otherwise.
     * A quote/backslash inside the bracket form is escaped with a backslash. This
     * must round-trip for any key the model can write, including keys containing
     * dots, brackets and quotes.
     */
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

    /** The path for an array element: `$[0]`, `$.items[2]`. */
    fun itemPath(parentPath: String, index: Int): String = "$parentPath[$index]"

    /**
     * One linear pass over the source, before parsing.
     *
     * It answers two questions no later stage can answer cheaply:
     *
     *  - **How deep can the parser recurse?** `{`/`[` outside string literals are
     *    counted, so a hostile `[[[[…` never reaches `parseToJsonElement`, and a
     *    brace inside a string is not mistaken for nesting.
     *  - **Does a string contain a raw control character?** JSON forbids U+0000–
     *    U+001F unescaped, and `kotlinx.serialization.json` accepts a literal
     *    newline inside a string (`{"a":"x<newline>y"}` parses). A tree that
     *    accepted it would display a document the model did not write, so this
     *    pass reports it and [parse] returns [JsonTreeFailure.Malformed].
     *
     * Escaped characters (`\n`, `\"`, `\u0000`) are fine: the scan tracks the
     * backslash so an escaped quote never ends the string.
     */
    private fun scanSource(source: String): SourceScan {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        var rawControl = false
        for (ch in source) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                    ch < ' ' -> rawControl = true
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > max) max = depth
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return SourceScan(maxDepth = max, hasRawControlCharacter = rawControl)
    }

    private class SourceScan(val maxDepth: Int, val hasRawControlCharacter: Boolean)

    private fun convert(
        element: JsonElement,
        path: String,
        depth: Int,
        limits: JsonTreeLimits,
        counter: IntArray,
    ): JsonTreeNode {
        if (depth > limits.maxDepth) throw JsonAbort(JsonTreeFailure.TooDeep)
        counter[0]++
        if (counter[0] > limits.maxNodes) throw JsonAbort(JsonTreeFailure.TooManyNodes)

        return when (element) {
            is JsonObject -> {
                val entries = ArrayList<JsonEntry>(element.size)
                for ((key, value) in element) {
                    entries += JsonEntry(
                        key = key,
                        value = convert(value, childPath(path, key), depth + 1, limits, counter),
                    )
                }
                JsonObjectNode(path = path, depth = depth, entries = entries)
            }

            is JsonArray -> {
                val items = ArrayList<JsonItem>(element.size)
                for ((index, value) in element.withIndex()) {
                    items += JsonItem(
                        index = index,
                        value = convert(value, itemPath(path, index), depth + 1, limits, counter),
                    )
                }
                JsonArrayNode(path = path, depth = depth, items = items)
            }

            is JsonNull -> JsonScalarNode(
                path = path,
                depth = depth,
                kind = JsonScalarKind.Null,
                text = "null",
                literal = "null",
            )

            is JsonPrimitive -> scalar(path, depth, element)
        }
    }

    private fun scalar(path: String, depth: Int, primitive: JsonPrimitive): JsonScalarNode {
        if (primitive.isString) {
            return JsonScalarNode(
                path = path,
                depth = depth,
                kind = JsonScalarKind.String,
                text = primitive.content,
                literal = primitive.toString(),
            )
        }
        val content = primitive.content
        if (content == "true" || content == "false") {
            return JsonScalarNode(
                path = path,
                depth = depth,
                kind = JsonScalarKind.Boolean,
                text = content,
                literal = content,
            )
        }
        // `Json.parseToJsonElement` is not a strict JSON *token* validator: it
        // accepts `hello`, `'x'`, `01`, `+1`, `.5`, `1.`, `1.2.3`, `truex`, `NaN`
        // and `Infinity` as bare literals (verified against kotlinx-serialization
        // 1.9.0 on a bare JVM). Those are not JSON. The model is the thing that
        // promises fidelity, so a primitive that is neither a boolean nor a
        // well-formed JSON number is rejected here rather than drawn as a number.
        if (!isStrictJsonNumber(content)) throw JsonAbort(JsonTreeFailure.Malformed)
        return JsonScalarNode(
            path = path,
            depth = depth,
            kind = JsonScalarKind.Number,
            text = content,
            literal = content,
        )
    }

    /**
     * `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`, handwritten so the check
     * is allocation-free and uses ASCII digits only (JSON's grammar, not Unicode
     * `isDigit`).
     */
    private fun isStrictJsonNumber(text: String): Boolean {
        var i = 0
        val n = text.length
        if (i < n && text[i] == '-') i++
        if (i >= n) return false
        if (text[i] == '0') {
            i++
        } else {
            if (text[i] !in '1'..'9') return false
            i++
            while (i < n && text[i] in '0'..'9') i++
        }
        if (i < n && text[i] == '.') {
            i++
            if (i >= n || text[i] !in '0'..'9') return false
            while (i < n && text[i] in '0'..'9') i++
        }
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < n && (text[i] == '+' || text[i] == '-')) i++
            if (i >= n || text[i] !in '0'..'9') return false
            while (i < n && text[i] in '0'..'9') i++
        }
        return i == n
    }

    /** Internal abort: converted to [JsonTreeResult.Failure] by [parse], never escapes. */
    private class JsonAbort(val reason: JsonTreeFailure) : RuntimeException()
}
