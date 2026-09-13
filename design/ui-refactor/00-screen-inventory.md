# 00 · 屏幕与内容清点（UI 重设计的事实底稿）

> 事实来源：**仅当前源码**（`app/src/main/kotlin/app/pi/ui/**`、`app/src/main/kotlin/app/pi/MainActivity.kt`、`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt`）。
> 不引用 `docs/pi-android-ui-spec.md` 与 `docs/pi-android-app-design.md`（用户已声明过时）。代码注释里出现的 `docs/...` 只是注释文本，不作为色值/尺寸的事实来源。
> 行号均指当前工作区文件的实际行号。不确定处标 **不确定**。

---

## 1. 导航骨架

### 1.1 顶层目的地

- 枚举：`PiDestination`，`app/src/main/kotlin/app/pi/ui/PiRoot.kt:44-49`。四个，顺序即底栏顺序：

| ordinal | enum | label（原文） | icon |
|---|---|---|---|
| 0 | `PiDestination.Sessions` | `会话` | `Icons.Filled.Forum` |
| 1 | `PiDestination.Chat` | `对话` | `Icons.Filled.ChatBubble` |
| 2 | `PiDestination.Workbench` | `工作区` | `Icons.Filled.Terminal` |
| 3 | `PiDestination.Settings` | `设置` | `Icons.Filled.Settings` |

- 起始目的地：`PiDestination.Chat.ordinal`（`PiRoot.kt:55`，`rememberSaveable`）。
- 底栏：`Scaffold(bottomBar = { NavigationBar { … NavigationBarItem … } })`，`PiRoot.kt:120-142`。图标 `contentDescription = null`，label 用 `Text(item.label)`。
- IME：`Scaffold(modifier = Modifier.imePadding())`，`PiRoot.kt:130` —— 全 App 只此一处，`TerminalPane` 自己不再 pad。
- 目的地切换是 `when (current)`（`PiRoot.kt:145-269`），**没有 NavHost / 路由字符串**；导航状态是 `rememberSaveable` 的 Int + 若干 Boolean。
- 顶栏底栏尺寸无显式设定：`NavigationBar`/`NavigationBarItem`/`TopAppBar` 全用 Material3 默认（`PiSpacing.bottomBar = 64.dp`、`appBar = 56.dp` 均已定义但**未被引用**，`ui/theme/PiTheme.kt:47-48`）。
- **`PiRoot(isDark)` 的 `isDark` 参数当前未被函数体读取**（`PiRoot.kt:52-53`；`MainActivity.kt:44` 传入 `theme.dark`）——死参数，重设计时可直接删。

### 1.2 目的地之外的两类"面"

- **覆盖层（overlay，非第五个 tab）**：`treeOpen` → `SessionTreeScreen` 直接画在 Box 里（`PiRoot.kt:61, 275-285`），覆盖当前目的地；关闭方式是它自己的 `关闭` 图标（`SessionTreeScreen.kt:108-110`）。
- **全局宿主**：`ExtensionUiHost(session, snackbarBottomPadding = padding.calculateBottomPadding() + 8.dp)`，`PiRoot.kt:296-299`，挂在**目的地切换之外**，所以扩展对话框/Snackbar 在任何页面都能弹。全 App 只挂这一次（`ChatScreen`/`WorkbenchScreen` 的 KDoc 都写明自己不再挂第二份）。

### 1.3 程序化导航（`NavRequest`）

- 定义：`sealed interface NavRequest`，`ui/PiSessionViewModel.kt:121-144` —— `data object Sessions :122`、`Chat :123`、`Workbench :124`、`Settings :126`、`SessionTree :144`，以及 `data class SettingsFocus(val key: String) :141`。
- 状态在 `PiSessionViewModel.UiState.navRequest`，`PiRoot.kt:86-118` 消费后 `session.consumeNav()`。分支：`Sessions` / `Chat` / `Workbench` / `Settings` / `SettingsFocus(key)` / `SessionTree`（会先 `refreshTree()`）/ `null`。
- 消费者举例：`ChatScreen.kt:671-719`（slash 命令 action）、`PiRoot.kt:203-240`（设置 Action 行）。
- 所有目的地切换都会先 `treeOpen = false`。

### 1.4 返回行为

- 全局只有两处 `BackHandler`：`PiSettingsStack.kt:269-291`（搜索/分组/设备能力/扩展包/凭证/许可/模型/诊断逐层回退）与 `LicensesScreen.kt:140`（阅读许可证全文时回列表）。
- 其余靠系统默认：顶层目的地与**会话树覆盖层都没有 `BackHandler`**（`grep -rn BackHandler ui/` 只有上述两处）→ 覆盖层打开时按返回键会离开 App，而不是关掉覆盖层。**这是当前实现的既有行为，非推断。**
- 设置分组的返回走 `TopAppBar.navigationIcon` 的 `ArrowBack`（`SettingsGroupScreen.kt:113-119`），contentDescription `"返回"`。
- 聊天/工作区/会话三个顶层页的 `TopAppBar` **没有 navigationIcon**（不是下级页）。

---

## 2. 每个屏幕一节

### 2.1 BootScreen（启动/首次安装/引擎死亡）

- **源码**：`ui/screens/BootScreen.kt:46-158`（`StepBar` `:168-183`）。渲染入口：`ChatScreen.kt:182-189`（`state.boot !is Boot.Ready` 时顶替整屏）。
- **任务**：首次启动解包 Linux 运行时的进度、失败原因与重试；同时也是"引擎中途死亡"的落地页（`Boot.Failed`）。
- **版面（上→下，居中 `Column` + `verticalScroll`）**：圆形 `Surface` + `Icons.Filled.Memory` 图标 → 按 `Boot` 三态分支的内容 → 失败时错误卡（`errorContainer`）+ `重试` 按钮。
- **真实文案**：
  - `Boot.Idle`：标题 `准备启动本地引擎`；正文 `首次启动要准备 Linux 运行环境，需要几分钟。不需要 root.`（原文 `…，需要几分钟。不需要 root。`）；按钮 `开始`。
  - `Boot.Working`：标题 `正在安装运行时`；步名 `boot.step.label`；`步骤 {index+1} / {total}`；`只在首次启动时做一次，之后冷启动是秒级。`
  - `Boot.Failed`：标题 `引擎没有在运行`；卡片内 `boot.message` + `boot.detail`（等宽小字）；按钮 `重试`；页脚 `如果反复失败，把这个提示连同它下面的文字发给我。`
- **数据来源**：`Boot`（`ui/PiSessionViewModel.kt` 的 boot 状态机）+ `RuntimeProvisioner.Step`（`app/pi/runtime/RuntimeProvisioner.kt`）。
- **最重的视觉元素**：居中的状态块 + 4dp 手绘进度条（`StepBar`，`StepBar` 用两个 `Box` 而非 `LinearProgressIndicator`，注释说明是刻意避免 API 变化）。

### 2.2 SessionsScreen（会话列表）

- **源码**：`ui/screens/SessionsScreen.kt:85-294`；行 `SessionRow` `:298-365`；`groupLabel` `:379-385`；`relativeTime` `:391-402`。
- **任务**：列出 pi 会话目录里的会话、按 cwd 分组、切换会话、新建、导入、删除、从会话新建子会话。点击 = `switchSession` 后跳 `对话`。
- **版面**：`TopAppBar("会话")`（actions：`导入` TextButton、`Refresh` 图标 `刷新会话列表`）→ 搜索行（`OutlinedTextField` + `按时间/按名称` + `全部/仅命名`）→ 提示行 → 空态或 `LazyColumn`（每个 cwd 一个 `PiSectionHeader`）→ 右下 `ExtendedFloatingActionButton("新建会话")`。
- **真实文案**：
  - 输入框 placeholder：`搜索名称 / 目录 / 文件名`；排序按钮切换 `按名称` / `按时间`；筛选按钮切换 `仅命名` / `全部`。
  - 提示：`长按一行可删除该会话；当前会话要切换后才能删除。`
  - 空态 1：`还没有会话` / `会话按工作目录分组，这里会列出每一个目录的对话。`
  - 空态 2：`没有匹配的会话` / `换一个关键词，或关掉「仅命名」筛选。`
  - 行内：`当前` 徽章；副行 `{cwd 组名} · {model}`；第三行 `{n} 条` +（` · 分支`）+（` · 已命名`）；右侧相对时间 `刚刚` / `{n} 分钟前` / `{n} 小时前` / `{n} 天前` / `{n} 周前`。
  - 长按对话框：标题=会话名，正文 `对这个会话做什么？`，动作 `新建子会话`、`删除`、`取消`。
  - 删除确认：标题 `删除会话`；正文 `删除「{name}」？这个会话会被移除，无法恢复。` 或当前会话 `「{name}」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。`；按钮 `删除` / `取消`。
  - 分组名：本应用工作区显示 `工作区`，无 cwd 显示 `工作目录未记录`，其余取路径最后一段。
- **数据来源**：扫描 pi 会话目录（`PiSessionStore.Summary`，`app/src/main/kotlin/app/pi/session/PiSessionStore.kt`）＋ `get_state` 的 `meta.sessionFile`（判"当前"，`SessionsScreen.kt:117`）。导入走 SAF `GetContent` + `session.importSession`。
- **最重的视觉元素**：分组列表（`LazyColumn` 内嵌 section header），行高约 `12dp` 上下 padding，无卡片。

### 2.3 ChatScreen（对话 / 转录）

- **源码**：`ui/screens/ChatScreen.kt`。`ChatScreen` `:165-191`（boot 分流）、`ChatBody` `:198-1386`、`SearchBar` `:1434-1470`、`ModelChip` `:1512-1540`、`QueueRow` `:1554-1574`、`QueueChip` `:1577-1586`、`ExportDeliveryRow` `:1600-1641`、`Composer` `:1656-1787`、`KeyHint` `:1832-1841`、`AttachmentThumb` `:1853-1887`。
- **任务**：主舞台——读转录、发消息、跑 `!` shell、`/` 命令面板、`@` 文件提及、附件、搜索、队列、模型/思考等级切换、会话操作。
- **版面（上→下，`Column`）**：
  1. `TopAppBar`：标题两行 = 会话名（`state.meta.sessionName`，否则 `windowTitleOf`，空会话回退 `新会话` / `会话`）+ `session.engineLabel(state)`；actions = 模型 chip、搜索图标、重载图标、`MoreVert` 溢出菜单。
  2. `PiStatusLine`（32dp 状态行：token/缓存/费用/上下文占比）。
  3. `ExtensionStatusRow`（扩展 `setStatus`，横向滚动）。
  4. 条件：`SearchBar`。
  5. 主体 `Box(weight=1f)`：空态 `PiEmptyState` 或 `LazyColumn` 转录；`!following` 时右下 `回到最新` 浮起胶囊。
  6. `QueueRow`（队列计数 + `收回并编辑`）。
  7. `ExportDeliveryRow`（导出完成后）。
  8. `ExtensionWidgetStack(AboveEditor)`。
  9. `BashPanel`（有 `state.bash` 时）。
  10. `SlashPalette`（草稿以 `/` 开头且未输入空格）。
  11. `MentionPalette`（`@` 提及候选非空时）。
  12. 附件缩略图行 + `随消息一起发送`。
  13. `Composer`（多行输入 + 圆形按键条）。
  14. `ExtensionWidgetStack(BelowEditor)`。
  15. `Spacer(bottomInset + 8.dp)`。
- **真实文案（AppBar/溢出菜单，`ChatScreen.kt:764-858`）**：`命令面板`、`会话与队列`、`会话树`、`会话信息`、`新建会话`、`从历史消息分支`、`复制当前会话`、`重命名`、`导出会话（按扩展名）`、`跳到上一条提问`、`跳到下一条提问`、`复制最后一条回复`、`收起全部工具输出`/`展开全部工具输出`、`循环切换模型`、`思考等级…`、`循环切换思考等级`、`选择模型…`、`打开终端（输入 pi 进原版 TUI）`。图标 contentDescription：`关闭对话内查找`/`在对话里查找`、`重载扩展、技能与主题`、`更多`。
- **真实文案（其余）**：
  - 空态：`引擎正在启动` / `首次启动要加载 pi 的运行时与扩展，通常要几十秒。\n现在发消息也可以：引擎开始工作后会立刻处理。`；`引擎已就绪` / `pi 会读写你选定的工作区、执行命令、改代码。\n输入 / 查看全部命令，输入 ! 直接跑 shell 命令。`
  - 顶部加载行：`加载更早的 {n} 条`（点击/滚到顶都加载）。
  - 回到最新：`回到最新` / `回到最新 · {n}`。
  - 队列：`穿插 {n}`、`后续 {n}`、`收回并编辑`。
  - 导出行：`会话已导出` + 文件名 + `保存到 Download`、`分享`、`关闭`。
  - 附件提示：`随消息一起发送`；输入框 placeholder `输入消息，/ 选命令，! 直接跑命令，@ 提及文件`；发送/停止 `发送`/`停止`。
  - 按键条 chip：`/`、`!`、`!!`、`@`、`图片`、`编辑器`、`终端`、`后续`（仅 streaming 时出现）、思考 chip `◐ {等级中文}`。
  - 搜索栏：placeholder `在对话里查找`；计数 `0/0` 或 `{i}/{n}`；`上一个匹配`、`下一个匹配`、`关闭查找`。
  - 通知类（`session.notifyUser`）：`外部编辑器没有改动内容，草稿保持原样。`、`外部编辑器没有返回可读的文本，草稿保持原样。`、`没有应用能编辑文本，草稿保持原样。`、`只能附加图片：所选文件的类型是 {mime}。请回到选择器换一张图片。`、`读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。`、`图片太大，上限是 {n} MB；它要整段随消息发送。请先压缩或裁剪后再试。`
- **数据来源**：RPC 事件流归约出的 `UiState`（`ui/PiSessionViewModel.kt`）：`transcript`（`TranscriptItem` 14 类）、`meta`（`get_state`）、`stats`（`get_session_stats`）、`lastUsage`、`commands`（`get_commands`）、`models`（`get_available_models`）、`mentions`、`extensionStatuses`/`extensionWidgets`/`extensionDialog`、`bash`、`queueSteering`/`queueFollowUp`、`exported`、`composerFill`、`prefs`（读 pi 的 `settings.json`）。
- **最重的视觉元素**：长转录列表（`LazyColumn` + 14 种 block），默认只渲染最后 `TRANSCRIPT_WINDOW_STEP = 50` 行（`ChatScreen.kt:1814`）。

### 2.4 WorkbenchScreen（工作区/终端）

- **源码**：`ui/screens/WorkbenchScreen.kt:61-78`，主体是 `TerminalPane`。
- **任务**：跑原版 pi TUI/普通 shell 的 PTY 终端（`ui/terminal/TerminalPane.kt:85-` 打开 `TerminalBridge`，离开该页 `DisposableEffect` 关进程）。
- **版面**：`TopAppBar("工作区")` → 一行说明 → `TerminalPane(weight=1f)`。终端内部：终端网格 `Box(weight=1f)` + 底部 `TerminalKeyBar`（含状态行文本）。
- **真实文案**：`输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。`；按键名 `ESC`/`TAB`/`CTRL`/`ALT`/`↑`/`↓`/`←`/`→`/`HOME`/`END`/`PGUP`/`PGDN`/`/`/`-`/`|`/`~`/`^C`/`^D`，动作 chip `PASTE`、`重开`（`TerminalKeyBar.kt:95-119, 231-232`）；终端状态文本 `已复制到剪贴板（OSC 52）`、`终端未能启动：{error}`（`TerminalPane.kt:119, 185`）。
- **数据来源**：PTY（`runtime/PtyLauncher`、`runtime/PtySession`）+ `app.terminal.*` 设置（`TerminalPreferences.read`）+ pi guest 环境的 shell。
- **最重的视觉元素**：整屏终端网格（等宽文本）。深/浅由 `MaterialTheme.colorScheme.background.luminanceIsDark()` 决定（`TerminalPane.kt:87`）。

### 2.5 设置（PiSettingsStack，一个目的地内的多级）

- **源码**：`ui/settings/PiSettingsStack.kt:57-474`。级 0 `SettingsHome` `:52-163`；级 1 `SettingsGroupScreen` `:46-`；级 2 `SettingsSearchScreen` `:55-`。
- **任务**：读写 pi 的 `settings.json` 等文件、App 自己的外观项、以及若干 App 专属页面（设备能力/扩展包/厂商凭证/模型/许可/诊断）。
- **版面**（`when` 分支，`PiSettingsStack.kt:300-415`）：`licenses` / `diagnostics` / `credentials` / `models` / `packages` / `deviceCapabilities` / `searching` / `currentGroup != null` / else `SettingsHome`。
- **级 0 `SettingsHome` 版面**：`TopAppBar("设置")` + 搜索图标 `搜索设置` → `SearchEntry`（`搜索设置` + `{n} 项`）→ `CurrentModelCard`（`当前模型` + `{model} · ◐ {level}`）→ `PiSectionHeader("设备")` + `DeviceCapabilityEntryRow` → `PiSectionHeader("扩展")` + `PiPackagesEntryRow` → `PiSectionHeader("关于")` + `PiLicensesEntryRow` → `PiSectionHeader("全部设置")` + 12 个 `GroupEntry` → 页脚 `共 {n} 项设置。搜索同时匹配标题、说明与字段名，输入 reserveTokens 或 /compact 都能直达。`
- **12 个分组（标题原文，`PiSettingsRegistry.kt:1356-1404`）**：`模型与推理`、`消息与网络`、`上下文与压缩`、`重试与网络`、`工具`、`会话`、`扩展与资源`、`外观`、`终端与 Shell`、`安全与信任`、`运行时与诊断`、`隐私与关于`。每个分组带一行动态摘要（如 `默认 {model} · ◐ {level}`、`穿插：{x} · 后续：{y}`、`主题 {theme}`、`保活：{x}`、`遥测：{x}`）。
- **级 1 `SettingsGroupScreen` 版面**：`TopAppBar(分组标题)` + 返回箭头 → `LazyColumn`：`PiSectionHeader(section)` 与 `PiSettingRow` 交替 → 末尾 `Spacer(PiSpacing.unit)`。高亮行（`highlightKey`）背景 `secondaryContainer.copy(alpha = 0.35f)`（`:135-142`）。
- **行种类（`PiRowKind`，`PiSettingsRows.kt:42-92`）**：`Switch`（`PiSwitchRow`）、`Action`（`PiActionSettingRow`，带 `dangerous`）、`Value`/`Number`/`Text`/`List`（都用 `PiValueRow` + chevron）。
- **编辑器（`PiSettingsEditors.kt`）**：`PiEffectiveDialog` `:73`、`PiOptionPickerSheet` `:134`、`PiNumberEditorSheet` `:255`（滑块 + 数字输入）、`PiTextEditorSheet` `:364`、`PiListEditorSheet` `:443`、`PiThemeEditorSheet` `:619`（主题挑选，含自动配对 `light/dark`）。
- **生效时机徽章文案**：`需重载`、`新会话`、`需重启引擎`、`需重启`（`PiCommon.kt:374-380`）；徽章解释对话框（`PiSettingsEditors.kt:78-130`）：标题 `需要重载`/`需要新会话`/`需要重启引擎`/`需要重启 App`，正文 `「{title}」{explanation}`，动作 `立即重载`/`新建会话`/`重启引擎`/`重启 App`、`稍后`、`知道了`。
- **分组/搜索/确认对话框文案**：Action 行确认 `执行`/`确认执行`/`取消`/`知道了`；无 dispatcher 时正文追加 `这个入口当前不可用。`（`SettingsGroupScreen.kt:222-256`）。
- **重启引擎对话框（`PiSettingsStack.kt:422-473`）**：标题 `重启引擎`；正文 `现在有回合正在运行。重启会终止模型调用、工具调用与正在跑的命令，它们都不会恢复；已写入磁盘的会话不会丢失。` 或 `重启会终止正在进行的回合，已写入磁盘的会话不会丢失。`；按钮 `重启`/`取消`；结果对话框按钮 `知道了`；未接线文案 `重启未接入：设置页还没有拿到引擎的重启入口。文件与设置都已保存。`；成功 `引擎已重启，新的进程设置已生效。`
- **级 2 `SettingsSearchScreen` 版面**：`TopAppBar("搜索设置")` + 返回 → 搜索框（label `标题 · 说明 · 字段名`，自动聚焦）→ 空白时 `SearchHints` 示例 chip → `{n} 条结果` + 结果行；空态 `没有匹配的设置` / `试试字段名（reserveTokens、sessionDir），或者斜杠命令（/compact、/tree）。`
- **数据来源**：`PiSettingsStore`（读写 pi 的 `settings.json` 等文件，`settings/PiSettingsFileStore.kt`）；只读运行时行来自 `RuntimeFacts`（`settings/RuntimeFacts.kt`）经 `valueOverrides` 注入；外部改动监视 `PiDirectoryWatch`（`settings/PiFileWatch.kt`），监视名单 `settings.json`、`models.json`、`auth.json`、`models-store.json`、`trust.json`、`extensions`、`skills`、`prompts`、`themes`（`PiSettingsStack.kt:482-492`）。
- **最重的视觉元素**：长列表行（标题 + 说明 + 尾值/开关/徽章），无卡片；编辑器是底部 sheet。

### 2.6 DeviceCapabilityScreen（设备能力）

- **源码**：`ui/device/DeviceCapabilityScreen.kt:93-`（入口行 `DeviceCapabilityEntryRow` `:358-`）。从设置进：`PiSettingsStack.kt:360-364`。
- **任务**：给 pi 授予这台手机的桥接能力（无障碍桥、Shell 策略、SAF 目录、Shizuku、运行时权限），并显示本会话审批记录。
- **版面**：`TopAppBar("设备能力")` + 返回 + 刷新图标（`刷新状态`）→ `LazyColumn`：`设备桥` 卡片 → `能力授权` 说明 → 每个 `DeviceCapability` 一张卡 → `Shell 策略` → `本会话的审批` → 页脚审计说明。
- **真实文案**：节标题 `设备桥`、`能力授权`、`Shell 策略`、`本会话的审批`；按钮 `启动设备桥`、`前往系统设置`、`撤销`、`授权目录`、`授予存储权限`、`请求 Shizuku 授权`、`授予定位权限`、`授予相机权限`、`授予通知权限`；说明 `能力默认关闭，逐组授权。「基础」组默认开启，因为它只做用户看得见的事（剪贴板、通知、打开链接、分享）。被关闭的能力不会静默失效：Agent 会收到明确原因，并被要求把原因和开启位置原样告诉你。`、页脚 `所有设备操作都会写入本地审计日志（不含内容本身），可在上面的状态卡里看到最近几条。紧急情况下可以直接关闭对应能力的开关，或停用系统的无障碍服务 —— 两者都会立刻生效。`；错误 `无法打开系统的无障碍设置页。请手动进入 系统设置 → 无障碍 → 已安装的服务，启用「pi 设备桥」。`、`无法发起 Shizuku 授权：Shizuku 没有在运行，或本机没有安装它。`、`这台设备的 Android 版本不需要旧式存储权限。`；权限显示名 `相机`、`位置信息`（`permissionLabel` `:933`）。
- **数据来源**：`DeviceCapabilityStore`（授权状态）、`DeviceBridgeController`（桥运行状态 + 审计尾）、`DeviceShizuku`、`DeviceSafStore`（目录授权）、`DeviceApprovalLedger`。轮询 1.5s，且用 `rememberPiScreenVisible()` 在不可见时停（`ui/PiScreenVisibility.kt:43-62`）。
- **最重的视觉元素**：能力卡片列表（每卡 = 标题 + 说明 + Switch + 若干按钮/状态徽章）。

### 2.7 PiCredentialScreen（厂商凭证）

- **源码**：`ui/settings/PiCredentialScreen.kt:101-`。入口 `PiSettingsStack.kt:318-330`，键 `app.credentials.apiKey` / `app.localModels.manage`。
- **任务**：选厂商 → 填 Key/BaseURL/api → 检测并扫描模型 → 勾选并保存（写 `models.json`/`auth.json`，并提示重启引擎）。
- **版面**：`TopAppBar("厂商凭证")` + 返回 → 可滚动 `Column`：可选两条错误 `Note` → 四个编号节 `1 选厂商`（RadioButton 列表）/ `2 粘 Key`（`API Key`、`Base URL`、`api（协议实现）`）/ `3 检测并扫描模型` / `4 勾选并保存`（模型勾选 + `手动模型名（逗号、空格或换行分隔）` 输入 + `保存` + 计数 `至少要勾选一个模型`/`已选 {n} 个`）→ `重启引擎` 按钮 → `说明`。
- **错误/提示文案**：`模型配置当前无法被 pi 解析：{e}。修好之前，写入的厂商不会生效。`、`凭证当前无法被读取，这个页面显示的厂商不是全部。修好之前 pi 不会启动。`、`支持图片输入`、重启不可用 `重启不可用：设置页还没有连上引擎。文件已经写好，重启 App 或引擎后生效。`、`有回合正在运行。重启会中断模型调用与正在执行的工具，所以这次没有重启；回合结束后再点一次。`、`当前没有待重启的资源变更（可能已经重启过）。`
- **数据来源**：`PiConfigFiles`（读 `models.json`/`auth.json`）、`PiProviderPresets`、`PiCredentialService`（写入/扫描）、`get_available_models`、`ExtensionLifecycle` + `EngineRestartCoordinator`。
- **最重的视觉元素**：表单（RadioButton 列表 + 多行输入 + 模型勾选列表）。

### 2.8 PiModelsScreen（模型）

- **源码**：`ui/settings/PiModelsScreen.kt:87-`。入口 `PiSettingsStack.kt:332-350`（键 `app.models.inventory`）。
- **任务**：把这台设备上配好的厂商/模型列出来、看每个模型能不能用、跳去改默认模型与循环范围、导入新厂商、必要时重启引擎。
- **版面**：`TopAppBar("模型")` + 返回 → `LazyColumn`：`Summary` 一行统计 → 若干 `Note`（`models.json`/`auth.json` 读不出、引擎未运行、待重启 `RestartCard`）→ `默认与循环`（`默认模型`、`循环模型（Ctrl+P）` 两个 `LinkRow` + 说明）→ `这台设备上配好的模型`（`ProviderCard` 可展开）→ `导入模型` 按钮 → `说明` → `Spacer(24.dp)`。
- **真实文案**：统计 `{p} 个厂商 · {m} 个模型 · 现在可用 {r} 个` +（` · 等待重启 {n} 个`）+（` · 缺凭证 {n} 个`）；`模型配置现在读不出来（{e}）。修好之前，里面写的模型都不会生效。`、`凭证现在读不出来（{e}）。下面「还没有凭证」的判断可能不准，pi 也可能起不来。`、`引擎现在没有运行，所以下面的模型都无法判断是不是已经能用。已经保存的配置不会因此丢失。`、`循环模型在引擎启动时确定，改完要重启引擎才会生效。`、`还没有配置任何厂商。点下面的「导入模型」选一个厂商、粘上 Key。`、`「pi 目录」是 pi 自带的模型定义；「手写申报」写在你的模型配置里；「覆盖」只改 pi 目录里的某几个字段。`、`一个厂商的模型一旦手写申报，就会替换这个厂商在 pi 目录里的同名条目；没动的厂商不受影响。`；链接 `修改`；重启卡 `有 {n} 个厂商已经配好了，但引擎还没有加载它们。`、`{ids} 下的模型要重启引擎之后才会出现在模型选择器里。已保存的配置不会丢失。`、按钮 `重启引擎`。
- **数据来源**：`PiModelInventory`（四份文件原文 + `get_available_models`）、`PiFileStamps`/`PiDirectoryWatch`（外部改动）、`ExtensionLifecycle`/`EngineRestartCoordinator`。
- **最重的视觉元素**：统计行 + 厂商折叠卡 + 模型行（含状态标签）。

### 2.9 LicensesScreen（开源许可）

- **源码**：`ui/settings/LicensesScreen.kt:128-262`；入口行 `PiLicensesEntryRow` `:79-125`；全文页 `LicenceTextScreen` `:215-`；`readManifest` `:266`。
- **任务**：列出随 App 分发的第三方组件与许可证，支持读全文；`PiSettingsStack` 的 `licenses` 分支。
- **版面**：`TopAppBar("开源许可")` + 返回 → `LazyColumn` 按 `section` 分组（`PiSectionHeader(section)` + 行）→ 点行进入全文页（`TopAppBar` + `BackHandler` 回列表）。
- **真实文案**：入口行标题 `开源许可`，副标题 `App 内分发的第三方组件、许可证原文，以及 GPL / LGPL 程序的源代码获取方式`，尾字 `打开`；行尾 `阅读`；加载中 `正在读取…`。
- **数据来源**：构建期生成的 licence manifest + `assets` 文本（`readManifest(context)`）。
- **最重的视觉元素**：分组列表 + 全文等宽/正文长文页。

### 2.10 DiagnosticsScreen（诊断报告）

- **源码**：`ui/settings/DiagnosticsScreen.kt:67-176`；报告正文 `ui/settings/DiagnosticsReport.kt`（节标题在 `:127-200` 附近）。
- **任务**：把引擎最后一次退出、运行时与载荷、关键路径、最近失败、设备信息汇总成一份纯文本，供保存/分享。
- **版面**：`TopAppBar("诊断报告")` + 返回 → 说明段 → `保存到 Download` + `分享` 按钮 → 状态文本 → `报告预览（与导出的文件内容一致）` → 等宽报告正文（可滚动）。
- **真实文案**：说明 `这份纯文本汇总了引擎最后一次退出的退出码与已捕获的 stderr、运行时与载荷状态、关键路径、最近的失败，以及设备信息。它不会进入对话上下文；敏感值已做脱敏。`；状态 `正在收集…`；报告分节 `── App 与设备 ──`、`── 引擎最后一次退出 ──`、`── 运行时事实 ──`、`── 关键路径 ──`、`── 最近的失败与错误 ──`；脱敏说明行 `已做脱敏：疑似 token / API Key / 密码的值替换为 <已脱敏>，家目录里的用户名替换为 <用户>。`、`报告仍可能包含普通文件路径与错误原文。`
- **数据来源**：`EngineDiagnostics`（引擎退出瞬间捕获）、`RuntimeFacts`、`RuntimeProvisioner` 载荷清单、`PiPaths`、`recentFailures()`。
- **最重的视觉元素**：一整屏等宽纯文本报告。

### 2.11 SessionTreeScreen（会话树，覆盖层）

- **源码**：`ui/chat/SessionTreeScreen.kt:88-160`；`BranchTab` `:163-`；`EntriesTab` `:356-`；`BranchRow` `:287-`；`EntryRow` `:381-`。
- **任务**：看分支结构（`get_tree`）与条目（`get_entries`），从某条消息分叉出新会话。
- **版面**：`Surface(fillMaxSize, color = background)` 覆盖当前页 → `TopAppBar("会话树")`（actions：`刷新` TextButton、`关闭` 图标）→ 分段按钮 `分支` / `条目` → 分支页：筛选输入（placeholder `筛选条目文字`）+ 筛选模式 TextButton + 提示 `分叉会新建一个会话文件，原会话保持不变。` → 行列表；条目页：条目列表。
- **真实文案**：`分叉新会话`（行内按钮）、空态 `没有匹配的条目`、`没有条目`。
- **数据来源**：`state.tree`（`get_tree`）、`state.entries`（`get_entries`）、`state.meta.sessionId`/`sessionFile`（判当前叶）。
- **最重的视觉元素**：带缩进的树行（缩进 `row.depth * 12.dp`，左侧 2dp×28dp 竖线，`SessionTreeScreen.kt:294-302`）。

### 2.12 TerminalPane（终端）

- **源码**：`ui/terminal/TerminalPane.kt:85-`（`TerminalSurface` `:242`，`terminalGrid` `:312`，`readClipboard` `:343`）；按键栏 `ui/terminal/TerminalKeyBar.kt:170-`；设置读取 `ui/terminal/TerminalSettings.kt`；桥 `ui/terminal/TerminalBridge.kt`。
- **任务**：真实 PTY 终端（原版 pi TUI 与普通 shell 都在这里）。
- **版面**：`Column(background = palette.background)` → 终端网格 `Box(weight=1f)` → `TerminalKeyBar`（状态行 + 键 chip 行）。
- **真实文案**：见 2.4。
- **数据来源**：PTY 字节流；颜色来自 `TerminalPalette`（App 自造，见 §4/§5）+ libvterm 自带 xterm 16 色。
- **最重的视觉元素**：终端网格 + 一行 34dp 高的键 chip。

### 2.13 SlashPalette（`/` 命令面板）

- **源码**：`ui/chat/SlashPalette.kt:45-`（行 `:78-`，来源徽章 `:140-`，过滤 `filterPalette` `:167-`，路由 `routeComposerText` `:217-`）。
- **任务**：草稿以 `/` 开头且还没敲空格时，列出命令（内置/扩展/模板/技能）。
- **版面**：`Surface(tonalElevation = 2.dp)` → 头部行（`padding(horizontal = 14.dp, vertical = 12.dp)`）→ `Column(heightIn(max = 260.dp))` 行列表。
- **真实文案**：分组徽章来自 `PiCommandSource.label`：`内置`、`扩展`、`模板`、`技能`（`PiSlashCommands.kt:92-108`）。内置命令名与描述（`PiSlashCommands.kt:163-238`）例：`model` `选择模型`、`tree` `浏览会话树，从某条消息分叉`、`thinking` `设置思考等级`、`scoped-models` `设置循环切换的模型范围`、`export` `导出会话：默认 HTML，路径以 .jsonl 结尾时写 JSONL`、`import` `从 JSONL 文件导入并恢复会话`、`copy` `复制最后一条模型消息`、`fork` `从某条历史消息创建分支`、`clone` `在当前节点复制整个会话`、`login` `配置 provider 认证（本应用只支持 API Key）`、`new` `新建会话`、`compact` `手动压缩会话上下文`、`reload` `重载扩展、技能、模板、主题与上下文文件`、`quit` `退出 pi`。
- **数据来源**：`get_commands`（pi 的扩展命令 + 模板 + 技能）＋ App 内置表 `PI_BUILTIN_SLASH_COMMANDS`。
- **最重的视觉元素**：单列命令列表（名称 + 描述 + 来源徽章）。

### 2.14 MentionPalette（`@` 文件提及）

- **源码**：`ui/chat/MentionPalette.kt:45-`；候选来源 `ui/chat/PiMentionSource.kt`；插入规则 `ui/chat/PiFileMentions.kt`。
- **任务**：把 pi 的 `@` 补全候选显示出来，点选插入（不发送）。
- **版面**：`Surface(tonalElevation = 2.dp)` → `Column(heightIn(max = 260.dp))` 行（`padding(horizontal = 14.dp, vertical = 8.dp)`）。
- **数据来源**：guest 里跑 pi 自己的 `fd`（`PiMentionSource`），去抖 `MENTION_DEBOUNCE_MS = 150L`（`ChatScreen.kt:1802`）。
- **最重的视觉元素**：短候选列表。仅在候选非空时绘制（`ChatScreen.kt:1180`）。

### 2.15 BashPanel（`!` shell 运行面板）

- **源码**：`ui/chat/BashPanel.kt:46-137`。
- **任务**：显示正在跑或刚跑完的 `!` 命令：命令、输出尾部、状态、中止/关闭。
- **版面**：`Surface(tonalElevation = 2.dp, color = palette.cardBg)` → `Column(padding(12dp/8dp))`：`$ {command}` 行 + 中止/关闭图标 → 输出（`heightIn(max = 220.dp)` 可滚）→ 状态行。
- **真实文案**：状态行由 `statusLine`（`:122-`）拼装，颜色：运行中 `palette.muted`、已取消 `palette.warning`、退出码 0/无 `palette.success`、否则 `palette.error`（`:103-106`）。
- **数据来源**：`state.bash`（`BashRun`：命令、输出、退出码、`excludeFromContext`）。
- **最重的视觉元素**：等宽输出块。

### 2.16 聊天底部 sheet（5 个 + 1 对话框）

- **源码**：`ui/chat/ChatSheets.kt`。由 `ChatScreen` 的私有 `enum class ChatSheet { Model, Thinking, Tools, Stats, Fork, Rename }`（`ChatScreen.kt:194`）驱动，`when (sheet)` 在 `:1310-1385`。
- **ModelPickerSheet** `:58-132`：`ModalBottomSheet`，标题 `选择模型`，筛选框 placeholder `按 provider / 模型名筛选`，空态 `正在读取模型列表…` / `没有可用模型。刚配置好的厂商要重启引擎后才会出现在这里；已经保存的模型可以在 设置 → 模型 里看到它们的状态。`，按钮 `重新读取`；行显示 name、`当前`、provider/id/上下文/`思考`/`图片`、`${cost} / M 输入 · ${cost} / M 输出`；页脚 `这个列表来自运行中的引擎。导入过的模型在 设置 → 模型 里能看到它们的状态。`。数据源 `get_available_models`。
- **ThinkingPickerSheet** `:200-273`：标题 `思考等级`，说明 `可选等级由当前模型决定。`，空 `当前模型不支持思考等级。`，行左侧 `◐` 徽章用 `PiTheme.palette.thinking(level)`，选中标 `当前`。数据源 `get_available_thinking_levels`。
- **SessionToolsSheet** `:279-431`（行渲染 `QueueModeRow` `:433-471`、`PiSectionHeader`/`PiSwitchRow`/`PiValueRow` 复用 `ui/components/PiCommon.kt`）：标题 `会话与队列`；节 `队列模式`（`穿插消息（steer）` + `本回合进行中插入，下一次回答之前生效`、`后续消息（follow up）` + `整个回合结束后才投递`，分段按钮 `逐条`/`全部`）、`上下文与重试`（`自动压缩` + `接近上下文上限时自动摘要`、`自动重试` + `可重试的模型错误按退避自动重试`、`取消重试`/`立即` + `结束正在等待的退避延迟`、`压缩上下文`/`执行` + `先中止当前回合，再生成一次摘要`）、`会话`（`会话信息与统计`/`查看` + `消息数、token、费用、上下文占用`、`会话树`/`打开` + `分支结构与扩展写入的条目`、`从历史消息分支`/`选择`、`复制当前会话`/`执行` + `复制出一个新的会话`、`重命名`/会话名、`导出会话（按扩展名）`/`导出` + `导出到工作区，包含扩展生成的渲染结果`、`复制最后一条回复`/`复制`）、条件节 `本应用显示不了的扩展（{n}）`（含说明 `下面这些扩展用到了本应用不支持的自绘界面，它们在对话页不会显示内容。`）。最大高度 560dp，节间用 `HorizontalDivider(color = outlineVariant)`。
- **SessionStatsSheet** `:474-555`（行 `StatLine` `:532`）：标题 `会话信息`；行 `会话名称`、`会话 ID`、`会话文件`、`消息`（`{u} 用户 · {a} 模型 · {t} 总计`）、`工具调用`（`{n} 次调用 · {n} 条结果`）、`Token`（`输入 … 输出 … 缓存读 … 缓存写 … 合计 …`）、`上下文占用`（`{p}%（{t} / {w}）`）、`累计费用`（`${x}`）；等待文案 `正在读取…`、无数据 `这次会话还没有统计信息。`；最大高度 560dp。数据源 `get_session_stats` + `get_state`。
- **ForkPickerSheet** `:557-617`：标题 `从哪条消息分支`，说明 `会在选中的消息处创建一个新会话。`，空 `没有可分叉的用户消息。`，行显示消息文本（最多 3 行，空时 `（空消息）`）+ entryId（`meta`）；列表最大高度 420dp。数据源 `get_fork_messages`。
- **RenameSessionDialog** `:619-654`：`AlertDialog`，标题 `会话名称`，说明 `会话列表里显示的名称。`，placeholder `名称`，按钮 `保存`（空白禁用）/`取消`。

### 2.17 ExtensionUiHost 与其对话框（全局）

- **源码**：`ui/extension/ExtensionUiHost.kt:44-`；对话框 `ui/extension/ExtensionDialogs.kt:59-`（`SelectDialog` `:81`、`ConfirmDialog` `:141`、`InputDialog` `:171`、`EditorDialog` `:220`、`DialogHeading` `:266`、`CountdownBar` `:295`、`TimeoutNote` `:311`）；常驻行 `ui/extension/ExtensionChrome.kt`（`ExtensionStatusRow` `:47-`、`ExtensionWidgetStack` `:90-`、`windowTitleOf` `:129`）。
- **任务**：承载 pi 扩展的 `notify`（Snackbar）、`confirm`/`select`/`input`/`editor`（AlertDialog）；状态行与 widget 是聊天页里的常驻 chrome。
- **版面**：`Box` → 底部 `SnackbarHost`（`padding(start=12, end=12, bottom=snackbarBottomPadding)`）+ `ExtensionDialogHost`。
- **真实文案**：对话框 `允许`、`拒绝`、`确定`、`取消`；排队 `还有 {n} 个扩展对话框在排队`；超时说明 `超时后{dialog.method.timeoutVerb}，晚到的回复会被丢弃。`；Snackbar 错误动作 `知道了`（错误用 `SnackbarDuration.Long`，其余 `Short`）；扩展状态行分隔符 `·`；widget 空行渲染为 `" "`。
- **数据来源**：`state.extensionDialog` / `extensionDialogBacklog` / `extensionStatuses` / `extensionWidgets` / notices 队列。
- **最重的视觉元素**：对话框（标题 + 倒计时条 + 长文本/选项）；Snackbar 是瞬时元素。

### 2.18 PiPackagesHost（扩展包管理，App 侧）

- **源码**：`app/src/main/kotlin/app/pi/packages/PiPackagesHost.kt` + `PiPackagesScreen.kt`（**不在 `ui/` 下**，但由 `PiSettingsStack.kt:352` 挂载，属用户可见面）。入口行 `PiPackagesEntryRow`（设置首页 `扩展` 节）。
- **任务**：`pi install` 没有 RPC，所以安装/卸载扩展包是 App 自己的界面，写 `settings.json` 的 `packages`。
- **不确定**：本次仅确认挂载点与入口行，未逐行核对 `PiPackagesScreen.kt` 的板块与文案（超出 `ui/` 目录范围）。

---

## 3. 对话流的 block 类型清单

渲染入口 `ui/blocks/BlockRenderer.kt:70-134`；`TranscriptItem` 是密封接口（`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:34-37`），实现类**共 13 种**（`:39-226`：`UserMessage` `:39`、`AssistantText` `:46`、`ThinkingBlock` `:53`、`ToolCall` `:64`、`ToolDiff` `:103`、`CompactionMarker` `:123`、`BranchSummary` `:156`、`HookMessage` `:177`、`ModelChange` `:185`、`SkillInvocation` `:193`、`ErrorText` `:201`、`DateSeparator` `:209`、`Notice` `:216`），`when` 无 `else`（`BlockRenderer.kt:82-133`）。
下表按 **`when` 分支**列出（`ToolCall` 因按 `toolName` 二次分发而占多行），共 11 个分支、覆盖 19 种呈现：

| # | 名称 (item) | 源码位置 | 长什么样（代码事实） |
|---|---|---|---|
| 1 | `UserMessage` | `blocks/UserMessageBlock.kt:38-` | 满宽 `Surface`，`PiShapes.card`(16dp)，色 `userMessageBg`，内边距 `PiSpacing.bubble`(14dp)，行距 10dp；markdown 正文用 `userMessageText` 作底色；图片走 `ImageGridBlock`；右下角时钟（`meta`，色 `userMessageText.copy(alpha=0.62f)`）；长按菜单 `复制`、`编辑并从此分叉` |
| 2 | `AssistantText` | `blocks/AssistantTextBlock.kt:35-` | 无容器，直接画在画布上；`PiMarkdownText`；流式中在文本右侧一个 `8dp × 16dp`、圆角 2dp、`accent.copy(alpha=0.65f)` 的静态光标；长按 `复制全部` |
| 3 | `ThinkingBlock` | `blocks/ThinkingBlockBlock.kt:30-` | 折叠时一行 32dp：左侧 3dp `AccentStripe`，颜色 = `palette.thinking(level)`（默认 `medium`）；标题 `思考中…` 或 `思考 {时长}`；等级中文标签用同一色；右侧 `展开`/`收起`；展开后斜体正文，行高 22sp，色 `thinkingBodyOnCanvas`；`hideThinking` 时整块不画 |
| 4 | `ToolCall`（通用/兜底卡；`toolName` 不属于 read/write/edit/grep/find/ls/bash/powershell，**或**结果带图片时） | `blocks/ToolCallBlock.kt:37-212`，chrome `blocks/ToolBlockChrome.kt` | 状态色卡：`toolPendingBg`/`toolSuccessBg`/`toolErrorBg`（`ToolBlockChrome.kt:44-48`），边框 = `warning`/`success`/`error` 的 35%（`:51-55`、`:217`）；标题行 `ToolHeader`（`:80-110`）：mono 工具名（`dim`）+ 主体（`toolTitle`）+ 状态字形 `…`/`✓`/`✗`（色 `warning`/`success`/`error`）；正文 mono `bodyOnTool`，默认前 200 行（`COLLAPSED_OUTPUT_LINES`，`:227`）+ `展开全部（共 N 行）`；>200KB（`MAX_OUTPUT_BYTES`，`:228`）显示 `输出超过 200 KB，正文未展开。`；尾部 `ToolFooter`（`:120-141`，文本由 `toolFooterText` `:273-281` 组装：`成功 · 退出码 N · 1.2s · N 行 · 已截断 · 无输出`，色 `muted`）；截断告警行 `ToolNotice`（`:178-199`，色 `warning`，可点复制路径）；长按 `复制命令`/`复制输出`/`复制完整输出路径`（`ToolActionMenu` `:230-251`）；结果带图片时画 `ImageGridBlock` 并吞掉点击（`ToolCallBlock.kt:194-202`） |
| 5 | `ToolCall` `read` | `blocks/ReadBlock.kt:39-` | 同上卡片，标题 `read` + `路径:起-止`；正文 `SourceLines`（行号 + 按路径语言高亮），色 `bodyOnTool`；空 `文件为空`；`展开全部（还有 N 行）` / `还有 N 行未显示`；`TOOL_SCAN_CAPPED_HINT = 输出过长，只解析了前面一部分。` |
| 6 | `ToolCall` `write` | `blocks/WriteBlock.kt:35-` | 标题 `write` + 路径；成功不打印任何结果文本；失败时正文用 `palette.error`；无可写内容时 `参数里没有文件内容。`；`展开全部（还有 N 行，共 N 行）` |
| 7 | `ToolCall` `edit` | `blocks/EditBlock.kt:36-` | 标题 `edit` + 路径；失败时显示 `item.output`（色 `error`）；diff 由 reducer 另投 `ToolDiff` 交给 `DiffBlock`（`EditBlock` 不重算） |
| 8 | `ToolCall` `grep` | `blocks/GrepBlock.kt:43-` | 标题 `grep` + `/pattern/ in path`；正文按文件分组（`GrepGroupHeading`：路径 `toolTitle` + `N 处` `dim`），行 = 行号(`dim`) + 文本（上下文行用 `contextOnTool`，其余 `bodyOnTool`）；空 `没有匹配`；footer 含 `{n} 处`、`{n} 个文件`；解析失败回落到通用卡 |
| 9 | `ToolCall` `find` / `ls` | `blocks/PathListBlock.kt:32-`（共享 `PathListBlock` `:92-`） | 标题 `find`/`ls` + 主体；正文按目录分组，`{n} 项`；空文案 `没有找到匹配的文件` / `空目录`；`展开全部（还有 N 项）` / `还有 N 项未显示` |
| 10 | `ToolCall` `bash` / `powershell` | `blocks/ShellBlock.kt:48-` | 标题 `$` + 命令（可带 ` (timeout Ns)`）；正文是**尾部**（`tailLines`），色 `bodyOnTool`；`展开全部（上方还有 N 行）`；footer 是 pi 的 `Elapsed`/`Took` 语义在 App 侧的计时 |
| 11 | `ToolDiff` | `blocks/DiffBlock.kt:35-` | 卡色 `toolPendingBg`，边框 `borderMuted.copy(alpha=0.35f)`；标题行：工具名(`dim`)+路径(`toolTitle`)+`+N`(`toolDiffAdded`)+`−N`(`toolDiffRemoved`)；第二行 `新增 N 行 · 删除 N 行`（`muted`）+ `展开`/`收起`；展开后每行：16dp 符号列（`+`/`-`/空格，色=增删色或 `contextOnTool`）+ 30dp 行号列(`dim`) + 正文（增删行 `text`，上下文行 `contextOnTool`），整行底色 = 行色 `alpha 0.08`；连续上下文 >4 行折成 `… N 行未变`；>200 行截断并显示 `差异过长，仅显示前 200 行`；空 diff `（无差异内容）` |
| 12 | `CompactionMarker` | `blocks/CompactionBlock.kt:42-` | 一条 hairline（`borderMuted.copy(alpha=0.5f)`）夹一个居中 chip（`PiShapes.chip`，`padding(horizontal=10dp, vertical=4dp)`），chip 色 `customMessageBg`，标签色 `customMessageLabel`、`labelSmall`；标签 `正在压缩上下文…`/`上下文已压缩`/`压缩已中止`/`压缩失败`；`Running` 副题 `pi 正在总结更早的对话`（+ ` · reason`）；摘要折叠 = 2 行纯文本（`COLLAPSED_SUMMARY_LINES = 2, :137`，色 `customMessageText`），展开为 markdown；`showBilledCost` 时附 `PiBilledCostLine`（英文原文 `Compaction: N tokens billed (~$x.xx)`，色 `warning`） |
| 13 | `BranchSummary` | `blocks/BranchSummaryBlock.kt:41-` | `customMessageBg` 卡，边框 `customMessageLabel.copy(alpha=0.35f)`；标题行：3dp `customMessageLabel` 竖条 + `分支摘要`（`labelLarge`、`customMessageLabel`）+ 分支 id（`monoSmall`、色 `muted`，不是 `metaOnCard`）+ 右侧 `展开`/`收起`（该 label 自身带 `toggleContent`，行点击则 `跳转分支`）；折叠 = 2 行纯文本 `（无摘要）`（`customMessageText`），展开为 markdown；`showBilledCost` 时附 `PiBilledCostLine`（`Branch summary: …`） |
| 14 | `HookMessage` | `blocks/HookMessageBlock.kt:29-` | `customMessageBg` 卡 + `customMessageLabel.copy(alpha=0.35f)` 边框 + `customMessageLabel` 3dp 竖条（高 `PiSpacing.accentStripe` 20dp）+ mono `customType`（空时 `extension`）；折叠阈值为 `markdown.length > 240 \|\| 换行数 > 4`（`:39`），短消息不可折叠（`ToggleContent(enabled = collapsible)`）；展开（或本就短）画 markdown `（空消息）` 兜底，折叠时 4 行纯文本（`COLLAPSED_MESSAGE_LINES = 4, :92`，色 `customMessageText`） |
| 15 | `ModelChange` | `blocks/ModelChangeBlock.kt:27-` | 一行，高 `PiSpacing.statusRow`(32dp)，上下 `PiSpacing.unit/2`；`模型切换 →`（色 `metaOnCanvas`，`meta` 字号）+ 模型名（色 `borderAccent`，`labelLarge`；空时 `provider` 或 `未知模型`）；有 `onClick` 时可点开模型选择器 |
| 16 | `SkillInvocation` | `blocks/SkillInvocationBlock.kt:38-` | `customMessageBg` 卡（**不用** `cardBg`）+ `customMessageLabel.copy(alpha=0.35f)` 边框（代码注释说明：pi 画这个盒子没有边框，边框是 App 自加）；标题行：`customMessageLabel` 竖条 + `技能`（`monoSmall`、`customMessageLabel`）+ `/skill:{name}`（`mono`、色 `text`，空时 `未知技能`）+ `展开`/`收起`；展开显示 SKILL.md 的 markdown（`（技能没有正文）` 兜底，`customMessageText` 作正文底色）；折叠无正文预览 |
| 17 | `ErrorText` | `blocks/ErrorBlock.kt:33-` | `toolErrorBg.copy(alpha=0.7f)` 卡 + `error.copy(alpha=0.45f)` 边框 + 左侧 `error` 竖条（20dp）；正文 `item.message`（空时 `出错了`，色 `text`，`bodyLarge`）；可展开 `详情`（`labelMedium`、色 `error`）；**无重试按钮**（`onRetry` 已删除） |
| 18 | `DateSeparator` | `blocks/DateSeparatorBlock.kt:22-` | 两条 hairline（`borderMuted.copy(alpha=0.5f)`）夹日期文本（`metaOnCanvas`，文本为空时用 `formatClock(ts)`），上下 `PiSpacing.unit/2`；`prefs.showTimestamps = false` 时整类不渲染（`ChatScreen.kt:362-368`） |
| 19 | `Notice` | `blocks/NoticeBlock.kt:21-` | 一行：`·` 前缀（`mono`）+ 文本（`meta`，空时 `（无内容）`）+ 时钟（`metaOnCanvas`）；颜色按 `item.tone`：`Info → muted`、`Warning → warning`、`Error → error` |

共享 chrome（不单独出块）：`BlockColumn`（块内 `spacedBy(PiSpacing.gutter=6dp)`，块间距由列表给）、`MonoText`（机器输出用等宽）、`ProseText`（人类文本用系统字体 + `bodyLarge`）、`BlockCard`、`AccentStripe`(3dp 宽)、`ExpandLabel`(`展开`/`收起`)、`Modifier.toggleContent`（**内容区即点击区**）、`BlockActionMenu`（长按下拉，`onLongClickLabel = "更多操作"`），均在 `blocks/BlockChrome.kt:62-301`。

---

## 4. pi 取色 / 变色清单（**不可改**的一侧）

### 4.1 唯一入口：pi 主题文件 → `PiPalette`

- `PiPalette`（`ui/theme/PiPalette.kt:31-100`）逐字携带 pi 主题的 **56 个色字段**（构造参数实测 59 个 `val …: Color`，去掉 5 个对比度派生后为 56；与 `PiThemeFiles.kt` 的 `REQUIRED_TOKENS` 51 项 + `OPTIONAL_FALLBACKS` 5 项一致）。字段即 token 名，**顺序**：核心 16（`accent` … `searchMatchText`）→ 消息面 5（`userMessageBg`…`customMessageLabel`）→ 工具面 5（`toolPendingBg`…`toolOutput`）→ markdown 10（`mdHeading`…`mdListBullet`）→ diff 3 → syntax 9 → 思考梯度 7（`thinkingOff`…`thinkingMax`）→ `bashMode` → 画布 3（`pageBg`/`cardBg`/`infoBg`）。默认值就是 pi 内置 `dark`（`:165-225`）与 `light`（`:228-288`）两套。
- 可选 token 的回退关系（`PiThemeFiles.kt:126-132`）：`scrollbarTrack←muted`、`scrollbarThumb←text`、`thinkingMax←thinkingXhigh`、`searchMatchBg←selectedBg`、`searchMatchText←text`。
- 解析：`PiThemeLoader.load`（`PiThemeFiles.kt:203-269`）→ `parseThemeJson`（`:285-378`）。值语法 = `#rrggbb` / 0-255 索引（按标准 xterm 256 表换算，`:422-439`）/ `""`（终端默认，用内置值代替）/ `vars` 引用（可递归，`:395-407`）。
- 主题发现：`discover`（`PiThemeFiles.kt:144-164`）扫 `<agentDir>/themes`、`<workspace>/.pi/themes`、`themes` 设置指向的路径/目录；作用域标签 `PiThemeScope`：`内置`、`~/.pi/agent/themes`、`.pi/themes`、`themes 设置`（`:46-58`）。
- 写入 `LocalPiPalette`：`PiTheme`（`ui/theme/PiTheme.kt:502-522`），由 `MainActivity.kt:39-45` 用 `theme.palette` 注入 → **任何 `MaterialTheme.colorScheme.*` 的色值也间接来自 pi**（见 4.2）。

### 4.2 pi 色 → Material3 `ColorScheme` 的映射（改这些等于改 pi 色）

`PiPalette.colorScheme()`，`ui/theme/PiTheme.kt:397-448`：

| ColorScheme 槽 | 来自 pi token / 派生 |
|---|---|
| `primary` / `inversePrimary` / `surfaceTint` | `accent` |
| `onPrimary` / `onSecondary` / `onTertiary` / `onError` | `accent` 亮度决定 `Color.White`/`Color.Black`（`:399`） |
| `primaryContainer` / `onPrimaryContainer` | `selectedBg` / `text` |
| `secondary` | `borderAccent` |
| `secondaryContainer` / `onSecondaryContainer` | `cardBg` / `text` |
| `tertiary` | `customMessageLabel` |
| `tertiaryContainer` / `onTertiaryContainer` | `customMessageBg` / `customMessageText` |
| `background` / `surface` / `onSurface` | `pageBg` / `pageBg` / `text` |
| `surfaceVariant` / `onSurfaceVariant` | `cardBg` / `muted` |
| `error` / `errorContainer` / `onErrorContainer` | `error` / `toolErrorBg` / `text` |
| `outline` / `outlineVariant` | `borderMuted` / `borderMuted.copy(alpha = 0.55f)` |
| `surfaceContainerLowest` / `Low` | `pageBg` / `cardBg` |
| `surfaceContainer` / `High` / `Highest` / `Bright` / `Dim` | `cardBg.elevate(text, 0.03/0.06/0.10/0.12)`、`pageBg.elevate(text, 0.02)`（`:428-434`，`elevate` = `lerp`） |
| `scrim` | `Color.Black`（硬编码，非 pi token，`:444`） |

### 4.3 五个"修正档"（pi 色经对比度抬升，仍是 pi 色系）

`ui/theme/PiPalette.kt:112-161`，算法在 `ui/theme/PiContrast.kt`（WCAG，`ensure` 每次 1% 向白/黑 lerp，最多 100 步，`:58-68`）：

| 派生 token | 定义 | 用在哪 |
|---|---|---|
| `bodyOnTool` | `toolOutput` 对 `toolPendingBg`/`toolSuccessBg`/`toolErrorBg` 抬到 4.5:1（`:130-134`） | `ToolCallBlock.kt:151`、`ReadBlock.kt:81`、`WriteBlock.kt:82`、`ShellBlock.kt:86`、`GrepBlock.kt:166`、`PathListBlock.kt:151` |
| `contextOnTool` | `toolDiffContext` 对 `toolPendingBg` 抬到 4.5:1（`:141`） | `DiffBlock.kt:98,151,155`、`GrepBlock.kt:166` |
| `thinkingBodyOnCanvas` | `thinkingText` 对 `pageBg` 抬到 4.5:1（`:149`） | `ThinkingBlockBlock.kt:64,84` |
| `metaOnCanvas` | `muted` 对 `pageBg` 抬到 3:1（`:155`） | `DateSeparatorBlock.kt:48`、`ModelChangeBlock.kt:52,68`、`NoticeBlock.kt:49` |
| `metaOnCard` | `dim` 对 `cardBg` 抬到 3:1（`:161`） | `ImageGridBlock.kt:153`（网格里多图时每格的「图片 N」标签；`+N` 溢出徽章用的是 `palette.text`，`:162`） |

> 结论：这 5 个是**由 pi 色计算出来的**，改它们等于改 pi 取色结果，属不可改侧。

### 4.4 markdown 渲染的 pi 色

- `render/PiMarkdownTheme.kt`：`piMarkdownColors`（`:136-151`）与 `piMarkdownTypography`（`:212-248`）逐 token 映射：
  - `text` → `MarkdownColors.text`（可被调用方的 `textColor` 覆盖）
  - `codeBackground` → `cardBg`；`inlineCodeBackground` → `infoBg`；`dividerColor` → `mdHr`；`tableBackground` → `cardBg`
  - 标题色 → `mdHeading`（h1…h6）
  - `inlineCode` → `mdCode`（12.5sp/18sp）；`code` → `mdCodeBlock`
  - `quote` → `mdQuote`；`bullet` → `mdListBullet`
  - `textLink` → `mdLink` + 下划线
- GFM alerts：`piAlertColors`（`PiMarkdownTheme.kt:178-186`）——`NOTE→mdQuote`、`TIP→success`、`IMPORTANT→mdHeading`、`WARNING→warning`、`CAUTION→error`。
- 代码块外框：`render/PiMarkdownComponents.kt:358-383` —— 底色 `palette.cardBg`，圆角 12dp，边框 `BorderStroke(1.dp, palette.mdCodeBlockBorder)`；未高亮正文用 `mdCodeBlock` 色。
- 公式回退：`PiMarkdownComponents.kt:189-197` 用 `palette.mdCode`；图片回退 `PiImageFallback`（`:320-341`）用 `palette.text`（alt）与 `palette.dim`（地址）。
- 图片：`render/PiMarkdown.kt:81,126,131-137`（source 重写 + `imageTransformer`）；`LocalPiCodeHighlighter provides PiNodeCodeHighlighter`（`PiMarkdown.kt:128`）。`isSystemInDarkTheme()`（`:90`）只喂给 alert 的明暗分支——**不决定 pi 主题**。

### 4.5 语法高亮 token → pi 色

- `render/PiCodeHighlight.kt`：
  - `PiSyntaxToken`（`:19-66`）15 项；`PiSyntaxToken.color(palette)`（`:73-87`）映射：`Comment→syntaxComment`、`Keyword→syntaxKeyword`、`Function→syntaxFunction`、`Variable→syntaxVariable`、`StringLiteral→syntaxString`、`Number→syntaxNumber`、`Type→syntaxType`、`Operator→syntaxOperator`、`Punctuation→syntaxPunctuation`、`Muted→muted`、`DiffAdded→toolDiffAdded`、`DiffRemoved→toolDiffRemoved`；`Emphasis`/`Strong`/`Link` 无颜色（斜体/粗体/下划线）。
  - `PiCodeLanguage`（`:148-241`）：fence 语言名归一化 + 按路径扩展名取语言；未知语言 → 不高亮（`PiPlainCodeHighlighter` `:135-137`）。
- 应用点：`render/PiMarkdownComponents.kt:410-469`（`rememberPiHighlightedCode` + `buildPiCodeText`，离主线程、流式 fence 去抖 `STREAM_SETTLE_MS = 200L` `:444`）；`blocks/ToolBodyText.kt:43-66`（`read`/`write` 文件正文的行号 + 高亮，行号 gutter 色 = `palette.muted` `:57-58`）。

### 4.6 diff 增删/上下文色

- `blocks/DiffBlock.kt`：`toolDiffAdded`（`:72,147`）、`toolDiffRemoved`（`:74,148`）、`toolDiffContext` 经 `contextOnTool`（`:98,151,155`）、`toolTitle`（`:67`）、`toolPendingBg`（`:50`）、`borderMuted.copy(alpha=0.35f)`（`:53`）、行底 `markColor.copy(alpha=0.08f)`（`:160`，颜色是 pi 的，**透明度是 App 的**）。

### 4.7 终端：唯一不走 pi 主题的取色面

- `ui/terminal/TerminalPalette.kt:18-43` 只有两个颜色（`foreground`/`background`），由 `TerminalPane.kt:87-88` 按 `MaterialTheme.colorScheme.background.luminanceIsDark()` 在 `TerminalPalette.dark()`/`light()` 之间二选一。
- 终端里的 16 色 / 256 色是 **guest 程序自己请求的**，由 `org.connectbot:termlib` + libvterm 绘制（`TerminalPalette.kt:5-13` 的注释）；App 只决定"没有请求颜色时"的前景/背景。因此**终端内容色不属于 pi 取色**，也不属于本次"不可改"的界线内。
- 键 chip 的两种底色是 App 从 `foreground` 派生的 alpha（`:24-25`），属可改侧。

### 4.8 各组件用到的 pi token（逐文件）

（取自 `PiTheme.palette.*` 与 `val palette = PiTheme.palette` 后的 `palette.*`）

| 文件 | 用到的 pi token |
|---|---|
| `blocks/UserMessageBlock.kt` | `userMessageBg`, `userMessageText` |
| `blocks/AssistantTextBlock.kt` | `accent` |
| `blocks/ThinkingBlockBlock.kt` | `thinking(level)`, `thinkingBodyOnCanvas` |
| `blocks/ToolCallBlock.kt` | `bodyOnTool`, `muted` |
| `blocks/ToolBlockChrome.kt` | `toolPendingBg`, `toolSuccessBg`, `toolErrorBg`, `toolTitle`, `warning`, `success`, `error`, `dim`, `muted` |
| `blocks/ReadBlock.kt` / `WriteBlock.kt` / `ShellBlock.kt` | `bodyOnTool`, `muted`, `error` |
| `blocks/EditBlock.kt` | `error` |
| `blocks/GrepBlock.kt` | `bodyOnTool`, `contextOnTool`, `dim`, `muted`, `toolTitle` |
| `blocks/PathListBlock.kt` | `bodyOnTool`, `dim`, `muted`, `toolTitle` |
| `blocks/DiffBlock.kt` | 见 4.6 |
| `blocks/CompactionBlock.kt` | `customMessageBg`, `customMessageLabel`, `customMessageText`, `borderMuted`, `muted` |
| `blocks/BranchSummaryBlock.kt` | `customMessageBg`, `customMessageLabel`, `customMessageText`, `muted` |
| `blocks/HookMessageBlock.kt` / `SkillInvocationBlock.kt` | `customMessageBg`, `customMessageLabel`, `customMessageText`（+ `text`） |
| `blocks/ModelChangeBlock.kt` | `borderAccent`, `metaOnCanvas` |
| `blocks/ErrorBlock.kt` | `toolErrorBg`, `error`, `text`, `bodyOnTool` |
| `blocks/DateSeparatorBlock.kt` | `borderMuted`, `metaOnCanvas` |
| `blocks/NoticeBlock.kt` | `muted`, `warning`, `error`, `metaOnCanvas` |
| `blocks/ImageGridBlock.kt` | `cardBg`, `borderMuted`, `text`, `muted`, `metaOnCard` |
| `blocks/ToolBodyText.kt` | `muted`（行号 gutter） |
| `screens/ChatScreen.kt` | `searchMatchBg`, `searchMatchText`（搜索高亮底/边框，`:994-997`）、`muted`（加载更早行 `:959,965`）、`accent`（光标、后续 chip）、`bashMode`（`!` 模式输入框边框 `:1690`）、`thinking(level)`（思考 chip + 输入框边框 `:1692,1775,1781`） |
| `components/PiCommon.kt` | `muted`, `warning`, `error`（状态行与计费行） |
| `chat/BashPanel.kt` | `cardBg`, `bashMode`/`dim`, `toolOutput`, `muted`, `warning`, `success`, `error` |
| `chat/ChatSheets.kt` | `thinking(level)`（思考等级徽章 `:238`） |
| `chat/ExtensionChrome.kt` | `accent`（状态文本）、`dim`（分隔符）、`cardBg`+`borderMuted.copy(0.4f)`+`muted`（widget 卡） |
| `chat/ExtensionUiHost.kt` | `accent`, `cardBg`, `text`, `warning`, `error`, `infoBg`, `toolErrorBg`（Snackbar 三态） |
| `extension/ExtensionDialogs.kt` | `accent`, `borderMuted`, `cardBg`, `muted`, `selectedBg`, `text`, `warning` |
| `device/DeviceCapabilityScreen.kt` | `warning`, `error` |
| `settings/PiSettingsRegistry.kt` | 主题行等（经 store 读值，颜色不直接取） |
| `render/*` | 见 4.4 / 4.5 |
| `terminal/TerminalPane.kt` | `TerminalPalette.foreground/background`（**不是** pi token，见 §5） |
| `theme/PiTheme.kt` | `toolSuccessBg`（`:441` → `errorContainer`） |

### 4.9 "跟 pi 取色/变色有关"的界线（一句话）

**不可改（pi 侧）**：`PiPalette` 的 56 个色字段及其取值/来源解析、`PiContrast` 的 5 个派生 token（`bodyOnTool`/`contextOnTool`/`thinkingBodyOnCanvas`/`metaOnCanvas`/`metaOnCard`）、`PiPalette.colorScheme()` 的全部槽位映射（含 `surfaceContainer*` 的 lerp 派生）、`piMarkdownColors`/`piMarkdownTypography` 的 markdown token 映射、`piAlertColors`、`PiSyntaxToken.color` 的 12 个彩色映射（14 个枚举值中 3 个仅装饰）、`DiffBlock` 的增删/上下文色、各 block 直接引用的 `palette.*`。
**可改（App 侧）**：布局/尺寸/圆角/间距、所有 `copy(alpha = …)` 的透明度、`Color(0x…)` 常量（终端两色与 scrim）、`Surface` 的 `tonalElevation`/`shadowElevation`、空态与提示文案、`MaterialTheme.colorScheme` 里由 App 自己设定的槽（实测全部来自 pi，见 4.2，故改任何一个都等于改 pi 取色）。

---

## 5. App 自造的颜色 / 尺寸常量清单

### 5.1 硬编码颜色（全仓库仅这些）

- `ui/theme/PiPalette.kt:166-288`：pi 内置 dark/light 的 56×2 个 `Color(0x…)`——**是 pi 值，不算 App 自造**（列在此仅为划清边界）。
- `ui/theme/PiTheme.kt:399`：`Color.White` / `Color.Black`（`onAccent`，按 `accent` 亮度二选一）。
- `ui/theme/PiTheme.kt:444`：`scrim = Color.Black`。
- `ui/theme/PiContrast.kt:60`：`Color.White` / `Color.Black`（对比度抬升目标）。
- `ui/theme/PiThemeFiles.kt:413,428,435,438`：`Color(0xFF000000L or …)`（解析 hex / xterm 表的位运算）。
- `ui/terminal/TerminalPalette.kt:35-36`：dark `foreground = Color(0xFFD4D4D4)`、`background = Color(0xFF101014)`；`:40-41`：light `foreground = Color(0xFF1F2328)`、`background = Color(0xFFF8F8F8)`。→ **唯一的"App 自造颜色"**，注释明确说"终端不是主题化表面"，xterm 16 色由 libvterm 自带。
- `ui/terminal/TerminalPalette.kt:24-25`：键 chip 色 `foreground.copy(alpha = 0.08f)` / `0.28f`（App 推导）。

### 5.2 透明度常量（颜色是 pi 的，alpha 是 App 的）

- `alpha = 0.35f` ×8：卡片/元素边框统一取 35% —— `blocks/BranchSummaryBlock.kt:53`、`blocks/DiffBlock.kt:53`、`blocks/HookMessageBlock.kt:44`、`blocks/SkillInvocationBlock.kt:70`、`blocks/ToolBlockChrome.kt:217`、`extension/ExtensionDialogs.kt:306`（倒计时条轨道）；行高亮另用同值 —— `settings/SettingsGroupScreen.kt:137`、`settings/PiModelsScreen.kt:313`（`secondaryContainer`）。
- `alpha = 0.5f` ×5：`DateSeparatorBlock.kt:42,53`、`CompactionBlock.kt:73,93`、`ImageGridBlock.kt:125`（`borderMuted`）。
- `alpha = 0.18f` ×3：思考色 chip（`ChatScreen.kt:1735,1775`、`ChatSheets.kt:238`）。
- `alpha = 0.16f` ×3：徽章底（`PiCommon.kt:384` 生效徽章、`DeviceCapabilityScreen.kt:896` 状态徽章、`SlashPalette.kt:148` 来源徽章）。
- `alpha = 0.08f` ×2：diff 行底（`DiffBlock.kt:160`）；终端 chip（`TerminalPalette.kt:24`）。
- `alpha = 0.7f`：错误卡底（`ErrorBlock.kt:43`）。
- `alpha = 0.45f`：错误卡边框（`ErrorBlock.kt:44`）。
- `alpha = 0.65f`：流式光标（`AssistantTextBlock.kt:64`）。
- `alpha = 0.62f`：用户消息时钟（`UserMessageBlock.kt:92`）。
- `alpha = 0.55f`：`PiTheme.kt:437`（`outlineVariant`）。
- `alpha = 0.4f`：扩展 widget 边框（`ExtensionChrome.kt:107`）。
- `alpha = 0.28f`：终端 armed chip（`TerminalPalette.kt:25`）。
- `alpha = 0.25f`：模型卡（`PiModelsScreen.kt:382`）。
- `alpha = 0.96f`：`PiThemeFiles.kt`（导出面派生）。

### 5.3 间距 / 圆角 / 高度常量（App 自造）

- `PiSpacing`（`ui/theme/PiTheme.kt:42-128`）：`unit=18.dp`、`screen=16.dp`、`card=12.dp`、`touchTarget=48.dp`、`appBar=56.dp`、`statusRow=32.dp`、`bottomBar=64.dp`、`listItem=72.dp`、`paragraphGap=12.65.dp`、`listIndent=23.dp`、`listItemSingle=56.dp`、`buttonInline=40.dp`、`buttonPrimary=56.dp`、`tab=40.dp`、`chip=32.dp`、`contextRing=20.dp`、`contextRingStroke=2.dp`、`errorDot=8.dp`；块内部尺度：`hairline=1.dp`、`tiny=2.dp`、`stripe=3.dp`、`small=4.dp`、`gutter=6.dp`、`inline=8.dp`、`inner=10.dp`、`bubble=14.dp`、`symbolColumn=16.dp`、`lineNumberColumn=30.dp`、`accentStripe=20.dp`。
- `PiShapes`（`ui/theme/PiTheme.kt:139-173`）：`card=16dp`、`cardInner=12dp`、`input=24dp`、`inputMultiline=16dp`、`fab=16dp`、`sheet=顶部 28dp`、`dialog=28dp`、`chip`/`badge`/`button`=`RoundedCornerShape(percent=50)`、`field=16dp`、`snackbar=12dp`。`piShapes()`（`:494-500`）：`extraSmall=4`、`small=8`、`medium=card`、`large=20`、`extraLarge=28`。
- `PiElevation`（`ui/theme/PiTheme.kt:184-205`）：`level0..level5 = 0/1/2/4/6/8.dp`，`level5ScrimAlpha=0.32f`。**实际引用很少**：代码里显式 elevation 只有 `SlashPalette.kt:56`、`MentionPalette.kt:54`、`BashPanel.kt:59` 的 `tonalElevation = 2.dp` 与 `ChatScreen.kt:1059` 的 `shadowElevation = 3.dp`。
- `PiIcons`（`:209-224`）：`inline=20.dp`、`nav=24.dp`、`errorDot=8.dp`、`emptyIllustration=120.dp`、`illustrationStroke=1.5.dp`。**实测零调用点**（`grep -rn "PiIcons\." ui/` 无结果）。
- `PiMotion`（`:250-319`）：`instant=0`、`fastSpatialMs=150`、`mediumSpatialMs=250`、`slowSpatialMs=400`、`fastEffectsMs=100`、`mediumEffectsMs=200`，spring 阻尼 `0.9/0.85/0.8`；另有 6 个 `@Deprecated` 旧拼写 `:302-317`。**实测零调用点**（`grep -rn "PiMotion\." ui/` 只命中自身注释与 deprecated 声明）——App 唯一的动画相关代码是**关闭**库动画（`render/PiMarkdown.kt:184-185`：`retainState = true`、`animations = markdownAnimations(animateTextSize = { this })`）。
- **已定义但零引用**（可作为重设计时直接删除或重新启用的清单，逐项用 `grep -rn "PiSpacing.<名>" ui/` 核对）：`PiElevation.*`、`PiIcons.*`、`PiMotion.*`、`PiSpacing.appBar`、`bottomBar`、`listItem`、`listItemSingle`、`buttonInline`、`buttonPrimary`、`tab`、`chip`、`contextRing`、`contextRingStroke`、`touchTarget`、`paragraphGap`、`listIndent`。
- **有引用**：`PiSpacing.statusRow`（4 处：`components/PiCommon.kt:153`、`blocks/ThinkingBlockBlock.kt:56`、`blocks/ModelChangeBlock.kt:45`、`extension/ExtensionChrome.kt:56`）；`PiSpacing.errorDot`（仅经 `theme/PiTheme.kt:217` 的 `PiIcons.errorDot` 转发，无实际消费者）。

### 5.4 直接写在 Compose 里的裸 dp/sp（共 438 处，未走 token）

按文件分布（不含 `PiSpacing.*` / `PiIcons.*` / `PiElevation.*` 引用）：`PiSettingsEditors.kt` 32、`ChatScreen.kt` 31、`PiMarkdownTheme.kt` 26、`DeviceCapabilityScreen.kt` 24、`ChatSheets.kt` 15、`PiCredentialScreen.kt` 13、`TerminalKeyBar.kt` 11、`SessionTreeScreen.kt` 11、`BootScreen.kt` 10、`SettingsHome.kt` 9、`PiModelsScreen.kt` 8、`PiMarkdownComponents.kt` 7、`SlashPalette.kt` 7、`SettingsSearchScreen.kt` 6、`SessionsScreen.kt` 6、`PiCommon.kt` 6、`ExtensionDialogs.kt` 5、`ExtensionChrome.kt` 5、`BashPanel.kt` 4、`PiSettingsRows.kt` 3、`MentionPalette.kt` 3、`LicensesScreen.kt` 2、`ExtensionUiHost.kt` 2、`BlockChrome.kt` 2、`AssistantTextBlock.kt` 2、`DiagnosticsScreen.kt` 1、`UserMessageBlock.kt` 1、`ThinkingBlockBlock.kt` 1、`ImageGridBlock.kt` 1、`ErrorBlock.kt` 1、`CompactionBlock.kt` 1、`BranchSummaryBlock.kt` 1、`PiRoot.kt` 1。

取值直方图（前 15）：`8dp` 61、`4dp` 57、`6dp` 55、`12dp` 41、`2dp` 25、`10dp` 21、`14dp` 17、`3dp` 10、`1dp` 6、`22dp` 5、`420dp` 4、`16dp` 4、`160dp` 4、`22sp` 3、`20dp` 3。

典型用途：`420dp`/`560dp`/`360dp` = bottom sheet 的 `heightIn(max=…)`（`ChatSheets.kt:479,463` 等、`ExtensionDialogs.kt:91`）；`260dp`/`220dp`/`200dp`/`160dp` = palette 与输出的可滚高度（`SlashPalette.kt:68`、`MentionPalette.kt:57`、`BashPanel.kt:92`、`SessionTreeScreen.kt:406`）；`300dp`/`420dp`/`200dp` = 设置编辑器（`PiSettingsEditors.kt:526,165,673`）；`48.dp` = 附件缩略图（`ChatScreen.kt:1864`）；`8dp × 16dp` = 流式光标（`AssistantTextBlock.kt:62`）；终端键 chip `40/34/62dp`、字号 `11/12sp`（`TerminalKeyBar.kt:240,260,279,307,316`）。

---

## 6. 现在的排版 / 尺寸事实

### 6.1 字体

- `PiTextStyles`（`ui/theme/PiTheme.kt:328-367`，App 自定义角色）：
  - `meta`：`FontFamily.Default`，`11.5sp / 16sp`，Normal —— 状态行、时间、副标题、说明文字。
  - `mono`：`FontFamily.Monospace`，`13sp / 20sp` —— 工具正文、命令、搜索框、路径。
  - `monoSmall`：`FontFamily.Monospace`，`11.5sp / 17sp` —— 行号、徽章、键 chip、widget 行。
  - `numeric` = `mono`（状态行数字防跳动，`:374`）。
  - 设置 `app.appearance.fontScaleDelta` 时三者同步平移（`scaled`，`:341-345`），下限 1sp。
- `piTypography`（`ui/theme/PiTheme.kt:457-492`，覆盖 M3）：`displaySmall 28/36 Medium`、`headlineSmall 22/28 SemiBold`、`titleMedium 17/24 SemiBold`、`titleSmall 15/22 SemiBold`、`bodyLarge 15/23 Normal`、`bodyMedium 14/20 Normal`、`labelLarge 14/20 Medium`、`labelMedium 12/16 Medium`、`labelSmall 11.5/16 Normal`；全部随 `textScaleDelta` 平移。
- markdown 标题尺度（`PiMarkdownTheme.kt:222-247`）：h1 `22/30` 带下划线、h2 `20/28`、h3 `18/26`、h4 `16/24`、h5 `15/22`、h6 `14/21`、alertTitle `16/24`、`inlineCode 12.5/18`、`table 12.5/18`。
- 其余裸字号：`22sp`（编辑器大数字，`PiSettingsEditors.kt`）、`18sp`、`12sp`（终端键 chip，`TerminalKeyBar.kt:279,316`）、`11sp`（键 chip，`:240`）、`20sp`、`14sp` 等见 5.4 直方图。
- 规则（代码里反复出现）：**人类写的文本用系统字体，机器输出用等宽**（`BlockChrome.kt:75-109` 的 `MonoText`/`ProseText`）。

### 6.2 行高 / 行距

- 正文 `bodyLarge` 行高 23sp，`meta` 16sp，`mono` 20sp，`monoSmall` 17sp（6.1）。
- markdown 段落间距 `PiSpacing.paragraphGap = 12.65dp`（`PiTheme.kt:53`）与列表缩进 `listIndent = 23dp`（`:56`）：**定义存在但零引用**（实际 markdown 段落/列表间距由 `render/PiMarkdownTheme.kt` 的 `piMarkdownPadding` 给：`block=2dp`、`list=4dp`、`listItemTop/Bottom=2dp`、`listIndent=12dp`）。
- markdown padding（`render/PiMarkdownTheme.kt:279-290`）：`block=2dp`、`list=4dp`、`listItemTop/Bottom=2dp`、`listIndent=12dp`、`codeBlock=horizontal 12dp / vertical 10dp`、`blockQuote=horizontal 12dp / 0`、`blockQuoteText=vertical 4dp`；dimens（`:315-324`）：`dividerThickness=1dp`、`codeBackgroundCornerSize=12dp`、`blockQuoteThickness=3dp`、`tableCellWidth=160dp`、`tableCellPadding=16dp`、`tableCornerSize=8dp`。

### 6.3 内边距 / 圆角 / 列表项高度

- 页面左右内边距 = `PiSpacing.screen = 16dp`（几乎所有屏）；卡片内边距 `PiSpacing.card = 12dp`（`BlockChrome.kt:179`）、`DeviceCapabilityScreen.kt` 的 `Card` 用 `14.dp`（`:888`）、`SettingsHome.CurrentModelCard` 用 `14.dp`（`:216`）。
- 列表项高度：会话行 `horizontal 16dp / vertical 12dp`（`SessionsScreen.kt:308`）；设置行 `PiSwitchRow` vertical `10dp`（`PiCommon.kt:304`）、`PiValueRow` vertical `12dp`（`:345`）、`GroupEntry` vertical `14dp`（`SettingsHome.kt:257`）、`NoticeRow` vertical `12dp`（`LicensesScreen.kt:197`）、sheet 内行 `vertical 10~12dp`。
- 块间距：转录 `LazyColumn` 的 `verticalArrangement = spacedBy(blockSpacing)`，默认 `PiSpacing.screen = 16dp`；`app.appearance.messageDensity` 改写：`compact → PiSpacing.unit/2 = 9dp` 且水平 `12dp`，`cozy → PiSpacing.unit*4/3 = 24dp`（`ChatScreen.kt:927-932`）。
- 圆角：见 5.3 `PiShapes`；出现最多的是卡片 16dp 与内嵌块 12dp。
- 状态行固定 32dp（`PiCommon.kt:153`）；终端键 chip：普通键 `40dp × 34dp`、圆角 6dp（`TerminalKeyBar.kt:260-268`），动作 chip（`PASTE`/`重开`）`62dp × 34dp`、圆角 6dp（`:307-308`）；键条容器 `padding(horizontal 6dp, vertical 4dp)`、行距/列距 4dp（`TerminalKeyBar.kt:209-216`）；转录默认窗口 50 行（`ChatScreen.kt:1814`）；工具卡默认折叠预览 200 行/200KB 上限（`ToolCallBlock.kt:227-228`）；diff 上限 200 行（`DiffBlock.kt:247`）；sheet 内容高度上限：会话工具/统计 `560dp`（`ChatSheets.kt:298,481`）、模型/思考/分叉 `420dp`（`ChatSheets.kt:110,216,579`）、扩展选择/输入对话框 `360dp`（`ExtensionDialogs.kt:91`）。

### 6.4 深色 / 浅色支持

- 明暗由 **pi 主题**决定：`PiResolvedTheme.dark`（`PiThemeFiles.kt:74`，解析 `theme` 设置，支持 `lightTheme/darkTheme` 自动配对，`:203-231`）；`MainActivity.kt:29-45` 把 `isSystemInDarkTheme()` 作为配对解析输入传给 `PiTheme(dark = theme.dark, palette = theme.palette)`。
- 未读到设置前的回退：`PiResolvedTheme.fallback(systemDark)` → `dark`/`light` 内置（`:83-93`）。
- `piMarkdownColors`/`piAlertColors` 另收一个 `darkTheme = isSystemInDarkTheme()`（`PiMarkdown.kt:90`）——这是库自身的明暗标志，与 pi 主题解析是**两个独立输入**（主题为浅色而系统为深色时二者会不一致；仅影响 alert 组件的派生色与 `DefaultMarkdownColors` 内部对）。
- 终端独立：`TerminalPalette.dark()/light()` 按 `MaterialTheme.colorScheme.background` 的亮度选择（`TerminalPane.kt:87-88`），与 pi 主题只间接相关（`background = pageBg`）。
- 无独立的"跟随系统"开关：跟随系统是通过 `theme` 设置的 `light/dark` 配对值实现的。

---

## 7. 不确定 / 存疑清单

1. **`PiPackagesHost` / `PiPackagesScreen` 的界面细节**：文件在 `app/src/main/kotlin/app/pi/packages/`，不在任务指定的 `ui/` 树下，本次只确认了挂载点（`PiSettingsStack.kt:352-358`）与入口行；未逐节核对板块与文案。
2. **`PiSettingsCatalog` 的完整 41 pi + 26 App 键名清单**：`PiSettingsRegistry.kt` 共 1495 行，本次只提取了 12 个分组标题、节标题样例与分组摘要；每个键的 `title`/`description` 未逐一登记（如需可另出一份键级清单）。
3. **`DeviceCapabilityCard` 内部逐能力的按钮/状态文案**：只核对了 220-360 行的骨架与 435-780 行的按钮文本，中间按能力分叉的文案（例如每个能力的具体说明句）未逐条抄录。
4. **`ChatScreen` 搜索命中的 block 级高亮**：确认了 `searchMatchBg`/`searchMatchText` 用在行 `background`/`border`（`:992-999`），但"当前命中"和"其它命中"的视觉差异只有边框有无——是否还有其它指示器未逐帧确认。
5. **`PiMotion` 已确认零调用点**（`grep -rn "PiMotion\." ui/` 只命中自身 KDoc 与 6 个 `@Deprecated` 声明）：App 里没有任何自造动画，唯一相关代码是关闭 markdown 库的尺寸动画（`PiMarkdown.kt:184-185`）。**不确定**：这是有意"零动画"还是遗留未接线；重设计时需用户决定是否启用。
6. **`PiElevation` / `PiIcons` 已确认零调用点**（见 5.3）；全 App 仅 4 处显式 elevation：`SlashPalette.kt:56`、`MentionPalette.kt:54`、`BashPanel.kt:59`（均 `tonalElevation = 2.dp`）与 `ChatScreen.kt:1059`（`shadowElevation = 3.dp`）。
7. **终端 xterm 16 色**：`TerminalPalette.kt:5-13` 的注释说 16 色由 libvterm 自带；本次未在依赖源码中核对具体值。
8. **`PiLatex` 的公式排版细节**：只读了文件头（`:1-40`）与在 `PiMarkdownComponents.kt:179-197` 的绘制方式；987 行里的符号表未逐项核对。
9. **`SessionsScreen` 的 `listItem=72dp` / `bottomBar=64dp`**：`PiSpacing` 里定义但未见引用（会话行是自己 pad 出来的）；实际列表项高度由 `12dp` 上下 padding + 三行文本决定，**不是** 72dp。
10. **主题切换时的重绘路径**：`PiTheme` 用 `LocalPiPalette` 注入；`rememberPiHighlightedCode` 以 `palette` 为键重算颜色（`PiMarkdownComponents.kt:433`），但高亮 span 本身缓存（不重跑后端）——非流式块在切换主题时是否立即重绘，本次未做运行时验证（无设备）。
11. **`onAccent` 的推导可能与 pi 不一致**：`PiPalette.colorScheme()` 用 `accent` 的亮度推导 `onPrimary/onSecondary/onTertiary/onError`（`PiTheme.kt:399`），而 pi 主题文件里并没有这些"前景色"字段——这是 App 的自造规则。**不确定**：pi 的终端主题是否有更合适的对应 token（如 `text`）；未在 pi 侧核对，列为设计决策点。
12. **pi 主题的 `notes` / `error` 展示面**：`PiThemeLoader` 会产出中文告警（`PiThemeFiles.kt:362-375` 的"缺少这些颜色定义…"、"256 色索引颜色…"等）与 `error` 文案（`:239-243, :258`），`PiSettingsStack` 把它们作为 `themeNotes`/`themeError` 传给主题编辑器（`PiSettingsStack.kt:174-176, 389-391`）；**未确认**主题错误是否还有第二个展示点（例如启动时的 Snackbar）。
