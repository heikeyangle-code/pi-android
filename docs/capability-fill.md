# 补齐：导出物交出去 / 排队消息收回不打断 / `/import`

`docs/capability-gap.md` §6 的「未决与设备判据」里，有三条是**可做而未做**的。本文是它们的交付文档：

| 编号 | 缺口 | 本文 | 状态 |
|---|---|---|---|
| P1-3 | 导出物要能被用户拿到 | §1 | 已改（未编译） |
| P0-2 / S-8 | 把排队消息收回输入框而不打断当前回合 | §2 | 已改（未编译） |
| P1-1 | `/import`：从 JSONL 恢复会话 | §3 | 已改（未编译） |

外加一件插单（§4，来自 `docs/regression-hunt.md` §4.1 的可证根因）：
**`replayHistory` 的两处静默失败改成走已有的失败通道**。它排在第一位做。

规矩沿用上一轮：**不改 pi（`/root/pi-src` 只读）、不改载荷、不改 `docs/known-gaps.md`、
禁止 git 写操作、不派子代理、不碰手机、本机不编译**（用户明令：这台「开发机」就是用户的手机，
`tools/typecheck.sh` / `tools/run-app-pure-checks.sh` / Gradle 一律不许跑）。
**CI 是唯一的编译器。** 本文所有结论的证据级别是「代码 + 逐行阅读」，不是「编译通过」。

---

## 0. 先更正一处判据（②的前提是错的）

题面与 `docs/capability-gap.md` 的 P0-2 行都写成「把排队消息**逐条**收回输入框」。**pi 没有逐条收回。**

* `interactive-mode.ts:4157-4164`（`handleDequeue`）→ `:4387-4406`（`restoreQueuedMessagesToEditor`）：
  它调 `clearAllQueues()`，把 **steering + followUp 的全部文本**一次拼给编辑器，
  返回的是**条数**（`restored`），不是某一行的收回。
* 那面「排队消息列表」（`updatePendingMessagesDisplay`，`:4368-4385`）是**只读显示**：每条消息一行，
  底部一行提示「↳ <键> to edit all queued messages」（`:4381-4383`）。列表里**没有任何一行可点**。
* RPC 侧同样没有逐条操作：`clear_queue` 无参数（`rpc-types.ts:26`），handler 直接把整个
  `session.clearQueue()` 返回（`rpc-mode.ts:433-435`）。
* pi 也没有「只清空、不收回」的键位；`clear_queue` 只出现在 `restoreQueuedMessagesToEditor`
  与压缩队列恢复里（`interactive-mode.ts:4354`、`:4436`）。

按用户口径「pi 有的必须 1:1；pi 没有的全不用做」：做的是**一次收回全部、且不 abort**，
**不做**逐条收回。`docs/capability-gap.md` §4.2 的方案本来就是一次收回全部，机制判据无误；
错的是 P0-2 那两行的措辞（已在该文档里更正，并在此留证）。

「保留整组清空」= 现在的 Stop 不动：`clear_queue` + `abort`（`PiEngineSession.stopAndDrainQueue`）。

---

## 1. ① 导出物要能被用户拿到（P1-3）

### 现状（改之前）

* `PiSessionViewModel.exportSession`（`:2795`，改动前）把 HTML 写进 `File(defaultWorkspace(), htmlName)`，
  成功提示只说文件名；**文件不存在时回落到 pi 的响应原文**（那是一句 guest 路径）——
  这是 App 自己那份文案里最后一处把内部目录印给用户的地方。
* `exportJsonl` 写 `File(defaultWorkspace(), fileName)`，提示同样只说文件名。
* 两者都只报「导出成功」，**没有任何动作能把文件交出去**。

### pi 的语义（`file:line`）

| 点 | pi | 依据 |
|---|---|---|
| `/export` 按扩展名分派 | `.jsonl` → `exportToJsonl`，否则 `exportToHtml` | `modes/interactive/interactive-mode.ts:6060-6075` |
| HTML 默认文件名 | `<APP_NAME>-session-<会话文件 basename>.html` | `core/export-html/index.ts:274-281` |
| JSONL 默认文件名 | `session-<ISO 时间戳>`，`:`/`.` 换成 `-` | `core/session-export.ts:23` |
| 报出落点 | `Session exported to: <path>` | `interactive-mode.ts:6068`、`:6072` |
| 「打开/分享导出物」 | **pi 没有这个动作**：它写进用户自己的 cwd，交给操作系统 | `interactive-mode.ts:6060-6075` |

最后一行是判定关键：**「把产物交出去」是 `pi 无对应物（App 的决定）`**，
所以这不是补 pi 的缺口，而是补「App 把文件写进用户打不开的私有目录」这个自己造的缺口。
做法按题面：**复用 App 已有的那条通道**（诊断报告的「保存到 Download + 系统分享」，
`bridge/DeviceSystemActions.export`/`share`）。

### 做法

1. 导出成功后把产物登记进状态（`UiState.exported: ExportedSession`，`:373`），
   提示改成只说后果：**「会话已导出，可以保存到 Download 或分享出去。」**（`:2830`）。
   产物仍留一份在工作区。
2. 对话页出现一条「已导出」行（`ChatScreen.ExportDeliveryRow`，`:1562`）：
   文件名 + **保存到 Download** + **分享** + 关闭。
   两条动作走 `ui/chat/SessionExportDelivery.kt`（`saveToDownloads` / `share`），
   内部就是 `DeviceSystemActions.export`/`share`——与诊断报告完全同一条通道，不新增存储位置。
3. 默认 HTML 文件名改成 pi 的规则（用会话文件 basename，不再用时间戳）：
   `session/SessionExportNaming.kt` 的 `defaultHtmlName`。
4. 文件没写出来时不再回落到 guest 路径，改成一句可行动的失败话（`:2811`、`:2893`）。

### 未做（写清，别当成做了）

* **分享走的是文本**，不是文件本身。`DeviceSystemActions.share` 只支持 `EXTRA_TEXT`；
  要把文件本体交给别的应用得引入 `FileProvider`（App 现在没有），那是**新通道**，
  与「复用已有通道」的要求相反。因此超过 `SessionExportDelivery.MAX_SHARE_CHARS`
  （400 000 字符）的导出物**不尝试分享**，改为提示「先保存到 Download，再从文件管理器里分享」。
* pi 的 JSONL 默认文件名（`session-<ISO>.jsonl`）**没有实现**：App 只有用户显式给了
  `.jsonl` 名字时才走 JSONL 分支（与改动前一致，也与 pi 的分派一致），
  无参数时永远是 HTML。所以那条默认名在这条路径上不可达。

### 设备判据

`/export` → 行里出现文件名 → 点「保存到 Download」→ 文件管理器 Download 里能打开那个 HTML；
再 `/export` 一次 → 点「分享」→ 系统分享面板能选到一个应用。

---

## 2. ② 排队消息一次收回、不打断本轮（P0-2 / S-8）

### 现状（改之前）

只有 Stop（`clear_queue` + `abort`）；队列 chip（`穿插 N` / `后续 N`）不可点。

### pi 的语义（`file:line`）

| 点 | pi | 依据 |
|---|---|---|
| 绑定 | `app.message.dequeue`（alt+up） | `interactive-mode.ts:2899`；`docs/keybindings.md:166` |
| 动作 | 全部队列文本 + 当前输入框文本合并进编辑器，**不 abort** | `interactive-mode.ts:4157-4164` → `:4387-4406` |
| 只有 Stop 路径才 abort | `restoreQueuedMessagesToEditor({ abort: true })` | `interactive-mode.ts:1864`、`:2050`、`:2854` |
| 0 条时给一句话 | `handleDequeue`：「No queued messages to restore」 | `interactive-mode.ts:4157-4164` |
| 一次清空、返回两串文本 | `session.clearQueue()` | `core/agent-session.ts:1608-1616` |
| 合并规则 | `[queuedText, currentText].filter(t => t.trim()).join("\n\n")` | `interactive-mode.ts:4397-4400` |
| 队列事件带**全量文本** | `queue_update { steering, followUp }` | `core/agent-session.ts:150-154`、`:591-597` |

### 做法

1. `PiEngineSession.drainQueue()`（`:884`）：`clear_queue`，**不发 `abort`**；
   `stopAndDrainQueue()`（`:901`）改成先 `drainQueue()` 再 `abort`——
   上一轮那条「从 `clear_queue` 的 response 读文本、别退回 `get_state`」的修复原样保留
   （`docs/hang-and-crash-review.md`）。
2. `PiSessionViewModel.restoreQueue(onRestored)`（`:2226`）：与 `stop` 同形，只是不 abort；
   0 条时按 pi 的话报一句「队列里没有待收回的消息」。
3. `ChatScreen` 的 `QueueRow`（`:1516`）多一个「收回并编辑」动作，
   点击 → `restoreQueue { restored -> draft = mergeRestoredQueue(restored, draft) }`（`:1052`）。
   合并规则抽成纯函数 `ui/chat/QueueRestore.kt`（原 `ChatScreen` 私有函数，语义一字未改），
   因为 Stop 与「收回」两条路径必须合并得一模一样，而 `ChatScreen.kt` 引入 Compose、本机编译不了。
4. Stop 不动：整组清空 + 打断仍在发送键上。

### 未做（写清）

* **队列里逐条文本的显示**没有加。pi 会在编辑器上方逐行列出每条排队消息
  （`updatePendingMessagesDisplay`，`:4368-4385`），App 只有计数 chip；
  `queue_update` 其实带着全量文本（`agent-session.ts:150-154`），所以这是**能做而没做**。
  不做的理由：App 已经把这些文本本地回显进对话流了（F1 的决定，
  `PiSessionEngine.echoUserPrompt` → `PiSessionViewModel.send/sendFollowUp`），
  再列一遍是同一句话在同一屏出现两次；且 `docs/capability-gap.md` §4.2 的方案只要一个动作。
  这是一处**与 pi 的显示差异**，如实记在这里。

### 设备判据

流式中发两条消息 → 队列 chip 出现 → 点「收回并编辑」→ 两条文本回到输入框，
且**回合仍在跑**（状态行还是「工作中」、Stop 仍是方块）；再按 Stop → 清空 + 打断。

---

## 3. ③ `/import`：从 JSONL 恢复会话（P1-1）

### 现状（改之前）

命令面板把 `/import` 标成「本应用没有入口」（`PiSlashCommands.kt`），
会话页（`SessionsScreen`）只有列表/打开/删除/新建。

### pi 的语义（`file:line`）

| 点 | pi | 依据 |
|---|---|---|
| 用法串 | `Usage: /import <path.jsonl>` | `modes/interactive/interactive-mode.ts:6109` |
| 先确认 | `Replace current session with <path>?` | `interactive-mode.ts:6112-6116` |
| 实现 | `runtimeHost.importFromJsonl(inputPath)` | `interactive-mode.ts:6121` → `core/agent-session-runtime.ts:361-405` |
| 文件不存在 | `SessionImportFileNotFoundError` | `agent-session-runtime.ts:46-53`、`:363-365` |
| 命名 | `join(sessionDir, basename(inputPath))`；已存在则 `<name>-1<ext>`、`-2`… | `agent-session-runtime.ts:371-379` |
| 能否决 | `emitBeforeSwitch("resume", destinationPath)` | `agent-session-runtime.ts:381-384` |
| 拷贝 | `copyFileSync(..., COPYFILE_EXCL)` | `agent-session-runtime.ts:387` |
| 校验（会话头） | 第一条可解析行必须是 `{type:"session", id:<string>}`，否则整个条目表被丢掉 | `core/session-manager.ts:551-556` |
| 校验（坏文件） | 非空但条目表为空 → `Session file is not a valid pi session: <path>` | `session-manager.ts:905-908` |
| 校验（空行/坏行） | 跳过 | `session-manager.ts:499-508` |
| 校验（cwd） | header 的 `cwd` 必须存在，否则 `MissingSessionCwdError`；pi 的 TUI 会问是否改用当前目录 | `session-manager.ts:1562-1576` → `core/session-cwd.ts:44-58`；`interactive-mode.ts:2545` |
| RPC 通道 | `switch_session` 收的就是**会话文件路径**，且它自己会走 `emitBeforeSwitch` + `SessionManager.open` + `assertSessionCwdExists` | `rpc-types.ts:61`；`rpc-mode.ts:605-611` → `agent-session-runtime.ts:197-224` |

### 做法

`选一个 .jsonl → 校验会话头 → 按 pi 的命名规则拷进会话目录 → switch_session → 重放`。

* 三个纯规则进 `session/SessionImport.kt`：`verdictOf`（会话头判定）、
  `destinationName`（命名 + 冲突后缀）、`failureSentence`（失败话术）。
* 落在两处入口：`/` 面板的 `/import` 行（`PiCommandAction.ImportSession`，
  `PiSlashCommands.kt:55`/`:190` → `ChatScreen:669` 开文档选择器），
  以及会话页 AppBar 的「导入」（`SessionsScreen.kt:140`）。两处调同一个
  `PiSessionViewModel.importSession(uri)`（`:2571`）。
* `prepareImport`（`:2620`）全部在 `Dispatchers.IO` 上：读有界头部（1 MiB，
  `readBoundedBytes` `:2713`）→ 判定 → 按 pi 的规则选目标名 → 流式拷贝。
* `switch_session` 失败时走 `failureSentence`（`:177`），**一句话原因 + 下一步，且不印路径**。

### 与 pi 的三处差异（写清）

1. **先校验后拷贝**。pi 是先拷后开，坏文件会留在会话目录里；App 校验不过就不拷。
   结果一样（这个文件不是会话），差别只是 App 不制造垃圾文件。
2. **`.jsonl` 后缀先拒**。pi 不查后缀（用法串里写了 `<path.jsonl>`），
   但一个不叫 `.jsonl` 的会话文件不会被 pi 的 picker 列出（`session-manager.ts:825`），
   也不会出现在本应用的会话列表里——所以这里给一句「请选 .jsonl」，
   而不是导入一个列不出来的会话。取不到显示名（provider 不发 `DISPLAY_NAME`）时用生成名，
   只看内容。
3. **cwd 不存在时无法「改用当前目录继续」**。pi 的 TUI 会问（`interactive-mode.ts:2545`），
   RPC 的 `switch_session` 没有 `cwdOverride`（`rpc-types.ts:61`），所以这条路径**如实失败**：
   「这份会话记录来自另一个工作目录，这台设备上没有那个目录……请改从本机导出的会话导入。」
   拷贝出来的那个文件**留在会话目录里**（pi 也留），下次点它会再次失败——这是残留，见 §6。

### 设备判据

会话页「导入」→ 选一个本应用导出的 `.jsonl` → 回到对话页并能看到完整历史、能继续说话；
再选一个随便的 `.json` / 图片 → 出现一句「不是会话文件」且**没有**新会话冒出来。

---

## 4. 插单：`replayHistory` 的两处静默失败

> **这一条不是「卡死」的答案**（用户实测：完全卡死时**界面上没有任何提示**，说明卡住发生在
> 比这里更早或更低的地方；卡死那件事由另一代理在 `docs/regression-hunt.md` 里追）。
> 这条改动的价值只有一个：**失败时给一句话，不再静默**。

依据 `docs/regression-hunt.md` §4.1（另一代理追查，已核）。位置：
`PiSessionViewModel.replayHistory`（现在 `:1881`）。

机制：`get_entries` 把整条会话放在**一个** JSONL 记录里，超过帧上限时 App 收不到它
（`2f3d09b` 之后引擎层已立刻失败并带中文原因；在那之前是等满 120 s 超时），
而这两处 `return` 把失败吞掉 ⇒ 那条失败路径上**零反馈**。这条路是 `d8ac56a` 之后才第一次
可达的（会话列表开始读平铺布局，引擎自己写下的会话以前点不到）。
注意：**能解释的范围只到这里**——它解释「失败被吞掉」，不解释「整屏完全冻住且一句提示都没有」；
后者是另一个症状，别把这条当成它的修复。

改法（只用已有通道，**没有**新增状态/字段/开关）：

* 请求本身失败（`getOrNull() == null`）→ `fail("引擎没能返回这个会话的内容，可以重新打开这个会话再试一次。")`；
* `response.success == false` → `fail("读取会话内容失败：${response.error ?: "原因未知"}。可以重新打开这个会话再试一次。")`。

`fail()`（`:1816-1819`）写 `lastError` + push `Notice.Tone.Error`，
与 `call()` 的失败路径是同一条通道；它还会进 `recentFailures()`（诊断报告），这是净收益。
**没有**顺手动 120 s 超时，**没有**动 `maybeResumeLastSession` 的 `recent == null` 分支
（「没有历史会话就静默开新会话」必须保持静默）。

### 设备判据

打开一个内容很大的历史会话：要么正常出现历史，要么出现那句失败文案（而不是**在这种失败下**
一动不动）。诊断报告里应能看到同一句。
**判据的边界**：这条**不**要求「卡死」消失——卡死是另一个症状（见本节开头）。

---

## 4.1 结论一句话

| 症状 | 本轮做了什么 | 这算修好了吗 |
|---|---|---|
| 打开历史会话**卡死 / 完全无响应** | 只把 `replayHistory` 的失败从静默改成一句话 | **不算**。它是「失败要说话」，不是卡死根因 |
| 打开历史会话后**没有任何反应也没有任何提示** | 同上：那条失败路径现在会说话 | 是（只限「`get_entries` 失败被吞」这一条路径） |
| 导出物拿不出来 / 排队消息收不回 / 不能导入 | ① ② ③ 三件 | 是（未编译，见 §7/§9） |

---

## 5. 改动清单（逐文件，`file:line` 是**改后**的行号）

| 文件 | 改动 | 状态 |
|---|---|---|
| `app/src/main/kotlin/app/pi/session/SessionExportNaming.kt` | **新增**（纯逻辑，80 行）：`isJsonl`（pi 的后缀分派 `interactive-mode.ts:6062-6066`）、`defaultHtmlName`（`export-html/index.ts:274-281`）、`mimeTypeFor` | 已改（未编译） |
| `app/src/main/kotlin/app/pi/session/SessionImport.kt` | **新增**（纯逻辑，177 行）：`verdictOf`、`destinationName`、`wrongSuffixSentence`/`unreadableSentence`/`noFreeNameSentence`/`failureSentence` | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/chat/QueueRestore.kt` | **新增**（纯逻辑，29 行）：`mergeRestoredQueue` 从 `ChatScreen` 移出，语义一字未改 | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/chat/SessionExportDelivery.kt` | **新增**（142 行）：`saveToDownloads`/`share` + `Result(sentence, warning)`；复用 `DeviceSystemActions`，超限不硬发 | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt` | `PiCommandAction.ImportSession`（`:55`）；`/import` 行用它（`:190`） | 已改（未编译） |
| `app/src/main/kotlin/app/pi/engine/PiEngineSession.kt` | `drainQueue()`（`:884`）；`stopAndDrainQueue()`（`:901`）改为复用它 | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` | `ExportedSession`（`:187`）+ `UiState.exported`（`:373`）；`publishExport`（`:2823`）/`dismissExport`（`:2835`）；`exportSession`（`:2795`）/`exportJsonl`（`:2854`）；`restoreQueue`（`:2226`）；`importSession`（`:2571`）+ `ImportPrep`（`:2609`）+ `prepareImport`（`:2620`）+ `displayNameOf`（`:2687`）+ `readBoundedBytes`（`:2713`）；`afterSessionReplaced` 清 `exported`；`replayHistory` 两处静默失败（`:1881`）；删掉已无用的 `JSONL_SUFFIX` | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt` | 导入 picker（`:619`）+ `pick` 分支（`:669`）；队列行「收回并编辑」（`:1052`、`:1516`）；导出交付行（`:1061`、`:1562`）；`mergeRestoredQueue` 改为 import | 已改（未编译） |
| `app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt` | 导入 picker（`:106`）+ AppBar「导入」（`:140`） | 已改（未编译） |
| `tools/run-app-pure-checks.sh` | `sessions` 闭包加入 `SessionImport.kt`/`SessionExportNaming.kt`（`:345-346`）；新增 harness `queue-restore`（注释 `:375-380`，注册 `:381-384`） | 已改（未编译） |
| `app/src/test/kotlin/app/pi/session/PiSessionStoreCheck.kt` | 追加 §9/§10 两组 checks（导入判定与命名、导出命名与 MIME、失败话术不印路径）；顶部 KDoc 加第 7/8 条 | 已改（未编译） |
| `app/src/test/kotlin/app/pi/ui/chat/QueueRestoreCheck.kt` | **新增** harness（83 行，7 条 check） | 已改（未编译） |
| `docs/capability-gap.md` | P0-2 的「逐条」措辞 + §4.2 一行更正 | 已改 |
| `docs/capability-fill.md` | 本文 | —— |

**没有改**：`/root/pi-src`、`app/src/main/assets/**`（载荷）、`docs/known-gaps.md`、
`docs/regression-hunt.md`（另一代理的在制品）、`PiEngineApi.kt`
（`docs/capability-gap.md` §4.2 曾写「4 个文件，含 `PiEngineApi` 一层转发」——
**那一层不需要**：`stopAndDrainQueue` 从来就不经 API，`stop`/`restoreQueue` 直接调
`PiEngineSession`，加一层转发会是没人调的死代码。所以②实际是 3 个文件）。

**没有 git 写操作**（无 `add`/`commit`/`checkout`/`restore`/`stash`）。

**树里还有别人的改动，不要算到这份清单上。** 我落笔期间另一代理在做卡死追查
（`docs/regression-hunt.md`）与工具卡渲染，工作树里同时出现了**它的**在制品：
改过的 `ui/render/PiCodeHighlight.kt`、`ui/render/PiMarkdownComponents.kt`、
`ui/chat/TailFollowCheck.kt`，以及新增的 `ui/blocks/ReadBlock.kt`、`ToolBlockChrome.kt`、
`ToolBodyText.kt`、`ToolOutputParse.kt`。
**那些不是我改的，我没有碰它们**（也没碰 `docs/regression-hunt.md`）。
CI 上如果这些文件红，先找那份追查的作者；本文的清单只覆盖上表里那些文件。

---

## 6. 未验证项与怎么测

**本机没有任何编译或运行**（§9）。下面每条都要在 CI 绿之后、在设备上按判据验。

| # | 未验证项 | 怎么测 |
|---|---|---|
| 1 | ① 的交付通道与 `DeviceSystemActions` 的参数是否匹配 | CI 编译；设备上 `/export` → 「保存到 Download」→ 文件管理器里能打开；「分享」→ 分享面板能选应用 |
| 2 | ① 的默认 HTML 名是否真的是 pi 的规则 | 设备上 `/export`（不带参数）→ 行里的文件名应是 `pi-session-<会话文件 basename>.html` |
| 3 | ① 大文件的分享：`MAX_SHARE_CHARS` 这道闸是否命中 | 导出一个长会话的 HTML（>1.2 MB）→ 点「分享」→ 应出现「内容较大，请先保存到 Download」而**不是**崩溃/静默 |
| 4 | ② 收回后本轮是否真的没被打断 | 流式中发两条 → 点「收回并编辑」→ 两条文本回输入框 + 状态仍「工作中」；再按 Stop → 清空 + 停 |
| 5 | ② 是否没退回上一轮的修复 | 流式中发两条 → 直接按 Stop → 两条文本也要回到输入框（上一轮修的就是这条） |
| 6 | ③ 从会话页导入后能否继续说话 | 会话页「导入」→ 选本应用导出的 `.jsonl` → 对话页有完整历史、再发一句能成 |
| 7 | ③ 的拒绝路径是否有话 | 选一个 `.json` 或图片 → 一句「不是会话文件」；选一个 jsonl 但内容是随便写的 → 一句「没有可识别的会话头」 |
| 8 | ③ cwd 不存在那条路径 | 导入一个从别的设备/目录导出的 `.jsonl` → 应出现「来自另一个工作目录……请改从本机导出的会话导入」，**不是**静默 |
| 9 | ④ `replayHistory` 的失败文案 | 打开一个内容很大的历史会话 → 要么正常，要么出现「引擎没能返回这个会话的内容…」；诊断报告里能看到同一句 |
| 10 | 会话列表会不会因为导入留下坏行 | 导入失败后回会话页看有没有多出一条打不开的会话（设计上只有 cwd 那条路径会留，见 §3 差异 3） |
| 11 | `mergeRestoredQueue` 的行为 | 由 harness `queue-restore` 在 CI 上跑（7 条 check），无需设备 |

### 已知残留（写清，别当成已修）

1. **导入的 cwd 失败会留下拷贝出来的那个文件**：pi 也留（`agent-session-runtime.ts:387` 在
   `SessionManager.open` 之前），本应用选择与它一致而不是自作主张删掉用户的数据。
   代价是会话列表里会出现一条点开仍失败的会话。
2. **分享只能发文本**：见 §1「未做」。
3. **排队消息的逐条文本不显示**：见 §2「未做」。
4. **`/import <path>` 的参数被忽略**：手机上没有路径可打，点一下就是这条命令；
   带参数输入时 `args` 被丢掉，不是错误也不是静默失败（命令照样开选择器）。

---

## 7. 上 CI 最可能出错的点（按可能性排序）

1. **`PiCommandAction` 新增成员的穷尽性**。`ChatScreen.pick` 的 `when` **没有** `else`，
   新增 `ImportSession` 后必须补分支（已补 `:669`）；`SlashPalette.routeComposerText`
   有 `else`（`:245`）所以不用动。**若以后有人删掉那个 `else`，两处都会红。**
2. **Compose 的 `rememberLauncherForActivityResult` 位置**。它必须在组合里**无条件**调用，
   且早于使用它的 `pick`。`ChatScreen.kt:619` 满足这两点；`SessionsScreen.kt:106` 同理。
   本机**没有 Compose 编译器插件**，这一条只有 CI 的 `:app:assembleRelease` 能证。
3. **`runCatching { ... }.getOrNull() ?: run { fail(...); return }`**（`replayHistory:1885`）：
   `run {}` 里最后一句是 `return`，类型是 `Nothing`，`response` 因此是非空 `PiEvent.Response`。
   若有人把 `return` 换成 `return@run`，类型会变成 `Unit?` 并在下一行报错——CI 会立刻红。
4. **纯逻辑闭包的源码清单**：`sessions` 闭包新增了 `SessionImport.kt` / `SessionExportNaming.kt`，
   两个文件必须**保持 Android-free**（它们只用 `java.io`、kotlin stdlib、
   `:rpc` 的 `PiJson`）。若哪天有人 import 了 Android 类，`run-app-pure-checks.sh` 会
   **编译失败**（这正是让它不引用 Android 的原因）。
   同理 `queue-restore` 闭包只有 `QueueRestore.kt`，它是零依赖的。
5. **新 harness 的 main 类名**：`app/src/test/kotlin/app/pi/ui/chat/QueueRestoreCheck.kt`
   必须 `package app.pi.ui.chat` + 顶层 `main`，才会生成脚本里写的
   `app.pi.ui.chat.QueueRestoreCheckKt`；且必须以 `harness: OK` 结尾、失败时 `exitProcess(1)`
   （脚本两半都查）。
6. **`PiSessionState`（`UiState`）新增字段的位置**：`exported` 加在 `busy` 之后、
   `navRequest` 之前，用的是**具名** `copy(exported = ...)`，没有位置参数构造点，
   所以不会静默移位。
7. **未使用的 import**：`PiSessionViewModel` 新增 `android.net.Uri`、
   `android.provider.OpenableColumns`、`app.pi.session.SessionExportNaming`、`SessionImport`
   ——四个都有使用点；删掉的 `JSONL_SUFFIX` 常量已无引用（若 CI 开了
   `allWarningsAsErrors`，一个漏掉的未用 import/常量就会红，这是本仓库最容易踩的一条）。
8. **`check-nested-comments.py`**：本轮新增的 KDoc 里有 `/*`、`` `{ abort: true }` ``、
   `` `[queuedText, currentText]...` `` 这类形状，已自检通过（§8）。
9. **`pi-contract.mjs` 扫源码文本**：它用正则读 `PiSessionViewModel.kt` 的
   扩展 chrome 分派（`"notify" ->` 等）。本轮改动没有碰到那些行，但那个正则对
   **文件形状**敏感——如果 CI 红在这里，先看 `tools/pi-contract.mjs:95-100` 的模式是否被巧合命中。

---

## 8. 自检（本仓库规矩）

| 检查 | 命令（已跑） | 结果 |
|---|---|---|
| 本机**不编译** | —— | 未运行 `typecheck.sh` / `run-app-pure-checks.sh` / Gradle（用户明令） |
| 嵌套注释 | `python3 tools/check-nested-comments.py` | `nested-comments: OK`（本轮新建 5 个 Kotlin 文件：`SessionExportNaming.kt`、`SessionImport.kt`、`QueueRestore.kt`、`SessionExportDelivery.kt`、`QueueRestoreCheck.kt`）。扫描的文件总数写作时是 187、复查时 189 —— 另一代理同时在加文件，**判定只看 `OK`** |
| harness 脚本语法 | `bash -n tools/run-app-pure-checks.sh` | OK（exit 0） |
| 新 harness 的 main 类名 | 人工核对包名/顶层 `main`/`harness: OK`/`exitProcess(1)` | 与脚本 `queue-restore` 的 `app.pi.ui.chat.QueueRestoreCheckKt` 一致 |
| `when` 穷尽性 | `grep -rn "PiCommandAction\." app/src` 逐个看 | 只有 `ChatScreen.pick`（已补）与 `routeComposerText`（有 `else`） |
| 已删常量的残留引用 | `grep -rn "JSONL_SUFFIX" app/src` | 只剩 `SessionExportNaming` 自己的定义与使用 |
| 纯文件是否 Android-free | 人工读 import 头 | `SessionImport.kt`（`java.io`/`kotlinx.serialization`）、`SessionExportNaming.kt`、`QueueRestore.kt` 均无 Android |
| 证据可核 | 本文每行的 `file:line` 都是本轮 `grep`/`sed` 现查的 | —— |
| 括号/分支配平 | 自写的纯 python 小扫描器（懂 Kotlin 的 `//`、`/* */`、`"…${…}"` 模板嵌套）跑了本文改过/新建的 11 个 Kotlin 文件 | 全部 `balanced`（**不是**编译器，只证明括号配平，不证明类型正确） |

**关于那个小扫描器**：第一版不懂 `"…${…}"` 里的嵌套字符串，把
`PiSessionStoreCheck.kt` 里**本来就有的** `message(...)` 助手误报成不配平
（HEAD 版本同样报错，偏移差恰好等于我加在它前面的 KDoc 长度）。改成懂模板嵌套之后就全过了。
这段过程留在这里，是因为「本机没有编译器」时最容易把工具误报当成真问题——
**误报与真错都要能区分**。

---

## 9. 本机未编译

**这台「开发机」就是用户的手机。** 本轮**没有**运行 `tools/typecheck.sh`、
`tools/run-app-pure-checks.sh`、任何 Gradle 任务。允许并已运行的静态检查见 §8。
**本文的结论是「按源码逐行看过」，不是「编译通过」；由 CI 编译验证。**
