# 双引擎方案：Pi Coding Agent + Pi Durable

> 状态：**设计 + 预留，不实现 Durable。** 优先级铁律：先把 Coding Agent 的 SDK 接入做完美
> （`migration.md` 步骤 1–8），本文只保证"以后加 Durable 不用推倒重来"。
> 没编译、没上机跑过；Durable 部分只读过 1.5.1 的 README 与 dist，没有运行过。

## 0. 结论先说

提案的方向（两个独立运行时、统一会话列表、会话绑定引擎、不热切换、各存各的）**是对的，我照单接受**。
但有 4 处我会改，原因都来自读了现有代码和 Durable 的真实形态：

| 提案 | 我的改法 | 为什么 |
|---|---|---|
| RuntimeManager / CodingAgentAdapter / DurableAdapter / SessionRegistry / EventDispatcher / StorageRouter / RuntimeCapabilities 七个类 | **只留 4 个概念**：`EngineHost`（进程）、`EngineClient`（协议客户端）、`SessionRef`（会话身份）、`Capabilities`（能力表）。EventDispatcher、StorageRouter 不单独成类 | EventDispatcher 在现有代码里就是 `PiEngineSession.handle → TranscriptReducer → publish`，再抽一层只会多一跳；StorageRouter 只是"路径约定"，没有逻辑可路由，写成类是空壳 |
| Durable 当 sdk-host 里的第二个模块 | **Durable 是另一个入口 `durable-host`，另一个 Node 进程**，共用 `io/protocol/hub` 底座 | 见 §3：Node ≥22.19、单写者存储、崩溃隔离、版本错位（durable 1.5.1 vs coding-agent 1.5.0）。同进程等于把两个引擎的崩溃与升级绑在一起 |
| "统一事件格式" | **不统一事件，也不强求统一行类型**：每个引擎自己的 reducer + 自己的专属渲染；只共享最底层组件，行类型开放（见 §2.4） | 两边事件形状本来就不同：Coding Agent 是事件流；Durable 是 state-view `watch()` + Chord 操作。`Transcript.kt` 里全是 pi 概念（thinking/tool/bash/compaction/分支摘要），Durable 的 Task/Commit/Document 没有对应物，硬统一必丢信息 |
| SessionRegistry 存会话 | **App 只存一张薄索引**（引擎+原生 id+workspace+标题缓存），真相永远在各引擎自己的存储里 | 现有 `PiSessionStore` 已是这个哲学："文件就是真相，索引只是缓存，读不出来就全量扫描" |

## 1. 现状审计（对照代码，不是猜的）

| 提案要的 | 现有 | 判断 |
|---|---|---|
| workspace | `runtime/WorkspaceStore.kt`：`<files>/pi/workspaces/workspace-N`，**路径即身份**，当前选择存 `app.workspace.current` | 直接复用。"默认引擎"加一个 `app.workspace.engine.<name>` 设置即可 |
| 会话列表 | `session/PiSessionStore.kt`（1048 行）扫 `sessions/` 下 jsonl，按 header 的 `cwd` 过滤，带 `PiSessionIndex` 缓存 | **只认 pi 的 jsonl**。要加引擎维度：每行 `Summary` 增 `engine` |
| 引擎进程 | `engine/PiEngineHost.kt`（启动，单一 `guestCommand` 缝）、`PiEngineSession.kt`（一个进程的会话，含 reducer） | 一进程一活动会话。已经是"一个 EngineHost 对应一个进程"的形状，可直接复用 |
| 事件→UI | `rpc/Transcript.kt`（2771 行 `TranscriptReducer`）→ `PiEngineSession.publish` → `TranscriptPublication`（只发变化的行） | 这就是"共享渲染核心"。**只有最底层组件（Markdown/代码块/diff/图片/卡片外壳）是引擎中立的**；行类型本身带 pi 概念，不能假定 Durable 通用 |
| 后台/杀进程 | `service/PiEngineService` + `PiEngineLifecyclePolicy` + `engine/EngineCrashRecovery` | 已有；Coding Agent 恢复 = 重开 session 文件 |
| Durable | 无 | 全新 |

关键事实：**现在没有"多会话同时运行"**。`PiEngineSession` 是一进程一个活动会话；切会话 = `session.switch`（同进程替换，epoch+1）。
所以提案里"A 会话在跑，切到 B 不影响 A"是**新能力**，不是现状。见 §5。

## 2. 目标架构

```
UI（聊天页只依赖下面的"视图模型"，不 import 任何引擎类型）
  │  TranscriptPublication（行）  +  SessionState（状态）  +  Capabilities（能力表）
  ▼
EngineClient（接口）                    ← Kotlin，app/pi/engine/
  ├─ CodingAgentClient  : sdk-host 协议 v1（已设计，docs/sdk-host/protocol.md）
  └─ DurableClient      : durable-host 协议（预留，见 §4）
  │  每个 client 内部：协议帧 → 各自的 reducer → 同一套行模型
  ▼
EngineHost（进程）                      ← 一个引擎实例 = 一个 Node 进程
  ├─ sdk-host     (pi-coding-agent 1.5.0)   --agent-dir  <files>/pi/agent
  └─ durable-host (pi-durable 1.5.1)        --data-dir   <files>/pi/durable/<workspace>
```

### 2.1 四个概念（Kotlin 草图，**不入库、未编译**）

```kotlin
enum class EngineKind { CodingAgent, Durable }

/** 会话身份。App 内部 id 只用于 UI 路由，真相是 (engine, nativeId)。 */
data class SessionRef(
    val engine: EngineKind,
    val nativeId: String,      // CA: session header id；Durable: conversation id
    val workspace: String,     // "workspace-N"
)

/** 能力表：UI 据此点亮入口，不判断"是哪个引擎"。来源：host.hello.capabilities。 */
data class Capabilities(val names: Set<String>)  // 例 "session.fork" "session.compact" "task.resume" "ext.ui.dialogs"

interface EngineClient {
    val kind: EngineKind
    val capabilities: Capabilities
    val state: StateFlow<SessionState>          // 宿主持有、主动推送（原则 4）
    val transcript: StateFlow<TranscriptPublication>
    suspend fun open(ref: SessionRef)           // 打开/恢复（CA: 开文件；Durable: Harness.open + resume）
    suspend fun create(workspace: String): SessionRef
    suspend fun send(text: String, attachments: List<Attachment> = emptyList())
    suspend fun stop()
    suspend fun close()                         // 释放进程内资源；不删数据
    suspend fun call(cmd: String, args: JsonObject): JsonObject  // 逃生口，同 sdk.call
    val raw: SharedFlow<RawEvent>               // 原始事件原样保留（调试/扩展 UI）
}
```

- **不做 EventDispatcher 类**：每个 client 自己把帧喂给自己的 reducer，产出同一种 `TranscriptPublication`。
- **不做 StorageRouter 类**，只定路径约定：

  ```
  <files>/pi/agent/...                      Coding Agent 原生数据（现状，不动）
  <files>/pi/durable/<workspace>/...        Durable 原生数据（一个 workspace 一个存储，单写者）
  <files>/pi/registry.json                  App 薄索引（可丢，丢了就重扫）
  ```
- **SessionRegistry = `PiSessionStore` 加 engine 维度**：CA 行仍由扫 jsonl 得到；Durable 行由 `durable-host` 的 `session.list` 得到（或扫其 JSONL 存储）。两路合并按更新时间排序即"统一列表"。

### 2.4 渲染分层（行类型开放，专属渲染各管各的）

```
各引擎自己的 reducer          (CA: Transcript.kt 不动；Durable: 新写)
   ↓ 产出
行 = 通用行（文本/代码/图片/错误）｜引擎专属行（engine + kind + raw 原样保留）
   ↓ 渲染
通用行 → 共享组件；专属行 → 各引擎自己的 Composable；未知 kind → 兜底"原始 JSON 折叠卡"
```

聊天页只依赖"行列表 + 能力表"，不 import 引擎类型。兜底渲染保证引擎升级出新事件时先能显示、不崩、不吞内容。
Durable 的真实输出形状未实测，专属行的具体 kind 等 durable-host 实现时按真实输出定。

### 2.2 不热切换引擎

会话一旦创建，`SessionRef.engine` 不可变。UI 上没有"换引擎"按钮；要换只有"新建 + 导出/迁移"（迁移是显式功能，阶段 4，且有损，要提示）。
workspace 的"默认引擎"只影响**新建**会话。

### 2.3 扩展系统各用各的

CA 的 Extension API 在 sdk-host 里已全接（`ui-design.md`）。Durable 的 Extension/Registry/Tool/Hook/Task 由 `durable-host` 原样加载，
UI 通过 `Capabilities` 里的 `ext.*` 名字点亮。**不做跨引擎的扩展抽象**——两边扩展不通用，假装通用会两边都做不好。

## 3. 为什么 Durable 必须是独立进程

1. **Node 版本**：Durable 要 node ≥ 22.19（用 `node:sqlite`）。运行时里的 Node 版本要在 proot/proroot 真机上确认，同进程会让 CA 被迫跟着升。
2. **单写者**：Durable 的 JSONL/SQLite 存储没有跨进程锁。独立进程 + "一个 workspace 一个 durable-host" 天然保证单写者；同进程多 Harness 反而要自己防并发。
3. **崩溃隔离**：Durable 是 experimental；它崩了不能带走正在跑的 Coding Agent 会话。
4. **版本错位**：durable 1.5.1 vs coding-agent 1.5.0，模型层也不同（Durable 用 pi-ai `createModels()`，CA 用 `ModelRuntime`）。分进程各锁各的依赖，升级互不牵连。
5. **成本**：只有用到 Durable 才拉起，不用不占内存。

共用部分抽成 `host-core`（`io.mjs / protocol.mjs / hub.mjs / state.mjs` 本来就与引擎无关）。**现在不拆目录**——等第二个入口真出现再拆，避免过早抽象；本文只保证这几个文件不 import pi SDK（现状已满足，contract 测试可加一条断言）。

## 4. durable-host 协议（预留，不实现）

沿用 v1 帧（`{id,cmd,args}` / `{t:"res"}` / `{t:"evt"}` / `{t:"req"}`），`host.hello.engine = "durable"`。命令按能力命名，示例：

| 命令 | 对应 Durable | 备注 |
|---|---|---|
| `session.list / create / open / close` | Harness / Conversation | `open` 内部做 `Harness.open` + `resume()` |
| `chat.send / chat.stop` | Conversation 提交 / 中止 | 与 CA 同名，UI 不分叉 |
| `task.list / task.resume / task.cancel` | Task | **Durable 独有**，能力 `task.*` |
| `view.watch / view.unwatch` | state-view `watch()` | 推 Chord 操作，不是事件；`durable-host` 自己把它折成"行"再推，或原样推给 Kotlin reducer（二选一，实现时定） |
| `sdk.call / sdk.get` | 逃生口 | 同 CA |

**恢复语义必须区分**（UI 文案也要区分）：
- Coding Agent："会话恢复" = 重开 jsonl 文件。进行中的那一轮**丢失**（工具调用可能执行了一半）。
- Durable："任务恢复" = `Harness.open` 后 `resume()`，**进行中的任务可以继续**，这是它存在的意义。

## 5. 多会话并行（唯一需要新验证的运行时问题）

"A 在跑时切到 B 不影响 A" 的两条路：

- **路线 1（推荐起步）：一个活动会话 = 一个 host 进程。** 切会话时 A 的进程继续跑（后台），为 B 起新进程。最简单、隔离最好；代价是内存（手机上限制 2–3 个并行）。
- **路线 2：同进程多个 `AgentSessionRuntime`。** 省内存，但 `configureHttpDispatcher`、事件总线、`process.chdir` 类的全局状态是否互不污染**我没验证**，不要提前承诺。

阶段 1–2 保持现状（一进程一会话，切换即替换）；路线 1 在阶段 3 做，前提是 Android 前台服务 + 内存预算评估。

## 6. Android 后台 / 被杀恢复

- 进程被杀 = guest 里的 Node 一起没了（`PiEngineService` 前台服务能降低概率，不能消除）。
- Coding Agent：重启后按 `SessionRef` 重开文件，未完成的一轮标记为"被中断"。已有 `EngineCrashRecovery` 的路径。
- Durable：重启后 `Harness.open` → `onWake/resume()`。**这是 Durable 在手机上的最大价值，也是最大风险**：SQLite（`node:sqlite`）在 proot/proroot 的文件系统上是否稳定**未验证**；备选是 JSONL 存储。要上机跑 kill -9 测试后才能下结论。
- 两者都要有"锁文件/心跳"，防止被杀后残留进程与新进程同时写同一存储（Durable 尤其）。

## 7. 阶段（SDK 优先）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 本文 + `host.hello.engine` + `Summary.engine` 的位置预留 | **本次** |
| 1–2 | `migration.md` 步骤 1–8：Kotlin `PiHostClient`、ViewModel 切换、删 RPC 层、surface UI、服务进宿主、子代理、PAUI | 进行中（优先） |
| 3 | 抽 `EngineClient` 接口（此时只有 CodingAgentClient 一个实现，**接口形状由真实 ViewModel 需要倒推**，不提前凭空设计） | SDK 完成后 |
| 4 | `durable-host` 最小版：创建/发送/流式/停止/恢复，跑通 JSONL 存储 | 需真机验证 Node 版本 |
| 5 | 统一会话列表、workspace 默认引擎、Durable 任务 UI、多会话并行（路线 1） | |
| 6 | 显式迁移（CA→Durable）、`node:sqlite` 评估 | |

## 8. 验证清单（每个引擎各跑一遍）

创建 → 发送 → 流式 → 工具执行 → 停止 → 杀进程后恢复 → 扩展加载 → 同 workspace 多会话共存 → 切会话不影响后台会话。
Coding Agent 一列在真机上也还没跑过；Durable 一列未开始。

## 9. 不做的事

- 不升级任何依赖版本来"凑齐"两个引擎（CA 钉在 1.5.0，Durable 钉在 1.5.1，各自锁）。
- 不在 sdk-host 里 import `@earendil-works/pi-durable`。
- 不统一两边的事件格式、扩展 API、模型层。
- 不为 Durable 预先写任何 Kotlin 代码（接口草图只在本文）。
