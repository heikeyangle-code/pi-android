# Phase 2/3 引擎选型研究：Mermaid 与数学排版

**状态**：研究文档，**不改生产代码、不加依赖、不跑 Gradle/Android 构建、不 commit**。
**对应方案**：`docs/plans/rich-rendering-upgrade.md` 第四节（Mermaid）、第五节（LaTeX）、第八节（SVG）、第十三节（统一 SVG 渲染服务）、第十五节（资源限制）。
**产出边界**：本文只回答"选什么、怎么打、风险在哪、什么必须真机验"；**不承诺工期，不估算 APK 增量**（增量只能由真实构建测出，见 §5）。

---

## 0. 结论摘要

1. **Mermaid 版本**：建议固定 `mermaid@12.2.0`（2026-10-13），备选 `11.17.2`（2026-08-25，11.x 末版）。两者都是 MIT。选 12 的理由是它是最新的稳定线；保留 11 的理由是 12 才发布不到两个月、且要求打包机 Node ≥ 22.12（构建期约束，不是运行期）。**待验证**：12.2.0 生成的代码在最低支持设备的 WebView 上能否跑通（§5）。〔已核实：版本与发布时间〕
2. **Mermaid 打包**：单文件 UMD `mermaid.min.js` 5,494,801 B（gzip -9 后 1,568,811 B）。UMD 包内含全部图类型依赖（`elk` 611 次、`venn` 149 次、`chevrotain` 56 次、`cytoscape` 18 次字符串命中），所以**一个文件就能覆盖全部图类型**，不需要 chunk 目录。ESM 形态（入口 30,936 B + 104 个 chunk 共 5,363,101 B）只有在需要懒加载时才值得，本方案不需要。〔已核实：npm 包文件清单 + 本机 gzip〕
3. **离屏 WebView**：`securityLevel`/`htmlLabels` 这些配置在 JS 侧没问题，真正的工程风险全在宿主：WebView 必须在**创建它的那个线程**（通常主线程）上创建与销毁，销毁后**任何方法都不能再调**；`onRenderProcessGone` 返回 `true` 才不崩，返回后该实例必须整体丢弃重建。离屏不 attach 的 WebView 能否给 mermaid 提供正确的 `getBBox` 文本度量，**只能真机验证**，这是 Mermaid 方案的第一号技术风险。〔已核实：Android 官方 API 文档原文；待验证：离屏布局〕
4. **Mermaid 输出 → AndroidSVG**：`path`/`defs`/`use`/`marker`/`clipPath`/`gradient`/`style` 这些**语法层面都支持**（AndroidSVG 官网"完全支持"清单包含 `clipPath`/`defs`/`marker`/`use`/`linearGradient`/`path`），但**语义层面有四个已核实的坑**：
   - `dominant-baseline` / `alignment-baseline` 在 AndroidSVG 1.4 与 master 源码里**完全不存在**（1.4 jar 字节串命中数 0），sequence / timeline / quadrant 图会用到 → 文字垂直居中失效；
   - 根 `<svg width="100%">`（mermaid 每张图都这么输出）在 AndroidSVG 里被当成 100px 的绝对宽度，且 `ex`/`em`/`%` 在根尺寸路径上**不做换算**（源码 `floatValue(dpi)` 直接返回数值）→ 必须重写根尺寸或显式给 viewport；
   - `<style>` 里出现 `[data-look="neo"]` 属性选择器，AndroidSVG 只支持 `[id]`/`[class]`，其余属性选择器整条规则丢弃；
   - `!important` 会被解析但**被忽略**（源码注释 `// We don't do anything with these. We just ignore them.`），`@keyframes` 走 `skipAtRule` 跳过不致命，但**一个 CSSParseException 会终止后面所有规则**。风险排序与缓解见 §2.6。
5. **数学排版**：**不宣布 MathJax 或 KaTeX 谁赢**。已核实的事实是两者输出形态根本不同：MathJax 有原生 SVG 输出（`tex-svg.js`，自包含 `<defs>`+`<use>`+内联 path），KaTeX **没有 SVG 输出**（官方 `output` 选项只有 `html`/`mathml`/`htmlAndMathml`）。在"统一走 AndroidSVG"这个前提不变的情况下，KaTeX 需要额外一层 HTML→SVG 或 MathML→Compose 的转换，这属于新架构，不在原型范围。评测语料与判定标准见 §3.4/§3.5。
6. **体积**（本机实测字节数，非 APK 增量）：Mermaid 5.49 MB（gz 1.57 MB）；MathJax `tex-svg.js` 1.85 MB（gz 615 KB）；KaTeX `katex.min.js` 273 KB（gz 76 KB）+ `katex.min.css` 24.8 KB + 字体 68 个文件共 1,160,134 B（仅 woff2 20 个文件共 259,792 B）。**这些都不等于 APK 增量**——asset 压缩、AAB 分发、符号表都会改变结果，必须真实构建测量。
7. **许可证**：Mermaid MIT、MathJax Apache-2.0、KaTeX MIT、AndroidSVG Apache-2.0、jsoup MIT。但**打包 mermaid 等于打包它的 23 个运行期依赖**，其中 `elkjs` 是 `EPL-2.0 OR GPL-3.0-or-later`、`dompurify` 是 `MPL-2.0 OR Apache-2.0`，这是本次选型里最容易被忽略的许可面。详见 §4。
8. **必须真机/真实构建才能定的结论**：离屏 WebView 布局、渲染进程崩溃恢复、冷启动与单图耗时、内存、CJK 字体回退、AndroidSVG 实际视觉差异、APK 真实增量。**任何"文档估算"都不能替代**，清单见 §5。

---

## 1. 证据等级与实验环境

本文每条结论都标了等级：

| 标记 | 含义 | 判定标准 |
|---|---|---|
| **已核实（文档）** | 上游官方文档/规范原文 | 给了可点开的 URL，且我实际读到过原文 |
| **已核实（源码）** | 上游源码 / 发布产物里的代码路径 | 给了文件与行为，可复现 |
| **已核实（本机实验）** | 我在本机跑出来的数字/结构 | 给了脚本与命令，可复现 |
| **待验证** | 只能靠真机、真实构建或法务确认 | §5 给出验证方法与判定 |
| **未核实** | 我既没读到文档也没跑过 | 明确写出来，不当结论用 |

**本机环境**（所有"本机实验"数字的上下文）：
Linux aarch64，8 核，Node **v24.19.0**，2026-11-01/02（UTC+8）。
**这不是 ART、也不是 Android WebView**：V8 与 WebView 的 V8 版本、JIT 策略、内存上限都不同，所以**JS 侧的毫秒数只能用于排序和发现"数量级问题"，不能当手机性能**。

实验产物都在 `/tmp`（`/tmp/eval/node_modules/{mathjax,katex,mermaid}`、`/tmp/mermaid*.svg`），**没有写进仓库**，仓库依赖零变化。

复现命令见 §6。

---

## 2. Mermaid

### 2.1 版本候选

| 版本 | 发布时间 | 许可证 | 备注 |
|---|---|---|---|
| **12.2.0** | 2026-10-13 | MIT | 最新；`engines.node >= 22.12.0`（**构建期**约束） |
| 11.17.2 | 2026-08-25 | MIT | 11.x 最后一版，维护更久 |
| 10.9.8 | 2026-08-04 | MIT | 仅作对照，不建议 |

来源：npm registry `mermaid` 的 `dist-tags`/`time` 字段（`https://registry.npmjs.org/mermaid`）。

**建议：固定 12.2.0，并记录 tarball 的 `integrity`/`shasum`（当前 `shasum=8500d2500a57bd10f0c8ab03ec4deff87df36c39`），打包时校验。** 不要写 `^12` 这种范围——渲染引擎升级会改变输出 SVG，进而改变 AndroidSVG 的兼容性结论，版本必须可重现。

**为什么不是 11.x**：12.0.0 是 2026-09-10 的新大版本，到 12.2.0 只经过两个补丁版；如果真机验证发现 12 在低版本 WebView 上有语法/运行时问题，回退 11.17.2 是低成本的，因为本文的配置项（`securityLevel`、`htmlLabels`、`maxTextSize`、`secure`）在两代里同名。

**【待验证】** 12.2.0 的产物语法基线（是否用到较新的 ES 特性/`Array.prototype.at`、可选链、`structuredClone` 等）在 API 26 设备上其系统 WebView 版本能否解析。Android 8 设备的 WebView 可以被 Play 更新，但不能假设。验证方法见 §5-A。

### 2.2 打包形态与体积（已核实）

数据来源：`https://data.jsdelivr.com/v1/packages/npm/mermaid@12.2.0?structure=flat` 的文件清单 + 本机 `gzip -9`。

| 文件 | 原始字节 | gzip -9 | 说明 |
|---|---:|---:|---|
| `dist/mermaid.min.js` | 5,494,801 | 1,568,811 | **UMD，单文件，含全部图类型** |
| `dist/mermaid.js`（未压缩） | 13,026,468 | — | 不要打包 |
| `dist/mermaid.esm.min.mjs` | 30,936 | — | ESM 入口，本身很小 |
| `dist/chunks/mermaid.esm.min/*.mjs`（104 个） | 5,363,101 | — | 懒加载 chunk；最大 `elk-7HBNN7NY.mjs` 1,618,161，`katex-AYS4IGT2.mjs` 273,226 |
| `*.map` | 最大 19.5 MB | — | **绝对不要打进 assets** |

**UMD 是否真的包含全部图类型**（本机 grep `mermaid.min.js`）：

| 关键字 | 命中次数 | 结论 |
|---|---:|---|
| `elk` | 611 | ELK 布局已内联 |
| `venn` | 149 | venn 图已内联 |
| `chevrotain` | 56 | 解析器运行时已内联 |
| `cytoscape` | 18 | 图布局已内联 |
| `cose-bilkent` | 10 | 布局已内联 |
| `api.iconify` | 0 | **没有**远程取图标 |

**离线安全性（bundle 层）**：`mermaid.min.js` 里一共 35 个不重复的 URL（其中 1 个是不完整的 `http://` 前缀，忽略），其余全是三类——(a) XML/W3C/EMF 命名空间（`http://www.w3.org/2000/svg`、`http://www.eclipse.org/elk/ElkGraph` 等，其中 ELK 的 Ecore/EMF 命名空间是 elkjs 被内联的又一证据）；(b) 许可/文档链接（`opensource.org/licenses/MIT`、`chevrotain.io/docs/...`、`github.com/mermaid-js/mermaid/issues`）；(c) 维基条目。**没有任何 CDN / 字体 / 图标加载地址**（`cdn.jsdelivr`、`unpkg.com`、`fonts.googleapis` 命中数均为 0）。**待验证**：architecture 图若启用 icon pack 需显式 `registerIconPacks`（默认不带远程地址），真机仍需开飞行模式实测（§5-G）。

### 2.3 最小可用打包方案（建议，不实现）

```text
app/src/main/assets/rich-render/
├── mermaid/mermaid.min.js          # 5,494,801 B，固定 12.2.0，附 SHA-256
├── mermaid/host.html               # 空白页 + 一个 <script src>
└── mermaid/host.js                 # initialize + render 桥（几十行）
```

加载方式：`WebViewAssetLoader` 把 `https://appassets.androidplatform.net/assets/rich-render/` 映射到 assets，`loadUrl` 走 https 域名（官方推荐的本地内容加载方式，见 [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)）。**不要用 `file://`**——那要么开 `allowFileAccess`，要么在很多机型上直接不给加载，而且会把整个文件系统暴露给页面。

桥接方向建议**单向**：Kotlin → `evaluateJavascript("render(json)")`；JS → Kotlin 用 `WebMessagePort` 或 `evaluateJavascript` 的回调，**不要 `addJavascriptInterface`**（方案 §13.1 已明确"不使用任意不可信 JavaScript Bridge"）。

不要引入 npm/bundler 到 Android 构建里：mermaid 是纯前端产物，直接下载固化即可；仓库里记版本 + 校验和 + 来源 URL，和现有 `libs.versions.toml` 的"每个依赖都写清楚为什么"风格一致。

### 2.4 离屏 WebView 宿主：工程约束（逐条）

以下全部引自 Android 官方 API 文档原文（`developer.android.com/reference/android/webkit/...`）：

| 约束 | 原文/事实 | 对本方案的含义 |
|---|---|---|
| 创建线程 | WebView 构造函数："This method must be called on the thread that originally created this UI element. This is typically the main thread of your app." | 宿主必须在主线程建；不能放到 `Dispatchers.Default` |
| Application Context 限制 | "If instantiated with an Application Context, WebView will be unable to provide several features, such as JavaScript dialogs and autofill." | 用 Activity/Service 的可用 Context 更稳；但**常驻宿主**用 Activity Context 会泄漏 → 需要 `WeakReference` 或 `MutableContextWrapper` 方案【待验证：哪种在离屏场景下稳定】 |
| `destroy()` | "This method should be called after this WebView has been removed from the view system. **No other methods may be called on this WebView after destroy.** This method must be called on the thread that originally created this UI element." | 释放路径必须严格：移出视图 → destroy → 置空，绝不复用已 destroy 的实例 |
| `onRenderProcessGone` | 新增于 API 26。"Multiple WebView instances may be associated with a single render process; onRenderProcessGone will be called for each WebView that was affected... **The given WebView can't be used, and should be removed from the view hierarchy, all references to it should be cleaned up**... Returns boolean: true if the host application handled the situation... otherwise, application will crash if render process crashed, or be killed if render process was killed by the system." | 返回 `true` + 丢弃重建；`minSdk=26`（本仓库 `app/build.gradle.kts`）刚好满足。文档还给了一个测试手段：`loadUrl("chrome://crash")` |
| `evaluateJavascript` | 回调在 UI 线程；不能同步等待 | 用 `suspendCancellableCoroutine` + 超时；**禁止** `runBlocking`/`CountDownLatch` 等结果（方案 §13.2 已禁止） |
| `shouldInterceptRequest` | WebViewAssetLoader 文档："This class is expected to be used within shouldInterceptRequest, which is invoked on a different thread than application's main thread." | asset 提供方必须线程安全，不得在里面碰视图系统 |

**离屏布局（最大不确定项）**：mermaid 的布局依赖 DOM 度量（`getBBox`、`getComputedTextLength`、`getScreenCTM`）。一个**没有 attach 到窗口、没有尺寸**的 WebView，Chromium 可能给出全零的文本度量，导致节点重叠、连线穿过文字（而"不报错"）。候选做法：

1. `attach` 到一个 0 尺寸/不可见的容器（`ViewGroup` 宽高设为 1px 或 `INVISIBLE`），保证有布局 pass；
2. 手动 `measure` + `layout`（指定一个真实的渲染宽高，比如屏宽 dp）；
3. 用 `WebView.postVisualStateCallback` 等首帧后再调 JS。

**这三种都要真机试，且必须在两种设备上试（低端 API 26 + 高端）**。判据：同一份源码在桌面 Chrome 里的 SVG 与真机 WebView 里的 SVG 结构/坐标一致（§5-B）。

**任务模型**（方案 §13.2 的落地形态，供原型参考）：

```text
RichSvgRenderHost (单例, 主线程)
  ├─ 有界队列（建议容量 8，超出直接返回 ENGINE_BUSY → 回退源码）
  ├─ 串行执行：一次只有一个 render 在飞
  ├─ 每个任务带 generation: Long；结果回传时 generation != 当前 → 丢弃
  ├─ 超时（建议 3–5 s，真机调）→ cancel → 回退
  ├─ 取消：调用方 Composable 离开/内容变化 → 标记失效，不打断当前 JS（打断会污染宿主）
  └─ 崩溃：onRenderProcessGone → 标记宿主失效 → 下一个任务重建
```

### 2.5 Mermaid 安全配置

`mermaid.initialize()` 建议值（均可在 mermaid 配置 schema 里查到定义）：

```js
mermaid.initialize({
  startOnLoad: false,
  securityLevel: "strict",   // 默认就是 strict
  htmlLabels: false,         // 默认是 true —— 必须显式关掉
  theme: "default",          // 深色用 "dark"，或自建 themeVariables
  fontFamily: "<App 字体栈>",
  maxTextSize: 64 * 1024,    // schema 默认 50000，这里按方案 §15 抬到 64 KiB
  maxEdges: 500,             // schema 默认值
  suppressErrorRendering: true,  // 解析失败时不要往 DOM 里塞 "Syntax error" 图
  deterministicIds: true,    // 缓存键稳定；schema 默认 false
  logLevel: "fatal",
});
```

**已核实（文档）**：`securityLevel` 的枚举与语义来自官方 schema——`strict` 是默认，含义是"HTML tags in the text are encoded and click functionality is disabled"；`antiscript` 允许标签（script 除外）、`loose` 同时允许标签与点击，两者都比 `strict` 宽。同时 `secure` 默认值是一个白名单 `["secure","securityLevel","startOnLoad","maxTextSize","suppressErrorRendering","maxEdges"]`，含义是**这些键只能通过 `initialize()` 改，不能被图里的 `%%{init: ...}%%` 指令覆盖**。也就是说：模型输出里写 `%%{init: {"securityLevel":"loose"}}%%` **无法**把宿主降级——这条是 Mermaid 方案最重要的安全保证，建议在原型里写一条断言测试。

**已核实（本机实验）**：不设 `htmlLabels` 时，flowchart 输出里出现 **4 个 `<foreignObject>`**；设 `htmlLabels:false` 后是 **0 个**，且标签里的 `<b>` 被转义成 `&lt;b&gt;`，`<script>` 不出现在输出中。**`foreignObject` 在 AndroidSVG 里是完全不支持的**（官网"Not supported at all"清单明确列出），所以 `htmlLabels:false` 是**硬要求**，不是优化。

**还应注意**：
- `themeCSS`/`themeVariables` 如果要从模型内容或用户输入派生，必须白名单化；不要把未过滤字符串拼进 CSS。
- `htmlLabels` 的 diagram 级写法（`flowchart.htmlLabels`）在 12.x 已标记 deprecated，**用根级**（schema 原文："Diagram-specific `htmlLabels` settings (e.g., `flowchart.htmlLabels`) are deprecated. Use this root-level `htmlLabels` setting instead."）。
- 不开 `click` 回调、不注册外部 icon pack、不让页面导航（`shouldOverrideUrlLoading` 一律拦）。

### 2.6 SVG 输出 → AndroidSVG 兼容性

#### 2.6.1 AndroidSVG 的能力边界（已核实：文档 + 1.4 产物 + master 源码）

- 版本：Maven Central 只有 `1.3` / `1.4`，`1.4` 的 `lastUpdated` 是 **2019-05-28**；GitHub 仓库仍在动（最后 push 2025-06-01）。**结论：拿到的是 2019 年的二进制，而 master 上积累了多年未发布的修复**——如果真机撞到 bug，只能要么忍，要么把源码 vendor 进来自己修（Apache-2.0 允许，但增加维护面）。〔已核实：`repo1.maven.org` metadata + GitHub API〕
- 能力声明：README "almost complete support for the static visual elements of SVG 1.1 and 1.2 Tiny specifications (except for filters)"。
- **完全支持**（官网清单）：`circle` `clipPath` `defs` `desc` `ellipse` `g` `line` `linearGradient` `marker` `mask` `path` `pattern` `polygon` `polyline` `rect` `solidColor` `stop` `svg` `switch` `symbol` `title` `use` `view`。
- **有限制**：
  - `<text>`：**多值形式的 `x`/`y`/`dx`/`dy` 不支持**；"Some of the other text features, such as alignment-baseline etc, are not supported"；
  - `<style>`：简化 CSS 解析器，"supports most, but not all, of the latest CSS standard"；属性选择器只支持 `[id]`/`[class]`；
  - `<radialGradient>`：`fx`/`fy` 不支持（Android Canvas 限制）；
  - `<pattern>`：patterned stroke 不支持。
- **完全不支持**：`filter`、`foreignObject`、SMIL/CSS 动画、SVG 字体。
- 源码级确认（master 分支 + 1.4 jar 字节串）：
  - `Unit` 枚举 = `px, em, ex, in, cm, mm, pt, pc, percent`；渲染器里 `floatValueX()` 对 `ex` 走 `renderer.getCurrentFontXHeight()`，**是支持的**；但**根 `<svg>` 的 width/height 走 `floatValue(dpi)`，`em`/`ex`/`percent` 直接返回原数值**（`SVGBase.java`，`case px: case em: case ex: case percent: default: return value;`）。
  - `dominant-baseline` / `alignment-baseline` 在 `SVGParserImpl.java`/`Style.java`/`SVG.java` 里**一次都没出现**；1.4 jar 的字节串里 `dominant` 命中 0 次 → **静默忽略**。
  - `!important`：`CSSParser.java` 解析后明确"we don't do anything with these. We just ignore them. TODO"。
  - 未知 at-rule：`skipAtRule()` 注释 "Skip an unsupported at-rule: ignore everything up to and including the next semicolon or block" → `@keyframes` 不致命。
  - 解析异常：`parseRuleset` 捕获 `CSSParseException` 后 `Log.e("CSS parser terminated early due to error")` → **该条规则之后的剩余规则全部丢弃**（已解析的前面规则保留，所以症状是"部分颜色失效"而不是"全黑"）。

#### 2.6.2 Mermaid 实际输出里出现了什么（本机结构探针）

方法：jsdom + 打桩 `getBBox`/`getComputedTextLength`/`getScreenCTM`，12 类图各渲染一次，统计输出结构。**注意：几何是假的（桩返回固定值），所以这张表只能说明"出现了哪些元素/属性"，不能说明"坐标对不对"**；mindmap 需要 canvas 2d，jsdom 里直接失败。

| 图类型 | 字节 | `<style>` | foreignObject | marker | path | text | tspan | dominant-baseline | gradient | `!important` |
|---|---:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| flowchart | 11,738 | 1 | 0 | 12 | 9 | 3 | 6 | 0 | 0 | 3 |
| sequence | 23,614 | 1 | 0 | 8 | 10 | 7 | 5 | **7** | 0 | 2 |
| classDiagram | 17,101 | 1 | 0 | 20 | 23 | 3 | 7 | 0 | 0 | 22 |
| state | 24,963 | 1 | 0 | 2 | 8 | 1 | 2 | 0 | 0 | 2 |
| er | 7,973 | 1 | 0 | 8 | 9 | 3 | 6 | 0 | 0 | 4 |
| gantt | 10,115 | 1 | 0 | 0 | 1 | 19 | 1 | 0 | 0 | 12 |
| pie | 3,564 | 1 | 0 | 0 | 2 | 5 | 0 | 0 | 0 | 2 |
| gitGraph | 9,296 | 1 | 0 | 0 | 3 | 4 | 2 | 0 | 0 | 2 |
| timeline | 12,199 | 1 | 0 | 1 | 4 | 3 | 3 | **3** | 0 | 2 |
| quadrant | 5,122 | 1 | 0 | 0 | 0 | 10 | 0 | **10** | 0 | 2 |
| sankey | 4,180 | 1 | 0 | 0 | 2 | 3 | 0 | 0 | **8** | 2 |
| mindmap | — | — | — | — | — | — | — | — | — | jsdom 失败（需 canvas 2d） |

共同特征（所有类型）：

1. **根元素** `<svg width="100%" ... viewBox="-8 -8 136 56">`（宽度 100%，尺寸靠 viewBox + `style="max-width:..."`）；
2. **一个内联 `<style>`**，规则形如 `#g1 .node rect{...}`，含 2 个 `@keyframes`、若干 `!important`、以及 `[data-look="neo"]` 属性选择器；
3. **文字内容放在 `<text>` 内的 `<tspan x=.. dy=..>`**（每行一个 tspan，单值，不触发 AndroidSVG 的"多值"限制）；
4. 箭头用 `<marker>` + `marker-end`（marker 定义在 `<defs>` 内）；
5. 颜色大量依赖 CSS class（`.label text{fill:#333}`、`.arrowheadPath{fill:#333333}`），**不是**内联 `fill` 属性。

#### 2.6.3 风险清单（按严重度）

| # | 风险 | 证据 | 影响 | 缓解 | 验证 |
|---|---|---|---|---|---|
| R1 | `foreignObject` 标签 | mermaid 默认 `htmlLabels:true` 实测 4 个；AndroidSVG 明确不支持 | **整张图文字消失**（只剩框） | `htmlLabels:false`（硬性） | 原型断言输出不含 `foreignObject`（§5-D） |
| R2 | `dominant-baseline` 垂直居中 | sequence 7 处、timeline 3 处、quadrant 10 处；AndroidSVG 忽略该属性 | 文字整体偏上/偏下，actor 名称压线 | 后处理：把 `dominant-baseline="central"` 转成显式 `dy`（**需要真机测量基线差**），或接受偏移并记录 | 逐图视觉对照（§5-D） |
| R3 | 根 `width="100%"` / `width="8.7ex"` | AndroidSVG 根尺寸路径不换算 percent/em/ex | 图被压成 100px 宽或 8px 宽 | 解析前重写根 `width`/`height`（按 viewBox 长宽比 + 目标宽度），或用 `RenderOptions.setViewPort` 显式给尺寸 | 单测 + 真机 |
| R4 | CSS 属性选择器 `[data-look="neo"]` | flowchart 实测命中 `[data-look="neo"]`；12 类探针每类都至少命中一处属性选择器；AndroidSVG 只支持 `[id]`/`[class]` | 少量样式丢失（"neo" 外挂皮肤的描边/阴影） | 接受（默认主题不依赖它），或后处理删掉该规则 | 视觉对照 |
| R5 | `!important` 被忽略 | 源码注释；实测每图 2–22 处 | 少数规则优先级错（如 `.root .anchor path{fill:#333!important}`） | 接受；若个别图颜色明显错，后处理把 `!important` 规则转成内联属性 | 视觉对照 |
| R6 | CSS 解析异常截断后续规则 | `CSSParser` 源码 | 某一条坏规则导致**后面所有颜色失效**（图变黑/无色） | 后处理：先用一个小 CSS 清洗（去掉 `@keyframes`/`@media`/未知 at-rule 与属性选择器），再把 `<style>` 交给 AndroidSVG；或直接内联化为属性 | 用 12 类图 + 故意构造的怪 CSS 测 |
| R7 | `currentColor` | mermaid 1 处、MathJax 多处；AndroidSVG 把 `currentColor` 解析为样式里的 `color`，缺省黑色 | 深色主题下图形变黑看不清 | 渲染前把 `currentColor` 替换成主题色（字符串替换，最稳） | 深浅主题各一张对照 |
| R8 | `marker` 上的 CSS 类样式 | `.marker{fill:#333333}`，marker 内 `<path>` 用 class | AndroidSVG 支持 `marker` 元素与 `E.class` 选择器，理论可行 | 保留；若箭头不显示，退化为后处理内联 `fill` | 真机看箭头 |
| R9 | `<tspan>` 多行定位 | 每行一个 tspan，带 `x`/`dy` | AndroidSVG 支持单值 `x`/`dy`，理论可行 | 无需处理 | 真机看多行标签 |
| R10 | `mask` / `clipPath` / 渐变 | 12 类图里**没有** `mask`/`clipPath`；`linearGradient` 只在 sankey 出现（8 处） | 低（AndroidSVG 三者标称完全支持） | 保留 | 出现时再看 |

**结论**：`path`/`defs`/`use`/`marker`/`clipPath`/`gradient` 这些**元素级**能力不是主要障碍；真正会破坏观感的是 **R1（已用配置消除）**、**R2（垂直基线）**、**R3（根尺寸）**、**R6（样式表被截断）**。这四条必须在原型里逐条量化，否则"能画出来但看着不对"会被误判为成功。

### 2.7 最小原型（不合并）与验收门槛

**原则：先做一次性原型，产物不进 main，不改现有 `PiMermaid`/`grok-mermaid` 路径。**

原型范围（只做这些，多了就是浪费）：

- **P0（最小可判定集）**：
  1. `assets/rich-render/mermaid/{mermaid.min.js,host.html,host.js}`；
  2. `RichSvgRenderHost`：主线程单例 + 有界队列 + 串行 + 超时 + generation 丢弃；
  3. 一个 `flowchart` 端到端：源码 → SVG 字符串 → AndroidSVG → `Canvas`/`Picture` → 截图；
  4. 失败回退源码。
- **P1**：补齐 12 类图、主题（深/浅）、`onRenderProcessGone` 重建、飞行模式。

**验收门槛（全绿才谈合并；任一红即停，先解决再继续）**

| # | 门槛 | 判定方式 | 不通过的处置 |
|---|---|---|---|
| G1 | 输出 SVG **不含** `foreignObject`/`<script>`/`on*` 属性 | 对 12 类图 × 2 主题的输出做字符串断言 | 停止，查配置泄漏 |
| G2 | 12 类图在**真机 WebView** 上全部产出 SVG（允许语义告警，不允许异常） | 真机日志 + SVG 长度记录 | 记录失败类型，判定是 mermaid 版本问题还是 WebView 版本问题 |
| G3 | 12 类图的 SVG **100% 能被 AndroidSVG 解析**（`SVG.getFromString` 不抛异常） | 单测 + 真机 | 修 R6/R3，或回退源码 |
| G4 | 视觉缺陷**清单化**：每张图逐项记录"缺元素/错位/错色"，不允许"看起来还行" | 真机截图 vs WebView 截图并排 | 未清单化 = 未验收 |
| G5 | `chrome://crash` 后宿主能重建，**后续 10 次渲染成功** | 真机脚本 | 不改架构就过不了 → 重新设计宿主生命周期 |
| G6 | 过期结果**绝不覆盖**新结果（generation 断言） | 单测（注入延迟） | 停止 |
| G7 | 超时（3–5 s）后一定回退，**不卡 UI**（主线程无 > 16 ms 长任务） | 真机 trace | 停止 |
| G8 | 飞行模式全绿（渲染 + 回退） | 真机 | 查是否有隐藏网络请求 |
| G9 | 主题切换后不显示旧色（缓存键含 theme，或渲染时替换 `currentColor`） | 真机手测 + 缓存单测 | 停止 |
| G10 | 上限生效：源码 > 64 KiB、SVG > 2 MiB、边数 > 500 都回退源码且**不崩** | 单测 + 真机 | 停止 |
| G11 | 旧 ASCII 路径**仍然可用**（回归） | 现有测试 + 手测 | 停止 |
| G12 | 真实构建记录：APK 增量、冷启动耗时、单图 P50/P95、宿主常驻 PSS | 真实 APK + 真机 | 无数据 = 不合并 |

**明确不承诺**：G12 的任何数值目标（"不得超过 X MB / Y ms"）不在本文给出；那是产品决策 + 真实测量结果，不能预先写死进研究文档。

---

## 3. 数学排版：MathJax vs KaTeX

### 3.1 关键差异是输出形态，不是覆盖率

| | MathJax | KaTeX |
|---|---|---|
| 版本（当前） | **4.1.3**（2026-07-03，Apache-2.0） | **0.19.1**（MIT） |
| 输出形态 | **原生 SVG**（`output/svg`），自包含 `<defs>`+`<use>`+内联 path | **HTML span 树 + CSS + 字体**，或 **MathML**；**没有 SVG 输出** |
| 官方 `output` 选项 | `svg` / `chtml` / `mml`（取决于组件） | 只有 `html` / `mathml` / `htmlAndMathml` |
| 单文件组件 | `tex-svg.js` 1,849,625 B（gz 615,224） | `katex.min.js` 273,081 B（gz 76,053）+ `katex.min.css` 24,793 B（gz 3,526） |
| 字体 | SVG 输出把字形转成 path 内联；CHTML 才需要字体文件 | **必须**有 KaTeX 字体（68 个文件 1,160,134 B；仅 woff2 20 个文件 259,792 B） |
| v4 变化 | 去掉了 v3 的 `/es5` 路径（官方文档 warning 原文） | — |

`output` 选项原文（KaTeX 官方选项文档）：
> "output: string. Determines the markup language of the output. The valid choices are: html / mathml / htmlAndMathml."

**这意味着**：如果 Phase 2 建立的统一管线是"JS 引擎 → SVG 字符串 → AndroidSVG（+ Compose）"，那么 **KaTeX 不满足接口**，除非额外引入一层转换（HTML→SVG 不可行；MathML→Compose 是另一套渲染器，等于放弃 AndroidSVG 管线）。**这一条是结构性事实，不是偏好。**

### 3.2 对比矩阵

| 维度 | MathJax 4.1.3 | KaTeX 0.19.1 | 证据等级 |
|---|---|---|---|
| **公式覆盖率** | 完整 TeX + `autoload` 扩展；实测 `\dv`（physics）、`\ce`（mhchem）、`\color`、`\href`、`\tag`、`\newcommand` 全部成功 | 官方支持列表很宽（含大量 AMS 环境），但 `\dv` 未定义；`\ce` 需额外 `contrib/mhchem`；`\href` 需 `trust:true` | 已核实（本机实验 + 文档） |
| **matrix** | 成功（SVG 4,654 B） | 成功（`pmatrix`/`bmatrix`/`matrix`/`array` 均在支持列表） | 已核实（本机实验） |
| **aligned / gather / split** | 成功（`aligned` SVG 6,716 B） | 支持：`align`/`align*`/`alignat`/`gather`/`split`/`equation` 在支持列表里 | 已核实（本机实验 + 文档） |
| **cases** | 成功（SVG 10,397 B） | 成功（支持列表含 `cases`） | 已核实（本机实验） |
| **宏** | `\newcommand`/`\def`/config `macros` + `autoload` 扩展 | `\def`/`\newcommand`/`\renewcommand`/`\providecommand`/`\let`/`\futurelet`/`\global\def`；实测"重定义已存在宏"会报错（`\R` 已定义 → `attempting to redefine \R`） | 已核实（本机实验 + 文档） |
| **中文混排** | `\text{中文}` → `<text font-family="serif">当</text>`（**依赖设备 serif 字体的 CJK 覆盖**） | HTML 输出里 CJK 就是普通字符，字体由 CSS 加浏览器回退决定（未在真机验证） | 已核实（本机实验）；真机字体 **待验证** |
| **SVG 输出** | ✅ 自包含（`fontCache:"local"` 时每式自带 path） | ❌ 无 | 已核实（文档 + 实验） |
| **速度（本机 Node，单式，不含引擎初始化）** | 16 例 1–30 ms，多数 5–15 ms | 16 例 0–9 ms | 已核实（本机实验）；**手机待验证** |
| **资源体积** | 1.85 MB 单文件 | 273 KB JS + 24.8 KB CSS + 字体 1.16 MB（woff2 子集 260 KB） | 已核实（npm 文件清单 + gzip） |
| **许可证** | Apache-2.0 | MIT（字体许可 **待核实**） | 已核实（npm metadata）/ 待核实（字体） |
| **离线** | `tex-svg.js` 里**没有** `input/tex/extensions/...` 之类外部相对路径，扩展列表内联；`@mathjax/mathjax-newcm-font` 是独立 npm 包（体积另算） | 需要字体文件与 CSS 一起本地打包 | 已核实（源码 grep）/ 待验证（WebView 行为） |
| **错误处理** | `noundefined`、`formatError` 可配；失败可回退 | `throwOnError` + `errorColor`（失败时按源码渲染） | 已核实（文档） |
| **与 AndroidSVG 的契合度** | 需要处理：`currentColor` 替换、根尺寸 `ex`、`<use xlink:href>`（默认 `useXlink:true`，别改成 false） | **不适用**（无 SVG） | 已核实（源码 + 实验） |

### 3.3 本机实测细节（可复现）

MathJax（`tex2svgPromise`，16 条语料）：全部成功，SVG 795–10,397 B；结构固定为
`<mjx-container class="MathJax" jax="SVG"><svg style="vertical-align:-0.025ex" width="8.704ex" height="2.025ex" viewBox="0 -883.9 3847.1 894.9"><defs><path id="MJX-9-NCM-I-1D438" d="..."/></defs><g stroke="currentColor" fill="currentColor" ...><use xlink:href="#MJX-...">`。

对 AndroidSVG 的直接含义：

1. 外层 `<mjx-container>` 是自定义元素，**必须剥掉**，只取 `<svg>`；
2. `width="8.704ex"` / `height="2.025ex"` 在 AndroidSVG 根尺寸路径上会被当成 8.7 px / 2 px（见 §2.6.1）→ **必须按 `viewBox` 重算尺寸**；
3. `style="vertical-align:-0.025ex"` 是行内基线偏移，AndroidSVG 不解析 `vertical-align`（属于 `alignment-baseline` 家族，源码里没有）→ **行内公式必须由 Compose 侧自己做基线对齐**（拿 `viewBox` 的 `y`/`height` 算），不能指望 SVG 自己摆正；
4. `currentColor` 在 AndroidSVG 里缺省解析成黑色 → 深色主题必须替换；
5. `<use xlink:href>` + `<defs><path id>`：AndroidSVG 支持 `use`/`defs`，**但 `useXlink` 要保留默认 `true`**（文档明确 `useXlink` 控制是否带 `xlink` 命名空间；`false` 时是裸 `href`，这是 HTML5 的写法，**SVG 解析器是否认裸 `href` 需实测**）。

KaTeX（`renderToString`，`output:"htmlAndMathml"`）：全部返回 HTML（含 `<math>` MathML），**没有**返回独立 SVG 文档；输出里的 `<svg>` 只是 `sqrt`/`overbrace` 这类"伸缩件"的内联小图，不是公式的矢量表示。语料里 `\dv`、`\ce` 报 `Undefined control sequence`，`\newcommand{\R}` 报"重定义"。

### 3.4 评估语料建议

**语料要能判死，不是能判活。** 建议 40–60 条，分五组（Phase 3 落地时才写进仓库，建议位置 `app/src/test/resources/rich-rendering/math/`）：

| 组 | 条数 | 内容 | 目的 |
|---|---:|---|---|
| A 现有能力对照 | 10 | 从 `PiLatexCheck.kt` 现有用例里挑：分数、根号、上下标、希腊字母、求和积分 | 保证"不比现在差" |
| B 结构复杂 | 15 | `matrix`/`pmatrix`/`bmatrix`/`vmatrix`、`aligned`、`gather`、`split`、嵌套 `cases`、多行 `array`、`\overbrace`/`\underbrace` | 判排版引擎的结构能力 |
| C 宏与扩展 | 10 | `\newcommand` 定义后使用、`\def`、`\operatorname`、`\mathbb`/`\mathcal`、`\tag`、`\label`+`\ref`、`physics` 的 `\dv`/`\pdv`、`mhchem` 的 `\ce` | 判覆盖率与"要不要引扩展" |
| D 中文与混排 | 5 | `$E=mc^2$ 是质能方程`、`\text{当 } x>0`、中英混排长句、全角标点、中文字体回退 | **最容易翻车**，必须真机看 |
| E 反例/回退 | 10 | 未闭合 `$`、`\begin{matrix}` 不闭合、`\undefinedcmd`、超长公式（>64 KiB）、极深嵌套、`\href{javascript:...}` | 判"失败不丢内容" |

每条记录：引擎成功/失败、失败原因、输出字节数、AndroidSVG 解析成功与否、与 PiLatex 的视觉差异、耗时。**同一份语料两个引擎都跑**，否则没有对照。

### 3.5 选择标准（不宣布赢家）

按优先级排序的判定规则，**先过硬门槛，再比分数**：

**硬门槛（任一不过即淘汰该引擎在该角色上的资格）**

| # | 门槛 | 说明 |
|---|---|---|
| H1 | 输出能被 AndroidSVG 解析并绘制 | MathJax 满足；KaTeX 不满足（无 SVG），除非接受额外转换层 |
| H2 | 语料 A+B 组（25 条）成功率 ≥ 95% | 现有能力不能退步 |
| H3 | 失败时不丢内容（回退 PiLatex/源码） | 方案 §14 的硬要求 |
| H4 | 许可证可分发（含字体/打包依赖） | 见 §4 |
| H5 | 全离线可用 | 不允许运行时 CDN |
| H6 | 行内公式能做基线对齐 | 方案 §5.2 明确要求行内；SVG 自身不解决 |

**评分维度（过了门槛才比）**：C 组覆盖率、D 组中文观感、体积、真机耗时（P50/P95）、缓存命中下的重复渲染成本、错误信息的可诊断性。

**当前结论**：
- **MathJax 是"统一 SVG 管线"下唯一满足 H1 的候选**，因此 Phase 3 应先做 **MathJax 最小原型**（同一宿主、同一 AndroidSVG 管线），这是"接口决定"而不是"质量判定"。
- **KaTeX 不应被淘汰**：它在体积（273 KB vs 1.85 MB）、速度（本机 0–9 ms vs 1–30 ms）、许可证（MIT）上都有明显优势。如果 Phase 3 原型证明 MathJax 在真机上不可接受（内存/首帧/字体），**下一步应该评估"KaTeX HTML 在离屏 WebView 里直接排版 + 截图/录制"这条备选架构**，而不是硬把 KaTeX 塞进 SVG 管线。
- **中文混排（D 组）是唯一可能推翻上述排序的因素**：MathJax SVG 把 CJK 变成 `<text font-family="serif">`，AndroidSVG 用设备字体画，字体缺失就是豆腐块；而 KaTeX 在 WebView 里排版时用的是系统 WebView 的字体回退，通常更可靠。这条必须真机对比后再定。

---

## 4. 许可证与 NOTICE

### 4.1 直接依赖（已核实）

| 组件 | 版本 | 许可证 | NOTICE 要求 | 结论 |
|---|---|---|---|---|
| Mermaid | 12.2.0 | **MIT** | MIT 无 NOTICE 机制；分发时保留版权声明与许可全文 | 复用仓库已有 `MIT.txt` |
| MathJax | 4.1.3 | **Apache-2.0** | Apache-2.0 §4(d)：**若上游分发物内含 NOTICE 文件，必须随分发保留**。npm 包内本次只看到 `LICENSE`，未见 `NOTICE` | **待核实**：GitHub 仓库根是否有 `NOTICE`；有则必须抄进 `assets/licenses/` |
| KaTeX | 0.19.1 | **MIT**（npm `license` 字段） | 同上 | **待核实**：KaTeX 字体（Computer Modern 系）是否另有 OFL 条款；npm 包内只有根 `LICENSE`，无独立字体许可文件 |
| AndroidSVG | 1.4 | **Apache-2.0**（POM `<licenses>` 原文 "The Apache Software License, Version 2.0"） | §4(d) 同上 | **待核实**：仓库无 `NOTICE`（本次未见到）→ 若无，只需 LICENSE |
| jsoup | 1.23.2 | **MIT**（POM：`The MIT License`，`https://jsoup.org/license`） | 无 NOTICE 机制 | 复用 `MIT.txt` |

### 4.2 mermaid 打包带进来的传递依赖（本次最容易被漏掉的面）

`mermaid@12.2.0` 的运行期依赖（npm `dependencies`）共 23 项。**打包 `mermaid.min.js` 等于把这 23 项一起分发**，它们都必须出现在 `app/src/main/assets/licenses/component-list.txt` 里。已核实的许可（按各包最新发布版的 `license` 字段）：

| 包 | 许可证 | 备注 |
|---|---|---|
| `elkjs` | **EPL-2.0 OR GPL-3.0-or-later** | ⚠️ 唯一的强 copyleft 风险点；EPL 是文件级 copyleft，**分发二进制需提供源码获取方式**；我们不修改它 → 提供上游 URL + 版本即可（**待法务确认**） |
| `dompurify` | **MPL-2.0 OR Apache-2.0** | 可选择 Apache-2.0，规避 MPL 的文件级义务（**待确认 mermaid 实际按哪个分发**） |
| `chevrotain` | Apache-2.0 | §4(d) NOTICE 检查 |
| `d3` | ISC | 保留版权声明 |
| `d3-sankey` | BSD-3-Clause | 保留版权 + 免责声明 |
| `katex` | MIT | mermaid 内部用于图内公式 |
| `cytoscape`、`cytoscape-fcose`、`cytoscape-cose-bilkent` | MIT | |
| `dagre-d3-es`、`@iconify/utils`、`@upsetjs/venn.js`、`es-toolkit`、`@mermaid-js/parser`、`marked`、`stylis`、`roughjs`、`dayjs`、`uuid`、`@braintree/sanitize-url`、`ts-dedent` | MIT | |
| `khroma` | **npm metadata 为 `None`** | **待核实**：上游仓库声明 MIT，但发布物未写 `license` 字段 → 需要按仓库 LICENSE 记录 |

**结论**：
1. 直接依赖全部是 MIT/Apache-2.0，可分发；但 **`elkjs` 的 EPL-2.0 必须显式处理**（记录源码地址 + 是否修改 + 修改披露渠道），不能只在清单里写一行 MIT 了事。
2. `mermaid.min.js` 是**打包产物**，其内部包含第三方代码；项目现有的 `component-list.txt` 是按"组件 · 版本 · 许可证 · 位置 · 上游地址"组织的，需要为每个内联组件补行，并注明"通过 mermaid.min.js 内联分发"。
3. **待核实项汇总**（不解决就不能合并）：
   - MathJax 是否有 `NOTICE` 文件；
   - KaTeX 字体是否有独立 OFL/其他条款；
   - `khroma` 的许可证字段缺失；
   - `dompurify` 在 mermaid 产物里实际按 MPL 还是 Apache 分发；
   - `elkjs` 的 EPL-2.0 对"未修改的打包再分发"的具体义务（源码获取方式写在哪）；
   - mermaid 12.2.0 实际锁定的依赖版本（npm 安装时解析的版本 ≠ 各包 latest，许可清单必须按**实际打包版本**核对）。

### 4.3 项目侧动作（结论，不实施）

- 新增许可证全文**可能为零**：MIT/Apache-2.0 全文仓库已有（`MIT.txt`、`Apache-2.0.txt`）；若字体确认 OFL，`JetBrainsMono-OFL-1.1.txt`/`SourceHanSerif-OFL-1.1.txt` 的 OFL 全文可复用（但**字体名与版权行必须单独记录**，不能只放一份 OFL 全文）。
- `manifest.txt` 需要加行（文件名 / 标题 / 分区），`component-list.txt` 需要加"内联分发"分组。
- 方案 §17 要求"更新组件清单和第三方许可证声明"——本文只给结论与待核实项，具体落地归 Phase 6。

---

## 5. 必须真机/真实构建验证的清单（不能凭文档估算）

> 这一节是本文的**硬约束**：下面每一条，文档、源码、本机 Node 都给不出答案。

| # | 项目 | 为什么文档不行 | 验证方法 | 判定 |
|---|---|---|---|---|
| A | mermaid 12.2.0 在最低支持 WebView 上能解析执行 | WebView 版本随设备/Play 更新变化；无法从 bundle 推断运行时语法支持 | API 26 真机 + 最新 WebView 各一台，跑 12 类图 | 全绿；任一失败先试 11.17.2 |
| B | 离屏（未 attach / 0 尺寸）WebView 能否给出正确文本度量 | Chromium 布局需要窗口与尺寸；`getBBox` 返回值与 attach 状态相关 | 同一份源码：本机 Chrome 截图 vs 真机离屏 WebView 截图的 SVG 坐标差异 | 坐标偏差 < 1px 级别才可接受；否则改 attach 方案 |
| C | `onRenderProcessGone` 崩溃恢复 | 只有真机能触发 renderer 崩溃/被系统回收 | `loadUrl("chrome://crash")` + 低内存模拟 + 后台杀进程 | 重建后连续 10 次渲染成功 |
| D | AndroidSVG 的实际视觉差异（R2/R3/R6/R7） | 元素支持 ≠ 观感正确；CSS 解析器、基线、尺寸都要真机看 | 12 类图 × 深/浅主题，逐图并排截图，缺陷清单化 | 无"静默丢图"；所有偏差进清单 |
| E | 冷启动 / 单图 P50/P95 耗时 | V8 版本、JIT、WebView 初始化、字体加载全不同 | 真机 systrace/宏基准，冷/热各 30 次 | 记录数据；是否达标由产品决策 |
| F | 宿主常驻内存（PSS/RSS） | 渲染进程内存与 WebView 实现、设备相关 | 真机 `dumpsys meminfo` 前后对比 | 记录数据；不预估 |
| G | 飞行模式下全链路可用 | WebView 可能尝试 DNS/网络；mermaid/ MathJax 扩展可能懒加载 | 飞行模式跑 12 类图 + 公式语料 | 全绿 |
| H | CJK 字体回退（MathJax SVG 的 `<text font-family="serif">`、KaTeX HTML） | 设备字体集不同（尤其国内 ROM） | 中英混排语料 D 组，在 3 台不同品牌真机上看 | 无豆腐块；否则改走 KaTeX HTML 或自带字体 |
| I | 主题切换后缓存不串色 | 取决于缓存键实现与 `currentColor` 处理 | 切主题后重复渲染同一公式/图 | 颜色正确 |
| J | 上限行为（64 KiB / 2 MiB / 500 边） | 极端输入的实际内存与耗时只有真机能测 | 构造超限输入 | 不崩、不卡、回退源码 |
| K | **APK 真实增量** | asset 压缩率、AAB 拆分、dex 变化都会影响 | 真实构建 + `apkanalyzer`/AAB 大小对比 | 只记录实测值，**本文不预估** |
| L | 构建兼容性 | AGP/Kotlin/Compose 版本约束只在真实构建里成立 | CI 真实构建 | 绿 |
| M | 许可证事实（NOTICE/字体/elkjs 版本） | 需要看真实打包产物与上游仓库文件 | 解包 npm tarball 核对文件；法务确认 | 全部有结论 |

**已经被文档/源码排除、不需要真机验证的**（避免重复劳动）：AndroidSVG 不支持 `foreignObject`/`filter`/动画/SVG 字体；`dominant-baseline` 被忽略；根尺寸不换算 `%`/`em`/`ex`；`!important` 被忽略；KaTeX 没有 SVG 输出；MathJax 4 没有 `/es5` 路径。这六条可以直接写进设计与测试用例。

---

## 6. 复现方法

**体积（本机，2026-11-02）**

```bash
cd /tmp && curl -sL -o mermaid.min.js \
  https://cdn.jsdelivr.net/npm/mermaid@12.2.0/dist/mermaid.min.js
stat -c%s mermaid.min.js          # 5494801
gzip -9 -c mermaid.min.js | wc -c # 1568811

# 文件清单与逐文件大小（不用下载整包）
curl -s "https://data.jsdelivr.com/v1/packages/npm/mermaid@12.2.0?structure=flat"
curl -s "https://data.jsdelivr.com/v1/packages/npm/mathjax@4.1.3?structure=flat"
curl -s "https://data.jsdelivr.com/v1/packages/npm/katex@0.19.1?structure=flat"
```

**MathJax 语料渲染（Node）**

```bash
mkdir -p /tmp/eval && cd /tmp/eval && npm init -y && npm i mathjax@4.1.3 katex@0.19.1
# 关键代码（完整脚本见本机 /tmp/eval/mj2.mjs）
import {init} from 'mathjax';
const M = await init({loader:{load:['input/tex','output/svg','[tex]/autoload','[tex]/require','[tex]/ams']}});
const A = M.startup.adaptor;
const svg = A.outerHTML(await M.tex2svgPromise('\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}', {display:true}));
```

**KaTeX 语料渲染**

```js
import katex from 'katex';
katex.renderToString(tex, {displayMode:true, throwOnError:true, output:'htmlAndMathml'});
```

**mermaid 结构探针（注意局限）**

```bash
cd /tmp/eval && npm i mermaid@12.2.0 jsdom
# jsdom 里打桩 getBBox/getComputedTextLength/getScreenCTM，再 mermaid.render()
# 只用于统计"出现了哪些元素/属性"；几何无效，mindmap 需 canvas 2d 会失败
```

**AndroidSVG 源码核对**

```bash
# 1.4 产物
curl -sL -o asvg-1.4.aar https://repo1.maven.org/maven2/com/caverock/androidsvg-aar/1.4/androidsvg-aar-1.4.aar
# master 源码（能力/限制/单位换算/!important/at-rule）
#   .../androidsvg/src/main/java/com/caverock/androidsvg/utils/{SVGParserImpl,CSSParser,SVGAndroidRenderer,SVGBase}.java
```

---

## 7. 参考来源

**Mermaid**
- npm：`https://registry.npmjs.org/mermaid`（版本、发布时间、`engines`、依赖）
- 配置 schema：`https://mermaid.js.org/schemas/config.schema.json`（`securityLevel` 枚举与默认、`htmlLabels` 说明、`maxTextSize`=50000、`maxEdges`=500、`secure` 白名单、`deterministicIds`、`suppressErrorRendering`）
- 配置文档：`https://mermaid.js.org/config/usage.html`、`https://mermaid.js.org/config/schema-docs/config.html`
- 包文件清单/体积：`https://data.jsdelivr.com/v1/packages/npm/mermaid@12.2.0?structure=flat`

**MathJax**
- npm：`https://registry.npmjs.org/mathjax`（4.1.3，Apache-2.0）
- Releases：`https://api.github.com/repos/mathjax/MathJax/releases`（4.0.0 = 2025-08-04，4.1.3 = 2026-07-03）
- Combined components 文档源码：`https://raw.githubusercontent.com/mathjax/MathJax-docs/master/web/components/combined.rst`（v4 去掉 `/es5`；组件列表含 `tex-svg`；"some TeX extensions and additional font data may be loaded dynamically"）
- SVG 输出选项：`https://raw.githubusercontent.com/mathjax/MathJax-docs/master/options/output/svg.rst`（`fontCache:'local'`、`useXlink:true`、`blacker`）
- 渲染文档（官方站点，Cloudflare 保护，人类可读）：`https://docs.mathjax.org/en/latest/web/components/combined.html`

**KaTeX**
- npm：`https://registry.npmjs.org/katex`（0.19.1，MIT）
- 选项文档：`https://katex.org/docs/options`（`output` 只有 html/mathml/htmlAndMathml；`throwOnError`、`errorColor`、`trust`）
- 支持列表：`https://katex.org/docs/supported`（环境、宏、`\href`/`\includegraphics` 需 `trust`、`\tag` 用法）
- mhchem 扩展：`https://katex.org/docs/supported.html`（`\ce`）+ 包内 `dist/contrib/mhchem.js`（78,561 B）

**AndroidSVG**
- 官网功能清单：`https://bigbadaboom.github.io/androidsvg/`（完全支持列表、`<text>` 多值限制、`foreignObject`/filter/动画不支持、简化 CSS）
- 实现细节：`https://bigbadaboom.github.io/androidsvg/svg_implementation_details.html`（逐元素说明、CSS 选择器限制、`text-anchor` 支持）
- Maven：`https://repo1.maven.org/maven2/com/caverock/androidsvg-aar/`（仅 1.3/1.4，`lastUpdated=20190528`；POM 声明 Apache-2.0）
- 源码：`https://github.com/BigBadaboom/androidsvg`（`utils/SVGParserImpl.java`、`utils/CSSParser.java`、`utils/SVGBase.java`、`utils/SVGAndroidRenderer.java`）

**Android 平台（WebView）**
- `WebViewClient#onRenderProcessGone`：`https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)`
- `WebView`（构造函数线程要求、Application Context 限制、`destroy()` 契约）：`https://developer.android.com/reference/android/webkit/WebView`
- `WebViewAssetLoader`：`https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader`
- 本仓库现状：`app/build.gradle.kts`（`minSdk=26`、`targetSdk=28` 默认）、`gradle/libs.versions.toml`（`webkit=1.13.0` 已在依赖）、`app/src/main/kotlin/app/pi/ui/screens/WorkspaceViewer.kt`（现有 WebView 仅用于用户打开的工作区 HTML，`allowFileAccess=true`，**不应作为新宿主的模板**）

**其他**
- jsoup：`https://repo1.maven.org/maven2/org/jsoup/jsoup/1.23.2/jsoup-1.23.2.pom`（MIT）、`https://jsoup.org/license`
- Apache-2.0 §4(d) NOTICE：`https://www.apache.org/licenses/LICENSE-2.0`
- elkjs（EPL-2.0 OR GPL-3.0-or-later）：`https://registry.npmjs.org/elkjs`

---

## 8. 未决问题（需要后续决策，不在本文结论内）

1. **宿主 Context**：常驻离屏 WebView 用 Activity Context（泄漏风险）还是 Application Context（功能受限）+ 包装？需要一个小实验，不在文档范围内能定。
2. **`dominant-baseline` 的后处理策略**：是"转成显式 `dy`"还是"接受偏移并在文档里列为已知缺陷"？取决于真机 R2 的实际偏差量级。
3. **KaTeX 的备选架构**：如果 MathJax 真机不可接受，是否接受"KaTeX 在 WebView 里排 HTML、把结果画成位图/录制"这条非 SVG 路径？这会改变 Phase 2 建立的统一管线，是架构决策，不是选型细节。
4. **是否自带数学字体**：MathJax SVG 的 CJK 走 `font-family="serif"`，若真机字体不可靠，要不要随包分发一份数学/CJK 字体（体积与 OFL 记录另算）。
5. **mermaid 12 vs 11**：等 A 项真机结果。
6. **elkjs 的 EPL 义务**：需要法务/维护者明确"未修改打包再分发"的合规写法。

---

## 附：与现有仓库的对照（避免与旧文档冲突）

| 事实 | 现状 | 依据 |
|---|---|---|
| `androidx.webkit` | **已在依赖里**（1.13.0），但当前只用于 `WorkspaceViewer` 的普通 WebView，**没有** `WebViewAssetLoader` 使用 | `gradle/libs.versions.toml`、`app/build.gradle.kts` |
| 现有 Mermaid | `PiMermaid.kt` + guest `grok-mermaid` → `/mermaid` → ASCII 框线；**没有** WebView | `app/src/main/assets/pi-extensions/pi-highlight/mermaid.ts` |
| 现有 LaTeX | `PiLatex.kt`（Unicode 网格），无排版引擎 | 方案 §1.4 |
| `minSdk` | 26 → `onRenderProcessGone` 可用 | `app/build.gradle.kts` |
| 现有 WebView 安全姿态 | `allowFileAccess=true`、`javaScriptEnabled=true`、`loadUrl("file://...")`，用于用户主动打开的工作区 HTML | `WorkspaceViewer.kt`；**新宿主必须收紧**，不能照抄 |
| 许可证组织方式 | `assets/licenses/manifest.txt`（索引）+ `component-list.txt`（组件）+ 全文 `.txt` | 目录实测 |
