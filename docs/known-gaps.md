# 已登记的缺口与延期项

这份文档存在的理由：**延期项不能只活在对话里。**在此之前，"以后再做"的判断散落在聊天记录和编码会话的任务列表里，一旦会话结束就丢了。凡是有明确原因被推迟、或者已知但尚未实现的，全部记在这里，并写清楚：为什么延期、卡在哪、怎么收尾。

格式约定：每条都有 **原因**（为什么不现在做）和 **收尾条件**（什么情况下可以做）。

状态标记：
- `延期` —— 想清楚了，有明确的阻塞点，解开了就做
- `未开始` —— RPC 路线上可实现，还没动
- `待验证` —— 代码写完了，但**从未在真机上跑过**
- `已定妥协` —— 不是待办，是刻意的设计取舍，写在这里以免被当成 bug
- `已完成（复核于 dc00279）` —— **逐行复核发现代码已经做完，但条目还写着"未开始/延期"。** 凡带这个标记的条目，收尾条件都已满足；**不要照着旧正文重做**。逐行的处置账本在 `docs/gap-disposition.md`（69 行，含状态列），那份文档是剩余工作的唯一权威清单；本文件负责"为什么延期 / 怎么收尾"的背景。

---

## A. 延期（有明确阻塞点）

### A1. 压缩摘要 / 分支摘要 / Hook 消息改用 markdown 渲染 —— **已完成（复核于 dc00279）**
> **状态：收尾条件已满足，本条不再是缺口。** 折叠态纯文本 + `maxLines`、展开态 markdown 的做法已经落地：`ui/blocks/CompactionBlock.kt:104`、`ui/blocks/BranchSummaryBlock.kt:80`、`ui/blocks/HookMessageBlock.kt:65` 都调用 `PiMarkdownText`，折叠预览仍用 `COLLAPSED_SUMMARY_LINES`——正是本条要求的"不为了保真把折叠预览弄坏"。随五代理 checkpoint `46015ad` 落地。复核未改代码；下面的原始原因保留，只用于解释设计取舍。
- **为什么该做**：这三块的内容是**模型写的散文**。pi 源码里 `compactionSummary` 走 `CompactionSummaryMessageComponent`，其第 43 行是 `new Markdown(header + this.message.summary, ...)`；`branchSummary` 同样走 markdown 组件；`HookMessageBlock` 的数据字段名直接就叫 `markdown`。纯文本渲染会把这些内容里的标题、列表、粗体、代码全部显示成源码。
- **阻塞点**：`CompactionBlock` 和 `BranchSummaryBlock` 现在用 `maxLines`（2 行 / 4 行）实现"收起预览，点开展开"。markdown 渲染器**没有 `maxLines`**，直接替换会**丢掉折叠预览**——那是实际功能，不能为了保真把它弄坏。
- **收尾条件 / 做法**：折叠态继续用纯文本 + `maxLines`（把前 N 行文字截出来即可），展开态切到 `PiMarkdownText`。约 30 行改动，落在 `ui/blocks/CompactionBlock.kt`、`BranchSummaryBlock.kt`、`HookMessageBlock.kt`。
- **已定的**：`ErrorBlock` / `SystemPromptBlock` / `SkillInvocationBlock` 是否也该走 markdown —— 已逐个回 pi 源码确认：`ErrorBlock` 保持纯文本（pi 用 `new Text(...)`，`interactive-mode.ts:2829-2830`）、`SystemPromptBlock` **已被整条删除**（没有生产者：pi 没有该转录组件、也没有 RPC 命令返回 prompt；`docs/gap-disposition.md` §10.1 的 A1/F22 行是账本）、`SkillInvocationBlock` **pi 是 markdown，已改**（`components/skill-invocation-message.ts:36-45`；正文用 `customMessageText`（`:43`）、卡片底色用 `customMessageBg`（`:17`）；唯一有意偏离是不重复 pi 的 `**name**` 正文头，理由写在块内）。`docs/gap-disposition.md` §10.1 是这三条的账本。

### A2. LaTeX（行内 `$...$` / 块级 `$$...$$`）渲染 —— **已完成（复核于 dc00279）**
> **状态：收尾条件已满足，本条不再是缺口。** `ui/render/PiLatex.kt` 已存在（`toUnicode` / `toDisplayUnicode`），并被 `ui/render/PiMarkdown.kt:176,180` 调用：块级 `$$` 与行内 `$` 都走 `renderLatex` 的等价物，无法归约的原文原样保留（`latexToken.raw` 的行为）。**A2 指出的 `custom()` 分派陷阱是被显式处理的，不是绕开的**：`ui/render/PiMarkdownComponents.kt:102` 提供 `custom`，`:146-149` 的 `when` （在 `:145` 的 `piMathComponent` 里）**只认 `INLINE_MATH` / `BLOCK_MATH` 且没有 `else` 分支**——因为 `custom` 的返回类型是 `Unit`（永远非 null），`MarkdownElementInternal` 的 `handled = components.custom?.invoke(...) != null` 会把任何被调用的节点都判为"已处理"；唯一能保住"未识别 ⇒ 递归子节点"默认行为的方式就是不认领它们，分发器自己的判断不会被这里改写。同一段 KDoc 已把这个陷阱写给下一个人。数学归约的结果在 `PiMarkdown.kt:132-167` 的 `piMarkdownSource` 里**在解析前**替换原文（代码围栏与行内代码跳过），因为库的 annotator 会把 math 节点的原始源文本追加进段落 `AnnotatedString`，用组件渲染反而会留下 `$x^2$` 本尊。未移植 pi 的竖直排版（矩阵、上下堆叠的分数/上下限）。**这条限制现在写清楚了，而且它不是 pi 侧"反正是行内"——pi 对块级公式是真的排版的**：`markdown.ts:509` 给块级 `$$…$$` 传 `display: true`，于是 `\frac` 走 `shouldStack`（`packages/tui/src/latex.ts:1017-1031`）、带上下限的算符走 `:1145-1148`、`\begin{matrix}` 等环境走 `:1301-1355`，最后由 `renderLayout`（`:723-809`）拼成字符网格；行内 `$…$` 走 `markdown.ts:649`，`display` 为 false，**`\frac` 堆叠（`latex.ts:1018`）与算符上下限（`:1145`）是唯一两个被 `display` 门控的分支**，所以这两项在行内与 pi 完全一致；但**环境分支不受 `display` 门控**——`\begin` 直接进 `parseEnvironment`（`:1090-1091`），多行矩阵一定会变成 layout node（`:1350-1354`），而 `renderLatex` 只要收过 layout node 就会跑 `renderLayout`（`:1382-1385`），所以 pi 连行内的 `\begin{pmatrix}` 也画网格。App 侧的可见后果：`\frac{a}{b}`、`\sum_{i=1}^{n}` 只在 `$$…$$` 内偏离（行内它们本来就是 pi 的样子）；`\begin{pmatrix}` 等环境**行内与块级都**让整条公式归约失败，因而原文照排。**为什么不直接移植 `renderLayout`**：App 是在解析**之前**把归约结果替换进 markdown 源码的（`PiMarkdown.kt` 的 `piMarkdownSource`），而渲染器的 annotator 会把段落内的换行变成空格（`MarkdownAnnotatorConfig.eolAsNewLine` 默认 `false`；`annotator/AnnotatedStringKtx.kt:357` 的 `EOL -> if (eolAsNewLine) append('\n') else append(' ')`），所以算出来的网格会被重新压回一行——移植布局函数的前提是先有一个能保住行结构的通道（围栏或块级 math 组件），那属于渲染架构改动，不在 `PiLatex.kt` 里。逐条对照表写在 `ui/render/PiLatex.kt` 的文件头。
- **为什么该做**：pi 支持 LaTeX（`packages/tui/src/components/markdown.ts` 里的 `LATEX_MARKDOWN_EXTENSIONS` + `renderLatex()`）。我们的解析层其实**已经能识别**：`org.intellij.markdown` 的 GFM flavour 定义了 `GFMElementTypes.INLINE_MATH`、`BLOCK_MATH`，还带一个 `MathGeneratingProvider`。缺的只是渲染。
- **阻塞点**：`MarkdownComponents.custom()` 的调用约定有陷阱 —— 渲染器的分派代码是
  `handled = components.custom?.invoke(node.type, model) != null`，
  而 `custom` 的返回类型是 `Unit`（永远非 null）。**一旦提供 `custom`，所有未识别的块级节点都会被判定为"已处理"**，从而关闭原本的"递归渲染子节点"兜底。所以必须先决定：要么让 `custom` 只接管 math 类型并把其余类型显式交给默认组件，要么在扩展里自建 flavour。
- **收尾条件**：先解决上面这个分派陷阱（写一个只认 math、其余类型转发默认行为的 `custom`），再接公式排版组件。

### A3. 图片（markdown 内嵌 `![](...)` ） —— **已完成（通道 `07c27861` + 接线于本次）**
> **状态：收尾条件已满足，本条不再是缺口。** 取字节通道已由 `07c27861` 交付（`app/src/main/kotlin/app/pi/bridge/GuestImageBytes.kt` 解析 `data:` 与 guest 路径、`PiGuestImageTransformer.kt` 实现 `ImageTransformer` 并带 LRU+字节上限的位图缓存），**渲染侧的接线也已落地**：`ui/render/PiMarkdown.kt` 调 `rememberPiGuestImageTransformer()`，同一个实例既进 `LocalPiImageTransformer`（`PiImagePlaceholder` 用它判断能否画）又作为 `Markdown(imageTransformer = …)` 参数进**库自己的** `LocalImageTransformer`（库的 `MarkdownImage`/`MarkdownInlineImage` 只读后者——`compose/Markdown.kt:258`、`:347`）；`PiImagePlaceholder` 现在按 `transform` 的**返回值**决定：拿到 `ImageData` 才转发给 `MarkdownImage`，否则保留 alt + 来源文本（`MarkdownImage.kt:17-29` 在 `null` 时会整节点消失，所以只看 transformer 类型会让解析不了的链接比今天更差）。`http(s)` 被**有意拒绝**（不出网，见 `GuestImageBytes` KDoc），走的就是 alt + 来源那条分支。
> ***行内*图片也已补齐**：库把段落里的图片走的是 `inlineImage` 槽而不是 `image`（`.../elements/MarkdownText.kt:384`），它的默认实现在 `transform` 返回 `null` 时**什么都不画**（`.../elements/MarkdownInlineImage.kt:13`）。`ui/render/PiMarkdownComponents.kt:290-299` 的 `PiInlineImage` 现在接管这个槽，失败时渲染与块级**同一个** `PiImageFallback`（`:314-333`），两种表面不会有两套文案。
> **剩下的是模型限制，不是没做**：行内槽里 `content` 是解析后的 link、`node` 是**外层文本节点**（`.../elements/MarkdownText.kt:384-386`），annotator 只把 URL 塞进 inline content（`.../annotator/AnnotatedStringKtx.kt:280-282`），**alt 文本根本到不了这个槽**——所以解析不了的行内图显示的是「图片 + 来源」，而不是 pi 那行 alt。要拿到 alt 得连 annotator 一起接管、把 alt 与 link 编码进同一个 inline tag，那是另一个设计决策，这里只记录、不擅自动。
- **为什么该做**：库里 `MarkdownImageKt` / `MarkdownInlineImageKt` / `ImageTransformer` / `ImageData` / `PlaceholderConfig` 一应俱全，但默认实现是 `NoOpImageTransformerImpl`（什么都不加载）。
- **阻塞点（已解开）**：pi 的图片不是 http URL，而是引擎侧的文件。`07c27861` 定下了取字节的方式（`data:` 就地解码；`file:`/裸路径按 guest→host 映射读文件；`http(s)` 拒绝），渲染侧只负责注入。
- **附注**：pi 的**终端界面根本不渲染图片**（终端放不下），所以这一项如果我们做了，是**比 pi 强**，不是补齐。

---

## B. 未开始（RPC 路线可实现，尚未动）

> **复核提示（dc00279；B12 于本轮更新）**：B1–B4 的收尾条件**都已经满足**，只是标题还写着"未开始"。**B5/B6/B7 的代码半边都已收尾**（B7 的"路径错位"见 §B7 末条的更正；B5 记的"安装物落在易失 rootfs"副作用随该绑定消失），只剩真机收尾；**B12 已收尾**（`ba297c7`，见 §B12 末条）。

### B1. `/` 命令面板 —— **已完成（复核于 dc00279）**
> **状态：已接线。** `get_commands` 现在是活路径：`PiSessionViewModel.refreshCommands()` → `PiSlashCommands.piCommandPalette()` → `ui/chat/SlashPalette.kt` 的分组面板；`routeComposerText` 把 `/name` 派发为命令、生成为 prompt、或标成"仅终端"，组内保留 `name:1` 后缀与 `sourceInfo` 来源标签。随 checkpoint `46015ad` 落地。
- 引擎支持 `get_commands`，返回扩展命令、prompt 模板、skills 三类，`source` 字段区分来源。
- `:rpc` 里 `PiCommands.getCommands(id)` 已经存在，但**没有任何调用方**。
- 收尾：输入框键入 `/` 时拉取并分组展示，选中后按 `prompt` 命令发送。

### B2. 会话管理：会话树 / fork / clone / switch / 重命名 / 导出 —— **已完成（复核于 dc00279）**
> **状态：全部已接线。** `get_tree` → `ui/chat/SessionTreeScreen.kt`（分支/条目两个 tab，标签已解析）；`fork` → tree 与 fork 选择器；`clone` → 溢出菜单；`switch_session` → `SessionsScreen`；`set_session_name` → 重命名对话框；`export_html` → 溢出菜单与 `/export`。**注意**：这里完成的是"能到达"，不代表语义与 pi 相同——树上的「分支」是 fork（写新文件），不是 pi 的原地跳分支，那条是 `gap-disposition.md` #27（LIMIT）。
- `get_tree`、`get_entries`、`get_fork_messages`、`fork`、`clone`、`switch_session`、`set_session_name`、`export_html` 的 builder 全部已存在，**全部无调用方**。
- 收尾：会话树 UI（分支可视化）+ 详情页动作。

### B3. 模型与思考等级选择器 / 队列模式 / 压缩与重试开关 —— **已完成（复核于 dc00279）**
> **状态：全部已接线。** 模型选择器与循环切换、思考等级选择器与循环、`set_steering_mode`/`set_follow_up_mode`（`SessionToolsSheet`）、`compact`（`/compact` 与工具面板）、`set_auto_compaction`、`set_auto_retry`/`abort_retry` 都有调用方。**例外**：`set_follow_up_mode` 能配置的队列**没有东西能入队**，因为输入框只会 `steer`——那是 `gap-disposition.md` #3（I4），与本条已完成的开关接线是两件事。
- `get_available_models`、`set_model`、`cycle_model`、`get_available_thinking_levels`、`set_thinking_level`、`cycle_thinking_level`、`set_steering_mode`、`set_follow_up_mode`、`compact`、`set_auto_compaction`、`set_auto_retry`、`abort_retry` —— builder 全部存在，**全部无调用方**。

### B4. bash 模式（在会话里直接跑命令） —— **已完成（复核于 dc00279）**
> **状态：已接线。** 输入框的 `!` / `!!` 由 `ui/chat/SlashPalette.kt:212-216` 路由到 `PiSessionViewModel.runBash(command, excludeFromContext)`（`bash` + `excludeFromContext`），`BashPanel` 提供 `abort_bash` 的停止入口。随 checkpoint `46015ad` 落地。
- `bash` / `abort_bash` 两个 builder 存在且未使用。

### B5. 扩展包管理（`pi install` / `pi list` / `pi remove`）—— **待验证**
- **RPC 协议里确实没有对应命令**，已核对 `rpc-types.ts:20-74` 的 `RpcCommand` 联合类型：只有 prompt/steer/abort/new_session/模型/思考/压缩/重试/bash/会话/消息/`get_commands`，没有 `install`、`remove`、`list`、`reload`。
- **但「直接编辑 `settings.json` 的 `packages` 数组」是错的做法**（本文档原先这么写）。pi 的安装不是一次设置写入：`DefaultPackageManager.install` 先跑 `npm install --prefix <agentDir>/npm --legacy-peer-deps`（`package-manager.ts:1785-1812`）或 `git clone`（`:1831-1863`），装完才由 `installAndPersist` 追加设置项（`:1029-1032`）。只改设置 = 配置了一个磁盘上不存在的包。
- **依赖规则已核对，与原先的说法不同**：`--omit=dev` 只出现在 **git 来源的依赖安装**（`getGitDependencyInstallArgs`，`package-manager.ts:1772-1778`）和管理式自更新的 `npm ci`（`package-manager-cli.ts:89-99`）。**npm 来源**的安装参数是 `install <spec> --prefix <root> --legacy-peer-deps`，**没有 `--omit=dev`**（`:1805`）。两种情况下 `devDependencies` 在运行期都不可用，但理由是「装的是生产依赖」与「只有 git 包会补装依赖」两件事，不要合并成一句。
- **实现（已完成，未上真机）**：`app/src/main/kotlin/app/pi/packages/`。
  - `PiPackageService` 在 guest 里执行 pi 自己的命令行，不重新实现安装逻辑。
  - guest 命令行（`cwd` = 工作区，工作区按 `PiEngineHost.guestPathFor` 的规则绑定）：
    ```
    /bin/bash -lc "exec /opt/node/bin/node \
      /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js \
      install 'npm:@scope/name@1.0.0' -l --approve"
    ```
    `install`/`remove`/`list` 三个动词、`-l`、`--approve` 的位置都对应 `package-manager-cli.ts:375-573` 的解析器；node 与 cli.js 路径与 `PiEngineHost.kt:95`、`:104` 完全一致。
  - **不走 PTY**（不用 `PtyLauncher.start`）。理由是可验证的：pi 用 `process.stdin.isTTY && process.stdout.isTTY` 判断 `appMode`（`package-manager-cli.ts:733-735`），PTY 下它是 `"interactive"`，于是 `hasUI` 为真、信任询问会走到 `ctx.ui.select`→`showStartupSelector` 并**永久阻塞**（`core/project-trust.ts:86-95`）。管道下 `hasUI` 为 `false`，信任闸门确定性地返回 `false`，`-l` 随后打印 pi 自己的错误。
  - 失败一律原样上报：pi 的错误在 stderr（`console.error(chalk.red(\`Error: ${message}\`))`，`package-manager-cli.ts:1096-1100`），成功行的 `Warning:`（`:255-263`、`:737-741`）也保留。超时与「启动失败」是 App 自己的状态，不会被伪装成包错误。
  - `pi list` 的输出是渲染而非协议，所以解析失败会显示为「无法解析」并附原文，**不会**退化成「没有已安装的包」——那正是会骗人的分支（`PiListOutput`）。
- **收尾条件**：真机上跑通一次 `pi list`。当前无法验证的部分：guest 内是否已解出 `npm`/`git`（`RuntimeProvisioner` 只软链了 node/npm/npx，未验证 npm 能连网）、`/opt/pi/.../cli.js` 是否真在同一路径、以及 10 分钟超时是否合适。
- **已知副作用（无法从本层修复）**：安装物与 `packages` 都落在 `/root/.pi/agent` 下，而那是 **rootfs 内的易失目录**——`RuntimeProvisioner.wipe()` 会整体删掉 `paths.runtime`（`RuntimeProvisioner.kt:85-91`）并只重建一个空的 `/root/.pi/agent`（`:185`）。见 B7 的「同一处路径错位」。**—— 已随 §B7 的 agent 目录绑定消失（`PiEngineHost` 的 `extraBinds` 现在含 `paths.agentDir`，见 §B7 末条）。**
  - **—— 已解决（复核于 `182823e`，工作树）**：agent 目录已被绑到 `paths.agentDir`（`engine/PiEngineHost.kt:285-295`），所以 pi 现在写的就是那份**存活于 `RuntimeProvisioner.wipe()` 之外**的目录，本段描述的"落在易失目录"不再成立。**收尾仍只剩真机跑通一次 `pi list`**（见上一行的收尾条件）。

### B6. `/reload` 热重载 —— **待验证**（已改为「显式重启 + 状态机」）
- **RPC 里没有 `reload` 命令**（同上，`rpc-types.ts:20-74`）。但重载管线**在 RPC 下是通着的**：`rpc-mode.ts:341-343` 把 `reload: async () => { await session.reload() }` 挂进了扩展的 `commandContextActions`，所以**扩展命令**调用 `ctx.reload()` 是 RPC 可达的（用 `prompt("/<扩展命令>")`）。内建 `/reload` 不行：内建命令被排除在 `get_commands` 之外（`docs/rpc.md:853`），`prompt` 只派发扩展命令（`agent-session.ts:1331-1343`）。App 目前没有这样的扩展命令，所以只能重启引擎进程。
- **实现（已完成，未上真机）**：`ExtensionLifecycle`（`app/src/main/kotlin/app/pi/packages/ExtensionLifecycle.kt`）。
  - 状态机：`Idle → Installing → NeedsRestart → AwaitingConfirmation → Restarting → Ready`，另有 `AwaitingIdle`（有回合在跑时的停放态）。
  - **绝不静默重启**：一次成功的 install/remove 只把状态推到 `NeedsRestart`。重启需要两步——`requestRestart()` 只产生「问题」，`restartStarted()` 才算「动作」；取消回到 `NeedsRestart`，不丢待办。
  - **有回合在跑时**：`requestRestart(turnRunning = true)` 进入 `AwaitingIdle`，**不重启**，没有超时、没有强制路径。用户先停回合或等它结束，再点一次；那时会重新查 `isTurnRunning()`，为假才弹确认。理由写在类注释里：App 无法预知回合何时结束，猜一个时刻去杀回合就是本状态机要防的那种静默重启。
  - 时长在确认文案里写明约 1–3 秒，并说明它会终止正在跑的回合、但磁盘上的会话（JSONL 追加写）不会丢。
  - 设置页里所有 `EffectiveKind.Reload` 的行（`PiSettingsRegistry.kt:760`、`:773`、`:797`、`:810`、`:823`；枚举定义在 `ui/components/PiCommon.kt:204`）走同一个入口 `noteExternalChange`——**`EffectiveKind.Reload` 从此有消费者了**。
- **重启入口已落地（本次授权范围内）**：`app/src/main/kotlin/app/pi/engine/PiEngineHost.kt` 现在有 `restart(reason, workspaceProvider, allowInterrupt)`，以及一个 `session: StateFlow<PiEngineSession?>`。
  - `restart` 的语义就是「停掉当前进程 → 重走 boot → 交出新的 session」，顺序是**先关旧的再起新的**：两个引擎跑在同一个 cwd 上会同时往同一个会话 JSONL 里追加。
  - **有回合在跑时自己也会拒绝**（`allowInterrupt=false` 时返回 `RefusedTurnRunning`，且**什么都没改**），所以调用方即使绕过状态机也不能静默打断回合。
  - 第三种结果 `RefusedNeedsProvisioning`：如果 stamp 与载荷派生的 revision（`RuntimeProvisioner.packagedRevision`，读 `assets/runtime-revision.txt` 里的载荷摘要；早先是一个手写常量）不一致，`restart` **拒绝执行**而不是顺手重新解包——重新解包会 `wipe()` 掉 guest 的 `/root/.pi/agent`（trust.json、packages、已安装的 npm/git 包全在里面）。「重载扩展」按钮永远不该做这件事。
  - `session` 这个 StateFlow 是给界面重新绑定用的：重启期间与重启失败后它是 `null`，界面收集它重建 `PiEngineApi`，**不会继续拿着一个已经死掉的 session**。`boot()` 也会发布并关闭旧的 session，所以「两个引擎一个 cwd」在任何路径下都不会发生。
  - 跨目录接线（`ui/**` 所有者，本层不改）：`app.pi.packages.EngineRestartCoordinator` 已经把状态机和 `restart` 接好了——`request()` 只产生确认问题，`confirm()` 才动作，并且**恒以 `allowInterrupt=false`** 调用引擎（确认不是永久杀回合的许可；确认之后才起的新回合会被引擎自己拒掉，然后回到 `AwaitingIdle` 而不是报失败）。
- **收尾条件**：真机上确认「重启后 `get_commands` 里出现新扩展/新技能」，以及重启耗时。

### B6-更优替代：用扩展命令调 `ctx.reload()`，不必重启引擎

**现状（B6）**：装 skill / prompt 模板 / 扩展后**需要重启引擎**才能生效。原因是 pi 把资源扫描结果**缓存在 loader 字段里**（`resource-loader.ts:308-314`，初始化 `[]` 于 `:285-288`），只被 `updateSkillsFromPaths`（`:672-693`）/`updatePromptsFromPaths`（`:695-717`）赋值，而它们**只被 `reload()` 调用**（`:472-473`、`:487-488`）；`reload()` 只在启动（`core/sdk.ts:185-188`）和 `session.reload()`（`agent-session.ts:2849`）各跑一次。TUI 靠 `/reload` 解决，而 RPC **没有这个命令**。

**更好的路（值得做，但要先实测）**：`ctx.reload()` **对扩展是可达的**——`rpc-mode.ts:341-343` 把 `reload` 挂进了扩展的 `commandContextActions`。而本 App **自带 3 个扩展**（设备桥、权限闸门、高亮服务）。所以在其中一个里注册一个命令去调 `ctx.reload()`，App 发 `prompt /<该命令>` 即可**当场重载**：
- **好处**：不重启 Node 进程、**不打断正在跑的回合**、省掉 1~3 秒；
- **必须先实测的三件事**：① `ctx.reload()` 在 RPC 模式下真的重扫 skills/prompts；② 重载后 `get_commands` 返回新条目；③ 重载不会破坏当前会话状态。
- **不实测就上 = 又造一个"点了没反应"的功能。** 这正是本项目反复出现的病：界面承诺了行为，行为不存在。
- 注意 `assets/pi-extensions/**` 有归属约束；`ASSET_VERSION` 必须同步提升（现在是 `"3"`），否则已安装设备拿不到新扩展。

### B6-定稿方案（用户已决策）：**任何安装 → 回合结束后自动重启**，不采用 `reload` 路线

用户明确否掉了"用扩展调 `ctx.reload()`"的复杂方案。**定稿：只要涉及安装动作，就重启引擎让它生效。**

**为什么重启必须由 App 做**：AI 跑在 pi 进程里，重启 = 杀掉它自己那个进程，**它做不到**。而 RPC **没有任何"我装了东西"的事件**。

**App 如何知道"该重启了"——三条互补，推荐 ①+③：**

| 办法 | 机制 | 评价 |
|---|---|---|
| **① 回合结束后比对资源指纹** | App 已收到 `agent_settled`；在回合前后各取一次指纹（`extensions/`、`skills/`、`prompts/`、`settings.json` 的 mtime+size），变了即视为"装了东西" | **最稳**：不依赖模型配合、不需要新协议；兜底 |
| **② 监听目录（FileObserver）** | agent 目录绑定修复之后（`extraBinds` 加了 `paths.agentDir`），**这些资源现在位于 App 自己的文件目录里**，Android 的 `FileObserver` 可直接监听 | 最快；但可能漏掉 rootfs 侧的偶发写入 |
| **③ 给模型一个"请求重启"的工具** | 在设备桥扩展里加如 `android_request_restart`，模型装完主动调 | 体验最好；但依赖模型记得调 |

**关键约束**：无论哪条路，**重启只能发生在回合结束之后**——即必须走 `ExtensionLifecycle` 的 `AwaitingIdle` 分支，**不许中途打断**。"装完立刻重启"的真实含义是"**装完的那个回合结束之后再重启**"。

### B6-体验细化（用户要求"弹提示问是否重启"，并问有无更优雅的方式）

**三层叠加，从体验到底线：**

**③ 让模型自己说（最优雅，成本最低）**
桥扩展**已经在 `before_agent_start` 注入环境信息**。加一句系统提示即可：
> "如果你安装或修改了扩展、skill、prompt 模板或设置，请在回复里明确说明'需要重启才生效'以及装了什么。"
→ 用户**在转录里当场看到**，不必等弹窗。兜底缺口：模型忘了说。

**① 在工具调用那一刻精确识别（不是事后猜）**
权限闸门已挂在 `tool_call` 上，把它扩展成**观察者**（不拦截）：识别安装类操作——`pi install …`、向 `extensions/`/`skills/`/`prompts/` 写入、改 `settings.json` 的 `packages`。
**比资源指纹强**：指纹只能说"有东西变了"（改主题也会触发，且**说不出装了什么**）；钩子知道**是什么、什么时候**，所以提示能写具体：
> 「模型安装了扩展 `foo`，重启后生效」

**② 回合结束后给一条"带动作"的提示（兜底，绝不漏）**
用现成的 `ExtensionUiHost` 弹一条**可操作**提示，而不是模态框：
> **已安装 X · 重启后生效 — [立即重启] [稍后]**
理由：模态框会挡住正在读的内容，而"是否重启"只有两个选项，内联按钮点一下即可。
**不变式**：重启仍只发生在回合结束之后（走 `AwaitingIdle`），**绝不中途打断**。

**附带好处**：有了 ①，日志/账本里能记"某回合装了 X"，出问题可回溯——目前**没人知道模型到底装了什么**。

**成本**：闸门扩展加一段匹配 + 桥扩展加一句提示注入 + UI 加一条可操作提示。**全在我们自己的扩展和 UI 里，不碰 pi。**

### B7. 项目信任弹窗 —— **待验证**
- **事实已核对**：`--mode rpc` 下 `hasUI` 恒为假（`main.ts:753` → `core/project-trust.ts:86-88` 直接 `return false`），配合默认 `defaultProjectTrust: "ask"`（`settings-manager.ts:1014-1017`），项目本地 `.pi/extensions` 被**静默跳过**：没有事件、没有 stderr、没有错误（`loader.ts:634-637`、`main.ts:775-782`）。
- **trust.json 的格式是读源码得到的，不是猜的**（`core/trust-manager.ts`）：
  - 路径 `<agentDir>/trust.json`（`:212-214`）；值是 `true | false | null` 的**对象**，其他值让整个 store 失效并且 pi **抛错**（`:111-121`）；`null` 是合法存量值，`setMany` 用 `null` 表示删除（`:236-237`）。
  - 序列化 = `JSON.stringify(sorted, null, 2) + "\n"`，键排序（`:125-135`）。键是 `canonicalizePath(resolvePath(cwd))`（`:40-42`），即 `realpathSync`，失败则原样返回（`utils/paths.ts:28-34`）。
  - 查找是**最近祖先优先**，且只有 `true`/`false` 算数，`null` 跳过继续向上（`:44-58`）。
  - 并发由 `<trust.json>.lock` 目录锁保护（proper-lockfile，10 次 × 20 ms，stale 10 s；`:137-176`）。
  - 询问选项是 pi 自己的五个（`:66-96`），连标签都照抄：`Trust`、`Trust parent folder (<parent>)`（先写父级 `true`、再删本级）、`Trust (this session only)`、`Do not trust`、`Do not trust (this session only)`；后两者（session-only）**不写任何文件**。
- **实现（已完成，未上真机）**：`TrustFile`（纯格式层，可离线校验）、`TrustRepository`（读写 + 双份落盘 + 锁 + `readlink -f` 规范化）、`ProjectTrust`（五选项 + 解析顺序，且**返回是哪一条分支决定的**，这样「项目资源被跳过」能变成一句用户看得懂的话）、`PiProjectTrustPrompt`（真正的弹窗）。
- **同一处路径错位（重要，跨目录，需要 engine 所有者改一行）**：`PiEngineHost` 只绑定了工作区（`:109-117`），**没有绑定 agent 目录**；于是 guest 里 pi 读写的是 `<files>/pi/runtime/rootfs/root/.pi/agent`，而 `PiPaths.agentDir`（`<files>/pi/.pi/agent`）**pi 根本看不见**。`docs/extension-compatibility.md:523-524` 写的「bind-mounted」与代码不符；`DeviceBridgeController.kt:182-188` 已经在用「两个位置都写」绕开它。
  - 影响：只写镜像 → 不生效；只写 rootfs → 下一次运行时更新被 `wipe()` 删掉。所以 `TrustRepository` 目前**两边都写**，并在 boot 后调 `publishIntoRootfs()` 从镜像回填。
  - **彻底修法（一行，属于 `engine/**`，本层不改）**：在 `PiEngineHost.boot` 的 `ProotCommand.build(...)` 里给 `extraBinds` 加 `paths.agentDir.absolutePath to guestAgentDir`。加上之后镜像就是权威，`publishIntoRootfs` 变成幂等空操作，`PiSessionViewModel` 的 `settingsStore`/`sessionStore` 也终于指向 pi 真正用的那份文件。
  - **—— 已完成（复核于 `182823e`，工作树）**：那一行已经加上——`engine/PiEngineHost.kt:285-295` 的 `extraBinds` 现在是 `workspace.absolutePath to guestWorkspace` **加上** `paths.agentDir.absolutePath to guestAgentDir`，注释里写明了它修的就是本段描述的错位。**本段上面两句"pi 根本看不见""需要 engine 所有者改一行"已过期**，保留仅为记录这段诊断。连带的收益：§B5 那条"安装物落在易失 rootfs 目录"的副作用、§K5 的 TrustRepository 三处修正、以及 `fd`/`rg` 的软链悬空（`PiPaths.agentBinDir`/`rootfsAgentBinDir` + `ensureToolsVisible()`）都随这次绑定一起成立。
- **`app.trust.*` 这两行设置项现在有意义了一半**：`app.trust.projects`（`PiSettingsRegistry.kt:1402`）的语义（「已保存信任决定的项目目录，对应 ~/.pi/agent/trust.json」）正是 `TrustRepository.read()`/`decide` 的模型，可以接到真数据上；`app.trust.extensions`（`:1413`，「扩展信任名单，第二道闸门」）**在 pi 里没有对应物**——pi 只有目录级信任，没有来源级扩展名单，所以这一行没有东西可写，建议删除或改成展示，而不是实现一个 pi 不认的第二套格式。**按约束没有改 `PiSettingsRegistry.kt`。**
- **收尾条件**：真机上「写入 trust.json → 重启 → 项目 `.pi/extensions` 真的被加载」，以及 `readlink -f` 在 proot 里返回的规范化路径与 pi 写的键一致。

### B5/B6/B7 共同的一条新发现：**skills / prompt 模板不会在 `get_commands` 时重新扫描** —— 需要重启
- `get_commands`（`rpc-mode.ts:682-713`）读的是三份**缓存**：`runner.getRegisteredCommands()`、`session.promptTemplates`、`session.resourceLoader.getSkills().skills`。
- `session.promptTemplates` 是 `this._resourceLoader.getPrompts().prompts` 的 getter（`agent-session.ts:1033-1035`）；`getPrompts()`/`getSkills()` 直接返回 loader 的字段（`resource-loader.ts:308-314`），字段在构造函数里初始化为 `[]`（`:285-288`）。
- 这两个字段**只**由 `updateSkillsFromPaths`（`resource-loader.ts:672-693`）与 `updatePromptsFromPaths`（`:695-717`）赋值，而它们只被 `reload()` 调用（`:472-473`、`:487-488`，方法体从 `:388` 开始，`:546` 置 `loaded = true`）。`reload()` 只在启动时跑一次（`core/sdk.ts:185-188`）和 `session.reload()` 时再跑（`agent-session.ts:2849`）。
- 也就是说：**扫描本身是活的**（`loadSkills` 每次都走文件系统，`:677-682`），**结果是缓存的**。往 `.pi/skills` 里丢一个新 `SKILL.md`，在重启之前 `get_commands` 看不到它。所以新增技能**同样需要重启**，和新增扩展一样。`ExtensionLifecycle.ANSWER_HINT` 会把这句话显示在每次重启提示旁边。

### 跨目录待接线（本层只提供，不改别人的文件）
- `ui/**` 所有者需要把 `PiPackagesScreen(state, …)` 接到一个 ViewModel 上：`PiPackagesUiState` 的 `entries` / `log` 来自 `PiPackageService.list()` / `install()` / `remove()`，`lifecycle` 来自 `ExtensionLifecycle.state`，`trust`/`trustInvalid` 来自 `TrustRepository.read()` + `ProjectTrust.resolve()`。
- 重启动作本身要由持引擎的一方提供：约定是 `isTurnRunning()` 与 `suspend fun restart(): String?`（null = 成功），再调用 `lifecycle.restartStarted()/restartSucceeded()/restartFailed()`。有回合在跑时必须走 `AwaitingIdle`，不能被调用方直接重启。

### B8. 扩展 UI 的其余接线 —— **已完成（复核于 dc00279）**
> **状态：全部已接线，包括全局承载点。** `ui/extension/ExtensionUiHost.kt` 现在**只**挂在 `ui/PiRoot.kt:166`（Scaffold 的 Box 内、目的页之上），页面里没有重复挂载——B8 自己写明的"必须成对操作"已经按正确方向完成，所以同一个请求不会弹两次窗。阻塞式对话框（`select`/`confirm`/`input`/`editor`）由 `PiSessionViewModel.onExtensionUi` 应答，即发即忘的 `notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text` 由 `onExtensionChrome` 承载，`extension_error` 有展示。逐字段核对见 `docs/extension-compatibility.md` §1.2 / §6.1。**下面原始正文保留作历史，不要据此重做。**
- 阻塞式对话框（`select`/`confirm`/`input`/`editor`）：`:rpc` 能解析、也有回包构造器，但 App 侧**一行处理都没有** → 引擎会永久阻塞。**这是当前最高优先级的缺陷。**
- 即发即忘（`notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text`）：同上，需要 UI 承载。
- `extension_error` 事件：解析了但没展示，等于静默失败。
- **全局承载点**：扩展弹窗目前只能挂在"对话/工作区"两个页面，用户停留在"会话/设置"页时弹窗排队不可见，而引擎正在等回复（`editor` 还**没有超时**，见下）。修法是在 `PiRoot.kt` 的 Scaffold 里挂一个全局 `ExtensionUiHost`。
  - **必须成对操作**：`PiRoot` 的 `Box` 里渲染的就是当前页面，所以**不能**在 PiRoot 和页面里同时挂——会变成两个 host，同一个请求弹两次窗、两个 Snackbar 抢同一个通知队列。正确顺序：先从 `ChatScreen.kt` / `WorkbenchScreen.kt` 摘掉，再加到 `PiRoot.kt`。
  - **状态**：等对话框子代理摘除页面里的挂载后，由主线加 PiRoot 那一行。

### B11. `PiEvent.ExtensionError` 丢掉了 `extensionPath` —— **已完成（复核于 dc00279）**
> **状态：字段已存在。** `rpc/src/main/kotlin/app/pi/rpc/Events.kt:285` `val extensionPath: String? = null,`，解析分支 `:468` `extensionPath = o.str("extensionPath"),`。落地于 `a7b7738`（`rpc/Events.kt` 的最后一次修改）。复核未改代码。
- **现状**：数据类只有 `message`，而 `EventUiRequest` 保留了 `raw`。扩展抛错时定位不到是哪个扩展。
- **收尾**：给数据类加字段 + 在 `extension_error` 解析分支里读——**一行改动**，但 `rpc/Events.kt` 当时被 RPC 命令面子代理占用，不能并发改，故排队。

---

### B9. markdown 渲染库升到 0.45.0 —— **已定论：AGP 8.13.2 可以，不用升 AGP 9**
- **答案**：AGP 8.13.2 **能**编译 `compileSdk = 37`，前提是走它的**小版本 SDK** 通道。所以 markdown 已从 0.41.0 升到 **0.45.0**（GFM alert、`alertTitle`、`darkTheme`、`StreamingMarkdownState` 一起回来了）。事先担心的「必须升 AGP 9.x」**是错的**：那是把「没有 `platforms;android-37`」误当成「AGP 8.13 不支持小版本平台」了。
- **根因与解法**：SDK 仓库里**没有**纯 `platforms;android-37`，只有 `37.0/37.1/37.2`。AGP 8.13.2 里 `compileSdk = 37` 单独写会哈希成 `android-37` → 找不到平台；必须再加 `compileSdkMinor = 0`，它才哈希成 `android-37.0`。**顺序不能反**：`CompileSdkDelegate.setCompileSdkMinor` 会先读当前 `compileSdk` 的 API level，为空就静默什么都不做。
- **证据**（全部在本机复现）：
  1. 仓库清单：`https://dl.google.com/android/repository/repository2-3.xml` 里只有
     `path="platforms;android-37.0"`、`37.1`、`37.2`（外加 `37.2-beta*`），**没有任何 `platforms;android-37`**。
  2. 平台可装：`sdkmanager --list` 能列出 `platforms;android-37.0`，安装成功；装完 `/opt/android-sdk/platforms/android-37.0/source.properties` 里是 `AndroidVersion.ApiLevel=37.0`、`ExtensionLevel=22`。
     ⚠ 这改变了一个全局副作用：`tools/typecheck.sh` 取 `platforms/android-*/android.jar | sort | tail -1`，现在选中的是 **android-37.0** 而不是 android-36（37 是 36 的超集，兼容）。
  3. DSL 存在：AGP 8.13.2 的公开接口 `com.android.build.api.dsl.CommonExtension` 有
     `getCompileSdkMinor()` / `setCompileSdkMinor(Integer)`（`gradle-api-8.13.2.jar`，`javap` 可见）。
  4. 用 AGP 8.13.2 **自己的类**直接调用（`java -cp <common/sdklib/gradle/gradle-api/gradle-common-api/kotlin-stdlib>` 跑一个探针）：
     ```
     sdklib AndroidVersion(36)                              -> android-36
     sdklib AndroidVersion(37, 0)                           -> android-37.0
     AGP CompileSdkVersionImpl(api=37, minor=0).toHash()    -> android-37.0
     AGP CompileSdkVersionImpl(api=37, minor=null).toHash() -> android-37   ← 这就是那个不存在的包
     ```
     `AndroidVersion.getPlatformHashString()` 只对 API 36 做「无 `.0` 后缀」的特例；37 走 `getApiStringWithExtension()`，小版本一定带 `.0`。
  5. AAR 元数据对得上：0.45.0 的 `META-INF/com/android/build/gradle/aar-metadata.properties` 是
     `minCompileSdk=37` + `minCompileMinorSdk=0`。AGP 8.13.2 的 `AarMetadataReader` 只有 8 个字段、**整包找不到 `minCompileMinorSdk` 字符串**（即它不检查小版本），而 `minCompileSdk` 的比较对 api>36 用的是**已装平台的 API level**（37），所以 37 ≤ 37 通过。
     → 副作用要记住：AGP 8.13.2 会**忽略** `minCompileMinorSdk`。将来若某个 AAR 要 `minCompileSdk=37` + `minCompileMinorSdk=1`，AGP 8.13.2 不会报错，只会在运行时缺 API。
  6. Kotlin 侧不是第二道坎：0.45.0 的类 `@Metadata mv=[2,2,0]`（与 0.41.0 相同），Kotlin 2.2.21 能读；`darkTheme` 参数名在 0.45.0 的 `MarkdownColorsKt` 里、0.41.0 里没有，`alertTitle` 在 0.45.0 的 `MarkdownTypographyKt` 里、0.41.0 里没有。
- **没跑成的验证（如实记）**：本机 Gradle **跑不起来**，且原因与本题无关：AGP 应用阶段就死在
  `java.nio.file.FileSystemException: /root/.gradle/android/FakeDependency.jar: Function not implemented`，
  随后 JVM 报 `pthread_create failed (ENOSYS)`（`unable to create native thread`）。加上 AAPT2 只有 x86_64，所以 `:app:checkDebugAarMetadata` 这条端到端验证在本机**不可能**通过；上面的 3–5 是能拿到的最强证据（AGP 自己的类 + 真实 AAR 元数据）。真机/CI 上应补跑一次 `:app:checkDebugAarMetadata`。
- **落地改动**：`gradle/libs.versions.toml`（`markdown = "0.45.0"`）、`app/build.gradle.kts`（`compileSdk = 37` + `compileSdkMinor = 0`）。`gradle.properties` **不需要**改。
- **仍需回补的两行**（该文件归 `ui/render` 负责人）：
  ```kotlin
  // piMarkdownColors() 的 markdownColor(...) 里：
  darkTheme = isSystemInDarkTheme(),          // + import androidx.compose.foundation.isSystemInDarkTheme
  // piMarkdownTypography() 的 markdownTypography(...) 里（放在 h6 之后）：
  alertTitle = heading.copy(fontSize = 16.sp, lineHeight = 24.sp),
  ```
  0.45.0 里这两个参数都有默认值，所以不补也能编译；补上才是这次升级的目的（GFM alert 的标题样式与暗色配色）。
- **本机类型检查的真实状态（完整两阶段 `tools/typecheck.sh`，2026-09-11 最终）**：
  - **`:app` 已通过，全树 0 条错误。** 命令与逐字输出：
    ```
    $ bash tools/typecheck.sh > /tmp/tc-verify2.log 2>&1; echo "EXIT=$?" >> /tmp/tc-verify2.log
    $ cat /tmp/tc-verify2.log
    typecheck: OK (:rpc + :app, cross-module boundary reproduced) — 0 error diagnostics, :rpc 0 / :app 0
    EXIT=0
    $ grep -cE '\.kt:[0-9]+:[0-9]+: error:' /tmp/tc-verify2.log
    0
    ```
    可信度检查（否则 OK 不算数）：跑前删掉了可能残留的 `build/typecheck/rpc.jar`，跑后它被重建为 **306,124 字节 / 143 个 class**（`:rpc` 真的编译了，不是空 jar），日志里没有 `CANNOT RUN`（JVM 真的起来了）。这一轮包含当时刚加入、从未编译过的 508 行新文件 `app/src/main/kotlin/app/pi/packages/PiPackagesHost.kt`。
  - 过程记录（供以后不要被吓到）：第一轮 107 条错误，根因是 `ChatScreen.kt:34` 两行 import 被拼成一行、`PiSessionViewModel.kt` 里 KDoc 的 `` `rpc/**` `` 让 Kotlin 块注释**嵌套**吞掉文件剩余部分；修完降到 6 条（`ChatScreen.kt` 缺 `import kotlinx.coroutines.launch`，两个 `let` 块连带报 `cannot infer type for 'R'`）；补上 import 后归零。**全程没有一条错误涉及 markdown 渲染 API、`compileSdk`、AGP 或 Shizuku**——0.41.0→0.45.0 与 36→37 没有引入任何编译错误。
  - **⚠ `tools/typecheck.sh` 曾有两种「假 OK」，都已修，但判读时要知道**：
    1. `set -uo pipefail` + `if echo "$APP_DIAG" | grep -qE "\.kt:...: error:"`：诊断超过管道缓冲（约 900+ 条）时 `grep -q` 提前退出、`echo` 收到 SIGPIPE，`pipefail` 让管道返回 141 → `if` 为假 → 打印 OK。实测出现过「946 条 error 后紧跟 `typecheck: OK` / `EXIT=0`」。修法：判定改用 here-string（`grep -qE ... <<<"$APP_DIAG"`，无管道即不受 pipefail 影响）。
    2. 脚本从不检查 `java` 的退出码，`:rpc` 的编译若被杀/启动失败（无诊断＝看不出错）会照常 `jar cf` 一个空目录，产出 330 字节、只有 `META-INF/MANIFEST.MF` 的 `rpc.jar`；`:app` 随即爆出几百条 `unresolved reference 'rpc'` 的**假错**（实测 946 条，其中 `rpc` 92 条、`PiResponses`/`PiCommands`/`SessionEntry` 等 rpc 类型名上百条）。**跑之前 `rm -f build/typecheck/rpc.jar`，跑完 `unzip -l` 确认有 class，OK 才算数。**
    加固后的判定行自带计数，不会再出现「光秃秃一个 OK」：`... — N error diagnostic(s)`。
  - ⚠ 判读陷阱：**`:app` 的 class 数永远是 0**。本机不加载 Compose 编译器插件，codegen 走不到最后，所以 `build/typecheck/out-*/app` 为空**不代表失败**——唯一判据是日志里的 `error:` 行（自己数，别只看末行）。脚本已改成每次调用独立 `out-$$`；`/tmp` 里 `fork: Function not implemented` / Kotlin daemon 的 `SocketException: Function not implemented`、`pthread_create ENOSYS` 都是这台机器被并发重编译压垮，不是代码问题。
  - **Shizuku 依赖已补齐**：`tools/fetch-typecheck-deps.sh` 新增一段，按 `gradle/libs.versions.toml` 的 `shizuku` 版本取 4 个 AAR（`api`/`aidl`/`shared`/`provider`，13.1.5 时四个都是 AAR，已实测 200），解出 `classes.jar` 放到 `build/typecheck/extra/aar/shizuku-<artifact>/`。不需要 Gradle 解析 POM：少一个就是一条 unresolved import，而不是解析错误。

### B10. `libprootloader.so` 不是 PIE —— 装机时可能被拒
- **背景**：`proot` 是 `ET_DYN`(PIE) 且引用 `/system/bin/linker64`，符合 Android 的 exec 要求；但 Termux 的 `loader` 是 **`ET_EXEC`（非 PIE）**，18,136 字节。它是被 proot **映射**（`PROOT_LOADER`）而不是被 exec 的，所以 PIE 规则本不适用于它 —— `tools/fetch-runtime.mjs` 里那个无条件 PIE 校验已经改成只对"会被 exec"的条目生效。
- **未验证**：Android 的安装器/原生库提取对 `lib/arm64-v8a/` 下**非 PIE 的 `lib*.so`** 是否有额外校验。如果真机装机或启动时报错，退路是：只有 `proot` 必须放在 `jniLibs`（它要被 exec，才需要那个伪装和可执行权限），loader 可以改放进 rootfs 资产里，`PROOT_LOADER` 指向 guest 路径即可 —— 它只需要可读。
- **收尾条件**：真机跑通 boot。

### B12. 扩展资产的安装闸门是手工版本号，会静默失效
- **现状**：`DeviceBridgeController.ASSET_VERSION` 是手工常量，资产按它比对 stamp 决定是否重新拷贝。**已运行过旧版本的设备会一直用旧扩展树**，新扩展（例如 `pi-highlight/`）永远装不进去。
- **危害**：症状不是报错，而是"某个功能就是不好使"——排查的人会去查端口、token、网络，而不是查安装器。高亮代理就是主动报出这一条才被发现的。
- **临时处置**：版本号已从 `"1"` 提到 `"2"`（本次新增 `pi-highlight/`），并在常量上写明"**任何对 `assets/pi-extensions/**` 的改动都必须提升它**"。
- **收尾**：改成**内容指纹**（哈希资产树的文件名+大小，写进 stamp），把这个人工步骤彻底消掉。改一个文件的事，但要在 `bridge/**` 的 owner 手上做。
- **已收尾（复核于本轮，commit `ba297c7`）**：`DeviceBridgeController.assetFingerprint` 对整棵 `assets/pi-extensions` 算 `sha256:<hex>`（按资产路径排序，路径与字节都进摘要），stamp 文件 `.pi-android-assets` 与它比对；`ASSET_VERSION` 退化成"资产读不出来时"的回退标记（那时行为与旧版一致，不会假装已是最新）。**手工提升版本号这个步骤已经不存在。** 代价实测可忽略：整棵树 9 个文件 / 129,363 字节（最大单文件 58 KB），即每次 `start()` 多读约 130 KB 再算一次 SHA-256。

## C. 待验证（代码已写，从未在真机运行）

> 这一节是整份文档里最重要的部分。上面所有 UI 都在**没有验证过地基**的前提下写的。
> **执行件**：`docs/device-verification.md` —— 把这一节拆成「操作 → 预期 → 判据 → 失败含义」的逐条清单（含 host↔guest 路径对照表、以及"可以先自动跑的"探针）。**那份清单里的条目同样一条都还没跑过。**

### C1. 运行时能否在真机上启动 —— **唯一的关键路径**
- proot + Ubuntu 24.04 + 官方 Node 24 + pi 在 Android 14 (SDK 34, aarch64) 上真正跑起来。
- 已知约束：Android 10+ 禁止对 `app_data_file` 执行 `execve()`，可执行文件必须是 `jniLibs` 里的 `lib*.so`（PIE + `/system/bin/linker64` + `PROOT_LOADER` 指向 `nativeLibraryDir`）。
- **现状**：两个 11MB 的 APK 能装、不崩 —— 但那是**不含运行时**的版本。含完整运行时的 APK（约 200MB）**从未构建出来跑过**。
- 收尾：出一次带运行时的 APK，在真机上跑通 `boot()`，这是解开其它一切验证的前提。

### C2. 自写 VT 终端模拟器
- 已覆盖：备用屏幕 `1049`、括号粘贴 `2004`、OSC（标题/超链接）、SGR、滚动区域 `DECSTBM`、插入/删除行、ED/EL、鼠标、宽字符与组合字符。
- **未验证**：真实 TUI 程序的转义序列覆盖率、中文输入法、`script(1)` 在打包 rootfs 里是否存在、真机输入延迟。
- 退路：WebView + xterm.js（App 已用 WebView 承载 Mermaid）。

### C3. 设备桥的真机行为
- 无障碍截屏（API 34 安全窗口限制）、后台启动 Activity 分享/打开、跨安装读取 MediaStore 导出文件、失焦时读剪贴板。
- shell 后端只有 app uid（未接 Shizuku / ADB 无线调试）。

#### C3.1 「设备能力」界面的静态一致性审计（`applied (committed 2bd97cd)`；未上 CI、未过真机）

把界面上每一处**声称**（这组要什么、现在能不能用、为什么不能用）与 `DeviceCapabilityStore`（唯一权威）、
`DeviceShellGuard`、`/app/health` 的**真实**状态逐条核对。改动只有一处：
`app/src/main/kotlin/app/pi/ui/device/DeviceCapabilityScreen.kt`。**真机行为仍然未验证**——这一节修的是"界面说的和代码做的不一致"，
不是 C3 本身。

**本轮逐条重核（工作树，六个标记逐个 grep 到）**：六处**都在**，且已随 `2bd97cd fix(ui): six places the device screen disagreed with the device` 提交（该文件现在与 HEAD 无差异）。下表状态列随之更新。

**pi 侧判据（源码 `bbb61e34`）**：**这一页整页是 App 自己的东西，pi 没有对应物**——pi 0.85.1 的 `core/settings-manager.ts` 的 `Settings` 接口里没有任何 Android 侧的键（无障碍服务、Shizuku、SAF、运行时权限、截屏能力都不存在，它也不知道自己在手机上跑）。所以这六处的判据只能是"**不撒谎 + 用户能用**"，**不能**去 pi 里找依据，也不能把我们的选择说成 pi 的行为。（六处里唯一与 pi 沾边的是"截屏"这个动作本身，但 pi 作为 CLI 根本没有截屏能力，它的 `images` 设置只关乎发给模型的图片。）

**收尾（同一文件，`applied (uncommitted)`）**：按用户随后下的界面字符串规矩，把上一轮新增文案里的内部词汇去掉——`setTorchMode`/`CAMERA`/`POST_NOTIFICATIONS`/API 号/`screencap`/包名都不再出现；权限请求被拒时返回的**平台常量**现在经 `permissionLabel`（`DeviceCapabilityScreen.kt`）显示为「相机 / 位置信息 / 通知」。保留不变的三类：后果（哪些还不可用、哪些不受影响）、前置条件（系统开关、Android 版本）、可行动的下一步（去哪个设置页、点哪个按钮）。本轮没有为观感增加任何动画/模糊/光晕。

| # | 界面声称（改前） | 真实行为 | 依据 | 状态 |
|---|---|---|---|---|
| 1 | Shell 卡「当前 Shell 后端」直接显示 `shizuku.backendLabel`，Shizuku 未就绪时字面为「Shizuku（未安装/未运行/未授权）」 | 此时执行命令的是 `AppUidShellBackend`（应用自身 uid）；`DeviceShellGuard.active()` 取第一个 available | `DeviceShizuku.kt:284-291`、`DeviceShell.kt:188-190`、`/app/health.shellBackends`；现读 `DeviceShellGuard.active().label`（`DeviceCapabilityScreen.kt:608`） | applied (committed 2bd97cd) |
| 2 | 无障碍卡「前往系统设置」 | `runCatching { startActivity(...) }` 丢弃结果：没有该 Activity 时点按无任何反应、无提示 | 改前 `DeviceCapabilityScreen.kt:217-219`；现在读 `.isSuccess` 并在失败时写提示（`:263`） | applied (committed 2bd97cd) |
| 3 | 无障碍组列「截屏并把图片交给模型查看」且徽章「可用」 | `screenshot` 在 API<30 直接 UNSUPPORTED（minSdk 26，Android 8/9 必现）；`/app/health.screenshotSupported = SDK>=R` | `DeviceUiAutomation.kt:609-616`、`DeviceBridgeRouter.kt:326`；现在 SDK<R 时显示一行红字（`DeviceCapabilityScreen.kt:548`） | applied (committed 2bd97cd) |
| 4 | 位置·传感器·相机卡只有一句静态文案，而 `cameraPrecondition()` 的 hint 叫用户"点「授予相机权限」" | 那个按钮**不存在**；全树没有任何代码请求 CAMERA / ACCESS_*_LOCATION；模型会原样转述，用户找不到入口 | `DeviceCapabilityStore.kt:268-278`（hint 在 `:275-276`）；现在两张权限按真实授予态显示并各有一个请求按钮（`DeviceCapabilityScreen.kt:704` 等） | applied (committed 2bd97cd) |
| 5 | 基础组默认开、徽章「可用」 | API 33+ 缺 `POST_NOTIFICATIONS` 时 `android_notify` 每次必拒，而全树无人请求该权限，卡上无一字 | `DeviceSystemActions.kt:103-112`、`DeviceCapabilityStore.kt:239-245`；现在显示通知权限态并可请求 | applied (committed 2bd97cd) |
| 6 | ApprovalsCard 显示扩展上报的审批状态 | 该 item 不读任何轮询状态，LazyColumn item 只组合一次 → 屏幕开着时**永不刷新** | 改前 `DeviceCapabilityScreen.kt:679-697`；现在由轮询循环喂 `approvals`（`:193`）并 `ApprovalsCard(lines = approvals)`（`:316`） | applied (committed 2bd97cd) |

修完的形态：后端标签改读 `DeviceShellGuard.active().label`（Shizuku 不可用的原因仍由 `DeviceShizuku.status` 的 `note` 单独说明）；
无障碍设置页打不开时写入可见提示；API<30 显示"截屏需要 Android 11+"；相机/定位/通知三条**按真实授予状态**显示并提供请求按钮
（请求后回读 store，不假设弹窗被接受）；ApprovalsCard 由轮询循环喂数据。

**核过、一致（未改）**：设备桥卡「仅监听 127.0.0.1」（`DeviceBridgeHttp.kt:131`）、「已监听/未运行」（`DeviceBridgeController.isRunning()/lastReport`）、
ShellPolicyCard 全部文案直读 `DeviceShellGuard`（`DeviceShell.kt:590-614`）、无障碍「服务未运行」与 store 同源（`DeviceCapabilityStore.kt:156-166`）、
存储卡的旧式权限提示（`DeviceCapabilityStore.kt:223-236`）、顶部「被关闭的能力不会静默失效」（`DeviceCapabilityStore.kt:111-148` + `DeviceBridgeRouter.withCapability`）、
`DeviceCapabilityEntryRow` 的「N/M 组能力可用」（返回设置页会重组重算）。

**记账、不修（不在本次范围）**：`ShellPolicyCard` 的危险操作枚举原先漏了 `android_keyevent`（`assets/pi-extensions/pi-android-bridge/danger.ts:47-79`）——文案里已补，
但那份清单仍在 UI 里硬编码、真相在 TS 扩展里，属"两份真相"残余；`DeviceShizuku.addPermissionResultListener` 每次进屏注册且不移除（listener 泄漏，`bridge/**`）；
设备桥审计尾行只在刷新/toggle 时更新（有可见刷新按钮，可接受）。

### C4. 代码高亮服务
- 方案：扩展在引擎进程内起回环 HTTP 服务，用**原版 highlight.js 10.7.3**（`--mode rpc` 下 pi 自己并不加载它）。
- **未验证**：真机上 Node 侧加载耗时与内存增量、回环往返延迟、引擎未启动时的降级表现。

---

## D. 已定妥协（设计取舍，不是缺陷）

### D1. 引用块的竖条颜色 = 引用文字颜色
渲染器的 block quote 用**同一个颜色**画竖条和文字，pi 是两个 token（`mdQuoteBorder` / `mdQuote`）。pi 内置的 dark/light 两套主题里这两个值**恰好相同**，所以出厂主题下输出一致；只有用户自写主题把两者设成不同值时才会丢失这个区分。

### D2. `mdLinkUrl` 在手机上无处可显示
终端里 pi 会把 URL 用括号打印在链接文字后面；手机上是原生链接，不打印 URL 文本。该 token 仍可从调色板取到（供链接详情面板使用）。

### D3. TUI-only 扩展需要走终端标签页
`ctx.ui.custom()` 以及 `setFooter`/`setHeader`/`setWorkingMessage`/`setWorkingIndicator`/`setEditorComponent`/`setToolsExpanded`/`getTheme` 在 **pi 自己的 RPC 模式**下就是 no-op（`docs/rpc.md` 明确列出）。任何 RPC 客户端都拿不到。出路是工作区终端的 **`pi TUI（原版）`** 标签页 —— 在那里跑的是未修改的原版 pi。按 pi 自带 69 个示例扩展统计：48 个（70%）走 RPC 就能完整工作，9 个（13%）需要对话框接线，12 个（17%）需要真终端。

### D4. 表格与其他元素改用手机版排版
终端只有一种字和一种字号，pi 的标题只靠粗体/下划线区分。App 保留**颜色**一致，另外加了字号层级 —— 手机窄栏里同字号的标题不可读。pi 自己的 HTML 导出为了同样的原因做了同样的取舍。

---

## E. 未指派（无主）—— 下一批必须明确归属

> 这一节的每一条都**还没有负责人**。之前它们只活在对话里，这就是"审计做得比路由好"的后果。

### E1. 工具名冲突（pi 的规则是"先注册的赢"）—— **已定：不做（2026-09-12，用户决定）**

**结论：不做工具名冲突检测。** 用户原话：「自带扩展，这个不用管了，自带那 3 个扩展也没什么卵用，不用管它。」

**仍然成立的事实，别读成"问题不存在"**：pi 的规则是**先注册的赢**，同名工具**静默让一方失效**、不报错；命令名的冲突 GUI 已处理（`name:1` 后缀），工具名仍然零处理。所以这是一处**已知且被接受的静默失败面**，不是已解决项。将来若有人被它咬到（症状：装了扩展、工具却不生效、也没有任何报错），回到本节。

**一条事实留给将来的人，免得把载体当垃圾删掉**：那三个自带扩展里——
- `pi-android-bridge` 是**设备能力的载体**（读屏 / 点按 / 截屏 / 剪贴板 / 通知那条链的 guest 侧），
- `pi-android-permission-gate` 是**权限闸门**，
- `pi-highlight` 是高亮服务。
用户的意思是"**不为它们做冲突检测**"，**不是**"它们可以删"：删掉 `pi-android-bridge`，设备能力在 pi 眼里就没了（Kotlin 侧的服务仍在跑，症状会很难查）。§E7 记过同一点。

原始记载（保留以便重估）：
自带扩展（`pi-android-bridge`、`pi-android-permission-gate`、`pi-highlight`）与用户安装的扩展**混在同一个 `~/.pi/agent/extensions/` 里**。同名工具会**静默地让一方失效**，不报错。
命令名的冲突已由 GUI 侧处理（`name:1` 后缀保留，见 `PiSlashCommands.kt`），**工具名的没有任何处理**。
收尾：扩展/包管理界面里检测并显示冲突；或在加载后比对工具表并给出警告。

### E9-接线规格（由包管理代理交付，界面未写——**这就是"钥匙"，别让它只活在聊天里**）

核心层已在 `app/src/main/kotlin/app/pi/packages/` 就绪，界面只需要接线，**不需要再写逻辑**：

| 界面元素 | 调用 | 返回 |
|---|---|---|
| 打开时预填 | `PiCredentialService.prefill(presetId)` | `Existing(providerId, modelId, configuredProviderIds, configuredModelIds, maskedKey, baseUrl, api, modelsFileError)` |
| 选厂商 | `PiProviderPresets.all`（10 家 pi 内置 + 3 条 App 侧，`baseUrl`/`api` 已从 pi 的 `providers/*.ts` 抄好） | — |
| **「检测并扫描模型」（唯一一个按钮）** | `suspend PiCredentialService.probe(preset, key, baseUrl)` | `Ok(models, endpoint, note)` / `Failed(kind, message, suggestion, endpoint)`——**`Failed.allowManual` 恒为 true** |
| 勾选 + 保存 | `PiCredentialService.save(preset, key, baseUrl, api, choices, defaultModelId)` | `SaveResult(ok, steps, restart)`；`restart` 直接喂 `ExtensionLifecycle.installSucceeded(...)` |
| **`PiRoot.kt`** | **已接（applied, uncommitted）**：`PiRoot` 传 `onRunAction`。**§I2 那一轮之后，Action 行没有一行再落到"还没有接入实现"的提示上**；**注册表现在有 8 行**（`app.credentials.apiKey` / `.oauth`、`app.localModels.manage`、`app.compaction.runNow`、`app.sessions.import`、`app.security.emergencyStop`、`app.runtime.restartEngine`、`app.about.changelog`）：`app.compaction.runNow` → RPC `compact`（`rpc-types.ts:44`）；`app.security.emergencyStop` → `stop()` + `abortBash()`；`app.runtime.restartEngine` → 确认对话框后重启引擎；`app.credentials.oauth` / `app.sessions.import` / `app.about.changelog` 指向 TUI（`rpc-types.ts:20-74` 里没有 login/import/changelog）。凭证两行由 `PiSettingsStack` 的 `hostActions` 直接开屏，不走确认对话框（同一张表还接了只读的 `packages` 行 → 包管理页）。逐行结论见 **§I2** | — |

界面还需自己补两块：
1. 扫到的 id **匹配 pi 内置目录取元数据**（数据源是 `PiCommands.getAvailableModels`——pi 的模型清单在**进程里**，不是文件）；匹配不上的用默认值并**标注"默认值，可改"**；
2. 把两句事实写进界面文案：**`models.json` 完全没有锁**、**"扫描模型"不是 pi 的能力而是 App 侧知识**（常量已在 `PiModelScanner`/`PiProviderPresets` 注释里）。

**状态（applied, uncommitted，未上 CI）**：界面已写，`app/src/main/kotlin/app/pi/ui/settings/PiCredentialScreen.kt`（四段：选厂商 / 粘 Key / 检测并扫描 / 勾选并保存；保存成功后走 `ExtensionLifecycle.installSucceeded` + `EngineRestartCoordinator` 确认式重启）。两条 UI 补充都做了：元数据取自 `ui/PiRoot.kt:200` 传入的 `get_available_models` 快照，匹配不上的标"默认值，可改"；`models.json` 无锁与"扫描是 App 侧知识"两句写在界面说明里（依据 `core/model-runtime.ts:180`、`packages/ai/src/models.ts:763`/`:831`）。
**未做完的一处 —— 已修（applied, uncommitted，未上 CI）**：`PiCredentialService.preferences()` 曾把 `settings.json` 只写到 `agentTruthDir`（rootfs 侧），而 `PiEngineHost.kt:285-294` 现在把 `paths.agentDir` 绑到了 guest 的 `/root/.pi/agent` —— 保存的第三步写的是一个 pi 不读、App 也不读的文件（`auth.json`/`models.json` 因为 `PiAuthStorage`/`PiModelsFile` 写两份而不受影响）。现改为 `packages/PiCredentialService.kt:69` 的 `agentDir = mirrorAgentDir`（即绑定源，也就是 `ui/PiSessionViewModel.kt:325-328` 那个 settings store 读的同一份）。同一前提还牵出 §K5 里 TrustRepository 的三处修正。

**之后**：**I2 已处置（已完成于 `6c2059a`；此后 Action 行又从 7 行变成 8 行 —— 新增了 `app.runtime.restartEngine`；逐行结论见 §I2）** → **I9 已完成**（删会话 `af2d675`、`-c` 续接 `7885599`）→ **§I11 也已完成**（离线 / `--system-prompt` / `PI_CACHE_RETENTION`：`app.runtime.offline`/`systemPrompt`/`cacheRetention` 三行 + `PiSessionViewModel.launchOptions()` + `boot(launch = …)`；见 §I11）。**§I 已全部收尾，没有"只剩"的那一条了。**

### E9. 模型/凭证的"导入"在 GUI 里**完全是空壳**（点下去没有任何反应）
- **症状**：设置 → 模型 → 「API Key」「OAuth 登录」「本地模型（llama.cpp）」三行**看得见、能点，但点了什么都不发生**。
- **原因（两处都缺）**：
  1. **（此因已消失，保留仅为记录）** 这三行是 `PiRowKind.Action`（`PiSettingsRegistry`），动作要通过 `PiSettingsStack(onRunAction = ...)` 派发——当时 **`PiRoot.kt` 没有传 `onRunAction`**，默认 `null`，所以动作永远不会执行；**现在已接**（见 §E9-接线规格与本段末条）；
  2. 即使传了，**也没有任何东西实现那个动作**：`grep -rn "app.credentials\|app.localModels"` 在 `PiSettingsRegistry.kt` 之外**零命中**——注册表只是画了行，没有消费者。
- **真正该对接的东西**：pi 把凭证写在 `~/.pi/agent/auth.json`（0600），**不属于 `settings.json`**（见该行自己的描述）。所以正确做法是 App 侧写这个文件、或调用 pi 的登录流程，**不是往 settings.json 里塞键**。
- **当前实际能做的**：只有**切换** pi 已经配好的模型（`get_available_models` → `set_model`/`cycle_model`），且需要引擎在跑、provider 已在 pi 侧配好。
- **连带**：`app.trust.*` 两行（`PiSettingsRegistry.kt:1400-1422`）是**同一形态的空壳**——`runtime/**` 的信任存储未做（见 G 节）。
- **收尾（用户已定方向，不再讨论方案）**：
  1. **不用扩展、不另造格式**——直接读写 pi 的官方文件：凭证 `auth.json`（0600）、设置 `settings.json`；字段以 **pi 源码**为准（不是文档）；
  2. **必须尊重 pi 的锁**（`proper-lockfile`，见 `settings-manager.ts:243`、`auth-storage.ts:76`），否则会和 pi 的运行期写入互相覆盖，症状是"设置自己变回去"，极难定位；
  3. 第一步先读 `docs/custom-provider.md` 确认 **provider/model 定义的确切落点**（`settings.json` 的普通键？独立文件？）——**未确认前不许动手写代码**；
  4. 界面要求**人性化**：选厂商 → 填 Key → 填模型名 → **「测试连接」** → 保存；**打开时读取并预填已有配置**，让"新增"和"编辑"共用一个界面。**"测试连接"是必须的**——要让人在保存前就知道 Key 对不对，而不是配完、重启、发消息才发现 401；
  5. 写完后走 `PiEngineHost.restart()` 生效；
  6. ~~`PiRoot.kt` 把 `onRunAction` 接通~~ **已完成**（见本段末条）；
  7. 顺带**审计所有 `PiRowKind.Action` 行**，凡是"画了但没有消费者"的，要么实现、要么从界面撤掉——**一个点了没反应的行，比没有这一行更坏**。

#### E9 已核实的结论（读源码得到，不是猜的）

- **落点确认：`providers` 不在 `settings.json` 里。** `Settings` 接口（`core/settings-manager.ts:106-158`）只有 `defaultProvider`、`defaultModel`、`modelThinkingLevels`、`enabledModels`——**没有 `providers`，也没有任何凭证键**。所以：
  - **厂商与模型** → `<agentDir>/models.json`，顶层 `{ "providers": { "<id>": {…} } }`（`docs/models.md:3`；加载器 `core/model-config.ts:251-290`，路径来自 `core/model-runtime.ts:174-175`）。
  - **凭证** → `<agentDir>/auth.json`（`core/auth-storage.ts:52`），`Record<providerId, Credential>`。
  - `settings.json` 只存**选择**：`defaultProvider` / `defaultModel` / `enabledModels`。
- **字段以源码为准，且与 `docs/custom-provider.md` 有一处不同**：`models.json` 里 provider 的 `oauth` 是字面量 `"radius"`（`model-config.ts:204`），**不是**文档里那个 OAuth 对象；模型条目**只有 `id` 是必填**（`model-config.ts:162-176`），文档里的 `name/reasoning/input/cost/contextWindow/maxTokens` 在 `models.json` 里全是可选。写文档那套形状会过不了 `validateModelsConfig`，结果是 pi 报 `Invalid models.json schema` 并**整份文件不生效**。
- **锁只有两把，`models.json` 没有锁。** `auth.json`：`lockfile.lockSync(authPath, {realpath:false})`，10 次 × 20 ms（`auth-storage.ts:76-100`），写入 `mode: 0o600` 且注释明说**只在创建时生效**（`:24-25`）。`settings.json`：同样的锁（`settings-manager.ts:236-256`）。**`models.json` 完全不加锁**：`ModelConfig.load` 就是一次 `readFile`（`model-config.ts:251-290`），而 pi 从不写这个文件（动态模型缓存写的是 `models-store.json`，`model-runtime.ts:180`）。给它加一把别的写者不认的锁没有意义。
- **`models.json` 允许注释**：加载前过 `stripJsonComments(stripBom(...))`（`model-config.ts:267`），所以 App 的读取端也必须容忍注释，否则会把用户手写的带注释文件判成损坏。
- **覆盖语义**：`models` 一旦提供就**替换该 provider 的全部模型**（`docs/custom-provider.md:684`），而 provider 块本身是**覆盖**在内置同名 provider 之上（`:33`）。所以给 `openai` 写 `models` 不是"追加"，是"替换内置清单"。
- **重启是必须的，理由可以精确到行**：`models.json` 只被 `ModelRuntime.refresh()` 读取（`model-runtime.ts:699`，另加启动时的 `create`，`:176`）。RPC 协议里**没有任何命令触发 refresh**——`get_available_models` 读的是缓存快照 `getAvailableSnapshot()`（`:422-424`）。`docs/models.md:80` 说「打开 `/model` 时重载、改完不用重启」，那是 **TUI**（`modes/interactive/model-catalog-refresh.ts:22` 会触发 refresh），**在 `--mode rpc` 下不成立**。`auth.json` 不同：它的读取带 revision 检查（`auth-storage.ts:39` + `getFileRevision`），所以外部新增的 Key 会被察觉；需要重启的是**厂商表**。
- **"扫描模型"这件事 pi 里没有**：`createProvider` 有一个可选的 `fetchModels` 钩子（`packages/ai/src/models.ts:760-763`，调用点 `:831`），但**全仓库没有任何 provider 实现它**（`grep -rn fetchModels packages/ --include=*.ts` 只命中 `models.ts` 和两个测试文件）。pi 自己的清单是 `packages/ai/src/providers/` 下的静态表 + 从 pi.dev 刷新。所以扫描用的端点形状（`GET {base}/models` 等）**是 App 侧知识，不是 pi 行为**，UI 里必须这么标注；Anthropic 的 `x-api-key` + `anthropic-version` 同样不在 pi 里——它在 `api/anthropic-messages.ts:298-302` 只*校验*这些头存在，实际由第三方 SDK 发送。
- **已实现（未上真机）**：`app/src/main/kotlin/app/pi/packages/` 下的 `PiConfigFiles`（`auth.json` / `models.json` / `settings.json` 的锁与原子写、0600、注释容忍）、`PiProviderPresets`（10 家 pi 内置厂商的 `baseUrl`/`api` 直接抄自 `providers/*.ts`）、`PiModelScanner`（按厂商分派的清单请求 + 失败分类，**失败永远 `allowManual=true`**）、`PiCredentialService`（预填 → 探测 → 保存三步，每步单独报结果）。
- **~~仍未做~~ 已完成（2026-09-11，提交 `dae0ff5`，CI 绿）**：`PiRoot.kt` 的 `onRunAction` 已接；「选厂商 → 粘 Key → 扫描 → 勾选 → 保存」的 Compose 界面已写（`ui/settings/PiCredentialScreen.kt`，586 行）；装包入口也已挂进设置（`PiPackagesHost`，之前**没有任何入口**）。**这一条原来是"仍未做"，现在不成立了——留着这行是提醒：过期的"未做"记载比漏记更糟，它会让下一个人重做已经做完的东西。**
  仍未闭环的只剩：`app.localModels.manage` 只做了一半（端点在、GGUF 加载/下载只能在 TUI）。**原记载的另外两项已不成立**：OAuth 现在是一个明确的"仅终端"跳转（`/login`，`PiRoot.kt:49-70`），`app.credentials.apiKey` 有真实表单；"16 个 Action 行只有提示没有实现"也已处置（**§I2**，19 行逐行有结论，未提交）。

**§E9 的收尾（2026-09-12，此前只在报告里）**：凭证页上原有一个**静默的假成功** —— `PiCredentialService.prefill()` 把 `auth.json` 的 `Read.Invalid` **静默丢成空 map**，于是破损的凭证文件会让界面显示"没有配置任何厂商"，而真相是 **pi 读它会直接抛错、启动不了**（`core/auth-storage.ts:216-227`）。修法两步：`Existing.authFileError` 由 `prefill()` 填上（`packages/PiCredentialService.kt`），再由 `ui/settings/PiCredentialScreen.kt` 消费并显示后果（「凭证当前无法被读取，这个页面显示的厂商不是全部。修好之前 pi 不会启动。」），**口径与旁边那条 `modelsFileError` 对齐**（后者另有一处源头泄漏：`PiConfigFiles` 的 `Snapshot.error` 会带上绝对路径，已记在这条旁边）。这是本仓"静默失败"家族的又一例：**能取到的失败原因必须说出来**。

### E2. 高亮服务的 `attach(context)` 到底有没有被调用 —— **已完成（复核于 dc00279）**
> **状态：调用链已确认，高亮不会静默退回单色。** `ui/render/PiMarkdown.kt:77` `remember(context) { PiNodeCodeHighlighter.attach(context) }`——每次渲染 markdown 时按 `context` 记住并调用一次，`attach` 自身幂等（`highlight/PiNodeCodeHighlighter.kt:105`）。落地于 `a7b7738`。原疑问"没人验证过"已经解决；剩下的是真机上的回环延迟，那属于 C4。
>
> **方向也要说清（2026-09-11 复核）**：监听 HTTP 的**不是 app**，而是 guest 里的 Node 扩展（`assets/pi-extensions/pi-highlight/service.ts:353` 绑 `127.0.0.1:3176`）。Kotlin 侧（`highlight/PiHighlightClient.kt:86`/`:176`）是**客户端**，端口和 token 从扩展写下的 `highlight-bridge.json` 里读。降级链路已逐层确认存在（客户端并发上限/超时/大小阈值、HTTP 层 100ms/150ms 超时与 401 重读、UI 层默认空高亮器、扩展侧 `catch(() => null)`），**不存在"高亮挂了就白屏或崩溃"的路径**。

### E3. 附件输入（图片）
协议**已支持**：`docs/rpc.md` 的 `prompt` 命令带可选 `images` 字段，格式 `ImageContent`；会话条目里的 `Attachment` 还带 `fileName/mimeType/size/extractedText/preview`。
App 侧**没有任何 UI**：没有选图、没有粘贴、没有拖入。**只能发纯文字。**
收尾：输入框加附件入口 + 预览 + 随 `prompt` 发送。

**状态（applied, uncommitted，未上 CI，未过真机）**：入口、预览、随消息发送都已接上。
- 协议的**真实形状**：`prompt` / `steer` / `follow_up` 的 `images` 是 `ImageContent`
  ——`{"type":"image","data":"<base64>","mimeType":"image/jpeg"}`（`packages/ai/src/types.ts:367-371`，
  `docs/rpc.md:51-53`、`:78`、`:88-93`、`:110-115`）。**没有路径字段、没有尺寸字段**，字节整段随 RPC 消息走；
  我们的类型是 `app.pi.rpc.PiImage(base64, mimeType)`（`Commands.kt:13`），由 `putImages` 编码。会话条目里的
  `Attachment(fileName/mimeType/size/...)` 是**落盘后**的记录，不是发送时的形状，两者别混。
- 取图方式选 **SAF 文档选择器**（`ActivityResultContracts.GetContent`，符号在 `ChatScreen` 的
  `imagePicker`）而不是 Android 13 的 Photo Picker：前者不需要任何存储/媒体权限（系统只授予用户刚选中的
  那个 URI 的读权限，与 App 已有的目录授权模型 `DeviceSafStore` 同一套），且在所有受支持 API 上无需 backport 依赖，
  同时覆盖文件提供者与相册。
- **不需要 guest 能读到文件**：既然字节内联在 RPC 里，就与 `/sdcard`、App 私有目录、guest 路径映射都无关。
  `bridge/GuestImageBytes.kt` 解决的是相反方向（guest 路径 → App 显示 markdown 图片），没有为附件另造通道。
- 失败逐条有原因：非 `image/*`、打不开/被拒/文件已删、超过 `MAX_ATTACHMENT_BYTES`（8 MB，内联 base64 的
  膨胀代价）三类各自给一句用户能读的话与下一步；取消选择不算失败，不弹提示。
- 预览：`AttachmentThumb` 用与转录同一个 `decodePiImage` 在 IO 线程解码出缩略图，点击移除；编解码器拒绝的
  载荷仍显示 MIME 类型，不静默消失。

### E4. `@` 文件提及 —— **applied (uncommitted)**（未上 CI，未过真机）

> 原记载是「pi 有（见 `docs/settings.md`、`docs/usage.md`），App 没有」。**那一行已经不成立**，
> 但下面的"仍然没有的"三件事必须一起读——`applied` 不等于 `done`。

**pi 的行为（读 `/root/pi-src`，`bbb61e34`，逐条带行号）**：

- **触发只在 token 边界**：`@` 必须是行首或紧跟 `空格 / tab / 双引号 / 单引号 / =`（`packages/tui/src/autocomplete.ts:7` `PATH_DELIMITERS`，判定在 `:474-479`）；`@` 前缀在 `/` 命令之前判断（`:298-311`）。TUI 编辑器默认触发字符集是 `["@", "#"]`（`packages/tui/src/components/editor.ts:251`），`@` 有 20 ms debounce（`:251`、`:261-265`）。
- **候选来自 cwd 下递归跑 `fd`**（`:124-222`）：`--type f --type d --follow --hidden`、排除 `.git` 及其子项、最多 100 条再取 20 条（`:750`、`:785`）。**fd 默认尊重 `.gitignore`（没加 `--no-ignore`）**，被忽略的路径不进候选；`--hidden` 让 `.pi/`、`.github/` 进，`.git` 出（`autocomplete.test.ts:315-335`）。**fd 不存在时候选为空、不弹窗**（`:741`）。
- **选中插入的就是字面 `@` + 相对路径**（`:107-121`）：文件后补一个空格，目录不补且保留结尾 `/`（`:413-429`），含空格自动加引号 `@"…"`（`:111`）。
- **pi 客户端不把 `@path` 展开成文件内容**：提交只做 `trim()`（`modes/interactive/interactive-mode.ts:2964-2967`），模型看到的是字面路径；它能被解析，是**工具侧** normalize 时剥掉开头的 `@`（`utils/paths.ts:80`，调用点 `core/tools/path-utils.ts:41`、`:49`）。
- **找不到文件时没有提示也没有校验**，用户照样能把它发出去。
- 与 CLI 的 `@file` 位置参数（`cli/args.ts:225-226`、`cli/file-processor.ts:25`：启动时把文件内容读进 prompt）是**两套机制**，composer 的提及不走它。混淆这两者会写出"顺手展开内容"的错误实现。

**App 的实现（本次，未提交）**：

- `ui/chat/PiFileMentions.kt` —— 纯逻辑：触发、`@` 前缀解析（含引号形式）、作用域拆分（`@a/b`、绝对路径、`~/`、`..`）、fd 的 argv 与其 shell 引用、`scoreEntry` 打分与完整排序、截 20、插入规则。每一条都对着上面引用的行转写；Android-free，所以能被 bare-JVM 自检直接编译。
- `ui/chat/PiMentionSource.kt` —— **候选集不自己遍历工作区**，而是用 guest 里 pi 自己那个 `fd`（`runtime.lock.json` 的 `artifacts.fd`；`RuntimeProvisioner.kt:106`、`:464-469` 装到 `/usr/local/bin/fd`），经 app 侧已有的 guest 命令通道 `app.pi.packages.GuestCommand`（`GuestCommand.kt:99`，与引擎同一套 proot argv 与 bind）跑。**判据**：ignore 语义（分层 `.gitignore`、否定规则、`.ignore`/`.fdignore`、全局 ignore）是 `ignore` crate 的行为，Kotlin 复刻只能近似；同一个二进制 + 同一组 argv 才是逐字节一致。**不用 RPC 的 `bash`** 的理由是排除、不是遗漏：它的输出会进模型上下文（`PiEngineApi.kt:245-249`）、`abort_bash` 只针对"当前那条"命令、并且要求引擎已经启动（与 E5 的懒启动冲突）。
- `ui/chat/MentionPalette.kt` + `ui/screens/ChatScreen.kt` + `ui/PiSessionViewModel.kt` —— 列表、debounce、抢答丢弃、点击插入；只有 `state.mentions.query == 当前前缀 && items 非空` 才画（空列表不画，对齐 `autocomplete.ts:305`）。**路由没改**：`@` 文本照旧走 `ComposerRoute.Message` → `send()`。

**有意偏离（两条，都必须带着理由读）**：

1. **光标用"草稿末尾"近似。** `Composer` 是 `OutlinedTextField(value = draft: String)`（`ui/screens/ChatScreen.kt`），**没有光标状态**，所以只有末尾那个提及 token 会补全；光标停在中间编辑旧的 `@` 时不弹列表。pi 读的是 `line.slice(0, cursorCol)`（`autocomplete.ts:296`）。**理由是输入框当前没有光标状态，不是"pi 也这样"。**
2. **debounce 150 ms（pi 是 20 ms）**，因为这里每次查询是一次 guest 进程。这是**延迟**，不是不同的答案：被抢答的查询结果直接丢弃。**没有真机数据，不声称任何延迟数字。**

另外两条与 pi 不同的细节（都写在 `PiFileMentions.kt` 的 KDoc 里）：pi 每次查询跑**两遍** fd（先 `--max-depth 1` 再递归）并去重（`:749-759`），本实现只跑递归那一遍（排序是全序，第一遍只在截断边界上有可见差异）；排序最后一级 pi 用 `localeCompare`（`:783`），这里用普通字符串序，只有"键完全相同"时才会看出差别。

**后续项（记在这里，别让它消失在聊天里）**：

- [ ] `Composer` 改 `TextFieldValue`，把真实光标带上，去掉偏离 1；**—— 仍未做（复核于本轮，工作树）**：输入框仍是 `OutlinedTextField(value = draft: String)`（`ui/screens/ChatScreen.kt` 的 composer，符号 `ChatScreen` 里那一处 `OutlinedTextField(value = draft, …)`），只有末尾那个 token 会补全。
- [x] ~~把 `app/src/test/kotlin/app/pi/ui/chat/PiFileMentionsCheck.kt` 注册进 `tools/run-app-pure-checks.sh`~~ **—— 已完成（复核于 `d54beb7`；`tools/run-app-pure-checks.sh:270` 的 `run_harness mentions`）**，而且"两处写死的 2 harnesses"已改成**计算出来的计数**（脚本末尾注释明写这个数字曾撒谎）。同批注册的还有 `guest-paths` 与 `agent-tool-paths`。
- [x] ~~**真机验证 `fd` 在 agent 目录 bind 之后仍可执行**~~ **—— 已解决（不是真机问题，是构建期就能定的 bug；复核于 `d54beb7`）**：`/usr/local/bin/{rg,fd}` 的软链目标确实会被 bind 遮蔽，且**首启时必然悬空**（`migrateGuestAgentDir()` 在 `ensureReady()` 之前跑，那时 rootfs 里还没有 `bin/`）。修法是两份都写 + 重放：`PiPaths.agentBinDir()`（绑定源）与 `rootfsAgentBinDir()`（rootfs 副本），`RuntimeProvisioner.ensureToolsVisible()` 在 stamp 未变的早退路径上补回缺失的那份；自检 `AgentToolPathsCheck.kt`（13 项断言，含"两个目录互不包含"）已注册（`run-app-pure-checks.sh:282`）。**"没查证过的已知风险"这句已经不成立。**


### E5. 懒启动引擎 + 无引擎浏览会话 —— **已定：不做（2026-09-12，用户决定）**

**结论：不做。** 用户原话：「启动引擎，这个不用改了，现在都是秒起，没有意义。」

实测前提：引擎冷启动已经是**秒级**（用户在这台设备上的观察），所以这条要改善的那个"启动体感"已经不存在了。**不要再派这条**——它改善的是首屏等待，而首屏已经没有等待。若将来引擎启动变慢（例如 pi 变大、或运行时换成更重的形态），把这条拿出来重估，判据是**实测的启动耗时**，不是设计文档里当初的担心。

下面保留原始记载，以便重估时不必重新调查：

现在**开 App 就起引擎**（`PiRoot` 的 `LaunchedEffect` → `session.boot()`）。而：
- 会话文件就在 App 私有目录里，**Android 能直接读** → 翻历史、看转录、改设置**根本不需要引擎**；
- 引擎只在"真的要发消息"时才需要起。
收尾：把 boot 从启动路径移走，做成"首次发送/首次需要时启动"，并加一个未启动/启动中/就绪/失败的状态分支。**改善的是启动体感，两条技术路线（留 pi / 换自研）都受益。**

### E6. `ASSET_VERSION` 改成内容指纹（B12 的收尾）
现在是手工常量。改成哈希资产树的文件名+大小写进 stamp，**彻底消掉"忘记提升版本号 → 新扩展静默不安装"这个人工步骤**。
**已收尾（复核于本轮，commit `ba297c7`；详见 §B12 末条）**：实现是**运行时**对资产内容算 SHA-256（`DeviceBridgeController.assetFingerprint`），不是构建期写一个指纹文件。选它的理由：指纹必须覆盖**打进 APK 的那份内容**，而 `assets/` 是源目录、构建期脚本还要额外保证"生成物与源一致"这第二个真相；运行时算的是 APK 里真实存在的东西，且这个树只有 129 KB（见 §B12）。手工常量留在原地只作回退。
**pi 侧核对（源码 `bbb61e34`）**：**pi 没有"扩展资产版本/指纹"这个概念** —— 扩展是**扫目录**加载的（`core/resource-loader.ts` 把 `extensionPaths` 交给 `core/extensions/loader.ts` 的 `loadExtensions`/`loadExtensionsCached`），它唯一的缓存是**进程内**的 `extensionCache` + generation token（`loader.ts` 的 `isCurrentCacheToken`），**没有落盘版本戳、没有内容指纹**。所以这个闸门**是我们自己的机制**（因为 APK 里的资产要先拷到 guest 的扩展目录才存在），不是"按 pi 的做法"。它要解决的问题 pi 根本不存在：pi 的扩展就在磁盘上，改完即生效。

### E7. 扩展/包管理界面必须区分「内置」与「用户安装」
`pi install` 装的东西和我们自带的三个混在同一个目录。若不加区分：
- 用户会**以为自己装过** `pi-android-bridge`，删掉它 → **设备能力全部消失**，而 Kotlin 侧服务还在跑，症状莫名其妙；
- 内置扩展应当**不可卸载**，并标明"随 App 提供"。
（这一点随 B5 的界面一起做。）

**状态（已完成，复核于 `6c2059a`）**：界面已区分，**结论是「pi 自己不区分」**，界面照实这么写。

- 数据源只有 App 自己：`packages/PiPackageModel.kt:63-119` 的 `PiBuiltinExtension` 是 `app/src/main/assets/pi-extensions/` 的转写（8 文件 → 3 个入口），入口文件按 pi 的发现规则取——`resolveExtensionEntries`（`package-manager.ts:557-585`）只在子目录含 `index.ts`/`index.js` 时接受该目录，所以两个目录扩展显示为 `index.ts`，单文件扩展显示为自身；安装器写的 `.pi-android-assets` 因为点号开头被 `collectAutoExtensionEntries` 跳过（`:604`）。
- **「内置」这个标注必须声明来源**：pi 把 `<agentDir>/extensions/` 下的一切都当成自动发现的用户扩展（`source:"auto"`/`scope:"user"`，`package-manager.ts:2352-2362`，收集于 `:2470-2475`），和用户手放的文件无法区分；`pi list` 只读 `settings.json` 的 `packages`（`package-manager-cli.ts:970-1002` → `package-manager.ts:977-1003`），内置扩展永远不会出现在里面。**证据留在代码注释与本节，界面上只剩用户需要的那一句**："「内置」是 App 的标注：pi 不分内置和用户安装。"
- 存在性用**纯文件检查**分三态（`packages/PiPackagesHost.kt:455-468`：`engineAgentDirHasEntry`/`rootfsHasEntry` → `PiBuiltinExtension.presenceIn`）：引擎目录里 / 只在 rootfs 副本 / 两个都没有。三态而不是两态，因为两个 agent 目录不是一回事（见 §K5）。**界面上只说"已安装 / 只装在了旧位置 / 未安装"**，两个目录的 host 路径不出现在界面里（§H 第 7 条）。
- **不可卸载**是构造上成立的：内置扩展不在 `settings.json` 的 `packages` 里，`pi remove <名字>` 只会回 `No matching package found` 并以退出码 1 结束（`package-manager-cli.ts:959-966`，`package-manager.ts:1054-1057` 的 `removeSourceFromSettings` 返回 false），所以内置区块**没有移除按钮**——这一点写进了区块标题"（不能用 pi 卸载）"。曾有一句专门解释它的界面文案（外加"删文件也不会自动回来"的说明），**已按 §H 第 7 条删除**：没有按钮就没有需要解释的操作；`DeviceBridgeController.kt:281-285` 的内容指纹闸门细节留在本文件。
- 界面位置：`packages/PiPackagesScreen.kt:215-296`（`BuiltinCard`/`BuiltinRowView`/`ListSectionHeading`），文案在 `packages/PackageStrings.kt:67-96`；`pi list` 的行上方加了来源标题"已安装的资源包"，两套东西不再混成一张列表。
- 覆盖：`app/src/test/kotlin/app/pi/packages/PackagesPureLogicCheck.kt:389-441`（名单、入口规则、guest 路径、存在性真值表）。

### E8. 终端方案的决策依据（记录，不是待办）
工作区终端是**手写的 VT 模拟器**（3265 行），但**这个选型从来没有做过"买 vs 造"的对比**——是子代理自行决定、我未监督的。
现成方案与其许可（**使用前必须逐个核实，许可搞错是法律问题**）：
| 方案 | 许可 | 特点 |
|---|---|---|
| xterm.js + WebView | MIT | 覆盖率最高，行业标准；我们已用 WebView 跑 Mermaid |
| ConnectBot `termlib` | Apache-2.0 | 成熟原生 VT 模拟器（Java），不用 WebView |
| Termux `terminal-emulator` | GPL-3.0 | 技术强但**传染性许可**，链入即整体 GPL，基本排除 |
收尾：**先上真机验证自写模拟器的覆盖率**；不行就换 xterm.js（MIT、覆盖最全、基础设施已在）。

---

## F. 协议层做不到的（这是限定，不是待办）

写成待办会永远做不掉，写清楚才能让人不再重问一遍。

### F1. 扩展加载失败是静默的
pi 把扩展加载错误**只写进 `runtime.diagnostics`，不发任何事件**。RPC 侧**没有通道**能感知"某个扩展加载失败了"。所以界面上无法提示——除非从 guest 侧读那个诊断文件（另一条链路，未做）。

### F2. GUI 永远够不着的这几个
`setLabel`、工具集的读取与设置、`getSystemPrompt`、扩展加载诊断——**RPC 协议里根本没有对应命令**，`prompt` 也派发不了（内置命令不在 `get_commands` 里）。
处置：GUI 里标「仅终端」并指向 `工作区 → pi TUI（原版）`。**不要伪造。**

### F3. TUI-only 的扩展能力
`custom()`、`setFooter/setHeader`、`setWorkingMessage/Visible/Indicator`、`setHiddenThinkingLabel`、`setEditorComponent`、`addAutocompleteProvider`、`onTerminalInput`、主题读写、`getEditorText()`（RPC 永远返回 `""`）、扩展驱动的 `setToolsExpanded`。
这些**在 pi 自己的 RPC 模式里就是 no-op**，任何 RPC 客户端都拿不到。出路是 `pi TUI（原版）` 标签页。

---

## G. 已交接、待完成（归属明确）

| 项 | 归属 |
|---|---|
| `rpc/**` 六条：`ExtensionError.extensionPath`/`event`、`MessageEnd.customType`/`display`、`model_select` 死分支、`AssistantDelta.Unknown` 注释与行为、`system_prompt`/`error` 死分支 | RPC 代理 — **已交付**（见 §J） |
| `turnIndex` 两份审计结论相反，**回源码裁决** | RPC 代理 — **已裁决**：RPC 线上**没有** `turnIndex`（见 §J） |
| `contentIndex` 用**真实 `pi --mode rpc` 抓包**验证，不许靠文档猜 | RPC 代理 — **已验证并修复**（`pi --mode rpc` 在本机起不来，改用真实适配器直连；见 §J） |
| `PiEngineHost` 的 `restart()`/`reload()` 入口（B6 与 `/reload` 替代方案的共同前置） | 包管理代理（新授权） |
| `runtime/**` 信任存储（`PiTrustStore` + `trust.json`） | 包管理代理 |
| `CAMERA` 权限 + `NO_PERMISSION` 前置条件 | 闸门代理 |
| C 节全部真机验证项 | **等 APK 能构建出来** |

---

### 更正：`:rpc` 那次红**不是作用域问题**（我诊断错了，代理用反证推翻）

**错误诊断**（我 + 包管理代理）：`TURN_FAILURE_REASONS` 声明在外层类的 `private companion object` 里，被更内层作用域引用，看不到。

**真实原因**：**那个常量当时根本还没写进文件**——RPC 代理在补 P5/F3 的 `failTurn` 时，**先写了引用、常量随后才加**，正好被撞上。

**反证（来自源码本身）**：`ENTRY_EVENT_TYPES` 一直就放在**同一个 `private companion object`** 里，并由**同一个 `onEvent` 成员函数**读取（`event.type in ENTRY_EVENT_TYPES`），**始终编译通过**。成员函数访问本类 companion 是普通 Kotlin，不存在"更内层作用域看不见"这回事。

**保留的改动**：常量仍按更稳的写法放成**文件级 `private val`**（`Transcript.kt:534`，引用在 `:661`）——两种写法都对，文件级不依赖嵌套规则，不易回退。

**教训（写进 H 节的理由）**：一次"看起来专业"的根因诊断，**如果没被回代码验证，就会把下一个人送到一个不存在的坑里**。本例里诊断方是主线 + 另一个代理，验证方是文件的所有者——**照单全收会浪费的是别人的时间**。

## H. 流程教训（写给下一个人，不是待办）

1. **记录 ≠ 派活。** 我把缺口写进这份文档就以为处理过了，但文档**不是工作队列**。每份审计报告进来，必须**立刻**转成"归属人 + 文件 + 验收条件"的条目，否则就会像发生过的那样——连着两轮被问"是不是还有落下的"，而**每次一问都能翻出东西**。
2. **文件归属切得干净，会切出"公共入口无主"的空洞。** `engine/**` 的重启入口就是这样：两个功能都依赖它、两个代理都不许碰。指派任务时要专门检查一遍"**有没有一个所有功能都依赖的文件**"。
3. **委托模板必须禁掉全部 git 写操作**，不只是 `commit`/`push`。`checkout` / `reset` / `stash` / `clean` / `restore` **能一次毁掉所有并行代理的在制品，而且不报错**。
4. **并行时每个代理都会报"红不是我的文件"。** 这没有意义——**只有冻结树之后的整树 typecheck 才算数**。
5. **自证不等于认账。** 代理报告 `typecheck: OK` 时要独立复跑；审计报告里"已核对为正确"的结论也要抽验。**反过来，报"证不出来"的项目要保留**，因为自信的错误发现会让人去改本来正确的代码。
6. **一条写着"没做"、其实已经做完的条目，比漏记更危险。** 漏记只是少了一条待办；过时的"没做"会让人**重做已经正确的代码**，而重做往往会把它弄坏。本文件因此做了两件事：一是把 A1/A2/B1–B4/B8/B11/E2 标注为已完成（复核于 `dc00279`，逐条给了 `path:line` 证据），二是把"逐行复核"本身变成例行动作——**任何审计写进本文件的条目，下次复核时必须回代码确认它仍然成立**，而不是只在派活时当作事实引用。判断标准是"能不能指出现在还在缺的那一行"，不是"文档里写着缺"。
7. **用户界面里不许出现文档路径、文件名、章节号（`§`），也不许解释"我们内部为什么这么做"。** 用户原话：**"不要乱加没用的说明。有了 Git 不就好了吗？写个鸡毛说明？没用的说明全删掉。"** 起因是 `PackageStrings` 里那句 `git: 源当前用不了…（docs/known-gaps.md §K2）`——它把**源码文档写进了用户界面**，这是无论内容对不对都不该发生的事。判定标准：**这条文案说的是用户此刻需要的事实，还是我给自己的设计做辩护？** 后者一律写进代码注释或本文件，不进界面。缺功能就补功能；**补不了的功能不该由一句说明来代替**（本例的正解是另一个任务把 git 打进 runtime，然后示例里加回 `git:` 即可）。规则落地时**回溯查了同批新增的 E7 文案**并一起缩减（见 §E7：删掉 `BUILTIN_UNINSTALLABLE`、`LIST_SECTION_NOTE`、`agentDirs`，`BUILTIN_NOTE` 从 5 行降到 1 句）。**那批遗留已清理完毕**（`PackageStrings` 的 `SCOPE_PROJECT_LOCKED`、`PROJECT_PACKAGES_HIDDEN`、`TRUST_INVALID_NOTE`、`TRUST_SESSION_ONLY_NOTE` 都已去掉 `*.ts:行号`；内部解释句 `SUBTITLE` 整条删除、连调用点一起，见 `docs/ui-prose-audit.md` 的 A1）。回归判据：对 `packages/**` 的字符串字面量做全文扫描，`.ts:` / `.kt:` / `docs/` / `§` / `auth.json` / `models.json` / `settings.json` / `agentDir` / `guest` / `host` **零命中**（保留项只有三类：用户项目里被检查的目录项名、用户要填的端点地址、以及纯代码里的路径拼接）。

8. **代理的结论必须落文件。只写在报告里的结论 = 不存在。** 每条"pi 行为 ↔ 我们的符号 ↔ 状态"都必须成为**某份文档的一行**，而不是只活在聊天记录里——聊天记录不参与下一轮的阅读，所以那种结论等于没写过，工作等于白做。本仓已经在三处栽过：**F9 那一行**（写成了"已派给某人"，而修法随后已经落地）、**`EffectiveKind.RestartEngine` 标签与唤醒锁直读**（代码里做了，账本里 grep 零命中）、**bash 卡的"完整输出路径 + 按 pi 的条件摘掉正文里重复那句"**（只在代码注释里，没有任何账本行）。

   **落地机制（每轮冻结提交前，由提交者执行一次"账本收口"）**：把本轮**只在报告里**的结论逐条抄进对应账本（`gap-disposition.md` 的处置表、本文件的章节、`feature-gaps.md` 的行、`remaining-work.md` 的清单）；每条都要能回答"**下一个人照着这行能找到什么**"——符号名、pi 侧的 `file:line`、以及**状态**；提交信息里点明这次收口补了哪几行，这样它自己也进了历史。**判据**：同一个结论在聊天里说过、但 `grep` 不到，就是漏收口。

   **已按这条规则完成的三处收口（2026-09-12）**：① F9 的处置表行补上"修法已落地"（`gap-disposition.md` §10 的 close-out 段，`89270cd0` 的实现）；② 本节 §I7 补上 `EffectiveKind.RestartEngine` 与唤醒锁直读；③ bash 卡的"完整输出路径 + 按 pi 的条件摘掉正文里重复那句"写进 `gap-disposition.md` §10 的 close-out 段（F17 那一族）。**另修掉一处比"过时"更坏的东西**：`feature-gaps.md` 在 §1 改成"reproduced"之后，同一文件的 §1 另一批行与 §2.8 标题**仍写着 "not set, no setting" / "has no app path"** —— 同一份文档自相矛盾，读者无法判断哪句是真的；这类"半更新"必须当成漏收口处理。

---

## I. 功能缺口审查（`f2a50c61`）发现的、原先三份文档都没记的 11 条

> 来源：`docs/feature-gaps.md`（667 行，148 行分级）。审查基线 `a7b7738` vs pi `bbb61e3`。
> 分级计数：IMPLEMENTED 74 · PARTIAL 20 · MISSING-GUI 26 · MISSING-TERMINAL-ONLY 12 · CLI-ONLY 9 · N/A 7。
> **其中 4 条是静默的行为缺陷**（写错字节、丢用户输入、死代码、失效开关），比"功能缺失"严重——因为它们**看起来是成功的**。

### I1. `/tree` 的"跳到历史某点"在 RPC 里根本不可达（DEFECT）—— **已判定为协议 limit + 界面已说真话（复核于本轮，工作树）**
pi 能在**不新建会话文件**的前提下跳到任意历史点（`docs/sessions.md:71`、`interactive-mode.ts:5216-5322`）；而 `RpcCommand` 只有 `get_tree`/`fork`（`rpc-types.ts:20-74`）。
App 的「分支」动作实际是 **fork，会写一个新会话文件**（`SessionTreeScreen.kt:74,165,226`）——**和 pi 的语义不同**，用户以为在"跳分支"，实际在"造新文件"。分支摘要也因此不可达。
→ 应补进 **F2**（协议做不到）。

**状态（已处置，属于"pi 有但我们够不着"）**：能力缺口不变（RPC 没有对应命令），**变的是界面不再撒谎**——`ui/chat/SessionTreeScreen.kt:350` 的按钮已改名为「分叉新会话」，并在上方写明：pi 的 `/tree` 是原地移动叶子、不写文件（`docs/sessions.md:71`），RPC 没有这条命令（`rpc-types.ts:20-74`），所以这个按钮是 fork。**不要再派"实现原地跳分支"的活**：除非 pi 的 RPC 协议新增命令，否则它做不了。

### I2. **动作类设置行点了没反应**（DEFECT，E9 的扩展）——**已处置：applied (uncommitted)，19 行逐行有结论，没有一行留在「点了弹一句未接入」**

> **先说数量：标题里的"20"已经不成立，本行原先改成的"19"也已经过期。** 第 20 个 Action 行 `app.device.sessionOverride` 在 `9a16271`（"drop the disconnected device/trust rows"）里随 7 个 `app.device.*` 一起删掉了；§I2 那一轮又删掉一批、并把凭证两行改为开屏；**注册表现在有 8 个 `kind = PiRowKind.Action,`**（含后来新增的 `app.runtime.restartEngine`）。`§E9-接线规格` 里"20 个动作行"是同一批过时数字，已在 `:354` 修。

**（以下是修复前的记载，保留以便复核。）**
`PiRoot.kt:130-138`（当时）**从不传 `onRunAction`**（默认 `null`，`PiSettingsStack.kt:34`），`SettingsGroupScreen.kt:151-170` 把它们降级成"这个入口由运行时接管…"。**（现在已接：见 §E9-接线规格；那句提示语也已改成"这个入口当前不可用。"，且已没有 Action 行会走到它。）**
涉及**App 里唯一的 API Key/OAuth 凭证入口、会话导入、更新检查、日志查看、诊断导出**——**全部点了没反应**。而且**整个仓库没有 `auth.json` 的写入者**。

**本次处置（applied, uncommitted，未上 CI，未上真机）** —— 判据是 **pi 里有没有**，不是我们想不想做：

| 行 key | pi 里有吗（依据） | RPC 有吗 | 结论 |
|---|---|---|---|
| `app.credentials.apiKey` | 有：`--api-key`（`cli/args.ts:108`）、`/login`，落在 `auth.json`（`core/auth-storage.ts:52`） | 无（App 直接写 `auth.json`） | **实现**（`PiCredentialScreen`；`PiSettingsStack.kt:146`） |
| `app.localModels.manage` | 有：`/llama`（`extensions/llama/index.ts:183`），但加载/卸载/下载对 `ctx.mode !== "tui"` 直接返回（`:186-189`） | 无 | **实现**（端点写 `models.json`）+ 描述明说加载/下载仅 TUI（`PiSettingsStack.kt:147`） |
| `app.compaction.runNow` | 有：`/compact` | 有：`compact`（`rpc-types.ts:44`） | **实现**（`PiRoot.kt:209` `session.compact()`）；带自定义指令的形态在对话面板（`ChatScreen.kt:341`），描述已写明 |
| `app.security.emergencyStop` | 有：Esc = 清队列 + abort；bash 由 `abort_bash` 停 | 有：`abort` / `clear_queue` / `abort_bash`（`rpc-mode.ts:428` / `:433` / `:586`） | **本次实现**：`PiRoot.kt:217-220` → `session.stop()`（`PiEngineSession.kt:480-486`）+ `session.abortBash()`（`PiEngineApi.kt:262`，`abortBash` 停的是**全部**在跑的 bash，`agent-session.ts:3073-3077`） |
| `app.credentials.oauth` | 有：`/login`（`interactive-mode.ts:3052-3055` → `:5485`），OAuth 只在交互模式 | 无 login/logout | **改跳转**：切 工作区 → 终端，文案写明 `/login`（`PiRoot.kt:49-70`） |
| `app.sessions.import` | 有：`/import`（`interactive-mode.ts:6107`/`:6122` → `runtimeHost.importFromJsonl`） | 无 | **改跳转**：切终端，写明 `/import <path.jsonl>` |
| `app.about.changelog` | 有：`/changelog`（`interactive-mode.ts:3022-3025`） | 无 | **改跳转**：切终端，写明 `/changelog` |
| `app.sessions.exportAll` | **无批量导出**：`/export` 只导当前会话（`interactive-mode.ts:6064-6065`） | 只有 `export_html`，且只作用于当前会话（`rpc-types.ts:60`、`rpc-mode.ts:600-602`） | **撤掉**（单会话导出在对话面板 `/export`，`PiSlashCommands.kt:148-151`，可发现性不丢） |
| `app.runtime.checkUpdate` | pi 自己的检查被 App 关掉（`PiEngineHost.kt:305-306` 设 `PI_SKIP_VERSION_CHECK`，`version-check.ts:98`）；`pi update --self` 是 npm 自更新（`package-manager-cli.ts:1033-1068`），而本 App 的引擎是 APK 资源按 revision 解包（`RuntimeProvisioner.kt:505-528` `extractEngine`，`:122` `wipe`），guest 里自更新会被下一次解包覆盖或与出厂版本分叉 | 无 | **撤掉**（版本由只读的「pi 版本」行报出，`PiSettingsRegistry.kt:1510` 起） |
| `app.runtime.rollback` | **无**：pi 没有 rollback 子命令（`cli.ts` / `package-manager-cli.ts` 均无） | 无 | **撤掉** |
| `app.runtime.cleanNpmCache` | **无**：pi 没有清缓存命令；App 也没有能在 guest 里跑 `npm cache clean` 的控制台（只有引擎 argv 与 PTY 终端） | 无 | **撤掉** |
| `app.runtime.phantomKillerGuide` | **无**：纯 Android 设备侧 workaround；它给的修法是 `settings put global settings_enable_monitor_phantom_procs false`，本 App 被策略禁止执行，也无法代用户执行 | 无 | **撤掉**（设备侧唯一接通的面是「设备能力」页，入口在设置首页，不在这行） |
| `app.runtime.logViewer` | **无**：pi 不写可供 GUI 尾随的日志文件 | 无 | **撤掉**（App 也没有分级日志 sink：引擎 stderr 只是内存里的一段 `MAX_STDERR_CHARS`，设备桥只有 audit log） |
| `app.runtime.exportDiagnostics` | **无**对应行为 | 无 | **撤掉**（要真做是新功能：文件选择器导出 + zip 打包，不该当 Action 行挂在 pi 的设置目录里） |
| `app.about.importFromDesktop` | **无**：pi 就地读它的 agent 目录，没有"从桌面导入"命令，也没有那种归档格式 | 无 | **撤掉** |
| `app.about.exportConfig` | **无**：pi 没有配置导出命令，设置就是明文 JSON（`settings-manager.ts:154-181`） | 无 | **撤掉** |
| `app.about.rawSettings` | **无**：pi 的 `/settings` 是分类编辑器菜单（`components/settings-selector.ts`），没有原样 JSON 编辑器 | 无 | **撤掉**（本 App 的逐键编辑器 + 全键搜索就是那个菜单的等价物；手编原始 JSON 还会绕过 pi 自己的写盘逻辑，和 `packages` 行同一个坑） |
| `app.about.reset` | **无**：设置菜单里没有任何 reset 项（`settings-selector.ts` 无 reset id） | 无 | **撤掉** |
| `app.about.licenses` | **无**：pi 不带任何许可界面 | 无 | **撤掉**（**这是判断项，不是铁证**：合规界面本来就不是 pi 行为。若 App 需要它，应以真实界面回归，而不是当 Action 行放回 pi 的设置目录） |

**落地位置（本次改动，未提交）**：
- `ui/settings/PiSettingsRegistry.kt` —— 19 行里 **12 行删除、7 行保留**（4 实现 + 3 跳转），被删的每一处都留了注释说明为什么：`app.sessions.exportAll`（`:731` 起）、运行时 6 行（`:1520`/`:1555`/`:1588`/`:1596` 起）、关于 5 行（`:1688` 起）。
- `ui/PiRoot.kt` —— `TERMINAL_ONLY_ACTIONS`（`:49-70`）命中时 `notifyUser` 说出要跑的命令 + `requestNav(NavRequest.Workbench)`（`:190-202`）；`app.security.emergencyStop` → `session.stop()` + `session.abortBash()`（`:217-220`）；兜底 `else` 现在**对注册表里任何一行都不可达**，文案不再解释内部原因（`:230-233`），只作回归守卫。
- 顺带同区域改动：`packages` 行从"让用户手编数组"改成 **只读 + 跳转包管理页**（`PiSettingsRegistry.kt:783-807` 的 `readOnly = true`，`PiSettingsStack.kt:148-153` 的 `hostActions`）。理由：那个数组是 `pi install` 的落盘结果（`core/package-manager.ts`；`PiPackagesScreen.kt:309` 自己写着"列表就是 `pi install` 写进 settings.json 的 packages"），手编会写出"看起来装了、其实没生效"的条目。
- **界面文案规则（本轮用户新下，已按它改）**：**UI 里不出现文档路径 / 文件名 / `§` 章节号，也不解释我们内部怎么实现**。本次新写的每一句都过了一遍：三条"仅终端"行的 description、`app.compaction.runNow`、`app.localModels.manage`、`packages`、`app.about.changelog`、`collapseChangelog`、`app.credentials.apiKey`（原来在界面上写 `auth.json` / `settings.json`），以及 `PiRoot.kt` 的两条提示与 `SettingsGroupScreen` 的 no-host 文案（原来写"这个入口由运行时接管…"）。**仍然违规、但不在本次改动范围的**：`PiSettingsRegistry.kt:822`（`SKILL.md`）、`:872`（`AGENTS.md`/`SYSTEM.md`）、`:1429`（`trust.json`/`.pi/settings.json`），以及 `PiCredentialScreen` 等界面里的同类文案——那是一次目录级文案统一，**故意不在本次顺手改**，免得和并行的代理人撞同一片文案。
- 顺带修掉的重复行：`stopReasonRow()`/`liveTurnIssue` 已删（冗余，会与 rpc 归约器新写的失败行重复），见 `ui/PiSessionViewModel.kt` 的 `syncTranscript` KDoc。

**没有做的事，别读成 done**：
- **没编译、没上 CI、没上真机**：本次一行 gradle 都没跑（父代理统一提交、统一验证）；`python3 tools/check-nested-comments.py` 是唯一跑过的自查。
- **跳转的落点没有在设备上验证过**：`TerminalPane.kt:298` 默认开的标签是 `Shell`，所以切到工作区后用户可能还要自己切到 `pi TUI` 标签——提示文案只说了"已切到 工作区 → 终端，请在那里运行 /login（/import、/changelog）"，**没有把那个标签设为默认，也没有验证切过去之后标签状态是什么**（`ui/terminal/**` 不在本次可改文件范围内）。
- `app.about.licenses` 的撤掉是判断项，见上表最后一行。
- `docs/feature-gaps.md` 里列的同一批 key（`:270`、`:302`、`:509-514`）**没有同步**——它不在本次允许改的文件范围内，父代理需要时再改。

### I3. `/export <path>.jsonl` **会把 HTML 写进 `.jsonl` 文件**（DEFECT，静默写错字节）—— **已解决（复核于 c6f8e41）**
App 永远调 `export_html`（`PiSessionViewModel.kt:1371-1376`），而它的命令面板却宣传支持 `.html/.jsonl`（`PiSlashCommands.kt:121-124`）。
pi 的 TUI 会按扩展名分支（`.jsonl` → `exportToJsonl`，`interactive-mode.ts:6064-6065`、`agent-session.ts:3488`），但 **RPC 只暴露 `export_html`**（`rpc-types.ts:60`、`rpc-mode.ts:600-602`）。
**结果：文件内容错、扩展名错，还弹一个成功提示。**
→ 处置：要么面板里不再宣传 `.jsonl`，要么自己按 pi 的 JSONL 格式导出。

**状态（已解决）**：选了"自己按 pi 的格式导出"。收尾条件 = 面板怎么说、磁盘上就是什么。
由 `c6f8e41` 满足：符号 `PiSessionViewModel.exportSession`（按扩展名分派 `JSONL_SUFFIX`）、
`exportJsonl`（`branchPath` 从 `get_entries` 的 `leafId` 回溯分支、`sessionHeaderOf` 取 header 的 id/cwd、
`CURRENT_SESSION_VERSION` 写版本）、动作枚举 `PiCommandAction.ExportSession`；
条目字节直接来自 `get_entries`，只按 pi 的 `core/session-export.ts` 重链 `parentId`。

### I4. `follow_up` 在 UI 里是死代码（DEFECT）—— **已解决（复核于 c6f8e41）**
`PiSessionViewModel.sendFollowUp`（`.kt:1086`）**全树零调用方**；输入框只会 `steer`（`send`，`.kt:1053-1063`）。
而 `set_follow_up_mode` **是接通的**——所以那个开关**看起来能用，实际没有任何东西能入队**。

**状态（已解决）**：收尾条件 = 存在一个能触达 `sendFollowUp` 的界面入口，且只在流式中提供
（pi 的绑定在空闲时等同普通发送，无条件入队会静默排在队里等下一次回合）。
由 `c6f8e41` 接上：符号 `PiSessionViewModel.sendFollowUp` ← composer 的「后续」chip
（`Composer.canFollowUp` = `streaming && draft.isNotBlank()`，且仅在 `streaming` 时渲染）。
签名后续由 `ecd98a2` / `7c2ecd7` 扩出 `images`，`follow_up` 因而不再丢附件。

### I5. 自定义主题 JSON 永远到不了 App 自己的颜色（DEFECT）—— **已完成（复核于本轮，工作树；两处都修了）**
两套写死的调色板（`PiPalette`），**没有任何代码解析主题文件**；`MainActivity` 除 light/dark/`a/b` 外忽略一切名字——而 `PiPalette` 的类注释把"跟随 pi 主题"写成设计意图。**注释与实际相反。**
主题发现也不全：选择器只从 `themes` 设置里取名字（`PiSettingEditorHost`），**漏了 pi 的规范目录 `~/.pi/agent/themes/`**（`resource-loader.ts:815`）。
**（以上两段是修复前的记载，保留以便复核；现在的行为见下面「状态（已完成）」的 ①②。）**

**状态（已完成）**：收尾条件 = ①App 的颜色真的来自 pi 主题文件；②`~/.pi/agent/themes/` 与 `.pi/themes` 里的主题可被选中。
- ① `MainActivity`：`val theme by session.theme.collectAsState()` → `PiTheme(palette = theme.palette, dark = theme.dark)`，注释明写"不是两个手写常量"。**注意 `PiPalette` 并没有被删空**：它仍保留 `Dark` / `Light` 两个内置常量，那是"没有主题文件时"的 fallback 与令牌解析的基底（`PiThemeFiles` 的 `fallback()`、`PiTheme.kt` 的 `LocalPiPalette` 默认值都用它），**不再是 App 唯一的颜色来源**。
- ② `ui/theme/PiThemeFiles.kt:152` `scanDirectory(File(agentDir, "themes"), …)`、`:153` `scanDirectory(File(workspace, ".pi/themes"), …)`；解析在 `PiThemeLoader.load`（`:202`），含 `a/b` 自动对与 256 色/变量引用。
- pi 依据：`core/resource-loader.ts:815`（agentDir/themes）、`:821`/`:875`（项目 `.pi/themes`）、`modes/interactive/theme/theme.ts:232`（`resolveVarRefs`）、`:597`（`resolveThemeSetting`）。

### I6. Esc 会清空队列、**并且丢掉你刚打的字**（DEFECT）—— **已解决（复核于 c6f8e41）**
`ChatScreen.kt:433` 调 `stop()` 时**没有传 `onRestored`**，而 `PiSessionViewModel.kt:1097` 支持把内容还回输入框。
→ 用户按 Esc 想停下，结果**待发队列被清、输入的东西也没了**。

**状态（已解决）**：收尾条件 = Stop 之后队列里的文字回到输入框，且不覆盖用户已打的草稿。
由 `c6f8e41` 满足：符号 `ChatScreen` 的 `onStop` 调用 `session.stop { restored -> draft = mergeRestoredQueue(restored, draft) }`，
`mergeRestoredQueue` 复刻 pi 的 `[queued, current].filter(trim).join("\n\n")`。

### I7. 13 个已注册设置**全树只有注册表一处出现**（= 失效开关）—— **已完成（复核于本轮；逐键表见下）**
含 `dynamicColor`、`fontScaleDelta`、`messageDensity`、`showTimestamps`、`thinkingCollapsedByDefault`、`app.tools.expandByDefault` 等；另外 `hideThinkingBlock` **在 `ChatScreen.kt:345-348` 从未被传入**。
→ 界面给了开关，**没有任何消费者**。与 E9/I2 是同一类病。
**逐键复核（工作树 + pi 源码 `bbb61e34`/0.85.1，本轮；13 键 = 4 个 `app.terminal.*` + 6 个 `PiSessionViewModel.readPrefs` 消费的 + 3 个早先已删）**。判据是 pi 源码，不是我们的想象；`grep -rn "<key>" --include=*.ts packages/`（排除 `.test.`）：

| 键 | pi 侧 | 我们这边（符号） | 结论 |
|---|---|---|---|
| `hideThinkingBlock` | **pi 有**：`core/settings-manager.ts:119`（`Settings` 接口）、`:962` `getHideThinkingBlock`、`:982` `setHideThinkingBlock`；消费在 `modes/interactive/components/assistant-message.ts:143`（渲染助手消息时按它隐藏 thinking 块） | `PiSessionViewModel.readPrefs` → `ChatScreen` 的 `hideThinking` 与搜索过滤 | **1:1，一致**。RPC 模式下 pi 不渲染，App 就是渲染方，所以读同一个键、按同一语义隐藏是唯一正确做法 |
| `app.appearance.fontScaleDelta` | **pi 无**（0 hits） | `readPrefs` → `MainActivity` `textScaleDelta` → `PiTheme` | App 自己的键（有 `app.` 前缀）；已接 |
| `app.appearance.messageDensity` | **pi 无**（0 hits） | `readPrefs` → `ChatScreen` 的块间距 | 同上 |
| `app.appearance.showTimestamps` | **pi 无**（0 hits） | `readPrefs` → `ChatScreen` 的 `visibleItems` | 同上 |
| `app.appearance.thinkingCollapsedByDefault` | **pi 无**（0 hits） | `readPrefs` → `ChatScreen` 的 `thinkingDefaultExpanded` | 同上 |
| `app.tools.expandByDefault` | **pi 无**（0 hits） | `readPrefs` → `ChatScreen` 的 `toolsExpanded` | 同上 |
| `app.runtime.keepAlive` | **pi 无**（`keepAlive` 的 10 处命中都在 `experimental/server.ts`，是 HTTP keep-alive，与此无关） | `readPrefs` → 启动路径上决定要不要起前台服务 | App 自己的键（Android 前台服务）；已接 |
| `app.terminal.fontSize` | **pi 无**（0 hits） | `ui/terminal/TerminalSettings.kt` 的 `TerminalPreferences.read` | App 自己的键（我们的终端渲染）；已接 |
| `app.terminal.keyBar` | **pi 无**（0 hits） | 同上 | 同上 |
| `app.terminal.cursorStyle` | **pi 无**（0 hits；pi 唯一的游标键是 `showHardwareCursor`，`settings-manager.ts:147`） | 无 | **已删除（复核于 `6c2059a`）**：App 的键 + 无消费者 + 行为归 libvterm（`DECSCUSR`） |
| `app.terminal.scrollbackLines` | **pi 无**（0 hits） | 无 | **已删除（复核于 `6c2059a`）**：同上（滚回缓冲是组件固定的那份） |
| `app.tools.bashTimeoutSeconds` | **pi 无此设置**；pi 的 bash 超时是**每次调用的工具参数**（`modes/rpc/rpc-types.ts:55` 的 `bash` 命令只有 `command`/`excludeFromContext`，没有 timeout 字段；`core/tools/bash.ts:24-34` 的 `resolveTimeoutMs`） | 无 | 早先删除，**依据成立** |
| `app.tools.outputMaxLines` | **pi 无此设置**；截断是编译常量（`core/tools/truncate.ts:11-12` `DEFAULT_MAX_LINES`/`DEFAULT_MAX_BYTES`） | 无 | 早先删除，**依据成立** |

**§I7 的收尾（2026-09-12，`2dbe79f` 之后）——两条只在报告里的结论，现已落账**：
- **新增 `EffectiveKind.RestartEngine`**：三个进程开关（`app.runtime.offline`/`systemPrompt`/`cacheRetention`）原先借用了 `EffectiveKind.Reload`（标签「需重载」），而它们**只在新进程生效**，所以「重载」是错的说法；现在有专属的 `RestartEngine`（标签「需重启引擎」），`ui/components/PiCommon.kt`。配套的动作行是 `app.runtime.restartEngine`，调用一律 `allowInterrupt = false`，所以有回合在跑时引擎自己会拒绝（`PiEngineHost.restart`）。
- **唤醒锁那一行改成直读**：`app.runtime.wakeLock` 原先是**推断**（"前台服务在不在运行"），现在读 `PiEngineService` 暴露的真实状态，并由 `RuntimeFacts` 供值。四个运行时只读行（`piVersion`/`nodeVersion`/`rootfsUsage`/`wakeLock`）都改成了「取不到就说取不到」，不再摆一个看起来像真值的默认值。
| `app.appearance.dynamicColor` | **pi 无**（Monet 是 Android 概念；pi 的主题是 `theme`，颜色来自 pi 主题令牌） | 无 | 早先删除，**依据成立** |

- **同一类病：`app.runtime.piVersion` / `nodeVersion` / `rootfsUsage` / `wakeLock` 曾经全树没有写入方**（全是 `app.` 前缀 = 我们自己的键，pi 无对应物），只显示各自默认值（"未安装"/"未安装"/"未知"/"未知"）。`G_RUNTIME` 组摘要原先读 `piVersion`，于是在设置首页写着「pi 未安装 · 保活：…」——**已改**为只报 store 里真有的值（摘要现在只拼「保活：…」）。
  - **—— 已修（applied (uncommitted)；`tools/typecheck.sh` 0 error）**：四个行现在由 `ui/settings/RuntimeFacts.kt` 供给**真值**，读不到就明说读不到，默认值也换成了中性的「未读取」（不再有像真值的假值）：
    | 行 | 真值取法 | pi 有对应物吗 |
    |---|---|---|
    | `app.runtime.piVersion` | 读 `<rootfs>/opt/pi/node_modules/@earendil-works/pi-coding-agent/package.json` 的 `version` —— **与 pi 自己的版本来源同一个文件**（`packages/coding-agent/src/config.ts:505` `export const VERSION = pkg.version`，`--version` 打印的就是它，`src/cli/args.ts:93`） | **pi 有**（版本值的来源是 pi 的） |
    | `app.runtime.nodeVersion` | 读 `<rootfs>/opt/node/include/node/node_version.h` 的三个 `*_VERSION` 宏，拼成 `v maj.min.patch` —— 该头文件就是 `node -v` 的来源；载荷是官方 tarball 无损重压（`tools/fetch-runtime.mjs`） | `pi 无对应物`（App 的运行时；值是 Node 的） |
    | `app.runtime.rootfsUsage` | 在 IO 线程 `walkTopDown()` 累加 `<files>/pi/runtime` 的真实字节数 | `pi 无对应物`（App 的磁盘占用） |
    | `app.runtime.wakeLock` | 查系统返回的**本应用服务**列表里是否有 `PiEngineService`（锁本身在该服务里是 private；服务运行时获取、停止时释放，行的说明写明了这一点） | `pi 无对应物`（App 的前台服务） |
  - 值的通路：`RuntimeFacts.read()` 在 `PiSettingsStack` 的 `LaunchedEffect` 里于 IO 上跑一次，经 `runtimeOverrides(facts)` 变成四行/搜索结果的 `valueOverrides`（`SettingsGroupScreen`、`SettingsSearchScreen` 都有这个可选参数），**不写进 pi 的 settings.json**（避免把易变值持久化）；读不到时按"运行时尚未解包 / 运行时里没有这个文件 / 系统没有返回本应用的服务列表"分别写明原因。
  - **未验证（真机/CI）**：①设备上 `package.json` 与 `node_version.h` 是否都在（载荷解包清单未在真机核对过）；②`getRunningServices` 虽在 API 26+ 只返回本应用服务，但它是 deprecated API，真机上是否稳定返回需要一次设备验证；③目录遍历在满树（数百 MB、上万文件）时的耗时没有实测，只保证不在主线程。

### I8. 搜索能力缺失（MISSING-GUI）—— **已完成（复核于本轮，工作树；四项都有）**
转录内搜索、会话树过滤、会话列表的搜索/排序/命名/过滤——都没有。

**状态（已完成）**：收尾条件 = 四项各自有可达入口，且判据照着 pi 的 TUI 抄（不是我们发明一套）。
- **转录内查找**：`ui/screens/ChatScreen.kt:274-297`（`searchOpen`/`searchQuery`/`searchMatches`/`searchCursor`）、`:332-336`（跳到匹配）、`:456-459`（AppBar 入口），行内高亮用 pi 的 `searchMatchBg`/`searchMatchText` token（`ui/theme` 调色板）。
- **会话树过滤**：`ui/chat/SessionTreeScreen.kt:96-97`（`filter`/`query`）、`:137`（切模式）、`:232-239`（`TreeFilter`，五种模式）、`:254-271`（`passesTreeFilter`，含 pi 的"无文本助手不显示"规则）。
- **会话列表搜索/排序/命名过滤**：`ui/screens/SessionsScreen.kt:90`（`query`）、`:104-114`（按显示名/cwd/文件名匹配 + `byName` 排序切换 + 只看已命名）、`:133-134`、`:177`（按 cwd 分组）。
- pi 依据（**都是 TUI 行为；RPC 没有搜索命令**）：`modes/interactive/components/tree-selector.ts:366-378`（五种筛选）、`docs/sessions.md:43-46`（选择器控件：输入搜索、Ctrl+S 排序、Ctrl+N 只看已命名）、`packages/tui/src/tui-alt-screen.ts:171-273`（`searchMatchStyle`/`searchCurrentMatchStyle`）+ `modes/interactive/theme/theme-json.ts:40`（`searchMatchBg` 定义）。**这一条属于"pi 有、RPC 够不着、我们用 App 侧实现对齐"，不是"pi 没有"。**

### I9. 没有删除会话，也没有 `pi -c` 的自动续接（MISSING-GUI）—— **已解决（复核于 af2d675 / 7885599）**
`boot()`→`attach()` **从不切会话**（`PiEngineHost.kt:231-233` 没有 `--continue`）。

**状态（已解决）**：两条分开处置，能力归属不同。
- 删除会话：**pi 没有协议对应物**（TUI 是 Ctrl+D 后确认，可用时走 `trash`）。由 `af2d675` 落地：
  符号 `PiSessionViewModel.deleteSession` ← `SessionsScreen` 的 `SessionRow.combinedClickable(onLongClick)`，
  确认对话框的确认按钮对**当前会话**禁用（引擎正在写该文件，不能 unlink 它下面的 inode），
  文案明说删除后无法从 App 里恢复（我们没有 trash）。
  本轮另加了一句可发现性提示（`applied (uncommitted)`）：列表上方写明"长按一行可删除该会话；
  当前会话要切换后才能删除"，因为入口存在但没人找得到等同于不存在。
- 自动续接：pi 有 `-c/--continue`（`cli/args.ts:100-101`），RPC 无对应物。由 `7885599` 落地：
  符号 `app.sessions.resumeLast`（开关，默认关闭）+ `PiSessionViewModel.maybeResumeLastSession()`
  —— `attach` 之后用现成的 `switch_session` 切到最近会话，每进程只试一次。

### I10. `app.device.*` 权限开关是**第二份、且可能矛盾的**授权真相（DEFECT）
它们在设置目录里声明，但**没有任何代码读它们**——真正的强制在 `DeviceCapabilityStore` 的 SharedPreferences。
→ 设置页会显示一套**和「设备能力」页不一致**的开关，**两边可以互相矛盾**；而 `SettingsHome.kt:80-82` 甚至声称没有这类键。
**这一条要优先修**：授权只能有一个真相来源。
**状态（已处置）**：7 条 `app.device.*` 与 `G_DEVICE` 组已从注册表删除，`SettingsHome.kt` 写明 `DeviceCapabilityStore` 是唯一权威。
删掉第二份开关**不等于**那一页就说真话了：同一页上仍有 6 处"界面声称 vs 真实行为"不一致（错的 Shell 后端标签、不存在的相机权限按钮、
API<30 的截屏、默认开的基础组缺通知权限、永不刷新的审批卡、静默失败的设置页跳转），见 **§C3.1**，`applied (uncommitted)`。

### I11. 环境类能力缺失（CLI-ONLY / MISSING）—— **已完成（applied (uncommitted)；`tools/typecheck.sh` 0 error）**
离线模式、`--system-prompt`、`PI_CACHE_RETENTION=long` —— **环境变量映射是写死的**（`PiEngineHost.kt:250-258`）。

**状态：三项已成设置行，并真的传进了 `PiLaunchOptions`。**
- pi 依据（**"pi 有"的真债**）：`cli/args.ts:110`（`--system-prompt`）、`:112`（`--append-system-prompt`）、`:318`（`--offline`，帮助文本明写 "same as PI_OFFLINE=1"）、`:433`（`PI_OFFLINE` 环境变量）；`packages/ai/src/api/anthropic-messages.ts:57`（`PI_CACHE_RETENTION=long` 的消费点）、`core/model-runtime.ts:196`（读 `PI_OFFLINE`）。**键是我们自己的**（`app.runtime.*`）：pi 的 `Settings` 接口里没有 `offline`/`systemPrompt`/`cacheRetention`（`core/settings-manager.ts:106-158` 逐键看过），这三项只以 CLI/env 形式存在 —— 所以是"把 pi 的能力接到手机界面上"，不是我们造功能。
- 界面（`ui/settings/PiSettingsRegistry.kt`，新 section「进程」）：`app.runtime.offline`（Switch）、`app.runtime.systemPrompt`（Text）、`app.runtime.cacheRetention`（Value：默认/长保留），三行 `effective = EffectiveKind.Reload`（界面标签「需重载」），**说明里写明"改动在重启引擎后生效"**；同 section 新增动作行 `app.runtime.restartEngine`（"重启引擎"），由 `PiSettingsStack.hostActions` 接住 → 确认对话框（写明会终止回合、磁盘会话不丢）→ 调用重启并显示引擎自己的回答。
- 接线：`ui/PiSessionViewModel.launchOptions()` 从 store 读这三个键；`boot()` 传 `launch = launchOptions()`；`restartEngine()` 传 `launch = launchOptions()`（**不重放旧值**）；`engine/PiEngineHost.restart(..., launch: PiLaunchOptions? = null, ...)` 新增该参数（null = 重放启动时的配置，旧行为不变），`launch` 放在 `onStep` 之前以免破坏尾随 lambda。
- 为什么不用 `EngineRestartCoordinator`：那台状态机回答的是"有一个**资源**变更等着被拾取"，而这三项是进程配置，只在**新进程**里生效；真正要守的"不打断回合"由引擎自己兜（`PiEngineHost.restart` 在 `Busy` 时拒绝），调用一律 `allowInterrupt = false`。
- **未验证（真机/CI）**：①离线模式是否真的关掉了 pi 的启动联网（`PI_OFFLINE` 的多个 truthiness 判定）；②`PI_CACHE_RETENTION=long` 在有缓存的厂商上确实改变行为；③自定义 system prompt 真的替换掉 pi 自己的（`--system-prompt` 的引号处理已由 `PiLaunchOptions.quote` 覆盖单引号，但没在 guest 里跑过）；④重启对话框的落点在设备上没点过。

### 审查自己标为 UNVERIFIED 的 6 项
设备能力"两份真相"是否已在并发重写中被消除（静态不一致**已确认**；现已消除，见 I10 状态，并追加了界面一致性审计 §C3.1）；`/share` 在 Android 上是否可行；终端专属行在真机是否可用（C2）；`hideThinkingBlock` 是否存在 grep 看不见的读取；"无消费者"全量扫描（它逐键验证了 13 个）；`SessionTreeScreen` 是否新增了非 fork 动作。**§2 的结论都不依赖这些未验证项。**

---

## J. RPC 代理交付记录（`rpc/**` + `engine/**`）

### J.1 编译与测试证据

| 命令 | 结果 |
|---|---|
| `K2JVMCompiler -no-stdlib -jvm-target 17 -classpath <kotlinc+serialization+coroutines> -d <out> $(find rpc/src/main/kotlin -name '*.kt')`（与 `tools/typecheck.sh` 同一套 kotlinc/classpath） | **exit 0，0 error，143 个 class** |
| `bash tools/typecheck.sh` | **`:app` 已通过编译验证（终局，2026-09-11）。** `typecheck: OK (:rpc + :app, cross-module boundary reproduced) — 0 error diagnostics, :rpc 0 / :app 0`，`EXIT=0`，`grep -cE '\.kt:[0-9]+:[0-9]+: error:'` = **0**；依据日志 `/tmp/tc-verify2.log`。可信度有反证：跑前删掉旧 `rpc.jar`，跑后它是 **306,124 字节 / 143 个 class**（不是空 jar），日志里 `CANNOT RUN` = 0，且本轮包含新增的 508 行 `PiPackagesHost.kt` 与 `import kotlinx.coroutines.launch`。<br>**此前两次 `OK` 是假 OK**（`/tmp/typecheck.log` 15:35:21、`/tmp/tc2.log` 11:17：日志里没有任何编译器诊断）。根因两个，均已修复：`pipefail` + `echo \| grep -q` 的 SIGPIPE 假 OK；不检查 `java` 退出码导致空 `rpc.jar` 引发 **946 条**假 `unresolved reference 'rpc'`。判定行现在自带错误计数，空 jar 会直接 `CANNOT RUN`。 |
| 最小闭包编译（`runtime/*.kt` + `bridge/*.kt` + `session/*.kt` + 三个 `engine/` 文件；classpath = kotlinc + coroutines + serialization + android.jar + `out-26048/rpc`） | 98 个 `error:` **全部落在 `bridge/**`**（最小 classpath 缺 AndroidX 的 `ContextCompat` 等，是 classpath 缺口而非代码缺陷）；**`engine/` 0 error** —— `PiEngineHost.kt` / `PiEngineSession.kt` / `PiEngineApi.kt` 编译干净 |
| `gradle :rpc:test --console=plain --no-daemon` | 最近一次**完成**的运行：**190 tests / 0 failures / 0 errors / 0 skipped**（`rpc/build/test-results/test/*.xml`，mtime 18:36:14）。本轮新增 10 个用例后期望 **200**，**待 `8ecfdf0b` 跑一次才有证据**（按资源规矩 RPC 代理不跑 gradle） |
| 单测直跑（JUnitCore，编译产物） | 曾 `OK (198 tests)`；`FidelityFixesTest` 现有 33 个 `@Test` |

**`pi --mode rpc` 在这台机器上起不来**（连 `--version` 都超时，stdin 关闭也一样），所以"本地抓 RPC stdout"不可行。等价的替代做法（已被 `contentIndex` 一条证实有效）：直接用 Node 驱动 **pi 真实的 provider 适配器**
`@earendil-works/pi-ai/dist/api/anthropic-messages.js` 的 `stream()`，喂一个 mock Anthropic SSE（text(0) → tool_use(1) → text(2)）。那是给 `contentIndex` 赋值、并被 `modes/json-event.ts` 原样序列化到线上的那一层。

### J.2 已交付项（全部带证据）

| 项 | 结论 / 证据 |
|---|---|
| `ExtensionError.extensionPath`/`event` | 已加字段并解析：`rpc/src/main/kotlin/app/pi/rpc/Events.kt:322`（`{extensionPath, event, error}`，文本兼容 `message`/`error`） |
| `MessageEnd.customType`/`display` | 已加字段；实时 `role:"custom"` 现在走 `onHookMessage`：`Events.kt:140-146`，`Transcript.kt:663` |
| `model_select` 死分支 | 已删（`onEntry` 只留 `model_change`；`ENTRY_EVENT_TYPES` 同步）。pi 只把它发给扩展（`_emitModelSelect` → `_extensionRunner.emit`），条目联合里也没有它 |
| `AssistantDelta.Unknown` | 注释与行为一致：投影为按 kind 去重的 `Notice`（`Transcript.kt:943-961`） |
| `system_prompt`/`error` 死分支 | 已删；`Transcript.kt:1258-1264` 留了"pi 没有这两种条目类型"的说明，行由 `onSystemPrompt()`/`onError()` 产生 |
| `turnIndex`（两审相反） | **裁决：RPC 线上没有。** `packages/agent/src/types.ts:436-437` 的 `AgentEvent` 无 index；`core/extensions/types.ts` 的 `TurnStartEvent`/`TurnEndEvent` 有，但 `_handleAgentEvent` 只把它交给 `_emitExtensionEvent`（扩展处理器），随后 `_emit(event)` 转发的是**原始 AgentEvent**，`toJsonEvent` 对非 `message_update` 原样透传。注释已写全链条：`Events.kt:76-119` |
| `contentIndex` | **真实抓包证明会错序，已修。** pi 真实 anthropic 适配器对 text(0)→tool_use(1)→text(2) 发出 `text_start@0, toolcall_start@1, text_start@2`；reducer 现在按 `contentIndex` 建行索引表（`Transcript.kt:594-607`），不再"取最后一个 streaming 文本行"。同一抓包顺带证实 `done` 事件是 `{"type":"done","reason":"toolUse"}`（`reason`，不是 `stopReason`） |
| 未知字段宽容 | `PiResponses` 的读者只取具名键、从不校验键集；策略写在 KDoc：未知字段忽略、缺省必需字段不伪造也不抛异常；测试 `unknown extra fields never break a reader` / `absent required fields degrade instead of throwing` |
| I11 引擎侧（`gap-disposition.md` #11/#12/#64/#65/#66） | `rpc/.../PiLaunchOptions.kt:31,47,61` 把 `PI_OFFLINE` / `PI_CACHE_RETENTION=long` / `--system-prompt` / `--append-system-prompt` 映射出来；`engine/PiEngineHost.kt:192,215,275,316,399` 应用并让 `restart()` 复用。**残留**：没有 settings 行传值，`boot()` 唯一调用点在 `ui/PiSessionViewModel.kt:535`（都不在 `rpc/**`+`engine/**`） |
| rendering-review P5 / F3+F2 | **归约器侧已完成（未提交，RPC 代理），两个触发条件已按裁决与 pi 一比一**：`Transcript.kt:1182-1229` `failTurn(reason, errorMessage, hasToolCalls, key = null)`。(1) **收卡**只在 `aborted`/`error`（`Transcript.kt:1214`），pi 依据 `modes/interactive/interactive-mode.ts:3294`（实时）/`:3735`（重放）；`length` 走 else（`:3305-3315`）**不收卡**。(2) **追加行**只在 `length`，或 `aborted`/`error` 且本条消息**没有**工具调用（`Transcript.kt:1191`），pi 依据 `packages/tui/src/components/assistant-message.ts:180`（`hasToolCalls`）+`:187`；该标志从 `message.content` 解析到事件上（`Events.kt:167`，判定函数 `Events.kt:688`，解析点 `Events.kt:430`）。(3) `errorMessage === "Request was aborted"` 的特判照抄（`Transcript.kt:1196` 对应 `assistant-message.ts:190`）。文案为中文（`length`→"回复被令牌上限截断"；`aborted`→pi 的 `errorMessage`，该特判时用"回合已中止"；`error`→pi 的 `errorMessage`，缺省 `Unknown error`）。**重放**：同一 `failTurn` 由持久化 assistant 条目驱动——`onHistoryAssistant` 读条目自带 `stopReason`/`errorMessage`（`Transcript.kt:1525`；pi 依据 `packages/ai/src/types.ts:440,443`，重放对照 `interactive-mode.ts:3735-3746`），所以 `seedFromHistory` 重开会话与实时流同源同判定；实时入口 `Transcript.kt:689`；历史行 key 可重放（`Transcript.kt:648`）。**app 侧重复行已删（applied, uncommitted，未上 CI）**：`ui/PiSessionViewModel.kt` 的 `stopReasonRow()`/`liveTurnIssue`（字段、`MessageEnd` 投影、`MessageStart` 复位、三处 `= null`、helper 本身）全部删除，归约器那行成为唯一一行；`syncTranscript` 的 `interrupted` 投影保留为 `projectRow`/`applyChanged`，作用域写在 KDoc 里（只碰 `ToolStatus.Pending` 的行，`output` 仅在为空时填，因此不会覆盖归约器写好的结果），可达路径是 pi 未建模的那种（引擎被杀/重启） |
| rendering-review F24/F23/F16/F18 | `TextEnd.content` 覆盖累加文本（`Transcript.kt:883`）、`ToolCallEnd` 补齐工具名/参数（`:918`）、`details.truncation` → `outputTruncated`（`:467`）、工具结果图片保留为 `List<PiImage>`（`Events.kt:188`，ToolCall 字段 `Transcript.kt:78`）、压缩 `usage` 落到 `CompactionMarker`（`Transcript.kt:129`），并在 `lastUsage`（`:591`）暴露实时 usage（F10） |
| F5（跨文件） | **engine 侧无需改动**：`PiEngineSession.handle()` 已经 `transcript.onEvent(event)` 投影 `entry_appended`，且 `_changes` 对每个非 response 事件自增。重复投影在 `ui/PiSessionViewModel.kt:666-675`（`e75821d0` 的 P2）。engine 侧已加注释说明"由本层投影一次"，避免两边各改一半 |

---

## K. 装包功能（install / list / remove）的可达性与 guest 链路 —— 本次实测

**一句话**：npm 链路已实测可用（同一版 pi、同一条命令形状、同样的落盘形状），**git 链路极可能不可用**（运行时里没有 git，而 UI 在宣传 `git:` 源），而**整块功能此前没有任何 UI 入口**（本次补了一个 host，settings 侧还差 3 行）。

### K1. 实测证据（开发容器，pi 0.85.1 + node 24.19.0，`npm:` 源）

| 命令 | 结果 |
|---|---|
| `node <pi>/dist/cli.js install 'npm:is-number@7.0.0' --approve`（stdin 关掉，非 TTY） | **rc=0**，输出 `Installing npm:is-number@7.0.0...` → `added 1 package, and audited 2 packages` → `Installed npm:is-number@7.0.0`。**没有卡在信任询问**（与 B5 的判断一致：非 TTY 下 `hasUI=false`，信任闸门确定性拒绝而不是弹窗） |
| 落盘 | `<agentDir>/settings.json` → `{"packages":["npm:is-number@7.0.0"]}`；`<agentDir>/npm/node_modules/is-number` 存在，`<agentDir>/npm/package-lock.json` 生成 |
| `node <pi>/dist/cli.js list` | **rc=0**，输出正是 `PiListOutput.parse` 期待的形状：`User packages:` / `  npm:is-number@7.0.0` / `    <agentDir>/npm/node_modules/is-number` |

**一条更正（重要）**：较早的一次记录说「`pi list` 挂起、无输出」。那**不是 pi 的行为**，而是当时这台容器的资源饥饿（同一时段 5 个代理并行编译、出现 `fork: Function not implemented`）。同样的命令现在 **<2 秒返回**。不要把那次挂起写进任何结论。

### K2. `git:` 源 —— **已解决：https 可用，ssh 形式仍不可用**（applied, uncommitted，未上 CI）

**状态**：git 已经打进运行时。`runtime.lock.json` 新增 16 个 artifact（`git` 2.43.0-1ubuntu7.3、`caCertificates`、14 个库包），`tools/fetch-runtime.mjs` 在构建期把 deb 重打包成 `git.tgz`，`RuntimeProvisioner.installGit()` 把它解到 rootfs，`ProotCommand.environment` 用 `GIT_SSL_CAINFO`/`SSL_CERT_FILE` 指向随包安装的 CA bundle。

**实测数字（构建机，非真机）**：

| 项 | 值 |
|---|---|
| `git.tgz` | **7.26 MiB**（gzip -9；真的组装了一遍并复验，不是估算） |
| 依赖闭包 | 读每个 ELF 的 `DT_NEEDED` 传递闭包：**33** 个 soname → **18** 个 pinned base 已有、**16** 个新增、**0** 未解析 |
| CA bundle | 构建期把 `ca-certificates` 的 **121** 个 Mozilla `*.crt` 拼成，182,140 B（deb 里**没有** `ca-certificates.crt`，它是 postinst 生成的） |
| `/usr/bin/git` | 软链到 `../lib/git-core/git`（deb 里那是**第二份 4 MB 副本**，不装它以省 ~4 MB） |

**一处更正**：`RuntimeProvisioner.installTool` 的旧 KDoc 写"git-remote-http resolves 的 31 个共享库里有 15 个 base 没有"。上表是重新按传递闭包算的，数字是 **33 / 18 / 16**。旧数字不要再用。

**明确的能力边界（这是结论，不是待办）**：

- **可用**：`https://` 远端（`clone`/`fetch`/`pull`/`push`）；全部本地命令（init/status/diff/log/commit/add/branch/checkout/merge/rebase/stash…）。所以模型自己可以跑 `git status`/`diff`/`log`/`commit`，pi 读 `.git/HEAD` 取分支名也不再是"唯一能做的事"。
- **不可用，且不是 bug**：`git@host:path` 与 `ssh://`。它们需要 **ssh 客户端 + 密钥/agent**，运行时不提供：没有 `ssh` 二进制、没有 `~/.ssh`、没有 agent forwarding，也无法应答 host-key 或口令提示。注意闭包里的 `libssh.so.4` 是 **libcurl 的 `sftp://` scheme**，不是 git 的 ssh transport——这两件事极容易被当成"那 ssh 应该能用"。失败形态是 `cannot run ssh: No such file or directory`。
- **不可用**：`git commit` 在设置 `user.name`/`user.email` 之前会拒绝执行；载荷**不**伪造身份（编造一个作者比一句清楚的 "Please tell me who you are" 更坏）。
- **不含**：Ubuntu 分到**别的包**里的子命令——`git svn`（`git-svn`）、`git send-email`（`git-email`）、`git gui`/`gitk`（`git-gui`/`gitk`）、`git instaweb`（无 web server）。载荷只装 `git` 包本身。

**收尾条件（UI 半边，不在 `runtime/**` 范围）**：`packages/PackageStrings.kt` 的 `SPEC_HINT` 现在只写 npm 与绝对路径，**应收复为同时列出 `git:github.com/user/repo@v1`**——能力已经有了，界面不该再少列一种。**不要**再加任何解释文字：界面里出现文档路径/文件名/章节号（以及内部设计解释）已被明令禁止，那句旧的"这个运行时里没有 git（docs/known-gaps.md §K2）"正是因此被整个删除的。

**做这件事时顺带发现并修掉的一条真实断链：`fd`/`rg` 在 agent 目录绑定之后悬空**（同一个 `RuntimeProvisioner` 问题域）

- 症状：`/usr/local/bin/{rg,fd}` 是软链到 guest `/root/.pi/agent/bin/<tool>`，而那个 guest 路径落在哪个 host 目录由**谁启动 proot**决定——`PiEngineHost`（引擎）与 `GuestCommand`（装包）把 `paths.agentDir` 绑上去，`PtyLauncher`（终端，活的）**不绑**。而 `installTool` 只写 rootfs 副本（`<rootfs>/root/.pi/agent/bin/`）：绑定一生效，那份就被遮蔽。
- 时序才是致命处：`PiEngineHost.kt:223` 的 `migrateGuestAgentDir()` 在 `:227` 的 `ensureReady()` **之前**跑；首启时 `<rootfs>/root/.pi/agent` 还不存在，于是它只返回"无需迁移"、**不写 marker**，随后 provisioning 才把 `bin/` 写进 rootfs。**所以第一次引擎启动时 rg/fd 就是悬空软链**：`@` 文件提及永远没有候选（与 pi 缺 fd 时的降级行为一模一样，看不出来），pi 自己的 `find` 工具（`runtime.lock.json` 里 fd 的理由就写着它）也坏掉。第二次启动迁移把 `bin/` rename 到 agentDir 才**碰巧**修好，而 wipe 之后又只能靠下一次 rename 的运气。
- 修法：两份都写——`PiPaths.agentBinDir()`（绑定源 `<files>/pi/.pi/agent/bin`）与 `PiPaths.rootfsAgentBinDir()`（rootfs 副本）；`ensureToolsVisible()` 在 `ensureReady` 的"stamp 未变、直接返回"路径上重放一次，从存活的那份补回缺失的那份。这两个目录**互不包含**，所以任何"只装一份"的写法必然让三条启动路径中的一条悬空。
- 自证：`app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt`（13 项断言，含"两个目录互不包含"）。**`tools/run-app-pure-checks.sh` 尚未注册它**，注册之前它在 CI 里等于不存在——补丁见交付报告。

**仍需真机确认（构建机证明不了）**：`git clone https://…` 是否真能过证书校验（CA 路径被显式钉在 `GIT_SSL_CAINFO`/`SSL_CERT_FILE`，因为 libcurl 编译进的是**目录** `/etc/ssl/certs` 的 `c_rehash` 形式，而载荷只装了拼接好的单文件 bundle、没有 hashed 软链）；以及 proot `--link2symlink` 之下 `git commit`/`gc` 的可靠性——**下结论前先量**。


### K3. 网络与路径

- **解析**：`RuntimeProvisioner.kt:161-173` 写死 `/etc/resolv.conf`（223.5.5.5 / 8.8.8.8 / 1.1.1.1），所以"guest 里没有解析器"不是问题。
- **registry**：npm 要连 `registry.npmjs.org`；设计 §25 已指出国内需要换镜像（`registry.npmmirror.com`）。这是真机网络问题，代码层不要假设它通。
- **路径**：`/opt/node/bin/node` 与 `/opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js` 由 `RuntimeProvisioner.extractNode`（`:109-126`）与 `extractEngine`（`:189-206`）创建，`AgentLayout.guestEngineCli`/`guestNode` 引用的就是这两个值；`PiPackageService.commandLine` 用的也是它们。**容器实测只能证明命令形状与 pi 子命令，路径本身仍需真机**（`PiPackageService` 已用 `Done.NotReady` 在缺失时明说，不会伪装成功）。

### K4. UI 可达性（本次新增，settings 侧还差 3 行）

- 此前 `PiPackagesScreen` / `PiProjectTrustPrompt` **没有任何调用方**（全仓 grep 只有 `packages/**` 自身），所以"包管理"对用户等于不存在。
- 本次新增 `app/src/main/kotlin/app/pi/packages/PiPackagesHost.kt`：`PiPackagesHost`（可挂载的整屏，含标题栏与返回）、`PiPackagesController`（状态 + guest 调用 → `PiPackagesUiState`）、`PiPackagesEntryRow`（入口行）。restart 走 `EngineRestartCoordinator`（未接引擎时不假装能重启），信任决定只经 `PiProjectTrustPrompt` 写入。
- settings 侧最小 patch 见交付报告（`PiSettingsStack.kt` + `SettingsHome.kt`，共 3 处）。
- 次要：`PiSettingsRegistry.kt:759` 的 `packages` 行让用户**直接编辑 `settings.json` 的数组**，而 `PiPackageService` 的 KDoc 已论证"只改数组 = 配置一个磁盘上不存在的包"（B5）。建议把该行改成只读展示并指向包管理页。

---

### K5. 装包命令与引擎的 agent 目录绑定不一致 —— **已修（applied, uncommitted，未上 CI）**

**症状（修复前）**：界面里 `pi install 'npm:foo'` 打印 `Installed npm:foo`、退出码 0、`pi list` 里也出现，但引擎什么都不加载；运行期更新（`RuntimeProvisioner.wipe()`）之后这些包连同 rootfs 一起消失。**没有错误、没有警告**——"静默无效"的形状。

**根因（两个 proot 各自绑各自的）**：
- 引擎：`PiEngineHost.kt:285-294` 的 `extraBinds` 是 workspace **+** `paths.agentDir.absolutePath to guestAgentDir`，并在 `:302-303` 钉住 `PI_CODING_AGENT_DIR`（`guestAgentDir` 定义在 `:170`）。
- 包命令：`GuestCommand` 的 `ProotCommand.build` 只传 `workspaceBind()`（旧 `:98-106`），也**不设** `PI_CODING_AGENT_DIR`。

proot 的 bind 是**每次调用**的事。于是同一条 host 路径在引擎里是 `/root/.pi/agent`，在包命令里是 rootfs 自带的那个 `/root/.pi/agent`：安装把 `settings.json` 与 `npm/` 写进了一份，引擎读的是另一份。pi 自己看不出这个差别（它只认自己的 agentDir，两边都"对"），只能由 App 保证一致。

**修法**：
- `packages/AgentLayout.kt:54` `guestAgentDir` 改用契约常量；`:110` `agentDirBind()` 产出与引擎**同一形状**的 bind；`:118` `ensureAgentMirrorDir()` 先建目录（proot 拒绝绑定不存在的 host 路径，而引擎没 boot 过时该目录还不存在）。
- `packages/GuestCommand.kt:198-206` `bindList()` 传 workspace + agent dir（引擎同序），并用 `PiAgentDirContract.bindsAgentDir` **断言**一致；`:219-222` `agentDirEnv()` 补上与引擎相同的两个环境变量。
- 自证装置：`packages/PiPackageModel.kt:137-160` 的 `PiAgentDirContract`（纯逻辑），覆盖在 `PackagesPureLogicCheck.kt:443-487`（引擎那对 bind 通过；只绑 workspace、从 rootfs 绑、guest 路径写错都判失败；顺序无关）。

**同一前提（"agent dir 没被绑定"）留下的连带修正**：
1. `packages/TrustRepository.kt:57-71`：`engineFile`（绑定源）改为**权威**，`rootfsFile` 降级为兼容写入；`read()` 改为先读 `engineFile`。旧顺序有两个真实后果：**引擎那一份损坏永远不会被报出来**（而 pi 启动时会对着它抛错），以及 **`withLock` 的锁加在 pi 不加锁的那个文件旁**（`:370`，互斥等于没有）。`repairInvalidStore` 的归档对象也跟着改。
2. `packages/PiCredentialService.kt:69`：改为写绑定源（详见 §E9 那一行）。
3. `publishIntoRootfs()`（`TrustRepository.kt:247`）现在**没有任何调用方**，绑定生效后也不再需要；保留但已注明，建议由 owner 删除。

**同一批审计发现、已单独修掉的界面问题**（都不在 K5 这条绑定本身）：`EngineRestartCoordinator` 拒绝重启后卡在 `Restarting`（`ExtensionLifecycle.restartRefused`）；`pi list` 未执行却显示"没有包"（`Listing.notReady`）；`npm:foo@v1.2.3` 被判成"可被 update 移动"（`PiPackageSource.isExactNpmVersion` 对齐 node-semver strict FULL）；`SPEC_HINT` 曾宣传 `git:` 源（已去掉；期间加过一句"当前运行时没有 git"的用户可见说明，**已按要求整个删除**，见 §K2 与 §H 第 7 条）。**全部为 applied (uncommitted)，未上 CI。**

**未验证**：本机不编译、不跑 device（规矩）；`bindsAgentDir` 的断言只在纯逻辑层被覆盖；"proot 能绑定刚 `mkdirs` 出来的目录"只有源码级把握。**真机验证点**：装一个 npm 包 → 重启引擎 → 该包的工具/资源真的出现（修复前不会）。

### K6. guest 里两条与 DNS 有关的环境限制（A2 / A3，已核实，不是待办）

真机排查中确认的两条限制。写在这里是因为它们的**症状都指向错误的方向**：看起来像我们的 `resolv.conf` 写错了，实际都不是。

#### A2. 静态链接的 glibc 二进制**不能**做域名解析

- **现象**：同一个域名、同一时刻，动态链接的 Node 能解析，静态链接的二进制（例如静态 ffmpeg）失败，`getaddrinfo` 返回 `EAI_SYSTEM`；连 `/etc/hosts` 里写死的条目也读不到。
- **根因**：glibc 的 `getaddrinfo` 通过 `dlopen` 加载 `libnss_dns.so.2` 完成 DNS 查询。静态链接把这条路切断，于是不是"查不到"，而是**没有任何解析器**。
- **与什么无关**：与我们的 `resolv.conf`（`RuntimeProvisioner.kt:161-173`）无关，与 `/etc/hosts` 也无关。不要去改它们——改了不会有任何变化。
- **我们改不了什么**：不能把 `libnss_*` 塞进 rootfs 去救静态程序。NSS 模块与 glibc **版本必须完全一致**才可能成功，做不到就是做不到。
- **能做什么**：用 apt 装的**动态版**，或 **musl 静态版**（musl 不依赖 NSS）。

#### A3. 境内 DNS 投毒：受影响的是被墙域名，不是"网络坏了"

- **现象**：某些域名解析到 `face:b00c:*` 这类地址。那是 Facebook 的 IPv6 前缀，是**投毒指纹**，不是真实解析结果。
- **根因**：链路上的 DNS 应答被伪造。
- **我们改不了什么**（三条硬约束，逐条挡死"本地 DoH 代理"这条路）：
  1. glibc 的 stub resolver **不支持 DoH/DoT**；
  2. `resolv.conf` 的 `nameserver` 只能写 **IP，不能带端口**，所以没法把它指到一个本地代理端口；
  3. Android 应用**不能绑定 1024 以下的端口**（没有 `CAP_NET_BIND_SERVICE`），所以也没法在 53 端口起一个本地解析器。
- **边界（重要，别扩大解释）**：B 站、GitHub、npm、PyPI 都正常，**只有被墙域名受影响**。因此不要把这类失败归因到运行时、npm 镜像或我们的配置上。
- **能做什么**：换可达的解析路径（例如应用可用的 DoH 客户端放在 App 侧、或在用户自己的网络层解决），而不是在 guest 里折腾 `resolv.conf`。

## L. 许可证合规处置（**分发义务**，不是 pi 行为）

> 这一节的规格来源不是 pi，而是**我们打包分发这个事实**。判据分两处：
> ① pi 自己怎么做署名 —— 这是 pi 规格，引 `file:line`；
> ② 分发第三方程序产生的义务 —— 这是 Android/APK 侧的事实，**不去 pi 里找依据**。
> 三态写法：`pi 有（file:line）` / `pi 无对应物（App 自己的决定或法定义务）` / `pi 有但我们够不着`。
> 状态：**applied (uncommitted)**，未上 CI，未上真机。

### L1. 我们到底分发了什么（逐条来自 `runtime.lock.json`，23 个 artifact 全部 SHA-256 校验通过）

校验方式：按 lock 里的 URL 重新下载，逐个比对 `sha256`，23/23 一致，再从**校验过的字节**里读版权文件。

| 进包位置 | 组件 | 许可证（读自包内 `copyright`） | 包内是否带许可全文 |
|---|---|---|---|
| `jniLibs/arm64-v8a/`（伪装成 `lib*.so`） | proot 5.1.107.92 | **GPL-2.0**（Termux 打包元数据） | **否**（Termux deb 不带 `usr/share/doc`） |
| 同上 | libtalloc 2.4.3 | **GPL-3.0**（Termux 元数据；上游 talloc 为 LGPL-3.0） | **否** |
| 同上 | libandroid-shmem 0.7 | **BSD-3-Clause**（Termux 元数据 + 上游 LICENSE） | **否** |
| `assets/runtime/ubuntu-base.tgz` | Ubuntu 24.04.3 基础系统，**91 个软件包** | 各自许可，共 88 份 `copyright`（GPL-2+/GPL-3+/LGPL-2.1+/BSD/MIT/Expat…） | **是**（88 份随载荷走）+ 18 份 `/usr/share/common-licenses` |
| `assets/runtime/git.tgz` | git 2.43.0 + **16 个库包** + CA bundle | git **GPL-2.0**；闭包内混合（LGPL-2.1+、MIT、BSD、Expat、OpenLDAP-2.8、curl…） | **否**（重建时只取 `usr/lib/git-core`、`usr/lib/aarch64-linux-gnu`、`usr/share/git-core`，**没取 `usr/share/doc`**） |
| 同上 | ca-certificates 20260601 | GPL-2.0+（打包）+ **MPL-2.0**（`certdata.txt`） | **否**（只取生成的 bundle） |
| `assets/runtime/node.tgz` | Node.js 24.19.0 | MIT | 是（载荷内 `LICENSE`） |
| `assets/runtime/ripgrep.tgz` | ripgrep 15.2.0 | Unlicense 或 MIT | 是（`UNLICENSE`/`LICENSE-MIT`/`COPYING`） |
| `assets/runtime/fd.tgz` | fd 10.2.0 | MIT 或 Apache-2.0 | 是（`LICENSE-MIT`/`LICENSE-APACHE`） |
| `assets/runtime/pi-engine.tgz` | pi 0.85.1 + 143 个依赖 | pi **MIT**；依赖 83 MIT / 36 Apache-2.0 / 13 BSD-3-Clause / 7 ISC / 2 BlueOak-1.0.0 / 1 Unlicense / 1 0BSD —— **全部宽松，无 copyleft** | 依赖各自包内（npm 保留），未复核 |
| 编译进 dex | termlib 0.0.13（含 libvterm） | Apache-2.0；libvterm **MIT** | 否 |
| 编译进 dex | Shizuku API 13.1.5 | MIT | 否 |
| 编译进 dex | AndroidX/Compose、Kotlin/kotlinx、markdown 渲染栈 | Apache-2.0（逐个 POM 核实） | 否 |

**由此得到的缺口（精确）**：git 那 17 个 deb、ca-certificates、以及 proot/libtalloc/libandroid-shmem 的许可全文**不在 APK 里**。Ubuntu 基础系统、node、ripgrep、fd 的许可是在载荷里的，但用户看不到。

### L2. pi 自己怎么做署名（**这是 pi 规格**）

| pi 行为 | 依据 | 结论 |
|---|---|---|
| pi 以 MIT 授权 | `/root/pi-src/LICENSE:1-3`（"MIT License / Copyright (c) 2025 Mario Zechner"） | pi 有 —— 它有 LICENSE 文件 |
| npm 包的许可声明 | `/root/pi-src/packages/coding-agent/package.json:98`（`"license": "MIT"`） | pi 有 —— 声明在 `package.json`，npm 会把它带进安装后的包 |
| 依赖树的许可声明 | `packages/coding-agent/npm-shrinkwrap.json`：143 个依赖条目**全部**带 `license` 字段（MIT 83、Apache-2.0 36、BSD-3-Clause 13、ISC 7、BlueOak-1.0.0 2、Unlicense 1、0BSD 1） | pi 有 —— 每个依赖各自声明 |
| **有没有"第三方声明"机制** | 全仓库 grep `third-party`/`thirdparty`（`packages/*/src`）：**仅 1 处命中**，且是计费提示 `modes/interactive/interactive-mode.ts:254`，与许可无关 | **pi 无对应物** |
| **有没有许可界面 / CLI 输出版权** | `src/cli.ts` / `src/cli/` / `src/main.ts` grep `licen[cs]e`：**零命中**；交互设置菜单里也没有许可项（`modes/interactive/` grep `licen` 零命中） | **pi 无对应物** |
| **有没有 per-package LICENSE** | `find . -maxdepth 3 -name "LICENSE*"`（排除 node_modules）：只有根 `./LICENSE` 一个 | **pi 无对应物** |

**这解释了两件事**：① §I2 把 `app.about.licenses` 从 pi 的设置目录里撤掉是**对的**——pi 确实没有许可界面，把它当 pi 的功能就是编造；② 但"pi 没有"**不等于"我们没有义务"**——义务来自我们打包分发 GPL/LGPL 程序，与 pi 的行为无关。

### L3. 我们的实现（`pi 无对应物`：形态是 App 自己的决定）

| 我们做了什么 | 位置 |
|---|---|
| 从**校验过的上游字节**生成许可资产（不手打、不凭记忆） | `tools/build-license-assets.py`（新） |
| 许可资产（**134 个文件 / 1707 KiB；133 条 manifest**；**107 份 per-package `copyright`**——91 个基础包 + 16 个 Ubuntu deb，一包一行；清单 **35 行**覆盖 `runtime.lock.json` 全部 23 个 artifact + pi 引擎闭包 + JVM 依赖树） | `app/src/main/assets/licenses/**`（在新目录，**不在 `assets/runtime/`**） |
| 声明"这些是未修改的上游发行版 + 逐组件上游地址" | `licenses/about.txt`、`licenses/source-code.txt`（由脚本写入，**是界面上的声明**，不是代码注释） |
| 组件清单（组件/版本/许可证/进包位置/上游地址） | `licenses/component-list.txt` + `licenses/manifest.txt` |
| 许可全文 | `GPL-2.0/LGPL-2.1/LGPL-3.0/GPL-3.0/MPL-2.0/Apache-2.0` 取自载荷的 `/usr/share/common-licenses`；`MIT/BSD-2/BSD-3/ISC/Zlib/curl/X11/OpenLDAP-2.8/BSD-4-Clause-UC/FSFULLR/all-permissive` 从**随包的 `copyright` 的 DEP-5 段落**里抽；`Unlicense` 取自 ripgrep 载荷；`0BSD`/`BlueOak-1.0.0` 按 pin 版本取（tslib 2.8.1 / lru-cache 11.4.0） |
| 每个 Ubuntu 软件包的 `copyright`（版权归属记录），**一个包一份文件**，并对 `usr/share/doc/<pkg>` 是符号链接的包跟随链接 | `licenses/ubuntu-copyright-*.txt`（107 份；其中 3 份在标题里注明「版权文件由 gcc-14-base / libtinfo6 提供」） |
| pi 自己的 MIT 原文（上游 npm 包里**没有**这份文本，见 L5-3） | `licenses/pi-license.txt`，取自上游 `v0.85.1` 标签且 sha256 与本地 0.85.1 源码树一致 |
| 如实列出「声明了许可但没带许可文本」的 pi 依赖 | `licenses/pi-engine-licence-gaps.txt`（13 个包） |
| 界面入口 | `ui/settings/LicensesScreen.kt`（新，`PiLicensesEntryRow` + `LicensesScreen`）；`ui/settings/SettingsHome.kt` 新增 `onOpenLicenses` 与「关于」分区一行；`ui/settings/PiSettingsStack.kt` 新增 `licenses` 分支 + 返回链 + 一行传参。**`PiRoot.kt` 未改**（分支在 stack 内部，默认参数即可） |
| 验证 | `bash tools/typecheck.sh`（**不走 Gradle**）在改动后整树 **0 error**：`:rpc 0 / :app 0`，输出 `typecheck: OK (:rpc + :app, cross-module boundary reproduced)`。它不跑 Compose 编译器插件，所以 composable 调用规则与 `@OptIn` 必要性**未被检查**。`python3 tools/check-nested-comments.py` → `OK (145 Kotlin file(s) scanned)`。 |
| 未改动的文件（澄清） | **`ui/settings/PiSettingsRegistry.kt` 本次没有改。** §I2 的撤行结论是「合规界面应以真实界面回归，而不是当 Action 行放回 pi 的设置目录」，所以只在**设置首页**加了一行入口，没有在 pi 的设置目录里重新登记任何 key。 |

### L4. 三态对照

| 项 | pi 行为（file:line） | 我们的实现 | 判定 |
|---|---|---|---|
| pi 的授权 | `LICENSE:1-3`、`packages/coding-agent/package.json:98` | 组件清单里列 `pi 引擎 0.85.1 MIT` | 一致 |
| 第三方声明机制 | 无（grep 零命中，唯一命中无关） | App 自建 `assets/licenses/**` + `LicensesScreen` | **pi 无对应物**（App 的决定） |
| 许可界面 | 无（CLI/交互菜单 grep 零命中） | 设置首页「关于」→「开源许可」 | **pi 无对应物**（§I2 撤行判断成立） |
| GPL/LGPL 源码提供 | 不适用（pi 不是这些程序的分发方） | `licenses/source-code.txt` 写明未修改 + 逐组件上游地址 + 索取方式 | **pi 无对应物**（法定义务） |
| Ubuntu 软件包版权记录 | 不适用（pi 不是这些程序的分发方） | 随包的 91 个包的版权文本镜像进 assets（107 份，一包一行；3 个经文档符号链接） | **pi 无对应物**（法定义务） |
| pi 引擎自身的 MIT 文本 | **pi 有**：`/root/pi-src/LICENSE:1-3`（MIT, Copyright (c) 2025 Mario Zechner）；声明在 `packages/coding-agent/package.json:98`。但**发布出的 npm 包不带这份文件**（正文只在仓库根），实测 0.85.1 的引擎载荷里没有 LICENSE 文件 | `licenses/pi-license.txt`，取自同版本上游标签，sha256 与本地 0.85.1 源码树逐字节一致 | **pi 有源码树内的文本、发布物缺失 → 由我们补上**（不是 pi 的行为，是我们分发的需要） |
| 许可资产的漂移断言 | 不适用 | `ci.yml` 新增一步：重新生成后要求 `git status` 干净 + 逐包覆盖检查 | **pi 无对应物**（App 的工程决定） |

### L5. 残留风险 / UNVERIFIED（要写清楚，别当成已解决）

1. **【已更正 —— 我上一轮报错了】** 我原先写「`libgcc-s1`、`libncursesw6`、`libstdc++6` 没有 `copyright` 文件，91 个包里就这 3 个」——**这是误报**，根因是只找 `usr/share/doc/<pkg>/copyright`、**没有跟文档目录的符号链接**。Ubuntu 用链接共享同一份版权文件：

   | 包 | `usr/share/doc/<pkg>` | 覆盖它的版权文件 | sha256 |
   |---|---|---|---|
   | libgcc-s1 14.2.0-4ubuntu2~24.04 | → `gcc-14-base` | `gcc-14-base/copyright`（声明 **GPL-3+ 与 GCC Runtime Library Exception 3.1**，例外全文在内） | `20390f8a6f3b1e4d7cb45dd8652dabb259bbef688cbad839bcdb0b9ba7252f79` |
   | libstdc++6 14.2.0-4ubuntu2~24.04 | → `gcc-14-base` | 同上 | 同上 |
   | libncursesw6 6.4+20240113-1ubuntu2 | → `libtinfo6` | `libtinfo6/copyright`（声明 **MIT/X11**、X11、BSD-3-clause，MIT 全文在内） | `7c9d2c8dbee48b6f4bbbffb1f370cc5858de11cc4f5a70c5c622c274917e1c65` |

   **结论：91/91 个基础软件包都有随包许可文本**（88 份自有 + 3 份经文档符号链接指向）。不是靠推断，两条证据链：
   - **载荷内**：`ubuntu-base` 的 tar 里 `usr/share/doc/libgcc-s1 -> gcc-14-base` 等三条链接，与两个目标文件（上面两个 sha256 就是从载荷字节算的）；
   - **独立复核**：`libncursesw6_6.4+20240113-1ubuntu2_arm64.deb` 自身也带 `./usr/share/doc/libncursesw6 -> libtinfo6`（说明这不是 ubuntu-base 裁剪造成的，Ubuntu 就是这么发的）；两个**源码包**的 `debian/copyright` 与载荷内那两份**逐字节相同**：
     - `ncurses_6.4+20240113-1ubuntu2.debian.tar.xz` sha256 `cd2840e65cc833541573616ffe36eae5161c309e62eb6c97c83f08d47e1ebf59`（`https://ports.ubuntu.com/ubuntu-ports/pool/main/n/ncurses/`）
     - `gcc-14_14.2.0-4ubuntu2~24.04.1.debian.tar.xz` sha256 `cfece214c2fb790ef5f3baffb9a53e40618e7ae12d053610b251e94d77d08ade`（`https://ports.ubuntu.com/ubuntu-ports/pool/main/g/gcc-14/`）—— `~24.04` 已被上游更新覆盖，池里只剩 `~24.04.1`；同一源码包，版权文件与载荷内那份一致。

   **已落地的改进（这才是真问题）**：生成器原先按**内容去重**，导致「与兄弟包共用同一份版权文件」的包在清单里**整行消失**——`libgssapi-krb5-2`/`libk5crypto3`/`libkrb5support0` 就是这样丢的。现在**一个包一份文件、一行 manifest**，并跟随文档符号链接，标题注明「版权文件由 X 提供」。清单因此从 78 行涨到 **107 行**（91 基础包 + 16 个 Ubuntu deb），每个包都可检索到。
2. **【已解决】libtalloc 的许可：以上游 LICENSE 为准 = LGPL-3.0。** 上一轮说"两说未消除"，这一轮取到了真实文件：
   - `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz`，sha256 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`；
   - 包内 `LICENSE` 的 sha256 `da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768`，正文第一行即 `GNU LESSER GENERAL PUBLIC LICENSE / Version 3, 29 June 2007` → **LGPL-3.0**；
   - Termux 打包元数据（`termux-packages/packages/libtalloc/build.sh`）写的是 `GPL-3.0`，**与上游不符**；组件清单已改为 LGPL-3.0 并注明这处不一致。
   - 我们随包的 `LGPL-3.0.txt`（取自载荷的 `/usr/share/common-licenses/LGPL-3`，sha256 `e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118`）与上游这份**只差一处 URL**（`https://fsf.org/` vs `http://fsf.org/`），是同一份 LGPL-3.0 正文，因此没有再加一份近乎重复的文件。
   - 顺带说明：Termux 的 deb **不带任何 `usr/share/doc`**，所以这三个 Termux 组件（proot / libtalloc / libandroid-shmem）的许可文本本来就只能靠上游取，不是在包内读到的。
3. **pi 引擎载荷的 npm 许可已实测复核（结论不乐观，但已定位）**。按 `fetch-runtime.mjs` 里 `pi-engine` 那步的原命令在隔离目录跑：
   `npm install --ignore-scripts --omit=dev --omit=optional @earendil-works/pi-coding-agent@0.85.1`（装到 `/tmp`，**没有落进 `app/` 或 `build/`**），再按 **npm 自己的安装清单**（`node_modules/.package-lock.json`）逐包核对：

   - 实际安装 **128** 个包（不是 shrinkwrap 里的 143——`--omit=optional` 会剪掉一批）；
   - **115 个带许可文件，13 个不带**；13 个全都**声明了** `license` 字段，所以是"没带文本"，不是"没有许可证"；
   - 对 115 个带文件者做了**文本 vs 声明**核对（MIT/Apache-2.0/BSD-2/BSD-3/ISC/BlueOak/Unlicense/0BSD 各自的特征串）：**0 处不符**。

   13 个没有文本的：`@earendil-works/pi-coding-agent`、`@earendil-works/chord`、`@earendil-works/pi-agent-core`、`@earendil-works/pi-ai`、`@earendil-works/pi-telemetry`、`@earendil-works/pi-tui`（均 0.85.1，MIT）、`@nodable/entities` 2.1.0、`data-uri-to-buffer` 4.0.1、`standardwebhooks` 1.1.1、`xml-naming` 0.1.0（MIT）、`@aws-sdk/credential-provider-http` 3.972.39、`@aws-sdk/credential-provider-login` 3.972.41、`@aws-sdk/nested-clients` 3.997.9（Apache-2.0）。

   **最要紧的一条：pi 自己就不带 LICENSE 文本。** 原因可查：`packages/coding-agent/package.json:98` 只声明 `license: MIT`，而 MIT 正文只在**仓库根** `LICENSE`，该目录对外发布时不带它 → 引擎载荷里只有字段、没有声明文本，而 MIT 要求保留声明。**处置**：由我们把 pi 的 MIT 原文随包发出，取自与引擎同版本的上游标签
   `https://raw.githubusercontent.com/earendil-works/pi/v0.85.1/LICENSE`，sha256 `0457f5bcec3b3b211605dfb5d1a49042fd638f3686a410fe099c24a25af13c48`——**与 `/root/pi-src/LICENSE`（0.85.1 源码树）逐字节相同**，已存为 `pi-license.txt`；版本号从 `tools/fetch-runtime.mjs` 的 `PI_VERSION` 读，避免两处版本漂移；哈希对不上时生成器**直接报错拒绝出包**。
   另把这 13 个包写成 `pi-engine-licence-gaps.txt` 随包展示——把"没有文本"如实写出来，比让清单看起来完整更重要。

   **这 13 个的"声明在哪"也逐个核了**（`pi-engine-npm-notices.txt`，每项都带实际抓到的 sha256）：
   - 6 个 `@earendil-works/*`：与 pi 同 monorepo，根 LICENSE 即其文本 —— 就是已随包的 `pi-license.txt`；
   - 3 个 `@aws-sdk/*`：其 LICENSE 就是 **Apache-2.0 模板本身、不含逐包版权行**（上游 `aws-sdk-js-v3` 的 LICENSE 与载荷内 `@aws-sdk/core/LICENSE` **逐字节相同**，sha256 `edea91454b811f127fbdea3d86f378f6719bd372ed440abf82b232f6fca06c3d`；同一载荷里 22 个 @aws-sdk 包有 19 个带这个文件）；
   - `@nodable/entities` 2.1.0 → `Copyright (c) 2026 Nodable`（sha256 `750cb3fb…`）、`xml-naming` 0.1.0 → `Copyright (c) 2026 Natural Intelligence`（sha256 `8e75fc0e…`）：**取自上游默认分支，这两个版本的上游标签不存在，未能按版本核对年份**；
   - `standardwebhooks` 1.1.1：上游文件是 Apache-2.0 模板，无版权行（sha256 `c71d239d…`）；
   - `data-uri-to-buffer` 4.0.1：**版权声明取不到** —— 声明的 MIT 与作者（`Nathan Rajlich <nathan@tootallnate.net>`，读自包内 `package.json`）可确认，但上游仓库的 LICENSE 在各分支/路径均 404。这是整份清单里**唯一一个连版权行都拿不到的组件**。
4. **CI 断言已加（`ci.yml` 的 `apk` job，新增一步，未动别人那几条）**——`Verify the licence assets match the pinned runtime`：
   - **漂移**：`python3 tools/build-license-assets.py --fetch-missing` 之后用 `git status --porcelain -- app/src/main/assets/licenses` 必须为空。用 `git status` 而不是 `git diff`，因为新生成的文件还是未跟踪状态，`git diff` 看不见它。
   - **覆盖**：`ubuntu-packages.txt` 里的每个包都必须有 `ubuntu-copyright-<pkg>.txt` 且出现在 manifest 里。这条不是冗余：**重新生成看不出生成器本身写错的那类 bug**（文档符号链接那次的误报，重跑只会重现同一个错误答案），所以要直接对输出断言。本地实测：`91/91`，且在未提交时能正确报出 32 个漂移路径。
   - **为什么必须由 CI 做**：资产是**生成物**，改 `runtime.lock.json` 里任何 URL/版本都不会让构建失败——APK 照样构建、照样安装，而许可清单在**静默描述上一个版本**；设备上看不出来，也没有人会手工 diff 130 个生成文件。靠"记得重跑生成器"等于靠人记得——这正是本仓库为 `runtime-revision.txt` 已经废弃过一次的安排。
5. **未上真机**：界面只在静态层面核对（无法本地编译 Gradle）。`manifest.txt` 的 TSV 解析、`assets.open` 的路径、63 KB 文本的滚动渲染都未在设备上跑过。
6. **`librtmp1` 的库是 LGPL-2.1、其可执行程序是 GPL-2**；我们只分发 `librtmp.so.1`（库），清单按 LGPL-2.1 列。

## M. 设备实测：首次发消息要等约 1 分钟（本轮定位，2026-09-12）

来源：用户录屏 `Screenrecorder-2026-09-12-22-04-24-636.mp4`（88 s，1440×3200，1 fps 逐帧核对）。

### M1. 「引擎已就绪」说的是**进程起来了**，不是 **pi 能干活了**（DEFECT，已修，工作树）

**录屏时间线**（每一条都是帧上读出来的）：

| t | 画面 |
|---|---|
| 1–5 s | AppBar 副标题「就绪」，空态标题「引擎已就绪」 |
| ≈7 s | composer 里是「。哈哈哈」，键盘已收 |
| ≈8 s | 用户气泡出现，**只有时间戳、没有文字**（见 M3） |
| ≈9 s | 同一气泡显示「。哈哈哈」 |
| 9–76 s | 屏幕上只有那一条用户气泡，**pi 没有任何输出** |
| ≈76.7 s | 同时出现 `模型 → deepseek-flash deepseek` 行、permission-gate 的 `session_start` 通知「设备审批已启用：10 个…」 |
| 80–85 s | 「思考 450ms」+ 回复正文 |

**代码层面的机制**：

- `PiEngineHost.bootLocked` 在 `ProcessBuilder.start()` 返回后**立刻** `publish(session)` 并 `Boot.Ready`；
- `PiSessionViewModel.attach` 立刻 `boot = Boot.Ready`；
- `engineLabel`（现在还有 `engineStarting`）当时只判 `Failed`/`Stopped`，`EngineState.Starting` 落进 `else -> "就绪"`。

**pi 侧时序**（`/root/pi-src` @ `bbb61e34`，0.85.1）：`runRpcMode` → `rebindSession()` → `session.bindExtensions()` → `extensionRunner.emit(session_start)`（`modes/rpc/rpc-mode.ts:299-347`、`core/agent-session.ts:2468-2491`），**stdin 的 JSONL reader 是在这之后才挂上的**。录屏里 `session_start` 通知与第一条模型行同时出现，正是这个顺序：在那之前写进管道的 `prompt` 一直躺在管道里没人读。

**本地量化**（开发容器，x86 Linux，node 24.19.0，pi 0.85.1，扩展用本仓库 `assets/pi-extensions` 原件，`PI_TIMING=1`）：

```
createAgentSessionRuntime: 2513ms   TOTAL: 2586ms
extensions: pi-android-bridge/index.ts 1158ms · pi-highlight/index.ts 429ms · permission-gate.ts 124ms
```

同一件事在设备上是 **68–76 s（≈29×）**：差距来自 proot 的 syscall 翻译（模块解析是海量 `stat`/`open`）加上手机冷启动。**这条是量出来的，不是猜的**——pi 本体和我们的扩展在无 proot 时都很快。

**已改（`applied (uncommitted)`）**：

1. `PiEngineSession.handle`：收到任何 `response` 时把 `Starting` → `Ready`。`response` 是「pi 已经在读 stdin」的唯一证据；`AgentStart`/`AgentSettled` 的既有语义不动，所以"任何一次成功回合也必然回到 Ready"这条兜底还在。
2. `PiEngineSession.probeServing()`：boot 之后立刻发一条 `get_state`（`PiCommands.getState`）。超时 300 s，**超时不改状态**——慢不等于坏，为了满足计时器而把它叫成就绪，正是这次要消灭的那个谎。进程真的死了由 `waitJob` 报 `Failed`，是另一个答案。
3. `PiSessionViewModel.engineStarting()`；`engineLabel` 增加 `Starting -> "启动中"`。
4. `ChatScreen` 空态：启动中时标题「引擎正在启动」，正文写明首次启动要几十秒、**现在发的消息会在引擎开始工作后立刻处理**（属实：`prompt` 已经在管道里，pi 一开始读就会处理）。
5. ~~`PiEngineHost`：`NODE_COMPILE_CACHE=…`~~ **—— 已撤回。实测无效果，见 M6。**

**没有解决的**：pi 自己的启动图（见 M4/M6）。M1 当时把 2.6 s 里的 1.7 s 算成"扩展加载"，那是被 `PI_TIMING` 的测量口径骗了（M4 第 1 条）：**真正的时间在 `main()` 之前，而那段没有任何计时**。

### M2. 输入法把输入框、工具栏、底部导航整个盖住（DEFECT，已修，工作树）

- **证据**：录屏 t=5 s 帧——键盘完全展开时，composer、其工具栏（`/ ! !! @ 图片` 那行）、底部四个导航项**一个都看不见**，聊天区下方直接就是键盘。
- **根因**：`MainActivity` 调 `enableEdgeToEdge()`（`decorFitsSystemWindows=false`）之后，manifest 的 `adjustResize`（`AndroidManifest.xml:118`）**不再让窗口为 IME 让位**，IME 改以 window inset 上报。全仓只有 `TerminalPane` 用了 `imePadding()`，聊天页没有——所以终端页是对的，聊天页被盖住。
- **修法**：`PiRoot` 的 `Scaffold(modifier = Modifier.imePadding())`，一处覆盖四个目的地（与 `adjustResize` 的语义一致：整屏上移，包括底栏）；同时删掉 `TerminalPane` 里那份——父级的 `imePadding()` 不会消费 inset，两处都留会 pad 两次，把按键栏顶出可视区。
- **pi 侧**：`pi 无对应物`（pi 是 TUI，没有 IME 概念）。这是 App 自己的决定。
- **未验证**：无本地 Gradle/模拟器，只有类型检查；键盘弹起时的实际位移必须在真机上看（列入 `docs/device-verification.md`）。

### M3. 发送后约 1 s 内用户气泡是**空的**（观察，未定位，不作为已解决）

t=8 s 帧：气泡已存在、只有时间戳 `22:04`、没有文字；t=9 s 起同一气泡显示「。哈哈哈」。`echoUserPrompt` 是**带着文本一次发布**的（`PiEngineSession.echoUserPrompt` → `transcript.onUserPrompt(text, images)`），所以不是"先发空行再补文本"的代码路径；更像 pi 冷启动把 CPU 占满期间的一次绘制滞后。**没有复现、没有定位**。修完 M1 之后 pi 的启动落在「启动中」阶段，观察它是否还在。

顺带排除一条假线索：气泡时间戳 `22:04` 与状态栏 `10:04` 看似矛盾，其实是状态栏用 12 小时制（10:04 PM = 22:04），不是时区 bug。

### M4. 追查结果（全链路，`docs/startup-latency.md`）——M1 的「为什么」

一位子代理按"从代码全链路 + 本地实测"把这段延迟拆开了，结论与我最初的 proot 猜想**不同**，也比它具体：

- **`PI_TIMING` 的 TOTAL 不是启动的主要成本。** `core/timings.ts:16-38`：`time()` 第一次调用才惰性建命名空间，`lastTime = Date.now()`，所以 TOTAL 从 `main()` 第一行才开始算——**node 启动和整个静态 ESM 模块图一秒都不在里面**。实测 `main TOTAL 2.9–3.4 s`，而同一次运行到「能响应命令」是 29–41 s。
- **真正的成本是 node 的模块加载器在 proot 下发出的 syscall。** 实测整进程墙钟的 **68.6–72.5%** 落在 `internalModuleStat`/`lstat`/`open`/`stat`/`read`/`fstat`/`close` 上，另 **22–24%** 是 V8 编译源码。启动时一共 **969 次模块加载 / 466 个 specifier / 316–380 个脚本**；在本容器里这不难理解：`node -e 1` 只要 0.15 s（syscall 极少），而 `node dist/cli.js --version` 要 **19–35 s**（user 约 7 s、sys 约 3 s，其余在等），因为 proot 每条 syscall 都要 ptrace 停一次进程。
- **jiti/Babel 的假设被量化证伪**：9 个 TS 文件全部 Babel 转译合计只有 **75–85 ms CPU**，jiti 自身加载 269–378 ms，占整进程 **1.1–2.0%**。
- **所以"App 造成的那部分"不大，但确实存在**（同一台机器、同一份工作，三次取样）：
  - pi 不带扩展：29.2 / 34.9 / 35.6 s；
  - `.js` 预编译扩展：30.0 / 34.6 / 35.1 s；
  - **App 原样 TS 扩展：40.6 / 41.0 / 80.7 s** → 预编译省 **6.4 s（中位）/ 10.5 s（最小）**；
  - 另外 `bash -lc` 的登录 profile ≈1.5 s、`RuntimeSelfCheck` 每次启动白跑一个 guest 进程 ≈0.3–3 s。
- **测量窗口被污染，已如实标注**：我（父 agent）当时正在同一容器里并发跑对照实验，内存也吃紧，同一份工作的墙钟能漂 25–55 倍。子代理的做法是只用**同一次运行内部的占比**与**与墙钟无关的结构性计数**，并明确写出"哪些暂时无法归因"。**那部分我不当成已解决。**

**因此 M1 的修法（不再把进程存在当成就绪、把等待显示出来）仍然是对的那一半；另一半——把等待本身缩短——App 侧只剩四条杠杆：预编译扩展、不要 `-lc`、`RuntimeSelfCheck` 只在 revision 变化时跑、让首次启动也能吃到 `NODE_COMPILE_CACHE`。**其中编译缓存本轮已落地；其余三条**本轮没有做**（预编译需要改资产树 + `PiPackageModel` 的 SHIPPED 清单 + 构建步骤，而我自己那次对照实验没能复现出收益——两边的差异是"量 PI_TIMING 的 TOTAL"与"量整进程到可服务"，前者测不到 pre-main 段。要用设备数据定，判据是 `设置 → 运行时与诊断 → 引擎启动耗时`）。

### M5. 那 ~1 秒的空气泡（M3）与"滴答"无关，但同一原因可解释

M3 记的"发送后约 1 秒内用户气泡是空的"仍未定位。它出现在 pi 冷启动把 CPU 占满的那段时间里，最合理的解释仍是"一次绘制滞后"，但在拿到设备侧的帧时间之前**不作为结论**。

### M6. 真 key 端到端实测：pi 自己就要 ~14 秒（2026-09-12，本轮最终数字）

用户提供 DeepSeek key 后，我在本容器（**同一台手机、同一个 proot**）用 pi 0.85.1 真跑了一轮对话，事件逐条打时间戳。方法：解析 `--mode rpc` 的 stdout JSONL，记录 `response(prompt)` / `agent_start` / 第一条 assistant delta / `agent_end`；prompt 在进程创建后 13–30 ms 内就写进 stdin（与 App 完全一样）。

一次典型结果（`--model deepseek/deepseek-v4-flash`）：

```
[    13 ms] prompt 写入 pi 的 stdin
[ 17420 ms] response(prompt) success=true   ← pi 开始读 stdin（引擎可用）
[ 17421 ms] agent_start                     ← 模型这一轮开始
[ 21382 ms] first assistant delta           ← 屏幕上开始出字
[ 21419 ms] agent_settled                   ← 回复完成
```

**模型自己只花约 4 秒；前面 17.4 秒全是引擎启动。**

随后在同一环境、空闲状态下做了对照（每次都是"prompt 写入 → pi 回应 prompt"）：

| 变体 | 用时 |
|---|---|
| **不带任何扩展** | **13.9 s** |
| 旧扩展（`session_start` 里 `await` HTTP，git HEAD） | 16.1 / 14.3 s |
| 新扩展（不阻塞，本工作树） | 14.4 / 14.2 s |
| TS 扩展 | 18.7 / 17.6 s |
| **预编译成 `.js` 的扩展** | **17.7 / 18.7 s**（与 TS 无差别） |
| 冷 `NODE_COMPILE_CACHE` | 20.8 s |
| 热 `NODE_COMPILE_CACHE`（第 2、3 次） | 20.7 / 19.1 s |

**由此撤回两条被污染测量带出来的结论**（都写在这里，免得再被引用）：

1. **"扩展预编译成 `.js` 能省 6.4–10.5 s"——不可复现。** TS 与 JS 两组在真 key 下无差别。相应地，M4 里那张表的"App：未预编译的 TS 扩展 ≈16%"要按本表读作 **≈1–2 s（且只在桥不可达时）**。
2. **"`NODE_COMPILE_CACHE` 把启动从 2.6 s 压到 0.95 s"——不可复现。** 那个 2.6/0.95 s 是 `PI_TIMING` 的 TOTAL，而 TOTAL 不含 pre-main 段（M4 第 1 条）。按"到可服务"量：冷 20.8 / 热 20.7 / 再热 19.1，无差别。**该环境变量已从 `PiEngineHost` 撤回**，只留一条注释说明"试过、没用、别再试"。

**仍然成立的**：

- **本机同一份工作的墙钟会漂 25–55 倍**（空闲 13.9 s ↔ 压测期 80.7 s），所以单次测量不能当结论；上表每个数字都配了同批次对照。
- **`session_start` 阻塞确实在关键路径上**：`rpc-mode.ts:382` 的 `await rebindSession()`（含 `bindExtensions`）先跑，`attachJsonlLineReader` 在 `:810` 才挂上；所以扩展在 `session_start` 里 `await` 一个带 5 s/3 s 超时且重试的 loopback HTTP，最坏会把"能读 stdin"推迟十几秒。**本工作树已把它改成 fire-and-forget**（`pi-android-permission-gate.ts`、`pi-android-bridge/index.ts`），上表 OLD 16.1/14.3 ↔ NEW 14.4/14.2 就是它的量级：**这里是 0–2 s，因为本机 3175 端口没人监听、连接被立刻拒绝；桥"在监听但迟迟不回"时上限仍是那 16 s。**
- **真正的大头是 pi 自己的启动图**：969 次模块加载 / 466 个 specifier / 316–380 个脚本，在本容器里耗时 13.9–18.7 s（空闲），与 App 的扩展基本无关。**唯一能显著动它的是让 pi 少加载文件**：试过用 esbuild 把 `dist/cli.js` 打成单文件（12.5 MB，3.8 s 打完）——**带 `require` banner 的 ESM 产物能起，但会在 `node:fs:484` 挂掉**，因为 pi 会按包目录相对路径读自己的运行时资源文件。**这不是即插即用，本轮没有采用**；要做就得连资源一起处理，并且要有 RPC 冒烟测试兜底。
- **App 侧本轮真正修好的是"说谎"那一半**：就绪判据 + 「启动中」 + 让等待可见，以及新增的「引擎启动耗时」事实行（`app.runtime.engineStartup`，由 `PiEngineSession.probeServing` 实测）。下一次设备复现时，那一行就是"到底多少秒、在哪一段"的答案。

### M7. 「从启动到第一条消息开始思考」要多少秒——冷启动与已就绪两组数字

方法：每次都是**全新进程、全新会话**（无任何热引擎），prompt 在进程创建后 10–30 ms 内写进 pi 的 stdin（与 App 完全一致），`--model deepseek/deepseek-v4-flash`，真 key。事件从 RPC stdout 的 JSONL 上逐条打时间戳。

**A. 引擎冷启动（App 刚打开、pi 还没起来）**——三次：

| # | pi 开始读 stdin | **开始思考（agent_start）** | 首次出字 | 完成 |
|---|---|---|---|---|
| 1 | 19 854 ms | **19 854 ms** | 24 486 ms | 24 909 ms |
| 2 | 14 702 ms | **14 703 ms** | 19 650 ms | 19 672 ms |
| 3 | 14 870 ms | **14 871 ms** | 18 965 ms | 18 987 ms |

三次回复分别是 `1+1 等于 2。` / `2` / `1+1 等于 2。`——**对话本身完全正常，慢的只有引擎起来之前那段。**

**B. 引擎已经就绪（App 显示「就绪」的真实含义）**——用 boot 时那条 `get_state` 探针对齐：

| # | 引擎就绪 | **发消息 → 开始思考** | → 首次出字 | → 完成 |
|---|---|---|---|---|
| 1 | 15 092 ms | **6 ms** | 3 462 ms | 3 475 ms |
| 2 | 15 548 ms | **6 ms** | 4 613 ms | 4 633 ms |

**结论（这就是用户问题的完整答案）**：

- **模型自己只要 3.5–4.6 秒**；用户感觉到的十几秒到一分钟，**全部是"引擎还没开始读那条消息"**。
- 引擎冷启动在**本容器**是 **14.7–19.9 秒**；引擎就绪后再发消息，**6 毫秒就开始思考**。
- 所以 M1 那个修法（就绪判据 + 「启动中」）不是安慰剂：它把"用户以为自己已经在用的那段时间"标出来了。**只要 App 启动时引擎就在起（现在就是这样），用户读完屏幕的十几秒正好覆盖它**；急着手打进去的消息也不会丢，引擎一就绪立刻处理。
- 设备上录屏那 75 秒比这里的 15–20 秒大 4–5 倍，差额在本容器里也能复现到同量级（同一份工作空闲 13.9 s ↔ 压测期 80.7 s），即**环境/负载**，不是另一处 App bug。要坐实这一点，判据是设备上新增的 `设置 → 运行时与诊断 → 引擎启动耗时`。

**本轮试过但没有采用的**（都写了原因，别重复走）：

| 尝试 | 结果 |
|---|---|
| `NODE_COMPILE_CACHE` | 冷 20.8 / 热 20.7 / 再热 19.1 s，**无差别**；已从代码撤回 |
| 扩展预编译成 `.js` | TS 18.7/17.6 s vs JS 17.7/18.7 s，**无差别** |
| esbuild 把 pi 打成单文件 | 12.5 MB / 3.8 s 打得出来，带 `require` banner 的 ESM 产物**能启动**，但运行到 `node:fs:484` 挂——pi 会按包目录相对路径读自己的运行时资源文件。要做就得连资源一起处理 |
| `RuntimeSelfCheck` 每次启动跑一个 guest 进程（`echo pi-runtime-ok`，`RuntimeSelfCheck.kt:79-100`） | **本轮没做**。它是 spawn 之前的步骤，直接压在"引擎开始起来"前面；按 revision 记一个"已通过"的 stamp 就能跳过，估 0.5–3 s。没做的原因是收益相对 15 s 太小，而它的价值恰恰是把"运行时跑不起来"变成一句清楚的话——为一个我没法在本地验证的改动动这个诊断不合适 |
| page cache | 本容器 `drop_caches` 无权限，所以上面所有数字**都带着热页缓存**；设备冷启动只会更慢，不会更快 |

### M8. 单文件打包实测能快 7–10 倍，但会破坏扩展解析——**未采用，方向已验证**

起因：M7 的结论是"那 14–20 秒是 pi 自己的启动图（969 次模块加载）"。既然成本按**文件数**走，就该能靠"把文件变少"来砍。实测如下（真 key、`deepseek-v4-flash`、prompt 在进程创建后 20–30 ms 写入）：

| 形态 | pi 开始读 stdin |
|---|---|
| 原样 `dist/cli.js`（969 次模块加载） | **16 242 / 20 136 ms** |
| esbuild 打成单文件（12.5 MB，JS 全内联） | **2 149 ms** |
| 单文件 + App 的扩展 | **2 805 ms**（但扩展加载失败，见下） |

做法（可复现）：`esbuild dist/cli.js --bundle --platform=node --format=esm --target=node20 --external:@silvia-odwyer/photon-node --banner:js="import { createRequire as __cr } from 'node:module'; const require = __cr(import.meta.url);"`——`--format=cjs` 不行（`cross-spawn` 等 CJS 依赖的动态 `require` 在 ESM 输出里报"Dynamic require … is not supported"）；ESM + `createRequire` banner 才能起。

**为什么没采用**：

1. **pi 会按包目录相对路径读运行时数据文件**，第一处就是
   `dist/modes/interactive/theme/dark.json`（`ENOENT`）。这条好办——数据文件本来就不在 bundle 里，放回原位即可（上表第 2 行就是这么测出来的）。
2. **真正的拦路虎是扩展解析**。把 bundle 放在原 `dist/cli.js` 位置后，pi 的扩展加载器（jiti，`core/extensions/loader.ts:501-514` 的 `alias: getAliases()` 分支）解析 `@earendil-works/pi-coding-agent` 时算出了错路径：
   `Cannot find module '/…/node_modules/@earendil-works/index.js'`；放在别处则是 `Cannot find module 'typebox'`（bundle 位置决定了 jiti 的解析基准）。**本 App 的全部设备能力都是扩展**，扩展加载不起来就没有 android_* 工具，所以这条不解决就不能上。
3. 因此"打包 payload 时把 `dist/cli.js` 换成 bundle"不是即插即用，需要先弄明白
   `getAliases()` / `getPackageDir()` 在 bundle 入口下的行为，并保证扩展按名字导入 SDK 时解析到的仍是 **同一份**模块实例（否则 `instanceof` 与共享状态会分叉）。

**所以这一轮的回答是：方向已验证、收益很大（7–10×），但没有落地。** 记在这里，免得被"我们试过打包"一句话带过——它试过、能快，卡在扩展解析上，且卡点已定位到具体文件与函数。

### M9. **真正的元凶是我们自己的扩展**：一条 `import { StringEnum } from "@earendil-works/pi-ai"`（已修）

M8 之后继续查"为什么打包入口没有变快"，查到了一个比打包重要得多的东西。

**证据（`PI_TIMING=1`，单次运行内部数字，不受环境漂移影响）**：

```
（修之前，App 的三个扩展原样）
  pi-android-bridge/index.ts        module import: 17 763 ms
  pi-android-permission-gate.ts     module import:     48 ms
  pi-highlight/index.ts             module import:    120 ms
```

**17.8 秒全在一条 value import 上。** 机制：pi 的扩展加载器用 **jiti** 把 SDK 交给扩展（`core/extensions/loader.ts:501-514` 的 `alias: getAliases()`），而
`@earendil-works/pi-ai` 这个别名指向的是 **compat 入口**（`getAliases()`，`loader.ts:111-114`：`piAiCompatEntry = ai/dist/compat.js`，"a strict superset of the core entrypoint"）。它的 import 图就是**每一家 provider 及其 SDK**，jiti 要逐文件走 Babel。我们从那条路径只取了一个 4 行的小工具函数。

**修法**：`StringEnum` 的实现（`packages/ai/src/utils/typebox-helpers.ts:14-26`）只依赖 `typebox`，而这个扩展**本来就 import typebox**。所以在 `pi-android-bridge/index.ts` 里逐字转写它（含出处注释），删掉那条 pi-ai value import；`ImageContent`/`TextContent` 仍是 `import type`，不产生运行时依赖。

**效果**：同一个扩展的 module import **17 763 ms → 667 ms（26×）**，其余两个不变。工具注册照常（factory 正常执行、无 stderr）。

**必须写清楚的边界**：**这个容器的端到端墙钟在同一份工作上能从 2 秒漂到 20 秒**（见 M7/M8），所以下面的三组 A/B **不能当成"修好了多少秒"的证据**：

| 组合 | 到 pi 开始读 stdin（各 2 次） |
|---|---|
| 原样入口 + 原扩展 | 17.0 / 13.8 s |
| 原样入口 + 修好的扩展 | 19.0 / 13.9 s |
| 打包入口 + 修好的扩展 | 16.6 / 15.4 s |

三者没有可区分的差别，因为没打包时"`main()` 之前的模块加载"本来就占大头，而那段在这里测不准。**能确定的是**：那条 import 让扩展加载多付了 17 秒的 CPU/解析工作（这是单次运行内部的量，不是墙钟对比），而它现在已经不付了。设备上的判据仍是 `设置 → 运行时与诊断 → 引擎启动耗时`。

### M10. 回到"能不能既打包又让扩展正常"——**能，卡点已定位并已实现一个可用的打包器**

答案：能。M8 里失败的原因是**具体的、可修的**：naive bundle 之后，每个被内联的模块的
`import.meta.url` 都变成了 **bundle 自己的 URL**，而 pi 用 `import.meta.url` 算真实路径——
`getAliases()` 就是 `path.resolve(dirname(fileURLToPath(import.meta.url)), "../..", "index.js")`
（`core/extensions/loader.ts:92-93`），它假定自己仍在 `dist/core/extensions/`。少了两级，于是别名算成了 `node_modules/@earendil-works/index.js`，所有扩展加载失败。

**做法（已在本地验证可用）**：用 esbuild 的 `onLoad` 插件，在**内联之前**把每个源文件里的 `import.meta.url` 替换成**该文件真实的 `file://` URL**（逐模块，而不是全局 define）。原始文件全部留在磁盘上，所以 `import.meta.resolve`、`createRequire`、jiti 别名指向的都还是真实路径。这样打出来的 bundle + App 的三个扩展可以正常加载（对话、工具注册都正常）。

**但打包的收益在本环境里测不出来**（上表三组没有差别），所以**本轮没有采用**。要真做，需要：payload 构建期加这一步（`tools/fetch-runtime.mjs`）、把入口指向 bundle、并配 RPC 冒烟测试——而且收益必须先在设备上用「引擎启动耗时」那一行确认，否则就是为一个测不出来的东西改打包。
