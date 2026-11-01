# 富渲染升级基线（Phase 0）

**快照**：`main` / `c636c862770f024cb1ad962451dbefc43e2332d2`（2026-11-01，本地 `c636c86`）。
**范围**：只记录**今天真实的行为**，不修改任何生产代码、不改动现有测试预期、不跑 Gradle/Android 构建。
**权威方案**：`docs/plans/rich-rendering-upgrade.md`。本文与它冲突时，以它为准；本文负责把它的
「当前状态」逐条落成可核对的证据，并把它没写到的今天行为补齐。
**固定夹具**：`app/src/test/resources/rich-rendering/`（86 条用例，见该目录 `README.md`）。

## 0. 证据等级

| 记号 | 含义 |
|---|---|
| `[源码]` | 本仓库 `c636c86` 的源码，行号以 `git show HEAD:<path>` 为准（工作树有并发改动，见 §4.1） |
| `[解析器]` | `org.jetbrains:markdown-jvm:0.7.9` 的 sources jar（Maven Central，`markdown-0.7.9-sources.jar`） |
| `[渲染库]` | `com.mikepenz:multiplatform-markdown-renderer:0.45.0` 的 sources jar |
| `[引擎锁]` | `tools/pi-engine.lock.json` + 对应 npm 包（pi 1.3.0、grok-mermaid 0.2.3、highlight.js 10.7.3） |
| `[真机待验]` | 只能由实体设备证明（视觉、手势、滚动、跟随、字体、性能），列在 §5 |

本文**没有**任何 `[真机待验]` 项被写成已确认结论。凡是「看起来会怎样」的句子，都标了它是
从代码推出的预期，而不是截图。

---

## 1. 今天的管线（一句话版）

模型原文 → `PiMarkdownText` → **`PiLatex.prepare` 先把公式改写成 Unicode 文本写回源码** →
JetBrains Markdown(0.7.9, GFM) 解析成 AST → Mikepenz 渲染器(0.45.0) 按节点分派 Compose 组件，
App 只覆盖 15 个槽（`codeFence`/`codeBlock`/`heading1..6`/`setext1..2`/`horizontalRule`/`image`/
`inlineImage`/`table`/`custom`）。代码围栏的语言=完整 info string；`mermaid` 走 guest 的
`grok-mermaid` 出 ASCII 图；其余全部是「代码块 + highlight.js 高亮」。

```text
原文 ──PiLatex.prepare──▶ 改过的源码 ──GFM 解析──▶ AST ──分派──▶ Compose
                                                        ├─ 代码围栏 → PiCodeSurface（高亮/复制/mermaid ASCII）
                                                        ├─ 表格 → PiTable（50 行/拟合列宽/横滑）
                                                        ├─ 图片 → 块/行内两个槽（失败回退 alt+地址）
                                                        ├─ 公式节点 → PiFormulaText（兜底，正常路径不可达）
                                                        └─ HTML_BLOCK → PiHtmlBlock（原文）
```

关键事实（都有行号，后面各节展开）：

| 事实 | 证据 |
|---|---|
| 全 App 只有 `PiMarkdownText` 一个 Markdown 入口，6 个调用点 | `[源码]` `PiMarkdown.kt:150-151`；调用点为 `AssistantTextBlock`/`UserMessageBlock`/`CompactionBlock`/`BranchSummaryBlock`/`HookMessageBlock`/`SkillInvocationBlock` |
| 公式在解析**之前**被改写成 Unicode 文本 | `[源码]` `PiMarkdown.kt:180-188`、`PiLatex.kt:1109-1110`、`:1154` |
| 组件槽只覆盖 15 个，其余用库默认 | `[源码]` `PiMarkdownComponents.kt:204-224` |
| 每次内容变化整篇重解析（库的 `MarkdownState`，`retainState=true`） | `[源码]` `PiMarkdown.kt:287-301`、`:412-421` |
| `streaming` 状态在 `c636c86` 上**不存在**于渲染层 | `[源码]` `git show HEAD:…/PiMarkdown.kt` 无该参数；`AssistantText.streaming` 只驱动光标（`AssistantTextBlock.kt:94`） |

---

## 2. 逐项当前行为

每一节的结构固定：**今天是什么** → **证据** → **升级后才能变成什么**（一句话，细节回方案）。
「夹具」列指 `rich-rendering/` 里对应的用例前缀。

### 2.1 Markdown / GFM 基础

- **今天**：标题（ATX/Setext）、段落、粗体、斜体、删除线、有序/无序/嵌套列表、引用、分隔线、
  行内代码、普通链接、GFM Alerts（`> [!NOTE]` 等，库自带 `alert` 槽，App 未覆盖）、GFM 表格（见 §2.3）、
  图片（见 §2.4）、代码围栏（见 §2.2）。段落内软换行渲染成**空格**；只有「本条消息含多行
  LaTeX 网格」时才打开 `eolAsNewLine`，让换行保留（`PiMarkdown.kt:372-377`）。
- **证据**：`[源码]` `PiMarkdownComponents.kt:204-224`（槽表）；`[渲染库]`
  `compose/components/MarkdownComponents.kt:72`（`alert` 槽）、`compose/MarkdownExtension.kt:91`
  （`ALERT -> components.alert`）；`[解析器]` `flavours/gfm/GFMElementTypes.kt:33`
  （`ALERT_TITLE`）、`GFMFlavourDescriptor.kt:112-113`（数学节点生成器）。
- **今天没有**：脚注（`0.7.9` 全文没有 footnote 节点）、标题锚点、`#section` 跳转、任务清单的
  勾选控件（见 §2.9）、HTML 排版（见 §2.8）。
- **夹具**：无专属文件；回归保护靠 §3.2 的「原有格式不得退步」和真机对照截图。
- **升级后**：仍是这套解析器与主题；只增加分发（公式/SVG/HTML/树状视图），不得改基础格式。

### 2.2 代码围栏与 info string

- **今天**：
  1. 语言 = **完整 info string**。库原样把 `FENCE_LANG` 节点的文本交给回调，不 trim、不取首词
     （`[渲染库]` `compose/elements/MarkdownCode.kt:80`）；App 只做 `trim().lowercase()`
     （`[源码]` `PiCodeHighlight.kt:238-242`）。
  2. 因此 ` ```ts title="src/main.ts" ` 的语言名是 `ts title="src/main.ts"`：标题行显示**归一化（trim+lowercase）后的整串**
     （`PiMarkdownComponents.kt:1011-1030`），highlight.js 不认这个名字 → 整块按
     `mdCodeBlock` 上色、**没有语法高亮**（`PiNodeCodeHighlighter.kt:139` 的 `languageKnown`）。
  3. 代码体是高亮后的 `AnnotatedString`；**没有行号、没有折叠、没有「自动换行/横向滚动」切换**，
     只有 `horizontalScroll`（`PiMarkdownComponents.kt:984`）。
  4. 「复制」复制的是**代码体**（`PiCodeHeader`，`:1033-1038`），而代码体在库侧已经过
     `replaceIndent()`（`[渲染库]` `MarkdownCode.kt:85`）——**缩进过的围栏复制出来不是逐字节源码**，
     这是一个需要裁定的既有语义（见 §7-2）。复制没有成功反馈。
  5. 高亮上限：`> 64 KiB` 或 `> 400 行` **不发请求**、整块不上色（`PiNodeCodeHighlighter.kt:81-82`、`:139`），
     界面上没有任何说明；并发 2、队列 64、超出丢弃；调用方最多等 750 ms（`:53-63`）；
     结果缓存 128 条、span>2048 不缓存（`:72-73`）；流动围栏防抖 200 ms（`PiMarkdownComponents.kt:1320`）。
  6. `mermaid` 之外的任何语言都没有特殊分支——` ```diff ` 也是普通围栏（见 §2.5）。
- **证据**：`[解析器]` `parser/markerblocks/providers/CodeFenceProvider.kt:28-35`（`FENCE_LANG`
  从 info 起点到行尾）；`[渲染库]` 同上；`[源码]` 上述行号；`[引擎锁]` highlight.js 10.7.3。
- **夹具**：`code-fence-metadata.md`（10 条）、`diff.md`（3 条）。
- **升级后**：解析 `title`/`filename`/`file`、行号开关、长块折叠、换行/横滑切换、文件名显示、
  复制反馈；**语言提取必须先修**，否则上面每一条都挂在错误的语言名上。

### 2.3 GFM 表格

- **今天**：`PiTable` 覆盖 `table` 槽（`PiMarkdownComponents.kt:781-853`）：
  - 列宽 = 可用宽 / 列数，低于 `120.dp` 才保持最小列宽并横滑（`MIN_TABLE_CELL_WIDTH`，`:859`）；
  - 单元格 `maxLines = 50`（`TABLE_MAX_LINES`，`:873`），不再是库默认的 1 行；
  - 表头一层 `mdHr` 10% 底色；真要横滑时右缘 26 dp 渐隐（`:838-851`）；
  - 表头分隔行仍由库画成一条 `MarkdownDivider`（`[渲染库]` `MarkdownTable.kt:116`）；
  - **列对齐没有实现**：解析器只在 HTML 生成器里读 `TABLE_SEPARATOR` 的 `:`（`[解析器]`
    `flavours/gfm/GFMGeneratingProviders.kt:226-252`），AST 里没有对齐属性；`PiTable` 也不读
    `TABLE_SEPARATOR`（只数表头 CELL，`:861-863`）。
- **今天没有**：表头吸顶（方案明确不做）、对齐、列宽按内容自适应（那是 pi 的行为）、
  「正在到达的表格」逐步补列（分隔行到达前就是普通段落）。
- **夹具**：无专属文件；表格用例并入真机清单（§5-3），因为列宽/渐隐/横滑都是像素行为。
- **升级后**：只补对齐；列宽算法与 50 行上限**不动**。

### 2.4 图片

- **今天**：块级走 `image` 槽（`PiImagePlaceholder`，`PiMarkdownComponents.kt:489-521`）、行内走
  `inlineImage` 槽（`PiInlineImage`，`:554-576`）。字节来自 `bridge/GuestImageBytes.kt`：
  `data:`、`file:`/裸 guest 路径、`http(s)`（无头请求、8 MiB 上限、有界超时、有磁盘缓存）。
  取不到字节 → 回退文字：「alt（或『图片』）+ 图片地址：…（当前无法显示）」；行内槽拿不到 alt，
  只能显示『图片』+ 地址两行。有宿主提供的 `onImageClick` 时，点按会先异步取字节再开查看器
  （`PiImageViewerTap`，`:590-620`）；没有回调时一个修饰符都不多。
- **证据**：`[源码]` 上述行号；`[真机待验]` 实际解码/失败回退的视觉。
- **夹具**：无（图片依赖设备字节通道，夹具只能给 markdown 源，价值低；真机项见 §5-4）。
- **升级后**：HTML 里的 `<img>` 必须复用这条链路（方案 §六.3），不得自己读文件。

### 2.5 Diff

- **今天**：工具卡 `ToolDiff` → `DiffBlock`（`[源码]` `BlockRenderer.kt:157`），有行号、增删着色、
  上下文折叠、词级差异。**` ```diff ` 围栏不是这条路**：它走 `PiCodeSurface`，只被 highlight.js
  的 `diff` 语法着色（`diff` 是真实语言，`app/src/main/assets/pi-extensions/pi-highlight/aliases.ts`），
  没有行号/折叠/hunk 结构。
- **证据**：`[源码]` `PiMarkdownComponents.kt:924-999`（没有 diff 分支）。
- **夹具**：`diff.md`（3 条，含「标签写 diff 但内容是散文」的误判护栏）。
- **升级后**：围栏 diff 安全复用 `DiffBlock` 的行渲染；**不复制** `DiffBlock.kt` 整套逻辑；
  工具卡 Diff 完全不动。

### 2.6 LaTeX

- **今天**（`PiLatex.kt`，2242 行）：
  - 四种定界符都在：`\(…\)`、`\[…\]`、`$…$`、`$$…$$`。`\(`/`\[` **解析器不认**（0.7.9 的
    `MathParser` 只认 `DOLLAR`），完全靠 `PiLatex.prepare` 在解析前替换。
  - 顺序与边界：先块级后行内；`FENCE`/`INLINE_CODE` 状态跳过代码，所以代码里的 `$` 不变公式
    （`PiLatex.kt:1063-1110`、`:1354`、`:1361`）。
  - 块级正则 `$$…$$` 或行首 `\[…\]`（`PiLatex.kt:1313-1318`）；行内三条分支
    `\(…\)`、`\[…\]`、`$…$`（`:1337-1342`）。
  - 多行环境（矩阵/cases/aligned/gather）写成 Unicode 网格；写回源码时把**前导空格换成 NBSP**
    （`MARKDOWN_PROTECTED_SPACE`，`:1127-1136`），避免被 CommonMark 当缩进代码块切碎；
    同时 `PreparedMath.hasDisplayGrid` 让这条消息打开 `eolAsNewLine` 并把段落换成等宽
    （`PiMarkdown.kt:189`、`:215-237`、`:372-377`）。
  - 归约失败 → **原文保留**（`applyMath` 返回 `match.value`）；文档行内 `$` 规则保守
    （`(?<!\s)`、`(?![\p{L}\p{N}])`），用 220 条真实语料量过（`PiLatex.kt:1319-1342`）。
  - 已知偏差：行中的 `$$x$$` 会变成独占一段的显示式（`$$` 分支没有行首锚点，`PiLatex.kt:1094-1097` 记着，正则见 `:1313-1318`）。
  - `custom` 槽里的 `PiFormulaText`（`PiMarkdownComponents.kt:425-460`）是**兜底**：正常路径
    公式已被改写，节点到不了那里；它只处理「源里还有漏网 math 节点」的情况。
- **今天没有**：真正的数学排版、SVG/WebView、MathJax/KaTeX、行内公式的批量提交。
- **夹具**：`latex.md`（13 条）。
- **升级后**：公式作为独立内容进入渲染（块级走 `piCustomComponent`/`PiFormulaText` 路径，行内走
  annotator + `InlineTextContent` 或等效占位）；**不得**再把真公式转成普通 Unicode 文本交给
  Markdown；流式中未闭合保持原文或 PiLatex 近似；失败保留 PiLatex 输出。

### 2.7 Mermaid

- **今天**（`[源码]` `PiMermaid.kt`、`PiMarkdownComponents.kt:1116-1221`）：
  - 判定：info string 的**首个空白分隔 token** `== "mermaid"`（`:1182-1184`）——所以
    ` ```mermaid title="x" ` 仍然出图，title 被忽略；这与高亮器的「整串当语言名」是两套规则。
  - 模式：读 pi 的 `markdown.mermaid`（`off`/`final`/`streaming`，默认 `streaming`），
    来自文件型 settings store，按 settings 文档的 mtime+size 失效（`:1249-1312`）。
  - 引擎：guest 的 `grok-mermaid`，走本地回环 `/mermaid`，返回**ASCII 框线图**（按语义
    class 着色），不是 Android WebView。
  - `grok-mermaid 0.2.3`（pi 1.3.0 钉住）只支持 **flowchart/graph、state、class、ER、sequence**
    五类；其余类型返回 `null`（= `PiMermaidReply.NoArt`，定论、进缓存）→ 界面显示**源码块**。
  - `warnings` 非空时**不画图**：显示源码 + 一行「Mermaid 图未完整渲染：…」（`PiMermaid.kt:153-157`，
    `PiMarkdownComponents.kt:928-934`、`:991-997`）。
  - 交互：没有缩放、平移、适应屏幕、源码/图形切换；宽图靠 `horizontalScroll`（`:941-944`）。
  - 缓存：进程级 64 项 / 4 MiB；最多 3 次询问（首次 + 2 次重试，间隔 400 ms）；只有 `Unavailable`
    才重试，`NoArt` 是定论（`:1148-1219`）。
- **夹具**：`mermaid.md`（12 条：5 条今天 `approx`，7 条今天 `source`）。
- **升级后**：本地 Mermaid.js → SVG；`securityLevel: "strict"`、`htmlLabels: false`、禁外部资源；
  旧 ASCII 路径保留为次级回退，**先建新引擎、再谈删旧路径**。

### 2.8 HTML

- **今天**：**不解析、不执行、不排版**，只把源码当文字画出来，且**一个字符都不丢**：
  - 块级 `HTML_BLOCK` 由 `custom` 槽认领 → `PiHtmlBlock` → `Text(typography.paragraph)`
    （`PiMarkdownComponents.kt:372-384`、`:408-421`）；文本 = pi 的 `raw.trim()`（`PiHtml.kt:103-104`），
    其中 `\t`→三空格、`\r\n|\r`→`\n`（`:120-139`），trim 用 JS 的字符集合（`:145-167`）。
  - 行内 `HTML_TAG` 由 annotator 认领 → 原文 append 进段落（`PiMarkdown.kt:74-84`、
    `PiHtml.kt:112-113`）；没有这个钩子时这些字符会**整段消失**（`PiHtml.kt:73-79` 记着）。
  - 因为不解析：`<details>` 不会折叠、实体不解码、未知标签原样、危险标签**安全**（只是文字）。
- **今天没有**：jsoup、白名单、`details/summary` 折叠、HTML 表格/列表/图片。
- **夹具**：`html.md`（9 条）。
- **升级后**：安全子集 + 原文回退；未知标签安全降级、危险标签绝不执行、无法安全解析的整块保留源码。

### 2.9 任务清单

- **今天**：`[x]` / `[ ]` **是文字**，不是控件。库有 `checkbox` 槽（`[渲染库]`
  `compose/components/MarkdownComponents.kt:71`、`:219`），默认画 `"[x] "` / `"[ ] "`
  **等宽文本**（`compose/elements/MarkdownCheckBox.kt:14-24`），并且**取代项目符号**
  （`compose/elements/MarkdownList.kt:108`、`:124-131`）。App 没有覆盖这个槽
  （`PiMarkdownComponents.kt:204-224` 的槽表里没有 `checkbox`）。
- **证据**：以上；`[解析器]` `flavours/gfm/GFMElementTypes.kt:18`（`CHECK_BOX` token 存在）。
- **升级后**：只读勾选标记；**仍是文字还是画图标、要不要保留 `[x]` 文本**是产品口径（见 §7）。

### 2.10 JSON / XML / YAML

- **今天**：三者都是普通围栏代码块 + highlight.js（`json`、`xml`、`yaml` 都是真实语言；
  `yml` 是 `yaml` 的别名）。**没有树状视图**：没有折叠、没有路径复制、没有节点结构，只有 highlight.js 对字符串/数字/标点的着色。
- **今天没有**：`kotlinx.serialization.json` 解析、XML 解析、YAML 解析、大小/深度/节点数上限
  （只有高亮器的 64 KiB/400 行上限在间接限制）。
- **误判护栏**：正文里的裸 `{"ok": true}` **不会**被识别（没有任何猜测逻辑）。
- **夹具**：`json.md`（5）、`xml.md`（4）、`yaml.md`（5）。
- **升级后**：识别明确围栏 → 树状视图；默认仍显示代码，用户切换；解析失败/超限回退源码。
  XML 禁 DTD/外部实体（XXE），YAML 用安全加载（禁危险标签）。

### 2.11 SVG

- **今天**：` ```svg ` 是普通围栏。highlight.js 里 `svg` 是 **`xml` 的别名**
  （`[源码]` `pi-extensions/pi-highlight/aliases.ts:172`），所以今天显示的是**按 XML 着色的源码**，
  不是图形。没有解析、没有安全校验、没有画布上限、没有缩放/平移。
- **夹具**：`svg.md`（6 条，含 defs/use/marker/clipPath/gradient 的兼容性样本与三种攻击面）。
- **升级后**：本地解析 → 等比例绘制 → 缩放/平移/适应屏幕/源码切换；安全规则见方案 §八。

### 2.12 CSV / TSV（方案 §九，顺带记录）

- **今天**：highlight.js 10.7.3 **没有** `csv`/`tsv` 语言，别名表里也没有（`aliases.ts` 全文无这两个名字）
  → 整块按 `mdCodeBlock` 上色、**未高亮**，标签显示 `csv`/`tsv`。
- **升级后**：原生 Compose 数据表；不能用 `split(",")`；超限回退源码。

### 2.13 流式

- **今天**：
  1. 每条消息携带**累计文本**；内容一变就整篇重新解析（`PiMarkdown.kt:287-301`），
     `retainState=true` 让旧内容在解析期间继续可见（`:412-421`）。
  2. 围栏未闭合 → 解析成「开放代码块」，到 EOF 为止；**这是正常渲染，不是错误**
     （`PiMarkdown.kt:128-133` 的 KDoc 写明）。
  3. 高亮防抖 200 ms（`PiMarkdownComponents.kt:1320`），首版不等；流动围栏不上色是预期。
  4. Mermaid：producer 防抖 200 ms；`Final` 模式每次也先等 200 ms（`:1197`）。
  5. 公式：未闭合的 `$$`/`$` 不匹配 → 原文留在正文。
  6. JSON/HTML：没有解析器，所以天然不会「提前切换」。
  7. **没有任何结构完整性判定**：不存在「闭合了才渲染」的统一闸门；每类内容各管各的。
  8. `c636c86` 上渲染层**没有** `streaming` 参数；`AssistantText.streaming` 只驱动光标
     （`AssistantTextBlock.kt:94-102`）。工作树里另有一位代理正在加
     `PiMarkdownText(streaming=…)` 与 `LocalPiMarkdownStreaming`（未提交，见 §4.1）。
  9. 消息结束后**没有**「最终重解析」的专门步骤：最后一次内容变化本身就是一次完整解析。
- **夹具**：`streaming-partial.md`（10 条快照）。
- **升级后**：每类新渲染都必须有「结构完整才显示」的判据；旧任务结果不得覆盖新内容；
  失败不得让整条消息消失；不得影响触底跟随与行高缓存（方案 §十四）。

---

## 3. 升级前后对比口径

### 3.1 固定变量（不固定这些，对比没有意义）

| 变量 | 取值 |
|---|---|
| 基线提交 | `c636c86`（本文所有 `now=`） |
| 对比时的提交 | 升级后同一分支的 HEAD，且**记录 SHA** |
| 设备 | 同一台实体机、同一分辨率/密度；`settings get system font_scale` 记下来（默认 1.0） |
| 应用设置 | `app.appearance.typography = comfortable`（默认）、`app.appearance.lineHeight = normal`、`app.appearance.fontSize` 默认 |
| 主题 | 浅色 + 深色各一轮；主题文件路径记下来（截图目录的惯例：用自己写的主题，见 `docs/screenshots/README.md`） |
| `markdown.mermaid` | 默认 `streaming`；另测 `off` 与 `final`（这两个是行为开关，不是外观） |
| 输入 | 只用本目录夹具，按 `id` 一一对应；不临时改写 |
| 网络 | 在线一轮 + 飞行模式一轮（离线验收，方案 §十七） |
| 注入方式 | 见 §3.3；**基线轮与升级轮必须同一种** |

### 3.2 判定规则（每条都可执行）

1. **状态符合**：升级后，每个用例的实际形态必须等于它的 `expect=`；`now=` 是升级前必须复现的。
   状态词表见夹具 `README.md` §2。
2. **内容不丢**：任何 `source`/回退结果，其可见文本必须**逐字节包含**夹具 body（允许外围
   chrome：代码块边框、复制按钮）。`rich` 结果必须同时能「复制源码」且复制结果与夹具 body
   逐字节相同——**这一条会暴露今天的 `replaceIndent()` 语义（§2.2-4）**，见 §7-2。
3. **原有格式零退步**：§2.1/§2.3/§2.4/§2.5 的既有能力在同一份输入、同一主题下**外观与交互不变**；
   任何变化都必须先在方案里登记为「有意变更」。
4. **不误判**：`json.md/js-inline-text`、`diff.md/df-not-diff`、`latex.md/lx-code-guard`、
   `latex.md/lx-price`、`code-fence-metadata.md/cf-language-prefix` 这几条必须保持 `source`——
   它们量的是「新识别规则的假阳性」。
5. **流式五不许**（录屏判定）：不许空白、不许闪烁、不许抢滚动、不许丢失触底跟随、
   不许让失败渲染带走整条消息。
6. **安全四不许**（`html.md`/`svg.md`/`xml.md`/`yaml.md` 的攻击面用例）：不许执行脚本、
   不许发外部请求、不许读本地文件、不许实例化危险类型。

### 3.3 基线怎么采集（真机）

**输入注入**（任选其一，两轮必须一致）：

- **A（推荐，最省事）**：把夹具 body 粘进输入框发送。用户消息也走 `PiMarkdownText`
  （`UserMessageBlock.kt:146`），所以同一套渲染路径、不依赖模型。
- **B（更真实）**：让模型原样回显夹具内容（prompt：「请逐字输出以下内容，不要加解释」），
  用于验证「助手消息 + 流式」的路径。**B 会引入模型改写，不能用于逐字节断言**。

**采集**：

1. 截图：浅色/深色各一张，命名 `docs/screenshots/rich-<id>-<theme>.jpg`（夹具 `README.md` §6）。
   全屏原始分辨率，不裁切、不缩放。
2. 录屏：`st-*` 与「长消息滚动」项用屏幕录制；文件名 `rich-<id>-stream.mp4`。
3. 失败回退与崩溃：`mf-*` 只记录「有没有崩/有没有空白」，不单独截图。
4. 记一份设备信息：`adb shell getprop ro.product.model`、`wm size`、`wm density`、`font_scale`、
   App 版本名与 versionCode。

### 3.4 本阶段明确不做

- 不跑 `./gradlew`、不装 APK、不出 APK 体积与构建结果（任务书禁止；谁跑见 §7-1）。
- 不改现有测试的期望值来迎合新代码（方案 §十六 Phase 0 原文）。
- 不把「编译通过」当验收（方案原则 10）。
- 不修 `docs/render-inventory.md` 的旧盘点了（它停在 `dee4c46`，表格那节已过时）；本文是新的基线。

---

## 4. 已核对的解析器 / 引擎事实

### 4.1 与方案陈述的差异

| # | 方案的陈述 | 本机核对结果 | 影响 |
|---|---|---|---|
| 1 | 「`PiMarkdownText` 没有 streaming/settled 参数」 | `c636c86` 上**正确**：`git show HEAD:…/PiMarkdown.kt` 无该参数，`AssistantText.streaming` 只驱动光标。**但工作树里已有未提交的 `PiMarkdownText(streaming=…)`、`PiMarkdownStreaming.kt` 与 `render/rich/FenceInfo.kt`、`RichBlock.kt`**（§4.2） | 基线以 `c636c86` 为准；若这些改动先落地，**真机采集前必须重新基线**，否则「升级前」已经不是方案描述的那一版 |
| 2 | 「复制操作默认复制原始源码」（方案 §二、§十七） | 今天复制的**不是**逐字节源码：库先 `replaceIndent()` 再交给组件，复制的是那个结果（`[渲染库]` `MarkdownCode.kt:85`；`PiMarkdownComponents.kt:1033-1038`） | 「原始源码」需要定义：是文件里的围栏 body（含缩进）还是库去缩进后的 body。这是**新要求**，不是现状 |
| 3 | 「`FENCE_LANG` 是完整 info string」 | **正确**，但更准确的说法是：`FENCE_LANG` 节点从 info 起点覆盖到**行尾**（含行尾换行），库再原样交给回调；`trim` 发生在 App 的 `normalize`，不发生在库 | 「首个有效字段作为语言」必须由新解析器实现；不能指望库 |
| 4 | 「任务清单 `[x]`、`[ ]` 仍是文本」 | **正确**，但准确形态是「等宽 `[x] ` 文本**取代项目符号**」，不是段落里的一串字符 | 改成只读勾选标记时，视觉变化包含「项目符号不见了」这件事 |

### 4.2 工作树里的并发改动（不是基线的一部分）

核对期间与交付前各看了一次 `git status`（2026-11-01 18:0x–18:1x UTC）。另一位代理正在写
Phase 1，列表**在核对过程中仍在增长**，以下是交付时观察到的全部内容（以 `git status` 为准）：

```text
 M  app/src/main/kotlin/app/pi/ui/blocks/AssistantTextBlock.kt      （+1 行：传 streaming）
 M  app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt              （+7 行：参数 + CompositionLocal）
 M  app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt    （+23 行：代码块/表格 chrome 接线）
 ?? app/src/main/kotlin/app/pi/ui/render/PiMarkdownStreaming.kt     （streaming 组合局部）
 ?? app/src/main/kotlin/app/pi/ui/render/PiCodeChrome.kt            （代码块 chrome）
 ?? app/src/main/kotlin/app/pi/ui/render/PiTableAlignment.kt        （表格对齐）
 ?? app/src/main/kotlin/app/pi/ui/render/rich/FenceInfo.kt          （fence info string 解析）
 ?? app/src/main/kotlin/app/pi/ui/render/rich/RichBlock.kt          （富块分发）
 ?? app/src/test/kotlin/app/pi/ui/render/rich/{FenceInfoCheck,RichBlockCheck,PiJsonTreeCheck}.kt
```

这些**都未提交**，也**不是**本文基线的一部分：本文所有 `[源码]` 证据取自
`git show HEAD:`，所以结论不受它们影响——但**工作树里打开同一个文件时行号会对不上**
（`PiMarkdownComponents.kt` 就多了 23 行），引用行号时请以 `git show HEAD:` 为准。
注意 `PiTableAlignment.kt` 的存在说明「表格对齐」可能已经在 Phase 1 动工（与 §7-7 的排期
建议无关，只是别把它当成本文的现状）。父代理需要决定：基线快照是否要跟着这些改动走（见 §7-11）。

（另：`app/src/test/resources/rich-rendering/` 与 `docs/rich-rendering-baseline.md` 是本次交付，
不是并发改动。）

### 4.3 逐条事实表

| # | 事实 | 证据 | 谁受影响 |
|---|---|---|---|
| F1 | `FENCE_LANG` = 完整 info string（到行尾） | `[解析器]` `CodeFenceProvider.kt:28-35` | 代码围栏元数据解析（Phase 1） |
| F2 | 库原样把 `FENCE_LANG` 文本作为 `language` 交给回调，不 trim、不取首词 | `[渲染库]` `MarkdownCode.kt:80` | 同上；证明「整串当语言名」的根因在库+App 两侧 |
| F3 | App 的 `normalize` 只有 `trim().lowercase()` | `[源码]` `PiCodeHighlight.kt:238-242` | Phase 1 必须先改这里 |
| F4 | 头部的 HTML 生成器才取「首个空白分隔词」 | `[解析器]` `html/GeneratingProviders.kt:225-230` | 说明「取首词」是生成器的行为，不是 AST 的 |
| F5 | `CHECK_BOX` 存在，是 **token** 类型（`GFMTokenTypes`） | `[解析器]` `flavours/gfm/GFMElementTypes.kt:18` | Phase 1 任务清单 |
| F6 | 库有 `checkbox` 槽，默认画等宽 `[x] `/`[ ] `，且取代 bullet | `[渲染库]` `MarkdownComponents.kt:71`、`:219`、`MarkdownCheckBox.kt:14-24`、`MarkdownList.kt:108`/`:124-131` | 同上；App 未覆盖该槽 |
| F7 | 表格对齐只存在于 HTML 生成器；AST 仅保留 `TABLE_SEPARATOR` token | `[解析器]` `GFMGeneratingProviders.kt:226-252`、`GFMElementTypes.kt:10` | 表格对齐（方案 §一.1 的「补对齐」要自己从 separator 文本解析） |
| F8 | `PiTable` 不读 `TABLE_SEPARATOR`（只数表头 CELL） | `[源码]` `PiMarkdownComponents.kt:861-863` | 同上 |
| F9 | 0.7.9 **没有**脚注节点（sources jar 全文无 `footnote`） | `[解析器]` grep 0 命中 | 脚注必须独立预处理/扩展（Phase 5） |
| F10 | 数学解析器只认 `DOLLAR`：`$`→`INLINE_MATH`、`$$`→`BLOCK_MATH` | `[解析器]` `parser/sequentialparsers/impl/MathParser.kt:14-30`、`GFMFlavourDescriptor.kt:112-113` | `\(…\)`/`\[…\]` 只能由 `PiLatex` 预处理；升级后的 annotator/占位方案也要自己认这两种 |
| F11 | 库的 fence body 经过 `replaceIndent()` | `[渲染库]` `MarkdownCode.kt:85` | 复制语义（§4.1-2）与「内容不丢」判定 |
| F12 | `svg` 在 highlight.js 里是 `xml` 的别名；`patch` 是 `diff` 的别名；`yml` 是 `yaml` 的别名；**没有** `csv`/`tsv` | `[源码]` `aliases.ts:172`、`:136`、`:198`；`[引擎锁]` hljs 10.7.3 | §2.10–§2.12 的「今天是什么颜色」 |
| F13 | `grok-mermaid 0.2.3` 只支持 flowchart/graph（含 subgraph）、state、class、ER、sequence | `[引擎锁]` pi 1.3.0 → grok-mermaid 0.2.3，其 `dist/index.js` KDoc 明列；`pi-engine.lock.json` | 7 类图今天必然源码；Phase 2 的 12 类目标不能拿今天的 ASCII 当对照 |
| F14 | GFM Alerts 有 `ALERT_TITLE` token 且库有 `alert` 槽 | `[解析器]` `GFMElementTypes.kt:33`；`[渲染库]` `MarkdownExtension.kt:91` | 已有能力，升级不得退步 |
| F15 | 未闭合围栏被解析成开放代码块，直到 EOF | `[源码]` `PiMarkdown.kt:128-133`（KDoc）；`[真机待验]` 视觉 | 流式验收的第一条 |

---

## 5. 需要真机截图 / 录屏的基线项（CI 不能证明）

本仓库的纯逻辑 harness 只编译「无 Compose、无 Android」的 Kotlin（`tools/run-app-pure-checks.sh`
的头部说明了这条边界），所以**任何**涉及排版、字体、颜色、手势、滚动、异步时序的结论都不属于
CI 能证明的范围。下表是必须在设备上留证据的项。

| # | 项 | 为什么 CI 不能证明 | 怎么留证据 | 存档名 |
|---|---|---|---|---|
| 1 | Markdown/GFM 全量外观（标题层级、列表、引用、分隔线、Alerts、删除线、行内代码） | 字号/间距/颜色都是 Compose 运行时 + 主题文件决定的 | 一条消息里放全部元素，浅/深各一张 | `rich-gfm-<theme>.jpg` |
| 2 | 代码围栏元数据：`cf-*` 全部 10 条 | 标题行显示什么、是否高亮、行号/折叠/换行按钮是否存在，都是像素 | 逐条截图（可与 #1 拼一张长图） | `rich-<cf-id>-<theme>.jpg` |
| 3 | 表格：2/3/4+ 列、长单元格 50 行、对齐标记 `:---:` | 列宽拟合、右缘渐隐、横滑、对齐都是像素 | 一张 4 列 + 一张 2 列长单元格；带 `:---:` 的对齐样本 | `rich-table-<theme>.jpg` |
| 4 | 图片：块级/行内成功、失败回退、点开查看器 | 解码、磁盘缓存、失败文案都是设备行为 | 成功一张、坏链接一张、点开一张 | `rich-image-<theme>.jpg` |
| 5 | Diff：`df-*` 三条 + 一张工具卡 Diff 对照 | 「围栏 diff」与「工具卡 diff」今天长得不一样，必须并排 | 同一屏放两条 | `rich-diff-fence-vs-card-<theme>.jpg` |
| 6 | LaTeX：`lx-*` 13 条 | Unicode 网格的对齐、NBSP 是否防住代码块切碎、行内高度，都只能看 | 一条消息放全部块级公式 + 一条放行内；断言「没有被切成代码框」 | `rich-latex-<theme>.jpg` |
| 7 | Mermaid 今天支持的五类（flowchart/sequence/class/state/ER） | ASCII 图的宽度、横滑、class 着色是像素 | 五条各一张或一张长图 | `rich-mm-<type>-<theme>.jpg` |
| 8 | Mermaid 今天不支持的七类（gantt/mindmap/pie/gitGraph/timeline/quadrant/sankey） | 「今天显示源码」这件事本身就是基线证据 | 一张长图（能看出是代码块而不是图） | `rich-mm-unsupported-<theme>.jpg` |
| 9 | HTML：`ht-*` 九条 | 今天「原文可见」是像素事实；升级后折叠组件是否真的折叠也是 | 今天一张，升级后一张（同名覆盖前先归档） | `rich-<ht-id>-<theme>.jpg` |
| 10 | JSON/XML/YAML 今天的代码块外观 | 高亮颜色是像素 | 三张或一张长图 | `rich-data-<theme>.jpg` |
| 11 | SVG 今天按 XML 着色 | 「不是图形」本身就是基线 | 一张 | `rich-svg-source-<theme>.jpg` |
| 12 | CSV/TSV 今天未高亮 | 颜色差异只能看 | 一张 | `rich-csv-source-<theme>.jpg` |
| 13 | 流式：`st-*` 十条快照 | 时序、闪烁、跟随、空白都只在设备上发生 | **录屏**，每条 3–5 秒 | `rich-<st-id>-stream.mp4` |
| 14 | 长消息滚动 + 长代码块 + 一张 Mermaid | 帧率、卡顿、行高缓存是否失效 | 录屏 10 秒 | `rich-scroll-perf.mp4` |
| 15 | 深色/浅色主题各一轮 | 主题是用户主题文件（`~/.pi/agent/themes/*.json`），不是内置 | 每类至少一张深色 | 见上各行的 `<theme>` |
| 16 | 飞行模式一轮 | 「本地渲染」是产品承诺，必须实测 | 关网后重放 #1/#2/#6/#7/#10/#11 | 同各行的 `-offline` 后缀 |
| 17 | 系统字体缩放（1.15 / 1.3） | 行高与代码网格是否错位 | 同一屏两档各一张 | `rich-fontscale-<n>.jpg` |

**采集顺序建议**：先 #1/#2/#6/#7/#10/#11（今天是什么样），再 #13/#14（今天怎么动），
最后才是升级后的对照。**不要**在升级代码落地之后补拍「升级前」——那时已经拍不到了。

---

## 6. 现有测试与注册状态

| 测试 | 内容 | `run-app-pure-checks.sh` 里的名字 |
|---|---|---|
| `app/src/test/kotlin/app/pi/ui/render/PiLatexCheck.kt` | `PiLatex.toUnicode` 与 pi 逐字节对照（`cases` 段）+ 114 条块级目标 + 偏差锁 | `latex`（`:1023`） |
| `app/src/test/kotlin/app/pi/ui/render/PiHtmlCheck.kt` | 块级/行内 HTML 的 pi 字节夹具 + 77 个节点类型的认领集合 | `html`（`:973`） |
| `app/src/test/kotlin/app/pi/highlight/PiMermaidMemoCheck.kt` | mermaid 进程级缓存策略 + 三处源码文本断言 | `mermaid-memo`（`:958`） |

- **本阶段不新增/不修改任何 harness，也不改 `tools/run-app-pure-checks.sh`**（父代理统一注册）。
- 新增的 `rich-rendering/` 夹具**尚未**被任何 harness 消费。消费方按夹具 `README.md` §3 的
  `CASE` 正则切分，路径用 `pi.repo.root` 解析（与 `PiLatexCheck.kt` 一致），夹具缺失应 `exit 2`。
- 现有测试的期望值**一条都没动**；`PiLatexCheck` / `PiHtmlCheck` 的夹具（`pi-latex-fixtures/`、
  `pi-html-fixtures/`）也没有动。

---

## 7. 仍需父代理决定的事项

| # | 事项 | 为什么需要你定 | 我的建议 |
|---|---|---|---|
| 1 | 「构建结果 / 渲染时间 / APK 大小」由谁采集 | 本阶段任务书禁止跑 Gradle；方案 §十六 Phase 0 却要求记录 | 升级前后各跑一次 `./gradlew :app:assembleRelease`，记录 APK 字节数与 `tools/run-app-pure-checks.sh` 输出；由你或 CI 做 |
| 2 | 「复制原始源码」的定义 | 今天复制的是 `replaceIndent()` 之后的 body（§2.2-4、F11） | 定义为「文件里的围栏 body（含缩进）」，即需要从原文切片复制；这会让 §3.2-2 成为一条**新**要求 |
| 3 | 行中 `$$x$$` 当显示式（`lx-midline-display`） | 既有偏差，pi 只在行首才当块级；修它影响面比补定界符大 | 保持现状并在升级说明里登记；不要顺手「修」 |
| 4 | HTML 实体（`&amp;`）与真实注释的口径 | 今天都原样可见；解码/隐藏会改变复制与搜索 | 基线先按「保留原文」；解码必须显式决定 |
| 5 | `language-kotlin` 前缀是否支持 | 方案没要求；支持就偏离 pi | 不支持，保持源码（夹具已按此记录） |
| 6 | ` ```mermaid title="…" ` 的 title 是否显示为图题 | 今天被忽略；SVG 阶段有机会显示 | Phase 2 再定，先不承诺 |
| 7 | 表格对齐放 Phase 1 还是 Phase 5 | 方案 Phase 1 清单没有它，§一.1 又要求「补对齐」 | 放 Phase 5（数据/专业内容）或 Phase 1 末，由你排期 |
| 8 | 围栏 diff 复用 `DiffBlock` 的边界 | 「复用行渲染」与「复用解析」是两件事 | 只复用行渲染/解析；工具卡的入口与状态完全不动 |
| 9 | CSV/TSV 是否本轮做 | 方案 §九有，Phase 列表在 Phase 5 | 按 Phase 5 排；夹具未包含（本轮清单没有要求） |
| 10 | 夹具 harness 的名字与注册 | 你统一注册 `tools/run-app-pure-checks.sh` | 建议 `rich-fixtures`：只做「注解格式合法 + id 唯一 + body 非空 + now/expect 词表」的元数据校验，不做渲染断言（渲染断言在真机） |
| 11 | 工作树里的并发 Phase 1 改动（§4.2） | 它们未提交，且直接改变「升级前」的定义 | 若在真机采集前落地，**重新基线**并更新本文 §2/§4；若先采集，则快照锁定 `c636c86` |
| 12 | 截图归档目录 | `docs/screenshots/` 现在只放 README 引用的 7 张固定图 | 建议新开 `docs/screenshots/rich-rendering/`，避免和主 README 的固定文件名冲突 |

---

## 附：本次核对用到的外部产物（未进仓库）

| 产物 | 来源 | 用途 |
|---|---|---|
| `markdown-jvm-0.7.9-sources.jar` | Maven Central | F1/F4/F5/F7/F9/F10 的行号 |
| `multiplatform-markdown-renderer(-m3)-0.45.0` sources | Maven Central（已在本机 `/tmp/jbmd`） | F2/F6/F11 的行号 |
| `grok-mermaid-0.2.3.tgz` | npm registry | F13 的支持类型清单 |
| `pi-engine.lock.json` | 本仓库 | `pi 1.3.0` → `grok-mermaid 0.2.3` |

这些都是**只读核对**，没有写进仓库，也没有参与任何构建。
