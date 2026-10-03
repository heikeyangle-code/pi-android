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
 * ## The one thing it deliberately does not port: pi's vertical layout
 *
 * pi's `LatexParser` can also emit *layout nodes* and let `renderLayout`
 * (`latex.ts:723-809`) assemble them into a small character grid:
 *
 * * `\frac` stacks as numerator / a `─` rule / denominator when the formula is
 *   display math and the fraction was not reached through a script
 *   (`latex.ts:1017-1031`, the `shouldStack` branch; drawn at `latex.ts:748-761`);
 * * a display operator carrying limits (`\sum`, `\int`, `\lim`, …) puts them
 *   above and below the symbol instead of beside it (`latex.ts:1145-1148`,
 *   drawn at `latex.ts:762-780`);
 * * the eight grid environments (`array`, `matrix`, `smallmatrix`, `pmatrix`,
 *   `bmatrix`, `Bmatrix`, `vmatrix`, `Vmatrix`) become a delimited grid
 *   (`latex.ts:1301-1306` dispatches them, `latex.ts:1312-1355` pads the columns
 *   and adds the `⎛⎝ ⎞⎠` family); `cases` (`latex.ts:1286-1299`) and
 *   `aligned`/`gather`/`split` (`latex.ts:1257-1284`) are the same mechanism
 *   without delimiters.
 *
 * None of that is drawn here. How far the difference reaches depends on the
 * construct, because pi gates only two of the three on `display`:
 *
 * * `\frac` (`latex.ts:1018`) and operator limits (`latex.ts:1145`) are
 *   **block-only** differences. pi renders an inline `$…$` token with
 *   `renderLatex(text)` (`packages/tui/src/components/markdown.ts:649`), where
 *   `display` is false and both are off, and a `$$…$$` token with
 *   `renderLatex(text, { display: true })` (`markdown.ts:509`), where they are
 *   on; each rendered line is then pushed separately (`markdown.ts:511-513`).
 * * the environment branch is **not** gated: `\begin` goes straight to
 *   `parseEnvironment` (`latex.ts:1090-1091`), a multi-row matrix always becomes
 *   a layout node (`latex.ts:1350-1354`), and `renderLatex` runs `renderLayout`
 *   whenever any node was collected, whatever `display` says
 *   (`latex.ts:1382-1385`). pi therefore draws the grid for an inline
 *   `$\begin{pmatrix}…\end{pmatrix}$` as well.
 *
 * So on a phone the visible fallbacks are exactly these:
 *
 * | construct | pi, inline `$…$` | pi, inside `$$…$$` | this port |
 * |---|---|---|---|
 * | `\frac{a}{b}` | `a/b` | stacked, with a rule between | `a/b` — pi's inline form (`latex.ts:1030`), so it matches inline and differs in block |
 * | `\sum_{i=1}^{n}` | `∑ᵢ₌₁ⁿ` | limits above and below | `∑ᵢ₌₁ⁿ` — pi's inline form (`latex.ts:1150-1157`), so it matches inline and differs in block |
 * | `\begin{pmatrix}…\end{pmatrix}` | a bracketed grid | a bracketed grid | `null`, so the caller prints the formula exactly as written — this one differs in **both** |
 *
 * **Why porting the layout function alone would not be enough.** [preprocess]
 * substitutes a rendered formula into the markdown *source* before the parser
 * runs, and the renderer's annotator turns an end of line inside a paragraph
 * into a **space**: `MarkdownAnnotatorConfig.eolAsNewLine` defaults to `false`
 * (`multiplatform-markdown-renderer/.../model/MarkdownAnnotatorConfig.kt`) and
 * the annotator reads `MarkdownTokenTypes.EOL -> if (eolAsNewLine) append('\n')
 * else append(' ')` (`.../annotator/AnnotatedStringKtx.kt:357`). A grid computed
 * here would be flattened back into one line by the renderer on the way out, so
 * pi's layout needs a channel that preserves line structure — a fence, or a
 * block-level math component — before a port would be visible. That is a
 * rendering-architecture change, not a change to this parser; until it exists,
 * porting `renderLayout` would produce a correct string the app cannot draw,
 * which is worse than the fallback above.
 *
 * Anything outside the ported subset makes [PiLatex.toUnicode] return `null`,
 * and the caller then leaves the source text alone \u2014 pi's own behaviour for a
 * formula it cannot render (`markdown.ts:509` and `:649` both fall back to
 * `latexToken.raw`). Printing `\begin{pmatrix} a & b \end{pmatrix}` verbatim is
 * honest; drawing a grid we cannot draw would not be.
 *
 * **Known limit, stated plainly:** there is no math-typesetting engine here and
 * none is being added. A formula either reduces to the Unicode pi's own inline
 * path would print, or it is shown as written. `docs/known-gaps.md` A2 and
 * `docs/gap-disposition.md` section 10.1 record the same trade.
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
        // pi：`latex.ts:222` 的值是 `"\\"`，也就是**一个**反斜杠字符。这里曾经写成
        // `"\\\\"`（两个），于是 `$\backslash$` 比 pi 多画一个 `\` —— 值本身写错不会
        // 有任何编译错误，只有 `tools/check-latex-tables.mjs` 的逐 key 比对能看见。
        "backslash" to "\\",
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

    /**
     * `latex.ts:265-278`。**这张表必须先于 [SYMBOLS] 和 [NAMED_OPERATORS] 判定**
     * （pi：`latex.ts:1087` 在 `:1098` 之前，也比符号表早）—— `inf`/`lim`/`max`/`min`/`sup`
     * 同时在 [NAMED_OPERATORS] 里，`inf` 还在 [SYMBOLS] 里没有（只有 `infty`），
     * 而 pi 对这 11 个命令一律走 `parseOperator(…, "bracket", …)`：`\lim_{n\to\infty}`
     * 是 `lim[n→∞]`，不是 `lim` 后面接一个下标。
     */
    private val LIMIT_OPERATORS: Set<String> = setOf(
        "argmax",
        "argmin",
        "inf",
        "injlim",
        "lim",
        "liminf",
        "limsup",
        "max",
        "min",
        "projlim",
        "sup",
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

    /**
     * `latex.ts:298-388`。为什么需要单独一张表：pi 对关系符在符号两侧各补一个空格
     * （`latex.ts:1109` 的 `RELATION_COMMANDS.has(command)` 那一支），而同一张符号表里
     * 别的命令不补。少了它 `$a\le b$` 会画成 `a≤ b`（pi：`a ≤ b`）。
     */
    private val RELATION_COMMANDS: Set<String> = setOf(
        "Leftarrow",
        "Leftrightarrow",
        "Longleftarrow",
        "Longleftrightarrow",
        "Longrightarrow",
        "Rightarrow",
        "Join",
        "Vdash",
        "Vvdash",
        "approx",
        "asymp",
        "bowtie",
        "cong",
        "dashv",
        "fullouterjoin",
        "doteq",
        "downarrow",
        "equiv",
        "ge",
        "geq",
        "geqslant",
        "gets",
        "gg",
        "hookleftarrow",
        "hookrightarrow",
        "iff",
        "implies",
        "in",
        "leadsto",
        "le",
        "leftarrow",
        "leftharpoondown",
        "leftharpoonup",
        "leftrightarrow",
        "leftrightharpoons",
        "leftouterjoin",
        "leq",
        "leqslant",
        "ll",
        "longleftarrow",
        "longleftrightarrow",
        "longmapsto",
        "longrightarrow",
        "ltimes",
        "mapsto",
        "mid",
        "models",
        "ne",
        "nearrow",
        "neq",
        "ni",
        "notin",
        "nvdash",
        "nvDash",
        "nwarrow",
        "parallel",
        "perp",
        "prec",
        "preceq",
        "propto",
        "rightharpoondown",
        "rightharpoonup",
        "rightleftharpoons",
        "rightouterjoin",
        "rightarrow",
        "rightsquigarrow",
        "rtimes",
        "searrow",
        "sim",
        "simeq",
        "sqsubset",
        "sqsubseteq",
        "sqsupset",
        "sqsupseteq",
        "subset",
        "subseteq",
        "succ",
        "succeq",
        "supset",
        "supseteq",
        "swarrow",
        "to",
        "triangleleft",
        "triangleright",
        "twoheadleftarrow",
        "twoheadrightarrow",
        "uparrow",
        "vdash",
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

    /**
     * `latex.ts:526`。`\rm`/`\bf` 这类**字体切换**命令自己什么都不画，而且吃掉紧跟其后的
     * 空白（`latex.ts:1050-1056`）—— 所以 `$\rm x$` 是 `x`，不是 `\rm x` 的原文，也不是 ` x`。
     * `a\bf   b` 是 `ab`（pi 的 whitespace 分支不会先插一个空格：命令返回空串后，剩下的空白
     * 由这一支自己吃掉）。不认这张表的后果是**整条公式**变成原文，因为未知命令在 pi 里是
     * `supported = false`。
     */
    private val FONT_SWITCH_COMMANDS: Set<String> = setOf("bf", "cal", "it", "rm", "sf", "sl", "tt")

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
     * @param display accepted for parity with pi's signature. It selects the
     *   stacked layout, which is not ported (see the class note), so it changes
     *   nothing here; the caller decides whether a block formula gets its own
     *   line. pi's inline call site passes nothing, i.e. `display = false`
     *   (`markdown.ts:649`), and for an inline formula without an environment
     *   this function then reproduces pi exactly: the `shouldStack` branch
     *   (`latex.ts:1018`) and the display-limits branch (`latex.ts:1145`) are the
     *   only `display`-gated ones, and pi turns both off. Note that pi's
     *   environment branch (matrices, `cases`, `aligned`) is **not**
     *   `display`-gated, so pi renders those even inline while this port reports
     *   the formula unsupported — which is why `display` does not fully decide
     *   this function's coverage.
     */
    fun toUnicode(source: String, display: Boolean = false): String? {
        val parser = LatexParser(source)
        val rendered = parser.parseSequence() ?: return null
        if (!parser.finished) return null
        return normalizeOutput(rendered)
    }

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

    /**
     * The block-math path: `markdown.ts:505-517` renders a `latexBlock` token
     * with `display: true`. On a terminal that means stacked fractions, operator
     * limits and matrix grids through `renderLayout` (`latex.ts:723-809`), which
     * this port does not draw (see the class note — and note that the class note
     * also explains why the missing piece is a line-preserving channel, not this
     * function). What this does keep is pi's other display-math decision: the
     * result is laid out as **its own line block** (`markdown.ts:511-513` pushes
     * each rendered line separately), so a display formula is never glued into
     * the middle of a sentence. That is the part worth keeping on a phone, and
     * it is why an unrenderable `$$...$$` still gets its own paragraph while an
     * inline one just sits in the text.
     *
     * The `\n` padding is what makes that paragraph break visible through the
     * markdown source the caller is rewriting; it is not pi's output (pi emits
     * whole lines into its own text buffer).
     */
    fun toDisplayUnicode(source: String): String? {
        val body = source.trim().removePrefix("$$").removeSuffix("$$").trim()
        return displayBlock(body)
    }

    /**
     * 显示式公式的最终形状：`null` = 这条公式画不出来（调用方保留原文），否则是
     * **独占一段**的 `\n<渲染结果>\n`（见 [toDisplayUnicode] 的说明）。
     */
    private fun displayBlock(body: String): String? {
        val rendered = toUnicode(body.trim(), display = true) ?: return null
        return "\n" + rendered + "\n"
    }

    // ---------------------------------------------------------------------
    // 接入 markdown 源文本：pi 的两个 latex tokenizer，作用在源上
    // ---------------------------------------------------------------------

    /**
     * pi 的 `LATEX_MARKDOWN_EXTENSIONS`（`packages/tui/src/components/markdown.ts:123-172`），
     * 作用在**源文本**上而不是 token 流上。
     *
     * pi 的 tokenizer 是 `marked` 扩展，也就是说它们跑在 marked 自己的规则**之后**：
     * 围栏代码与行内代码里的 `$` 永远不会到 `tokenizeInlineLatex`
     * （`markdown.ts:52-99`）。这里要复刻这一点，所以扫描必须自己跳过代码 ——
     * [FENCE] 与 [INLINE_CODE] 两个状态就是干这个的。少了它们，`` a `$y$` b ``
     * 会被在代码里替换掉，渲染出来是一段写着公式结果的代码。
     *
     * 顺序也跟 marked 一致：**先块级，再行内**。块级 token 会先把整段 `$$…$$` /
     * `\[…\]` 吃掉，行内那一趟就看不到显示式公式的内部；反过来会把 `$$` 当成两条
     * 相邻的行内公式。
     *
     * ## 与 pi 定界符的逐条对齐（以及哪里只是逼近）
     *
     * 四种定界符都在这里（pi：`markdown.ts:56-58`、`:106-112`）：
     *
     * | 定界符 | pi 的形态 | 这里 |
     * |---|---|---|
     * | `\(…\)` | 行内；单行、非空（`markdown.ts:65-99`） | 一样 |
     * | `\[…\]` | **行首**（≤3 空格）+ `\]` 后到行尾 ⇒ 块级；否则行内 | 一样（行首判定在 [atLineStart]） |
     * | `$$…$$` | **行首** + `$$` 后到行尾 ⇒ 块级；否则行内 | 见下面那条已知偏差 |
     * | `$…$` | 行内；body 不以空白结尾、后面不接数字等（`markdown.ts:65-72`） | 沿用原有正则，未动 |
     *
     * `\(`/`\[` 的转义：`\\(` 不是定界符（pi 的 `escape` 规则先吃掉它），所以两条分支
     * 都带 `(?<!\\)`。**注意 `\(` 前面是字母或数字时 pi 仍然认它是公式**
     * （`a\(x\)` 是 `ax`，我在本机用 marked 跑过），所以这两条分支没有
     * "前面不能是单词字符" 的 lookbehind —— 那一条只属于 `$`。
     *
     * **已知偏差（`$$` 的行首锚点）**：pi 只在 `$$…$$` 位于"段落起点 + ≤3 空格"
     * 且 `$$` 后到行尾时才当块级（否则当行内：`abc $$x$$` 渲染成行内的 `x`）。
     * 这里的 `$$` 分支沿用 App 原有的、没有行首锚点的正则，所以行中的 `$$x$$`
     * 今天会变成独占一段的显示式。这一条**没有**在这次改动里动
     * （`docs/known-gaps.md` §A2 记着它）：它是既有行为、影响面比补两种定界符大，
     * 而 `\[…\]` 是新加的，可以一次就做对。
     *
     * 剩下的一类边界也记在这里：`a \[x\] b` 这种"公式前正好一个字符 + 一个空格"的
     * 行中 `\[`，pi 的 marked 会把段落切在那里、然后把它当**待定块级**（原文照排），
     * 而这里会当成行内公式画出来。这是 marked 段落切分的副作用（`e.slice(1)` 那一处
     * 的锚点），不是可以"照抄"的规则；宁可画成公式（pi 在 `abc \[x\] b` 上就是这么做的）
     * 也不去复刻它。
     *
     * 任何 [PiLatex] 归约不出来的公式都原样留在源里 —— pi 自己的回退也是打印
     * `latexToken.raw`（`markdown.ts:509`、`:649`）。
     */
    fun preprocess(markdown: String): String {
        // 快路径：两个定界符家族（`$` 与 `\(`/`\[`）都没有时，整篇没有任何东西可改。
        // 流式转写里这是绝大多数帧的情况。
        if (!markdown.contains('$') && !markdown.contains('\\')) return markdown
        val out = StringBuilder(markdown.length)
        var index = 0
        while (index < markdown.length) {
            val fence = FENCE.find(markdown, index)
            if (fence != null && fence.range.first == index) {
                // 围栏代码块：一直拷到同字符、不短于开栏的收栏为止，这样里面更短的
                // 反引号串不会提前结束它。
                out.append(fence.value)
                val closing = "\n" + fence.groupValues[1]
                val end = markdown.indexOf(closing, fence.range.last + 1)
                if (end < 0) return out.append(markdown, fence.range.last + 1, markdown.length).toString()
                out.append(markdown, fence.range.last + 1, end + closing.length)
                index = end + closing.length
                continue
            }
            val code = INLINE_CODE.find(markdown, index)
            if (code != null && code.range.first == index) {
                out.append(code.value)
                index = code.range.last + 1
                continue
            }
            val start = when {
                fence == null -> code?.range?.first ?: markdown.length
                code == null -> fence.range.first
                else -> minOf(fence.range.first, code.range.first)
            }
            out.append(applyMath(markdown, index, start))
            index = start
        }
        return out.toString()
    }

    /** 对一段"没有代码"的区间 `[start, end)` 做两次替换。 */
    private fun applyMath(text: String, start: Int, end: Int): String {
        val run = text.substring(start, end)
        if (!run.contains('$') && !run.contains('\\')) return run
        // **两道 guard 都是精确的。** 上面的 `contains` 已经把"不可能匹配"的区间整个
        // 跳过了；下面这两道只跳过对应的那一趟，因为每个 pattern 都必须先看到它自己的
        // 字面量才能匹配（`$$` / `\[`）。少了它们，那一趟会走完整个区间才得出"这里
        // 没有显示式公式"的结论 —— 手机上 5.9 KB 的一趟量到 0.36–1.32 ms，而带 guard
        // 是 0.40 ms，代价出现在每个流式 token 更新时的重组里
        // （`docs/scroll-perf-items.md` §3）。`Regex.replace` 没有匹配时返回原串，
        // 所以跳过它唯一能改变的就是"哪个实例"流到下一趟。
        val block = if (run.contains(BLOCK_MATH_MARKER) || run.contains(BLOCK_BRACKET_MARKER)) {
            BLOCK_MATH.replace(run) { match ->
                val dollar = match.groupValues[1]
                val bracket = match.groupValues[2]
                when {
                    // `$$…$$`：整段（含定界符）交给 toDisplayUnicode。
                    dollar.isNotEmpty() -> toDisplayUnicode(match.value) ?: match.value
                    // `\[…\]`：块级形态的 body 已经在 group 2 里（含可能的换行），
                    // 空 body 是 pi 的"待定"（原文照排），所以不动。
                    bracket.isNotBlank() -> displayBlock(bracket) ?: match.value
                    else -> match.value
                }
            }
        } else {
            run
        }
        return INLINE_MATH.replace(block) { match ->
            val parenthesized = match.groupValues[1]
            val bracketed = match.groupValues[2]
            when {
                // `\(…\)`：pi 没有块级形态，任何时候都是行内。
                parenthesized.isNotEmpty() -> toUnicode(parenthesized) ?: match.value
                // `\[…\]`：行首的那种交给上面那一趟（没被替换 = pi 的待定/画不出来，
                // 保持原文）；只有行中的才是 pi 的行内 token。
                bracketed.isNotEmpty() ->
                    if (atLineStart(block, match.range.first)) match.value
                    else toUnicode(bracketed) ?: match.value
                // `$…$`：定界符就是首尾两个字符，形状没变。
                else -> {
                    val source = match.value
                    toUnicode(source.substring(1, source.length - 1)) ?: source
                }
            }
        }
    }

    /**
     * 这个位置是不是"行首（允许 ≤3 个空格/制表符）"。
     *
     * 与 pi 的 `tokenizeBlockLatex` 的 `^ {0,3}` 同义，用在行内那一趟里把行首的
     * `\[` 留给块级趟（`markdown.ts:106-112`）。pi 真正的锚点更细（还包含 marked
     * 的段落切分），那部分见 [preprocess] 的已知偏差。
     */
    private fun atLineStart(text: String, index: Int): Boolean {
        var back = index
        var spaces = 0
        while (back > 0 && spaces < 3 && (text[back - 1] == ' ' || text[back - 1] == '\t')) {
            back--
            spaces++
        }
        return back == 0 || text[back - 1] == '\n'
    }

    /**
     * 块级 Math 的两条分支**共用一次替换**，与 pi 的 block tokenizer 同序：
     * 它在每个位置先试 `$$` 再试 `\[`（`markdown.ts:106-112`）。共用还有一个更要紧的
     * 效果：`Regex.replace` 不会重扫替换结果，所以 `$$a \[b\]$$` 里的 `\[b\]`
     * 不会被当成第二条公式（pi 也把它整段当一条）。
     *
     * 1 号组 = `$$…$$` 的 body，2 号组 = `\[…\]` 的 body。
     */
    private val BLOCK_MATH = Regex(
        pattern = """(?<![\p{L}\p{N}\\])\$\$([^$]+?)\$\$\n?""" +
            """|(?m)^ {0,3}\\\[[ \t]*(?:\n)?([\s\S]*?)\\\][ \t]*(?:\n|$)""",
        option = RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * 行内数学的三条分支，`\(`/`\[`/`$` 依次（pi：`markdown.ts:56-58`）。
     * 1 号组 = `\(…\)`，2 号组 = `\[…\]`，3 号 = `$…$`。
     *
     * `$` 这一支的开关位置不是随意的，每一处都是拿 **220 条真实 prose 语料**量过以后
     * 留的（`app/src/test/resources/pi-latex-fixtures/cases.json` 的 `dollarProse`：
     * 184 条取自本仓库自己的 `docs/**.md` / `res/values*/**.xml`，36 条手写真实用法）：
     * `(?<!\\)` 与 `(?<!\s)` 对应 pi 自己的规则（`markdown.ts:56-72`），而开定界符
     * 前面**不再**要求"不是字母/数字"、body 开头**不再**要求"不是数字" —— 那两条
     * 让 `a$x$b`、`2$x$`、`$5$` 这些 pi 当公式的输入被我们当成普通文本，实测它们在
     * 真实语料上既不减少误判、也不保护任何正常用法（详见 §A2）。
     *
     * 仍然保留的是收尾那条 `(?![\p{L}\p{N}])`：它比 pi 严（pi 只在"body 像常量名而后面
     * 紧跟标识符"时拒绝）。去掉它 `$` 的偏差就归零 —— 但实测在真实语料上会多吃 3 条
     * shell/Perl/模板文本（今天原样保留、pi 会吃掉它们），所以宁可在剩下的 4 种形状上
     * 比 pi 保守。理由与逐条证据见 `docs/known-gaps.md` §A2。
     */
    private val INLINE_MATH = Regex(
        pattern = """(?<!\\)\\\(([^\n]+?)\\\)""" +
            """|(?<!\\)\\\[([^\n]+?)\\\]""" +
            """|(?<!\\)\$([^\s$][^$\n]*?)(?<!\s)\$(?![\p{L}\p{N}])""",
    )

    /**
     * [applyMath] 里两道 guard 需要的字面量。放在 pattern 旁边，这样 guard 不会和
     * pattern 悄悄失配。
     */
    private const val BLOCK_MATH_MARKER = "$$"
    private const val BLOCK_BRACKET_MARKER = "\\["

    /**
     * 当前位置的开围栏：最多三个空格，然后三个以上反引号或波浪号
     * （CommonMark 的规定）。info string 不捕获 —— 收栏只需要标记本身。
     */
    private val FENCE = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\n]*")

    /**
     * 行内代码：一串反引号，然后**同样长度**的第一串反引号。
     * `org.intellij.markdown` 用的是同样的贪心规则，所以扫描器与解析器对
     * "这一行内代码到哪里结束"的判断一致。
     */
    private val INLINE_CODE = Regex("(`{1,3})(?:(?!\\1)[\\s\\S])*?\\1")

    /**
     * pi 的两枚**具名算子哨兵**（`latex.ts:651-652`）。
     *
     * pi 用的是补充平面的私用码位 `\u{f0004}` / `\u{f0005}`；这里换成 BMP 私用区的
     * `\uE004` / `\uE005`，原因是下面两条间距正则要在 Java 的 lookbehind 里放一个
     * **单字符**（补充平面字符在 UTF-16 里是两个 char，写进字符类会变成两个独立
     * 选择项，语义就错了）。哨兵在 [normalizeOutput] 结束前一定被消费掉，不进任何
     * 输出字符串，所以码位本身不是契约；语义与 pi 相同这一点由 `PiLatexCheck` 的
     * 公式夹具（`2\sin x`、`\sin\alpha`、`\sin^2`）钉住。
     */
    private const val NAMED_OPERATOR_START = "\uE004"
    private const val NAMED_OPERATOR_END = "\uE005"

    /** `latex.ts:653`：具名算子前面是字母/数字/`)`/`]`/布局标记 时补一个空格。 */
    private val NAMED_OPERATOR_LEFT_SPACING = Regex("(?<=[\\p{L}\\p{N}\\)\\]}])$NAMED_OPERATOR_START")

    /** `latex.ts:654`：具名算子后面紧跟字母/数字/`√` 时补一个空格。 */
    private val NAMED_OPERATOR_RIGHT_SPACING = Regex("$NAMED_OPERATOR_END(?=[\\p{L}\\p{N}\u221a])")

    /**
     * `latex.ts:654-665` `normalizeOutput`：折叠空格、逐行 trim、丢掉内容行之间的空行。
     *
     * 前两条替换是 pi 的**具名算子间距**协议（`latex.ts:649-653`、`:657-659`）：
     * `\sin` 这类命令在解析期被包上两个哨兵，最后在这里换成空格或删掉。为什么不能
     * 直接拼字符串：间距取决于**后面**是什么（`latex.ts` 的
     * `NAMED_OPERATOR_RIGHT_SPACING_PATTERN` 是 lookahead），而 `\sin` 出现在
     * `2\sin x`（要空格：`2 sin x`）与 `\sin(x)`（不要空格）两种上下文里。
     * 少了这两条，`$2\sin x$` 会画成 `2sin x`。
     *
     * pi 的右间距类里还有一个 `LAYOUT_MARKER_START`（`latex.ts:660`）：那是
     * `renderLayout` 的布局标记，只有移植了布局支（Phase B）才可能出现，所以这里
     * 不放——放了也匹配不到任何东西。
     */
    private fun normalizeOutput(value: String): String {
        val spaced = value
            .replace(NAMED_OPERATOR_LEFT_SPACING, " ")
            .replace(NAMED_OPERATOR_START, "")
            .replace(NAMED_OPERATOR_RIGHT_SPACING, " ")
            .replace(NAMED_OPERATOR_END, "")
        val lines = spaced.split('\n')
        val kept = lines.filterIndexed { index, line -> line.isNotEmpty() || (index > 0 && index < lines.size - 1) }
        return kept.joinToString("\n") { it.replace(Regex("[ \t]+"), " ").trim() }.trim()
    }

    /**
     * `latex.ts:602-612` `replaceCharacters`, `latex.ts:614-620`
     * `normalizeScriptValue` + `formatUnicodeScript`, `latex.ts:622-634`
     * `formatScript`, `latex.ts:636-642` `formatFraction`, `latex.ts:644-647`
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
                        // `latex.ts:885-891`：负间距吃掉紧邻它前面的那个空格；如果
                        // 前面正好是一个具名算子（`\sin` 这类被哨兵包起来的），吃掉的
                        // 是**右哨兵**而不是空格 —— 否则 `\sin\!x` 会留下一个空壳哨兵，
                        // 最后在那里多出一个空格。
                        while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.length - 1)
                        if (result.endsWith(NAMED_OPERATOR_END)) result.delete(result.length - NAMED_OPERATOR_END.length, result.length)
                    } else {
                        result.append(command)
                    }
                    continue
                }
                if (character == '^' || character == '_') {
                    // `latex.ts:894-904`：脚本插在**右哨兵之前**。`\sin^2` 的 `²` 必须
                    // 落在 `END` 里面（`START sin ² END`），否则 `normalizeOutput` 的右
                    // 间距规则会以为 `²` 跟不上算子，多补一个空格（pi 是 `sin²`）。
                    position++
                    while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.length - 1)
                    val argument = parseScripts(character) ?: return null
                    if (result.endsWith(NAMED_OPERATOR_END)) {
                        result.delete(result.length - NAMED_OPERATOR_END.length, result.length)
                        result.append(argument).append(NAMED_OPERATOR_END)
                    } else {
                        result.append(argument)
                    }
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

        /**
         * `latex.ts:949-1005` `parseScripts`，**去掉 layout 那一支**
         * （`latex.ts:982-1005` 的 `needsLayout`）。
         *
         * 去掉它是安全的，也是唯一的空档：那一支的前置条件是 `this.display`
         * （`latex.ts:982`），而本文件里 `display` 只由 [toDisplayUnicode] 传进来、
         * 且目前不改变任何解析结果（见 [toUnicode]）。留在外面的部分是 pi 的
         * "第二个标记" 规则：两个 `_`/`^` 之间允许有空白，但第二个必须是**另一种**
         * 标记（同种交给外层 `parseSequence` 的下一轮），例如 `x^2 _3` 是 `x²₃`。
         */
        private fun parseScripts(initialMarker: Char): String? {
            var sub: String? = null
            var sup: String? = null
            val order = ArrayList<Char>(2)
            var failed = false
            fun parseOne(marker: Char) {
                val value = parseRequiredArgument() ?: run { failed = true; return }
                if (marker == '_') sub = value else sup = value
                order.add(marker)
            }
            parseOne(initialMarker)
            var next = position
            while (next < source.length && source[next].isWhitespace()) next++
            if (next < source.length && (source[next] == '^' || source[next] == '_') && source[next] != initialMarker) {
                position = next + 1
                parseOne(source[next])
            }
            if (failed) return null
            // pi 在这里用的是 `subUnicode ?? formatScript(scripts.sub ?? "", kind)`，而
            // `formatUnicodeScript` 就是 `formatScript` 的前半段 —— 两个三元表达式
            // 化简下来就是这一行。
            return order.joinToString("") { marker ->
                formatScript((if (marker == '_') sub else sup) ?: "", marker == '_')
            }
        }

        /** `latex.ts:1013` `parseCommand`. */
        private fun parseCommand(): String? {
            position++
            if (position >= source.length) return null
            val first = source[position]
            if (first == '\n' || first == '\r') {
                // `latex.ts:1019-1026`: a backslash before a line break is a
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

            // `latex.ts:1043-1190`, in pi's own order: the first match wins.
            if (command == "\\") return "\n"
            if (command in SPACING_COMMANDS) return " "
            if (command in NEGATIVE_SPACING_COMMANDS) return NEGATIVE_SPACE
            if (command in FONT_SWITCH_COMMANDS) {
                // `latex.ts:1050-1056`：字体切换命令自己画空白，并且**吃掉后面的空白**
                // —— 所以 `\rm x` 是 `x`（不是 ` x`），`a\bf   b` 是 `ab`。
                while (position < source.length && source[position].isWhitespace()) position++
                return ""
            }
            if (command in IGNORED_COMMANDS) return ""
            if (command in CHAR_ESCAPES) return command
            if (command == "|") return "\u2016"
            if (command == "not") {
                // `latex.ts:1074-1085`.
                val value = (parseRequiredArgument() ?: return null).trim()
                NEGATED_SYMBOLS[value]?.let { return " $it " }
                if (value.isEmpty()) return null
                return " " + value[0] + "\u0338" + value.substring(1) + " "
            }
            if (command in LIMIT_OPERATORS) {
                // `latex.ts:1086-1088`：**在符号表之前**。11 个命令里 `inf`/`lim`/
                // `liminf`/`limsup`/`max`/`min`/`sup` 同时是具名算子、`sum` 类则在符号表里
                // —— pi 一律按"算子 + 方括号下限"走（`\lim_{x}` 是 `lim[x]`），
                // 所以这个分支的位置就是语义。
                return parseOperator(command, InlineLowerStyle.BRACKET, spaced = true) ?: return null
            }
            SYMBOLS[command]?.let { symbol ->
                // `latex.ts:1090-1096`. 上下限在 display 下才上下排（`latex.ts:1237`，
                // 未移植），行内路径里 pi 也走 parseOperator，把下限排成下标
                // （`\sum_{i=1}^{n}` → `∑ᵢ₌₁ⁿ`）；这里的差别是 parseOperator 会先
                // `normalizeOutput(...).replaceAll(" ", "")` 再格式化 —— `\sum_{n \to \infty}`
                // 是 `∑_(n→∞)`（下标里没有空格），走外层 `parseSequence` 会得到
                // `∑_(n → ∞)`。
                if (command in DISPLAY_LIMIT_SYMBOLS) {
                    return parseOperator(symbol, InlineLowerStyle.SCRIPT, spaced = false) ?: return null
                }
                // `latex.ts:1095`：乘法算子与关系符两侧补空格（`a\le b` 是 `a ≤ b`）。
                // 关系符列表见 [RELATION_COMMANDS]；`=`/`<`/`>` 三个字面量由
                // `parseSequence` 自己那一支处理（`latex.ts:905`）。
                return if (command == "cdot" || command == "times" || command in RELATION_COMMANDS) {
                    " $symbol "
                } else {
                    symbol
                }
            }
            if (command in NAMED_OPERATORS) {
                // `latex.ts:1097-1099`：pi 把算子名包进哨兵，由 `normalizeOutput` 决定
                // 要不要在两侧补空格（`2\sin x` 是 `2 sin x`，`\sin(x)` 不动）。
                return NAMED_OPERATOR_START + command + NAMED_OPERATOR_END
            }
            if (command in SIZE_COMMANDS) return ""
            if (command == "left" || command == "middle" || command == "right") {
                // `latex.ts:1103-1108`: the delimiter that follows is parsed as
                // a symbol by the next iteration; `\left.` is invisible.
                if (position < source.length && source[position] == '.') position++
                return ""
            }
            if (command == "frac" || command == "dfrac" || command == "tfrac") {
                // `latex.ts:1109-1122`, minus the `shouldStack` branch: this port
                // never stacks, so a fraction is always `a/b`. That is pi's own
                // result wherever `display` is false (`latex.ts:1110`、`:1121`),
                // i.e. for every inline formula; inside `$$...$$` pi would stack
                // instead (class note).
                val numerator = parseRequiredArgument() ?: return null
                val denominator = parseRequiredArgument() ?: return null
                return formatFraction(numerator, denominator)
            }
            if (command == "sqrt") {
                // `latex.ts:1124-1137`, including `\sqrt[3]`.
                val degree = parseOptionalArgument()?.trim()
                val value = parseRequiredArgument() ?: return null
                if (degree == null || degree == "2") return formatRoot(value)
                if (degree == "3") return formatRoot(value, "\u221b")
                if (degree == "4") return formatRoot(value, "\u221c")
                return formatScript(degree, false) + formatRoot(value)
            }
            if (command == "boxed" || command == "fbox") {
                // `latex.ts:1138-1140`.
                return "[" + (parseRequiredArgument() ?: return null).trim() + "]"
            }
            if (command == "binom" || command == "dbinom" || command == "tbinom") {
                // `latex.ts:1141-1143`.
                val top = parseRequiredArgument() ?: return null
                val bottom = parseRequiredArgument() ?: return null
                return "(" + top + " choose " + bottom + ")"
            }
            ACCENTS[command]?.let { accent ->
                // `latex.ts:1144-1148`: one base character takes a combining
                // mark; anything longer keeps the command name.
                val value = parseRequiredArgument() ?: return null
                return if (value.length == 1) value + accent else command + "(" + value + ")"
            }
            if (command == "mathbb") {
                // `latex.ts:1149-1152`.
                val value = parseRequiredArgument() ?: return null
                return value.map { BLACKBOARD[it.toString()] ?: it.toString() }.joinToString("")
            }
            if (command == "operatorname") {
                // `latex.ts:1153-1160`：名字由 `parseOperator` 排版，所以
                // `\operatorname*{argmax}_{x}` 与 `\argmax_{x}` 一样是方括号下限
                // （`argmax[x]`）—— 差别只在 `*` 决定的 displayLimits，而行内两条路
                // 都不上下排。把名字直接印出来会得到 `argmax_(x)`。
                if (position < source.length && source[position] == '*') position++
                val operator = normalizeOutput((parseRequiredArgument() ?: return null))
                return parseOperator(operator.trim(), InlineLowerStyle.BRACKET, spaced = true) ?: return null
            }
            if (command == "mod" || command == "bmod") return " mod "
            if (command == "pmod" || command == "pod") {
                // `latex.ts:1164-1167`.
                val value = (parseRequiredArgument() ?: return null).trim()
                return if (command == "pmod") " (mod $value)" else " ($value)"
            }
            if (command == "overset" || command == "stackrel") {
                // `latex.ts:1168-1171`.
                val upper = parseRequiredArgument() ?: return null
                val value = (parseRequiredArgument() ?: return null).trim()
                return value + formatScript(upper, false)
            }
            if (command == "underset") {
                // `latex.ts:1173-1176`.
                val lower = parseRequiredArgument() ?: return null
                val value = (parseRequiredArgument() ?: return null).trim()
                return value + formatScript(lower, true)
            }
            if (command in PLAIN_WRAPPERS) {
                // `latex.ts:1178-1181`.
                val value = parseRequiredArgument() ?: return null
                return if (command.startsWith("text") || command == "mbox") value else value.trim()
            }
            if (command == "begin") {
                // `latex.ts:1183-1184` hands `\begin` to `parseEnvironment`
                // (`latex.ts:1331-1422`), which builds layout nodes: the eight
                // grid environments (`latex.ts:1384-1386`, `:1410-1468`),
                // `cases` (`:1379`), `aligned`/`gather`/`split` (`:1355-1377`).
                // Every one of them is drawn by `renderLayout`, which this port
                // does not have.
                // Returning `null` makes the whole formula unrenderable, so the
                // caller prints the source text as written — pi's own recovery
                // for a formula it cannot render (`markdown.ts:509`), applied one
                // level earlier than pi applies it. See the class note for why the
                // layout is not ported.
                return null
            }
            // `latex.ts:1185-1188` 的 `\end` 与 `:1190-1191` 的未知命令在 pi 里是同一件事
            // （`supported = false`，返回值只有 `\end` 那支会用到、而它已经不可渲染），
            // 在本文件里也归成同一件事：返回 `null`，调用方保留原文。
            return null
        }

        /**
         * `latex.ts:1194-1250` `parseOperator`，**minus the layout branch**
         * （`latex.ts:1237-1240` 的 `operator` 节点）：上下限只在 display 下才上下排，
         * 那一支要 `renderLayout`（class note）。
         *
         * 为什么必须有它，而不是让外层 `parseSequence` 去处理 `_`/`^`：pi 在这里对
         * 参数做了两件外层不做的事 ——
         *  ① 下限用方括号而不是下标（`inlineLowerStyle = "bracket"`，`\lim_{x}` 是
         *     `lim[x]`，而 `x_{a}` 是 `xₐ`）；
         *  ② 参数先 `normalizeOutput(...).replaceAll(" ", "")`（`latex.ts:1223`），
         *     所以 `\sup_{x \in A}` 是 `sup[x∈A]`，下标里不留空格。
         * 另外 `\limits`/`\nolimits` 这个修饰词只有这里认（`latex.ts:1202-1208`）。
         *
         * `displayLimits` 参数没有跟着搬过来：它只参与上面那个 layout 分支
         * （`latex.ts:1237`），在这一支里没有任何可观察效果。
         */
        private fun parseOperator(operator: String, inlineLowerStyle: InlineLowerStyle, spaced: Boolean = false): String? {
            var lower: String? = null
            var upper: String? = null
            // 修饰词之前的空白与 `\limits` 本身都不进输出；`(?![A-Za-z])` 让 `\limitsfoo`
            // 不算 `\limits`（pi 的 `latex.ts:1205`）。
            var modifierPosition = position
            while (modifierPosition < source.length && (source[modifierPosition] == ' ' || source[modifierPosition] == '\t')) {
                modifierPosition++
            }
            val modifier = LIMITS_MODIFIER.find(source, modifierPosition)
            if (modifier != null && modifier.range.first == modifierPosition) {
                position = modifierPosition + modifier.value.length
            }
            while (true) {
                var scriptPosition = position
                while (scriptPosition < source.length && (source[scriptPosition] == ' ' || source[scriptPosition] == '\t')) {
                    scriptPosition++
                }
                if (scriptPosition >= source.length) break
                val kind = source[scriptPosition]
                if (kind != '_' && kind != '^') break
                position = scriptPosition + 1
                val value = normalizeOutput(parseRequiredArgument() ?: return null).replace(" ", "")
                if (kind == '_') {
                    // `latex.ts:1228-1230`：同一个算子出现两个下限是错误（pi 置
                    // `supported = false`；这里直接让整条公式回原文）。
                    if (lower != null) return null
                    lower = value
                } else {
                    if (upper != null) return null
                    upper = value
                }
            }
            var rendered = operator
            if (lower != null) {
                rendered += if (inlineLowerStyle == InlineLowerStyle.BRACKET) "[$lower]" else formatScript(lower, true)
            }
            if (upper != null) rendered += formatScript(upper, false)
            return if (spaced) " $rendered " else rendered
        }

        /**
         * `latex.ts:1252-1298` `parseRequiredArgument` / `parseRequiredArgumentValue`，
         * 外加 pi 的**前导空白跳过**（`latex.ts:1261-1263`）：`\frac {a} {b}` 是 `a/b`，
         * 不跳的话 ` {a}` 会被当成参数本身，得到 `()/ab` 这种结果。
         *
         * pi 的 `stackFractions` 参数（同一个 `\frac` 在 display 下嵌套时是否继续堆叠）
         * 属于没有移植的 layout 支，所以这里没有这个参数。
         */
        private fun parseRequiredArgument(): String? {
            while (position < source.length && source[position].isWhitespace()) position++
            if (position >= source.length) return null
            if (source[position] == '{') {
                position++
                return parseSequence('}')
            }
            // `latex.ts:1272-1279`: one character or one command.
            val character = source[position]
            if (character == '\\') return parseCommand()
            position++
            return character.toString()
        }

        /**
         * `latex.ts:1280-1296` `parseOptionalArgument`：`[ \t]` 之后必须紧跟 `[`，
         * 取到**下一个** `]`（pi 用的是 `indexOf`，不认嵌套、也不管转义）。
         *
         * pi 对取出来的度数调 `renderNested`（再起一个 LatexParser），这里用
         * `parseSequence(']')` 就地解析。两者在正常输入上等价；差别只在
         * `\sqrt[a\]b]{x}` 这种带转义的方括号里 —— pi 会在被转义的 `]` 处切断，
         * 这里会因为 `\]` 是未知命令而整条回原文。宁可让这种输入回原文，也不去
         * 复刻一个"切在转义符上"的行为。
         */
        private fun parseOptionalArgument(): String? {
            while (position < source.length && (source[position] == ' ' || source[position] == '\t')) position++
            if (position >= source.length || source[position] != '[') return null
            position++
            return parseSequence(']')
        }
    }

    /** `latex.ts:1195`：下限的排法。项目符号表里没有"运算符"这种角色，所以用枚举。 */
    private enum class InlineLowerStyle { BRACKET, SCRIPT }

    /**
     * `latex.ts:1205` 的 `/^\\(limits|nolimits)(?![A-Za-z])/`。pi 是对
     * `source.slice(position)` 求匹配，所以 `^` 表示"从当前位置开始"；Kotlin 的
     * `Regex.find(input, startIndex)` 不会把 `^` 当成 `startIndex`
     * （`^` 永远指输入开头），所以这里去掉锚点，改由调用点比较 `range.first`。
     */
    private val LIMITS_MODIFIER = Regex("\\\\(limits|nolimits)(?![A-Za-z])")

    /**
     * `latex.ts:525` `NEGATIVE_SPACE`. Pi uses a NUL sentinel so that the
     * trimming behaviour cannot collide with real output; the same value is
     * reused here.
     */
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** `latex.ts:970-982`: `\$`, `\%`, `\#`, `\_`, `\&` and the brace escapes. */
private val CHAR_ESCAPES: Set<String> = setOf("{", "}", "$", "%", "#", "_", "&")
