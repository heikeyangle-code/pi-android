# 工作区 = 项目 = 会话的 cwd

对照 pi 源码（`core/agent-session-runtime.js`）：`switchSession` 按会话头里的 cwd 重建 cwd 相关服务，
`newSession` 在当前 cwd 里开。cwd 是**会话的属性**，不是进程的全局设置。

## 已落地
- 宿主：`controller.cwd` 跟着运行时走（state、会话列表、资源、MCP、信任都读它）；`--cwd` 只是第一个会话的默认值。
- `session.new{cwd}`：先写一个只有头的会话文件（与 SDK 的头一致），再 `switchSession`（pi 自己跨项目 /resume 的路径）。
- `session.switch{sessionPath, cwd?}`：透传 `cwdOverride`。
- 项目信任的“表态覆盖”只对表态时的 cwd 有效。
- App：切到内部工作区 / 启动时已挂上的外部工作区 = 热切换（不重启引擎）；引擎启动时把所有已登记外部目录一并 bind。
  只有新登记的外部目录才需要重启一次。

## 还没做（按这个方向继续）
- 工作区页的「正在跑 / 本次改动 / 资源」仍由转录和 guest 命令推出，应改读宿主 `session.entries` / `resources.*`。
- 会话库按 cwd 分组、搜索优先；“项目”入口从会话库出发而不是 `workspace-N` 列表。
- 终端仍把同一目录挂在 `/workspace`（和引擎的拼法不同），需要设备上验证后统一。

## 扩展 / MCP / 资源（已迁到宿主）
- 扩展包页：`packages.list/updates/install/remove/update` + `packages.progress`；装完 `session.reload` 热重载，不再重启引擎。
- 资源：`resources.list/toggle`（官方 `pi config` 的三态），不再在 guest 另起 node 进程。
- 项目信任：宿主返回 `trusted`，表态 `project.trust`；App 里复刻的信任库判据已删。
- MCP：`mcp.servers`（只读配置）、`mcp.list`（真连）、`mcp.login/cancel/logout`（OAuth，可取消、可贴回跳地址）；
  mcp.json 的编辑表单仍由 `PiMcpConfig` 的纯函数写（引擎没起来也能改），生效走 `session.reload`。
- 已删：`PiPackagesScreen/Host/Service`、`pi list` 解析、`TrustFile/TrustRepository/ProjectTrust`、`ExtensionLifecycle`、
  `PiMcpCheck/PiMcpLogin`、`PiResources`+`pi-resources.mjs`、`WorkspaceResources/ProjectResources/PiResourceFacts`（RPC 时代自己扫资源目录）。
