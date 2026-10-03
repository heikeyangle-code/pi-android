#!/usr/bin/env node
/**
 * **数学夹具**：pi 自己跑出来的 `renderLatex` 输出，一次性捕获，作为
 * `app/src/test/kotlin/app/pi/ui/render/PiLatexCheck.kt` 的期望值。
 *
 * ## 为什么要有这个文件
 *
 * `PiLatex.kt` 是 pi 的 `latex.ts` 的逐字移植。抄表可以逐 key 比对
 * （`tools/check-latex-tables.mjs`），**行为**不行：`\lim_{n\to\infty}` 是
 * `lim[n→∞]` 还是 `lim_(n→∞)` 取决于 `parseCommand` 里两个分支谁在前面，
 * 这种「顺序即语义」的地方没有编译器能拦。所以这里的期望值不是我读代码写的句子，
 * 而是 pi 自己的函数对每条公式的返回值 —— 同一个 pin（`pi-tui` 1.0.1）。
 *
 * ## 三段，三种断言强度
 *
 * | 段 | 是什么 | `PiLatexCheck` 怎么用 |
 * |---|---|---|
 * | `cases` | `renderLatex(src)`（行内） | **逐字断言** |
 * | `knownUnported` | pi 画得出来、端口画不出来（`\begin{…}` 那一族） | 断言**仍然不一样**（Phase B 移植后这条会红，那时把它挪进 `cases`） |
 * | `displayTargets` | `renderLatex(src, {display:true})` 与行内结果**不同**的条目 | 只记录 Phase B 的目标，本阶段不断言 |
 *
 * 「只记录」不等于「没确认」：`displayTargets` 里存的是 pi 的真实输出，Phase B 就是
 * 拿它当验收标准；本阶段断言它只会让 Phase A 红，因为堆叠分数与算子上限要
 * `renderLayout`（`latex.ts:737`），那正是 Phase B。
 *
 * ## 用法
 *
 *   node tools/collect-latex-fixtures.mjs                    # 重新生成夹具
 *   node tools/collect-latex-fixtures.mjs --check            # 只比对，不写盘（CI 用）
 *   node tools/collect-latex-fixtures.mjs --pi-tui <dir>     # 指定 pi-tui 包目录
 *   PI_TUI_DIR=<dir> node tools/collect-latex-fixtures.mjs
 *
 * 退出码：0 = 一致 / 已写盘；1 = `--check` 发现差异；2 = 跑不起来（缺 pi-tui）。
 */

import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const FIXTURE = join(REPO, "app/src/test/resources/pi-latex-fixtures/cases.json");

/** 与 `tools/check-latex-tables.mjs` 同一套解析规则（那边有完整注释）。 */
function resolvePiTui() {
	const flag = process.argv.indexOf("--pi-tui");
	const candidates = [];
	if (flag >= 0) {
		if (!process.argv[flag + 1]) {
			console.error("--pi-tui 后面要跟目录");
			process.exit(2);
		}
		candidates.push(process.argv[flag + 1]);
	}
	if (process.env.PI_TUI_DIR) candidates.push(process.env.PI_TUI_DIR);
	candidates.push(join(REPO, "build/pi-contract/node_modules/@earendil-works/pi-tui"));
	for (const candidate of candidates) {
		if (existsSync(join(candidate, "dist/latex.js"))) return candidate;
	}
	console.error("找不到 pi-tui：");
	for (const candidate of candidates) console.error(`  试过 ${candidate}`);
	process.exit(2);
}

const piTui = resolvePiTui();
const { renderLatex } = await import(join(piTui, "dist/latex.js"));
const piVersion = JSON.parse(readFileSync(join(piTui, "package.json"), "utf8")).version;

/**
 * 公式清单。**每一条都必须是能读懂的意图**，因为失败信息里只有公式本身：
 * 名字只用来在日志里指路，`source` 才是被测的东西。
 *
 * 分组顺序大致跟着 `latex.ts` 的分支顺序：符号表 → 具名算子/上下限 → 关系符 →
 * `\not` → 分数/根号 → 装饰/包装 → 参数空白 → 失败路径 → 环境。
 */
const CASES = [
	// ---- 字体切换（latex.ts:526 表 + :1050-1056 分支）----------------------
	...["bf", "cal", "it", "rm", "sf", "sl", "tt"].flatMap((font) => [
		`font-switch-${font} \\${font}{x}`,
		`font-switch-space-${font} \\${font} x`,
		`font-switch-eats-whitespace-${font} \\${font}   x`,
		`font-switch-before-command-${font} a\\${font}\\alpha`,
	]),
	"font-switch-nested {\\rm x}y",
	"font-switch-in-script x^{\\rm y}",
	"font-switch-named x\\rm x",

	// ---- 上下限算子（latex.ts:265 表，:1086-1088 判定）---------------------
	...[
		"argmax", "argmin", "inf", "injlim", "lim", "liminf",
		"limsup", "max", "min", "projlim", "sup",
	].flatMap((operator) => [
		`limit-operator-bare-${operator} \\${operator} x`,
		`limit-operator-lower-${operator} \\${operator}_{x} f(x)`,
		`limit-operator-lower-paren-${operator} \\${operator}_{n \\to \\infty} a_n`,
		`limit-operator-upper-${operator} \\${operator}^{2}`,
		`limit-operator-both-${operator} \\${operator}_{i=1}^{n}`,
	]),
	"limit-operator-limits \\lim\\limits_{n} x",
	"limit-operator-nolimits \\lim\\nolimits_{n} x",
	"limit-operator-limits-spaced \\lim \\limits_{n} x",
	"limit-operator-modifier-not-prefix \\lim\\limitsx",
	"limit-operator-bracket-not-script \\max_i",
	"limit-operator-word-arg \\lim_{n\\to\\infty}",
	"limit-operator-space-arg \\lim_ {n}",
	"limit-operator-tab-arg \\lim\t_{n}",
	"limit-operator-double-lower \\lim_{a}_{b} x",
	"limit-operator-inside-text a\\lim_{x}b",

	// ---- 符号表 + display-limit 符号（latex.ts:1090-1096）------------------
	...["sum", "prod", "int", "iint", "iiint", "oint", "coprod", "bigcap", "bigcup",
		"bigvee", "bigwedge", "bigoplus", "bigotimes", "bigodot", "bigsqcup", "biguplus",
	].flatMap((symbol) => [
		`display-limit-bare-${symbol} \\${symbol}`,
		`display-limit-lower-${symbol} \\${symbol}_{i=1}`,
		`display-limit-lower-spaces-${symbol} \\${symbol}_{n \\to \\infty}`,
		`display-limit-both-${symbol} \\${symbol}_{i=1}^{n}`,
	]),
	"symbol-greek \\pi\\alpha\\beta\\Gamma\\Omega",
	"symbol-var-forms \\phi\\varphi\\epsilon\\varepsilon\\epsilon",
	"symbol-operators \\pm\\mp\\times\\div\\cdot\\ast\\star\\circ\\bullet",
	"symbol-sets \\in\\notin\\ni\\subset\\supset\\subseteq\\supseteq",
	"symbol-arrows \\to\\rightarrow\\leftarrow\\Rightarrow\\Leftarrow\\mapsto",
	"symbol-misc \\infty\\emptyset\\forall\\exists\\neg\\land\\lor",
	"symbol-big \\bigcup\\bigcap\\bigwedge\\bigvee",
	"symbol-dots \\ldots\\cdots\\vdots\\ddots",
	"symbol-abs \\vert x\\vert\\Vert y\\Vert\\lfloor x\\rfloor\\lceil y\\rceil",
	"symbol-braces \\lbrace x\\rbrace",
	"symbol-backslash \\backslash",
	"symbol-backslash-inline a\\backslash b",
	"symbol-prime \\prime",
	"symbol-nabla \\nabla f",
	"symbol-partial \\partial f / \\partial x",

	// ---- 关系符补空格（latex.ts:1095）--------------------------------------
	"relation-le a\\le b",
	"relation-leq-spaced a \\leq b",
	"relation-in a\\in B",
	"relation-notin a\\notin B",
	"relation-to a\\to b",
	"relation-implies a\\implies b",
	"relation-iff a\\iff b",
	"relation-approx a\\approx b",
	"relation-equiv a\\equiv b",
	"relation-ne a\\ne b",
	"relation-subset A\\subset B",
	"relation-mid a\\mid b",
	"relation-parallel a\\parallel b",
	"relation-gets f\\gets x",
	"relation-no-space-around a+b",
	"relation-literal-equals a=b",
	"relation-literal-less a<b",
	"relation-literal-greater a>b",
	"relation-literal-less-spaced a < b",
	"relation-cdot a\\cdot b",
	"relation-times a\\times b",
	"relation-cdot-no-space a\\cdotb",
	"relation-negative-space a\\!b",
	"relation-negmedspace a\\negmedspace b",

	// ---- 具名算子间距（latex.ts:1097-1099 + normalizeOutput）---------------
	...["sin", "cos", "tan", "log", "ln", "exp", "det", "gcd", "deg", "dim", "hom",
		"ker", "Pr", "sec", "csc", "cot", "arcsin", "arccos", "arctan", "sinh",
		"cosh", "tanh", "coth", "arg", "lg",
	].flatMap((operator) => [
		`named-operator-bare-${operator} \\${operator} x`,
		`named-operator-glued-${operator} 2\\${operator} x`,
		`named-operator-command-${operator} \\${operator}\\alpha`,
		`named-operator-paren-${operator} \\${operator}(x)`,
	]),
	"named-operator-superscript \\sin^2 x",
	"named-operator-subscript \\sin_x y",
	"named-operator-negative-space \\sin\\!x",
	"named-operator-glued-after-paren (x)\\sin y",
	"named-operator-two 2\\sin x\\cos y",

	// ---- \not（latex.ts:1074-1085）----------------------------------------
	"not-in a\\not\\in B",
	"not-equals \\not=",
	"not-less \\not<",
	"not-symbol \\not\\subset",
	"not-char \\not a",
	"not-empty \\not{}",

	// ---- 分数 / 根号 -------------------------------------------------------
	"fraction \\frac{a}{b}",
	"fraction-simple \\frac12",
	"fraction-spaced \\frac {a} {b}",
	"fraction-space-between \\frac{a} {b}",
	"fraction-newline \\frac\n{a}{b}",
	"fraction-dfrac \\dfrac{a}{b}",
	"fraction-tfrac \\tfrac{a}{b}",
	"fraction-nested \\frac{1}{\\frac{a}{b}}",
	"fraction-long-numerator \\frac{a+b}{c}",
	"fraction-long-denominator \\frac{a}{b+c}",
	"fraction-single-letter \\frac{x}{2}",
	"fraction-missing-arg \\frac{a}",
	"root \\sqrt{x}",
	"root-group \\sqrt{ab}",
	"root-three \\sqrt[3]{x}",
	"root-four \\sqrt[4]{x}",
	"root-n \\sqrt[n]{x}",
	"root-optional-spaced \\sqrt [3]{x}",
	"root-optional-symbol \\sqrt[\\alpha]{x}",
	"root-nested \\sqrt{\\sqrt{x}}",
	"root-no-arg \\sqrt",

	// ---- 装饰 / 包装 -------------------------------------------------------
	"accent-vec \\vec{v}",
	"accent-hat \\hat{x}",
	"accent-tilde \\tilde{x}",
	"accent-bar \\bar{x}",
	"accent-dot \\dot{x}",
	"accent-ddot \\ddot{x}",
	"accent-wide \\widehat{abc}",
	"accent-long \\overline{ab}",
	"accent-underline \\underline{ab}",
	"accent-overrightarrow \\overrightarrow{AB}",
	"accent-multiple \\vec{a}\\vec{b}",
	"wrappers-text \\text{a b}",
	"wrappers-textbf \\textbf{a b}",
	"wrappers-mathbb \\mathbb{R}",
	"wrappers-mathbb-mixed \\mathbb{R}^n",
	"wrappers-mathcal \\mathcal{L}",
	"wrappers-mathrm \\mathrm{d}x",
	"wrappers-mbox \\mbox{abc}",
	"wrappers-emph \\emph{a}",
	"wrappers-nested \\mathbf{a+b}",
	"boxed \\boxed{a}",
	"boxed-spaced \\boxed {x}",
	"binom \\binom{n}{k}",
	"pmod \\pmod{n}",
	"pod \\pod{n}",
	"bmod a\\bmod n",
	"mod a\\mod n",
	"overset \\overset{a}{b}",
	"stackrel \\stackrel{a}{b}",
	"underset \\underset{a}{b}",
	"left-right \\left(\\frac{a}{b}\\right)",
	"left-right-dot \\left. x \\right)",
	"middle-pipe \\left(x\\middle|y\\right)",
	"size-commands \\big( x \\Big)",
	"style-commands \\displaystyle x + \\textstyle y",
	"limits-ignored \\sin\\limits_{x}",

	// ---- 上下标（latex.ts:949-1005 / 894-904）------------------------------
	"scripts-simple x^2",
	"scripts-sub x_i",
	"scripts-both x_i^2",
	"scripts-both-reverse x^2_i",
	"scripts-two-markers-spaced x^2 _3",
	"scripts-multi-letter-sub x_{ab}",
	"scripts-upper-sub x_{A}",
	"scripts-upper-sub-two x_{AB}",
	"scripts-symbol-sub x_{\\alpha}",
	"scripts-group-sub x_{i+1}",
	"scripts-group-sup x^{n+1}",
	"scripts-negative x^{-1}",
	"scripts-spaced x^ 2",
	"scripts-space-between x ^ 2",
	"scripts-nested x^{y^2}",
	"scripts-in-group {x^2}y",
	"scripts-empty x^{}",
	"scripts-with-equals x_{i=1}",
	"scripts-with-equals-spaced x_{i = 1}",
	"scripts-to-infinity x_{n \\to \\infty}",
	"scripts-no-arg x^",
	"scripts-braces-only x_{}",

	// ---- 空白 / 转义 / 杂项 ------------------------------------------------
	"whitespace-collapse a   b",
	"whitespace-newline a\nb",
	"whitespace-tab a\tb",
	"whitespace-nbsp a\u00a0b",
	"whitespace-leading   x",
	"whitespace-trailing x   ",
	"nonbreaking-tilde a~b",
	"alignment-tab a&b",
	"forced-newline a\\\\b",
	"command-newline a\\\nb",
	"escape-brace \\{x\\}",
	"escape-dollar \\$x\\$",
	"escape-percent a\\%b",
	"escape-hash \\#x",
	"escape-underscore a\\_b",
	"escape-amp a\\&b",
	"escape-pipe a\\|b",
	"escape-unicode \\pi \\ne \\alpha",
	"spacing-comma a\\,b",
	"spacing-semicolon a\\;b",
	"spacing-quad a\\quad b",
	"spacing-qquad a\\qquad b",
	"spacing-space-command a\\ b",
	"spacing-enspace a\\enspace b",
	"spacing-thinspace a\\thinspace b",
	"spacing-negative-thin a\\negthinspace b",
	"spacing-negative-bang a\\!b",

	// ---- 失败路径（必须整条回原文）----------------------------------------
	"unknown-command \\unknown x",
	"unknown-command-alone \\foo",
	"unknown-letter-command \\q",
	"unbalanced-close x}",
	"unbalanced-open x{",
	"unbalanced-group {x",
	"end-without-begin x\\end{y}",
	"trailing-backslash x\\",
	"matrix-inline \\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
	"cases-inline \\begin{cases}a&x>0\\\\b&x<0\\end{cases}",
	"aligned-inline \\begin{aligned}a&=b\\\\c&=d\\end{aligned}",
	"equation-inline \\begin{equation}x^2\\end{equation}",
	"unknown-environment \\begin{foo}x\\end{foo}",
	"empty-source ",
	"only-space   ",
	"only-braces {}",
];

/**
 * `cases` 与 `knownUnported` 的分界不是靠人工挑，而是**按 pi 的输出**分：pi 返回
 * `undefined`（认不出来）的进 `cases`（期望 `null`），pi 画得出来而端口画不出来的
 * 才进 `knownUnported`。后者目前只有 `\begin{…}` 那一族 —— 判据是「端口返回
 * `null`」这件事由生成器**不**假设，而是留给定夹具去断言"仍然不一样"。
 */
const KNOWN_UNPORTED = new Set(["matrix-inline", "cases-inline", "aligned-inline", "equation-inline"]);

const cases = [];
const knownUnported = [];
const displayTargets = [];
const seen = new Set();
for (const entry of CASES) {
	if (entry === "...") {
		console.error("公式清单里还留着占位符 `...`");
		process.exit(2);
	}
	// 名字与公式之间是**第一个**空格：公式自己含空格，所以不能用 `split(" ")`。
	const separator = entry.indexOf(" ");
	const name = entry.slice(0, separator);
	const source = entry.slice(separator + 1);
	if (separator < 0) {
		console.error(`公式清单里的条目没有名字：${JSON.stringify(entry)}`);
		process.exit(2);
	}
	const inline = renderLatex(source);
	const display = renderLatex(source, { display: true });
	if (seen.has(name)) {
		console.error(`公式清单里有重复的名字：${name}`);
		process.exit(2);
	}
	seen.add(name);
	const record = {
		name,
		source,
		expected: inline === undefined ? null : inline,
	};
	if (KNOWN_UNPORTED.has(name)) {
		knownUnported.push(record);
	} else {
		cases.push(record);
	}
	if (display !== inline) {
		displayTargets.push({ name, source, expected: display === undefined ? null : display });
	}
}

// ---------------------------------------------------------------------------
// 第二部分：定界符（`PiLatex.preprocess`）
// ---------------------------------------------------------------------------

/**
 * pi 的四个 tokenizer 函数**不是**导出的（`components/markdown.js` 里只有
 * `LATEX_MARKDOWN_EXTENSIONS` 是模块内部常量），所以这里从引擎自己的
 * `dist/components/markdown.js` 里**按名字抽出函数体**再求值 —— 不是手抄一份：
 * pi 改一个边界条件，下一次 `--check` 就会不一样。这也是 `tools/check-latex-tables.mjs`
 * 读 `latex.js` 字面量的同一手法。
 */
function piTokenizers() {
	const source = readFileSync(join(piTui, "dist/components/markdown.js"), "utf8");
	const names = [
		"isEscaped",
		"findClosingDelimiter",
		"looksLikePendingDollarMath",
		"tokenizeInlineLatex",
		"tokenizeBlockLatex",
	];
	const bodies = names.map((name) => {
		const at = source.indexOf(`function ${name}(`);
		if (at < 0) throw new Error(`markdown.js 里找不到 function ${name}(`);
		let depth = 0;
		let opened = false;
		for (let index = at; index < source.length; index++) {
			if (source[index] === "{") {
				depth++;
				opened = true;
			} else if (source[index] === "}") {
				depth--;
				if (opened && depth === 0) return source.slice(at, index + 1);
			}
		}
		throw new Error(`function ${name}( 没有配平`);
	});
	return new Function(`${bodies.join("\n")}; return { tokenizeInlineLatex, tokenizeBlockLatex };`)();
}

/** pi 的 `LATEX_MARKDOWN_EXTENSIONS`（`markdown.ts:123-172`），逐字接线。 */
async function piMarked() {
	// `marked` 是 pi-tui 自己的依赖，不在本仓库的 node_modules 里，所以按 pi-tui
	// 的位置去找它的 ESM 入口（npm 平铺或嵌套两种布局都试）。
	const markedEntry = [
		join(piTui, "node_modules/marked/lib/marked.esm.js"),
		join(piTui, "..", "..", "marked/lib/marked.esm.js"),
		join(piTui, "..", "marked/lib/marked.esm.js"),
	].find((candidate) => existsSync(candidate));
	if (!markedEntry) {
		console.error(`找不到 marked（pi-tui 的依赖）：${piTui}`);
		process.exit(2);
	}
	const { Marked } = await import(pathToFileURL(markedEntry).href);
	const { tokenizeInlineLatex, tokenizeBlockLatex } = piTokenizers();
	const extensions = [
		{
			name: "latexBlock",
			level: "block",
			start(source) {
				const match = /(?:^|\n) {0,3}(?:\$\$|\\\[)/.exec(source);
				return match ? match.index + (match[0].startsWith("\n") ? 1 : 0) : undefined;
			},
			tokenizer: tokenizeBlockLatex,
		},
		{
			name: "latex",
			level: "inline",
			start(source) {
				const indices = [source.indexOf("$"), source.indexOf("\\("), source.indexOf("\\[")].filter(
					(index) => index >= 0,
				);
				return indices.length > 0 ? Math.min(...indices) : undefined;
			},
			tokenizer: tokenizeInlineLatex,
		},
	];
	const marked = new Marked();
	marked.use({ extensions });
	return marked;
}

/** 文档顺序里的所有 math token（含 `pending` 标记）。 */
function latexTokens(tokens, out = []) {
	for (const token of tokens) {
		if (token.type === "latex" || token.type === "latexBlock") out.push(token);
		if (token.tokens) latexTokens(token.tokens, out);
		if (token.items) for (const item of token.items) latexTokens(item.tokens ?? [], out);
	}
	return out;
}

/**
 * 期望输出的生成规则 —— 它编码的是**契约**，不是 App 今天的实现：
 *
 * > 每个 pi 认出来的数学 token，其 raw 覆盖的那一段源文本，被替换成
 * > `renderLatex(text)`（行内）或 `"
" + renderLatex(text, {display:true}) + "
"`
 * > （块级，独占一段）；pi 认不出来（`undefined`）或仍待定（`pending`）的 token
 * > 原样保留。
 *
 * 两个形态（`
…
`）来自 `PiLatex.toDisplayUnicode` 的既有契约（把显示式公式
 * 写回源文本时让它独占一段），不是 pi 的输出形状 —— pi 是直接把渲染行推进自己的
 * 文本缓冲。**决策**（哪一段是公式、body 是什么、行内还是块级）全部来自 pi 的
 * tokenizer 与 `renderLatex`。
 */
function expectedSource(source, tokens, blockRendering) {
	let out = "";
	let cursor = 0;
	for (const token of tokens) {
		const at = source.indexOf(token.raw, cursor);
		if (at < 0) continue;
		out += source.slice(cursor, at);
		const rendered = token.pending
			? undefined
			: renderLatex(token.text, token.type === "latexBlock" && blockRendering === "display" ? { display: true } : {});
		if (rendered === undefined) {
			out += token.raw;
		} else if (token.type === "latexBlock") {
			out += `\n${rendered}\n`;
		} else {
			out += rendered;
		}
		cursor = at + token.raw.length;
	}
	return out + source.slice(cursor);
}

/**
 * 定界符用例：`[名字, 源]`。名字用来定位，源是被测的东西。
 *
 * 这一张表里放**能断言**的（App 今天就该与 pi 一样）。pi 的 tokenizer 与 App 的
 * 正则不一致、而这次没有改的那些，全部放在 `DELIMITER_DEVIATIONS` 里并写明理由。
 */
const DELIMITERS = [
	["inline-paren", String.raw`\(x\)`],
	["inline-paren-adjacent", String.raw`a\(x\)b`],
	["inline-paren-spaces", String.raw`a \(x\) b`],
	["inline-paren-symbols", String.raw`\(\alpha+\beta\)`],
	["inline-paren-fraction", String.raw`\(\frac{a}{b}\)`],
	["inline-paren-unknown", String.raw`\(\unknown\)`],
	["inline-paren-empty", String.raw`\(\)`],
	["inline-paren-unclosed", String.raw`a \(x`],
	["inline-paren-newline", String.raw`\(x` + "\n" + String.raw`y\)`],
	["dollar-simple", String.raw`$x$`],
	["dollar-spaces-around", String.raw`a $x$ b`],
	["dollar-prose", String.raw`costs $5 and $10 today`],
	["dollar-prose-word", String.raw`the $PATH and $HOME dir`],
	["dollar-unknown", String.raw`$\unknown$`],
	["dollar-pending-price", String.raw`price: $3.50 (USD)`],
	["dollar-after-digit", String.raw`2$x$`],
	["dollar-digit-body", String.raw`$5$`],
	["bracket-line-start", String.raw`\[\frac{a}{b}\]`],
	["bracket-line-start-newline", String.raw`\[\frac{a}{b}\]` + "\n"],
	["bracket-line-start-indented", "   " + String.raw`\[\frac{a}{b}\]` + "\n"],
	["bracket-alone-in-line", "text\n" + String.raw`\[\frac{a}{b}\]` + "\ntext"],
	["dollar-wrapping-paren", String.raw`$\(x\)$`],
	["dollar-inline-in-text", String.raw`a $x$ and $y$`],
	["dollar-escaped", String.raw`\$x\$`],
	["dollar-only", "$$"],
	["bracket-line-start-simple", String.raw`\[x^2\]` + "\n"],
	["bracket-line-start-two", String.raw`\[a\]` + "\n" + String.raw`\[b\]` + "\n"],
	["bracket-and-dollar-blocks", String.raw`$$x$$` + "\n" + String.raw`\[y\]` + "\n"],
	["bracket-multiline", "\\[\nx\n\\]" + "\n"],
	["bracket-mid-line", String.raw`pre \[\frac{a}{b}\]`],
	["bracket-trailing-text", String.raw`\[x\] trailing`],
	["bracket-empty", String.raw`\[\]`],
	["dollar-block-line", String.raw`$$x^2$$` + "\n"],
	["dollar-block-multiline", "$$\nx^2\n$$\n"],
	["code-span-untouched", "a `$y$` b"],
	["code-span-bracket-untouched", "a `" + String.raw`\(y\)` + "` b"],
	["fence-untouched", "```\n$$x$$\n```\n"],
	["fence-bracket-untouched", "```\n" + String.raw`\(x\)` + "\n```\n"],
	["two-inline", String.raw`\(a\) and \(b\)`],
	["inline-and-block", String.raw`x \(a\) \[b\]` + "\n"],
];

/**
 * **已知不同**（断言"仍然不一样"，所以它们不会悄悄变成一样、也不会被忘掉）。
 * 三类理由，每一类的出处都写在这里：
 *
 * ① `$` 那一条正则比 pi 的 tokenizer 保守，**原因现在只剩一个**：收尾的
 *    `(?![\p{L}\p{N}])`。pi 的规则在 `markdown.ts:52-99` 里只有四种拒绝
 *    （body 以空白结尾、收尾 `$` 之后紧跟数字、body 像常量名而后面紧跟标识符、
 *    body 含反引号），开定界符前面是什么、body 是不是数字开头都**不**影响它是不是
 *    公式。原先还有"开定界符前不能是字母/数字"与"body 不能以数字开头"两条，
 *    220 条 prose 语料量下来它们既不减少误判、也不保护任何正常用法，已经删掉
 *    （`2$x$`、`$5$` 因此从这张表挪进了 `DELIMITERS`）。剩下这 4 种形状
 *    （`a$x$b`、`a$5$b`、`$x$y`、`abc$def$ghi`）都来自收尾那条，保留的理由与
 *    逐条语料证据见 `docs/known-gaps.md` §A2。
 * ② marked 的段落切分：位置相关的行为（`e.slice(1)`），让"公式前正好一个字符 +
 *    一个空格"的行中 `$$`/`\[` 变成**待定块级**（原文照排），而 App 画成公式。
 * ③ 未移植：`\begin{…}` 那一族（`latex.ts:1331`）pi 连行内都画（布局支
 *    `renderLayout` 没搬过来），App 一律回原文。
 */
const DELIMITER_DEVIATIONS = [
	// ① `$` 正则的整体近似
	["dollar-after-word", String.raw`a$x$b`],
	["dollar-digit-body-adjacent", String.raw`a$5$b`],
	["dollar-letter-after", String.raw`$x$y`],
	["dollar-word-word", String.raw`abc$def$ghi`],
	// ② marked 的段落切分 / `$$` 无锚点
	["inflated-one-char-before", String.raw`a \[x\] b`],
	["inflated-bracket-glued", String.raw`a\[x\]b`],
	["inflated-dollar-mid-line", String.raw`a $$x$$ b`],
	["inflated-dollar-in-text", String.raw`pre $$x$$ post`],
	// ③ 未移植的布局支
	["bracket-unrenderable", String.raw`\[\begin{pmatrix}a&b\\c&d\end{pmatrix}\]`],
];

// ---------------------------------------------------------------------------
// 第三部分：`$` 的 prose 语料 —— "我们有意保守"的可复核证据
// ---------------------------------------------------------------------------

/**
 * 为什么要扫一遍真实语料：`PiLatex` 的 `$` 分支比 pi 的 tokenizer 保守
 * （见 KDoc 与 `docs/known-gaps.md` §A2 的三条偏差）。"保守"是好事还是坏事，
 * 只能用真实文本来决定 —— 对齐它会不会把 shell 命令、模板占位符、货币价格
 * 吃掉？这 220 条就是回答：**取自本仓库自己的文档/资源 + 一份手写的真实用法
 * 清单（每条一个场景）**。
 *
 * 这里**只记语料与 pi 的判定**，"我们改不改它"由 `PiLatexCheck.kt` 在测试时算
 * （生成脚本跑不了 Kotlin），所以夹具不会随着 App 的实现而过期。
 *
 * 语料本身不进仓库（体积与许可证），进的只是"取自哪个文件 + 命中片段 + 判定"。
 */
function walkFiles(dir, out = []) {
	for (const entry of readdirSync(dir)) {
		const path = join(dir, entry);
		if (statSync(path).isDirectory()) {
			if (!/^(build|\.git|node_modules)$/.test(entry)) walkFiles(path, out);
		} else {
			out.push(path);
		}
	}
	return out;
}

/** 真实用法清单：每条写明场景，因为"这算不算正常用法"正是判定本身。 */
const DOLLAR_PROSE_CURATED = [
	["US$5", "货币：不带闭定界符"],
	["a $5 and $10 bill", "货币：一句话里两个价格（pi 的两条 guard 就是为它）"],
	["costs $5 and $10 today", "货币：散文里的价格"],
	["I paid $3.50 (USD)", "货币：带小数与括号"],
	["$5$", "两个 `$` 夹一个数字（pi 会当公式，本 App 不会）"],
	["total: $1,234.56", "货币：千分位与小数"],
	["$USD", "货币：跟字母"],
	["\\$x\\$", "被转义的两美元（pi 不当公式，我们也不当）"],
	["$HOME and $PATH", "shell：两个变量名"],
	["echo $HOME", "shell：命令"],
	["$1 $2", "shell：位置参数"],
	["${name}", "模板：占位符"],
	["${1:default}", "模板：带默认值"],
	["%1$s 个任务", "Android 占位符：`%1$s`"],
	["%1$d 个任务进行中", "Android 占位符：`%1$d`（res/values 里就有这一条）"],
	["%s$%d", "Android 占位符：两个"],
	["^\\d+$", "正则：结尾锚点"],
	["price$", "正则：只有结尾锚点"],
	["a$b", "正则：行锚点"],
	["$^", "正则：两个锚点"],
	["`$y$`", "markdown 行内代码里的公式（代码里的定界符不该被当公式）"],
	["see `$x$` for the value", "散文里夹行内代码"],
	["The $ sign", "单独一个美元符号"],
	["a $ b", "被空格夹住的美元符号"],
	["$", "只有一个 `$`"],
	["$$", "两个 `$`"],
	["$x", "只有开定界符"],
	["x$", "只有闭定界符"],
	["a$x$b", "字母夹公式（pi 当公式，本 App 不当）"],
	["$x$y", "公式后紧跟字母（pi 当公式，本 App 不当）"],
	["x$y$", "字母后紧跟公式（pi 当公式，本 App 不当）"],
	["2\\pi$x$", "命令夹公式（pi 当公式，本 App 不当）"],
	["abc$def$ghi", "单词夹公式（pi 当公式，本 App 不当）"],
	["$ABC$def", "常量名样式 body + 后面跟标识符（pi 的常量名规则就是为它，两边都不当公式）"],
	["the ${BRAVO} var", "模板：跟常量名（同上）"],
	["value: $$x$$", "行中的双美元（pi 当行内公式，本 App 当独占一段的块级 —— 已知偏差）"],
];

function scanDollarProse() {
	const entries = new Map();
	const add = (source, provenance) => {
		if (!source.includes("$") || entries.has(source)) return;
		entries.set(source, { source, provenance });
	};
	let scannedLines = 0;
	for (const file of walkFiles(join(REPO, "docs"))) {
		if (!file.endsWith(".md")) continue;
		readFileSync(file, "utf8").split("\n").forEach((line, index) => {
			if (!line.includes("$")) return;
			scannedLines++;
			// 文档里的表格行长几百字符；取前 200 字符就够复核，也压住夹具体积。
			add(line.length <= 200 ? line : line.slice(0, 200), `${relative(REPO, file)}:${index + 1}`);
		});
	}
	for (const file of walkFiles(join(REPO, "app/src/main/res")).filter((f) => /values[^/]*\/.*\.xml$/.test(f))) {
		readFileSync(file, "utf8").split("\n").forEach((line, index) => {
			if (line.includes("$")) add(line.trim(), `${relative(REPO, file)}:${index + 1}`);
		});
	}
	for (const [source, why] of DOLLAR_PROSE_CURATED) add(source, `手写语料：${why}`);
	return { scannedLines, entries: [...entries.values()] };
}

const marked = await piMarked();
function delimiterCase([name, source]) {
	const tokens = latexTokens(marked.lexer(source));
	const inline = expectedSource(source, tokens, "inline");
	const display = expectedSource(source, tokens, "display");
	return {
		name,
		source,
		inline,
		display,
		// 记录 pi 认出的 token 类型，日志里能立刻看出"一个 token 都没认出来"。
		tokens: tokens.map((token) => token.type + (token.pending ? ":pending" : "")),
	};
}

/** 块级渲染在 Phase A（行内）与 pi（display）下相同的用例：可以断言。 */
const delimiterAssertions = DELIMITERS.map(delimiterCase)
	.filter((entry) => entry.inline === entry.display)
	.map((entry) => ({ name: entry.name, source: entry.source, expected: entry.inline, tokens: entry.tokens }));

/** 块级渲染两者不同的用例：pi 的 display 需要 renderLayout，只记录。 */
const delimiterDisplayTargets = DELIMITERS.map(delimiterCase)
	.filter((entry) => entry.inline !== entry.display)
	.map((entry) => ({ name: entry.name, source: entry.source, inline: entry.inline, display: entry.display, tokens: entry.tokens }));

/**
 * 每条语料的 pi 判定：`piVisible` = pi 渲染出来的可见文本（`pending` 与
 * `renderLatex` 返回 `undefined` 的都是原文），`piRenders` = 它是否真的替换了文本。
 * 用 [expectedSource] 的同一套规则算出来，所以"什么算公式"与其它夹具段完全一致。
 */
const prose = scanDollarProse();
const dollarProse = prose.entries.map(({ source, provenance }) => {
	const tokens = latexTokens(marked.lexer(source));
	const piVisible = expectedSource(source, tokens, "inline");
	return { source, provenance, piVisible, piRenders: piVisible !== source };
});

const fixture = {
	piTui: piVersion,
	generatedBy: "tools/collect-latex-fixtures.mjs（期望值是 pi 的 renderLatex 返回值，不是手抄）",
	cases,
	knownUnported,
	delimiters: delimiterAssertions,
	delimiterDisplayTargets,
	delimiterDeviations: DELIMITER_DEVIATIONS.map(delimiterCase),
	dollarProse,
	displayTargets,
};
const text = JSON.stringify(fixture, null, "\t") + "\n";

if (process.argv.includes("--check")) {
	if (!existsSync(FIXTURE)) {
		console.error(`夹具不存在：${FIXTURE}`);
		process.exit(1);
	}
	const committed = readFileSync(FIXTURE, "utf8");
	if (committed === text) {
		console.log(
			`latex-fixtures: OK — 夹具与 pi-tui ${piVersion} 的输出一致（${cases.length} 条公式断言 + ` +
				`${fixture.delimiters.length} 条定界符断言 + ${fixture.delimiterDisplayTargets.length} 条定界符 display 目标 + ${knownUnported.length + fixture.delimiterDeviations.length} ` +
				`条已知偏差 + ${displayTargets.length} 条 display 目标）`,
		);
		process.exit(0);
	}
	const committedLines = committed.split("\n");
	const freshLines = text.split("\n");
	const at = committedLines.findIndex((line, index) => line !== freshLines[index]);
	console.error(`latex-fixtures: FAILED — 夹具与 pi-tui ${piVersion} 的输出不同`);
	console.error(`  第一处差异在第 ${at + 1} 行：`);
	console.error(`    已提交： ${committedLines[at]}`);
	console.error(`    引擎现在：${freshLines[at]}`);
	console.error(`  如果是引擎升级造成的，重跑 node tools/collect-latex-fixtures.mjs 并 review 差异。`);
	process.exit(1);
}

writeFileSync(FIXTURE, text);
console.log(
	`latex-fixtures: 已写入 ${FIXTURE}\n` +
		`  pi-tui ${piVersion}：${cases.length} 条公式断言 + ${fixture.delimiters.length} 条定界符断言 + ` +
		`${knownUnported.length + fixture.delimiterDeviations.length} 条已知偏差 + ` +
		`${displayTargets.length} 条 display 目标 + ${dollarProse.length} 条 prose 语料` +
		`（扫过的含 \`$\` 行 ${prose.scannedLines}）`,
);
