# pi 能力面全量审查 · 命令行 / 协议 / 会话 / 模型认证 / 包与信任 / 可观测

> 回答一个问题：**pi 到底还有啥功能我们没做？**
> 报告本身是只读审查的产物；**§15 记录其后一个批次的改动**（改了哪些符号、没验什么），
> §1 的计数与 §10–§14 的状态都已随该批次更新。
> **§16 记录再其后一轮的复核（本文档更新到 1.3.0 基线的那一轮）**：那次复核逐条重判了
> §1/§10/§11/§13/§14 的全部条目，并列出被证伪的断言 F1–F12。

## 0. 方法与判据

| | 值 |
|---|---|
| pi（权威） | ~~`/root/pi-src` @ `bbb61e3`，`packages/coding-agent` 0.85.1~~ → **`@earendil-works/pi-coding-agent` 1.3.0**，两处实测一致：`tools/fetch-runtime.mjs:588` 的 `PI_VERSION` = `"1.3.0"`，`tools/pi-engine.lock.json` 的 `packages["node_modules/@earendil-works/pi-coding-agent"].version` = `"1.3.0"` |
| App | ~~`/root/pi-android` @ `5a49bdc`~~ → **`c2a174b`**（工作树有并发写者；§15 的改动**已提交**，§16 复核时的工作树另有若干**未提交**改动，逐处标注） |
| **0.85.1 那一版的树** | **不在盘上**：`/root/pi-src` 不存在；`/tmp/pi-src` 是一个**空目录**（`ls -la` 无文件、`du -sh` = 3.5K、无 `.git`）。⇒ **0.85.1 → 1.1.0 之间是盲区**，见下 |
| 本研究实际比对的版本 | **1.1.0 / 1.2.0 / 1.3.0**（`/tmp/pi-1.2.0/v110/package`、`/tmp/pi-1.2.0/package`、`/tmp/pi-1.3.0/*/package`，另 `/tmp/pac120`、`/tmp/pi-1.2.0/ai120`）。凡本文说"某能力 0.85.1 就有"，证据只到 1.1.0 为止 |
| 协议定义 | `packages/coding-agent/src/modes/rpc/{rpc-types.ts,rpc-mode.ts}`、`modes/json-event.ts`、`packages/agent/src/types.ts`、`packages/ai/src/types.ts`（1.3.0 的等价物是 `dist/modes/rpc/rpc-types.d.ts`、`dist/modes/json-event.d.ts`、`@earendil-works/pi-agent-core/dist/types.d.ts`、`@earendil-works/pi-ai/dist/types.d.ts` —— 随包只发 `dist`） |
| App 协议层 | `rpc/src/main/kotlin/app/pi/rpc/{Commands,Events,Responses,Transcript,PiPreSpawnConfig,PiLaunchOptions,ExtensionFlagArgs}.kt` |
| App 调用/界面层 | `app/src/main/kotlin/app/pi/{engine/**,ui/**,packages/**,session/**,settings/**}` |

**盲区的含义（明写）**：本文的"官方有而 App 没接"只能保证**在 1.1.0 与 1.3.0 上都成立**。
只存在于 0.85.1–1.0.2 那段时期的 CLI 面**可能仍未被审查** —— 这不是假设：
`pi mcp` 子命令组就是从这段盲区里挖出来的（它 1.1.0 起就在，而本文 §3 曾把它写成"不存在"，见 F2/§13 的新增行）。
要彻底关掉这个风险，需要一份 0.85.1（或 0.86.1/0.87.1）的包或源码树逐面差分；**本轮没有**。

**本轮实际跑过的命令**（可复现；跑不了的如实说不跑）：

```bash
# ① 契约：App 自己的设置键决策表对着真装的 1.3.0
node tools/pi-contract.mjs --pi /tmp/pi130stage/node_modules/@earendil-works/pi-coding-agent --only=tables
  → PASS every pi settings key the app does not expose is a written-down decision (30 decided)
  → PASS the unexposed-settings decisions still describe reality (0 stale)
  → (40 pi-owned settings keys pinned, 59 in pi; 30 not exposed)

# ② 未知 --flag 的端到端行为（这是本轮最有价值的一次实测）：1.1.0 / 1.2.0 / 1.3.0 都是 exit 1
echo '{"id":"x","type":"get_state"}' | node <pkg>/dist/bundle/rpc-entry.js --session-dir <dir> --bogus-flag
  → exit=1, stderr="Error: Unknown option: --bogus-flag", stdout 空
  → 对照（不带未知 flag）: exit=0, stdout 有 {"type":"response","command":"get_state","success":true,…}

# ③ CLI flag 集合差分：三版逐字相同（各 43 个值，两两 diff 为空）
grep -o '"--[a-z-]*"' <pkg>/dist/cli/args.js | sort -u

# ④ 协议边界的零命中：1.3.0 的 rpc-types.d.ts 里 navigate / label / reload 各 0 次
#    并且 ExtensionRunner.setFlagValue 全仓无调用者（只有定义）

# ⑤ settings accessor → 调用者分组：从 dist/core/settings-manager.js 精确抽每个 getter 的方法体
#    （大括号配平）→ 取它读的键 → 在 dist/**/*.js（排除 bundle/）里找调用者 →
#    按是否落在 dist/modes/interactive/ 之外分组。用来逐条重核 §11/§13 的"TUI-only"理由。
```

**没跑、也不声称跑过**：`tools/typecheck.sh`（本机无 Android SDK / Gradle 缓存，必然 exit 2）、
任何 Kotlin harness（本机无 kotlinc）、真机。

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

> ~~**本节的计数已按本批次（见 §15）更新**：原报告有 3 条真欠账，其中两条已在本批次处理
> （一条接上、一条按裁定不做），第 3 条仍在。~~
> **§16 复核轮再次更新本节**：下面那条"还剩 1 条"**已经不成立**（它已补上）；
> 复核轮筛出的三条欠账候选（`pi mcp` 子命令组、`--no-tools`、`--no-mcp`）**裁定全部不做**，
> 各记一行在 §13。⇒ **本节口径：真欠账 0 条、有意不做若干条。** 另有 1 个 App 缺陷（不是欠账）记录在 §16.2。

**原「还剩 1 条真欠账」→ 现在是 0 条**：那唯一的一条 —— ~~**扩展注册的 CLI flag 传不进去**（`ctx.registerFlag` / `ctx.getFlag`）。pi 的值只能从 argv 进（`core/agent-session-services.ts:100-113` 从 CLI 的 unknown flags 灌入），而我们的引擎 argv 是固定的（`PiEngineHost` 的 `guestCommand`，`:390-397`），`PiLaunchOptions` 没有字段、设置里没有行、`PiPreSpawnConfig` 的两张表都没收它。装了 plan-mode / preset / sandbox / ssh 这类示例扩展时，它们的 `--plan` / `--preset` / `--no-sandbox` / `--ssh` **永远是未设值**。~~ —— **已补上**。链路六处（都是**已提交**代码）：

1. `rpc/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt`（**整个文件**）：按 pi 的 `cli/args.ts:227-241` 复刻"未知 `--flag`"的解析 —— `=` 形式、空格形式、裸 flag → `true`、重复取最后值；单横线选项 / `--` / `@file` / 裸词四类在**引擎启动之前**拒绝并给一句话（`Refusal`/`refusal`）。
2. `rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt:172-197`：**两个** `channel = PiPreSpawnChannel.ExtensionFlagsPassThrough` 的旋钮，`appKey = "app.extensions.args"`（该 channel 的 `flag`/`envVar` 为 null 是刻意的 —— 名字属于扩展）。
3. `rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:150`（`extensionFlags` 字段）、`:282`（拼进 argv）、`:331-342`（读值并带上拒绝原因）、`:156`（`extensionArgsRefusal`）。
4. `app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt:1027-1038`：`key = "app.extensions.args"`、`title = "扩展启动参数"`、`kind = PiRowKind.Text`、`effective = EffectiveKind.RestartEngine`。
5. `app/src/main/kotlin/app/pi/ui/ExtensionArgsSettingsStore.kt:42`（`ExtensionArgsStore`，`PREFS_NAME = "pi-extension-args"`，`:65`）：值存在 **App 自己的 sidecar** 里，不落进 pi 能看见的文件；读取点 `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:2184`，拒绝提示 `:3486`。
6. harness：`tools/run-app-pure-checks.sh:719-724`（`pre-spawn`）+ `:735-737`（`extension-flags`），断言在 `app/src/test/kotlin/app/pi/rpc/{PiPreSpawnCheck,ExtensionFlagArgsCheck}.kt`。

**今天真正的真欠账：0 条。** 复核轮按同一判据把"官方有、手机可用、App 没接"的候选重新筛了一遍，
筛出**三条曾被当作欠账候选**的能力，**裁定三条全部不做**，各一行记在 §13（有意钉死）：

1. **`pi mcp` 子命令组**（本文 §3 曾把它写进"不存在的子命令" —— 那处**是错的**，它存在，1.3.0 有 5 个子命令）。
   **裁定：不新增 MCP 相关的任何东西。** 事实是：MCP 的**运行时**一直是好的 —— 读 `mcp.json`、连服务器、
   注册并在会话里调用 `mcp__<server>__<tool>`；pi 只是把**管理**做成了 CLI 子命令。
   App 不提供 MCP 管理界面，配置走 `mcp.json`（文件屏可改）。详见 §13 的对应行。
2. **`--no-tools`（连扩展工具一起关）—— 裁定：不做。** `defaultTools: []` 关的是**内置**工具，
   扩展工具因 `includeAllExtensionTools: true` 仍在，而"连扩展工具一起关"没有实际使用场景。详见 §13。
3. **`--no-mcp`（一次运行关掉内置 MCP 扩展）—— 裁定：不做。** 理由同上一条的裁定：**MCP 一律不动，
   官方传什么就原样透过来。** 详见 §13。

> **一个与欠账无关、但本轮必须记下来的 App 缺陷（已在修）**：§10.1 那条 fix 自己的一处前提是错的 ——
> `ExtensionFlagArgs.kt` 的 KDoc 原说未注册的 flag 只会得到一条 `Unknown option` 诊断而**不会退出**；
> 实测三个版本都是 `exit 1`（1.1.0 / 1.2.0 / 1.3.0，脚本见 §0）。⇒ 用户在「扩展启动参数」那一行
> 打错一个 flag 名（或那个 flag 的扩展被卸载），引擎起不来。**这是缺陷，不是欠账**，所以不进上面的计数；
> 记录与现状见 §16 的 F7/F8 与 §16.2（**KDoc 与退出诊断两侧都已在修**，而 `ExtensionFlagArgsCheck.kt` 的旁注仍待补）。

**原第 2 条已接上**：`pi update --extensions` / `pi update <source>` 现在有实现与入口
（`PiPackageService.update`、`PiPackagesController.update/updateAll`、`PiPackagesScreen` 的
「全部更新」与行内「更新」），诚实边界写在界面上而不是埋在日志里 —— 见 §10.2 与 §15。
（§16 复核：`PiPackageUpdateCheck` 的 harness **已注册**进 `tools/run-app-pure-checks.sh:274-276`，§15.3 的"尚未注册"随之过期 —— 见 F10。）

**原第 3 条按裁定不做**：`pi auth print-api-key` / `print-bearer-token` 判为**有意不做**
（凭据只写不显示是刻意的安全取舍），理由已记进 §11 的对应行 —— 见 §10.3。**（§16 复核：判定不变。）**

**原一致性缺陷已修**：`app.credentials.oauth` 行、`/login` `/logout` 的提示语、以及
`PiRoot` 里那一行的动作答复，现在都指向终端页这一条**事实**路径（~~`TerminalScreen.kt:72` 的
说法本来就是对的，未改~~ → 那句话**已不存在**：`TerminalScreen.kt:28-38` 的 KDoc 写着
"The explanatory sentence is gone, on purpose"，屏幕上只剩 `"终端"` 与 `"返回"`；
`PtyLauncher.environment()` 仍带 `PI_CODING_AGENT_DIR`/`PI_CODING_AGENT_SESSION_DIR`，`:396-404`，
所以"终端页能跑 `/login`"这个**实质**结论不变）。见 §10.4 与 §15。

**三条"pi 有、但 pi 自己的 RPC 够不着"**（不是欠账，属协议边界，见 §5.4）：`/tree` 跳转 + 分支摘要（`navigateTree`）、会话标签的**写**、`/reload`。它们在 pi 里只有 L2/L3 入口（~~`modes/rpc/rpc-types.ts` 里 `navigate` / `label` / `reload` 三个词**一次都没出现**~~ → **1.3.0 的 `dist/modes/rpc/rpc-types.d.ts` 里仍然各 0 次**，§16 复核确认）。

**pi 没有的东西我们一件也不用补**：`pi doctor` **不存在**（全 `packages/coding-agent` 无 `doctor` 符号，只有 `DiagnosticsScreen`/`collectSettingsDiagnostics` 这种设置诊断）；`--dangerously-*` **不存在**（全仓只有 SDK 内部的 `dangerouslyAllowBrowser`，`packages/ai/src/api/*.ts`，与 CLI 无关）。
**（§16 复核：两条判断都仍成立。但同一句话里的 `pi mcp` 例外 —— 它现在存在，见 §13 的新增行 / §16.5 / F2。）**

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
| `--session <path\|id>` `:123` | 指定会话文件或 ID 前缀（`resolveSessionPath`，`main.ts:252-278`） | 运行时：`switch_session` + `SessionsScreen`；**启动时**：进程内重启在 pi 的 id 查找不唯一命中（同 id 多份、`findById` 取 `readdirSync` 顺序第一个）或 header 的 cwd 对不上引擎 cwd 时，发 `--session <path>`（`PiSessionViewModel.resumeTarget` → `PiLaunchOptions.resumeSessionPath`；它没有 cwd 过滤，起出来的 runtime cwd 取会话自己的 cwd，与 `switch_session` 同一个状态） | 已接 | 运行时可做，不必启动参数 —— 但**重启**那一条必须走 argv：起来之后再补 `switch_session` 会与新引擎的首帧抢会话 |
| `--session-id <id>` `:125` | 精确 ID（不存在则按该 ID 新建，`main.ts:431-443`） | **引擎重启**时靠 argv 钉回同一段对话（`PiSessionViewModel.restartEngine` → `app.pi.session.resumeTargetFor`）；只有「`findById` 一定且唯一命中那个文件」时才发它，否则改发 `--session <path>`（见上一行） | 已接（App 内部用，不是用户面） | 手机不可用的那半是「让用户手输一个 ID」；App 是把它当**续接**的手段用的 |
| `--fork <path\|id>` `:127` | 从某会话 fork 成新文件（`SessionManager.forkFrom`，`main.ts:363-384`） | `fork` / `clone` RPC | 已接 | `SessionTreeScreen` 的「分叉」 |
| `--session-dir <dir>` `:129` | 会话存储目录（`main.ts:670-676`） | 与 `PI_CODING_AGENT_SESSION_DIR` 一起**钉死**（`PiPreSpawnConfig.kt:355-357`） | 有意钉死 | 设定后设置里的 `sessionDir` 永远读不到，会变成两个真相 |
| `--models <patterns>` `:131` | Ctrl+P 循环用的模型作用域（`main.ts:788-792`） | `enabledModels` 行（通配符编辑） | 已接 | CLI 值会盖过设置值，故只留设置 |
| `--no-tools` / `-nt` `:133` | 关掉**全部**工具（`sdk.ts:258-262`） | 无直接等价物；`defaultTools`（空数组）只关内置 | 有意钉死（**§16 已核准，裁定不做** —— 原「存疑，见 §14」，§14 第 2 条已结） | 手机上"全关"的意图由 `defaultTools` 覆盖大半。**§16 补足**：`--no-tools` 在 1.3.0 里仍是**唯一**能关掉扩展工具的通道（`dist/core/sdk.js:149` 的 `options.noTools ? [] : …`、`:314` 的 `!options.noTools`），而"连扩展工具一起关"没有实际使用场景 —— 完整裁定见 §13 的对应行 |
| `--no-builtin-tools` / `-nbt` `:135` | 关内置、留扩展工具（`sdk.ts:258-262`） | `defaultTools` 行 | ~~已接（近似）~~ → **已接（等价）**（§16 更正，F5） | 同一开关的设置形式。**等价性的依据**：pi 侧 `--no-builtin-tools` 让 `initialActiveToolNames = []` 且 `includeAllExtensionTools: true`（`dist/core/sdk.js:149`、`:160`、`:314`）；设置侧 `defaultTools: []` 走 `resolveDefaultTools([])` → `[]`（1.2.0 的 `PiQuickAdd.kt:68-77` 是它的转录），随后同样是 `includeAllExtensionTools: true` ⇒ 两边都是"内置为空 + 扩展工具仍在"，不是"近似" |
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
| 未知 `--flag` `:227-240` | **扩展注册的 flag**（见下） | ~~**无**~~ → `app.extensions.args` 行 + `ExtensionFlagArgs` + `PiLaunchOptions.extensionFlags`（§16 更正） | ~~**欠账**~~ → **已接**（F1） | ~~§10.1~~ → §10.1 已改成"已补上"；该 fix 自己的一个前提是错的，见 §16.2 |

**`--flag` 之外还有一条容易漏的：`args.ts:323` 的帮助文本明说"扩展可以注册额外 flag（例如 plan-mode 扩展的 `--plan`）"**，而这条链在 RPC 模式是通的：`parseArgs` 收进 `unknownFlags` → `main.ts:737` → `applyExtensionFlagValues`（`agent-session-services.ts:82-124`）写进 `runtime.flagValues` → 扩展用 `ctx.getFlag(name)` 读（`core/extensions/types.ts:1345`、`loader.ts:358-362`）。注册侧是 `ctx.registerFlag`（`types.ts:1329-1345`、`loader.ts:321-336`）。~~**链的另一端（我们）是空的** —— 见 §10.1。~~ **§16 更正：链的另一端现在也接了** —— App 侧把用户写的一行文本按 pi 自己的规则解析成 argv（`ExtensionFlagArgs.kt`），见 §1 的六处证据与 §10.1。
**但这条链上有一个必须写下来的事实（本轮实测，三个版本一致）**：pi 对**未注册**的 flag **不是**只记一条诊断 —— 它 `exit 1`。见 §16.2。

### 2.1 环境变量面（题面未要求逐条，但与 flag 表互为镜像）

`PiPreSpawnConfig.kt` 已经把 pi 的 env 面分成三张表（App 发出的 5 条 / 由设置键覆盖的 14 条 / 有意不发的 20 条），本次逐条复核**没发现该表漏项**：`args.ts:387-435` 的环境变量清单与 `docs/environment-variables.md` 里被引用的那些，都能在 `COVERED_BY_PI_SETTING` 或 `NOT_EXPOSED_PRE_SPAWN` 里找到落点。两个补充观察：

- `PI_TELEMETRY` 覆盖 `enableInstallTelemetry`（`core/telemetry.ts:8-14`）—— 但它的**唯一消费者**是 provider 归因请求头（`core/provider-attribution.ts:38-40`，OpenRouter/NVIDIA/Cloudflare 的 header），0.85.1 里**没有安装/更新上报的网络调用**。我们的 `enableInstallTelemetry` 行因此是 1:1 的、也只是那一个作用。
  **§16 在 1.3.0 上复核（结论不变）**：`dist/core/settings-manager.js:819-828` 仍只有 `getEnableAnalytics`/`setEnableAnalytics`/`getTrackingId`/`getOrCreateDeviceId`；`grep -rn 'getEnableAnalytics\|getTrackingId' dist/` 的消费者只有 `dist/core/bug-report.js:52`（把 `trackingId`/`deviceId` **从报告里剔掉**）与 settings-manager 自己；`getOrCreateDeviceId` 的唯一调用者是 `dist/modes/interactive/interactive-mode.js:5282`（ChatGPT 登录的 `getDeviceId`）—— 仍是 TUI-only。⇒ §11 里"`enableAnalytics`/`trackingId` 写进去没有消费者"这一行仍成立。
- `PI_SKIP_VERSION_CHECK` 由 App 钉成 `1`（`PiEngineHost.kt:424`）与 pi 的 `--offline` 行为一致（`main.ts:565-568`）；pi 的版本检查只在交互模式跑（`modes/interactive/interactive-mode.ts:1047`），RPC 模式本来就不会查。

---

## 3. CLI 子命令全量 —— `main.ts` 的分派 + `package-manager-cli.ts` + `auth-command.ts`

pi 的"子命令"是 `main.ts` 在 `parseArgs` **之前**用 `args[0]` 特判的，一共 6 组：

| 子命令 | pi 入口 | 选项 | App 对应物 | 状态 | 理由 |
|---|---|---|---|---|---|
| `install <source>` | `main.ts:586-597` → `package-manager-cli.ts:864-957` | `-l/--local`、`-a/--approve`、`-na/--no-approve` | `PiPackageService.install` + `PiPackagesHost.install` + `PiPackagesScreen` 安装卡（`:1036`） | 已接 | 源形态（npm/git/local）逐条移植（`PiPackageSource`） |
| `remove` / `uninstall <source>` | `:959-968`（`uninstall` 在 `:378-379` 归一） | `-l`、`-a`、`-na` | `PiPackageService.remove` + `PiPackagesScreen` 卸载（`:1151`） | 已接 | — |
| `list` | `:970-1004` | `-a`、`-na` | `PiPackageService.list` + `PiListOutput` / `PiPackagesScreen` | 已接 | 用户/项目两组、`(filtered)` 后缀都读 |
| `update [source\|self\|pi]` | `:1006-1094`；参数解析 `:375-573` | `--self`、`--extensions`、`--models`、`--all`、`--extension <src>`、`--force`、`-a`、`-na` | ~~**只覆盖 `--models` 的等效行为**（RPC 启动时 pi 自己刷新目录，`main.ts:921-928`）~~ → `PiPackageService.update` + `PiPackagesController.update/updateAll` + `PiPackageUpdate`（§10.2）；`--models` 那半仍由 pi 在 RPC 启动时后台刷新目录覆盖 | ~~**部分欠账**~~ → **已接**（§16 修正：本行原与 §10.2 自相矛盾） | §10.2 |
| `config [-l]` | `main.ts:599-601` → `package-manager-cli.ts:791-862` | `-l/--local`、`-a`、`-na` | 资源过滤编辑器：`PiPackageFilterStore` + `PiPackageFilters` + `PiPackagesScreen.FilterEditor`（`:1172-1237`，四类 `extensions/skills/prompts/themes`） | 已接 | pi 的 config 是**交互式 TUI 复选框**，我们编辑同一批 `+`/`-`/`!` 模式串（`config-selector.ts:605-627`）；无 `autoload` 丢失（`PiPackageFilters.normalize`） |
| `auth check` | `main.ts:132-208`；参数 `cli/auth-command.ts:48-118` | `--provider`、`--model`、`--json`、`--credentials`、`--no-refresh` | 凭证页的"检测并扫描模型"（`PiCredentialScreen` → `PiModelScanner`）+ 厂商就绪展示（`PiCredentialService` 的 `credentialPresent` / `get_available_models` 的 auth 过滤） | 已接（机制不同） | pi 是"有没有可用凭据"，我们是网络探测 + 只读 `auth.json`；结论等价、更强 |
| `auth print-api-key` | `main.ts:161-173` → `cli/credential-print.ts` | `--provider` / `--model` | 无 | **欠账（低）** | §10.3 |
| `auth print-bearer-token` | 同上（会刷新过期 OAuth） | `--provider`/`--model`/`--min-expiry` | 无 | **欠账（低）** | 同上；OAuth 刷新本身由 pi 在引擎内做，我们只是不能取出来 |

~~**不存在的子命令**（避免以后照文档补）：`pi doctor`、`pi login`、`pi logout`、`pi upgrade`、`pi clean`、`pi mcp`。~~ → **§16 更正（F2）：这一句里的 `pi mcp` 是错的，它存在。**
**仍然不存在的子命令**（避免以后照文档补）：`pi doctor`、`pi login`、`pi logout`、`pi upgrade`、`pi clean`。前两条题面点名过 —— `doctor` 全 `packages/coding-agent` 无该符号；`login` 是**斜杠命令**不是子命令（`core/slash-commands.ts:36`，实现 `interactive-mode.ts:5485`）。

**`pi mcp` 是第 7 组子命令**（1.3.0 实测；1.1.0 起就在，所以它是从 §0 写明的那个盲区里漏进来的一条）：`dist/main.js:487` 的
`if (args[0] === "mcp")` → `runMcpCommand(args.slice(1), …)`；用法全文在 `dist/extensions/mcp/cli.js:17-56`，
子命令实现分别在 `:142`（`list`）、`:152-153`（`login`/`logout`）、`:211`（`add`）、`:324`（`remove`）。
App 侧**没有**这一组的任何 argv 拼装 —— **按用户裁定不做**（不新增 MCP 相关的任何东西），评估记录见 §13 的新增行与 §16.5。

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

**结论：33/33 有 builder，31/33 有真实发送方；两条不发的都有书面理由。** 审查当时与 `docs/rpc-coverage.md` 的"32/33 有入口"**不一致**：那份文档给 `cycle_model` 标了 UI（`ChatScreen.kt:659`），但当前树的 UI 已按用户裁决删掉该入口（`Commands.kt:133-141` 的 KDoc 记着），`grep "cycleModel(" app/src/main` 只有 `PiEngineApi.kt:152` 一处。**以代码为准：`cycle_model` 与 `get_messages` 都是"从不发送"。** 该文档已在**本批次**按代码改正（见 §15.1）。

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
| 精确 ID | `--session-id` `:125` | — | — | 引擎**重启**时由 argv 钉住（`PiSessionViewModel.restartEngine`）：pi 的 `findById` 一定且唯一命中时发 `--session-id`，否则发 `--session <path>` | 已接（App 内部用，不是用户面） |
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

`/tree` 跳转、分支摘要、会话标签的写 —— 三条都只有 L2/L3 入口，`modes/rpc/rpc-types.ts` 里 `navigate`/`label`/`reload` **零出现**（本次 grep 逐词确认）。**§16 在 1.3.0 上复核：仍然各 0 次**（文件是 `dist/modes/rpc/rpc-types.d.ts`）。App 目前的处理：

- 树屏的按钮是**分叉**而不是跳转，并且有一句正面说明（`SessionTreeScreen.kt:328-358`）。**这是诚实且正确的处理**，本次审查不主张改。
- 会话标签：只读展示 + 五种筛选齐全（`TreeFilter`，对应 `components/tree-selector.ts:340-395`）。缺的只是"写"。
- `/reload`：`/reload` 是 TUI 内建（`interactive-mode.ts:3074`），RPC 无命令；扩展侧的 `ctx.reload()` 才可达（`rpc-mode.ts:341-343`）。App 用**重启引擎**覆盖（`EngineRestartCoordinator` / `PiSessionViewModel.restartEngine`）。

**~~一处待确认~~ → §16 已定论（机制层面）**：扩展命令里调 `ctx.reload()` 之后，App 的命令面板**不会**自动刷新。
证据：1.3.0 的 `dist/core/agent-session.js:2969-3005` 的 `reload()` 全程**没有一次 `this._emit(...)`**，
只发扩展事件 `_extensionRunner.emit({ type: "session_start", reason: "reload" })`（`:2999`），
而扩展事件不走 `session.subscribe`、不进 RPC；App 的 `refreshCommands()` 只有四个触发点
（`PiSessionViewModel.kt:1418`、`:2764`、`:3068`（`AgentSettled`）、`:5776`）。
⇒ 这是 pi 的**信息缺失**，不是 App 能修的（除非加轮询）。**端到端仍缺一次真机实测**（点一次扩展命令 → 看 `/` 面板）——
机制已定论、现象未实测，这个区分写在 §14 第 1 条。

---

## 6. 模型与认证

| 项 | pi 证据 | App 符号 | 状态 | 理由 |
|---|---|---|---|---|
| `auth.json` 位置与 `0600` | `auth-storage.ts:52`（`~/.pi/agent/auth.json`）、`:25`（`mode: 0o600`，**只在创建时**）、`:59`（父目录 `0700`）、`:106`/`:187`（每次写入沿用） | `PiConfigFiles.authFile` + `PiConfigFiles` 的"只收紧不放宽"模式设置（`:146-165`）、`PiAuthStorage` | 已接 | 写入走 pi 自己的锁语义（`AuthStorage.withLock`，`:96-114`）；App 是"读-改-整份重写"的镜像实现 |
| auth 读写锁 | `auth-storage.ts:69-201`（`lockfile` + stale 30s） | `PiSettingsLock` / `TrustRepository` 的同类锁 | 已接 | 同一套"锁文件 + 过期"策略 |
| OAuth 登录 | **只有 TUI**：`/login`（`slash-commands.ts:36`、`interactive-mode.ts:3052-3060`、`handleLoginCommand:5485`、`showOAuthSelector:5619`）；RPC 无命令（`rpc-types.ts` 无 `login`/`oauth`） | 无原生入口；**终端页可跑**（~~`TerminalScreen.kt:72`~~ → §16 更正：那一行说明文字已删；事实路径仍是终端页，`PtyLauncher` 带同一 agent 目录 `:396-404`） | TUI-only（有逃生口） | 见 §12 与 §1 的一致性问题 |
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
| 自更新 `pi update`（self） | `package-manager-cli.ts:1022-1092`（托管安装 / npm / pnpm 三条路） | **有害**：App 钉死 `PI_VERSION`（`tools/fetch-runtime.mjs`，~~**1.0.0**~~ → §16 更正为 **1.3.0**，见 `:588`）并按 revision 校验载荷 | 无 | 见 §11 |
| 启动计时 | `core/timings.ts:5`（`PI_TIMING=1`，写 stderr） | 有 | `app.runtime.engineStartup` 行（App 自己测） | 已接（机制不同） |
| 诊断（设置诊断） | `core/settings-diagnostics.ts`；非交互模式写 stderr（`main.ts:896-900`） | 有 | 引擎 stderr 捕获（`PiEngineSession.stderr`）+ `DiagnosticsReport`；设置侧另有 `PiSettingsValidation` | 已接 |
| `pi doctor` | **不存在** | — | `DiagnosticsScreen`/`DiagnosticsReport` 是 App 自建 | pi 没有对应物 → 不做 |
| 分享 `/share` | `slash-commands.ts:27`；`interactive-mode.ts:3002`/`:6152` → `modes/interactive/session-share.ts:46`（Radius，回落 `gh gist create`） | 无（需 `gh`） | `SessionExportDelivery`（导出 + 系统分享） | TUI-only |
| 会话内 `/session` 面板 | `interactive-mode.ts:6216-6222`：`getSessionStats()` + `computeCacheWaste`（`cache-stats.ts:138`）+ `getUsageCostBreakdown`（`usage-totals.ts:37`） | 部分 | 统计 sheet 覆盖 stats/token/费用/命中率，**没有** cache 浪费与按厂商成本拆分 | TUI-only（数据可从 `get_entries` 复算，但入口是内建斜杠命令） |
| 崩溃/退出 | — | 有 | `EngineExitCause`、`DiagnosticsReport` | App 自建 |

---

## 10. 真欠账清单（pi 有、手机可用、我们没接）

> **§16 复核后的状态**：§10.1（扩展 flag）**已补上**；§10.2（包更新）**已接上**；
> §10.3（`pi auth print-*`）**按裁定不做**；§10.4（一致性）**已修**。
> **⇒ 本表今天没有活着的欠账（0 条）。**
> 复核轮筛出的三条欠账候选 —— `pi mcp` 子命令组、`--no-tools`、`--no-mcp` —— **裁定全部不做**，
> 已经归入 §13「有意钉死」，不在这里列条目。

### 10.1 扩展注册的 CLI flag 传不进去 —— **§16 复核：已补上**（原严重度：中）

**原文（当时的判断，已被推翻，保留作为记录）**：

> **pi 侧（读到的）**：
> - 注册：`ctx.registerFlag(name, {type, default?, description?})`（`core/extensions/types.ts:1329-1345`，落地 `core/extensions/loader.ts:321-336`，`default` 在这里生效）。
> - 取值：`ctx.getFlag(name)`（`types.ts:1345`，`loader.ts:358-362`）。
> - **唯一的值来源是 argv**：`cli/args.ts:227-240` 把未知 `--flag` 收进 `unknownFlags` → `main.ts:737` 传给 `createAgentSessionServices({extensionFlagValues})` → `applyExtensionFlagValues`（`agent-session-services.ts:82-124`）写进 `runtime.flagValues`。运行时的 `ExtensionRunner.setFlagValue`（`runner.ts:536-538`）**全仓无调用者**，即没有第二条输入通道。
> - RPC 侧也没有对应命令（`rpc-types.ts` 里 `flag` 零命中）。
> - pi 自己的帮助文本就举这个例子：`cli/args.ts:323`"Extensions can register additional flags (e.g., `--plan` from plan-mode extension)"；示例扩展确实在用：`examples/extensions/plan-mode/index.ts:53`（`--plan`）、`preset.ts:114`、`sandbox/index.ts:202`（`--no-sandbox`）、`ssh.ts:115`。
>
> **我们侧（读到的）**：引擎 argv 是拼死的字符串 —— `PiEngineHost` 的 `guestCommand`（`:390-397`）只有 `node <cli>`、`--session-dir`、以及 `launch.commandLineSuffix()`；`PiLaunchOptions`（`rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt`）的字段只有 `offline`/`longCacheRetention`/`systemPrompt`/`appendSystemPrompt`/`noContextFiles`；设置注册表 70 键里没有任何一行是"额外进程参数"；`PiPreSpawnConfig.kt` 的三张表（`APP_EXPOSED_PRE_SPAWN` `:87-129`、`COVERED_BY_PI_SETTING` `:137-238`、`NOT_EXPOSED_PRE_SPAWN` `:247-391`）**都没有收 unknown flags 这一项**。
>
> **为什么算欠账而不是"pi 没有"**：值必须在 spawn 那一刻决定，而 spawn 是**我们**做的；pi 对此的设计就是"嵌入方把 argv 传进来"。这跟已经接了的 `--system-prompt` / `--offline` 是同一类（同一张表、同一条通道），只是这一项漏了。
>
> **后果**：装了 `plan-mode` 的用户的 `--plan` 永远是未设值 → 扩展走 `getFlag("plan") === undefined` 的分支，功能静默失效（不会报错）。装了 sandbox 的用户同理。逃生口只有终端页（自己敲 `pi --plan`）。

**§16 复核结论：这条欠账已经不成立 —— 链路六处齐全**（全部**已提交**，见 §1 的同一串证据）：

| # | 位置 | 作用 |
|---|---|---|
| 1 | `rpc/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt` | 按 pi 的 `cli/args.ts:227-241` 解析未知 `--flag`：`=` 形式、空格形式、裸 flag → `true`、重复取最后值；单横线 / `--` / `@file` / 裸词四类在**启动引擎之前**拒绝并给出一句话 |
| 2 | `rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt:172-197` | **两个** `channel = PiPreSpawnChannel.ExtensionFlagsPassThrough` 的旋钮，`appKey = "app.extensions.args"` |
| 3 | `rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:150` / `:282` / `:331-342` / `:156` | 字段、拼进 argv、读值 + 带出拒绝原因 |
| 4 | `app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt:1027-1038` | 用户面那一行：`key = "app.extensions.args"`、`title = "扩展启动参数"`、`kind = PiRowKind.Text`、`effective = EffectiveKind.RestartEngine` |
| 5 | `app/src/main/kotlin/app/pi/ui/ExtensionArgsSettingsStore.kt:42`（`PREFS_NAME = "pi-extension-args"`，`:65`）+ `PiSessionViewModel.kt:2184` / `:3486` | 值存在 App 自己的 sidecar（不落进 pi 能看见的文件）；读值点与拒绝提示 |
| 6 | `tools/run-app-pure-checks.sh:719-724`（`pre-spawn`）、`:735-737`（`extension-flags`）+ `app/src/test/kotlin/app/pi/rpc/{PiPreSpawnCheck,ExtensionFlagArgsCheck}.kt` | 两份 harness |

**而"值来源唯一"这个前提在 1.3.0 仍然成立**：`dist/core/extensions/runner.js:443` 的 `setFlagValue(name, value)` **全仓仍然无调用者**（只有它自己的定义），所以 argv 是唯一入口 —— 也就是说上面那条修法是对的方向。

**落地要点那两条（当时给决策用的）已经落地**：采用了第 1 条（自由文本的过程参数行），
而不是第 2 条（先跑 `pi --help` 枚举）。**代价**：模型选择/拼错都会让引擎起不来 —— 见 §16.2（实测三个版本都是 `exit 1`）。

### 10.2 已安装资源包无法更新 —— **本批次已接上**（原严重度：中低）

**pi 侧**：`pi update --extensions`（`package-manager-cli.ts:1013-1021`）与 `pi update <source>`（`updateTarget.type === "extensions"`，`:533-544`）都会走 `DefaultPackageManager.update`（`package-manager.ts:1059+`）；另有 `checkForAvailableUpdates`（`:1186-1250`）供提示"有 N 个更新"。

**我们侧（原状）**：`PiPackageService` 的公开动作只有 `install`、`remove`、`list`；`PiPackagesHost` 只有 `install()`/`remove()`/`removeFilter()`；`PiPackagesScreen` 的三个按钮是安装、刷新列表、卸载。**没有任何地方拼 `update`。**

**本批次做了什么**：新增纯逻辑 `PiPackageUpdate`（argv 词、`self`/`pi` 拒绝、哪些来源可更新、结果行识别）；
`PiPackageService.update(source, trust)` + `updateCommandLine`；`PiPackagesController.update(entry)` / `updateAll()`；
界面上的「全部更新」与行内「更新」。命令走 pi 自己的 argv：
`update --extensions` 或 `update '<来源>'`，**不加 `-l`**（pi 对 update 拒绝它，`:409-416`），
`--approve` 只在项目作用域且项目已受信任时加（与 install/remove 同一规则，`:755`）。

**诚实边界（写进界面，不埋在日志里）**：pi 在 `:1013-1021` **无条件**打印 `Updated packages` /
`Updated <source>`，无论是否真的换到新版本；而"有没有新版本"这个问题在此路径上**问不出来**——
`checkForAvailableUpdates` 只有 pi 的终端界面会调（`interactive-mode.ts:1054`、`:1143`）。
所以：精确版本的 npm 与本地路径**不提供按钮**（pi 自己的候选收集器就跳过它们，
`package-manager.ts:1103-1110`），其余情况按钮下面直接写明"pi 不报告有没有新版本"。

**注意**：`pi update --models`（`:914-923` → `refreshModelCatalogs`）**不算欠账** —— pi 在 RPC 启动时已经后台刷新目录（`main.ts:921-928`），能力等效存在。`pi update`（self）**不算欠账**，归 §11。

### 10.3 `pi auth print-api-key` / `print-bearer-token` —— **按裁定：有意不做**（原严重度：低，存疑）

**pi 侧**：`cli/auth-command.ts:18-22`（用法）、`main.ts:161-173`（解析凭据并打印，含刷新过期 OAuth）、`cli/credential-print.ts`。用途是给**外部客户端**取凭据。

**我们侧**：凭证页只在"填写 / 覆盖"方向工作（`PiCredentialScreen`、`PiCredentialService.save`），没有任何"显示已存密钥"的动作；`auth.json` 的写模式是 `0600`（`PiConfigFiles` 收紧不放宽）。

**裁定与理由**（已记进 §11 的对应行，不再算欠账）：凭据**只写不显示**是刻意的安全取舍；手机上本来没有"外部客户端"这个消费者，而把长期有效的 API Key / bearer token 明文打到屏幕上（或复制进剪贴板，Android 上任何应用都能读）是净增风险、不增能力。`print-bearer-token` 会顺带刷新 OAuth 令牌这一点，在终端页跑一次 `/login` 也能达到。**如果以后要做，只做 `print-bearer-token` 的"显示/复制"一种，且需要用户再确认一次。**

### 10.4 一致性缺陷 —— **本批次已修**（原状：不是欠账，但要修）

原来 `/login`（订阅登录）有**四处互相矛盾的说法**，其中三处由本报告点名：
- ~~`TerminalScreen.kt:72`：`"输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。"`（真的：`PtyLauncher.environment()` 带 `PI_CODING_AGENT_DIR`/`PI_CODING_AGENT_SESSION_DIR`，`:396-404`）~~
  → **§16 更正（F6）：这一行已经不存在。** `TerminalScreen.kt:28-38` 的 KDoc 写着
  "The explanatory sentence is gone, on purpose"（用户裁决「把这些小的说明文字删掉，光留最顶端的那两个字和返回按钮」），
  屏幕上只剩 `"终端"` 与 `"返回"`。**但实质结论不变**：`PtyLauncher.environment()` 仍然带
  `PI_CODING_AGENT_DIR`/`PI_CODING_AGENT_SESSION_DIR`（`:396-404`），所以终端页仍能跑 `/login`。
  同一行的过期引用还有两处：§1 的"原一致性缺陷已修"段、§6 的 `OAuth 登录` 表行（`TerminalScreen.kt:72`）—— 一并按此更正。
- `PiSettingsRegistry.kt:315-327`（`app.credentials.oauth` 行）说"订阅登录还没有入口"，注释还写着「终端不是可用面」。（**§16：现在在 `:353-372`，标题「订阅登录（在终端页里运行 pi）」，正文指向 工作区 → 终端。**）
- `PiSlashCommands.kt` 的 `/login`、`/logout` 提示语说"本应用没有对应入口"。（**§16：现在在 `:323-324`，指向终端页。**）
- **我上一轮漏报的第四处**：`PiRoot.kt` 里该行的动作答复也是"本应用没有入口"（点击后弹出的那句话）。（**§16：现在在 `:725-731`，指向 工作区 → 终端。**）

**本批次**：三处改成指向事实路径（`PiSettingsRegistry` 的标题与正文、`PiSlashCommands` 的
`login`/`logout` 两条、`PiRoot` 的动作答复），`PiSlashCommands` 的 KDoc 也按"哪里真的能跑"
重新分组（login/logout 与另外七条不再共用一句"没有入口"）。~~`TerminalScreen.kt:72` 本来就是
真话，**未改**。~~ → **§16 更正**：那句话后来被整段删掉了（`TerminalScreen.kt:28-38` 的 KDoc 记着原因），
所以不是"未改"而是"已不存在"；一致性本身仍在（四处都指向终端页这一条事实路径）。
**这一条判定不变：不是欠账**，逻辑、颜色、导航都没动，动作仍然只给一句通知，不跳转。

## 11. 手机不可用 / 有害（明确不做）

| 项 | pi 证据 | 为什么不做 |
|---|---|---|
| `pi update`（自更新，含托管安装与 npm/pnpm 两条路） | `package-manager-cli.ts:1022-1092`；`getSelfUpdatePlan` | App 钉死 `PI_VERSION`（`tools/fetch-runtime.mjs`，~~1.0.0~~ → §16 更正为 **1.3.0**）并按 revision 校验/解压载荷（`RuntimeProvisioner`）。让它自更新等于让被校验的产物自己变，`tools/pi-contract.mjs` 的全部断言（命令名、语义、扩展 API）会一起失效。**代码层还有一道**：`PiPackageUpdate.plan` 直接拒绝位置参数 `self`/`pi`（pi 把它们编译成自更新目标，`package-manager-cli.ts:534-536`），所以界面上不存在能触发它的路径 |
| `pi auth print-api-key` / `print-bearer-token` | `cli/auth-command.ts:18-22`；`main.ts:161-190`；`cli/credential-print.ts` | **有意不做**（本批次裁定）。凭据只写不显示是刻意的安全取舍：`auth.json` 由 App 以 `0600` 维护（`PiConfigFiles` 只收紧不放宽），而把长期 API Key / bearer token 明文显示或复制到剪贴板，在 Android 上任何应用都能读——净增风险、不增能力（凭证页 `PiCredentialScreen` 没有"显示已存密钥"，这是设计而非缺口）。这两条 pi 命令的用途是给**外部客户端**取凭据，手机上这个消费者不存在；`print-bearer-token` 顺带刷新 OAuth 令牌这一半，在终端页跑一次 `/login` 即可达到。**要重开的话只做 `print-bearer-token` 的显示/复制，并需再次确认** |
| `--session-id <id>` | `args.ts:125-127`、`main.ts:431-443` | **2026-10-04 更正：App 现在用它**（进程内重启钉回同一段对话，`PiSessionViewModel.restartEngine`），所以这一行不再属于「不做」；留着它是因为它有一个**必须绕开**的边：`findById` 按 cwd 过滤、且同 id 多份时取 `readdirSync` 顺序里的**第一个**（不是最新那份）——cwd 对不上或那个 id 在平铺层有歧义时改用 `--session <path>`（`app.pi.session.resumeTargetFor`，断言见 `session-resume` harness）。「手机不可用」的那半现在只剩「让用户手输一个 ID」 |
| `--print` / `-p` | `args.ts:157-163`、`main.ts:118-120` | 给脚本一问一答；App 的对话页就是交互面 |
| `pi experimental server` / `client`、`packages/protocol` | `cli/experimental/**`；`package.json:31-34` 把 `dist/cli/experimental`、`dist/experimental` 排除出发布物；`protocol.ts:5` `PROTOCOL_VERSION = 8` | **不在随包发出的产物里**，与我们的 RPC 协议是两套东西 |
| ~~`PI_RADIUS_GATEWAY` / `PI_SERVER_DIR` / `PI_SERVER_ID`、Radius 认证~~ | ~~`experimental/radius-auth.ts:8`、`experimental/server.ts:51-52`；`/share` 的 Radius 分支 `session-share.ts:49-...`~~ | ~~源码级实验特性，发布物里没有~~ → **§16 更正（F3）：这一行把三件事并成了一件，其中两件错了**（见下面两行） |
| `PI_SERVER_DIR` / `PI_SERVER_ID`、`pi experimental server\|client`、`@earendil-works/pi-{client,protocol,server}` | 0.85.1 在 `cli/experimental/**`、`experimental/server.ts:51-52` | **仍然不做，而且更彻底**：1.3.0 里这套东西**整体删除** —— `dist/experimental/` 与 `dist/cli/experimental/` 都不存在，`package.json` 的 `exports` 只剩 `"."` 与 `"./rpc-entry"`（`@earendil-works/pi-client`/`pi-protocol`/`pi-server` 也不再发布）。1.3.0 里 grep `PI_SERVER_DIR`/`PI_SERVER_ID` 零命中 |
| **`PI_RADIUS_GATEWAY`（Radius 网关地址）** | **1.3.0 的 `dist/core/radius.js:3`：`export const ENV_RADIUS_GATEWAY = "PI_RADIUS_GATEWAY";`**；Radius 是**随包 provider**（`dist/providers/data/radius.json` 在 42 份目录文件里），`/share` 的 Radius 分支在 `dist/modes/interactive/session-share.js:88`（`tryShareViaRadius`） | **不做，但理由变了**：它**在随包产物里**（不是"发布物里没有"）。不做是因为它是 OAuth 订阅 provider（`/login radius`，TUI 内建），手机上的入口是终端页那一条——与 `pi auth print-*`（§10.3）和 `/share`（下一行）同一类，不是"能力不存在" |
| `/share`（GitHub gist / Radius） | `interactive-mode.ts:3002`、`:6152`；`session-share.ts:46` | 需要 `gh` CLI 且是 TUI 内建；App 用"导出 + 系统分享"（`SessionExportDelivery`）覆盖用户真正的意图 |
| `--api-key`（一次性明文） | `args.ts:108-109`、`main.ts:806-815` | 会把明文带进进程参数表；凭证页写 `auth.json`/`models.json` 是正确通道 |
| `enableAnalytics` / `trackingId` | `settings-manager.ts:129-130`、`:1066-1079` | pi 里**写进去没有消费者**（本次 grep 全文确认，只有测试读 `getTrackingId`）。暴露一个没有作用的开关比不暴露更差 |
| `--exclude-tools` | `args.ts:142-146`、`sdk.ts:260-262` | 工具全集 = 内置 + 运行时扩展，设置页给不出准确清单（已记录在 `NOT_EXPOSED_PRE_SPAWN`） |

---

## 12. 附录 A：TUI 专属项（**不算欠账**，列出来是为了"知道漏了什么"）

三条来源：pi 自己标注的 RPC no-op、内建斜杠命令、TUI 组件。

**(a) pi 自己声明的 RPC 模式不可达**（`modes/rpc/rpc-mode.ts` 的 `ExtensionUIContext` 实现）：

> **§16 更正（F12）**：下表的 `pi 行` 列是 0.85.1 的 `rpc-mode.ts` 行号，**1.3.0 全部前移**。
> 每个格子里左边是原值、右边是 1.3.0 的 `dist/modes/rpc/rpc-mode.js` 实测值。**结论（TUI-only）不变。**

| 方法 | pi 行（原 → 1.3.0） | pi 的处置 |
|---|---|---|
| `onTerminalInput` | `:163-166` → `:97` | 返回空退订 |
| `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` | `:179-189` → `:111` / `:114` / `:117` | 空实现（要 TUI loader） |
| `setHiddenThinkingLabel` | `:191-193` → `:120` | 空实现 |
| `setWidget`（组件工厂形态） | `:195-208` → `:123` | 只支持字符串行，工厂被丢 |
| `setFooter` / `setHeader` | `:210-216` → `:137` / `:140` | 空实现 |
| `custom()` | `:228-231` → `:152-155` | 直接 `undefined`（**这条被 §16.5 引用：`/mcp` 的 `ctx.ui.custom` 因此什么都不做 → 那条 `notify` 按原样透传**） |
| `addAutocompleteProvider` / `setEditorComponent` / `getEditorComponent` | `:273-284` → `:194` / `:197` / `:200` | 空实现 |
| `setTheme` / `getAllThemes` / `getTheme` | `:286-301` → `:213-216` / `:207` / `:210` | `setTheme` 返回 `{success:false,error:"Theme switching not supported in RPC mode"}`（`:214-215`） |
| `getToolsExpanded` / `setToolsExpanded` | `:303-310` → `:217` | 空实现 |
| `pasteToEditor` | `:233-236` → `:156` | 退化成 `setEditorText` |
| `getEditorText` | `:248-252` → `:169` | 返回 `""` |

App 的处置：`TuiOnlyScan.kt` 扫扩展源码给出提示 —— 这是能做的极限（pi 不会报任何事件）。

~~**(b) 23 个内建斜杠命令**（`core/slash-commands.ts:19-43`；TUI 分派行见 `interactive-mode.ts`）：~~
→ **§16 更正：(b) 是 24 个**（1.3.0 的 `dist/core/slash-commands.js` 实测 24 个 `name:` 项）；
下表漏了 `bug`（0.86.1 加的，本文 §13 与 `PiSlashCommands.kt` 都收过它，只有这张表没跟上）。
**`TUI 行` 那一列仍是 0.85.1 的 `interactive-mode.ts` 行号，本轮没有逐条重定位**（1.3.0 的分派不再是一串
`case "<name>":`，所以那一列不能靠 grep 平移）；本轮只核准了**命令集合**（24 个，与 App 的两张表 7+17 逐名对齐）。

| 命令 | TUI 行 | App 状况 |
|---|---|---|
| `settings` | `:2970` | 底栏「设置」（有意不列为面板行） |
| `model` | `:2980` | 顶栏模型按钮 |
| `tree` | `:3042` | App 有树屏，但按钮是**分叉**（§5.4） |
| `thinking` | `:2986` | 输入框 ◐ |
| `scoped-models` | `:2975` | `enabledModels` 设置行 |
| `export` | `:2992` | 已接（HTML + JSONL） |
| `import` | `:2997` | 已接（文件选择器） |
| `share` | `:3002` | 不做（§11）。**§16 补**：1.3.0 多了一个目的地选择（`sessionShareDestination`，`dist/modes/interactive/session-share.js:50-57`），仍无 RPC 命令 |
| `bug` | —（**§16 新增行**） | 不做（pi 把报告发给 Pi 开发者；App 的 `DiagnosticsScreen` 是另一件事）——`PiSlashCommands.kt:314-318` 已收，`tools/pi-contract.mjs` 的 `tables` 组守着 |
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

**(c) 只在 TUI 里消费的 pi 设置键**（`core/settings-manager.ts:107-157` 里，App 的 ~~70 键~~ 中**故意没有**的那些）：`lastChangelogVersion`、`collapseChangelog`、`externalEditor`（Ctrl+G）、`quietStartup`、`tuiMode`、`fullscreenExitOutput`、`fullscreenScrollbar`、`fullscreenCopyOnSelect`、`editorPaddingX`、`outputPad`、`autocompleteMaxVisible`、`showHardwareCursor`、`doubleEscapeAction`、`treeFilterMode`、`markdown.{codeBlockIndent,mermaid}`、`warnings.anthropicExtraUsage`（`:4909`）、`terminal.*`（7 个，只有终端页里跑的原版 pi 会读）、`branchSummary.skipPrompt`（`:32`，摘要确认提示）。

> **§16 复核（数字更正 + 逐条重核）**：~~App 的 70 键~~ → 1.3.0 的实测口径是
> **59 个顶层设置键、App 钉住 40 个（含 `compaction.enabled` 这类嵌套写法）、30 个不暴露**，
> 由 `node tools/pi-contract.mjs --pi <1.3.0> --only=tables` 打印；
> 那 30 条在 `tools/pi-contract.mjs:1707` 的 `UNEXPOSED_SETTINGS_DECISIONS` 里逐条有决策行，
> 契约报 `0 stale`。**这 30 条的理由本轮逐条重核，全部仍然成立** —— 方法是从
> `dist/core/settings-manager.js` 精确抽出每个 getter 的方法体、取它读的键，再在 `dist/**/*.js`
> （排除 `bundle/`）里找调用者，按是否落在 `dist/modes/interactive/` 之外分组。
> 两个"越界"的调用者也查了：`showHardwareCursor` 还被 `dist/cli/config-selector.js` 与
> `dist/cli/startup-ui.js` 调（都是终端启动 UI），`externalEditor` 出现在
> `dist/core/keybindings.js:222` 只是动作 id 字符串 `"app.editor.external"`（读键的
> `getExternalEditorCommand` 只被 `interactive-mode.js:2218`、`:3772` 调）。⇒ 结论不变，仍是 TUI-only。
> 1.3.0 新加的顶层键只有一个：`sessionShareDestination`（`dist/modes/interactive/session-share.js:50` 是唯一运行期读者），已进上面那张决策表。

**(d) 其他 TUI 组件**：`core/footer-data-provider.ts`（页脚）、`core/cache-stats.ts` + `core/usage-totals.ts`（`/session` 面板的两块）、`core/keybindings.ts`（快捷键表）、`modes/interactive/components/**`。

---

## 13. 我们有意钉死（pi 有、手机可用，但**我们选择不做**）

> ~~`--flag` 这一类（扩展注册的 flag）**不在这张表里**，它没有理由记录，按欠账处理（§10.1）。~~
> → **§16 更正（F1 的第四处）**：这一类**已经接上了**（`app.extensions.args`，见 §1 与 §10.1），
> 所以它既不在欠账表里、也不必在这张表里 —— 它是"已接"。

| 项 | 证据 | 我们的理由（读到的） |
|---|---|---|
| `--session-dir` / `sessionDir` 键 | `PiPreSpawnConfig.kt:355-357`、`:181-188`（**§16 实测：现在是 `:251-257` 的 `COVERED_BY_PI_SETTING`**） | App 同时发 flag 和 env，设置里的键永远读不到 |
| `--no-extensions` | `:249-255`（**§16：`:144`**） | 会停掉 App 自己的 `android_*` 设备扩展 |
| `--no-skills` / `--no-prompt-templates` / `--no-themes` | `:256-274`（**§16：`:153` / `:160` / `:167`；另有设置行 `PiSettingsRegistry.kt:1540` / `:1552` / `:1564`**） | App 的技能/模板/主题屏读同一批目录，会自相矛盾 |
| `--exclude-tools`、`--no-session`、`--name`、`--verbose`、`--tui-mode`、`--api-key` | `:275-317`（**§16：`:318` / `:327` / `:334` / `:341` / `:347` / `:354`，都在 `NOT_EXPOSED_PRE_SPAWN`**） | 逐条理由在表里 |
| ~~`--no-tools` / `--no-builtin-tools`~~ | ~~**两张表都没收**（见 §14）~~ | ~~现状：`defaultTools` 行覆盖内置工具的选择；"全关"没有入口~~ |
| **`--no-builtin-tools`（已接，等价）** | **§16 更正（F5）**：`defaultTools: []` 与它**等价**（两边都是"内置为空 + 扩展工具仍在"：`dist/core/sdk.js:149`、`:160`、`:314`；`resolveDefaultTools([])` → `[]`，`PiQuickAdd.kt:68-77` 是它的转录） | 不需要单独一行 —— 它不是"有意钉死"，是"已有等价入口" |
| **`--no-tools`（有意钉死，裁定：不做）** | **§16 新增行**。证据：`dist/core/sdk.js:149`（`options.noTools ? [] : …`）、`:160`、`:314`（`!options.noTools`）；`grep` 全仓，`noTools` 的值**只从 CLI 来**，pi 没有对应设置键 | **不做**：`defaultTools: []` 关的是**内置**工具，扩展工具因 `includeAllExtensionTools: true` 仍然在位（`dist/core/sdk.js:314` 一带），而"连扩展工具一起关"没有实际使用场景。§14 第 2 条随之从"未确认"结案 |
| `cycle_model`（命令存在、无 UI） | `Commands.kt:133-141` | 用户裁决删除了溢出菜单入口；模型选择器已列出全部模型 |
| `get_messages`（命令存在、无调用） | `PiEngineApi.kt:61-63` | 压缩后与 `get_entries` 不同，用它替换会丢历史 |
| ~~`setStatus` 收到但不绘制~~ → **`setStatus` 已绘制（§16 更正，F4）** | ~~`PiSessionViewModel.onExtensionChrome`（`setStatus` 分支）+ `setExtensionStatus` 的 KDoc~~ → 现在是 `app/src/main/kotlin/app/pi/ui/chat/ChatSheets.kt:488-502`（`PiSectionHeader("扩展状态（N）")` + `ExtensionStatusLine`，其定义在 `:715`），入口是 ⋮ 菜单「会话与队列」→ `ChatScreen.kt:1808` `sheet = ChatSheet.Tools` → `:2718` `SessionToolsSheet` | ~~用户裁决 D-3 删除了扩展状态行；数据保留（"重新出现的成本是一个调用点"）~~ → **这条数据后来真的落了地**：D-3 的行被删过（`ce633e6`），又在 `9c547a1` 的实机反馈轮里以「会话与队列」sheet 末尾一节的形式回来（`git show c2a174b:…ChatSheets.kt` 里这段就在 `:488-502`，不是未提交改动）。**同时**：`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` 的 `setExtensionStatus` KDoc（复核时在 `:3202`）**至今仍写着**「**Nothing renders this today.** …the `ExtensionStatus` list has no composable any more.」—— **那句 KDoc 是错的**（同一轮里该文件仍在被别人改，行号已移到 `:3249` 一带，所以这里只钉符号名）。已由另一人分派修改，本文件只记事实 |
| 订阅登录（OAuth） | §10.4 | 无原生**表单**（终端页可达且是唯一入口）；文案已按事实统一（本批次） |
| `pi auth print-api-key` / `print-bearer-token` | §11 的对应行 | 不在这张表里，归 §11「明确不做」：凭据只写不显示是安全取舍 |
| **`pi mcp` 子命令组**（**§16 新增行**） | `dist/main.js:487`（`args[0] === "mcp"` 分派）；用法全文 `dist/extensions/mcp/cli.js:17-56`；子命令实现 `:142`（`list [--json]`）、`:152`/`:153`（`login`/`logout`）、`:211`（`add`）、`:324`（`remove`）。App 侧**没有**这一组的 argv 拼装（`grep -rn '"mcp"' app/src/main rpc/src/main --include=*.kt` 命中的全是 `mcp.json`/`mcp__`/`mcpServer` 字面量） | **不做 —— 用户裁定：不新增 MCP 相关的任何东西。** 事实是：MCP 的**运行时**一直是好的 —— 读 `mcp.json`（`app/src/main/kotlin/app/pi/settings/PiFiles.kt:98-108` 把它当"pi 会读、App 也能打开"的文件；`app/src/main/kotlin/app/pi/packages/ProjectTrust.kt:43-53` 把 `.pi/mcp.json` 纳入信任判据）、连服务器、注册并调用 `mcp__<server>__<tool>`（`app/src/main/kotlin/app/pi/ui/blocks/ToolCallBlock.kt:193-195`）。pi 只是把**管理**做成了 CLI 子命令；App 不提供 MCP 管理界面，配置走 `mcp.json`（文件屏可改）。**`pi mcp list --json` 等结构化能力因此不进 App。** 完整评估记录见 §16.5 |
| **`--no-tools`（连扩展工具一起关）**（**§16 新增行**） | `dist/core/sdk.js:149`（`options.noTools ? [] : …`）、`:160`、`:314`（`!options.noTools`）；`grep` 全仓，`noTools` 的值**只从 CLI 来**，pi 没有对应设置键 | **不做**：`defaultTools: []` 关的是**内置**工具，扩展工具因 `includeAllExtensionTools: true` 仍在（`dist/core/sdk.js:314` 一带的 session 构建），而"连扩展工具一起关"没有实际使用场景。§14 第 2 条随之从"未确认"结案 |
| **`--no-mcp`（一次运行关掉内置 MCP 扩展）**（**§16 新增行**） | `dist/main.js:637`（`disabledBuiltinExtensions: parsed.noMcp ? ["mcp"] : undefined` —— **唯一来源**）；唯一消费者 `dist/core/resource-loader.js:410`；内置扩展清单 `dist/extensions/index.js:5-11` | **不做 —— 理由同上一条的裁定：MCP 一律不动，官方传什么就原样透过来。** ⇒ 也不做"MCP 相关的新开关"。连带结论：pi 在服务器需要处理时发的那条 `notify` 按**原样透传**处理，见 §16.5 |

---

## 14. 未确认项（写明缺什么证据）

1. **扩展命令里 `ctx.reload()` 之后，App 的面板会不会刷新。** `rpc-mode.ts:341-343` 把它接到 `session.reload()`；这条路上 pi **不发任何事件**，App 只在 `AgentSettled` 时刷 `refreshCommands`（`PiSessionViewModel.onEvent`）。~~缺的证据：一次真机上"扩展命令 → reload → 看 `/` 面板"的实测（本次只读、无设备）。~~
   → **§16：机制已定论，现象仍缺一次实测。** 定论：1.3.0 的 `dist/core/agent-session.js:2969-3005` 的 `reload()` 全程**没有一次 `this._emit(...)`**，只发扩展事件 `_extensionRunner.emit({ type:"session_start", reason:"reload" })`（`:2999`），而扩展事件不走 `session.subscribe`、不进 RPC；App 的 `refreshCommands()` 只有 `PiSessionViewModel.kt:1418`、`:2764`、`:3068`（`AgentSettled`）、`:5776` 四个触发点。⇒ **面板不会自己刷，且这不是 App 能修的**（除非加轮询）。仍缺的那一步：真机上点一次扩展命令、看 `/` 面板（本次只读、无设备）。
2. ~~**`--no-tools` / `--no-builtin-tools` 是否真的没有 App 入口。**~~ → **§16：已核准（结案）。** `--no-builtin-tools` 与 `defaultTools: []` **等价**（`dist/core/sdk.js:149`、`:160`、`:314`），所以已有入口；`--no-tools` **无任何通道**（`grep '"--no-tools"' app/src/main rpc/src/main` 零命中，`noTools` 只从 CLI 进）。**裁定：不做**，理由记在 §13 的对应行（不再是欠账条目）。原文保留：~~我读了 `defaultTools` 行与 `app.security.emergencyStop`（`PiSettingsRegistry.kt:1330-1340` + `PiRoot.kt:751-756`，它是 `abort`+`clear_queue`+`abort_bash`，**不是**关工具），也确认 `PiPreSpawnConfig` 两张表都没收这两个 flag。缺的证据：产品意图记录（"要不要一个读-only 会话"）。功能上 `defaultTools: []` 能关内置工具；"连扩展工具也关"没有通道（`sdk.ts:258-262` 的 `noTools:"all"` 只在 CLI）。~~
3. **cache 浪费 / 按厂商成本拆分要不要复算。** 数据在 `get_entries` 里（每条 assistant 的 `usage`），pi 的算法在 `cache-stats.ts:138`/`usage-totals.ts:37`，而入口是 TUI 内建 `/session`（`interactive-mode.ts:6216-6222`）。我**没有**逐个语义核对这两段算法（例如"上一请求"的定义 `cache-stats.ts:107-147`），所以不主张"可以照抄"。缺的证据：`cache-stats.ts` 的逐行语义核对。
   → **§16：仍然是未确认项，本轮也没有做。** 只补了一句现状：1.3.0 里模块仍在（`dist/core/cache-stats.js:94` `computeCacheWaste`、`:102` `collectCacheMisses`、`:109` `detectCacheMiss`；`dist/core/usage-totals.js:41` `getUsageCostBreakdown`），**逐行语义仍未核对**。
4. **`pi auth check` 与 App 的"检测并扫描模型"是否在所有 provider 上等价。** ~~pi 的判定是"本地有没有可用凭据 + 能不能解析"（`cli/auth-check.ts`），App 的是"发一次 `GET /models`"。对无模型列举接口的厂商（例如只支持 chat 的自定义端点）两者可能给出不同结论。缺的证据：`cli/auth-check.ts` 逐分支阅读 + 一次真机对比。~~
   → **§16：已澄清，不是欠账。** 读了 1.3.0 的 `dist/cli/auth-check.js:5-35`：`checkProviderAuth` **完全本地** —— `modelRuntime.getProvider(provider)` → `modelRuntime.checkAuth(provider)`，`options.refresh` 时才 `modelRuntime.getAuth(provider)`，**不发网络请求**。App 侧有**两个并列的判据**：本地凭据 `PiCredentialService.kt:229` `credentialPresent = id != null && auth().hasAnyEntry(id)`（= pi 的"有凭据"），以及 pi 侧的 READY 计数 `ModelProviderScreen.kt:1060` `data.count(PiModelInventory.Status.READY)`（来自 `get_available_models`）。⇒ 两种语义**都被 App 显示**，"App 发一次 `GET /models`"只是它多出来的那一半，不是缺的那一半。
5. **`docs/rpc-coverage.md` 的"32/33 有入口"已过期**（`cycle_model` 现在没有 UI）。我按代码判定为 31/33 有真实发送方。**该文档在本批次已按代码改正**（`cycle_model` 行 + 命令小结 + 结论 §7），不再算未确认。
6. **`--session-id` / `--print` 的"手机不可用"分级**是我的产品判断（推断），没有代码依据可引。
7. **`setSessionName` 的规范化提示。** pi 的 TUI 在名字被规范化时会提示（`interactive-mode.ts:6211-6213`），RPC 只回 `success`；App 拿不到"被规范化成了什么"，只能重读 `get_state`。这是 pi RPC 的信息缺失，不是我漏读 —— 但如果以后要显示，需要先确认 `get_state.sessionName` 足够。**（§16 复核：1.3.0 的 `dist/modes/rpc/rpc-mode.js:523-530` 仍然只 `return success(id, "set_session_name")`，判定不变。）**

---

## 15. 本批次（在 §0 的 revision 之上）：改了什么、没验什么

### 15.1 符号清单

| 模块 | 改动 |
|---|---|
| `packages/PiPackageUpdate.kt`（**新**） | 纯逻辑：`Plan`/`plan`（含 `self`/`pi` 拒绝）、`words`（注入 quoting）、`canUpdate`/`skipReason`、`resultLine`、`fallbackSummary` |
| `packages/PiPackageService.kt` | `update(source, trust)`、`updateCommandLine(words, trust)`；`classify(..., timeoutMs)`；`restartRequirement(done, detail)`；`RESTART_DETAIL_INSTALL`/`RESTART_DETAIL_UPDATE`；结果行识别改用 `PiPackageUpdate.resultLine`（一个读者，三种动词） |
| `packages/GuestCommand.kt` | `UPDATE_TIMEOUT_MS`（10 分钟，与 install 同：这条命令对每个来源做一次网络工作） |
| `packages/PiPackagesHost.kt` | `PiPackagesController.update(entry)` / `updateAll()` / `runUpdate`；`finish(done, restartDetail)`；两个新回调接到屏幕 |
| `packages/PiPackagesScreen.kt` | 新参数 `onUpdate` / `onUpdateAll`；`UpdateAllRow`；列表头下的 `UPDATE_NOTE` / `UPDATE_NOTHING_TO_UPDATE`；行内「更新」按钮（不可更新的来源显示理由句而不是按钮） |
| `packages/PackageStrings.kt` | 6 条新字符串（`UPDATE_ALL_LABEL`、`UPDATE_LABEL`、`UPDATE_NOTE`、`UPDATE_NOTHING_TO_UPDATE`、`UPDATE_SKIP_PINNED`、`UPDATE_SKIP_LOCAL`、`UPDATE_REFUSED_SELF`） |
| `ui/settings/PiSettingsRegistry.kt` | `app.credentials.oauth` 的标题与正文改为指向终端页；KDoc 更正 |
| `ui/chat/PiSlashCommands.kt` | `login` / `logout` 提示语指向终端页；KDoc 把"没有入口"的说法收窄到另外七条 |
| `ui/PiRoot.kt`（**越界一处，见 15.4**） | 该行的动作答复改成"到 工作区 → 终端 输入 pi 后 /login"；注释更正 |
| `app/src/test/kotlin/app/pi/packages/PiPackageUpdateCheck.kt`（**新**） | 纯逻辑 harness（28 项断言），编译并跑绿（`harness: OK`），~~**尚未注册进脚本**（见 15.3）~~ → **§16 更正（F10）：已注册**（`tools/run-app-pure-checks.sh:274-276`，见 15.3） |
| `docs/rpc-coverage.md` | `cycle_model` 行按代码改正；命令小结 32/33 → 31/33 并写明两条不发送的理由；§7 结论两处同步 |
| `docs/pi-surface-audit-cli.md` | 本文件：§1 计数、§10.2/10.3/10.4、§11、§13、§14 第 5 条、新增 §15 |

### 15.2 零副作用：调用点普查

- `PiPackageService.update` 的调用点：**1 个**（`PiPackagesController.runUpdate`）。
- `updateCommandLine`：1 个（`update` 内部）。
- `PiPackageUpdate.*`：`PiPackageService`（plan/words/resultLine/fallbackSummary）、`PiPackagesController`（`canUpdate`、`ALL_WHAT`）、`PiPackagesScreen`（`canUpdate`、`skipReason`），以及新的 harness。
- `restartRequirement` 新增的 `detail` **有默认值**，唯一的旧调用点（`finish`）签名也扩了默认值 → install/remove 的行为与文案**一字未变**。
- `classify` 新增的 `timeoutMs` **有默认值**（`INSTALL_TIMEOUT_MS`），`mutate` 未改调用 → install/remove 的 TimedOut 文案不变。
- 结果行识别由原来的"两动词内联"改成 `PiPackageUpdate.resultLine`（**加了第三个动词 `Updated `**）。对 install/remove 的输入集没有变化：那两个动词仍优先匹配第一条。
- `PiPackagesScreen` 的 `PackageRow` 多了一个参数，唯一调用点是同一文件的列表循环；`UpdateAllRow` 是新私有 composable，无其他引用。
- `GuestCommand.UPDATE_TIMEOUT_MS` 是新增常量，不改任何既有常量。

### 15.3 harness 注册（`tools/run-app-pure-checks.sh` —— 本批次**没有**改它）

> **§16 更正（F10）：现在已经注册了。** `tools/run-app-pure-checks.sh:274-276` 有
> `run_harness pi-package-update \ app.pi.packages.PiPackageUpdateCheckKt \ …`。
> 下面这段"配方"因此是**历史记录**（当时只能手工跑），不是待办。

把下面这一块贴在 `run_harness packages …` 之后（本文件不拥有该脚本，故只给命令行）：

```bash
# app.pi.packages: `pi update` 的 argv 形状、self/pi 拒绝、哪些来源可更新，以及
# pi 自己的结果行识别（不能把 Updating 进度行当成结果）。纯 kotlin stdlib。
run_harness pi-package-update \
  app.pi.packages.PiPackageUpdateCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/packages/PiPackageUpdateCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageUpdate.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageSource.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PackageStrings.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiResourceDiscovery.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageModel.kt"
```

（最后两个文件是 `PackageStrings` 的签名里点名 `PiResourceDiscovery.Kind` 与
`PiBuiltinExtension.Presence` 才需要，不是新依赖。）

**本批次已经用上面这段配方手工跑过一次**（同一组文件、同一 `LIB_CP`/`KOTLINC_CP`，
`build/pure-checks/out-audit-*`）：编译零诊断、`harness: OK (all checks passed)`。
所以它是"验过、但还没写进脚本"的状态 —— 脚本本身不在本批次的所有权内，注册那一行留给它的主人。
这一跑同时证明了一件事：`PiPackageUpdate.kt` 与它引用的 `PackageStrings.kt` 是**真纯的**
（没有 `android.jar`、没有 Compose 也能编译），否则这一步会像设计那样失败。

### 15.4 越界说明（请求追认或回退）

任务给的边界里没有 `ui/PiRoot.kt`。但该文件里**还有同一处矛盾的第四份文案**（我在上一轮
审计里漏报了它）：`app.credentials.oauth` 行点击后弹出的通知原文是"订阅登录要在 pi 的原版 TUI
里做，**本应用没有入口**"。只改设置行的标题/正文、却让这一行的**点击结果**继续否认它，等于把
矛盾留在同一个控件上。所以我把那句话改成了与其余三处一致的事实路径（**一行字符串 + 一段注释，
没有动 `when` 的结构、没有动 `warning`、没有加导航**）。如果这条越界不被接受，回退
`ui/PiRoot.kt` 即可，其余改动不依赖它。

### 15.5 真机判据（更新一个已装包）

1. **能拿到新版本**：装一个非精确版本的 npm 包（例如 `npm:@scope/name` 或 `npm:pkg@^1.0.0`），
   在包管理页点该行「更新」→ 应看到 `pi update '<来源>'` 的 argv（日志卡）、pi 自己的
   `Updated <来源>` 行、以及"需要重启引擎"卡片；点「全部更新」应看到 argv 是
   `pi update --extensions`（**不是**裸 `pi update`）。
2. **git 来源**：`git:host/u/r` 应能更新；精确版本的 npm 与本地路径**行内没有按钮**，
   显示的是理由句。
3. **失败有话说**：断网或在 `settings.json` 里改掉来源后点更新 → 应看到 pi 的原文
   （`Error: …` / `No matching package found for …`），而不是空白或无提示。
4. **诚实句在场**：列表头下的说明必须在按钮**上方**（不是结果之后），且点完更新后
   `Done.Ok` 的行首句是 pi 的 `Updated …`，不是 App 自造的"已更新到新版本"。
5. **设置里那行现在指向终端页**：设置 → 模型与推理 → 凭证 → 「订阅登录（在终端页里运行 pi）」，
   标题与正文都指向 工作区 → 终端；点击后弹出的通知也指向同一个地方（不是"没有入口"）。
   在终端页真的跑 `pi` → `/login` → 会看到 pi 的登录界面（这同时验证文案说的是真的）。
6. **`/login` 手输**：在对话页输入 `/login` 回车 → 提示语应指向终端页，而不是"没有对应入口"。

### 15.6 未做 / 未验证

- **没有运行 Gradle / 没有跑 `:rpc:test` / 没有跑真机。** 静态检查只做了两项：
  `tools/typecheck.sh`（全 `:app` 主源码，唯一报错是**未改动的**
  `ui/settings/DiagnosticsReport.kt` 里 3 条 `unresolved reference 'BuildConfig'` —— 该脚本不生成
  AAPT2 的 `BuildConfig` 存根，是既有环境缺口，与本批次无关），以及 §15.3 那次手工 harness。
  **Compose 编译器插件的 composable 调用规则仍未验证**：新增的 `UpdateAllRow` 与改过的
  `PackageRow` 没有编译器背书（`typecheck.sh` 明确不跑该插件）。
- **新增 harness 没写进 `tools/run-app-pure-checks.sh`**（该文件不在本批次所有权内，且并发批次
  正在改它）。注册块与"已手工跑绿"的说明都在 §15.3。→ **§16 更正（F10）：已经写进去了**（`:274-276`）。
- **`pi update --models` 仍然没有入口**（有意：pi 在 RPC 启动时已后台刷新目录）。
- **"有没有新版本"仍然看不到**，且这是有证据的不可达（`checkForAvailableUpdates` 只有
  pi 的终端界面会调），不是没做；若以后要，得走终端页或自己实现一次 npm/git 版本比较
  （那会是新功能，不属于"1:1"）。
- **`--no-tools` / `--no-builtin-tools`、扩展 flag** 未动（前者仍在 §14，后者仍是 §10.1 的欠账）。
  → **§16 更正**：三件事现在都有结论 —— 扩展 flag **已接**（§10.1）；`--no-builtin-tools` **已有等价入口**
  （`defaultTools: []`，F5）；`--no-tools` **已核准不做**（§13）。
- **`ui/PiRoot.kt` 的越界改动没有被真机看过**（§15.4）：文案与设置行的指向一致，但
  "点一下那一行"的端到端只有真机能证（判据见 §15.5 第 5 条）。
<br>

---

## 16. 复核轮：本文档更新到 1.3.0 基线（`c2a174b`，工作树另有并发改动）

> **这一节是只读复核的产物**：逐条重判了 §1 / §10 / §11 / §13 / §14 的**全部**条目，
> 并把"官方能力面从 0.85.1 到 1.3.0 的增量"与 App 现有入口逐面对照（CLI flag 42 个 /
> RPC 命令 33 个 / 内建斜杠命令 24 个 / 扩展 `ExtensionAPI` 29 个去重成员 / 59 个顶层设置键与
> 30 条不暴露决策 / 23 个会话事件 + 3 个信封 + 12 个 delta）。
> **本轮没有改任何代码**；凡涉及代码注释的更正，本文只记事实、实现另派。

### 16.1 复核后的状态（一屏）

| 节 | 原结论 | 现在 |
|---|---|---|
| §1 / §10.1（扩展注册的 CLI flag） | 还剩 1 条真欠账 | **已补上**（0 条） |
| §1 / §10.2（包更新） | 已接上 | 已接上（harness 也已注册） |
| §1 / §10.3（`pi auth print-*`） | 按裁定不做 | 判定不变 |
| §10.4（订阅登录文案一致性） | 已修 | 实质成立（被引用的那行已不存在） |
| §5.4（`/tree` 跳转、标签写、`/reload`） | 协议边界，不是欠账 | **1.3.0 仍然 `navigate`/`label`/`reload` 各 0 次** |
| §11（明确不做 10 行） | —— | 9 行判定不变；`PI_RADIUS_GATEWAY` 那条**拆开重写**（F3） |
| §13（有意钉死 10 行） | —— | `--no-builtin-tools` 更正为"等价已接"；`--no-tools` 独立成行并裁定不做；`setStatus` 更正为"已绘制"（F4） |
| §14（未确认 7 条） | —— | 第 1 条**机制已定论**（端到端仍缺一次实测）、第 2 条**已核准**、第 4 条**已澄清**、第 3 条**仍未确认**；复核期临时加过第 8 条（`pi mcp list --json` 的输出形状），**随 `pi mcp` 判"不做"一并撤回** |
| **复核筛出的欠账候选** | —— | 三条 —— `pi mcp` 子命令组、`--no-tools`、`--no-mcp` —— **裁定全部不做**，入 §13；证据记录见 §16.5 |

### 16.2 本轮最有价值的一次实测：未注册的 `--flag` 会让引擎 `exit 1` —— **一个 App 缺陷（不是欠账），已在修**

**实测（1.1.0 / 1.2.0 / 1.3.0 三次，脚本见 §0）：**

```
echo '{"id":"x","type":"get_state"}' | node <pkg>/dist/bundle/rpc-entry.js --session-dir <dir> --bogus-flag
  → exit=1   stderr="Error: Unknown option: --bogus-flag"   stdout 空
对照（不带未知 flag）→ exit=0   stdout 有 get_state 的 success 响应
```

**当时的问题**：§10.1 那条 fix（`app.extensions.args`）自己的一处前提是错的 ——
`rpc/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt` 的 KDoc 原说未注册的 flag 只会得到
一条 `Unknown option` 诊断而**不会退出**（"so the app passes them and stays quiet"）；
代码侧对应的也是 `type: "error"`：`dist/core/agent-session-services.js:36-41` 推 error 诊断 →
`dist/main.js:744-755` 的 `hasRuntimeErrors` → `process.exit(1)`。
⇒ **用户在「扩展启动参数」那一行打错一个 flag 名（或那个 flag 的扩展被卸载/未启用），引擎起不来。**

**同一缺陷的第二半**：`app/src/main/kotlin/app/pi/engine/EngineExitCause.kt` 原把 `Unknown option`
单因归因为"打包的引擎与 App 的启动 flag 不一致，即没人对齐过的载荷变更"，用户可见文案因此说
「通常是运行时载荷与 App 版本不一致。**重装 App 一般能恢复**」—— 在 App 自己转发用户输入之后，
真正的原因可能只是那一行文本，重装 App 不会有用。

**现状（同轮内已被另一批改动修掉，本文记录即可，不再算待办）**：

| 面 | 现状 |
|---|---|
| `ExtensionFlagArgs.kt` 的 KDoc | **已修**：现在写着 `**For any other name pi does not stay quiet — it exits 1.**`（`:51` 一带），并带上实测三版的证据（exit 1 / stderr `Error: Unknown option: --bogus-flag` / stdout 空 / 对照组 exit 0） |
| `EngineExitCause.kt` 的退出诊断 | **已修**：`summary`/`detail`/`classify` 现在都收一个 `extensionArgs: String?`（`:85`、`:138`、`:151`），`Unknown option` 分支把它分成**两个来源两句话**（`:163` 的注释），`if (!extensionArgs.isNullOrBlank())`（`:169`）时直接点名「设置 → 扩展与资源 → 扩展启动参数」那一行，并明确说旧的"重装 App"说法对这一个来源无效 |
| `app/src/test/kotlin/app/pi/rpc/ExtensionFlagArgsCheck.kt` 的旁注 | **仍待补**（F8）：`:172-176` 那条断言仍是"未知 flag 名被接受"，没有把"pi 会因此 `exit 1`"写成它的已知后果 |

⇒ **这是缺陷，不是欠账**（不是"pi 有而 App 没接"），所以不进 §1 的计数。

> 行号说明：上面引的 `ExtensionFlagArgs.kt` / `EngineExitCause.kt` 行号是复核当轮读到的；
> 这两个文件在本轮被并发改动过（KDoc 与诊断都在改），所以只把**符号名 + 事实**当稳定引用。

### 16.3 被证伪的断言清单（F1–F12）

> 每一条都保留原句的痕迹并给出现在的证据。**这一节的价值一半在于"账本曾经写错过什么"。**

| # | 位置 | 原文（要点） | 现在的事实 | 判定 |
|---|---|---|---|---|
| **F1** | §1 的"还剩 1 条"、§10.1 全节、§13 首行注、§2 flag 表末行 | 扩展注册的 CLI flag **传不进去** | 六处证据齐全（§1 与 §10.1） | **文档写错**（4 处，均已就地更正） |
| **F2** | §3 的"不存在的子命令" | 列表里有 `pi mcp` | `dist/main.js:487` + `dist/extensions/mcp/cli.js:17-56`（5 个子命令，1.1.0 起就在）⇒ 评估记录 §16.5；**裁定不做**（§13） | **文档写错** |
| **F3** | §11 的 `PI_RADIUS_GATEWAY` / `PI_SERVER_DIR` / `PI_SERVER_ID` 行 | 三者并列"源码级实验特性，发布物里没有" | `PI_RADIUS_GATEWAY` 在随包产物里（`dist/core/radius.js:3`，且 `dist/providers/data/radius.json` 是 42 份目录之一）；`PI_SERVER_*` 与整套 experimental 已删 | **文档写错**（已拆成三行） |
| **F4** | §13 的 `setStatus` 行 + `PiSessionViewModel.setExtensionStatus` 的 KDoc（复核时在 `:3202`） | 「收到但不绘制」「Nothing renders this today」 | `app/src/main/kotlin/app/pi/ui/chat/ChatSheets.kt:488-502`（`ExtensionStatusLine` 在 `:715`）画在「会话与队列」sheet 里，**且这段在 `c2a174b` 就存在**（`git diff -U0 … \| grep '扩展状态'` 无命中） | **文档写错 + 代码注释写错**（**文档已改**；那句 KDoc 至今仍在，行号随并发改动移到 `:3249` 一带 —— **代码侧仍待修**） |
| **F5** | §2 的 `--no-builtin-tools` 行 | 「已接（**近似**）」 | 与 `defaultTools: []` **等价**（`dist/core/sdk.js:149`、`:160`、`:314` × `resolveDefaultTools([])`） | **文档写错** |
| **F6** | §10.4 + §1 + §6 引用 `TerminalScreen.kt:72` | 那句话"本来就是真话，**未改**" | 该句**已不存在**（`TerminalScreen.kt:28-38` 的 KDoc「The explanatory sentence is gone, on purpose」；屏幕只剩 `"终端"`/`"返回"`）；实质结论（终端页能跑 `/login`）仍成立 | **文档写错**（3 处引用） |
| **F7** | `app/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt` 的 KDoc（复核时在 `:48-52`） | 未注册的 flag「**without exiting** — so the app passes them and stays quiet」 | 三版实测都 `exit 1`；代码路径 `agent-session-services.js:36-41` → `main.js:744-755` ⇒ §16.2 | **文档写错（代码注释）** → **已在同轮修好**：KDoc 现在写 `For any other name pi does not stay quiet — it exits 1.` 并附实测；`EngineExitCause.kt` 的退出诊断也改成"两个来源两句话"、会点名「扩展启动参数」那一行 |
| **F8** | `app/src/test/kotlin/app/pi/rpc/ExtensionFlagArgsCheck.kt` 的断言（复核时在 `:164-169`，现在 `:172-176`） | 「no registration check: an unknown flag name is accepted」 | 断言本身正确（解析器就该接受），但它原与 F7 同一处 KDoc 的"不会退出"结论**一起**把"打错一个 flag 会杀掉引擎"排除了视野 | **断言正确 / 结论被证伪**；**代码侧仍待补**（应把"pi 会因此 `exit 1`"写成这条断言的已知后果） |
| **F9** | `tools/pi-1.1.0-upgrade-review.md:39` | `--no-mcp`「**不要**：App 不传这两个开关，工具选择走设置键 `defaultTools`」 | 理由讲的是 `--tools`/`--exclude-tools`，与 `--no-mcp` 无关（§13 的 `--no-mcp` 行 / §16.5） | **文档写错**（已在该文件原地标注） |
| **F10** | §15.3 与 §15.1 的 `PiPackageUpdateCheck` 行 | harness「尚未注册进脚本」 | `tools/run-app-pure-checks.sh:274-276` 已注册 | **文档写错**（过期） |
| **F11** | §0 的基线 | `/root/pi-src` @ `bbb61e3`、0.85.1；App `5a49bdc` | `/root/pi-src` 不存在；`/tmp/pi-src` 是空目录；App 在 `c2a174b`；引擎 1.3.0 | **文档写错**（已在 §0 更正，并写明 0.85.1→1.1.0 的盲区） |
| **F12** | §12(a) 的 `pi 行` 列 | `onTerminalInput :163-166` … `custom() :228-231` … | 1.3.0 全部前移：`:97` / `:111` / `:114` / `:117` / `:120` / `:123` / `:137` / `:140` / `:152-155` / `:156` / `:169` / `:194` / `:197` / `:200` / `:207` / `:210` / `:213-216` / `:217` | **文档写错**（结论不变） |

**本轮顺手更正的两处（同一类过期，未在 F 表里单列）**：
§3 的 `update` 行原写"**部分欠账**"，与 §10.2 的"已接上"自相矛盾 → 改成"已接"；
§12(b) 原写"23 个内建斜杠命令"且表里漏了 `bug` → 改成 24 并补行（该表 `TUI 行` 一列仍是 0.85.1 的行号，本轮未逐条重定位）。

### 16.4 本轮没做 / 未验证

- **没有运行 Gradle / 没有跑 `:rpc:test` / 没有跑真机**（本机无 Android SDK 与 Gradle 缓存，
  `tools/typecheck.sh` 必然 exit 2；无 kotlinc，任何 Kotlin harness 都跑不了）。
  本轮跑过的只有 §0 列出的那五类 Node/Python 取证。
- **0.85.1 的能力面本身无法复核**（树不在盘上）⇒ §0 的盲区仍在，`pi mcp` 是从盲区里挖出来的
  **一个例子**，同一时期可能还有别的 CLI 面没进本文。
- **`pi mcp list --json` 的真实输出**没跑 —— 但这条已经**不再需要**：`pi mcp` 那一族按裁定"不做"（§13），不需要知道它的输出形状。
- **`/mcp` 在 App 里的端到端表现**没在设备上验证 —— 同样**不再需要**：那条 `notify` 按**原样透传**处理（§16.5）。
- **cache 浪费 / 按厂商成本拆分的语义**仍未逐行核对（§14 第 3 条）。
- **1.3.0 的 `dist/bundle/**`** 只被间接使用（未知 flag 的实测跑的就是 `dist/bundle/rpc-entry.js`），
  没有逐 chunk 审 ⇒"未打包路径与打包路径行为完全一致"仍是假设。
- **§12(b) 的 `TUI 行` 列**没有逐条重定位（见上）。

### 16.5 复核筛出的三条欠账候选 —— **裁定：全部不做**（证据记录）

> 这一节是"评估过、为什么不做的记录"。三条都进 §13（有意钉死），**不进 §10**（§10 今天 0 条）。
> 记在这里是为了**不要让证据丢掉** —— 下一次有人问"MCP 管理面要不要做"时，能直接看到当时读到了什么。

**(a) `pi mcp` 子命令组 —— 不做（用户裁定：不新增 MCP 相关的任何东西）**

- pi 侧：`dist/main.js:487` `if (args[0] === "mcp") { const { runMcpCommand } = await loadMcpCommand(); process.exitCode = await runMcpCommand(args.slice(1), { cwd, agentDir }); return; }`；
  用法全文 `dist/extensions/mcp/cli.js:17-56`，**5 个子命令** —— `add <server> [options] -- <command> [args...]`
  或 `add <server> --url <url>`（`:211`）、`remove <server> [-l]`（`:324`）、`list [--json]`（`:142`）、
  `login <server> [--timeout <seconds>]`（`:152`）、`logout <server>`（`:153`）；
  选项 `-l/--local`、`--url`、`--env`、`--cwd`、`--header`、`--bearer-token-env-var`、`--oauth-client-id`、
  `--oauth-client-secret`、`--oauth-callback-port`、`--oauth-client-name`、`--exposure`、`--description`、`--json`、`--timeout`。
  它读 `~/.pi/agent/mcp.json` 与受信任项目里的 `.pi/mcp.json`。
- App 侧（运行时是好的）：`app/src/main/kotlin/app/pi/settings/PiFiles.kt:98-108`（`mcp.json` 是"pi 会读、App 也能打开"的文件）、
  `app/src/main/kotlin/app/pi/packages/ProjectTrust.kt:43-53`（`.pi/mcp.json` 进信任判据）、
  `app/src/main/kotlin/app/pi/ui/blocks/ToolCallBlock.kt:193-195`（认得 `mcp__<server>__<tool>`）、
  `rpc/src/main/kotlin/app/pi/rpc/ExtensionErrorText.kt`（转写 MCP 错误）。
  **没有**任何 `pi mcp` 的 argv 拼装。
- **为什么不做的理由（事实）**：pi 只是把**管理**做成了 CLI 子命令；MCP 的**运行时**（读配置、连服务器、
  注册并调用工具）一直是好的。App 不提供 MCP 管理界面 —— 配置走 `mcp.json`（文件屏可改）。
  用户裁定不新增 MCP 相关的任何东西（含 `list`/`add`/`remove`/`login`/`logout`，以及 `list --json` 的结构化读取）。
- **那条 `Run /mcp to fix.` 的 `notify` —— 原样透传，不做处理**（这是既定行为，不是待修项）：
  `dist/extensions/mcp/index.js:540` 在服务器需要处理时发
  `ctx.ui.notify("MCP servers need attention:\n…\nRun /mcp to fix.")`，这条 `notify` 经 `extension_ui_request`
  到 App（`rpc/src/main/kotlin/app/pi/rpc/Events.kt` 的 `notify` 分支 → `PiSessionViewModel.onExtensionChrome`），
  **App 原样显示**。相关的代码事实（保留作证据、不代表待办）：
  `dist/extensions/mcp/ui.js:177` 的 `/mcp` 用的是 `ctx.ui.custom(...)`，而 RPC 模式下 `custom()` 直接
  `return undefined`（`dist/modes/rpc/rpc-mode.js:152-155`，见 §12(a)）；登录那条更直白 ——
  `dist/extensions/mcp/index.js:942` `Signing in to MCP server "…" requires interactive mode.`。
  **结论：这是"官方传什么就让它传"的一个实例，App 不特殊处理、也不改写 pi 的文案。**

**(b) `--no-tools` —— 不做**

- pi 侧：`dist/core/sdk.js:149`（`const defaultToolNames = options.noTools ? [] : (settingsManager.getDefaultTools() ?? DEFAULT_TOOL_NAMES);`）、
  `:160`（`initialActiveToolNames`）、`:314`（`usesDefaultTools: (…) && !options.noTools`）。`grep` 全仓，
  `noTools` 的值**只从 CLI 来**，pi 没有对应设置键。
- 不做的理由：`defaultTools: []` 关的是**内置**工具，扩展工具因 `includeAllExtensionTools: true` 仍在；
  "连扩展工具一起关"没有实际使用场景。**注意 `--no-builtin-tools` 不在这条里** —— 它与 `defaultTools: []` 等价（F5），已有入口。

**(c) `--no-mcp` —— 不做**

- pi 侧：`dist/main.js:637`（`disabledBuiltinExtensions: parsed.noMcp ? ["mcp"] : undefined` —— 这是它的**唯一来源**）、
  唯一消费者 `dist/core/resource-loader.js:410`、内置扩展清单 `dist/extensions/index.js:5-11`
  （`llama.cpp` / `codemode` / `tool-search` / `mcp`，后三者 `replaceable: true`）。
- 不做的理由：同 (a) 的裁定 —— **MCP 一律不动，官方传什么就原样透过来**，所以也不做"MCP 相关的新开关"。
- **顺带更正一处旧文档**：`tools/pi-1.1.0-upgrade-review.md:39` 当时给 `--no-mcp` 记的"不要"理由是讲
  `--tools`/`--exclude-tools` 的（"走设置键 `defaultTools`"），**对 `--no-mcp` 不成立**（它没有设置键替代）。
  结论碰巧相同（都不做），但理由已在那个文件里原地更正（见 F9）。
