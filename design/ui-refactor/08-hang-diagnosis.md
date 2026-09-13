# 08 · 「一进会话 / 一发消息就卡死」根因报告

> **本机没有编译任何仓库代码。** 只读代码、只读本机缓存里的第三方字节码、只读第三方库 v0.45.0 的源码，
> 并在 `/tmp` 下跑了三个 bare-JVM 探针（不含 Compose，不含仓库文件）。`git` 只读。
> 未改动 `app/` 或 `tools/` 下任何既有文件；本文件是唯一新增产物。
> 行号以**符号名**为准：`ChatScreen.kt` / `PiSessionViewModel.kt` / `PiRoot.kt` 在本报告写作期间
> 正被别的代理改动，本文件里的行号是**核对当天**的值（`ChatScreen.kt` 自 `:850` 起、`PiSessionViewModel.kt`
> 自 `:1060` 起已各下移约 10 行）；改代码时按符号找，别按行号找。

---

## 1. 结论一句话

**`ui/render/PiMarkdown.kt` 每次执行都把第三方 markdown 渲染器的三个「默认对象」重新造一遍
（`GFMFlavourDescriptor` / `MarkdownParser` / `ReferenceLinkHandlerImpl`），而这三个对象正是
`rememberMarkdownState` 的 `remember` 键、也是它内部 `Input.equals` 的比较字段 —— 于是
「**markdown 行每次重组 = 整篇文档重新解析一次（后台线程）+ 整篇文档重新组合一遍（主线程）**」，
哪怕文本一个字节都没变。**

转录每收到一个流式事件就重组一次（`state.revision` / `state.transcript` 变），所以主线程上的
markdown 重渲染量 = **每次发布 × 屏上所有 markdown 行的文档大小**。量级随会话内容线性增长：
一屏几行、每行十几到几十 KB 时，一次发布的 markdown 组合工作就是几十到几百毫秒，
而发布频率是 10–50 次/秒 ⇒ **帧线程永远不空闲 ⇒ 输入分发超时 ⇒ 系统弹「应用无响应」**。

同一机制解释两个触发点，且天然带来「第二条比第一条严重」：

* **发消息 → 卡**：发送会写 `draft`/`reArmTail`/转录，每一步都让转录重组一次；从第二条起，
  屏上多了**已经定稿的**第一篇长回答（+ 工具卡），一次发布要重渲染的是「N 篇文档」而不是「1 篇」。
* **从历史列表进会话 → 卡**：整条会话一次性灌进来，随后 attach 尾部的十几次 `_state.value` 写入
  （`refreshState`/`refreshCommands`/`refreshPrefs`/`refreshTheme`…）每一发都触发一轮
  「所有可见 markdown 行 × 整篇重解析 + 整篇重渲染」，连着十几轮、中间没有一个空闲帧。

---

## 2. 证据

### 2.1 机制 A（核心，可证）：markdown 行「每重组一次 = 重解析 + 全量重渲染」

**A1. App 侧的唯一调用点，且没有传那三个对象。**
`app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt:131-187`：

```kotlin
Markdown(
    content = content, colors = colors, typography = typography,
    padding = piMarkdownPadding, dimens = piMarkdownDimens,
    imageTransformer = imageTransformer, components = components,
    retainState = true,
    animations = markdownAnimations(animateTextSize = { this }),
    modifier = modifier,
)
```
没有 `flavour` / `parser` / `referenceLinkHandler` 三个参数。全仓只有这一个 `Markdown(` 调用点
（`grep -rn "Markdown(" app/src/main/kotlin/app/pi/ui/render/*.kt`），入口是 `PiMarkdownText`，
它的唯一转录调用者是 `ui/blocks/AssistantTextBlock.kt:53-56`。

**A2. 库 v0.45.0 的三个默认值是在 `Markdown(...)` 自己的函数体里现造的。**
版本锚点：`gradle/libs.versions.toml:18`（`markdown = "0.45.0"`）。
库源码（v0.45.0，`multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/compose/Markdown.kt`）：

```kotlin
flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor(),
parser: MarkdownParser = MarkdownParser(flavour),
referenceLinkHandler: ReferenceLinkHandler = ReferenceLinkHandlerImpl(),
...
immediate: Boolean = LocalInspectionMode.current,
```

本机缓存字节码同证 —— `build/typecheck/extra/aar/multiplatform-markdown-renderer-android/classes.jar` 里
`com/mikepenz/markdown/compose/MarkdownKt.class` 的 `Markdown(String, …)` 方法体内：

```
1152: iload 25 / 1154: bipush 64 / 1156: iand / 1157: ifeq 1185      ← if (($default & 64) != 0)
1160: new org/intellij/markdown/flavours/gfm/GFMFlavourDescriptor
1200: invokespecial org/intellij/markdown/parser/MarkdownParser."<init>"
1401: new com/mikepenz/markdown/model/ReferenceLinkHandlerImpl
```
且这一段整体被 `Composer.startDefaults()` … `Composer.endDefaults()` 包住（字节码 `636:`/`1264:`）。
**Compose 编译器不会把默认值表达式放进 `remember`** —— 只要 `Markdown(content=…)` 被执行一次，
这三个对象就是三个新实例。

**A3. 三个类都没有 `equals`/`hashCode`。**
`javap -p` 计数：`org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor` → 0；
`org.intellij.markdown.parser.MarkdownParser` → 0；`com.mikepenz.markdown.model.ReferenceLinkHandlerImpl` → 0。
⇒ 它们只有**引用相等**，而 A2 保证引用每次都是新的。

**A4. 这三个对象恰好是 `remember` 的键、也是 `Input.equals` 的字段。**
`.../model/MarkdownState.kt` 的 `rememberMarkdownState`：

```kotlin
val input = remember(content, lookupLinks, flavour, parser, referenceLinkHandler, retainState) { Input(...) }
val currentInput by rememberUpdatedState(input)
LaunchedEffect(Unit) {
    snapshotFlow { currentInput }.conflate().collect { newInput -> state.updateInput(newInput); state.parse() }
}
```
`Input` 是 data class，`equals` 逐字段比，**包含 `flavour` / `parser` / `referenceLinkHandler`**。
⇒ A2+A3 让 `remember` 每次都 miss ⇒ 新的 `Input` ⇒ `rememberUpdatedState` 的值真的变了 ⇒
`updateInput` 不早退（`if (input == newInput) return`）⇒ **`state.parse()` 被重新提交**。

**A5. 一次 parse 的代价是整篇文档；一次 `Success` 的代价是整篇文档重渲染。**
`parse()` = `withContext(Dispatchers.Default) { parseBlocking() }`（异步，✓ 不占主线程）；
`parseBlocking()` = `input.parser.buildMarkdownTreeFromString(content)` + 递归 `lookupLinkDefinition`
（`lookupLinks` 默认 **true**，即每篇文档再走一遍全树）。
`stateFlow.value = State.Success(...)` 一到，`Markdown(markdownState, …)` 里的
`markdownState.state.collectAsState()` 就重组，落到：

```kotlin
@Composable fun MarkdownSuccess(state: State.Success, components: MarkdownComponents, modifier: Modifier) {
    Column(modifier) { state.node.children.forEach { node -> MarkdownElement(node, components, state.content) } }
}
```
**整篇文档的每一个顶层节点都变成一个 composable，装在普通 `Column` 里，没有任何虚拟化**；
每个文本节点的 `AnnotatedString` 构建与文字排版都在主线程。这一段是本 App 反复踩的同一条事实
（仓库自己也写过：`docs/streaming-review.md` §2.7 与 §4.2「注解与渲染仍在主线程、每次该行重组时做 O(文档长度)」）。

### 2.2 测量（真实数字；探针在 `/tmp`，源码见 §2.4）

用本机缓存里的真库 `build/typecheck/extra/markdown-jvm-0.7.5.jar`（`GFMFlavourDescriptor` + `MarkdownParser`，
即 App 实际走的那条解析路径）在 bare JVM 上量 `buildMarkdownTreeFromString`：

```
doc   5219 chars (    5 KB)  parse=  8.95 ms  totalASTnodes=  1026  rootChildren(Column items)=  141  ~1.71 us/char
doc  15433 chars (   15 KB)  parse= 13.79 ms  totalASTnodes=  3021  rootChildren(Column items)=  414  ~0.89 us/char
doc  51260 chars (   50 KB)  parse= 22.97 ms  totalASTnodes=  9976  rootChildren(Column items)= 1365  ~0.45 us/char
doc 153701 chars (  150 KB)  parse= 58.02 ms  totalASTnodes= 29811  rootChildren(Column items)= 4080  ~0.38 us/char
```

仓库里真实的 markdown（`design/ui-refactor/02-real-content.md`，47 KB）：

```
02-real-content.md (real, 73KB)  chars=  47322  parse= 29.95 ms  astNodes=  7349
```

读法（这是关键的三步换算）：

1. **一次 parse** ≈ 9–58 ms（5–150 KB 文档，桌面 JVM，已预热）。手机通常再慢 2–5×。
2. **一次重渲染**的 composable 数量 = `rootChildren`（141 / 414 / 1365 / 4080），
   外加整篇的 `AnnotatedString` 构建 + 文字排版，全部在主线程。
   这一半没有真机测不了，但节点数是硬数字。
3. 转录**每收到一个非节流事件就发布一次**（`PiEngineSession.kt:575-604` 的 `foldEvent`：
   只有 `response` 和被 F8 节流的 `tool_execution_update` 不发布），
   即流式期间 10–50 次/秒 × 「屏上 markdown 行数 × 该行文档」= 主线程被占满。

换一个说法：**一屏 4 行 15 KB 的回答，一次发布就要在主线程上重建约 1656 个 composable 并排版 60 KB 文字。**
这个量在一条 delta 到达的间隔里做不完，于是下一帧继续做，帧线程永不空闲。

### 2.3 机制 B（重组从哪来）与「第二条更卡」

**B1. 一次发布 = 一次 `ChatBody` 重组。**
`ui/screens/ChatScreen.kt:179`（`val state by session.state.collectAsState()`）→ `:190`（`ChatBody(state = state, …)`）；
`ui/PiSessionViewModel.kt:1173-1179` 收 `engine.publication` → `:1318-1363` `syncTranscript` 写
`_state.value = _state.value.copy(transcript = …, revision = …)`；`revision` 变化 ⇒ `UiState` 不等 ⇒ ChatBody 重跑。
（`docs/streaming-review.md` §2.7 已经写死了这条：「`ChatBody` 会重组是**必要**的」。）

**B2. 可见行会被重新调用。** `ChatScreen.kt:968-1031` 的 `itemsIndexed` 项 lambda 捕获了
`hiddenCount`、`searchMatches`（`List<Int>`，`:393`）、`searchCursor`、`prefs`、`toolsExpanded`、
`session`；`BlockRenderer` 的三个回调（`:1010`、`:1014`、`:1023`）都捕获 `session`
（`PiSessionViewModel` 的公开属性是 `StateFlow`，Compose 判它 unstable）。捕获了 unstable 值的 lambda
不会被强跳过记忆化 ⇒ `BlockRenderer` 的实参不相等 ⇒ 它重跑 ⇒ `AssistantTextBlock` ⇒ `PiMarkdownText` ⇒ `Markdown(…)`。
**这一条是推断**（本机没有 Compose 编译器，判不了跳过），见 §3-①。

即便如此，最保守的读法也成立：**正在流式的那一行，`item` 实例每次都变**，那一行必然重跑
`PiMarkdownText` → `Markdown(…)` → A4 的「新 `Input`」→ 重解析 + 重渲染。也就是说
**机制 A 至少对「流式中那一行」是逐 delta 成立的**，不需要依赖跳过推断。

**B3. 为什么「第二条消息」比第一条更容易卡（明确回答）。**

* 第一条之前 `state.transcript` 是空的：走 `ChatScreen.kt:896-915` 的 `PiEmptyState`，
  **LazyColumn 根本不存在，屏上一篇 markdown 都没有**。
* 第一条之后，LazyColumn 常驻，屏上出现第一篇（逐渐增长的）回答。此时「一次发布的成本」≈ 1 篇文档。
* 从第二条起，「一次发布的成本」= **屏上所有 markdown 行**，其中包含已经**定稿**的第一篇长回答
  （以及工具卡）。也就是说：第二条不是引入了新代码路径，而是**第一次把「已定稿的大文档」放进了
  「每次发布都要重渲染」的集合里**；而且 `ChatScreen` 的发送路径本身会连写几次状态
  （`draft = ""`、`reArmTail()` → `tailPoke++`/`following`、`session.send` 内部的 `echoUserPrompt` 发布、
  `syncTranscript`），每一次都是一轮全量 markdown 重渲染。
* 顺带：发送后列表第一次变成「可滚动」，跟随的 pin / `requestScrollToItem` 路径这时才真正跑起来
  （那是**放大器**，不是病因，见 §4-⑥）。

**B4. 为什么「从历史列表进会话」也卡。**

* `switchSession` → `switch_session` → `replayHistory`（`ui/PiSessionViewModel.kt:1913-1940`）→
  `engine.seedHistory`（`engine/PiEngineSession.kt:820-849`，投影在 `Dispatchers.Default` ✓）
  → `adoptSeededTranscript`（`:851-859`）发布一次 `replaced=true` 的全量行 ⇒ ChatBody 第一次带上整段历史。
* 每篇 markdown 行的**首帧是空白**：`MarkdownStateImpl` 的 `stateFlow` 初值是 `State.Loading`，
  解析是异步的。所以「先画空 Box，解析好了再整篇画出来」，每行两轮。
* 紧接着 attach 的收尾连着写状态：`ui/PiSessionViewModel.kt:1195-1212`
  （`refreshState` → `refreshCommands` → `refreshTuiOnlyExtensions` → `seedAutoRetryFromSettings` →
  `refreshPrefs` → `refreshTheme` → `maybeResumeLastSession`），
  以及 `afterSessionReplaced`（`:2986-2998`）里的 `refreshState`/`refreshCommands`/`refreshSessions`；
  `call()`（`:1860-1875`）每个 RPC 还会写两次 `busy`。
  **这十几发 `_state.value` 每一发都触发一轮「所有可见 markdown 行 × 重解析 + 整篇重渲染」，
  中间没有空闲帧。** 一屏 4 行 15 KB ⇒ 一轮 ≈ 1656 个 composable + 60 KB 排版；十几轮连着来，
  主线程 >5 s 不处理输入 ⇒ ANR。**不需要任何活锁**：一次性突发就够。

### 2.4 探针源码与输出（`/tmp`，可复现）

`/tmp/mdprobe/Probe2.java`（parse 成本 × 文档大小 × AST 节点数）：

```java
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.parser.MarkdownParser;
import org.intellij.markdown.ast.ASTNode;
public class Probe2 {
    static int count(ASTNode n){int c=1;for(ASTNode k:n.getChildren())c+=count(k);return c;}
    static String para(int i){ return "第 "+i+" 段：引擎把每个事件折叠成一行；\"tool_execution_update\" 在 200 ms 窗口内被抑制。\n\n"; }
    static String code(int i){ return "```kotlin\nfun f"+i+"(x: Int) = x * 2\n```\n\n"; }
    static String mk(int target, int codeEvery){ StringBuilder b=new StringBuilder(); int i=0;
        while(b.length()<target){ b.append(para(i)); if(codeEvery>0 && i%codeEvery==0) b.append(code(i)); i++; } return b.toString(); }
    public static void main(String[] a) {
        for(int s: new int[]{5*1024,15*1024,50*1024,150*1024}){
            String doc=mk(s,12); long best=Long.MAX_VALUE; int nodes=0, root=0;
            for(int r=0;r<5;r++){ MarkdownParser p=new MarkdownParser(new GFMFlavourDescriptor());
                long t0=System.nanoTime(); ASTNode g=p.buildMarkdownTreeFromString(doc); long dt=System.nanoTime()-t0;
                if(dt<best){best=dt;nodes=count(g);root=g.getChildren().size();} }
            System.out.printf("doc %6d chars  parse=%6.2f ms  totalASTnodes=%6d  rootChildren(Column items)=%5d%n",
                doc.length(), best/1e6, nodes, root); } } }
```
编译运行（用的就是本机缓存里的真库，没有编译仓库）：
```bash
CP=/root/pi-android/build/typecheck/extra/markdown-jvm-0.7.5.jar:<kotlin-stdlib.jar>
javac -encoding UTF-8 -cp "$CP" -d out Probe2.java && java -cp "out:$CP" Probe2
```
输出见 §2.2。另有一个同类探针量了 App 自己的 `piMarkdownSource`（见 §4-⑦）。

### 2.5 三条路各自的证据强度（按要求分列）

| 路 | 结论 | 证据强度 |
|---|---|---|
| **markdown 解析/渲染路径** | **主因**：每重组 = 重解析 + 全篇重渲染；重组随发布发生 | **强（机制由库源码 + 本机字节码 + 测量三面钉死）**。唯一靠推断的一环是「未变的行是否被重新调用」（§3-①），但流式那一行不需要这一步也成立 |
| **转录 reducer / 状态复制** | 不是 5 秒级 ANR 的来源 | **已排除（本机可证）**：`PiEngineSession.publish`/`diffIndices`（`:630-690`）用 `!==` 身份比较、只在有行变动时 `toList()`；`syncTranscript` 的 `pub.rows.any{…}`（`PiSessionViewModel.kt:1332`）是 O(行数) 的指针比较；`UiState` 是 data class，行相等靠 `===` 短路。全部微秒级（与 `docs/regression-hunt.md` C7 一致，我复核了符号） |
| **进入会话时的 IO** | 不是 ANR 来源（只是让突发更长） | **已排除**：`seedHistory` 在 `Dispatchers.Default`（`engine/PiEngineSession.kt:820`）；JSON 解析在 `readLoop` 的 `withContext(Dispatchers.IO)`（`:440-475`）；会话目录扫描 `PiSessionStore.kt:91` 在 IO。主线程没有文件 IO。代价是「种子落地后要连做十几轮 markdown 重渲染」，那是**后果**不是原因 |

---

## 3. 强烈怀疑但未证实（附证实办法）

① **「未变的 markdown 行也会被重新调用」完全成立**（这决定机制 A 是「1 篇/发布」还是「N 篇/发布」）。
   依据：`ChatScreen.kt:968-1031` 的项 lambda 与 `BlockRenderer` 的三个回调都捕获 unstable 的
   `session`（`:1010`/`:1014`/`:1023`），因此不能被强跳过记忆化。
   **怎么证实**：真机/Android Studio 的 Layout Inspector 打开重组计数，停在**不流式、不打字**的会话里，
   制造一次纯状态发布（例如让队列 chips 变化，或从别的屏切回来），看 `PiMarkdownText`
   的重组计数是否照样 +1。CI 上量不了。

② **主线程重渲染的绝对量级**。我只测到了 parse 侧（9–58 ms）与节点数（141–4080），
   Compose 文字排版那一半必须在设备上量。
   **怎么证实**：`adb shell dumpsys gfxinfo app.pi framestats`（或 Layout Inspector 的帧耗时），
   在「打开含长回答的历史会话」与「连发两条消息」两场景各取 60 s；对比修复前后。

③ **用户会话里的文档真的在 10–150 KB 量级**。换算完全线性：若回答都 <2 KB，这条机制只有毫秒级，
   就不是主因。
   **怎么证实**：看那条会话 `.jsonl` 里最大的 assistant text 字节数（`wc -c` 逐行取最大），
   或用 App 自己的诊断报告。旁证：`get_entries` 把整条会话放在**一个** JSONL 记录里，
   而 framer 的单记录上限是 8 MiB（`rpc/.../Jsonl.kt`），说明真实会话确实到 MB 级。

---

## 4. 已排除（附理由，别重复劳动）

① **动画没开**。`PiMarkdown.kt:185` 的 `markdownAnimations(animateTextSize = { this })` 真的是恒等：
   库 v0.45.0 的 `markdownAnimations` 只把 lambda 包进 `DefaultMarkdownAnimation`，
   `MarkdownText` 用它做 `Modifier.then(animations.animateTextSize())`，`{ this }` 原样返回修饰符，
   `animateContentSize()` 不会被挂上。（`DefaultMarkdownAnimation` 实现了 `equals`/`hashCode`（比 lambda），
   所以把 `animations` 传进去也不会破坏跳过。）

② **解析不是同步的**。库的 `immediate` 默认值是 `LocalInspectionMode.current`，真实 App 里是 `false`，
   所以 `MarkdownStateImpl(...).apply { parseBlocking() }` 那条**同步解析**分支不会走；
   `parse()` 是 `withContext(Dispatchers.Default)`。（这条上色审计的疑问到此为止。）

③ **流式不会堆积解析任务**。`snapshotFlow { currentInput }.conflate().collect { updateInput(it); parse() }`
   是**串行 + conflate**：同一时刻最多一个 parse 在跑，中间的值被丢掉，collector 不并发。
   问题不是堆积，而是「每次重组都重新提交一次」+「每次 `Success` 都整篇重渲染」。

④ **高亮不是嫌疑**（与父代理的高亮保真审计一致）：`rememberPiHighlightedCode`
   （`ui/render/PiMarkdownComponents.kt:409-436`）是 `produceState(code, language, highlighter)` +
   `withContext(Dispatchers.Default)` + 200 ms 去抖 + 128 条 SHA-256 LRU；`ToolBodyText.kt:54-59`
   全在 `remember` 内。它不在主线程。

⑤ **`TailFollow` 的活锁不成立**（我重新推了一遍，与 `4e2f845`/`ab52357` 两次改动一致）：
   跟随效果的键全是数据（`ChatScreen.kt:509-517`），一次运行最多下发一个 pin，
   `requestScrollToItem` 只产生滚动与 remeasure、改不了任何键；唯一会被自己写的 `following`
   收敛于一次额外重跑。**它是放大器（每个 pin 触发的 `forceRemeasure` 会让可见子树重新测量），
   不是病因**。两次「跟随」修复都没治好，正说明病因不在这里。

⑥ **`piMarkdownSource` 不是二次算法**。它是 `remember(markdown)` 键住的（`PiMarkdown.kt:81`），
   只在文本变化时跑。探针（`/tmp/mdprobe/Probe4.java`，把 `PiMarkdown.kt:218-318` 逐行搬到 Java、
   `PiLatex` 用恒等占位）在**固定 32 KB 文档**上把围栏数从 1 加到 150：

   ```
   size=31974 chars  fences=  1  ->     4.97 ms
   size=32008 chars  fences=  5  ->     2.96 ms
   size=32038 chars  fences= 20  ->     3.74 ms
   size=32118 chars  fences= 60  ->     3.78 ms
   size=33468 chars  fences=150  ->     4.05 ms
   ```
   成本与围栏数无关 ⇒ **线性，不是 O(k·n)**。含 `$` 的 60–480 KB 文档上量到 19–34 ms
   （不含 `$` 走 `if (!markdown.contains('$')) return markdown` 快路径，< 2 ms）——
   一次文本变化几十毫秒，是次要项，不是 5 秒级病因。

⑦ **进入会话的主线程 IO**：见 §2.5 第三行，已排除。

⑧ **`ShellBlock.kt:78` / `ReadBlock.kt:55-56` / `WriteBlock.kt:46-47`**（高亮审计点名的三处「每帧重算」）：
   实测口径下都是零头，而且 `ShellBlock.kt:78` 的 `System.currentTimeMillis()` **必须**每次重组读
   （它就是「已运行 12.3 秒」那个走动的秒数），**不要**给它加 remember。
   `ReadBlock`/`WriteBlock` 那两行是 `take(5)` 的小列表分配。**不建议动。**

---

## 5. 最小修复建议（按「改动最小、断链最直接」排序）

**修 1（必做，3 行 + 3 个 `remember`，全在 App 自己的文件里）——把库的三个普通对象变成稳定实例。**

`app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt`：

```kotlin
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
// ...
val flavour = remember { GFMFlavourDescriptor() }
val parser = remember(flavour) { MarkdownParser(flavour) }
val references = remember { ReferenceLinkHandlerImpl() }
Markdown(
    content = content, colors = colors, typography = typography,
    padding = piMarkdownPadding, dimens = piMarkdownDimens,
    imageTransformer = imageTransformer, components = components,
    flavour = flavour, parser = parser, referenceLinkHandler = references,   // ← 新增
    retainState = true,
    animations = markdownAnimations(animateTextSize = { this }),
    modifier = modifier,
)
```

**为什么这样能断链**：`rememberMarkdownState` 的 `remember(content, lookupLinks, flavour, parser,
referenceLinkHandler, retainState)` 现在拿到的是**同一批实例** ⇒ `Input` 复用 ⇒ `Input.equals` 为真 ⇒
`updateInput` 早退、`parse()` 不再被提交、`stateFlow` 不变 ⇒ `collectAsState` 不发射 ⇒
**未变文本的行不再重解析、也不再整篇重渲染**（A4+A5 的那条链被切断）。
用**行内 `remember`**（不是顶层 val）是为了保住 `ReferenceLinkHandlerImpl` 的行间隔离：
它是**有状态**的（`store`/`find` 引用链接定义），全 App 共用一个实例会让 A 消息里的
`[x]: url` 影响 B 消息的链接解析。`MarkdownParser` 复用是库自己推荐的形态
（它自己的默认值正是 `parser: MarkdownParser = remember(flavour) { MarkdownParser(flavour) }`）。

**修 2（紧接 1；针对「流式中那一行」这个修 1 治不了的部分）——给流式文本去抖。**

即使修 1 到位，**文本每变一次仍然要一次全量解析 + 一次整篇重渲染**，而流式每秒变 10–50 次。
在 `ui/blocks/AssistantTextBlock.kt`（或 `PiMarkdownText` 的调用侧）把**流式期间**交给库的文本
按 ~120–150 ms 节流（例如 `produceState(item.text)`：`item.streaming` 时 `delay(120)` 再赋值，
`!item.streaming` 时立刻赋值；只在值真的变化时写 `value`）。
理由：库自己对流式的答案是 `rememberStreamingMarkdownState`（增量解析、只重渲染不稳定尾巴），
但那要换入口；节流是同样的目标、更小的面，且**不丢最终态**（`!streaming` 时立刻跟到最新）。
**注意**：`retainState = true` 必须保留 —— 它保证「去抖窗口内显示的是上一版内容而不是空白」。

**修 3（只在 1+2 之后仍不够时做）——让未变的行能被跳过。**
把 `ChatScreen.kt:1010`/`1014`/`1023` 的三个回调从「每次重建的 lambda」改成不捕获 unstable
`session` 的稳定值（`remember` 住，或改成经一个 `@Stable` 壳传递），并让项 lambda 不再捕获
`searchMatches: List<Int>`（`:393`，可换成 `IntArray`/`Set` 之类的稳定类型）。
这样 `BlockRenderer` 的实参可比较相等，未变的行才会真正跳过。
**这是 §3-① 的前置条件，所以放在最后**：它需要真机/CI 确认收益，而且改动面比前两条大。

**不要做的**：不要重写 markdown 渲染、不要给 App 加埋点/看门狗、不要把 `Markdown` 换成
`StreamingMarkdownState`（那是产品级重构，等 1+2 的结果）。

---

## 6. 修复后的验证方式（只能靠设备；本机不能编译）

**前提**：CI 编译通过（本机是用户手机，禁止编译）。下面每步都写「用户做什么 / 看什么 / 什么算修好」。

**A. 主场景（对应两个触发点）**
1. 找一个**最后一篇回答很长**（十几 KB 以上，带代码块）的历史会话，从会话列表点进去。
   * 看：转录出现后 **1 秒内**点一下列表并拖动 —— 列表要立刻跟手。
   * 修好 = 无「应用无响应」弹窗、拖动跟手；没修好 = 界面不动、几秒后弹「没有响应」。
2. 在同一个会话里连发两条消息（第二条要等第一条回答完再发）。
   * 看：从点发送到「自己的消息出现」之间的延迟；流式期间输入框还能不能输入（光标是否闪、字是否上屏）。
   * 修好 = 两条都不卡、流式中打字能立刻上屏；没修好 = 第二条卡死。

**B. 量化判据（需要 ADB；用户自己在开发者选项里开无线调试）**
* `adb shell dumpsys gfxinfo app.pi framestats`：在「点进长会话」与「连发两条」两场景各取 60 s，
  **修复前后各一次**，比 janky 帧数与最长帧。
* `adb logcat -d | grep -iE "Input dispatching timed out|ANR in app"`：修复前应能抓到一条 ANR 记录，
  修复后两场景各跑 2 分钟不应再出现。
* 判据宁可严一点：**最长帧 < 200 ms、且两分钟内 0 条 ANR**。

**C. 反向确认（证明修的是这条链，而不是把问题挪走了）**
* 修 1 单独上：不流式、不打字的会话里，反复制造纯状态发布（例如切到会话列表再切回来），
  `PiMarkdownText` 的重组计数**不应该**再引起 `parse` —— 用 Layout Inspector 的
  recomposition 计数对照，或看 `dumpsys gfxinfo` 的帧时间不再随「屏上文档字节数」线性上升。
* 修 2 单独上：流式一问，长回答期间最长帧应显著下降，而**最终文本必须与 pi 的完整回答一字不差**
  （去抖只影响中间帧，不能丢尾巴）—— 等回答结束后对照 `get_last_assistant_text` / 复制出来的全文。

**D. 回归项（改了 `PiMarkdown.kt` 必须一起看的）**
* 代码块高亮仍要出现（修 1 只动了 parser，不动高亮通道）。
* 引用式链接语法（`[a][b]` + `[b]: url`）仍要解析；**两条消息里的同名链接不能互相串**
  （行内 `remember` 的 `ReferenceLinkHandlerImpl` 就是为这个）。
* LaTeX 改写（`piMarkdownSource`）不变 —— 修 1 不动它。

---

## 7. 实施记录（本轮已落地 / 明确押后）

### 7.1 已落地（只改了这两个文件）

| 文件 | 改动 |
|---|---|
| `ui/render/PiMarkdown.kt` | `PiMarkdownText` 里新增三个**行内** `remember`：`val flavour = remember { GFMFlavourDescriptor() }`、`val parser = remember(flavour) { MarkdownParser(flavour) }`、`val references = remember { ReferenceLinkHandlerImpl() }`，并作为 `flavour` / `parser` / `referenceLinkHandler` 三个具名实参传给 `Markdown(...)`；`retainState = true` 原样保留。函数 KDoc 与调用点各有注释说明机制与被测数字 |
| `ui/blocks/AssistantTextBlock.kt` | 新增私有 `@Composable fun settledStreamingText(text, streaming)` 与常量 `STREAM_TEXT_WINDOW_MS = 140L`；交给 `PiMarkdownText` 的文本改为它的返回值。流式期间按 140 ms 一窗推进（**首帧立即**，`delay` 在每轮末尾、键只有 `streaming`，所以不会像 `produceState(text)` 那样被下一个 token 取消而不推进）；`streaming == false` 时**原样返回 `item.text`**，不延迟、不丢尾 |

**为什么三个对象必须行内 remember、不能提到顶层或 CompositionLocal**：`ReferenceLinkHandlerImpl` 有状态
（`lookupLinks` 默认 true，parse 会把每个引用式链接定义写进 handler），全 App 共用一个实例会让一条消息里的
`[x]: url` 影响另一条消息的 `[x]` 解析。行内 `remember` 既稳定（同一次行内重组的引用不变）又保持行间隔离。
另外每个只被组合到的行才持有实例，行滚出组合即释放，不会随 `renderWindow` 增长而累积。

**放弃的替代方案（及原因）**
1. **改库 / 换 `StreamingMarkdownState`**：先把「O(文档) 每次重组」这条链在两个文件的范围内断掉，代价最小、可回退；
   换流式入口是产品级重构，而且要等前两条的真机结果才知道有没有必要。
2. **`produceState(text) { delay(…); value = text }` 做去抖**：它会被每一个到达的 token 取消，
   在 token 快于窗口时**永远不发射** ⇒ 回答中途整行冻住。这是本次唯一必须避开的坑，所以循环的键只有 `streaming`。
3. **在 `PiMarkdownComponents.kt` 里做去抖**：该文件归上色代理改，且去抖属于「块的行为」而不是「组件库的装配」，
   放在 `AssistantTextBlock` 更贴语义。
4. **全局共享 parser/flavour 以省内存**：省不下多少（几十字节级），却会把 `ReferenceLinkHandlerImpl` 也一起共享，
   带来链接串味的功能回归。

### 7.2 押后：修 3（稳定 lambdas / `searchMatches`）——本轮**不做**

* 要动 `ui/screens/ChatScreen.kt`（`:968-1031` 项 lambda、`:1010`/`1014`/`1023` 三个回调、`:393` 的 `searchMatches`），
  而该文件正被 B3（工作区那批）改，两个改动撞一起会互相打断。
* **什么条件下需要做**：按 §6-A/B 做完设备验收后，若
  ①「未变的 markdown 行仍被重新调用」在 Layout Inspector 里被证实（§3-①），且
  ②修 1+2 之后 `dumpsys gfxinfo` 的长帧仍随「屏上文档字节数 × 发布次数」线性增长、
  ③单行文档 <5 KB 的会话里打开/连发也仍卡（说明单次渲染之外还有「重渲染次数」这一项没被压住），
  再做修 3；否则它是纯优化，不值得动正在被别人改的文件。
* 做时注意：项 lambda 还捕获了 `searchMatches: List<Int>`（`:393`）与 `session`，两者都要变成稳定值
  （`IntArray`/`Set` 之类）才可能让 `BlockRenderer` 跳过。
