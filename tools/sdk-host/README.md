# sdk-host —— 用官方 SDK 跑 pi 引擎的宿主

`pi --mode rpc` 的宿主上下文是 pi 自己写的残缺实现（`rpc-mode.js` 里 17 处
`not supported in RPC mode`）。扩展的 `ctx.ui.custom()`、`setWidget(工厂)`、消息渲染器
在那种模式下永远拿不到组件对象。

这里改用官方 SDK 自己建 session、自己实现 `ExtensionUIContext`：

```
App(Kotlin) ──JSONL──> host.mjs ──SDK──> AgentSession + 扩展
```

- 一个进程 = 一个工作区（与 App 的 `PiEngineHost` 对齐）；
- 组件只走 `render(width)` / `handleInput(data)`，宿主按 App 给的**字符宽度**重渲染；
- 子代理走 `pi-subagents` 自己的进程内 RPC（`subagents:rpc:v1:*`），不做键盘注入；
- 项目信任默认**关**（SDK 默认 `projectTrusted=true`，照搬会把项目级扩展当已信任加载）。

## 跑冒烟测试

`host.mjs` 需要能解析 `@earendil-works/pi-coding-agent`。用仓库里的引擎载荷即可：

```bash
mkdir -p /tmp/sdkrun && tar -xzf app/src/main/assets/runtime/pi-engine.tgz -C /tmp/sdkrun
cp -r tools/sdk-host/* /tmp/sdkrun/
cd /tmp/sdkrun && node smoke.mjs
```

期望输出（9 条全 PASS）：

```
PASS 握手
PASS session 建立
PASS setStatus 到 App
PASS setWidget(行) 到 App
PASS setWidget(工厂) 渲染成 surface
PASS 按键喂进组件
PASS select 拿到 App 回值
PASS custom() 出整屏 surface
PASS custom() 由组件自己 done()
harness: OK (sdk-host)
```

`fixtures/probe-extension.mjs` 是把 `ExtensionUIContext` 每一面都碰一遍的探针扩展。

## 命令面（已接）

| 命令 | 作用 |
|---|---|
| `hello` | 协议版本 + 能力表 |
| `open` | 建 session（先应答，SDK 慢慢加载；绑扩展在后台） |
| `prompt` / `abort` / `session.steer` / `session.followUp` | 对话控制 |
| `session.stats` / `session.tree` / `session.name` | 会话状态 |
| `session.thinking` / `session.tools` | 推理等级、工具开关 |
| `session.compact` / `session.retry` / `session.reload` / `session.export` | 维护操作 |
| `session.new` / `session.fork` / `session.switch` | **待接**（需要 AgentSessionRuntime） |
| `render.message` | 调扩展注册的 `MessageRenderer`，把 `render(width)` 的行发回 |
| `subagent` | 转发到 `pi-subagents` 的进程内 RPC |

UI 请求：`ui.select/confirm/input/editor` 等 App 回值；`ui.surface` 发组件渲染结果；
App 用 `ui.input` 把按键喂回 `handleInput`，用 `ui.resize` 改宽度。

## 启动耗时（实测，本机）

- 握手 `ready`：**115 ms**（SDK 改为 dynamic import 之后；之前要等整包加载完，约 7 s）；
- `open`：约 5.6 s（SDK 导入 + session 创建），App 应显示「引擎启动中」而不是白屏。
