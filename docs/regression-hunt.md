# 回归追查（regression-hunt）

> **本机未编译。** 本机是用户的手机，用户明令禁止在这台机器上跑 `tools/typecheck.sh`、
> `tools/run-app-pure-checks.sh`、Gradle。**CI 是唯一的编译器。** 本文所有"已修/成立"的结论，
> 证据级别只有两级：**读代码 + 读本机缓存里的库字节码**。凡是没做到的，写进 §5。
>
> 行号是**写作时**的行号，一律以符号名为准。
>
> ## 0. 取证装置：已全部手工撤回（用户否掉这条路）
>
> 上一轮按"先做取证装置"的要求写过三个新文件；用户明确否了（"尽量不要往软件里加东西了"），
> **已全部删除，工作树恢复原状**：
>
> | 撤掉了什么 | 说明 |
> |---|---|
> | `app/src/main/kotlin/app/pi/diagnostics/HangTrace.kt` | 删除（本轮新增文件） |
> | `app/src/main/kotlin/app/pi/diagnostics/HangForensics.kt` | 删除（本轮新增文件） |
> | `app/src/main/kotlin/app/pi/diagnostics/CrashRecorder.kt` | 删除（本轮新增文件） |
> | `app/src/main/kotlin/app/pi/diagnostics/` 目录 | 清空后删除 |
> | `app/src/main/kotlin/app/pi/PiApplication.kt` | 用 `git show HEAD:<file>` 的原文**整文件恢复**，`git status` 无残留 |
> | `tools/run-app-pure-checks.sh` | **从未改过**（只读过） |
> | `docs/anr-hunt.md` | **没有创建** |
>
> 没有落盘、没有看门狗线程、没有自动导出、没有把任何取证接进诊断报告；代码里零残留。
>
> ## 0.1 工作树是共享的
>
> 追查期间我两次 `git status` 之间，`app/src/main/kotlin/app/pi/engine/PiEngineSession.kt`
> 从"干净"变成"已修改"（另一代理在改 `drainQueue`/`stopAndDrainQueue`）。此外树里还有
> 未跟踪的在制品：`session/SessionExportNaming.kt`、`session/SessionImport.kt`、
> `ui/chat/QueueRestore.kt`、`docs/capability-fill.md` —— **不是我的改动，我没有碰**。
> 也正因为共享，我**没有改动任何其它代理正在编辑的文件**（见 §4 的处置列与 §2 的结论）。

---

## 1. 结论速览

| # | 结论 | 判定 |
|---|---|---|
| 1 | `4e2f845`（父代理的修复）**本身是安全的减法**：`repeated` 只比 pin 之后，跟随状态机**不可能**再重发同一个 pin，"每帧重发同一个 pin"这一形态被彻底关掉 | **修复：采纳** |
| 2 | 但它提交信息里的**机理叙述有两处不成立**：①返回固定 `TailPin(tail, 0)` 的分支**不是**"最后一行比视口高"，而是"最后一行**完全不可见**"；②"列表满足不了这个请求"这个前提，对 `TailPin(tail, 0)` 来说**恰好只在最后一行比视口矮时成立**，而那种情况下夹住后最后一行**必然可见**，pin 随即变 `null` ⇒ 收敛，不成环。所以**这条不足以解释症状**，不能据此收工 | **不足** |
| 3 | "点进历史会话就卡死" 目前**唯一能证明**的机制在**重放链**上（不是跟随）：`get_entries` 把整条会话放在**一个**记录里 → 8 MiB 上限 → 失败被 `replayHistory` **静默吞掉**（`d8ac56a` 时是等满 120 s，`2f3d09b` 之后是立刻失败但同样被吞）⇒ 界面停在 `busy` 且永不给出任何反馈。而这条路**第一次变成可达**正是回归窗口里的 `PiSessionStore.list()` 改动（它第一次把引擎自己写的**平铺** `.jsonl` 读进列表） | **可证 / 改动单见 §4.1** |
| 3b | "**发第二条消息就卡**"：专项逐条查完（`closeAfterSettling` 全部调用点、`reportWork`、`_events` 丢弃、`TailFollow` 初始态、`retainState`、`lastExitCode`、每 token 的 O(n)）**没有找到可证机制**，详见 §4.2 | **未证实（诚实）** |
| 4 | `maybeResumeLastSession`（回归窗口新增）在**每次 attach 之后**都可能 `switch_session` + 重放整条会话，走的是**同一条**重放链 ⇒ 冷启动后"第一条消息就卡"也有解释 | **未改（零改动真机判别法见 §5）** |
| 5 | `PiMarkdown` 的 `retainState = true`：**用户实测把它整段回退到 `98cc21a` 仍然完全卡死 ⇒ markdown 那条路不是病因**（父代理已撤销回退 `bead9cc`）。我另外用本机缓存字节码核了 API 存在 | **已排除** |
| 6 | 我把"自己写自己观察的状态"逐条扫了一遍（§3）：除跟随的 pin 之外，**没有第二条会每帧自我维持的路径**；`while (true)` 共 16 处，逐条看都是有阻塞/挂起点的正常循环 | **清** |
| 7 | **新硬事实**：没有 busy 提示 ⇒ 症状是**主线程被占死**（不是引擎在等）；点历史会话与发消息都卡 ⇒ 共同点是"把已有转录渲染出来"。因此**打开历史会话时数据已静止，占死主线程的必须是自驱动的**。在 ChatBody 里逐个排查后，**唯一"不需要外部输入就能再进入"的只有跟随那条 `snapshotFlow → requestScrollToItem` 效果**（§4.3.1） | **方向确认：只有跟随** |
| 8 | 但改换机理逐条过完（每帧变化的 key／同步 `forceRemeasure` 形成的再入链／`isScrollInProgress` 交互／`poke` 递增点），**每一条都收敛**；`pinToTail()` 在所有"已停在末尾"的几何下都返回 `null`，`4e2f845` 又不重发同一 pin ⇒ **我写不出"每帧自维持"的路径**。把收敛论证钉成了 **harness G14/G15**（`TailFollowCheck.kt`，只加断言）：G15 若在 CI 红，就说明收敛是错的、环路是真的 | **不足以解释（诚实）／已把可反驳性交给 CI** |
| 9 | **已落的修正（§4.4）**：按父代理的方向，把跟随的**触发源**从"读 `layoutInfo` 的 long-lived `snapshotFlow`"换成"**数据驱动的 `LaunchedEffect`**（键 = `revision`/`streaming`/`renderedItems.size`/`following`/`tailPoke`/`sessionKey`/`bottomInset`）+ `withFrameNanos` 等一帧后读**一次**布局"；"用户一滚动就停"改成只观察 `isScrollInProgress` 的上升沿；`unseenRows` 改成 `pausedRows` 与 `transcriptRows` 相减得到 | **已改（只动跟随那一段）** |

---

## 2. 对 `4e2f845` 的独立验证

### 2.1 修复本身：采纳

```kotlin
// app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt:236
val repeated = pin != null && pin == lastPin
val issued = if (repeated) null else pin
...
lastPin = pin            // :241
```

- `repeated` 现在**与几何无关**：只要 `pinToTail()` 给出与上一次**相等**的 pin，就不会再下发。
- `ChatScreen.kt:528-530` 里，`requestScrollToItem` 是这条链上**唯一**的副作用（唯一"安排一次
  remeasure"的动作）。没有新的 `requestScrollToItem`，就没有新的 `LazyListLayoutInfo`，
  `snapshotFlow` 也就没有新东西可发。**"同一个 pin 的自我维持循环"被从根上关掉**，与前提真假无关。
- `reArm()` 里 `lastPin = null`（`:129`）是这条减法**必须的配套**：否则用户点「回到最新」时，
  记忆里的 pin 会把它压住 —— 这属于"去掉自触发"，不是新机制。
- 结论：**方向正确、只动一处判断、没有引入任何机制**。父代理的这条修复我独立核对后**同意采纳**。

### 2.2 但提交信息里的两处机理不成立（这是本文最重要的一条）

**(a) `TailPin(tail, 0)` 的分支说反了。**

```kotlin
// TailFollow.kt 的 TailViewport.pinToTail()
if (lastVisibleIndex < tail) return TailPin(index = tail, offsetPx = 0)   // ← 固定值 pin
if (lastVisibleIndex > tail) return null
val hidden = lastVisibleOffsetPx + lastVisibleSizePx - viewportEndOffsetPx
if (hidden <= 0) return null
return TailPin(index = firstVisibleIndex, offsetPx = firstVisibleOffsetPx + hidden)
```

固定值 `TailPin(tail, 0)` 的条件是 **`lastVisibleIndex < tail`：最后一行根本没被算进可视项**
（在折线以下、尚未出现）。而"**最后一行比视口高**且已经可见"落在**第三个**分支，那时的 pin 是
`TailPin(firstVisibleIndex, firstVisibleOffsetPx + hidden)` —— **依赖 offset，不是固定值**。
提交信息把这两种情况合成了一句话，前提本身就错了。

**(b) "这个请求列表满足不了"推不出每帧循环。** 对**最后一项** `i = tail` 来说，
`requestScrollToItem(tail, 0)` 要求把最后一行的**顶**放到视口顶，即滚到
`s = contentHeight - lastRowHeight`；而可达的最大滚动量是 `maxScroll = contentHeight - viewportHeight`。于是：

- **最后一行比视口矮**（`lastRowHeight < viewportHeight`）⇒ `s > maxScroll` ⇒ **不可满足，被夹住**：
  停在 `maxScroll`。而在 `maxScroll` 处，最后一行的**底**正好贴视口底 ⇒ 下一帧
  `lastVisibleIndex == tail`、`hidden = lastVisibleOffset + size - viewportEndOffset ≤ 0`
  ⇒ `pinToTail()` 返回 **null** ⇒ **收敛，不成环**。（注意：这正是提交信息说的"满足不了"的情形，
  但它的前提是"最后一行**矮**"，与提交信息写的"高"相反。）
- **最后一行比视口高** ⇒ 请求**可满足**：最后一行的顶落在视口顶，`hidden = size - viewportHeight > 0`
  ⇒ 下一帧的 pin 变成 `TailPin(tail, 0 + hidden)` —— **与上一帧的 pin 不相等**，属于"几何真的动了"
  ⇒ 下发一次 ⇒ 再下一帧 `hidden → 0` ⇒ `null`。**两步收敛。**
- 补一个前提：`snapshotFlow` 对**相等**的值不会重复发射（Compose 的常见实现是保存 `lastValue`
  比较后再 emit；本机**没有 Compose 的产物**可核，见 §6-3，这一条我只按已知语义用）。
  **但结论不依赖它**，两种情况都推不出循环：
  - 若它**会**去重：一模一样的几何不会回到收集器 ⇒ 要成环，每帧几何都必须变 ⇒ 落到上面两支，
    而两支都收敛（矮行 → 夹住后 `null`；高行 → pin 变化后在两步内 `null`）。
  - 若它**不**去重（每次 remeasure 都回灌）：那么**旧条件反而是拦得住的那个** ——
    同一 pin + 同一几何 ⇒ `repeated = true` ⇒ 不下发 ⇒ 循环停。此时"旧条件放行"同样要求
    **每帧几何都变**，与上一支同一个前提。
  也就是说：**无论 `snapshotFlow` 是否去重，"每帧不同几何 + 同一个不可能被满足的 pin"都是那条机理
  唯一可能的立足点，而它对 `TailPin(tail, 0)` 的两种情形都收敛。** 我找不到这条现实路径。

**判定：这条机理不成立，不足以解释"卡住不动/没有响应"。** 修复保留（它是安全的减法，且把
"同一 pin 重复下发"这一形态永久关掉），但**不能据此认为症状已修**，追查必须继续（§4）。

唯一不能排除的残余形态：几何在**两个不同值之间来回**（pin 交替 ⇒ 新条件放行）。这需要
"下发 pin → 位置落到 A；再下发 → 位置落回 B"这种非确定的夹取行为，我没有证据，也没有在本机复现的手段。
列进 §5 的真机验证项。

### 2.3 新增 harness（G9/G10/G11）的期望值核对

我逐条对着实现算了一遍（fixture 见 `TailFollowCheck.kt:43-63`：`viewport(6, atBottom = false,
lastVisibleIndex = 2)` ⇒ `totalItems = 6` ⇒ `tail = 5`；`lastVisibleIndex = 2 < 5`）：

| 断言 | 期望 | 与实现一致？ |
|---|---|---|
| G9 `a tail above the viewport pins to its index` | `TailPin(5, 0)` | ✅ 走第一个分支（`lastVisibleIndex 2 < tail 5`） |
| G10 `the same unsatisfiable pin is not re-issued` | `null` | ✅ 第二次快照 pin 仍是 `(5,0)`，`pin == lastPin` ⇒ `repeated` ⇒ `issued = null` |
| G11 `an explicit re-arm pins again` | `TailPin(5, 0)` | ✅ `reArm()` 清了 `lastPin` |

三条期望值**站得住**，不需要改。唯一要写清的是它们钉的是**去重不变量**，**不是**"列表满足不了"那个前提
（fixture 不模拟夹取），所以 G9/G10 **不能**当成"§2.2(b) 已被证实"的证据。另外我核了
`TailFollowCheck.kt` 与其它 harness 里**没有任何地方引用被删掉的 `lastPinViewport`**（`grep` 0 命中），
所以 CI 不会因为删字段而编译不过。

---

## 3. 同类扫描：所有"自己写自己观察的状态"

判据：**在这个原语里写回它自己读取的那个状态，或安排一次布局/滚动/重组，从而再次触发自己。**

| 位置（符号） | 形态 | 会不会每帧自我维持 | 处置 |
|---|---|---|---|
| `ChatScreen.kt:497-532`（`LaunchedEffect(listState)` + `snapshotFlow{…layoutInfo…}` + `requestScrollToItem`） | 观察 `layoutInfo`，动作会安排 remeasure | **同一 pin：不会**（`4e2f845` 后不可能重发）；**pin 交替：原理上未排除**（§2.2 残余） | 已由父代理改；不再动 |
| `ChatScreen.kt:539-550`（`atTop` derivedStateOf + `LaunchedEffect(atTop, hiddenCount)` 写 `earlierArmed`/`renderWindow`） | 写的是自己的 key（`hiddenCount`）上游 | **不会**：`earlierArmed` 在增长**之前**就清成 `false`，且 `hiddenCount` 随 `renderWindow` 单调减到 0；一次性 | 不改 |
| `ChatScreen.kt:563-576`（`pendingJump` effect） | 读 `pendingJump` 并把它写回 `null` | **不会**：自清空、一次性（目标不在范围内时不动作，也不自旋） | 不改 |
| `ChatScreen.kt:584-589`（`fill?.seq` effect → `consumeComposerFill`） | 读过的东西由副作用置空 | **不会**：置空后 key 变化，effect 再跑一次即 `return` | 不改 |
| `ChatScreen.kt:551-562`（搜索跳转 effect） | 读 `searchMatches`/`searchCursor`，写 `following`/`renderWindow`/`pendingJump` | **不会**：写的都不是自己的 key | 不改 |
| `ChatScreen.kt:1697`、`ImageGridBlock.kt:113`（`produceState(image.base64)`） | 只写 `value` | **不会**：`value` 不是 producer 的 key，不重启 producer | 不改 |
| `PiMarkdownComponents.kt:402-429`（高亮 `produceState(code, language, highlighter)` + `hasStreamed` 标记 + `delay(200)`） | 只写 `value`；`remember(code, spans.value, palette)` 重建文本 | **不会**：key 只在内容变化时变；`delay` 在新内容到达时**被取消**，这正是"不每 token 请求一次高亮"的机制 | 不改 |
| `PiFileWatch.kt:51-99`（`FileObserver` → `onChanged` → `reloadTick++` / `filesEpoch++`） | 文件事件 → 读文件 → 可能再写文件 | **不会**：两个消费者的重读路径都**只读**（`PiCredentialService.inventory` 全是 `readTextOrNull`；`PiConfigFiles.effectiveFile` 只选文件不写）；唯一会写的是 `writeMirror`，只在**写凭证**时发生（一次写 → 一次重读 → 停）。`MASK` 已排除 `OPEN`/`ACCESS`/`CLOSE_NOWRITE`，读自己不触发；atime 更新在 inotify 里是 `IN_ACCESS`，同样被排除 | 不改 |
| `PiScreenVisibility.kt`（`DisposableEffect` + `LifecycleEventObserver`） | 只在生命周期事件上写 `visible` | **不会**（事件驱动，不是自触发） | 不改 |
| `DeviceCapabilityScreen.kt:200`（`while (true)` + `delay(1500)`） | 可见性驱动的轮询 | **不会**（每条分支都 `delay`；`visible=false` 时循环退出） | 不改 |
| `PiSessionViewModel.kt:1722`（对话框倒计时 `while (true)`） | `delay` 驱动，超时 `break` | **不会** | 不改 |
| `ChatScreen.kt:493` 的 `reArmTail()`（调用点 `:1018` FAB、`:1220/:1228/:1238` 发送） | 写 `tailPoke`，而 `tailPoke` 是 snapshotFlow 的输入 | **不会**：只在**用户动作**上 +1（每次发送/点 FAB 一次），不是每帧 | 不改 |

**16 处 `while (true)` 逐条看过**（`PtySession`、`TarExtractor`、`RuntimeProvisioner`、`PiEngineSession.readLoop`、
`DeviceShell`、`DeviceUiAutomation`、`DeviceShizuku`、`PiNodeCodeHighlighter`、`PiHighlightClient`、
`GuestCommand`、`TrustFile`、`TrustRepository`×2、`PiConfigFiles`、`DeviceCapabilityScreen`、
`PiSessionViewModel`）：**没有一处是不带阻塞点/挂起点的自旋**（`readLoop` 的 `input.read`、
`PtySession` 的读都在循环头）。

**结论：扫完之后，"每帧自我维持"只剩跟随的 pin 这一条，而它已被 `4e2f845` 从"同一 pin"这一侧关掉。**

---

## 4. 回归候选表（窗口 `98cc21a → d8ac56a`，按可能性排序）

> 区分口径：**窗口内** = 引入于 `98cc21a..d8ac56a`（第一个坏版本）；
> **后来叠加** = `d8ac56a` 之后别的批次又改过（不算回归，但会影响症状能否被观察到）。

| # | 候选 | 提交 | 为什么**只在"第二条消息"/"打开历史会话"时**发作 | 证据（`file:line` / 符号） | 处置 |
|---|---|---|---|---|---|
| C1 | **会话列表第一次包含"引擎自己写的会话"**：`list()` 原来只遍历**子目录**（分组布局），平铺 `.jsonl` 被 `if (!group.isDirectory) return@forEach` 跳过；而本 App 的引擎正是用 `--session-dir` 写**平铺**布局 | 窗口内（`d8ac56a`） | 以前列表里**没有**引擎的会话（点不到），`switch_session + get_entries` 这条路实际上不可达；改动之后它**第一次可达**。所以"点会话进去卡死"这个症状与第一个坏版本同时出现 | `session/PiSessionStore.kt` 的 `list()`（新增 `isSessionFile(entry) -> readSummary(entry, null)` 分支，与原 `if (!group.isDirectory) return@forEach` 的差集）；`PiEngineHost` 传 `--session-dir` | **不改**（这是修 bug 的正确行为）。写在这里是为了说明"为什么是 `d8ac56a` 之后才发作" |
| C2 | **重放链：一条记录装整条会话 + 8 MiB 上限 + 失败被静默吞掉** ⇒ 界面停在 `busy` 且**零反馈**（用户口中的"卡死/没有响应"），且**内容越多越容易**（会话越大，单条记录越大）。`d8ac56a` 时表现为"等满 **120 s** 超时"，`2f3d09b` 之后表现为"立刻失败但同样被吞" | 窗口内重新可达（机制本身早于窗口） | 打开历史会话 = `switchSession` → `switch_session` → `get_entries`（**整条会话在一个 JSONL 记录里**）→ 若超过 framer 的 8 MiB 就被丢弃 → 那条 `response` 要么等满 **120 s**、要么被 `onRecordDropped()` 立刻失败 → `replayHistory` 的 `runCatching{…}.getOrNull() ?: return` 与 `if (!response.success) return` **静默返回** ⇒ 画面不动、`busy` 挂着。第二条消息之所以也可能撞上：同一进程里后续每次 `get_entries`（attach/切会话/resumeLast）都走这条 | `engine/PiEngineSession.kt` 的 `request(command, timeoutMs = 120_000)`、`onRecordDropped()`；`JsonlFramer` 的 `DEFAULT_MAX_RECORD_CHARS = 8 MiB`（`rpc/.../Jsonl.kt`）；`ui/PiSessionViewModel.kt:1870-1892` 的 `replayHistory`（两处静默 `return`） | **未改**：**精确改动单见 §4.1**（交给正在改该文件的代理；我不动它） |
| C3 | **`maybeResumeLastSession` 每次 attach 都可能自动切会话并重放** | 窗口内（新增 `PiSessionStore.mostRecentForResume` + `resumeAttempted`） | 它和手动点会话走**同一条重放链**（C2），所以"冷启动后第一条就卡"也能由它产生；`resumeAttempted` 只拦**同一个 ViewModel 内**的重复，重启/换引擎后又是一次 | `ui/PiSessionViewModel.kt` 的 `maybeResumeLastSession()`（`attach` 末尾调用）、`PiSessionStore.mostRecentForResume(cwd)` | **不改**（行为受 `app.sessions.resumeLast` 开关控制）。这条给出一个**零改动的真机判别法**（§5） |
| C4 | **`onCleared` 用最长 30 s 去 settle 引擎，而 `PROCESS_LOCK` 是进程级的** | 窗口内 `closeAfterSettling`；**进程锁与 onCleared 的最终形态在 `db291dc`（后来叠加）** | ViewModel 被清理时（Activity 结束）`teardownScope.launch { engine.closeAfterSettling() }`，最多等 30 s；下一个 ViewModel 的 `boot()` → `host.boot()` 要**等同一把 `Mutex`** ⇒ 用户重开 App 看到长时间"启动中"。**不解释**"第二条消息卡死"，但解释"重开之后仍不正常" | `engine/PiEngineSession.kt` 的 `closeAfterSettling(timeoutMs = SETTLE_TIMEOUT_MS = 30_000)`；`ui/PiSessionViewModel.kt` 的 `onCleared()` / `teardownScope`；`engine/PiEngineHost.kt` 的 `PROCESS_LOCK` | **不改**（改动跨两个代理的在制品文件，风险大于收益） |
| C5 | 跟随滚动的 pin 去重（父代理已修） | 窗口内（`TailFollow` 新增 + `ChatScreen` 跟随重构） | 只在**已有内容**、尾行高度与视口不匹配时才有 pin 可发 ⇒ 天然是"第二条才开始"的形状。**但机理不足以解释症状**（§2.2） | `ui/chat/TailFollow.kt`、`ui/screens/ChatScreen.kt:497-532` | **已改（`4e2f845`）**，独立核对后采纳 |
| C6 | `PiMarkdown` 的 `retainState = true` / 去掉 `animateContentSize` | 窗口内 | `retainState=true` 会让渲染器**保留旧内容**直到新解析完成 —— 天然是"第二条/内容变化时才起作用"的形状。我用本机缓存字节码核了 API 存在（`build/typecheck/extra/aar/…/classes.jar`：`MarkdownStateImpl.updateInput$com_…`、`rememberMarkdownState(String, boolean, boolean, …)`），但**读不出**任何成环证据 | `ui/render/PiMarkdown.kt`（`retainState = true` / `markdownAnimations(animateTextSize = { this })`） | **不改**（未证实；且这两个参数正是 `docs/streaming-review.md` §2.3/§2.4 的结论） |
| C7 | 每 token 的 O(n) 主线程工作（重放/流式链） | 窗口内已有、非新增 | "内容越多越容易"确实指向 O(n)，但量级是**微秒级**：`applyChanged` 每次发布 `current.toMutableList()`（n=2000 ⇒ 约 16 KB 引用拷贝）+ `syncTranscript` 的 `rows.any{…Pending}` 扫描 + `ChatBody` 的 `visibleItems.filterNot`（O(n)）。30 token/s 下 < 1% 帧预算 | `ui/PiSessionViewModel.kt` 的 `syncTranscript`/`applyChanged`；`ui/screens/ChatScreen.kt` 的 `visibleItems`/`renderedItems` | **不改**（不是病根；要动就是架构改动） |

### 4.1 改动单（交给正在改 `PiSessionViewModel.kt` 的代理执行，**我不动那个文件**）

**位置**：`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` 的 `replayHistory(engine)`，
当前是 `:1870-1892`。原文与前后 3 行上下文（快照时刻）：

```
1869:     */
1870:     private suspend fun replayHistory(engine: PiEngineSession) {
1871:         val response = runCatching {
1872:             engine.request({ PiCommands.getEntries(it) })
1873:         }.getOrNull() ?: return                       // ← 静默 return ①
1874:         if (!response.success) {
1875:             // A brand-new in-memory session answers with an empty page, not an
1876:             // error; a real error means we cannot rebuild, and saying nothing is
1877:             // better than clearing a transcript we cannot repopulate.
1878:             return                                      // ← 静默 return ②
1879:         }
1880:         // `seedHistory`, not `transcript.seedFromHistory`: …
1887:         engine.seedHistory(PiResponses.entries(response))
```

**改成**（只用仓库里**已有**的通道 `fail(...)`，`:1825-1828`；它同时写 `lastError`（AppBar 的错误行）
并 push 一条 `Notice.Tone.Error`，与 `call()` 的失败路径完全一致，`call` 在 `:1847` 就是这么用的）：

```kotlin
private suspend fun replayHistory(engine: PiEngineSession) {
    val response = runCatching {
        engine.request({ PiCommands.getEntries(it) })
    }.getOrElse { error ->
        fail(
            "读取会话内容失败：" +
                (error.message?.takeIf { it.isNotBlank() } ?: "没有更多信息") +
                "。可以重新打开这个会话再试一次。",
        )
        return
    }
    if (!response.success) {
        // pi 自己给的原因原样带出来：framer 丢掉超大记录时，
        // `PiEngineSession` 已经用一句用户能读的中文失败所有在途请求，
        // 那句话正好是这里最该显示的东西。
        fail(
            response.error?.takeIf { it.isNotBlank() }
                ?: "引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。",
        )
        return
    }
    engine.seedHistory(PiResponses.entries(response))
    syncTranscript(engine, engine.publication.value)
}
```

**用户可见文案（不出现路径/类名/`§`）**：两句，都带下一步动作 ——
"读取会话内容失败：…。可以重新打开这个会话再试一次。" /
"引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。"
（超大记录那条由引擎层已有的文案接上："引擎发来的一条内容太大，没能读取，这次操作没有完成。请重试。"）

**为什么这两行是全部的改动**：当前树里 `PiEngineSession.onRecordDropped()`（`2f3d09b` 之后）会把
超过 8 MiB 的记录变成"立刻失败所有在途请求" ⇒ `response.success == false` 且 `response.error`
就是那句中文 ⇒ 眼下它**正好被 `:1874-1879` 吃掉**。在 `d8ac56a` 时还没有这个 fail-fast，表现是
"等满 120 s 再被吃掉"。两种情况都是**同一个静默 return** 的结果。

**对 `maybeResumeLastSession` 的影响**：同链（它 → `switchSession` → `afterSessionReplaced` → `replayHistory`）。
改后自动恢复失败会给出明确一句话，而不是静默地停在空转录上。**"没有历史会话就静默开始新会话"
的语义不受影响** —— 那走 `recent == null` 分支，根本到不了 `replayHistory`。

**副作用评估**：
- 某些会话从"静默空白/停住"变成"明确报错" ⇒ **改进**，不是回退（用户至少知道下一步；今天他只会说"卡死"）。
- `fail()` 会进 `recentFailures()` ⇒ 诊断报告从此能带上这句话，对下一轮取证是净收益。
- **不改变**任何重放/清空/超时行为：`seedHistory` 只在成功分支调用，原转录在失败时仍然保留
  （原注释担心的"清空一个填不回来的转录"依然成立）；`busy` 仍由 `call()`/`attach` 的 `finally` 清。
- 不要顺手把 120 s 超时也改了 —— 那是**另一个**决定，跟这处减法无关。

### 4.2 「发第二条消息就卡」专项追查：**本轮没有找到可证机制**

按"只在已经有内容/已经跑过一回合时才做"逐条查过，全部**排除**：

| 只在之后才发生的事 | 会不会在第二条发作 | 依据 |
|---|---|---|
| `closeAfterSettling()` | **正常回合走不到** | 全部调用点只有两处：`PiEngineHost.kt:430`（`restart(...)` 内，只有设置页/凭证页发起重启时）与 `PiEngineHost.kt:462`（`shutdown()` 内）。`shutdown()` 的调用者是 `stopEngineHook`（`PiSessionViewModel.kt:432`，注册给 `PiEngineController`，只有通知的「停止」和 `PiEngineService.stopEngineAndSelf()` 会触发）与 `onCleared` 的 `teardownScope`（`:3079`）。而 `stopEngineAndSelf()` 只由通知 `ACTION_STOP` 或 `PiEngineService.stopIfRunning()` 触发，后者只被 `syncEngineService`（`:769`）调用，它的三个调用点（`:948` boot 失败、`:1009` restart 的 else、`:1121` 死亡分支）**全部在引擎已经死了/启动失败之后**。⇒ **父代理怀疑的"服务把引擎杀掉"在正常回合里不可达** |
| 前台服务 / 唤醒锁 `reportWork` | 不会 | `reportWakeLockNeed()` 被 `reportedWork` 变更门控（`PiSessionViewModel.kt:807-809`），一回合只翻几次；`applyWork` 只 acquire/release + 更新通知计数，不碰引擎 |
| `_events` 的 256 缓冲 + `tryEmit` 静默丢弃（`PiEngineSession.kt:178`、`:558`） | **只有装了会弹 UI 的扩展才可能"永久卡"** | 逐个看 ViewModel 的 `onEvent`：有后果的**只有** `ExtensionUiRequest`（丢了 = pi 在等一个答复；`editor` 连 agent 侧超时都没有，见 `cancelAllDialogs` 的 KDoc），其余（`QueueUpdate`/`ExtensionError`/`ThinkingLevelChanged`/`SessionInfoChanged`/`AgentSettled` 的 refresh/`BashExecutionUpdate`）只是显示陈旧。"内容越多越容易丢"成立（publication 更贵 ⇒ collector 更慢 ⇒ 更容易积压超 256），所以它**同时吻合"第二条/内容多"和"偶发"**；但它**不是本窗口引入的**，且我无法证明用户环境里有这种扩展 ⇒ **未证实** |
| `TailFollow` 的初始态 / `previousRows` | 不会 | 第二条时 `previousRows ≥ 0` 只影响 `unseenRows` 与"转录被重建"判定；`reArmTail()` 只在发送/FAB 调用 |
| `retainState = true` 的 markdown 状态 | 不构成"第二条特有" | 每一行是**自己的** `MarkdownState`（`remember`）；第二条的助手行是新的 composable 实例，旧行保持 final 内容 |
| `PiEngineSession.lastExitCode` | 不会 | 只在 `waitJob` 写、只在死亡分支读 |
| `syncTranscript`/`publication`、`renderWindow`/`earlierArmed` | 不会 | 量级微秒级（§4-C7）；`earlierArmed` 那条是一次性的（§3） |

**结论（诚实）**：我**没有**找到"第二条消息"特有的可证回归。剩下的候选都只能由真机区分：
pin 交替（§2.2 残余）、真机内存压力（引擎进程树 + 转录增长）、以及上面那条"扩展 UI 请求被丢"。
判别只需看一件事：**卡住时界面是否停在 `busy` 文案上**（有 ⇒ 引擎侧在等/重放链；没有 ⇒ 帧线程被占死）。

---

## 4.3 用户新硬事实之后：只剩"跟随那套重写"这一方向，**仍然没有找到能写出来的机制**

**新事实（用户实测）**：①把 `PiMarkdown.kt` 回退到 `98cc21a` 仍然完全卡死 ⇒ markdown 那条路
（入口切换 / `retainState` / `animations`）**不是病因**；②**界面没有任何 busy 提示** ⇒ 不是"引擎在等"；
③**点历史会话**与**发消息**两条不同入口一样卡 ⇒ 共同点只有"**把已有转录渲染出来**"；④与内容量正相关。

### 4.3.1 由新事实能*推出来*的一条硬结论（这条是新的，也给父代理的方向提供了支持）

**打开一个历史会话时，卡死发生在数据已经静止之后。** 重放只产生**一次** publication（`replaced = true`），
之后 `refreshState`/`refreshCommands`/`refreshSessions` 都是异步的一次性调用，**没有任何东西再改
`UiState.transcript`**。也就是说那一刻**没有任何数据流在推动重组**。所以占死主线程的东西**必须是自驱动的**
（composition / 布局 / 挂起效果自己再触发自己），**不可能**是"每 token 的工作"，也**不可能**是"引擎在等"
——这与用户"没有 busy 提示"的观察互相印证。

这把候选面收窄到 ChatBody 里那些"不需要外部输入就能再进入"的点：

| 自驱动候选（ChatBody 内） | 不靠外部输入能否再进入 | 判定 |
|---|---|---|
| `LaunchedEffect(listState)` + `snapshotFlow{…layoutInfo…}` + `requestScrollToItem`（`ChatScreen.kt:500-535`） | **能**：布局写入本身就能再触发这份快照 | **唯一合格者**，见 4.3.2 |
| `LaunchedEffect(atTop, hiddenCount)`（`earlierArmed`/`renderWindow`，`:546-553`） | 需要 `earlierArmed == true`，而它只在 `!atTop` 时被置位；且增长让 `hiddenCount` 单调减到 0 | 一次性，不足以解释 |
| `LaunchedEffect(pendingJump, …)`（`:566-579`） | 写回 `pendingJump = null`，自清空 | 不足以解释 |
| `LaunchedEffect(searchMatches, searchCursor)`（`:554-565`） | 只在有搜索词时进入；打开历史会话时没有搜索词 | 不足以解释 |
| `LaunchedEffect(fill?.seq)`（`:587-592`） | 只在扩展 `set_editor_text` 时进入 | 不足以解释 |
| `produceState`（图片 `ChatScreen.kt:1700`／高亮 `PiMarkdownComponents.kt:411`） | 只在 key（`image.base64`/`code`）变化时重跑 | 不足以解释 |

### 4.3.2 跟随那套：改换机理后**仍然不足以解释**（逐条）

- **`TailSnapshot` 的 key 里有没有每帧都变的字段？** 没有。`transcriptRows` 来自
  `rememberUpdatedState(state.transcript.size)`（写等值不失效，且历史会话打开后 size 不再变）、
  `poke` 只在发送/点 FAB 时 +1、`viewport` 的每个字段都来自 `LazyListLayoutInfo`；
  `renderedItems`/`renderWindow`/`headerRows` 只在转录或窗口变化时改变 `totalItems`，**不是每帧**。
- **`requestScrollToItem` 在 collect 里形成"改状态→重新发射→再改"的同步链？** 这条**有物理基础**：
  `LazyListState.requestScrollToItem` 内部会**同步** `forceRemeasure()`，那次 measure 会写 `layoutInfo`
  ——也就是写回这条 `snapshotFlow` 观察的东西。但链条要**无限**，必须**每次都有一个非空且不同的 pin**，
  而 `pinToTail()` 在每一种"已经停在末尾"的几何下都返回 `null`：
  `hidden = lastVisibleOffset + lastVisibleSize - viewportEndOffset`，在最大滚动位置上等于
  `-(afterContentPadding)`（本页 `contentPadding` 是**纵向对称**的 `PaddingValues(vertical = PiSpacing.screen)`,
  `ChatScreen.kt:899` ⇒ ≤ 0）；尾行比视口高时请求可满足、pin 变成 `(tail, hidden)` 后**一次**就 `hidden == 0`。
  再加上 `4e2f845` 后 `repeated` 只比 pin、同一 pin 不再重发 ⇒ **我看不到"每帧都不收敛"的路径**。
- **`isScrollInProgress` 与 `requestScrollToItem` 的交互**：它不开启滚动会话（`TailFollow.kt` 的 KDoc 有依据），
  所以不会把 `gesture`（`isScrollInProgress && anchor != previous`）变成"用户的手"；它可能在一次 fling 中
  被调用而结束那次 fling，但那是**用户输入驱动**的，不是自维持。
- **`poke` 由谁递增**：只有 `reArmTail()`（`ChatScreen.kt:496`），调用点 `:1021`（FAB 点击）、
  `:1223/:1231/:1241`（发送路径）——全是**用户动作**，不是每帧。

**因此：把"点历史会话也卡"归给一条我能写出来的跟随路径——做不到。** 明确记下：
**跟随重写是唯一"能自驱动"的代码（4.3.1），但我无法证明它*怎么*自驱动；这一轮的结论是
"不足以解释"，不是"已解释"。**

### 4.3.3 本轮唯一落地的东西：把收敛论证钉进 harness（只加断言，不改行为）

在 `app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt` 的 G 组末尾加了 **G14/G15** 两条纯断言
（**不触碰 `TailFollow.kt`**，不改任何行为、不加任何机制）：

- **G14**：尾行比视口高且可见（`firstVisibleIndex = lastVisibleIndex = 5`，`lastVisibleOffsetPx = 0`，
  `lastVisibleSizePx = 2000`，`viewportEndOffsetPx = 1000`，`atBottom = false`）
  ⇒ 期望 `TailPin(5, 1000)`（`hidden = 0 + 2000 - 1000`）。
- **G15**：把这次 pin 应用后**必然**得到的几何喂回去（`lastVisibleOffsetPx = -1000`，同一 size）
  ⇒ 期望 **`null`**（`hidden = -1000 + 2000 - 1000 = 0`）。

也就是说：**"这一次 pin 之后还需要再 pin"在 CI 里成了可反驳的命题**。如果哪天 G15 红了，
就说明收敛论证错了、环路是真的——这正是需要尽早拿到的信号。两条期望值我按 `pinToTail()` 的第三个
分支逐项手算校对过两遍（参数名取自 `TailFollowCheck.kt:43-63` 的 `viewport(...)` 助手，
变量名用 `tallAsked`/`tallSettled` 以避免与 A 组的 `tall` 冲突；标签用 G14/G15 以避免与已有的
G12/G13 重名）。

---

## 4.4 已落的修正：把跟随的触发源从"读布局"换成"由数据驱动"

**动机（父代理给的，我核对后同意）**：用户又给了两条硬事实 —— **内容很少（一问一答两条）也会卡**
⇒"渲染成本随内容增长"这种解释很弱；**第一条偶尔也会卡**（转录还没有内容）⇒ 触发条件不需要已有内容，
只需要"在跟随处于活动状态时发生一次布局/流更新"。合起来，最符合的仍然是**跟随那条效果的触发源本身**：
它**读布局**，而 `requestScrollToItem` **会安排一次布局**。

**改动位置**：`app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt`，**只动跟随那一段**
（`ChatBody` 内，当前约 `:442-573`）。同文件里另一个代理正在加的 P2-1 工具渲染块**我一个字没碰**。

**改了什么**

1. **删掉**了那条 long-lived `snapshotFlow { listState.layoutInfo … }.collect { … requestScrollToItem(…) }`
   —— 即唯一"自己写自己观察的状态"的地方（读 `layoutInfo`，而请求会写 `layoutInfo`）。
2. **新的 pin 触发是数据驱动的 `LaunchedEffect`**，键为
   `state.revision, state.streaming, renderedItems.size, following, tailPoke, sessionKey, bottomInset`：
   每次键变化后 `withFrameNanos { }`（**等**一帧，让这次发布新增的行量过高度）、**读一次**
   `layoutInfo` 组装同一个 `TailSnapshot`、交给仍然纯逻辑的 `TailFollow.onSnapshot`、
   然后用同一条 `requestScrollToItem` 落位。
   - 一次发布一次（`state.revision` 每次 publication 前进一步），**不再每帧**。
3. **"用户一操作就停"改成窄观察**：一条只观察 `listState.isScrollInProgress` 的
   `snapshotFlow`，`true` 的**上升沿**上 `pauseTail()`。
4. `unseenRows` 不再由机器镜像，而是**由数据算出来**：
   `pausedRows`（暂停时的转录行数，`rememberSaveable(sessionKey)`，旋转不丢）与当前 `transcriptRows` 之差。
   计数语义不变（"暂停之后新到达的行数"），但它不再需要每帧读列表。
5. `TailFollow.kt` 只改了两处**注释**（`TailSnapshot.transcriptRows` 的括号说明、`poke` 的
   "让 snapshotFlow 重新发射"改成"是这个效果的 key 的一部分"）——**逻辑一行没动**，
   harness（含 G14/G15）继续有效。

**终止性论证（一句话，父代理要求）**：

> **这条效果的每一个 key 都只能被"数据"改变（发布／新增行／用户手势／会话切换／inset），
> 而它的动作（`requestScrollToItem`）产生的是滚动与 measure，滚动与 measure 改变不了任何一个 key；
> 唯一会被它自己写的 key 是 `following`，而那只让效果再跑一次并在第一行 `return`。
> 所以它不可能自我维持。**

分开看两个效果，各自都不可能"每帧自触发"：

| 效果 | 观察的输入 | 动作 | 为什么输入不会被动作改写 |
|---|---|---|---|
| pin 效果（键 = 上面 7 个数据） | **无观察**（`withFrameNanos` 只是等一帧，不是订阅） | `requestScrollToItem` | 请求不改变任何 key（滚动/measure 不写 `state`/`renderedItems`/`following`） |
| 手势效果 | `listState.isScrollInProgress` | `pauseTail()`（写 `following`/`pausedRows`） | `requestScrollToItem` **不开启滚动会话**（`TailFollow` 的 KDoc，依据 `LazyListState` 源码），所以本代码不可能把 `isScrollInProgress` 置 `true`；`pauseTail` 写的 `following` 是 pin 效果的 key（跑一次即 `return`），不是手势效果的输入 |

**保留的行为**：①默认自然触底跟随（每次发布重新落位，长回答在流式期间每次 token 跟随）；②用户一滚动
就停（而且比原来更可靠：原来靠"几何倒退 + 滚动中"的推断，现在直接看滚动会话的上升沿）；
③「回到最新」可继续（`reArmTail()` 置 `following = true` 并 `tailPoke++`，即便没有别的 key 变化也会落位）。

**必须如实说清的行为差异（一条）**：原来机器有一条"回到末尾就自动恢复跟随"的规则
（`TailFollow.onSnapshot` 的 rule 3）。新触发只在 `following == true` 时才观察列表，所以
**暂停之后不会再靠"滚回底部"自动恢复**，要用「回到最新」按钮。这与**窗口之前**的行为一致
（`98cc21a` 的旧代码也"只关不开"），也在父代理列出的必须保留行为之内；但它是**行为差异**，
如果用户要求自动恢复，就需要再引入一条窄观察（例如 `atBottom` 的上升沿），**我这次没有加**。

**没有改的东西**：`TailFollow.kt` 的逻辑、`requestScrollToItem` 这个原语、`earlierArmed`/`renderWindow`
窗口加载（那处仍然读 `listState.firstVisibleItemIndex`，但它是一次性的：`earlierArmed` 先清后加、
`hiddenCount` 随窗口增长单调减到 0，且 pin 永远指向尾部、不会把列表拉回 index 0）。

**这次修正解决什么、不解决什么（诚实）**：它**结构性**地移除了"效果观察自己的输出"这一类自触发
（有上面的终止性论证），这正是用户新事实指向的那一类；但**我仍然没有证明它就是那次 ANR 的成因**
（§4.3.2 里我写不出那条每帧路径）。所以它是"由硬事实支持的结构性修正 + 可证的终止性"，
**不是"已证实修好的 bug"**。真机复现一次就能判定：若"点历史会话/发消息"仍卡且无 busy，
那说明还有第二个自触发源（下一步该查的是 `atTop` 那处派生观察，以及 LazyColumn 之外的组合）。

---

## 5. 未验证项与"下一轮真机怎么测"

**零成本判别法（不改一行代码）：**

1. **卡住时界面顶上有没有 `busy` 文案**（"切换会话"/"正在加载会话"/"读取会话状态"）。
   - 有 ⇒ 走的是 **C2 重放链**（失败被吞、界面无反馈）。
   - **没有 ⇒ 主线程被占死。用户已经实测：没有 busy 提示 ⇒ 症状是主线程被占死**（见 §4.3），
     所以这一条**已经判完**，后续只需要找"哪一步占死它"。
2. **把 `app.sessions.resumeLast` 关掉再复现一次**：
   - "第一条就卡/冷启动后就卡"消失 ⇒ C3 命中；
   - 仍然"第二条 100% 卡" ⇒ 与重放无关，回到 C5/C6/§2.2 残余。
3. **看一眼那个点不进去的会话文件有多大**（`…/pi/.pi/agent/sessions/*.jsonl`）：
   文件 ≥ 8 MiB ⇒ C2 的单条记录超限**必然**成立（`get_entries` 一条记录装整条会话）。
   这是唯一一个不用 logcat 就能证伪/证实 C2 的办法。

**本机做不到、必须真机才能定的：**

- `4e2f845` 之后是否还残留"pin 交替"的自维持循环（§2.2 残余）：需要真机上抓
  `requestScrollToItem` 的调用频率（或 Compose 的 recomposition/measure 计数），本机不能编译、不能跑。
- `retainState = true` 在真机上"内容变化时"的解析耗时与是否持有旧 AST 做重活：需要真机计时。
- C2 的 120 s 等待在真机上的实际观感（用户是否把它描述成"卡死"）：需要用户确认。
- C4 的 `PROCESS_LOCK` 等待造成的"重开很久不响应"：需要真机时序。

---

## 6. 上 CI 最可能出错的点

1. **§4.4 的 `ChatScreen.kt` 改动**（本轮唯一改到的 `main` 源集）：
   - 新增 `import androidx.compose.runtime.withFrameNanos`（已加在 `snapshotFlow` 之后），
     删掉了随 `unseenRows` 一起失效的 `import androidx.compose.runtime.mutableIntStateOf`；
   - `LaunchedEffect` 用 7 个 key ⇒ 走 `vararg keys` 重载（同文件 `:584`/`:602` 早已这么用）；
   - `unseenRows` 从 `var … by remember` 变成普通 `val`（`:1051`/`:1072` 的读法不变）；
   - `withFrameNanos { }` 的空 lambda 忽略其 `Long` 参数。
2. **本轮新增的 G14/G15（`app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt`，G 组末尾）**：
   纯断言，期望值按 `pinToTail()` 手算校对两遍（G14 `TailPin(5, 1000)`、G15 `null`）；
   若红了，请把 "actual vs expected" 贴回来 —— 那正是"收敛论证错在哪里"的证据。
   变量名 `tallAsked`/`tallSettled` 与 A 组的 `tall` 不在同一 `run { }` 作用域，标签用 G14/G15
   以避开已有的 G12/G13（`:484`/`:491`）。
3. **`4e2f845` 删掉了 `TailFollow` 的 `lastPinViewport` 字段**：`grep app/src/**`（含 test）**0 命中**。✅
4. **`TailFollowCheck.kt` 原有的 G9/G10/G11 期望值**：按 fixture 逐条算过，与实现一致（§2.3）。✅
5. **`markdownAnimations(animateTextSize = { this })` 的 API 形状**：本机只有编译产物、没有源码，
   具名参数是否叫 `animateTextSize` 只能由 CI 判（`dbdc1e3`/`bead9cc` 那轮回退又改回，
   最终形态以工作树为准）。
6. **`PiApplication.kt` 的恢复**：用 `git show HEAD:` 原文整文件覆盖，`git status` 显示已干净。✅
7. 本文件只是 `docs/*.md`，不参与编译。

---

## 7. 本机未编译（用户明令）

- **`typecheck.sh` / `run-app-pure-checks.sh` / Gradle 一律没跑**；这台"开发机"就是用户的手机。
- **CI 是唯一的编译器。** 本轮所有 Kotlin 结论都是"读源码 + 读本机缓存的库字节码（`javap`）"得出的，
  没有一行是编译或运行验证的。
- 本轮我对 Kotlin 源码的改动**只有三处**：
  1. `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt`：**只动跟随那一段**（§4.4，约 `:442-573`），
     同文件里另一个代理正在加的 P2-1 工具渲染块一个字没碰；
  2. `app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt`：**只改两处注释**，逻辑一行没动（§4.4 第 5 条）；
  3. `app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt`：新增 **G14/G15** 两条断言（§4.3.3）。
- **没有**碰 `PiSessionViewModel.kt`（§4.1 的改动单交给正在改它的代理）、没有碰
  pi / 载荷 / `known-gaps.md`、没有 git 写操作、没有派子代理、没有碰手机。
- 与本文一起进树的新文件只有 `docs/regression-hunt.md`。
