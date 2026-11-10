# 架构

## 进程与分层

```
┌────────────────────────── Android App（Kotlin）──────────────────────────┐
│ UI（Compose） ← PiSessionViewModel ← PiHostClient（新）                    │
│                                       │  JSONL，stdin/stdout，LF 分帧      │
└───────────────────────────────────────┼────────────────────────────────────┘
                                        │  proot 或 proroot（RuntimeSelection 选）
┌───────────────────────────────────────┼────────────────────────────────────┐
│ guest：/opt/node/bin/node /opt/pi/sdk-host/main.mjs ...                    │
│                                       ▼                                    │
│  io.mjs（传输）→ hub.mjs（序号/epoch/提问）→ commands/*（命令表）            │
│                         ▲                         │                        │
│  ui/*（表面、UI 上下文）  │  controller.mjs（运行时、绑定、关机）              │
│                         │            │                                     │
│  ┌──────────────────────┴────────────▼──────────────────────────────────┐ │
│  │ @earendil-works/pi-coding-agent（SDK，优先走 dist/bundle/index.js）     │ │
│  │  AgentSessionRuntime → AgentSession ← 扩展（含 MCP / Code Mode / ...） │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

一个进程 = 一个工作区（与 `PiEngineHost` 的「每个 cwd 一个引擎」一致）；进程内会话可被替换多次。

## 模块职责（`app/src/main/assets/guest/sdk-host/`）

| 文件 | 职责 | 不做什么 |
|---|---|---|
| `main.mjs` | 装配：接管 stdout → 发 `host.ready` → 注册命令 → 读 stdin | 不含业务逻辑 |
| `io.mjs` | 原始 stdout 的带背压写（EAGAIN 重试）、LF-only 读行 | 不懂协议内容 |
| `protocol.mjs` | 版本号、错误码、参数校验小工具 | |
| `hub.mjs` | 唯一的「向 App 说话」出口：`seq`、`epoch`、`ask()`（向 App 提问并等回答）、`ui.cancel` | |
| `sdk.mjs` | SDK 懒加载；优先 `dist/bundle/index.js`，失败回退 `dist/index.js` / 包名 | |
| `controller.mjs` | 持有 `AgentSessionRuntime`；`#bind` 是**唯一**订阅/绑扩展的地方；项目信任；关机 | 不含具体命令 |
| `commands/*.mjs` | 命令表。`registry.mjs` 是分发器；其余按领域分文件 | 不重写 pi 逻辑 |
| `ui/context.mjs` | 完整 `ExtensionUIContext` | |
| `ui/surfaces.mjs` | 把扩展组件投影成「表面」（渲染、输入、重绘、尺寸） | |
| `ui/theme.mjs` | 给组件的 `theme` 对象，语义色 → ANSI，色板可由 App 替换 | |

## 启动顺序（改之前读完）

1. **接管 stdout**（`takeOverStdout`）：必须在任何别的模块执行前。之后所有 `console.log` / `process.stdout.write` 去 stderr。
2. 设置 pi 打包入口会设的环境（`PI_CODING_AGENT`、`AI_AGENT`、关 warning）。
3. 发 `host.ready`。**不等 SDK。** SDK 加载是秒级，App 要立刻知道进程活着、能显示「引擎启动中」。
4. 注册命令，开始读 stdin。
5. App 发 `session.open` → 此时才 `import` SDK（`sdk.mjs`）→ 建 `ModelRuntime`、`AgentSessionRuntime` → 同步绑定（订阅事件、epoch +1）→ **先应答 `open`** → 后台 `bindExtensions`，完成后发 `ext.bound`。

为什么第 5 步要这样：`bindExtensions` 会等每个扩展的 `session_start` 处理器，而处理器里常见 `await ctx.ui.select(...)`（等用户）。
若 `open` 等它，一个扩展就能让「建立会话」永远不返回。（原型版冒烟测试踩到的。）

## 会话替换与 epoch

`new / switch / fork / clone` 以及扩展触发的同类动作，最终都走 `AgentSessionRuntime`，它在替换完成后回调 `rebindSession`。
宿主只在这一个回调里：取消旧订阅 → `ui.reset()`（收所有表面、取消所有挂起的提问）→ `epoch += 1` → 订阅新会话 → `bindExtensions`。

- **不要在命令处理里再手动绑定一次**（pi 的 rpc-mode 有这个历史遗留；测试里「epoch 恰好 +1」就是防它）。
- 非首次绑定是**等**扩展绑定完成的：`session.new` 的应答会等新会话的 `session_start` 处理器跑完（与 pi 一致）。
  App 必须在这期间仍能回答扩展弹出的对话框 —— 命令是并发处理的，所以可以。

## 背压

事件产生得比管道消化得快（长输出的工具、快速流式）时：写入队列超过 256 KB 高水位，`transport.drained()` 返回挂起的 Promise，
`session.agent.subscribe` 里的订阅者 `await` 它，agent 就停下来等。没有它，手机上会把帧堆满内存。

## 事件为什么「原样透传」

`agent` 事件的 `data` 就是 `AgentSessionEvent`，唯一改动是 `message_update` 去掉每个增量里都带着的整条 `partial`
（pi 自己的 `json-event.js` 同款；体积差一个数量级）。我们**不**重新塑形、不挑字段。
所以 pi 新增事件类型时，宿主不用改，App 的解析器遇到未知类型忽略即可。

## 为什么优先 `dist/bundle/index.js`

`exports["."]` 指向 `dist/index.js`（几千个小模块，proot 下每个 open/stat 都要被拦截；你们文档记的冷启动 18–20 s 就是它）。
`dist/bundle/index.js` 是同一份 SDK 的打包版，导出同样的符号。它**不是上游承诺的入口**，所以：找不到/加载失败就回退，
并且 `contract.mjs` 在 CI 断言它存在。

## 已验证与未验证

已验证（本机 node 22.23、真 pi 1.5.0）：以上全部行为，见 `tools/sdk-host/test.mjs`。
未验证：真机 proot / proroot；Android 上的 Node 版本差异（guest 里是 node 24.x）；手机上的启动耗时。
