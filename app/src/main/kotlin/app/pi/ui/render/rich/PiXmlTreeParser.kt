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
                        when (val feed = builder.startElement(parser.name, attributes)) {
                            XmlFeed.Ok -> Unit
                            else -> return failureFor(feed)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (val feed = builder.endElement(parser.name)) {
                            XmlFeed.Ok -> Unit
                            else -> return failureFor(feed)
                        }
                    }

                    XmlPullParser.TEXT,
                    XmlPullParser.CDSECT,
                    XmlPullParser.IGNORABLE_WHITESPACE -> builder.text(parser.text ?: "")

                    XmlPullParser.ENTITY_REF -> {
                        // Predefined entities normally arrive as TEXT; an ENTITY_REF
                        // at this point means a declaration this build refuses.
                        val resolved = parser.text
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

    private fun failureFor(feed: XmlFeed): XmlTreeResult.Failure = when (feed) {
        XmlFeed.TooDeep -> XmlTreeResult.Failure(XmlTreeFailure.TooDeep)
        XmlFeed.TooManyNodes -> XmlTreeResult.Failure(XmlTreeFailure.TooManyNodes)
        XmlFeed.Malformed, XmlFeed.Ok -> XmlTreeResult.Failure(XmlTreeFailure.Malformed)
    }
}
