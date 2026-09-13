# 面账补审：工作区（workspace）自身

这份文件补的是 `docs/surfaces-terminal-workbench-device.md` **没有覆盖**的那一半：那轮审的是
「工作区路径怎么转写」与「`@` 提及跑的是谁」，这一轮审的是**工作区自身的生命周期与语义** ——
它是什么、有几个、谁建它、删了会怎样、里面的项目面 pi 是否真的看得见、文件面边界在哪、
满了怎么办。

判定沿用同一套四态写法（见那份文件的说明）：

- **`pi 有 <file:line>`** —— 事实在 pi 里，我们读它 / 问它 / 照抄它；
- **`pi 无对应物（App 的决定）`** —— pi 没有这个概念，只能由 App 自己定，且必须说得出为什么；
- **`pi 有但我们够不着（卡在哪）`**；
- **一致 / 有意偏离** —— 结论是「不必改」时用它，偏离要写出代价。

行号基准同前：本仓库工作树（2026-09）；pi 为钉住的 `0.85.1`（`tools/fetch-runtime.mjs` 的
`PI_VERSION`，源码树 `/root/pi-src`，`bbb61e34`）。**行号是写作时的**，每条旁边都给符号名；
对不上时按符号搜。`docs/known-gaps.md` 本轮**不动**。

---

## ① 工作区是什么、有几个、能不能换

### ①-1 名字里的 `workspace-1` 是硬编码

| 项 | 真相来源 | 判定 | 结论 |
|---|---|---|---|
| 唯一的工作区目录 | `GuestWorkspacePath.RELATIVE = "pi/workspaces/workspace-1"`（`runtime/GuestWorkspacePath.kt:49`） | **`pi 无对应物（App 的决定）`** | 目录名里的 `-1` 是「本来打算有第二个」的化石：全仓库没有任何代码读它、切换它或枚举同级目录（`grep -rn "workspaces"` 只剩这一处常量与注释） |
| 谁会拿到它 | 所有消费方都读同一个入口 `PtyLauncher.workspaceHost(context)`（`runtime/PtyLauncher.kt:304`），而它现在只做 `GuestWorkspacePath.ensureHost(filesDir)`（见 ②-1） | —— | **一致**：没有第二份拼写（上一轮已合并，见 `docs/surfaces-terminal-workbench-device.md` ②-2） |
| pi 侧对应物 | pi 没有「工作区」概念：它的 cwd 是**进程的 cwd**，`FileSettingsStorage` 按 `resolvePath(cwd)` 求项目设置（`core/settings-manager.ts:228-234`），sessions 按 cwd 分组 / 过滤（`core/session-manager.ts:1675` `-c` 用 `:631-633`），资源按 `join(cwd, CONFIG_DIR_NAME, …)` 发现（见 ③） | **`pi 有`：cwd 就是全部语义** | 把 cwd 固定成一个 App 私有目录**是 App 的决定**，pi 那边没有「工作区」这个东西可以对应 |

### ①-2 用户能不能新建 / 切换 / 重命名工作区

| 项 | 证据 | 判定 |
|---|---|---|
| 设置里有没有「工作区」这一项 | `grep -rn "工作区" app/src/main/kotlin/app/pi/ui` → 只有文案（`WorkbenchScreen.kt:67`、`ChatScreen.kt:828`、`PiSettingsRegistry.kt:373`、`PiRoot.kt:47` 等），没有任何一行的键名或写入 | 无 |
| 有没有目录选择器 | `ActivityResultContracts.OpenDocumentTree()` 只有一处：`ui/device/DeviceCapabilityScreen.kt:130`，写进 `DeviceSafStore`（SAF 授权，设备能力面），**不碰工作区**；`GetContent()` 只有一处：`ui/screens/ChatScreen.kt:283`（图片附件） | 无 |
| `workspaces/` 下能不能有第二个目录 | 没有任何枚举/创建逻辑；`PiSessionViewModel.defaultWorkspace()`（`ui/PiSessionViewModel.kt:754`）是唯一入口 | 无 |

**判定：`pi 无对应物（App 的决定）` —— 一个工作区，且不能换。** 结论是**一致，不必改**，理由如下
（这条要写清楚，因为它是一个会被反复提出来的决定）：

- pi 的语义支持多工作区（每个 cwd 一套 `.pi/`、AGENTS.md、技能、会话），但**手机上收益小**：
  用户没有一个「项目目录树」要在这里复用，App 私有目录也不是用户在文件管理器里整理的地方。
- 代价是真实的、也是用户已经能看到的：**不同项目的项目级状态会混在一个 cwd 下** ——
  `<workspace>/.pi/settings.json`、`.pi/{skills,prompts,themes,extensions}`、`AGENTS.md`、
  `SYSTEM.md`、`pi install -l` 的信任与安装范围、以及（会话面）同一个平铺目录里所有会话。
  会话**没有**混：`PiSessionStore.list()` 不按 cwd 过滤（`session/PiSessionStore.kt:85-109`），
  但每条会话的文件头里都带 `cwd`（`:242-249`），界面按它分组（`ui/screens/SessionsScreen.kt:177-187`），
  而 `-c` 等价物 `maybeResumeLastSession` 用 `mostRecentForResume(guestWorkspace())` 按 cwd 过滤
  （`session/PiSessionStore.kt:135-142`、`ui/PiSessionViewModel.kt:1066-1105`，对应
  `core/session-manager.ts:1589-1598` / `:636-653`）。所以「不同工作区的会话混在一起」**不是缺陷**：
  索引是一个目录，分组靠 cwd，恢复靠 cwd 过滤。**有意偏离**（pi 默认按 cwd 分目录平铺，
  我们用 `--session-dir` 平铺），代价只在于 `sessions/` 里看不出分组，界面里看得见。
- **要不要做多工作区**：能说得出口的收益只有一条 —— 让用户把工作区指向手机里**已有的**项目目录。
  但那要动的东西是连锁的：`PtyLauncher` 的 `-b` 绑定（**设备上已验证，本轮明确不动**）、
  `PiEngineHost` 的 cwd、`GuestWorkspacePath`、`AgentLayout`（每条包命令的绑定）、会话索引的
  cwd 分组、以及设备能力面的写入边界（`DeviceWorkspace.contains`）。这是一次**必须有一次真机
  验证的行为变更**，而本轮不允许动手机。**结论：不做，写成具名决定 + 本文档**；真要做的第一步
  不是加选择器，而是把 ①-1 那个 `-1` 变成一个 App 设置（并让上面五处都从它推导）。

### ①-3 一个工作区带来的、界面上说了谎的地方（已修）

`WorkbenchScreen` 的三段（文件 / Git / 任务）**没有实现**，只给空状态：
`ui/screens/WorkbenchScreen.kt:105-116`。原文案：「把手机里的一个文件夹设为工作区后，文件树会出现
在这里。」—— 这句话承诺了一个不存在的动作（①-2），且空状态标题「没有工作区」与事实相反
（工作区一直在，只是这里没有文件树）。`SessionsScreen.kt:166-167` 有同一句话。

**已修**（见本次改动清单）：两处文案改成陈述现状，不提任何不存在的动作。

### ①-4 没有实现的东西，不装作有（记账）

| 段落 | 现状 | 判定 |
|---|---|---|
| 文件 | 空状态；没有文件树、没有 `open/import/export/read/write` 的文件 UI | **`pi 无对应物（App 的决定）`**：pi 的 TUI 也没有文件浏览器，它是 `@` 补全 + 工具；App 侧只有 `@`（已审） |
| Git | 空状态；没有任何 `git status/diff/log` 调用 | 同上：pi 把 git 当普通命令交给模型，没有「Git 面板」这个概念 |
| 任务 | 空状态；后台任务信息在对话页（`ToolCallBlock` 的「运行中/停止」） | 同上 |
| 终端 | **是这块唯一承重的段落**（`TerminalPane`，见上一轮 ①） | —— |

这条记账的意义：`WorkbenchScreen` 的三段空状态**不构成缺陷**，但它们的文案不能暗示功能存在
（①-3 已修）。**未验证**：设备上逐段打开确认三段都是空状态、没有崩溃路径。

---

## ② 工作区的创建与存在性

### ②-1 谁建它、什么时候建（**本轮的真问题**）

| 路径 | 建不建 | 证据 |
|---|---|---|
| 引擎启动 | **建** | `engine/PiEngineHost.kt:257-259`：`val workspace = workspaceProvider(); workspace.mkdirs()`（绑之前，proot 不绑不存在的源） |
| 终端 | **建** | `runtime/PtyLauncher.kt:143-157`：上一轮补的 `workspace.mkdirs()`，与引擎同源 |
| 设置（任何一页） | 不建（读） | `ui/settings/PiSettingsStack.kt:176-179`、`PiCredentialScreen.kt:128-133`、`PiModelsScreen.kt:104-107` 都只拿 `File` |
| `@` 提及 | 不建（读） | `ui/chat/PiMentionSource.kt:87-96`：`GuestCommand` 用同一套绑定；绑不存在的源 → fd 拿不到列表，静默空 |
| 主题发现 | 不建（读） | `ui/theme/PiThemeFiles.kt:154` |
| TUI-only 扩展扫描 | 不建（读） | `ui/PiSessionViewModel.kt:1868-1871` |
| 设备能力写入边界 | 不建（读） | `bridge/DeviceWorkspace.kt:74-96`：失败时回退到 `FALLBACK_RELATIVE`，**不创建** |
| 包命令 | 不建 workspace，只建 agent dir | `packages/AgentLayout.kt:118` 的 `ensureAgentMirrorDir()` 只保证 agent dir |

`defaultWorkspace()`（`ui/PiSessionViewModel.kt:754`）返回的 `File` **没有 `mkdirs()`**，
而且它自己就是 `File(filesDir, RELATIVE)` 的字符串拼接 —— 目录不存在时没有任何信号。

**判定：`pi 无对应物（App 的决定）`，原来的处置是缺陷。** 后果是具体的三档：

1. **装机后先开设置**：`PiSettingsStack` 是第一个 `workspaceHost` 调用方，装完就撞上；
2. **Android 清理 app 私有存储 / 用户删了目录**：下一次启动所有读侧静默为空 ——
   `@` 没有候选（fd 绑不上源）、项目设置读成默认、项目技能/模板/主题/扩展都不出现；
3. **终端**：proot 不绑不存在的源，用户会看到「进不去 shell」，而引擎那条路径是好的 ——
   两条路径表现不一致，正是上一轮修终端时记下的同一个形状。

**修改**：保证点收敛到**唯一入口** `PtyLauncher.workspaceHost`（`runtime/PtyLauncher.kt:292-304`），
它现在调 `GuestWorkspacePath.ensureHost(filesDir)`（`runtime/GuestWorkspacePath.kt:66-87`）：
幂等的 `mkdirs()`，连中间层 `pi/workspaces` 一起建（那一层原来没有任何人建）。引擎与终端各自
那两行 `mkdirs()` 保留（幂等、且它们要的是「绑之前一定存在」这个局部保证）。

**为什么放在这里而不是各调用点**：`workspaceHost` 已经是「一个 W 的目录」的单一权威
（引擎、终端、`@`、包命令、设备边界全走它，`grep -rn "workspaceHost"` 共 8 个调用方），
在它里面保证存在性，等价于**所有**调用方都拿到一个存在的目录；逐个调用点加是 8 个忘记的机会。
代价：`workspaceHost` 不再是纯函数（一次 `isDirectory`/`mkdirs`），所以 `PtyLauncher` 里
不再有「返回一个不存在的路径」这个可能 —— 这正是要的。

### ②-2 工作区被删掉之后，各条路径分别怎样（改动前 / 改动后）

| 路径 | 删除后（改动前） | 删除后（现在） |
|---|---|---|
| 引擎启动 | 正常（自己 `mkdirs`） | 正常 |
| 终端 | 正常（上一轮已修） | 正常 |
| 会话列表 | 正常，但每条会话的分组标题把 guest 路径打到界面上（见 ②-4） | 正常，标题是人话 |
| `@` 提及 | 目录不存在 → 空列表（静默）；下一次从任意一页拿到 `workspaceHost` 会被重建 | 重建后正常 |
| 项目设置读 | 空（默认值）| 同 |
| 项目设置写 | **陷阱**：`PiSettingsFileStore` 的 `projectCache` 还留着删除前的文档；若某个项目级键曾被读过，下次写它会 `mkdirs()` 把目录建回来，并**把缓存里所有项目键连同新值一起写回** `settings.json` —— 用户删掉的项目覆盖被悄悄复活，从此盖住全局值 | **已修**：写入定位用文件系统事实（`settings/PiSettingsFileStore.kt:96-101`，`projectFile.isFile && lookup(project(), key) != null`），删掉的项目文档与「从来没有过」等价 |
| 包命令 | `pi install -l` 写 `<cwd>/.pi`（`core/package-manager.ts:931`），目录不存在时由 `PiProjectConfig` 一侧的路径推导给出；`TrustRepository` 写 `trust.json` 的键是 guest cwd | 同（`AgentLayout` 的绑定源存在性由 `ensureAgentMirrorDir` 保证） |
| 文件选择（`@`、图片） | `@` 见上；图片附件走 SAF，与工作区无关 | 同 |

**残留（未改，记账）**：`PiSettingsFileStore.project` 的**内存缓存**在文件被外部删除后仍然命中
（`project()` 返回 `projectCache`，`:66-73`），所以删除后、任何一次写入之前，设置页可能还显示
删除前的项目值。改它需要动「缓存与文件系统一致性」这个全局约定（`PiFileWatch` + `invalidate()`
是现有的答案，但只在设置页在屏幕上时装），不在本轮范围。**怎么测**：见未验证项汇总。

### ②-3 「有 Writer 无 Reader」的老形状：还有没有

没有发现新的。本轮确认的是**反方向**：工作区的存在性原来只有两个 Writer、八个 Reader，
现在收敛成一个保证点（②-1）。

### ②-4 会话分组标题把内部 guest 路径打到界面上（已修）

`SessionsScreen` 的分组标题与每行副标题原来都是 `shortenPath(summary.cwd)`（旧 `:186`、`:325`），
而 `cwd` 是**引擎的 guest 路径**：App 自己的会话是 `/workspace/pi/workspaces/workspace-1`
（`GuestWorkspacePath.under`），终端里手敲 `pi` 起的会话是 `/root`
（`PtyLauncher.kt:164` 把终端 cwd 设为 `/root`，会话落进同一个 `sessions/`）。
用户看到的是 `/workspace/pi/works…` 这种字符串 —— 既是上一轮的挂载点差异在界面上的投影，
也违反了「用户可见文案不出现文件路径」。

**已修**：`groupLabel()`（`ui/screens/SessionsScreen.kt:366-378`）：App 自己的工作区显示成
「工作区」，其它目录显示最后一段（`/root` → `root`），空 cwd 显示「工作目录未记录」。
代价写在函数 KDoc 里：同名目录的两组标题会长得一样 —— 比一条没人能操作的路径好。

同类两处**一并修**：导出确认原来的 `"会话已导出：${hostPath.absolutePath}"`
（`ui/PiSessionViewModel.kt:2430`、`:2489`）打印的是 app 私有存储的绝对路径 ——
`/data/user/0/app.pi/files/pi/workspaces/workspace-1/…`，用户在文件管理器里打不开，
信息量为零。现在只报文件名；只有当 App 找不到自己写出的文件时才回退到 pi 的响应原文
（`$written`，那是 guest 拼法，属于「说清楚去哪了」比「什么都不说」好的例外）。

---

## ③ 工作区里的「项目面」是否真的被 pi 看见

### ③-1 逐项对照（App 读的路径 vs pi 从 cwd 发现的路径）

pi 的所有项目面路径都是 `join(cwd, CONFIG_DIR_NAME, …)`，`CONFIG_DIR_NAME` 来自**引擎自己的
`package.json`**（`config.ts:504`：`pkg.piConfig?.configDir || ".pi"`，钉住的引擎声明 `.pi`）。

| 项目面 | pi 的真身 | App 读/写的地方 | 判定 |
|---|---|---|---|
| 项目设置 | `core/settings-manager.ts:233` → `<cwd>/.pi/settings.json`（`:350-361` 也是同一对路径） | `PiSettingsFileStore.forWorkspace` → `PiProjectConfig.settingsFile(workspace)`（`settings/PiSettingsFileStore.kt:170-177`）；`PiCredentialService.inventory` 同（`:130`） | **一致**（再核一遍：一致，且现在两处同一常量） |
| 项目设置（第二读者） | 同上 | `PiModelInventory.selection` 收 `projectSettings` 原文 | 一致 |
| 技能 | `core/skills.ts:454` → `<cwd>/.pi/skills` | `PiResourceDiscovery.discover(PiProjectConfig.root(…))` 的 `Kind.Skills`（`packages/PiResourceDiscovery.kt:49-53`） | **一致**（名字从同一常量来） |
| 提示模板 | `core/prompt-templates.ts:203` → `<cwd>/.pi/prompts` | 同上 `Kind.Prompts` | 一致 |
| 主题 | `core/resource-loader.ts:875` → `<cwd>/.pi/themes` | `PiThemeFiles.discover` → `PiProjectConfig.themesDir(workspace)`（`ui/theme/PiThemeFiles.kt:154`）；`PiThemeScope.Project` 的标签也由常量拼（`:54`） | 一致 |
| 扩展 | `core/extensions/loader.ts:783` → `<cwd>/.pi/extensions`（受信任门控） | `PiSessionViewModel.scanTuiOnlyExtensions` → `PiProjectConfig.extensionsDir(defaultWorkspace())`（`ui/PiSessionViewModel.kt:1871`） | 一致 |
| `SYSTEM.md` / `APPEND_SYSTEM.md` | `core/resource-loader.ts:1024`、`:1038` | **App 不读**（pi 自己读，我们不需要列出来） | 一致（无对应 UI） |
| `AGENTS.md` / `AGENTS.override.md` / `CLAUDE.md` | `core/resource-loader.ts:72` 的候选表 + 从 cwd **向上逐级**加载（`:114-160`） | **App 不读** | 一致（无对应 UI；`app.runtime.noContextFiles` 是 pi 的开关，见设置注册表） |
| `.git` | pi 不特殊处理（git 是普通命令/`git:` 包源） | 无 | 一致 |
| 包安装的项目范围 | `core/package-manager.ts:931` → `<cwd>/.pi` | `AgentLayout.hostProjectConfigDir()` = `PiProjectConfig.root(hostWorkspace)`（`packages/AgentLayout.kt:127-136`；**这行的 KDoc 原来写的是 `settings.json`，改掉了**） | 一致 |
| `trust.json` 的键 | `<cwd>` 的 canonical 形式（`core/project-trust.ts:25` 的提示文案、`ProjectTrust` 复刻） | `TrustRepository`：guest cwd（`packages/TrustRepository.kt:303` 的 `"$guestCwd/${ProjectTrust.CONFIG_DIR_NAME}"`） | 一致（且 `CONFIG_DIR_NAME` 现在也指向同一常量） |
| 需要信任的项目条目表 | `trust-manager.ts` 的 entry 列表 | `ProjectTrust.TRUST_REQUIRING_CONFIG_ENTRIES`（`packages/ProjectTrust.kt:37-47`：settings.json/extensions/skills/prompts/themes/SYSTEM.md/APPEND_SYSTEM.md） | 一致（App 自己列，理由见 `ProjectTrust` 头注释：RPC 无 UI 时 pi 静默跳过） |

### ③-2 本轮的真问题：`.pi` 这个**字面量**在 7 个地方各写一遍

`CONFIG_DIR_NAME` 不是 RPC 通道能问到的（pi 的设置面是「文件、不是 schema」），所以名字只能
**转写**。改动前它出现在：`PiSettingsFileStore`、`PiCredentialService`（两处）、
`PiSessionViewModel`、`PiThemeFiles`（两处）、`PiSettingsStack`、`AgentLayout`、
`ProjectTrust`（第三个同名常量！）。

**判定：`pi 有 config.ts:504` + `pi 无对应物（问不到）`。** 7 份转写里任何一份与引擎的
`package.json` 不一致，后果是**一整个 pi 从不打开的目录**：设置页显示默认值、项目技能/模板/
主题/扩展都不出现、项目级 `pi install -l` 报成功而 pi 看不见 —— 静默、且看起来像「本来就没有」。
这正是 §M12 的形状，只是低一层。

**修改**：
1. 新增 `runtime/PiProjectConfig.kt`（Android-free 纯对象）：一个 `DIRECTORY` 常量 +
   `root/themesDir/skillsDir/promptsDir/extensionsDir/settingsFile/under`。
2. **7 处全部改为读它**（清单见文末），`ProjectTrust.CONFIG_DIR_NAME` 也改成指向它。
3. `tools/pi-contract.mjs` 新增 `checkProjectConfigDir(piDir)`：把 Kotlin 常量与**钉住的引擎
   `package.json` 的 `piConfig.configDir`** 比对（默认 `.pi`）。这是「能被 pi 验证的表」，
   按 `docs/pi-sourced-lists.md` 的规则就该有断言。
4. `AgentToolPathsCheck.kt` 第 6 节钉住形状（根、`settings.json`、`themes`、三个资源目录）
   与 `under()` 的边界规则。

### ③-3 会话与 cwd 的关系

| 项 | 真相来源 | 我们 | 判定 |
|---|---|---|---|
| pi 默认会话布局 | `join(agentDir, "sessions", "--<cwd 转写>--")`（`core/session-manager.ts:473-486`） | 不用（我们传 `--session-dir`） | 有意偏离 |
| 显式 session dir 的布局 | `SessionManager.create` 只在没有 sessionDir 时才回落按 cwd 分目录（`:1551-1552`），文件名 `join(getSessionDir(), …)`（`:947-949`） | `PiEngineHost.kt:275` 传 `--session-dir <guestAgentDir>/sessions`，平铺写 | **有意偏离，代价见下** |
| `-c` 的语义 | `findMostRecentSession(sessionDir, cwd)`（`:1589-1598`、`:636-653`），按 **mtime** 排序并 `sessionCwdMatches`（`:631-633`）过滤 header 的 cwd | `PiSessionStore.mostRecentForResume(cwd)` + `maybeResumeLastSession` 传 `guestWorkspace()` | **一致**（逐字复刻，harness `sessions` 钉住） |
| 列表的语义 | `SessionManager.list` 有 `filterCwd`（`:1675`），桌面 picker 是「所有项目」 | `list()` **不**过滤 cwd，界面按 cwd 分组 | 一致（我们读的是「所有项目」那一半） |

**「不同工作区的会话混在一起」的结论**：**不会**。索引是一个目录（平铺），但 cwd 是每条会话
文件头里的字段，恢复按它过滤、列表按它分组。**代价只有一条**：终端里手敲 `pi` 起的会话
（cwd `/root`）和 App 自己的会话在同一个 `sessions/` 里，界面里是多一组、
恢复时 `maybeResumeLastSession` 会把它过滤掉（`ui/PiSessionViewModel.kt:1079-1083` 的
KDoc 已经写明这一点）。**未验证**：见汇总表。

---

## ④ 工作区的文件面

### ④-1 `WorkbenchScreen` 到底提供什么

| 能力 | 有没有 | 证据 |
|---|---|---|
| 浏览（文件树） | **没有** | `WorkbenchScreen.kt:105-116` 空状态 |
| 打开 / 导入设备文件 | **没有**（对话页的图片附件是 SAF，见 ④-2） | —— |
| 导出 | **没有 UI**；导出入口在对话页 overflow（`ChatScreen.kt:714`） | `PiSessionViewModel.exportSession` / `exportJsonl` |
| 读写工作区文件 | **没有 UI**；读写发生在引擎侧（模型工具）与终端里 | —— |
| `@` 提及列出工作区文件 | 有（已审） | `PiMentionSource` → `fd` |

### ④-2 上传 / 图片附件 / 导出的落点

| 流程 | 落点 | 判定 |
|---|---|---|
| 图片附件 | **不落盘**：SAF `GetContent()` 读字节 → base64 内联进 `prompt`/`steer`/`follow_up` 的 `images`（`ui/screens/ChatScreen.kt:283-320`，上限 `MAX_ATTACHMENT_BYTES = 8 MiB`，`:1694`；wire 形状 `packages/ai/src/types.ts:367-371`） | **一致**：`docs/rpc.md` 的 images 就是内联 base64，没有路径字段 |
| markdown 里的图片链接 | `GuestImageBytes`（`bridge/GuestImageBytes.kt`）把 guest 路径读成字节；候选顺序由 `GuestPathMapping` 定（harness `guest-paths` 钉住） | **`pi 有但我们够不着`/未接线**：该文件头注释自述 **NOT WIRED YET**，`ui/render` 两行没人接 |
| `/export <路径>` → HTML | 显式路径 `"${guestWorkspace()}/$htmlName"` 交给 `export_html`，pi 写进工作区；App 再核对 `File(defaultWorkspace(), htmlName)` | 一致（同一条 cwd 的两侧拼法），且**现在不存在工作区时也能写出**（②-1） |
| `/export <路径>` → JSONL | App 自己写 `File(defaultWorkspace(), fileName)`（`ui/PiSessionViewModel.kt:2486-2488`） | 一致 |
| 会话文件读写 | `<agentDir>/sessions`（不是工作区） | 一致：会话归 agent dir，工作区归项目面 |
| `@` 提及的绝对路径 | pi 允许 `@/abs/path` 与 `@~/…`（`autocomplete.ts:537-543`）；我们照抄（`PiFileMentions.kt` 头注释第 3 条），fd 在 guest 里跑 | **一致**：客人能列工作区之外，这是 parity，不是新开口 |

**结论**：没有发现「看起来在工作区、实际在别处」的路径。唯一边界含糊的一处是设备能力面的
`/app/files/*`（SAF 授权目录）与工作区（app 私有目录）**互不相交**，这**是对的**：
`DeviceWorkspace` 的 KDoc 说明它只做设备 shell 的写入边界，且**故意不接受** guest 拼法的
`/workspace`（`bridge/DeviceWorkspace.kt:41-46`：设备 shell 在宿主命名空间里，`/workspace`
不存在，接受它等于对一条必然 ENOENT 的命令说「允许」）。两套目录各由一套授权管，
没有重叠；**唯一要说清的是「模型能写哪里」有两个答案**（SAF 目录 / 工作区），
`/app/health` 的 `workspace.guestPath(+aliases)` 与 `saf` 两段都如实报告（`DeviceBridgeRouter.kt:335-346`、`:358`）。

### ④-3 路径边界

| 项 | 边界 | 证据 |
|---|---|---|
| `exportJsonl` 的目标 | `File(defaultWorkspace(), fileName)` —— `fileName` 来自 UI 输入，**没有做包含性校验**：`../` 可以写到工作区之外 | `ui/PiSessionViewModel.kt:2486`。风险有限（值来自本机用户，写的是 app 私有/同级目录），但它是这一面**唯一**一个把外部字符串接到路径上的地方；`PiProjectConfig.under` 现在提供了校验器（④-4），**没有**改这里（文件名不是 `.pi` 内的相对路径，套用它反而会改行为） |
| 会话删除 | `PiSessionStore.delete` 有包含性守门（只有 `sessionsRoot` 内的 `.jsonl`） | `session/PiSessionStore.kt:161-167` |
| 设置写入 | 三个固定文件（global/project/app-local），键名不参与路径 | `PiSettingsFileStore` |
| `.pi` 内的一切 | 现在有一个受控入口 `PiProjectConfig.under(workspace, relative)`：`..`/`.`/空段拒绝、`\` 归一、绝对路径**被包含**（`/etc/passwd` → `<ws>/.pi/etc/passwd`，不是逃逸） | `runtime/PiProjectConfig.kt:69-84`；**今天无调用方**（所有调用点传字面量），存在的意义是下一个「用户/模型给的名字」不要再走 `File(workspace, "…")` |

---

## ⑤ 配额与失败

| 项 | 现状 | 判定 |
|---|---|---|
| 位置 | app 私有存储（`<filesDir>/pi/workspaces/workspace-1`，而 `<filesDir>` 在 `/data/data/app.pi/files`）；**不是** `/sdcard`。理由写在 `ui/PiSessionViewModel.kt:747-754`（速度：`/sdcard` 走 FUSE） | **`pi 无对应物（App 的决定）`**，代价：用户在系统文件管理器里看不到、SAF 目录选择器也选不到 |
| 系统清理 / 用户删除 | 见 ②-2 全表；现在所有入口都会重建（②-1） | 已修 |
| 存储满 | `ensureHost` 的 `mkdirs()` 失败**不抛异常**（返回的 `File` 仍然不保证存在）；读侧各路径本就「不存在 → 空/默认值」；写侧 `SettingsFileStore.writeDocument` 的 `writeText` 会抛，由设置页的错误处理接住（`grep` 到的写路径都在 `runCatching` 或 UI 的 try 里）；`exportJsonl` 的 `writeText` 在 `call{}` 的异常路径里 | **一致（尽力而为）**：没有任何地方假设「目录一定存在」而崩溃；代价是**磁盘满时可能静默**（`@` 空列表、设置不保存），**未验证** |
| 「目录一定存在」的假设残留 | `bridge/DeviceWorkspace.refresh` 失败时用 `FALLBACK_RELATIVE` 继续算边界（`DeviceWorkspace.kt:74-77`）——它**不创建**目录，但会把一个不存在的路径报成写入边界的「已知」状态（`isKnown()` 由 aliases 非空决定，`:106`） | **有意保留**：边界是授权判断，不是文件操作；`summary()` 里写的是路径本身。**未验证**：设备上删掉工作区后打开设备能力页，看它是否仍显示旧路径（预期：是） |
| 权限 | app 私有目录不需要任何运行时权限 | 一致 |

---

## 本次改动清单（全部可 `grep` 到）

| 文件 | 改动 | 依据 |
|---|---|---|
| `runtime/PiProjectConfig.kt` | **新增**：pi 项目配置目录的唯一转写（`DIRECTORY`）+ 路径推导 + `under()` 边界校验（Android-free） | ③-2 |
| `runtime/GuestWorkspacePath.kt:66-87` | 新增 `ensureHost()`：幂等 `mkdirs()`，连中间层一起建 | ②-1 |
| `runtime/PtyLauncher.kt:292-304` | `workspaceHost` 改为 `ensureHost` —— 保证点收敛到唯一入口 | ②-1 |
| `settings/PiSettingsFileStore.kt:96-101` | 写入定位改为「文件系统事实」而非缓存 | ②-2 |
| `settings/PiSettingsFileStore.kt:170-177` | 项目设置路径改为 `PiProjectConfig.settingsFile` | ③-2 |
| `packages/PiCredentialService.kt:130`、`:154` | 同上（读设置、监视目录） | ③-2 |
| `packages/ProjectTrust.kt:52-61` | `CONFIG_DIR_NAME` 指向 `PiProjectConfig.DIRECTORY`（原来是**第三个** `.pi` 字面量） | ③-2 |
| `packages/AgentLayout.kt:127-136` | `hostProjectConfigDir()` 改为委托；**改掉一句错的 KDoc**（说返回 `settings.json`，实际是目录） | ③-1 |
| `ui/PiSessionViewModel.kt:1871` | 项目扩展目录改为委托 | ③-2 |
| `ui/theme/PiThemeFiles.kt:54`、`:154` | 项目主题目录与标签改为委托 | ③-2 |
| `ui/settings/PiSettingsStack.kt:179` | 监视目录改为委托 | ③-2 |
| `ui/screens/SessionsScreen.kt:177-187`、`:326`、`:366-378` | 会话分组改用 `groupLabel()`：不再把内部 guest 路径打到界面 | ②-4 |
| `ui/PiSessionViewModel.kt:2429-2432`、`:2489` | 导出确认只报文件名 | ②-4 |
| `ui/screens/SessionsScreen.kt:166`、`ui/screens/WorkbenchScreen.kt:106` | 文案：不再承诺「把手机里的文件夹设为工作区」 | ①-3 |
| `app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt` | 第 6 节：项目配置目录的形状 + `under()` 边界规则 | ③-2 |
| `tools/pi-contract.mjs` | 新增 `checkProjectConfigDir`：Kotlin 常量 vs 钉住引擎的 `piConfig.configDir` | ③-2 |
| `tools/run-app-pure-checks.sh` | `agent-tool-paths` 加 `PiProjectConfig.kt`；`packages` 加同一个文件（`ProjectTrust` 现在依赖它） | 同上 |

**没有改**：pi（`/root/pi-src` 只读）、载荷（`assets/runtime/**`、`runtime.lock.json`）、
`docs/known-gaps.md`、任何 git 写操作、Gradle；**没有动**终端的 `-b` 绑定与
`GuestWorkspacePath.TERMINAL_GUEST_PATH`（上一轮明确留下的「不冒险」项）；
**没有实现**多工作区、文件树、Git 面板、任务面板（①-2、①-4 写了理由）。

**写作时的树不止这一条线在动**（同一棵工作树里有并发的改动落地，见行号基准那一段）：
`git status` 里的 `engine/`、`service/`、`ui/screens/BootScreen.kt`、`ui/settings/RuntimeFacts.kt`、
`ui/device/DeviceCapabilityScreen.kt` 以及 `docs/lifecycle-and-timers.md` 的改动**不是本次的**；
上表里的行号已按本轮结束时的树重取一次，符号名都给了，按符号搜即可。

---

## 未验证项汇总（都要设备或 CI）

| 项 | 怎么测 |
|---|---|
| 工作区不存在时的首次启动（②-1） | 装机后**先**进「设置 → 关于/隐私」（随便一页会读工作区），再进工作区；`adb` 或设备 shell 看 `<files>/pi/workspaces/workspace-1` 应已存在；再进对话、`@` 补全应有候选 |
| 删掉工作区后的四档表现（②-2） | 引擎跑着时删掉 `<files>/pi/workspaces/workspace-1` → ①`@` 补全（应空，不崩）；②终端（重新进应能进 shell）；③设置页改一个项目级键（应先出现在设置页，写完后文件应**只在**新位置出现，不复活旧键）；④重启引擎（应正常，`pwd` 是工作区） |
| 项目设置「删了不复活」（②-2 已修的那条） | ①在工作区里放一个项目级覆盖（例如 `theme`）；②**不重启 App**，删掉整个工作区目录；③改同一个键；④看新生成的 `settings.json`：应**只有**这一个键，不含删除前的其它键 |
| 设置页缓存残留（②-2 残留） | 同上，但第③步改成「只打开设置页看值」：预期**仍显示删除前的项目值**（未修），直到 `PiFileWatch` 的 ON_RESUME 或一次写入丢掉缓存。这条决定要不要补一个「文件不在 → 丢缓存」的规则 |
| 会话分组标题（②-4 已修） | 造两条会话：一条 App 对话、一条终端里 `cd /workspace && pi` 起的；会话列表里应显示「工作区」与「root」，**不出现** `/workspace/…` 或 `/root/…` |
| 导出确认（②-4 已修） | 对话页 overflow → 导出会话；确认提示应是文件名（`pi-session-….html`），不是 `/data/user/0/…`；再导出 `.jsonl` 同样 |
| `.pi` 名字与钉住的引擎一致（③-2） | CI 的 `contract` job；本地：`node tools/pi-contract.mjs --pi <0.85.1 安装目录>`，应多一行 `PASS the pinned engine's project config directory …` |
| 项目面被 pi 真的看见（③-1） | 在 App 的「扩展与资源」里放一个项目技能 / 提示模板 / 主题 / 扩展，然后在**对话**里问模型它看见了哪些（或看 `/app/health` 之外的方式：`pi list`）；再在终端里 `cd /workspace && pi` 对照一次。两侧应一致 |
| 取信门控下的项目扩展（③-1，标注为「只报不加载」） | 未信任时：`scanTuiOnlyExtensions` **会**列出 `.pi/extensions` 里的扩展（它读文件，不问信任），而 pi 引擎**不会**加载。界面文案若暗示「已加载」即为缺陷。**未验证**：看「仅终端可用的扩展」列表在未信任时是否列出项目扩展，以及有没有一句「需要信任」 |
| 会话平铺的 cwd 过滤（③-3） | ①终端里 `cd /workspace && pi` 起一个会话并退出；②App 里打开对话、开「恢复上次会话」；③应恢复**工作区 cwd** 的那条，不能是 `root` 那条；④会话列表里两条都应出现，分组不同 |
| `under()` 的边界（④-3） | harness `agent-tool-paths`（本机**不跑**，上 CI；纯函数，无设备依赖） |
| 存储满（⑤） | 设备上把 `files` 分区填满（或 `chmod 000 workspace-1`），再：进终端、开一轮对话、改一个设置、导出一次；四条都应给出可见的失败或降级，**不能**白屏/崩溃 |
| 设备能力页在目录被删后的显示（⑤） | 删掉工作区 → 打开「设置 → 设备能力」：`写入边界 = 工作区：…` 那一行应仍显示旧路径（当前设计），不该显示「尚未确定」 |

---

## 上 CI 最可能出错的点（本机不编译，写在这里给下一个人）

1. **`PiProjectConfig` 漏登记会先红在 harness 编译**。`packages` harness 现在要编
   `ProjectTrust.kt`，而它 `import app.pi.runtime.PiProjectConfig`；`agent-tool-paths` 也要编
   同一个文件。两个都已在 `tools/run-app-pure-checks.sh` 里登记，但**如果 CI 报
   `unresolved reference: PiProjectConfig`，先看这个清单，不要怀疑 import 写法**。
2. **`PiProjectConfig.kt` 必须保持 Android-free**。它只许用 `java.io.File` + stdlib；
   一旦有人加了 `android.*` 或 Compose，两个 harness 会以
   `pure-checks: FAILED — … did not compile` 的形式红（脚本头注释写了这条约定）。
3. **新增的 7 个委托点**（`grep -rn PiProjectConfig app/src/main/kotlin`）：任何一处
   **类型**写错就是 `:app` 的编译错误（例如 `PiProjectConfig.root()` 返回 `File`，
   不要当成 `String`）。`PiThemeFiles.PiThemeScope.Project(...)` 是一个 `enum` 构造参数，
   那里是**字符串插值**，不是路径对象。
4. **`tools/pi-contract.mjs` 的 `checkProjectConfigDir`** 读的是 `appLiterals(...)` 的**源文本**：
   它靠正则 `DIRECTORY:\s*String\s*=\s*"([^"]+)"` 抓常量。如果 `PiProjectConfig.kt` 里的声明
   改成别的形状（例如 `const val DIRECTORY = ".pi"` 去掉类型），**这条断言会静默变成
   `kotlinDir === undefined` 而失败**（失败信息会指向 `PiProjectConfig.kt`），本地验证方式：
   `node -e` 里对源文件跑一次同样的正则（本轮已用这种方式验证过匹配到 `".pi"`、
   与 `/root/pi-src/packages/coding-agent/package.json` 的 `piConfig.configDir` 相等）。
5. **CI 的 `contract` job 需要 `piDir` 下的 `package.json`**。`--pi <dir>` 指向的是
   `node_modules/@earendil-works/pi-coding-agent`，它根目录有 `package.json`（含
   `piConfig.configDir`）。若 `fetch-runtime.mjs` 改成只下载 `dist/`（丢包清单），这条会
   以 `ENOENT` 抛在 `JSON.parse(readFileSync(...))` 上 —— 那时不要把它当成「pi 改了目录名」，
   去看载荷是怎么打的。
6. **`SessionsScreen.groupLabel` 依赖 `GuestWorkspacePath.RELATIVE` 与 `GUEST_ROOT`**：
   它是 UI 文件，`tools/typecheck.sh` 用 android.jar + 缓存里的 Compose 编译 `:app`，
   所以这条**不会**被 harness 覆盖。若有人改了工作区常量，UI 只需跟着编译即可（值来自常量，
   不是字面量）。
7. **harness 计数**：`run-app-pure-checks.sh` 的结尾是**算出**的（`$ran` 个），不写死 ——
   本轮没有增加 harness 个数（还是 12 个），只增加了两个 harness 的输入文件。若 CI 输出
   的个数与预期不符，说明有人动了清单。
