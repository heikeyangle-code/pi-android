# brand-spec.md —— pi 安卓 App 的「品牌资产」= pi 自己的主题令牌

> 本文件是 §1.a 核心资产协议的产物。这个产品没有第三方品牌，它的「品牌」就是 **pi 的主题令牌 + π 字形**。
> 下面每个值都来自代码事实（给出来源文件:行），不是估的。

## 0. 产品是什么

- **pi**（小写，官方就是这么写的；`app/src/main/res/values/strings.xml:6`）是一个**终端原生**的 AI 编程 agent。
- 本 App 是它的安卓客户端：把 pi 作为**本地子进程**跑起来，通过 pi 的 RPC 通道（stdio 上的 LF-JSONL）驱动它。**App 自己不做任何 agent 逻辑**，它只是 pi 的手和眼（会话、工具调用、diff、审批、扩展 UI 全部来自 pi）。
- 因此：**用户在这台手机上看的东西，就是 pi 自己的输出**。任何「凭想象美化」都会立刻被认出来是假的——这是本设计最重要的约束。
- 目标用户：已经在桌面用 pi 的开发者，现在想在手机上继续同一个会话、同一个工作区、同一个主题。

## 1. 图形资产：π 字形（唯一一个）

App 图标的真实矢量（`app/src/main/res/drawable/ic_launcher_foreground.xml`），**108×108 viewport**：

```svg
<svg viewBox="0 0 108 108" width="108" height="108">
  <path fill="#8ABEB7" d="M30,38 H78 V46 H71 V74 H63 V46 H45 V74 H37 V46 H30 Z"/>
</svg>
```

- 形状：一根横梁 + 两条腿 = 一个几何化的 π。方头方脑、无衬线、无衬线脚。
- 底色 `#18181E`，笔画色 `#8ABEB7`（`app/src/main/res/values/colors.xml`）。
- **这就是全部的品牌图形**。pi 没有别的 logo、没有吉祥物、没有插图库。
- 用法：只作为 App 图标 / 顶部标识 / 空态标识使用，**不要**把它当装饰图案铺开，不要描边加光、不要渐变、不要旋转。

## 2. 颜色资产：pi 主题令牌（这是本设计的「不可动区」）

颜色**唯一事实源**是 pi 主题 JSON 的 56 个令牌（`app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt:31-100`，默认值 = pi 内置 dark/light 主题，逐字转录在 `:165-288`）。
App 把这些令牌映射到界面角色，于是**换 pi 主题 = 换 App 主题**，桌面自制主题可直接导入。

### 🔴 红线（用户原话：「涉及到跟 pi 取色、变色这方面的那些的颜色变化啥的都不用动」）

- 下列令牌的值、含义、角色映射**一律不许改**：不许换色、不许调透明度、不许重映射到别的组件、不许发明新配色去替代它。
- 也不许引入调色板之外的颜色（除下面第 4 节的「App 自造中性阶」，那是从 pi 令牌派生出来的，不是新颜色）。
- 需要表达层级时，**用构图、字号、间距、形状、位置**去做，不要靠加色。

### 2.1 pi 内置 `dark`（本设计的基准主题，暗色优先）

| 令牌 | 值 | 界面角色 |
|---|---|---|
| `accent` | `#8ABEB7` | 主强调：选中、链接感、进度、图标高亮 |
| `border` | `#5F87FF` | 强调描边 |
| `borderAccent` | `#00D7FF` | 聚焦描边 |
| `borderMuted` | `#505050` | 常规分隔 / 描边（**1px，只在必须有边界的地方**） |
| `success` | `#B5BD68` | 成功 |
| `error` | `#CC6666` | 失败 |
| `warning` | `#FFFF00` | 警告 |
| `text` | `#D4D4D4` | 正文 |
| `muted` | `#808080` | 次要信息 |
| `dim` | `#666666` | 占位、禁用 |
| `thinkingText` | `#808080` | 思考文本 |
| `selectedBg` | `#3A3A4A` | 列表选中底 |
| `searchMatchBg` / `searchMatchText` | `#3A3A4A` / `#D4D4D4` | 搜索命中 |
| `userMessageBg` / `userMessageText` | `#343541` / `#D4D4D4` | 用户消息 |
| `customMessageBg` / `Text` / `Label` | `#2D2838` / `#D4D4D4` / `#9575CD` | 扩展注入 / 压缩 / 分支摘要 |
| `toolPendingBg` | `#282832` | 工具卡·进行中 |
| `toolSuccessBg` | `#283228` | 工具卡·成功 |
| `toolErrorBg` | `#3C2828` | 工具卡·失败 |
| `toolTitle` / `toolOutput` | `#D4D4D4` / `#808080` | 工具卡标题 / 输出 |
| `mdHeading` | `#F0C674` | markdown 标题 |
| `mdLink` / `mdLinkUrl` | `#81A2BE` / `#666666` | 链接 / URL |
| `mdCode` / `mdCodeBlock` / `mdCodeBlockBorder` | `#8ABEB7` / `#B5BD68` / `#808080` | 行内码 / 码块 / 码块边 |
| `mdQuote` / `mdQuoteBorder` / `mdHr` / `mdListBullet` | `#808080` ×3 / `#8ABEB7` | 引用 / 分隔 / 列表点 |
| `toolDiffAdded` / `Removed` / `Context` | `#B5BD68` / `#CC6666` / `#808080` | diff 三色 |
| `syntaxComment` | `#6A9955` | 语法·注释 |
| `syntaxKeyword` | `#569CD6` | 语法·关键字 |
| `syntaxFunction` | `#DCDCAA` | 语法·函数 |
| `syntaxVariable` | `#9CDCFE` | 语法·变量 |
| `syntaxString` | `#CE9178` | 语法·字符串 |
| `syntaxNumber` | `#B5CEA8` | 语法·数字 |
| `syntaxType` | `#4EC9B0` | 语法·类型 |
| `syntaxOperator` / `Punctuation` | `#D4D4D4` / `#D4D4D4` | 语法·运算符 / 标点 |
| `thinkingOff` | `#505050` | 思考等级色温（本 App 的签名元素） |
| `thinkingMinimal` | `#6E6E6E` | 同上 |
| `thinkingLow` | `#5F87AF` | 同上 |
| `thinkingMedium` | `#81A2BE` | 同上 |
| `thinkingHigh` | `#B294BB` | 同上 |
| `thinkingXhigh` | `#D183E8` | 同上 |
| `thinkingMax` | `#FF5FFF` | 同上 |
| `bashMode` | `#B5BD68` | shell 输入模式 |
| `export.pageBg` | `#18181E` | 页面底 |
| `export.cardBg` | `#1E1E24` | 卡片底 |
| `export.infoBg` | `#3C3728` | 信息块底 |

### 2.2 pi 内置 `light`（同一套令牌的第二组值，供浅色主题参考）

`accent #5A8080` · `border #547DA7` · `borderAccent #5A8080` · `borderMuted #B0B0B0` · `success #588458` · `error #AA5555` · `warning #9A7326` · `text #1F2328` · `muted #6C6C6C` · `dim #767676` · `selectedBg #D0D0E0` · `userMessageBg #E8E8E8` · `customMessageBg #EDE7F6` · `customMessageLabel #7E57C2` · `toolPendingBg #E8E8F0` · `toolSuccessBg #E8F0E8` · `toolErrorBg #F0E8E8` · `toolOutput #6C6C6C` · `mdHeading #9A7326` · `mdLink #547DA7` · `mdCode #5A8080` · `mdCodeBlock #588458` · `mdCodeBlockBorder #6C6C6C` · `toolDiffAdded #588458` · `toolDiffRemoved #AA5555` · `syntaxComment #008000` · `syntaxKeyword #0000FF` · `syntaxFunction #795E26` · `syntaxVariable #001080` · `syntaxString #A31515` · `syntaxNumber #098658` · `syntaxType #267F99` · `thinkingOff #B0B0B0` · `thinkingLow #547DA7` · `thinkingMedium #5A8080` · `thinkingHigh #875F87` · `thinkingXhigh #8B008B` · `thinkingMax #AF005F` · `bashMode #588458` · `pageBg #F8F8F8` · `cardBg #FFFFFF` · `infoBg #FFFFFAE6`

## 3. App 自造的「中性阶」（允许动，但只能从 pi 令牌派生）

App 从 `cardBg` 向 `text` 逐级提亮得到表面阶梯（`ui/theme/PiTheme.kt:428-434`），**这不是新颜色，是同色系的派生**：

| 角色 | 派生规则 | dark 实测值 | light 实测值 |
|---|---|---|---|
| SurfaceContainerLowest | `pageBg` | `#18181E` | `#F8F8F8` |
| SurfaceContainerLow | `cardBg` | `#1E1E24` | `#FFFFFF` |
| SurfaceContainer | `cardBg` ↑3% text | `#232329` | `#F8F8F9` |
| SurfaceContainerHigh | `cardBg` ↑6% text | `#29292F` | `#F2F2F2` |
| SurfaceContainerHighest | `cardBg` ↑10% text | `#303036` | `#E9E9EA` |
| surfaceBright | `cardBg` ↑12% text | `#343439` | `#E4E4E5` |
| surfaceDim | `pageBg` ↑2% text | `#1C1C22` | — |
| outline | `borderMuted` | `#505050` | `#B0B0B0` |
| outlineVariant | `borderMuted` @55% | — | — |

**可以用**：这些表面阶梯、`borderMuted` 的 1px 线、间距、圆角、字号、动效。
**不可以用**：任何新的色相、任何渐变、任何毛玻璃/模糊（用户已明确否决，纯耗性能无信息增益）。

## 4. 字体资产

- pi 在终端里用的是**等宽字**。本 App 的「机器语言」层必须等宽：命令、代码、diff、工具输出、路径、token 数。
- 中文正文需要一套真正的中文字体（本机已装 Noto Sans CJK / Noto Serif CJK 用于出图）。
- 显示层可以用衬线（Noto Serif CJK）或几何无衬线做气质差异——**这是三个方向的自由度之一**。
- 禁止：全 App 用系统默认字体蒙混（撞 AI slop）；禁止用 emoji 当图标。

## 5. 气质关键词（来自用户原话）

- 「现在就像个毛坯房，问题特别多，数不清。」→ 要**成品感**，不要 demo 感。
- 「会话列表/工作区信息太少，看不出在发生什么。」→ 要**信息密度**，要能一眼看懂 agent 在干什么。
- 「设置页太深、太难找，像调试面板。」→ 设置要**像产品**，不像内部开关板。
- 「对话页杂乱、信息层级不清，工具调用卡片太丑。」→ 对话流是**主战场**。
- 「整体要流畅、美观。」→ 流畅 = 无卡顿、无装饰性动画；美观 = 版式与层级，不是贴纸。

## 6. 禁区（本产品专属）

- 不许出现「终端」页/终端入口/终端字样——用户已明确放弃终端，GUI 只做 GUI 的事。
- 不许在界面上写文件路径、类名、`§` 引用等内部术语（用户既有规范）。
- 不许加装饰性图标、装饰性 stats、装饰性渐变。
- 不许改动 pi 取色/变色的颜色（第 2 节）。
