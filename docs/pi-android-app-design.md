# pi Agent 安卓独立 App —— 完整设计与分析文档

> **分析对象**：`earendil-works/pi` v0.85.1（clone 于 `/root/pi-src`，17.5 万行 TS，11 个包，250+ 源文件）
> **实测机型**：Xiaomi M2011K2C / Android 14 (SDK 34) / aarch64 / 7 核 / 7.1 GB RAM / 224 GB 存储（约 14 GB 可用）
> **文档范围**：pi 源码全量分析 + Android 平台约束调研 + 完整架构/UI/渲染设计 + 竞品调研 + 实施路线
> **证据分级**：`[实测]` 本机真机验证 · `[源码]` 读 pi 源码得出 · `[文献]` 外部资料（附链接）· `[推断]` 未验证的判断

---

# 第一部分　结论与实测

## 1. 摘要

```
┌───────────────────────────────────────────────────────────┐
│ ① UI 层     Kotlin + Jetpack Compose 原生外壳              │
│             对话流用 WebView 复用 pi 自带渲染器             │
├───────────────────────────────────────────────────────────┤
│ ② 桥接层    JSONL-RPC stdio ⇄ Flow  +  设备 HTTP 桥        │
├───────────────────────────────────────────────────────────┤
│ ③ 内核层    pi 原版（npm 包，零补丁）                      │
│              --mode rpc 主通道 / TUI(真 PTY) 兜底通道       │
├───────────────────────────────────────────────────────────┤
│ ④ 运行时层  proot(glibc) + Ubuntu 24.04 + Node 24 + npm    │
├───────────────────────────────────────────────────────────┤
│ ⑤ 平台层    前台服务 / 无障碍服务 / Shizuku(可选)          │
└───────────────────────────────────────────────────────────┘
```

**一句话**：把 pi 当"内核"塞进手机 App 的外壳。壳负责原生 UI、设备能力、后台保活、打包分发；内核原封不动，于是扩展生态 100% 可用。

**为什么不能重写 pi**：pi 的能力一半在内核、一半在**扩展生态** —— 扩展是同进程加载的 TS/npm 包（`jiti` 转译），能用任意 `node:*` 与 npm 依赖、能按名覆盖内建工具、能注册模型厂商、能拦截工具调用。Kotlin 重写或 QuickJS 嵌入只能得到"形似"。

**为什么不能只嵌 Node**：`nodejs-mobile` 停在 Node 18（pi 要求 ≥22.19）；且 npm 生态的预编译产物是 **glibc linux-arm64**，不是 Bionic。运行时必须是真 glibc Linux 用户态，即 proot。

**为什么 UI 不用从零写**：pi 自带 `src/core/export-html/`（template.html + template.css 1066 行 + template.js 1864 行 + vendored marked/highlight.js），已经实现了 Markdown、代码高亮、**diff 红绿视图**、**thinking 折叠块**、工具调用卡片、会话树侧栏，并且 `tool-renderer.ts` 能把**扩展的 TUI 渲染器转成 HTML**。详见 §20。

---

## 2. 真机实测验证

### 2.1 pi 本体跑通 `[实测]`

```bash
$ npm install --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent@latest
  → node_modules 440 MB；`pi --version` → 0.85.1

$ printf '%s\n' '{"id":"1","type":"get_state"}' '{"id":"2","type":"get_available_models"}' \
    '{"id":"3","type":"get_commands"}' \
  | node .../dist/cli.js --mode rpc --no-session

{"id":"1","type":"response","command":"get_state","success":true,
 "data":{"sessionId":"01a08af5-7293-737c-bf04-a7dda52b85ab","thinkingLevel":"off",
         "isStreaming":false,"isCompacting":false,"steeringMode":"one-at-a-time",
         "followUpMode":"one-at-a-time","autoCompactionEnabled":true,
         "messageCount":0,"pendingMessageCount":0,...}}
{"id":"2","type":"response","command":"get_available_models","success":true,"data":{"models":[]}}
{"id":"3","type":"response","command":"get_commands","success":true,
 "data":{"commands":[{"name":"llama","description":"Manage llama.cpp router models",
                      "source":"extension","sourceInfo":{"path":"<inline:llama.cpp>",...}}]}}
```

**含义**：RPC 协议、JSONL 帧、会话初始化、命令分发、内联扩展加载，在 Android aarch64 + proot 下全部正常。这是本方案最大的未知数，现已消除。

### 2.2 运行时环境 `[实测]`

| 项 | 值 |
|---|---|
| 用户态 | Ubuntu 24.04.3 LTS，**glibc 2.39**，aarch64 |
| 内核 | `5.4.274-qgki` (aarch64) |
| Node | **v24.19.0**（官方 linux-arm64），npm 11.17.0，pnpm 可用 |
| 磁盘 | `/usr` 695 MB，dpkg 已装 123 个包 |
| 已有工具 | `git` `bash` `sh` `curl` `python3`；**缺** `rg` `fd` `gcc` `make` `tmux` |
| 内存 | 总 7.25 GB，可用约 1.2 GB（系统占用高） |
| 网络 | proot 内 `curl 127.0.0.1:3090` **可直连 Android 侧服务**（网络命名空间不隔离）`[实测]` |
| DNS | 静态 `/etc/resolv.conf`：`nameserver 8.8.8.8` + `223.5.5.5` |
| TMPDIR | `/tmp`（已有，必须保证） |

### 2.3 本机 DSH App 的 proot 配方（**直接抄**）`[实测]`

从运行中的 `/proc/6754/environ` 与 `/proc/6754/cmdline` 完整提取：

**环境变量**
```
PROOT_TMP_DIR   = /data/user/0/<pkg>/files/linux/tmp
PROOT_LOADER    = <nativeLibraryDir>/libprootloader.so      ← 关键
PROOT_LOADER_32 = <nativeLibraryDir>/libprootloader32.so
PROOT_L2S_DIR   = <rootfs>/.l2s
LD_LIBRARY_PATH = <pkg>/files/linux/lib:<nativeLibraryDir>
TMPDIR          = /tmp
HOME            = /root
```

**nativeLibraryDir 内的文件**：`libproot.so`、`libprootloader.so`、`libprootloader32.so`

**命令行**
```
<lib>/libproot.so \
  --link2symlink \
  -b <rootfs>/.l2s:<rootfs>/.l2s \
  -L --kill-on-exit -0 \
  --rootfs=<rootfs> --cwd=/root \
  -b /dev -b /dev/urandom:/dev/random \
  -b /proc -b /sys -b /system -b /apex \
  -b /proc/self/fd:/dev/fd \
  -b /storage/emulated/0:/sdcard \
  -b /storage/emulated/0:/storage/emulated/0 \
  /bin/bash -c "<命令>"
```

**进程树**：`libproot.so`(PID 6754) → `node dsh web`(6757) → `bash` → …

### 2.4 下载目录可写 `[实测]`

`/sdcard/Download` 可直接写入（`WRITE_OK`）；`/app/export` 也可用，会注册到 MediaStore 并返回 `content://media/external/downloads/<id>`。

### 2.5 结论

技术风险已从"能不能跑"降级为"怎么做好用"。运行时、内核、渲染三块都有现成的或已验证的路径。

---

# 第二部分　pi 源码完整分析

## 3. 仓库结构与规模

| 包 | 行数/作用 | 对 App 的意义 |
|---|---|---|
| `packages/coding-agent` | ~5.4 万行，交互式编码 Agent CLI（主体） | **运行的就是它** |
| `packages/agent` | Agent 运行时：工具调用、状态、会话；另有实验性 durable harness | 被上面依赖 |
| `packages/ai` | 统一多厂商 LLM API | 纯 HTTP SDK |
| `packages/tui` | 终端 UI 库（差分渲染）+ 原生剪贴板附件 | 只影响 TUI 模式 |
| `packages/protocol` / `client` / `server` | **实验性** CBOR 远程会话协议 | 暂不用 |
| `packages/chord` | 应用组合运行时（服务/复制状态/RPC/插件） | 间接依赖 |
| `packages/telemetry` | 遥测契约 | 无关 |
| `packages/session-backends/sqlite-node` | `node:sqlite` 会话后端 | **CLI 未接线，可选** |
| `packages/evals` | 评测 | 无关 |

## 4. 运行形态与 RPC 协议

### 4.1 四种运行形态

| 形态 | 入口 | 传输 | 状态 |
|---|---|---|---|
| **RPC 模式** | `pi --mode rpc`（`src/main.ts:112`，`src/cli/args.ts:11`） | stdin/stdout JSONL | 稳定 → **App 主通道** |
| 进程内 SDK | `createAgentSession()`（`core/sdk.ts:39`） | 函数调用 | 稳定，可选 |
| JSON 事件流 | `pi --mode json "prompt"` | stdout，一次性，不可驱动 | 稳定 |
| CBOR client/server | `PI_EXPERIMENTAL=1 pi server/client` | 4 字节长度前缀 CBOR + Unix socket | **实验性，不采用** |

另：`src/rpc-entry.ts` 是专用 bin（注入 `--mode rpc`），发布为 `./rpc-entry`；`RpcClient`（`src/modes/rpc/rpc-client.ts:56`）是子进程客户端；`src/client/index.ts` 只有一行 `export * from "@earendil-works/pi-client"`。

**没有 ACP 模式。**

### 4.2 RPC 命令全表（App → pi）

`src/modes/rpc/rpc-types.ts:20-74`

**Prompting**：`prompt`、`steer`、`follow_up`、`abort`、`clear_queue`、`new_session`
**State**：`get_state`、`get_messages`
**Model**：`set_model`、`cycle_model`、`get_available_models`
**Thinking**：`set_thinking_level`、`cycle_thinking_level`、`get_available_thinking_levels`
**Queue**：`set_steering_mode`、`set_follow_up_mode`
**Compaction**：`compact`、`set_auto_compaction`
**Retry**：`set_auto_retry`、`abort_retry`
**Bash**：`bash`、`abort_bash`
**Session**：`get_session_stats`、`export_html`、`switch_session`、`fork`、`clone`、`get_fork_messages`、`get_entries`、`get_tree`、`get_last_assistant_text`、`set_session_name`
**Commands**：`get_commands`
**带外（stdin）**：`extension_ui_response`

### 4.3 RPC 事件（pi → App）

`response`（回显 `id`；失败为 `{success:false,error}`；解析错误 command 为 `"parse"`）、全部 `AgentSessionEvent`、`extension_ui_request`、`extension_error`。

**启动/会话态**：`agent_start`、`agent_end{messages,willRetry}`、`agent_settled`、`session_info_changed`、`thinking_level_changed`、`entry_appended`、`queue_update{steering,followUp}`
**回合/消息**：`turn_start{turnIndex,timestamp}`、`turn_end{turnIndex,message,toolResults}`、`message_start`、`message_update{message,assistantMessageEvent}`、`message_end`
**工具**：`tool_execution_start{toolCallId,toolName,args}`、`tool_execution_update{...,partialResult}`、`tool_execution_end{...,result,isError}`、`bash_execution_update`（带命令 `id`）
**压缩/重试**：`compaction_start{reason:"manual"|"threshold"|"overflow"}`、`compaction_end{result,aborted,willRetry,errorMessage}`、`auto_retry_start/end`、`summarization_retry_scheduled`、`summarization_retry_attempt_start`、`summarization_retry_finished`

**流式细节**（`assistantMessageEvent`）：`text_start` / `text_delta{contentIndex,delta}` / `text_end`、`thinking_start/delta/end`、`toolcall_start{id,toolName}/delta/end{toolCall}`。wire 形态会丢掉累积的 `partial`。

### 4.4 RPC 帧格式与细节

- **严格 LF-only JSONL**：只按 `\n` 切分，容忍尾随 `\r`。**Node 的 `readline` 不符协议**（它还会在 `U+2028/U+2029` 断行，而这两个字符在 JSON 字符串里合法）。实现见 `src/modes/rpc/jsonl.ts`。
- **图片输入**：`images?: ImageContent[]` = `{type:"image",data:<base64>,mimeType}`（`packages/ai/src/types.ts:367`）。
  > 注意：`docs/sdk.md:211` 里写的 `source:{type:"base64",...}` 包装与类型定义不符，以 `types.ts` 为准。
- **流式中发消息必须给 `streamingBehavior`**：`"steer"`（当前回合工具跑完后注入）或 `"followUp"`（本回合结束后）。不给则报错。
- **扩展命令**在流式中也能立即执行（如 `/mycommand`）。
- **输入展开顺序**：扩展命令 → `input` 钩子 → 技能展开（`/skill:name`）→ 模板展开 → `before_agent_start`。
- **Interrupt**：`abort` 会等 idle 才响应；Esc 语义 = `clear_queue` + `abort`，并把被清空的排队文本还回输入框。
- **背压**：RPC 独占 stdout，有 raw write + backpressure（`src/core/output-guard.ts`）。

### 4.5 RPC 的能力缺口（App 必须自补）

| 缺口 | 说明 | App 对策 |
|---|---|---|
| 无会话列表命令 | 没有 list-sessions | 扫 `session-dir`（pi 自己也是 header-only 扫描） |
| 无登录/登出/配 Key | 只有交互式 `/login` | 写 `auth.json`，或调 `npx @earendil-works/pi-ai login` |
| 无主题/键位命令 | RPC 无对应 | 写 `settings.json` / `keybindings.json` |
| 无内建审批原语 | SDK 与 RPC 都没有 | 靠扩展的 `tool_call` 钩子 + `ctx.ui.confirm` |
| `navigateTree` 命令 | 只有扩展的 `commandContextActions` 能到（`rpc-mode.ts:329`） | App 通过扩展命令间接调用 |
| `reload` | 仅扩展可用 | 同上 |
| 内建 TUI 斜杠命令 | 不在 `get_commands`；发了也不执行（`rpc.md:853`） | App 自实现等价功能 |
| 无 provider 认证状态查询 | — | App 读 `auth.json` |

### 4.6 扩展 UI 子协议（`rpc.md:1184+`，实现 `rpc-mode.ts:91-311`）

**请求-应答类**（pi 阻塞等 App 回包，可能有服务端超时自动 resolve）：`select`、`confirm`、`input`、`editor`
```
pi  → {"type":"extension_ui_request","id":"uuid-1","method":"select","title":"...","options":[...],"timeout":?}
App → {"type":"extension_ui_response","id":"uuid-1","value":"Allow"}
     {"type":"extension_ui_response","id":"uuid-2","confirmed":true}
     {"type":"extension_ui_response","id":"uuid-3","cancelled":true}
```

**单向 fire-and-forget**：`notify`、`setStatus`、`setWidget`（**只接受 `string[]`，组件工厂被忽略**）、`setTitle`、`set_editor_text`

`ctx.mode === "rpc"`，`ctx.hasUI === true`；print/json 模式 `hasUI=false`。

### 4.7 CBOR 协议（实验性，仅供了解）

- `PROTOCOL_VERSION = 8`；`hello{version}` / `hello_error` / `request{id,target,call}` / `cancel` / `response` / `service_update` / `attachment`；target = `{serverId}` 或 `{serverId,sessionId,attachmentId}`；serverId 为规范小写 UUIDv4。
- 帧：4 字节大端长度前缀 + CBOR，默认上限 16 MiB；握手 5 秒超时；重复 request id 被拒。
- 服务（chord `ServiceCall`，非固定命令表）：server 域 `SessionDirectory`/`SessionManagement`/`PresentationPlugins`；session 域 `AgentController`/`Models`/`Transcript`/`SessionPlugins`；presentation 域 `SlashCommands`/`PresentationUI`。
- `session-router.ts`：每 sessionId 惰性开一个 `HostedSession`，每客户端一个 attachment，按客户端串行化；支持多会话、多客户端；`SessionNotAttachedError`、`ServerDrainingError`。
- **只提供 Unix domain socket 传输**（`<dir>/<uuid4>.sock`，0600），无 stdio/TCP 内建；`ServerListener`/`ByteTransportFactory` 是扩展点；另有一个实验性 WebSocket relay。

---

## 5. 扩展系统（"100% 兼容"的主战场）

### 5.1 发现与加载

**位置**
- 全局：`~/.pi/agent/extensions/*.ts|*.js`、`~/.pi/agent/extensions/<name>/index.ts`（**不递归超过一层**）
- 项目：`.pi/extensions/*.ts`、`.pi/extensions/<name>/index.ts`

**其他来源**
- `settings.json` 的 `extensions: [file|dir]`，支持 `!`/`+`/`-` glob 过滤器
- `settings.json` 的 `packages: [npm:@scope/pkg@1.2.3, git:host/user/repo@ref, https://…, /abs, ./rel]`
- 子目录里的 `package.json` 带 `pi.extensions` 数组（多入口）
- 约定目录 `extensions/`
- CLI `-e|--extension <path>`（可重复，仅本次运行，装到临时目录）
- SDK 内联工厂：`createAgentSession({ extensions: [factory] })`

**信任门控**
- 项目级资源（`.pi/extensions`、项目包、`.agents/skills`）**只在项目被信任后加载**
- 控制：`~/.pi/agent/trust.json`、`defaultProjectTrust`（`ask`/`always`/`never`）、`--approve`/`-a`、`--no-approve`/`-na`、`/trust`
- 全局扩展与 CLI `-e` 在信任之前就加载
- `project_trust` 事件只在全局/CLI 扩展中触发，第一个 yes/no 生效

**格式**
```ts
export default function (pi: ExtensionAPI) { /* 可 async，await 完才发 session_start */ }
```
- 可 `import`：`@earendil-works/pi-coding-agent`、`pi-ai`（+ `/compat`、`/oauth`、`/providers/all`）、`pi-agent-core`、`pi-tui`、`typebox`（含 `@sinclair/*`、`@mariozechner/*` 别名）、**任意 npm 依赖**、任意 `node:*`
- 核心 pi 包以虚拟模块注入（编译版）或 alias/tsconfig 映射（源码版）；扩展必须把它们声明为 `peerDependencies: "*"`

**运行时**
- **`jiti` 运行时转译 TS**（`moduleCache: false`），`await jiti.import(path,{default:true})`（`src/core/extensions/loader.ts:513`）
- **同进程、完整权限、无沙箱**
- 缓存键 = cwd + generation；`/reload` 清缓存并重初始化所有扩展
- 发现/加载失败只记 diagnostics，**从不中断启动**

**依赖安装**
- npm/git 包：pi 自己跑 `npm install --omit=dev`（git 源在配了 `npmCommand` 时跑 `install`）
- git clone 到 `~/.pi/agent/git/<host>/<path>`；npm 装到 `~/.pi/agent/npm/`（项目级在 `.pi/`）
- `npmCommand` 设置可指定替代包管理器（mise/asdf）
- **本地手写的扩展目录需要用户自己 `npm install`**
- 无权限模型，官方立场"只从可信来源安装"

### 5.2 ExtensionAPI 全量表

**注册（加载期与运行期都可用，运行期注册立即生效，无需 `/reload`）**

| 方法 | 说明 |
|---|---|
| `on(event, handler)` | 订阅事件（见 §5.3） |
| `registerTool(def)` | 注册 LLM 可调用工具；**同名会覆盖内建工具** |
| `registerCommand(name,{description,handler,getArgumentCompletions?})` | Slash 命令；重名自动加 `:1`/`:2` |
| `registerShortcut(keyId,{description,handler})` | 键盘快捷键（**TUI**） |
| `registerFlag(name,{type:"boolean"\|"string",default,description})` + `getFlag(name)` | CLI 标志 |
| `registerMessageRenderer(customType, fn(msg,{expanded,outputPad},theme)→Component)` | **TUI** |
| `registerEntryRenderer(customType, fn(entry,{expanded},theme)→Component)` | **TUI** |
| `registerMarkdownTransformer(fn(md,{messageType,isStreaming,availableWidth})→md)` | 每扩展一个，仅显示 |
| `registerProvider(name, config)` / `registerProvider(Provider)` / `unregisterProvider(name)` | 自定义模型厂商 |

**动作**

| 方法 | 说明 |
|---|---|
| `sendMessage(customMsg,{triggerTurn?,deliverAs:"steer"\|"followUp"\|"nextTurn"})` | 注入自定义消息 |
| `sendUserMessage(text\|parts,{deliverAs?,expandPromptTemplates?})` | 以用户身份发消息 |
| `appendEntry(customType,data)` | 持久化但不进 LLM 上下文 |
| `setSessionName` / `getSessionName` | 会话名 |
| `setLabel(entryId,label?)` | 条目标签 |
| `exec(cmd,args,{cwd,signal,timeout})→{stdout,stderr,code,killed}` | 执行命令 |
| `getActiveTools()` / `getAllTools()` / `setActiveTools([...])` | 工具开关（**支持动态加载工具**） |
| `getCommands()` | 列出所有 slash 命令 |
| `setModel(model)→bool` / `getThinkingLevel()` / `setThinkingLevel(level)` | 模型与思考等级 |
| `pi.events.on/emit(channel,data)` | 扩展间事件总线 |

**`ctx`（ExtensionContext）**

`ui`、`mode`（`tui`/`rpc`/`json`/`print`）、`hasUI`、`cwd`、`sessionManager`(只读)、`modelRegistry`、`model`、`scopedModels`、`thinkingLevel`、`signal`、`isIdle()`、`isProjectTrusted()`、`abort()`、`hasPendingMessages()`、`shutdown()`、`getContextUsage()`、`compact({customInstructions,onComplete,onError})`、`getSystemPrompt()`

**命令 ctx 额外**：`getSystemPromptOptions()`、`waitForIdle()`、`newSession({parentSession,setup,withSession})`、`fork(entryId,{position,withSession})`、`navigateTree(id,{summarize,customInstructions,replaceInstructions,label})`、`switchSession(path,{withSession})`、`reload()`；`withSession` 给的是 `ReplacedSessionContext`（额外有 async `sendMessage`/`sendUserMessage`）。**会话被替换后继续用旧 pi/ctx 会抛错**（设计如此）。

**`ctx.ui`（ExtensionUIContext）**

`select` / `confirm` / `input` / `editor`（`opts?:{timeout,signal}`）、`notify(msg,level)`、`onTerminalInput(handler)`、`setStatus(key,text?)`、`setWorkingMessage?` / `setWorkingVisible` / `setWorkingIndicator({frames,intervalMs})`、`setHiddenThinkingLabel?`、`setWidget(key,string[]|factory,{placement:"aboveEditor"|"belowEditor"})`、`setFooter(factory)`、`setHeader(factory)`、`setTitle`、`custom<T>(factory,{overlay?,overlayOptions?,onHandle?})`、`pasteToEditor` / `setEditorText` / `getEditorText`、`addAutocompleteProvider(factory)`、`setEditorComponent(factory?)` / `getEditorComponent()`、`theme`、`getAllThemes` / `getTheme` / `setTheme`、`get/setToolsExpanded`

### 5.3 事件生命周期全表（30+）

**启动**：`project_trust{cwd}→{trusted,remember?}`、`session_start{reason:"startup"|"reload"|"new"|"resume"|"fork",previousSessionFile?}`、`resources_discover{cwd,reason}→{skillPaths?,promptPaths?,themePaths?}`

**会话**：`session_info_changed{name?}`、`session_before_switch→{cancel}`、`session_before_fork→{cancel|skipConversationRestore}`、`session_before_compact{preparation,branchEntries,customInstructions,reason,willRetry,signal}→{cancel}|{compaction}`、`session_compact{compactionEntry,fromExtension,reason,willRetry}`、`session_compact_failed{...}`、`session_before_tree→{cancel}|{summary}`、`session_tree{newLeafId,oldLeafId,summaryEntry,fromExtension}`、`session_shutdown{reason,targetSessionFile?}`

**Agent/回合**：`before_agent_start{prompt,images,systemPrompt,systemPromptOptions}→{message?,systemPrompt?}`（链式）、`agent_start`、`agent_end{messages}`、`agent_settled`、`ui_prompt_start{kind,title?}` / `ui_prompt_end`、`turn_start{turnIndex,timestamp}`、`turn_end{turnIndex,message,toolResults}`、`message_start` / `message_update` / `message_end`（可返回同角色的 message）

**工具**：`tool_execution_start{toolCallId,toolName,args}`、`tool_execution_update{...,partialResult}`、`tool_execution_end{...,result,isError}`

**模型/网络**：`model_select{model,previousModel,source}`、`thinking_level_select{level,previousLevel}`、`before_provider_headers`（原地改 `event.headers`，置 null 删除）、`before_provider_request{payload}→替换`、`after_provider_response{status,headers}`

**拦截**：`tool_call{toolName,toolCallId,input}` → `{block:true,reason?,terminate?}`（**不做重新校验；handler 抛错按 block 处理，fail-safe**）、`tool_result{...}→部分补丁{content?,details?,isError?,usage?}`（中间件链）、`context{messages}→{messages}`、`user_bash{command,excludeFromContext,cwd}→{operations}|{result}`、`input{text,images,source,streamingBehavior?}→{action:"continue"|"transform"|"handled"}`

### 5.4 自定义工具定义

```ts
{
  name, label, description,
  promptSnippet?, promptGuidelines?,
  parameters: TypeBoxObjectSchema,
  prepareArguments?(args),              // 校验前跑（兼容旧会话）
  execute(toolCallId, params, signal, onUpdate, ctx),
  renderCall?, renderResult?,           // TUI 渲染槽（可独立继承内建）
  renderShell?: "self"
}
```

- `execute` 是 async，参数按 typebox schema 校验（Google 兼容要用 `StringEnum`）
- 返回 `{content:[{type:"text",text}|image], details, usage?, terminate?}`
- **用 `throw` 表示 isError**（return 不会设 isError）
- **流式**：`onUpdate?(partialResult)` → `tool_execution_update` / 部分渲染；`signal` 可中断
- 可以跑 shell（`pi.exec`）、spawn 任意进程、用 Node API 做后台任务
- 文件改动应包 `withFileMutationQueue(absPath, fn)` 以与内建 edit/write 串行
- **输出截断是工具自己的责任**（`truncateHead/Tail/Line/formatSize`，默认 50 KB / 2000 行）
- **同名覆盖内建**时：执行与渲染槽独立（未定义的槽继承内建）；`promptSnippet`/`promptGuidelines` **不**继承；结果形状必须匹配
- **动态加载工具**：先注册全部，保留一个 `search_tools` 加载器处于激活态，运行期 `setActiveTools([...current, ...added])` → 下一个请求即暴露（Anthropic 4.5+/GPT-5.4+ 走原生 deferred loading，否则回退全量列表）
- 内建工具也可通过工厂插拔：`createReadTool/createBashTool/...({operations})`、`spawnHook`、`exposeSessionEnvironment`、`createLocalBashOperations()`（SSH/容器/微虚机场景）

### 5.5 自定义 Provider

```
baseUrl, apiKey（支持 "$ENV" 与 "!cmd"）, api, headers, authHeader,
models[{id,name,reasoning,input,cost,contextWindow,maxTokens,headers?,compat?}],
refreshModels?(ctx)→models, oauth{login,refreshToken,getApiKey}, streamSimple
```
原生形式还支持 `auth` / `getModels` / `filterModels` / `stream`。

### 5.6 技能（Skills）

- 标准 **Agent Skills**：一个目录 + `SKILL.md`
- frontmatter：`name`（必需，≤64，小写/连字符）、`description`（必需，≤1024）、可选 `license`/`compatibility`/`metadata`/`allowed-tools`/`disable-model-invocation`
- 发现：`~/.pi/agent/skills/`、`~/.agents/skills/`、项目 `.pi/skills/`、cwd 及祖先直到 git root 的 `.agents/skills/`（**仅信任项目**）、包内 `skills/` 或 `pi.skills`、settings `skills[]`、`--skill <path>`、`--no-skills`
- **系统提示里只放 name + description（XML）**，正文按需读取（渐进披露）
- `/skill:name [args]` 命令由 `enableSkillCommands` 控制（默认 true）
- 校验告警但照常加载；同名保留第一个
- 技能是"数据 + 脚本"，**没有代码钩子**；扩展可以通过 `resources_discover` 增加技能路径

### 5.7 其他可扩展面

- **AGENTS.md**：全局 `~/.pi/agent/AGENTS.md` + cwd 及祖先的 `AGENTS.override.md` / `AGENTS.md` / `AGENTS.MD` / `CLAUDE.md` / `CLAUDE.MD`（就近优先、去重、worktree shadow 处理）；`--no-context-files`；还有 `SYSTEM.md` / `--system-prompt` / `--append-system-prompt`
- **settings.json**：`~/.pi/agent/settings.json` + `.pi/settings.json`（递归合并，项目覆盖全局）。字段含 `packages` `extensions` `skills` `prompts` `themes` `defaultTools` `theme` `enabledModels` `compaction` `retry` `branchSummary` `shellPath` `shellCommandPrefix` `npmCommand` `sessionDir` `terminal`/`images` `markdown` `httpProxy` `defaultProjectTrust` `tuiMode` 等
- **keybindings.json**：`~/.pi/agent/keybindings.json`，所有动作可重绑，`/reload` 生效
- **prompt 模板**：`~/.pi/agent/prompts/*.md`（不递归）、`.pi/prompts/*.md`、包内 `prompts/`、settings `prompts[]`、`--prompt-template`；frontmatter `description`/`argument-hint`；参数 `$1`、`$@`/`$ARGUMENTS`、`${1:-def}`、`${@:N:L}`
- **主题**：`~/.pi/agent/themes/*.json`、`.pi/themes/*.json`、包内、settings、`--theme`/`--use-theme`；JSON 颜色令牌表；内建 dark/light；扩展可运行期切换
- **pi 包**：npm/git/本地，`pi` 清单（extensions/skills/prompts/themes + glob/排除），`pi install|remove|list|update`，`-l` 项目级，画廊元数据
- **`pi config`**：TUI 里管理资源开关

### 5.8 MCP 与"pi 故意不做的东西"

- **没有 MCP**：全仓无 client/config/dependency（只有一处无意提及的注释）
- **没有** subagent、workflow、task-spawn、plan mode、todo、web search/fetch、notebook、image-gen、memory 工具
- 官方立场（`docs/usage.md:309`）：**这些请用扩展或包实现**
- 仓库自带 **约 75 个示例扩展**（`packages/coding-agent/examples/extensions/`）：
  `permission-gate` `question` `questionnaire` `qna` `timed-confirm` `confirm-destructive` `protected-paths` `plan-mode` `subagent` `todo` `sandbox` `ssh` `gondolin` `git-checkpoint` `git-merge-and-resolve` `auto-commit-on-exit` `dirty-repo-guard` `handoff` `summarize` `structured-output` `dynamic-tools` `dynamic-resources` `kimi-deferred-tools` `tools` `tool-override` `built-in-tool-renderer` `inline-bash` `interactive-shell` `file-trigger` `input-transform` `input-transform-streaming` `commands` `preset` `prompt-customizer` `system-prompt-header` `minimal-mode` `status-line` `model-status` `notify` `session-name` `bookmark` `shutdown-command` `trigger-compact` `custom-compaction` `entry-renderer` `message-renderer` `widget-placement` `working-indicator` `working-message-test` `hidden-thinking-label` `border-status-editor` `modal-editor` `rainbow-editor` `custom-footer` `custom-header` `titlebar-spinner` `event-bus` `reload-runtime` `send-user-message` `truncated-tool` `provider-payload` `project-trust` `mac-system-theme` `claude-rules` `github-issue-autocomplete` `rpc-demo` `overlay-test` `overlay-qa-tests` `snake` `space-invaders` `tic-tac-toe` `doom-overlay` `hello` `pirate` `with-deps` `custom-provider-anthropic` `custom-provider-gitlab-duo` `examples/sdk/*` `examples/plugins/*`

  > `permission-gate.ts` 的做法值得注意：`pi.on("tool_call")` 里匹配危险命令，`ctx.ui.select(...)` 让用户确认；**无 UI 模式默认 block**。

### 5.9 扩展 API 中 TUI 绑定的部分（Android 必须处理）

`ctx.mode === "tui"` 才有意义，RPC 下退化：

| API | RPC 下的行为 |
|---|---|
| `ctx.ui.custom()` + overlay | 返回 `undefined` |
| `setFooter` / `setHeader` / `setWidget(component)` | no-op（`setWidget` 只吃 `string[]`） |
| `setWorkingMessage/Visible/Indicator` / `setHiddenThinkingLabel` | no-op |
| `setEditorComponent` / `getEditorComponent` / `addAutocompleteProvider` / `onTerminalInput` | no-op |
| `getEditorText()` | 返回 `""` |
| `pasteToEditor` | 降级为 `setEditorText` |
| `getToolsExpanded()` | `false` |
| `getAllThemes()` | `[]` |
| `getTheme()` / `setTheme()` | `undefined` / `{success:false}` |
| `registerShortcut` | 无效果（键盘） |
| `registerMessageRenderer` / `registerEntryRenderer` / `renderCall` / `renderResult` | **不参与 GUI 渲染**（但见 §20.3：可用 `createToolHtmlRenderer` 在 Node 侧转 HTML） |
| `registerMarkdownTransformer` | 生效（纯文本变换） |

---

## 6. Agent 核心

### 6.1 分层

- `pi-agent-core`：通用 `Agent`/`agentLoop`，无 I/O（`packages/agent/src/agent.ts`、`agent-loop.ts`）
- 产品层：`coding-agent/src/core/agent-session.ts`（3552 行）包了会话、扩展、压缩、认证、重试
- `packages/agent/src/harness/**`：**另一套** durable 运行时（lanes、JSONL v4、SQLite），只给实验性 server/mini 用，**CLI 不用**

### 6.2 循环步骤

```
agent_start → turn_start → 用户消息事件
  → 每回合：transformContext() → convertToLlm() → {systemPrompt, messages, tools}
           → 解析 API Key → streamFn
           → 流事件：start | text_start/delta/end | thinking_* | toolcall_* | done | error
           → 工具调用 → 工具结果 → turn_end
  → 有工具调用或有 steering 就继续；否则轮询 follow-up；否则 agent_end
```
一个 **turn** = 1 次 LLM 调用 + 它的工具执行。`continue()` 只在最后一条消息能转成 user/toolResult 时才继续。

### 6.3 工具并发

- 默认 **`parallel`**：预检**串行**（`prepareArguments` shim → TypeBox 校验 → `beforeToolCall`），然后允许的工具**并发**执行
- `tool_execution_end` 按完成顺序发，但 toolResult 消息按助手源顺序追加
- 若配置为 `sequential`，或**任一**调用指向 `executionMode:"sequential"` 的工具 → 整个批次串行（8 个内建工具都没设，所以默认并行）
- 同文件写入由 `file-mutation-queue.ts` 串行化（按 realpath）
- `afterToolCall` 可覆盖 content/details/isError/usage/terminate
- 批次提前停止：**每个** finalized 结果都 `terminate:true` 才停
- `stopReason:"length"` → **全部**工具调用按"可能被截断"失败，而不是执行（`failToolCallsFromTruncatedMessage`）

### 6.4 取消 / 中断

- 每次运行一个 `AbortController`；`agent.abort()`
- signal 在每个预检后与工具内部检查；被中断的工具返回错误结果，`stopReason:"aborted"` 结束运行
- `steer(msg)`：当前回合工具跑完后注入
- `followUp(msg)`：仅在循环本会停止时注入
- `QueueMode`：`one-at-a-time`（默认）| `all`；`clear*Queue()`

### 6.5 重试

- HTTP 层（`packages/ai`）：408/409/429/5xx、`x-should-retry`、`retry-after[-ms]`，最大延迟 60 秒
- Agent 层：`RetryPolicy{enabled:true,maxRetries:3,baseDelayMs:1000}` + `retryAssistantCall`（压缩/分支摘要也用它）
- RPC：`set_auto_retry` / `abort_retry`
- `shouldStopAfterTurn` / `prepareNextTurn` 支持优雅停止与回合间换模型/上下文

### 6.6 Token 计账

`Usage{input,output,cacheRead,cacheWrite,cacheWrite1h,reasoning,totalTokens,cost}`
`estimateContextTokens` = 最后一次助手的 usage + 尾随消息的 chars/4 估算（**图片按 4800 字符计**）
`usage-totals.ts` 按 provider/model 聚合成本。

### 6.7 压缩（Compaction）

| 项 | 值 |
|---|---|
| 触发 | `contextTokens > contextWindow - reserveTokens`，**reserveTokens 默认 16384**（可按模型覆盖） |
| 检查时机 | 运行中回合之间、新 prompt 之前、运行结束之后 |
| 另两种入口 | provider 上下文溢出恢复、手动 `/compact [instructions]` |
| 切割点 | 从最新往前退到 `keepRecentTokens`（默认 **20000**） |
| 合法切割 | user/assistant/bashExecution/custom；**绝不切在 tool result** |
| 跨回合切割 | 拆成两段摘要再合并 |
| 产物 | `CompactionEntry{summary, firstKeptEntryId, tokensBefore, usage, details{readFiles,modifiedFiles}, fromHook}` |
| 重复压缩 | 从上一个 `firstKeptEntryId` 重新开始 |
| 摘要结构 | 结构化 markdown（Goal / Constraints / Progress / Key Decisions / Next Steps / Critical Context）+ 累积 `<read-files>` / `<modified-files>`；工具结果裁到 2000 字符 |
| 范围 | **只压当前分支**；废弃分支走独立的**分支摘要**（`/tree` → `BranchSummaryEntry`） |
| 细节 | 摘要调用用全新 routing session id，且禁用 prompt-cache 写入 |

### 6.8 系统提示组装

`core/system-prompt.ts` + `resource-loader.ts` + `skills.ts`：

1. 静态人设（"pi 里的专家编码助手"）
2. `Available tools:` 各工具的 snippet
3. `Guidelines:`（与工具相关；**只有 grep/find/ls 缺失时才提示"用 bash 做 ls/rg/find"**；再加 "Be concise"、"Show file paths clearly"、用户 `promptGuidelines`）
4. pi README/docs/examples 的绝对路径
5. `--append-system-prompt`
6. `<project_context>`：上下文文件内容块
7. `<available_skills>` XML（name/description/location，**仅当 read 或 bash 启用**）
8. `Current working directory: <cwd>`

**没有 git status、没有日期、没有 OS/平台环境块。** 技能正文不注入，模型按需读 `SKILL.md`。
`--system-prompt` 替换基础部分，但仍会追加上下文文件、技能、cwd。

---

## 7. 内建工具（共 8 个）

注册表：`core/tools/index.ts`，`ToolName = "read"|"bash"|"powershell"|"edit"|"write"|"grep"|"find"|"ls"`

> ⚠️ **默认激活只有 `read` `bash` `edit` `write`**；`grep`/`find`/`ls`/`powershell` 已注册但需显式开启（`--tools`、settings、`--no-builtin-tools`）。
> **App 应默认开启 grep/find/ls**，否则模型只能退回 bash 跑 rg/fd，手机体验更差。

截断默认：`DEFAULT_MAX_LINES=2000`、`DEFAULT_MAX_BYTES=50KB`，grep 行上限 500。

| 工具 | 参数（必需/可选） | 行为 | 需要的 OS 能力 |
|---|---|---|---|
| `read` | `path`；`offset`(1 起)、`limit` | 文本头部截断 2000 行/50 KB 并附 `Use offset=` 提示；图片 jpg/png/gif/webp/bmp 以 `image` 块返回，压到最大 2000×2000 / 4.5 MB base64；非视觉模型给省略说明。**不输出行号** | 文件读；图片缩放（photon WASM/native） |
| `bash` | `command`；`timeout`(秒，无默认，最大 2147483.647) | `/bin/bash -c`（回退 `sh -c`），detached 进程组，stdout+stderr 合并，中断/超时树杀，非零退出→错误。尾部截断 2000 行/50 KB，溢出写到 `$TMPDIR/pi-bash-*.log`；节流的流式更新。注入 `PI_SESSION_ID/PI_SESSION_FILE/PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL` | 进程 spawn，**无 PTY**；文件系统 |
| `powershell` | 同 bash | 同语义，UTF-8 前导；非 Windows 抛错 | Windows 专用 |
| `edit` | `path`；`edits[]` of `{oldText,newText}` | 所有编辑对**原始文件**匹配，必须唯一且不重叠；BOM/EOL 归一；按文件排队；返回统一 diff/patch 到 `details` | 文件读+写 |
| `write` | `path`；`content` | `mkdir -p` + 覆盖；每文件排队 | 文件写 |
| `grep` | `pattern`；`path`、`glob`、`ignoreCase`、`literal`、`context`、`limit`(100) | `rg --json --line-number --hidden`，尊重 `.gitignore`；输出 `path:LINE: text`（**pi 里唯一的行号来源**）；单行上限 500 字符；有匹配数/字节数截断提示 | spawn `rg`（缺失时联网下载） |
| `find` | `pattern`；`path`；`limit`(1000) | `fd --glob --hidden`，非 git 仓库加 `--no-require-git`，pattern 含 `/` 时加 `--full-path`；目录带 `/` 后缀 | spawn `fd`（缺失时下载） |
| `ls` | `path`(默认 `.`)；`limit`(500) | readdir，大小写不敏感排序，目录带 `/`，含点文件 | 文件读 |

补充：
- `path-utils.ts`：去 `@` 前缀、展开 `~`、Unicode 空格归一、macOS NFD/弯引号/NBSP 重试
- **无沙箱**：任意绝对路径
- 截断辅助：`truncateHead/truncateTail/truncateLine/formatSize`
- `packages/agent/src/harness/tools/` 是一套平行的 read/bash/edit/write（走 `ExecutionEnv`，可远程/durable），CLI 不用

---

## 8. 会话持久化与格式

- **产品路径**：`core/session-manager.ts`，**JSONL v3，一会话一文件**
  路径：`~/.pi/agent/sessions/--<cwd 里 / \ : 换成 ->--/<ISO>_<UUIDv7>.jsonl`
  （`--session-dir`、`PI_CODING_AGENT_SESSION_DIR` 可覆盖）
- **头部行**：`{type:"session",version:3,id,timestamp,cwd,parentSession?}`
- **行类型**：`message`、`model_change`、`thinking_level_change`、`compaction`、`branch_summary`、`custom`（扩展状态，**不进上下文**）、`custom_message`（**进上下文**）、`label`、`session_info`；每行 `{type,id,parentId,timestamp}`，树靠 `id`/`parentId`（8 位十六进制 id）
- **写入**：append-only `appendFileSync`，**首条助手消息前缓冲、无 fsync**；v1/v2 自动迁移到 v3
- **恢复**：`-c`（按 mtime 取最新）、`-r`（选择器）、`--session <path|部分 UUID>`、`--session-id`、`--no-session`
- **分支**：**没有 COW** —— `/fork`（从某条用户消息）、`/clone`（在叶子）、`createBranchedSession` 都**写新文件**并复制分支路径 + `parentSession`；`/tree` 原地移动叶子（可选写分支摘要）
- **导出**：`pi --export <in.jsonl> [out.html]` → 自包含 HTML（`core/export-html/`，vendored marked + highlight）；`/export`；`/share`（私有 gist / Radius，先 `exportSessionToJsonl` 出当前分支）；RPC `export_html`
- **大会话**：增量追加；发现阶段只看头部（扫描上限 1 MiB、4 KiB 缓冲）；列表用 readline 并发 10 并缓存 `firstMessage`+`allMessagesText`；**打开时整体载入，无懒索引**；`--session` 指向不可解析的非空文件会报错而不是覆盖；存储的 cwd 不存在是硬错误
- **SQLite**：只作为**库**存在（`packages/session-backends/sqlite-node`，用内建 `node:sqlite`，WAL + `BEGIN IMMEDIATE`）给 durable harness 的 `SessionRepo`；**CLI 没有接线**，没有设置项也没有环境变量。harness 的 JSONL v4 同理实验性

---

## 9. 项目 / 工作区模型

- **以 cwd 为中心，不假设 git**：没有仓库/远端/分支也能用
- 会话按 cwd 编码后的目录名分桶；`--session-dir` 可覆盖
- 项目级资源（`.pi/settings.json`、`.pi/{extensions,skills,prompts,themes}`、项目包、`.agents/skills`）**只在项目被信任后加载**（`trust-manager.ts`、`/trust`、`--approve/--no-approve`、`cli/project-trust.ts`）
- git 只在两处有意义：`fd` 的 gitignore 行为、祖先 `.agents/skills` 搜索的终止点

---

## 10. 模型 / Provider 层

### 10.1 10 种 wire API

`openai-completions`、`openai-responses`、`azure-openai-responses`、`openai-codex-responses`、`anthropic-messages`、`google-generative-ai`、`google-vertex`、`bedrock-converse-stream`、`mistral-conversations`、`pi-messages`（pi 自家 Radius 网关，SSE）

### 10.2 40 个内建 provider

anthropic、openai、google、google-vertex、amazon-bedrock、azure-openai-responses、openai-codex、github-copilot、xai、groq、cerebras、**deepseek**、mistral、openrouter、vercel-ai-gateway、together、fireworks、baseten、nvidia、huggingface、**minimax(+cn)**、**moonshotai(+cn)**、**zai(+coding-cn)**、**kimi-coding**、**opencode(+go)**、cloudflare（workers-ai / ai-gateway）、**qwen-token-plan(+cn/individual)**、**xiaomi(+cn/ams/sgp)**、ant-ling、radius、llama.cpp

图片**生成** API：只有 `openrouter-images`。

### 10.3 认证

- provider 自有 `ProviderAuth{apiKey,oauth}`；优先级：存储凭证 → 环境变量 → 环境 ambient（AWS 链、gcloud ADC）
- 持久化在 **`~/.pi/agent/auth.json`**（**0600，明文**，`proper-lockfile` 串行化）
  形状：`{providerId: {type:"api_key", key, env?}}` 或 `{type:"oauth", access, refresh, expires}`
  **`key` 可以是一个 shell 命令**
- **没有 OS keychain / keytar**
- OAuth 支持：Anthropic Claude Pro/Max、OpenAI Codex（ChatGPT Plus/Pro）、GitHub Copilot、OpenRouter PKCE、Kimi Code、xAI、Radius
- **没有 `pi login` 子命令** —— 交互式 `/login <provider>`；另有 `pi auth check|print-api-key|print-bearer-token`；独立 CLI `npx @earendil-works/pi-ai login`
- 目录：`models-store.json`；自定义 provider 用 `models.json`

### 10.4 流式

- `AssistantMessageEvent` 带 `contentIndex` 与实时 `partial`；text/thinking/tool-call 各 start·delta·end
- 工具参数累积 `partialJson` → 每个 delta 走 `parseStreamingJson`（partial-json + 修复），`toolcall_end` 时给出权威调用
- Google 只发一个完整的 tool-call delta
- 传输：`sse | websocket | websocket-cached | auto`
- **`StreamFn` 不允许抛异常** —— 错误编码成 `stopReason:"error"` 的消息

### 10.5 图片输入

base64 `{type:"image",data,mimeType}`，来源：`read` 读图片文件、`@file`、Ctrl+V 剪贴板 → 自动缩放到最大 2000×2000 / 4.5 MB base64，不支持的格式转 PNG；非视觉模型静默丢弃并附说明。

### 10.6 工具调用兼容性坑

- `tool_choice` 只有 `auto|none`
- per-model `compat` 开关：严格 JSON schema、grammar 工具、`requiresToolResultName`、`requiresAssistantAfterToolResult`、`requiresThinkingAsText`、thinking 格式、Anthropic beta 头、Opus 4.7+ 拒绝 `temperature`
- 多个工具调用 = 多个 content block；**没有任何地方设 `parallel_tool_calls`**

### 10.7 本地模型

`coding-agent/src/extensions/llama/**` **不是一个 runner**，而是配置**外部 llama.cpp router**：
- `llama-server`，默认 `http://127.0.0.1:8080`，环境变量 `LLAMA_BASE_URL` / `LLAMA_API_KEY`
- provider id `llama.cpp`，走 `openai-completions`，关闭 store / developer-role / reasoning-effort
- 模型从 `/props` + `/v1/models` 取；`/llama` 可加载/卸载并从 HuggingFace 下载 GGUF
- 任何 OpenAI 兼容服务（Ollama/vLLM/LM Studio）都能通过 `models.json` 接
- 模型元数据：构建期取 `models.dev/api.json`，运行期从 `pi.dev/api/models/providers/<id>` 覆盖（etag，4 小时）

### 10.8 多 Agent

**产品里没有** —— 没有 subagent / task-spawn / workflow / parallel-agent 工具；一个 `AgentSession` 跑一个循环。
唯一的多 Agent 原语在**实验性 durable harness**：一个 Session 挂多个 **Branch/AgentLane**，各自模型配置、队列、至多一个操作，共享一棵 write-once entry 树，可并行；通过 `packages/{server,client,protocol}`（CBOR v8）暴露，藏在 `pi experimental server|client` 后面，**不在打包的 CLI 里**。
技能/模板是"指令复用"，不是 agent。

---

## 11. 依赖与平台依赖面（完整）

### 11.1 `node:` 内建使用统计（`packages/*/src` + session-backends）

`node:fs` 34 · `node:crypto` 22 · `node:os` 20 · `node:child_process` 15 · `node:net` 6 · `node:module` 5 · `node:readline` 4 · `node:worker_threads` 2 · `node:sqlite` 2 · `node:perf_hooks` 2 · `node:http` 2 · `node:vm` 1 · `node:https` 1

**child_process 24 个调用点**（coding-agent）：
`utils/shell.ts`(4)、`utils/tools-manager.ts`(3)、`utils/child-process.ts`(1 包装)、`core/exec.ts:36`、`core/tools/bash.ts:88`、`core/tools/grep.ts:168`、`core/tools/find.ts:216`、`core/footer-data-provider.ts:52`(`spawnSync "git"`)、`utils/open-browser.ts:10`(`xdg-open`)、`utils/clipboard-command.ts:9`、`modes/rpc/rpc-client.ts:94`(`spawn "node"`)、interactive `session-share.ts`/`external-editor.ts`、若干 `experimental/*`；另有 `packages/agent/src/harness/env/nodejs.ts:153,264,593`、`packages/tui/src/autocomplete.ts:169`(fd)、`terminal-image.ts:55`(tmux execSync)

**外部二进制引用**：`git`×34、`npm`×32、`fd`×10、`bash`×8、`rg`×6、`xclip`/`wl-paste`/`xsel`/`xdg-open`/`pbcopy`/`which`/`termux-clipboard-*`

### 11.2 关键判断

- **完全没有 PTY**：`node-pty`/`openpty` 零命中。TUI 用 `process.stdin.setRawMode`（`packages/tui/src/terminal.ts:178,465`；`coding-agent/src/migrations.ts:289`）。子进程 stdio 只有管道（`core/tools/bash.ts:98`）
- **`worker_threads`** 仅用于图片缩放（`utils/image-resize.ts:1`；worker 是第二个 Bun 编译入口 `scripts/build-binaries.sh:117-124`），**有进程内回退**（`image-resize.ts:105-108`）
- **`node:sqlite`** 只在 `session-backends/sqlite-node/src/index.ts:1-2`，**coding-agent 不引用它** → 可选；**没有 better-sqlite3**
- **原生 `.node`** 只有 `packages/tui/native/*/prebuilds`，`createRequire` 加载于 `packages/tui/src/native-platform.ts:38`，`:54-56` 只在 darwin/win32 放行，`:59+` 是 Linux 分支（**X11 依赖**）。**无 android 预编译**
- **WASM**：`photon_rs_bg.wasm`（`@silvia-odwyer/photon-node`），`utils/photon.ts:29,46-52` 有路径修补，加载失败→`null`（`:126-138`）；非核心，可降级
- **keytar/keychain 零命中**；凭证就是明文 `auth.json` 0600（`core/auth-storage.ts:25,52`）
- **HTTP server**：OAuth 回环 `createServer`（`packages/ai/src/auth/oauth/openrouter.ts:14,169,210`，host 由 `PI_OAUTH_CALLBACK_HOST` 控制）
- **Unix socket**：`server/src/transports/unix/listener.ts:4`、`client/src/unix.ts:2`、`experimental/{coordinator.ts:298,299, session-worker.ts:1}`；路径 `join(serverDirectory,"<uuid4>.sock")`，目录由 `PI_SERVER_DIR` 控制 —— **实验性**
- **`os` 用法**：`homedir()`→`config.ts:533`（`~/.pi/agent`）；`tmpdir()`→`core/bash-executor.ts:69`、`core/tools/output-accumulator.ts:21`、`external-editor.ts:15`、`session-share.ts:47`、`utils/clipboard-image.ts:131`；`platform()/arch()`→`utils/tools-manager.ts:262-263`，android 判断在 `:368`
- **信号 32 处**：`killProcessTree` 用 `process.kill(-pid,"SIGKILL")`（`utils/shell.ts` ~231），依赖 `detached:true`（`bash.ts:92`）；SIGINT/SIGTERM 处理在 `experimental/commands.ts:58-59`、`coordinator.ts:310-311`、`experimental/server.ts:776-777`、`session-worker.ts:747-748`、`interactive-mode.ts:4102`
- **fs watcher 17 处**：`utils/fs-watch.ts:23`、`core/footer-data-provider.ts:336,370`（轮询 `.git/HEAD`，250–1000 ms）、`theme.ts:759`
- **TTY 检测**：`main.ts:81,634`、`interactive-mode.ts:272`、`package-manager-cli.ts:734`、`experimental/commands.ts:68`

### 11.3 各包直接依赖

| 包 | 依赖 | 原生标记 |
|---|---|---|
| coding-agent | chord / agent-core / ai / tui（workspace，纯 JS）、chalk 6、cross-spawn 7（win32 专用）、diff、hosted-git-info、highlight.js、ignore、jiti 2.7、minimatch、proper-lockfile、semver、typebox、undici 8.10、yaml | **`@silvia-odwyer/photon-node` 0.3.4**（Rust→WASM，非核心，null 回退）；`grok-mermaid` 0.2.3（纯 JS，仅 TUI mermaid） |
| ai | @anthropic-ai/sdk、@aws-sdk/client-bedrock-runtime、@google/genai、@smithy/node-http-handler、http-proxy-agent、https-proxy-agent、openai、partial-json、typebox | 全纯 JS；AWS 的可选加速器（bufferutil/utf-8-validate/kerberos）是允许的外部依赖且有 JS 回退 |
| chord | **esbuild 0.28.2** | **原生平台二进制**（Go），仅用于打包扩展/facet（`packages/chord/src/node/bundle.ts:4`）；核心 agent 不需要 |
| tui | get-east-asian-width、marked | 纯 JS；原生预编译在仓库里而非 npm 依赖 |
| session-backends/sqlite-node | 仅内建 `node:sqlite` | 无 npm 原生 |
| agent / telemetry / protocol / client / server / evals | 无第三方运行时依赖 | — |

**净结论：原生面只有 2 个** —— `esbuild`（打扩展）与 `photon` WASM（图片）—— 外加可选的 tui `.node` 与受 Node 版本门控的 `node:sqlite`。

### 11.4 bash / shell 工具的实现细节

- **解析顺序**（`utils/shell.ts:88-120`）：自定义 `shellPath` → win32 Git Bash → `/bin/bash` → `which bash`（`spawnSync("which")`，`:50`）→ 回退 `{shell:"sh", args:["-c"]}`，**无 BASH 编译时常量**
- **执行**（`core/tools/bash.ts:88`）：`spawn(shell, [...args, command])`，非 win32 用 `detached`，管道，env 来自 `getShellEnv`，**它会把 `~/.pi/agent/bin` 前置到 PATH**（`shell.ts:138-150`）；超时/中断走进程组杀；cwd 有存在性预检（`bash.ts:79-84`）
- **无 bash 的平台**会静默用 `/system/bin/sh`（mksh）—— toybox 的 `ls/cat/grep/find` 能用，bash-isms 不行
- `BashOperations` 是有文档的可插拔接口（`bash.ts:57-75`），grep/find 同理（`grep.ts:66`、`find.ts:66`）

### 11.5 tui/native

手写 C/ObjC **N-API 插件**（自带 `napi.h`，无 node-gyp 配置），用于剪贴板 + 修饰键检测：
`native/darwin/src/darwin-platform.m`（NSPasteboard）、`native/linux/src/linux-platform-x11.c`（`libxcb.so.1` X11 剪贴板读取；预编译 linux-x64/arm64 约 67 KB）、`native/win32`
**不是 pty，不是 Rust/Zig。** 核心 agent 不依赖它：`native-platform.ts:54` 在非 darwin/win32 返回 undefined，剪贴板回退到命令（`utils/clipboard.ts:24,62`）或 **OSC 52**（`:16-20`）。但 coding-agent 静态 import 了 `pi-tui`（`main.ts:10`、`core/tools/render-utils.ts:4`），所以 JS 包必需、原生附件不必需。

### 11.6 非 Node 运行时

- 浏览器冒烟测试：`scripts/check-browser-smoke.mjs` + `browser-smoke-entry.ts` 用 esbuild `platform:"browser"` 打包 ai/agent-core/protocol/client，断言无 Node-only import → 这些库是平台无关的；**coding-agent 是 Node-only**
- Deno：只有一条 CHANGELOG 注记
- Bun：是官方构建目标（`scripts/build-binaries.sh`、`src/bun/runtime-setup.ts`），但 **src 里零 `Bun.*` API**，全是标准 Node
- 没有 workerd/edge 目标；Cloudflare 只是 provider

### 11.7 构建与打包

- `scripts/build-binaries.sh` 用 `bun build --compile --target=bun-<platform>[-baseline]` 产 6 个目标的单文件 `pi`（**约 60–110 MB**，Bun 运行时占大头）
- **但不是自包含**：旁挂文件有 package.json/README/CHANGELOG、`photon_rs_bg.wasm`、`theme/`、`assets/`、`dist/core/export-html/`、`docs/`、`examples/`、`packages/tui/native/<plat>/prebuilds`（脚本 138–160 行）
- **有 linux-arm64 目标**（另有 linux-x64、darwin×2、windows×2）
- **全仓无 musl 目标**（workflows 里 grep musl = 0）→ 产物是 **glibc 链接**
- npm 路径：`build-coding-agent-bundle.mjs`（esbuild，`target:"node22.19"`，`PI_BUNDLED_NODE` 定义，externals 只有 chord/photon/jiti/可选原生，88/118/20-34 行）
- 根 `engines: node >=22.19.0`

### 11.8 已有 Android/Termux 支持

- **官方唯一路径就是 Termux**（`docs/termux.md`，被 index.md 与 quickstart.md 引用）：
  `pkg install nodejs termux-api git` → `npm i -g --ignore-scripts` → `mkdir -p ~/.pi/agent` → `pi`
  剪贴板用 `termux-clipboard-get/set`（`utils/clipboard.ts:24,62`；`clipboard-image.ts:226`），**仅文本**；图片剪贴板不支持；`/storage/emulated/0` 需先 `termux-setup-storage`
  文档还给了一份示例 `~/.pi/agent/AGENTS.md`，列了 `termux-open-url` / `termux-open` / `termux-notification` / `termux-battery-status` / `termux-wifi-connectioninfo` / `termux-telephony-deviceinfo` / `termux-share` / `termux-toast` / `termux-vibrate` / `termux-tts-speak` / `termux-camera-photo`
  → **pi 自己不认识这些命令，它们只是 bash 命令**
- `tools-manager.ts:366-374`：**当 `platform()==="android"` 时拒绝下载 fd/rg 的 Linux 二进制**（Bionic 不匹配），改提示 `pkg install`
- `shell.ts:45` 有 Termux `which` 的注释
- TUI：`packages/tui/src/tui-main-screen.ts:109-110` 检测 `TERMUX_VERSION`，`:345` 有键盘高度保护
- **没有 android 构建目标、没有 termux 分支**，除通用 `PI_*` 外无移动端标志（`PI_CODING_AGENT_DIR`/`PI_PACKAGE_DIR` 在 `config.ts:391`、`PI_OFFLINE`、`PI_SKIP_VERSION_CHECK`、`PI_TUI_*`、`PI_HARDWARE_CURSOR`、`PI_IMAGE_PROTOCOL`）
- `terminal-setup.md` 没有 Android 章节

---

# 第三部分　Android 平台约束

## 12. exec / W^X（第一号约束）

Android 10（API 29）起，对 `targetSdk ≥ 29` 的应用，`untrusted_app_29` 域的 W^X 由 SELinux 强制：`getFilesDir()`/`getCacheDir()` 里的文件标签是 `app_data_file`，**没有 execute 权限**，`execve()` 返回 EACCES。`nativeLibraryDir`（`/data/app/~~<r>/<pkg>-<r>/lib/arm64/`）标签是 `apk_data_file`，**可执行**。`[文献]`

**三个会浪费数周的陷阱**
1. **`run-as` 会骗你**：`adb shell run-as` 用的是 `runas_app` 域，**能** exec `app_data_file`。用 `run-as` 测通过 ≠ 应用真能跑。**必须从 App UI 里验证。**
2. **`memfd_create` 是死路**：Android 14+ 强制 `MFD_NOEXEC_SEAL`；三星把 memfd 区域标为不可执行。
3. **zygote 的 seccomp BPF 过滤器**屏蔽约 18 个 syscall（`setuid` `setgid` `setgroups` `mount` `umount2` `chroot` …）。它编译进**系统镜像**，与 targetSdk 无关；proot 自带的过滤器是死代码，只有 zygote 的是活的。

**四条出路**
- **(a) targetSdkVersion 28** —— Termux 的做法（维护者 twaik：*"这正是 Termux 使用 targetSdkLevel 28 的原因"*）。Play 完全不兼容。
- **(b) 可执行文件伪装成 `lib*.so` 放进 `jniLibs`** → 释放到 `nativeLibraryDir`。三个硬要求：**PIE**；ELF interpreter 必须是 **`/system/bin/linker64`**（glibc interpreter 的二进制会被**静默地不释放**）；必须开 `android.packagingOptions.jniLibs.useLegacyPackaging true`（否则从 APK zip 里 mmap 而不落盘）。
- **(c) `PROOT_LOADER=<nativeLibDir>/libprootloader.so`** —— proot 默认把 loader 解到临时目录（被禁）。这是**已被验证在 targetSdk 35/36、Android 16、SELinux enforcing、无 root 下可用**的 Play 可行路线。**本机 DSH 正是这么做的。** `[实测]+[文献]`
- **(d)** 自定义 ELF loader 或 exec broker。

**分发渠道由此决定**

| 方案 | targetSdk | 渠道 | 代价 |
|---|---|---|---|
| A（推荐主线） | **36** | Play 可用 | 需 16 KB page size 对齐 + PIE + Bionic interpreter 的自律 |
| B（兜底构建） | 28 | 仅 F-Droid / 侧载 | 最省事；Play 自 **2026-08-31** 起新应用与更新必须 target API 36，现有应用需 ≥35 才对新用户可见 |

另：target Android 15+ 必须在 `lib/arm64-v8a` 支持 **16 KB 内存页** —— 而伪装成 `.so` 的二进制正好住在这里。

## 13. proot 的现实

**能用**：`apk update`、`apk add openssh`、`apk add gcc` + 编译运行，在 targetSdk 35/36、Android 16 上通过（37 个测试）。`[文献]`

**代价**：UNIXbench 裸机 11861/12114/11991 vs proot 内 6746/6811/6466 → **综合约低 44%**（x86_64 实测，2026-05）。`[文献]`
> 注意：这是综合基准，日常"Node 计算 + 偶发文件 I/O"场景的开销远小于此；CLI 类负载通常 5–20%。

**已知破坏点**
| 问题 | 说明 |
|---|---|
| **DNS** | glibc 看不到 Android 的 per-network resolver；静态 `/etc/resolv.conf` 会 `ping: bad address`。解法：静态写公共 DNS（本机 DSH 用 8.8.8.8 + 223.5.5.5）或跑 CONNECT 代理 `[实测]+[文献]` |
| **`/tmp`** | Android 无可用 `/tmp` → 必须设 `TMPDIR`，否则 bash 输出落盘（`bash-executor.ts:69`、`output-accumulator.ts:21`）、编辑器/分享临时文件全 ENOENT `[文献]` |
| **信号/进程组** | `killProcessTree` 依赖 `detached` + `process.kill(-pid)`；proot 下进程组是宿主概念，翻译是逐进程的 → **超时/树杀可能不完全生效** `[推断]` |
| **termux-api 在 proot 内** | Android 15 / OneUI 7 上坏过 |
| **TCGETS2 ioctl** | 内核 6.12 上 proot 曾坏；据称已在 proot 5.1.107-66（2025-10）修复（来源方自述曾发布未经验证的 proot 说法，**需独立核实**） |
| **`cargo build`** | 在 proot 内失败（rustc 派生子进程 → ENOSYS）；**gcc 正常** |
| **不能用** | Docker、KVM、systemd、嵌套模拟器 |
| **ptrace** | 加固 ROM（GrapheneOS 最严格档）可能禁 → proot 立即失败。App 必须开机自检并明确报错 |

## 14. 后台、保活与幻影进程杀手

- **前台服务 + 常驻通知 + `PARTIAL_WAKE_LOCK`**：Android 8+ 必需，否则切后台被杀；息屏 CPU 睡眠会把 30 分钟任务拖成几小时。
- **幻影进程杀手（Android 12+）**：系统跟踪 App 派生的子进程；**全局（不是单 App）超过 32 个**就触发裁剪，按**父进程 oom_adj** 从旧到新；也会因 CPU 占用过高杀。**是静默引入的**（Android 12 行为变更文档里没有）。症状：`Process completed (signal 9)`。`[文献]`
  - 关闭：`settings put global settings_enable_monitor_phantom_procs false`（Android 12L beta 3+，需 ADB/root）
  - **厂商会覆盖**：一加/ColorOS 15 与 OxygenOS 即使关闭也杀 Termux 进程，直到 2024-11 厂商更新才修好
  - 对一个不停跑 `rg`/git/npm/node 的 Agent，**会在阈值附近震荡**
- Android 14 把 `killBackgroundProcesses()` 限制到只能杀自己的进程
- Android 14+ 要求显式 FGS type + 对应权限，部分带超时
- **实测足迹参考**：Mobile-Harness 要求 4 GB RAM（推荐 8 GB）/ 2.5 GB 存储；PocketCode 声称 6 GB+ / 5 GB；claw-code 约 500 MB。每层 proot 都是一个进程（5–20 MB）

## 15. 存储

- **App 私有目录**：非 FUSE，快 → 工作区与 `node_modules` 放这里
- **`/sdcard`**：FUSE，大量小文件慢 5–10 倍 → 只用于导入导出
- 用户文件经 **SAF（`ACTION_OPEN_DOCUMENT_TREE`）** 接入
- `MANAGE_EXTERNAL_STORAGE` 需 Play 声明且限品类
- **实测**：本机 `/sdcard/Download` 可直写 `[实测]`

## 16. Node 运行时选型（完整对比）

| 路线 | Node 来源 | 扩展生态 | 原生模块 | 工具链 | 体积/耗时 | 结论 |
|---|---|---|---|---|---|---|
| **A. proot + Ubuntu(glibc)** | 官方 nodejs.org linux-arm64 | ✅ 完整 | glibc linux-arm64 预编译可用 | apt 全套 | ~1–2 GB / 20–30 min（可裁剪到 ~350 MB） | **采用** |
| B. Bionic 原生（Termux 式） | Termux 构建的 node（需改 prefix） | ⚠️ shebang/prefix 坑 | 需 Bionic 重编 | 无 apt | 中 | 备选/后续提速 |
| C. nodejs-mobile（libnode.so + JNI） | **停在 v18.20.4（2024-10）**，Node 18 已 EOL | ❌ | 需交叉编译一切 | 无 | 小 | **否决**（pi 要求 ≥22.19） |
| D. glibc-runner（只装 ld.so + 官方 Node） | 官方 linux-arm64，**ld.so wrapper** 启动 | ⚠️ 部分 | 需逐个配 | 无 apt | ~200 MB / 3–10 min | 备选轻量路线 |

**关键文献证据**
- **Bun 在 Android 不可用**：官方二进制是**非 PIE**，内核直接拒绝（`Android only supports position-independent executables (-fPIE)`）；npm 也因 `process.platform === "android"` 拒绝它。OpenCode 的 Bun 单文件只能靠 "proot + ld.so 拼接" hack 跑。
- **patchelf 在 Android 上会 segfault** —— 要用 ld.so wrapper 脚本而不是改 ELF。
- **原生模块是"100% 生态"的真正考验**：Claude Code ≥v2.1.113 的 glibc linux-arm64 原生二进制**通过 glibc-runner 在 Android 上 segfault**，用户被迫锁 v2.1.112；Node 24 在 Termux 下启动挂起 60+ 秒。需要逐模块配方：`better-sqlite3` 要 Termux fork/补丁；`sharp` 要 libvips + Bionic 兼容头；`node-llama-cpp` 的 cmake postinstall 因工具链不兼容失败，只能 `--ignore-scripts` + 预编译 arm64。
- **⇒ 支撑"生态"需要 glibc 用户态，而不只是嵌入 libnode。**

**A 的额外好处**：proot 里 `process.platform === "linux"`，**自动绕过 pi 的 Android 特判**（`tools-manager.ts:366-374` 会在 `platform()==="android"` 时拒绝下载 rg/fd，proot 内不触发）。B/C 路线反而要打 `bionic-compat` 补丁（claw-code 的 `bionic-compat.js` 就在改 `process.platform`→`"linux"`、`os.cpus()`、`os.networkInterfaces()`）。

---

# 第四部分　设计

## 17. 总体架构

```
┌──────────────────────── Android App 进程 (Kotlin) ────────────────────────┐
│                                                                           │
│  UI 层 (Compose 原生外壳)                                                  │
│   会话列表 │ 对话(WebView 承载对话流) │ 终端(真 PTY) │ 文件/Git │ 设置     │
│                                                                           │
│  状态投影层 (Kotlin Flow)  ← RPC 事件反序列化 → UI State                   │
│   ▸ pi 是唯一事实源；App 只是投影（重连用 get_entries{since} 补差）        │
│                                                                           │
│  桥接层                                                                    │
│   ├ RpcChannel   : 与 pi 子进程 stdio / 本地 socket 双向 JSONL             │
│   ├ Supervisor   : pi 进程生命周期、多会话、崩溃恢复、事件重放              │
│   └ DeviceBridge : HTTP 127.0.0.1:<port> + token（供 rootfs 内扩展调用）   │
│                                                                           │
│  Android 能力层                                                            │
│   ├ ForegroundService (保活 + 通知 + 唤醒锁)                               │
│   ├ AccessibilityService (读屏/点按/输入/截图)                             │
│   ├ Shizuku / ADB 适配器 (可选)                                            │
│   └ 系统集成: 分享接收/相机/剪贴板/通知/TTS/传感器/位置                    │
└──────────────────────────────────┬────────────────────────────────────────┘
                                   │ fork/exec (nativeLibraryDir 内的 libproot.so)
┌──────────────────────────────────┴────────────────────────────────────────┐
│ proot 会话: Ubuntu 24.04 aarch64 (glibc)                                   │
│   /opt/pi-runtime/   node 24, npm, pnpm, rg, fd, git, python3,(按需 gcc)   │
│   /root/.pi/         agentDir: settings/extensions/skills/prompts/themes/  │
│                      sessions/ npm/ git/ bin/ auth.json                    │
│   /workspace/        工作区（真实 f2fs，快）                                │
│   /sdcard            bind 用户共享存储                                     │
│                                                                           │
│   pi 内核（npm 原版，零修改）                                              │
│     pi --mode rpc        ← App 主通道（已实测跑通）                        │
│     pi (TUI on PTY)      ← 终端页，100% 兜底                               │
│     pi <CLI 子命令>       ← 终端页 / App 代理                              │
│                                                                           │
│   ~/.pi/agent/bin/  ← rg, fd, termux-* shims（前置进 PATH）                │
│   扩展（原样加载，jiti）                                                   │
│     @pi-android/device-bridge、permission-gate、question、todo、plan-mode、 │
│     git-checkpoint、notify …                                              │
└───────────────────────────────────────────────────────────────────────────┘
```

**一次对话的数据流**
```
用户输入 → Compose → RpcChannel → {"type":"prompt",...} → pi
pi → message_update{assistantMessageEvent:{type:"text_delta"}} → Flow → 增量 DOM/Compose
pi → tool_execution_start(bash) → 工具卡片 → tool_execution_update → 实时追加
扩展 → extension_ui_request("confirm") → 原生对话框 → extension_ui_response
扩展 → HTTP 127.0.0.1:PORT/app/ui/dump → DeviceBridge → AccessibilityService → JSON
```

## 18. 桥接层

### 18.1 主通道：RPC over stdio

- App 用 `ProcessBuilder` 在 proot 里起 `pi --mode rpc`
- **写**：每行一个 JSON 命令，加自增 `id`
- **读**：**自己实现 LF 切分**（严格按 `\n`，容忍尾随 `\r`；不要用会吞 `U+2028` 的实现）
- Kotlin 侧用 `sealed interface` 建模事件，`CompletableDeferred` 按 `id` 关联响应
- 背压：pi 侧有 output-guard；App 侧 `Channel` 缓冲 + 溢出降级（丢中间 delta，保状态事件）
- **为什么不走 CBOR protocol/server**：标着 experimental，服务契约（chord）不稳，只有 Unix socket。等稳定后再作为"多端共享会话"的升级路径。

### 18.2 Supervisor：让会话活过 UI

纯 stdio 的硬伤是 **UI 进程一死 pi 就死**。对策：
- pi 子进程挂在**前台服务**里，不是 Activity
- 常驻 Node `pi-supervisor`：
  - 每会话一个 `pi --mode rpc`
  - 对 App 暴露 localhost unix socket / WebSocket 多路复用
  - 缓存每会话最近 N 条事件，UI 重连**先重放**，再 `get_entries{since}` 补差量
  - UI 全关时保持进程（配合前台通知"pi 正在运行 · N 个任务"）
  - 会话元数据落盘，冷启动秒列会话
- 兜底：supervisor 崩了也不丢数据，会话 JSONL 在盘上，`switch_session` 恢复

### 18.3 会话列表

pi 没有 list 命令 → App 解析 `~/.pi/agent/sessions/--<cwd>--/*.jsonl` 的头部行（pi 自己也是 header-only 扫描），按 cwd 分组，显示最后活动时间、名称、模型、token/cost。

## 19. 运行时层

### 19.1 rootfs 分层

| 层 | 内容 | 压缩体积 | 分发 |
|---|---|---|---|
| L0 引导 | `libproot.so` `libprootloader.so` `libprootloader32.so` + 静态 bash/toybox | ~5 MB | **必须进 APK** |
| L1 基础 | debootstrap minbase + ca-certificates + git + python3 + curl | ~60 MB | APK 或首启下载 |
| L2 pi 运行时 | Node 24 + npm/pnpm + **预置 rg/fd** + pi 本体（含 npm 缓存预热） | ~80 MB | APK（离线可用） |
| L3 工具链 | build-essential / cmake / golang … | ~250 MB | **按需 apt** |

- **必须预置 `rg`/`fd`**：pi 的 `ensureTool()` 缺工具时会联网下载（`utils/tools-manager.ts`）。手机首启无网就卡住。放 aarch64-musl 静态版到 `~/.pi/agent/bin/`（`getShellEnv` 会把该目录前置到 PATH）。已核实 `fd-v10.2.0-aarch64-unknown-linux-musl.tar.gz` 可下载（1.5 MB）；ripgrep 的对应 asset 名需按实际 release 确认（14.1.1 那个 URL 404）。
- Node 官方 linux-arm64 压缩包 30.5 MB。
- 首启解压 30–90 秒（带进度）；之后冷启动 1–2 秒。

### 19.2 Termux 兼容 shim（四两拨千斤）

pi 在 `TERMUX_VERSION` 存在时走 `termux-clipboard-get/set`；官方 `termux.md` 还列了 `termux-open-url`/`termux-notification`/`termux-share`/`termux-toast`/`termux-vibrate`/`termux-tts-speak`/`termux-camera-photo`。

**做法**：在 `~/.pi/agent/bin/` 放一组同名 shim 脚本，内部 `curl` App 的设备桥。
- pi 的**剪贴板原生支持直接生效（零补丁）**
- 用户从 Termux 迁来的 `AGENTS.md` / 技能里写的 `termux-*` **照样能用**
- 成本极低、回报极高

## 20. UI / UX 设计

> **调研结论**：现有手机 Agent 项目失败的原因**几乎都不是技术，而是体验**。HN 上多位开发者独立指出：小屏 + 无键盘让他们把提示词写得更短，于是"留给模型自己猜的空间更大"，导致反复返工与欠债；复制终端输出、代码片段、浏览器报错难用到"懒得在手机上拼这段提示词"；TUI-on-phone 被普遍评价为 clunky，用户要的是原生控件而不是终端克隆。
>
> **设计立场：GUI 优先，终端只做逃生舱。**

### 20.1 信息架构

底部四 Tab：

| Tab | 内容 |
|---|---|
| **会话** | 按工作区分组、搜索、置顶、新建/继续/Fork、重命名、导出 HTML/JSON |
| **对话** | 主战场 |
| **终端** | 多标签：① rootfs shell ② **原版 pi TUI（100% 兜底）** ③ 任意命令（git/apt/测试） |
| **更多** | 文件/Git、扩展与包、模型与 Provider、技能/模板/主题、Android 能力、运行时管理、设置 |

### 20.2 对话页

**消息流**：用户消息（Markdown + 图片缩略图）；助手消息（Markdown + 代码块高亮/复制/保存 + **thinking 折叠区**）；状态条（轮次 / token 与上下文占用 / 排队消息）；压缩与重试提示。

**输入区**：
- 多行输入 + 手机键盘工具条（`Esc` `Tab` `Ctrl+C` `↑↓` `/` `@`）
- `/` → 命令补全（`get_commands`：扩展命令 / `/skill:name` / 模板）
- `@` → 文件引用
- 附件：相册 / 拍照 / 文件 / 粘贴图片 → base64 → `images`
- 流式中发送：三选「排队(steer) / 追加(followUp) / 打断重说」
- 停止 = `clear_queue` + `abort`，并把被清空的排队文本还回输入框
- 模型 / 思考等级快捷切换

**审批与提问**：所有 `extension_ui_request` 渲染成原生组件（`confirm`→底部抽屉含命令原文与"本次/总是/拒绝"；`select`→列表；`input`/`editor`→输入/全屏编辑器；`notify`→Snackbar 或通知；`setStatus`→状态条；`setWidget`→输入区上/下小组件区；`setTitle`→标题栏）。**注意服务端超时**，UI 要同步倒计时。

### 20.3 渲染：**pi 自己带了整套，不用从零写**

这是本次分析中回报最高的一块。**pi 在 `packages/coding-agent/src/core/export-html/` 里已经有一整套会话渲染器**（为"导出为自包含 HTML"而做，正好是给浏览器用的）：

| 资产 | 规模 | 能力 |
|---|---|---|
| `template.html` | 55 行 | 骨架：侧栏 + 会话树容器 + 消息区 + 图片弹窗 + 汉堡按钮 + 侧栏拖拽 |
| `template.css` | 1066 行 | 完整主题化样式：`tool-diff`/`diff-added`/`diff-removed`/`diff-context`、`thinking-block`/`thinking-text`/`thinking-collapsed`、`tool-output`、卡片、侧栏、响应式 |
| `template.js` | 1864 行 | 渲染逻辑：`renderEntry`、`renderToolCall`、`findToolResult`、`renderTree`、`renderHeader`、Markdown 走 `marked`、代码走 `hljs.highlight`、**diff 从 `result.details.diff` 解析**、thinking 块渲染与折叠、"T toggle thinking · O toggle tools" 快捷键、会话统计（用户/助手/工具/压缩/分支/token/cost/模型） |
| `vendor/marked.min.js` | 42 KB | Markdown |
| `vendor/highlight.min.js` | 122 KB | 代码高亮（190+ 语言） |
| `ansi-to-html.ts` | 6.7 KB | **ANSI → HTML**：标准/亮色 30-37/90-97、背景 40-47/100-107、**256 色 `38;5;N`**、**真彩 `38;2;R;G;B`**、bold/dim/italic/underline/reset |
| `tool-renderer.ts` | 5.5 KB | **调用扩展的 `renderCall`/`renderResult`（TUI Component）→ 渲染成 ANSI → 转 HTML** |
| `index.ts` | 11 KB | `exportSessionToHtml` / `exportFromFile`、主题变量派生（`generateThemeVars`）、自定义工具预渲染 |

**关键推论**：
1. 日常要渲染的东西（Markdown、代码高亮、**diff**、**thinking**、工具卡、会话树、统计）**pi 全都写好了**，约 3000 行，且与 TUI 视觉一致。
2. `tool-renderer.ts` 证明**扩展的自定义工具渲染可以在 GUI 里保留**：把扩展的 TUI 组件渲染成 ANSI 文本再转 HTML。所以"扩展自定义渲染"不是只能降级成通用卡片，只是**形态为终端单元格**（等宽、按行布局）。
3. 它是**一次性导出**用的（1864 行里没有流式逻辑），所以 App 需要在它之上加一个"事件 → 增量 DOM"的应用层。

**渲染方案（已在 UI 规范 v2 中裁决，此处以 UI 规范为准）**

> **结论：对话流用原生 Compose 实现，不用 WebView 整体承载。**
> 原因：`export-html` 那套 HTML 是**终端审美**（全等宽 12px、扁平 4px 边框、无表面层级），与「现代原生 Android App」的定位冲突。UI 规范优先于实现便利。
> `export-html` 的资产改为**按需复用**：① `ansi-to-html.ts` 的映射逻辑移植为 Kotlin 的 ~150 行 SGR 解析；② `tool-renderer.ts`（`createToolHtmlRenderer`）在 **Node 侧**用于把扩展的 TUI 自定义渲染转成 HTML，再由该工具卡内一个**隔离 WebView** 呈现 —— 这样既保住「扩展渲染 100% 兼容」，又把 WebView 限制在小块内。
> WebView 仅出现在两处：**Mermaid 图** 与 **扩展自定义工具渲染**。
> 详细分工、依赖库与代价见 `pi-android-ui-spec.md` §4.10。

**App 需要自己写的渲染相关代码（估算）**：

| 项 | 工作量 | 说明 |
|---|---|---|
| 事件 → DOM 增量渲染层 | ~800–1200 行 JS | 复用 `renderEntry`/`renderToolCall`，加 `appendEntry`/`updateToolOutput`/`appendTextDelta` 这类 API；把 template.js 的"先构建全量后填充"改成"可增量" |
| 工具卡 HTML 模板（8 个内建） | ~600–1000 行 JS+CSS | 可大量参考 template.js 里已有的 bash/diff/read 渲染分支 |
| 扩展自定义工具渲染桥 | ~200 行 | 在 Node 侧调 `createToolHtmlRenderer`（已有），把 HTML 随 `tool_execution_end` 的 `details` 一起下发 |
| JS ⇄ Kotlin 桥 | ~300 行 | 事件下发、审批回传、复制/打开链接/保存文件、主题变量同步 |
| 状态投影层（Kotlin） | ~500–800 行 | 见 20.5 |
| 主题映射 | ~150 行 | pi 主题 JSON → CSS 变量（`generateThemeVars` 已有逻辑可参考）+ Compose 配色 |

**必须自己写、且没有现成解的两处**（都很小）：
1. **流式增量更新**：pi 的导出器是全量渲染。要支持打字机效果，得把 `renderEntry` 改造成可 patch 的（新增/追加/替换一个 entry 的 DOM 节点）。这是本项目在渲染上唯一的真实工程量。
2. **真 PTY 终端**（见 20.4）。

**如果坚持全原生 Compose**（可选路线 B），需要额外自建：Markdown 渲染（可用 `com.mikepenz:multiplatform-markdown-renderer` 或 Halil Ozercan 的 `richtext-commonmark`，但 GFM 表格/嵌套/流式要自己调）、语法高亮（`dev.snipme:highlights` 是纯 Kotlin 的，可用）、ANSI SGR 解析（自写 ~150 行，或移植 `ansi-to-html.ts` 的逻辑）、diff 渲染（自写 ~300–400 行：解析 `@@` hunk + 行级着色）、工具卡（~2000 行）、流式（~400 行）。**代价是 Markdown/Mermaid 的边角会长期欠债**。推荐先用 WebView 起步，之后按热点逐步原生替换 —— 事件流是同一份，重构成本低。

**方案 C（只跑 TUI）不推荐**：等于把终端搬上手机，正是调研里用户明确说不好用的形态。

### 20.4 终端页（100% 兜底）

- **复用 termux-app 的 `terminal-emulator` + `terminal-view` + 其 JNI（`forkpty`）**（Apache-2.0）：IME、选区、滚动、鼠标、真彩都经过真机验证，且它天然提供**真 PTY** —— 这正是 pi TUI 必需的（`process.stdin.setRawMode`，`packages/tui/src/terminal.ts:178`）。
- 在 PTY 上启动：`libproot.so … /bin/bash -l`，用户可在里面跑 `pi`（TUI）或任何命令。
- 需补的终端能力：
  - **OSC 52**（剪贴板）—— Android 无 X11，pi 的 `clipboard.ts:16-20` 本来就有 OSC 52 回退，这是正解；再用 `termux-clipboard-*` shim 双保险
  - **Kitty 键盘协议**，至少回退到 xterm `modifyOtherKeys`（影响 `Shift+Enter` 等，见 `docs/terminal-setup.md`）
  - **OSC 8** 超链接（点击开浏览器）
  - 内联图片（Kitty/iTerm2 图形协议）**不实现**，设 `PI_IMAGE_PROTOCOL=none` / `terminal.images:false` —— GUI 里图片本来就是原生的
- ⚠️ **必须上真 PTY**：Mobile-Harness 用的是"进程管道桥"，其自述限制就是"全屏交互程序可能渲染错乱"。

### 20.5 状态管理：pi 是事实源，App 只是投影

**不要**在 App 里重新实现 agent 状态机。做法：
- 实时：订阅 RPC 事件，增量更新 UI（`message_*` 构建消息、`tool_execution_*` 挂到对应工具调用、`compaction_*`/`queue_update` 更新状态条）
- 恢复/重连：`get_entries{since: cursor}` 拿**持久化的差量**（durable cursor），或 `get_messages` / `get_tree` 重建
- 冷启动：`switch_session{sessionPath}` 打开历史会话，再 `get_entries` 全量投影
- 好处：状态代码量小（~500–800 行），且**永远不会与 pi 的真实状态不一致**

需要 App 自己维护的只有少量派生态：滚动位置、折叠状态、输入草稿、待审批队列、通知去重。

### 20.6 手机特化

- **分享接收**：任意 App 分享文本/图片/文件 → pi（新建会话或追加到当前）
- **快捷磁贴 / 小组件**："新建会话"、"继续上次"
- **通知**：任务完成 / 需要审批 / 出错 → 通知栏，带「查看/停止/快捷回复」
- **语音**：系统语音转文字输入；可选 TTS 输出
- **分屏 / 折叠屏**：对话 + 终端并排；横屏左工具右对话
- **主题**：`themes/*.json` 色板 → CSS 变量（WebView）+ Compose 配色，做到"换 pi 主题 = 换 App 主题"

## 21. 设备能力桥：`@pi-android` 扩展包

### 21.1 为什么用扩展而不是 MCP

pi **明确不做 MCP**。但它的扩展系统比 MCP 更强（能注册工具、拦工具、改 system prompt、注入上下文）。把手机能力做成**随 App 分发的 pi 扩展包**，天然 100% 兼容 —— 同一份扩展在桌面也能装能用。

### 21.2 传输

App 内起仅绑 `127.0.0.1` 的 HTTP 服务（**独立端口 + 独立 token，避开 DSH 的 3090**），token 写入 rootfs 内只读文件。扩展用 `node:http` 调用。**已实测 proot 内可直连 127.0.0.1。**

### 21.3 工具清单（首批）

| 类别 | 工具 |
|---|---|
| 屏幕/UI | `android_ui_dump` `android_tap` `android_input` `android_key` `android_swipe` `android_screenshot`（**返回 ImageContent，模型能直接"看"屏幕**） |
| 应用 | `android_apps` `android_launch` `android_stop_app` |
| 交互 | `android_notify` `android_toast` `android_vibrate` `android_share` `android_open` `android_ask` |
| 数据 | `android_clipboard_get/set` `android_files_read/write` `android_export` `android_import` |
| 传感 | `android_location` `android_sensors` `android_torch` `android_battery` |
| 媒体 | `android_camera_photo` `android_tts` |
| 高级（可选） | `android_shell`（Shizuku/ADB，uid=2000；套用与 DSH 相同的策略守卫：禁块设备/SELinux/`settings put`/挂载/清数据；结束应用前刷新清单并区分用户/系统应用） |

另外注册：
- **策略扩展**（危险操作分级确认，参考 `permission-gate.ts` 的写法）
- **`resources_discover`** 注入 Android 环境说明（等价 pi 官方的 Termux AGENTS.md 模板，但描述 App 的真实布局与可用命令）

### 21.4 权限分级（默认关闭）

1. **基础**（默认开）：剪贴板、通知、打开链接、分享
2. **存储**：SAF 指定目录读写
3. **无障碍**：屏幕读取与操作（高敏感，单独开关 + 说明）
4. **位置/传感器/相机**：单独开关
5. **Shell（Shizuku/ADB）**：单独开关 + 配对流程

被禁能力**返回明确原因，不静默失败**（沿用 DSH 的 `DISABLED`/`NO_PERMISSION` 语义，并要求模型"照原话告诉用户去哪开"）。

## 22. 后台与可靠性

| 问题 | 对策 |
|---|---|
| 切后台被杀 | 前台服务 + 常驻通知 + `PARTIAL_WAKE_LOCK` |
| 幻影进程杀手 | 检测 + 引导 ADB 关闭 + 并发节流 + 失败原因可操作；**不假设开关在 OEM 上有效** |
| UI 退出任务中断 | supervisor 常驻（§18.2） |
| 息屏长任务 | 唤醒锁 + 通知 + 完成震动 |
| 断线 | 事件重放 + `get_entries{since}` 补差 |
| 崩溃 | 崩溃日志 + 「安全模式启动」（禁用用户扩展，等价 `--no-extensions`） |
| 升级失败 | L2 层可回滚 |
| DNS / TMPDIR | 静态 `resolv.conf` + 显式 `TMPDIR=/tmp` |
| proot 信号语义偏差 | 记录 PID 表 + 独立看门狗双重兜底 |

## 23. 安全模型

pi 官方立场：**没有内建权限系统；扩展是同进程、无沙箱的任意代码**。在手机上，一个第三方扩展能读相册、剪贴板、通讯录。**不要假装能沙箱化扩展**，改为：

1. **信任门控**：装扩展必须显式确认，展示来源、`package.json`、会申请的能力；项目级扩展默认不自动加载（对齐 pi 的 `defaultProjectTrust`）
2. **能力分级**：设备桥默认关闭，逐项授权；无障碍/Shell 单独开关
3. **策略守卫**：设备 shell 层硬禁块设备、SELinux、`settings put`、挂载、清应用数据
4. **密钥**：`auth.json` 本就在 App 私有目录 0600；可再用 Android Keystore 包一层（启动时解密到 0600 文件供 pi 读）。**不落 `/sdcard`**
5. **网络**：不开入站端口；本地桥仅 `127.0.0.1` + token
6. **可审计**：设备操作、扩展加载、危险命令确认全进本地审计日志
7. **紧急刹车**：通知栏常驻「停止全部」

> 反面教材：claw-code-android **默认 `approval_policy="never"` / `danger-full-access`**。一个有手机文件系统访问权 + 已存 API Key 的 Agent 是真实风险。

## 24. 性能预算

| 指标 | 目标 | 依据 |
|---|---|---|
| 冷启动到可输入 | < 2.5 s | UI 先渲染，内核后台起；proot+node 约 0.3–1.5 s |
| proot 开销 | syscall 密集 5–20% | 文献综合基准 -44%，但那是全场景基准 |
| 常驻内存 | < 500 MB（App + Node + pi 空闲） | 本机可用约 1.2 GB |
| 打字机延迟 | < 100 ms | stdio + Flow + 增量 DOM |
| 大输出 | 不卡 | pi 已截断（50 KB/2000 行）；App 用虚拟列表/懒渲染 |
| 会话上限 | 默认 1 活跃 + N 挂起 | 防内存与幻影进程 |

## 25. 中国网络与模型接入

- Anthropic/OpenAI/Google 默认不可直连 → 必须支持 `HTTP(S)_PROXY`（pi 有 `httpProxy` 配置项）
- **国产 Provider pi 已内建**：deepseek、moonshotai(+cn)、zai(+coding-cn)、kimi-coding、minimax(+cn)、qwen-token-plan(+cn/individual)、xiaomi(+cn/ams/sgp)、ant-ling、opencode(+go) 等，直接可选
- **本地模型**：pi 通过外部 `llama-server`（`http://127.0.0.1:8080`）接入 → App 可选装 llama.cpp Android 构建，实现完全离线（手机算力有限，作兜底）。需注意 `node-llama-cpp` 在 Android 上 postinstall 会失败，只能 `--ignore-scripts` + 预编译
- OAuth 登录用 Custom Tabs + `127.0.0.1` 回环（`PI_OAUTH_CALLBACK_HOST`），或 `npx @earendil-works/pi-ai login`
- npm 切镜像（`registry.npmmirror.com`），否则装扩展很慢

---

# 第五部分　交付计划

## 26. 实施路线

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M0 运行时打通**（最高风险，先做） | `libproot.so`/`libprootloader.so` 进 jniLibs + `PROOT_LOADER` + rootfs 解压 + Node + `pi --version` + `--mode rpc` 应答 | 真机上 `get_state` 返回 success（**可行性已验证**） |
| **M1 最小闭环** | Compose 外壳 + WebView 对话流（接 `template.js`）+ RPC 通道 + 流式增量渲染 + 审批弹窗 + 前台服务 | 能完成一次"读文件→改文件→跑测试"完整任务 |
| **M2 可用** | 工具卡矩阵、**真 PTY 终端页（跑原版 pi TUI）**、会话列表/恢复、模型/Provider 设置、扩展管理器、rg/fd 预置、`termux-*` shim | 日常可用，扩展能装能用 |
| **M3 手机能力** | `@pi-android` 设备桥、无障碍、通知/分享/相机、保活、幻影进程引导 | Agent 能自己操作手机 |
| **M4 打磨** | 文件/Git 页、主题映射、断线重放、崩溃恢复、体积优化、双渠道打包、原生替换热点渲染 | 流畅好用，可发布 |

M0 不过关后面都是空中楼阁；M0 的第一个 PR 应该是真机验证脚本。

## 27. 风险与对策

| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| 幻影进程杀手杀任务 | **高** | 高 | 引导 ADB 关闭 + 并发节流 + 明确报错；不依赖开关在 OEM 有效 |
| proot 被禁 ptrace（加固 ROM） | 中 | 致命 | 开机自检并明确报错；退路 Bionic 原生 |
| 体积过大（>300 MB） | 中 | 中 | 分层：APK 只带 L0–L2，工具链按需 apt |
| proot 下信号/进程组语义偏差 | 中 | 中 | PID 表 + 看门狗双保险 |
| pi 上游改 RPC 协议 | 中 | 中 | 只依赖稳定子集 + 协议版本探测 + 兼容层；不 fork |
| **pi 上游改 `template.js`** | 中 | 低 | 以 submodule/patch 方式跟踪；渲染层做适配层隔离 |
| 扩展里的原生模块需 arm64 预编译 | 中 | 低 | glibc 路线下绝大多数有 `linux-arm64` 预编译 |
| 扩展无沙箱导致安全事故 | 中 | 高 | 信任门控 + 审计 + 设备能力默认关 + 文档明示 |
| 首启/装扩展受网络影响 | 高 | 中 | 镜像源 + 离线预置（rg/fd/pi 全打进包） |
| Play 审核（可执行代码 / 16 KB / API 36） | 中 | 中 | 主线走 nativeLibraryDir 合规路线；同时提供侧载/F-Droid 构建 |

## 28. 竞品调研：现有手机本地 Agent 为什么不好用

| 项目 | 架构 | UX | 能用 | 问题 / 抱怨 |
|---|---|---|---|---|
| [PocketCode](https://github.com/rajbreno/PocketCode) | **不是 App** —— `curl\|bash` 在 **Play 版 Termux** 里装 proot-distro + Acode 编辑器 | 终端 + opencode web UI :4096 | 诚实、5 分钟上手；有唤醒锁/电量说明 | 依赖它自己链接的**已停更冻结在 0.101 的 Play 版 Termux**；要求 6 GB RAM / 5 GB 存储；项目随 Termux 卸载而亡；故障排查里列了"Termux 随机退出"。其兄弟项目 [4PocketCode](https://github.com/rajbreno/4PocketCode)（$4/月云）等于承认了本地天花板 |
| [Mobile-Harness](https://github.com/techjarves/Mobile-Harness) | 真 APK：Compose + C++ JNI 管道桥 + PRoot Ubuntu 20.04 + Node LTS + Claude Code CLI | 原生 chat + 终端 + WebView 预览 | 设计最完整。**sideload 版 target API 28**，另有 `-PplayBuild=true` 的 Play 构建；Keystore AES-256-GCM 存密钥；SAF；可在设备上构建 APK | 仅 arm64；PRoot 不是安全边界；**"进程桥而非完整 PTY —— 全屏交互程序可能渲染错乱"**；ncurses 伪影；被电池优化限流；**离线 APK 818 MB** |
| [claw-code-android](https://github.com/friuns2/claw-code-android) | APK 内置 Termux bootstrap + Node 24 + 73 MB Rust musl 二进制 + Python；WebView 套本地 Express :18923 + 网关 :18789 + 控制 UI :19001 + CONNECT 代理 :18924 | WebView (Vue) | 与本项目最接近的类比。**明确用 `targetSdk = 28` 绕开 W^X**（其故障排查自己写了）。`bionic-compat.js` 修 `process.platform`→`"linux"`、`os.cpus()`、`os.networkInterfaces()`；DNS/TLS 靠代理桥接；支持 MCP；19 个工具 | 4 个本地端口 + 代理 + WebView = 失败点多；**默认 `approval_policy="never"` / `danger-full-access`**；营销腔调 + 无法核实的 star 声明；Agent 是第三方重写而非原版 |
| [ToolNeuron](https://github.com/Siddhesh2377/ToolNeuron) | 三进程（`:app`/`:inference`/`:server`）；GGUF 走 llama.cpp AAR + sherpa-onnx；插件为 DEX 模块 | 原生 Compose | 有价值的先例：进程拆分、`stopWithTask=false` 让前台服务挺过"从最近任务划掉"、能力门控的插件运行时；把 dex/so 锁为只读因为"Android 14+ 拒绝可写 dex" | **不是编码 Agent**。README 说 2026-04 的转型把工具调用与 Termux 集成都砍掉了 |
| [opencode-mobile](https://github.com/dzianisv/opencode-mobile) | 薄 RN/Expo 客户端；**无本地运行时**，需要自建 `opencode serve` 走 LAN/Tailscale/CF | 原生移动聊天、diff、审批 UI | 这一组里移动 UX 最好；Play + F-Droid + APK；Keystore 存凭证 | **需要一台笔记本/VPS**。路线图里"离线历史""托管服务"仍标着 planned |
| [openclaw-android](https://github.com/AidanPark/openclaw-android) | Termux 安装脚本，**绕开 proot**：只装 glibc 动态链接器 + 官方 Node 22 linux-arm64，用 ld.so wrapper 启动 | Termux + 可选独立 APK | **与本项目最直接相关**。~200 MB / 3–10 min vs proot 的 1–2 GB / 20–30 min；有真实的原生模块配方（`build-sharp.sh`、`termux-compat.h`、`spawn.h`、`systemctl` stub、`--ignore-scripts`） | App 私有存储不支持硬链接 → 它自己的 `backup create` 会失败；CLI 命令感觉慢；本地 LLM 不实用（无 GPU offload） |
| [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) | 指南 + 集成层（无 APK） | Termux | 记录了真实陷阱：`/tmp` 不可写 → `proot -b $PREFIX/tmp:/tmp claude`；裸 `claude` 静默失败；32 位机跑不了 arm64；**57 个 Termux API 只确认 24 个可用（约 58%）** | 其 `story.md` 是作者自述的"发未验证声明"的反面教材 |
| [oonid/pr](https://github.com/oonid/pr) | proot distro runner，**targetSdk 35、Play 兼容、无 Termux**，37 个通过测试 | Kotlin/Compose + 内嵌终端 | **A1 一节最好的工程参考**（PROOT_LOADER + nativeLibraryDir；`run-as` 陷阱；memfd 死路） | `cargo build` 在 proot 内仍失败 |

**用户抱怨的共同主题**（最强证据是 [HN 上关于 Codex-in-ChatGPT-mobile 的讨论](https://news.ycombinator.com/item?id=48142142)）：
- **体验而非技术才是天花板**。多位独立开发者说小屏 + 无键盘让他们把提示词写短，"不可避免地给模型留了更多解释空间"，于是反复返工、技术债累积；一位明确说移动端"没有 6 个月前那么大的变革感"。
- **复制粘贴是硬伤**：终端输出、文件片段、浏览器 console 报错都难复制，难到"人们干脆不在手机上拼这段提示词"。
- **TUI-on-phone 被评价为 clunky**，用户想要原生控件（复制粘贴、顺滑滚动）而不是终端克隆。
- 各家文档补充的问题：静默失败到处都是、20–30 分钟的多 GB 初始化、只能 F-Droid 装、"Termux 随机退出"、signal 9 死亡。

**本方案的差异化**：
1. **不是"把 CLI 塞进手机"，而是"给 pi 内核配一个手机原生壳"** —— 内核零修改
2. **扩展生态是真的**：真 npm、真 TS、真文件布局，桌面扩展拿来即用
3. **留了 100% 兜底通道**（真 PTY + 原版 TUI），不靠"我们实现了它 95% 的 API"自称兼容
4. **复用 pi 自带渲染器**（§20.3），把"从零写 UI"变成"复用 + 增量适配"
5. **反过来增强**：无障碍操作手机、截图给模型看、分享接收、通知唤醒、语音 —— 这些是 pi 在电脑上没有的
6. **体验优先**：GUI 是一等公民，终端只做逃生舱

## 29. 需要拍板的四个决策点

1. **分发渠道**：只做 F-Droid + 侧载（最省事，可留 targetSdk 28 兜底），还是同时上 Google Play（需严格走 `nativeLibraryDir` + `PROOT_LOADER` + PIE + 16 KB 对齐 + API 36 的自律路线）？
2. **首启体积**：APK 直接带完整运行时（~150 MB，零等待可离线），还是最小引导 + 首启下载（APK ~40 MB，需联网）？
3. **是否做设备能力桥**（无障碍操作手机 + Shizuku/ADB）：会让 App 从"手机上的编码 Agent"变成"能自己操作手机的 Agent"，但需要高敏感授权，且与现有 DSH App 的能力有重叠 —— 自建、还是复用 DSH 那条 `127.0.0.1:3090` 桥？
4. ~~**渲染路线**~~ —— **已裁决：原生 Compose 为主**，WebView 仅用于 Mermaid 图与扩展自定义渲染两块（见 UI 规范 §4.10）。此决策不再需要拍板。

---

# 附录 A　关键事实索引（便于复核）

| 主题 | 位置 |
|---|---|
| 仓库根 README / 开发规范 | `README.md`、`AGENTS.md`、`CONTRIBUTING.md` |
| RPC 命令/事件全表 | `packages/coding-agent/docs/rpc.md`、`src/modes/rpc/rpc-types.ts` |
| RPC 实现（含扩展 UI 子协议） | `src/modes/rpc/rpc-mode.ts:91-311`、`:768` |
| RPC JSONL 严格切分 | `src/modes/rpc/jsonl.ts` |
| SDK API | `docs/sdk.md`、`src/core/sdk.ts:39` |
| Agent 会话 | `src/core/agent-session.ts`（3552 行） |
| Agent 循环 | `packages/agent/src/{agent,agent-loop,types}.ts` |
| 扩展 API 全量 | `docs/extensions.md`、`src/core/extensions/types.ts`（`ExtensionAPI` 自 1252 行；事件 union 1086-1113） |
| 扩展加载/发现（jiti） | `src/core/extensions/loader.ts`（import 在 513 行；目录规则 715-790 行） |
| 权限门控扩展示例 | `examples/extensions/permission-gate.ts` |
| 内建工具注册表 | `src/core/tools/index.ts` |
| 内建工具实现 | `src/core/tools/{read,bash,edit,write,grep,find,ls}.ts` |
| rg/fd 自动下载与 Android 特判 | `src/utils/tools-manager.ts`（Android 拒绝下载 366-374 行） |
| shell 探测与环境（PATH 前置 `~/.pi/agent/bin`） | `src/utils/shell.ts:88-120`、`:138-150` |
| 剪贴板（OSC 52 回退、termux-* 分支） | `src/utils/clipboard.ts:16-20,24,62`、`clipboard-command.ts:9` |
| 会话格式与存储 | `docs/session-format.md`、`src/core/session-manager.ts` |
| 认证存储（明文 0600） | `src/core/auth-storage.ts:25,52` |
| 压缩策略 | `docs/compaction.md` |
| 系统提示组装 | `src/core/system-prompt.ts`、`resource-loader.ts`、`skills.ts` |
| **HTML 导出渲染器（UI 复用重点）** | `src/core/export-html/{template.html,template.css,template.js,ansi-to-html.ts,tool-renderer.ts,index.ts,vendor/}` |
| 移动端官方文档 | `docs/termux.md`（全仓唯一提到 Android 的地方） |
| TUI 终端要求（Kitty 键盘、内联图片） | `docs/terminal-setup.md`、`docs/tui.md` |
| 容器化/沙箱 | `docs/containerization.md` |
| 扩展示例（约 75 个） | `packages/coding-agent/examples/extensions/` |
| 环境变量 | `docs/environment-variables.md` |

# 附录 B　M0 本机验证记录 `[实测]`

```bash
# 1) 运行时（本机已有 Ubuntu 24.04 + Node 24，等价于 App 内 rootfs）
node -v            # v24.19.0
npm -v             # 11.17.0
ldd --version      # Ubuntu GLIBC 2.39
uname -m           # aarch64
nproc              # 7

# 2) 安装 pi
npm install --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent@latest
#   → node_modules 440 MB；--version → 0.85.1

# 3) headless RPC 冒烟（✅ 全部 success）
printf '%s\n' '{"id":"1","type":"get_state"}' \
              '{"id":"2","type":"get_available_models"}' \
              '{"id":"3","type":"get_commands"}' \
  | node node_modules/@earendil-works/pi-coding-agent/dist/cli.js --mode rpc --no-session

# 4) proot 配方（取自运行中的 DSH App，PID 6754）
#   PROOT_LOADER=<nativeLibraryDir>/libprootloader.so
#   PROOT_LOADER_32=<nativeLibraryDir>/libprootloader32.so
#   PROOT_TMP_DIR=<pkg>/files/linux/tmp
#   PROOT_L2S_DIR=<rootfs>/.l2s
#   LD_LIBRARY_PATH=<pkg>/files/linux/lib:<nativeLibraryDir>
#   <libproot.so> --link2symlink -b <rootfs>/.l2s:<rootfs>/.l2s -L --kill-on-exit -0 \
#     --rootfs=<rootfs> --cwd=/root \
#     -b /dev -b /dev/urandom:/dev/random -b /proc -b /sys -b /system -b /apex \
#     -b /proc/self/fd:/dev/fd -b /storage/emulated/0:/sdcard \
#     -b /storage/emulated/0:/storage/emulated/0 \
#     /bin/bash -c "..."

# 5) 预置资源可获取性
#   node-v24.19.0-linux-arm64.tar.xz                 30.5 MB  ✅ HTTP 200
#   fd-v10.2.0-aarch64-unknown-linux-musl.tar.gz      1.5 MB  ✅ HTTP 200
#   ripgrep 14.1.1 aarch64-musl 该 URL 404（需按实际 release 名确认）

# 6) /sdcard/Download 可写  ✅
```

# 附录 C　证据分级与不确定项

**已实测（本机真机）**：pi RPC 跑通；运行时环境参数；DSH 的 proot 配方与进程树；proot 内可访问 127.0.0.1；Download 可写；预置资源 HTTP 可达。

**源码直读**：第二部分的全部内容（RPC 协议、扩展 API、事件表、工具行为、会话格式、依赖清单、平台 API 使用点、export-html 渲染器）。

**文献（附链接）**：第三部分的 Android 平台约束（W^X/SELinux、`run-as` 陷阱、memfd、zygote seccomp、Play 政策与 16 KB、proot 破坏点、UNIXbench 数字、nodejs-mobile 状态、Bun 非 PIE、原生模块案例、幻影进程杀手、FGS 限制）、第四部分 §28 的竞品调研。

**需要进一步核实的点（标注为不确定）**：
1. `proot -L` 的确切语义（本机配方里有，但未查证；照抄即可，不影响设计）。
2. "TCGETS2 ioctl 已在 proot 5.1.107-66 修复"来自一份作者自述曾发布未验证说法的文档，**需独立核实**。
3. 热节流对长时间 Agent 任务的量化影响——**未找到量化来源**。
4. 本机未验证：内存可用仅约 1.2 GB，多会话并发时的实际表现需实测。
5. 本机 `rg`/`fd` 缺失，pi 在 proot 内的 `ensureTool()` 下载行为需实测确认（理论上 `platform()==="linux"` 不会触发 Android 拒下分支）。
6. Play 对"APK 内包含可执行运行时"的审核口径存在不确定性，**建议先做 F-Droid/侧载构建，Play 作为第二阶段**。

---

*文档结束。技术可行性已在本机端到端验证；剩余工作是工程实现与产品打磨。*
