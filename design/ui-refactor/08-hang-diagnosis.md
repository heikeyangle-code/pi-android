# 08 · 「一进会话 / 一发消息就卡死」根因报告

> **本机没有编译任何仓库代码。** 只读代码、只读本机缓存里的第三方字节码、只读第三方库 v0.45.0 的源码，
> 并在 `/tmp` 下跑了三个 bare-JVM 探针（不含 Compose，不含仓库文件）。`git` 只读。
> 未改动 `app/` 或 `tools/` 下任何既有文件；本文件是唯一新增产物。
> 行号以**符号名**为准：`ChatScreen.kt` / `PiSessionViewModel.kt` / `PiRoot.kt` 在本报告写作期间
> 正被别的代理改动，本文件里的行号是**核对当天**的值（`ChatScreen.kt` 自 `:850` 起、`PiSessionViewModel.kt`
> 自 `:1060` 起已各下移约 10 行）；改代码时按符号找，别按行号找。

---

## 0. 结论（设备验证：主因已修好）

> 这一节是**结论**，写在最前面。后面 §1–§9 是按时间顺序的取证过程（含一次被设备证伪的支线），
> 保留原样以免下一轮重复劳动。

**机制（一句话）**：`ui/render/PiMarkdown.kt` 调第三方 markdown 渲染器时没有传
`flavour` / `parser` / `referenceLinkHandler`，而库 v0.45.0 在 `Markdown(content, …)` **自己的函数体里**
每次现造这三个默认对象；它们又正是 `rememberMarkdownState` 的 `remember` 键与 `Input.equals` 的比较字段
（三个类都没有 `equals`）。于是**每次重组都让库内部的 remember 失效 ⇒ 重新提交解析 ⇒ 整篇文档重渲染**，
而「新的默认对象 ⇒ 再组合 ⇒ 又造新对象」让这条链**自我延续**，`MarkdownSuccess` 又是
`Column { node.children.forEach { … } }`（无虚拟化）⇒ 一帧画不完 ⇒ 输入 5 秒超时 + 每秒读数冻住 + ANR。

**修复内容**（已提交，`6465184`）：`PiMarkdown.kt` 里用**行内 `remember`** 造出这三个对象并显式传给
`Markdown(...)`（`PiMarkdown.kt:145-147`、`:189`），`retainState = true` 保留。行内而不是顶层，是因为
`ReferenceLinkHandlerImpl` 有状态（引用式链接定义会跨消息串味）。

**验证方式与结果**：用户安装含该提交的 APK（`PI-a820a78`）后原话 —— **「这个版本我现在聊天不会卡死了。」**
即：新会话发第一条、以及从历史列表进会话，两个触发点都不再卡死。

**仍然未知（诚实清单）**
1. **两个修复没有分开验证**：那次 APK 同时包含「稳定解析对象」与当时的「140 ms 流式去抖」。
   其中去抖已在 **§10** 因「触底跟随失效」被撤回 —— 所以现在的生效组合是**只有稳定解析对象**
   （§10 给出撤回的代价评估）。「主因是稳定解析对象」由机制与撤回后的现象共同支持，但不是一次
   单变量实验。
2. **是否还有更小的长帧**：撤回去抖后，流式那一行**每次内容变化仍要整篇重渲染**
   （测量：5 KB→8.95 ms/1026 节点；15 KB→13.79 ms/3021；50 KB→22.97 ms/9976；150 KB→58.02 ms/29811，
   桌面 JVM，手机约 2–5×）。长回答流式时仍可能有可见掉帧（预计 15–30 fps 而非 60），
   但不再是「一帧画不完」⇒ 不是 ANR。**没有设备帧时间数据**（ADB 关着）。
3. **§7.2 修 3（稳定 lambdas / `searchMatches`）的触发条件尚未确认**：它需要「未变的行仍被重新调用」
   被 Layout Inspector 证实，或帧时间仍随「屏上文档字节数 × 发布次数」线性增长。ADB 关着 ⇒ 未判定。
4. **§9 的硬阻塞支线**：设备验证表明主因是 markdown 那条链，所以 ①（主线程唤醒锁+通知）与
   ②（高亮抢 `Dispatchers.Default`）**都降级为未证实、未采纳**，见 §9 开头的结案说明。

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

---

## 8. 专项：「全新会话、一条消息都没有时发，回复开始出现文字就卡」

> 用户补充的**用户口径变更**：卡死**不是**发生在历史会话里，而是**新会话、空转录**时发消息；
> 且时机是「**等回复开始出现文字了才卡**」，卡住那一刻屏幕上是**「我发的那句话 + 回复的开头」**。
> 这一节只回答一个问题：§2 的机制能不能解释它；解释不了的部分**明确说解释不了**，并给出设备判据。

### 8.0 先给结论（分三段，不含糊）

1. **能解释的**：「回复的第一个 token 到达 → 这一行开始长 → 卡」这一段。**新会话里这是本进程的
   第一篇 markdown 文档**，而且第一条回复（推理模型还要先过 thinking，见 §8.1-0）往往就是最长的那一篇。
   从第一个 delta 起，每一次发布都让这一行「整篇重解析 + 整篇重渲染」（§2.1），发布频率 10–50 次/秒。
   这一点与「历史会话」是同一条链，只是新会话里文档数从 0 变成 1，**没有任何机制上的区别**。
2. **不能解释的**：「屏幕上只有几百个字符、主线程就彻底不动」。§2.2 量到 5 KB 文档一次 parse 8.95 ms、
   141 个顶层 composable —— 几百字符的一次重渲染是毫秒级，**单靠这条链不可能把帧线程压死**。
   所以如果用户看到的「回复的开头」真的只有两三行，那**还有第二个机制，而我本轮没有找到有证据的那一个**（§8.2）。
3. **关键认识：屏幕停在「回复的开头」不能证明「卡住那一刻成本还很小」。** 它有两种完全不同的解释：
   * **（a）主线程忙**：帧还在画，只是画得越来越慢；用户看到的是**最后一帧成功画出来的内容**，
     而那可能已经是被压死之前的某一帧 —— 与「几百字符」并不矛盾，也说明不了成本小；
   * **（b）主线程被阻塞**：一帧都画不出来，屏幕**冻在它被卡住前的那一帧** —— 这时「回复的开头」
     精确等于「阻塞发生的那一刻」，而这条**不是** §2 的机制（§2 是 CPU 忙，不是等锁/等 Binder）。

   (a) 与 (b) 只需要**零改动的两条命令**就能分开，见 §8.4。**在它跑出来之前，我不会猜。**

### 8.1 按点排查（每条给 `file:line` 或测量）

**0）先补一个前提：新会话第一条回复的「开头」是什么。**
`ThinkingBlockBlock.kt:39-87`：思考块**收起时只画一行 headline**（`:42-46`、`:58-76`），
`item.text` 只在 `expanded` 分支里用（`:77-86`）——所以推理模型先流半小时的 thinking **不花主线程的钱**，
除非用户自己把「思考块默认展开」打开了（`UiPrefs.thinkingCollapsedByDefault` 默认 true →
`ChatScreen.kt` 传 `thinkingDefaultExpanded=false`）。**这一条排除了「thinking 长文把主线程压死」。**

**1）`PiEmptyState` → `LazyColumn` 的第一次切换：一次性、O(1)，没有重活。**
* `listState` 在 `ChatScreen.kt:219` 就建好了（不是切换时新建）；
* `renderWindow` 初值 50（`:386`），`hiddenCount = 0` ⇒ `headerRows = 0`（`:391`）⇒ **不生成「加载更早」行**；
* `searchMatches` 为空（没开查找，`:391` 区域）⇒ 项上的 `contains` 是空列表；
* 空转录时走 `PiEmptyState`（`:895`），有行时走 `LazyColumn`（`:921`）——**同一帧里的两条分支，没有额外的测量/布局阶段**；
* `atTop`/`earlierArmed` 那条效果（`:569-577`）：`earlierArmed` 初值 false（`:572`），所以**第一帧什么都不做**；
* 跟随效果（`:508-552`）：`.size` 0→1 或 1→2 会重启它，但 `atBottom` 为真时 `TailViewport.pinToTail()`
  返回 **null**（`TailFollow.kt:346-354`：`lastVisibleIndex == tail` 且 `hidden <= 0`）⇒ **一次 `requestScrollToItem`
   都不发**，也就没有那次 `forceRemeasure`。⇒ 首次切换**没有一次性重活**。

**2）新会话首条回复时写了几次 state —— 答：1 次，不是 13 次。**
`PiSessionViewModel.send()`（`:2105-2141`）全程只有：`engine.prompt(...)`（内部 `echoUserPrompt` +
队列化一次写；`PiEngineSession.kt:779-783`、`:706-716`）+ **一次** `syncTranscript(engine, publication.value)`
（`:2140`）⇒ **一次 `_state.value` 赋值**（collector 之后会再应用同一个 revision，得到等值列表，
`StateFlow` 可能连发射都不发）。**没有 `call()`、没有 `busy`、没有 RPC 等待、没有 IO。**
* 我报告 §2.3-B4 里那个 ~13 次是**切会话/attach 的收尾链**（`PiSessionViewModel.kt:1195-1212` 的
  `refreshState`/`refreshCommands`/`refreshTuiOnlyExtensions`/`seedAutoRetryFromSettings`/`refreshPrefs`/
  `refreshTheme`/`maybeResumeLastSession`，加 `afterSessionReplaced`（`:2996-3008`）与 `call()` 的 busy 双写
  （`:1870-1885`））——**它发生在启动/切会话时，不在「第一条回复出现」的那一刻**。
* **pi 在第一条助手消息结束时才落会话文件**，这会让 `sessionFile` 由 null 变成非 null：
  下一发 `get_state`（`refreshState`，在 `agent_settled` 之后）把它写进 `meta` ⇒ `sessionKey`
  （`ChatScreen.kt:385`）变化 ⇒ **`rememberSaveable(sessionKey)` 重建 `renderWindow`/`earlierArmed`/
  `pausedRows`/`tail`**（`:386`、`:572`、`:484`、`:477`）。**这是回合结束，不是回复开始**
  ——与本条现象时序不符，但它是一条已核实的既有行为（记在这里，别再当新发现查一遍）。

**3）会话文件出现时的文件监视/轮询 —— 排除。**
* `PiDirectoryWatch` 只有两个组合点：`ui/settings/PiModelsScreen.kt:133`、`ui/settings/PiSettingsStack.kt:210`
  ——**聊天路径上一个都没有**；
* 它本身也不是轮询：`FileObserver`（inotify，掩码排除 OPEN/ACCESS/CLOSE_NOWRITE）+ `ON_RESUME` 时比一次
  `PiFileStamps` 指纹（`ui/settings/PiFileWatch.kt`），**没有定时器**；
* `SessionFileScan` 只被 `PiSessionStore.readSummary`（`session/PiSessionStore.kt:220-307`）用；
  `PiSessionStore.list()`（`:91`）只由 `refreshSessions()`（`PiSessionViewModel.kt:537-541`）调用，
  而它的调用者是 `afterSessionReplaced`（切会话/新建）与会话列表页
  ⇒ **「pi 写出会话文件」这件事在聊天路径上没有任何观察者**，那一刻不会开始扫目录。

**4）引擎侧会不会挡住 UI 线程 —— 发送路径上找不到。**
* `send()`（`engine/PiEngineSession.kt:706-716`）= `writeQueue.execute{ 编码 + write + flush }`，
  `writeQueue` 是 `Executors.newSingleThreadExecutor`（`:171`），**队列无界** ⇒ `execute` 永不阻塞调用方；
  编码（含 base64 附件）也在那条线程上；
* 全仓 `runBlocking` 只出现在 `bridge/DeviceBridgeRouter.kt`（设备桥，与聊天无关）；
  `join`/`Thread.sleep`/`waitFor` 在 UI 路径上零命中（`app/src/main/kotlin/app/pi/ui/**`、`engine/**`）；
* 唯一的阻塞等待是 `close()` 里的 `writeQueue.awaitTermination(WRITER_DRAIN_TIMEOUT_MS = 1_000 ms)`
  （`:972-983`、常量 `:1053`），调用者是 teardown（`PiEngineHost.kt:570` 的 `previous.close()`、
  `closeAfterSettling`）——**不在发送路径**；
* `echoUserPrompt`（`:779-783`）取 `transcriptLock`，持有者是 reader 线程的「一次 fold + 一次 publish」
  （`:575-676`），量级 µs–ms。⇒ **发送路径上没有等锁、没有等队列空间、没有 runBlocking。**

**5）高亮 —— 主线程不等；但有一条 Default 线程饥饿的真问题（新发现）。**
* `PiNodeCodeHighlighter.attach` 只构造客户端、无 IO（与上色代理的核验一致）；
* `fetch` 的唯一入口是 `rememberPiHighlightedCode` 的 `withContext(Dispatchers.Default)`
  （`ui/render/PiMarkdownComponents.kt:418-432`）⇒ **token 文件 IO（`PiHighlightClient.kt:82-89` 的
  `file.readText()` + JSON）与 100 ms/150 ms 超时（`:357-358`）都在 Default 线程**，主线程不等；
* **但是**：`BoundedWorkers.submit`（`PiNodeCodeHighlighter.kt:199-215`）会把**调用它的那条 Default 线程
  占住最多 `CALLER_WAIT_MS = 750 ms`**（`:63`、`:145`），而 markdown 的解析**也在 `Dispatchers.Default`**
  （库的 `MarkdownStateImpl.parse` = `withContext(Dispatchers.Default) { parseBlocking() }`）。
  引擎/高亮桥还没开始服务时（新会话第一条回复、冷启动刚结束）每个围栏都要等满 750 ms 且**不缓存**
  ⇒ 回答里一次出现几个代码块，就把 Default 的几条线程各占 750 ms ⇒ **回答的解析被推迟**，
  表现为「回复行空白/滞后」。**这是真的、也对得上「第一条围栏那一刻」，但它是延迟不是 ANR
  ——主线程没有被它挡住。**

**6）那还剩什么？** 见 §8.2。

### 8.2 我找到的「最像主线程阻塞」的候选，以及为什么它也对不上这一刻

**候选：`reportWork` 在主线程上做 Binder（唤醒锁 + 通知）。**
`syncTranscript` → `reportWakeLockNeed()`（`PiSessionViewModel.kt:833-850`，变更门控）
→ `PiEngineService.reportWork(active)`（`service/PiEngineService.kt:228-230`，**同步调用，没有切线程**）
→ `applyWork`（`:149-152`）→ `acquireWakeLock`（`:154-161`，PowerManager Binder）
+ `updateNotification`（`:129-132`）→ `buildNotification`（`:103-126`：**两次 `PendingIntent.getActivity/getService`**）
→ `NotificationManagerCompat.notify`（Binder）。**全部在调用者线程上，而调用者就是主线程。**

**但时序对不上**：`reportWork` 只在 `active` 翻转时被调（`reportedWork` 门控），
而 `active` 在 `agent_start`（`engine == Busy`）那一刻就已经变成 true（`PiEngineSession.kt:524` 把 `AgentStart → Busy`）。**第一条文字到达时 `active` 已是 true ⇒ 那一刻不会再调 `reportWork`。**
所以它只能解释「回复还没出现就卡」的变体，解释不了用户描述的这一刻。

⇒ **结论（诚实）**：**「新会话第一条回复刚开始出现文字就卡」这一刻，我没有找到有证据的主线程阻塞点；
如果设备证据判定是「阻塞」而不是「忙」，我需要主线程栈才能继续（§8.4-②）。**
如果判定是「忙」，那 §2 的机制就是对症的，已落地的修 1+2 正好作用在这条链上
（新会话第一篇文档不再「每次重组都重解析 + 整篇重渲染」，流式更新从 10–50 次/秒降到 ~7 次/秒）。

### 8.3 最小修复（本轮边界内已做 / 需要别人做的）

* **已做（边界内）**：§7.1 的修 1（`PiMarkdown.kt` 三个稳定实例）+ 修 2（`AssistantTextBlock.kt` 140 ms 去抖）。
  修 2 的窗口**恰好是新会话首条回复最需要的那一条**：流式那一行的重渲染次数直接除以 4–7。
* **不在我边界内、但按证据排序的下一步**（谁拥有谁做）：
  1. `PiEngineService.applyWork`（唤醒锁 + 通知）挪出主线程（例如 `Handler`/`Dispatchers.IO`）
     —— **只在 §8.4 判出「阻塞」时才值得做**，否则是纯搬迁；
  2. 高亮桥的 `BoundedWorkers.submit` 不要阻塞可复用的 `Dispatchers.Default` 线程
     （`PiNodeCodeHighlighter.kt`，上色代理的边界）—— 这是**延迟**问题，不是 ANR，优先级低于 1；
  3. §7.2 的修 3（稳定 lambdas / `searchMatches`）—— 条件是设备上修 1+2 之后仍「忙」。

### 8.4 设备判据（零改动，先跑这两条再谈根因）

**① 卡住那一刻，主线程是「忙」还是「阻塞」——这条决定后面所有分支。**
```bash
# 卡住时连跑两次，间隔 2 秒，看帧计数是否还在涨
adb shell dumpsys gfxinfo app.pi | grep -E "Total frames rendered|Janky frames"
# 看主线程的线程状态：R = 在烧 CPU（忙）  S/D = 在等（阻塞）
adb shell top -H -p $(adb shell pidof app.pi) | head -20
```
* **帧计数不涨 + 主线程 S/D ⇒ 阻塞**（§2 的机制解释不了；要主线程栈，见 ②）。
* **帧计数在涨但很慢 + 主线程 R ⇒ 忙**（§2 的机制对症；量修 1+2 前后的 `Janky frames`/最长帧）。

**② 阻塞分支要的那一项证据：主线程栈。**
```bash
adb logcat -b events -d | grep am_anr          # ANR 的 reason 字符串
adb shell dumpsys activity processes | grep -A 5 -i "anr\|not responding"
```
能拿到栈最好（`/data/anr/` 需要 root 或 debuggable；拿不到就用 ① 的状态码 + `logcat -b events` 的 reason）。
**要看的只有一件事：主线程栈顶是不是 `Binder.transact` / `synchronized` / `Socket` / `File`。**
如果是 `Binder.transact`，§8.2 那个候选（唤醒锁/通知）就升格为嫌疑，按 §8.3-1 改。

**③ 顺手能判掉高亮那条**：让模型在**新会话的第一条回复**里一次输出 **两个以上代码围栏**。
* 若现象是「回复行先空白/滞后 0.7-1.5 秒再出现文字」⇒ 就是 §8.1-5 的 Default 线程饥饿（延迟，不是 ANR）；
* 若现象仍是「完全卡死」⇒ 与高亮无关。

---

## 9. 硬阻塞专项（用户新证据：卡住时**每秒读数也冻住**，随后被 ANR 杀掉）

> **用户新口径（决定性）**：卡死那几秒里，界面上**每秒在变的读数也不跳**；再等一会直接「软件停止运行」。
> ⇒ 这不是「慢」，是**主线程硬阻塞 / 一帧永远画不完**（§8.0 的 (b) 支）。
> **这一节的目标只有一个：在「新会话 → 发第一条 → 回复第一个 token → 屏上第一次出现 markdown」
> 这条精确路径上找硬阻塞。** 找不到的，明确写「找不到」。
> **§9 已结案（设备验证）**：含 `6465184` 的 APK 装上后用户报告「不会卡死了」⇒ **主因是 markdown 那条链**
> （§2/§7/§10），**不是**这一节找的主线程硬阻塞。本节两条候选因此**降级为未证实、未采纳**：
> ① 的补丁**不落**（用户不接受「不修 bug 的改动」），② 单独登记为一条独立小缺陷（§9.5）。
> 本节保留原样，是因为它排除了五类猜测（§9.1-③④⑤⑥），下一轮不用重来。
>
> **一处更正（重要，别再用错）**：本节初稿写过「单纯忙不足以触发 ANR」——**那是错的**。
> 正确的判据是「主线程连续 5 秒没有完成一帧」，而**自我延续的重组循环恰好让一帧永远画不完**：
> 它同样会让输入超时、让每秒读数冻住、让系统弹「没有响应」，与硬阻塞在**现象上不可分**。
> 所以「读数冻住」**不能**用来排除「忙」这一类；能分开的只有主线程的线程状态（R vs S/D）与 ANR 栈。
> 最终由设备验证给出的答案是：这一支就是「忙」（一帧画不完），不是 Binder 阻塞。

### 9.0 先钉两条判据上的事实（它们能砍掉一整类猜测）

* **「等 RPC 应答」不可能造成这个症状。** ANR 的触发是「输入事件 5 秒没被处理」＝**线程被占住**；
  而 `viewModelScope` 里的 `engine.request(...)` 是**挂起**等待（`PiEngineSession.kt:720-742` 的
  `withTimeout { deferred.await() }`），挂起的协程**让出线程**，主线程照样处理输入。
  所有 RPC 都有超时（`PiEngineApi.kt:394` `DEFAULT_TIMEOUT_MS = 120_000`；探针
  `PiEngineSession.kt:1030` 300 s；收尾 `:1043` 30 s），且**全仓没有 `runBlocking`**（唯一的在
  `bridge/DeviceBridgeRouter.kt`，设备桥）。⇒ **「没有超时 ⇒ 主线程永久挂起」这条在本树里不成立**
  （缺超时的后果是「停在 busy」，不是 ANR）。
* ~~**「忙」不能触发 ANR。**~~ **（这条是错的，见本节开头的更正与 §0）**「每次重组几百毫秒」确实只会卡顿，
  因为帧与帧之间输入仍会被处理；但**自我延续的重组循环会让一帧永远画不完**，那就等价于
  「连续 ≥5 秒不处理输入」⇒ 同样 ANR、同样冻住读数。**「忙」与「硬阻塞」在现象上不可分**，
  当时据此把 §2 判出局是错的；设备验证证明主因正是「忙」这一类（只是忙法特殊：一帧画不完）。

### 9.1 按「可能性 × 证据强度」排序的清单（沿精确路径）

**① `PiEngineService.applyWork`：主线程上的唤醒锁 + 通知（含两次 `PendingIntent` 查询）。**

> **【已降级：未证实、未采纳，补丁不落】** 设备验证（§0）表明主因是 markdown 那条链，不是这里。保留分析供将来真遇到 Binder 类 ANR 时参考，但**不要**据此改 `PiEngineService`：用户明确不接受「不修 bug 的改动」。
* 机制：`syncTranscript` → `reportWakeLockNeed()`（`PiSessionViewModel.kt:833-850`，同步、变更门控）
  → `PiEngineService.reportWork(active)`（`service/PiEngineService.kt:228-230`，**静态方法直接调，
  没有切线程**）→ `applyWork`（`:149-152`）→ `acquireWakeLock`（`:154-161`，PowerManager Binder）
  + `updateNotification`（`:129-132`）→ `buildNotification`（`:103-126`：**两次 `PendingIntent.getActivity/
  getService`**）→ `NotificationManagerCompat.notify`（Binder）。**全部在调用者线程，而调用者就是主线程。**
* **为什么恰好是这一刻**：`active` 由 `engine == Busy || streaming || busy != null` 决定；
  它第一次翻成 true 是在 **`agent_start`**（`PiEngineSession.kt:524` 把 `AgentStart → Busy`）
  ——**就在回复第一个 token 之前的那几百毫秒**。本机是小米 M2011K2C / Android 14：MIUI 对
  notification / PendingIntent / wake-lock 这条系统服务路径有额外拦截，而那一刻 system_server
  正被同一个 App 的 proot+node 引擎占着 CPU —— Binder 交易卡住数秒是这一类 ANR 的典型形态
  （trace 上表现为主线程栈顶 `android.os.BinderProxy.transactNative`，且**没有任何一帧在跑**，
  与用户看到的现象完全一致）。
* **证据强度**：中。代码路径 100% 确认（同步、主线程、Binder），但它是否真的卡住**只能由 ANR 栈判定**；
  且严格说它由 `agent_start` 触发，若用户看到的最后一帧里已经有回复文字，则它发生在更早一点。
* **最小修复**：把这两件事挪出主线程——`PiEngineService.reportWork` 里改成向后台 Handler/`Dispatchers.IO`
  投递（或 `applyWork` 内部切线程），调用点保持 `PiSessionViewModel.kt:833-850` 不变。
  文件：`app/src/main/kotlin/app/pi/service/PiEngineService.kt`（另一代理/文件所有者）。
  **注意**：不要在 `syncTranscript` 里加 `withContext`——那是挂起函数，会把状态发布变成异步。

**② 高亮 `BoundedWorkers.submit` 占住 `Dispatchers.Default` 线程 750 ms，与 markdown 解析抢同一个池。**

> **【已降级：与本次卡死无关，另登记为独立小缺陷】** 它解释不了 ANR（是延迟不是阻塞），设备验证也与它无关。它的最小改法与「要不要修」见 §9.5，**与本报告的主线分开**。
* 机制：`PiNodeCodeHighlighter.kt:199-215` 的 `submit` 在**调用线程**上 `completed.await(...)`
  最多 `CALLER_WAIT_MS = 750`（`:63`、`:145`）；调用线程来自
  `PiMarkdownComponents.kt:553` 的 `withContext(Dispatchers.Default)`；而 markdown 的
  `parse()` 也跑在 `Dispatchers.Default`（库 `MarkdownStateImpl.parse`）。
* **为什么恰好是这一刻**：第一条回复里**第一次出现代码围栏**时，同一个 `Dispatchers.Default`
  池同时被「等高亮」和「等解析」占住；引擎刚起来、高亮桥还没服务时每次都要等满 750 ms 且不缓存。
* **证据强度**：强（代码可证），但**它造成的是延迟，不是主线程阻塞**：主线程只是拿不到解析结果，
  仍然处理输入 ⇒ **解释不了「读数冻住」**。列在这里是因为它会掩盖①，且修法便宜。
* **最小修复**：`PiNodeCodeHighlighter` 不要在 `Dispatchers.Default` 上阻塞等（改为
  `CompletableDeferred` + `withTimeout` 的挂起等待，或把等待放到自己的专用线程池）。

**③ 锁与临界区全表（结论：找不到死锁）。**

| 锁 | 谁拿（线程） | 临界区里干什么 | 会不会挡住主线程 |
|---|---|---|---|
| `transcriptLock`（`PiEngineSession.kt`） | reader 线程 `:550`；**主线程** `:780`（`echoUserPrompt`）、`:852`（`adoptSeededTranscript`） | 一次 `foldEvent`（reducer 折叠 + `publish`：身份 diff、`toList()`、**两次 StateFlow 写**）或 `onUserPrompt`+publish | **不会**：临界区内**没有 I/O**，两次 StateFlow 写不会等待订阅者（StateFlow 无背压，订阅者由各自 dispatcher 调度）。临界区 µs–ms 级 |
| `stderr`（`PiEngineSession.kt:350/386`） | stderr 泵 / 死亡分支 | 一次 `StringBuilder.append` / 一次读 | 不会 |
| `PROCESS_LOCK`（`PiEngineHost.kt:202/395/460`，`kotlinx.Mutex`） | suspend 协程 | 整个 boot/restart/shutdown（**分钟级**） | **不会**：`Mutex` 是挂起语义，等待者让出线程（这也是为什么它没写成 `synchronized`） |
| `PiMentionSource.lock`（`ui/chat/PiMentionSource.kt:66`） | `Dispatchers.IO` | `withContext(Dispatchers.IO)` 内的一次 guest `fd` | 不会（不在主线程） |
| `writeQueue.awaitTermination(1000ms)`（`PiEngineSession.kt:982`） | teardown 线程 | 等写线程排空 | 只在 `close()`，**不在发送路径** |

* **为什么找不到死锁**：唯一会「主线程拿、别人持有」的是 `transcriptLock`，而持有者（reader 线程）
  在临界区内**不会等待任何东西**（不写 stdin、不等应答、不做 I/O）⇒ 不存在「锁内等通信、通信方等锁」
  的环。**结论：锁序死锁在本树里被结构性排除。**

**④ 组合/测量期写 state、`while`/递归 —— 逐个查完，本 App 侧没有。**
* 全 App UI **没有任何自定义 `Layout` / `MeasurePolicy` / `Modifier.layout`**（唯一命中是
  `TailFollow.kt:30` 的注释）⇒ 「测量期写 state」这一类**没有站点**；
* 我们自己的渲染层（`ui/render/**`、`ui/blocks/**`）**没有 `derivedStateOf`、没有组合期 `.value =`**
  （`PiMarkdownComponents.kt:540/596` 的 `remember { booleanArrayOf(false) }` 只在 `produceState`
  协程里改；`:553/:601` 的 `withContext` 结果赋给 `value` 是在 producer 上下文里完成的）；
* 组合期唯一的「读系统」是 `LocalClipboardManager.current`（`AssistantTextBlock.kt:47`、
  `UserMessageBlock.kt:45`、`PiMarkdownComponents.kt:464`、`ToolBlockChrome.kt:184/236`）——
  它只是读一个 CompositionLocal（**不做 Binder**，真正的剪贴板读写在用户长按时）；
* `while`/递归：渲染路径上只有 `piMarkdownSource`（`PiMarkdown.kt:269-306`，`remember(markdown)` 键住、
  实测线性）与 `PiLatex` 的字符扫描（有界），加库里的 AST 递归（有界）；
* `LazyColumn` 的 key：`key = { _, item -> item.key }` 只读**已存好的** String
  （`rpc/Transcript.kt:35-217` 的构造器属性；`nextKey`（`:746`）在 reducer 里跑，不在组合期）⇒ 无重活、无自增。
* **库侧**：`LogCompositions`（`com.mikepenz.markdown.utils`）确实带一个「组合计数器」状态，
  但它被 `MarkdownLogger.enabled` 门住，而该字段是 `private static boolean`、**静态初始化器里没有赋 true**
  （`javap` 已核），且**本 App 从不引用 `MarkdownLogger`** ⇒ 是死代码，本次不是它。
  库的 `MarkdownText` 里确有 **`onPlaced { containerSize.value = … }`（测量/放置期写 state）**，
  并被一个 `derivedStateOf` 读取 —— 但它收敛：写的是同一个 `Size`（结构相等 ⇒ 不通知），
  且 `derivedStateOf` 结果相等就不向下失效；纯文本节点（无图片）不会有 `imageSizeByLink` 的
  写-读回路。**结论：不是硬阻塞，但它是这条路径上唯一的「测量期写 state」站点，值得设备上排除。**

**⑤ `withFrameNanos` 自锁 —— 不会反过来阻塞帧的产生。**
`ChatScreen.kt:508-552` 的跟随 effect 确实以 `withFrameNanos {}` 开头，且键里有 `state.revision`
（每个流式事件都变）⇒ **每个发布重启一次、每次重启都取消上一个帧回调再注册新的**。这会产生
**帧回调注册/取消的抖动**（O(发布次数)），但 `withFrameNanos` 是**挂起**等待：它不占线程，
也不会阻止 Choreographer 派发帧。**唯一可设想的恶性形态**是「每次注册都被下一帧的取消吃掉、
永远等不到回调 ⇒ 跟随永不生效」——那是功能失效，不是 ANR。⇒ **排除**（但见 §9.3 的取证项）。

**⑥ 快照写竞争 —— 没有站点。**
全 App 无 `Snapshot.withMutableSnapshot`/`takeMutableSnapshot`（grep 0 命中）；后台线程只写
`StateFlow`（引擎的 `_publication`/`_changes`/`_state` 与 `MarkdownStateImpl.stateFlow`），
**不写 Compose snapshot state**；Compose state 的写全在 `produceState`/组合/effect 的主线程上
⇒ 不存在「主线程在长组合里等后台快照写入」的同步点。**排除。**

### 9.2 诚实结论

**在「新会话第一条回复第一个 token」这条路径上，我没有找到能在主线程上卡死 ≥5 秒的代码级硬阻塞。**
能证的两条都写在上面：① 是**主线程上的系统服务（Binder）调用**（`PiEngineService.applyWork`），
它是本路径上**唯一**主线程同步 Binder 工作，也是我给出的第一嫌疑；② 是 Default 线程饥饿（延迟，不是 ANR）。
其余五类（RPC 等待 / 锁序死锁 / 组合期写 state 或死循环 / `withFrameNanos` 自锁 / 快照竞争）
**都被结构性排除**，理由在 §9.1。

### 9.3 判定树（用户两条回答已到位）+ 下一步取证

| 用户的观察 | 判定 | 指向哪条链 | 下一步 |
|---|---|---|---|
| 每秒读数**也不跳** | **「一帧画不完」或硬阻塞，两者现象相同**（初稿误判为「硬阻塞」） | **设备验证的答案是 §2 的 markdown 链（自我延续的重组循环）**，不是 Binder | 已结案：见 §0 与 §10 |
| 等一会**自己恢复并继续出字** | 忙/慢 | §2 机制 | 量修 1+2 前后 `dumpsys gfxinfo` |
| 等一会**弹「停止运行」** | ANR 被杀（已确认） | 同「硬阻塞」 | 同上 |

**下一步取证（README 只需这一条）：拿主线程栈 —— 这是唯一能一锤定音的东西。**
```bash
# 卡死之后（已弹「无响应/停止运行」）跑，不需要 root：dropbox 里的 ANR 记录带完整线程栈
adb shell dumpsys dropbox --print data_app_anr | head -200
# 或整个 bugreport（不需要 root，包含 /data/anr 的 traces）
adb bugreport pi-anr.zip
```
**只看一件事：主线程（`"main"`）栈顶。**
* 栈顶是 `android.os.BinderProxy.transactNative` / `android.os.Binder.transact` ⇒ **①成立**，
  按 §9.1-① 的最小修复改 `PiEngineService.reportWork`（挪出主线程）；
* 栈顶是 `androidx.compose.runtime.*` / `LazyLayout*` / `Text` 排版 ⇒ **是「一帧画不完」**，
  那就要在 `ChatScreen`/库的 markdown 渲染里继续找（我 §9.1-④ 已把本 App 侧排除，
  剩下的嫌疑在库的 `MarkdownText`/`MarkdownSuccess` 与行数很多的 `Column`）；
* 栈顶是 `File`/`Socket`/`synchronized` ⇒ 把对应调用点报给我，那是我漏掉的一条。

**顺带能一次砍掉一半的对照实验（零改动，2 分钟）**：
打开 App 的**设置 → 运行时/实例**里把 `app.runtime.keepAlive` 关掉（它决定前台服务起不起，
`PiSessionViewModel.kt:962` 的 `if (prefs.keepAlive) startEngineService()`），再复现一次：
* **仍然卡死** ⇒ 与 ① 的唤醒锁/通知无关（服务根本没起，`reportWork` 是 no-op：`PiEngineService.kt:228-230`
  的 `instance?.` 为 null）⇒ 直接把 ① 划掉，回到「平台/库」两支；
* **不卡了** ⇒ ① 基本成立，按 §9.1-① 修。

### 9.4 ① 的补丁（**结论：不落。** 保留下来只为「将来真遇到 Binder 类 ANR」时可直接套用）

**目标**：让 `reportWork` 变成一次「投递」，调用方（主线程）立即返回；Binder（唤醒锁 + 通知 +
两次 `PendingIntent` 查询）全部在后台执行。**不动 `syncTranscript`** —— 它是普通函数，
在里面加 `withContext` 要么改挂起语义、要么得用 `runBlocking`，两者都会改变状态发布的时序。

```diff
--- a/app/src/main/kotlin/app/pi/service/PiEngineService.kt
+++ b/app/src/main/kotlin/app/pi/service/PiEngineService.kt
@@ class PiEngineService : Service() {
     @Volatile
     private var wakeLock: PowerManager.WakeLock? = null
 
+    /**
+     * The one thread that applies work state, because applying it is Binder work.
+     *
+     * `reportWork` is called from `PiSessionViewModel.reportWakeLockNeed`, which runs on the
+     * **frame thread** (it is reached from `syncTranscript`). Everything below `applyWork`
+     * talks to the system server: `PowerManager.newWakeLock`/`acquire`, two
+     * `PendingIntent.get*` lookups and `NotificationManagerCompat.notify`. Those are cheap
+     * when the system server is idle and *not* cheap when it is not — and the moment this
+     * fires (a turn starting, right after boot) is exactly the moment this app's own
+     * proot+node engine is competing for CPU. A blocked transaction here freezes every
+     * frame, which is the reported 卡死 (`design/ui-refactor/08-hang-diagnosis.md` §9.1-①).
+     *
+     * One thread, not a pool: the calls are ordered (a `true` immediately followed by a
+     * `false` must end in `false`), and there is at most one of them in flight per turn.
+     * Same shape as `PiEngineSession`'s stdin writer (`PiEngineSession.kt:171-176`).
+     */
+    private val workQueue: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
+        Thread(runnable, "pi-engine-work").apply { isDaemon = true }
+    }
+
+    /**
+     * False once the service is being torn down. A queued `applyWork` must not re-acquire a
+     * wake lock, and must not re-post a notification, after `stopForeground(REMOVE)` has
+     * already removed it — an ongoing 「正在运行」 notice over a service that is gone is the
+     * one thing `onStartCommand`'s KDoc says must not happen.
+     */
+    @Volatile
+    private var workAlive = true
+
     override fun onBind(intent: Intent?): IBinder? = null
@@ 
     private fun applyWork(active: Boolean) {
-        if (active) acquireWakeLock() else releaseWakeLock()
-        updateNotification(if (active) 1 else 0)
+        // Enqueue only. Never touch the lock or the notification from the caller's thread.
+        runCatching { workQueue.execute { applyWorkNow(active) } }
+    }
+
+    /** [applyWork] on [workQueue]. The `workAlive` check is what makes teardown safe. */
+    private fun applyWorkNow(active: Boolean) {
+        if (!workAlive) return
+        if (active) acquireWakeLock() else releaseWakeLock()
+        updateNotification(if (active) 1 else 0)
     }
@@ 
     private fun stopEngineAndSelf() {
+        // Set first, before `stopForeground` removes the notification: a `applyWork(true)`
+        // that is already queued must not run after the removal and re-post it.
+        workAlive = false
         // The engine first, then the service that exists for it: `PiEngineController`
         // invokes the handler its owner registered (`PiSessionViewModel.stopEngineHook`
@@ 
     override fun onDestroy() {
-        releaseWakeLock()
+        workAlive = false
+        // Queued work still runs (each task checks the flag), and the release is queued
+        // behind it so it is the last word. The queue is drained with `shutdown()` (not
+        // `shutdownNow()`): dropping the release could leave the wake lock held by a dead
+        // service.
+        runCatching {
+            workQueue.execute {
+                releaseWakeLock()
+                workQueue.shutdown()
+            }
+        }
+        runCatching { workQueue.shutdown() }
         // Cleared *after* the lock is released, so a reader that sees no instance
         // can never also believe a lock is still held by it.
         instance = null
         super.onDestroy()
     }
```

**要写清的语义（这也是抄这个补丁时最容易漏的部分）**

1. **`reportWork` 变成纯投递**：`instance?.applyWork(active)` 现在只做一次
   `workQueue.execute{…}`，主线程不再进 Binder。`reportWakeLockNeed`
   （`PiSessionViewModel.kt:833-850`）与它的门控（`reportedWork`）**一个字都不用改**。
2. **通知能延后吗？能。** 通知里只有「%d 个任务进行中」这一个计数（`buildNotification`，
   `:103-126`），它是**说明性**的；延后一帧到一毫秒没有任何用户可见差别。
   唤醒锁也能延后：它的作用是「别让 CPU 在一个回合中途睡下去」，那是秒级的事，
   而这里的延后是同一次 `execute` 的排队时间（微秒级）。**唯一不能延后的是顺序**，
   单线程队列保证了它。
3. **漏一次通知/释放的三种窗口，以及各自靠什么挡住**：
   * `true`→`false` 连着来：单线程队列按序执行，终态正确（`applyWorkNow` 各跑一次）；
   * 服务被停掉（通知栏「停止」或 `stopIfRunning()`）：`workAlive = false` 在
     `stopEngineAndSelf()` 的**第一行**设置，早于 `ServiceCompat.stopForeground(REMOVE)`，
     所以队列里那个还没跑的 `applyWorkNow(true)` 会直接 return，不会把通知贴回来；
   * `onDestroy`：队列里最后一项是 `releaseWakeLock()` + `shutdown()`，
     而 `releaseWakeLock` 本身是幂等的（`wakeLock?.let { if (it.isHeld) it.release() }`，`:176-179`）。
     `shutdown()`（不是 `shutdownNow()`）保证这一步不会被丢掉。
4. **刻意不动的两处**：
   * `startForegroundWithNotification()` 留在主线程：它必须在 `startForegroundService` 之后
     10 秒内调到 `startForeground`，而且它只发生**一次**（服务冷启动，即 boot 期间，
     那时 Composer 还没交付、用户也还没法打字），不属于本次 ANR 的那一刻。
     若想一并收干净，可把里面的 `acquireWakeLock()` 单独挪到 `workQueue`（可选，不在这份最小补丁里）。
   * `stopEngineAndSelf()` 里的 `releaseWakeLock()` 也留在主线程：它是**用户点通知**触发的
     一次性路径，且 `stopSelf()` 要紧接着执行；把它挪进队列反而会让「停服务」多等一次排队。
5. **为什么不能改成 `syncTranscript` 里 `withContext`**：`syncTranscript` 是普通函数，
   调用点分布在 `send()`/`replayHistory()`/publication collector 里。把它改成 suspend 会
   连带改发布时序（例如 `send()` 里那句「读一次 publication 让回显行同帧可见」，
   `PiSessionViewModel.kt:2138-2140` 的注释就是为这个存在的），而且 `reportWakeLockNeed`
   还有别的调用点。**这份补丁把改动关在 `PiEngineService` 内部，正是为了不动发布链。**

### 9.5 ② 独立小缺陷：首个代码围栏会先空白 0.7–1.5 秒（**与本次卡死无关**；只登记，不夹带）

**要解决的问题**：`BoundedWorkers.submit` 会在**调用它的那条线程**上阻塞等待，最多
`CALLER_WAIT_MS = 750 ms`（`PiNodeCodeHighlighter.kt:63`、`:199-215`），而调用它的是
`PiMarkdownComponents.kt:553` 的 `withContext(Dispatchers.Default)` —— 与 **markdown 解析**
（库 `MarkdownStateImpl.parse` 也是 `Dispatchers.Default`）**抢同一个池**。
第一条回复一次出现几个代码围栏时，Default 的几条线程被各占 750 ms，解析与渲染整体被推迟。
**注意：它是「延迟」，不是 9.1-① 那种主线程硬阻塞**；先修它是因为它会**掩盖**①的实验结论。

**方案 A（一行，最小，推荐先做）——把「会阻塞的等待」放到 `Dispatchers.IO`：**

```diff
--- a/app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt
+++ b/app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt
@@         } else {
             val isUpdate = hasStreamed[0]
             hasStreamed[0] = true
             if (isUpdate) delay(STREAM_SETTLE_MS)
-            withContext(Dispatchers.Default) { highlighter.highlight(code, language) }
+            // `highlight` *waits*: `BoundedWorkers.submit` parks this thread for up to
+            // CALLER_WAIT_MS (750 ms) behind the queue, and the guest may not be serving
+            // yet. `Dispatchers.Default` is the CPU pool the markdown parse also runs on,
+            // so parking a thread here starves the reply's own rendering
+            // (design/ui-refactor/08-hang-diagnosis.md §9.1-②). IO is the dispatcher for
+            // calls that block, and it is elastic.
+            withContext(Dispatchers.IO) { highlighter.highlight(code, language) }
         }
     }
```
（`:601` 的 mermaid 那行同理，如果 `PiNodeMermaidRenderer.render` 也会阻塞/等通道；
它不是本次路径，但一样的形状就该一样的处理。）

代价：`Dispatchers.IO` 会同时跑文件/网络调用；同时被占住的线程上界是「一屏代码围栏数」，
每条最多 750 ms，IO 池有 64 条上限，代价可接受。**收益**：`Dispatchers.Default` 恢复给解析用，
回复不再「先空白 0.7–1.5 秒」。

**方案 B（正确但波及面大）——把这条等待改成挂起语义，一次修掉根因：**

```kotlin
// ui/render/PiCodeHighlight.kt:109-121 —— 接口改成挂起
internal interface PiCodeHighlighter {
    suspend fun highlight(code: String, language: String?): List<PiCodeSpan>   // 原为 fun
}
internal object PiPlainCodeHighlighter : PiCodeHighlighter {
    override suspend fun highlight(code: String, language: String?): List<PiCodeSpan> = emptyList()
}
```
```kotlin
// highlight/PiNodeCodeHighlighter.kt —— 等待不再是「占线程」，而是「挂起」
private class Task(val work: () -> PiCodeHighlight?) {
    val deferred = CompletableDeferred<PiCodeHighlight?>()      // 取代 result + finished
}
suspend fun submit(work: () -> PiCodeHighlight?, waitMs: Long): PiCodeHighlight? {
    val task = Task(work)
    lock.withLock {
        if (queue.size >= queueLimit) return null
        queue.addLast(task)
        if (!started) { started = true; repeat(threads) { spawn(it) } }
        waiting.signalAll()
    }
    return withTimeoutOrNull(waitMs) { task.deferred.await() }   // 挂起，不占线程
}
// worker 线程完成时：task.deferred.complete(result)；worker 池仍是自建的 2 条线程
```
**为什么它更好**：调用方 `withContext(Dispatchers.Default)` 里的等待变成挂起 ⇒ 那条 Default 线程
**立刻被释放**，即使调用方一行不改也不再有「抢池」。**为什么先不选它**：接口是 App 的渲染缝，
改 `suspend` 会波及 `PiPlainCodeHighlighter`、两个调用点、`PiCodeHighlight.kt` 的 KDoc 契约
（它现在明说「deliberately synchronous」），而那个文件归上色代理；先上方案 A 拿实验结论，
方案 B 留给那一批收口。**两者都只写在报告里，本轮没有改任何文件。**

### 9.6 ③ 库侧「放置期写 state」怎么在设备上排除、以及不改库的规避

**它到底是什么**（库 v0.45.0 `compose/elements/MarkdownText.kt`，本机字节码同源）：
```kotlin
val containerSize = remember { mutableStateOf(Size.Unspecified) }
...
val resolved by remember(node, inlineContent.inlineContent, content, transformer, …) {
    derivedStateOf {
        val blocks = mutableListOf<BlockImageRange>()
        val map = inlineContent.inlineContent + buildImageInlineContent(
            containerSize = containerSize.value,   // ← 派生读它
            imageSizeByLink = imageSizeByLink,
            imageSizeChanged = { link, size -> imageSizeByLink += (link to size) },  // ← 派生里写
            …)
        map to blocks.sortedBy { it.start }
    }
}
val containerModifier: @Composable (Modifier) -> Modifier = { base ->
    base…onPlaced { it.parentLayoutCoordinates?.also { c -> containerSize.value = c.size.toSize() } }  // ← 放置期写
}
```
即**「放置期写 state → 派生重算（派生块里还会写 `imageSizeByLink`）→ 若结果变化则重组 → 再放置」**。
它是这份报告里唯一符合「测量/放置期写 state」形态的**库侧**站点。

**为什么我判断它对「纯文本回复」是收敛的（因此不是本次病因）**：
`containerSize` 是**结构相等**的 `Size` ⇒ 同一个尺寸再写一次**不通知**；而派生结果
（内联内容 map + 块图区间表）对**没有图片**的节点**恒为空且相等** ⇒ `derivedStateOf` 不向下失效。
真正的回路需要「容器尺寸 → 图片占位尺寸 → 文本布局 → 容器尺寸」这个环，
也就是**必须有一张图片**（`shouldPromote`/`placeholderConfig` 才会用 `containerSize`，
`onSizeDetected` 才会去写 `imageSizeByLink`）。

**设备上怎么顺手排除（不需要 ADB，用户就能答）**：
1. **卡死的那条回复里有图片吗？**（Markdown 里的 `![...](路径)`）
   * **没有图片 ⇒ 这一支直接划掉**（推理如上：无图则派生结果恒空且相等）。
   * 有图片 ⇒ 它升格为嫌疑，进第 3 步。
2. **同一台设备上，让模型在新会话第一条回复里只输出纯文字（明确要求「不要图片、不要代码」）**，
   再复现一次：仍卡死 ⇒ 与这条无关；不卡了 ⇒ 与它有关（但更可能是 §9.1-② 的围栏问题，
   因为「不要代码」同时也去掉了围栏，两个变量要在第 3 步分开）。
3. 若要把它和 §9.1-② 分开（都需要改代码，属于**下一步**而不是现在）：
   用 Layout Inspector / `dumpsys gfxinfo` 看**同一行**的重组次数是否在没有任何新内容时仍持续上涨；
   那是「派生→重组→放置」自维持的直接证据。

**不改库的规避（如果第 1/3 步判出是它，这是 App 侧最小的两条出口）**：
* **出口一（一行，在 `ui/render/PiMarkdown.kt`）**：不安装真实图片通道
  —— 把 `imageTransformer = imageTransformer` 换回库的 `NoOpImageTransformerImpl`（或直接不传）。
  这样 `MarkdownInlineImageWithSize` 的 `transformer.transform(link)` 返回 null ⇒
  `intrinsicSize == Size.Unspecified` ⇒ **`SideEffect { onSizeDetected(...) }` 不会执行** ⇒
  `imageSizeByLink` 永远空 ⇒ 派生块里的那次写消失、回路断掉。
  代价：markdown 里的图片退回「alt + 源码」文本回退（pi 在无图形终端里本来就是这么显示的，
  所以这不是「功能倒退」，而是与 pi 的文本分支一致）。**注意**：`bridge/PiGuestImageTransformer.kt`
  的文件头注释还写着「NOT WIRED YET — nothing calls rememberPiGuestImageTransformer」，
  但 `PiMarkdown.kt:164` 已经在调它并传给 `Markdown(imageTransformer = …)` —— 那条注释是过期的，
  改这个出口的人要先知道图片是**接通了的**。
* **出口二（结构性，改动更大）**：让图片走我们自己的固定尺寸块——`components.image` 的覆写点
  已经在 `PiMarkdownComponents.kt` 里，给图片一个与容器无关的固定高（而不是内联占位），
  派生结果就不再依赖 `containerSize`。这条要动上色代理的文件，优先级最低。

**结论**：③ 在「回复开头是纯文本」这个已知事实下**本来就已经出局**（第 1 步即可确认），
列在这里是为了：万一设备实验判出 ①（Binder）不成立，下一刀按 §9.6 的第 2/3 步走，
出口一可以不碰库、只改 `PiMarkdown.kt` 一行。

---

## 10. 回归：「修 2」（流式去抖）让触底跟随失效 —— 已撤回

> 用户反馈（装 `PI-a820a78` 后）：**「现在就是流式输出不会触底跟随，必须得手动往下滑，一点都不跟随。」**
> 处置：**撤掉「修 2」**（`AssistantTextBlock.kt` 的 140 ms 去抖），**保留「修 1」**
> （`PiMarkdown.kt` 的稳定解析对象 —— 那才是 ANR 的解）。

### 10.1 证实 / 证伪：先纠正触发点，再给出真正的原因

**假设里「effect 不重跑」这一半是错的。** 跟随 effect 的键是
（`ChatScreen.kt:522-530`）：
```kotlin
LaunchedEffect(state.revision, state.streaming, renderedItems.size, following, tailPoke, sessionKey, bottomInset)
```
`state.revision` **每个发布都会变**（`PiSessionViewModel.syncTranscript` 每次发布都写
`revision = pub.revision`，见 §2.3-B1 的行号），所以 effect **照样每个发布重启一次**。
「内容长了但不改任何一个键 ⇒ effect 不重跑」不成立。

**但「去抖导致不跟随」这个结论成立，机制是另一条 —— 触发是「发布」，它测量的却是「已定格的几何」：**

1. effect 的体是 `withFrameNanos { }` → 读 `listState.layoutInfo`（`ChatScreen.kt:531-537`）。
   这个读**按设计就晚一帧**（注释自己写着 "reading `layoutInfo` before the layout pass would compute
   the pin from the previous frame's geometry"）。⇒ **体要走完，需要重启之后有约 1–2 帧的安静窗口。**
2. **去抖之前**：那一行**每个发布都在长**（每个 delta 都重解析 + 整篇重渲染）。
   于是**无论哪一次体走完，看到的几何都变过** ⇒ `pinToTail()` 一定给出 pin
   （`TailFollow.kt:346-354`：`hidden = lastVisibleOffsetPx + lastVisibleSizePx - viewportEndOffsetPx > 0`）
   ⇒ 跟随虽抖，但一直在跟。
3. **去抖之后**：那一行**只在 140 ms 的闸门处**长一下（一个脉冲），两次脉冲之间
   `hidden <= 0`（已经在底部）⇒ `pinToTail()` 返回 `null` ⇒ 不发 pin。
   而一次增长脉冲只有在「体正好在那之后 1–2 帧内走完」时才被观察到 ——
   重启由发布驱动（可以比一帧更快），大多数体走完都落在别处 ⇒ **pin 几乎永远不发**
   ⇒ **「一点都不跟随」**。
4. 与用户「必须手动往下滑」吻合：手动滚动会让
   `snapshotFlow { listState.isScrollInProgress }` 起上升沿 → `pauseTail()`（`ChatScreen.kt:558-565`），
   即他一旦自己滚，跟随就被显式暂停，于是他看到的就是「完全靠手」。
5. 另一条旁证：那条 effect 自己的 KDoc 就把契约写成「键随**发布**移动、体读**已定格的几何**」
   （`ChatScreen.kt:474-477`）。**去抖恰好破坏了「每次体走完几何都变过」这个隐含前提。**

**结论**：去抖与这条跟随设计**结构性不兼容**——去抖的目的就是让几何**别**每次发布都变，
而跟随正是靠「几何每次发布都变」来补上它「晚一帧读」的缺陷。**在 `ChatScreen.kt` 之外无法两全。**

### 10.2 处置与代价（用测量说话）

**处置**：`ui/blocks/AssistantTextBlock.kt` 撤回 `settledStreamingText` / `STREAM_TEXT_WINDOW_MS` /
那 7 个 import 与 `delay`；`markdown = item.text` 原样恢复。相对修复前的原文
（`git diff 6465184~1`）**只多 12 行注释**，内容是「别再往这个块里加去抖」以及为什么。
`ui/render/PiMarkdown.kt` 的修 1 **一字未动**（`:145-147`、`:189`，`retainState = true` 在 `:235`）。

**撤回去抖会带回什么、不会带回什么**（这是「ANR 会不会复现」的判据）：

| | 去抖前（修 1 之前） | 现在（只有修 1） |
|---|---|---|
| 触发源 | **任何一次重组**（敲键、滚动引起的 ChatBody 重组、跟随自己的 remeasure…） | **只有内容变化**（= 引擎发布，10–50 次/秒） |
| 文本没变时 | **照样**重解析 + 整篇重渲染（库内部 remember 被新默认对象击穿） | **不解析、不渲染**（`Input` 相等 ⇒ `updateInput` 早退） |
| 一帧能否画完 | 不能：工作可被自身/任意重组再次触发 ⇒ **一帧永远画不完** ⇒ 输入 5 s 超时 | 能：每次发布一份工作，做完即完成该帧 |
| 每次发布的成本（测量，桌面 JVM；手机约 2–5×） | 同下但 ×「该帧内的重组次数」（无界） | **8.95 ms/1026 节点（5 KB）、13.79/3021（15 KB）、22.97/9976（50 KB）、58.02/29811（150 KB）**；顶层 composable 141/414/1365/4080 |

⇒ **预期是「掉帧」而不是「ANR」**：15 KB 文档一次约 30–60 ms（手机），帧仍会完成，输入每帧都被处理
（约 15–30 fps）。**自我延续那一条被修 1 关掉，这才是 ANR 的解**；去抖只是把「每次发布一次」又砍成
「每 140 ms 一次」，代价是跟随失效 —— 这是一笔不划算的交易，所以撤回。
**残留风险与判据**：若长回答流式时仍出现「几秒不动」，那就不再是这条链（§0 的未知清单第 2 条），
下一刀是 §7.2 的修 3 或下面 10.3 的 (ii)。

### 10.3 如果将来仍需要「少重渲染」（**都要动 `ChatScreen.kt`，本报告不动**）

去抖本身不是错，错在它**对跟随不可见**。三条正路，按代价排序，全部落在 B4 正在改的文件里，
所以只登记、由父代理安排串行：

1. **把「已定格文本」变成一个 effect 能观察的键**：在 `ChatScreen` 的键表里加一个
   「流式行已定格文本的版本号/长度」（需要把该状态提到 `ChatBody` 或 ViewModel 可读的地方），
   这样增长仍然对 effect 可见；去抖与跟随就能共存。**改动面：`ChatScreen.kt` + 一处状态提升。**
2. **先把 effect 的完成时延降下来**：`withFrameNanos { }`（`ChatScreen.kt:533`）这一等，
   换来的是「读上一帧的几何」——而它**本来就是上一帧的几何**（见该处注释）。
   去掉这一等，体在**重启所在的那一帧**就能走完，被下一个发布打断的概率大降，
   增长脉冲被观察到的概率随之上升。**需要设备验证**：它是否会让 pin 在「本轮布局还没算完」时发出
   （这也是当初加这一等的理由），必须先量再加。
3. **不靠去抖，改去砍「一次渲染的代价」**：换用库的 `StreamingMarkdownState`
   （增量解析、只重渲染不稳定尾巴）。这是渲染入口的更换，改动最大，收益也最正。
   **不要**用「把整段文本截断到最近 N 行」这种假节省 —— 那会改用户看到的内容。
