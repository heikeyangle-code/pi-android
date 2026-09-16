# 12 · 上色面积占比审查 + 对话转录滚动条

> **性质**：只读审查 + 方案。本文档是这次任务**唯一**的产物；没有改动、创建或删除任何其它文件，没有 commit、push，没有跑 gradle。
> **度量口径**：本文所有比例都是**估算**，不是实测。算法写在 §1，脚本与全部假设写在附录 A。凡是我实测/复核到的东西，一律给 `file:line`；凡是估算，都写"估"。
> **裁决状态**：与冻结板子（`design-demos/direction-b-v2.html`）或既有裁决账本（`07-construction-decisions.md`）冲突的地方，本文**显式标注**并给选项；没有一条被写成"已经确定"。
> **五条前提（用户裁决，已生效，不再是待裁决项）**：
> 1. **滚动条要做，但只做对话转录视口一处**（原话：「V2 有滚动条吗？有 就按 v 二的去做，并且按 pi token 一比一」／「对话界面有一个就行。别的地方不用做。」）——几何按 v2，颜色按 pi 的 `scrollbarTrack`/`scrollbarThumb` **原值**（§7）。
> 2. **不做任何对比度调整**：token 在浅色下即使对比度不够也照 pi 原值用；本方案**不提出任何**"为了保证可读性而偏离 pi 原值"的改法。既有的"对比度修正"路线（`PiContrast` 与 5 个派生 token）按用户裁决**撤销**（§6.2）。
> 3. **"上面你说的其他不一致的，全修的一致"**：凡是"pi 有、我们没按 pi 的语义用"的地方，一律改成 1:1（§6.1–§6.4）。
> 4. **搜索高亮保持现状**（用户原话：「整行是指的搜索时候是吧？如果是搜索时就不要管了，保持现在就行。」）——从改法清单移出，理由与事实写在 §6.5。
> 5. **两枚浮层箭头**（用户裁决）：下箭头「回到最新」= pi 的 `↓ Jump to latest message` 指示条（`selectedBg` 底 + `text` 字形）；上箭头「回到顶部」**保持现状**（pi 没有对应物，不发明映射）（§7.6）。

---

## 0. 这次审查要回答什么

用户的问题：「官方上完色，各种色的占据屏幕比例要和我的软件的比例差不多。看看我的 UI 哪里用改吗？」

因此本文回答三件事，顺序就是重要度顺序：

1. **同一个主题下，pi 每个 token 在屏幕上的面积占比 vs 我们的占比**，差在哪里（§4）。
2. **差值是"配色差"还是"结构差"**——这是本次审查最重要的一条结论：**面积占比上的大差值几乎全部来自两侧画的东西不同，不来自颜色取错**（§5）。
3. **要改的清单**，逐条给 `file:line`、依据、是否与板子/裁决冲突、代价与风险、上机验收点（§6），以及**对话转录滚动条**的完整方案（§7：几何按 v2、颜色按 pi 原值、只做一处）。

---

## 1. 方法与模型

### 1.1 为什么只能是估算

pi 是**字符网格**渲染（一个终端字符 = 一个着色单元），我们是 **dp 网格**（一个 Compose 节点 = 一块矩形）。两者没有共同的物理单位，也不该有：把 pi 的终端折成"手机 dp"需要假设一个屏幕尺寸与字号，那一步必然引入主观选择。

所以本文选一个**与视口无关**的量做主要比较：

> **占比 = 该 token 涂到的面积 ÷ 同一份会话内容的总面积**

它的好处是：**你不需要知道屏幕多大**。屏幕上装 1 屏还是 1.2 屏内容，各颜色的**份额**不变——这正是用户真正在问的"各种色的比例"。两侧都用这个定义，于是"`userMessageBg` pi 12.12% vs 我们 16.12%"是可比的两个数（§4.1 主表）。

同时给出两个副产品（用于解释差值来源，不用于主表）：
- **一屏装多少**：pi 的场景估 33 行 / 30 行 = 110% 屏；我们同一份内容估 676dp / 718dp 视口 = 94%。两者都是"一屏多一点 / 不到一屏"，量级相当。
- **绝对面积**：只在需要说明"为什么同一个构件两侧份额差很多"时使用。

### 1.2 pi 侧模型（字符网格 100×30）

**选 100×30 并说明**：pi 的 fullscreen 模式用 alt-screen，视口 = 终端行列数。100×30 是桌面全屏终端的典型值，也落在 pi 自己 `tools/` 里假定的宽度量级上。它不是标准，只是本次审查选定的**参考终端**。

**灵敏度**：换成 80×24 重算，pi 侧每个 token 的份额变动 ≤ 0.6 个百分点（脚本输出见附录 A），差值排序与结论不变。所以 100×30 这个选择不影响任何结论。

pi 侧的"面积"按两类数：
- **背景令牌**（`userMessageBg`、`tool*Bg`、`customMessageBg`、`selectedBg`、`searchMatchBg`…）：涂的是**整块矩形**，面积 = 行数 × 列宽占比 × 100 格。
- **前景令牌**（`toolTitle`、`toolOutput`、`muted`、`dim`…）：涂的是**字形格**，面积 = 它着色的字符数 × 1 格（宽的 CJK 字符在终端里本来就占 2 格，所以这套数法和我们的"1 个汉字 ≈ 1.0 em"是一致口径）。

### 1.3 我们侧模型（dp 网格，v2 板的设备 412×892dp）

- 设备与几何**全部**取冻结板子与 `06 §2`：视口 **412×892dp**（`06` §2「设备：412×892」）、页水平内边距 **14dp**（`06` §2「屏水平 14px」）、卡片内 12dp、块间距 8、顶栏 48、底栏 56。
- 正文列宽 = 412 − 2×14 = **384dp**；转录视口高 = 892 − 48（顶栏）− 70（输入区）− 56（底栏）= **718dp**（输入区 70dp = 容器 `padding 9/10` + 输入 20 + gap 8 + chip 行 24，`ui/screens/ChatScreen.kt:2688-2705`、`06 §2` 输入区）。
- 字号/行高用实现里的角色（与 `06 §2` 的 5 档一致）：`meta` 12/18、`monoSmall` 12/18、`mono` 13/20、`code` 13/19、`prose` 14/23（`ui/theme/PiTheme.kt:224-228`、`:270-289`）。
- 字符前进宽按字体本征值取：等宽 0.6 em（13sp → 7.8dp、12sp → 7.2dp），比例字体混排按 0.55 em 平均。
- **灵敏度**：换成 360×780dp 重算，我们侧份额变动 ≤ 3 个百分点，差值排序与结论不变（附录 A）。

### 1.4 "典型会话屏"的同一份内容

两侧渲染的是**同一份内容**：

1. 一条用户消息：3 行 markdown 正文 + 1 张图片 + 右下时间；
2. 一段助手回答：标题 1 行 + 列表 4 行 + 代码块 5 行 + 引用 2 行；
3. 一张 `read` 工具卡（收起/展开两说，见 §4.1 脚注）；
4. 一次 `!` 命令（BashPanel）；
5. 单独一屏列表（会话列表 / 会话树，含选中行）。

图片按 **4:3 照片**取（308→360dp 宽 × 270dp 高，取决于设备）；竖长截图是极端情形，单列一行说明（§4.2）。

### 1.5 三条必须承认的结构不对称（它们决定了差值怎么读）

| # | 不对称 | 事实 | 对占比的影响 |
|---|---|---|---|
| A | **pi 的 TUI 不画用户消息里的图片附件** | 用户消息只渲染 `textContent`（`modes/interactive/interactive-mode.js:2968` 只传文字）；pi-tui 的 Markdown 组件对 image 只做行判定、不做绘制（`node_modules/@earendil-works/pi-tui/dist/components/markdown.js:3,214,229`，全文没有 `renderImage`/`Image(`） | 我们用户气泡里那块图片面积，在 pi 侧是 **0**。这是"含图片场景"下 `userMessageBg` 差值 28 个百分点的**全部**来源 |
| B | **一屏行容量与单格面积不同** | pi 30 行/屏，`mono` 行高 20dp 时我们约 718/20 = 36 行/屏；pi 一个字符格折到 412×892 约 4.12×29.7 = 122dp²，我们 13sp 等宽格 7.8×20 = 156dp²（我们大 1.28×） | 同一构件、同一内容两侧份额**本来就不等**；所以主表看"同一份内容内的份额"，而不是看绝对值 |
| C | **pi 的 `text` 不是被令牌涂色的面积** | pi 的 `text` 令牌通常是空串（终端默认前景，`theme-schema.json` 的 `text` 描述 + `theme.js` 的解析），只有显式 `theme.fg("text", …)` 的地方才算 | 我们映射到 `text` 的大块前景（工具卡参数摘要、页脚状态词）在 pi 侧计 0，这项差值属"归属差"不属"配色差" |

---

## 2. pi 侧：哪些 token 真的会上色（复核结果）

### 2.1 背景令牌的绘制点（我逐条复核过）

| 令牌 | pi 的绘制点（file:line） | 涂多大 |
|---|---|---|
| `userMessageBg` | `modes/interactive/components/user-message.js:29` | `Box(outputPad, 1, bgFn)`：整条用户消息块（内容行 + 上下各 1 行 padding），**满宽**（`pi-tui/dist/components/box.js:84-118` 的 `applyBg` 补齐到 width） |
| `toolPendingBg` / `toolSuccessBg` / `toolErrorBg` | `modes/interactive/components/tool-execution.js:215-221`（`bgFn` 三选一）→ 施加在 `:47` 的 `Box(1, 1, …)` 上 | **整张工具卡**：内容行 + 上下各 1 行 padding，满宽 |
| `customMessageBg` | 5 处：`components/custom-message.js:23`、`custom-entry.js:40`、`compaction-summary-message.js:13`、`branch-summary-message.js:13`、`skill-invocation-message.js:14` | 各自的 `Box(1, 1, …)`，满宽 |
| `selectedBg` | ① `components/session-selector.js:421`（**整行**，行已用空格补到满宽，`:395` 的光标 `› ` 是 accent、`:406-408` 的**当前会话**是 accent 文字）② `components/tree-selector.js:603-604`（**只涂光标格 + 正文实际宽**，`bodyWidth` 见 `:605`）③ `tui-renderer.js:17` | 见 §2.1 的更正 |
| `searchMatchBg` / `searchMatchText` | `tui-renderer.js:9` 定义 → `tui-alt-screen.js:1300-1311` 施加 | **只涂命中文字的列区间** `[startCol, endCol)`；当前命中额外 `bold + inverse`（`tui-renderer.js:11-12`） |
| `scrollbarTrack` / `scrollbarThumb` | 接线点 `interactive-mode.js:629-630` → `chat-viewport.js:4-13` → 几何 `pi-tui/dist/layout.js:183-217` → 绘制 `layout.js:218` | 视口最右**一列**整列；见 §7.1 留档 |

**一处更正（重要）**：`tui-renderer.js:17` 的 `selectedBg` **不是**列表整行选中，它是 **"↓ Jump to latest message" 那个浮层指示条**的背景：

```
tui-renderer.js:14-18
  scrollToEndIndicator: () => {
    const shortcut = keyDisplayText("tui.altScreen.bottom");
    const label = ` ↓ Jump to latest message${shortcut ? ` · ${shortcut}` : ""} `;
    return theme.bg("selectedBg", theme.fg("text", label));
```
它画在视口底部一行（`tui-alt-screen.js:1387-1407`），且只在**不跟随末尾**时出现（`:1389`）。它对应的是我们对话页右下角那颗「回到最新」箭头，**不是**会话列表的选中行。这一点在 §6.4 和 §7 里各有一处后果，请一并看。

### 2.2 export-only 的三个令牌（结构化差异，必须写清）

`pageBg` / `cardBg` / `infoBg` **只**喂给 HTML 导出器：
- 唯一读取处是 `core/export-html/index.js:81-83`（`--exportPageBg` / `--exportCardBg` / `--exportInfoBg`）与 `:100-110`；
- 主题解析把它放在 `export` 段（`modes/interactive/theme/theme.js:787-789`，`theme-schema.json` 的 `export` 属性）；
- TUI 侧**没有任何** `theme.bg("pageBg", …)` 调用点（我对 `dist/` 全量 grep 过，命中只在上述导出链路与 bundle）。

**结论**：我们的 `pageBg` 占满全屏**不是违规**，是 export token 的正常用法（`07` 账本没有为它立过规矩，`docs/pi-android-ui-spec.md:92` 本来就把它定义成 `Surface` 的基准）。同理 `cardBg`（我们的卡片与输入区底）与 `infoBg`（小面积提示条）也不是"抢了 pi 的颜色"——pi 的 TUI 里没有对应构件。

### 2.3 前景令牌在会话流里的绘制点

| 令牌 | pi 用在哪（file:line） |
|---|---|
| `toolTitle` | 每个内置工具卡的**工具名**：`core/tools/renderers/read.js:27`、`:79`、`bash.js:31`、`edit.js:53`、`write.js:90`、`grep.js:19`、`find.js:18`、`ls.js:15`；无渲染器时的兜底卡名 `tool-execution.js:91`、`:316` |
| `toolOutput` | 工具卡**正文**（`read.js:97` 的 `theme.fg("toolOutput", …)`，`bash.js` 的 shell 调用名在 `:31` 的 `commandDisplay`） |
| `muted` | 工具卡的**截断提示**（`read.js:99`）、`read` 的展开提示 `dim`（`read.js:72`）、`bash` 的**耗时页脚**（`bash.js:95`）、`bash-execution.js:110,113` 的 `!` 命令**输出正文**、loader 的说明句（`bash-execution.js:38`） |
| `dim` | `session-selector.js:417` 每行的树前缀；`read.js:72` 的展开提示；`tree-selector.js:596` 的前缀 |
| `thinkingText` | 思考块正文（app 侧注释 `ui/blocks/ThinkingBlockBlock.kt` 指的是 `components/assistant-message.js:151`） |
| `toolDiffContext` | diff 上下文行（`components/diff.ts:127-152` 的整行染色） |
| `bashMode` | `bash-execution.js:24`（`colorKey`）→ `:32` 上边框、`:36` `$ command` 头、`:38` 转圈、`:40` 下边框 |

**助手消息没有任何背景令牌**：`components/assistant-message.js` 全文没有 `theme.bg(`（我 grep 过）。这条和我们是**一致**的（我们的助手正文也是无容器）。

**工具卡正文在 pi 里默认是空的**：`read.js:82-84`——`if (!options.expanded && !isError) return "";`。所以 pi 的 `read` 卡收起时只有 `call` 一行 + `Box` 的上下 padding = **3 行**；展开后正文最多 10 行（`:95-96`）。

---

## 3. 我们侧：每个 token 的实际消费点

| 令牌 | 消费点 | 备注 |
|---|---|---|
| `userMessageBg` | `ui/blocks/UserMessageBlock.kt:99-114`（`Surface` 满宽、`PiShapes.cardInner`、`padding 12`、`marginBottom 10` 在 Surface **外**） | 图片在气泡内（`:127-133`） |
| `toolPendingBg/SuccessBg/ErrorBg` | `ui/blocks/ToolBlockChrome.kt:106-113`（`toolContainerColor`）→ `:424-431`（`BlockCard`） | 卡宽 = 列宽 − 轨道缩进 26dp（`ui/blocks/ToolRail.kt:81`） |
| 卡描边 = 状态色 35% | `ui/blocks/ToolBlockChrome.kt:429`、`:437` | `06 §2` 工具卡描边 |
| `toolTitle` | **0 个渲染消费点**（只有 `theme/PiPalette.kt:59` 定义、`theme/PiThemeFiles.kt:540/603` 与 `extension/ChromeColor.kt:88` 的导入导出） | 工具名画在 `muted`：`ui/blocks/ToolBlockChrome.kt:198-201` |
| `toolOutput` | **1 个**：`ui/chat/BashPanel.kt:94`（`!` 输出正文） | 其余 18 处走派生 `bodyOnTool` |
| `toolDiffContext` | **0 个**：2 处走派生 `contextOnTool` | |
| `thinkingText` | **0 个**：3 处走派生 `thinkingBodyOnCanvas` | |
| `scrollbarTrack` / `scrollbarThumb` | **0 个渲染消费点**：两个 token 各有 8 行管线引用（定义 `theme/PiPalette.kt:45-46`、`:178-179`、`:241-242`；主题往返 `theme/PiThemeFiles.kt:131-132`、`:528-529`、`:591-592`；扩展导出 `extension/ChromeColor.kt:83-84`；另有 1 行共同注释 `PiPalette.kt:10`），**绘制点 0** | 精确说法（更正任务书里的"0 个调用点"）：**token 有 15 行引用，0 个绘制点** |
| `selectedBg` | 文本选择底 `ui/theme/PiTheme.kt:348`；`colorScheme().primaryContainer` `:368`；设置搜索命中行底 `ui/settings/PiSettingsRows.kt:208`；列表编辑器选中行 `ui/settings/PiSettingsEditors.kt:195`、`:827`；分段控件 `ui/PiRoot.kt:453`、`ui/screens/WorkspaceChrome.kt:250`；信任提示 `pi/PiProjectTrustPrompt.kt:105` | **两个会话列表上都没有**（见 §6.4） |
| `searchMatchBg/Text` | 对话内查找**整行**底：`ui/screens/ChatScreen.kt:1525-1528`（当前命中另加 1dp `searchMatchText` 描边）；设置搜索**命中词级**：`ui/settings/SettingsSearchScreen.kt:338-360` | 两处粒度不同（对话内=行级、设置搜索=词级）；**用户裁决两者都保持现状**（§6.5） |
| `bashMode` | 输入框边框（以 `!` 开头时）`ui/screens/ChatScreen.kt:2682-2684`；BashPanel 的 `$ command` 一行 `ui/chat/BashPanel.kt:54,62` | 面板底是 `cardBg`、**无边框** |
| `customMessageBg` | `ui/blocks/CompactionBlock.kt:81`、`BranchSummaryBlock.kt:51`、`HookMessageBlock.kt:43`、`SkillInvocationBlock.kt:60`、`ErrorBlock` 不用它 | 与 pi 的 5 处同源 |
| `pageBg` / `cardBg` / `infoBg` | 画布 `ui/theme/PiTheme.kt:382-399`；`infoBg` 只在 2 处小面积：`ui/screens/ProjectScreen.kt:2365`（工作区 Warning 提示条）、`ui/extension/ExtensionUiHost.kt:155`（扩展 snack 的 Warning 档） | `infoBg` 的调用点更正见 §6.6 |
| 5 个派生 token | 26 个消费点 / 18 个文件（清单见附录 B） | `ui/theme/PiPalette.kt:130/141/149/155/161` 定义，`ui/theme/PiContrast.kt` 实现 |

---

## 4. 逐 token 面积对照

### 4.1 主表：同一份会话内容（两侧同内容，都不含图片；工具卡按展开态估）

分母 = 该场景在两侧各自的着色总面积（pi 3300 格 / 我们 259,584dp²）。

| # | token | pi 占比 | 我们占比 | 差 | 差值性质 | 结论 |
|---|---|---|---|---|---|---|
| 1 | `bashMode` | **6.45%** | **0.72%** | **+5.73** | **真配色差** | pi 画上下两条**全宽**边框；我们只有一行文字。**唯一一条"同一构件、同一 token、着色范围不同"的差**，要改（§6.3） |
| 2 | `userMessageBg` | 12.12% | 16.12% | −4.00 | 结构差 | 气泡更"高"：我们的 12dp 内边距 ×2 + 8dp 间距 ×2 + 时间行 18dp 都在底色里（`UserMessageBlock.kt:112-147`）；pi 是 `Box(outputPad, 1, …)` 上下各 1 行。**不建议为凑比例改内边距**（v2 的 12/12 是冻结值） |
| 3 | `toolOutput` | 2.42% | **0.00%** | +2.42 | 归属差 | 同一块正文我们用的是 `bodyOnTool`（派生自 `toolOutput`）。按用户裁决要回到 pi 原 token（§6.2），改完这一行自然归零 |
| 4 | `text` | 0.00% | 2.40% | −2.40 | 归属差 | 见 §1.5-C：pi 的 `text` 不是令牌面积。**不是缺陷** |
| 5 | `toolSuccessBg` | 15.15% | 14.62% | +0.53 | 结构差 | **几乎完全一致**（差 0.5pp）。工具卡底色占比这一项**不用改** |
| 6 | `muted` | 0.91% | 0.50% | +0.41 | 归属差 | 我们的工具名画在 `muted`（`ToolBlockChrome.kt:200`）、页脚状态词也在 `text`；pi 的工具名在 `toolTitle`。按裁决改后这一项会分开（§6.1） |
| 7 | `toolTitle` | 0.12% | 0.00% | +0.12 | 归属差 | 同 #6 |
| | **着色合计** | **37.2%** | **34.4%** | | | 整体"上色密度"两侧差 2.8pp —— **上色这件事本身我们并不比 pi 多** |

**工具卡的两侧默认态脚注（估）**：pi 的 `read` 卡收起时正文为空（`read.js:82-84`）→ 卡 3 行、场景 31 行 → 3/31 = **9.68%**；我们收起时卡 54dp（`BlockCardRowPadding` 上下 6 + 标题 18 + gutter 6 + 页脚 18）、场景 632dp → 19,332/242,688 = **7.97%**。**差值 +1.71pp，与展开态的 +0.53pp 同一个量级**（都远小于 `bashMode` 的 5.73pp），结论不变。

### 4.2 附表：用户口径的"含图片场景"

| token | pi | 我们 | 差 | 读法 |
|---|---|---|---|---|
| `userMessageBg` | 12.12% | **40.57%** | **−28.44** | **完全是结构差**：pi 的 TUI 不画用户附件图片（§1.5-A），我们气泡里那张 4:3 图片本身占了场景面积的 30%。**这不是配色问题，这条差不可能靠改颜色消掉** |
| `bashMode` | 6.45% | 0.51% | +5.94 | 同主表 #1 |
| `toolSuccessBg` | 15.15% | 10.36% | +4.79 | 分母被图片撑大导致的**假差**；同内容口径下只有 +0.53（主表 #5） |
| 着色合计 | 37.2% | 53.5% | | 差值全部来自那张图片 |

**竖长截图（9:16）极端情形**：单图高上限是屏高 60%（`ui/blocks/ImageGridBlock.kt:354`、`:167-169`），4:3 时约 270dp、9:16 时封顶 535dp → `userMessageBg` 的份额会到 55% 上下。**这是"我们画了 pi 不画的东西"的放大版**，不是新问题。

### 4.3 列表屏（会话列表 / 会话树，各 12 行）

| 项 | pi | 我们 |
|---|---|---|
| 可见行数 | 10（`session-selector.js:255` `maxVisible = 10`） | 11（718dp ÷ 62dp 行高） |
| 选中行底 `selectedBg`（会话列表） | **整行**：`session-selector.js:395`（accent 光标 `› `）+ `:419-421` → 占列表 **10.0%** | **0**（当前会话用徽标：`ui/screens/SessionsScreen.kt:738-739` 的注释 + `:834`） |
| 选中行底 `selectedBg`（会话树） | 只涂光标格 + 正文实际宽（`tree-selector.js:603-605`）→ 约 **3.0%** | **0**（我们画的是 `onActivePath` 的 2dp 竖线：`ui/screens/chat/SessionTreeScreen.kt:773-782`） |
| 行的默认底色 | 终端默认（无底） | `surfaceContainerLow`（= `cardBg`，`SessionsScreen.kt:764`） |
| 会话名颜色 | 当前会话名 accent（`session-selector.js:406-408`） | `onSurface`（`SessionsScreen.kt:790-794`，理由写在 `:736-739`） |

**这是全审查里唯一一条"真·颜色面积"的结构性差异**（列表屏差 10.0pp），处理见 §6.4。

### 4.4 补充构件

| 构件 | pi 占屏 | 我们占屏 | 差 | 读法 |
|---|---|---|---|---|
| diff 卡（20 行） | 23/30 行 = **76.7%** | 358×465 / 367,504 = **45.3%** | −31.4 | 同一个构件，pi 占屏更多：**pi 一屏 30 行、我们一屏 36 行**（§1.5-B）。同内容口径下两者都是"这卡几乎是整屏"，**不用改** |
| 思考块（10 行正文） | 600 格 = 20.0% | 88,920dp² = 24.2% | +4.2 | 两者都只涂字形、都不给底（我们 `ui/blocks/ThinkingBlockBlock.kt:73/84/118` 是字形色） |
| 压缩卡（1 行） | 3 行 = 10.0% | 16,128dp² = 4.4% | −5.6 | pi 的 `Box(1,1)` 让它比一行文字高得多；我们的卡是 `padding 12` + 18dp 行 |
| 搜索命中（1 处 6 个字） | 6 格 = **0.20%** | 整行 384×62 = **6.48%** | +6.28（**32 倍**） | 相对差最大的一项；**用户已裁决保持现状、不改**，依据与代价见 §6.5 |
| 滚动条（pi auto，滚动后 1s） | 一列 × 30 行 = 30 格 = **1.0%** | **0**（`scrollbarTrack`/`scrollbarThumb` 目前 0 绘制点） | +1.0 | **本轮要做**（用户裁决）：只在对话转录视口，几何按 v2、颜色按 pi 原值（§7） |

---

## 5. 归因：差值的三个来源

1. **结构差（我们画了 pi 不画的东西）**：用户附件图片（§1.5-A）、每行卡片底与内边距（我们的列表行、气泡、卡片都有 `cardBg`/`surfaceContainerLow` 底，pi 的列表行没有底）、一屏行容量（§1.5-B）。**这类差不该用改颜色去凑**——把气泡内边距改小来"让比例像 pi"会直接违反冻结板子。
2. **归属差（同一块颜色换了 token 名）**：`bodyOnTool→toolOutput`、`contextOnTool→toolDiffContext`、`thinkingBodyOnCanvas→thinkingText`、`metaOnCanvas/metaOnCard→muted/dim`、工具名 `muted→toolTitle`。**这类差值在面积上是零**（派生的色相来自 pi 原值），它的代价是"自定义主题下语义漂移"。用户已裁决全部改回（§6.1–§6.2）。
3. **真配色差（同一构件、同一 token、着色范围不同）**：只有两条 —— `bashMode`（pi 两条全宽边框 vs 我们一行字，差 5.73pp）与 `searchMatchBg`（命中词 vs 整行，差 6.28pp / 约 32 倍）。两条之中：`bashMode` **要改**（§6.3），`searchMatchBg` **已由用户裁决保持现状、不改**（§6.5）。

---

## 6. 改法清单

每条 = 改什么 + `file:line` + 依据 + 与板子/裁决的关系 + 代价与风险 + 上机验收点。

**冲突图例**：`【不冲突】` / `【与板子冲突·待裁决】` / `【与既有裁决冲突·需追加账本】`。

### 6.1 建议改 · 工具卡的工具名 → `toolTitle`

- **改什么**：`ui/blocks/ToolBlockChrome.kt:198-201` 的 `color = palette.muted` → `palette.toolTitle`。参数摘要**不动**（`:205-215` 的 `palette.text`，`06 §2` 工具卡「主体 12 正文」+ pi 渲染器把 `c-text` 给摘要）。
- **依据**：pi 用它画每个内置工具卡的**工具名**——`read.js:27`、`:79`、`bash.js:31`、`edit.js:53`、`write.js:90`、`grep.js:19`、`find.js:18`、`ls.js:15`；兜底卡 `tool-execution.js:91`、`:316`。我们 0 消费点（§3）。
- **冲突**：**【与板子冲突·用户已裁决】**。板子 `06 §2`「工具卡」写的是「工具名 12 **muted**」，`docs/pi-android-ui-spec.md:156` 也写「工具名…`muted` 色」，`ui/blocks/ToolBlockChrome.kt:208-212` 的注释解释了当初为什么不用 `toolTitle`。用户本轮裁决"全修的一致"，所以**执行改**；记账时必须写明这条覆盖了 `06 §2` 的一行原文与 `docs/pi-android-ui-spec.md:156`（两处都要同步改，否则文档与实现互相说谎）。
- **代价与风险**：两套内置主题下 `toolTitle` 与 `muted` 不同值（dark `#D4D4D4` vs `#808080`），**工具名会显著变亮**——在一张已经靠"状态色 + 三态底色"表达状态的卡上，工具名变亮会把视线从状态拉回标题。这是这次改动的真实观感代价，属"1:1 优先"的取舍，不改。
- **上机验收点**：深/浅两套主题下，工具名比参数摘要**不更暗**、卡片上"成功/失败"的三重编码仍然第一眼可读；自定义主题（把 `toolTitle` 与 `text` 设成不同值）时工具名与摘要能分开。

### 6.2 建议改（用户已裁决）· 撤销 5 个对比度修正 token，全部回 pi 原值

- **改什么**：**26 个消费点 / 25 行 / 18 个文件**（逐个见附录 B.2；任务书说"19 个文件"，差的应是 `ui/theme/PiContrast.kt` 自身——它要一并删除，所以不计入消费点）逐个换回，并**删除** `ui/theme/PiContrast.kt` 与 `ui/theme/PiPalette.kt:112-161` 的 5 个派生属性：
  | 派生 | → pi 原 token | 消费点 | 文件 |
  |---|---|---|---|
  | `bodyOnTool` | `toolOutput` | 18 | 14 |
  | `contextOnTool` | `toolDiffContext` | 2 | 2 |
  | `thinkingBodyOnCanvas` | `thinkingText` | 3 | 1 |
  | `metaOnCanvas` | `muted` | 2 | 2 |
  | `metaOnCard` | `dim` | 1 | 1 |- **依据**：`docs/pi-android-ui-spec.md:95`「语义色保真」+ 用户本轮裁决"不做任何对比度调整"。pi 的这两个失败组合是它自己的取值：`toolOutput #808080` 在 `toolPendingBg #282832` 上 **3.69:1**、在 `toolSuccessBg #283228` 上 **3.37:1**（`ui/theme/PiPalette.kt:126-128` 自己记的数）。**照原值用 = 深色下工具卡正文低于 §9 的 4.5:1 地板。这是 pi 的原值，作为已知取舍写在这里，我们不改**（与 `07` D9 对"已知可读性代价"的处理方式一致）。
- **冲突**：**【与既有裁决冲突·需追加账本】** `07` D2（`07-construction-decisions.md:31-34`）裁决「被拒用 `palette.bodyOnTool`，不新增颜色」——它的理由正是对比度。撤销派生 token 后 `ui/theme/PiStateChip.kt:120` 的 `StateTone.Rejected` 目标是什么，需要一句话追加到 `07`：
  - 选项 A（推荐，跟 pi）：`StateTone.Rejected → palette.toolOutput`。被拒卡落在 `toolPendingBg` 上，`toolOutput` 在那里 3.69:1。
  - 选项 B：`StateTone.Rejected → palette.muted`（D2 原话明确排除过"muted 更暗"）。**不推荐**。
- **不能机械替换的 6 处**（它们的语义本来就不是工具正文，替换目标各不相同，逐条给目标）：
  | 位置 | 现状 | 依据 | 目标 |
  |---|---|---|---|
  | `ui/blocks/ToolBlockChrome.kt:234` | 展开 chevron 的 `tint` | pi 卡上没有 chevron；pi 表达"还能展开"的是 `read.js:72` 的 `theme.fg("dim", " (ctrl+o to expand)")` | `dim` |
  | `ui/components/PiCommon.kt:335` | 上下文环里的 `?` | pi 的未知百分比**不加任何 `theme.fg`**（`components/footer.js:136-145` 的 `?` 分支直接 `contextPercentStr = contextPercentDisplay`） | `text` |
  | `ui/screens/BootScreen.kt:261` | 启动卡上的 mono 说明行 | pi 无对应构件；pi 的说明句一律 `muted`（`bash-execution.js:38`、`read.js:99`） | `muted` |
  | `ui/screens/WorkspaceChrome.kt:565` | 错误行下的 mono 详情 | 同上 | `muted` |
  | `ui/screens/WorkspaceViewer.kt:584` | 查看器行号列 | pi 的 `read` 正文含行号，整体是 `theme.fg("toolOutput", …)`（`read.js:97`） | `toolOutput` |
  | `ui/screens/WorkspaceViewer.kt:606`、`:612` | `⋮` 与「其余 N 行未显示」 | pi 的同类提示是 `muted`（`read.js:99`） | `muted` |
  | `ui/blocks/ImageGridBlock.kt:254` | 图片占位格标签 | pi 的工具图 fallback 文字是 `toolOutput`（`tool-execution.js:307` 传 `{ fallbackColor: s => theme.fg("toolOutput", s) }`）；用户消息图片 pi 不画 | `dim`（按用户给的映射）；若该格只用于**工具返回图**，`toolOutput` 更贴 pi |
- **实施标注（红线）**：清单里命中红线的文件——`ui/screens/WorkspaceChrome.kt`、`ui/screens/WorkspaceViewer.kt`（工作区那几个文件）——**本轮只列不改**，等对应批次落地；其余（`ui/blocks/**`、`ui/components/`、`ui/theme/`）不属红线。
- **代价与风险**：26 处一次性替换**极易把注释里的名字也换掉**（`07` D14 记过一次注释事故）；`ui/extension/ChromeColor.kt:83-84` 是**导出给扩展的颜色清单**，删 token 会让它少两项（需要同步删除或保留兼容），这条必须单独确认。
- **上机验收点**：深浅两套主题下逐屏过一遍工具卡（三态 + 被拒）、diff 卡、思考块、日期分隔、提示条、图片占位、工作区查看器行号、Boot 卡；重点看**深色下工具卡正文**（3.37–3.69:1）是否仍可读——可读性下降是这次裁决**接受**的代价，验收时只记录、不改。

### 6.3 建议改 · `!` 命令面板补齐 pi 的整框

- **pi 是什么样**（`components/bash-execution.js:22-40`）：`Spacer(1)` → **上边框**（`DynamicBorder`：`"─".repeat(width)` 满宽，`components/dynamic-border.js:17`）→ 内容容器〔`$ command` 头（`:36`，`bashMode` + bold）、loader（`:37-38`，转圈 `bashMode`、说明句 `muted`）〕→ **下边框**（`:40`）→ 完成时用 exit code 行收尾（`:138`）。整盒**没有底色**，只有这两条线。
- **实施标注（红线）**：`ui/chat/**` 属红线（会话加载批次在写），这一节的实现**等该批次落地后再做**，本轮只列不改。
- **我们缺什么**：`ui/chat/BashPanel.kt:51-55` 是 `Surface(color = cardBg, shape = cardInner(12dp))`，`:58-62` 只有 `$ command` 一行用 `bashMode`（`!!` 时 `dim`，`:54`）；**没有那两条线**。占比 0.72% vs pi 6.45%（主表 #1）。
- **落地（贴 pi 的满宽 + 贴 v2 的圆角语言，两者都要）**：
  1. 保留现有 `cardBg` 底 + 12dp 圆角（pi 在终端里没有"底"，v2 的卡片语言要求有底；这是**按 v2 落地 pi 的信息**，不是偏离）；
  2. 用 `Modifier.drawBehind` 在卡片上、下各画一条 **1dp** 全宽的 `palette.bashMode` 线（`!!` 时 `palette.dim`，与 `:54` 的 `accent` 同一个来源变量）。画在 `Surface(shape = cardInner)` 的裁剪之内，两端会被 12dp 圆角自然裁掉——既是 pi 的"满宽"，又不破坏 v2 的圆角；
  3. 线宽只能是 **1dp**（`06 §2`「线宽：全篇只有 1px」，`PiSpacing.hairline`）；不要用 2dp 以上去追 pi 的字符高度（终端 `─` 是一整格高，手机上没有对应物，1dp 是 v2 唯一的线宽）；
  4. `06 §2` 的块间距 8dp 与卡片内边距 12dp 都不动。
- **冲突**：**【不冲突】**（板子没有 bash 盒这个构件，`06 §5` 也没有列它）。
- **转圈：已裁决不补（不再是待裁决项）**。pi 的 loader 是**循环动画**（`bash-execution.js:37-38`，80ms 换帧，`pi-tui/dist/components/loader.js:2-4`），而 `06 §5` 明写「**没有**…循环动画」、`docs/pi-android-ui-spec.md:161-188` 的动效系统也没有循环项。**裁决：不补，保留现有的「停止」按钮**（`BashPanel.kt:64-71`，`tint = error`）。依据两条：① `06 §5` 的硬规定；② pi 的 loader 是终端字符动画，手机上用一个**可点的 Stop** 更合适。
  `bashMode` 的面积差（6.45% vs 0.72%，主表 #1）由**上下两条全宽线 + 命令头**这一处改动修掉，转圈不参与面积贡献。
- **顺带一条结构差（只记录、不改）**：pi 在**引擎空闲时把 `!` 命令盒挂进转录**（`interactive-mode.js:5470-5477` 的 `chatContainer`），只在流式中才挂进 pending 区（`:5476-5479`）；我们**始终**挂在输入区上方（`ui/screens/ChatScreen.kt:1720-1727`），对应的是 pi 的流式分支。位置差不是颜色差，本轮不动。
- **上机验收点**：`!` 与 `!!` 各跑一次，深浅两套主题下看两条线是否满宽、是否被 12dp 圆角整齐裁掉、是否与标题行的 `$ command` 同色；完成态（exit code 行）与运行态都能看到上下两条线；缩放字号后线仍贴边。

### 6.4 列表选中行用 `selectedBg` 整行底 —— **需要一句话确认（两个列表的落法不同）**

先把 pi 的语义说准（这决定了怎么落）：

| pi 的状态 | 表现 | 依据 |
|---|---|---|
| 会话列表**光标行** `isSelected` | accent 光标 `› ` + 粗体 + **整行 `selectedBg` 底** | `session-selector.js:395`、`:419-421` |
| 会话列表**当前会话** `isCurrent` | **只有名字**染 accent（不是底） | `session-selector.js:402-408` |
| 会话树**光标行** `isSelected` | 光标格 + 正文实际宽涂 `selectedBg`（**不是满行**） | `tree-selector.js:595-605` |
| 「↓ Jump to latest message」指示条 | `selectedBg` 底 + `text` 字的一行文字标签 | `tui-renderer.js:14-18`（**不是列表行**，见 §2.1 更正） |

**板子在这两点上与 pi 一致**：`direction-b-v2.html:1812` 的 `SwipeRow` 就是 `background: p.selected ? 'var(--selected-bg)' : 'var(--surf-low)'`，而 `:1820` 的当前会话是 `Badge`（accent）；`:1891` 把 `selected` 设成"**长按/操作框那一行**"（`onSelect={()=>{setSel(r.t);setSheet(true);}}`）。也就是说：**板子早就有 `selectedBg` 整行底这一档，我们只是没实现它。**

落法（三处，逐条）：

1. **会话列表（做）**：给"**行上有未结束操作**"的那一行加整行 `selectedBg`。精确改法：
   - `ui/screens/SessionsScreen.kt:403` 的状态 `actions = summary` 已经存在（长按设置）；
   - 在 `:395-404` 的 `SessionRow(...)` 调用里加 `selected = actions?.file?.absolutePath == summary.file.absolutePath`；
   - `:762-765` 的 `.background(MaterialTheme.colorScheme.surfaceContainerLow)` 改成 `if (selected) PiTheme.palette.selectedBg else surfaceContainerLow`；
   - 行的圆角（`first`/`last` 只圆两端，`:759-763`）不动——底换了、形状不换。
   对照度（facutal，不做调整）：`selectedBg` 相对页面底 dark **1.59:1** / light **1.43:1**（`#3A3A4A` on `#18181E`、`#D0D0E0` on `#F8F8F8`），行内文字在它上面 dark 7.52:1 / light 10.37:1。**这就是 pi 与板子的原值，不改。**
2. **会话树（建议不做）**：pi 的树 `selectedBg` 也是**光标行**（`tree-selector.js:603-604`），而它是键盘列表；我们的树是触屏、行尾"分叉"按钮**直接执行**、没有打开操作框的中间态（`ui/chat/SessionTreeScreen.kt:749-782` 里没有任何 `selected` 状态）。**要落就只能发明一个新状态**（点一行先高亮、再点按钮才执行），那是新增交互，`06 §5`「不要自行脑补」明确禁止。落法改为：`onActivePath` 的 2dp `primary` 竖线（`:773-782`）**保持**——它对应 pi 的 `tree-selector.js:592` 的 `• ` accent 记号，语义相同、形态是我们自己的。
3. **「回到最新」不是列表行**：`tui-renderer.js:17` 的 `selectedBg` 属于 **`↓ Jump to latest message` 浮层指示条**（`tui-renderer.js:14-18`），它对应的是我们对话页右下角那颗**下箭头**。这条已由用户单独裁决：**下箭头 = `selectedBg` 底 + `text` 字形**（§7.6），与会话列表的整行底是两件不同的事，互不牵连。

**会不会影响板子的观感**：不会引入新色（`--selected-bg` 板子自己在 9 处用过），只是把板子已有的 `selected` 档补上；分隔线在行内、底色在行上，两者不冲突。

**实施标注（红线）**：`ui/screens/SessionsScreen.kt` 不在红线清单里（可做）；但同批若涉及 `ui/screens/ProjectScreen.kt` 等红线文件，同样只列不改。

**代价与风险**：`actions` 关闭后是否清底需要一个决定（板子的 `sel` 不会清）。建议**跟随操作框**（sheet 关闭即清），理由是底色表达的是"现在这一行正被你操作"，不是"上次操作过它"。

**上机验收点**：长按任意一行 → 该行整行变 `selectedBg`，操作框关闭后恢复；当前会话行的徽标与底色能同时读懂（"当前" vs "正在操作"是两个事实）；组内首/末行的圆角不被底色破坏；深浅两套主题下都没把正文压暗到读不出来。

### 6.5 明确不改（用户裁决）· 搜索高亮 `searchMatchBg` / `searchMatchText`

**用户原话**：「整行是指的搜索时候是吧？如果是搜索时就不要管了，保持现在就行。」

**裁决**：**保持现状，不改**。`ui/screens/ChatScreen.kt:1521-1529` 的整行底（当前命中另加 1dp `searchMatchText` 描边）原样保留；`ui/settings/SettingsSearchScreen.kt:338-360` 的设置搜索命中词级高亮也原样保留（两者本来就不同，本次不统一）。

**事实留档（为什么"改"不划算，也是这条裁决的依据）**：
- pi 的高亮粒度是**逐字**的：`tui-renderer.js:9` 定义样式 → `tui-alt-screen.js:1300-1311` 按 `segment.startCol/endCol` 对**已渲染的屏幕列区间**重绘，当前命中额外 `bold + inverse`（`tui-renderer.js:11-12`）。它靠的是"屏幕字符层"这个对象，Compose 没有。
- 我们的查找只产出**行级索引**：`ui/screens/ChatScreen.kt:1516-1526` 的 `searchMatches` 是"哪几个转录条目命中"的下标列表（`:597-607` 用 `searchTextOf(item).contains(query)` 算出），**没有字符区间**。
- 所以逐字对齐 pi 的代价是：给 `BlockRenderer` 的每种 block 传查询与区间，各自产出 `AnnotatedString`，并各写测试；而助手正文与用户消息走第三方 markdown 渲染器（`ui/render/PiMarkdown.kt`），它不暴露 token 级 span，那一半**到不了 1:1**（改完是"mono 词级 + markdown 无高亮"，两侧都不一致）。
- 面积差确实存在且很大（§4.4：一处 6 字命中，pi 占屏 0.20%、我们占屏 6.48%，约 32 倍），**按用户裁决记录、不改**。

**将来若真要改，最小路径**（留给以后，不在本轮）：把 `ui/settings/SettingsSearchScreen.kt:338-360` 的 `highlightMatch` 提成共享 helper → 给 `ui/blocks/BlockChrome.kt` 的 `MonoText`/`ProseText` 加可选 `query` 参数 → `BlockRenderer` 透传；markdown 块维持"只跳转、不涂底"，并在文档里写明这是刻意的半程。

### 6.6 `infoBg`：**不动**（结论）

- 事实：pi 的 TUI **零消费**（§2.2），我们是 2 处小面积：工作区 Warning 提示条（`ui/screens/ProjectScreen.kt:2365`，调用点在 `:2360-2370` 的 `WsNotice`）与扩展 snack 的 Warning 档（`ui/extension/ExtensionUiHost.kt:155`）。
- **更正任务书里的调用点**：`ui/screens/WorkspaceChrome.kt` 那处用的是 `toolErrorBg`（`:379`）不是 `infoBg`；`infoBg` 的第二处实际在 `ui/extension/ExtensionUiHost.kt:155`。
- 结论：这属于"**pi 没有对应构件**"的部分（`06 §4` 的 Snack 三档与 `06 §3` 构件 17 是本 App 自己的实体，`docs/pi-android-ui-spec.md:92` 已经把 `infoBg` 定义成"SurfaceContainerHigh 的基准"）。**不改**——照 1:1 的严格读法它无处可对，照"用户新增构件只能用 pi 的 token"的读法它用的正是 pi 的 token。**不需要动。**

### 6.7 明确不改 / 不建议改（并说明为什么）

| 项 | 为什么**不**改 |
|---|---|
| 搜索高亮整行底（`searchMatchBg`/`searchMatchText`） | **用户裁决不改**（§6.5）：我们只有行级索引、pi 是逐字列区间；逐字改要给每种 block 传区间并各写测试，且 markdown 那一半到不了 1:1 |
| `pageBg` 占满全屏 | export-only token 的正常用法（§2.2）。pi 的 TUI 不上色不等于"我们不能画底"，`docs/pi-android-ui-spec.md:92` 明确把 `pageBg` 定为 `Surface` 基准 |
| `userMessageBg` 占比比 pi 高 4pp（同内容口径） | 差值来自 v2 冻结的气泡内边距 12 与时间行（`UserMessageBlock.kt:112-147`）。改它等于改板子 |
| 工具卡底色占比（`toolSuccessBg` +0.53pp） | 已经几乎一致，没有可改的空间 |
| 列表行/卡片的 `surfaceContainerLow` 底 | pi 的列表行没有底，但板子（`06 §2` 卷「Card」）与 `07` D37 都要求分组卡有底；这是**板子与 pi 的既有分歧**，`11-designer-adjudication.md` 的"外观与版式以 v2 为准"已经判过 |
| 我们比 pi 多画的图片网格 | 平台能力增量（pi 的 TUI 显示不了图片附件），不是配色问题 |
| 「当前会话」徽标保持 accent | 见 §6.4 第 2 行与 `SessionsScreen.kt:736-739`：pi 的当前会话是 accent **文字**，板子给的是徽标；两者都指向 accent，不冲突 |
| 终端页、工作区两栏等 | 与本次面积审查无关（`06 §5` 明确没做） |

---

## 7. 对话转录视口的滚动条（用户裁决：只做这一处）

> **本节的三条裁决（都已生效，不再是待裁决项）**
> 1. **要不要做**：「V2 有滚动条吗？有 就按 v 二的去做，并且按 pi token 一比一」→ 做。
> 2. **做在哪**：「对话界面有一个就行。别的地方不用做。」→ **只做对话页的转录视口**，其它任何容器都不加。
> 3. **怎么取色**：几何/行为按 v2，颜色按 pi 的两个 token **原值**，**不做任何对比度调整**（与全文通则一致）。

### 7.0 范围：一处，不是一类

**做**：对话页转录视口 = `ui/screens/ChatScreen.kt:1436` 的 `LazyColumn`。

这条收窄同时是 **1:1 pi** 的：pi 全仓**只有一处**滚动条接线（`modes/interactive/interactive-mode.js:629-630` 把 `scrollbarTrackStyle`/`scrollbarThumbStyle` 交给 `createChatViewport`），而 `createChatViewport` 里也只有**一个** `new ScrollView`（`modes/interactive/chat-viewport.js:4`，接的就是转录）。pi 的其它列表（会话选择器、树选择器、设置选择器）**都不画滚动条**，只用一行文字计数（`session-selector.js:424-426` 的 `(3/8)`）。所以"只做对话页"不是妥协，是原样。

**不做**：其余所有可滚容器（清单与依据见 §7.7）。它们继续用各自既有的滚动方式，不加任何滚动条视觉。

### 7.1 几何：按 v2（`direction-b-v2.html:143-146`）

| 属性 | v2 原文 | 我们落成 | 依据 |
|---|---|---|---|
| 宽 | `::-webkit-scrollbar{width:3px}`（`:144`） | **3dp** | 板子唯一的滚动条宽度 |
| 滑块圆角 | `::-webkit-scrollbar-thumb{…;border-radius:2px}`（`:145`） | **2dp** | 同 |
| 位置 | 滚动容器末尾（右侧）边缘 | 视口右缘，`Modifier.align(Alignment.CenterEnd)` | 同 |
| 与内容的关系 | `scrollbar-width:thin` / `::-webkit-scrollbar` | **overlay：覆盖在内容之上，不占布局宽** | 见下 |
| 轨道高度 | 浏览器默认 = 容器高 | 视口全高 | — |
| 滑块长度 | v2 **没有**写规则（CSS 滚动条的长度由浏览器按比例算） | **取 pi 的公式**：`max(24dp, round(trackH² / contentH))`，另外"内容不超出视口时不画" | pi `pi-tui/dist/layout.js:191-192`；最小长度把 pi 的"最少 2 格"折成 24dp（**估**，见下） |

**"几何按 v2、行为按 pi"的接缝**：v2 只给了宽度、圆角、颜色、透明度，**没有给滑块长度算法**（那是浏览器内置的）。所以长度这一项按 pi 的公式取——pi 是 `minThumbHeight = min(2, trackH)`、`thumbHeight = max(minThumbHeight, min(trackH, round(trackH²/contentH)))`（`layout.js:191-192`）：按**可视比例**，并且有一个最小长度。pi 的"2 格"在手机上折算成 dp 只能靠假设（我们一屏 36 行、`06 §2` 的 mono 行高 20dp），取 **24dp**（≈ 一行 mono 13/20 + 4）——**这是估算，不是 v2 或 pi 的原文**。

算出来的滑块长度（视口 718dp，内容为 N 屏）：

| 内容长度 | 滑块长 | 占轨道 |
|---|---|---|
| 1.2 屏 | 598dp | 83% |
| 2 屏 | 359dp | 50% |
| 3 屏 | 239dp | 33% |
| 10 屏 | 72dp | 10% |
| ≈30 屏以上 | 触底到 24dp 最小值 | — |

**为什么是 overlay 而不是挤占 3dp 宽**：
- **v2 在浏览器里其实是"占位"的**：Blink/WebKit 的 `::-webkit-scrollbar` 是 classic（经典）滚动条，那 3px 会挤压内容宽度。这一点必须写明——我们的实现**有意偏离原型的渲染结果**。
- **依据两条**：① pi 的 `auto` 模式本身就是 overlay——它把滚动条字符**覆盖写**在屏幕最后一列上（`layout.js:207-221` 的 `replaceScrollbarCell` 保留了目标行的背景），只有 `always` 模式才把内容宽度减 1 列（`pi-tui/dist/components/scroll-view.js:62-64`）；② 手机屏宽最贵，挤 3dp 会让每一行正文重新折行。
- overlay 还有一条实际好处：3dp 落在页水平内边距（14dp，`06 §2`）之内，**不压住正文的最后一列字符**。

### 7.2 颜色：pi 的两个 token 原值（1:1）

| 部件 | token | dark（`ui/theme/PiPalette.kt:178-179`） | light（`:241-242`） |
|---|---|---|---|
| 轨道（整列） | `scrollbarTrack` | `#505050` | `#B0B0B0` |
| 滑块 | `scrollbarThumb` | `#D4D4D4` | `#1F2328` |

**必须显式记录的冲突**：v2 写的是 `::-webkit-scrollbar-track{background:transparent}`（`direction-b-v2.html:146`，**轨道透明**），而"按 pi token 一比一"要求**把轨道画出来**（pi 就是先铺满一列 `scrollbarTrackStyle("│")`，再把滑块字符盖上去：`layout.js:216-220`）。

**裁决**：按**用户裁决 = pi 1:1** 处理，**画轨道**。理由：
1. 用户这一轮的原话是"按 pi token 一比一"，轨道是 pi 滚动条的两半之一（`scrollbarTrack` 在我们的代码里目前是 0 绘制点，见 §3）；
2. pi 的 track 承担"这一屏是可滚的"这个常驻信号，而 v2 的透明轨道是因为原型把滑块写成了单色 `--muted`（不需要衬托）；我们用的是 pi 的 token 对（**暗轨 + 亮块**），轨道正是对比的来源；
3. 不画轨道的话，浅色主题下的滑块（`#1F2328`）在 `#F8F8F8` 页面上会显得孤零零一条，而深色下的亮滑块也没有"轨道"这个容器感。

**不做任何对比度调整**（用户通则）。把 §7.3 的不透明度也算进去，四组数是（**事实记录，不是修正建议**）：

| 组合 | 深色 | 浅色 |
|---|---|---|
| 轨道对页面底（α1） | 2.19:1 | 2.04:1 |
| 轨道对页面底（α0.55，默认态） | 1.49:1 | 1.45:1 |
| 滑块对页面底（α1 / α0.55） | 11.92:1 / 4.43:1 | 14.88:1 / 3.58:1 |
| 滑块对轨道（α1） | 5.44:1 | 7.28:1 |

读法：**默认态"不明显"由 α0.55 承担**（轨道 1.49/1.45、滑块 4.43/3.58），**激活态"明显"由 α1 承担**（11.92/14.88）。轨道本身低于 §9 的 3:1 图形下限，**照 pi 原值用，不修正**。

### 7.3 出现 / 消失：按 pi，但选它自己的 `always` 模式

pi 有三种模式（`modes/interactive/components/settings-selector.js:482-486`：`auto | always | hidden`；默认走 `auto`，`core/settings-manager.js:874-877`）：
- `auto`：内容超出视口（`scroll-view.js:54-57`）**且** 正在滚动时出现，`scrollbarHideDelayMs` 默认 **1000ms**（`scroll-view.js:19`）后**完全消失**；
- `always`：轨道 + 滑块**恒显**（`scroll-view.js:54-55`），同时内容宽度减 1 列（`:62-64`）；
- `hidden`：不画。

**一处必须显式处理的冲突**：用户更早的原话是「默认不是很明显，点击会变大一点，我可以上下自由拖动」；而 pi 的 `auto` 在 1 秒后**整条消失**——消失之后"点击变大"就无从发生。

**处理（推荐）**：取 pi 自己的 **`always` 模式**当基线（轨道+滑块常驻），再把"明显/不明显"和"变大"表达在**宽度与不透明度**上；`auto` 的那个 **1000ms** 仍然用上，但它的作用从"隐藏"改成"激活态回落"。这样既没有发明 pi 没有的东西（`always` 是 pi 的三个模式之一），也满足用户的可点击要求。

| 态 | 触发 | 轨道 | 滑块 | 宽度 |
|---|---|---|---|---|
| **默认（静止 ≥1s）** | 无滚动、无触摸 | `scrollbarTrack`，不透明度 **0.55** | `scrollbarThumb`，不透明度 **0.55** | 轨道 3dp / 滑块 3dp |
| **滚动中 / 指针按下** | `isScrollInProgress` 或按下 | `scrollbarTrack`，不透明度 1 | `scrollbarThumb`，不透明度 1 | 轨道 3dp / 滑块 **6dp**（"变大一点"） |
| **拖动中** | 手指抓在滑块/轨道上 | 同上（不透明度 1） | 同上 | 滑块 6dp；拖动位置实时跟随 |
| **内容不溢出** | `contentHeight ≤ viewportHeight` | 不画 | 不画 | — |

- 透明度只用 v2 已有的档位（`06 §2` 的 alpha 阶梯里有 `.55`，见 `direction-b-v2.html:179` 的 `.hair{opacity:.55}`），**不引入新透明度**。
- 3dp 是 v2 的滚动条宽度；6dp 是 v2 的卡片内间距档（`PiSpacing.gutter`），两者都是既有数字。
- 拖动结束后回到"默认"态延迟 **1000ms**（pi 的 `scrollbarHideDelayMs` 默认值）——**一次性延时**，不是常驻计时器（§7.5）。

### 7.4 交互（按用户原话）

**命中区（手指 ≥24dp，视觉仍 3dp 级）**

| 项 | 值 | 理由 |
|---|---|---|
| 视觉宽 | 3dp（激活 6dp） | §7.3 |
| 命中区宽 | **24dp**，贴屏右缘；竖直 = 轨道全长（再各留 8dp 容差） | 手指能按准的最小可靠宽度；**不用 48dp** 的理由：在 360dp 屏上 48dp = 屏宽的 13.3%，而 24dp = 6.7%，且 24dp 已经完整覆盖"页边距 14dp + 滑块 3dp + 7dp 容差" |
| 命中区与两枚箭头 | 箭头在右下角（`ChatScreen.kt:1631-1635`，`BottomEnd`、padding 12dp、30dp 见 `:2890-2892`），与滚动条的右下端**会重叠** | Compose 后绘制的兄弟节点先命中 → 箭头优先；**本轮不动箭头位置**（动它要改 `07` D33 的裁决）。若实机出现"滑到底部时拇指拖不到滑块"，备选是把箭头 padding 从 12dp 提到 36dp，届时请用户裁一句 |

**手势冲突的具体解法**

| 冲突对象 | 解法 |
|---|---|
| 列表惯性滚动 / 拖动 | 命中区内的 down **不消费**，只在**垂直 slop 达成**后才接管（`awaitVerticalTouchSlopOrCancellation`）；一旦抓住就取消正在进行的 fling（`scrollToItem` 会中断它），与 pi 的"抓滑块即接管"一致（`tui-alt-screen.js:888-909`） |
| 长按文本选择（`SelectableContent`，D33 第 3 条） | 按住不动 ⇒ 垂直 slop 永不达成 ⇒ 我们的手势**放弃且不消费**，事件落到文本层，选择照常开始。命中区里那 10dp 与文本列重叠的部分因此不会"吃掉"长按 |
| 左右滑返回（系统手势导航） | down 不消费 + 垂直 slop 门槛（横向滑动不满足垂直 slop）⇒ 系统边带照旧拿到手势；命中区不向屏内超过 24dp，不扩大与边带的重叠 |
| 与尾部跟随（`TailFollow`） | **最容易漏的一条**：拖动开始 → `pauseTail()`（`ChatScreen.kt` 已有的函数，`:1643` 与 `:1666` 的调用点），否则拖动过程中新 token 到达会把视口拉回底部。pi 的 `scrollTo` 同样会清掉 `followingEnd`（`pi-tui/dist/components/scroll-view.js:105-124`） |

**按在轨道上 = 跳转 + 进入拖动**（1:1 pi）：`pi-tui/dist/tui-alt-screen.js:891-909` —— 按在滑块上则 `grabOffset = 指针 y − 滑块顶`；按在轨道空白处则 `grabOffset = 滑块高 / 2` 并**立即把滑块中心移到指针位置**（`scrollScrollbarToPointer`，`:866-872`），然后继续拖动。我们照做。

### 7.5 实现路径

**新增文件：一个** —— `app/src/main/kotlin/app/pi/ui/components/PiScrollbar.kt`

因为范围只剩对话转录视口，**不需要通用适配器**（不用为 `ScrollState` 写第二套、不用逐屏 hoist state）。API 草案：

```kotlin
/**
 * 对话转录视口的滚动条。几何取 v2（3dp / 圆角 2dp / 贴右缘 overlay），
 * 颜色取 pi 的 scrollbarTrack / scrollbarThumb 原值，出现与拖动按 pi。
 */
@Composable
fun PiScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,   // 调用点传 Modifier.align(Alignment.CenterEnd)
)
```

内部三块：

1. **画**：`Modifier.drawBehind {}`，在 **draw lambda 里**读 `state.layoutInfo` / `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`。
   **为什么必须在 draw 阶段读**：在 composable 体里读 `firstVisibleItemScrollOffset` 会让这个 composable **每帧重组**；在 draw lambda 里读只让**绘制阶段**失效（滚动条是一张图，重画就够了）。这是"不增加负担"的第一条。
   比例（**估**）：`visibleFraction = visibleItemsCount / totalItemsCount`；`positionFraction = (firstVisibleItemIndex + 首个可见项的 offset/自身高度) / max(1, totalItemsCount - visibleItemsCount)`。总高不精确可用（LazyColumn 不报内容总高），所以滑块长度是**近似**的——这一点写在代码注释里，不要假装精确。
2. **手势**：`Modifier.pointerInput(state) { awaitEachGesture { … awaitVerticalTouchSlopOrCancellation … } }`；拖动时把指针 y 映射成 `scrollToItem(index, offset)`（LazyColumn 只能按 item 滚：index 取整 + 剩余比例 × 该 item 的实测高度）。
3. **态**：`active` / `dragging` 用普通 `remember`——**不写 `Saver`、不进 `SaveableStateRegistry` 的 Bundle**（它是装饰态；与 `TailFollowSaver`（`ChatScreen.kt:2969`）不同，那个存的是**位置**，必须存）。回落延时用 `LaunchedEffect(isScrolling || dragging) { if (!…) { delay(1000); active = false } }`——**随状态自杀的协程，没有常驻计时器、没有轮询**（这是"不增加负担"的第二条）。
4. **依赖**：只用已经在用的 `androidx.compose.foundation` / `ui`；**不加任何依赖**（第三条）。

**接线（在 `ChatScreen.kt` 里，属红线，等会话加载批次落地）**：

| 位置 | 现状 | 改成 |
|---|---|---|
| `ui/screens/ChatScreen.kt:1412` | `Box(Modifier.weight(1f).fillMaxWidth())` —— 转录与两枚箭头**已经在同一个 Box 里** | **不动**：overlay 直接加进这个 Box（`PiScrollbar(listState, Modifier.align(Alignment.CenterEnd))`），**不需要新增 Box** |
| `ui/screens/ChatScreen.kt:358` | `val listState = rememberLazyListState()` | **不动**（state 已经显式；这是范围收窄换来的最大收益：不需要 hoist） |
| `ui/screens/ChatScreen.kt:1436` | `LazyColumn(state = listState, …)` | **不动** |
| 拖动开始处 | — | 调用已有的 `pauseTail()`（同 `:1643` 的用法） |

改动量：**新增 1 个文件 + `ChatScreen.kt` 加 1 行调用 + 拖动处 1 行**。

**不做的事（明确）**：不给 App 加"滚动条模式"设置项（`always/auto/hidden` 是 pi 的设置，加一行是新增实体，`07` D38.2「如无必要，勿增实体」）；不做 `always` 模式那个"内容宽减 1 列"（我们是 overlay）。

### 7.6 两枚浮层箭头（用户重新裁决后的最终口径）

`scrollbarTrack` / `scrollbarThumb` 既然回到滚动条本义，就不再用于箭头。新的两条：

| 按钮 | 底 | 字形 | 描边 | 依据 |
|---|---|---|---|---|
| **下箭头「回到最新」** | `selectedBg` | `text` | 去掉，或保持 `borderMuted`（见下） | **1:1 pi** 的 `↓ Jump to latest message` 指示条：`theme.bg("selectedBg", theme.fg("text", label))`（`modes/interactive/tui-renderer.js:14-18`），画在视口底部一行、只在**不跟随末尾**时出现（`pi-tui/dist/tui-alt-screen.js:1387-1407`） |
| **上箭头「回到顶部」** | `surfaceContainerHigh`（现状） | `onSurface`（现状） | `borderMuted`（现状） | pi **没有**"回到顶部"的对应物（我 grep 过 `scrollToStartIndicator` / `Jump to top`，零命中），所以**不发明映射**，保持现状 |

**实现代价（必须写清）**：`ScrollArrowButton`（`ui/screens/ChatScreen.kt:2917-2957`）目前**一套颜色服务两颗按钮**（`:2930` 底、`:2931` 描边、`:2944` 字形）。要按上表分色，需要给它加一个 tone 参数（例如 `enum class ScrollArrowTone { Top, Down }`，默认 `Top`），两个调用点（`:1638`、`:1656`）各传一个；**尺寸、命中区、`SCROLL_ARROW_IDLE_ALPHA = 0.62f`、按下态 `1f`、未读角标（`accent`，`:2954`）全部不动**。
下箭头的描边怎么办：pi 的指示条**没有描边**（它是一行文字标签，不是圆钮）。两个选项——(a) 去掉下箭头的描边（最贴 pi，但两颗按钮形状感不一致）；(b) 保持 `borderMuted` 描边（与上箭头对称，视觉上仍是一个"按钮"）。
**我建议 (b)**：描边不是颜色语义，是"这是一个可点的东西"的形态；去掉它会让下箭头在 `selectedBg` 底（对页面 dark 1.59:1 / light 1.43:1）上难以被认出来。

**与 v2 的差异（记录）**：板子在 `direction-b-v2.html:1515` 给这个浮层胶囊定的三值是 `--surf-high / --border-muted / --text`。下箭头改用 pi 的 `selectedBg`/`text` ⇒ **偏离板子这一处**（按用户裁决）；上箭头继续用板子那三值（`--surf-high` 就是 `surfaceContainerHigh`，`ui/theme/PiTheme.kt:396`）。因此**两颗按钮颜色不同是有意的**：它们本来就是两件不同的事（pi 只有"回到最新"）。

**对比度（事实，不调整）**：`selectedBg` 对页面 dark 1.59:1 / light 1.43:1；`text` 在 `selectedBg` 上 dark 7.52:1 / light 10.37:1。字面清晰度没问题，底的对比度低于 3:1 而**不改**。

### 7.7 明确不加的容器（用户裁决 + pi 只有一处）

| 容器 | 我们的 file:line | 判定 |
|---|---|---|
| 会话列表 | `ui/screens/SessionsScreen.kt:383` | 不加 |
| 会话树（两种视图） | `ui/chat/SessionTreeScreen.kt:442`、`:888` | 不加 |
| 设置首页 / 分组 / 搜索 | `ui/settings/SettingsHome.kt:124`、`SettingsGroupScreen.kt:153`、`SettingsSearchScreen.kt:114` | 不加 |
| 设备能力 / 扩展包 / 模型 / 凭证 / 许可 / 诊断 | `ui/device/DeviceCapabilityScreen.kt:251`、`packages/PiPackagesScreen.kt:225`、`ui/settings/PiModelsScreen.kt:149`、`PiCredentialScreen.kt:283`、`LicensesScreen.kt:181`、`:293`、`DiagnosticsScreen.kt:101` | 不加 |
| 工作区（现场 / 文件 / 查看器） | `ui/screens/ProjectScreen.kt:496`、`WorkspaceViewer.kt:503`、`:527`、`:614`、`:758`、`WorkspaceChrome.kt:1139` | 不加 |
| sheet / 对话框正文 | `ui/chat/ChatSheets.kt:117`、`:223`、`:305`、`:524`、`:580`、`:968`、`ui/extension/ExtensionDialogs.kt:174`、`WorkspaceChrome.kt:1139` | 不加 |
| 菜单 / 候选面板 | `ui/components/PiCommon.kt:437`（`PiMenu`）、`ui/chat/SlashPalette.kt:66`、`MentionPalette.kt:55` | 不加 |
| 编辑器（多行 / 列表） | `ui/settings/PiSettingsEditors.kt:184`、`:648`、`:808` | 不加 |
| `!` 命令输出预览 | `ui/chat/BashPanel.kt:92`（`heightIn(max=220.dp)` + `verticalScroll`） | 不加 |
| Boot 页 | `ui/screens/BootScreen.kt:77` | 不加 |
| 输入框内部（v2 在 `Composer :1406` 挂了 `.b-scroll`，`maxHeight:172`） | `ChatScreen.kt:2701-2705`（`heightIn(min=20, max=120)`，滚动交给 Compose 自己，D34） | 不加：`BasicTextField` 的内部滚动状态不暴露，要拿它得换渲染路径；且上限 120dp < v2 的 172px，实际很少触发 |
| 横向滚动（表格 / 代码 / 键条 / 设置搜索 chip 行） | `ui/render/PiMarkdownComponents.kt:422`、`:452`、`ui/settings/SettingsSearchScreen.kt:261`、`PiSettingsEditors.kt:599`、`ui/terminal/TerminalKeyBar.kt:214` | 不加：v2 的 `.b-scroll` 是 `overflow-x:hidden`，只管纵向 |
| 终端 | `ui/terminal/TerminalPane.kt`（`script(1)` 把网格 pin 死，`:163-169` 注释） | 不加：没有 scrollback 模型，网格也不可滚 |

### 7.8 上机验收点（真机）

1. **默认态**：比现在"有，但不吵"——深浅两套主题下都能看见一条 3dp 轨道与滑块，但不会抢正文的注意力。
2. **滚动时**：滚动/惯性期间滑块与轨道升到满不透明度、滑块变宽到 6dp；停止约 1 秒后回落到默认（不是消失）。
3. **拖动跟手**：按住滑块上下拖，视口与手指同向、无跳变；按住轨道空白处 → 滑块中心**立即**跳到手指位置再跟随（pi 行为）。
4. **不打架**：在列表任意位置快速上下滑不受影响；在滚动条那一列**按住不动**能正常长按选中文本；从屏右缘向左滑仍能触发系统返回；拖动时新到达的消息**不会**把视口拽回底部（跟随被暂停）。
5. **边界**：空会话 / 内容不到一屏 → **完全不画**；刚打开且贴底时也不该出现"滑块在底部但内容比视口短"的错位。
6. **来回切屏**：切到别的 tab 再回来，滚动条重新按当前滚动位置画出（状态不存 Bundle，所以"回来时先是默认态"是预期行为）。
7. **两枚箭头**：下箭头改成 `selectedBg` 底 + `text` 字形后仍读得出"可点"；有未读时 5dp `accent` 角标仍在；上箭头外观与今天一致。
8. **手感成本**：360dp 屏右侧 24dp 不能用来拖列表——确认这不会让"滑列表"变得别扭（若别扭，把命中区降到 12dp，视觉仍是 3dp）。

---

## 8. 分阶段实施顺序

风险从低到高；每一步都能独立提交与回退。

| 批 | 内容 | 触及 | 风险 | 可独立回退的边界 |
|---|---|---|---|---|
| **P0** | **先记账**：把 §6.1（`06 §2`「工具名 muted」与 `docs/pi-android-ui-spec.md:156`）、§6.2（`07` D2「被拒用 bodyOnTool」）、§7.2（v2 的 `track:transparent` 被裁成"按 pi 画轨道"）、§7.3（pi 的 `auto` 全隐被裁成 `always` + 1000ms 回落）四处与既有文档冲突的裁决先写进 `07`（**追加新的一条，编号取 D40**——`07` 现在到 D39，被工作区屏那一批占用），**再**动代码 | 只写文档 | 极低 | 文档提交独立 |
| **P1** | **滚动条**（§7）——新增 `ui/components/PiScrollbar.kt` + `ChatScreen.kt` 加 1 行调用 + 拖动处 1 行 `pauseTail()`。**等 `ChatScreen.kt` 脱离红线** | 新文件 1 个 + `ChatScreen.kt` | **中**（新构件 + 手势，但只接一个容器、state 已显式、不需要 hoist、不需要新增 Box） | 新文件删掉 + `ChatScreen.kt` 回退 2 行；两处都是独立 diff |
| **P2** | **`!` 面板补两条全宽 `bashMode` 线**（§6.3；转圈已裁决不补）——**等 `ui/chat/**` 脱离红线** | `ui/chat/BashPanel.kt` | 低（新增 `drawBehind`，不改布局） | 一个文件 |
| **P3** | **工具名 → `toolTitle`**（§6.1） | `ui/blocks/ToolBlockChrome.kt` + 两处文档 | 低（一行）+ 观感变化明显 | 一个文件 |
| **P4** | **列表"正在操作的行"整行 `selectedBg`**（§6.4 第 1 条） | `ui/screens/SessionsScreen.kt` | 中（新增 `selected` 参数并接上 `actions` 状态；组内圆角与分隔线要一起看） | 一个文件、三处 |
| **P5** | **下箭头换 `selectedBg` + `text`**（§7.6）——**等 `ChatScreen.kt` 脱离红线**；与 P1 同文件，建议同批做 | `ui/screens/ChatScreen.kt`（`ScrollArrowButton` 加 tone 参数） | 低（分两套色，尺寸/命中区/alpha/角标全不动） | 同 P1 |
| **P6** | **撤掉 5 个派生 token**（§6.2）——**含红线文件（工作区那两三个），整批等它们落地** | 18 个文件、26 处 + 删 `PiContrast.kt` + 改 `ChromeColor.kt` 导出清单 | **高**（跨文件、注释里也有这些名字；`07` D14 记过注释事故；`ChromeColor.kt` 影响扩展） | **按 token 分批**：`metaOnCard`（1 处）→ `metaOnCanvas`（2 处）→ `contextOnTool`（2 处）→ `thinkingBodyOnCanvas`（3 处）→ 最后 `bodyOnTool`（18 处）；每批一个提交 |
| ~~P7~~ | ~~搜索高亮~~ —— **取消**：用户裁决保持现状（§6.5） | — | — | — |
| **不做** | 其它屏的滚动条（§7.7 全表）、`infoBg`、`pageBg`、图片面积 | — | — | §7.7、§6.6、§6.7 |

**红线文件口径（全程适用）**：`ui/screens/ChatScreen.kt`、`ui/chat/**`、`ui/PiSessionViewModel.kt`、以及工作区的 `ui/screens/{ProjectScreen,WorkspaceChrome,WorkspaceViewer}.kt` —— **本轮只列不改**，实现一律等对应批次落地；本文给它们的是改法与验收点，不是改动。P1/P2/P5/P6 都压在这条口径上。

**顺序理由**：
- **P0 必须最先**：四处冲突都要改掉文档里的原文，代码先走会让文档与实现互相说谎。
- **P1 排在第一个实施位**：滚动条是用户这一轮唯一新增的实体，而且它**只接一个容器**（`ChatScreen.kt:1412` 那个 Box 里已经有转录与两枚箭头、`listState` 在 `:358` 已经显式）→ 不需要逐屏 hoist、不需要新增 Box，范围收窄把它的风险从"跨十屏"降到了"一个文件 + 两行调用"。
- **P5 与 P1 同文件**：两枚箭头与滚动条都在 `ChatScreen.kt`，而 `ScrollArrowButton` 的分色改动与滚动条 overlay 互不重叠；分两次提交但不建议跨批次等待。
- **P6 放最后**：跨 18 个文件、且"改坏注释"事故概率最高（`07` D14）。

---

## 9. 如果只能做三件事

1. **做对话转录视口的滚动条**（§7）——用户这一轮明确要的唯一新构件，同时把两个"0 绘制点"的 token（`scrollbarTrack`/`scrollbarThumb`）第一次接到屏幕上；范围只有一处、一个新文件 + 两行调用。
2. **`!` 面板补上 pi 的两条 `bashMode` 全宽边框**（§6.3）——全部审查里唯一一条**真正的配色差**（6.45% vs 0.72%，差 5.7pp），一个文件、一个 `drawBehind`、零新依赖。
3. **把 5 个派生 token 撤掉回 pi 原值**（§6.2）——它是面积表里 `toolOutput` 那一项归属差（2.42pp）的根因，也是自定义主题下语义漂移的唯一来源（`text` 那一项的 2.40pp 改不掉：pi 的 `text` 本就是终端默认前景，见 §1.5-C）；**但它是风险最高的一批**，所以要按 token 分 5 次提交、每批实机过一眼。

**五分钟就能做完的两件小事**（若还有余量）：工具名 → `toolTitle`（`ui/blocks/ToolBlockChrome.kt:200` 一行，不在红线里）；下箭头 → `selectedBg` + `text`（`ChatScreen.kt:2930`/`:2944` 两行，与滚动条同批）。

**不做三件事**（本轮明确不碰）：其它任何屏的滚动条（§7.7 全表）、搜索高亮（**用户裁决保持现状**，§6.5）、以及任何形式的对比度"修正"（用户通则已否决）。

---

## 附录 A · 模型脚本与全部假设

### A.1 假设清单（全部为**估**）

| # | 假设 | 值 | 来源 / 理由 |
|---|---|---|---|
| 1 | pi 参考终端 | 100×30 格 | §1.2；灵敏度用 80×24 |
| 2 | 我们的设备 | 412×892dp | `06 §2`「设备：412×892」 |
| 3 | 页水平内边距 / 卡片内 / 块间距 / 顶栏 / 底栏 | 14 / 12 / 8 / 48 / 56 dp | `06 §2` |
| 4 | 输入区总高 | 70dp | 容器 padding 9+9、输入 20、gap 8、chip 行 24（`ChatScreen.kt:2688-2705`） |
| 5 | 用户消息 | 正文 3 行 + 8 + 图片 + 8 + 时间 18，内边距 12 | `UserMessageBlock.kt:112-147` |
| 6 | 图片 | 4:3，宽 = 列宽 − 24 | `ImageGridBlock.kt:167-169`（单图按真实比例，上限屏高 60%） |
| 7 | 助手回答 | 标题 22 + 列表 4×23 + 码块 5×19 + 引用 2×23 + 4×13 段距 | `PiTheme.kt:224-228` |
| 8 | 工具卡 | 展开：`6+18+6 + 6+2×20 + 6+18+6` = 106dp，宽 = 列宽 − 26 | `BlockChrome.kt:292-320`、`ToolRail.kt:81` |
| 9 | BashPanel | `8+48+2×16+4+18+8` = 118dp（标题行的 IconButton 按 48dp 触控） | `BashPanel.kt:51-105` |
| 10 | 列表行高 | `10+21+3+18+10` = 62dp | `06 §2` 行（Row） |
| 11 | 等宽字符前进宽 | 0.6 em（13sp → 7.8dp、12sp → 7.2dp） | JetBrains Mono 的本征值；与 `06 §2`「diff 符列 16 / 行号列 30」同量级 |
| 12 | pi 的背景块 | 内容行 + `Box` 的 `paddingY`×2，满宽 | `pi-tui/dist/components/box.js:84-118` |
| 13 | pi 的工具卡 | `call` 1 行 + `result` 行（`read` 收起时 0 行、展开时 2 行）+ padding 2 | `read.js:82-84`、`tool-execution.js:47,215-221` |
| 14 | pi 的 `!` 盒 | `Spacer(1) + border(1) + header(1) + loader(2) + border(1)` = 6 行 | `bash-execution.js:22-40`、`pi-tui/.../loader.js:25-27` |

### A.2 我怎么算的（可复算）

```
pi  占比 = 该 token 的着色字符格数 ÷ 场景总格数（场景行数 × 100）
我们 占比 = 该 token 的着色 dp² ÷ 场景总面积 dp²（列宽 × 场景总高）
分母口径：两侧都取"同一份内容在各自渲染下的着色总面积"（§1.1）
```

**主表原始数（412×892 + 100×30）**

| token | pi 格 | pi 分母 | pi% | 我们 dp² | 我们分母 | 我们% |
|---|---|---|---|---|---|---|
| `userMessageBg` | (2+2)×100=400 | 3300 | 12.12 | 384×(117−10)=41,088 | 259,584 | 16.12 |
| `toolSuccessBg` | 5×100=500 | 3300 | 15.15 | 358×106=37,948 | 259,584 | 14.62 |
| `bashMode` | 2×100+13=213 | 3300 | 6.45 | 12×7.8×20=1,872 | 259,584 | 0.72 |
| `toolOutput` | 2×40=80 | 3300 | 2.42 | 0（派生） | — | 0.00 |
| `text` | 0（终端默认） | — | 0.00 | 2×20×7.8×20=6,240 | 259,584 | 2.40 |
| `muted` | 30 | 3300 | 0.91 | 10×7.2×18=1,296 | 259,584 | 0.50 |
| `toolTitle` | 4 | 3300 | 0.12 | 0 | — | 0.00 |

**灵敏度**

| 变更 | 受影响的份额变动 |
|---|---|
| pi 100×30 → 80×24 | 每个 token ≤ 0.6pp（`muted` 0.91→1.14、`bashMode` 6.45→6.55、`toolOutput` 2.42→3.03） |
| 我们 412×892 → 360×780 | 每个 token ≤ 3.0pp（`userMessageBg` 16.12→16.12、`bashMode` 0.72→0.83、`text` 2.40→2.78） |

两组变更都**不改变差值排序**，也不改变任何结论。

**对比度**用 WCAG 相对亮度公式（sRGB 线性化 + `0.2126/0.7152/0.0722`），透明度合成按 `α·fg + (1−α)·bg` 在 sRGB 空间直接插值（与 Compose 的 `Modifier.alpha` 对已渲染图层的合成一致）。

---

## 附录 B · 复核结果（与任务给定"已核事实"的逐条比对）

| 任务书里的事实 | 复核结果 |
|---|---|
| `scrollbarTrack`/`scrollbarThumb`：我们 **0 个调用点** | **成立但要说准**：两个 token 共 15 行管线引用（`PiPalette.kt:45-46,178-179,241-242`、`PiThemeFiles.kt:131-132,528-529,591-592`、`ChromeColor.kt:83-84`、共同注释 `PiPalette.kt:10`），**0 个绘制点** |
| `toolTitle`：我们 0 个调用点 | **成立**（只有定义、导入导出与 4 处注释） |
| 我们的工具名画在 `muted` | **成立**：`ui/blocks/ToolBlockChrome.kt:198-201` |
| 5 个 token 被派生 | **成立**，并且我精确到 **26 个消费点 / 18 个文件**（任务书说 19 个文件；差的那一个应是 `ui/theme/PiContrast.kt` 本身或注释行口径） |
| `selectedBg`：我们只有文本选择 + `primaryContainer` | **不完整**：另外还有 6 处（设置搜索命中行 `PiSettingsRows.kt:208`、列表编辑器 `PiSettingsEditors.kt:195/827`、分段控件 `PiRoot.kt:453`、`WorkspaceChrome.kt:250`、信任提示 `PiProjectTrustPrompt.kt:105`）。**两个会话列表上确实没有** |
| 会话列表的选中行从 `primaryContainer` 改成 accent | **要修正**：改成的是**徽标**（`SessionsScreen.kt:738-739`、`:834`），accent 给的是徽标与"新建会话"按钮，**行本身既没有 accent 底也没有 selectedBg 底** |
| pi 的 `bashMode` 画整盒（两条边框 + 头 + 转圈） | **成立**：`bash-execution.js:24,32,36,38,40` |
| `searchMatchBg`：pi 只涂命中文字，我们涂整行 | **成立**：pi `tui-renderer.js:9` → `tui-alt-screen.js:1300-1311`；我们 `ChatScreen.kt:1525-1528`（任务书写 `1523-1526`，差 2 行） |
| `infoBg` 用在 `WorkspaceChrome.kt` / `ProjectScreen.kt` 的 snack | **要修正**：`infoBg` 只有 2 处 —— `ProjectScreen.kt:2365`（工作区 Warning 提示条）与 `ExtensionUiHost.kt:155`（扩展 snack Warning 档）；`WorkspaceChrome.kt:379` 用的是 `toolErrorBg` |
| `tui-renderer.js:17` 是"整行选中" | **不成立**：它是 **`↓ Jump to latest message` 浮层指示条**的底（`tui-renderer.js:14-18`），不是列表行；它对应的是对话页的**下箭头**（已按 pi 1:1 定色，§7.6）。见 §2.1、§6.4 |
| `pageBg`/`cardBg`/`infoBg` 是 export-only | **成立**：只在 `core/export-html/index.js:81-83,100-110` 被读 |
| `!` 命令在 pi 里挂在转录 | **要补充**：pi **空闲时**挂 `chatContainer`（`interactive-mode.js:5470-5477`），**流式中**才挂 pending 区（`:5476-5479`） |
| v2 板没有滚动条 | **不成立**：`direction-b-v2.html:143-146` 给了 `.b-scroll` 一条 3px、滑块 `var(--muted)`、轨道透明的规格（挂在 13 个容器上，浏览器渲染所以截图上看不到）。本轮只做对话转录一处（§7） |

**另一处主动补充**：pi 的 `read` 卡**收起时不画正文**（`read.js:82-84`），所以"一张 read 卡"在 pi 里默认只有 3 行；这条直接影响工具卡面积怎么估（§4.1 脚注）。

### B.2 五个派生 token 的 26 个消费点（逐个，可直接照此改）

计数口径：**排除注释行**，**排除** `theme/PiPalette.kt`（定义处）与 `extension/ChromeColor.kt`（它只是把颜色导出给扩展，见下表末行）。`ui/blocks/GrepBlock.kt:205` 一行里有两个 token，所以是 **26 个消费点 / 25 行 / 18 个文件**。

| 文件 | 行 | 派生 | → 目标（依据见 §6.2） |
|---|---|---|---|
| `ui/blocks/DiffBlock.kt` | 112、160、193 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/DiffBlock.kt` | 260 | `contextOnTool` | `toolDiffContext` |
| `ui/blocks/ErrorBlock.kt` | 121 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/GrepBlock.kt` | 205 | `bodyOnTool` **与** `contextOnTool` | `toolOutput` / `toolDiffContext`（同一行三元） |
| `ui/blocks/ImageGridBlock.kt` | 254 | `metaOnCard` | `dim`（只用于工具返回图时可考虑 `toolOutput`） |
| `ui/blocks/NoticeBlock.kt` | 86 | `metaOnCanvas` | `muted` |
| `ui/blocks/DateSeparatorBlock.kt` | 48 | `metaOnCanvas` | `muted` |
| `ui/blocks/PathListBlock.kt` | 184 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/ReadBlock.kt` | 91 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/ShellBlock.kt` | 118 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/ThinkingBlockBlock.kt` | 73、84、118 | `thinkingBodyOnCanvas` | `thinkingText` |
| `ui/blocks/ToolBlockChrome.kt` | 234 | `bodyOnTool` | `dim`（展开 chevron，不是工具正文） |
| `ui/blocks/ToolCallBlock.kt` | 163 | `bodyOnTool` | `toolOutput` |
| `ui/blocks/WriteBlock.kt` | 95 | `bodyOnTool` | `toolOutput` |
| `ui/components/PiCommon.kt` | 335 | `bodyOnTool` | `text`（上下文环里的 `?`） |
| `ui/screens/BootScreen.kt` | 261 | `bodyOnTool` | `muted` |
| `ui/screens/WorkspaceChrome.kt` | 565 | `bodyOnTool` | `muted` |
| `ui/screens/WorkspaceViewer.kt` | 584 | `bodyOnTool` | `toolOutput`（查看器行号列） |
| `ui/screens/WorkspaceViewer.kt` | 606、612 | `bodyOnTool` | `muted`（`⋮` 与「其余 N 行未显示」） |
| `ui/theme/PiStateChip.kt` | 120 | `bodyOnTool` | `toolOutput`（`StateTone.Rejected`；**与 `07` D2 冲突，见 §6.2**） |
| `ui/theme/PiContrast.kt` | 整文件 | 实现 | **删除**（`ensure` / `ensureAgainstAll` 在 `PiPalette.kt:130-161` 被调用，一并删） |
| `ui/extension/ChromeColor.kt` | 83-88 | 导出清单 | 5 个名字从"扩展可见颜色"里去掉（**需要单独确认**：扩展可能按名字取色，删名是一次对外可见的收窄） |
| `ui/blocks/ToolBlockChrome.kt` | 198-201 | 工具名用 `muted` | `toolTitle`（§6.1，不是派生 token 的撤销项，列在这里是因为它与上面同一行区域） |

---

## 附录 C · 读数口径与已知边界

1. **行号是工作区快照**：本文所有 `file:line` 都是在**当前工作区**（含"会话加载代理"尚未提交的改动）里读出来的。`ui/screens/ChatScreen.kt`、`ui/blocks/*`、`ui/screens/{ProjectScreen,SessionsScreen,WorkspaceChrome,WorkspaceViewer}.kt` 等文件此刻在 `git status` 里是 `M`。施工时以文件里**函数名/常量名**为准，行号只作定位参考。
2. **估算的边界**：所有比例都是估（§1.1、附录 A）。差值**排序**与**归因**（结构差 / 归属差 / 真配色差）在两组灵敏度测试下都稳定（§A.2），这是本文敢下结论的部分；**具体百分数不要当成实测**。
3. **没有做的改动**：本文没有改任何代码、没有跑 gradle、没有动红线文件；滚动条是**方案**（待实施），不是已完成项。
4. **落地时要同步更新的既有文档（四处冲突）**：`06 §2`「工具名 12 muted」、`docs/pi-android-ui-spec.md:156`「工具名…muted 色」（对应 §6.1）；`07` D2「被拒用 `bodyOnTool`」（对应 §6.2）；`direction-b-v2.html:146` 的 `track: transparent`（对应 §7.2，按 pi 1:1 画轨道）；pi 的 `auto` 模式"1s 后全隐"（对应 §7.3，按 `always` + 1000ms 回落）。前两项要改我们自己的文档，后两项是**板子/pi 与我们实现之间的差异记录**，写进 `07` 而不是改板子。
5. **没有遗留的待裁决项**：§6.3 的"转圈"已裁决**不补**（保留 Stop 按钮；依据 `06 §5` 的"无循环动画"，理由见 §6.3）；§7 的滚动条已全部按用户裁决定死（含"画轨道""选 `always` 基线""命中区 24dp""下箭头保留 `borderMuted` 描边"四处，理由都写在正文）。本文档到此定稿。
