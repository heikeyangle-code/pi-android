# 02 · 真实内容与 Mockup 数据（app.pi / pi-android）

> 这一份是**像素级 Mockup 的素材表**：所有中文标签都从 Kotlin 源码里逐字抄出来，并附 `文件:行号`。
> 没有 Lorem ipsum，没有「示例标题 1」，没有自造按钮。
>
> 两条硬约定，请在 Figma 里也照做：
>
> | 标记 | 含义 |
> |---|---|
> | `‹源› 文件:行` | 这是 App 真正会渲染的字符串，可直接粘贴。 |
> | `<!-- 示例 -->` | 这一段是**我编的**（对话内容、会话标题、diff 正文、命令输出）。它的**形状**来自代码，**文字**不是 App 自带的标签。 |
>
> 本文件不引用 `docs/pi-android-ui-spec.md` / `docs/pi-android-app-design.md`（用户已声明过时）。
> 对话流块清单**只**取自 `ui/blocks/BlockRenderer.kt` 的 `when` 分发、`ui/blocks/*` 各块的 `Text(...)`，以及 `rpc/Transcript.kt` 的 item 定义。

---

## 0.1 对话流块清单（唯一权威：代码）

`BlockRenderer.kt:82`–`134` 是一个**封闭的** `when`（没有 `else` 分支，注释 `:35-44` 说明这是故意的：新块必须编译报错）。
它接收 14 种 `TranscriptItem`（`rpc/Transcript.kt:34-44` 起的 sealed interface），其中 `ToolCall` 再按 `item.toolName` 二次分发成 8 种工具卡（`BlockRenderer.kt:99-114`）。

| # | item（代码名） | 渲染块 | 视觉形态 | 该块自己的中文标签 | 源 |
|---|---|---|---|---|---|
| 1 | `UserMessage` | `UserMessageBlock` | 容器气泡 | 长按菜单「复制」「编辑并从此分叉」 | `UserMessageBlock.kt:49,55` |
| 2 | `AssistantText` | `AssistantTextBlock` | 无容器，落在画布上 | 长按「复制全部」 | `AssistantTextBlock.kt:46` |
| 3 | `ThinkingBlock` | `ThinkingBlockBlock` | 左侧色条 + 一行/展开 | 「思考中…」「思考 12s」「（无思考内容）」 | `ThinkingBlockBlock.kt:43,45,79` |
| 4 | `ToolCall` → `read` | `ReadBlock` | 工具卡 | 标题 `read`；「文件为空」「展开全部（还有 N 行）」 | `ReadBlock.kt:65,77,86` |
| 5 | `ToolCall` → `write` | `WriteBlock` | 工具卡 | 标题 `write`；「参数里没有文件内容。」 | `WriteBlock.kt:57` / `ToolBlockChrome.kt:288` |
| 6 | `ToolCall` → `edit` | `EditBlock` | 工具卡（不显示 diff 正文） | 标题 `edit` | `EditBlock.kt:52` |
| 7 | `ToolCall` → `grep` | `GrepBlock` | 工具卡，按文件分组 | 标题 `grep`；「没有匹配」「N 处」 | `GrepBlock.kt:86,93,141` |
| 8 | `ToolCall` → `find` | `PathListBlock`（FindBlock） | 工具卡，路径清单 | 标题 `find`；「N 项」 | `PathListBlock.kt:38,127` |
| 9 | `ToolCall` → `ls` | `PathListBlock`（LsBlock） | 工具卡，路径清单 | 「（空目录）」 | `ToolOutputParse.kt:534` |
| 10 | `ToolCall` → `bash`/`powershell` | `ShellBlock` | 工具卡，**尾部**预览 | 标题 = `$ 命令`；「运行中」「已运行 12.3 秒」 | `ShellBlock.kt:64,125` / `ToolOutputParse.kt:251` |
| 11 | `ToolCall`（其它工具 / 带图片） | `ToolCallBlock` | 通用工具卡 | 「输出超过 200 KB，正文未展开。」「输出过长，只解析了前面一部分。」 | `ToolCallBlock.kt:141` / `ToolBlockChrome.kt:291` |
| 12 | `ToolDiff` | `DiffBlock` | 独立 diff 卡（**跟在 edit 卡后面**） | 「新增 6 行 · 删除 5 行」「… 12 行未变」「差异过长，仅显示前 200 行」 | `DiffBlock.kt:79,112,127`；跟随关系见 `Transcript.kt:1405-1440` |
| 13 | `CompactionMarker` | `CompactionBlock` | 通栏 chip | 「正在压缩上下文…」「上下文已压缩」「压缩已中止」「压缩失败」「释放 42k tokens」 | `CompactionBlock.kt:50-53,61` |
| 14 | `BranchSummary` | `BranchSummaryBlock` | 卡片 | 「分支摘要」+ 分支 id；点击「跳转分支」/「展开摘要」 | `BranchSummaryBlock.kt:58,67` |
| 15 | `HookMessage` | `HookMessageBlock` | 卡片 + 左侧色条 | 顶部等宽标签 = `customType`，缺失时 `extension` | `HookMessageBlock.kt:58` |
| 16 | `ModelChange` | `ModelChangeBlock` | 单行 | 「模型切换 →」+ 模型 id + provider | `ModelChangeBlock.kt:50,56,66` |
| 17 | `SkillInvocation` | `SkillInvocationBlock` | 卡片，可折叠 | 「技能」+ `/skill:<name>` | `SkillInvocationBlock.kt:79,85` |
| 18 | `ErrorText` | `ErrorBlock` | 错误卡 | 「出错了」「详情」 | `ErrorBlock.kt:54,69` |
| 19 | `DateSeparator` | `DateSeparatorBlock` | 居中细线 | 「今天」「昨天」「3 月 14 日」 | `Transcript.kt:543-547` |
| 20 | `Notice` | `NoticeBlock` | App 自身 notice 行 | 前缀「·」；空文本时「（无内容）」 | `NoticeBlock.kt:34,40` |

**没有的东西（请勿在 Mockup 里加）**：

- **没有 `todo` / task-list 块，没有 plan 块，没有 subagent 块。** 依据：`BlockRenderer.kt:82-134` 的 `when` 是封闭集合（注释 `:35-44` 说明它**故意不带 `else`**，新块必须让编译失败）；工具渲染器只有 `read/write/edit/grep/find/ls/bash/powershell`（`:102-112`）。「任务清单 / 计划 / 子代理」在这个 App 里没有生产者。
- 如果稿子里确实需要「像 todo 一样的东西」，只有两条**代码诚实**的路：① 助手正文里的 markdown 列表（`AssistantTextBlock` → `PiMarkdownText`）；② 扩展写入的条目走 `HookMessageBlock`，正文是 markdown。注意 markdown 渲染用的是 `com.mikepenz.markdown` 的默认组件，本 App 只覆盖了图片 / 数学 / 代码围栏（`PiMarkdownComponents.kt:116,170,233,296,344`）——**没有复选方框组件**，所以不要画 `- [x]` 样式的勾选框。
- 最接近「进度/清单」语义的真实替代物是：`CompactionBlock`（压缩进度）、`BranchSummaryBlock`（分支摘要）、`SkillInvocationBlock`（技能正文）、`HookMessageBlock`（扩展条目）、`NoticeBlock`（App 自述行）。
- 没有「连接」状态：打开 App 就启动引擎（`PiRoot.kt:71`）。

---

## 1. 会话列表 `SessionsScreen`

源：`ui/screens/SessionsScreen.kt`（整屏）、`ui/components/PiCommon.kt:224-268`（空态）。

### 1.1 真实结构（自上而下）

| 位置 | 真实内容 | 源 |
|---|---|---|
| AppBar 标题 | `会话` | `SessionsScreen.kt:136` |
| AppBar 右侧动作 | `导入`（文字按钮，打开系统文件选择器 `*/*`） | `:140` |
| AppBar 右侧图标 | 刷新，无障碍标签 `刷新会话列表` | `:142` |
| 搜索框 placeholder | `搜索名称 / 目录 / 文件名` | `:155` |
| 排序按钮（切换） | `按名称` ⇄ `按时间` | `:160` |
| 筛选按钮（切换） | `仅命名` ⇄ `全部` | `:163` |
| 搜索框下方提示 | `长按一行可删除该会话；当前会话要切换后才能删除。` | `:171` |
| 分组头 | `工作区`（本应用自己的工作区）/ 其它 cwd 取最后一段 / 无 cwd 时 `工作目录未记录` | `:379-385` |
| 右下 FAB | `新建会话`（图标 Add） | `:228` |

### 1.2 行字段公式（三段，逐字）

每行是「标题 + 当前徽标 + 第二行 + 第三行 + 右侧相对时间」：

```
第 1 行   summary.displayName            ← pi 的 set_session_name；未命名时由列表推导
          [当前]   ← 仅当该行是 pi 正在写入的会话（badge，:326-333）
第 2 行   groupLabel(cwd)                ← 「工作区」或目录末段
          + " · " + model                ← model 为 null 时整段省略（:338-341）
第 3 行   "<messageCount> 条"
          + " · 分支"                    ← 仅当 parentSession != null（:351）
          + " · 已命名"                  ← 仅当 name 非空（:352）
右侧      relativeTime(lastActivityAt)    ← :391-401
```

相对时间的真实措辞（`relativeTime()` `:393-400`）：
`刚刚` · `N 分钟前` · `N 小时前` · `N 天前` · `N 周前`（未来时间也显示 `刚刚`）。

排序与分组的事实（做排序态 mockup 时用）：先按 `cwd` 分组，组内行顺序来自 store；组之间按 **`rows.maxOf { it.lastActivityAt }` 降序**（`:191-194`）。「按名称」时按 `displayName.lowercase()` 排序（`:129`）。

未命名会话的标题：`displayName` 由 store 从会话内容推导。<!-- 示例 --> 下面 12 条的标题按「未命名 = 首条用户消息的前几字」的形状编，命名过的用「已命名」标记。

### 1.3 空态（两套，都要做）

```
还没有会话
会话按工作目录分组，这里会列出每一个目录的对话。
```
源 `SessionsScreen.kt:179-180`，图标 `Icons.Filled.Forum`。

```
没有匹配的会话
换一个关键词，或关掉「仅命名」筛选。
```
源 `SessionsScreen.kt:186-187`。

### 1.4 12 条会话样例 <!-- 示例：标题与数字为编造，字段形状来自 1.2 -->

字段顺序固定为：`标题 | 当前? | 第二行 | 第三行 | 右侧`。

```
工作区（分组头）                                        ‹源› groupLabel → SessionsScreen.kt:383
1  会话列表排序错了 · 对话页顶部状态行          [当前]   工作区 · deepseek/deepseek-chat        18 条 · 已命名                 刚刚
2  把 diff 卡片的高度收紧                              工作区 · anthropic/claude-sonnet-4-5    42 条 · 已命名                 12 分钟前
3  工具卡的展开按钮点不到                              工作区 · deepseek/deepseek-reasoner     9 条                          1 小时前
4  设置里 reserveTokens 该填多少                       工作区 · openai/gpt-5                  27 条 · 分支                   3 小时前
5  检查一下 @ 提及补全超时                             工作区 · zai/glm-4.6                    6 条                          昨天
6  会话导出为 HTML 少了扩展渲染                        工作区 · anthropic/claude-sonnet-4-5    55 条 · 已命名                 2 天前
7  队列里的消息怎么单独撤回                            工作区 · deepseek/deepseek-chat        4 条                          3 天前
8  主题的对比度在深色下不达标                          工作区 · moonshotai/kimi-k2            31 条 · 分支 · 已命名          1 周前
9  终端里 pi 的按键条挡住了输入                        工作区 · qwen/qwen3-coder-plus         12 条                          1 周前

pi-android（分组头：另一个 cwd 取末段）                  ‹源› SessionsScreen.kt:384
10 把 run-app-pure-checks 的输出看懂                   pi-android · deepseek/deepseek-chat     7 条                          4 小时前
11 pi-contract 检查失败的三个文件                      pi-android · anthropic/claude-sonnet-4-5 14 条 · 分支                  5 天前
12 源码许可清单要补一个库                               pi-android · openai/gpt-5               3 条                          2 周前

工作目录未记录                                          ‹源› SessionsScreen.kt:381（异常态，建议也画一条）
13 （无标题，displayName 为文件名）                     · 未记录                                                               1 分钟前
```

> Mockup 提示：搜索框是等宽字体（`PiTheme.text.mono`，`:156`）；分组头是 `PiSectionHeader`（小号、`primary` 色，`PiCommon.kt:272-284`）。

### 1.5 行内交互的两个对话框（真实文案）

长按一行的动作选择（`AlertDialog`，标题 = 该会话 `displayName`）：

```
标题：<会话名>
正文：对这个会话做什么？                      ‹源› :242
按钮：新建子会话        删除        取消       ‹源› :247,254,255
```

删除确认（标题固定 `删除会话`，`:270`）：两种正文，**当前会话那一条是禁止态**。

```
（非当前会话，可删除）
删除「<会话名>」？这个会话会被移除，无法恢复。            ‹源› :276
按钮：删除（可点）  取消                                ‹源› :287,290

（当前会话，确认按钮 disabled）
「<会话名>」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。   ‹源› :274
```

---

## 2. 一段真实感的对话记录（28 个块）

**场景** <!-- 示例 -->：同一个人在 app.pi 自己的仓库里干活。
第一轮修「会话列表排序错了」，第二轮修「把 diff 卡片的高度收紧」。
所有**对话文字**、**diff 正文**、**命令输出**都是我编的；所有**块的类型名、状态词、页脚格式、按钮标签**都来自代码。

### 2.0 块序总览

```
01 date-separator        今天
02 user-message          「会话列表排序错了…」+ 1 张截图
03 thinking-block        折叠态「思考 8s」
04 assistant-text        标题 + 列表 + 行内代码
05 tool-execution/read   read SessionsScreen.kt:119-131     成功 ✓
06 tool-execution/grep   grep /sortedBy/ in app/…           成功 ✓ · 3 个文件
07 assistant-text        一句话结论
08 tool-execution/edit   edit SessionsScreen.kt             成功 ✓
09 tool-diff             独立 diff 卡（+6 −5）
10 tool-execution/write  write 新测试文件                    成功 ✓
11 tool-execution/bash   $ tools/run-app-pure-checks.sh      成功 ✓ · 多行输出
12 tool-execution/bash   $ ./gradlew :app:compileDebugKotlin 运行中 … · 已运行 12.3 秒   ← 长任务进度态
13 thinking-block        展开态（斜体思考正文）
14 tool-execution/find   find "*.kt" in app/src              成功 ✓ · 12 项
15 tool-execution/ls     ls app/src/main/kotlin/app/pi/ui     成功 ✓
16 tool-execution/bash   $ rg -n "TODO" …                    失败 ✗ · 退出码 2   ← 工具失败卡
17 error-text            「出错了」+ 详情                      ← 错误块
18 notice                「上下文已压缩（12,340 → 8,100 tokens）」
19 compaction            「上下文已压缩 · 释放 42k tokens」
20 branch-summary        「分支摘要」
21 model-change          「模型切换 →」
22 skill-invocation      「技能 /skill:review」
23 hook-message          customType = extension
24 message-images        2 图 + 1 溢出（「+1」）
25 tool-execution（通用卡）ToolCallBlock 回退形态
26 extension confirm     扩展审批弹窗（允许 / 拒绝 + 倒计时）
27 queue chips           穿插 2 · 后续 1 · 收回并编辑（输入区上沿）
28 assistant-text        收尾：fenced code block + 下一步
```

### 2.1 逐块数据

#### 块 01 · `date-separator`
- 类型：`DateSeparator` → `DateSeparatorBlock`（`BlockRenderer.kt:130`）
- 真实标签：`今天` / `昨天` / `3 月 14 日` / `2025 年 12 月 2 日`（`Transcript.kt:543-547`）
- 渲染：居中细线，中央标签 11.5sp `dim`（`DateSeparatorBlock.kt:44-46`）

```
label: 今天
```

#### 块 02 · `user-message`
- 类型：`UserMessage` → `UserMessageBlock`
- 容器：`userMessageBg`，内边距 14dp，右下角时间戳 `14:02`（`UserMessageBlock.kt:62-88`）
- 长按菜单：`复制` / `编辑并从此分叉`（`UserMessageBlock.kt:49,55`）
- 附件：图片走 `ImageGridBlock`，下面这张是**待发送**区的截图

```
text: |
  会话列表排序错了。我按「按时间」的时候，最近活动的目录应该排在最上面，
  但现在带「已命名」的那几条跑到最后去了。
  截图是这个列表，注意「工作区」那一组已经掉到第二屏。
images: 1 张（image/png 720×1280）
time: 14:02
```
<!-- 示例 -->

#### 块 03 · `thinking-block`（折叠态）
- 类型：`ThinkingBlock` → `ThinkingBlockBlock`
- 折叠标签：运行中 `思考中…`，结束后 `思考 8s`（`ThinkingBlockBlock.kt:43-45`）
- 左侧 3dp 色条 = 思考等级色（`PiTheme.palette.thinking(level)`，`:40`）
- 展开时正文用斜体 + `thinkingText`；空正文为 `（无思考内容）`（`:79`）

```
status: 已结束
headline: 思考 8s
level 标签: 中        ← thinkingLabelOf("medium") = 「中」，ChatSheets.kt:663
body（折叠时不显示）: 排序在 groupBy 之后做的，先看 visible 的构造。
```
<!-- 示例（正文） -->

#### 块 04 · `assistant-text`（markdown 全要素）
- 类型：`AssistantText` → `AssistantTextBlock`，**无容器**（`AssistantTextBlock.kt:53`）
- 长按菜单：`复制全部`（`:46`）
- 这一段要同时覆盖：标题、无序列表、行内代码、围栏代码块

```markdown
## 先定位排序来源

列表是「先分组、再排序」：

- 分组用的是 `it.cwd`，所以一个目录一段
- 组间排序用的是组内最新的 `lastActivityAt`
- `按名称` 时才改用 `displayName.lowercase()`

改之前先确认一下组内行序是谁给的：

```kotlin
val groups = visible
    .groupBy { it.cwd }
    .toList()
    .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
```
```
<!-- 示例（正文；引用的 Kotlin 片段是真实代码 SessionsScreen.kt:191-194，缩进为排版调整） -->

#### 块 05 · `tool-execution` / `read`
- 类型：`ToolCall(toolName="read")` → `ReadBlock`
- 标题行：等宽 `read` + 路径（+ `:起始-结束` 行范围，`ToolOutputParse.kt:717-722`）+ 状态符号
- 页脚：`成功 · 132ms · 13 行`（`toolFooterText`，`ToolBlockChrome.kt:273-281`）
- 展开后「还有 N 行未显示」/「展开全部（还有 N 行）」（`ReadBlock.kt:86,91`）

```
title: read
subject: app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt:119-131
status: 成功 ✓
footer: 成功 · 132ms · 13 行
body（前 10 行，等宽带行号）:
  119  val visible = remember(sessions, query, byName, namedOnly) {
  120      sessions
  121          .filter { summary ->
  ...
```
<!-- 示例（正文；行号与代码形状取自真实文件） -->

#### 块 06 · `tool-execution` / `grep`（多命中）
- 类型：`ToolCall(toolName="grep")` → `GrepBlock`
- 标题 = `grep` + `/pattern/ in path`，可带 ` (glob)` 与 ` limit N`（`ToolOutputParse.kt:733-737`）
- 页脚：`成功 · 7 处 · 3 个文件 · 88ms`（`GrepBlock.kt:72-80`）
- 正文按文件分组，每组标题右边是 `N 处`（`GrepBlock.kt:134-143`）

```
title: grep
subject: /sortedBy/ in app/src/main/kotlin
status: 成功 ✓
footer: 成功 · 7 处 · 3 个文件 · 88ms

app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt         3 处
  129: if (byName) list.sortedBy { it.displayName.lowercase() } else list
  194: .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt    3 处
app/src/main/kotlin/app/pi/ui/chat/SessionTreeScreen.kt         1 处
```
<!-- 示例（命中内容为编造，路径为真实文件） -->

#### 块 07 · `assistant-text`（短句）

```markdown
组内没排过，难怪「已命名」那几条按文件名落到了后面。我把组内也按时间倒序补上。
```
<!-- 示例 -->

#### 块 08 · `tool-execution` / `edit`
- 类型：`ToolCall(toolName="edit")` → `EditBlock`
- 标题 = `edit` + 文件路径；**卡片本身不画 diff**（diff 是紧随其后的独立块，见块 09）
- 失败时展开显示结果文本（`EditBlock.kt:56`），页脚才可展开（`:58`）

```
title: edit
subject: app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt
status: 成功 ✓
footer: 成功 · 214ms · 2 行
```
<!-- 示例（数值为编造；标题与状态词真实） -->

#### 块 09 · `tool-diff`（真实感 unified diff）
- 类型：`ToolDiff` → `DiffBlock`，key 为 `<toolCallId>-diff`，**跟在 edit 卡之后**（`Transcript.kt:1405-1440`）
- 卡头：等宽 `edit`（`item.toolName`，缺失时 `diff`）+ 路径（缺失时 `未命名文件`）+ `+6` `−5`
- 折叠/统计：`新增 6 行 · 删除 5 行`（`DiffBlock.kt:79`）、上下文折叠 `… 12 行未变`（`:112`）、超 200 行折叠 `差异过长，仅显示前 200 行`（`:127`）

```diff
--- a/app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt
+++ b/app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt
@@ -188,7 +188,11 @@ fun SessionsScreen(
             } else {
                 val groups = visible
                     .groupBy { it.cwd }
                     .toList()
-                    .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
+                    .map { (cwd, rows) -> cwd to rows.sortedByDescending { it.lastActivityAt } }
+                    .sortedByDescending { (_, rows) ->
+                        rows.firstOrNull()?.lastActivityAt ?: 0L
+                    }
@@ -330,2 +334,3 @@ private fun SessionRow(
             Spacer(Modifier.width(12.dp))
-        Text(relativeTime(summary.lastActivityAt), style = PiTheme.text.meta)
+        Text(relativeTime(summary.lastActivityAt), style = PiTheme.text.meta, maxLines = 1)
```
<!-- 示例（diff 正文为编造；`groupBy`/`sortedByDescending` 两种写法来自真实代码 SessionsScreen.kt:192-194） -->

#### 块 10 · `tool-execution` / `write`
- 类型：`ToolCall(toolName="write")` → `WriteBlock`
- 标题 = `write` + 路径；展开后有「展开全部（还有 N 行，共 M 行）」（`WriteBlock.kt:87`）
- 参数里没内容时的真实文案：`参数里没有文件内容。`（`ToolBlockChrome.kt:288`）

```
title: write
subject: app/src/test/kotlin/app/pi/ui/screens/SessionGroupOrderTest.kt
status: 成功 ✓
footer: 成功 · 96ms · 38 行
```
<!-- 示例（路径与数值为编造） -->

#### 块 11 · `tool-execution` / `bash`（真实命令 + 多行输出）
- 类型：`ToolCall(toolName="bash")` → `ShellBlock`
- 标题 = `$ ` + 命令（多行命令只取首行），带 ` (timeout 120s)` 时追加后缀（`ShellBlock.kt:124-129`）
- 页脚：`成功 · 退出码 0 · 耗时 6.4 秒 · 42 行`（`ShellBlock.kt:138-151`）
- 折叠只显示**尾部 5 行**（`SHELL_PREVIEW_LINES`，`ShellBlock.kt:153`），上方未显示的写 `上方还有 N 行未显示`

```
title: $
subject: tools/run-app-pure-checks.sh (timeout 120s)
status: 成功 ✓
footer: 成功 · 退出码 0 · 耗时 6.4 秒 · 42 行
output（尾部 5 行）:
  [settings-audit] 68 项设置，全部有读取方
  [prose-audit] 用户可见文案 0 处内部路径
  [nested-comments] ok
  [highlight] ok
  PASS
```
<!-- 示例（脚本名与「68 项设置」为真实事实：tools/run-app-pure-checks.sh 存在，settings.size 见 PiSettingsRegistry.kt:240；输出文字为编造） -->

#### 块 12 · `tool-execution` / `bash`（**长任务进度态**）
- 同一个 `ShellBlock`，只是 `status = Pending`
- 状态词 `运行中` + 符号 `…`（`ToolBlockChrome.kt:59,66`）
- 计时是 **`已运行 12.3 秒`**（不是「耗时」，`ToolOutputParse.kt:251`）
- 无输出时页脚不写 `无输出`（pending 分支排除，`ShellBlock.kt:148`）

```
title: $
subject: ./gradlew :app:compileDebugKotlin
status: 运行中 …
footer: 运行中 · 已运行 12.3 秒 · 18 行
output: >
  > Task :app:compileDebugKotlin
  （光标在最后一行闪烁）
```
<!-- 示例（gradle 任务名为真实任务名形状） -->

#### 块 13 · `thinking-block`（展开态，与块 03 组成折叠/展开对照）
- 展开后：`thinkingText` 色 + 斜体 + 行高 22
- 折叠入口文案由 `Modifier.toggleContent` 提供：`展开` / `收起`（`BlockChrome.kt:274`、`ToolBlockChrome.kt:138`）

```
headline: 思考 21s
level 标签: 高
expanded: true
body（斜体）: |
  组内排序改完还要看一点：跨天的时候 relativeTime 会变，
  行内的「刚刚」可能和分组顺序不一致，但那不是排序问题。
```
<!-- 示例（正文） -->

#### 块 14 · `tool-execution` / `find`
- 类型：`ToolCall(toolName="find")` → `PathListBlock`（`FindBlock`，`BlockRenderer.kt:107`）
- 标题 = `find` + `<pattern> in <path>`，可带 ` (limit N)`（`ToolOutputParse.kt:746-749`）
- 页脚带 `12 项`；展开 `展开全部（还有 N 项）`（`PathListBlock.kt:127,166`）

```
title: find
subject: *.kt in app/src
status: 成功 ✓
footer: 成功 · 12 项 · 150ms
```
<!-- 示例（数值为编造） -->

#### 块 15 · `tool-execution` / `ls`
- 类型：`ToolCall(toolName="ls")` → `PathListBlock`（`LsBlock`）
- 标题 = `ls` + 路径（空参数的 fallback 是 `.`）
- 空目录的真实文案：`(empty directory)` 由解析层识别并翻成「N 项 = 0」形态（`ToolOutputParse.kt:534`）

```
title: ls
subject: app/src/main/kotlin/app/pi/ui
status: 成功 ✓
footer: 成功 · 8 项 · 40ms
```
<!-- 示例（数值为编造） -->

#### 块 16 · `tool-execution` / `bash`（**失败卡**）
- `status = Error` → 容器 `toolErrorBg`，状态词 `失败` + 符号 `✗`（`ToolBlockChrome.kt:61,68`）
- 页脚里的退出码写 `退出码 2`（`ShellBlock.kt:145`）

```
title: $
subject: rg -n "TODO" app/src/main/kotlin
status: 失败 ✗
footer: 失败 · 退出码 2 · 耗时 0.4 秒 · 0 行
output: |
  rg: app/src/main/kotlin: 没有那个文件或目录 (os error 2)
```
<!-- 示例（输出为编造；错误输出文本真实来自 rg 的形状） -->

#### 块 17 · `error-text`
- 类型：`ErrorText` → `ErrorBlock`
- 真实文案：消息为空时 `出错了`；展开按钮 `详情`（`ErrorBlock.kt:54,69`）
- 视觉：错误色卡 + 左侧 3dp error 条；详情是等宽文本（`ErrorBlock.kt:85-86`）

```
message: 引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。   ← ‹源› PiSessionViewModel.kt:1885（真实 App 文案，可直接用）
detail（折叠）: get_entries: session file not found
```
> 设计师注意：这类句子在源码里是有真身的（`fail(...)` 一共 9 条，见 §7）；上面这一条就是原话。

#### 块 18 · `notice`（App chrome 行）
- 类型：`Notice` → `NoticeBlock`；前缀 `·`，右侧时间戳（`NoticeBlock.kt:34,47`）
- 三种 tone：Info = `muted`、Warning = `warning`、Error = `error`（`NoticeBlock.kt:27-31`）

```
tone: Info
text: 上下文已压缩（12,340 → 8,100 tokens）      ← ‹源› PiSessionViewModel.kt:2334-2335 的真实句式
time: 14:26
```

#### 块 19 · `compaction`
- 类型：`CompactionMarker` → `CompactionBlock`
- 真实标签：`正在压缩上下文…` / `上下文已压缩` / `压缩已中止` / `压缩失败`（`CompactionBlock.kt:50-53`）
- 副文案：运行中 `pi 正在总结更早的对话`；结束 `释放 42k tokens`；否则 `原因：<reason>`（`:60-62`）
- 计费行（仅 `showCacheMissNotices` 打开时）：`Compaction: 42k tokens billed (~$0.03)`（英文标签是 pi 原话，`PiCommon.kt:199-216`）

```
status: 已结束
label: 上下文已压缩
caption: 释放 42k tokens
billed（可选）: Compaction: 42k tokens billed (~$0.03)
summary（展开后 markdown）: 前 3 轮已经把排序逻辑定位到 SessionsScreen…
```
<!-- 示例（caption 数值与摘要为编造） -->

#### 块 20 · `branch-summary`
- 类型：`BranchSummary` → `BranchSummaryBlock`
- 真实标签：`分支摘要` + 分支 id；点击标签 `跳转分支`，无目标时 `展开摘要`（`BranchSummaryBlock.kt:58,67,74`）
- 空摘要：`（无摘要）`（`:105`）

```
label: 分支摘要
branchId: 3f1c9a7e
clickLabel: 跳转分支
summary: 上一轮改过 grep 卡的分组标题，这次从那条消息分出来。
```
<!-- 示例（branchId 与摘要为编造） -->

#### 块 21 · `model-change`
- 类型：`ModelChange` → `ModelChangeBlock`
- 真实文案：`模型切换 →` + 模型 id + provider；无名字时 `未知模型`（`ModelChangeBlock.kt:33,50`）

```
prefix: 模型切换 →
modelId: deepseek/deepseek-chat
provider: deepseek
time: 14:31
```
<!-- 示例（模型 id 为编造；provider id 来自真实 providerOptions，PiSettingsRegistry.kt:170） -->

#### 块 22 · `skill-invocation`
- 类型：`SkillInvocation` → `SkillInvocationBlock`
- 真实标签：`技能` + `/skill:<name>`；空正文 `（技能没有正文）`；空名字 `未知技能`（`SkillInvocationBlock.kt:79,85,118`）
- 展开显示 SKILL.md 正文（markdown）

```
label: 技能
invocation: /skill:review
expanded: false
body: |
  # Review
  按仓库规则逐条核对改动，输出「问题 / 位置 / 证据」三列。
```
<!-- 示例（技能名与正文为编造） -->

#### 块 23 · `hook-message`
- 类型：`HookMessage` → `HookMessageBlock`
- 顶部等宽小标签 = `customType`；缺失时字面量 `extension`（`HookMessageBlock.kt:58`）
- 空 markdown：`（空消息）`（`:74`）

```
customType: extension
markdown: |
  已记录本次改动：1 个文件，+6 −5。
```
<!-- 示例 -->

#### 块 24 · `message-images`
- 由 `ImageGridBlock` 渲染，出现在用户消息内或工具结果卡里
- 真实标签：无障碍 `第 N 张图片`；解码失败时 `图片 N` + mime；溢出 `+N`（`ImageGridBlock.kt:132,144,149,159`）
- 布局：1 图撑满 / 2–4 图 2×2 / >4 图网格 + `+N`

```
images: 3 张（image/png）
overflowLabel: +1
```
<!-- 示例（数量为编造） -->

#### 块 25 · `tool-execution`（通用回退卡 `ToolCallBlock`）
- 用于 pi 没有专属渲染器的工具，或结果里带图片的调用（`BlockRenderer.kt:99-112`）
- 真实文案：`输出超过 200 KB，正文未展开。`、`展开全部（共 N 行）`（`ToolCallBlock.kt:141,156`）
- 结果被自身扫描预算截断时：`输出过长，只解析了前面一部分。`（`ToolBlockChrome.kt:291`）

```
title: <toolName>              ← 例如 pi 扩展注册的工具名，逐字显示
subject: <argsSummary>         ← 单行参数摘要（ToolCall.argsSummary）
status: 成功 ✓
footer: 成功 · 2 行
```
<!-- 示例 -->

#### 块 26 · 扩展审批弹窗（权限 / 确认请求）
- 来源：扩展通过 RPC 发 `confirm` 请求 → `ExtensionUiHost` → `ExtensionConfirmDialog`
- 真实按钮：`允许` / `拒绝`（`ExtensionDialogs.kt:157,160`）；`select`/`input`/`editor` 的确定键是 `确定`，取消键 `取消`（`:124,201,204,247,250`）
- 标题行会拼倒计时秒数：`<title> (5s)` 形态由 `DialogHeading` 组装（`ExtensionDialogs.kt:269-277`）
- 排队数：`还有 N 个扩展对话框在排队`（`ExtensionDialogs.kt:279`）
- 超时说明：`超时后自动拒绝，晚到的回复会被丢弃。`（确认类）／`超时后自动取消，晚到的回复会被丢弃。`（其它类）（`ExtensionDialogs.kt:316` + `ExtensionUi.kt:53`）

```
标题: 允许写入 app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt? (5s)
正文: <扩展给的 message>
按钮: 允许      拒绝
页脚: 超时后自动拒绝，晚到的回复会被丢弃。
```
<!-- 示例（标题与 message 由扩展提供，故此处编造；四个按钮/提示字符串均为源码原话） -->

#### 块 27 · 队列 chips（严格说属于输入区上沿，不在对话流里）
- 真实文案：`穿插 N` / `后续 N`，右侧动作 `收回并编辑`（`ChatScreen.kt:1559-1561,1576`）
- 语义提示：`收回并编辑` 会把全部排队消息放回输入框，**当前回合继续跑**（`ChatScreen.kt:1547-1552`）

```
chips: 穿插 2   后续 1              收回并编辑
```

#### 块 28 · `assistant-text`（收尾，含围栏代码块）

```markdown
两处都改完了：

1. 组内按 `lastActivityAt` 倒序
2. 组间改为用组内第一条的时间，空组回退 `0L`

```kotlin
.sortedByDescending { (_, rows) ->
    rows.firstOrNull()?.lastActivityAt ?: 0L
}
```

下一步建议把「已命名」那一栏的排序也写进测试，免得再回归。
```
<!-- 示例 -->

### 2.2 进行中 / 流式态的视觉清单（画交互稿直接用）

| 位置 | 进行中的真实表现 | 源 |
|---|---|---|
| AppBar 状态行 | `工作中`（流式）；有具体 RPC 时显示动作词如 `读取会话树` | `PiSessionViewModel.kt:3054-3055` |
| 助手正文 | 无容器文字持续追加，末尾一个呼吸光标 | `AssistantTextBlock.kt`（`streaming` 字段，`Transcript.kt:50`） |
| 思考块 | 折叠态标题变 `思考中…`；结束后替换为 `思考 8s` | `ThinkingBlockBlock.kt:43-45` |
| 工具卡（通用） | 状态 `运行中` + 符号 `…`，容器用 pending 底色 | `ToolBlockChrome.kt:45,59,66` |
| Shell 卡 | 页脚 `运行中 · 已运行 12.3 秒 · 18 行`（**只有 pending 用「已运行」**） | `ShellBlock.kt:138-151`、`ToolOutputParse.kt:251` |
| 压缩块 | 标签 `正在压缩上下文…`，副文案 `pi 正在总结更早的对话` | `CompactionBlock.kt:50,60` |
| 扩展等待答复 | 确认/选择框标题带倒计时 `<title> (5s)`；后台还有排队时 `还有 N 个扩展对话框在排队` | `ExtensionDialogs.kt:269-279` |
| 队列 | 输入区上沿 chips `穿插 2` / `后续 1` + 动作 `收回并编辑` | `ChatScreen.kt:1559-1576` |
| 发送按钮 | 流式中变成停止（无障碍名 `停止`），否则发送（`发送`） | `ChatScreen.kt:1700` |
| 长列表 | 顶部 `加载更早的 N 条`；用户上滑后右下角出现 `回到最新 · N` | `ChatScreen.kt:963,1072` |

---

## 3. 输入区（composer）

源：`ui/screens/ChatScreen.kt:1660-1800`、`ui/chat/SlashPalette.kt`、`ui/chat/PiSlashCommands.kt`、`ui/chat/MentionPalette.kt`、`ui/chat/BashPanel.kt`。

### 3.1 真实元素清单

| 元素 | 真实文案 / 形态 | 源 |
|---|---|---|
| 输入框 placeholder | `输入消息，/ 选命令，! 直接跑命令，@ 提及文件` | `ChatScreen.kt:1683` |
| 边框色 | 有状态含义：`!` 开头 = `bashMode` 色；否则 = 当前思考等级色 | `ChatScreen.kt:1694-1699` |
| 尾部按钮 | 流式中为停止（无障碍 `停止`），否则发送（无障碍 `发送`） | `ChatScreen.kt:1700` |
| 按键提示条 | `/` `!` `!!` `@` `图片` `编辑器`，全部等宽小字 | `ChatScreen.kt:1712-1726` |
| 流式时多出的 chip | `后续`（队列到回合结束再投递；不可用时变灰底板） | `ChatScreen.kt:1734-1756` |
| 右下角文字入口 | `终端` | `ChatScreen.kt:1762-1770` |
| 思考等级 chip | `◐ <中文等级>`，点击循环；等级中文见 3.3 | `ChatScreen.kt:1777-1786` |
| 附件缩略图 | 无障碍 `待发送的第 N 张图片，点击移除`；点击标签 `移除第 N 张图片` | `ChatScreen.kt:1865,1874` |
| 附件说明 | `随消息一起发送` | `ChatScreen.kt:1209` |
| 附件失败 | `只能附加图片：所选文件的类型是 <mime>。请回到选择器换一张图片。` / `读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。` / `图片太大，上限是 10 MB；它要整段随消息发送。请先压缩或裁剪后再试。` | `ChatScreen.kt:299,324,329-331` |
| `/` 弹层 | 等宽命令名 + 参数提示（如 `<provider/model>`）+ 说明 + 来源徽标 | `SlashPalette.kt:88-130` |
| `/` 弹层空态 | `没有可用命令` / `没有匹配「<query>」的命令` | `SlashPalette.kt:60` |
| 来源徽标 | `内置` `扩展` `模板` `技能`；带来源时写成 `扩展·p`、`模板·u:npm:pi-skills` | `PiSlashCommands.kt:89-101` / `SlashPalette.kt:147` |
| 「本应用没有入口」注 | 说明后追加 ` · 本应用没有入口`；有落地页时 ` · 本应用：设置 → …` | `SlashPalette.kt:111-112` |
| `@` 弹层 | 一行等宽 `label` + 一行 `description`；**无候选时不画**（不留空弹层） | `MentionPalette.kt:66-88` |
| `!` / `!!` 面板 | 标题 `$ <command>`；关按钮 `关闭输出`，停止按钮 `停止命令`；状态行 `运行中 · 进入上下文` / `成功 · 不进上下文` / `已取消 · 进入上下文` / `退出码 1 · 进入上下文` / `已结束 · 输出被截断，完整输出：<path>` | `BashPanel.kt:64,73,81,64-134` |
| 导出投递行 | 标题 `会话已导出` + 文件名 + `保存到 Download` / `分享` / `关闭` | `ChatScreen.kt:1617-1635` |
| 队列行 | 见块 27 | `ChatScreen.kt:1555-1580` |
| 「回到最新」悬浮 | `回到最新` / `回到最新 · <N>` | `ChatScreen.kt:1072` |
| 加载更早 | `加载更早的 <N> 条` | `ChatScreen.kt:963` |

### 3.2 斜杠命令全表（23 条内置 + 运行期扩展/模板/技能）

顺序是 pi 自己的顺序，**不要重排**（`PiSlashCommands.kt:150-158`）。`<...>` 是真实 `argumentHint`。
`本应用：…` 是源码里给该行的落地页说明（`appLanding`）。

```
/settings                打开设置菜单
/model <provider/model>  选择模型
/tree                    浏览会话树，从某条消息分叉
/thinking <level>        设置思考等级
/scoped-models           设置循环切换的模型范围
/export                  导出会话：默认 HTML，路径以 .jsonl 结尾时写 JSONL
/import                  从 JSONL 文件导入并恢复会话
/share                   将会话分享为私密 GitHub gist        · 本应用没有入口
/copy                    复制最后一条模型消息
/name                    设置会话显示名称
/session                 查看会话信息与统计
/changelog               查看更新日志                      · 本应用没有入口
/hotkeys                 查看全部快捷键                     · 本应用没有入口
/fork                    从某条历史消息创建分支
/clone                   在当前节点复制整个会话
/trust                   保存项目信任决定                   · 本应用：设置 → 扩展 → 扩展包与项目信任
/login <provider>        配置 provider 认证（本应用只支持 API Key） · 本应用：设置 → 模型与推理 → 凭证
/logout                  移除 provider 认证                · 本应用：设置 → 模型与推理 → 凭证
/new                     新建会话
/compact                 手动压缩会话上下文
/resume                  切换到另一个会话
/reload                  重载扩展、技能、模板、主题与上下文文件  · 本应用：设置 → 运行时与诊断 → 进程 → 重启引擎
/quit                    退出 pi                          · 本应用没有入口
```
源逐行：`PiSlashCommands.kt:161-238`；落地页文案由 `SlashPalette.kt:111` 拼成 `本应用：<landing>`。
点击行为差异（做交互态时用）：**有 `argumentHint` 的行只把命令名填进输入框**（pi 的 Tab 行为），其余点击即执行（`ChatScreen.kt:1155-1172`）。
未知命令的提示：`「/<name>」不是已安装的扩展命令、模板或技能；如果要把它作为消息发给模型，请去掉开头的 /`（`PiSessionViewModel.kt:3032-3033`）。
无入口命令的提示：`/<name> 只在 pi 的原版 TUI 里，本应用没有对应入口。` / `/<name> 在本应用里：<落地页>。`（`PiSessionViewModel.kt:3012-3014`）。

### 3.3 思考等级中文（`ChatSheets.kt:659-667`）

```
off → 关闭   minimal → 极简   low → 低   medium → 中
high → 高    xhigh → 很高     max → 最高   （未知枚举原样显示）
```
chip 形态：`◐ 中`。弹层标题 `思考等级`，副标题 `可选等级由当前模型决定。`，空态 `当前模型不支持思考等级。`（`ChatSheets.kt:208-213,222`）。

### 3.4 其他输入区相关弹层（真实标题）

| 弹层 | 标题 / 说明 | 源 |
|---|---|---|
| 模型选择 | `选择模型`；筛选 placeholder `按 provider / 模型名筛选`；空态 `没有可用模型。刚配置好的厂商要重启引擎后才会出现在这里；已经保存的模型可以在 设置 → 模型 里看到它们的状态。`；按钮 `重新读取`；底部一行 `这个列表来自运行中的引擎。导入过的模型在 设置 → 模型 里能看到它们的状态。` | `ChatSheets.kt:80-127` |
| 会话与队列 | `会话与队列`；分区 `队列模式`；两行 `穿插消息（steer）`（`本回合进行中插入，下一次回答之前生效`）、`后续消息（follow up）`（`整个回合结束后才投递`），选项 `逐条` / `全部` | `ChatSheets.kt:300-330` |
| 会话与队列（开关段） | `自动压缩` / `接近上下文上限时自动摘要`；`自动重试` / `可重试的模型错误按退避自动重试`；`取消重试` / `结束正在等待的退避延迟`；`压缩上下文` / `先中止当前回合，再生成一次摘要` | `ChatSheets.kt:325-345` |
| 不可渲染的扩展 | 分区 `本应用显示不了的扩展（N）` + `下面这些扩展用到了本应用不支持的自绘界面，它们在对话页不会显示内容。` | `ChatSheets.kt:405-411` |
| 会话信息 | `会话信息`；行 `会话名称`（默认 `未命名`）`会话 ID`（默认 `—`）`会话文件`（默认 `（尚未落盘）`）`消息`（`N 用户 · N 模型 · N 总计`）`工具调用`（`N 次调用 · N 条结果`）`Token`（`输入 … · 输出 … · 缓存读 … · 缓存写 … · 合计 …`）`上下文占用`（`52%（104000 / 200000）`）`累计费用`（`$0.42`）；加载中 `正在读取…`；无数据 `这次会话还没有统计信息。` | `ChatSheets.kt:487-525` |
| 从历史消息分支 | `从哪条消息分支`；说明 `会在选中的消息处创建一个新会话。`；空态 `没有可分叉的用户消息。` | `ChatSheets.kt:564-580` |

---

## 4. 设置首页 + 分组页

源：`ui/settings/SettingsHome.kt`、`SettingsGroupScreen.kt`、`PiSettingsRegistry.kt`、`PiSettingsJson.kt`、`PiCommon.kt`。

### 4.1 设置首页（L0）真实结构与顺序

```
AppBar: 设置                        右侧图标无障碍名 搜索设置        ‹源› SettingsHome.kt:79,82
① 搜索入口卡片:  「搜索设置」        右侧显示「68 项」                ‹源› :188,194（数字来自 settings.size）
② 当前模型快捷卡: 「当前模型」 副行「<默认模型> · ◐ <思考等级>」     ‹源› :228,233
③ 分区「设备」   → 行「设备能力」  副行「4/6 组能力可用」 右侧「查看」  ‹源› :104,387 / DeviceCapabilityScreen.kt:365,385,392
④ 分区「扩展」   → 行「扩展包与项目信任」 右侧「打开」               ‹源› :119 / PiPackagesHost.kt:76-84
⑤ 分区「关于」   → 行「开源许可」 副行「App 内分发的第三方组件、许可证原文，以及 GPL / LGPL 程序的源代码获取方式」 右侧「打开」  ‹源› :131 / LicensesScreen.kt:100-113
⑥ 分区「全部设置」→ 12 个分组行
⑦ 页脚说明: 「共 68 项设置。搜索同时匹配标题、说明与字段名，输入 reserveTokens 或 /compact 都能直达。」  ‹源› :149-150
```

`SettingsHome.kt:104` 的分区标题是字面量 `设备`；「扩展」「关于」「全部设置」同形。左上角返回等 L1 细节见 `SettingsGroupScreen.kt:117`（返回图标无障碍名 `返回`）。

### 4.2 12 个分组（真实顺序 + 标题 + 首页副标题格式）

| # | 分组 id | 标题 | 首页副行（真实格式） | 源 |
|---|---|---|---|---|
| 1 | `model` | `模型与推理` | `默认 <模型> · ◐ <等级>`；未配置时 `未配置模型，启动时再选` | `:1357-1363` |
| 2 | `messages` | `消息与网络` | `穿插：<穿插模式> · 后续：<后续模式>` | `:1364-1366` |
| 3 | `compaction` | `上下文与压缩` | `<自动压缩开/关> · <保留token> / <保留最近token>` | `:1367-1372` |
| 4 | `retry` | `重试与网络` | `<自动重试开/关> · <最大重试次数> 次` | `:1373-1376` |
| 5 | `tools` | `工具` | `默认 read·bash·edit·write`；有配置时 `<N> 个已启用` | `:1377-1381` |
| 6 | `sessions` | `会话` | `由本应用固定（与 pi 共用）· 续接：<开/关>` | `:1382-1387` |
| 7 | `resources` | `扩展与资源` | `<N> 个扩展 · <N> 个资源包` | `:1388-1392` |
| 8 | `appearance` | `外观` | `主题 <主题值>` | `:1393-1396` |
| 9 | `terminal` | `终端与 Shell` | `<shell 路径> · 等宽 <字号>`；未显式设置时 `默认 /bin/bash · 等宽 <字号>` | `:1397-1406` |
| 10 | `security` | `安全与信任` | `项目信任：<询问/总是信任/从不信任>` | `:1407-1409` |
| 11 | `runtime` | `运行时与诊断` | `保活：<开/关>` | `:1410-1418` |
| 12 | `about` | `隐私与关于` | `遥测：<开/关>` | `:1419-1421` |

### 4.3 行类型与「当前值显示格式」（全部来自代码）

行类型 6 种：`Switch` `Value` `Number` `Text` `List` `Action`（`PiSettingsRegistry.kt:57`）。
值的显示规则（`PiSettingsJson.kt:133-168`）：

| 行类型 | 尾部显示 | 交互 |
|---|---|---|
| Switch | 开关控件（`开`/`关` 是 `display()` 的文字形态，卡片上用 Switch） | 就地切换写盘（`SettingsGroupScreen.kt:148-155`） |
| Value | 选项中文标签；未设置 `未设置`；不在选项表里就原样显示 | 打开选择/输入 sheet |
| Number | `<数字> <unit>`；未设置 `未设置` | 打开数字编辑器 |
| Text | 原文；空/缺省 `未设置` | 打开文本编辑器 |
| List | `<N> 项`；0 时显示该行自己的 `emptyListLabel` | 打开清单编辑器 |
| Action | 尾部为空（标题本身是动作，`Action` 用 `primary` 色，危险行用 `error` 色） | 弹确认框 / 直接进页面 |

生效时机徽标（`PiCommon.kt:373-380`）：`需重载`（tertiary）、`新会话`（onSurfaceVariant）、`需重启引擎`（error）、`需重启`（error）；`Immediate` 不显示徽标。

### 4.4 代表行

> 任务书允许「只列最代表性的 ~40 行」；这里给到了 12 个分组共 **67 行**（68 项里只剩 1 项未列）。
> 多出来的部分是为了让设计稿能画出「分组页很长」这件事本身——每行的**格式**都是代码事实，可以直接复制粘贴。

格式：`分组 → 标题（key）｜类型｜尾部值示例｜徽标｜源`

```
模型与推理
 已导入的模型（app.models.inventory）            Action   尾部空                          —        :249
 默认厂商（defaultProvider）                     Value    DeepSeek / 未设置               新会话    :258
 默认模型（defaultModel）                        Value    deepseek/deepseek-chat / 未设置  新会话    :270
 默认思考等级（defaultThinkingLevel）            Value    中 / 未设置                     新会话    :281
 逐模型思考等级（modelThinkingLevels）           List     2 项 / 无覆盖                   新会话    :292
 思考预算（thinkingBudgets）                     List     未设置                          新会话    :305
 隐藏思考块（hideThinkingBlock）                 Switch   开/关                            —        :318
 缓存未命中提示（showCacheMissNotices）          Switch   开/关                            —        :328
 循环模型（enabledModels）                       List     全部模型 / 3 项                  需重启引擎 :338
 API Key（app.credentials.apiKey）               Action   尾部空                           —        :357
 订阅登录（app.credentials.oauth）               Action   尾部空                           —        :369
 本地模型（llama.cpp）（app.localModels.manage） Action   尾部空                           —        :395
消息与网络
 穿插模式（steeringMode）                        Value    逐条                             —        :408
 后续模式（followUpMode）                        Value    全部                             —        :419
 传输方式（transport）                           Value    自动                             新会话    :431
 HTTP 空闲超时（httpIdleTimeoutMs）              Number   30000 ms                         需重启引擎 :443
 WebSocket 连接超时（websocketConnectTimeoutMs） Number   10000 ms                         新会话    :458
 HTTP 代理（httpProxy）                          Text     未设置                           需重启引擎 :473
上下文与压缩
 自动压缩（compaction.enabled）                  Switch   开/关                            —        :488
 保留 token（compaction.reserveTokens）          Number   400000                           新会话    :498
 保留最近 token（compaction.keepRecentTokens）   Number   20000                            新会话    :513
 逐模型覆盖（compaction.modelOverrides）         List     无覆盖                           新会话    :528
 分支摘要保留 token（branchSummary.reserveTokens）Number  未设置                           新会话    :541
 立即压缩（app.compaction.runNow）               Action   尾部空                           —        :561
重试与网络
 自动重试（retry.enabled）                       Switch   开/关                            —        :575
 最大重试次数（retry.maxRetries）                Number   3                                新会话    :585
 基础延迟（retry.baseDelayMs）                   Number   1000                             新会话    :600
 最大重试延迟（retry.maxAgentDelayMs）           Number   未设置                           新会话    :615
 Provider 超时（retry.provider.timeoutMs）       Number   未设置                           新会话    :630
 Provider 重试次数（retry.provider.maxRetries）  Number   未设置                           新会话    :644
工具
 内建工具（defaultTools）                        List     默认 read/bash/edit/write         新会话    :678
 工具输出默认展开（app.tools.expandByDefault）   Switch   开/关                            —        :701
会话
 启动续接最近会话（app.sessions.resumeLast）     Switch   开/关                            需重启    :734
扩展与资源
 扩展（extensions）                              List     0 项 / 空                        需重启引擎 :749
 资源包（packages）                              List     0 项 / 空                        需重启引擎 :772
 技能（skills）                                  List     空                               需重启引擎 :787
 提示模板（prompts）                             List     空                               需重启引擎 :800
 主题（themes）                                  List     空                               需重启引擎 :813
 技能命令（enableSkillCommands）                 Switch   开/关                            —        :826
外观
 主题（theme）                                   Value    dark / light/dark                需重载    :860
 字号微调（app.appearance.fontScaleDelta）       Number   0                                 —        :877
 消息密度（app.appearance.messageDensity）       Value   舒适/紧凑/宽松（按代码分支）        —        :891
 显示时间戳（app.appearance.showTimestamps）     Switch   开/关                            —        :906
 思考块默认折叠（app.appearance.thinkingCollapsedByDefault） Switch 开/关                    —        :916
 图片自动缩放（images.autoResize）               Switch   开/关                            新会话    :926
 屏蔽图片（images.blockImages）                  Switch   开/关                            新会话    :937
终端与 Shell
 Shell 路径（shellPath）                         Text     默认 /bin/bash                   新会话    :952
 命令前缀（shellCommandPrefix）                  Text     未设置                           新会话    :964
 npm 命令（npmCommand）                          List     默认 npm                          新会话    :976
 终端字号（app.terminal.fontSize）               Number   13                                需重载    :990
 键盘按键条（app.terminal.keyBar）               List     预设 11 个键                      需重载    :1006
安全与信任
 项目信任策略（defaultProjectTrust）             Value    询问 / 总是信任 / 从不信任        需重启引擎 :1022
 紧急停止（app.security.emergencyStop）          Action   尾部空（危险色）                  —        :1040
运行时与诊断
 pi 版本（app.runtime.piVersion）                Text     只读                              —        :1065
 Node 版本（app.runtime.nodeVersion）            Text     只读                              —        :1089
 运行时占用（app.runtime.rootfsUsage）           Text     只读                              —        :1100
 引擎启动耗时（app.runtime.engineStartup）       Text     只读，默认文案 未读取               —        :1111
 离线模式（app.runtime.offline）                 Switch   开/关                            需重启引擎 :1141
 自定义系统提示（app.runtime.systemPrompt）      Text     未设置                           需重启引擎 :1153
 追加系统提示（app.runtime.appendSystemPrompt）  Text     未设置（depth 2）                 需重启引擎 :1166
 缓存保留策略（app.runtime.cacheRetention）      Value    未设置                           需重启引擎 :1182
 不加载上下文文件（app.runtime.noContextFiles）  Switch   开/关                            需重启引擎 :1198
 重启引擎（app.runtime.restartEngine）           Action   尾部空                           —        :1210
 后台保活（app.runtime.keepAlive）               Switch   开/关                            需重启    :1228
 唤醒锁状态（app.runtime.wakeLock）              Text     只读                              —        :1239
 导出诊断报告（app.runtime.diagnostics）         Action   尾部空                           —        :1260
隐私与关于
 安装遥测（enableInstallTelemetry）              Switch   开/关                            新会话    :1275
```

> 其中 4 行的默认值/副文案我按代码里的 `defaultValue` 填了示例值（`30000 ms`、`10000 ms`、`400000`、`20000`、`3`、`1000`、`13`、`11 个键`）；**显示格式**是代码事实（`PiSettingsJson.kt:150`），**具体数字**请以 `defaultValue` 声明为准再核一遍。
> 消息密度三个分支的真实取值：代码读 `app.appearance.messageDensity`，只在 `"compact"` / `"cozy"` / 其它（默认 `comfortable`）之间分支（`PiSessionViewModel.kt:214,610-611`、`ChatScreen.kt:930-935`）。

### 4.5 分组页（L1）的其他真实文案

| 场景 | 文案 | 源 |
|---|---|---|
| 返回图标 | `返回` | `SettingsGroupScreen.kt:117` |
| Action 确认框（有 dispatcher） | 主按钮 `执行`；危险行 `确认执行`；次按钮 `取消` | `SettingsGroupScreen.kt:242,248` |
| Action 确认框（无 dispatcher，预览态） | 正文末尾追加 `这个入口当前不可用。`；单按钮 `知道了` | `SettingsGroupScreen.kt:227,235` |
| 生效说明弹窗 | 标题 = 徽标名（`需要重载` / `需要新会话` / `需要重启引擎` / `需要重启 App`）；正文 `「<设置标题>」<解释>`；按钮随类型为 `立即重载` / `新建会话` / `重启引擎` / `重启 App`，次按钮 `知道了` 或 `稍后` | `PiSettingsEditors.kt:82-124` |
| 重启引擎确认 | 标题 `重启引擎`；忙时正文 `现在有回合正在运行。重启会终止模型调用、工具调用与正在跑的命令，它们都不会恢复；已写入磁盘的会话不会丢失。`；闲时 `重启会终止正在进行的回合，已写入磁盘的会话不会丢失。`；按钮 `重启` / `取消`；结果框标题同 `重启引擎`，按钮 `知道了` | `PiSettingsStack.kt:425-472` |
| 清单编辑器说明 | Object 型：`每行一项，写成「键 = 值」，键按精确匹配不认通配符。值以 { 或 [ 开头时按 JSON 解析，例如逐模型压缩覆盖写成 model-id = {"reserveTokens": 400000}，其余按字符串/数字/布尔解析。`；Array 型：`每行一项，支持 glob 与排除标记：!pattern 排除、+path 强制包含、-path 强制排除。值以 { 或 [ 开头时按 JSON 解析，例如 packages 的对象形式 {"source": "pi-skills", "autoload": false}。` | `PiSettingsEditors.kt:472-477` |
| 主题的告警 | `当前主题有部分颜色无法照搬：` + 逐条 `· <note>` | `PiSettingsEditors.kt:661` |
| 设置搜索页 | 副标题 `搜索全部 68 项设置。匹配标题、说明与字段名，斜杠命令也能用。`；空态 `试试字段名（reserveTokens、sessionDir），或者斜杠命令（/compact、/tree）。`；结果数 `<N> 条结果`；面包屑 `<分组标题> · <分区名>` | `SettingsSearchScreen.kt:171,118,127` / `PiSettingsRegistry.kt:135` |
| 搜索快捷词 | `reserveTokens` `thinkingBudgets` `theme` | `SettingsSearchScreen.kt:159-161` |
| 模型清单页 | 状态词 `等待重启` / `无法判断`；汇总后缀 ` · 等待重启 <N> 个`；引擎未运行时 `引擎现在没有运行，所以下面的模型都无法判断是不是已经能用。已经保存的配置不会因此丢失。` | `PiModelsScreen.kt:476,292,181` |
| 凭证页异常 | `模型配置当前无法被 pi 解析：<err>。修好之前，写入的厂商不会生效。` / `凭证当前无法被读取，这个页面显示的厂商不是全部。修好之前 pi 不会启动。` | `PiCredentialScreen.kt:297,310` |

---

## 5. 工作区 `WorkbenchScreen`

源：`ui/screens/WorkbenchScreen.kt`、`ui/terminal/TerminalPane.kt`、`PiRoot.kt:47`。

```
AppBar 标题: 工作区                                   ‹源› WorkbenchScreen.kt:65
说明文字（AppBar 下一行，唯一一句）:
  输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。   ‹源› :72
主体: 真实 PTY 终端（TerminalPane），占满剩余高度        ‹源› :77
底部导航标签: 工作区                                    ‹源› PiRoot.kt:47
终端状态提示: 终端未能启动：<error>                     ‹源› TerminalPane.kt:185
终端工具栏浮动提示: 已复制到剪贴板（OSC 52）             ‹源› TerminalPane.kt:119
```

**今天的真实分段只有一个**：终端。原来的「终端 · 文件 · Git · 任务」四段中后三段被删掉了，KDoc 逐条写了删除理由（pi 没有文件树、只读一个 git 分支名、没有任务/调度概念，见 `WorkbenchScreen.kt:19-43`）。
**没有空态**（终端自己会画）；整屏唯一文案就是上面那句说明。

---

## 6. 会话树 / 其他全屏层、弹层、对话框

### 6.1 会话树 `SessionTreeScreen`（全屏覆盖层，`PiRoot.kt:275-285`）

```
AppBar 标题: 会话树
右侧: 刷新（文字按钮「刷新」）；关闭图标无障碍名「关闭」
分段控件: 分支 | 条目                      ‹源› SessionTreeScreen.kt:115
搜索框 placeholder: 筛选条目文字           ‹源› :133
筛选循环按钮: 默认 → 无工具 → 仅提问 → 仅有标签 → 全部（点击循环到下一个）
                                          ‹源› :232-237（TreeFilter）
分支页顶部说明: 分叉会新建一个会话文件，原会话保持不变。   ‹源› :140
行内分叉按钮: 分叉新会话                   ‹源› :350
空态 1: 正在读取… / 还没有分支  + 会话有第一条消息后，这里会显示分支结构。   ‹源› :180-181
空态 2: 没有匹配的条目 + 当前筛选是「<筛选名>」。换一个关键词，或再按一次筛选按钮循环到下一种模式。  ‹源› :189-190
条目空态: 没有条目                         ‹源› :360
```
条目类型的中性标签（英文是 pi 的 entry type，逐字）：`branch_summary`、`provider/model`、`target → label` 等，见 `SessionTreeScreen.kt:419-448`。

### 6.2 删除 / 重命名 / 分叉 / 导出 / 导入

| 动作 | 真实文案 | 源 |
|---|---|---|
| 删除会话（标题） | `删除会话` | `SessionsScreen.kt:270` |
| 删除确认（非当前） | `删除「<名>」？这个会话会被移除，无法恢复。` | `:276` |
| 删除确认（当前，禁止） | `「<名>」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。` | `:274` |
| 长按动作框 | 标题 = 会话名；正文 `对这个会话做什么？`；按钮 `新建子会话` `删除` `取消` | `:242,247,254,255` |
| 重命名 | 标题 `会话名称`；说明 `会话列表里显示的名称。`；placeholder `名称`；按钮 `保存`（空名禁用）/ `取消` | `ChatSheets.kt:626-652` |
| 分叉（sheet） | `从哪条消息分支` / `会在选中的消息处创建一个新会话。` / 空态 `没有可分叉的用户消息。` | `ChatSheets.kt:564-580` |
| 分叉（树） | `分叉新会话`；说明 `分叉会新建一个会话文件，原会话保持不变。` | `SessionTreeScreen.kt:350,140` |
| 导出（入口） | `导出会话（按扩展名）` / `导出到工作区，包含扩展生成的渲染结果` | `ChatSheets.kt:382-383` |
| 导出（结果行） | `会话已导出` + 文件名 + `保存到 Download` / `分享` / `关闭` | `ChatScreen.kt:1617-1635` |
| 导出成功 notice | `会话已导出，可以保存到 Download 或分享出去。` | `PiSessionViewModel.kt:2831` |
| 导出为空 | `导出没有写出文件，请重试。` | `PiSessionViewModel.kt:2811,2893` |
| 保存失败 | `保存到 Download 失败：<原因> <建议>` / `保存到 Download 失败：文件读不出来了。请重新导出一份再试。` | `SessionExportDelivery.kt:83-90` |
| 分享失败 | `分享失败：<原因> <建议>` / `分享失败：这份导出读不出来了。请重新导出一份，或改用保存到 Download。` / `内容较大，系统分享带不动它。请先保存到 Download，再从文件管理器里分享。` | `SessionExportDelivery.kt:124-137` |
| 导入 | 会话列表 AppBar 按钮 `导入`；命令说明 `从 JSONL 文件导入并恢复会话`；成功 `已导入会话：<名>`；被扩展否决 `扩展取消了导入会话`；引擎未就绪 `引擎还没有就绪，等它启动完成后再导入。` | `SessionsScreen.kt:140` / `PiSlashCommands.kt:189` / `PiSessionViewModel.kt:2592,2588,2576` |
| 复制会话 | `复制当前会话` / `复制出一个新的会话`；成功 `已复制为新会话`；被否决 `扩展取消了复制` | `ChatSheets.kt:370-371` / `PiSessionViewModel.kt:2747,2742` |
| 新建子会话 | `新建子会话` | `SessionsScreen.kt:247` |
| 紧急停止 | `紧急停止`（危险色 Action 行） | `PiSettingsRegistry.kt:1040-1045` |
| 退出/关闭类 | `取消`、`关闭`、`知道了`、`稍后` 是全局一致的次级按钮词 | 多处 |

### 6.3 扩展 UI 对话框（`ExtensionDialogs.kt`）

```
select  → 标题 + 选项列表（逐项可点），取消键「取消」        ‹源› :89-124
confirm → 标题 + message，按钮「允许」「拒绝」              ‹源› :149-160
input   → 标题 + 输入框（placeholder 来自扩展，缺省「输入内容」），按钮「确定」「取消」  ‹源› :182-204
editor  → 标题 + 多行编辑框（placeholder 缺省「编辑内容」），按钮「确定」「取消」       ‹源› :229-250
标题附加倒计时: <title> (5s)                               ‹源› :269-277
排队提示: 还有 N 个扩展对话框在排队                          ‹源› :279
超时提示: 超时后自动拒绝/自动取消，晚到的回复会被丢弃。        ‹源› :316 + ExtensionUi.kt:53
```
扩展自己的 `notify` / `setStatus` / `setWidget` 由 `ExtensionChrome` 渲染：状态条前缀 `·`（`ExtensionChrome.kt:64`），widget 每行一行文本、空行写空格占位（`:117`）。

---

## 7. 状态与异常文案（逐条 + 源码位置）

### 7.1 AppBar 状态行 `engineLabel()`

一条永远存在的短状态（`PiSessionViewModel.kt:3053-3068`），优先级自上而下：

| 文案 | 何时 | 行 |
|---|---|---|
| `<busy 文本>`（操作进行中，如 `读取会话树`） | 有 RPC 在跑 | `:3054` |
| `工作中` | 流式中 | `:3055` |
| `启动中` | boot 未 Ready | `:3056` |
| `引擎已退出` | engine == Failed | `:3057` |
| `引擎已停止` | engine == Stopped | `:3058` |
| `启动中` | engine == Starting | `:3059` |
| `排队中` | 队列非空 | `:3060` |
| `就绪` | 其它 | `:3061` |

`call()` 的做前缀用的是动词短语，共 10 条（`PiSessionViewModel.kt:1919-2008`）：
`读取会话状态` `读取思考等级` `读取模型列表` `读取命令列表` `读取会话统计` `读取会话树` `读取可分叉消息` `读取会话内容` `读取最后一条回复`。
失败兜底：`"$label 失败"`（`:1847`）。

### 7.2 对话页空态（两套）

```
引擎正在启动
首次启动要加载 pi 的运行时与扩展，通常要几十秒。
现在发消息也可以：引擎开始工作后会立刻处理。      ‹源› ChatScreen.kt:910-914

引擎已就绪
pi 会读写你选定的工作区、执行命令、改代码。
输入 / 查看全部命令，输入 ! 直接跑 shell 命令。    ‹源› ChatScreen.kt:916-917
```

### 7.3 启动 / 首次运行 / 引擎死亡（`BootScreen.kt`）

| 状态 | 标题 | 正文 | 按钮 |
|---|---|---|---|
| 未开始 | `准备启动本地引擎` | `首次启动要准备 Linux 运行环境，需要几分钟。不需要 root。` | `开始` |
| 安装中 | `正在安装运行时` | 当前步骤名 + `步骤 N / M` + `只在首次启动时做一次，之后冷启动是秒级。` | — |
| 失败 | `引擎没有在运行` | `<boot.message>` + 可选等宽详情 | `重试` |
| 失败补充 | — | `如果反复失败，把这个提示连同它下面的文字发给我。` | — |
源：`BootScreen.kt:74,77,82,86,97,103,116,125,144,149`。

### 7.4 失败 / 异常逐条（可当错误文案库）

| 文案 | 触发场景 | 源 |
|---|---|---|
| `读取会话目录失败：<msg>` | 会话目录扫描失败 | `PiSessionViewModel.kt:1215` |
| `当前工作区还没有历史会话，已开始新会话。` | 续接开关打开但无历史 | `:1225` |
| `引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。` | 读内容空响应 | `:1885` |
| `读取会话内容失败：<原因>。可以重新打开这个会话再试一次。` | 读内容报错 | `:1893` |
| `读取会话条目失败` | `get_entries` 错误兜底 | `:2859` |
| `找不到会话文件：<文件名>` | 切换/删除找不到文件 | `:2479,2527` |
| `删除失败：<文件名>` | 删除失败 | `:2509` |
| `该模型没有 provider，pi 无法按 provider/id 定位它` | 模型缺 provider | `:2272` |
| `队列里没有待收回的消息` | `dequeue` 空 | `:2232` |
| `当前模型不支持思考等级` | 等级不可用 | `:2256` |
| `只有一个可用模型，无法循环切换` | 循环模型 | `:2290` |
| `已有 bash 命令在运行，先停止再执行新的命令` | `!` 并发冲突 | `:2409` |
| `引擎还没有就绪，等它启动完成后再导入。` | 导入过早 | `:2576` |
| `还没有模型回复可以复制` | `/copy` 空 | `:2948` |
| `导出没有写出文件，请重试。` | 导出空 | `:2811,2893` |
| `扩展取消了新建会话` / `扩展取消了切换会话` / `扩展取消了导入会话` / `扩展取消了分支` / `扩展取消了复制` | 扩展 veto | `:2461,2533,2588,2729,2742` |
| `扩展发来一个没有编号的「<method>」对话框，无法回复；这个扩展的弹窗在当前引擎上无法使用。` | 协议异常 | `:1507-1508` |
| `「/<name>」不是已安装的扩展命令、模板或技能；如果要把它作为消息发给模型，请去掉开头的 /` | 未知斜杠命令 | `:3032-3033` |
| `/<name> 只在 pi 的原版 TUI 里，本应用没有对应入口。` | 无落地页的内置命令 | `:3014` |
| `「<设置标题>」当前不可用，请把这一步报告给我们。` | Action 行无处理器 | `PiRoot.kt:236` |
| `订阅登录要在 pi 的原版 TUI 里做，本应用没有入口。用 API Key 的厂商可以在上面的「API Key」里配置。` | OAuth 行 | `PiRoot.kt:205-206` |
| `这个入口当前不可用。` | 无 dispatcher 预览态 | `SettingsGroupScreen.kt:227` |
| `重启未接入：设置页还没有拿到引擎的重启入口。文件与设置都已保存。` | 重启未接线 | `PiSettingsStack.kt:451` |
| `引擎已重启，新的进程设置已生效。` | 重启成功 | `:456` |
| `终端未能启动：<error>` | PTY 启动失败 | `TerminalPane.kt:185` |
| `图片太大，上限是 10 MB；它要整段随消息发送。请先压缩或裁剪后再试。` | 附件超限 | `ChatScreen.kt:329-331` |

> 关于「没有响应」：源码里**没有**面向用户的「没有响应」字符串。卡住不动的真实表现是**状态行停在 `工作中`**、Shell 卡页脚停在 `已运行 12.3 秒`、以及 composer 上出现「回到最新」悬浮按钮（`ChatScreen.kt:445,1072`、`TailFollow.kt:232` 的注释解释了这一现象被当 bug 报过）。请在 mockup 里用这三种表现表达「假死」，不要发明一句「没有响应」。

### 7.5 Snackbar（notice）层级

- Info / Warning 用 `Short`；Error 用 `Long` **并带动作按钮 `知道了`**（`ExtensionUiHost.kt:67-73`）。
- 底色：Info = card 底 + 正文色；Warning = info 底 + warning 色；Error = toolError 底 + error 色（`ExtensionUiHost.kt:91-98`）。

---

## 8. 术语与语气

### 8.1 它怎么称呼这些东西（照抄，别换词）

- 会话（session）——不是「对话记录」「聊天」。列表页标题就叫 `会话`，当前那条打 `当前`。
- 回合（turn）——`本回合进行中插入`、`整个回合结束后才投递`、`重启会终止正在进行的回合`。
- 工具（tool）——`内建工具`、`工具调用`、`展开全部工具输出`；工具名本身**保留英文小写**：`read` `write` `edit` `grep` `find` `ls` `bash`。
- 扩展 / 资源包 / 技能 / 提示模板（extension / package / skill / prompt template）——四者是并列的「资源」，来源徽标写 `扩展·p`、`模板·u:npm:…`、`技能`。
- 引擎（engine）——指 `pi --mode rpc` 进程：`重启引擎`、`引擎已退出`、`引擎已启动`。
- 原版 TUI——pi 自己的交互界面；只在**做不到**的时候提，且必须同时说清「本应用有没有别的入口」。
- 队列的两个模式，中文固定为 `穿插`（steer）与 `后续`（follow up）。
- 压缩（compact）——`上下文已压缩 · 释放 42k tokens`；计费行故意保留英文 `Compaction: … tokens billed`（pi 原话，`PiCommon.kt:181-196`）。
- 分叉（fork）/ 分支（branch）/ 分支摘要（branch summary）——分叉是**新建会话文件**，不是移动叶子。

### 8.2 语气规则（从代码里读出来的四条）

1. **说用户能做的事，不说字节去了哪。** 描述里不出现文件路径、类名、内部标记（`PiSettingsRegistry.kt:39-41` 把这条写成规则）。
2. **不承诺做不到的动作。** 一个入口如果本应用没有，就写「本应用没有入口」，而不是「去终端」（`SlashPalette.kt:105-112`、`PiSessionViewModel.kt:2994-3012`）。
3. **说后果，不说机制。** `收回并编辑` 而不是 `app.message.dequeue`；`已保存，重启引擎后生效` 而不是「值写入了 JSON」（`ChatScreen.kt:1547-1552`、`PiSettingsEditors.kt:95`）。
4. **状态必须是词，不是只有颜色。** 工具状态永远带字（`运行中/成功/失败`）+ 符号（`… ✓ ✗`）（`ToolBlockChrome.kt:57-69`）。

### 8.3 三组「好文案 vs 不专业文案」

| # | ✅ App 里真实的口吻 | ❌ 不要写成 |
|---|---|---|
| 1 | `当前工作区还没有历史会话，已开始新会话。`（`PiSessionViewModel.kt:1225`） | `未找到 session 数据，fallback 到默认会话。` |
| 2 | `删除「<名>」？这个会话会被移除，无法恢复。`（`SessionsScreen.kt:276`） | `确认删除？` / `操作不可逆，请谨慎。` |
| 3 | `引擎还没有就绪，等它启动完成后再导入。`（`PiSessionViewModel.kt:2576`） | `Error: engine not ready (code 7)` 或 `出错了` |

补充一组 UI 用语对照（同样从代码取）：
`展开全部（还有 12 行）` 而不是 `Load more`；`回到最新 · 8` 而不是 `Scroll to bottom`；`随消息一起发送` 而不是 `attachments will be uploaded`。

---

## 附：Mockup 落地清单（设计师速查）

- 必做屏：会话列表（列表态 / 两个空态 / 删除确认两个变体 / 长按动作框）、对话页（含 §2 的 28 个块，逐个）、设置首页 + 2 个代表性分组页（`模型与推理`、`运行时与诊断`）、工作区、会话树、扩展确认弹窗、模型/思考/会话与队列三个 sheet。
- 必做状态：`启动中` / `就绪` / `工作中` / `引擎已退出` / 排队 chips / 长任务 `运行中 · 已运行 12.3 秒` / 工具 `失败 ✗` / `出错了` 卡 / 两套对话页空态 / 会话列表两套空态。
- 一个刻意的取舍：`edit` 卡与 `diff` 卡是**两张卡**（`Transcript.kt:1405-1440`），别合并成一张。
