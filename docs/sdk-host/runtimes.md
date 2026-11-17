# proot 与 proroot

两个运行时都是「在 guest 里启动 `node …`」的容器，App 用 `RuntimeSelection.of(...)` 决定走哪个，用户可自由切换。
**宿主对两者一视同仁**：它只用 Node 标准库和 pi SDK，不碰容器的任何特性。区别只在 Kotlin 启动命令里，且已经由现有代码处理。

## 接入点（只有一处）

`app/src/main/kotlin/app/pi/engine/PiEngineHost.kt` 约 611–625 行，`guestCommand` 的 `buildString`：

```kotlin
// 旧
append("exec /opt/node/bin/node ").append(cli)            // cli = …/dist/bundle/rpc-entry.js
if (needsModeFlag) append(" --mode rpc")
append(" --session-dir ").append(guestAgentDir).append("/sessions")
append(launch.commandLineSuffix())

// 新
append("exec /opt/node/bin/node /opt/pi/sdk-host/main.mjs")
append(" --cwd ").append(shellQuote(guestWorkspace))
append(" --agent-dir ").append(shellQuote(guestAgentDir))
append(" --session-dir ").append(shellQuote("$guestAgentDir/sessions"))
append(" --pi-dir ").append("$ENGINE_GUEST_ROOT/node_modules/$PI_PACKAGE")   // 必须显式给，见 sdk.mjs
// 不再有 commandLineSuffix()：这些选项改由 session.open 的 JSON 参数携带
```

此外：
1. **安装宿主文件**：仿 `installScandirFix()`，每次启动把 `assets/guest/sdk-host/**`（含子目录）复制到 `<rootfs>/opt/pi/sdk-host/`。
   复制整棵目录树，不是单文件（`PiResources` 的单文件复制器不够用）；用一个版本戳文件避免每次全量写。
2. `EngineEntry.Bundled/Unpacked` 的候选逻辑**删除**（宿主自己决定 `dist/bundle/index.js` 还是 `dist/index.js`）；
   但仍要检查引擎包目录存在，不存在时给「引擎未安装」。
3. 环境变量不变（`PI_CODING_AGENT_DIR`、`PI_CODING_AGENT_SESSION_DIR`、`PI_SKIP_VERSION_CHECK`、`PI_ANDROID_BRIDGE_FILE`…）。

## 两个运行时的差异与宿主的应对

| 问题 | proot | proroot | 宿主 |
|---|---|---|---|
| `scandir(3)` 对被改写路径返回 ENOENT，Node 的 `fs.readdir*` 全挂 | 无此问题 | 有；`ProrootCommand` 在 `scandir-fix.mjs` 存在时用 `NODE_OPTIONS=--import` 注入补丁 | 宿主与 SDK 全部目录读取都走 `fs`，补丁生效。**测试里 `--scandir-fix` 模拟这一点** |
| 工作区 bind 挂载 | 外部工作区需要 `-b` | 同；且内部工作区**不能**加 bind（`mkstemp` 会 ENOENT） | 宿主只用传入的 `--cwd`，不自己建目录结构 |
| 冷启动慢（大量小文件 open/stat） | 慢 | 较快 | 优先打包入口 `dist/bundle/index.js`（模块数从千级降到十几个 chunk） |
| 信号 / 进程树 | `GuestTreeReaper` 收尾 | 同 | 宿主处理 SIGTERM/SIGHUP 后 `dispose` 再退出；残留子进程仍靠 Reaper |
| stdout 管道 | 同 | 同 | `Transport` 的 EAGAIN 重试对两者都适用 |

## 验证清单（**必须在真机上做，本机只模拟了 scandir 补丁**）

对 proot 和 proroot **各做一遍**，勾完才算过：

- [ ] `host.ready` 在进程启动后 ≤ 1 s 内到达；记录从点「发送」到 `session.open` 应答的耗时。
- [ ] `session.open` 的 `sdkVia` 是 `bundle`（不是 `package`）；若是 `package`，查 `dist/bundle/index.js` 是否被裁剪。
- [ ] 加载一个带 `session_start` 对话框的扩展，`open` 应答不被拖住，`ext.bound` 在对话框回答后到达。
- [ ] 发一条真实 prompt（有凭据），流式输出、工具调用、`agent_settled` 都到达；输出很长的工具（`cat` 大文件）下 App 不卡、宿主内存不涨。
- [ ] `session.new` 后 epoch +1，旧会话的 widget 全部消失。
- [ ] 外部工作区（`/storage/...`）能打开，`read`/`ls` 工具能列目录（proroot 下重点看）。
- [ ] 切换运行时后重启引擎，会话文件能续上。
- [ ] 杀掉 App 后 guest 里没有残留的 `pi-sdk-host` 进程。
