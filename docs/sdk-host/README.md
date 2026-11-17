# sdk-host：把引擎从 `pi --mode rpc` 换成官方 SDK

> 状态：**宿主骨架已完成并经真实 pi 1.5.0 验证；Kotlin 侧尚未切换。** 分支 `feat/sdk-host`。
> 读完这一页你就知道：为什么做、做了什么、没做什么、下一步从哪下手。

## 一句话

App 不再启动 `pi --mode rpc` 并说 pi 的 RPC 协议，而是启动**我们自己的 Node 宿主**（`app/src/main/assets/guest/sdk-host/`），
宿主用官方 SDK（`@earendil-works/pi-coding-agent`）建会话，通过**我们自己定义的协议**和 Kotlin 通信。
pi 的 RPC 协议、RPC 响应类型、RPC 的残缺 UI 桩，**全部废弃**，不做兼容层。

## 为什么

`pi --mode rpc` 给扩展的 UI 对象里有 17 处「RPC 模式不支持」：`custom()` 返回 undefined、
`setWidget` 的组件工厂被丢弃、footer/header/编辑器组件/自动补全/主题全是空操作、`getEditorText` 永远空串。
会话被替换后要靠重启进程重载；包、资源、MCP 都要另起 `pi` 子进程再解析输出。
这些都是**协议形状**造成的，不是缺实现。SDK 让我们自己提供 UI 对象、直接调 `SessionManager` / `ModelRuntime` /
`DefaultPackageManager` / `SettingsManager`，一个进程里换会话，不用重启。

## 设计原则（评审任何改动都拿这几条量）

1. **宿主只做适配，不重写 pi 的功能。** 凡是 pi 已经有的逻辑（压缩、分支、重试、MCP、技能、包解析……）一行不复制。
2. **官方能力第一版接全。** 清单见 [capabilities.md](capabilities.md)，每一项标了「已接 / 通用口可达 / 待接」。
3. **升级 pi 时，已接的能力随引擎更新。** 靠两条：事件流**原样透传**；新 API 通过**通用调用口** `sdk.call` / `sdk.get` / `bus.*` 不改宿主就能用。
4. **宿主持状态并主动推送，不是换了信封的 RPC。** `state` 事件 + 变更命令应答带 `state` + 粗粒度命令 `chat.send/stop`。
5. **协议由我们定，且带序号 / 会话代号 / 错误码。** 见 [protocol.md](protocol.md)。
6. **UI 全由 App 原生画。** 终端组件只能拿到「带颜色的文本行」，要原生就走 PAUI（[ui-design.md](ui-design.md)）。
7. **proot 与 proroot 两个运行时共用同一个宿主。** 见 [runtimes.md](runtimes.md)。

## 文档地图

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 进程与模块、启动顺序、会话替换、背压、为什么这样拆 |
| [protocol.md](protocol.md) | 帧格式、全部命令与事件、错误码 |
| [capabilities.md](capabilities.md) | 官方能力清单 × 接入状态 × 怎么接 + **已知缺口** |
| [rpc-to-host.md](rpc-to-host.md) | 旧 RPC 命令/事件 → 新命令/事件的对照表（Kotlin 改造时逐行对） |
| [ui-design.md](ui-design.md) | 新增 UI 面的设计（表面、弹层、信任弹窗、子代理…） |
| [runtimes.md](runtimes.md) | proot / proroot 的启动接入点和验证清单 |
| [migration.md](migration.md) | **分步任务单**：谁、改哪些文件、验收标准 |
| [dual-engine.md](dual-engine.md) | **双引擎预留**：Coding Agent + Durable 的架构、接口草图、阶段（SDK 优先，Durable 不实现） |
| [ui-redesign.md](ui-redesign.md) | **整个 App 界面的重做方案**（每屏的数据源命令、删什么、自审清单、落地顺序） |
| [upgrading.md](upgrading.md) | 升级 pi 版本的流程 |

## 已经做完的（本分支）

- 宿主骨架：传输层（接管 stdout、带背压、LF 分帧）、协议、会话运行时（`AgentSessionRuntime` + 单点重绑）、完整 `ExtensionUIContext`、表面管理、命令表。
- 官方内置扩展装进来了：**MCP、Code Mode、Tool Search**（SDK 会话默认没有，CLI 才内置）。
- 官方默认工具、全部会话操作（new / switch / fork / clone / tree / compact / retry / bash / export）。
- 通用调用口 `sdk.call` / `sdk.get`，扩展事件总线桥 `bus.*`。
- 测试：`tools/sdk-host/test.mjs`（56 项，真引擎）+ `contract.mjs`（SDK 依赖面）+ CI 三种环境。

## 没做的（按优先级）

1. **Kotlin 客户端与 UI 切换**（migration.md 步骤 1–4）。这是最大的一块。
2. 服务迁入宿主：包安装/更新/卸载、资源开关、MCP 检测与登录、设置读写、OAuth 登录（步骤 5）。
3. 子代理一等公民（步骤 6）。
4. 已知缺口：HTTP 代理/超时配置、启动迁移、`llama.cpp` 内置扩展（capabilities.md 末尾）。

## 没验证过的（诚实清单）

- 没有在真机的 proot / proroot 里跑过宿主。本机只模拟了 proroot 的 `scandir` 补丁。真机验证清单在 runtimes.md。
- Kotlin 侧一行没改，所以没编译。
- 宿主的**启动耗时**在手机上未测；本机 node22：握手 <100 ms，SDK 打包入口加载 ≈ 250 ms（冷缓存下 1 s 量级）。
- [`sessions.md`](sessions.md) —— 会话管理整块按 SDK 重做：审计、覆盖对照、UI、删除顺序

## 范围原则（当前）

只做**官方 SDK 有的能力**，并按手机原生体验做强；不为官方不内置的第三方扩展（子代理、web-access 等）做专属接入或优化——通用的命令、工具、flag、扩展 UI（select/confirm/input/editor/status/widget/custom）、事件总线（`bus.*`）接好，扩展接入后自然可用。已撤掉的专属代码：`subagent.rpc`、子代理 widget 摘要与其 fixtures/脚本。PAUI 设计保留为文档，暂不实现。
