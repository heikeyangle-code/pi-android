# 分步任务单

每一步：**目标 → 改哪些文件 → 具体任务 → 验收**。按顺序做，每步结束分支应当能编译；步骤 1–3 之间 App 不保证可用（切换式改造，不并行保留旧栈）。
做完一步，把本页该步标题后的「[ ]」改成「[x]」并写上提交号。

通用约定：
- 提交信息写清楚「做了什么 / 为什么」；不带工具署名；
- 每步结束跑：`tools/check-ui-consistency.sh`、`tools/check-nested-comments.sh`（若有）、`node tools/pi-contract.mjs`、`node tools/sdk-host/contract.mjs`、`node tools/sdk-host/test.mjs`（三种变体）；
- 没编译 / 没上机跑过的东西，提交说明里**明说**。

---

## 步骤 0 [x]：宿主骨架（已完成）

`app/src/main/assets/guest/sdk-host/**`、`tools/sdk-host/{test,contract,client}.mjs`、CI。见 README「已经做完的」。

## 步骤 1 [~]：把宿主装进 guest 并能启动

> **已做**：`engine/SdkHostInstaller.kt`（同步安装：相同不写、源里没了的删、先 `.partial` 再改名；harness 用真 assets 装一遍并用 node 起一次，见 `sdk-host-install`）、`engine/SdkHostLaunch.kt`（启动命令，含引用转义）、`PiEngineHost` 每次启动调用安装（`lastSdkHostInstall`，非致命）。
> **没做**：`guestCommand` 仍是旧入口 —— 它必须和读取端（`PiEngineSession`）一起换，放在步骤 3；真机 proot / proroot 各起一次还没验。
> 没编译（Gradle 此处连不上 Maven）；`PiEngineHost` 的改动只有 ~20 行，等 CI。

**目标**：App 能用新命令行启动宿主，收到 `host.ready`，`host.hello` 能应答。还不接 UI。

**改**：`engine/PiEngineHost.kt`；新增 `engine/SdkHostInstaller.kt`；`runtime/*`（只读，确认两个运行时的启动路径）。

**任务**
1. `SdkHostInstaller`：把 `assets/guest/sdk-host/` 整棵树复制到 `<rootfs>/opt/pi/sdk-host/`，写版本戳（文件内容哈希或 `BuildConfig.VERSION_CODE`）避免重复复制。同时保证 `scandir-fix.mjs` 的现有安装不受影响。
2. 改 `guestCommand`（见 runtimes.md「接入点」）。
3. 删 `EngineEntry` 的 Bundled/Unpacked 选择；保留「引擎包目录存在」检查。
4. 读取子进程 stdout 的现有逻辑保留，但按「LF 分行的 JSON 帧」解析（先只打日志）。

**验收**：真机 proot 和 proroot 各启动一次，logcat 里看到 `host.ready` 与 `host.hello` 应答；runtimes.md 验证清单前两项勾上。

## 步骤 2 [x]：Kotlin 客户端 `PiHostClient`

> **已做**（`app/pi/host/`）：`HostFrame`（解析，未知帧忽略）、`PiHostClient`（epoch 过滤、seq 跳号 → `Resync`、配对/超时/取消/退出清理）、`HostState`+`HostStateStore`（按 (epoch,seq) 取最新）、`HostCommands`（具名命令 + `sdkCall` 通用口）。
> harness `host-client`（84 条）含**回放真宿主录下的会话**（`node tools/sdk-host/record.mjs` 生成 `app/src/test/resources/sdk-host/session.jsonl`；宿主协议变了就重录）。
> **没做**：`AgentEventAdapter`（等步骤 3 接 `Events.kt`）；`HostSignal` 没有 replay，**调用方必须先订阅再开读**。
> 本地用 JSON 替身编译、跑通，真 kotlinx-serialization 的 API 差异等 CI。

**目标**：类型化的命令调用 + 事件流，规则按 protocol.md。

**新增** `app/src/main/kotlin/app/pi/host/`（纯 Kotlin，不依赖 Android，便于 JVM harness 测）：
- `HostFrame.kt`：`sealed interface HostFrame { Res, Evt, Req }`；用现有的 JSON 工具（沿用项目里 `rpc` 模块用的那套）解析一行。**未知 `t` / 未知事件名忽略，不抛**。
- `PiHostClient.kt`：
  - 持有 `send(line)` / `lines: Flow<String>` 两个抽象（由 `PiEngineHost` 注入，便于测试）；
  - `suspend fun call(cmd, args): Result<JsonObject>`：生成 `id`，`CompletableDeferred` 配对，**超时与取消要清理 pending**；`res.ok=false` 变成带 `code` 的异常类型 `HostException(code, message)`；
  - 事件流 `events: SharedFlow<HostEvent>`：**按 epoch 过滤**（丢弃 `epoch < current`；`session.bound` 更新 current）、**seq 跳号检测**（跳号时发一个内部 `Resync` 事件，由 ViewModel 去 `session.snapshot`）；
  - `req` 帧转成 `HostRequest` 流，供对话框队列消费，回答用 `respond(id, value|cancelled)`。
- `HostCommands.kt`：每条命令一个 suspend 函数，参数/返回是 data class，名字按 rpc-to-host.md 左列保持旧名以减少调用点改动（`prompt`、`steer`、`getState` → `snapshot` 等）。
- `AgentEventAdapter.kt`：把 `evt agent` 的 `data` 交给**现有** `Events.kt` 的解析（形状同旧事件行）。

**测试**：`app/src/test/kotlin/app/pi/host/HostClientCheck.kt`（bare-JVM harness，登记进 `tools/run-app-pure-checks.sh`）。
用 `node tools/sdk-host/test.mjs` 的一次运行录制一份真实帧序列存为 fixture（给 `client.mjs` 加个 `--record <file>` 开关），测试里回放：
epoch 过滤、seq 跳号、res/req 配对、超时清理、未知帧忽略。

**验收**：harness 通过；录制 fixture 回放得到的命令结果与 node 侧断言一致。

## 步骤 3 [~]：ViewModel 切换并删除旧 RPC 层

> **进度（诚实版）**：
> - 已做：`PiEngineSession` 接宿主协议（真宿主端到端 harness 验过）；`PiEngineHost` 用 `SdkHostLaunch.command` 起宿主、
>   握手后 `startHost(launch.toOpenArgs())`（启动前设置变成结构化参数，`host-launch` harness 钉住）；
>   `PiSessionViewModel` 的 `send/request` 全部改成 `respondUi/fire/api`；树导航改直接调 `session.navigate`
>   （不再绕 `pi-android-bridge` 扩展命令）；`PiEngineApi.kt` 已删，VM 用 `HostEngineApi`/`HostCommandException`。
> - **没验证**：整个 App 没过 Gradle（环境没有 android.jar，只能编纯逻辑部分），VM/Host 的改动靠逐处核对 +
>   「VM 用到的 engine 成员都存在」的检查，**不能当作编译通过**；没上机跑 proot/proroot。
> - 还没做：删旧 RPC 层剩余（`PiCommands` 的 `extensionUi*` 仍被 VM 用来拼回答再翻译、`rpc` 模块里旧 Events 解析
>   仍是转录的输入、`pi-oauth.mjs` / `pi-resources.mjs` / `PiMcpCheck`）；会话库整块见 `sessions.md`。

**目标**：聊天主路径（发送、流式、工具卡、队列、中止、模型/思考、会话列表/切换/分叉/树）全部走新客户端，旧 RPC 代码删除。

**改**：`ui/PiSessionViewModel.kt`（约 6k 行，分块改，按 rpc-to-host.md 逐行换）、`engine/PiEngineSession.kt`、`engine/PiEngineApi.kt`（删，换 `HostCommands`）、`engine/PiEngineHost.kt`（删重启重载逻辑）。
**删**：`rpc/…/Commands.kt`、`Responses.kt`、`PiLaunchOptions`（换 `OpenArgs`）、`ExtensionFlagArgs`、`pi-android-bridge` 里仅为绕 RPC 而存在的命令（`NAVIGATE_COMMAND` 等；设备桥本身若仍需要，保留为扩展，后续迁成 `extensionFactories`）。
**保留**：`Transcript.kt`（对话还原）、`Events.kt` 的事件解析、`Messages.kt`、`SessionEntries.kt`、`Ansi.kt`。

**任务要点**
0. **状态来源统一为 `state` 事件**：ViewModel 里模型、思考等级、工具、用量、成本、队列这些字段不再各自 `getXxx`，全部由最近一份 `state` 派生；命令应答里的 `state` 也喂进同一个入口（按 `seq` 取新）。
1. 启动序列：spawn → 等 `host.ready` → `host.hello`（核对 `protocol == 1`，不符报「宿主版本不兼容」）→ `session.open`（把原来命令行标志换成 JSON 参数）→ 显示 ui-design §6 的启动状态。
2. `reload`：改成 `session.reload`，不重启进程。
3. 对话框：接 `req` + `ui.cancel`（ui-design §4）；`session.bound` 清空队列。
4. `project.trust.required` 弹窗（ui-design §5）。
5. `session.prompt` 的 `rejected` 与 `agent.error` 事件的错误展示：沿用现有错误气泡。

**验收**：真机跑完 runtimes.md 的完整清单（proot、proroot 各一遍）；旧 RPC 相关符号全局 grep 为 0（`grep -rn "get_state\|extension_ui_request\|--mode rpc" app rpc` 无结果，历史说明文档除外）。

## 步骤 4 [ ]：表面 UI

**目标**：ui-design.md §1–§3、§8。

**新增**：`ui/extension/SurfaceView.kt`、`ui/extension/KeyBar.kt`、`ui/extension/SurfaceHost.kt`（按 placement 摆放）；改 `ExtensionWidgetCard.kt`（卡体可换成 `SurfaceView`）、`ExtensionChrome.kt`。
**任务**：ANSI 渲染复用 `Ansi`；宽度测量 + `ui.resize` 防抖；按键条；Esc→`ui.close` 的关闭规则；`ui.palette` 在启动与主题切换时发送。
**验收**：用 `tools/sdk-host/fixtures/probe-extension.mjs`（`--extension` 挂上）在真机上看到：行 widget、自动刷新的工厂 widget（ticks 在涨）、`custom` 面板里按 Enter 能结束、旋转屏幕宽度跟着变。

## 步骤 5 [ ]：服务迁入宿主，删子进程

按 capabilities.md 的「待接」逐项，每项一个 `services.<域>.<动作>` 命令 + 一个 node 测试：

| 步 | 内容 | 做法 | 替换什么 |
|---|---|---|---|
| 5a | 包安装 / 卸载 / 更新（带进度事件） | `sdk.call packageManager …` 包一层，`setProgressCallback` → `evt services.progress` | 替换 `PiPackagesHost` 的子进程 |
| 5b | 资源开关 | 照抄 `pi-resources.mjs` 的逻辑，改成直接调 SDK | 删 `pi-resources.mjs`、`PiResources` 的进程管理 |
| 5c | MCP 检测 / 登录 / 重连 / 启停 / 暴露方式 | 先读 MCP 扩展源码确认状态 API；OAuth 打开 URL 用新增的 `ui.openUrl` 请求 | 删 `PiMcpCheck` 的 `pi mcp list` |
| 5d | Provider OAuth 登录 / 退出 | `modelRuntime.login(provider, type, interaction)`，`interaction` 映射成 `req`/`evt` | 删 `pi-oauth.mjs` |
| 5e | 设置读写 | `sdk.call settings …` | 逐页切换，可与直接读写文件并存 |

**每项验收**：node 测试覆盖；Kotlin 侧对应页面不再 spawn 任何 `pi` 子进程（`grep -rn '"pi"' app/src/main/kotlin` 对应调用点消失）。

## 步骤 6 [ ]：子代理一等公民

设计见 ui-design.md §9。
1. 宿主：无需新命令（`subagent.rpc` + `bus.*` 已有）。**任务**：在 node 测试里装 `pi-subagents`（`npm i pi-subagents@<锁定版本>` 进测试用的 agentDir 的扩展路径）跑通 `ping` / `status` / 事件订阅；这一步是为了确认事件名和回复形状，**以实测为准更新 ui-design §9**。
2. Kotlin：`SubagentStore`（事件 → 250 ms 合并 → `status` 读取 → 状态模型）；列表页、详情页、徽标；steer / interrupt / stop / resume 命令。
3. 删：`ExtensionWidgetLines.kt` 里子代理相关的摘要拼接（`subagentSummary` 等），widget 里的子代理行改为跳转。
**验收**：真机启动两个子代理，列表能看到，点进去能看到实时对话记录，发 steer 能被收到（`deliveryStatus`），能 stop。

## 步骤 7 [暂缓]：PAUI 接到总线（不做第三方专属优化，见 ui-design.md §7）

ui-design §7。改 `ui/extension/PauiView.kt` 的输入源为 `bus` 事件；写一个演示扩展放 `tools/sdk-host/fixtures/`，并在 test.mjs 里加用例（`paui:render` → `bus` 事件转发 → `bus.emit paui:event` 回到扩展）。

## 步骤 8 [ ]：收尾

- 删除 `docs/` 里描述旧 RPC 的段落（或加「已废弃」头）；更新 `docs/architecture*.md`。
- CI：把 sdk-host 三个步骤并进主 job 的必过项。
- 合并到 main；`tools/sdk-host/README.md` 的原型说明删除。

---

## 配方：加一条新命令（宿主侧，5 分钟）

1. 选领域文件（`commands/session.mjs`…），`reg.add("域.动作", (args, call) => …)`。
2. 参数用 `str/optStr/bool` 校验，错误抛 `HostError(ERR.xxx, …)`；**只转发 SDK 调用，不加 pi 没有的逻辑**。
3. 需要「先应答后继续」用 `call.defer()` 并返回 `DEFERRED`。
4. `tools/sdk-host/test.mjs` 加一条用例；`protocol.md` 加一行。
5. 若 SDK 里没有对应方法 → 命令返回 `unsupported`，不要自己实现。

## 配方：App 想用一个宿主没有具名命令的 SDK 新方法

直接 `sdk.call {target, method, params}`，不用改宿主。用稳定了、需要校验或两阶段语义时，再补具名命令。
