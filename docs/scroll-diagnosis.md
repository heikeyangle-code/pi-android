# 对话页滚动专项诊断（只查不改）

**被查修订**（本文件里的行号以此为准）

| 文件 | md5（我读的版本） | 备注 |
|---|---|---|
| `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt` | `3cfe49e2…`（16:07:26） | 现为 `6c9d55ce…`；期间另一个 agent 只改了 `ExtensionWidgetStack` 的两处 `remember`（与滚动无关），滚动相关行号未变 |
| `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` | `4752db12…`（16:07:26） | 现为 `7f4734…`；另一个 agent 正在把字符计量抽到新文件 `ui/HistoryRetention.kt`（16:14 新建），我已按现状复核（见 §0.1） |
| `app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt` | `3d572fb8…` | 未变 |
| `app/src/main/kotlin/app/pi/ui/PiRoot.kt` | 未变 | |
| `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt` | 未变 | |

**本机证据来源**（全部在 `/tmp`，未动仓库）

* `/tmp/probe/scroll/WindowAnchorProbe.kt` —— 与**真实的** `TailFollow.kt` 一起编译（用的就是 `prependAnchoredIndex` 本尊），只建模 `ChatScreen` 自己的窗口公式与 Compose 的两个已证事实。运行输出见 §1.4。
* `/tmp/probe/scroll/ListRebuildProbe.java` —— 量化每次发布在主线程重建列表的代价（§0.2）。
* `tools/run-app-pure-checks.sh`（另一个 agent 正在改它，结果见 §5）。
* `androidx.compose.foundation:foundation-android:1.8.3` 与 `com.mikepenz:multiplatform-markdown-renderer-android:0.45.0` 的**字节码**（`javap -c`），下面标「字节码」的结论都是从这里读出来的。

---

## 0. 结论摘要

**症状 2（切屏回来跳）** —— 切走时 `ChatScreen` **被销毁**（`when(current)` 让它离开组合），只有 `rememberSaveable` 通过 `SaveableStateHolder` 存活。位置**保存/恢复机制本身是对的**（这也是「切回来不会停在最底下」被修好的原因），坏在恢复的**语义**和**首帧几何**：

1. **S2-a（最可能、已证）**：`LazyListState` 的 saver 只存 `(firstVisibleItemIndex, offset)`，**不存行 key**（字节码）；而渲染窗口是 `visibleItems.takeLast(renderWindow)`（尾部锚定）。于是「离开期间 transcript 长了 g 行」会把恢复出来的同一索引解释成 **g 行更新的内容** → 阅读位置整块上移 g 行。这解释「跳一下」和「有时候位置变了」。
2. **S2-b（很可能、推断）**：`canScrollForward` 在**任何一次新 `LazyListState` 的首帧**都是 `false`（字节码：构造器里 `mutableStateOf(false)`），而 `reArmsEarlier` 把 `!canScrollForward` 当作「滚不动了」的武装边 → 若恢复出来的 `atTop==true` 且 `hiddenCount>0`，自动加载 effect 会在**首帧**就再加载一批并 `requestScrollToItem(≈51)`，把位置一次挪 50 行。
3. **S2-c（很可能、推断）**：`MarkdownState` 的初态是 `State.Loading`，它的 loading 槽是**空 `Box`（0 高）**（字节码），而 `rememberMarkdownState` 是 `remember`（不跨切屏）。所以切回来时**每一行都从 0 高开始长**：首帧几何 ≠ 离开时几何，`LazyListMeasure` 的 scroll-back 夹取会写回一个不同的位置；最后一行会「先空白再落下」= 刷/蹦。`retainState = true` 救不了这一条（首次组合没有 previous 可保留）—— 这就是「修了好几次没修好」的那一半。

**症状 1（往旧消息方向不自然）** —— 一次上滑的真实状态转移：

```
手指上移(看下面的消息)  = 列表向后滚，尾部追加/替换行 → 你正在看的内容不被插入影响
手指下移(看上面的消息)  = 列表向前滚 → 到达窗口顶(索引0=「加载更早的N条」哨兵行)
                        → 抬手 → 只有抬手后(!isScrollInProgress)才允许加载一批
                        → renderWindow += 50 → requestScrollToItem(把锚点从哨兵行挪到内容行)
                        → 列表重新测量
（窗口耗尽时）           → 读会话文件 → seedHistory(整条重建投影, Default) → syncTranscript 发布新 list
                        → renderedItems = takeLast(renderWindow) 被重新从尾部切
```

按贡献排序：

| # | 机制 | 量级 | 触发条件 | 证据等级 |
|---|---|---|---|---|
| P1 | 每批加载都把锚点**从哨兵行挪到内容行**（`prependAnchoredIndex` 的结果就是「旧内容行」），可见内容整体上移 **哨兵行的高度**（约 30–45dp） | 每批一次，约一行 | 每次自动加载（`atTop` 下每爬到顶一次） | **已证**（算术 + 探针 §1.4-B） |
| P2 | 文件读取批次**完全没有锚点补偿**：`renderedItems = takeLast(renderWindow)` 且 `renderWindow` 不增长 → 新读到的 `renderWindow - |transcript|` ∈ [0,49] 行插在哨兵行与阅读位置**之间**，阅读位置下移同样行数 | 最多 49 行，**一次一帧** | 只在会话大于 `readTail` 窗口（4000 条 / 8MiB）且把整个内存窗口都渲染完之后 | **已证**（算术 + 探针 §1.4-C） |
| P3 | **新进入视口的行首帧高度是错的**（markdown `Loading` = 0 高，解析落在 `Dispatchers.Default`）。往上滚时新行从**视口顶部**进入，它的高度修正把整屏内容往下推；往下滚时新行从**底部**进入，修正量在屏幕外 → **这就是「上下不一样自然」的机制** | 一行高度（几十~几百 px） | 每次向上滑进尚未组合过的行 | 库事实**已证**，可见性**推断** |
| P4 | 「必须抬手才加载」+「必须真的到索引 0」⇒ 每一批前面都有一次**硬到顶**（Android 12+ 拉伸回弹）+ 哨兵行文字变化 | 观感 | 每次自动加载 | 机制**已证**，观感**推断** |
| P5 | `firstOfRun/lastOfRun` 用**切片**邻行算：前插后锚点行的轨线缩进可能从「run 首」变「run 中」，**锚点行自己的高度**变 16–32dp | 半行 | 锚点是工具卡（ToolCall/ToolDiff）时 | **已证**（代码）+ 影响推断 |

为什么往新消息方向就没这些：① 那个方向**不触发加载**，没有「到边→抬手→重定位」的循环；② 渲染窗口是尾部锚定的，**尾部追加不改变现有行的索引**，跟随只是 `pinToTail()` 一次性贴底；③ 新行从**下方**进入视口，首帧高度误差发生在屏幕外（P3 的不对称性就是这个）。

---

## 0.1 先否定父代理给的 P0（`entryChars` 在主线程）

**结论：这条线索在当前工作树里已经不成立；它描述的是更早的一个修订。** 证据：

1. 我读的版本（`PiSessionViewModel.kt` md5 `4752db12`，16:07:26）里，`entryChars` 的 KDoc 已明写「**every caller runs it off the frame thread**」（`:717-725`），三处调用点全部在 `withContext(Dispatchers.IO)` 里：
   * `:2921-2930`（`replayHistory` 的 `readTail` + `entryCharsOf(read.entries)`）
   * `:3021-3022`（`replayHistoryOverRpc`：`val chars = withContext(Dispatchers.IO) { entryCharsOf(entries) }`）
   * `:3109`（`expandEarlierHistory`：`loadedHistoryChars += withContext(Dispatchers.IO) { entryCharsOf(loaded.entries) }`）
2. 父代理说的「`:2934` 还对**已保留的全部 entry** 重算」也不成立：那里是上一次修复留下的**增量**写法，源码自己写着「The **increment**, not a re-sum over `combined`」（快照 `:3102-3108`），并且 `loadedHistoryChars` 是累加字段。
3. 父代理给的行号（`:2772/:2856/:2934`）与快照对不上（快照 4797 行、那三行分别是 `loadedHistoryChars = windowChars` 之类），说明那份审计读的是更早的修订。
4. 补充证据：`git show HEAD:…PiSessionViewModel.kt` 里**根本没有 `entryChars`**（HEAD 4076 行），`git ls-tree HEAD` 里也没有 `ui/blocks/ImageSize.kt`。也就是说「窗口化历史读取 + `entryChars` + `pinToTail` 的一步到行末 + 图片行高由报头决定 + `reArmsEarlier`」这一整套**都还没提交**（`git status` 全是 ` M`）。**用户手机上跑的 APK 是不是含这些修复，取决于它是什么时候构建的 —— 这一点我在这台机器上查不到（没有设备 shell/ADB，见 §5）。**
5. 另一个 agent 此刻正在把这段计量抽成纯逻辑文件 `app/src/main/kotlin/app/pi/ui/HistoryRetention.kt`（16:14 新建，`internal fun entryChars/entryCharsOf`，`PiSessionViewModel` 只剩 `retainedChars(entries) = entryCharsOf(entries)` 的薄壳），三处调用点仍是 `withContext(Dispatchers.IO)`（现文件 `:2926`、`:3022`、`:3110`）。方向正确。

**仍然成立、但量级不足以造成跳的是「每次加载都会重建整条 transcript」这条链路本身**（`expandEarlierHistory` → `engine.seedHistory(combined)` → `syncTranscript` → 发布新 list）。它的代价分三段：

* 读文件 + 字符计量：已经在 IO；
* `seedHistory` 的投影：`PiEngineSession.seedHistory` 在 `Dispatchers.Default` 上做（`PiEngineSession.kt:997-1000`），是挂起的，不占帧线程；
* **真正落在帧线程上的**只剩 `syncTranscript` 的 `_state.value = copy(transcript = pub.rows, …)`（列表换引用，不拷贝）+ 随之而来的整屏重组与两个列表重建：`visibleItems = state.transcript.filterNot { it is DateSeparator }`（O(N)）与 `renderedItems = takeLast(renderWindow)`（O(R)）。

我用 `/tmp/probe/scroll/ListRebuildProbe.java` 量了那两个列表重建（桌面 JVM，盒装 `ArrayList`，200 次平均）：

```
transcript rows=  500  filterNot+takeLast = 180.1 us/publication
transcript rows= 2000  filterNot+takeLast = 609.1 us/publication
transcript rows= 8000  filterNot+takeLast = 457.0 us/publication
transcript rows=16000  filterNot+takeLast = 577.5 us/publication
```

**0.2–0.6 ms**（噪声内基本恒定），一帧是 8.3ms(120Hz)/16.7ms(60Hz)。**所以「重建 transcript」不可能是「会跳会刷会蹦」的原因**；它顶多让那一帧多花几百微秒。父代理那条链路的结论要改成：**计量已下 IO（不是主线程），重建不是可见跳动的成因。**

---

## 1. 第 1 条：往旧消息方向为什么不自然

### 1.1 一次真实交互的状态转移（含行号）

| 步 | 发生了什么 | 代码 |
|---|---|---|
| 1 | 手指下移 → 列表向前滚 → 到达窗口顶：`firstVisibleItemIndex == 0`（索引 0 就是哨兵行「加载更早的 N 条」，因为 `hiddenCount > 0`） | `ChatScreen.kt:932-934`（`atTop`）、`:673-674`（`showsEarlierRow`/`headerRows`）、`:1587`（`item(key = "transcript-earlier")`） |
| 2 | **必须在滚动会话结束之后**才能加载：`mayLoadEarlier(atTop, earlierArmed, hiddenCount, !isScrollInProgress)` | `ChatScreen.kt:968`；`TailFollow.kt:481-486` |
| 3 | 加载：`renderWindow += 50`，然后**马上**（同一次 effect 体、重组之前）请求 `requestScrollToItem(prependAnchoredIndex(firstVisibleIndex=0, 50, headerBefore=1, headerAfter), firstVisibleItemScrollOffset)` | `ChatScreen.kt:970-992` |
| 4 | `prependAnchoredIndex(0, 50, 1, headerAfter)` = `headerAfter + 0 + 50` ⇒ 请求的是**旧切片的第一个内容行**（哨兵行被顶出屏幕） | `TailFollow.kt:539-547` |
| 5 | 窗口真的耗尽（`hiddenCount == 0`）时，改读会话文件：`session.expandEarlierHistory()` | `ChatScreen.kt:961-963`、`:1595` |
| 6 | ViewModel：IO 读 `readBefore` → `engine.seedHistory(new + loadedHistory)`（**整条重建投影，Default 线程**）→ `syncTranscript` 发布新的 `transcript` list | `PiSessionViewModel.kt:3069-3117`（`:3100` seedHistory、`:3117` syncTranscript） |
| 7 | 发布后 `visibleItems`/`renderedItems` 重新计算，且 `renderedItems` **永远是从尾部切**：`visibleItems.takeLast(renderWindow)`；这一路**没有**任何位置补偿 | `ChatScreen.kt:616-663`（`:661`） |
| 8 | LazyColumn 重新测量（`requestScrollToItem` 走 `LazyListScrollPosition.requestPositionAndForgetLastKnownKey` + 一次 remeasure，**不进入滚动会话**） | 字节码：`LazyListState.requestScrollToItem` → `snapToItemIndexInternal(..., false)`；`TailFollow.kt:242-266` 的去重 |

### 1.2 哪一步造成可见的跳/刷/蹦（按贡献）

**P1 —— 每批加载都把「可见内容」整体上移一个哨兵行的高度（已证）**

* 事实：`mayLoadEarlier` 要求 `atWindowTop`，所以加载那一刻 `firstVisibleItemIndex == 0` —— **第一个可见项一定是哨兵行**（`showsEarlierRow` 保证 `hiddenCount>0` 时它就存在，且它是 item 0）。
* 而请求的索引是 `headerAfter + 0 + prepended`，即**旧切片的第一个内容行**；哨兵行在新列表里仍在 index 0、key 仍是 `transcript-earlier`，LazyColumn 自己的 key 锚定（`findIndexByKey` 的「同索引同 key」快路径，字节码已读）本来就会让它**不动**。
* 于是这次 `requestScrollToItem` 的实际效果是：把哨兵行和整批新行一起推到屏幕上方，可见内容上移 `哨兵行高度`。哨兵行是 `Icon + 6dp + Text + 8dp*2`（`:1588-1619`），约 30–45dp。
* **量级**：每加载一批一次 ≈ 一行。多次爬升就是「一顿一顿地跳」。探针 §1.4-B 验证了 `requestedIndex == 旧第一个内容行的新索引`（**这里没有 off-by-one**，父代理若有此怀疑可以排除）。

**P2 —— 文件读取批次（`expandEarlierHistory`）没有锚点补偿（已证）**

* 前置条件：`hiddenCount == 0` ⇒ `renderWindow >= visibleItems.size`（整个内存窗口都渲染着）。
* `renderedItems` 永远是 `takeLast(renderWindow)`（尾部锚定）。文件读取在 transcript **头部**插入 k 行后，切片被重新从尾部切：**旧行全部保留**，但在切片头部**新增** `renderWindow - |transcript|` ∈ **[0,49]** 行。
* 这一路**没有任何 `requestScrollToItem`**（ViewModel 不知道屏幕），而视口此刻锚在哨兵行（index 0，key 未变）⇒ 阅读位置**下移 0–49 行，一帧内完成**。探针 §1.4-C：`N=4123 R=4150 k=4000` → 切片头新增 27 行、锚点行索引 0→27；`N=1000 R=1000` → 新增 0 行（**不跳**）。
* **「有时候」在这里有精确解释**：只有 `renderWindow - N != 0` 时才跳，而 `renderWindow` 以 50 为步长增长、`N` 是行数，所以它经常是 0（不跳）、经常是 1–49（跳）。另外这一路只在会话大于 `readTail` 窗口（`HISTORY_WINDOW_ENTRIES = 4000` 或 `HISTORY_WINDOW_CHARS = 8MiB`，`PiSessionViewModel.kt:4748-4751`）且用户把整个 4000 条窗口都爬完之后才会触发。
* 顺带：`DateSeparator` 的 key 是 `nextKey("date") = "date-${now()}-${seq++}"`（`rpc/…/Transcript.kt:982`、`:2479`），而 `reset()` 不重置 `seq`（`:2485-2505`）⇒ **每次重建，所有日期分隔行的 key 都变**。若锚点行恰好是日期分隔行，Compose 的 key 锚定会落回「保持原索引」的分支（`findIndexByKey` 在 key 不存在时返回原索引，字节码已读），位置就按上面的行数漂。这不是主因（概率低），但它使「重建后按 key 保锚点」这条兜底不可靠。

**P3 —— 新进入视口的行首帧高度是错的（库事实已证 + 可见性推断）——这是「上下不一样自然」的机制**

* 字节码证实的库事实：
  * `MarkdownStateImpl.<init>` 把 state 初始化成 `MutableStateFlow(State.Loading)`；
  * `Markdown.kt:112` 的默认 loading 槽是 `Box(it)` —— **空 Box，0 高**；
  * `rememberMarkdownState` 用的只有 `remember`（`MarkdownStateKt` 里只有两个 `LaunchedEffect`，**没有任何 `rememberSaveable`**）⇒ 行一旦离开组合（切屏、或被 LazyColumn 回收），解析状态就没了；
  * 解析是异步的（`Dispatchers.Default`），所以「这一帧画成空白」是解析与 vsync 的赛跑。仓库自己已经在 `docs/streaming-review.md` §2.4 与 `PiMarkdown.kt:219-228` 记过同一个现象（当时的症状是流式时的「乱闪」）。
* 不对称：往上滚（看旧消息）时，新进入视口的行是从**视口顶部**进来的 —— 它的高度从 0 长到 H 时，**它下面的一切（也就是整屏正在读的文字）往下移动 H**；往下滚（看新消息）时新行从**底部**进来，同样的高度修正发生在屏幕外。**这就是「上滑看下面的消息挺好、下滑看上面的消息不对劲」的直接机制**，也解释了为什么它「有时候」：取决于进入顶部的那一行是不是还没解析完的大行。
* 另外 `retainState = true`（`PiMarkdown.kt:237`）只覆盖「同一组合内的内容更新」，**覆盖不到「整体重新组合」**——切屏、以及每次加载更早时新行第一次进入，都属于后者。
* P3 与 P1/P2 的区别：P1/P2 是「位置被谁写错了」，P3 是「行高本身在动」。

**P4 —— 每一批前面都有一次硬到顶 + 抬手（机制已证，观感推断）**

`mayLoadEarlier` 要求 `!isScrollInProgress`，`atTop` 要求真的到索引 0。所以：持续拖动**永远不会**加载，用户必须先撞到列表边界（Android 12+ 会画拉伸回弹 = 「会蹦」），抬手后才出现下一批。往新消息方向没有任何这种边界事件。这是设计取舍（`TailFollow.kt:452-486` 的 KDoc 解释了它换掉的是「一甩就冲回会话最顶部」），不是 bug —— 但它就是「不如上滑自然」的那部分。

**P5 —— 锚点行的轨线缩进（已证，量小）**

`previous/next` 取自 `renderedItems`（切片）而不是整条 `visibleItems`：

```kotlin
1663:  val previous = renderedItems.getOrNull(sliceIndex - 1)
1664:  val next = renderedItems.getOrNull(sliceIndex + 1)
1665:  val firstOfRun = previous !is ToolCall && previous !is ToolDiff
1666:  val lastOfRun = next !is ToolCall && next !is ToolDiff
```

前插之后，原本是切片第一行的那一行（= P1 里的锚点行）拿到了新的 `previous`，`firstOfRun` 可能 true→false，而执行轨道的两端各有 16dp 缩进 ⇒ **锚点行自己的高度变 16–32dp**，它下面的一切跟着动。只影响工具卡。

### 1.3 「为什么往下滑（看新消息）就没这个问题？」

1. 那个方向**不触发加载**（`expandEarlierHistory` 与 `renderWindow` 增长都只在 `atTop` 发生），所以没有「撞边 → 抬手 → 重定位」的循环（P1/P4 不存在）。
2. 渲染窗口是 `takeLast(renderWindow)`，**尾部锚定**：尾部追加/替换行不改变当前可见行的索引；跟随只是 `pinToTail()` 一次贴底（`TailFollow.kt:385-393`），且 `pinToTail()` 在「已经在末尾」的几何下返回 `null`（不重发，`:261-266`）。
3. **新行从下方进入视口**：`MarkdownState` 首帧 0 高这件事仍然发生，但它的高度修正在屏幕之外（P3）。这是最关键的一条不对称。

### 1.4 探针输出（原样）

（`/tmp/probe/scroll/WindowAnchorProbe.kt`，与真实 `TailFollow.kt` 一起编译）

```
== A. restore after rows arrived while the chat destination was not composed ==
   N=400 R=50 g=3  anchorRow=357 savedIndex=8 -> item@8 is row 360 (should be 357 at index 5)  displacement=3 rows
   N=400 R=50 g=40 anchorRow=357 savedIndex=8 -> item@8 is row 397 (should be 357 at index -1) displacement=40 rows
   N=1000 R=400 g=250 ... displacement=250 rows
   N=4000 R=4000 g=1 ... displacement=1 rows

== B. client-side prepend at atTop (real prependAnchoredIndex) ==
   hidden=350 prepended=50 requestedIndex=51  oldFirstContentRow=350 nowAt=51   [OK]
   sentinel before=sentinel after=sentinel (same key => index 0 kept)
   sentinel-gone case: hidden=50 requestedIndex=50 expected=50                   [OK]

== C. file-read prepend (expandEarlierHistory): a HEAD prepend, no anchor correction ==
   N=4123 R=4150 k=4000 -> slice 4123->4150; anchor row 0: item index 0 -> 27; rows added at the slice head=27
   N=1000 R=1000 k=500  -> slice 1000->1000; anchor row 0: item index 0 -> 0;  rows added at the slice head=0
   N=500  R=520  k=4000 -> slice 500->520;   anchor row 0: item index 0 -> 20; rows added at the slice head=20
   N=4000 R=4000 k=4000 -> slice 4000->4000; anchor row 0: item index 0 -> 0;  rows added at the slice head=0
```

A 组直接用来说明症状 2 的 S2-a；B 组否掉了「客户端前插有 off-by-one」；C 组给出 P2 的量级与「有时候」的判据。

---

## 2. 第 2 条：切到别的屏再切回来

### 2.1 切走时 `ChatScreen` 是销毁还是仅停止组合？

**销毁（离开组合），不是停止组合。** `PiRoot` 用 `when (current)` 三分支，只有当前目的地的分支在组合里：

```kotlin
// PiRoot.kt:661-682
when (current) {
    PiDestination.Chat -> saveableStateHolder.SaveableStateProvider(current.name) { ChatScreen(...) }
    PiDestination.Workbench -> saveableStateHolder.SaveableStateProvider(current.name) { ProjectScreen(...) }
    PiDestination.Settings -> saveableStateHolder.SaveableStateProvider(current.name) { PiSettingsStack(...) }
}
```

`saveableStateHolder = rememberSaveableStateHolder()`（`PiRoot.kt:552`，D30 引入，注释 `:535-551` 明说这是为了「切回来不丢位置」，并**否决了「进入即贴底」的兜底**）。所以：

* 离开时：`ChatBody` 里所有 `remember` **全部丢弃**；所有 `rememberSaveable` 由 holder 按 `"Chat"` 这个 key 存下来；`LazyColumn`/`LazyListState` 这一层组合被销毁。
* 回来时：重新组合，`rememberSaveable` 从 holder 恢复，`remember` 重新初始化。
* ViewModel 不受影响（`viewModel()` 在 PiRoot 上，整机一个），`state.transcript`/`history` 继续被引擎发布推进（`PiSessionViewModel.kt:1943` 的 `engine.publication.collect { syncTranscript(engine, it) }` 挂在 attach 的 scope 上，与页面无关）。

### 2.2 位置由谁保存、谁恢复、哪一步冲掉

| 状态 | 归宿 | 恢复时 |
|---|---|---|
| 滚动位置 | `LazyListState.Saver` = `listOf(firstVisibleItemIndex, firstVisibleItemScrollOffset)`（**字节码证实，只有索引和像素偏移，没有行 key**） | 恢复成 `LazyListState(index, offset)`，`lastKnownFirstItemKey == null` ⇒ **首帧不做 key 锚定**，索引是权威 |
| `renderWindow`/`windowOpen`/`earlierArmed`/`pausedRows`/`tail`/搜索框与游标/`toolsExpanded` | `rememberSaveable(sessionKey, …)`（`ChatScreen.kt:651/659/942/789/782/574-576/361`） | 同一输入 key（`sessionKey = state.meta.sessionFile ?: sessionId`，`:650`）⇒ 恢复；若 `sessionKey` 变了则**丢弃并重新初始化**（切会话才会发生） |
| `draft`/`attachments` | ViewModel（`session.composerDraft`） | 一直在 |
| `scrolling`/`tailPoke`/`pendingJump`/`searchScan`/`sheet`/`overflow` | 纯 `remember` | 重置（无关位置） |
| 转录本身 | ViewModel `state.transcript` | 一直在（而**恰恰是它会变**） |

**把它冲掉的三条（按可能性）：**

* **S2-a：`takeLast` + 只存索引。** 渲染窗口是「转录的最后 `renderWindow` 行」。离开期间引擎每发布一次就可能追加行（工具卡、思考块、下一条消息……），于是同一个索引落到了 **g 行更新**的内容上。用户不是在底部时（暂停阅读历史）**没有任何机制把它拉回来**——这就是「切回来位置变了」。若用户在底部且在跟随，尾部锚定让「相对索引」不变、`atBottom` 仍为真、pin 为 `null`（`TailFollow.kt:385-393`），所以**在底部时反而没问题**，这解释了症状为什么「有时候」。
  * **前提（必须写清）**：只有当窗口是「真窗口」——`renderWindow < visibleItems.size`（哨兵行在、`hiddenCount > 0`，长会话的常态）——时才会偏。若 `hiddenCount == 0`（`windowOpen`，或会话短于窗口），切片就是「全部行」，尾部追加不改变任何索引 ⇒ 内容不动，只是「不再在底部」；此时若还在跟随，pin 会把它贴回底部（用户看到的是「回到最新」，属期望行为）。所以 S2-a 的触发条件是「长会话 + 用户不在底部 + 离开期间有新行到达」。
  * 探针 A 组：g=3 → 偏 3 行；g=40 → 偏 40 行；g=250 → 偏 250 行。
  * 修复前可以用「同一个词在屏幕上的高度」实测：切走前记下一句话，切回来它在屏幕上的位置整体移动了 g 行的距离。
* **S2-b：首帧 `canScrollForward == false` 造成的伪武装。** 字节码：`LazyListState` 构造器 `canScrollForward$delegate = mutableStateOf(false)` ⇒ **在第一次测量之前它一定是 false**；而 `reArmsEarlier(atTop, canScrollForward) = !atTop || !canScrollForward`（`TailFollow.kt:511-512`）。`scrolling` 初值 false，`hiddenCount>0`，于是若恢复出来的索引是 0（用户停在窗口最顶），首帧 effect 就会：`earlierArmed = true` → `mayLoadEarlier(...)` 为真 → `renderWindow += 50` + `requestScrollToItem(≈51)` —— **首帧把窗口多塞 50 行、把哨兵行顶出屏幕、位置一次挪 50 行**。开屏时同一只 effect 也会这样，但那时跟随的 pin 会把它按到尾部，所以看不见；切回来（用户没在跟随）就看得见。
  * 置信边界：这取决于「`LaunchedEffect` 体是否在本帧 measure 之前跑」。同一文件里跟随 effect 的注释（`ChatScreen.kt:857-861`：「reading `layoutInfo` before the layout pass would compute the pin from the previous frame's geometry」，并且为此加了 `withFrameNanos { }`）说明作者认为**会**。（我无法在本机执行 Compose，故标 `[推断]`；但修复代价极低，见 §3.2。）
* **S2-c：重进首帧的几何 ≠ 离开时的几何。** 每一行 markdown 重新组合 ⇒ `State.Loading` ⇒ 0 高（§1.2-P3 的库事实）。整屏一起塌成 0 高时，`LazyListMeasure` 的 scroll-back 分支（`TailFollow.kt:363-366` 已经引用过它的存在：`LazyListMeasure.kt:246-269` 会把过冲夹回内容末端）会按**塌陷后的**几何夹一次并把结果写回 `scrollPosition`；解析落地后内容长回来，位置却已经是夹出来的那个。用户就看到「先空白/先在某处，然后跳」。
  * 这条同时解释了为什么「修了好几次没修好」：之前所有修复都在保 **state**（`SaveableStateHolder`、`TailFollowSaver`、`renderWindow` 的 session key），而 state 一直是好的；坏的是**首帧的测量几何**。

### 2.3 重进时 `LaunchedEffect` 第一次跑会做什么

| effect | 首帧行为 | 是否可能移动位置 |
|---|---|---|
| `LaunchedEffect(state.revision, state.streaming, renderedItems.size, scrolling, atBottom, tailPoke, sessionKey, bottomInset)`（`:834-902`） | `withFrameNanos{}` 等一帧 → 读 `layoutInfo` → `tail.onSnapshot(...)` → 若 `following` 且不在滚动会话中，则 `requestScrollToItem(pin)` | 会：`following` 从 `TailFollowSaver` 恢复；不过在底部时 `pinToTail()` 返回 null（不移动）。若离开期间行数变多且用户在底部（窗口短到 `hiddenCount==0` 时），`atBottom` 会先真后假 → pin → 跳到底 |
| `LaunchedEffect(atTop, canScrollForward, hiddenCount, scrolling, earlierHistory)`（`:952-993`） | 见 S2-b：可能首帧就加载一批 + 重定位 | **会** |
| `LaunchedEffect(listState)` 的 `snapshotFlow { isScrollInProgress }`（`:909-918`） | 收集器先见 `false` | 不会 |
| `LaunchedEffect(pendingJump, …)`（`:1011-1024`） | `pendingJump` 是纯 `remember`，恢复为 null | 不会 |
| `LaunchedEffect(fill?.seq)`（`:1032-1037`） | 消费扩展填充；重进时会重跑一次（注释自己写了这一点） | 不涉及位置 |

---

## 3. 最小修法（每条约 1–3 处，可照着写）

> 前提：**现在不要动 `ChatScreen.kt` / `PiSessionViewModel.kt` / `ui/chat/**`**（另一个 agent 在改）。下面按「文件」标注，`ChatScreen.kt` 的那几处等它收工。

### 3.1 症状 2 的主修：把锚点从「索引」改成「行 key」（`ChatScreen.kt`，约 15 行）

```kotlin
// 1) 与 renderWindow/tail 同级，跟会话走
var anchorKey by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
var anchorOffset by rememberSaveable(sessionKey) { mutableIntStateOf(0) }

// 2) 只在离开组合的那一刻读一次（同一次旋转/切屏都要）
DisposableEffect(listState) {
    onDispose {
        val i = listState.firstVisibleItemIndex - headerRows
        anchorKey = renderedItems.getOrNull(i)?.key
        anchorOffset = listState.firstVisibleItemScrollOffset
    }
}

// 3) 一次性纠正：只有「key 解析出来的索引」与恢复出来的索引不一致时才动
var anchorRestored by remember { mutableStateOf(false) }
LaunchedEffect(anchorKey, renderedItems, headerRows) {
    val key = anchorKey ?: return@LaunchedEffect
    if (anchorRestored) return@LaunchedEffect
    if (listState.isScrollInProgress) return@LaunchedEffect   // 用户已经接手，绝不再动
    val i = renderedItems.indexOfFirst { it.key == key }
    if (i >= 0) {
        val target = headerRows + i
        if (target != listState.firstVisibleItemIndex) {
            listState.requestScrollToItem(target, anchorOffset)
        }
    }
    anchorRestored = true
}
```

* 代价：一次 `DisposableEffect` + 首帧一次 O(rendered) 扫描（≤ 几千项，微秒级）；`requestScrollToItem` 只在真的错位时调用，因此**没有副作用通道**（它不进滚动会话，不会打断手势，重复调用被去重）。
* 风险与边界：`anchorKey` 是 `rememberSaveable(sessionKey)`，切会话自动清空；锚点行不在渲染窗口内（`i < 0`，例如会话被换掉）时**不动**，退回原来的索引恢复。要「一帧都不动」还得配合 3.2/3.3。
* 怎么证明没有副作用：① 在跟随时（`following==true`）`pinToTail()` 本来就会把位置按到尾部，纠正 effect 若与它同时生效，顺序是「pin 先（两帧内）→ 纠正只在自己那一次跑」 ⇒ 需要让纠正 effect 在 `following` 时直接 return，或把它放在同一个 effect 里；② 纯逻辑可测的部分（key→索引）可以照 `TailFollowCheck` 的样子加一个 harness；③ 真机判据见 §4-S1。

### 3.2 症状 2 的次修：不要在第一帧武装（`ChatScreen.kt`，1 行）

```kotlin
LaunchedEffect(atTop, canScrollForward, hiddenCount, scrolling, earlierHistory) {
    // 没有任何一次测量时，canScrollForward 恒为 false（LazyListState 构造器），
    // 而 reArmsEarlier 把「滚不动」当武装边 —— 首帧必然是伪武装。
    if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
    if (reArmsEarlier(atTop, canScrollForward)) earlierArmed = true
    ...
```

或者更省事：在 effect 体开头 `withFrameNanos { }`（与跟随 effect 同样的理由与写法，`:861`）。
* 代价：这一条规则晚一帧生效（加载更早本来就要求抬手，所以用户不会察觉）。
* 风险：极低；不改变任何正常路径的判定，只排除「首帧 layoutInfo 为空」这一种状态。
* 证明：把 `canScrollForward` 与 `layoutInfo.totalItemsCount` 打到 logcat（debug），切屏回来时应看到 `totalItemsCount` 先 0 后非 0，而加载不再在其中发生。

### 3.3 症状 1/2 的共同根因：让重进/新进入的行**首帧就是正确高度**（`ui/render/PiMarkdown.kt` + 一个新 cache 文件）

* 做法：进程级 LRU（建议 64 项）缓存 library 的 `MarkdownState`，key = 内容哈希（+ 主题相关键），`PiMarkdownText` 走 `Markdown(state = cached, …)` 重载；行重新进入组合时直接命中 `State.Success`，**首帧高度就是离开时的高度**。
* 重构边界：缓存只保存**解析结果**（`State.Success` 的 AST/AnnotatedString），宽度相关的测量仍然由 Compose 做；LRU 上界防内存；`MarkdownState` 是不可变内容→状态映射，跨行共享同一份内容是安全的。
* 为什么必须做：`retainState = true` 只能覆盖「同一组合内的更新」，而切屏与新行首次进入都属于「新的组合」，此时没有 previous 可保留 ⇒ 首帧必然是 `Loading` 的 0 高。
* 落地难点（先说清）：库的解析由**组合内**的 `LaunchedEffect` 驱动，所以「提前 warm 一个 state」能不能真的让它先解析完，需要一个小验证（20 行 spike）。**退路**：给行加高度下限 —— `Modifier.heightIn(min = lastMeasuredHeight[item.key])`，用 `onSizeChanged` 记录、用一个有界 map（ViewModel 或 LRU）持有。这条退路不依赖库内部，但需要处理「内容变短」的情况（下限只在首帧用，测量完清掉）。
* 怎么证明没有副作用：LRU 上界 + 单元测试「同一内容两次取到同一 state」；真机上看「切回来首帧不再空白」（§4-S4）。
* 备选（不改库，只改 ChatScreen）：把「重进后前 N 帧（或直到用户触摸）持续把锚点写回 key 对应的索引」当成策略 —— 便宜，但会与用户手势竞争，不如 3.3 干净。

### 3.4 症状 1 的主修：不要为自动加载把锚点从哨兵行挪走（`ChatScreen.kt`，1–3 行）

把 `:984-992` 的 `requestScrollToItem(prependAnchoredIndex(...), ...)` 改成**不请求**（或仅当 `firstVisibleItemIndex != 0` 时保留原逻辑）：

```kotlin
// atTop 下 firstVisibleItemIndex 恒为 0，也就是哨兵行；它在新列表里仍是 index 0、key 未变，
// LazyColumn 自己的 key 锚定会保住像素 —— 新行出现在哨兵行下面，已读内容一个像素都不动。
// 旧的 prependAnchoredIndex 请求等于把哨兵行顶出屏幕，可见内容整体上移一个哨兵行的高度。
```

* 效果：P1 消失（每批不再有 ~40dp 上移），行为变成「旧消息从顶部推入」的 reverse-infinite-scroll —— 也就是用户要的「上下一样自然」。
* 风险：`earlierArmed` 的语义要复核：请求不再改变 `firstVisibleItemIndex`（仍是 0），但 `hiddenCount` 变了 ⇒ effect 会重跑 ⇒ `reArmsEarlier(atTop=true, canScrollForward=true)` = false ⇒ 不会连发（现在也是靠 `earlierArmed=false` 挡住）。**必须用 harness 加一条**「一批加载后仍然 `atTop` 时不得立刻再加载」。
* 代价：`prependAnchoredIndex` 变成只在非 atTop 路径用（当前没有这样的调用者）——可以保留函数与 harness，或标注它只在手动路径用。

### 3.5 症状 1 的次修：文件读取批次要有补偿（`ChatScreen.kt` + 可选 `PiSessionViewModel.kt`）

* 方案 A（最简，`ChatScreen.kt`）：记住上一次的 `state.transcript.size`，当它**变大且 `windowOpen == false` 且增大的量是一整批（即文件读取落地）**时，把 `renderWindow` 同步加上这个增量，让新行进入渲染窗口而不是留在隐藏前缀；或 `requestScrollToItem(firstVisibleItemIndex + delta, offset)`（`firstVisibleItemIndex` 此刻是 0，delta 就是 `rows added at the slice head`，探针 C 组正是这个数）。
  * 代价：一次文件读取可能让 `renderWindow` 一次涨到 4000（本来也要涨，只是分 80 次）。
  * 风险：`delta` 是**行数**而 ViewModel 只知道**条目数/字符数**（日期分隔行会让两者不等）⇒ 必须用 `state.transcript.size` 的前后差，不能用 ViewModel 的通知值。
* 方案 B（结构性，推荐方向）：把窗口从「尾部 count」改成「显式起点」——`renderedItems = visibleItems.subList(renderWindowStart, visibleItems.size)`，加载更早时只减 `renderWindowStart`。这样：文件读取在头部插入后，把 `renderWindowStart` 前移同一 delta（新行自动进窗口、尾端不动），且本地批次根本不需要 `requestScrollToItem`（哨兵行不动、LazyColumn key 锚定天然保住内容行）⇒ 3.4 自动成立。改动集中在 `ChatScreen.kt`：`hiddenCount`、`showsEarlierRow`、`reveal()`、`pendingJump` 的 `index = row - hiddenCount + headerRows`、`:1378/:1388` 的两处索引换算。**不碰 ViewModel**。等另一个 agent 收工后再动。
* 证明：I1 的验收（`docs/device-verification.md:603-612`）现在可以真的通过；再加一条纯逻辑 harness 把「文件读取后 renderedItems 与锚点索引」钉住。

### 3.6 症状 1 的小修：轨线判定用整条列表的邻行（`ChatScreen.kt`，2 行）

```kotlin
val previous = visibleItems.getOrNull(hiddenCount + sliceIndex - 1)
val next     = visibleItems.getOrNull(hiddenCount + sliceIndex + 1)
```

`renderedItems[sliceIndex] == visibleItems[hiddenCount + sliceIndex]`，所以窗口内部结果完全不变，只有切片边界那两行会看到隐藏的邻居 —— 而这正是我们想要的：run 是**整条转录**的属性（`:1655-1662` 的注释自己就这么说），并且前插后锚点行的 `firstOfRun` 不再翻转、高度不再抖（P5）。
* 风险：边界行的轨道会「延伸出窗口」，是画法上的变化，需要看一眼截图确认视觉合理。

---

## 4. 上机判据（Xiaomi M2011K2C / Android 14）

**通用手法（可量化）**

1. 系统屏幕录制（控制中心 → 屏幕录制）录一段，然后在电脑上抽帧：
   `ffmpeg -i rec.mp4 -vf fps=240 -vsync 0 f/%05d.png`（录制是 60fps 的话抽 240 也只是重复帧，够用）。
2. 在屏幕上选一个**短而且独一无二**的字符串（比如某条消息里的一个专有名词），逐帧记它的**上边缘 y 坐标**。一帧内 y 变化 > 4px 就算「跳」。
3. 更省事的肉眼判据：**手指按住不动时，屏幕上不许有任何像素移动；松手后新内容只允许「从上方推入 / 从下方推入」，不允许已经看得见的文字往上或往下蹦。**

### S1（症状 2，无新行）

* 操作：滚到会话中段，记住屏幕**最上面一行**的文字 → 切到「工作区」→ 停 10 秒（不触发任何回合）→ 切回「对话」。
* 通过：从切回来的**第一帧**起，那一行就在原来的高度（±2px），不得先出现在别处再回来。
* 现在：可能因 S2-c（首帧塌陷 + 夹取）移动；修正后应一帧不动。

### S2（症状 2，有新行到达）—— 最容易一眼看出

* 操作：让 pi 正在干活（流式），记住某一句 → 切到「工作区」停 5 秒 → 切回。
* 通过：仍然停在同一句话上。
* 现在：位置会按「这 5 秒新增的行数」整体上移 g 行（探针 A 组：g=3 就是 3 行）。
* 量化：数这 5 秒里 transcript 增加了多少行（同一会话里数新出现的卡片），与屏幕上那句话移动的行数对照，应当**线性相等** —— 这就是 S2-a 的直接验证。

### S3（症状 2，停在窗口最顶）

* 操作：一路滚到最顶（看到「加载更早的 N 条」）→ 切走 → 切回。
* 通过：一帧不动，`N` 不变。
* 现在：若首帧伪武装（S2-b）成立，会看到「N 立刻少了 50」且哨兵行被顶出屏幕（位置一次挪 50 行）。

### U1（症状 1，每批一次）

* 操作：把列表停在窗口最顶（哨兵行在屏幕最上方、手指按住不动）→ 松手 → **只看松手后的 2–3 帧**。
* 通过：屏幕上被跟踪的那一行不移动（哨兵行允许滚出屏幕上方，但**它下面**被跟踪的行不得整体上移）；`N` 的减少只体现在哨兵行的文字上。
* 现在：会整体上移约 30–45dp（P1）。
* 量化：松手前后同一行的上边缘 y 差 / 屏幕密度（本机 1dp ≈ 2.75px 量级）= 应当 ≈ 0 而不是 ≈ 30–45dp。

### U2（症状 1，观感）

* 操作：连做 5 次「滑到顶、松手」，录屏。
* 通过：只有「新行从上方推入」；不出现任何「已读内容整体上移一行」的帧；不应在顶部看到明显的拉伸回弹反复出现（P4 是设计取舍，但至少不能叠加 P1 的位移）。

### U3（症状 1，文件读取路径）

* 前置：需要一条 **entry 数 > 4000（或 > 8MiB）** 的会话（用 pi 的 `/export` 或直接数 `.jsonl` 非 header 行数确认）。否则这条路永远不触发（`readTail` 一次就 `reachedStart`）。
* 操作：一路往上爬，直到 `renderWindow` 覆盖整个内存窗口 —— 即哨兵行从「加载更早的 N 条」变成「加载更早的内容」/「正在读取更早的内容…」的那一刻（这是唯一入口）。
* 通过：落地那一帧，屏幕内容不得整块替换、阅读位置不得移动。
* 现在：最多下移 49 行（探针 C 组；`renderWindow - N == 0` 时不跳，所以要多试几次）。

### 日志侧的量化（需要 debug 构建 / logcat，本会话做不到）

在 debug 里把 `listState.firstVisibleItemIndex/ScrollOffset`、`hiddenCount`、`renderWindow`、`anchorKey` 打点，切屏前后各打一条；`requestScrollToItem` 只在参数变化时打点。这样「跳了几行」有数字，也能直接区分 S2-a / S2-b / S2-c。

---

## 5. 本机验证不到的部分（如实）

1. **打不出 APK**：本容器没有 Compose 编译器插件、AAPT2 只有 x86-64，`app/` 无法 assemble（这是父代理已知的前提）。所以 §3 的任何改动我都没跑过。
2. **没有设备 shell / ADB**：桥只到屏幕与文件层（`/app/help` 的端点里没有 shell；`/app/apps` 直接返回 `[APP_LIST_UNAVAILABLE]`，原因写的是「设备停止操作会通过 ADB/Shizuku 重新读取」）。所以我**拿不到 logcat、拿不到已安装 APK 的构建时间、不能自己 screenrecord 抽帧**。§4 的判据只能由用户或另一个有 adb 的 agent 执行。也因此**「用户手机上的 APK 是否包含当前工作树里的修复」我无法确认** —— 而这些修复（`SessionFileReader`、`entryChars` 的 IO、`ImageSize`、`reArmsEarlier`）**全都还没提交**（`git show HEAD:` 里没有它们）。
3. **Compose 的测量/跳过时机只能推理**，这些正是 §1.2-P3、§2.2-S2-b、§2.2-S2-c 的置信边界：
   * `LaunchedEffect` 体是否在本帧 measure 之前运行（我按同文件 `:857-861` 的注释判为「会」）；
   * `LazyListMeasure` 的 scroll-back 是否把夹取结果写回 `scrollPosition`（`LazyListScrollPosition.updateFromMeasureResult` 存在，但具体分支没跑过）；
   * LazyColumn 对滚出视口的行是否丢弃 `remember` 状态（标准行为，但会影响 P3 的「每次进入」还是「只偶尔」）。
   我能证的是**库的事实**（saver 只有索引+偏移、`canScrollForward` 初值 false、`requestScrollToItem` 不进滚动会话、`findIndexByKey` 找不到 key 就保留原索引、markdown 初态 `Loading` 且 loading 槽是空 Box、`rememberMarkdownState` 不 saveable）—— 全部来自字节码。
4. **真机「markdown 首帧是否真的露出 0 高」**取决于解析与 vsync 的赛跑（仓库自己的 `docs/streaming-review.md` §2.4 / `PiMarkdown.kt:219-228` 把它记为「未验证」），本机测不到。可证的是初态与槽位。
5. `tools/run-app-pure-checks.sh` 我起了两次：第一次在父代理提示「23 个全绿」之后运行，但另一个 agent 同时在改 `tools/run-app-pure-checks.sh` 并新增 `HistoryRetentionCheck.kt`（16:15），所以那次结果不代表任何稳定修订；第二次的结果见随附日志 `/tmp/probe/scroll/pure-checks.log`（结论行 `pure-checks: OK — N harnesses ran`）。

---

## 6. 一句话给用户

* 切回来跳：**位置是按「第几行」存的，不是按「哪条消息」存的**，而渲染窗口又总跟着最新消息走，所以你在别处待着的这段时间里新到的任何消息都会把你顶走 g 行；另外重进的**第一帧**里每一行 markdown 都还没解析完（0 高），测量的结果和离开时不一样。修法：锚点改存**行 key**（约 15 行），首帧别信 `canScrollForward`（1 行），再让 markdown 的解析结果跨切屏活着（一个 LRU）。
* 往上看不自然：**每加载一批，代码会主动把锚点从「加载更早的 N 条」那一行挪到内容行**，于是已读文字整体上移约 40dp，一批一次；往上滚时新行又是从**顶部**进来、首帧高度是错的，把整屏往下推（往下滚时这些都在屏幕外，所以那边好）。修法：不要再挪锚点（1–3 行），文件读取批次补上补偿（或把窗口改成显式起点）。
