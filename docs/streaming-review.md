# 流式输出审查（streaming-review）

> **As-of**：本文件在工作树上逐跳读代码写成；列出的 `file:line` 是**修复前**（`git show HEAD:<file>`）的行号，修复后行号会移动，所以每条都同时给出**符号名**。工作树里同时有别的代理在改 `PiSessionViewModel.kt` / `Transcript.kt` / `run-app-pure-checks.sh`（`git status` 可见），凡与我无关的在制品错误都在 §8.2 点名。
> **pi 源码**：`/root/pi-src`，`0.85.1`，commit `bbb61e34`（与 App 运行时里打的是同一个版本）。**只读**，一个字没改。
> **规格来源（App 自己的）**：`docs/pi-android-ui-spec.md` §4.2/§4.3/§4.5。
> **口径**：`pi 有 <file:line>` / `pi 无对应物（App 的决定）` / `pi 有但我们够不着（卡在哪）` / 一致或有意偏离 —— 四处都在 §7 汇总。

---

## 0. 一句话结论

流式链路上**真正看得见的问题有五个**，全部在"最后一跳"（Compose 的组合与滚动）和"第一跳"（stdout 解码）上，而且**没有一个**是"引擎事件太多"：

1. **第一跳把中文/emoji 解码坏了**（`String(bytes, 0, read, UTF_8)` 逐读解码，跨读的字符变成 `U+FFFD`）→ 修复并加了 harness（§2.0）。
2. **跟随被自己关掉且永不恢复**（`atBottom` 的一帧近似 + "只关不开"）→ 抽出纯状态机、按 pi 的规则重写（§2.1）。
3. **跟随效果按 `revision` 每 token 重启，滚动可能永远轮不到执行**（`scrollToItem` 是挂起的、会被取消）→ 单条长活效果 + 非挂起 `requestScrollToItem`（§2.2）。
4. **库默认给每一段 markdown 文本挂了 `animateContentSize()`**（流式时每 token 重启 height 动画：行的实测高度永远追不上文本、最新几行被推到列表视口下沿之外、每秒 60 次多余 relayout）→ 关掉（§2.3）。
5. **`retainState = false`**：每次内容变化把行打回 `State.Loading`（默认渲染一个**空 Box**），解析在后台线程完成，是否正好画到"空"的那一帧取决于 vsync 竞争 —— 这就是"有时候会乱闪"→ 打开（§2.4）。

另外两条是**结构性的次要缺陷**，也已修：`scrollToItem(lastIndex)` 在"最后一行比视口高"时把**最新文字留在折线以下**（§2.5）；长会话的"回到最新"入口没有计数、命中搜索/跳转后没有 pi 的 `disableFollow` 语义（§2.6）。

**没有被证实的是"每 token 的 O(n) 算法开销"**：把数字算出来以后（§4.3），reducer 的字符串拼接、engine 的身份 diff、ViewModel 的整表拷贝在手机规模下都是**微秒级**，不是病根；病根是"每秒 60 次的动画与重排"和"空白帧"。诚实的结论是：F7 那条"越流越卡"的叙述在**算法层面**被高估了，在**动画层面**被漏掉了。

---

## 1. 怎么审的（四问逐跳）

链路的每一跳都按四个问题过一遍：**① 每个 token 做了不必要的工作吗？② 产生了会让下游失效/重解析的新对象吗？③ 重组的范围到底是哪一块？④ 有没有"一旦进入就回不去"的闩锁或一次性标记？**

```
① stdout 读取（PiEngineSession.readLoop / JsonlFramer）        → §2.0
② 事件解析（rpc/Events.kt → PiEvents.parse）                    → §2.6（usage 每 token 到达）
③ 归约成行（TranscriptReducer.onEvent / onMessageUpdate）        → §4.3
④ 发布（PiEngineSession.publish / changedIndices / F8 节流）     → §4.3
⑤ ViewModel 同步（syncTranscript / applyChanged / UiState）      → §2.6, §4.3
⑥ 组合与滚动（ChatScreen / LazyColumn / renderWindow / following）→ §2.1, §2.2, §2.5, §2.6
⑦ 行内容（AssistantTextBlock → PiMarkdownText → 高亮）           → §2.3, §2.4, §4.2
```

**审前先核账本（不许照抄）**：`docs/rendering-review.md` 的 F1–F34 在本树里已有多条过期，逐条 grep 之后的结论是：

| 账本 | 账本原话 | 本树实际（我读到的符号） |
|---|---|---|
| F7 | "每个流式行一个 `MutableState<String>`"、"整屏重算" | **`rpc/Transcript.kt` 里没有任何 `mutableStateOf`/`MutableState`**（`grep` 0 命中）：行是不可变 data class；`changedIndices` 已发布（`PiEngineSession.publish`）。行为已改，但**残留见 §2.6** |
| F8 | `tool_execution_update` 未节流 | **已实现**：`TOOL_UPDATE_THROTTLE_MS = 200L`（`Transcript.kt:618`）+ `onToolUpdate` 的节流分支（`:1302`）+ engine 侧不发布被吞掉的块（`PiEngineSession.foldEvent`，HEAD `:445-449`） |
| F4 | 每个 token 重新动画滚动 | **已改成 `following` 状态机**，但新实现自身有两个 bug（§2.1/§2.2） |
| F30 | `SimpleDateFormat` 每次构造 | **已修**：`BlockChrome.kt` 用 `DateTimeFormatter` 常量 |
| F33 | `items` 没有 `contentType` | **已修**：`itemsIndexed(..., contentType = { _, item -> item::class })` |
| F34 | 无上界渲染窗口 | **已实现**：`renderWindow` + 「加载更早的 N 条」+ 滚到顶自动加载 |
| `PiMarkdownComponents.kt:154` | "streaming renderer (`StreamingMarkdownState`) renders an unstable AST tail" | **这个库确实有 `StreamingMarkdownState`**（见 §4.2），但**本 App 没有用它** —— 注释在说一件没发生的事，已改 |

---

## 2. 逐条缺陷（现象 → 根因 `file:line` → 修法）

四态标注只出现在 §7 的汇总表；本节每条给"是不是 pi 的行为"。

### §2.0 — 每个读边界都可能吃掉中文字符（第一跳）

* **现象**：流式中文/emoji 回复里偶尔出现 `�`；长回复里不止一处。用户没直接说"乱码"，但这是流式链路上**唯一会永久损坏内容**的缺陷，且完全不需要设备就能证。
* **根因**：`PiEngineSession.readLoop` 把每次 `InputStream.read(buffer)` 的字节**单独**解码成字符串再交给 framer：

  ```kotlin
  // HEAD app/src/main/kotlin/app/pi/engine/PiEngineSession.kt:368
  val records = framer.feed(String(buffer, 0, read, Charsets.UTF_8))
  ```

  16 KiB 缓冲区的读边界是**任意的**（管道里有多少字节就返回多少），而 `new String(bytes, off, len, UTF_8)` 的契约是"把畸形/不完整序列替换掉"。于是一个跨读的字符被拆成"前一读的残缺序列 + 后一读的续字节"，各变成替换字符。**实测**（本机 JDK，`new String` + `UTF_8`）：

  ```
  "中文" 在第 1 字节切开 -> "\uFFFD\uFFFD\uFFFD文"     // 3 个替换字符，不是 1 个
  ```

  三字节的中文/四字节的 emoji 占多数时，**大多数读边界都落在字符中间**。而且它是**永久的**：framer 在收到剩余字节之前已经把解码后的字符缓冲下来了；下游也没有任何东西能把它当错误 —— JSON 字符串里的 `U+FFFD` 是合法 JSON，记录照样解析成功。
* **pi 侧**：`pi` 自己的读法不共享这个问题 —— 它的 `attachJsonlLineReader` 按**字节**切 `\n` 再解码（`packages/coding-agent/src/modes/rpc/jsonl.ts`）。`pi 有`：按字节分帧 + 解码是一条正确的路线，我们选择了另一条（按文本分帧），于是必须自己保证增量解码。
* **修法**：新增 `rpc/src/main/kotlin/app/pi/rpc/Utf8StreamDecoder.kt`（`java.nio.charset.CharsetDecoder` 的三参数 `decode`，把不完整序列的字节**带到下一读**；只有 `flush()` 在 EOF 才允许把真正截断的序列变成替换字符），`readLoop` 改为 `framer.feed(decoder.decode(buffer, read))` + 收尾 `framer.feed(decoder.flush())`（`PiEngineSession.kt:367-384`）。framer 的契约（只按 `\n` 切、`U+2028/U+2029` 不算换行）一个字没动。
* **验证**：`app/src/test/kotlin/app/pi/rpc/Utf8StreamDecoderCheck.kt`，18 条检查，包括**在每一个字节偏移处切开**、拆成三段、逐字节喂、以及把替换字符行为的基线本身钉住（万一哪天 Java 变了，harness 会红）。`tools/run-app-pure-checks.sh` 的 `utf8-stream`。

### §2.1 — 跟随被自己关掉，而且永远回不来（"不自动触底跟随"）

* **现象**：流式输出时不自动跟随到底；往上翻一次之后再也回不到"跟着走"的状态（除非点 FAB）；"有时候"才出现。
* **根因**（两条独立，叠在一起）：

  1. **判据是"某一帧"的近似**：

     ```kotlin
     // HEAD ChatScreen.kt:405-412
     val atBottom by remember(listState) { derivedStateOf {
         val total = info.totalItemsCount
         total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 2
     } }
     // HEAD ChatScreen.kt:415
     LaunchedEffect(atBottom) { if (!atBottom) following = false }
     ```

     这个 `-2` 的松弛量**只看行号，不看像素**：最后一行只要"部分可见"（索引到了 `total-2`）就算"在底部"。而流式时最后一行的**正文尾部**经常在折线以下 —— 判为"在底部"、于是**不滚**，最新文字永远看不见。反过来，当一行新增/一行长高让这个近似为假时，`following` 被永久关掉。
  2. **只关不开**（`:413-415` 的注释自己写着"Only ever unlocks"）。用户的手势导致的离开和**内容增长导致的离开**用的是同一个信号，后者在流式期间每几帧就会发生一次。关掉之后没人再武装它，直到用户点 FAB。

  **`pi` 的规则正好相反**：`ScrollView` 的 `follow: "end"`（`packages/coding-agent/src/modes/interactive/chat-viewport.ts:22-25`）里，只有**滚动位置**能决定跟随 —— `scrollTo`/`scrollBy` 之后 `followingEnd = followEnd && next === maxScrollTop`（`packages/tui/src/components/scroll-view.ts:157`、`:181`），而 `updateLayout`（内容长高走的就是它）在跟随态**直接重新贴底**（`:189-201`，`:197`）。pi 里**没有任何一条路径**让"内容变高"关掉跟随。
* **修法**：把整台状态机抽成 `app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt`（纯 Kotlin，零 Compose/Android 依赖），规则照 pi：
  * 暂停**只**由"用户在滚动会话中把视口往回移动"触发（`firstVisibleItemIndex`/`firstVisibleItemScrollOffset` 变小，且 `isScrollInProgress`）；
  * **回到末尾就恢复**（`atBottom = !canScrollForward`）—— 这就是"用户滚回底部 → 恢复跟随"；
  * 内容增长、窗口增减、会话切换、inset 变化**都不能改变状态**；
  * 行数变少＝会话被重建（切会话/fork/replay）→ 重新武装并归零未读；
  * 导航类跳转（搜索命中、「跳到上/下一条提问」）走 `pause()`，带 pi 的 `disableFollow` 语义（`scroll-view.ts:131`、`tui-alt-screen.ts:636`）：即使落在最后一行也不自动恢复。
  `ChatScreen` 侧只剩"读 `LazyListState`、填 `TailSnapshot`、执行 `requestScrollToItem`"（`ChatScreen.kt:435-500`）。
* **验证**：`app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt`（52 条检查）。**A 组就是这条回归**：内容增长（含"最后一行高过高视口"）不得关跟随；B 组是手势与"滚回底部恢复"；C 组是 `renderWindow` 增减；G 组是"同一帧多次事件不来回翻转"。

### §2.2 — 跟随效果每个 token 被取消重建，滚动可能一次都没执行

* **现象**："有时候"不跟随；以及底部反复抖动/刷新。
* **根因**：

  ```kotlin
  // HEAD ChatScreen.kt:416-427
  LaunchedEffect(state.revision, following, renderedItems.size, headerRows) {
      if (!following) return@LaunchedEffect
      val last = renderedItems.size - 1 + headerRows
      if (state.streaming) listState.scrollToItem(last) else listState.animateScrollToItem(last)
  }
  ```

  `UiState.revision` 由 `syncTranscript` 写成 `pub.revision`（`PiSessionViewModel.kt` HEAD `:963`，`publish` 每次发布都 +1），**每个流式事件都推进**。所以这个 effect 每 token 被取消并按新 key 重启；而 `scrollToItem` 是**挂起**函数（内部 `scroll { snapToItemIndexInternal(...) }`），在真正执行前就可能被下一个 token 取消 —— 快流式下"到底滚没滚"取决于调度，这正是"有时候"。
* **修法**：
  * 换成**一条长活效果** `LaunchedEffect(listState)` + `snapshotFlow`（`ChatScreen.kt:465-500`），不再以 `revision` 为 key（`ChatBody` 也不再读 `state.revision`，顺带砍掉一条每 token 的整屏失效来源，见 §2.6）；
  * 动作换成**非挂起**的 `LazyListState.requestScrollToItem(index, offsetPx)`：它"在下次 remeasure 应用位置"，不发动画、不进入滚动会话、一帧内多次决定只花一次布局，也不会被下一个 token 取消。
  * `requestScrollToItem` 会**取消进行中的滚动**，所以只在 `following` 且 `!isScrollInProgress` 时调用（快照后还再读一次 `isScrollInProgress` 收口），**绝不打断用户的手**。
* **已验证的部分**：状态机与 pin 的算术（§5）；`requestScrollToItem` 的存在与语义（`androidx.compose.foundation:foundation-android:1.8.3` 的 `LazyListState` 字节码 + Compose 源码 KDoc 都读过）。
* **未验证**：真机上"快流式下到底还有没有丢帧"（§6.1）。

### §2.3 — 库默认给每段 markdown 文本挂了 `animateContentSize()`（"乱闪 / 在底部反复刷新"的主嫌）

* **现象**：流式时底部反复刷新、乱闪；最新几行看起来"跟不上"。
* **根因**：App 从不传 `animations`，于是吃库的默认值：

  * `markdown-renderer 0.45.0`，`model/MarkdownAnimations.kt:40-49`：`animateTextSize` 默认 `{ animateContentSize() }`；注释里写着"也可以返回 `{ this }` 来完全不动画"。
  * 每一段 markdown 文本都带它：`compose/elements/MarkdownText.kt:214` —— `modifier = finalModifier.let { animations.animateTextSize(it) }`。

  `animateContentSize()` 动画的是**布局量出来的尺寸**（Compose 1.8.3 的 `SizeAnimationModifierNode.measure` 把子节点放进"动画中的边界"里，默认 spec 是 `StiffnessMediumLow` 的 spring）。流式时**每个 token 都改变文本高度 → 每次都重启/改目标**，于是：

  * 行的**实测高度**永远滞后于文本：跟随的 pin 一直在追一个移动的目标，最新文字被排到列表视口（`LazyColumn` 自己会裁剪）下沿之外 —— 用户看到的就是"到底了但看不见最后一行"；
  * 每秒 60 次多余 relayout/重绘**只为了动画一个尺寸**；
  * 这是**整个 App 里唯一的动画**：`grep -rn "animateContentSize\|AnimatedVisibility\|animate\*AsState\|Crossfade" app/src/main/kotlin/app/pi/ui` 只有 `BlockChrome.kt:257` 的一句注释（说我们**没有**用它）。用户对"空耗性能的装饰"零容忍，而这一个还顺手把最新文字藏起来了。
* **pi 侧**：`pi 无对应物`。pi 的终端渲染是整帧字符串 diff（`packages/tui/src/tui.ts:477` 把渲染节流到 16 ms），没有任何"内容尺寸动画"。
* **修法**：`PiMarkdown.kt:178` 传 `animations = markdownAnimations(animateTextSize = { this })`（库文档给的就是这个写法）。**没有引入任何新动画**。

### §2.4 — `retainState = false`：每次内容变化把行打回"空"

* **现象**："有时候会乱闪"（间歇、看时序）。
* **根因**：`Markdown(content = …)` 的默认 `retainState = false`。`model/MarkdownState.kt` 的 `updateInput`：

  ```kotlin
  // markdown-renderer 0.45.0, model/MarkdownState.kt:186-187
  if (!newInput.retainState) stateFlow.value = State.Loading(input.referenceLinkHandler)
  ```

  而 `Loading` 的默认槽位是**一个空 `Box`**（`compose/Markdown.kt:107`）。解析随后在 `Dispatchers.Default` 上完成（`MarkdownState.kt` 的 `parse()`）。于是"这一帧会不会画成空白"变成**解析与下一次 vsync 的赛跑**：文档小、机器快时看不出来；文档大或主线程忙时，行**塌成 0 高再长回来** —— 高度抖动正是"在底部反复刷新"。库自己的注释把 `retainState = true` 描述为 "the previous content remains visible while new content is being parsed"。
* **修法**：`PiMarkdown.kt:177` 传 `retainState = true`。注意这个参数**只有核心入口有**：m3 包装（`markdown-renderer-m3` 的 `m3/Markdown.kt:62-104`）不转发它，所以这次调用改成 `com.mikepenz.markdown.compose.Markdown`；m3 包装对这次调用**没有任何别的贡献**（colors/typography/dimens/padding/components/imageTransformer 全是我们自己传的）。
* **未验证**：真机上"空白帧"到底占多少（§6.2）。

### §2.5 — `scrollToItem(lastIndex)` 在"最后一行比视口高"时把最新文字留在折线下

* **根因**：HEAD `ChatScreen.kt:421-425` 的目标是 `renderedItems.size - 1 + headerRows`，而 `scrollToItem(i)` 的语义是"把第 i 项的**顶**放到视口顶"。文档里最长的那一行（正在流式的回答）恰好比视口高时，它的**底**（最新文字）就被放到折线以下一个视口的位置 —— "跟到底"的目标点本身就看不见尾部。
* **pi 侧**：`pi 有`，而且是它的默认行为：`ScrollView` 跟随的是 `scrollToEnd()`（`scroll-view.ts:177-186`）＝**位置**（`contentHeight - viewportHeight`），不是"某一行"。
* **修法**：纯函数算"还差多少像素"，用像素差滚（`TailViewport.pinToTail()`，`TailFollow.kt`）：最后一行可见时 `hidden = lastVisibleOffset + lastVisibleSize - viewportEndOffset`，把它加到当前锚点的 `firstVisibleItemScrollOffset` 上（不需要知道任何行高）；最后一行完全在折线下时先 `Pin(tailIndex, 0)`，下一帧根据实测高度对齐 —— 有限步收敛，而且**过冲会被 Compose 自己在内容末尾夹回来**（`LazyListMeasure.kt:246-269` 的 scroll-back 分支，源码读过）。
* **验证**：harness 的 F 组（含"header 行不改变 pin"—— 老的 `headerRows` 算术整个消失了，pin 直接说列表自己的坐标）。

### §2.6 — "回到最新"入口与导航语义（人性化 / 不抢滚动）

* **FAB 没有计数**：HEAD `ChatScreen.kt:873-880` 只有一个箭头图标，没有 spec §4.5 的「↓ 回到最新（N）」。现在的实现给了一个带文字的胶囊（`ChatScreen.kt:958-983`），`N` = 暂停之后**新到达的行数**（整条 transcript，不只是渲染窗口）。`pi` 的对应物只画一个没有计数的指示条（`tui-renderer.ts:29-33`，`tui-alt-screen.ts:1620`），所以计数是 `pi 无对应物（App 的决定，来自 spec §4.5）`。
* **跳转不是"用户的手"**：HEAD `:451`、`:637`、`:645` 把 `following = false` 写死，`:459` 用 `animateScrollToItem`。动画＝**滚动会话**，会被状态机读成用户手势，从而清掉 `disableFollow` 那层抑制。现在跳转用 `requestScrollToItem(index, 0)`（`ChatScreen.kt:541`），与 pi 的 reveal 一致（`tui-alt-screen.ts:636` 是直接 `scrollTo(target, {disableFollow:true})`，没有动画）。
* **发送后要落在最新**（spec §4.5「发送后…滚动到底」）：`reArmTail()` 挂在发送/排队两条路径上（`ChatScreen.kt:1125`、`:1133`、`:1143`）。
* **会话间不继承**：`following` 原来用 `rememberSaveable`（无 key）—— 切会话时既不重置，rotation 时又会被保住"已暂停"。现在状态机随 `sessionKey` 重建、并带自己的 `Saver`（`ChatScreen.kt:435`、`:1621`）：新会话默认跟随；旋转保住"暂停 + 计数"，且**不**把旋转后的新布局误读成手势（harness G9–G13）。

### §2.7 — `UiState.revision` 之外的每 token 整屏失效

* **根因**：`PiStatusLine(latestUsage = state.lastUsage, …)` 在 `ChatBody` 里读 `state.lastUsage`，而 `lastUsage` **每个 token 都变**：pi 的每条 `message_update` 都带 `usage`（`packages/coding-agent/src/modes/json-event.ts:56-61` 把 `event.message.usage` 放进每条更新），reducer 照单全收（`rpc/Transcript.kt` 的 `onMessageUpdate` 开头 `event.usage?.let { lastUsage = it }`）。所以即使删掉 `state.revision` 这个 key，`ChatBody` 仍会因为 `lastUsage`/`transcript` 每 token 重组一次。
* **判断**：`ChatBody` 会重组是**必要**的（它要拿到新的 `transcript`），真正要保护的是**其余可见行的重组**。这部分依赖 Compose 的跳过：`itemsIndexed` 的 `key = item.key` + `contentType = item::class` 已经就位，Kotlin 2.2 + Compose 编译器的 strong skipping 让 `BlockRenderer` 以参数 `equals` 跳过未变的行（`TranscriptItem` 是 data class；`Modifier` 相等）。
* **没做的事（写在这里，别当成已修）**：把 `lastUsage`/`stats` 的读取下沉到子 composable（把读取范围从 `ChatBody` 收窄到那一行），以及把整个转录区抽成独立 composable 以停止 `ChatBody` 因 `transcript` 重组。两者都是重排 1700 行文件的改动，本地**编译不了 Compose**，收益也只能在真机上量（§6.3），所以这次不动。

### §2.8 — 顺带确认"不是"问题的几处（避免下一轮白干）

* `STREAM_SETTLE_MS = 200`（`PiMarkdownComponents.kt`）**只作用于代码块高亮**（`rememberPiHighlightedCode` 的 `produceState`），不是正文路径，也不是闪烁来源 —— 与父代理的判断一致，没动它。
* `renderWindow` 的 `rememberSaveable(sessionKey)` **没有**空→非空的抖动：pi 的 `SessionManager` 在创建会话时就给了 `sessionFile`（`core/session-manager.ts:947-951`，`persist` 为真时），`get_state` 一次就返回（`modes/rpc/rpc-mode.ts:458`）。所以窗口不会在流式途中被重置。
* `searchMatches` 只在 `searchQuery` 非空时才做 O(n) 扫描（`ChatScreen.kt` 的 `if (searchQuery.isBlank()) emptyList()`），不搜索时不花。
* `formatClock` 已经用 hoist 过的 `DateTimeFormatter`（F30 已修）。

---

## 3. 已实施的修复（根因 → 动作 → 位置）

| # | 根因（§） | 动作 | 位置 |
|---|---|---|---|
| 1 | 逐读解码破坏跨读字符（§2.0） | 新增增量解码器；`readLoop` 用它 | `rpc/.../Utf8StreamDecoder.kt`；`app/.../engine/PiEngineSession.kt:367-384` |
| 2 | `atBottom` 近似 + 只关不开（§2.1） | 纯状态机 `TailFollow`；单条长活效果；pi 的 follow-end 规则 | `app/.../ui/chat/TailFollow.kt`；`ui/screens/ChatScreen.kt:435-500` |
| 3 | 每 token 重启 + 挂起滚动被取消（§2.2） | 非挂起 `requestScrollToItem`；不再以 `revision` 为 key | `ChatScreen.kt:465-500` |
| 4 | 库默认 `animateContentSize`（§2.3） | `animations = markdownAnimations(animateTextSize = { this })` | `ui/render/PiMarkdown.kt:178` |
| 5 | `retainState=false` 打回 Loading（§2.4） | `retainState = true`（改走核心入口） | `ui/render/PiMarkdown.kt:177`（import `:14`） |
| 6 | 高行滚不到底（§2.5） | 像素差 pin；`headerRows` 算术消失 | `TailFollow.kt` 的 `pinToTail()` |
| 7 | FAB 无计数、跳转清抑制（§2.6） | 带计数的"回到最新"；跳转改直接定位；发送后重新武装 | `ChatScreen.kt:958-983`、`:541`、`:1125/1133/1143` |
| 8 | 注释谎：`StreamingMarkdownState`（账本） | 改成事实：库有、我们没用、为什么 | `ui/render/PiMarkdownComponents.kt:150-160` |

**没有改的东西**：`TranscriptReducer`/`Transcript.kt`、`PiSessionViewModel.kt`、`PiEngineSession.publish`、`JsonlFramer`、`AssistantTextBlock`（静态光标是**信息**，不是装饰，保留）。

---

## 4. 性能：策略、取舍与"其实不重要"的部分

### 4.1 每 token 的量级（算出来，不猜）

设一条长回答 20 000 字符、500 个 delta、转录 500 行：

| 每 token 的工作 | 量级 | 判断 |
|---|---|---|
| reducer 拼字符串 `current.text + delta.delta`（`Transcript.kt:1118`） | O(len) 拷贝，全程 ≈ 500 × 10 KB ≈ **5 MB memcpy** | 微秒级，**不是病根**；不为了它引入 per-row `MutableState` 的架构改动 |
| engine 身份 diff `diffIndices`（`PiEngineSession.kt:522-534`，HEAD） | O(行数) 次 `!==`，500 次/ token | 微秒级 |
| engine `current.toList()`（HEAD `:496`） | 500 元素数组拷贝/ token | 微秒级 |
| ViewModel `applyChanged` 整表拷贝（HEAD `:997-1011`） | 500 元素拷贝/ token，**在主线程** | 微秒级；值得做的是把它挪出主线程（未做，§4.3） |
| `visibleItems`/`renderedItems` 重建 | 一次 O(n) filter + 一次 O(window) take | 小；只在 `transcript` 变时才跑 |
| **每 token 的 markdown 注解 + 重排** | O(文档长度) 的 `AnnotatedString` 构建 + 文本测量 | **真正的成本**，且不可避免（见 §4.2） |
| **`animateContentSize` 的动画帧**（修掉前后对比） | 62 s 的流式 ≈ **3 700 次多余 relayout** | **修掉的就是它**（§2.3） |

结论：Kotlin 层的"O(n) per token"在手机规模下是噪声；**每帧一次的尺寸动画**和**空帧**才是"无谓刷新"。

### 4.2 markdown 每个 token 是否重解析 —— 策略与选择

* **事实**：`PiMarkdownText` 把整段 `markdown` 交给库；库按 `content` 变化重新解析（`MarkdownState.updateInput` → `parse()`），解析在 `Dispatchers.Default`，并用 `snapshotFlow{…}.conflate()` **合并**（`MarkdownState.kt:73-83`），所以不是"每个 token 一次解析"，而是"每次解析周期追最新值"。注解与渲染（`AnnotatedString` 构建）仍在主线程、每次该行重组时做 O(文档长度)。
* **pi 侧对照**：`pi` 每条更新都**重建整条消息的 Markdown 组件**（`modes/interactive/components/assistant-message.ts:91-100` 的 `updateContent` → `contentContainer.clear()` + 新 `Markdown`），但它按 (text, width) 缓存渲染结果（`packages/tui/src/components/markdown.ts:245-293`），并且整帧渲染被 16 ms 节流（`packages/tui/src/tui.ts:477`、`:952-1000`）。**即 pi 也是"整段重解析 + 帧节流"，不是增量解析**。
* **我选的策略（未实施，写清为什么）**：正确的增量方案是**稳定前缀缓存** —— 库 0.45.0 自带 `StreamingMarkdownState`（`model/StreamingMarkdownState.kt`：`stableAst` + `unstableAstTail`，`append(chunk)` **只追加**）。但它要求 UI 拿到**增量**；App 的 `UiState.transcript` 拿到的是**累计文本**，要接它就得先给 reducer→UiState 开一条 delta 通道（正是 spec §4.2 那套 `MutableState<String>` 设计）。这个改动跨 `rpc/`+`ui/`、本地编译不了 Compose、失败模式是"空白或陈旧文本"，**不在这次的可证明范围内**，因此不做，只记录（`PiMarkdownComponents.kt:150-160` 也写了）。
* **因此这次关于 markdown 的收益**：不再"先空再跳"（§2.4）、不再每帧动画尺寸（§2.3）。**重注解仍每 token 发生**，这是已知的残留。

### 4.3 残留（未做，需真机数据才决定值不值得做）

1. `syncTranscript` 的整表拷贝在**主线程**（`viewModelScope` = `Dispatchers.Main.immediate`，`PiSessionViewModel.kt` 的 `attach` 里 `engine.publication.collect`）→ 挪到 IO 再切主线程写状态。
2. `ChatBody` 每 token 重组（`state.transcript` 与 `state.lastUsage` 都在它作用域里读）→ 把 usage 的读取下沉到子 composable、把转录区抽成独立 composable（§2.7）。
3. `lastUsage` 每 token 变（pi 每条 `message_update` 都带 usage）→ 可以让 `UiState` 只在**渲染用得到的字段**变化时才发新值。

三者都是"缩小失效范围/减少主线程工作"，不是功能缺陷。

---

## 5. 纯逻辑与 harness：能证明什么、不能证明什么

| harness | 覆盖 | 证明的是 |
|---|---|---|
| `tail-follow`（`app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt` + `app/.../ui/chat/TailFollow.kt`） | 52 检查：内容增长不关跟随（含高行）、手势才暂停、回到末尾恢复、窗口增减不误判、导航暂停的 `disableFollow` 语义、rebuild 重新武装、pin 的像素算术与收敛、同帧多次不翻转、同一几何不重复请求、旋转的保存/恢复 | **规则**：跟随状态机的每一条判断都能在裸 JVM 上复现，包括"瞬时离开底部不该关掉跟随"这条回归 |
| `utf8-stream`（`app/src/test/kotlin/app/pi/rpc/Utf8StreamDecoderCheck.kt` + `rpc/.../Utf8StreamDecoder.kt` + `Jsonl.kt`） | 18 检查：**每个字节偏移**切开、三段、逐字节、畸形序列、EOF 截断、与 `JsonlFramer` 串起来的分帧 | **第一跳的解码**：任意切分都必须等于原文；顺便把"朴素解码会坏"的基线钉住 |
| 既有 5 个 harness | 见 `tools/run-app-pure-checks.sh` | 与本 Change 无关，但我改动 `run-app-pure-checks.sh` 后必须全绿 |

**不能证明的**：Compose 的实际组合/布局/滚动行为（本地**没有 Compose 编译器插件**，`tools/typecheck.sh` 只做 Kotlin 前端）、真机帧率、`requestScrollToItem` 与 `LazyColumn` 的交互细节。这些只能上机（§6）。

---

## 6. 需要真机才能定的（含怎么测）

> 原则：**不在用户可见处加调试计数器**；下面每条的测法都尽量用已有手段（录屏、`adb` 抓帧、已有的 `app.runtime.*` 诊断行做法）。

### 6.1 跟随是否真的一直贴底、是否还有丢帧
* **怎么测**：手机开"开发者选项 → GPU 呈现模式分析（条形图/帧时间）"，发一条要求写长文的问题（≥2000 字），**不要碰屏幕**，录屏 30 s。
* **看什么**：① 最后一行文字是否始终完整可见（不发现在折线以下）；② 是否有周期性 jank 尖峰（如果还有，去看"每帧一次动画"是否真的没了：现在流式行不应有连续的 `animateContentSize` 帧）。
* **期望**：跟随无需用户操作；`following` 不会被自动关掉（不会莫名出现"回到最新"）。

### 6.2 空白帧（§2.4）还剩多少
* **怎么测**：`adb shell dumpsys gfxinfo app.pi framestats`（或录屏逐帧）在流式期间抓 10 s；对比 `retainState = true` 前后（可临时把该参数改回 false 做对照，只在本机实验，不要提交）。
* **看什么**：正在流式的那一行高度是否还会出现"塌陷再长回"；`U+FFFD` 是否彻底消失（现在应该只在引擎真的被截断时才出现）。

### 6.3 重组范围（§2.7）
* **怎么测**：用 `Layout Inspector` 的 recomposition counts（Android Studio）对着 `ChatBody`/`BlockRenderer` 看 60 s 流式里的计数；或按仓库里已有的做法（`app.runtime.*` 那些诊断行）临时加一个**只写日志**的计数器（`Log.d`），**不放进界面**。
* **看什么**：未变的行是否 0 次重组；`ChatBody` 是否每 token 一次（预计仍是，见 §2.7）。

### 6.4 长会话 + 流式共存（§2.5 / F34）
* **怎么测**：找/造一个 ≥300 行的会话，打开（窗口 50），先滚到顶加载几批，再发一条长回答，然后在流式中途上滑看历史、再点"回到最新"。
* **看什么**：① 上滑期间**绝不**被拉回；② "回到最新 · N"的 N 与新增行数吻合；③ 点它以后落在**最后一行文字**（不是最后一行的开头）；④ 滚到顶加载更早的行时不会跳回底部。

---

## 7. pi 四态对照（只覆盖流式链路）

| 语义 | 判定 | 依据 / 卡在哪 |
|---|---|---|
| 跟随最新（`follow: "end"`）、位置回到末尾即恢复、内容增长绝不关跟随 | `pi 有` | `modes/interactive/chat-viewport.ts:22-25`；`tui-alt-screen.ts:262`；`tui/components/scroll-view.ts:157`、`:181`、`:189-201` |
| 导航类跳转即使落在末尾也不重新武装 | `pi 有` | `scroll-view.ts:19`、`:131`；`tui-alt-screen.ts:636` |
| "回到最新"指示条只在暂停时出现、点击**直接定位**（无动画） | `pi 有` | `tui-alt-screen.ts:1620`、`:1015-1021`、`:477-480`；文案在 `tui-renderer.ts:29-33` |
| 指示条上的**未读计数**（`N`） | `pi 无对应物（App 的决定）` | spec §4.5「↓ 回到最新（N）」 |
| 渲染节流到 ~16 ms、忽略中间 delta | `pi 有` | `tui/src/tui.ts:477`、`:952-1000` |
| 整段 markdown 重解析（每条更新重建组件），按 (text,width) 缓存渲染 | `pi 有` | `components/assistant-message.ts:91-100`；`components/markdown.ts:245-293` |
| **增量** markdown（稳定前缀 AST + 不稳定尾巴） | `pi 无对应物`；库有但我们没用 | 库 0.45.0 `model/StreamingMarkdownState.kt`（`stableAst`/`unstableAstTail`）；App 走的是整段入口 `PiMarkdown.kt`。要做需要 delta 通道（§4.2） |
| 尺寸/内容动画 | `pi 无对应物` | pi 无任何尺寸动画；我们曾吃库默认的 `animateContentSize`（§2.3），已关 |
| stdout 按字节分帧 + 增量解码 | `pi 有` | `modes/rpc/jsonl.ts` 的按字节行读取；App 曾逐读解码（§2.0），已改为增量 |
| 每个流式行一个 `MutableState<String>`（spec §4.2 的"打字机"设计） | `pi 无对应物（App 的设计）`，**仍未做** | 现在是不可变行 + 整表增量替换；要做需要 UiState 结构改动（§4.2/§4.3） |
| F8 的 `tool_execution_update` 200 ms 节流 | `一致`（App 自定，pi 无对应节流） | `rpc/Transcript.kt:618`、`:1302`；engine 侧 `PiEngineSession.foldEvent` |

---

## 8. 改动清单与并发现状

### 8.1 我改的文件
* 新增 `rpc/src/main/kotlin/app/pi/rpc/Utf8StreamDecoder.kt`
* 新增 `app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt`
* 新增 `app/src/test/kotlin/app/pi/rpc/Utf8StreamDecoderCheck.kt`
* 新增 `app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt`
* 改 `app/src/main/kotlin/app/pi/engine/PiEngineSession.kt`（`readLoop` 用增量解码；import）
* 改 `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt`（跟随状态机、FAB、跳转、发送后重新武装、importer、Saver）
* 改 `app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt`（`retainState` + 关动画 + 改走核心入口）
* 改 `app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt`（一句注释改成事实）
* 改 `tools/run-app-pure-checks.sh`（注册 `tail-follow`、`utf8-stream`）
* 新增本文件 `docs/streaming-review.md`

### 8.2 同一工作树里的其他在制品（不是我改的，若 typecheck 报错先看这里）
`git status` 于审查时（05:53）显示：`PiEngineHost.kt`、`PiEngineSession.kt`、`session/PiSessionStore.kt`、`ui/PiSessionViewModel.kt`、`rpc/Transcript.kt`、`tools/run-app-pure-checks.sh` 被**别的代理**修改，另有未跟踪的 `app/src/test/kotlin/app/pi/rpc/`、`app/src/test/kotlin/app/pi/session/`、`rpc/.../ExtensionErrorText.kt`。
我改 `PiEngineSession.kt`/`run-app-pure-checks.sh` 时是**最小插入**，并复核过他们的改动仍在（`stopAndDrainQueue` 读 `clear_queue` 响应那一段、`settings-audit` harness 注册都还在）。如果他们在我之后又写同一文件，我的插入可能被覆盖 —— 复核方式：`grep -n Utf8StreamDecoder app/.../PiEngineSession.kt` 与 `grep -n "^run_harness" tools/run-app-pure-checks.sh`。

### 8.3 自检命令与结果
见 §9。

---

## 9. 自检结果

| 命令 | 结果 |
|---|---|
| `bash tools/typecheck.sh` | 见下（`:rpc` / `:app` 的 error 数） |
| `bash tools/run-app-pure-checks.sh` | 见下（含新加的 `tail-follow`、`utf8-stream`） |
| `python3 tools/check-nested-comments.py` | 见下（应为 0 处） |

> 结果在下面的"自检记录"一节逐条写入（命令、退出状态、关键输出）。

---

## 10. 未定/未做（诚实清单）

1. **真机项**：§6.1–§6.4 全部需要在手机上量；本节所有"修好了"的结论都只有"代码 + harness"两级证据。
2. **§4.2 的增量 markdown**：需要 delta 通道，未做。
3. **§4.3 的三项性能收尾**：未做（需要重排 UI 文件 + 真机量收益）。
4. **spec §4.2 的 per-row `MutableState<String>`**：未做（同上）。
5. **`ui-prose-audit` 口径的文案**：新增/改动的用户可见文案只有「回到最新」「回到最新 · N」，没有路径、类名、`§` 或内部设计解释。
