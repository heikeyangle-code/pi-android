package app.pi.ui.render.rich.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * The **policy** for the bare-HTML subset of `§六` in
 * `docs/plans/rich-rendering-upgrade.md`.
 *
 * The document model (`PiHtmlDocument`) and the Compose renderer (`PiHtmlRenderer`)
 * live in this package but outside this file. This file has no Compose and no
 * Android imports, so `tools/run-app-pure-checks.sh` can compile and run it on a
 * bare JVM — the `PiHtmlPolicyCheck.kt` harness next to it does exactly that.
 *
 * ## jsoup is a parser, not a policy
 *
 * jsoup's own `Cleaner`/`Safelist` is deliberately **not** used. The plan says
 * jsoup "本身不等于安全策略" (`§六.3`), and a versioned third-party safelist is a
 * moving target that also hides *which* decisions this app made. jsoup is used for
 * exactly two jobs: turning bytes into a tree, and turning the sanitized tree back
 * into bytes. Every allow/deny decision below is ours.
 *
 * ## What "sanitize" returns
 *
 * [PiHtmlPolicy.sanitize] returns a **newly built** tree serialized back to HTML.
 * The input string is never sliced, filtered or reproduced verbatim, and no
 * attribute from the input is copied without passing [isAllowedAttribute] and (for
 * URLs) [sanitizeUrl]. Consequences:
 *
 *  - **Entities are decoded exactly once.** jsoup's tree builder decodes character
 *    references once while parsing; text is copied as `TextNode.wholeText` and the
 *    serializer escapes it exactly once. `&amp;lt;` therefore stays the two
 *    characters `&lt;` (rendered `&amp;lt;`) and can never become a real `<`.
 *  - **No parser round-trip trick survives.** `<noscript>`, `<svg>`, `<math>`,
 *    `<template>` and friends are unknown tags here: their tag is dropped, their
 *    children are re-examined in this policy, and anything dangerous inside is
 *    dropped by the same rules. The output is a flat HTML tree, not a foreign
 *    namespace jsoup or a WebView might reinterpret later.
 *  - **Dropped content cannot be recovered by the renderer.** Because the renderer
 *    parses the returned string again, the returned string is the complete trusted
 *    input to it.
 *
 * ## Degrade, do not reject
 *
 * [PiHtmlPolicyResult.Rejected] is reserved for the resource bounds of `§十五`
 * (size / depth / node count) and for a defensive parse failure. An *attribute*
 * or *URL* that is not allowed is simply **not copied**: the element and its text
 * stay. That is the plan's "所有情况下都不能静默丢失模型输出" (`§六.4`): a
 * `javascript:` link becomes a plain link, a relative `<img>` becomes nothing (it
 * has no text), and the surrounding prose is untouched. A caller that wants the
 * raw source even then can still compare `Ok.cleanedHtml` with its input.
 *
 * ## Bounds
 *
 * [PiHtmlPolicyLimits] carries the plan's numbers: 256 KiB, depth 64 and 20,000
 * nodes. They are parameters, not constants, so the harness can drive the failure
 * paths with tiny values. Depth is counted on the **source** tree (a chain of
 * unknown wrappers is still a chain), and node count covers every element and
 * text node visited. Subtrees dropped as dangerous are neither counted nor
 * depth-checked — they never reach the output, and dropping the first element of
 * a hostile subtree is already O(1).
 */
object PiHtmlPolicy {

    /** The plan's `§十五` defaults, in one place; used by the one-argument [sanitize]. */
    val LIMITS = PiHtmlPolicyLimits()

    /** `§六.1` block-level subset, in the plan's wording. */
    val BLOCK_TAGS: Set<String> = setOf(
        "div",
        "p",
        "details",
        "summary",
        "blockquote",
        "ul",
        "ol",
        "li",
        "table",
        "thead",
        "tbody",
        "tr",
        "th",
        "td",
        "pre",
        "hr",
        "figure",
        "figcaption",
    )

    /** `§六.1` inline subset, in the plan's wording. */
    val INLINE_TAGS: Set<String> = setOf(
        "strong",
        "b",
        "em",
        "i",
        "del",
        "s",
        "mark",
        "u",
        "code",
        "kbd",
        "sup",
        "sub",
        "span",
        "br",
        "a",
        "img",
    )

    /** Every tag this policy can emit. */
    val ALLOWED_TAGS: Set<String> = BLOCK_TAGS + INLINE_TAGS

    /**
     * Tags whose **content is dropped with the tag**.
     *
     * The core of the list is the plan's forbidden set (`§六.3`: `script`,
     * `iframe`, `object`, `embed`, `form`, CSS injection), completed with the
     * form-control tags (`input`, `button`, `textarea`, `select`, `option`) and the
     * head-only resource tags (`link`, `meta`, `base`) — a control or a resource
     * loader has no meaning in this subset, and keeping its label as loose text
     * would misrepresent the document. Dropping the subtree, not just the tag, is
     * the point: `<textarea>secret</textarea>` and `<button>click</button>` are
     * content the model wrote, but it is not going to be displayed.
     *
     * Anything **not** in this set and not in [ALLOWED_TAGS] is an unknown tag:
     * the tag is dropped and its children are sanitized normally, so their text
     * survives.
     */
    val DANGEROUS_TAGS: Set<String> = setOf(
        "script",
        "style",
        "iframe",
        "object",
        "embed",
        "form",
        "input",
        "button",
        "textarea",
        "select",
        "option",
        "link",
        "meta",
        "base",
    )

    /**
     * The complete attribute vocabulary, per tag. There is no default: an
     * attribute absent from the tag's set is dropped, which rules out
     * `style`/`class`/`id`, every `on*` handler, `srcdoc`, `formaction`,
     * `target`, `srcset` and everything else in one place.
     *
     * `img[src]` is not in the plan's one-line whitelist but is required by the
     * next paragraph of the same section ("图片必须走现有图片资源处理链路"); it is
     * accepted only as `http`/`https`.
     */
    private val ATTRIBUTES: Map<String, Set<String>> = mapOf(
        "a" to setOf("href", "title"),
        "details" to setOf("open"),
        "ol" to setOf("start"),
        "td" to setOf("colspan", "rowspan"),
        "th" to setOf("colspan", "rowspan"),
        "img" to setOf("src"),
    )

    /**
     * The tags an `a` may link to and an `img` may load from.
     *
     * `mailto` is only valid on `a`: an image cannot be an email address, so
     * `img[src="mailto:…"]` is dropped rather than passed to the image loader.
     */
    private val LINK_SCHEMES = setOf("http", "https", "mailto")
    private val IMAGE_SCHEMES = setOf("http", "https")

    /** Browsers clamp table spans at 1000; a value beyond it is dropped, not repaired. */
    private const val MAX_SPAN = 1000

    /** `open` is a boolean attribute: only its presence is meaningful. */
    private const val BOOLEAN_OPEN = "open"

    /**
     * Sanitize one raw HTML block with the plan's default limits.
     *
     * Never throws for untrusted input; every failure is a
     * [PiHtmlPolicyResult.Rejected].
     */
    fun sanitize(html: String): PiHtmlPolicyResult = sanitize(html, LIMITS)

    /**
     * Sanitize one raw HTML block.
     *
     * @param html the block body exactly as the model wrote it (entities intact).
     * @param limits the resource bounds; see [PiHtmlPolicyLimits].
     */
    fun sanitize(html: String, limits: PiHtmlPolicyLimits): PiHtmlPolicyResult {
        if (html.length > limits.maxChars) return rejected(PiHtmlRejectReason.TooLarge)

        val parsed = try {
            Jsoup.parseBodyFragment(html)
        } catch (_: StackOverflowError) {
            // Defence in depth: jsoup's tree builder is iterative, but a parser
            // failing this way must still not escape to the UI thread.
            return rejected(PiHtmlRejectReason.TooDeep)
        } catch (_: Exception) {
            return rejected(PiHtmlRejectReason.Malformed)
        }

        return try {
            val output = Document("")
            output.outputSettings().prettyPrint(false)
            val body = output.appendElement("body")
            val builder = Builder(output, limits)
            builder.copyChildren(parsed.body(), body, parentDepth = 0)
            PiHtmlPolicyResult.Ok(body.html())
        } catch (stop: PolicyStop) {
            rejected(stop.reason)
        } catch (_: StackOverflowError) {
            rejected(PiHtmlRejectReason.TooDeep)
        } catch (_: Exception) {
            rejected(PiHtmlRejectReason.Malformed)
        }
    }

    /** Whether [tag] (case-insensitive) may appear in the output. */
    fun isAllowedTag(tag: String): Boolean = tag.lowercase() in ALLOWED_TAGS

    /** Whether [tag] is dropped **with its content**. */
    fun isDangerousTag(tag: String): Boolean = tag.lowercase() in DANGEROUS_TAGS

    /** Whether [attribute] (case-insensitive) is copied for [tag]. */
    fun isAllowedAttribute(tag: String, attribute: String): Boolean {
        val allowed = ATTRIBUTES[tag.lowercase()] ?: return false
        return attribute.lowercase() in allowed
    }

    /**
     * A URL that may be handed to the platform, or `null`.
     *
     * The value is validated in four steps, in this order:
     *
     *  1. C0/C1 control characters are removed (browsers ignore them inside
     *     schemes, so `java\tscript:` must be treated as `javascript:`); ends are
     *     trimmed.
     *  2. Character references are decoded **repeatedly** *for the check*.
     *     jsoup already decoded the attribute once, but `&amp;#x6a;` arrives here
     *     as `&#x6a;` and would become a `j` the next time a parser looks at it.
     *     Validation therefore uses the fully-decoded form; the returned string
     *     stays the once-decoded value so it cannot be decoded again into
     *     something else.
     *  3. All whitespace and controls are removed **for the check** so
     *     `java\nscript:` and `java script:` are seen as one token.
     *  4. The scheme before the first `:` must be one of [LINK_SCHEMES] (or
     *     [IMAGE_SCHEMES] when `allowMailto = false`), and there must be
     *     something after the colon. No scheme means a relative/absolute local
     *     path or a fragment, and is rejected; `//host` is rejected too.
     *
     * @param raw the attribute value as jsoup decoded it (one entity pass).
     * @param allowMailto `false` for `img[src]`, which may only be http(s).
     * @return the cleaned URL to copy, or `null` to drop the attribute/element.
     */
    fun sanitizeUrl(raw: String, allowMailto: Boolean = true): String? {
        val cleaned = stripControlCharacters(raw).trim(::isUrlSpace)
        if (cleaned.isEmpty()) return null

        val probe = buildString(cleaned.length) {
            for (c in decodeEntitiesRepeatedly(cleaned)) {
                if (Character.isISOControl(c) || isUrlSpace(c)) continue
                append(c)
            }
        }
        if (probe.startsWith("//")) return null

        val colon = probe.indexOf(':')
        if (colon <= 0) return null
        if (probe.length <= colon + 1) return null

        val scheme = probe.substring(0, colon).lowercase()
        val allowed = if (allowMailto) LINK_SCHEMES else IMAGE_SCHEMES
        if (scheme !in allowed) return null
        return cleaned
    }

    private fun rejected(reason: PiHtmlRejectReason): PiHtmlPolicyResult =
        PiHtmlPolicyResult.Rejected(reason)

    /**
     * Builds the output tree. One instance per call; the counters are per block.
     *
     * Source and output are separate trees on purpose: no node from jsoup's parse
     * is ever re-parented into the output, so an input shape that jsoup happens to
     * represent in a surprising way (foster-parented table text, template content,
     * a namespace element) cannot leak that representation into what the renderer
     * parses later.
     */
    private class Builder(
        private val output: Document,
        private val limits: PiHtmlPolicyLimits,
    ) {
        private var nodes = 0

        /**
         * @param target the output element that receives this level's nodes.
         * @param parentDepth depth of [target] **in the source tree**. It differs
         *   from the output depth when an unknown wrapper was unwrapped, which is
         *   deliberate: the bound is on how deeply the input nests, not on how
         *   shallow the cleaned output happens to become.
         */
        fun copyChildren(source: Element, target: Element, parentDepth: Int) {
            for (node in source.childNodes()) {
                when (node) {
                    is Element -> copyElement(node, target, parentDepth)
                    is TextNode -> {
                        countNode()
                        val text = node.wholeText
                        if (text.isNotEmpty()) target.appendText(text)
                    }
                    // Comments, DataNodes (raw script/style text) and declarations
                    // never reach the output. DataNodes only occur inside the
                    // dangerous tags, which are dropped before recursion.
                    else -> Unit
                }
            }
        }

        private fun copyElement(source: Element, target: Element, parentDepth: Int) {
            val tag = source.normalName()
            if (tag in DANGEROUS_TAGS) return

            countNode()
            val depth = parentDepth + 1
            if (depth > limits.maxDepth) throw PolicyStop(PiHtmlRejectReason.TooDeep)

            if (tag == "img") {
                // No text, so dropping an invalid image loses nothing; keeping a
                // src-less <img> would only show a broken-image glyph.
                val src = sanitizeUrl(source.attr("src"), allowMailto = false) ?: return
                val image = output.createElement("img")
                image.attr("src", src)
                target.appendChild(image)
                return
            }

            if (tag !in ALLOWED_TAGS) {
                // Unknown tag: drop the tag, keep and re-examine its children.
                copyChildren(source, target, depth)
                return
            }

            val copy = output.createElement(tag)
            copyAllowedAttributes(source, copy, tag)
            target.appendChild(copy)
            copyChildren(source, copy, depth)
        }

        private fun copyAllowedAttributes(source: Element, target: Element, tag: String) {
            when (tag) {
                "a" -> {
                    copyUrl(source, target, "href", allowMailto = true)
                    copyText(source, target, "title")
                }
                "details" -> {
                    if (source.hasAttr(BOOLEAN_OPEN)) target.attr(BOOLEAN_OPEN, "")
                }
                "ol" -> copyInteger(source, target, "start", min = Int.MIN_VALUE, max = Int.MAX_VALUE)
                "td", "th" -> {
                    copyInteger(source, target, "colspan", min = 1, max = MAX_SPAN)
                    copyInteger(source, target, "rowspan", min = 1, max = MAX_SPAN)
                }
            }
        }

        private fun copyUrl(source: Element, target: Element, name: String, allowMailto: Boolean) {
            // The HTML parser has already de-duplicated repeated attributes; the
            // hasAttr guard is belt-and-braces for a future parser change.
            if (target.hasAttr(name)) return
            val safe = sanitizeUrl(source.attr(name), allowMailto) ?: return
            target.attr(name, safe)
        }

        private fun copyText(source: Element, target: Element, name: String) {
            if (target.hasAttr(name) || !source.hasAttr(name)) return
            target.attr(name, source.attr(name))
        }

        private fun copyInteger(source: Element, target: Element, name: String, min: Int, max: Int) {
            if (target.hasAttr(name) || !source.hasAttr(name)) return
            val value = source.attr(name).trim()
            val integer = value.toIntOrNull() ?: return
            if (integer < min || integer > max) return
            // Reject forms toIntOrNull accepts but HTML integers do not: a leading
            // `+`, surrounding whitespace inside the digits, `0x…`, etc.
            if (!isHtmlInteger(value)) return
            target.attr(name, value)
        }

        private fun countNode() {
            nodes++
            if (nodes > limits.maxNodes) throw PolicyStop(PiHtmlRejectReason.TooManyNodes)
        }
    }
}

/** The bounds from the plan's `§十五` table. */
data class PiHtmlPolicyLimits(
    /** Maximum raw block length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum source-tree nesting; a top-level element is depth 1. */
    val maxDepth: Int = 64,
    /** Maximum element + text nodes visited (the plan's 20,000). */
    val maxNodes: Int = 20_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
    }
}

/** Why a block could not be sanitized; the caller keeps the raw source in every case. */
enum class PiHtmlRejectReason {
    /** Longer than [PiHtmlPolicyLimits.maxChars]. */
    TooLarge,

    /** Nested deeper than [PiHtmlPolicyLimits.maxDepth]. */
    TooDeep,

    /** More than [PiHtmlPolicyLimits.maxNodes] elements/text nodes. */
    TooManyNodes,

    /** A defensive failure while parsing or serializing; never expected from jsoup. */
    Malformed,
}

/** The outcome of [PiHtmlPolicy.sanitize]: trusted cleaned HTML, or a stated refusal. */
sealed interface PiHtmlPolicyResult {
    /** A **new** HTML fragment containing only allowed tags/attributes/URLs. */
    data class Ok(val cleanedHtml: String) : PiHtmlPolicyResult

    /** No cleaned fragment; the caller must keep showing the raw source. */
    data class Rejected(val reason: PiHtmlRejectReason) : PiHtmlPolicyResult
}

/**
 * Control-flow carrier for the two resource bounds. A private exception keeps the
 * recursive walk readable; it is caught in `sanitize` and never escapes.
 */
private class PolicyStop(val reason: PiHtmlRejectReason) : RuntimeException()

/** C0 and C1 controls, removed from every URL before it is validated or copied. */
private fun stripControlCharacters(value: String): String {
    var first = -1
    for (index in value.indices) {
        if (Character.isISOControl(value[index])) {
            first = index
            break
        }
    }
    if (first < 0) return value
    return buildString(value.length) {
        for (c in value) if (!Character.isISOControl(c)) append(c)
    }
}

/**
 * Whitespace as a URL sees it.
 *
 * `Character.isWhitespace` does not cover every Space_Separator (NBSP, U+2007,
 * U+202F), so both predicates are needed when trimming and when building the
 * no-whitespace probe.
 */
private fun isUrlSpace(c: Char): Boolean =
    Character.isWhitespace(c) || Character.isSpaceChar(c)

/**
 * Decode HTML character references until the string stops changing (at most four
 * passes).
 *
 * Used **only** to build the probe for [PiHtmlPolicy.sanitizeUrl]. jsoup already
 * consumed one pass while parsing the attribute, so this is the defence against
 * `&amp;#x6a;`-style nesting. Bounding the loop keeps a `&amp;amp;…` payload from
 * costing more than linear work per pass; a string that has not settled after four
 * passes still cannot spell an allowed scheme by accident.
 */
private fun decodeEntitiesRepeatedly(value: String): String {
    var current = value
    repeat(4) {
        val next = Parser.unescapeEntities(current, false)
        if (next == current) return current
        current = next
    }
    return current
}

/**
 * The HTML "valid integer" shape: optional `-`, then ASCII digits only.
 *
 * `toIntOrNull` alone would accept `+2`, which HTML does not treat as an integer;
 * the policy copies values, not repaired values.
 */
private fun isHtmlInteger(value: String): Boolean {
    var index = 0
    if (value.startsWith("-")) index = 1
    if (index >= value.length) return false
    var digits = 0
    while (index < value.length) {
        if (value[index] !in '0'..'9') return false
        digits++
        index++
    }
    return digits in 1..9
}
