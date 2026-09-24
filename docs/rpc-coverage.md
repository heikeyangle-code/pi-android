# pi `--mode rpc` 协议覆盖审查（pi 0.85.1 ↔ app.pi）

审查对象：

| | 值 |
|---|---|
| pi（权威） | `/root/pi-src` @ `bbb61e34`，`packages/coding-agent` 0.85.1 |
| App | `/root/pi-android`，本文件写作时的工作树（含其他作者未提交的改动） |
| 协议定义 | `packages/coding-agent/src/modes/rpc/rpc-types.ts`（类型联合）、`modes/rpc/rpc-mode.ts`（实现）、`modes/json-event.ts`（事件序列化）、`docs/rpc.md` |
| App 协议层 | `rpc/src/main/kotlin/app/pi/rpc/{Commands,Events,Responses}.kt` |
| App 调用层 | `app/src/main/kotlin/app/pi/engine/{PiEngineApi,PiEngineSession}.kt` |
| App 界面层 | `app/src/main/kotlin/app/pi/ui/**`、`ui/extension/**` |

方法：**每个符号都用 `grep` 在树上核对**，不引用 `docs/extension-compatibility.md` /
`docs/gap-disposition.md` 的结论（这两个账本在本仓库有过“说没做、其实做了”的记录）。
本文件里每一条 App 证据都是命令行 grep 出来的 `file:line`。

---

## 0. 先独立复核“协议面有多大”——题面给的数字有两个是错的

用脚本从 pi 源码里数（不是从文档抄）：

| 题面说法 | 实测 | 依据 |
|---|---|---|
| `RpcCommand` 32 个 | **33 个** | `rpc-types.ts:20-74` 的联合体里有 33 个 `type:` 字面量。题面自己列出的名字数一遍也是 33 个，只是数字写成了 32。App 的 `Commands.kt` KDoc 写“33”是对的 |
| 扩展 UI 请求方法 8 个 | **9 个** | `rpc-types.ts:246-281`：`select confirm input editor notify setStatus setWidget setTitle **set_editor_text**`。题面漏了 `set_editor_text`，而 `rpc-mode.ts:238-246` 确实会把它发到 stdout，`docs/rpc.md:1339-1350` 也把它列为 fire-and-forget 方法之一 |
| 事件类型（题面未给数） | 服务端事件 **24** 个 + `response` + `extension_ui_request` = **26** 个 stdout 记录类型；另加 `message_update` 内层 **12** 个 delta | 见 §3 |

三个“不属于命令方向”的忽略项也对不上：`RpcCommand` 联合体里本来就**没有**这三个成员——`response`/`extension_ui_request`/`extension_ui_response` 是各自独立的联合体（`rpc-types.ts:116-239`、`:246-281`、`:288-291`），不是从 33 里减 3。

**结论：App 侧 33 个命令、9 个方法、26 个事件记录类型、12 个 delta，全部都有解析与处理。** 协议面没有“漏了一整项”的缺口；缺口都在“落在哪张界面上”和“有没有按 pi 的语义用对”，见 §1–§4。

---

## 1. 命令表（33/33）

`App 支持` 的判定：`:rpc` 里有 builder + `PiEngineApi`/`PiEngineSession` 里有封装 = `完整`；
只有 builder、没有封装 = `部分`。`是否需要 UI` 的判据是用户能不能“看见 / 触发 / 知道结果”。

| 协议项 | pi 侧 | App 支持 | App 证据 | 是否需要 UI | 缺口与处置 |
|---|---|---|---|---|---|
| `prompt` | `rpc-types.ts:22`；`rpc-mode.ts:394-416` | 完整 | `Commands.kt:46`；`PiEngineSession.kt:585-592`；`PiSessionViewModel.kt:1742`（普通发送）、`:1791`（模板/技能） | 已经有 | `pi 有 rpc-types.ts:22`；一致。**本次修**：回合中选模板/技能原来不带 `streamingBehavior`，pi 直接拒绝（见 §5 改动 2） |
| `steer` | `rpc-types.ts:23`；`rpc-mode.ts:418-421` | 完整 | `Commands.kt:59`；`PiSessionViewModel.kt:1735-1741` | 已经有 | `pi 有 rpc-types.ts:23`；一致。App 用 `steer` 命令而不是 `prompt{streamingBehavior:"steer"}`，对纯文本等价（`agent-session.ts:1425-1435` 同样展开技能/模板） |
| `follow_up` | `rpc-types.ts:24`；`rpc-mode.ts:423-426` | 完整 | `Commands.kt:75`；`PiSessionViewModel.kt:1827-1833`；UI 由“后续”chip 触发（`ChatScreen.kt:1002-1017`） | 已经有 | `pi 有 rpc-types.ts:24`；一致（含图片） |
| `abort` | `rpc-types.ts:25`；`rpc-mode.ts:428-431` | 完整 | `Commands.kt:86`；`PiEngineSession.kt:705`（`stopAndDrainQueue`）；`PiEngineApi.kt:269`（无 UI 消费者，走引擎层那条） | 已经有 | `pi 有 rpc-types.ts:25`；一致。入口是 Stop / Escape（`ChatScreen.kt:1050`） |
| `clear_queue` | `rpc-types.ts:26`；`rpc-mode.ts:433-435` | 完整 | `Commands.kt:88`；`PiEngineSession.kt:701-712`；`PiEngineApi.kt:198` | 已经有 | `pi 有 rpc-types.ts:26`；一致。**本次修**：队列文本原来从 `get_state` 读，恒为空（见 §5 改动 1） |
| `new_session` | `rpc-types.ts:27`；`rpc-mode.ts:437-444` | 完整 | `Commands.kt:91`；`PiEngineApi.kt:297`；`PiSessionViewModel.kt:2067-2076`；UI：`ChatScreen.kt:531,611`、`SessionsScreen.kt:205,233` | 已经有 | `pi 有 rpc-types.ts:27`；一致。`cancelled`（`session_before_switch` 否决）会提示用户 |
| `get_state` | `rpc-types.ts:30`；`rpc-mode.ts:450-466` | 完整 | `Commands.kt:99`；`PiEngineApi.kt:58`；`PiSessionViewModel.kt:1556-1573`；另有 `PiEngineSession.kt:345`（就绪探针）、`:743-748`（收尾时用 `isStreaming`/`isCompacting` 判断要不要先 abort） | 已经有 | `pi 有 rpc-types.ts:30`；一致。`isCompacting`/`pendingMessageCount` 被解析但只有 `isStreaming`/`isCompacting` 在引擎收尾路径用（`PiEngineSession.kt:743-748`）；UI 的“正在压缩”来自 `compaction_start`/`end` 事件而不是这个字段 |
| `set_model` | `rpc-types.ts:33`；`rpc-mode.ts:472-480` | 完整 | `Commands.kt:105`；`PiEngineApi.kt:137`；`PiSessionViewModel.kt:1879-1893`；UI：`ChatScreen.kt:1070-1073`（模型选择器） | 已经有 | `pi 有 rpc-types.ts:33`；一致 |
| `cycle_model` | `rpc-types.ts:34`；`rpc-mode.ts:482-488` | 完整（协议 + 封装） | `Commands.kt:142`；`PiEngineApi.kt:149-153` | **不需要**（有理由，已复核于 `5a49bdc` 工作树） | **一致或有意偏离**。全树没有调用者：`PiSessionViewModel` 里没有这个函数，`ChatScreen` 也没有模型行点击入口——溢出菜单里那个入口按用户裁决删除了（理由写在 `Commands.kt:133-141` 的 KDoc：循环是键位习惯，而模型选择器已经列出全部模型）。本行原来写的 `PiSessionViewModel.kt:1896-1911` / `ChatScreen.kt:659` 已随该裁决消失，**这一句是过期证据，本次按代码改正**。响应里的 `isScoped` 仍被解析但无处展示（内部状态，无 UI 义务） |
| `get_available_models` | `rpc-types.ts:35`；`rpc-mode.ts:490-493` | 完整 | `Commands.kt:114`；`PiEngineApi.kt:108`；`PiSessionViewModel.kt:1593-1597`；UI：`ChatScreen.kt:506,575,672,849,1074` | 已经有 | `pi 有 rpc-types.ts:35`；一致 |
| `set_thinking_level` | `rpc-types.ts:38`；`rpc-mode.ts:499-502` | 完整 | `Commands.kt:118`；`PiEngineApi.kt:158`；`PiSessionViewModel.kt:1857-1859`；UI：`ChatScreen.kt:1081` | 已经有 | `pi 有 rpc-types.ts:38`；一致。显示值以 `thinking_level_changed` 为准（pi 会钳制） |
| `cycle_thinking_level` | `rpc-types.ts:39`；`rpc-mode.ts:504-510` | 完整 | `Commands.kt:124`；`PiEngineApi.kt:169`；`PiSessionViewModel.kt:1862-1876`；UI：`ChatScreen.kt:668,992` | 已经有 | `pi 有 rpc-types.ts:39`；一致 |
| `get_available_thinking_levels` | `rpc-types.ts:40`；`rpc-mode.ts:512-515` | 完整 | `Commands.kt:126`；`PiEngineApi.kt:121`；`PiSessionViewModel.kt:1583-1590`；UI：`ChatScreen.kt:510,663,1079` | 已经有 | `pi 有 rpc-types.ts:40`；一致 |
| `set_steering_mode` | `rpc-types.ts:43`；`rpc-mode.ts:521-524` | 完整 | `Commands.kt:131`；`PiEngineApi.kt:178`；`PiSessionViewModel.kt:1920-1925`；UI：`ChatScreen.kt:1087` | 已经有 | `pi 有 rpc-types.ts:43`；一致 |
| `set_follow_up_mode` | `rpc-types.ts:44`；`rpc-mode.ts:526-529` | 完整 | `Commands.kt:137`；`PiEngineApi.kt:186`；`PiSessionViewModel.kt:1927-1932`；UI：`ChatScreen.kt:1088` | 已经有 | `pi 有 rpc-types.ts:44`；一致 |
| `compact` | `rpc-types.ts:47`；`rpc-mode.ts:535-538` | 完整 | `Commands.kt:145`；`PiEngineApi.kt:212`；`PiSessionViewModel.kt:1939-1947`；UI：`ChatScreen.kt:532,1092-1093`、`PiRoot.kt:234` | 已经有 | `pi 有 rpc-types.ts:47`；一致（含 `customInstructions`） |
| `set_auto_compaction` | `rpc-types.ts:48`；`rpc-mode.ts:540-543` | 完整 | `Commands.kt:151`；`PiEngineApi.kt:220`；`PiSessionViewModel.kt:1949-1954`；UI：`ChatScreen.kt:1089` | 已经有 | `pi 有 rpc-types.ts:48`；一致 |
| `set_auto_retry` | `rpc-types.ts:51`；`rpc-mode.ts:549-552` | 完整 | `Commands.kt:159`；`PiEngineApi.kt:230`；`PiSessionViewModel.kt:1956-1961`；UI：`ChatScreen.kt:1090` | 已经有 | `pi 有 rpc-types.ts:51`；一致。初值从 pi 的 `settings.json` 读回（`PiSessionViewModel.kt:1703-1707`），因为 `get_state` 没有这个字段 |
| `abort_retry` | `rpc-types.ts:52`；`rpc-mode.ts:554-557` | 完整 | `Commands.kt:165`；`PiEngineApi.kt:238`；`PiSessionViewModel.kt:1964-1966`；UI：`ChatScreen.kt:1091` | 已经有 | `pi 有 rpc-types.ts:52`；一致 |
| `bash` | `rpc-types.ts:55`；`rpc-mode.ts:563-584` | 完整 | `Commands.kt:169`；`PiEngineApi.kt:253`；`PiSessionViewModel.kt:2015-2042`；UI：`ChatScreen.kt:1031`、`BashPanel.kt` | 已经有 | `pi 有 rpc-types.ts:55`；一致（`excludeFromContext` / 截断 / 取消都有界面） |
| `abort_bash` | `rpc-types.ts:56`；`rpc-mode.ts:586-589` | 完整 | `Commands.kt:177`；`PiEngineApi.kt:262`；`PiSessionViewModel.kt:2044-2046`；UI：`ChatScreen.kt:895`、`PiRoot.kt:244` | 已经有 | `pi 有 rpc-types.ts:56`；一致 |
| `get_session_stats` | `rpc-types.ts:59`；`rpc-mode.ts:595-598` | 完整 | `Commands.kt:181`；`PiEngineApi.kt:101`；`PiSessionViewModel.kt:1622-1626`；UI：`ChatSheets.kt:475-494` | 已经有 | `pi 有 rpc-types.ts:59`；一致（费用 / token / 上下文占用都显示） |
| `export_html` | `rpc-types.ts:60`；`rpc-mode.ts:600-603` | 完整 | `Commands.kt:184`；`PiEngineApi.kt:280`；`PiSessionViewModel.kt:2211-2231`；UI：`ChatScreen.kt:524,628,1115` | 已经有 | `pi 有 rpc-types.ts:60`；一致。`.jsonl` 分支是 App 用 `get_entries` 自己写的（RPC 无该命令），已在 `PiSessionViewModel.kt:2233-2281` 说明 |
| `switch_session` | `rpc-types.ts:61`；`rpc-mode.ts:605-611` | 完整 | `Commands.kt:190`；`PiEngineApi.kt:305`；`PiSessionViewModel.kt:2134-2149`；UI：`SessionsScreen.kt:193` | 已经有 | `pi 有 rpc-types.ts:61`；一致。pi 的拒绝（`session_before_switch` 否决、cwd 不存在）走失败响应并被提示 |
| `fork` | `rpc-types.ts:62`；`rpc-mode.ts:613-619` | 完整 | `Commands.kt:196`；`PiEngineApi.kt:320`；`PiSessionViewModel.kt:2152-2162`；UI：`ChatScreen.kt:861,1126`、`PiRoot.kt:293` | 已经有 | `pi 有 rpc-types.ts:62`；一致（`session_before_fork` 否决有提示） |
| `clone` | `rpc-types.ts:63`；`rpc-mode.ts:621-631` | 完整 | `Commands.kt:202`；`PiEngineApi.kt:333`；`PiSessionViewModel.kt:2165-2176`；UI：`ChatScreen.kt:521,620,1110` | 已经有 | `pi 有 rpc-types.ts:63`；一致 |
| `get_fork_messages` | `rpc-types.ts:64`；`rpc-mode.ts:633-636` | 完整 | `Commands.kt:204`；`PiEngineApi.kt:85`；`PiSessionViewModel.kt:1645-1649`；UI：`ChatScreen.kt:518,615,1106` | 已经有 | `pi 有 rpc-types.ts:64`；一致 |
| `get_entries` | `rpc-types.ts:65`；`rpc-mode.ts:638-649` | 完整 | `Commands.kt:211`；`PiEngineApi.kt:73`；`PiSessionViewModel.kt:1526`（重放）、`:1632`（树）、`:2229`（导出） | 已经有 | `pi 有 rpc-types.ts:65`；一致。可选的 `since` 游标已贯通到 `PiCommands.getEntries`/`PiEngineApi.getEntries`，但三个调用点都不传（全量重放）；这是性能选择，不是协议缺口 |
| `get_tree` | `rpc-types.ts:66`；`rpc-mode.ts:651-654` | 完整 | `Commands.kt:217`；`PiEngineApi.kt:81`；`PiSessionViewModel.kt:1636-1642`；UI：`ChatScreen.kt:514,601,853,1101`、`PiRoot.kt:137,296` | 已经有 | `pi 有 rpc-types.ts:66`；一致（`SessionTreeScreen`） |
| `get_last_assistant_text` | `rpc-types.ts:67`；`rpc-mode.ts:656-659` | 完整 | `Commands.kt:219`；`PiEngineApi.kt:95`；`PiSessionViewModel.kt:2327-2345`；UI：`ChatScreen.kt:1255`（`/copy`） | 已经有 | `pi 有 rpc-types.ts:67`；一致。`text: null` 按 pi 的文档当正常答案 |
| `set_session_name` | `rpc-types.ts:68`；`rpc-mode.ts:661-668` | 完整 | `Commands.kt:221`；`PiEngineApi.kt:345`；`PiSessionViewModel.kt:2183-2191`；UI：`ChatScreen.kt:1133`（`/name`） | 已经有 | `pi 有 rpc-types.ts:68`；一致。空名字由 pi 拒绝，错误会显示 |
| `get_messages` | `rpc-types.ts:71`；`rpc-mode.ts:674-676` | 完整（协议 + 封装） | `Commands.kt:101`；`Responses.kt:453-455`（函数在 `:454`）；`PiEngineApi.kt:62` | **不需要**（有理由） | **一致或有意偏离**。全树没有 UI 调用点：App 的对话流以 `get_entries` 的会话记录为准（`TranscriptReducer.seedFromHistory`），那才是 pi 自己 TUI 也画的那份；`get_messages` 返回的是进程内 `session.messages`，在压缩后与记录不同，用它替换会**丢掉历史**。因此不做界面，且不在本文件里把它算成缺口。`PiMessage` 类型本身仍被 `SessionEntries.kt` 用来解析记录里的消息 |
| `get_commands` | `rpc-types.ts:74`；`rpc-mode.ts:682-713` | 完整 | `Commands.kt:229`；`PiEngineApi.kt:128`；`PiSessionViewModel.kt:1610-1619`；UI：`PiSlashCommands.kt:199-224`、`ChatScreen.kt:907-930` | 已经有 | `pi 有 rpc-types.ts:74`；一致。扩展命令、模板、技能（`skill:`）都进 `/` 面板，带 `sourceInfo` 来源标记 |

**命令小结**：33/33 有 builder，33/33 有调用层封装，**31/33 有用户可见入口**。两条**从不发送**，理由不同：`get_messages` 是"用了会丢历史"（见本行），`cycle_model` 是溢出菜单入口按用户裁决删除（见本行）。两条都保留 builder 与封装：`:rpc` 是 pi 命令面的完整转录，去掉就再也无法声明"协议面 1:1"。

---

## 2. 扩展 UI 方法表（9 个请求 + 1 个应答）

| 协议项 | pi 侧 | App 支持 | App 证据 | 是否需要 UI | 缺口与处置 |
|---|---|---|---|---|---|
| `select` | `rpc-types.ts:247`；`rpc-mode.ts:137-140` | 完整 | `Events.kt:550`；`PiSessionViewModel.kt:1160-1195`；`ExtensionDialogs.kt:81-130` | 已经有 | `pi 有 rpc-types.ts:247`；一致。选项列表点一下即答，取消 = `cancelled:true` |
| `confirm` | `rpc-types.ts:248`；`rpc-mode.ts:142-145` | 完整 | 同上；`ExtensionDialogs.kt:141-166` | 已经有 | `pi 有 rpc-types.ts:248`；一致（允许 / 拒绝 / 超时默认 false） |
| `input` | `rpc-types.ts:249-256`；`rpc-mode.ts:147-150` | 完整 | 同上；`ExtensionDialogs.kt:171-210` | 已经有 | `pi 有 rpc-types.ts:249-256`；一致 |
| `editor` | `rpc-types.ts:257`；`rpc-mode.ts:254-271` | 完整 | 同上；`ExtensionDialogs.kt:220-256` | 已经有 | `pi 有 rpc-types.ts:257`；一致。pi 侧**没有超时**（签名里根本没有 `opts`），所以 App 没有倒计时可显示；界面关闭 / 引擎死亡 / ViewModel 销毁时一律回 `cancelled`（`PiSessionViewModel.kt:1400-1411,1429-1436`），这是唯一能避免 pi 永久挂起的动作 |
| `notify` | `rpc-types.ts:258-264`；`rpc-mode.ts:152-161` | 完整 | `Events.kt:550`；`PiSessionViewModel.kt:1198-1204`；`ExtensionUiHost.kt:64-77`、`ExtensionUi.kt:236-240` | 已经有 | `pi 有 rpc-types.ts:258-264`；一致（`notifyType` 映射成三档语气，未知按 info） |
| `setStatus` | `rpc-types.ts:265-271`；`rpc-mode.ts:168-177` | 完整 | `PiSessionViewModel.kt:1238-1249`；`ExtensionChrome.kt:47-79` | 已经有 | `pi 有 rpc-types.ts:265-271`；一致（按 key 增删，`statusText` 空 = 清除） |
| `setWidget` | `rpc-types.ts:272-279`；`rpc-mode.ts:195-208` | 完整 | `PiSessionViewModel.kt:1258-1273`；`ExtensionChrome.kt:90-126`；`ChatScreen.kt:889,1059` | 已经有 | `pi 有 rpc-types.ts:272-279`；一致（只支持字符串行——pi 在 RPC 下丢弃组件工厂；未知 placement 按 pi 文档的默认 `aboveEditor`） |
| `setTitle` | `rpc-types.ts:280`；`rpc-mode.ts:218-226` | 完整 | `PiSessionViewModel.kt:1214-1216`；`ExtensionChrome.kt:129`；`ChatScreen.kt` AppBar | 已经有 | `pi 有 rpc-types.ts:280`；`一致或有意偏离`：pi 的字段语义是**终端窗口标题**，App 拿来当标题栏文字是解释而非契约，已在 `ExtensionChrome.kt:128` 注明 |
| `set_editor_text` | `rpc-types.ts:281`；`rpc-mode.ts:238-246` | 完整 | `PiSessionViewModel.kt:1218-1226,1327-1332`；`UiState.composerFill` | 已经有 | `pi 有 rpc-types.ts:281`；一致。一次写入、带序号消费，避免回到对话页时重复覆盖用户已输入的内容 |
| `extension_ui_response`（应答） | `rpc-types.ts:288-291`；`rpc-mode.ts:769-782` | 完整 | `Commands.kt:237,243,249`；`PiSessionViewModel.kt:1290-1325,1448-1461` | 已经有 | `pi 有 rpc-types.ts:288-291`；一致。按 id 一一对应、每个 id 只答一次、答案类型与方法匹配（`value` 给 confirm 会被本地拒绝） |

**方法小结**：9/9 有界面落点，`extension_ui_response` 三种形状都实现。RPC 模式下不可达的 `ExtensionUIContext` 成员（`custom`、`setFooter`、`setWorkingMessage`、`getEditorText`、主题三件套、`setToolsExpanded` 等）**不是协议面**——pi 在 `rpc-mode.ts:163-311` 里显式 no-op 或返回假值，没有上线通道；App 用 `ui/chat/TuiOnlyScan.kt` 扫描扩展源码并提示用户改用终端页，这是能做的最诚实处理。逐条对照见 §4。

---

## 3. 事件表（26 个 stdout 记录类型 + 12 个 delta，全解析）

事件如何序列化：`rpc-mode.ts:355-360` 把 `session.subscribe` 的每个事件交给 `toJsonEvent`；
`modes/json-event.ts:46-60` 除了 `message_update` 去掉累计快照 `partial`/`message` 之外原样透传。
所以**只有 `session.subscribe` 能收到的事件**才可能到 App，扩展专属事件（`session_start`、`input`、`tool_call` 等）不在此列。

### 3.1 顶层记录类型

| 协议项 | pi 侧 | App 支持 | App 证据 | 是否需要 UI | 缺口与处置 |
|---|---|---|---|---|---|
| `agent_start` | `agent/src/types.ts:433`；`agent-loop.ts:110,139` | 完整 | `Events.kt:439`；`Transcript.kt:899-902`；`PiEngineSession.kt:392` | 已经有 | `pi 有 agent/src/types.ts:433`；一致（置 streaming） |
| `agent_end`（含 `willRetry`） | `agent-session.ts:146-150` 覆盖；`types.ts:434`；`loop:217` | 完整 | `Events.kt:440`；`Transcript.kt:903`；`PiEngineSession.kt:393` | 已经有 | `pi 有 agent-session.ts:146-150`；一致。`willRetry` 解析进 `PiEvent.AgentEnd`，重试本身另有 `auto_retry_*` 行呈现 |
| `turn_start` | `types.ts:436`；`loop:111` | 完整 | `Events.kt:444`；无投影 | 不需要（有理由） | `pi 有 types.ts:436`；一致。事件不带 turn 序号（带 `turnIndex` 的是扩展事件，不上线，`Events.kt:113-135` 已考证），对话流靠条目分隔，无需界面 |
| `turn_end` | `types.ts:437`；`loop:216,243` | 完整 | `Events.kt:445`；`Transcript.kt:928-932` | 已经有 | `pi 有 types.ts:437`；一致。只取 `toolResults.length`，用于跨天的日期分隔 |
| `message_start` | `types.ts:439`；`loop:113` | 完整 | `Events.kt:449`；`Transcript.kt:860-866` | 已经有 | `pi 有 types.ts:439`；一致。用户消息行由 `message_end` + 本地回显得到 |
| `message_update` | `types.ts:441`；`loop:337`；`json-event.ts:46-60` | 完整 | `Events.kt:450-456,592-642`；`Transcript.kt:1108+` | 已经有 | `pi 有 types.ts:441`；一致。顶层 `usage` 与 12 个 delta 都解析 |
| `message_end` | `types.ts:442`；`loop:114,204,355` | 完整 | `Events.kt:458-476`；`Transcript.kt:869-898`；`PiSessionViewModel.kt:1062` | 已经有 | `pi 有 types.ts:442`；一致。`role: "custom"`（扩展注入上下文）、`role: "user"` 图片、`stopReason` 失败行都投影 |
| `tool_execution_start` | `types.ts:444`；`loop:386` | 完整 | `Events.kt:478`；`Transcript.kt:914` | 已经有 | `pi 有 types.ts:444`；一致 |
| `tool_execution_update` | `types.ts:445`；`loop:695` | 完整 | `Events.kt:484`；`Transcript.kt:915`（含 200ms 节流） | 已经有 | `pi 有 types.ts:445`；一致 |
| `tool_execution_end` | `types.ts:446`；`loop:776` | 完整 | `Events.kt:490-500`；`Transcript.kt:916` | 已经有 | `pi 有 types.ts:446`；一致（含图片结果与 `details`） |
| `agent_settled` | `agent-session.ts:151,629` | 完整 | `Events.kt:441`；`Transcript.kt:904-912`；`PiEngineSession.kt:393`；`PiSessionViewModel.kt:1125-1138` | 已经有 | `pi 有 agent-session.ts:629`；一致。App 在这一刻刷新状态 / 命令 / 统计 |
| `queue_update` | `agent-session.ts:152-156,593-596` | 完整 | `Events.kt:507`；`PiSessionViewModel.kt:1048-1051`；`ChatScreen.kt:884-885` | 已经有 | `pi 有 agent-session.ts:593-596`；一致（队列条数显示；文本由本地回显行可见） |
| `compaction_start` | `agent-session.ts:157,1970,2290` | 完整 | `Events.kt:512`；`Transcript.kt:1542-1553` | 已经有 | `pi 有 agent-session.ts:1970`；一致 |
| `compaction_end` | `agent-session.ts:161-168,2084-2434` | 完整 | `Events.kt:513-521`；`Transcript.kt:1555-1604` | 已经有 | `pi 有 agent-session.ts:161-168`；一致（手动/阈值/溢出、aborted、errorMessage、`result.summary` 全投影；断线重连时合成收尾行） |
| `entry_appended` | `agent-session.ts:158,2620` | 完整 | `Events.kt:576-583`；`Transcript.kt:977` | 已经有 | `pi 有 agent-session.ts:2620`；一致。pi 只在扩展 `appendEntry` 这条路上发它，App 直接投影整个 entry，不等重读 |
| `session_info_changed` | `agent-session.ts:159,3116` | 完整 | `Events.kt:585`；`PiSessionViewModel.kt:1097-1099` | 已经有 | `pi 有 agent-session.ts:3116`；一致 |
| `thinking_level_changed` | `agent-session.ts:160,1830` | 完整 | `Events.kt:586`；`PiSessionViewModel.kt:1086-1093`；`Transcript.kt:918-921` | 已经有 | `pi 有 agent-session.ts:1830`；一致。这是“pi 实际生效的等级”，比命令响应更权威 |
| `auto_retry_start` | `agent-session.ts:169,2933` | 完整 | `Events.kt:523`；`Transcript.kt:934-944` | 已经有 | `pi 有 agent-session.ts:2933`；一致 |
| `auto_retry_end` | `agent-session.ts:170,701,1128,2955` | 完整 | `Events.kt:530`；`Transcript.kt:946-957` | 已经有 | `pi 有 agent-session.ts:2955`；一致（失败才落错误行） |
| `summarization_retry_scheduled` | `agent-session.ts:171-177,2893` | 完整 | `Events.kt:536`；`Transcript.kt:983-1007` | 已经有 | `pi 有 agent-session.ts:2893`；一致 |
| `summarization_retry_attempt_start` | `agent-session.ts:178-183,2902` | 完整 | `Events.kt:543`；`Transcript.kt:1009-1038` | 已经有 | `pi 有 agent-session.ts:2902`；一致（`source: compaction\|branchSummary`） |
| `summarization_retry_finished` | `agent-session.ts:184,2908` | 完整 | `Events.kt:548`；`Transcript.kt:1040-1053` | 已经有 | `pi 有 agent-session.ts:2908`；一致 |
| `bash_execution_update` | `agent-session.ts:185,3027` | 完整 | `Events.kt:502-505`；`PiSessionViewModel.kt:1113-1119`；`BashPanel.kt` | 已经有 | `pi 有 agent-session.ts:3027`；一致。`id` 被解析但未用于多命令关联（pi 一次只跑一条 bash），无 UI 义务 |
| `extension_error` | `rpc-mode.ts:348-350`（**不是** session 事件，由错误监听器直接输出）；字段来源 `runner.ts:851-882` | 完整 | `Events.kt:570-574`；`Transcript.kt:959-967`；`PiSessionViewModel.kt:1071-1078` | 已经有 | `pi 有 rpc-mode.ts:348-350`；**本次修**：`extensionPath` 原来解析后没人用，失败无法归因；现在标题与通知都点名扩展（见 §5 改动 3）。`event`（pi 钩子名）按 pi 自己 TUI 的做法不展示（`interactive-mode.ts:2827-2828` 只打印路径与错误） |
| `response`（应答信封） | `rpc-types.ts:116-239`；`rpc-mode.ts:64-77,386-720` | 完整 | `Events.kt:431-437`；`PiResponses` 全部 reader；`PiEngineSession.kt:377-389,547-557` | 已经有 | `pi 有 rpc-types.ts:116-239`；一致。id 关联、`success:false`、解析失败（`command:"parse"`）都按失败处理 |
| `extension_ui_request` | `rpc-types.ts:246-281`；`rpc-mode.ts:129` | 完整 | `Events.kt:550-568`；`PiSessionViewModel.kt:1160-1266` | 已经有 | `pi 有 rpc-types.ts:246-281`；一致（见 §2） |
| 未知类型 | —（新版本 pi 的兜底） | 完整 | `Events.kt:589` → `PiEvent.Unknown`；`Transcript.kt:1068-1101` | 已经有 | `一致`：未知事件落一行“当前版本不认识”的提示，不空白、不崩 |

### 3.2 `message_update.assistantMessageEvent` 的 12 个 delta

来源 `packages/ai/src/types.ts:546-562`；App 解析 `Events.kt:592-642`；投影 `Transcript.kt:1108+`。

| delta | pi 侧 | App 支持 | App 证据 | 是否需要 UI | 缺口与处置 |
|---|---|---|---|---|---|
| `start` | `types.ts:547` | 完整 | `Events.kt:595` | 已经有 | `pi 有 types.ts:547`；一致 |
| `text_start` | `types.ts:548` | 完整 | `Events.kt:596` | 已经有 | `pi 有 types.ts:548`；一致 |
| `text_delta` | `types.ts:549` | 完整 | `Events.kt:597` | 已经有 | `pi 有 types.ts:549`；一致 |
| `text_end` | `types.ts:550` | 完整 | `Events.kt:601` | 已经有 | `pi 有 types.ts:550`；一致（`content` 是权威文本，丢包也能补全） |
| `thinking_start` | `types.ts:551` | 完整 | `Events.kt:605` | 已经有 | `pi 有 types.ts:551`；一致 |
| `thinking_delta` | `types.ts:552` | 完整 | `Events.kt:606` | 已经有 | `pi 有 types.ts:552`；一致 |
| `thinking_end` | `types.ts:553` | 完整 | `Events.kt:612` | 已经有 | `pi 有 types.ts:553`；一致。**必需**：被脱敏的思考块不发 delta，这里是唯一文本来源 |
| `toolcall_start` | `types.ts:554`（`json-event.ts:23-30` 额外补 `id`/`toolName`） | 完整 | `Events.kt:616` | 已经有 | `pi 有 types.ts:554`；一致 |
| `toolcall_delta` | `types.ts:555` | 完整 | `Events.kt:620` | 已经有 | `pi 有 types.ts:555`；一致 |
| `toolcall_end` | `types.ts:556` | 完整 | `Events.kt:624-634` | 已经有 | `pi 有 types.ts:556`；一致 |
| `done` | `types.ts:557-561` | 完整 | `Events.kt:638` | 已经有 | `pi 有 types.ts:557-561`；一致。`docs/rpc.md:938-996` 的 delta 表没列 `start`/`done`/`error`，但实现里有；App 按实现解析 |
| `error` | `types.ts:562` | 完整 | `Events.kt:639` | 已经有 | `pi 有 types.ts:562`；一致 |

---

## 4. 扩展相关协议面：谁需要界面、现在有没有

| 协议面 | 上线通道 | App 落点 | 判定 |
|---|---|---|---|
| dialog 方法 `select`/`confirm`/`input`/`editor` | `extension_ui_request` | `ExtensionDialogs.kt` + `PiSessionViewModel.answerDialog` | 有，完整 |
| fire-and-forget 方法 `notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text` | `extension_ui_request` | `ExtensionUiHost`（snackbar）、`ExtensionChrome`（状态行/部件/标题）、composer fill | 有，完整 |
| 扩展抛错 | `extension_error` | 对话流错误行 + snackbar | 有；**本次补**：点名扩展（原来只有错误文本） |
| 扩展注册的**命令** | `get_commands` → `source:"extension"` | `/` 面板（`piCommandPalette`），`prompt("/name args")` 派发 | 有，完整 |
| 扩展注册的**工具** | 只有 `tool_execution_*` 事件带 `toolName`/`args`/`result` | 通用工具卡（参数摘要、流式输出、diff/图片结果） | 有；扩展自定义渲染（`label`、`promptSnippet`、组件）**pi 无对应物（RPC 没有通道）** |
| 扩展注册的**状态栏** | `setStatus` | `ExtensionStatusRow` | 有，完整 |
| 扩展注册的**部件** | `setWidget` | `ExtensionWidgetStack`（composer 上下方） | 有；组件工厂 pi 在 RPC 下丢弃，只有字符串行可达 |
| `session_before_switch` 否决 | `new_session`/`switch_session` 响应 `cancelled` | `PiSessionViewModel.kt:2070-2073,2142-2145` 提示 | 有，完整 |
| `session_before_fork` 否决 | `fork` 响应 `cancelled` | `PiSessionViewModel.kt:2155-2158` | 有，完整 |
| `session_before_compact` 否决 / 替换结果 | `compaction_end` 的 `aborted`/`errorMessage`/`result` | `Transcript.kt:1555-1604` | 有，完整 |
| `session_before_tree` / `session_tree` / `navigateTree` | 无命令（`rpc-types.ts:20-74` 没有 `navigate_tree`） | 无 | **pi 有但我们够不着**：RPC 没有这条命令，App 的会话树只能只读 + 分叉（`SessionTreeScreen`） |
| `setLabel` | 无命令 | 树界面只读显示 pi 已解析的标签 | **pi 有但我们够不着**：`rpc-types.ts:20-74` 无写入通道 |
| `registerMarkdownTransformer` / `registerMessageRenderer` / `registerEntryRenderer` | 渲染器只在 TUI 被调用；消息/条目本体上线 | 通用卡片兜底（`HookMessageBlock`）；`custom` entry 自 **2026-09-24** 起不画行（`onEntry` 的 `custom` 分支返回 `TranscriptChange.None`，用户裁决） | **pi 有但我们够不着**：渲染器实现不可达（`interactive-mode.ts:2024,3558,3597`），内容不丢 |
| `custom()` / `setFooter` / `setHeader` / `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` / `setHiddenThinkingLabel` / `getEditorText` / `getAllThemes` / `getTheme` / `setTheme` / `get/setToolsExpanded` / `addAutocompleteProvider` / `set/getEditorComponent` / `onTerminalInput` | 无（`rpc-mode.ts:163-311` no-op / 假值） | `TuiOnlyScan` 扫描源码并提示改用终端页 | **pi 有但我们够不着**：RPC 上下文不实现，App 收不到任何信号 |
| `getActiveTools` / `getAllTools` / `setActiveTools` / `getSystemPrompt` | 无命令 | 无 | **pi 有但我们够不着**：RPC 无此命令 |

---

## 5. 本次动手做的三项（都是“协议里有、没用对/没落 UI”）

### 改动 1（真 bug）：Stop/Escape 会静默丢掉排队消息

- pi 依据：`clear_queue` 的响应体就是被清掉的队列——`rpc-mode.ts:433-435` 返回
  `session.clearQueue()`，`agent-session.ts:1608-1615` 返回 `{steering, followUp}`。
  `get_state` 的 `RpcSessionState`（`rpc-types.ts:96-109`）**没有**队列数组，只有
  `pendingMessageCount`。
- 原实现（`PiEngineSession.kt:701-708`）：先 `get_state`，再从它的 data 里读
  `steering`/`followUp` → 永远是空数组；随后 `clear_queue` 清空 pi 侧队列但响应被丢弃。
  于是“停一下把排队的话还回输入框”这一整条链（`ChatScreen.kt:1050-1052` →
  `mergeRestoredQueue`）拿到的一直是空列表：用户按 Stop，排队消息被 pi 清掉且原文消失。
- 改法：改成从 `clear_queue` 的响应读，随后照旧 `abort`。少一次往返，语义与 pi 一致。

### 改动 2（真 bug）：回合中从面板选模板/技能会被 pi 拒绝

- pi 依据：流式中提交 `prompt` 必须带 `streamingBehavior`，否则抛
  `Agent is already processing. Specify streamingBehavior ('steer' or 'followUp')…`
  （`agent-session.ts:1211-1217`）。pi 自己的 TUI 对**所有**非内置提交都带
  `{ streamingBehavior: "steer" }`（`interactive-mode.ts:3137-3142`）。
- 原实现（`PiSessionViewModel.kt:1759-1795`）：模板/技能分支调 `engine.prompt(text)`，
  不带 `streamingBehavior`；扩展命令分支走 `send`（pi 会立即执行，不受影响）。
  结果：回合中在 `/` 面板点一个模板或技能 → pi 拒绝 → 只弹一条错误；同一个动作在 pi TUI
  是“排队成 steer”。
- 改法：按 `engine.transcript.streaming` 决定是否带 `StreamingBehavior.Steer`
  （`PiCommands.prompt` 与 `PiEngineSession.prompt` 本来就支持这个参数，之前只有测试在用）。
  “后续”chip 仍是独立的 `follow_up` 手势，不受影响。

### 改动 3（补 UI 落点）：`extension_error` 现在能说出是哪个扩展

- pi 依据：`rpc-mode.ts:348-350` 发 `{ extensionPath, event, error }`；
  `runner.ts:851-882` 是唯一来源。pi 自己 TUI 的显示是
  `Extension "<path>" error: <error>`（`interactive-mode.ts:2827-2828`）——即“点名扩展”是
  pi 的既有行为。
- 原状态：`PiEvent.ExtensionError.extensionPath` 被解析、写进 KDoc，但没有任何读者
  （`grep extensionPath app/ rpc/` 只命中声明与注释）。用户看到“扩展出错：<错误>”，
  无法知道是哪个扩展。
- 改法：新增纯逻辑 `rpc/src/main/kotlin/app/pi/rpc/ExtensionErrorText.kt`，把 pi 的文件路径
  归约成“名字”（不出现路径分隔符；`index.ts` 这种通用入口取所在目录名），对话流错误行
  （`Transcript.kt:959`）与 snackbar（`PiSessionViewModel.kt:1071`）共用同一句
  `扩展出错：<名字>`。`event`（pi 钩子名）按 pi 自己 TUI 的做法不展示，仍是
  `PiEvent.ExtensionError` 上的字段。
- 新纯逻辑已进 bare-JVM harness：`app/src/test/kotlin/app/pi/rpc/ExtensionErrorTextCheck.kt`
  （22 项断言，含“结果里不得出现 `/` 或 `\`”），登记在
  `tools/run-app-pure-checks.sh` 的 `extension-error-text`。

---

## 6. 自检

| 命令 | 结果 |
|---|---|
| `bash tools/typecheck.sh` | `typecheck: OK (:rpc + :app, cross-module boundary reproduced) — 0 error diagnostics, :rpc 0 / :app 0` |
| `bash tools/run-app-pure-checks.sh` | `pure-checks: OK — 6 harnesses ran on a bare JVM`（含新增 `extension-error-text`；`sessions` 是同一工作树里另一位作者新加的，也在跑） |
| `python3 tools/check-nested-comments.py` | `nested-comments: OK (155 Kotlin file(s) scanned)` |

三条都在**同一工作树**上通过；该树里还有另一位作者未提交的改动（`PiEngineHost` /
`PiSessionStore` / `sessions` harness 等），所以数字反映的是这棵树的整体状态，不是单人结果。

未做 / 测不到的部分，明确记在这里：

- `:rpc:test`（Gradle）按规矩没有运行，所以 `rpc/src/test` 里已有的
  `EventsTest`/`CommandsTest` 没有在本次改动上跑过；新的纯逻辑用 bare-JVM harness 覆盖。
- 真机行为没有验证：改动 1、2 需要一台跑着 pi 引擎的设备才能端到端确认（队列里真有消息
  时按 Stop、回合中从面板选模板）。代码路径与 pi 源码的对照是确定的，端到端仍是“需要设备上的
  X”。
- `get_messages` 与 `cycle_model` 保持无界面，理由见 §1；如果以后要“给模型看的上下文”视图
  （而不是对话记录），那才是 `get_messages` 的用途，属于新功能而不是协议缺口；
  `cycle_model` 要回来只需给模型选择器加一个“下一个”按钮，同样是新功能。

## 7. 结论（直接回答三个问题）

1. **不是“缺协议项”，而是“协议面已经 100% 抵达 App”**：33/33 命令、9/9 扩展 UI 方法、
   26/26 事件记录类型、12/12 delta 都有解析与处理（§1–§3 每行都有 grep 出来的证据）。
   真正缺的只有两类：**(a) pi 在 RPC 模式下根本不发的东西**（§4 标 `pi 有但我们够不着`
   的那些 no-op / 无命令项，属于“协议里没有”）；**(b) 两条命令有封装但从不发送**
   （`get_messages`——用它替换会丢历史；`cycle_model`——入口按用户裁决删除；
   `extension_error` 的归因字段没人用——已补；
   `clear_queue`/`streamingBehavior` 两处语义用错——已修）。
2. **必须体现在 UI 上的**：失败要能归因（改动 3）、Stop 要能拿回排队文本（改动 1）、
   回合中选模板/技能要能排队而不是报错（改动 2）。**不需要 UI 的**：`get_messages`
   （与对话记录重复且更差）、`cycle_model` 及其 `isScoped`、`get_state.pendingMessageCount`、
   `bash_execution_update.id`、`turn_end` 的逐条 `toolResults`——纯内部状态，用户没有可看/可点的东西。
3. **扩展协议面的界面落点**：dialog 四件套、fire-and-forget 五件套、`extension_error`、
   注册命令（`/` 面板）、状态栏、部件、三个可被否决的钩子（switch/fork/compact）现在都有；
   工具只有通用卡片（自定义渲染 RPC 没有通道），树导航/标签写入/系统提示读取/主题与 TUI 组件
   则完全没有命令通道，只能靠终端页兜底。
