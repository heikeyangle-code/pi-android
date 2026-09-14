# 面账补审：终端与 Shell · 工作区与 @ 提及 · 设备能力

这份文件补上 `docs/surface-inventory.md` 末尾「本轮**没有**重新审的三块」。它不是待办清单，而是**覆盖账**：
每一面填出真相来源（`docs/pi-sourced-lists.md` 的规则）、判定、验收方式、未验证项。

判定只有四种写法：

- **`pi 有 <file:line>`** —— 事实在 pi 里，我们读它 / 问它 / 照抄它；
- **`pi 无对应物（App 的决定）`** —— pi 没有这个概念，所以只能由 App 自己定，且必须说得出为什么；
- **`pi 有但我们够不着（卡在哪）`** —— pi 有这个行为，但通道到不了，写明卡在什么上；
- **一致 / 有意偏离** —— 结论是「不必改」时用它，偏离要写出代价。

行号基准：本仓库工作树（2026-09；`docs/known-gaps.md` 不动），pi 为钉住的 `0.85.1`（`tools/fetch-runtime.mjs` 的 `PI_VERSION`，源码树 `/root/pi-src`，`bbb61e34`）。
**行号是写作时的**（同一棵树上还有别的改动在落地），每个引用旁边都给了符号名；行号对不上时按符号搜。

---

## ① 终端与 Shell

审的东西：`ui/terminal/**`、`runtime/PtyLauncher.kt`、`runtime/PtySession.kt`、`ui/settings/PiSettingsRegistry.kt` 的
`G_TERMINAL` 组（`shellPath` / `shellCommandPrefix` / `npmCommand` / `app.terminal.*`）。

### ①-1 终端里的 guest 环境与引擎是不是同一套

| 项 | 真相来源 | 判定 | 结论 |
|---|---|---|---|
| proot 环境变量（`PROOT_LOADER`/`PROOT_TMP_DIR`/`PROOT_L2S_DIR`/`LD_LIBRARY_PATH`） | App 自己的决定，proot 的约束（`runtime/PiRuntime.kt:226-256`，`ProotCommand.environment`） | **`pi 无对应物（App 的决定）`** | 一致：引擎与终端都调**同一个** `ProotCommand.environment`（引擎 `engine/PiEngineHost.kt:301`，终端 `runtime/PtyLauncher.kt:186`） |
| guest 侧的 `HOME`/`TMPDIR`/`LANG`/`PATH`/`TERM`/CA 变量 | 同上 | **`pi 无对应物（App 的决定）`** | 一致：都在 `ProotCommand.environment` 里，终端 `extra` 只**追加**不覆盖 |
| agent dir 绑定（`<files>/pi/.pi/agent` → `/root/.pi/agent`） | pi 读写的目录（`pi/config.ts:528-533` 的 `PI_CODING_AGENT_DIR`，回退 `homedir()/.pi/agent`） | **`pi 有 config.ts:528-533`** | 一致：引擎 `PiEngineHost.kt:289-298`、终端 `PtyLauncher.kt:166-183`、`pi install` 的 `GuestCommand.bindList()` 三处都绑，且 `PiAgentDirContract.bindsAgentDir` 把「host 源 + guest 目标」钉住 |
| `PI_CODING_AGENT_DIR` / `PI_CODING_AGENT_SESSION_DIR` | 同上 | **`pi 有`** | 一致：引擎与终端都设（`PiEngineHost.kt:306-307`、`PtyLauncher.kt:329-330`） |
| `PI_SKIP_VERSION_CHECK=1` | pi 的版本检查（`cli/args.ts:318` 一带的启动联网） | **`pi 有`** | 一致：两处都设（终端 `PtyLauncher.kt:376`） |
| 终端的额外键：`COLUMNS`/`LINES`/`PI_TUI_ESC_TIMEOUT`/`COLORTERM`/`PI_HYPERLINKS=0`/`PI_IMAGE_PROTOCOL=none` | pi 的终端探测与 `packages/tui/src/terminal.ts:481-487`（rows 为 0 时回退 `LINES`/`COLUMNS`） | **`pi 有（终端专属）`** | **有意偏离**：这些是「我们的终端是什么」的陈述，引擎进程不需要（它不画 TUI）。`PI_HYPERLINKS=0` / `PI_IMAGE_PROTOCOL=none` 是能力上限的诚实声明，不是配置 |
| 引擎的额外键之一：`PI_ANDROID_BRIDGE_FILE=/root/.pi/device-bridge.json` | 我们的扩展读它（`assets/pi-extensions/pi-android-bridge/client.ts:63-70`） | **`pi 无对应物`** | 一致（**不必改**）：扩展自己会回退到 `$PI_CODING_AGENT_DIR/device-bridge.json`，而 `DeviceBridgeController` **两个位置都写**（`bridge/DeviceBridgeController.kt:48` 与 agent dir 内那份）。终端不设它，桥在终端里照样可用 |
| 引擎的额外键之二：`PI_OFFLINE` / `PI_CACHE_RETENTION`（`rpc/.../PiLaunchOptions.kt:47-50`） | `pi 有`：`src/cli/args.ts:433`、`packages/ai/src/api/anthropic-messages.ts:57` | **`pi 有`** | **有意偏离，且是这块唯一有代价的一处**：见下 |

**有意偏离的代价，说清楚**：三个进程开关（`app.runtime.offline` / `cacheRetention` / `systemPrompt`）只作用于
**引擎进程**。用户在设置里打开离线模式，再在工作台终端里手打 `pi`，那个 `pi` 是**用户自己启动的新进程**，不继承这三个
开关（`PI_OFFLINE`/`PI_CACHE_RETENTION` 是环境变量，理论上能导出；`--system-prompt` 是命令行参数，导出不了）。
只导出前两个会让「终端里的 pi = 引擎里的 pi」这句话**看起来**成立而其实不成立，比两个都不导出更坏，所以**两个都不导出**，
并把这条写进本文件而不是留给下一个人去猜。用户可见的后果只有一条：终端里的 `pi` 走自己的默认值。**怎么测**：
设备上开离线模式 → 重启引擎 → 终端里 `env | grep PI_` 看不到 `PI_OFFLINE`；`pi --help` 里 `--offline` 说明的仍是进程参数。

### ①-2 `shellPath` / `shellCommandPrefix` 到底谁在管

| 键 | pi 的真身 | 谁消费 | 判定 |
|---|---|---|---|
| `shellPath` | `core/settings-manager.ts:122`（`Settings` 接口）、`:993-995`（`getShellPath`）；消费点 `core/agent-session.ts:2794`（建运行时）、`:3016-3023`（`executeBash` → `createLocalBashOperations({ shellPath })`） | **pi 的 bash 工具**（引擎进程内） | **`pi 有 agent-session.ts:3016-3023`** |
| `shellCommandPrefix` | `core/settings-manager.ts:125`、`:1025-1026`；消费点 `core/agent-session.ts:2793`（建运行时）、`:3015`（`executeBash`） | 同上（每条命令前拼一行） | **`pi 有 agent-session.ts:2793,3015`** |

**设计判断：这两个键不应该管我们的终端。** 理由三条，每条都能指到代码：

1. pi 把 `shellPath` 交给 `createLocalBashOperations`，那是 **`spawn(shell, ["-c", cmd])` 的非交互语义**；我们的终端要的是
   **交互式** shell（提示符、作业控制、`-i`）。同一个值不可能同时是这两者。
2. 这两个键的典型用法是**包装器**（pi 自己的注释就写着 Cygwin / `shopt -s expand_aliases`；本仓库的 `npmCommand` 同族）。
   把包装器拿来当终端的交互式 shell，轻则丢提示符，重则 `PtyLauncher` 的
   `stty rows …; exec script … -c "…bash -i"`（`runtime/PtyLauncher.kt:287` 的 `buildGuestCommand`）整条拼不出来。
3. 我们的终端今天是**写死的 `bash -i`**（`PtyLauncher.kt:382` 的 `DEFAULT_COMMAND`）。这不是遗漏：终端页的定位是
   「一个普通终端，`pi` 是里面可以敲的一个程序」（`PtyLauncher.kt:86-90`）。

**但界面文案原来有歧义**（原文：「自定义 shell 路径…」，挂在「终端与 Shell → Shell」下），用户完全可以读成「终端的 shell」。
已改：两行的说明现在明说「只影响 pi 执行的命令，不影响工作台终端」（`ui/settings/PiSettingsRegistry.kt:954`、`:966` 两行）。
`docs/surface-inventory.md:40` 那条判据（「`shellPath` 改了之后新开的终端用它」）按本次审查作废，正确判据是
「引擎的 bash 工具用它，终端不认它」。

### ①-3 `app.terminal.*` 是否都有消费者（本仓库把「注册了没人读」当 DEFECT）

| 键 | 消费者 | 判定 |
|---|---|---|
| `app.terminal.fontSize` | `ui/terminal/TerminalSettings.kt:80` → `TerminalPane` 的 `terminalGrid` / 组件 `initialFontSize` | **一致（已被 §I7 的收尾覆盖）** |
| `app.terminal.keyBar` | `TerminalSettings.kt:105-116` → `TerminalBarKey.byId`（`ui/terminal/TerminalKeyBar.kt:131-149`） | 同上 |
| `app.terminal.cursorStyle` / `scrollbackLines` | —— | **已删**（`6c2059a`，pi 无对应物：pi 唯一的游标键是 `showHardwareCursor`，`core/settings-manager.ts:147`；滚回缓冲归 libvterm） |

这一项**没有新缺陷**：`G_TERMINAL` 里 pi 无对应物的 App 键只剩上面两个，两个都有消费者，`settings-audit` harness 覆盖这条规则。

### ①-4 本次改掉的一处真问题（终端是唯一没建工作区就绑它的路径）

`PtyLauncher.prepare` 把工作区目录绑进 guest，却**不先创建它**；引擎在绑之前先 `workspace.mkdirs()`
（`engine/PiEngineHost.kt:259`）。终端的目录来自 `workspaceHost(context)`，而用户完全可能**先打开工作台、再打开对话**——
proot 不绑一个宿主侧不存在的源。**修改**：`runtime/PtyLauncher.kt:157` 加 `workspace.mkdirs()`，与引擎那一行同源。
**未验证（需要设备）**：装机后**不**先开对话，直接进「工作区 → 终端」，确认能进 shell 且 `pwd` 可用。

---

## ② 工作区与 @ 提及

### ②-1 `@` 列出的文件集与 pi 的是否一致

| 项 | 真相来源 | 判定 |
|---|---|---|
| 候选集怎么来 | **不是我们自己发明一套 ignore 规则**：`ui/chat/PiMentionSource.kt` 在 guest 里跑 fd，argv 由 `ui/chat/PiFileMentions.fdCommand` 逐字转写 pi 的 `walkDirectoryWithFd`（`packages/tui/src/autocomplete.ts:132-161`） | **`pi 有 autocomplete.ts:132-161`** |
| 同一个 fd **二进制**吗 | 是：App 把 fd 装进 `PiPaths.agentBinDir()` = `<files>/pi/.pi/agent/bin`（`runtime/PiRuntime.kt:62`，`RuntimeProvisioner.installTool`），而这正是 pi 自己的托管工具目录——`getBinDir()` = `<agentDir>/bin`（`pi/config.ts:562-564`），`getToolPath` 先查它（`utils/tools-manager.ts:10,83-91`），pi 的 `@` 用 `ensureTool("fd")`（`modes/interactive/interactive-mode.ts:976-980`） | **一致** |
| 触发/解析/打分/插入 | `PiFileMentions.prefixOf` ← `autocomplete.ts:467-482`；`parse` ← `:94-105`；`score`/排序 ← `:702-722`、`:762-785`；`buildCompletionValue` ← `:107-121`；`applyCompletion` ← `:412-429` | **`pi 有`** |

**两处有意偏离**（都写在 `PiFileMentions.kt` 的头注释里，且都有代价）：

1. **只跑一趟 fd**。pi 跑两趟：非递归一趟（`:733`，`maxDepth = 1`）+ 递归一趟（`:750`），再去重。我们只保留递归那趟。
   代价：当递归那趟在 100 条上限处截断、而某个直接子项只出现在第一趟时，我们会漏掉它。**未验证**：设备上造一个
   「目录里 100+ 文件、目标只是直接子项」的工作区，对照 pi TUI 的 `@` 列表。
2. **排序最后一级用 `String` 序**（pi 用 `localeCompare`，`:783`）：只在完全同分时不同，Kotlin 这边没有 ICU collator。

**验收**：harness `mentions`（`app/src/test/kotlin/app/pi/ui/chat/PiFileMentionsCheck.kt`）钉住 argv、打分与插入；
**新增**契约断言 `tools/pi-contract.mjs` 的 `checkMentionArgv`——它把钉住引擎的 `dist/` 里 pi 自己的 fd argv（`--type f/d`、
`--follow`、`--hidden`、三条 `--exclude .git*`、`--base-directory`/`--max-results`/`--full-path`）与 `fdCommand` 比对，
失败信息直接指向 `PiFileMentions.kt`。**为什么这条值得加**：`fdCommand` 是一条「因为 pi 是这么做的，所以我们这么做」的
**过滤规则**，正是 `docs/pi-contract.md:41` 的判据。**未验证**：CI 里 `contract` job 的下一次运行（本地用
`--pi <0.85.1 的安装目录>` 跑过这一段，通过）。

### ②-2 工作区路径映射：所有转写点

| # | 位置 | 原来干的事 | 现在 |
|---|---|---|---|
| 1 | `engine/PiEngineHost.guestPathFor`（private） | 宿主 → guest 的**规则本体** | 改成调 `GuestWorkspacePath.under`（`PiEngineHost.kt:601`） |
| 2 | `ui/PiSessionViewModel.guestWorkspace()` | **字面量** `/workspace/pi/workspaces/workspace-1` | 改成调同一个函数（`PiSessionViewModel.kt:684`） |
| 3 | `packages/AgentLayout.guestWorkspace` | 复写同一规则（root 取 `home.parentFile`） | 改成调同一个函数（`AgentLayout.kt:90`） |
| 4 | `bridge/DeviceWorkspace.guestSpellingOf` | 第二份复写（多两个分支） | 改成调同一个函数（`DeviceWorkspace.kt:142`） |
| 5 | `ui/PiSessionViewModel.defaultWorkspace()` | 字面量 `pi/workspaces/workspace-1` | 改成 `PtyLauncher.workspaceHost`（`PiSessionViewModel.kt:673`） |
| 6 | `runtime/PtyLauncher.workspaceHost` + `WORKSPACE_RELATIVE` | 宿主侧**规则本体** | 常量搬进 `GuestWorkspacePath.RELATIVE`，函数改成委托（`PtyLauncher.kt:293`） |
| 7 | `runtime/PtyLauncher.guestWorkspace = "/workspace"` | **同一宿主目录的第二个挂载点** | 保留，但变成具名常量 `GuestWorkspacePath.TERMINAL_GUEST_PATH`（`PtyLauncher.kt:307`），并且旧注释里那句「这就是 `PiEngineHost` 映射的路径」被改掉（它是错的） |
| 8 | `bridge/GuestPathMapping` 的 workspace 分支 | guest → host 的候选顺序（含两种拼法） | 保留：它是**反向**映射，且顺序是安全论据，由 harness `guest-paths` 钉住 |
| 9 | `bridge/DeviceWorkspace.GUEST_WORKSPACE_ROOT` | `/workspace` 第三份字面量 | 改成引用 7 的常量（`DeviceWorkspace.kt:165`） |

**能合并的已经合并成一处**：1–6、9 全部走 `runtime/GuestWorkspacePath.kt`（Android-free 纯函数），由扩展后的
harness `agent-tool-paths` 钉住（`app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt` 第 5 节）。

**不能合并的（7、8）是「一个目录两个挂载点」，不是同一规则的两次转写**：

```
engine    /workspace/pi/workspaces/workspace-1  == 工作区      (PiEngineHost / GuestCommand / fd)
terminal  /workspace                            == 同一个工作区 (PtyLauncher)
```

后果是具体的：在**终端**里，`/workspace/pi/workspaces/workspace-1` 是工作区里一个**不存在的子目录**（往里写就是建嵌套目录）；
在**引擎**里，`/workspace` 是一个空目录，**不是**工作区。`/app/health` 的 `workspace.guestPath` + `guestPathAliases`
（`DeviceBridgeRouter.kt:335-346`）把两种拼法都告诉模型，就是为了让它别猜。

**没有把两个挂载点对齐**，这是一个明确的取舍：对齐要动终端的 `-b` 目标（今天在设备上工作），
属于「必须有一次真机验证的行为变更」，而本次审查**不允许动手机**，所以只把差异变成具名常量 + 断言 + 本文件。
**怎么测（将来要合并时）**：设备上终端里 `ls -d /workspace /workspace/pi/workspaces/workspace-1` 应分别是工作区与 ENOENT；
合并后两者都应指向同一目录，且 `cd /workspace` 与对话里的 cwd 一致。

### ②-3 工作区里 pi 的 `.pi/` 与 App 读的是不是同一个目录

| 项 | pi 的真身 | 我们 | 判定 |
|---|---|---|---|
| 项目级 settings | `projectSettingsPath = join(resolvedCwd, CONFIG_DIR_NAME, "settings.json")`（`core/settings-manager.ts:233`、`:358-359`；`CONFIG_DIR_NAME = ".pi"`，`config.ts:504`） | `PiSettingsFileStore.forWorkspace(agentDir, workspace)` 的 `projectFile = File(workspace, ".pi/settings.json")`（`settings/PiSettingsFileStore.kt:160-163`），两个调用方都用同一个 workspace：`PiSessionViewModel.settingsStore`（`:371-375` → `defaultWorkspace()`）与 `ui/terminal/TerminalSettings.terminalSettingsStore`（`:61-64` → `PtyLauncher.workspaceHost`），二者现在同源 | **一致** |
| 引擎的 cwd | 引擎用 `guestPathFor(workspace)` 当 cwd（`PiEngineHost.kt:260,287`） | 同一个工作区宿主目录 | **一致** |
| `pi install -l` 写 `<cwd>/.pi`、`trust.json` 的键 | `core/package-manager.ts`（install 的项目作用域）、`ProjectTrust` | `AgentLayout.hostProjectConfigDir()` = `<workspace>/.pi`；`GuestCommand` 的 cwd 用 `layout.guestWorkspace` | **一致** |
| 终端里手敲 `pi` 的项目目录 | —— | 终端 shell 的 cwd 是 `/root`（`PtyLauncher.kt:164`），所以用户在终端里直接敲 `pi` 时，pi 的项目目录是 `/root/.pi`，**不是**工作区的 `.pi` | **有意偏离**：终端是「一个 shell」，cwd 由用户决定；这不是 App 能替它决定的。要拿工作区项目设置，用户得先 `cd /workspace`（见 ②-2 的挂载点差异） |

---

## ③ 设备能力面

### ③-1 `app.device.*` 与真实授权是否仍可能矛盾

**先说结论：这一条已被覆盖，现在由 `DeviceCapabilityStore` 单独保证。**

| 项 | 真相来源 | 谁保证 | 判定 |
|---|---|---|---|
| 权限开关 | `DeviceCapabilityStore`（SharedPreferences，`bridge/DeviceCapabilityStore.kt:58-135`） | 它自己 | **`pi 无对应物（App 的决定）`**：pi 不知道手机，这套授权只能由 App 定 |
| 设置目录里的第二份开关 | —— | —— | **已删**：7 条 `app.device.*` 与 `G_DEVICE` 组已从注册表移除，删除点留了注释（`PiSettingsRegistry.kt:1055` 起），设置首页写明唯一权威（`ui/settings/SettingsHome.kt:99-101`）。`grep -rn "app\.device\." app/src` 现在只剩注释 |
| `/app/health` 报告的能力 | 同一个 store：`capabilityArray()` = `store.states()`（`DeviceBridgeRouter.kt:378-389`），并附 `accessibilityRunning` / `screenshotSupported`（API≥30）/ 三个运行时权限位，全部来自 store 或系统查询（`DeviceBridgeRouter.kt:315-360` 的 `health()`） | 同一个 store | **一致** |
| 设备能力页 | `ui/device/DeviceCapabilityScreen.kt` 的开关、权限按钮、审批卡全部读 store（`:108-120`、`:248-304`、`:341-345`），不读第二份键 | 同一个 store | **一致** |
| 扩展里的危险工具门（`danger.ts` 的等级表 + `shellPrecheck`） | 见 ③-2 | Kotlin 侧是强制方 | **一致，但原来是两份表** |

补充：`/app/health` 的 `capabilities[].usable/reason` 与端点 `withCapability(group)`（`DeviceBridgeRouter.kt:297-304`）读的是**同一个** `store.check(group)`——
「页面显示开关打开了」与「端点放行」不可能各说一套。

### ③-2 两份表（`DANGEROUS_TOOLS`/`shellPrecheck` ↔ `DeviceShellGuard`）

| 表 | 谁的真身 | 不一致时谁赢 | 处置 |
|---|---|---|---|
| `danger.ts` 的 `DANGER_LEVELS` / `DANGEROUS_TOOLS` | **TS 独有**：Kotlin 侧没有「危险等级」表，逐次确认这件事完全在扩展里（`assets/pi-extensions/pi-android-permission-gate.ts`）。未声明的 `android_*` 名字按 `dangerous` 处理（`danger.ts:dangerLevelOf`，fail closed） | 不适用（没有第二份） | **`pi 无对应物（App 的决定）`**；新增检查：注册的工具集合 == 声明的等级集合（见下） |
| `danger.ts` 的 `FORBIDDEN_SHELL_PATTERNS` / `shellPrecheck` | **Kotlin 的 `DeviceShellGuard.hardBlocks`（12 条）**（`bridge/DeviceShell.kt:281-330`）；放宽模式的语法检查由 `inspect` 直接做（`:349-358`） | **Kotlin 赢**：`/app/shell` 每次调用都在 App 进程里过 `DeviceShellGuard.inspect`（`DeviceBridgeRouter.kt:223-240`），扩展拦不住也放不过；TS 那份只是「别为一件**永远不可能被允许**的事弹确认框」的预过滤 | **原来是「两份表 + 一句注释」，现在有机器检查** |

**新增的检查**：`app/src/test/kotlin/app/pi/bridge/ShellPolicyMirrorCheck.kt`，bare-JVM harness `shell-policy-mirror`
（`tools/run-app-pure-checks.sh` 的清单里，紧跟 `guest-paths`）。它把两个文件当**源文本**读（Kotlin 的守卫 import
`android.os.Process`，在裸 JVM 上编译不了；`settings-audit` 读注册表用的是同一个办法），断言四件事：

1. 两份清单**条数相同**；
2. 每条**字面 token 序列相同**：先把每条正则归约成它点名的字面 token——`\b`/`\s` 这类**操作符**转义是边界（token 到此为止），
   其它转义是它保护的那个**字面字符**（`\/` 是斜杠，`\.` 是点）。于是 `\bmount\b` 与 `(^|[\s;&|()])mount(\s|$)` 都归约成 `mount`，
   Kotlin 的 `/dev/block` 与 TS 的 `\/dev\/block` 都归约成 `dev` `block`；TS 的 `i` 标志不参与比较；
3. 两侧都**没有** `\b` 紧接 `/` —— 这正是当初让块设备规则在 TS 侧变成死规则的写法（`\b` 在空格与 `/` 之间永远不匹配，
   所以 `/\/dev\/block\b/` 一次都没匹配过）。当时是**人工**发现的，现在构建能发现；
4. `index.ts` 注册的 `android_*` 工具集合 == `DANGER_LEVELS` 的键集合（两边都是 21 个，见 D32）。

**这条检查不保证什么**（免得绿灯被读多）：它比的是**规则文本**，不是行为；「同一个命令两边判得一样不一样」需要两个正则引擎，
而 TS 那个只活在 pi 进程里。

### ③-3 残留：门不查能力开关（记账，未改）

`permission-gate.ts` 决定「要不要弹确认框」时只看三件事：是不是 `android_*`、等级是不是 `dangerous`、`ctx.hasUI`。
它**不读** `/app/health` 的能力开关。后果：`Shell` 组关着时，模型调 `android_shell`，用户仍会被问一次，
批准之后端点才回 `[DISABLED]`。**不是授权矛盾**（门从不放行任何东西，端点才是强制方，且 fail closed），
只是**一次多余的确认**。没有改的原因：要按能力组提前拒绝，需要一张「工具 → 能力组」的表，而今天这张表**不是数据**——
它是 `DeviceBridgeRouter.kt` 里每条路由上的 `withCapability(DeviceCapability.X)`（`:45-275`）。凭空在 TS 里抄一张就是
**第三份表**，与 `docs/pi-sourced-lists.md` 的规则相反。正确修法是先把路由表变成数据（`Map<String, DeviceCapability>`），
再让 `/app/health` 顺带发表；那是一次独立的路由重构，**不在本次审查的范围**。
**怎么测（现状）**：关掉「Shell」→ 让模型跑一条 `android_shell` → 设备上应**先**看到确认框，批准后拿到 `[DISABLED]` + 中文原因。

---

## 本次改动清单（全部可 `grep` 到）

| 文件 | 改动 | 依据 |
|---|---|---|
| `app/src/main/kotlin/app/pi/runtime/GuestWorkspacePath.kt` | **新增**：工作区宿主/guest 拼法的唯一实现（Android-free 纯函数） | ②-2：五个转写点 |
| `app/src/main/kotlin/app/pi/engine/PiEngineHost.kt:601` | `guestPathFor` 改为委托 | 同上 |
| `app/src/main/kotlin/app/pi/runtime/PtyLauncher.kt:157` | 绑之前先 `workspace.mkdirs()` | `PiEngineHost.kt:259` 的同一条 |
| `…/runtime/PtyLauncher.kt:293,307` | 宿主路径取共享常量；终端挂载点具名；**改掉一句错的注释** | ②-2 表格第 7 行 |
| `…/packages/AgentLayout.kt:90` | `guestWorkspace` 改为委托 | ②-2 第 3 行 |
| `…/bridge/DeviceWorkspace.kt:142,165` | 规则改为委托；常量引用共享值 | ②-2 第 4、9 行 |
| `…/ui/PiSessionViewModel.kt:673,684` | 两处字面量改为委托 | ②-2 第 2、5 行 |
| `…/ui/settings/PiSettingsRegistry.kt:954,966` | `shellPath`/`shellCommandPrefix` 的说明写明**不管辖工作台终端** | ①-2 |
| `app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt` | 第 5 节：工作区拼法与「两个挂载点确实不同」的断言 | ②-2 |
| `app/src/test/kotlin/app/pi/bridge/ShellPolicyMirrorCheck.kt` | **新增** harness `shell-policy-mirror` | ③-2 |
| `tools/run-app-pure-checks.sh` | 两处清单登记（`agent-tool-paths` 加一个源文件；新增 `shell-policy-mirror`） | 同上 |
| `tools/pi-contract.mjs` | 新增 `checkMentionArgv`：钉住 pi 的 fd argv | ②-1 |
| `app/src/main/assets/pi-extensions/pi-android-bridge/danger.ts` | 注释补上「两份清单由 harness 机械比对」（资产指纹会自动重装，`DeviceBridgeController.kt:252-260`） | ③-2 |

**没有改**：pi（`/root/pi-src` 只读）、载荷（`assets/runtime/**`、`runtime.lock.json`）、`docs/known-gaps.md`、任何 git 写操作、Gradle。

**自检**（本机唯一能跑的测试方式；结果如实记，含**不属于本次改动**的红）：

- `python3 tools/check-nested-comments.py` → `nested-comments: OK (169 Kotlin file(s) scanned)`，0 处。
- `bash tools/run-app-pure-checks.sh` → **12 个 harness 里 11 个 `harness: OK`**，包括本次新增/扩展的三个
  （`shell-policy-mirror` 新增、`agent-tool-paths` 第 5 节新增、`settings-audit` 读了被改文案的注册表）。
  唯一红的是 `models-inventory`，**编译失败在 `app/src/test/kotlin/app/pi/packages/PiModelInventoryCheck.kt:272`、`:286`**
  （`cannot infer type for type parameter 'T'`）——那个 harness 是同一棵树上**另一个改动**刚加的，本次一行没碰。
- `bash tools/typecheck.sh` → `:rpc 0`；`:app` 剩 **2 条**，都在
  `app/src/main/kotlin/app/pi/ui/settings/PiModelsScreen.kt:213`、`:217`（`no 'get'/'set' operator method providing
  array access`，`expanded[...]` 那个 `SnapshotStateMap`），**不在本次改过的任何文件里**。两次运行之间 `:app` 的错误
  从 4 条降到 2 条（另一条在 `PiPackagesScreen.kt` 消失），说明这是并发改动中的中间态；本次改动的文件在两次运行里
  **都是 0 条诊断**（第一次唯一属于我的是 `PiEngineHost.kt` 少一行 import，已修）。
  **所以 `typecheck` 的「`:app 0`」这条自检要求本机现在拿不到，但挡住它的不是本次改动。**

  **`PiModelsScreen` 那两条为什么很可能是环境、不是代码**（留给下一条线去核实，本次不动别人的文件）：
  ① 那个文件与 `HEAD`（`bd7fd02`）逐字一致；② 同一个 `SnapshotStateMap` 在
  `ui/settings/PiSettingsStore.kt:47`、`:50` 上索引却不报错；③ Gradle 缓存里**同时有两个 compose-runtime 版本**
  （`androidx.compose.runtime:runtime-android:1.12.1` 与 `:1.8.3`），`tools/typecheck.sh` 把缓存里**所有** jar/aar 都塞进
  classpath，谁赢由 `find` 的顺序决定——它自己的头注释说「resolution order matches what Gradle would pick (newest version
  wins)」，但那只对 `build/typecheck/extra` 成立，缓存**内部**的同名不同版本没有排序。`:app` 的红是否随之漂移，值得一次
  独立核实（例如在脚本里按版本号排序、或剔掉旧版本 AAR）。

---

## 未验证项汇总（都要设备或 CI）

| 项 | 怎么测 |
|---|---|
| 终端在「没先开对话」时装机的首次启动 | 装完直接进「工作区 → 终端」，应进 shell、`pwd` 有输出（①-4） |
| 终端里的 guest 环境与引擎同一套 | 终端里跑 `env \| sort > /sdcard/t.txt`；再让 pi 用它自己的 `bash` 工具跑同样的命令写到另一个文件；两个文件里 `PATH`/`HOME`/`TMPDIR`/`LANG`/CA 变量/`PROOT_*` 应逐字相同，差异只允许出现在 ①-1 列出的那几个键上 |
| `shellPath` 只作用于 pi 的工具 | 设成 `/bin/sh` → 新会话让模型 `echo $0`；终端里 `echo $0` 仍应是 `bash`（①-2） |
| `@` 与 pi TUI 的列表逐条一致 | 同一工作区（含 `.gitignore`、`.ignore`、隐藏目录、目录 + 文件混合）里对照 pi TUI 的 `@` 与 App 的 `@`；再测 100+ 文件的截断边界（②-1 的偏离 1） |
| 工作区两个挂载点的现状 | 终端 `ls -d /workspace /workspace/pi/workspaces/workspace-1`（第一个是工作区，第二个 ENOENT）（②-2） |
| 终端里手敲 `pi` 的项目设置目录 | 终端 `cd /workspace && pi`，改一个项目级设置，看它落到工作区 `.pi/settings.json` 还是 `/root/.pi`（②-3） |
| `shell-policy-mirror` 的失败信息真的可读 | 故意在 `danger.ts` 删一条 pattern，跑 harness，确认它打印两侧的正则源与「该回去读哪个文件」（③-2） |
| `contract` job 里新增的 `checkMentionArgv` | CI 的 `contract` job；本地：`node tools/pi-contract.mjs --pi <安装目录>`（②-1） |
| 门的「多问一次」现状 | 关掉「Shell」→ 模型跑 `android_shell` → 先确认框、批准后 `[DISABLED]`（③-3） |
