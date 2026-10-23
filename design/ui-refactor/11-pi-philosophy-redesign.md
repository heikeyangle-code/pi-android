# 11 · 对齐 pi 的调整（代码核对版）

> 上一稿（同日早）只看了 pi 的设计文档和部分源码，把「主题令牌」「工作区资源」「覆盖层缺件」都判断错了。
> 这一版是**逐文件核对当前代码**之后的结论。事实来源：`app/src/main/kotlin/app/pi/ui/**`（当前工作区）与
> `/root/repos/pi-upstream`。
>
> 结论先说：**这套 UI 不需要"重做"**。它已经按 pi 的语义长出来了，真正要动的是三处收敛 + 一处新增。

---

## 1. 已经与 pi 对齐的（核对过，别再改）

| 面 | 证据 |
|---|---|
| 主题令牌 | `theme/PiPalette.kt`：pi 的 56 个 token 全量字面转写（`accent #A798D7`、`userMessageBg #213B49`、工具三态底、思考七档、diff、syntax、bashMode）；`theme/PiThemeFiles.kt` 复刻 pi 的主题发现（`~/.pi/agent/themes`、项目 `.pi/themes`、`themes` 设置）+ schema 校验 |
| 转录条目类型 | `ui/blocks/` 覆盖 pi 的条目语义：`BranchSummary`、`Compaction`、`SkillInvocation`、`HookMessage`、`Thinking`、`Diff`、`Read/Write/Edit/Grep/Shell`、`Notice`、`DateSeparator`… |
| 渲染原语 | `ui/render/PiMarkdown`、`PiLatex`、`PiCodeHighlight`、`PiHtml`（与 pi 的 md 语义色一致） |
| 覆盖层 | `ModelPickerSheet`、`ThinkingPickerSheet`、`SessionToolsSheet`、`ContextSheet`、`SessionStatsSheet`、`ForkPickerSheet`、`SessionTreeScreen`、`SlashPalette`、`MentionPalette`、`BashPanel` |
| 工作区资源 | `screens/WorkspaceResources.kt` 已按 pi 的 package-manager 语义分 4 类（`extensions/skills/prompts/themes`）× global/project，含来源标签与冲突提示 |
| 会话树 / fork / 扩展 UI | `chat/SessionTreeScreen`、`chat/PiTreeNavigation`、`ExtensionUiHost`（全局单挂） |
| 视觉骨架 | 手绘底栏（非 Material NavigationBar）、工具卡多轮打磨、`PiMeter` 刻度 |

所以「工作区要不要重做」「设置要不要重做」「颜色要不要换」——**都不要**。

---

## 2. 真正的三处偏差（要调整的）

### 2.1 常驻信息：pi 的页脚在我们这儿不存在

pi 的 `components/footer.ts` 是两段常驻信息：

```
~/work/x (main) • 会话名
↑12.4k ↓3.2k R8.1k CH72.5% $0.42  12.3%/200k (auto)        opus-4-5 • 中
```

当前代码里：

- 上下文与用量在 **⋮ 菜单** 里（`ChatScreen.kt:1834`「上下文与用量」→ `ContextSheet`）；
- 模型在 **顶栏**（`ChatScreen.kt:3400` `ModelChip`）；
- 没有常驻的成本/缓存/上下文行。

**调整**：新增 28dp 页脚（两行，左状态右模型），点击各段打开既有 `ContextSheet` / `ModelPickerSheet`；
顶栏只留「会话名（点开会话覆层）+ ⋮」。>70% 黄、>90% 红，照 pi 的阈值。
这是**唯一真正新增的界面**。

### 2.2 会话入口碎成三处

现状：`SessionToolsSheet`（会话工具）、`SessionTreeScreen`（树）、`ForkPickerSheet`（分叉）三个独立入口；
`NavRequest.Sessions` 在 `PiRoot.kt` 里已经没有消费者（只剩 `onOpenSessions` 回调）。

pi 是三个并列 selector：`session-selector`（列表+搜索）、`tree-selector`、`user-message-selector`（只列用户消息，fork 从这里起）。

**调整**：合成一个「会话」覆层，三个 tab：**列表（搜索）/ 树 / 用户消息**；fork 从用户消息那一行发起。
好处：一个入口、三种视图，与 pi 的三个 selector 一一对应，且修掉失效的 `NavRequest.Sessions`。

### 2.3 顶栏承担的语义不对

pi 顶栏没有模型——模型属于页脚（状态），不属于标题区。

**调整**：顶栏 = 会话名 + ⋮；模型/思考下移到页脚右端。这一条与 2.1 是同一件事的两半。

---

## 3. 工作区与设置：只调文案与分组，不重做

### 工作区（`WorkspaceChrome/Files/Resources/Viewer/PiWrite/ExternalPicker`，≈3.1k 行）

内容已经对：cwd、文件、`.pi` 资源（4 类 × 2 作用域）、查看器、外置打开、写回。
对齐 pi 只做两件事：

1. 标题与分组用 pi 的词汇：`cwd`、`extensions / skills / prompts / themes`、`global / project`，不再出现"终端"字样；
2. 顶部一行状态：`cwd (git branch) · 本会话改过 N 个文件`——与页脚同一套措辞。

### 设置（`settings/PiSettingsRegistry` + `ModelProviderScreen` + `DiagnosticsScreen`）

现有分组已含 pi 的语义（默认模型/默认厂商/**循环模型**（= pi 的 scoped models）/推理/已导入模型/本地模型/API Key/订阅登录/诊断/许可）。
只调整顺序与措辞，让读取顺序与 pi 的 settings 一致：

`模型` → `资源（extensions/skills/prompts/themes）` → `外观（主题文件）` → `对话（压缩/思考/发送）` → `输入` → `工具` → `引擎与运行` → `应用（语言/诊断/许可）`。
不新增页面、不改实现。

---

## 4. 渲染能不能改成 Web？—— pi 有现成的，但是**只读**的

`packages/coding-agent/src/core/export-html/`：

| 文件 | 行数 | 作用 |
|---|---|---|
| `template.html` | 55 | 外壳：侧栏（树 + 搜索 + 过滤按钮）、转录容器 |
| `template.css` | 1076 | 完整视觉（含 pi 的主题色注入） |
| `template.js` | 1902 | 转录渲染：条目、工具卡、diff、折叠、树导航、深链（`leafId`/`targetId`） |
| `ansi-to-html.ts` | 258 | 工具输出里的 ANSI → HTML（与 TUI 一致） |
| `tool-renderer.ts` | 172 | 扩展工具的自定义 HTML |

数据是一次性快照：`<script id="session-data">` 里 base64 的 `{header, entries, leafId, systemPrompt, tools, renderedTools}`；
侧栏有 5 种过滤（default / no-tools / user-only / labeled / all）。

**关键限制：它没有输入框**（`template.js` 里 `composer`/`textarea` 计数为 0）——是导出查看器，不是聊天客户端。

所以：

| 想法 | 判断 |
|---|---|
| 用 pi 的 export-html 做**导出/分享/长会话回看** | **值得做**：零协议改动、与 pi 的实际导出产物完全一致、主题自动跟随 |
| 把**实时聊天**搬进 WebView | 不划算：要自己加输入与增量渲染，还会丢掉现有的 tail-follow、图片查看、20 个 block 的原生交互、`Transcript.kt`(2763 行) 的性能优化 |
| 复用 **omp 的 collab-web** | 技术上可行但需要 pi→omp 快照适配层，且会把 omp 的设计语言（纯黑 + 青粉）带进来，与"统一"目标相反 |

**建议**：聊天保持原生；新增「用 pi 的 export-html 预览/分享」这一个 WebView 入口（设置或 ⋮ 里），它顺手解决了"长会话想给别人看"的需求。

---

## 5. 分阶段（都不碰协议层）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1** | 页脚（两行，点击开既有 sheet）；顶栏去掉模型芯片 | 数字与 pi 语义一致；>70/90% 变色 |
| **P2** | 会话覆层三 tab（列表/树/用户消息），fork 从用户消息起 | 一个入口三种视图；返回键先关覆层 |
| **P3** | 工作区/设置文案与分组对齐（纯字符串与顺序） | 不新增页面，diff 里只有文案与顺序 |
| **P4** | 用 `export-html` 做只读预览/分享入口 | 打开导出的 HTML 与 pi 导出的同款一致 |

## 6. 待拍板

1. 页脚两行还是压成一行？（两行信息全，多占 20dp）
2. 「导出预览」放设置还是会话 ⋮ 菜单？
