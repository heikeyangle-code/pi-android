# pi-android：模型原生输出富渲染系统升级实施方案

**基准仓库**：`heikeyangle-code/pi-android`  
**基准提交**：`c636c862770f024cb1ad962451dbefc43e2332d2`（2026-11-01）  
**目标**：在不修改模型、不增加系统提示词、不增加 Agent 工具、不改变 API 调用方式和 Agent 工作流程的前提下，增强 Android 客户端对模型已有输出格式的识别和展示能力。

本任务是客户端渲染升级，不是 Agent 架构改造。

> 子代理注意：实现前先核对当前源码，不要只信 `docs/render-inventory.md` 的旧盘点。该文档有些内容已经落后于当前代码（例如表格和 LaTeX 的状态）。以实际源码为准，并在完成时同步修订相关文档。

---

## 0. 最高优先级原则

1. 不修改模型调用链路、系统提示词、Agent 工具定义和 RPC 协议。
2. 不推翻现有 Markdown 解析器和渲染组件。
3. 不重写已经正常工作的 Diff、工具卡、图片、代码高亮、流式输出。
4. 新能力全部采用本地解析和本地渲染，默认不依赖外部服务。
5. 所有富渲染都必须保留原始文本，解析失败时回退原始内容。
6. 不允许为了增加渲染能力，让普通消息出现闪烁、空白、卡顿或内容丢失。
7. 不要把模型没有提供的信息补造出来。
8. 新增第三方库必须检查许可证、Kotlin/AGP 兼容性，并更新项目许可证清单。
9. 不要为了实现功能顺便重构不相关的界面和业务代码。
10. 所有新功能必须经过实际测试，不能仅以编译通过作为验收。

---

## 一、先保护现有资产

以下文件和模块是现有系统的基础，原则上保留。

### 1. Markdown 主链路

涉及：

- `app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt`
- `app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt`
- `app/src/main/kotlin/app/pi/ui/render/PiMarkdownTheme.kt`
- `app/src/main/kotlin/app/pi/ui/render/PiMarkdownImmediate.kt`

当前使用 JetBrains Markdown AST 和 Mike Penz Compose Markdown Renderer。

继续使用现有 CommonMark/GFM 解析能力，不更换解析器，不自己重写 Markdown 语法分析器。

保留现有：

- 标题、段落、列表、引用、粗体、斜体、删除线。
- Markdown 表格。
- 普通链接和自动链接。
- GFM Alerts：`> [!NOTE]`、`> [!WARNING]` 等。
- 块级和行内图片。
- 现有图片查看器入口和失败兜底。
- 流式消息渲染。
- 当前主题和排版系统。

特别注意：

**表格已经有专门的 `PiTable`，不是库的默认单行表格。**

当前已经实现多行单元格、列宽自适应、最低列宽、横向滚动和右侧渐隐提示，单元格有 50 行上限。不要重复开发，不要随意更改列宽算法。

表头吸顶不在本次范围内。

### 2. 代码高亮

涉及：

- `app/src/main/kotlin/app/pi/ui/render/PiCodeHighlight.kt`
- `app/src/main/kotlin/app/pi/highlight/PiNodeCodeHighlighter.kt`
- `app/src/main/kotlin/app/pi/highlight/PiHighlightClient.kt`
- `app/src/main/assets/pi-extensions/pi-highlight/`

继续使用现有 highlight.js 和 guest 高亮服务。

保留：

- 异步高亮。
- 现有缓存。
- 失败回退。
- 流式防抖。
- 语言别名处理。
- 现有代码颜色体系。

不要为了增加行号和折叠功能，重写高亮引擎。

### 3. Diff 和工具卡

涉及：

- `app/src/main/kotlin/app/pi/ui/blocks/DiffBlock.kt`
- `app/src/main/kotlin/app/pi/ui/blocks/ShellBlock.kt`
- `app/src/main/kotlin/app/pi/ui/blocks/ReadBlock.kt`
- `app/src/main/kotlin/app/pi/ui/blocks/WriteBlock.kt`
- `app/src/main/kotlin/app/pi/ui/blocks/ToolCallBlock.kt`

这些组件已经有各自的展示逻辑。

尤其 Diff 已经有行号、差异着色、上下文折叠和词级差异。不要再造一套替代 Diff。

Shell 输出目前会剥离 ANSI 转义序列，这是现有行为。不要假设 ShellBlock 能直接获得完整 ANSI 颜色信息，也不要为了增加 ANSI 显示随意改变 guest 的输出处理流程。

### 4. LaTeX 现有解析器

涉及：

`app/src/main/kotlin/app/pi/ui/render/PiLatex.kt`

这个文件有 2243 行，是已有的 LaTeX 解析和 Unicode 布局实现。

它已经具备：

- `$...$`
- `$$...$$`
- `\(...\)`
- `\[...\]`
- 常见数学符号。
- 分数、上下标、根号。
- 矩阵。
- cases。
- aligned、gather、split 等环境。
- 多行 Unicode 网格布局。
- 失败时保留原始公式。

它不是完整的 TeX 排版引擎，但也绝不是只有简单符号替换。

**禁止删除或重写 PiLatex。**

它将继续承担真正排版引擎不可用、公式解析失败或流式阶段的回退职责。

### 5. Mermaid 现有实现

涉及：

- `app/src/main/kotlin/app/pi/ui/render/PiMermaid.kt`
- `app/src/main/kotlin/app/pi/highlight/PiNodeMermaidRenderer.kt`
- `app/src/main/kotlin/app/pi/highlight/PiMermaidMemo.kt`
- `app/src/main/kotlin/app/pi/highlight/PiHighlightClient.kt`
- `app/src/main/assets/pi-extensions/pi-highlight/mermaid.ts`
- `app/src/main/assets/pi-extensions/pi-highlight/service.ts`

当前实际实现是：

模型 Mermaid 源码 → guest 的 `grok-mermaid` → 本地回环服务 `/mermaid` → Kotlin 解析 ASCII 图形 → Compose 着色显示。

这不是 Android WebView 渲染。

现有缓存、错误区分、重试、模式设置和源码回退都需要保留，直到新方案完全稳定。

---

## 二、建立统一的 Rich Block 渲染管线

不要继续在 `PiCodeSurface` 中无限增加 `if/else`。

增加一个统一的内容识别与渲染分发层。

建议新增包：

`app/src/main/kotlin/app/pi/ui/render/rich/`

建议结构：

- `RichFenceInfo.kt`：代码围栏语言和附加属性解析。
- `PiRichBlockRegistry.kt`：富内容类型注册与分发。
- `PiRichRenderCache.kt`：富渲染缓存。
- `PiSvgRenderEngine.kt`：统一 SVG 渲染服务。
- `PiSvgSanitizer.kt`：SVG 安全校验。
- `PiSvgDrawable.kt`：Compose 矢量绘制。
- `PiJsonTree.kt`：JSON 树。
- `PiXmlTree.kt`：XML 树。
- `PiYamlTree.kt`：YAML 树。
- `PiHtmlParser.kt`：安全 HTML 解析。
- `PiHtmlRenderer.kt`：HTML Compose 展示。

实际文件可以根据现有代码结构调整，不要求机械地创建所有文件。

目标结构：

```text
模型原始消息
    │
    ▼
现有 PiMarkdownText
    │
    ▼
现有 JetBrains Markdown AST
    │
    ├── 普通文本 → 现有 Markdown 组件
    │
    ├── 表格 → 现有 PiTable
    │
    ├── 图片 → 现有图片组件
    │
    ├── 公式 → 数学公式渲染
    │
    ├── 裸 HTML → 安全 HTML 渲染
    │
    └── 代码围栏 → Rich Block Registry
                       │
                       ├── 普通代码 → 现有高亮组件
                       ├── Mermaid → SVG
                       ├── SVG → 安全矢量展示
                       ├── JSON → 树状视图
                       ├── XML → 树状视图
                       ├── YAML → 树状视图
                       ├── CSV/TSV → 数据表
                       ├── Diff → 现有 Diff 能力
                       └── 其他类型 → 原始代码块
```

分发规则：

- 只根据明确的语言标识和确定的 AST 节点进行识别。
- 不允许把普通代码误识别为图表。
- 不认识的语言保持现有代码块显示。
- 解析失败保持原始代码块显示。
- 富渲染不修改 transcript 中保存的原始内容。
- 复制操作默认复制原始源码，而不是复制转换后的视觉结果。

---

## 三、代码围栏升级

修改：

`PiMarkdownComponents.kt` 中的 `PiCodeSurface` 和相关代码块组件。

当前代码块已经有语言标签、复制和高亮，但缺少一些开发者常用的展示能力。

### 1. 解析代码围栏附加属性

支持识别：

```text
```typescript title="src/main.ts"
```

```text
```kotlin filename="MainActivity.kt"
```

```text
```python file="scripts/main.py"
```

解析规则：

- 第一个有效字段作为语言。
- `title`、`filename`、`file` 作为可选文件名。
- 属性值支持引号和空格。
- 不执行任何属性内容。
- 未识别属性不得导致整个语言识别失败。
- 原始 info string 必须保留。

特别注意：现有 `PiCodeLanguage.normalize()` 的处理方式需要检查。不能直接把 `ts title="src/main.ts"` 整串当作 highlight.js 语言名。

### 2. 增加代码块展示能力

增加：

- 行号开关。
- 长代码块折叠。
- 展开全部。
- 自动换行 / 横向滚动切换。
- 文件名显示。
- 复制源码。
- 复制成功反馈。

建议：

- 短代码块默认完整显示。
- 长代码块提供折叠入口。
- 不要给每个代码块塞满按钮。
- 行号、换行等控制尽量放入紧凑菜单。
- 代码内容继续使用现有等宽字体和高亮结果。
- 大代码块必须有行数和字节上限。

行号不能改变实际源码，也不能影响复制结果。

### 3. Markdown Diff 围栏

支持识别：

```text
```diff
```

如果能安全复用现有 Diff 的解析和行渲染逻辑，就复用。

不要复制 `DiffBlock.kt` 的整套逻辑另写一个版本。

工具调用产生的现有 Diff 卡片完全不动。

---

## 四、Mermaid：升级为真正的图形

这是本次最重要的视觉升级之一。

当前 ASCII 框线图在手机上占宽、不能缩放，应该升级。

### 1. 引擎

优先使用官方 Mermaid.js，固定明确版本，作为 App 本地资源打包。

不使用远程 Mermaid 服务，不依赖互联网，不增加 Agent 工具。

考虑使用：

- 本地 Mermaid.js。
- Android WebView 离屏执行。
- 输出 SVG。
- AndroidSVG 解析和绘制。
- Compose Canvas 展示。

现有 `androidx.webkit` 已在依赖中，但仍需实际验证离屏 WebView 的创建、生命周期和异步执行。

### 2. 统一 SVG 管线

Mermaid 不要单独实现一套 WebView 管理系统。

与后面的 MathJax 共用一个 SVG 渲染服务。

WebView 只负责：

```text
输入源码 + 主题参数
       ↓
本地 JS 引擎
       ↓
生成 SVG
       ↓
返回 SVG 字符串
```

WebView 不放在每一条消息里面，也不放进 LazyColumn 的每个列表项中。

使用一个共享渲染宿主和串行任务队列，避免多个 WebView 同时初始化。

### 3. Mermaid 必须支持的类型

至少测试：

- Flowchart 流程图。
- Sequence Diagram 时序图。
- Class Diagram 类图。
- State Diagram 状态图。
- Entity Relationship Diagram。
- Gantt 甘特图。
- Mindmap 思维导图。
- Pie 饼图。
- Git Graph。
- Timeline。
- Quadrant Chart。
- Sankey。

不允许只测试一个简单流程图就宣布 Mermaid 已完成。

### 4. 展示交互

实现：

- 自适应宽度。
- 等比例缩放。
- 双指缩放。
- 拖动平移。
- 一键适应屏幕。
- 一键复制源码。
- 源码 / 图形切换。

深色、浅色主题必须跟随 App 当前主题。

### 5. 安全

Mermaid 配置至少要求：

- `securityLevel: "strict"`。
- `htmlLabels: false`。
- 禁止外部资源加载。
- 不允许任意脚本执行。
- 不允许图表通过外部链接加载内容。
- SVG 生成后经过安全校验。

### 6. 回退

保留现有 `grok-mermaid` 路径，作为迁移期间的次级回退。

新 Mermaid SVG 成功后优先显示 SVG；新引擎失败时，可以尝试现有 ASCII；仍然失败则显示原始 Mermaid 源码。

确认新引擎稳定、全部调用方迁移完成之后，才考虑删除旧的 ASCII 主路径和 `/mermaid` 路由。

不要先删后补。

---

## 五、LaTeX：从 Unicode 近似升级为真正排版

目标不是推翻 PiLatex，而是在其上增加真正的数学排版能力。

### 1. 引擎选择

实际比较 MathJax 和 KaTeX。

重点比较：

- 复杂公式覆盖率。
- 矩阵、cases、aligned、gather。
- 上下标和分数。
- 宏支持。
- 中文混排。
- 渲染速度。
- 输出 SVG 的兼容性。
- 本地资源体积。

优先考虑 MathJax 的 SVG 输出能力，若 KaTeX 在现有公式测试集上表现更好，也可以采用 KaTeX。

必须将引擎和字体资源本地打包。

不允许运行时从 CDN 下载。

### 2. 行内公式和块级公式都要支持

不能只处理块级公式。

例如：

```text
爱因斯坦质能方程是 $E=mc^2$。
```

必须在普通段落中正确排版。

块级公式：

```text
$$
\begin{aligned}
E &= mc^2 \\
F &= ma
\end{aligned}
$$
```

必须保留多行结构和对齐关系。

### 3. 接入方式

当前 `PiMarkdown.kt` 通过 `PiLatex.prepare()` 在 Markdown 解析之前处理公式。

当前公式写回源文本的方式会带来 Markdown 二次解析风险。

升级时不要继续把真正公式转换成普通 Unicode 文本再交给 Markdown。

应当让公式作为独立内容进入渲染：

- 块级公式接入现有 `piCustomComponent` / `PiFormulaText` 路径。
- 行内公式通过 Markdown annotator 和 `InlineTextContent` 或等效占位机制实现。
- `\(...\)`、`\[...\]` 应继续得到支持。
- 公式内容不能被 Markdown 再次解释。
- 不允许每个行内公式单独启动 WebView。

同一条消息中的公式应尽可能批量提交给共享 SVG 渲染服务。

### 4. 流式处理

流式中：

- 公式定界符未闭合：显示原文或现有 PiLatex 近似结果。
- 定界符闭合、内容停止变化后：异步请求真正排版。
- 成功后替换显示。
- 失败时保留 PiLatex 输出。
- 不允许空白闪烁。
- 不允许因公式失败导致整条消息消失。

### 5. 保留 PiLatex

`PiLatex.kt` 不删除。

它继续承担：

- 流式阶段回退。
- MathJax/KaTeX 失败回退。
- 不支持的宏和公式回退。
- WebView 不可用时的本地显示。

保留 `PiLatexCheck.kt` 的既有测试，并增加真正排版的对照测试。

---

## 六、裸 HTML：安全子集渲染

当前：

- `PiHtml.kt` 负责识别和保留 HTML 原文。
- `PiMarkdown.kt` 的 `piInlineHtmlAnnotate` 处理行内 HTML。
- `PiMarkdownComponents.kt` 的 `PiHtmlBlock` 处理块级 HTML。

现在是按原文展示，不是 HTML 排版。

本次增加安全子集，但必须保留原文回退能力。

### 1. 支持的标签

块级：

- `div`
- `p`
- `details`
- `summary`
- `blockquote`
- `ul`
- `ol`
- `li`
- `table`
- `thead`
- `tbody`
- `tr`
- `th`
- `td`
- `pre`
- `hr`
- `figure`
- `figcaption`

行内：

- `strong`
- `b`
- `em`
- `i`
- `del`
- `s`
- `mark`
- `u`
- `code`
- `kbd`
- `sup`
- `sub`
- `span`
- `br`
- `a`
- `img`

不要求支持任意 HTML 和 CSS。

### 2. details / summary

例如：

```html
<details>
  <summary>点击查看详细解释</summary>
  <p>这里是补充说明。</p>
</details>
```

转换成 Compose 折叠组件。

默认遵循 HTML 的折叠行为，支持嵌套和独立展开状态。

### 3. 安全策略

可以使用 jsoup 解析 HTML，但 jsoup 本身不等于安全策略。

必须自行建立标签、属性和 URL 协议白名单。

禁止执行：

- script。
- 任意 JavaScript。
- iframe。
- object。
- embed。
- form。
- 任意事件属性，例如 `onclick`、`onerror`。
- 任意 CSS 注入。
- `javascript:` URL。
- 未经允许的外部资源。

链接只允许经过校验的 `https`、`http`、`mailto` 等明确许可协议。

图片必须走现有图片资源处理链路，不允许 HTML 自己读取任意本地文件。

### 4. 最重要的内容保留原则

不能像简单过滤器一样直接把未知内容全部删除。

处理规则：

- 安全且认识的标签：转换为 Compose。
- 不认识的标签：安全降级。
- 危险标签：绝不执行。
- 无法安全解析的 HTML 块：保留完整源码，作为普通文本或代码样式展示。
- 所有情况下都不能静默丢失模型输出。

`PiHtmlCheck.kt` 现有字节级测试不能删除。保留原始回退路径的测试，另外建立安全渲染测试。

---

## 七、JSON / XML / YAML 树状视图

新增原生 Compose 组件。

不要使用 WebView 展示这些数据结构。

### 1. JSON

支持：

- 对象折叠。
- 数组折叠。
- 字符串、数字、布尔、null 区分颜色。
- 层级缩进。
- 复制键名。
- 复制值。
- 复制完整 JSON 路径。
- 原始代码 / 树状视图切换。

使用项目已有的 `kotlinx.serialization.json`，不必重复引入 JSON 解析库。

### 2. XML

支持：

- 节点树。
- 属性展示。
- 文本节点。
- 折叠和展开。
- 复制节点路径。
- 原始 XML / 树状视图切换。

必须防止 XML 外部实体攻击，禁用 DTD 和外部实体解析。

### 3. YAML

支持：

- Map。
- List。
- Scalar。
- 层级折叠。
- 原始 YAML / 树状视图切换。

必须使用安全加载方式，禁止任意类型实例化和危险自定义标签。

### 4. 显示规则

识别明确的 `json`、`xml`、`yaml`、`yml` 围栏。

不要把所有普通文本都猜成 JSON。

树状视图不能取代原始源码视图。

默认可以显示代码视图，用户主动切换树状视图，避免开发者失去源码阅读体验。

限制最大深度、节点数量和输入长度。超限退回代码块。

---

## 八、SVG 支持

新增 `svg` 代码围栏渲染：

```text
```svg
<svg viewBox="0 0 400 200">
  ...
</svg>
```

支持：

- 本地 SVG 解析。
- 等比例显示。
- 缩放。
- 平移。
- 适应屏幕。
- 源码切换。
- 复制源码。

优先评估 AndroidSVG。

但必须实际测试 Mermaid.js 和 MathJax 输出的 SVG 是否能被 AndroidSVG 正确绘制，包括 path、defs、use、marker、clipPath、gradient 和 style。

如果有不兼容，先解决 SVG 转换兼容问题，不允许悄悄丢失图形。

SVG 安全规则：

- 禁止脚本。
- 禁止事件属性。
- 禁止外部文件引用。
- 禁止任意网络请求。
- 禁止访问本地文件。
- 禁止 `foreignObject`。
- 限制 SVG 大小、节点数量、嵌套深度和画布尺寸。
- XML 解析禁用外部实体。

原始 SVG 无法安全渲染时，回退源码。

---

## 九、CSV / TSV 数据表

增加对明确标注的 `csv`、`tsv` 代码围栏的识别。

转换为原生 Compose 数据表。

支持：

- 正确处理带引号的逗号。
- 正确处理字段内部换行。
- 表格横向滚动。
- 列宽自适应。
- 表头区分。
- 单元格复制。
- 原始文本 / 数据表切换。

不要使用简单的 `split(",")` 处理 CSV，因为这会破坏带引号的字段。

数据过大时限制行数和列数，保留原始代码回退。

---

## 十、开发者专用内容增强

这一部分针对 Pi Coding Agent 的实际工作场景。

### 1. Stack Trace

识别常见异常：

- Java / Kotlin Stack Trace。
- JavaScript / TypeScript Stack Trace。
- Python Traceback。
- Rust Panic。
- Go Panic。

展示：

- 异常类型。
- 异常信息。
- 文件名。
- 行号。
- 调用栈层级。

文件路径和行号只有在能可靠映射到当前工作区时才提供点击跳转。

不能仅凭字符串猜测文件位置。

### 2. 文件路径

识别模型明确输出的本地项目路径。

在路径可验证的情况下：

- 文件名高亮。
- 行号识别。
- 点击打开项目文件。
- 路径复制。

不允许把普通 URL 当成本地文件路径。

### 3. 终端输出

现有 ShellBlock 的输出处理保持不变。

ANSI 颜色不作为本次 Shell 输出的改造目标，因为上游已经会剥离 ANSI。

如果以后支持显式 `ansi` 代码围栏，可以单独实现 SGR 颜色解析，不得改变当前 Shell 结果数据。

---

## 十一、脚注、标题锚点和链接

### 1. 脚注

支持常见脚注语法：

```markdown
这里是一段内容[^1]。

[^1]: 这是脚注解释。
```

实现：

- 正文上标引用。
- 点击跳转到脚注。
- 脚注返回正文。
- 多条脚注编号。
- 未定义脚注原文回退。

JetBrains GFM 不应被假设天然支持脚注，需要独立实现或采用经过验证的扩展。

### 2. 标题锚点

支持：

- 标题生成稳定 ID。
- Markdown 内部 `#section` 链接。
- 点击目录项定位标题。
- 多个相同标题不冲突。

不改变现有标题视觉风格。

### 3. 链接预览

当前 Markdown 链接已经可点击。

可以增加轻量级的域名和链接类型预览，但本次不做自动 OG 抓取，不增加外部网络请求。

链接卡片只能展示 URL 本身可以确定的信息，不得伪造标题、摘要和图片。

---

## 十二、其他图表语言：先建立扩展能力，再按需增加

不要把所有 DSL 一次性打包进 App。

先让 `PiRichBlockRegistry` 支持以后接入不同渲染器。

后续可以增加：

| 格式 | 用途 | 建议 |
|---|---|---|
| Vega-Lite | 数据图表 | 优先考虑 |
| Graphviz / DOT | 关系图、依赖图 | 可增加 |
| WaveDrom | 数字电路时序图 | 可增加 |
| PlantUML | UML 图 | 后续评估 |
| D2 | 软件架构图 | 后续评估 |
| BPMN | 业务流程图 | 后续评估 |

这些格式全部使用本地引擎或本地解析器。

没有可靠本地实现时，不要调用在线渲染服务。

第一轮不要为了支持小众格式增加大量二进制、JAR 或 WASM 资源。

---

## 十三、统一 SVG 渲染服务的工程要求

这是 Mermaid 和 MathJax 共用的核心基础设施。

建议使用一个共享的本地 WebView 渲染宿主。

### 1. 资源加载

- 使用本地 Assets。
- 优先通过 `WebViewAssetLoader` 提供本地资源。
- 不从 CDN 加载 JS、CSS、字体。
- 不允许外部网络访问。
- 不允许 WebView 导航到外部页面。
- 禁止不必要的文件访问和内容访问。
- 不使用任意不可信 JavaScript Bridge。

### 2. 线程与任务管理

WebView 的生命周期和执行线程必须正确处理。

要求：

- WebView 操作遵守主线程要求。
- JS 执行采用异步回调。
- 禁止在主线程等待渲染结果。
- 不使用 `runBlocking` 阻塞 Compose。
- 不在 LazyColumn 中创建 WebView。
- 渲染请求进入有界队列。
- 同一源码的重复请求合并。
- 已经失效的任务可以取消。
- 过期任务的返回结果必须丢弃。
- 渲染超时后及时回退。
- WebView 崩溃或被系统回收后能够重建。

建议先实现 Mermaid 的最小原型，再把 MathJax 接进同一套服务。

### 3. 缓存

缓存键必须包含必要的信息：

- 源码。
- 引擎版本。
- 渲染类型。
- 主题。
- 影响布局的尺寸参数。

使用有上限的 LRU。

同时限制：

- 最大缓存条数。
- 最大缓存字节数。
- 单份 SVG 最大尺寸。
- 单个渲染任务最长执行时间。

不能只限制条数而不限制总字节数。

现有 `PiMermaidMemo` 的有界缓存设计可以参考或复用，不要重新造一套没有上限的缓存。

### 4. 主题

Mermaid 和公式输出必须适配：

- 深色主题。
- 浅色主题。
- 系统字体缩放。
- 不同屏幕宽度。

主题切换后不得继续显示错误颜色的旧缓存。

---

## 十四、流式渲染：不能破坏现有体验

这是本项目的重点，必须单独验收。

当前 `PiMarkdownText` 使用累计文本，并且已有解析状态保留、非流式首次布局预算、代码高亮防抖和 Mermaid 缓存等机制。

本次不要为了增量解析，贸然引入新的 transcript delta 通道。

也不要直接采用 Markdown Renderer 的 `StreamingMarkdownState`，因为它需要上游数据结构配合，属于更大的架构改造。

新渲染器统一遵守以下规则：

1. 流式期间未闭合的代码围栏保持普通源码。
2. 未闭合的数学公式保持原文或现有 PiLatex 近似结果。
3. 未闭合 HTML 不提前执行或解析为交互组件。
4. JSON 不完整时不切换为树状视图。
5. 只有格式完整、解析成功，才允许显示富内容。
6. 现有 200ms 防抖可以作为初始策略，但它不等于内容一定完整，必须结合结构完整性判断。
7. 消息结束后允许对最终内容重新解析。
8. 新内容到达时取消旧渲染任务。
9. 旧任务晚到的结果不得覆盖新内容。
10. 渲染失败不能影响整条消息。
11. 不允许每个 token 都启动一次 Mermaid、MathJax 或 SVG 渲染。
12. 不允许因为富渲染导致现有自动跟随、滚动、行高缓存失效。

特别注意：当前 `PiMarkdownText` 已经存在整篇 Markdown 重解析的成本。不要再叠加每 token 多次昂贵渲染。

---

## 十五、资源限制和安全验收

初始限制可以从以下量级开始，最终根据真机测试调整：

| 内容 | 初始限制建议 |
|---|---:|
| Mermaid 源码 | 64 KiB |
| 单个 SVG | 2 MiB |
| HTML 块 | 256 KiB |
| JSON/XML/YAML | 256 KiB |
| 树状结构最大深度 | 64 |
| 树状结构最大节点数 | 20,000 |
| 单个 HTML 树最大深度 | 64 |

超出限制不崩溃、不冻结，回退源码。

必须测试：

- 恶意 HTML。
- 恶意 SVG。
- 危险 URL。
- XML XXE。
- YAML 危险标签。
- 超长字符串。
- 极深嵌套。
- Mermaid 极大节点图。
- 不完整公式。
- 不闭合代码围栏。
- WebView 渲染超时。
- WebView 进程异常。
- 快速滚动造成的任务取消。
- 主题切换造成的缓存失效。

---

## 十六、实施顺序

按下面顺序直接实施，不要并行大规模改动同一批核心文件。

### Phase 0：建立基线

先固定当前正常行为。

使用现有测试：

- `PiLatexCheck.kt`
- `PiHtmlCheck.kt`
- `PiMermaidMemoCheck.kt`
- `tools/run-app-pure-checks.sh`

记录现有截图、构建结果、渲染时间和 APK 大小。

不要改现有测试的预期结果来迎合新代码。

### Phase 1：基础增强

先完成：

1. Rich Block Registry。
2. 代码围栏语言和属性解析。
3. 文件名显示。
4. 代码行号开关。
5. 长代码块折叠。
6. 自动换行 / 横向滚动切换。
7. JSON 树状视图。
8. XML 树状视图。
9. 安全 YAML 树状视图。
10. 任务列表只读勾选标记。

这一阶段尽量不引入新重型依赖。

### Phase 2：SVG 基础设施与 Mermaid

1. 建立共享本地 WebView 渲染宿主。
2. 接入 Mermaid.js。
3. 转换成 SVG。
4. 接入 AndroidSVG。
5. 实现缩放、平移和适应屏幕。
6. 实现渲染缓存和任务取消。
7. 深色/浅色主题适配。
8. 测试全部主要 Mermaid 图类型。
9. 新旧 Mermaid 路径对照。
10. 新引擎稳定之后才考虑清理旧 ASCII 主路径。

### Phase 3：真正的数学排版

1. 比较 MathJax 和 KaTeX。
2. 接入本地 JS 引擎。
3. 块级公式 SVG 渲染。
4. 行内公式 SVG 渲染。
5. 公式基线和行高适配。
6. 矩阵、aligned、cases 等复杂公式测试。
7. 流式阶段保留 PiLatex fallback。
8. 失败自动回退。
9. 保留全部 PiLatex 原有测试。

### Phase 4：HTML 和 SVG

1. jsoup 解析 HTML。
2. HTML 安全白名单。
3. details/summary Compose 折叠。
4. 行内 HTML 富文本。
5. HTML 表格和列表。
6. HTML 图片复用现有图片通道。
7. 独立 SVG 安全解析。
8. Mermaid、MathJax 和普通 SVG 共用最终绘制管线。
9. 未知或危险内容安全回退原文。

### Phase 5：数据和专业内容

1. CSV/TSV 数据表。
2. Markdown 脚注。
3. 标题锚点。
4. 轻量链接预览。
5. Stack Trace 识别。
6. 可验证的项目文件路径跳转。
7. Vega-Lite 等图表语言评估。

### Phase 6：完整验收

完成真机测试、性能测试、资源测试、离线测试、构建测试和许可证清单。

---

## 十七、测试和验收标准

### 功能验收

- 同一份 Markdown 内容在升级前后，原有基础格式不能退步。
- 表格、图片、Diff、代码高亮继续正常。
- 新增格式识别准确。
- 不支持的格式保持源码。
- 富渲染失败不丢内容。
- 复制操作得到原始源码。
- Mermaid 主要图类型能够正常绘制。
- 行内和块级公式均有真正排版结果。
- HTML 安全子集正确工作。
- JSON/XML/YAML 树状视图能够折叠、展开、复制。
- SVG 缩放和平移正常。
- 深色和浅色主题正常。

### 流式验收

模拟以下输入：

- Mermaid 逐步生成。
- 公式逐步生成。
- JSON 逐步生成。
- HTML 逐步生成。
- 生成到一半中断。
- 快速连续更新。
- 用户滚动离开消息后再滚回来。
- 渲染完成前切换会话。

要求：

- 不显示过期渲染结果。
- 不出现整块空白。
- 不出现反复闪烁。
- 不抢用户滚动。
- 不影响最新消息跟随。
- 不因为渲染失败导致消息内容消失。

### 性能验收

- 普通 Markdown 消息不启动 WebView。
- 不为每个代码块创建 WebView。
- 复杂渲染不阻塞主线程。
- 长消息滚动不能因为富渲染明显卡顿。
- 所有缓存都有明确上限。
- 渲染任务有超时、取消和错误恢复。
- 实测内存、CPU、APK 增量，不凭估算报告数字。

### 离线验收

打开飞行模式：

- Markdown 正常。
- 代码高亮正常。
- Mermaid 正常。
- 数学公式正常。
- JSON/XML/YAML 正常。
- SVG 正常。

这些渲染引擎的静态资源必须已经随 App 安装。

### 构建和许可证

- 不随意升级 AGP。
- 不随意升级 Kotlin。
- 新依赖必须兼容当前 Kotlin 2.2.21、AGP 8.13.2 和现有 Compose 环境。
- 更新 `gradle/libs.versions.toml`。
- 更新 `app/build.gradle.kts`。
- 更新 `app/src/main/assets/licenses/` 中的依赖许可证资料。
- 更新组件清单和第三方许可证声明。
- 最终构建真实 APK，记录体积变化。

---

## 十八、最终交付要求

完成后提供：

1. 修改文件清单。
2. 新增文件清单。
3. 每个新组件的职责。
4. 新增依赖和许可证。
5. Mermaid 实际支持的图类型。
6. MathJax/KaTeX 最终选择及对照结果。
7. HTML 白名单和安全策略。
8. SVG 安全策略。
9. 缓存和任务管理策略。
10. 流式输出测试结果。
11. 原有测试通过结果。
12. 新增测试通过结果。
13. 真机截图。
14. APK 实际增量。
15. 仍然不支持的格式和明确原因。

不要只给一份实施报告或设计稿，最终必须落到实际代码。

**最终验收原则：模型一字不改，提示词一字不加，Agent 一项工具不增加，原有功能不退步；仅通过增强 Android 客户端，让模型原本能够输出的内容得到更丰富、更准确、更专业的展示。**

---

## 附：当前仓库事实核对（以 `c636c862` 为准）

| 模块 | 当前真实情况 | 决策 |
|---|---|---|
| Markdown/GFM | JetBrains Markdown + Compose 渲染器，已有完整主链路 | 保留 |
| 表格 | 已有 `PiTable`，自适应列宽、横滑、多行单元格，上限 50 行；但列对齐未实现 | 不重做，补对齐 |
| GFM 提示框 | `> [!NOTE]` 等已支持 | 不重复开发 |
| 图片 | 已有块级/行内图片、查看器入口和失败兜底 | 保留 |
| 代码高亮 | 已有 guest highlight.js 服务、缓存、异步高亮 | 保留 |
| Diff | 已有独立 Diff 组件、行号、上下文折叠、词级差异 | 保留 |
| LaTeX | `PiLatex.kt` 支持 `$`、`$$`、`\(...\)`、`\[...\]`，矩阵和 aligned 能排 Unicode 网格 | 保留，增加真正排版 |
| Mermaid | ASCII 框线图，由 guest `grok-mermaid` 经本地回环渲染 | 升级为 SVG；旧路径保留为回退 |
| HTML | 裸 HTML 当原文显示 | 增强安全子集，保留原文回退 |
| 任务清单 | `[x]`、`[ ]` 仍是文本 | 改为只读勾选标记 |
| JSON/XML/YAML | 主要是代码块 | 增加树状视图 |
| SVG | 没有完整安全展示管线 | 新增 |

### 已核对的解析器事实

- `org.jetbrains:markdown 0.7.9` 的 `FENCE_LANG` 是**完整 info string**，`MarkdownCodeFence` 整串交给回调；当前 `PiCodeLanguage.normalize()` 也整串 lowercase，所以 `ts title="..."` 会被当成语言名。Phase 1 必须先修语言提取。
- `GFMTokenTypes.CHECK_BOX` 存在，任务清单解析器支持；App 没有覆盖 `components.checkbox`。
- 表格对齐只存在于解析器的 HTML 生成器；AST 不保存 alignment，Compose 渲染器也不使用；当前 `PiTable` 没有解析 `TABLE_SEPARATOR`。
- 0.7.9 没有脚注节点；脚注必须独立预处理或扩展。
- 公式只认 `$`/`$$`；`\(...\)`、`\[...\]` 没有节点。
- `AssistantText.streaming` 目前只用于光标，`PiMarkdownText` 没有 streaming/settled 参数。
- `PiMarkdownText` 当前每次内容变化会走整篇解析；不要在此基础上叠加每 token 的整篇富渲染分类。

---

## 附：额外判断

优先完成五件事：

1. Mermaid 真图 SVG。
2. MathJax 真正数学排版，包括行内和块级。
3. HTML 安全子集，尤其 `details/summary`。
4. JSON/XML/YAML 树状查看。
5. 代码块文件名、行号、折叠和换行控制。

最容易做坏的是数学公式和 Mermaid 的异步渲染。先把共享渲染引擎做好，再增加图形类型。

不要一次性实现 PlantUML、D2、WaveDrom 等；它们可以后续扩展。
