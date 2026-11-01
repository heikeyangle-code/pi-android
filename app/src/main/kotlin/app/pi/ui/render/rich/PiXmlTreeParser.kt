package app.pi.ui.render.rich

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * The **Android half** of the XML pipeline: `XmlPullParser` → [XmlTreeBuilder].
 *
 * `PiXmlTree.kt` holds every rule and every bound and is the file the bare-JVM
 * harness compiles (`PiXmlTreeCheck.kt`). This adapter only translates events, and
 * it is deliberately the thinnest file in the package: it cannot be compiled on a
 * machine without `android.jar`, so anything that could live in the pure model
 * must live there instead.
 *
 * ## Security
 *
 * Before the parser is constructed, [PiXmlTree.checkSource] rejects oversized input
 * and any `DOCTYPE`/`ENTITY` declaration (see that function's KDoc). The parser is
 * then configured so declarations are not processed even if one somehow reached it:
 *
 *  - `FEATURE_PROCESS_DOCDECL = false` — custom entities are never registered;
 *  - `FEATURE_PROCESS_NAMESPACES = false` — no namespace resolution / DTD-driven
 *    namespace lookups; tag names keep their literal prefix;
 *  - a `DOCDECL` event or an unresolved `ENTITY_REF` is a [XmlTreeFailure.Dtd]
 *    failure, not something to interpret.
 *
 * There is no external-entity resolution to disable on `XmlPullParser`: with DTD
 * processing off there is no declaration table, and this file never opens a
 * `URL`/`File`. Text is only ever taken from the parser's own resolved character
 * data. The builder adds no entity expansion of its own.
 *
 * ## Why events at all
 *
 * The alternative — collecting `XmlEvent`s into a list — would duplicate the
 * source in memory and let a hostile document allocate before the node/depth
 * bounds are applied. Feeding the builder as events arrive means the first limit
 * reached stops the pull loop and the parser is discarded.
 */
internal object PiXmlTreeParser {
    /**
     * Parse an explicitly tagged `xml` fence.
     *
     * Never throws; every failure becomes [XmlTreeResult.Failure].
     *
     * @param settled `false` while the model is still writing the fence
     *   (`§十四`: an unclosed fence stays source).
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: XmlTreeLimits = XmlTreeLimits(),
    ): XmlTreeResult {
        if (!settled) return XmlTreeResult.Failure(XmlTreeFailure.NotSettled)
        PiXmlTree.checkSource(source, limits)?.let { return XmlTreeResult.Failure(it) }

        val parser: XmlPullParser = Xml.newPullParser()
        // Both features are optional per the XmlPullParser contract; a parser that
        // does not know one throws. The declaration pre-scan above is the
        // non-optional gate, so a refusal here cannot silently re-enable DTDs.
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        } catch (_: Exception) {
            // Not fatal: the builder keeps literal names either way.
        }
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (_: Exception) {
            // Not fatal only because `checkSource` already refused every DTD.
        }

        val builder = XmlTreeBuilder(limits)
        try {
            parser.setInput(StringReader(source))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val attributes = ArrayList<XmlAttribute>(parser.attributeCount)
                        for (index in 0 until parser.attributeCount) {
                            attributes += XmlAttribute(
                                name = parser.getAttributeName(index),
                                value = parser.getAttributeValue(index),
                            )
                        }
                        val name = parser.name
                            ?: return XmlTreeResult.Failure(XmlTreeFailure.Malformed)
                        when (val feed = builder.startElement(name, attributes)) {
                            XmlFeed.Ok -> Unit
                            else -> return failureFor(feed)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name
                            ?: return XmlTreeResult.Failure(XmlTreeFailure.Malformed)
                        when (val feed = builder.endElement(name)) {
                            XmlFeed.Ok -> Unit
                            else -> return failureFor(feed)
                        }
                    }

                    XmlPullParser.TEXT,
                    XmlPullParser.CDSECT,
                    XmlPullParser.IGNORABLE_WHITESPACE -> builder.text(parser.text ?: "")

                    XmlPullParser.ENTITY_REF -> {
                        // Predefined/numeric references normally arrive as TEXT.
                        // Anything still an ENTITY_REF is either one of the five XML
                        // built-ins (resolved here) or a reference this build has no
                        // declaration for — and with declarations refused, that can
                        // only be a document we will not interpret.
                        val resolved = resolveEntity(parser.name, parser.text)
                            ?: return XmlTreeResult.Failure(XmlTreeFailure.Dtd)
                        builder.text(resolved)
                    }

                    XmlPullParser.DOCDECL -> return XmlTreeResult.Failure(XmlTreeFailure.Dtd)

                    // START_DOCUMENT, COMMENT, PROCESSING_INSTRUCTION: nothing to store.
                    else -> Unit
                }
                event = parser.next()
            }
        } catch (_: StackOverflowError) {
            return XmlTreeResult.Failure(XmlTreeFailure.TooDeep)
        } catch (_: Exception) {
            return XmlTreeResult.Failure(XmlTreeFailure.Malformed)
        }

        return builder.finish()
    }

    /**
     * An `ENTITY_REF` event's replacement text.
     *
     * Only the five predefined XML entities and numeric character references are
     * resolved. There is deliberately no general-entity table: `DOCTYPE`/`ENTITY`
     * were refused before the parser was created, so any other name is a reference
     * this build cannot (and must not) resolve.
     */
    private fun resolveEntity(name: String?, text: String?): String? {
        if (!text.isNullOrEmpty()) return text
        if (name.isNullOrEmpty()) return null
        return when (name) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                if (!name.startsWith("#")) return null
                val code = if (name.startsWith("#x") || name.startsWith("#X")) {
                    name.substring(2).toIntOrNull(16)
                } else {
                    name.substring(1).toIntOrNull()
                }
                if (code == null || !Character.isValidCodePoint(code) || code in 0xD800..0xDFFF) {
                    null
                } else {
                    String(Character.toChars(code))
                }
            }
        }
    }

    private fun failureFor(feed: XmlFeed): XmlTreeResult.Failure = when (feed) {
        XmlFeed.TooDeep -> XmlTreeResult.Failure(XmlTreeFailure.TooDeep)
        XmlFeed.TooManyNodes -> XmlTreeResult.Failure(XmlTreeFailure.TooManyNodes)
        XmlFeed.Malformed, XmlFeed.Ok -> XmlTreeResult.Failure(XmlTreeFailure.Malformed)
    }
}
