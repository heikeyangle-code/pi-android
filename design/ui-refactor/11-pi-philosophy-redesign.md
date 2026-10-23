# 11 · 按 pi 的哲学重做（稿）

> 事实来源：`/root/repos/pi-upstream`（pi 源码，commit e178770）。
> 只读它自己的界面层：`packages/coding-agent/src/modes/interactive/**`、`theme/dark.json`、`theme/light.json`。
> 本文是**提案**，不改代码；已裁决过的东西（三 tab、终端降格）不推翻，只在其上对齐 pi。

---

## 1. pi 的界面到底由什么组成

### 1.1 一条“会计师”页脚，不是状态胶囊

`modes/interactive/components/footer.ts` 的 render 顺序：

```
左： <cwd 缩写> (git branch) • <sessionName>
    ↑输入 ↓输出 R缓存读 W缓存写 CH命中率% $成本  12.3%/200k (auto)
右： <model id> • <thinkingLevel>
```

- 上下文 >70% 变黄、>90% 变红；
- `(auto)` 表示自动压缩开着；
- 订阅制 provider 显示 `$0.000 (sub)`；
- 全是数字，全部可扫读——pi 把“这次会话花了多少、还剩多少”当成常驻信息。

### 1.2 一切操作都是覆盖层（45 个 selectors）

`modes/interactive/components/` 里的选择器就是 pi 的“页面”：

| 覆盖层 | 作用 |
|---|---|
| `session-selector` + `session-selector-search` | 会话列表（带搜索），`/resume` |
| `tree-selector` | 会话树：分支、跳转、从某条消息重开 |
| `user-message-selector` | 只列用户消息，快速回跳 |
| `model-selector` + `scoped-models-selector` | 模型（provider 分组、成本、窗口）+ 限定可选集合 |
| `thinking-selector` | 思考档（off…max，7 档，各有颜色） |
| `settings-selector` + `settings-submenu` | 设置：分组 + 子菜单（含「每模型思考档」） |
| `theme-selector` | 主题（pi 有 dark/light JSON 主题，可扩展） |
| `oauth-selector` / `login-dialog` | 登录、切账号 |
| `extension-selector` / `extension-editor` / `extension-input` | 扩展自己的 UI |
| `show-images-selector`、`trust-selector`、`first-time-setup` | 图片显示、项目信任、首启 |
| `skill-invocation-message`、`branch-summary-message`、`compaction-summary-message` | 技能调用、分支摘要、压缩摘要（pi 的记忆压缩是有界面的） |

### 1.3 视觉是“okhsl 令牌 + 紫色强调”

`theme/dark.json`（78 行）里：`accent = violet(295 50% 67%)`，用户消息是**蓝底卡片**（`userMessageBg = blueBg`），
工具卡按状态分 `toolPendingBg / toolSuccessBg / toolErrorBg`，Markdown 有独立色（标题黄、链接蓝、代码紫、代码块绿），
思考档从紫到粉到橙（`thinkingOff…thinkingMax` 七档渐变），diff 绿/红/灰。

### 1.4 pi 没有 workspace

cwd 派生一切：会话按 cwd 分组、`.pi/` 资源按目录查找、`/add-dir` 是命令。
所以「工作区」这个 tab 在 pi 里没有对应物——它是我们**为了手机**加的一个“项目现场”，不是 pi 的概念。

---

## 2. 现状 vs pi：差在哪

以 v2 三 tab（`对话 / 工作区 / 设置`）为基线：

| 面 | pi 的做法 | 我们现在的做法 | 差距 |
|---|---|---|---|
| 常驻信息 | footer 会计师行（↑↓R W CH% $ ctx%/window auto + 模型·思考） | 顶栏会话名 + 若干芯片 | **要重做成 pi 的页脚语义** |
| 会话切换 | selector 覆盖层（列表 + 搜索），树是另一个 selector | 覆盖层已存在（列表/树） | 基本一致，需合成一个 overlay 的两个 tab |
| 模型 | 分组 + 成本/窗口 + scoped models | 模型选择页 | 缺 scoped models、成本/窗口信息 |
| 思考档 | 7 档 + 颜色 | 有档位 | 需核对 7 档与颜色映射 |
| 主题 | dark/light JSON，可自定义 | 自家 PiTheme | **建议把 pi 的 JSON 令牌映射进来**（紫强调、蓝用户卡） |
| 用户消息回跳 | user-message-selector | 无 | 缺 |
| 会话树 | tree-selector（分支/重开） | 有 SessionTreeScreen | 功能在，入口与层级要对齐 |
| 分享 | `session-share` / `/share` | 导出 | 缺“分享链接”（可后续） |
| 项目信任 | trust-selector | 无 | 缺（首启/打开新目录时） |
| 扩展 UI | selector/editor/input | ExtensionUiHost ✓ | 已对齐 |
| 命令面板 | `/` 补全，只列命令 | SlashPalette ✓ | 保持一致：**只放命令，不放 App 动作** |
| 首启 | first-time-setup | BootScreen ✓ | 已对齐 |

**结论**：结构（三 tab + 覆盖层）已经是对的，真正要补的是**常驻信息（页脚）、覆盖层的完整度、以及视觉令牌与 pi 对齐**。

---

## 3. 提案

### 3.1 对话页：把页脚还给 pi

```
┌ 顶栏 56 ────────────────────────────────┐
│ 会话名（点 → 会话 overlay）        ⋮      │
├ 转录（现有，不改） ───────────────────────┤
│ …                                        │
├ 输入区（现有，不改） ─────────────────────┤
├ 页脚 28（新增，pi 语义） ─────────────────┤
│ ~/work/x (main) • 修复键盘遮挡            │
│ ↑12.4k ↓3.2k R8.1k CH72.5% $0.42 12.3%/200k (auto) │
│                                     opus-4-5 • 中 │
└──────────────────────────────────────────┘
```

- 两行：左状态、右模型；手机宽度下把成本与上下文放第二行尾，模型在右端。
- 点页脚任何数字 → 打开对应覆盖层（成本 → usage；上下文 → 压缩；模型 → 模型选择）。
- **顶栏不再放模型/思考芯片**（pi 就是放在页脚）。
- 数字用等宽；>70% 黄、>90% 红（照抄 pi 的阈值）。

### 3.2 会话 overlay：列表 ↔ 树，一个覆盖层两个 tab

- 顶部：搜索（pi 的 `session-selector-search` 语义）+ 右侧「树」切换。
- 列表项：标题 / `时间 · 轮数 · cwd`；当前会话高亮。
- 树：现有 `SessionTreeScreen` 移进来，分支/重开动作保留。
- 长按项 → 改名 / 导出 / 删除（pi 没有长按，但手机必须）。

### 3.3 模型与思考：补齐 pi 的两个选择器

- 模型：provider 分组 + 搜索；每行 `名字 · 成本 in/out · 窗口`；底部「限定模型（scoped）」入口——选中的集合成为 `/model` 循环范围（pi 的 `scoped-models-selector`）。
- 思考：7 档（off/minimal/low/medium/high/xhigh/max），当前模型不支持的档位置灰并说明原因（pi 的 thinking-selector 同款）。

### 3.4 工作区（项目现场）——手机特有，但用 pi 的词汇

- 顶部：`cwd`（点 → SAF 选目录）+ `git branch / 改动数`。
- 三组：
  1. **本会话动过的文件**（读会话条目里的工具调用）；
  2. **`.pi/` 资源**：技能 / 提示词 / 扩展 / 主题 / 包（pi 的项目级发现路径）；
  3. **正在运行**：后台 bash / 任务（对应 pi 的 bash-execution）。
- 不给终端任何位置（已裁决）。

### 3.5 设置：按 pi 的分组重排

| 组 | 内容（pi 的语义） |
|---|---|
| 模型 | 默认模型、scoped 集合、每模型思考档（pi settings 的 `per-model thinking`） |
| 外观 | 主题（dark/light/跟随 + 自定义 JSON 导入）、排版密度、代码高亮 |
| 对话 | 自动压缩、上下文阈值、思考显示、发送键行为 |
| 输入 | 编辑器行为、Tab 补全、撤销栈深度（pi 的输入层配置） |
| 工具 | 工具开关、bash 模式、审批策略 |
| 扩展 | 扩展/技能/包管理、内置扩展 |
| 凭证 | 登录、账号、provider key |
| 终端 | 保留一行入口（已裁决，零投入） |
| 应用 | 语言、关于、许可、诊断日志 |

### 3.6 视觉令牌：从 pi 的 JSON 抄

| 令牌 | dark 值（okhsl） | 用途 |
|---|---|---|
| accent | `295 50% 67%` | 强调、选中、链接 |
| userMessageBg | `233 41% 24%`（蓝） | 用户消息卡片底 |
| customMessageBg | `295 42% 24%` | 扩展消息卡片 |
| toolSuccessBg / toolErrorBg / toolPendingBg | `158 46% 25%` / `19 54% 25%` / `229 5% 24%` | 工具卡状态底 |
| text / muted / dim | `234 3% 89%` / `229 6% 67%` / `229 8% 56%` | 三级文字 |
| thinkingOff…Max | 紫→粉→橙 七档 | 思考档指示 |
| mdHeading / mdLink / mdCode / mdCodeBlock | 黄 / 蓝 / 紫 / 绿 | Markdown 语义色 |

原生侧只取其中 ~24 个语义令牌（`PiColors` 已有类似结构，替换数值与映射即可），**不引入 okhsl 运行时**，在构建期算成 hex。

---

## 4. 分阶段（每一步都能独立验收，不碰协议层）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1** | 页脚：两行会计师信息 + 点击进覆盖层 | 打开任意会话，数字与 pi 的语义一致；>70/90% 变色 |
| **P2** | 会话 overlay 合并（列表 + 树 两个 tab、搜索、长按） | 一个入口两个视图，返回键先关覆盖层 |
| **P3** | 模型/思考覆盖层补齐（分组、成本、窗口、scoped、7 档） | 与 pi 的 model/thinking-selector 逐项对齐 |
| **P4** | 令牌对齐（紫强调、蓝用户卡、工具三态底、Markdown 语义色） | 换色后工具卡/转录不破相，截图对比 |
| **P5** | 设置分组重排（表 3.5） | 旧分组项全部有归属，无丢失 |

不动的东西：三 tab 结构、工具卡（已多轮打磨）、终端（零投入）、会话树实现本身。

---

## 5. 待用户拍板

1. 页脚放手势/信息密度：两行（如上）还是压成一行（手机更省高度，但数字会挤）？
2. 主题是否**完全**照 pi 的紫强调 + 蓝用户卡，还是保留现有强调色、只借 pi 的语义层级？
3. scoped models（限定可选模型集合）v1 要不要做？
