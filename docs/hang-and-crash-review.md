# 卡死与闪退审查（hang-and-crash-review）

> **本机没有编译。** 用户明令禁止在这台机器上跑 `tools/typecheck.sh`、`tools/run-app-pure-checks.sh`、Gradle ——
> 这台"开发机"就是用户的手机。**CI 是唯一的编译器。** 本文里凡是"已修"的结论，证据级别只有两级：
> 代码 + 纯逻辑推理（能进 harness 的进了 harness，按用户要求**只登记、没在本机跑**）。
> 行号是**写作时**的行号，改动点自己带注释；同树里另有代理在改 `RuntimeProvisioner.kt` /
> `PiSettingsRegistry.kt` / `PiSettingsStack.kt` / `DiagnosticsReport.kt` / `DiagnosticsScreen.kt`
> （与本文件无关），它们一动行号还会位移。
>
> **两个症状分开查**，每条假设都写成可证伪的四段：现象如何产生 / 代码证据 / 本机与真机怎么验证 / 现有改动是否已覆盖。
> 一句话结论：**"重放把主线程卡住"这条老根因已经不在树里**（`seedHistory` 早就是 `Dispatchers.Default`），
> 现在能把这两个症状串起来的是**同一条传输层事实**：App 读不了 pi 发来的大记录时，
> 既不会立刻失败、也不会告诉用户 —— 它**等满超时**，而"刚开始回复就卡死"的另一半是
> **主线程上的管道 I/O 与读取无界**（本次已修）。

---

## 0. 结论速览

| # | 结论 | 性质 |
|---|---|---|
| 1 | `readLoop` 对每条记录**没有任何异常隔离**：`foldEvent` 抛出一次，进程唯一的 stdout 读者就死了（`SupervisorJob`、无人观察），pi 写满 64 KB 管道后阻塞 ⇒ 回合永久卡住、之后每次 RPC 都等满自己的超时、唤醒锁不放 | 已修（逐条隔离） |
| 2 | 超过 8 MiB 的单条记录被 framer **静默丢弃**，而等它的请求只能等满 **120 s** 默认超时；`get_entries` 返回整条会话（**一个**记录），所以"打开历史会话"正好撞上这一条 | 已修（立刻失败 + 上限自洽） |
| 3 | 附件上限（8 MiB）**恰好等于** framing 上限（8 MiB），而附件以 base64 回显 ⇒ 任何合法尺寸的附图都保证把自己发出去的那条会话变成读不开的会话 | 已修（上限从 framing 上限推导） |
| 4 | `send()` 在主线程上 `writer.write` + `flush()` 一个管道：pi 启动期间不读 stdin，任何大于 64 KB 管道缓冲区的命令都把**帧线程**堵住 | 已修（专用写线程） |
| 5 | 附件读取在主线程上 `readBytes()` 整个流，**读完才**比大小 ⇒ 云端相册给多大就分配多大，超限也一样 | 已修（有界读 + 挪到 IO） |
| 6 | 会话文件扫描的"1 MiB 预算"是假的：`readLine()` 先返回整行，再比预算。会话文件的行**就是**消息，一行可以是一张图或一大段工具输出 | 已修（`SessionFileScan`，纯函数 + harness） |
| 7 | 引擎退出后 App **不会继续等**（`waitJob` 失败所有 pending，UI 转失败屏/重试），也**没有**自动重启引擎的路径 ⇒ 不存在"反复解包重启导致的卡死" | 无需修改，已核对 |
| 8 | `TailFollow` 的"measure → 请求滚动 → 再 measure"**活锁不成立**：同一几何 + 同一 pin 不重复下发，pin 在尾部可见后返回 null | 不改，只写真机验证步骤 |
| 9 | 引擎退出码本身**没有被 App 记录**（`waitJob` 只把它写进一句话），所以"code 1 到底是谁"只能靠真机的 stderr 尾部 | 记录 + 取证协议（另一代理的诊断报告正好是这个入口） |

---

## A. 症状一：在对话列表里点开之前的对话 → 直接卡死

### A 表（按可能性排序）

| # | 假设 | 现象如何由此产生 | 代码证据 | 如何验证 | 判定 |
|---|---|---|---|---|---|
| A1 | **`get_entries` 的响应超过单条记录上限，被 framer 丢掉；等它的请求只能等满 120 s，然后 `replayHistory` 静默返回** | 打开会话 = `switch_session` → `get_entries`（整条会话在**一个** JSONL 记录里）→ 若该记录 > 8 MiB，framer 丢弃它 → 这条 `response` 永远不来 → `request` 等满 120 s → 合成失败 → 重放"失败时不清空已有转录"（有意的），于是**画面没有任何变化**、`busy` 一直挂着。用户看到的就是"点进去直接卡死" | `rpc/.../Jsonl.kt:69-73`（达到上限即 `dropping`）、`:109`（`DEFAULT_MAX_RECORD_CHARS = 8 MiB`）、`:31`（只计数）；**全仓没有读者读这个计数**（只有 `JsonlFramerTest`）；`engine/PiEngineSession.kt:666-676`（`request` 默认 120 s）、`:335-341`（只有进程退出才失败 pending，丢记录不算）；`ui/PiSessionViewModel.kt:1764-1772`（`!success` 直接 return，不报错）；`ui/screens/ChatScreen.kt:1738-1742`（改前附件上限 8 MiB，与 framing 上限同值） | 本机：只能算出可达性（pi 自己的工具结果上限 50 KB × 一个长会话；含 base64 图片的行更大）。真机：看会话文件大小与最长行长度（§7-②）。**不需要**猜：改后若这条发生，界面会立刻给出那句失败文案，而不是等 120 s | **已修**：`readLoop` 一旦发现丢记录就立刻失败所有在途请求（`PiEngineSession.kt:409-421`、`:448-454`）；附件上限改为由 framing 上限推导（`ChatScreen.kt:1738-1742`），使"合法附件产生的回显记录"不再可能超限 |
| A2 | 重放在主线程上逐条 reduce（老根因） | 条目多会冻帧 | `PiEngineSession.seedHistory` 已是 `withContext(Dispatchers.Default)` + 换 reducer（`PiEngineSession.kt` 的 `seedHistory`）；`replayHistory`（`PiSessionViewModel.kt:1764-1786`）只 await | 读代码即可：投影不在帧线程 | **不成立**（树里已不存在），只保留"重放期间到达的事件被丢弃"这个已记录窗口 |
| A3 | 打开会话后**一次性把整条转录灌进 UI**，主线程做 O(n) 工作 | `syncTranscript` 每次发布都 `pub.rows.any { …Pending }` + `_state.value.copy(...)` | `PiSessionViewModel.kt:1191-1236`（`interrupted` 扫描 `:1195`、adopt 分支 `:1207`） | n=2000 时是 2000 次指针比较 + 一次列表引用复用，微秒级 | **不是病根**；残留（把扫描挪进 engine 的发布）留作优化，不做 |
| A4 | **列会话时读文件无界**：`readLine()` 先整行返回，再比 1 MiB 预算 | 列表页要遍历会话目录（平铺布局 ⇒ 目录里每个 `.jsonl`），一个带图/大工具输出的会话文件里会有一行几 MB～几十 MB：峰值 = reader 缓冲 + `String` + `JsonObject` 树，三份 | 改前 `session/PiSessionStore.kt` 的两处 `while (consumed < headerScanBudget) { reader.readLine() … }`；预算 `:78` | 本机：造一个 2 MiB 单行的会话文件，用 `SessionFileScan` 的 harness 断言（已加，§8）。真机：列表页内存曲线 | **已修**：`session/SessionFileScan.kt`（总预算 + 单行上限都真实生效），`PiSessionStore.kt:184-196`、`:246-307` |
| A5 | 列表刷新的**总** IO 无界：300 个文件 × 每个最多 1 MiB | 会话多时一次刷新读 300 MB | `PiSessionStore.kt:91-115`（每文件 1 MiB 预算，`limit = 300` 只截断**结果**不截断**读取**） | 真机：`dumpsys` / 简单计时；也可数会话文件个数 | **未修**，写进残留（§10）。有界但很贵，属于"要不要缓存索引"的产品决定 |
| A6 | `Transcript.kt` / `PiSessionStore.kt` 遇到坏行/缺字段/大 base64 **抛出到组合里** | 抛在 Compose 组合里 = 闪退 | `PiEvents.parse` 永抛不出（`rpc/.../Events.kt:414-428` 双层 `try`）；reducer 的取字段全是 `as?` / `?:`；`Transcript.kt:1167`/`:1190` 的 `index!!` 由上面三行的 `current != null` 保护（同一把锁内单线程）；`PiSessionStore.kt:114` 的 `subList` 有 size 判断 | 本机：`SessionFileScan` harness 覆盖坏行/空行/超长行 | **本机没有找到能证明的抛出点**；"打开就闪退"更可能是 OOM（A4/A5 的内存形态）而不是未捕获异常 |
| A7 | 打开会话时 **OOM**（大响应 JSON 树 + 行副本 + Compose） | 1.21 GB 可用 + 应用堆上限（未设 `largeHeap`，未在本机核对 manifest 行号） | 同 A1/A4 的分配量级 | 真机：`logcat` 里 `OutOfMemoryError` / `lowmemorykiller`（§7-③） | **未定**，需要真机 |

**A 的净结论**：老根因（主线程重放）已不存在；本次修掉了"大记录 ⇒ 静默 120 s 等待"和"读文件无界"两条。
剩下最需要真机确认的是 A1 的**前提**（用户的会话里到底有没有 > 8 MiB 的行）与 A7。

---

## B. 症状二：刚开始回复消息 → 直接卡死

### B 表（按可能性排序）

| # | 假设 | 现象如何由此产生 | 代码证据 | 如何验证 | 判定 |
|---|---|---|---|---|---|
| B1 | **stdout 读者被一条事件杀死，之后什么都没了** | `readLoop` 是进程里**唯一**读 pi stdout 的地方，跑在 `scope`（`SupervisorJob`）的一个子 job 里，没有任何人观察它是否结束。`handle` → `foldEvent`（reducer）若抛出一次，循环整体结束：App 不再读一个字节 → pi 的 `write` 在 ~64 KB 管道写满后**阻塞** → 事件、`response` 全都不再来 → `state` 停在 `Busy`、唤醒锁一直持有、每个后续 `request` 各自等满超时 ⇒ "刚开始回复就卡死，再也不动" | 改前 `PiEngineSession.kt` 的 `readLoop`：`for (record in records) handle(record)` 裸调用；`handle` 里 `synchronized(transcriptLock) { foldEvent(event) }` 也是裸的；`scope` = `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（类头），`readerJob` 无人 `.invokeOnCompletion` | 本机：无法证明 reducer 会抛（见 A6），但**"抛一次就永久卡死"这个放大机制本身**是可证的：隔离前后行为完全不同。真机：§7-④（回合中看 pi 进程是 `S`/`D` 还是被 pipe 堵住） | **已修**：`handle` 里逐条隔离（`PiEngineSession.kt:498-502`），并写清为什么只 catch `Exception` 而不是 `Throwable` |
| B2 | **主线程写管道被堵**（ANR 类） | `send()` 在**调用者线程**上 `writer.write` + `flush()`；调用者是 `viewModelScope`（`Dispatchers.Main.immediate`）⇒ 帧线程。管道的写是**无界阻塞**的：pi 在启动完成前根本不读 stdin（这正是 `probeServing` 存在的原因），正在解析大记录时也不读。命令大于 64 KB 管道缓冲区（一张附图就是几 MB base64）就把帧线程堵到 pi 开始读为止 —— 用户看到的是"点了发送，界面死住" | 改前 `PiEngineSession.kt:563-569`（`send`）；调用链 `PiSessionViewModel.kt:1951-1987`（`send`）→ `PiEngineSession.prompt`、`:1999-2037`（`runPromptCommand`）、`:2059-2074`（`sendFollowUp`）；`probeServing` 的超时是 300 s（同文件常量），说明"启动期间不读 stdin"是设计事实 | 本机：**不能**复现（要真管道 + 慢读者）。真机：§7-⑤（发一条带附图的消息，看是否 ANR；`logcat` 有无 `Input dispatching timed out`） | **已修**：专用单线程写者（`PiEngineSession.kt:171-176`、`:652-668`），编码也挪进去；`close()` 改成排在写者后面并**有界**等待（`:893-905`、`:964`） |
| B3 | **附件读取在主线程且无界** | 选图回调在主线程（`rememberLauncherForActivityResult` 在主线程 resume）；改前是 `openInputStream(uri)?.use { it.readBytes() }`，**读完**才和 8 MiB 比：相册/云盘给多少就分配多少，然后还要 base64 一份 | 改前 `ChatScreen.kt:295-314`；上限 `:1694` 区域（`private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024`） | 本机：读代码可证（顺序：先读后比）。真机：§7-⑤ | **已修**：`readBounded`（最多 `limit + 1` 字节，`ChatScreen.kt:1753-1766`）+ 读取与编码都进 `Dispatchers.IO`（`:288`、`:313-341`） |
| B4 | **附图会话的自我损坏**（与 A1 同源，但发生在"发出去那一刻"） | 上限相等 ⇒ 只要用户附了一张接近上限的图，pi 回显这条消息的记录必然超限 ⇒ 之后这条会话**再也打不开**（A1 的 120 s）⇒"一聊天就卡死"与"点历史会话卡死"在用户那里是同一件事的两面 | 同 A1 | 同 A1 | **已修**（上限同源推导） |
| B5 | `TailFollow` 的 measure→pin→measure **活锁** | 每次 remeasure 又发一次 pin，永不收敛 ⇒ 列表永远在测量，帧率掉到 0 | `ui/chat/TailFollow.kt:220-226`（几何 + pin 都相同则不下发）、`:331-339`（尾部底边进入视口后 `pinToTail()` 返回 null）；`ChatScreen.kt:497-532`（`snapshotFlow` 只读布局状态，且 pin 只在 `!isScrollInProgress` 时发） | 本机：已有 52 条 `tail-follow` 检查（**本次没有跑**）。真机：§7-⑥ | **不成立**（不再作为嫌疑），**不改**；真机只做观感确认 |
| B6 | `_events`（`extraBufferCapacity = 256` + `tryEmit`）在事件密集时**静默丢事件** | 帧线程上的收集者落后 256 条后，`tryEmit` 返回 false 且**丢弃**：转录不受影响（走 `publication`，是 StateFlow，只合并**不丢**），但 `agent_settled`（→ 刷新状态/统计）、`queue_update`、`bash_execution_update`、**`extension_ui_request`** 会丢 —— 丢一个对话请求就是"扩展在等一个永远不出现的对话框"，直到 pi 自己的超时兜底 | `PiEngineSession.kt:178`（容量）、`:507`（`tryEmit`）；`PiSessionViewModel.kt` 的 `engine.events.collect` 在 `viewModelScope` | 本机：读代码可证"满了就丢"。真机：§7-⑦（长流式期间扩展对话框/队列 chips 是否失联） | **未修**：正确修法是给事件通道做背压或分类（对话框必须不丢、token 类可丢），这是设计改动，写进 §10 |
| B7 | 引擎在这**一刻**死了（code 1），界面在等一个永不来的响应 | 见 C 节：`waitJob` 会失败所有 pending，所以**不会**永等；但 `busy` 标签/`streaming` 的收尾取决于死亡分支是否走到 | `PiEngineSession.kt:335-341`；`PiSessionViewModel.kt:971-1024` | 见 C 节 | **已覆盖**（§C 逐条核对） |

**B 的净结论**：最像"刚开始回复就卡死"的是 **B1（读者一次异常即永久卡死）** 与 **B2/B3（帧线程上的无界 I/O）**；
B5 被排除。三条里两条已修，B1 的"触发源"（reducer 会不会抛）本机仍不能证明 —— 但无论触发源是什么，
"抛一次就永久卡死"这条放大链已经不存在。

---

## C. `rpc: engine exited with code 1` 时 App 做了什么

### C1. 这句话是谁说的

不是 pi 说的，是**App 自己**拼的：

* `engine/PiEngineSession.kt:335-341`（`waitJob`）：`process.waitFor()` 拿到退出码后，把**所有**在途请求
  以 `failure("engine exited with code $code")` 完成。
* `engine/PiEngineApi.kt:20-24`：`PiRpcException` 的 message 是 `"${command ?: "rpc"}: $reason"` ——
  `failure()` 的 `command` 是 `null`，于是前缀成了 `rpc`。

所以用户看到的 "rpc: engine exited with code 1" 的**准确含义**是：
**pi 进程以状态 1 退出，而当时正好有一个 RPC 命令在等它的 response。** 它不是模型报的错，也不是网络错。

### C2. 退出后 App 的状态（逐条）

| 面 | 结果 | 证据 |
|---|---|---|
| 引擎状态 | `Stopped`（code 0）/ `Failed`（非 0） | `PiEngineSession.kt:336-337` |
| 在途请求 | 全部立即失败（不会永等） | 同上 `:338-340` |
| ViewModel | `api = null`、`session = null`、`streaming = false`、`bash = null`、`busy = null`，对话框取消、扩展 chrome 清空 | `PiSessionViewModel.kt:991-1011` |
| 界面 | `Boot.Failed` ⇒ `ChatScreen.kt:179-183` 换成可重试的失败屏（标题已是「引擎没有在运行」） | `PiSessionViewModel.kt:1003-1010`；`ui/screens/BootScreen.kt` |
| 前台服务 | 非重启场景下停掉（没有引擎要保护） | `PiSessionViewModel.kt:1012-1021` |
| 唤醒锁 | `reportWakeLockNeed()` 重算 ⇒ 释放 | `PiSessionViewModel.kt:1023`、`PiEngineLifecyclePolicy.shouldHoldWakeLock` |
| 续接标记 | `resumeAttempted = false` ⇒ 重试会再试一次 `-c` | `PiSessionViewModel.kt:1019` |
| 显示的错误 | `call()` 捕获 → `fail(message)` → 错误行 + 提示；`onEvent(Response)` 也会写 `lastError` | `PiSessionViewModel.kt:1732-1746`、`:1370-1372` |

### C3. "引擎死了它还会等吗" —— 所有等待点逐个问

| 等待点 | 引擎死了会怎样 | 判定 |
|---|---|---|
| `PiEngineSession.request`（所有 RPC） | `waitJob` 已失败 pending ⇒ 立刻返回失败 | 不会永等 |
| `closeAfterSettling` | `alreadyExited()` 成立 ⇒ 直接 `close()`，不等 30 s | 不会永等 |
| `stopAndDrainQueue`（停止按钮） | 两个 `request` 立刻拿到失败 | 不会永等 |
| `replayHistory`（切换/attach 的重放） | 立刻失败并**故意不清空**已有转录 | 不会永等（但也没告诉用户 —— 这是 A1 的"静默"那一半） |
| `armDialogTimer`（扩展对话框倒计时） | 死亡分支 `cancelAllDialogs()` | 不会继续 |
| `probeServing`（300 s 探测） | 立刻失败，不算"serving" | 不会永等 |
| `PiMentionSource`（`@` 列表，跑 guest `fd`） | 不依赖引擎（另一条 guest 命令通道），`mentionRequestId` 负责丢弃过期结果 | 与引擎无关 |
| **`PiEngineHost._session` / `host.session`** | **仍然是那个死对象**：只有 `boot`/`restart` 的 `publish` 会清它（`PiEngineHost.kt:563-572`），ViewModel 的死亡分支只清自己的字段 | **陈旧但不会等**。`turnRunning` 读它的状态（`Failed`）⇒ 重启保护仍然正确。**未修**，见 §10-3 |
| 自动重启 | **不存在**：`restartEngine` 只被设置页的 `EngineRestartCoordinator` 调用，`boot()` 只有 `PiRoot.kt:74` 的 `LaunchedEffect(Unit)`（每进程一次）与失败屏的重试按钮 | 不会出现"反复解包重启" |

### C4. App **报不出**退出码（真机证据的缺口）

`waitJob` 把退出码写进一句话就丢了（`PiEngineSession.kt:336`），所以诊断报告里
`EngineDiagnostics.exitCode` 按构造永远是 `null`（同树另一代理正在加的
`ui/settings/DiagnosticsReport.kt:38-44` 自己写明了这一点）。**这正是 §7-① 要向用户要 stderr 尾部的原因**：
`stderr` 是 64 KB 有界捕获的（`PiEngineSession.kt:330`、`:917`），它能直接回答"谁让 pi 退出的"。

---

## D. 闪退（进程崩溃）与内存

### D1. 已修的两条（都在上面）

* 附件：主线程无界读（B3）。
* 会话文件：单行无界读（A4）。

### D2. 逐个查过、**没有**发现可证问题的位置（避免下一轮白干）

| 位置 | 查法 | 结果 |
|---|---|---|
| `!!` / `first()` / `single()` / `getValue` / `check(` / `require(` / `subList` | 全仓 `grep`，逐个人工看是否有数据驱动的抛点 | 主题的 `getValue` 全是常量 token 表（缺键是编程错，不是数据错）；`Transcript.kt:1167`/`:1190` 的 `index!!` 被紧邻的 `current != null` 保护；`PiSessionStore.kt:114` 的 `subList` 有 size 判断 |
| `Regex(` 编译 | 看是否在每帧/每 token 路径上 | 都是模块级常量（`ui/render/PiMarkdown.kt:275` 等）；`PiLatex` 里有逐调用构造（`:678-719`），但只在含 `$` 的文本上走到 |
| JSON 解析 | 坏行/坏形状 | `PiEvents.parse` 双层 `try`（`rpc/.../Events.kt:414-428`），失败即 `PiEvent.Unknown` → 一条可见的 Notice 行；`PiJson.parseObjectOrNull` 也不抛（`rpc/.../PiJson.kt:20-24`） |
| `TranscriptReducer` | 读 `onEvent`/`onMessageUpdate`/`failTurn`/`removeFirst` | `onEvent` 自述 "Never throws"，本次核对未发现反例；`onUserMessageEnd` 的 `removeFirst()` 有 `isNotEmpty()` 守卫（`Transcript.kt:816-825`） |
| `PiModelsScreen` / `PiFileWatch` / `PiScreenVisibility` / `GuestWorkspacePath.ensureHost()` | 读文件 + 生命周期 | 分别是：防抖 + `FileObserver`（掩码排除 `OPEN`/`ACCESS`，不会自触发）、`DisposableEffect` 注销、`mkdirs()` 幂等；没有主线程阻塞文件 IO 的证据 |
| `stderr` 持有 | 有界？ | `MAX_STDERR_CHARS = 64 KB`（`PiEngineSession.kt:917`，追加处 `:330`） |
| `JsonlFramer.pending` | 有界？ | 8 MiB 字符上限（`rpc/.../Jsonl.kt:109`）；注意单个中间 `StringBuilder` 在 UTF-16 下峰值约 16 MB，这是**设计上限**不是泄漏 |
| 转录列表 | 无界持有？ | 有意无界（`renderWindow` 只管渲染，`ChatScreen.kt:395`）；行里保留工具输出与图片 base64（`ToolCall.images` **有**渲染，`ui/blocks/ToolCallBlock.kt:278`），所以这是"功能需要"的内存，不是死数据 |

### D3. 本次**没有**动的（写在这里，别当成已修）

1. `PiSessionStore.list()` 的总 IO 无界（A5）。
2. `_events` 满 256 条后静默丢事件（B6）。
3. 引擎死后 `PiEngineHost` 仍持有死对象（C3 末行）。
4. `app.runtime.keepAlive=false` 时没有前台服务，回合可能被系统冻结 —— 这是既有开关语义，不是 bug。

---

## 6. 已修清单（改动 → 依据 → 位置）

| # | 改动 | 依据（改前的问题） | 位置（改后） |
|---|---|---|---|
| 1 | `handle` 逐条隔离 reducer 异常：一条坏事件不再终结唯一的 stdout 读者 | `readLoop` 裸调 `handle`，`SupervisorJob` 静默吞掉 job 失败 ⇒ pi 写满管道阻塞 ⇒ 永久卡死 | `engine/PiEngineSession.kt:495-499` |
| 2 | framer 丢记录时**立刻**失败所有在途请求，并给出用户可读的后果 | `droppedRecords` 全仓只有测试读；响应被丢 ⇒ 等满 120 s 再静默失败 | `engine/PiEngineSession.kt:408-421`、`:448-454` |
| 3 | 附件上限由 framing 上限推导（`(cap - slack)/4*3`，≈5.95 MiB），两个常量不可能再漂开 | 8 MiB 附件 ⇒ base64 ≈10.7 M 字符 ⇒ 必然超 8 MiB 记录上限 ⇒ 该会话的 `get_entries` 读不回来 | `ui/screens/ChatScreen.kt:1738-1742` |
| 4 | 命令写入挪到专用单线程写者，编码也在那里；`close()` 排在写者之后并**有界**等待 | 帧线程上的 `write`+`flush`（ANR）；原 `synchronized(writer)` 还让帧线程可能等锁 | `engine/PiEngineSession.kt:171-176`、`:652-668`、`:893-905`、`:964` |
| 5 | 附件读取有界（最多 `limit+1` 字节）且读取/编码都在 IO | 主线程 `readBytes()` 整流、读完才比上限 | `ui/screens/ChatScreen.kt:288`、`:298-341`、`:1753-1766` |
| 6 | 会话文件扫描换成有界扫描器：总预算 + 单行上限都真实生效，超长行跳过而不是整行读入 | `readLine()` 先返回整行再比预算 ⇒ 1 MiB"预算"形同虚设；一行可以是图/大工具输出 | 新增 `session/SessionFileScan.kt`；`session/PiSessionStore.kt:78`、`:184-196`、`:246-307` |
| 7 | 用户可见文案：附图超限不再打印由"有界读"得出的假尺寸；丢记录时的失败文案说明后果与下一步 | 文案规则（无路径/类名/内部设计） | `ChatScreen.kt:325-329`；`PiEngineSession.kt:448-454` |

**没有改的**：`Transcript.kt`、`PiEngineApi.kt`、`PiEngineHost.kt`、`PiEngineService.kt`、`PiRoot.kt`、
`TailFollow.kt`、`docs/known-gaps.md`（用户明令）。**没有 git 写操作。**

---

## 7. 还需要真机才能定的：可执行取证协议

> 原则：**不给界面加调试计数器**。下面每一步都写清"点哪里 / 看什么 / 把什么发回来 / 需要哪条权限或开关"。
> 所有需要权限的步骤都注明了**用户要自己去哪里开**，命令不要反复重试（开关不会自己变）。

**先决条件（一次即可）**
1. 设置 → 应用 → pi → 通知权限打开（Android 13+；不开也能跑，只是前台服务不可见）。
2. 如果能开，`设置 → 开发者选项 → USB 调试`（无线调试更佳）。**不开也可以**做 ①–⑦，只是少一列证据。

**① 引擎退出时到底发生了什么（最重要）**
* 点哪里：让引擎崩一次（重试到出现「引擎没有在运行」）。然后进 设置 → 运行时与诊断 → **导出诊断报告**
  （入口由同树另一代理在这次改动里加；如果界面上还没有这一项，跳过本步并直接做 ②）。
* 看什么 / 发回来：报告全文（它含 **stderr 尾部**、运行时是否解包、App/设备版本、内存与存储）。
* 这一步直接回答 "code 1 是谁"：Node 未捕获异常 / `process.exit(1)` / proot 启动失败 / OOM 各有各的 stderr 形状。
* 需要什么：无需额外权限（导出到 Download 或用系统分享）。

**② 打开历史会话到底卡在哪**
* 点哪里：列表 → 点那条打不开的会话，**看 3 分钟不要动**。
* 看什么：① 是否出现「引擎发来的一条内容太大…请重试」这类失败文案（= 命中 A1，本次新加的立刻失败）；
  ② 还是**什么反应都没有**（= 另一种静默等待）。
* 发回来：那 3 分钟里界面上的任何文字（截图即可）；以及**别动手机时**的那条会话文件有多大
  （文件管理器/终端里看 `<应用私有目录>/pi/.pi/agent/sessions/` 下的 `.jsonl` 大小与最大单行长度；
  这一步只在能进终端时做）。

**③ 是不是内存问题**
* 点哪里：复现一次卡死/闪退，然后 `adb logcat -d | grep -iE "OutOfMemoryError|lowmemorykiller|FATAL EXCEPTION|ANR in app.pi"`。
* 看什么 / 发回来：`OutOfMemoryError` 或 `FATAL EXCEPTION` 的整段堆栈（**这是唯一能证 D 节的东西**）。
* 需要什么：ADB（用户自己在开发者选项里开）。

**④ 卡死时 pi 进程是什么状态**
* 点哪里：卡死时 `adb shell ps -A | grep -i node`（或看 `top`），以及 `adb shell dumpsys activity services app.pi`。
* 看什么：node 是否还在、CPU 占用是 0（= 阻塞）还是 100%（= 在算）；`pi 正在运行` 的通知是否还在。
* 发回来：这两条命令的输出。

**⑤ 发送大消息（附图/长文）时会不会 ANR**
* 点哪里：附一张 3–6 MB 的图，发出去，**立刻**连续点界面（滑动列表）。
* 看什么：界面是否有 3–5 s 无响应；`adb logcat | grep -i "Input dispatching timed out"`。
* 意义：验证 B2/B3 的触发条件在真机上是否可达（本次已把写与读都移出帧线程）。

**⑥ 跟随行为（只确认观感，不是为了定位）**
* 点哪里：发一条要求写长文的问题（≥2000 字），不碰屏幕 30 s；然后上滑一次，再点「回到最新」。
* 看什么：① 最后一行文字是否始终完整可见；② 上滑后**绝不**被拉回；③「回到最新 · N」的 N 与新增行数是否吻合。
* 意义：`TailFollow` 的 52 条检查本机**没有跑**，这条是它在真机上的对应验收。

**⑦ 扩展对话框 / 队列 chips 是否会"失联"**
* 点哪里：跑一个会发 `confirm`/`select` 的扩展（例如任一会在回合中提问的扩展），在流式很密的时候看。
* 看什么：对话框是否出现；`get_state` 的队列数字（chips）是否与 pi 侧一致。
* 意义：验证 B6（`_events` 满 256 后丢事件）在真机上是否真的发生。

---

## 8. 纯逻辑与 harness（只登记，**本机没有跑**）

| harness | 本次变化 | 证明什么 / 不能证明什么 |
|---|---|---|
| `sessions`（`tools/run-app-pure-checks.sh`） | 新增源文件 `session/SessionFileScan.kt`；`PiSessionStoreCheck.kt` 增加 6 条检查：CR 剥离、**预算处正好停止**、超长行被丢而下一行保留、`onLine` 返回 false 即停、EOF 无换行的尾行、以及**通过 `list()` 端到端**：单行 2 MiB 的会话仍能被列出（表头/首条用户消息/回落到 mtime） | 证明：扫描是有界的、超长行不会把整行读进内存、调用方的语义（截断 ⇒ 回落 mtime）没变。不能证明：Compose/Gradle 编译（本机无编译器插件），也不能证明真机上的帧时间 |
| `tail-follow` | 未改 | 见上，仅登记 |
| `utf8-stream`、`packages`、`guest-paths`、`shell-policy-mirror`、`mentions`、`agent-tool-paths`、`extension-error-text`、`models-inventory`、`settings-audit`、`pre-spawn`、`lifecycle-policy` | 未改 | 与本改动无关，但改 `run-app-pure-checks.sh` 后**必须全绿** |

本机跑过的**不需要编译**的自检：`python3 tools/check-nested-comments.py` → OK（176 个 Kotlin 文件）；
`bash -n tools/run-app-pure-checks.sh` → OK。**`tools/typecheck.sh` / `run-app-pure-checks.sh` / Gradle 一律没跑**（用户明令）。

---

## 9. 上 CI 最可能出错的点

1. **`PiEngineSession.kt` 的写者线程**（CI 是唯一编译器）：`Executors.newSingleThreadExecutor { … }` 是 Java `ThreadFactory`
   的 SAM 转换，lambda 的返回值必须是 `Thread`（这里用 `apply` 返回自身）；`send` 的 `runCatching { writeQueue.execute { … }; true }`
   类型是 `Result<Boolean>`。这两处如果写错，是**编译错**，会立刻红。
2. **`ChatScreen.kt` 的 `pickerScope`**：`rememberCoroutineScope()` 必须**无条件**、且在 `rememberLauncherForActivityResult`
   之前调用；`withContext(Dispatchers.IO)` 的回调里写 Compose 状态必须在主线程（`withContext` 返回后本来就在主线程）。
   本机**没有 Compose 编译器插件**，这些只能靠 CI 的 `:app:assembleRelease` 验证。
3. **`SessionFileScan` 的接受者类型**：`forEachLine(reader, budget, maxLineChars) { … }` 的尾 lambda 返回 `Boolean`；
   两个调用点里都有 `return@forEachLine true/false`，其中 `readSummary` **不能**再用非局部 `return null`（已改成 `notASession` 标志）。
   这类"lambda 里改写外层 local var"是 CI 上最容易漏看的一类，但它只在编译期可见。
4. **`headerScanBudget` 从 `Long` 改成 `Int`**：`consumed >= headerScanBudget`（`Long >= Int`）在 Kotlin 里合法，
   但 `SessionFileScan` 的签名要求 `Int`；如果将来有人把它改回 `Long`，会红在两处调用。
5. **`MAX_ATTACHMENT_BYTES` 从 `const val Long` 变成 `val Int`**：凡是拿它和 `Long` 比的地方都会红（本次已核，只有 `bytes.size` 一处）。
6. **`run-app-pure-checks.sh` 的 `sessions` 闭包**多了一个文件：如果 CI 上 `SessionFileScan.kt` 引用了 Android 类型，
   这条 harness 会**编译失败**（这正是不让它引用 Android 的原因）；`SessionFileScan` 只用 `java.io`。
7. **`check-nested-comments.py`**：本次新增的 KDoc 里出现过 `(cap - slack) / 4 * 3` 与 `/*` 形状的示例吗？已自检 → OK。

---

## 10. 未定 / 未做（诚实清单）

1. **A1 的前提未验证**：用户的会话里到底有没有 > 8 MiB 的单条记录（一张附图就够）。修完后即使有，
   界面也会立刻说明而不是等 120 s —— 但"是不是它"只有真机能定（§7-②）。
2. **B1 的触发源未验证**：本机没找到能证明 reducer 会抛出的输入；修的是"抛一次就永久卡死"这条放大链。
3. **`PiEngineHost` 仍持有死引擎**（C3）：影响面是 `host.session`/`host.live` 的读者与"服务该不该在"的副本，
   本次不敢动（改它要新增一个 `forget()` 入口并复核所有读者）。
4. **`_events` 丢事件**（B6）：正确修法是背压/分类，属设计改动。
5. **`list()` 的总 IO 无界**（A5）：属于"要不要缓存会话索引"的产品决定。
6. **`TailFollow` 的真机观感**（§7-⑥）与 **recomposition 范围**（`docs/streaming-review.md` §2.7/§4.3 的残留）未动。
7. **退出码没有落进状态**（C4）：诊断报告需要它，而那个文件的归属是另一个代理；本次只在文档里点名 `file:line`，
   没有改它的文件。
