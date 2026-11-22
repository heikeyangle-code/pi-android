package app.pi.ui.render

/**
 * Zero-dependency, sub-millisecond native Kotlin syntax highlighter covering 35+ languages
 * and mapping cleanly onto Pi's 12 [PiSyntaxToken] semantic roles (100% theme-driven via
 * the active Pi palette's `syntax*` tokens).
 *
 * Highlights:
 *  - Keywords, control flow, built-in types & PascalCase user types
 *  - Functions, method calls (`obj.method()`), Rust macros (`vec!`), annotations (`@Composable`, `#[derive]`)
 *  - Single/double/triple/raw strings with live template interpolation (`${expr}`, `$var`, Python `f"{expr}"`, Ruby `#{expr}`)
 *  - Line, block, and doc comments (with `@param` / `@return` doc-tag accenting)
 *  - Hex/binary/octal/scientific/underscored numbers & type suffixes (`42L`, `3.14f`, `100u64`)
 *  - Multi-char operators (`->`, `=>`, `::`, `==`, `!=`, `<=`, `>=`, `&&`, `||`, `??`, `?.`, `..`) & structural punctuation
 *  - Dedicated lexers for JSON/JSONC, YAML, TOML/INI, HTML/XML/SVG/JSX, CSS/SCSS, SQL, GraphQL,
 *    Protobuf, Dockerfile, Makefile, Markdown, and Diff/Patch
 *
 * Pure JVM (zero Android/Compose imports) and strictly O(N) linear scan with zero regex backtracking.
 */
internal object PiFastCodeHighlighter {

    private const val MAX_FAST_CHARS = 64 * 1024

    private enum class Family {
        CLike,
        Python,
        Ruby,
        Lua,
        Shell,
        Json,
        Yaml,
        Toml,
        Sql,
        Markup,
        Css,
        Dockerfile,
        Makefile,
        Markdown,
        Diff,
        Mermaid,
        Csv,
    }

    private fun familyOf(language: String?): Family? {
        val lang = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (lang) {
            "kt", "kotlin", "java", "ts", "typescript", "tsx", "js", "javascript", "jsx",
            "mjs", "cjs", "rs", "rust", "go", "golang", "c", "h", "cpp", "c++", "cc", "cxx",
            "hpp", "cs", "csharp", "c#", "swift", "dart", "scala", "php", "groovy", "gradle",
            "proto", "protobuf", "glsl", "zig", "objc", "objectivec", "graphql", "gql" -> Family.CLike

            "py", "python", "python3" -> Family.Python
            "rb", "ruby", "ex", "elixir", "exs", "perl", "pl", "r" -> Family.Ruby
            "lua" -> Family.Lua
            "sh", "bash", "zsh", "shell", "console", "ps1", "powershell", "bat", "cmd", "fish" -> Family.Shell
            "json", "jsonc", "json5", "jsonl" -> Family.Json
            "yaml", "yml" -> Family.Yaml
            "toml", "ini", "cfg", "conf", "properties", "env", "dotenv" -> Family.Toml
            "sql", "sqlite", "postgres", "postgresql", "mysql", "plsql" -> Family.Sql
            "html", "htm", "xml", "svg", "xhtml", "vue", "svelte" -> Family.Markup
            "css", "scss", "sass", "less" -> Family.Css
            "dockerfile", "docker" -> Family.Dockerfile
            "makefile", "make", "mk", "cmake" -> Family.Makefile
            "md", "markdown", "mdx" -> Family.Markdown
            "diff", "patch" -> Family.Diff
            "mermaid", "mmd" -> Family.Mermaid
            "csv", "tsv" -> Family.Csv
            else -> null
        }
    }

    private fun keywordsFor(lang: String): Set<String> = when (lang) {
        "kt", "kotlin" -> KOTLIN_KW
        "java", "scala", "groovy", "gradle" -> JAVA_KW
        "ts", "typescript", "tsx", "js", "javascript", "jsx", "mjs", "cjs" -> JS_KW
        "py", "python", "python3" -> PY_KW
        "rb", "ruby" -> RUBY_KW
        "ex", "elixir", "exs" -> ELIXIR_KW
        "perl", "pl" -> PERL_KW
        "r" -> R_KW
        "lua" -> LUA_KW
        "rs", "rust" -> RUST_KW
        "go", "golang" -> GO_KW
        "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "objc", "objectivec" -> CPP_KW
        "cs", "csharp", "c#" -> CSHARP_KW
        "swift" -> SWIFT_KW
        "dart" -> DART_KW
        "zig" -> ZIG_KW
        "php" -> PHP_KW
        "graphql", "gql" -> GRAPHQL_KW
        "proto", "protobuf" -> PROTO_KW
        "sh", "bash", "zsh", "shell", "console", "fish" -> SHELL_KW
        "ps1", "powershell" -> POWERSHELL_KW
        "bat", "cmd" -> BATCH_KW
        "sql", "sqlite", "postgres", "postgresql", "mysql", "plsql" -> SQL_KW
        else -> COMMON_KW
    }

    private fun builtinTypesFor(lang: String): Set<String> = when (lang) {
        "kt", "kotlin" -> KOTLIN_TYPES
        "ts", "typescript", "tsx", "js", "javascript", "jsx", "mjs", "cjs" -> TS_TYPES
        "rs", "rust" -> RUST_TYPES
        "go", "golang" -> GO_TYPES
        "py", "python", "python3" -> PY_TYPES
        "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "zig" -> C_TYPES
        "swift", "dart", "java", "cs", "csharp", "c#" -> JVM_SWIFT_TYPES
        else -> emptySet()
    }

    /** Returns true when this native lexer directly owns [language] with full grammar fidelity. */
    fun supports(language: String?): Boolean = familyOf(language) != null

    fun highlight(code: String, language: String?): PiCodeHighlight {
        if (code.isEmpty()) return PiCodeHighlight()
        val lang = language?.trim()?.lowercase() ?: return PiCodeHighlight()
        val family = familyOf(lang) ?: return PiCodeHighlight()
        // For huge files in a known language, keep languageKnown=true so callers skip slow IPC fallback.
        if (code.length > MAX_FAST_CHARS) return PiCodeHighlight(spans = emptyList(), languageKnown = true)
        val spans = ArrayList<PiCodeSpan>((code.length / 8).coerceIn(32, 4096))
        when (family) {
            Family.Diff -> scanDiff(code, spans)
            Family.Json -> scanJson(code, spans)
            Family.Yaml -> scanYaml(code, spans)
            Family.Toml -> scanToml(code, spans)
            Family.Markup -> scanMarkup(code, spans)
            Family.Css -> scanCss(code, spans)
            Family.Dockerfile -> scanDockerfile(code, spans)
            Family.Makefile -> scanMakefile(code, spans)
            Family.Markdown -> scanMarkdown(code, spans)
            Family.Mermaid -> scanMermaid(code, spans)
            Family.Csv -> scanCsv(code, delimiter = if (lang == "tsv") '\t' else ',', out = spans)
            Family.Sql -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = SQL_TYPES,
                hashComment = false,
                dashComment = true,
                slashComment = true,
                backtickString = false,
                dollarInterpolation = false,
                hashInterpolation = false,
                preprocessor = false,
                caseInsensitive = true,
            )
            Family.Python -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = builtinTypesFor(lang),
                hashComment = true,
                dashComment = false,
                slashComment = false,
                backtickString = false,
                dollarInterpolation = false,
                hashInterpolation = false,
                preprocessor = false,
                caseInsensitive = false,
                pythonFStrings = true,
            )
            Family.Ruby -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = emptySet(),
                hashComment = true,
                dashComment = false,
                slashComment = false,
                backtickString = true,
                dollarInterpolation = false,
                hashInterpolation = true,
                preprocessor = false,
                caseInsensitive = false,
            )
            Family.Lua -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = emptySet(),
                hashComment = false,
                dashComment = true,
                slashComment = false,
                backtickString = false,
                dollarInterpolation = false,
                hashInterpolation = false,
                preprocessor = false,
                caseInsensitive = false,
            )
            Family.Shell -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = emptySet(),
                hashComment = lang != "bat" && lang != "cmd",
                dashComment = false,
                slashComment = false,
                backtickString = true,
                dollarInterpolation = true,
                backtickDollarInterpolation = true,
                hashInterpolation = false,
                preprocessor = false,
                caseInsensitive = lang == "bat" || lang == "cmd" || lang == "ps1" || lang == "powershell",
            )
            Family.CLike -> scanCode(
                code = code,
                out = spans,
                keywords = keywordsFor(lang),
                builtinTypes = builtinTypesFor(lang),
                hashComment = lang == "php",
                dashComment = false,
                slashComment = true,
                backtickString = true,
                dollarInterpolation = lang in DOUBLE_QUOTE_DOLLAR_INTERP_LANGS,
                backtickDollarInterpolation = lang in BACKTICK_DOLLAR_INTERP_LANGS,
                hashInterpolation = false,
                preprocessor = lang in PREPROCESSOR_LANGS,
                caseInsensitive = false,
                rustAttributes = lang == "rs" || lang == "rust",
                rustLifetimes = lang == "rs" || lang == "rust",
                nestedBlockComments = lang in NESTED_COMMENT_LANGS,
                regexLiterals = lang in JS_REGEX_LANGS,
            )
        }
        return PiCodeHighlight(spans = spans, languageKnown = true)
    }

    // ------------------------------------------------------------------ Diff

    private fun scanDiff(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            if (end > i) {
                when {
                    code.startsWith("@@", i) -> out.add(PiCodeSpan(i, end, PiSyntaxToken.Keyword))
                    code.startsWith("diff ", i) || code.startsWith("index ", i) ->
                        out.add(PiCodeSpan(i, end, PiSyntaxToken.Type))
                    code.startsWith("+++", i) || code.startsWith("---", i) ->
                        out.add(PiCodeSpan(i, end, PiSyntaxToken.Muted))
                    code[i] == '+' -> out.add(PiCodeSpan(i, end, PiSyntaxToken.DiffAdded))
                    code[i] == '-' -> out.add(PiCodeSpan(i, end, PiSyntaxToken.DiffRemoved))
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    // ------------------------------------------------------------------ JSON / JSONC

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
                ch == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
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
                    out.add(PiCodeSpan(start, i.coerceAtMost(n), tok))
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
                ch in "{}[]:," -> {
                    out.add(PiCodeSpan(i, i + 1, PiSyntaxToken.Punctuation))
                    i++
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ YAML

    private fun scanYaml(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        var blockScalarParentIndent = -1
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            var p = i
            while (p < end && (code[p] == ' ' || code[p] == '\t')) p++
            val indent = p - i

            // Inside a YAML block scalar (`|` / `>`), every blank line or line indented deeper than
            // the parent key belongs to the literal string payload, not nested key: value mappings.
            if (blockScalarParentIndent >= 0) {
                if (p >= end) {
                    i = if (nl < 0) n else nl + 1
                    continue
                }
                if (indent > blockScalarParentIndent) {
                    out.add(PiCodeSpan(p, end, PiSyntaxToken.StringLiteral))
                    i = if (nl < 0) n else nl + 1
                    continue
                }
                blockScalarParentIndent = -1
            }

            when {
                p >= end -> Unit
                code[p] == '#' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
                code.startsWith("---", p) || code.startsWith("...", p) ->
                    out.add(PiCodeSpan(p, end, PiSyntaxToken.Punctuation))
                else -> {
                    var keyStart = p
                    if (code[p] == '-' && (p + 1 == end || code[p + 1] == ' ' || code[p + 1] == '\t')) {
                        out.add(PiCodeSpan(p, p + 1, PiSyntaxToken.Punctuation))
                        keyStart = p + 1
                        while (keyStart < end && (code[keyStart] == ' ' || code[keyStart] == '\t')) keyStart++
                    }
                    val colon = findYamlColon(code, keyStart, end)
                    if (colon >= 0) {
                        if (colon > keyStart) out.add(PiCodeSpan(keyStart, colon, PiSyntaxToken.Variable))
                        out.add(PiCodeSpan(colon, colon + 1, PiSyntaxToken.Punctuation))
                        val vStart = skipSpaces(code, colon + 1, end)
                        if (vStart < end && scanYamlScalar(code, vStart, end, out)) {
                            blockScalarParentIndent = indent
                        }
                    } else if (keyStart < end) {
                        if (scanYamlScalar(code, keyStart, end, out)) {
                            blockScalarParentIndent = indent
                        }
                    }
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    private fun findYamlColon(code: String, start: Int, end: Int): Int {
        var inSingle = false
        var inDouble = false
        var p = start
        while (p < end) {
            val c = code[p]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> {
                    if (p == start || code[p - 1] != '\\') inDouble = !inDouble
                }
                c == ':' && !inSingle && !inDouble -> {
                    if (p + 1 == end || code[p + 1] == ' ' || code[p + 1] == '\t') return p
                }
            }
            p++
        }
        return -1
    }

    /** Returns true when this scalar header opens a YAML block scalar (`|` / `>`). */
    private fun scanYamlScalar(code: String, v: Int, end: Int, out: MutableList<PiCodeSpan>): Boolean {
        val hash = findYamlComment(code, v, end)
        val vEnd = if (hash in v until end) hash else end
        val raw = code.substring(v, vEnd).trim()
        val isBlockHeader = isYamlBlockScalarHeader(raw)
        when {
            raw.isEmpty() -> Unit
            isBlockHeader ->
                out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.Operator))
            raw.startsWith("&") || raw.startsWith("*") ->
                out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.Type))
            raw.startsWith('"') || raw.startsWith('\'') ->
                out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.StringLiteral))
            raw == "true" || raw == "false" || raw == "null" || raw == "~" ||
                raw == "yes" || raw == "no" || raw.toDoubleOrNull() != null ->
                out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.Number))
            else -> out.add(PiCodeSpan(v, vEnd, PiSyntaxToken.StringLiteral))
        }
        if (hash in v until end) out.add(PiCodeSpan(hash, end, PiSyntaxToken.Comment))
        return isBlockHeader
    }

    private fun isYamlBlockScalarHeader(raw: String): Boolean {
        if (raw.isEmpty() || (raw[0] != '|' && raw[0] != '>')) return false
        for (idx in 1 until raw.length) {
            val c = raw[idx]
            if (c != '-' && c != '+' && c !in '1'..'9') return false
        }
        return raw.length <= 3
    }

    private fun findYamlComment(code: String, start: Int, end: Int): Int {
        var inSingle = false
        var inDouble = false
        var p = start
        while (p < end) {
            val c = code[p]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle && (p == start || code[p - 1] != '\\')) inDouble = !inDouble
            else if (c == '#' && !inSingle && !inDouble && p > start && (code[p - 1] == ' ' || code[p - 1] == '\t')) {
                return p
            }
            p++
        }
        return -1
    }

    // ------------------------------------------------------------------ TOML / INI

    private fun scanToml(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            val p = skipSpaces(code, i, end)
            if (p < end) {
                when (code[p]) {
                    '#', ';' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
                    '[' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Type))
                    else -> {
                        val eq = code.indexOf('=', p)
                        if (eq in p until end) {
                            out.add(PiCodeSpan(p, eq, PiSyntaxToken.Variable))
                            out.add(PiCodeSpan(eq, eq + 1, PiSyntaxToken.Operator))
                            val v = skipSpaces(code, eq + 1, end)
                            if (v < end) scanYamlScalar(code, v, end, out)
                        }
                    }
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    // ------------------------------------------------------------------ HTML / XML / SVG / Vue

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
            if (code[i] == '&') {
                val semi = code.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 10) && code.substring(i + 1, semi).all { it.isLetterOrDigit() || it == '#' }) {
                    out.add(PiCodeSpan(i, semi + 1, PiSyntaxToken.Number))
                    i = semi + 1
                    continue
                }
            }
            if (code[i] == '<') {
                val openStart = i
                var p = i + 1
                if (p < n && (code[p] == '/' || code[p] == '!' || code[p] == '?')) p++
                out.add(PiCodeSpan(openStart, p, PiSyntaxToken.Punctuation))
                val tagStart = p
                while (p < n && (code[p].isLetterOrDigit() || code[p] == '-' || code[p] == ':' || code[p] == '_')) p++
                val tagEnd = p
                if (tagEnd > tagStart) out.add(PiCodeSpan(tagStart, tagEnd, PiSyntaxToken.Keyword))
                while (p < n && code[p] != '>') {
                    when {
                        code[p] == '"' || code[p] == '\'' -> {
                            val q = code[p]
                            val s = p++
                            while (p < n && code[p] != q) p++
                            if (p < n) p++
                            out.add(PiCodeSpan(s, p, PiSyntaxToken.StringLiteral))
                        }
                        code[p] == '=' -> {
                            out.add(PiCodeSpan(p, p + 1, PiSyntaxToken.Operator))
                            p++
                        }
                        code[p] == '/' -> {
                            out.add(PiCodeSpan(p, p + 1, PiSyntaxToken.Punctuation))
                            p++
                        }
                        code[p].isLetter() || code[p] == '_' || code[p] == '@' || code[p] == ':' -> {
                            val a = p++
                            while (p < n && (code[p].isLetterOrDigit() || code[p] == '-' || code[p] == ':' || code[p] == '_' || code[p] == '.')) p++
                            out.add(PiCodeSpan(a, p, PiSyntaxToken.Variable))
                        }
                        else -> p++
                    }
                }
                val isClosingTag = openStart + 1 < n && code[openStart + 1] == '/'
                val isSelfClosing = p > openStart + 1 && code[p - 1] == '/'
                val tagName = if (tagEnd > tagStart) code.substring(tagStart, tagEnd).lowercase() else ""
                if (p < n && code[p] == '>') {
                    out.add(PiCodeSpan(p, p + 1, PiSyntaxToken.Punctuation))
                    p++
                }
                if (!isClosingTag && !isSelfClosing && (tagName == "script" || tagName == "style")) {
                    val closeToken = if (tagName == "script") "</script" else "</style"
                    val closeIdx = code.indexOf(closeToken, p, ignoreCase = true)
                    val bodyEnd = if (closeIdx < 0) n else closeIdx
                    if (bodyEnd > p) {
                        val sub = code.substring(p, bodyEnd)
                        val collector = OffsetSpanCollector(out, p)
                        if (tagName == "script") {
                            scanCode(
                                code = sub,
                                out = collector,
                                keywords = JS_KW,
                                builtinTypes = TS_TYPES,
                                hashComment = false,
                                dashComment = false,
                                slashComment = true,
                                backtickString = true,
                                dollarInterpolation = false,
                                backtickDollarInterpolation = true,
                                hashInterpolation = false,
                                preprocessor = false,
                                caseInsensitive = false,
                                regexLiterals = true,
                            )
                        } else {
                            scanCss(sub, collector)
                        }
                    }
                    i = bodyEnd
                    continue
                }
                i = p
                continue
            }
            i++
        }
    }

    // ------------------------------------------------------------------ CSS / SCSS / Less

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
                ch == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                }
                ch == '"' || ch == '\'' -> {
                    val s = i++
                    while (i < n && code[i] != ch) { if (code[i] == '\\') i++; i++ }
                    if (i < n) i++
                    out.add(PiCodeSpan(s, i.coerceAtMost(n), PiSyntaxToken.StringLiteral))
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
                ch == '.' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '-' || code[i + 1] == '_') -> {
                    val s = i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_')) i++
                    out.add(PiCodeSpan(s, i, PiSyntaxToken.Type))
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
                    when {
                        k < n && code[k] == ':' -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Variable))
                        k < n && code[k] == '(' -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Function))
                    }
                }
                ch in "{}()[];:," -> {
                    out.add(PiCodeSpan(i, i + 1, PiSyntaxToken.Punctuation))
                    i++
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ Dockerfile & Makefile

    private fun scanDockerfile(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            val p = skipSpaces(code, i, end)
            if (p < end) {
                if (code[p] == '#') {
                    out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
                } else {
                    var wEnd = p
                    while (wEnd < end && (code[wEnd].isLetter() || code[wEnd] == '_')) wEnd++
                    val word = code.substring(p, wEnd).uppercase()
                    if (word in DOCKER_INSTRUCTIONS) {
                        out.add(PiCodeSpan(p, wEnd, PiSyntaxToken.Keyword))
                        scanCode(
                            code = code.substring(wEnd, end),
                            out = OffsetSpanCollector(out, wEnd),
                            keywords = SHELL_KW,
                            builtinTypes = emptySet(),
                            hashComment = true,
                            dashComment = false,
                            slashComment = false,
                            backtickString = false,
                            dollarInterpolation = true,
                            hashInterpolation = false,
                            preprocessor = false,
                            caseInsensitive = false,
                        )
                    }
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    private fun scanMakefile(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            val p = skipSpaces(code, i, end)
            if (p < end) {
                when {
                    code[p] == '#' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
                    code[i] != '\t' && code.indexOf(':', p) in (p + 1) until end -> {
                        val colon = code.indexOf(':', p)
                        out.add(PiCodeSpan(p, colon, PiSyntaxToken.Function))
                        out.add(PiCodeSpan(colon, colon + 1, PiSyntaxToken.Punctuation))
                    }
                    else -> scanCode(
                        code = code.substring(p, end),
                        out = OffsetSpanCollector(out, p),
                        keywords = SHELL_KW,
                        builtinTypes = emptySet(),
                        hashComment = true,
                        dashComment = false,
                        slashComment = false,
                        backtickString = false,
                        dollarInterpolation = true,
                        hashInterpolation = false,
                        preprocessor = false,
                        caseInsensitive = false,
                    )
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    // ------------------------------------------------------------------ Markdown

    private fun scanMarkdown(code: String, out: MutableList<PiCodeSpan>) {
        var i = 0
        val n = code.length
        while (i < n) {
            val nl = code.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            val p = skipSpaces(code, i, end)
            if (p < end) {
                when {
                    code[p] == '#' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Keyword))
                    code.startsWith("```", p) || code.startsWith("~~~", p) ->
                        out.add(PiCodeSpan(p, end, PiSyntaxToken.Type))
                    code[p] == '>' -> out.add(PiCodeSpan(p, end, PiSyntaxToken.Comment))
                    else -> {
                        var k = p
                        while (k < end) {
                            when {
                                code[k] == '`' -> {
                                    val close = code.indexOf('`', k + 1)
                                    if (close in (k + 1) until end) {
                                        out.add(PiCodeSpan(k, close + 1, PiSyntaxToken.StringLiteral))
                                        k = close + 1
                                    } else k++
                                }
                                code.startsWith("**", k) -> {
                                    val close = code.indexOf("**", k + 2)
                                    if (close in (k + 2) until end) {
                                        out.add(PiCodeSpan(k, close + 2, PiSyntaxToken.Strong))
                                        k = close + 2
                                    } else k += 2
                                }
                                code[k] == '[' -> {
                                    val close = code.indexOf("](", k + 1)
                                    val parenEnd = if (close >= 0) code.indexOf(')', close + 2) else -1
                                    if (close in (k + 1) until end && parenEnd in (close + 2) until end) {
                                        out.add(PiCodeSpan(k + 1, close, PiSyntaxToken.Link))
                                        out.add(PiCodeSpan(close + 2, parenEnd, PiSyntaxToken.Muted))
                                        k = parenEnd + 1
                                    } else k++
                                }
                                else -> k++
                            }
                        }
                    }
                }
            }
            i = if (nl < 0) n else nl + 1
        }
    }

    // ------------------------------------------------------------------ General Code Lexer

    private fun scanCode(
        code: String,
        out: MutableList<PiCodeSpan>,
        keywords: Set<String>,
        builtinTypes: Set<String>,
        hashComment: Boolean,
        dashComment: Boolean,
        slashComment: Boolean,
        backtickString: Boolean,
        dollarInterpolation: Boolean,
        hashInterpolation: Boolean,
        preprocessor: Boolean,
        caseInsensitive: Boolean,
        pythonFStrings: Boolean = false,
        rustAttributes: Boolean = false,
        rustLifetimes: Boolean = false,
        nestedBlockComments: Boolean = false,
        regexLiterals: Boolean = false,
        backtickDollarInterpolation: Boolean = dollarInterpolation,
    ) {
        var i = 0
        val n = code.length
        var regexAllowed = true
        while (i < n) {
            val ch = code[i]
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                i++
                continue
            }

            // 1. Slash comments (line comments and block comments, with nesting support for Kotlin/Rust/Swift)
            if (slashComment && ch == '/' && i + 1 < n) {
                if (code[i + 1] == '/') {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    i = end
                    continue
                }
                if (code[i + 1] == '*') {
                    val end = findBlockCommentEnd(code, i, nestedBlockComments)
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                    if (i + 2 < end && code[i + 2] == '*') {
                        scanDocTags(code, i + 3, end, out)
                    }
                    i = end
                    continue
                }
            }

            // 1b. JS/TS regex literals (`/pattern/flags`) when `/` appears in value position
            if (regexLiterals && regexAllowed && ch == '/' && i + 1 < n && code[i + 1] != '/' && code[i + 1] != '*') {
                val regexEnd = scanJsRegexEnd(code, i)
                if (regexEnd > i) {
                    out.add(PiCodeSpan(i, regexEnd, PiSyntaxToken.StringLiteral))
                    i = regexEnd
                    regexAllowed = false
                    continue
                }
            }

            // 2. Rust attributes `#[derive(...)]` / `#![allow(...)]`
            if (rustAttributes && ch == '#' && i + 1 < n && (code[i + 1] == '[' || (code[i + 1] == '!' && i + 2 < n && code[i + 2] == '['))) {
                val close = code.indexOf(']', i + 2)
                val end = if (close < 0) n else close + 1
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Muted))
                i = end
                continue
            }

            // 3. C/C++/C# preprocessor directives (`#include`, `#define`, `#ifdef`, ...)
            if (preprocessor && ch == '#' && isLineLeading(code, i)) {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                var w = skipSpaces(code, i + 1, end)
                while (w < end && (code[w].isLetter() || code[w] == '_')) w++
                out.add(PiCodeSpan(i, w, PiSyntaxToken.Muted))
                val rest = skipSpaces(code, w, end)
                if (rest < end && (code[rest] == '<' || code[rest] == '"')) {
                    out.add(PiCodeSpan(rest, end, PiSyntaxToken.StringLiteral))
                    i = end
                } else {
                    i = w
                }
                continue
            }

            // 4. Hash comments (`# ...`)
            if (hashComment && ch == '#') {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                i = end
                continue
            }

            // 5. Dash comments (`-- ...`)
            if (dashComment && ch == '-' && i + 1 < n && code[i + 1] == '-') {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out.add(PiCodeSpan(i, end, PiSyntaxToken.Comment))
                i = end
                continue
            }

            // 6. Python prefixed strings (`f"..."`, `r"..."`, `b"..."`, `rf"""..."""`)
            if (pythonFStrings && (ch == 'f' || ch == 'F' || ch == 'r' || ch == 'R' || ch == 'b' || ch == 'B' || ch == 'u' || ch == 'U')) {
                var p = i
                var isFormat = false
                while (p < n && p - i < 2 && code[p] in "fFrRbBuU") {
                    if (code[p] == 'f' || code[p] == 'F') isFormat = true
                    p++
                }
                if (p < n && (code[p] == '"' || code[p] == '\'')) {
                    val q = code[p]
                    val isTriple = p + 2 < n && code[p + 1] == q && code[p + 2] == q
                    val contentStart = p + if (isTriple) 3 else 1
                    val end = scanStringEnd(code, contentStart, q, isTriple)
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.StringLiteral))
                    if (isFormat) scanBraceInterpolations(code, contentStart, (end - if (isTriple) 3 else 1).coerceAtLeast(contentStart), out)
                    i = end
                    regexAllowed = false
                    continue
                }
            }

            // 6b. Rust lifetimes (`'a`, `'static`, `'_`) vs char literals (`'x'`, `'\n'`, `'\u{1F600}'`)
            if (rustLifetimes && ch == '\'' && i + 1 < n) {
                val charEnd = matchRustCharLiteralEnd(code, i)
                if (charEnd > i) {
                    out.add(PiCodeSpan(i, charEnd, PiSyntaxToken.StringLiteral))
                    i = charEnd
                    regexAllowed = false
                    continue
                }
                val next = code[i + 1]
                if (next.isLetter() || next == '_') {
                    var end = i + 2
                    while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                    out.add(PiCodeSpan(i, end, PiSyntaxToken.Type))
                    i = end
                    regexAllowed = false
                    continue
                }
            }

            // 7. Triple-quoted strings (`"""..."""` / `'''...'''`)
            if ((ch == '"' || ch == '\'') && i + 2 < n && code[i + 1] == ch && code[i + 2] == ch) {
                val contentStart = i + 3
                val end = scanStringEnd(code, contentStart, ch, triple = true)
                out.add(PiCodeSpan(i, end, PiSyntaxToken.StringLiteral))
                if (dollarInterpolation && ch == '"') {
                    scanDollarInterpolations(code, contentStart, (end - 3).coerceAtLeast(contentStart), out)
                }
                i = end
                regexAllowed = false
                continue
            }

            // 8. Single / double / backtick strings (with language-accurate template interpolation)
            if (ch == '"' || ch == '\'' || (backtickString && ch == '`')) {
                val contentStart = i + 1
                val end = scanStringEnd(code, contentStart, ch, triple = false)
                out.add(PiCodeSpan(i, end, PiSyntaxToken.StringLiteral))
                val bodyEnd = (end - 1).coerceAtLeast(contentStart)
                if ((ch == '"' && dollarInterpolation) || (ch == '`' && backtickDollarInterpolation)) {
                    scanDollarInterpolations(code, contentStart, bodyEnd, out)
                } else if (hashInterpolation && ch == '"') {
                    scanHashInterpolations(code, contentStart, bodyEnd, out)
                }
                i = end
                regexAllowed = false
                continue
            }

            // 9. Annotations / decorators (`@Composable`, `@override`)
            if (ch == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
                val s = i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                out.add(PiCodeSpan(s, i, PiSyntaxToken.Muted))
                continue
            }

            // 10. Shell / PHP variables (`$VAR`, `${VAR}`)
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

            // 11. Numbers (0xFF, 0b1010, 0o77, 1_000, 3.14e-5f, 42u64)
            if (ch.isDigit() || (ch == '.' && i + 1 < n && code[i + 1].isDigit())) {
                val s = i++
                if (ch == '0' && i < n && (code[i] == 'x' || code[i] == 'X' || code[i] == 'b' || code[i] == 'B' || code[i] == 'o' || code[i] == 'O')) {
                    i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                } else {
                    while (i < n) {
                        val c = code[i]
                        when {
                            c.isLetterOrDigit() || c == '_' -> i++
                            c == '.' && i + 1 < n && code[i + 1].isDigit() -> i++
                            (c == '+' || c == '-') && i > s && (code[i - 1] == 'e' || code[i - 1] == 'E') -> i++
                            else -> break
                        }
                    }
                }
                out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                regexAllowed = false
                continue
            }

            // 12. Identifiers, keywords, built-in types, functions (incl. generic calls), PascalCase/type params, members
            if (ch.isLetter() || ch == '_') {
                val s = i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(s, i)
                val lookup = if (caseInsensitive) word.lowercase() else word
                val precededByDot = s > 0 && code[s - 1] == '.'
                val isKeyword = !precededByDot && lookup in keywords
                when {
                    isKeyword -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Keyword))
                    !precededByDot && lookup in LITERALS -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                    !caseInsensitive && i < n && code[i] == '!' && rustAttributes -> {
                        i++
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Function))
                    }
                    !caseInsensitive && (i < n && code[i] == '(' || isGenericCallAhead(code, i)) ->
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Function))
                    lookup in builtinTypes -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Type))
                    !caseInsensitive && word[0].isUpperCase() && word.any { it.isLowerCase() } ->
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Type))
                    !caseInsensitive && word.length == 1 && word[0] in 'A'..'Z' && isTypeParameterContext(code, s, i) ->
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Type))
                    !caseInsensitive && word.length >= 2 && word.all { it.isUpperCase() || it.isDigit() || it == '_' } ->
                        out.add(PiCodeSpan(s, i, PiSyntaxToken.Number))
                    precededByDot -> out.add(PiCodeSpan(s, i, PiSyntaxToken.Variable))
                }
                regexAllowed = isKeyword && lookup in REGEX_PREFIX_KEYWORDS
                continue
            }

            // 13. Multi-character & single-character operators
            val opLen = matchOperatorLength(code, i)
            if (opLen > 0) {
                out.add(PiCodeSpan(i, i + opLen, PiSyntaxToken.Operator))
                regexAllowed = !(opLen == 2 && (code[i] == '+' && code[i + 1] == '+' || code[i] == '-' && code[i + 1] == '-'))
                i += opLen
                continue
            }

            // 14. Structural punctuation
            if (ch in "{}[](),;.") {
                out.add(PiCodeSpan(i, i + 1, PiSyntaxToken.Punctuation))
                regexAllowed = ch in "{[(,;"
                i++
                continue
            }

            i++
        }
    }

    private fun scanStringEnd(code: String, from: Int, quote: Char, triple: Boolean): Int {
        val n = code.length
        if (triple) {
            var p = from
            while (p + 2 < n) {
                if (code[p] == '\\') {
                    p += 2
                    continue
                }
                if (code[p] == quote && code[p + 1] == quote && code[p + 2] == quote) {
                    return p + 3
                }
                p++
            }
            return n
        }
        var p = from
        while (p < n) {
            val c = code[p]
            if (c == '\\') {
                p += 2
                continue
            }
            if (c == quote) return p + 1
            if (quote != '`' && c == '\n') return p
            p++
        }
        return n
    }

    private fun findBlockCommentEnd(code: String, start: Int, nested: Boolean): Int {
        val n = code.length
        if (!nested) {
            val close = code.indexOf("*/", start + 2)
            return if (close < 0) n else close + 2
        }
        var depth = 1
        var p = start + 2
        while (p + 1 < n && depth > 0) {
            if (code[p] == '/' && code[p + 1] == '*') {
                depth++
                p += 2
            } else if (code[p] == '*' && code[p + 1] == '/') {
                depth--
                p += 2
            } else {
                p++
            }
        }
        return if (depth == 0) p else n
    }

    private fun matchRustCharLiteralEnd(code: String, open: Int): Int {
        val n = code.length
        if (open + 2 >= n) return -1
        var p = open + 1
        if (code[p] == '\n' || code[p] == '\'') return -1
        if (code[p] == '\\') {
            p++
            if (p >= n) return -1
            when (code[p]) {
                'x' -> p += 3
                'u' -> {
                    if (p + 1 < n && code[p + 1] == '{') {
                        val closeBrace = code.indexOf('}', p + 2)
                        if (closeBrace < 0 || closeBrace - p > 9) return -1
                        p = closeBrace + 1
                    } else {
                        return -1
                    }
                }
                else -> p++
            }
        } else {
            p++
        }
        return if (p < n && code[p] == '\'') p + 1 else -1
    }

    private fun scanJsRegexEnd(code: String, open: Int): Int {
        val n = code.length
        var p = open + 1
        var inCharClass = false
        while (p < n) {
            val c = code[p]
            if (c == '\n' || c == '\r') return -1
            if (c == '\\') {
                p += 2
                continue
            }
            if (c == '[' && !inCharClass) {
                inCharClass = true
                p++
                continue
            }
            if (c == ']' && inCharClass) {
                inCharClass = false
                p++
                continue
            }
            if (c == '/' && !inCharClass) {
                p++
                while (p < n && code[p] in "gimsuydv") p++
                return p
            }
            p++
        }
        return -1
    }

    /** Matches generic function calls such as `remember<State>(...)` or `parse<List<Int>>(...)`. */
    private fun isGenericCallAhead(code: String, afterIdent: Int): Boolean {
        val n = code.length
        if (afterIdent >= n || code[afterIdent] != '<') return false
        var depth = 0
        var p = afterIdent
        val limit = (afterIdent + 64).coerceAtMost(n)
        while (p < limit) {
            val c = code[p]
            when {
                c == '<' -> depth++
                c == '>' -> {
                    depth--
                    if (depth == 0) {
                        var next = p + 1
                        while (next < n && (code[next] == ' ' || code[next] == '\t')) next++
                        return next < n && code[next] == '('
                    }
                }
                c == '\n' || c == ';' || c == '{' || c == '}' -> return false
            }
            p++
        }
        return false
    }

    /** Matches single-letter uppercase generic type parameters such as `<T>`, `: T`, `, K>`. */
    private fun isTypeParameterContext(code: String, start: Int, end: Int): Boolean {
        var prev = start - 1
        while (prev >= 0 && (code[prev] == ' ' || code[prev] == '\t')) prev--
        var next = end
        while (next < code.length && (code[next] == ' ' || code[next] == '\t')) next++
        val prevChar = if (prev >= 0) code[prev] else ' '
        val nextChar = if (next < code.length) code[next] else ' '
        if (prevChar == '<' && (nextChar == '>' || nextChar == ',' || nextChar == ':' || nextChar == '?')) return true
        if ((prevChar == ':' || prevChar == ',') && (nextChar == '>' || nextChar == ',' || nextChar == '?' || nextChar == ')' || nextChar == '{' || nextChar == '\n')) return true
        return false
    }

    private fun scanDocTags(code: String, start: Int, end: Int, out: MutableList<PiCodeSpan>) {
        var p = start
        while (p < end) {
            if (code[p] == '@' && p + 1 < end && code[p + 1].isLetter()) {
                val s = p++
                while (p < end && (code[p].isLetterOrDigit() || code[p] == '_')) p++
                out.add(PiCodeSpan(s, p, PiSyntaxToken.Keyword))
            } else {
                p++
            }
        }
    }

    private fun scanDollarInterpolations(code: String, start: Int, end: Int, out: MutableList<PiCodeSpan>) {
        var p = start
        while (p < end) {
            if (code[p] == '\\') { p += 2; continue }
            if (code[p] == '$' && p + 1 < end) {
                if (code[p + 1] == '{') {
                    val s = p
                    var depth = 1
                    p += 2
                    while (p < end && depth > 0) {
                        if (code[p] == '{') depth++ else if (code[p] == '}') depth--
                        p++
                    }
                    out.add(PiCodeSpan(s, p.coerceAtMost(end), PiSyntaxToken.Variable))
                    continue
                } else if (code[p + 1].isLetter() || code[p + 1] == '_') {
                    val s = p++
                    while (p < end && (code[p].isLetterOrDigit() || code[p] == '_')) p++
                    out.add(PiCodeSpan(s, p, PiSyntaxToken.Variable))
                    continue
                }
            }
            p++
        }
    }

    private fun scanHashInterpolations(code: String, start: Int, end: Int, out: MutableList<PiCodeSpan>) {
        var p = start
        while (p < end - 1) {
            if (code[p] == '\\') { p += 2; continue }
            if (code[p] == '#' && code[p + 1] == '{') {
                val s = p
                var depth = 1
                p += 2
                while (p < end && depth > 0) {
                    if (code[p] == '{') depth++ else if (code[p] == '}') depth--
                    p++
                }
                out.add(PiCodeSpan(s, p.coerceAtMost(end), PiSyntaxToken.Variable))
                continue
            }
            p++
        }
    }

    private fun scanBraceInterpolations(code: String, start: Int, end: Int, out: MutableList<PiCodeSpan>) {
        var p = start
        while (p < end) {
            if (code[p] == '{') {
                if (p + 1 < end && code[p + 1] == '{') { p += 2; continue }
                val s = p
                var depth = 1
                p++
                while (p < end && depth > 0) {
                    if (code[p] == '{') depth++ else if (code[p] == '}') depth--
                    p++
                }
                out.add(PiCodeSpan(s, p.coerceAtMost(end), PiSyntaxToken.Variable))
                continue
            }
            p++
        }
    }

    private fun matchOperatorLength(code: String, i: Int): Int {
        val n = code.length
        val c = code[i]
        val next = if (i + 1 < n) code[i + 1] else '\u0000'
        val next2 = if (i + 2 < n) code[i + 2] else '\u0000'
        if ((c == '=' && next == '=' && next2 == '=') ||
            (c == '!' && next == '=' && next2 == '=') ||
            (c == '.' && next == '.' && next2 == '.') ||
            (c == '<' && next == '<' && next2 == '=') ||
            (c == '>' && next == '>' && next2 == '=')
        ) return 3
        if ((c == '-' && next == '>') ||
            (c == '=' && next == '>') ||
            (c == ':' && next == ':') ||
            (c == ':' && next == '=') ||
            (c == '=' && next == '=') ||
            (c == '!' && next == '=') ||
            (c == '<' && next == '=') ||
            (c == '>' && next == '=') ||
            (c == '&' && next == '&') ||
            (c == '|' && next == '|') ||
            (c == '?' && next == '?') ||
            (c == '?' && next == ':') ||
            (c == '?' && next == '.') ||
            (c == '.' && next == '.') ||
            (c == '+' && (next == '=' || next == '+')) ||
            (c == '-' && (next == '=' || next == '-')) ||
            (c == '*' && (next == '=' || next == '*')) ||
            (c == '/' && next == '=') ||
            (c == '%' && next == '=') ||
            (c == '<' && next == '<') ||
            (c == '>' && next == '>')
        ) return 2
        if (c in "+-*/%=!<>&|^~?:") return 1
        return 0
    }

    private fun isLineLeading(code: String, index: Int): Boolean {
        var p = index - 1
        while (p >= 0 && code[p] != '\n') {
            if (code[p] != ' ' && code[p] != '\t') return false
            p--
        }
        return true
    }

    private fun skipSpaces(code: String, from: Int, end: Int): Int {
        var p = from
        while (p < end && (code[p] == ' ' || code[p] == '\t')) p++
        return p
    }

    /** Wraps an output list to shift span offsets when delegating a substring slice. */
    private class OffsetSpanCollector(
        private val target: MutableList<PiCodeSpan>,
        private val offset: Int,
    ) : AbstractMutableList<PiCodeSpan>() {
        override val size: Int get() = target.size
        override fun get(index: Int): PiCodeSpan = target[index]
        override fun add(index: Int, element: PiCodeSpan) {
            target.add(index, PiCodeSpan(element.start + offset, element.end + offset, element.token))
        }
        override fun removeAt(index: Int): PiCodeSpan = target.removeAt(index)
        override fun set(index: Int, element: PiCodeSpan): PiCodeSpan = target.set(index, element)
    }

    // ------------------------------------------------------------------ Language tables

    private val DOUBLE_QUOTE_DOLLAR_INTERP_LANGS = setOf(
        "kt", "kotlin", "dart", "groovy", "gradle", "php",
    )

    private val BACKTICK_DOLLAR_INTERP_LANGS = setOf(
        "ts", "typescript", "tsx", "js", "javascript", "jsx", "mjs", "cjs",
    )

    private val NESTED_COMMENT_LANGS = setOf(
        "kt", "kotlin", "rs", "rust", "swift",
    )

    private val JS_REGEX_LANGS = setOf(
        "ts", "typescript", "tsx", "js", "javascript", "jsx", "mjs", "cjs",
    )

    private val REGEX_PREFIX_KEYWORDS = setOf(
        "return", "case", "throw", "typeof", "void", "delete", "in", "of", "yield", "await", "new",
    )

    private val PREPROCESSOR_LANGS = setOf(
        "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "cs", "csharp", "c#", "objc", "objectivec", "glsl",
    )

    private val LITERALS = setOf(
        "true", "false", "null", "nil", "none", "None", "True", "False", "undefined", "NaN", "Infinity",
    )

    private val DOCKER_INSTRUCTIONS = setOf(
        "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY",
        "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL",
    )

    private val KOTLIN_KW = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else",
        "when", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally",
        "throw", "in", "is", "as", "by", "private", "public", "internal", "protected", "override",
        "open", "abstract", "sealed", "data", "enum", "companion", "suspend", "inline", "reified",
        "const", "lateinit", "vararg", "tailrec", "operator", "infix", "init", "constructor",
        "this", "super", "typealias", "expect", "actual", "annotation", "value", "where", "out",
        "noinline", "crossinline", "external", "inner",
    )

    private val KOTLIN_TYPES = setOf(
        "Unit", "Any", "Nothing", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double",
        "Char", "String", "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet",
        "Pair", "Triple", "Result", "Flow", "StateFlow", "Sequence", "Array", "ByteArray", "IntArray",
    )

    private val JAVA_KW = setOf(
        "package", "import", "class", "interface", "enum", "record", "extends", "implements",
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "transient", "native", "void", "boolean", "byte", "char", "short", "int", "long", "float",
        "double", "if", "else", "switch", "case", "default", "for", "while", "do", "break",
        "continue", "return", "try", "catch", "finally", "throw", "throws", "new", "this", "super",
        "instanceof", "var", "sealed", "permits", "yield", "def", "trait", "val", "match", "object",
    )

    private val JS_KW = setOf(
        "import", "export", "from", "default", "const", "let", "var", "function", "async", "await",
        "return", "if", "else", "switch", "case", "for", "of", "in", "while", "do", "break",
        "continue", "try", "catch", "finally", "throw", "new", "class", "extends", "implements",
        "interface", "type", "enum", "namespace", "public", "private", "protected", "readonly",
        "abstract", "static", "this", "super", "typeof", "instanceof", "keyof", "as", "is",
        "yield", "delete", "void", "declare", "module", "satisfies", "infer", "override", "get", "set",
    )

    private val TS_TYPES = setOf(
        "string", "number", "boolean", "bigint", "symbol", "unknown", "never", "any", "object",
        "Promise", "Array", "Record", "Partial", "Required", "Readonly", "Pick", "Omit", "Map", "Set",
        "Error", "Date", "RegExp", "Uint8Array", "Buffer",
    )

    private val PY_KW = setOf(
        "import", "from", "as", "def", "class", "return", "yield", "async", "await", "if", "elif",
        "else", "for", "in", "while", "break", "continue", "pass", "try", "except", "finally",
        "raise", "with", "lambda", "and", "or", "not", "is", "del", "global", "nonlocal", "assert",
        "match", "case", "self", "cls",
    )

    private val PY_TYPES = setOf(
        "int", "float", "str", "bool", "bytes", "list", "dict", "set", "tuple", "object",
        "Any", "Optional", "Union", "Callable", "Iterable", "Iterator", "Sequence", "Mapping",
    )

    private val RUBY_KW = setOf(
        "def", "end", "class", "module", "if", "elsif", "else", "unless", "case", "when", "while",
        "until", "for", "in", "do", "begin", "rescue", "ensure", "raise", "return", "yield", "break",
        "next", "redo", "retry", "self", "super", "require", "require_relative", "include", "extend",
        "attr_reader", "attr_writer", "attr_accessor", "and", "or", "not", "then", "fn", "defp", "defmodule",
    )

    private val LUA_KW = setOf(
        "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
        "local", "not", "or", "repeat", "return", "then", "until", "while",
    )

    private val RUST_KW = setOf(
        "use", "mod", "pub", "crate", "super", "self", "Self", "fn", "let", "mut", "const", "static",
        "struct", "enum", "trait", "impl", "for", "in", "while", "loop", "if", "else", "match",
        "return", "break", "continue", "async", "await", "move", "ref", "type", "where", "unsafe",
        "extern", "dyn", "as", "box", "macro_rules",
    )

    private val RUST_TYPES = setOf(
        "bool", "char", "str", "i8", "i16", "i32", "i64", "i128", "isize",
        "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
        "String", "Vec", "Option", "Result", "Box", "Rc", "Arc", "HashMap", "HashSet", "BTreeMap",
    )

    private val GO_KW = setOf(
        "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan",
        "if", "else", "switch", "case", "default", "for", "range", "break", "continue", "return",
        "go", "defer", "select", "goto", "fallthrough", "make", "new", "len", "cap", "append", "delete",
    )

    private val GO_TYPES = setOf(
        "bool", "byte", "rune", "string", "error", "any", "int", "int8", "int16", "int32", "int64",
        "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "float32", "float64", "complex64", "complex128",
    )

    private val CPP_KW = setOf(
        "auto", "break", "case", "catch", "class", "const", "constexpr", "consteval", "continue",
        "default", "delete", "do", "else", "enum", "explicit", "export", "extern", "for", "friend",
        "goto", "if", "inline", "mutable", "namespace", "new", "noexcept", "nullptr", "operator",
        "private", "protected", "public", "register", "return", "sizeof", "static", "static_assert",
        "struct", "switch", "template", "this", "throw", "try", "typedef", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "while", "override", "final", "co_await", "co_return",
    )

    private val C_TYPES = setOf(
        "void", "bool", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
        "size_t", "ssize_t", "ptrdiff_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "usize",
    )

    private val CSHARP_KW = setOf(
        "using", "namespace", "class", "struct", "record", "interface", "enum", "public", "private",
        "protected", "internal", "static", "readonly", "const", "virtual", "override", "abstract",
        "sealed", "partial", "async", "await", "var", "void", "if", "else", "switch", "case",
        "default", "for", "foreach", "in", "while", "do", "break", "continue", "return", "try",
        "catch", "finally", "throw", "new", "this", "base", "is", "as", "typeof", "nameof", "get", "set", "init",
    )

    private val SWIFT_KW = setOf(
        "import", "class", "struct", "enum", "protocol", "extension", "actor", "func", "init", "deinit",
        "let", "var", "if", "else", "guard", "switch", "case", "default", "for", "in", "while", "repeat",
        "break", "continue", "return", "throw", "throws", "rethrows", "try", "catch", "do", "defer",
        "async", "await", "public", "private", "fileprivate", "internal", "open", "static", "override",
        "final", "mutating", "weak", "unowned", "self", "Self", "super", "typealias", "associatedtype", "where", "as", "is",
    )

    private val DART_KW = setOf(
        "import", "export", "part", "library", "class", "mixin", "enum", "extension", "typedef",
        "extends", "implements", "with", "on", "abstract", "sealed", "final", "const", "var", "late",
        "required", "static", "void", "if", "else", "switch", "case", "default", "for", "in", "while",
        "do", "break", "continue", "return", "try", "catch", "finally", "throw", "rethrow", "async",
        "await", "sync", "yield", "this", "super", "new", "is", "as", "get", "set", "factory",
    )

    private val ZIG_KW = setOf(
        "const", "var", "fn", "pub", "return", "if", "else", "switch", "while", "for", "break",
        "continue", "defer", "errdefer", "try", "catch", "orelse", "struct", "enum", "union", "error",
        "comptime", "inline", "extern", "packed", "export", "test", "unreachable", "undefined", "and", "or",
    )

    private val PHP_KW = setOf(
        "namespace", "use", "class", "interface", "trait", "enum", "extends", "implements", "function",
        "fn", "public", "private", "protected", "static", "final", "abstract", "readonly", "const",
        "if", "elseif", "else", "switch", "case", "default", "match", "for", "foreach", "as", "while",
        "do", "break", "continue", "return", "try", "catch", "finally", "throw", "new", "echo", "print",
    )

    private val GRAPHQL_KW = setOf(
        "query", "mutation", "subscription", "fragment", "on", "type", "interface", "union", "enum",
        "input", "scalar", "extend", "implements", "directive", "schema", "repeatable",
    )

    private val PROTO_KW = setOf(
        "syntax", "edition", "package", "import", "public", "weak", "option", "message", "enum",
        "service", "rpc", "returns", "stream", "oneof", "map", "reserved", "repeated", "optional", "required",
    )

    private val JVM_SWIFT_TYPES = setOf(
        "String", "Int", "Double", "Float", "Bool", "Boolean", "Void", "List", "Map", "Set",
        "Array", "Dictionary", "Optional", "Future", "Stream", "Task", "Result",
    )

    private val ELIXIR_KW = setOf(
        "def", "defp", "defmodule", "defstruct", "defprotocol", "defimpl", "defmacro", "defmacrop",
        "do", "end", "fn", "if", "unless", "else", "case", "cond", "with", "for", "try", "rescue",
        "catch", "after", "raise", "receive", "import", "alias", "require", "use", "when", "in",
        "and", "or", "not", "quote", "unquote",
    )

    private val PERL_KW = setOf(
        "sub", "my", "our", "local", "state", "use", "require", "package", "if", "elsif", "else",
        "unless", "while", "until", "for", "foreach", "next", "last", "redo", "return", "die",
        "warn", "print", "say", "eval", "begin", "end", "and", "or", "not", "eq", "ne", "gt", "lt",
    )

    private val R_KW = setOf(
        "function", "if", "else", "repeat", "while", "for", "in", "next", "break", "return",
        "library", "require", "source", "NA", "NaN", "Inf", "NULL", "TRUE", "FALSE",
    )

    private val SHELL_KW = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case",
        "esac", "function", "return", "exit", "local", "export", "readonly", "set", "unset",
        "shift", "trap", "exec", "eval", "source", "echo", "printf", "cd", "pwd", "test",
    )

    private val POWERSHELL_KW = setOf(
        "function", "param", "begin", "process", "end", "if", "elseif", "else", "switch", "for",
        "foreach", "while", "do", "until", "break", "continue", "return", "try", "catch", "finally",
        "throw", "class", "enum", "using", "filter", "in", "not", "and", "or", "eq", "ne", "gt", "lt",
    )

    private val BATCH_KW = setOf(
        "echo", "set", "setlocal", "endlocal", "if", "else", "not", "exist", "defined", "for", "in",
        "do", "call", "goto", "exit", "rem", "pause", "shift", "start", "cd", "dir", "copy", "del",
    )

    private val SQL_KW = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "index", "view", "drop", "alter", "add", "column", "join", "inner", "left", "right",
        "outer", "full", "on", "as", "and", "or", "not", "in", "is", "like", "ilike", "between",
        "exists", "group", "by", "order", "asc", "desc", "having", "limit", "offset", "distinct",
        "union", "all", "case", "when", "then", "else", "end", "primary", "key", "foreign",
        "references", "begin", "commit", "rollback", "with", "returning", "conflict", "replace",
    )

    private val SQL_TYPES = setOf(
        "int", "integer", "bigint", "smallint", "serial", "bigserial", "text", "varchar", "char",
        "boolean", "bool", "real", "double", "numeric", "decimal", "date", "time", "timestamp",
        "timestamptz", "json", "jsonb", "uuid", "blob", "bytea",
    )

    private fun scanMermaid(code: String, out: MutableList<PiCodeSpan>) {
        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]
            // Mermaid comments: `%% ...`
            if (c == '%' && i + 1 < n && code[i + 1] == '%') {
                val start = i
                while (i < n && code[i] != '\n') i++
                out.add(PiCodeSpan(start, i, PiSyntaxToken.Comment))
                continue
            }
            // Quoted labels `"..."`
            if (c == '"') {
                val start = i
                i++
                while (i < n && code[i] != '\n') {
                    if (code[i] == '\\' && i + 1 < n) {
                        i += 2
                    } else if (code[i] == '"') {
                        i++
                        break
                    } else {
                        i++
                    }
                }
                out.add(PiCodeSpan(start, i, PiSyntaxToken.StringLiteral))
                continue
            }
            // Arrows & edge connectors (`-->`, `-.->`, `==>`, `-->>`, `->>`, `<-->`, `---`, `===`)
            if ((c == '-' || c == '=' || c == '<') && i + 1 < n) {
                val arrowEnd = matchMermaidArrowEnd(code, i)
                if (arrowEnd > i) {
                    out.add(PiCodeSpan(i, arrowEnd, PiSyntaxToken.Operator))
                    i = arrowEnd
                    continue
                }
            }
            // Hex colors (`#rrggbb`)
            if (c == '#' && i + 3 < n && isFastHexDigit(code[i + 1])) {
                val start = i
                i++
                while (i < n && isFastHexDigit(code[i])) i++
                out.add(PiCodeSpan(start, i, PiSyntaxToken.Number))
                continue
            }
            // Identifiers & Mermaid keywords / directions
            if (isFastIdentStart(c)) {
                val start = i
                i++
                while (i < n && (isFastIdentPart(code[i]) || code[i] == '-')) i++
                val word = code.substring(start, i)
                when {
                    word in MERMAID_KW -> out.add(PiCodeSpan(start, i, PiSyntaxToken.Keyword))
                    word in MERMAID_DIRS -> out.add(PiCodeSpan(start, i, PiSyntaxToken.Type))
                }
                continue
            }
            // Numbers
            if (c.isDigit()) {
                val start = i
                while (i < n && (code[i].isDigit() || code[i] == '.')) i++
                out.add(PiCodeSpan(start, i, PiSyntaxToken.Number))
                continue
            }
            if (c in "[](){}|:;,.~") {
                out.add(PiCodeSpan(i, i + 1, PiSyntaxToken.Punctuation))
            }
            i++
        }
    }

    private fun isFastHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isFastIdentStart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_'

    private fun isFastIdentPart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun matchMermaidArrowEnd(code: String, start: Int): Int {
        val n = code.length
        var i = start
        if (code[i] == '<') i++
        var dashes = 0
        while (i < n && (code[i] == '-' || code[i] == '=' || code[i] == '.')) {
            dashes++
            i++
        }
        if (dashes < 1) return start
        if (i < n && (code[i] == '>' || code[i] == 'o' || code[i] == 'x')) {
            i++
            if (i < n && code[i] == '>') i++
            return i
        }
        return if (dashes >= 3) i else start
    }

    private fun scanCsv(code: String, delimiter: Char, out: MutableList<PiCodeSpan>) {
        val n = code.length
        var i = 0
        var rowIndex = 0
        while (i < n) {
            val lineStart = i
            var lineEnd = i
            while (lineEnd < n && code[lineEnd] != '\n') lineEnd++
            var p = lineStart
            while (p < lineEnd) {
                val c = code[p]
                if (c == delimiter) {
                    out.add(PiCodeSpan(p, p + 1, PiSyntaxToken.Punctuation))
                    p++
                    continue
                }
                if (c == '"') {
                    val start = p
                    p++
                    while (p < lineEnd) {
                        if (code[p] == '"') {
                            if (p + 1 < lineEnd && code[p + 1] == '"') {
                                p += 2
                            } else {
                                p++
                                break
                            }
                        } else {
                            p++
                        }
                    }
                    out.add(
                        PiCodeSpan(
                            start,
                            p,
                            if (rowIndex == 0) PiSyntaxToken.Keyword else PiSyntaxToken.StringLiteral,
                        ),
                    )
                    continue
                }
                val start = p
                while (p < lineEnd && code[p] != delimiter) p++
                val token = code.substring(start, p).trim()
                if (token.isNotEmpty()) {
                    val role = when {
                        rowIndex == 0 -> PiSyntaxToken.Keyword
                        isCsvNumberOrBool(token) -> PiSyntaxToken.Number
                        else -> PiSyntaxToken.Variable
                    }
                    out.add(PiCodeSpan(start, p, role))
                }
            }
            if (lineEnd > lineStart) rowIndex++
            i = if (lineEnd < n) lineEnd + 1 else n
        }
    }

    private fun isCsvNumberOrBool(token: String): Boolean {
        val lower = token.lowercase()
        if (lower == "true" || lower == "false" || lower == "null") return true
        var i = 0
        if (token[i] == '-' || token[i] == '+') i++
        if (i >= token.length) return false
        var digits = 0
        var dots = 0
        while (i < token.length) {
            val ch = token[i]
            when {
                ch.isDigit() -> digits++
                ch == '.' && dots == 0 -> dots++
                ch == '%' && i == token.length - 1 -> Unit
                else -> return false
            }
            i++
        }
        return digits > 0
    }

    private val MERMAID_KW = setOf(
        "graph", "flowchart", "sequenceDiagram", "classDiagram", "stateDiagram", "stateDiagram-v2",
        "erDiagram", "gantt", "pie", "journey", "gitGraph", "mindmap", "timeline", "quadrantChart",
        "requirementDiagram", "C4Context", "C4Container", "C4Component", "sankey-beta", "xychart-beta",
        "subgraph", "end", "participant", "actor", "activate", "deactivate", "Note", "note", "over",
        "left", "right", "of", "loop", "alt", "else", "opt", "par", "and", "critical", "option",
        "break", "rect", "autonumber", "class", "classDef", "style", "linkStyle", "click", "direction",
        "state", "title", "section", "dateFormat", "axisFormat", "excludes", "includes", "accTitle",
        "accDescr", "as",
    )

    private val MERMAID_DIRS = setOf("TD", "TB", "BT", "RL", "LR")

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
