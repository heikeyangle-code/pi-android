# 官方能力清单 × 接入状态

状态词：
- **已接**：命令 / 事件已有，测试覆盖。
- **通用口**：没有具名命令，但 `sdk.call` / `sdk.get` / `bus.*` 已经能调到，App 可以直接用；要不要补具名命令看是否需要参数校验或两阶段语义。
- **待接**：需要宿主新写（说明了接哪个 SDK 接口、怎么映射）。
- **不适用**：终端专属，手机上没有对应物（说明降级方式）。

依据：pi 1.5.0 的 `docs/*.md` 与 `dist/*.d.ts`。

## 1. Agent 核心与工具

| 能力 | 状态 | 怎么用 / 怎么接 |
|---|---|---|
| Agent 循环、流式输出 | 已接 | `agent` 事件，pi 原样（`message_update` 去 `partial`） |
| 工具执行事件（start/update/end） | 已接 | 同上，`tool_execution_*` |
| 官方默认工具 `read/bash/edit/write` | 已接 | 创建会话即带（测试断言） |
| 其余官方工具 `grep/find/ls` | 已接 | `catalog.tools` 看全集，`catalog.setTools` 启用 |
| `powershell` 工具 | 不适用 | 仅 Windows |
| 会话库：搜索 / 只读预览 / 任意会话改名 / 删除 / 书签 / 当前分支 / 上下文编辑 | 已接（宿主） | `session.search/read/rename/delete/label/path/contextEdit`；设计见 `sessions.md` |
| 提示图片 | 已接 | `session.prompt.images` |
| 排队：steer / follow-up、两种模式 | 已接 | `session.steer/followUp`、`queue.*Mode`、`session.clearQueue` |
| 用户 bash（`!cmd`）+ 扩展 `user_bash` 钩子 | 已接 | `session.bash` / `session.abortBash` |
| 工具白名单覆盖（`tools` / `excludeTools`） | 通用口 | 创建参数目前不暴露；需要时在 `controller.mjs` 的 `createAgentSessionFromServices` 调用里加 `open` 参数 |

## 2. MCP、Code Mode、Tool Search

| 能力 | 状态 | 说明 |
|---|---|---|
| MCP 扩展（`mcp.json`、连接、工具、`/mcp`） | 已接 | `controller.mjs` 的 `builtinExtensions()` 装入，与 CLI 同款（`builtin: true, replaceable: true`），`-builtin:mcp` 设置开关继续有效 |
| Code Mode（`codemode` 工具） | 已接 | 同上；默认**未激活**，用 `defaultTools: ["+codemode"]` 或 MCP 服务器的 `codemode` 暴露方式激活（与 CLI 一致） |
| Tool Search（`tool_search`） | 已接 | 同上；`deferred` 暴露的 MCP 服务器会激活它 |
| MCP 状态变化事件 | 通用口 | `bus.subscribe` 订阅 MCP 扩展发的事件（事件名见 MCP 扩展源码 `McpServersChangeEvent`） |
| MCP 服务器管理（检测、增删、启停、暴露方式、OAuth 登录退出） | **宿主已接，App 界面待重做** | `mcp.list/add/remove/update/login/cancel/logout`：官方把 `pi mcp …` 做成了可直接调用的函数（`runMcpCommand` 等，在 `dist/extensions/mcp/`，非公开导出，contract 测试把守）；登录的授权链接走 `mcp.auth` 事件，浏览器到不了本机回调时 `mcp.redirect` 让用户贴回重定向 URL（官方非交互 CLI 没有这条兜底）。测试用一个真 stdio MCP 服务器跑通。以下为旧说明： 现在靠 `pi mcp list --json` 子进程。迁移：读 MCP 扩展状态（先查它暴露的事件/方法；没有就走 `/mcp` 命令 + `ctx.ui` 对话框，由 `ui.*` 回路承接）。OAuth 登录需要打开浏览器：宿主发 `ui.openUrl` 请求（新增）由 App 用系统浏览器打开 |

## 3. Skills、Prompt Templates、Extensions

| 能力 | 状态 | 说明 |
|---|---|---|
| 技能 / 提示模板 / 扩展命令列表 | 已接 | `catalog.commands` |
| 调用 `/skill:name`、模板、扩展命令 | 已接 | 走 `session.prompt`（pi 会分派），**不会**产生用户气泡 —— 与旧 App 的 `runPromptCommand` 行为一致 |
| 重载（`/reload`） | 已接 | `session.reload`，**不再需要重启进程**（旧 `PiEngineHost.restart` 的那段理由消失） |
| 扩展 CLI 标志（`--flag`） | 已接 | `session.open.flags`；查扩展声明了哪些：`sdk.call extensionRunner getFlags` |
| 扩展的完整 UI（对话框、widget 行/组件、footer、header、`custom()`、通知、状态、标题、编辑器文本） | 已接 | `ui.*`；组件以「表面」呈现（ANSI 行），见 ui-design.md |
| 扩展事件总线 | 已接 | `bus.*` |
| 扩展注册的消息渲染器 | 已接 | `render.message` |
| 包：列出 / 检查更新 | 已接 | `packages.list` / `packages.updates` |
| 包：安装 / 卸载 / 更新 | **已接** | `packages.install/remove/update`，进度 `packages.progress`，同时只允许一个操作；装完不自动重载，由 App 调 `session.reload`。以下为旧说明： `sdk.call packageManager install/remove/update`；进度回调（`setProgressCallback`）没映射，**待接**一条 `packages.install` 命令带进度事件 |
| 资源开关（启用/停用某个扩展、技能、模板、主题） | **已接** | `resources.list/toggle`（原 `pi-resources.mjs` 的逻辑进了宿主；清单 `resolve(onMissing→skip)`，开关复用 `pi config` 的 `ConfigSelectorComponent`，写出的规则与终端一致）。以下为旧说明： 现在靠 `pi-resources.mjs`。迁移：`packageManager.resolve()` + 设置的 `extensions/skills/prompts/themes` 过滤项，逻辑照抄 `pi-resources.mjs` 但改成直接调 SDK |
| 扩展里自己的 `registerProvider` / 虚拟模型 | 已接 | 在 SDK 内部生效，`model.list` 能看到 |

## 4. 会话、分叉、分支、压缩

| 能力 | 状态 | 命令 |
|---|---|---|
| 新建 / 恢复 / 切换 | 已接 | `session.new` / `session.open{session}` / `session.switch` |
| 列会话 | 已接 | `session.list`（本目录或全部） |
| 分叉（从某条用户消息） | 已接 | `session.forkMessages` + `session.fork` |
| 克隆 | 已接 | `session.clone` |
| 会话树、条目、叶子 | 已接 | `session.tree` / `session.entries` |
| **树导航 + 摘要**（`/tree`） | 已接 | `session.navigate`。**旧 App 靠 pi-android-bridge 扩展绕路，现在是直接方法** |
| 标签 | 通用口 | `sdk.call sessionManager` 的标签方法 |
| 导入 JSONL | 已接 | `session.import` |
| 导出 HTML / JSONL | 已接 / 通用口 | `session.export`（HTML）；JSONL：`sdk.call session exportToJsonl` |
| 手动压缩（可带指令） | 已接 | `session.compact` |
| 自动压缩开关 / 自动重试开关 / 中止重试 | 已接 | `session.autoCompaction` / `session.retry` |
| 压缩 / 分支摘要的设置项 | 通用口 | `sdk.call settings …` |
| 会话改名 | 已接 | `session.name` |
| 会话统计 / 上下文用量 | 已接 | `session.stats` / `session.snapshot.contextUsage` |
| 项目信任 | 已接 | `project.trust.required` 事件 + `project.trust` |
| `/share`（上传会话得链接） | 待评估 | 会把会话内容发到外部服务，默认不接；要接前先确认隐私边界 |

## 5. 模型、Provider、思考等级

| 能力 | 状态 | 说明 |
|---|---|---|
| 列模型 / 设置 / 循环 | 已接 | `model.list/set/cycle` |
| 思考等级 | 已接 | `thinking.levels/set/cycle` |
| 范围模型（scoped models） | 通用口 | `sdk.call session setScopedModels` |
| 虚拟模型、自定义 Provider（`models.json`） | 已接 | SDK 内部；`modelRuntime` 目标可读 |
| Provider 认证状态 | **已接** | `auth.providers`（含每个供应商支持的认证方式）/`auth.credentials`。以下为旧说明： `sdk.call modelRuntime getProviderAuthStatus / listCredentials / hasConfiguredAuth` |
| API Key 设置 / 删除 | **已接** | `auth.setApiKey`/`auth.removeApiKey`（走 `login("api_key")` / `logout`，写 auth.json）。**不要用** `setRuntimeApiKey`：实测只改内存，重启即失。以下为旧说明： `sdk.call modelRuntime setRuntimeApiKey / removeRuntimeApiKey`（注意：命名是 *runtime* key，是否持久化要看 SDK 语义，接之前读 `model-runtime.d.ts` 注释） |
| OAuth 登录 / 退出 | **已接** | `auth.login`/`auth.cancel`/`auth.logout`：`interaction.prompt`→`req auth.prompt`，`notify`→`auth.event`；必须带 `getDeviceId`（ChatGPT 订阅登录没有它直接报错，宿主已按官方 TUI 传 `settings.getOrCreateDeviceId()`）。以下为旧说明： `modelRuntime.login(providerId, type, interaction, options)`；`interaction`（打开 URL、让用户粘贴 code）映射成宿主的 `req`/`evt`。现在靠 `pi-oauth.mjs` |
| 模型列表刷新 | **已接** | `auth.refreshModels`。以下为旧说明： `sdk.call modelRuntime refresh` |

## 6. 设置、主题、其他

| 能力 | 状态 | 说明 |
|---|---|---|
| 设置读写 | **已接** | `settings.get/set/reload`：`set{method,params}` 调会话正在用的 `SettingsManager.setXxx` 并落盘，当前会话立即生效；只放行真实存在且以 `set` 开头的方法。以下为旧说明： `sdk.call settings …`。App 现在直接读写 `settings.json`，**迁移期两边并存没问题**（同一文件），之后逐页改成走宿主，好处是 pi 的校验和 `projectTrusted` 过滤一致 |
| 主题 | 不适用 | pi 的主题是终端配色；App 自己的主题通过 `ui.palette` 喂给扩展组件 |
| 快捷键、`/hotkeys` | 不适用 | |
| `/changelog` `/bug` `/quit` | 不适用 / 待评估 | App 有自己的版本说明；`/bug` 会上传，同 `/share` |
| 离线模式 | 通用口 | 启动环境变量 `PI_OFFLINE`（见 `PiLaunchOptions` 的 KDoc：**必须省略而不是设成 0**） |

## 已知缺口（宿主做不到或还没做，不能假装有）

| 缺口 | 影响 | 处理 |
|---|---|---|
| **HTTP 代理 / 空闲超时**：pi 的 `main()` 调 `configureHttpDispatcher()`，SDK 不导出 | 设置里的 `httpProxy`、`httpIdleTimeout` 在宿主里不生效 | 先向上游提需求导出；临时方案：宿主内用 pi 依赖里的 `undici` 自己配 `setGlobalDispatcher`（要先确认 undici 在引擎包里可解析） |
| **启动迁移**：`runMigrations()` 不导出 | 用户把 agentDir 从很老的 pi 版本带过来时，旧格式不被迁移 | App 的 agentDir 由 App 管，影响面小；升级 pi 的大版本时在 upgrading.md 里手动核一遍迁移说明 |
| **`llama.cpp` 内置扩展**：CLI 内置，SDK 不导出工厂 | `/llama` 命令不可用 | 需要时 `import(dist/extensions/index.js)`（未打包模块图，慢）取 `builtInExtensions`，仅在用户启用本地模型时懒加载 |
| 关机时跟踪的 detached 子进程清理（`killTrackedDetachedChildren` 不导出） | 退出时正在跑的 bash 子进程可能残留 | Kotlin 侧已有 `GuestTreeReaper` 收尾；保持它 |
| `onTerminalInput` 原始按键、`setEditorComponent` | 扩展里依赖它们的功能不可用 | 前者降级为「输入框文本变化回调」，后者只通知 App；见 ui-design.md |
| 子代理一等公民 UI | 未做 | migration.md 步骤 6 |
