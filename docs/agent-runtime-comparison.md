# Agent · 运行时 · 容器：源码级对比（A=我们 / B=Operit / C=rikkahub-agent / D=OpenMinis）

> 取证方式：外部仓库浅克隆到 `/tmp/cmp/*`（部分因网络不稳退化为 `raw.githubusercontent.com` 单文件抓取 + GitHub API 目录树），只读；我们的仓库只读。
> 规则：**每条结论后面跟 `仓库/文件:行` 或符号名**。README 里的话单独标注为「README 说法」，不当实现证据。没读到的写「未确认」。

---

## 0. 结论先行（三句话）

1. **Agent 循环**上，A 用的是上游第三方引擎（pi `packages/agent/src/agent-loop.ts`），B 自研了一套「递归式」循环（`EnhancedAIService.sendMessage → processStreamCompletion → handleToolInvocation → processToolResults → 再发请求`），C 自研了一个显式的 `for (stepIndex in 0 until maxSteps)` 循环；C 的循环最像"A 的循环 + 我们自己没加的那几道保险"（步数上限、墙钟上限、审批落库与重放保护）。
2. **运行时/容器**上，四家都是**同一族技术**：用户态 PRoot 家族 + 设备内 Linux rootfs，都不需要 root；差别在 rootfs 是 Ubuntu（A、B）还是 Alpine（D），以及绑定表/回收策略谁写得更死（A 把绑定表和两个 runtime 的差异集中在一个 `GuestRecipe` 里并逐条注释）。
3. **A 的真正欠账不在容器，而在"B/C 有而 pi 有对应物、我们还没接上"的那几件**——目前看主要是 **MCP、子代理/任务分解、per-tool 审批交互**；而 B/C 的大量能力（工作流、消息通道机器人、虚拟显示、设备全家桶工具）**在 pi 里根本没有对应物**，按站内规则属于"不做"。

---

## 1. 对比对象定位（一句话各自）

| | 定位 | 语言/许可证 | star | 默认分支 | 最后推送 | 仓库体积 |
|---|---|---|---|---|---|---|
| **A** | 内嵌**官方未改动的 pi CLI 0.85.1** 的 Android 壳；引擎第三方，界面自绘 | Kotlin（App）+ TypeScript（引擎） | 私有仓库 | — | — | 我们侧 `app/src/main` + `rpc/src/main` 共 263 个 `.kt`，85,923 行 |
| **B** | 自研全栈 Android Agent（"手机助手"），工具/模型/容器/UI 全自己写 | Kotlin / LGPL-3.0 | 7,942 | `main` | 2026-09-18 | ~576 MB |
| **C** | RikkaHub 聊天客户端 + Full agent mode（自研循环 + 自己的 `workspace` 容器模块） | Kotlin / AGPL-3.0 | 283 | `master` | 2026-09-11 | ~79 MB |
| **D** | OpenMinis：iOS 为主 + `src/android` 原生 Android 版；RikkaMinis 是其 Android fork | Swift（iOS）+ Kotlin（Android）/ GPL-3.0 | 4,537（RikkaMinis 7） | `main` | 2026-09-01（RikkaMinis 2026-09-18） | ~35 MB（RikkaMinis ~85 MB） |
| **E** | **名字有歧义，判为"都不是"** → 见 §9 | — | — | — | — | — |

pi 侧版本锚点：`pi-src` HEAD = `bbb61e34aaf231639fdaaad1adbd757947034eac`（`git log -1`，2026-09-10）。

---

## 2. 维度一：Agent 循环

| 维度 | A（我们 / pi） | B（Operit） | C（rikkahub-agent） | D（OpenMinis） |
|---|---|---|---|---|
| 谁拥有循环 | **第三方引擎**：pi。App 自己不跑循环，只做 RPC 驱动 | **自研** | **自研** | 未确认（Android 侧未定位到 loop 文件，见 §8） |
| 循环在哪 | `pi-src/packages/agent/src/agent-loop.ts:156` `runLoop()`；薄封装 `:121` `runAgentLoopContinue()` | `Operit/app/src/main/java/com/ai/assistance/operit/api/chat/EnhancedAIService.kt:903` `sendMessage` → `:1712` `processStreamCompletion` → `:2072` `handleToolInvocation` → `:2200` `processToolResults` → `:2337` 再发请求 → `:2458` 再进 `processStreamCompletion` | `rikkahub-agent/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt:440` `generateText()`，循环体 `:506` `for (stepIndex in 0 until maxSteps)` | `OpenMinis/src/android/.../app/agent/ToolLoopDetector.kt`、`InterruptedTailDetector.kt` 存在，但主循环文件未找到 |
| 一轮怎么串 | 内层 `while`（`:175`）内：`streamAssistantResponse`（`:279`）→ 过滤 `toolCall`（`:222`）→ `executeToolCalls*` → 结果入 context（`:237-240`）→ `turn_end` → 回到循环顶部再问；外层 `while (true)`（`:171`）负责 follow-up | **递归**：`handleToolInvocation` 里 `launch` 一个 job，执行完工具后调 `processToolResults`，后者再次 `serviceForFunction.sendMessage(...)`（`:2337`）并再进 `processStreamCompletion`（`:2458`） | 显式 for：每步先检查墙钟/loop guard，再 `streamText`，再按 `approvalState` 执行工具，工具结果写回 messages，进入下一步 | 未确认 |
| 并行工具调用 | **有**，且显式二选一：`executeToolCallsSequential`（`:431`）与 `executeToolCallsParallel`（`:487`），后者在 `:547` 用 `Promise.all` 并发、结果保序；由 `AgentTool.executionMode`（`pi-src/packages/agent/src/types.ts:411`）决定 | **有**：`ToolExecutionManager.executeInvocations`（`Operit/.../api/chat/enhance/ToolExecutionManager.kt:514`）用 `coroutineScope` + `async`（`:635`）+ `awaitAll`（`:660`） | **未确认**（读到的 `GenerationLoop` 是顺序 for，未读到并行分支） | 未确认 |
| 子代理 / 任务分解 | **pi 里没有**：`grep -ri subagent packages/coding-agent/src` 无命中 | **有**：`core/tools/agent/PhoneAgent.kt`（手机 Agent）、`ToolRegistration.kt` 里的 `send_message_to_ai` / `call_chat_model` 等 | **有且最完整**：`subagent/SubAgentEngine.kt`（580 行）、`SubAgentRegistry.kt`、`SubAgentTools.kt`，测试覆盖超时/等待/校验（`app/src/test/.../SubAgentTimeoutStopTest.kt` 等） | 未确认 |
| 计划模式 | **pi 里没有**（`grep -ri "planMode\|\"plan\"" packages/coding-agent/src` 无命中） | 未确认 | 未确认 | 未确认 |
| 回合中断 / 重试 | 中断：`AbortSignal` 贯穿 `runLoop`（`agent-loop.ts:160`），`:215` `stopReason === "aborted"` 直接 `turn_end`+`agent_end`；`:514`/`:521` 并发分支里逐个检查 `signal.aborted`。重试在 coding-agent 层（`agent-session.ts` 的 retry/`_summarizationRetryCallbacks`） | 重试在 provider 层：`ClaudeProvider.kt:1404`、`GeminiProvider.kt:1224`、`OpenAIProvider.kt:3248` 都有 `while (retryCount <= maxRetries)` | **有**：`GenerationLoop.kt:228` `retryGenerationTransportRequest`（传输层重试，带退避）；`ToolRuntimeLimits.turnBudgetMs` 墙钟上限（`:513`）；`MAX_LOOP_GUARD_TRIPS_PER_TURN` 防"反复撞守卫"（`:516-520`） | 未确认 |
| 失败回退 | `stopReason === "length"` 时**整批工具调用直接判失败**，不执行可能被截断的参数（`agent-loop.ts:227-233`）；工具批次可用 `terminate` 提前收束（`:235` `shouldTerminateToolBatch`） | 显式处理"纯思考输出"（`EnhancedAIService.kt:1777-1830` 注入告警并回传继续）；工具结果 XML 截断修复 `detectAndRepairTruncatedToolRound`（`:1443`） | **有**：进程被杀导致"已开始未产出"的工具，重放时翻成 `Denied(interrupted_unknown_outcome)`（`GenerationLoop.kt:475-500`） | 未确认 |
| 行数量级 | `agent-loop.ts` 803 行；上层 `pi-src/packages/coding-agent/src/core/agent-session.ts` **3,552 行** | `EnhancedAIService.kt` **3,221 行** + `ConversationService.kt` 1,342 行 | `GenerationLoop.kt` **1,373 行** | 未确认 |

**读到的 A 的循环特征（用于后面判"强/弱"）**：`agent-loop.ts` 把"内层工具循环 / 外层排队消息"分成两层 `while`，steering 消息（`:257`）与 follow-up 消息（`:261`）是两条独立通道；这解释了为什么 pi 的循环里**没有**"步数上限"这种数字——它靠 `terminate` 与消息队列收束，而不是靠计数。

---

## 3. 维度二：工具系统

| 维度 | A（pi） | B（Operit） | C（rikkahub-agent） | D（OpenMinis） |
|---|---|---|---|---|
| 内建工具清单 | **8 个**：`read` `bash` `powershell` `edit` `write` `grep` `find` `ls`（`pi-src/packages/coding-agent/src/core/tools/index.ts:182-193` `createAllToolDefinitions`）。另有两套子集：`createCodingTools`（`:195`，read/bash/edit/write）、`createReadOnlyTools`（`:204`，read/grep/find/ls 系） | **182 个**注册名（`Operit/.../core/tools/ToolRegistration.kt`，`grep -c 'handler.registerTool('` = 182），覆盖 `read_file`/`write_file`/`bash`/`grep_code`/`http_request`/`tap`/`swipe`/`capture_screenshot`/`start_app`/`uninstall_app`/`create_terminal_session`/`zip_files`/`ffmpeg_execute`/蓝牙全家桶/音乐播放/角色卡/工作流 等 | `ChatToolFactory.createTools`（`.../data/ai/tools/ChatToolFactory.kt:44-90`）按 assistant 开关组合：记忆工具、搜索工具、`localTools.getTools(assistant.localTools)`、会话工具、workspace 工具、技能工具、MCP 工具。`LocalTools.kt` 1,083 行；`local/` 目录工具粒度极细（SMS、通话记录、NFC、相机、SSH/SFTP、Telegram、定时任务……） | `tools/`：`AgentTools.kt`、`BrowserUseTool.kt`、`FileReadTool.kt`、`FileWriteTool.kt`、`FileEditTool.kt`、`MemoryTools.kt`、`ReadImageTool.kt`；再加 `sandbox/offload/` 下 **20 个** offload handler（Accessibility/Alarm/BrowserUse/Calendar/Clipboard/Contacts/Device/Location/ModelUse/Notification/Open/Photos/Player/ScheduledTask/Sessions/Shizuku/Speak/Speech/Weather） |
| schema 从哪来 | **TypeBox**：`parameters: readSchema`（`.../tools/read.ts:76`，`import { Type } from "typebox"` 在 `read.ts:5`），运行时 JSON Schema | 自有 `ToolParameter` 模型（`data/model/`），由 `ToolRegistration` 逐个构造；提示词侧描述在 `core/config/SystemToolPrompts.kt` | 有独立 `ai` 模块的 `me.rerere.ai.core.Tool`（`ChatToolFactory.kt:8` import），MCP 工具直接用 `tool.inputSchema`（`ChatToolFactory.kt:76`） | 未确认（只读到文件名） |
| per-tool allow/ask/deny | **pi 没有**：`grep -n "approve\|confirm\|allow\|deny" tools/bash.ts core/bash-executor.ts` 无命中。pi 的权限粒度是**项目信任**：`core/trust-manager.ts:31` `TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES`（settings.json / extensions / skills / prompts / themes / SYSTEM.md / APPEND_SYSTEM.md） | **有**：`ui/permissions/ToolPermissionSystem.kt:55`（单例）、`:59` `PERMISSION_REQUEST_TIMEOUT_MS = 60000L`，配套 `PermissionRequestOverlay.kt`（475 行）、`ToolPermissionDialog.kt`、`ToolPermissionCheckResult.kt` | **有且最细**：循环里按 `ToolApprovalState`（`Auto`/`Pending`/`Approved`/`Denied`/`Answered`）分支执行（`GenerationLoop.kt:770-880`）；`ToolApprovalAllowList.kt` + `ToolApprovalDefaults.kt` 做"本会话允许/始终允许"；`HardlineCommandGuard.kt` 是硬底线，且在**重放路径上二次校验**（`GenerationLoop.kt:790-810`） | 未确认 |
| 可扩展性 | **扩展系统**：`core/extensions/types.ts` 1,797 行、`loader.ts`/`runner.ts`/`wrapper.ts`；**包管理**：`core/package-manager.ts` 2,699 行（npm / git 两种来源，`install` 在 `:1005`）；**技能**：`core/skills.ts` 509 行，扫 `SKILL.md`（`:195`）；提示词模板 `core/prompt-templates.ts`。**MCP：pi 里没有**（全仓 `grep -ri mcp packages/coding-agent/src packages/agent/src` 只命中 vendored `highlight.min.js` 与 `tool-result-images.ts`） | **有**：MCP（`data/mcp/`、`core/tools/mcp/`）、工具包（`plugins/toolpkg`、`core/tools/packTool`）、JS 脚本沙箱（`execute_sandbox_script_direct` / `list_sandbox_packages` + `quickjs` 模块）、技能（`data/skill/`）、工作流（`core/workflow/`） | **有**：MCP（`data/ai/mcp/McpManager.kt` 等，含 OAuth 协调器与 URL 守卫）、技能（`assets/default-skills/*/SKILL.md`，`TOOLS.md`/`SOUL.md`/`HEARTBEAT.md`）、子代理 | **有**：技能（`agent/SoulStore.kt`）、MCP（`app/mcp/` 4 个文件）、offload handler 机制本身是一种扩展点 |

---

## 4. 维度三：上下文与会话

| 维度 | A（pi） | B（Operit） | C（rikkahub-agent） | D（OpenMinis） |
|---|---|---|---|---|
| 会话持久化 | **JSONL**，一会话一文件：`~/.pi/agent/sessions/<encoded-cwd>/<timestamp>_<id>.jsonl`（`pi-src/packages/coding-agent/src/core/session-manager.ts:474` 编码 cwd、`:949`、`:1465`、`:1641` 新建）；`:641`/`:825` 扫 `.jsonl` | SQLite/Room（`data/db/`、`data/dao/`、`data/repository/`） | Room（`data/agentrun/AgentRun.kt` 是 Room `@Entity`，`AgentRunDao.kt`） | Room（`data/db/ChatSessionEntity.kt`、`ProviderAgentLoopIdEntity.kt`） |
| 上下文超限 | **压缩 + 分支摘要**：`core/compaction/compaction.ts` **1,012 行**、`branch-summarization.ts` 382 行、`utils.ts` 158 行；`agent-session.ts:538` `_compactBeforeNextAssistantResponse`，阈值/溢出/手动三种触发（`agent-session.ts:157` `reason: "manual" \| "threshold" \| "overflow"`） | 摘要式压缩：`api/chat/enhance/ConversationService.kt`（1,342 行）里按阶段生成 summary（`:257` stage 匹配），另有 `ChatMemoryRebuildManager.kt` | **有**：`data/ai/ContextBudgetPlanner.kt`、`ContextCompactionPlanner.kt`；循环侧 `onAfterToolExecution` 回调让 `ChatService` 返回"仅用于本次请求的压缩历史"（`GenerationLoop.kt:466-470`） | 未确认（只读到 `MemorySnapshot.kt` 等文件名） |
| 长期记忆 | **pi 没有**独立的长期记忆层（会话 + 压缩 + 技能） | **有**：`api/chat/library/MemoryLibrary.kt`、`MemoryAutoSaveScheduler.kt`、`create_memory`/`query_memory` 等工具 | **有**：`data/ai/tools/MemoryTools.kt`、`MemoryRepository`、`AssistantMemory`、`Assistant.enableMemory` | **有**：`data/repository/MemoryRepository.kt`、`data/MemoryGlobalPrefs.kt`、`ui/chat/SessionMemorySheet.kt`、`tools/MemoryTools.kt` |
| 恢复到历史点 | **有**：会话 fork（`session-manager.ts:1465`、`:1641` `newSessionFile`）、`/compact` 手动压缩入口（`agent-session.ts:1953-1962`）；`packages/agent/src/harness/runtime/restore.ts` 存在 | 未确认（有 `data/recovery/`、`data/backup/`） | 未确认（有 `AgentRunBootRecovery.kt`：崩溃后把 `running`/`awaiting_approval` 翻成 `process_lost`，这是**运行恢复**不是**上下文回滚**） | **有**：`data/SessionForkManager.kt`（fork 会话）；另有 `InterruptedTailDetector.kt` |

---

## 5. 维度四：运行时（谁真正执行工具）

| 维度 | A | B | C | D |
|---|---|---|---|---|
| 工具在哪个进程/用户态执行 | **guest 的真 Linux 用户态**：pi（Node 24）跑在 Ubuntu 24.04 的 proot/proroot 里，`bash` 工具 fork 出来的就是 guest 的 `/bin/bash`。宿主侧只有 launcher 与 `PtyLauncher`（`app/src/main/kotlin/app/pi/runtime/PtyLauncher.kt`，512 行） | **guest 的 Linux 用户态**：`Operit/.../core/performance/PerformanceMonitorManager.kt:299` 一句话写死进程树——「终端（PTY 子进程树：bash → proot → 命令）」 | **guest 的 Linux 用户态**：`workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt:89` `buildCommand` 拼 proot argv | **guest 的 Linux 用户态**：`OpenMinis/src/android/.../sandbox/PRootKernel.kt:19`「Corresponds to iOS ISHKernel — but since PRoot is process-per-command」 |
| 宿主 ↔ guest 文件映射 | 两条路：① 绑定表 `GuestRecipe.binds`（`/dev`、`/dev/urandom:/dev/random`、`/proc`、`/sys`、`/system`、`/apex`、`/proc/self/fd:/dev/fd`、`<storage>:/sdcard`、`<storage>:/storage/emulated/0`）+ `GuestRecipe.tmpBind`（`<tmp>:/tmp`）；② 路径翻译 `GuestWorkspacePath`、`GuestPathMapping`（bridge 侧）+ `pi-android-bridge` 扩展做宿主复制。**允许额外绑定**：`ProrootCommand.build(..., extraBinds)` / `ProotCommand` 同签名 | `util/PathMapper.kt:12-36`：把 guest 绝对路径机械地拼到 `{filesDir}/usr/var/lib/proot-distro/installed-rootfs/ubuntu` 下——即**宿主直接读写 rootfs 目录**，不经过 proot | `WorkspaceManager.resolveRootfsPath`（`.../WorkspaceManager.kt:136-160`）把 guest 路径映射到宿主 `linuxDir(root)`，并特判 `/workspace` 与 `KERNEL_FS_MOUNTS`；反方向用 `-b ${filesDir}:/workspace`（`ProotShellRunner.kt:104-105`） | `PRootKernel.bindMounts`（`PRootKernel.kt:48` `linkedMapOf`，Linux 路径 → 宿主路径），另有 `MountedFolderCoordinator.kt` 管 SAF 挂载 |
| 环境变量 / PATH 从哪来 | **显式表，不靠 profile**：`GuestRecipe.environment`（`GuestRecipe.kt:79-140`）给 `HOME=/root`、`TMPDIR=/tmp`、`TERM`、`LANG=C.UTF-8`、`PATH=/opt/pi/bin:...`、`NARB_DISABLE_NATIVE_CACHE=1`、`GIT_SSL_CAINFO`/`SSL_CERT_FILE`；runtime 专属变量由 `ProotCommand`（`PROOT_LOADER`/`PROOT_TMP_DIR`/`PROOT_L2S_DIR`/`LD_LIBRARY_PATH`）与 `ProrootCommand.environment`（`PROROOT_*`）各加。**故意 `bash -c` 而非 `-lc`**（`GuestRecipe.shellArgs`，注释里写明登录 shell 每次多 ~1.5 s 且无东西可设） | 未确认（terminal 是 submodule `OperitTerminalCore`，本地未取到） | **`env -i` 白名单**：`ProotShellRunner.kt:121` `"/usr/bin/env", "-i"` + `HOME=/root`/`PATH`/`TERM`/`LANG`/`LC_ALL`/`CI=true`/`NO_COLOR=1`/`PAGER=cat`，再 `"/bin/bash", "-l", "-c"`（`:133-135`） | `PRootKernel.kt:93-112` `customEnvironment`：`PATH`（含 `/opt/bin`）、`HOME=/root`、`BROWSER=/usr/local/bin/minis-open`、`ENV=/etc/profile`、`CHARSET=UTF-8` |
| 结果怎么送回 | guest stdout/stderr → `PtySession`/`PtyLauncher` → RPC 事件（`rpc/src/main/kotlin/app/pi/rpc/Events.kt` 746 行）→ `PiEngineSession`（`app/src/main/kotlin/app/pi/engine/PiEngineSession.kt:76`，1,415 行）→ `rpc/Transcript.kt`（2,653 行）归约 → UI | 工具返回 `ToolResult`，经 `ToolProgressBus` / `collector.emit` 回流到 UI 流 | `WorkspaceCommandResult(exitCode, stdout, stderr)`（`ProotShellRunner.kt:21-31`） | 未确认 |

**A 与 C 的 argv 差异（值得记一笔，都是显式的）**：A 的 proroot 路径**丢掉**了 `--kill-on-exit`，改用 `GuestTreeReaper`（`app/src/main/kotlin/app/pi/runtime/GuestTreeReaper.kt`）在宿主侧回收（`ProrootCommand.kt` KDoc 明说这是"唯一一处把工作搬进我们代码"的妥协）；C 的 proot 路径**保留** `--kill-on-exit`（`ProotShellRunner.kt:97`）。

---

## 6. 维度五：容器（隔离与用户态）

| 维度 | A | B | C | D |
|---|---|---|---|---|
| rootfs 发行版/版本 | **Ubuntu 24.04.3 (noble) arm64 base**，`runtime.lock.json` `ubuntuBase.url = https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz`，带 sha256 | **Ubuntu（提示词里写 "Ubuntu 24"）**，`Operit/.../util/PathMapper.kt:20` 注释「Ubuntu根目录位于 {filesDir}/usr/var/lib/proot-distro/installed-rootfs/ubuntu」 | **未确认发行版**（只读到 `RootfsInstaller.install(root, url, ...)` 从 URL 下载，`RootfsInstaller.kt:19-48`；`RootfsInstallerTest.kt` 存在但未读） | **Alpine**：`OpenMinis/src/android/.../sandbox/RootfsManager.kt:42` `rootfsDir = File(filesDir, "alpine-rootfs")` |
| 随包 vs 运行时下载 | **随包**：`app/src/main/assets/runtime/` 有 `ubuntu-base.tgz`(29,865,086 B ≈ 28.5 MiB)、`node.tgz`(57,128,436 B)、`pi-engine.tgz`(22,944,170 B)、`git.tgz`、`ripgrep.tgz`、`fd.tgz`。解包后 tar 约 106 MB（`RuntimeProvisioner.kt:859-861` 注释记录了这个数字） | **运行时下载**（proot-distro 惯例） | **运行时下载**：`RootfsInstaller.kt:26-28` `download(url, archive, onProgress)`，支持 `.gz`/`.xz`（`:13` `XZInputStream`、`:393`） | **随包 asset**：`RootfsManager.kt:21-22` 注释「Neither platform actually downloads the rootfs from the network today — it ships bundled as an asset」 |
| 要不要 root | **不要**：proot 是用户态 syscall 翻译；proroot 是 LD_PRELOAD 路径翻译（`ProrootCommand.kt` KDoc） | **不要**（executor 另有 `root/` 一套可选，不是必需） | **不要**（`--root-id` 只是假 uid 0） | **不要** |
| 隔离机制 | **proot**（Termux `proot 5.1.107.92` + `libtalloc 2.4.3` + `libandroid-shmem 0.7`，`runtime.lock.json`）**或** **proroot**（LD_PRELOAD，5 个 pin 的 `.so`：`libproroot.so`、`libproroot-runtime.so`、`libproroot-linker.so`、`libproroot-stub-loader.so`、`libproroot-bridge.so`，见 `app/src/main/jniLibs/arm64-v8a/`）。两者共用绑定表与 `bash -c`（`GuestRecipe`），差异清单在 `ProrootCommand.kt` KDoc | **proot-distro**（Ubuntu 环境在 `installed-rootfs/ubuntu`；terminal 由 submodule `OperitTerminalCore` 提供，`.gitmodules`） | **proot**：`libproot_exec.so` + `libproot_loader.so`（`workspace/src/main/jniLibs/{arm64-v8a,x86_64}/`），argv 在 `ProotShellRunner.kt:94-104`：`--root-id`（`:95`）`--link2symlink`（`:96`）`--kill-on-exit`（`:97`）`-r <rootfs>`（`:98`）`-w <cwd>`（`:100`）`-b <filesDir>:/workspace`（`:102-103`） | **proot**：`libproot.so`（`RootfsManager.kt:43`）+ `libproot-loader.so` / `libproot-loader32.so`（`src/android/app/src/main/jniLibs/arm64-v8a/`）；`deps/build_proot.sh` 自建 |
| 绑定点白名单 | 固定表在 `GuestRecipe.binds`（9 条）+ `tmpBind`，**允许调用方追加** `extraBinds`；rootfs 外的东西默认进不去 | 未确认 | 固定：`-b ${filesDir}:/workspace`（`ProotShellRunner.kt:102-103`）+ `KERNEL_FS_MOUNTS`（`WorkspaceManager.kt:315` = `/dev`、`/proc`、`/sys`，在 `ProotShellRunner.kt:113-119` 逐条加）+ 调用方 `bindMounts` 且**只在 `mount.source.exists()` 时才加**（`:106-111`） | `bindMounts` 可变 map + `MountedFolderCoordinator`（SAF） |
| 退出时回收 | proot：`--kill-on-exit`；proroot：**不支持**该 flag，改由 `GuestTreeReaper`（236 行）在宿主侧按进程树回收，`ProrootCommand.kt` KDoc 点明这是"搬到我们代码里的那一处"。另有 `RuntimeSpaceBudget.kt`（磁盘预算）、`GuestProcessTree.kt` | 未确认 | `--kill-on-exit`（`ProotShellRunner.kt:97`）+ `WorkspaceBackgroundProcesses.kt`（208 行，测试 `WorkspaceBackgroundProcessesTest.kt`） | 未确认 |
| 已知静默失败模式 | **有一个，且已修**：`app/src/main/kotlin/app/pi/runtime/TarExtractor.kt:60-75` 的记录——`readFully` 曾把"一个字节都没有"和"数据正常结束"报成同一种结果，于是**恰好截在 512 字节边界的归档会解出半个 rootfs，然后照样写 provisioning stamp**，设备进到一个"半解包的 runtime 但没有任何提示"的状态。现在抛 `IOException`，由 `RuntimeProvisioner.extractAsset` 带上 payload 审计信息重抛。另 `RuntimeProvisioner.kt:859-873` 记录过一次真实的打包/解包后缀错配（`ubuntu-base.tar.gz` vs `.tar`，asset 打不开时把 `assets/runtime/` 目录内容打进错误信息） | 未确认 | `RootfsInstaller` 用 staging 目录 + `renameTo`（`RootfsInstaller.kt:33-40`），失败不会污染 `linuxDir`；`RootfsInstallerTest.kt` 存在但未读 | 未确认 |

---

## 7. 维度六～八（压缩：只列已确认项）

### 7.1 Android 系统能力通道

| | A | B | C | D |
|---|---|---|---|---|
| 通道 | **四条并存**：无障碍服务 `bridge/DeviceAccessibilityService.kt`（屏幕树/点按/滑动/输入/截图）、Shizuku `bridge/DeviceShizuku.kt`（`:50` object，`:90` `isReady()`，`:175` `exec`，`:271` `ShizukuShellBackend`）、设备 shell `bridge/DeviceShell.kt`、`DeviceUiAutomation`；SAF 在 `DeviceSafStore.kt` | **五档 executor 并行**：`core/tools/defaultTool/{standard,accessbility,admin,debugger,root}/`（admin = Device Owner，root = 可选 su）；虚拟显示：`core/tools/agent/{VirtualDisplayManager,ShowerController,PhoneAgent}.kt` | 无障碍 `service/RikkaAccessibilityService.kt`、通知监听 `service/RikkaNotificationListenerService.kt`、Shizuku `data/ai/tools/local/ShizukuTool.kt`，其余走普通 Android API 工具 | 无障碍 + Shizuku：`sandbox/offload/AccessibilityOffloadHandler.kt`、`ShizukuOffloadHandler.kt`；并有一层 `offload/OffloadGate.kt` 做闸门 |
| 授权与审计 | **显式分组 opt-in**：`bridge/DeviceCapability.kt` 五个能力组（Basic 默认开；Storage / Accessibility / Sensors / Shell 默认关），每组列出"允许什么"；`DeviceCapabilityStore.kt` 持久化；`DeviceBridgeHttp.kt`/`DeviceBridgeRouter.kt` 是 HTTP 桥 | **有**：`ToolPermissionSystem` + 权限弹层（见 §3） | **有**：`ToolApprovalState` + `HardlineCommandGuard` + allow-list（见 §3） | 未确认 |

### 7.2 模型与本地推理

| | A | B | C | D |
|---|---|---|---|---|
| 云端 provider | 走 pi：`pi-src/packages/ai/` + `coding-agent/src/core/{model-resolver,model-registry,provider-composer,models-store}.ts`；App 侧只做只读展示与预配置（`rpc/.../PiPreSpawnConfig.kt` 407 行） | `api/chat/llmprovider/`：`OpenAIProvider`、`ClaudeProvider`、`GeminiProvider`、`DeepseekProvider`、`KimiProvider`、`CodexProvider` 等 | `ai/` 模块 + `data/ai/` provider 实现；`provider/ProviderFactory.kt` 在 D 侧是 Kotlin | `provider/`：`AnthropicProvider.kt`、`OpenAIProvider.kt`、`GeminiProvider.kt`、`VoiceProvider.kt` 等（Android 侧）；iOS 另有 69 个文件 |
| 本地推理 | **没有**（我们侧只维护 pi 的 provider 配置） | **有**：`llmprovider/MNNProvider.kt`、`LlamaProvider.kt`、`data/mnn/`（含 `MnnModelDownloadManager`） | **有**：顶层 `llama-cpp/` 与 `local-llm/` 两个 Gradle 模块 | 未确认（有 `provider/ModelReleaseIndex.kt`、`ModelsDevApi.kt`） |
| 凭证存储 | pi 自己的 `~/.pi/agent/auth.json`（`pi-src/packages/coding-agent/src/core/auth-storage.ts:52`，`:347` 工厂，`:493` 一次性读取），我们侧**只读**该文件（`app/src/main/kotlin/app/pi/ui/screens/PiFilesScreen.kt:101` 明确把 `auth.json`、`models-store.json`、`sessions/` 划为只读；`WorkspacePiWrite.kt:118` 记录 pi 建它为 `0600`） | 未确认 | 未确认（`oauth/` 模块存在） | 未确认 |

### 7.3 成熟度信号

| | A | B | C | D |
|---|---|---|---|---|
| star | 私有仓库 | 7,942 | 283 | 4,537（RikkaMinis 7） |
| 最后推送 | — | 2026-09-18 | 2026-09-11 | 2026-09-01（RikkaMinis 2026-09-18） |
| 许可证 | — | LGPL-3.0 | AGPL-3.0 | GPL-3.0 |
| 代码规模（我量的） | `app/src/main` + `rpc/src/main`：263 `.kt` / 85,923 行。关键文件行数：`PiEngineSession.kt` 1,415、`rpc/Transcript.kt` 2,653、`RuntimeProvisioner.kt` 951、`PiEngineHost.kt` 861、`PtyLauncher.kt` 512 | `app/src/main/java` 下 1,178 `.kt`（我的 sparse checkout 子集）。关键：`ToolRegistration.kt` 2,745、`EnhancedAIService.kt` 3,221、`ChatViewModel.kt` 3,199、`ConversationService.kt` 1,342 | 全库 1,907 个文件（`git ls-tree -r`）。关键：`GenerationLoop.kt` 1,373、`LocalTools.kt` 1,083、`SubAgentEngine.kt` 580 | `src/android` 773 个 blob，`src/ios` 约 1,000 个 |

---

## 8. D（OpenMinis / RikkaMinis）专项：三件事

用户点名只要这三件，逐条给结论。

**(1) Agent 循环是谁写的？——「自研，但 Android 侧我没定位到主循环文件」。**
Android 侧 `app/provider/` 有 29 个文件（`ProviderFactory.kt`、`LLMProvider.kt`、`ThinkingRule*.kt`、各家 provider），`app/agent/` 只有 4 个（`InterruptedTailDetector.kt`、`ToolLoopDetector.kt`、`SoulStore.kt`、`SoulIcon.kt`）。我按名字试过 `AgentRunner/ChatEngine/GenerationEngine/AgentEngine` 全部 404。**结论：循环是自研的（有 `ToolLoopDetector.kt`、`InterruptedTailDetector.kt` 这类只有自研循环才会需要的文件），但具体文件与行数未确认。**iOS 侧 `src/ios/Agent/` 有 132 个文件，循环很可能在那里，我没读。

**(2) Android 上沙箱是什么机制？——「PRoot + Alpine，随包 asset，不要 root」。**
- `OpenMinis/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:42` `val rootfsDir: File = File(context.filesDir, "alpine-rootfs")`
- 同文件 `:43` `val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")`
- 同文件 `:21-22` KDoc：「Neither platform actually downloads the rootfs from the network today — it ships bundled as an asset」
- `sandbox/PRootKernel.kt:19` KDoc：「Corresponds to iOS ISHKernel — but since PRoot is process-per-command (not a persistent kernel)」
- `sandbox/PRootKernel.kt:45-48`：`customEnvironment` + `bindMounts`（Linux 路径 → 宿主路径）
- jniLibs：`libproot-loader.so` + `libproot-loader32.so`（32 位 loader 也带）
- **iOS 与 Android 不同族**：iOS 侧目录是 `src/ios/iSH/`（14 个文件）+ `ISHCommandExecutionExample.swift` + `PRootKernel.kt` 自称对应 `ISHKernel`。**我推断**（未读 iSH 源码）：iOS 的"Linux 沙箱"是 iSH（x86 用户态仿真），Android 才是真 PRoot + Alpine。这个差异本身对"iOS/Android 同一套沙箱"的说法是个反证。
- 不要 root：proot 是用户态翻译，无需 root；我没读到任何 `su` 路径。

**(3) 我们明确没有的能力（D 侧有、A 侧不存在）。**
- **记忆**：`OpenMinis/src/android/.../data/repository/MemoryRepository.kt`、`data/MemoryGlobalPrefs.kt`、`tools/MemoryTools.kt`、`ui/chat/SessionMemorySheet.kt`。pi 没有记忆层。
- **浏览器自动化**：`tools/BrowserUseTool.kt` + `sandbox/offload/BrowserUseOffloadHandler.kt`。pi 没有任何浏览器工具。
- **设备集成 offload 全家桶**：`sandbox/offload/` 20 个 handler（Calendar/Contacts/Photos/Alarm/Weather/Player/Speak/…）。A 的能力组是 5 组粗粒度（`bridge/DeviceCapability.kt`），没有这种 per-能力 handler 结构。
- **Workspaces**：README 写「Organise work into separate contexts, addressable via `minis://workspace/`」——**这条我只有 README，没有代码证据**，标为未确认。
- **Native offload 机制本身**：`sandbox/NativeOffload.kt` + `offload/OffloadGate.kt`，"重活交给原生而不是沙箱"。A 侧没有对应抽象（A 的对应物是把宿主能力做成 pi 扩展 `app/src/main/assets/pi-extensions/pi-android-bridge`）。
- **会话 fork**：`data/SessionForkManager.kt`。A 侧 pi 有等价物（`session-manager.ts:1465`），**不算我们缺**。

**RikkaMinis 与 RikkaHub 的关系**（它的 README 明说）：「引擎与代码库来自 OpenMinis，UI 与交互逻辑则受 RikkaHub 启发」（`RikkaMinis/README.md`）。仓库树里 `src/android/app` 906 个 blob，与 OpenMinis 的 `src/android` 同构，另加 `deps/build_proot.sh`、`scripts/prepare_android_sandbox.sh`、`sync-upstream.yml`。**所以 D 的 Android 形态有两份**：上游自己的 `src/android`，和这个 UI 换皮 + 保持 upstream 同步的 fork。

---

## 9. E = OpenBot：判定为「名字有歧义，都不是手机端 AI Agent 应用」

我只找到两个有分量的同名候选，**都不属于本对比的类别**：

| 候选 | 语言 | 许可证 | star | 定位 | 为什么不是 |
|---|---|---|---|---|---|
| [`CopilotKit/OpenBot`](https://github.com/CopilotKit/openbot) | TypeScript | MIT | 5,123 | "Open-source AI coworkers that each get a computer of their own: a browser, files and tools" | 是**服务端/网页**的 agent 平台，不是 Android 上的 Agent 应用；无 rootfs/容器/设备能力这一层 |
| [`intel-isl/OpenBot`](https://github.com/intel-isl/OpenBot) | — | — | — | 「leverages smartphones as brains for low-cost robots」，手机当机器人小车的大脑（[项目页](https://olud.ai/project/ob-f-openbot.html)） | 有 Android App，但它是**机器人控制器**，不是 AI Agent 运行时；没有 LLM 循环/工具系统 |

GitHub 搜索 `OpenBot + agent`（按 star 排序）返回的其余项（`meetopenbot/openbot` 337★ TypeScript、`openbotx/openbotx` 93★ Python、`nightly-labs/openbot` 88★ TypeScript 等）全部是桌面/服务端 agent，无一为手机端 Agent 应用。

**结论：用户可能指的是另一个同名项目。**按指示**跳过 E**，不硬凑对比。若确有一个我漏掉的"手机上的 OpenBot Agent"，需要用户给仓库地址。

---

## 10. A 相对 B/C/D 的强项（带证据）

1. **容器运行时最"双轨且被注释死"**：唯一一家同时支持 **proot 与 proroot（LD_PRELOAD）两条互斥路径**，且把两者的差异收敛到一个逐条注释的映射表里。`app/src/main/kotlin/app/pi/runtime/GuestRecipe.kt` 把"两个 runtime 必须一致的东西"（绑定表、环境、`bash -c`）抽成公共对象，其 KDoc 明说这个文件存在的理由就是"防止两份拷贝出来的 bind 列表悄悄漂移"；`ProrootCommand.kt` 的 KDoc 逐条列出 6 处差异（`--link2symlink` 保留、`-b l2s` 去掉、`-L` 去掉、`--kill-on-exit` 去掉改 `GuestTreeReaper`、`--rootfs=`→`-r`、`-0` 不变）。B/C/D 都是单轨。
2. **运行时回收有独立组件**：`GuestTreeReaper.kt`（236 行）是"proroot 不支持 `--kill-on-exit`"这个具体缺陷逼出来的，且在**每个停止点**接线（`ProrootCommand.kt` KDoc）；C 靠 `--kill-on-exit`、D/B 未读到等价物。
3. **磁盘/空间预算显式化**：`RuntimeSpaceBudget.kt`（90 行）。B/C/D 未见对应文件。
4. **静默失败有"前科记录 + 修复 + 打包期审计"**：`TarExtractor.kt:60-75` 那段注释把"512 字节边界截断 → 半解包 → 照写 stamp"写成了一条可复现的故障叙事，并把修法（抛 `IOException`）与调用侧（`RuntimeProvisioner.extractAsset` 带 payload 审计）绑在一起。这是四家里唯一一处"把已知静默失败模式文档化"的证据。
5. **引擎是"官方未改动"的第三方**：pi CLI 0.85.1 原封不动（`pi-src` HEAD `bbb61e3`）。B/C/D 的循环/工具/（C 的）容器全是自研，**它们的能力等于它们的代码规模**；A 的能力等于 **pi 的能力 + 我们的接线**。
6. **环境变量不依赖 profile**：`GuestRecipe.shellArgs` 明确选 `-c` 而非 `-lc`，并给了理由（登录 shell 每次 ~1.5 s，且 `/etc/profile.d` 不是依赖）。C 用的是 `/bin/bash -l -c`（`ProotShellRunner.kt:133-135`），D 用 `ENV=/etc/profile`（`PRootKernel.kt:107`）——两家都把 profile 拉进了启动路径。
7. **设备能力有显式分组 + 默认关**：`bridge/DeviceCapability.kt` 五组，Storage/Accessibility/Sensors/Shell **默认关**，Basic 默认开，每组把"允许什么"逐条写进 `allows` 供 UI 渲染。C 的审批是 per-tool 的（更细），但 A 这套是"能力域"粒度的显式 consent，B/D 未见等价物。

---

## 11. A 的弱项（带证据，并区分"pi 没有"与"pi 有我们没接上"）

### 11.1 真欠账：**pi 有对应物，我们没接上**

| 欠账 | pi 里的对应物（证据） | 我们这边缺什么 |
|---|---|---|
| **per-tool 审批交互** | pi 有扩展钩子 `beforeToolCall` / `afterToolCall`（`pi-src/packages/coding-agent/src/core/agent-session.ts:483`、`:504`），足以实现 allow/ask/deny | 我们的 UI/RPC 层未见把 `beforeToolCall` 暴露成 Pending→Approved/Denied 的交互。C 有完整实现（`GenerationLoop.kt:770-880`），B 有 `ToolPermissionSystem`。**这是我们能靠 pi 扩展补上、但目前没补的** |
| **子代理 / 任务分解** | pi 有 extension + package 机制（`core/extensions/`、`core/package-manager.ts:1005`）可以挂出来 | 我们自己没做。C 有 `subagent/SubAgentEngine.kt`（580 行）。**注意：pi 内核本身没有 subagent，这属于"能用扩展做、官方没做"** |
| **技能（SKILL.md）的可发现性/自动加载** | pi 有 `core/skills.ts`（509 行，扫 `SKILL.md`，`:195`） | 未确认我们侧有没有把技能管理接到 UI（`rpc/SkillBlock.kt` 存在，66 行，说明至少解析到了） |

### 11.2 非欠账：**pi 里根本没有对应物**（按站内规则 = 不做，不是"我们缺"）

- **MCP**：pi 全仓无 MCP（grep 只命中 vendored JS 与一个无关文件）。B 有 `data/mcp/`，C 有 `data/ai/mcp/McpManager.kt`，D 有 `app/mcp/`。→ **pi 没有，不做。**
- **长期记忆**：pi 没有记忆层。B（`MemoryLibrary.kt`）、C（`MemoryTools.kt`）、D（`MemoryRepository.kt`）都有。→ **pi 没有，不做。**
- **浏览器自动化**：pi 没有任何浏览器工具。C 有 `browser` 工具族，D 有 `BrowserUseTool.kt`。→ **pi 没有，不做。**
- **子代理之外的任务编排**：B 的 `core/workflow/`（工作流）、`send_message_to_ai`/`call_chat_model`（Agent 之间互调），C 的 cron/Telegram 机器人（`service/CronJobScheduler.kt`、`TelegramBotService.kt`）、`AgentTurnTracker.kt`。→ **pi 没有，不做。**
- **虚拟显示 / 投放**：B 的 `VirtualDisplayManager.kt` + `ShowerController.kt`。→ **pi 没有，不做。**
- **设备全家桶工具**（SMS/通话记录/NFC/通讯录/相机/蓝牙/音乐…）：B 的 182 个工具、C 的 `local/` 几十个工具、D 的 20 个 offload handler 都远超 A 的 `bridge/` 五组。→ **pi 没有这些工具，不做。**（A 侧有 `pi-android-bridge` 扩展，是**我们的**设计，不受"pi 没有就不做"约束——但也不该按 B/C/D 的清单去追。）
- **本地推理**：pi 的 `extensions/llama` 存在，但 B（MNN/Llama provider）、C（`llama-cpp/`、`local-llm/`）都是自研模块。→ 归为"pi 侧非核心，不做"。

### 11.3 真实存在但可接受的差距

- **循环的"护栏数字"**：pi 的 `runLoop` 没有 `maxSteps` 这种计数上限，也没有墙钟上限（`agent-loop.ts:171-273` 只有 steering/follow-up/terminate 三种收束）。C 有 `ToolRuntimeLimits.maxToolSteps` + `turnBudgetMs`（`ToolRuntimeLimits.kt`）——C 的 KDoc 还记录了这两个数字的**来源**：「Was hardcoded at 32, which truncated long Termux chains mid-task (issue #22)」。**这不是我们该抄的**：加计数上限是对 pi 语义的改动，而站内规则是 pi 1:1。如果确实需要，应作为 pi 侧扩展/配置而非我们偷偷加。
- **工具数量级差距**：A 8 个内建工具 vs B 182 个注册工具。这是"引擎哲学不同"，不是缺陷——但**用户体验上**，靠 pi 的 8 个工具 + bash 能不能覆盖 B 的 `tap`/`swipe`/`capture_screenshot`，取决于 `pi-android-bridge` 扩展做得如何；这一点我**没读我们的扩展源码**，未确认。

---

## 12. 谁在哪一轴上更强（按轴裁决，不给总冠军）

| 轴 | 更强 | 依据（一句话） |
|---|---|---|
| **Agent 循环的健壮性（护栏/重放/审批）** | **C** | `GenerationLoop.kt` 是四家里唯一同时有步数上限、墙钟上限、loop guard 计数、传输重试、`interrupted_unknown_outcome` 重放保护和 resume 路径硬底线二次校验的循环 |
| **Agent 循环的通用性与上游同步** | **A** | 循环是官方 pi 的（`agent-loop.ts`），有并行/顺序工具执行双模式（`:487`/`:547`）与 steering/follow-up 双消息通道（`:257`/`:261`），升级即受益 |
| **工具系统广度** | **B** | 182 个注册工具（`ToolRegistration.kt`），覆盖设备/媒体/办公/蓝牙/工作流 |
| **工具系统的审批精细度** | **C**→**B**（并列靠前） | C 有 `ToolApprovalState` 五态 + allow-list + hardline 底线；B 有 `ToolPermissionSystem` + 60 s 超时弹层 |
| **工具系统安全性（底线设计）** | **C** | `HardlineCommandGuard` 在重放路径上二次校验（`GenerationLoop.kt:790-810`），B 未见等价物 |
| **上下文/压缩** | **A** | `compaction.ts` 1,012 行 + `branch-summarization.ts` 382 行 + 三档触发（manual/threshold/overflow），量级超过 B 的单文件 summary 与 C 的两个 planner |
| **会话恢复（回到历史点）** | **A** | 会话 fork（`session-manager.ts:1465`）+ `/compact` 手动入口（`agent-session.ts:1953`）+ `harness/runtime/restore.ts`；D 有 `SessionForkManager` 但路径不同 |
| **运行时的"可解释性"** | **A** | 绑定表/环境/两条 runtime 的差异集中且逐条注释（`GuestRecipe.kt`、`ProrootCommand.kt`），`PtyLauncher.kt` 显式管理 PTY |
| **容器的发行版现代度** | **A**≈**B**（Ubuntu 24.04 > Alpine） | A 是 pinned 的 ubuntu-base 24.04.3（glibc 2.39），B 提示词写 Ubuntu 24，D 是 Alpine（musl） |
| **容器的"自带"程度** | **A** | rootfs/Node/pi/git/ripgrep/fd 全随 APK（`assets/runtime/` 实打实 6 个 tar），D 只随 Alpine rootfs，B/C 靠运行时下载 |
| **Android 系统能力** | **B**（广度）/**A**（consent 显式度） | B 五档 executor（standard/无障碍/Device Owner/调试/root）+ 虚拟显示；A 五组能力组默认关且逐条列 `allows` |
| **模型层与本地推理** | **B** | 云端 provider 最多 + MNN/Llama 本地推理 |
| **成熟度/社区** | **B**（7,942★） | 其次 D（4,537★）；C 283★；A 私有 |
| **许可证宽松度** | **B**（LGPL-3.0） | C 是 AGPL-3.0（传染性最强）、D 是 GPL-3.0、A 无（私有） |
| **已知失败模式的处理** | **A** | 只有 A 把一次真实静默失败（tar 截断）写成可复现叙事并绑定到审计（`TarExtractor.kt:60-75`） |

---

## 13. 我们**明确没有**的东西（清单）

按来源分两类，避免把"pi 没有"写成"我们缺功能"。

**A. pi 里没有对应物 → 按站内规则不做**
1. MCP 客户端（证据：pi `coding-agent/agent` 源码 grep 无 MCP）
2. 长期记忆层（证据：pi 无记忆模块）
3. 浏览器自动化工具（证据：pi 无浏览器工具）
4. 工作流 / 定时任务 / 消息通道机器人（Telegram 等）（证据：pi 无）
5. 虚拟显示与投放（证据：pi 无）
6. 设备全家桶工具（SMS/通话/NFC/通讯录/相机/蓝牙/音乐/FFmpeg…）（证据：pi 只有 8 个内建工具）
7. 本地推理（MNN / llama.cpp）（证据：pi 只有 `extensions/llama`，非内建；我们没有把任何本地推理接进来）
8. 子代理内核（pi 内核无 subagent；只能靠扩展实现）

**B. pi 有对应物 → 属于我们尚未接上的欠账**
1. **per-tool allow/ask/deny 交互**（pi 有 `beforeToolCall`/`afterToolCall`：`agent-session.ts:483`、`:504`）——未确认我们接了多少
2. **子代理/任务分解的扩展实现**（pi 有 extensions + package-manager）
3. **技能（SKILL.md）的用户侧管理**（pi 有 `core/skills.ts`；我们有 `rpc/SkillBlock.kt`，但是否完整未确认）

**C. 不适用（我们是外壳而非竞品）**
- 我们不做模型层、不做工具集、不做循环——这三样按设计属于 pi。B/C/D 把这三样都写进自己的仓库，所以"代码规模"这项对比对 A 没有意义。

---

## 14. 未确认项（明确缺什么证据）

| # | 未确认的事 | 缺的证据 | 影响 |
|---|---|---|---|
| 1 | **B 的 proot 启动命令行**（`--link2symlink`/`-0`/rootfs 路径/绑定表/回收） | `terminal` 是 submodule（`.gitmodules` → `AAswordman/OperitTerminalCore`），`git sparse-checkout set` 因网络未能取到；只从 `PathMapper.kt:20` 与 `PerformanceMonitorManager.kt:299` 侧证 | 无法逐条比对 B 的 proot flag 与 A 的 |
| 2 | **B 的 Agent 循环是否有迭代上限** | `EnhancedAIService.kt` 是递归式，我没找到计数/墙钟守卫；也搜过 `maxIteration`/`MAX_ROUND` 无命中，但没有穷尽 | "B 循环会不会无限跑"未定 |
| 3 | **C 的 rootfs 默认发行版与版本** | `RootfsInstaller.install(root, url, ...)` 的 url 来自调用方，我没读到默认 URL 常量 | 只能说"运行时下载"，不能说发行版 |
| 4 | **C 是否有并行工具调用** | `GenerationLoop.kt` 读到的执行是顺序的，没有穷尽 `ChatService.kt`（未读） | 保守写"未确认" |
| 5 | **D 的 Agent 主循环文件与步数上限** | 按名字试了 `AgentRunner`/`ChatEngine`/`GenerationEngine`/`AgentEngine` 全 404；`src/ios/Agent/`（132 文件）未读 | D 的"循环是谁写的"只能靠 `ToolLoopDetector.kt`/`InterruptedTailDetector.kt` 的存在侧证 |
| 6 | **D 的 iOS 沙箱是否真是 iSH** | 只读到 `src/ios/iSH/`（14 文件）与 `PRootKernel.kt:19` 自称对应 `ISHKernel`，未读 iSH 源码 | "iOS 用 iSH 仿真、Android 用 PRoot"是推断，非确证 |
| 7 | **D 的 Workspaces（`minis://workspace/`）实现** | 只有 README 一句话，代码未读 | README 说法，未当证据 |
| 8 | **D 的 root 需求与退出回收** | 未读到 `su` 或 kill-on-exit/进程树回收代码 | 只能写"不要 root"，回收策略未确认 |
| 9 | **D 的凭证存储** | 未读 | 空白 |
| 10 | **A 侧 `pi-android-bridge` 扩展到底暴露了多少设备能力** | 未读 `app/src/main/assets/pi-extensions/pi-android-bridge` 源码；只读了 `bridge/DeviceCapability.kt` 的 UI 侧描述 | §11.3 里"8 个内建工具够不够覆盖 B 的 tap/swipe"无法回答 |
| 11 | **A 侧审批交互（`beforeToolCall`）接了多少** | 只确认 pi 有钩子（`agent-session.ts:483`）、我们有审批 UI 的痕迹未查 | §11.1 第 1 条是欠账，但欠多少不确定 |
| 12 | **C 的 `ai` 模块的 `Tool` 类型 schema 细节** | `ai/` 模块未 sparse-checkout | 只能说"MCP 工具直接用 `inputSchema`" |
| 13 | **E = OpenBot 的确切指向** | `intel-isl/OpenBot` 的 star/许可证我没拿到（API 返回 None，可能限流） | 只能列候选并判定"都不是手机端 Agent 应用" |

---

## 附录：本文引用的外部仓库取证路径

| 仓库 | 本地路径 | 取证方式 |
|---|---|---|
| `AAswordman/Operit` | `/tmp/cmp/operit` | `git clone --depth 1 --filter=blob:none --sparse` + `sparse-checkout set app/src/main/java llm tools`（`terminal` submodule 未取到） |
| `ExTV/rikkahub-agent` | `/tmp/cmp/rikkahub-agent`（tree）+ `/tmp/cmp/csrc`（blob） | sparse clone 的 blob 拉取因网络失败（`could not fetch … from promisor remote`），改用 `raw.githubusercontent.com/ExTV/rikkahub-agent/master/<path>` 抓 29 个关键文件 |
| `OpenMinis/OpenMinis` | `/tmp/cmp/dsrc` | GitHub API `git/trees/main?recursive=1`（1,878 blob）+ raw 抓 `PRootKernel.kt`/`RootfsManager.kt` |
| `Filterrr/RikkaMinis` | `/tmp/cmp/rikkaminis_tree.json` | GitHub API tree（959 blob）+ raw 抓 `README.md` |
| `CopilotKit/OpenBot` / `intel-isl/OpenBot` | — | GitHub API + web 检索（仅元数据） |
| `pi` 上游 | `/root/pi-src` | 本地只读，HEAD `bbb61e3` |
| A（我们的 App） | `/root/pi-android` | 本地只读 |
