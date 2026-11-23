package app.pi.ui.render.rich

/**
 * 围栏没写语言（或写错了常见别名）时，按内容猜一个**确定性很高**的语言，让富渲染不被漏掉。
 * 宁可不猜：只认几种几乎不会误判的开头。
 */
object PiFenceSniff {

    private val MERMAID_HEAD = Regex(
        """^(?:(?:graph|flowchart)\s+(?:TD|TB|BT|RL|LR)\b.*|sequenceDiagram|classDiagram(?:-v2)?|stateDiagram(?:-v2)?|erDiagram|gantt|journey|mindmap|timeline|gitGraph|quadrantChart|pie(?:\s+showData)?(?:\s+title\s.*)?|xychart(?:-beta)?|sankey(?:-beta)?|block-beta|packet-beta|kanban|architecture-beta|requirementDiagram|C4(?:Context|Container|Component|Dynamic|Deployment)|zenuml|radar-beta|treemap-beta)(?:\s.*)?$""",
    )

    /** 别名：写了也等于没写对的那些。 */
    fun alias(language: String): String = when (language.lowercase()) {
        "mmd", "mermaidjs" -> "mermaid"
        "tex", "latex", "katex", "mathjax" -> "math"
        "svg+xml" -> "svg"
        "geojson", "jsonld", "webmanifest" -> "json"
        "rss", "atom", "plist", "xsd", "xsl", "xslt", "kml", "gpx", "wsdl", "xhtml" -> "xml"
        else -> language
    }

    /** 没写语言时按内容猜；猜不出来返回 null。 */
    fun sniff(code: String, jsonValid: (String) -> Boolean): String? {
        val t = code.trim()
        if (t.isEmpty() || t.length > 256 * 1024) return null
        val firstLine = firstMeaningfulMermaidLine(t)
        return when {
            t.startsWith("<svg", ignoreCase = true) && t.contains("</svg>", ignoreCase = true) -> "svg"
            t.startsWith("<?xml") -> "xml"
            firstLine != null && MERMAID_HEAD.matches(firstLine) && t.lines().size >= 2 -> "mermaid"
            (t.startsWith("{") && t.endsWith("}") || t.startsWith("[") && t.endsWith("]")) &&
                t.length >= 2 && jsonValid(t) -> "json"
            else -> null
        }
    }

    /** Skips leading `%%` comments and optional `--- ... ---` YAML frontmatter used by Mermaid 11/12. */
    private fun firstMeaningfulMermaidLine(trimmed: String): String? {
        val lines = trimmed.lines()
        var i = 0
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            var closeIdx = -1
            for (j in 1 until minOf(lines.size, 32)) {
                if (lines[j].trim() == "---") {
                    closeIdx = j
                    break
                }
            }
            if (closeIdx > 0) i = closeIdx + 1
        }
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("%%")) return line
            i++
        }
        return null
    }
}
