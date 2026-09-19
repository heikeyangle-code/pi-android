# 设置面 · 实现侧 bug 审查（对照 pi 0.85.1 / HEAD bbb61e3）

**范围**：`app/src/main/kotlin/app/pi/ui/settings/**`、`app/src/main/kotlin/app/pi/settings/PiSettingsFileStore.kt`，
以及接线处 `ui/PiRoot.kt`、`ui/PiSessionViewModel.kt`、`MainActivity.kt`。pi 源侧（缺失/无用/信息架构）不在本文。

**证据口径**

- 指向**本仓库**一律用**符号名**（函数/键名/类名），不写行号——同一轮有别的代理在改这些文件，行号会立刻过期。
- 指向 **pi 源码**才用 `file:line`，全部对着 `/root/pi-src` HEAD `bbb61e3` 核过。
- 代码状态：**读的是工作区当前内容**（含在飞批次已改的 `PiSettingsRegistry.kt`/`PiSettingsRows.kt`/`PiSettingsStack.kt`/
  `PiSettingsFileStore.kt`/`PiCredentialScreen.kt`/`PiModelsScreen.kt`/`DiagnosticsReport.kt`/`LicensesScreen.kt`/
  `PiSettingsEditors.kt` 与新增未跟踪的 `AppOnlySettingsStore.kt`），不是 git HEAD。
- 本文只做只读诊断：**没有修改任何已有文件**，只新建本文。
- 每条结论都能指到符号与调用路径。凡是我没核到的，写在 §5「没能确认」。

---

## 0. 结论先行

### 0.1 最严重的五条

| # | 级别 | 一句话 | 根因符号 |
|---|---|---|---|
| **B1** | **P0** | 数字编辑器点「恢复默认」写入 JSON `null`，而 pi 对 `null` 是**抛异常**不是「用默认值」：`httpIdleTimeoutMs` 一旦为 `null`，`pi --mode rpc` **启动就抛**（引擎起不来）；`compaction.reserveTokens`/`keepRecentTokens` 为 `null` 时每次压缩判定都抛；`websocketConnectTimeoutMs` 为 `null` 时每次模型请求都抛 | `PiNumberEditorSheet` 的「恢复默认」→ `onSet(null)` → `PiSettingEditorSheet` 的 `PiRowKind.Number` 分支写 `JsonNull`；`PiSettingsStore` 没有 `remove` |
| **B2** | **P0** | `PiSettingsFileStore` 的"一个实例一份缓存 + 只在本实例内 `synchronized`"导致**同一进程内的两个实例互相擦掉对方的键**：`WorkspaceStore` 自己 new 了一个只读全局文档的 store，**从不 `invalidate()`**，它在重命名/切换工作区时把**过期的整份文档**写回去 → 用户在设置里刚改的值从文件里消失 | `WorkspaceStore.store(context)`（`projectFile = null`）、`WorkspaceStore.rename`→`writeLabels`→`writeSetting`、`PiSettingsFileStore.writeLocked` 的 `base = global()`（缓存） |
| **B3** | **P0/P1** | 设置页的主写入路径**完全不取 pi 的 `proper-lockfile` 锁**（`PiConfigFiles.withLock` 在同仓库里已经实现且被 `PiEnginePreferences` 用了，只有 `PiSettingsFileStore.write` 没用）。pi 的 `persistScopedSettings` 是"锁内重读文件 + 只覆盖自己改过的字段"，App 是"无锁 + 整份缓存文档覆盖" → 双向丢更新；且两边临时文件名都是 `<name>.tmp-<pid>`，两个写入方会写**同一个临时文件** | `PiSettingsFileStore.write`/`writeDocument` vs `PiConfigFiles.withLock`/`write`；pi 侧 `settings-manager.ts:235-256`、`:258-285`、`:632-661` |
| **B4** | **P1** | 只读行（pi 版本/Node 版本/运行时占用/引擎启动耗时/唤醒锁/运行时实际生效）**整行可点**，点开的是**空的、禁用的**编辑器：行上写 `0.85.1`，sheet 里字段是空 + 一句「这一项只读。」。代码只把 chevron 藏了，没有把点击关掉 | `PiSettingRow`（`onClick = onOpen` 无条件传，只有 `if (!setting.readOnly)` 藏 chevron）、`SettingsGroupScreen.openRow`、`PiSettingEditorSheet` 用 `setting.textIn(store, "")` 而不是 `valueOverrides` |
| **B5** | **P1** | 对象型列表编辑器（`compaction.modelOverrides` / `thinkingBudgets` / `modelThinkingLevels`）**不做任何取值校验**：写一行 `openrouter/x = abc`，pi 的 `getCompactionTokenSetting` 直接 `throw`，此后**每个回合**的压缩判定都抛（`agent-session.ts:540`）。App 侧只是照原样写文件，没有任何提示 | `PiSetting.elementFromEntries`（`key = value` 无校验）、`PiListEditorSheet` 的保存路径 |

### 0.2 「为什么会乱」的实现侧根因

1. **设置面是「文件编辑器 + 展示层」，但缺少"键的生命周期"这一层。** `PiSettingsStore` 只有 `read`/`write`：
   没有删除、没有作用域参数、没有失败返回。于是「恢复默认」只能写 `null`（→B1），「清除」只能写 `""`，
   而 pi 的清除语义是**把键从该作用域的文档里删掉**（`persistScopedSettings` 里 `undefined` 会被 `JSON.stringify` 丢掉）
   ——这一半能力整个缺失，`SettingsDocument.removePath` 已经写好、有单测、**但全树 0 个调用点**。这是"设置页看起来能改一切、
   实际改不动一半"的结构原因。
2. **"谁是这份文档的权威"没有定死。** 同一个 `~/.pi/agent/settings.json` 现在有**四个** `PiSettingsFileStore` 实例
   （ViewModel、`WorkspaceStore`、`PiPackageFilterStore`、`PiEnginePreferences.ownStore`），各自缓存、各自加锁，
   再加上文件外的 pi 进程。锁只在实例内部有效（→B2/B3）。`PiEnginePreferences` 的 KDoc 已经把这条教训写下来了
   （"two instances over one file disagree after a write"，因此引入了 `sharedStore` 参数），但 `sharedStore` 的
   调用方 `PiCredentialService.preferences()` **传的还是默认值 `null`**，所以那条修法只落在了纸上。
3. **行/徽标/摘要的真相有三个来源**（registry 的 `defaultValue`、store 里的显式值、`valueOverrides` 的运行时事实），
   而编辑器只读第一个与第二个。只读行的点击路径（B4）就是这个三源分裂的最直观症状：行会读 override，编辑器不会。
4. **注册表元数据有一部分是"写了没人读"的**：`depth`（12 行）、`globAware`（6 行）、`emptyListLabel`（全部）在
   全树没有任何读取方（§4 B9）。而 KDoc 用 `depth` 承诺了 L2/L3 导航，实际所有编辑器只有一层 sheet。
5. 好消息：**上一轮账本里"有效/无效"的结论这次基本都核得住**（§1）。乱不是因为死键又回来了，而是上面这四条
   结构性缺口 + 若干"界面在说假话"的小口子。

---

## 1. 旧账核对（先分清"已真修"与"还在"）

| 旧账 | 这次核到的状态 | 证据 |
|---|---|---|
| `docs/settings-review.md` 说"注册了没有消费者"的 12 个死键已删 | **真修**。当前 70 键里，任何不在 `PiSettingsCatalog.piOwnedKeys` 的键都能在 `app/src/main/kotlin` 里找到字符串字面量消费者；我用脚本逐键重放了 `PiSettingsAuditCheck` 的五条断言：**0 个无读者、0 个过期白名单、0 个重复键、0 个 `app.*` 进白名单、9 个搜索 chip 全部可解析**。没有"只出现在注释里"的假消费者 | `PiSettingsAuditCheck` 的 `unread`/`stale`/`duplicates`/`badOwned`/`dangling` 五条；我按同一判据独立复算 |
| `piOwnedKeys` 的 23 条理由（"pi 读它，App 只写"） | **真品，且我逐条核过 pi 源码**。`transport`(`settings-manager.ts:831`→`core/sdk.ts:369`)、`modelThinkingLevels`(getter 实名 `getAllModelThinkingLevels`→`sdk.ts:218`)、`thinkingBudgets`(`:1174`→`sdk.ts:370`)、`httpIdleTimeoutMs`(`:936`)、`websocketConnectTimeoutMs`(`:957`)、`httpProxy`(`main.ts:583/:850`)、`compaction.reserveTokens/keepRecentTokens`(`:882/:886`→`agent-session.ts:540`)、`compaction.modelOverrides`(`:854-880`)、`branchSummary.reserveTokens`(`:903`)、`retry.maxRetries/baseDelayMs/maxAgentDelayMs`(`:927`)、`retry.provider.*`(`:949`)、`defaultTools`(getter 实名 `getDefaultTools`→`sdk.ts:257`)、`shellPath`(`:993`→`agent-session.ts:2794/:3016`)、`shellCommandPrefix`(`:1025`→`:2793/:3015`)、`npmCommand`(`:1035`→`package-manager.ts:1748`)、`images.autoResize`(`:1289`)、`images.blockImages`(getter 实名 `getBlockImages`→`sdk.ts:270`)、`enableInstallTelemetry`(`:1055`)。**没有一条是"只有 TUI 读"或"没人读"** | 见上，逐条 `rg` 到 getter 定义与消费点 |
| `EffectiveKind` 是否说真话 | **除一处瑕疵外相符**。App 侧真读的键我都走到了实际行为：`UiPrefs`（`readPrefs`）→ `ChatScreen`/`MainActivity`；`onSettingWritten` → `refreshPrefs`/`refreshTheme`/`refreshCommands` + 四个 RPC 推送（`setSteeringMode`/`setFollowUpMode`/`setAutoCompaction`/`setAutoRetry` 真实存在且在 `call(...)` 里发命令）；`app.runtime.*` 五个进程开关由 `PiLaunchOptions` 实际发出（`PI_OFFLINE`/`PI_CACHE_RETENTION=long`/`--system-prompt`/`--append-system-prompt`/`--no-context-files`，`cacheRetention="short"` 正确**不**设环境变量）；`app.runtime.keepAlive`=`RestartApp` 对得上（`if (prefs.keepAlive) startEngineService()` 在 attach/启动路径）；`enabledModels`=`RestartEngine` 对得上（pi 在进程启动解析一次，`main.ts:788`）。瑕疵见 §4 B11（`app.runtime.proroot` 徽标） | 符号见上 |
| `RuntimeFacts` 的只读行是否摆假值 | **诚实**。每个 reader 取不到时返回 `null`，`runtimeOverrides` 给每个键兜一句"取不到：运行时尚未解包 / 运行时里没有这个文件"，第一帧是"未读取"；`wakeLock` 用 `PiEngineService.isWakeLockHeld()` 的真值并区分"未持有（当前空闲）"，不是从服务存活推断 | `RuntimeFacts.read/piVersion/nodeVersion/runtimeUsage`、`runtimeOverrides` |
| `app.sessions.cleanupPolicy` 等 14 个 `app.*` 死键已删 | **真修**，全树 0 命中 | 逐键字符串扫描 |
| `app.runtime.safeMode`、`sessionDir`、`packages[].autoload` 已删 | **真修**（`sessionDir` 的键已不在注册表；`packages` 现在是只读行 + 开包管理页） | `PiSettingsCatalog.settings` 全量键列表 |
| 四个实时推送（`steeringMode`/`followUpMode`/`compaction.enabled`/`retry.enabled`） | **接上了，但只在"有引擎时"**：`onSettingWritten` 里 `queueModeOf(...)?let(::setSteeringMode)` 等；`readString` 返回 `null`（键被删/被清成空）时静默不推；`setSteeringMode` 等 `call(...)` 失败只走既有的错误通道。另外 `retry.enabled` 另有一处 seed（`seedAutoRetryFromSettings`）。**未在真机验证线格式**（§5） | `onSettingWritten`、`setSteeringMode`/`setFollowUpMode`/`setAutoCompaction`/`setAutoRetry`、`queueModeOf`、`seedAutoRetryFromSettings` |
| `PiFileWatch.kt` 的目录级监视 + ON_RESUME 兜底 | **真接线**：`PiSettingsStack` 顶层与 `PiModelsScreen` 各装一个 `PiDirectoryWatch`；`onDispose` 停表；掩码排除了 `OPEN/ACCESS/CLOSE_NOWRITE`（不会因自己读而自激）。问题见 §4 B10（重复触发 + 失效后的主线程 IO） | `PiDirectoryWatch`、`SETTINGS_WATCHED`、`PiModelsScreen` 的 `PiDirectoryWatch` |
| `packages` 行只读、开包管理页 | **真修**：`hostActions["packages"]` → 包管理屏；行 `readOnly = true` 且 `PiSettingsRows` 不给 chevron | `PiSettingsStack.hostActions`、`PiSettingsCatalog` 的 `packages` |
| `settings-audit` 进 CI | **在**：`tools/run-app-pure-checks.sh` 里 `run_harness settings-audit …`；`pre-spawn` 也在 | 脚本 harness 列表 |

**旧账里唯一仍在的**：`docs/settings-review.md` §7 列的四个真机判据（推送线格式、资源类五行的 RestartEngine 是否够、
`app.terminal.fontSize` 的 Reload、会话树默认过滤）——都不是实现侧 bug，原样留给真机。

---

## 2. 逐键链路表（70 键）

列含义：**kind** = 行型；**生效标签** = registry 的 `EffectiveKind`（未写 = 默认 `Immediate`）；
**写入方** = 谁把值写进文件（都是 `store.write(key, …)` → `PiSettingsFileStore` → 全局或项目文档）；
**读取方** = **真正消费它**的符号（不是"字符串出现过"）；**判定** 用这几个码：

- `1:1` = pi 读、App 标签与真实生效时机一致
- `PW` = `piOwnedKeys`：App 只写、pi 读；**已逐条核过**（§1）
- `AL` = App 自有键，有真实消费者且标签相符
- `!!` = 见 §4 的 bug 编号

| 键 | kind | 生效标签 | 读取方（符号） | 判定 |
|---|---|---|---|---|
| `app.models.inventory` | Action | — | `PiSettingsStack.hostActions` → `openModels` → `PiModelsScreen` | AL |
| `defaultProvider` | Value | NewSession | 显示 `SettingsHome.CurrentModelCard`；写 `PiEnginePreferences.selectModel`；pi 建会话读 `core/sdk.ts:215` | 1:1 |
| `defaultModel` | Value | NewSession | 同上（`core/sdk.ts:216`） | 1:1 |
| `defaultThinkingLevel` | Value | NewSession | `SettingsHome.CurrentModelCard`（显示 + 配色）；pi `core/sdk.ts:217` | 1:1 |
| `modelThinkingLevels` | List(Object) | NewSession | 无 App 读者（pi `getAllModelThinkingLevels` → `sdk.ts:218`） | PW |
| `thinkingBudgets` | List(Object) | NewSession | 无 App 读者（pi `:1174` → `sdk.ts:370`） | PW |
| `hideThinkingBlock` | Switch | Immediate | `PiSessionViewModel.readPrefs` → `UiPrefs.hideThinkingBlock` → `ChatScreen`（隐藏思考块 + 搜索过滤） | AL |
| `showCacheMissNotices` | Switch | Immediate | `readPrefs` → `UiPrefs.showCacheMissNotices` → `ChatScreen`（billing 行） | AL |
| `enabledModels` | List(Array) | **RestartEngine** | 显示 `PiModelsScreen`；写 `PiEnginePreferences.selectModel`；pi `main.ts:788`（进程启动解析） | 1:1 |
| `app.credentials.apiKey` | Action | — | `hostActions` → 凭证表单 → `PiCredentialService.save` | AL |
| `app.credentials.oauth` | Action | — | `PiRoot` 的 `onRunAction` → `session.notifyUser(...)`（只解释，不导航） | AL |
| `app.localModels.manage` | Action | — | `hostActions` → 凭证表单（preset `llamacpp`） | AL |
| `app.runtime.systemPrompt` | Text(multiline) | RestartEngine | `readLaunchOptions` → `PiLaunchOptions.systemPrompt` → `--system-prompt`（`takeIf { isNotBlank }`） | AL |
| `app.runtime.appendSystemPrompt` | Text(multiline) | RestartEngine | 同上 → `--append-system-prompt` | AL |
| `steeringMode` | Value | Immediate | `onSettingWritten` → `setSteeringMode` → RPC `set_steering_mode`；`get_state` 回读 | AL |
| `followUpMode` | Value | Immediate | `onSettingWritten` → `setFollowUpMode` | AL |
| `transport` | Value | NewSession | 无 App 读者（pi `:831` → `sdk.ts:369`） | PW |
| `httpIdleTimeoutMs` | Number | RestartEngine | 无 App 读者（pi `:936` → `main.ts:851`） | PW + **B1** |
| `websocketConnectTimeoutMs` | Number | NewSession | 无 App 读者（pi `:957` → `sdk.ts:322`） | PW + **B1** |
| `httpProxy` | Text | RestartEngine | 无 App 读者（pi `main.ts:583/:850` `applyHttpProxySettings`）；清空写 `""`，pi 侧 `if (!proxy) return` → 等价未设 | PW |
| `compaction.enabled` | Switch | Immediate | `onSettingWritten` → `setAutoCompaction`；`ChatScreen` 的实时开关 | AL |
| `compaction.reserveTokens` | Number | NewSession | 无 App 读者（pi `:882` → `agent-session.ts:540`） | PW + **B1** |
| `compaction.keepRecentTokens` | Number | NewSession | 无 App 读者（pi `:886`） | PW + **B1** |
| `compaction.modelOverrides` | List(Object) | NewSession | 无 App 读者（pi `:854-880`） | PW + **B5** |
| `branchSummary.reserveTokens` | Number | NewSession | 无 App 读者（pi `:903` → `agent-session.ts:3232`）；`null` 安全（`?? 16384`） | PW |
| `app.compaction.runNow` | Action | — | `PiRoot.onRunAction` → `session.compact()` | AL |
| `retry.enabled` | Switch | Immediate | `onSettingWritten` → `setAutoRetry`；`seedAutoRetryFromSettings` | AL |
| `retry.maxRetries` | Number | NewSession | 无 App 读者（pi `:927`）；`null` 安全（`?? 3`） | PW |
| `retry.baseDelayMs` | Number | NewSession | 同上（`?? 2000`） | PW |
| `retry.maxAgentDelayMs` | Number | NewSession | 同上（`?? DEFAULT`） | PW |
| `retry.provider.timeoutMs` | Number | NewSession | 无 App 读者（pi `:949`）；**无 `defaultValue`**，行显示「未设置」但编辑器只能存 0/整数 → 见 B6 | PW + **B6** |
| `retry.provider.maxRetries` | Number | NewSession | 同上 | PW |
| `retry.provider.maxRetryDelayMs` | Number | NewSession | 同上（`?? 60000`） | PW |
| `defaultTools` | List(Array) | NewSession | 无 App 读者（pi getter `getDefaultTools` → `sdk.ts:257`） | PW |
| `app.tools.expandByDefault` | Switch | Immediate | `readPrefs` → `UiPrefs.expandToolsByDefault` → `ChatScreen` | AL |
| `app.sessions.resumeLast` | Switch | **RestartApp** | `maybeResumeLastSession` 启动路径读它 | AL |
| `extensions` | List(Array) | RestartEngine | pi `resource-loader`/`package-manager`；App 侧 `ProjectTrust`/`PiPackageFilters` 读同一键做信任/过滤展示（不是启动通道） | 1:1 |
| `packages` | List(Array) readOnly | RestartEngine | `hostActions["packages"]` → 包管理屏；`PiPackageFilterStore` 读写数组 | AL |
| `skills` | List(Array) | RestartEngine | pi `resource-loader`；`ProjectTrust`/`PiResourceDiscovery` 展示 | 1:1 |
| `prompts` | List(Array) | RestartEngine | pi `resource-loader`；`ProjectTrust` 展示 | 1:1 |
| `themes` | List(Array) | RestartEngine | `PiSessionViewModel.configuredThemePaths` → `PiThemeLoader.load/discover`（App 热读） | 1:1（有意偏离：App 热读、pi 要重启）+ **B8**（与 `theme` 同名） |
| `enableSkillCommands` | Switch | Immediate | `refreshCommands` → `piCommandPalette` | AL |
| `theme` | Value | Reload | `PiSessionViewModel.refreshTheme` → `PiThemeLoader.load`；`PiSettingEditorSheet` 走 `PiThemeEditorSheet`；`onSettingWritten("theme")` → `refreshTheme()` | AL + **B8** |
| `app.appearance.fontScaleDelta` | Number | （Immediate） | `readPrefs` → `UiPrefs.fontScaleDelta` → `MainActivity`（`textScaleDelta`） | AL |
| `app.appearance.messageDensity` | Value | （Immediate） | `readPrefs` → `ChatScreen` 块间距 | AL |
| `app.appearance.showTimestamps` | Switch | （Immediate） | `readPrefs` → `ChatScreen` 时间戳/日期分隔 | AL |
| `app.appearance.thinkingCollapsedByDefault` | Switch | （Immediate） | `readPrefs` → `ChatScreen` `thinkingDefaultExpanded` | AL |
| `images.autoResize` | Switch | NewSession | 无 App 读者（pi `:1289`） | PW |
| `images.blockImages` | Switch | NewSession | 无 App 读者（pi `:1302` → `sdk.ts:270`，注释写明"mid-session changes take effect"，但 manager 每会话/进程才重建） | PW |
| `shellPath` | Text | NewSession | 无 App 读者（pi `:993` → `agent-session.ts:2794`）；清空写 `""`，pi `utils/shell.ts:69` `if (customShellPath)` → 等价未设 | PW |
| `shellCommandPrefix` | Text | NewSession | 无 App 读者（pi `:1025`） | PW |
| `npmCommand` | List(Array) | NewSession | 无 App 读者（pi `:1035`） | PW |
| `app.terminal.fontSize` | Number | Reload | `TerminalSettings` → `TerminalPane` | AL（Reload 判据见 §5） |
| `app.terminal.keyBar` | List(Array) | Reload | `TerminalSettings` → 终端按键条 | AL |
| `defaultProjectTrust` | Value | RestartEngine | `PiSettingsStack`（传给包管理屏）/`ProjectScreen`；pi `:1014` 在进程启动建 trust manager | 1:1 |
| `app.security.emergencyStop` | Action(dangerous) | — | `hostActions`? 否 → `onRunAction` → `session.stop()` + `abortBash()`；分组页页尾固定重画 | AL |
| `app.runtime.proroot` | Switch | RestartEngine | `AppOnlySettingsStore` → `RuntimeSelection.setProrootEnabled` → `RuntimePreferences` → `PtyLauncher`（`RuntimeSelection.of(context, paths)`） | AL + **B11** |
| `app.runtime.prorootStatus` | Text readOnly | — | `AppOnlySettingsStore.read` ← `RuntimeSelection.status().summary`（`PiSettingsStack` 的 `LaunchedEffect`） | AL + **B4** |
| `app.runtime.offline` | Switch | RestartEngine | `readLaunchOptions` → `PiLaunchOptions.environment` `PI_OFFLINE=1` | AL |
| `app.runtime.cacheRetention` | Value | RestartEngine | `readLaunchOptions` → `PiLaunchOptions` `cacheRetention=="long"` → `PI_CACHE_RETENTION=long`（`"short"` 不设，正确） | AL |
| `app.runtime.noContextFiles` | Switch | RestartEngine | `readLaunchOptions` → `--no-context-files` | AL |
| `app.runtime.restartEngine` | Action(dangerous) | — | `hostActions` → 确认对话 → `restartEngine(reason,false)` | AL |
| `app.runtime.keepAlive` | Switch | **RestartApp** | `readPrefs.keepAlive` → `if (prefs.keepAlive) startEngineService()`（启动路径） | AL |
| `app.runtime.wakeLock` | Text readOnly | — | `runtimeOverrides` ← `RuntimeFacts.wakeLockHeld`（`PiEngineService.isWakeLockHeld()`） | AL + **B4** |
| `app.runtime.piVersion` | Text readOnly | — | `runtimeOverrides` ← `RuntimeFacts.piVersion`（payload `package.json`） | AL + **B4** |
| `app.runtime.nodeVersion` | Text readOnly | — | `runtimeOverrides` ← `RuntimeFacts.nodeVersion`（`node_version.h`） | AL + **B4** |
| `app.runtime.rootfsUsage` | Text readOnly | — | `runtimeOverrides` ← `RuntimeFacts.runtimeUsage` | AL + **B4** |
| `app.runtime.engineStartup` | Text readOnly | — | `runtimeOverrides` ← `PiEngineSession.lastServingMs` | AL + **B4** |
| `app.runtime.diagnostics` | Action | — | `hostActions` → `DiagnosticsScreen`（`engineDiagnostics`/`recentFailures` 从 `PiRoot` 接） | AL |
| `enableInstallTelemetry` | Switch | NewSession | 无 App 读者（pi `:1055` → `core/telemetry.ts:12`） | PW |

**表内统计**：70 键 = 47 有 App 消费者 + 23 `PW`（`piOwnedKeys` 逐条核过）。
**没有任何一键是"注册了但没人读"或"写了但 pi 不读 App 也不读"**——上一轮那类病这次没复发。

---

## 3. 高发 bug 类逐类实测

### 3.1 与 pi 的并发写（写到同一份 `settings.json`）

**pi 侧事实（都在 `settings-manager.ts`）**

- 锁：`FileSettingsStorage.acquireLockSyncWithRetry`（`:235-256`）用 `lockfile.lockSync(path,{realpath:false})`，
  **锁目录是 `<path>.lock`**，`ELOCKED` 重试 **10 次 × 20ms**（忙等）。`withLock`（`:258-285`）在**文件存在**时取锁，
  在锁内 **重读文件**，再 `writeFileSync(path, next)`（**直接写，非原子**）。
- 保存语义：`persistScopedSettings`（`:632-661`）在锁内 `JSON.parse(current)` → `{...currentFileSettings}` →
  **只把自己 `modifiedFields` 覆盖上去** → `JSON.stringify(...,null,2)`。→ **pi 从不丢未知键**（`app.*` 会被保留），
  也从不丢"别人在同一锁内改过的键"。
- 谁会在运行期写：`save()` 的调用点有 50+ 处（`/model`、`/theme`、compaction、retry、trust、autoload…全部走
  `settings-manager.ts` 的 setter → `save()`），所以"运行中的 pi 会写 settings.json"不是假设。

**App 侧事实**

- `PiSettingsFileStore.write`（设置页主路径）只用 `synchronized(lock)`，**全文件没有 `PiConfigFiles.withLock`**；
  `writeLocked` 把**缓存的整份文档** + 一个键写回文件。
- `PiConfigFiles.withLock` 已经**精确复刻**了 pi 的锁协议（同 `<file>.lock` 目录、10×20ms、10s 陈旧窗口），
  `PiEnginePreferences.selectModel` 就是这么用的。**只有设置页这条路径没用。**

**结论（B3）**：App 的读-改-写窗口与 pi 的读-改-写窗口互相不可见，两个方向都会丢：

1. App 在 pi 的 `read→writeFileSync` 之间写入 → pi 用它读到的旧内容 + 自己的字段覆盖 → **App 的改动消失**（无任何报错）。
2. pi 写完、App 的缓存文档还是旧的 → App 下一次写设置时把整份旧文档写回 → **pi 的改动消失**。
3. `PiSettingsFileStore.writeDocument` 与 `PiConfigFiles.write` 的临时文件名**完全相同**（`<name>.tmp-<pid>`）：
   两个写入方在同一进程里并发时写同一个临时文件，`rename` 的内容可能是交错的。

**最小修法**：`PiSettingsFileStore.writeLocked` 里把真正的落盘包进 `PiConfigFiles.withLock(target)`（对 global/project
各自的目标文件），并把临时文件名加一个单调计数器/UUID；`withLock` 抛 `IllegalStateException`（锁超时）必须由调用方
转成界面错误，不能让 Compose 回调裸抛。

**怎么验证**：真机 + 文件观察（纯 JVM 跑不了：`PiSettingsFileStore` 里有 `android.os.Process.myPid()` 与
`java.nio`）。判据：① 设置里连改 3 个键，`cat ~/.pi/agent/settings.json` 三个键都在；② 在终端里 `pi` 跑一次
`/model` 改模型，然后立刻在设置里改一个外观开关 → 模型选择应仍在；③ 需要一个"人为拉长读-改-写窗口"的注入测试。

### 3.2 同一份文档的多实例缓存（**B2**，本条不需要真机就能证明）

`WorkspaceStore.store(context)` 自己 new 了 `PiSettingsFileStore(globalFile = <agentDir>/settings.json)`
（`projectFile = null`、`appLocalFile = null`），并在 `Scope` 里**永久缓存**，`WorkspaceStore` 里**没有任何
`invalidate()` 调用**。两条用户路径会用它写全局 `settings.json`：`rename` → `writeLabels` 和
`setCurrent` → `writeCurrent`。而 `PiSettingsFileStore.writeLocked` 的 `base = global()` 就是那个**过期缓存**。

触发路径（全部是真实 UI 操作）：

1. 打开 App → 工作区列表读一次 `WorkspaceStore.labels(context)` → 私有实例缓存文档 `D`（这一步在 `ProjectScreen` 组合时就发生）。
2. 设置 → 外观 → 改主题（或任何 pi 键）→ ViewModel 的 store 把 `D+theme` 写进文件。
3. 回到项目页 → 重命名工作区（或切换工作区）→ `writeLabels`/`writeCurrent` 把 `D+names` 写回文件 → **第 2 步的改动从文件里消失**。

同一形状也适用于 `PiPackageFilterStore`（它每次操作前显式 `invalidate()`，所以**没有**这个 bug——反证成立）
和 `PiEnginePreferences.ownStore`（每次 `preferences()` 新建实例，读的是新文件，也没有这个 bug）。

**最小修法**（按代价从低到高，选一）：

- (a) 低成本：`WorkspaceStore.writeSetting` 前先 `store(context).invalidate()`，读 `labels()` 前也 `invalidate()`。
  代价是每次工作区列表读取都读盘（列表本来就在 IO 上下文里）。
- (b) 正解：让全进程只有一份全局文档的 store（例如 `PiSessionViewModel.settingsStore` 交给
  `WorkspaceStore`/`PiPackageFilterStore`/`PiCredentialService`，即 `PiEnginePreferences` 的 `sharedStore` 参数来路
  已经画好，`PiCredentialService.preferences()` 现在传的是默认 `null`——把它接上）。
- (c) 或给不同实例的临时文件名 + 落盘加同一把跨实例锁（与 B3 合并做）。

**副作用风险**：`WorkspaceStore` 也用这份文档存 `app.workspace.current` / `app.workspace.names`，
`invalidate()` 变频繁只会多读盘，不改语义。改 `PiSettingsFileStore` 本体会与在飞批次的
`PiSettingsFileStore.kt` 相撞（见 §6 归属）。

### 3.3 文件监听（`PiFileWatch.kt`）

| 问题 | 判定 | 证据 |
|---|---|---|
| 自激循环 | **不存在**。掩码不含 `OPEN/ACCESS/CLOSE_NOWRITE`，而回调只做"丢缓存 + 加 epoch + 重读"，不写文件 | `MASK`、`PiSettingsStack.onChanged`（`onExternalSettingsWrite()` + `filesEpoch++`） |
| 抖动/重复触发 | **存在（P2，B10）**。App 每次写会发两次事件（CREATE `.tmp-<pid>` + MOVED_TO 正式名），每次都 `invalidate()`+`filesEpoch++`；设置页在组合里时 `PiModelsScreen` 自己**还装了一个** `PiDirectoryWatch`（同一批目录/名字），模型页打开时同一次改动会触发两个监视器 | `SETTINGS_WATCHED` 前缀匹配、`PiModelsScreen` 的 `PiDirectoryWatch` |
| 外部改动能否可靠刷到界面 | **能，但对设置首页不完整**：`SettingsGroupScreen` 与 `SettingsSearchScreen` 接 `freshness = filesEpoch` 并 `remember(groupId, freshness)` 重建行；`SettingsHome` **没有 freshness 参数**，它只在 `PiSettingsStack` 重组时被动重读——因为 `filesEpoch` 变化会让 `PiSettingsStack` 重组，所以实际也能刷到；这是"靠父级重组"而非显式契约 | `PiSettingsStack` 的 `when`、`SettingsHome(store = effectiveStore, …)`（无 freshness） |
| 丢 watcher | **不丢**：`DisposableEffect(key)` 的 `onDispose` 里 `stopWatching()`；key 由目录路径拼成，切工作区会重建 | `PiDirectoryWatch` |
| 主线程 IO | **存在（P2）**：`FileObserver(String,Int)` 的回调在主线程；`onChanged` → `invalidate()` → 行在**组合期** `store.read()` → 冷缓存时 `global()` 在组合线程上 `file.readText()`。`refreshPrefs` 的 KDoc 明确说"第一次读会碰盘所以放到 IO"，但行读取没有这层保护 | `PiSettingsFileStore.global/readDocument`、`PiSettingsFileStore` KDoc |

### 3.4 校验与解析（对 pi 的合法域）

| 键 | pi 的解析 | App 编辑器 | 判定 |
|---|---|---|---|
| `httpIdleTimeoutMs` | `parseTimeoutSetting` → `parseHttpIdleTimeoutMs`（`http-dispatcher.ts:19-35`）：字符串 `"disabled"`→0、空串→undefined、数字串→Number；**非 number → undefined**；然后 `if (value !== undefined) throw`（`settings-manager.ts:186-196`） | 范围 0..3600000、step 1000；输入非法时**静默保留旧值**；「恢复默认」写 **`null`** | **B1（P0）**：`null` 不等于 `undefined` → **throw** |
| `websocketConnectTimeoutMs` | `getWebSocketConnectTimeoutMs` 同样走 `parseTimeoutSetting`；未设时返回 `undefined`（不是 15000！） | 同上 | **B1** + registry 的 `defaultValue = 15000` 是**SDK 层默认**，行显示"15000 ms"——语义上可接受（§5 记为未逐字核 `docs/settings.md`） |
| `compaction.reserveTokens` / `keepRecentTokens` | `getCompactionTokenSetting`：`ordinary !== undefined && (typeof !== "number" || !Number.isSafeInteger || < 0) → throw` | 范围 0..1000000、step 1024；「恢复默认」写 `null` | **B1（P0）** |
| `compaction.modelOverrides` | 同上，再加上 `entry` 必须是对象、`override` 必须是非负安全整数，否则 `throw`（`settings-manager.ts:854-880`） | 自由文本 `key = value`，`valueElementOf` 把 `abc` 存成字符串 | **B5（P1）** |
| `retry.maxRetries` / `baseDelayMs` / `maxAgentDelayMs` | `?? 3` / `?? 2000` / `?? DEFAULT`，**对 `null` 安全** | 同上 | 无 bug（只是「恢复默认」写 `null` 而不是删键，见 B6） |
| `retry.provider.timeoutMs` | `getProviderRetrySettings().timeoutMs` 原样透出；下游 `options?.timeoutMs ?? providerRetrySettings.timeoutMs ?? effectiveTimeoutMs` 用 `??`，`null` 被跳过 | 行**没有** `defaultValue` → 显示"未设置"，但编辑器 `start = low = 0`，任何一次「保存」都会写 **0**（不是"留空"） | **B6（P1）**：`0` 在 pi 侧不是"沿用 SDK 默认"（`??` 只跳过 null/undefined）→ 写 0 等于把超时设成 0（`sdk.ts:317-319` 对 `httpIdleTimeoutMs==0` 专门换成 max int32，provider timeout 没有这层保护） |
| `retry.provider.maxRetries` / `maxRetryDelayMs` | `?? 60000` / 原样透出 | 数值框 | 无 bug |
| `thinkingBudgets` | `getThinkingBudgets()` **原样返回对象**（`settings-manager.ts:1174-1176`），一路透到 provider（`sdk.ts:370`、`bedrock-converse-stream.ts:1245` 用 `?? default`） | 自由 `key = value`，非法值不拦 | P2（B7）：provider 会收到非数字预算 → 报错信息来自厂商而不是设置页 |
| `outputPad` | `0\|1` | **注册表已删**（无 `wireIsScalar` 通道，KDoc 写明再加要先加回开关） | 无（旧账已处置） |
| `shellPath` | `getShellPath()`：`shellPath ? normalizePath : shellPath` → `""` 返回 `""`；下游 `utils/shell.ts:69` `if (customShellPath)` → **`""` 当未设** | Text 行 + 「清除」写 `""` | 行为上安全；但见 B6/B8（写 `""` 不等于删键，`isExplicit` 会变真） |
| `httpProxy` | `applyHttpProxySettings`：`httpProxy?.trim()` 为空即 return | 同 | 安全 |
| 数字框输入非数字 / 超范围 | — | `typed` 与 `value` 分离：`toIntOrNull()` 失败时 `value` 不变，`保存` 写**旧值**；超范围时 `typed` 显示原串、`value` 被 clamp | **B6（P1）**：界面显示的数字与写入的数字可以不一致，且没有任何错误提示 |

### 3.5 作用域（项目 `.pi/settings.json` vs 全局 `~/.pi/agent/settings.json`）

- 选文档的规则在 `PiSettingsFileStore.writeLocked`：`targetIsProject = projectFile != null && projectFile.isFile &&
  lookup(project(), key) != null` —— "项目文档存在**且已带这个键**"才写项目，否则写全局；先说 `isFile` 是为了
  不把用户删掉的项目文档复活。**这条规则本身是对的、也写了理由**。
- **缺的是"删键"**：`PiSettingsStore` 没有 `remove`，`SettingsDocument.removePath` 有实现有单测但**没有调用点**。
  于是：
  1. 项目里覆盖过的键，用户在 App 里**永远删不掉**（只能改成另一个值），而 pi 的 `persistScopedSettings` 里
     `undefined` 会让键从该作用域的文件里消失 —— 「项目覆盖 = 删项目里的键」这条语义在 UI 上不可达。
  2. 「清除」写 `""`、「恢复默认」写 `null`，都不是删除（B1/B6 的直接来源）。
- **会不会写错文件？** 设置页不会：它按"键在哪个文档里"路由。`PiPackageFilterStore` 是唯一显式带作用域的写入方，
  它自己拦住了一个真会写错的组合：**用户包的过滤规则**在项目也有一份 `packages` 时**拒绝写**（否则 `store.write("packages", …)`
  会把用户数组写进项目文件）。这条防护是好代码，也说明"作用域只能靠启发式"这个设计是脆的。

### 3.6 JSON 保真

- 合并：`SettingsDocument.merge` = 对象递归、**数组整体替换**、标量替换 —— 与 pi 的 `isMergeableObject` 一致。
  未知键保留（每次 `setPath` 都 `LinkedHashMap(node)` 复制）。
- 数组 replace：`setPath` 直接放 `JsonArray`，不合并 ✓。
- 对象递归 merge：`setPath("retry.provider.maxRetries", …)` 只替换叶子，兄弟键保留 ✓。
- **`app.*` 键确实会落进 pi 的文档**（`isAppLocalKey(key) = key.contains("[]")`，而注册表里**没有任何键含 `[]`**）。
  结论：`appLocalFile` / `<agentDir>/../app-prefs.json` 这个机制**当前是死的（B12，P2）**；`app.*` 全部写进
  `~/.pi/agent/settings.json`。这与 `PiSettingsFileStore` KDoc 的"app-only preferences live here harmlessly"一致，
  而且我核过 pi 侧**确实不会丢**它们（`persistScopedSettings` 是锁内重读 + 只覆盖 modifiedFields）。所以：
  **不是数据损坏，是"App 成了 pi 用户配置的共同作者"**——pi 自己的 `/settings`、配置 diff、备份里都会看到这些键。
- 损坏文件：`readDocument` 解析失败时把文件 `move` 成 `<name>.corrupt-<ts>` 再返回空对象，**不会覆盖**；
  `writeDocument` 是 temp + `Files.move(REPLACE_EXISTING)`，失败才回退 `writeText`。
  **但没有任何测试覆盖这条路**（见 §3.8）。

### 3.7 界面层「乱」的根因（逐条实测）

| 症状 | 根因（符号） | 级别 |
|---|---|---|
| 两行都叫「主题」 | `PiSettingsCatalog` 里 `themes`（扩展与资源 · 本地资源）与 `theme`（外观 · 主题）`title` 都是 `主题`。全注册表**只有这一处**重复标题（我脚本扫了 70 行的 `title`）；搜索 chip `theme` 的命中会出现两条只差面包屑的结果 | **B8（P1，文案级）** |
| 只读行看起来能点 | `PiSettingsRow` 的 `Text/Number/List/Value` 分支无条件 `onClick = onOpen`，只把 chevron 藏了；`SettingsGroupScreen.openRow` 把所有非 Action/Switch 行送进编辑器；`PiSettingEditorSheet` 对 `Text` 用 `setting.textIn(store,"")`（**不读 `valueOverrides`**）→ 行上"0.85.1"、sheet 里空白 + "这一项只读。" | **B4（P1）** |
| 分组摘要说谎 | 只在"清除/恢复默认"之后：`G_TERMINAL` 的 summary 用 `row.isExplicit(store)` 决定显示"默认 /bin/bash"还是值；写 `""` 之后 `isExplicit` 变真 → 摘要显示「未设置 · 等宽 13sp」，而 pi 仍在用 /bin/bash。`G_PROMPTS` 的 summary 用了 `isNotBlank()`，**没有**这个问题（同一份代码里两种口径） | **B6/B8（P2）** |
| `Number` 编辑器的光标/IME | `PiEditorField` 是 `BasicTextField`；数字框 `singleLine` + 高度固定，键盘类型未显式设 `KeyboardType.Number`（在 `PiEditorField` 里看不到 `keyboardOptions`）→ 手机上弹全键盘 | **B13（P2）** |
| 搜索页命中与 hint chip | **都能解析**。9 个 chip（`reserveTokens`/`thinkingBudgets`/`theme`/`代理`/`压缩`/`保活`/`/compact`/`/login`/`llama`）我用 `PiSettingsCatalog.search` 的判据逐个走过：`/login` 靠 `app.credentials.oauth` 的 `aliases`、`llama` 靠 `app.localModels.manage` 的 `aliases`、`代理` 靠 `httpProxy` 的 description。harness 的 chip 断言读的就是 `SettingsSearchScreen` 的 `examples` 字面量（不是过期的硬编码副本），且只在"行区域"里匹配 | 无 bug |
| `PiSettingsStack` 返回/重入/状态丢失 | `BackHandler` 的分支顺序正确（search → licenses → diagnostics → models → credentials → packages → device → group）；`backReturnsToSearch` 让"从分组内搜索进搜索结果"能回到搜索。**但**所有层级状态都是 `remember`，不是 `rememberSaveable`：旋转/进程重建回到首页并丢掉搜索词 | **B14（P2）** |
| 滚动位置 | `SettingsGroupScreen` 的 `listState = rememberLazyListState()` 在分组切换时被复用（`groupId` 变化不重置），从长分组进短分组时可能停在底部 | **B15（P2）** |
| 「危险操作」行重复 | `SettingsGroupScreen` 把 `app.security.emergencyStop` 固定在**每个**分组页页尾（除它自己所在的安全与信任组）。这是 v2 的刻意设计（注释写明），不是 bug；但它让"这一页到底几项"难数——信息架构问题留给源侧那位 | 非 bug |

### 3.8 可验证性与测试缺口

- 唯一与设置有关的纯 JVM harness 是 `settings-audit`（把注册表当**源码文本**读）与 `:rpc` 的
  `SettingsDocumentTest`（merge/lookup/setPath/**removePath** 都覆盖了）。
- **`PiSettingsFileStore` 零测试**：原子写、损坏文件移开、锁、缓存失效都没有覆盖。原因是它依赖
  `android.os.Process.myPid()`（临时文件名）与 Compose 侧的 `PiSettingsStore` 接口，进不了 bare-JVM harness。
  **最便宜的可测性改造**：把临时文件名从 `pid` 换成实例内自增计数器/UUID（同时也是 B3 的修法之一），
  并把 `PiSettingsFileStore` 的 `PiSettingsStore` 依赖换成 :rpc 里 Android-free 的接口 —— 之后
  `run_harness settings-store` 就能覆盖"写一个键 → 兄弟键与未知键保留""损坏文件被移到 `.corrupt-*` 而不是被覆盖"
  "两个实例互相可见（或至少能测出互相不可见）"。
- B1/B5/B6 的**判据**都能在纯 JVM 上做，只要校验逻辑放在 Android-free 文件里：`elementFromEntries` 现在在
  `PiSettingsJson.kt`，但 `PiSetting` 数据类在导入了 Compose 的 `PiSettingsRegistry.kt`，所以现在的 harness
  只能读文本。**建议**：把"这个键的合法域/校验"抽成一个 Android-free 的 `PiSettingValidation`，
  这样 `settings-audit` 就能顺带断言"每个 Number 行的 min/max 与 pi 的解析域一致""Object 容器行的值必须有校验器"。

---

## 4. Bug 明细（含最小修法、副作用、验证、文件归属）

| # | 级别 | 症状与触发路径 | 根因（符号 / pi file:line） | 最小修法 | 副作用风险 | 怎么验证 | 碰哪些在飞文件 |
|---|---|---|---|---|---|---|---|
| **B1** | **P0** | 设置 → 消息与网络 → 「HTTP 空闲超时」→ 恢复默认 → `settings.json` 里 `"httpIdleTimeoutMs": null` → 重启引擎 → **引擎起不来**。同理 `websocketConnectTimeoutMs`（每次请求抛）、`compaction.reserveTokens`/`keepRecentTokens`（每次压缩判定抛） | `PiNumberEditorSheet` 的「恢复默认」`onSet(null)`；`PiSettingEditorSheet` 的 `PiRowKind.Number` 分支 `store.write(key, if (value==null) JsonNull else …)`；`PiSettingsStore` 无 `remove`。pi：`settings-manager.ts:186-196`（`parseTimeoutSetting` 对 `value!==undefined` 抛）、`http-dispatcher.ts:19-35`（`parseHttpIdleTimeoutMs(null)===undefined`）、`settings-manager.ts:860-880`（compaction 显式 check `typeof !== "number"` → 抛）、`main.ts:850-851`（启动时 `configureHttpDispatcher(getHttpIdleTimeoutMs())`，无 try/catch）、`core/sdk.ts:315-322`、`core/agent-session.ts:540` | 「恢复默认」改成**删除该键**：给 `PiSettingsStore` 加 `remove(key)`，`PiSettingsFileStore.remove` 用 `SettingsDocument.removePath`（已存在、已测）+ `writeDocument`，`InMemoryPiSettingsStore.remove` 已经有了，`AppOnlySettingsStore.remove` 对 `prorootStatus` 吞掉、对 `proroot` 拒绝。**任何情况下不要写 `JsonNull`** | 接口变更要同步 3 个实现 + 编辑器；`AppOnlySettingsStore` 必须实现新方法（否则编译失败 = 好事）。若只做"写默认值"而不是删键，会与 pi 的稀疏语义不一致（文件里多出 `"reserveTokens": 16384`），但不会抛 | 纯 JVM：`SettingsDocumentTest` 已覆盖 `removePath`；端到端要真机（写 → `cat settings.json` → 重启引擎）。**进不了现在的 harness**（`PiSettingsFileStore` 依赖 Android） | 改 `PiSettingsEditors.kt`/`PiSettingsStore.kt`/`AppOnlySettingsStore.kt`；删键落在 `PiSettingsFileStore.kt`（**在飞**） |
| **B2** | **P0** | ① 打开 App（工作区列表读过一次）② 设置里改任何 pi 键 ③ 项目页重命名/切换工作区 → 文件里第 ② 步的值**消失** | `WorkspaceStore.store(context)`（`projectFile = null`，永久缓存，全类无 `invalidate()`）、`WorkspaceStore.rename`→`writeLabels`→`writeSetting`、`WorkspaceStore.setCurrent`→`writeCurrent`、`PiSettingsFileStore.writeLocked` 的 `base = global()` | 首选把 `WorkspaceStore` 的读写接到**同一份** store（把 `PiSessionViewModel.settingsStore` 传进去，或让 `WorkspaceStore` 用 `PiSettingsFileStore.forWorkspace` 并统一由一个持有者持有）；退一步：`writeSetting`/`labels` 前后 `store(context).invalidate()` | 退一步方案会让工作区列表每次读盘（它已在 IO 上下文里）；接到同一实例会牵动 `WorkspaceStore` 的构造（`object`，无注入点）——最干净是引入一个进程级单例持有者 | 真机：按上面 3 步走一遍 + `cat` 文件。纯 JVM 只能覆盖 `PiSettingsFileStore` 的实例可见性（需先做 §3.8 的可测性改造） | 改 `PiSettingsFileStore.kt`（**在飞**）；若改 `WorkspaceStore.kt`（不在计划里） |
| **B3** | **P0/P1** | pi 运行期改设置（`/model`、`/theme`、compaction、trust…）与设置页写入交错 → 一方**无提示地**丢掉另一方的改动；极端情况下两个写入方写同一个 `.tmp-<pid>` 临时文件 | `PiSettingsFileStore.write` 只有 `synchronized(lock)`，无 `PiConfigFiles.withLock`；`writeDocument` 的临时名与 `PiConfigFiles.write` 相同（`<name>.tmp-<pid>`）。pi：`settings-manager.ts:235-256`、`:258-285`、`:632-661` | 在 `PiSettingsFileStore.writeLocked` 里把落盘包进 `PiConfigFiles.withLock(目标文件)`（`PiConfigFiles` 已经精确复刻 pi 的锁协议）；临时文件名加自增计数/UUID；`withLock` 的 `IllegalStateException` 转成 `Result`/界面提示 | 取锁会引入等待（最多 10×20ms），设置页的写从"立刻"变成"最多 200ms 后"；`toggleRow` 现在是裸回调，必须改成能显示失败。**不要**在 `read` 路径取锁（读不需要） | 真机 + 双写并发脚本；纯 JVM 需要先抽出 Android-free 的"落盘"辅助 | `PiSettingsFileStore.kt`（**在飞**）+ `PiSettingsEditors`/`SettingsGroupScreen` 的错误处理 |
| **B4** | **P1** | 设置 → 运行时与诊断 → 点「pi 版本」→ 打开一个**空的、禁用的**文本框（行上是 `0.85.1`），底部一句「这一项只读。」。`唤醒锁状态`/`运行时（实际生效）`/Node 版本/运行时占用/引擎启动耗时同样 | `PiSettingRow`（`onClick = onOpen` 对 Text/Number/List/Value 无条件传，只有 `if (!setting.readOnly)` 藏 chevron）；`SettingsGroupScreen.openRow`（`else if (setting.kind != PiRowKind.Switch) editing = setting`）；`PiSettingEditorSheet` 的 `PiRowKind.Text` 用 `setting.textIn(store, "")` | `openRow` 里补 `&& !setting.readOnly`（最省），并把 `PiSettingsRowShell` 的 `onClick` 对 readOnly 行传 `null`（行不可点，chevron 已经藏了）。**不要**改成"编辑器读 override"——那会做出一个编辑运行时事实的入口 | 只读行失去点击反馈（本来就无意义）；搜索页命中只读行仍能跳过去，需要确认跳转高亮不依赖点击 | 纯 JVM 不行（Compose）。真机：`/app/ui/dump` 点一次读只读行，断言不出现 sheet（或用截图） | `SettingsGroupScreen.kt`（不在父代理列表但在飞改动集合里，见 §6）、`PiSettingsRows.kt`（**在飞**） |
| **B5** | **P1** | 设置 → 上下文与压缩 → 逐模型覆盖 → 加一行 `openrouter/anthropic/claude = abc` → 保存成功（无提示）→ 此后**每次**压缩判定 pi 抛 `Invalid compaction.modelOverrides["…"].reserveTokens setting: abc…`，该回合的上下文管理失效 | `PiSetting.elementFromEntries`（`key = value` 无校验，`valueElementOf("abc")` → JSON 字符串）；`PiListEditorSheet` 的保存路径无错误状态；pi `settings-manager.ts:854-880`（`override !== undefined && typeof !== "number"` → throw），`core/agent-session.ts:540` | 给 `container = Object` 且键属于 compaction 的行加校验器：值必须是**非负安全整数**；不合法时在 sheet 内联报错并**拒绝保存**（`PiListEditorSheet` 已经有能力显示文本，加一个 `errorText` 即可）。`thinkingBudgets` 同类但只影响厂商侧（P2） | 校验规则一处定义、三行共用；注意 `modelThinkingLevels` 的值是**字符串**枚举，别把三种 Object 行用同一条规则 | 纯 JVM 可做**只要**校验逻辑放进 Android-free 文件（`PiSetting` 所在的 registry 导入 Compose，现在进不了 harness —— 见 §3.8） | `PiSettingsEditors.kt`（**在飞**）、`PiSettingsJson.kt`（干净） |
| **B6** | **P1** | ① 数字框输入 `abc` 或 `1e9`（超 `toIntOrNull`）→ 点「保存」→ 静默写入**旧值**；② 输入超范围数字 → 框里显示原数、写入被 clamp，两者不一致；③ `retry.provider.timeoutMs` 的说明写「留空表示沿用 SDK 默认值」，但编辑器**没有办法留空**，任何一次保存都写 0；④ 任何数字行「恢复默认」的另一个表现见 B1 | `PiNumberEditorSheet` 的 `onValueChange`（只更新 `typed`，`value` 仅在解析成功时变）、`onSet(value)`；`retry.provider.timeoutMs` 无 `defaultValue` → `start = low = 0`；pi `settings-manager.ts:946-949`（`timeoutMs` 原样透出）→ `core/sdk.ts:317`（`??` 只跳过 null/undefined，0 会生效） | 数字框加"无效即禁用保存 + `isError` + 范围提示"；`retry.provider.timeoutMs` 这类**可空**行给一个显式「未设置（用厂商默认）」选项，配合 B1 的 `remove`；把 clamp 后的值回写进 `typed` | 禁用保存会改变"随手点保存"的习惯；可空行的 UI 需要与 Value 行的"未设置"口径统一 | 真机 UI 测试；纯 JVM 可测 `intValueOrNull`/clamp 的纯函数部分（需抽出） | `PiSettingsEditors.kt`（**在飞**） |
| **B7** | **P2** | `thinkingBudgets` 里写非数字 → provider 收到非法预算，报错来自厂商 | `PiSetting.elementFromEntries`；pi `settings-manager.ts:1174-1176` 原样返回 → `core/sdk.ts:370` → `packages/ai/.../bedrock-converse-stream.ts:1245`（`?? default`） | 同 B5 的校验器（数值型 Object 行） | 同 B5 | 同 B5 | 同 B5 |
| **B8** | **P1（文案）** | 两行都叫「主题」（`themes` 在扩展与资源、`theme` 在外观）；搜 `theme` 时两条结果只差面包屑；另外 `G_TERMINAL` 摘要在「清除」后显示「未设置 · 等宽 13sp」而 pi 仍在用 /bin/bash | `PiSettingsCatalog` 两行的 `title`；`G_TERMINAL` 的 `summary` 用 `isExplicit(store)` 判分支（`G_PROMPTS` 用 `isNotBlank()`，同文件两种口径） | `themes` 的标题改成「主题文件」（它是**路径数组**，本来就不该叫"主题"）；摘要改成用 `textIn(...).isNotBlank()` 判分支 | 只动文案与一个 `if`；搜索排序不变（`themes` 的分数来自 key/alias） | 纯 JVM：给 `settings-audit` 加一条"标题唯一"断言（1 行，立即能失败） | `PiSettingsRegistry.kt`（**在飞**） |
| **B9** | **P2** | 注册表元数据有"写了没人读"的字段：`depth`（12 行）、`globAware`（6 行）、`emptyListLabel`（70 行都用默认）在全树 0 个读取方；KDoc 用 `depth` 承诺"2/3 表示还能再进一层编辑器"（spec §6.1 L2/L3），实际所有编辑器只有一层 sheet | `PiSetting.depth`/`globAware`/`emptyListLabel` 的声明与 KDoc；`PiSettingEditorSheet` 不读 `depth` | 要么删掉这三个字段（连同 12+6 行的实参），要么把 KDoc 改成实话（"当前只有 L2，没有 L3"）。**别**为了让字段有用去做 L3 | 删字段会碰 18 行的实参；删 `depth` 会与 in-flight 批次的行改动冲突 | 纯 JVM：`settings-audit` 可以断言"注册表里出现的每个字段名都能在 registry 之外找到一次读取"（文本级，弱但能抓回归） | `PiSettingsRegistry.kt`（**在飞**） |
| **B10** | **P2** | 每次设置写入触发两个监视事件（`.tmp-<pid>` + 正式名）→ 双份 `invalidate()` + `filesEpoch++`；模型页开着时**两个** `PiDirectoryWatch` 同时命中；`invalidate()` 之后的下一次组合会在**主线程**读盘 | `SETTINGS_WATCHED` 的前缀匹配（故意含 `.tmp-`/`.lock`）、`PiModelsScreen` 自己的 `PiDirectoryWatch`、`PiSettingsFileStore.global()` 在组合期被调用 | 给 `PiDirectoryWatch` 的 `onChanged` 加一个几毫秒的合并（`LaunchedEffect` + `delay`，`PiModelsScreen` 已经有 200ms 的合并先例）；或让 `PiSettingsStack` 的监视在 `models` 分支打开时让位；把首次读盘挪到 IO（预热） | 合并会让外部改动的可见性晚几毫秒；让位逻辑要小心别把首页的监视关掉 | 真机：改一次文件数一下重读次数（日志/计数）；纯 JVM 不行 | `PiFileWatch.kt`（干净）、`PiModelsScreen.kt`（**在飞**） |
| **B11** | **P2** | `app.runtime.proroot` 的徽标是「需重启引擎」，但这一行**同时**有立即副作用（清失败计数、清探针缓存结论、刷状态行）。徽标是"什么时候生效"的唯一信号，只说了对 runtime 的生效时机 | `PiSettingsCatalog` 的 `EffectiveKind.RestartEngine`；`AppOnlySettingsStore.write`+`RuntimeSelection.setProrootEnabled`；`PiSettingsStack` 的 `handleSettingWritten`（`runtimeStatusEpoch++`） | 行说明里已经写了"关掉再打开会清零失败计数、并让探针重测"——**够了**；若要更准，可在徽标旁保留"状态已刷新"的提示。不建议改 `EffectiveKind`（会与"runtime 要重启"冲突） | 无 | 真机：点开关看状态行是否立刻变 | `PiSettingsRegistry.kt`（**在飞**） |
| **B12** | **P2** | `appLocalFile`/`app-prefs.json` 机制是死的：`isAppLocalKey` 只认含 `[]` 的键，而注册表里**没有**这种键（`packages[].autoload` 已删）。KDoc 仍然承诺"这类键被留在单独的文件里" | `PiSettingsFileStore.isAppLocalKey`、`appLocalFile` 参数、`forWorkspace` 的 `File(agentDir.parentFile, "app-prefs.json")` | 要么删掉这个机制（连同 KDoc 段落），要么保留但在 KDoc 写明"当前没有键走这条路，`app.*` 会落在 pi 的文档里"。**不要**为了用它而把 `app.*` 挪进 sidecar（会与 `piOwnedKeys`/审计的判据打架） | 删参数会碰 `PiSettingsFileStore.kt`（在飞）与测试构造 | 纯 JVM：断言注册表没有含 `[]` 的键（1 行） | `PiSettingsFileStore.kt`（**在飞**） |
| **B13** | **P2** | 数字编辑器在手机上弹**全键盘**（没有数字键盘），且 sheet 里 `−`/`+` 的步进与手输混用 | `PiSettingsEditors.PiEditorField`（`BasicTextField` 未传 `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)`) | 给数字框加 `KeyboardType.Number`（`PiEditorField` 加一个可选参数，只数字行传） | 无（`Decimal`/`Number` 键盘在部分 IME 上不显示负号，而 `fontScaleDelta` 的域是 -2..2 → 用 `KeyboardType.Number` 而不是 `Decimal`，负号靠 `−` 按钮） | 真机 UI | `PiSettingsEditors.kt`（**在飞**） |
| **B14** | **P2** | 旋转屏幕或进程重建后回到设置首页，丢掉当前分组/搜索词 | `PiSettingsStack` 的 `groupId`/`searching`/`backReturnsToSearch`/`credentials`/`models`… 全部 `remember` 而非 `rememberSaveable` | 换成 `rememberSaveable`（字符串/布尔都可保存） | 进程重建后 `store` 是同一个 ViewModel，恢复安全；`editing`/`confirming` 这类持有 `PiSetting` 的状态**不要** saveable（数据类不可序列化） | 真机：旋转一次 | `PiSettingsStack.kt`（**在飞**） |
| **B15** | **P2** | 从长分组（如「运行时与诊断」）退到短分组（如「隐私与关于」）时列表停在底部 | `SettingsGroupScreen` 的 `listState = rememberLazyListState()` 不随 `groupId` 重置 | `LaunchedEffect(groupId) { listState.scrollToItem(0) }`（或 `key(groupId)` 包一层） | 会让"从搜索结果跳回同一分组"也回到顶部——`highlightKey` 的 `animateScrollToItem` 在同一 `LaunchedEffect` 里，需保证顺序（先回顶再滚到命中） | 真机 UI | `SettingsGroupScreen.kt`（**在飞改动集合里**） |

---

## 5. 没能确认的项（写明缺什么证据）

1. **四个 RPC 推送的线格式是否与本版 pi 一致**（`set_steering_mode`/`set_follow_up_mode`/`set_auto_compaction`/`set_auto_retry`）：
   静态链核到了（`PiEngineApi` 的方法 + `call(...)` + pi 侧 `rpc-mode.ts:521-540`），但**没有真机跑过**。
   失败形态会是"标签一闪而过而模式没变"。需要真机 + 一个流式回合。
2. **`app.terminal.fontSize` 的 `Reload` 是否总是成立**：`TerminalSettings`/`TerminalPane` 的重新读取时机只在
   `remember(settingsStore)` 上，工作台离开再回来是否一定重新组合本机证不了（`docs/settings-review.md` §7.4 同一条）。
3. **`websocketConnectTimeoutMs` 的 `defaultValue = 15000`**：我核到 pi 的 getter 未设时返回 `undefined`（不是 15000），
   15000 是厂商 SDK 侧的默认。**没有逐字核 `packages/coding-agent/docs/settings.md` 的数字**。若 doc 写的就是 15000，
   这一行是诚实的"SDK 默认"；否则它是"把 SDK 默认冒充 pi 默认"。
4. **`defaultThinkingLevel` 无 `defaultValue`**：行显示"未设置"，而 pi 在选模型时会自己定一个思考等级（`core/sdk.ts:217`）。
   我没有核 pi 在"设置里没有这个键"时最终用的等级，因此没把"组摘要 ◐ 未设置"判成 bug（可能是诚实的"键未设置"）。
5. **`images.blockImages` 的 `NewSession`**：pi 的 `getBlockImages()` 在 `convertToLlmWithBlockImages` 里**每次转换都读**
   （`core/sdk.ts:270-273`，注释写"mid-session changes take effect"），我核到的是"每会话/进程一个 `SettingsManager`，
   所以文件写入到不了运行中的进程"，但**没有拿到"运行中 pi 会 `reload()` settings"的反证**。若 pi 在某条 RPC 上会 reload，
   这一行的 `NewSession` 就该改成 `Immediate`。
6. **`enableInstallTelemetry` 的消费点**：白名单写的是 `core/telemetry.ts:12`（经 `provider-attribution.ts:40`），
   我**没有**打进这两个文件核对；其余 22 条都核了 getter + 消费点。
7. **`app.sessions.resumeLast` 的 `RestartApp`**：我核到 `maybeResumeLastSession` 读它，**没有**核它的调用者一定在
   App 启动路径上（`docs/settings-review.md` 说是）。若它也在某个"切会话"路径上被读，标签要重判。
8. **B2 的端到端复现**：机制是代码级确定的（实例永久缓存 + 整份文档写回 + 无 invalidate），但**我没有在设备上跑**
   那三步。真机判据写在 §4。
9. **`PiSettingsStyle`/`DiagnosticsReport`/`LicensesScreen`/`PiCredentialScreen` 的界面层细节**：我只核了它们的**键与
   写盘接线**（凭证表单的 `PiEnginePreferences(agentDir = mirrorAgentDir)` 不带 `sharedStore`、`PiModelsScreen` 只读清单、
   诊断报告的两个 lambda 从 `PiRoot` 接），**没有**逐屏审视觉/文案。父代理若需要，建议单独派一轮。
10. **`PiCredentialService.preferences()` 不传 `sharedStore` 是否真的有害**：它的 `ownStore` 每次新建、读的是新文件，
    所以我没把它算成"丢更新"（§3.2 已注明）。但它意味着 §3.1 的锁只在这条路径上成立——如果有人在
    `PiCredentialService` 里缓存了 `preferences()` 的实例，就会变成第二个 B2。**这一条值得在真机上双写验证**。

---

## 6. 建议的批次划分（按文件归属，给父代理派活用）

在飞批次（本轮**不要碰**，会互相覆盖）：`PiSettingsRegistry.kt`、`PiSettingsRows.kt`、`PiSettingsStack.kt`、
`PiSettingsFileStore.kt`、`PiCredentialScreen.kt`、`PiModelsScreen.kt`、`DiagnosticsReport.kt`、`LicensesScreen.kt`，
**外加** `PiSettingsEditors.kt`（git status 显示它已在飞）与新增未跟踪的 `AppOnlySettingsStore.kt`。

| 批次 | 内容 | 涉及文件 | 与在飞批次的关系 |
|---|---|---|---|
| **A（不撞车，可立即做）** | B9 的 KDoc/字段处置、B12 的机制说明、B15 的滚动重置、B10 的事件合并、B13 的数字键盘、给 `settings-audit` 加"标题唯一""注册表没有 `[]` 键""每个注册字段都有读取方"三条断言 | `PiSettingsRegistry.kt`（只改注释/标题 → 仍属在飞文件）、`PiFileWatch.kt`、`SettingsGroupScreen.kt`、`PiSettingsAuditCheck.kt` | `PiSettingsRegistry.kt` 会撞 → 等 A 批之外的持有者；`PiFileWatch.kt`/`SettingsGroupScreen.kt`/harness 干净 |
| **B（接口变更，需一次做完）** | B1 + B6 + 3.5 的删键：给 `PiSettingsStore` 加 `remove`，`PiSettingsFileStore.remove` 走 `SettingsDocument.removePath`，`AppOnlySettingsStore.remove`，编辑器「恢复默认/清除」改走 `remove`，数字框的无效输入状态 | `PiSettingsStore.kt`、`PiSettingsFileStore.kt`（在飞）、`AppOnlySettingsStore.kt`（在飞/新增）、`PiSettingsEditors.kt`（在飞）、`PiSettingEditorHost.kt` | **必须等在飞批次落地**，否则一次接口变更会与 proroot 接线互相编译失败 |
| **C（并发正确性）** | B2 + B3：取 pi 的锁、临时文件名唯一化、`WorkspaceStore` 的单实例化/失效 | `PiSettingsFileStore.kt`（在飞）、`WorkspaceStore.kt`、`PiCredentialService.kt`（传 `sharedStore`） | 依赖 B 批（同一文件），且 `PiSettingsFileStore.kt` 在飞 |
| **D（校验）** | B5 + B7：抽 Android-free 的校验器，Object 容器的 `key = value` 校验与拒绝保存 | `PiSettingsEditors.kt`（在飞）、`PiSettingsJson.kt`、新文件（校验器），harness 的 `run_harness` 登记 | `PiSettingsEditors.kt` 在飞 |
| **E（文案/只读行）** | B4 + B8 | `SettingsGroupScreen.kt`、`PiSettingsRows.kt`（在飞）、`PiSettingsRegistry.kt`（在飞） | 撞 |
| **F（可测性，建议先行）** | §3.8：把临时文件名从 pid 换成计数器/UUID、把 store 的接口依赖移到 Android-free，新增 `settings-store` harness（覆盖原子写、损坏文件移开、兄弟键保留、双实例可见性） | `PiSettingsFileStore.kt`（在飞）、`tools/run-app-pure-checks.sh`、新 harness | 撞；但它是 B/C/D 的验证前提，价值最高 |

---

## 7. 本次没有做的事

- 第一轮（纯诊断）没有修改任何已有文件；没有跑 Gradle；没有跑 `tools/typecheck.sh` 或
  `tools/run-app-pure-checks.sh`。（第二轮之后的落地批次里，harness 用与脚本同样的 kotlinc 方式各跑过；
  `tools/typecheck.sh` 在 §10.4 跑过一次全量，结果与归属写在那里。）
- 对 `PiSettingsAuditCheck` 的五条断言，我是用等价的 Python 脚本在工作区**当前**文本上重放的
  （70 键 / 23 白名单 / 0 无读者 / 0 注释假消费者 / 0 重复 / 0 过期白名单 / 9 chip 可解析），
  并把 23 条白名单理由逐条对到 pi 源码。**真正的 harness 仍需 CI 跑一次**（注册表在被并发改动）。
- 逐键表的"App 消费者"以符号级 `rg` 为准；`Value` 行的"显示"也算消费者，我在表里写明了是"显示"还是"生效"。

---

## 8. 本批已落地（uncommitted，2026 补记）

第二轮：B1/B2/B3/B4/B5/B6/B8/B9/B10/B15 + 与 pi 不一致那批已实现，`settings-audit` / `settings-validation` /
`settings-store` / `models-inventory` / `pre-spawn` 五个 bare-JVM harness 全绿。逐条对应：

| bug | 落地位置（符号） | harness 判据 |
|---|---|---|
| B1 | `PiSettingsStore.remove`（接口）、`PiSettingsFileStore.remove`（作用域正确：项目文档显式有就删项目、否则删全局）、`AppOnlySettingsStore.remove`、`PiSettingEditorHost` 的 Number/Text 分支（`null`/`""` → `remove`）、`InMemoryPiSettingsStore.remove` | `settings-store`：`remove leaves the key absent (not null)`、`remove never writes JSON null`、嵌套删键剪父、`removing a project override deletes it from the project document` |
| B2 | `PiSettingsFileStore.Document`（按 canonical 路径进程级共享：一把锁 + `(size,mtime)` 戳 + 250ms 节流的重读）、`shared(...)`（按三元组单实例）、`forWorkspace` 走 `shared` | `settings-store`：`a second instance … sees the first one's write`、`a second instance's write does not erase the first one's key`、`an external change is picked up without invalidate()`、`concurrent writers to one document lose nothing` |
| B3 | `PiSettingsLock.withLock`（pi 的 `<file>.lock` + 10×20ms + 10s 陈旧窗口，best-effort）、`update()` 在锁内**重读**文件再改一个键、临时名 `TEMP_SEQUENCE` 每次唯一（去掉 `android.os.Process.myPid()`） | `settings-store`：`the lock directory is pi's \`<file>.lock\``、`a write leaves no temp file behind`、并发 40 键 4 线无丢失 |
| B4 | `PiSettingsRows`：`onClick = if (setting.readOnly) null else onOpen`（与 chevron 同条件）；`SettingsGroupScreen.openRow` 也拦；`PiSettingEditorSheet` 新增 `valueOverrides` 参数并用于 Text 初值 | 进不了 harness（Compose）；真机判据：点「pi 版本」不出现 sheet |
| B5 | `PiSetting.validateEntries` + `PiListEditorSheet(validate=…)`（不合法**拒绝保存并内联报错**）；判定复用 `PiSettingsValidation` | `settings-validation`：`line verdict: a bad compaction override line is rejected` 等 |
| B6 | `PiNumberEditorSheet`：文本是唯一真相（`initial?.toString() ?: ""`）、非整数拒绝并提示、越界**只提示不夹值**、留空=remove、保存前过 `piValueVerdict` | `settings-validation`：`parseEditedInt rejects a fraction/overflow`、`number verdict: out of range is reported with the bounds` |
| B8 | `themes` 改名「自定义主题目录」；`G_TERMINAL` 摘要改按 `textIn(...).isNotBlank()` 判；`httpIdleTimeoutMs` 说明补 pi 的 `disabled` | `settings-audit` 规则 6：`no two registered rows share a title` |
| B9 | 删 `PiSetting.depth`（12 行实参）与 `PiSetting.globAware`（6 行实参）+ KDoc 改实话；`emptyListLabel` **保留**（它真有读者：`PiSetting.display` 裸名读取，6 行用了非默认值）；`appLocalFile`/`app-prefs.json` **实现**（`app.*` 与含 `[]` 的键落 sidecar，pi 文档里的旧值仍可读、下一次写该键时清掉） | `settings-audit` 规则 8：`every PiSetting field has a reader`；`settings-store`：`an app.* key is not written into pi's settings.json` / `is written to the sidecar` |
| B10 | `PiDirectoryWatch`：inotify 与 ON_RESUME 都只投 conflated 信号，消费端 100ms 合并 → 一次改动一次回调；`Baseline` 构造与 `consume()` 移到 `Dispatchers.IO`，回调回到主线程；`PiSettingsStack.onChanged` 先在 IO 上预热文档再 bump epoch | 进不了 harness（FileObserver/Compose）；真机判据：改一次文件只重读一次、无主线程解析 |
| B15 | `PiSettingsStack` 层级状态全改 `rememberSaveable`；`SettingsGroupScreen` 按 `groupId` 复位滚动 | 进不了 harness（Compose）；真机判据：旋转后仍在原分组、换分组回到顶部 |
| pi 对齐 | `enabledModels` 的 `EffectiveKind` → `NewSession`（调用点在每会话重跑的 `createRuntime` 闭包内）；`PiModelInventory.matches` 加 pi 的子串回退、`*`/`?` 不跨 `/`、`[...]` 字符类（`hasUnsupportedGlobSyntax` 不再把 `[`/`]`/`[]` 内的 `!` 当不支持） | `models-inventory`：新增 `a bare substring matches a model id`、`a character class matches`、`a negated character class matches`、`a glob star does not cross a slash` |

**仍未做（明确记下）**：`PiSettingsAuditCheck` 规则 1 已剥注释，但它仍是**文本**判据（"键名作为字符串出现过"），
不是调用图；`settings-store` 覆盖不到"Compose 侧行在组合期读盘"这条（改由 `PiDirectoryWatch` 的 IO 预热兜住）。
**未验证**：上述 Compose 文件（`SettingsGroupScreen`/`PiSettingsRows`/`PiSettingEditorHost`/`PiSettingsEditors`/
`PiSettingsStack`/`PiSettingsInMemory`/`AppOnlySettingsStore`/`PiSettingsRegistry`/`PiSettingsJson`）本机没有编译过 ——
只做了嵌套注释检查与括号平衡（与 HEAD 的差值不变）；编译留给 CI。

---

## 9. 信息架构重排（第三轮，`applied (uncommitted)`）

用户那句「设置那一屏幕的所有东西这么乱呢？」的根因是**四种东西同形同色地混在一起**：pi 键（写
`settings.json`、pi 读）、app 偏好（`app.*`，我们自己读）、只读事实（版本/占用/运行时实际生效）、
动作·指路牌。这一轮只动**分组、顺序、section 归属、行标题/说明文案**（颜色/主题、键语义、默认值、
`EffectiveKind` 一律没动），把区分做在**分组与 section**上，并用两条机器规则把它锁住。

### 9.1 交付骨架（12 组 / 24 个 section / 70 行）

| 组 | section（行数）| 行（→ 说明种类）|
|---|---|---|
| **1. 模型与推理**（12 行）| **本机模型**（2）<br>已导入的模型（动作）, 本地模型（llama.cpp）（动作）<br><sub>搬动：app.localModels.manage: 节 凭证→本机模型</sub><br>**凭证**（2）<br>API Key（动作）, 订阅登录（本应用暂无入口）（动作）<br>**默认模型**（4）<br>默认厂商, 默认模型, 默认思考等级, 循环模型<br><sub>搬动：enabledModels: 节 循环模型→默认模型</sub><br>**逐模型与预算**（2）<br>逐模型思考等级, 思考预算<br><sub>搬动：modelThinkingLevels: 节 默认模型→逐模型与预算；thinkingBudgets: 节 默认模型→逐模型与预算</sub><br>**行为**（2）<br>隐藏思考块, 缓存未命中提示 | |
| **2. 系统提示词**（2 行）| **系统提示词**（2）<br>自定义系统提示, 追加系统提示 | |
| **3. 消息与网络**（6 行）| **送达**（2）<br>穿插模式, 后续模式<br>**网络**（4）<br>传输方式, HTTP 空闲超时, WebSocket 连接超时, HTTP 代理<br><sub>搬动：transport: 节 传输→网络</sub> | |
| **4. 上下文与压缩**（6 行）| **压缩**（5）<br>自动压缩, 保留 token, 保留最近 token, 逐模型覆盖, 分支摘要保留 token<br><sub>搬动：branchSummary.reserveTokens: 节 分支摘要→压缩</sub><br>**动作**（1）<br>立即压缩（动作） | |
| **5. 重试**（7 行）| **重试**（4）<br>自动重试, 最大重试次数, 基础延迟, 最大重试延迟<br>**Provider 重试（高级）**（3）<br>Provider 超时, Provider 重试次数, Provider 最大延迟 | |
| **6. 工具**（2 行）| **工具**（2）<br>内建工具, 工具输出默认展开<br><sub>搬动：defaultTools: 节 内建工具→工具；app.tools.expandByDefault: 节 展示→工具</sub> | |
| **7. 会话**（1 行）| **存储**（1）<br>启动续接最近会话 | |
| **8. 扩展与资源**（6 行）| **本地资源**（5）<br>扩展, 技能, 提示模板, 自定义主题目录, 技能命令<br>**资源包**（1）<br>资源包（只读事实） | |
| **9. 外观**（5 行）| **外观**（5）<br>主题, 字号微调, 消息密度, 显示时间戳, 思考块默认折叠<br><sub>搬动：theme: 节 主题→外观；app.appearance.fontScaleDelta: 节 排版→外观；app.appearance.messageDensity: 节 排版→外观；app.appearance.showTimestamps: 节 排版→外观；app.appearance.thinkingCollapsedByDefault: 节 排版→外观</sub> | |
| **10. 终端与 Shell**（7 行）| **Shell**（3）<br>Shell 路径, 命令前缀, npm 命令<br>**终端显示**（2）<br>终端字号, 键盘按键条<br><sub>搬动：app.terminal.keyBar: 节 按键条→终端显示</sub><br>**图片**（2）<br>图片自动缩放, 屏蔽图片<br><sub>搬动：images.autoResize: 外观→终端与 Shell；images.blockImages: 外观→终端与 Shell</sub> | |
| **11. 运行时与诊断**（14 行）| **运行时状态**（6）<br>pi 版本（只读事实）, Node 版本（只读事实）, 运行时占用（只读事实）, 引擎启动耗时（只读事实）, 唤醒锁状态（只读事实）, 运行时（实际生效）（只读事实）<br><sub>搬动：app.runtime.piVersion: 节 版本→运行时状态；app.runtime.nodeVersion: 节 运行时→运行时状态；app.runtime.rootfsUsage: 节 运行时→运行时状态；app.runtime.engineStartup: 节 运行时→运行时状态；app.runtime.wakeLock: 节 后台→运行时状态；app.runtime.prorootStatus: 节 运行时→运行时状态</sub><br>**进程**（5）<br>运行时加速（实验性）, 离线模式, 缓存保留策略, 不加载上下文文件, 后台保活<br><sub>搬动：app.runtime.proroot: 节 运行时→进程；app.runtime.keepAlive: 节 后台→进程</sub><br>**动作**（3）<br>重启引擎（动作）, 导出诊断报告（动作）, 紧急停止（动作）<br><sub>搬动：app.runtime.restartEngine: 节 进程→动作；app.runtime.diagnostics: 节 诊断→动作；app.security.emergencyStop: 安全与隐私→运行时与诊断</sub> | |
| **12. 安全与隐私**（2 行）| **信任与隐私**（2）<br>项目信任策略, 安装遥测<br><sub>搬动：defaultProjectTrust: 节 信任→信任与隐私；enableInstallTelemetry: G_ABOUT→安全与隐私</sub> | |

总行数：70；组数：12

（`（动作）` = `PiRowKind.Action`；`（只读事实）` = `readOnly = true`。这两个标记是给审查看的，
界面上靠的是它们**独占自己的 section** —— 见 9.3 的两条规则。）

### 9.2 每一条搬动与理由

| 搬动 | 从 → 到 | 理由（可核） |
|---|---|---|
| `app.localModels.manage` | 模型与推理/凭证 → 本机模型 | 它写的是 `models.json`（端点/模型定义）+ 占位凭证，不写 `auth.json`；与「已导入的模型」回答同一件事 |
| 凭证两行 | 组末 → 组首第二段 | 「先有凭证才有可选模型」是新用户的第一步（pi 的首配流程同序：`cli/setup.ts`） |
| `enabledModels` | 独立节「循环模型」→ 默认模型 | 与 `defaultModel`/`defaultProvider` 回答同一个问题（用哪些模型）；pi 的 `docs/settings.md` 也把它与模型选择同段 |
| `modelThinkingLevels`+`thinkingBudgets` | 默认模型 → 逐模型与预算 | 「默认值」与「逐模型覆盖/预算」是两层，混在一节里 `默认模型` 会有 6 行、两个概念 |
| `transport` | 传输（单行节）→ 网络 | 它和 `httpIdleTimeoutMs`/`websocketConnectTimeoutMs`/`httpProxy` 同属 pi `docs/settings.md` 的 Network 节；单行节消失 |
| `branchSummary.reserveTokens` | 分支摘要（单行节）→ 压缩 | 两类 token 预留（压缩 / 分支摘要）同一节；单行节消失 |
| `defaultTools`+`app.tools.expandByDefault` | 内建工具(1)+展示(1) → 工具(2) | 两个单行节合成一个真节 |
| `packages` | 本地资源 → 资源包（保持） | 它是**只读**行（开包管理页），不能和 5 行可编辑路径数组同节（规则 9 会拦） |
| `theme`+`app.appearance.*` | 主题(1)+排版(4) → 外观(5) | 单行节消失；组内只剩一类（可编辑的外观偏好） |
| `app.terminal.fontSize`+`keyBar` | 终端显示(1)+按键条(1) → 终端显示(2) | 两个单行节合成一个真节 |
| `images.autoResize`/`blockImages` | 外观/图片 → 终端与 Shell/图片 | pi 的 `docs/settings.md` 把 `images.*` 与 `terminal.*` 放同一节（`:215-226`）；外观组只留主题与排版 |
| `app.runtime.piVersion`…`prorootStatus`、`wakeLock` | 版本(1)+运行时(5)+后台(1) → **运行时状态**(6) | 六个**只读事实**独占一节；`版本` 单行节消失 |
| `app.runtime.proroot`、`keepAlive` | 运行时/后台 → **进程**(5) | 它们是**控件**，与只读事实分开（原先 proroot 与 4 个只读事实同节 = 用户抱怨的形状） |
| `app.runtime.restartEngine`、`diagnostics`、`app.security.emergencyStop` | 进程/诊断/安全与信任 → **动作**(3) | 三个 Action 独占一节：动作与设置不同形，也不该混在开关里 |
| `defaultProjectTrust`+`enableInstallTelemetry` | 信任(1) + 隐私(1) → **信任与隐私**(2) | 信任策略与安装遥测都是「这个应用/项目能对外做什么」的闸门；`G_ABOUT` 那个只有一行却叫「隐私与关于」的组消失 |
| 组名 | 提示词 → **系统提示词**；重试与网络 → **重试**（组里没有网络）；安全与信任 → **安全与隐私** | 组名与内容一致；「系统提示词」与它唯一的 section 同名（`G_PROMPTS` 的 id 与 `prompts` 键无关，KDoc 已写明） |
| `themes` 行名 | 主题 → 自定义主题目录（上一轮已做，本轮确认落定） | 与 `theme`（当前主题）区分；`settings-audit` 规则 6 拦重复标题 |

### 9.3 审计加强（可机检，已进 `PiSettingsAuditCheck`）

| 规则 | 判据 | 当前结果 |
|---|---|---|
| 9（新） | **一节一种**：任何 section 不得同时含「不可就地编辑的行」（`Action` 或 `readOnly`）与可编辑设置行 | PASS（24 个 section 全部同类）|
| 10（新） | 组内有多个 section 时，**不允许只有一行且那一行是可编辑设置的 section**（单行节只有在那一行是动作/只读事实时才合法） | PASS（只剩 3 个单行节：`压缩/动作`(Action)、`扩展与资源/资源包`(readOnly)、`会话/存储`(整组只有一节)）|
| 11（新） | 声明了但一行都没有的组、指向未声明组的行、两个组同名 | PASS（12 组都有行、行都指向已声明组、组名唯一）|
| 6（上一轮） | 行标题唯一 | PASS（`主题`/`自定义主题目录`）|

### 9.4 D52（给台账的文本，父代理粘贴）

> **D52 设置面的信息架构：先按"这行是谁的"分四类，再按 pi 的分节归属**
>
> 用户的「乱」不是文案问题，是四种东西同形同色地混在一起：pi 键（写 `settings.json`、pi 读）、
> app 偏好（`app.*`）、只读事实、动作/指路牌。本轮把区分做在**分组与 section**上（不动颜色、不动
> 任何键的语义/默认值/`EffectiveKind`）：70 行由 13 组 30 节收成 **12 组 24 节**。
> 只读事实独占 `运行时与诊断/运行时状态`（6 行），动作独占各自组的 `动作` 节（`上下文与压缩/动作`
> 1 行、`运行时与诊断/动作` 3 行），组内不再出现「一个开关 + 四个读数」的同一节。
> 单行节从 7 个降到 3 个（其中两个是动作/只读事实，一个是整组只有一节）。
> 组名与内容对齐：`重试与网络`→`重试`（组内没有网络）、`提示词`→`系统提示词`、
> `安全与信任`→`安全与隐私`；只有一行却叫「隐私与关于」的 `G_ABOUT` 并入后者。
> `images.*` 按 pi 的 `docs/settings.md:215-226` 移到 `终端与 Shell/图片`。
> 两条可机检规则进了 `settings-audit`：**一节不得混动作/只读事实与设置**、**多节组里不得有单行的
> 可编辑设置节**；另加"组与行互相声明、组名唯一"。因此这类重组不能再悄悄退化。
> 与 pi 源侧文档 `docs/settings-audit-pi-gap.md` §5.4 的偏差：那份骨架正文写「11 组」但列了 12 条，
> 且第 9 行把外观与终端合成「外观与终端」却只列 theme/排版/图片（漏了 Shell/终端显示/按键条）、
> 其搬动理由表又写「images 移到终端与 Shell」——两处自相矛盾。本轮取理由表：外观与终端仍是两组，
> `images.*` 进终端。结果 12 组不是 11 组。

### 9.5 真机判据（只有上机才看得出好）

1. 设置首页：12 行分组条目，**组名与内容一致**（点「重试」只看到 `retry.*`；点「系统提示词」只看到两行提示词；点「安全与隐私」看到信任 + 遥测）。
2. 运行时与诊断：第一屏是 6 行**只读**读数（pi 版本 / Node / 占用 / 启动耗时 / 唤醒锁 / 运行时实际生效），往下才是开关与动作 —— 读数不再是"混在开关里的第 3 行"。
3. 上下文与压缩：最下面「立即压缩」独占一段；运行时与诊断最下面「重启引擎 / 导出诊断报告 / 紧急停止」三行一段 —— 动作不再和开关同形相邻。
4. 终端与 Shell：图片两行与 Shell/终端显示同一屏（与 pi 文档一致）；外观组只剩主题 + 排版。
5. 每个分组的 section 头不再出现「1 项」（只读事实节与动作节除外，它们是整块的不同种类）。

---

## 10. 「扩展与资源」：从"五条空路径"改成"实际发现了什么 + 一个出口"（`applied (uncommitted)`）

用户真机反馈：设置 →「扩展与资源」点进去是空的，而他真正能看到资源的地方在别处（项目页资源段 /
包管理页）。核过的原因是**这一屏只列手填的额外搜索路径**（`extensions`/`skills`/`prompts`/`themes`
默认都空），而 pi 真正加载的是它**自动发现**的资源。结论：那四条路径行删掉，换成**只读事实**+
**一条出口**。

### 10.1 删了什么、留了什么（72 键）

| 处置 | 键 | 说明 |
|---|---|---|
| **删** | `extensions` | 手填的"额外扩展路径"数组，默认空 |
| **删** | `skills` | 同上（额外技能路径） |
| **删** | `prompts` | 同上（额外提示模板路径） |
| **删** | `themes` | 同上（自定义主题目录） |
| **留** | `packages` | 资源包（只读 + 开包管理页） |
| **留** | `enableSkillCommands` | 技能命令（开关） |
| **留** | `app.extensions.args` | 扩展启动参数（另一批加的行，未动） |
| **新增（只读事实）** | `app.resources.discovered.skills` / `.themes` / `.prompts` / `.extensions` | 实际发现的数量 + 来源分布 |
| **新增（动作）** | `app.resources.openFiles` | 「查看资源文件」→ 本栈已有的「Pi 文件」层级（`piFiles`），不新造导航 |

组内 section 变成四节（每节一种，规则 9/10 全过）：**实际发现**(4 只读) → **技能命令与扩展参数**(2
可编辑) → **资源包**(1 只读) → **动作**(1 动作)。

**能力没有减少**（这条写给以后的人）：删的是**设置页的行**，不是 pi 的键。`settings.json` 里的
`extensions`/`skills`/`prompts`/`themes` 仍然被 pi 读取，扫描资源的代码路径（`PiResourceDiscovery`、
`ExtensionLifecycle`、`WorkspaceResourceScan`、`PiPackageFilters`、`ProjectTrust`）一行没动。要加
资源有两条更直接的路：**「Pi 文件」屏**里直接编辑 `~/.pi/agent/settings.json`，或者把文件放进
标准目录（`~/.pi/agent/{skills,prompts,themes,extensions}`、工作区 `.pi/**`、`.agents/**`）——
pi 本来就自动发现它们，不需要登记路径。

### 10.2 数字从哪来（复用扫描器，不新写第二份）

- 扫描：`app.pi.ui.screens.WorkspaceResourceScan.scan(workspace, configDir, agentDir)` —— 项目页
  资源段用的**同一个**扫描器（纯磁盘读取，不需要引擎在跑）。设置栈在 IO 上调用它（`LaunchedEffect`
  按 `workspacePath`/`filesEpoch` 重跑），**没有**第二份扫描实现。
- 纯计算：`ui/settings/PiResourceFacts.kt`（Android-free）。同名去重保留优先级最高的来源
  （项目 `.pi` > `.agents` > `~/.pi/agent` > 已装包 > 扩展贡献，与 pi 的解析顺序一致），
  所以报的是"实际会生效的那一份来自哪"；同名冲突的展示仍归项目页资源段。
- 三种读数**分开且不许互相冒充**：

| 情况 | 行文本 | 为什么 |
|---|---|---|
| 扫到 N 个 | `3 个 · 项目 .pi 1 · ~/.pi/agent 2` | 只给数字不给来源，用户不知道在哪、要不要动 |
| 扫到 0 个 | `还没有发现任何资源` | `0 个` 会被读成"坏了/没装好"；真相是"还没有"，行说明里写了该放哪 |
| 读不到 | `读不到：<原因>` | 用 `0 个` 冒充读数正是这一批要消灭的形状 |
| 还没扫完 | `未读取` | "还没读"不等于"读不到"，也不等于"没有" |

### 10.3 分组摘要现在报什么

`G_RESOURCES` 摘要从「设置里 N 条扩展路径 · M 个资源包」（N 恒为 0，因为那个键没了）改成：

- 有扫描结果：`"2 个资源包 · 已发现 3 个技能 · 1 个主题"`
- 没扫描过（用户还没进过设置目的地）：`"2 个资源包"` —— **不提**那一句，而不是说 `已发现 0 个`。

机制：`PiResourceFactsCache`（写入方是设置栈那次真实扫描；读的人只有这个摘要）。摘要是设置首页
画的，它的签名拿不到扫描结果，所以只能是"最近一次扫描"；过期窗口是"切换工作区且停留在设置首页"
这段（重新进入设置就会重扫）。

### 10.4 审计与验证

`PiSettingsAuditCheck` 同步：没有任何 `piOwnedKeys` 涉及这 4 个键（无需移除），键数从 71 → **72**；
规则 9/10（一节一种、多节组不得有单行可编辑节）在新结构下全过；那次修掉的组摘要也不再有
"恒为 0 的数字"。

```
settings-audit      : OK (23 PASS)
audit: 72 registered keys (37 pi, 35 app): 49 read by this app, 23 declared pi-owned, 9 search hints, 21 PiSetting fields
settings-resources  : OK (all checks passed)   ← 新增 harness
pre-spawn           : OK
nested-comments     : OK (278 files)
```

`tools/typecheck.sh`（本机全量跑）：**`:app` 4 个 error，全部不在本轮改过的文件里**，我一个都没碰：

```
runtime/RuntimeProvisioner.kt:87:21: error: unresolved reference 'ensureAndroidGroups'      ← 运行时批次的在制品（文件 M）
ui/settings/DiagnosticsReport.kt:7:15 / :156:34 / :156:75: error: unresolved reference 'BuildConfig'  ← 另一批的文件
```

- 本轮所有文件（`PiSettingsRegistry`/`PiSettingsStack`/`PiResourceFacts`/…）**0 error** ——
  这一条比括号平衡强得多：Compose 侧真的过了一遍前端。
- `BuildConfig` 那三条是 **typecheck 的盲区**，不是代码错误：`BuildConfig` 由 AGP 生成，而
  `tools/typecheck.sh` 只为 `R` 生成 stub（`grep BuildConfig tools/typecheck.sh` 无命中），
  Gradle/CI 那边能编过。全树只有 `DiagnosticsReport.kt` 引用它。
- `ensureAndroidGroups` 那条**看起来是在制品/快照问题**：`private fun ensureAndroidGroups()` 就声明在
  同一个 `class RuntimeProvisioner` 的 `:748`，而 `:87` 在同一个类的 `ensureReady` 里；该文件当前是
  `M`（运行时批次正在改）。我不改它，只报告。

新 harness 的注册命令行（`tools/run-app-pure-checks.sh` 由父代理加）：

```
run_harness settings-resources \
  app.pi.ui.settings.PiResourceFactsCheckKt \
  "$ROOT/app/src/main/kotlin/app/pi/ui/settings/PiResourceFacts.kt" \
  "$ROOT/app/src/test/kotlin/app/pi/ui/settings/PiResourceFactsCheck.kt"
```

它钉住的是：同名去重与来源优先级、每类只数自己那一类、来源按优先级排序且**扩展贡献**不被折进全局、
三种读数与"未读取"四态互不冒充（尤其：空扫描与不可读都**不许**出现数字）、摘要行在没扫描/读不到时
返回 null（不给假 0）、缓存取最近一次。

### 10.5 真机判据

1. 点进「扩展与资源」：**不再有** `扩展`/`技能`/`提示模板`/`自定义主题目录` 四行；第一段是
   「实际发现」的四个只读读数（已发现的技能/主题/提示模板/扩展），值与**你实际装的东西对得上**，
   且带来源（`项目 .pi 1 · ~/.pi/agent 2` 这种）。
2. 一个资源都没有时，写的是「还没有发现任何资源」，行说明里写着该往哪放（`skills/` 等标准目录），
   **不是空屏**、也不是 `0 个`。
3. 扫描失败时写「读不到：<原因>」（例如引擎目录还没解包）；**任何情况下都不会显示 `0 个`** 冒充读数。
4. 「查看资源文件」能点到「Pi 文件」屏，返回键回到设置（这一屏是设置栈自己的层级）。
5. 那四条被删的行确实**不影响能力**：在「Pi 文件」屏里给 `~/.pi/agent/settings.json` 加一条
   `"skills": ["/some/dir"]`，pi 仍然会读它（键没删、扫描代码没动）；把文件放进
   `~/.pi/agent/skills/` 或工作区 `.pi/skills/` 也照样被自动发现，这一屏的「已发现的技能」数字
   会跟着涨（监视器 bump `filesEpoch` 后重扫）。

### 10.6 D56 文本（给台账，父代理粘贴）

> **D56 「扩展与资源」：删掉四条手填路径，改成"实际发现了什么" + 一个出口**
> 结论：设置页删掉 `extensions`/`skills`/`prompts`/`themes` 四行（都是"额外搜索目录"清单，手机上
> 更直接的路是往标准目录里放文件），这一组保留 `packages`/`enableSkillCommands`/`app.extensions.args`，
> 新增四个**只读事实行**（已发现的技能/主题/提示模板/扩展，带来源分布：项目 `.pi`、`.agents`、
> `~/.pi/agent`、已装包、扩展贡献）与一条**动作行**「查看资源文件」→ 本栈已有的「Pi 文件」屏。
> 为什么删：这四行只表达"额外路径"、默认空，用户点进来看到一屏空值，会以为这一屏没用；而 pi 的
> 资源是**自动发现**的 —— `~/.pi/agent/{skills,prompts,themes,extensions}`、工作区 `.pi/**`、
> `.agents/**`、已装包自带的，不需要在设置里登记路径。**能力没有减少**：`settings.json` 里这些键
> 仍然被 pi 读取（删的是行，不是键），要加就用「Pi 文件」屏直接编辑 `~/.pi/agent/settings.json`，
> 或者把文件放进上面那些标准目录。
> 数字来源：复用项目页资源段那个扫描器（`WorkspaceResourceScan`），纯磁盘读取、不需要引擎在跑；
> 同名按名字去重、来源取优先级最高的一份（与 pi 的解析顺序一致）。三种读数分开且**不许互相冒充**：
> `N 个 · 来源…` / `还没有发现任何资源` / `读不到：<原因>`，扫描未落地时显示「未读取」——**从不**用
> `0 个` 冒充读数。
> 组摘要：不再报那个恒为 0 的"扩展路径条数"，改成"资源包 N 个 · 已发现 …"（没有扫描结果时只报
> 资源包条数，绝不说 0）。审计同步：规则 9/10 在新结构下全过，键数 72。

### 10.7 未做 / 未验证

- Compose 侧（`PiSettingsStack.kt` 的扫描与覆盖、`PiSettingsRegistry.kt` 的新行）**本机没有编译过**：
  只做了括号平衡、结构解析（72 行、12 组、G_RESOURCES 4 节）、审计 23 条与 pre-spawn。编译结果见
  §10.4 的 `typecheck`（本机跑，日志在 `build/`）。
- `WorkspaceResourceScan` 是**跨批依赖**：它住在 `ui/screens/WorkspaceResources.kt`（滚动/工作区批次
  的文件，本轮不许改）。我只**调用**它，没有改它；如果那一批改了它的签名，这里要跟着改一行。
- 计数把"被包筛选/不自动加载"的资源也算作"已发现"（它们确实在磁盘上、pi 也确实看到了，只是没加载）。
  项目页资源段把它们显示为 muted；这一屏不做第二次状态分类。真要区分需要新的纯逻辑与文案。

---

## 11. 「工具」组的两个问题：List 行的"清空"语义 + `powershell` 预设（`applied (uncommitted)`）

### 11.1 清空的最终语义：**删键**（与 Text/Number 行一致）

**症状（真 bug）**：`PiSettingEditorHost` 的 List 分支无条件 `store.write(key, elementFromEntries(entries))`，
空 entries 产出空数组。对 `defaultTools` 这就是**写 `"defaultTools": []`** —— 而 pi 的实现是
`configuredDefaultToolNames ?? defaultActiveToolNames`（`core/sdk.ts:256-262`），**空数组不是 null**，
所以那是"一个内建工具都不开"。行上的 `emptyListLabel` 却写着「默认 read/bash/edit/write」——
**界面在说假话**（B1 那一族的漏网：Text/Number 已经改成删键，List 没有）。

**修法（选"删键"）**：

| 符号 | 改动 |
|---|---|
| `PiSettingEditorHost` 的 `PiRowKind.List` 分支 | `if (entries.isEmpty()) store.remove(setting.key) else store.write(...)`；KDoc 里逐行写明为什么（含每行的 pi 依据） |
| `PiListEditorSheet`（`PiSettingsEditors.kt`） | 保存按钮在列表为空时**改叫「恢复默认」**（点之前就知道结果）；两种容器的说明各加一句"清空并保存 = 删掉这项设置、回到 pi 的默认（不是写一个空列表/空对象）" |
| 注册表 7 条文案 | `defaultTools` 重写（默认 4 个 vs 可选 3 个 + powershell 说明 + "0 个工具"怎么表达）；`enabledModels`/`modelThinkingLevels`/`thinkingBudgets`/`compaction.modelOverrides`/`npmCommand`/`app.terminal.keyBar` 各加一句"清空并保存 = 回到默认（…）" |

**"一个内建工具都不开"仍然可达、但不在这条路上**：这是 pi 的 `defaultTools: []` 语义（与默认是两件事），
要它就按行说明去「Pi 文件」屏直接编辑 `settings.json`。理由：在手机上让 agent 一个工具都没有不是需要
一个 UI 入口的需求，而**留着一个"看着是恢复默认、实际把工具全关掉"的按钮**是确定的伤害。

### 11.2 所有 List/Array 行的自查（逐个给结论）

| 行 | 容器 | 空（`[]`/`{}`）在 pi / App 里的真实语义 | 与"未设置"相同？ | 结论 |
|---|---|---|---|---|
| `defaultTools` | Array | `configuredDefaultToolNames ?? defaultActiveToolNames`：`[]` **非 null** → **0 个内建工具**（`core/sdk.ts:256-262`） | **不同** | **本次修的真 bug**；清空→删键→pi 的 4 个默认；行说明写清"0 个"要走 `settings.json` |
| `enabledModels` | Array | `main.ts:788`：`modelPatterns && length > 0 ? resolveModelScope(…) : []`；`agent-session.cycleModel` 的注释写"Uses scoped models if available, **otherwise all available models**" | 相同（都=没有作用域→循环全部） | 删键行为一致；行标签「全部模型」为真，文案补一句 |
| `npmCommand` | Array | `core/package-manager.ts:1747-1753`：`!configuredCommand \|\| length === 0` → 默认 `{command:"npm",args:[]}` | 相同 | 同上；标签「默认 npm」为真 |
| `app.terminal.keyBar` | Array | App 侧 `TerminalSettings.keyBarOf`：absent → `defaultRows`；**空数组也 `return defaultRows`**；名字全解析不出也回默认 | 相同 | 同上；文案补一句 |
| `modelThinkingLevels` | Object | `getAllModelThinkingLevels()` = `{...(settings.modelThinkingLevels ?? {})}`，查表 `?.[key]` → `{}` 等价于没有覆盖 | 相同 | 同上 |
| `thinkingBudgets` | Object | `getThinkingBudgets()` 原样返回；消费侧 `options.thinkingBudgets?.[level] ?? default` → `{}` 等价于未设置 | 相同 | 同上 |
| `compaction.modelOverrides` | Object | `compaction?.modelOverrides?.[modelKey]` → `{}` 等价于未设置 | 相同 | 同上 |
| `packages` | Array | **只读**行：编辑器路径被 `hostActions` 拦下（开店包管理页）；pi 侧包清单 `?? []`，两者都是"没有包" | 相同 | 无编辑器路径，无需改动 |

**唯一写空容器的地方**：`grep elementFromEntries` 全树只有 `PiSettingEditorHost:134` 一处，且已落在
`else` 分支里；没有第二个写入方（`write("defaultTools"/…)` 之类 0 命中）。`inMemoryDefaultStore()`
会把 `packages`/`npmCommand` 的 `defaultValue`（空数组）种进**内存预览** store —— 那不是文件写入，
只影响"没接运行时的预览"。

### 11.3 `powershell`：从候选里去掉（保留说明）

- 证据：pi 的 `ToolName` 联合确实是 8 个（`core/tools/index.ts:95`：
  `read | bash | powershell | edit | write | grep | find | ls`），但 `powershell` 的实现
  `getPowerShellConfig()`（`utils/shell.ts`）在**非 Windows 上直接抛**
  "The powershell tool is only available on Windows."；我们的 guest 是 Linux rootfs + Node，没有 `pwsh`。
  给了这个 chip = 让用户选一个"第一次被调用就失败"的工具。
- 选择：**从 `builtinTools` 预设里删掉**（7 个候选），并在行说明里点明"pi 还有 powershell，但它只在
  Windows 上能跑，本应用不提供"。`builtinTools` 的 KDoc 写明依据，`settings-audit` 规则 14 守着
  （"预设里不得出现 powershell"）。
- **手输**仍可写出 `powershell`：List 行的自由文本是我们有意保留的能力（pi 接受任意工具名，用一个
  它不认识的只会静默少一个工具）。硬拦一个 pi 自己接受的名字等于发明规范；所以这里只把**我们能控的
  那个入口**（chip 列表）堵住，并在文案里说清后果。
- 默认 vs 可选的区分：行说明现在写「**默认只有 4 个：read、bash、edit、write**；可选的是再加
  grep、find、ls」，`emptyListLabel` 仍是「默认 read/bash/edit/write」，两个数字不再混淆。

### 11.4 门槛

```
settings-audit      : OK (26 PASS)   ← 新增规则 12/13/14
audit: 72 registered keys (37 pi, 35 app): 49 read by this app, 23 declared pi-owned, 9 search hints, 21 PiSetting fields
nested-comments     : OK (280 files)
pre-spawn           : OK
settings-resources  : OK（本轮未改它的输入；上一轮注册行待加）
```

`tools/typecheck.sh`（本轮全量重跑）：`:app` **3 个 error，全部是 `ui/settings/DiagnosticsReport.kt`
的 `BuildConfig`（typecheck 盲区：脚本只为 `R` 造 stub，`BuildConfig` 由 AGP 生成）**；
本轮改动的 4 个文件（`PiSettingEditorHost`/`PiSettingsEditors`/`PiSettingsRegistry`/`PiSettingsAuditCheck`）
**0 error**。（上一轮那条 `runtime/RuntimeProvisioner.kt` 的 `ensureAndroidGroups` 已由那个批次修掉。）

新增的三条审计规则（都不放宽既有规则）：

| 规则 | 判据 | 为什么可机检 |
|---|---|---|
| 12 | `PiSettingEditorHost` 的 Number 分支必须 `store.remove(setting.key)` | 钉住 §B1：`null` 不能写进文件 |
| 13 | List 分支必须同时出现 `entries.isEmpty()` 与 `store.remove(setting.key)` | 钉住本次修法：空列表=删键，不许再写 `[]`/`{}` |
| 14 | `builtinTools` 预设里不得出现 `powershell` | 钉住 11.3：不让用户选出这台机器上跑不了的工具 |

### 11.5 真机判据

1. **清空内建工具**（把 7 个 chip 全取消，或删光文本行）→ 保存按钮已显示「恢复默认」→ 点它 →
   行读数回到「默认 read/bash/edit/write」，`settings.json` 里 `defaultTools` 这个键**不存在**（不是 `[]`）。
2. **再开一个新会话**：pi 的工具集是**默认那 4 个**（read/bash/edit/write），不是 0 个 ——
   判据：让模型跑一次 `read` 或 `bash` 能成功；或直接看会话里工具清单。
3. **选 `grep`/`find`/`ls`** 后保存 → 新会话里这三个真的出现在工具列表里（让模型调用即知）。
4. **其它 List 行同样回默认**：清空「循环模型」→ 又变回全部模型可循环；清空「npm 命令」→ 回默认 npm；
   清空「键盘按键条」→ 回预设那排按键（这三条都是"空=未设置"的行，删键与写空值行为一致）。
5. **工具行文案**：一眼能分清默认 4 个与可选 3 个；chip 里**没有** `powershell`，而行说明交代了它为什么不在。

### 11.6 未做 / 未验证

- 「一个内建工具都不开」没有 UI 入口（有意）：只能去「Pi 文件」改 `settings.json`。若以后要放进设置页，
  必须是一个**独立**的、写明后果的开关，而不是复用「清空」。
- 手输 `powershell` 仍会被写进 `defaultTools`（pi 不会因此起不来，只是那个工具一用即失败）——
  见 11.3 的理由；真机上没有验证"手输 powershell 后工具列表长什么样"。
- 真机判据 1–5 未上机；Compose 侧只过到 typecheck 前端。
