// 生成 `app/src/test/resources/pi-html-fixtures/cases.json` 的**第一步**：拿 pi 1.0.1 自己的
// markdown 渲染器（`@earendil-works/pi-tui` dist）对一批真实形状的裸 HTML 取输出。
//
// 为什么要生成而不是手抄：pi 的行为是「块级 HTML 原样照排（`raw.trim()`）、行内 HTML
// 原样照排（`raw`）」，而「原样」到底包含什么 —— 后面的空行算不算、内部换行保不保留、
// `<not a tag>` 是块还是文本 —— 只有真跑一遍 pi 才知道。手抄一份期望值等于把手抄的
// 错误当成规格。
//
// 输出（stdout 的 JSON）：每条 case 的 `piText` = pi 渲染结果的**纯文本**（去掉它给终端
// 上的 ANSI 样式和补到 width 的尾部空格），多行以 "\n" 连接。
//
// 收集器的两个自律（不是"为了通过而放宽"）：
//  1. 补位空格与内容尾随空格在 pi 的输出里不可区分（`applyBackgroundToLine` /
//     直接 `+ " ".repeat(pad)`）。所以收集器把自己包装成"带样式的文本"（defaultTextStyle
//     的 color 是个包裹函数），补位空格落在包裹标记**之外**；仍然无法区分的情形（整串的
//     中间行）由 `assertNoContentTrailingSpace` 直接**报错退出**，而不是悄悄写进夹具。
//  2. 任何 case 只要在 width 下发生了折行，也报错退出 —— 折行会把一"行"变成两行，
//     期望值就不再是 pi 对这个形状的输出了。
//
// 第二步在 `HtmlFixtureSpans.java`：把 pin 住的解析器（org.jetbrains:markdown 0.7.9 +
// GFMFlavourDescriptor）的节点跨度与类型全集补上去，合成最终夹具。
// 用法（在仓库根目录跑）：
//   PI_TUI=/path/to/node_modules/@earendil-works/pi-tui/dist/components/markdown.js \
//     node app/src/test/resources/pi-html-fixtures/collect-pi-html-pitext.mjs > /tmp/pi-html-pitext.json
//   java -cp <markdown-jvm-0.7.9.jar>:<kotlin-stdlib.jar> \
//     app/src/test/resources/pi-html-fixtures/HtmlFixtureSpans.java \
//     /tmp/pi-html-pitext.json "$PWD" > app/src/test/resources/pi-html-fixtures/cases.json
//
// pi 的安装位置没有默认值是对的：默认值会让"夹具是从哪一份 pi 生成的"变成一个猜出来的
// 事实，而这份夹具的全部价值就在"期望值来自 pi 自己"。
const PI_TUI = process.env.PI_TUI;
if (!PI_TUI) {
  console.error("PI_TUI 未设置（指向 @earendil-works/pi-tui/dist/components/markdown.js）");
  process.exit(2);
}
const { Markdown } = await import(PI_TUI);

const RENDER_WIDTH = 4096;
const ANSI = /\x1b\[[0-9;]*m/g;
const OPEN = "\u0001";
const CLOSE = "\u0002";

// 主题：pi 的 Markdown 需要它来给标题/代码等上色。全部取恒等函数 —— 我们只关心字符，
// 不关心颜色（App 那一侧的颜色是 `PiMarkdownTheme.kt` 的事，与本夹具无关）。
const theme = new Proxy(
  {},
  {
    get: (_, prop) => {
      if (prop === "highlightCode") return undefined;
      if (prop === "codeBlockIndent") return "  ";
      return (s) => s;
    },
  },
);

// 默认文本样式只做一件事：把内容包进一对标记，好让补位空格留在标记外。
const defaultTextStyle = { color: (s) => OPEN + s + CLOSE };

function renderLines(text) {
  const md = new Markdown(text, 0, 0, theme, defaultTextStyle, {});
  const raw = md.render(RENDER_WIDTH);
  for (const line of raw) {
    // 标记本身占 length 不占可见宽度，先摘掉再量。
    const visible = line.split(OPEN).join("").split(CLOSE).join("");
    if (visible.length > RENDER_WIDTH) {
      throw new Error(`折行发生了（${visible.length} > ${RENDER_WIDTH}），夹具不能这样生成: ${JSON.stringify(text)}`);
    }
  }
  return raw.map((line) => {
    // 先去掉补位空格：内容被 OPEN/CLOSE 包着，所以标记**之后**的空格一定是补位。
    let stripped = line.replace(/\s+$/, "");
    if (stripped.endsWith(CLOSE)) stripped = stripped.slice(0, -1);
    stripped = stripped.split(OPEN).join("").split(CLOSE).join("");
    // 到这里如果还剩尾随空格，那可能是内容自己的 —— 但中间行没有标记可依靠，
    // 所以一律拒绝，不给夹具留一个"可能少了一个空格"的期望值。
    if (stripped !== stripped.replace(/\s+$/, "")) {
      throw new Error(`内容尾随空格无法与补位区分，这个 case 不能进夹具: ${JSON.stringify(text)}`);
    }
    return stripped.replace(ANSI, "");
  });
}

/** pi 的输出：整篇文档的行。`\n` 连接，方便与 Kotlin 侧的字符串逐字节比。 */
function piText(text) {
  return renderLines(text).join("\n");
}

const CASES = [
  // ---- 块级 ----
  ["b-div-multiline", "<div>\nhello\n</div>"],
  ["b-div-nested", "<div>\n  <span>a</span>\n  <span>b</span>\n</div>"],
  ["b-details", "<details><summary>摘要</summary>\n正文\n</details>"],
  ["b-comment", "<!-- 注释 -->"],
  ["b-comment-multiline", "<!--\nline1\nline2\n-->"],
  ["b-br", "<br>"],
  ["b-hr", "<hr/>"],
  ["b-self-closing-img", '<img src="x.png"/>'],
  ["b-not-a-tag", "<not a tag>"],
  ["b-p-with-inline", "<p>Hello <b>world</b></p>"],
  ["b-attrs-one-line", '<div class="a" id="b">x</div>'],
  ["b-attrs-multiline", '<div class="a"\n     id="b">\n  x\n</div>'],
  ["b-table", "<table>\n<tr><td>1</td><td>2</td></tr>\n</table>"],
  ["b-script", "<script>\nconsole.log(1)\n</script>"],
  ["b-trailing-newline", "<div>\nx\n</div>\n"],
  ["b-crlf", "<div>\r\nx\r\n</div>"],
  ["b-tab-indent", "<div>\n\ta\n</div>"],
  ["b-tab-and-crlf", "<div>\r\n\ta\r\n</div>"],
  ["b-two-blocks-adjacent", "<div>a</div>\n<div>b</div>"],
  ["b-indent-1sp", " <div>x</div>"],

  // ---- 块级 + 周围的正文（块的范围由 HTML block 规则决定，不看到底像不像标签）----
  ["m-between-prose", "before\n<div>\nhello\n</div>\nafter"],
  ["m-blank-both-sides", "before\n\n<div>\nhello\n</div>\n\nafter"],
  ["m-after-heading", "# 标题\n\n<div>x</div>"],
  ["m-after-list", "- 一\n- 二\n\n<div>x</div>"],

  // ---- 行内 ----
  ["i-basic", "这是 <b>重点</b> 文字"],
  ["i-img", 'a <img src="x.png"> b'],
  ["i-link", 'a <a href="https://example.com">链接</a> b'],
  ["i-br", "a <br/> b"],
  ["i-uppercase", "a <B>x</B> b"],
  ["i-nested", "外层 <span>内层 <em>更深</em></span> 结束"],
  ["i-with-attr-gt", 'a <span data-x="1 > 0">y</span> b'],
  ["i-with-attr-lt", "a <span data-x='1 < 0'>y</span> b"],
  ["i-only-tag", "<b>"],
  ["i-triple", "a <b>x</b> <i>y</i> <u>z</u> b"],
  ["i-tab-in-tag", "a <b\tclass=\"x\">y</b> b"],

  // ---- 反例：一个字符都不许动 ----
  ["c-fence-backtick", "```\n<div>\n```"],
  ["c-fence-lang", "```html\n<div>x</div>\n```"],
  ["c-fence-tilde", "~~~\n<div>\n~~~"],
  ["c-inline-code", "这是 `<b>` 文字"],
  ["c-inline-code-multichar", "a ` <div>x</div> ` b"],
  ["c-lt-spaces", "a < b"],
  ["c-lt-digits", "1 < 2"],
  ["c-angle-3", "<3"],
  ["c-entity-lt", "&lt;div&gt;"],
  ["c-entity-amp", "x &amp; y"],
  ["c-entity-mixed", "&lt;b&gt;粗&lt;/b&gt;"],
  ["c-4sp-fence", "    <div>indented</div>"],
  ["c-4sp-after-text", "text\n\n    <div>indented</div>"],
  ["c-arrow", "a -> b"],
  ["c-generic", "List<String> 与 Map<K, V>"],
  ["c-html-escaped-in-code", "`` `<div>` ``"],
];

const out = [];
for (const [name, source] of CASES) {
  out.push({ name, source, piText: piText(source) });
}

// pi 对**整篇**源文本做的两步归一（`components/markdown.js:194` 的 `\t` → 三个空格，
// 以及 marked 词法器自己的 `\r\n|\r` → `\n`）。块级 HTML 的 `raw.trim()` 是在归一之后
// 取到的，所以这两个字符集合必须跟 pi 一模一样，否则一个带制表符的 `<pre>` 就会和 pi
// 差三个空格。这里把两步的输入输出都记下来，交由 Kotlin 侧逐字节断言。
const NORMALIZE_INPUTS = [
  [""],
  ["plain"],
  ["a\tb"],
  ["\ta"],
  ["a\t"],
  ["a\r\nb"],
  ["a\rb"],
  ["a\nb"],
  ["\r\n"],
  ["<div>\r\n\ta\r\n</div>"],
  ["\t\t\t"],
  ["a\t\tb"],
  ["a\r\r\nb"],
];
const normalizeCases = NORMALIZE_INPUTS.map(([raw]) => ({
  raw,
  normalized: raw.replace(/\t/g, "   ").replace(/\r\n|\r/g, "\n"),
}));

// 只测 trim 这一步：两边（JS 的 `String.prototype.trim` 与 Kotlin 的 `String.trim()`）
// 的字符集合**不一样** —— JS 会吃掉 U+FEFF（ZWNBSP），Kotlin 不会；Kotlin 会吃掉
// U+001C–U+001F 与 U+0085，JS 不会。pi 用的是 JS 那一个，所以这里把 JS 的答案记下来。
const TRIM_INPUTS = [
  "",
  " ",
  "  a  ",
  "\n a \n",
  "\t a \t",
  "\u000Ba\u000B",
  "\u000Ca\u000C",
  "\u0085a\u0085",
  "\u001Ca\u001C",
  "\u001Fa\u001F",
  "\uFEFFa\uFEFF",
  "\u00A0a\u00A0",
  "\u1680a\u1680",
  "\u2000a\u2000",
  "\u200Aa\u200A",
  "\u2028a\u2028",
  "\u2029a\u2029",
  "\u202Fa\u202F",
  "\u205Fa\u205F",
  "\u3000a\u3000",
  "\u2007a\u2007",
  "a",
  "<div>\n x \n</div>",
];
const trimCases = TRIM_INPUTS.map((raw) => ({ raw, trimmed: raw.trim() }));

// `String.prototype.trim` 的**完整**字符集合：对 0..0xFFFF 每一个码位跑一遍 V8 自己的
// `trim()`（pi 调的就是这个函数），结果压成区间。夹具里给全集而不是几十个样本，是因为
// Kotlin/JVM 的 `String.trim()` 用的是另一套集合（`Character.isWhitespace`），而两边
// 的分歧点恰恰落在没人会手写进样本的那些码位上 —— 实测：JS 多一个 U+FEFF，
// Kotlin 多 U+001C–U+001F。全量比一次就把"集合到底一样不一样"这件事说死了。
const jsTrimCodes = [];
for (let c = 0; c <= 0xffff; c++) {
  if (String.fromCharCode(c).trim() === "") jsTrimCodes.push(c);
}
const jsTrimRanges = [];
for (const c of jsTrimCodes) {
  const last = jsTrimRanges[jsTrimRanges.length - 1];
  if (last && last[1] + 1 === c) last[1] = c;
  else jsTrimRanges.push([c, c]);
}

process.stdout.write(
  JSON.stringify(
    { renderWidth: RENDER_WIDTH, cases: out, normalizeCases, trimCases, jsTrimRanges },
    null,
    "\t",
  ) + "\n",
);
