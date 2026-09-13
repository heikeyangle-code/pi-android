# 05 · Compose 改造计划（方向 B + A/C 各移入「一丢丢」）

> **这是一份计划，不是补丁。** 目标读者是接下来并行动手改代码的工程师（可能是多个 agent）。
> 事实底稿：`00-screen-inventory.md`（源码级清点）、`02-real-content.md`（真实文案）、
> `03-navigation-decision.md`（三目的地裁决）、`04-direction-b-graft.md`（v2 增量规格）、
> `01-design-spec.md` + `brand-spec.md`（颜色红线）。
> 行号 = 本计划写作时工作区的实际行号；改代码前请重新核对（本仓库有多个 agent 并行改动）。
>
> **命名规则（用户 2026-09-13 更正）**：这个 App 叫 **PI**（大写，`strings.xml:9`、
> 无障碍服务、`系统设置 → 应用 → PI → 权限`、诊断报告抬头）；它里面跑的 agent / CLI 叫
> **pi**（小写，上游写法）。凡本计划写「App」= PI，写「pi」= 上游引擎。
> 涉及自称的字面量按新值写（例：`PiSettingsRegistry.kt:1386` 的「由本应用固定」→「由 PI 固定」）。
>
> **本机没有编译器。** 计划里所有结论都必须有 `file:line`；不确定的显式标「不确定」。

---

## 0. 执行摘要

| 项 | 值 |
|---|---|
| 触及的源文件 | 约 34 个 Kotlin 文件（其中新建约 12 个） |
| 建议批次 | **8 批**，每批可单独过 CI |
| 结构性改动 | 4 目的地 → 3 目的地；会话列表 tab → 覆盖层；终端 tab → 设置入口行 + 全屏页；工作区 = 项目现场 |
| 视觉改动 | 耗时刻度（A）、两个读数（A）、三重编码（C）、1px 线与表面阶梯代阴影。~~一屏一个 accent（C）~~ 已撤回（用户 2026-09-13：不好维护） |
| 硬不动的 | pi 取色/变色、RPC 协议、终端内部（libvterm 网格）、扩展系统协议 |
| 最大风险 | `LazyColumn` 的 key/contentType 与窗口化、`ChatScreen` 输入区/滚动跟随、`PiMarkdownTheme` 的 token 映射 |

---

## 1. 全局现状锚点（先读这张表，再读逐屏清单）

> **范围缩减（用户 2026-09-13 追加）**：**终端整体划出范围**——`ui/terminal/**`、
> `PtyLauncher`/`PtySession`、`TerminalBridge`、终端页本身的实现**一律不动**（不重构、不清理、
> 不为新设计改它）。涉及终端的改动**只有导航层一条**：设置首页加一行「终端」入口行，
> 点进去还是现在那个全屏终端页（§3.8）。终端的**视觉细化工作为零**。
> 设置分组「终端与 Shell」**保留**（`shellPath`/`shellCommandPrefix`/`npmCommand` 是 pi 自己的配置，
> 影响 agent 执行命令）；`app.terminal.*` 两行保留现状、不做设计。

| 面 | 文件:行 | 一句话 |
|---|---|---|
| 顶层导航枚举 | `ui/PiRoot.kt:44-49` | `PiDestination` 四项：`Sessions`/`Chat`/`Workbench`/`Settings`，`ordinal` 即底栏顺序 |
| 底栏 | `ui/PiRoot.kt:131-142` | `NavigationBar` + `NavigationBarItem`，图标 `contentDescription = null`，label 用 `item.label` |
| 目的地分发 | `ui/PiRoot.kt:145-269` | `when (current)`，无 NavHost、无路由字符串 |
| 导航请求 | `ui/PiSessionViewModel.kt:121-144` | `sealed interface NavRequest`：`Sessions`/`Chat`/`Workbench`/`Settings`/`SettingsFocus(key)`/`SessionTree` |
| 导航请求消费 | `ui/PiRoot.kt:86-118` | `LaunchedEffect(navRequest)` → 改 `destination` / `treeOpen`，末尾 `consumeNav()` |
| 覆盖层（唯一） | `ui/PiRoot.kt:61,275-285` | `treeOpen` → `SessionTreeScreen` 画在 `Box` 里，覆盖当前目的地 |
| 全局宿主 | `ui/PiRoot.kt:296-299` | `ExtensionUiHost(snackbarBottomPadding = padding.calculateBottomPadding() + 8.dp)`，全 App 只挂一次 |
| 死参数 | `ui/PiRoot.kt:52-53` + `MainActivity.kt:44` | `PiRoot(isDark)` 的 `isDark` 函数体从不读取 |
| IME | `ui/PiRoot.kt:130` | 全 App 唯一一处 `Modifier.imePadding()`（`TerminalPane` 自己不再 pad） |
| 间距/形状/字号令牌 | `ui/theme/PiTheme.kt:42-128` / `:139-173` / `:328-367` | `PiSpacing` / `PiShapes` / `PiTextStyles` |
| 零引用常量 | `ui/theme/PiTheme.kt:184-205` / `:209-224` / `:250-319` | `PiElevation` / `PiIcons` / `PiMotion`，实测 `grep -rn` 只命中自身声明 |
| 转录渲染入口 | `ui/blocks/BlockRenderer.kt:70-134` | 封闭 `when`（无 `else`），14 种 item + `ToolCall` 按 `toolName` 二次分发 8 种 |
| 共享块 chrome | `ui/blocks/BlockChrome.kt:62-301` | `BlockColumn`/`MonoText`/`ProseText`/`BlockCard`/`AccentStripe`/`ExpandLabel`/`toggleContent`/`BlockActionMenu` |
| 工具卡 chrome | `ui/blocks/ToolBlockChrome.kt:44-291` | `toolContainerColor`/`toolAccentColor`/`toolStatusLabel`/`toolStatusGlyph`/`ToolHeader`/`ToolFooter`/`ToolCard`/`toolFooterText` |
| 转录列表 | `ui/screens/ChatScreen.kt:934-1044` | `LazyColumn`，`key = item.key`，`contentType = item::class`，默认渲染尾部 `TRANSCRIPT_WINDOW_STEP = 50`（`:1814`） |
| 状态行 | `ui/components/PiCommon.kt:103-176` | `PiStatusLine`，pi footer 的 `↑↓RW CH $` + 上下文占比，固定 32dp |
| 会话列表 | `ui/screens/SessionsScreen.kt:85-294` | `TopAppBar("会话")` + 搜索行 + 分组 `LazyColumn` + `ExtendedFloatingActionButton` |
| 工作区 | `ui/screens/WorkbenchScreen.kt:61-78` | `TopAppBar("工作区")` + 一句说明 + `TerminalPane(weight=1f)` |
| 设置栈 | `ui/settings/PiSettingsStack.kt:57-474` | 级 0/1/2 + 7 个 App 专属分支，`when` 在 `:300-415`，`BackHandler` 在 `:269-291` |
| 设置首页 | `ui/settings/SettingsHome.kt:52-163` | 搜索入口卡 → 当前模型卡 → 设备/扩展/关于 → 12 分组 → 页脚 |
| 设置分组页 | `ui/settings/SettingsGroupScreen.kt:46-267` | `TopAppBar` + 返回 + `LazyColumn`（`PiSectionHeader` 与 `PiSettingRow` 交替）+ 高亮底 |
| 扩展宿主 | `ui/extension/ExtensionUiHost.kt:44-114` | `SnackbarHost` 三态 + `ExtensionDialogHost` |
| 扩展对话框 | `ui/extension/ExtensionDialogs.kt:59-340` | `select`/`confirm`/`input`/`editor` + `DialogHeading` + `CountdownBar` |
| 终端 | `ui/terminal/TerminalPane.kt:85-357` + `TerminalKeyBar.kt:170-319` | 真 PTY 网格 + 一行键 chip。**本轮不动**（见范围缩减） |
| Boot | `ui/screens/BootScreen.kt:46-183` | 居中圆图标 + 三态分支 + 4dp 手绘进度条 |

---

## 2. 令牌落地方式（先定这个，后面每屏都引用它）

### 2.1 结论：**沿用现有 token 体系，只新增 3 个对象，不新建命名体系**

| 类别 | 取值来源 | 做法 |
|---|---|---|
| 颜色 | pi 令牌（`PiTheme.palette.*`）+ M3 槽位（`PiPalette.colorScheme()`，`ui/theme/PiTheme.kt:397-448`） | **一个都不许改、不许加**。见 §2.4 |
| 表面阶梯 | `surfaceContainerLowest/Low/Container/High/Highest/Bright/Dim`（`:428-434`） | 直接沿用；**禁止 `shadowElevation`**（唯一的 `ChatScreen.kt:1059` 要删，见 §3.3） |
| 圆角 | `PiShapes`（`ui/theme/PiTheme.kt:139-173`） | 沿用。v2 收紧：**卡片 16dp → 12dp（`PiShapes.cardInner`），内嵌块 12dp → 8dp（新增 `PiShapes.blockInner`）**；这两处只改 `PiShapes` 定义 + 改调用点，不动 `piShapes()` 的 M3 映射 |
| 间距 | `PiSpacing`（`:42-128`） | 沿用。**未引用的 13 个 token 先删，再按 v2 需要重新加回 4 个**（见 §2.2） |
| 线宽 | `PiSpacing.hairline = 1.dp`（`:86`） | v2 的分层唯一手段。**所有阴影改 1px 线** |
| 字号 | `PiTextStyles`（`:328-367`）+ `piTypography`（`:457-492`） | 沿用 + **新增 `PiTextStyles.monoTiny` / `micro`**（度量标尺与徽标用），见 §2.3 |
| 动效 | `PiMotion`（`:250-319`） | **不启用**。v2 §3 明确不做装饰动效；`PiMotion` 整体删除（含 6 个 `@Deprecated`） |
| 终端 | `ui/terminal/**` | **不在本轮范围内**：不重构、不清理、不替换 dp/sp 字面量、不为新设计改它。见 §3.8 |

### 2.2 必须删除的零引用常量（实测，本计划已重新核对）

`grep -rn "PiSpacing\.<名>" --include=*.kt app/src/main/kotlin/app/pi` 计数结果：

| token | 引用数 | 处置 |
|---|---|---|
| `PiSpacing.appBar` / `bottomBar` / `listItem` / `listItemSingle` / `buttonInline` / `buttonPrimary` / `tab` / `chip` / `contextRing` / `contextRingStroke` / `touchTarget` / `paragraphGap` / `listIndent` | 0 | **删**（`PiTheme.kt:47,49,50,59,62,63,67,70,71,46,53,56,59`） |
| `PiElevation`（整对象，`:184-205`） | 0 | **删**（v2 禁阴影，它正是阴影阶梯） |
| `PiIcons`（整对象，`:209-224`） | 0 | **删**（v2 保留少量 18px 单线图标，但用 M3 图标尺寸直接写，不重建这套） |
| `PiMotion`（整对象 + 6 个 `@Deprecated`，`:250-319`） | 0 | **删**（`docs/pi-android-ui-spec.md` 已过时，见 `00-screen-inventory.md` 文件头声明） |
| `PiSpacing.screen`(83) / `unit`(65) / `tiny`(25) / `inline`(12) / `hairline`(7) / `gutter`(7) / `card`(5) / `inner`(5) / `statusRow`(4) / `accentStripe`(4) / `small`(2) / `lineNumberColumn`(2) / `stripe`(1) / `bubble`(1) / `symbolColumn`(1) / `errorDot`(1) | >0 | 保留 |

**v2 需要新加回**（不要复活旧名，按新语义命名）：
`PiSpacing.ruleTick = 1.dp`、`PiSpacing.ruleTickGap = 2.dp`、`PiSpacing.meterWidth = 3.dp`、`PiSpacing.rowHairline = 1.dp`（可用 `hairline` 复用，不重复定义）。

⚠️ `00-screen-inventory.md` §5.3 把 `touchTarget` 也列进零引用，实测同样为 0；但 v2 的可点区域要求 ≥48dp，**保留 `touchTarget` 作为唯一被文档化的触达尺寸**并在本批给它接上消费者（底栏项、行内 Action），否则它会在下一轮又变成零引用。

### 2.3 新增的文件（3 个，全部放 `ui/theme/`）

```
ui/theme/PiMeter.kt        ← 耗时刻度（A 移入的唯一新视觉原语）
ui/theme/PiStateChip.kt    ← 三重编码的通用徽章（C 移入的纪律，一个实现多处复用）
ui/theme/PiLayout.kt       ← 唯一的“列宽/列表行高”常量（**本轮不接两栏**，见 §3.4）
```

伪代码（意图，不是成品）：

```kotlin
// PiMeter.kt —— 长度 ∝ log2(ms)，量级分段；纯函数，可被 JVM harness 测
enum class MeterSegments { Under1s, Under10s, Above10s }         // 1 / 2 / 3 段
fun meterSegments(elapsedMs: Long?, status: ToolStatus): Int = when { … }
@Composable fun DurationMeter(elapsedMs: Long?, status: ToolStatus)   // 1px 竖刻度，色 = toolAccentColor(status)
```

```kotlin
// PiStateChip.kt —— 字 + 符号 + 颜色；颜色只能由调用方从 palette 传入
@Composable fun StateChip(label: String, glyph: String, color: Color, modifier: Modifier = Modifier)
```

### 2.4 必须保持不动的 pi 令牌清单（引用 `00-screen-inventory.md` §4）

**执行者请把这一节当作红线，逐条对照。任何一条被改动 = 本轮返工。**

1. **`PiPalette` 的 56 个色字段**及其取值/来源解析（`ui/theme/PiPalette.kt:31-100`，默认值 `:165-288`）、`PiThemeFiles.kt` 的 `REQUIRED_TOKENS`/`OPTIONAL_FALLBACKS`/`parseThemeJson`/256 色表（`PiThemeFiles.kt:126-132,285-439`）。
2. **`PiContrast` 的 5 个派生 token**：`bodyOnTool`、`contextOnTool`、`thinkingBodyOnCanvas`、`metaOnCanvas`、`metaOnCard`（`ui/theme/PiPalette.kt:112-161`，算法 `ui/theme/PiContrast.kt`）。
3. **`PiPalette.colorScheme()` 的全部槽位映射**（`ui/theme/PiTheme.kt:397-448`），**含 `surfaceContainer*` 的 lerp 派生**（`:428-434`）。改任何一个槽位 = 改 pi 取色。
4. **`piMarkdownColors` / `piMarkdownTypography` / `piAlertColors`** 的 token 映射（`ui/render/PiMarkdownTheme.kt:136-151,178-186,212-248`）与 `piMarkdownPadding`/`dimens` 里**与颜色无关**的尺寸（`:279-290,315-324` 可动，颜色不可动）。
5. **`PiSyntaxToken.color` 的 12 个彩色映射**（`ui/render/PiCodeHighlight.kt:73-87`）。
6. **`DiffBlock` 的增删/上下文色**（`ui/blocks/DiffBlock.kt:72,74,98,147,148,151,155`）与**行底 `markColor.copy(alpha = 0.08f)`**（`:160`）——颜色是 pi 的、透明度是 App 的；透明度可动，色相不可动。
7. **各 block 直接引用的 `palette.*`**：逐文件清单见 `00-screen-inventory.md` §4.8，照抄不改。
8. **`TerminalPalette` 是唯一的例外**：它的两色（`ui/terminal/TerminalPalette.kt:35-41`）不是 pi token，属 App 自造（`00-screen-inventory.md` §5.1），**可动**；但 v2 §3 说不做终端内部改造，所以本计划**不动它**（见 §7）。
9. **允许动的一侧**（`00-screen-inventory.md` §4.9 明列）：布局/尺寸/圆角/间距、所有 `copy(alpha = …)`、`Color(0x…)` 常量（终端两色与 scrim）、`Surface` 的 `tonalElevation`/`shadowElevation`、空态与提示文案。

> **一个必须记账的例外**：`ChatScreen.kt:1094` 的 `searchMatchBg` 行底 + `:994-997` 搜索命中的 `border`/`background` 是 pi token（`searchMatchBg`/`searchMatchText`），v2 要改的只是**命中行与当前命中行的区分方式**（从「有无 1px 边框」改成「左边 1px accent 竖线 + 计数文字」），**不能**换成别的颜色。

---

## 3. 逐屏改造清单

### 3.1 顶层导航（`PiRoot` + `PiSessionViewModel`）

**现状**
- `PiRoot.kt:44-49` `enum class PiDestination(label, icon)` 四项，顺序 = 底栏顺序。
- `PiRoot.kt:131-142` `NavigationBar { PiDestination.entries.forEachIndexed { … } }`。
- `PiRoot.kt:145-269` `when (current)`，四个分支。
- `PiRoot.kt:55-56` 起始目的地 = `PiDestination.Chat.ordinal`（`rememberSaveable` Int）。
- `PiSessionViewModel.kt:121-144` `NavRequest` 六个成员。

**目标（`03-navigation-decision.md`）**
底部三项：**对话 / 工作区 / 设置**。会话列表降为对话页左上角的覆盖层；终端降为设置首页的入口行（点进去全屏页）。⚠️ `03-navigation-decision.md` 里的「≥600dp 两栏（左项目现场 / 右终端）」**本轮不做**（用户已判定终端是废品功能，见 §3.4 第 3 条、§3.8）。

**改动清单**

1. `ui/PiRoot.kt` — `PiDestination` 从 4 值改 3 值，并**删掉 icon 里的 `Terminal`**：
   ```kotlin
   enum class PiDestination(val label: String, val icon: ImageVector) {
       Chat("对话", Icons.Filled.ChatBubble),
       Workbench("工作区", Icons.Filled.Dashboard),   // 不再用 Terminal
       Settings("设置", Icons.Filled.Settings),
   }
   ```
   注意：`ordinal` 语义变化 → 已保存的 `destination` Int 会错误映射。**必须**把 `rememberSaveable` 的 key 换掉或改成 `rememberSaveable { mutableStateOf(PiDestination.Chat.name) }`（存名字不存序号），否则老用户在升级后会落在错误的目的地（`PiRoot.kt:55`）。
2. `ui/PiRoot.kt:52-53` — **删 `isDark` 参数**；`MainActivity.kt:44` 改为 `PiRoot()`。（`00-screen-inventory.md` §1.1 已核为死参数。）
3. `ui/PiRoot.kt:86-118` — `LaunchedEffect(navRequest)` 的 `when` 重写：
   - `NavRequest.Sessions` → **删该分支**（改为新成员 `NavRequest.SessionList`，动作 = 打开会话列表覆盖层，不再改目的地）。
   - `NavRequest.Workbench` → 语义保留但含义变为「工作区目的地」；新增 `NavRequest.ProjectTerminal`（全屏终端页，会话内 `onOpenTerminal` 用）。
   - `NavRequest.Chat` / `NavRequest.Settings` / `SettingsFocus` / `SessionTree` 保持。
4. `ui/PiSessionViewModel.kt:121-144` — `NavRequest` 成员调整：
   - 删 `data object Workbench` 若确认全 App 无消费者（`ChatScreen.kt:160` 的 `onOpenTerminal` 经 `PiRoot.kt:160` 走它 → 改指向 `ProjectTerminal`）。
   - 新增 `data object SessionList`、`data object ProjectTerminal`。
   - `SettingsFocus(key)` 保持不变（`/scoped-models` 依赖它，`PiRoot.kt:106-110`、`ChatScreen.kt:713-716`）。
5. `ui/PiRoot.kt:120-142` — 底栏：`NavigationBar` 保持，但给每个 `NavigationBarItem` 显式 `Modifier.heightIn(min = PiSpacing.touchTarget)` 并把 `PiSpacing.touchTarget` 接上（见 §2.2）；三项宽度 ~137dp，是 v2 选三项的理由。
6. `ui/PiRoot.kt:145-269` — `when` 分支重写：
   - `Sessions` 分支删除；`SessionsScreen` 只剩覆盖层调用点。
   - `Chat` 分支：`ChatScreen` 增加 `onOpenSessions: () -> Unit`、`onOpenTerminal: () -> Unit`（后者语义 = 全屏终端页）。
   - `Workbench` 分支：改调新的 `ProjectScreen`（§3.4）。
   - `Settings` 分支：`PiSettingsStack` 增加 `onOpenTerminal: (() -> Unit)?`（§3.7）。
7. `ui/PiRoot.kt` — 覆盖层状态从 1 个布尔扩成一个小型密封状态（两个覆盖层，同一层可切换，`03-navigation-decision.md` §裁决表）：
   ```kotlin
   var overlay by rememberSaveable { mutableStateOf<PiOverlay?>(null) }  // SessionList | SessionTree | Terminal | null
   ```
   并**新增 `BackHandler(enabled = overlay != null) { overlay = null }`**：当前覆盖层打开时按返回键会离开 App（`00-screen-inventory.md` §1.4 明列这是既有行为），v2 必须修。
8. `ui/PiRoot.kt:296-299` — `ExtensionUiHost(snackbarBottomPadding = padding.calculateBottomPadding() + 8.dp)`：**底栏高度变化后必须重新核这一行**。三项窄底栏 + 覆盖层打开时，`calculateBottomPadding()` 是 0（覆盖层在 `Scaffold` 内容区里），Snackbar 会贴到屏幕底——需要显式给一个常量兜底。这是 §6 的高风险点之一。

**引用令牌**：`PiSpacing.touchTarget`、`PiSpacing.hairline`、`surfaceContainerLow/High`、`accent`（唯一 accent 给当前目的地项）。

---

### 3.2 对话页（`ChatScreen`，1936 行，hero）

**现状**
- `ChatScreen.kt:165-191` `ChatScreen`：boot 未 Ready 时整体让位给 `BootScreen`。
- `ChatScreen.kt:198-1386` `ChatBody`：`Column`，顺序 = AppBar → `PiStatusLine`(`:868`) → `ExtensionStatusRow`(`:875`) → 可选 `SearchBar`(`:877`) → `Box(weight=1f)`（空态 `PiEmptyState` `:900-919` 或 `LazyColumn` `:934-1044` + `回到最新` 悬浮 `:1051-1078`）→ `QueueRow`(`:1082`) → `ExportDeliveryRow`(`:1100`) → `ExtensionWidgetStack(AboveEditor)`(`:1123`) → `BashPanel`(`:1127`) → `SlashPalette`(`:1141`) → `MentionPalette`(`:1180`) → 附件行(`:1189`) → `Composer`(`:1216`) → `ExtensionWidgetStack(BelowEditor)`(`:1303`) → `Spacer(bottomInset + 8.dp)`(`:1307`)。
- `ChatScreen.kt:194` `private enum class ChatSheet { Model, Thinking, Tools, Stats, Fork, Rename }`，`when` 在 `:1310-1385`。

**目标**
1. **AppBar 标题行成为覆盖层入口**：会话名从纯文本改成可点行（`onOpenSessions`），右侧加一个 `当前 →` 的箭头 affordance；`engineLabel` 保留但补**三重编码**（文字 + 符号 + 颜色）。
2. **状态行补两个读数**（`04 §1.2`）：`PiStatusLine` 增加 `输出` 与 `缓存读` 两个读数，在同一行。
3. **工具卡页脚加耗时刻度**（`04 §1.1`）。
4. ~~**一屏一个 accent**：v2 只留「正在跑的那一步 / 当前选中项」。~~ **已撤回**（用户 2026-09-13：「不要做 C 的那个一屏幕一个什么了，那个不好维护」）。accent 按组件语义正常使用；**不要**为了凑预算把现有 accent 撤下来。
5. **删 `shadowElevation = 3.dp`**（`:1059`），`回到最新` 浮起胶囊改为 1px `borderMuted` 线 + `surfaceContainerHigh` 底。

**改动清单**

| # | 位置 | 动作 |
|---|---|---|
| A1 | `ChatScreen.kt:722-746` | AppBar title 两行改造：第一行包一层 `Row` + `Modifier.clickable(onClick = onOpenSessions)`，右侧加 `Icons.Filled.KeyboardArrowDown`（`contentDescription = "会话列表"`）；**不要**改成 `TextButton`（会破坏 title 的排版） |
| A2 | `ChatScreen.kt:198-203` | `ChatBody` 增加参数 `onOpenSessions: () -> Unit`；`ChatScreen`(`:165`) 透传 |
| A3 | `ChatScreen.kt:868-873` | `PiStatusLine(...)` 调用增加 `showTwoReadings = true`（或直接改 `PiCommon.kt:103-176` 的签名，见 A10） |
| A4 | `ChatScreen.kt:1051-1078` | 删 `shadowElevation = 3.dp`；加 `.border(1.dp, PiTheme.palette.borderMuted, RoundedCornerShape(percent = 50))`；调色改为 `surfaceContainerLow`（表面阶梯，不用阴影） |
| A5 | `ChatScreen.kt:1730-1751` | `后续` chip：`accent.copy(alpha=0.18f)` 底 + `accent` 字 → 改为**中性底 + accent 字 + 前置符号**（`+` 或 `↳`）。accent 只保留给「正在跑的那一步」 |
| A6 | `ChatScreen.kt:1772-1783` | 思考 chip `◐ 中`：**保留**（它已经是三重编码的样板：符号 `◐` + 中文等级 + `thinking(level)` 色温）。唯一改动是把底色 `alpha = 0.18f` 降到 `0.12f` 并**只在流式中**才用 thinking 色（非流式用 `onSurfaceVariant`） |
| A7 | `ChatScreen.kt:934-1044` | `LazyColumn` 的 `itemsIndexed` 块。**只加不改 key/contentType**：新增 `item(key = "transcript-duration-legend")` 之类的行是错的——不要加辅助行。改动只允许发生在 `BlockRenderer` 内部，列表契约保持 `key = { _, item -> item.key }` + `contentType = { _, item -> item::class }`（`:982-983`） |
| A8 | `ChatScreen.kt:992-998` | 搜索命中：`isCurrentMatch` 的 1px `border` 改为**左侧 1px accent 竖线**（`Modifier.drawBehind` 或一个 2dp `Box`）＋ 保持 `searchMatchBg` 底；`isMatch` 只保留底色。**颜色不变**，只改形状 |
| A9 | `ChatScreen.kt:1359-1365` | `SessionToolsSheet` 的导出/重命名等行保持；该 sheet 的**结构**不动（v2 不重排 sheet） |
| A10 | `ui/components/PiCommon.kt:103-176` | `PiStatusLine`：在 `statsText` 之后、上下文占比之前插入 `输出 {piFormatTokens(output)}` 与 `缓存读 {piFormatTokens(cacheRead)}`。数据来自 `stats.tokens.output` / `stats.tokens.cacheRead`（**pi 的真实 usage 字段**，`:111-117` 已经在读）。注意 `piFormatTokens`（`:41-47`）与 `formatTokens`（`BlockChrome.kt:333-337`）是**两个不同的取整**，状态行必须用前者 |
| A11 | `ChatScreen.kt:1814` | `TRANSCRIPT_WINDOW_STEP = 50`：**不动**。v2 的「加载更早的 N 条」文案（`:963`）已符合要求 |
| A12 | `ChatScreen.kt:216-218` | `toolsExpanded` 的 `rememberSaveable(prefs.expandToolsByDefault)` 契约保持；v2 只改工具卡的**外观**，不改展开语义 |
| A13 | `ChatScreen.kt:182-189` | `BootScreen` 分流保持（Boot 三态在 §3.9 单独改造） |

**引用令牌**：`accent`（唯一一处）、`borderMuted`、`surfaceContainerLow/High`、`toolPendingBg/SuccessBg/ErrorBg`（经 `BlockRenderer`）、`bashMode`、`thinking(level)`、`searchMatchBg`/`searchMatchText`。

---

### 3.3 转录块渲染（`ui/blocks/**`，8 种工具卡 + `BlockRenderer`）

这是 v2 的**主战场**，也是改动最容易被做错的地方。先把 `BlockRenderer` 的契约钉死。

**现状（`BlockRenderer.kt:82-133`）**：封闭 `when`，14 种 `TranscriptItem`；`ToolCall` 按 `item.images.isNotEmpty()` 与 `item.toolName` 二次分发 → `ReadBlock`/`WriteBlock`/`EditBlock`/`GrepBlock`/`FindBlock`/`LsBlock`/`ShellBlock`/`ToolCallBlock`；`ToolDiff` 是**独立块**（`:116`）。

**目标与改动**

| 组件 | 现状 `file:line` | v2 改动 | 类型 |
|---|---|---|---|
| `ToolHeader` | `ToolBlockChrome.kt:80-110` | 右侧状态字形从单独一个 `Text` 改为 `StateChip(label, glyph, color)`——**字 + 符号 + 颜色三重编码**（C 移入）。`toolStatusLabel`/`toolStatusGlyph`(`:58-69`) 已经在，直接复用 | 改 |
| `ToolFooter` | `ToolBlockChrome.kt:120-141` | 在 `Text(text)` 与 `ExpandLabel` 之间插入 `DurationMeter(elapsedMs, status)`（A 移入，新建 `ui/theme/PiMeter.kt`）。刻度宽度固定 3dp，高度按 `meterSegments` 分 1/2/3 段，色 = `toolAccentColor(status)` | 改 |
| `toolFooterText` | `ToolBlockChrome.kt:273-281` | **不改**（`成功 · 退出码 N · 132ms · 13 行` 是 pi 的语义，v2 只要刻度作为补充而不是替代——`04 §1.1` 明确写「保留 B 原本的读数文字」） | 不动 |
| `ToolCard` | `ToolBlockChrome.kt:206-220` | 边框 `toolAccentColor(status).copy(alpha = 0.35f)` → 状态**成功/失败/进行中**都保留；但 v2 要求「层级用 1px 线与表面阶梯」，所以非 pending 卡的边框 alpha 降到 `0.22f`，pending 卡保留 `0.35f`（唯一发光给正在跑的那一步） | 改 |
| `ToolCallBlock`（通用/兜底） | `ToolCallBlock.kt:37-212` | 只继承 `ToolHeader`/`ToolFooter` 的改动；`ImageGridBlock` 吞点击逻辑(`:194-202`)不动 | 继承 |
| `ReadBlock` | `ReadBlock.kt:39-120` | 继承；`SourceLines` 的 gutter 色 `palette.muted`(`ToolBodyText.kt:57-58`)不动 | 继承 |
| `WriteBlock` | `WriteBlock.kt:35-118` | 继承 | 继承 |
| `EditBlock` | `EditBlock.kt:36-62` | 继承。**`edit` 卡与 `diff` 卡保持两张卡**（`02-real-content.md` 附注明确要求，`Transcript.kt:1405-1440`） | 继承 |
| `GrepBlock` | `GrepBlock.kt:43-173` | 继承；`GrepGroupHeading` 的 `N 处`(`:134-143`) 加 `StateChip` 形态 | 改 |
| `PathListBlock`（find/ls） | `PathListBlock.kt:32-212` | 继承 | 继承 |
| `ShellBlock`（bash/powershell） | `ShellBlock.kt:48-153` | 继承 + **pending 卡的页脚刻度是 v2 的签名细节**（`运行中 · 已运行 12.3 秒 · 18 行` + 一段刻度） | 改 |
| `DiffBlock` | `DiffBlock.kt:35-247` | **只改外层卡**：`piShapes` 圆角、边框 alpha；**行内三色与 `alpha = 0.08f` 行底一律不动**（§2.4 第 6 条） | 改（外壳） |
| `ThinkingBlockBlock` | `ThinkingBlockBlock.kt:30-89` | 折叠行 32dp 保持（`PiSpacing.statusRow` 是 spec 值，`PiTheme.kt:115-127` 的 KDoc 记录了这一点）；`AccentStripe` 色 = `thinking(level)` 不动；等级中文标签用 `StateChip` | 改 |
| `AssistantTextBlock` | `AssistantTextBlock.kt:35-70` | 流式光标 `8dp × 16dp`、圆角 2dp、`accent.copy(alpha=0.65f)`(`:62-64`) —— **这是全屏唯一的 accent 之一，保留** | 不动 |
| `UserMessageBlock` | `UserMessageBlock.kt:38-98` | 容器 `userMessageBg` + `PiShapes.card` 不动；`PiShapes.card` 16dp→12dp 的全局变化会波及它，**这里需要单独确认**：用户气泡 16dp 是 §2.3 的值，v2 若收紧全卡圆角，气泡是否跟着收？→ **决策：气泡保持 16dp（单独写 `RoundedCornerShape(16.dp)`），只把工具卡与内嵌块收紧** | 需确认 |
| `CompactionBlock` | `CompactionBlock.kt:42-150` | 通栏 chip 形态保留；`customMessageBg`/`customMessageLabel` 色不动 | 继承 |
| `BranchSummaryBlock` | `BranchSummaryBlock.kt:41-137` | 卡 + 3dp 竖条保留；标题行的分支 id 已是 `monoSmall`+`muted` | 继承 |
| `HookMessageBlock` | `HookMessageBlock.kt:29-95` | 保留 | 继承 |
| `SkillInvocationBlock` | `SkillInvocationBlock.kt:38-126` | 保留 | 继承 |
| `ModelChangeBlock` | `ModelChangeBlock.kt:27-74` | 一行 32dp + `borderAccent` 模型名色彩。**`borderAccent` 是 pi token，不动**；`borderAccent` 保持现状（预算规则已撤回，不做非流式降级） | 改 |
| `ErrorBlock` | `ErrorBlock.kt:33-96` | 保留（已经有三重编码：左侧 error 竖条 + `出错了` 字 + `详情` 展开） | 不动 |
| `DateSeparatorBlock` | `DateSeparatorBlock.kt:22-56` | 保留 | 不动 |
| `NoticeBlock` | `NoticeBlock.kt:21-53` | `·` 前缀 + tone 色 + 时钟。v2 要求三重编码 → 前缀符号按 tone 变（Info `·` / Warning `!` / Error `✗`），文字保留 | 改 |
| `ImageGridBlock` | `ImageGridBlock.kt:181` | 保留 | 不动 |

**新共享组件（放 `ui/blocks/BlockChrome.kt` 之后，或新建 `ui/blocks/StateChrome.kt`）**
```
StateChip(label: String, glyph: String, color: Color)   ← 工具状态 / 队列 / 审批 / 连接 / 错误 共用
DurationMeter(elapsedMs: Long?, status: ToolStatus)     ← 只在 ToolFooter 里调用
```

⚠️ **性能红线**（`ToolBlockChrome.kt:38-40` 的 KDoc 与 F31/F8）：块在每次 200ms 发布时重组，**刻度所需的一切必须由调用方传进来或 `remember` 计算**，`toolFooterText(item, label, lines)` 的 `lines` 就是为此设计的。`DurationMeter` 不能扫描 `item.output`。

---

### 3.4 工作区（`WorkbenchScreen` → `ProjectScreen`，项目现场）

**现状**：`ui/screens/WorkbenchScreen.kt:61-78` —— `TopAppBar("工作区")` + 一句说明（`:72`）+ `TerminalPane(weight=1f)`(`:77`)。KDoc `:17-58` 逐条论证了「终端是唯一有 pi 能力支撑的分段」。

**目标**：从「整屏终端」变「项目现场」。但 `01-design-spec.md:97` 的硬约束是「**不要发明 pi 没有的数据**」。所以这里必须逐项判定可做/不可做：
| 设计稿要求（`03-navigation-decision.md` §裁决表） | 有没有数据源 | 处置 |
|---|---|---|
| 当前 cwd | ✅ `PiSessionStore.Summary.cwd`（`ui/screens/SessionsScreen.kt:339` 已在用，`groupLabel(cwd)` `:379-385` 负责把 guest path 翻成人能读的组名） | **做**：显示 `groupLabel` 的同一套输出，**不打印路径**（`brand-spec.md` §6 禁路径） |
| 这个目录里的 `.pi` 资源（技能/提示词/扩展/主题/包） | ✅ `PiResourceDiscovery.discover(root, scope)`（`app/src/main/kotlin/app/pi/packages/PiResourceDiscovery.kt:74-82`，Kind = Skills/Prompts/Themes，Scope = Global/Project）+ `PiAutoExtensions`（扩展）+ `settings.json` 的 `packages` | **做**：读 `<workspace>/.pi` 与 agentDir 两处，按 kind 分组显示计数与名字（名字可显示，它们是用户自己的文件） |
| git 改动 | ❌ **没有数据源**。`WorkbenchScreen.kt:26-33` 的 KDoc 已逐条论证：pi 只从 git 读一个分支名，且只喂它自己的 TUI footer；`rpc-types.ts:20-74` 没有 branch/changes/diff/checkpoint 任何通道 | **不做**（列入 §7）。不要写「读不到 git 状态」这种占位行 → **整块省略** |
| 这次会话动过的文件 | ⚠️ 可从本次转录推导（`ToolCall` 的 `path`/`file_path` 参数，`ToolBlockChrome.kt:260-263` 的 `toolCommandText` 已在解析同一批字段）。**但这是 App 侧推导，不是 pi 的字段** | **可做，但必须标注推导来源**：行的说明写「来自这次会话的工具调用」，不要写成「pi 报告的文件」 |

**改动清单**
1. 新建 `ui/screens/ProjectScreen.kt`（约 200 行）：
   - `TopAppBar("工作区")`；**不保留** `WorkbenchScreen.kt:72` 那句关于原版 TUI 的话（它跟终端一起搬去 `TerminalScreen`，见 §3.8）。
   - `LazyColumn`：`PiSectionHeader("工作目录")` + 一行 `groupLabel` 输出；`PiSectionHeader("项目资源")` + 资源行；`PiSectionHeader("这次会话动过的文件")` + 文件行（空态：「这次会话还没有改过文件。」）。
   - **不引入新组件**：行一律用 `ui/components/PiCommon.kt` 的 `PiValueRow`/`PiSwitchRow`/`PiSectionHeader`（保证与设置页同语言）。
2. 删除 `ui/screens/WorkbenchScreen.kt`（**建议直接删文件**，避免两个入口；终端那部分由 §3.8 的 `TerminalScreen.kt` 接管）。`PiRoot.kt:32` 的 import 与 `:163-165` 的调用同时改。
3. **不做宽屏两栏**。`ui/theme/PiLayout.kt` 的 `TwoPaneMinWidth` 常量可以保留（将来项目现场自己用），但本轮**不接任何两栏**——用户已判定终端是废品功能，不值得为它做宽屏布局。
4. 数据接线：`ProjectScreen` 需要的 workspace root 已有现成入口 —— `PtyLauncher.workspaceHost(context)`（`PiSettingsStack.kt:198` 在用）。`PiRoot` 需要把 `session` 传给它（现在 `WorkbenchScreen` 不接 `session`，这是**签名变化**）。

---

### 3.5 会话列表覆盖层（`SessionsScreen` → 覆盖层）

**现状**：`ui/screens/SessionsScreen.kt:85-294` —— 整屏，`TopAppBar("会话")`(`:136`) + 导入(`:140`) + 刷新(`:142`) + 搜索行(`:146-165`) + 长按提示(`:171`) + 两套空态(`:176-189`) + 分组 `LazyColumn`(`:195-213`) + `ExtendedFloatingActionButton("新建会话")`(`:216-229`) + 两个 `AlertDialog`(`:238-293`)。

**目标**：同一个 `SessionsScreen` 作为**覆盖层**渲染，不再是目的地。`03-navigation-decision.md`：列表 ↔ 会话树在同一层切换。

**改动清单**
1. `SessionsScreen.kt:83-89` 签名改动：
   ```kotlin
   fun SessionsScreen(
       contentPadding: PaddingValues,
       onOpenChat: () -> Unit,
       onOpenTree: () -> Unit,        // 新增：右上角「会话树」入口（列表 ↔ 树同层切换）
       onClose: () -> Unit,           // 新增：关闭覆盖层
       session: PiSessionViewModel,
   )
   ```
2. `SessionsScreen.kt:133-230` 外层从 `Box(fillMaxSize)` 改成覆盖层容器：`Surface(Modifier.fillMaxSize(), color = palette.pageBg)`（与 `SessionTreeScreen.kt:88-160` 的 `Surface(fillMaxSize, color = background)` 对齐）。**必须不透明**，否则下面的对话页会透出来。
3. `SessionsScreen.kt:135-145` `TopAppBar`：左侧加 `IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭会话列表") }`；右侧 actions 增加一个「会话树」TextButton（`onOpenTree`）。
4. `SessionsScreen.kt:216-229` `ExtendedFloatingActionButton` 的 `bottom` padding 依赖 `contentPadding.calculateBottomPadding()`；覆盖层里底栏仍存在（在 `Scaffold` 外）→ **需要设备确认 FAB 是否被底栏遮住**（§8 清单第 5 条）。
5. **新增 `BackHandler(onBack = onClose)`**（当前无，`00-screen-inventory.md` §1.4）。若 `PiRoot` 也加了统一 `BackHandler`（§3.1 第 7 条），**二者只能留一个** —— 建议只留 `PiRoot` 的那一个，`SessionsScreen` 不自带，避免两个 handler 竞争。**这是执行者最容易做错的点之一**。
6. 行渲染 `SessionRow`(`:298-365`) 的 v2 改动（`04 §4` 要求补长按操作框/删除确认/排序筛选选中态）：
   - `:313-334` 当前徽章 `Surface(PiShapes.badge, color = primaryContainer)` → 保留，但补一个 `StateChip` 形态的符号（`● 当前`）。
   - `:337-346` 副行 `{组名} · {model}` 与 `:348-356` 第三行 `{n} 条 · 分支 · 已命名` → 保持文案（这些是 `02-real-content.md:78-86` 的字段公式，**不许改写**）。
   - `:359-363` 右侧 `relativeTime` → 已是 `meta` 字；加 `numeric`（`PiTheme.text.numeric`，`PiTheme.kt:374`）保证不抖动。
   - 分组头 `:200` `PiSectionHeader(groupLabel(cwd))` → 保留。
7. 两套空态文案(`:177-189`)与长按对话框(`:238-259`)、删除确认(`:266-293`)**一字不改**（它们是真实文案）。
8. **对话页左上角入口的接线**：`ChatScreen` 的 A1 改动调 `onOpenSessions` → `PiRoot` 设 `overlay = SessionList`。

**引用令牌**：`pageBg`（覆盖层底）、`primaryContainer`/`onPrimaryContainer`（当前徽章，pi 槽位）、`borderMuted`（行分隔 hline，v2 新增）。

---

### 3.6 会话树（`SessionTreeScreen`，覆盖层）

**现状**：`ui/chat/SessionTreeScreen.kt:88-160`（`Surface(fillMaxSize, color = background)` + `TopAppBar("会话树")` + 刷新/关闭 + 分段按钮 `分支`/`条目`）；`BranchTab:163-`、`EntriesTab:356-`、`BranchRow:287-`、`EntryRow:381-`。缩进 `row.depth * 12.dp`，左侧 2dp×28dp 竖线(`:294-302`)。

**目标**：与会话列表同为覆盖层、同层可切换（`03` 裁决）；树行加 1px 线分层；`depth * 12.dp` 的裸 dp 收进 token。

**改动清单**
1. `SessionTreeScreen.kt:88-110` 增加 `onClose` 与列表切换入口（若 §3.5 决定由 `PiRoot` 管返回，这里不加 `BackHandler`）。
2. `SessionTreeScreen.kt:294-302` 的 `12.dp` / `2.dp` / `28.dp` → `PiSpacing.inline`(8) / `PiSpacing.ruleTick`(1，新) / `PiSpacing.accentStripe`(20)。**注意这是尺寸改动，会改变缩进的视觉节奏**，属「可改侧」。
3. `SessionTreeScreen.kt:115` 分段按钮与 `:133` 筛选输入框保持文案（`筛选条目文字`）。
4. 覆盖层不透明性：`Surface(color = background)`(`:88`) 已经是 `pageBg`，与列表覆盖层一致。

---

### 3.7 设置（级 0/1/2 + App 专属子页）

**现状**：`ui/settings/PiSettingsStack.kt:57-474`。级 0 `SettingsHome`(`SettingsHome.kt:52-163`)、级 1 `SettingsGroupScreen`(`SettingsGroupScreen.kt:46-267`)、级 2 `SettingsSearchScreen`(`SettingsSearchScreen.kt:55-247`)。分支 `when` 在 `PiSettingsStack.kt:300-415`：`licenses`/`diagnostics`/`credentials`/`models`/`packages`/`deviceCapabilities`/`searching`/`currentGroup != null`/else `SettingsHome`。App 专属子页：`DeviceCapabilityScreen.kt:93`、`PiCredentialScreen.kt:101`、`PiModelsScreen.kt:87`、`LicensesScreen.kt:128`、`DiagnosticsScreen.kt:67`、`PiPackagesScreen.kt:133`（后者在 `app/pi/packages/`，不在 `ui/` 下）。

**目标**：设置首页新增**终端入口行**（与 设备/扩展/关于 同级）；12 分组页的行型与徽标样式统一到 v2 的令牌；6 种行型、4 种生效徽标、危险行确认态、5 个 sheet 全部补齐状态（`04 §4`）。

**改动清单**

| # | 位置 | 动作 |
|---|---|---|
| S1 | `PiSettingsStack.kt:137-139`（`licenses` 变量附近） | 新增 `var terminal by remember { mutableStateOf(false) }` |
| S2 | `PiSettingsStack.kt:269-291` `BackHandler` | 的 `enabled` 条件与分支链加入 `terminal` |
| S3 | `PiSettingsStack.kt:300-415` `when` | **新增分支** `terminal -> TerminalScreen(onBack = { terminal = false })`（第 §3.4 步新建的那一屏）。**放在最前或按现有顺序**：建议插在 `diagnostics` 之后，保持「App 专属页」的成组 |
| S4 | `PiSettingsStack.kt:401-414` | `SettingsHome(... onOpenTerminal = { terminal = true })` |
| S5 | `SettingsHome.kt:52-75` | 签名加 `onOpenTerminal: (() -> Unit)? = null`（与 `onOpenDeviceCapabilities` 同形，`null` 隐藏） |
| S6 | `SettingsHome.kt:102-136` | 新增一节：**放在哪？** `03` 说「与 设备 / 扩展 / 关于 同级」→ 新增 `PiSectionHeader("终端")` + 一行 `PiValueRow(title = "终端", supporting = "输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。", value = "打开", onClick = onOpenTerminal)`。**说明文案直接搬 `WorkbenchScreen.kt:72`**（一字不改），并随之从 `WorkbenchScreen` 删除 |
| S7 | `PiCommon.kt:372-393` `PiEffectiveBadge` | 4 种徽标已齐（`需重载`/`新会话`/`需重启引擎`/`需重启`）。v2 只改**形状**：`alpha = 0.16f` 底 + `PiShapes.badge` → 保留；文字色与 `Surface` 底不动（颜色来自 `tertiary`/`onSurfaceVariant`/`error`，是 M3 槽位 = pi 派生，**不动**） |
| S8 | `SettingsGroupScreen.kt:132-142` | 行高亮底 `secondaryContainer.copy(alpha = 0.35f)` → v2 用 **1px accent 左线 + `surfaceContainerLow` 底**（`accent` 是「当前项」唯一 accent 的合法用法） |
| S9 | `SettingsGroupScreen.kt:113-120` | `TopAppBar` + 返回箭头：文案 `返回` 保持；`PiSpacing.appBar`(56dp) 已定义零引用 → 若 v2 要固定 app bar 高度，这里接上它（否则 §2.2 的删除清单包含它） |
| S10 | `PiSettingsRows.kt:41-88` `PiSettingRow` | 6 种行型已齐。v2 改动只有两处：`PiActionSettingRow`(`:91-128`) 的危险色 `error` 保持（pi token）；行间距收进 `PiSpacing`（现在 `:106` 是裸 `12.dp`，`:304` 的 `PiSwitchRow` 是裸 `10.dp`，`:345` 的 `PiValueRow` 是裸 `12.dp`） |
| S11 | `PiSettingsEditors.kt:73/134/255/364/443/619` | 5 个 sheet（`PiEffectiveDialog`/`PiOptionPickerSheet`/`PiNumberEditorSheet`/`PiTextEditorSheet`/`PiListEditorSheet`/`PiThemeEditorSheet`）+ 1 个对话框已齐。v2 改动：`heightIn(max = …)` 的裸值（`:165` 420dp、`:526` 300dp、`:673` 200dp）→ 收进 `PiLayout.kt` 常量。**sheet 操作逻辑一律不动** |
| S12 | `PiSettingsEditors.kt:661` | 主题告警 `当前主题有部分颜色无法照搬：` + 逐条 note —— **文案与颜色都不动**（它正是 pi 取色链路的展示面） |
| S13 | `PiSettingsStack.kt:193-206` | `PiDirectoryWatch` 的 `SETTINGS_WATCHED`(`:482-492`) **不动**（`sessions/` 刻意不在名单里，`:479-481` 有理由） |

**App 专属子页（`04 §4` 要求各一张）**：`DeviceCapabilityScreen.kt:93`、`PiPackagesScreen.kt:133`、`PiModelsScreen.kt:87`、`PiCredentialScreen.kt:101`、`LicensesScreen.kt:128`、`DiagnosticsScreen.kt:67`。
- 这 6 个文件的**共同改造只有一件事**：把裸 `dp` 收进 `PiSpacing`（`00-screen-inventory.md` §5.4：`DeviceCapabilityScreen.kt` 24 处、`PiCredentialScreen.kt` 13 处、`PiModelsScreen.kt` 8 处、`LicensesScreen.kt` 2 处、`DiagnosticsScreen.kt` 1 处）+ 卡圆角统一到 `PiShapes`。
- **不重排它们的板块结构**（`04 §4` 说「各一张」，是设计稿要求，不是改造要求）。
- `PiPackagesScreen` 有 22 个 `Card`/`item`，是这批里最重的；**建议单独一批**（§6 第 6 批）。
- ⚠️ **不确定**：`PiPackagesScreen.kt` 与 `PiPackagesHost.kt` 属 `app/pi/packages/`，不在 `00-screen-inventory.md` 的逐行核对范围内（§7 第 1 条明列「未逐节核对」）。动它之前先读一遍全文——它 934 行。

---

### 3.8 终端页 —— **本轮只做导航，零视觉工作**

> **用户裁决（2026-09-13）**：「终端基本不用管它了。现在是个废品那个功能。」
> 因此：`ui/terminal/**`（`TerminalPane.kt`、`TerminalKeyBar.kt`、`TerminalBridge.kt`、
> `TerminalSettings.kt`、`TerminalPalette.kt`）、`runtime/PtyLauncher`、`runtime/PtySession`
> **一律不动**。不清理裸 dp/sp、不统一圆角、不套新令牌、不补三重编码、不加减任何东西。

**现状**：终端是底栏第 3 个目的地（`PiDestination.Workbench`，`PiRoot.kt:47`），页面是
`ui/screens/WorkbenchScreen.kt:61-78`（`TopAppBar("工作区")` + 一句说明 `:72` + `TerminalPane(weight=1f)` `:77`）。

**目标**：终端从底部目的地降为**设置首页的一个入口行**（`03-navigation-decision.md:24`），
点进去仍是现在这个全屏终端页（**内容与实现一字不改**）。

**改动清单（只有导航层，共 5 处）**

1. **新建 `ui/screens/TerminalScreen.kt`（约 30 行，薄包装，不含任何视觉设计）**：
   ```kotlin
   @Composable
   fun TerminalScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
       Column(Modifier.fillMaxSize().padding(contentPadding)) {
           // navigationIcon = ArrowBack，contentDescription "返回"
           TopAppBar(title = { Text("终端") }, navigationIcon = { BackIcon(onBack) })
           // 原 WorkbenchScreen.kt:72，一字不改
           Text("输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。")
           TerminalPane(modifier = Modifier.weight(1f))
       }
   }
   ```
   （上面两处行内注释写成整行注释，是因为 Kotlin 的块注释会**嵌套**：行内出现注释起止符的形状会把整个文件吞掉，见 §7.1 的 `comments` job 警告。）
   说明：这是把 `WorkbenchScreen.kt` 现有的三行版面**原样搬到新文件**并加一个返回箭头，
   不是重新设计。`TerminalPane` 的调用方式（`weight(1f)`、不传其他参数）与现在完全一致。
2. **删 `ui/screens/WorkbenchScreen.kt`**（它的 `TopAppBar("工作区")` 与那句说明全部由
   §3.4 的 `ProjectScreen.kt` / 本节的 `TerminalScreen.kt` 接管，不再保留同名文件）。
   `PiRoot.kt:32` 的 import 与 `:163-165` 的调用同步改（调用点落在 §3.4 的 `ProjectScreen`）。
3. **`PiRoot.kt`**：新增 `NavRequest.ProjectTerminal` 的消费分支——打开全屏终端页。
   `PiRoot.kt:160` 的 `onOpenTerminal = { session.requestNav(NavRequest.Workbench) }`
   改指 `NavRequest.ProjectTerminal`（语义从「切目的地」变为「打开终端页」）。
   终端页的呈现方式与 §3.5 的会话列表覆盖层**共用一个 overlay 状态**（§3.1 第 7 条），
   由 `PiRoot` 统一管返回键。
4. **`SettingsHome.kt` 新增「终端」入口行 + `PiSettingsStack` 接线**：见 §3.7 的 S1–S6。
5. **`ChatScreen.kt:1759-1766` 的「终端」文字入口与 `:854` 的「打开终端（输入 pi 进原版 TUI）」**
   保留，只是 `onOpenTerminal` 的目标换成新的 `NavRequest.ProjectTerminal`。

**不做（明确排除）**
- ❌ 不把 `TerminalKeyBar.kt:240,260,279,307,316` 的裸 `40/34/62dp`、`11/12sp` 收进 `PiSpacing`/`PiTextStyles`。
- ❌ 不动 `TerminalPane.kt:87-88` 的 `TerminalPalette.dark()/light()` 明暗选择、`:119`/`:185` 的状态文案。
- ❌ 不动 `TerminalPalette.kt:18-43` 的两个 App 自造色（它是唯一不走 pi 主题的取色面，本轮维持现状）。
- ❌ 不做 §3.4 早期草案里的「≥600dp 两栏（左项目现场 / 右终端）」。**用户已判定终端是废品功能，不值得为它做宽屏布局**。`ui/theme/PiLayout.kt` 的 `TwoPaneMinWidth` 仍可保留给将来的项目现场使用，但本轮**不接任何两栏**。

**设置分组的「终端与 Shell」保留（配置面，不是设计面）**
- `PiSettingsRegistry.kt` 的 `terminal` 分组（`shellPath` `:952`、`shellCommandPrefix` `:964`、
  `npmCommand` `:976`、`app.terminal.fontSize` `:990`、`app.terminal.keyBar` `:1006`）
  **一个键都不许删、不许改名、不许改描述** —— 它们是 pi 自己的配置，影响 agent 执行命令。
- `app.terminal.fontSize` / `app.terminal.keyBar` 属 App 侧显示项，**保留现状、不做设计**（不换行型、
  不换徽标、不重排）。它们仍受 §3.7 的通用视觉批（B6）影响，但只受「行距统一」这一项，不受任何终端专属改造。

⚠️ 文档冲突：`brand-spec.md` §6 写「不许出现终端页/终端入口/终端字样」，但 `03-navigation-decision.md:24`、
`01-design-spec.md:55,93`、`04 §4` 与本次用户裁决都明确保留「设置首页的一个终端入口行 + 全屏终端页」。
**以 `03`/`04`/用户裁决为准**；`brand-spec.md` §6 那一句只保留它的颜色红线部分。

---

### 3.9 Boot（`BootScreen`）

**现状**：`ui/screens/BootScreen.kt:46-158`，`StepBar:167-183`（两个 `Box` 拼的 4dp 进度条）。渲染入口 `ChatScreen.kt:182-189`。

**目标**：三态（`准备启动本地引擎` / `正在安装运行时` / `引擎没有在运行`）视觉升级；**文案一字不改**（`02-real-content.md:980-986` 逐条给了源码行）。

**改动清单**
1. `BootScreen.kt:59-69` 圆形 `Surface` + `Memory` 图标 → **改为 π 字形**（`brand-spec.md` §1 的 path 是唯一允许的图形资产，用法限定「App 图标 / 顶部标识 / 空态标识」）。新建 `ui/components/PiMark.kt`，内联 SVG path（用 `ImageVector.Builder` 或 `VectorPainter`；**不要**直接复用 launcher drawable，那是 108×108 且带底色）。
2. `BootScreen.kt:167-183` `StepBar` 保持两个 `Box` 的写法（KDoc `:160-166` 明确说明是为了避免 `LinearProgressIndicator` 的 API 变化 —— **这是刻意的，不要"顺手改成标准组件"**）。颜色 `surfaceContainerHighest` + `primary` 保持。
3. `BootScreen.kt:118-138` 错误卡 `errorContainer`/`onErrorContainer` 保持（M3 槽位来自 pi，§2.4 第 3 条）。
4. 新增第三态展示（`04 §4` 要求 `引擎没有在运行` 有独立视觉）：`Boot.Failed` 分支(`:109-153`)补一条**三重编码**的错误状态行（符号 `✗` + `引擎没有在运行` + `error` 色）。
5. `BootScreen.kt:74,86,116` 三个标题的字号都走 `MaterialTheme.typography.titleMedium`；`:91` 的 `boot.step.label` 用 `primary`（accent）保留（它是这屏唯一活跃的东西）。

---

### 3.10 扩展对话框（`ExtensionDialogs`）

**现状**：`ui/extension/ExtensionDialogs.kt:59-340` —— `ExtensionDialogHost:59`、`SelectDialog:81`、`ConfirmDialog:141`、`InputDialog:171`、`EditorDialog:220`、`DialogHeading:266`、`CountdownBar:295`、`TimeoutNote:311`。

**目标**：**协议与文案一字不改**（`允许`/`拒绝`/`确定`/`取消`、`还有 N 个扩展对话框在排队`、超时说明都是 pi 的语义）。只做视觉与编码。

**改动清单**
1. `ExtensionDialogs.kt:266-293` `DialogHeading`：标题 + `(5s)` 倒计时。v2 三重编码 → 倒计时数字用 `PiTextStyles.numeric`（`PiTheme.kt:374`，= `mono`）保证秒数不抖。
2. `ExtensionDialogs.kt:295-310` `CountdownBar`：轨道色 `borderMuted.copy(alpha = 0.35f)`(`:306`) 保持；**进度条**改为 1px 高的 `Box`（现在是多高 → 需读该函数确认；若已是细条则不动）。
3. `ExtensionDialogs.kt:141-170` `ConfirmDialog`：`允许` = `primary`（accent），`拒绝` = 默认。保留 `允许` 为 accent，`拒绝` 改为中性 `onSurfaceVariant` 字（现在若是 `error` 色，**不要**动 —— 那是 pi 语义）。
4. **排队提示** `还有 N 个扩展对话框在排队`(`:279`)：加 `StateChip` 形态。
5. `ExtensionUiHost.kt:79-113` Snackbar：
   - `:90-99` 三态底色映射（`cardBg`/`infoBg`/`toolErrorBg`）**不动**（pi 色）。
   - `:104` `actionColor = palette.accent` → Snackbar 的 `知道了` 用 accent 保留（瞬时元素，且它是唯一动作）。
   - `:69` `actionLabel = if (Error) "知道了" else null` —— 不动（`02-real-content.md` §7.5 已核）。
   - **新增**：Warning 态的 Snackbar 现在没有动作也没有符号；v2 要求三重编码 → 在 `message` 前加一个 tone 前缀符号（Info `·` / Warning `!` / Error `✗`）。⚠️ 这会改动**传给 Snackbar 的字符串**，而该字符串是 pi 的 `notify` 原文（`ExtensionUiHost.kt:68` `current.message`）→ **决策：不改 pi 的原文**，改为在 `Snackbar` 的 `content` 槽位里前置一个 `Text` 符号（Compose 的 `Snackbar` 有 `content` 重载）。若 API 不可用，则放弃这一条并记录在案。

---

### 3.11 会话树 / 其余覆盖层与 sheet

见 §3.6（会话树）、§3.5（会话列表）。底部 5 个 sheet + 1 对话框（`ui/chat/ChatSheets.kt`：`ModelPickerSheet:58`、`ThinkingPickerSheet:200`、`SessionToolsSheet:279`、`SessionStatsSheet:474`、`ForkPickerSheet:557`、`RenameSessionDialog:619`）：
- v2 **不改结构**（`04 §4` 没有把它们列入必须补齐的清单，只列了扩展对话框与 Snackbar）。
- 唯一改动：`ChatSheets.kt:238` 的思考等级徽章 `thinking(level).copy(alpha = 0.18f)` → 换成 `StateChip`（与 `ChatScreen` 的思考 chip 同一实现）。
- `ChatSheets.kt:479,463,110,216,579` 的 `heightIn(max = 560/420/560/420/420dp)` → `PiLayout.kt` 常量。

---

### 3.12 其他小面

| 面 | 位置 | 动作 |
|---|---|---|
| `SlashPalette` | `ui/chat/SlashPalette.kt:45-247` | 来源徽章 `alpha = 0.16f`(`:148`) → `StateChip`；`tonalElevation = 2.dp`(`:56`) → **删**（v2 禁阴影/tonal 抬升，改 1px 线） |
| `MentionPalette` | `ui/chat/MentionPalette.kt:45-94` | 同上（`tonalElevation = 2.dp` `:54`） |
| `BashPanel` | `ui/chat/BashPanel.kt:46-137` | 同上（`tonalElevation = 2.dp` `:59`）；状态行(`:103-106`)补符号（`运行中 …` / `已取消 !` / `成功 ✓` / `失败 ✗`） |
| `ExtensionStatusRow` | `ui/extension/ExtensionChrome.kt:47-79` | 分隔符 `·`(`:64-68`) 与 `accent` 文字色(`:73`) → 预算规则已撤回：不强制改。若要改，判据是「扩展状态行是否长期占着 accent 却什么也没发生」，而不是预算 |
| `ExtensionWidgetStack` | `ui/extension/ExtensionChrome.kt:90-126` | widget 卡边框 `borderMuted.copy(alpha = 0.4f)`(`:107`) 保持；`tonalElevation` 无 → 不动 |
| `PiEmptyState` | `ui/components/PiCommon.kt:224-268` | 圆形 `Surface` + 图标 → 换成 π 字形（`PiMark`），与 Boot 一致 |
| `PiSectionHeader` | `ui/components/PiCommon.kt:272-284` | `labelMedium` + `primary` 色 → **保留**（这是列表分组的标准，也是 accent 的合法使用点之一） |

---

## 4. 排版（中文排印在 Compose 里怎么落实）

### 4.1 现状（`00-screen-inventory.md` §6.1）

- `PiTextStyles.meta` = `FontFamily.Default`，`11.5sp/16sp`（`PiTheme.kt:349-354`）。
- `PiTextStyles.mono` / `monoSmall` = `FontFamily.Monospace`，`13sp/20sp` 与 `11.5sp/17sp`（`:355-364`）。
- `numeric` = `mono`（`:374`，注释说明是因为 `FontFeatureSetting("tnum")` 在支持的 API 上不可用）。
- 全 App **没有 `res/font/` 目录**（实测：`app/src/main/res/` 只有 `drawable`/`mipmap-anydpi-v26`/`values`/`xml`）。
- 人类文本 vs 机器输出的分裂规则在 `BlockChrome.kt:75-109`（`MonoText`/`ProseText`）。

### 4.2 目标与落地方式

| 项 | 做法 | 依据 |
|---|---|---|
| **fallback 链西文在前中文在后** | Compose 没有 `font-family` 列表字符串，但有等价的 `FontFamily` 组合方式：用 `FontFamily(Font(...), Font(...))` 把多个 **打包字体**按顺序排成 fallback 链；系统字体则在 `FontFamily.Default` 后由系统兜底 | `01-design-spec.md:102` |
| **中文字体家族数** | 全 App 最多 **2 个**：正文用 **Noto Sans SC**（或设备已装的 `system-ui` 中文），display 用 **Noto Serif SC** | `01-design-spec.md:109`、`brand-spec.md` §4 |
| **`font-synthesis: none`** | Compose 没有直接的合成开关；等价做法是**永远不要**用 `FontWeight.Bold`/`FontStyle.Italic` 去「造」一个没打包的字重。**斜体思考正文**（`ThinkingBlockBlock.kt:64,84`）是当前唯一的 faux italic 风险点 —— 必须给中文正文打包或用 `FontStyle.Italic` + 一个真有斜体的字体，否则中文会变形 | `01-design-spec.md:103` |
| **避头尾 / `line-break: strict`** | Compose 无直接等价；`TextStyle(lineBreak = LineBreak.Paragraph/Heading)` 是 Compose 1.7+ 的 API。**不确定**：本项目 Compose 版本是否 ≥1.7（需读 `gradle/libs.versions.toml` 的 `composeBom`）。若可用，正文用 `LineBreak.Paragraph`；不可用则本项记为「做不到，接受现状」 | `01-design-spec.md:103` |
| **`overflow-wrap: anywhere`** | `SoftwareKeyboardController` 无关；Compose 的等价是让长 token 可断行：`TextStyle` 无法直接控制，靠 `Text` 默认断词 + 不要给它 `maxLines = 1`。工具卡正文已经是多行的，无需额外处理 | `01-design-spec.md:103` |
| **引号用「」** | 纯文本规则，代码里已在用（`SessionsScreen.kt:171,274,276`） | 仓库硬规范 |
| **数字等宽 `tabular-nums`** | **不是**在 `PiTextStyles` 里加 `fontFeatureSettings`，而是**所有数字继续走 `PiTheme.text.numeric`（= mono）**。当前 `numeric` 只被…（实测无独立调用点，各处直接写 `PiTheme.text.meta`）→ v2 要把 `耗时/退出码/行数/token/费用/秒数` 六类改成 `numeric` | `01-design-spec.md:105`、`PiTheme.kt:369-374` |
| **字号档位 ≤6** | 现有：`11.5 / 12 / 12.5 / 13 / 14 / 15 / 16 / 17 / 18 / 20 / 22 / 28`（`PiTheme.kt:349-364,457-492` + `PiMarkdownTheme.kt:222-247`）→ **已经超过 6 档**。v2 的处置：**不动 markdown 的 6 级标题**（那是 pi 语义），把 App 自有角色收敛为 `micro 11.5 / meta 12 / body 14 / mono 13 / monoSmall 11.5 / title 17` 六档 | `01-design-spec.md:106` |
| **中文正文 0–0.05em 字距；标题 0** | `TextStyle(letterSpacing = …)`。Compose 的 `letterSpacing` 单位是 `TextUnit`（可用 `.em`）。中文正文加 `0.02.em`，标题 `0.em` | `01-design-spec.md:106` |
| **行高** | 现有 `bodyLarge 15/23`(1.53)、`meta 11.5/16`(1.39)、`mono 13/20`(1.54)、`monoSmall 11.5/17`(1.48)，均偏紧但符合中文正文 1.4–1.6 的区间 | `PiTheme.kt:349-364,468-491` |
| **中文大字的张力** | 用**同族字重对比**（如 Noto Serif SC 900 压 300）。当前 App 没有任何 900/300 的使用 → v2 在 **Boot 的 `准备启动本地引擎`** 与 **设置首页的「当前模型」卡** 两处引入一次字重对比（display 场景） | `01-design-spec.md:107` |

### 4.3 等宽字该不该随包打包？—— **该打包，而且这是必须项**

**当前用系统 `FontFamily.Monospace` 的具体代价**（都能在源码里指出来）：

1. **`FontFamily.Monospace` 在 Android 上解析到的是设备 OEM 的 monospace**（AOSP 是 Droid Sans Mono，各家不同），所以同一份 diff/行号在不同手机上**列宽不一样** —— `DiffBlock.kt:247` 的「30dp 行号列」与 `symbolColumn 16dp`（`PiSpacing.lineNumberColumn`/`symbolColumn`）是按某个字宽算的，换机就会错位。
2. **中文会掉出 mono 回退**：`FontFamily.Monospace` 没有 CJK 字形，路径/命令里出现中文时（`grep` 的命中行、`read` 的文件正文都可能含中文）会回退到系统 sans，**等宽列立刻断掉**。这正是 `01-design-spec.md:108` 说的「机器语言层必须等宽」。
3. **`monoSmall 11.5sp` 在系统 monospace 上偏小**：等宽字体 x-height 通常更小，11.5sp 的系统 mono 在 412dp 屏上已经接近可读下限（`01-design-spec.md:21` 要求标签 ≥12px）。
4. **`numeric` = `mono` 的取舍是被迫的**（`PiTheme.kt:369-374` 注释原文：「`FontFeatureSetting("tnum")` is unavailable on all API levels we support via `FontFamily.Default`」）。打包一个带 `tnum` 的字体后，这个取舍可以解除，但**本轮不建议解除**（会让所有数字行重新排一次版，风险大于收益）。

**建议**：打包 **JetBrains Mono**（OFL-1.1，与 pi 的终端气质一致，且 codelate 量小）到 `app/src/main/res/font/`，`PiTextStyles.mono/monoSmall` 改成引用它。APK 体积代价可忽略（现有 APK 上限 200 MiB，见 CI 的 `apk_bytes` 检查；一个 variable mono 约 200–400 KB）。⚠️ **不确定**：`brand-spec.md` §4 只规定「可以上 JetBrains Mono / Geist Mono」，**没有规定必须打包**；这一条是工程建议，执行者需与用户确认是否接受打包（它会让 `res/font` 从零变一，且字体许可证要进 `README`/许可页）。若用户不同意打包，**fallback 方案**是保持系统 mono，但把 `DiffBlock` 的列宽从固定 dp 改成 `rememberTextMeasurer` 实测字宽（改动更大，不推荐）。

---

## 5. 不做什么（本轮的边界）

执行者请把这一节当**禁止清单**。以下每一项都是「看起来应该顺手做，但本轮不做」：

1. **pi 取色/变色**：`PiPalette` 的 56 字段、`PiContrast` 的 5 个派生 token、`PiPalette.colorScheme()` 的全部槽位（含 `surfaceContainer*` 的 lerp）、`piMarkdownColors`/`piAlertColors`、`PiSyntaxToken.color`、diff 三色。逐条见 §2.4。
2. **RPC 协议**：`rpc/src/main/kotlin/app/pi/rpc/**`（`Transcript.kt`、`PiResponses` 等）、`PiSessionViewModel` 的事件归约逻辑、`NavRequest` 之外的状态机。**只允许改 `NavRequest` 的成员**。
3. **终端内部**：`TerminalPane.kt` 的网格/字体/`VTermKey`/PTY 生命周期、`TerminalBridge.kt`、`TerminalSettings.kt`、`TerminalPalette.kt` 的两色、libvterm 的 xterm 16 色。**`TerminalKeyBar.kt` 也不动**（用户裁决：终端是废品功能，本轮零视觉工作）。参见 §3.8。
4. **扩展系统**：`extension/ExtensionUi.kt` 的协议模型、四个对话框的按钮文案与语义、排队/超时逻辑、`ExtensionUiHost` 的单例挂载约定（`PiRoot.kt:287-295` 的 KDoc 是硬约束：两个宿主 = 两个对话框）。
5. **设置注册表**：`PiSettingsRegistry.kt`（1495 行）的键、标题、说明、`defaultValue`、`EffectiveKind`。**一个键都不许删、不许改名**（`PiSettingsAuditCheck` 会挂 —— 见 §6 第 0 条）。
6. **markdown 渲染管线**：`render/PiMarkdown.kt`、`render/PiMarkdownComponents.kt`、`render/PiCodeHighlight.kt`、`render/PiLatex.kt` 的**逻辑**（只允许改 `PiMarkdownTheme.kt` 里与颜色无关的尺寸）。
7. **纯逻辑模块**：`ui/chat/PiFileMentions.kt`、`ui/chat/TailFollow.kt`、`ui/chat/QueueRestore.kt`、`ui/blocks/ToolOutputParse.kt`、`ui/chat/PiSlashCommands.kt` —— 它们被纯 JVM harness 直接编译执行（§6），**改签名 = 改 harness**。
8. **不新增数据**：git 改动、任务列表、待办、plan、子代理、连接状态一律不发明（`01-design-spec.md:97`、`02-real-content.md:46-51`）。
9. **不做动效**：`PiMotion` 删除而不是启用；不加 `AnimatedVisibility` 的新调用点；`render/PiMarkdown.kt:184-185` 的关闭库动画**保持关闭**。
10. **不做渐变/模糊/辉光/阴影**：删掉现存唯一的 `shadowElevation`（`ChatScreen.kt:1059`）与三个 `tonalElevation = 2.dp`。

---

## 6. 风险与实施顺序

### 6.0 先读这一段：三个「改一处牵动多屏」的高风险点

**风险 1 · `LazyColumn` 的 item key / contentType / 窗口化（最高）**
- 位置：`ChatScreen.kt:934-1044`（`key = item.key`、`contentType = item::class`、`:397` 的 `renderWindow`、`:980-983`）。
- 为什么危险：块渲染的一行改动（比如给 `ToolHeader` 套一层 `Row`）不会破坏编译，但会让 `contentType` 的槽位复用失效；`renderWindow` 与 `hiddenCount` 的**全列表索引 ↔ 渲染索引**换算（`:988-989`、`:809`、`:817`）是 F34 修过的老 bug，任何「在列表里加一个辅助行」的冲动都会把它带回来。
- 防护：**禁止在 `LazyColumn` 里新增辅助 item**；新增的 `StateChip`/`DurationMeter` 必须是块**内部**的 Composable；`key`/`contentType` 表达式一字不改。

**风险 2 · `ChatScreen` 1936 行里的输入区与滚动跟随（高）**
- 位置：`ChatBody` `listState`(`:219`)、`Composer`(`:1656-1787`)、`reArmTail`/`pauseTail`（`ui/chat/TailFollow.kt`，由 `TailFollowCheck` 守护）、`Spacer(bottomInset + 8.dp)`(`:1307`)。
- 为什么危险：底栏从四项变三项 + 覆盖层打开时 `Scaffold` 的 `padding` 变化 → `contentPadding.calculateBottomPadding()` 的值变了；`bottomInset`(`:180`) 同时喂给 `BootScreen`(`:186`) 与末尾 `Spacer`(`:1307`)。改错会让「回到最新」在键盘弹出时偏移，或让 composer 被键盘盖住（`PiRoot.kt:121-129` 的 KDoc 记录了 `docs/known-gaps.md §M2` 就是这类事故）。
- 防护：**先做 §3.1（导航）再回来动 ChatScreen**；导航批次结束后必须在设备上确认「键盘弹出 → composer 与底栏都在键盘上方、`回到最新` 不被顶掉」。

**风险 3 · markdown 主题映射（高）**
- 位置：`ui/render/PiMarkdownTheme.kt:136-151`（`piMarkdownColors`）、`:178-186`（`piAlertColors`）、`:212-248`（`piMarkdownTypography`）、`:279-290`（padding）、`:315-324`（dimens）。
- 为什么危险：这份文件同时承载**颜色红线**（§2.4 第 4 条）和**尺寸可改**两部分，两者的行号混在一起。按「统一圆角/统一间距」的直觉扫一遍这个文件，极可能顺手改到 `piMarkdownColors`。另外 `:212-248` 的 6 级标题字号是 pi 的语义（`h1 22/30`…`h6 14/21`），不是 App 的档位。
- 防护：**改这个文件时逐行对照本计划 §2.4 第 4 条**；只允许改 `:279-290`/`:315-324` 里与颜色无关的数值；`piMarkdownTypography` 只允许改 `inlineCode`/`table` 的 12.5sp 一处（为了「字号 ≤6 档」），其余不动。

**其余高风险（中）**
- `PiShapes.card` 从 16dp 收紧会**波及 `UserMessageBlock`**（`BlockChrome.kt:174` 的 `BlockCard` 与 `UserMessageBlock.kt` 的 `PiShapes.card`）→ 见 §3.3 最后一行，建议气泡单独保留 16dp。
- `PiRoot` 的 `BackHandler` 与会话列表/树/终端三个覆盖层各自的 `BackHandler` **只能存在一处**。这是最容易做出「按一次返回关了 App」的地方。
- `rememberSaveable` 存 `ordinal`（`PiRoot.kt:55`）→ 目的地从 4 变 3 后必须改存 `name`。

### 6.1 校验基线：这个仓库没有本地编译

- **本机（这台手机）禁止**：Gradle、编译器、`git` 命令、subagent。**CI 是唯一的编译器。**
- CI 五个 job（`.github/workflows/ci.yml`）：
  1. `protocol` — `./gradlew :rpc:test`（`:rpc` 单元测试）。
  2. `apk` — `:app:assembleRelease` 的两个 targetSdk 变体（`sideload28` / `modern36`）+ 运行时载荷校验 + 许可资产校验。**UI 代码的编译错误在这里第一次出现**，且失败会被 `Report Kotlin errors as annotations` 步骤转成 `::error::` 注解（`:136-142`）。
  3. `pure-checks` — `tools/run-app-pure-checks.sh`，用 Maven 上的 kotlinc 编译并运行 `app/src/test/**` 的 harness **加上它们各自的 main 闭包**。
  4. `contract` — `node tools/pi-contract.mjs`，断言 App 对**钉住版本引擎**的假设。
  5. `comments` — `python3 tools/check-nested-comments.py`（Kotlin 块注释会嵌套，这个仓库已经为此付过五次 CI 周期的代价）。
- 另有 `tools/typecheck.sh`：**不在 CI 里**，但它是本机唯一可用的「快速类型检查」（不需要 AAPT2）。⚠️ 用户的规矩是**本机不许跑编译** —— 执行者在自己那台机器上是否允许运行，请以当前任务书为准；**本计划的建议是：只在 CI 里跑，本机只跑不编译的检查**（`python3 tools/check-nested-comments.py`、`bash -n tools/*.sh`、`node tools/pi-contract.mjs`）。

**`pure-checks` 直接编译的 UI 相关文件（改动它们 = 必须同步看 harness）**：
| harness | 守护的生产文件 | 对本次改造的含义 |
|---|---|---|
| `ui/chat/PiFileMentionsCheck.kt` | `ui/chat/PiFileMentions.kt` | 不动 |
| `ui/chat/TailFollowCheck.kt` | `ui/chat/TailFollow.kt` | **滚动跟随的守护者**；动 `ChatScreen` 的滚动前先读它 |
| `ui/chat/QueueRestoreCheck.kt` | `ui/chat/QueueRestore.kt` | 不动 |
| `ui/settings/PiSettingsAuditCheck.kt` | `ui/settings/**`（含 `PiSettingsRegistry.kt`） | **设置注册表一个键都不许删/改**，否则这个 job 红 |
| `ui/blocks/ToolOutputParseCheck.kt` | `ui/blocks/ToolOutputParse.kt` | 工具卡改造**不许改这个文件的签名** |

### 6.2 建议批次（8 批，每批可单独过 CI）

> 排批原则：**先动「没有消费者会痛」的，再动结构，最后动块渲染**。每批一次 push、一次 CI 观察，不要合并批。

| 批 | 内容 | 为什么在这个位置 | 主要验证 |
|---|---|---|---|
| **B0 · 令牌清理** | 删 `PiElevation`/`PiIcons`/`PiMotion` + 13 个零引用 `PiSpacing.*`；删 `PiRoot(isDark)`；`MainActivity.kt:44` 跟着改 | 纯删除，零行为变化；它让后面每一批的 grep 结果干净 | `apk` job（编译）+ `comments` job |
| **B1 · 新增视觉原语** | 新建 `PiMeter.kt` / `PiStateChip.kt` / `PiLayout.kt` / `PiMark.kt`；`PiTextStyles` 加 `numeric` 的消费约定；`PiSpacing` 加 4 个新 token | 后面每批都要用它们；先合入让并行 agent 有稳定 API | `apk` job；`PiMeter` 的 `meterSegments` 建议加一个小 harness（纯函数，可进 `pure-checks`） |
| **B2 · 导航三目的地** | `PiRoot.kt`（枚举/when/底栏/BackHandler/overlay 状态）、`PiSessionViewModel.kt` 的 `NavRequest`、`PiRoot.kt` 删 `isDark`（若 B0 未做） | **结构性改动必须先做**，否则后面每屏都在改一个还会变的宿主 | `apk` job；设备确认清单第 1–5 条 |
| **B3 · 工作区 + 终端入口** | 新建 `ProjectScreen.kt`（项目现场）与 `TerminalScreen.kt`（**薄包装，无视觉设计**，把 `WorkbenchScreen.kt` 现有三行版面原样搬过来 + 一个返回箭头）；删 `WorkbenchScreen.kt`；`PiSettingsStack` 加终端入口行（`SettingsHome.kt` 新节） | 依赖 B2 的宿主 | `apk` job；`pure-checks`（`PiSettingsAuditCheck` 不因新入口行而红 —— 新增的是 App 侧行不是注册表键）；设备清单第 6–8b 条 |
| **B4 · 会话列表覆盖层** | `SessionsScreen.kt` 覆盖层化（`onClose`/`onOpenTree`/不透明 Surface/BackHandler 归属）+ `ChatScreen` 的 AppBar 入口(A1/A2) | 依赖 B2（覆盖层状态） | `apk` job；设备清单第 10–13 条 |
| **B5 · 对话块渲染** | `ToolBlockChrome.kt`（`ToolHeader`→`StateChip`、`ToolFooter`→`DurationMeter`）、`BlockChrome.kt`（`BlockCard` 边框/圆角）、8 个工具卡的继承改动、`NoticeBlock`/`GrepBlock` 的符号 | 风险最高的视觉批，单独一个批次便于回滚 | `apk` job + `ToolOutputParseCheck` 必须仍绿；设备清单第 14–20 条 |
| **B6 · 设置视觉 + App 专属子页** | `SettingsGroupScreen.kt` 高亮行改 1px accent 线；`PiSettingsRows.kt` 行距；6 个 App 专属子页的裸 dp 清理（**`PiPackagesScreen.kt` 单独一次**）。**终端与 Shell 分组的 5 行只受「行距统一」影响，不做终端专属设计** | 与 B5 无耦合，可与 B5 并行（不同 agent） | `apk` job + **`PiSettingsAuditCheck` 必须绿**；设备清单第 21–24 条 |
| **B7 · 打字/排印与收尾** | `PiTextStyles` 的字体家族改造（若用户同意打包 mono）、`numeric` 的六类消费点、`PiTheme.kt` 的字距/字重对比、删除三个 `tonalElevation` 与 `shadowElevation`。**不含 `ui/terminal/**`** | 放最后：它会让全 App 的文字重新排一次版，必须在前面的结构都稳定后做 | `apk` job；**这一批 CI 覆盖最弱**，主要靠设备清单第 25–30 条 |

**并行建议**：B5 与 B6 可同时进行（文件集不重叠）。B2/B3/B4 必须串行（同一批文件）。B7 必须最后。

### 6.3 CI 覆盖不到的改动（必须靠设备肉眼）

CI 只能回答「编译过吗 / 纯逻辑对吗 / pi 契约还对吗」。以下改动**CI 一个字都不会说**：

- 所有布局（间距/圆角/对齐/溢出）：Compose 的 `Modifier` 组合错误只有跑起来才知道。
- `LazyColumn` 的槽位复用与滚动性能。
- 中文字形（fallback、faux italic、字距、标点避头尾）。
- 主题切换后的重绘（`00-screen-inventory.md` §7 第 10 条已列「未做运行时验证」）。
- 覆盖层的可点区域、`BackHandler` 的优先级、Snackbar 的位置。
- 键盘（IME）与 composer/底栏的关系。
- 对比度是否真的 ≥4.5:1（`PiContrast` 只保证 5 个派生 token，其他组合没算过）。

---

## 7. 验证方式

### 7.1 每批的验证动作（照抄执行）

```
# 1. 不编译的自检（本机可以跑，零风险）
python3 tools/check-nested-comments.py            # 必须 OK；新增 KDoc 里不要出现 /* 与 */ 的组合
bash -n tools/run-app-pure-checks.sh              # 只做语法校验
node tools/pi-contract.mjs                        # 只在改了 pi 相关假设时跑；本批通常不必

# 2. 推 CI（唯一的编译器），观察三个 job
#    apk         → 编译通过 + 运行时载荷/许可校验（后者与本轮无关，应当不变）
#    pure-checks → 五个 UI harness（TailFollow / QueueRestore / PiFileMentions / PiSettingsAudit / ToolOutputParse）
#    comments    → 注释嵌套
# 3. 设备上肉眼确认（§7.2 清单）
```

⚠️ **`comments` job 是本轮最容易踩的红线**：本仓库已经在 5 个不同文件上因为 KDoc 里写了「`ui/` 后跟两个星号」而吞掉整个文件。**新写的 KDoc 里不要出现 `/*`、`/**`、`*/` 的形状**（尤其引用路径与通配符时）。新增约 12 个文件 → 风险面变大。

### 7.2 设备确认清单（逐条可勾选）

> 建议在真机上按批勾选；每一批只勾它自己的那些条。**未勾完不得开始下一批。**

**B0/B1（令牌与新增原语）**
- [ ] 1. App 冷启动，四个原目的地里**不再有**「会话」和「终端」后仍能正常进入（B2 前先确认 B0/B1 没把 App 弄坏）
- [ ] 2. `Boot` 页的 π 字形有正确描边（不是方形/空白）

**B2（三目的地导航）**
- [ ] 3. 底栏是**三项**，点击全部可达；三项的触达高度 ≥48dp
- [ ] 4. **升级场景**：装上前一版、停在「设置」，升级到本批，启动后落在**对话**而不是错的目的地（验证 `ordinal` → `name` 的修复）
- [ ] 5. 键盘弹出时 composer 与底栏都在键盘上方；Snackbar 不被底栏遮住

**B3（工作区 / 终端入口）**
- [ ] 6. 工作区显示当前工作目录的可读名（**没有路径**）
- [ ] 7. 项目资源行的计数与实际目录一致（可在设备 shell 里数一遍）
- [ ] 8. 设置首页有「终端」节与入口行；点进去是全屏终端，且**返回箭头能回来**
- [ ] 8b. 设置分组「终端与 Shell」的 `shellPath`/`shellCommandPrefix`/`npmCommand`/`app.terminal.*` 五行**仍在、仍可读可编辑**

**B4（会话列表覆盖层）**
- [ ] 10. 对话页左上角点会话名 → 覆盖层出现；覆盖层**不透**（看不到下面的转录）
- [ ] 11. 覆盖层里按返回键 → **关掉覆盖层**，不是退出 App
- [ ] 12. 覆盖层 ↔ 会话树 可来回切换
- [ ] 13. 长按一行 → 三个动作；删除当前会话 → 确认键 disabled；新建会话 FAB **不被底栏遮住**

**B5（块渲染）**
- [ ] 14. 工具卡的刻度只出现在页脚，长度随耗时变化（用 `bash: sleep 1` / `sleep 12` 两条命令对比）
- [ ] 15. pending 工具卡的刻度只有 1–2 段、色 = `warning`；失败卡 = `error`；成功卡 = `success`
- [ ] 16. 每张工具卡的状态都同时有**字 + 符号 + 颜色**
- [ ] 17. `edit` 与 `diff` 仍是**两张卡**，没有合并
- [ ] 18. diff 的三色与行底 alpha 与改造前**完全一致**（截图对比）
- [ ] 19. 流式回答时同屏只有一个 accent（正在跑的块/流式光标），没有第二处在发光
- [ ] 20. 长会话滚动不卡；长度 >200 行的工具卡展开后仍不卡

**B6（设置）**
- [ ] 21. `/scoped-models`（或设置里搜 `enabledModels`）仍能定位并高亮那一行，高亮现在是 1px accent 左线
- [ ] 22. 6 种行型各点一次：Switch 就地生效、Value/Number/Text/List 打开对应 sheet、Action 弹确认
- [ ] 23. 4 种生效徽标（需重载/新会话/需重启引擎/需重启）点开都能解释
- [ ] 24. App 专属 6 页都能进能出；`扩展包与项目信任` 页不崩

**B7（排印）**
- [ ] 25. 中文正文没有 faux italic（思考块展开后看中文字形是否变形）
- [ ] 26. 数字列不抖（状态行 token/费用、diff 行号、耗时）
- [ ] 27. 长路径/长 URL 能断行，不撑破卡片
- [ ] 28. 标点不出现行首（避头尾）—— 若 Compose 版本不支持，记录为「已知做不到」
- [ ] 29. 浅色 pi 主题下同样可读（切 `theme` 设置到 light 再走一遍第 14–19 条）
- [ ] 30. 系统字体放大（设置 → 显示 → 字体大小最大）后没有裁切；`app.appearance.fontScaleDelta` 调整后同样

---

## 8. 交接备注（给并行 agent）

1. **本计划与 `00-screen-inventory.md` §5.4 的裸 dp 清单配套使用**：那一节给了每个文件的裸 dp 数量。清理时按文件认领，不要两个人清同一个文件。
2. **`PiPackagesScreen.kt`（934 行）与 `PiPackagesHost.kt`（694 行）没有被逐行核对过**（`00-screen-inventory.md` §7 第 1 条）。动它们之前必须自己读一遍。
3. **`00-screen-inventory.md` 与 `02-real-content.md` 里仍有「本应用」字样**（例：`02-real-content.md` 引用的 `PiSettingsRegistry.kt:1386`「由本应用固定」）。这些是**指代 App 自身**的地方，按新命名规则应改为「PI」；本计划不改这两个文档，但改到对应源文件时请一并改字面量。
4. **不要动 `design/` 下的其他文件**；本轮唯一产出是这份计划，接下来每一批的产出是代码改动。
5. **遇到与本文档冲突的旧文档**：`brand-spec.md` §6 的「不许出现终端页/终端字样」已被 `03-navigation-decision.md` 与 `04-direction-b-graft.md` 覆盖（见 §3.8 末尾），其余冲突以 `04` → `03` → `01` → `brand-spec` 的顺序裁决。
