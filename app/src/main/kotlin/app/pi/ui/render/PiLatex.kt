package app.pi.ui.render

/**
 * pi's LaTeX support, ported for a phone.
 *
 * pi's markdown renderer is `marked` with two extra tokenizers
 * (`packages/tui/src/components/markdown.ts:123`, `LATEX_MARKDOWN_EXTENSIONS`)
 * that recognise inline `$...$` / `\(...\)` and block `$$...$$` / `\[...\]`
 * math. Every math token is handed to `renderLatex`: `markdown.ts:509` for the
 * block case, `markdown.ts:649` for the inline one. `renderLatex`
 * (`packages/tui/src/latex.ts:1376`) is a hand-written LaTeX parser that draws
 * a **Unicode approximation** \u2014 Greek letters, operators, `a/b` fractions,
 * `x^2` scripts \u2014 inside the one font a terminal has. It is not a typesetting
 * engine: it returns `undefined` for anything it does not understand, and the
 * call sites then print the source text as written (`markdown.ts:509`,
 * `?? latexToken.raw.trim()`). The `renderLatex: false` option and the
 * `pending` flag do the same thing deliberately: a formula that is still
 * arriving is shown verbatim (`markdown.ts:508`).
 *
 * ## Scope
 *
 * The tables below are transcribed **verbatim** from pi, and the parser is a
 * port of the text-level half of `LatexParser` (`latex.ts:811-1233`):
 * symbols, `^`/`_` scripts, `\frac`, `\sqrt`, accents, `\mathbb`, `\not`,
 * the `\text`-family wrappers, spacing, `\left`/`\right`, size commands and
 * the character escapes.
 *
 * It deliberately does **not** port pi's vertical layout (`LayoutNode`,
 * `renderLayout`, `latex.ts:723`): stacked fractions, `\sum` limits above and
 * below the operator, and `\begin{matrix}` grids. Those exist to place glyphs
 * in a fixed character grid, which a proportional-text phone column does not
 * have. pi itself degrades all of them to inline text whenever the formula is
 * not display math, and the Android renderer has no display-math layout either.
 *
 * Anything outside the ported subset makes [PiLatex.toUnicode] return `null`,
 * and the caller then leaves the source text alone \u2014 pi's own behaviour for a
 * formula it cannot render. Printing `\begin{pmatrix} a & b \end{pmatrix}`
 * verbatim is honest; drawing a grid we cannot draw would not be.
 *
 * **Known limit, stated plainly:** there is no math-typesetting engine here and
 * none is being added. A formula either reduces to the Unicode pi would print
 * or it is shown as written. `docs/known-gaps.md` records the same trade.
 */
internal object PiLatex {

    // ---------------------------------------------------------------------
    // Tables. Line references are into packages/tui/src/latex.ts.
    // ---------------------------------------------------------------------

    /** `latex.ts:3-228`. Case matters: `Phi`/`phi`, `Gamma`/`gamma`, `Im`/`Re`. */
    private val SYMBOLS: Map<String, String> = mapOf(
        "alpha" to "\u03b1",
        "beta" to "\u03b2",
        "gamma" to "\u03b3",
        "delta" to "\u03b4",
        "epsilon" to "\u03f5",
        "varepsilon" to "\u03b5",
        "zeta" to "\u03b6",
        "eta" to "\u03b7",
        "theta" to "\u03b8",
        "vartheta" to "\u03d1",
        "iota" to "\u03b9",
        "kappa" to "\u03ba",
        "varkappa" to "\u03f0",
        "lambda" to "\u03bb",
        "mu" to "\u03bc",
        "nu" to "\u03bd",
        "xi" to "\u03be",
        "pi" to "\u03c0",
        "varpi" to "\u03d6",
        "rho" to "\u03c1",
        "varrho" to "\u03f1",
        "sigma" to "\u03c3",
        "varsigma" to "\u03c2",
        "tau" to "\u03c4",
        "upsilon" to "\u03c5",
        "phi" to "\u03d5",
        "varphi" to "\u03c6",
        "chi" to "\u03c7",
        "psi" to "\u03c8",
        "omega" to "\u03c9",
        "Gamma" to "\u0393",
        "Delta" to "\u0394",
        "Theta" to "\u0398",
        "Lambda" to "\u039b",
        "Xi" to "\u039e",
        "Pi" to "\u03a0",
        "Sigma" to "\u03a3",
        "Upsilon" to "\u03a5",
        "Phi" to "\u03a6",
        "Psi" to "\u03a8",
        "Omega" to "\u03a9",
        "pm" to "\u00b1",
        "mp" to "\u2213",
        "times" to "\u00d7",
        "div" to "\u00f7",
        "cdot" to "\u00b7",
        "ast" to "\u2217",
        "star" to "\u22c6",
        "circ" to "\u2218",
        "bullet" to "\u2022",
        "oplus" to "\u2295",
        "ominus" to "\u2296",
        "otimes" to "\u2297",
        "oslash" to "\u2298",
        "odot" to "\u2299",
        "bigcirc" to "\u25cb",
        "dagger" to "\u2020",
        "ddagger" to "\u2021",
        "amalg" to "\u2a3f",
        "uplus" to "\u228e",
        "sqcap" to "\u2293",
        "sqcup" to "\u2294",
        "bowtie" to "\u22c8",
        "Join" to "\u22c8",
        "ltimes" to "\u22c9",
        "rtimes" to "\u22ca",
        "leftouterjoin" to "\u27d5",
        "rightouterjoin" to "\u27d6",
        "fullouterjoin" to "\u27d7",
        "triangleleft" to "\u25c1",
        "triangleright" to "\u25b7",
        "wr" to "\u2240",
        "cap" to "\u2229",
        "cup" to "\u222a",
        "bigcap" to "\u22c2",
        "bigcup" to "\u22c3",
        "bigwedge" to "\u22c0",
        "bigvee" to "\u22c1",
        "bigsqcup" to "\u2a06",
        "biguplus" to "\u2a04",
        "bigoplus" to "\u2a01",
        "bigotimes" to "\u2a02",
        "bigodot" to "\u2a00",
        "setminus" to "\u2216",
        "in" to "\u2208",
        "notin" to "\u2209",
        "ni" to "\u220b",
        "subset" to "\u2282",
        "supset" to "\u2283",
        "subseteq" to "\u2286",
        "supseteq" to "\u2287",
        "sqsubset" to "\u228f",
        "sqsupset" to "\u2290",
        "sqsubseteq" to "\u2291",
        "sqsupseteq" to "\u2292",
        "prec" to "\u227a",
        "preceq" to "\u227c",
        "succ" to "\u227b",
        "succeq" to "\u227d",
        "ll" to "\u226a",
        "gg" to "\u226b",
        "le" to "\u2264",
        "leq" to "\u2264",
        "leqslant" to "\u2264",
        "ge" to "\u2265",
        "geq" to "\u2265",
        "geqslant" to "\u2265",
        "ne" to "\u2260",
        "neq" to "\u2260",
        "equiv" to "\u2261",
        "approx" to "\u2248",
        "sim" to "\u223c",
        "simeq" to "\u2243",
        "cong" to "\u2245",
        "asymp" to "\u224d",
        "doteq" to "\u2250",
        "propto" to "\u221d",
        "parallel" to "\u2225",
        "perp" to "\u22a5",
        "mid" to "\u2223",
        "vdash" to "\u22a2",
        "dashv" to "\u22a3",
        "models" to "\u22a8",
        "Vdash" to "\u22a9",
        "Vvdash" to "\u22aa",
        "nvdash" to "\u22ac",
        "nvDash" to "\u22ad",
        "forall" to "\u2200",
        "exists" to "\u2203",
        "nexists" to "\u2204",
        "neg" to "\u00ac",
        "land" to "\u2227",
        "wedge" to "\u2227",
        "lor" to "\u2228",
        "vee" to "\u2228",
        "to" to "\u2192",
        "rightarrow" to "\u2192",
        "longrightarrow" to "\u2192",
        "leftarrow" to "\u2190",
        "longleftarrow" to "\u2190",
        "gets" to "\u2190",
        "leftrightarrow" to "\u2194",
        "longleftrightarrow" to "\u2194",
        "hookleftarrow" to "\u21a9",
        "hookrightarrow" to "\u21aa",
        "twoheadleftarrow" to "\u219e",
        "twoheadrightarrow" to "\u21a0",
        "leftharpoonup" to "\u21bc",
        "leftharpoondown" to "\u21bd",
        "rightharpoonup" to "\u21c0",
        "rightharpoondown" to "\u21c1",
        "rightleftharpoons" to "\u21cc",
        "leftrightharpoons" to "\u21cb",
        "nearrow" to "\u2197",
        "searrow" to "\u2198",
        "swarrow" to "\u2199",
        "nwarrow" to "\u2196",
        "rightsquigarrow" to "\u21dd",
        "leadsto" to "\u21dd",
        "Rightarrow" to "\u21d2",
        "Longrightarrow" to "\u21d2",
        "Leftarrow" to "\u21d0",
        "Longleftarrow" to "\u21d0",
        "Leftrightarrow" to "\u21d4",
        "Longleftrightarrow" to "\u21d4",
        "implies" to "\u21d2",
        "iff" to "\u21d4",
        "mapsto" to "\u21a6",
        "longmapsto" to "\u21a6",
        "uparrow" to "\u2191",
        "downarrow" to "\u2193",
        "partial" to "\u2202",
        "nabla" to "\u2207",
        "int" to "\u222b",
        "iint" to "\u222c",
        "iiint" to "\u222d",
        "oint" to "\u222e",
        "sum" to "\u2211",
        "prod" to "\u220f",
        "coprod" to "\u2210",
        "infty" to "\u221e",
        "emptyset" to "\u2205",
        "varnothing" to "\u2205",
        "angle" to "\u2220",
        "therefore" to "\u2234",
        "because" to "\u2235",
        "aleph" to "\u2135",
        "beth" to "\u2136",
        "gimel" to "\u2137",
        "daleth" to "\u2138",
        "top" to "\u22a4",
        "bot" to "\u22a5",
        "triangle" to "\u25b3",
        "square" to "\u25a1",
        "lozenge" to "\u25ca",
        "checkmark" to "\u2713",
        "complement" to "\u2201",
        "wp" to "\u2118",
        "prime" to "\u2032",
        "ldots" to "\u2026",
        "dots" to "\u2026",
        "cdots" to "\u22ef",
        "vdots" to "\u22ee",
        "ddots" to "\u22f1",
        "ell" to "\u2113",
        "hbar" to "\u210f",
        "Im" to "\u2111",
        "Re" to "\u211c",
        "langle" to "\u27e8",
        "rangle" to "\u27e9",
        "vert" to "|",
        "lvert" to "|",
        "rvert" to "|",
        "Vert" to "\u2016",
        "lVert" to "\u2016",
        "rVert" to "\u2016",
        "lbrace" to "{",
        "rbrace" to "}",
        "backslash" to "\\\\",
        "lfloor" to "\u230a",
        "rfloor" to "\u230b",
        "lceil" to "\u2308",
        "rceil" to "\u2309",
        "colon" to ":",
    )

    private val NEGATED_SYMBOLS: Map<String, String> = mapOf(
        "<" to "\u226e",
        ">" to "\u226f",
        "=" to "\u2260",
        "\u2208" to "\u2209",
        "\u220b" to "\u220c",
        "\u2223" to "\u2224",
        "\u2225" to "\u2226",
        "\u223c" to "\u2241",
        "\u2243" to "\u2244",
        "\u2245" to "\u2247",
        "\u2248" to "\u2249",
        "\u2261" to "\u2262",
        "\u2264" to "\u2270",
        "\u2265" to "\u2271",
        "\u227a" to "\u2280",
        "\u227b" to "\u2281",
        "\u2282" to "\u2284",
        "\u2283" to "\u2285",
        "\u2286" to "\u2288",
        "\u2287" to "\u2289",
        "\u22a2" to "\u22ac",
        "\u22a8" to "\u22ad",
        "\u2194" to "\u21ae",
        "\u2190" to "\u219a",
        "\u2192" to "\u219b",
        "\u21d2" to "\u21cf",
        "\u21d0" to "\u21cd",
        "\u21d4" to "\u21ce",
        "\u227c" to "\u22e0",
        "\u227d" to "\u22e1",
    )

    private val BLACKBOARD: Map<String, String> = mapOf(
        "C" to "\u2102",
        "H" to "\u210d",
        "N" to "\u2115",
        "P" to "\u2119",
        "Q" to "\u211a",
        "R" to "\u211d",
        "Z" to "\u2124",
    )

    private val SUPERSCRIPTS: Map<String, String> = mapOf(
        "0" to "\u2070",
        "1" to "\u00b9",
        "2" to "\u00b2",
        "3" to "\u00b3",
        "4" to "\u2074",
        "5" to "\u2075",
        "6" to "\u2076",
        "7" to "\u2077",
        "8" to "\u2078",
        "9" to "\u2079",
        "+" to "\u207a",
        "-" to "\u207b",
        "=" to "\u207c",
        "(" to "\u207d",
        ")" to "\u207e",
        "a" to "\u1d43",
        "b" to "\u1d47",
        "c" to "\u1d9c",
        "d" to "\u1d48",
        "e" to "\u1d49",
        "f" to "\u1da0",
        "g" to "\u1d4d",
        "h" to "\u02b0",
        "i" to "\u2071",
        "j" to "\u02b2",
        "k" to "\u1d4f",
        "l" to "\u02e1",
        "m" to "\u1d50",
        "n" to "\u207f",
        "o" to "\u1d52",
        "p" to "\u1d56",
        "r" to "\u02b3",
        "s" to "\u02e2",
        "t" to "\u1d57",
        "u" to "\u1d58",
        "v" to "\u1d5b",
        "w" to "\u02b7",
        "x" to "\u02e3",
        "y" to "\u02b8",
        "z" to "\u1dbb",
    )

    private val SUBSCRIPTS: Map<String, String> = mapOf(
        "0" to "\u2080",
        "1" to "\u2081",
        "2" to "\u2082",
        "3" to "\u2083",
        "4" to "\u2084",
        "5" to "\u2085",
        "6" to "\u2086",
        "7" to "\u2087",
        "8" to "\u2088",
        "9" to "\u2089",
        "+" to "\u208a",
        "-" to "\u208b",
        "=" to "\u208c",
        "(" to "\u208d",
        ")" to "\u208e",
        "a" to "\u2090",
        "e" to "\u2091",
        "h" to "\u2095",
        "i" to "\u1d62",
        "j" to "\u2c7c",
        "k" to "\u2096",
        "l" to "\u2097",
        "m" to "\u2098",
        "n" to "\u2099",
        "o" to "\u2092",
        "p" to "\u209a",
        "r" to "\u1d63",
        "s" to "\u209b",
        "t" to "\u209c",
        "u" to "\u1d64",
        "v" to "\u1d65",
        "x" to "\u2093",
    )

    private val ACCENTS: Map<String, String> = mapOf(
        "acute" to "\u0301",
        "bar" to "\u0305",
        "breve" to "\u0306",
        "check" to "\u030c",
        "ddot" to "\u0308",
        "dot" to "\u0307",
        "grave" to "\u0300",
        "hat" to "\u0302",
        "mathring" to "\u030a",
        "overleftarrow" to "\u20d6",
        "overleftrightarrow" to "\u20e1",
        "overline" to "\u0305",
        "overrightarrow" to "\u20d7",
        "tilde" to "\u0303",
        "underline" to "\u0332",
        "vec" to "\u20d7",
        "widehat" to "\u0302",
        "widetilde" to "\u0303",
    )

    private val NAMED_OPERATORS: Set<String> = setOf(
        "arccos",
        "arcsin",
        "arctan",
        "arg",
        "cos",
        "cosh",
        "cot",
        "coth",
        "csc",
        "deg",
        "det",
        "dim",
        "exp",
        "gcd",
        "hom",
        "inf",
        "ker",
        "lg",
        "lim",
        "liminf",
        "limsup",
        "ln",
        "log",
        "max",
        "min",
        "Pr",
        "sec",
        "sin",
        "sinh",
        "sup",
        "tan",
        "tanh",
    )

    private val DISPLAY_LIMIT_SYMBOLS: Set<String> = setOf(
        "bigcap",
        "bigcup",
        "bigodot",
        "bigoplus",
        "bigotimes",
        "bigsqcup",
        "biguplus",
        "bigvee",
        "bigwedge",
        "coprod",
        "int",
        "iint",
        "iiint",
        "oint",
        "prod",
        "sum",
    )

    private val SPACING_COMMANDS: Set<String> = setOf(
        ",",
        ":",
        ";",
        " ",
        ">",
        "enspace",
        "enskip",
        "medspace",
        "quad",
        "qquad",
        "thickspace",
        "thinspace",
    )

    private val NEGATIVE_SPACING_COMMANDS: Set<String> = setOf(
        "!",
        "negmedspace",
        "negthickspace",
        "negthinspace",
    )

    private val IGNORED_COMMANDS: Set<String> = setOf(
        "displaystyle",
        "limits",
        "nolimits",
        "scriptstyle",
        "scriptscriptstyle",
        "textstyle",
    )

    private val SIZE_COMMANDS: Set<String> = setOf(
        "big",
        "Big",
        "bigg",
        "Bigg",
        "bigl",
        "Bigl",
        "biggl",
        "Biggl",
        "bigr",
        "Bigr",
        "biggr",
        "Biggr",
    )

    private val PLAIN_WRAPPERS: Set<String> = setOf(
        "emph",
        "mathcal",
        "mathbf",
        "mathfrak",
        "mathit",
        "mathrm",
        "mathnormal",
        "mathscr",
        "mathsf",
        "mathtt",
        "mathup",
        "mbox",
        "overbrace",
        "pmb",
        "smash",
        "substack",
        "text",
        "textbf",
        "textit",
        "textmd",
        "textnormal",
        "textrm",
        "textsc",
        "textsf",
        "textsl",
        "texttt",
        "textup",
        "underbrace",
        "bm",
        "boldsymbol",
    )

    /**
     * `latex.ts:1376` `renderLatex`: the Unicode approximation of one formula,
     * or `null` when it uses something this port does not implement.
     *
     * @param display accepted for parity with pi's signature. It only selects
     *   the stacked-limit layout that is not ported, so it changes nothing
     *   here; the caller decides whether a block formula gets its own line.
     */
    fun toUnicode(source: String, display: Boolean = false): String? {
        val parser = LatexParser(source)
        val rendered = parser.parseSequence() ?: return null
        if (!parser.finished) return null
        return normalizeOutput(rendered)
    }

    /**
     * The block-math path: `markdown.ts:505-517` renders a `latexBlock` token
     * with `display: true`. On a terminal that means stacked fractions and
     * operator limits through `renderLayout` (`latex.ts:723`), which this port
     * does not draw (see the class note). What it does keep is pi's other
     * display-math decision: the result is laid out as **its own line block**
     * (`markdown.ts:511-513` pushes each rendered line separately), so a display
     * formula is never glued into the middle of a sentence. That is the part
     * worth keeping on a phone, and it is why an unrenderable `$$...$$` still
     * gets its own paragraph while an inline one just sits in the text.
     */
    /**
     * The AST-level counterpart to [toUnicode]: the formula inside one parsed
     * math node, with the delimiters removed.
     *
     * The node's own offsets are used rather than the match text, for the same
     * reason the annotator uses them: `ASTUtilKt.getTextInNode` is what the
     * renderer treats as the node's content, so the two cannot disagree about
     * which characters the node owns.
     *
     * @param fallback what to return when the node carries no usable text; pi
     *   prints the raw token (`markdown.ts:508`) in that case, and so does the
     *   caller.
     */
    fun formulaText(content: String, text: String, block: Boolean, fallback: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallback
        val opening = if (block) "$$" else "$"
        val body = trimmed.removePrefix(opening).removeSuffix(opening).trim()
        return body.ifEmpty { fallback }
    }

    fun toDisplayUnicode(source: String): String? {
        val body = source.removePrefix("$$").removeSuffix("$$").trim()
        val rendered = toUnicode(body, display = true) ?: return null
        return "\n" + rendered + "\n"
    }

    /**
     * `latex.ts:646-657` `normalizeOutput`, minus the named-operator spacing
     * markers (the layout they exist for is not ported): collapse runs of
     * spaces, trim every line, and drop blank lines between content lines.
     */
    private fun normalizeOutput(value: String): String {
        val lines = value.split('\n')
        val kept = lines.filterIndexed { index, line -> line.isNotEmpty() || (index > 0 && index < lines.size - 1) }
        return kept.joinToString("\n") { it.replace(Regex("[ \t]+"), " ").trim() }.trim()
    }

    /**
     * `latex.ts:601-611` `replaceCharacters`, `latex.ts:613-626`
     * `formatScript`, `latex.ts:628-634` `formatFraction`, `latex.ts:636-639`
     * `formatRoot`. Kept separate with pi's names so each fallback can be
     * compared against upstream.
     */
    private fun replaceCharacters(value: String, replacements: Map<String, String>): String? {
        val result = StringBuilder()
        for (character in value) {
            val replacement = replacements[character.toString()] ?: return null
            result.append(replacement)
        }
        return result.toString()
    }

    private fun formatScript(value: String, subscript: Boolean): String {
        val trimmed = value.trim().replace(Regex("\\s*([=+-])\\s*"), "$1")
        val unicode = replaceCharacters(trimmed, if (subscript) SUBSCRIPTS else SUPERSCRIPTS)
        if (unicode != null) return unicode
        val prefix = if (subscript) "_" else "^"
        if (trimmed.length == 1 || (subscript && trimmed.matches(Regex("^[A-Za-z]+$")))) {
            return "$prefix$trimmed"
        }
        return "$prefix($trimmed)"
    }

    private fun formatFraction(numerator: String, denominator: String): String {
        val n = numerator.trim()
        val d = denominator.trim()
        val simpleNumerator = n.matches(Regex("^[\\p{L}\\p{N}.]+$"))
        val simpleDenominator = d.matches(Regex("^[\\p{N}.]+$")) || d.length == 1
        return (if (simpleNumerator) n else "($n)") + "/" + (if (simpleDenominator) d else "($d)")
    }

    private fun formatRoot(value: String, symbol: String = "\u221a"): String {
        val v = value.trim()
        return if (v.matches(Regex("^[\\p{L}\\p{N}.]+$"))) symbol + v else symbol + "(" + v + ")"
    }

    /**
     * The text-level half of pi's `LatexParser` (`latex.ts:811-1233`).
     *
     * Bracket depth is the parser's own state, as in pi: `parseSequence`
     * consumes its own closing brace (`latex.ts:838`) and a stray `}` marks the
     * expression unsupported (`latex.ts:842`). Pi's `string | undefined`
     * returns plus a separate `supported` flag collapse into one nullable
     * value here, because on the Android side every failure has exactly one
     * recovery: leave the source text alone.
     */
    private const val NEGATIVE_SPACE = "\u0000"

    private class LatexParser(private val source: String) {

        private var position = 0

        /** `latex.ts:825`: true only when the whole source was consumed. */
        val finished: Boolean get() = position == source.length

        /** `latex.ts:833` `parseSequence`. */
        fun parseSequence(endCharacter: Char? = null): String? {
            val result = StringBuilder()
            while (position < source.length) {
                val character = source[position]
                if (endCharacter != null && character == endCharacter) {
                    position++
                    return result.toString()
                }
                if (character == '}') {
                    // `latex.ts:842`: an unmatched brace cannot be recovered.
                    return null
                }
                if (character == '{') {
                    position++
                    result.append(parseSequence('}') ?: return null)
                    continue
                }
                if (character == '\\') {
                    val command = parseCommand() ?: return null
                    if (command == NEGATIVE_SPACE) {
                        // `latex.ts:845-852`: negative spacing trims what came
                        // before it.
                        while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.length - 1)
                    } else {
                        result.append(command)
                    }
                    continue
                }
                if (character == '^' || character == '_') {
                    // `latex.ts:854-868`.
                    position++
                    while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.length - 1)
                    val argument = parseRequiredArgument() ?: return null
                    result.append(formatScript(argument, character == '_'))
                    continue
                }
                if (character.isWhitespace()) {
                    // `latex.ts:921-927`: a run of whitespace collapses to one
                    // space.
                    while (position < source.length && source[position].isWhitespace()) position++
                    result.append(' ')
                    continue
                }
                if (character == '=' || character == '<' || character == '>') {
                    // `latex.ts:882-886`: relations get a space on each side.
                    while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.length - 1)
                    result.append(' ').append(character).append(' ')
                    position++
                    continue
                }
                if (character == '&') {
                    // `latex.ts:888`: an alignment tab has no inline meaning.
                    position++
                    continue
                }
                if (character == '~') {
                    // `latex.ts:893`: a non-breaking space.
                    position++
                    result.append(' ')
                    continue
                }
                result.append(character)
                position++
            }
            // `latex.ts:909-912`: a sequence that ran out before its closing
            // brace is unsupported.
            return if (endCharacter != null) null else result.toString()
        }

        /** `latex.ts:928` `parseCommand`. */
        private fun parseCommand(): String? {
            position++
            if (position >= source.length) return null
            val first = source[position]
            if (first == '\n' || first == '\r') {
                // `latex.ts:934-941`: a backslash before a line break is a
                // forced newline.
                position++
                if (first == '\r' && position < source.length && source[position] == '\n') position++
                return " "
            }
            val command: String
            if (first.isAsciiLetter()) {
                val start = position
                while (position < source.length && source[position].isAsciiLetter()) position++
                command = source.substring(start, position)
            } else {
                command = first.toString()
                position++
            }

            // `latex.ts:943-1100`, in pi's own order: the first match wins.
            if (command == "\\") return "\n"
            if (command in SPACING_COMMANDS) return " "
            if (command in NEGATIVE_SPACING_COMMANDS) return NEGATIVE_SPACE
            if (command in IGNORED_COMMANDS) return ""
            if (command in CHAR_ESCAPES) return command
            if (command == "|") return "\u2016"
            if (command == "not") {
                // `latex.ts:983-996`.
                val value = (parseRequiredArgument() ?: return null).trim()
                NEGATED_SYMBOLS[value]?.let { return " $it " }
                if (value.isEmpty()) return null
                return " " + value[0] + "\u0338" + value.substring(1) + " "
            }
            SYMBOLS[command]?.let { symbol ->
                // `latex.ts:1002-1008`: a display-limit symbol drops its
                // above/below limits here (that layout is not ported), and pi
                // pads the multiplicative operators. Relations are padded by
                // the `=`/`<`/`>` branch above instead, since a symbol table
                // entry is what the model wrote for every other command.
                if (command == "cdot" || command == "times") return " " + symbol + " "
                return symbol
            }
            if (command in NAMED_OPERATORS) {
                // `latex.ts:1005-1007`: `\sin` prints in roman. Pi wraps it in
                // spacing markers for its operator layout; the name alone is
                // what the Unicode rendering shows.
                return command
            }
            if (command in SIZE_COMMANDS) return ""
            if (command == "left" || command == "middle" || command == "right") {
                // `latex.ts:1017-1022`: the delimiter that follows is parsed as
                // a symbol by the next iteration; `\left.` is invisible.
                if (position < source.length && source[position] == '.') position++
                return ""
            }
            if (command == "frac" || command == "dfrac" || command == "tfrac") {
                // `latex.ts:1024-1040`, minus the display-stacking branch: the
                // Android renderer has no vertical layout, so fractions are
                // always `a/b`. pi does the same for every inline fraction,
                // where `display` is false.
                val numerator = parseRequiredArgument() ?: return null
                val denominator = parseRequiredArgument() ?: return null
                return formatFraction(numerator, denominator)
            }
            if (command == "sqrt") {
                // `latex.ts:1041-1057`, including `\sqrt[3]`.
                val degree = parseOptionalArgument()?.trim()
                val value = parseRequiredArgument() ?: return null
                if (degree == null || degree == "2") return formatRoot(value)
                if (degree == "3") return formatRoot(value, "\u221b")
                if (degree == "4") return formatRoot(value, "\u221c")
                return formatScript(degree, false) + formatRoot(value)
            }
            if (command == "boxed" || command == "fbox") {
                return "[" + (parseRequiredArgument() ?: return null).trim() + "]"
            }
            if (command == "binom" || command == "dbinom" || command == "tbinom") {
                // `latex.ts:1062-1064`.
                val top = parseRequiredArgument() ?: return null
                val bottom = parseRequiredArgument() ?: return null
                return "(" + top + " choose " + bottom + ")"
            }
            ACCENTS[command]?.let { accent ->
                // `latex.ts:1065-1070`: one base character takes a combining
                // mark; anything longer keeps the command name.
                val value = parseRequiredArgument() ?: return null
                return if (value.length == 1) value + accent else command + "(" + value + ")"
            }
            if (command == "mathbb") {
                // `latex.ts:1071-1073`.
                val value = parseRequiredArgument() ?: return null
                return value.map { BLACKBOARD[it.toString()] ?: it.toString() }.joinToString("")
            }
            if (command == "operatorname") {
                // `latex.ts:1074-1082`: no operator layout here, so the name is
                // simply printed.
                if (position < source.length && source[position] == '*') position++
                return (parseRequiredArgument() ?: return null).trim()
            }
            if (command == "mod" || command == "bmod") return " mod "
            if (command == "pmod" || command == "pod") {
                // `latex.ts:1085-1087`.
                val value = (parseRequiredArgument() ?: return null).trim()
                return if (command == "pmod") " (mod $value)" else " ($value)"
            }
            if (command == "overset" || command == "stackrel") {
                // `latex.ts:1089-1092`.
                val upper = parseRequiredArgument() ?: return null
                val value = (parseRequiredArgument() ?: return null).trim()
                return value + formatScript(upper, false)
            }
            if (command == "underset") {
                // `latex.ts:1093-1096`.
                val lower = parseRequiredArgument() ?: return null
                val value = (parseRequiredArgument() ?: return null).trim()
                return value + formatScript(lower, true)
            }
            if (command in PLAIN_WRAPPERS) {
                // `latex.ts:1097-1099`.
                val value = parseRequiredArgument() ?: return null
                return if (command.startsWith("text") || command == "mbox") value else value.trim()
            }
            if (command == "begin") {
                // `latex.ts:1101-1103` parses environments into layout nodes
                // (matrices, `cases`, `aligned`), none of which is ported here.
                return null
            }
            // `latex.ts:1100`: an unknown command marks the expression
            // unsupported, and the call site prints the raw source.
            return null
        }

        /** `latex.ts:1160` `parseRequiredArgument`: a group or a single token. */
        private fun parseRequiredArgument(): String? {
            if (position >= source.length) return null
            if (source[position] == '{') {
                position++
                return parseSequence('}')
            }
            // `latex.ts:1168`: one character or one command.
            val character = source[position]
            if (character == '\\') return parseCommand()
            position++
            return character.toString()
        }

        /** `latex.ts:1188` `parseOptionalArgument`: `[...]`, or `null`. */
        private fun parseOptionalArgument(): String? {
            if (position >= source.length || source[position] != '[') return null
            position++
            return parseSequence(']')
        }
    }

    /**
     * `latex.ts:525` `NEGATIVE_SPACE`. Pi uses a NUL sentinel so that the
     * trimming behaviour cannot collide with real output; the same value is
     * reused here.
     */
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** `latex.ts:970-982`: `\$`, `\%`, `\#`, `\_`, `\&` and the brace escapes. */
private val CHAR_ESCAPES: Set<String> = setOf("{", "}", "$", "%", "#", "_", "&")
