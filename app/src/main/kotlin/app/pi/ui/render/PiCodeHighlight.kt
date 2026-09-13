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
 * It is deliberately synchronous and dependency-free: the winning backend is a
 * Node service reached over loopback (`app.pi.highlight.PiNodeCodeHighlighter`),
 * but it must stay callable from a background dispatcher with no Compose
 * involvement, so a code block can be highlighted once and cached as plain spans.
 *
 * Two obligations on callers, both learned from the backend's cost model:
 *
 *  - **call it off the main thread** (`rememberPiHighlightedCode` uses
 *    `Dispatchers.Default`); the implementation may wait up to a few hundred
 *    milliseconds for the engine;
 *  - **pass settled text, never a streaming prefix.** A fence that still changes
 *    on every token would make every intermediate version a cache miss and a
 *    request, which is why the caller debounces the block before asking.
 */
internal interface PiCodeHighlighter {
    /**
     * @param code the fence body, exactly as the model emitted it, and settled:
     *   see the note above about streaming.
     * @param language the fence's info string, already reduced by
     *   [PiCodeLanguage.normalize], or `null` when the fence named nothing.
     * @return spans in any order; they are applied in order, so a later span
     *   that overlaps an earlier one wins — plus [PiCodeHighlight.languageKnown],
     *   which is the same question pi asks before it colours anything.
     */
    fun highlight(code: String, language: String?): PiCodeHighlight
}

/**
 * One answer from a highlighter.
 *
 * [languageKnown] is pi's `supportsLanguage(lang)` (`theme.ts:1080`, `:1088`,
 * `:1192`) — the *gate*, not a detail. pi has exactly two rendering branches and
 * this boolean is what picks between them:
 *
 *  - **known** → `highlight()` ran, and the characters highlight.js did not wrap
 *    carry **no colour at all**: pi never calls `theme.fg` on them, so a terminal
 *    draws them in its default foreground. The phone's equivalent of "terminal
 *    default" is [app.pi.ui.theme.PiPalette.text], and a highlighted block's base
 *    colour must be exactly that.
 *  - **not known** (or no language at all) → pi skips highlighting and paints the
 *    body itself: `mdCodeBlock` for a fence (`theme.ts:1085`, `:1193`),
 *    `toolOutput` for a `read`/`write` body (`read.ts:132`, `write.ts:120`).
 *    `mdCodeBlock` is therefore pi's "we are not highlighting this" colour, and
 *    using it as the base of a *highlighted* block paints every un-tokenised
 *    character green.
 *
 * The distinction is invisible for most languages — they always produce spans —
 * but it is not a detail. `text`/`plaintext` **are** highlight.js languages, so pi
 * runs `highlight()` for ```text and lands in the first branch, while
 * `fish`/`sass`/`graphql`/`hcl` are not languages at all and land in the second —
 * even though pi's own extension table lists all four (`theme.ts:1131`, `:1138`,
 * `:1161`, `:1163`). A highlighter that cannot answer the question (no engine, a
 * timeout, a dropped request) must answer `false`: "we could not colour this" has
 * to render like pi's uncoloured branch, never like a coloured block.
 */
internal data class PiCodeHighlight(
    val spans: List<PiCodeSpan> = emptyList(),
    val languageKnown: Boolean = false,
)

/**
 * pi's fallback: when the fence has no language, or a language highlight.js does
 * not know, it skips highlighting entirely rather than guessing. Its comment on
 * that choice is explicit — "cli-highlight's auto-detection is unreliable and
 * can misidentify prose as AppleScript, LiveCodeServer, etc., coloring random
 * English words as keywords."
 *
 * This is also what the app renders with whenever the real highlighter cannot
 * answer — no engine running, a timeout, a malformed reply, an unknown language.
 * Code appears uncoloured rather than wrong, and a code block can never fail to
 * render because highlighting did.
 */
internal object PiPlainCodeHighlighter : PiCodeHighlighter {
    override fun highlight(code: String, language: String?): PiCodeHighlight = PiCodeHighlight()
}

/**
 * Fence-info reduction and pi's file-extension table.
 *
 * These are two *different* pi code paths, and they are deliberately not one map
 * any more — the single shared table is exactly what made the two disagree.
 *
 *  - **A fence carries no file.** pi hands the fence's info string to highlight.js
 *    and lets the engine's own registry (191 grammars plus its alias table) answer
 *    `supportsLanguage` (`theme.ts:1080`). So [normalize] only does what `marked`
 *    and highlight.js each already do on that way: `marked` trims the info string
 *    (`packages/tui/src/components/markdown.ts` → `token.lang`), and
 *    `getLanguage` lower-cases the name before looking it up. Nothing else may
 *    happen here — no first word, no `language-` prefix, no alias table — because
 *    every extra rule colours a fence that pi renders plain.
 *  - **A file path does have an extension**, and pi resolves it through one
 *    58-entry table, `getLanguageFromPath` (`theme.ts:1102-1168`), before asking
 *    the engine the same question. [forPath] is that table, verbatim.
 */
internal object PiCodeLanguage {

    /**
     * pi's `extToLang`, transcribed key for key (`theme.ts:1106-1165`).
     *
     * Four of its *values* are not highlight.js languages at all — `fish`, `sass`,
     * `graphql`, `hcl` — and that is kept, because it is pi's behaviour: the table
     * only names a candidate, and the engine still has the last word. For those
     * four the answer is "not known", so the block renders uncoloured in pi and
     * here alike. `cmake` (`:1149`) is a real highlight.js grammar and is included.
     */
    private val extensionToLanguage: Map<String, String> = mapOf(
        "ts" to "typescript", "tsx" to "typescript",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "py" to "python", "rb" to "ruby", "rs" to "rust", "go" to "go", "java" to "java",
        "kt" to "kotlin", "swift" to "swift",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "cs" to "csharp", "php" to "php",
        "sh" to "bash", "bash" to "bash", "zsh" to "bash", "fish" to "fish", "ps1" to "powershell",
        "sql" to "sql", "html" to "html", "htm" to "html",
        "css" to "css", "scss" to "scss", "sass" to "sass", "less" to "less",
        "json" to "json", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml", "xml" to "xml",
        "md" to "markdown", "markdown" to "markdown",
        "dockerfile" to "dockerfile", "makefile" to "makefile", "cmake" to "cmake",
        "lua" to "lua", "perl" to "perl", "r" to "r", "scala" to "scala",
        "clj" to "clojure", "ex" to "elixir", "exs" to "elixir", "erl" to "erlang",
        "hs" to "haskell", "ml" to "ocaml", "vim" to "vim",
        "graphql" to "graphql", "proto" to "protobuf", "tf" to "hcl", "hcl" to "hcl",
    )

    /**
     * A fence's info string, reduced to the name pi would hand highlight.js.
     *
     * `marked` gives pi the info string **trimmed and whole**: a fence written
     * ` ```js title=foo ` reaches `supportsLanguage("js title=foo")`, which is not a
     * language, so pi renders that block plain. Keeping the whole string is what
     * makes this app agree; the earlier "first word, strip `language-`, look up an
     * alias" leniency coloured fences that pi leaves alone.
     *
     * The single addition is `lowercase()`. It is not an extra rule but the same
     * one highlight.js applies internally (`getLanguage` lower-cases before the
     * lookup), and it has to happen on this side because the guest service is asked
     * for an exact key. Without it, ` ```Kotlin ` would be plain here and coloured
     * in pi.
     *
     * @return the name, exactly as pi would pass it (lower-cased), or `null` when
     *   the fence named nothing — see [isUnspecified].
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return trimmed.lowercase()
    }

    /**
     * The language of a *file*, for the blocks that render file content rather than a fence.
     *
     * pi's `read` and `write` renderers resolve it from the path they were given —
     * `getLanguageFromPath(rawPath)` (`theme.ts:1102-1168`, called at
     * `core/tools/renderers/read.ts:126` and `write.ts:45`/`:111`) — and then
     * highlight only if highlight.js knows the name (`highlightCode`,
     * `theme.ts:1078-1086`).
     *
     * The expression is pi's, exactly: `filePath.split(".").pop().toLowerCase()`.
     * It never strips a directory, which has one consequence worth stating because
     * it looks like a bug on our side and is not: a path with **no dot at all**
     * resolves to the whole path, so a bare `Dockerfile` becomes `dockerfile` and
     * gets highlighted (`:1147`), while `/root/proj/Dockerfile` becomes
     * `/root/proj/dockerfile`, misses the table, and stays uncoloured — in pi too.
     * Copying the expression rather than "improving" it is the point.
     */
    fun forPath(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return extensionToLanguage[path.substringAfterLast('.').lowercase()]
    }

    /**
     * Whether the fence named nothing at all — pi's `lang === undefined`.
     *
     * This is **not** "the language is plain": `text` and `plaintext` are real
     * highlight.js languages, so pi colours such a fence through its normal branch
     * (painting the un-tokenised characters with its default foreground). Only a
     * fence that names nothing takes pi's `!validLang` shortcut, and only then may
     * the caller skip the highlighter entirely.
     */
    fun isUnspecified(language: String?): Boolean = language == null
}
