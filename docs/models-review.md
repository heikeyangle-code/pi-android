# 模型这一块，与「外部改动 → 界面更新」的全面审查

| | 值 |
|---|---|
| pi（权威） | `/root/pi-src` @ `bbb61e34`，`packages/coding-agent` 0.85.1 |
| App | `/root/pi-android`（本文件写作时的工作树，含其他作者未提交的改动） |
| 触发 | 用户原话：「模型这块还有很多问题——导入过的模型在里面看不到，没有『已导入的模型』列表；设置页模型那一块感觉很差劲。」随后追加：「AI 改完相应的**所有**东西，设置里有的尽量都要更新。」 |
| 方法 | 每个论断都用 `grep`/`sed` 在 pi 源码与 App 源码上核过；四态标注：`pi 有 <file:line>` / `pi 无对应物（App 的决定）` / `pi 有但我们够不着（卡在哪）` / `一致或有意偏离` |
| 不改 | pi（`/root/pi-src` 只读）、载荷、`docs/known-gaps.md` |

---

## 0. 直接回答：「如果 AI 帮我改了配置文件，它会出现在设置页面里吗？」

**分三层答，因为答案不一样：文件层、App 界面层、引擎层。**

| 层 | 谁 | 改完立刻能看到吗 | 依据 |
|---|---|---|---|
| 文件层 | `models.json` 真的被改了 | 是（文件就是文件） | — |
| App 界面层 | 设置 → 模型 的清单 | **能（本次改动之后）**。直读文件，并用 inotify + 回前台比 mtime 触发重读 | 新代码：`PiModelInventory.kt`、`PiModelsScreen.kt`、`PiFileWatch.kt` |
| App 界面层（旧行为） | 设置页 | **看不到**。设置页里没有「已导入的模型」这东西；唯一能看到的地方是凭证表单里那个厂商的勾选行（`PiCredentialScreen` 的 `existingIds`） | 改动前的 `PiCredentialScreen.kt` + 空白的模型分组 |
| App 界面层 | 对话页「选择模型」 | **看不到**。那个列表的唯一来源是 `get_available_models` | `ui/chat/ChatSheets.kt`（列表 = `state.models`）；`PiSessionViewModel.kt:1633` 只做一次 `api.getAvailableModels()` |
| 引擎层 | pi 自己认不认这个模型 | **不认，直到重启引擎。** RPC 没有任何重读通道 | `core/model-runtime.ts:176`（`create`）与 `:699`（`refresh`）是 `ModelConfig.load` 仅有的两个调用点；`modes/rpc/rpc-mode.ts:490-493` 只读 `getAvailableSnapshot()`（`core/model-runtime.ts:422-424`，纯 getter）。启动期那一次 `void refresh(...)` 在 RPC 服务开始**之前发起**、不 await（15s abort），所以它只覆盖「引擎刚起来」那一瞬（`main.ts:922-934`） |

**所以答案是一句分叉的话：**

> **会。** 设置 → 模型 会显示这个新模型，并且**明确标出它现在的状态**：
> 「现在可用」= 引擎也已经认了；「等待重启」= 文件里有、引擎还没加载；「缺凭证」= 那个厂商还没有
> Key，pi 不会列出它的任何模型（`docs/models.md:34-36`）。
> 后两种都带一个下一步：前者的下一步是重启引擎（复用 `ExtensionLifecycle` /
> `EngineRestartCoordinator`），后者是去填凭证。
>
> 但**对话页的模型选择器**不会变——那是引擎的答案，只有重启引擎之后才变。这不是 App 的缺陷，
> 而是 pi 在 `--mode rpc` 下的语义（pi 文档说的「edit during session; no restart needed」是
> **TUI** 的行为：TUI 打开 `/model` 会调 `refresh`，见 `modes/interactive/model-catalog-refresh.ts:22`）。

**同一个问题对 `settings.json` 的答案**（AI 直接改 `enabledModels`/`defaultModel` 而不是走 App）：

- 旧行为：设置页显示**旧值**。`PiSettingsFileStore` 把两份文档各缓存一次，只有 App 自己写入
  或引擎重启（`PiSessionViewModel.attach`）才会 `invalidate`。
- 新行为：设置页的行会在**外部改动事件**（inotify 事件，或切回前台时 mtime/大小变了）之后重读，
  见 §3。
- 但 `enabledModels` 的**效果**要重启引擎：pi 在**进程启动**时把它解析成 `--models` 的作用域
  （`main.ts:788-791`，而 `buildSessionOptions` 在 `main.ts` 里只有 `:797` 一处调用），
  Ctrl+P 循环用的就是那份 `_scopedModels`（`agent-session.ts:1717`）。所以那一行的
  `EffectiveKind` 已从 `NewSession` 改成 `RestartEngine`——**原来的标注是错的**。

**`auth.json` 也是同样的结论，理由值得写下来，因为直觉上容易判错**：pi 的凭证读取**是**
revision-checked（`core/auth-storage.ts:341-342`，`getFileRevision`），所以外部加进去的 Key
pi 会读到；但 `get_available_models` 回答的是 `snapshot.available`，那个快照只在可用性刷新里
重建（`core/model-runtime.ts:290-313`），而 RPC 侧只调用 getter（`rpc-mode.ts:473`、`:491`）。
**「读到了新 Key」和「愿意列出这个模型」是两步**，RPC 下只有重启能做第二步。

---

## 1. 模型这一块：逐项核对

| 项 | 改动前的现状 | pi 的出处 | 判定 | 处置 |
|---|---|---|---|---|
| `models.json` 何时被读 | App 只在文档里写了「要重启」，用户看不到为什么 | `pi 有 core/model-runtime.ts:176`（`create`）、`:699`（`refresh`）——`ModelConfig.load` 仅有的两个调用点 | 一致 | 保留；把「为什么」变成界面上的一行状态（新增 `PiModelsScreen`） |
| RPC 有没有重读通道 | 没有 | `pi 有 modes/rpc/rpc-mode.ts:490-493` 只读快照；`get_available_models` 不触发 refresh。启动期一次 `void refresh(...)`：`main.ts:925-936` | 一致 | 不发明重读；用「等待重启」+ 重启按钮 |
| pi 的文档说「打开 `/model` 就重载」 | App 的 KDoc 已注明那只对 TUI 成立 | `pi 有 docs/models.md:92`；TUI 侧 `modes/interactive/model-catalog-refresh.ts:22` 调 `refresh` | 一致 | KDoc 保留并更新引用行号 |
| `applyModelsJson` 的同 id 条目=整条替换 | 已修（§M11/§M13） | `pi 有 core/provider-composer.ts:203-209`（`models[existingIndex] = model` 否则 `push`） | 一致 | 不动写入规则 |
| 省略字段被填成 pi 的默认值 | 已修（只声明 pi 不知道的） | `pi 有 core/provider-composer.ts:150-166`（`input ?? ["text"]`、`contextWindow ?? 128000`…） | 一致 | 不动 |
| `modelOverrides` 是合并 | App 只保留、不编辑 | `pi 有 core/provider-composer.ts:106-118` | 一致（有意不做编辑器） | 清单里把它标成「覆盖」，与「手写申报」区分开 |
| provider 级字段会套到每个模型 | 已修 | `pi 有 core/provider-composer.ts:181-200`（块至少要有一个键）、`:203-209` | 一致 | 不动 |
| pi 的目录 `models-store.json` | 只有凭证表单读它 | `pi 有 packages/ai/src/models-store.ts:3-14`（形状）、`core/model-runtime.ts:180`（路径）、`core/agent-session-services.ts:142`（`modelsPath` 的父目录） | 一致 | 清单把它作为「pi 目录」来源标出来 |
| 可用模型按凭证过滤 | 只在 KDoc 里 | `pi 有 docs/models.md:34-36`；`core/model-runtime.ts:281`（`available = all.filter(configuredProviders.has(...))`） | 一致 | 清单的第三种状态「缺凭证」直接对应它 |
| `settings.json` 的三个「选择」键 | 已有编辑器 | `pi 有 core/settings-manager.ts:108`（`defaultProvider`）、`:109`（`defaultModel`）、`:139`（`enabledModels`）；`:1316`、`:1325-1326` 的读写 | 一致 | 不重做编辑器；清单页只做「打开那一行」 |
| `enabledModels` 是**模式**不是 id 清单 | 注册表描述里说了「支持通配符」 | `pi 有 core/model-resolver.ts:295-317`（剥 `:思考等级` 后对 `provider/modelId` **或**裸 id 做 minimatch）、`cli/args.ts:60`（合法思考等级） | 一致 | 纯逻辑实现 `*`/`?`；其余 glob 语法**不猜**，报成「无法判断」 |
| `enabledModels` 什么时候生效 | 注册表标 `NewSession` —— **错** | `pi 有 main.ts:788-791`（进程启动时解析一次）+ `:797`（`buildSessionOptions` 唯一调用点）+ `agent-session.ts:1717`（循环用 `_scopedModels`） | **一致或有意偏离 → 实为缺陷** | 改成 `EffectiveKind.RestartEngine`，描述里直说 |
| `auth.json` 的 revision check | KDoc 说「外部加的 Key 不用重启就认」—— **半错** | `pi 有 core/auth-storage.ts:341-342`（读到新内容）但 `core/model-runtime.ts:290-313` 的快照只在 refresh 里重建，RPC 无触发（`rpc-mode.ts:473`、`:491`） | **一致或有意偏离 → 实为缺陷** | 改 KDoc；界面上统一按「要重启」说 |
| 凭证表单「很差劲」 | 编辑一个已配好的厂商必须重新粘贴 Key；厂商列表不显示哪些已配置；模型行的来源只有一句「pi 元数据：…」 | `pi 无对应物（App 的决定）`：pi 没有凭证编辑器（`RpcCommand` 里没有 login/logout，`rpc-types.ts:20-74`），这一页整个是 App 的 | 有意偏离 | 留空=沿用已保存凭证；厂商行标「已配置」；模型行标来源（扫描到 / pi 目录里有 / 已保存 / 手动填写） |
| 「已导入的模型」列表 | **没有** | `pi 无对应物（App 的决定）`：pi 从不列「你导入过什么」，`get_available_models` 只列**现在可用**的 | 有意偏离（必须能说出理由：问不到，所以 App 读文件拼） | 新增 `PiModelInventory` + `PiModelsScreen`，来源全部是文件与引擎 |
| 「文件里有、引擎没加载」的判定 | 不存在 | `pi 有但我们够不着`：没有任何命令能回答「引擎读过这个文件了吗」 | 卡在哪：RPC 只有「现在列不列它」 | 用可观测的近似：有凭证 + 引擎列表里没有 = 等待重启；边界与误报条件写在 `PiModelInventory` KDoc 里 |
| `models-store.json` 的路径/形状断言 | 无 | `pi 有 package.json` 载荷里就是这几个字符串 | 一致 | `tools/pi-contract.mjs` 新增断言（§5） |
| 「运行期改 `models.json` 不生效」的断言 | 无 | `pi 有`（见上） | 一致 | `tools/pi-contract.mjs` 新增**行为**断言（§5） |

---

## 2. 外部改动 → 界面更新：逐来源审查（用户追加的要求）

「外部」= App 之外：pi 自己、终端里的命令、AI 通过工具改配置。

| 外部改动 | 对应界面 | 改动前会不会更新 | 为什么（`file:line`） | 本次处置 |
|---|---|---|---|---|
| `models.json`（增删改厂商/模型） | 设置 → 模型与推理、对话页模型选择器 | **不会**（没有任何地方列出已导入模型；选择器只读引擎快照） | `ui/chat/ChatSheets.kt`（`models` 来自 `get_available_models`）；`PiSessionViewModel.kt:1633` | 新增设置 → 模型：直读 + 监听 + 状态；选择器加一句「列表来自运行中的引擎，状态看 设置 → 模型」 |
| `auth.json`（凭证增删改） | 同上（「已配置/未配置」） | **不会**（凭证表单只在进入时 `prefill` 一次） | `PiCredentialScreen` 的 `LaunchedEffect(presetId)` | 清单页把「缺凭证」做成模型级状态；表单的厂商行标「已配置」、Key 留空=沿用 |
| `settings.json`（**用户/AI 直接改**，全局与项目两份） | 设置页每一行的当前值 | **不会**：文档缓存到下一次 App 自己写入 | `app/src/main/kotlin/app/pi/settings/PiSettingsFileStore.kt:52-54`（三个 `*Cache` 字段）、`:104`（`invalidate()` 只在 App 写入/引擎重启时被调用：`PiConfigFiles.kt` 的 `selectModel`、`PiSessionViewModel.kt:606` 的 `invalidateSettingsCache`，后者由 `attach` 在 `:811` 调用） | 设置栈装 `PiDirectoryWatch`：外部事件 → `onExternalSettingsWrite()` 丢掉缓存 + `filesEpoch++` 让分组/搜索页的行重读（`SettingsGroupScreen.freshness`、`SettingsSearchScreen.freshness`） |
| `<agentDir>/extensions/`、`skills/`、`prompts/`、`themes/` | 设置 → 扩展与资源、资源包页 | **进入页面时会**（不是实时） | `PiPackagesHost.kt:184`（`LaunchedEffect(Unit) { controller.refresh() }`）+ `:208`（刷新按钮） | 不改：`refresh()` 会跑 `pi list`（一个 guest 子进程），把它挂到 inotify 上就是"空浪费"的对立面。设置分组里这四行的**值**（`settings.json` 的数组）会随上一行的机制更新 |
| `<agentDir>/sessions/`（新会话、重命名、删除） | 会话列表 | **进入页面时会** | `ui/screens/SessionsScreen.kt:101`（`LaunchedEffect(Unit) { session.refreshSessions() }`）+ `:127`（刷新按钮）；`PiSessionStore.list` 无缓存（`PiSessionStore.kt:85`） | 不改，并且在 `SETTINGS_WATCHED` 里**故意不列 `sessions/`**：一个回合里它被反复写，算进来等于让设置页跟着对话节奏重组 |
| `trust.json` | 安全与信任、项目信任提示 | **进入页面时会** | `PiPackagesHost` 的 `readTrust()` 每次 `refresh()` 里都读文件（`TrustRepository.read()` 无缓存，`TrustRepository.kt:91`） | 不改；`trust.json` 已进 `SETTINGS_WATCHED`（设置页有一行显示默认信任策略） |
| `models-store.json`（pi 自己刷新的目录） | 模型能力展示 | **不会**（表单的 `catalogModels` 每次调用都读文件，但表单不会重进） | `PiCredentialService.catalogModels`（无缓存） | 清单页把它当「pi 目录」来源，并跟其它三份文件一起监听 |
| 运行时树 / 引擎版本（`RuntimeFacts`） | 设置 → 运行时与诊断 | **不会**（在设置栈里只读一次） | `PiSettingsStack` 的 `LaunchedEffect(runtimeFacts) { facts = withContext(IO) { runtimeFacts.read() } }` | 不改：内容是运行时目录与引擎进程的事实，引擎重启时设置栈通常已离开组合；这是**已知边界**，记在 §6 |
| pi 的命令/技能/模板列表 | `/` 面板 | 会（引擎事件驱动） | `PiSessionViewModel.refreshCommands()`（`:1650`）在 `attach`、每个 `agent_settled` 与写 `enableSkillCommands` 后调用 | 不改：这里有真正的信号（引擎事件），不需要猜 |
| 主题文件 | 外观 | 会（写设置时与应用重启时） | `refreshTheme()` 在 `:560`、`:592`、`:883` 被调用 | 不改 |

---

## 3. 一套统一做法（不是每个面各打一个补丁）

**三条，全部落在两个新文件里：`packages/PiFileStamps.kt`（判据，纯逻辑）与
`ui/settings/PiFileWatch.kt`（时机，一个 Composable）。**

### 3.1 判据：`(大小, mtime)`

`PiFileStamps.of(file)` = `"length:lastModified"`，不存在 = `null`（「没有」和「空」必须区分）。
`Baseline.consume()` 是**消费式**的：比较并同时更新基线，所以同一次变化只报一次，调用者不需要
自己记上次的指纹。

为什么不是内容哈希：那等于每次都读文件。**这台机器上的一切都建立在「不要空浪费」上**，而
`stat` 与「读文件 + 建 JSON 树」差三个数量级。代价与它**不认识**的情况（内容变了但大小与
mtime 都没变）写在 KDoc 里：同一个进程内的 App 写入必须自己通知——`PiSettingsFileStore.write`
本来就是这么做的。

### 3.2 时机：两个，都不是定时器

| 时机 | 机制 | 什么时候装 / 停 | 代价 |
|---|---|---|---|
| 外部写入 | `FileObserver`（inotify），**目录级** | 只在用到它的页面处于组合里时 `startWatching`，`onDispose` 就 `stopWatching` | 不动文件时**零**唤醒；一个目录一个 inotify watch |
| 切回前台 | `ON_RESUME` 时比一次 mtime/大小 | 与页面同生命周期 | 几个 `stat`（微秒级），且只在真的变了才回调 |
| 进入页面 | 各页原有的 `LaunchedEffect(Unit)` | 已有 | 已有 |

**为什么监视目录而不是文件**：App 写这些文件是原子的（写临时文件 + `rename`，
`PiConfigFiles.write`），`rename` 换掉 inode，而 inotify 的监视挂在 inode 上——监视文件会在
自己写完之后失聪。目录级监视不受影响（事件带子文件名来）。

**掩码不用 `ALL_EVENTS`**：那包含 `OPEN`/`ACCESS`/`CLOSE_NOWRITE`，即 App 自己的每一次读——
一个被自己的读者触发的 watch 就是死循环。

**为什么 `ON_RESUME` 不是冗余**：Android 在内存紧张时会静默丢掉 inotify 监视（已知平台行为），
所以生命周期这一层是兜底。两层都只在判据为「变了」时回调，**回调本身不解析**：解析是页面收到
通知之后按需做的（`PiModelsScreen` 在 IO 线程上重读并重组）。

### 3.3 谁在用

| 界面 | 监听 | 变了之后做什么 |
|---|---|---|
| 设置栈（分组页 + 搜索页） | `<agentDir>` 与 `<workspace>/.pi` 里的 `settings.json`、`models.json`、`auth.json`、`models-store.json`、`trust.json`、`extensions`、`skills`、`prompts`、`themes`（前缀匹配，`PiSettingsStack.kt` 的 `SETTINGS_WATCHED`） | `onExternalSettingsWrite()` 丢缓存 + `filesEpoch++`：分组行与搜索结果的列表被重建，因此重读值 |
| 设置 → 模型 | 同上那四份文件（`PiCredentialService.MODEL_FILES`） | 200ms 合并后重读四份文件并重组清单 |
| 不监听 | 会话列表、资源包页、凭证表单、`RuntimeFacts` | 见 §2 的「不改」列与理由 |

**`filesEpoch` 为什么必须存在**：行是在 composition 里 `store.read(...)` 的，
`PiSettingsFileStore.invalidate()` **本身不会触发重组**。只丢缓存等于什么都没发生——这是这个
仓库里「以为自己修好了」的典型形状，所以这里把「丢缓存」和「让行重读」两件事写在一起并注明。

### 3.4 一致性边界（界面上直说，不让用户猜）

| 改动 | 生效方式 | 界面上怎么说 |
|---|---|---|
| `models.json` / `auth.json` | **重启引擎** | 清单页「等待重启」+ 重启按钮；选择器空态与页脚 |
| `enabledModels` | **重启引擎**（不是新会话） | 清单页「循环模型在引擎启动时确定，改完要重启引擎才会生效」；注册表那一行的描述与 `RestartEngine` 徽章 |
| `settings.json` 的一般键 | 多数即时或新会话 | 各行原有的 `EffectiveKind` 徽章（不动） |
| 系统提示词 / 进程开关 | 新进程 | 已有的 `app.runtime.*` 行 |

重启永远走同一套机器：`ExtensionLifecycle`（状态机）+ `EngineRestartCoordinator`（执行者）。
本次还把**设置栈里的两份**（厂商凭证页、模型页）收敛到**同一份** `ExtensionLifecycle`——它们
写同一批文件，各记一份「有待重启的变更」就会出现一页说「需要重启」、另一页说「没有待重启」。

---

## 4. 本次改动

### 4.1 新增的纯逻辑（进 bare-JVM harness）

- `app/src/main/kotlin/app/pi/packages/PiModelInventory.kt`（新）
  四份文件原文 + 引擎的回答 → 厂商/模型清单。判定：**来源**（pi 目录 / 手写申报 / 覆盖，可叠加）、
  **状态**（现在可用 / 等待重启 / 缺凭证 / 无法判断）、**启用**（`enabledModels` 的模式匹配）、
  **默认**。glob 只实现 `*`/`?`；`{}`/`[]` 等语法**不猜**，进 `unjudgedPatterns`。
- `app/src/main/kotlin/app/pi/packages/PiJsonComments.kt`（新，从 `PiConfigFiles` 抽出）
  `models.json` 允许注释（`core/model-config.ts:267` 先 strip 再 `JSON.parse`），现在有两个
  读者（写入端与清单），一份实现才不会互相看不见对方的模型。`PiConfigFiles.stripJsonComments`
  委派给它，签名不变。
- `app/src/main/kotlin/app/pi/packages/PiFileStamps.kt`（新）
  外部改动的轻量判据，见 §3.1。
- `app/src/test/kotlin/app/pi/packages/PiModelInventoryCheck.kt`（新）
  60 条检查，分五组：来源 / 状态 / 选择 / 坏输入 / 指纹。登记为
  `tools/run-app-pure-checks.sh` 的 `models-inventory`。

### 4.2 新增的界面

- `app/src/main/kotlin/app/pi/ui/settings/PiModelsScreen.kt`（新）
  设置 → 模型。摘要行、「等待重启」卡片（带重启按钮）、默认模型 / 循环模型的入口（打开注册表里
  已有的编辑器，不重做编辑逻辑）、每个厂商一张卡：凭证状态、模型列表（来源 + 状态标签）、
  「导入模型」按钮。厂商模型多时默认收起，只留"有事要做"的行。
- `app/src/main/kotlin/app/pi/ui/settings/PiFileWatch.kt`（新）
  `PiDirectoryWatch`，见 §3.2/§3.3。

### 4.3 改动

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `packages/PiCredentialService.kt` | 新增 `inventory()`、`watchedDirectories()`、`MODEL_FILES`；`save()` 的**留空 Key = 沿用已保存凭证**规则；`prefill()` 多返回 `credentialPresent`；类 KDoc 里改正 auth 的结论 | 「加一个模型」不该要求重新粘贴 Key；清单必须直读文件、不进 `PiSettingsFileStore` 的缓存 |
| `packages/PiConfigFiles.kt` | `PiAuthStorage.hasAnyEntry()`（任何类型，含 oauth）；`stripJsonComments` 委派给新文件 | 留空不动凭证的前提是「本来就有凭证」，而 `read()` 只看 `api_key` |
| `ui/settings/PiCredentialScreen.kt` | 共用的 `ExtensionLifecycle`/`EngineRestartCoordinator`（可注入）；厂商行标「已配置」；Key 提示改为「留空沿用/粘贴则替换」；模型行改成 `candidateMeta()`（来源 + pi 知道什么）；删掉那句已经不存在的「默认值」说法 | 用户说「很差劲」的地方：这里四处都在撒谎或含糊（留空会丢 Key、不知道哪些配过、模型来源一句话、提到一个不存在的标签） |
| `ui/settings/PiSettingsRegistry.kt` | 新增 Action 行 `app.models.inventory`（G_MODEL 的第一行，分组「本机模型」）；`enabledModels` 的 `effective` `NewSession → RestartEngine` + 描述 | 让「已导入的模型」一眼可达；修掉一条错的生效时机 |
| `ui/settings/PiSettingsStack.kt` | `models` 分支；`hostActions["app.models.inventory"]`；一份 `ExtensionLifecycle`/`EngineRestartCoordinator` 传下去；`PiDirectoryWatch` + `filesEpoch`；`SETTINGS_WATCHED` | §3 |
| `ui/settings/SettingsGroupScreen.kt`、`SettingsSearchScreen.kt` | `freshness: Int = 0`，进 `remember` 的 key | 缓存失效本身不触发重组，见 §3.3 |
| `ui/chat/ChatSheets.kt` | 选择器空态与页脚各一句：列表来自运行中的引擎，导入状态看 设置 → 模型 | 用户在这个界面才发现「导入过的模型看不到」，要在这里给他一条路 |

### 4.4 契约断言（`tools/pi-contract.mjs`）

| 断言 | 为什么值得钉住 | 失败时打印的后果 |
|---|---|---|
| `models-store.json` 这个名字仍在钉住的引擎里 | App 是**重建**这个路径的（`PiModelCatalog`），改名会让读取器对所有厂商静默返回空表——这是设计（「宁可沉默也不猜」），所以别的检查不会发现 | 回去读 `core/model-runtime.ts` 的 `FileModelsStore` 构造，改 `PiModelCatalog` 与 `PiCredentialService.catalogModels` |
| **运行期改 `models.json` 真的不生效**（同一个活着的进程里，写完文件再问一次，新模型必须**不在**） | 整张「等待重启」的界面都是这条事实；如果 pi 哪天能在会话里刷新，那句「要重启」就是在骗用户重启 | 回去读 `core/model-runtime.ts` 的 `refresh` 调用点，然后要么改用刷新命令、要么删掉这段 UI |
| 同一份文件在新进程里真的会被读到（新模型**在**） | 同一事实的另一半 | 这次失败说明探针本身错了（文件或凭证），不是 App 错 |
| 新 id 是**追加**而不是合并进已有条目 | 区分 `applyModelsJson` 的两半（替换 vs push） | 读 `core/provider-proposer.ts` 的 `applyModelsJson` |

新增的行为断言需要一个**活着的** pi 进程（写完文件再问一次），所以脚本里加了一个小的
`startPi()`（`spawn` + 行分帧 + 按 `id` 等应答 + 30s 上限）。它是一个**反对**的断言，所以
失败信息写明了它不依赖的假设：两个请求由同一个进程按顺序回答，从不回答的进程会让这条断言以
「a live engine answers…」失败，而不是安静通过。

---

## 5. 四态标注汇总（哪些是 App 的决定、哪些够不着）

| 形状 | 本项目里的例子 |
|---|---|
| `pi 有 <file:line>` | `models.json` 只在启动/refresh 读、RPC 无重读通道、`applyModelsJson` 的替换与 `modelOverrides` 的合并、`models-store.json` 的形状与路径、`enabledModels` 的模式语法与进程启动解析、凭证按 auth 过滤、`auth.json` 的 revision check |
| `pi 无对应物（App 的决定）` | 整个凭证表单与设置 → 模型的界面；「已导入的模型」这张清单本身；`PiFileStamps`/`PiDirectoryWatch`；`candidateMeta` 的来源措辞；「留空=沿用凭证」的规则；`enabledModels` 的 glob 只实现 `*`/`?` |
| `pi 有但我们够不着（卡在哪）` | 「引擎读过这个文件了吗」没有任何命令能回答（只能用「现在列不列它」近似）；`login`/`logout` 没有 RPC 命令（`rpc-types.ts:20-74`），所以 OAuth 与凭证编辑器没有 pi 侧通道；扩展的资源刷新没有 RPC 触发（`get_commands` 读缓存，`RefreshSemantics` 里已有完整考证） |
| 一致或有意偏离 | 不申报 pi 自带目录的厂商（§M13 的收敛，一致）；`enabledModels` 只实现 `*`/`?`（偏离，且**报出来**）；清单页只打开注册表里的编辑器而不在页内编辑（有意，避免两份规则） |

---

## 6. 未验证项（都需要设备，写了怎么测）

1. **inotify 在真机上的行为**（本次新增的机制）：在设置 → 模型 打开的情况下，用 工作区 → 终端 改
   `<agentDir>/models.json`（加一个 id），看清单是否在 ~1 秒内出现那一行并标「等待重启」。
   再按 Home 键切出去、用别的 App 改文件、切回来，看是否更新（`ON_RESUME` 那条路）。
   判据：两条路都更新；且**页面不可见时**不该有任何 CPU 活动（`top`/`dumpsys cpuinfo` 看本 App
   的 CPU 占比应为 0 附近）。
2. **重启后是否真的生效**：在清单页按「重启引擎」，确认（a）引擎重启成功、当前回合没有被
   偷偷杀掉；（b）重启后那一行从「等待重启」变成「现在可用」；（c）对话页选择器里出现它。
3. **凭证留空不再丢 Key**：给一个已配置的厂商（例如 DeepSeek）只加一个模型、Key 留空、保存，
   然后看 `auth.json` 里那条 `key` **没有变**，且 pi 重启后仍然可用。
4. **`enabledModels` 的生效时机**：改一次循环列表、**不**重启引擎，按 Ctrl+P 确认范围没变；
   重启引擎后确认范围变了。这条是本次唯一"改渲染逻辑"的断言（`NewSession → RestartEngine`），
   值得实测一次。
5. **外部改 `settings.json` 后设置页的值**：在设置 → 模型与推理 打开时改 `defaultModel`，
   看那一行是否更新（这是 `filesEpoch` 的直接判据）。
6. **大模型的清单性能**：给 OpenRouter（目录很大）配一个凭证，打开 设置 → 模型，确认首屏不卡
   （解析在 IO 线程，且厂商默认收起）；这是本次唯一可能变慢的操作。
7. **`tools/pi-contract.mjs` 的新断言**：**本次没有在本机跑过**（用户要求本批不在本机运行任何
   东西，CI 会跑；`ci.yml:294`）。跑法：
   `node tools/pi-contract.mjs`（或 `--pi <已安装的包目录>`；本机 `/tmp/pi-contract-install/` 下已经
   有装好的 0.85.1，是本次为核对 pi 行为装的），判据是 `pi contract: OK`，且
   `PASS models.json edited while the engine runs really does need a restart`。
8. **`filesEpoch` 与 Compose 的重组**：typecheck 不跑 Compose 编译器，所以「`freshness` 进
   `remember` key 会真的重建行」这件事只有真机能证明（第 5 条的判据就是它）。

**TypeScript 侧的自检**：`node --check tools/pi-contract.mjs` 通过（语法层面）；行为断言没有
执行过（第 7 条）。

---

## 7. 自检

**本机未编译（用户要求）。这一批由 CI 编译验证。**

理由写在这里，免得下一个人以为是漏了：这台"开发机"就是用户本人的手机，`kotlinc` 一次要
~1GB 堆，编译期间手机卡到不能用。用户已经就此抱怨过两次，并在本轮明确要求**不运行**
`tools/typecheck.sh`、`tools/run-app-pure-checks.sh`、任何 `kotlinc`/`javac`/Gradle 命令，
**包括后台运行**。所以本次改动**没有任何本机编译或测试结果**，下面的东西按这个前提读：

| 命令 | 谁来跑 | 覆盖什么 |
|---|---|---|
| `bash tools/typecheck.sh` | CI（`ci.yml` 的 APK / protocol job 会编全部代码） | `app/src/main/kotlin` 与 `:rpc` 的类型与符号（不含 Compose 编译器的 composable 规则） |
| `bash tools/run-app-pure-checks.sh` | CI（`ci.yml` 的 `pure-checks` job，`.github/workflows/ci.yml:268`） | 新增的 `models-inventory` harness（60 条）与已有的 8 个 harness |
| `node tools/pi-contract.mjs` | CI（`ci.yml` 的 `contract` job，`:294`） | §4.4 的四条断言（含两条新的行为断言） |
| `python3 tools/check-nested-comments.py` | 本机已跑 | `nested-comments: OK (169 Kotlin file(s) scanned)` |
| `node --check tools/pi-contract.mjs` | 本机已跑 | 语法通过（不执行断言） |

本机能做、也做了的只有**读代码核对**（按 `file:line` 逐个符号 grep）与上面两条不需要编译器
的命令。**没有跑过的部分不要当成通过。**

## 8. 上 CI 时最可能出错的点（照这个对上 CI 的报错）

按"我最不确定"排序。每一条都写了：出错时会指向哪个文件、以及怎么改。

| # | 位置 | 为什么不确定 | 报错大概长什么样 / 怎么改 |
|---|---|---|---|
| 1 | `app/src/main/kotlin/app/pi/ui/settings/PiFileWatch.kt:10`、`:67`、`:81` | 这是本仓库**第一次**用 `androidx.lifecycle.compose.LocalLifecycleOwner` 与 `LifecycleEventObserver`（全树 grep 只有这一处）。我已确认该类在缓存里的 `lifecycle-runtime-compose` aar 中存在（`androidx/lifecycle/compose/LocalLifecycleOwnerKt.class`），但**没有编译过** | `Unresolved reference: LocalLifecycleOwner` → 改用 `androidx.compose.ui.platform.LocalLifecycleOwner`；`Unresolved reference: LifecycleEventObserver` → 换成一个 `LifecycleObserver` 实现类 + `@OnLifecycleEvent`（已废弃）或直接删掉 resume 那一层（只留 inotify），删掉之后 §3.2 的兜底说明要一起删 |
| 2 | `PiFileWatch.kt:93-99` | `FileObserver(String, Int)` 在 API 29 起被废弃；若这个模块开了 `allWarningsAsErrors` 会失败（我查过 `app/build.gradle.kts` 与根 `build.gradle.kts`，**没有**这条设置，所以应当只是警告） | `w: 'constructor FileObserver(String!, Int)' is deprecated` → 已加 `@Suppress("DEPRECATION")`；若仍失败，改成 `FileObserver(directory, MASK)` 并确认 `minSdk >= 29` |
| 3 | `app/src/main/kotlin/app/pi/packages/PiModelInventory.kt:181`、`:201` | `compareBy<String>({ ... }, { ... })` 与 `groupBy({...},{...})` 的泛型推断。我已把 `compareBy` 写成显式类型参数（`compareBy<String>`），把 `associate` 的 lambda 参数写成显式名字，就是为了消掉这一类风险 | `Not enough information to infer type variable T` → 改成 `.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })` 或 `Comparator { a, b -> ... }` |
| 4 | `PiModelInventory.kt:203` | `buildSet`（stdlib 1.6+，本仓 Kotlin 2.2.21，应该没问题） | `Unresolved reference: buildSet` → 换成 `mutableSetOf<Origin>().apply { ... }.toSet()` |
| 5 | `PiModelInventory.kt:310` | `Regex.escape(String)` 的签名在 Kotlin 1.9 前后动过（`escape(literal)` vs `escape(regex)`） | `None of the following candidates is applicable` → 换成手写转义或 `Regex(Regex.escape(...))` 的旧形式 |
| 6 | `app/src/main/kotlin/app/pi/ui/settings/PiModelsScreen.kt:169`（`items(data.providers, key = ...)`） | 用的是 `androidx.compose.foundation.lazy.items` 的 **list** 重载（本树其它 5 处也用它，我特意从 `items(count)` 换过来的），但 `items` 出现在 `if (data == null) ... else ...` 的 DSL 里 | `Unresolved reference: items` → 加 `import androidx.compose.foundation.lazy.items`（已有）；若是 Compose 编译器层面的问题，把 `else` 分支里的 `items` 换成 `data.providers.forEach { provider -> item(key = provider.id) { ... } }` |
| 7 | `PiModelsScreen.kt:466-472` | `val (text, color) = when (...) { ... MaterialTheme.colorScheme.primary ... }`：在 @Composable 函数体里做解构赋值并读 `MaterialTheme`。理论上合法，但 Compose 编译器只在 Gradle/APK job 里跑，typecheck.sh 不会说话 | `@Composable invocations can only happen from the context of a @Composable function` → 拆成 `val text = ...; val color = ...` 两个 `when`，或先取 `val scheme = MaterialTheme.colorScheme` 再在 `when` 里用它 |
| 8 | `PiModelInventoryCheck.kt`（整个文件） | 它是 `app/src/test/kotlin` 下的 bare-JVM harness，只有 `run-app-pure-checks.sh` 编它（`typecheck.sh` 不编），所以本地读代码也最容易漏 | `pure-checks: FAILED — models-inventory did not compile` → 报错行号直接对应该文件；`check`/`failures` 的顶层声明与 `PackagesPureLogicCheck` 同名，但两个 harness 是**分别**编译的，若 CI 报重名就是脚本里把两者编在了一起（对照 `tools/run-app-pure-checks.sh` 的 `run_harness models-inventory` 只列了 4 个源文件） |
| 9 | `app/src/main/kotlin/app/pi/ui/settings/PiCredentialScreen.kt`（`activeLifecycle` / `activeCoordinator` / `candidateMeta`） | 参数新增与重命名最多的一处；`remember(layout, restartEngine)` 里捕获 `isTurnRunning`（沿用原码的键，不是我引入的） | `Cannot infer type` / `Unresolved reference: activeLifecycle` → 检查 543-640 行是否还有漏改的 `lifecycle.`/`coordinator` 裸引用（我用 grep 清过一遍，理论上没有） |
| 10 | `SettingsGroupScreen.kt` / `SettingsSearchScreen.kt` 的 `freshness: Int = 0` | 新增参数插在中间位置（`highlightKey` 之后 / `valueOverrides` 之后），全部调用点用的是命名参数（全树只有 `PiSettingsStack` 一处调用） | `No value passed for parameter` 或 `Too many arguments` → 把所有调用点改成命名参数（已经是） |
| 11 | `tools/pi-contract.mjs` 里新增的 `startPi()` + `checkStartupOnlyReload()`（`node --check` 通过，但**没执行过**） | 这是本次最可能"逻辑对但 CI 红"的一处：它 `spawn` 一个 `pi --mode rpc`、按行分帧 stdout、按请求 `id` 等应答（30s 上限），再在进程活着的时候改写 `models.json`。风险点是（a）启动或分帧时序、（b）`PI_OFFLINE=1` + `DEEPSEEK_API_KEY` 的环境假设、（c）断言是"反对"（要求新模型**不在**活引擎的列表里），一个不回答的进程会以 `a live engine answers get_available_models` 失败而不是安静通过 | `pi contract: FAILED (1)` 且失败名是 `a live engine answers get_available_models` → 问题在探针（pi 没启动/没应答），不是 App；失败名是 `models.json edited while the engine runs really does need a restart` → **pi 真的变了**，回去读 `core/model-runtime.ts` 的 `refresh` 调用点，并据此改 §0 与 `PiModelInventory` 的 `PENDING_RESTART`；两个新的 `PASS` 都没出现时先看 `runPi`/`writeModelsJson`（原有函数）是否还对 |

**我没有验证过、也不该假装验证过的**：Compose 的 composable-call 规则、`@OptIn` 的必要性、
AAPT2/资源、以及真机上的 inotify 行为（§6）。
