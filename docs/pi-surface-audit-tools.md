# pi 能力面 × App 实现对照审查：工具 / 扩展钩子 / 技能 / 上下文压缩 / 附件 / agent 内核

- **被审 pi**：`/root/pi-src`，HEAD `bbb61e3`，`packages/coding-agent` 版本 **0.85.1**（`packages/coding-agent/package.json:3`）。
- **被审 App**：`/root/pi-android` 的 `app/src/main/kotlin/app/pi/**`、`rpc/src/main/kotlin/app/pi/rpc/**`、`app/src/main/assets/pi-extensions/**`。
- **方法**：pi 侧每条结论都带 `file:line`；App 侧只给**符号名**（少数给行号，因为要证明"这一支存在/不存在"）。未编译、未运行 Gradle、未改动任何既有文件；本文件是本次唯一新增文件。
- **标注**：`{读}` = 我直接读到的代码事实；`{推}` = 我据代码推断出的结论。`README` 未作为实现证据。
- **口径**：「pi 有的必须 1:1，pi 没有的一律不做」；**TUI 专属不算欠账**，单列附录 A。

---

## 0. 结论先行

**还剩 1 条真欠账（D1 已实现，D2 未做——原因见 §9 D2）。**

| # | 欠账 | 严重度 | 一句话 |
|---|---|---|---|
| **D1** | **会话内分支切换 + 分支摘要**（pi 的 `navigateTree`） | ~~高~~ **已实现** | 原状：pi 有 `session.navigateTree()` 与 `/tree`，RPC 线上没有任何命令能触发它，`BranchSummaryBlock` 永远收不到输入。现已通过随包扩展命令 `pi-android-navigate` + App 侧的分支重取接上（见 §9 D1「已实现」）。 |
| **D2** | **扩展注册的 CLI flag 透传**（`registerFlag` / `getFlag`） | 低 | pi 把扩展 flag 从进程 argv 解析后交给 `flagValues`；App 的启动参数表只列了固定的几项，依赖 flag 的扩展在 App 会话里永远读到默认值（终端页跑原版 TUI 可绕过）。 |

**其余各面（§1 内建工具、§2 工具钩子、§3 扩展系统、§4 技能、§5 上下文与压缩、§6 附件与图片、§7 agent 内核）在当前 pi 的 RPC 面上都已 1:1**，不是"看起来像"而是逐条核过：

- **8 个内建工具**全部有专属工具卡分支（`BlockRenderer`），参数 / 截断 / 超时 / `executionMode` 逐条对得上；`executionMode` 在 8 个内建工具里**一个都没设**（只有扩展工具能用），所以"顺序执行"不是 App 欠账。
- **`extension_ui_request` 的 9 个 method 全部有落点**，没有静默丢弃（详见 §3 表）。
- **RPC 的 33 条命令与 40 个事件名（含 assistant 流式子事件）全部被 App 消费**（`Commands.kt` / `Events.kt`），我逐条对过 `rpc-types.ts:20-74` 与 `rpc-types.ts:247-281`。
- **设置键**（含 `compaction.*`、`retry.*`、`images.*`、`defaultTools`、`enableSkillCommands`、`branchSummary.reserveTokens`）逐条映射；不暴露的键都写了理由（`PiPreSpawnConfig.NOT_EXPOSED_PRE_SPAWN`）。

**`extension_ui_request` 各 kind 处理情况（一句话）**：pi 在 RPC 模式只会发这 9 个 method —— `select`/`confirm`/`input`/`editor`（阻塞、要回 `extension_ui_response`）与 `notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text`（fire-and-forget）；**9 个我们全部处理并且都有界面落点**，pi 协议里另外那些（`custom`、`setWorkingMessage`、`setFooter`、`setHeader`、`setTheme`…）在 pi 自己的 `rpc-mode.ts` 里就是 no-op，**根本没有上线**，所以不构成欠账。

---

## 1. 内建工具全量：pi 注册了什么，行为面是什么，我们呈现了吗

### 1.1 工具清单与行为面 `{读}`

pi 的工具全集是 `ToolName = "read" | "bash" | "powershell" | "edit" | "write" | "grep" | "find" | "ls"`
（`packages/coding-agent/src/core/tools/index.ts:95-105`），默认启用 `["read","bash","edit","write"]`
（`packages/coding-agent/src/core/sdk.ts:256`；`core/system-prompt.ts:45` 同值），`defaultTools` 设置可改（`core/settings-manager.ts:1319-1322`）。

| 工具 | 参数（`file:line`） | 截断上限 | 超时 | `executionMode` | 结果如何回到上下文 |
|---|---|---|---|---|---|
| `read` | `path` / `offset` / `limit`（`core/tools/read.ts:22-27`） | `truncateHead` 2000 行 **或** 50KB 先到先算（`core/tools/truncate.ts:11-12`，用于 `read.ts:150`）；单行超 50KB 时整行替换为提示（`read.ts:155`） | 无 | 未设 | `content: (Text | Image)[]`，图片走附件（`read.ts:107-140`），文本带 `[Showing lines X-Y of N. Use offset=…]` 尾注（`read.ts:162-166`） |
| `bash` | `command` / `timeout`(秒)（`core/tools/bash.ts:36-39`） | **截尾** 2000 行 / 50KB，并写全文到临时文件（`bash.ts:234`、`:320-331`），结果文本追加 `Full output: <path>` | 可选，上限 `2_147_483_647 ms`（`bash.ts:21-34`） | 未设 | 文本 + `details.truncation` / `details.fullOutputPath`（`bash.ts:268`、`:321`） |
| `powershell` | 同 `bash`（`core/tools/powershell.ts:40`；`UTF8_OUTPUT_PREFIX` 见 `:16`） | 同 `bash` | 同 `bash` | 未设 | 同 `bash` |
| `edit` | `path` + `edits[]:{oldText,newText}`（`core/tools/edit.ts:33-41`）；兼容**旧式**顶层 `oldText`/`newText`（`edit.ts:44-47`） | 无独立截断 | 无 | 未设 | 与写并发安全：`withFileMutationQueue` 按 realpath 串行化同文件写（`core/tools/file-mutation-queue.ts:31`） |
| `write` | `path` / `content`（`core/tools/write.ts:11-14`） | 无 | 无 | 未设 | 自动 `mkdir -p`（`write.ts:24-27`），同样入写队列 |
| `grep` | `pattern` / `path` / `glob` / `ignoreCase` / `literal` / `context` / `limit`（`core/tools/grep.ts:20-33`） | `DEFAULT_LIMIT=100` 条（`grep.ts:41`）或 50KB（`grep.ts:282-299`）；**单行截到 500 字符**（`core/tools/truncate.ts:13`） | 无 | 未设 | 每行 `path:line: text`，context 行为 `path-line- text`（`grep.ts:209-212`）；`details.matchLimitReached` / `linesTruncated` |
| `find` | `pattern` / `path` / `limit`（`core/tools/find.ts:25-31`） | `DEFAULT_LIMIT=1000`（`find.ts:41`）或 50KB（`find.ts:147-156`） | 无 | 未设 | 相对路径一行一条，目录带 `/` 后缀（`find.ts:20-23`） |
| `ls` | `path` / `limit`（`core/tools/ls.ts:12-15`） | `DEFAULT_LIMIT=500`（`ls.ts:23`）或 50KB（`ls.ts:141-151`） | 无 | 未设 | 字母序，目录带 `/`，含点文件（`ls.ts:62`） |

补充的行为面：

- **默认并行**：`Agent` 的 `toolExecution` 默认 `"parallel"`（`packages/agent/src/agent.ts:237`），`executeToolCalls` 只在 `config.toolExecution === "sequential"` **或**该批里有一个 `executionMode === "sequential"` 的工具时才串行（`packages/agent/src/agent-loop.ts:409-421`）。**8 个内建工具没有任何一个声明 `executionMode`**（`core/tools/*.ts` grep 无命中；只有 `tool-definition-wrapper.ts:14,44` 做透传），所以并行/顺序在 pi 里实际是"扩展工具才能触发"的开关。`{读}`
- **中途流式结果**：只有 `bash` 真正调 `onUpdate`（`core/tools/bash.ts:261-297`），经 `tool_execution_update` 事件出去（`packages/agent/src/agent-loop.ts:687-700`）。`{读}`
- **`length` 截断的自我防护**：若 assistant 因输出 token 上限被截断（`stopReason === "length"`），该消息里**所有**工具调用一律失败，不执行（`packages/agent/src/agent-loop.ts:229-235` + `:380-403`）。`{读}`
- **constrained sampling**：`read`/`bash`/`edit`/`write` 声明了 `constrainedSampling: {type:"json_schema", strict:"prefer"}`（`read.ts:77`、`bash.ts:238`、`edit.ts:156`、`write.ts:57`），**`grep`/`find`/`ls` 没有**——这是模型层行为，与客户端无关。`{读}`
- **工具结果里的图片会被归一化**：扩展/截图工具塞回的任意 base64 先过 `processImage` 再进历史（`core/tools/../utils/tool-result-images.ts:12-60`，挂在 `afterToolCall` 上，见 `core/agent-session.ts:521-523`）。`{读}`
- **`details` 与 `usage` 随 `ToolResultMessage` 一起走**（`packages/agent/src/agent-loop.ts:784-798`），`content` 进模型上下文；`addedToolNames` 是 provider 侧"延迟工具加载"的挂点（`packages/ai/src/types.ts:453-465`）。`{读}`

### 1.2 逐条对 App：**8/8 都有专属卡**，无呈现层欠账

证据：`app/src/main/kotlin/app/pi/ui/blocks/BlockRenderer.kt:108-133`。

| pi 工具 | App 的分支 | 状态 |
|---|---|---|
| `read` | `ReadBlock`（`BlockRenderer.kt:122`），行号来自 `args.offset`（`ReadBlock` KDoc 引 `renderers/read.ts:28-33`） | 有专属卡 |
| `write` | `WriteBlock`（`:123`） | 有专属卡 |
| `edit` | `EditBlock`（`:124`）；diff 另有 `DiffBlock`（`:134`） | 有专属卡 |
| `grep` | `GrepBlock`（`:125`）；context 行与命中行同色，**与 pi 的 `renderers/grep.ts:52` 一致** | 有专属卡 |
| `find` | `FindBlock`（`:126`） | 有专属卡 |
| `ls` | `LsBlock`（`:127`） | 有专属卡 |
| `bash` | `ShellBlock`（`:130`），含 pi 的 ` (timeout Ns)` 后缀（`ShellBlock` KDoc 引 `renderers/bash.ts`） | 有专属卡 |
| `powershell` | 与 `bash` **共用** `ShellBlock`（`:130`，注释点明 pi 的 `renderers/index.ts:35-36` 也是同一个工厂，只差提示符） | 有专属卡 |
| 带图片的结果 | `ToolCallBlock`（`:112-114`）+ `ImageGridBlock` + `PiImageViewer` | 有专属卡 |
| 其它（扩展工具） | `ToolCallBlock` 通用卡（`:131` `else`） | 与 pi 的 `withBuiltInRenderers`（`core/tools/renderers/index.ts:51-63`）同口径 |

截断/全文提示也对得上：`ToolOutputParse` 的 `ToolTruncation`、`fullOutputPathOf`、`stripFullOutputFooter`、`truncationNotice` 复刻了 pi 的 `renderers/bash.ts:59-64`、`:100-118`。

> **一处文档级瑕疵（非功能）**：`GrepBlock.kt` 的 KDoc 在"context 行取 `contextOnTool` 颜色"（`:181-183`）与"所有行同一个 `toolOutput` token"（`:205-206`）之间自相矛盾；**代码是后者**，与 pi 的 `renderers/grep.ts:52` 一致。更强的一条证据：`contextOnTool` 这个 token **在 pi 的主题里根本不存在**，pi 的 diff 渲染器用的是 `toolDiffContext`（`modes/interactive/components/diff.ts:89`、`:141`，token 定义 `modes/interactive/theme/theme.ts:73`），且全仓只有 diff 用。建议删掉 `GrepBlock.kt:181-183` 那句。

---

## 2. 工具执行前后的钩子与审批能力（重点）

### 2.1 pi 提供了什么 `{读}`

| 口子 | 位置 | 语义 |
|---|---|---|
| `Agent.beforeToolCall` | `packages/agent/src/agent.ts`（字段）+ `packages/agent/src/agent-loop.ts:626-660` | 执行前回调。返回 `{block:true, reason?, terminate?}` 则**不执行**，结果被替换成一条 error tool result（`agent-loop.ts:643-655`）；`terminate:true` 还会置结果上的 `terminate`（`:645-646`） |
| `Agent.afterToolCall` | `packages/agent/src/agent-loop.ts:731-753` | 执行后回调，可替换 `content` / `details` / `usage` / `terminate` / `isError`（`:738-748`）；抛异常则整条结果变成 error（`:749-751`） |
| 扩展事件 `tool_call` | `core/extensions/types.ts:1298`（注册）、`:1125-1134`（`ToolCallEventResult`） | `{block?, reason?, terminate?}`。**改参数的方式是原地改 `event.input`**（`:1126` 注释）。runner 里首个 `block` 立即短路（`core/extensions/runner.ts:987-1005`） |
| 扩展事件 `tool_result` | `types.ts:1299`、`:1144-1149` | 可改 `content`/`details`/`isError`/`usage`（`runner.ts:928-980`） |
| 谁把它们接起来的 | `core/agent-session.ts:483-502`（`beforeToolCall` → `runner.emitToolCall`）、`:504-535`（`afterToolCall` → `emitToolResult` + 图片归一化） | 注释明说"拦截从 wrapper 移到了这里"（`:474-481`） |
| 问用户 | `ctx.ui.select/confirm`（`types.ts:134-137`） | 在 RPC 模式就是 `extension_ui_request` 的 `select`/`confirm`（`modes/rpc/rpc-mode.ts:137-151`） |

### 2.2 关键结论：pi **没有**内建的 per-tool allow/ask/deny 语义

- `--approve` / `--no-approve` **不是工具审批**，是**项目信任**（`cli/args.ts:219-222`、`:316-317`；`core/project-trust.ts:25`）。`{读}`
- 全仓 grep `allowlist|permission|approve|requiresConfirmation|needsApproval`：命中的只有 `--tools` 的"工具白名单"、项目信任、包管理器 `--approve`，**没有任何"某个工具需要用户确认"的内建字段**。`{读}`
- pi 自己给出的答案是一个**示例扩展**：`packages/coding-agent/examples/extensions/permission-gate.ts:12-37`（只守 `bash`，`ctx.ui.select` 问一次，无 UI 时 `block`）。

### 2.3 能不能在 RPC 模式下用？能，而且我们**已经用了**

- 唯一的通道是「**pi 进程内的扩展** 用 `pi.on("tool_call", …)` + `ctx.ui.*`」→ 我们收到 `extension_ui_request` → 回 `extension_ui_response`。RPC 命令表里**没有**任何审批/许可命令（`modes/rpc/rpc-types.ts:20-74` 全量 33 条，已逐条核对）。`{读}`
- 我们的实现：`app/src/main/assets/pi-extensions/pi-android-permission-gate.ts:131-227` —— `pi.on("tool_call")` + `ctx.ui.select(…, [仅允许这一次, 同意并记住本次会话, 拒绝], {timeout:120_000})`；无 UI / 对话框抛错 / 超时一律 `block`（`:161-169`、`:192-199`、`:219-226`）；会话级记忆在 `session_start` 清空（`:240-251`）。`{读}`
- 界面落点：`ExtensionDialogs.kt`（`select` 对话框）、`ExtensionUiHost.kt:123-127`。`{读}`

**但是**：这道门**只守 `android_*` 设备工具**（`permission-gate.ts:136-139` 的 `isDeviceTool` 早退），pi 自己的 `bash`/`read`/`write`/`edit` **一律不问**。这不是欠账：pi 也没有内建的、对 `bash` 逐条确认的行为，它只给了一个示例扩展，而示例只守 `bash`。按「pi 没有对应物的一律不做」，做一个**通用** per-tool allow/ask/deny 界面属于**新增功能**，不是补齐 pi。口子本身已经被我们占了（这一点是本次审查里最值得记的一条）。`{推，基于 §2.1–2.2 的证据}`

---

## 3. 扩展系统

### 3.1 `extension_ui_request` 的**全部** kind（RPC 线上实际会出现的 9 个）

权威清单：`packages/coding-agent/src/modes/rpc/rpc-types.ts:247-281`（`RpcExtensionUIRequest`）。响应 `:289-291`。

| method | pi 侧产生处 | 我们处理了吗 | 界面落点 |
|---|---|---|---|
| `select` | `rpc-mode.ts:137-141` | ✅ | `ExtensionUi.ExtensionDialogMethod.Select` → `ExtensionDialogs.kt`，无选项/超时 → `cancelled` |
| `confirm` | `rpc-mode.ts:142-146` | ✅ | `ExtensionDialogs.kt`，超时/取消 → `confirmed:false` |
| `input` | `rpc-mode.ts:147-151` | ✅ | `ExtensionDialogs.kt`（`placeholder`） |
| `editor` | `rpc-mode.ts:254-271`（**手写 promise，无 timeout**） | ✅ | `ExtensionDialogs.kt` 多行 `prefill`，**不显示倒计时**（`EXTENSION_UI_INTEGRATION.md` §2 明确写了原因） |
| `notify` | `rpc-mode.ts:152-161` | ✅ | `ExtensionUiHost.kt` snackbar，tone 来自 `notifyType`（`noticeToneOf`） |
| `setStatus` | `rpc-mode.ts:168-177` | ✅ | `PiSessionViewModel.setExtensionStatus` 收集 → `ChatSheets.kt:463-478` 的「扩展状态」段（不是页脚行，见 D-3 裁决） |
| `setWidget` | `rpc-mode.ts:195-208` | ✅ | `ExtensionWidgetStack`（`ExtensionChrome.kt`），`aboveEditor`/`belowEditor` 两种 placement，行数按 pi 的 10 行截断 |
| `setTitle` | `rpc-mode.ts:218-226` | ✅ | Chat AppBar 标题（`windowTitleOf`） |
| `set_editor_text` | `rpc-mode.ts:238-246` | ✅ | composer 一次性填充（`ComposerFill`，seq 消费） |

分发证据：`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:2277` → `onExtensionUi`，阻塞四种在 `:2400-2444`，fire-and-forget 五种在 `:2446-2492`；事件解析在 `rpc/src/main/kotlin/app/pi/rpc/Events.kt:550-570`。**没有任何一个 method 落到"丢弃"分支**（`else -> Unit` 只兜 pi 协议外的输入）。`{读}`

### 3.2 那些"看起来漏了"的 —— 其实是 pi 在 RPC 模式下自己不发

`ExtensionUIContext` 的完整方法集在 `types.ts:133-307`，但 RPC 实现里这些是**显式 no-op / 返回空**，所以**永远不会有 `extension_ui_request`**：`{读}`

| 方法 | rpc-mode.ts 行 |
|---|---|
| `onTerminalInput` | `:163-166`（返回空 unsub） |
| `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` | `:179-189` |
| `setHiddenThinkingLabel` | `:191-193` |
| `setFooter` / `setHeader` | `:210-216` |
| `custom()` | `:228-231`（返回 `undefined`） |
| `getEditorText()` | `:248-252`（永远 `""`） |
| `addAutocompleteProvider` / `setEditorComponent` / `getEditorComponent` | `:273-284` |
| `getAllThemes` / `getTheme` / `setTheme` | `:290-301` |
| `getToolsExpanded` / `setToolsExpanded` | `:303-310` |

`UIPromptKind` 里的 `"custom"`（`types.ts:745`）只用于 `ui_prompt_start/end` 的**事件标签**，不对应任何 RPC 请求。`{读}`

### 3.3 扩展能注册什么，生命周期与错误

| 注册面（`types.ts`） | 行 | RPC 模式下我们能看到吗 |
|---|---|---|
| `registerTool` | `:1308-1315` | ✅ 工具会出现在 `tool_execution_*` / `message_*` 里；无专属卡时走 `ToolCallBlock` |
| `registerCommand` | `:1317` | ✅ 出现在 `get_commands` → `PiSlashCommands.piCommandPalette` 的 `PiCommandSource.Extension` 组 |
| `registerProvider` / `unregisterProvider` | `:1486-1502` | ✅ 影响 `get_available_models`（`rpc-mode.ts:490-493`）→ App 的模型清单 |
| `registerShortcut` | `:1320-1327` | ❌ 只在 TUI 绑定（`runner.ts:557`）→ 附录 A |
| `registerFlag` / `getFlag` | `:1325-1345` | ⚠️ 见 **D2** |
| `registerMessageRenderer` / `registerEntryRenderer` | `:1352`、`:1358` | ❌ 返回 TUI `Component` → 附录 A |
| `registerMarkdownTransformer` | `:1355` | ❌ 只被 `modes/interactive/components/assistant-message.ts:32-40` 消费 → 附录 A |
| 事件订阅（33 个 `on(event: …)`，`types.ts:1257-1301`） | 同上 | ✅ 全部在 pi 进程内生效；我们通过 RPC 事件/命令的**结果**看到效果 |

**生命周期与错误处理**：`{读}`

- 加载失败只在加载期收集成 `{path,error}`（`core/extensions/loader.ts:575-590`、`:619-646`），非致命。
- 运行期任一 handler 抛错 → `runner.emitError` → RPC 输出 `{"type":"extension_error", extensionPath, event, error}`（`modes/rpc/rpc-mode.ts:347-350`）。
- **我们收到了**：`Events.kt` 解析 `extension_error` → `PiSessionViewModel.kt:2268` 推 snackbar（`ExtensionErrorText.kt` 负责把 `extensionPath` 变成扩展名）。
- 扩展上下文在 `newSession/fork/switchSession/reload` 后**失效**，pi 会直接抛"stale ctx"（`loader.ts:213`）；我们用 `pi-android-bridge/index.ts:1383-1389` 的 `device-reload` 主动调 `ctx.reload()` 走的就是这条路（notify 在 await 之前，`:1378-1382`）。

---

## 4. 技能（skills）

| 面 | pi 证据 | App 符号 | 状态 |
|---|---|---|---|
| 发现顺序 | `core/skills.ts:446-450`：先 `<agentDir>/skills`（user）后 `<cwd>/.pi/skills`（project）；同名先到者胜，冲突记 diagnostic（`:430-445`） | `WorkspaceResources` / `PiResourceDiscovery`（`ui/screens/WorkspaceResources.kt:23` 复刻同一规则），全局与项目分组显示 | ✅ |
| 额外目录 | `package-manager.ts:469`、`:2397`、`:2437-2488`：`<dir>/.agents/skills`、`~/.agents/skills`（并有独立的来源/baseDir 语义） | `ProjectResources.kt:134-135` 与 `WorkspaceResources.kt` 的"来源徽标"复刻 | ✅ |
| `--skill` / `skills` 设置 | `cli/args.ts:171-173`、`main.ts:710`；设置键 `skills`（`settings-manager.ts`） | 设置行 `skills`（`PiSettingsRegistry.kt:837`，`effective = RestartEngine`）+ `PiPreSpawnConfig` 的 `--skill` 映射 | ✅ |
| `SKILL.md` 格式 | frontmatter `name`/`description`/`disable-model-invocation`（`core/skills.ts:67-81`、`:335-342`）；目录里可以没有 frontmatter 也成技能 | 由 pi 解析；App 只读目录与文件正文 | ✅ 无需客户端逻辑 |
| `disable-model-invocation` 的语义 | 从系统提示的技能清单里剔除，但仍可 `/skill:name` 显式调用（`skills.ts:352-356`） | 由 pi 执行；App 无需逻辑 | ✅ |
| 技能进系统提示 | `formatSkillsForPrompt`（`skills.ts:355-390`）产 `<available_skills>` 块，仅当所选工具里有 `read` 或 `bash`（`core/system-prompt.ts:46`、`:160-163`） | 由 pi 组装 | ✅ |
| `/skill:name` 命令 | 由 `get_commands` 无条件列出（`modes/rpc/rpc-mode.ts:702-708`） | `PiSlashCommands.kt:353-360`：`PiCommandSource.Skill` 分组，且按 `enableSkillCommands` 过滤 | ✅ **比 pi 的 RPC 更细**（pi 的 RPC 无条件给，TUI 才看这个开关，`interactive-mode.ts:716`、`:4570`） |
| `enableSkillCommands` 开关 | `settings-manager.ts:1165-1170` | 设置行（`PiSettingsRegistry.kt:873`，`Immediate`，说明注释点明 RPC 侧不受影响） | ✅ |
| 用户可见的"管理动作" | pi **没有**逐技能启用/停用/安装的界面；技能靠"目录里有没有"和 frontmatter，安装靠 `pi install <source>`（`package-manager.ts`） | **列出**：`WorkspaceResources` / `ProjectResources`（技能/提示词/扩展/主题四类）✅；**看内容**：`WorkspaceViewer` + `ProjectResources` ✅；**改文件**：`PiFilesScreen` + `PiFiles`（`app/src/main/kotlin/app/pi/settings/PiFiles.kt:95-107` 含 `SKILL.md` 所在目录）✅；**安装**：`PiPackagesScreen`（`pi install`，设置里的 `packages` 行只读并跳转过去）✅；**逐技能启停**：pi 没有对应物 → 不做 | ✅，且没有虚构出 pi 没有的开关 |

> 结论：**技能面没有欠账**。App 侧多出来的只有"逐技能启停"，而它**不存在**于 pi，按规矩不做是对的。

---

## 5. 上下文与压缩

### 5.1 系统提示词组装 `{读}`

`core/system-prompt.ts:28-168`：

1. `customPrompt`（= `--system-prompt` 或 `<agentDir>/SYSTEM.md` 或 `<cwd>/.pi/SYSTEM.md`）**替换**整份提示（`:48-73`）；
2. `appendSystemPrompt`（= `--append-system-prompt` 或 `APPEND_SYSTEM.md`）追加（`:41`、`:146-148`）；
3. `contextFiles`（`AGENTS.override.md` → `AGENTS.md` → `AGENTS.MD` → `CLAUDE.md` → `CLAUDE.MD`，从 `agentDir` 到 cwd 逐级向上，`core/resource-loader.ts:71-73`、`:119-156`）包进 `<project_context>`（`system-prompt.ts:150-158`）；
4. 技能块（`formatSkillsForPrompt`）；
5. `Current working directory: …`。

来源文件优先级：`SYSTEM.md` 项目优先（**需项目已信任**）否则全局（`resource-loader.ts:1022-1034`），`APPEND_SYSTEM.md` 同构（`:1038-1049`）。`--no-context-files` 关掉第 3 步（`cli/args.ts:194`，`resource-loader.ts:516`）。

### 5.2 逐条对 App

| pi 输入 | App 符号 | 状态 |
|---|---|---|
| `--system-prompt` | `PiLaunchOptions.systemPrompt`（`rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:38-39`、`:67-70`）+ 设置行 `app.runtime.systemPrompt`（`PiSettingsRegistry.kt:452`） | ✅ 且引号转义有测试（`PiPreSpawnCheck`） |
| `--append-system-prompt` | `PiLaunchOptions.appendSystemPrompt`（`:41`、`:71-73`）+ 设置行（`:469`） | ✅ |
| `--no-context-files` | `PiLaunchOptions.noContextFiles`（`:43`、`:74`）+ 设置行（`:1266`） | ✅ |
| `AGENTS.md` / `CLAUDE.md` | `PiFiles.kt:102-105` 的候选清单（顺序与 pi 一致）+ `PiFilesScreen`（`:92-98` 说明这就是这些文件的编辑入口） | ✅ |
| `SYSTEM.md` / `APPEND_SYSTEM.md` | `PiFiles.kt:106-107` + `PiFilesScreen` | ✅ |
| `current working directory` | 引擎 cwd 由 `GuestWorkspacePath` 固定 | ✅ |

### 5.3 压缩：触发条件、参数、等价物 `{读}`

| 项 | pi 证据 | App 符号 | 状态 |
|---|---|---|---|
| 保留量参数 | `CompactionSettings.reserveTokens` 默认 **16384**、`keepRecentTokens` 默认 **20000**（`core/compaction/compaction.ts:126-135`） | 设置行 `compaction.reserveTokens` / `compaction.keepRecentTokens`（`PiSettingsRegistry.kt:577` / `:592`） | ✅ |
| 逐模型覆盖 | `compaction.modelOverrides`（`settings-manager.ts:867-877`） | 设置行 `compaction.modelOverrides`（`:607`） | ✅ |
| 阈值判定 | `shouldCompact(tokens, window, s) = tokens > window - reserveTokens`（`compaction.ts:235-238`） | 由 pi 执行；App 显示 `compaction_start/end`（`CompactionBlock`） | ✅ |
| 触发时机 | **每次 assistant 回复前**：`_compactBeforeNextAssistantResponse`（`core/agent-session.ts:538-555`），挂在 `prepareNextTurnWithContext`（`:563-578`）；另有 `"overflow"`（上下文溢出后重试）路径 `:2138-2223` | 无客户端逻辑 | ✅ |
| 事件 reason | `"manual" \| "threshold" \| "overflow"`（`agent-session.ts:157`、`:163`、`:182`） | `CompactionBlock.kt:60-62` 直接把 reason 打进文案 | ✅ |
| 分支摘要参数 | `branchSummary.reserveTokens` 默认 16384（`settings-manager.ts:903-907`）；`skipPrompt` **只被 TUI 读**（`interactive-mode.ts:5236`） | `branchSummary.reserveTokens` 有设置行（`:619`）；`skipPrompt` 不列——`settings-review.md:158` 与 `settings-audit-pi-gap.md:385` 已按"TUI 专属"裁决 | ✅（skipPrompt → 附录 A） |
| 手动压缩 | RPC `compact`，可带 `customInstructions`（`rpc-types.ts:47`、`rpc-mode.ts:535-539`；`agent-session.ts:1967`） | `PiSessionViewModel.compact(customInstructions)`（`:3714`）、设置行 `app.compaction.runNow`、斜杠 `/compact <指令>`（`ChatScreen.kt:1398`）、`PiRoot.kt:743` | ✅ |
| 自动压缩开关 | RPC `set_auto_compaction`（`rpc-types.ts:48`） | `Commands.setAutoCompaction` + 设置行 `compaction.enabled`（`:567`） | ✅ |
| 压缩期扩展钩子 | `session_before_compact` / `session_compact` / `session_compact_failed`（`types.ts:1270-1272`） | 扩展在 pi 进程内；我们收 `compaction_*` 事件 | ✅ |

> 结论：**上下文/压缩面没有欠账**。

---

## 6. 附件与图片

### 6.1 pi 的语义 `{读}`

| 项 | 证据 | 语义 |
|---|---|---|
| 支持的内联格式 | `utils/mime.ts:6-20`：jpeg（排除 `0xF7` 前缀的 JPEG2000）/ png（**非动画** PNG）/ gif / webp / **bmp** | 检测用的清单 |
| 归一化后的传输格式 | `utils/image-process.ts:33-47`：png / jpeg / gif / webp **原样**；其余（含 bmp、heic、tiff）→ 转 PNG（`:49-63`），并回一句 `[Image converted from X to Y.]` 提示（`:65-71`） | 只有 4 种真正上线 |
| 缩放上限 | `utils/image-resize-core.ts:22-27`：maxWidth/maxHeight **2000**、maxBytes **4.5 MiB 的 base64 长度**（`4.5*1024*1024`） | 已在限内且 base64 未超 → **原字节直发，不再编码**（`:82-93`）；否则长边夹到 2000 → 候选序 PNG→JPEG 各质量 → 每次缩到 3/4（`:129-150`） |
| `images.autoResize` | `settings-manager.ts:1289-1299`，读到 `agent-session.ts:522`（工具结果归一化）与 `:2792`（`read` 工具建实例） | 关掉即不缩放 |
| `images.blockImages` | `settings-manager.ts:1302-1311`；在 `core/sdk.ts:267-300` 的 `convertToLlm` 包装里**把图片换成文本** `"Image reading is disabled."`（连续同类去重） | **发送侧闸门**：图片仍留在历史里，只是不发 |
| 附件怎么进上下文 | RPC `prompt/steer/follow_up` 的 `images?: ImageContent[]`（`rpc-types.ts:22-24`），元素形状 `{type:"image", data:<base64>, mimeType}`（`packages/ai/src/types.ts:367-371`）；`@file` 是 **CLI 专属**（`cli/file-processor.ts:24-66`），RPC 无此命令 | |
| 终端渲染开关 | `terminal.showImages`（`settings-manager.ts:1189-1197`），只被 `interactive-mode.ts:3257` 读 | TUI 专属 |

### 6.2 对照与**语义差异**

| 项 | App 符号 | 状态 |
|---|---|---|
| 每图上限与候选编码序 | `AttachmentBudget`（`ui/screens/AttachmentBudget.kt:16-70`）逐条复刻 pi 的 2000/4.5MiB/base64 字符数口径 | ✅ |
| **差异 1（有意的）** | `AttachmentBudget.kt:30-36`、`:211-212`：pi 在候选序里**总是先试 PNG**，App 只在源图**能带 alpha** 时才试 PNG，否则直接 JPEG | 语义差异：一张不透明、超出尺寸的 PNG，pi 可能仍以 PNG 发送，App 发 JPEG。**快速路径 1（已在限内）仍然字节不变**。理由是有意的（Android 上 2000×2000 PNG 编码慢且更大）。`{读}` |
| **差异 2（App 侧新增）** | 整条消息的 base64 预算 `MESSAGE_BASE64_CHARS`（`AttachmentBudget.kt:47-70`），由 `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` 反推，上限 **7 张 pi 满额图**；pi 只有"每图 4.5MiB"，没有整条消息预算 | 这是**因为传输/回读是 App 自己的约束**（`get_entries` 记录与 `message_start/end` 回显）而加的下限，不是 pi 语义的替代品。属于"更严"，不会产生与 pi 不同的模型可见结果，除非一张合法消息超 32MiB 记录上限——那时 App 会拒绝，pi 会发。`{读} + {推}` |
| `images.autoResize` | 设置行（`PiSettingsRegistry.kt:1064`） | ✅ |
| `images.blockImages` | 设置行（`:1075`），描述明确写"给模型设的闸门：图片仍可附加，只是不会随请求发出去" | ✅ 语义与 pi 一致 |
| `terminal.showImages` | 不列 | 附录 A（TUI 终端能力） |
| 缩略图 / 查看器 | `ImageGridBlock.kt` + `PiImageViewer.kt` + `ImageSize.kt` | ✅ pi 的 TUI 侧对应物是 `showImages` + 图片解码；我们的呈现更完整 |
| 从设备/共享存储读图 | `bridge/GuestImageBytes.kt`（8MB 显示上限；**明确不读 `blockImages`**，因为那是发送侧闸门，`:126-130`） | ✅ 判断正确 |

> 结论：**附件面没有功能欠账**；有 2 处语义差异，都是**有意且写在代码里**的，其中差异 1 会改变"不透明超尺寸 PNG 的传输编码"，差异 2 是更严的客户端预算。

---

## 7. agent 内核行为面

### 7.1 pi 的通道 `{读}`

| 行为 | 证据 | 语义 |
|---|---|---|
| steering | `Agent.steer()`（`packages/agent/src/agent.ts:283-286`）、`steeringMode`（`:264-272`）、入参默认 `"one-at-a-time"`（`:231`） | 队列在**每轮工具结果之后**取（`agent-loop.ts:167`、`:251-255`），在下一轮 assistant 之前注入为 message（`:200-210`） |
| follow-up | `Agent.followUp()`（`agent.ts:288-290`）、`followUpMode`（`:274-282`）、默认 `"one-at-a-time"`（`:232`） | 只在 agent 本要停下时被取出，然后回到内层循环（`agent-loop.ts:259-266`） |
| `all` vs `one-at-a-time` | `PendingMessageQueue.drain()`（`agent.ts:141-154`）：`all` 一次全取，否则只取队首 | 两种模式的**唯一**差别 |
| 清队列 | `clearSteeringQueue`/`clearFollowUpQueue`/`clearAllQueues`（`agent.ts:292-303`） | RPC 只有 all-or-nothing 的 `clear_queue`（`rpc-types.ts:26`） |
| 并行工具 | `agent-loop.ts:409-421`（见 §1.1） | 同批工具并行，结果按**原顺序**归位（`:559` 的 `orderedFinalizedCalls`） |
| abort | `Agent.abort()`（`agent.ts:319-321`）→ `AbortController`；循环在工具准备前/执行后/串行每步都查 `signal.aborted`（`agent-loop.ts:476`、`:513-524`、`:636-642`） | 中途 abort 会把在跑的工具变成 `Operation aborted` error 结果 |
| `terminate` | `shouldTerminateToolBatch`：**只有当整批每条结果都 `terminate === true`** 才停（`agent-loop.ts:589-590`），来自扩展 `tool_call` 的 `terminate`（`types.ts:1129-1133`）或工具结果自带的 `terminate`（`agent-loop.ts:645-646`、`:750`） | 扩展机制，非用户可见 |
| 重试 | `_prepareRetry`（`core/agent-session.ts:2917-2964`）：`retry.enabled`、`retry.maxRetries` 默认 3、指数退避；事件 `auto_retry_start/end`（`:2929-2943`、`:2954-2960`）；`abortRetry()`（`:2972-2974`） | 厂商层另有 `retry.provider.*`（`settings-manager.ts:949-955` → `sdk.ts:315-371`） |
| 超时 | 厂商请求超时 `retry.provider.timeoutMs`（`sdk.ts:315`）；工具侧只有 `bash` 自带 per-call timeout | |
| 自动压缩对下一轮的影响 | `prepareNextTurnWithContext` 每轮刷新 `systemPrompt` 与 `tools`（`agent-session.ts:563-578`） | 换模型/换工具/压缩后立即生效 |
| `agent_settled` | 只有"不会再自动重试、不会再自动压缩、也没有排队续跑"时才发（`types.ts:735-741`，发点 `agent-session.ts:625-633`） | 客户端的"真的停了"信号 |

### 7.2 逐条对 App

| pi 能力 | RPC 命令/事件 | App 符号 | 状态 |
|---|---|---|---|
| steering 投递 | `steer`（`rpc-types.ts:23`）+ `prompt.streamingBehavior:"steer"` | `Commands.steer` + `PiSessionViewModel` 的聊天提交路径 | ✅ |
| follow-up 投递 | `follow_up`（`:24`）+ `streamingBehavior:"followUp"` | `Commands.followUp` | ✅ |
| 两种队列模式 | `set_steering_mode` / `set_follow_up_mode`（`:43-44`） | `Commands.setSteeringMode/setFollowUpMode` + 设置行 `steeringMode`/`followUpMode`（`PiSettingsRegistry.kt:487` / `:499`）+ 队列 sheet 的 `QueueModeRow` | ✅ 界面入口有 |
| 队列实时状态 | `queue_update` 事件（`agent-session.ts:153`、`:592-598`） | `Events.QueueUpdate` → `PiSessionViewModel.kt:2238-2241`（steering/followUp 计数） | ✅ |
| 清队列 | `clear_queue`（`:26`） | `Commands.clearQueue`，注释说明两队列一起清（`ChatScreen.kt:3022`、`PiEngineSession.kt:1136-1146`） | ✅ |
| abort | `abort`（`:25`） | `Commands.abort` + `PiRoot.kt:744` 的按钮（与 `clear_queue` 同按） | ✅ |
| 自动压缩开关 | `set_auto_compaction`（`:48`） | 设置行 + `Commands.setAutoCompaction` | ✅ |
| 自动重试开关 / 中止重试 | `set_auto_retry` / `abort_retry`（`:51-52`） | 设置行 `retry.enabled` + `Commands.abortRetry`（`PiSessionViewModel.kt:3747`） | ✅ |
| 重试事件 | `auto_retry_start/end`（`agent-session.ts:169-170`） | `Events.kt` 解析 + UI 呈现 | ✅ |
| 压缩重试事件 | `summarization_retry_scheduled/attempt_start/finished`（`agent-session.ts:2889-2905`） | `Events.kt` 三类全部解析 | ✅ |
| "真停了" | `agent_settled`（`:735-741`） | `Events.kt` 解析 | ✅ |
| 并行工具的呈现顺序 | `tool_execution_start/update/end` 按 c1..cn，结果按序归位 | `ToolRail` / `Transcript.kt` 的 `onToolUpdate` | ✅ |
| `terminate` | 无 RPC 通道 | — | 扩展机制，pi 自己也不给用户看 `{推}` |

> 结论：**agent 内核面没有欠账**。四条 RPC 推送（steering / follow-up / auto-compaction / auto-retry）我们都在用，另外还接住了 `queue_update`、`agent_settled`、`summarization_retry_*`、`tool_execution_update`（200ms 节流，见 `Transcript.kt:793-800`）。

---

## 8. 其它：MCP / subagent / memory / browser / plan mode / hooks

按用户口径，这一节的用途是判断"别人有的功能我们按规矩不做"。**全部 grep 过**：

| 候选 | grep 结果（`packages/*/src/**/*.ts`） | 判定 |
|---|---|---|
| **MCP** | **无命中**（只有 `utils/tool-result-images.ts:15` 的一句注释提到"MCP bridges"作为例子） | pi 没有 MCP。**不做** |
| **subagent** | **`subagent` 0 命中、`sub-agent` 0 命中**；只有 `examples/extensions/subagent/`（示例）与 `examples/extensions/handoff.ts` | 核心没有。**不做**（除非把示例扩展当"pi 有"，见 §8.1） |
| **memory** | 107 处命中，**全部是"内存/in-memory"**（`in-memory-storage-state.ts`、`MemorySessionRepo`、`export-html/index.ts:245`…），**没有一处是"记忆功能"** | pi 没有 memory 功能。**不做** |
| **browser** | `packages/coding-agent/src` 内只有 `utils/open-browser.ts` 的 `openBrowser`（给 `/login` 打开 URL 用，`login-dialog.ts:111`、`tui-renderer.ts:34`） | 没有浏览器工具。**不做** |
| **plan mode** | `planMode` / `plan_mode` 命中都是**别的意思**：`packages/ai/src/api/anthropic-messages.ts:93-94` 的 `EnterPlanMode`/`ExitPlanMode` 工具**名过滤表**，`qwen-token-plan` 是套餐名；`examples/extensions/plan-mode/` 是示例 | 核心没有。**不做** |
| **TODO 工具** | `todo` 命中全是 `TODO_CONTEXT`（取消上下文常量，`experimental/server.ts:11`）与 `TODO:` 注释；工具是 `examples/extensions/todo.ts` | 核心没有。**不做** |
| **`.pi/hooks` 目录** | **无加载代码**；`migrations.ts:222-231` 反而是**迁移警告**：发现 `hooks/` 就打印 `"Hooks have been renamed to extensions."` | pi **没有** hooks 目录。**不做** |
| **web search / RAG / 向量** | `web_search`/`websearch` **0 命中**；`rag` 命中是 `storage` 等子串；`vector` 命中与检索无关 | **不做** |
| **远程会话服务（CBOR server/client）** | `packages/protocol` + `packages/client` + `packages/server` 存在，但**只有** `src/cli/experimental/**` 与 `src/experimental/**` 引用它们；而 `packages/coding-agent/package.json:29-39` 的 `files` **明确排除** `dist/experimental` 与 `dist/cli/experimental` | **不在发行版里**，`pi` 这个 bin 也到不了（`package.json:9-11` → `dist/bundle/cli.js`）。**不做** |
| **唯一的内建扩展** | `src/extensions/index.ts:4`：`builtInExtensions = [{name:"llama.cpp", factory: llamaExtension, hidden:true}]` | 只有 llama.cpp 一个。它的模型加载/下载 UI 在 `extensions/llama/index.ts:186` 用 `ctx.mode !== "tui"` 自我屏蔽；RPC 可达的部分（provider 注册、`/login llama`）我们已经接住（设置行 `app.localModels.manage`，`PiSettingsRegistry.kt:297`） |

### 8.1 示例扩展：pi 有（随包发布）、但不是内建

`packages/coding-agent/package.json:29-39` 的 `files` **包含 `examples`**，所以 `permission-gate` / `subagent` / `plan-mode` / `todo` / `qna` / `question` / `ssh` / `sandbox` / `git-checkpoint` / `auto-commit-on-exit` / `interrupt-handling` 这类**79 个条目**（`examples/extensions/` 的 `ls | wc -l`，含 `README.md` 与若干子目录，纯 `.ts` 文件数略少）确实随 pi 一起分发，但**默认不加载**，只有 `--extension` 或装包才会生效。`{读}`

按「pi 没有对应物的一律不做」，它们**不是** pi 的内建能力；按「pi 有的必须 1:1」，它们又是"pi 发的文件"。本次审查的判定：**不计入欠账**，理由是 (a) 它们不是 pi 的核心行为面，(b) App 已经装了 3 个自带扩展并覆盖了其中最关键的一条（危险操作确认），(c) 用户装任何示例扩展都能直接跑（`PiPackagesScreen` + `extensions` 设置行 + `device-reload` 原地重载）。这条判定与仓库既有结论一致（`docs/capability-gap.md:228-238`、`docs/known-gaps.md:329`）。

---

## 9. 真欠账清单（按严重度）

### D1 — 会话内分支切换与分支摘要（`navigateTree`）｜严重度：高

- **pi 有**：`AgentSession.navigateTree(targetId, {summarize, customInstructions, replaceInstructions, label})`（签名 `packages/coding-agent/src/core/agent-session.ts:3136-3139`，实现到 `:3332`）；唯一的**分支摘要生产点**也在这里：`:3233` 调 `core/compaction/branch-summarization.ts:293`，`:3284` 用 `sessionManager.branchWithSummary` 写入 `BranchSummaryEntry`。它只发**扩展事件** `session_tree`（`:3315-3321`，`core/extensions/types.ts:664`），而 RPC 只转发 `AgentSessionEvent`（`modes/rpc/rpc-mode.ts:356` → `session.subscribe`），所以连「导航完成了」这条 RPC 事件都不存在。内建斜杠命令 `/tree`（`core/slash-commands.ts:22`）；扩展侧 `ctx.navigateTree`（`core/extensions/types.ts:375-379`，RPC 模式已接线：`modes/rpc/rpc-mode.ts:329-335`）。
- **手机可用**：是。它是纯会话状态操作，不需要终端。
- **我们没接**：RPC 的 33 条命令里**没有** navigate/tree-switch 类命令（`modes/rpc/rpc-types.ts:20-74` 全量核对），所以 App 只能：
  - `get_tree` 看树（`Commands.getTree`）+ `fork` 出**新会话文件**（`Commands.fork`），并且这一点是**写在代码里的自觉决定**：`app/src/main/kotlin/app/pi/ui/chat/SessionTreeScreen.kt:79-89`——"the only tree commands are `get_tree` and `fork` … 所以 App 的按钮是 fork"。
  - 呈现层**已经准备好了**：`rpc/…/Transcript.kt:156` 定义了 `BranchSummary`、`:2304` 投影它、`ui/blocks/BranchSummaryBlock.kt` 渲染它——**但这条数据永远不会有输入**（除非会话文件是原版 TUI 或某扩展产生的）。`{读}`
- **实现路径（口子确切）**：随包扩展注册一个命令，处理函数里调 `ctx.navigateTree(entryId, {summarize:true, customInstructions, label})`；App 侧用 `prompt` 文本派发（`core/agent-session.ts:1175-1190` → `_tryExecuteExtensionCommand`，`prompt` 默认 `expandPromptTemplates:true`，RPC 的 `prompt` 也正是走这条）。precedent 我们已经有一个：`pi-android-bridge/index.ts:1383-1389` 的 `device-reload` 就是同一个形状。
- **代价与风险**：`navigateTree` 在流式中会抛错（`agent-session.ts:3140-3147`），并且换 leaf 后没有任何推送（见上），只能在 `get_entries`/`get_tree` 之后整屏重取。`{推}`

### D1 已实现（applied, uncommitted）— 符号级清单

| 面 | 符号 | 说明 |
|---|---|---|
| 纯逻辑（新文件） | `app/src/main/kotlin/app/pi/ui/chat/PiTreeNavigation.kt`：`NavigateLanding`、`landingFor`、`BranchSummaryChoice`、`summaryPromptShown`、`wantsSummary`、`needsCustomInstructions`、`navigateCommandArgs`、`NavigateOutcome`、`navigateOutcome`、`branchFromRootToLeaf`、`activeBranch` | pi 的落点规则（`agent-session.ts:3265-3277`）、摘要三选一（`interactive-mode.ts:5236-5263`）、命令参数 JSON、结果判定、`getBranch` 过滤。无 Android/Compose。 |
| 扩展 | `app/src/main/assets/pi-extensions/pi-android-bridge/index.ts`：新命令 **`pi-android-navigate`** | `JSON.parse(args)` → `ctx.navigateTree(targetId, {summarize, customInstructions, replaceInstructions, label})`；`aborted`/`cancelled`/成功各发一条 `ctx.ui.notify`，成功且 `fillEditorIfEmpty` 时 `ctx.ui.setEditorText(editorResult)`；抛出的 pi 原句经 `notify(..., "error")` 回到 App。 |
| 视图模型 | `PiSessionViewModel.navigateTo(entryId, choice, customInstructions)`、`replayActiveBranch`、`navigateFailureText`、`branchSummarySkipPrompt()`、常量 `NAVIGATE_COMMAND` / `NAVIGATE_BUSY_LABEL` | 派发前用 `get_commands` 验命令存在；直接 `engine.request(PiCommands.prompt(...))` **不走** `PiEngineSession.prompt`（那条会本地回显命令文本）；`get_tree.leafId` 前后对比 → `navigateOutcome`；`Moved` 时重建分支 + `refreshTree` + `refreshStats`。 |
| 界面 | `SessionTreeScreen`：新参数 `onNavigate`、`skipSummaryPrompt`；`BranchRow` 整行可点 = 跳转；新 `NavigateSummaryDialog`（pi 的三答 + 自定义指令）；`TREE_FORK_HINT` 文案改写 | 跳转与分叉现在是两个明确不同的动作。 |
| 调用点 | `SessionsScreen` 的 `SessionTreeScreen(...)` 增加 `onNavigate` / `skipSummaryPrompt` 两个实参 | **边界外的一次必要改动**（该文件是唯一调用点，不在禁改清单里）；纯追加参数，无行为改动。 |
| 台账/注释 | `GrepBlock.kt:179-185` 的 KDoc | 删掉「context 行取 `contextOnTool`」这句（pi 无此 token，实际用 `toolDiffContext`，只有 diff 渲染器用）。只改注释。 |
| Harness（新文件） | `app/src/test/kotlin/app/pi/ui/chat/PiTreeNavigationCheck.kt` | 50 个断言，全部 PASS。**尚未注册**到 `tools/run-app-pure-checks.sh`（该文件在禁改清单里）；注册补丁见文末。 |

**重取路径（回答"用的是哪条"）**：`get_entries`（它带 pi 的**内存** `leafId`，`rpc-mode.ts:648`）→ `activeBranch(entries, leafId)` 过滤出 root→leaf → `engine.seedHistory(branch)`，并把 `HistoryCursor` 置为 `reachedStart = true`。**不是** `replayHistory`（文件尾读取）也**不是** `replayHistoryOverRpc`（整表顺序折叠）：两者都不走 `parentId`，而 `navigateTree` 之后文件里仍然留着被放弃的那条路径（`session-manager.ts:1374-1416` 从不删除 entry），所以它们会把**不在 pi 上下文里**的消息画出来 —— 这正是「不许出现半屏旧数据」要挡的那个 bug。`reachedStart = true` 是同一件事的另一半：重建从分支根开始，上面没有东西可补，若让 `expandEarlierHistory` 继续往前读，就会把被放弃的分支接到新上下文前面。

**pi 的语义 1:1 对照**（不许发明那条）：

| pi 语义 | 证据 | App 的做法 |
|---|---|---|
| 落点：user `message` / `custom_message` → `parentId`（并把原文交回编辑器）；其它 → 自身 | `agent-session.ts:3265-3277` | `landingFor`；「跳转」在**每一行**都提供（pi 接受任何 entry id），落点由 pi 决定，客户端只选择目标 |
| 流式中 / 压缩中拒做 | `:3140-3147` | **不做本地 guard**：照发，pi 抛出的原句经扩展的 `notify(..., "error")` 显示。pi 的 TUI 会先 `abort()`（`interactive-mode.ts:5266-5269`），那是 UI 便利层，App 不隐式中断用户的进行中回合 |
| 摘要问题三答 + `branchSummary.skipPrompt` 跳过 | `interactive-mode.ts:5236-5263`、`settings-manager.ts:910` | `BranchSummaryChoice` + `summaryPromptShown`/`wantsSummary`；`skipPrompt` 从设置文档**读取**（不加设置行，它在 `ui/settings/**` 禁改区） |
| 摘要模型/预算 | 用**当前会话模型** + `branchSummary.reserveTokens`（默认 16384） | 全部由 pi 决定；App 只发 `summarize` 与 `customInstructions` |
| `cancelled` / `aborted` / 已在原位 | `:3204-3206`、`:3247-3249`、`:3151-3154` | 扩展逐条 `notify`；App 用 `navigateOutcome` 判定 `NoMove` → **不重建**（否则是"重置了但没东西可显示"的反向 bug） |
| 摘要写入位置 | `branchWithSummary` 把它放在导航目标处并成为活动 leaf（`session-manager.ts:1395-1416` → `:1061`） | 过滤器把它当普通 entry 收进分支 → `BranchSummaryBlock` 终于有输入了 |
| 导航后**没有** RPC 事件 | `session_tree` 只发扩展事件（`:3315-3321`），RPC 只转发 `AgentSessionEvent`（`rpc-mode.ts:356`） | 用 **prompt 响应**当作完成信号：`_tryExecuteExtensionCommand` 被 await 之后才 `preflightResult(true)`（`:1184-1188` → `rpc-mode.ts:401-406`），再用 `get_tree` 前后比对 leafId |
| 未注册命令会**变成给模型的消息** | `:1338` 返回 false → `:1198-1218` 当普通用户回合发出 | 派发前查 `get_commands`（`rpc-mode.ts:685-690`），缺命令则只提示、**不派发** |

**一处 pi 在 RPC 模式下的缺口（不是我们的欠账，但必须写清）**：`editorText` 与 `aborted` **在 RPC 模式里根本读不到**。`AgentSession.navigateTree` 返回 `{ editorText?, cancelled, aborted?, summaryEntry? }`（`agent-session.ts:3139`），pi 的 TUI 全都用（`interactive-mode.ts:5299-5315`，含 `:5313` 的「只在编辑器为空时回填」）；但扩展在 RPC 模式拿到的接线是 `return { cancelled: result.cancelled }`（`rpc-mode.ts:329-335`），声明类型也只有 `{ cancelled: boolean }`（`extensions/types.ts:375-379`）。

后果有两条，都写在扩展的注释里：(1) **被中止的摘要无法与成功区分**；(2) **原文回不到输入框**，所以做不到 pi `/tree` 那句「改完再发」。这是 pi 自己的 RPC 缺口 —— 扩展的 `tsc --noEmit` 会直接把这它当成类型错误（`Property 'aborted' does not exist on type '{ cancelled: boolean; }'`），这正是本轮实测发现的。跳转本身（换 leaf + 分支摘要）不受影响；若将来要那条回填，正确做法是 App 从它已经拿到的树节点自己取该条目的正文（等于在客户端重做 `contentText(entry.message.content)`），而不是假装这个字段存在。

**零副作用（调用点普查）**：`SessionTreeScreen` 的唯一调用点是 `SessionsScreen.kt`；两个新参数都有默认值，所以其它位置（无）与预览/测试不受影响。旧的「只有 fork」行为**没有**被改掉：`onFork`、`canFork` 条件（user message only）、分叉按钮与其文案全部保留；新增的是**整行可点 = 跳转**，以及原来那句「只能新建分支」的说明改为两个动作的分工。`navigateTo` 是新增函数，无既有调用者。`activeBranch` 是新函数，没有替换 ViewModel 里既有的私有 `branchPath`（导出 JSONL 仍用它），所以导出路径一行未动。`GrepBlock` 只动 KDoc。

**D2 状态：整条未动（零半成品）**。`PiLaunchOptions.kt`、`PiPreSpawnConfig.kt`、`PiEngineApi.kt`、argv 组装链**一行未改**（`git diff --stat` 对这些路径为空；`engine/**` 的临时改动已 `git checkout --` 还原）。按用户决定：要么为它加设置行再实现，要么整条不做（台账记「有意不做 + 终端页可达」）。

**验证（本轮实跑）**：

| 检查 | 命令 | 结果 |
|---|---|---|
| 纯逻辑 harness | 见下方命令行 | **50 PASS / 0 FAIL / `harness: OK (all checks passed)` / exit 0** |
| pi 扩展类型 + 运行时 | `bash build/extension-check/run.sh`（`tsc --noEmit` 对 pi 0.85.1 的真实类型 + jiti 运行时 harness） | **`tsc: OK`**（我新增的命令零类型错误）。运行时 harness 报 18 个 FAIL，全部是**既有**失败：它们落在工具表、环境说明与 permission gate 上，而本轮 diff 对 `index.ts` 是 **147 行纯新增、0 行删除**（`git diff --stat`），所以与本次改动无因果关系 |
| Kotlin 全量类型检查 | `./tools/typecheck.sh` | **我引入的错误 0 个**。首轮报出我引入的 2 个可见性错误（`'public' function exposes its 'internal' parameter type 'BranchSummaryChoice'`，已把该 enum 改为 public）；改后重跑只剩 **3 个既有错误**，全在 `ui/settings/DiagnosticsReport.kt` 的 `unresolved reference 'BuildConfig'` —— 该文件本轮未改，且 `typecheck.sh` 从不生成 `BuildConfig`（它只造 `R` 的桩），是脚本本身的已知局限 |

**Harness 命令行 / 期望输出 / PASS 数**：

```
# 与 tools/run-app-pure-checks.sh 里 run_harness 相同的 recipe
KOTLINC_CP="$(find -L build/typecheck/kotlinc -name '*.jar' | tr '
' ':')"
LIB_CP="$(find -L build/pure-checks/lib -name '*.jar' | tr '
' ':')"
java -Xmx1100m -cp "${KOTLINC_CP%:}:${LIB_CP%:}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler   -no-stdlib -jvm-target 17 -classpath "${LIB_CP%:}" -d /tmp/tree-nav-out   app/src/test/kotlin/app/pi/ui/chat/PiTreeNavigationCheck.kt   app/src/main/kotlin/app/pi/ui/chat/PiTreeNavigation.kt
java -cp "/tmp/tree-nav-out:${LIB_CP%:}" app.pi.ui.chat.PiTreeNavigationCheckKt
```

期望输出：50 行 `PASS`，末行 `harness: OK (all checks passed)`，退出码 0。（本次实跑结果：**50 PASS / 0 FAIL / exit 0**。）

**要成为 CI 门禁**，需要在 `tools/run-app-pure-checks.sh` 追加下面这 6 行（该文件在禁改清单里，**未改**）：

```bash
# app.pi.ui.chat: pi's in-session tree navigation — the leaf rule, the summary
# question and `branchSummary.skipPrompt`, the JSON argument text, the branch filter
# that keeps an abandoned path out of the rebuilt conversation.
run_harness tree-navigation \
  app.pi.ui.chat.PiTreeNavigationCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/PiTreeNavigationCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/PiTreeNavigation.kt"
```

### D2 — 扩展注册的 CLI flag 透传（`registerFlag` / `getFlag`）｜严重度：低｜**未做**

- **pi 有**：`pi.registerFlag(name, {type:"boolean"|"string", default})` / `getFlag(name)`（`core/extensions/types.ts:1325-1345`）；argv 值进入 `flagValues` 并传给运行时（`core/agent-session.ts:2790`、`:2812`）。
- **手机可用**：边缘。它让"依赖 flag 的扩展"生效。
- **我们没接**：`PiLaunchOptions`（`rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:35-75`）只带 `--system-prompt` / `--append-system-prompt` / `--no-context-files` 与两个环境变量；`PiPreSpawnConfig` 的"不暴露"清单（`rpc/…/PiPreSpawnConfig.kt:246-390`）里也**没有**泛化的 `--<ext-flag>` 透传项，所以扩展 flag 在 App 会话里永远是默认值（`agent-session.ts:2790` 的 `flagValues` 为空）。`{读}`
- **缓解**：工作区终端页跑的是**未修改的原版 pi**（`ui/screens/TerminalScreen.kt:72` 明说"订阅登录、会话导入、以及需要终端的扩展都在那边"），用户可以在那里带 flag 启动。所以这不是能力丧失，只是 App 会话里够不着。
- **实现路径**：给 `PiLaunchOptions` 加一个"额外 argv"字段（连同 `PiPreSpawnCheck` 的 `containsFlag` 纯检查一起扩）。`{推}`
- **未做的原因（边界）**：这条改动本身很小（`PiLaunchOptions` + `commandLineSuffix` + `PiPreSpawnConfig` 三张表里的一条），但它**没有可设置的值**：能写这个值的唯一入口是设置页的一行，而 `ui/settings/**` 在本轮禁改清单里。只加传输层会得到一段**死代码**（没有任何调用者能填它），比不做更糟。需要改的符号（交给有设置页写入权的一方）：
  1. `PiLaunchOptions`（`rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt`）：加 `extraFlags: List<String> = emptyList()`，在 `commandLineSuffix()` 里逐项 `append(" --")…`（值需 `quote()`），并在 `fromSettingValues(...)` 增加一个入参；
  2. `PiPreSpawnConfig`（`rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt`）：在 `COVERED_BY_PI_SETTING` 或新增一段"扩展 flag"说明——它不属于 pi 的任何设置键，`flagValues` 由 argv 直接解析（`core/agent-session.ts:2790`、`:2812`），所以要**新增**一张表而不是塞进现有三张；
  3. `PiSessionViewModel.launchOptions()`（`:1650` 附近）：读一个新设置键（例如 `app.runtime.extensionFlags`）并传下去；
  4. `PiSettingsCatalog`（`ui/settings/PiSettingsRegistry.kt`）：加一行 `PiRowKind.List`，`effective = RestartEngine`；
  5. `PiPreSpawnCheck`（`app/src/test/kotlin/app/pi/rpc/PiPreSpawnCheck.kt`）：`containsFlag` 断言覆盖新 flag。

### 未计入欠账但值得记的一条

**OAuth 登录（`/login`）没有原生界面**：pi 的 8 个 provider 有 OAuth（`packages/ai/src/providers/anthropic.ts:50`、`github-copilot.ts:16`、`openai-codex.ts:13`…），但登录流程实现在 TUI（`modes/interactive/components/login-dialog.ts:111` 调 `openBrowser`），**RPC 无任何登录命令**。App 明确把这件事划给终端（`ui/settings/PiCredentialScreen.kt:55`、`packages/PiConfigFiles.kt:249`），且**能正确地把 `oauth` 条目当作"已配置"而不去覆盖它**（`PiConfigFiles.kt:263-280`、`PiCredentialService.kt:178`）。判定：**pi 侧无 RPC 通道 → 不是我们的欠账**；用户可用终端页完成。

---

## 10. 呈现层欠账（功能在、界面没呈现）

| 项 | 功能是否在 | 现状 | 判定 |
|---|---|---|---|
| `setStatus` 的**专用页脚行** | 在（`PiSessionViewModel.setExtensionStatus` 收集） | 页脚行被 **D-3 裁决删除**（`ChatScreen.kt:1771` 注释），改在「会话与队列」sheet 的「扩展状态」段呈现（`ChatSheets.kt:463-478`） | **不算欠账**：数据没丢，只是换了落点，且是用户裁决 |
| 扩展 `custom` 消息 / `custom` entry | 在 | `HookMessageBlock.kt`（`customMessageBg` + `customType` 标签）；`Transcript.onCustomEntry`（`Transcript.kt:2322-2336`） | 无欠账 |
| `model_change` 行 | 在（session entry 存在，`core/session-manager.ts:64`） | **故意不画**（`BlockRenderer.kt:143-160` 的注释），改由 AppBar 的模型 chip 承担 | 无欠账（pi 自己也不画成 transcript 行） |
| `label` / bookmark | 在（`SessionEntries.kt:115-121`、`:149-150` 解析，含"label 被清除" 的语义） | **能显示，不能设置**；pi 侧也只有扩展 API（`agent-session.ts:2629` 的 `setLabel`）与 TUI 的 `labeled-only` 过滤（`tree-selector.ts:376`），没有核心 UI | 无欠账 |
| `grep` 的 context 行配色 **KDoc** | 在 | 代码正确（`toolOutput`，与 pi `renderers/grep.ts:52` 一致；pi 无 `contextOnTool` token），但 `GrepBlock.kt:181-183` 的 KDoc 说它取 `contextOnTool` | **文档瑕疵**，建议删那两行注释 |

---

## 11. pi 没有对应物的清单（附 grep 证据）

| 项 | 证据 | 结论 |
|---|---|---|
| MCP | `grep -rniE "\bmcp\b" --include=*.ts packages/*/src` → **恰好 1 命中**：`packages/coding-agent/src/utils/tool-result-images.ts:15` 的注释（"extensions, MCP bridges, screenshot tools"） | 无 |
| subagent / 子代理 | `grep -rni "subagent\|sub-agent" packages/*/src` → **0** | 无（仅 `examples/extensions/subagent/`） |
| memory 功能 | `grep -rni "\bmemory\b" packages/coding-agent/src/core packages/agent/src` → 107 命中，逐条看均为 in-memory 存储 | 无 |
| browser 工具 | `grep -rni "browser" packages/coding-agent/src/core packages/agent/src` → `utils/open-browser.ts`（登录开链接） | 无 |
| plan mode | `grep -rni "planMode\|plan_mode" packages/*/src` → `packages/ai/src/api/anthropic-messages.ts:93-94` 的工具名过滤、`qwen-token-plan` 套餐名 | 无 |
| todo 工具 | `grep -rniE "todo" packages/coding-agent/src` → 全是 `TODO_CONTEXT` 常量与 `TODO:` 注释 | 无 |
| `.pi/hooks` 目录 | `grep -rniE "\.pi/hooks\|hooksDir\|loadHooks" packages/*/src` → 只有 `packages/coding-agent/src/migrations.ts:223-231`，且是"hooks 已改名为 extensions"的**弃用警告** | 无（且已废弃） |
| web search / RAG | `grep -rniE "web_search\|websearch" packages/*/src` → **0** | 无 |
| 远程会话 server/client | 只有 `src/cli/experimental/**`、`src/experimental/**` 引用；`packages/coding-agent/package.json:29-39` 的 `files` 排除 `dist/experimental`、`dist/cli/experimental`；bin 指向 `dist/bundle/cli.js`（`:9-11`） | **不在发行版**，不做 |

---

## 附录 A — TUI-only 清单（按用户口径：不算欠账）

| 项 | pi 证据 | 为什么是 TUI-only |
|---|---|---|
| `ctx.ui.custom()` | `modes/rpc/rpc-mode.ts:228-231` 返回 `undefined` | 需要真终端组件 |
| `setFooter` / `setHeader` | `:210-216` | 需要 TUI 组件工厂 |
| `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` | `:179-189` | 需要 TUI loader |
| `setHiddenThinkingLabel` | `:191-193` | 需要 TUI 消息渲染 |
| `getToolsExpanded` / `setToolsExpanded` | `:303-310` | 无 TUI |
| `setTheme` / `getTheme` / `getAllThemes` | `:290-301` | RPC 明确返回失败 |
| `onTerminalInput` | `:163-166` | 无原始键盘流 |
| `getEditorText` | `:248-252` | 永远返回 `""`（同步方法等不了 RPC） |
| `addAutocompleteProvider` / `setEditorComponent` / `getEditorComponent` | `:273-284` | 需要 TUI 编辑器 |
| `registerShortcut` | `core/extensions/runner.ts:557`（只喂 TUI 键位） | 键盘 |
| `registerMessageRenderer` / `registerEntryRenderer` | 返回 `Component`（`types.ts:1213-1223`） | 需要 TUI 组件 |
| `registerMarkdownTransformer` | 只被 `modes/interactive/components/assistant-message.ts:32-40` 消费 | TUI 渲染管线 |
| 各内置斜杠命令的 TUI 实现：`/share`（`slash-commands.ts:27`）、`/changelog`（`:31`）、`/hotkeys`（`:32`）、`/quit`、`/tree` 的交互、`/settings` 菜单 | `core/slash-commands.ts:19-43` | App 各自有等价物或已在 `ui/chat/PiSlashCommands.kt:135` 的注释里逐条说明为何排除 |
| `terminal.*` 设置族 | `showImages`（`settings-manager.ts:1189-1197` → `interactive-mode.ts:3257`）、`outputPad`、`editorPaddingX`、`autocompleteMaxVisible`、`showHardwareCursor`、`markdown.*`、`quietStartup`、`doubleEscapeAction`、`treeFilterMode`、`tuiMode`、`fullscreen*`、`clearOnShrink`、`showTerminalProgress`、`trueColor` | `design/ui-refactor/07-construction-decisions.md:1090` 已按"TUI 的旋钮"裁决 |
| `branchSummary.skipPrompt` | 只被 `interactive-mode.ts:5236` 读 | 同上（`settings-review.md:158`） |
| `--tui-mode` / `--verbose` | `cli/args.ts`；`PiPreSpawnConfig.kt:305-310`、`:299-303` | 只影响 TUI 启动 |
| llama.cpp 的模型加载/下载 UI | `extensions/llama/index.ts:186` 的 `if (ctx.mode !== "tui")` 早退 | 自带扩展自我屏蔽 |

---

## 附录 B — 未确认项

1. **`addedToolNames` / provider 侧延迟工具加载**：`packages/ai/src/types.ts:453-465` 说工具结果可以"加载"新工具；RPC 线上没有任何工具清单接口，我**没有确认** App 是否需要感知它。判定倾向：不需要（工具集变化由 pi 内部处理）。`{推}`
2. **`--tools` / `--exclude-tools` 与扩展工具的交互**：`core/sdk.ts:256-262` 的过滤发生在建会话时；扩展工具是否被 `--exclude-tools` 影响，我只读到 `excludedToolNameSet` 只作用于 `initialActiveToolNames`，**没有追到扩展工具注册路径**。`{未确认}`
3. ~~**`fork` 的 `position:"before"|"at"` 未确认**~~ → **已确认，做 D1 时读清**：默认是 `"before"`（`core/agent-session-runtime.ts:266`：`options?.position ?? "before"`），而 `"before"` 要求目标必须是 **user `message`**（`:282-284`，否则抛 "Invalid entry ID for forking"）；`"at"` 接受任何 entry（`clone` 用的就是它，`rpc-types.ts` 的 `clone` → `:621`）。所以 RPC 的 `fork` 与 pi 的 `/fork` 语义一致，没有第二处差异。
4. **`ctx.ui.select` 的 3 选项语义在 pi 侧是否有承载**：我们的 `permission-gate` 用 `select` 提供「仅一次 / 记住本会话 / 拒绝」。pi 侧只有 `select` 原语，没有"记住"概念，记忆完全在扩展里。**这是 App 自己新增的语义**（不是 pi 的），我已把它当作"扩展实现细节"而非欠账；若用户认为扩展也算 pi 的一部分，这一条需要重新裁决。`{推}`
5. **`ExtensionContext.getSystemPromptOptions()` / `getContextUsage()` 等扩展只读视图**：只在 pi 进程内被扩展使用，App 不需要通道；我**没有**逐条确认它们都被正确实现（不属于本次范围）。`{未确认}`
6. **`packages/agent/src/harness/**`**：pi-agent 里还有一整套 harness/drive 实现（`runtime/drive/*.ts`）。`coding-agent` 走的是 `agent.ts`/`agent-loop.ts` 这条（`core/sdk.ts:306` 直接 `new Agent`），所以我把 harness 当作**未被 CLI 使用**的平行实现。**没有逐文件确认它是否被某个模式引用**。`{未确认}`
