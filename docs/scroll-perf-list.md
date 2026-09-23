# 对话页滚动性能审查 · 列表与状态层（`ChatScreen.kt` 的滚动/位置路径）

**症状（用户原话）**：「上下滑动出现一些东西的时候会有点顿，有点卡。什么工具卡呀之类的。出现这些东西的时候，滑动不流畅」。

**本文件的范围**：`ui/screens/ChatScreen.kt` 的列表与状态层（发布粒度／整列表重组、每项组合期成本、行高下限的二次布局、eager markdown 同步解析、哨兵浮层、锚点跟踪器、滚动相关的其它）。`ui/blocks/**`、`ui/render/**`（除本轮已知滚动文件）、`ui/chat/**`（除滚动文件）里**别人的**代码只做只读定位，需要改的写成 §5 的 patch。

**被查修订**（我读它、并据它做判断的 md5）

| 文件 | 改前 md5 | 改后 md5（第二轮：每帧预算 + 解析信号） |
|---|---|---|
| `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt` | `f5c8b9448a3617d483f39b32e5028a78` | `dcb5222e2865e3b6d6ef5a7964b59703`（含 §9 的缩略图缓存 patch） |
| `app/src/main/kotlin/app/pi/ui/render/TranscriptRowHeight.kt` | `e182130a5775f26d70038eea61e670e4` | `ec7fb24c04ded58d026fe6f4c1963ea9` |
| `app/src/main/kotlin/app/pi/ui/render/RowHeightCache.kt` | `126a9928bfd83fcb0b67f0f9682ff641` | `88f66495aa4c82c1b6a38fd367684712` |
| `app/src/main/kotlin/app/pi/ui/render/PiMarkdownImmediate.kt` | `（第一轮改前）` | `479582615862282a16b787f5f0c46d5d` |
| `app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt` | `（第二轮委派方授权后首次改）` | `604de68ee9f4b3f081d654d56844aae0` |
| `ui/chat/TailFollow.kt`、`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt` | 未改，只读 | 同 |

**证据来源**（三类，下文分别标 `[读码]` `[字节码]` `[实测]` `[推断]`）

* `[读码]` 现工作树，符号级；pi 的引用给 `file:line`。
* `[字节码]` 本机 Gradle 缓存/`build/typecheck` 里的 artifact，用 `javap -c/-p` 读：`foundation-release/classes.jar`（1.8.3）、`foundation-layout-release/classes.jar`、`ui-release/classes.jar`、`extra/aar/multiplatform-markdown-renderer-android/classes.jar`（renderer 0.45.0）、`extra/markdown-jvm-0.7.5.jar`。
* `[实测]` 本机（aarch64 容器，HotSpot 21.0.12）bare-JVM 探针，源码在 `/tmp/audit/probe/`：
  * `ParseProbe.kt` —— 用**真的** `MarkdownStateImpl.parseBlocking()`（反射调用其 internal 改写名 `parseBlocking$com_mikepenz_multiplatform_markdown_renderer`）+ 真的 `MarkdownParser(GFMFlavourDescriptor())` 量同步解析；
  * `ListStateProbe.kt` —— 用 `build/typecheck/rpc.jar` 里**真的** `TranscriptItem` 类型 + **真的** `RowHeightCache.kt` 量发布期的列表工作。
  ART 与 HotSpot 有差异，所以下面的数字按**量级**读；每条都给了上机复核判据（§4）。
* 唯一用到的站外权威材料是 Compose 的 strong skipping 官方说明（flag 默认值）：`kotlinlang.org` 的 `ComposeFeatureFlag.StrongSkipping`（"This feature is enabled by default"，Kotlin 2.5 起连 flag 都要删）与 JetBrains 的 design doc（不稳定参数按 `===` 比较、捕获不稳定的 lambda 会被 memoize）。

---

## 0. 结论先行：造成「出现新东西时顿」的机制排序

| # | 机制 | 触发条件（用户能感知的那个动作） | 代价量级 | 证据等级 | 处置 |
|---|---|---|---|---|---|
| **1** | **滚进待 eager 解析的行时，markdown 在帧线程同步解析**（`LocalPiMarkdownImmediate = true` → 库的 `immediate = true` → `parseBlocking()` + 节点树组合） | 往上滚进「加载更早」刚取回的批次；切屏回来那一帧；任何 `freshRowKeys` 里的行进入视口 | **实测**：20 KB 工具卡 **5.3 ms**、50 KB **10.5 ms**（折算 0.2–0.6 ms/KB）；60 fps 预算是 16.7 ms，**一行吃掉 1/3–2/3**，一行以上必掉帧 | 「帧线程同步」= `[字节码]` 已证；代价 = `[实测]` | **第二轮已按「每帧预算」做掉**（§2.4）：不再由字符数决定任何一行的命运；一帧的同步解析总量 ≤ 4 ms + 至多一行（那一行是最坏单行 10.5 ms），其余同帧新行退回异步 |
| **2** | **搜索匹配扫描在帧线程，且流式期间每 200 ms 一遍** | 打开搜索框 + 正在流式输出时滚动（哪怕不滚动也卡） | **每次 80.6 ms（median）／32.3 ms（min）**，500 行（375×2 KB 正文 + 125×20 KB 工具输出）；`SEARCH_RESCAN_MS = 200` ⇒ 帧线程约 2/5 的时间在 80 ms 的块里 | `[实测]` | **已改**：扫描移到 `Dispatchers.Default`，结果落地再发布（§2.2） |
| **3** | **行高下限的「3 帧窗口」是跟异步 parse 的赛跑；输了反而制造两次几何变化** | 同上：行进了视口、下限生效、parse 在 3 帧（50 ms）内没落地 | 输了：行先塌到 0（内容整体上跳）再长回来（跳回），**两次**几何变化 + 每行 3 次重组 + 1 次重测量；赢了也有每行 3 次重组 | 机制 `[读码]`+`[字节码]`；可见性 `[推断]`（需上机） | **两轮已改**：下限改 placement 期（§2.3）且**解除改由库的 `State.Success` 驱动**（§2.5）——塌陷/长回这一类几何变化在原理上不再出现，12 帧只作「内容永远量不出高度」的兜底 |
| **4** | 「整列表重组」：每次发布 `ChatBody` 整体重组、`itemsIndexed` 的 provider 换新 → 可见项重进组合；唯一破坏跳过的不稳定捕获是 `onForkFromMessage` | 流式输出期间滑动（每 200 ms 一次） | 列表算术 **0.15–0.5 ms/次**（实测，§3.1）；行体重跑被 strong skipping 挡住（唯一例外见左） | 机制 `[字节码]`+官方文档；量级 `[实测]` | **已改**：把该 lambda 的 identity 固定（§2.1）。修后量级 <1 ms/次，**不是主因** |
| **5** | 每行 3 次 `frames++` → 3 次行重组（外加解除下限那次重测量） | 每次滚动把新行带进组合 | 每行 3 次重组 + 1 次重测量；一次 fling 10–30 行/s ⇒ 30–90 次额外重组/s | `[读码]`+`[字节码]` | **已改**：随第 3 条一起变成 1 次写（§2.3） |
| **6** | 锚点跟踪器每个滚动帧写一次 state（`anchorOffset`） | 手指拖动/惯性期间 | 每帧：1 次 `snapshotFlow` 恢复 + 1 个 `Pair` 分配 + 1 次 state 写；**不触发重组**（组合期无读者） | `[读码]` | 不改（§3.6 说明为什么它是干净的） |
| **7** | 哨兵浮层 `Modifier.offset { }` 在 layout 期读 `listState.layoutInfo` | 每次滚动 | 每次 layout 一次 lambda（可见项 `firstOrNull`，≈10 项），**不重组、不重测量** | `[字节码]` | 不改（§3.5） |

其余查过并**不成立**的嫌疑：主线程 IO、图片解码、`rememberLazyListState` 每帧写锚点、滚动中被反复调用的 `requestScrollToItem`、`derivedStateOf` 键每次发布都变、`filterNot`/`takeLast` 之外的 O(n) 组合期计算（逐条见 §3）。

**一句话**：顿的主要来源是**「新行出现」这件事本身把工作搬到了帧线程上**（第 1 条是设计取舍、第 2 条是漏网的、第 3 条是修复自己的副作用），而不是「列表太大重组太贵」。

---

## 1. 实测数据

### 1.1 同步 markdown 解析（第 1、3 条的代价）

`MarkdownStateImpl.parseBlocking()` = `MarkdownParser.buildMarkdownTreeFromString(content)` + 引用链接查找 + `State.Success` 构造，全在调用线程上（`[字节码]` renderer 0.45.0 `model/MarkdownState.kt` 的 `parseBlocking`；`MarkdownStateKt.rememberMarkdownState` 的 `if (immediate) state.parseBlocking$…()` 分支）。内容取 `piMarkdownSource(markdown)` 之后的正文字符数。

`[实测]`（warmup 40 次、取 51 次的最小值，aarch64/HotSpot；内容为「段落 + 列表 + 围栏代码块 + 行内代码/链接」的混合 markdown）：

| 正文大小 | 纯解析 | `parseBlocking()`（含链接查找 + State 构造） | AST 节点数 |
|---|---|---|---|
| 5.2 KB | 2.12 ms | 3.31 ms | 1021 |
| 20.0 KB | 6.56 ms | 5.27 ms | 3911 |
| 50.0 KB | 9.88 ms | 10.46 ms | 9776 |
| 100 KB | 19.67 ms | 35.42 ms | 19551 |

折算 ≈ **0.2–0.6 ms/KB**（小文档偏高，≥50 KB 趋近 0.2；与 `design/ui-refactor/08-hang-diagnosis.md` §2.2 在另一台机器上的 8.95 ms/5 KB、22.97 ms/50 KB 同量级）。一次工具卡的输出常常是 20–60 KB ⇒ **单行 5–12 ms**，而且 `FRESH_ROW_KEYS_MAX = 200` 意味着一次「加载更早」批次里最多 200 个键都带这个资格。

### 1.1b 每帧预算的准入（第二轮实测，`/tmp/audit/probe/BudgetProbe.kt`）

用**真的** `MarkdownParseBudget`（从 `RowHeightCache.kt` 编译）跑 2 000 帧的滚动模拟：候选行按到达率出现，
代价用 §1.1 的实测 parse 值线性拟合 `1.16 + 0.185 × KB`（**不含**把 AST 组合成 composable 的那一半，所以真实
「转异步比例」只会更高），行高混合 80 % 2 KB 正文 + 10 % 20 KB 卡 + 10 % 50 KB 卡：

| 到达率 | 仍然 `immediate` | 退回异步 | 有行被退回的帧 | 单帧最坏解析花费 | 超 4 ms 的帧 |
|---|---|---|---|---|---|
| 0.45 行/帧（≈27 行/s，正常拖动） | **100 %** | 0 % | 0 % | 10.41 ms | 8.1 % |
| 0.9 行/帧（≈54 行/s，快速 fling） | **100 %** | 0 % | 0 % | 10.41 ms | 17.8 % |
| 2.0 行/帧 | 89.4 % | 10.7 % | 21.3 % | 11.94 ms | 35.8 % |
| 3.5 行/帧（批次连滚） | 69.7 % | 30.3 % | 67.6 % | 13.47 ms | 100 % |

读法：**常见的滚动速度下预算根本不吃到**（0 % 退回）；「超 4 ms 的帧」那一列不是新引入的开销，而是
「一帧只来一张大卡」时那一行本来就有的 10.5 ms（预算只是不再让**第二张**大卡叠上去）。

### 1.2 发布期/扫描期的列表工作（第 2、4 条）

`[实测]`（`ListStateProbe.kt`；`eq` = `List.equals`，`filter` = `visibleItems` 的 `filterNot`，`slice` = `takeLast(50)`，`indexOf` = 锚点纠正，`rhc×10` = 10 个可见行的 `RowHeightCache.of`）：

| 会话形状 | eq | filter | slice | indexOf | search 扫描 | rhc×10 |
|---|---|---|---|---|---|---|
| 500 行（375×2 KB 正文 + 125×20 KB 工具输出） | 0.046 ms | 0.117 ms | 0.006 ms | 0.238 ms | **80.6 ms**（min 32.3） | 0.011 ms |
| 2000 行（1500 + 500） | 0.146 ms | 0.323 ms | 0.006 ms | 0.075 ms | —（探针 60 s 超时，说明随行数×10 MB 线性增长） | 0.011 ms |

结论：**列表算术便宜**（发布一次 <0.5 ms），**搜索扫描贵**（80 ms 级），二者相差两个数量级——这也正是第 2 条排在第 4 条前面的原因。

---

## 2. 我改了什么（我的文件）

### 2.1 `ChatScreen.kt` · `onForkFromMessage` 固定 identity（第 4 条）

**改前**：`itemsIndexed` 的 item lambda 里内联 `onForkFromMessage = { key -> … visibleItems … }`；`visibleItems` 是每次有行变化就被 reducer 换掉的 `List`。

**机制（`[字节码]`+官方文档）**：`LaunchedEffect` 之外，LazyColumn 的可见项为什么会在每次发布重进组合，链路是完整的：

1. `ChatScreen` 每 200 ms 收到新 `UiState`（`PiSessionViewModel._state` 是 `MutableStateFlow`，`syncTranscript` 只在 `changedIndices` 非空时 `toMutableList()` 换新 list，否则复用旧实例）；
2. `ChatBody(state = …)` 的参数 `UiState` 含 `List` ⇒ 不稳定；strong skipping 下不稳定参数按 `===` 比较，新实例 ⇒ 重组；
3. 新 `visibleItems` ⇒ `itemsIndexed(renderedItems, key, contentType) { … }` 的 content lambda 捕获值变了；strong skipping 会把「捕获不稳定的 lambda」用 `remember(捕获)` memoize 且**不稳定键按 `===`**，所以 lambda 换新；
4. 字节码：`LazyListItemProviderKt.rememberLazyListItemProviderLambda` 把 content 包进 `rememberUpdatedState` 再由 `derivedStateOf(referentialEqualityPolicy())` 生产 `intervalContentState`；item 的内容 lambda 在**自己的组合里**先读 `itemProvider()`（`LazyLayoutItemContentFactory$CachedItemContent$createContentLambda$1` 第 3 条指令），因此 provider 一换，**每个已组合的可见项都被 invalidate**；
5. 可见项重组时传给 `BlockRenderer` 的参数里，`item`（同一实例）、`modifier`（`Modifier` 标了 `@Stable`，按 `equals` 比；`CombinedModifier.equals` 逐段 `areEqual`，`SizeElement.equals` 只比 5 个尺寸字段、`OnSizeChangedModifier.equals` 比 lambda 引用——见 §2.3 的 `remember`）都相等；`onBranchClick` 捕获 `session`（同实例）、`onImageClick` 捕获 `viewedImage` 的 state 对象（同实例）也相等；
6. **只有 `onForkFromMessage` 捕获 `visibleItems`，每次发布拿到新 list 实例 ⇒ 新 lambda ⇒ 该参数不等**。它只传给 `UserMessageBlock`（`ui/blocks/BlockRenderer.kt:100`），所以放走的正是**用户消息气泡那一行**的整块重算——「发完消息、回答在流式输出时回看上面自己写的话」这个动作会顿一下。

**改法**：把 lambda 提到 `ChatBody` 顶层，用 `rememberUpdatedState(visibleItems)` 在**调用时**读列表，lambda 本体用 `remember(session)` 固定；调用点改成 `onForkFromMessage = onForkFromMessage`。

**为什么零副作用**：回调仍在运行时读最新列表（`latestVisibleItems.value`），`userMessageOrdinal(items, key)` 的语义（扫全量列表、按 key 找序号）不变；`remember(session)` 的键是同一个 ViewModel 实例，跨会话切换时 `session` 不变——本来这个 lambda 也只捕获 `session` 和列表。

**代价量级**：修后每次发布的「整列表重组」只剩 item lambda 外壳（state 读、`getOrNull`、`HashSet.contains`、`RowHeightCache.of`）≈ 每行 1–3 µs，10 行 <50 µs，加上 0.15–0.5 ms 的列表算术。**这条不是主因，但它是「整列表重组」里唯一真实存在的那部分**。

### 2.2 `ChatScreen.kt` · 搜索扫描移出帧线程（第 2 条）

**改前**：`val searchMatches = remember(searchQuery, prefs.hideThinkingBlock, searchScan) { … }` —— 组合期同步跑一遍全量 `searchHits`；`searchScan` 在 `LaunchedEffect(searchActive, state.streaming)` 里**每 `SEARCH_RESCAN_MS = 200 ms` 加一**，所以流式期间每次发布都在帧线程重扫。

**改法**：
* 新增纯函数 `scanSearchHits(items, query, hideThinking): SearchHits`（与 `searchHits` 并列，`private`）；
* `searchMatches` 变成 `remember { mutableStateOf(SearchHits.None) }`，`LaunchedEffect(searchQuery, prefs.hideThinkingBlock, searchScan)` 里 `withContext(Dispatchers.Default) { scanSearchHits(...) }` 后 `searchMatches.value = hits; searchMatchesSeq++`；
* 结果展示的 effect 由 `LaunchedEffect(searchScan, searchQuery, searchCursor)` 改为 `LaunchedEffect(searchMatchesSeq, searchCursor)`（**在扫描落地时**reveal，而不是在扫描**开始**时——旧写法可能对着上一轮结果 reveal）；
* 读取点普查：item lambda（高亮）、`SearchBar` 的 `matchCount`/上一处/下一处，共 3 处，全部改成 `searchMatches.value…`，同一文件内没有别的读者。

**不变量**：结果集内容完全不变（同一谓词、同一遍历顺序、同一 `hideThinking` 规则）；`searchCursor` 的取整与 `pauseTail()`+`reveal(index)` 的语义不变；`withContext` 返回时会重新检查取消，被取代的扫描不会覆盖更新的结果。**唯一的行为差异是「快一帧」变「慢一帧」**——在 80 ms 的冻结面前这是净胜。

**风险**：扫描现在跑在与 markdown 解析同一个 `Dispatchers.Default` 池上；池宽 = 核数，且备选方案是堵住唯一能画的线程。上机要看的判据见 §4。

### 2.3 `ui/render/TranscriptRowHeight.kt` · 下限改到 placement 期 + 内容落地才解除（第 3、5 条）

**改前的机制（逐帧讲清）**：

1. 行首次组合：`remember(rowKey) { RowHeightCache.shared.of(rowKey) }` 读到旧高度 H，`frames = 0` ⇒ `floor = H`，链上是 `heightIn(min = H) + onSizeChanged { record }`；
2. `[字节码]` `SizeKt.heightIn-VpY3zN4` 造的是 `SizeElement(…, enforceIncoming = true)`，而 `SizeNode.measure` 在 `enforceIncoming` 分支里用**被强制的约束**去量孩子（`ConstraintsKt.constrain-N9IONVI(incoming, target)`）⇒ 内容被以 `minHeight = H` 测量。于是**无论在链的哪一段读**，量到的都是 H：内容自己的高度不可观测，只有帧数能当到期条件；
3. `LaunchedEffect(rowKey) { while (frames < 3) { withFrameNanos {}; frames++ } }`：`frames` 是组合期读到的 state，所以**每加一就 invalidate 一次该行**——3 帧 3 次重组；`floor` 由 `Dp` 值相等 ⇒ `SizeElement` 相等 ⇒ 链不更新、不重量；第 3 帧 `floor` 变 null ⇒ 链结构变化 ⇒ **该行重测量一次**；
4. 如果这一帧 parse 还没落地（`Dispatchers.Default` 上的工作与 vsync 赛跑，`docs/streaming-review.md` §2.4 已经写过这场赛跑），行在**下限消失**后量到 0 ⇒ 塌；随后 parse 落地又长回 H ⇒ **两次几何变化**，正是这个文件想消灭的那个抖。窗口取 3 帧的理由（行合法变矮时别把它撑太久）是对的，但代价不对称。

**改法**：把下限从「测量约束」改成「报告高度的下限」：

```kotlin
Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)      // 原始约束，内容量到它自己的高度
    layout(placeable.width, maxOf(placeable.height, floorPx)) { placeable.placeRelative(0, 0) }
}.onSizeChanged { size ->                                 // 位于 layout 之内 ⇒ 派发给内层 coordinator
    if (size.height > 0) { RowHeightCache.shared.record(rowKey, size.height); settled = true }
}
```

* `[字节码]` 为什么 `onSizeChanged` 拿到的是**内容自己的高度**：`NodeCoordinator.onMeasured()` 把 `this.getMeasuredSize()` 派发给「本 coordinator 段内」的 `LayoutAwareModifierNode`（`headNode(includeSelf)`：非最外层 coordinator 的段头是 `wrappedBy.tail.child`），而 `InnerNodeCoordinator.measure` 在本 LayoutNode 的 measure policy 返回后 `onMeasured()`；`onSizeChanged` 在链上位于我的 `LayoutModifierNode` 之内 ⇒ 属于内层段 ⇒ 拿内层尺寸 = 内容自然高度。
* 到期条件改成**内容量到非零**（一次 latch），`REMEMBERED_ROW_HEIGHT_FRAMES` 从 3 改成 **12**，只作为「内容永远量不出高度」（隐藏的思考块、空文本、parse 进 `State.Error`）的兜底。正常路径下每行**只写一次** state（而不是 3 次），并且**不可能**再出现「下限先消失、内容还没到」的塌陷。
* 返回的 `Modifier` 用 `remember(rowKey, floor, density)` 固定实例（`Modifier.layout` 造的元素按引用比较，不 remember 会每帧换新 ⇒ `BlockRenderer` 的 `modifier` 参数每帧不等，把 §2.1 修好的跳过又毁掉）。

**为什么不破坏既有的四个不变量**：

1. **行高不变**：稳定态下「内容自然高度 ≥ 下限」（下限就是这一行上次量到的高度 H）⇒ `max(自然, H) = 自然 = 今天 heightIn 的结果`。只有内容还没到时才多出 H，而这正是目的。
2. **锚点按行 key**：这次改动不碰 `listState`、不碰 `anchorKey/anchorOffset`、不碰 `hiddenCount`/`renderWindow`，锚点那套（§3.6）完全没动。
3. **首帧不武装**：不涉及 `earlierArmed`/`mayArmEarlier`。
4. **哨兵带子外观一致**：`contentPadding.top = 10.dp + earlierBand` 与浮层 `offset{}` 的公式没动；行高不变 ⇒ 带子高度不变。
   另外，`settled` 让「合法变矮」比今天更快被承认（下一次测量即可，而不是等 3 帧），这是变好的一侧。
5. **搜索高亮的行链不能被吞掉**：改后的函数返回 `this.then(heightModifier)`，`this`（调用方的 `rowModifier`：命中的描边/底色）仍然在外层，只有「下限 + onSizeChanged」这一段被 `remember` 固定；`Modifier.then` 对空 Modifier 直接返回 `this`，所以无命中的行拿到的就是那个固定实例。（这一条是我改完自审时抓到的：第一版把 `this` 吞了，会让搜索高亮消失。）

**残余风险（如实）**：`settled` 只由「内容自然高度 > 0」或 12 帧兜底触发。一个**真的零高度**的行（隐藏思考块被打开时那一行、空文本）如果缓存里有旧高度，最多会被撑 200 ms（今天是 50 ms）。上机要看这一条：切换 `hideThinkingBlock` 后那一行是否在 200 ms 内收掉。要彻底消掉，只能让 `PiMarkdownText` 把库的解析状态报出来（`State.Success`/`Loading`），那要动 `ui/render/PiMarkdown.kt`（别人的文件）——写成 §5.2 的方案，本轮不做。

**改动会影响 pure-check 吗**：不会。`tools/run-app-pure-checks.sh` 的编译单元只有 `ui/chat/TailFollow.kt`（`:493-494`）与 `ui/render/RowHeightCache.kt`（`:671-673`）两个滚动文件，**没有** `TranscriptRowHeight.kt`（它是 Compose 文件，进不了 bare-JVM harness）；这两个文件我一个字没动，H 组 45 条与 tail-follow 127 条不受影响。

---


### 2.4 `ui/render/RowHeightCache.kt` + `ChatScreen.kt` + `PiMarkdown.kt` · eager 解析的**每帧预算**（第 1 条，第二轮）

**改前**：`immediate` 只看「是不是 fresh/restoring + 有没有量过」——一次「加载更早」批次最多 200 个键都带资格，
一帧里滚进 2–3 张大工具卡就叠 2–3 次 5–10 ms 的同步解析。

**改法**（符号级）：

* `RowHeightCache.kt`（纯逻辑，Android/Compose-free，harness 已经编译这个文件）新增
  `internal class MarkdownParseBudget(budgetNanos, periodNanos)` 与 `internal val piMarkdownParseBudget`；
  方法 `allow(nowNanos)` / `chargeNanos(nowNanos, costNanos)` / `availableNanos(nowNanos)` / `reset()`。
  **纯逻辑 + 一个共享实例**，两侧（门槛与计费）看的是同一个对象。
* `ChatScreen.kt` item lambda 的门槛多一项：`… && piMarkdownParseBudget.allow(System.nanoTime())`（放最后，短路）。
* `PiMarkdown.kt` 计费：`val parseStartedNanos = if (immediate) System.nanoTime() else 0L`，包住 `Markdown(state = …)`
  整个调用，之后 `piMarkdownParseBudget.chargeNanos(now, System.nanoTime() - parseStartedNanos)`。

**计价依据（回答「预算怎么定」）**：

* 计的是**实测耗时**，不是字符数：`Markdown(state = …)` 这一次调用里既有 `parseBlocking()`，也有把返回的
  AST 组合成 composable 的成本，两者都是这一帧真实花掉的，所以计时窗口就是它。**没有任何一处按 KB 或字符数
  做门槛或记账**。
* 参数值：`DEFAULT_BUDGET_NANOS = 4_000_000L`（4 ms）、`DEFAULT_PERIOD_NANOS = 16_666_667L`（60 Hz 一帧）。
  依据 = §1.1 的实测表：20 KB 卡 5.27 ms、50 KB 10.46 ms、5.2 KB 3.31 ms；一帧 16.7 ms 里编译器/布局/绘制
  本来就占掉几毫秒，1/4 帧是能给解析的上限。对这张表就是：一张 20 KB 卡（唯一允许的越界）或一张 5 KB 正文
  （两张就不够）或一小把 1 KB 级的小行。
* 两条刻意的性质（都写进 KDoc）：**债务不过帧**（大行把余额夹到 0 而不是负数，越界只让当帧剩下的一行变异步；
  否则一张大卡会让随后好几帧的行全部异步）；**一帧的第一行永远放行**（第一行决定行为的下界不变，只有「一帧里
  挤来一群贵行」才被削）。
* 实测：`/tmp/audit/probe/BudgetProbe.kt` 的 13 条纯逻辑断言全 PASS（含「预算耗尽后 1 µs、半帧都不得回血」
  ——这条在实现里抓到过一个真 bug：连续 refill 会让同帧后续行看到几百纳秒「余额」从而全部放行，改成**整帧
  refill** 后才成立），滚动模拟见 §1.1b。

**零副作用**：门槛只在「本来就有资格」的行上多做一次时间比较；没有资格的行一次都不碰预算；预算被拒的行走的
就是**今天**的异步路径（高度档位/解析信号照旧），既不新增也没有少任何一条渲染路径；`MarkdownParseBudget`
是纯函数对象，harness 可用 (§7 给注册行)。

### 2.5 `ui/render/PiMarkdown.kt` + `PiMarkdownImmediate.kt` + `TranscriptRowHeight.kt` · 下限解除改由库的解析完成信号驱动（第 3 条的下半，第二轮）

**改前**：`TranscriptRowHeight` 只有两个到期信号——「内容量到非零」（晚一次重组）与「12 帧」（跟 `Dispatchers.Default`
赛跑的兜底）。

**改法**（符号级）：

* `PiMarkdown.kt`：不再调库的 `Markdown(content = …)` 重载，改成
  `rememberMarkdownState(content, lookupLinks = true, retainState = true, flavour, parser, referenceLinkHandler, immediate)`
  + `Markdown(markdownState = state, …)`（同一个库的 state 重载；参数名从 renderer 0.45.0 的 Kotlin 元数据读出）。
  这样 **state 在调用方手里**，`val parsedState by markdownState.state.collectAsState()` 就能看到
  `State.Success`；命中时调用 `LocalPiMarkdownParsed.current`（新增的 CompositionLocal）。
* `PiMarkdownImmediate.kt`：新增 `internal val LocalPiMarkdownParsed = compositionLocalOf<(() -> Unit)?> { null }`
  （逐行的回调，与 `LocalPiMarkdownImmediate` 同级、同理）。
* `ChatScreen.kt` item lambda：`val markdownParsed = remember(item.key) { mutableStateOf(false) }` +
  `val onMarkdownParsed = remember(markdownParsed) { { markdownParsed.value = true } }`，在同一个
  `CompositionLocalProvider` 里 `LocalPiMarkdownParsed provides onMarkdownParsed`，并把
  `markdownParsed.value` 传给 `rememberedRowHeight(item.key, contentReady = …)`。
* `TranscriptRowHeight.kt`：`rememberedRowHeight(rowKey, contentReady)`；到期变成
  `settled = contentReady || measured`（`measured` 是非零测量的兜底，`REMEMBERED_ROW_HEIGHT_FRAMES = 12` 只留给
  「内容永远量不出高度」：隐藏的思考块、空文本、`State.Error`）。`LaunchedEffect(rowKey, contentReady)` 在信号到达时
  **立刻取消**那段等待，而不是让它跑完。

**为什么这样就没有「塌陷/长回」**：以前「解除」是一个**代理指标**（帧数或测量），任何一次代理与真实 parse 的
错位都会让下限在下限最需要的时候消失；现在解除条件就是「解析完成」本身，由库自己的状态机报告——谁的 parse
先落地都不影响几何：下限一直罩着，直到内容真的在了。

**零副作用**：`Markdown(state = …)` 与 `Markdown(content = …)` 是同一个库的重载，前者就是后者的实现；
`retainState`/`immediate`/`lookupLinks` 从「Markdown 的默认参数」平移到 `rememberMarkdownState` 的命名参数
（值不变：true / 调用方给的值 / true）。每行多一个 `collectAsState` 订阅（库自己原本也在内部 `collectAsState`）
和一次 `LaunchedEffect`，两者都是每行每文档一次的量级；无 markdown 的行 `onParsed` 回调为 null 且靠
「测量非零」释放，与今天一致。

## 3. 逐条验证（含「不成立」）

### 3.1 发布粒度与整列表重组（嫌疑 1）——**部分成立，量级小；已修唯一的那部分**

* 顶层读了哪些 state：`ChatScreen` 读 `session.state`（`collectAsState`）并在 `state.boot` 上分叉；`ChatBody` 顶层读 `state.transcript`（`ChatScreen.kt:719`，`visibleItems` 的 remember 键）、`state.streaming`、`state.revision`、`state.prefs`、`state.history`、`state.meta.sessionFile/sessionId`、`state.composerFill` 等——**是的，每次发布 `ChatBody` 整体重组**（`UiState` 含 `List` ⇒ 不稳定 ⇒ strong skipping 下按 `===` ⇒ 每个新实例都会重组）。
* 但这不等于「整列表全部重算」：见 §2.1 的 1–6 步；`item`/`modifier`/其余 lambda 都相等，只有 `onForkFromMessage` 破坏跳过；`BlockRenderer` 本身只把 `onForkFromMessage` 交给 `UserMessageBlock`。
* `remember(visibleItems, …)`：键是 `List`，`remember` 的键比较用 `equals` ⇒ **每次发布一次 O(n) 深比较**（实测 0.146 ms / 2000 行），然后 `filterNot` 0.323 ms、`takeLast(50)` 0.006 ms。都不是 16 ms 级。
* `derivedStateOf` 用法：`atTop`/`atBottom`/`canScrollForward` 都是 `remember(listState) { derivedStateOf { … } }`（`ChatScreen.kt:1044-1053`），键是稳定的 `listState`，**不会**每次发布重建；组合期只读这三个派生值（`showUp`/`showDown`），它们只在「到顶/到底」翻转时 invalidate。**这条不成立**。
* `searchMatches`（改前）是 `remember(searchQuery, hideThinking, searchScan)`，键不含 transcript ⇒ **不随发布重扫**——但它被 `searchScan` 每 200 ms 敲一次（§2.2），所以真实成本在别处。

量级：**发布一次 ≈ 0.15–0.5 ms 的列表算术 + 每可见项 µs 级外壳**。结论：机制成立但**不是主因**；已把唯一会让大块重跑的参数（`onForkFromMessage`）固定。

### 3.2 每项组合期成本（嫌疑 2）——**成立但便宜**

* `itemsIndexed(renderedItems, key = { _, item -> item.key }, contentType = { _, item -> item::class })` —— key 与 contentType 都在（`ChatScreen.kt:1909-1912`），`item::class` 是 KClass 单例、不分配。
* `renderedItems = visibleItems.takeLast(renderWindow)` 与 `filterNot` **在组合期**，O(n)，但实测 0.006/0.117–0.323 ms（§1.2）。n=2000 时仍然 <0.4 ms。
* item 内的邻居读取 `visibleItems.getOrNull(hiddenCount + sliceIndex ± 1)`（`:1955-1956`）是 O(1)；`searchMatches.present.contains(index)` 是 `HashSet`（注释 `:1919-1921` 记了从 `List.contains` 换过来的那次修复）。
* 每项的 `earlierRowHeight(earlierText)` 不在 item 里，在列表外层：`remember(text, labelStyle, density, windowWidthPx, measurer)`（`:357-377`）。**注意**：`earlierText` 在窗口满时会带 `hiddenCount`（`加载更早的 N 条`），`hiddenCount` 随**每一条新追加的行**变大 ⇒ 每次发布（流式期间）都可能重跑一次 `TextMeasurer.measure`（短字符串，帧线程）。量级 `[推断]` 0.05–0.2 ms/次，未上机量过；不改（改文案不是我该做的），记在这里备查。

### 3.3 `RowHeightCache` + `TranscriptRowHeight`（嫌疑 3）——**成立，是修复自己的副作用；已改**（机制、字节码、修法见 §2.3）

**第二轮补**：解除条件不再是「帧数/测量」两个代理，而是库的 `State.Success`（§2.5），所以「下限在下限最需要的
时候消失」这条机制本身没了；12 帧只剩「内容永远量不出高度」的兜底。

补充一句关于 `RowHeightCache.shared` 的读写位置：`remember(rowKey) { of(rowKey) }`（组合期）与 `onSizeChanged { record }`（布局期）都在 UI 线程、都在快照之外；访问序 `LinkedHashMap` 的 `get` 会动链表指针，但它**不是 Compose state**，不产生 invalidate、不产生额外布局。`capacity = DEFAULT_CAPACITY = 96`（`RowHeightCache.kt:83`）：可见行 ≈10，96 条够 4 批以上；被淘汰的键只损失「一帧 0 高度」，不会错。**这条也没问题**。

### 3.4 `PiMarkdownText(immediate = …)` 的 eager 同步解析（嫌疑 4）——**成立，且是排序第 1 的机制**

* 门的精确条件（`ChatScreen.kt` item lambda）：
  `immediateMarkdown = !streamingRow && RowHeightCache.shared.of(item.key) == null && (item.key in freshRowKeys || restoring)`；
  `freshRowKeys` 由 `freshRowKeysAfter(freshRowKeys, keys, FRESH_ROW_KEYS_MAX = 200)` 维护，键来自 `renderedItems.size` 增长时 `[hiddenCount, hiddenCount + gained)`；`restoring` 是本次组合的首帧（`withFrameNanos {}` 后置 false）。
* **用户往下滑（看更早的消息）时命中吗？** 命中，而且正是这条路径：`renderWindow += TRANSCRIPT_WINDOW_STEP` 或会话文件读取把窗口**从头部**扩大 ⇒ 新变可见的那些行就是 `freshRowKeys`；它们要被看到，就得**往上**滚进去，于是每滚进一行就在组合期跑一次 `parseBlocking()`。另外 `restoring` 命中「切屏回来」的首帧（此时所有还没量过的可见行一起同步解析）。
* **是在帧线程上同步跑吗？** 是。`[字节码]` renderer 0.45.0 `MarkdownStateKt.rememberMarkdownState`：构造 `MarkdownStateImpl(input)` 之后立刻 `if (immediate) state.parseBlocking$com_mikepenz_multiplatform_markdown_renderer()`；`parseBlocking` 体内第一句就是 `parser.buildMarkdownTreeFromString(input.content)`，没有任何 `withContext`/`runBlocking` 之外的分派——组件的 `Markdown(…, immediate = true)` 就在组合里调用它。
* 代价量级：§1.1，20 KB ⇒ 5 ms，50 KB ⇒ 10 ms。
* 为什么当初**不改**它：它是 D51 用来换「新行首帧就是终高」的取舍（`/tmp/probe/scroll/D51-addendum.md` ⑤），关掉它会把 P3（往上滚内容整体下移一行）换回来。**第二轮按「每帧预算」做了**（§2.4）：资格判定不变，新增的只是「这一帧已经花掉的同步解析时间」这一条，计费用实测耗时；常见滚动速度下 §1.1b 的模拟显示 **100 % 的行仍然 `immediate`**，只有一帧里挤进 2+ 张贵行时才把后面的行退回异步（2 行/帧时约 11 %，3.5 行/帧时约 30 %）。

### 3.5 哨兵行移出列表后的浮层（嫌疑 5）——**不成立**

* `Modifier.offset { … }` 的 lambda 在**布局期**跑：`[字节码]` `OffsetPxNode.measure` 里读 `listState.layoutInfo` 的部分在 `layout(...)` 的 placement 块（`OffsetPxNode$measure$1`）里，不是在组合里；它读的 `layoutInfo` 是 measure 写、placement 读，形成「每次 measure 之后 placement 重跑一次」的依赖，**没有写回、不会成环、不产生重组**。
* 「每帧触发重算」的一半要看 lambda 的 identity：`OffsetPxNode.update` 只在 lambda 不等时 `invalidatePlacement`；这个 lambda 捕获 `bandPx`（值）与 `listState`（同实例），strong skipping 会 memoize ⇒ identity 稳定 ⇒ **不会**每次重组都 invalidation。每次 layout 的成本 = 可见项列表（≈10 项）的 `firstOrNull`，微秒级。
* `contentPadding.top = 10.dp + earlierBand`：`earlierBand` 只在 `showsEarlierRow` 翻转或 `earlierRowHeightValue` 变化时变（后者 = `earlierText` 变化，即批次数/是否 loading 变化），**不是每帧**；变化时列表本来就要重测量（批次边界），没有额外代价。
* 与 `showsEarlierRow` 的联动：`showsEarlierRow = hiddenCount > 0 || history?.hasEarlier == true`（`:780`），`earlierBand`/浮层都是它的纯函数；没有多余重组。

### 3.6 锚点跟踪器（嫌疑 6）——**写频率高，但不触发重组**

* `LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { … } }`（`:1156-1172`）：拖动期间**每个滚动帧**都会发一次（offset 变），每次：恢复协程 + 分配一个 `Pair` + `anchorWindow.getOrNull(index)?.key` + 比较 `anchorKey`/`anchorOffset`，变了才写。
* 写的是 `rememberSaveable` 的 `mutableStateOf`/`mutableIntStateOf`（`:1099-1100`）。**组合期没有任何读者**——`anchorKey` 只被两个 `LaunchedEffect` 体内和本 effect 读，`anchorOffset` 只在 effect 里读；`saveable` 的注册表只在 `onSaveInstanceState` 时读值。所以这两次写**不会**重组、不会重测量，只走一次 snapshot apply + 必要的装箱（`mutableIntStateOf` 不装箱）。
* `firstEmission` 与 `anchorSettled` 的门都是对的（首帧不记录被恢复出来的、已经漂移的位置）。
* 结论：**不成立**（不是顿的来源）。唯一可省的是每帧一个 `Pair`，代价是几十纳秒级，不值得改。
* 另一半：锚点纠正 `LaunchedEffect(hiddenCount, visibleItems.size)`（`:1119-1152`）里的 `visibleItems.indexOfFirst { it.key == key }` 是 O(n)，实测 0.075–0.238 ms；它在「每次追加一行」时都会重跑（`visibleItems.size` 变），并且在真的漂移时调一次 `requestScrollToItem`（⇒ 一次列表重测量）。量级亚毫秒，保留。

### 3.7 其它滚动相关（嫌疑 7）——**逐条不成立**

| 检查项 | 结论 | 证据 |
|---|---|---|
| `LazyListState` 的 `remember` 位置跨屏保持 / 每帧写锚点 | 不成立：`rememberLazyListState` 内部是 `rememberSaveable(saver = LazyListState.Saver)`，`Saver` 是 `listSaver`，只在保存时读 `firstVisibleItemIndex`/`firstVisibleItemScrollOffset`（`[字节码]` `LazyListState$Companion.saver` → `ListSaverKt.listSaver`），保存由 `onSaveInstanceState` 驱动，与帧无关 | `[字节码]` |
| `requestScrollToItem`/`animateScrollToItem` 在滚动中被反复调用 | 不成立：唯一的滚动期调用点是 follow effect（`:1013-1015`，`pin != null && !listState.isScrollInProgress`）与锚点纠正（`:1147`，`index != firstVisibleItemIndex` 且前面已挡 `isScrollInProgress/following/pendingJump`）、以及 `pendingJump` 消费（`:1306`，跳转路径）。`animateScrollToItem` 在本文件**没有**调用 | `[读码]` |
| 主线程 IO / 解析 | 不成立：`expandEarlierHistory()` 的会话文件读取在 `withContext(Dispatchers.IO)`（`PiSessionViewModel.kt:3110-3129`）；导出/附件复制走 `rememberCoroutineScope()` + 各自的分派；滚动路径本身没有文件/网络 IO | `[读码]` |
| 图片解码在组合期 | 不成立：`ImageGridBlock` 是 `produceState` + `withContext(Dispatchers.IO)` + `piImageDecodeGate` 并发闸门（`ui/blocks/ImageGridBlock.kt:237-247`）；唯一的组合期调用是 `remember(image.base64) { naturalImageAspect(...) }`（`:215`，每 payload 一次、只读头部） | `[读码]` |
| list/state 层的其它 O(n) 组合期计算 | `userRowIndices()` 改成按需（注释 `:805-809`）；`userMessageOrdinal` 只在 fork 回调里跑；`filterNot`/`takeLast` 见 §3.2 | `[读码]` |

---

## 4. 真机判据（本机没有设备 shell，命令原样留给能上机的人）

前置：`PKG=app.pi`（以实际 applicationId 为准）；开启「开发者选项 → GPU 呈现模式分析」不必要，下面用 `gfxinfo` + Perfetto。**每项测前 `reset`，测后读一次**。

**A. 基线/回归总览**

```bash
adb shell dumpsys gfxinfo $PKG reset
# 做一段固定动作（下面 B/C/D 各一次），然后：
adb shell dumpsys gfxinfo $PKG | sed -n '/Total frames rendered/,/90th/p'
# 逐帧分布（最有用的一行是 95th/99th 与 Janky frames 百分比）：
adb shell dumpsys gfxinfo $PKG framestats | sed -n '/Janky frames/,+8p'
```

**B. 搜索框开着 + 正在流式输出时滑动（验 §2.2）**

1. 打开一个有 500+ 行、含大块工具输出的会话，点搜索图标，输入一个会命中工具输出的词（如 `find`）；
2. 发一条会长时间流式的消息，然后在流式期间**连续上下滑动 5 秒**；
3. 判据：修前 `Janky frames` 会占大头（扫描每次 80 ms 级，每 200 ms 一次），修后应回到个位数百分比；Perfetto 里 `Choreographer#doFrame` 不应再出现 80 ms 级的 `Recomposer:recompose` 块。

**C. 往上滚进「加载更早」批次（验 §2.3 与第 1 条）**

1. 长会话（>200 行），把视口停在窗口最顶，反复上滑触发 3–5 批「加载更早」；
2. 判据（对应 `docs/scroll-diagnosis.md` 的 U1/U2）：**松手后 2–3 帧内，正在读的那一行不得整体上移**；行不得出现「先塌成 0 高再长回来」的两次高度变化（修前会在 parse 输掉 3 帧赛跑时出现）；逐帧看可以用：

```bash
adb shell atrace -t 8 gfx view wm -b 16000 > /data/local/tmp/scroll.trace
# 拉回本机用 Perfetto UI 打开，看 Layout 段里同一行是否在相邻两帧各变一次高度
adb pull /data/local/tmp/scroll.trace .
```

**D. 切屏回来（验 `restoring` 首帧的 eager 解析与下限）**

切到「工作区」再切回，判据：回来的第一帧不得出现整列空白行（下限 + `immediate` 的既有目标），也不得出现整体下移；`gfxinfo` 的该帧可以长（这是第 1 条的既有代价）。

**E. 隐藏思考块（验 §2.3 的残余风险）**

打开/关闭「隐藏思考块」偏好，判据：那一行必须在 **200 ms（12 帧）内**收掉，不能撑住不动。

**E2. 每帧预算（验 §2.4）**：在一个含大工具卡的会话里，快速连滚触发 2–3 批「加载更早」，同时录
`atrace gfx view`/`Choreographer#doFrame`。
判据：**任何一帧里同步解析（帧线程上的 `buildMarkdownTreeFromString`/`MarkdownSuccess`）的总时长不得超过
「4 ms + 当帧最坏一行」**；超预算那一帧里，被退回异步的行只允许「从上方进入」时出现一次高度增长
（即「长一下」），且**不得**在下一帧再变一次。`0.45–0.9 行/帧`（正常拖动/fling）下不应该看到任何「长一下」。

**E3. 什么情况下**还会**看到「长一下」**（如实）：只有当**一帧里同时挤进多张贵行**、且后面的行是从视口**上方**
进入时——那一行会走异步，于是先 0 高、parse 落地后长到终高（它下面的内容被推开一次）。具体是三件同时成立：
① 该行本身没有缓存高度（刚被批次带进来）；② 同一帧已经有行花掉了 4 ms（一张 20 KB 卡就够）；
③ 它从上方进入（往下滑时同样的增长发生在屏外，看不见）。**它仍是「有界」的**：只影响那一行，且只影响一帧；
而预算是 `4 ms + 一行`，所以**不存在「一帧里叠三张大卡」那种 20–30 ms 的停帧**了。

**F. 空闲态对照**

不动手时 `dumpsys gfxinfo` 的 95th 不应因这次改动变差（`settled` 每行只写一次；搜索 effect 不再同步跑）。

---

## 5. 交给别人的 patch（我没碰别人的文件）

### 5.1 ~~eager 解析按字符数封顶~~ —— **已否决并改为每帧预算**（§2.4）

保留这一小节只为记录取舍：字符数一刀切会让「大卡」永远走异步（承担一帧的几何跳），而它并不比预算便宜——
按 §1.1b 的模拟，预算在常见滚动速度下 100 % 放行，只有一帧挤进 2+ 张贵行时才削。原文如下（**不要再落**）：

`LocalPiMarkdownImmediate` 现在的资格只看「是不是 fresh/restoring + 有没有量过」。第 1 条的量级说明：20 KB 以上同步解析一次就是 5–12 ms，而这类行正是「工具卡」。折中方案（**不做也可以，这是观感取舍**）：

```kotlin
// item lambda 内
val eagerCandidate = !streamingRow &&
    RowHeightCache.shared.of(item.key) == null &&
    (item.key in freshRowKeys || restoring)
// 只有便宜的行才允许把解析搬到帧线程；大行退回异步（承担一帧的几何跳）。
val immediateMarkdown = eagerCandidate && markdownCharCountOf(item) <= IMMEDIATE_PARSE_MAX_CHARS
```

`IMMEDIATE_PARSE_MAX_CHARS` 取 `8_000`（≈1–1.6 ms/行），并需要一个按 `TranscriptItem` 取正文字符数的纯函数（`AssistantText.text.length`、`ToolCall.output.length`、…）。取舍写清：小行得到「首帧终高」，大行回到「先 0 高再长高」——**这正是用户说的「工具卡出现时顿一下」的另一半**，所以是否愿意用一帧几何跳换掉 5–12 ms 的停帧，需要观感决策。我没有落这条。

### 5.2 ~~`ui/render/PiMarkdown.kt` 把库的解析状态报出来~~ —— **第二轮已实现**（§2.5）

原文（作为实现说明保留）：

`MarkdownState` 公开 `state: StateFlow<State>`，`State.Success/Loading/Error` 可判。若 `PiMarkdownText` 把「已 Success」通过一个 `CompositionLocal`（或回调）报给行，`TranscriptRowHeight` 就能在**内容真的就绪**那一刻解除下限、`ChatScreen` 也就能只在**大行**上放弃同步解析——§2.3 的 12 帧兜底与 §5.1 的字符阈值都能消失。代价：新增一个与 `LocalPiMarkdownImmediate` 同级的 local，`PiMarkdown.kt` 多一处 `collectAsState`。属别人的文件，我只给方案。

### 5.3 台账（不是我的文件，文本见 §8）

`design/ui-refactor/07-construction-decisions.md` 的 D51 ④/⑤ 需要按 §2.3 更新：⑤ 的「按帧数到期」改成「按内容就绪到期 + 12 帧兜底」，并把 §1.1 的实测数字补进去。我没有这份台账的写权。

---

## 6. 未确认 / 需要上机或额外证据的部分（如实）

1. **`[推断]` 的部分**：第 3 条「输掉赛跑会先塌再长」的**可见性**是推断——机制（`heightIn` 的 `enforceIncoming`、3 帧无条件解除、链变化触发重测量）都是字节码级已证，但「parse 是否真的会超过 3 帧」要在真机上量（判据 C）。改后的实现不再依赖帧数，所以这条推断即使偏保守也不会造成回归。
2. **`[推断]` `onSizeChanged` 拿的是内层（内容）尺寸**：由 `NodeCoordinator.onMeasured`/`headNode`、`InnerNodeCoordinator.measure`、`SizeNode.measure` 的字节码推导（§2.3）。若这条不成立（即它拿的是被 `layout` 下限撑起来的外层尺寸），latch 会在第一帧就触发、下限立刻消失——判据 C/D 会立刻看见「行先 0 高」的回归。这是本轮**最该优先上机确认**的一条。
3. **HotSpot vs ART**：§1 的数字来自 aarch64 容器里的 HotSpot 21；ART 上 markdown 解析与 `String.contains(ignoreCase)` 的常数不同，按量级读；上机判据里给了直接测帧的路径。
4. **strong skipping**：默认开启来自官方 flag 文档（Kotlin 2.5 起该 flag 弃用待删）与 design doc（不稳定参数 `===`、lambda 捕获 memoize）。本机**没有** Compose 编译器插件 jar（`typecheck.sh` 明确不跑它），所以「这个 composable 到底是否 skippable」没有本机字节码证据——要 `javap` **构建产物**里 `app/pi/ui/blocks/BlockRendererKt.class` 的 `$composer.changed`/`changedInstance` 序列才能钉死。
5. **`earlierRowHeight` 每次 `hiddenCount` 变化重跑一次 `TextMeasurer.measure`**（§3.2）：量级是按文本测量推的，未实测；判据：Perfetto 里流式期间的 `TextMeasurer`/`Paragraph` 段。
6. **§2.2 的 off-thread 扫描与 markdown 解析共用 `Dispatchers.Default`**：**未实测的相互影响**（委派方第 4 条）。
   已知的定性边界：`Dispatchers.Default` 的并行度是 `max(2, 核数)`（`kotlinx.coroutines`，本机/目标机 8 核 ⇒ 8 条），
   一次扫描只占其中一条，其余线程仍可跑 markdown 解析，所以「饿死」要同时满足「扫描连续排队 + 池被占满」，而不是
   一次 80 ms 扫描就会发生。上机怎么看：`atrace -t 8 gfx view sched` 或 Perfetto 里抓 `DefaultDispatcher-worker-*`
   的 CPU 时间轴，看（a）两个任务是否在同一时间窗内争同一条线程、（b）`Choreographer#doFrame` 是否**等待**它们
   （即帧线程上出现 `DefaultDispatcher` 相关的长 slice），(c) 解析落地时间是否被推后到下一帧（表现就是 E3 的
   「长一下」变多）。若确实互相影响，第一选择是把扫描降到 `Dispatchers.IO`（同一实现里 `withContext` 的实参一处），
   而不是把扫描搬回帧线程。
7. **预算的计价是「含渲染的一半」**：§1.1b 的模拟用 §1.1 的 `parseBlocking` 值（不含把 AST 组合成 composable），
   所以真实「转异步比例」只会比表里的更高；§1.1 的表哪一半占多少，本机只能给 parse 那一半（0.2–0.6 ms/KB），
   渲染那一半要上机用 E2 的 trace 量。

---

## 7. 改动的落地状态与验收

* `tools/typecheck.sh`（全量，两轮各跑一次）

  **第一轮**（§2.1–§2.3 落盘后，2026-09-18 19:0x）：`typecheck: FAILED in :app — 3 error diagnostic(s)`，
  全部是 `ui/settings/DiagnosticsReport.kt` 的 `BuildConfig` 假阳性。

  **第二轮**（§2.4/§2.5 落盘后，2026-09-19 13:1x）：

  ```
  app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:7:15: error: unresolved reference 'BuildConfig'.
  app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:156:34: error: unresolved reference 'BuildConfig'.
  app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:156:75: error: unresolved reference 'BuildConfig'.
  app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt:856:28: error: argument type mismatch: actual type is 'String', but 'JsonElement?' was expected.
  typecheck: FAILED in :app — 4 error diagnostic(s)
  ```

  **我的 5 个文件一条诊断都没有**（这一点是这次运行最重要的结论：编译器到了 `:app` 最后，列出的错误全在
  `ui/settings/**`）——所以第二轮那些最可能写错的地方（`rememberMarkdownState` 的 7 个命名参数、
  `Markdown(markdownState = …)` 重载、`State.Success`、`collectAsState`、`rememberedRowHeight` 的新参数、
  `piMarkdownParseBudget`）**都通过了编译器**。

  第 4 条不是我的：`ui/settings/PiSettingsRegistry.kt` 的 mtime 是 **2026-09-19 13:04:52**（就在这次 typecheck
  运行期间），是另一个代理在飞的改动；它是 `ui/settings/**`（本轮边界之外），我也从未碰过。第一轮那次是 3 条。

  **第三轮**（§9 的缩略图 patch 落盘后，2026-09-19 稍晚）：`:app` 报了 **15 条**，**没有一条在我的文件里**——
  12 条在另一个代理正在改的 `app/pi/packages/PiPackagesScreen.kt`（`WsBadge`/`StateTone`/`PiSettingsCollapseAbove`
  等未定义引用，典型的改到一半的中间态），3 条是那 3 个已知 `BuildConfig` 假阳性。这一轮同时验证了 §9：
  `PiImageCache.readThrough` 在 `withPermit`/`withContext(Dispatchers.IO)` 里可编译（否则会在 `ChatScreen.kt`
  报 unresolved reference 或 suspend 相关错误）。

* `python3 tools/check-nested-comments.py` → 第一轮 271、第二轮 274、第三轮 **280** 个文件全部 OK（文件数含其它
  代理这一轮新建的）。
* `MarkdownParseBudget` 的纯逻辑：`/tmp/audit/probe/BudgetProbe.kt`（用**真的** `RowHeightCache.kt` 编译）
  → `harness: OK (all checks passed)`，13 条断言即 §8.6 的 I1–I13；同一支探针跑 2 000 帧滚动模拟（§1.1b）。
* 未跑 Gradle 编译、未提交、未推送、未派子代理；我改的文件是 `ChatScreen.kt`、`ui/render/TranscriptRowHeight.kt`、
  `ui/render/RowHeightCache.kt`、`ui/render/PiMarkdownImmediate.kt`、`ui/render/PiMarkdown.kt`（第二轮委派方授权）与
  本报告；`ui/blocks/**`、`ui/chat/**`、`ui/settings/**`、`settings/**`、`runtime/**`、`engine/**`、脚本、台账：
  **一个字节没动**（`git diff --stat` 里这几处只有别的代理的改动）。

---

## 8. 交给委派方落盘的台账文本（D51 ③/④/⑤ 补记；`design/ui-refactor/07-construction-decisions.md` 由你落）

### 8.1 D51 ③ 的最后一段**替换**（「头三帧 `heightIn`，之后放手」→ 新的到期机制）

原文（旧）：
> `Modifier.rememberedRowHeight(rowKey)` —— 一行**重新组合**的头三帧用「上次实测高度」当 `heightIn(min=…)`，
> 之后放手（所以展开/折叠全部工具卡、改字号这类**合法变矮**最多错 3 帧 ≈ 50 ms，而不会卡住）。

替换为：
> `Modifier.rememberedRowHeight(rowKey, contentReady)` —— 一行**重新组合**后，在被测到真实高度之前，用「上次实测
> 高度」当**放置期下限**（`Modifier.layout { max(自然高度, 下限) }`，**不是** `heightIn(min=…)`：`heightIn` 造的是
> `enforceIncoming = true` 的 `SizeElement`，会把下限压进**测量约束**，内容自身的高度就不可观测了，
> 于是「到没到期」只能靠帧数猜 —— `TranscriptRowHeight.kt` 的 KDoc 有字节码依据）。下限的解除有三个信号，
> 按正常到达顺序：① **`contentReady`** —— 库自己的 `State.Success`，由 `PiMarkdownText` 经
> `LocalPiMarkdownParsed` 报上来（`PiMarkdownImmediate.kt`），**这是机制**；② **内容量到非零高度** —— 没有 markdown
> 的行（notice / 日期分隔 / 图片网格）走这条，它们从不发信号；③ `REMEMBERED_ROW_HEIGHT_FRAMES = 12`（200 ms）
> 只兜「内容永远量不出高度」：隐藏的思考块、空文本、parse 进 `State.Error`。
> 于是「展开/折叠全部工具卡、改字号」这类**合法变矮**在下一次测量就被承认（比 3 帧更快），而
> **「下限先消失、内容还没到」的塌陷/长回在原理上不再存在**（旧实现是 3 帧无条件到期、跟 `Dispatchers.Default`
> 赛跑，输了就是「先塌到 0 再长回来」的两次几何变化）。代价：一行重新组合只多一次重组（解除下限那次写）与一次
> 重测量（链丢掉下限，而尺寸已被内容自己的测量证明相等）。
> 同一张图那条老判据（D48「同一张图从第一次进入视口到最终渲染，行高不允许变化」）仍然成立并被推广：
> **同一个 key 的行，重新组合后第一帧就必须是它离开时的高度。**

### 8.2 D51 ④：结论不变，加一句

④（`requestScrollToItem` 那条账 / 哨兵行移出 `LazyColumn`）**结论与算术都没变**：诊断 §3.4 的建议是错的、
删掉补偿是倒退、唯一解法是把哨兵行移出列表。这一轮的两处改动（放置期下限、解析完成信号）不碰锚点、不碰
`pendingJump`、不碰 `firstOfRun/lastOfRun` 的邻行读取，所以 ④ 原样有效。补一句：**④ 的收益与 ⑤ 无关**——
即使把 ⑤ 整段回退，④ 仍要保留。

### 8.3 D51 ⑤ **替换**（「批次 + 一次」→「每帧预算 + 解析完成信号」）

> ### ⑤ markdown 的**首帧**：`immediate = true`，按「**每帧 4 ms 预算**」封顶；行高下限由**解析完成信号**解除
>
> ③ 的高度下限只救**量过再重组**的行；**从没量过的行**——也就是「加载更早」刚取回来、用户正要往上滚进去的那些
> ——没有高度可恢复，第一帧仍是 `State.Loading` 的空 `Box`（0 高）。而它偏偏是从**视口上方**进来的：它长到真实
> 高度时把整屏正在读的文字往下推了它自己的高度。库自己给了「首帧就解析完」这一档：`Markdown(…, immediate = true)`
> 在 `rememberMarkdownState` 里 `if (immediate) state.parseBlocking()`（renderer 0.45.0 字节码），第一帧就是终高。
>
> **代价怎么封顶（这一条的全部难点）。** `immediate` 是逐调用的；对所有行打开就等于把 markdown 解析搬回帧线程。
> 所以 `ChatScreen` 只在三种情况下提供 `LocalPiMarkdownImmediate = true`：窗口刚从头部吃进来的行
> （`renderedItems.size` 增长时 `[hiddenCount, hiddenCount + gained)`）、恢复态首帧、且**永远不包括正在流式的行**。
> 第二轮再加第四道闸：**每帧预算**，并按实测耗时计费。
>
> * `MarkdownParseBudget`（`ui/render/RowHeightCache.kt`，纯逻辑、Android/Compose-free，已进 harness 的编译单元）：
>   **一帧 4 ms**（`DEFAULT_BUDGET_NANOS = 4_000_000L`，`DEFAULT_PERIOD_NANOS = 16_666_667L` 即 60 Hz 一帧）；
>   **按整帧回补**（不是连续回补 —— 连续回补会让预算耗尽后 1 µs 的调用又看到几百纳秒「余额」，同一帧剩下的行
>   就会全部放行；实现时探针抓到了这个 bug）；**债务不过帧**（大行把余额夹到 0 而不是负数，越界只让当帧剩下的
>   行变异步）；**一帧的第一行永远放行**。
> * **计费按实测耗时**：`PiMarkdown.kt` 用 `System.nanoTime()` 包住 `Markdown(state = …)` 的整次调用（里面既有
>   `parseBlocking()`，也有把 AST 组合成 composable 的那一半），没有任何一处按字符数或 KB 做门槛或记账。
> * **依据**（本机 bare-JVM，真库 `parseBlocking`）：5.2 KB → 3.31 ms、20 KB → 5.27 ms、50 KB → 10.46 ms
>   （0.2–0.6 ms/KB）。于是「一帧的同步解析总量」上界是 **4 ms + 至多一行**（最坏单行就是 50 KB 的 10.46 ms），
>   而不是「一帧里叠几张卡」。常见滚动速度下预算根本不吃到：`/tmp/audit/probe/BudgetProbe.kt` 的 2 000 帧模拟里，
>   0.45–0.9 行/帧（≈27–54 行/s）时 **100 % 的行仍然 `immediate`**；2 行/帧时 89 %、3.5 行/帧时 70 %。
> * **还没解决的（写清楚）**：一帧里同时挤进多张贵行时，后面的行退回异步 ⇒ 如果它从视口**上方**进入，仍会看到它
>   「先 0 高、落地后长到终高」的一次增长（有界：只那一行、只那一帧）。省掉它的唯一办法是在解析之前就知道一行
>   要花多久，而库没有暴露 AST 大小；按字符数一刀切是**猜**，已否决。
> * **下限的解除**：不再「头三帧之后放手」，而是由库的 `State.Success` 解除（`PiMarkdownText` 把解析完成报给行：
>   `LocalPiMarkdownParsed` + `rememberedRowHeight(rowKey, contentReady)`），非零测量与 12 帧只作兜底（见 ③）。
>   这也是「塌陷 / 长回」消失的原因：解除条件就是「解析完成」本身。
>
> **有界性**：一行最多一次同步解析（`RowHeightCache` 当闩锁，量过的行不再问）；一帧最多花「预算 + 一行」在同步
> 解析上；键集合由纯函数 `freshRowKeysAfter(previous, added, max)` 维护，`max = FRESH_ROW_KEYS_MAX = 200`，
> 不随会话增长。harness：tail-follow 的 H 组（键集合）+ 预算组（注册行见 §8.6）。

### 8.4 判据增量（接在 D51「判据」里）

* **U4**（⑥ 的专属，原样保留）：按在「加载更早」上往下拖 —— 列表滚动、行不触发点击；松手后点一下 —— 与今天同样的行为。
* **U5**（⑤·每帧预算）：快速连滚 2–3 批「加载更早」，同时录 `atrace gfx view`；**任何一帧里帧线程上的同步解析
  总时长不得超过「4 ms + 当帧最坏一行」**；在 0.45–0.9 行/帧（正常拖动/fling）的速度下不应出现任何「行先 0 高
  再长高」。
* **U6**（⑤·解析完成信号）：切走再切回（S1）、以及往上滚进批次时，**不得**出现「同一行在相邻两帧各变一次高度」；
  隐藏思考块被打开时，那一行必须在 **12 帧（200 ms）内**收掉，不能撑住。

### 8.5 影响行增量

⑤ 第二轮的影响：`ui/render/RowHeightCache.kt`（+`MarkdownParseBudget`、`piMarkdownParseBudget`）、
`ui/render/PiMarkdown.kt`（`rememberMarkdownState` + `Markdown(state = …)` + 计费）、
`ui/render/PiMarkdownImmediate.kt`（+`LocalPiMarkdownParsed`）、`ui/render/TranscriptRowHeight.kt`（放置期下限 +
`contentReady`）、`ui/screens/ChatScreen.kt`（门槛 + 提供回调 + 传递 `contentReady`）。**颜色/主题一个字节没动**；
`ui/blocks/**`、`ui/chat/**`、`ui/settings/**`、`settings/**`、`runtime/**`、`engine/**`、
`tools/run-app-pure-checks.sh`、本台账：一行没碰。

### 8.6 harness 注册行（要把预算组落地时用）

`tools/run-app-pure-checks.sh` 里 `RowHeightCache` 那一节（H 组，`:671-673`）**不用改**：`RowHeightCache.kt`
已在该编译单元里，`MarkdownParseBudget` 会跟着一起编译。要加断言，在
`app/src/test/kotlin/app/pi/ui/render/RowHeightCacheCheck.kt` 里追加一组（建议组名 `I`，13 条，名字逐条对应
`/tmp/audit/probe/BudgetProbe.kt` 的输出，那个探针目前 `harness: OK (all checks passed)`）：

```
I1  新预算放行
I2  4 ms 预算里花掉 1 ms 后剩 3 ms
I3  还剩 3 ms 时仍然放行
I4  花掉 5 ms 后余额夹在 0（不产生负债务）
I5  余额为 0 时拒绝
I6  1 µs 之后仍然拒绝（整帧回补：不允许亚帧回血）
I7  半帧之后仍然拒绝
I8  整帧之后回补到满额
I9  整帧之后重新放行
I10 静默 10 帧也只回补到一帧的量（上限）
I11 正好花光后拒绝
I12 负代价不能凭空造出预算
I13 一帧的第一行永远放行（哪怕上一帧刚花掉 20 ms）
```

（按本轮边界，唯一允许新建的文件是本报告，所以我没有新建 `...Check.kt`；这 13 条断言已经由探针在 bare JVM 上
跑通。）

---

## 9. 追加：附件缩略图接进 `PiImageCache`（别人交来的 patch，落在我的文件里）

**改动**：`ChatScreen.kt` 的 `AttachmentThumb`（`produceState` → `withContext(Dispatchers.IO)` 之内）：

```kotlin
piImageDecodeGate.withPermit {
    PiImageCache.readThrough(image.base64, thumbPx, thumbPx) {
        decodePiImage(image.base64, thumbPx, thumbPx)
    }
}
```

（`import app.pi.ui.blocks.PiImageCache` 一并加上；`PiImageCache` 是 `internal object`，同模块可见。）

**四条要求逐条核对**：

1. **仍在 `piImageDecodeGate.withPermit` 之内** —— 是；`readThrough` 整个调用（含命中时的查表）都在许可内，
   所以闸门语义（最多 `MAX_CONCURRENT_IMAGE_DECODES` 个并发）一字不改；命中只多占一次许可、立刻释放。
2. **仍在 `Dispatchers.IO`** —— 是；`withContext(Dispatchers.IO)` 是最外层，`readThrough` 的 KDoc 也要求调用方
   在后台调度器上（等值 payload 的比较是 MB 级 `memcmp`、未命中是整次解码，都不属于帧线程）。
3. **源文本计数** —— `ChatScreen.kt` 里 `decodePiImage(image.base64` = **1**、`piImageDecodeGate.withPermit` = **1**
   （改后实测），`PiImageCache.readThrough` = 1。**更正一处前提**：`ImageSizeCheck` 的 `wiringChecks` 实际上
   **不读 `ChatScreen.kt`**（它的两份文件是 `ImageGridBlock.kt` 与 `PiImageViewer.kt`，注释里写明「ChatScreen.kt's
   composer preview is deliberately not in the list」——`app/src/test/.../ImageSizeCheck.kt:515-544`）。也就是说
   ③ 的断言本来就不覆盖这个文件；我仍然保持了它的计数不变，并**手工跑了那支 harness**：
   `image-size` → `harness: OK (all checks passed)`（26 条，含「every payload decode in ImageGridBlock.kt /
   PiImageViewer.kt is under the shared gate」两条源文本断言，两者当前都是 1/1 且都已走 `readThrough`）。
4. **与手上的改动冲突？** 没有。这条落在 `AttachmentThumb`（输入框附件区），我这一轮/上一轮的改动在 `ChatBody` 的
   `itemsIndexed` item lambda、搜索扫描 `LaunchedEffect`、`onForkFromMessage` 与渲染层的三个文件，互不重叠；
   `ChatScreen.kt` 内没有第二处 `decodePiImage`/闸门调用，导入也没有重名。

**代价/收益与边界**：缓存键 = payload + 盒子（`PiImageCache.Key`），所以「同一张图换一个缩略图尺寸」是两条目；
只缓存**成功的解码**（`decode()` 返回 null 不写），一次内存不足造成的失败不会被固化。风险与既有 cache 路径同源，
且这个对象已被 `ImageGridBlock`/`PiImageViewer` 用在同一形状上（两者的 1/1 计数与 `readThrough` 都已在树里）。
**上机判据**：装两张图、发出去再撤回/重进输入框，第二次进入同一张图不应再有整次 codec 解码（`atrace` 里
`BitmapFactory`/`nativeDecode` 段只在第一次出现）；连续切走再切回若干次，`dumpsys meminfo` 的 bitmap 占用应停在
32 MiB 界内而不是随次数增长。

---

## 10. 追加：附件候选序对齐方案 (a)（`AttachmentBudget` + `ChatScreen` + `AttachmentBudgetCheck`；与滚动无关，为可追溯记在这里）

**用户拍板**：截图/PNG 源走无损，相机 JPEG 不多付一次 PNG 编码。即 PNG 候选的进入条件从「只在带 alpha 时」
放宽为「**源 MIME 是 `image/png`（或带 alpha）**」，其余与 pi 的候选序/质量阶梯一致
（`image-resize-core.ts:112-122`：「第一个塞得下的候选」语义不变）。

**符号级改动**

| 文件 | 改动 |
|---|---|
| `ui/screens/AttachmentBudget.kt` | 新增 `fun pngFirst(sourceMime: String, hasAlpha: Boolean): Boolean`（= `hasAlpha \|\| baseMimeType(sourceMime) == "image/png"`）；`fun encodings(sourceMime, hasAlpha)`（原 `encodings(hasAlpha)`）；`fun attemptPlan(sourceMime, hasAlpha, width, height)`（原 3 参）；抽出 `private fun baseMimeType(mime)`（`piInlineSupported` 与 `pngFirst` 共用，规约方式 = pi 的 `image-process.ts:29-31`）；类 KDoc 的「一处刻意差异」整段重写（见下） |
| `ui/screens/ChatScreen.kt` | `compressAttachment` 一处实参：`AttachmentBudget.attemptPlan(mime, source.hasAlpha(), width, height)`（**只有这一行是必须改的**；`mime` 本就在作用域里，没有新增参数、没有改调用链）；顺带更新该函数 KDoc 第 3 条（「Alpha picks the format」→「Alpha, and a PNG source, pick the format」）与一行行内注释，避免文档说旧话 |
| `app/src/test/.../AttachmentBudgetCheck.kt` | 原来的两条 `encodings` 断言更新签名；新增 12 条断言（下表）；文件头注释同步（差异描述 + 「ChatScreen 的接线以源文本读」）；新增 `import java.io.File` |

**KDoc 里如实写下的残余差异**（要求原文）：pi 对**任何**需要重编码的源都先试 PNG（包括相机 JPEG，
`image-resize-core.ts:112-114`），本应用只对 PNG 源/带 alpha 的位图先试 PNG（`pngFirst`）——理由是 Android 上给
2000×2000 的照片编 PNG 又慢又大（原文那句保留）。**用户可见的差别现在只剩**：一个**不透明、非 PNG 且需要重编码**
的源（相机 JPEG，以及 pi 会先转 PNG 的 HEIC/BMP/WebP，`image-process.ts:49-65`）直接走 JPEG 阶梯，因此可能发出
JPEG 而 pi 会先给 PNG —— 对照片而言这只是名字上的差别（PNG 只会更大），而**快速路径不受影响**：限制内的
PNG/JPEG/GIF/WebP 仍然**逐字节**原样转发，所以限制内的不透明 PNG 仍然以 PNG 原样发出。

**新断言（12 条，全部 PASS）**

| # | 断言 | 覆盖要求 |
|---|---|---|
| 1 | `an opaque non-PNG source skips PNG (the one deliberate difference from pi)` | ②（改前就有，签名更新） |
| 2 | `an alpha source tries PNG first, then JPEG` | ③（改前就有） |
| 3 | `a PNG source tries PNG first even when the bitmap is opaque` | ① |
| 4 | `a PNG source's first candidate is PNG` | ① |
| 5 | `an opaque JPEG source starts at JPEG 80` | ② |
| 6 | `and its candidates contain no PNG at all` | ② |
| 7 | `an alpha source gets PNG whatever its MIME says`（jpeg/heic/webp/octet-stream × alpha） | ③ |
| 8 | `a PNG source is recognised with parameters and casing`（`IMAGE/PNG`、`image/png; charset=binary` …） | ① + 规约方式 |
| 9 | `a JPEG-ish source that is not PNG is not`（jpg/gif/webp/png8/空） | ①的反面 |
| 10 | `the whole candidate order is pi's when PNG is in` = `png, jpeg80, jpeg85, jpeg70, jpeg55, jpeg40` | ④ |
| 11 | `and the JPEG ladder itself is untouched for every source`（三种源的 JPEG 序列都恒为 80/85/70/55/40） | ④/⑤ |
| 12 | `a PNG source's plan starts at PNG, an opaque JPEG's at JPEG 80` | ①/② 在 `attemptPlan` 上也成立 |
| 13 | `the fast path still returns the original bytes for an under-limits, inline-MIME picture`（源文本：那次 `return PiImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)` 恰好一次） | ⑤ |
| 14 | `and it is still evaluated before any encoding plan is built`（源文本：该 return 的位置 < `for (attempt in AttachmentBudget.attemptPlan(mime, source.hasAlpha(), width, height))` 的位置） | ⑤ |

（⑤「快速路径不受影响」只能读源码：`ChatScreen` 导 Compose/Android，本 harness 编不了它；做法与
`ImageSizeCheck` 对解码点的源文本断言一致，`pi.repo.root` 由脚本传给每个 harness。）

**门槛输出**

```
$ # 复刻 tools/run-app-pure-checks.sh 的 image-attachment-budget 注册行（编译 + 运行）
$ java -Dpi.repo.root=<repo> -cp out:libs app.pi.ui.screens.AttachmentBudgetCheckKt
…
PASS a PNG source tries PNG first even when the bitmap is opaque
PASS a PNG source's first candidate is PNG
PASS an opaque JPEG source starts at JPEG 80
PASS and its candidates contain no PNG at all
PASS an alpha source gets PNG whatever its MIME says
PASS a PNG source is recognised with parameters and casing
PASS a JPEG-ish source that is not PNG is not
PASS the whole candidate order is pi's when PNG is in
PASS and the JPEG ladder itself is untouched for every source
PASS a PNG source's plan starts at PNG, an opaque JPEG's at JPEG 80
PASS the fast path still returns the original bytes for an under-limits, inline-MIME picture
PASS and it is still evaluated before any encoding plan is built
…
harness: OK (all checks passed)          # 67 条 PASS，0 FAIL
```

`python3 tools/check-nested-comments.py` → `nested-comments: OK (280 Kotlin file(s) scanned)`。

`tools/typecheck.sh`（全量，改动落盘后）：

```
app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:7:15: error: unresolved reference 'BuildConfig'.
app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:156:34: error: unresolved reference 'BuildConfig'.
app/src/main/kotlin/app/pi/ui/settings/DiagnosticsReport.kt:156:75: error: unresolved reference 'BuildConfig'.
typecheck: FAILED in :app — 3 error diagnostic(s)
```

**只有那 3 条已知的 `BuildConfig` 假阳性**（`ui/settings/**`，我从未碰）；`AttachmentBudget.kt`、
`ChatScreen.kt`、`AttachmentBudgetCheck.kt` **0 error**——`encodings`/`attemptPlan` 的新参数与 `pngFirst`
都通过了编译器。（这次运行里上一轮看到的 `PiPackagesScreen.kt`/`PiSettingsRegistry.kt` 中间态错误已经消失，
说明那两个代理收尾了。）

**真机判据（发一张大截图）**

1. **文字还糊不糊**：截一张屏幕上满是 12–14px 文字的图（例如设置页/一篇文章），通过 `+` 选进输入框，看 48dp 缩略图；
   再把它发出去，看转录里那张缩略图/点开大图后的文字边缘。**方案 (a) 的收益就在这里**：PNG 源（截图就是 PNG）
   现在第一个候选就是 PNG，所以在**不缩边**的情形下它是逐字节无损；只有在需要缩边或 PNG 超出 4.5 MB base64 上限时
   才会落到 JPEG@80。判据：**同一张截图在本次改动前后，发出去的那张图的文字锐利度不得变差**（改动只会让候选序更靠前
   取到 PNG，不会更差）；若变差，说明 PNG 编码在设备上抛异常/超时走到了 JPEG，需要把 `Bitmap.compress(PNG)` 的失败
   打进日志复核。
2. **相机 JPEG 不多付一次编码**：选一张 12MP 相机 JPEG（>2000 边长），观察发送耗时。判据：**不再出现"先试一次
   PNG 编码再退回 JPEG"的那一秒级延迟**（这类源现在直接从 JPEG@80 开始）；用 `atrace`/systrace 看
   `Bitmap.compress` 只被调用一次（同一尺寸上）。
3. **旁证**：`settings` 里看不到这些数据；导出报告（`DiagnosticsReport`）里的附件统计可以作旁证——发同一张截图
   前后，记录里那张图的 base64 长度应与"PNG 候选胜出"一致（比 JPEG 大或小都正常，关键是与 pi 侧记录一致，
   即 MIME 为 `image/png`）。
4. **回归**：连续选 14 张 pi 上限附近的图 + 1 张（第 15 张应被拒）——`MESSAGE_BYTES` 那条拒绝文案不变（已有 66 条断言覆盖）。
