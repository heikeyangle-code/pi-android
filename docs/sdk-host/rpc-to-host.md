# 旧 RPC → 新宿主 对照表

Kotlin 改造时逐行对。左列是旧代码里的名字（`rpc/src/main/kotlin/app/pi/rpc/Commands.kt`、`PiEngineApi.kt`），
右列是新命令。**没有的一行就是「删」**。

## 命令

| 旧（`PiCommands.*` / `PiEngineApi.*`） | 新 | 差异 |
|---|---|---|
| `prompt` | `session.prompt` | 应答语义不变（预检通过即回）；失败码 `rejected`；整轮后的错误走 `agent.error` 事件 |
| `steer` / `follow_up` | `session.steer` / `session.followUp` | 返回 `{disposition}` |
| `abort` | `session.abort` | |
| `clear_queue` | `session.clearQueue` | |
| `new_session` | `session.new` | |
| `get_state` | `session.snapshot` | 超集，带 `epoch`/`seq`/`contextUsage` |
| `get_messages` | `session.messages`（或 snapshot `messages:true`） | |
| `set_model` / `cycle_model` / `get_available_models` | `model.set` / `model.cycle` / `model.list` | |
| `set_thinking_level` / `cycle_thinking_level` / `get_available_thinking_levels` | `thinking.set` / `thinking.cycle` / `thinking.levels` | |
| `set_steering_mode` / `set_follow_up_mode` | `queue.steeringMode` / `queue.followUpMode` | |
| `compact` | `session.compact` | |
| `set_auto_compaction` / `set_auto_retry` / `abort_retry` | `session.autoCompaction` / `session.retry` | |
| `bash` / `abort_bash` | `session.bash` / `session.abortBash` | |
| `get_session_stats` | `session.stats` | |
| `export_html` | `session.export` | |
| `switch_session` / `fork` / `clone` | `session.switch` / `session.fork` / `session.clone` | 会话替换，epoch +1 |
| `get_fork_messages` | `session.forkMessages` | |
| `get_entries` / `get_tree` | `session.entries` / `session.tree` | |
| `get_last_assistant_text` | `session.lastText` | |
| `set_session_name` | `session.name` | |
| `get_commands` | `catalog.commands` | |
| `extension_ui_response`（`extensionUiValue/Confirmed/Cancelled`） | `ui.respond` | 同一个 `id` 配对 |
| 靠 pi-android-bridge 的 `NAVIGATE_COMMAND` 做树导航 | `session.navigate` | **删掉**那个扩展命令和 `NAVIGATE_COMMAND` 相关代码 |
| `pi mcp list --json`（`PiMcpCheck`） | 待接（capabilities §2） | |
| `pi-resources.mjs`（`PiResources`） | 待接（capabilities §3） | |
| `pi-oauth.mjs` | 待接（capabilities §5） | |
| `PiEngineHost.restart`（为了重载） | `session.reload` | 为重载重启进程的逻辑**删除**；重启只剩「引擎崩了」 |

## 事件

| 旧（`PiEvent.*`） | 新 | |
|---|---|---|
| 所有 agent 事件（`agent_start`、`message_update`、`tool_execution_*`、`queue_update`、`compaction_*`、`auto_retry_*`、`agent_settled` …） | `evt agent` 的 `data` | **JSON 形状与旧 RPC 事件行相同**，`Events.kt` 的解析可以直接复用：把 `data` 当成原来的一行 |
| `Response` | `res` 帧 | |
| `extension_error` | `evt ext.error` | |
| `extension_ui_request` method=`select/confirm/input/editor` | `req ui.select/confirm/input/editor` | 多了 `ui.cancel` 收回通知 |
| `extension_ui_request` method=`notify` | `evt ui.notify` | |
| …`setStatus` | `evt ui.status` | |
| …`setWidget`（行） | `evt ui.widget`（`lines`） | |
| …`setWidget`（工厂）、`custom`、`setFooter/Header` | **新**：`ui.widget{surfaceId}` / `ui.surface` / `ui.chrome` | 旧协议里不存在 |
| …`setTitle` / `set_editor_text` | `evt ui.title` / `ui.editorText` | |
| （无） | `session.bound` / `ext.bound` / `project.trust.required` / `bus` / `host.*` | 新 |

## 启动方式

| 旧 | 新 |
|---|---|
| `node /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/rpc-entry.js --session-dir … <PiLaunchOptions.commandLineSuffix()>` | `node /opt/pi/sdk-host/main.mjs --cwd … --agent-dir … --session-dir … --pi-dir /opt/pi/node_modules/@earendil-works/pi-coding-agent`；原来的命令行标志改成 `session.open` 的参数 |
| `PiLaunchOptions`（拼命令行） | 变成 `session.open` 的 JSON 参数：`--no-extensions` 等 → `resources`；`--session-id`/`--session` → `session:{id\|file}`；扩展 `--flag` → `flags` |
| `PiPreSpawnConfig`（环境变量） | 不变：`PI_OFFLINE`、`PI_CACHE_RETENTION` 等仍是进程环境变量，pi SDK 照样读 |
