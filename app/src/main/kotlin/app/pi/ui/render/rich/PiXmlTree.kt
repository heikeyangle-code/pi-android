package app.pi.ui.render.rich

/**
 * The **pure model** behind the XML tree (`§七.2` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose component is `PiXmlTreeView.kt`; the Android parser adapter is
 * `PiXmlTreeParser.kt`. The model is separated for the same hard reason as
 * `PiJsonTree.kt`: the bare-JVM harnesses (`tools/run-app-pure-checks.sh`) have no
 * `android.jar`, so anything that imports `org.xmlpull.v1` / `android.util.Xml`
 * cannot be compiled — or therefore tested — on this machine. Everything in this
 * file is pure Kotlin and is exercised by `PiXmlTreeCheck.kt`:
 *
 *  - [PiXmlTree.checkSource] — the size and declaration gates that run **before**
 *    any parser sees the text;
 *  - [XmlTreeBuilder] — the streaming tree builder, driven with events in the
 *    tests exactly as `PiXmlTreeParser` drives it with `XmlPullParser` events;
 *  - the node/path vocabulary the view draws.
 *
 * ## XML security (`§七.2`, `§八`, `§十五`)
 *
 * XML's dangerous feature is not parsing, it is **declarations**: a `DOCTYPE` can
 * define an `ENTITY` that reads a local file (`file:///…`) or performs an HTTP
 * request (XXE), and a "billion laughs" entity can expand to gigabytes. This
 * build's answer has three independent layers:
 *
 *  1. [PiXmlTree.checkSource] **rejects** any `<!DOCTYPE` / `<!ENTITY` before the
 *     parser is constructed. The scan understands comments, CDATA and processing
 *     instructions, so those words appearing *inside* a comment/CDATA are not
 *     declarations and are allowed. Unknown `<!…` markup is left to the parser,
 *     which reports it as malformed.
 *  2. `PiXmlTreeParser` disables `XmlPullParser.FEATURE_PROCESS_DOCDECL`; with the
 *     Android parser that means custom entities are never processed. There is no
 *     external entity resolution to disable because the feature is off and no
 *     declaration can reach it.
 *  3. The builder only stores `XmlPullParser`'s already-resolved text. It performs
 *     no entity expansion of its own, so even if a declaration slipped through,
 *     nothing here would fetch or expand anything.
 *
 * The tree is also bounded: [XmlTreeLimits.maxChars] (256 KiB, the plan's
 * `§十五`), [XmlTreeLimits.maxDepth] (64), [XmlTreeLimits.maxNodes] (20,000).
 * Exceeding any of them is a [XmlTreeFailure], never a partial tree and never an
 * exception.
 */

/** One XML attribute, in document order. Values are already entity-resolved by the parser. */
data class XmlAttribute(val name: String, val value: String)

/**
 * One element in the tree.
 *
 * [path] is an XPath-like location (`/root/item`, `/root/item[2]`) computed while
 * the tree is frozen. [text] is the element's **direct** character data, trimmed,
 * or `null` when it is empty/whitespace — indentation between child elements is
 * layout, not content, and a tree view is not an XML re-serializer. Character data
 * inside child elements belongs to those children, never to this node.
 */
data class XmlNode(
    val name: String,
    val path: String,
    val depth: Int,
    val attributes: List<XmlAttribute>,
    val children: List<XmlNode>,
    val text: String?,
)

/** How many direct child elements this node has. */
val XmlNode.childCount: Int get() = children.size

/** True when the element has neither children nor text. */
val XmlNode.isEmptyElement: Boolean get() = children.isEmpty() && text == null

/** The copyable path of one attribute, e.g. `/root/item[2]/@id`. */
fun XmlNode.attributePath(attribute: XmlAttribute): String = "$path/@${attribute.name}"

/** The bounds for one XML tree; defaults are the plan's `§十五` numbers. */
data class XmlTreeLimits(
    /** Maximum source length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum element nesting; the root element has depth 0, so 64 allows 64 levels. */
    val maxDepth: Int = 64,
    /** Maximum number of elements, root included. */
    val maxNodes: Int = 20_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
    }
}

/** Why no tree could be built. */
enum class XmlTreeFailure {
    /** The producer said the fence is still streaming. */
    NotSettled,

    /** Empty or whitespace-only source. */
    Empty,

    /** Longer than [XmlTreeLimits.maxChars]. */
    TooLarge,

    /** Nested deeper than [XmlTreeLimits.maxDepth]. */
    TooDeep,

    /** More than [XmlTreeLimits.maxNodes] elements. */
    TooManyNodes,

    /** `DOCTYPE` / `ENTITY` (or unknown `<!…` markup): never parsed, always refused. */
    Dtd,

    /** The parser or the builder rejected the document (not well-formed, mismatched tags, …). */
    Malformed,
}

/** The outcome of the XML pipeline: a bounded tree, or a stated failure. */
sealed interface XmlTreeResult {
    /** A complete tree within every bound. */
    data class Tree(val root: XmlNode, val nodeCount: Int) : XmlTreeResult

    /** No tree; the caller keeps showing the source. */
    data class Failure(val reason: XmlTreeFailure) : XmlTreeResult
}

/**
 * The pure half of the XML pipeline: source gates and the streaming builder.
 *
 * It never touches Android or a real parser; [PiXmlTreeParser] is the adapter that
 * turns `XmlPullParser` events into [XmlTreeBuilder] calls.
 */
object PiXmlTree {
    /**
     * Everything that must be decided **before** handing text to a parser.
     *
     * @return `null` when the source may be parsed, otherwise the reason it must
     *   not be. [XmlTreeFailure.Dtd] is the security gate: the source contains a
     *   `DOCTYPE` or `ENTITY` declaration.
     */
    fun checkSource(source: String, limits: XmlTreeLimits = XmlTreeLimits()): XmlTreeFailure? {
        if (source.isBlank()) return XmlTreeFailure.Empty
        if (source.length > limits.maxChars) return XmlTreeFailure.TooLarge
        if (findDangerousDeclaration(source) != null) return XmlTreeFailure.Dtd
        return null
    }

    /**
     * The declaration keyword (`"DOCTYPE"`, `"ENTITY"`) found outside comments,
     * CDATA and processing instructions, or `null`.
     *
     * Case-insensitive, because XML declaration keywords are case-sensitive but a
     * hostile document that spelled `<!doctype` is still something a browser-era
     * parser would consider; refusing it is the safe direction. A `<!DOCTYPE`
     * inside `<!-- … -->`, `<![CDATA[ … ]]>`, `<? … ?>` **or an attribute value**
     * is not a declaration and is reported as `null` — those regions are skipped,
     * not searched. Any remaining `<!…` is reported as `"DECLARATION"`: XML has no
     * other use for that prefix, so it cannot be valid markup.
     */
    fun findDangerousDeclaration(source: String): String? {
        var i = 0
        while (i < source.length) {
            val lt = source.indexOf('<', i)
            if (lt < 0) return null

            if (source.startsWith("<!--", lt)) {
                val end = source.indexOf("-->", lt + 4)
                if (end < 0) return null
                i = end + 3
                continue
            }
            if (source.startsWith("<![CDATA[", lt)) {
                val end = source.indexOf("]]>", lt + 9)
                if (end < 0) return null
                i = end + 3
                continue
            }
            if (source.startsWith("<?", lt)) {
                val end = source.indexOf("?>", lt + 2)
                if (end < 0) return null
                i = end + 2
                continue
            }
            if (source.matchesAt(lt, "<!DOCTYPE")) return "DOCTYPE"
            if (source.matchesAt(lt, "<!ENTITY")) return "ENTITY"
            if (source.startsWith("<!", lt)) return "DECLARATION"

            // An ordinary start/end tag: skip to its closing `>` so a `<` inside a
            // quoted attribute value is never mistaken for a declaration.
            var j = lt + 1
            var quote = '\u0000'
            while (j < source.length) {
                val ch = source[j]
                if (quote != '\u0000') {
                    if (ch == quote) quote = '\u0000'
                } else {
                    when (ch) {
                        '"' -> quote = '"'
                        '\'' -> quote = '\''
                        '>' -> {
                            j++
                            break
                        }
                    }
                }
                j++
            }
            i = j
        }
        return null
    }

    /** Case-insensitive `token` at [offset], without relying on a platform-specific overload. */
    private fun String.matchesAt(offset: Int, token: String): Boolean {
        if (offset < 0 || offset + token.length > length) return false
        for (index in token.indices) {
            if (!this[offset + index].equals(token[index], ignoreCase = true)) return false
        }
        return true
    }
}

/** The result of feeding one parser event; `Ok` means "keep going". */
internal enum class XmlFeed {
    Ok,
    Malformed,
    TooDeep,
    TooManyNodes,
}

/**
 * The streaming tree builder.
 *
 * The contract mirrors `XmlPullParser`'s: [startElement], [text], [endElement],
 * then [finish]. It is fed one event at a time and never buffers the source, so
 * the only memory it holds is the tree it is allowed to build. Once a limit is
 * hit the failure is latched and every later call is a no-op; the adapter may stop
 * pulling at that point.
 *
 * Well-formedness rules enforced here (the parser enforces the rest):
 *  - exactly one root element;
 *  - end tags match in order;
 *  - no non-whitespace text outside the root;
 *  - no duplicate attribute names on one element;
 *  - element names are XML-name-shaped.
 */
internal class XmlTreeBuilder(private val limits: XmlTreeLimits = XmlTreeLimits()) {
    private class MutableNode(val name: String) {
        val attributes = ArrayList<XmlAttribute>()
        val children = ArrayList<MutableNode>()
        val text = StringBuilder()
    }

    private val stack = ArrayList<MutableNode>()
    private var root: MutableNode? = null
    private var nodeCount = 0
    private var failure: XmlTreeFailure? = null

    /** How many elements have been accepted so far. */
    val builtNodes: Int get() = nodeCount

    fun startElement(name: String, attributes: List<XmlAttribute>): XmlFeed {
        failure?.let { return feedFor(it) }
        if (!isXmlName(name)) return fail(XmlTreeFailure.Malformed)
        if (stack.isEmpty() && root != null) return fail(XmlTreeFailure.Malformed)

        val depth = stack.size
        if (depth >= limits.maxDepth) return fail(XmlTreeFailure.TooDeep)

        nodeCount++
        if (nodeCount > limits.maxNodes) return fail(XmlTreeFailure.TooManyNodes)

        val seen = HashSet<String>(attributes.size)
        for (attribute in attributes) {
            if (!seen.add(attribute.name)) return fail(XmlTreeFailure.Malformed)
        }

        val node = MutableNode(name)
        node.attributes.addAll(attributes)
        val parent = stack.lastOrNull()
        if (parent == null) root = node else parent.children.add(node)
        stack.add(node)
        return XmlFeed.Ok
    }

    fun text(text: String): XmlFeed {
        failure?.let { return feedFor(it) }
        if (text.isEmpty()) return XmlFeed.Ok
        val parent = stack.lastOrNull()
        if (parent == null) {
            // Whitespace between the prolog and the root (or after it) is layout.
            return if (text.isBlank()) XmlFeed.Ok else fail(XmlTreeFailure.Malformed)
        }
        parent.text.append(text)
        return XmlFeed.Ok
    }

    fun endElement(name: String): XmlFeed {
        failure?.let { return feedFor(it) }
        val open = stack.lastOrNull() ?: return fail(XmlTreeFailure.Malformed)
        if (open.name != name) return fail(XmlTreeFailure.Malformed)
        stack.removeAt(stack.size - 1)
        return XmlFeed.Ok
    }

    fun finish(): XmlTreeResult {
        failure?.let { return XmlTreeResult.Failure(it) }
        if (stack.isNotEmpty()) return XmlTreeResult.Failure(XmlTreeFailure.Malformed)
        val documentRoot = root ?: return XmlTreeResult.Failure(XmlTreeFailure.Empty)
        val frozen = freeze(documentRoot, "/${documentRoot.name}", depth = 0)
        return XmlTreeResult.Tree(root = frozen, nodeCount = nodeCount)
    }

    private fun freeze(node: MutableNode, path: String, depth: Int): XmlNode {
        val occurrences = HashMap<String, Int>()
        val children = ArrayList<XmlNode>(node.children.size)
        for (child in node.children) {
            val count = (occurrences[child.name] ?: 0) + 1
            occurrences[child.name] = count
            // First occurrence keeps the readable path; later ones get XPath's
            // 1-based index (`item`, `item[2]`, `item[3]`).
            val segment = if (count == 1) child.name else "${child.name}[$count]"
            children += freeze(child, "$path/$segment", depth + 1)
        }
        return XmlNode(
            name = node.name,
            path = path,
            depth = depth,
            attributes = node.attributes.toList(),
            children = children,
            text = node.text.toString().trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun fail(reason: XmlTreeFailure): XmlFeed {
        if (failure == null) failure = reason
        return feedFor(reason)
    }

    private fun feedFor(reason: XmlTreeFailure): XmlFeed = when (reason) {
        XmlTreeFailure.TooDeep -> XmlFeed.TooDeep
        XmlTreeFailure.TooManyNodes -> XmlFeed.TooManyNodes
        else -> XmlFeed.Malformed
    }

    /**
     * XML 1.0 `Name` without namespace resolution: a letter/underscore/colon start,
     * then letters, digits, `_`, `-`, `.` or `:`. The parser has already validated
     * the text; this only keeps the model honest for direct builder users.
     */
    private fun isXmlName(name: String): Boolean {
        if (name.isEmpty()) return false
        val first = name[0]
        if (!(first.isLetter() || first == '_' || first == ':')) return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ':' }
    }
}
