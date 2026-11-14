# SDK 覆盖审计（宿主 / App 两层）

对照 `@earendil-works/pi-coding-agent` 1.5.0 的 `AgentSession`、`SessionManager`、`SettingsManager`、
`ModelRuntime`、`ExtensionRunner` 公开方法逐项核对。**宿主**列 = 有无命令；**App**列 = 有无界面 / 数据层。
没验证的写「未验证」，不写「已完成」。

## AgentSession

| SDK | 宿主命令 | App |
|---|---|---|
| prompt / steer / followUp / abort / clearQueue | `session.prompt/steer/followUp/abort/clearQueue`、`chat.send/stop` | 有 |
| setModel / cycleModel / thinking | `model.*` `thinking.*` | 有 |
| scopedModels get/set | `models.scoped` `models.setScoped` | **无界面** |
| cacheWarming 模式 / 状态 | `cache.status` `cache.setMode` | **无界面** |
| compact / abortCompaction | `session.compact` `session.abortCompaction` | 压缩有；中止无 |
| navigateTree / abortBranchSummary | `session.navigate` `session.abortBranchSummary` | 跳转有；中止无 |
| retry / abortRetry / autoRetry | `session.retry` + `sdk.call` | 有 |
| executeBash / abortBash / recordBashResult | `session.bash` `session.abortBash` | 有 |
| setSessionName | `session.name` `session.rename` | 有 |
| fork / clone / getUserMessagesForForking | `session.fork/clone/forkMessages` | 有 |
| exportToHtml / exportToJsonl | `session.export` `session.exportJsonl` | HTML 有；JSONL 无 |
| summarizeForBugReport | `session.bugReport` `session.bugReportCancel` | **无界面** |
| systemPrompt | `session.systemPrompt` | **无界面** |
| routedModel | 快照 `routedModel` | **无界面** |
| getSteeringMessages / getFollowUpMessages | `queue.messages` | 队列条有；内容预览无 |
| getAllTools / setActiveToolsByName / callable | `catalog.tools` `catalog.setTools` `tools.callable` | 开关有；详情无 |
| getContextUsage / getSessionStats | 快照 | 有 |
| steeringMode / followUpMode | `queue.steeringMode/followUpMode` | 有 |
| reload | `session.reload` | 有 |

## 对话页消息长按（整条消息一个长按 → 底部操作面，触感反馈）
| 动作 | 用户消息 | 助手消息 | SDK 对应 |
|---|---|---|---|
| 复制全部 / 选择文字 / 引用到输入框 | ✓ | ✓ | 纯 App（选择文字 = 面内整条 Markdown 原文的系统选择） |
| 编辑并从此分叉 | ✓ | — | `session.fork`（pi 只允许从用户消息分叉） |
| 从这里继续（开新分支） | ✓ | ✓ | `session.navigate`（不摘要） |
| 加标签 / 清除 | ✓ | ✓ | `session.label`（`appendLabelChange`） |
| 从模型上下文隐藏 | ✓ | ✓ | `session.contextEdit`（`appendContextEdit`） |
生成中只给前三项（宿主会拒绝改会话树）。行键对不上 pi 条目 id 时（本轮实时生成的行）按「角色 + 正文，最新优先」
回查（`MessageEntryResolver`，有 harness）；第一轮回答结束前 pi 尚未落盘，会明说「还没写进会话文件」。
**未做**：重新生成（SDK 没有这个方法，要靠「从此分叉再发」模拟，没接）；工具卡仍只有复制类动作（已改用同样的底部面板）。

## 对话页其余交互
- **错误卡「重试」**：只在末尾那张错误卡上、pi 空闲时出现。SDK 没有「立刻重试」（`session.retry` 只管自动重试开关与中止；
  它内部是摘掉出错消息再 `agent.continue()`，不对外），所以做法是把最后一条用户消息（文字+图片）再发一次；
  出错的助手消息发给模型前会被 pi-ai 丢掉，上下文与第一次一致，代价是会话里多一条重复的用户消息。
- **命令面板重做为协议驱动**：宿主的 `catalog.commands` 除扩展/模板/技能外，还带 pi 自己的 `BUILTIN_SLASH_COMMANDS`
  （`builtins`：名字/说明/参数提示；从 `dist/core/slash-commands.js` 按路径读，读不到带 `builtinsError`，App 退回自己映射表的顺序）。
  App 不再手抄 pi 的命令表，只维护 `CLIENT_BUILTINS`（pi 命令名 → 手机上怎么做 + 中文说明）。pi 的每个内建命令一行：
  有映射就是可执行行（settings/model/tree/thinking/scoped-models/export/import/bug/copy/name/session/fork/clone/trust/login/logout/
  new/compact/resume/reload），没有（share/changelog/hotkeys/quit，以及 pi 以后新增的）显示为「不支持」行，点一下说原因。
  `/bug` 走 `session.bugReport`，成文后复制到剪贴板；`/reload` 走 `session.reload`；scoped-models/trust/login/logout 跳设置并说明落在哪页。
  合约测试（`tools/pi-contract.mjs`）改为只盯两件事：映射表里的名字 pi 还有；宿主确实在读 pi 的那张表。
- 队列条已经显示每条排队消息的文字并能「收回并编辑」（此前审计表写「无内容预览」是过期的）。

## SessionManager（见 `sessions.md`）
list / listAll / open / getTree / getBranch / appendSessionInfo / appendLabelChange / appendContextEdit：
宿主全部有（`session.list/search/read/rename/delete/label/path/contextEdit/tree/entries`）；
`list` 的进度与取消、`forkFrom` **未接**。

## SettingsManager
- 全部 `setXxx` 经 `settings.set {method, params}`（宿主校验方法名、flush、返回诊断）；读 `settings.get`。**宿主已覆盖全部**。
- App 侧设置页是否覆盖每一项：**未逐项核对**（见 `ui-redesign.md` 设置页）。

## ModelRuntime / 认证 / 模型
- 登录 / 登出 / API key / 凭据列表 / 刷新目录：`auth.*`。
- **自定义供应商 `models.json`**：`models.config.get/set/setProvider/removeProvider`（SDK 当校验器，非法回滚）。
  **已完成**：旧 `ModelProviderScreen` 及其整簇（扫描、预设、官方目录读取、OAuth 宿主、凭据服务、重启标记）已删除，
  新页面是 `ui/models/ModelsScreen`（经 `HostAuth` / `HostAdvanced`）；表单模型 `models/ProviderForm` 有纯 JVM 的 harness。
- 虚拟模型（`registerVirtualModel`）：由扩展注册，宿主不额外接；`routedModel` 已在快照里。

## 扩展
- 命令 / 工具 / 消息渲染器：`catalog.commands/tools`、`render.message`。
- 扩展 flag：`catalog.flags` `catalog.setFlag`（启动值走 `session.open {flags}`）。
- 命令 / 快捷键诊断：`catalog.diagnostics`。
- UI 上下文（select / confirm / input / editor / notify / status / widget / custom 组件 / 面板）：`ui.*`，已有。
- 事件总线：`bus.*`。子代理：`subagent.rpc`。

## 包 / 资源 / MCP / 信任
`packages.*`、`resources.*`、`mcp.*`、`project.trust`：宿主有，测试覆盖；界面按 `ui-redesign.md` 重做中。

## 故意不接
- 键位 / 终端相关设置（`keybindings`、`tuiMode`、`fullscreen*`、`editorPaddingX`…）：手机用不上。它们仍可经 `settings.set` 写，只是界面不暴露。
- 遥测 / 崩溃日志上传（`telemetry`、`bug-report-upload`）：不替用户上传；问题报告只在本地生成文本，由用户决定怎么发。
