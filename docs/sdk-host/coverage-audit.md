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
  旧 `ModelProviderScreen`（约 2950 行）里手写读写与校验的那部分应整体删除，改调这些命令。
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
