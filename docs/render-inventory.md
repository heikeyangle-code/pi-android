# 渲染能力盘点（只读）

本文只做一件事：把 `app/src/main/kotlin/app/pi/ui/render/**` 今天**实际**能做与不能做的渲染能力逐项列清，每条给 `文件:行` 证据，并对照钉住的引擎 pi 1.0.1。**没有改动任何已有文件。**

## 0. 快照、证据记号与方法

- 仓库：`/root/repos/pi-android`，分支 `main`，**HEAD = `dee4c46`**（`fix(workspace): …`，2026-10-03 17:49 UTC）。
- 本盘点写入时间：**2026-10-03 约 18:24 UTC**。
- **并发改动声明（重要）**：另两个代理正在改 `ui/render/**`、`ui/screens/**`、`bridge/**`、`docs/known-gaps.md`。我读盘期间，render 目录里已有 **2 个**文件被改：
  1. `app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt` —— 工作树版（810 行）已给 `piMarkdownComponents()` 加了 `table = { model -> PiTable(model) }`（工作树 `:148`、`:412`），`PiTable` 用 `maxLines = Int.MAX_VALUE` 换掉库给单元格写死的 1（§2.14）。HEAD 版 801 行、无此覆盖，`md5 1763f95119d6929c6152cb61c31165bd`。
  2. `app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt` —— 工作树版把 `http(s)` 图片的 KDoc 从「有意拒绝、不出网」改成了「**现在会真的去取**，由改过的 `bridge/GuestImageBytes.kt` 提供无头/8 MiB/有超时/有磁盘缓存的契约」，见工作树 `PiMarkdown.kt:212-221`。这会给渲染层引入**第一次出网**，且**偏离 pi**（pi 从不取图）。
  - **本文所有仓库行号一律以 HEAD `dee4c46` 为准**（`git show HEAD:<path>`）；上面两处在对应小节另注工作树状态。其余 7 个 render 文件在读写期间与 HEAD 逐字节相同（HEAD 哈希：`PiCodeHighlight 16f5c5f4`、`PiLatex dd5efbe6`、`PiMarkdown 604de68e`、`PiMarkdownImmediate 47958261`、`PiMarkdownTheme 6c845101`、`PiMermaid 4c744e52`、`RowHeightCache 88f66495`、`TranscriptRowHeight ec7fb24c`）。
- 本机没有 Kotlin 编译器、没有 Gradle 缓存，也**没有跑任何构建**。库内部实现按用户给的 Maven sources jar 拉到 `/tmp` 读的（只放 `/tmp`，未进仓库）。证据记号：
  - `[LIB]` = `/tmp/mdlib/core/commonMain/com/mikepenz/markdown`（`com.mikepenz:multiplatform-markdown-renderer:0.45.0` 的 sources jar 解包）
  - `[M3]` = `/tmp/mdlib/m3/commonMain/com/mikepenz/markdown/m3`（`multiplatform-markdown-renderer-m3:0.45.0`）
  - `[JBMD]` = `/tmp/mdlib/jbmd`（`org.jetbrains:markdown:0.7.9` sources，由 m3 renderer 的 POM 钉死）
  - `[PI]` = `/root/pi-1.0.1/audit/node_modules/@earendil-works`（pi 1.0.1 产物；LaTeX 在 `pi-tui/dist/latex.js`，markdown 在 `pi-tui/dist/components/markdown.js`）
- **我实际执行过的验证**：① 用 Node 直接 `import` 了 `[PI]/pi-tui/dist/latex.js` 的 `renderLatex`，对 50+ 公式取 pi 的真实输出；② 逐字复制 `[PI] markdown.js` 的 `tokenizeInlineLatex` / `tokenizeBlockLatex` 到 `/tmp` 验证 `$`/`$$`/`\(`/`\[` 的边界；③ 用脚本把 `PiLatex.kt` 的 13 张表/集合与 `latex.js` 的对应表逐 key 比对（结论：**逐项相同**，见 §4.2）。这些脚本只落在 `/tmp`。
- 约定：`### <能力名>` 下固定三字段。「状态」取 `done / 有意不同（代价）/ 缺 / 坏（症状）`。

---

## 1. 入口与数据流（先交代清楚，后面各节引用）

### 1.1 markdown 的唯一入口 `PiMarkdownText`
- **今天的行为**：全 App 的 markdown 都走 `PiMarkdownText`；它先做公式预处理，再交给库的 `Markdown(markdownState = …)` 画成 Compose 树。证据：`app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt:82-100`（入口与 `piMarkdownSource`）、`:175-183`（`rememberMarkdownState`）、`:228-300`（库调用）。
- **状态**：done。
- **要补的话**：无。调用方在 `ui/blocks/**`（`AssistantTextBlock`、`UserMessageBlock`、`CompactionBlock`、`BranchSummaryBlock`、`HookMessageBlock`、`SkillInvocationBlock` 等），本次不改。

### 1.2 库的渲染骨架：一个 `Column`，没有虚拟化
- **今天的行为**：解析成功后库用 `Column { state.node.children.forEach { MarkdownElement(it, …) } }` 一次组合整篇文档，**没有**增量/AST 复用。证据：`[LIB]/compose/Markdown.kt:393-404`（`MarkdownSuccess`）。库另有一个 `StreamingMarkdownState`（`[LIB]/model/StreamingMarkdownState.kt`）本 App **未采用**，理由写在 `HEAD PiMarkdownComponents.kt:176-181`。
- **状态**：有意不同（代价：一篇很长的 markdown 或一个超长代码围栏，全部节点都会组合；App 用 `LazyColumn` 按“行”虚拟化，但**行内**不虚拟化）。
- **要补的话**：无（采纳 `StreamingMarkdownState` 需要先给 reducer 加 per-token delta 通道，属于架构改动）。

### 1.3 组件集 `piMarkdownComponents()`（HEAD）
- **今天的行为**：只覆盖 5 个槽 —— `codeFence`、`codeBlock`、`image`、`inlineImage`、`custom`；其余全部用库默认组件。证据：HEAD `app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt:135-141`；库默认表 `[LIB]/compose/components/MarkdownComponents.kt:160-227`。
- **状态**：done。
- **要补的话**：工作树已给第 6 个槽 `table` 加覆盖（工作树 `PiMarkdownComponents.kt:148`，见 §2.14）。

### 1.4 主题与排版对象
- **今天的行为**：pi 的 markdown 十色令牌映射到库的 5 个颜色槽 + 排版；code/inline-code 背景透明，quote=mdQuote+斜体，链接=mdLink+下划线，table 用等宽 12.5sp。证据：`app/src/main/kotlin/app/pi/ui/render/PiMarkdownTheme.kt:176-196`（颜色）、`:258-304`（排版）、`:322-347`（padding）、`:367-389`（dimens）。
- **状态**：done（`mdLinkUrl` 有意不画，理由 `PiMarkdownTheme.kt:116-126`；pi 在可超链接分支同样只印链接文字，`[PI]/pi-tui/dist/components/markdown.js:539-560`）。
- **要补的话**：无。

### 1.5 公式预处理 `piMarkdownSource`
- **今天的行为**：解析**之前**用正则把 `$…$` / `$$…$$` 归约成 Unicode 写回源码；跳过围栏与行内代码；归约失败就原样保留。证据：`PiMarkdown.kt:337-372`、`:375-401`、`:414-457`。这样做的原因（库 annotator 会把 math 节点原文追加进段落）也写在 `PiMarkdown.kt:48-59`，并在库侧得证：`[LIB]/annotator/AnnotatedStringKtx.kt:315-320`。
- **状态**：done（但带风险，见 §4.5）。
- **要补的话**：无（本条本身不是缺陷）。

---

## 2. GFM 元素逐项

### 2.1 标题（ATX `#` 与 Setext `===`/`---`）
- **今天的行为**：库把 ATX_1..6 / SETEXT_1..2 都交给 `MarkdownHeader`，只画标题文字；字号 22/20/18/16/15/14sp，h1 加下划线，全部 SemiBold + mdHeading 色。证据：`[LIB]/compose/MarkdownExtension.kt:76-83`、`[LIB]/compose/elements/MarkdownHeader.kt:14-27`；字号在 `PiMarkdownTheme.kt:270-279`。pi 的 h1=heading+bold+underline，h2+=heading+bold，**h3 及以上会把 `### ` 前缀也画出来**（`[PI]/pi-tui/dist/components/markdown.js:342-367`）。
- **状态**：有意不同（代价：h3–h6 不出现 `#` 前缀；字号层级是 App 加的，已在 `PiMarkdownTheme.kt:236-241` 说明，因为终端只有一个字号）。
- **要补的话**：若要对齐 pi 的短前缀，改 `PiMarkdownTypography` 不够（前缀是内容）；需要覆盖 `heading1..6` 槽，约 20–30 行，风险低（不动架构），性能无额外代价。

### 2.2 段落
- **今天的行为**：库默认 `MarkdownParagraph` + 每块前置 `Spacer(block)`，`block = 2.dp`。证据：`[LIB]/compose/MarkdownExtension.kt:70`、`:85`；`[LIB]/compose/elements/MarkdownParagraph.kt`；`PiMarkdownTheme.kt:336-347`。软换行（段内 EOL）在 annotator 里变成**空格**（`[LIB]/annotator/AnnotatedStringKtx.kt:357`），硬换行（行尾两空格/`\`）变成 `\n`（`:346-349`）。
- **状态**：done（与 pi 的段落内换行语义一致：pi 的 `text` 行软换行也折成一行，`[PI]/…/markdown.js:367-376`）。
- **要补的话**：无。

### 2.3 粗体 / 斜体
- **今天的行为**：annotator 对 `STRONG` push `FontWeight.Bold`，对 `EMPH` push `FontStyle.Italic`。证据：`[LIB]/annotator/AnnotatedStringKtx.kt:284-294`。
- **状态**：done。
- **要补的话**：无。

### 2.4 删除线 `~~x~~`
- **今天的行为**：GFM `STRIKETHROUGH` → `TextDecoration.LineThrough`。证据：`[LIB]/annotator/AnnotatedStringKtx.kt:296-300`。pi 也支持（StrictStrikethroughTokenizer，`[PI]/…/markdown.js:6-16`、`:565-569`）。
- **状态**：done。
- **要补的话**：无。

### 2.5 有序 / 无序列表
- **今天的行为**：无序项目符号是库默认的 `"• "`，有序是 `"${listNumber + index}. "`，项目文字用 body 色/有序同色、符号用 mdListBullet。证据：`[LIB]/compose/ComposeLocal.kt:35-43`、`[LIB]/compose/elements/MarkdownList.kt:196-241`；颜色 `PiMarkdownTheme.kt:297-299`。有序列表起始号从第一项的 `LIST_NUMBER` 数字解析（`MarkdownList.kt:59-65`），符合 CommonMark 的 start number。
- **状态**：有意不同（代价：pi 的无序符号默认是 `- `，见 `[PI]/…/markdown.js:611-615`；用户消息里 pi 用 `preserveOrderedListMarkers` 保留原符号，`[PI]/pi-coding-agent/dist/modes/interactive/components/user-message.js:31-36`，App 一律用 `•`/`N.`）。
- **要补的话**：若要贴 pi，给 `bullet` 槽传一个 `BulletHandler`（一行 `markdownComponents(bullet = …)` 不够——库的 handler 在 `LocalBulletListHandler`，需要在 `PiMarkdownText` 里 `CompositionLocalProvider`，约 10–15 行），风险低，性能零。

### 2.6 嵌套列表
- **今天的行为**：库按 `extra["markdown_list_depth"]` 递归，`Column` 左缩进 `listIndent(12dp) * depth`，并在 `MarkdownListItem` 里对 `ORDERED_LIST/UNORDERED_LIST` 子节点再次派发。证据：`[LIB]/compose/elements/MarkdownList.kt:52-57`、`:155-193`；`PiMarkdownTheme.kt:341`。
- **状态**：done（缩进台阶是 12dp，pi 是每层 4 个字符，量纲不同但行为对）。
- **要补的话**：无。

### 2.7 引用 `> `
- **今天的行为**：库 `MarkdownBlockQuote` 画一条 3dp 的竖条（颜色取 style.color，即 mdQuote），内容左/右留 16dp，支持嵌套；文字是 mdQuote + **斜体**。证据：`[LIB]/compose/elements/MarkdownBlockQuote.kt:43-104`；`PiMarkdownTheme.kt:295`、`:343`、`:382`。pi 是每行加 `│ ` 前缀 + `quote+italic`（`[PI]/…/markdown.js:426-466`）。
- **状态**：有意不同（代价：竖条 vs 行首 `│`；`mdQuote` 与 `mdQuoteBorder` 在 App 里合并为一个颜色，已在 `PiMarkdownTheme.kt:107-111` 说明）。
- **要补的话**：无（要完全一致需自己实现引用槽，收益低）。

### 2.8 分隔线 `---`
- **今天的行为**：库 `MarkdownDivider` 画 1dp 满宽实线，颜色 mdHr。证据：`[LIB]/compose/MarkdownExtension.kt:89`、`[LIB]/compose/elements/MarkdownDivider.kt`；`PiMarkdownTheme.kt:380`、`:190`。pi 画 `─` 重复 `min(width, 80)` 个字符（`[PI]/…/markdown.js:468-471`）。
- **状态**：有意不同（代价：一条实线 vs 一行制表符；信息等价）。
- **要补的话**：无。

### 2.9 行内代码 `` `x` ``
- **今天的行为**：annotator 对 `CODE_SPAN` push `codeSpanStyle`，并在内容**两侧各补一个空格**；`codeSpanStyle` = `inlineCode` 排版 + `inlineCodeBackground`。App 的 `inlineCode` 是 mono 12.5sp/18sp、色 mdCode，背景透明。证据：`[LIB]/annotator/AnnotatedStringKtx.kt:302-308`、`[LIB]/utils/Extensions.kt:203-208`；`PiMarkdownTheme.kt:285`、`:189`。pi 是 `theme.code(text)`，不补空格、无背景（`[PI]/…/markdown.js:536-538`、`theme.ts` 的 code 定义）。
- **状态**：有意不同（代价：行内代码左右各多一个空格，来自库；视觉上是可接受的排版差）。
- **要补的话**：无（覆盖 `codeSpanStyle` 不能去掉空格，空格是 annotator 的硬编码；要改需接管 annotator，收益低）。

### 2.10 代码围栏：语言标签
- **今天的行为**：库从 `FENCE_LANG` 取语言（`[LIB]/compose/elements/MarkdownCode.kt:80`），去掉围栏行与 `replaceIndent()`（`:81-88`）；App 关掉库的表头（`HEAD PiMarkdownComponents.kt:440`，`showHeader = false`），自己画一行语言标签 + 「复制」，标签用 monoSmall + mdCodeBlockBorder。证据：`PiMarkdownComponents.kt:445`、`:478-512`。pi 打印的是**围栏行本身** `` ```lang ``，颜色 mdCodeBlockBorder（`[PI]/…/markdown.js:392-412`）。
- **状态**：done（有意不同：不画三反引号，画一个标签行，`PiMarkdownComponents.kt:469-477` 有说明）。
- **要补的话**：无。

### 2.11 代码围栏：横向滚动
- **今天的行为**：有。App 在正文上 `horizontalScroll(rememberScrollState())`（`HEAD PiMarkdownComponents.kt:452`），mermaid 分支同样（`:422`）；库自己的 `MarkdownCode` 也有一层（`[LIB]/compose/elements/MarkdownCode.kt:62`）。**不会自动折行、不省略号**，长行靠横滑。
- **状态**：done。
- **要补的话**：无（注意 `rememberScrollState()` 每次重组新建，滚动位置不跨重组保持；对短命流式行影响小）。

### 2.12 代码围栏：高亮与上限（确切数字）
- **今天的行为**：语言已知 → 走引擎高亮，未被 highlight.js 包住的字符用 `palette.text`；语言未知或引擎不可达 → 整块 `mdCodeBlock`。证据：`HEAD PiMarkdownComponents.kt:446-454`、`app/src/main/kotlin/app/pi/ui/render/PiCodeHighlight.kt:150-153`、`app/src/main/kotlin/app/pi/highlight/PiNodeCodeHighlighter.kt:126-140`。**确切边界**：
  - `code.length > 64 * 1024` 或 `换行数 > 400` → **不发请求**，整块按“未知语言”画：`PiNodeCodeHighlighter.kt:81-82`、判断 `:139`。
  - 语言名归一化 = 整条 info string `trim().lowercase()`（不做“取第一个词/别名/`language-` 前缀”）：`PiCodeHighlight.kt:238-242`；`null` 才表示“没写语言”：`:275`。
  - 并发上限：2 个工作线程、FIFO 队列 64，超出**直接丢弃并画成未上色**：`PiNodeCodeHighlighter.kt:53-54`、`:145`、`:209-230`。
  - 调用方等待上限 750ms（含排队）：`:63`；单次请求 100ms 连接 + 150ms 读：`app/src/main/kotlin/app/pi/highlight/PiHighlightClient.kt:403-404`。
  - 结果缓存 128 条（LRU，key=SHA-256(language,code)），span 数 > 2048 不进缓存：`:72-73`、`:150-152`。
  - 流式围栏先 debounce **200ms**，首版不等待：`HEAD PiMarkdownComponents.kt:555-578`、`:776`。
  - 任何失败都返回空 span + `languageKnown=false`，从不抛：`:126-134`。
- **状态**：有意不同（代价：pi 没有上限——终端同步高亮。>400 行 / >64 KiB 的块在 App 里**静默不上色**，界面上没有任何说明；这是唯一“用户看不出发生了什么”的取舍）。
- **要补的话**：可加一条“过大未高亮”的页脚提示（改 `PiCodeSurface`，约 10 行；风险低；性能零）。是否偏离 pi：是（pi 无上限），属于 App 已定的取舍，应登记。

### 2.13 链接：可点、长 URL、引用式、自动链接
- **今天的行为**：
  - **可点**：库 `annotatorSettings()` 的默认 `linkInteractionListener` 调 `LocalUriHandler.openUri`（`[LIB]/annotator/AnnotatorSettings.kt:46-64`），App 不覆盖它；链接样式 = mdLink + 下划线（`PiMarkdownTheme.kt:300-302`）。
  - **长 URL**：渲染时只画链接文字，**不附加 URL**（App 属 pi 的“终端能带超链接”分支，`PiMarkdownTheme.kt:116-126`；pi 同分支见 `[PI]/…/markdown.js:539-560`）。
  - **引用式** `[x][id]` / `[id]: url`：由 `ReferenceLinkHandler` 解析，App 每个 markdown 行一个独立 handler，避免跨消息串味。证据：`[LIB]/annotator/AnnotatedStringKtx.kt:160-193`；`PiMarkdown.kt:152-160`。
  - **自动链接** `<https://…>`、GFM 裸 URL、邮箱 → `mailto:`：`[LIB]/annotator/AnnotatedStringKtx.kt:202-233`、`:310-330`。
  - **图片链接**：特殊，见 §2.15/§2.16。
- **状态**：done。
- **要补的话**：无。唯一未确认：`LocalUriHandler` 是 Activity 的，`http(s)` 会被系统浏览器打开——本机无设备，未实测。

### 2.14 表格
- **今天的行为（HEAD `dee4c46`）**：库默认表格。列宽 = 列数 × `tableCellWidth(160.dp)`、**等宽**（不是按内容）；容器比表窄时 `horizontalScroll + requiredWidth(tableWidth)`，否则 `fillMaxWidth` 把列拉宽；**每个单元格 `maxLines = 1` + `Ellipsis`**，所以长单元格被截断成一行加省略号；表头不是 sticky。证据：`[LIB]/compose/elements/MarkdownTable.kt:86-88`（列数/宽度）、`:91-104`（`scrollable = maxWidth <= tableWidth`，`:99`；`horizontalScroll`，`:100-103`）、`:124-162`（表头，`maxLines=1` 在 `:130`）、`:164-203`（数据行，`maxLines=1` 在 `:172`）、`:217-267`（单元格，`maxLines=1` 在 `:222`）；槽默认 `[LIB]/compose/components/MarkdownComponents.kt:216-217`；App HEAD 不覆盖 `table` 槽（HEAD `PiMarkdownComponents.kt:135-141`）。库没有 `maxLines` 参数可传（`MarkdownTable` 签名 `:66-81`）。
  - **工作树状态（并发修复，落盘中）**：已经加好 `table = { model -> PiTable(model) }`（工作树 `PiMarkdownComponents.kt:148`，新增 import `:54-56`），`PiTable`（`:412-429`）用 `maxLines = TABLE_MAX_LINES = Int.MAX_VALUE`（`:439`）分别传给 `MarkdownTableHeader`/`MarkdownTableRow`；不动横向滚动、不动 `tableCellWidth`、不加内部纵向滚动（KDoc `:370-410`）。**HEAD 上尚未提交**，按 HEAD 记仍是“每格 1 行”。
- **状态**：**坏**（症状：多行单元格 / 长解释被压成一行加省略号；这是表格今天真正的缺陷）。宽表格不是问题——横向滚动库已经有了。
- **要补的话**：另一个代理正在做的方向正确：覆盖 `table` 槽、用 `MarkdownTableHeader`/`MarkdownTableRow` 传更大的 `maxLines`（甚至 `Int.MAX_VALUE`）。风险低（不动架构，只是槽覆盖）；性能代价：单元格从 1 行变多行会增加表高与 `Column` 的测量量，但表本来就是一次组合，无新出网/新后台任务。
  - **用户裁定（直接照写，不作为建议）**：
    1. **表头吸顶：用户已否决**，原话「这个表头吸顶。没感觉有啥问题，如果很麻烦就不要做了」→ 明确**不做**。
    2. **表格现在真正的问题是「每个单元格只显示 1 行」**，根因即上面 `[LIB] MarkdownTable.kt:130/172/222` 的 `maxLines = 1` + `Ellipsis`，而 `MarkdownTable` 本身没有 `maxLines` 参数、库默认 `table` 槽不传、App 的 `piMarkdownComponents()` 也从未覆盖 `table` 槽。
- **与 pi 的差异（供登记）**：pi 的表格是**内容自适应列宽 + 单元格按列宽折行成多行**的方框网格，宽度不够时按最小词宽收缩、实在放不下就退化成原始 markdown（`[PI]/…/markdown.js:673-818`，尤其 `:687-691`、`:760-800`）。App 是固定 160dp 等宽 + 横滑 + 单行省略。这是库的设计，不是一处能靠参数补齐的差异。

### 2.15 图片（块级 `![](…)` 自成一段）
- **今天的行为**：`PiImagePlaceholder` 先问 `transformer.transform(link)`；拿到字节才转交库的 `MarkdownImage`，否则画 alt + 来源的文本兜底。证据：HEAD `PiMarkdownComponents.kt:251-279`、`:338-360`；库 `MarkdownImage` 在 `transform == null` 时整节点消失（`[LIB]/compose/elements/MarkdownImage.kt:12-29`）。**pi 的终端根本没有图片分支**，`![]()` 落到 default 分支印 alt（`[PI]/…/markdown.js:619-627`）。
  - **工作树状态（并发改动）**：`PiMarkdown.kt:212-221` 的 KDoc 已改成 `http(s)` **会真的去取**（`bridge/GuestImageBytes.kt` 改成「无头请求 / 8 MiB / 有界超时 / 有界磁盘缓存」）。这是渲染层第一次出网；取不到（离线、超时、超限、非 2xx）仍回落到 alt + 来源。
- **状态**：HEAD：有意不同（App 比 pi 强，且 `http(s)` 不出网；A3 已登记）。**工作树：偏离 pi 更进一步**——pi 从不取图，现在 App 会为了 markdown 里的一条 URL 发网络请求，KDoc 说清了代价。
- **要补的话**：无（若保留这次改动，按仓库规矩**登记**：这是“偏离 pi + 渲染层新增出网”的一笔交易；`docs/known-gaps.md` A3 的“http(s) 被有意拒绝”那句需要同步改，否则文档与代码会互相打脸）。

### 2.16 图片（行内 `text ![]() text`）
- **今天的行为**：库把段落里的图片经 `inlineImage` 槽而不是 `image`（`[LIB]/compose/elements/MarkdownText.kt:384-386`）；App 的 `PiInlineImage` 同样先 transform，失败时画「图片 + 图片地址…」。证据：HEAD `PiMarkdownComponents.kt:314-324`、`:338-360`。
- **状态**：有意不同（代价：行内槽里 `content` 是**解析后的链接**、`node` 是外层文本节点，**alt 文本到不了这个槽**——annotator 只 append URL（`[LIB]/annotator/AnnotatedStringKtx.kt:280-282`），所以解析失败的行内图只显示「图片 + 来源」，不是 pi 那行 alt。A3 已记录。工作树里 `http(s)` 的加载行为同 §2.15）。
- **要补的话**：拿回 alt 需要连 annotator 一起接管并把 alt+link 编进同一个 inline tag；收益低，风险中（接管 annotator），性能零。

---

## 3. 任务列表 `- [x]`

- **今天的行为**：**画成等宽的 `[x] ` / `[ ] ` 文本**，不是勾选控件；并且这个 `[x]` 槽**取代了项目符号**，所以一条任务项**没有** `- ` 前缀。证据：库 `MarkdownCheckBox` 默认渲染 `"[${if (checked) "x" else " "}] "` 且 `fontFamily = Monospace`（`[LIB]/compose/elements/MarkdownCheckBox.kt:14-31`）；列表项在 `checkboxNode != null` 时走 `components.checkbox` 而不是 bullet（`[LIB]/compose/elements/MarkdownList.kt:108`、`:124-134`）；App HEAD **没有**覆盖 `checkbox` 槽（HEAD `PiMarkdownComponents.kt:135-141`），所以拿到的是库默认。pi 是 `- [x] text`，即**项目符号后**再拼 task marker（`[PI]/…/markdown.js:613-615`）。
- **状态**：有意不同（代价：与 pi 差一个 `- ` 前缀；把 repo 的裁决 `design/ui-refactor/01-design-spec.md:58`「这个 App 没有 todo / 待办 / plan / 子代理块，也**没有复选方框组件**——不要把 `- [x]` 画出来」读成“不能画复选控件”的话，今天的行为**满足**（没有控件）；但它确实把 `[x]` 当文本画出来了。`design/ui-refactor/02-real-content.md:48-49` 说的是设计稿层面没有该块类型/组件，与 markdown 渲染器默认行为是两件事——这份文档之前把两者混为一谈的风险要指出）。
- **要补的话**：如果要严格对齐 `01-design-spec.md:58` 的字面（不出现 `[x]`），最小改法是覆盖 `checkbox` 槽画一个项目符号（`•`）而不是 `[x]`，约 8–12 行，风险低，性能零。**但这是产品口径问题**，不建议在没得到明确裁决前动它；若动，属于有意偏离 pi（pi 显示 `[x]`）。

---

## 4. 数学

### 4.1 四种定界符
- **今天的行为**：只处理 `$…$` 与 `$$…$$`。`piMarkdownSource` 第一行就是 `if (!markdown.contains('$')) return markdown`（`PiMarkdown.kt:340`），所以 `\(…\)` / `\[…\]` 原样透传；intellij 的 GFM `MathParser` 也只认 `DOLLAR`（`[JBMD]/parser/sequentialparsers/impl/MathParser.kt:18,35`），不会生成 math 节点。**pi 四种都认**：`\(`/`\[`（inline）与 `\[`/`$$`（block）在 `[PI]/pi-tui/dist/components/markdown.js:56-58`、`:106-112`、`:123-172`。
- **状态**：缺（`\(…\)`、`\[…\]` 完全走不到公式，显示为字面文本）。
- **要补的话**：在 `piMarkdownSource` 加两对定界符的扫描（`PiMarkdown.kt`，约 40–60 行；需要像现在一样跳过围栏/行内代码，并注意 `\(` 的反斜杠本身要防止被 markdown 当转义）。风险低（不动架构），性能：只在含 `\` 的串上多一趟扫描，可忽略。**不偏离 pi**（这是补齐）。

### 4.2 `PiLatex.kt` 与 pi `latex.js` 的表/分支逐项对照（脚本比对结论）
- **13 张表/集合**：`SYMBOLS` 224/224、`NEGATED_SYMBOLS` 30/30、`BLACKBOARD` 7/7、`SUPERSCRIPTS` 40/40、`SUBSCRIPTS` 32/32、`ACCENTS` 18/18、`NAMED_OPERATORS` 32/32、`DISPLAY_LIMIT_SYMBOLS` 16/16、`SPACING_COMMANDS` 12/12、`NEGATIVE_SPACING_COMMANDS` 4/4、`IGNORED_COMMANDS` 6/6、`SIZE_COMMANDS` 12/12、`PLAIN_WRAPPERS` 30/30 —— **逐 key 一致，无缺项**。证据：`PiLatex.kt:102-602` vs `[PI]/pi-tui/dist/latex.js:1-530`（比对脚本在 `/tmp`，未入库）。
- **parseCommand 分支**：端口覆盖了 pi 的绝大部分分支，但有 **5 类 pi 有、端口没有**：

| pi 分支 | pi 证据 | 端口 | 后果 |
|---|---|---|---|
| `FONT_SWITCH_COMMANDS`：`\bf \cal \it \rm \sf \sl \tt` | `latex.js:516`、`:967-971` | 无此集合 | 端口把 `\rm` 当未知命令 → 整条公式返回 `null` → 原样显示 `\rm x`（pi 显示 `x`） |
| `LIMIT_OPERATORS` 独有项：`\argmax \argmin \injlim \projlim` | `latex.js:262-291`、`:1001-1003` | `NAMED_OPERATORS` 32 项与 pi 相同，但**不含这 4 个** | 未知命令 → 整条公式原样显示（pi 显示 `argmax[x]`） |
| `parseOperator` 的 inline bracket 风格 | `latex.js:1105-1158` | `NAMED_OPERATORS` 只返回裸名字（`PiLatex.kt:856-861`） | `\lim_{x\to0}`：pi=`lim[x→0]`，端口=`lim_(x→0)`；`\max_i`：pi=`max[i]`，端口=`maxᵢ` |
| `RELATION_COMMANDS` 的符号内补空格 | `latex.js:293-…`、`:1009` | 只给 `=`/`<`/`>` 字面量和 `cdot`/`times` 补空格（`PiLatex.kt:785-791`、`:847-855`） | `a\le b`：pi=`a ≤ b`，端口=`a≤ b` |
| `parseRequiredArgument`/`parseOptionalArgument` **跳过前导空白** | `latex.js:1160-1163`、`:1188-1191` | 端口不跳（`PiLatex.kt:956-967`、`:970-974`） | `\frac {a}{b}`：pi=`a/b`，端口=`()/ab`；`\sqrt [3]{x}` 同理 |

- 另有一处**主动不移植**（非 bug，见 §4.3）：`\begin{…}` 一律返回 `null`（`PiLatex.kt:937-949`），而 pi 会画。
- **状态**：缺（上表 5 类）+ 有意不同（layout 那一支）。
- **要补的话**：上表 5 类都在 `PiLatex.kt` 单文件内，总计约 60–100 行；风险低（纯文本层，不碰渲染架构）；性能：无（同一趟解析内）。**不偏离 pi**。

### 4.3 `renderLayout` 那一支今天各自显示成什么
pi 的 `renderLayout`（`[PI]/latex.js:681-809`）把 layout 节点拼成字符网格；端口完全没有这一支（`PiLatex.kt:28-60` 的理由：`piMarkdownSource` 把结果写回 markdown 源，而库 annotator 把段内换行变空格，`[LIB]/annotator/AnnotatedStringKtx.kt:357`，网格会被压回一行）。逐项实际输出（pi 输出为我在本机跑 `renderLatex` 的真实值）：

| 构造 | pi 行内 `$…$` | pi 块级 `$$…$$` | 本 App 今天 |
|---|---|---|---|
| `\frac{a}{b}` | `a/b` | `a`↵`─`↵`b`（堆叠） | `a/b`（= pi 行内；块级偏离） |
| `\sum_{i=1}^{n}` | `∑ᵢ₌₁ⁿ` | ` n`↵` ∑`↵`i=1`（上下限） | `∑ᵢ₌₁ⁿ`（= pi 行内；块级偏离） |
| `\begin{pmatrix}a & b \\ c & d\end{pmatrix}` | `⎛ a │ b ⎞`↵`⎝ c │ d ⎠` | 同左 | **`null` → 原样显示 `\begin{pmatrix}…\end{pmatrix}`** |
| `\begin{cases}a & x>0 \\ b & x<0\end{cases}` | `⎧ a if x > 0`↵`⎨`↵`⎩ b if x < 0` | 同左 | `null` → 原文 |
| `\begin{aligned}a &= b \\ c &= d\end{aligned}` | `a = b`↵`c = d` | 同左 | `null` → 原文 |
| `\begin{gather}a \\ b\end{gather}` | `a`↵`b` | 同左 | `null` → 原文 |
| `\begin{split}…\end{split}` | 单行 | 同左 | `null` → 原文 |
| `array/matrix/smallmatrix/pmatrix/bmatrix/Bmatrix/vmatrix/Vmatrix`（8 种） | 带/不带定界符的网格 | 同左 | 全部 `null` → 原文 |
| `\begin{equation}x^2\end{equation}`、`\begin{displaymath}…` | `x²` | `x²` | `null` → 原文 |

- pi 侧证据：环境在 `[PI]/latex.js:1230-1310`（`equation/displaymath` `:1243-1245`；`aligned/align/gather/multline/split…` `:1246-1284`；`cases` `:1286-1299`；8 种网格 `:1301-1355`）；`renderLatex` 只要收过 layout 节点就跑 `renderLayout`（`:1361-1377`）。`\frac` 与算子上限是唯二被 `display` 门控的分支（`latex.js:1024-1031`、`:1145-1148`），**环境分支不受门控**，所以 pi 连行内矩阵也画。
- **状态**：有意不同（代价：矩阵/cases/aligned 全部退化成原文；块级 `\frac`/算子上限退化成行内形式。文件头 `PiLatex.kt:28-93` 有完整说明，`docs/known-gaps.md` A2 已登记）。
- **要补的话**：要真正画出来，需要一个**保住行结构**的通道（块级 math 组件，或把结果写进围栏再渲染），这是渲染架构改动：`PiMarkdownComponents.kt` 的 `custom` 槽 + `PiLatex` 的 `renderLayout` 移植，约 200–300 行 + 一次架构决策；风险**高**（动渲染架构）；性能：网格字符串本身很小，主要是每公式一次布局计算，可缓存。**不偏离 pi**（是补齐 pi 的块级排版），但成本最高，不建议先做。

### 4.4 我读出来的其它差异（供登记）
- **端口 `toUnicode` 永远传 `display=false` 的效果**在块级路径由 `toDisplayUnicode` 只加首尾换行实现（`PiLatex.kt:666-670`），所以块级公式除了“独占一段”以外，排版与行内一致。上面已列。
- **`\cdot`/`\times` 端口补空格**（`PiLatex.kt:853`），pi 同（`latex.js:1009`）——一致。
- **`\operatorname` 的 `*` 与 limits**：pi 在 display 下让 limits 生效（`latex.js:1074-1082`），端口只印名字（`PiLatex.kt:908-913`）。差异同 §4.3（display 排版）。

### 4.5 重写源文本带来的二次解析风险
- **今天的行为**：归约后的 Unicode 会被**写回 markdown 源**再解析。因此公式里若出现 markdown 有意义的字符，就会被 markdown 重新解释。具体地：`` $\text{a*b*c}$ `` 归约成 `a*b*c`，解析后 `*b*` 变斜体；含 `[`/`` ` ``/`|` 的公式同理；**行中的 `$$x$$` 也会被当作块级公式**（端口 `BLOCK_MATH` 正则没有行首锚点，`PiMarkdown.kt:429-432`；pi 的 block tokenizer 要求行首 `^ {0,3}\$\$`，`[PI]/…/markdown.js:106-112`），得到 `\n x \n` 独占一段，而 pi 会按行内 `$$` 处理。
- **状态**：坏（症状少见但存在：公式里的 `*`/`_`/`|` 会改变邻近文字的排版；行中 `$$` 会多出一个空行段落）。
- **要补的话**：把公式结果放进一个不会被 markdown 再解释的通道（围栏/块级组件），或对归约结果做转义——前者同 §4.3 的架构改动，后者约 20 行但要小心别把正常公式字符转义乱。风险中；性能零。这属于 App 的实现方式带来的偏离（pi 在 token 流里替换，不存在二次解析）。

---

## 5. mermaid

- **今天的行为**：` ```mermaid ` 围栏由 guest 里的 `grok-mermaid` 排版成 **ASCII 框线图**，App 按语义类着色后当内联代码画（横向滚动）。证据：`HEAD PiMarkdownComponents.kt:402-425`（`rememberPiMermaidArt` → `Text(piMermaidText(...))`）、`:641-677`、`app/src/main/kotlin/app/pi/ui/render/PiMermaid.kt:10-63`、`:126-142`；guest 侧 `PiNodeMermaidRenderer.kt`。
  - 语言判定用 info string 的**第一个空白分隔词**小写（`HEAD PiMarkdownComponents.kt:645-647`），与高亮器“整串”规则不同（`PiCodeHighlight.kt:238-242`）——这是 pi 自己的差异（```` ```mermaid x ```` 画图，```` ```js x ```` 不高亮），KDoc `:627-630` 已写明。
  - 模式取自 `settings.json` 的 `markdown.mermaid`，默认 `streaming`；`off` 完全不请求：`:686`、`:705-709`、`:719-768`。
  - 有 warnings 且已 settle → **丢掉图，保留源码 + 警告行**（pi 的规则）：`:407-413`、`:458-466`、`PiMermaid.kt:153-156`。
  - 冷启动重试 3 次、间隔 400ms；settle 200ms：`:680-683`、`:776`。
  - pi 用图的宽度决定“太宽就不画”，App 改用横向滚动（**有意偏离**）：`PiMermaid.kt:50-59`。
- **状态**：有意不同（代价：`grok-mermaid` 的 ASCII 艺术在手机上占满宽度且不能缩放；宽度规则与 pi 不同，已写明）。
- **要补的话**：无（要真图需要引入 mermaid 渲染器，是另一个量级的决定，不在本次范围）。

---

## 6. 代码高亮的边界（确切数字汇总）

| 项 | 值 | 证据 |
|---|---|---|
| 单块字符上限 | `64 * 1024` | `app/src/main/kotlin/app/pi/highlight/PiNodeCodeHighlighter.kt:81` |
| 单块行数上限 | `400` | `:82` |
| 上限命中后果 | 不发请求，整块按未上色（`mdCodeBlock`） | `:139`、`HEAD PiMarkdownComponents.kt:449-450` |
| 并发/排队 | 2 个工作线程、队列 64，超出丢弃 | `:53-54`、`:145`、`:209-230` |
| 调用方总等待 | 750ms | `:63` |
| 单请求超时 | 连接 100ms + 读 150ms | `PiHighlightClient.kt:403-404` |
| 响应体上限 | 2 MiB；头 16 KiB | `PiHighlightClient.kt:405-406` |
| 结果缓存 | 128 条 LRU，key=SHA-256(lang,code) | `:72`、`:161-167` |
| 不入缓存的 span 数 | `> 2048` | `:73`、`:150-152` |
| 流式 debounce | 200ms（首版不等） | `HEAD PiMarkdownComponents.kt:776`、`:555-578` |
| 语言名归一 | 整条 info string `trim().lowercase()` | `PiCodeHighlight.kt:238-242` |
| “没写语言” | 仅 `null`（`text`/`plaintext` 是合法语言） | `:275` |
| 文件体语言 | 58 项扩展名表，`path.split(".").pop().lowercase()` | `:189-218`、`:261-264` |

- **状态**：有意不同（pi 没有这些上限；`docs/syntax-highlight-eval.md` §4.3 是取舍来源）。
- **要补的话**：同 §2.12 的提示行；另外“按 `>400 行` 不上色”与“按块输出 400 行”是**两个不同的 400**，别混（后者见 `app/src/main/kotlin/app/pi/ui/blocks/ToolCallBlock.kt:276` 的 200 行）。

---

## 7. 流式 / 半截 markdown

### 7.1 半截文档整体
- **今天的行为**：库的 `rememberMarkdownState` 对**任意** partial 文档都解析；`retainState = true` 让内容变化时**继续显示上一版**而不是空 Box，解析在 `Dispatchers.Default` 上、用 `conflate` 合并高频更新。证据：`PiMarkdown.kt:175-183`（`retainState = true`）；`[LIB]/model/MarkdownState.kt:77-84`（`snapshotFlow…conflate`）、`:191-196`（`updateInput` 的 retainState 分支）、`:212-214`（`parse()` 走 Default）。
- **状态**：done（代价：每次内容变化是**整篇重解析**，不是增量；见 §1.2）。
- **要补的话**：增量解析要么用库的 `StreamingMarkdownState`（需要 per-token delta，§1.2），要么自己做 AST 前缀缓存；架构级，不在本次。

### 7.2 未闭合围栏
- **今天的行为**：`MarkdownCodeFence` 在子节点数 < 3 时直接**跳过不画**（`[LIB]/compose/elements/MarkdownCode.kt:81-88`）；正常未闭合围栏仍有 ≥3 个子节点，会画成一个开放代码块；围栏内容在 settle 前不着色（§6 的 200ms）。`piMarkdownSource` 的 FENCE 扫描把未闭合围栏一直复制到文末，避免把 `$` 在围栏里改写（`PiMarkdown.kt:344-355`）。
- **状态**：done。
- **要补的话**：无。

### 7.3 正在到达的表格
- **今天的行为**：GFM 表格需要分隔行 `|---|` 才成立（intellij 的 GFM 解析），在分隔行到达前它就是普通段落；到达后整块变成表。库/App 都没有“边到达边补列”的逻辑。
- **状态**：done（与 pi 的 marked 行为同：半截表先是段落）。
- **要补的话**：无。

### 7.4 正在到达的公式
- **今天的行为**：`$$`/`$` 缺闭合定界符时正则不匹配，原文透传（`PiMarkdown.kt:388-400`）；pi 的 `pending` 标记同样印原文（`[PI]/…/markdown.js:502-509`、`:380-391`）。所以两者都表现为“先看到 `$…` 字面量，闭合后一次变成公式”。
- **状态**：done。
- **要补的话**：无。

### 7.5 流式行的“不 eager”与行高
- **今天的行为**：正在流式的行**永远不**做同步解析（`app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt:2172-2175`）；非流式、首次入窗口、且当帧 4ms 解析预算还有余额的行才 `immediate = true`（`PiMarkdownImmediate.kt:34-55`、`RowHeightCache.kt:150-239`、`ChatScreen.kt:2163-2175`）；已测量过的行用缓存高度垫住，解析落地即撤（`TranscriptRowHeight.kt`、`RowHeightCache.kt`）。
- **状态**：done（这是 streaming-review 之后的实现，属于“渲染就绪时机”而不是 markdown 能力）。
- **要补的话**：无。

---

## 8. 我发现的其它面

### 8.1 GFM Alerts（`> [!NOTE]` 等）
- **今天的行为**：库解析出 `ALERT` 并画成“竖条 + 图标 + 标题 + 正文”的卡片；App 把五类 accent 映射到 pi 自己的语义令牌（mdQuote/success/mdHeading/warning/error）。证据：`[LIB]/compose/MarkdownExtension.kt:91`、`[LIB]/compose/elements/MarkdownAlert.kt`；`PiMarkdownTheme.kt:223-231`、`:152-155`。**pi 没有 alert**（`[PI]/…/markdown.js` 无 alert 分支，grep 只命中注释）。
- **状态**：有意不同（App 独有；已登记为 addition）。
- **要补的话**：无。

### 8.2 每元素前置 2dp Spacer
- **今天的行为**：库在每次 `MarkdownElement(includeSpacer=true)` 前插 `Spacer(block=2.dp)`；引用/alert 内部递归传 `includeSpacer=false`。证据：`[LIB]/compose/MarkdownExtension.kt:70`、`[LIB]/compose/elements/MarkdownBlockQuote.kt:92-95`、`[LIB]/compose/elements/MarkdownAlert.kt:130-135`。
- **状态**：done。
- **要补的话**：无。

### 8.3 `custom` 槽“认领一切”的陷阱
- **今天的行为**：`MarkdownElementInternal` 的 else 分支是 `handled = components.custom?.invoke(...) != null`，而 `custom` 返回 `Unit`（永不为 null），所以**只要提供了 `custom`，所有未识别节点都被判为已处理**，不再递归子节点。App 的 `piMathComponent` 用无 `else` 的 `when` 只认两种 math，其余“什么都不做”。证据：`[LIB]/compose/MarkdownExtension.kt:92-95`（`handled`）、`:97-101`（递归）；`HEAD PiMarkdownComponents.kt:188-194`；说明 `:143-187`。
- **状态**：done（有意为之；副作用是 `HTML_BLOCK` 也不会递归，见 §11.8，但递归本来也画不出东西）。
- **状态行（本次追加）**：陷阱本身没变（`custom` 的 `when` 仍然没有 `else`），但认领清单从两个变成了三个：`piCustomComponent` 现在按名字认领 `GFMElementTypes.INLINE_MATH`、`GFMElementTypes.BLOCK_MATH`、`HTML_BLOCK`。上面「状态」那句「`HTML_BLOCK` 也不会递归」描述的仍是递归这一侧的事实，而整块消失这件事已在 §11.8 修掉（改由这一支自己把原文画出来）。
- **要补的话**：无（若将来要接管别的未知节点，必须回到这里读这段）。

### 8.4 主题对象缓存（F32 的修复落点）
- **今天的行为**：`colors`/`typography`/`components` 用 `remember(...)`；库的三个解析对象（`flavour`/`parser`/`references`）也由 App `remember` 后传入，使 `rememberMarkdownState` 的 `Input.equals` 在文本不变时相等，**不再每次重组重解析**。证据：`PiMarkdown.kt:115-125`、`:158-160`、`:175-183`；库侧 key/相等性 `[LIB]/model/MarkdownState.kt:50-59`、`:367-401`。
- **状态**：done（F32 已修）。
- **要补的话**：无。

### 8.5 库解析器对“unterminated code fence”的流式裁剪
- **今天的行为**：库的 `MarkdownCodeFence` 对未闭合围栏只画到倒数第二个子节点，靠这个避免闪烁（`[LIB]/compose/elements/MarkdownCode.kt:81-88`）；pi 在 token 层显式裁掉半截闭合围栏（`[PI]/…/markdown.js:153-172`）。
- **状态**：done（不同实现，效果都是“尾围栏不提前吃掉内容”）。
- **要补的话**：无。

---

## 9. `docs/rendering-review.md` F1–F19 逐条复核（只用当前代码）

> 旧评审的 `F` 编号与当时的行号已多处漂移；下表按 HEAD `dee4c46` 重新取证。**F1–F5、F7–F19 都已修**（多数是后续提交修掉的，不是照抄旧文），F6 变成“有意不画”。

| # | 旧问题 | 今天的证据 | 结论 |
|---|---|---|---|
| F1 | mid-turn 消息不上屏 | `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:4205-4213`（压缩中先 `echoUserPrompt` 再 `steer`）、`:4254-4259`（`engine.prompt` 自己回显）；`rpc/.../Transcript.kt:1106-1117`（live `message_end(role=user)` 也建行） | **已修** |
| F2 | 中止/出错后工具卡永远“运行中” | `Transcript.kt:1093-1104`（`TURN_FAILURE_REASONS` 见 `:716`）→ `failTurn` `:1830-1885`（把 Pending 卡改 Error） | **已修** |
| F3 | `length`/aborted 没有任何说明 | `failTurn`：`:1830-1870`（`length`→“回复被令牌上限截断”，error→`errorMessage`）；replay 路径 `:2256-2259` | **已修** |
| F4 | 每 token 抢滚动 | `ChatScreen.kt:1130-1150`（数据而不是布局做 key，`requestScrollToItem`）、`TailFollow.kt`（机器），「回到最新」：`ChatScreen.kt:2277`、`:2325` | **已修** |
| F5 | `entry_appended` 投影两次 | `PiSessionViewModel.kt:2838-2844`（`EntryAppended -> Unit`）；`engine/PiEngineSession.kt:823-826`（KDoc：“A caller must NOT call `transcript.onEntry(entry)`”） | **已修** |
| F6 | 扩展 `custom` 条目什么都不画 | `Transcript.kt:1994-2005`（注释：**故意不画**，RPC 没有 renderer 注册表）+ `:2595`（`ENTRY_EVENT_TYPES` 含 `custom`）；依据用户裁决 D29（`design/ui-refactor/07-construction-decisions.md:186-188`） | **有意不同**（已登记） |
| F7 | 每 token 全量重建列表 | `PiEngineSession.kt:298-313`（`TranscriptPublication`/`changedIndices`）；`PiSessionViewModel.kt:2690-2706`（无变更时沿用 `previous.transcript`，不 `.toList()`）、`:2708-2727`（只 copy 新 UiState） | **已修**（transcript 引用不再每事件全量复制；UiState 仍每事件新建一个对象） |
| F8 | `tool_execution_update` 不节流 | `Transcript.kt:739`（`TOOL_UPDATE_THROTTLE_MS = 200L`）、`:1613-1621`（窗口内只合并）、`:861`（`flushToolOutputs` 保尾） | **已修** |
| F9 | 回放在主线程 | `PiSessionViewModel.kt:3512-3565`（文件读 + 计量 + `seedHistory` 前都在 `withContext(Dispatchers.IO)`，`:3534`）；RPC 回放 `:3612-3639` | **已修** |
| F10 | 用量被解析后丢掉 | 数据入 UiState：`PiSessionViewModel.kt:2724-2727`（`lastUsage`/`turnUsage`）；显示在会话信息 sheet `ui/chat/ChatSheets.kt:601-612`、`:835+`；实时读数变成输入区上下文环 `ChatScreen.kt:2535`、`:3676-3698` | **已修**（形态从 spec 的 32dp 状态行改成 D23 的 ring） |
| F11 | 块边距约 2× | `ChatScreen.kt:2075-2083`（`pageHorizontal=14dp` + top 10/bottom 12）、`:2025-2031`（`blockSpacing` 按密度）、`BlockChrome.kt:67-78`（`BlockColumn` 不再自带 padding） | **已修** |
| F12 | 暗色 `dim` 对比度不足 | `app/src/main/kotlin/app/pi/ui/blocks/UserMessageBlock.kt:157-160`（时间戳改用 `palette.muted`，注释写明 F12）；`ui/theme/PiPalette.kt:152-161`（`metaOnCanvas`/`metaOnCard` 的 `PiContrast.ensure`） | **已修** |
| F13 | 正文对比度 <4.5:1 | `PiPalette.kt:128-143`（`bodyOnTool`/`contextOnTool` 用 `PiContrast.ensure` 到 4.5:1）；调用点 `blocks/ShellBlock.kt:206`、`ToolCallBlock.kt:194`、`ErrorBlock.kt:130`、`DiffBlock.kt:133,192,237` 等 | **已修** |
| F14 | 浅色主题没有 `muted`/`dim` 修正档 | `PiPalette.kt:112-161`（“corrected tokens”整节）+ `PiPalette.Light` 的 `muted/dim`（`:236-237`） | **已修** |
| F15 | 用户消息渲染裸 markdown | `UserMessageBlock.kt:132`（走 `PiMarkdownText`，带 `textColor = palette.userMessageText`） | **已修** |
| F16 | 工具返回的图片被拍成 `[image]` | `Transcript.kt` 的 `ToolCall.images`；`BlockRenderer.kt:138-141`；`blocks/ImageGridBlock.kt`（`ImageCell` 真的用平台解码器解码 base64，失败才占位，KDoc `:100-130`） | **已修**（`ImageGridBlock.kt`/`PiImageViewer.kt` 正在被另一个代理改，见 §0） |
| F17 | 工具输出硬截 400 行、无 >200KB 规则 | `blocks/ToolCallBlock.kt:276`（`COLLAPSED_OUTPUT_LINES = 200`）、`:277`（`MAX_OUTPUT_BYTES = 200 * 1024`）、`:197-201`（「展开全部（共 N 行）」置 `fullOutput=true`）、`:89-90`（`fullOutputPath`/truncation notice） | **已修** |
| F18 | 压缩成本解析后丢掉 | `Transcript.kt:133-147`（`CompactionMarker.usage`）；`blocks/CompactionBlock.kt:150-156`（`PiBilledCostLine`，`showBilledCost` = pi 的 `showCacheMissNotices`） | **已修** |
| F19 | 五个回调从不提供 | `BlockRenderer.kt:87-97`（只剩 `onBranchClick` 有目标，其余参数连同被 gate 的标签一起删了）；`ChatScreen.kt:2209-2215` 提供 `onBranchClick`；图片查看器回来后 `onImageClick` 也有了（`BlockRenderer.kt:94-97`） | **已修**（删除 + 接上两个） |

- **复核结论**：F 列表里没有一条“还开着”的缺陷；只有 F6 的状态从“缺口”变成了“用户裁定的有意不同”。旧的 F32（主题对象每次重建）也已在 HEAD 修掉（见 §8.4）。F20–F34 不在本次要求内，未逐条复核。

---

## 10. `docs/known-gaps.md` A1–A3 复核

- **A1（压缩/分支/Hook 改 markdown）**：`CompactionBlock.kt:104`、`BranchSummaryBlock.kt:80`、`HookMessageBlock.kt:65` 都已调用 `PiMarkdownText`；折叠预览仍是纯文本 + `maxLines`（`CompactionBlock.kt:138-144`、`COLLAPSED_SUMMARY_LINES=2` `:168-171`）。**结论：已做，条目“已完成”属实。**
- **A2（LaTeX）**：`PiLatex.kt` 已在，接线正确，`custom` 陷阱处理正确（§8.3）。但**条目把它写成“已完成”过于乐观**：它只覆盖了行内 `$…$` 的等价物与可归约子集；`\(…\)`/`\[…\]`、`FONT_SWITCH_COMMANDS`、`LIMIT_OPERATORS` 4 项、bracket-limits、关系符空格、参数前导空白、以及整个 `renderLayout` 支都没有（§4.2–§4.4）。**结论：主体已做，但仍有 5 类可补的解析缺口与 1 类架构缺口；“已完成”应理解为“不再是阻塞项”，不是“与 pi 等价”。**
- **A3（markdown 图片）**：HEAD 上块级与行内两个槽都接上，`http(s)` 有意拒绝，失败有 alt+来源兜底（§2.15/§2.16）。**结论：HEAD 已做，条目“已完成”属实；行内 alt 不可达是模型限制，条目已写明。** 但注意工作树正在把 `http(s)` 改成真的去取（§2.15、§0）——`docs/known-gaps.md` 里「`http(s)` 被**有意拒绝**（不出网）」那句已与工作树代码不符，**需要同步改，否则文档与代码互相打脸**。

---

## 11. 按性价比排序的建议

每条给 **收益 / 风险 / 性能代价**（低/中/高），并标 **是否偏离 pi**。偏离 pi 的按仓库规矩要登记。

### 11.1（★最高）把 `\(…\)` / `\[…\]` 两种定界符补进 `piMarkdownSource`
- 收益 **高**：这是唯一“pi 有、App 完全没有”的数学入口，且 `\(`/`\[` 在模型输出里常见度不低于 `$`。
- 风险 **低**：只动 `PiMarkdown.kt` 的正则与扫描；现有的“跳过围栏/行内代码”逻辑可直接复用。
- 性能代价 **低**：只在含 `\` 的串上多一趟线性扫描；无内存/出网。
- **是否偏离 pi**：否（补齐）。

### 11.2（★高）补 5 类 `PiLatex` 解析缺口
`FONT_SWITCH_COMMANDS`（`\rm \bf \it \cal \sf \sl \tt`）、`LIMIT_OPERATORS` 的 `\argmax \argmin \injlim \projlim`、`parseOperator` 的 inline bracket 风格、`RELATION_COMMANDS` 的空格、`parseRequiredArgument`/`parseOptionalArgument` 的前导空白跳过。
- 收益 **高**：这 5 类今天会让**整条公式**退化成原文（前两类）或排版明显不对（后三类），而修法是纯文本层的。
- 风险 **低**：`PiLatex.kt` 单文件，不碰渲染架构。
- 性能代价 **低**：同一趟解析内，无新增分配量级。
- **是否偏离 pi**：否（补齐）。

### 11.3（★高）把表格每格 1 行修完并验证
- 收益 **高**：用户已裁定的表格真问题；单元格多行是“能读”与“不能读”的差别。
- 风险 **低**：槽覆盖，不动架构；但要注意与 §2.14 的库行为（等宽列、横滑）配合，别顺手改列宽。
- 性能代价 **低–中**：单元格行数变多，表高与测量量上升；仍是一次组合，无新后台任务。
- **是否偏离 pi**：是（pi 是内容自适应列宽 + 折行网格；App 仍是固定等宽 + 横滑）。**要登记**为“同 pi 在‘多行可读’上对齐，但列宽策略仍不同”。
- **注**：另一个代理的修复已经在**工作树**里落地（`PiTable` + `maxLines = Int.MAX_VALUE`，工作树 `PiMarkdownComponents.kt:412-439`），但**尚未提交到 HEAD**；本盘点按 HEAD 记“每格 1 行”，并把它列为“已在进行、待验证”。

### 11.4（中）给“超过高亮上限”的代码块加一行提示
- 收益 **中**：>400 行 / >64 KiB 的块今天静默不上色，用户无法判断是“语言不对”还是“块太大”。
- 风险 **低**：`PiCodeSurface` 加一行 Text。
- 性能代价 **低**：一次字符串判断。
- **是否偏离 pi**：是（pi 无上限，这是 App 的既有取舍）。**要登记**并给出提示。

### 11.5（中）对齐 pi 的列表符号（`- ` / 保留原符号）
- 收益 **中**：与 pi 的肉眼一致度；`01-design-spec` 也强调正文列表是 markdown 列表。
- 风险 **低**：`CompositionLocalProvider(LocalBulletListHandler provides …)`，约 10–15 行。
- 性能代价 **低**。
- **是否偏离 pi**：否。

### 11.6（中低）块级数学的排版通道（移植 `renderLayout`）
- 收益 **中**：矩阵/cases/aligned 从原文变成网格；块级 `\frac`/算子上限才有意义。
- 风险 **高**：需要保住行结构的新通道（块级 math 组件或围栏），是**渲染架构改动**。
- 性能代价 **低–中**：每公式一次布局，可缓存。
- **是否偏离 pi**：否（补齐），但建议排在 11.1–11.3 之后。

### 11.7（中低）避免公式归约结果被 markdown 二次解析
- 收益 **中**：消除 `$\text{a*b*c}$`→斜体、公式内 `|`→破表、行中 `$$`→多空段这类隐蔽 bug。
- 风险 **中**：要么走 11.6 的通道，要么做转义（容易过度转义）。
- 性能代价 **低**。
- **是否偏离 pi**：是（App 的实现方式导致；pi 在 token 层替换）。**要登记**。

### 11.8（低）裸 HTML：把原始 HTML 当文本画出来 —— **已完成（本次追加状态行；上面与下面这些描述保留为改动前的快照）**
- **状态行（本次追加）**：两条路都补上了 —— 块级 `HTML_BLOCK` 由 `custom` 槽的新分支（`PiMarkdownComponents.kt` 的 `PiHtmlBlock`）画成 pi 的 `raw.trim()`，行内 `HTML_TAG` 由 `Markdown(annotator = …)` 的认领钩子（`PiMarkdown.kt` 的 `piInlineHtmlAnnotate`）把原始字节 `append` 回那一段 `AnnotatedString`。判定与文本拼装在零 import 的 `ui/render/PiHtml.kt`，由 `PiHtmlCheck`（`tools/run-app-pure-checks.sh` 的 `html`）对着 pi 自己渲染的 51 条形状逐字节断言，并在解析器的**全部 77 个节点类型名**上断言「只认这两个」。本机实跑 `harness: OK`（187 条 PASS）。**残余差异**（都不是这一次引入的，故没有动）：段落软换行仍然是「App 空格 / pi 换行」、被换行拆开的行内标签由库的 `LT`/`TEXT`/`EOL` token 拼出（字符不少、只是不分行）、行内代码两侧的空格（§11.10）、pi 对**整篇**源文本做 `\t`/`\r` 归一而 App 只在 HTML 切片上补。
- 收益 **低–中**：pi 对块级/行内 HTML 都是原文照排（`[PI]/…/markdown.js:474-478`、`:570-574`）；App 今天整块丢弃（`HTML_BLOCK` 落到 `custom` 槽的 `when` 空分支，§8.3；行内 `HTML_TAG` 在 annotator 的 `else` 里没有 `append`，`[LIB]/annotator/AnnotatedStringKtx.kt:363-368`）。
- 风险 **低**：覆盖 `HTML_BLOCK` 需要一个 `text`/自定义槽；行内要给 annotator 传自定义 `MarkdownAnnotator`。
- 性能代价 **低**。
- **是否偏离 pi**：否（补齐）。
- 说明：手机上一段 `<div>` 文本的价值有限，故排后面。

### 11.9（低）标题短前缀 `###`
- 收益 **低**：只影响 h3–h6 的“是不是像 pi”。
- 风险 **低**：覆盖 `heading3..6` 槽，约 20–30 行。
- 性能代价 **低**。
- **是否偏离 pi**：否。

### 11.10（低）行内代码不补两侧空格
- 收益 **低**：纯像素级对齐。
- 风险 **中**：空格是库 annotator 硬编码，要接管 annotator。
- 性能代价 **低**。
- **是否偏离 pi**：否。

### 用户已否决（不作为建议）
- **表格表头吸顶**：用户原话「这个表头吸顶。没感觉有啥问题，如果很麻烦就不要做了」。**不做，不推荐。**

---

## 12. 不确定 / 未能确认

1. **未在真机验证过任何视觉效果**：本机无设备、不能编译，所有“今天显示成什么”都是从代码与库源码推出来的；像素级结论（省略号、横滑手感、对比度观感）未实测。
2. **`MarkdownAnnotator`/库内部对“行内 HTML 是否真的丢字”**：我确认了 annotator 没有 HTML 分支（`[LIB]/annotator/AnnotatedStringKtx.kt:363-368`），但没能在本机跑一遍 `org.intellij:markdown` 解析器验证 `HTML_TAG` 是否一定进入 paragraph 的子节点列表；若库某处另做了兜底，结论要改。
3. **`\(…\)`/`\[…\]` 在 App 里“显示为字面文本”**：`piMarkdownSource` 提前返回与 `MathParser` 只认 `DOLLAR` 都已确认，但我没有端到端跑过 Compose；理论上 `\(x\)` 会作为普通文本显示（反斜杠可能被 markdown 当转义，具体取决于 intellij 的转义处理），这一层未能确认。
