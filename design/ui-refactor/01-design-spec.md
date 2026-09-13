# 01 · 设计 spec（三个方向的唯一共同输入）

> 本文件是三个并行设计 subagent 的**唯一共同输入**。三版都必须基于本文件；三版之间互不参考。
> 事实底稿：`00-screen-inventory.md`（屏幕与内容，源码级事实）、`02-real-content.md`（真实文案与 28 块对话样例）、`brand-spec.md`（pi 品牌资产与不可动的颜色红线）。

---

## 1. 这是什么产品

**这个 App 叫 PI**（用户的产品名，启动器与 Android 设置里显示的就是它）。
**它里面跑的 agent / CLI 叫 pi**（上游项目自己的写法，小写）。

🔴 命名规则：界面上指「这个应用」的地方用 **PI**（通知标题、无障碍服务名「PI 设备桥」、系统设置路径「应用 → PI」、诊断报告抬头）；指「里面那个 agent」的地方保持 **pi**（「输入 pi 回车」「pi 引擎」「pi 会加载它」）。

**pi** 是一个终端原生的 AI 编程 agent。**PI** 是它的安卓客户端：把 pi 作为**本地子进程**跑起来，通过 pi 的 RPC 通道驱动，**App 自己不做任何 agent 逻辑**。

用户在手机上看到的一切——会话、工具调用、diff、审批请求、扩展 UI、token 统计——**都是 pi 的真实输出**。所以这一版重设计的任务不是「想象一个 AI App 长什么样」，而是：**给 pi 的真实输出做一副配得上它的骨架**。

## 2. 目标受众与使用场景

- **受众**：已经在桌面用 pi 的开发者。他熟悉终端、熟悉 agent 的工作方式、看得懂 diff。他打开手机不是来「聊天的」，是来**看 agent 干到哪了、批一下、回一句、把它继续推下去**。
- **场景**（决定信息密度）：
  1. 竖屏单手，走在路上，5-20 秒瞥一眼：agent 在跑什么？成功了吗？要不要我审批？
  2. 坐在桌前，把手机当第二屏：读一段长回复、看一个 diff、改一句话发出去。
  3. 长会话（几十上百轮）：要能快速回看、定位、搜索、读早期上下文。
- **观众距离**：10cm 手机。**字号下限**：正文 ≥14px、标签/注释 ≥12px、正文对比度 ≥4.5:1。
- **信息密度分型：高密度型**（产品卖点是 agent 的智能与上下文）。每屏至少 3 处**有内容的**差异化信息：真实 token/费用/上下文占比、工具调用的真实命令与真实输出、diff 的真实增删行、思考等级、队列、审批状态。**装饰性 icon 依然忌讳**——加的是内容密度，不是贴纸。

## 3. 这一版必须解决的 5 个痛点（用户原话）

1. 「整体不够像正经产品，太像 demo」→ 要成品感：一致的栅格、清楚的层级、有作者的细节。
2. 「会话列表/工作区信息太少，看不出在发生什么」→ 列表要能一眼读出状态；工作区要有真实内容。
3. 「设置页太深、太难找，像调试面板」→ 设置要像产品，不像内部开关板（当前 12 个分组 + 4 级层级）。
4. 「对话页杂乱、信息层级不清，工具调用卡片太丑」→ **对话流是主战场**。
5. 「整体要流畅、美观」→ 流畅 = 无装饰性动画/模糊/渐变（用户已明确否决「空浪费性能」）；美观 = 版式与层级，不是贴纸。

## 4. 输出格式与尺寸（三版必须一致，否则无法横向对比）

- **形态**：单文件 HTML（`file://` 双击可开，无外部文件依赖），纯内联。
- **React 环境（pin 死，别改版本）**：
  ```html
  <script src="https://unpkg.com/react@18.3.1/umd/react.development.js" integrity="sha384-hD6/rw4ppMLGNu3tX5cjIb+uRZ7UkRJ6BPkLpg4hAu/6onKUg4lLsHAs9EBPT82L" crossorigin="anonymous"></script>
  <script src="https://unpkg.com/react-dom@18.3.1/umd/react-dom.development.js" integrity="sha384-u6aeetuaXnQ38mYT8rp6sbXaQe3NL9t+IBXmnYxwkUI2Hw4bsp2Wvmx4yRQF1uAm" crossorigin="anonymous"></script>
  <script src="https://unpkg.com/@babel/standalone@7.29.0/babel.min.js" integrity="sha384-m08KidiNqLdpJqLq95G/LEi8Qvjl/xUYll3QILypMoQ65QorJ9Lvtp2RXYGBFj1y" crossorigin="anonymous"></script>
  ```
- **设备框**：用超话 skill 的 `assets/android_frame.jsx`（**禁止手写状态栏/打孔/导航条**）。把它的 `androidFrameStyles` + `AndroidFrame` 组件原样内联进 `<script type="text/babel">`。默认尺寸 `width={412} height={892}`，用 `darkMode`。
- **版面**：横向平铺 **5 台** `AndroidFrame`（`display:flex; gap:32px; flex-wrap:wrap; padding:48px; background:#0e0e12`），每台上方一行 13px 注释说明这是哪个界面。整页宽度按 5×432+间距 ≈ 2300px 设计。
- **交互（每台都是独立状态机，不是静态摆拍）**：底部导航可切、工具卡可展开/收起、sheet/dialog 可弹出、列表行可选中、开关可点。写 `cursor:pointer` + hover 反馈。
- **截图**：`npx playwright screenshot --viewport-size=2400,1200 --full-page file://...` + 单台特写 `--viewport-size=430,950`（对话那台）。
- **禁止**：`scrollIntoView`；`const styles = {...}`（多组件必须唯一命名，如 `const chatStyles = {...}`）；多个 `<script type="text/babel">` 之间作用域不共享（用 `Object.assign(window, {...})`）。

## 5. 五台屏（每版都做这五台，覆盖 App 的核心功能面）

| # | 屏 | 必须表现的真实内容（取自 `02-real-content.md`） |
|---|---|---|
| 1 | **对话（hero）** | 28 块样例里的核心段：用户消息 → 助手 markdown（含标题/列表/行内码/码块）→ `read` 工具卡（折叠+展开两态各一）→ `grep` 工具卡（多条命中）→ `edit` 工具卡（**真实 diff，增删色用 pi 的 `toolDiffAdded/Removed`**）→ `bash` 工具卡（真实命令+多行输出+耗时）→ 进行中的工具卡（pending 态）→ 一个失败块 → **审批提示**（用户要决策）→ 助手正文里的 **markdown 列表**（不是待办块）→ 流式中的助手文本（带光标）。

⚠️ **块类型只有代码里存在的那些**：`02-real-content.md` §0 是唯一权威（14 种 `TranscriptItem` + 工具卡二次分发的 8 种）。**这个 App 没有 todo / 待办 / plan / 子代理块**，也**没有复选方框组件**——不要把 `- [x]` 画出来，不要把待办清单当成一个块类型。状态文案见该文件 §2.2「流式/进行中态」清单（如「运行中 · 已运行 12.3 秒 · 18 行」）。 |
| 2 | **会话列表（覆盖层）** | 从对话页左上角会话名进入的覆盖层，不是底部 tab（见 `03-navigation-decision.md`）。12 条真实字段形状的会话行（cwd 分组、当前徽章、副行 `{组名} · {model}`、第三行 `{n} 条`、右侧相对时间）+ 搜索行（`搜索名称 / 目录 / 文件名`、`按时间/按名称`、`全部/仅命名`）+ 空态（两套文案）。 |
| 3 | **工作区（项目现场）** | ⚠️ 见 `03-navigation-decision.md`：工作区 = 当前 cwd、该目录的 `.pi` 资源、git 改动、这次会话动过的文件；不再是整屏终端。终端降格为设置首页的一个入口行。宽屏（≥600dp）才做「左项目现场 / 右终端」两栏。 |
| 4 | **设置首页** | 真实结构：`搜索设置 {n} 项` → `当前模型` 卡 → 设备 / 扩展 / 关于 三个入口 → 12 个分组的入口 + 每个分组的动态摘要。要**像产品**，不像调试面板。 |
| 5 | **设置的一个分组页** | 选 `模型与推理` 或 `外观`（自选），含真实的 6 种行型（Switch / Action(含 dangerous) / Value+chevron / Number / Text / List）、分组标题、`生效方式` 徽标、危险行的确认态。 |

## 6. 颜色：只能用 pi 的令牌（**红线**）

用户原话：「涉及到跟 pi 取色、变色这方面的那些的颜色变化啥的都不用动」。

- 颜色**只能**来自 `brand-spec.md` §2.1 的 pi `dark` 主题令牌（+ §2.2 的 light 值如果你想做浅色那台）+ §3 的派生表面阶。
- **不许**换色、调透明度当装饰、重映射到别的组件、发明新色相；**不许**渐变、毛玻璃、模糊、辉光（用户已明确否决：空耗性能、无信息增益）。
- 需要层级 → 用构图、字号、间距、形状、位置、以及**已有的**表面阶和 1px `borderMuted` 线。
- 建议把令牌写成 CSS 变量再引用，例如：
  ```css
  :root{
    --accent:#8ABEB7; --border:#5F87FF; --border-accent:#00D7FF; --border-muted:#505050;
    --success:#B5BD68; --error:#CC6666; --warning:#FFFF00;
    --text:#D4D4D4; --muted:#808080; --dim:#666666; --selected-bg:#3A3A4A;
    --user-bg:#343541; --user-text:#D4D4D4;
    --custom-bg:#2D2838; --custom-label:#9575CD;
    --tool-pending:#282832; --tool-success:#283228; --tool-error:#3C2828;
    --tool-title:#D4D4D4; --tool-output:#808080;
    --md-heading:#F0C674; --md-link:#81A2BE; --md-link-url:#666666;
    --md-code:#8ABEB7; --md-codeblock:#B5BD68; --md-codeblock-border:#808080;
    --md-quote:#808080; --md-hr:#808080; --md-bullet:#8ABEB7;
    --diff-add:#B5BD68; --diff-del:#CC6666; --diff-ctx:#808080;
    --syntax-comment:#6A9955; --syntax-keyword:#569CD6; --syntax-function:#DCDCAA;
    --syntax-variable:#9CDCFE; --syntax-string:#CE9178; --syntax-number:#B5CEA8;
    --syntax-type:#4EC9B0; --syntax-op:#D4D4D4;
    --think-off:#505050; --think-minimal:#6E6E6E; --think-low:#5F87AF;
    --think-medium:#81A2BE; --think-high:#B294BB; --think-xhigh:#D183E8; --think-max:#FF5FFF;
    --bash:#B5BD68;
    --page:#18181E; --card:#1E1E24; --info:#3C3728;
    --surf-low:#1E1E24; --surf:#232329; --surf-high:#29292F; --surf-highest:#303036; --surf-dim:#1C1C22;
  }
  ```

## 7. 工作区与底部导航（已裁决，见 `03-navigation-decision.md`）

**底部导航 = 三项：对话 / 工作区 / 设置。** 会话列表从 tab 改为对话页的覆盖层；终端从 tab 降格为设置首页的一个入口行；两栏只在 ≥600dp 出现。

**工作区 = 项目现场**：当前 cwd、这个目录里的 `.pi` 资源（技能/提示词/扩展/主题/包）、git 状态与改动文件、这次会话动过的文件。

- **不要发明 pi 没有的数据**：凡是界面上出现的东西，都必须能说出它来自 pi 的哪条 RPC / 哪个文件（`00-screen-inventory.md` §2 有数据来源一栏）。
- 三版可以在**呈现方式**上各自诠释（列表 / 树 / 台账 / 分栏），但「工作区 = 项目现场」这个定位不再变。

## 8. 排版（中文排印，硬要求）

- **fallback 链西文在前、中文在后**：`"Geist","Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif`；衬线同理。中文放前面 = 拉丁字符全被中文字体的丑西文吃掉。
- `font-synthesis: none;`（禁合成斜体/加粗——中文 faux italic 直接毁字形）；`line-break: strict;`（避头尾）；`overflow-wrap: anywhere`（长路径/URL）。
- 引号用「」（本仓库硬规范，不用弯引号）。
- 数字一律 `font-variant-numeric: tabular-nums`（token/费用/行号/耗时，否则列会抖）。
- 字号档位 **≤6 档**；中文正文 0–0.05em 字距；中文标题 0；display 巨字最多 -0.02em。
- 中文没有 Ultra Thin→Black 的 display 生态：大字的张力靠**同族字重对比**（如 Noto Serif SC 900 压 300）制造。
- **机器语言层必须等宽**：命令、代码、diff、工具输出、路径、token 数。当前 App 用的是系统 monospace，这是要改的地方之一（可上 JetBrains Mono / Geist Mono）。
- 全 App 最多两个中文字体家族。

## 9. 动效

- 只允许「一次编排好的入场」和**状态过渡的静态呈现**（如 pending 态本身的样子）。
- **禁止**：循环动画、装饰性视差、逐条飞入、按钮弹跳、无限滚动指示。
- 原型里请把「进行中」表现为**状态设计**（色块、标记、进度文本），不是动画。

## 10. 图片

本设计**不需要任何照片**：这是工具界面，页面上的「图」是代码、diff、命令输出、统计——都是文本。
Phase 3.5 判定：图片非内容必需 → **跳过取图**，不许用 Unsplash 灵感图、不许 CSS 渐变充数、不许 SVG 画插画。
唯一允许的图形资产是 **π 字形**（`brand-spec.md` §1，一个 path，直接内联）。

## 11. 视觉母题（每版开工前先答 form 推导五问，把答案写在 HTML 顶部的注释里）

1. **叙事角色**：这台屏在用户的流程里是 hero / 过渡 / 数据 / 结尾？（对话是 hero，设置是结尾）
2. **观众距离**：10cm 手机 → 字号下限与信息密度。
3. **视觉温度**：安静 / 兴奋 / 冷静 / 权威 / 温柔？（本产品整体应偏「冷静·精密」，但三版可以各自偏一点）
4. **容量估算**：用纸笔画 3 个 5 秒 thumbnail，算内容塞不塞得下（尤其对话页一屏要放 6-8 个块）。
5. **视觉母题**：这个内容独有的视觉元素/结构/隐喻是什么？（候选：工具调用的**执行时间轴**、diff 的**行级纹理**、token 的**上下文环**、思考等级的**色温刻度**、会话的**分支树**）——母题必须从 pi 的真实输出里长出来，答不出就是在套模板。

每版交付时必须能说一句「form 来自内容的哪里」。

## 12. 反 AI slop（本任务逐条对照）

禁：紫/蓝渐变当科技感、emoji 当图标、圆角卡片+左侧彩色 accent 条、无意义的 stats/徽章堆砌、每个标题配图标、大而空的留白（留白必须是构图，不是内容缺席）。
允许且鼓励：`text-wrap: pretty`、CSS Grid、`oklch()` 之外只用 pi 令牌、一个细节做到 120%（挑一处「值得截图」的签名细节：比如 diff 的行纹理 / 工具卡的状态语言 / 上下文环）。

## 13. 三套逻辑的分工（互不参考，结构骨架必须互异）

- **方向 A · 🎲 秒数轮盘 #16：Terminal-Core Soft-Futurism（等宽字+等距立方）**
  参照物：Cursor (Anysphere) 的开发者终端美学 × Teenage Engineering 工业极简。
  DNA：等宽字主角、命令行/代码块当前景、bento 分区、2.5D 等距示意、暖白×炭黑、克制的工业精密感。
  落在这台手机上：**「仪表」取向**——信息以「读数 + 轨道」的形式组织，对话流像一条带读数的执行轨道。
  ⚠️ 配色仍必须用 §6 的 pi 令牌（风格的炭黑≈`--page`，暖白≈`--text`，accent 用 `--accent`），不许自带蓝紫渐变。

- **方向 B · 🏆 现实参照（标杆迁移）**
  先 `WebSearch` 核实一个真实存在、设计公认出色的**移动端**参照（候选：Things 3 / Linear 移动端 / Claude iOS / Bear / Halide / Flighty——自行判断哪个最贴合「开发者用的高密度 agent 控制台」，**必须搜到真实资料再动手**，把 URL 与设计语言拆解写进 HTML 顶部注释）。
  把它拆解到配色（本任务=令牌用法）、字体、布局、标志元素层面，再迁移到 pi 的内容上。
  落在这台手机上：**「直接操作 + 强层级」取向**——参考选择本身决定骨架。

- **方向 C · 🧠 最佳设计师（顶级定制）**
  深呼吸，认真想：假如预算无上限，世界上最适合为「这个用户、这个产品」做设计的工作室/设计师是谁？（候选：Panic Inc. 的开发者工具式工艺与幽默感 / Rauno Freiberg 的交互精度 / Teenage Engineering 的仪器感 / 原研哉的留白——自行选择并说明理由）
  启用该设计师的设计思维与哲学，从头为 pi 的安卓客户端设计。
  落在这台手机上：**「作者性」取向**——有一处 120% 的签名细节，别处克制到 80%。

**三版都必须**：同一批真实内容（`02-real-content.md`）、同一尺寸、同一设备框；差异必须在**结构层**（导航/构图/内容区组织至少一项结构性不同），不许两版共用骨架只换色换字体（会被一眼识破「换皮」）。

## 14. 每版交付前的自检（写进文件末尾的注释里逐条打勾）

- [ ] 5 台 AndroidFrame 都是 412×892，坐标与尺寸与另两版一致
- [ ] 每台都能交互（导航可切、卡片可展、sheet 可弹），Playwright 点击无 `pageerror`
- [ ] 颜色 100% 来自 §6 令牌；无渐变/模糊/新色相
- [ ] 正文 ≥14px、标签 ≥12px、正文对比度 ≥4.5:1
- [ ] 中文排印：fallback 链、`font-synthesis:none`、`line-break:strict`、「」、tabular-nums
- [ ] 真实文案（不是 Lorem），且没有文件路径/类名/`§` 出现在用户可见文本里
- [ ] 工作区选了一个答案并在页面里标注
- [ ] 能说出「form 来自内容的哪里」
