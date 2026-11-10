# 线协议 v1

传输：stdin / stdout，**一行一个 JSON**，UTF-8，只按 `\n` 分行（**不要**用会把 `\r`、U+2028 当行尾的读行器）。
stderr 是日志，不是协议。源码：`app/src/main/assets/guest/sdk-host/protocol.mjs`、`hub.mjs`。
测试里的 `tools/sdk-host/client.mjs` 是「App 侧最小实现」，写 Kotlin 客户端时当行为参照。

## 三种帧

| 方向 | 形状 | 说明 |
|---|---|---|
| App → 宿主 | `{"id":"c1","cmd":"session.prompt","args":{…}}` | 命令。`id` 由 App 生成，宿主原样回带 |
| 宿主 → App | `{"t":"res","id":"c1","ok":true,"data":{…}}` / `{"t":"res","id":"c1","ok":false,"error":{"code","message"}}` | 命令的应答。每条命令**恰好一个**应答 |
| 宿主 → App | `{"t":"evt","seq":12,"epoch":2,"name":"agent","data":{…}}` | 事件，单向 |
| 宿主 → App | `{"t":"req","id":"r7","method":"ui.select","params":{…}}` | 宿主向 App 提问（对话框）。App 用命令 `ui.respond` 回答 |

## 序号与会话代号（App 必须实现的两条规则）

- `seq`：进程内单调递增 1,2,3…。**看到跳号 = 丢帧**，立刻 `session.snapshot` 对账。
- `epoch`：每次会话被替换 +1。事件带着它；**App 丢弃 epoch 小于当前的迟到事件**，否则上一个会话的尾巴会画进新会话。
  当前 epoch 以 `session.bound` 事件和 `session.snapshot` 为准。

## 错误码（App 按 `code` 分支，不要匹配 message 文案）

| code | 含义 |
|---|---|
| `bad_frame` | 这一行不是合法 JSON / 缺 `cmd` |
| `unknown_cmd` | 没有这条命令 |
| `bad_args` | 参数缺失或类型不对 |
| `not_open` | 还没 `session.open` |
| `busy` | 当前状态不允许（如超时、重复 open） |
| `not_found` | 模型 / 条目 / 会话文件不存在 |
| `rejected` | pi 自己拒绝了（`session.prompt` 的预检失败） |
| `unsupported` | 这个宿主 / 这个 pi 版本没有该能力（`sdk.call` 找不到方法时） |
| `internal` | 其他异常，`message` 是原文，`detail` 是栈 |

## 命令

> 括号里是 pi 1.5.0 `rpc-mode.js` 里对应的 RPC 命令，完整对照见 [rpc-to-host.md](rpc-to-host.md)。

### host
`host.hello`（协议版本、命令表、能力表）· `host.ping` · `host.shutdown`

### session
| 命令 | 参数 | 返回 | 备注 |
|---|---|---|---|
| `session.open` | `session?:{file\|id\|continue}`, `sessionDir?`, `flags?`, `resources?:{noExtensions,noSkills,noPromptTemplates,noThemes,noContextFiles}`, `builtins?:{mcp,codemode,tool-search:false}`, `trust?:boolean` | 会话信息 + `epoch` | 只能调一次。先应答，扩展在后台绑 |
| `session.snapshot` | `messages?:boolean` | 与 `state` 事件同形的完整快照 | 对账用（`seq` 跳号、重连时调一次）；`get_state` 的超集 |
| `chat.send` | `message`, `images?`, `behavior?: steer\|followUp`（默认 steer） | `{disposition, state}` | **App 发送按钮用这一条**：空闲时开新一轮，流式中按 `behavior` 排队；应答规则同 `session.prompt` |
| `chat.stop` | `clearQueue?` | `{state}` | 中止（可同时清队列） |
| `session.prompt` | `message`, `images?`, `streamingBehavior?` | `{disposition: started\|queued\|handled}` | **预检通过即应答**，不等整轮；整轮后的失败走 `agent.error` 事件 |
| `session.steer` / `session.followUp` | `message`, `images?` | `{disposition}` | |
| `session.abort` · `session.clearQueue` | | | |
| `session.new` · `session.switch` · `session.fork` · `session.clone` | `parentSession?` · `sessionPath` · `entryId` · — | `{cancelled,…}` | 会触发会话替换，epoch +1 |
| `session.messages` · `session.entries` · `session.tree` · `session.stats` · `session.lastText` · `session.forkMessages` · `session.list` | `since?`（entries）、`all?`（list） | | 读 |
| `session.name` · `session.compact` · `session.export` · `session.reload` | | | |
| `session.retry` · `session.autoCompaction` | `enabled?`, `abort?` / `enabled` | | |
| `session.bash` · `session.abortBash` | `command`, `excludeFromContext?` | bash 结果 | 用户 `!cmd`，先走扩展的 `user_bash` |
| `project.trust` | `trusted` | | 写 pi 的信任库并重载 |

> **变更类命令的应答都带 `state`**（`session.abort/steer/followUp/clearQueue/new/switch/fork/clone/navigate/name/compact/reload/retry/autoCompaction`、`model.set/cycle`、`thinking.set/cycle`、`queue.*Mode`、`catalog.setTools`、`chat.*`），App 不必再追问一次。

### model / thinking / queue
`model.list` · `model.set {provider,modelId}` · `model.cycle` · `thinking.levels` · `thinking.set {level}` · `thinking.cycle` · `queue.steeringMode {mode}` · `queue.followUpMode {mode}`

### catalog
`catalog.commands`（扩展命令 + 提示模板 + 技能）· `catalog.tools` · `catalog.setTools {names}` · `render.message {customType,message,width,expanded?}`（调扩展注册的消息渲染器）

### ui（App → 宿主的 UI 回路）
| 命令 | 参数 | 说明 |
|---|---|---|
| `ui.respond` | `id`, `value?` / `cancelled?` | 回答宿主的 `req`。迟到的回答返回 `{matched:false}`，不是错误 |
| `ui.input` | `surfaceId`, `data` | 把按键/文本喂给组件的 `handleInput`，随后宿主重绘 |
| `ui.resize` | `width`, `height?` | 字符列数。全部表面按新宽度重绘 |
| `ui.close` | `surfaceId` | 用户关掉了弹层 |
| `ui.editorState` | `text` | App 输入框内容同步给宿主（`getEditorText()` 读它） |
| `ui.palette` | `palette:{语义色:"#rrggbb"}` | App 主题变了，换色板 |

### 通用口与总线（「随引擎升级」的杠杆）
| 命令 | 参数 | 说明 |
|---|---|---|
| `sdk.call` | `target`, `method`, `params[]` | 调 `session / runtime / sessionManager / settings / modelRuntime / resourceLoader / extensionRunner` 上的任意**公开**方法。结果自动 JSON 安全化，丢掉的字段列在 `dropped` |
| `sdk.get` | `target`, `prop` | 读属性/getter |
| `bus.subscribe` / `bus.unsubscribe` | `names[]` | 把扩展事件总线上的事件按名转成 `bus` 事件 |
| `bus.emit` | `name`, `data` | 往总线发事件 |
| `subagent.rpc` | `method`, `params`, `timeoutMs?` | pi-subagents 的进程内 RPC（`ping/status/spawn/steer/interrupt/stop/resume/cost`）；没装时 `busy` |

### 服务（骨架，持续迁入）
`packages.list` · `packages.updates`。其余按 [migration.md](migration.md) 步骤 5 加。

## 事件（`evt.name`）

| name | data | 说明 |
|---|---|---|
| `host.ready` | `{pid,node,cwd}` | 进程活了（SDK 还没加载） |
| `host.fatal` / `host.log` | `{code?,message}` | 致命 / 日志 |
| `session.bound` | `{sessionId,sessionFile,first}` | 会话绑定（epoch 已 +1，这条事件带着新 epoch） |
| `ext.bound` | `{ok,first,error?}` | 扩展绑定完成（含所有 `session_start` 处理器） |
| `ext.error` | `{extensionPath,event,error}` | 扩展处理器抛错 |
| `state` | `{reason, state}` | **宿主持有的完整状态快照**（会话、模型、思考等级、工具、上下文用量、token、成本、消息计数、扩展绑定）。会改变快照的 agent 事件触发，100 ms 合并推送；`open` 后、扩展绑定完成后、会话替换后也推。**App 的 UI 状态直接吃它，不要自己攒字段** |
| `agent` | **pi 的 `AgentSessionEvent` 原样**（`message_update` 去掉 `partial`） | 对话、工具执行、队列、压缩、重试、`agent_settled`… |
| `agent.error` | `{message}` | `prompt` 整轮跑完后的失败 |
| `project.trust.required` | `{cwd}` | 项目里有需要信任才加载的资源，且没有决定。宿主已按「不信任」启动，App 应弹窗并发 `project.trust` |
| `bus` | `{name,data}` | `bus.subscribe` 订阅的扩展事件 |
| `ui.notify` | `{message,tone}` | tone: info/warning/error |
| `ui.status` | `{key,text\|null}` | |
| `ui.title` · `ui.editorText` · `ui.paste` | | |
| `ui.working` · `ui.workingVisible` · `ui.workingIndicator` · `ui.thinkingLabel` · `ui.toolsExpanded` | | 工作中提示相关 |
| `ui.widget` | `{key,lines\|null,surfaceId\|null,placement}` | 字符串行 / 清除（`lines:null,surfaceId:null`）/ 组件（`surfaceId`） |
| `ui.chrome` | `{slot: footer\|header\|editor, attached, supported, surfaceId?}` | |
| `ui.surface` | `{surfaceId,key,mode,overlay,rev,interactive,width,lines[]}` | 组件的一次渲染，`lines` 含 ANSI。`rev` 递增，只画最新的 |
| `ui.close` | `{surfaceId,key,mode}` | 表面关闭 |
| `ui.cancel` | `{id}` | 宿主收回了一个提问（超时 / abort / 会话替换）：**App 把对应对话框收掉** |
| `ui.autocomplete` | `{registered}` | 有扩展注册了补全（暂未映射） |

## 宿主向 App 的提问（`req.method`）

`ui.select {title,options,timeout?}` · `ui.confirm {title,message,timeout?}` · `ui.input {title,placeholder?,timeout?}` · `ui.editor {title,prefill?}`
App 用 `ui.respond` 回；用户取消回 `{cancelled:true}`。

## 版本演进规则

- 加命令 / 加事件 / 给已有帧加字段：**不升版本**，App 忽略不认识的东西。
- 改已有字段的含义、删东西：升 `PROTOCOL_VERSION`，`host.hello` 里能看到，App 对不上就报「宿主版本不兼容」。
