# 设置面审查与重构（对照 pi 源码）

**审查对象**：`app/src/main/kotlin/app/pi/ui/settings/**`（重构前 5578 行、注册表 107 键；本轮删到 66 键，随后另一位代理在同一文件补入 1 行，现 67 键——见 §0.4）。
**权威来源**：pi 的 `Settings` 接口、`docs/settings.md`、pi 自己的 `/settings` 选择器、以及 `cli/args.ts` 的同名参数。
每个结论都带 `file:line`——**行号是本次审查时的快照**：同一轮还有别的改动在动这些文件，
引用时以符号名（函数名/键名）为准，行号只作定位用。用户可见文案里不写路径/类名/`§`/内部设计解释。

pi 侧文件（下文省略前缀 `packages/coding-agent/`）：

| 代号 | 文件 | 说明 |
|---|---|---|
| `SM` | `src/core/settings-manager.ts` | `Settings` 接口 :106-158；getter 从 :714 起；`reload()` :534；`persistScopedSettings` :632 |
| `DOC` | `docs/settings.md` | 每个键的类型/默认值/语义 |
| `UI` | `src/modes/interactive/components/settings-selector.ts` | pi 的 `/settings` 菜单（id 列表 :462-830） |
| `ARGS` | `src/cli/args.ts` | 同名 CLI 参数 |

---

## 0. 结论（三个问题的直接回答）

### 0.1 设置页应该有哪些东西

**判据是"这个键的读者在手机上够得着吗"**，不是"pi 有没有这个键"。四种归属：

1. **pi 的键、App 只是编辑器**（41 个）——写进 `settings.json`，由 pi 读。它们构成设置页的骨架。
2. **App 自己的键**（25 个）——pi 不认识，App 自己读写（`app.*` 前缀）。
3. **App 必须消费的 pi 键**——写下去，App 自己也得读；`hideThinkingBlock`、`showCacheMissNotices`、
   `theme`、`themes`、`enableSkillCommands`、`defaultProvider/defaultModel`（凭证页）、
   `enabledModels`（导航）、`defaultProjectTrust`（信任弹窗）、以及本轮接上实时通道的
   `steeringMode/followUpMode/compaction.enabled/retry.enabled`。
4. **pi 有、但被本应用固定住的键**（1 个）——`sessionDir`：App 把会话根目录同时写进 pi 的命令行与环境变量，
   而 pi 的优先级是命令行 > 环境变量 > 设置，所以写进设置也不会被读（§7.5）。

由此得到的取舍：**pi 有、但在这个 App 里不可能生效的 28 个键不再出现在设置页**（§1.2 逐条给理由）。
它们不是"删功能"：其中 25 个只有交互式 TUI 读，而 App 的 RPC 引擎不渲染 TUI；
2 个（`enableAnalytics`/`trackingId`）**pi 自己也是只写不读**；
1 个（`sessionDir`）被本应用自己的启动参数覆盖。

### 0.2 现在哪些是无效的

| 形态 | 本轮查到 | 处置 |
|---|---|---|
| 注册了、全树没有消费者 | 12 个 `app.*`（7 个 `app.interaction.*`、`app.trust.projects`、`app.security.dangerThreshold`、`app.security.auditLog`、`app.runtime.safeMode`、`app.sessions.cleanupPolicy`），其中 `cleanupPolicy`/`auditLog`/`dangerThreshold` 连实现的影子都没有 | **删除**（§2.2） |
| 键名根本不是 pi 的 JSON 路径 | `packages[].autoload`：`SettingsDocument.setPath` 按 `.` 切分（`rpc/src/main/kotlin/app/pi/rpc/SettingsDocument.kt:46-58`），会写出 `{"packages[]":{"autoload":true}}` 这种 pi 不认、App 也不读的垃圾对象 | **删除**（§1.2） |
| 读者够不着 | 25 个只有交互式 TUI 读的键，标着"立即生效"（默认 `Immediate`）而 RPC 进程永远不读 | **删除**（§1.2，逐条写"卡在哪"） |
| 刷新语义标错 | `enabledModels` 标 `Immediate`（其实按会话解析）、`httpIdleTimeoutMs`/`websocketConnectTimeoutMs`/`httpProxy`/`defaultProjectTrust`/`images.*`/`retry.*`/`enableInstallTelemetry` 标 `Immediate`/`NewSession` 而实际是"进程启动"或"新会话"、`app.terminal.fontSize` 标 `Immediate` 而 `TerminalPane` 每次进入终端页才读一次 | **逐个改成有依据的值**（§5） |
| 行是第二份真相、与实时状态可能矛盾 | `steeringMode`/`followUpMode`/`compaction.enabled`/`retry.enabled`：聊天页有一套实时开关（`ChatScreen.kt:1205-1206`、`ChatSheets.kt:294`），设置行写文件却推不到运行中的进程 | **接上 RPC 推送**（§4.1），四个键真的变成即时生效 |
| 行只是"指路牌" | 3 个动作行（`app.sessions.import`、`app.about.changelog`、`app.credentials.oauth`）点下去只是切到终端并提示"输入 pi 后运行 xxx" | **删 2 留 1**（§9.2：留下的那个是 OAuth 的唯一下入口；发现性收拢成终端页一句话） |
| 被本应用自己的启动参数覆盖 | `sessionDir`：pi 有键、也有读者，但优先级是 `--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > 设置，而 App 两条都传了，所以这一行永远读不到（原描述"改动在下次启动引擎时生效"是假的） | **删除**（§1.2、§7.5，含"要做需要付出什么"） |
| 描述与 pi 行为不符 | `images.blockImages` 声称"附件入口会禁用"（App 并没有这么做，`images.blockImages` 在 App 侧零消费）；`terminal.showTerminalProgress` 写"源码里有、文档里没写的字段"（内部解释） | **改文案**（§4.3；后者随行删除） |

### 0.3 怎么重构

**删**（41 行）→ **接**（4 个键的实时通道、`app.terminal.fontSize` 的 Reload 语义、`TerminalSettings` 里过期的指向）→
**改**（刷新语义、默认值、文案）→ **锁**（新增 `settings-audit` 检查，让"注册了但没人读"变成能失败的构建）。

分组从 13 组降到 12 组，顺序按 pi 自己的分类 + 手机使用频率：
模型与推理 → 消息与网络 → 上下文与压缩 → 重试与网络 → 工具 → 会话 → 扩展与资源 → 外观 →
终端与 Shell → 安全与信任 → 运行时与诊断 → 隐私与关于。
"交互"整组消失（它的内容一半是死键、一半是 TUI-only 键）；"设备能力/扩展包/开源许可"三个分支留在首页，
它们本来就不是 pi 的键（`SettingsHome.kt:96-140`）。

### 0.4 计数说明

本文的数字是写作时的快照，**唯一权威是 `settings-audit` 每次运行打印的那一行**。本轮的净变化：
**删 43 行**（29 个 §1.2：27 个读者够不着的 pi 键 + 2 个 pi 自己也不读的键 + 1 个被启动参数覆盖的
`sessionDir` + 1 个不是合法路径的 `packages[].autoload`；12 个 §2.2 没有消费者的 `app.*`；
2 个 §9.2 的"指路牌"动作行），**107 − 43 = 64**。

写作之后，同轮别的代理在同一个注册表里补了 3 行（都带真实消费者，`settings-audit` 同样要求）：
`app.models.inventory`（模型清单页，`PiSettingsStack.kt:228`）、`app.runtime.appendSystemPrompt`、
`app.runtime.noContextFiles`。所以当前是 **67 键（41 pi + 26 App）**。

---

## 1. pi 键逐项表

### 1.1 保留的 41 个

"App 消费者"列是 `grep '"<key>"' app/src/main/kotlin rpc/src/main/kotlin` 的结果（排除注册表本身）；
"—"表示**没有 App 消费者，这是正确的**：它是 `PiSettingsCatalog.piOwnedKeys` 里带 pi 读取点的白名单项。

| pi 键 | pi 读取点（getter → 消费点） | pi 自己的界面 | App 注册 | App 消费者 | EffectiveKind | 判定 |
|---|---|---|---|---|---|---|
| `defaultProvider` | `SM:729` → `core/sdk.ts:215`、`main.ts:490` | `/model` Ctrl+S | 是 | `PiConfigFiles.kt:634,652` | `NewSession` | 一致 |
| `defaultModel` | `SM:733` → `core/sdk.ts:216`、`main.ts:491` | `/model` Ctrl+S | 是 | `PiConfigFiles.kt:635,653`、`SettingsHome.kt:205` | `NewSession` | 一致 |
| `defaultThinkingLevel` | `SM:794` → `core/sdk.ts:217`、`agent-session.ts:1882` | `/thinking` Ctrl+S | 是 | `SettingsHome.kt:206` | `NewSession` | 一致 |
| `modelThinkingLevels` | `SM:808` → `core/sdk.ts:218` | `/settings`→逐模型 | 是 | —（白名单） | `NewSession` | 一致 |
| `thinkingBudgets` | `SM:1174` → `core/sdk.ts:370` | 否（JSON-only） | 是 | —（白名单） | `NewSession` | 一致 |
| `hideThinkingBlock` | `SM:961` → `interactive-mode.ts:573` | `/settings` | 是 | `PiSessionViewModel.kt:525`（渲染方就是 App） | `Immediate` | 一致 |
| `showCacheMissNotices` | `SM:965` → `interactive-mode.ts:3694,3804` | `/settings` | 是 | `PiSessionViewModel.kt:529` | `Immediate` | 一致 |
| `transport` | `SM:831` → `core/sdk.ts:369` | `/settings` | 是 | —（白名单） | `NewSession` | 一致 |
| `enabledModels` | `SM:1315` → `main.ts:788`（建会话时解析作用域） | `/scoped-models` | 是 | 只写+导航：`PiConfigFiles.kt:641`、`ChatScreen.kt:541` | `NewSession` | **修正**（原 `Immediate`：写文件不改运行中会话的作用域） |
| `steeringMode` | `SM:757` → `agent-session.ts:1894` | `/settings` | 是 | 本轮新增 `PiSessionViewModel.kt:561-566` RPC 推送 | `Immediate` | **修正**（原为默认值，但没有推送，是假的即时） |
| `followUpMode` | `SM:767` → `agent-session.ts:1895` | `/settings` | 是 | 同上 | `Immediate` | **修正** |
| `httpIdleTimeoutMs` | `SM:936` → `main.ts:851` `configureHttpDispatcher` | `/settings` | 是 | —（白名单） | `RestartEngine` | **修正**（原 `Immediate`） |
| `websocketConnectTimeoutMs` | `SM:957` → `core/sdk.ts:322` | 否 | 是 | —（白名单） | `NewSession` | **修正**（原 `Immediate`） |
| `httpProxy` | `main.ts:583`/`:850` `applyHttpProxySettings(getGlobalSettings().httpProxy)` | 否（JSON-only） | 是 | —（白名单） | `RestartEngine` | **修正**（原 `NewSession`） |
| `compaction.enabled` | `SM:841` → `agent-session.ts:2465` | `/settings`→Auto-compact | 是 | 本轮新增 RPC 推送 + `ChatScreen.kt:1089` | `Immediate` | **修正** |
| `compaction.reserveTokens` | `SM:882` → `agent-session.ts:540,1979` | 否（JSON-only） | 是 | —（白名单） | `NewSession` | 一致（补 `effective`） |
| `compaction.keepRecentTokens` | `SM:886` → 同上 | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `compaction.modelOverrides` | `SM:854-880` → `agent-session.ts:540` | 否（JSON-only） | 是 | —（白名单） | `NewSession` | 一致（补） |
| `branchSummary.reserveTokens` | `SM:903` → `agent-session.ts:3232` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.enabled` | `SM:914` → `agent-session.ts:2983` | 否（`/settings` 无 retry 项） | 是 | `PiSessionViewModel.kt:1727` seed + 本轮新增推送 | `Immediate` | **修正** |
| `retry.maxRetries` | `SM:927` → `agent-session.ts:722,2918` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.baseDelayMs` | `SM:927` → `agent-session.ts:722` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.maxAgentDelayMs` | `SM:927` → `agent-session.ts:2918` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.provider.timeoutMs` | `SM:949` → `core/sdk.ts:315` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.provider.maxRetries` | `SM:949` → `core/sdk.ts:315` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `retry.provider.maxRetryDelayMs` | `SM:949` → `core/sdk.ts:371` | 否 | 是 | —（白名单） | `NewSession` | 一致（补） |
| `defaultTools` | `SM:1319` → `core/sdk.ts:257` | 否（`--tools` 参数） | 是 | —（白名单） | `NewSession` | 一致 |
| `extensions` | `SM:1100` → `resource-loader` / `package-manager.ts` | `pi config`（TUI） | 是 | `ExtensionLifecycle.kt:372`、`PiPackageFilters.kt:76`、`ProjectTrust.kt:41` | `RestartEngine` | 一致（不确定项见 §7.2） |
| `packages` | `SM:1084` → `package-manager.ts:914` | `pi install`/`pi list` | 是 | `PiPackageFilterStore.kt:69,90,97`、`PiPackageService.kt:261` | `RestartEngine` | 一致（只读行，开包管理页） |
| `skills` | `SM:1116` → `resource-loader` | `pi config` | 是 | `ExtensionLifecycle.kt:373`、`ProjectTrust.kt:42` | `RestartEngine` | 一致 |
| `prompts` | `SM:1132` → `resource-loader` | `pi config` | 是 | `PiPackageFilters.kt:76`、`ProjectTrust.kt:43` | `RestartEngine` | 一致 |
| `themes` | `SM:1148` → `resource-loader` | `pi config` | 是 | `PiSessionViewModel.kt:489`（App 自己的主题选择器） | `RestartEngine` | **有意偏离**：App 侧热读、pi 侧要重启（§7.2） |
| `enableSkillCommands` | `SM:1164` → `interactive-mode.ts:716` | `/settings` | 是 | `PiSessionViewModel.kt:1636`（命令面板） | `Immediate` | 一致（RPC 下由 App 实现，见 `docs/known-gaps.md` 记录） |
| `theme` | `SM:783` → `main.ts:887` `initTheme`、`agent-session.ts:3464` | `/settings`、`/theme` | 是 | `PiSessionViewModel.kt:466`、`PiSettingEditorHost.kt:35` | `Reload` | 一致 |
| `images.autoResize` | `SM:1289` → `agent-session.ts:522,2792` | `/settings` | 是 | —（白名单） | `NewSession` | **修正**（原 `Immediate`） |
| `images.blockImages` | `SM:1302` → `core/sdk.ts:271` | `/settings` | 是 | —（白名单） | `NewSession` | **修正**（原 `Immediate`） |
| `shellPath` | `SM:993` → `agent-session.ts:2794,3016` | 否（JSON-only） | 是 | —（白名单；`DeviceBridgeRouter.kt:336` 的同名字符串是设备桥自己的字段，与这个键无关） | `NewSession` | 一致 |
| `shellCommandPrefix` | `SM:1025` → `agent-session.ts:2793,3015` | 否 | 是 | —（白名单） | `NewSession` | 一致 |
| `npmCommand` | `SM:1035` → `package-manager.ts:1748` | 否（JSON-only） | 是 | —（白名单） | `NewSession` | 一致 |
| `defaultProjectTrust` | `SM:1014` → `main.ts:745`（**启动时**那个 manager）、`interactive-mode.ts:4589` | `/settings` | 是 | `PiSettingsStack.kt:226`（App 自己的信任弹窗） | `RestartEngine` | **修正**（原 `NewSession`：读它的是进程启动时建的 manager，新会话不重读） |
| `enableInstallTelemetry` | `SM:1055` → `core/telemetry.ts:12`（`provider-attribution.ts:40`） | `/settings` | 是 | —（白名单） | `NewSession` | **修正**（原 `Immediate`） |

### 1.2 移除的 29 个（28 个 pi 键 + 1 个不存在的路径）

删除依据分四类：**读者够不着**（TUI-only，RPC 不渲染）、**pi 自己也不读**、
**被本应用自己的启动参数覆盖**、**键名不是合法路径**。每条都能在 pi 源码里指到读者、
或指出覆盖它的那一行，所以不是"我们猜它没用"。

| pi 键 | pi 读取点 | 判定与卡点 |
|---|---|---|
| `sessionDir` | `SM:150` 定义，`SM:724` 读取，`main.ts:670-676` 决定优先级 | **删除**（App 自己把它固定了，§7.5）。pi 的优先级是 `--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > `settings.json`，而 App 两条都传（`PiEngineHost.kt:275`、`:307`），所以写在这里的值永远不会被读。**不是"接不上"，是"我们故意钉住了"**：App 必须知道去哪读会话列表，那个目录由启动参数与 `PiSessionStore` 共用 |
| `terminal.showImages` | `SM:1188` → `interactive-mode.ts:3257,3332,3725` | App 的决定：不做。只有 TUI 画内联图片时读；RPC 引擎不渲染，内嵌终端的 libvterm/termlib 没有 kitty/iTerm2 图片协议 |
| `terminal.imageWidthCells` | `SM:1201` → 同上 | 同上（没有图片协议，宽度无意义） |
| `terminal.clearOnShrink` | `SM:1218` → `interactive-mode.ts:542,1952` | 同上（TUI 重绘策略，App 的对话流是 Compose） |
| `terminal.showTerminalProgress` | `SM:1235` → `interactive-mode.ts:843,3184` | 同上（OSC 9;4 由 TUI 发；这一行的旧描述还写了"源码里有、文档里没写"的内部解释） |
| `terminal.hyperlinks` | `SM:1178` `getTerminalCapabilityOverrides` → `cli/startup-ui.ts:84` | 同上（能力覆盖只喂给 TUI 的终端探测） |
| `terminal.images` | 同上 | 同上 |
| `terminal.trueColor` | 同上 | 同上 |
| `outputPad` | `SM:1372` → `interactive-mode.ts:574,1950` | App 的决定：不做。TUI 消息内边距；App 的块间距是自己的 `app.appearance.messageDensity` |
| `editorPaddingX` | `SM:1362` → `interactive-mode.ts:556,1957` | 同上（TUI 输入编辑器） |
| `autocompleteMaxVisible` | `SM:1382` → `interactive-mode.ts:557,2682` | 同上（TUI 补全下拉；App 的 `@` 补全有自己的列表） |
| `showHardwareCursor` | `SM:1352` → `interactive-mode.ts:536,1951` | 同上（TUI 光标定位） |
| `markdown.codeBlockIndent` | `SM:1392` → `interactive-mode.ts:1261` | 同上（TUI markdown 渲染；App 用 compose-markdown） |
| `markdown.mermaid` | `SM:1396` → `interactive-mode.ts:440` | 同上 |
| `quietStartup` | `SM:1004` → `interactive-mode.ts:859,912` | 同上（TUI 启动头） |
| `doubleEscapeAction` | `SM:1330` → `interactive-mode.ts:2863` | 同上（TUI 的连按 Esc）；App 有自己的返回/停止处理 |
| `treeFilterMode` | `SM:1340` → `interactive-mode.ts:5208` | 够不着。App 的会话树有自己的 5 种过滤（`SessionTreeScreen.kt:232-239`），默认值没有接线；要接应作为**会话树的默认过滤**，属功能（§7.3） |
| `fullscreenCopyOnSelect` | `SM:1279` → `interactive-mode.ts:539,1944` | App 的决定：不做。全屏 TUI 专属 |
| `fullscreenScrollbar` | `SM:1268` → `interactive-mode.ts:884,1936` | 同上（要指针悬停/滚动条轨道，手机没有） |
| `fullscreenExitOutput` | `SM:1258` → `interactive-mode.ts:6601` | 同上 |
| `tuiMode` | `SM:1248` → `interactive-mode.ts:523` | 同上；RPC 模式连主题切换都不支持（`rpc-mode.ts:300-303`），更没有 TUI 模式 |
| `externalEditor` | `SM:969` → `interactive-mode.ts:2628,4247` | 够不着。Ctrl+G 外部编辑器；运行时里没有 nano/vim 这类可用的编辑器 |
| `warnings.anthropicExtraUsage` | `SM:1408` → `interactive-mode.ts:4909` | 够不着。只在 TUI 弹警告，RPC 事件流里没有这条 |
| `branchSummary.skipPrompt` | `SM:910` → `interactive-mode.ts:5236` | 够不着。TUI 导航 `/tree` 时问不问"要摘要吗"；RPC 的 `navigate_tree` 由客户端传 `summarize`，App 自己决定 |
| `enableAnalytics` | `SM:1065`（**全树没有调用点**） | 多余。pi 只写不读（写入在 `SM:1074-1079`），`docs/settings.md` 也写"currently only asked for during the experimental first-time setup" |
| `trackingId` | `SM:1069`（**全树没有调用点**） | 多余。同上，且这一行在 App 里是不可编辑的只读行，显示一个没人用的值 |
| `lastChangelogVersion` | `SM:714` → `interactive-mode.ts:1218` | App 的决定：不做。changelog 只在 TUI 里展示；App 给 pi 设了 `PI_SKIP_VERSION_CHECK=1`（`PiEngineHost.kt:310`），永远不会有"更新后第一次启动"这件事 |
| `collapseChangelog` | `SM:1045` → `interactive-mode.ts:766` | 同上 |
| `packages[].autoload` | —（**不是 pi 的键**） | 删除。`packages` 的对象形式里可以写 `autoload`，但注册表用的是点分路径，`SettingsDocument.setPath` 会把它当成字面量键 `packages[]` 写进 JSON（`SettingsDocument.kt:46-58`）。对象形式的过滤/autoload 已由包管理页（`PiPackageFilters`）负责 |

---

## 2. App 自有键逐项表

### 2.1 保留的 24 个 + 3 行（每个都有真实调用点）

| `app.*` 键 | 谁读它（file:line） | EffectiveKind | 判定 |
|---|---|---|---|
| `app.credentials.apiKey` | `PiSettingsStack.kt:188`（动作分发） | — | 一致（动作行，开凭证表单） |
| `app.credentials.oauth` | `PiRoot.kt:68`（切终端跑 `/login`） | — | 一致 |
| `app.localModels.manage` | `PiSettingsStack.kt:189`（开 llama.cpp 端点表单） | — | 一致（描述已写明管理部分只能在终端做，依据 `extensions/llama/index.ts:186-189`） |
| `app.compaction.runNow` | `PiRoot.kt:234` → `session.compact()` | — | 一致 |
| `app.tools.expandByDefault` | `PiSessionViewModel.kt:524` → `ChatScreen` | `Immediate` | 一致 |
| `app.sessions.resumeLast` | `PiSessionViewModel.kt:903` → `maybeResumeLastSession()` | `RestartApp` | 一致 |
| `app.appearance.fontScaleDelta` | `PiSessionViewModel.kt:520` | `Immediate` | 一致 |
| `app.appearance.messageDensity` | `PiSessionViewModel.kt:521` | `Immediate` | 一致 |
| `app.appearance.showTimestamps` | `PiSessionViewModel.kt:522` | `Immediate` | 一致 |
| `app.appearance.thinkingCollapsedByDefault` | `PiSessionViewModel.kt:523` | `Immediate` | 一致（文案不再引用 pi 的键名） |
| `app.terminal.fontSize` | `TerminalSettings.kt:80` → `TerminalPane` | `Reload` | **修正**（原 `Immediate`：`TerminalPane.kt:86-91` 每次进入终端页才读一次，是双指缩放的起点） |
| `app.terminal.keyBar` | `TerminalSettings.kt:105` | `Reload` | 一致 |
| `app.security.emergencyStop` | `PiRoot.kt:242` → `stopAndDrainQueue()` | — | 一致 |
| `app.runtime.piVersion` | `RuntimeFacts.kt:153,166` | — | 一致（只读真值） |
| `app.runtime.nodeVersion` | `RuntimeFacts.kt:154,167` | — | 一致（只读真值） |
| `app.runtime.rootfsUsage` | `RuntimeFacts.kt:155,168` | — | 一致（只读真值） |
| `app.runtime.engineStartup` | `RuntimeFacts.kt:156,174` | — | 一致（只读真值） |
| `app.runtime.offline` | `PiSessionViewModel.kt:698` → `PiLaunchOptions.offline`（`PI_OFFLINE`） | `RestartEngine` | 一致 |
| `app.runtime.systemPrompt` | `PiSessionViewModel.kt:702` → `--system-prompt` | `RestartEngine` | 一致 |
| `app.runtime.cacheRetention` | `PiSessionViewModel.kt:699` → `PI_CACHE_RETENTION=long` | `RestartEngine` | 一致 |
| `app.runtime.restartEngine` | `PiSettingsStack.kt:199`（确认后重启） | — | 一致 |
| `app.runtime.keepAlive` | `PiSessionViewModel.kt:530,574` → `startEngineService()`（启动路径，调用点 `:720`） | `RestartApp` | 一致 |
| `app.runtime.wakeLock` | `RuntimeFacts.kt:157,183` | — | 一致（只读真值） |
| `app.models.inventory` | `PiSettingsStack.kt:228`（开模型清单页） | — | 一致（**另一位代理本轮补入**，不在本轮的删除/修正范围内；审计同样要求它有消费者） |

### 2.2 删除的 14 个（没有消费者、描述的机制不存在，或只是指路牌）

| `app.*` 键 | 查到什么 | 处置与理由 |
|---|---|---|
| `app.sessions.cleanupPolicy` | 全树 0 处引用；App 没有任何会话清理/回收逻辑，也没有"可撤销删除" | 删除。留着等于承诺一个不存在的清理器 |
| `app.interaction.searchScope` | 0 处 | 删除。对话内搜索（`ChatScreen.kt:274-297`）有自己的匹配规则，没有读这个键；要做成一个开关是功能，不是修缺 |
| `app.interaction.gestures` | 0 处 | 删除。手势是硬编码的，没有逐项开关 |
| `app.interaction.haptics` | 0 处 | 删除（同上） |
| `app.interaction.soundEffects` | 0 处 | 删除（同上） |
| `app.interaction.motionLevel` | 0 处 | 删除。真正的动效控制是 Android 系统的"移除动画"，App 没有独立实现 |
| `app.interaction.lowEndDeviceMode` | 0 处；描述却写得很具体（降到 10fps、丢弃中间增量） | 删除。这是"描述了一个没人实现的机制"的最坏形态 |
| `app.interaction.keybindings` | 0 处；而且它描述的是 **pi 的文件** `~/.pi/agent/keybindings.json`（`docs/keybindings.md`），却挂在 `app.` 前缀下、格式也不是 pi 的格式 | 删除。它不是 `settings.json` 的键；要做应是一个文件编辑器（像 `models.json` 那样），见 §8 |
| `app.trust.projects` | 0 处；信任决定的唯一权威是 `TrustRepository` + `PiProjectTrustPrompt` | 删除。这是 `docs/known-gaps.md` §I10 的同一条教训——**授权只能有一个真相来源**，设置页不该摆第二份信任名单 |
| `app.security.dangerThreshold` | 0 处；pi 也没有这个设置（审批粒度在工具/扩展层） | 删除 |
| `app.security.auditLog` | 0 处；App 没有审计日志实现，pi 也没有 | 删除。这一行是"列每一次工具调用与审批决定"，而没有任何东西在记录 |
| `app.runtime.safeMode` | 0 处 | 删除，**卡在这里**：pi 有 `--no-extensions`（`ARGS:169`，帮助文本 `:303`），但设备桥扩展就装在 agent 目录的 `extensions/` 下、不是用 `-e` 显式传的（`PiEngineHost.kt:289-295` 只绑目录），所以 `--no-extensions` 会连桥一起禁掉；要安全模式只禁用户扩展，得先把桥改成显式 `-e` + `-ne`，pi 没有更细的粒度。等有了这个粒度再做 |
| `app.sessions.import` | 消费者只有它自己那一行的跳转（`PiRoot.kt` 的 `onRunAction`）；`/import` 只有 TUI 有 | 删除（§9.2）。一行只能说"去终端跑 /import"，不是设置 |
| `app.about.changelog` | 同上（`/changelog`） | 删除（§9.2）。与 `lastChangelogVersion`/`collapseChangelog` 是同一类 TUI 事务 |

---

## 3. 判定分类汇总

- **一致**：22 个 pi 键 + 23 个 App 键的行为与刷新语义都和读者一致（§1.1 里没被标"修正"的行、§2.1 里没被标"修正"的行）。
- **缺口**：**pi 的键全部有归属**——`Settings` 接口是 51 个顶层键、展开嵌套组后 72 个叶子键，
  其中 `thinkingBudgets` 的 4 个子键由一行对象编辑器覆盖，所以是 69 个条目 =
  **41 个已注册 + 28 个不做**（25 个读者够不着、2 个 pi 自己也不读、1 个被 App 启动参数固定，§7.5），
  没有"pi 有、App 完全缺失且值得在手机上有的键"。
  唯一两个候选是 `treeFilterMode`（作为会话树默认过滤，§7.3）和 pi 的 `keybindings.json`（§8），
  两者都不是 `settings.json` 的缺失键，属功能而非缺口。
- **多余 / 无效**：§0.2 的四类，共 40 行已删。
- **有意偏离**：3 处，都在 §7 写明依据与不确定性：`themes`（App 热读、pi 要重启）、
  `extensions/skills/prompts/packages` 的 `RestartEngine`（新会话是否足够，§7.2）、
  `enableSkillCommands` 在 RPC 下由 App 自己实现。

---

## 4. 重构动作清单

### 4.1 把四个"第二份真相"接上实时通道（新代码，唯一的行为改动）

`steeringMode`、`followUpMode`、`compaction.enabled`、`retry.enabled` 在设置页写 `settings.json`，
但**运行中的进程不会读这个文件**：pi 的 `SettingsManager` 每个进程建一次
（`SM:344-357` `create` → `fromStorageWithPaths`）、每个会话再建一次
（`agent-session-runtime.ts:236-252` 重建 runtime → `main.ts:731` 新建 manager）。
RPC 面只有这四个键有对应的实时命令：`set_steering_mode`/`set_follow_up_mode`
（`rpc-mode.ts:521-530`）、`set_auto_compaction`/`set_auto_retry`（`rpc-mode.ts:532-540`），
而 pi 收下命令后会**写回同一个键**（`agent-session.ts:1902-1917`），所以两个写入方不会互相打架。

落地：`PiSessionViewModel.onSettingWritten`（`PiSessionViewModel.kt:548-571`）新增一个 `when`，
把刚写下的值通过现成的 `setSteeringMode`/`setFollowUpMode`/`setAutoCompaction`/`setAutoRetry` 推给引擎。
改完之后这四行的 `Immediate` 才是真的。

### 4.2 删除 40 行（§1.2、§2.2）

`PiSettingsRegistry.kt` 本轮删 43 行（107 → 64），同轮别的代理补入 3 行，现 67 键（41 pi + 26 App）；
注册表行数 1926 → 1467（随并发编辑在动，见 §0.4）。
连带删掉的是"只为已删行存在"的复杂度：组常量 `G_INTERACTION` 与它的图标导入、
5 个选项表（`tripleStateOptions`/`imageProtocolOptions`/`outputPadOptions`/`mermaidOptions`/`gesturePresets`）、
以及 `PiOption.wireIsScalar` 这个标量线格式开关——它存在的唯一理由是 `outputPad`（0|1）、
`terminal.hyperlinks`/`trueColor`（boolean|"auto"）、`terminal.images`（以 `false` 结尾）这四个键，
而它们全在 §1.2 被移除；`wireElement` 现在就是 `JsonPrimitive(wire)`，
`wireElement` 的 KDoc 写明了"再加这种行要先把这个开关加回来"，避免下一个人写出 `"1"` 而不是 `1`。
分组编号注释同步修正（10 安全与信任 … 13 隐私与关于）。

### 4.3 文案与默认值

- `images.blockImages`：改成 pi 的语义（图片仍可附加，只是不发给模型），删掉 App 并没有实现的"附件入口会禁用"。
- `terminal.showTerminalProgress`：随行删除（旧描述含内部解释）。
- `app.appearance.thinkingCollapsedByDefault`：不再引用 pi 的键名。
- `enabledModels`：说明改成"选择范围在开启新会话时确定"（与新的 `NewSession` 一致）。
- `compaction.enabled`：说明补一句"改动立刻作用于当前会话"（与新的推送一致）。
- `app.terminal.fontSize`：说明"起始字号、可双指缩放、重新进入终端页后生效"（与 `TerminalPane.kt:86-91` 一致）。
- 默认值逐条对照 `DOC`：本轮**没有发现与 pi 文档不符的默认值**（`compaction.*`、`retry.*`、`httpIdleTimeoutMs` 300000、
  `websocketConnectTimeoutMs` 15000、`steeringMode`/`followUpMode` `one-at-a-time`、`transport` `auto`、
  `defaultProjectTrust` `ask`、`images.*`、`theme` `dark` 等全部一致）。

### 4.4 顺手修掉的一处过期注释

`ui/terminal/TerminalSettings.kt:41-43` 写着"`app.terminal.scrollbackLines`/`cursorStyle` 两行还在注册表里、等待处置"，
而这两行早在 `6c2059a` 就删了——注释比代码慢了一轮的典型。已改成"本轮审计已移除，无需同步"。

### 4.5 分组

删掉"交互"组；"消息与队列"更名为"消息与网络"并把 `transport` 从"模型与推理/传输"移进来
（pi 的 `DOC` 也是把 `transport`/`httpIdleTimeoutMs` 与送达模式放在同一节 "Message Delivery"）。
其余顺序与组名保持，因为问题从来不是组名，而是组里塞了读者够不着的行。

---

## 5. `EffectiveKind` 判据（本轮统一采用）

| 值 | 判据 | 依据 |
|---|---|---|
| `Immediate` | **App 自己读并即时生效**（渲染偏好、命令面板、或经 RPC 推送进运行中的进程） | `PiSessionViewModel.readPrefs`/`onSettingWritten`；推送见 §4.1 |
| `Reload` | App 在**重新进入某个界面**时热读（不重启进程） | 主题 `PiSessionViewModel.kt:466`；终端字号/按键条 `TerminalPane.kt:86-91` + `TerminalSettings.kt:51-54` |
| `NewSession` | 值由 pi 在**建会话时**读（`SettingsManager.create` + `createAgentSessionServices`，`agent-session-runtime.ts:236-252`、`agent-session-services.ts:139-152`） | 列在 §1.1 各行的 pi 读取点 |
| `RestartEngine` | 值只在**进程启动**时读（`main.ts` 的 startup/dispatcher/代理/会话目录，或 CLI/env） | `main.ts:583,675,745,850,851`；`app.runtime.*` 三项映射到 `PiLaunchOptions` |
| `RestartApp` | App 自己在**进程启动**时读（前台服务、续接最近会话） | `PiSessionViewModel.kt:720`、`:903` |

判定的一个前提值得写下来：**pi 不重读 `settings.json`**。它只在 `create`/`reload()`（`SM:534-559`）时读文件，
而 `reload()` 的 RPC 面只在扩展的 `commandContextActions.reload` 里（`rpc-mode.ts:341-343`），
App 没有可达的 `/reload`。所以"写文件就生效"对 pi 的键一律不成立——要么新会话、要么新进程、要么走 RPC 命令。

---

## 6. 新增检查：`settings-audit`

`tools/run-app-pure-checks.sh` 新增一项（bare JVM，不需要 Android SDK、不需要 Gradle）：

```
run_harness settings-audit app.pi.ui.settings.PiSettingsAuditCheckKt \
  app/src/test/kotlin/app/pi/ui/settings/PiSettingsAuditCheck.kt
```

它把 `PiSettingsRegistry.kt` 当**源码文本**读（注册表 import 了 Compose，不能在这里编译），然后断言：

1. 每个注册键**要么**在 `app/src/main/kotlin` 里出现键名字符串（近似"有消费者"），
   **要么**在 `PiSettingsCatalog.piOwnedKeys` 里带理由声明（"pi 读它、App 只写"）。都不满足 → **失败并打印键名**。
2. `app.*` 键**不得**出现在白名单里（App 是它唯一可能的读者，白名单化等于给死开关盖章）。
3. 白名单不得过期（每一项都必须是已注册的键）。
4. 不得重复注册（`byKey` 会静默保留最后一个）。
5. 白名单的每一条理由都非空（"声明"不能是一个空词）。
6. 搜索页的每一个提示 chip（含斜杠命令）必须仍能在注册表的**行区域**里匹配到东西——`/tree` 与
   `sessionDir` 的失效正是"删了行、提示还留着"，与死开关是同一类病，只是位置高了一层。
   只匹配行区域（`val settings` 到查找表之前），否则 chip 可能靠一段注释通过。

运行环境由脚本统一传入 `-Dpi.repo.root="$ROOT"`，这样审计的判据不取决于"恰好从哪个目录跑了脚本"。
当前结果（写作后一位代理又补了一行）：**67 键（41 pi + 26 App）→ 44 个由 App 读、23 个声明为 pi-owned、9 个提示 chip 全部可解析、0 失败**。
这个检查的价值在于它**能失败**：把 §I7 那 13 个键、`app.contextFiles`、或本轮的 12 个死键中的任何一个放回注册表，
这条断言都会变红并打印键名——它们全都是"注册了、没人读"。

---

## 7. 不确定性（写明判据，需要设备/真机才能定）

### 7.1 四个实时推送在真机上的表现
判据：改"穿插模式"为"全部" → 不新建会话 → 发一条流式消息看它是否一次收两条。
静态依据是 `rpc-mode.ts:521-540` 与 `agent-session.ts:1902-1917`，但没有真机跑过。
失败形态会是 `busy` 标签一闪而过而模式没变——那就是 `PiEngineApi.setSteeringMode` 的线格式与该版 pi 不一致。

### 7.2 资源类五行（`extensions`/`packages`/`skills`/`prompts`/`themes`）的 `RestartEngine`
本轮**保留**了 `RestartEngine`，但源码读下来新会话也够：`new_session` → `runtimeHost.newSession`
（`agent-session-runtime.ts:226-252`）→ `createRuntime` → 新的 `DefaultResourceLoader` + `reload()`
（`agent-session-services.ts:139-152`），扩展/技能/提示模板都会重新扫描。
`docs/known-gaps.md` §B5/B6/B7 的结论（"只有重启才重新扫描"）测的是**同一个会话内**的缓存，与新会话无关。
之所以没有直接把标签改成"新会话"：App 的安装流程本来就重启引擎（`ExtensionLifecycle` + `EngineRestartCoordinator`），
而这一改动需要真机确认。**判据**：装一个技能 → 开新会话 → 看 `/skill:名字` 是否出现在命令面板。
出现 → 五行应改成 `NewSession`（更省的代价）；不出现 → 维持 `RestartEngine`。

### 7.3 `treeFilterMode` 作为会话树的默认过滤
`SessionTreeScreen` 有 5 种过滤（`SessionTreeScreen.kt:232-271`），但默认值不读 pi 的键。
要接需要把 settings store 传进那个屏幕（现在它只拿 ViewModel 的 state）。
判据：接上后进入会话树，默认过滤等于 `settings.json` 里的值。本轮没做，因为它是功能新增而不是修缺。

### 7.4 `app.terminal.fontSize` 的"重新进入终端页"是否真的重新读
`TerminalPane.kt:94` 的 `remember(settingsStore)` 只在 Workbench 重新组合时读，
而 Workbench 在底部导航里离开再回来是否一定重新组合，本机证明不了。
判据：改字号 → 切到对话页 → 回终端页，字号应变化。

---

### 7.5 `sessionDir`：被本应用钉住的会话根目录（已删除该行）

pi 有 `sessionDir`（`SM:150`，读取 `SM:724`，优先级 `--session-dir` > `PI_CODING_AGENT_SESSION_DIR` >
`settings.json`：`main.ts:670-676`、帮助文本 `ARGS:431`）。本应用**两条更高优先级的输入都传了**
（`PiEngineHost.kt:275` 的 `--session-dir`、`:307` 的 `PI_CODING_AGENT_SESSION_DIR`），因为会话列表的读取方
（`PiSessionStore`）必须和 pi 看同一份目录。所以设置页那一行**永远生效不了**，它的描述
"改动在下次启动引擎时生效"是一句假话——这就是它被删掉的原因，而不是"pi 没有这个能力"。

**如果将来要让它真的生效（本轮不做，代价明确）**：① 同时去掉 `--session-dir` 与那个环境变量；
② 让 `PiSessionStore.mostRecentForResume` 也读按 cwd 的分组目录（它现在只读平铺根目录，
`PiSessionStore.kt:127-128` 自己这么写着），否则 `-c` 续接会在 pi 换了目录之后指向空；
③ 会话列表页的分组展示要跟着改（现在按 cwd 分组，是因为引擎把目录固定了）。
**判据**：删掉那两个启动输入后，在设置里改一个目录 → 重启引擎 → 新会话文件真的出现在那个目录，
且会话列表能列出它、`-c` 能续接它。

---

## 8. 不做 / 够不着的清单（明确写下来，不硬做）

| 项 | pi 侧 | 为什么不进设置页 |
|---|---|---|
| 25 个 TUI-only 键 | 见 §1.2 | 读者是交互式 TUI，App 的引擎是 RPC；够不着，且 App 用 Compose 重画了同一批界面元素 |
| `keybindings.json` | `docs/keybindings.md`（`core/keybindings.ts`，`/reload` 后生效） | 不是 `settings.json` 的键。要做应做成"文件编辑器"，像凭证页编辑 `models.json` 那样；本轮删掉了那个冒牌的 `app.interaction.keybindings` 行 |
| 原始 `settings.json` 编辑器 | pi 的 `/settings` 是类型化表单，没有原始编辑器 | App 已有逐键编辑器 + 全键搜索；再加一个原始编辑器会写出 pi 的写入方不会产生的文件（与 `packages` 行改成只读同理） |
| 「导入桌面 `~/.pi`」/「导出配置包」/「重置」 | pi 没有这些命令 | pi 的设置在文档化的路径下就是 JSON 文件，没有导入导出格式可对齐（`SM` 的 KDoc、`DOC` 的 "Location" 表） |
| `app.runtime.safeMode` | `--no-extensions` 存在 | 见 §2.2：会连设备桥一起禁掉，pi 没有"只禁用户扩展"的粒度 |
| `sessionDir` 改成真正生效 | pi 有键、也有读者 | 见 §7.5：要先去掉两个启动输入并让会话列表读分组目录，本轮不做 |

---

## 9. 架构原则：设置只调 GUI，TUI 的旋钮不进设置

**原则（用户裁决，本轮执行）**：**设置里只放 GUI 自己能兑现的东西。pi 的键如果只有交互式 TUI 读，
就不进 App 的设置——用户要调它，去终端里跑原版 TUI。两者基本分开，唯一共享的是会话列表；
底层是同一个 pi，扩展与技能是同一份。**

判定两步，缺一不可：

1. **pi 侧**：这个 `Settings` 键的 getter，调用点是否**只在 `modes/interactive/**` 或 `cli/**`**（只有 TUI 读它）；
2. **App 侧**：App 自己是否读它、并实现了一个 **GUI 行为**。是 → 它是 GUI 自己的开关，不算 TUI 特有（保留）。

### 9.1 逐行处置（第一批判为 TUI-only 的键）

| pi 键 | pi 侧读者 | App 侧 | 处置 |
|---|---|---|---|
| `autocompleteMaxVisible` | `interactive-mode.ts:557,1958,2682` | 0 处 | 删除（§1.2 同一批） |
| `branchSummary.skipPrompt` | `interactive-mode.ts:5236` | 0 处 | 删除 |
| `markdown.codeBlockIndent` | `interactive-mode.ts:1261` | 0 处 | 删除 |
| `collapseChangelog` | `interactive-mode.ts:766,4583` | 0 处 | 删除（`/changelog` 那个动作行也一并删，见 10.2） |
| `doubleEscapeAction` | `interactive-mode.ts:2863,4585` | 0 处 | 删除 |
| `editorPaddingX` | `interactive-mode.ts:556,1957` | 0 处 | 删除 |
| `fullscreenCopyOnSelect` | `interactive-mode.ts:539,1944` | 0 处 | 删除 |
| `fullscreenExitOutput` | `interactive-mode.ts:6601` | 0 处 | 删除 |
| `fullscreenScrollbar` | `interactive-mode.ts:884,1936` | 0 处 | 删除 |
| `terminal.imageWidthCells` | `interactive-mode.ts:3258,4567` | 0 处 | 删除 |
| `lastChangelogVersion` | `interactive-mode.ts:1218` | 0 处 | 删除（App 给 pi 设了 `PI_SKIP_VERSION_CHECK=1`，`PiEngineHost.kt:310`） |
| `markdown.mermaid` | `interactive-mode.ts:440,4582` | 0 处 | 删除 |
| `quietStartup` | `interactive-mode.ts:859,912,1650` | 0 处 | 删除 |
| `terminal.showImages` | `interactive-mode.ts:3257,4566` | 0 处 | 删除 |
| `terminal.showTerminalProgress` | `interactive-mode.ts:843,3184` | 0 处 | 删除 |
| `treeFilterMode` | `interactive-mode.ts:5208,4586` | 0 处 | 删除（App 的会话树有自己的过滤器，不读这个键） |
| `tuiMode` | `interactive-mode.ts:523` | 0 处 | 删除（RPC 模式没有 TUI 可言） |
| `outputPad` | `interactive-mode.ts:574,1950` | 0 处（复核过：App 侧那 2 个文件里是 Compose 的 padding 令牌，不是这个键） | 删除 |
| `warnings.anthropicExtraUsage` | `interactive-mode.ts:4909` | 0 处（复核过：另外 4 个文件命中的是普通英文词 warning/warnings） | 删除 |
| `externalEditor` | `interactive-mode.ts:2628,4247` | **0 处** | **删除**，与"保留"名单不同，理由见 10.3 |

### 9.2 三个"指路牌"动作行

| 行 | 处置 | 理由 |
|---|---|---|
| `app.sessions.import` | **删除** | `/import` 只有 TUI 有（`interactive-mode.ts:6107-6122`），RPC 无对应命令（`rpc-types.ts:20-74`）。一行只能说"去别处"，不是设置 |
| `app.about.changelog` | **删除** | 同上（`/changelog`，`interactive-mode.ts:3022-3025`），且与 `lastChangelogVersion`/`collapseChangelog` 是同一类 TUI 事务 |
| `app.credentials.oauth` | **保留（例外）** | 理由不是"能力"，是**发现性**：OAuth 是订阅制登录的唯一入口，而 App 自己的凭证表单只写 API key（`PiCredentialScreen.kt:52-58`），没有这一行用户**根本不会知道** pi 支持订阅登录。文案已写明"在原版 TUI 里"，点击后切到终端并提示 `/login`（`PiRoot.kt` 的 `onRunAction`） |

`WorkbenchScreen.kt` 的终端段现在有**唯一一句**发现性说明（原文）：
「输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。」
——设置里不再有任何同类提示；上一批散落的（`app.localModels.manage` 的"管理本地模型请在终端里做"、
`ChatScreen` 外部编辑器失败提示里的"设置 →「外部编辑器」"）都已随行删除或改写。

### 9.3 `externalEditor` 为什么删（与交叉核对的"保留"不同）

交叉核对的第二步把 `externalEditor` 归入"App 自己实现了 → 保留"，依据是 App 确实有一个外部编辑器功能。
复核后的结论是**删**，因为**判定第二步要求 App 读这个键**：

- pi 侧：这个键只被 TUI 的 Ctrl+G 读（`interactive-mode.ts:2628` 取命令、`:4247` 真正启动），
  它是一条**外部命令字符串**（`SM:969` `getExternalEditorCommand`，回退 `$VISUAL`/`$EDITOR`）。
- App 侧：`ChatScreen.openInExternalEditor`（`ChatScreen.kt:266-277`）走的是 Android 的 `ACTION_EDIT`，
  启动系统里能编辑文本的应用，**完全不读这个键**（全树 grep `"externalEditor"` 在 App 源码里 0 命中）。
  它是 App 自己的一件等价功能，不是对这个设置的消费。
- 结论：留着这一行就是"一个 TUI 旋钮 + 一个说明"，正是本轮要清掉的东西；而 App 的外部编辑器照旧可用。
- **复核人同意按此判定（删）**：判定第二步是硬条件——App 不读该键，就不算"GUI 自己的开关"；
  不为了与名单一致而把它加回来。它的失败提示也一并改了（`ChatScreen.kt:266-277`），
  不再指向一个已删除的设置行。

### 9.4 其余两问

- **`tuiOnlyExtensions` / `tuiOnlyMarkers`（`ui/chat/TuiOnlyScan.kt`，`PiSessionViewModel.kt:1705`）：保留。**
  它不是 TUI 旋钮，而是**关于已安装资源的事实**：pi 在 RPC 模式下把 `ctx.ui.custom()`、`setFooter`、
  `onTerminalInput` 等做成静默 no-op（`rpc-mode.ts:163-166`、`:179-231`、`:273-311`），
  扩展自己又察觉不到（`ctx.hasUI` 仍为 true），于是"这个扩展为什么没反应"在 GUI 里无从回答。
  列表说的是"某个扩展需要终端"，而不是"设置里再放一个开关"；它也只出现在对话页的扩展清单里。
- **`enableSkillCommands`：保留，且它已经是 App 自己实现的。**
  证据：`PiSessionViewModel.kt:1636` 读它来构建 `piCommandPalette`，`:561-566` 在写后刷新命令表；
  也就是说 RPC 下"技能作为斜杠命令"这件事是 App 的命令面板在做（`docs/known-gaps.md` 记过这条），
  不是照抄 TUI 语义。同理保留的还有 `hideThinkingBlock`（App 是渲染方）、`showCacheMissNotices`
  （App 打印摘要计费行）、`theme`（App 的调色板来自主题文件）。
- **设置的动作行现在剩 6 个**：`app.credentials.apiKey`、`app.credentials.oauth`、`app.localModels.manage`、
  `app.compaction.runNow`、`app.security.emergencyStop`、`app.runtime.restartEngine`。

### 9.5 原则执行后的自检口径

`settings-audit` 现在同时守住这条原则的两头：注册键要么有 App 消费者、要么在 `piOwnedKeys` 里声明
pi 读它（§6）。**但 `piOwnedKeys` 不能成为 TUI 键的后门**——它里面的 23 个键，每一个的 pi 读者都在
`sdk.ts`/`agent-session.ts`/`main.ts`/`package-manager.ts`/`telemetry.ts`（进程或会话级），
没有一个是"只有 `interactive-mode.ts` 读"。这条判据是人工复核的（§1.1 每一行都写了读取点），
没有做成自动断言：审计是纯文本扫描，读不懂 TypeScript 的调用图。

### 9.6 边界：设置面与命令面不是一类界面

原则只作用于**设置面**。pi 的**命令面**（斜杠命令面板，`PiSlashCommands.kt` / `SlashPalette.kt` /
`notifyTerminalOnly`）保留 `PiCommandAction.TerminalOnly` 那一批（`/share`、`/changelog`、`/hotkeys`、
`/trust`、`/login`、`/logout`、`/reload`、`/quit`），**不删**。理由：

- **两个界面的职责不同**。命令面板回答"pi 有哪些命令"，隐掉就是**藏掉 pi 的能力**，与 1:1 的镜像关系冲突；
  设置页回答"调 GUI 自己"，TUI 旋钮才在这里被清掉。
- **保留 `app.credentials.oauth` 的那条理由在这里同样成立**：订阅登录只有 TUI 能完成，
  命令面板里删掉 `/login`，用户就更不知道它存在了。而且那里已有诚实的处置：
  徽标「仅终端」+ 点下去给一句"在原版 TUI 里运行"。
- **文案按同一条原则收过**：`notifyTerminalOnly` 以前写的是"pi 的 RPC 模式没有实现 /xxx"——那是内部协议解释，
  已改成只讲后果与去处（`"/xxx 要在原版 TUI 里运行：工作区 → 终端。"`），并且**不声称已经切过去**
  （命令面板这一路并不导航，只有设置里的 OAuth 例外行才会 `requestNav(NavRequest.Workbench)`）。
  结构没动，只动了这一句与它的 KDoc。

一句话记法：**设置面清 TUI 旋钮；命令面保留，因为它表达的是 pi 有什么能力。**
下一轮不要"顺手删干净"命令面板里的这一批。

---

## 10. 自检（以及"本机不编译"这条约束）

用户裁决：**这台机器就是用户手机，不要在本机跑 kotlinc**（typecheck 与 pure-checks 都很重），
验证连同别的改动一起交给 CI。所以本轮的自检口径分成"已在本机取证"与"必须在 CI 完成"两栏。

**本机已取证：**

- `python3 tools/check-nested-comments.py` → `OK`（0 处嵌套注释；169 个 Kotlin 文件，含本轮新增的审计 harness）。
- **`settings-audit` 的判据在最终树上用等价脚本复核过**（Python 逐条重放 harness 的五条断言，读的是同一份
  `PiSettingsRegistry.kt` 文本）：`67 registered keys (41 pi, 26 app): 44 read by this app, 23 declared pi-owned`，
  `0` 个无读者、`0` 个过期白名单、`0` 个重复键、`0` 个 `app.*` 进白名单、9 个搜索提示 chip 全部可解析。
  最后一版 harness 还加了一条"行区域里先剥掉注释"的修正——注释里提到已删的 `app.sessions.import`
  会让 `导入` chip 假通过，这正是它抓到的第一例。
- **真实 harness 在本轮较早的树上是跑通过的**：`bash tools/run-app-pure-checks.sh` 输出
  `pure-checks: OK — settings-audit`（那一刻的注册表是 67 键 / 44 read / 24 owned / 2 slash hints）。
  之后 harness 增加了 chip 检查、注册表又经过用户裁决的两批删除，**需要 CI 再跑一次**。
- 结构自检（不是编译）：本轮改过的 5 个文件做过去字符串/注释的括号平衡检查，全部为 0；改动均通过
  `edit` 工具的"读后写 + 唯一匹配"完成，其中一次被并发写挡下（工具报 file changed），说明并发保护有效。
- 一次历史证据（改动之前的树）：`bash tools/typecheck.sh` → `:rpc 0`、`:app 1`，那一条是**别人的在制品**
  `app/src/main/kotlin/app/pi/packages/PiPackagesScreen.kt` 的重复 `@Composable`（该文件随后被其作者修掉）。

**必须由 CI 完成（本机不跑）：**

1. `bash tools/typecheck.sh` → 期望 `:rpc 0 / :app 0`。本轮手改了 5 个 Kotlin 文件
   （`PiSettingsRegistry.kt`、`PiRoot.kt`、`WorkbenchScreen.kt`、`SettingsSearchScreen.kt`、`ChatScreen.kt`）
   与 1 个 harness；风险点是新增的 `MaterialTheme`/`dp` 导入与注释块内的编辑，都已逐行复核。
2. `bash tools/run-app-pure-checks.sh` → 期望 8 项全过（`settings-audit` 是新增项）。
3. `docs/settings-review.md` §7 列的四个真机判据。

**未改**（规矩）：`/root/pi-src`（只读）、载荷、`docs/known-gaps.md`。没有 git 写操作、没有 Gradle、没有设备操作。
一次干扰记在这里：本轮第一次 `run-app-pure-checks.sh` 与另一位代理对 `tools/run-app-pure-checks.sh`
的编辑撞在一起（06:40:46 对方加了 `PiResourceDiscovery.kt` 一行），bash 按字节偏移读脚本时读到错位内容，
日志里出现一堆 `line 312: xxx: command not found`；重跑即正常。共享脚本的并发编辑会让任何在跑的长任务留下这种假故障。
