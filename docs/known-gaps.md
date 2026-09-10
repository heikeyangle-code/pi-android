# 已登记的缺口与延期项

这份文档存在的理由：**延期项不能只活在对话里。**在此之前，"以后再做"的判断散落在聊天记录和编码会话的任务列表里，一旦会话结束就丢了。凡是有明确原因被推迟、或者已知但尚未实现的，全部记在这里，并写清楚：为什么延期、卡在哪、怎么收尾。

格式约定：每条都有 **原因**（为什么不现在做）和 **收尾条件**（什么情况下可以做）。

状态标记：
- `延期` —— 想清楚了，有明确的阻塞点，解开了就做
- `未开始` —— RPC 路线上可实现，还没动
- `待验证` —— 代码写完了，但**从未在真机上跑过**
- `已定妥协` —— 不是待办，是刻意的设计取舍，写在这里以免被当成 bug

---

## A. 延期（有明确阻塞点）

### A1. 压缩摘要 / 分支摘要 / Hook 消息改用 markdown 渲染
- **为什么该做**：这三块的内容是**模型写的散文**。pi 源码里 `compactionSummary` 走 `CompactionSummaryMessageComponent`，其第 43 行是 `new Markdown(header + this.message.summary, ...)`；`branchSummary` 同样走 markdown 组件；`HookMessageBlock` 的数据字段名直接就叫 `markdown`。纯文本渲染会把这些内容里的标题、列表、粗体、代码全部显示成源码。
- **阻塞点**：`CompactionBlock` 和 `BranchSummaryBlock` 现在用 `maxLines`（2 行 / 4 行）实现"收起预览，点开展开"。markdown 渲染器**没有 `maxLines`**，直接替换会**丢掉折叠预览**——那是实际功能，不能为了保真把它弄坏。
- **收尾条件 / 做法**：折叠态继续用纯文本 + `maxLines`（把前 N 行文字截出来即可），展开态切到 `PiMarkdownText`。约 30 行改动，落在 `ui/blocks/CompactionBlock.kt`、`BranchSummaryBlock.kt`、`HookMessageBlock.kt`。
- **还没定的**：`ErrorBlock` / `SystemPromptBlock` / `SkillInvocationBlock` 是否也该走 markdown —— 需要逐个回 pi 源码确认它的渲染路径，不能凭感觉。

### A2. LaTeX（行内 `$...$` / 块级 `$$...$$`）渲染
- **为什么该做**：pi 支持 LaTeX（`packages/tui/src/components/markdown.ts` 里的 `LATEX_MARKDOWN_EXTENSIONS` + `renderLatex()`）。我们的解析层其实**已经能识别**：`org.intellij.markdown` 的 GFM flavour 定义了 `GFMElementTypes.INLINE_MATH`、`BLOCK_MATH`，还带一个 `MathGeneratingProvider`。缺的只是渲染。
- **阻塞点**：`MarkdownComponents.custom()` 的调用约定有陷阱 —— 渲染器的分派代码是
  `handled = components.custom?.invoke(node.type, model) != null`，
  而 `custom` 的返回类型是 `Unit`（永远非 null）。**一旦提供 `custom`，所有未识别的块级节点都会被判定为"已处理"**，从而关闭原本的"递归渲染子节点"兜底。所以必须先决定：要么让 `custom` 只接管 math 类型并把其余类型显式交给默认组件，要么在扩展里自建 flavour。
- **收尾条件**：先解决上面这个分派陷阱（写一个只认 math、其余类型转发默认行为的 `custom`），再接公式排版组件。

### A3. 图片（markdown 内嵌 `![](...)` ）
- **为什么该做**：库里 `MarkdownImageKt` / `MarkdownInlineImageKt` / `ImageTransformer` / `ImageData` / `PlaceholderConfig` 一应俱全，但默认实现是 `NoOpImageTransformerImpl`（什么都不加载）。所以现在 markdown 里的图片显示为 alt 文本。
- **阻塞点**：pi 的图片不是 http URL，而是引擎侧的文件（会话附件、工作区里的文件）。要接就得先确定"引擎侧文件怎么按需取字节"这条通道（与设备桥类似，需要一个回环接口或直接读 guest 文件系统）。
- **收尾条件**：定下取字节的方式后，实现一个 `ImageTransformer` 并通过 `LocalPiImageTransformer` 注入。
- **附注**：pi 的**终端界面根本不渲染图片**（终端放不下），所以这一项如果我们做了，是**比 pi 强**，不是补齐。

---

## B. 未开始（RPC 路线可实现，尚未动）

### B1. `/` 命令面板
- 引擎支持 `get_commands`，返回扩展命令、prompt 模板、skills 三类，`source` 字段区分来源。
- `:rpc` 里 `PiCommands.getCommands(id)` 已经存在，但**没有任何调用方**。
- 收尾：输入框键入 `/` 时拉取并分组展示，选中后按 `prompt` 命令发送。

### B2. 会话管理：会话树 / fork / clone / switch / 重命名 / 导出
- `get_tree`、`get_entries`、`get_fork_messages`、`fork`、`clone`、`switch_session`、`set_session_name`、`export_html` 的 builder 全部已存在，**全部无调用方**。
- 收尾：会话树 UI（分支可视化）+ 详情页动作。

### B3. 模型与思考等级选择器 / 队列模式 / 压缩与重试开关
- `get_available_models`、`set_model`、`cycle_model`、`get_available_thinking_levels`、`set_thinking_level`、`cycle_thinking_level`、`set_steering_mode`、`set_follow_up_mode`、`compact`、`set_auto_compaction`、`set_auto_retry`、`abort_retry` —— builder 全部存在，**全部无调用方**。

### B4. bash 模式（在会话里直接跑命令）
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
- **已知副作用（无法从本层修复）**：安装物与 `packages` 都落在 `/root/.pi/agent` 下，而那是 **rootfs 内的易失目录**——`RuntimeProvisioner.wipe()` 会整体删掉 `paths.runtime`（`RuntimeProvisioner.kt:85-91`）并只重建一个空的 `/root/.pi/agent`（`:185`）。见 B7 的「同一处路径错位」。

### B6. `/reload` 热重载 —— **待验证**（已改为「显式重启 + 状态机」）
- **RPC 里没有 `reload` 命令**（同上，`rpc-types.ts:20-74`）。但重载管线**在 RPC 下是通着的**：`rpc-mode.ts:341-343` 把 `reload: async () => { await session.reload() }` 挂进了扩展的 `commandContextActions`，所以**扩展命令**调用 `ctx.reload()` 是 RPC 可达的（用 `prompt("/<扩展命令>")`）。内建 `/reload` 不行：内建命令被排除在 `get_commands` 之外（`docs/rpc.md:853`），`prompt` 只派发扩展命令（`agent-session.ts:1331-1343`）。App 目前没有这样的扩展命令，所以只能重启引擎进程。
- **实现（已完成，未上真机）**：`ExtensionLifecycle`（`app/src/main/kotlin/app/pi/packages/ExtensionLifecycle.kt`）。
  - 状态机：`Idle → Installing → NeedsRestart → AwaitingConfirmation → Restarting → Ready`，另有 `AwaitingIdle`（有回合在跑时的停放态）。
  - **绝不静默重启**：一次成功的 install/remove 只把状态推到 `NeedsRestart`。重启需要两步——`requestRestart()` 只产生「问题」，`restartStarted()` 才算「动作」；取消回到 `NeedsRestart`，不丢待办。
  - **有回合在跑时**：`requestRestart(turnRunning = true)` 进入 `AwaitingIdle`，**不重启**，没有超时、没有强制路径。用户先停回合或等它结束，再点一次；那时会重新查 `isTurnRunning()`，为假才弹确认。理由写在类注释里：App 无法预知回合何时结束，猜一个时刻去杀回合就是本状态机要防的那种静默重启。
  - 时长在确认文案里写明约 1–3 秒，并说明它会终止正在跑的回合、但磁盘上的会话（JSONL 追加写）不会丢。
  - 设置页里所有 `EffectiveKind.Reload` 的行（`PiSettingsRegistry.kt:760`、`:773`、`:797`、`:810`、`:823`；枚举定义在 `ui/components/PiCommon.kt:204`）走同一个入口 `noteExternalChange`——**`EffectiveKind.Reload` 从此有消费者了**。
- **收尾条件**：真机上确认「重启后 `get_commands` 里出现新扩展/新技能」，以及重启耗时。

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

### B8. 扩展 UI 的其余接线（子代理正在做，此处留档）
- 阻塞式对话框（`select`/`confirm`/`input`/`editor`）：`:rpc` 能解析、也有回包构造器，但 App 侧**一行处理都没有** → 引擎会永久阻塞。**这是当前最高优先级的缺陷。**
- 即发即忘（`notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text`）：同上，需要 UI 承载。
- `extension_error` 事件：解析了但没展示，等于静默失败。
- **全局承载点**：扩展弹窗目前只能挂在"对话/工作区"两个页面，用户停留在"会话/设置"页时弹窗排队不可见，而引擎正在等回复（`editor` 还**没有超时**，见下）。修法是在 `PiRoot.kt` 的 Scaffold 里挂一个全局 `ExtensionUiHost`。
  - **必须成对操作**：`PiRoot` 的 `Box` 里渲染的就是当前页面，所以**不能**在 PiRoot 和页面里同时挂——会变成两个 host，同一个请求弹两次窗、两个 Snackbar 抢同一个通知队列。正确顺序：先从 `ChatScreen.kt` / `WorkbenchScreen.kt` 摘掉，再加到 `PiRoot.kt`。
  - **状态**：等对话框子代理摘除页面里的挂载后，由主线加 PiRoot 那一行。

### B11. `PiEvent.ExtensionError` 丢掉了 `extensionPath`
- **现状**：数据类只有 `message`，而 `EventUiRequest` 保留了 `raw`。扩展抛错时定位不到是哪个扩展。
- **收尾**：给数据类加字段 + 在 `extension_error` 解析分支里读——**一行改动**，但 `rpc/Events.kt` 当时被 RPC 命令面子代理占用，不能并发改，故排队。

---

### B9. markdown 渲染库升到 0.42+ / 0.45
- **现状**：用 0.41.0。原因是硬约束：0.42.0 及更新的 AAR 要求 `minCompileSdk=37`，而本项目 `compileSdk = 36`（AGP 8.13.2）；升 37 需要 AGP 9.x，而版本目录里明确记录了刻意避开 AGP 9.x 的大版本迁移。
- **代价**（相对 0.45.0，均已核对）：
  - 没有 GFM alert（`> [!NOTE]` 会退化成普通引用块）
  - `MarkdownTypography` 没有 `alertTitle`
  - 没有 `StreamingMarkdownState` 重载（流式专门优化的解析状态）——**目前没用它**，用的是 String 重载
- **收尾条件**：升级 AGP 到 9.x 且 `compileSdk` 能到 37 时，改版本号 + 补回 `darkTheme`/`alertTitle` 两个参数即可（`MarkdownCodeBackground` 等组件签名 0.41 与 0.45 一致，已核对）。

### B10. `libprootloader.so` 不是 PIE —— 装机时可能被拒
- **背景**：`proot` 是 `ET_DYN`(PIE) 且引用 `/system/bin/linker64`，符合 Android 的 exec 要求；但 Termux 的 `loader` 是 **`ET_EXEC`（非 PIE）**，18,136 字节。它是被 proot **映射**（`PROOT_LOADER`）而不是被 exec 的，所以 PIE 规则本不适用于它 —— `tools/fetch-runtime.mjs` 里那个无条件 PIE 校验已经改成只对"会被 exec"的条目生效。
- **未验证**：Android 的安装器/原生库提取对 `lib/arm64-v8a/` 下**非 PIE 的 `lib*.so`** 是否有额外校验。如果真机装机或启动时报错，退路是：只有 `proot` 必须放在 `jniLibs`（它要被 exec，才需要那个伪装和可执行权限），loader 可以改放进 rootfs 资产里，`PROOT_LOADER` 指向 guest 路径即可 —— 它只需要可读。
- **收尾条件**：真机跑通 boot。

### B12. 扩展资产的安装闸门是手工版本号，会静默失效
- **现状**：`DeviceBridgeController.ASSET_VERSION` 是手工常量，资产按它比对 stamp 决定是否重新拷贝。**已运行过旧版本的设备会一直用旧扩展树**，新扩展（例如 `pi-highlight/`）永远装不进去。
- **危害**：症状不是报错，而是"某个功能就是不好使"——排查的人会去查端口、token、网络，而不是查安装器。高亮代理就是主动报出这一条才被发现的。
- **临时处置**：版本号已从 `"1"` 提到 `"2"`（本次新增 `pi-highlight/`），并在常量上写明"**任何对 `assets/pi-extensions/**` 的改动都必须提升它**"。
- **收尾**：改成**内容指纹**（哈希资产树的文件名+大小，写进 stamp），把这个人工步骤彻底消掉。改一个文件的事，但要在 `bridge/**` 的 owner 手上做。

## C. 待验证（代码已写，从未在真机运行）

> 这一节是整份文档里最重要的部分。上面所有 UI 都在**没有验证过地基**的前提下写的。

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

### E1. 工具名冲突（pi 的规则是"先注册的赢"）
自带扩展（`pi-android-bridge`、`pi-android-permission-gate`、`pi-highlight`）与用户安装的扩展**混在同一个 `~/.pi/agent/extensions/` 里**。同名工具会**静默地让一方失效**，不报错。
命令名的冲突已由 GUI 侧处理（`name:1` 后缀保留，见 `PiSlashCommands.kt`），**工具名的没有任何处理**。
收尾：扩展/包管理界面里检测并显示冲突；或在加载后比对工具表并给出警告。

### E2. 高亮服务的 `attach(context)` 到底有没有被调用
高亮代理说它在 `PiMarkdown.kt` 加了 `attach(context)`。**但没人验证过这条调用在 App 启动路径上真的会走到。**
若没走到：高亮**永远静默退回单色**，而症状看起来像"回环服务没起来"——排查方向会完全跑偏。
收尾：读 `attach` 的调用链，确认它被某一处真实调用（Application 启动 / 首次渲染 markdown）。**改动很小，但它是"看起来能用其实没生效"的典型形态。**

### E3. 附件输入（图片）
协议**已支持**：`docs/rpc.md` 的 `prompt` 命令带可选 `images` 字段，格式 `ImageContent`；会话条目里的 `Attachment` 还带 `fileName/mimeType/size/extractedText/preview`。
App 侧**没有任何 UI**：没有选图、没有粘贴、没有拖入。**只能发纯文字。**
收尾：输入框加附件入口 + 预览 + 随 `prompt` 发送。

### E4. `@` 文件提及
pi 有（见 `docs/settings.md`、`docs/usage.md`），App 没有。

### E5. 懒启动引擎 + 无引擎浏览会话
现在**开 App 就起引擎**（`PiRoot` 的 `LaunchedEffect` → `session.boot()`）。而：
- 会话文件就在 App 私有目录里，**Android 能直接读** → 翻历史、看转录、改设置**根本不需要引擎**；
- 引擎只在"真的要发消息"时才需要起。
收尾：把 boot 从启动路径移走，做成"首次发送/首次需要时启动"，并加一个未启动/启动中/就绪/失败的状态分支。**改善的是启动体感，两条技术路线（留 pi / 换自研）都受益。**

### E6. `ASSET_VERSION` 改成内容指纹（B12 的收尾）
现在是手工常量。改成哈希资产树的文件名+大小写进 stamp，**彻底消掉"忘记提升版本号 → 新扩展静默不安装"这个人工步骤**。

### E7. 扩展/包管理界面必须区分「内置」与「用户安装」
`pi install` 装的东西和我们自带的三个混在同一个目录。若不加区分：
- 用户会**以为自己装过** `pi-android-bridge`，删掉它 → **设备能力全部消失**，而 Kotlin 侧服务还在跑，症状莫名其妙；
- 内置扩展应当**不可卸载**，并标明"随 App 提供"。
（这一点随 B5 的界面一起做。）

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
| `rpc/**` 六条：`ExtensionError.extensionPath`/`event`、`MessageEnd.customType`/`display`、`model_select` 死分支、`AssistantDelta.Unknown` 注释与行为、`system_prompt`/`error` 死分支 | RPC 代理 |
| `turnIndex` 两份审计结论相反，**回源码裁决** | RPC 代理 |
| `contentIndex` 用**真实 `pi --mode rpc` 抓包**验证，不许靠文档猜 | RPC 代理 |
| `PiEngineHost` 的 `restart()`/`reload()` 入口（B6 与 `/reload` 替代方案的共同前置） | 包管理代理（新授权） |
| `runtime/**` 信任存储（`PiTrustStore` + `trust.json`） | 包管理代理 |
| `CAMERA` 权限 + `NO_PERMISSION` 前置条件 | 闸门代理 |
| C 节全部真机验证项 | **等 APK 能构建出来** |

---

## H. 流程教训（写给下一个人，不是待办）

1. **记录 ≠ 派活。** 我把缺口写进这份文档就以为处理过了，但文档**不是工作队列**。每份审计报告进来，必须**立刻**转成"归属人 + 文件 + 验收条件"的条目，否则就会像发生过的那样——连着两轮被问"是不是还有落下的"，而**每次一问都能翻出东西**。
2. **文件归属切得干净，会切出"公共入口无主"的空洞。** `engine/**` 的重启入口就是这样：两个功能都依赖它、两个代理都不许碰。指派任务时要专门检查一遍"**有没有一个所有功能都依赖的文件**"。
3. **委托模板必须禁掉全部 git 写操作**，不只是 `commit`/`push`。`checkout` / `reset` / `stash` / `clean` / `restore` **能一次毁掉所有并行代理的在制品，而且不报错**。
4. **并行时每个代理都会报"红不是我的文件"。** 这没有意义——**只有冻结树之后的整树 typecheck 才算数**。
5. **自证不等于认账。** 代理报告 `typecheck: OK` 时要独立复跑；审计报告里"已核对为正确"的结论也要抽验。**反过来，报"证不出来"的项目要保留**，因为自信的错误发现会让人去改本来正确的代码。
