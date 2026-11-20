package app.pi.ui.render

/**
 * Zero-dependency, sub-millisecond native Kotlin syntax highlighter mapping directly onto Pi's
 * [PiSyntaxToken] enum (so every color comes 100% from the active official Pi theme's `syntax*` tokens).
 *
 * Role in the highlighting pipeline:
 *  1. **Instant Frame-0 & Streaming**: Renders syntax colors immediately while tokens stream in or
 *     before the Node `pi-highlight` service finishes booting/answering — eliminating monochrome flash.
 *  2. **Offline / Cold-Session Resilience**: Old sessions and Workspace files stay richly highlighted
 *     even when the engine process is stopped or restarting.
 *  3. **Seamless Upgrade**: Once settled, [app.pi.highlight.PiNodeCodeHighlighter] (Pi's 191-language
 *     `highlight.js` in Node) replaces the fast spans automatically whenever available.
 */
internal object PiFastCodeHighlighter {

    private const val MAX_FAST_CHARS = 32 * 1024

    private enum class Family {
        CLike,
        Python,
        Shell,
        Json,
        Yaml,
        Sql,
        Markup,
        Css,
        Diff,
    }

    private fun familyOf(language: String?): Family? {
        val lang = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (lang) {
            "kt", "kotlin", "java", "ts", "typescript", "tsx", "js", "javascript", "jsx",
            "mjs", "cjs", "rs", "rust", "go", "golang", "c", "h", "cpp", "c++", "cc", "cxx",
            "hpp", "cs", "csharp", "c#", "swift", "dart", "scala", "php", "groovy", "gradle",
            "proto", "protobuf", "glsl", "zig", "objc", "objectivec" -> Family.CLike

            "py", "python", "python3", "rb", "ruby", "r", "elixir", "ex", "exs", "perl", "pl",
            "toml", "ini", "cfg", "conf", "dockerfile", "docker", "makefile", "make", "cmake" -> Family.Python

            "sh", "bash", "zsh", "shell", "console", "ps1", "powershell", "bat", "cmd" -> Family.Shell
            "json", "jsonc", "json5", "jsonl" -> Family.Json
            "yaml", "yml" -> Family.Yaml
            "sql", "sqlite", "postgres", "postgresql", "mysql", "plsql" -> Family.Sql
            "html", "htm", "xml", "svg", "xhtml", "vue", "svelte" -> Family.Markup
            "css", "scss", "sass", "less" -> Family.Css
            "diff", "patch" -> Family.Diff
            else -> null
        }
    }

    private fun keywordsFor(lang: String): Set<String> = when (lang) {
        "kt", "kotlin" -> KOTLIN_KW
        "java", "scala", "groovy", "gradle" -> JAVA_KW
        "ts", "typescript", "tsx", "js", "javascript", "jsx", "mjs", "cjs" -> JS_KW
        "py", "python", "python3" -> PY_KW
        "rs", "rust" -> RUST_KW
        "go", "golang" -> GO_KW
        "sh", "bash", "zsh", "shell", "console" -> SHELL_KW
        "sql", "sqlite", "postgres", "postgresql", "mysql", "plsql" -> SQL_KW
        else -> COMMON_KW
    }

    fun highlight(code: String, language: String?): PiCodeHighlight {
        if (code.isEmpty() || code.length > MAX_FAST_CHARS) return PiCodeHighlight()
        val lang = language?.trim()?.lowercase() ?: return PiCodeHighlight()
        val family = familyOf(lang) ?: return PiCodeHighlight()
        val spans = ArrayList<PiCodeSpan>(64)
        when (family) {
            Family.Diff -> scanDiff(code, spans)
            Family.Json -> scanJson(code, spans)
            Family.Yaml -> scanYaml(code, spans)
            Family.Markup -> scanMarkup(code, spans)
            Family.Css -> scanCss(code, spans)
            Family.Sql -> scanCode(code, spans, keywordsFor(lang), hashComment = false, dashComment = true, slashComment = true, backtickString = false, caseInsensitive = true)
            Family.Python -> scanCode(code, spans, keywordsFor(lang), hashComment = true, dashComment = false, slashComment = false, backtickString = false, caseInsensitive = false)
            Family.Shell -> scanCode(code, spans, keywordsFor(lang), hashComment = true, dashComment = false, slashComment = false, backtickString = true, caseInsensitive = false)
            Family.CLike -> scanCode(code, spans, keywordsFor(lang), hashComment = false, dashComment = false, slashComment = true, backtickString = true, caseInsensitive = false)
        }
        return PiCodeHighlight(spans = spans, languageKnown = true)
    }

    private fun scanDiff(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            if (end > i) {
                when {
                    code.startsWith("@@", i) -> out.add(PiCodeSpan(i, end, PiSyntaxToken.Keyword))
                    code.startsWith("+++", i) || code.startsWith("---", i) -> out.add(PiCodeSpan(i, end, PiSyntaxToken.Muted))
                    code[i] == '+' -> out.add(PiCodeSpan(i, end, PiSyntaxToken.DiffAdded))
                    code[i] == '-' -> out.add(PiCodeSpan(i, end, PiSyntaxToken.DiffRemoved))
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    private fun scanJson(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]
            when {
                ch == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                }
                ch == '"' -> {
                    val start = i++
                    while (i < n) {
                        if (code[i] == '\\') { i += 2; continue }
                        if (code[i] == '"') { i++; break }
                        i++
                    }
                    var k = i
                    while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
                    val tok = if (k < n && code[k] == ':') PiSyntaxToken.Variable else PiSyntaxToken.StringLiteral
                    out.add(PiCodeSpan(start, i, tok))
                }
                ch.isDigit() || (ch == '-' && i + 1 < n && code[i + 1].isDigit()) -> {
                    val start = i++
                    while (i < n && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || code[i] == 'E' || code[i] == '+' || code[i] == '-')) i++
                    out.add(PiCodeSpan(start, i, PiSyntaxToken.Number))
                }
                ch.isLetter() -> {
                    val start = i++
                    while (i < n && code[i].isLetter()) i++
                    val word = code.substring(start, i)
                    if (word == "true" || word == "false" || word == "null") {
                        out.add(PiCodeSpan(start, i, PiSyntaxToken.Number))
                    }
                }
                else -> i++
            }
        }
    }

    private fun scanYaml(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            var p = i
            while (p < end && (code[p] == ' ' || code[p] == '\t')) p++
            if (p < end && code[p] == '#') {
                out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
            } else if (p < end) {
                val colon = code.indexOf(':', p)
                if (colon in p until end && (colon + 1 == end || code[colon + 1] == ' ' || code[colon + 1] == '\t')) {
                    val keyStart = if (code[p] == '-' && p + 1 < colon && code[p + 1] == ' ') p + 2 else p
                    if (keyStart < colon) out.add(PiCodeSpan(keyStart, colon, PiSyntaxToken.Variable))
                    var v = colon + 1
                    while (v < end && (code[v] == ' ' || code[v] == '\t')) v++
                    if (v < end) {
                        val hash = code.indexOf(" #", v)
                        val vEnd = if (hash in v until end) hash else end
                        val raw = code.substring(v, vEnd).trim()
                        when {
                            raw.startsWith('"') || raw.startsWith('\'') -> out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.StringLiteral))
                            raw == "true" || raw == "false" || raw == "null" || raw == "~" || raw.toDoubleOrNull() != null ->
                                out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.Number))
                            raw.isNotEmpty() -> out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.StringLiteral))
                        }
                        if (hash in v until end) out.add(PiCodeSpan(hash + 1, end, PiSyntaxToken.Comment))
                    }
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    private fun scanMarkup(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                val close = code.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                i = end
                continue
            }
            if (code[i] == '<') {
                var p = i + 1
                if (p < n && (code[p] == '/' || code[p] == '!' || code[p] == '?')) p++
                val tagStart = p
                while (p < n && (code[p].isLetterOrDigit() || code[p] == '-' || code[p] == ':' || code[p] == '_')) p++
                if (p > tagStart) out.add(PiCodeSpan(tagStart, p, PiSyntaxToken.Keyword))
                while (p < n && code[p] != '>') {
                    when {
                        code[p] == '"' || code[p] == '\'' -> {
                            val q = code[p]
                            val s = p++
                            while (p < n && code[p] != q) p++
                            if (p < n) p++
                            out.add(PiCodeSpan(s, p, PiSyntaxToken.StringLiteral))
                        }
                        code[p].isLetter() || code[p] == '_' -> {
                            val a = p++
                            while (p < n && (code[p].isLetterOrDigit() || code[p] == '-' || code[p] == ':' || code[p] == '_')) p++
                            out.add(PiCodeSpan(a, p, PiSyntaxToken.Variable))
                        }
                        else -> p++
                    }
                }
                i = if (p < n) p + 1 else n
                continue
            }
            i++
        }
    }

    private fun scanCss(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]
            when {
                ch == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                }
                ch == '"' || ch == '\'' -> {
                    val s = i++
                    while (i < n && code[i] != ch) { if (code[i] == '\\') i++; i++ }
                    if (i < n) i++
                    out.add(PiCodeSpan(s, i, PiSyntaxToken.StringLiteral))
                }
                ch == '@' -> {
                    val s = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '-')) i++
                    out.add(PiCodeSpan(s, i, PiSyntaxToken.Keyword))
                }
                ch == '#' && i + 1 < n && code[i + 1].isLetterOrDigit() -> {
                    val s = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_')) i++
                    out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                }
                ch.isDigit() -> {
                    val s = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '%')) i++
                    out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                }
                ch.isLetter() || ch == '-' -> {
                    val s = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_')) i++
                    var k = i
                    while (k < n && code[k] == ' ') k++
                    if (k < n && code[k] == ':') out.add(PiCodeSpan(s, i, PiSyntaxToken.Variable))
                }
                else -> i++
            }
        }
    }

    private fun scanCode(
        code: String,
        out: MutableList<PiCodeSpan>,
        keywords: Set<String>,
        hashComment: Boolean,
        dashComment: Boolean,
        slashComment: Boolean,
        backtickString: Boolean,
        caseInsensitive: Boolean,
    ) {
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]
            // Line & block comments
            if (slashComment && ch == '/' && i + 1 < n) {
                if (code[i + 1] == '/') {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                    continue
                }
                if (code[i + 1] == '*') {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                    continue
                }
            }
            if (hashComment && ch == '#') {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                i = end
                continue
            }
            if (dashComment && ch == '-' && i + 1 < n && code[i + 1] == '-') {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                i = end
                continue
            }
            // Triple-quoted strings
            if ((ch == '"' || ch == '\'') && i + 2 < n && code[i + 1] == ch && code[i + 2] == ch) {
                val delim = if (ch == '"') "\"\"\"" else "'''"
                val close = code.indexOf(delim, i + 3)
                val end = if (close < 0) n else close + 3
                out.add(PiCodeSpan(i, end, PiSyntaxToken.StringLiteral))
                i = end
                continue
            }
            // Quoted strings
            if (ch == '"' || ch == '\'' || (backtickString && ch == '`')) {
                val s = i++
                while (i < n) {
                    if (code[i] == '\\') { i += 2; continue }
                    if (code[i] == ch) { i++; break }
                    if (ch != '`' && code[i] == '\n') break
                    i++
                }
                out.add(PiCodeSpan(s, i.coerceAtMost(n), PiSyntaxToken.StringLiteral))
                continue
            }
            // Annotations / decorators / shell vars
            if (ch == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
                val s = i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                out.add(PiCodeSpan(s, i, PiSyntaxToken.Muted))
                continue
            }
            if (ch == '$' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_' || code[i + 1] == '{')) {
                val s = i++
                if (code[i] == '{') {
                    val close = code.indexOf('}', i + 1)
                    i = if (close < 0) n else close + 1
                } else {
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                }
                out.add(PiCodeSpan(s, i, PiSyntaxToken.Variable))
                continue
            }
            // Numbers
            if (ch.isDigit()) {
                val s = i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || (code[i] == '.' && i + 1 < n && code[i + 1].isDigit()))) i++
                out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                continue
            }
            // Identifiers / keywords / types / functions
            if (ch.isLetter() || ch == '_') {
                val s = i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(s, i)
                val lookup = if (caseInsensitive) word.lowercase() else word
                when {
                    lookup in keywords -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Keyword))
                    lookup in LITERALS -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                    !caseInsensitive && i < n && code[i] == '(' -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Function))
                    !caseInsensitive && word[0].isUpperCase() && word.any { it.isLowerCase() } ->
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Type))
                }
                continue
            }
            i++
        }
    }

    private val LITERALS = setOf("true", "false", "null", "nil", "none", "None", "True", "False", "undefined", "NaN", "Infinity")

    private val KOTLIN_KW = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else",
        "when", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally",
        "throw", "in", "is", "as", "by", "private", "public", "internal", "protected", "override",
        "open", "abstract", "sealed", "data", "enum", "companion", "suspend", "inline", "reified",
        "const", "lateinit", "vararg", "tailrec", "operator", "infix", "init", "constructor",
        "this", "super", "typealias", "expect", "actual", "annotation", "value", "where", "out",
    )

    private val JAVA_KW = setOf(
        "package", "import", "class", "interface", "enum", "record", "extends", "implements",
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "transient", "native", "void", "boolean", "byte", "char", "short", "int", "long", "float",
        "double", "if", "else", "switch", "case", "default", "for", "while", "do", "break",
        "continue", "return", "try", "catch", "finally", "throw", "throws", "new", "this", "super",
        "instanceof", "var", "sealed", "permits", "yield",
    )

    private val JS_KW = setOf(
        "import", "export", "from", "default", "const", "let", "var", "function", "async", "await",
        "return", "if", "else", "switch", "case", "for", "of", "in", "while", "do", "break",
        "continue", "try", "catch", "finally", "throw", "new", "class", "extends", "implements",
        "interface", "type", "enum", "namespace", "public", "private", "protected", "readonly",
        "abstract", "static", "this", "super", "typeof", "instanceof", "keyof", "as", "is",
        "yield", "delete", "void", "declare", "module", "satisfies", "infer", "override",
    )

    private val PY_KW = setOf(
        "import", "from", "as", "def", "class", "return", "yield", "async", "await", "if", "elif",
        "else", "for", "in", "while", "break", "continue", "pass", "try", "except", "finally",
        "raise", "with", "lambda", "and", "or", "not", "is", "del", "global", "nonlocal", "assert",
        "match", "case", "self", "cls", "end", "do", "then", "require", "module", "begin", "rescue",
    )

    private val RUST_KW = setOf(
        "use", "mod", "pub", "crate", "super", "self", "Self", "fn", "let", "mut", "const", "static",
        "struct", "enum", "trait", "impl", "for", "in", "while", "loop", "if", "else", "match",
        "return", "break", "continue", "async", "await", "move", "ref", "type", "where", "unsafe",
        "extern", "dyn", "as", "box", "macro_rules",
    )

    private val GO_KW = setOf(
        "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan",
        "if", "else", "switch", "case", "default", "for", "range", "break", "continue", "return",
        "go", "defer", "select", "goto", "fallthrough", "make", "new", "len", "cap", "append",
    )

    private val SHELL_KW = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case",
        "esac", "function", "return", "exit", "local", "export", "readonly", "set", "unset",
        "shift", "trap", "exec", "eval", "source", "echo", "printf", "cd", "pwd", "test",
    )

    private val SQL_KW = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "index", "view", "drop", "alter", "add", "column", "join", "inner", "left", "right",
        "outer", "full", "on", "as", "and", "or", "not", "in", "is", "like", "between", "exists",
        "group", "by", "order", "asc", "desc", "having", "limit", "offset", "distinct", "union",
        "all", "case", "when", "then", "else", "end", "primary", "key", "foreign", "references",
        "begin", "commit", "rollback", "with", "returning", "conflict", "replace",
    )

    private val COMMON_KW = setOf(
        "import", "export", "include", "define", "using", "namespace", "class", "struct", "enum",
        "interface", "trait", "protocol", "extension", "func", "function", "fn", "def", "let",
        "var", "val", "const", "static", "final", "public", "private", "protected", "internal",
        "return", "if", "else", "switch", "case", "default", "for", "while", "do", "break",
        "continue", "try", "catch", "finally", "throw", "new", "delete", "this", "self", "super",
        "async", "await", "void", "int", "long", "float", "double", "bool", "boolean", "char",
        "string", "auto", "override", "virtual", "template", "typename", "typedef", "sizeof",
    )
}
