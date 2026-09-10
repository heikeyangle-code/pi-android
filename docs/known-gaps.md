# 已登记的缺口与延期项

这份文档存在的理由：**延期项不能只活在对话里。**在此之前，"以后再做"的判断散落在聊天记录和编码会话的任务列表里，一旦会话结束就丢了。凡是有明确原因被推迟、或者已知但尚未实现的，全部记在这里，并写清楚：为什么延期、卡在哪、怎么收尾。

格式约定：每条都有 **原因**（为什么不现在做）和 **收尾条件**（什么情况下可以做）。

状态标记：
- `延期` —— 想清楚了，有明确的阻塞点，解开了就做
- `未开始` —— RPC 路线上可实现，还没动
- `待验证` —— 代码写完了，但**从未在真机上跑过**
- `已定妥协` —— 不是待办，是刻意的设计取舍，写在这里以免被当成 bug

---

## A. 延期（有明确阻塞点）

### A1. 压缩摘要 / 分支摘要 / Hook 消息改用 markdown 渲染
- **为什么该做**：这三块的内容是**模型写的散文**。pi 源码里 `compactionSummary` 走 `CompactionSummaryMessageComponent`，其第 43 行是 `new Markdown(header + this.message.summary, ...)`；`branchSummary` 同样走 markdown 组件；`HookMessageBlock` 的数据字段名直接就叫 `markdown`。纯文本渲染会把这些内容里的标题、列表、粗体、代码全部显示成源码。
- **阻塞点**：`CompactionBlock` 和 `BranchSummaryBlock` 现在用 `maxLines`（2 行 / 4 行）实现"收起预览，点开展开"。markdown 渲染器**没有 `maxLines`**，直接替换会**丢掉折叠预览**——那是实际功能，不能为了保真把它弄坏。
- **收尾条件 / 做法**：折叠态继续用纯文本 + `maxLines`（把前 N 行文字截出来即可），展开态切到 `PiMarkdownText`。约 30 行改动，落在 `ui/blocks/CompactionBlock.kt`、`BranchSummaryBlock.kt`、`HookMessageBlock.kt`。
- **还没定的**：`ErrorBlock` / `SystemPromptBlock` / `SkillInvocationBlock` 是否也该走 markdown —— 需要逐个回 pi 源码确认它的渲染路径，不能凭感觉。

### A2. LaTeX（行内 `$...$` / 块级 `$$...$$`）渲染
- **为什么该做**：pi 支持 LaTeX（`packages/tui/src/components/markdown.ts` 里的 `LATEX_MARKDOWN_EXTENSIONS` + `renderLatex()`）。我们的解析层其实**已经能识别**：`org.intellij.markdown` 的 GFM flavour 定义了 `GFMElementTypes.INLINE_MATH`、`BLOCK_MATH`，还带一个 `MathGeneratingProvider`。缺的只是渲染。
- **阻塞点**：`MarkdownComponents.custom()` 的调用约定有陷阱 —— 渲染器的分派代码是
  `handled = components.custom?.invoke(node.type, model) != null`，
  而 `custom` 的返回类型是 `Unit`（永远非 null）。**一旦提供 `custom`，所有未识别的块级节点都会被判定为"已处理"**，从而关闭原本的"递归渲染子节点"兜底。所以必须先决定：要么让 `custom` 只接管 math 类型并把其余类型显式交给默认组件，要么在扩展里自建 flavour。
- **收尾条件**：先解决上面这个分派陷阱（写一个只认 math、其余类型转发默认行为的 `custom`），再接公式排版组件。

### A3. 图片（markdown 内嵌 `![](...)` ）
- **为什么该做**：库里 `MarkdownImageKt` / `MarkdownInlineImageKt` / `ImageTransformer` / `ImageData` / `PlaceholderConfig` 一应俱全，但默认实现是 `NoOpImageTransformerImpl`（什么都不加载）。所以现在 markdown 里的图片显示为 alt 文本。
- **阻塞点**：pi 的图片不是 http URL，而是引擎侧的文件（会话附件、工作区里的文件）。要接就得先确定"引擎侧文件怎么按需取字节"这条通道（与设备桥类似，需要一个回环接口或直接读 guest 文件系统）。
- **收尾条件**：定下取字节的方式后，实现一个 `ImageTransformer` 并通过 `LocalPiImageTransformer` 注入。
- **附注**：pi 的**终端界面根本不渲染图片**（终端放不下），所以这一项如果我们做了，是**比 pi 强**，不是补齐。

---

## B. 未开始（RPC 路线可实现，尚未动）

### B1. `/` 命令面板
- 引擎支持 `get_commands`，返回扩展命令、prompt 模板、skills 三类，`source` 字段区分来源。
- `:rpc` 里 `PiCommands.getCommands(id)` 已经存在，但**没有任何调用方**。
- 收尾：输入框键入 `/` 时拉取并分组展示，选中后按 `prompt` 命令发送。

### B2. 会话管理：会话树 / fork / clone / switch / 重命名 / 导出
- `get_tree`、`get_entries`、`get_fork_messages`、`fork`、`clone`、`switch_session`、`set_session_name`、`export_html` 的 builder 全部已存在，**全部无调用方**。
- 收尾：会话树 UI（分支可视化）+ 详情页动作。

### B3. 模型与思考等级选择器 / 队列模式 / 压缩与重试开关
- `get_available_models`、`set_model`、`cycle_model`、`get_available_thinking_levels`、`set_thinking_level`、`cycle_thinking_level`、`set_steering_mode`、`set_follow_up_mode`、`compact`、`set_auto_compaction`、`set_auto_retry`、`abort_retry` —— builder 全部存在，**全部无调用方**。

### B4. bash 模式（在会话里直接跑命令）
- `bash` / `abort_bash` 两个 builder 存在且未使用。

### B5. 扩展包管理（`pi install` / `pi list` / `pi remove`）
- **注意：RPC 协议里没有对应命令。** 它不能靠协议做，只能由 App 直接编辑 `settings.json` 的 `packages` 数组，然后**重启引擎**让 pi 重新解析。
- 另有依赖规则：pi 以 `npm install --omit=dev` 安装，`devDependencies` 在运行期不可用。

### B6. `/reload` 热重载
- **RPC 协议里也没有对应命令**（我核对了 `docs/rpc.md` 的完整命令表）。
- 替代方案：重启引擎进程。对用户表现为"重载扩展 → 会话重启"，需要设计确认提示（未保存的会话状态）。

### B7. 项目信任弹窗
- `.pi/extensions` 只在项目被信任后才加载；信任记录在 `trust.json`。设置页已有信任项，但**打开未信任项目时没有询问流程**。
- 风险背景：扩展是**满权限任意代码**，pi 自己的文档对此有明确警告。

### B8. 扩展 UI 的其余接线（子代理正在做，此处留档）
- 阻塞式对话框（`select`/`confirm`/`input`/`editor`）：`:rpc` 能解析、也有回包构造器，但 App 侧**一行处理都没有** → 引擎会永久阻塞。**这是当前最高优先级的缺陷。**
- 即发即忘（`notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text`）：同上，需要 UI 承载。
- `extension_error` 事件：解析了但没展示，等于静默失败。
- **全局承载点**：扩展弹窗目前只能挂在"对话/工作区"两个页面，用户停留在"会话/设置"页时弹窗排队不可见，而引擎正在等回复（`editor` 还**没有超时**，见下）。修法是在 `PiRoot.kt` 的 Scaffold 里挂一个全局 `ExtensionUiHost`。
  - **必须成对操作**：`PiRoot` 的 `Box` 里渲染的就是当前页面，所以**不能**在 PiRoot 和页面里同时挂——会变成两个 host，同一个请求弹两次窗、两个 Snackbar 抢同一个通知队列。正确顺序：先从 `ChatScreen.kt` / `WorkbenchScreen.kt` 摘掉，再加到 `PiRoot.kt`。
  - **状态**：等对话框子代理摘除页面里的挂载后，由主线加 PiRoot 那一行。

### B11. `PiEvent.ExtensionError` 丢掉了 `extensionPath`
- **现状**：数据类只有 `message`，而 `EventUiRequest` 保留了 `raw`。扩展抛错时定位不到是哪个扩展。
- **收尾**：给数据类加字段 + 在 `extension_error` 解析分支里读——**一行改动**，但 `rpc/Events.kt` 当时被 RPC 命令面子代理占用，不能并发改，故排队。

---

### B9. markdown 渲染库升到 0.42+ / 0.45
- **现状**：用 0.41.0。原因是硬约束：0.42.0 及更新的 AAR 要求 `minCompileSdk=37`，而本项目 `compileSdk = 36`（AGP 8.13.2）；升 37 需要 AGP 9.x，而版本目录里明确记录了刻意避开 AGP 9.x 的大版本迁移。
- **代价**（相对 0.45.0，均已核对）：
  - 没有 GFM alert（`> [!NOTE]` 会退化成普通引用块）
  - `MarkdownTypography` 没有 `alertTitle`
  - 没有 `StreamingMarkdownState` 重载（流式专门优化的解析状态）——**目前没用它**，用的是 String 重载
- **收尾条件**：升级 AGP 到 9.x 且 `compileSdk` 能到 37 时，改版本号 + 补回 `darkTheme`/`alertTitle` 两个参数即可（`MarkdownCodeBackground` 等组件签名 0.41 与 0.45 一致，已核对）。

### B10. `libprootloader.so` 不是 PIE —— 装机时可能被拒
- **背景**：`proot` 是 `ET_DYN`(PIE) 且引用 `/system/bin/linker64`，符合 Android 的 exec 要求；但 Termux 的 `loader` 是 **`ET_EXEC`（非 PIE）**，18,136 字节。它是被 proot **映射**（`PROOT_LOADER`）而不是被 exec 的，所以 PIE 规则本不适用于它 —— `tools/fetch-runtime.mjs` 里那个无条件 PIE 校验已经改成只对"会被 exec"的条目生效。
- **未验证**：Android 的安装器/原生库提取对 `lib/arm64-v8a/` 下**非 PIE 的 `lib*.so`** 是否有额外校验。如果真机装机或启动时报错，退路是：只有 `proot` 必须放在 `jniLibs`（它要被 exec，才需要那个伪装和可执行权限），loader 可以改放进 rootfs 资产里，`PROOT_LOADER` 指向 guest 路径即可 —— 它只需要可读。
- **收尾条件**：真机跑通 boot。

## C. 待验证（代码已写，从未在真机运行）

> 这一节是整份文档里最重要的部分。上面所有 UI 都在**没有验证过地基**的前提下写的。

### C1. 运行时能否在真机上启动 —— **唯一的关键路径**
- proot + Ubuntu 24.04 + 官方 Node 24 + pi 在 Android 14 (SDK 34, aarch64) 上真正跑起来。
- 已知约束：Android 10+ 禁止对 `app_data_file` 执行 `execve()`，可执行文件必须是 `jniLibs` 里的 `lib*.so`（PIE + `/system/bin/linker64` + `PROOT_LOADER` 指向 `nativeLibraryDir`）。
- **现状**：两个 11MB 的 APK 能装、不崩 —— 但那是**不含运行时**的版本。含完整运行时的 APK（约 200MB）**从未构建出来跑过**。
- 收尾：出一次带运行时的 APK，在真机上跑通 `boot()`，这是解开其它一切验证的前提。

### C2. 自写 VT 终端模拟器
- 已覆盖：备用屏幕 `1049`、括号粘贴 `2004`、OSC（标题/超链接）、SGR、滚动区域 `DECSTBM`、插入/删除行、ED/EL、鼠标、宽字符与组合字符。
- **未验证**：真实 TUI 程序的转义序列覆盖率、中文输入法、`script(1)` 在打包 rootfs 里是否存在、真机输入延迟。
- 退路：WebView + xterm.js（App 已用 WebView 承载 Mermaid）。

### C3. 设备桥的真机行为
- 无障碍截屏（API 34 安全窗口限制）、后台启动 Activity 分享/打开、跨安装读取 MediaStore 导出文件、失焦时读剪贴板。
- shell 后端只有 app uid（未接 Shizuku / ADB 无线调试）。

### C4. 代码高亮服务
- 方案：扩展在引擎进程内起回环 HTTP 服务，用**原版 highlight.js 10.7.3**（`--mode rpc` 下 pi 自己并不加载它）。
- **未验证**：真机上 Node 侧加载耗时与内存增量、回环往返延迟、引擎未启动时的降级表现。

---

## D. 已定妥协（设计取舍，不是缺陷）

### D1. 引用块的竖条颜色 = 引用文字颜色
渲染器的 block quote 用**同一个颜色**画竖条和文字，pi 是两个 token（`mdQuoteBorder` / `mdQuote`）。pi 内置的 dark/light 两套主题里这两个值**恰好相同**，所以出厂主题下输出一致；只有用户自写主题把两者设成不同值时才会丢失这个区分。

### D2. `mdLinkUrl` 在手机上无处可显示
终端里 pi 会把 URL 用括号打印在链接文字后面；手机上是原生链接，不打印 URL 文本。该 token 仍可从调色板取到（供链接详情面板使用）。

### D3. TUI-only 扩展需要走终端标签页
`ctx.ui.custom()` 以及 `setFooter`/`setHeader`/`setWorkingMessage`/`setWorkingIndicator`/`setEditorComponent`/`setToolsExpanded`/`getTheme` 在 **pi 自己的 RPC 模式**下就是 no-op（`docs/rpc.md` 明确列出）。任何 RPC 客户端都拿不到。出路是工作区终端的 **`pi TUI（原版）`** 标签页 —— 在那里跑的是未修改的原版 pi。按 pi 自带 69 个示例扩展统计：48 个（70%）走 RPC 就能完整工作，9 个（13%）需要对话框接线，12 个（17%）需要真终端。

### D4. 表格与其他元素改用手机版排版
终端只有一种字和一种字号，pi 的标题只靠粗体/下划线区分。App 保留**颜色**一致，另外加了字号层级 —— 手机窄栏里同字号的标题不可读。pi 自己的 HTML 导出为了同样的原因做了同样的取舍。
