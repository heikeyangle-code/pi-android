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
 * ## pi 的竖直排版（layout）：已移植
 *
 * `LatexParser` 会把一部分构造变成 *layout 节点*，再由 [renderLayout] 拼成一小块
 * 字符网格（`latex.ts:737`）：
 *
 * * `\frac` 在 display 下、且不是从脚本里进来的时候，排成
 *   分子 / 一条 `─` / 分母（`latex.ts:1110` 的 `shouldStack`，画在 `latex.ts:748`）；
 * * 带上下限的算子（`\sum`、`\int`、`\lim`…）在 display 下把限排到符号上下
 *   （`latex.ts:1237`，画在 `latex.ts:762`）；
 * * 八种网格环境（`array`、`matrix`、`smallmatrix`、`pmatrix`、`bmatrix`、
 *   `Bmatrix`、`vmatrix`、`Vmatrix`）变成带定界符的网格（`latex.ts:1384-1386`
 *   分派、`latex.ts:1422-1481` 补列宽并加 `⎛⎝ ⎞⎠` 一族）；`cases`（`latex.ts:1393`）
 *   与 `aligned`/`gather`/`split`（`latex.ts:1355-1377`）是同一套机制、不带定界符；
 * * `parseScripts` 在 display 下也能把上下标排成两行（`latex.ts:982` 的
 *   `needsLayout`），判据有三层，见 [LatexParser.parseScripts] 的注释。
 *
 * 三处里只有两处被 `display` 门控（分数与算子上限）；**环境不受门控**：`\begin`
 * 直接进 [LatexParser.parseEnvironment]（`latex.ts:1183`），多行一定变成 layout
 * 节点，而 `renderLatex` 只要收过节点就一定跑 [renderLayout]
 * （`latex.ts:1497-1505`）。所以行内的 `$\begin{pmatrix}…\end{pmatrix}$` 在 pi 里
 * 也是网格，这里也一样。
 *
 * **网格能不能画到屏幕上，是另一件事（PiMarkdown 侧）。** [preprocess] 把渲染结果
 * 写回 markdown *源文本*，而渲染器的 annotator 会把段落内的换行变成**空格**
 * （`MarkdownAnnotatorConfig.eolAsNewLine` 默认 `false`：
 * `annotator/AnnotatedStringKtx.kt` 的 `EOL -> if (eolAsNewLine) append('\n') else
 * append(' ')`）。所以块级公式必须走一条**保行**的通道（库的 math 节点 + `custom`
 * 槽的组件，或一个专用围栏），否则这块网格会在出门时被压回一行 —— 那比不画更糟。
 * 通道不在这个文件里；[toUnicode] 只保证"算出来的字符串与 pi 逐字节相同"。
 *
 *  * **Known limit, stated plainly:** 这里没有、也不会加一个真正的排版引擎。一条公式
 * 要么归约成 pi 自己会画的那串 Unicode（含上面那块网格），要么整条按原文显示
 * （pi 的回退：`markdown.ts:509`、`:649` 都打印 `latexToken.raw`）。`\begin{pmatrix}`
 * 画不出来时把原文印出来是诚实的；画一块自己都算不对的网格不是。
 * `docs/known-gaps.md` §A2 记着当前的偏差与证据。
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
     * `latex.ts:1488` `renderLatex`：一条公式的 Unicode 近似，或 `null`（用到了这个
     * 移植不认识的东西时）。
     *
     * `display` 与 pi 的同名参数同义（`markdown.ts:649` 行内传 `false`、
     * `markdown.ts:509` 块级传 `true`），它决定三件事：
     *  - `\frac` 是否堆叠（`latex.ts:1110` 的 `shouldStack`）；
     *  - 带上下限的算子是否把限排到上下（`latex.ts:1237`）；
     *  - `parseScripts` 里的脚本是否变成上下两行（`latex.ts:982` 的 `needsLayout`）。
     *
     * 环境（矩阵、`cases`、`aligned`）**不受** `display` 门控：`\begin` 直接进
     * `parseEnvironment`（`latex.ts:1183`），多行一定变成 layout node，而只要收过
     * layout node 就一定会跑 [renderLayout] —— 所以 pi 连行内的 `\begin{pmatrix}`
     * 也画网格，这里也一样。
     */
    fun toUnicode(source: String, display: Boolean = false): String? {
        val nodes = ArrayList<LayoutNode>()
        val parser = LatexParser(source, nodes, display)
        val rendered = parser.render() ?: return null
        if (nodes.isEmpty()) return rendered.replace(PROTECTED_SPACE, " ")
        return finishLayout(renderLayout(rendered, nodes))
    }

    /**
     * `latex.ts:1488-1506` `renderLatex` 的收尾：算出网格后剥掉**所有非空行的公共
     * 左缩进**、逐行 `trimEnd`、再整体 `trimEnd`，最后把 [PROTECTED_SPACE] 换回普通
     * 空格。
     *
     * 两处容易抄错的细节：
     *  - 缩进用的是 `line.length`（UTF-16 长度）而不是显示宽度（pi 就是 `slice`），
     *    所以一行里有 CJK 时剥掉的字符数与列数不是一回事；
     *  - 全是空行时 `Math.min(...[])` 在 JS 里是 `Infinity`，每一行都会被切空 ——
     *    这里用 `Int.MAX_VALUE` 复刻同一个结果（返回空串）。
     */
    private fun finishLayout(layout: Layout): String {
        val lines = layout.lines
        val indentation = lines.filter { it.trim().isNotEmpty() }
            .minOfOrNull { it.length - it.trimStart().length } ?: Int.MAX_VALUE
        return lines
            .joinToString("\n") { it.drop(indentation).trimEnd() }
            .trimEnd()
            .replace(PROTECTED_SPACE, " ")
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
     * 块级公式：`markdown.ts:505-517` 用 `display: true` 渲染一个 `latexBlock`，
     * 于是 `\frac` 堆叠、算子的限上下排、网格环境成块（见类注释）。
     *
     * `display` 之外还有 pi 的另一个决定：整块结果**独占一段**
     * （`markdown.ts:511-513` 把每一行单独推进文本缓冲），所以显示式公式永远不会被
     * 粘进句子中间。返回的 `\n` 包裹就是让这件事透过"写回 markdown 源文本"这个
     * 机制看得见 —— pi 那边是直接把行推进自己的缓冲，没有这两个 `\n`。
     *
     * 能不能真的画出多行，取决于调用方有没有保行通道（类注释末尾）。
     */
    fun toDisplayUnicode(source: String): String? {
        val body = source.trim().removePrefix("$$").removeSuffix("$$").trim()
        return displayBlock(body)
    }

    /**
     * 显示式公式的最终形状：`null` = 这条公式画不出来（调用方保留原文），否则是
     * **独占一段**的 `\n<渲染结果>\n`（见 [toDisplayUnicode] 的说明）。
     */
    /** 显示式 body 的渲染结果（可能是多行网格），没画出来时 `null`。 */
    private fun displayRendered(body: String): String? = toUnicode(body.trim(), display = true)

    private fun displayBlock(body: String): String? = displayRendered(body)?.let { "\n$it\n" }

    // ---------------------------------------------------------------------
    // 竖直排版：latex.ts:681-809 的 layout 节点与 renderLayout
    // ---------------------------------------------------------------------

    /**
     * 一个待排版的节点（`latex.ts:681-696` 的 `LayoutNode`）。
     *
     * `Fraction`/`Operator`/`Script` 的字段都是**已经 normalizeOutput 过的文本**
     * （可能还含布局标记，所以 [renderLayout] 对它们递归）；`Matrix` 的 `lines` 是
     * 已经拼好的行 —— `parseSequence` 的 `.` 那一支还要**就地**往最后一行追加一个
     * 字符，所以是 `MutableList`。
     */
    private sealed interface LayoutNode {
        class Fraction(val numerator: String, val denominator: String) : LayoutNode
        class Operator(val operator: String, val lower: String?, val upper: String?) : LayoutNode
        class Script(val lower: String?, val upper: String?) : LayoutNode
        class Matrix(val lines: MutableList<String>, val baseline: Int) : LayoutNode
    }

    /** 一块排好的字符网格（`latex.ts:688-692`）：行、总宽、基线所在行号。 */
    private class Layout(val lines: List<String>, val width: Int, val baseline: Int)

    /**
     * `latex.ts:707` `padLayoutLine`：左对齐（或居中）补到 [width] 列。
     *
     * `centered` 时左边是 `floor(padding / 2)`，余下的都在右边 —— pi 就是这么写的，
     * 奇数差的那一列留在右边（差值只能是 0 或 1，因为它只用来居中小于等于宽度的内容）。
     */
    private fun padLayoutLine(line: String, width: Int, centered: Boolean = false): String {
        val padding = maxOf(0, width - layoutWidth(line))
        val left = if (centered) padding / 2 else 0
        return " ".repeat(left) + line + " ".repeat(padding - left)
    }

    /**
     * `latex.ts:717` `joinLayouts`：把同一行里的若干块**按基线对齐**横向拼起来，
     * 每块补到自己的宽度；空行只 `trimEnd` 右边。
     *
     * 基线是"块内第几行与整行的文字基线对齐"：`baseline` 越大说明这块越靠下。
     * 整行的基线取各块的最大值，所以比它靠上的块（`baseline` 小）会在下面留白。
     */
    private fun joinLayouts(layouts: List<Layout>): Layout {
        if (layouts.isEmpty()) return Layout(listOf(""), 0, 0)
        val baseline = layouts.maxOf { it.baseline }
        val below = layouts.maxOf { it.lines.size - it.baseline - 1 }
        val lines = ArrayList<String>(baseline + below + 1)
        for (row in 0..baseline + below) {
            val builder = StringBuilder()
            for (layout in layouts) {
                val sourceRow = row - baseline + layout.baseline
                builder.append(
                    if (sourceRow >= 0 && sourceRow < layout.lines.size) {
                        padLayoutLine(layout.lines[sourceRow], layout.width)
                    } else {
                        " ".repeat(layout.width)
                    },
                )
            }
            lines.add(builder.toString().trimEnd())
        }
        return Layout(lines, layouts.sumOf { it.width }, baseline)
    }

    /**
     * `latex.ts:737` `renderLayout`：把带布局标记的文本画成字符网格。
     *
     * `source` 是解析阶段拼出来的字符串，里面每个布局节点都是一个
     * `LAYOUT_MARKER_START <下标> LAYOUT_MARKER_END`；标记之间的**字面文本**按
     * pi 的规则 trim，而紧挨着 `matrix` 的空格要保住（`latex.ts:751-759`、
     * `:815-819`）—— 那是矩阵与左右文字之间唯一的分隔，`\left( \begin{matrix}…`
     * 这类写法全靠它。
     *
     * 一条公式的**多行来源**在这里汇合：分数（上下叠）、算子上限、脚本（上下标）、
     * 矩阵（多行一起进 [Matrix]），以及源文本里 `\\` 换出来的换行。
     */
    private fun renderLayout(source: String, nodes: List<LayoutNode>): Layout {
        val renderedLines = ArrayList<String>()
        var firstBaseline = 0
        for (sourceLine in source.split('\n')) {
            val layouts = ArrayList<Layout>()
            var position = 0
            var previousNode: LayoutNode? = null
            for (match in LAYOUT_MARKER_PATTERN.findAll(sourceLine)) {
                val index = match.range.first
                val node = nodes.getOrNull(match.groupValues[1].toInt()) ?: continue
                if (index > position) {
                    val sliced = sourceLine.substring(position, index)
                    val trimmed = (if (previousNode != null) sliced.trimStart() else sliced).trimEnd()
                    val preserveLeadingSpace = previousNode is LayoutNode.Matrix && sliced.firstOrNull()?.isWhitespace() == true
                    val preserveTrailingSpace = node is LayoutNode.Matrix && sliced.lastOrNull()?.isWhitespace() == true
                    val text = if (trimmed.isNotEmpty()) {
                        (if (preserveLeadingSpace) " " else "") + trimmed + (if (preserveTrailingSpace) " " else "")
                    } else if (preserveLeadingSpace || preserveTrailingSpace) {
                        " "
                    } else {
                        ""
                    }
                    layouts.add(Layout(listOf(text), layoutWidth(text), 0))
                }
                when (node) {
                    is LayoutNode.Fraction -> {
                        val numerator = renderLayout(node.numerator, nodes)
                        val denominator = renderLayout(node.denominator, nodes)
                        val contentWidth = maxOf(numerator.width, denominator.width, 1)
                        val width = contentWidth + 2
                        layouts.add(
                            Layout(
                                lines = numerator.lines.map { padLayoutLine(it, width, centered = true) } +
                                    (" " + "─".repeat(contentWidth) + " ") +
                                    denominator.lines.map { padLayoutLine(it, width, centered = true) },
                                width = width,
                                baseline = numerator.lines.size,
                            ),
                        )
                    }
                    is LayoutNode.Operator -> {
                        val contentWidth = maxOf(
                            layoutWidth(node.operator),
                            node.lower?.let { layoutWidth(it) } ?: 0,
                            node.upper?.let { layoutWidth(it) } ?: 0,
                        )
                        val lines = ArrayList<String>(3)
                        node.upper?.let { lines.add(padLayoutLine(it, contentWidth, centered = true) + " ") }
                        lines.add(padLayoutLine(node.operator, contentWidth, centered = true) + " ")
                        node.lower?.let { lines.add(padLayoutLine(it, contentWidth, centered = true) + " ") }
                        layouts.add(Layout(lines, contentWidth + 1, if (node.upper == null) 0 else 1))
                    }
                    is LayoutNode.Script -> {
                        val upper = node.upper?.let { renderLayout(it, nodes) }
                        val lower = node.lower?.let { renderLayout(it, nodes) }
                        val width = maxOf(upper?.width ?: 0, lower?.width ?: 0)
                        layouts.add(
                            Layout(
                                lines = (upper?.lines?.map { padLayoutLine(it, width) } ?: emptyList()) +
                                    " ".repeat(width) +
                                    (lower?.lines?.map { padLayoutLine(it, width) } ?: emptyList()),
                                width = width,
                                baseline = upper?.lines?.size ?: 0,
                            ),
                        )
                    }
                    is LayoutNode.Matrix -> {
                        val width = maxOf(0, node.lines.maxOfOrNull { layoutWidth(it) } ?: 0)
                        layouts.add(Layout(node.lines.map { padLayoutLine(it, width) }, width, node.baseline))
                    }
                }
                position = match.range.last + 1
                previousNode = node
            }
            if (position < sourceLine.length) {
                val sliced = sourceLine.substring(position)
                val trimmed = if (previousNode != null) sliced.trimStart() else sliced
                val text = if (previousNode is LayoutNode.Matrix && sliced.firstOrNull()?.isWhitespace() == true) {
                    " $trimmed"
                } else {
                    trimmed
                }
                layouts.add(Layout(listOf(text), layoutWidth(text), 0))
            }
            val lineLayout = joinLayouts(layouts)
            if (renderedLines.isEmpty()) firstBaseline = lineLayout.baseline
            renderedLines.addAll(lineLayout.lines)
        }
        return Layout(renderedLines, maxOf(0, renderedLines.maxOfOrNull { layoutWidth(it) } ?: 0), firstBaseline)
    }

    /**
     * 网格里一格文本占几列。pi 用的是 `visibleWidth`（`utils.ts:250` → 字形分段 +
     * `get-east-asian-width` + emoji 规则），这里只需要它在本场景下的行为：
     * 汉字/全角算 2 列、组合记号算 0 列、其余（拉丁、希腊、`∑ ∫ √ ─ ⎛` 这些
     * Ambiguous 数学符号）算 1 列 —— 实测 `get-east-asian-width` 对这些符号返回 1。
     *
     * 网格里不会有 ANSI 转义序列（那是终端才有的东西），所以 pi 的剥 ANSI 一步不需要。
     */
    private fun layoutWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            width += when {
                codePoint == 0x200D -> 0
                codePoint in 0xFE00..0xFE0F -> 0
                codePoint in 0x0300..0x036F -> 0
                codePoint in 0x1AB0..0x1AFF -> 0
                codePoint in 0x1DC0..0x1DFF -> 0
                codePoint in 0x20D0..0x20FF -> 0
                codePoint in 0xFE20..0xFE2F -> 0
                codePoint in 0x1100..0x115F -> 2
                codePoint in 0x2E80..0x303E -> 2
                codePoint in 0x3041..0x33FF -> 2
                codePoint in 0x3400..0x4DBF -> 2
                codePoint in 0x4E00..0x9FFF -> 2
                codePoint in 0xA000..0xA4CF -> 2
                codePoint in 0xAC00..0xD7A3 -> 2
                codePoint in 0xF900..0xFAFF -> 2
                codePoint in 0xFE30..0xFE6F -> 2
                codePoint in 0xFF00..0xFF60 -> 2
                codePoint in 0xFFE0..0xFFE6 -> 2
                codePoint in 0x1F300..0x1F64F -> 2
                codePoint in 0x1F900..0x1F9FF -> 2
                codePoint in 0x20000..0x3FFFD -> 2
                else -> 1
            }
        }
        return width
    }

    /**
     * 布局标记（`latex.ts:700-703`）与**受保护空格**（`:704`）。
     *
     * pi 用补充平面私用区 `U+F0000`…`U+F0002`；这里用 BMP 私用区 `U+E000`…`U+E002`，
     * 理由与具名算子哨兵相同（[NAMED_OPERATOR_START] 的注释：Java 正则的 lookbehind
     * 要单字符）。四个值互不相同、且都不进输出，所以码位本身不是契约。
     */
    private const val LAYOUT_MARKER_START = "\uE000"
    private const val LAYOUT_MARKER_END = "\uE001"
    private const val PROTECTED_SPACE = "\uE002"

    /**
     * `latex.ts:1384`：八种"画成网格"的环境。`array` 的 body 前面还有一个 `{列格式}`
     * 方案（`latex.ts:1385`），`renderMatrix` 之前先把它去掉。
     */
    private val GRID_ENVIRONMENTS = setOf(
        "array",
        "matrix",
        "smallmatrix",
        "pmatrix",
        "bmatrix",
        "Bmatrix",
        "vmatrix",
        "Vmatrix",
    )

    /**
     * `latex.ts:1436-1442` 的五套定界符：`[左上, 右上, 左中, 右中, 左下, 右下]`。
     * `array`/`matrix`/`smallmatrix` 不在表里（它们没有定界符），所以这里只放五种。
     */
    private val MATRIX_DELIMITERS: Map<String, List<String>> = mapOf(
        "pmatrix" to listOf("⎛", "⎞", "⎜", "⎟", "⎝", "⎠"),
        "bmatrix" to listOf("⎡", "⎤", "⎢", "⎥", "⎣", "⎦"),
        "Bmatrix" to listOf("⎧", "⎫", "⎨", "⎬", "⎩", "⎭"),
        "vmatrix" to listOf("│", "│", "│", "│", "│", "│"),
        "Vmatrix" to listOf("║", "║", "║", "║", "║", "║"),
    )

    /** `latex.ts:702`：`LAYOUT_MARKER_START <十进制下标> LAYOUT_MARKER_END`。 */
    private val LAYOUT_MARKER_PATTERN = Regex("$LAYOUT_MARKER_START(\\d+)$LAYOUT_MARKER_END")

    /** `latex.ts:703`：**结尾**的布局标记，`parseSequence` 的 `.` 那一支要用。 */
    private val TRAILING_LAYOUT_MARKER_PATTERN = Regex("$LAYOUT_MARKER_START(\\d+)$LAYOUT_MARKER_END$")

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
    fun preprocess(markdown: String): String = prepare(markdown).text

    /**
     * 写回 markdown 源时用来保护**前导空白**的字符：不换行空格 U+00A0。
     *
     * 为什么需要它：markdown 里 **≥4 个前导 ASCII 空格 = 缩进代码块**，而网格的前导
     * 空格正是 `renderLayout` 居中对齐的产物（`renderLatex` 只剥掉所有非空行的**公共**
     * 缩进，剩下的每行缩进会露给 markdown）。被 markdown 当成代码块的那些行会被单独
     * 画成一个带「复制」头的代码框 —— 一条公式就被切成"一半代码框 + 一半正文"，用户
     * 报的就是这个。
     *
     * 为什么不是 pi 的 `PROTECTED_SPACE`（`latex.ts:704`，本文件里也在用）：那是**布局
     * 内部**的哨兵，`renderLatex` 收尾一定会把它换回普通空格、**不进输出**；而写进
     * markdown 源的字符必须留在源里、并且**看起来就是空格**。私用区字符在字体里没有
     * 字形，会画成豆腐块。NBSP 满足两件事：markdown 的缩进规则只认 ASCII 空格与制表符
     * （CommonMark），而等宽字体里 NBSP 与空格同宽 —— 网格的对齐与视觉宽度因此不变。
     */
    private const val MARKDOWN_PROTECTED_SPACE = "\u00A0"

    /**
     * 把每一行的**前导空格**换成 [MARKDOWN_PROTECTED_SPACE]，其余字符（含中间的对齐
     * 空格）原样 —— 保护范围只到"markdown 会不会把它当缩进"为止。
     */
    private fun protectLeadingIndent(rendered: String): String =
        rendered.split('\n').joinToString("\n") { line ->
            val leading = line.takeWhile { it == ' ' }
            MARKDOWN_PROTECTED_SPACE.repeat(leading.length) + line.substring(leading.length)
        }

    /** 预处理的结果：改过的源文本 + "这次真的写进了一块多行网格"。 */
    class PreparedMath(val text: String, val hasDisplayGrid: Boolean)

    /**
     * 预处理，并且**报告这次有没有往源文本里写进多行网格**。
     *
     * 为什么需要这个标记：网格是**多行**文本，而库的 annotator 会把段落内的换行变成
     * 空格（`MarkdownAnnotatorConfig.eolAsNewLine` 默认 `false`），所以含网格的这条
     * 消息必须在渲染时打开 `eolAsNewLine`（见 `PiMarkdownText`）。标记只在**这次
     * 替换确实产生了多行结果**时为真：`$x^2$`、`$$x^2$$` 这类只用一行的公式不打开，
     * 别的消息一个字节都不动。
     *
     * 行内公式也要算：环境（矩阵/`cases`/`aligned`）**不受 `display` 门控**，行内的
     * `$\begin{pmatrix}…\end{pmatrix}$` 也会写成一块网格。
     */
    fun prepare(markdown: String): PreparedMath {
        // 快路径：两个定界符家族（`$` 与 `\(`/`\[`）都没有时，整篇没有任何东西可改。
        // 流式转写里这是绝大多数帧的情况。
        if (!markdown.contains('$') && !markdown.contains('\\')) return PreparedMath(markdown, false)
        val grid = BooleanArray(1)
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
                if (end < 0) return PreparedMath(out.append(markdown, fence.range.last + 1, markdown.length).toString(), grid[0])
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
            out.append(applyMath(markdown, index, start, grid))
            index = start
        }
        return PreparedMath(out.toString(), grid[0])
    }

    /** 对一段"没有代码"的区间 `[start, end)` 做两次替换；`grid[0]` 记录是否写进了多行网格。 */
    private fun applyMath(text: String, start: Int, end: Int, grid: BooleanArray): String {
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
                // `$$…$$` 的 body 要从 match.value 里剥（含可能的收尾换行）；`\[…\]`
                // 的 body 已经在 2 号组里。空 body 是 pi 的"待定"（原文照排），不动。
                val body = if (dollar.isNotEmpty()) {
                    match.value.trim().removePrefix("$$").removeSuffix("$$").trim()
                } else {
                    bracket.trim()
                }
                val rendered = if (body.isEmpty()) null else displayRendered(body)
                // **只有渲染结果本身是多行**才算网格：`$$x^2$$` 画出来是一行，独占一段
                // 的 `\n` 包裹是 App 的形状、不是网格（见 prepare 的注释）。
                if (rendered != null && rendered.contains('\n')) grid[0] = true
                if (rendered == null) match.value else "\n" + protectLeadingIndent(rendered) + "\n"
            }
        } else {
            run
        }
        return INLINE_MATH.replace(block) { match ->
            val parenthesized = match.groupValues[1]
            val bracketed = match.groupValues[2]
            when {
                // `\(…\)`：pi 没有块级形态，任何时候都是行内。
                parenthesized.isNotEmpty() -> toUnicode(parenthesized)?.let {
                    if (it.contains('\n')) {
                        grid[0] = true
                        protectLeadingIndent(it)
                    } else {
                        it
                    }
                } ?: match.value
                // `\[…\]`：行首的那种交给上面那一趟（没被替换 = pi 的待定/画不出来，
                // 保持原文）；只有行中的才是 pi 的行内 token。
                bracketed.isNotEmpty() ->
                    if (atLineStart(block, match.range.first)) match.value
                    else toUnicode(bracketed)?.let {
                        if (it.contains('\n')) {
                            grid[0] = true
                            protectLeadingIndent(it)
                        } else {
                            it
                        }
                    } ?: match.value
                // `$…$`：定界符就是首尾两个字符，形状没变。
                else -> {
                    val source = match.value
                    val rendered = toUnicode(source.substring(1, source.length - 1))
                    if (rendered != null && rendered.contains('\n')) {
                        grid[0] = true
                        protectLeadingIndent(rendered)
                    } else {
                        rendered ?: source
                    }
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
     * 184 条取自本仓库自己的 `docs` 下的全部 `.md` 与 `res/values` 开头的 `.xml`，
     * 36 条手写真实用法）：
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
    private val NAMED_OPERATOR_LEFT_SPACING =
        Regex("(?<=[\\p{L}\\p{N}\\)\\]}$LAYOUT_MARKER_END])$NAMED_OPERATOR_START")

    /** `latex.ts:654`：具名算子后面紧跟字母/数字/`√` 时补一个空格。 */
    private val NAMED_OPERATOR_RIGHT_SPACING =
        Regex("$NAMED_OPERATOR_END(?=[\\p{L}\\p{N}\u221a$LAYOUT_MARKER_START])")

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
     * 两条正则的字符类里都带着**布局标记**（`latex.ts:653-654` 的 `\u{f0001}` 与
     * `\u{f0000}`）：`\sum` 这类节点在下标排成两行时，紧挨着算子名的正是布局标记，
     * 少了它 `2\limits\sum` 这种写法就会少一个空格。
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

    /** `latex.ts:614` `normalizeScriptValue`：去掉 `= + -` 两侧的空白。 */
    private fun normalizeScriptValue(value: String): String =
        value.trim().replace(Regex("\\s*([=+-])\\s*"), "$1")

    /** `latex.ts:618-620` `formatUnicodeScript`：整串都有对应上下标字符才算成功。 */
    private fun formatUnicodeScript(value: String, subscript: Boolean): String? =
        replaceCharacters(normalizeScriptValue(value), if (subscript) SUBSCRIPTS else SUPERSCRIPTS)

    private fun formatScript(value: String, subscript: Boolean): String {
        val trimmed = normalizeScriptValue(value)
        val unicode = formatUnicodeScript(value, subscript)
        if (unicode != null) return unicode
        val prefix = if (subscript) "_" else "^"
        // pi 用 `Array.from(value).length`（**码点**数）判断"单个字符"，所以 emoji/
        // 补充平面字符算一个；Kotlin 的 `String.length` 是 UTF-16 长度，得显式数码点。
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints == 1 || (subscript && trimmed.matches(Regex("^[A-Za-z]+$")))) {
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

    private class LatexParser(
        private val source: String,
        private val layoutNodes: MutableList<LayoutNode>,
        private val display: Boolean,
    ) {

        private var position = 0

        /** `latex.ts:898-901` 的 `supported`：false 时整条公式回原文。 */
        private var supported = true

        /** `latex.ts:902` 的 `stackFractions`：只在 display 下的 `\frac` 嵌套里被关掉。 */
        private var stackFractions = true

        /** `latex.ts:903` 的 `scriptDepth`：脚本里的脚本不再单独排成上下两行。 */
        private var scriptDepth = 0

        /** `latex.ts:825`: true only when the whole source was consumed. */
        val finished: Boolean get() = position == source.length

        /**
         * `latex.ts:855-862` `render()`：解析 + `supported`/位置两项校验，
         * 通过则返回 [normalizeOutput] 过的文本。冒号前的两层校验缺一不可 ——
         * `position` 没走到末尾说明有没消费掉的尾巴（例如多出来的 `}` 分支被
         * `parseSequence` 直接返回了），在 pi 里同样是 `undefined`。
         */
        fun render(): String? {
            val rendered = parseSequence() ?: return null
            if (!supported || position != source.length) return null
            return normalizeOutput(rendered)
        }

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
                if (character == '.') {
                    // `latex.ts:929-937`：矩阵节点结尾紧跟一个 `.` 时，这个点要落进
                    // 矩阵的**最后一行**（`\begin{matrix}…\end{matrix}.` 这种写法）。
                    // 否则矩阵会被当成"一块"、点在块外面单独占一列。
                    val marker = TRAILING_LAYOUT_MARKER_PATTERN.find(result)
                    val node = marker?.let { layoutNodes.getOrNull(it.groupValues[1].toInt()) }
                    if (node is LayoutNode.Matrix) {
                        node.lines[node.lines.size - 1] = (node.lines.lastOrNull() ?: "") + character
                        position++
                        continue
                    }
                }
                result.append(character)
                position++
            }
            // `latex.ts:909-912`: a sequence that ran out before its closing
            // brace is unsupported.
            if (endCharacter != null) return null
            return result.toString()
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
                scriptDepth++
                val value = try {
                    parseRequiredArgument(stackFractions = false)
                } finally {
                    scriptDepth--
                }
                if (value == null) {
                    failed = true
                    return
                }
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
            val inline = {
                order.joinToString("") { marker ->
                    formatScript((if (marker == '_') sub else sup) ?: "", marker == '_')
                }
            }
            // `latex.ts:982-1005` 的 `needsLayout`：**三层**条件，缺一层都会把不该上下排的
            // 脚本排成两行。`canUseLayout` 是 pi 对"这块脚本适不适合上下排"的判据：
            // 出现 `/`（分数已排成一行文字）、含布局标记（里面已经有块了）、或者
            // 多个字符且不含大写与 `*`/`∗`（`_ab` 这类会画成上下两行，而 `x^{n+1}` 不会）。
            val subValue = sub
            val supValue = sup
            val canUseLayout = listOf(subValue, supValue).none { value ->
                value != null &&
                    (
                        value.contains('/') ||
                            (
                                !value.contains(LAYOUT_MARKER_START) &&
                                    value.codePointCount(0, value.length) > 1 &&
                                    !value.any { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ*∗" }
                                )
                        )
            }
            val needsLayout = display &&
                canUseLayout &&
                (
                    scriptDepth > 0 ||
                        (subValue != null && formatUnicodeScript(subValue, subscript = true) == null) ||
                        (supValue != null && formatUnicodeScript(supValue, subscript = false) == null)
                    )
            if (!needsLayout) return inline()
            val index = layoutNodes.size
            layoutNodes.add(
                LayoutNode.Script(
                    lower = subValue?.let { normalizeOutput(it) },
                    upper = supValue?.let { normalizeOutput(it) },
                ),
            )
            return "$LAYOUT_MARKER_START$index$LAYOUT_MARKER_END"
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
                return parseOperator(command, InlineLowerStyle.BRACKET, displayLimits = true, spaced = true) ?: return null
            }
            SYMBOLS[command]?.let { symbol ->
                // `latex.ts:1090-1096`. 上下限在 display 下才上下排（`latex.ts:1237`，
                // 未移植），行内路径里 pi 也走 parseOperator，把下限排成下标
                // （`\sum_{i=1}^{n}` → `∑ᵢ₌₁ⁿ`）；这里的差别是 parseOperator 会先
                // `normalizeOutput(...).replaceAll(" ", "")` 再格式化 —— `\sum_{n \to \infty}`
                // 是 `∑_(n→∞)`（下标里没有空格），走外层 `parseSequence` 会得到
                // `∑_(n → ∞)`。
                if (command in DISPLAY_LIMIT_SYMBOLS) {
                    return parseOperator(symbol, InlineLowerStyle.SCRIPT, displayLimits = true) ?: return null
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
                // `latex.ts:1109-1122`：`shouldStack` 只在 display 下、且没被
                // `stackFractions` 关掉时成立（`\frac` 的分子里再套 `\frac` 就不再叠 ——
                // 一格网格里塞不下第二层）。`parseRequiredArgument(!shouldStack)` 就是
                // 把这层开关传下去：正在堆叠的这一层，它的分子分母里的分数不堆叠。
                val shouldStack = display && stackFractions && command != "tfrac"
                val numerator = parseRequiredArgument(stackFractions = !shouldStack) ?: return null
                val denominator = parseRequiredArgument(stackFractions = !shouldStack) ?: return null
                if (shouldStack) {
                    val index = layoutNodes.size
                    layoutNodes.add(LayoutNode.Fraction(normalizeOutput(numerator), normalizeOutput(denominator)))
                    return "$LAYOUT_MARKER_START$index$LAYOUT_MARKER_END"
                }
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
                val starred = position < source.length && source[position] == '*'
                if (starred) position++
                val operator = normalizeOutput((parseRequiredArgument() ?: return null))
                return parseOperator(operator.trim(), InlineLowerStyle.BRACKET, displayLimits = starred, spaced = true)
                    ?: return null
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
                // `latex.ts:1183-1184`：`\begin{…}` 一律交给 [parseEnvironment]。
                return parseEnvironment()
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
        private fun parseOperator(
            operator: String,
            inlineLowerStyle: InlineLowerStyle,
            displayLimits: Boolean,
            spaced: Boolean = false,
        ): String? {
            var useDisplayLimits = displayLimits
            var lower: String? = null
            var upper: String? = null
            // 修饰词之前的空白与 `\limits` 本身都不进输出；`(?![A-Za-z])` 让 `\limitsfoo`
            // 不算 `\limits`（pi 的 `latex.ts:1205`）。`\limits`/`\nolimits` 只在这里
            // 被认出来 —— 它是"这个算子要不要上下排限"的显式开关。
            var modifierPosition = position
            while (modifierPosition < source.length && (source[modifierPosition] == ' ' || source[modifierPosition] == '\t')) {
                modifierPosition++
            }
            val modifier = LIMITS_MODIFIER.find(source, modifierPosition)
            if (modifier != null && modifier.range.first == modifierPosition) {
                useDisplayLimits = modifier.groupValues[1] == "limits"
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
            // `latex.ts:1237-1240`：display 下、且这个算子允许上下限时，限排成上下两行。
            // `useDisplayLimits` 说的是"这个算子在准则排版里上下排限"：`\lim`/`\sum`
            // 这类是 true（`latex.ts:1087`、`:1093`），`\operatorname*` 由那个 `*` 决定
            // （`:1153-1159`），`\sin` 不是 → 它走上面的 inline 分支。
            if (display && useDisplayLimits && (lower != null || upper != null)) {
                val index = layoutNodes.size
                layoutNodes.add(LayoutNode.Operator(operator, lower, upper))
                return "$LAYOUT_MARKER_START$index$LAYOUT_MARKER_END"
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
        private fun parseRequiredArgument(stackFractions: Boolean = true): String? {
            // `latex.ts:1252-1259`：`stackFractions` 只在**这一次**参数解析里生效，
            // 解析完要还原 —— 它由 `\frac` 的 `shouldStack` 决定，用来阻止第二层堆叠。
            val previous = this.stackFractions
            this.stackFractions = previous && stackFractions
            try {
                return parseRequiredArgumentValue()
            } finally {
                this.stackFractions = previous
            }
        }

        /** `latex.ts:1260-1279` `parseRequiredArgumentValue`。 */
        private fun parseRequiredArgumentValue(): String? {
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
        /**
         * `latex.ts:1298-1330` `readRawGroup`：读出 `{…}` 的**原文**（不解析），
         * 括号深度按 `{`/`}` 数，`\` 后面那个字符跳过（`\{`/`\}` 不算深度）。
         *
         * 环境名必须原文拿到 —— `\begin{matrix}` 里的 `matrix` 不是公式内容，
         * 不能过 [parseCommand]（那样 `\begin{align}` 会被当命令解析）。
         */
        private fun readRawGroup(): String? {
            while (position < source.length && (source[position] == ' ' || source[position] == '\t')) position++
            if (position >= source.length || source[position] != '{') return null
            position++
            val start = position
            var depth = 1
            while (position < source.length) {
                val character = source[position]
                if (character == '\\') {
                    position += 2
                    continue
                }
                if (character == '{') depth++
                if (character == '}') depth--
                if (depth == 0) {
                    val value = source.substring(start, position)
                    position++
                    return value
                }
                position++
            }
            return null
        }

        /** `latex.ts:1351` `splitEnvironmentRows`：行分隔是 `\\`，可带 `[2pt]` 这类可选参数。 */
        private val ENVIRONMENT_ROW_SEPARATOR = Regex("""\\\\(?:\[[^\]\n]*\])?""")

        private fun splitEnvironmentRows(body: String): List<String> =
            ENVIRONMENT_ROW_SEPARATOR.split(body)

        /**
         * `latex.ts:1331-1391` `parseEnvironment`。分派顺序与 pi 一致：
         * `equation` 族（只做一次嵌套渲染）、`aligned` 族（逐行拼、`&` 丢掉）、
         * `cases`、八种网格环境，其余一律不支持。
         */
        private fun parseEnvironment(): String? {
            val environment = readRawGroup() ?: return null
            val endMarker = "\\end{$environment}"
            val end = source.indexOf(endMarker, position)
            if (end < 0) return null
            val body = source.substring(position, end)
            position = end + endMarker.length
            if (environment == "equation" || environment == "equation*" || environment == "displaymath") {
                return renderNested(body)?.trim()
            }
            if (
                environment == "aligned" ||
                environment == "align" ||
                environment == "align*" ||
                environment == "alignedat" ||
                environment == "alignat" ||
                environment == "alignat*" ||
                environment == "gather" ||
                environment == "gathered" ||
                environment == "multline" ||
                environment == "multline*" ||
                environment == "split"
            ) {
                // `latex.ts:1357-1377`：`alignedat` 族的**第一个** `{…}` 是列数，
                // 丢掉；其余环境的 `&` 直接删掉（对齐信息对单列文本没意义），
                // 每行单独渲染、空行丢掉、用 `\n` 拼起来。
                val alignedAt = environment == "alignedat" || environment == "alignat" || environment == "alignat*"
                val alignmentBody = if (alignedAt) body.replace(Regex("^\\s*\\{[^}]*}"), "") else body
                val rendered = ArrayList<String>()
                for (row in splitEnvironmentRows(alignmentBody)) {
                    val cells = row.split('&')
                    val joined = if (alignedAt) {
                        cells.chunked(2).joinToString(" ") { it.joinToString("") }
                    } else {
                        cells.joinToString("")
                    }
                    // 有一行渲染不出来 ⇒ 整条公式回原文（pi 把 `supported` 置 false）。
                    // 这里不能 `mapNotNull` 掉这一行 —— 那会在屏幕上留下一段**缺行的**
                    // 公式，比原文更难看出是坏的。
                    rendered.add(renderNested(joined)?.trim() ?: return null)
                }
                return rendered.filter { it.isNotEmpty() }.joinToString("\n")
            }
            if (environment == "cases" || environment == "cases*") {
                return renderCases(body)
            }
            if (environment in GRID_ENVIRONMENTS) {
                val matrixBody = if (environment == "array") body.replace(Regex("^\\s*\\{[^}]*}"), "") else body
                return renderMatrix(environment, matrixBody)
            }
            return null
        }

        /**
         * `latex.ts:1393-1421` `renderCases`：两列（值 | 条件），值列右侧用
         * [PROTECTED_SPACE] 补齐，条件列前面按 pi 的规则加 ` if ` 或一个空格；
         * 行数大于 1 时上下加 `⎧ ⎨ ⎩`，偶数行在中间插一行只有分隔符的空行。
         *
         * 为什么用 [PROTECTED_SPACE] 而不是普通空格：这些补齐最终会被
         * `renderLayout` 的 `trimEnd` 与 `padLayoutLine` 处理，普通空格会被吃掉，
         * 而 `cases` 的列对齐要求它活到最后（收尾时统一换回空格）。
         */
        private fun renderCases(body: String): String? {
            val rows = splitEnvironmentRows(body)
                .map { row ->
                    row.split('&').map { cell ->
                        renderNested(cell, stackFractions = false)?.trim() ?: return null
                    }
                }
                .filter { row -> row.any { it.isNotEmpty() } }
            val valueWidth = maxOf(
                0,
                rows.maxOfOrNull { row -> layoutWidth((row.getOrNull(0) ?: "").replace(Regex(",\\s*$"), "")) } ?: 0,
            )
            val contents = rows.map { row ->
                val value = (row.getOrNull(0) ?: "").replace(Regex(",\\s*$"), "")
                val condition = row.getOrNull(1) ?: ""
                if (condition.isEmpty()) {
                    value
                } else {
                    val conditionPrefix = if (Regex("^(?:if|when|for|otherwise)\\b", RegexOption.IGNORE_CASE).containsMatchIn(condition)) " " else " if "
                    value + PROTECTED_SPACE.repeat(valueWidth - layoutWidth(value)) + conditionPrefix + condition
                }
            }
            if (contents.size <= 1) {
                return if (contents.isEmpty()) "" else "⎧ ${contents[0]}"
            }
            val middle = contents.size / 2
            val visualRows: List<String?> = if (contents.size % 2 == 0) {
                // pi 在一个**空行**上插分隔符（`latex.ts:1404-1406`）：偶数行时
                // 中间多出一行只有 `⎨` 的行，上下两半各占一半高度。
                ArrayList<String?>(contents.size + 1).apply {
                    addAll(contents.subList(0, middle))
                    add(null)
                    addAll(contents.subList(middle, contents.size))
                }
            } else {
                contents
            }
            val lines = visualRows.mapIndexed { index, content ->
                val delimiter = when (index) {
                    0 -> "⎧"
                    visualRows.size - 1 -> "⎩"
                    else -> "⎨"
                }
                if (content == null) delimiter else "$delimiter $content"
            }
            val index = layoutNodes.size
            layoutNodes.add(LayoutNode.Matrix(lines.toMutableList(), baseline = middle))
            return "$LAYOUT_MARKER_START$index$LAYOUT_MARKER_END"
        }

        /**
         * `latex.ts:1422-1481` `renderMatrix`：列宽 = 该列所有单元格显示宽度的最大值，
         * 单元格之间是 ` │ `，`array`/`matrix`/`smallmatrix` 只有网格，其余五种加
         * 定界符（`pmatrix` 是圆括号，`bmatrix` 是方括号，`Bmatrix` 是花括号，
         * `vmatrix`/`Vmatrix` 是竖线）。**只有一行时直接返回那一行**（不建节点，
         * 于是不会走 [renderLayout]）；`\end{matrix}.` 那个点由 `parseSequence` 追加。
         */
        private fun renderMatrix(environment: String, body: String): String? {
            val matrix = splitEnvironmentRows(body)
                .map { row ->
                    row.split('&').map { cell ->
                        renderNested(cell, stackFractions = false)?.trim() ?: return null
                    }
                }
                .filter { row -> row.any { it.isNotEmpty() } }
            val columnCount = maxOf(0, matrix.maxOfOrNull { it.size } ?: 0)
            val columnWidths = (0 until columnCount).map { column ->
                maxOf(0, matrix.maxOfOrNull { layoutWidth(it.getOrNull(column) ?: "") } ?: 0)
            }
            val rows = matrix.map { row ->
                (0 until columnCount).joinToString(" │ ") { column ->
                    val cell = row.getOrNull(column) ?: ""
                    cell + PROTECTED_SPACE.repeat(maxOf(0, columnWidths[column] - layoutWidth(cell)))
                }
            }
            val lines: List<String> = if (environment == "array" || environment == "matrix" || environment == "smallmatrix") {
                rows
            } else {
                val delimiters = MATRIX_DELIMITERS[environment] ?: return rows.joinToString("\n")
                rows.mapIndexed { index, row ->
                    val left = when (index) {
                        0 -> delimiters[0]
                        rows.size - 1 -> delimiters[4]
                        else -> delimiters[2]
                    }
                    val right = when (index) {
                        0 -> delimiters[1]
                        rows.size - 1 -> delimiters[5]
                        else -> delimiters[3]
                    }
                    "$left $row $right"
                }
            }
            if (lines.size <= 1) return lines.firstOrNull() ?: ""
            val index = layoutNodes.size
            layoutNodes.add(LayoutNode.Matrix(lines.toMutableList(), baseline = 0))
            return "$LAYOUT_MARKER_START$index$LAYOUT_MARKER_END"
        }

        /**
         * `latex.ts:1441-1458` `renderNested`：再起一个解析器，**共用**同一份
         * layoutNodes（下标连续），`display` 还要与 `stackFractions` 相与 ——
         * 嵌套里的 `\frac` 只在"外层正在堆叠"时继续堆叠。
         *
         * 失败（`undefined`）在 pi 里是把自己的 `supported` 置 false 并**返回原文**，
         * 这里返回 `null`，由调用方一路变成整条公式的 `null`：对用户是同一件事
         * （原文照排），但实现上不能只在这里 `?: ""`，否则半截渲染会漏出去。
         */
        private fun renderNested(source: String, stackFractions: Boolean = true): String? {
            val parser = LatexParser(source, layoutNodes, display && stackFractions)
            val rendered = parser.render() ?: return null
            return rendered
        }

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
