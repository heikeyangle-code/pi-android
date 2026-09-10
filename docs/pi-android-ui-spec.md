# pi Agent 安卓 App —— UI/UX 设计规范 v2

> 配套文档：`pi-android-app-design.md`（架构与源码分析）
> **v2 变更**：修正视觉方向；补齐组件库、设置分层模型、聊天界面实现机制、视觉特效体系、以及「pi 能力 → App 入口」覆盖矩阵。
> 一句话定调：**它是一款现代原生 Android App；pi 的色彩语义与术语是它的灵魂，终端只是它里面的一个工作区。**

---

## 0. 先纠正方向

### 0.1 v1 的偏差

| v1 写的 | 问题 | v2 改为 |
|---|---|---|
| 「终端血统、移动重塑」「像终端一样诚实」 | 把 pi 的**终端实现方式**当成了设计目标 | **现代原生 App 优先**；只继承 pi 的**语义层**（色彩角色、术语、状态含义），不继承它的终端表现层 |
| 「无阴影、无渐变、4dp 圆角、1dp 硬描边」 | 这是终端模拟器的画法，不是现代 App | **M3 表面体系**：16dp 圆角卡片、tonal surface、克制的层级阴影、柔和分隔 |
| 正文用系统字、机器输出等宽 | 这条**保留**（它是对的） | 保留，并强化：字号阶梯更大、层级更清晰 |
| 「只有 4 处动效」 | 太空，现代 App 缺反馈会显得廉价 | **完整动效系统**：M3 动效物理 + 8 类转场 + 触觉 + 可选音效 |
| 组件只写了对话内容块 | 没有基础组件库，无法施工 | **完整组件库**（基础/容器/数据展示/对话专用/终端专用 5 组） |
| 设置只有 13 个分组 | 没有层级模型、行类型、搜索、重启标记 | **4 层导航模型 + 6 种行类型 + 全局搜索 + 生效方式标记** |
| 缺「pi 能力 → App 入口」映射 | 无法证明 100% 兼容 | **附录 A 覆盖矩阵**（逐项证明） |

### 0.2 「100% 兼容」在 UI 层的定义

> **pi 的每一个功能、每一个设置项、每一个扩展能力，都能在这个 App 里找到入口或被自动承载；没有任何一项只能靠终端才能用。**

实现它的三条保证：

1. **覆盖矩阵**（附录 A）：pi 的全部交互面逐项映射到 App 入口，逐项打勾。
2. **零补丁内核**：App 不改 pi，所有 pi 的能力天然存在；App 只是它的 UI 投影。
3. **终端逃生舱**：唯一无法在 GUI 复刻的（扩展的终端绘制型 UI、内建 TUI 斜杠命令）由工作台里的**原版 pi TUI** 承载 —— 不是妥协，是把 100% 落到实处。

### 0.3 现代原生 App 的具体含义

| 维度 | 采用 |
|---|---|
| 设计系统 | Material 3（含 Expressive 的动效与形状语言），克制使用 |
| 表面 | tonal surface + 层级 elevation（不再"无阴影"） |
| 形状 | 卡片 16dp、Sheet 28dp 顶部、FAB 16dp、Chip 全圆 |
| 色彩 | **pi 主题令牌为准**（保证主题往返），映射到 M3 色彩角色；支持动态取色与 pi 主题导入 |
| 字体 | 系统字（Roboto Flex / 思源黑）分层次；等宽只用于代码/终端/工具输出 |
| 动效 | M3 spring 物理，8 类转场，幅度克制 |
| 触控 | 最小 48dp，主操作 56dp，列表项 72dp |
| 导航 | 底部导航 + 预测式返回 + 标准 App Bar + Modal Sheet |
| 平台感 | 预测式返回动画、共享元素转场、边缘回滑、系统动态取色、TalkBack |

**唯一的"pi 签名"**：**思考等级的色温梯度**（`thinkingOff→Max`）作为贯穿全局的强调元素（输入框光环、思考块色条、状态点）。这是 App 里最有辨识度的一笔，也是与 pi 的血缘标记。

---

## 1. 设计原则（8 条）

| # | 原则 | 说明 |
|---|---|---|
| 1 | **流即界面** | 对话是一条单一纵向流。不发明卡片墙、仪表盘、左右气泡分栏（那是聊天软件的隐喻，不是 agent 的）。 |
| 2 | **pi 的词汇，App 的语法** | 术语沿用 `session / entry / branch / tree / fork / clone / compaction / steering / follow-up / thinking level / label / skill / template / provider / package`；但呈现方式是现代 App 的。 |
| 3 | **色彩即状态** | 工具三态、思考等级色温、diff 三色。不靠图标堆砌传达状态。 |
| 4 | **默认收起，一切可展开** | 块只展示「标题 + 关键参数 + 状态」，点开看全文。手机上尤其重要。 |
| 5 | **核心小，扩展大** | 不自造 plan mode / todo / subagent / 权限模型；这些是扩展，App 只做它们的 UI 宿主。 |
| 6 | **一处强调** | 每屏一个主焦点。层级靠表面与留白，不靠花哨描边。 |
| 7 | **两种声音** | 人写给人的用系统字体；机器输出的（命令/代码/diff/终端/工具输出）用等宽。这条规则让"谁在说话"一目了然。 |
| 8 | **永不阻塞，永远可退** | 停止 = 取消 + 把排队文本还给输入框；分叉 = 从任意消息重开；破坏性操作有确认与撤销。 |

---

## 2. 设计语言

### 2.1 颜色系统：pi 令牌 → M3 色彩角色

**pi 主题 JSON 是颜色的唯一事实源**（51 个必需令牌 + 5 个可选（有回退），共 56 个，另有可选 `vars` 与 3 键 `export` 段）。App 把它映射到 M3 角色，于是**换 pi 主题 = 换 App 主题**，且用户在桌面自制的主题能直接导入。

| pi 令牌 | M3 角色 / App 用途 |
|---|---|
| `accent` | `Primary` — 主按钮、FAB、选中态、进度、链接 |
| `borderAccent` | `Primary` 的变体 — 聚焦边框、标题强调 |
| `border` / `borderMuted` | `Outline` / `OutlineVariant` — 卡片描边（**仅 1dp，用于需要明确边界处，不是主要语言**） |
| `text` | `OnSurface` |
| `muted` | `OnSurfaceVariant` — 元信息、副标题 |
| `dim` | `OnSurfaceVariant` 低透明变体 — 占位符、禁用 |
| `success` / `error` / `warning` | 语义色（不参与 M3 动态取色，必须保真） |
| `selectedBg` | `SecondaryContainer` — 列表选中 |
| `searchMatchBg` / `searchMatchText` | 搜索命中（当前命中反转） |
| `userMessageBg` / `userMessageText` | 用户消息容器 |
| `customMessageBg` / `customMessageText` / `customMessageLabel` | 扩展注入消息、压缩标记、分支摘要 |
| `toolPendingBg` / `toolSuccessBg` / `toolErrorBg` | 工具卡三态容器（映射为 M3 tonal container） |
| `toolTitle` / `toolOutput` | 工具卡标题 / 输出正文 |
| `md*`（10 个） | Markdown 元素 |
| `toolDiffAdded` / `Removed` / `Context` | diff 三色 |
| `syntax*`（9 个） | 代码高亮 |
| `thinkingOff…Max`（7 个） | **思考等级色温**（App 签名元素） |
| `bashMode` | Shell 模式强调色 |
| `export.pageBg` / `cardBg` / `infoBg` | `Surface` / `SurfaceContainer` / `SurfaceContainerHigh` 的基准 |

**规则**
- **语义色保真，中性色可动态**：`success/error/warning/toolDiff*/thinking*/bashMode` 必须用 pi 主题的值；`surface`/`outline` 这类中性色允许被系统动态取色（Android 12+ Monet）覆盖 —— 用户可关。
- **暗色优先**，浅色派生。pi 的 dark/light 已经是同 token 的两套值。
- **主题自动模式**：pi 把 `"lightTheme/darkTheme"` 这个**字面量字符串**存进 `theme` 字段。App 的主题选择器必须**原样往返**，不能拆成两个字段，否则桌面端读不懂。
- **对比度**：正文 ≥4.5:1；`muted` 在浅色主题下需微调（pi 的 light 主题这项偏弱，App 要给一个修正档）。

### 2.2 排版系统

| 角色 | 字体 | 字号 / 行高 / 字重 | 用途 |
|---|---|---|---|
| 展示 | 系统字 | 28 / 36 / Medium | 空态主标题、向导标题 |
| 标题大 | 系统字 | 22 / 28 / Semibold | 页面主标题 |
| 标题小 | 系统字 | 17 / 24 / Semibold | 卡片标题、会话名 |
| **正文** | 系统字 | **15 / 23 / Regular** | 助手与用户消息（中文行高要松） |
| 正文紧 | 系统字 | 14 / 20 / Regular | 列表副标题、设置说明 |
| 标签 | 系统字 | 12 / 16 / Medium | Chip、徽章、分组标题 |
| 元信息 | 系统字 | 11.5 / 16 / Regular | 时间戳、token、cwd |
| **机器** | **等宽** | **13 / 20 / Regular** | 代码、命令、工具输出、diff、终端 |
| 机器小 | 等宽 | 11.5 / 17 | 行号、状态字符号、工具名 |
| 数字 | 等宽数字（tabular） | 继承 | token / 成本 / 行号 / 进度 |

**细则**
- 中文与等宽混排时，代码块加 0.5dp 字距。
- 段落间距 = 0.55 × 行高；列表缩进 = 1 × 行高。
- 长路径/URL 用 `overflow-wrap: anywhere`。
- **不提供「全等宽」预设**（v1 的错误）：想要终端质感的用户，直接去工作台的终端页 —— 那才是终端该待的地方。
- 跟随系统字号缩放；App 内额外提供 ±2sp 微调。

### 2.3 形状与圆角

| 元素 | 圆角 |
|---|---|
| 内容块卡片（工具卡、压缩卡、扩展消息） | **16dp** |
| 内嵌块（代码块、工具输出） | 12dp |
| 输入框 | 24dp（胶囊感）或 16dp（多行态） |
| Chip / 徽章 / 头像 | 全圆 |
| FAB | 16dp（方形圆角，比方圆更有工具感） |
| Modal Sheet | 顶部 28dp |
| Dialog | 28dp |
| 缩略图 | 12dp |

**形状语言**：`M3 Expressive` 的"柔和圆角 + 清晰分隔"，不用 `cut corner` 之类的异形。

### 2.4 高度与表面

v1 说"无阴影"，这是错的。现代 App 需要层级线索。改为 **M3 tonal surface + 克制的 elevation**：

| 层级 | 表面 | 阴影 | 用途 |
|---|---|---|---|
| 0 | `Surface`（= `export.pageBg`） | 无 | 页面底色 |
| 1 | `SurfaceContainerLow` | 1dp | 列表卡片、消息块 |
| 2 | `SurfaceContainer` | 2dp | 悬浮输入区（滚动时） |
| 3 | `SurfaceContainerHigh` | 4dp | 顶栏（滚动后）、Chip |
| 4 | `SurfaceContainerHighest` | 6dp | Bottom Sheet、Dialog |
| 5 | — | 8dp + scrim 32% | FAB 按下态、全屏查看器 |

- **不用纯黑阴影**：用主题的 `shadow` 色 + 低透明度，暗色主题里用"更亮的表面"代替阴影（M3 的做法）。
- **允许一处毛玻璃**：顶栏在滚动内容下方时，用 `Surface` 88% 不透明度 + 12dp blur（Android 12+ RenderEffect）。这是全 App 唯一使用模糊的地方，用来强化"内容在栏下滚动"的空间感。

### 2.5 图标与插图

- **图标**：Material Symbols Rounded，线性、20dp（内联）/ 24dp（导航），可选填充态表示选中。
- **工具类型不用图标**：用等宽小字（`bash` `read` `edit` `write` `grep` `find` `ls`），`muted` 色 —— 比图标更准确，也更"pi"。扩展工具用通用方块字符 + 工具名。
- **状态用色与形状**，不用图标堆砌。错误时才加一个 8dp 的 `error` 圆点。
- **思考等级**：一个 3 段的弧形/阶梯点阵，颜色取对应 token；旁边永远带文字（`off/minimal/low/medium/high/xhigh/max`）以满足色盲与无障碍。
- **插图**：空态用**极简线稿**（1.5dp 描边、单色 `outlineVariant`），不搞彩色 3D 插画 —— 与工具调性一致。

### 2.6 动效系统

**物理**：M3 motion physics（spring），幅度克制。

| 令牌 | 时长/曲线 | 用途 |
|---|---|---|
| `instant` | 0ms | 输入反馈、点击态、滚动 |
| `fast.spatial` | 150ms spring(damping 0.9) | 块入场、Chip 展开 |
| `medium.spatial` | 250ms spring(damping 0.85) | Sheet 上滑、卡片展开 |
| `slow.spatial` | 400ms spring(damping 0.8) | 全屏转场、共享元素 |
| `fast.effects` | 100ms linear | 底色/透明度过渡 |
| `medium.effects` | 200ms ease-in-out | 状态色、主题切换 |

**8 类转场（明确清单）**

| # | 场景 | 效果 |
|---|---|---|
| 1 | 消息入场 | 淡入 + 上移 8dp；同批错开 40ms 形成"流出"感 |
| 2 | 工具卡状态 | 容器底色 200ms 过渡；状态符号 100ms 缩放淡入；完成瞬间卡片边框一次 1px `success` 呼吸 |
| 3 | 流式文本 | 末尾呼吸方块光标（1s，透明度 0.3↔1，不用闪烁） |
| 4 | 思考块 | 展开/收起用 `fast.spatial`；流式中左色条有 2s 的缓慢色相漂移（色温语言） |
| 5 | 上下文环 | 数值变化 200ms 扫过；跨过 70%/90% 阈值时环颜色 200ms 渐变 + 一次轻震 |
| 6 | 页面对页面 | 共享元素（会话名、缩略图）走 M3 container transform |
| 7 | 返回 | **预测式返回**（Android 14+）：从屏幕边缘拖动时预览上一屏 |
| 8 | 危险确认 | 卡片红色脉冲**一次** + 长震；不做抖动、不做闪烁 |

**禁止**：列表项逐条飞入、按钮弹跳、装饰性视差、`overshoot` 超过 1.1、同一屏出现两处以上持续动画。

### 2.7 触觉与音效

| 事件 | 触觉（HapticFeedbackConstants / VibratorEffect） |
|---|---|
| 发送 | `KEYBOARD_TAP` |
| 切换思考等级 / 模型 | `CLOCK_TICK` |
| 展开工具卡、切换会话 | `CONTEXT_CLICK` |
| 审批弹出 | 两段（20-60-20ms） |
| 危险确认达成 | `LONG_PRESS`（60ms） |
| 任务完成 | 成功节奏（20-60-20-60-40） |
| 错误 / 断线 | `REJECT`（80ms） |
| 滑到会话顶/底 | `TEXT_HANDLE_MOVE` |

- 三档开关：全部 / 仅重要 / 关闭。
- **音效默认关闭**，可选：任务完成、需要审批（两个极短的柔和音），不提供其他音效。

### 2.8 动态取色与主题导入

- 支持 **Android 12+ 动态取色**（Monet），但**只作用于中性色**；语义色（success/error/warning/thinking/toolDiff）永远取 pi 主题值 —— 否则状态色会失真。
- 支持**导入 pi 主题 JSON**（从文件、从剪贴板、从 URL）。校验 51 个必需令牌，缺项用当前主题补并给出警告列表。
- 支持**跟随桌面**：如果用户把 `~/.pi/agent/themes/` 放进工作区，App 直接读它 —— 与桌面共用同一套主题文件。
- 主题切换**即时热重载**（pi 本身也是编辑即热重载）。

---

## 3. 信息架构与导航

### 3.1 一级导航（底部，4 个目的地）

| # | 目的地 | 内容 |
|---|---|---|
| 1 | **会话** | 会话列表（按工作区分组） |
| 2 | **对话** | 当前会话流（主战场） |
| 3 | **工作区** | 分段：**终端 · 文件 · Git · 任务** |
| 4 | **设置** | 全部配置 |

**为什么不是 5 个**：底部导航超过 4 项在手机上会拥挤且误触。模型/凭证/扩展/设备能力都收进设置（它们都是低频的"配置"）。

**预测式返回**：整个 App 依赖系统返回手势，不自造返回栏。每一层都要正确声明 `BackHandler`。

### 3.2 二级：视图内分段

| 位置 | 分段 |
|---|---|
| 工作区顶部 | 终端 · 文件 · Git · 任务（SegmentedButton，带图标） |
| 会话列表顶部 | 全部 · 已命名 · 当前工作区 |
| 会话树 | 过滤器 Chip：默认 · 无工具 · 仅用户 · 仅标签 · 全部 |

### 3.3 三级：内容块（对话流元素，共 14 类）

`user-message` · `assistant-text` · `thinking-block` · `tool-execution` · `tool-diff` · `compaction` · `branch-summary` · `hook-message` · `model-change` · `skill-invocation` · `system-prompt` · `message-images` · `error-text` · `date-separator`

### 3.4 四级：展开层

| 类型 | 用途 | 呈现 |
|---|---|---|
| **Modal Bottom Sheet** | 模型选择、思考等级、命令面板、文件引用、会话快捷面板、会话树节点操作、导出 | 顶部 28dp 圆角、拖拽把手、最大 90% 高 |
| **全屏** | 代码查看器、diff 查看器、图片查看器、会话树、系统提示全文、会话信息 | container transform 转场 |
| **Dialog** | 扩展 UI 请求（select/confirm/input/editor）、危险确认、重命名、项目信任 | 28dp 圆角、最大宽 400dp |
| **Inline 展开** | 工具卡输出、思考块、压缩摘要、代码块 | 高度动画，不离开上下文 |

### 3.5 导航图

```
[通知 / 分享 / 磁贴 / 小组件] ──┐
                                ▼
                         ┌─────────────┐
                         │  会话列表    │──▶ 会话树(全屏)
                         └──────┬──────┘
                                │ 点会话
                                ▼
   ┌───────────────────────────────────────────────┐
   │ 对话页                                         │
   │  ┌─ App Bar: 会话名 · ⟳ · ◐ · ⋮ ─────────────┐│
   │  ├─ 状态行: cwd·分支 · ↑↓token · 上下文环 · 模型┤│
   │  ├─ 流: 14 类内容块                            ││
   │  ├─ 队列 chips / 扩展 widget                  ││
   │  └─ 输入区 (边框 = thinking 等级)              ││
   └────┬─────────────────────────┬────────────────┘
        │ App Bar 下拉             │ 底部导航
        ▼                          ▼
   会话快捷面板              工作区(终端/文件/Git/任务)
   (模型/思考/队列/工具/压缩)        设置(4 层)
```

---

## 4. 聊天界面（机制级规范）

这是全 App 最重要的界面，占日常使用时间 90%。下面从数据模型讲到像素。

### 4.1 页面结构

```
┌──────────────────────────────────────────────────┐
│ ←  重构认证中间件            ⟳   ⋮              │  App Bar（56dp，滚动后毛玻璃）
│ ~/projects/api · main · ↑24.1k ↓3.2k · ◐ 52% · sonnet-4.5 │  状态行（32dp，可点各项）
├──────────────────────────────────────────────────┤
│                                                  │
│  ──────────── 今天 14:02 ────────────            │  日期分隔
│                                                  │
│  ┌────────────────────────────────┐              │
│  │ 帮我重构 auth 中间件            │              │  用户消息（容器，左对齐，100% 宽）
│  └────────────────────────────────┘              │
│                                                  │
│  ▎思考 12s                              14:05    │  思考块（收起，左色条 = thinking 等级）
│                                                  │
│  我先看一下现有的实现。                            │  助手文本（无容器，落在画布上）
│                                                  │
│  ╭──────────────────────────────────────────╮    │
│  │ read   src/auth.ts                 ✓ 0.3s│    │  工具卡（tonal 容器，三态底色）
│  │ 120 行                             [展开]│    │
│  ╰──────────────────────────────────────────╯    │
│  ╭──────────────────────────────────────────╮    │
│  │ edit   src/auth.ts                 ✓ 0.1s│    │
│  │ +12 −5  src/auth.ts                       │    │
│  │   14  - const secret = "hardcoded"        │    │  diff（+/- 符号 + 极淡底纹）
│  │   15  + const secret = env.AUTH_SECRET    │    │
│  │   … 3 行未变                              │    │
│  ╰──────────────────────────────────────────╯    │
│                                                  │
│  改好了。要我跑一下测试吗？                  ⟋   │  流式光标
│                                                  │
├──────────────────────────────────────────────────┤
│  ⤷ 穿插 "顺便更新测试"                       ✕  │  队列 chip
├──────────────────────────────────────────────────┤
│ ╭────────────────────────────────────────────╮   │
│ │ 输入消息…                              📎 │   │  输入区（边框 = thinking 等级）
│ ╰────────────────────────────────────────────╯   │
│ [esc][tab][↑][↓][/][@][!]        ◐ medium  ▸    │  键盘工具条 + 发送
└──────────────────────────────────────────────────┘
```

### 4.2 消息列表的数据模型与渲染

**核心原则：pi 是唯一事实源，App 是投影。**

```kotlin
sealed interface TranscriptItem {
  val key: String          // = pi 的 entry id（8 位十六进制），保证稳定
  val ts: Long
}
data class UserMessage(key, ts, markdown, images, entryId)
data class AssistantText(key, ts, markdown, isStreaming)
data class ThinkingBlock(key, ts, text, level, elapsedMs, isStreaming)
data class ToolCall(key, ts, toolName, argsSummary, status /*PENDING|SUCCESS|ERROR*/,
                    output, outputTruncated, exitCode, elapsedMs, details)
data class ToolDiff(key, ts, path, diffText, added, removed, truncated)
data class CompactionMarker(key, ts, summary, tokensFreed, firstKeptEntryId)
data class BranchSummary(key, ts, summary, branchId)
data class HookMessage(key, ts, customType, markdown)
data class ModelChange(key, ts, provider, modelId)
data class SkillInvocation(key, ts, skillName, body)
data class SystemPrompt(key, ts, fullText)
data class ErrorText(key, ts, message, detail)
data class DateSeparator(key, ts, label)
```

**构建方式**
1. 打开会话：`switch_session{sessionPath}` → `get_entries` → 全量投影为 `TranscriptItem` 列表
2. 重连/补差：`get_entries{since: cursor}` → 只投影新增部分
3. 实时：订阅 RPC 事件，增量修改列表

**渲染**
- `LazyColumn` + `key = item.key`（稳定 key 是滚动位置恢复、动画正确性、增量更新的前提）
- **流式优化**：正在流式的那个 item 的文本放在一个独立的 `MutableState<String>` 里，只有那个 composable 读它 → 列表其余部分不重组（这是打字机流畅的关键）
- **Markdown 两段式**：流式中只用轻量行内解析（粗体/斜体/行内码/标题）；`message_end` 后再做**完整解析 + 语法高亮**（后台线程）。避免每帧重排。
- **代码块**：先渲染纯文本，高亮结果异步到位后替换（`remember` 缓存按内容 hash）
- **超长输出**：默认渲染前 200 行 + 「展开全部」（展开后启用虚拟滚动）；单块 >200 KB 直接给「前往工作区查看完整日志」
- **图片**：`Coil` 异步加载 + 占位骨架；大图先出缩略图

### 4.3 流式更新的实现规则

| 事件 | App 动作 |
|---|---|
| `message_start` | 在列表尾部插入一个空的 `AssistantText`（`isStreaming=true`） |
| `message_update` (`text_delta`) | 追加到该 item 的 `MutableState`；若已解锁跟随则滚到底 |
| `message_update` (`thinking_delta`) | 追加到尾部 `ThinkingBlock`；收起态显示「思考中…」+ 计时 |
| `toolcall_start` | 预插入一个 `ToolCall{PENDING}` 占位（这样参数流出时用户能看到工具名） |
| `tool_execution_start` | 补齐工具名与参数摘要 |
| `tool_execution_update` | **节流 200ms** 追加输出（避免抖动）；自动滚底（若在跟随态） |
| `tool_execution_end` | 状态切 SUCCESS/ERROR，底色过渡；写入 exitCode/elapsedMs |
| `message_end` | `isStreaming=false`，做完整 Markdown 解析与高亮 |
| `turn_end` | 结束本轮，插入 `date-separator` 若跨天 |
| `entry_appended` | 追加对应类型 item（扩展 entry / label 等） |
| `queue_update` | 更新队列 chip 区 |
| `compaction_start` | 插入一条「正在压缩上下文…」的行内指示（不算 item） |
| `compaction_end` | 替换为 `CompactionMarker` |
| `auto_retry_start/end` | 状态行显示「重试 2/3 · 4s」，不在流里插入内容 |
| `model_select` | 插入 `ModelChange` |
| `session_info_changed` | 更新 App Bar 标题 |

**降级策略（背压）**：若 UI 线程积压超过 32 个待处理 delta，丢弃中间的 `text_delta`，只保留最新累积值 —— 视觉上表现为"跳一段"，而不是卡住。在设置里可开「低端设备模式」（进一步降低刷新率到 10fps）。

### 4.4 输入区（完整交互）

**结构与状态**

```
┌─ 附件条（可折叠）────────────────────────────┐
│ [图1 ×] [图2 ×]                             │
├──────────────────────────────────────────────┤
│ 输入消息…                                 📎 │  ← 自适应 1–6 行，超过滚动
│                                              │     边框颜色 = thinking 等级
│                                              │     `!` 模式时 = bashMode 色
╰──────────────────────────────────────────────╯
 [esc][tab][↑][↓][/][@][!][⌨]      ◐ medium  ▸
```

**边框即状态**（pi 的核心设计，App 完整继承）

| 输入内容 | 边框 | 徽章 |
|---|---|---|
| 普通 | `thinkingOff…Max` 对应色 | `◐ medium` |
| 以 `!` 开头 | `bashMode` | `▸ Shell` |
| 以 `!!` 开头 | `bashMode`（更深/加粗） | `▸ Shell·静默` |
| 以 `/` 开头 | `accent` | `⌘ 命令` |
| 流式中发送过 | 叠加 1dp 虚线内描边 | `⤷ 排队中` |

**触发符与面板**

| 输入 | 面板 | 内容 |
|---|---|---|
| `/` | 命令面板 | 四组：**App 内建 / 扩展命令 / 技能 `/skill:` / 模板**；显示来源与描述；模糊搜索 |
| `@` 或 `#` | 文件面板 | 模糊搜索 + 最近文件 + 当前打开的标签 |
| Tab | 路径补全 | 行内补全 |
| `!` | 无面板，切换模式 | 边框与徽章变化 |

**键盘工具条**：`esc` `tab` `↑` `↓` `/` `@` `!` `⌨`（外部编辑器）`◐`（思考等级）。可自定义键位与顺序，可隐藏。

**发送行为**

| 场景 | 行为 |
|---|---|
| IDLE 点发送 | `prompt` |
| STREAMING 点发送 | 弹三选 Sheet：**排队穿插**（`steer`）/ **等它做完**（`followUp`）/ **打断，重说**（`clear_queue`+`abort`+重发） |
| 长按发送 | 直接选送达方式，不弹层 |
| 回车 | **默认换行**（手机惯例）；可在设置改为"回车发送" |
| 发送后 | 清空输入、保留草稿快照（撤销用）、插入用户消息、滚动到底、轻震 |
| 草稿 | 按会话隔离持久化，切会话/杀进程都不丢 |

### 4.5 滚动、跟随与长会话

- **跟随逻辑**：默认跟随最新。用户上滑 > 1 屏 → 解锁跟随 → 右下浮出 **「↓ 回到最新（N）」** FAB（`N` = 未读新消息数）。
- **流式时若已解锁跟随，绝不抢滚动**（最常被做错的地方）。
- **回顶**：双击 App Bar；或长按该 FAB 跳到会话开头。
- **上一轮/下一轮**：长会话时底部中央出现极简 `↑ 上一轮 / ↓ 下一轮`（在 `UserMessage` 之间跳转，对应 pi 的 `previousPrompt`/`nextPrompt`）。
- **长会话加载**：打开时先渲染最后 50 条，向上滚动时分批加载更早的 entry（`get_entries` 天然支持增量），顶部显示加载指示。
- **位置恢复**：离开会话时记住锚点 entry id，回来时 `scrollToItem(key)`。
- **搜索**：App Bar 放大镜 → 顶部搜索栏（命中计数、上/下一处、关闭），命中用 `searchMatchBg/Text`，当前命中反转配色 + 加粗。搜索范围含工具输出（可关）。

### 4.6 消息分组与时间戳

- **时间戳**：不在每条消息上重复显示。规则：每轮首次出现处显示一次（右下角，`dim`，11.5sp）；跨天插入 `DateSeparator`（今天 / 昨天 / 具体日期）。
- **无头像**：pi 的流里没有"两个人对话"的概念，加头像会误导。
- **用户消息用容器**（`userMessageBg`，16dp 圆角，内边距 14dp，宽度 100%），**助手文本无容器**（直接落在画布上）。这个对比本身就是"输入 vs 内容"的语义区分 —— 比左右气泡更准确。
- **连续助手文本**（多段之间没有工具调用）合并为一个块，避免碎片化。

### 4.7 附件与图片

- 入口：📎 → 相册 / 拍照 / 文件 / 粘贴板
- 发送时转 base64 走 `images` 字段（对应 pi 的 `ImageContent`）
- 缩略图 64dp 圆角 12dp，右上角 `×` 删除
- 图片超过 2000×2000 或 4.5 MB 时按 pi 的 `images.autoResize` 自动缩放，并提示「已压缩」
- `images.blockImages` 开启时，📎 的图片入口禁用并说明原因

### 4.8 消息与卡片的操作

| 目标 | 手势 | 动作 |
|---|---|---|
| 任意文本 | 长按 | 选中即复制（默认，对应 `fullscreenCopyOnSelect`）；选中后浮动条：复制 / 引用 / 搜索 |
| 用户消息 | 长按 | 复制 · **编辑并从此分叉**（pi 的 fork from user message） · 删除后续（需确认） |
| 助手消息 | 长按 | 复制全部 · 保存为文件 · 重新生成（仅最后一条） |
| 工具卡 | 点击 | 展开/收起全部（对应 `app.tools.expand`） |
| 工具卡（bash） | 长按 | 复制命令 · 复制输出 · **在工作区终端重跑** · 查看完整日志文件 |
| 工具卡（edit/write） | 点击 | 全屏 diff；有检查点时提供「回滚此文件」 |
| 工具卡（read/grep/find/ls） | 点击 | 打开文件页并定位到行 |
| 思考块 | 点击 | 展开/收起（对应 `app.thinking.toggle`） |
| 压缩卡 | 点击 | 展开摘要全文 |
| 分支摘要 | 点击 | 展开 · 跳到该分支 |
| 图片 | 点击 | 全屏查看器（缩放/保存/分享） |
| 模型变更行 | 点击 | 打开模型面板 |

### 4.9 对话页的空态与异常
| 场景 | 呈现 |
|---|---|
| 无当前会话 | 居中卡：「开始一个新会话」+ 3 个示例任务 + 「选择已有会话」 |
| 无模型配置 | 居中卡：「先配置模型」+ 主按钮（跳凭证设置） |
| 加载历史 | 骨架屏（3 个消息块 + 2 个工具卡），>300ms 才显示 |
| 断线 | 顶部 `warning` Banner：「与内核断开」+ 重连；若 supervisor 仍在跑，副文案「后台仍在运行，重连后自动恢复」 |
| 模型错误 | 流内 `ErrorText` + 「重试 / 换模型 / 查看详情」 |
| 上下文已满 | 状态行 `error` 色 + 一行提示「即将自动压缩」 |
| 压缩中 | 状态指示器旋转 + 流内指示行 |
| 重试中 | 状态指示器 `warning` + 「重试 2/3 · 4s」 |

### 4.10 渲染技术选型（**裁决：原生 Compose 为准**）

主设计文档 §20.3 曾建议「对话流用 WebView 复用 pi 的 `export-html` 渲染器」。**这条在 v2 里被否决** —— 因为那套 HTML 是**终端审美的**（全等宽 12px、扁平 4px 边框、无表面层级），把它塞进一个现代原生 App 会造成两种设计语言打架。**UI 规范优先于实现便利。**

**分工**

| 内容 | 技术 | 说明 |
|---|---|---|
| 对话流全部 14 类内容块 | **原生 Compose** | 按 §7.4 的规格实现 |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer-m3`（或等价的 Compose Markdown 库） | 需支持 GFM 表格、任务列表、嵌套列表；代码块渲染回调交给我们自己 |
| 语法高亮 | `dev.snipme:highlights`（纯 Kotlin，无 JNI） | 后台线程做，先渲染纯文本再替换 |
| ANSI 转义（bash 输出） | 自写 SGR 解析（~150 行） | 只需 SGR：16/亮色、256 色、真彩、bold/dim/italic/underline；不支持的序列静默丢弃 |
| unified diff 解析 | 自写（~200 行） | 解析 `@@` hunk + 行级分类；渲染按 §7.4 的 `tool-diff` |
| Mermaid 图 | **小 WebView**（每个图一个，隔离） | 对应 pi 的 `markdown.mermaid`（off/final/streaming）；这是唯一合理的 WebView 用法 |
| 文件页编辑器 | v1：`BasicTextField` + 高亮；M4：CodeMirror in WebView | 与对话流解耦 |
| **扩展的自定义工具渲染** | **Node 侧调 pi 的 `createToolHtmlRenderer`** → HTML 字符串 → **该工具卡内一个隔离 WebView** | 这保住了「扩展渲染 100% 兼容」，同时把 WebView 限制在单张卡片里，不污染整体设计语言 |
| 终端页 | 原生终端视图 + 真 PTY | 复用 termux-app 的 `terminal-emulator`/`terminal-view` |

**为什么这样分**：现代 App 的观感只能靠原生 Compose 保证（IME、滚动、动效、主题、无障碍都是原生的强项）；而 WebView 只在两个"确实需要"的地方出现 —— Mermaid 图与扩展自定义渲染 —— 且都是**被隔离的小块**，不会把终端审美带进主界面。

**代价与对策**：Markdown 的边角（脚注、定义列表）可能长期欠债 → 对策是「按需补 + 不承诺完整 GFM」，并在设置里提供「用 WebView 渲染这条消息」的逃生开关（长按消息 → 用 HTML 打开）。

---

## 5. 其他界面

### 5.1 会话列表

**App Bar**：标题「会话」+ 搜索 + 「排序/分组」图标 + FAB（新建）。

**列表项**（三段式，高 88dp）
```
┌─────────────────────────────────────────────────────┐
│ ▍ 重构认证中间件                          3 分钟前   │  ← 左竖条=状态色；标题；右侧时间
│   ~/projects/api · main                             │  ← cwd + 分支（muted）
│   ▓▓▓▓▓▓▓▓░░░░░░░░ 52%      ↑24.1k  ¥0.87           │  ← 上下文条 + token + 成本
└─────────────────────────────────────────────────────┘
```
- 左竖条 4dp：`accent`（当前活跃）/ `success`（刚完成）/ `warning`（等审批）/ `outlineVariant`（空闲）
- 无名称时显示首条用户消息的前 40 字
- **长按 → 上下文菜单**：重命名 · 分叉 · 复制分支 · 导出 HTML · 导出 JSONL · 分享 gist · 删除
- **左滑** → 删除（二次确认 + 5 秒撤销 Snackbar）；**右滑** → 置顶
- 分组：按工作区（默认）/ 按时间 / 平铺；排序：最近更新 / 名称 / 消息数
- 过滤 Chip：全部 / 已命名 / 当前工作区
- 搜索：匹配标题、首条消息、全部消息文本（pi 的列表阶段就缓存了 `allMessagesText`）

### 5.2 会话树（全屏）

- 纵向树，节点 = entry；节点显示：角色色点 + 摘要 + 时间戳 + 标签
- 过滤器 Chip（pi 的 5 种）：默认 / 无工具 / 仅用户 / 仅标签 / 全部
- 折叠/展开分支段（对应 `tree.foldOrUp` / `unfoldOrDown`）
- 点节点 → 底部 Sheet：**从这里继续**（移动叶子）· **分叉新会话** · **加标签** · **复制消息** · **查看原始 JSON**
- 废弃分支：虚线 + 若有摘要则显示摘要卡
- **`/tree` 的精确语义**：选中用户/自定义消息 → 叶子移到其父节点，并把该消息文本放回输入框以便重发；选中助手/工具/压缩 → 叶子移到那里；选根 → 回到空会话。导航到废弃分支时可选生成分支摘要（不生成 / 默认提示词 / 自定义聚焦点）

### 5.3 工作区

顶部 SegmentedButton：**终端 · 文件 · Git · 任务**

**终端**
- 多标签，标签类型：① Shell（rootfs `/bin/bash -l` at cwd）② **pi TUI（原版 pi，100% 兜底）** ③ 自定义命令
- 顶部一行极简按键条：`esc` `tab` `ctrl` `↑` `↓` `←` `→` `|` `~` `/` `-`
- 选中即复制；长按粘贴；字号/光标/滚回可配
- **这是唯一保留"终端观感"的地方** —— 因为它本来就是终端

**文件**
- 面包屑 + 文件树（左滑固定）+ 编辑器（高亮/行号/只读标记）
- 动作：在终端打开 · 在对话中引用（插入 `@path`）· git blame
- 权限边界明确提示（只读目录 / 需 SAF）

**Git**
- 分支、ahead/behind、工作区状态；变更列表 → 点击看 diff（复用对话的 diff 组件）
- 快捷：暂存 / 取消暂存 / 提交（提交信息可让模型生成 → 走一次 prompt）
- 与 `git-checkpoint` 扩展联动：检查点列表 + 一键回滚

**任务**
- 后台 agent 轮次与 bash 作业；每项显示类型、开始时间、耗时、状态、**停止**
- 对应前台通知「pi 正在运行 · N 个任务」点进来的落点

### 5.4 模型与凭证（设置内）

- Provider 列表（pi 内建 40 个，国产置顶并标「国内直连」）
- 每个 Provider：认证状态（未配置 / API Key / OAuth）、凭证编辑、可用模型
- 模型详情：上下文窗口、价格、能力（视觉/推理/工具调用）
- 循环模型（scoped models）：勾选 + 拖动排序（对应 `enabledModels` 与 `app.models.reorderUp/Down`）
- 逐模型思考等级（`modelThinkingLevels`）、思考预算（`thinkingBudgets`）
- 本地模型：`/llama` 入口 —— 管理 llama.cpp router（下载/加载/卸载 GGUF）

### 5.5 扩展与资源（设置内）

- **扩展**：全局 / 项目两组；每项显示来源、路径、加载状态、诊断信息（pi 的 loader 会把失败收集为 diagnostics）、启用开关
- **资源包**：`pi install npm:@x/y` / `git:` / 本地路径；列表显示已装包与可更新状态（对应 `packages` + `pi list/update`）
- **技能**：列表 + SKILL.md 预览 + 校验结果（name/description 规则）
- **提示模板**：列表 + 参数提示（`$1`、`$@`、`${1:-def}`）
- **主题**：内置 dark/light + 自定义 + 导入 + 自动模式
- **上下文文件**：`AGENTS.md`/`SYSTEM.md` 的全局与项目版本，内置编辑器
- **信任**：项目信任名单（`trust.json` 可视化），扩展信任名单

### 5.6 设备能力（设置内）

分级授权页（每项一个卡片，含权限状态、用途说明、开关）：
1. 基础（默认开）：剪贴板、通知、打开链接、分享
2. 存储：SAF 目录授权（显示已授权目录列表）
3. 无障碍：屏幕读取与操作（高敏感，单独开关 + 展开说明 + 跳系统设置）
4. 位置 / 传感器 / 相机：单独开关
5. Shell（Shizuku / ADB）：状态检测 + 配对引导

每项显示**当前能力清单**（这个能力让 Agent 能做什么），并给出「本会话暂时禁用」的快捷开关。

### 5.7 运行时与诊断（设置内）

- pi 版本 + 「检查更新」+ 更新历史（可回滚到上一版）
- Node 版本、rootfs 占用、apt 包管理、npm 缓存清理
- 后台保活状态、唤醒锁状态、**幻影进程杀手指引**（一键跳 ADB 设置 + 复制命令）
- 日志查看器（按级别/模块过滤）、导出诊断包
- 安全模式启动（禁用用户扩展）

---

## 6. 设置体系

### 6.1 导航层级模型（最多 4 层）

```
L0 设置首页       13 个分组，每组右侧显示"当前值摘要"
   └─ L1 分组页    若干 Section + 设置行
        └─ L2 编辑器   选择器 / 输入 / 列表编辑
             └─ L3 子编辑器  逐模型的覆盖、逐级别的预算、包详情
```

**约束**：任何设置从首页到可改**不超过 3 次点击**；高频项（模型、思考等级、压缩、主题）在 L0 就提供快捷入口。

**L0 首页结构**
```
┌────────────────────────────────────────────┐
│ 设置                              🔍       │  ← 全局搜索
├────────────────────────────────────────────┤
│ 当前模型                            ⟩      │  ← 快捷卡：显示模型 + 思考等级，点击直接切
│ claude-sonnet-4.5 · ◐ medium               │
├────────────────────────────────────────────┤
│ 🧠 模型与推理            默认 sonnet-4.5  ⟩ │
│ 💬 消息与队列            穿插：逐条        ⟩ │
│ 📦 上下文与压缩           自动 · 16k/20k   ⟩ │
│ 🔁 重试与网络             开 · 3 次        ⟩ │
│ 🔧 工具                   6 个已启用       ⟩ │
│ 💾 会话                   ~/.pi/sessions  ⟩ │
│ 🧩 扩展与资源             12 个扩展        ⟩ │
│ 🎨 外观                   深色 · 跟随系统  ⟩ │
│ ⌨️ 终端与 Shell           等宽 13sp        ⟩ │
│ 👆 交互                   双击返回：会话树 ⟩ │
│ 🛡️ 安全与信任             项目：询问       ⟩ │
│ 📱 设备能力               无障碍：开       ⟩ │
│ ⚙️ 运行时与诊断           pi 0.85.1       ⟩ │
│ 🔒 隐私与关于             遥测：关         ⟩ │
└────────────────────────────────────────────┘
```

### 6.2 设置行的 6 种类型

| 类型 | 布局 | 用途 |
|---|---|---|
| **SwitchRow** | 标题 + 说明 + 右侧 Switch | 布尔设置 |
| **ValueRow** | 标题 + 说明 + 右侧当前值 + `⟩` | 枚举/对象（点击开 L2 选择器） |
| **NumberRow** | 标题 + 说明 + 右侧数值；展开为滑杆 + 输入框 | 数值（`reserveTokens` 等） |
| **TextRow** | 标题 + 说明 + 右侧值 | 字符串（路径、URL、代理） |
| **ListRow** | 标题 + 「N 项」+ `⟩` | 数组（packages / extensions / skills / prompts / themes / enabledModels / defaultTools） |
| **ActionRow** | 标题（危险时 `error` 色） | 动作（导出、重置、安全模式） |

**辅助元素**：`SectionHeader`（分组标题）、`InfoNote`（灰色说明卡，用于解释性文字）、`EffectiveBadge`（生效方式，见 6.5）、`ExperimentalBadge`。

**每行必带说明文字** —— 直接采用 pi 文档里的描述（中文化），不自己编。

### 6.3 全局搜索

- L0 顶部搜索框；输入即跨**全部 80+ 设置项**模糊匹配标题、说明、**字段名**（`reserveTokens` 也能搜到）
- 结果显示「路径面包屑」+ 当前值，点击直达该项（并高亮）
- 也匹配 pi 的斜杠命令（`/compact` → 跳到压缩设置）

### 6.4 完整设置清单（按层级）

**L0-1 模型与推理**
- L1：默认厂商（ValueRow，40 个 Provider 选择器）· 默认模型（ValueRow）· 默认思考等级（ValueRow，7 档）· 逐模型思考等级（ListRow → L2 列表 → L3 单模型）· 思考预算（ListRow → L3，4 档数值）· 循环模型（ListRow，勾选+排序）· 隐藏思考块（Switch）· 缓存未命中提示（Switch，高级）· 传输方式（ValueRow：auto/sse/websocket/websocket-cached）
- 凭证：API Key / OAuth（L2 表单 + Custom Tabs 登录 + 登出）

**L0-2 消息与队列**
- 穿插模式（ValueRow：逐条/全部）· 后续模式（ValueRow）· HTTP 空闲超时（Number，0=禁用）· WebSocket 连接超时（Number）· HTTP 代理（Text，全局）

**L0-3 上下文与压缩**
- 自动压缩（Switch）· 保留 token（Number，默认 16384）· 保留最近 token（Number，默认 20000）· 逐模型覆盖（ListRow → L3）· 分支摘要保留 token（Number）· 跳过"要摘要吗"询问（Switch）
- 动作：立即压缩（ActionRow，可填自定义指令）

**L0-4 重试与网络**
- 自动重试（Switch）· 最大次数（Number，3）· 基础延迟（Number，2000ms）· 最大延迟（Number，60000ms）· Provider 超时（Number，高级）· Provider 重试次数（Number）· Provider 最大延迟（Number）

**L0-5 工具**
- 内建工具开关（ListRow → L2，8 项；**grep/find/ls 默认开**）· bash 默认超时（Number）· 输出截断上限（Number）· 工具输出默认展开（Switch）

**L0-6 会话**
- 会话目录（Text）· 导入会话（Action）· 导出全部（Action）· 清理策略（ValueRow）

**L0-7 扩展与资源**
- 扩展（ListRow）· 资源包（ListRow + 安装/更新/移除）· 技能（ListRow）· 提示模板（ListRow）· 主题（ListRow + 导入）· 技能命令开关（Switch）· 上下文文件（ListRow → 编辑器）· 信任名单（ListRow）

**L0-8 外观**
- 主题（ValueRow，含自动模式 `"浅/深"` 字面量）· 动态取色（Switch，Android 12+）· 字号微调（Number ±2sp）· 消息密度（ValueRow：舒适/紧凑/宽松）· 时间戳显示（Switch）· 思考块默认折叠（Switch）· 用户消息内边距 `outputPad`（ValueRow 0/1）· 编辑器水平内边距 `editorPaddingX`（0–3）· 补全最大可见项 `autocompleteMaxVisible`（3–20）· 代码块缩进 `markdown.codeBlockIndent`（Text）· Mermaid 渲染 `markdown.mermaid`（off/final/streaming）· 图片自动缩放（Switch）· 屏蔽图片（Switch）· 隐藏启动信息卡 `quietStartup`（Switch）

**L0-9 终端与 Shell**
- Shell 路径 `shellPath`（Text）· 命令前缀 `shellCommandPrefix`（Text）· npm 命令 `npmCommand`（ListRow）· 终端字号 · 光标样式 · 滚回行数 · 终端图片 `terminal.showImages`（Switch）· 图片宽度 `terminal.imageWidthCells`（Number）· 收缩清屏 `terminal.clearOnShrink`（Switch）· 终端进度 `terminal.showTerminalProgress`（Switch）· 超链接 `terminal.hyperlinks`（ValueRow，高级）· 图片协议 `terminal.images`（ValueRow，高级）· 真彩 `terminal.trueColor`（ValueRow，高级）· 键盘按键条自定义（ListRow）

**L0-10 交互**
- 双击返回动作 `doubleEscapeAction`（ValueRow：会话树/分叉/无）· 选中即复制 `fullscreenCopyOnSelect`（Switch）· 滚动条策略 `fullscreenScrollbar`（ValueRow：auto/always/hidden）· 退出时输出 `fullscreenExitOutput`（ValueRow）· TUI 模式 `tuiMode`（ValueRow：regular/fullscreen）· 树过滤器 `treeFilterMode`（ValueRow，5 种）· 硬件光标 `showHardwareCursor`（Switch）· 对话内搜索范围（ValueRow）· 手势开关（ListRow：滑动删除、双击回顶等）· 触觉档位（ValueRow）· 音效（Switch）· 动效强度（ValueRow：完整/减少/跟随系统）· 低端设备模式（Switch）

**L0-11 安全与信任**
- 项目信任策略 `defaultProjectTrust`（ValueRow：询问/总是/从不）· 项目信任名单（ListRow）· 扩展信任（ListRow）· 危险操作分级阈值（ValueRow）· Anthropic 额外用量警告 `warnings.anthropicExtraUsage`（Switch）· 审计日志（ListRow → 查看器）· 紧急停止（Action）

**L0-12 设备能力**（见 5.6）

**L0-13 运行时与诊断**（见 5.7）

**L0-14 隐私与关于**
- 安装遥测 `enableInstallTelemetry`（Switch）· 匿名分析 `enableAnalytics`（Switch）· 追踪 ID `trackingId`（只读）· 版本与 changelog（`collapseChangelog`）· 开源许可 · **原始配置**（直接编辑 `settings.json` / `keybindings.json`，带校验与恢复默认）

### 6.5 生效方式标记（`EffectiveBadge`）

pi 的不同设置生效时机不同，App 必须明确标注，否则用户会困惑：

| 标记 | 含义 | 涉及设置 |
|---|---|---|
| — | 立即生效 | 大多数 |
| 🔄 **重载** | 需要 `/reload`（等价于 App 的重载动作） | 键位、扩展、技能、模板、主题、上下文文件 |
| 🔁 **重启会话** | 需要新会话或重连内核 | 模型默认值、思考等级默认值、shell 路径 |
| ♻️ **重启 App** | 需要重启进程 | 会话目录、部分运行时设置 |

点标记 → 弹出说明 + 「立即执行」按钮。

### 6.6 原始配置与导入导出

- **原始配置编辑器**：直接编辑 `settings.json` / `keybindings.json`，等宽字体、JSON 校验、错误定位、保存前 diff 预览
- **从桌面导入 `~/.pi`**：选一个目录/压缩包，识别并导入 AGENTS.md、extensions、skills、prompts、themes、settings.json、auth.json(**可选，默认不导入凭证**)
- **导出配置包**：把当前配置打包（不含凭证），用于分享或备份
- **重置**：分项重置 / 全部重置 / 仅重置外观

---

## 7. 组件库

### 7.1 基础组件

| 组件 | 规格 |
|---|---|
| **主按钮** | 高 40dp（行内）/ 56dp（底部主操作），圆角全圆，底 `Primary`，字 `OnPrimary`，14sp Medium；按下 92% 缩放 + tonal overlay |
| **次按钮** | 描边 1dp `Outline`，文字 `Primary` |
| **文字按钮** | 无底，文字 `Primary`；用于"展开/收起/跳过" |
| **图标按钮** | 40dp 触控区，24dp 图标；仅图标时必须有 `contentDescription` |
| **FAB** | 56dp，圆角 16dp，`PrimaryContainer`；「新建会话」「回到最新」「发送」各一处 |
| **扩展 FAB（发送）** | 圆形 48dp，IDLE 时箭头、STREAMING 时方块（停止） |
| **SegmentedButton** | 高 40dp，全圆，选中项 `SecondaryContainer`；用于工作区分段 |
| **Tab** | 用于终端标签；高 40dp，选中下划线 3dp `Primary`；可关闭（`×`） |
| **Chip** | 高 32dp，全圆，1dp 描边；有 FilterChip（可选中）、AssistChip（带图标）、InputChip（可删除，用于队列/附件） |
| **Switch** | M3 标准，48×32 |
| **滑杆** | 用于数值；配数值显示，可点数值直接输入 |
| **步进器** | 用于 ±2sp 这类小范围 |
| **文本框** | 填充式（`SurfaceContainerHighest`）+ 圆角 16dp；聚焦时 2dp `Primary` 边框 |
| **下拉/选择器** | 一律用 Modal Bottom Sheet，不用系统下拉菜单（手机上更好点） |
| **搜索框** | 全宽，左侧放大镜，右侧清除；`SurfaceContainerHigh` |

### 7.2 容器类

| 组件 | 规格 |
|---|---|
| **卡片** | 圆角 16dp，`SurfaceContainerLow`，无描边（或 1dp `OutlineVariant`）；内边距 14dp；层级用 surface 而非阴影 |
| **列表项** | 高 72dp（双行）/ 56dp（单行）；左图标/左竖条、标题 15sp、副标题 14sp `muted`、右侧元信息或 `⟩` |
| **可展开面板** | 头部（标题 + 状态 + 展开箭头）+ 内容；`AnimatedVisibility` 高度动画 250ms |
| **ModalBottomSheet** | 顶部 28dp 圆角、拖拽把手、scrim 32%、最大 90% 高、可 `skipPartiallyExpanded` |
| **Dialog** | 圆角 28dp，最大宽 400dp；主按钮在右 |
| **Snackbar** | 底部，圆角 12dp，可带一个动作（撤销/重试），4 秒 |
| **Banner** | 顶部通栏，`SurfaceContainerHigh` + 左侧 3dp 状态色条；用于断线/权限/上下文满 |
| **Tooltip / 长按提示** | 仅用于图标按钮的语义补充，不用装饰性 tooltip |

### 7.3 数据展示

| 组件 | 规格 |
|---|---|
| **上下文环** | 20dp 环，2dp 宽；阈值色（>70 warning，>90 error）；不显示百分比数字时用点阵替代 |
| **线性进度条** | 用于会话列表里的上下文条、任务进度 |
| **徽章** | `auto`（自动压缩）、`sub`（订阅）、`exp`（实验）、工具三态符号 |
| **键值行** | 用于会话信息、模型详情：左 `muted` 标签、右值（数字等宽） |
| **统计块** | 2×2 或一行四格：token 输入/输出、缓存命中率、成本、耗时 |
| **空态** | 极简线稿插图（120dp）+ 标题 17sp + 说明 14sp `muted` + 一个主按钮；**不写"暂无数据"这种废字** |
| **骨架屏** | 与真实内容同形状的 `SurfaceContainerHigh` 圆角块 + 1.5s 微光扫过；>300ms 才出现 |
| **错误态** | 图标 + 一句人话 + 「重试」+ 「详情」（展开原始错误） |
| **加载更多** | 列表底部 48dp 指示器，或顶部下拉 |

### 7.4 对话专用组件（14 类，逐个规格）

**通用规则**：宽度撑满；块间距 16dp；圆角 16dp（有容器的）；无容器的不加边框。

| 组件 | 规格 |
|---|---|
| **user-message** | 容器 `userMessageBg`，圆角 16dp，内边距 14dp，字 15/23；支持 Markdown 与图片；右下角时间戳（每轮首次） |
| **assistant-text** | **无容器**，直接落画布，内边距左右 0（靠页面边距 16dp）、上下 0；Markdown 全支持；流式末尾呼吸光标 |
| **thinking-block** | 收起：一行 32dp，左侧 3dp 圆角色条（thinking 等级色）+ 「思考 12s」+ `muted`；展开：`thinkingText` 色 + **斜体** + 行高 22；`hideThinkingBlock` 时不渲染 |
| **tool-execution** | 容器三态色（pending/success/error），圆角 16dp，内边距 12dp；标题行 = 等宽工具名（`dim` 11.5sp）+ 参数摘要（15sp 单行省略）+ 状态符号；输出区等宽 13/20；页脚 = 退出码 · 耗时 · 行数 + 「展开」；>5s 无输出显示运行计时 |
| **tool-diff** | 顶部路径 + `+12 −5`；正文等宽 13/20，**符号列 16dp（`+`/`-`/空格）+ 极淡底纹（8% 透明）+ 文字色**；上下文行折叠「…N 行未变」；>200 行折叠为统计行；点击开全屏 diff（统一/并排可切） |
| **compaction** | 通栏细线 + 中央 Chip（`customMessageLabel`）+ 文案「上下文已压缩 · 释放 42k tokens」；点击展开摘要（Markdown） |
| **branch-summary** | 卡片 `customMessageBg`，头部一行标签「分支摘要」+ 分支 id；点击跳转 |
| **hook-message** | 卡片 `customMessageBg` + 左侧 3dp `customMessageLabel` 色条 + 顶部等宽小标签（`customType`）；**视觉上必须与用户/助手消息明显区分** |
| **model-change** | 单行 32dp，11.5sp `dim`；模型名 `borderAccent` + Medium；文案「模型切换 → sonnet-4.5」 |
| **skill-invocation** | 卡片；头部「技能 `/skill:name`」+ 展开箭头；展开显示 SKILL.md 正文（Markdown） |
| **system-prompt** | 收起为一行「系统提示 · 3.2k 字符」；展开显示全文（等宽或按 Markdown 渲染，可切） |
| **message-images** | 1 图撑满（最大 100% 宽）/ 2–4 图 2×2 网格 / >4 图网格 + `+N`；圆角 12dp；点击全屏（缩放/保存/分享） |
| **error-text** | 卡片 `toolErrorBg` 弱化版 + 左侧 3dp `error` 条；一句人话 + 「详情」+ 「重试」 |
| **date-separator** | 居中细线 + 中央时间标签 11.5sp `dim` |

### 7.5 终端专用组件

- 终端视图（复用 termux-app 的 `terminal-emulator`/`terminal-view`，Apache-2.0）
- 按键条（横向可滚动 Chip）
- 标签栏（可关闭 Tab）
- 补齐的能力：**OSC 52**（剪贴板）、**OSC 8**（超链接）、**Kitty 键盘协议**（至少回退 `modifyOtherKeys`）
- 不实现内联图片图形协议，改设 `PI_IMAGE_PROTOCOL=none`

---

## 8. 状态、边界与异常

| 场景 | 设计 |
|---|---|
| 首次启动 | 3 步向导（选厂商 → 填凭证 → 选模型+工作区），可跳过；权限按需申请，不一次性全请求 |
| 无会话 | 空态 + 主按钮 + 3 个示例任务 |
| 无模型 | 引导卡，主按钮跳凭证 |
| 加载 | 骨架屏；>300ms 才显示 |
| 离线 | 顶部 `warning` Banner「网络不可用，本地任务仍可运行」 |
| 上下文将满 | 状态行 >70% `warning`、>90% `error`；满溢前提示即将自动压缩 |
| 长任务 | 前台通知（进度 + 停止 + 回到 App）；完成通知 + 成功触觉 |
| 断线 | `error` Banner + 重连；supervisor 存活时提示「后台仍在运行」 |
| 工具失败 | 卡片变 error 色；不弹全局错误（模型通常会自愈） |
| 幻影进程被杀 | 持久 `warning` Banner + 「如何解决」（跳 ADB 引导）；任务页标记该轮被系统终止 |
| 权限缺失 | 相关入口显示锁 + 一句原因；点击跳设置对应项 |
| 崩溃恢复 | 启动检测异常退出 → 「恢复上次会话」/「安全模式启动」 |
| 存储不足 | 运行时页显示占用；<1 GB 告警 + 「清理 npm 缓存」 |
| 删除会话 | 二次确认 + 5 秒撤销 Snackbar |

---

## 9. 无障碍

- **对比度**：正文 ≥4.5:1，元信息 ≥3:1；浅色主题需修正 pi light 的 `muted`（偏弱）
- **字号**：跟随系统 + App 内 ±2sp
- **色盲**：diff 必须有 `+/-` 符号；工具状态必须有文字/形状；思考等级永远带文字
- **TalkBack**：每个内容块一个语义节点，`contentDescription` 用 pi 术语（「用户消息」「助手回复」「工具调用 bash，执行中」「思考块，已折叠」「上下文压缩」）；工具卡状态变化用 `liveRegion=polite`；输入框播报当前模式（普通/Shell/静默 Shell）与思考等级
- **开关控制**：所有手势都有等价按钮（长按功能也进 `⋮` 菜单）
- **减少动效**：跟随系统「移除动画」+ App 内开关
- **触控目标**：≥48dp，间距 ≥8dp

---

## 10. 自适应布局

| 断点 | 布局 |
|---|---|
| **<600dp**（手机竖屏） | 一屏一舞台；底部 4 导航；输入区贴 IME |
| **600–839dp**（手机横屏 / 折叠展开） | 双栏：左 360dp 会话列表 + 右对话；工作区占右侧 |
| **≥840dp**（平板 / DeX） | 三栏：会话列表 · 对话 · 工作区（可拖拽分隔）；完整键盘快捷键 |

- 分屏/自由窗口窄于 600dp 时退化为竖屏布局
- 折叠屏：铰链处避免放交互元素；展开时自动切双栏
- 横屏对话页：输入区仍在底部，状态行可折叠

---

## 11. 视觉特效清单（做什么 / 不做什么）

### 做
1. **思考色温光晕**：输入框聚焦时，边框外 2dp 有一层对应思考等级的柔和光晕（`thinking*` 色 12% 透明，blur 8dp）—— App 的签名视觉
2. **顶栏毛玻璃**：内容滚动到顶栏下方时，顶栏 `Surface` 88% + 12dp blur（全 App 唯一用模糊处）
3. **工具卡状态呼吸**：完成瞬间边框一次 `success`/`error` 色的 1px 呼吸（600ms）
4. **上下文环扫过**：数值变化时环 200ms 扫过；跨阈值时颜色渐变 + 轻震
5. **流式光标**：末尾 8×16dp 圆角方块，1s 透明度呼吸
6. **共享元素转场**：会话卡片 → 对话页 App Bar 标题；缩略图 → 全屏图片
7. **消息流入**：淡入 + 上移 8dp，同批错开 40ms
8. **骨架微光**：1.5s 线性扫过
9. **Chip/卡片收起展开**：高度 + 透明度同步动画（250ms spring）
10. **危险确认脉冲**：卡片红色边框一次脉冲 + 长震

### 不做
- ❌ 任何形式的渐变背景（除光晕）
- ❌ 装饰性视差、粒子、彩带、庆祝动画
- ❌ 列表项逐条飞入 / 交错入场（除消息流）
- ❌ 按钮弹跳、`overshoot` > 1.1
- ❌ 同时出现两处以上持续动画
- ❌ 彩色 3D 插画
- ❌ 全屏转圈（超过 1 处）

---

## 12. 实施顺序与交付物

| 阶段 | 交付 |
|---|---|
| **前置** | `tokens.json`（从 pi 主题派生）→ 生成 Compose Theme + WebView CSS 变量；组件库骨架（§7.1–7.3）；对话内容块（§7.4） |
| **M1** | 会话列表 + 对话页（流 + 输入区 + 状态行）+ 审批 Sheet + 空态；仅 dark |
| **M2** | 工作区（终端优先）+ 会话树 + 设置 L0–L2（含搜索）+ 向导 + light 主题 |
| **M3** | 设备能力授权 + 权限引导 + 长任务通知 + 任务页 + 诊断页 |
| **M4** | 平板/折叠/横屏 + 无障碍打磨 + 动效与触觉精修 + 主题导入 + 原始配置编辑 |

**关键判断**：先把**对话页 + 输入区 + 审批**打磨到极致 —— 这三块占日常使用的 90%。会话树、Git、设置高级项全部可后置。

---

# 附录 A　覆盖率矩阵：pi 的每项能力 → App 入口

这张表就是「100% 兼容」在 UI 层的证明。

| pi 能力 | App 入口 | 备注 |
|---|---|---|
| 发消息 / 流式回复 | 对话页输入区 | |
| `steer` 穿插消息 | 流式中发送 → 三选「排队穿插」 | |
| `followUp` 后续消息 | 流式中发送 → 三选「等它做完」 | |
| `abort` 中止 | 停止按钮 / 返回键 | |
| `clear_queue` | 停止按钮（含还原到输入框） | pi 的 Esc 语义 |
| 队列取回 | 队列 chip 点击 / 「全部取回编辑」 | |
| 新会话 | 会话列表 FAB | |
| 会话列表 / 恢复 | 会话列表页 | RPC 无 list，App 扫目录 |
| 会话重命名 | 顶栏标题点击 / 长按菜单 | `set_session_name` |
| 会话信息 | `⋮` → 会话信息 | `get_session_stats` |
| 会话树导航 | 会话树全屏页 | RPC 只有 `get_tree` 数据 |
| 从消息分叉 | 用户消息长按 → 编辑并从此分叉 | `fork` |
| 复制分支 | `⋮` → 复制分支 | `clone` |
| 分支摘要 | 会话树导航时弹出三选 | |
| 手动压缩 | `⋮` / 快捷面板 → 压缩 | `compact` |
| 自动压缩开关 | 快捷面板 / 设置·上下文 | `set_auto_compaction` |
| 模型切换 | 状态行模型名 → 模型面板 | `set_model` |
| 模型循环 | 模型面板「循环」/ 蓝牙键盘 Ctrl+P | `cycle_model` |
| 可用模型列表 | 模型面板 | `get_available_models` |
| 循环模型（scoped） | 设置·模型与推理·循环模型 | RPC 无，写 `enabledModels` |
| 思考等级 | 键盘工具条徽章 / 快捷面板 | `set_thinking_level` |
| 思考等级循环 | 徽章点击 | `cycle_thinking_level` |
| 思考等级列表 | 徽章长按 | `get_available_thinking_levels` |
| 思考块折叠/展开 | 思考块点击 / `⋮` | 对应 `app.thinking.toggle` |
| 工具输出展开/收起 | 工具卡点击 / `⋮` → 全部展开 | `app.tools.expand` |
| 工具开关（active tools） | 快捷面板 → 工具 | `getActiveTools`/`setActiveTools` |
| 自动重试开关 | 快捷面板 / 设置·重试 | `set_auto_retry` |
| 中止重试 | 状态行「中止」 | `abort_retry` |
| 穿插/后续模式 | 快捷面板 / 设置·消息与队列 | `set_steering_mode`/`set_follow_up_mode` |
| 直接执行 bash | 工作区·终端 | `bash` / `abort_bash` |
| `!command` Shell 模式 | 输入区 `!` 前缀（边框变色） | |
| `!!command` 静默 Shell | 输入区 `!!` 前缀 | |
| 斜杠命令 | 输入 `/` → 命令面板 | `get_commands` |
| 命令列表 | 命令面板 | `get_commands` |
| 文件引用 `@`/`#` | 输入区 `@` → 文件面板 | |
| 路径补全 | 键盘工具条 Tab | |
| 多行输入 | 回车换行（默认） | |
| 外部编辑器 | 键盘工具条 `⌨` → 全屏 sheet | |
| 图片输入 | 📎 → 相册/拍照/文件/粘贴 | `images` 字段 |
| 历史提示词 | 键盘工具条 `↑` | |
| 草稿持久化 | 自动 | |
| 全部 23 个斜杠命令 | 见 §6.7 映射表 | 11 个需 App 自实现 |
| 全部 80+ 设置项 | 设置 14 组（§6.4） | 1:1 覆盖，含 2 个未文档化字段 |
| 键位自定义 | 设置·交互·快捷键（终端页 + 蓝牙键盘用） | 读写 `keybindings.json` |
| 主题 | 设置·外观（含自动模式字面量 + 导入） | 读写主题 JSON |
| 技能 | 设置·扩展与资源·技能；命令面板 `/skill:` | |
| 提示模板 | 设置·扩展与资源·模板；命令面板 | |
| AGENTS.md / SYSTEM.md | 设置·扩展与资源·上下文文件（内置编辑器） | |
| 扩展加载/信任 | 设置·扩展与资源·扩展 + 安全与信任 | |
| 资源包安装/更新 | 设置·扩展与资源·资源包 | `pi install/update` |
| 项目信任 | 首次进入项目时弹窗 + 设置 | |
| 凭证（API Key / OAuth） | 设置·模型与推理·凭证 | OAuth 走 Custom Tabs |
| 本地模型（llama.cpp） | 设置·模型与推理·本地模型 | 内建扩展命令 `/llama` |
| 导出 HTML | `⋮` / 长按会话 → 导出 HTML | `export_html` |
| 导出 JSONL | 同上 | |
| 分享（gist） | `⋮` → 分享 | RPC 无，走 CLI |
| 导入 JSONL | 设置·会话·导入 | |
| 会话搜索 | 会话列表搜索 + 对话内搜索 | |
| 从桌面迁移 | 设置·隐私与关于·从桌面导入 `~/.pi` | |
| 重载资源 | 顶栏 ⟳ | 对应 `/reload` |
| 版本 / changelog | 设置·隐私与关于 | |
| 更新 pi | 设置·运行时与诊断 | `pi update` / npm |
| **扩展的终端绘制 UI** | **工作区·终端（原版 pi TUI）** | 唯一需要逃生舱的部分 |
| **内建 TUI 斜杠命令** | 同上 | |

**未覆盖项：0。** 需要逃生舱的只有两类，都在工作区·终端里，且用的是**原版 pi**。

---

# 附录 B　设置字段 → 层级映射（完整清单）

（按 §6.4 的 14 组，共 80+ 字段。此处省略逐行展开，以 §6.4 为准。关键点：）

- **两个文档里没有但源码里有的字段**必须支持：`terminal.showTerminalProgress`（bool，默认 false）、`packages[].autoload`（bool）
- **主题自动模式的字面量**：`theme` 存 `"浅色主题名/深色主题名"`，必须原样往返
- **数组字段的语法**支持 glob、`!pattern`（排除）、`+path`（强制包含）、`-path`（强制排除）—— 编辑这些数组时 App 要保留这个语法，不能简化为纯路径列表
- **路径解析规则**：全局设置里的相对路径相对 `~/.pi/agent`，项目设置里相对 `.pi`
- **项目设置会递归合并覆盖全局**；`defaultTools` 是**数组整体替换**而非合并
- **会话目录优先级**：`--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > `sessionDir` 设置

---

# 附录 C　一句话总结

> **这是一款现代原生安卓 App。**
> 它用 Material 3 的表面、圆角、动效与导航；
> 用 pi 的语义色、术语与状态含义；
> 把「思考等级色温」做成自己的签名视觉；
> 聊天界面是一条忠于 agent 心智模型的单一流，不是聊天软件的气泡；
> 设置是 4 层、6 种行型、可全局搜索、且标注生效方式；
> 并且有一张覆盖率矩阵，证明 pi 的每一项能力都有入口 —— 唯一需要终端的那两项，交给工作区里的原版 pi。
