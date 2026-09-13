# 09 · 代码上色保真度审计（pi 0.85.1 ↔ 本 App）

> 审计对象：pi `@earendil-works/pi-coding-agent@0.85.1` 的发布包 vs `/root/pi-android`。
> 只读审计，未修改任何既有文件。本文件是本次唯一新增文件。
>
> **引证约定**
> - pi 侧：发布包 `dist/` 里**没有 `src/`**，但每个 `.js` 都带 `sourcesContent` 的 sourcemap，**原始 TypeScript 全文可复原**，所以下面 pi 的 `:NNN` 都是**原始 `.ts` 的行号**（不是 `dist/*.js` 的行号）。写法：`<map 文件> → <原始 ts>:NN`。这是本报告在无 `src/` 情况下的取证方式，**不算“上游取证受限”**（唯一真正受限的是 `@earendil-works/pi-tui` 之外的第三方库内部，见 §不确定）。
> - App 侧：仓库相对路径 `:NN`。
> - 参考文档 `design/ui-refactor/00-screen-inventory.md`、`brand-spec.md` 只用于定位；**已发现其 `PiTheme.kt` 行号过期**（它引 `:397-448`，实际该文件只有 376 行，`onAccent` 在 `:253`、`scrim` 在 `:298`）。`docs/pi-android-ui-spec.md` 与 `docs/pi-android-app-design.md` 按要求未引用。

---

## 0. 结论先行

> **本节是审计当时的结论（历史快照）。本轮已按「施工记录」把 D1/D3/D4/D5/D6/D7/D8 修到 pi 行为，D2 经复核裁决为「两侧本就一致、无需改渲染」。要施工清单请直接看 §「施工记录」+ §「pi-highlight 扩展审计」。**

1. **“和 pi 一模一样吗？”——不是，但差异集中在“底色”与“外壳”，不在 token 表上。**
   `hljs scope → pi syntax token` 的映射、语言判定、未知语言降级、流式行为，这四件事是**逐行一致**的（§A）。真正的偏差是：**高亮成功之后，未被 hljs 着色的那些字符，App 用的是 `mdCodeBlock`，pi 用的是 `text`（终端默认）**。
2. **漏掉最多的三类 pi 上色**：① mermaid 图（4 个 token 的上色整条链路缺失）；② diff 的行色 + 行内词级 `inverse`；③ HTML 导出那一整套（`--syntax*`/`--md*` CSS 变量 + 与 CLI 不同的第二张 hljs 映射表）——第③类对“手机 App”本身不适用，但它是 pi 里真实存在的第二套上色机制，必须记账。
3. **最严重的保真度缺陷（单条）**：`PiMarkdownTheme.kt:236` + `PiMarkdownComponents.kt:375-377`——高亮成功的代码围栏，其**基色是 `mdCodeBlock`（dark 主题 = 绿 `#B5BD68`）**，而 pi 在该分支**不给底色**（`theme.ts:1186-1205` 只返回 hljs 着好色的行），未着色字符落到 `text`（`#D4D4D4`；HTML 导出侧两处明证：`template.css:959` `.hljs{color:var(--text)}`、`:906-909` `.markdown-content pre code{color:var(--text)}`）。**结果：Kotlin/Python 块里所有标点、空白、未被文法命中的标识符，在 App 里是绿色，在 pi 里是近白色。** `mdCodeBlock` 在 pi 里**只**用于“没有语言/语言未知 → 完全不高亮”那一个分支（`theme.ts:1085`、`:1193`）。
4. **“上色路径是否每次重组重算”——不是，结论明确：不会。**
   上色只有两个入口，两个都带键：`rememberPiHighlightedCode`（`PiMarkdownComponents.kt:409-436`，`produceState(code, language, highlighter)` + `remember(code, spans.value, palette)` + 流式 200 ms 去抖）与 `SourceLines`（`ToolBodyText.kt:54-59`，`remember(lines/path)` → `rememberPiHighlightedCode` → `remember(highlighted, lines, startLine, palette.muted)`）。下游还有 `PiNodeCodeHighlighter` 的 SHA-256 键 128 条 LRU（`:70`、`:92-95`、`:138-147`）。**重组只重放已算好的 span，不会重新走高亮器**；`ui/blocks/*` 逐个核对见 §A3，**没有任何一个块把上色放在无键的调用里**。真正每帧在跑的是**markdown 解析**（第三方库内，见 §不确定 4）和几处便宜的字符串拼接。

---

## A. 机制对比

### A1. pi 在哪一步给代码上色

| 环节 | 事实 | 证据 |
|---|---|---|
| 高亮器 | **自带集成，但引擎是第三方 `highlight.js@10.7.3`**（pi 的直接依赖） | `utils/syntax-highlight.js.map → src/utils/syntax-highlight.ts:1`、`package.json` deps `highlight.js`；实测 `node_modules/highlight.js/package.json` = `10.7.3` |
| 预注册语言 | 20 个 eager：python java go javascript cpp typescript php ruby c csharp nix bash rust scala kotlin swift dart groovy perl lua；启动后**后台**再 `import("highlight.js/lib/index.js")` 拉全部 191 个 | `…/syntax-highlight.ts:2-21`（import 清单）、`:24-49`（注册循环）、`:51-68`（`loadAllHighlightLanguages`，`setImmediate` + 动态 import，失败静默） |
| 调用点 | `highlightCode(code, lang)`（`theme.ts:1078-1097`）被 `getMarkdownTheme().highlightCode`（`:1186-1205`）与 `read`/`write` 渲染器复用 | `theme.ts:1078`、`:1186`；`core/tools/renderers/read.js.map → src/core/tools/renderers/read.ts:126-127`；`write.ts:44-46`、`:111-114` |
| 上色动作 | `hljs.highlight(code,{language,ignoreIllegals:true})` → HTML → **`renderHighlightedHtml` 把 `<span class="hljs-x">` 折成带作用域的文本，作用域按 `exact → 前缀(.) → 前缀(-) → theme.default` 解析，`default` 未定义 ⇒ 未命中就不上色** | `…/syntax-highlight.ts:200-208`（`highlight`）、`:146-198`（`renderHighlightedHtml`）、`:99-136`（三层解析 + `getActiveFormatter` 从最内层往外找） |
| scope→token 表 | 22 条 + 3 条纯装饰（`emphasis/strong/link`） | `theme.ts:1036-1064`；表按 theme 缓存：`:1066-1072` |
| 语言表（路径） | `getLanguageFromPath`：`filePath.split(".").pop().toLowerCase()` → **59 键扩展名表** | `theme.ts:1102-1168` |
| 语言表（围栏） | **不做归一化**：`marked` 给的 `token.lang` 是**整条 info string 去空白**，直接喂 `supportsLanguage()`→`hljs.getLanguage()` | `@earendil-works/pi-tui … → src/components/markdown.ts:520-540`；`marked@18.0.5` `fences()`：`lang: t[2] ? t[2].trim()…`（`node_modules/marked/lib/marked.esm.js` 内联 `fences`），故 ` ```js title=x ` ⇒ `lang="js title=x"` ⇒ hljs 不认 ⇒ **整块不上色** |
| 超长代码 | **无上限**。只有 `read` 预览 10 行（`read.ts:129`）、`write` 预览 10 行（`write.ts:117`）、工具级截断（`read.ts:140-144`） | 同上 |
| 流式未闭合围栏 | `trimPartialClosingFences`：尾行是**不完整的闭合围栏**（长度短于开栏、且全是同一字符）时把它从 `token.text` 里剪掉；只处理最后一个 token，并递归 list/blockquote | `src/components/markdown.ts:146-169` |
| 终端 ANSI | **pi 在源头剥**：bash 执行器的每个数据块 `stripAnsi(...)`，落盘/转发/最终 `output` 都是无 ANSI 的；显示层 `getTextOutput` 再剥一次；流式 bash 组件也剥 | `core/bash-executor.js.map → src/core/bash-executor.ts:78-105`（`:82` 是剥的那行）与 `:113-129`；`core/tools/render-utils.js.map → src/core/tools/render-utils.ts:39-48`；`modes/interactive/components/bash-execution.js.map → src/modes/interactive/components/bash-execution.ts:81-83` |

### A2. App 在哪一步上色（三层分工）

| 层 | 做什么 | 证据 |
|---|---|---|
| ① `pi-highlight` 扩展（guest Node） | 在 pi 进程内起 loopback HTTP + bearer；`hljs` **懒到一次请求只加载 `lib/core.js` + 一个语言文件**；产出 `{start,end,scopes[]}` offsets 而非 ANSI；`known:false` 与 `ENGINE_UNAVAILABLE` 是两种不同答案 | `app/src/main/assets/pi-extensions/pi-highlight/service.ts:182-289`（路由，`:247-252` 未知语言、`:210-217` 长度上限）、`hljs.ts:126-156`（定位 pi 自己的 hljs）、`:220-241`（按需 `require`）、`:260-273`（`ignoreIllegals:true` + `htmlToRuns`）、`html-runs.ts:152-231`（pi 的 HTML 行走器换成 offsets，含 `isSpanOpenTagStart`、实体解码、相同 scope 合并） |
| ② Kotlin 客户端 | 只做传输/校验/翻译：`codeUnits` 必须等于本地代码长度（否则整份丢弃）、scope 栈 → token 表、401 重读一次 token | `app/src/main/kotlin/app/pi/highlight/PiHighlightClient.kt:99-206`（`:198-203` 长度校验、`:249-271` spans 解析）、`PiHighlightScopes.kt:31-78`（**与 `theme.ts:1036-1064` 逐行同表**，含 dot/dash 前缀与"最内层先命中"） |
| ③ Compose | 把 spans 贴到 `AnnotatedString`；语言归一化；未知语言/服务不可用一律"无色" | `ui/render/PiCodeHighlight.kt:19-87`（token↔palette）、`:109-137`（接缝 + `PiPlainCodeHighlighter`）、`:148-241`（别名表 + `forPath` + `isPlaintext`）；`ui/render/PiMarkdownComponents.kt:451-468`（贴 span，含 `Emphasis→Italic`/`Strong→Bold`/`Link→Underline`） |

**降级矩阵（App）**

| 情况 | 结果 | 证据 |
|---|---|---|
| 服务未启动 / 读不到 token | 空 spans → 纯 `mdCodeBlock` | `PiHighlightClient.kt:64-89`、`:99-104`；`PiNodeCodeHighlighter.kt:124-136` |
| 连接/读取超时（100 ms connect / 150 ms read；排队+请求合计 750 ms） | 空 spans | `PiHighlightClient.kt:281-282`；`PiNodeCodeHighlighter.kt:61` |
| 语言未知（`known:false`） | 空 spans，且**算确定答案**被缓存 | `service.ts:247-252` → `PiHighlightClient.kt:192-197` → `PiNodeCodeHighlighter.kt:145-147` |
| 并发>2 / 队列>64 / 等待超时 | 丢弃 → 纯文本 | `PiNodeCodeHighlighter.kt:51-52`、`:194-215` |
| 代码 >64 KB 或 >400 行 | **不发请求**，纯文本 | `PiNodeCodeHighlighter.kt:79-80`、`:135`；与 `service.ts:61` 的上限同值 |
| `codeUnits` 不符 / 响应非 JSON / 超大 | 丢弃 → 纯文本 | `PiHighlightClient.kt:184-203`、`:235-245` |
| **没有语言**（围栏无 info） | 直接纯文本，**不问服务** | `PiCodeHighlight.kt:239-240` + `PiMarkdownComponents.kt:424-425` + `PiNodeCodeHighlighter.kt:133` |

> **机制结论**：pi 是"同步、进程内、无上限、无缓存、不猜测"；App 是"异步、跨进程、有上限、有缓存、不猜测"。**唯一不可能一致的降级路径是"语言还在后台加载"**——pi 在会话刚开、后台 171 语言未就绪时会把 `sql/html/yaml` 等渲染成纯文本，之后才上色；App 的服务是按需即时 `require`，**不存在这个中间态**（`hljs.ts:220-241`）。这是"更好"，但确实不同。

### A3. 触发时机：pi vs App（**明确结论**）

**pi：不是"渲染时上色一次"，而是"每次行缓存失效就重算"。**
- `Markdown.render(width)`（`src/components/markdown.ts:277-368`）先查 `(cachedText, cachedWidth)` 行缓存（`:279-281`，写回 `:363-366`），未命中才走 `renderToken` → `case "code"` → `this.theme.highlightCode(token.text, token.lang)`（`:520-540`，调用在 `:524`）。
- 因此：**文本变 或 宽度变 ⇒ 屏幕上每个代码块都重跑一次 hljs**（终端 resize 会触发全量重算）。`highlightCode` 自身无缓存（`theme.ts:1078-1097`），只有"scope→formatter"的函数表按 theme 缓存（`:1066-1072`）。
- pi 对"流式重算"的补丁只在 `write` 工具：增量前缀复用 + 单行 `highlightSingleLine` + 每 50 行刷新前缀（`write.ts:29-86`、`:154-155`）。

**App：键控 + 缓存 + 去抖，重组不会重算。**
- 唯一真正调高亮器的地方：`PiMarkdownComponents.kt:418-432` 的 `produceState(initialValue=emptyList(), code, language, highlighter)`；**只有 `code`/`language`/`highlighter` 三者之一变化才会重新启动生产者**。
- 颜色贴在 `remember(code, spans.value, palette)`（`:433-435`）——**换主题只重贴色，不重跑高亮器**（这正是它注释里写的目的）。
- 第二次调用点 `ToolBodyText.kt:54-59` 全部在 `remember` 里。
- 后端还有两级：`PiNodeCodeHighlighter` 的 128 条 SHA-256 LRU（`:70`、`:156-162`）+ `BoundedWorkers(2, 64)`（`:177-243`）。
- 流式：**第一次**（块首次出现）不等，之后的更新先 `delay(200ms)`（`:417`、`:429`、`:444`），且每次变更会取消上一个等待，所以"一个 token 一次请求"不会发生。

**逐块核对（`ui/blocks/*`，只列与解析/上色有关的）**

| 块/文件 | 上色或解析 | 是否在 `remember` 内 | 证据 |
|---|---|---|---|
| `AssistantTextBlock.kt` | 交给 `PiMarkdownText` | ✓ 上游 | `:59-63`；`PiMarkdown.kt:81`（源码重写 `remember(markdown)`）、`:93-103`（五个对象全部 `remember`） |
| `ToolBodyText.kt`（`read`/`write` 正文） | **上色 + 行号拼接** | ✓ 全在 | `:54`、`:55`、`:56`、`:57-59` |
| `PiMarkdownComponents.kt`（围栏/码块） | **上色** | ✓ `produceState` + `remember` | `:360`、`:418-433` |
| `ToolCallBlock.kt` | 预览派生（不是上色） | ✓ | `:55,56,63,72,73,82,90,103,115` |
| `ShellBlock.kt` | 尾部行取用 | ✓ `painted`/`bodyText` 等 | `:57-76`；**例外**：`:78` 的 `shellFooter(...)` 每次重组重算（纯字符串拼接，廉价） |
| `GrepBlock.kt` | 解析 | ✓ | `:51,56,57,58,59,62,69,72` |
| `PathListBlock.kt` | 解析 | ✓ | `:105,110,111,112,115,122,125` |
| `ReadBlock.kt` | 正文解析 | ✓ | `:47-51,58`；**例外**：`:55-56` 的 `shown`/`hidden` 每次重组算（`take`+减法） |
| `WriteBlock.kt` | 正文解析 | ✓ | `:43,44,50`；**例外**：`:46-47` 同上 |
| `EditBlock.kt` | 无上色（diff 由 `ToolDiff` 行渲染） | ✓ | `:43,44,46` |
| `DiffBlock.kt` | 差异行规划 | ✓ | `:45`；`:46` 的 `omitted` 廉价 |
| `ImageGridBlock.kt` | 位图 | ✓ `produceState` | `:113` |

> **结论**：App **没有**"每次重组重算上色"。用户怀疑的卡顿若与上色路径有关，可排除"重组重算高亮"这一条；剩下的候选是**markdown 解析**（第三方库，每个 token 重解析整篇）与**未 `remember` 的几处廉价字符串工作**（`ShellBlock.kt:78`、`ReadBlock.kt:55-56`、`WriteBlock.kt:46-47`），以及 `DiffBlock` 一次性生成最多 200 行 Compose 节点（`:101-124`）。

### A4. 语言别名 / 扩展名映射：与 pi 的逐个差异

**两侧共同点**：App 的 `normalize` 把围栏名按 `aliases` 表归一（`PiCodeHighlight.kt:209-215`），`forPath` 复用同一张表（`:230-236`）。

| # | 情形 | pi | App | 影响 |
|---|---|---|---|---|
| 1 | 围栏带属性 ` ```js title=x ` | `lang="js title=x"` → hljs 不认 → **整块不上色** | 取第一个词 `js` → 上色 | App 更宽容；**与 pi 不一致**（无害） |
| 2 | 围栏写 ` ```language-js ` | hljs 不认 `language-js` → 不上色 | 去 `language-` 前缀 → 上色 | 同上 |
| 3 | 围栏 ` ```JavaScript `/` ```SH ` | hljs 内部小写化 → 上色 | `lowercase()` → 上色 | 一致 |
| 4 | 围栏 ` ```console `/` ```shell `/` ```sh-session ` | hljs 别名表认（`console→shell→bash`） | 显式归一为 `bash` | 一致 |
| 5 | **`forPath` 无扩展名文件** `Dockerfile`/`Makefile` | `"Dockerfile".split(".").pop()` = `"Dockerfile"` → `dockerfile` ⇒ **高亮** | `dot <= 0` 直接 `null`（`:234`）⇒ **不高亮** | **缺陷**：App 少一处上色 |
| 6 | **`.cmake`** | 表内有 `cmake: "cmake"` ⇒ 高亮 | 表里没有 `cmake` 键 ⇒ `null` ⇒ 不高亮 | **缺陷** |
| 7 | `.txt` / `.diff` / `.ini` / `.jsonc` / `.kts` / `.nix` / `.groovy` / `.dart` / `.m` | 表内没有 ⇒ **不高亮**（`read`/`write` 走 `toolOutput`） | 表内有（`:173,192-201`）⇒ 会去问服务并可能上色（`ini/diff/nix/groovy/dart/objc` hljs 都认） | App 多上色；`.txt/.jsonc/.kts` 因归一后是 `plaintext`/hljs 不认，净效果仍是纯文本 |
| 8 | `objectivec` | 表内无（eager 语言里也没有） | 有（`:201`） | App 多 |
| 9 | 表规模 | 59 键 | 71 键（59 − `cmake` + 13） | 统计口径：`PiCodeHighlight.kt:150-202` vs `theme.ts:1106-1165` |

> **注**：App 的别名表**刻意多出来的那部分**（`kts/jsonc/shell/sh-session/console/diff/patch/ini/text/plain/txt/nix/groovy/dart/objectivec/objc`）与扩展里的 `LANGUAGE_ALIASES`（`aliases.ts`，hljs 自己的 173 条别名）**职责不同**：前者是"围栏/路径名 → hljs 名"，后者是"hljs 名 → 语言文件"。两侧都合理，但**没有任何一处保证两边不打架**——`aliases.ts` 是生成+校验过的（`hljs.ts:27-30`、`tools/pi-highlight-check.mjs`），Kotlin 那张表没有对应校验。**不确定**：Kotlin 表与 `aliases.ts` 是否存在冲突键（例如 `console`），本次未做机械比对 → 见 §不确定 2。

---

## B. 颜色映射对比

### B5. pi 的**全部**上色来源（这是本节的事实基础）

| # | 来源 | 内容 | pi 里的用途 | 证据 |
|---|---|---|---|---|
| S1 | 主题 JSON `colors` | 51 必需 + 5 可选（有回退） | 一切 | `dist/modes/interactive/theme/theme-schema.json`；回退 `theme.ts:270-274` |
| S2 | `syntax*` ×9 | hljs scope → token，**CLI 版** | 代码围栏、`read`/`write` 正文 | `theme.ts:1036-1064` |
| S3 | `md*` ×10 | markdown 元素钩子 | 标题/链接/URL/行内码/码块/码块框/引用/引用框/hr/列表点 | `theme.ts:1170-1207`；消费点 `src/components/markdown.ts:520-540`、`:685-709`、`:873-931`(CSS) |
| S4 | `toolDiff*` ×3 | 增/删/上下文行 + **行内词级 `theme.inverse()`** | `edit` 预览、diff | `modes/interactive/components/diff.js.map → src/modes/interactive/components/diff.ts:24-79`（词级）、`:86-160`（行级） |
| S5 | mermaid | `borderMuted/text/accent/muted(+bold)/warning` | ` ```mermaid ` 渲染成 ASCII 图后按 span 类着色 | `modes/interactive/components/mermaid.js.map → src/modes/interactive/components/mermaid.ts:38-53`（`styleSpan`）、`:55-56`；开关 `mermaidRenderingMode` 设置 |
| S6 | ANSI 16/256/RGB 调色板 | 硬编码 16 色表 + 256 色立方 + 灰阶 | **仅 HTML 导出**（`ansi-to-html.ts`）；TUI 侧被剥掉 | `core/export-html/ansi-to-html.js.map → src/core/export-html/ansi-to-html.ts:15-31`（表）、`:141/161/181/184`（30-37/40-47/90-97/100-107）、`:193`（`ESC[…m` 正则） |
| S7 | HTML 导出的 CSS 变量 | 所有 token → `--<token>`（含 3 个 `export.*` 派生） | 导出一份自带主题的 HTML | `core/export-html/index.js.map → src/core/export-html/index.ts:109-125`；`template.css:587-589`(diff)、`:873-931`(md)、`:959-972`(语法) |
| S8 | **第二张 hljs→token 表（与 S2 不同！）** | `.hljs-meta→syntaxKeyword`（S2 是 `muted`）、`.hljs-name→syntaxFunction`（S2 是 `syntaxKeyword`）、`.hljs-doctag→syntaxString`（S2 是 `syntaxComment`）、`.hljs-selector-tag→syntaxKeyword`（S2 是 `syntaxPunctuation`）、另有 `.hljs-subst→text`、`.hljs-property/.hljs-variable.language_` | 只在导出 HTML 里 | `template.css:959-972` vs `theme.ts:1036-1064` |
| S9 | `thinking*` ×7 | 思考级别边框色（`thinkingMax` 回退到 `thinkingXhigh`） | 思考框 | `theme.ts:371-390`、`:272` |
| S10 | `bashMode` | `!` 模式边框（跑 bootstrap 时是 `dim`） | 输入框/流式 shell 框 | `theme.ts:393-395`；`bash-execution.ts:37-64` |
| S11 | 面板/表面色 | `toolPendingBg/SuccessBg/ErrorBg`、`toolTitle`、`toolOutput`、`userMessage*`、`customMessage*`、`selectedBg`、`searchMatch*`、`scrollbar*`、`border*` | 上色内容的背景与外壳 | `theme.ts:41-90`（`ThemeColor` 联合）、`:371-395` |

### B6. App 是否体现（逐来源表）

| pi 的颜色来源 | pi 里的用途 | App 是否体现 | App 的位置 | 差异与影响 |
|---|---|---|---|---|
| S1 主题 56 token | 全局 | **有** | `ui/theme/PiPalette.kt:31-100`（56+3 字段）、`165-288`（两套内置值）；加载/回退 `ui/theme/PiThemeFiles.kt:110-132`（51 必需 + 5 回退，**与 `theme.ts:270-274` 一一对应**）、`525-567` | 无差异。注：`PiThemeFiles.kt:110` 注释写“54 个”，实为 51+5=56（文档笔误，无害） |
| S2 `syntax*` ×9 | 围栏/文件正文语法色 | **有（表 1:1）** | 表：`highlight/PiHighlightScopes.kt:31-57`（22+3 条，与 `theme.ts:1036-1064` 逐条相同）；颜色：`ui/render/PiCodeHighlight.kt:73-87`；贴色：`PiMarkdownComponents.kt:451-468` | **表一致；底色不一致** → 缺陷 **D1** |
| S3 `mdHeading/mdLink/mdCode/mdCodeBlockBorder/mdQuote/mdHr/mdListBullet` | markdown 各元素 | **有** | `ui/render/PiMarkdownTheme.kt:218`（标题）、`:243-245`（链接）、`:237`（行内码）、`:145`（hr）、`:238`+`:102`（引用，**`mdQuoteBorder` 合并进 `mdQuote`**）、`:241`（列表点）、`:364`（码块边框） | 引用框色是唯一真妥协：库的引用条与正文共用一个色（`PiMarkdownTheme.kt:106-110` 已注明）；内置 dark/light 两个 token 同值 ⇒ 内置主题下**等价**，自定义主题会丢区分 |
| S3 `mdCodeBlock` | **pi：仅“不上色”分支** | **有，但用错位** | `PiMarkdownTheme.kt:236`（码块/围栏基色）、`PiMarkdownComponents.kt:359,375-377` | **缺陷 D1**：pi 在“上色成功”分支不用它 |
| S3 `mdLinkUrl` | 无 OSC8 时 `(URL)` 文字色 | **没有**（加载了、从不绘制） | 仅 `PiPalette.kt:64/194/257`、`PiThemeFiles.kt:540/603`（装载/回写） | **缺陷 D2**：`PiMarkdownTheme.kt:97` 说“仍可为链接面板解析”，但仓库内**没有任何链接面板**（`grep mdLinkUrl` 无消费点） |
| S4 `toolDiffAdded/Removed` | diff 增删**整行**文字色 | **部分**（只用在符号列/统计） | `blocks/DiffBlock.kt:72,74`（`+N/−N` 统计）、`:146-148`（符号列色） | **缺陷 D3**：pi 是 `theme.fg("toolDiffRemoved", "-N " + content)`（`diff.ts:127-152`）——**整行**染色；App 正文用 `palette.text`（`DiffBlock.kt:153-156`） |
| S4 `toolDiffContext` | 上下文行 | **部分（派生）** | `DiffBlock.kt:98,151,155` 经 `PiPalette.kt:141` 的 `contextOnTool` | 有意的对比度抬升（见 §B8-b）；**但 pi 的原始值 `#808080` 与 App 实际值不同** |
| S4 **行内词级 `inverse`** | 单行修改时对变化的词反白 | **没有** | — | **缺陷 D4**：`diff.ts:24-79` 的 `renderIntraLineDiff` 在 App 无对应物 |
| S5 mermaid | 4 个 token 上色 ASCII 图 | **没有** | 全仓库 `grep -i mermaid` = 0 命中 | **缺陷 D5**：pi 把 ` ```mermaid ` 画成图；App 会把 `mermaid` 送去高亮器 → hljs 不认 → 纯文本源码 |
| S6 ANSI 16/256/RGB | 仅导出 HTML | **没有**（也不需要） | 解释器在 `rpc/src/main/kotlin/app/pi/rpc/Ansi.kt:23-35`、`68+`；**生产路径唯一调用是 `Ansi.strip`**（`app/src/main/kotlin/app/pi/packages/PiListOutput.kt:41`） | 因为 pi 在源头就剥了（`bash-executor.ts:82`、`render-utils.ts:48`）。**`Ansi.parse` 全仓库零生产调用**（只有 `rpc/src/test/.../AnsiTest.kt`）——死代码，且**若**将来有工具绕过 bash 执行器吐 ANSI，App 会把转义序列原样画出来（`ShellBlock.kt:84-88` 直接 `MonoText(item.output)`） |
| S7 导出 CSS 变量 | 导出 HTML | **没有**（App 无 HTML 导出） | — | 无害；记账用 |
| S8 第二张 hljs 映射表 | 导出 HTML | **没有**（App 选了 S2 那张 = 对 TUI 保真） | `PiHighlightScopes.kt:31-57` | **这是正确的选择**：App 是 TUI 的替代品。但要记一笔：pi 内部两张表不一致，未来若做导出必须换表 |
| S9 `thinking*` ×7 | 思考框 | **有** | `PiPalette.kt:102-110`（`thinking(level)`，含 `max`）、`87-93/214-220/277-283` | 一致（`thinkingMax` 用可选回退，见下） |
| S10 `bashMode` | `!` 模式边框 | **有** | `ui/screens/ChatScreen.kt:1690`（`bashModeOf(draft)` `:1790`） | 一致 |
| S11 面板/表面色 | 卡底/标题/输出/消息面 | **有** | 逐文件清单见 `00-screen-inventory.md` §4.8；本次抽查 `DiffBlock.kt:50,67`、`ShellBlock.kt:86`、`ToolCallBlock.kt:151`（`bodyOnTool`）、`ChatScreen.kt:994`（`searchMatchText`） | 大体一致；`toolOutput` 常以 `bodyOnTool` 派生出现（见 §B8-b） |

### B7. 专项检查

**(1) 工具输出里的 ANSI 颜色**
- pi：**剥掉，不是转换**。bash 执行器 `stripAnsi`（`bash-executor.ts:82`）→ 流式/最终文本都无 ANSI；显示层 `getTextOutput` 再剥（`render-utils.ts:48`）；流式 bash 组件再剥（`bash-execution.ts:83`）。**终端 16 色只在 HTML 导出里活着**（`ansi-to-html.ts:15-31`）。
- App：**既不剥也不画**。转义序列会原样进 `Text`（`ShellBlock.kt:84-88`）。
- **净效果**：因为 pi 已经在源头剥过，App 现在**看不到** ANSI 垃圾 —— 所以这不是一个当前会爆的缺陷；但它是一个**依赖上游行为的隐含假设**，且仓库里那个能正确解析 16/256/RGB 的 `Ansi.parse` **从未接线**（`Ansi.kt:68` 起，零生产调用）。归 §B8-(c) 记账 + §建议 6。

**(2) diff 的行内词级高亮 与 toolDiffContext**
- pi：先按"1 删 + 1 增"配对算 `Diff.diffWords`，变化的片段套 `theme.inverse`（并刻意去掉首个片段的前导空白，避免把缩进反白）；只有**恰好 1 删 1 增**才做，多行是整块列出（`diff.ts:24-79`、`:118-135`）。整行色：`toolDiffRemoved`/`toolDiffAdded`/`toolDiffContext`（`:127-152`）。
- App：`DiffBlock.kt:35-136`（卡片）+ `:139-186`（行）。**无 inverse、无词级**；正文色是 `palette.text`（`:154`）+ 8% 色底（`:160`）；`toolDiffContext` 经 `contextOnTool` 派生（`:151,155`）。
- 结论：**缺陷 D3/D4**（行色与词级两处都不同）。`toolDiffContext` 本身"有体现但被改写"。

**(3) markdown 每一类元素**

| pi 元素 | pi 钩子/token | App | 证据 |
|---|---|---|---|
| 标题 | `mdHeading` | **有** | `PiMarkdownTheme.kt:218`（h1–h6 + alertTitle 全用同一色） |
| 链接文字 | `mdLink` + 下划线 | **有** | `:243-245` |
| 链接 URL | `mdLinkUrl`（仅无 OSC8 时显示 `(href)`） | **没有** | 见 D2 |
| 行内码 | `mdCode`（无底色） | **有，但加了底色** | `:237`；底色 `:144` = `infoBg` ← **挪用**，见 D6 |
| 码块正文 | `mdCodeBlock`（**仅不上色分支**） | **有，且被当成基色** | 见 D1 |
| 码块边框 | `mdCodeBlockBorder`（pi 画的是 ``` 围栏行本身） | **有**（1dp stroke） | `PiMarkdownComponents.kt:364` |
| 引用正文 | `mdQuote` | **有** | `:238` |
| 引用边框 | `mdQuoteBorder` | **合并进 `mdQuote`** | `:102`（注释自认） |
| hr | `mdHr` | **有** | `:145`（`dividerColor`） |
| 列表点 | `mdListBullet` | **有** | `:241`（`bullet`） |
| 粗/斜/下划线/删除线 | 装饰（`bold/italic/underline/strikethrough`） | **有** | `:218`(SemiBold)、`:244`；行内 `del` 由库默认 |
| GFM alert | pi 没有 | App 自造映射 | `:178-186`（`NOTE→mdQuote`、`TIP→success`、`IMPORTANT→mdHeading`、`WARNING→warning`、`CAUTION→error`） |
| 表格 | 导出用 `mdCodeBlockBorder` 做边框、`rgba(128,128,128,.1)` 表头底 | 库默认 + `tableBackground=cardBg` | `PiMarkdownTheme.kt:146,246` |

**(4) “外壳”颜色（语言标签 / 行号 / 复制按钮）**

| 外壳 | pi | App | 差异 |
|---|---|---|---|
| 语言标签 | 就是开栏行本身，用 `mdCodeBlockBorder` 打印 ` ```lang ` | 库的 `MarkdownCodeBackground(showHeader=true, language=…)`（`PiMarkdownComponents.kt:368-372`） | App **没有把该标签指定为 `mdCodeBlockBorder`**，按该文件自己的 KDoc（`PiMarkdownTheme.kt:131-134`）库读的是 `MarkdownColors.text` → 落在 `palette.text`。**缺陷 D7（颜色）**；另 `00-screen-inventory.md` §4.4 把它记成“库默认”，本次未独立核对库源码 → §不确定 4 |
| 码块行号 | **pi 的 `read`/`write` 没有行号**（`read.ts:126-134` 只有高亮/toolOutput 与续行提示） | App 自加：`ToolBodyText.kt:83-89`（`padStart` 到最宽），色 = `palette.muted`（`:57-58`） | 有意的平台差异（§B8-b） |
| diff 行号 | 嵌在染色行内：`-123 content`，色 = 行色（`diff.ts:127-152`） | 单独一列，`palette.dim`（`DiffBlock.kt:170-177`） | 差异（同 D3 的一部分） |
| 复制按钮 | pi 没有 | App 自加（`PiMarkdownComponents.kt:368-371`），颜色未指定 = 库/Material 默认 | 自造外壳；**不是 pi token**（§B8-c） |

**(5) 那 5 个可选令牌**

| 令牌 | pi 回退 | App | 是否被用 |
|---|---|---|---|
| `scrollbarTrack` | `muted` | `PiThemeFiles.kt:127` | **未体现**（`palette.scrollbarTrack` 零消费点；App 没有全屏滚动条组件） |
| `scrollbarThumb` | `text` | `:128` | **未体现**（同上） |
| `thinkingMax` | `thinkingXhigh` | `:129` | **有**（`PiPalette.kt:108` → 思考梯度 `thinking("max")`） |
| `searchMatchBg` | `selectedBg` | `:130` | **有**（`ChatScreen.kt` 搜索高亮 3 处） |
| `searchMatchText` | `text` | `:131` | **有**（`ChatScreen.kt:994` 等） |

回退关系与 `theme.ts:270-274` **完全一致**；2 个 `scrollbar*` 属于"加载正确、无处绘制"（平台差异）。

**(6) App 自造颜色混进上色路径**

| 自造项 | 位置 | 性质 |
|---|---|---|
| `PiContrast` 5 派生（`bodyOnTool`/`contextOnTool`/`thinkingBodyOnCanvas`/`metaOnCanvas`/`metaOnCard`） | `PiPalette.kt:130-161`；算法 `PiContrast.kt:58-75` | 用户已知，**已知的唯一系统性色值偏移** |
| `onAccent = White/Black` | `PiTheme.kt:253`（被 `:256,262,267,294` 用作 onPrimary/Secondary/Tertiary/Error） | pi 主题无此字段 ⇒ **App 自造** |
| `scrim = Color.Black` | `PiTheme.kt:298` | App 自造 |
| 终端前景/背景硬编码 | `ui/terminal/TerminalPalette.kt:35-36,40-41` | App 自造（文档已声明终端不是主题化表面） |
| **行内码底色 = `infoBg`** | `PiMarkdownTheme.kt:144` | **挪用**：`infoBg` 在 pi 里是"导出页 info 面板底色"（`theme-schema.json` export 段），pi 的行内码**没有底色**（导出 `.hljs`/`code` 均是透明、只有字色 `mdCode`）。`PiMarkdownTheme.kt:93-104` 的对照表**没有登记这两个底色** |
| **码块底色 = `cardBg`** | `:143` | 同上：pi 的码块无独立底色（导出 `pre{background:transparent}`） |
| **高亮块基色 = `mdCodeBlock`** | `:236` + `PiMarkdownComponents.kt:375-377` | **错位**：见 D1 |
| `read`/`write` 正文基色 = `bodyOnTool` | `ReadBlock.kt:81`、`WriteBlock.kt:82` | 半错位：pi 的高亮分支不给底色；非高亮分支给 `toolOutput`（`read.ts:132`）。`bodyOnTool` 是 `toolOutput` 的对比度抬升版，**对非高亮分支算忠实，对高亮分支仍是"pi 没有的底色"** |
| `DiffBlock` 8% 色底 + 符号列 | `DiffBlock.kt:160`、`:141-152` | 颜色是 pi 的，**alpha 是 App 的**（可接受，但正文色另见 D3） |
| 卡片边框 `borderMuted.copy(alpha=0.35f)` | `:53` | 同上 |
| 代码块语言标签用 `palette.text` | 见 B7-(4) | 未指定成 pi 的 `mdCodeBlockBorder` |

### B8. 「代码高亮相关的处理，和 pi 一模一样吗？」

**不一样。** 分三类：

#### (a) 必须修的保真度缺陷（按严重度）

> **状态（本轮已施工）**：下表是**审计当时**的状态与证据，行号也是当时的；除 **D2 经复核裁决为「无需改渲染」**（见「施工记录」D2 行：App 落在 pi 的同一分支）外，**D1、D3、D4、D5、D6、D7、D8 均已修**，逐条的改动落点与理由见下方「施工记录：本次『修到 1:1』改了什么」。

| ID | 缺陷 | 现状证据 | pi 的权威行为 |
|---|---|---|---|
| **D1** | 高亮成功时，未着色字符的基色 = `mdCodeBlock`（dark = 绿 `#B5BD68`） | `PiMarkdownTheme.kt:236`（`code = mono.copy(color = mdCodeBlock)`）、`PiMarkdownComponents.kt:359,375-377`；`read`/`write` 同理 `ReadBlock.kt:81`、`WriteBlock.kt:82` | `theme.ts:1186-1205` 上色分支**只返回 hljs 着好色的行**，其余字符无 ANSI = 终端默认；HTML 导出两处明证 `template.css:959`（`.hljs{color:var(--text)}`）、`:906-909`（`pre code{color:var(--text)}`）。`mdCodeBlock` 只在 `theme.ts:1085`/`:1193`（不上色）出现 |
| **D2** | `mdLinkUrl` 加载了但从不绘制；文档声称的"链接面板"不存在 | `PiPalette.kt:64,194,257`；`PiThemeFiles.kt:540,603`；`PiMarkdownTheme.kt:97`；全仓库无消费点 | `theme.ts:1174` + `markdown.ts:699-707`：非 OSC8 时 `linkUrl(" (href)")` |
| **D3** | diff 增删行**正文**不用 `toolDiffAdded/Removed` | `DiffBlock.kt:153-156`（正文 `palette.text`），`:146-152`（只有符号列用 diff 色） | `diff.ts:127-152`：整行 `theme.fg("toolDiffRemoved", "-N content")` |
| **D4** | 无行内词级高亮（`inverse`） | `DiffBlock.kt` 全无 background/inverse span | `diff.ts:24-79`（`renderIntraLineDiff`，含"首片段去前导空白"这个细节）、`:118-135`（只在 1 删 1 增时做） |
| **D5** | mermaid 上色/渲染全缺 | 全仓库 `mermaid` 零命中 | `mermaid.ts:38-56`：`borderMuted/text/accent/muted/accent+bold` |
| **D6** | 自造底色：行内码 `infoBg`、码块 `cardBg` | `PiMarkdownTheme.kt:143-144` | pi 无码块/行内码底色；`infoBg` 的 pi 用途是导出的 info 面板（`theme-schema.json` export 段） |
| **D7** | 码块语言标签色不是 `mdCodeBlockBorder` | `PiMarkdownComponents.kt:368-372`（未指定色）；据 `PiMarkdownTheme.kt:131-134` 落到 `palette.text` | `markdown.ts:522,535`：开/闭栏行用 `codeBlockBorder` |
| **D8** | `forPath` 漏 `.cmake` 与**无扩展名** `Dockerfile`/`Makefile` | `PiCodeHighlight.kt:230-236`（`dot <= 0 → null`；表内无 `cmake`） | `theme.ts:1102-1168`（`split(".").pop()` 对无点文件名返回整名；表内有 `cmake`/`dockerfile`/`makefile`） |

#### (b) 有意的平台差异（理由充分，**不算缺陷**，但要写清理由）

| 项 | 差异 | 理由（已写在代码里） |
|---|---|---|
| 高亮器跑在 guest Node | 跨 loopback 请求，而不是进程内 | 手机上跑 JS 引擎 48–98 ms/块，同一份 hljs 在 Node 里 ~2 ms，且**输出与 pi 同版同文法**：`PiNodeCodeHighlighter.kt:13-21`、`hljs.ts:1-36` |
| 交付 offsets 而非 ANSI | App 端二次贴色 | Compose 需要 `AnnotatedString` 区间：`html-runs.ts:1-24` |
| 上限/并发/去抖 | 64 KB、400 行、2 并发、队列 64、750 ms、200 ms 去抖 | 终端同步、手机要掉帧：`PiNodeCodeHighlighter.kt:43-80`、`PiMarkdownComponents.kt:438-444` |
| 逐语言懒加载 | 不在启动时注册 20+171 语言 | 手机内存/CPU：`hljs.ts:11-36` |
| `PiContrast` 5 派生 | `toolOutput`/`toolDiffContext`/`thinkingText`/`muted`/`dim` 抬到 3:1 / 4.5:1 | 终端字形 vs 手机 13–15sp 正文：`PiPalette.kt:112-161`、`PiContrast.kt:58-75` |
| `read`/`write` 加行号 | pi 无行号 | 手机缺"工具调用行上的 :offset"上下文：`ToolBodyText.kt:27-35` |
| diff 8% 色底 + 符号列 | pi 无 | "颜色不能是唯一信号"：`DiffBlock.kt:28-32` |
| 码块/行内码加圆角底色与边框 | pi 是终端字符排版 | 触屏卡片化 |
| 复制按钮、语言标签抬头 | pi 无 | 触屏能力：`PiMarkdownComponents.kt:368-371` |
| `scrollbar*` 无处绘制 | pi 用于全屏滚动条 | App 用 Compose 滚动条 |
| 链接无 OSC8 | 不下划线超链接、不打印 URL | 手机不打印 `(href)`（但见 D2） |
| ANSI 不解析 | pi 已在源头剥 | 依赖上游行为；见 §建议 6 |

#### (c) 无害的实现差异

| 项 | 说明 |
|---|---|
| ~~语言别名表多 13 项、少 1 项~~ | **已修（本轮）**：拆表后路径表 = pi 的 58 键（含 `cmake`），围栏不再查别名表 —— 见「别名表机械比对」 |
| ~~围栏 info string 取第一个词~~ | **已修（本轮）**：`normalize` 只剩 `trim + lowercase`，与 pi 交给 hljs 的字符串一致 |
| 高亮器缓存/并发结构 | pi 靠 `(text,width)` 行缓存（`markdown.ts:279-281`），App 靠 `remember`+LRU；**两边都不会"每帧重算"以外的行为差异** |
| `Ansi.parse` 仍是零生产调用 | `rpc/.../Ansi.kt:68+`：本轮按 pi 的行为在**渲染侧**接了 `Ansi.strip`（`ShellBlock.kt`、`ToolBodyText.kt`），`parse` 这一半没有对应需求（pi 从不自造 ANSI 渲染）。**处置建议见 §建议修复项 9**：或删 `parse`+`Span`+对应测试，或保留并注明它是给未来"带色输出面"的、已被测试覆盖的工具——我**没有删**，因为整份 `AnsiTest` 要跟着重写，属于 Protocol core 的 CI 面，收益为零、风险不为零 |
| `PiThemeFiles.kt:110` 注释"54 个 token" | 实为 51+5=56（笔误，未改：越界文件） |
| pi 内部两张 hljs 映射表不一致（S2 vs S8） | App 选了 TUI 那张，正确 |
| `PiSyntaxToken.Emphasis/Strong/Link` | pi 的装饰项，App 用 Italic/Bold/Underline 落地（`PiMarkdownComponents.kt:625-630`）——**一致** |
| 未知语言/无语言 | 两侧都"不猜测、不上色"（`theme.ts:1078-1086` ↔ `PiCodeHighlight.kt:109-151`、`PiMarkdownComponents.kt:538-551`）——**一致** |
| scope 解析算法 | `exact → prefix(.) → prefix(-) → default(未定义) → 最内层优先` 两侧完全相同（`theme.ts:99-136` ↔ `PiHighlightScopes.kt:64-78`）——**一致** |
| 行内词级 diff 的编辑脚本 | 分词器与 `equals` 逐条移植 jsdiff，**但搜索算法用 LCS 而非 Myers**：极小编辑脚本不唯一时，"哪个词被标为变化"可能与 pi 不同（两者都是极小解）。见 `DiffBlock.kt:364-402` 的 KDoc |
| 码块宽度门 / mermaid 开关 | pi 有「图比终端宽 → 显示源码」与 `mermaidRenderingMode`/thinking 两个跳过条件；App 用横向滚动代替宽度门，且看不到消息类型 —— 逐条理由写在 `PiMermaid.kt:44-56` 与 `PiMarkdownComponents.kt:569-589` |

---

## 建议修复项（按 1:1 保真度排序）

> **状态：1–7 本轮已全部落地**（落点与理由见「施工记录」），**8 的两条已做一条、一条转建议**，另新增第 9 条（本轮审计发现的真缺陷）。下面是当时的原文，保留作为"为什么这么修"的依据。

1. **D1（最高优先，一行级改动就有肉眼可见效果）**：把"高亮成功"的基色从 `mdCodeBlock` 改为 `palette.text`。
   - 落点：`ui/render/PiMarkdownComponents.kt:357-383` 的 `PiCodeSurface`——`Text` 的 `style` 不再直接用 `model.typography.code`，改为 `model.typography.code.copy(color = palette.text)`；**同时保留**"`isPlaintext(language)` ⇒ 用 `mdCodeBlock`"这一分支（pi 的 `theme.ts:1085`/`:1193` 就是这么分的）。
   - `read`/`write`：`ReadBlock.kt:81`、`WriteBlock.kt:82` 传的 `palette.bodyOnTool` 只应在"不高亮"时生效；高亮时应是 `palette.text`（pi：`read.ts:132` 的非高亮分支才 `toolOutput`）。`ToolBodyText.kt:43-66` 对此一无所知，需要把"是否有语言"（`language != null && !isPlaintext`）作为参数传进去。
   - 验证方式：拿 dark 主题，渲染一段 Kotlin，比较"标识符/标点"的颜色是否与 pi TUI / `pi --export-html` 一致。
2. **D3 + D4**：`DiffBlock.kt:139-186`——正文行改用 `toolDiffAdded/Removed`（正文与符号同色），并在"1 删 1 增"配对时加词级反白。pi 的算法可整段移植：`diff.ts:24-79`（注意"首片段去前导空白"与"仅 1 删 1 增"两条规则）。`toolDiffContext` → 保留 `contextOnTool` 作为 §B8-b 的有意派生，或加开关。
3. **D5**：补 mermaid。至少先把 ` ```mermaid ` 从高亮路径摘出来（现在会被当未知语言渲染成纯文本源码），再决定是否移植 `mermaid.ts:38-56` 的 ASCII 渲染 + 4 个 token。
4. **D7**：把码块语言标签指定为 `palette.mdCodeBlockBorder`（需要在 `PiMarkdownComponents.kt:345-355` 的自定义码块组件里自己画 header，或确认库的可覆写点）。顺手把 `PiMarkdownTheme.kt:131-134` 的断言升级为"已核对库源码"。
5. **D6**：去掉/降级两个自造底色——`PiMarkdownTheme.kt:143-144`（`cardBg` / `infoBg`）。1:1 做法是 `codeBackground = Color.Transparent`、`inlineCodeBackground = Color.Transparent`；若为了手机可读性保留，必须写进 `PiMarkdownTheme.kt:93-104` 的对照表（现在这张表没有登记它们，属于"隐形自造"）。
6. **D2**：要么实现一个链接面板/长按显示 URL（用 `mdLinkUrl`），要么把 `PiMarkdownTheme.kt:97` 的说法改掉——当前是"文档承诺 > 实现"。
7. **D8**：`PiCodeHighlight.kt:230-236` 的 `forPath`：`dot <= 0` 时回退为"整名查表"（对齐 pi 的 `split(".").pop()`），并在表里补 `cmake`。
8. **§B8-b 的三个"隐含依赖"**（不是缺陷，但建议加护栏）：
   - ANSI：给 `ShellBlock.kt:72-74` 接上 `Ansi.strip`（或在界面上标注"依赖 pi 已剥"）；否则 `Ansi.parse` 白写且一旦上游改动会露字节。
   - Kotlin 别名表 vs `aliases.ts`：加一条机械校验（同 `tools/pi-highlight-check.mjs` 的思路），防止两张表打架。
   - `PiThemeFiles.kt:110` 的"54"改"56"。
9. **本轮新发现（已修，列出以便回归）**：`PiHighlightClient` 只在 HTTP 401 时重读凭据文件，而**引擎重启后旧端口的连接是被拒、不是 401** ⇒ 一次引擎重启会让本次 App 进程内所有代码块不再上色。已在 `fetch`/`mermaid` 的 transport 失败分支 `cached = null`（下次请求自愈）。回归方式：让引擎重启两次，观察第二个会话的代码块仍然上色。
10. **仍未改成 1:1 的三处（有意，理由已写进 KDoc）**：`DiffBlock` 的 8% 底 + 独立符号/行号列；`ToolBodyText` 给 `read`/`write` 加行号；`PiMermaid` 用横向滚动代替 pi 的宽度门、且看不到「thinking / 流式 / 开关」三态。加上 §B8-b 的 `PiContrast` 5 派生与 `contextOnTool`。

---

## 施工记录：本次「修到 1:1」改了什么

| # | 落在哪一侧 | 改动（行号为改动后位置） | 为什么现在是 1:1 |
|---|---|---|---|
| **D1** | Kotlin | `ui/render/PiCodeHighlight.kt:92-151`（新增结果类型 `PiCodeHighlight(spans, languageKnown)`；`isPlaintext` → `isUnspecified`）、`highlight/PiNodeCodeHighlighter.kt:126-149`、`highlight/PiHighlightClient.kt:92-172`、`ui/render/PiMarkdownComponents.kt:395-449`、`ui/blocks/ToolBodyText.kt:47-91` | pi 的门是 **`supportsLanguage`**（`theme.ts:1080/1088/1192`），不是「围栏有没有写语言名」。引擎的 `known` 现在随 spans 一起回到渲染层：高亮成功分支基色 = `text`（= 终端默认前景），不上色分支 = `mdCodeBlock`（围栏，`:1085`）/ `toolOutput`（read/write，`read.ts:132`）。顺带修掉一个隐藏错位：围栏写 `text`/`plaintext` 时，pi 走「hljs 认识 ⇒ 无底色」，旧实现把它归进不上色分支 |
| **D2** | 不改渲染，只改 KDoc | `ui/render/PiMarkdownTheme.kt:93-116` | 库的默认 annotator 把链接接到 `LocalUriHandler.openUri`（0.45.0 `annotator/AnnotatorSettingsKt` 的 `getLocalUriHandler` + `openUri`），App 因此落在 pi 的**有超链接能力**分支（`src/components/markdown.ts:692-707`）——该分支 pi 同样不打印 URL 文本。两侧一致；错的是原注释里那句「仍可为链接面板解析」 |
| **D6** | Kotlin | `ui/render/PiMarkdownTheme.kt:186-190`（行内码/码块底色 → `Color.Transparent`）、`ui/render/PiMarkdownComponents.kt:422-436`（码块底 `Color.Transparent`） | pi 在两种表面上都没有码块背景（终端：围栏就是文字；导出：`template.css:900-909` 明写 `transparent`/`none`）。不再用 pi 令牌去填一个 pi 没有的表面（`infoBg` 本是导出页 info 面板色） |
| **D7** | Kotlin | `ui/render/PiMarkdownComponents.kt:452-489`（自画 `PiCodeHeader`）、`:429-436`（关掉库 top bar，仍传 `language`/`code` 供无障碍标签） | 语言标签改用 `mdCodeBlockBorder`，与 pi 打印围栏行的令牌一致（`src/components/markdown.ts:522`/`:535`）；复制按钮同色，不引入新色相（它本身是 App 独有功能） |
| **D8** | Kotlin | `ui/render/PiCodeHighlight.kt:171-283`：路径表 = pi 的 58 键 `extToLang`（含新增 `cmake`），`forPath` 用 pi 的 `split(".").pop()` 语义；围栏 `normalize` 只剩 `trim + lowercase` | 见下「别名表机械比对」 |
| **D3+D4** | Kotlin | `ui/blocks/DiffBlock.kt:159-410`（整行色、词级反白、jsdiff 分词器移植、配对规则） | 整行 = `toolDiffAdded`/`toolDiffRemoved`（`diff.ts:127-152`）；1 删 1 增配对时对变化片段反白（`:24-79`，含「首片段前导空白不进反白」`:44-60`）；Compose 用「文字=卡片底、底=行色」表达 reverse video（本 App 唯一的文字背景） |
| **D5** | **扩展（新增路由）+ Kotlin（取色）** | 新增 `pi-extensions/pi-highlight/mermaid.ts`、`service.ts` 的 `/mermaid`；新增 `ui/render/PiMermaid.kt`、`highlight/PiNodeMermaidRenderer.kt`、`ui/render/PiMarkdownComponents.kt:564-604` | pi 的 mermaid 是 `grok-mermaid` 的布局 + 4 个语义类取色（`mermaid.ts:38-56`）。布局留在 guest（同一个 `grok-mermaid@0.2.2`），**取色在 Kotlin**（`PiMermaid.kt` 的 cls→token 与 pi 同表）。`render` 返回 `null`、服务不可达、超时都回退成「直接显示围栏源码」，与 pi 的 `token.raw` 回退一致（`:75-76`） |
| **ANSI** | Kotlin | `ui/blocks/ShellBlock.kt:68-80`、`ui/blocks/ToolBodyText.kt:47-72` 各接一次 `Ansi.strip`；`rpc/**` **未动** | pi 在源头剥（`bash-executor.ts:82`）并在显示层再剥（`render-utils.ts:48`），且从不自造 ANSI 渲染（16 色只在导出）。App 现在同侧剥、不画 |
| **HTML 导出那套** | 不动 | — | 按要求保持不碰（原报告 S7/S8） |

### 别名表机械比对（用户点名的未确认项，已查实）

参照物：hljs 10.7.3 的 `lib/languages/*.js` = **191** 个语言文件；扩展 `aliases.ts` = **173** 个别名键；pi `extToLang`（`theme.ts:1106-1165`）= **58** 键；改前的 Kotlin 表 = **73** 键（围栏与路径共用一张）。

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| 1 | 与 pi 共享的 58 个键**取值零分歧** | 58 键逐一比对 | 无需改 |
| 2 | Kotlin 表**缺 `cmake`** | pi `theme.ts:1149` | **已补**（`.cmake` 现在与 pi 一样高亮） |
| 3 | Kotlin 表**多出 16 键**（`kts/jsonc/shell/sh-session/console/diff/patch/ini/text/plain/txt/nix/groovy/dart/objectivec/objc`） | 旧 `PiCodeHighlight.kt` 单表两用 | **已通过拆表修掉**：路径表 = pi 的 58 键；围栏归一化 = pi 的 `trim + lowercase`（不再查别名表）。旧表会让 App 给 pi 明确不上色的 `.ini/.diff/.nix/.groovy/.dart/.objc` 上色 |
| 4 | 5 个**值**不是 hljs 语言：`fish/sass/graphql/hcl` | hljs 无这四个文法 | **保留**：pi 自己的表也这么写，且两侧都在「引擎答 `known:false`」处收敛为不上色（`:1131/1138/1161/1163`） |
| 5 | `aliases.ts` 与 hljs 实际注册表的一致性由 `tools/pi-highlight-check.mjs` 校验（191/191 语言 + 173 行别名 0 失配） | `hljs.ts:27-30` | Kotlin 侧**现在不再持有任何别名数据**，因此不存在第二份会漂移的表——这是本次比对最有价值的结论 |
| 6 | 服务自身大小写敏感（`knownLanguages().has(name)` + `LANGUAGE_ALIASES[name]`），而 `hljs.getLanguage` 会小写化 | 旧 `hljs.ts` `resolveLanguageFile` | **已修**：先 `.toLowerCase()`，服务自身即等价于 `supportsLanguage`（Kotlin 侧照旧也小写，两道都有意保留） |
| 7 | 围栏 info string 的取词规则现与 pi 一致 | pi：`marked@18.0.5` 的 `fences` 给整条 trim 后的 info string；App：`normalize` 只 `trim + lowercase` | 旧实现「取第一个词 + 去 `language-` 前缀 + 查别名表」会让 ` ```js title=x `、` ```language-js ` 上色，而 pi 是纯文本——现已一致 |
| 8 | mermaid 的取词规则**故意不同**，且与 pi 相同 | pi 的 mermaid transformer 取 info string 的**第一个词**（`components/mermaid.ts:15`） | App 在 `rememberPiMermaidArt` 里用同样的「第一个词小写化」。与上一条不矛盾：pi 自己就是两套规则 |

---

## 附：`pi-highlight` 扩展审计（用户点名：「这个上色扩展有没有什么问题？」）

结论先行：**扩展的核心没有发现必须修的上色缺陷**——它与 pi 用的是同一份 hljs、同一套 scope 语义、同源的语言判定。找到 3 个真问题：2 个已修（1 个在 Kotlin 客户端侧、1 个在扩展侧），1 个建议未改。改扩展的运行期含义见本节末。

| # | 问题 | 证据 | 影响 | 建议 / 处置 |
|---|---|---|---|---|
| E1 | **hljs 版本与 scope 语义**是否与 pi 一致 | `hljs.ts:126-156` 用 `createRequire(anchor).resolve("highlight.js/package.json")` 从 pi 自己的包根解析；`hljs.ts:200-209` 对齐 `supportsLanguage`；`PiHighlightScopes.kt:31-57` 与 `theme.ts:1036-1064` 逐条同表 | **同源**：不是「同版本号」而是**同一份模块**（10.7.3），scope 名字不可能漂移；响应还回 `hljs` 版本号供诊断（`service.ts:285`） | 保持。可选加固：让 `tools/pi-highlight-check.mjs` 断言解析到的版本 == pi `package.json` 声明的版本 |
| E2 | 语言注册方式与 pi 不同 | `hljs.ts:220-241`（按需单文件）vs pi `syntax-highlight.ts:24-68`（20 eager + 余下后台 `import`） | 结果一致（别名由语言定义自带，`resolveLanguageFile` 保证「一语言一文件」）；差别只在 pi 存在「后台加载完成前某语言暂时纯文本」的中间态，本服务没有 | 无需改，`hljs.ts:11-36` 已记录 |
| E3 | 「未命中语言」与「引擎故障」会不会混成一条 | `service.ts:247-252`（`known:false`，200）vs `:257-273`（hljs 加载失败 → 503）；客户端 `PiHighlightClient.kt:145-152`、`:186-192` | **没有混**：未知语言 = 200 + `known:false`（缓存）；加载失败 = 503（丢弃、不缓存、下次重试）。这正是 D1 需要的区分 | 保持 |
| E4 | **瞬态 fs 失败被永久缓存** | `hljs.ts:167-183`：`names` 无论成功失败都 `names = set` | `lib/languages` 一次读取失败 ⇒ 该进程此后把**所有**语言都当 unknown，直到引擎重启 | **建议（未改）**：只在成功时赋值 `names`。低概率，但后果是静默降级 |
| E5 | 凭据陈旧：引擎重启后高亮永久消失 | 客户端旧逻辑只在 HTTP 401 时 `cached = null`；引擎换进程后旧端口的连接是**被拒**而不是 401 | 一个引擎重启就能让本次 App 进程内所有代码块不再上色 | **已修**：`fetch`/`mermaid` 的 transport 失败分支现在 `cached = null`，下次请求重读 `highlight-bridge.json`（自愈） |
| E6 | 端口 / token 复用有没有退化 | `service.ts:386-410`（复用前做端到端 `/health` 校验）、`:476-492`（close 只删仍带自己 token 的文件）、`:448`（`unref` 不吊住 pi）、`index.ts:37-49`（同进程 `globalThis` 句柄） | 历史上「第二个进程抢端口并覆盖 token 文件、先退出者删掉后者的凭据」的 bug **未回归**；端到端校验还排除了「崩溃进程留下的陈旧文件被当成活服务」 | 保持；建议补一条「同 agent 目录并发启动两个 pi」的测试 |
| E7 | 并发 | Node 单线程 + `highlightToRuns` 同步（`hljs.ts:260-273`）；共享可变状态只有 `registered`/`names`/`engine`，且都是幂等写入 | 请求天然串行，无数据竞争；客户端侧有 `BoundedWorkers(2, 64)` 限流（`PiNodeCodeHighlighter.kt:51-52`） | 保持 |
| E8 | 超大代码 / 未闭合围栏 / 超时 | `service.ts:57-61`（256 KB 体）、`:210-217`（64 KB 码 → 413）、`:184-192`（体过大 → 413）；客户端 `PiNodeCodeHighlighter.kt:79-80`（64 KB / 400 行预检）、`PiHighlightClient.kt:281-282`（100 ms 连接 / 150 ms 读） | 不会打爆引擎，也不会让 UI 等；未闭合围栏由 App 侧 200 ms 去抖兜住 | 保持。**残余差异**：pi 有 `trimPartialClosingFences`（`src/components/markdown.ts:146-169`）剪掉流式中的残缺闭合围栏，App 依赖 markdown 解析器 + `retainState`，视觉不闪但非逐位等价 —— 要 1:1 需改 `PiMarkdown.kt`（不在本次范围） |
| E9 | span 越界 / 重叠 | `html-runs.ts:152-231`（`position` 单调、同 scope 栈相邻合并、零长度不产出）；`PiHighlightClient.kt:250-282`（`0 ≤ start < end ≤ codeUnits`，且 `codeUnits` 必须等于本地长度才接受整份） | 越界被丢弃、长度不符整份丢弃 ⇒ 最坏是**无色**，不会错位；嵌套 scope 靠栈反向取最内层（`PiHighlightScopes.kt:64-69`），不靠重叠 | 保持 |
| E10 | 重复上色 / 多实例 | 扩展只回 runs/classes，从不在 guest 上色（`html-runs.ts` 是 pi `renderHighlightedHtml` 的行走器**去掉取色**）；mermaid 也只回 `cls`（`mermaid.ts`） | 没有双重上色；同进程不会起第二个服务（`index.ts:37-49`），跨进程由一个服务独占 token 文件（E6） | 保持 |

**扩展改动的运行期含义（重要）**：`app/src/main/assets/pi-extensions/pi-highlight/**` 是**随包资产**，由 pi 在引擎内以扩展形式加载（本仓库不编译它）。本次改了扩展（新增 `/mermaid`、`hljs.ts` 的大小写归一、`SERVICE_VERSION` 1→2），因此：

- **必须让引擎重新加载扩展**（重启引擎，或对引擎执行 `/reload`）才生效。在此之前旧进程只服务 `/highlight`，App 请求 `/mermaid` 会拿到 404 → 客户端按「无 art」回退成直接显示围栏源码，**不报错、不白屏**；
- `reuseExisting` 只校验 `service` 名与 `/health`、**不校验版本**，所以「装了新资产但没重载」是一个可预期的中间态，不是故障；
- 协议是**加法**的：`/highlight` 的请求/响应、鉴权顺序、错误信封、降级行为一个字节未动；`/mermaid` 复用同一 token、同一端口、同一 `readBody` 上限与同一把前置鉴权。

---

## 不确定清单（附“怎么证实”）

1. ~~**pi-tui 0.85.1 的 `MarkdownCodeTopBar` 具体取哪个色**~~ —— **已查实（本轮）**：`javap -c` 反编译 `multiplatform-markdown-renderer-android-0.45.0/classes.jar` 的 `com/mikepenz/markdown/compose/elements/MarkdownCodeTopBarKt`，其取色指令是 `getLocalMarkdownColors()` → `MarkdownColors.getText-0d7_KjU`，即 top bar 的语言标签**读 `MarkdownColors.text`**。这正是 D7 无法靠配置修、必须自画 header 的原因（改 `MarkdownColors.text` 会连带改正文色）。
2. ~~**App 的 Kotlin 别名表与 `aliases.ts` 是否有冲突键**~~ —— **已机械比对（本轮）**，结论见上「别名表机械比对」：共享的 58 键零分歧；Kotlin 表缺 `cmake`、多 16 键（已通过拆表消除）；服务侧大小写不等价（已修）。
3. **pi 的 `highlightAuto` 分支是否被任何生产路径走到**——`syntax-highlight.ts:206` 有 `hljs.highlightAuto`，但全仓库对 `highlight()` 的调用只有 `theme.ts:1093/1201`，两处都**必传** `language`（经过 `supportsLanguage` 校验）。
   → 证实：`grep -rn "highlight(" ` 排除这两处与定义处后若为空，则该分支是库的通用性，不是 pi 的行为；结论"pi 不做自动猜测"成立。
4. **App 的 markdown 解析是否每 token 重解析整篇**（间接影响流式卡顿）——`com.mikepenz` 库内部用没用 `produceState(content)` 缓存，本次未读库源码；App 侧只确定 `piMarkdownSource`（`PiMarkdown.kt:81`）与五个配置对象（`:93-103`）是 `remember` 的，`retainState=true`/关动画（`:184-185`）已在用。
   → 证实：读 0.45.0 的 `compose/Markdown.kt`（`retainState`/`MarkdownState` 的 produceState 键），或用 `Layout Inspector`/`Trace` 在真机流式时看解析线程占用。
5. ~~**`mermaidRenderingMode` 的默认值**~~ —— 本轮**部分查实**：D5 已按 pi 的默认路径实现（渲染 art），但 pi 的 `off` 开关与「thinking 块内不渲染」两个条件未在本 App 复现（`rememberPiMermaidArt` 的 KDoc 已写清理由）。要完全 1:1 需要把「设置 + 消息类型」透传到 markdown 渲染点，那要动 `PiMarkdown.kt`（不在本次范围）。
6. **`renderers/read.ts`/`write.ts` 之外的其它渲染器是否也有高亮**——本次只逐个核对了 `read/write/edit/bash/grep/find/ls/write` 的 import 与 `theme.fg` 行；`grep/find/ls` 未见 `highlightCode`。
   → 证实：对 `dist/core/tools/renderers/*.js.map` 全量 grep `highlightCode`（本次已做，命中 `read`/`write`）。
7. **引用块的 `mdQuoteBorder` 是否真能被单独绘制**——App 用一个色画条+文字，断言"内置两主题同值 ⇒ 等价"来自 dark.json/light.json 实测（`PiPalette.kt:198-199,261-262`）；但库有没有别的槽位能用 `mdQuoteBorder`，未核。
   → 证实：读 0.45.0 `elements/MarkdownBlockQuote.kt` 的取色。

---

### 审计过程附注（可复现）

- pi 侧源码复原命令（只读，不落盘）：对 `dist/**/*.js.map` 逐个 `JSON.parse(...).sourcesContent[i]`。本报告涉及的关键 map：`utils/syntax-highlight`、`modes/interactive/theme/theme`、`modes/interactive/theme/theme-json`、`modes/interactive/components/{diff,mermaid,bash-execution}`、`core/tools/{render-utils,bash-executor}`、`core/tools/renderers/{read,write,edit,bash}`、`core/export-html/{ansi-to-html,index,tool-renderer}`、`core/agent-session`、`@earendil-works/pi-tui/dist/components/markdown`、`marked/lib/marked.esm.js`（内联 tokenizer）。
- 版本事实：pi `0.85.1`；`highlight.js` 实测 `10.7.3`（`pi/node_modules/highlight.js/package.json`）；`marked` 实测 `18.0.5`。
- App 侧关键计数：`grep -rho "palette\.[a-zA-Z]*"` 直方图（用于确认 `scrollbarTrack/Thumb`、`mdLinkUrl`、`mdQuoteBorder` 零消费）；`grep -rn "Ansi\." --include=*.kt . | grep -v test | grep -v build` 仅命中 `PiListOutput.kt:41`。
