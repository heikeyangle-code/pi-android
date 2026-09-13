# 反向覆盖审查：pi / RPC 做得到、而本应用界面上没有的

这份文件回答的问题是**反向**的：

> 「我这个协议，它有什么功能我没有？**这个协议能支持的功能，我 APP 没有的**。」

不是「pi 发的每个事件 App 有没有解析」——那一遍已经做完了，结论在 `docs/rpc-coverage.md`
（33 命令 / 9 个扩展 UI 方法 / 26 个 stdout 记录类型 / 12 个 delta 全部有解析）。
这份文件问的是**另一件事：协议与 pi 本身能表达的能力，用户在这个 App 的界面上够不够得着。**

| | |
|---|---|
| pi（权威） | `/root/pi-src` @ `bbb61e34`，`packages/coding-agent` 0.85.1 |
| App | `/root/pi-android` 本文件写作时的工作树（含其他作者未提交的改动） |
| 方法 | 每个符号都用 `grep` 在树上核对；不引用别的账本的结论 |
| 判定口径 | `docs/pi-sourced-lists.md`（问 pi / 看 pi 写的地方 / `pi 无对应物`） |

## 先给答案（一屏）

**真正「pi 能做、App 界面上没有」的只有六件事**，其余都是「pi 没有，所以我们不做」或
「pi 有但协议/运行时没有通道，够不着」：

1. **订阅式（OAuth）登录 / 登出** —— pi 里有（`interactive-mode.ts:5485`），RPC 没有命令，
   App 只有 API Key 表单。终端不可用之后，这条路彻底不通。
2. **把排队消息逐条收回输入框、而不打断当前回合** —— pi 有（`interactive-mode.ts:4157-4164`），
   App 只有 Stop（一次清空队列 + abort）。**可做，约 30 行，本轮给了方案没动代码。**
3. **`/import`：从 JSONL 恢复会话** —— pi 有（`interactive-mode.ts:6107-6119`），
   App 没有；`switch_session` 收的就是文件路径，**可做**。
4. **导出物能被用户拿出来**（打开 / 存 Download / 分享）—— 导出本身两边都有，
   但 pi 写进用户自己的 cwd，App 写进私有目录只报文件名。**可做，且 App 已有同类通道。**
5. **工具卡按工具分派渲染**（`read` 的行号与高亮、`grep`/`find`/`ls` 的列表）—— pi 有
   (`core/tools/renderers/*`)，App 是通用卡（`edit`/`write` 的 diff 除外）。**可做，收益中等。**
6. **`/share`（Radius / 私密 gist）** —— pi 有（`session-share.ts:43-53`），
   RPC 无通道、终端不可用。**够不着**，真做等于重写一条对外上传链。

以及**三件 pi 根本没有、界面却承诺过的**（本轮已删）：
工作区的「文件树」、「Git 变更/diff/检查点」、「后台任务列表」。
pi 没有 cron、没有调度器、没有后台任务、bash 工具也没有后台参数 —— 这一点我是独立复核的（§3.5）。

## 0. 四态与一条新的前提

| 标注 | 含义 |
|---|---|
| `pi 有且 App 有` | 两边都有，且给了两处 `file:line` |
| `pi 有但 App 没有（可做）` | pi 有、App 没有，**且 App 侧有可达的实现路径**；写明做什么 |
| `pi 有但我们够不着（…）` | pi 有，但协议或运行时没有通道；括号里写卡在哪 |
| `pi 无对应物（App 的决定）` | pi 没有这个概念，是 App 自己造的 |

**本轮的新前提：终端不再算落点。** 「工作区 → 终端」里跑的是原版 pi TUI，它是本应用历史上
承接「pi 有、RPC 没有」那批能力的兜底面（`ui/terminal/**`）。用户已决定**不再管终端，它现在
完全不能用**。因此：

1. 凡「只能靠终端 / TUI 才能达到」的能力，一律标 **`pi 有但我们够不着（终端不可用）`**，
   **不再建议「去终端做」**；
2. 应用里那些**把用户指去终端的文案**，现在是死路——它们是本轮要修的头号小缺口（§4）。

需要立刻说清的一个后果：`docs/rpc-coverage.md` §4 把一批扩展能力归为「靠终端页兜底」，
那句结论在本轮之后**不再成立**——兜底没了。这些条目在下面的主表里重新给了判定。

---

## 1. 主表（按「用户能不能感觉到」排序）

优先级：**P0** = 用户今天就会撞上；**P1** = 明显缺一块；**P2** = 少数人/条件触发；
**P3** = 内部状态或无界面义务。

### P0 — 用户今天就会撞上

| # | pi 的能力 | pi `file:line` | App 现状 | App `file:line` | 四态 | 建议 |
|---|---|---|---|---|---|---|
| P0-1 | **订阅式（OAuth）登录 / 登出**：`/login`、`/logout` 走浏览器授权，能拿到 Claude Pro/Max、Codex、Copilot、OpenRouter、Kimi、xAI、Radius | `modes/interactive/interactive-mode.ts:3052-3062`（分发）、`:5485`（`handleLoginCommand`）、`:5059`（logout 选择器）；流程在 `cli/auth-command.ts` | 只有 **API Key 表单**；OAuth 只有一行「说明」行，点「执行」把用户送到终端 | `app/src/main/kotlin/app/pi/ui/settings/PiCredentialScreen.kt:52-58`（只写 API Key）；`.../PiSettingsRegistry.kt:369-382`（OAuth 行）；`.../PiRoot.kt:196-214`（通知 + `NavRequest.Workbench`） | **`pi 有但我们够不着（终端不可用）`**：`rpc-types.ts:20-74` 的 33 个命令里没有任何登录命令，pi 的 OAuth 实现整个长在 TUI 里（`BorderedLoader`/`OAuthSelector`） | **改文案**（本轮已做，§4.4）：说清「订阅登录本应用目前没有入口」，不再指终端。真做要在 App 内重写 PKCE/设备码流程 + 一个浏览器回调，属新功能，不在本轮 |
| P0-2 | **把排队消息逐条收回输入框，而不打断当前回合**（`app.message.dequeue` 绑定） | `modes/interactive/interactive-mode.ts:4157-4164`（`handleDequeue` → `restoreQueuedMessagesToEditor()`）；`:4387-4406`（`abort?: boolean` 参数）；`:2899`（键位绑定） | **只有 Stop**：`clear_queue` + `abort` 一起做，回合被打断 | `app/src/main/kotlin/app/pi/engine/PiEngineSession.kt:874-880`（`stopAndDrainQueue`）；`.../PiSessionViewModel.kt:2149-2160`（`stop`）；`.../ui/screens/ChatScreen.kt:1198-1200`（Stop 按钮）、`:1459-1480`（队列 chip，**不可点**） | **`pi 有但 App 没有（可做）`**：`clear_queue` 只清队列不打断（`core/agent-session.ts:1608-1616`），App 已经会读它的响应；缺的只是「不清了就 abort」这一条路径 | **做**（本轮只写方案，见 §4.2）：队列 chip 可点 → `clear_queue` → 文本回到输入框，`abort` 不发 |
| P0-3 | **`/reload`**：重载扩展、技能、模板、主题与上下文文件 | `modes/interactive/interactive-mode.ts:5970`（`handleReloadCommand`）；状态句在 `:6042-6048` | 命令面板把 `/reload` 标成「仅终端」；AppBar 那个刷新按钮也只说「去终端」 | `.../ui/chat/PiSlashCommands.kt:170`（`TerminalOnly`）；`.../ui/screens/ChatScreen.kt:698`（AppBar 按钮）、`:1393-1401`（`reloadCommand()`） | **`pi 有且 App 有（但没写清）`**：App 侧等价做法是**重启引擎**——它重跑 `pi --mode rpc`，pi 会重读 `settings.json` 并重扫四个资源目录（`core/agent-session-runtime.ts:226-252` → `createRuntime`） | **改文案**（本轮已做，§4.3）：`/reload` 指向「设置 → 运行时与诊断 → 进程 → 重启引擎」。语义差别（pi 在原地重载、重启会断当前回合）要写进说明 |

### P1 — 明显缺一块

| # | pi 的能力 | pi `file:line` | App 现状 | App `file:line` | 四态 | 建议 |
|---|---|---|---|---|---|---|
| P1-1 | **`/import`**：从 JSONL 文件导入并继续 | `modes/interactive/interactive-mode.ts:6107-6119`（`handleImportCommand`，含确认框） | 没有导入入口。会话页只有列表/打开/删除 | `.../ui/screens/SessionsScreen.kt`（全 389 行无导入）；`.../session/PiSessionStore.kt`（只读索引） | **`pi 有但 App 没有（可做）`**：`switch_session`（`rpc-types.ts:61`）收的就是**会话文件路径**，把用户选的文件写进 `<agentDir>/sessions/` 再 `switch_session` 即可，不需要新协议 | **做**（未做）：SAF 选文件 → 拷进会话目录 → `switch_session`。注意 pi 会校验 cwd/`version` 头（`session-manager.ts`），导入外部文件前要先按 pi 的规则核一遍 |
| P1-2 | **`/share`**：把会话传成 Radius artifact，或退化成私密 GitHub gist | `modes/interactive/interactive-mode.ts:3002-3005`；实现在 `modes/interactive/session-share.ts:43-53`（Radius → `gh` 兜底）、`:60-76`（`gh auth status`） | 没有分享会话的入口。全 App 只有**诊断报告**能走系统分享 | `.../ui/settings/DiagnosticsScreen.kt:145-149`（诊断分享）；会话侧无 | **`pi 有但我们够不着（终端不可用）`**：RPC 无 share 命令；pi 的实现依赖 TUI 的 `BorderedLoader` 与 Radius 凭证/`gh` CLI | **不做**：真做等于把 pi 的 TUI 内部实现（对外上传 + `gh` 依赖）搬进 App，并新开一条会把系统提示词与工具定义一起上传的通道（`session-share.ts:30-42`）。这是一条安全面，值得单独立项 |
| P1-3 | **导出物要能被用户拿出来**（`/export` 写完会报出落点路径） | `modes/interactive/interactive-mode.ts:6060-6075`：`Session exported to: <path>`；pi 的落点默认是 **cwd**（`core/export-html/index.ts:274-281`） | 导出写进 App 私有工作区，成功提示**只说文件名**；没有「打开 / 保存到 Download / 分享」 | `.../PiSessionViewModel.kt:2530-2552`（HTML）、`:2483-2535`（JSONL）、`:2470-2477`（只报文件名的理由） | **`pi 有且 App 有（导出本身）`**；「把文件交出去」这一半是 **`pi 无对应物（App 的决定）`**——pi 也没有打开/分享导出物的动作，但它的落点是用户自己的 cwd | **做**（小）：导出成功后给一个「分享 / 存到 Download」动作，复用 `DiagnosticsExport` 已经在用的那条通道（`.../ui/settings/DiagnosticsReport.kt:298-337`）。终端不可用之后，这是用户唯一能真正拿到 HTML 的路 |
| P1-4 | **图片全屏查看（缩放 / 保存 / 分享）** | pi 在终端里把图片画进单元格（`packages/tui/src/components/image.ts:97-104`；不支持图形时退化成 `[Image: mime WxH]`，`terminal-image.ts:683-696`），**没有查看器** | 图片网格能画（F16 已修），点击无反应 | `.../ui/blocks/ImageGridBlock.kt:36-46`（明说查看器不存在，F19 删掉了假点击目标）、`.../blocks/ToolCallBlock.kt:274-286`（工具结果图）、`.../blocks/ChatScreen.kt`（用户消息图） | **`pi 无对应物（App 的决定）`**：查看器是 `docs/pi-android-ui-spec.md` §7.4 自己许的，不是 pi 的能力 | **按用户原则：不做**（或把 spec 那句改成「只画网格」）。若做，是 App 自己的新面，不是补 pi 的缺口 |

### P2 — 条件触发 / 少数人

| # | pi 的能力 | pi `file:line` | App 现状 | App `file:line` | 四态 | 建议 |
|---|---|---|---|---|---|---|
| P2-1 | **八个内建工具各有专用渲染**：`read` 带行号 + 语法高亮、`write`/`edit` 高亮、`grep`/`find`/`ls` 的成组列表、`bash` 的实时计时 | `core/tools/renderers/index.ts:34-44`（全部渲染器）；`renderers/read.ts:127`（`highlightCode`）；`renderers/write.ts:54`；`renderers/bash.ts:140` | 一张**通用卡**：工具名 + 参数摘要 + 原始输出。只有 `edit`/`write` 的 diff 有专用渲染 | `.../ui/blocks/ToolCallBlock.kt:36-190`（通用卡，`item.toolName`/`item.argsSummary`/`MonoText`）；`.../ui/blocks/BlockRenderer.kt:85`（`ToolDiff` → `DiffBlock`）；`rpc/.../Transcript.kt:1402-1430`（diff 从 `details` 解析） | **`pi 有但 App 没有（可做）`**：`tool_execution_*` 事件带 `toolName`/`args`/`details`，App 全都有（`rpc/.../Events.kt:478-500`） | **部分做**：`grep`/`find`/`ls` 的列表化收益最大（手机上最省滚动）。高亮要评估开销（`ToolCallBlock` 每 200ms 重组一次，`renderers` 的 token 也没数据通道） |
| P2-2 | **模型这一刻到底有哪些工具**：`getAllTools()` / `getActiveTools()` / `getSystemPrompt()` | `core/extensions/types.ts:1400`、`:1403`、`:348`；绑定处 `core/agent-session.ts:2632-2634`、`:2674` | 设置里只有 `defaultTools` 这一格的**内建**清单；模型实际能用什么（含扩展注册的 30 个 `android_*`）界面上看不到 | `.../ui/settings/PiSettingsRegistry.kt:677-690`（`defaultTools` 行）；`.../assets/pi-extensions/pi-android-bridge/index.ts`（30 个工具名，App 侧无读者） | **`pi 有但我们够不着（RPC 无通道；只在扩展上下文里）`**：33 个命令没有工具枚举 | **可做但绕**：App 自己**拥有**那三个随包扩展的源码，可以扫出工具名；但用户自己装的扩展仍列不全，列一半的清单比没有更危险（`docs/pi-sourced-lists.md` 的两次学费就是这个形状）→ **建议不做** |
| P2-3 | **会话树导航**（切到任意叶子）与**给条目打标签** `navigateTree` / `setLabel` | `core/extensions/types.ts:657-664`（`session_before_tree`/`session_tree`）；`core/agent-session.ts`（`navigateTree`） | 会话树**只读**，从某条消息只能**分叉出新会话**；标签只显示 pi 已解析的 | `.../ui/chat/SessionTreeScreen.kt`（只读）；`.../ui/chat/PiSlashCommands.kt:133-139`（`/tree` 的说明已改成「浏览会话树，从某条消息分叉」） | **`pi 有但我们够不着（RPC 无命令）`**：`rpc-types.ts:20-74` 里没有 `navigate_tree`、也没有写标签的命令 | **不做**：没有通道；分叉是能给的最近似动作，且已写明 |
| P2-4 | **扩展的四件套对话框 + 五个 fire-and-forget + 自定义渲染** | `modes/rpc/rpc-types.ts:246-281`（9 个方法）；`rpc-mode.ts:163-311`（不可达项逐个 no-op） | 四件套、五件套都有界面；**自定义渲染类**（`custom()`、`setFooter`、`setHeader`、`setWorkingMessage`、`setWorkingIndicator`、`setHiddenThinkingLabel`、`setEditorComponent`、`addAutocompleteProvider`、`onTerminalInput`、`getEditorText`、主题三件套、`setToolsExpanded`）没有落点 | `.../ui/extension/ExtensionDialogs.kt:81-256`（四件套）；`.../ui/extension/ExtensionChrome.kt:47-126`（状态/部件）；`.../ui/chat/TuiOnlyScan.kt:22-33`（标记扫描） | **`pi 有但我们够不着（RPC 上下文里是 no-op）`**：`rpc-mode.ts:174`（`setWorkingMessage`）、`:189`（`setFooter`）、`:197`（`setHeader`）、`:229-231`（`custom` 直接 `undefined`）、`:255-258`（autocomplete）、`:269-276`（editor component）、`:287-296`（主题）、`:300-307`（tools expanded）。pi 不报任何信号 | **不做**：没有上线通道；`TuiOnlyScan` 的提示文案本轮改写（不再指终端，见 §4） |
| P2-5 | **`input` 钩子的 `handled` 分支**：扩展可以接住用户这句话，自己处理掉 | `core/agent-session.ts:1147-1165`（`_runInputHandlers`）；`core/extensions/types.ts:881-884`（`{action:"handled"}`） | 用户消息会本地回显，然后**什么都不发生**；`prompt` 仍回 `success:true` | `.../PiSessionViewModel.kt:2023-2055`（本地回显）、`:1948`（`prompt` 无异常即当成功） | **`pi 有但我们够不着（pi 不报信号）`**：`handled` 走的是「提前返回」，线上没有事件 | **不做**：pi 侧的行为，App 无从分辨。已在 `docs/rpc-coverage.md` §4 同一类里登记过 |
| P2-6 | **Git 分支名**在页脚常驻 | `core/footer-data-provider.ts:127`（`getGitBranch`）；消费方 `modes/interactive/components/footer.ts:117` | App 没有任何地方显示分支；工作区「Git」段是空状态 | `.../ui/screens/WorkbenchScreen.kt:105-115`（改动前的「Git」段，现已删除） | **`pi 有但我们够不着（RPC 无通道；终端不可用）`**：`getGitBranch` 只喂 TUI 页脚，`RpcCommand` 里没有任何 git 命令 | **不做**：App 自己跑 `git symbolic-ref` 能算出来（它有 guest shell），但那是 App 自造的第二份真相，且 pi 的 `packages` 功能与它无关。详见 §3.6 |

### P3 — 内部状态 / 没有界面义务（判定为「不需要做」）

| # | 协议项 | 判定 | 依据 |
|---|---|---|---|
| P3-1 | `get_messages` | `pi 有且 App 有意不做` | 返回进程内 `session.messages`，压缩后与记录不同，用它替换会丢历史；App 以 `get_entries` 为准（`docs/rpc-coverage.md` §1） |
| P3-2 | `get_state.pendingMessageCount` | `pi 有且 App 有（等价信息）` | `core/agent-session.ts:1618-1621` 定义它 = steering + followUp 条数；App 从 `queue_update` 拿到的就是同一对数组（`core/agent-session.ts:150-154`、`:593-596` → `.../PiSessionViewModel.kt:1349-1352` → `ChatScreen.kt:1022-1023`）。字段本身解析了但没人读（`rpc/.../Responses.kt:99,284`），这是**冗余**而不是缺口 |
| P3-3 | `cycle_model.isScoped` | `pi 有且 App 不需要` | 内部状态，用户没有可看/可点的东西 |
| P3-4 | `turn_end.toolResults` 逐条 | `pi 有且 App 不需要` | 内容已逐条经 `tool_execution_end` 到 App |
| P3-5 | `bash_execution_update.id` | `pi 有且 App 不需要` | pi 一次只跑一条 bash（`rpc-types.ts:55`；App 侧 `PiSessionViewModel.runBash` 也拒绝并发） |
| P3-6 | `/quit` | `pi 有但我们够不着（无意义）` | pi 的引擎由 App 的前台服务持有（`.../service/PiEngineService.kt`），没有「退出 pi」这个用户动作；保留该行只为让用户不困惑（`PiSlashCommands.kt:122-125,171`） |
| P3-7 | 隐藏命令 `/debug`、`/arminsayshi`、`/dementedelves` | `pi 有但我们够不着（且不是产品能力）` | `modes/interactive/interactive-mode.ts:6447,6480,6486`；它们**不在** `BUILTIN_SLASH_COMMANDS` 里，`get_commands` 也不含内置命令，所以任何客户端都列不出 |
| P3-8 | `--export <in> [out]`（CLI 一次性导出） | `pi 有且 App 有（等价）` | `cli/args.ts:164-165`，它是**非 RPC** 的一次性模式；App 的长期 RPC 引擎不能进那个模式，但 `export_html` + 自写 JSONL 覆盖了同样两种产物（`PiSessionViewModel.kt:2530-2600`） |
| P3-9 | `--mode text` / `--mode json`（管道式一次问答） | `pi 有但 App 有意不做` | `cli/args.ts:11`；App 与引擎的唯一协议是 `--mode rpc`（`.../engine/PiEngineHost.kt:274`），再开一种模式等于第二套生命周期 |
| P3-10 | `user_bash` 钩子（扩展替换用户命令的执行） | `pi 有且 App 有（结果可见）` | `modes/rpc/rpc-mode.ts:563-584`：钩子返回值直接成为 `bash` 命令的响应；App 显示结果（`.../ui/chat/BashPanel.kt`）。App 分辨不出命令被换过，但用户看到的结果是真的 |

---

## 2. 「能立刻修的小缺口」单独列一节

这一节只有一条判据：**改动能用代码证明，且改完就没有一句用户可见的谎话。**
（「谎话」= 界面上写着可以做的动作，实际上做不到。）

| # | 缺口 | 现状（`file:line`） | 本轮 | 影响面 |
|---|---|---|---|---|
| S-1 | 工作区「文件 / Git / 任务」三段是**空状态**，承诺的能力 pi 根本没有 | `ui/screens/WorkbenchScreen.kt:105-115`（改动前；该文件现在 80 行，这段已不存在） | **已删**（§4.1） | 一个文件的三个分支 + 一批随之无用的 import；`PiEmptyState` 仍被别处使用 |
| S-2 | 命令面板与通知把用户**指去终端**，而终端不可用 | `PiSessionViewModel.kt:2706-2715`；`ui/chat/SlashPalette.kt:102-115` | **已改**（§4.3） | 改一句话（`notifyTerminalOnly`），面板徽标从「仅终端」改成两态（App 落点 / 本应用没有入口） |
| S-3 | `/reload`、`/trust`、`/login`、`/logout` 其实**有 App 落点**，却全标「仅终端」 | `ui/chat/PiSlashCommands.kt:185-224（`/trust`、`/login`、`/logout`、`/reload` 四行）` | **已改**（§4.3）：新增 `appLanding` 字段，四处写出落点 | `PiSlashCommand` 加一个**带默认值**的末位字段（所有既有构造点位置参数不受影响）；`ChatScreen.reloadCommand()` 改为复用该行，去掉第二份副本 |
| S-4 | 订阅登录那一行点「执行」把用户**导航到终端** | `ui/settings/PiSettingsRegistry.kt:369-382`；`ui/PiRoot.kt:196-214` | **已改**（§4.4） | 两处文案 + 去掉那次导航；`NavRequest.Workbench` 仍被别处使用，无死代码 |
| S-5 | 「仅终端可用的扩展」一节让用户去终端 | `ui/chat/ChatSheets.kt:403-425` | **已改**（§4.5） | 一段文案 |
| S-6 | 引擎因扩展加载失败退出时，让用户**去终端的扩展目录**里排查 | `engine/EngineExitCause.kt:107-108` | **已改**（§4.6） | 一句话；该文件有 bare-JVM harness（`EngineExitCauseCheck.kt`，断言只看 `summary()` 且要求不出现路径），不动 `summary()` 的判据 |
| S-7 | 扩展发来无编号对话框时，提示「改用终端模式」 | `ui/PiSessionViewModel.kt:1471-1477` | **已改**（§4.7） | 一句话 |
| S-8 | 排队消息**只能整批收**（Stop），不能只收而不打断 | `PiEngineSession.kt:874-880`；`ChatScreen.kt:1023,1458-1480` | **未做**，方案在 §4.2 | 4 个文件约 30 行；见下方说明 |

**S-8 为什么留到下一轮。** 它是本节唯一一条「要加功能」的小缺口（其余七条都是删／改文案）。
改动面是 `PiEngineSession`（新 `drainQueue()`）→ `PiEngineApi`（一层转发）→ `PiSessionViewModel`
（新 `restoreQueue`）→ `ChatScreen`（队列 chip 变可点）。这台机器上**编译不了 Compose**
（`tools/typecheck.sh` 不编 `ui/**`，`run-app-pure-checks.sh` 只跑纯逻辑），四文件改动没有本地
验证手段，而它是纯增量、不修也不会说假话（现在的 Stop 是真的能收回排队文本的）。所以按
「改动小而能讲清」的规矩，本轮只给方案、不动代码，并把它记为**下一个该做的**。

---

## 3. 逐项清单（题面七项，一项不漏）

### 3.1 RPC 命令：33 个逐个问「用户在界面上做得到吗」

命令名与行号来自 `packages/coding-agent/src/modes/rpc/rpc-types.ts:20-74`。App 证据全部现查。

**关于行号（重要）**：本文的 App 侧行号是**写作这一刻的这棵工作树**的快照。
`ui/screens/ChatScreen.kt`、`ui/PiSessionViewModel.kt`、`ui/settings/PiSettingsRegistry.kt`
正在被其他作者同时改动，行号会漂——本文定稿前刚重新对齐过一次（`PiSessionViewModel.kt`
一次就移动了约 100 行）。所以本文的每条判定都写了**符号名与内容**，行号只是方便跳转；
跳不准时请按符号名找。顺带一个应当修的事实：`docs/rpc-coverage.md` 里 `ChatScreen.kt`
的行号（例如它把 `:1070-1073` 写成模型选择器、把 `:1255` 写成 `/copy`）**已经对不上**，
那份文档的结论仍然成立，但按行号跳会跳错地方。

| 命令 | pi 侧 | App 用户能不能做 | App 证据（现查） | 四态 |
|---|---|---|---|---|
| `prompt` | `:22` | 能：输入框发送；回合中自动带 `streamingBehavior` | `PiSessionViewModel.kt:2023-2055`（`send`）、`:2131-2145`（`sendFollowUp`） | `pi 有且 App 有` |
| `steer` | `:23` | 能：回合中发送即 steer | 同上 `:2023-2055`（`send` 里 `transcript.streaming` 那一支） | `pi 有且 App 有` |
| `follow_up` | `:24` | 能：「后续」chip（仅在流式中出现） | `ChatScreen.kt:1138-1160`（`onFollowUp`） | `pi 有且 App 有` |
| `abort` | `:25` | 能：发送键在流式中变 Stop | `ChatScreen.kt:1524-1528`；`PiSessionViewModel.kt:2149-2160` | `pi 有且 App 有` |
| `clear_queue` | `:26` | **部分**：只能随 Stop 一起清（见 P0-2） | `PiEngineSession.kt:874-880` | `pi 有但 App 没有（可做）`——缺「只清不打断」 |
| `new_session` | `:27`（含 `parentSession`） | 能：三条入口 + 会话页「以此为父新建」 | `ChatScreen.kt:645,725`；`SessionsScreen.kt:205,233`；`PiSessionViewModel.kt:2379,2398` | `pi 有且 App 有` |
| `get_state` | `:30` | 不面向用户（引擎就绪探针 + 收尾判断） | `PiEngineSession.kt:427`（就绪探针）、`:932-934`（收尾时用 `isStreaming`/`isCompacting`） | `pi 有且 App 有意不需要界面` |
| `set_model` | `:33` | 能：模型选择器 | `ChatScreen.kt:1214-1224` | `pi 有且 App 有` |
| `cycle_model` | `:34` | 能：点模型行循环 | `ChatScreen.kt:773` | `pi 有且 App 有` |
| `get_available_models` | `:35` | 能（选择器数据源） | `PiSessionViewModel.kt:1905-1908` | `pi 有且 App 有` |
| `set_thinking_level` | `:38` | 能：思考等级选择器 | `ChatScreen.kt:1226-1230` | `pi 有且 App 有` |
| `cycle_thinking_level` | `:39` | 能：点思考 chip | `ChatScreen.kt:782,1130` | `pi 有且 App 有` |
| `get_available_thinking_levels` | `:40` | 能（选择器数据源） | `PiSessionViewModel.kt:1895-1899` | `pi 有且 App 有` |
| `set_steering_mode` | `:43` | 能：「会话与队列」里的「穿插」分段 | `ChatSheets.kt:308-322,433-460`；`ChatScreen.kt:1235` | `pi 有且 App 有` |
| `set_follow_up_mode` | `:44` | 能：同上「后续」分段 | 同上 `:1236` | `pi 有且 App 有` |
| `compact` | `:47` | 能：面板 `/compact`（可带指令）+ 设置行 + 溢出菜单 | `ChatScreen.kt:646,1241`；`PiSessionViewModel.kt:2251-2259` | `pi 有且 App 有` |
| `set_auto_compaction` | `:48` | 能：开关 | `ChatScreen.kt:1237` | `pi 有且 App 有` |
| `set_auto_retry` | `:51` | 能：开关 | `ChatScreen.kt:1238` | `pi 有且 App 有` |
| `abort_retry` | `:52` | 能：按钮 | `ChatScreen.kt:1239` | `pi 有且 App 有` |
| `bash` | `:55` | 能：`!` / `!!` 前缀 + BashPanel | `ChatScreen.kt:1010-1040,1179`；`PiSessionViewModel.kt:2327-2353` | `pi 有且 App 有` |
| `abort_bash` | `:56` | 能：面板里的停止 | `ChatScreen.kt:1033`；`PiSessionViewModel.kt:2356-2358` | `pi 有且 App 有` |
| `get_session_stats` | `:59` | 能：统计面板（费用/token/上下文占用） | `ChatScreen.kt:1270`；`ChatSheets.kt:470-500` | `pi 有且 App 有` |
| `export_html` | `:60` | 能：面板 `/export` + 溢出菜单；**产物拿不出来**（P1-3） | `ChatScreen.kt:638,742,1263`；`PiSessionViewModel.kt:2530-2552` | `pi 有且 App 有`（拿到文件那半见 P1-3） |
| `switch_session` | `:61` | 能：会话列表点开 | `SessionsScreen.kt:193`；`PiSessionViewModel.kt:2446-2461` | `pi 有且 App 有` |
| `fork` | `:62` | 能：面板 `/fork` / 溢出菜单 / 长按消息 | `ChatScreen.kt:631-634,729-730`；`ChatSheet.Fork` | `pi 有且 App 有` |
| `clone` | `:63` | 能：面板 `/clone` + 溢出菜单 | `ChatScreen.kt:635,734` | `pi 有且 App 有` |
| `get_fork_messages` | `:64` | 能（分叉选择器数据源） | `PiSessionViewModel.kt:1957-1960` | `pi 有且 App 有` |
| `get_entries` | `:65` | 能（重放会话；可选的 `since` 未用，是性能选择） | `PiSessionViewModel.kt:1838（重放）,1951（树）,2570（导出）` | `pi 有且 App 有` |
| `get_tree` | `:66` | 能：会话树页（只读，见 P2-3） | `ChatScreen.kt:628,715,1249`；`ui/chat/SessionTreeScreen.kt` | `pi 有且 App 有` |
| `get_last_assistant_text` | `:67` | 能：`/copy` | `ChatScreen.kt:1400-1406`（`copyLastAssistant`） | `pi 有且 App 有` |
| `set_session_name` | `:68` | 能：面板 `/name` + 重命名面板 | `ChatScreen.kt:640,738`；`PiSessionViewModel.kt:2497-2500` | `pi 有且 App 有` |
| `get_messages` | `:71` | 有意不做界面 | 见 P3-1 | `pi 有且 App 有意不需要` |
| `get_commands` | `:74` | 能：`/` 面板 | `PiSessionViewModel.kt:1927`；`ui/chat/PiSlashCommands.kt:199-224` | `pi 有且 App 有` |

**小结：33/33 有 builder 与调用层封装；32/33 有用户可见入口（`get_messages` 有意不做）；
唯一的「有入口但不完整」是 `clear_queue`（只能连 abort 一起用，P0-2）。**

抽查复核的结论：`docs/rpc-coverage.md` §1 里标「已经有」的那些，**内容属实**——上表每一行都是
现查现验的。但那份文档里 `ChatScreen.kt` 的行号（如 `:1070-1073` 说是模型选择器，实际是 `@` 提及
的注释；`:1255` 说是 `/copy`，实际是打开分叉面板）**已经对不上**，因为那个文件在本工作树里被改过。
读那份文档时请按内容找，不要按行号跳。

### 3.2 pi 交给模型的工具全集：看得出用了吗？能干预吗？

**工具全集**（`core/tools/index.ts:96-105` 的 `allToolNames`，8 个）：

| 工具 | pi 定义 | App 能看出模型用了它吗 | App 能干预吗（专门批准/拒绝） | 专用渲染 |
|---|---|---|---|---|
| `read` | `tools/read.ts` | 能：通用卡（名字 + `file_path` 摘要 + 输出） | 不能直接；只能靠扩展的 `tool_call` 钩子 | **没有**（pi 有行号 + 高亮，`renderers/read.ts:127`） |
| `bash` | `tools/bash.ts` | 能；且有**独立面板**（实时输出/退出码/取消/截断路径） | 能取消（`abort_bash`） | 大致有（`BashPanel.kt`） |
| `powershell` | `tools/powershell.ts` | 能（若被启用） | 同上 | 没有（`renderers/index.ts:37` 与 bash 共用渲染器） |
| `edit` | `tools/edit.ts` | 能 | 只能靠钩子 | **有**：diff 卡（`DiffBlock`） |
| `write` | `tools/write.ts` | 能 | 只能靠钩子 | **有**：diff 卡 |
| `grep` | `tools/grep.ts` | 能 | 只能靠钩子 | 没有 |
| `find` | `tools/find.ts` | 能 | 只能靠钩子 | 没有 |
| `ls` | `tools/ls.ts` | 能 | 只能靠钩子 | 没有 |

- **渲染**：`ui/blocks/ToolCallBlock.kt:36-190` 是通用卡，`toolName` 直接印出来
  （`item.toolName.ifEmpty { "工具" }`），所以「模型用了哪个工具」永远看得出来。
  pi 那套按工具分派的渲染器（`core/tools/renderers/index.ts:34-44`）在 RPC 下不会被执行——
  **渲染器是 TUI 进程里的东西，不上线**；但**导出的 HTML 会**用它
  （`core/agent-session.ts:3463-3476` 的 `createToolHtmlRenderer`），所以扩展的
  `renderCall`/`renderResult` 唯一的 App 侧落点就是导出物（`PiSessionViewModel.kt:2542`）。
- **审批**：pi **没有**内建工具审批。机制是扩展的 `tool_call` 钩子返回 `{block:true,reason}`
  （`packages/agent/src/types.ts:58-62`），pi 把它变成一条**错误工具结果**
  （`packages/agent/src/agent-loop.ts:643-644`），App 显示成失败卡——**这条链是完整的**
  （`ToolCallBlock.kt:97-116` 的三态）。App 自己那份「设备工具确认」就是这样一个扩展
  （`app/src/main/assets/pi-extensions/pi-android-permission-gate.ts:11-18`，走 `ctx.ui.select`
  → App 的 `ExtensionDialogs`）。
- **`task` / `todo` / 子代理 / 后台执行**（题面点名）：
  - **pi 核心没有**。`allToolNames` 只有上面 8 个；`grep -rn "todo\|subagent" packages/*/src`
    在核心源码里 **0 命中**。
  - 它们以**示例扩展**存在：`packages/coding-agent/examples/extensions/todo.ts:136-284`
    （注册 `todo` 工具 + `/todos` 命令 + 自定义 `renderCall/renderResult`）、
    `examples/extensions/subagent/index.ts:473-767`（`subagent` 工具，支持并行/链式，
    也带自定义渲染）。
  - 若用户装上它们：`/todos` 这类**命令**会出现在 App 的 `/` 面板并能派发
    （`get_commands` → `piCommandPalette`，`PiSlashCommands.kt:199-224`）；
    **工具**会以通用卡出现，自定义渲染被丢弃（同 P2-4）——`todo` 的清单在
    `details` 里（`todo.ts:29-38`），App 把它当文本，看起来就是 pi 的文本输出。
  - 判定：**`pi 有（示例扩展）但 App 没有专用呈现（可做但没意义）`**——App 随包只装了三个扩展
    （`app/src/main/kotlin/app/pi/packages/PiPackageModel.kt:116`），这两个示例不在其中；为一个
    用户没装的示例扩展做专用卡不符合「pi 有的 1:1」口径（pi 核心没有它）。

### 3.3 pi 的斜杠命令全集 vs App 命令面板

pi 的**内置**命令表：`core/slash-commands.ts:19-43`，**23** 条。App 的转写在
`ui/chat/PiSlashCommands.kt:149-224`，逐条对得上（名字、顺序、参数提示都有）。

我还把 TUI 里**实际处理**的斜杠串全量抽了一遍（`grep 'text === "/…"'` 于
`modes/interactive/interactive-mode.ts`）：**26** 个拼写 = 上面 23 个 + 3 个隐藏命令
（`/debug`、`/arminsayshi`、`/dementedelves`，`interactive-mode.ts:6447,6480,6486`）。
后三个不在 `BUILTIN_SLASH_COMMANDS` 里，也不在 `get_commands` 的输出里（内置命令被 pi 故意排除，
`docs/rpc.md:853`），所以**任何客户端都列不出来**，不是 App 漏了。

逐条判定（「等价落点」这一列是题面要求写清的）：

| pi 命令 | pi 实现 | App 面板现状 | App 等价落点 | 四态 |
|---|---|---|---|---|
| `/settings` | `:3043` | 打开设置 | 设置目的地 | `pi 有且 App 有` |
| `/model` | `:2980` | 模型选择面板 | 模型 chip / 面板 | `pi 有且 App 有` |
| `/thinking` | `:2986` | 思考等级面板 | 思考 chip | `pi 有且 App 有` |
| `/tree` | `:3042` → `showTreeSelector :5205` | 会话树页 | 只读树 + 分叉（P2-3） | `pi 有且 App 有`（少「移动叶子」） |
| `/scoped-models` | `:2975-2978` → `showModelsSelector :5024` | 跳到 `enabledModels` 行 | 设置 → 模型与推理 → 循环模型 | `pi 有且 App 有` |
| `/export` | `handleExportCommand :6060` | 导出（HTML/JSONL 按扩展名） | — | `pi 有且 App 有`（文件拿不出来见 P1-3） |
| **`/import`** | `handleImportCommand :6107` | **标「仅终端」** | **没有** | **`pi 有但 App 没有（可做）`**（P1-1） |
| **`/share`** | `:3002` → `session-share.ts:43` | **标「仅终端」** | **没有** | **`pi 有但我们够不着（终端不可用）`**（P1-2） |
| `/copy` | `handleCopyCommand` | 复制最后回复 | — | `pi 有且 App 有` |
| `/name` | `handleNameCommand :6193` | 重命名面板 | — | `pi 有且 App 有` |
| `/session` | `handleSessionCommand :6217` | 会话信息面板 | — | `pi 有且 App 有` |
| **`/changelog`** | `handleChangelogCommand :6280` | **标「仅终端」** | **没有** | **`pi 有但我们够不着（终端不可用）`**；可做＝把 pi 自带 `CHANGELOG.md` 读出来展示（`utils/changelog.ts`），但那是 App 自造的一页，pi 只在自己的 TUI 里读它 → 建议**不做** |
| **`/hotkeys`** | `handleHotkeysCommand :6315` | **标「仅终端」** | 没有；GUI 没有键盘快捷键表 | **`pi 有但我们够不着（终端不可用）`**；**不做**（手机上是触屏，pi 的键位表在这里没有对应物） |
| `/fork` | `:3032` → `showUserMessageSelector :5146` | 分叉选择器 | — | `pi 有且 App 有` |
| `/clone` | `:3037` → `handleCloneCommand :5184` | 直接克隆 | — | `pi 有且 App 有` |
| **`/trust`** | 见 §3.3 下方 | **原本标「仅终端」；本轮已改**为「本应用：设置 → 扩展 → 扩展包与项目信任」 | **有**：设置 → 扩展 → 扩展包与项目信任（pi 的五个选项原样转写） | **`pi 有且 App 有`** ← 面板原来标错了 |
| **`/login`** | `handleLoginCommand :5485` | **原本标「仅终端」；本轮已改**为「本应用：设置 → 模型与推理 → 凭证」 | **部分**：凭证页写 API Key；OAuth 没有落点 | **`pi 有但 App 只有一半`**（P0-1） |
| **`/logout`** | `:3058-3061`（OAuth 选择器） | **原本标「仅终端」；本轮已改**为「本应用：设置 → 模型与推理 → 凭证」 | **部分**：凭证页可以移除该厂商凭证 | **`pi 有但 App 只有一半`**（P0-1） |
| `/new` | `:3063` → `handleClearCommand` | 新建会话 | — | `pi 有且 App 有` |
| `/compact` | `:3068` | 压缩（可带指令） | — | `pi 有且 App 有` |
| `/resume` | `:3094` → `showSessionSelector` | 会话页 | — | `pi 有且 App 有` |
| **`/reload`** | `handleReloadCommand :5970` | **原本标「仅终端」；本轮已改**为「本应用：设置 → 运行时与诊断 → 进程 → 重启引擎」 | **有**：设置 → 运行时与诊断 → 进程 → 重启引擎（引擎重跑会重读设置并重扫资源目录） | **`pi 有且 App 有`** ← 面板原来标错了（P0-3） |
| `/quit` | `:3063` 附近 → 退出 | 标「仅终端」→ 本轮改成「本应用没有入口」 | 无（P3-6） | `pi 有但我们够不着（无意义）` |
| `/debug`（隐藏） | `:6447` | 面板里没有（列不出来） | — | `pi 有但我们够不着` |
| `/arminsayshi`、`/dementedelves`（隐藏） | `:6480,6486` | 同上 | — | `pi 有但我们够不着（彩蛋）` |

`/trust` 的 App 落点证据：`app/src/main/kotlin/app/pi/packages/ProjectTrust.kt:5-33`
（把 pi 的 `resolveProjectTrusted` 与「RPC 无 UI 时静默跳过项目资源」这件事整段复刻，
并给出 pi 的五个选项）、`.../packages/PiProjectTrustPrompt.kt:37-62`（提问界面）、
`.../packages/TrustRepository.kt:140`（写出 pi 的 `trust.json` 格式）、
入口在 `.../packages/PiPackagesScreen.kt:118-151`。pi 侧：`core/trust-manager.ts:66-96`
（五个选项）、`:69`（`{ "<canonical cwd>": true }`）。

**结论：23 条内置命令，18 条 App 有真落点（其中 3 条原来被错标成「仅终端」，本轮改正），4 条够不着，
1 条（`/import`）可做。** 另有 3 条隐藏命令任何客户端都列不出。

### 3.4 扩展能力面：协议支持而 App 没有可视落点的

判定依据是 `modes/rpc/rpc-mode.ts:117-311` 里那个真实的 `ExtensionUIContext` 实现
（**不是**文档），逐项读过：

| 能力 | RPC 里的实际行为 | App 落点 | 四态 |
|---|---|---|---|
| `select` / `confirm` / `input` / `editor` | 发 `extension_ui_request`，等应答 | `ui/extension/ExtensionDialogs.kt:81-256`（含超时倒计时 `:295-320`） | `pi 有且 App 有` |
| `notify` | 发请求，fire-and-forget | snackbar（`ExtensionUiHost.kt`） | `pi 有且 App 有` |
| `setStatus` | 发请求（`rpc-mode.ts:167-177`） | `ExtensionChrome.kt:47-79` | `pi 有且 App 有` |
| `setWidget` | **只发字符串数组**，组件工厂被丢（`:195-208`） | `ExtensionChrome.kt:90-126`（两种 placement 都画） | `pi 有且 App 有`（组件工厂部分 `够不着`，pi 自己丢的） |
| `setTitle` | 发请求（`:218-226`） | AppBar 标题（`ExtensionChrome.kt:129`） | `pi 有且 App 有`（语义是终端窗口标题，App 的用法是解释，已注明） |
| `set_editor_text` | 发请求（`:238-246`） | composer 填充（一次写入带序号） | `pi 有且 App 有` |
| 扩展**命令** | `get_commands` → `source:"extension"` | `/` 面板 + `prompt("/name")` 派发 | `pi 有且 App 有` |
| 扩展**工具** | 只有 `tool_execution_*` 带上线 | 通用卡（P2-1） | `pi 有且 App 有`（渲染降级） |
| **`custom()`** | `return undefined as never`（`:229-231`） | 无。扩展拿到 `undefined`，多数会走取消分支 | **`pi 有但我们够不着（RPC no-op，无信号）`** |
| **`setFooter` / `setHeader`** | 注释 + 空函数（`:189`、`:197`） | 无 | 同上 |
| **`setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator`** | 空函数（`:174`、`:179`、`:183`） | 无 | 同上 |
| **`setHiddenThinkingLabel`** | 空函数（`:187`） | 无 | 同上 |
| **`getEditorText`** | 恒返回 `""`（`:240-244`） | 无（App 自己维护输入框状态） | 同上 |
| **`addAutocompleteProvider` / `setEditorComponent` / `getEditorComponent`** | 空函数 / `undefined`（`:255-276`） | 无（App 有自己的 `@`/`/` 面板） | 同上 |
| **主题三件套**（`getAllThemes`/`getTheme`/`setTheme`） | `[]` / `undefined` / `{success:false}`（`:287-296`） | 无（App 的主题来自 pi 的主题 JSON 文件，不是扩展 API） | 同上 |
| **`get/setToolsExpanded`** | `false` / 空函数（`:300-307`） | App 有**自己的**「工具输出默认展开」设置（`PiSettingsRegistry.kt:700-712`），但那是 App 的键，不是这个 API | 同上 |
| **`onTerminalInput`** | 返回空退订（`:163-166`） | 无 | 同上 |
| 三个**可否决**钩子：`session_before_switch` / `session_before_fork` / `session_before_compact` | 否决经 RPC 响应回传 | `PiSessionViewModel.kt:2382-2385`（switch 取消）、`:2155-2158`（fork 取消）、`rpc/.../Transcript.kt:1555-1604`（compact 的 `aborted`/`errorMessage`） | `pi 有且 App 有` |
| `session_before_tree` / `session_tree` / `navigateTree` / `setLabel` | **没有 RPC 命令** | 树只读（P2-3） | `pi 有但我们够不着（RPC 无命令）` |
| `registerMessageRenderer` / `registerEntryRenderer` / `registerMarkdownTransformer` | 渲染器只在 TUI 被调用 | 通用兜底：`HookMessageBlock`、`onCustomEntry`、`BlockRenderer.kt:91-101` | `pi 有但我们够不着（渲染器不上线，内容不丢）` |
| `tool_call` 否决 | 变成错误工具结果（`agent-loop.ts:643-644`） | 失败卡（有落点） | `pi 有且 App 有` |
| `user_bash` 替换执行 | 结果直接进 `bash` 响应 | BashPanel | `pi 有且 App 有`（P3-10） |
| `input` 的 `handled` | 提前返回，无事件 | 无 | `pi 有但我们够不着（pi 不报信号）`（P2-5） |
| `getActiveTools` / `getAllTools` / `getSystemPrompt` | 只在**扩展上下文**里（`types.ts:1400,1403,348`） | 无 | `pi 有但我们够不着（无 RPC 命令）`（P2-2） |

**这张表补全了 `docs/rpc-coverage.md` §4 的两处：** (a) 那份表把「no-op 一族」列成一行，
这里逐个给了 `rpc-mode.ts` 的行号；(b) 它把「靠终端页兜底」写成处置——终端不可用后，
这一整块（`custom`、footer/header、working 系列、渲染器）**没有任何兜底**，只能如实显示
「本应用显示不了」并列出哪些扩展受影响（`ui/chat/TuiOnlyScan.kt` 已经在做这件事，
只是它的提示文案指向终端，本轮改掉）。

### 3.5 用户点名的「后台任务 / 定时任务」：独立复核

**结论：pi 没有 cron / 定时任务 / 后台任务这一整类东西。用户的结论成立。**

我把 `setInterval` 在本仓库里出现的**全部**位置列了出来（`packages/*/src`，排除测试）：

| 位置 | 是什么 | 是不是调度器 |
|---|---|---|
| `core/tools/renderers/bash.ts:140` | bash 卡的**重绘节流**（1s） | 否，渲染 |
| `modes/interactive/components/countdown-timer.ts:8,21` | 对话框倒计时 | 否，TUI 显示 |
| `modes/interactive/components/armin.ts:62,182` | 动画 | 否，TUI 动画 |
| `modes/interactive/components/daxnuts.ts:60,78` | 动画 | 否，TUI 动画 |
| `modes/interactive/interactive-mode.ts:4097` | `suspendKeepAlive = setInterval(() => {}, 2**30)` | 否，是**进程挂起时保活**，让 Node 事件循环别停 |
| `packages/tui/src/components/loader.ts:82` | 载入动画 | 否 |
| `packages/tui/src/terminal.ts:530` | 终端进度条 | 否 |
| `packages/tui/src/tui-alt-screen.ts:1270` | 鼠标选择自动滚动（50ms） | 否 |
| `experimental/mini/shared/rpc.ts:153` | 实验性 mini 模式的轮询 | 否（且 `experimental/` 不在 RPC 路径上：`modes/rpc/**` 与 `main.ts` 都不引用它） |

用户在题面里说「只有 bash 渲染器、TUI 倒计时、TUI 动画、suspendKeepAlive 四处」——
**方向对，数字不全**：非实验代码里还有 `daxnuts`（第二个动画）与 `packages/tui` 的三处
（loader 动画、终端进度、选择自动滚动）。它们全部是显示层，**没有一处是「到点执行某件事」**。
另外 `core/footer-data-provider.ts:201-235` 的 `scheduleRefresh` 是 500ms 防抖的**页脚刷新**，
不是定时任务。

**那「后台/长时」的各种形式呢？** 逐条查过：

| 形式 | pi 有没有 | pi 证据 | App 有没有落点 | 四态 |
|---|---|---|---|---|
| cron / 定时执行 | **没有** | 上表 | — | `pi 无对应物（App 的决定）` |
| bash 的 `run_in_background` 之类参数 | **没有**：pi 的 bash 工具入参只有 `command` 与 `timeout` | `core/tools/bash.ts:35-38`（schema）；`:127`（`await waitForChildProcess(child)`，**等子进程退出才返回**） | App 侧 `bash` 命令也只有这两个概念（命令 + 排除出上下文） | `pi 无对应物` |
| `detached` | **有，但不是后台执行**：`spawn(..., detached: process.platform !== "win32")` 是**进程组**，目的是 `killProcessTree` 能整棵杀掉 | `core/tools/bash.ts:96`、`:103`、`:107`、`:113`；`utils/shell.ts:198-211` | App 的 `abort_bash` 走同一个 `abortBash` | `pi 有且 App 有`（用户感觉不到差别） |
| 命令自己用 `&` 放后台 | **能，且 pi 会失去跟踪**：shell 立刻退出 → 工具返回 → 那个进程留在 `trackedDetachedChildPids` 里，只在 pi 退出时被 `killTrackedDetachedChildren` 收掉 | `core/tools/bash.ts:107`（登记 pid）、`:129`（注释：不等被 detached 后代持有的 stdio）；`utils/shell.ts:206-211`（退出时清理） | App 的 BashPanel 只显示这一条命令的结果，之后那个后台进程**在界面上完全不可见、也没法停**（`abort_bash` 只管 pi 记着的那条） | **`pi 有（shell 层面）但两边都没有界面`** → App 侧 `pi 无对应物（App 的决定）`：没有可显示的对象，也就没有「任务列表」 |
| 会话级穿插队列 `steering` | **有** | `core/agent-session.ts:150-154`（事件形状）、`:593-596`（`_emitQueueUpdate`）、`:1608-1616`（`clearQueue` 返回两串文本） | **有**：`queue_update` → 条数 chip | `pi 有且 App 有` |
| 会话级后续队列 `followUp` | **有** | 同上 | **有**：「后续 N」chip + 逐条本地回显（消息本体可见） | `pi 有且 App 有` |
| 队列的**逐条**操作 | **有**：`app.message.dequeue` 把队列收回输入框而不打断 | `modes/interactive/interactive-mode.ts:2899`（绑定）、`:4157-4164`（`handleDequeue`）、`:4387-4406`（实现） | **没有**：只有 Stop 的整批收回 | **`pi 有但 App 没有（可做）`**（P0-2） |
| `get_state.pendingMessageCount` | **有** | `core/agent-session.ts:1618-1621` | 解析了但没人读；等价信息来自 `queue_update` | `pi 有且 App 有（等价信息）`（P3-2） |

**队列落点的具体界面**（题面要求核到 UI）：chip 在 `ui/screens/ChatScreen.kt:1022-1023`
（`QueueRow`，定义在 `:1459-1480`），条数来自 `PiSessionViewModel.kt:1349-1352`
（`PiEvent.QueueUpdate`），消息本体来自 App 自己的本地回显
（`PiEngineSession.echoUserPrompt`，`PiSessionViewModel.kt:2044-2047`、`:2062-2065`），
Stop 时的整批收回在 `PiSessionViewModel.kt:2149-2160` + `ChatScreen.kt:1198-1200,1304-1316`。

### 3.6 工作区页的三段空状态

`ui/screens/WorkbenchScreen.kt` 原来是四个分段：终端 / 文件 / Git / 任务，后三个是空状态
（原文 `:105-115`）。逐段回答：

| 段 | 原文承诺 | pi 有对应能力吗 | 四态 | 处置 |
|---|---|---|---|---|
| **文件**（「文件树还没做」） | 显示工作目录的目录树 | **没有**。pi 的 TUI 组件清单里没有任何文件树/资源管理器（`modes/interactive/components/` 44 个文件，最接近的是 `show-images-selector.ts` 与 `session-selector.ts`）；pi 表达「看文件」的方式是 `@` 提及补全（`packages/tui/src/autocomplete.ts:289-311`，App 已实现于 `ui/chat/PiFileMentions.kt`）与 `ls`/`read` 工具 | `pi 无对应物（App 的决定）` | **删**（本轮已做，§4.1） |
| **Git**（「显示变更、diff 与检查点」） | 变更 / diff / 检查点 | **只有分支名**：`core/footer-data-provider.ts:127` + `modes/interactive/components/footer.ts:117`，而且只喂 TUI 页脚，**RPC 无通道**。`git-checkpoint` 是**示例扩展**（`examples/extensions/git-checkpoint.ts:1-10`：在每回合前 `git stash create`，分叉时问要不要恢复），不是内建能力，也不在 App 随包的三个扩展里 | 「变更/diff/检查点」= `pi 无对应物（App 的决定）`；「分支名」= `pi 有但我们够不着（RPC 无通道；终端不可用）` | **删**（本轮已做，§4.1）；分支名如需显示，是 App 自算的第二份真相，**不建议** |
| **任务**（「没有后台任务」/「长时间运行的回合与命令会出现在这里」） | 后台任务列表 | **没有**。§3.5 已独立复核：pi 没有 cron、没有后台任务、bash 工具没有后台参数，`&` 放出去的进程连 pi 自己都不跟踪 | `pi 无对应物（App 的决定）` | **删**（本轮已做，§4.1） |

**删除的影响面**：`WorkbenchScreen.kt` 从「四分段」变成「只有终端」；随之无用的
`SegmentedButton`/`Icons.Filled.Folder`/`Icons.Filled.AccountTree`/`Icons.Filled.TaskAlt`/
`PiEmptyState`/`rememberSaveable` 等 import 一并移除。**没有任何消费者**引用这三个分段的
状态（`segment` 是本地 `rememberSaveable`，没有被提升到别处），也没有测试/契约断言提到它们
（`grep -rn "工作区.*文件树\|Icons.Filled.TaskAlt" app/src rpc/src tools/` 只命中这个文件）。
风险面：零（一个自包含的 Compose 文件）。

### 3.7 导出 / 分享 / 管道

| 能力 | pi 侧 | App 侧 | 四态 |
|---|---|---|---|
| `/export`（HTML 默认，`.jsonl` 走 JSONL） | `modes/interactive/interactive-mode.ts:6060-6075`：按扩展名分派，报出路径 | 有：`PiSessionViewModel.kt:2530-2600`（同样的分派规则），入口在面板 `/export` 与溢出菜单 | `pi 有且 App 有` |
| HTML 导出的**默认落点** | 不传路径时写 `<cwd>/pi-session-<basename>.html`（`core/export-html/index.ts:274-281`） | App 永远传一个显式路径（写进工作区） | `pi 有且 App 有`（App 更确定，因为要核对文件是否真的写出来） |
| 导出物**能被用户拿到** | pi 的落点是用户自己的 cwd | 落点是 App 私有工作区，提示只说文件名；没有打开/保存到 Download/分享 | **`pi 无对应物（App 的决定）`**，但用户能感觉到的缺口 → 见 P1-3（建议做） |
| 导出物**带扩展的自定义渲染** | `core/agent-session.ts:3463-3476`（`createToolHtmlRenderer`） | **有**：调 `export_html` 就等于拿到 pi 渲染的那一份 | `pi 有且 App 有`（对话流里反而没有，见 P2-1） |
| `/import` | `:6107-6119` | **没有** | `pi 有但 App 没有（可做）`（P1-1） |
| `/share`（Radius / 私密 gist） | `session-share.ts:43-53`、`:60-76` | 没有 | `pi 有但我们够不着（终端不可用）`（P1-2） |
| `--export <in> [out]`（CLI） | `cli/args.ts:164-165`、`:312`（帮助文本）、`:384-385`（用法例） | 不适用（App 的引擎常驻 `--mode rpc`） | `pi 有且 App 有（等价）`（P3-8） |
| 「管道」：`--mode text` / `--mode json` | `cli/args.ts:11` | 不适用 | `pi 有但 App 有意不做`（P3-9） |
| 分享**诊断报告**（App 自己的东西） | 无 | 有：存 Download 或系统分享 | `pi 无对应物（App 的决定）`，且它是 P1-3 可以直接复用的那条通道 |

---

## 4. 本轮改了什么（逐条可核）

> 规矩：**不改 pi、不改载荷、不改 `docs/known-gaps.md`；本机不编译。**
> 下面每条都只动 `app/src/main/kotlin` 里的 App 代码。

### 4.1 删掉工作区「文件 / Git / 任务」三段（§3.6）

`ui/screens/WorkbenchScreen.kt`：四分段变一段，三个空状态与它们的图标、`PiEmptyState` 依赖
一并删除；`segment` 本地状态、`SingleChoiceSegmentedButtonRow` 与 `rememberSaveable` 不再需要。
保留终端段与它那句话（终端本身是用户的决定，不在本轮范围内）。

### 4.2 【未做，方案】排队消息「只收回、不打断」（P0-2）

下一轮照这个做，四个文件：

1. `engine/PiEngineSession.kt`：在 `stopAndDrainQueue` 旁边加
   `suspend fun drainQueue(): List<String>` —— `request(PiCommands.clearQueue(nextId()))`，
   读 `steering`/`followUp` 两串文本（复用现成的私有 `queuedText`），**不发 `abort`**；
2. `engine/PiEngineApi.kt`：加一层转发（照 `stopAndDrainQueue` 的写法）；
3. `ui/PiSessionViewModel.kt`：加 `fun restoreQueue(onRestored: (List<String>) -> Unit)`，
   与 `stop`（`PiSessionViewModel.kt:2149-2160`）同形，只是不 abort；
4. `ui/screens/ChatScreen.kt`：`QueueRow`（`:1459-1480`）加一个点击动作
   （`ChatScreen.kt:1022-1023` 传进去），点击时 `session.restoreQueue { restored -> draft = mergeRestoredQueue(restored, draft) }`；
   文案用现有 `mergeRestoredQueue`（`:1304-1316`）的语义。

语义对齐点：pi 的 `restoreQueuedMessagesToEditor()` 把队列文本**与当前输入框内容**拼起来
（`interactive-mode.ts:4397-4400`），App 的 `mergeRestoredQueue` 已经是同一条规则。

### 4.3 命令面板与通知：不再指终端；四条命令写出真实落点（S-2、S-3）

- `ui/chat/PiSlashCommands.kt`：`PiSlashCommand` 新增**末位带默认值**的字段
  `appLanding: String?`（所有既有构造点用的是位置参数或具名参数，加末位默认值不破坏任何调用）；
  四行填上：`/trust` → 「设置 → 扩展 → 扩展包与项目信任」；`/login`、`/logout` →
  「设置 → 模型与推理 → 凭证」；`/reload` → 「设置 → 运行时与诊断 → 进程 → 重启引擎」。
- `ui/chat/SlashPalette.kt`：副标题的徽标逻辑从「`TerminalOnly` → 仅终端」改成两态
  ——「有 `appLanding` → `本应用：<落点>`；没有 → `本应用没有入口`」。
- `ui/PiSessionViewModel.kt` 的 `notifyTerminalOnly`：有 `appLanding` 时报
  「`/x` 在本应用里：<落点>」（info 语气），没有时报「`/x` 只在 pi 的原版 TUI 里，
  本应用没有对应入口」（warning 语气）。**不再出现「工作区 → 终端」这个做不到的指引。**
- `ui/screens/ChatScreen.kt`：AppBar 那个刷新按钮原来自己另造了一份 `/reload` 行；
  现在改为直接取面板里那一行（`PI_BUILTIN_SLASH_COMMANDS.first { it.name == "reload" }`），
  于是「本应用里 `/reload` 在哪」只有一个答案；随之不再需要的 `PiCommandSource` import 一并删掉。

### 4.4 订阅登录那一行不再把人送去终端（S-4）

- `ui/settings/PiSettingsRegistry.kt`：`app.credentials.oauth` 的标题与说明改成如实描述
  ——哪些厂商支持订阅登录、授权在浏览器里完成、**本应用目前只支持 API Key**。
- `ui/PiRoot.kt`：`app.credentials.oauth` 的动作不再 `requestNav(NavRequest.Workbench)`，
  只留一条说明性通知（并指出用 API Key 的那一行在它上面）。

### 4.5 「仅终端可用的扩展」一节（S-5）

`ui/chat/ChatSheets.kt`：标题从「仅终端可用的扩展」改成「本应用显示不了的扩展」，
小节的句子从「请到 工作区 → pi TUI（原版）里使用」改成「用到了本应用不支持的自绘界面，
在对话页不会显示内容」；仍然列出受影响的扩展名与它用到的标记（`TuiOnlyScan.kt` 的数据不变），
因为「哪个扩展会显示不全」才是用户能据以行动的信息。

### 4.6 引擎因扩展加载失败退出时的排查指引（S-6）

`engine/EngineExitCause.kt`：原来是「请到 工作区 → 终端 里查看扩展目录」。两处都不成立：
终端不是可用面，而且扩展目录在应用私有存储里，任何文件管理器都打不开。现在只说用户能做的：
把最近装过或改过的扩展移走后重试。`summary()` 的判据与措辞没动，只改了 `classify()` 里那一条的
句子（harness `app/src/test/kotlin/app/pi/engine/EngineExitCauseCheck.kt` 断言的是 `summary()`
的非空性、含「扩展」、以及**不得**出现 `/root` —— 三条都仍然成立）。

### 4.7 扩展发来无编号对话框时（S-7）

`ui/PiSessionViewModel.kt`：原来是「请改用终端模式运行该扩展」，现在说
「这个扩展的弹窗在当前引擎上无法使用」——原因（请求没有 id，而应答机制按 id 对应）仍在 KDoc 里。

### 4.8 本轮改动的文件清单（一屏可核）

| 文件 | 改了什么 |
|---|---|
| `app/src/main/kotlin/app/pi/ui/screens/WorkbenchScreen.kt` | 删掉文件/Git/任务三段与空状态，只留终端；Kdoc 记下逐段理由 |
| `app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt` | 新增 `appLanding` 字段；`/trust`、`/login`、`/logout`、`/reload` 四行填落点；`TerminalOnly` 的 KDoc 改写 |
| `app/src/main/kotlin/app/pi/ui/chat/SlashPalette.kt` | 徽标两态；文件头 KDoc 同步 |
| `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` | `notifyTerminalOnly` 改写；无编号对话框的提示 |
| `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt` | `reloadCommand()` 改为复用面板那行；import 增删各一条 |
| `app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt` | 订阅登录那一行的标题与说明 |
| `app/src/main/kotlin/app/pi/ui/PiRoot.kt` | 订阅登录那一行的动作不再导航；注释同步 |
| `app/src/main/kotlin/app/pi/ui/chat/ChatSheets.kt` | 「本应用显示不了的扩展」一节 |
| `app/src/main/kotlin/app/pi/engine/EngineExitCause.kt` | 扩展加载失败那一句 |
| `docs/capability-gap.md` | 本文 |

---

## 5. 本机未编译，CI 是编译器

**这台「开发机」就是用户的手机**，按规矩本轮**没有**运行 `tools/typecheck.sh`、
`tools/run-app-pure-checks.sh`、任何 Gradle 任务，也没有运行 `python3 tools/check-nested-comments.py`
以外的构建脚本。**下面的结论是「按源码逐行看过」，不是「编译通过」。**

只做了允许的静态检查（见 §7 的自检表）。

### 5.1 上 CI 最可能出错的点（按可能性排序）

1. **未使用的 import / 未使用的局部变量**：§4.1 删了三个分段，`WorkbenchScreen.kt` 里
   `Icons.Filled.Folder`、`Icons.Filled.AccountTree`、`Icons.Filled.TaskAlt`、`PiEmptyState`、
   `SegmentedButtonDefaults`、`SingleChoiceSegmentedButtonRow`、`SegmentedButton`、
   `rememberSaveable`、`mutableStateOf`、`getValue`/`setValue` 里有一批会变成无用。
   Kotlin 对未使用 import **只报警告**（不红），但如果仓库的 CI 开了
   `allWarningsAsErrors`，就会红——**这是本轮最可能的一条**。
2. **`PiSlashCommand` 新字段的构造点**：新增字段必须放在**末位且有默认值**。
   已有构造点里 `PI_BUILTIN_SLASH_COMMANDS`（`PiSlashCommands.kt:128-172`）用的是位置参数，
   `piCommandPalette`（`:210-221`）与 `ChatScreen.reloadCommand()`（`:1393-1401`）用具名参数。
   若把字段插在中间，位置参数那批会**静默移位**（类型恰好都是 `String?`/`PiCommandAction`，
   编译器可能只在部分行报错）。
3. **`when` 的穷尽性**：本轮**没有**新增 `PiCommandAction` 成员（改的是 `PiSlashCommand` 字段），
   所以 `ChatScreen.pick`（`:616-658`）与 `routeComposerText`（`SlashPalette.kt:228-235`）的
   穷尽分支不受影响。若下一轮为 `/reload` 加新 action，这两处都要同步。
4. **纯逻辑 harness 的源码文本断言**：`tools/run-app-pure-checks.sh` 里的 `settings-audit` 与
   `pre-spawn` 是**读源码文本**做断言的。§4.4 改的是 `PiSettingsRegistry.kt` 里
   `app.credentials.oauth` 那一段的**描述字符串**，`settings-audit` 只认键名与「谁读它」，
   理论上不受影响；但若断言里含「这段描述必须提到终端」之类的文本匹配，就会红——**值得在 CI 里先看这个 job**。
5. **`appLanding` 的文案与设置页的一致性**：本轮写的落点是
   「设置 → 模型与推理 → 凭证」「设置 → 运行时与诊断 → 进程 → 重启引擎」
   「设置 → 扩展 → 扩展包与项目信任」这三条**用户可见路径**，它们必须与设置页真实的分组名一致
   （`PiSettingsRegistry.kt` 的 12 个分组标题 + `SettingsHome` 的「扩展」小节）。
   分组标题被改动而这里没跟着改，就会出现一句对不上的指路。这一条没有任何断言在钉。
6. **`EngineExitCauseCheck` 是**注册在 `tools/run-app-pure-checks.sh` 里的 harness
   （`run_harness engine-exit-cause`），它编译并运行 `EngineExitCause.kt`。
   §4.6 只改了 `classify()` 里一句中文，三条相关断言（`summary()` 非空、含「扩展」、不含 `/root`）
   都不受影响；但如果 CI 里还有别的检查对这段文案做文本匹配，会在这里红。

---

## 6. 未决与设备判据

| 项 | 状态 | 判据 |
|---|---|---|
| P0-2 / S-8 的「只收回不打断」 | 方案已写（§4.2），未实现 | 设备上：流式中发两条消息 → 点队列 chip → 两条文本回到输入框，且**回合仍在跑**（状态行还是「工作中」） |
| P1-3 的「导出物交出去」 | 未做 | 设备上：`/export` → 分享/存 Download → 用文件管理器能打开那个 HTML |
| P1-1 的 `/import` | 未做 | 设备上：选一个 JSONL → 会话能被打开并继续 |
| P2-1 的 `grep`/`find`/`ls` 列表化 | 未做 | 设备上：一次 `grep` 结果 200 行时的滚动与展开是否真的更省事 |
| S-1…S-7 的改动 | 已改，**未编译** | CI 绿；设备上确认命令面板里 `/reload`、`/trust`、`/login` 三行显示的是 App 落点，而 `/import`、`/share`、`/changelog`、`/hotkeys`、`/quit` 显示「本应用没有入口」 |
| 终端不可用后还剩哪些指去终端的入口 | 本轮修了 7 处文案（§4），**未做全量清理** | 剩下的三类都是「打开终端这个目的地」本身，而不是「在那里能做成某件事」：`ChatScreen.kt` 溢出菜单的「打开终端（输入 pi 进原版 TUI）」、composer 的「终端」chip、以及工作区终端段自己的那句说明。它们要不要删，取决于终端这件事的最终结论，本轮不动 |
| `appLanding` 的落点路径会不会过期 | 已写死为字符串 | 分组标题改动时这里不会自动跟着变。若以后加一条「命令面板的落点必须指向存在的设置节点」的断言，`PiSettingsCatalog.groups` 是唯一真相来源 |

---

## 7. 自检（本仓库规矩）

| 检查 | 命令（已跑） | 结果 |
|---|---|---|
| 本机**不编译** | — | 未运行 `typecheck.sh` / `run-app-pure-checks.sh` / Gradle（规矩） |
| 嵌套注释 | `python3 tools/check-nested-comments.py` | `nested-comments: OK (180 Kotlin file(s) scanned)` |
| 改动的 Kotlin 文件可解析性 | 无本地编译器；以逐行阅读 + 括号/分支配平核对代替 | 见 §5.1 的风险清单 |
| import 是否仍被使用 | `grep -n "PiCommandSource\|PI_BUILTIN_SLASH_COMMANDS" ui/screens/ChatScreen.kt` | 删掉 `PiCommandSource`（已无使用点）、加上 `PI_BUILTIN_SLASH_COMMANDS`（新使用点） |
| 证据可核 | 本文每行 `file:line` 都是本轮 `grep`/`sed` 现查的 | — |

**一句必须说明的话**：本文里 pi 侧的每一行都是现查的；App 侧的行号也是现查的，
但 `docs/rpc-coverage.md` 里引用的 `ChatScreen.kt` 行号**已经因为该文件被改动而漂移**——
我在 §3.1 的脚注里写了这件事，读那份文档时请按内容找。
