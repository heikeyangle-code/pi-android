# 14 · 插画与排队：与 pi 官方的逐项差异

审查对象：`/tmp/node_modules/@earendil-works/pi-coding-agent`（`0.85.1`）。
“pi 源码行号”一栏默认给 **原始 `.ts` 行号**——该行号取自包内自带的 sourcemap
（`dist/**/*.js.map` 的 `sourcesContent` 就是原始 TS），所以是可复核的，不是从编译产物倒推的。
`dist/**/*.js` 行号只在少数地方补注。

**范围修订**（父代理转述的用户澄清）：用户说的「插画」和「排队」不是空态插图、也不是泛泛的队列显示，
而是**输入框上那两颗按钮**——「插话」（steer）与「排队」（followUp）——的**功能**。
因此第 3 节（排队）是主体，第 2 节（插画）压缩成一节，只回答「官方有没有对应物 + 我们实现了几处」。

---

## 1. 一句话结论

- **插画**：**pi 官方没有空态插图这回事**——它的 TUI 空态是**一行 muted 文字**（`No sessions found` / `No matching models`），
  没有图形、没有线稿、没有框；它的三处“图形”全部是彩蛋（`/arminsayshi`、`/dementedelves`、opencode+Kimi 的自动触发），
  与空态无关。我们**也没有** 120dp 线稿插图，实际实现的是 **π 字形 34dp / Material 图标 30dp + 标题 + 正文**（v2 板子的画法），
  与 `docs/pi-android-ui-spec.md:819` 那句「极简线稿插图（120dp）」**对不上**——对不上的是**规范**，不是实现。
- **排队**：**功能语义与 pi 一致，呈现与命名不一致，覆盖度不如 pi 与板子的要求。**
  两颗按钮各自交给 pi 的 `streamingBehavior: "steer"` / `"followUp"` 与官方逐字一致（含压缩期的岔路）；
  但 ① 队列**只显示计数**，pi 显示**每条排队消息的原文**、v2 板子还画了**逐条明细行**（含「编辑」）——这是**我们有 pi 有而没做**；
  ② 同一对概念在我们仓库里有**三套名字**（穿插/后续 vs 插话/排队 vs `steeringMode/followUpMode`），无裁决记录；
  ③ 附件-only 的插话在「收回并编辑」时会**静默丢图**；
  ④ 若干 KDoc 引用了 0.85.1 里**不存在的方法**（`_queueUserInput`）与**不成立的附件说法**。

---

## 2. 插画

### 2.1 前提核对：`brand-spec.md:25` 的断言

> `design/ui-refactor/brand-spec.md:25` —「**这就是全部的品牌图形**。pi 没有别的 logo、没有吉祥物、**没有插图库**。」

**结论：断言基本成立，但“没有别的品牌图形”需要补一条脚注。**

`modes/interactive/` 下的图形资产清点（全量，不是抽样）：

| 东西 | pi 侧 file:line | 是什么 | 与空态的关系 |
|---|---|---|---|
| `assets/clankolas.png`（640×537 PNG） | `modes/interactive/components/earendil-announcement.ts:8`（`const IMAGE_FILENAME = "clankolas.png"`）、`:41` `new Image(...)` | 公告卡里的一张照片 | **无**。只在 `/dementedelves` 触发（`interactive-mode.ts` 的 `handleDementedDelves`，编译行 `interactive-mode.js:5438`） |
| `ArminComponent` | `components/armin.ts:8`（`// XBM image: 31x36 pixels`）、`:60` | 31×36 XBM 位图 + 7 种动画效果（typewriter/scanline/crt…） | **无**。`/arminsayshi` 彩蛋（`interactive-mode.js:2481-2482`） |
| `DaxnutsComponent` | `components/daxnuts.ts:10`（`// 32x32 RGB image of dax`）、`:57` | 32×32 RGB 图，半块字符渲染 | **无**。`checkDaxnutsEasterEgg`，选到 opencode+kimi-k2.5 时自动播（`interactive-mode.js:4047/4180/4782`） |
| “logo” | `interactive-mode.ts:913` | **不是图形**：`theme.bold(theme.fg("accent", APP_NAME)) + theme.fg("dim", " v" + version)`，即一段**着色文字** | 无 |

**空态类文案全量（这是 pi 对“空态插图”的全部回答）——一行 muted 文字，零图形：**

| 场景 | pi file:line | 原文 |
|---|---|---|
| 会话列表空 | `components/session-selector.ts:432` | `"  No sessions found"` |
| 会话列表空（仅命名 / 当前目录两变体） | `:426` / `:428` / `:435` | `"  No named sessions found. Press … to show all."` 等 |
| 模型选择无结果 | `components/model-selector.ts:348` | `theme.fg("muted", "  No matching models")` |
| 凭证/provider 空 | `components/oauth-selector.ts:157-158` | `"No providers available"` / `"No providers logged in. Use /login first."` |

另外 `modes/interactive` 里所有“框”都是 `DynamicBorder`（`components/dynamic-border.ts:18`：`"─".repeat(width)` 的一行横线），
是**分隔线**不是插图容器。

→ **空态插图这件事，pi 官方没有对应物。** 我们没有可以“对齐”的官方实现，只有 v2 板子。

### 2.2 板子要什么

| 出处 | 要求 |
|---|---|
| `direction-b-v2.html:818-828` | `EmptyState` 组件 = `inline-flex` 的**裸图形**（`PiMark` 34dp 或 `Icon` 30dp，色 `--muted`）+ 标题 `t17 w6`（`marginTop:12`）+ 正文 `t14 c-muted`（`marginTop:8`，行高 1.6，左对齐）+ 可选 actions。**没有 120dp，没有线稿，没有描边** |
| `direction-b-v2.html:809-816` | `PiMark`：就是 App 图标那枚 108×108 viewBox 的 π 单色 path，色 `--muted`，注释「π 字形只做主标识，不铺开当装饰」 |
| `direction-b-v2.html:1878 / :1880 / :1922 / :2816 / :2825` | 五个实际用例：会话列表空（`icon="chat"`）、无匹配（`icon="search"`）、会话树空（`icon="branch"`）、引擎启动中（`pi`）、引擎就绪（`pi`） |
| `workspace-final.html:594-612` | 同款 `PiMark`（`:595-602`）/`EmptyState`（`:603-612`）内联，但**工作区空态实际用的是 `Row` + 16dp `Icon` + 标题 + meta**，**不是** `EmptyState` |
| `workspace-final.html:1528-1540` | 空目录：`这个目录是空的`（`:1530`）+ accent 实底「新建文件」按钮（`:1531-1537`）+ 一句提示（`:1538`） |
| `direction-b-v2.html:2017-2023` / `:2024-2031` | 工作台两处空态也是 `Row` 型：无改动 `lead=<Icon n="check" s={16}/>`（`:2020`）、无 `.pi` 资源 `lead=<Icon n="folder" s={16}/>`（`:2027`）——**16dp 图标，不是 30/34 的空态图形** |
| `design/ui-refactor/06-v2-construction-reference.md:128` | 「空态 EmptyState（π 字形标识 + 标题 + 两行正文）」——与 HTML 一致（该行同时把工作台四处空态单列为 `Row` 型，见 `:125-126` 一带） |

**与规范的冲突（重要）**：`docs/pi-android-ui-spec.md:819` 写「空态｜极简线稿插图（120dp）+ 标题 17sp + 说明 14sp `muted` + 一个主按钮」，
`:159-165`（§2.5）写「插图：空态用**极简线稿**（1.5dp 描边、单色 `outlineVariant`）」。
**v2 冻结稿里没有任何一处 120dp 线稿，也没有 1.5dp 描边路径**——规范这一行是板子之前的旧稿残留。

### 2.3 我们实现了几处、画的什么

| 空态 | 实现 file:line | 实际画的是什么 | 与规范/板子的差距 |
|---|---|---|---|
| 对话页空态 | `ui/screens/ChatScreen.kt:1362-1400`（`emptyTranscript` 注释块） | **什么都没有**——空白画布 + 输入框；`PiEmptyState` 的调用已被 D31 删除（`design/ui-refactor/07-construction-decisions.md` D31） | 与板子 phone59/60 不一致，**已裁决**（D31），不算漏做 |
| 会话列表空 | `ui/screens/SessionsScreen.kt:360-366`，构件 `ui/components/PiCommon.kt:734` | `PiEmptyStateTopAnchored(markPi=false)` → **Material 图标 30dp**（`Icons.Filled.Forum`）+ 标题 17 + 正文 14 | 与板子一致 |
| 会话列表无匹配 | `SessionsScreen.kt:370-376` | `Icons.Filled.Search` 30dp + 标题 + 正文 | 与板子一致 |
| 会话树空（三条分支） | `ui/chat/SessionTreeScreen.kt:413-421`（busy）、`:429-437`（无匹配）、`:876-884`（没有条目） | 同款 `PiEmptyState`，图标 `Icons.Filled.AccountTree`（`:416/:432/:879` 注释指 `direction-b-v2.html:1922` `icon="branch"`） | 与板子一致 |
| 工作区文件空 / 目录空 | `ui/screens/WorkspaceViewer.kt:563`（文件空）、`ui/blocks/PathListBlock.kt:77`（`emptyText = "空目录"`）、`ui/screens/WorkspaceChrome.kt:395`（实底「新建文件」比照 `workspace-final.html:1531-1537`） | **文字**（`PathListBlock` 一行「空目录」）+ 一个主按钮 | 与板子 `workspace-final.html:1528-1540` 一致；**不是** `EmptyState`，板子也是 |
| 资源四类空 | `ui/screens/ProjectScreen.kt:869-880` | `WsCardSlice`+`WsRow`：`title="这一类还没有资源"`、`lead = FileGlyph()`（`Icons.AutoMirrored.Filled.InsertDriveFile`，见 `:1381-1388`）、一句 meta | 与 `direction-b-v2.html:2024-2031` 一致 |
| 设置搜索无结果 | `ui/settings/SettingsSearchScreen.kt:106-113` | `PiEmptyState`（默认 `markPi=true`）→ **π 字形 34dp** + 标题 + 正文 | 板子没有这张空态的截图；π 字形属“App 自己那几面”的用法，可接受 |
| 模型列表空 | `ui/settings/PiModelsScreen.kt:200`（`Note("还没有配置任何厂商…")`）、`:495` `Note` 实现 | **纯文字 Note**，无图形 | 板子/规范都没有这张空态；无差距，只是没有图形 |
| 凭证列表空 | `ui/settings/PiCredentialScreen.kt:510`（`Note("还没有候选模型…")`） | 纯文字 | 同上 |
| 设备能力屏空 | `ui/device/DeviceCapabilityScreen.kt`（`grep 空态` 零命中：该屏用状态行/说明文字表达，`bridge/*` 的“还没有授权/还没有上报”文案） | 文字 | 无板子对照 |
| 扩展包屏空 | `packages/PackageStrings.kt:55`（`NO_PACKAGES = "还没有安装任何资源包。"`），使用点 `ui/../packages/PiPackagesScreen.kt:285` | 一行文字 | 无板子对照 |

**汇总：全仓 `PiEmptyState` 只有 3 个调用面**（`SessionsScreen`、`SessionTreeScreen`、`SettingsSearchScreen`，见 §2.3 表），
其余空态是 `WsRow` / `Note` / 一行文字。**没有任何一处是 120dp 线稿插图**，也没有 1.5dp `outlineVariant` 描边路径。

### 2.4 差距与建议

| # | 差距 | 选项 | 倾向 |
|---|---|---|---|
| A1 | 规范 `docs/pi-android-ui-spec.md:819` 与 `:159-165` 要求的「120dp 极简线稿插图」在板子和实现里都不存在 | **改规范**（写成“π 字形 34dp / 屏自有 Material 图标 30dp + 标题 17 + 正文 14 muted”）/ 改实现（真画 120dp 线稿）/ 不改 | **改规范**。板子是冻结视觉权威，120dp 线稿是板子之前的旧话；照它做等于推翻 v2 |
| A2 | `brand-spec.md:25`「没有别的品牌图形」少了脚注 | **改规范**：补一句「pi 终端侧存在三处彩蛋图形（`armin` XBM / `daxnuts` RGB / `clankolas.png` 公告卡），均为 `/arminsayshi`、`/dementedelves`、opencode+Kimi 触发，**不是** logo/吉祥物/空态插图，本 App 不移植」 / 不改 | **改规范**。断言本身正确，但“插图库为零”这个说法在源码层面可被反例挑战，注释一句成本最低 |
| A3 | 三处“无图形”空态（模型/凭证/扩展包/设备能力）没有图形 | 改实现（补图形）/ 不改 | **不改**。板子对这四屏没有空态图，pi 官方也是纯文字；补图形属发明 |

---

## 3. 排队（steer / followUp）

### 3.1 pi 侧的机制（先给事实，再对照）

**a) 两条入队入口**

| 事实 | pi file:line | 原文 |
|---|---|---|
| 流式中提交 → steer | `modes/interactive/interactive-mode.ts:3137-3143` | `if (this.session.isStreaming) { … await this.session.prompt(text, { streamingBehavior: "steer" }); … return; }` |
| 提交前统一要求非空文本 | `interactive-mode.ts:2964-2967` | `text = text.trim(); if (!text) return;` |
| alt+enter → followUp | `interactive-mode.ts:4125-4155` | `private async handleFollowUp(): Promise<void> { const text = (this.editor.getExpandedText?.() ?? this.editor.getText()).trim(); if (!text) return; …` |
| followUp 的流式分支 | `interactive-mode.ts:4143-4149` | `if (this.session.isStreaming) { … await this.session.prompt(text, { streamingBehavior: "followUp" }); … }` |
| followUp 的**非流式**分支（alt+enter 当普通回车） | `interactive-mode.ts:4150-4154` | `else if (this.editor.onSubmit) { this.editor.setText(""); this.editor.onSubmit(text); }` |
| 按键绑定 | `core/keybindings.ts`（编译行 `keybindings.js:69-76`） | `"app.message.followUp": { defaultKeys: windowsKeybindings ? "ctrl+q" : "alt+enter" }`；`"app.message.dequeue": { defaultKeys: windowsKeybindings ? "alt+q" : "alt+up" }` |
| 绑定处理注册 | `interactive-mode.ts:2898-2899` | `onAction("app.message.followUp", () => this.handleFollowUp())` / `onAction("app.message.dequeue", () => this.handleDequeue())` |

**b) 语义（两条队列的投递时机不同，这是“两个词不是同义词”的根据）**

| 事实 | pi file:line | 原文 |
|---|---|---|
| steering 的出队点：进入 loop 时、每轮 `turn_end` 之后、以及 `prepareNextTurn` 之后补poll | `pi-agent-core/dist/agent-loop.js:83` / `:158` / `:104-108` | `let pendingMessages = (await config.getSteeringMessages?.()) || [];` … `pendingMessages = (await config.getSteeringMessages?.()) || [];` |
| follow-up 的出队点：内层 loop 即将退出时 | `agent-loop.js:160-166` | `const followUpMessages = (await config.getFollowUpMessages?.()) || []; if (followUpMessages.length > 0) { pendingMessages = followUpMessages; continue; }` |
| steering 注入位置 | `agent-loop.js:111-119` | 作为 `message_start`/`message_end` 注入 `currentContext.messages`，**在下一轮助手回复之前** |
| 入队只为“显示”保留文本，给 agent 的是完整 content | `core/agent-session.ts:1423-1438` / `:1440-1455` | `this._steeringMessages.push(text); this._emitQueueUpdate(); const content = [{ type: "text", text }]; if (images) content.push(...images); this.agent.steer({ role: "user", content, timestamp: Date.now() });` |
| RPC 文档口径 | `docs/rpc.md:62-63` / `:82` / `:104` | `"steer"`: delivered after the current assistant turn finishes executing its tool calls, before the next LLM call. / `"followUp"`: Wait until the agent finishes. Message is delivered only when agent stops. |

**c) 模式（逐条 / 全部）**

| 事实 | pi file:line | 原文 |
|---|---|---|
| 两个独立的键，各自默认 `one-at-a-time` | `core/settings-manager.ts:746` / `:756` | `return this.settings.steeringMode \|\| "one-at-a-time";` / `return this.settings.followUpMode \|\| "one-at-a-time";` |
| 迁移：`queueMode → steeringMode` | `settings-manager.ts:425-429` | `if ("queueMode" in settings && !("steeringMode" in settings)) { settings.steeringMode = settings.queueMode; delete settings.queueMode; }` |
| 落盘位置 | `settings-manager.ts:750-752` / `:760-762` | `this.globalSettings.steeringMode = mode; this.markModified("steeringMode"); this.save();`（两键都在 `settings.json` 的**全局**层） |
| 模式如何影响 flush | `pi-agent-core/dist/agent.js:63-75` | `drain() { if (this.mode === "all") { const drained = this.messages.slice(); this.messages = []; return drained; } const first = this.messages[0]; … return [first]; }` |
| 会话把设置同步进 agent | `agent-session.ts:1872-1875` / `:1881-1884` / `:1890-1893` | `this.agent.steeringMode = this.settingsManager.getSteeringMode(); …` / `setSteeringMode(mode) { this.agent.steeringMode = mode; this.settingsManager.setSteeringMode(mode); }` |
| TUI 设置项（两行） | `components/settings-selector.ts:469-475` / `:477-482` | `label: "Steering mode"`, `description: "Enter while streaming queues steering messages. 'one-at-a-time': deliver one, wait for response. 'all': deliver all at once."`, `values: ["one-at-a-time","all"]`；`Follow-up mode` 同构，描述以 `followUpKey` 开头 |
| RPC 口径 | `docs/rpc.md:361-382` | `"all"`: Deliver all steering messages after the current assistant turn finishes…；`"one-at-a-time"`: Deliver one steering message per completed assistant turn (default) |

**d) 显示**

| 事实 | pi file:line | 原文 |
|---|---|---|
| pending 容器 | `interactive-mode.ts:550` | `this.pendingMessagesContainer = new Container();` |
| 位置：**聊天与编辑器之间** | `interactive-mode.ts:876-889`（viewport 装配）+ `:890-900`（挂载顺序 `document → pendingMessages → status → widgetsAbove → editor → widgetsBelow → footer`） | `pendingMessages: this.pendingMessagesContainer,` |
| 画什么 | `interactive-mode.ts:4370-4387` | 每条 steering 一行 `theme.fg("dim", \`Steering: ${message}\`)`；每条 follow-up 一行 `\`Follow-up: ${message}\``；末尾一行 `\`↳ ${dequeueHint} to edit all queued messages\``；**没有编号、没有 chip、没有颜色区分**（全部 `dim`） |
| 消息文本的删除靠**文本匹配** | `agent-session.ts:646-662` | `const steeringIndex = this._steeringMessages.indexOf(messageText); if (steeringIndex !== -1) { this._steeringMessages.splice(steeringIndex, 1); this._emitQueueUpdate(); }` |
| 事件形态 | `agent-session.ts:596-601` | `_emit({ type: "queue_update", steering: [...], followUp: [...] })` |

**e) 出队 / 中止 / 压缩**

| 事实 | pi file:line | 原文 |
|---|---|---|
| dequeue（不 abort，回合继续跑） | `interactive-mode.ts:4157-4164` → `:4389-4408` | `private handleDequeue(): void { const restored = this.restoreQueuedMessagesToEditor(); if (restored === 0) this.showStatus("No queued messages to restore"); …}` |
| restore 的合并规则 | `interactive-mode.ts:4399-4402` | `const queuedText = allQueued.join("\n\n"); const currentText = options?.currentText ?? this.editor.getText(); const combinedText = [queuedText, currentText].filter((t) => t.trim()).join("\n\n"); this.editor.setText(combinedText);` |
| 顺序 = steering 先、followUp 后 | `interactive-mode.ts:4390-4391` | `const allQueued = [...steering, ...followUp];` |
| Esc（流式中）= restore **并 abort** | `interactive-mode.ts:2852-2854` | `if (this.session.isStreaming) { this.restoreQueuedMessagesToEditor({ abort: true }); }` |
| abort 的语义（文档口径：不走 clear 则队列继续） | `docs/rpc.md:158` | `To implement interactive Esc behavior, send clear_queue before abort, then restore the returned text in the client editor. abort continues queued messages when they remain in the session.` |
| 压缩期入队（TUI 本地暂存） | `interactive-mode.ts:3130`（steer）/ `:4136`（followUp）/ `:4410-4415`（`queueCompactionMessage`）/ `:4428+`（`flushCompactionQueue`） | `this.queueCompactionMessage(text, "steer");` / `{ text, mode }` 入 `compactionQueuedMessages` |
| 清空两个 session 队列的 RPC | `agent-session.ts:1587-1595` | `clearQueue(): { steering: string[]; followUp: string[] } { const steering = [...this._steeringMessages]; … this.agent.clearAllQueues(); this._emitQueueUpdate(); return { steering, followUp }; }` |
| **附件**：TUI 的 steer/followUp **不携带图片** | `interactive-mode.ts:3140` / `:4146`（都只传 `{ streamingBehavior: … }`）+ `agent-session.ts:1184` | `let currentImages = options?.images;` ⇒ `undefined` ⇒ `_queueFollowUp(expandedText, undefined)` |
| **附件**：RPC 的 `steer`/`follow_up`/`prompt` **接受** `images` | `docs/rpc.md:50-52` / `:88-90` / `:110-112`；handler `dist/bundle/chunks/chunk-JVUZSMYM.js`：`case"steer":return await session.steer(command.message,command.images)` | 合法通道存在，只是 TUI 不走 |
| **附件**：TUI 粘贴图片的形态是**路径文本** | `interactive-mode.ts` `handleClipboardPaste`（编译行 `interactive-mode.js:2334-2354`） | `this.editor.insertTextAtCursor?.(filePath);` —— 编辑器里没有“附件”概念 |

**f) 状态词**：pi 的 TUI 只有 `Working`（`interactive-mode.ts:411` `private readonly defaultWorkingMessage = "Working"`）与空闲；**没有 “queued/排队中” 这一档**。

### 3.2 逐项对照

判定列：**一致** / **不一致** / **我们没有** / **我们有 pi 没有** / **两方都没有(仅板子有)**。

| # | 行为 | pi（file:line + 原文） | 我们（file:line） | 判定 | 建议 |
|---|---|---|---|---|---|
| 1 | **流式中提交文本 = steer** | `interactive-mode.ts:3137-3140`：`if (this.session.isStreaming) { … prompt(text, { streamingBehavior: "steer" }) }` | ① `ChatScreen.kt:2773-2774` 的「插话」chip → `session.send`；② `PiSessionViewModel.kt:3252`：`streamingBehavior = if (engine.transcript.streaming) StreamingBehavior.Steer else null`；③ 调色板模板/技能中途也走 Steer（`:3306`） | **我们的“发送键”不一致，但语义已由 chip 覆盖**：流式中发送键是**停止**（`ChatScreen.kt:2845-2853`，D25 裁决），pi 的同位置是 steer | **不改**（D25 已裁决，且“停止”是手机唯一能中止长回合的办法）。若要更贴 pi，可给发送盘加长按 = steer——但**规范自己删掉了长按**（见 #22） |
| 2 | **显式 followUp 入口** | `interactive-mode.ts:4143-4146`：`prompt(text, { streamingBehavior: "followUp" })`，按键 `alt+enter` | `ChatScreen.kt:2775`「排队」chip → `:1961` `session.sendFollowUp(draft, attachments)` → `PiSessionViewModel.kt:3367`：`streamingBehavior = … StreamingBehavior.FollowUp else null` | **一致**（通道与语义一致；绑定不同：手机没有 alt+enter，改由 chip 承担） | **不改** |
| 3 | **两颗按钮只在流式时出现** | 无对应物：pi 的 alt+enter **任何时候都在**，空闲时降级为普通提交（`interactive-mode.ts:4150-4154`） | `ChatScreen.kt:2773`：`if (streaming) { KeyChip(插话) ; KeyChip(排队) }` | **我们有 pi 没有**（两个命名 chip 是 D25 的产物，板子 Composer 里也没有这两颗 —— `direction-b-v2.html:1391-1450` 全函数只有 `/ ! !! @ 图片 编辑器` 与思考等级、发送盘） | **不改**。空闲时那颗“排队”本来就等于普通发送，隐藏避免两个按钮做同一件事 |
| 4 | **followUp 要求非空文本** | `interactive-mode.ts:4126-4127`：`if (!text) return;` | `ChatScreen.kt:1844`：`canFollowUp = state.streaming && draft.isNotBlank()`；`PiSessionViewModel.kt:3336-3337`：`if (trimmed.isEmpty()) return` | **一致** | **不改** |
| 5 | **steer 要求非空文本** | `interactive-mode.ts:2964-2967`：`text = text.trim(); if (!text) return;` | `ChatScreen.kt:1848`：`canSteer = streaming && (draft.isNotBlank() \|\| attachments.isNotEmpty())`；`PiSessionViewModel.kt:3193-3194`：`if (trimmed.isEmpty() && images.isEmpty()) return` | **不一致（我们更宽）**：我们允许**附件-only**的插话，pi 的 TUI 两条路都要求文本 | **需裁决**：保留（RPC 的 `steer` 确实接受 `images`，`docs/rpc.md:88-90`）还是收紧到 pi 的 TUI 口径。**注意 #12 的丢图问题** |
| 6 | **队列显示 = 每条消息原文** | `interactive-mode.ts:4375-4382`：`\`Steering: ${message}\`` / `\`Follow-up: ${message}\``，一条一行，`dim` | `ChatScreen.kt:2529-2556` `QueueRow`：**只有计数**（`插话 N` / `排队 N`）；`PiSessionViewModel.kt:2097-2098` 把事件里的文本**丢掉只留 `.size`**（`rpc/.../Events.kt:252-257` 其实已经把文本解析出来了） | **pi 有我们没做 + 板子也要求**：`direction-b-v2.html:2702-2718` 的 `QueueState` 画了逐条明细行（模式词 + 序号 + 消息原文 + 「编辑」） | **改实现**（低成本：文本已在 `PiEvent.QueueUpdate` 里，不需要改 wire；也不需要新 sheet）。这是本轮最值得做的一条 |
| 7 | **显示位置：编辑器上方** | `interactive-mode.ts:876-889` + `:890-900`（`document → pendingMessages → editor`） | `ChatScreen.kt:1675-1687`（在 `Column` 内位于 weight(1f) 的 transcript 之后、`Composer`（`:1829`）之前） | **一致** | 不改 |
| 8 | **顺序：steering 先，followUp 后** | `interactive-mode.ts:4390-4391`（出队）/ `:4375-4382`（显示） | `ChatScreen.kt:2539-2541`（插话 chip 先、排队 chip 后）；`PiEngineSession.kt:917-921` `drainQueue` 返回 `steering + followUp` | **一致** | 不改 |
| 9 | **dequeue = 回编辑器、不 abort** | `interactive-mode.ts:4157-4158`：`handleDequeue` 调 `restoreQueuedMessagesToEditor()`（**无** options） | `ChatScreen.kt:1683-1685`「收回并编辑」→ `PiSessionViewModel.kt:3400-3410` `restoreQueue` → `PiEngineSession.kt:917-921` `drainQueue()`（只有 `clear_queue`，**无** `abort`） | **一致** | 不改 |
| 10 | **合并规则（队列文本在前，草稿在后，丢空段）** | `interactive-mode.ts:4399-4402` | `ui/chat/QueueRestore.kt:27-29`：`mergeRestoredQueue` | **一致**（逐字） | 不改 |
| 11 | **Stop/Esc = clear_queue 然后 abort** | `interactive-mode.ts:2852-2854`（TUI）＋ `docs/rpc.md:158`（RPC 客户端的正确写法） | `ChatScreen.kt:1961-1963` → `PiSessionViewModel.kt:3373-3384` `stop` → `PiEngineSession.kt:934-937`：`drainQueue()` 之后 `abort` | **一致** | 不改 |
| 12 | **队列里的附件** | TUI：**不存在**（编辑器无附件概念，粘贴图是路径文本，`:2334-2354`）；RPC：`images` 合法（`docs/rpc.md:88-90`） | 入队合法（`PiSessionViewModel.kt:3193-3194`、`:3332` 都收 `images`），但 `clear_queue` **只返回文本数组**（`agent-session.ts:1587-1595` 只回 `string[]`）⇒ 收回时 `mergeRestoredQueue` 过滤空串（`QueueRestore.kt:28`）⇒ **附件-only 的插话“收回并编辑”后图片静默消失**（计数还显示过 1） | **不一致 / 缺陷**：附件-only 插话可入队（#5），但不可收回 | **需裁决**（见 §4-①）。三个选项：禁用附件-only 的「插话」/ 收回时若文本为空则给出「这条带附件，收不回」的提示 / 不改并记成已知缺口 |
| 13 | **模式设置：`steeringMode`** | `settings-selector.ts:469-475`；`settings-manager.ts:746/750-752`；语义 `docs/rpc.md:361-370` | `PiSettingsRegistry.kt:487-496`（`key="steeringMode"`，标题「**穿插**模式」，选项 `逐条/全部`，默认 `one-at-a-time`，见 `:243-246`）；`ChatSheets.kt:320-325`（标题「**插话**消息（steer）」，选项 `逐条/全部` `:481-482`）；RPC `Commands.kt:161-165` `set_steering_mode` | **功能一致，命名不一致**（同一仓库内两种叫法） | **需裁决**（§4-②） |
| 14 | **模式设置：`followUpMode`** | `settings-selector.ts:477-482`；`settings-manager.ts:756/760-762`；语义 `docs/rpc.md:372-382` | `PiSettingsRegistry.kt:498-507`（「**后续**模式」）；`ChatSheets.kt:326-331`（「**排队**消息（follow up）」）；RPC `Commands.kt:167-171` | 同上 | 同上 |
| 15 | **两个模式互不共用** | 两个独立键、两个独立队列（`settings-manager.ts:746/756`；`agent.js:128-129`） | 两个独立 `UiState.meta.steeringMode` / `followUpMode`（`PiSessionViewModel.kt:549-550`）；两个独立 setter（`:3466-3477`） | **一致** | 不改 |
| 16 | **默认值 `one-at-a-time`** | `settings-manager.ts:746/756` | `PiSessionViewModel.kt:549-550`（`QueueMode.OneAtATime`）、`PiSettingsRegistry.kt:493/504`（`str("one-at-a-time")`） | **一致** | 不改 |
| 17 | **持久化位置（`settings.json` 全局层）** | `settings-manager.ts:750-752/760-762`（`globalSettings` + `save()`） | 只经 RPC：`PiSessionViewModel.kt:3466-3477` → `Commands.kt` → pi 自己落盘；设置页另有一条文件写入并**回灌**（`PiSessionViewModel.kt:1083-1087`，key 就是 `steeringMode`/`followUpMode`）；摘要 `PiSettingsRegistry.kt:1438` | **一致**（且 app 不另存一份，无漂移） | 不改 |
| 18 | **模式如何影响 flush** | `agent.js:63-75`：`all` 全取，`one-at-a-time` 只取队首 | 我们**不实现**投递逻辑，全部由 pi 执行（只在 `set_*_mode` 上写值 + `get_state` 对账 `PiSessionViewModel.kt:2983-2984`） | **一致**（转发正确即可） | 不改 |
| 19 | **`queueMode → steeringMode` 迁移** | `settings-manager.ts:425-429` | 无对应代码（迁移在 pi 进程内发生；我们的设置页只认 `steeringMode`/`followUpMode`，`PiSettingsRegistry.kt:487/498`） | **一致**（不需要我们做） | 不改 |
| 20 | **计数来源** | `agent-session.ts:596-601` 的 `queue_update` | `app/pi/rpc/Events.kt:252-257` 解析 + `PiSessionViewModel.kt:2095-2098` `event.steering.size` / `event.followUp.size` | **一致**（同源） | 不改；但见 #6（我们把文本丢掉了） |
| 21 | **队列文本被移除的判定** | pi 用**文本匹配**：`agent-session.ts:646-662` `indexOf(messageText)` | 我们不做这个判定，只消费 pi 发来的 `queue_update` | **一致**（我们继承 pi 的行为，包括它“两条相同文本只删第一条”的怪癖） | **不改**，但建议在 §4 记一条“不复制”说明 |
| 22 | **长按发送 = 直接选送达方式** | 无对应物 | 无实现（`ChatScreen.kt` 的发送盘只有 `clickable`，`:2844-2847`，全文件 `onLongClick` 零命中） | **规范有、板子没有、双方实现都没有**：`docs/pi-android-ui-spec.md:434`「长按发送｜直接选送达方式，不弹层」 | **改规范**（删除或标注废止） |
| 23 | **流式中点发送 → 三选 Sheet** | 无对应物（pi 的 Enter 直接 steer，不做选择） | 无实现；改为两颗 chip | **规范有、板子没有、双方实现都没有**：`docs/pi-android-ui-spec.md:433` | **改规范**（该行已被 D25 取代，应在规范里显式标注） |
| 24 | **每条队列消息可单独取回/编辑** | **没有**：pi 的列表**只读**，只有“edit **all** queued messages”一条提示（`interactive-mode.ts:4384`）；`clear_queue` 无逐条形态（`docs/rpc.md:139`） | 我们也没有——`PiSessionViewModel.kt:3394-3398` 的 KDoc 明确说明“逐条不在菜单上，因为 wire 做不到” | **两方都没有** | **一致的“没有”**；板子的「编辑」按钮（`direction-b-v2.html:2715`）是**做不到的**，建议在板子/规范上标注 |
| 25 | **空队列出队** | `interactive-mode.ts:4159-4160`：`showStatus("No queued messages to restore")` | `PiSessionViewModel.kt:3402-3406`：`pushNotice("队列里没有待收回的消息", Tone.Warning)` | **一致**（措辞不同，语义相同） | 不改 |
| 26 | **队列残留（回合结束）** | 交付时 pi 自己从数组里删并 `_emitQueueUpdate()`（`agent-session.ts:646-662`）⇒ 计数归零 | 我们只消费事件；会话替换时额外清零（`PiSessionViewModel.kt:4339-4340`） | **一致** | 不改。**已知共享限制**：`get_state` 只带 `pendingMessageCount`，不带数组（`agent-session.ts:1598-1601`），所以**中途 attach 时计数会先显示 0**，直到下一次 `queue_update`。pi 的 TUI 不受影响（队列在进程内） |
| 27 | **压缩期入队** | TUI 本地暂存：`interactive-mode.ts:3130` / `:4136` / `:4410-4415` / `:4428+`；`prompt` 在压缩期**抛错**（`agent-session.ts:1176-1180`） | `PiSessionViewModel.kt:3210-3224`（`send`）与 `:3341-3352`（`sendFollowUp`）：`meta.compacting` 为真时改发**裸 `steer`/`follow_up`**（`PiCommands.kt:77-86` / `:95-105`），由 pi 排进 session 队列并回 `queue_update` | **语义一致、机制不同**（不本地暂存，因此不需要 flush、不踩丢事件）。D23 已记录 | **不改**。但**实现依据的 KDoc 是错的**，见 §4-⑤ |
| 28 | **steering 与 followUp 同时非空** | 显示与出队都是两段拼接（`interactive-mode.ts:4375-4382` / `:4390-4391`） | `ChatScreen.kt:2539-2541` 两个 chip 都画、中间 8dp spacer；`drainQueue` 两段拼接（`PiEngineSession.kt:919-920`） | **一致** | 不改 |
| 29 | **顶部状态词「排队中」** | pi **没有**：只有 `Working`（`interactive-mode.ts:411`） | `PiSessionViewModel.kt:4405`：`current.queueSteering > 0 \|\| current.queueFollowUp > 0 -> "排队中"` | **我们有 pi 没有**，但**板子有**：`direction-b-v2.html:457`（`'排队中': {g:'≡', c:'var(--warning)'}`）与 `:3531` 的状态行清单，`06-v2-construction-reference.md:136` | **不改**。仅提示：因为 `:4402` 的 `current.streaming -> "工作中"` 排在前，`排队中` 实际只在**压缩窗口**（非流式且队列非空）出现 |
| 30 | **队列 chip 的符号/颜色（三重编码）** | pi 无符号：单色 `dim` 文字（`interactive-mode.ts:4375-4382`） | `ChatScreen.kt:2539/2541`：`⇢` `warning` / `⇣` `muted`，chip 高 26 圆角 999（`QueueChip` `:2563+`）；已被 `13-pi-token-callsite-diff.md:233` 记录 | **我们有 pi 没有**，但**板子有**（`direction-b-v2.html:1384-1385`） | 不改 |
| 31 | **队列行只在有内容时画** | `interactive-mode.ts:4373`：`if (steeringMessages.length > 0 \|\| followUpMessages.length > 0)` | `ChatScreen.kt:1675` 同一条件 | **一致** | 不改 |
| 32 | **入队时的本地回显** | pi 的 TUI 在 `queueCompactionMessage` 里**不画对话气泡**，只在 pending 区列一行（`interactive-mode.ts:4410-4415`） | 压缩期分支**先 echo 一条气泡**再发裸命令（`PiSessionViewModel.kt:3216-3217` / `:3342` → `PiEngineSession.kt:812-816` `echoUserPrompt`） | **不一致**（我们多一条立即气泡）。pi 的 `message_start(user)` 到达时我们不再追加行，所以不会双气泡；但 `echoUserPrompt` **不登记 pending echo**（`PiEngineSession.kt:779-800` 的 KDoc 说“这条路径已无人调用”，与 D23 后的实际调用点矛盾） | **需裁决**：保留（用户立刻看到自己说了什么）还是删掉（贴 pi）。**先修 KDoc**（它现在是错的） |

### 3.3 路由与 wire 的落点（便于后续改动定位）

- `ChatScreen.kt:1844` `canFollowUp` / `:1848` `canSteer`（使能条件）
- `ChatScreen.kt:1861-1876` `onSteer`（→ `session.send`） / `:1938-1964` `onFollowUp`（→ `session.sendFollowUp`） / `:1961-1963` `onStop`
- `ChatScreen.kt:1675-1687` `QueueRow` 调用 / `:2529-2556` `QueueRow` / `:2563+` `QueueChip` / `:2773-2776` 两颗 chip / `:2844-2853` 发送盘
- `PiSessionViewModel.kt:3192-3255` `send` / `:3332-3369` `sendFollowUp` / `:3373-3384` `stop` / `:3400-3410` `restoreQueue` / `:3466-3477` 两个模式 setter / `:2095-2098` 计数 / `:4339-4340` 会话替换清零 / `:4405` 排队中
- `PiEngineSession.kt:917-921` `drainQueue`（`clear_queue`）/ `:934-937` `stopAndDrainQueue`（drain→abort）
- `ui/chat/QueueRestore.kt:27-29` 合并规则
- `rpc/Commands.kt:47-58` `prompt`（含 `streamingBehavior`）/ `:77-86` `steer` / `:95-105` `follow_up` / `:161-171` 两个模式命令 / `:25-28` `QueueMode` wire 枚举
- `rpc/Events.kt:252-257` `QueueUpdate` / `:507-510` 解析
- `ui/chat/ChatSheets.kt:286` `SessionToolsSheet` / `:308` 标题「会话与队列」/ `:315` 段头「队列模式」/ `:320-331` 两行 / `:464-490` `QueueModeRow`
- `ui/settings/PiSettingsRegistry.kt:243-246` `deliveryOptions` / `:487-507` 两行 / `:1438` 摘要

---

## 4. 需要用户或父代理裁决的条目

| # | 条目 | 事实 | 选项 | 我的倾向 |
|---|---|---|---|---|
| ① | **附件-only 的「插话」收回时丢图** | 可入队（`ChatScreen.kt:1848`、`PiSessionViewModel.kt:3193-3194`），`clear_queue` 只回文本（`agent-session.ts:1587-1595`），`mergeRestoredQueue` 过滤空串（`QueueRestore.kt:28`）⇒ 图静默消失 | (a) 禁用附件-only 的插话（`canSteer` 去掉 `\|\| attachments.isNotEmpty()`）(b) 收回时对“有队列但文本为空”给一句诚实提示 (c) 不改，记已知缺口 | **(b)**。禁用会砍掉一个 RPC 合法能力（`docs/rpc.md:88-90`）；(b) 只加一句提示，不改协议、不改布局 |
| ② | **命名三套并存** | 对话页/队列行/会话队列 sheet 用**插话/排队**（`ChatScreen.kt:2539/2541/2774/2775`、`ChatSheets.kt:321/327`）；设置页与摘要用**穿插/后续**（`PiSettingsRegistry.kt:488/499/1438`）；规范与 v2 板子用**穿插/后续**（`docs/pi-android-ui-spec.md:322/433/657/710/951/978`、`direction-b-v2.html:1384-1385`、`06-v2-construction-reference.md:125/137`）。**仓库里没有任何一条 D 裁决记录这次改名**（`07-construction-decisions.md` D21/D25 直接用“插话/排队”，因为那是用户原话） | (a) 统一到**插话/排队**（用户自己的词）并改规范/板子/设置页 (b) 统一到**穿插/后续**（改对话页）(c) 保留两套 | **(a)**。用户的原话就是“插话”，D21/D25 已经按它落地；改设置页 2 行 + 摘要 1 行 + 规范 6 处即可，不必碰 UI 结构 |
| ③ | **队列明细要不要做** | pi 显示每条原文（`interactive-mode.ts:4375-4382`），v2 板子画逐条明细（`direction-b-v2.html:2702-2718`，含每行「编辑」），我们只有计数（`ChatScreen.kt:2529-2556`） | (a) 照 pi：在 QueueRow 下加一行/条的 `dim` 原文（只读）(b) 照板子：加明细行 + 「编辑」(c) 不改 | **(a)**。文本已经在 `PiEvent.QueueUpdate` 里（`Events.kt:252-257`），`UiState` 加两个 `List<String>` 即可，**零 wire 改动**；(b) 里的「编辑」**做不到**（`clear_queue` 无逐条形态，`docs/rpc.md:139`），会变成假按钮 |
| ④ | **规范里被 D25 取代的两处** | `:433`「STREAMING 点发送 → 弹三选 Sheet」、`:434`「长按发送 → 直接选送达方式」、`:322`「`⤷ 穿插 "…"` ✕」、`:415`「流式中发送过 → 虚线描边 + `⤷ 排队中`」在实现里都不存在，且板子也没有 | (a) 改规范、标注被 D25 取代 (b) 改实现去补回来 (c) 不改 | **(a)**。板子是冻结视觉权威，D25 是用户裁决；规范这四行是更早的稿 |
| ⑤ | **KDoc 引用了 0.85.1 里不存在的方法** | `PiSessionViewModel.kt:3204-3208`（「`_queueUserInput`（`:1388-1413`）… 裸命令也跑扩展 `input` handlers」）、`Commands.kt:68-73`、`07` D21/D23 都基于 `_queueUserInput`。**0.85.1 源码里没有这个方法**：`steer()` 直接展开后进 `_queueSteer`（`agent-session.ts:1387-1398`），`followUp()` 同理（`:1407-1418`）；`emitInput` **只在 `prompt` 里调一次**（`:1185-1191`）⇒ 裸命令**不跑**扩展 input handler，差异比 KDoc 说的大 | (a) 改 KDoc/账本，把差异写成“裸命令不经过扩展 input handler、不经过 pre-flight” (b) 改实现（压缩期也走 `prompt`）——不可能，`prompt` 在压缩期必抛（`:1176-1180`）(c) 不改 | **(a)**。实现是对的，错的是说明；这条也会同时修掉 `07` D21「裸命令绕过 `_processInput`」的措辞 |
| ⑥ | **同一批 KDoc 行号相对 0.85.1 的漂移** | 我们的注释引 `agent-session.ts:1192-1196`（压缩抛错），0.85.1 的 `.ts` 是 **`:1176-1180`**；引 `:1225-1226`（`_queueFollowUp(expandedText, currentImages)`），实际是 **`:1217`**；引 `interactive-mode.ts:4368-4385`/`:4387-4406`，实际 `:4370-4387`/`:4389-4408`（尾差 2 行）。`interactive-mode.ts:3137-3143`、`:4143-4150`、`:4146`、`:4126-4155`、`:4157-4164`、`:2854`、`:3125-3131` **全部正确** | (a) 批量按 0.85.1 校正 (b) 不改 | **(a)**，成本极低（sourcemap 里就能读到原始 `.ts`，见本文档头注） |
| ⑦ | **压缩期是否保留立即气泡** | pi 只在 pending 区列一行（`interactive-mode.ts:4410-4415`），我们先 echo 一条用户气泡（`PiSessionViewModel.kt:3216-3217`/`:3342`） | (a) 保留（用户立刻看到自己说了什么）(b) 删掉贴 pi (c) 不改 | **(a) 保留**，但**必须先修 `PiEngineSession.kt:779-800` 的 KDoc**——它现在声称 `echoUserPrompt`「这条路径已无人调用」，而 D23 后它有两个调用点 |
| ⑧ | **附件-only steer 的按钮语义**（与①相关但更窄） | 见 #5/#12 | 同① | 同① |

**不需要裁决、只需记录的已知共享限制**：中途 attach 时队列计数从 0 开始（`get_state` 不带数组，`agent-session.ts:1598-1601`）；pi 用文本匹配移除队列项（`:646-662`），两条相同文本只删第一条。

---

## 5. 我确认一致的部分（覆盖证明）

1. **两条队列的语义**：steer = 本轮工具调用之后、下一次 LLM 调用之前投递；followUp = 内层 loop 即将退出（无更多工具调用、无 steering）时投递。pi：`agent-loop.js:83/158`（steer）与 `:160-166`（followUp）、`agent-session.ts:1217/1219`；我们：两颗 chip 分别走 `StreamingBehavior.Steer`（`PiSessionViewModel.kt:3252/3306`）与 `StreamingBehavior.FollowUp`（`:3367`），不自己实现投递。
2. **入队通道**：都走 `prompt` + `streamingBehavior`，**不是**裸命令（pi `interactive-mode.ts:3140/4146`；我们 `PiSessionViewModel.kt:3252/3306/3367`），与 D21 的裁决一致。
3. **非流式时的降级**：pi 空闲时 alt+enter 是普通提交（`interactive-mode.ts:4150-4154`）；我们流式才给 chip、空闲由发送盘承担（`ChatScreen.kt:2773`、`:2846`），`streamingBehavior` 只在流式时下发（`PiSessionViewModel.kt:3252/3367` 的 `else null`）。
4. **`followUp` 非空文本门槛**：`interactive-mode.ts:4126-4127` ↔ `ChatScreen.kt:1844` + `PiSessionViewModel.kt:3336-3337`。
5. **队列显示位置**（编辑器上方）：`interactive-mode.ts:876-889/890-900` ↔ `ChatScreen.kt:1675-1687`。
6. **出队顺序**（steering 先、followUp 后）：`interactive-mode.ts:4390-4391` ↔ `PiEngineSession.kt:919-920`、`ChatScreen.kt:2539-2541`。
7. **出队合并规则**：`interactive-mode.ts:4399-4402` ↔ `QueueRestore.kt:27-29`（逐字相同）。
8. **dequeue 不 abort / Stop 才 abort**：`interactive-mode.ts:4157-4158` vs `:2852-2854`、`docs/rpc.md:158` ↔ `PiSessionViewModel.kt:3400-3410`（`drainQueue`）vs `:3373-3384`（`stopAndDrainQueue`）。
9. **空队列出队会说话**：`interactive-mode.ts:4159-4160` ↔ `PiSessionViewModel.kt:3405`。
10. **`clear_queue` 是一次性全部**：`agent-session.ts:1587-1595`、`docs/rpc.md:139` ↔ `PiEngineSession.kt:900-921` 的 KDoc 与实现；双方都没有逐条取回。
11. **模式是两套独立键、默认 `one-at-a-time`、不共用**：`settings-manager.ts:746/756`、`docs/rpc.md:370-371/387-388` ↔ `PiSessionViewModel.kt:549-550`、`PiSettingsRegistry.kt:493/504`。
12. **模式命令与落盘**：`set_steering_mode`/`set_follow_up_mode` 由 pi 自己写 `settings.json` 全局层（`settings-manager.ts:750-752/760-762`）↔ `Commands.kt:161-171` + `PiSessionViewModel.kt:3466-3477`；设置页文件写入会回灌到引擎（`:1083-1087`），两条写者不会漂移（`:1075-1082` 的 KDoc 已论证）。
13. **`queue_update` 是计数/文本的唯一来源**：`agent-session.ts:596-601` ↔ `Events.kt:252-257` + `PiSessionViewModel.kt:2095-2098`；会话替换时清零（`:4339-4340`）。
14. **队列行只在非空时渲染**：`interactive-mode.ts:4373` ↔ `ChatScreen.kt:1675`。
15. **压缩期的投递选择不丢**：pi 的 `queueCompactionMessage(text, "steer"|"followUp")`（`interactive-mode.ts:3130/4136/4410`）↔ 我们的 `meta.compacting` 分支按按钮分别发裸 `steer`/`follow_up`（`PiSessionViewModel.kt:3210-3224/3341-3352`），语义对齐、且不会因丢 `compaction_end` 而永远送不出（D22/D23）。
16. **pi 官方空态零插图**：`session-selector.ts:432`、`model-selector.ts:348`、`oauth-selector.ts:157-158`；三处彩蛋（`armin.ts:8/60`、`daxnuts.ts:10/57`、`earendil-announcement.ts:8/41`）与空态无关——`brand-spec.md:25` 的主断言成立。
