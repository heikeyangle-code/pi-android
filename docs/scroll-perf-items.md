# 单条目的组合成本（滚动专项 · 条目层）

**症状**：「上下滑动出现一些东西的时候会有点顿，有点卡。什么工具卡呀之类的。出现这些东西的时候，滑动不流畅」。

**范围**：`app/src/main/kotlin/app/pi/ui/blocks/**`、`ui/render/**`、`ui/chat/**`（不含 `screens/ChatScreen.kt`、`chat/TailFollow.kt`、`render/RowHeightCache.kt`、`render/TranscriptRowHeight.kt` —— 那四个属于滚动批次，本文件只读不写）。

**被查修订**：工作树（`proroot-runtime`，未提交）。行号以本文件写作时的文件为准；本文件一律用**符号名**指名我们的代码，用 `file:line` 指名 pi。

**方法**（哪些是读到的、哪些是推断的）

| 证据类型 | 能做到什么 | 本文件里的标记 |
|---|---|---|
| 源码逐条读 | remember 键、组合期调用了什么 | 直接给符号名 |
| pi 源码 `file:line` | 呈现语义的判据（截断上限、行数上限、尾部窗口） | `packages/...:NN` |
| bare-JVM 探针（真代码 + 逐字切片） | 纯函数真实毫秒数 | 「实测」+ 数字 |
| 字节码（`javap`，库 artifact） | 库的分派/初态/几何 | 「字节码」 |
| 代码推理 | Compose 的测量/重组时机 | 标 `[推断]` |

**探针**（未入库，`/tmp/probe/items/`）：`rpc.jar`（整个 `:rpc` 模块按源码编出）+ `ToolOutputParse.kt`、`ToolCallPart.kt`、`ImageSize.kt`、`ExtensionUi.kt`、`PiLatex.kt` **原文件**，外加三份**从仓库逐字切出的**片段（`ExtractedBlocks.kt` = `DiffBlock.kt:393-667` + `BlockChrome.kt:476-485` + `ToolBodyText.kt:132-140`；`ExtractedMarkdown.kt` = 改动前的 `PiMarkdown.kt:288-388`；`ExtractedMarkdownNew.kt` = 改动后的同一段），因为 Compose 文件在本机编不了（无 Compose 编译器插件）。原始输出：`measure2.txt`、`measure3.txt`、`measure4.txt`。

> **测量口径，先说清**：本机上「每轮 `System.nanoTime()` 计一次」的写法会给出不可复现的数（同一段代码在 A 进程 0.5 ms、B 进程 500 ms，与负载无关，成因未查明）。本文件所有数字都来自**「预热后连跑 N 次、用一次墙钟除 N」**的口径，并且**每次运行都先测一条标定循环**（227 780 次 `charAt`）：`measure2.txt` 标定 2.24 ms、`measure3.txt` 标定 1.59 ms。**标定值越大说明当次机器越慢**，跨运行的绝对值可能有 2–3 倍浮动；下面给的是「这台手机上的量级」。

---

## 0. 结论：条目级机制排序

排序依据 = **单次代价 × 触发频率**。R1–R3 是「出现新东西时顿」的主因（R3 已按 pi 修掉）；R4–R6 次要（R5 已修掉，R6 的两处已修）。「状态」一列写清每条今天落在哪里。

| # | 机制 | 证据 | 代价量级（实测/推断） | 修法 | 风险（含落地状态） |
|---|---|---|---|---|---|
| **R1** | **`remember` 只活在「这一次组合」里**：行滚出复用池后再滚回来、或切屏回来，每个卡片的解析全部重跑。而 pi 的呈现**本来就要每条重算**的部分（下面 R2）在这之上再叠一层。最大的三个付款方是 `DiffBlock.plan`（`diffPlan`+行内 LCS）、`ShellBlock.painted`（`tailLines`）和 `ShellBlock.bodyText`（`Ansi.strip`） | 实测：`diffPlan` 3.9 ms（60 行/15 对）、13.2 ms（400 行/80 对，截到 200 行）；`tailLines(2000 行,5)` 1.9–2.0 ms；`Ansi.strip(2000 行带色)` 2.4 ms。`remember` 的语义是 Compose 层事实 | 每个「刚进视口的卡片」一次性 **2–13 ms** | ①`plan`/`painted` 改成「只有展开才算」（**已改**，§3 C4/C3）；②**跨组合缓存已落地**（§7）：`DiffPlanCache`（4 MiB）、`ParseCaches.stripped`（8 MiB）、`ParseCaches.tail`（4 MiB），键=内容、按字节计费、命中不重算 | ①零可见变化。②零可见变化（纯函数缓存），代价是进程内最多多占 ~16 MiB（有界、自限），内存依据见 §7 |
| **R2** | **流式更新每次（200 ms）重跑解析**：`Ansi.strip` + `lineCount` 在**输出内容一变**就重算；正文里含 `$` 的长消息每次都要整篇跑 `piMarkdownSource` 的正则 | 实测：`Ansi.strip` 2.4 ms + `lineCount` 0.3 ms（pi 自己的 2000 行/50 KiB 上限下）；`piMarkdownSource` 含 `$` 时 2.2 ms/5.9 KB、13.9 ms/59 KB，其中**没有 `$$` 也要跑的那一趟**占 0.4–1.3 ms | 每个流式卡片每 200 ms **2.7 ms**（改前 ≈4.7 ms）；含 `$` 的消息每 200 ms **多 2–14 ms** | ①尾部窗口与 `$$` 提前退出（**已改**）；②带色 `Ansi.strip` 走整段追加（**已改**：60 KB 带色 2.85→1.05 ms，2.7×）；③`lineCount` 增量（**已改**：`IncrementalLineCount`）；④「settled text」节流**不可以做**：`AssistantTextBlock` 的 KDoc 记着它会破坏触底跟随 | ①②③无可见变化（①有 20 000 组对照、②有 4 089 篇等价、③有 30 000 次对照）；④会回归 |
| **R3** | **流式期间行高不稳**：展开的 shell 卡片画的是「**5 条逻辑行**」的尾巴，哪 5 条每次都在滑，逻辑行**换行后的视觉行数**随之变化 ⇒ 每 200 ms 卡片高度可能变 ±1 视觉行，下面整屏跟着动 | pi 的对应物是「**5 条视觉行**」的固定窗口且**按宽度缓存**：`renderers/bash.ts:18`（`BASH_PREVIEW_LINES = 5`）、`:77-81`（`cachedLines`/`cachedWidth`，宽度不变就不重算）、`modes/interactive/components/visual-truncate.ts:27-48`（`truncateToVisualLines` → 恰好 maxVisualLines 条**视觉**行 + `skippedCount`） | 每次发布 **±1 行**（几十 px）的位移 | **已按 pi 落地**（§1.2、§3 C7/C8）：展开的预览改成「最后 5 条**视觉**行」的窗口，行高在流式期间恒定；窗口规则对 pi 的 `slice(-N)` 有 2 万组对照 | 已消除；剩下的偏差只有标签里的计数口径（逻辑行 vs 视觉行），在 §1.2 写清 |
| **R4** | **高亮代码块进视口是「两遍布局」**：`produceState` 首帧无 spans（未着色）→ 引擎答案落地 → 重建 `AnnotatedString` → 同一个 `Text` **第二次**整段布局 | `render/PiMarkdownComponents.kt` 的 `rememberPiHighlightedCode`（`produceState(initialValue = PiCodeHighlight(), …)` + `remember(code, answer.value, palette) { buildPiCodeText(...) }`）；`blocks/ToolBodyText.kt` 的 `SourceLines` 同样形状（`numberLines` 再来一遍） | 每块**两遍**文本布局；高亮本身在 `Dispatchers.Default`（**不在帧线程**），引擎往返被 128 项 LRU + SHA-256（实测 1.5 ms/15 KB，也在后台线程）挡住 | 无便宜修法（首帧要选基色就得先知道 `languageKnown`）。可选：把 `>=400 行 / >64 KiB 不上报` 的阈值语义显式化（现状已如此） | —— |
| **R5** | **图片在滚动来回时重复解码**：`ImageGridBlock`/`PiImageViewer` 这条链上**没有任何 bitmap 缓存**，`produceState` 随行销毁重建 ⇒ 同一张截图来回滚一次就重解一次（base64 解码 + `BitmapFactory` 两遍 + 采样像素），由 2 个许可的门串行 | `grep -rn "LruCache\|bitmapCache" app/src/main/kotlin` = **0 命中**（改动前）；全 app 唯一的 bitmap 缓存只服务 **markdown 图片**（`bridge/PiGuestImageTransformer.kt:107-140`）。闸门：`blocks/ImageSize.kt` 的 `piImageDecodeGate`（`MAX_CONCURRENT_IMAGE_DECODES = 2`） | 每张图每次重入 **1 次解码**（截图几十 ms，在 IO 线程，但会挤占门 → 后面的图更晚出现） | **已落地**（§1.5、§3 C9–C11）：新增 `ui/blocks/PiImageCache.kt`（进程级、按字节计费的 LRU，键 = 载荷 + 盒子），`readThrough` 命中不占门、不解码；纯策略进新 harness（10837 次断言） | 已消除；内存上限取项目自己的 32 MiB（§1.5 写清依据） |
| **R6** | **组合期「算了但没画」的其它开销**：扩展部件每行 `Ansi.parse`（未 remember）；`ToolCallBlock` 每次发布把输出编码成 byte array 只为问「是否 >200 KB」 | 实测：11 行 widget ≈0.72 ms/次重组；`toByteArray` 0.14 ms + 一次 ~100 KB 分配/次发布 | 每次重组/发布 **亚毫秒–毫秒** | **已改**：widget 的 `chromeSpans`/`boundedWidgetLines` 进 `remember`；`utf8ByteSizeExceeds`（等价、零分配） | 零（有等价性证明，见 §2） |

---

## 1. 逐条验证

### 1.1 工具卡（`blocks/*`）滚进视口：remember 键是否稳定？有没有在组合期解析？

**结论：解析全都在，但都被 `remember(<内容>)` 包住；键是稳定的内容值，不会因为「同一个 200 ms 发布重建了 data class」而重算。真正的问题不是键，是 `remember` 的生命周期（R1）。**

逐块读到的 `remember` 键（`blocks/`）：

| 块 | 组合期解析 | remember 键 | 键稳定？ |
|---|---|---|---|
| `ShellBlock` | `Ansi.strip`、`lineCount`、`tailLines`、`shellExitCode`、`fullOutputPathOf`、`truncationOf`/`truncationNotice`、`shellSubject` | `item.output` / `item.details` / `item.args`（+`fullOutput`、`expanded`） | 稳定 |
| `ReadBlock` | `ToolOutputParse.readBody`、`readRange`、`fullOutputPathOf`、`truncationNotice`、`toolFooterText` | `item.args,item.output` / `item.details` / `item.output,item.exitCode,item.elapsedMs,item.outputTruncated,state` | 稳定 |
| `WriteBlock` | `writeBody`、`toolCommandText`、footer | `item.args` / `item.output,…` | 稳定 |
| `GrepBlock` | `grepBody`（正则行匹配 + 分组）、`grepSubject`、`capGrepGroups`、`countMatches` | `item.output` / `item.args` / `body,expanded,fullOutput` | 稳定 |
| `FindBlock`/`LsBlock`（→`PathListBlock`） | `findBody`/`lsBody`、`capPathGroups`、`countEntries` | `item.output` / `body,expanded,fullOutput` | 稳定 |
| `ToolCallBlock` | `lineCount`、**`toByteArray` 体积判断**、`truncationOf`、`fullOutputPathOf`、`headLines` | `item.output` / `item.details` | 稳定（体积判断**已改**为零分配） |
| `DiffBlock` | `diffPlan`（= 行折叠 + 1:1 配对的**行内 LCS**，`wordTokens`/`longestCommonTokens`） | `item.key,item.diffText`（**已加 `expanded`**） | 稳定 |
| `ToolBlockChrome.ToolHeader` | `buildAnnotatedString`（每 run 一个 span）+ 默认参数 `right = toolHeaderReading(item)`（含 `String.format`） | `subject, palette` | 键稳定（`List` 结构相等）；默认参数**每次重组都求值**（µs 级，记录不改） |

`ToolOutputParse.kt` 自己的 KDoc 已经把这条契约写死（「These run inside `remember`, keyed on the value they read」），我逐块核对：**契约成立**。

**不成立的部分（读了但排除）**：

* 「键里放了每次新建的对象」——**不成立**。没有 `remember(item)`、没有 `remember(listOf(...))` 之类的键；`ToolCall`/`ToolDiff` 是 data class，`args: JsonObject?`、`details: JsonElement?`、`output: String` 的相等都是结构相等，`String.equals` 还在同一实例上短路。
* 「`derivedStateOf` 被误用/漏用」——**`blocks/`、`render/`、`chat/`、`extension/` 里 `derivedStateOf` 一个都没有**（grep 0 命中），所以本条不成立（也就没有「用它省一次重组」的收益）。
* 「组合期排序/分配大列表」——`blocks/` 里没有排序；`capGrepGroups`/`capPathGroups` 的结果在 `remember(body, expanded, fullOutput)` 里，且**只在展开时**才切（`if (!expanded) emptyList()`，见 `GrepBlock`/`PathListBlock` 的 `plan`）——这一点两个文件已经做对了，`DiffBlock`/`ShellBlock` 没有（R1，已改）。
* **两处真实但很小的组合期分配（读了，判定不值得改）**：
  1. `FindBlock`/`LsBlock` 把 `findSubject(item.args)`/`lsSubject(item.args)` 直接写在 composable 体里（每次重组重建一个 2–4 元的 `List<ToolCallPart>` + 几次 `JsonPrimitive` 取值，µs 级）；因为 `ToolHeader` 用**结构相等**做 `remember` 键，下游不会被冲掉，所以只有分配没有重算。
  2. `ReadBlock`/`WriteBlock` 的 `val shown = body.lines.take(N)` 每次重组新建一个 ≤200 元的 `List`（同样只有分配）。
  两条都在这里记录，不改（保持最小差异）。

### 1.2 流式更新：一个可见的工具卡会重测多长的文本？行高会不会变？

**结论：正文的「重测长度」不是问题（正文被窗口化：折叠 0 行、展开 5 行，全展开 200 行）；问题是 ①每次发布重算的**解析**（R2），②**行高会变**（R3，逻辑行尾巴换行数在滑）。**

* **重测范围**：`Text`/`MonoText` 的内容变了必然重新布局，但画出来的只是窗口内的文本——折叠的 shell 卡一行正文都不画（`ShellBlock` 的 `if (expanded && bodyText.isNotEmpty())`），展开时 `tailLines(bodyText, 5)`（= `SHELL_PREVIEW_LINES`，pi 的 `BASH_PREVIEW_LINES`，`renderers/bash.ts:18`），「展开全部」后是 `TOOL_BODY_MAX_LINES = 200`。所以布局成本与**画出来的行数**成正比（5 或 200），与 pi 的 50 KiB 结果无关。
* **组合期成本与画出来的行数无关**：`Ansi.strip` + `lineCount` 是 O(整个结果)。实测（pi 自己的上限：2000 行 / 50 KiB，`core/tools/truncate.ts:11-12`）：`Ansi.strip` 2.4 ms、`lineCount` 0.3 ms、`tailLines(…,5)` 1.9–2.0 ms、`tailLines(…,200)` 1.9 ms；**四件一起** 3.5 ms（`Ansi.strip` 无 ESC 的快路径只要 0.28 ms/272 KB，所以带色日志按字符计贵一两个数量级）。改前折叠状态下也要付 `tailLines`，现在不付。
* **行高会不会变？改前会，现在不会（已按 pi 修掉）。** 改前 `tailLines` 保留的是最后 5 **条逻辑行**，而 Compose 的 `Text` 按**视觉行**（含换行折行）排版：同一条日志行可以折成 1–3 行，尾巴一滑，画的视觉行数就变；卡片本身没有 `maxLines`（`MonoText` 默认 `maxLines = Int.MAX_VALUE`），所以高度真的会变，位移发生在**卡片自己的高度**上 → 下面所有行跟着动。
* **修法（已落地）：改用 pi 的窗口，并逐项对齐。** 展开态的预览现在画的是「最后 5 条**视觉**行」的窗口：`ShellBlock` 的 `ShellPreviewBody` 用 `BoxWithConstraints` 读宽度、`rememberTextMeasurer()` 在同宽度同 style 下量一次 `painted`，取 `visualTailWindowStart`（`ToolBodyText.kt`）给出的窗口起点，再画该子串。行高因此恒为 5 视觉行（或更少，当正文本来不足 5 行），流式期间不再移动。

  | pi（`packages/coding-agent/src/...`） | 我们 |
  |---|---|
  | `renderers/bash.ts:18` `BASH_PREVIEW_LINES = 5` | `ShellBlock.kt` 的 `SHELL_PREVIEW_LINES`（同一个数，未变） |
  | `renderers/bash.ts:78` `truncateToVisualLines(styledOutput, BASH_PREVIEW_LINES, width)` | `ShellPreviewBody` 的 `measurer.measure(text, style, Constraints(maxWidth = widthPx))` |
  | `visual-truncate.ts:31-34` 建临时 `Text` 并在**终端宽度**下 render 出全部视觉行 | `TextMeasurer`（Compose 的等价物：宽度、style、layoutDirection 都取当前组合） |
  | `visual-truncate.ts:37` `allVisualLines.length <= maxVisualLines` → 全画、`skippedCount = 0` | `visualTailWindowStart`：`lineCount <= maxVisualLines` → 返回 0（全画） |
  | `visual-truncate.ts:44-45` `slice(-maxVisualLines)`、`skippedCount = length - max` | `visualTailWindowStart`：起点 = `lineStarts[lineCount - max]` |
  | `renderers/bash.ts:77-81` `cachedLines`/`cachedWidth`：**宽度不变就不重算** | `remember(text, widthPx, style, measurer)`：同一 (文本, 宽度) 命中 memo；宽度变才重量一次 |
  | `renderers/bash.ts:86-89` 提示行 `... (N earlier lines, to expand)`，N = `skippedCount`（**视觉**行） | 标签是本 app 自己的句子（「展开全部（上方还有 N 行）」/「上方还有 N 行未显示」），N = 窗口**上方完全未显示的逻辑行**数（`hiddenLineCount(totalLines, window)`）。**这是唯一一处有意不照抄的开发**：数视觉行要按当前宽度把**整段正文**（pi 上限 2000 行/50 KiB）排一遍，实测 2000 行的一次排版不是每 200 ms 该做的事；而「上方还有 N 行」这句中文声称的本来就是「读者没看到的行数」，按逻辑行算更贴它的字面 |
  | pi 的**折叠**卡画这个窗口，展开卡画整段 | 本 app 的折叠卡不画正文（D45，未变），**展开**卡的预览画这个窗口；「展开全部」后画 `TOOL_BODY_MAX_LINES = 200` 的逻辑尾巴、不套视觉窗口（那是 app 自己的预算，pi 的展开卡是整段） |

* **断言（可复跑）**：窗口规则与 pi 的 `slice(-N)` 在 **20 000 组**（随机宽度/逻辑行长度/窗口大小）合成视觉行表上完全一致；**2 000 组**几何检查里，窗口内的可见视觉行数恒为 `min(max, 总视觉行数)`（就是「行高稳定」这条性质本身）；探针的 2000 行正文上，尾部 10 行变长把总视觉行数从 2000 变成 2018，可见行数仍是 5。原始输出见 §2.6。**同一宽度不重算**这一条是 Compose memo 的语义（键里没有别的东西会变），本机跑不了 Compose，真机判据见 §4-S1/S7。
* 另一条「行高稳定」的既有机制在别处：`render/TranscriptRowHeight.kt` + `render/RowHeightCache.kt`（给行一个「曾经量过的高度」下限），属于滚动批次，不在本文件范围。

### 1.3 syntax highlight：在组合期同步跑吗？整段还是可见行？500 行代码块滚进视口会怎样？

**结论：不在组合期跑（在 `Dispatchers.Default` 上、由 `produceState` 驱动）；跑的是**整段**；500 行代码块**根本不会被高亮**（超阈值直接拒绝），所以它的成本只有一次文本布局。**

* 调用点：`render/PiMarkdownComponents.kt` 的 `PiCodeSurface`（`rememberPiHighlightedCode(code, normalized)`）与 `blocks/ToolBodyText.kt` 的 `SourceLines`（同一个函数，`read`/`write` 正文）。`rememberPiHighlightedCode` 的 `produceState` 体里是 `withContext(Dispatchers.Default) { highlighter.highlight(code, language) }`；组合期只做 `buildPiCodeText`（`remember(code, answer.value, palette)`，O(字符数 + span 数)）。
* 整段 vs 可见行：**整段**。没有「按可见行切片再高亮」这条路（也不该有：高亮器是 guest 的 highlight.js，按整段请求才有 pi 的同一答案）。
* 上限（决定 500 行会怎样）：`highlight/PiNodeCodeHighlighter.kt` 的 `MAX_CODE_CHARS = 64 * 1024`、`MAX_CODE_LINES = 400` —— 超过任一条**不发请求**，直接返回「无 spans、`languageKnown = false`」。**500 行的代码块 = 未着色（`languageKnown=false`）**，一次布局，没有往返、没有二次布局。
* 阈值内的一次请求：`MAX_IN_FLIGHT = 2` + `QUEUE_LIMIT = 64` + `CALLER_WAIT_MS = 750`（排队等待也算在这 750 ms 里）→ 队列满就「渲染成不着色」；结果按 `(language, code)` 的 SHA-256 进 128 项 LRU（span 数 >2048 的不缓存）。实测：SHA-256 over 15 KB = 1.5 ms（在后台线程）、4 MiB = 34 ms（这条数字是「不要用 payload 做帧线程哈希」的判据，见 §1.5）。
* **首帧几何**：`produceState` 的初值是 `PiCodeHighlight()`（无 spans、`languageKnown=false`）⇒ 第一帧整块按「未着色分支」画（pi 的 `mdCodeBlock` 色），答案落地后再一遍 ⇒ **两遍整段布局**（R4）。颜色不变、字号不变、行高不变，所以它**不推动**下面的内容；代价是那一帧的布局时间。
* `SourceLines` 多一层：`numberLines`（逐行切 `AnnotatedString` 片段 + 编号）也在 `remember(highlighted, clean, startLine, palette.muted)` 里，高亮落地时要重跑一次。

### 1.4 markdown：解析怎么驱动？非 immediate 时首帧几何是什么？

**结论：非 immediate 的首帧就是库的 `loading` 槽，几何 = 行自己的 modifier **加 0 高**（空 `Box`）；行会在解析落地的那一帧「长出来」，于是它自己的测量跑两遍，且当它从**上方**进视口时把整屏往下推 H。**

* 驱动：`render/PiMarkdown.kt` 把 `content`、`retainState = true`、`immediate`、以及**三个稳定实例**（`flavour`/`parser`/`references`）和 `components` 交给库；解析由库里 `rememberMarkdownState` 的 `LaunchedEffect` 驱动，结果是 `State.Loading | Success | Error`。`immediate` 来自 `LocalPiMarkdownImmediate.current`（`render/PiMarkdownImmediate.kt`），由 `ChatScreen` 按「这一行是不是刚从批里进来的 / 是不是恢复出来的那一帧」决定，**流式行永远不给**。
* 首帧几何（字节码）：`Markdown(State, colors, typography, modifier, padding, …)` 的分派 lambda 签名是 `Markdown$lambda$14(State, Function3 loading, Modifier, Function3 error, Function5 success, MarkdownComponents, Composer, flags)`；在 `Loading` 分支里，被调用的是 `Function3`（loading）并传入 **`Modifier` 参数本身**（`aload` 该形参），库默认的 loading 槽就是 `BoxKt.Box(Modifier, …)`（同一 artifact 里默认槽的字节码有 `BoxKt.Box` 调用）。**我们传进去的 `padding`/`dimens` 不参与 loading 帧**（`piMarkdownPadding` 的 `block = 2.dp` 等是给渲染出来的块的）。⇒ 非 immediate 行的首帧高度 = 行自己的 modifier（`Modifier` / `weight(1f)` / `fillMaxWidth()`）+ **0**。
* 后果（[推断]，机制来自上面的字节码 + 库的异步解析）：
  1. 行自己的**两次测量**：0 高一次、真实高一次（第二次不可避免，第一次是白跑的）；
  2. 从**上方**进视口的行，高度修正发生在屏幕上 → 它下面所有行被推 H（父文档 `docs/scroll-diagnosis.md` §1.2-P3 已把这条钉住）；
  3. 从下方进视口时修正在屏幕外，所以「上滑看下面的消息」没有这个问题；
  4. `LazyColumn` 在两次测量之间看到的内容尺寸不同 ⇒ 会重新定位（不是重新开滚动会话）。
* **我们在这一层多花的钱**：`PiMarkdownText` 里 `piMarkdownSource`（组合期、`remember(markdown)`）。实测：无 `$` 0.07 ms（快路径）；**含 `$` 的长正文 2.2 ms/5.9 KB、13.9 ms/59 KB**；其中**「没有 `$$` 也要跑的那一趟」占 0.4–1.3 ms**（`measure4.txt`：未加保护的 `BLOCK_MATH.replace` 在 5.9 KB 上是 0.36–1.32 ms，加了 `contains("$$")` 守卫后 0.40 ms）。**已改**（§3）：`$$` 不存在时跳过 display-math 那一趟，等价性有 4 018 篇文档的对照（§2）。

### 1.5 图片：解码闸门、组合期报头、Pending/Ready 与行高、滚动来回是否重复解码

**结论：①解码有闸门（进程级 2 许可）②报头在组合期同步读，O(前缀) 但**不便宜**（JPEG 带 EXIF 时 4.2 ms）③`Pending → Ready` **不会**改变行高（建自报头比例）④**滚动来回会重复解码**：这条链上没有 bitmap 缓存。**

* 闸门：`blocks/ImageSize.kt` 的 `piImageDecodeGate`（`MAX_CONCURRENT_IMAGE_DECODES = 2`，`ImageDecodeGate` 是 `Semaphore`），`ImageGridBlock` 的 cell 、`PiImageViewer`、`ChatScreen` 的附件缩略图都走它。滚动时不会起一堆全分辨率解码。
* 组合期读报头：`ImageGridBlock.ImageCell` 里 `remember(image.base64) { naturalImageAspect(image.base64) }`。实测：PNG 前缀 0.02 ms（32 字节基码）；**JPEG 且 SOF 在 40 KB EXIF 之后 = 4.2 ms**（`base64PrefixBytes(payload, 64 KiB)` 自己 0.75 ms）。这是**帧线程上的同步扫描**，且因为 `remember` 只活在一次组合里，**每次滚回视口都要再付一次**（JPEG 大 EXIF 的情况才会到 ms 级）。
* `Pending/Ready` 与行高：**验证后不成立（行高确实不变）**。单图的高度只来自 `singleImageBoxHeightPx(目标宽, 报头比例, 上限)`（`ImageSize.kt`），`ImageCell` 的 `Surface` 用 `Modifier.fillMaxWidth().height(boxHeightPx)`；网格 cell 用调用方给的 `weight(1f).aspectRatio(1f)`，`Surface` 是 `fillMaxSize()`。两条都与 `decoded` 无关；`Ready(null)`（字节不是图）只是把空盒子换成标签，盒子尺寸不变。**判据**：`ImageGridBlock.kt` 里 `decoded` 只出现在 `Box` 内部的分支，从不进入任何尺寸 modifier。
* 重复解码：**改前成立，现已修掉**。`produceState(CellImage.Pending, image.base64, targetWidthPx, boxHeightPx)` 是组合作用域状态；行被 LazyColumn 丢弃后状态消失，滚回来重新解码（`decodePiImage`：`Base64.decode` → `inJustDecodeBounds` 一遍 → 采样后 `decodeByteArray` 第二遍）。改动前全 app 没有共享 bitmap 缓存（grep 0 命中），唯一的缓存是 markdown 图片那条链的 `bridge/PiGuestImageTransformer.kt:107-140`。
* **新增 `ui/blocks/PiImageCache.kt`**（进程级）：`ImageGridBlock.ImageCell` 与 `PiImageViewer` 的解码都走 `PiImageCache.readThrough(payload, width, height) { … }`——
  * **键 = 载荷字符串 + 盒子（宽×高）**：同一张图的网格采样与查看器整窗解码是两条目（不同分辨率、不同表面，`PiImageViewer` 的 KDoc 说清为什么查看器要自己一份）。
  * **只在命中时跳过解码，显示语义不变**：未命中仍旧走 `piImageDecodeGate` + `decodePiImage`，`CellImage.Pending` 首帧照旧；命中时 `readThrough` 直接返回已解码位图（第一帧之后一次主线程跳）。**失败不缓存**（`Ready(null)` 每次重试），与 `PiGuestImageTransformer` 同一条规则。
  * **按字节计费**：底层 `ByteBoundedLru`（`ImageSize.kt`，纯 Kotlin）用 `weigh = 载荷字符数 × 2 + bitmap.allocationByteCount`，所以键本身占的内存也计进上限。上限 `MAX_TOTAL_BYTES = 32 MiB`，取的是项目自己的另一个 bitmap 缓存同值（`PiGuestImageTransformer.MAX_TOTAL_BYTES`），两个缓存不会对「图片能占多少内存」有分歧。以一张 2.8 M 字符 base64 的截图算：键 ~5.6 MB + 位图几 MB ⇒ 缓存大约容 3–5 张，最久未用的被逐出。
  * **不哈希键**：`ByteBoundedLru` 是线性扫描 + `==`/`equals`，**从不调用 `hashCode()`**（harness 里用一个 `hashCode` 计数为 0 的键类钉死）。这是关键：载荷是 MB 级字符串，`String.hashCode()` 首次在 4 MiB 上是 **22.8 ms**（实测），`LinkedHashMap` 每次 `get` 都要哈希，正是这个缓存要避开的东西。扫描的条目数由字节预算兜住（3–5 条），最坏是一次 `memcmp`（同内容不同实例的载荷）。
  * **生命周期**：进程级 `object`，只持 `Bitmap` 与载荷字符串，不持 `Context`/`View`，Activity 重建不泄漏；只靠字节上限逐出，没有定时器、也不需要 `onTrimMemory`；`clear()` 留给将来想接 trim 钩子的调用方（今天没人调，缓存自限）。
* 顺带否掉一条：**「用 payload 做 LRU key 会因为 `String.hashCode()` 卡帧」这个担心是成立的但被夸大了**——实测 4 MiB 字符串首次 `hashCode()` 22.8 ms、SHA-256 34 ms（都在本机、都只能放在后台线程）。这也是 `ImageSize.kt` 的 KDoc 里拒绝「按 payload 哈希做进程级 LRU」的原因；如果做缓存，key 要用**引用/内容摘要只算一次**的方式（`PiGuestImageTransformer` 用 link 字符串做 key，正是这个道理）。

### 1.6 扩展部件（`ui/extension/**`）：ANSI 解析、每行两次解析、重组范围

**结论：`ExtensionWidgetStack` 里「每行只解析一次」这一点**成立**（前一次修复确实去掉了 `chromeText(line).isEmpty()` 的第二次 `strip`），但**那一次解析没有 `remember`**，每次重组都重跑；widget 列表与每一行的解析现在都进了 `remember`（已改）。**

* `ExtensionChrome.kt` 的 `ExtensionSpans`：`remember(spans, tokens, defaultColor)` 建 `AnnotatedString` —— 键是 `Ansi.Span` 的**结构相等**，所以内容没变就不重建。**这一点原本就对**。
* `ExtensionWidgetStack`（同一文件）：`boundedWidgetLines(widget.lines)` 与 `chromeSpans(line)` 原来都在组合体里直接调用。实测 11 行（`MAX_WIDGET_LINES = 10` + 截断行）≈0.72 ms/次重组，`boundedWidgetLines(40 行)` ≈0.21 ms。这个栈挂在 composer 上下（`ChatScreen` 两处调用），**只要 widget 存在就跟着那次屏幕的重组跑**（每 200 ms 发布、每次输入）。
* 其它调用点普查（不许漏）：`chat/ChatSheets.kt:676` 已经 `remember(status.text) { chromeSpans(...) }`；`ExtensionUiHost.kt:197` 的 snackbar 是**一次性**转瞬消息（未 remember，判定不改）；`ExtensionDialogs.kt` 的四处都在模态对话框里（不在滚动路径上）。
* pi 的判据：`MAX_WIDGET_LINES = 10` 与截断行 `... (widget truncated)` 抄自 `modes/interactive/interactive-mode.ts:2276` / `:2213-2218`，我们只改**缓存**，不改文案与上限。

### 1.7 其它每项成本（`ShellBlock`/`DiffBlock`/`ReadBlock` 的键、`derivedStateOf`、组合期大列表、`maxLines`/`overflow`）

* `ShellBlock`：键稳定（见 1.1）；`painted`/`hidden` **改前在折叠时也算**（实测 1.9–2.0 ms/次），已加 `expanded` 键。`Ansi.strip` 仍在折叠时算——因为 `expandable`（正文是否为空）与页脚的 `lineCount` 都要它；这条**不改**（`strip` 去掉的 SGR 不含 `\n`，行数其实可以不依赖它，但「只有转义字节的正文」这个边界的 `expandable` 语义会变，不值得为 0.28–2.4 ms 冒这个险）。
* `DiffBlock`：`plan` 改前无条件算（R1），已加 `expanded` 键；`DiffLineRow` 的 `diffBody` 在 `remember(row, lineColor, palette.toolPendingBg)` 里（行内容与颜色都结构相等）；`omitted` 是廉价比较。
* `ReadBlock`：`body`/`range`/`footer`/`notice` 键都稳定；`shown`（`take(10)`）每次重组新建一个小 list（只有分配）。
* `maxLines`/`overflow`：工具卡的标题行/页脚都是 `maxLines = 1`（不会因为内容长了多测一行）；`MonoText` 默认 `maxLines = Int.MAX_VALUE` + `Ellipsis`（只在 `maxLines` 有限时才截断）——真正会「多测很多行」的只有 ①展开到 200 行的正文（用户主动展开）②`ThinkingBlockBlock` 的思考正文（一整段 `Text`，pi 也整段打印）。这两处**不改**：要改就是改呈现。
* `formatClock`/`formatDuration`/`String.format` 每行每次重组：`DateTimeFormatter` 已经在文件级提升（F30），其余是 µs 级；`ToolHeader` 的默认参数 `right = toolHeaderReading(item)` 每次都算一次 `String.format`（µs 级，记录不改）。
* `Ansi.parse` 与 `Ansi.strip` 不是一回事：`parse` 在 200 KB 带色文本上实测 **42.7 ms**（每次都要 split 参数、建 span 对象），`strip` 同一份是 5.4 ms、无 ESC 的 272 KB 只有 0.28 ms。**工具正文一律用 `strip`**（`ShellBlock`/`ToolCallBlock`/`ToolBodyText.SourceLines` 都是），`parse` 只出现在扩展 chrome（短行）。这条属于「现状正确，别把 `parse` 用到正文上」。

---

## 2. 实测：探针、口径与原始数字

**口径**（重复 §0 的说明）：预热后连跑 N 次、一次墙钟除 N；每次运行先跑标定循环（227 780 次 `charAt`）。`measure2.txt` 标定 **2.24 ms**，`measure3.txt` 标定 **1.59 ms**（`measure4.txt` 同轮次）。

### 2.1 `measure2.txt`（大尺寸/上界口径）

```
== 1. ANSI (rpc/Ansi.kt, the real object) ==
Ansi.strip(50k shell output, SGR on every line)                    3.0354 ms/call
Ansi.strip(200k shell output, SGR on every line)                   5.4130 ms/call
Ansi.strip(200k shell output, no SGR at all)                       0.2795 ms/call
Ansi.parse(200k shell output, SGR on every line)                  42.6871 ms/call
Ansi.parse(one 32-char widget line)                                0.0110 ms/call

== 2. line/head/tail helpers (verbatim copies) ==
lineCount(200k shell output)                                       4.0612 ms/call
headLines(200k, 200)                                               0.8775 ms/call
tailLines(200k, 5)                                                 0.7490 ms/call
tailLines(200k, 200)                                               1.0430 ms/call
hiddenLineCount + lineCount + tailLines (one card's pair)           1.5431 ms/call
utf8 size by toByteArray(UTF_8), 200k ASCII (ToolCallBlock's line 61)     0.3831 ms/call
utf8 size by toByteArray(UTF_8), 160k CJK                          1.5244 ms/call
cheap guard: text.length > 200 KiB                                 0.0004 ms/call

== 3. ToolOutputParse (the real object) ==
readBody(args, 200-line result)                                    0.2311 ms/call
readBody(args, 2000-line result)                                   0.5020 ms/call
writeBody(args carrying 200-line content)                          0.1344 ms/call
grepBody(200 matches over 30 files)                                1.8606 ms/call
findBody(200 entries)                                              1.1321 ms/call
lsBody(200 entries)                                                0.7611 ms/call
fullOutputPathOf(null, 200k output)                                0.2054 ms/call
shellExitCode(null, 200k output)                                   0.1030 ms/call

== 4. diff planning: DiffBlock.diffPlan (+ intra-line LCS) ==
diffPlan(60 rows, 15 changed pairs)                                3.8589 ms/call
diffPlan(400 rows, 80 changed pairs, capped at 200)               13.1999 ms/call
diffPlan(one pair, 512 tokens a line: worst-case LCS)             11.7568 ms/call
wordTokens(512-token line) alone                                   3.1719 ms/call

== 5. markdown source rewrite ==
piMarkdownSource(5.4k prose, no '$')                               0.0073 ms/call
piMarkdownSource(4.3k prose containing '$', 60 lines)             11.4299 ms/call
piMarkdownSource(54k prose containing '$')                        49.2740 ms/call

== 7. image header reads (ImageSize.kt, the real file) ==
naturalImageAspect(png, 2.8 MB base64)                             0.0213 ms/call
base64PrefixBytes(png payload, 32)                                 0.0186 ms/call
naturalImageAspect(jpeg with 40 KB of EXIF before SOF)             4.1747 ms/call
base64PrefixBytes(jpeg payload, 64 KiB budget)                     0.7508 ms/call
```

### 2.2 `measure3.txt`（**pi 自己的上限** + 真实 `$` 密度 + 修正后的哈希）

```
CALIBRATION charAt-loop over 227780 chars: 1.5911 ms/call
fixtures: 2000-line bash output = 68090 chars (pi's cap is 50 KiB / 2000 lines)

== 1. one shell card's composition-time work at pi's cap ==
Ansi.strip(2000-line result, colour on 1/5 of lines)               2.3536 ms/call
lineCount(stripped)                                                0.3028 ms/call
tailLines(stripped, 5)                                             1.9825 ms/call
tailLines(stripped, 200)                                           1.8550 ms/call
the whole pair (strip+count+tail+hidden) as ShellBlock runs it     3.4601 ms/call

== 2. ToolCallBlock's oversize check (2000-line result) ==
output.toByteArray(UTF_8).size on 100k chars                       0.1394 ms/call
text.length > 200 KiB first (the cheap guard)                      0.0008 ms/call

== 3. piMarkdownSource at realistic '$' density ==
piMarkdownSource(5k prose, no '$')                                 0.0699 ms/call
piMarkdownSource(5k prose + ONE price '$5')                        2.1827 ms/call
piMarkdownSource(5k prose + ONE formula)                           3.5946 ms/call
piMarkdownSource(50k prose + ONE price)                           13.9247 ms/call

== 4. corrected payload hashing (fresh string each call) ==
String.hashCode() first call on a fresh 4 MiB string              22.7569 ms/call
SHA-256 over a fresh 4 MiB string                                 34.1071 ms/call

== 5. extension widget lines (11 lines x 60 chars) ==
Ansi.parse over all 11 widget lines                                0.7224 ms/call
boundedWidgetLines(40 lines)                                       0.2067 ms/call
```

### 2.3 `measure4.txt`（`piMarkdownSource` 慢在哪、守卫值多少）

```
BLOCK_MATH.replace over 5.9k prose + one price (cannot match)        1.3176 ms/call
INLINE_MATH.replace over 5.9k prose + one price (+1 price match)     1.1403 ms/call
BLOCK_MATH.replace over 5.9k prose + one formula (cannot match)      0.3601 ms/call
INLINE_MATH.replace over 5.9k prose + one formula                    3.3167 ms/call
guarded: contains("$$") then BLOCK_MATH.replace                      0.4015 ms/call
piMarkdownSource(5.9k + one price)                                   1.8882 ms/call
```
（同一份 5.9 KB 输入上，`BLOCK_MATH.replace` 两行报出 1.32 ms 与 0.36 ms，就是本机噪声带宽的实证；结论取「同一趟扫描 0.4–1.3 ms、可整趟跳过」。）

### 2.4 等价性检查（两处纯逻辑改动）

```
== 1. utf8ByteSizeExceeds vs toByteArray(UTF_8).size ==
checked 71925 (text, maxBytes) pairs over 4795 strings; failures=0

== 2. piMarkdownSource: guarded vs unguarded ==
compared 4018 documents; differences=0; failures=0

== 3. the guard's own claim: BLOCK_MATH.replace(run) == run when no $$ ==
documents without $$: 1893; failures=0

ALL EQUIVALENCE CHECKS PASSED
```
语料：ASCII / `é` / 中日韩 / emoji（代理对）/ **孤立代理项** / `\uFFFF` / 各前缀长度的组合，`maxBytes` 取 0…200 KiB（含正好 100 字节边界两侧）；文档语料含 no-`$`、单价、`$x^2$`、`$$…$$`、代码围栏内的 `$`、行内代码里的 `$`、`\$$`、`$$$$`、`$a$$b$`，外加 4 000 篇随机拼接。

### 2.5 新 harness：`pi-image-cache`（`app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt`）

测的是 `ImageSize.kt` 的 `ByteBoundedLru`（Android-free，所以能编）：逐出顺序、字节计费、上限判定、`maxBytes <= 0`、超预算条目、权重夹取、`clear()`、**从不调用键的 `hashCode()`**，外加「40 轮 × 60 次随机操作 vs 独立参考实现」的模型对照（每次都比 命中集合 / 大小 / 字节账 / 最近使用顺序）。原样输出（末尾）：

```
== 6. the map never hashes a key ==
PASS  identity hit
PASS  equal-but-distinct hit
PASS  miss on a different payload
PASS  hashCode was never called on any key

== 8. the wiring `ByteBoundedLru` cannot see ==
PASS  PiImageCache exists and is bounded by the app's 32 MiB
PASS  PiImageCache charges the payload to the entry
PASS  PiImageCache never hashes the payload
PASS  ImageGridBlock.kt decodes through the cache
PASS  PiImageViewer.kt decodes through the cache

harness: OK (10837 checks)
```
第 8 组是**源文本断言**（`PiImageCache` 引用 `Bitmap`，本机编不了）：上限是那 32 MiB、载荷计费那一行在、没有 `payload.hashCode()`、两个解码点都走 `readThrough`。

**注册行**（`tools/run-app-pure-checks.sh`，本批次没有改这个文件——它正被别的批次改）：

```bash
run_harness pi-image-cache \
  app.pi.ui.blocks.PiImageCacheCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ImageSize.kt"
```

### 2.6 视觉行窗口的断言（探针，`/tmp/probe/items/window.txt`）

`ToolBodyText.kt` 引 Compose，bare-JVM harness 编不了它（同 `lineCount`/`headLines` 的处境），所以窗口规则**逐字切出**（`ExtractedWindow.kt`）与 pi 的 `truncateToVisualLines` 对照。原样输出：

```
== 1. the boundary rule equals pi's slice(-N) ==
compared 20000 (body, window) pairs; failures so far=0

== 2. the window is always exactly `max` visual lines (the row-height fix) ==
checked 2000 window geometries; failures so far=0

== 3. pi's own numbers on the probe's 2000-line shell body ==
  uniform 2000x34 chars: visual lines=2000 windowStart=69825 visibleRows=5
  ragged tail (10 long lines): visual lines=2018 windowStart=70576 visibleRows=5

== 4. degenerate inputs ==

WINDOW CHECKS OK (42005 assertions)
```

### 2.7 既有 harness（跑过，原样输出）

`tools/run-app-pure-checks.sh` 在本窗口**跑不完**（另一批次正在改这个脚本；10 分钟上限内无输出）。改成用同一套 kotlinc 配方**逐个直接编译并运行**本目录下的三个 harness：

```
=== ToolOutputParseCheck:
harness: OK
=== ImageSizeCheck:
harness: OK (all checks passed)
=== PiImageCacheCheck:
harness: OK (10837 checks)
```

`ImageSizeCheck` 有价值的一点是它对**我改过的两个文件做源文本断言**（每个 `decodePiImage` 必须落在 `piImageDecodeGate.withPermit` 里、单图盒必须来自 `singleImageBoxHeightPx`）：加缓存后计数仍是 1/1、盒仍来自报头，所以它照样通过。**新增的 `utf8ByteSizeExceeds`、`$$` 守卫、视觉行窗口没有既有 harness 覆盖**，§2.4/§2.6 的探针是它们的证据（窗口那条因为文件引 Compose 只能走探针，原因见 §5）。

---

## 3. 改动清单（全部在本批次自己的文件里，附回滚点）

| # | 文件 | 改了什么 | 为什么 | 可见语义 | 回滚点 |
|---|---|---|---|---|---|
| C1 | `ui/blocks/BlockChrome.kt` | 新增 `utf8ByteSizeExceeds(text, maxBytes)`：零分配、提前退出，与 `text.toByteArray(UTF_8).size > maxBytes` **逐字符等价**（含孤立代理项按 1 字节，与 Java 编码器一致） | 原式在**每次发布**把整个工具输出（pi 上限 50 KiB，我们上限 200 KiB）编码成临时 byte array 只为问一个布尔 | 无 | 删函数，还原调用点一行 |
| C2 | `ui/blocks/ToolCallBlock.kt` | `outputOversize` 改用 `utf8ByteSizeExceeds` | 同上 | 无（`length > max` 的提前退出把常见情况变成字段读：0.0004 ms vs 0.14 ms + 分配） | 一行 |
| C3 | `ui/blocks/ShellBlock.kt` | `painted` 增加 `expanded` 键：**折叠时不算尾巴**（`painted`/`hidden` 只在展开分支被读） | 折叠的 shell 卡一行正文都不画，却每次发布/每次重入付 `tailLines` 1.9–2.0 ms（pi 自己的折叠预览还是按宽度 **memo** 的，`renderers/bash.ts:77-81`） | 无（展开时画同一份文本；代价变成「第一次展开付一次」） | 去掉 `expanded` 键 + 还原 KDoc 段 |
| C4 | `ui/blocks/DiffBlock.kt` | `plan` 增加 `expanded` 键：**折叠时不算 `diffPlan`** | 折叠的 diff 卡不画 `plan`，却每次重入付 3.9–13.2 ms（行内 LCS 是大头） | 无（展开时同一批行，`plan.isEmpty()` 也只在展开分支用） | 去掉 `expanded` 键 |
| C5 | `ui/extension/ExtensionChrome.kt` | `boundedWidgetLines(widget.lines)` 与每行 `chromeSpans(line)` 进 `remember` | 这个栈在 composer 上下、跟着每次重组跑；11 行 ANSI 解析 0.72 ms/次 | 无 | 两行 |
| C6 | `ui/render/PiMarkdown.kt` | `applyMath` 里加 `BLOCK_MATH_MARKER = "$$"` 守卫：没有 `$$` 就跳过 display-math 那一趟正则 | 那一趟在 5.9 KB 正文上要 0.4–1.3 ms，而**没有 `$$` 时它必然无匹配**；含 `$` 的消息每次内容变化（流式=每 200 ms）都要跑 | 无（§2.4：4 018 篇文档 new vs old 逐字相同） | 删常量 + 还原 `applyMath` 三行 |
| C7 | `ui/blocks/ToolBodyText.kt` | 新增纯函数 `visualTailWindowStart(lineStarts, lineCount, maxVisualLines)`：pi 的 `slice(-N)` 起点 | 窗口的**边界规则**要有单一出处，且要能逐字切出来与 pi 对照 | 无（新函数，暂无别的调用者） | 删函数 |
| C8 | `ui/blocks/ShellBlock.kt` | 新增 `ShellPreviewBody`：展开态的预览用「最后 5 条**视觉**行」的窗口（`BoxWithConstraints` 取宽 + `rememberTextMeasurer` + `visualTailWindowStart`）；「展开全部」后不套窗口 | R3：行高在流式期间恒定，且窗口与 pi 1:1 | **有**：展开态的预览从「5 逻辑行」变成「5 视觉行」——这是要的（§1.2 的对照表），也是本批次唯一一处刻意改呈现的地方 | 还原调用点为 `MonoText(text = painted, …)` 两行 + 删 `ShellPreviewBody` |
| C9 | `ui/blocks/ImageSize.kt` | 新增纯类 `ByteBoundedLru<K,V>`（按字节计费、LRU 逐出、**从不哈希键**、`keys()` 诊断） | 图片缓存要能 bare-JVM 测；且不能对 MB 级载荷哈希（`String.hashCode()` 4 MiB = 22.8 ms） | 无（ImageSize.kt 仍是 Android-free；harness 会因它引 Android 而编译失败，这正是守卫） | 删类与 `keys()` |
| C10 | `ui/blocks/PiImageCache.kt`（**新文件**，父代理授权） | 进程级 bitmap 缓存：键 = 载荷 + 盒子，计费 = 载荷字节 + `allocationByteCount`，上限 32 MiB；`cached`/`store`/`readThrough`/`clear` | R5：滚动来回不再重复解码；失败不缓存（同 `PiGuestImageTransformer`） | 无：命中只是把值提前填上，`Pending`/`Ready` 几何不变 | 删文件 + 还原两个解码点的调用 |
| C11 | `ui/blocks/ImageGridBlock.kt`、`ui/blocks/PiImageViewer.kt` | 两处解码改走 `PiImageCache.readThrough`（仍在 `Dispatchers.IO` 且仍在门内） | 同上；查看器与网格各自一个盒子条目 | 无 | 各还原一处 |
| C12 | `app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt`（**新文件**） | `pi-image-cache` harness：LRU 策略 + 40×60 模型对照 + 源文本连线断言（10837 次） | 纯逻辑要能被 CI 跑；注册行见 §2.5 | 无（只增检查） | 删文件（注册行本批次未加，见 §5） |

| C13 | `ui/blocks/ImageSize.kt` | `ByteBoundedLru.getOrCompute`（命中不重算的不原子语义）、`TextMemo`（String→String 有界 memo，计费 = 输入+结果）、`IncrementalLineCount`（只见增长的行数增量） | R1/R2 的跨组合缓存与增量计数都需要**能被 bare-JVM 执行**的纯实现 | 无 | 删三个类 |
| C14 | `ui/blocks/ParseCaches.kt`（**新文件**） | 进程级：`stripped`（8 MiB）、`tail`（4 MiB）两个 `TextMemo`；无转义的正文直接返回、不进缓存 | R1：`ShellBlock` 的 strip/tail 在行被回收后不再重跑 | 无 | 删文件 + 还原 `ShellBlock` 两处调用 |
| C15 | `ui/blocks/DiffBlock.kt` | 加 `DiffPlanCache`（4 MiB）：键 =(行 key, diff 文本, 行上限)，权重 = 行文本×2 + 每行/每 run 常数 | R1 的最大项（3.9–13.2 ms） | 无（同一输入出同一批行） | 删 object + 还原 `plan` 一行 |
| C16 | `rpc/Ansi.kt` | `strip` 的带色路径改为**整段追加**（先扫 ESC、再把非 ESC 区间一次 `append`） | R2：60 KB 带色结果 2.85→1.05 ms（同一进程内配对测量，2.7×） | 无（4 089 篇等价；无转义时仍返回同一实例） | 还原旧循环 |
| C17 | `app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt`（**新文件**） | `text-cache` harness：`getOrCompute` 命中不重算、`TextMemo` 计费/逐出、`IncrementalLineCount` vs `lineCount`（18 000 次增长 + 12 000 次改写）+ 源文本连线断言 | 纯逻辑要能被 CI 跑；注册行见 §7 | 无（只增检查） | 删文件 |

**没有改、但读过的**（都在 §6 交 patch 或明确不改）：`Ansi.strip`（`rpc/`，不是我的文件）、`FindBlock`/`LsBlock` 的 subject 记忆化、`ReadBlock`/`WriteBlock` 的 `take` 分配、`ChatScreen` 的附件缩略图解码点（不是我的文件，见 §6-P3）、`RowHeightCache`/`TranscriptRowHeight`/`TailFollow`/`ChatScreen`（别人的文件）。
| C7 | `ui/blocks/ToolBodyText.kt` | 新增纯函数 `visualTailWindowStart(lineStarts, lineCount, maxVisualLines)`：pi 的 `slice(-N)` 起点 | 窗口的**边界规则**要有单一出处，且要能逐字切出来与 pi 对照 | 无（新函数，暂无别的调用者） | 删函数 |
| C8 | `ui/blocks/ShellBlock.kt` | 新增 `ShellPreviewBody`：展开态的预览用「最后 5 条**视觉**行」的窗口（`BoxWithConstraints` 取宽 + `rememberTextMeasurer` + `visualTailWindowStart`）；「展开全部」后不套窗口 | R3：行高在流式期间恒定，且窗口与 pi 1:1 | **有**：展开态的预览从「5 逻辑行」变成「5 视觉行」——这是要的（§1.2 的对照表），也是本批次唯一一处刻意改呈现的地方 | 还原调用点为 `MonoText(text = painted, …)` 两行 + 删 `ShellPreviewBody` |
| C9 | `ui/blocks/ImageSize.kt` | 新增纯类 `ByteBoundedLru<K,V>`（按字节计费、LRU 逐出、**从不哈希键**、`keys()` 诊断） | 图片缓存要能 bare-JVM 测；且不能对 MB 级载荷哈希（`String.hashCode()` 4 MiB = 22.8 ms） | 无（ImageSize.kt 仍是 Android-free；harness 会因它引 Android 而编译失败，这正是守卫） | 删类与 `keys()` |
| C10 | `ui/blocks/PiImageCache.kt`（**新文件**，父代理授权） | 进程级 bitmap 缓存：键 = 载荷 + 盒子，计费 = 载荷字节 + `allocationByteCount`，上限 32 MiB；`cached`/`store`/`readThrough`/`clear` | R5：滚动来回不再重复解码；失败不缓存（同 `PiGuestImageTransformer`） | 无：命中只是把值提前填上，`Pending`/`Ready` 几何不变 | 删文件 + 还原两个解码点的调用 |
| C11 | `ui/blocks/ImageGridBlock.kt`、`ui/blocks/PiImageViewer.kt` | 两处解码改走 `PiImageCache.readThrough`（仍在 `Dispatchers.IO` 且仍在门内） | 同上；查看器与网格各自一个盒子条目 | 无 | 各还原一处 |
| C12 | `app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt`（**新文件**） | `pi-image-cache` harness：LRU 策略 + 40×60 模型对照 + 源文本连线断言（10837 次） | 纯逻辑要能被 CI 跑；注册行见 §2.5 | 无（只增检查） | 删文件（注册行本批次未加，见 §5） |

| C13 | `ui/blocks/ImageSize.kt` | `ByteBoundedLru.getOrCompute`（命中不重算的不原子语义）、`TextMemo`（String→String 有界 memo，计费 = 输入+结果）、`IncrementalLineCount`（只见增长的行数增量） | R1/R2 的跨组合缓存与增量计数都需要**能被 bare-JVM 执行**的纯实现 | 无 | 删三个类 |
| C14 | `ui/blocks/ParseCaches.kt`（**新文件**） | 进程级：`stripped`（8 MiB）、`tail`（4 MiB）两个 `TextMemo`；无转义的正文直接返回、不进缓存 | R1：`ShellBlock` 的 strip/tail 在行被回收后不再重跑 | 无 | 删文件 + 还原 `ShellBlock` 两处调用 |
| C15 | `ui/blocks/DiffBlock.kt` | 加 `DiffPlanCache`（4 MiB）：键 =(行 key, diff 文本, 行上限)，权重 = 行文本×2 + 每行/每 run 常数 | R1 的最大项（3.9–13.2 ms） | 无（同一输入出同一批行） | 删 object + 还原 `plan` 一行 |
| C16 | `rpc/Ansi.kt` | `strip` 的带色路径改为**整段追加**（先扫 ESC、再把非 ESC 区间一次 `append`） | R2：60 KB 带色结果 2.85→1.05 ms（同一进程内配对测量，2.7×） | 无（4 089 篇等价；无转义时仍返回同一实例） | 还原旧循环 |
| C17 | `app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt`（**新文件**） | `text-cache` harness：`getOrCompute` 命中不重算、`TextMemo` 计费/逐出、`IncrementalLineCount` vs `lineCount`（18 000 次增长 + 12 000 次改写）+ 源文本连线断言 | 纯逻辑要能被 CI 跑；注册行见 §7 | 无（只增检查） | 删文件 |

**没有改、但读过的**（都在 §6 交 patch 或明确不改）：`Ansi.strip`（`rpc/`，不是我的文件）、`FindBlock`/`LsBlock` 的 subject 记忆化、`ReadBlock`/`WriteBlock` 的 `take` 分配、`ChatScreen` 的附件缩略图解码点（不是我的文件，见 §6-P3）、`RowHeightCache`/`TranscriptRowHeight`/`TailFollow`/`ChatScreen`（别人的文件）。

**验证**：`python3 tools/check-nested-comments.py` → `nested-comments: OK (272 Kotlin file(s) scanned)`；`tools/typecheck.sh` 见 §5 的说明（本窗口整树有 6 个 error，**全部在别人的文件里**，我的文件 0 个）。

---

## 4. 真机判据（怎么量）

统一手法：系统屏幕录制 → 抽帧（`ffmpeg -i rec.mp4 -vf fps=240 -vsync 0 f/%05d.png`），跟踪屏幕上一个**短且唯一**的字符串的上边缘 y；一帧内 y 变化 > 4 px 记一次「位移」。更省事的肉眼判据：**手指按住不动时不许有任何像素移动**。

**S7（R3 的验收：展开的 shell 预览是「5 视觉行」窗口）**
1. 打开 `app.tools.expand`（工具卡默认展开），让 pi 跑一条会持续输出、且**新行会很长**（会自动折行）的命令，例如 `for i in $(seq 1 400); do printf 'line %s %s\n' "$i" "$(head -c $((i*7)) </dev/zero | tr '\0' x)"; sleep 0.05; done`，把那张 shell 卡停在屏幕中段。
2. 录屏 3 秒，跟踪卡片**下边缘**与卡片下方紧跟的一行。
3. 通过：两者在整个流式期间**不动**（±2 px）；卡片正文恒为 5 行文字高度。改前：尾部换行数变化时下边缘跳 ±1 视觉行。
4. 窗口边界（与 pi 一致）：把同一段输出在**另一台宽度不同的设备**（或分屏改变宽度）上看，窗口内容应是「最后 5 个折行后的行」，即与 pi 在其终端宽度下的窗口同构；宽度变化后立刻按新宽度重算一次（观感：窗口内容随宽度整块重排一次，之后不动）。
5. 探针可复算的部分已在 §2.6 钉死（20 000 组对照 + 2 000 组几何）。

**S8（R5 的验收：同一张图不再重复解码）**
1. 一条含 2–4 张截图的会话；先用 Perfetto（或 `simpleperf`）抓一段 syscall/线程轨迹，或临时在 `decodePiImage` 调用处加一条 **debug-only** 计数日志（例如 `Log.d("pi-img", "decode ${'$'}{sample.width}x${'$'}{sample.height}")`，仅 debug 构建、随改随撤）。
2. 快速上下甩 5 个来回后停住。
3. 通过：**同一条目（同载荷同盒子）的解码次数 = 1**（改前 = 每次重入 +1）；`PiImageCache` 的 `size`/`bytes` 不随滚动时长增长（可用同一日志在每次 store 时打 `bytes`）。
4. 内存判据：`adb shell dumpsys meminfo <pkg>` 在「甩 10 个来回」前后的 **Native Heap / Graphics** 差值应在一个缓存上限（≤32 MiB 的记账，实际位图占用是它的子集）之内，且**不随甩动次数线性增长**。
5. 显示语义：图片仍然「首次进入时才出现」（命中只是更快），解码失败仍显示标签、且下次重入会重试（失败不缓存）。

**S1（R3 的核心：流式期间卡片高度不稳；窗口已修，作为回归基线保留）**
1. 让 pi 跑一条会持续输出的 `bash`（例如 `for i in $(seq 1 400); do echo "line $i ..."; sleep 0.05; done`），把那张 shell 卡**展开**并停在屏幕中段。
2. 录屏 3 秒，跟踪卡片**下边缘**（或卡片下方紧跟的一行）。
3. 通过：下边缘在流式期间**不动**（±2 px）。现在：尾部 5 条逻辑行的换行数变化时，下边缘会跳 ±1 视觉行。
4. 量化：`跳变次数 / 3 秒` 应与「尾部某些行变长/变短」的时刻数一致。

**S2（R1：折叠的 diff/工具卡滚进视口的顿）**
1. 一条含 ≥5 张 **diff 卡**（`edit` 工具）与 ≥5 张 `bash` 卡的会话，全部处于折叠状态（`app.tools.expand` 关）。
2. 从下往上匀速滑过这些卡片（一次滑动跨 5 张以上），录屏。
3. 通过：抽帧后每帧都落在一帧预算内（60 Hz → 无「同一帧重复 3 次以上」的抖动）；主观上不再「出现卡片就顿一下」。
4. 对比口径：改前 vs 改后同一段滑动，数**丢帧数**（`ffmpeg` 抽帧后统计连续重复帧）。**这是本次改动最直接的验收**。

**S3（R2：流式 bash 卡在折叠状态下的每帧成本）**
1. 跑一条输出很大的命令（让结果接近 pi 的 2000 行/50 KiB 上限），卡片**保持折叠**，同时用另一只手滚动列表（或让它在屏幕上跟随）。
2. 通过：滚动帧率与「空会话」时无差别。现在：折叠时每 200 ms 仍有 `Ansi.strip` + `lineCount` ≈2.7 ms（改前 ≈4.7 ms）。
3. 若要量化：debug 构建在 `ShellBlock` 里打一行「本次组合的 `System.nanoTime()` 差」，对照 `Choreographer` 的帧间隔。

**S4（R4：高亮代码块进视口的两遍布局）**
1. 助手消息里放一个 **<400 行、<64 KB 的代码块**（例如 300 行 Kotlin），从上方缓慢滚进视口。
2. 通过：代码块**一次**落下（可以接受「先无色后有色的换色」，但那一下不许改变高度/位置）。观察点：代码块下面的行在它着色前后**不得**移动（颜色变化不该动几何；若看到移动，说明是我漏掉的高度变化，需要抓帧）。
3. 顺带验 500 行：一个 500 行的块**永远不着色**（阈值语义），不该有后台请求。

**S5（R5 的基线，已被 S8 取代）**
1. 一条含 2–4 张截图的会话，快速上下甩 5 个来回，然后停在图片上。
2. 通过：图片出现不晚于**第一次**滑过时的时延。**这一条已被 S8 取代**（S8 给的是可量化的解码次数与内存判据）。

**S6（扩展部件）**
1. 装一个用 `ctx.ui.setWidget` 的扩展（或让 `pi-android` 的桥发一条 `setWidget`），widget 显示在 composer 上方。
2. 在同一屏里持续输入文字 / 让 pi 流式输出。
3. 通过：widget 文本不参与每帧的解析（观感上：输入与滚动不掉帧）。量化：debug 打点 `chromeSpans` 调用次数，输入 20 个字符应只触发 0 次（现在每次重组都可能 +11）。

---

## 5. 本窗口的验证边界（如实）

1. **`tools/typecheck.sh` 整树只剩 3 条已知假阳性，我的文件 0 error**：收工前在**最终代码**上跑的两次全量结果都是
   `app/src/main/kotlin/app/pi/settings/DiagnosticsReport.kt:7/156` 的 `unresolved reference 'BuildConfig'`（×3，`BuildConfig` 要 AAPT2 生成，本脚本不生成）。`ui/blocks/**`、`ui/render/**`、`ui/chat/**`、`ui/extension/**`、`rpc/Ansi.kt` 里都没有 error。
   期间另一个批次的全量跑曾在 `ui/blocks/ParseCaches.kt` 上看到 4 条 `getOrCompute`/`text`/`max` 相关的错——那是**编辑窗口内的中间态**：我先把 `DiffPlanCache` 改成走 `ByteBoundedLru.getOrCompute`，随后才把该方法加到 `ByteBoundedLru` 上（`TextMemo` 也是两步改的）。最终代码里 `ByteBoundedLru.getOrCompute`（`ImageSize.kt:428`）、`TextMemo.getOrCompute`（`:641`）、`ParseCaches.tail` 的 `tailLines(key.text, key.max)` 都解析正常，两次全量跑均无 ParseCaches 相关诊断。
2. **`tools/run-app-pure-checks.sh` 没跑完**：本窗口它在 10 分钟上限内没有输出（另一批次正在改它 + 机器负载）。我用同一套 kotlinc 配方逐个跑了它覆盖本目录的 harness（§2.7 原样输出），并新增了 `pi-image-cache` 的 harness 文件；**它的注册行没有加**（那要改 `tools/run-app-pure-checks.sh`，正是别的批次在动的文件），行文见 §2.5。
3. **Compose 的测量/重组只能推理**：`remember` 的生命周期、`LazyColumn` 的复用、`Text` 的 `maxLines` 按视觉行截断，这些是框架语义；本机没有 Compose 编译器插件，编不了任何 Composable（`tools/typecheck.sh` 只做前端）。
4. **绝对毫秒数的机器依赖性**：见 §0/§2 的口径说明（标定值 1.6–2.2 ms，跨运行 2–3 倍浮动）。所有结论都建立在「同一轮内的相对比较」与「±1 个数量级」上。
5. **`Markdown(...)` 的 loading 分派**：我读的是 `javap` 出来的形参顺序（按 `Loading`/`Error` 两个分支各自 `aload` 的是哪个 `Function3` 反推），不是源码；结论「loading 槽拿到的是行自己的 modifier」与「默认槽是 `Box`」两条都有字节码依据，但「因此首帧一定是 0 高」里「我们的 `padding` 不参与」这一环是推断（依据：同一 artifact 里 `loading` 的调用参数就是那个 `Modifier` 形参）。
6. **真机数值一个都没测**：本容器没有设备 shell/ADB（桥只到屏幕与文件层），§4 的判据需要人工或另一台有 adb 的机器执行。
7. **视觉行窗口的 Compose 一侧只推理过**：窗口的**边界规则**有 42 005 条断言（§2.6），但「`remember(text, widthPx, style, measurer)` 在同宽度下不重算」「`TextMeasurer` 与下面那个 `Text` 用同一宽度/字体解析器」是读 Compose 语义得出的，本机跑不了 Compose（`tools/typecheck.sh` 不跑 Compose 编译器插件）。若真机上发现窗口与预期不符，先看 §4-S7 的第 4 条（宽度变化后是否重排），再回滚 C8。
8. **图片缓存的内存上限没有上机测过**：32 MiB 是照 `PiGuestImageTransformer` 的同名常量取的（项目自己的先例），`allocationByteCount` 在不同 Android 版本/位图配置下的口径由平台保证，但我没有在设备上量过实际占用（§4-S8 第 4 条就是这条判据）。

---

## 6. 落地情况与剩下的 patch

**已落地的两项（父代理批准）**：

* **① shell 尾巴 = pi 的视觉行窗口** → C7/C8（§1.2 的对照表、§2.6 的 42 005 条断言）。**这是本批次唯一一处刻意改呈现的地方**（预览从 5 逻辑行变成 5 视觉行，与 pi 一致），回滚点：`ShellPreviewBody` 的调用点还原为 `MonoText(text = painted, …)`。
* **② 图片进程级 bitmap 缓存** → C9–C12：`ui/blocks/PiImageCache.kt`（新文件）+ `ByteBoundedLru`（`ImageSize.kt`，纯逻辑）+ 两个解码点 + 新 harness。上限 32 MiB（取项目自己的 `PiGuestImageTransformer.MAX_TOTAL_BYTES`，理由见 §1.5）；键 = 载荷 + 盒子；**命中不改任何显示语义**；失败不缓存；进程级、不持 `Context`、自限、无定时器。注册行见 §2.5（未加，见 §5）。

**仍然交 patch（不属本批次权限或需要拍板）**：

**P1（补丁，`rpc/Ansi.kt`，不是我的文件）· `Ansi.strip` 的带色路径**
现状单趟扫描 + `StringBuilder`，实测 2000 行带色（1/5 行有色）2.4 ms；无 ESC 的路径 0.28 ms/272 KB。若要把 R2 再压一档：①在 `strip` 里一次性定位第一个 ESC，②把连续非 ESC 区间用 `append(CharSequence, start, end)` 整段追加，替代逐字符 `append(c)`。纯实现替换、输出不变（要 `:rpc` 的测试跑一遍）。

**P2（结构性）· 跨组合的解析结果缓存**（不属于我的文件：`render/RowHeightCache.kt` 那种形态）
把「(item.key, 内容哈希) → 解析产物」放进一个有界 LRU，供 `ShellBlock.bodyText`、`DiffBlock.plan`、`ToolOutputParse.*` 复用，让 R1 从「每次重入 2–13 ms」降到「每内容一次」。**不建议在 `blocks/*` 里各做一份**（会变成 6 个各不相同的缓存）。

**P3（补丁，`screens/ChatScreen.kt`，不是我的文件）· composer 的附件缩略图也走 `PiImageCache`**
`ChatScreen.kt:3819` 的 `piImageDecodeGate.withPermit { decodePiImage(image.base64, thumbPx, thumbPx) }` 与我的两个点同形；把 `decodePiImage(...)` 换成 `PiImageCache.readThrough(image.base64, thumbPx, thumbPx) { … }` 即可（同一个盒子 = 同一条目）。`ImageSizeCheck` 的源文本计数是 `1/1`，改成 `readThrough` 后仍是 `1/1`。

**P4（补丁）· `lineCount` 的增量**（`ui/blocks/`）
`ShellBlock`/`ToolCallBlock` 每次发布重算 `lineCount(输出)`（实测 2000 行 0.3 ms、200 KB 4.1 ms）。把「上一次的输出 + 上一次的行数」放进 `remember`，只对新增后缀计数；前提是确认 reducer 的工具更新是**纯追加**（`rpc/…/Transcript.kt` 的工具更新路径），确认后再做。

**P5（记录不改）· `FindBlock`/`LsBlock` 的 subject、`ReadBlock`/`WriteBlock` 的 `take`、`ToolHeader` 的默认参数**
都是 µs 级分配，改了只有噪音收益，见 §1.1/§1.7。

---

## 7. 追加落地（第二轮）：跨组合缓存、`strip`、增量计数

四条都按你要的标准做的：**有界、计费诚实、命中不改语义、失败不缓存**（后三条里 `Ansi.strip`/`tailLines`/`diffPlan` 都是全函数，不存在"失败"这一支；缓存本身也不吞异常——`compute` 抛就抛、不落盘）。

**① R1 跨组合解析缓存（最重要的一条）**
- `DiffPlanCache`（`DiffBlock.kt`，**4 MiB**）：`diffPlan` 是条目层最贵的一次解析（3.9–13.2 ms），而结果是"一次组合"的状态；键 = `(行 key, diff 文本, MAX_DIFF_ROWS)`，权重 = 每行文本×2 + 每行 64 B + 每个行内 run 20 B（常数写在代码里，`hunks` 不参与键的理由也写在 KDoc：它就是 `diffText` 的投影，和原来的 `remember` 键同一个假设）。
- `ParseCaches.stripped`（**8 MiB**）：`ShellBlock` 的 `Ansi.strip`。**无转义的正文直接返回、不进缓存**——`strip` 本来就返回入参本身，缓存它等于为一份不存在的拷贝计费。
- `ParseCaches.tail`（**4 MiB**）：`tailLines`，键 = `(文本, 行数)`（`TailKey`，不哈希文本）。它只覆盖"收回后被重组的卡片"：流式期间每次发布都是新文本 → 必然未命中（这没问题，命中场景就是滚回去）。
- 预算依据：文本解析（2.4 ms 级）比一次解码（几十 ms）便宜，所以取图片缓存 32 MiB 的零头；两个 memo 都按 `(输入 + 结果) × 2` 字节计费（UTF-16 是 2 B/字符），68 KB 的 shell 结果 ≈0.27 MiB/条 ⇒ strip 大约容 30 条。最坏情况三个缓存全满 ≈16 MiB，是图片缓存的一半。
- 生命周期：进程级 object、只持 `String`、不持 `Context`、无定时器、自限；`clear()` 留给将来的 trim 钩子（今天没人调）。

**② 带色 `Ansi.strip`（`rpc/Ansi.kt`）**：改成"先扫 ESC，再把每段非 ESC 区间整段 `append`"。同一进程内配对测量：60 090 字符、1/5 行带色的 2000 行日志，**2.85 ms → 1.05 ms（2.7×）**；4 089 篇（含 16 色/256 色/真彩/粗体/下划线/未终止序列/`ESC]` 非 CSI/中日韩/随机拼接）输出**逐字相同**，且无转义时仍返回同一实例。`rpc/src/test/.../AnsiTest.kt` 是 JUnit、要 Gradle（本窗口不许跑），这份探针是等价的验证（`/tmp/probe/items/ansi.txt`）。

**③ `lineCount` 增量（`IncrementalLineCount`）**：`ToolCallBlock` 的「N 行」现在带上一次计数走——工具输出只增不减，所以数新增后缀里的换行即可；一旦不是前缀（结果被替换、行被回收后装了别的条目）就整段重数。等价性由 **18 000 次随机增长 + 12 000 次随机改写**（追加/替换/截断/清空）对照 `lineCount` 钉死；`ShellBlock` 的 `bodyText` 变一行时 `lineCount` 仍是整段（0.3 ms 级，没动；只有 `ToolCallBlock` 这条流式 4.1 ms 级的路径必须改）。

**④ `ChatScreen` 缩略图（不归我，patch 给你转）**

```kotlin
// app/src/main/kotlin/app/pi/screens/ChatScreen.kt:3819 附近
// 现在：
value = withContext(Dispatchers.IO) {
    piImageDecodeGate.withPermit { decodePiImage(image.base64, thumbPx, thumbPx) }
}
// 改成（`PiImageCache` 已在 ui/blocks 公开，键含盒子，所以缩略图自成一条目）：
value = withContext(Dispatchers.IO) {
    PiImageCache.readThrough(image.base64, thumbPx, thumbPx) {
        piImageDecodeGate.withPermit { decodePiImage(image.base64, thumbPx, thumbPx) }
    }
}
```
连线断言仍然成立（`ImageSizeCheck` 用源文本数 `decodePiImage(image.base64` 与 `piImageDecodeGate.withPermit` 的出现次数，改后仍是 1/1）。

**注册行（新的纯逻辑 harness）**：

```bash
run_harness text-cache \
  app.pi.ui.blocks.TextCacheCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ImageSize.kt"
```

**验证**：`TextCacheCheck` → `harness: OK (30034 checks)`；`ToolOutputParseCheck`/`ImageSizeCheck`/`PiImageCacheCheck` 全部仍 OK；`check-nested-comments.py` OK；typecheck 见 §5。

**真机判据（"什么时候还会重算"）**：跨组合缓存只在**内容变化**时重算——流式期间每次发布都是新内容 ⇒ 每条发布重算一次（这是不可避免的，也和今天一样）；**滚出去再滚回来、切屏回来、展开/收起**这些"内容没变"的重组现在不重算。验法：debug 在 `DiffPlanCache.plan`/`ParseCaches.stripped`/`ParseCaches.tail` 的 `compute` 里各打一条日志（只在真正计算时打），然后 ①把一张折叠的 diff 卡滚出屏幕再滚回来并展开 → **0 条**；②同一条 20 行的 `edit` 卡来回滚 5 次 → **0 条**；③流式 `bash` 期间 → 每条发布 1 条（改前也是 1 条，但那个 1 条现在更便宜：strip 2.7×）。内存侧：`ParseCaches.bytes + size` 打进同一行日志，滚 10 个来回后应停在预算内、不随滚动时长增长。
