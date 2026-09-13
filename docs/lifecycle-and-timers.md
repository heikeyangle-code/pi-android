# 生命周期与定时/后台任务：逐项对照 pi（0.85.1，commit `bbb61e34`）

本文只审**一面**：`app.pi` 的生命周期（页面 / 组合 / 引擎进程 / 前台服务 / pty）与**所有周期性或延时任务**。
判定用四态标签，与 `docs/pi-sourced-lists.md`、`docs/session-lifecycle.md` 的口径一致：

- **`pi 有 <file:line>`** —— pi 有明确实现，App 应当照做（pi 侧路径都相对 `/root/pi-src`，只读，本次没改）；
- **`pi 无对应物（App 的决定）`** —— pi 里没有这个东西，是 App 自己定的；
- **`pi 有但我们够不着（卡在哪）`** —— pi 实现了，App 拿不到，写清卡点；
- **一致 / 有意偏离** —— 与 pi 一致，或有意不同并说明理由。

行号是**本次审计时的快照**（本仓库同时有多个 agent 在改，`file:line` 只保证写作时成立；
凡是本文说"已改"的，改动点自己在代码里带注释）。

**pi 的"生命周期"是什么**：pi 是 CLI，它的生命周期就是**进程**——没有 Activity、没有前台服务、
没有息屏概念，因此本文里所有"什么时候该停止、谁负责停、屏幕关了怎么办"的问题，pi 侧一律是
`pi 无对应物（App 的决定）`。pi 侧真正可对照的只有三件事：**落盘时机**、**关停顺序**、**它自己的两个节拍**。

---

## 0. 结论摘要

| # | 结论 | 性质 |
|---|---|---|
| 1 | 引擎是**App 进程的子进程**（`PiEngineHost` 在 ViewModel 里 spawn），前台服务并不"拥有"它；通知里的「停止」因此只停了服务，**引擎继续跑** | 已修（服务→`PiEngineController`→引擎拥有者的停机回调） |
| 2 | 引擎退出后，ViewModel 仍留着 `session`，聊天页照常渲染输入框，**点发送会往已关闭的管道写**（`IOException` 从 UI 线程抛出） | 已修（退出即清空并转成可重试的失败态；`send` 不再抛） |
| 3 | 唤醒锁按**服务寿命**持有、带 6 小时上限：正常回合中途静默失效，而空闲几小时却一直醒着 | 已修（改成"有活才持"：解包/启动/回合中） |
| 4 | `START_STICKY` + `intent == null` 会**复活一个没有任何引擎的服务**，重新发通知、重新上锁 | 已修（`START_NOT_STICKY`；null intent 视为停机） |
| 5 | `lifecycleLock` 曾是**实例字段**，而它保护的资源（一个 cwd、一份会话 JSONL）是进程级的：旧 Activity 的 ViewModel 还在收尾、新 ViewModel 已经在 boot，是唯一一个真能出现"两个引擎"的窗口 | 已修（锁改为进程级） |
| 6 | 对话框倒计时用固定 **200 ms 轮询**显示"整秒"，且 App 退后台仍继续；pi 自己的同类倒计时是 **1000 ms** | 已修（按整秒边界睡，≤1 次/秒；不动的是 pi 侧的兜底） |
| 7 | `DeviceCapabilityScreen` 每 1.5 s 轮询一次（含 Shizuku binder 调用），**App 切后台不停** | 已修（只在页面可见期间跑） |
| 8 | `DeviceBridgeHttpServer.stop()` 不关线程池，而每启动一次桥就新建一个 4 线程池（每次引擎 boot 都启动一次）→ **每次启动泄漏 4 个常驻线程** | 已修（`shutdownNow`） |
| 9 | 引擎已经死了还调 `closeAfterSettling()` 会把 `get_state` 的 30 s 超时白等一遍 | 已修（已退出就直接 close） |
| 10 | 切到别的目的地就会**结束工作区终端**（`DisposableEffect` → `bridge.close()`），终端里正在跑的命令一起死 | **未改**，见 §4-3：修它要动终端的持有层级，属于设计改动 |
| 11 | 从最近任务划掉 App（`stopWithTask` 默认 true）会结束前台服务并杀进程，回合中断 | **未改**，见 §4-4：设计文档记过 `stopWithTask=false` 这个先例，是一次产品决定 |
| 12 | 通知里的「%d 个任务进行中」恒为 0（`updateNotification` 无调用者） | 已修（同一个"有没有活"的事实驱动 0/1） |

---

## A. 生命周期：逐项表

### A1. Activity / 组合

| 面 | 现状（`file:line`） | pi 对照 | 判定 | 处置 |
|---|---|---|---|---|
| `MainActivity` | `enableEdgeToEdge()` 在 `super.onCreate` **之前**（`MainActivity.kt:19-20`）；`setContent` 里 `viewModel()`（`:30`）拿 Activity 级实例；`LaunchedEffect(systemDark)` 重新解析主题（`:34`） | `pi 无对应物`（Android 窗口/主题） | 一致：顺序是 `enableEdgeToEdge` 的文档要求 | 不动 |
| 单一 ViewModel | `MainActivity.kt:30` 与 `PiRoot.kt:73`、`ExtensionUiHost.kt:45` 三处 `viewModel()` 都取同一个 `ViewModelStoreOwner`（MainActivity），所以是**同一个**实例 | `pi 无对应物` | 一致（`ExtensionUiHost` 的 KDoc 明写这个前提） | 不动 |
| `PiRoot` 的组合副作用 | ① `LaunchedEffect(Unit) { session.boot() }`（`PiRoot.kt:74`）；② `LaunchedEffect(navRequest)` 消费一次导航请求（`:88-118`）；③ 只在 `Settings` 目的地才组合设置栈（`:167`） | `pi 无对应物`（导航） | 有意：导航请求走 state 而不是回调，因为它是 RPC 回答之后才发生的 | 不动 |
| 旋转 / 配置变化 | `android:configChanges="orientation\|screenSize\|screenLayout\|keyboardHidden\|uiMode\|density\|fontScale"`（`AndroidManifest.xml:115`）→ **Activity 不重建 ⇒ ViewModel 不换 ⇒ 引擎不重启**；`singleTask`（`:117`） | `pi 无对应物` | 有意（正面）：旋转/字体缩放/深浅色切换都不会中断回合 | 不动 |
| `MainActivity` 没有 `onStop`/`onDestroy` 钩子 | 全仓 `onDestroy` 只有两个 Service（`PiEngineService:177`、`DeviceAccessibilityService:46`） | `pi 无对应物` | 有意：引擎的寿命挂在 ViewModel + 前台服务上，不挂 Activity | 不动 |
| `BootScreen` | 纯展示（`BootScreen.kt:45-152`）；`Boot.Failed` 有「重试」（`:135`）→ `session.boot()` | `pi 无对应物` | 一致 | **本文新增用法**：引擎中途退出也复用这一屏（§5-2），标题从「引擎没能启动」改成「引擎没有在运行」 |
| `ChatScreen` 的 boot 门 | `if (state.boot !is Boot.Ready) { BootScreen(...); return }`（`ChatScreen.kt:176-183`） | `pi 无对应物` | 一致 | 成为"引擎没了 → 给重试"的现成通道 |

### A2. `PiSessionViewModel`

| 面 | 现状（`file:line`） | pi 对照 | 判定 | 处置 |
|---|---|---|---|---|
| 引擎归属 | `private val host = PiEngineHost(app)`（`PiSessionViewModel.kt:353`）——**per-ViewModel**，`session`/`api` 各一份（`:354`、`:361`） | `pi 无对应物` | 有意（"引擎比页面活得久"），但**代价**：`PiEngineHost` 的 KDoc 与 `PiEngineService` 的 KDoc 都写过"引擎挂在服务上"，实际是"挂在 App 进程上、服务只提供优先级" | 已改写两处 KDoc（`PiEngineService` 类注释） |
| 引擎状态收集器 | `attach()` 里 `engine.state.collect`（`:813-839`），并有一道 `if (session !== engine) return@collect`（`:821`） | `pi 无对应物` | **一致且必需**：旧引擎 `close()` 会发 `Stopped`，这道判断是"新引擎刚装上时不要被旧引擎的 Stopped 清空 api"的唯一防线 | 保留；退出分支补上 `session = null` + 失败态（§5-2） |
| 发布流收集器 | `engine.publication.collect`（`:846-852`）+ `engine.events.collect`（`:853-855`），都在 `viewModelScope` | `pi 无对应物` | 一致 | 不动 |
| attach 时的历史重放 | `replayHistory(engine)` → `get_entries` → `seedHistory`（`:860-885`、`:1564-1586`） | pi 的 TUI 用**进程内** entries 重画（`interactive-mode.ts:3922`）；RPC 客户端必须自己拉（`pi 有但我们够不着`：RPC 没有"给我重放"的通道，只有 `get_entries`） | 一致（唯一可行路径） | 不动 |
| `maybeResumeLastSession` 的 `resumeAttempted` | `:629` 定义、`:917-919` 一次性返回 | `pi 有`：`-c`（`cli/args.ts:100`、`main.ts:426-430`） | 有意：pi 的 `-c` 是**每次进程启动**一次；App 的一次引擎 = 一次 `-c`。副作用：引擎崩溃后「重试」拿到的是**新会话**（空转录），即使 `app.sessions.resumeLast` 是开的 | 已改：引擎**自行退出**（不是 restart 的一部分）时重置该标记；见 §5-2 | 
| 引擎死亡后的动作 | 原来只 `api = null` + 清扩展 chrome（`:833-836`），`session` 仍在 | `pi 无对应物` | **缺陷**：`send()`/`sendFollowUp()`/`runPromptCommand()` 走的是 `session`，不查 `api`（与 `:358` 的"每个动作都查 api"自述矛盾）→ 往关闭的 writer 写 | 已修（§5-2、§5-3） |
| `onCleared` | 先 `cancelAllDialogs()`，再把 `closeAfterSettling()` 交给**进程级** `teardownScope`（`:2479-2492`、`:2534`） | `pi 有`：pi 落盘在 `message_end`（`agent-session.ts:669-691`），文件在第一条 assistant 之前不存在（`session-manager.md`… 实为 `session-manager.ts:1029-1052`） | 方向正确（不能在 `viewModelScope` 里做收尾） | 已改：改调 `host.shutdown()`，让进程级 `lifecycleLock` 覆盖这次收尾（§5-4） |
| ViewModel 没有 `onStop`/前台判定 | `viewModelScope` 在 Activity 停止时**不暂停** | `pi 无对应物` | 有意：回合就得在后台跑 | 不动；但由此产生的"后台还在轮询"在 §B 逐条处置 |
| `teardownScope` 永不取消 | `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（`:2534`） | `pi 无对应物` | 有意：它必须比 ViewModel 活得久 | 不动（无子任务常驻） |

### A3. 引擎进程：`PiEngineHost` / `PiEngineSession`

| 面 | 现状（`file:line`） | pi 对照 | 判定 | 处置 |
|---|---|---|---|---|
| `scope` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，per-host，**从不取消**（`PiEngineHost.kt:67`） | `pi 无对应物` | 可接受：`close()` 会取消三个 job（`PiEngineSession.kt:802-804`），剩下的只有 `probeServing` 的超时协程 | 记录：视图模型被销毁后该 scope 里最多挂一个 5 分钟探测（§4-1） |
| `lifecycleLock` | 原为**实例字段**（`PiEngineHost.kt:74`，现已移入 companion） | `pi 无对应物` | **缺陷**：它保护的资源（cwd、会话 JSONL、guest agent dir）是进程级的，而 host 是 per-ViewModel 的 | 已改为进程级（§5-4） |
| 入口覆盖 | `boot()`（`:204`）、`restart()`（`:397`）、`shutdown()`（`:462`）三个入口都 `withLock`；`bootLocked()`（`:216`）供 restart 复用（`Mutex` 不可重入） | `pi 无对应物` | 一致（覆盖完整） | 不动 |
| `publish()` | 换引擎时**硬关**旧的（`:565-574`，`previous.close()`） | `pi 无对应物` | 与 `restart` 的"先 `closeAfterSettling` 再起新的"（`:425-436`）**不一致**，但 `boot()` 只在 `boot !is Ready` 时可被 UI 触达，所以硬关分支事实上不可达 | 记录为有意偏离（§4-2），不改 |
| `shutdown()` 原本**没有调用者** | `:462-467`；全仓 grep 只有定义 | `pi 无对应物` | **缺陷**：通知的「停止」走的是占位 `PiEngineController.stop()`，`shutdown()` 一次都没被跑过 | 已接入（§5-1） |
| 三个 job | `readerJob` / `stderrJob` / `waitJob`（`PiEngineSession.kt:210-212`，`start()` 在 `:291-312`） | `pi 有`：stdout 是 JSONL 帧（`modes/rpc/jsonl.ts:11`）、`end` 事件驱动关停（`rpc-mode.ts:804-807`） | 一致：stderr 必须排空（`:294-295`），否则 64 KB 管道填满会让引擎卡死 | 不动 |
| `waitJob` 是死亡真相 | `process.waitFor()` → `Stopped`/`Failed` + 失败所有 pending（`:305-311`） | `pi 无对应物`（进程退出就是退出） | 一致 | 不动 |
| `probeServing` | 300 s 超时的 `get_state`（`:342-357`、`:804`） | `pi 有`：`get_state` 由 stdin 读线程回答（RPC 契约） | 一致：超时**不算失败**，状态留在 `Starting` | 不动 |
| `close()` vs `closeAfterSettling()` | `close()` = 关 writer + `destroy()` + 取消 job（`:799-806`）；`closeAfterSettling()` = `get_state` →（流式中）`abort` → `close`（`:768-791`） | **`pi 有`（关键）**：关 stdin ⇒ pi 立即 `shutdown()`、**不等 agent**（`rpc-mode.ts:804-807` → `:727-745`）；`abort` 的 response 意味着已 `waitForIdle`（`rpc-mode.ts:428-431`、`agent-session.ts:1640-1646`） | 一致（正确顺序），且 `abort` 是 pi 自己的命令而非 sleep | 补：已退出就直接 close（§5-5） |
| `send()` 会抛 | 原来直接 `writer.write`（`:551-556`） | `pi 无对应物` | **缺陷**：UI 线程上的一次写失败 = 崩溃 | 已改成 `runCatching` 返回 Boolean（§5-3） |
| `PtyLauncher` / `PtySession` | `prepare()` 组 argv/env（`PtyLauncher.kt:143-…`）；`PtySession.close()` 幂等：关输出 → `destroy()` → 关输入（`PtySession.kt:77-83`），靠 proot 的 `--kill-on-exit` 带走子进程（`PiRuntime.kt:202`）；reader/waiter 都是 daemon 线程（`:131-158`） | `pi 无对应物`（pi 不起 pty） | 一致：关流是 reader 线程唯一的退出方式（KDoc 明写） | 不动 |
| 终端的持有层级 | `TerminalPane.kt:124-126` 的 `DisposableEffect(bridge)` → `bridge.close()`；`TerminalBridge.close()` 关 pty + `writer.shutdownNow()`（`TerminalBridge.kt:174-177`） | `pi 无对应物` | **有意但代价明确**：离开"工作区"目的地即结束终端（§4-3） | 未改，写进未验证/未决 |

### A4. 前台服务：`PiEngineService`

| 面 | 现状（`file:line`） | pi 对照 | 判定 | 处置 |
|---|---|---|---|---|
| `onCreate` | 只发布 `instance`（`:44-49`） | `pi 无对应物` | 一致 | 不动 |
| `onStartCommand` | 原来 `START_STICKY`，`else -> startForegroundWithNotification()`（`:51-60`） | `pi 无对应物` | **缺陷**：`intent == null`（进程被杀后系统复活服务）会重新发通知 + 上锁，而引擎已随进程消失 | 已修（§5-6） |
| 前台类型 | `dataSync \| specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（`AndroidManifest.xml:126-133`） | `pi 无对应物` | 一致 | 不动 |
| 唤醒锁 | `acquire(6h)`（`:157-160`），`setReferenceCounted(false)` | `pi 无对应物` | **缺陷**：整服务寿命持锁 → 空闲也醒着、6 h 后静默失效 | 已修（按"有活"持锁，§5-7） |
| `isRunning()` / `isWakeLockHeld()` | 静态读 `instance` / 读锁自己的 `isHeld`（`:159`、`:177`） | `pi 无对应物` | 一致（真值直读） | 语义变了：`服务在跑但锁未持有` 现在是**正常空闲态**；读这份数据的文案要跟着改（§5-7） |
| `stopEngineAndSelf` | `PiEngineController.stop()` + 放锁 + `stopForeground` + `stopSelf`（`:163-170`） | `pi 无对应物` | **缺陷**：`PiEngineController.stop()` 当时只写了一个没人读的枚举 | 已修（§5-1） |
| `updateNotification(runningTasks)` | `:120-123`，**无调用者**，通知文案恒为"0 个任务进行中" | `pi 无对应物` | **缺陷**（本仓库"无消费者"那一类） | 已修（由"有没有活"驱动 0/1，§5-7） |
| `onDestroy` | 放锁 → 清 `instance`（`:177-183`） | `pi 无对应物` | 一致 | 不动 |
| `keepAlive` 开关 | `app.runtime.keepAlive` 只决定"要不要起服务"（`PiSessionViewModel.kt:737`、registry `:1220-1230`） | `pi 无对应物` | 一致（关掉就没有前台服务，回合可能被系统冻结） | 不动（文案保留） |

---

## B. 定时 / 后台任务清单（全量）

**枚举方式**：`grep -rn "delay(\|while (true)\|postDelayed\|Handler(\|Timer(\|TimerTask\|AlarmManager\|WorkManager\|FileObserver\|LaunchedEffect\|produceState" app/src rpc/src`。
结论：**没有** `AlarmManager`、**没有** `WorkManager`、**没有** `java.util.Timer`、`Handler.postDelayed` 只有一处（TTS 引擎的延迟 `shutdown`，与引擎无关，`DeviceSystemActions.kt:628`）。
`while (true)` 的其余 14 处都是**阻塞式流循环**（读管道 / 解 tar / 拷文件），不是节拍，逐条在表后列出。

| # | 任务 | 为什么必须有（不给会怎样） | 频率 | 允许跑的状态 | 谁负责停 | 引擎/页面不在时会不会继续跑 |
|---|---|---|---|---|---|---|
| B1 | 对话框倒计时 `armDialogTimer`（`PiSessionViewModel.kt:1406-1428`） | 显示扩展要的「Title (5s)」倒计时；到点按 pi 的默认值回答（confirm→false、其余→cancelled） | 原 **200 ms 固定轮询**；显示只按整秒变 | 有**带 timeout 的**阻塞对话框在队首时才起 | `armDialogTimer(null)` / `settleDialog` / `cancelAllDialogs`（`:1407-1409`、`:1471-1476`） | **会**：它跑在 `viewModelScope`，App 退后台/息屏都继续（代价是每 5 次/秒的唤醒）。**已收敛为 ≤1 次/秒且对齐整秒边界**（§5-8） |
| B2 | 设备能力页轮询（`DeviceCapabilityScreen.kt:178-199`） | 屏幕外发生的事实（无障碍服务、Shizuku binder、桥的 audit、审批台账）没有事件通道，只能读 | **1500 ms** | 页面在组合里 | `LaunchedEffect` 离开组合时取消 | **会**：App 切后台时 `LaunchedEffect` 不被取消，仍每 1.5 s 一次 binder + 文件读。**已修**：只在 Activity ≥ STARTED 时跑（§5-9） |
| B3 | `@` 提及的防抖（`ChatScreen.kt:571-577`，`delay(MENTION_DEBOUNCE_MS)`） | 每个键击都跑一次 guest `fd` 进程是不可接受的（proot 启动是秒级） | 每次键入后一次（防抖窗口） | 输入里有 `@` 记号时 | `LaunchedEffect(mentionPrefix)` 重新键入即取消 | 不会（不是循环；页面一走就取消） |
| B4 | 模型清单的外部改动防抖（`PiModelsScreen.kt:127-131`，`if (reloadTick > 0) delay(200)`） | 一次原子写会产生多个 inotify 事件（临时文件、rename、属性），200 ms 合并成一次重读 | 每次文件变化一次 | 模型页可见 | `LaunchedEffect` | 不会（不是循环；页面一走就取消）。**依据**：`PiDirectoryWatch` 只在组合里装（`PiFileWatch.kt:73-89`） |
| B5 | 流式代码块的防抖高亮（`PiMarkdownComponents.kt:411-425`，`delay(STREAM_SETTLE_MS=200)`） | 每个 token 都请求一次高亮 = 每帧一次 socket 往返 | 每个仍在变化的代码块一次 | 代码块正在流式更新 | `produceState` 的 key 变化即取消 | 不会；页面不在组合里就不跑 |
| B6 | 终端状态提示的自动消失（`TerminalPane.kt:131-136`，`delay(2500)`） | 一次性 UI 提示（"已复制到剪贴板"）的自动隐去 | 每次提示一次 | 提示出现时 | 自身结束 | 不会 |
| B7 | `FileObserver`（`PiFileWatch.kt:73-99`） | 外部改动（pi 自己、终端、AI 工具）→ 界面必须知道；inotify 是唯一实时来源 | **事件驱动，静止时 0 开销** | 只在相关 Composable 在组合里时装（`onDispose` 停） | `onDispose`：`stopWatching`（`:85-88`） | 不会（明确刻意：掩码排除了 `OPEN`/`ACCESS`，否则会被自己的读唤醒，`:107-117`） |
| B8 | `ON_RESUME` 时的 `(size, mtime)` 比对（`PiFileWatch.kt:81-84`、`PiFileStamps.kt:49-72`） | inotify 在内存紧张时会被平台静默丢弃，这是兜底 | 每次回前台一次 | `ON_RESUME` | 一次性 | 不会 |
| B9 | 对话框时限的 pi 侧 `setTimeout` | pi 自己在 `timeout` 到点时用默认值 resolve，App 不回也一样 | 每个带 timeout 的请求一次 | —— | pi 的 `cleanup`（`rpc-mode.ts:103-120`） | 不适用（引擎侧） |
| B10 | `handler.postDelayed`（`DeviceSystemActions.kt:628`） | TTS 朗读超时后延迟 1.5 s 释放引擎 | 每次 TTS 超时一次 | —— | 自身结束 | 不会（与 pi 无关） |

**`while (true)` 里不是节拍的那些**（都是阻塞读/拷贝，退出条件是 EOF 或任务完成）：
`PiEngineSession.kt:370`（stdout 读到 EOF）、`PtySession.kt:134`（pty 读到 EOF）、`DeviceShell.kt:108`、
`DeviceUiAutomation.kt:167`、`DeviceShizuku.kt:261`、`PackageManagerService`/`GuestCommand.kt:168`、
`TarExtractor.kt:66`、`RuntimeProvisioner.kt:328`、`TrustFile.kt:135`、`TrustRepository.kt:310`/`:373`、
`PiConfigFiles.kt:104`、`PiHighlightClient.kt:226`（HTTP 头读到空行）、`PiNodeCodeHighlighter.kt:225`（工作线程等队列）。
其中 `PiNodeCodeHighlighter.BoundedWorkers`（`:177-243`）的线程 `waiting.await()` **永久阻塞**、daemon、数量有界
（首次提交时才建，`ui/render` 里按需创建）——记录为"进程寿命的常驻线程"，上限固定，不再增长。

**pi 侧的节拍（对照）**：

| pi 的节拍 | 条件 | 出处 |
|---|---|---|
| TUI 倒计时 | 每个带 timeout 的对话框一个 `setInterval(…, 1000)`，计数到 0 时 `dispose()` | `modes/interactive/components/countdown-timer.ts:13-31`（`setInterval` `:21`、`dispose` `:26-29`） |
| bash 工具卡片的 1 s 重绘 | 只在 `isPartial`（命令还在跑）时 `setInterval(…, 1000)`，结束即 `clearInterval` | `core/tools/renderers/bash.ts:140`、`:146-149` |
| 挂起时的保活 interval | 仅 `ctrl+z` 挂起期间，`setInterval(() => {}, 2**30)` 只为占住 event loop | `modes/interactive/interactive-mode.ts:4097` |
| 对话框超时 | `opts.timeout` → `setTimeout` → 用默认值 resolve；`cleanup` 清 timer | `modes/rpc/rpc-mode.ts:115-120`、`:103-107` |

**结论（对"空浪费性能"）**：pi 自己**没有任何"固定间隔轮询"**——它的两个节拍都只在"有东西在等"（对话框 / 命令在跑）时存在，且都在结束条件上 `dispose`。本 App 里唯一与之同类的固定轮询是 B1（已收敛）与 B2（已按可见性收敛）。

---

## 5. 本次修复（改动小、每处都有 `file:line` 依据）

| # | 文件 | 改动 | 依据 |
|---|---|---|---|
| 5-1 | `service/PiEngineController.kt` | 新增 `registerStopHandler` / `unregisterStopHandler`；`stop()` 现在真的调用拥有者的停机回调 | 通知的「停止」原来只写了一个**无读者**的枚举（旧 `:23-25`）；引擎是 `PiEngineHost` 的子进程，服务够不着它 |
| 5-1 | `ui/PiSessionViewModel.kt` | `stopEngineHook = { teardownScope.launch { host.shutdown() } }`，`init` 注册、`onCleared` 注销 | `PiEngineHost.shutdown()` 全仓无调用者；停机必须走 `closeAfterSettling` 这条已论证的路径（`session-lifecycle.md` §2.7） |
| 5-2 | `ui/PiSessionViewModel.kt` | 引擎进入 `Stopped`/`Failed` 时：`session = null`、`boot = Boot.Failed(...)`（→ 聊天页显示可重试的失败态），非 restart 场景下重置 `resumeAttempted` | `:358` 自述"每个动作都查 `api != null`"，而 `send`/`sendFollowUp`/`runPromptCommand` 用的是 `session`；`ChatScreen.kt:176-183` 已有 boot 门与重试 |
| 5-3 | `engine/PiEngineSession.kt` | `send()` 改为 `runCatching { … }.getOrDefault(false)`，返回 Boolean | 原文 KDoc 自称 "Fire-and-forget"，实现却让 `IOException` 从 UI 线程抛出 |
| 5-4 | `engine/PiEngineHost.kt`、`ui/PiSessionViewModel.kt` | `lifecycleLock` 改为进程级（companion `PROCESS_LOCK`）；`onCleared` 改为调 `host.shutdown()` | 锁保护的资源（cwd/会话文件/guest agent dir）是进程级的，host 是 per-ViewModel；`onCleared` 原来绕过 host 直接关 session，因此不受任何锁保护 |
| 5-5 | `engine/PiEngineSession.kt` | `closeAfterSettling()` 在**已退出**（`!process.isAlive` 或状态已 `Stopped`/`Failed`）时直接 `close()` | 否则 `get_state` 的 30 s 超时被白等一遍；死亡真相来自 `waitJob`（`:305-311`） |
| 5-6 | `service/PiEngineService.kt` | `onStartCommand` 走 `PiEngineLifecyclePolicy.startCommand`；两种情况都 `START_NOT_STICKY`；`intent == null` 视为停机 | `START_STICKY` + null intent = 复活一个没有引擎的服务（重新发通知 + 上锁） |
| 5-7 | `service/PiEngineService.kt`、`ui/PiSessionViewModel.kt`、`ui/settings/RuntimeFacts.kt` | 新增 `reportWork(active)`；唤醒锁按 `shouldHoldWakeLock(booting, turnRunning)` 取/放；通知计数由同一事实驱动；`app.runtime.wakeLock` 的文案改成"空闲不持锁"是正常的 | 原锁整服务寿命持有 + 6 h 上限（旧 `:148`、`:157-160`）；原通知计数恒 0（`:120-123` 无调用者） |
| 5-8 | `ui/PiSessionViewModel.kt` | 倒计时循环用 `PiEngineLifecyclePolicy.nextCountdownDelayMs` 睡到**下一个整秒边界**（≤1000 ms），删掉固定 200 ms 常量 | pi 的同类倒计时是 1 Hz（`countdown-timer.ts:21`）；显示只按整秒变 |
| 5-9 | `ui/screens/DeviceCapabilityScreen.kt`（+ 新的可见性小工具） | 轮询循环只在 Activity ≥ STARTED 时跑 | 现在 App 退后台仍每 1.5 s 一次 binder 调用；`PiFileWatch.kt:81-88` 已经用同一套 `LocalLifecycleOwner` + `LifecycleEventObserver` 写法，照抄它 |
| 5-10 | `bridge/DeviceBridgeHttp.kt` | `stop()` 里 `pool.shutdownNow()` | 每 `DeviceBridgeController.start()` 新建一个 4 线程池（`:140`），而 `start()` 每次 boot 都跑（`PiEngineHost.kt:255`）→ 旧池永不回收 |
| — | `docs/lifecycle-and-timers.md` | 本文 | —— |

**没有改的（有意）**：§A3 的 `publish()` 硬关；§4-3 的终端随页面销毁；§4-4 的 `stopWithTask`。

---

## 4. 未决 / 未验证项（需要设备的部分写"怎么测"）

1. **`PiEngineHost.scope` 的收尾**：ViewModel 销毁后，host 的 `scope` 里可能还挂着一个 `probeServing`（最长 300 s，`PiEngineSession.kt:342-357`）。
   **不修的理由**：唯一可行的做法是在 `shutdown()` 里 `scope.cancel()`，而 `shutdown()` 之后仍可能再 `boot()`（通知停止 → 用户按「重试」）——取消过的 scope 会让新引擎的 reader 永远不启动，比泄漏更糟。
   **怎么测**：设备上启动 App 后立刻返回退出（在 pi 还没回答 `get_state` 之前），观察 `dumpsys` 里进程是否仍持有该协程（或直接看 `probeServing` 是否在 5 分钟后自然结束）；这不是可观察的用户行为，优先级低。
2. **`bootLocked` 的硬关分支**（`PiEngineHost.kt:334-363` 里的 `publish`）：UI 无法在 `boot is Ready` 时触发 `boot()`，所以"先硬关旧引擎"事实上不可达。
   **怎么测**：无需测；若将来把「重试」接到 `boot()` 之外的入口，必须改成 `closeAfterSettling`。
3. **工作区终端随页面销毁**（`TerminalPane.kt:124-126`）：切到「对话」就 `bridge.close()` → 终端里的 `npm install`/`pi` 一起死。修它要把 bridge 提到目的地之上（`PiRoot`）持有，属于结构性改动，本次不动。
   **怎么测**：设备上在工作区终端里跑 `sleep 60`，切到「对话」再切回 —— 现在会看到终端被重开（`sleep` 不在了）。
4. **从最近任务划掉 App**：manifest 没有 `android:stopWithTask="false"`（`AndroidManifest.xml:125-133`），系统默认会结束服务并杀进程 ⇒ 回合中断。设计文档记过这个先例（`docs/pi-android-app-design.md:1108`），是一次产品决定，本次不翻。
   **怎么测**：设备上让一个回合跑着，从最近任务划掉，再从会话列表看那条 assistant 消息在不在。
5. **唤醒锁策略的实测**：6 小时上限现在只影响"单次超过 6 小时的工作段"（§5-7）。真机上没有验证过"回合开始时锁被重新拿到、回合结束被放掉"。
   **怎么测**：`adb shell dumpsys power | grep -i "pi:engine"`（或设置 → 运行时 → 唤醒锁状态那一行）在空闲时应为「未持有」、发一条消息后变「持有中」、回合结束回到「未持有」。
6. **`send()` 不再抛**：真机上"引擎崩了之后点发送"现在应当显示失败屏而不是崩溃。
   **怎么测**：设备上用 `adb shell run-as app.pi kill <pi 的 pid>`（或让引擎 OOM）之后点发送/后续，看是否出现「引擎异常退出」+「重试」，且不发生崩溃。
7. **`resumeAttempted` 重置的观感**（§5-2）：`app.sessions.resumeLast` 打开时，引擎崩溃后按「重试」应当回到崩溃前那条会话。
   **怎么测**：打开该开关，聊一句，杀引擎，按「重试」，看转录是否回到该会话。
8. **B2 的可见性门**：切后台时 1.5 s 轮询应当停止。
   **怎么测**：设备能力页停留 → 切后台 → `adb shell dumpsys activity service`/日志观察是否还有 binder 活动；亦可临时在循环里打一行日志对比前后台。

---

## 6. 上 CI 最可能出错的点

1. **`tools/run-app-pure-checks.sh` 新增的 `lifecycle-policy` harness**：它把
   `app/src/main/kotlin/app/pi/service/PiEngineLifecyclePolicy.kt` 与 harness 一起交给 kotlinc 编译。
   最容易出错的两处：① 策略对象里**误引 Android 类型**（`Intent`/`PowerManager`）——它必须"import 为空"；
   ② `PiEngineService` 的 `const val ACTION_STOP = PiEngineLifecyclePolicy.ACTION_STOP` 必须仍是**编译期常量**
   （普通 `val` 会让 `when`/常量语境失效，也会让 harness 侧的比较失去意义）。
2. **`PiSessionViewModel` 的三处非局部时序改动**（引擎死亡分支、`attach`、`onCleared`）：
   任何一处把 `session` 在**错误时机**置空，症状都是"UI 变哑"而不是编译错误。CI 只能保证编译；
   行为要靠 §4-6 的设备步骤。
3. **`BootScreen` 的标题文案改动**：`Boot.Failed` 现在同时表示"首次启动失败"和"引擎中途退出"，
   标题若仍写「引擎没能启动」就会与实际不符（CI 看不出来）。
4. **`DeviceCapabilityScreen` 的可见性小工具**：本机**不能编译 Compose**（`tools/typecheck.sh` 不带 Compose 插件），
   所以新加的 import（`LocalLifecycleOwner`、`LifecycleEventObserver`、`DisposableEffect`）只能靠 CI 的
   `:app:assembleRelease` 验证。写法刻意照抄 `PiFileWatch.kt:73-88`（同一套 API 已被本仓库编译过）。
5. **`tools/check-nested-comments.py`**：本次新增的 KDoc 里有 `/*` 形状的示例会触发
   （本文档与代码注释里出现过 `%d`、`/app/*`），提交前用 `python3 tools/check-nested-comments.py` 自检。
6. **未跑的检查**：按用户要求，本机**没有**跑 `tools/typecheck.sh`、`tools/run-app-pure-checks.sh`、
   Gradle，也没有跑 `check-nested-comments.py`（本轮最后一次编辑之后）。CI 是唯一的编译器。
