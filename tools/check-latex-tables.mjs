#!/usr/bin/env node
/**
 * `PiLatex.kt` 的表 ↔ pi 的 `latex.js` 的表，**逐 key 比对**。
 *
 * ## 为什么需要它
 *
 * `app/src/main/kotlin/app/pi/ui/render/PiLatex.kt` 的表是从 pi 的 TypeScript 里
 * **逐字抄**下来的（224 个符号、32 个具名算子、127 个关系符/否定符/上下标……）。
 * 抄错一个 key、漏掉一行、把 `phi` 和 `varphi` 的码位调换，都是**静默**的：
 * 手机上只会看到某个符号画错，而没有任何编译错误。`app/src/test/kotlin/app/pi/ui/render/PiLatexCheck.kt`
 * 只跑公式，覆盖不到表的全集（它抽查行为，不查穷举）。所以这里把两张表**都当文本读出来**
 * 再逐 key 比 —— Kotlin 侧读的是仓库里的源文件，JS 侧读的是钉住引擎自己的
 * `dist/latex.js`，两边都不是手抄的期望值。
 *
 * ## 与手机的关系
 *
 * 纯 Node，不需要 Android、不需要设备、不需要 npm install（只要有一份 pi-tui）。
 * 表本身是**数据**，所以这个检查是本仓库里少见的「本机能给出终局结论」的那一类。
 *
 * ## 用法
 *
 *   node tools/check-latex-tables.mjs                     # pi-tui 从下面三个位置里找
 *   node tools/check-latex-tables.mjs --pi-tui <dir>      # 显式指定 pi-tui 包目录
 *   PI_TUI_DIR=<dir> node tools/check-latex-tables.mjs    # 或者用环境变量
 *
 * 退出码：0 = 每张表逐 key 相同；1 = 有差异；2 = 跑不起来（缺源文件 / 缺 pi-tui）。
 * 差异会打印到 stdout（CI 日志里看得见），不是抛异常。
 */

import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** 本仓库，从脚本自身位置推出来 —— 这样换 checkout 目录也不用改这里。 */
const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const PI_LATEX_KT = join(REPO, "app/src/main/kotlin/app/pi/ui/render/PiLatex.kt");

/**
 * pi-tui 的包目录。三个来源，按顺序：`--pi-tui`、`PI_TUI_DIR`、
 * `build/pi-contract/node_modules/@earendil-works/pi-tui`（`tools/pi-contract.mjs`
 * 装引擎的那个目录；pi-tui 是 `pi-coding-agent` 的依赖，npm 会把它放在同一个
 * `node_modules/@earendil-works/` 下）。
 */
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
	console.error(`  跑一次 \`node tools/pi-contract.mjs --only=surface\` 会把引擎装到 build/pi-contract，`);
	console.error(`  或者用 \`--pi-tui /path/to/node_modules/@earendil-works/pi-tui\` 指路。`);
	process.exit(2);
}

/**
 * 从 Kotlin 源文本里抽一张表。
 *
 * 表的两半都在**源文本**里，不在运行时（它们是 `private` 的，编译不了也读不到）：
 *   - `private val NAME: Map<String, String> = mapOf( "k" to "v", … )`
 *   - `private val NAME: Set<String> = setOf( "a", "b", … )`
 *
 * 值里的转义按 Kotlin 的规则解开（`\uXXXX` / `\\` / `\"` / `\n` / `\t` / `\$`）：
 * 比的是**字符串**，不是源码里的写法。
 */
function kotlinTable(source, name) {
	const declaration = new RegExp(
		`private val ${name}:\\s*(Map<String,\\s*String>|Set<String>)\\s*=\\s*(mapOf|setOf)\\(`,
	);
	const found = declaration.exec(source);
	if (!found) return undefined;
	const kind = found[1].startsWith("Map") ? "map" : "set";
	// 从 `mapOf(` / `setOf(` 的左括号开始，按括号配平找到表尾 —— 值里可能有
	// `(`/`)`（目前没有，但这比"下一个空行"稳）。
	let depth = 0;
	let end = found.index + found[0].length - 1;
	for (let index = end; index < source.length; index++) {
		if (source[index] === "(") depth++;
		else if (source[index] === ")") {
			depth--;
			if (depth === 0) {
				end = index;
				break;
			}
		}
	}
	const body = source.slice(found.index + found[0].length, end);
	if (kind === "set") {
		return { kind, value: new Set(kotlinStrings(body)) };
	}
	const value = new Map();
	const pair = /"((?:[^"\\]|\\.)*)"\s*to\s*"((?:[^"\\]|\\.)*)"/g;
	let match;
	while ((match = pair.exec(body)) !== null) {
		value.set(kotlinUnescape(match[1]), kotlinUnescape(match[2]));
	}
	return { kind, value };
}

/** 一段 Kotlin 代码里的所有字符串字面量。 */
function kotlinStrings(body) {
	const value = [];
	const literal = /"((?:[^"\\]|\\.)*)"/g;
	let match;
	while ((match = literal.exec(body)) !== null) value.push(kotlinUnescape(match[1]));
	return value;
}

function kotlinUnescape(text) {
	return text.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, escape) => {
		if (escape[0] === "u") return String.fromCharCode(parseInt(escape.slice(1), 16));
		return { n: "\n", r: "\r", t: "\t", b: "\b", "0": "\0", "\\": "\\", '"': '"', "$": "$" }[escape] ?? escape;
	});
}

/**
 * 从 `latex.js` 的源文本里抽一张表，并**在隔离作用域里求值**那块字面量 ——
 * 这不是手抄期望值的地方：字面量就是引擎自己的字节，解出来的就是引擎运行时看到的对象。
 * （`latex.js` 的 const 都不导出，所以只能这样读；这也是这个脚本比"再抄一遍"可信的原因。）
 */
function javascriptTable(source, name) {
	let found = new RegExp(`^const ${name} = \\{`, "m").exec(source);
	if (found) {
		const body = balanced(source, found.index + found[0].length - 1, "{", "}");
		const value = new Function(`return ({${body}});`)();
		return { kind: "map", value: new Map(Object.entries(value)) };
	}
	found = new RegExp(`^const ${name} = new Set\\(\\[`, "m").exec(source);
	if (found) {
		const body = balanced(source, found.index + found[0].length - 1, "[", "]");
		const value = new Function(`return ([${body}]);`)();
		return { kind: "set", value: new Set(value) };
	}
	return undefined;
}

/** 从 `open` 处配平到它自己的 `close`，返回括号之间的文本。 */
function balanced(source, start, open, close) {
	let depth = 0;
	for (let index = start; index < source.length; index++) {
		if (source[index] === open) depth++;
		else if (source[index] === close) {
			depth--;
			if (depth === 0) return source.slice(start + 1, index);
		}
	}
	throw new Error(`从 ${start} 开始的 ${open} 没有配平`);
}

/**
 * 要比的表。名字两边相同，**顺序**按 pi 的定义顺序（`latex.js` 的 const 顺序），
 * 这样日志读起来和 pi 的源文件一致。
 *
 * `CHAR_ESCAPES`（`PiLatex.kt:987`）不在这里：pi 没有这张表，它的 7 个转义字符
 * 是 `parseCommand` 里 7 个 `command === "x"` 条件（`latex.js:976-984`），
 * 由 `PiLatexCheck` 的公式夹具覆盖。`NEGATIVE_SPACE` 同理（一个哨兵值，不是表）。
 */
const TABLES = [
	"SYMBOLS",
	"NEGATED_SYMBOLS",
	"BLACKBOARD",
	"SUPERSCRIPTS",
	"SUBSCRIPTS",
	"NAMED_OPERATORS",
	"LIMIT_OPERATORS",
	"DISPLAY_LIMIT_SYMBOLS",
	"RELATION_COMMANDS",
	"SPACING_COMMANDS",
	"NEGATIVE_SPACING_COMMANDS",
	"FONT_SWITCH_COMMANDS",
	"IGNORED_COMMANDS",
	"SIZE_COMMANDS",
	"PLAIN_WRAPPERS",
	"ACCENTS",
];

const piTui = resolvePiTui();
const latexJs = readFileSync(join(piTui, "dist/latex.js"), "utf8");
if (!existsSync(PI_LATEX_KT)) {
	console.error(`找不到 ${PI_LATEX_KT}`);
	process.exit(2);
}
const piLatexKt = readFileSync(PI_LATEX_KT, "utf8");

let failures = 0;
let compared = 0;
const rows = [];

for (const name of TABLES) {
	const kotlin = kotlinTable(piLatexKt, name);
	const javaScript = javascriptTable(latexJs, name);
	if (!kotlin || !javaScript) {
		failures++;
		rows.push(
			`${name}: ${kotlin ? "" : "PiLatex.kt 里没有这张表 "}${javaScript ? "" : "latex.js 里没有这张表"}`.trim(),
		);
		continue;
	}
	if (kotlin.kind !== javaScript.kind) {
		failures++;
		rows.push(`${name}: 种类不同（Kotlin ${kotlin.kind} / pi ${javaScript.kind}）`);
		continue;
	}
	const differences = [];
	if (kotlin.kind === "map") {
		for (const [key, expected] of javaScript.value) {
			if (!kotlin.value.has(key)) differences.push(`缺 key "${key}"（pi = ${JSON.stringify(expected)}）`);
			else if (kotlin.value.get(key) !== expected) {
				differences.push(
					`"${key}"：Kotlin ${JSON.stringify(kotlin.value.get(key))} ≠ pi ${JSON.stringify(expected)}`,
				);
			}
		}
		for (const [key, value] of kotlin.value) {
			if (!javaScript.value.has(key)) differences.push(`多出 key "${key}"（= ${JSON.stringify(value)}）`);
		}
	} else {
		for (const member of javaScript.value) {
			if (!kotlin.value.has(member)) differences.push(`缺 "${member}"`);
		}
		for (const member of kotlin.value) {
			if (!javaScript.value.has(member)) differences.push(`多出 "${member}"`);
		}
	}
	compared += javaScript.value.size;
	if (differences.length === 0) {
		rows.push(`${name}: ${javaScript.value.size}/${javaScript.value.size} 相同`);
	} else {
		failures++;
		rows.push(`${name}: ${differences.length} 处不同\n    ${differences.join("\n    ")}`);
	}
}

for (const row of rows) console.log(row);
console.log(
	failures === 0
		? `latex-tables: OK — ${TABLES.length} 张表、${compared} 个 key/成员逐项相同（pi-tui: ${piTui}）`
		: `latex-tables: FAILED — ${failures} 张表有差异（pi-tui: ${piTui}）`,
);
process.exit(failures === 0 ? 0 : 1);
