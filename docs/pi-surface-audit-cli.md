# pi 能力面全量审查 · 命令行 / 协议 / 会话 / 模型认证 / 包与信任 / 可观测

> 回答一个问题：**pi 到底还有啥功能我们没做？**
> 只读审查，未改任何代码；本文件是本次唯一新增的文件。

## 0. 方法与判据

| | 值 |
|---|---|
| pi（权威） | `/root/pi-src` @ `bbb61e3`，`packages/coding-agent` 0.85.1 |
| App | `/root/pi-android` @ `5a49bdc`（工作树，`docs/**` 有并发写者） |
| 协议定义 | `packages/coding-agent/src/modes/rpc/{rpc-types.ts,rpc-mode.ts}`、`modes/json-event.ts`、`packages/agent/src/types.ts`、`packages/ai/src/types.ts` |
| App 协议层 | `rpc/src/main/kotlin/app/pi/rpc/{Commands,Events,Responses,Transcript,PiPreSpawnConfig,PiLaunchOptions}.kt` |
| App 调用/界面层 | `app/src/main/kotlin/app/pi/{engine/**,ui/**,packages/**,session/**,settings/**}` |

四条阅读约定：

1. **每个 pi 结论都带 `packages/...`（或 `src/...`）的 `file:line`**，都是本次在树上读到的；每个 App 结论都带**符号名**（类/函数/键名），也是读到的。
2. 表里的 **`状态` 与 `理由` 是我的判断**（推断），凡拿不准的进 §14「未确认项」，不写成结论。
3. 判据用题面给的：**pi 有而我们没接 = 欠账**；**Operit 有而 pi 没有 = 不是欠账**；**pi 没有对应物 = 不做**。
4. 「我们有没有」一律回代码核对，不引 `docs/rpc-coverage.md` / `gap-disposition.md` 的结论（那两份账本有过"说没做其实做了"的记录）；只在**发现账本与代码冲突**时点名。

**一条贯穿全文的关键区分**（下面反复用到，先立在这里）：pi 的能力有三个可达层 ——
**(L1) CLI/RPC**：`--flag` 或 stdin JSON 命令，App 直接可达；
**(L2) 扩展 API**：扩展在 pi 进程内调用（`ctx.*`），App 间接受益但自己发不出；
**(L3) TUI 内部**：只在 `modes/interactive/**` 里实现，RPC 无通道。
题面要求把 **L3 排除**；但 **L2 里有些东西（如 `ctx.registerFlag`）的值只能由 spawn 参数喂进去** —— 那类不是 L3，下面按欠账处理，并逐条说明为什么。

---

## 1. 结论先行

**还剩 3 条真欠账**（pi 有、手机可用、我们没接）：

1. **扩展注册的 CLI flag 传不进去**（`ctx.registerFlag` / `ctx.getFlag`）。pi 的值只能从 argv 进（`core/agent-session-services.ts:100-113` 从 CLI 的 unknown flags 灌入），而我们的引擎 argv 是固定的（`PiEngineHost` 的 `guestCommand`，`:390-397`），`PiLaunchOptions` 没有字段、设置里没有行、`PiPreSpawnConfig` 的两张表都没收它。装了 plan-mode / preset / sandbox / ssh 这类示例扩展时，它们的 `--plan` / `--preset` / `--no-sandbox` / `--ssh` **永远是未设值**。
2. **已安装资源包无法更新**（`pi update --extensions` / `pi update <source>`）。`PiPackageService` 只有 `install` / `remove` / `list`，`PiPackagesScreen` 的按钮也只有装 / 卸 / 刷新（`:1036` / `:1151` / `:1039`）。git 源包在 App 里只能"卸了再装"。
3. **`pi auth print-api-key` / `print-bearer-token` 没有对应动作**（低价值，见 §10.3）。pi 里这两条是给外部客户端取凭据用的（`cli/auth-command.ts:18-22`、`main.ts:161-190`）；App 不显示已存密钥。

**外加 1 条不是欠账但要修的一致性问题**：`app.credentials.oauth` 行与 `/login` 的提示语说"本应用没有对应入口"（`PiSettingsRegistry.kt:315-327`、`PiSlashCommands.kt:287`），而终端页自己的说明写的是"输入 pi 回车进入原版 TUI：**订阅登录**、会话导入…都在那边"（`TerminalScreen.kt:72`），且终端页确实带着同一份 agent 目录启动 pi（`PtyLauncher.kt:396-404`）。两处说法互相矛盾 —— 能力是有的（走终端页），文案各说各话。

**三条"pi 有、但 pi 自己的 RPC 够不着"**（不是欠账，属协议边界，见 §5.4）：`/tree` 跳转 + 分支摘要（`navigateTree`）、会话标签的**写**、`/reload`。它们在 pi 里只有 L2/L3 入口（`modes/rpc/rpc-types.ts` 里 `navigate` / `label` / `reload` 三个词**一次都没出现**）。

**pi 没有的东西我们一件也不用补**：`pi doctor` **不存在**（全 `packages/coding-agent` 无 `doctor` 符号，只有 `DiagnosticsScreen`/`collectSettingsDiagnostics` 这种设置诊断）；`--dangerously-*` **不存在**（全仓只有 SDK 内部的 `dangerouslyAllowBrowser`，`packages/ai/src/api/*.ts`，与 CLI 无关）。

---

## 2. CLI 参数全量 —— `cli/args.ts` 逐 flag

pi 的 `parseArgs` 一共认 **44 项**（含 `--`、`@file`、位置消息、unknown flag 三条兜底分支）。下表顺序与 `args.ts:79-246` 一致。

`pi 证据` 列是该 flag 的 parse 行；「做什么」的语义行写在理由里。**状态**取值：`已接` / `有意钉死` / `手机不可用` / `欠账` / `TUI-only`。

| flag（args.ts） | 做什么（pi 证据） | App 对应物（符号） | 状态 | 理由 |
|---|---|---|---|---|
| `--help` / `-h` `:91` | 打印帮助并退出；**顺带列出扩展注册的 flag**（`main.ts:853-860`） | 无对应行；`app.runtime.piVersion` 只回答版本 | 有意钉死 | 终端页可随时 `pi --help`；App 里没有"文本帮助"这种面 |
| `--version` / `-v` `:93` | 打印 `VERSION`（`main.ts:615-618`） | `app.runtime.piVersion` 行 | 已接 | 行值即 pi 版本 |
| `--mode <text\|json\|rpc>` `:95` | 选输出模式（`main.ts:111-122`） | `PiEngineHost` 以 `--mode rpc` 或 `rpc-entry.js` 启动（`:391-392`）；终端页由用户自己敲 | 已接 | 两种模式都用到，无需再暴露 |
| `--continue` / `-c` `:100` | 续接本 cwd 最近的会话（`SessionManager.continueRecent`，`main.ts:427-429`） | `app.sessions.resumeLast` + `PiSessionStore.mostRecentForResume`（`session-manager.ts:636-653` 的移植） | 已接 | 启用了就 `switch_session` |
| `--resume` / `-r` `:102` | 打开会话选择器（TUI） | `SessionsScreen` + `switch_session` | 已接 | 选择器由 App 自绘 |
| `--provider <name>` `:104` | 指定厂商（`main.ts:477-512`） | `defaultProvider` 行 | 已接 | 同一语义；`set_model` 可运行时改 |
| `--model <pattern>` `:106` | 模型 pattern / `provider/id` / `id:thinking`（`resolveCliModel`，`main.ts:464-486`） | `defaultModel` 行 + `set_model` | 已接 | `:thinking` 简写由 App 的两行分别承担 |
| `--api-key <key>` `:108` | 一次性明文覆盖（`setRuntimeApiKey`，`main.ts:806-815`） | 凭证页写 `auth.json`（`PiCredentialService`） | 有意钉死 | 避免密钥进 argv；持久形式更对 |
| `--system-prompt <text>` `:110` | 替换系统提示（`resource-loader.ts:526`） | `app.runtime.systemPrompt` → `PiLaunchOptions.systemPrompt` | 已接 | 收录在 `APP_EXPOSED_PRE_SPAWN` |
| `--append-system-prompt` `:112` | 追加（可重复，`resource-loader.ts:532-534`） | `app.runtime.appendSystemPrompt` | 已接 | 同上 |
| `--name` / `-n` `:115` | 会话显示名（`normalizeSessionName`，`main.ts:690-697`） | `set_session_name` → `PiSessionViewModel.setSessionName` | 已接 | RPC 有等价命令，启动参数是重复入口 |
| `--no-session` `:121` | 临时会话（`SessionManager.inMemory`，`main.ts:359-361`） | 无 | 有意钉死 | 会话列表 / 续接 / 导出全建立在会话文件上 |
| `--session <path\|id>` `:123` | 指定会话文件或 ID 前缀（`resolveSessionPath`，`main.ts:252-278`） | `switch_session` + `SessionsScreen` | 已接 | 运行时可做，不必启动参数 |
| `--session-id <id>` `:125` | 精确 ID（不存在则按该 ID 新建，`main.ts:431-443`） | 无 | 手机不可用 | 只有外部编排器需要钉 ID；App 自己 `new_session` |
| `--fork <path\|id>` `:127` | 从某会话 fork 成新文件（`SessionManager.forkFrom`，`main.ts:363-384`） | `fork` / `clone` RPC | 已接 | `SessionTreeScreen` 的「分叉」 |
| `--session-dir <dir>` `:129` | 会话存储目录（`main.ts:670-676`） | 与 `PI_CODING_AGENT_SESSION_DIR` 一起**钉死**（`PiPreSpawnConfig.kt:355-357`） | 有意钉死 | 设定后设置里的 `sessionDir` 永远读不到，会变成两个真相 |
| `--models <patterns>` `:131` | Ctrl+P 循环用的模型作用域（`main.ts:788-792`） | `enabledModels` 行（通配符编辑） | 已接 | CLI 值会盖过设置值，故只留设置 |
| `--no-tools` / `-nt` `:133` | 关掉**全部**工具（`sdk.ts:258-262`） | 无直接等价物；`defaultTools`（空数组）只关内置 | 有意钉死（存疑，见 §14） | 手机上"全关"的意图由 `defaultTools` 覆盖大半 |
| `--no-builtin-tools` / `-nbt` `:135` | 关内置、留扩展工具（`sdk.ts:258-262`） | `defaultTools` 行 | 已接（近似） | 同一开关的设置形式 |
| `--tools` / `-t <list>` `:137` | 允许清单，**CLI 值赢过 `defaultTools`**（`sdk.ts:258-262`） | `defaultTools` 行 | 已接 | 只留设置，避免第二个真相 |
| `--exclude-tools` / `-xt` `:142` | 拒绝清单（`sdk.ts:260-262`） | 无 | 有意钉死 | 工具全集=内置+运行时扩展，设置页给不出准确清单（已记录理由） |
| `--thinking <level>` `:147` | 启动思考等级（`main.ts:511-513`、`sdk.ts:240-251`） | `defaultThinkingLevel` / `modelThinkingLevels` / `set_thinking_level` | 已接 | 启动与运行时都有 |
| `--print` / `-p` `:157` | 非交互一问一答（`main.ts:118-120`、`runPrintMode`） | 无 | 有意钉死 | App 的对话页就是交互模式；print 是给脚本的 |
| `--export <file>` `:164` | 导出会话为 HTML（`exportFromFile`，`main.ts:620-632`） | `export_html` + `/export` + `exportSession`（含 `.jsonl` 自写） | 已接 | 两种后缀都做 |
| `--extension` / `-e <path>` `:166` | 额外扩展路径（`main.ts:709`、`:762-766`） | `extensions` 行 | 已接 | 设置里的数组是持久形式 |
| `--no-extensions` / `-ne` `:169` | 关扩展发现（`main.ts:767`） | 无 | 有意钉死 | 关掉会顺带停掉 App 自己的 `android_*` 设备扩展 |
| `--skill <path>` `:171` | 额外技能路径（`main.ts:710`） | `skills` 行 | 已接 | 同上形状 |
| `--prompt-template <path>` `:174` | 额外模板路径（`main.ts:711`） | `prompts` 行 | 已接 | 同上形状 |
| `--theme <path>` `:177` | 额外主题路径（`main.ts:712`） | `themes` 行 | 已接 | 同上形状 |
| `--use-theme <name>` `:180` | 本次运行的初始主题（`main.ts:662-663`） | `theme` 行 | 已接 | 一次性覆盖的设置形式 |
| `--no-skills` / `-ns` `:188` | 关技能发现（`main.ts:768`） | 无 | 有意钉死 | App 的技能屏读同一批目录，会自相矛盾 |
| `--no-prompt-templates` / `-np` `:190` | 关模板发现（`main.ts:769`） | 无 | 有意钉死 | 同上 |
| `--no-themes` `:192` | 关主题发现（`main.ts:770`） | 无 | 有意钉死 | 同上 |
| `--no-context-files` / `-nc` `:194` | 关 `AGENTS.md`/`CLAUDE.md` 发现（`resource-loader.ts:516`） | `app.runtime.noContextFiles` | 已接 | pi 无对应设置键，故走进程参数 |
| `--list-models [search]` `:196` | 列可用模型（`cli/list-models.ts`，`main.ts:862-867`） | `get_available_models` + `PiModelsScreen` | 已接 | 同一数据源（`modelRuntime.getAvailableSnapshot`） |
| `--tui-mode` `:203` | TUI 布局（`main.ts:943`） | 无 | TUI-only | 引擎跑 rpc 模式 |
| `--verbose` `:217` | 强制详细启动（`main.ts:942`） | 无 | TUI-only | 只有 `InteractiveMode` 读它 |
| `--approve` / `-a` `:219` | 本次信任项目文件（`main.ts:725-744`） | `defaultProjectTrust` 行 + 包命令的 `TrustPass.Approve`（`--approve`） | 已接 | 两种通道都有 |
| `--no-approve` / `-na` `:221` | 本次忽略项目文件 | 同上（`TrustPass` 的 `--no-approve` 分支） | 已接 | — |
| `--offline` `:223` | 关启动网络（并置 `PI_SKIP_VERSION_CHECK`，`main.ts:565-568`） | `app.runtime.offline` | 已接 | 收录在 `APP_EXPOSED_PRE_SPAWN` |
| `--` `:82` | 选项结束，其后当消息/文件 | — | 不适用 | RPC 模式没有 argv 消息 |
| `@file` `:225` | 把文件内容/图片内联进初始消息（`cli/file-processor.ts:24-...`） | 图片走 `prompt.images`（`AttachmentBudget`）；文本走 `@路径` 提及（`PiFileMentions`） | 不适用 | **pi 自己在 rpc 模式拒绝 `@file`**（`main.ts:640-643`）；TUI 的 `@` 补全也只是插入路径文本，无服务端展开（`packages/tui/src/autocomplete.ts:412-477`） |
| 位置消息 `:243` | 初始消息（`buildInitialMessage`） | `prompt` | 已接 | — |
| 未知 `--flag` `:227-240` | **扩展注册的 flag**（见下） | **无** | **欠账** | §10.1 |

**`--flag` 之外还有一条容易漏的：`args.ts:323` 的帮助文本明说"扩展可以注册额外 flag（例如 plan-mode 扩展的 `--plan`）"**，而这条链在 RPC 模式是通的：`parseArgs` 收进 `unknownFlags` → `main.ts:737` → `applyExtensionFlagValues`（`agent-session-services.ts:82-124`）写进 `runtime.flagValues` → 扩展用 `ctx.getFlag(name)` 读（`core/extensions/types.ts:1345`、`loader.ts:358-362`）。注册侧是 `ctx.registerFlag`（`types.ts:1329-1345`、`loader.ts:321-336`）。**链的另一端（我们）是空的** —— 见 §10.1。

### 2.1 环境变量面（题面未要求逐条，但与 flag 表互为镜像）

`PiPreSpawnConfig.kt` 已经把 pi 的 env 面分成三张表（App 发出的 5 条 / 由设置键覆盖的 14 条 / 有意不发的 20 条），本次逐条复核**没发现该表漏项**：`args.ts:387-435` 的环境变量清单与 `docs/environment-variables.md` 里被引用的那些，都能在 `COVERED_BY_PI_SETTING` 或 `NOT_EXPOSED_PRE_SPAWN` 里找到落点。两个补充观察：

- `PI_TELEMETRY` 覆盖 `enableInstallTelemetry`（`core/telemetry.ts:8-14`）—— 但它的**唯一消费者**是 provider 归因请求头（`core/provider-attribution.ts:38-40`，OpenRouter/NVIDIA/Cloudflare 的 header），0.85.1 里**没有安装/更新上报的网络调用**。我们的 `enableInstallTelemetry` 行因此是 1:1 的、也只是那一个作用。
- `PI_SKIP_VERSION_CHECK` 由 App 钉成 `1`（`PiEngineHost.kt:424`）与 pi 的 `--offline` 行为一致（`main.ts:565-568`）；pi 的版本检查只在交互模式跑（`modes/interactive/interactive-mode.ts:1047`），RPC 模式本来就不会查。

---

## 3. CLI 子命令全量 —— `main.ts` 的分派 + `package-manager-cli.ts` + `auth-command.ts`

pi 的"子命令"是 `main.ts` 在 `parseArgs` **之前**用 `args[0]` 特判的，一共 6 组：

| 子命令 | pi 入口 | 选项 | App 对应物 | 状态 | 理由 |
|---|---|---|---|---|---|
| `install <source>` | `main.ts:586-597` → `package-manager-cli.ts:864-957` | `-l/--local`、`-a/--approve`、`-na/--no-approve` | `PiPackageService.install` + `PiPackagesHost.install` + `PiPackagesScreen` 安装卡（`:1036`） | 已接 | 源形态（npm/git/local）逐条移植（`PiPackageSource`） |
| `remove` / `uninstall <source>` | `:959-968`（`uninstall` 在 `:378-379` 归一） | `-l`、`-a`、`-na` | `PiPackageService.remove` + `PiPackagesScreen` 卸载（`:1151`） | 已接 | — |
| `list` | `:970-1004` | `-a`、`-na` | `PiPackageService.list` + `PiListOutput` / `PiPackagesScreen` | 已接 | 用户/项目两组、`(filtered)` 后缀都读 |
| `update [source\|self\|pi]` | `:1006-1094`；参数解析 `:375-573` | `--self`、`--extensions`、`--models`、`--all`、`--extension <src>`、`--force`、`-a`、`-na` | **只覆盖 `--models` 的等效行为**（RPC 启动时 pi 自己刷新目录，`main.ts:921-928`） | **部分欠账** | §10.2 |
| `config [-l]` | `main.ts:599-601` → `package-manager-cli.ts:791-862` | `-l/--local`、`-a`、`-na` | 资源过滤编辑器：`PiPackageFilterStore` + `PiPackageFilters` + `PiPackagesScreen.FilterEditor`（`:1172-1237`，四类 `extensions/skills/prompts/themes`） | 已接 | pi 的 config 是**交互式 TUI 复选框**，我们编辑同一批 `+`/`-`/`!` 模式串（`config-selector.ts:605-627`）；无 `autoload` 丢失（`PiPackageFilters.normalize`） |
| `auth check` | `main.ts:132-208`；参数 `cli/auth-command.ts:48-118` | `--provider`、`--model`、`--json`、`--credentials`、`--no-refresh` | 凭证页的"检测并扫描模型"（`PiCredentialScreen` → `PiModelScanner`）+ 厂商就绪展示（`PiCredentialService` 的 `credentialPresent` / `get_available_models` 的 auth 过滤） | 已接（机制不同） | pi 是"有没有可用凭据"，我们是网络探测 + 只读 `auth.json`；结论等价、更强 |
| `auth print-api-key` | `main.ts:161-173` → `cli/credential-print.ts` | `--provider` / `--model` | 无 | **欠账（低）** | §10.3 |
| `auth print-bearer-token` | 同上（会刷新过期 OAuth） | `--provider`/`--model`/`--min-expiry` | 无 | **欠账（低）** | 同上；OAuth 刷新本身由 pi 在引擎内做，我们只是不能取出来 |

**不存在的子命令**（避免以后照文档补）：`pi doctor`、`pi login`、`pi logout`、`pi upgrade`、`pi clean`、`pi mcp`。前两条题面点名过 —— `doctor` 全 `packages/coding-agent` 无该符号；`login` 是**斜杠命令**不是子命令（`core/slash-commands.ts:36`，实现 `interactive-mode.ts:5485`）。

**实验性子命令 `pi experimental server|client` 不算能力面**：它们排在被发布产物之外（`packages/coding-agent/package.json:31-34` 的 `files` 明确排除 `dist/experimental`、`dist/cli/experimental`），也就是**随包发出的 `dist` 里没有**；`packages/protocol`（`PROTOCOL_VERSION = 8`，`protocol.ts:5`，`hello`/`request`/`cancel` 信封 `:29-62`）是这套实验服务的协议，与我们 RPC 的那套（`rpc-types.ts` 的 stdin JSON、`type: "response"`）**不是一回事**。所以 §4 只审 RPC。

---

## 4. RPC 协议全量

### 4.1 命令 33/33

pi 的 `RpcCommand` 联合体在 `modes/rpc/rpc-types.ts:20-74`，**33 个** `type` 字面量；实现在 `rpc-mode.ts:386-720` 的 `switch`。

| 面 | pi 命令（`rpc-types.ts` / `rpc-mode.ts`） | App builder（`Commands.kt`） | App 发送方 | 状态 |
|---|---|---|---|---|
| 提示 | `prompt` `:22`/`:394-416` | `PiCommands.prompt` | `PiEngineSession.send/prompt`、`PiSessionViewModel` 模板/技能路径 | 已接 |
| | `steer` `:23`/`:418-421` | `PiCommands.steer` | `PiSessionViewModel`（压缩中那条窗口） | 已接 |
| | `follow_up` `:24`/`:423-426` | `PiCommands.followUp` | `PiSessionViewModel` | 已接 |
| | `abort` `:25`/`:428-431` | `abort` | `PiEngineSession.stopAndDrainQueue` | 已接 |
| | `clear_queue` `:26`/`:433-435` | `clearQueue` | `PiEngineSession`（并回填草稿） | 已接 |
| | `new_session` `:27`/`:437-444` | `newSession` | `PiSessionViewModel.newSession` | 已接 |
| 状态 | `get_state` `:30`/`:450-466` | `getState` | `PiEngineSession` 就绪探针 / `PiSessionViewModel.refreshState` | 已接 |
| 模型 | `set_model` `:33`/`:472-480` | `setModel` | `PiSessionViewModel.setModel` | 已接 |
| | `cycle_model` `:34`/`:482-488` | `cycleModel` | **无调用者**（全树仅 `PiEngineApi.kt:152` 定义） | **从不发送**（有意，见 §13） |
| | `get_available_models` `:35`/`:490-493` | `getAvailableModels` | `PiSessionViewModel.refreshModels` | 已接 |
| 思考 | `set_thinking_level` `:38`/`:499-502` | `setThinkingLevel` | 有 | 已接 |
| | `cycle_thinking_level` `:39`/`:504-510` | `cycleThinkingLevel` | 有 | 已接 |
| | `get_available_thinking_levels` `:40`/`:512-515` | 有 | 有 | 已接 |
| 队列 | `set_steering_mode` `:43`/`:521-524` | 有 | 有 | 已接 |
| | `set_follow_up_mode` `:44`/`:526-529` | 有 | 有 | 已接 |
| 压缩 | `compact(customInstructions)` `:47`/`:535-538` | `compact` | `PiSessionViewModel.compact` | 已接 |
| | `set_auto_compaction` `:48`/`:540-543` | 有 | 有 | 已接 |
| 重试 | `set_auto_retry` `:51`/`:549-552` | 有 | 有 | 已接 |
| | `abort_retry` `:52`/`:554-557` | 有 | 有 | 已接 |
| bash | `bash` `:55`/`:563-584` | `bash` | `PiSessionViewModel.runBash` | 已接 |
| | `abort_bash` `:56`/`:586-589` | `abortBash` | `ChatScreen` / 紧急停止 | 已接 |
| 会话 | `get_session_stats` `:59`/`:595-598` | 有 | 有 | 已接 |
| | `export_html` `:60`/`:600-603` | `exportHtml` | `exportSession` | 已接 |
| | `switch_session` `:61`/`:605-611` | `switchSession` | `switchToSession` / 导入 | 已接 |
| | `fork` `:62`/`:613-619` | `fork` | `SessionTreeScreen` 分叉 | 已接 |
| | `clone` `:63`/`:621-631` | `clone` | `/clone` | 已接 |
| | `get_fork_messages` `:64`/`:633-636` | 有 | `refreshForkMessages` | 已接 |
| | `get_entries(since)` `:65`/`:638-649` | `getEntries` | 会话重放 / 树 / 导出 | 已接 |
| | `get_tree` `:66`/`:651-654` | `getTree` | `refreshTree` | 已接 |
| | `get_last_assistant_text` `:67`/`:656-659` | 有 | `/copy` | 已接 |
| | `set_session_name` `:68`/`:661-668` | 有 | `/name` | 已接 |
| 消息 | `get_messages` `:71`/`:674-676` | `getMessages` | **无调用者** | **从不发送**（有意：全量在 `session.messages`，压缩后与记录不同，用它替换会丢历史） |
| 命令 | `get_commands` `:74`/`:682-713` | `getCommands` | 命令面板 | 已接 |

**结论：33/33 有 builder，31/33 有真实发送方；两条不发的都有书面理由。** 与 `docs/rpc-coverage.md` 的"32/33 有入口"说法**不一致**：那份文档给 `cycle_model` 标了 UI（`ChatScreen.kt:659`），但当前树的 UI 已按用户裁决删掉该入口（`Commands.kt:133-141` 的 KDoc 记着），`grep "cycleModel(" app/src/main` 只有 `PiEngineApi.kt:152` 一处。**以代码为准：`cycle_model` 与 `get_messages` 都是"从不发送"。**

### 4.2 事件：26 个 stdout 记录 + 12 个 delta

pi 侧的上线集合 = `session.subscribe` 的事件（`AgentSessionEvent`，`core/agent-session.ts:144-185`；其 `agent` 部分是 `AgentEvent`，`packages/agent/src/types.ts:431-446`）+ `toJsonEvent` 的加工（`modes/json-event.ts:46-61`，只剥掉 `partial`/累计 `message`）+ 扩展错误（`rpc-mode.ts:348-350`）+ 扩展 UI 请求（`rpc-mode.ts:129`）+ `response` 信封。

| 事件 | App 解析（`Events.kt`） | App 投影/消费 | 状态 |
|---|---|---|---|
| `agent_start` / `agent_end` / `agent_settled` | `:439-441` | `Transcript.kt:900-912`；`PiSessionViewModel` 在 `agent_settled` 上刷新 state/commands/stats | 已接 |
| `turn_start` / `turn_end` | `:444-447` | `turn_end` 只用于跨天分隔（`Transcript.kt:928-932`）；`turn_start` 无投影（pi 不带 turn 序号） | 已接 |
| `message_start` / `message_update` / `message_end` | `:449-476`、`:592-642` | `Transcript.kt:1150-1195`、`:1408+` | 已接 |
| `tool_execution_start/update/end` | `:478-500` | `Transcript.kt:914-916`（update 有 200ms 节流） | 已接 |
| `queue_update` | `:507-510` | `PiSessionViewModel.onEvent` 队列条数 | 已接 |
| `compaction_start` / `compaction_end` | `:512-521` | `Transcript.kt:1542-1604`（含 `result.summary`） | 已接 |
| `auto_retry_start` / `auto_retry_end` | `:523-534` | `Transcript.kt:934-957` | 已接 |
| `summarization_retry_scheduled` / `_attempt_start` / `_finished` | `:536-548` | `Transcript.kt:983-1053` | 已接 |
| `bash_execution_update` | `:502-505` | `PiSessionViewModel`（有界累积 + 节流） | 已接 |
| `entry_appended` | `:576-583` | `Transcript.kt:977` 走 `onEntry` | 已接 |
| `session_info_changed` / `thinking_level_changed` | `:585-586` | `PiSessionViewModel` | 已接 |
| `extension_error` | `:570-574` | 转写行 + 通知（带 `extensionPath`） | 已接 |
| `extension_ui_request`（9 个 method） | `:550-568` | 阻塞四类进对话框；`notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text` 进 chrome | 已接（`setStatus` 只存不画，见 §13） |
| `response` | `:431-437` | `PiResponses` 全 reader | 已接 |
| 未知类型 | `:589` → `PiEvent.Unknown` | 转写一行"当前版本不认识" | 已接 |

**12 个 `assistantMessageEvent` delta**（`packages/ai/src/types.ts:546-562`）解析在 `Events.kt:592-642`；投影在 `Transcript.kt:1414-1570`。四种 delta **解析但不投影**（都无信息量）：`start`、`text_start`、`thinking_start`、`done`（`done.reason` 与 `message_end.stopReason` 重复）。**没有"漏了一整项"的事件**。

**RPC 模式下永远收不到的扩展专属事件**（写在这里防止以后当成缺口）：`session_start`、`input`、`tool_call`、`tool_result`、`user_bash`、`model_select`、`thinking_level_select`、`context`、`before_provider_request` 等 —— 它们是 `ExtensionEvent`（`core/extensions/types.ts:1086-1113`）**只发给扩展**，`AgentSession._emitExtensionEvent` 另走一条路，不进 `session.subscribe`。这也是 App 必须在 `agent_settled` 时重读 `get_state` 的原因。

---

## 5. 会话生命周期

### 5.1 pi 的入口矩阵

| 能力 | pi 的 CLI | pi 的 RPC | pi 的斜杠/TUI | App | 状态 |
|---|---|---|---|---|---|
| 新建 | 默认行为 | `new_session(parentSession?)` `rpc-types.ts:27` | `/new`（`interactive-mode.ts:3063`） | `/new`、会话页、`PiSessionViewModel.newSession` | 已接 |
| 续接最近 | `--continue` `args.ts:100` | — | — | `app.sessions.resumeLast` + `mostRecentForResume` | 已接 |
| 恢复指定 | `--resume`、`--session` `:102`/`:123` | `switch_session` `:61` | `/resume`（`:3094`） | `SessionsScreen` → `switchToSession` | 已接 |
| 精确 ID | `--session-id` `:125` | — | — | 无 | 手机不可用 |
| fork（新文件） | `--fork` `:127` | `fork(entryId)` `:62` | `/fork` | 树屏「分叉」 | 已接 |
| clone | — | `clone` `:63` | `/clone` | `/clone` | 已接 |
| **树内跳转** | — | **无命令** | `/tree`（`:3042` → `session.navigateTree`，`agent-session.ts:3136`） | 无（只能 fork 新文件） | **pi RPC 够不着**（§5.4） |
| **分支摘要** | — | **无命令**（只在 `navigateTree({summarize:true})` 里，`agent-session.ts:3297-3309`） | `/tree` 摘要提示（`branchSummary.skipPrompt` `settings-manager.ts:32`） | 只能**渲染**已有摘要（`BranchSummaryBlock.kt`），不能生成 | **pi RPC 够不着** |
| 压缩 | — | `compact(customInstructions?)` `:47` | `/compact`（`:3068`） | `/compact` + `app.compaction.runNow` | 已接 |
| 导入 | — | **无命令**（用 `switch_session` 代替） | `/import`（`:2997`/`:6107-6119`） | `SessionImport` + `importSession`（选文件→拷进会话目录→`switch_session`） | 已接（有 1 处差异） |
| 导出 | `--export` `:164` | `export_html(outputPath?)` `:60` | `/export [path]`（`:2992`/`:6061-6076`，`.jsonl` 走 `exportToJsonl`） | `export_html` + `SessionExportNaming`（`.jsonl` 自写） | 已接 |
| 列表/分组 | — | — | `/resume` 选择器（`cli/session-picker.ts`、`SessionManager.list/listAll` `session-manager.ts:1670-1687`） | `PiSessionStore`（按 cwd 分组、mtime 排序、`mostRecentForResume`） | 已接 |
| 删除 | — | — | 选择器 Ctrl+D（`docs/sessions.md:48`） | `PiSessionStore.delete` + 长按确认（`SessionsScreen`） | 已接 |
| 重命名 | `--name` `:115` | `set_session_name` `:68` | `/name` | `/name` | 已接 |
| 标签（写） | — | **无命令** | `/tree` 内标注（`interactive-mode.ts:5332`）；扩展 `pi.setLabel`（`agent-session.ts:2627-2630`） | 只**读**（`SessionEntries` 的 `Label`/`label`/`labelTimestamp`）+ 五种筛选 | **pi RPC 够不着** |

### 5.2 会话文件格式面

`SessionManager`（`session-manager.ts:856+`）暴露的条目类型：`message`、`thinking_level_change`、`model_change`、`compaction`、`branch_summary`、`custom`、`label`、`session_info`、`custom_message`（`:46-156`）。App 侧 `SessionEntries.kt` 逐类型建模（`:115-253`），`Transcript.kt` 的 `onEntry` 逐条投影 —— 包括 `label` 与 `branch_summary`。**这一面没有缺项。**

### 5.3 两个已知的语义差异（都不是我们的错）

1. **导入没有 `cwdOverride`**：pi 的 TUI 导入在会话 cwd 不存在时会问"在当前 cwd 继续"（`main.ts:678-689`、`formatMissingSessionCwdPrompt`、`interactive-mode.ts:2545`），而 `switch_session` 的签名里没有这个参数（`rpc-types.ts:61`，对比 `agent-session-runtime.ts:196-224` 的 `options.cwdOverride`）。App 的选择是**如实报错**并说明没有 `cwdOverride` 可用（`SessionImport.kt:154-155`、`PiSessionViewModel.kt:3996-3998`）。
2. **`get_entries` 的 `since` 游标**三条调用点都不传（全量重放）。这是性能选择，不是协议缺口。

### 5.4 pi RPC 够不着的那三条（**不是欠账**，但要在界面上说真话）

`/tree` 跳转、分支摘要、会话标签的写 —— 三条都只有 L2/L3 入口，`modes/rpc/rpc-types.ts` 里 `navigate`/`label`/`reload` **零出现**（本次 grep 逐词确认）。App 目前的处理：

- 树屏的按钮是**分叉**而不是跳转，并且有一句正面说明（`SessionTreeScreen.kt:328-358`）。**这是诚实且正确的处理**，本次审查不主张改。
- 会话标签：只读展示 + 五种筛选齐全（`TreeFilter`，对应 `components/tree-selector.ts:340-395`）。缺的只是"写"。
- `/reload`：`/reload` 是 TUI 内建（`interactive-mode.ts:3074`），RPC 无命令；扩展侧的 `ctx.reload()` 才可达（`rpc-mode.ts:341-343`）。App 用**重启引擎**覆盖（`EngineRestartCoordinator` / `PiSessionViewModel.restartEngine`）。

**一处待确认**：扩展命令里调 `ctx.reload()` 之后，App 的命令面板不会自动刷新（只在 `agent_settled` 刷，`PiSessionViewModel.onEvent` 的 `AgentSettled` 分支）—— 见 §14。

---

## 6. 模型与认证

| 项 | pi 证据 | App 符号 | 状态 | 理由 |
|---|---|---|---|---|
| `auth.json` 位置与 `0600` | `auth-storage.ts:52`（`~/.pi/agent/auth.json`）、`:25`（`mode: 0o600`，**只在创建时**）、`:59`（父目录 `0700`）、`:106`/`:187`（每次写入沿用） | `PiConfigFiles.authFile` + `PiConfigFiles` 的"只收紧不放宽"模式设置（`:146-165`）、`PiAuthStorage` | 已接 | 写入走 pi 自己的锁语义（`AuthStorage.withLock`，`:96-114`）；App 是"读-改-整份重写"的镜像实现 |
| auth 读写锁 | `auth-storage.ts:69-201`（`lockfile` + stale 30s） | `PiSettingsLock` / `TrustRepository` 的同类锁 | 已接 | 同一套"锁文件 + 过期"策略 |
| OAuth 登录 | **只有 TUI**：`/login`（`slash-commands.ts:36`、`interactive-mode.ts:3052-3060`、`handleLoginCommand:5485`、`showOAuthSelector:5619`）；RPC 无命令（`rpc-types.ts` 无 `login`/`oauth`） | 无原生入口；**终端页可跑**（`TerminalScreen.kt:72`、`PtyLauncher` 带同一 agent 目录 `:396-404`） | TUI-only（有逃生口） | 见 §12 与 §1 的一致性问题 |
| OAuth 登出 | `ModelRuntime.logout`（`model-runtime.ts:690-695`）、`/logout`（`interactive-mode.ts:3058`） | 无原生入口；终端页可跑 | TUI-only | 同上 |
| OAuth 刷新 | 引擎内自动（`auth-storage` 的 revision 检查 `:341-343`） | 无需界面 | 已接 | 刷新是 pi 的事 |
| 内置模型表 | `packages/ai/src/models.generated.ts`（生成物，`packages/ai/scripts/generate-models.ts`） | 经 `get_available_models` 拿 | 已接 | 不复制 pi 的表 |
| `models.json`（自定义厂商） | `core/model-config.ts`、`provider-composer.ts` | `PiModelsScreen`、`PiModelsMerge`、`PiCredentialService`、`PiProviderPresets`（十个厂商的 `baseUrl`/`api` 逐个标注 pi 的 provider 文件行号） | 已接 | 兼容端点（OpenAI/Anthropic/Google/自定义）都能建 |
| `models-store.json`（目录缓存） | `core/model-runtime.ts:180`、`packages/ai/src/models-store.ts` | `PiModelCatalog`（**只读**，用于取能力元数据）；写入由 pi 做 | 已接 | pi 在 RPC 启动时后台刷新（`main.ts:921-928`），故 `pi update --models` 的能力等效存在 |
| provider/模型选择 | `set_model` `rpc-types.ts:33`、`resolveCliModel` `core/model-resolver.ts` | `PiSessionViewModel.setModel` + 模型选择器 | 已接 | — |
| 作用域（`--models` / `enabledModels` 的 pattern） | `main.ts:788-792`、`settings-manager.ts:139` | `enabledModels` 行（glob 编辑） | 已接 | — |
| 逐模型思考等级 | `settings-manager.ts:111`、`sdk.ts:236-246` | `modelThinkingLevels` 行 | 已接 | — |
| 思考预算 | `settings-manager.ts:143`、`sdk.ts:369` | `thinkingBudgets` 行 | 已接 | — |
| 认证检查 | `main.ts:175-201`（ready / not_ready / invalid） | 凭证页扫描 + 厂商就绪展示 | 已接（机制不同） | 见 §3 |
| 取凭据给外部客户端 | `cli/auth-command.ts:18-22` | **无** | 欠账（低） | §10.3 |
| 运行时 API key 覆盖 | `--api-key` `args.ts:108`、`model-runtime.ts:91` 的 `"setRuntimeApiKey"` | 无 | 有意钉死 | 避免明文进 argv |
| transport（`transport` 键） | `settings-manager.ts:112`、`ai/src/types.ts` 的 Transport | `transport` 行 | 已接 | — |

---

## 7. 包 / 技能 / 提示词 / 主题 / 扩展的"安装与管理面"

| 用户可见动作 | pi 证据 | App 符号 | 状态 |
|---|---|---|---|
| 装包 | `package-manager-cli.ts:954-957`；源解析 `package-manager.ts:1446-1471` | `PiPackageService.install`、`PiPackageSource`、`PiPackagesScreen` 安装卡 | 已接 |
| 卸包 | `:959-968` | `PiPackageService.remove` | 已接 |
| 列包（用户/项目两组、`(filtered)`） | `:970-1004` | `PiPackageService.list`、`PiListOutput` | 已接 |
| 更新包 | `:1006-1021`（`updateTargetIncludesExtensions`）、`package-manager.ts:1059` | 无 | **欠账** §10.2 |
| 检查有无更新 | `package-manager.ts:1186-1250`；**只有 TUI 用**（`interactive-mode.ts:1054`/`:1143`） | 无 | TUI-only（App 也没有"有更新"提示） |
| 启用/停用包内资源 | `config-selector.ts:605-627`（`+`/`-`/`!`）+ `package-manager.ts:708-726` | `PiPackageFilterStore`、`PiPackageFilters`、`FilterEditor`（四类，逐模式串可增删） | 已接 |
| 作用域 global/project | `-l/--local` `package-manager-cli.ts:409-416`；`package-manager.ts:977-1003` | `PiPackageScope`、`PiPackageFilterStore.read()` 双文档、`PiPackagesScreen` 作用域切换 | 已接 |
| 技能发现/加载 | `core/skills.ts:168`/`:409`（只发现，无安装命令） | `skills` 行 + 技能扫（`PiResourceDiscovery`） | 已接 |
| 技能 → `/skill:name` 命令 | `settings-manager.ts:136` `enableSkillCommands`；TUI 侧 `interactive-mode.ts:716` | `enableSkillCommands` 行 + `piCommandPalette(skillCommandsEnabled=...)` | 已接（且补上了 pi RPC 不传该开关的缺口） |
| 提示模板发现 | `core/prompt-templates.ts`、`resource-loader.ts` | `prompts` 行 | 已接 |
| 主题发现/选择 | `resource-loader.ts`、`themes` 键 `settings-manager.ts:135`、`use-theme` | `themes` 行、`theme` 行、`PiThemeFiles` | 已接 |
| 扩展发现 | `resource-loader.ts`、`extensions` 键 `:132` | `extensions` 行 + `PiResourceDiscovery` | 已接 |
| 扩展加载失败可见 | `main.ts:782-785` 汇总为 error 诊断并 `exit(1)`（`:901-906`） | 引擎退出原因 + 诊断报告（`DiagnosticsReport` 带 stderr 尾部） | 已接 |
| 资源 TUI 扫描（用了 TUI-only 面的扩展） | —（这是 App 自己的判断） | `TuiOnlyScan.kt`（扫 `custom(`/`setFooter(`/…/`mode === "tui"`） | 已接（pi 无对应物，App 自建） |

---

## 8. 信任与安全

| 项 | pi 证据 | App 符号 | 状态 |
|---|---|---|---|
| 项目信任存档 | `core/trust-manager.ts:209-245`（`ProjectTrustStore.get/set` → `trust.json`） | `TrustFile`、`TrustRepository` | 已接 |
| 默认策略 `ask`/`always`/`never` | `settings-manager.ts:124`、`project-trust.ts:47-84` | `defaultProjectTrust` 行 + `ProjectTrust` 纯逻辑（`ProjectScreen.kt:245-264`） | 已接 |
| 一次性覆盖 `--approve`/`--no-approve` | `args.ts:219-222`、`project-trust.ts:17`/`:47-49` | `TrustPass.Approve/None`（包命令拼 `--approve`） | 已接 |
| 哪种项目资源需要信任 | `trust-manager.ts:185` `hasTrustRequiringProjectResources` | `ProjectTrust` 的判定复用同一条件 | 已接 |
| 信任交互（TUI 弹窗） | `main.ts:726-760`（`hasUI` 分支）、`cli/project-trust.ts` | `PiProjectTrustPrompt` | 已接 |
| 扩展钩子（工具执行前可拦） | `ToolCallEvent` → `ToolCallEventResult.block/reason/terminate`（`extensions/types.ts:1112`、`:1125-1134`）；另有 `ToolResultEvent`、`UserBashEvent`（`:1110-1113`） | 无需客户端实现（扩展在 pi 进程内） | 已接（L2） |
| 全部扩展钩子 | 28 个 `ExtensionEvent`（`extensions/types.ts:1086-1113`） | — | 无需客户端实现（L2） |
| `--dangerously-*` 之类的危险开关 | **pi 没有**（全仓 `--dangerously` 零命中；`packages/ai` 里的 `dangerouslyAllowBrowser` 是 SDK 选项，与 CLI 无关） | — | pi 没有对应物 → 不做 |
| `PI_OFFLINE` / 网络开关 | `core/telemetry.ts:8-14`、`core/model-runtime.ts:196` | `app.runtime.offline` | 已接 |
| 离线包操作短路 | `package-manager.ts:1187-1189`（`isOfflineModeEnabled`） | 由 pi 负责 | 已接 |

---

## 9. 可观测 / 周边

| 项 | pi 证据 | 手机上有意义吗 | App | 状态 |
|---|---|---|---|---|
| 安装遥测开关 | `core/telemetry.ts:8-14` | 有（但作用只有请求头，见 §2.1） | `enableInstallTelemetry` 行 | 已接 |
| 归因请求头 | `core/provider-attribution.ts:38-72`（OpenRouter `HTTP-Referer`、NVIDIA、Cloudflare UA） | 有（影响厂商计费/路由） | 由 pi 读取，设置 1:1 | 已接 |
| 分析开关 / tracking id | `settings-manager.ts:129-130`、`:1066-1079` —— **写进去但无人消费**（全仓 `getAnalyticsEnabled`/`getTrackingId` 的调用者只有测试） | 无（pi 没实现上报） | 未暴露 | **正确**：不给一个没有作用的开关 |
| 更新检查（"有新版本"） | `utils/version-check.ts:97-98`；只在 TUI 起（`interactive-mode.ts:1047`） | 无（App 自己钉版本） | `app.runtime.piVersion` 行 | TUI-only |
| `/changelog` | `slash-commands.ts:31`；`interactive-mode.ts:3022`；`utils/changelog.ts` | 低 | 无（`PiSettingsRegistry.kt:1367-1372` 记录过删除理由） | TUI-only |
| `collapseChangelog` / `lastChangelogVersion` | `settings-manager.ts:127`/`:107` | 无 | 无 | TUI-only |
| 自更新 `pi update`（self） | `package-manager-cli.ts:1022-1092`（托管安装 / npm / pnpm 三条路） | **有害**：App 钉死 `PI_VERSION = "0.85.1"`（`tools/fetch-runtime.mjs:190`）并按 revision 校验载荷 | 无 | 见 §11 |
| 启动计时 | `core/timings.ts:5`（`PI_TIMING=1`，写 stderr） | 有 | `app.runtime.engineStartup` 行（App 自己测） | 已接（机制不同） |
| 诊断（设置诊断） | `core/settings-diagnostics.ts`；非交互模式写 stderr（`main.ts:896-900`） | 有 | 引擎 stderr 捕获（`PiEngineSession.stderr`）+ `DiagnosticsReport`；设置侧另有 `PiSettingsValidation` | 已接 |
| `pi doctor` | **不存在** | — | `DiagnosticsScreen`/`DiagnosticsReport` 是 App 自建 | pi 没有对应物 → 不做 |
| 分享 `/share` | `slash-commands.ts:27`；`interactive-mode.ts:3002`/`:6152` → `modes/interactive/session-share.ts:46`（Radius，回落 `gh gist create`） | 无（需 `gh`） | `SessionExportDelivery`（导出 + 系统分享） | TUI-only |
| 会话内 `/session` 面板 | `interactive-mode.ts:6216-6222`：`getSessionStats()` + `computeCacheWaste`（`cache-stats.ts:138`）+ `getUsageCostBreakdown`（`usage-totals.ts:37`） | 部分 | 统计 sheet 覆盖 stats/token/费用/命中率，**没有** cache 浪费与按厂商成本拆分 | TUI-only（数据可从 `get_entries` 复算，但入口是内建斜杠命令） |
| 崩溃/退出 | — | 有 | `EngineExitCause`、`DiagnosticsReport` | App 自建 |

---

## 10. 真欠账清单（pi 有、手机可用、我们没接）

### 10.1 扩展注册的 CLI flag 传不进去 —— 严重度：中

**pi 侧（读到的）**：
- 注册：`ctx.registerFlag(name, {type, default?, description?})`（`core/extensions/types.ts:1329-1345`，落地 `core/extensions/loader.ts:321-336`，`default` 在这里生效）。
- 取值：`ctx.getFlag(name)`（`types.ts:1345`，`loader.ts:358-362`）。
- **唯一的值来源是 argv**：`cli/args.ts:227-240` 把未知 `--flag` 收进 `unknownFlags` → `main.ts:737` 传给 `createAgentSessionServices({extensionFlagValues})` → `applyExtensionFlagValues`（`agent-session-services.ts:82-124`）写进 `runtime.flagValues`。运行时的 `ExtensionRunner.setFlagValue`（`runner.ts:536-538`）**全仓无调用者**，即没有第二条输入通道。
- RPC 侧也没有对应命令（`rpc-types.ts` 里 `flag` 零命中）。
- pi 自己的帮助文本就举这个例子：`cli/args.ts:323`"Extensions can register additional flags (e.g., `--plan` from plan-mode extension)"；示例扩展确实在用：`examples/extensions/plan-mode/index.ts:53`（`--plan`）、`preset.ts:114`、`sandbox/index.ts:202`（`--no-sandbox`）、`ssh.ts:115`。

**我们侧（读到的）**：引擎 argv 是拼死的字符串 —— `PiEngineHost` 的 `guestCommand`（`:390-397`）只有 `node <cli>`、`--session-dir`、以及 `launch.commandLineSuffix()`；`PiLaunchOptions`（`rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt`）的字段只有 `offline`/`longCacheRetention`/`systemPrompt`/`appendSystemPrompt`/`noContextFiles`；设置注册表 70 键里没有任何一行是"额外进程参数"；`PiPreSpawnConfig.kt` 的三张表（`APP_EXPOSED_PRE_SPAWN` `:87-129`、`COVERED_BY_PI_SETTING` `:137-238`、`NOT_EXPOSED_PRE_SPAWN` `:247-391`）**都没有收 unknown flags 这一项**。

**为什么算欠账而不是"pi 没有"**：值必须在 spawn 那一刻决定，而 spawn 是**我们**做的；pi 对此的设计就是"嵌入方把 argv 传进来"。这跟已经接了的 `--system-prompt` / `--offline` 是同一类（同一张表、同一条通道），只是这一项漏了。

**后果**：装了 `plan-mode` 的用户的 `--plan` 永远是未设值 → 扩展走 `getFlag("plan") === undefined` 的分支，功能静默失效（不会报错）。装了 sandbox 的用户同理。逃生口只有终端页（自己敲 `pi --plan`）。

**落地要点（供决策，不是本文件的改动）**：
1. 一个自由文本的进程参数行（`RestartEngine` 生效），最省事，风险是拼错 flag 会让 pi `exit(1)`（`agent-session-services.ts:118-124` 的 "Unknown option" 是 error 级诊断）。
2. 或者先枚举再选：`pi --help` 在加载扩展后会把注册的 flag 打出来（`main.ts:853-860` 的 `printHelp(extensionFlags)`，格式 `args.ts:252-261`），App 可以在 guest 里跑一次 `--help` 解析出清单，再做开关行。这条更贴 pi、也更贵。

### 10.2 已安装资源包无法更新 —— 严重度：中低

**pi 侧**：`pi update --extensions`（`package-manager-cli.ts:1013-1021`）与 `pi update <source>`（`updateTarget.type === "extensions"`，`:533-544`）都会走 `DefaultPackageManager.update`（`package-manager.ts:1059+`）；另有 `checkForAvailableUpdates`（`:1186-1250`）供提示"有 N 个更新"。

**我们侧**：`PiPackageService` 的公开动作只有 `install`（`:195-196`）、`remove`（`:199-200`）、`list`（`:222-245`）；`PiPackagesHost` 只有 `install()`（`:374`）、`remove()`（`:398`）、`removeFilter()`（`:432`）；`PiPackagesScreen` 的三个按钮是安装（`:1036`）、刷新列表（`:1039`）、卸载（`:1151`）。**没有任何地方拼 `update`。**

**手机上有意义吗**：有。装包本来就在 App 里做（同一个 `GuestCommand` 通道、`INSTALL_TIMEOUT_MS`），更新是同一套 argv 的下一个动词。

**注意**：`pi update --models`（`package-manager-cli.ts:914-923` → `refreshModelCatalogs`）**不算欠账** —— pi 在 RPC 启动时已经后台刷新目录（`main.ts:921-928`），能力等效存在。`pi update`（self）**不算欠账**，归 §11。

### 10.3 `pi auth print-api-key` / `print-bearer-token` 无对应动作 —— 严重度：低（存疑）

**pi 侧**：`cli/auth-command.ts:18-22`（用法）、`main.ts:161-173`（解析凭据并打印，含刷新过期 OAuth）、`cli/credential-print.ts`。用途是给**外部客户端**取凭据。

**我们侧**：凭证页只在"填写 / 覆盖"方向工作（`PiCredentialScreen`、`PiCredentialService.save`），没有任何"显示已存密钥"的动作；`auth.json` 的写模式是 `0600`（`PiConfigFiles` 收紧不放宽）。

**为什么列出来**：题面规矩是"pi 有的必须 1:1"，而这一项既没有实现、也没有任何地方记录过"我们决定不做"（全仓只有 `docs/pi-android-app-design.md:652` 顺带提到这两条子命令）。**它是不是欠账取决于产品判断**：手机上没有外部客户端，而把长期密钥明文显示在屏幕上通常是要刻意避免的（对比 `print-bearer-token` 还会触发 OAuth 刷新，这在手机上反而有意义）。
**我的判断**（推断）：归入"知道就好"，真要补也只值得补 `print-bearer-token` 的"显示/复制"一种，且需要用户确认。

### 10.4 一致性缺陷（不是欠账，但要修）

`/login`（订阅登录）现在有**两个互相矛盾的说法**：
- `TerminalScreen.kt:72`：`"输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。"`（真的：`PtyLauncher.environment()` 带 `PI_CODING_AGENT_DIR`/`PI_CODING_AGENT_SESSION_DIR`，`:396-404`）
- `PiSettingsRegistry.kt:315-327`（`app.credentials.oauth` 行，标题"订阅登录（本应用暂无入口）"）：注释写着「终端不是可用面」，正文说"订阅登录还没有入口"。
- `PiSlashCommands.kt:287`：`"login" to "pi 有 /login；本应用没有对应入口。"`

能力上是对的（终端页能跑 `/login`，`interactive-mode.ts:3052-3060` → `handleLoginCommand:5485`）；错的是**其中两处文案否认了它**。仓库自己的规矩是「界面必须说真话，指向 pi TUI」，这里正好违反。建议以 `TerminalScreen.kt:72` 的措辞为准，统一成"本应用无原生入口，可在 工作区 → 终端 里运行 `pi` 后 `/login`"。

---

## 11. 手机不可用 / 有害（明确不做）

| 项 | pi 证据 | 为什么不做 |
|---|---|---|
| `pi update`（自更新，含托管安装与 npm/pnpm 两条路） | `package-manager-cli.ts:1022-1092`；`getSelfUpdatePlan` | App 钉死 `PI_VERSION = "0.85.1"`（`tools/fetch-runtime.mjs:190`）并按 revision 校验/解压载荷（`RuntimeProvisioner`）。让它自更新等于让被校验的产物自己变，`tools/pi-contract.mjs` 的全部断言（命令名、语义、扩展 API）会一起失效 |
| `--session-id <id>` | `args.ts:125-127`、`main.ts:431-443` | 只有外部编排器需要"我要这个 ID"；App 的 `new_session` 不需要 |
| `--print` / `-p` | `args.ts:157-163`、`main.ts:118-120` | 给脚本一问一答；App 的对话页就是交互面 |
| `pi experimental server` / `client`、`packages/protocol` | `cli/experimental/**`；`package.json:31-34` 把 `dist/cli/experimental`、`dist/experimental` 排除出发布物；`protocol.ts:5` `PROTOCOL_VERSION = 8` | **不在随包发出的产物里**，与我们的 RPC 协议是两套东西 |
| `PI_RADIUS_GATEWAY` / `PI_SERVER_DIR` / `PI_SERVER_ID`、Radius 认证 | `experimental/radius-auth.ts:8`、`experimental/server.ts:51-52`；`/share` 的 Radius 分支 `session-share.ts:49-...` | 源码级实验特性，发布物里没有 |
| `/share`（GitHub gist / Radius） | `interactive-mode.ts:3002`、`:6152`；`session-share.ts:46` | 需要 `gh` CLI 且是 TUI 内建；App 用"导出 + 系统分享"（`SessionExportDelivery`）覆盖用户真正的意图 |
| `--api-key`（一次性明文） | `args.ts:108-109`、`main.ts:806-815` | 会把明文带进进程参数表；凭证页写 `auth.json`/`models.json` 是正确通道 |
| `enableAnalytics` / `trackingId` | `settings-manager.ts:129-130`、`:1066-1079` | pi 里**写进去没有消费者**（本次 grep 全文确认，只有测试读 `getTrackingId`）。暴露一个没有作用的开关比不暴露更差 |
| `--exclude-tools` | `args.ts:142-146`、`sdk.ts:260-262` | 工具全集 = 内置 + 运行时扩展，设置页给不出准确清单（已记录在 `NOT_EXPOSED_PRE_SPAWN`） |

---

## 12. 附录 A：TUI 专属项（**不算欠账**，列出来是为了"知道漏了什么"）

三条来源：pi 自己标注的 RPC no-op、内建斜杠命令、TUI 组件。

**(a) pi 自己声明的 RPC 模式不可达**（`modes/rpc/rpc-mode.ts` 的 `ExtensionUIContext` 实现）：

| 方法 | pi 行 | pi 的处置 |
|---|---|---|
| `onTerminalInput` | `:163-166` | 返回空退订 |
| `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` | `:179-189` | 空实现（要 TUI loader） |
| `setHiddenThinkingLabel` | `:191-193` | 空实现 |
| `setWidget`（组件工厂形态） | `:195-208` | 只支持字符串行，工厂被丢 |
| `setFooter` / `setHeader` | `:210-216` | 空实现 |
| `custom()` | `:228-231` | 直接 `undefined` |
| `addAutocompleteProvider` / `setEditorComponent` / `getEditorComponent` | `:273-284` | 空实现 |
| `setTheme` / `getAllThemes` / `getTheme` | `:286-301` | `setTheme` 返回 `{success:false,error:"Theme switching not supported in RPC mode"}` |
| `getToolsExpanded` / `setToolsExpanded` | `:303-310` | 空实现 |
| `pasteToEditor` | `:233-236` | 退化成 `setEditorText` |
| `getEditorText` | `:248-252` | 返回 `""` |

App 的处置：`TuiOnlyScan.kt` 扫扩展源码给出提示 —— 这是能做的极限（pi 不会报任何事件）。

**(b) 23 个内建斜杠命令**（`core/slash-commands.ts:19-43`；TUI 分派行见 `interactive-mode.ts`）：

| 命令 | TUI 行 | App 状况 |
|---|---|---|
| `settings` | `:2970` | 底栏「设置」（有意不列为面板行） |
| `model` | `:2980` | 顶栏模型按钮 |
| `tree` | `:3042` | App 有树屏，但按钮是**分叉**（§5.4） |
| `thinking` | `:2986` | 输入框 ◐ |
| `scoped-models` | `:2975` | `enabledModels` 设置行 |
| `export` | `:2992` | 已接（HTML + JSONL） |
| `import` | `:2997` | 已接（文件选择器） |
| `share` | `:3002` | 不做（§11） |
| `copy` | `:3007` | 已接 |
| `name` | — | 已接 |
| `session` | `:3017` | 统计 sheet（少了 cache 浪费/按厂商成本，§9） |
| `changelog` | `:3022` | 不做（TUI 面板） |
| `hotkeys` | `:3027` | 不做（触屏无快捷键表） |
| `fork` | — | 已接 |
| `clone` | — | 已接 |
| `trust` | `:3047` | 设置行 |
| `login` | `:3052-3060` | 无原生入口；**终端页可达** |
| `logout` | `:3058` | 同上 |
| `new` | `:3063` | 已接 |
| `compact` | `:3068` | 已接 |
| `resume` | `:3094` | 已接 |
| `reload` | `:3074` | 重启引擎覆盖 |
| `quit` | `:3099` | 不做（引擎的生死由 App 的前台服务管） |

**(c) 只在 TUI 里消费的 pi 设置键**（`core/settings-manager.ts:107-157` 里，App 的 70 键中**故意没有**的那些）：`lastChangelogVersion`、`collapseChangelog`、`externalEditor`（Ctrl+G）、`quietStartup`、`tuiMode`、`fullscreenExitOutput`、`fullscreenScrollbar`、`fullscreenCopyOnSelect`、`editorPaddingX`、`outputPad`、`autocompleteMaxVisible`、`showHardwareCursor`、`doubleEscapeAction`、`treeFilterMode`、`markdown.{codeBlockIndent,mermaid}`、`warnings.anthropicExtraUsage`（`:4909`）、`terminal.*`（7 个，只有终端页里跑的原版 pi 会读）、`branchSummary.skipPrompt`（`:32`，摘要确认提示）。

**(d) 其他 TUI 组件**：`core/footer-data-provider.ts`（页脚）、`core/cache-stats.ts` + `core/usage-totals.ts`（`/session` 面板的两块）、`core/keybindings.ts`（快捷键表）、`modes/interactive/components/**`。

---

## 13. 我们有意钉死（pi 有、手机可用，但**我们选择不做**）

> `--flag` 这一类（扩展注册的 flag）**不在这张表里**，它没有理由记录，按欠账处理（§10.1）。

| 项 | 证据 | 我们的理由（读到的） |
|---|---|---|
| `--session-dir` / `sessionDir` 键 | `PiPreSpawnConfig.kt:355-357`、`:181-188` | App 同时发 flag 和 env，设置里的键永远读不到 |
| `--no-extensions` | `:249-255` | 会停掉 App 自己的 `android_*` 设备扩展 |
| `--no-skills` / `--no-prompt-templates` / `--no-themes` | `:256-274` | App 的技能/模板/主题屏读同一批目录，会自相矛盾 |
| `--exclude-tools`、`--no-session`、`--name`、`--verbose`、`--tui-mode`、`--api-key` | `:275-317` | 逐条理由在表里 |
| `--no-tools` / `--no-builtin-tools` | **两张表都没收**（见 §14） | 现状：`defaultTools` 行覆盖内置工具的选择；"全关"没有入口 |
| `cycle_model`（命令存在、无 UI） | `Commands.kt:133-141` | 用户裁决删除了溢出菜单入口；模型选择器已列出全部模型 |
| `get_messages`（命令存在、无调用） | `PiEngineApi.kt:61-63` | 压缩后与 `get_entries` 不同，用它替换会丢历史 |
| `setStatus` 收到但不绘制 | `PiSessionViewModel.onExtensionChrome`（`setStatus` 分支）+ `setExtensionStatus` 的 KDoc | 用户裁决 D-3 删除了扩展状态行；数据保留（"重新出现的成本是一个调用点"） |
| 订阅登录（OAuth） | §10.4 | 无原生入口（终端页可达）；**但文案有两处说反了** |

---

## 14. 未确认项（写明缺什么证据）

1. **扩展命令里 `ctx.reload()` 之后，App 的面板会不会刷新。** `rpc-mode.ts:341-343` 把它接到 `session.reload()`；这条路上 pi **不发任何事件**，App 只在 `AgentSettled` 时刷 `refreshCommands`（`PiSessionViewModel.onEvent`）。缺的证据：一次真机上"扩展命令 → reload → 看 `/` 面板"的实测（本次只读、无设备）。
2. **`--no-tools` / `--no-builtin-tools` 是否真的没有 App 入口。** 我读了 `defaultTools` 行与 `app.security.emergencyStop`（`PiSettingsRegistry.kt:1330-1340` + `PiRoot.kt:751-756`，它是 `abort`+`clear_queue`+`abort_bash`，**不是**关工具），也确认 `PiPreSpawnConfig` 两张表都没收这两个 flag。缺的证据：产品意图记录（"要不要一个读-only 会话"）。功能上 `defaultTools: []` 能关内置工具；"连扩展工具也关"没有通道（`sdk.ts:258-262` 的 `noTools:"all"` 只在 CLI）。
3. **cache 浪费 / 按厂商成本拆分要不要复算。** 数据在 `get_entries` 里（每条 assistant 的 `usage`），pi 的算法在 `cache-stats.ts:138`/`usage-totals.ts:37`，而入口是 TUI 内建 `/session`（`interactive-mode.ts:6216-6222`）。我**没有**逐个语义核对这两段算法（例如"上一请求"的定义 `cache-stats.ts:107-147`），所以不主张"可以照抄"。缺的证据：`cache-stats.ts` 的逐行语义核对。
4. **`pi auth check` 与 App 的"检测并扫描模型"是否在所有 provider 上等价。** pi 的判定是"本地有没有可用凭据 + 能不能解析"（`cli/auth-check.ts`），App 的是"发一次 `GET /models`"。对无模型列举接口的厂商（例如只支持 chat 的自定义端点）两者可能给出不同结论。缺的证据：`cli/auth-check.ts` 逐分支阅读 + 一次真机对比。
5. **`docs/rpc-coverage.md` 的"32/33 有入口"已过期**（`cycle_model` 现在没有 UI）。我按代码判定为 31/33 有真实发送方。缺的证据：无 —— 这是**已确认的账本与代码冲突**，留给该文档的作者。
6. **`--session-id` / `--print` 的"手机不可用"分级**是我的产品判断（推断），没有代码依据可引。
7. **`setSessionName` 的规范化提示。** pi 的 TUI 在名字被规范化时会提示（`interactive-mode.ts:6211-6213`），RPC 只回 `success`；App 拿不到"被规范化成了什么"，只能重读 `get_state`。这是 pi RPC 的信息缺失，不是我漏读 —— 但如果以后要显示，需要先确认 `get_state.sessionName` 足够。
<br>
