package app.pi.ui.render

import androidx.compose.ui.graphics.Color
import app.pi.ui.theme.PiPalette

/**
 * The syntax tokens pi colours code with, transcribed from
 * `packages/coding-agent/src/modes/interactive/theme/theme.ts` → `buildCliHighlightTheme`.
 *
 * pi drives highlight.js and maps each highlight.js *scope* onto one of its own
 * theme colours. This enum is that mapping's target: 9 palette colours plus 3
 * pure decorations. Keeping the enum at pi's level — rather than exposing a
 * backend's own colour model — is what lets the app swap the highlighter without
 * the palette drifting away from pi's tokens.
 *
 * Two scopes often share a token (`literal` and `number` are both
 * [Number]); that is pi's own collapse, not an approximation made here.
 */
internal enum class PiSyntaxToken {
    /** `comment`, `doctag` → `syntaxComment`. */
    Comment,

    /** `keyword`, `name` → `syntaxKeyword`. */
    Keyword,

    /** `function`, `title` → `syntaxFunction`. */
    Function,

    /** `attr`, `variable`, `params` → `syntaxVariable`. */
    Variable,

    /** `string`, `regexp` → `syntaxString`. */
    StringLiteral,

    /** `number`, `literal` → `syntaxNumber`. */
    Number,

    /** `built_in`, `class`, `type` → `syntaxType`. */
    Type,

    /** `operator` → `syntaxOperator`. */
    Operator,

    /** `tag`, `punctuation` → `syntaxPunctuation`. */
    Punctuation,

    /** `meta` → `muted`. */
    Muted,

    /** `addition` → `toolDiffAdded`. */
    DiffAdded,

    /** `deletion` → `toolDiffRemoved`. */
    DiffRemoved,

    // The three entries below carry no colour of their own in pi: they only add
    // a decoration to whatever colour the surrounding code already has.
    /** `emphasis` → italic. */
    Emphasis,

    /** `strong` → bold. */
    Strong,

    /** `link` → underline. */
    Link,
}

/**
 * The colour a token resolves to, or `null` when the token is decoration-only.
 * `@ReadOnlyComposable`-free on purpose: this is called from ordinary code paths
 * (a highlighter running off the main thread) as well as from composition.
 */
internal fun PiSyntaxToken.color(palette: PiPalette): Color? = when (this) {
    PiSyntaxToken.Comment -> palette.syntaxComment
    PiSyntaxToken.Keyword -> palette.syntaxKeyword
    PiSyntaxToken.Function -> palette.syntaxFunction
    PiSyntaxToken.Variable -> palette.syntaxVariable
    PiSyntaxToken.StringLiteral -> palette.syntaxString
    PiSyntaxToken.Number -> palette.syntaxNumber
    PiSyntaxToken.Type -> palette.syntaxType
    PiSyntaxToken.Operator -> palette.syntaxOperator
    PiSyntaxToken.Punctuation -> palette.syntaxPunctuation
    PiSyntaxToken.Muted -> palette.muted
    PiSyntaxToken.DiffAdded -> palette.toolDiffAdded
    PiSyntaxToken.DiffRemoved -> palette.toolDiffRemoved
    PiSyntaxToken.Emphasis, PiSyntaxToken.Strong, PiSyntaxToken.Link -> null
}

/** Half-open character range `[start, end)` carrying a [PiSyntaxToken]. */
internal data class PiCodeSpan(val start: Int, val end: Int, val token: PiSyntaxToken)

/**
 * The seam between the transcript and whatever actually colours code.
 *
 * It is deliberately synchronous and dependency-free: whichever backend wins
 * (an embedded highlight.js, a TextMate engine, or nothing at all) must be
 * callable from a background dispatcher with no Compose or Android context, so
 * that a code block can be highlighted once and cached as plain spans.
 */
internal interface PiCodeHighlighter {
    /**
     * @param code the fence body, exactly as the model emitted it.
     * @param language the fence's info string, already normalised by
     *   [PiCodeLanguage.normalize], or `null` when it is absent or unknown.
     * @return spans in any order; they are applied in order, so a later span
     *   that overlaps an earlier one wins. Empty means "no highlighting", which
     *   renders the block in `mdCodeBlock` — pi's behaviour for every language it
     *   does not recognise.
     */
    fun highlight(code: String, language: String?): List<PiCodeSpan>
}

/**
 * pi's fallback: when the fence has no language, or a language highlight.js does
 * not know, it skips highlighting entirely rather than guessing. Its comment on
 * that choice is explicit — "cli-highlight's auto-detection is unreliable and
 * can misidentify prose as AppleScript, LiveCodeServer, etc., coloring random
 * English words as keywords."
 *
 * This is also the current default while the highlighter backend is undecided,
 * so the app renders correct, uncoloured code rather than wrong colours.
 */
internal object PiPlainCodeHighlighter : PiCodeHighlighter {
    override fun highlight(code: String, language: String?): List<PiCodeSpan> = emptyList()
}

/**
 * Fence-info normalisation, ported from pi's `getLanguageFromPath` map in
 * `packages/coding-agent/src/utils/syntax-highlight.ts` plus the aliases a model
 * actually writes into a fence.
 *
 * pi resolves a language from a *file extension* first (`ts` → typescript) and
 * then hands the name to highlight.js, which knows both `ts` and `typescript`.
 * A fence carries no file, so the fence string is treated as either form.
 */
internal object PiCodeLanguage {

    private val aliases: Map<String, String> = buildMap {
        // The extension table, verbatim from pi.
        put("ts", "typescript"); put("tsx", "typescript")
        put("js", "javascript"); put("jsx", "javascript")
        put("mjs", "javascript"); put("cjs", "javascript")
        put("py", "python")
        put("rb", "ruby")
        put("rs", "rust")
        put("go", "go")
        put("java", "java")
        put("kt", "kotlin"); put("kts", "kotlin")
        put("swift", "swift")
        put("c", "c"); put("h", "c")
        put("cpp", "cpp"); put("cc", "cpp"); put("cxx", "cpp"); put("hpp", "cpp")
        put("cs", "csharp")
        put("php", "php")
        put("sh", "bash"); put("bash", "bash"); put("zsh", "bash")
        put("fish", "fish")
        put("ps1", "powershell")
        put("sql", "sql")
        put("html", "html"); put("htm", "html")
        put("css", "css")
        put("scss", "scss"); put("sass", "sass"); put("less", "less")
        put("json", "json"); put("jsonc", "json")
        put("yaml", "yaml"); put("yml", "yaml")
        put("toml", "toml")
        put("xml", "xml")
        put("md", "markdown"); put("markdown", "markdown")
        put("lua", "lua")
        put("perl", "perl")
        put("r", "r")
        put("scala", "scala")
        put("clj", "clojure")
        put("ex", "elixir"); put("exs", "elixir")
        put("erl", "erlang")
        put("hs", "haskell")
        put("ml", "ocaml")
        put("vim", "vim")
        put("graphql", "graphql")
        put("proto", "protobuf")
        put("tf", "hcl"); put("hcl", "hcl")
        // Names a model writes into the fence that are not file extensions.
        put("shell", "bash"); put("sh-session", "bash"); put("console", "bash")
        put("dockerfile", "dockerfile")
        put("diff", "diff"); put("patch", "diff")
        put("ini", "ini")
        put("makefile", "makefile")
        put("text", "plaintext"); put("plain", "plaintext"); put("txt", "plaintext")
        put("nix", "nix")
        put("groovy", "groovy")
        put("dart", "dart")
        put("objectivec", "objectivec"); put("objc", "objectivec")
    }

    /**
     * @return the canonical language name, or `null` when the fence says nothing
     *   useful. Fence info strings can carry extra attributes ("js title=foo"),
     *   so only the first word counts, as in CommonMark.
     */
    fun normalize(raw: String?): String? {
        val first = raw?.trim()?.substringBefore(' ')?.substringBefore('\t')
        if (first.isNullOrEmpty()) return null
        val lower = first.lowercase().removePrefix("language-")
        if (lower.isEmpty()) return null
        return aliases[lower] ?: lower
    }

    /** pi renders an unknown language as unhighlighted code, never as a guess. */
    fun isPlaintext(language: String?): Boolean =
        language == null || language == "plaintext" || language == "text"
}
