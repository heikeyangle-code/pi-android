# 会话生命周期：pi 写了什么、App 读了什么

本文对照 pi **0.85.1**（commit `bbb61e34`，`/root/pi-src`）查清 App（`app.pi`）会话管理这一整条链路，
给出每一处的 `file:line`、App 与 pi 的差异判定、根因与本次修复，以及**没有验证**的部分。

判定用四种标签：

- **`pi 有 <file:line>`** —— pi 有明确实现，App 应当照做；
- **`pi 无对应物（App 的决定）`** —— pi 里没有这个东西，是 App 侧自己定的；
- **`pi 有但我们够不着（卡在哪）** —— pi 实现了，但 App 这条通道（RPC / 绑定 / 权限）拿不到，写清卡点；
- **一致 / 有意偏离** —— 与 pi 一致，或有意不同并说明理由。

所有 pi 行为都带 `file:line`；凡是**没有**在设备上或容器里真跑过的，一律写进 §7「未验证项」，
不在正文里当实测写。

---

## 0. 结论（先说结果）

两个症状、两个独立原因，都被修了：

1. **列表恒为空**（用户报的"退出再进来，会话列表里找不到"）。根因是**读的布局和 pi 实际写的布局相反**：
   引擎传了 `--session-dir`，pi 就把会话文件**平铺**写在 `<agentDir>/sessions/` 下
   （`core/session-manager.ts:1551-1552`），而 App 的读取器只遍历**子目录**、并显式跳过顶层文件
   （旧 `PiSessionStore.kt:52-61`）。于是 `list()` 恒返回空 —— 列表打不开、自动续接也拿不到"最近一条"。
2. **退出时最后一段对话没落盘**（第二个原因，只在"回合还没结束时退出"发生）。pi 在
   `message_end` 才写一条 entry，而会话文件本身在**第一条 assistant 消息结束前根本不存在**
   （`core/session-manager.ts:1027-1052`）；`PiEngineSession.close()` 关掉 stdin 后 pi 立即退出
   （`modes/rpc/rpc-mode.ts:805-807` → `:727-745`），正在跑的回合就这样丢了。

**验收标准**（用户原话）：退出软件再进去，会话还在、列表里也有、能打开看到完整对话。
修完之后：列表能列出平铺（我们的引擎）与分组（pi 默认布局）两种会话、能打开、`get_entries` 重放出完整对话；
`app.sessions.resumeLast` 打开时按 **pi `-c` 的规则**（文件 mtime + 本 cwd 过滤）切回那条会话；
离开 App 时先 `abort` 等 pi 落盘再关。

---

## 1. 现象

- 在 App 里聊完，退出 App 再进来：对话面板是空的，**会话列表也没有任何一条** ——
  既回不到刚才那条，也打不开它。
- 会话文件其实**在磁盘上**（`<files>/pi/.pi/agent/sessions/*.jsonl`，宿主路径），
  pi 自己也认得它（`SessionManager.continueRecent` 在容器里实测能命中同一批文件，§2.1；
  完整的 `pi -c` 进程启动本身没跑，见 §7-⑥），只有 App 看不见。
- 另一个只在"回合进行中退出"时出现的现象：最后那条回复（以及整个回合）不在文件里。

---

## 2. pi 0.85.1 的会话语义（逐条 `file:line`）

### 2.1 存储位置与两种布局

| 规则 | 出处 |
| --- | --- |
| 没有给出会话目录时，默认目录 = `<agentDir>/sessions/--<cwd 编码>--/`，按 cwd 分子目录 | `core/session-manager.ts:473-486`（`getDefaultSessionDirPath`）；编码规则 `:476-478`：去掉开头的 `/`，把 `/`、`\`、`:` 全换成 `-`，两端加 `--` |
| **显式给** `sessionDir` 时，文件**直接写在该目录**，不再按 cwd 建子目录 | `core/session-manager.ts:1551-1552`：`const dir = sessionDir ? normalizePath(sessionDir) : getDefaultSessionDir(cwd)`；文件名 `join(this.getSessionDir(), ...)`，`:947-949` |
| 打开某个会话文件时若不给 `sessionDir`，目录从**文件所在目录**推导 | `:1562-1580`（`:1579-1580`） |
| 会话文件不是会话但非空 → 抛错，不改文件 | `:905-908` |
| `sessionDir` 的来源优先级：`--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > `settings.json` 的 `sessionDir` | `main.ts:670-676`；帮助文本 `cli/args.ts:431`（"overridden by --session-dir"）；设置键定义 `core/settings-manager.ts:150`，读取 `:724-726` |
| 环境变量名 | `config.ts:508-509`（`PI_CODING_AGENT_DIR` / `PI_CODING_AGENT_SESSION_DIR`），`getSessionsDir()` `:572-573` |

**实测（本机容器，node 直跑 pi 的 `SessionManager`，未联网、未改 pi）**：

```
== A. explicit sessionDir (what the app passes) ==
sessionFile (before any message): <tmp>/agent/sessions/2026-09-13T05-36-56-222Z_<id>.jsonl
sessions dir on disk: []
after appendMessage(user): dir = [] file exists = false     ← 只有 user 消息时不落盘
after appendMessage(assistant): dir = [ '...2026-...jsonl' ] ← 第一条 assistant 结束才建文件
list(cwd, sessionDir) → 1 条（平铺文件被读到）
list(cwdOther, sessionDir) → 0 条（按 header cwd 过滤）
== B. no sessionDir (pi default) ==
<agentDir>/sessions/--tmp-pi-probe-...-workspace--/<file>.jsonl  ← 分组布局
```

复现方式（**没改 pi 一个字节**：`node` 直接加载仓库里的 `src/core/session-manager.ts`，
Node 24 会剥掉类型；只有两个外部 import 需要垫片）见 §附录 A。

### 2.2 文件格式（写端）

每行一个 JSON 对象，LF 结尾（`modes/rpc/jsonl.ts:11` 只按 `\n` 分帧）。

| 行 | 形状 | 出处 |
| --- | --- | --- |
| 第一行 `session` 头 | `{type:"session", version:3, id, timestamp(ISO), cwd, parentSession?}` | 接口 `core/session-manager.ts:32-38`；写点 `:932-939` |
| `message` | `{type:"message", id, parentId, timestamp(ISO), message:AgentMessage}`；`AgentMessage.timestamp` 是**Unix 毫秒数字** | 接口 `:53-56`；写点 `appendMessage` `:1071-1080`；`packages/ai/src/types.ts:425` |
| `session_info` | `{type:"session_info", id, parentId, timestamp, name?}` —— 未命名/空名表示**清除** | `:118-121`，写点 `appendSessionInfo` `:1150-1161` |
| `model_change` | `{type:"model_change", ..., provider, modelId}` | `:63-67` |
| 其它 entry | `thinking_level_change`、`compaction`、`branch_summary`、`custom`、`custom_message`、`label` | `:69-101`（`SessionEntry` 各分支） |
| 公共字段 | 除头以外每条都有 `id` / `parentId` / `timestamp`，构成分支树 | `:46-51` |

**"这是不是一个会话"只看第一行**：第一个能被解析出来的 entry 必须是 `type === "session"`
（`buildSessionInfo` `:707-709`）；空行和坏行在找头时被跳过（`parseSessionEntryLine` `:503-512`）。
头发现（`readSessionHeader`）另外要求 `id` 是字符串（`parseSessionHeaderCandidate` `:566-570`）。

### 2.3 枚举 / 列表 / 命名 / 搜索

| 规则 | 出处 |
| --- | --- |
| 列表函数 `SessionManager.list(cwd, sessionDir?)`：`sessionDir` 给定时读那个目录，否则读该 cwd 的默认目录；按 `modified` **降序** | `:1670-1676` |
| 一层枚举：`readdir(dir)` 里所有 `.jsonl`，**不递归** | `listSessionsFromDir` `:812-843`（`:825` 是那个 `.jsonl` 过滤） |
| `SessionManager.listAll()`（不给目录）：只读 `<agentDir>/sessions` 下**一层子目录**里的 `.jsonl`，顶层文件**不算**，也不递归 | `:1700-1718`（`readdir` `:1706`，逐个目录再 `readdir` `:1716`） |
| `listAll(sessionDir)`（给了目录）：走 `listSessionsFromDir`，即平铺一层 | `:1692-1697` |
| 目录不存在 → 空列表 | `:819-821` |
| `SessionInfo` 字段：`path / id / cwd / name / parentSessionPath / created / modified / messageCount / firstMessage / allMessagesText` | `:739-762`；`messageCount` 数**所有** `message` entry（`:719`）；`firstMessage` 是**第一条 user 消息文本**，没有则 `"(no messages)"`（`:760`） |
| `name` 取**最后一条** `session_info`，空名即清除 | `:714-716` |
| "最后活动时间" `modified` = 最后一条 user/assistant 消息的时间戳（数字优先，回退 entry 的 ISO 串）→ 否则头里的 `timestamp` → 否则文件 mtime | `:674-690`（取值）、`:744-749`（回退链） |
| 头扫描上限 1 MiB（超了当发现失败，不当会话） | `:487-489`、`:571-575` |
| 选择器每行显示：`name ?? firstMessage`，右边 `messageCount` + 相对时间（+ cwd / path） | `modes/interactive/components/session-selector.ts:461-465` |
| 选择器还有：搜索（`re:` 正则 / 模糊词 / 引号短语）、`Ctrl+S` 排序（threaded/recent/relevance）、`Ctrl+N` 只看已命名、`Ctrl+P` 显示路径、`Ctrl+R` 重命名、`Ctrl+D` 删除 | 搜索文本 = `id + name + allMessagesText + cwd`（`session-selector-search.ts:29-31`）；控件列表 `packages/coding-agent/docs/sessions.md:43-48` |
| 选择器把 `parentSessionPath` 组成树（threaded 模式） | `session-selector.ts:205-235` |

**实测（同一个探针，`<agentDir>/sessions/` 里同时放一个分组目录和一个顶层 `.jsonl`）**：

```
sessions/ contents : [ '--tmp-workspace--', '2026-01-01T00-00-00-000Z_flat.jsonl' ]
listAll()          : [ 'grouped' ]   ← 不给目录：只读一层子目录，顶层文件不算
listAll(sessions)  : [ 'flat' ]      ← 给了目录：只读该目录一层
list(cwd, sessions): [ 'flat' ]
list(cwd) 默认目录  : [ 'grouped' ]
```

也就是说"pi 读哪个布局"完全取决于**有没有给它会话目录**，两种都不是异常数据 ——
这正是本次读取器必须同时支持两者的原因。

### 2.4 续接

| 规则 | 出处 |
| --- | --- |
| `-c` / `--continue`：启动时续上**本 cwd** 最近的一条 | 参数 `cli/args.ts:100-101`；帮助 `:285`、示例 `:351` |
| 实现：`SessionManager.continueRecent(cwd, sessionDir)` → `findMostRecentSession(dir, cwd)` | `main.ts:426-430`；`core/session-manager.ts:1589-1598` |
| `findMostRecentSession` 的三条语义：**① 按文件 mtime 降序**（不是 `modified`）；② header 的 `cwd` 必须等于传入 cwd（`sessionCwdMatches`）；③ **只在给它的那个目录里找**，不进子目录 | `:636-653`（mtime `:649`）、`:631-633`、`:626-629` |
| 没有匹配 → 新建（不是报错） | `:1592-1598` |
| `-r` / `--resume`：打开交互式选择器 | 参数 `cli/args.ts:102-103`；`main.ts:406-421` → `cli/session-picker.ts:15-54`（`createStartupTui`，要 TTY） |
| `--mode rpc` 由 `resolveAppMode` 直接判定，与 TTY 无关；`createSessionManager` 对所有模式都会跑，所以 **RPC 下 `-c` 是通的** | `main.ts:108-113`、`:676`、`:426-430` |
| RPC 下 `-r` **不通**：它要走 `selectSession`（`main.ts:406-421`）→ `createStartupTui`，而 RPC 的 stdout 已被 `takeOverStdout` 接管（`main.ts:634-638`），没有 TTY 可用 | 同上 + `cli/session-picker.ts:20`、`:52-53` |
| RPC 切会话的命令：`switch_session { sessionPath }` | `modes/rpc/rpc-types.ts:61`；`rpc-mode.ts:605-611` |
| 切会话的运行时语义：先发 `session_before_switch`（扩展可否决）→ `assertSessionCwdExists` → **用会话自己的 cwd 重建整套 cwd 相关服务** | `core/agent-session-runtime.ts:204-224`（否决 `:133-143`、cwd 检查 `:211`、`createRuntime({cwd: sessionManager.getCwd()})` `:213-221`） |
| 会话记录的 cwd 在 guest 里不存在 → 抛 `MissingSessionCwdError` | `core/session-cwd.ts:23-38`、`:54-59` |
| TUI 遇到这个错会**问用户**，然后用 `cwdOverride` 在"当前 cwd"里重试 | `modes/interactive/interactive-mode.ts:5393-5422` |
| RPC 的 `switch_session` **不接** `cwdOverride`，所以这条补救路径在 App 里没有 | `rpc-mode.ts:605-611`（只传 `command.sessionPath`） |

### 2.5 生命周期命令：RPC 面 vs TUI 面

`RpcCommand` 全集：`modes/rpc/rpc-types.ts:22-74`。

| 能力 | RPC | TUI | App |
| --- | --- | --- | --- |
| `new_session`（含 `parentSession`） | `rpc-types.ts:27`，`rpc-mode.ts:437-449` | `interactive-mode.ts:6435` | 有（`:2093` 一带，`PiCommands.newSession`） |
| `switch_session` | `:61` / `:605-611` | `:5393-5422` | 有 |
| `fork` / `clone` | `:62`、`:63` / `:613-631` | 双 Esc / `/tree` | 有 |
| `get_entries` / `get_tree` | `:65`、`:66` / `:638-655` | 进程内 entries | 有 |
| `set_session_name` | `:68` / `:661-673` | `/name`、`Ctrl+R` | 有 |
| `export_html` | `:60` / `:600-602` | `/export` | 有 |
| **`navigate_tree`（原地移动叶子，不写文件）** | **没有** | `/tree`，`docs/sessions.md:71` | 无（`pi 有但我们够不着`：RPC 没有对应命令，见 `docs/known-gaps.md` §I8） |
| **删除会话** | **没有** | `Ctrl+D` 后确认，`docs/sessions.md:48` | App 自己删文件（`pi 无对应物（App 的决定）`） |
| **`/import`（从 jsonl 恢复会话）** | **没有** | `interactive-mode.ts:6108-6122` | 跳终端（`pi 有但我们够不着`） |
| 会话清理 / 保留策略 | **没有**（`Settings` 接口 `core/settings-manager.ts:106-152` 里没有 retention/prune/cleanup 键） | —— | App 注册表里那一行 `app.sessions.cleanupPolicy` **已删除**（设置面重构：无消费者、pi 无对应物，见 `settings-review.md` §2.2） |

### 2.6 切换后的转录恢复

- pi 的 TUI 不需要"重放"：切会话后它直接从**进程内**的 entries 重画聊天区
  （`rebuildChatFromMessages` / `renderSessionEntries`，`interactive-mode.ts:3922`、`:3780-3796`）。
- RPC 客户端必须自己拉：`get_entries` 是文档化的重连路径（App 注释引 `docs/rpc.md`），
  也就是说"切完会话画面就对了"在 App 里**是 App 的责任**。
- 否决语义：`session_before_switch` 由 `emitBeforeSwitch` 发出（`core/agent-session-runtime.ts:133-143`），
  `new_session` 用 reason `"new"`（`:231`），`switch_session` 用 `"resume"`（`:204`）；
  被否决时返回 `{cancelled:true}`，pi **什么都没做** —— App 不能把 `cancelled` 当成功。

### 2.7 落盘时机与关闭顺序

| 规则 | 出处 |
| --- | --- |
| 每条 entry 在 **`message_end`** 时经 `appendMessage` 写入 | `core/agent-session.ts:669-691` |
| `_persist`：只要文件里还没有 assistant 消息，**什么都不写**（`flushed` 保持 false）；出现第一条 assistant 后才用 `openSync(..., "wx")` 建文件并把此前所有 entry 一次写出；此后每条 `appendFileSync` | `core/session-manager.ts:1027-1057`（`:1029-1042` 是那道 guard，`:1044` 是 `wx`，`:1035`/`:1054` 是两次写） |
| 推论（**实测见 §2.1**）：一条只有 user 消息、assistant 还没回的会话**在磁盘上不存在** | 同上 |
| 关闭：`close()` 关 stdin → `attachJsonlLineReader` 的 `end` → `shutdown()`，**直接 unsubscribe + exit，不等 agent** | `modes/rpc/rpc-mode.ts:805-807`、`:727-745`；jsonl `end` 处理 `modes/rpc/jsonl.ts:46-51` |
| 收到 `SIGTERM`/`SIGHUP` 也走同一个 `shutdown()` | `rpc-mode.ts:367-378` |
| `abort` 是"等它落盘"的正确手段：RPC 的 `abort` **await `session.abort()`**，而 `session.abort()` await `waitForIdle()` | `rpc-mode.ts:428-431`；`core/agent-session.ts:1640-1646` |
| 被 abort 的 assistant 消息同样以 `message_end` 落盘 | `agent-session.ts:669-691` |

所以**正确关闭顺序**是：`get_state` 看 `isStreaming`/`isCompacting` →（忙碌时）发 `abort` 并**等它的 response**
（response 回来就意味着 turn 已停、已落盘）→ 然后才 `close()`。App 的关闭路径原本是直接
`writer.close()` + `process.destroy()`（旧 `PiEngineSession.kt:706-713`），正在跑的回合必丢。

---

## 3. App 现状 × pi：逐项对照

`唯一根因`一列只在有结论时写。

### 3.1 存储与布局

| # | 面 | pi | App（改前） | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 引擎传 `--session-dir /root/.pi/agent/sessions` + 同名环境变量 | 显式目录 ⇒ **平铺**（`:1551-1552`） | `PiEngineHost.kt:275`、`:307`（与 pi 一致） | 一致 |
| 2 | 宿主 `<files>/pi/.pi/agent` bind 到 guest `/root/.pi/agent` | —— | `PiEngineHost.kt:298`（`paths.agentDir.absolutePath to guestAgentDir`） | 一致：pi 写的目录和 App 读的目录是同一个 |
| 3 | 列表读 `<agentDir>/sessions` | —— | `PiSessionViewModel.kt:385`（`PiSessionStore(File(host.paths().agentDir, "sessions"))`） | 一致 |
| 4 | **读取器支持哪种布局** | 平铺（给了目录时，`:812-843`）**和**分组（默认布局，`:473-486`；`listAll` 只读一层分组 `:1706-1718`） | **只读分组、且跳过顶层文件**（旧 `PiSessionStore.kt:52-61`） | **根因**：布局相反 |
| 5 | 递归 | pi 不递归（每个层面都只看一层） | 改前不递归但跳过顶层 | 修后一致 |

**为什么保留 `--session-dir`、不改成"用 pi 的默认按 cwd 布局"**：
① 现有用户的所有会话都是平铺写下的，改目录会让它们从列表里消失（正是这次要修的 bug 的反面）；
② 引擎和终端都用同一个 `sessions` 目录，若只有引擎改成默认布局，两种布局会**同时**存在（照样得两种都读）；
③ pi 的 `-c` / `-r` 在有 `--session-dir` 时本来就只看平铺目录（`:636-653`、`:1692-1697`），
保持平铺才能让"App 的续接"和"pi 的 `-c`"指向同一批文件。
代价：App 必须两种布局都读（已做），并且"最近会话"要按 pi 的 `-c` 规则过滤 cwd（已做）。

### 3.2 文件格式解析

| # | 面 | pi | App（改前） | 判定 |
| --- | --- | --- | --- | --- |
| 6 | 判断"这是不是会话" | 第一个可解析 entry 必须是 `session` 头（`:707-709`），空/坏行跳过（`:503-512`），头发现还要求 `id` 是字符串（`:566-570`） | 不判断：任何 `.jsonl` 都成一条（旧 `readSummary` 无校验） | 已对齐（改后第一种判定；`id` 缺失时回退文件名，见注释） |
| 7 | 头字段 | `id` / `cwd` / `timestamp` / `parentSession`（`:32-38`） | 四个都读（旧代码也读） | 一致 |
| 8 | `message` entry | 数所有 `message`（`:719`）；user/assistant 才更新"最后活动"（`:674-690`） | 数所有 `message`，但**任何角色**都更新最后活动 | 已对齐（改成只看 user/assistant） |
| 9 | 消息时间戳 | 嵌套 `AgentMessage.timestamp`（Unix 毫秒）优先，回退 entry 的 ISO 串（`:674-690`） | 同样（`parseIsoMillis` 显式区分两种表示） | 一致 |
| 10 | `name` | 取最后一条 `session_info`；**空名清除**（`:714-716`） | 只在有非空 name 时覆盖，**空名不清除** | 已对齐（空名 → null） |
| 11 | 未命名会话显示什么 | `firstMessage`，没有 user 消息时字面量 `"(no messages)"`（`:760`），选择器 `name ?? firstMessage`（`session-selector.ts:461`） | `name ?: title`；旧代码在"有消息但没有 user 文本"时回退成**文件名**（时间戳+UUID） | 有意偏离：文案改成 `(空会话)`（中文界面），并去掉文件名回退 |
| 12 | `<agentDir>/sessions/<group>/` 目录名当 cwd 兜底 | 目录名是 cwd 的有损编码（`:476-478`），pi 不用它，只用 header 的 `cwd` | 编码不可逆（`-` 本身合法），只作 header 无 `cwd` 时的兜底 | 有意偏离，且只在 header 缺字段时生效 |
| 13 | 读多少 | `buildSessionInfo` 用 readline **整文件流式读**（`:697-742`），用来数 `messageCount` 和收集 `allMessagesText` | 上限 1 MiB（`headerScanBudget`），与 pi 的**头发现**上限一致（`:487-489`） | 有意偏离：手机上一次列 300 条不能读几百 MB。代价写进 §7 |
| 14 | `model` 字段 | `SessionInfo` **没有** model | App 自己从 assistant 消息 / `model_change` 里取 | `pi 无对应物（App 的决定）` |
| 15 | `allMessagesText`（全量正文，供搜索） | 有（`:761`） | 无 | 有意偏离：1 MiB 上限下无法提供，App 搜索只覆盖标题/cwd/文件名 |

### 3.3 枚举、排序、最近会话

| # | 面 | pi | App（改前） | 判定 |
| --- | --- | --- | --- | --- |
| 16 | 列表按什么排 | `modified` 降序（`:1675`） | `lastActivityAt` 降序 —— 但列表恒空 | 修后一致 |
| 17 | 列举范围 | 选择器默认"本项目"（按 cwd 过滤，`:1672-1674`），`Ctrl+P`/`Tab` 可切"所有项目" | 列**所有** cwd，并按 cwd 分组显示（`SessionsScreen.kt:179-187`） | 有意偏离：App 的列表就是它唯一的选择器，分组展示 + 搜索 cwd 可以覆盖 pi 的两个范围 |
| 18 | 搜索 | `re:` 正则 / 模糊词 / 引号短语 / 三种排序 / 只看已命名（`session-selector-search.ts:29-31`、`docs/sessions.md:43-48`） | 子串匹配 `displayName` / `cwd` / 文件名 + "只看已命名" | 有意偏离：不做正则/模糊/排序切换（`docs/known-gaps.md` §I6 记过同一件事） |
| 19 | **`-c` 的"最近"** | **文件 mtime 降序**（`:649`），且 header `cwd` 必须等于引擎 cwd（`:631-633`） | 用 `list(limit = 1)`，即按"最后活动"取全目录最新的一条，**不过滤 cwd** | 已对齐：改用 `mostRecentForResume`（mtime + cwd 过滤 + 只看平铺目录） |
| 20 | 找不到匹配 | 新建会话，不报错（`:1592-1598`） | `firstOrNull() ?: return` —— **静默** | 已改：目录里有会话但没有本 cwd 的，给一条可读提示 |
| 21 | 列表刷新时机 | —— | 进入列表页 / 手动刷新 / 重命名 / 删除（`SessionsScreen.kt:101`、`:127`，`PiSessionViewModel.kt:2117`、`:2189`、`:2352`） | 一致（够用） |

### 3.4 生命周期命令

| # | 面 | pi | App | 判定 |
| --- | --- | --- | --- | --- |
| 22 | `new_session` | `rpc-types.ts:27` | 有（`PiCommands.newSession`、`PiSessionViewModel.newSession`） | 一致 |
| 23 | `new_session { parentSession }` | 写进 header（`:938`），列表按 `parentSessionPath` 组树（`session-selector.ts:205-235`） | 有（`newChildSession` 传 guest 路径）；列表只标"分支"，不组树 | 有意偏离（树视图只在会话内的 `/tree` 屏） |
| 24 | `switch_session` | `rpc-mode.ts:605-611` | 有；`cancelled` 被当"扩展否决"处理并提示 | 一致 |
| 25 | 切到"cwd 已不存在"的会话 | TUI 会问用户并用 `cwdOverride` 重试（`interactive-mode.ts:5393-5422`） | RPC 不带 `cwdOverride` ⇒ 只能显示 pi 的报错 | `pi 有但我们够不着（RPC 的 switch_session 不接 cwdOverride）` |
| 26 | `fork` / `clone` | `rpc-mode.ts:613-631` | 有 | 一致 |
| 27 | `get_entries` / `get_tree` | `rpc-mode.ts:638-655` | 有 | 一致 |
| 28 | `navigate_tree`（原地跳分支） | 只在 TUI（`docs/sessions.md:71`） | 无 | `pi 有但我们够不着`（RPC 无命令） |
| 29 | `set_session_name` | `rpc-mode.ts:661-673`（空名拒绝） | 有，空名交 pi 拒绝 | 一致 |
| 30 | 删除会话 | 只在 TUI（`docs/sessions.md:48`，优先 `trash`） | App 自己 unlink；当前会话禁止删 | `pi 无对应物（App 的决定）`；本次把 unlink 收进 `PiSessionStore.delete` 的包含性检查里 |
| 31 | `/import` | 只在 TUI（`interactive-mode.ts:6108-6122`） | 跳终端 | `pi 有但我们够不着` |
| 32 | `export_html` | `rpc-mode.ts:600-602` | 有 | 一致 |
| 32b | `/export x.jsonl`（按分支导出一份线性 JSONL） | TUI 走 `AgentSession.exportToJsonl`（`agent-session.ts:3488`）→ `exportSessionToJsonl`（`core/session-export.ts:8-38`，逐条按 `getBranch()` 重连 `parentId`，`session-manager.ts:1274-1285`）；**RPC 没有这个命令**（`rpc-types.ts:60` 只有 `export_html`） | App 自己按同样的字节格式写（`PiSessionViewModel.kt:2245-2262`），条目逐字来自 `get_entries` | `pi 有但我们够不着（RPC 无 jsonl 导出）`，App 侧对齐实现 |
| 33 | `app.sessions.cleanupPolicy`（清理策略行） | **pi 没有**任何 retention/prune 键（`core/settings-manager.ts:106-152`） | 注册表曾有这一行，**无消费者**；描述"删除动作始终可撤销"也不成立 | **已删除**（设置面重构，`settings-review.md` §2.2） |
| 34 | `sessionDir` 设置行（原 `PiSettingsRegistry.kt:689`） | pi 有：`core/settings-manager.ts:150`、`:724-726` | 行曾有，但引擎的 `--session-dir`（`PiEngineHost.kt:275`）与环境变量 `PI_CODING_AGENT_SESSION_DIR`（`PiEngineHost.kt:307`、`PtyLauncher.kt:321`）**都优先于设置项**（`main.ts:670-676`） | **该行已删除**：设置写下去永远不会被读，`App 的决定：不做`（要做需要付出什么见 `settings-review.md` §7.5） |

### 3.5 切换后的转录恢复

| # | 面 | pi | App | 判定 |
| --- | --- | --- | --- | --- |
| 35 | 切完会话画面怎么变对 | TUI 用进程内 entries 重画（`interactive-mode.ts:3922`）；RPC 客户端得自己拉 | `afterSessionReplaced()` → `replayHistory()` → `get_entries` → `seedHistory`（`PiSessionViewModel.kt:1524-1546`）；attach 时也跑一次 | 一致（RPC 侧的唯一可行路径） |
| 36 | 扩展否决 | `session_before_switch`（`agent-session-runtime.ts:133-143`） | `cancelled` → 提示"扩展取消了…"且不重放 | 一致 |
| 37 | 空会话的 `get_entries` | 返回空页而不是错误 | 失败时**不清空**已有转录（注释在 `:1528-1532`） | 一致 |

### 3.6 落盘与关闭

| # | 面 | pi | App（改前） | 判定 |
| --- | --- | --- | --- | --- |
| 38 | 什么时候写盘 | `message_end`（`agent-session.ts:669-691`）；第一条 assistant 前不建文件（`session-manager.ts:1029-1052`） | 不感知 | —— |
| 39 | 关闭引擎 | 关 stdin ⇒ 立即退出、不等 agent（`rpc-mode.ts:805-807`、`:727-745`） | `close()` = `writer.close()` + `process.destroy()`（旧 `:706-713`） | **根因（第二个）**：正在跑的回合丢失 |
| 40 | 正确顺序 | `abort` 的 response 意味着已 `waitForIdle`（`rpc-mode.ts:428-431`、`agent-session.ts:1640-1646`） | 无 | 已修：`closeAfterSettling` |

---

## 4. 根因

**根因 1（列表恒空 ⇒ 用户报的全部症状）**

1. 引擎启动时显式传会话目录（`PiEngineHost.kt:275`、`:307`），于是 pi 把会话**平铺**写在
   `<agentDir>/sessions/` 下（`core/session-manager.ts:1551-1552`）。实测见 §2.1（A 段）。
2. App 的读取器只遍历子目录，并且对顶层条目显式 `if (!group.isDirectory) return@forEach`
   （旧 `app/src/main/kotlin/app/pi/session/PiSessionStore.kt:52-61`）。
   平铺文件全部被跳过，分组目录又不存在 ⇒ `list()` **恒返回空**。
3. 依赖这个列表的两件事一起失效：
   - `SessionsScreen` 没有任何行可显示；
   - `maybeResumeLastSession()` 从 `sessionStore.list(limit = 1)` 取"最近一条"
     （旧 `PiSessionViewModel.kt:860-862`），拿不到 → `return`，**永远不 switch**。
4. 所以用户看到的是"退出再进来对话清空、列表也找不到"——**同一个根因的两个症状**。

**为什么当初会写错**：pi 自己的文档 `packages/coding-agent/docs/session-format.md:7-11`
（"File Location"，`~/.pi/agent/sessions/--<path>--/<timestamp>_<session-id>.jsonl`）只描述了
**默认**布局，没有说 `--session-dir`/`PI_CODING_AGENT_SESSION_DIR` 会把这一层子目录去掉
（`core/session-manager.ts:1551-1552`）。旧 `PiSessionStore` 的 KDoc 正是引用了这份文档 ——
照着文档写读取器，就只会读那个"文档里有、而 App 的引擎从来不写"的布局。

**根因 2（只在回合未结束时退出才出现）**

`close()` 关 stdin → pi 立即 `shutdown()`（`rpc-mode.ts:805-807`、`:727-745`），
而被中止的回合还没走到 `message_end`，因此没落盘（`session-manager.ts:1029-1052`）。
首轮更严重：文件根本还没被创建，会话在磁盘上不存在。

**顺带确认的一条（台账相关）**：`docs/known-gaps.md` §I9 把"`-c` 自动续接"记为**已解决**（`7885599`）。
按上面的链路，它在设备上**从未真正生效过**：`resumeLast` 的第一句就是
`sessionStore.list(limit = 1)…firstOrNull() ?: return`（旧 `PiSessionViewModel.kt:860-862`），
而列表恒空，所以 `switch_session` 一次都没发出去。这条修复的**代码**在，**行为**为零；
§I9 的"已解决"应改为"依赖会话列表，随本次修复一起才生效"。

---

## 5. 本次修复（App 侧，全部在 `app/src/main/kotlin/**`）

**文件：`app/src/main/kotlin/app/pi/session/PiSessionStore.kt`**

1. `list()` 现在**两种布局都读**：顶层 `.jsonl` = 一个会话（cwd 取头里的 `cwd`，取不到就空串），
   子目录 = pi 默认布局（目录名当 header 缺 `cwd` 时的兜底）。混合存在时不重复、不丢。
   （旧行为见 §4 第 2 点；依据 `core/session-manager.ts:1551-1552`、`:473-486`、`:1706-1718`。）
2. `readSummary(file, fallbackCwd)`：第一个可解析行必须是 `session` 头，否则**不是会话**
   （`:707-709`、`:503-512`；非空但解析不出会话的头文件 pi 会拒绝打开，`:905-908`）。
3. 字段与 pi 的 `SessionInfo` 对齐：`name` 空名清除（`:714-716`）、"最后活动"只看 user/assistant
   （`:674-690`）、回退链 `最后活动 → 头时间戳 → mtime`（`:744-749`）、
   未命名文案 `(空会话)`（pi 是 `"(no messages)"`，`:760`）。
4. 扫描被 1 MiB 上限截断时用 mtime 兜底（而不是把"看见的最后一条"当成真的最后活动）——
   理由写在该行注释里（`:749`）。
5. 新增 `mostRecentForResume(cwd)`：pi `-c` 的规则（mtime、cwd 过滤、**只看平铺目录**，
   `:636-653`、`:631-633`、`:1589-1598`）。
6. `delete(file)` 保留包含性检查（只删会话根目录内的普通 `.jsonl`），并被 ViewModel 真正用起来了。

**文件：`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt`**

7. `maybeResumeLastSession()`：改用 `sessionStore.mostRecentForResume(guestWorkspace())`
   （本 App 引擎 cwd 的 guest 拼写，`PiEngineHost.kt:587-591`），不再用 `list(limit = 1)`。
   读目录失败 → 错误提示；目录里有会话但没有本 cwd 的 → 警告提示（不再静默 `return`）。
   默认值不变（`app.sessions.resumeLast` 默认关，与 pi"不带 `-c` 就开新会话"一致，`main.ts:426-443`）。
8. `deleteSession()` 改走 `sessionStore.delete()`，让"只删会话根目录内的 `.jsonl`"这条检查真的运行。
9. `onCleared()`：先把引擎交给 `teardownScope`（进程级、独立于 `viewModelScope`）做
   `closeAfterSettling()`，再清空引用。

**文件：`app/src/main/kotlin/app/pi/engine/PiEngineSession.kt`**

10. 新增 `closeAfterSettling()`：`get_state` → 若 `isStreaming || isCompacting` 发 `abort` 并等 response
    （`rpc-mode.ts:428-431`、`agent-session.ts:1640-1646`）→ 再 `close()`。
    超时 30 s，任何失败路径都落到 `close()`。

**文件：`app/src/main/kotlin/app/pi/engine/PiEngineHost.kt`**

11. `restart()`（`allowInterrupt` 分支）与 `shutdown()` 改用 `closeAfterSettling()`。
    忙时拒绝重启的既有策略不变。

---

## 6. 测试与自检

**新增 bare-JVM harness：`app/src/test/kotlin/app/pi/session/PiSessionStoreCheck.kt`**，
在 `tools/run-app-pure-checks.sh` 里注册为 `sessions`（编译 `PiSessionStore.kt` + `:rpc` 的 `PiJson`/`internal/Json`）。
覆盖：平铺、分组、**混合**、非会话文件（message-only / 空文件 / 1 MiB 之外的 header）、
空行与坏行前置、`session_info` 覆盖与清除、按"最后活动"排序、`limit`、
`-c` 的 mtime/cwd/只看平铺三条语义、删除包含性检查、目录不存在的空列表。

**已跑（本机）**

```
tools/run-app-pure-checks.sh   → OK — 6 harnesses ran on a bare JVM（含 sessions，全部 PASS）
tools/check-nested-comments.py → nested-comments: OK (153 Kotlin file(s) scanned)
tools/typecheck.sh             → typecheck: OK (:rpc + :app, cross-module boundary reproduced)
                                 — 0 error diagnostics, :rpc 0 / :app 0
```

（`run-app-pure-checks.sh` 在本轮期间被并行改过，多了 `extension-error-text` 一个 harness；上面的
6 个里边 `sessions` 是本次新增的。`tools/typecheck.sh` 只编译 `app/src/main/kotlin`，
新 harness 由 `run-app-pure-checks.sh` 编译，两者都过了。上面这条 `typecheck: OK` 取自一次包含
**本次全部功能性改动**的完整运行；此后 `PiSessionStore.kt` 只改过注释，注释改动由
`sessions` harness 的编译覆盖。本机在收尾时同时有多个 agent 在跑 `typecheck.sh`，
所以这些脚本单次耗时远高于它自己的 10 分钟预期。）

**不能进 harness 的部分（为什么、上机怎么验）**

- `PiEngineSession.closeAfterSettling()` 依赖真实 pi 进程：要验的是"回合进行中退出 App，
  再进来能在列表里打开该会话并看到那条回复"。上机步骤见 §7-③。
- `host.guestPathFor()` / VM 里 `guestWorkspace()` 的 guest 拼写与 pi 实际写进 header 的 `cwd`
  是否逐字相等，只能上机读一条真实会话的 header 验证，见 §7-①。

---

## 7. 未验证项（**不要**当成已验证）

① **guest cwd 的逐字相等**（影响 `mostRecentForResume` 的过滤）。
   `maybeResumeLastSession` 用 `guestWorkspace()` = `/workspace/pi/workspaces/workspace-1`
   （`PiEngineHost.kt:587-591` 由 `filesDir` 前缀替换而来），假设 pi 写进 header 的
   `process.cwd()` 就是这个字符串。proot 是否原样回传 `-w` 的路径**没有在设备上验证**。
   **测量**：在设备上聊一句（让文件落盘），读该文件第一行 header 的 `cwd`，与上面这个字符串比对。
   若不相等，`-c` 风格的续接会找不到会话（列表与手动打开不受影响，因为列表不按 cwd 过滤）。

② **终端 TUI 会话与聊天会话混在同一目录**（`PtyLauncher.kt:321` 也设了同一个环境变量）。
   `cwd` 不同（终端是 `/root`），列表会显示成两组；`-c` 风格的续接只认聊天那个 cwd。
   这是照 pi 的语义做的，但"列表里出现 `/root` 组"在真机上的观感没有验证。

③ **`closeAfterSettling()` 在真机上的时序**：`abort` 的 response 是否真的在落盘之后到达
   （代码依据是 `session.abort()` await `waitForIdle()`），以及 30 s 上限在慢设备上是否够。
   **测量**：回合进行中按返回退出 App，等几秒后重新进入，看列表里该会话的条数与最后一条内容。

④ **`sessionDir` 设置行永不生效**（§3 #34）——**已处置**：设置面重构把这一行删了（`settings-review.md` §7.5
   记录了"要让它真的生效"的三步代价：去掉两个启动输入、让 `mostRecentForResume` 也读分组目录、会话列表跟着改）。
   同样，`app.sessions.cleanupPolicy` 行没有消费者、pi 也没有对应机制（§3 #33），**已删除**（同文 §2.2）。

⑤ **1 MiB 扫描上限的两个后果**：超长会话（>1 MiB）的"最后活动时间"退化成 mtime，
   排序可能与 pi 的选择器不同；`allMessagesText` 缺失导致搜索覆盖不到正文。
   只在超长会话上出现，设备上有这样一条会话时可验证。

⑥ **`pi --mode rpc -c` 是否真的能续上**：本文只从代码路径推出（`main.ts:426-430` → `:1589-1598`），
   容器里验证的是 `SessionManager.continueRecent` 本身（§2.1 A 段），**没有**跑过完整的 rpc 模式启动。
   App 没有走这条路（它用 `switch_session`），所以不影响本次修复。

---

## 附录 A：怎么在没有设备的情况下验 pi 的写盘语义

**前提**：`/root/pi-src` 没有 `node_modules`，所以只给两个外部 import 垫片，其余源码原样加载
（`node` v24 会剥掉 TypeScript 类型；`import type` 全被擦除）。脚本放在仓库外（例如 `/tmp/piprobe/`），
**不写入 pi 仓库**。

```js
// /tmp/piprobe/loader.mjs —— 把两个外部包指向垫片
import { pathToFileURL } from "node:url";
const MAP = {
  "@earendil-works/pi-ai": "/tmp/piprobe/shims/pi-ai.js",               // 只有 uuidv7 是运行时值
  "@earendil-works/pi-agent-core": "/tmp/piprobe/shims/pi-agent-core.js", // import type => 空文件
  "cross-spawn": "/tmp/piprobe/shims/cross-spawn.js",                   // config.ts 需要这个绑定存在
};
export function resolve(specifier, context, next) {
  const hit = MAP[specifier];
  return hit ? { url: pathToFileURL(hit).href, shortCircuit: true } : next(specifier, context);
}
```

```js
// probe.mjs（`node --import ./register.mjs probe.mjs`；register.mjs 只做 module.register("./loader.mjs")）
import { SessionManager } from "/root/pi-src/packages/coding-agent/src/core/session-manager.ts";
const cwd = "<一个临时目录>", sessionDir = "<临时目录>/agent/sessions";
const sm = SessionManager.create(cwd, sessionDir);            // A：显式目录
console.log(sm.getSessionFile(), sm.getSessionDir());          //   文件名已定，但文件还没建
sm.appendMessage({ role: "user", content: [], timestamp: Date.now() });
console.log(existsSync(sm.getSessionFile()));                  //   false —— 只有 user 消息不落盘
sm.appendMessage({ role: "assistant", content: [], timestamp: Date.now() });
console.log(readdirSync(sessionDir));                          //   这时才有 .jsonl
await SessionManager.list(cwd, sessionDir);                    //   平铺目录能被列出
await SessionManager.listAll(sessionDir);                      //   同上
SessionManager.continueRecent(cwd, sessionDir).getSessionFile();//  `-c` 命中同一个文件
const sm2 = SessionManager.create(cwd);                        // B：不给目录 => 分组布局
```

`uuidv7` 用 `node:crypto` 的 `randomBytes` + 毫秒时间戳拼一个即可（会话 id 的取值不影响上面的结论）。
