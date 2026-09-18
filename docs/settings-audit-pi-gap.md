# 设置面 · pi 源侧盘点（缺口 / 无用 / 不一致 / 信息架构 / 文件面）

**本文是只读诊断。没有修改任何已有文件，只新建本文。**（同一轮另一个代理在改
`ui/settings/**`、`settings/PiSettingsFileStore.kt` 等未提交工作；实现侧 bug 由
`docs/settings-audit-impl.md` 负责，本文**不重复**它的结论。）

**对照对象**：pi 0.85.1（`/root/pi-src`，HEAD `bbb61e3`，`packages/coding-agent/package.json` version = 0.85.1）。

**证据口径**

- 指向**本仓库**一律用**符号名**（函数 / 键名 / 类名），不写行号——同一轮有别的代理在改这些文件。
- 指向 **pi 源码**才用 `file:line`，全部对着 `/root/pi-src` 核过。pi 侧省略前缀 `packages/coding-agent/`，
  `@ai/` = `packages/ai/`，`@tui/` = `packages/tui/`。
- 代码状态：读的是**工作区当前内容**（含在飞批次已改的 `PiSettingsRegistry.kt`、`PiSettingsFileStore.kt`、
  `PiSettingsStack.kt`、`PiSettingsRows.kt`、`PiSettingsEditors.kt`、`ProjectScreen.kt`、`WorkspaceViewer.kt`
  与新增未跟踪的 `AppOnlySettingsStore.kt`），不是 git HEAD。
- 站内规则按用户裁决执行：**pi 有的必须 1:1（触发条件 / 位置 / 默认值 / 边界逐条一致），pi 没有的一律不做**；
  用户可见文案只能是中文。
- 上一轮的 `docs/settings-review.md`（530 行）、`docs/known-gaps.md` §I7/§I10/§I11、
  `app/src/test/kotlin/app/pi/ui/settings/PiSettingsAuditCheck.kt` 都当**待核对的旧账**处理，
  结论在 §7 逐条给（那张表里有一处是实质性错误：`enabledModels` 的 `EffectiveKind`）。

---

## 0. 结论先行

### 0.1 缺什么

pi 的 `Settings` 是 **51 个顶层键 / 69 个叶子键**（`core/settings-manager.ts:106-158` 逐个展开嵌套组）。
App 注册表现了 **70 个键 = 41 个 pi 键 + 29 个 app 键**，其中 23 个走 `piOwnedKeys` 白名单。
也就是说：**69 个 pi 叶子里有 28 个不在 App 的注册表里**（§2 表 A 逐条）。

但**这 28 条的旧判据（"读者在 RPC 上够不着"）是不成立的**，这是本次审查最重要的一条更正：

- App **自带一个全屏终端**，`SettingsHome` 的「终端」行原文写着
  「输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。」
  → 用户可以在本设备上启动**原版 pi TUI**。旧文档 `docs/settings-review.md` §9 的处置原则本身也是这么写的
  （「用户要调它，去终端里跑原版 TUI」）。
- 那个 TUI 读的是**同一个 `~/.pi/agent/settings.json`**：`PtyLauncher` 的 `Spec.environment()` 把
  `PI_CODING_AGENT_DIR` 指向同一个 agent dir，并在 `prepare` 里把宿主目录 bind 到 `/root/.pi/agent`。
- `rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt` 里却写着
  「read only by pi's own terminal renderer…**pi's TUI never runs here**」——
  这句话与 `PtyLauncher.Spec.environment()` 的注释（"the terminal page opens a plain shell,
  and the user starts `pi` themselves by typing it"）和 `SettingsHome` 的终端行**互相矛盾**。
  旧账的错判就是从这里来的。

所以正确的分类不是"够不着 / 够得着"，而是三分：

| 类别 | 判据 | 数量 | 裁决 |
|---|---|---|---|
| **本应用钉死 / pi 侧无读者** | 读者是 App 的启动参数或环境变量，或 pi 全树没有消费者 | 3（`sessionDir`、`enableAnalytics`、`trackingId`） | **已裁决：不补** |
| **设备上真的做不到 / 设了有害** | App 的终端组件没有该能力，或 App 已把值写死，而 pi 的**设置会覆盖环境变量** | 4（`terminal.images`、`terminal.showImages`、`terminal.imageWidthCells`、`terminal.hyperlinks`）+ 2（`lastChangelogVersion`、`collapseChangelog`：被 `PI_SKIP_VERSION_CHECK=1` 钉死）+ 1（`externalEditor`：载荷里没有可用的编辑器） | **已裁决：不补** |
| **只有原版 TUI 读、但终端页可达** | 读者在 `interactive-mode.ts`，而终端页能跑 pi | 18（其余） | **已裁决：不补进设置页**（用户裁决）。理由**不是**"够不着"，而是"这是 TUI 的旋钮，终端页里能改，不搬进设置页" |

结论：**不该把 18 个 TUI 键做成 18 行表单**（用户抱怨的正是"乱"），但**必须把那 28 条的判据改对**，
并给这 18 个键一个诚实的落点——由「Pi 文件」查看器 / 编辑器（§6.3，已实现）或终端页承接。
这条裁决的正式文本见设置/构造决定的 **D49**（结论 + 判据 + 被否的旧理由 + 受影响的文件）。

### 0.2 什么没用

| 判定 | 项 | 依据 |
|---|---|---|
| **重复的入口（该合并）** | `app.runtime.diagnostics` 行 + `SettingsHome` 的「诊断报告」入口，同一个屏幕两条路 | `SettingsHome` 的 `onOpenDiagnostics` 与注册表行都指向 `DiagnosticsScreen` |
| **同名不同物的两行（该改名）** | `themes` 与 `theme` **标题都叫「主题」** | 注册表两行 `title = "主题"`；一个在 `G_RESOURCES`（路径数组），一个在 `G_APPEARANCE`（主题名） |
| **指路牌（可合并）** | `app.credentials.oauth` | 标题自带「本应用暂无入口」，点击只切终端；而命令面板的 `/login` 已经有「仅终端」徽标 |
| **手机不成立的预设（该删预设，不删键）** | `defaultTools` 的 `powershell` 预设 chip | pi 的合法值是 8 个（`core/tools/index.ts:95`），但 `powershell` 是 Windows 替代项（`docs/settings.md:272-278`），而 App 的 guest 载荷里没有 `pwsh`（`tools/fetch-runtime.mjs`：Ubuntu base + Node + ripgrep + fd + git） |
| **默认值编出来的（该改文案）** | `app.runtime.cacheRetention` 的 `defaultValue = "short"` | pi 只有 `PI_CACHE_RETENTION=long` 与非设置两态（`@ai/api/anthropic-messages.ts:57`），`"short"` 是 App 自己的词 |
| **`app.*` 键漏进 pi 的文档** | 29 个注册的 `app.*` 键 + **2 个未注册的** `app.workspace.current` / `app.workspace.names` | 只有含 `[]` 的键进 `app-prefs.json` sidecar（`PiSettingsFileStore.isAppLocalKey`），其余全部写进 `<agentDir>/settings.json` |

### 0.3 哪里不一致（1:1 的真实含义）

按严重度排序，整表在 §4：

1. **`enabledModels` 的生效时机被打错**：注册表现在是 `EffectiveKind.RestartEngine`，行说明写「pi 在引擎启动时解析一次，
   改完要重启引擎才生效」——**两处都错**。`main.ts:788` 的 `settingsManager.getEnabledModels()` 在
   `createRuntime` 闭包内（`main.ts:714-839`），而该闭包在**每次新会话**都被调用
   （`core/agent-session-runtime.ts:237` `newSession` → `:241` `this.createRuntime(...)`；
   该闭包里另有 `SettingsManager.create`，`main.ts:731`）。正确值是 `NewSession`。
   （`docs/settings-review.md` §1.1 写的 `NewSession` 是对的，是**代码里的注释改错了**。）
2. **所有数字行的 `max` 上限是 App 发明的**：pi 这些键没有上界（`core/settings-manager.ts:936-947` 只拒
   `!isFinite || <0`；压缩量只要求非负安全整数 `:860`）。而 App 的数字编辑器对**手输**也做
   `coerceIn(low, high)`，所以 pi 接受的值进不来，**已经在文件里的越界值一进编辑器就被静默夹到上限**。
3. **`enabledModels` 的模式匹配是第二套实现**（`PiModelInventory.matches`）：只认精确串 + `*`/`?`；
   pi 对**非通配**模式做**子串**匹配（`core/model-resolver.ts:135-160` `tryMatchModel`，
   alias 优先、否则取最新日期版），`[...]` 是合法 glob（`:280`），通配模式下先做精确命中（`:305`），
   `:level` 后缀判定**大小写敏感**（`cli/args.ts:60-64`）。后果：用户按 pi 自己的帮助示例
   `--models claude-sonnet,claude-haiku,gpt-4o`（`cli/args.ts:366`）写下的模式，App 的「模型」页会**明确地**
   说该模型不在循环列表里。
4. **写作用域是隐式的**：pi 自己的设置界面一律写 **global**（各 `setX` 只改 `globalSettings`，
   `core/settings-manager.ts:788-792` 等），只有 `pi config` 有一个**用户可见**的作用域切换
   （`cli/config-selector.ts` 的 `writeScope`，Tab 切）。App 的 `PiSettingsFileStore.writeLocked`
   按"项目文件里已经有这个键吗"偷偷决定写哪份，界面上**没有任何地方说明**。
5. **`theme` 的默认值不是 pi 的语义**：pi 无默认，未设时按**终端背景探测**决定
   （`cli/startup-ui.ts:87` `resolveThemeSetting(...) ?? terminalTheme`）。App 写死 `"dark"`。
   在手机上可接受，但不能说成「pi 的默认值」；且 App 的主题编辑器不校验 pi 的两条命名规则
   （单个名字不能含 `/`：`modes/interactive/theme/theme.ts:480-484`；自动模式最多一个 `/`：`:580-593`），
   `a/b/c` 这种值会被 pi 静默忽略。
6. **`httpIdleTimeoutMs` 的取值域比 App 大**：pi 接受字符串 `"disabled"`（等价 0）并向下取整
   （`core/http-dispatcher.ts:19-35`）；App 的数字行把它显示成「未设置」。另外 pi 对**非法值直接抛**
   （`core/settings-manager.ts:188-197` `parseTimeoutSetting`），而这个 getter 在
   `main.ts:851`（进程启动）与 `core/sdk.ts:316`（每次请求）都被调 → 手改坏一个值会让引擎起不来，
   App 目前对此零提示。
7. **JSON 保真**：pi 自己写的都是 `JSON.stringify(x, null, 2)`
   （`core/settings-manager.ts:659`、`core/trust-manager.ts:134`、`core/auth-storage.ts:106/187`），
   App 写的是紧凑 JSON（`PiSettingsFileStore.writeDocument`、`PiConfigFiles.stringify`）。
   语义一致，但会把用户的排版压掉，且与 pi 的下一次重写产生无意义 diff。
8. **`app.*` 键漏进 pi 的文档**（§0.2 最后一行），与"pi 没有的一律不做"需要区分：
   功能可以不做，但**键不该落在 pi 读的那份文档里**——sidecar 机制已经存在（`app-prefs.json`），
   现在只服务于含 `[]` 的伪路径。

### 0.4 为什么用户说"这么乱"

不是组名的问题，是**同一个屏幕混了四种东西**：

1. **pi 的键**（41）——写进 `settings.json`，pi 读；
2. **App 自己的偏好**（29）——pi 完全不知道，只是恰好也住在 `settings.json` 里；
3. **只读事实**（6 个 `app.runtime.*`）——显示值，不可改；
4. **动作 / 指路牌**（7 个 Action 行 + 首页的 5 个入口行）。

它们在界面上**长得一模一样**（同一套行样式、同一个 `group/section` 骨架），唯一的区分是
`EffectiveKind` 的徽标和 chevron。加上：

- **两行同名「主题」**（`themes` / `theme`）；
- **13 个分组里有的组只有 1 行**（`G_ABOUT` 只有一个 `enableInstallTelemetry`，
  section 还叫「隐私」，而组名叫「隐私与关于」却没有"关于"内容——关于类入口在首页）；
- **section 名与内容不匹配**：`G_RETRY`（重试与网络）里**没有一个网络设置**，网络在 `G_MESSAGES` 里；
  `G_APPEARANCE/图片` 里是 pi 键（`images.*`），而 pi 的文档把图片和 `terminal.*` 放在同一节
  （`docs/settings.md:215-226`）；
- **单行 section**：`G_MESSAGES/传输` 只有 `transport`，`G_APPEARANCE/主题` 只有 `theme`，
  `G_ABOUT/隐私` 只有一行——section 头在视觉上比内容还重。

§6 给出目标骨架与逐条搬动理由。

### 0.5 文件面该怎么做（用户这句的核心）

**现状**：App **没有任何**"Pi 文件"查看器。能碰到的 pi 文件只有三条路：

| 路 | 根 | 能力 |
|---|---|---|
| 工作区文件树（`ProjectScreen` 的「全部文件」→ `WorkspaceViewer`） | 工作区（`GuestWorkspacePath.RELATIVE` 那个 app 私有目录） | 看 + 改 + 新建 + 改名 + 删；**顺带**能改到 `<workspace>/.pi/**` 与 `<workspace>/AGENTS.md`（点开头目录不隐藏） |
| 资源段（`WorkspaceResources` + `ProjectScreen` 的「资源」） | `.pi` / `.agents` / agent dir / 包的 `npm/node_modules` | **只看**（`editable = false`），覆盖 `skills`/`prompts`/`extensions`/`themes` |
| 凭证页（`PiCredentialScreen` → `PiCredentialService`） | `<agentDir>/auth.json`、`models.json`、`settings.json` | 字段级改（不是文件级） |

**结论**：`~/.pi/agent` 的**根文件**（`settings.json`、`models.json`、`auth.json`、`keybindings.json`、
`trust.json`、`AGENTS.md`、`SYSTEM.md`、`APPEND_SYSTEM.md`、`models-store.json`）**一个都看不到**；
而 `<workspace>/.pi/settings.json` 反而**能**通过工作区文件树当普通文本改
——这与 `docs/settings-review.md` §8 说的「App 有意不提供原始 settings.json 编辑器」**互相矛盾**：
编辑器其实已经有了，只是在另一个屏、另一条路上，而且是**非原子、无锁、整份覆盖**的写。

**该怎么做**（§6.3 给完整方案）：加一个「Pi 文件」屏幕，根目录 = agent dir + `<workspace>/.pi`，
按"pi 会不会重写它"分三档：**只看**（认证/信用/会话/缓存类，pi 自己写）、**可改**（用户写给 pi 读的文档类，
如 `settings.json`/`models.json`/`SYSTEM.md`/`AGENTS.md`/主题/技能/提示模板）、**绝不手改**
（`auth.json` 只能走凭证页、`models-store.json`、`sessions/`、`trust.json`（唯一真相是 App 的信任流程）、
`pi-debug.log`）。改的时候必须满足 pi 的约束：`proper-lockfile` 的 `<file>.lock` 目录锁、
`auth.json` 的 `0600`（只在创建时生效）、原子写（临时文件 + rename，**注意 pi 的临时文件名习惯**）、
JSON 保真（数组 replace / 对象 merge、未知键保留、2 空格缩进）、以及 **app-only 键不许写进 pi 的文档**。

---

## 1. pi 0.85.1 的完整设置/配置面

### 1.1 三份来源的交叉结果

| 来源 | 位置 | 它回答什么 |
|---|---|---|
| `Settings` 接口 + 每个 getter/setter | `core/settings-manager.ts:106-158`（接口）、`:714-1417`（读与写） | 键、类型、默认值、作用域、谁读它 |
| 文档 | `docs/settings.md`（405 行） | pi 自己承认的类型 / 默认值 / 语义 |
| pi 自己的设置菜单 | `modes/interactive/components/settings-selector.ts`（945 行）+ `settings-submenu.ts`（258 行） | pi **实际暴露给用户的行**（id 列表 `:462-830`） |

三份**互有出入**，以代码为准：

| 项 | 代码 | `docs/settings.md` | 结论 |
|---|---|---|---|
| `websocketConnectTimeoutMs` 默认 | getter 返回 `undefined`（`core/settings-manager.ts:957-959`） | `15000`（`docs/settings.md:213`） | 两边都对：默认值在**provider** 里（`@ai/api/openai-codex-responses.ts:50`，应用点 `:1049`） |
| `retry.provider.maxRetries` 默认 | getter 返回 `undefined`（`:949-955`） | `0`（`docs/settings.md:180`） | 默认值在 SDK 客户端（`@ai/api/anthropic-messages.ts:574`、`@ai/api/openai-completions.ts:364`） |
| `outputPad` 类型 | `0 \| 1`（`:145`、`:1372-1374`） | 数字 `0/1` | 一致 |
| `theme` 默认 | 无（`:777-786`） | `"dark"` | 文档说的是**探测后的回退**，不是键的默认值（见 §4 C5） |
| `enableAnalytics` / `trackingId` | 只有 getter，**全树无消费者**（`grep -rn "getEnableAnalytics\|getTrackingId"` 只命中 `settings-manager.ts` 与一个 test） | `docs/settings.md:61-62` 写着"currently only asked for during the experimental first-time setup" | **pi 自己只写不读** |

### 1.2 pi 键总表（51 顶层 / 69 叶子）

`读它` 一列给 pi 侧的**读取点 → 首个消费点**；`scope` 是**写**的作用域（project = 只有 `setProject*` 才写项目文件；
但**任何键都能被项目文件覆盖**，因为载入是把项目文档整份深合并上来，`core/settings-manager.ts:345`）。

| # | 键（叶子） | 类型 / 取值域 | 默认 | scope | 谁读它（file:line） | RPC 引擎也读？ |
|---|---|---|---|---|---|---|
| 1 | `lastChangelogVersion` | string | — | global | `:714` → `interactive-mode.ts:1218` | 否（TUI） |
| 2 | `defaultProvider` | string | — | global | `:729` → `core/sdk.ts:215` | **是** |
| 3 | `defaultModel` | string | — | global | `:733` → `core/sdk.ts:216` | **是** |
| 4 | `defaultThinkingLevel` | `ThinkingLevel` = `off\|minimal\|low\|medium\|high\|xhigh\|max`（`@agent/types.ts:301`） | — | global | `:794` → `core/sdk.ts:219/240-251` | **是** |
| 5 | `modelThinkingLevels` | `Record<"provider/modelId", ThinkingLevel>` | — | global | `:804/808` → `core/sdk.ts:218` | **是** |
| 6 | `transport` | `sse\|websocket\|websocket-cached\|auto`（`@ai/types.ts:110`） | `"auto"` | global | `:831` → `core/sdk.ts:369` | **是** |
| 7 | `steeringMode` | `all\|one-at-a-time` | `one-at-a-time` | global | `:757` → `core/sdk.ts:365`、`agent-session.ts:1894` | **是** |
| 8 | `followUpMode` | `all\|one-at-a-time` | `one-at-a-time` | global | `:767` → `core/sdk.ts:366`、`agent-session.ts:1895` | **是** |
| 9 | `theme` | string；单个名字**不能含 `/`**，`"lightTheme/darkTheme"` 是自动模式（`theme.ts:480-484`、`:580-593`） | 无（按终端背景探测回退，`cli/startup-ui.ts:87`） | global | `:777/783` → `main.ts:887`、`agent-session.ts:3464` | 是（建会话时的调色板/导出） |
| 10 | `compaction.enabled` | boolean | `true` | global | `:841` → `agent-session.ts:2465` | **是** |
| 11 | `compaction.reserveTokens` | 非负安全整数 | `16384` | global | `:882` → `agent-session.ts:540/1979/2155/2272` | **是** |
| 12 | `compaction.keepRecentTokens` | 非负安全整数 | `20000` | global | `:886` → 同上 | **是** |
| 13 | `compaction.modelOverrides` | `Record<"provider/modelId", {reserveTokens?,keepRecentTokens?}>`，键**精确大小写匹配**（`docs/settings.md:154`） | — | global | `:854-880` | **是** |
| 14 | `branchSummary.reserveTokens` | 非负整数 | `16384`（输出上限 4096） | global | `:903` → `agent-session.ts:3232` | **是** |
| 15 | `branchSummary.skipPrompt` | boolean | `false` | global | `:910` → `interactive-mode.ts:5236` | 否（TUI） |
| 16 | `retry.enabled` | boolean | `true` | global | `:914` → `agent-session.ts:2983` | **是** |
| 17 | `retry.maxRetries` | number | `3` | global | `:927` → `agent-session.ts:722/2918` | **是** |
| 18 | `retry.baseDelayMs` | number | `2000` | global | `:927` → `agent-session.ts:722` | **是** |
| 19 | `retry.maxAgentDelayMs` | number | `60000`（`@ai/utils/retry.ts:109` `DEFAULT_MAX_AGENT_RETRY_DELAY_MS`） | global | `:932` | **是** |
| 20 | `retry.provider.timeoutMs` | number | SDK 默认 | global | `:949` → `core/sdk.ts:315` | **是** |
| 21 | `retry.provider.maxRetries` | number | `0`（SDK 客户端硬编码，见 §1.1） | global | `:949` → `core/sdk.ts:315` | **是** |
| 22 | `retry.provider.maxRetryDelayMs` | number | `60000` | global | `:953` → `core/sdk.ts:371` | **是** |
| 23 | `hideThinkingBlock` | boolean | `false` | global | `:961` → `interactive-mode.ts:1949` | 否（TUI 渲染） |
| 24 | `showCacheMissNotices` | boolean | `false` | global | `:965` → `interactive-mode.ts:3694/3804` | 否（TUI 渲染） |
| 25 | `externalEditor` | string；回退 `$VISUAL`→`$EDITOR`→`nano`（`core/settings-manager.ts:969-979`） | — | global | `:969` → `interactive-mode.ts:2628/4247` | 否（TUI） |
| 26 | `shellPath` | string，支持前导 `~` | — | global | `:993` → `agent-session.ts:2794/3016` | **是** |
| 27 | `quietStartup` | boolean | `false` | global | `:1004` → `interactive-mode.ts:1650` | 否（TUI） |
| 28 | `defaultProjectTrust` | `ask\|always\|never` | `ask` | **global only**（`:124`） | `:1014` → `main.ts:745`（**启动时**那个 manager） | 是（进程启动） |
| 29 | `shellCommandPrefix` | string | — | global | `:1025` → `agent-session.ts:2793/3015` | **是** |
| 30 | `npmCommand` | string[]（argv） | — | global | `:1035` → `core/package-manager.ts:1748` | 是（装包时） |
| 31 | `collapseChangelog` | boolean | `false` | global | `:1045` → `interactive-mode.ts:766` | 否（TUI） |
| 32 | `enableInstallTelemetry` | boolean | `true` | global | `:1055` → `core/telemetry.ts:12`（每次构建归因头/上报时问一次） | **是** |
| 33 | `enableAnalytics` | boolean | `false` | global | **无消费者** | 否 |
| 34 | `trackingId` | string | — | global | **无消费者**（`setEnableAnalytics` 首次开启时生成，`:1074-1082`） | 否 |
| 35 | `packages` | `(string \| {source,autoload?,extensions?,skills?,prompts?,themes?})[]` | `[]` | global **+ project**（`setProjectPackages` `:1094`） | `:1084` → `core/package-manager.ts:914/978` | 是（建会话时解析） |
| 36 | `extensions` | string[]（glob / `!` / `+` / `-`） | `[]` | global + project（`:1110`） | `:1100` → `core/extensions/loader.ts:783` | 是 |
| 37 | `skills` | string[] | `[]` | global + project（`:1126`） | `:1116` → `core/skills.ts:454` | 是 |
| 38 | `prompts` | string[] | `[]` | global + project（`:1142`） | `:1132` → `core/prompt-templates.ts:203` | 是 |
| 39 | `themes` | string[] | `[]` | global + project（`:1158`） | `:1148` → `core/resource-loader.ts:875` | 是（建会话时加载） |
| 40 | `enableSkillCommands` | boolean | `true` | global | `:1164` → `interactive-mode.ts:716` | 否（TUI 注册命令） |
| 41 | `terminal.showImages` | boolean | `true` | global | `:1188` → `interactive-mode.ts:3257/3332/3725` | 否 |
| 42 | `terminal.imageWidthCells` | number（≥1） | `60` | global | `:1201` → 同上 | 否 |
| 43 | `terminal.clearOnShrink` | boolean（设置优先于 `PI_CLEAR_ON_SHRINK`，`:1218-1224`） | `false` | global | `:1218` → `interactive-mode.ts:1952` | 否 |
| 44 | `terminal.showTerminalProgress` | boolean | `false` | global | `:1235` → `interactive-mode.ts:3184` | 否 |
| 45 | `terminal.hyperlinks` | `boolean \| "auto"` | `"auto"` | global | `:1178` → `main.ts:849` `setCapabilityOverrides` → `@tui/terminal-image.ts:160-170` | 入口在非 TUI 也跑，但消费者只有 TUI 渲染 |
| 46 | `terminal.images` | `"kitty" \| "iterm2" \| "auto" \| false` | `"auto"` | global | 同上 | 同上 |
| 47 | `terminal.trueColor` | `boolean \| "auto"` | `"auto"` | global | 同上 | 同上 |
| 48 | `images.autoResize` | boolean | `true` | global | `:1289` → `agent-session.ts:522/2792` | **是** |
| 49 | `images.blockImages` | boolean | `false` | global | `:1302` → `core/sdk.ts:271` | **是** |
| 50 | `enabledModels` | string[]，`--models` 同格式（glob / `provider/id` / 裸 id / `:level` 后缀） | — | global | `:1315` → `main.ts:788`（**在 `createRuntime` 闭包内**） | **是** |
| 51 | `defaultTools` | `read\|bash\|powershell\|edit\|write\|grep\|find\|ls`（`core/tools/index.ts:95`） | —（生效默认 `read,bash,edit,write`，`core/sdk.ts:256`） | global | `:1319` → `core/sdk.ts:257` | **是** |
| 52 | `doubleEscapeAction` | `fork\|tree\|none` | `tree` | global | `:1330` → `interactive-mode.ts:2863` | 否 |
| 53 | `treeFilterMode` | `default\|no-tools\|user-only\|labeled-only\|all` | `default` | global | `:1340` → `interactive-mode.ts:5208` | 否 |
| 54 | `thinkingBudgets` | `{minimal?,low?,medium?,high?}`（**只有这 4 个键**，`:67-72`） | — | global | `:1174` → `core/sdk.ts:370` → `@ai` 各 provider | **是** |
| 55 | `editorPaddingX` | number，写入时 `clamp(0,3)`（`:1367`） | `0` | global | `:1362` → `interactive-mode.ts:1957` | 否 |
| 56 | `outputPad` | `0 \| 1` | `1` | global | `:1372` → `interactive-mode.ts:1950` | 否 |
| 57 | `autocompleteMaxVisible` | number，`clamp(3,20)`（`:1387`） | `5` | global | `:1382` → `interactive-mode.ts:1958/2682` | 否 |
| 58 | `showHardwareCursor` | boolean（回退 `PI_HARDWARE_CURSOR=1`，`:1353`） | `false` | global | `:1352` → `interactive-mode.ts:1951` | 否 |
| 59 | `markdown.codeBlockIndent` | string | `"  "` | global | `:1392` → `interactive-mode.ts:1261` | 否 |
| 60 | `markdown.mermaid` | `off\|final\|streaming` | `streaming` | global | `:1396` → `interactive-mode.ts:440` | 否 |
| 61 | `warnings.anthropicExtraUsage` | boolean | `true` | global | `:1408` → `interactive-mode.ts:4909` | 否 |
| 62 | `sessionDir` | string（绝对/相对/`~`） | —（默认 `~/.pi/agent/sessions`） | global | `:724` → `main.ts:670-676`（优先级 `--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > 设置） | 是（进程启动） |
| 63 | `httpProxy` | string | — | global | `main.ts:583/850` `applyHttpProxySettings(getGlobalSettings().httpProxy)` | 是（进程启动） |
| 64 | `httpIdleTimeoutMs` | number ≥0，**字符串 `"disabled"` 也是合法值**（=0），非法值**抛异常**（`http-dispatcher.ts:19-35` + `settings-manager.ts:188-197`） | `300000`（`http-dispatcher.ts:4`） | global | `:936` → `main.ts:851`、`core/sdk.ts:316` | **是** |
| 65 | `websocketConnectTimeoutMs` | 同上的解析规则 | 无（provider 侧 15000） | global | `:957` → `core/sdk.ts:322` | **是** |
| 66 | `tuiMode` | `regular \| fullscreen` | `regular` | global | `:1248` → `interactive-mode.ts:523` | 否 |
| 67 | `fullscreenExitOutput` | `transcript \| resume-hint` | `transcript` | global | `:1258` → `interactive-mode.ts:6601` | 否 |
| 68 | `fullscreenScrollbar` | `auto \| always \| hidden` | `auto` | global | `:1268` → `interactive-mode.ts:1936` | 否 |
| 69 | `fullscreenCopyOnSelect` | boolean | `true` | global | `:1279` → `interactive-mode.ts:1944` | 否 |

### 1.3 pi 自己的 `/settings` 菜单（TUI 实际行）

行序**不是**文档的分节顺序。`settings-selector.ts:462-830`，条件插入见下（`supportsImages` 来自
`getCapabilities().images`，`:449`）：

| # | id | 标签 | 键 | 取值 | 位置 |
|---|---|---|---|---|---|
| 1 | `autocompact` | Auto-compact | `compaction.enabled` | true/false | `:462` |
| 1a | `show-images` | Show images | `terminal.showImages` | true/false | `:719`（**仅当 `getCapabilities().images` 为真**） |
| 1b | `image-width-cells` | Image width | `terminal.imageWidthCells` | 60/80/120 | `:726`（同上） |
| 2 | `auto-resize-images` | Auto-resize images | `images.autoResize` | true/false | `:736` |
| 3 | `block-images` | Block images | `images.blockImages` | true/false | `:746` |
| 4 | `skill-commands` | Skill commands | `enableSkillCommands` | true/false | `:756` |
| 5 | `show-hardware-cursor` | Show hardware cursor | `showHardwareCursor` | true/false | `:766` |
| 6 | `editor-padding` | Editor padding | `editorPaddingX` | 0/1/2/3 | `:776` |
| 7 | `output-padding` | Output padding | `outputPad` | 0/1 | `:786` |
| 8 | `autocomplete-max-visible` | Autocomplete max items | `autocompleteMaxVisible` | 3/5/7/10/15/20 | `:796` |
| 9 | `clear-on-shrink` | Clear on shrink | `terminal.clearOnShrink` | true/false | `:806` |
| 10 | `terminal-progress` | Terminal progress | `terminal.showTerminalProgress` | true/false | `:816` |
| 11 | `steering-mode` | Steering mode | `steeringMode` | one-at-a-time/all | `:469` |
| 12 | `follow-up-mode` | Follow-up mode | `followUpMode` | one-at-a-time/all | `:477` |
| 13 | `transport` | Transport | `transport` | sse/websocket/websocket-cached/auto | `:484` |
| 14 | `http-idle-timeout` | HTTP idle timeout | `httpIdleTimeoutMs` | `HTTP_IDLE_TIMEOUT_CHOICES` 的标签 + `disabled`，显示用 `formatHttpIdleTimeoutMs` | `:491` |
| 15 | `hide-thinking` | Hide thinking | `hideThinkingBlock` | true/false | `:499` |
| 16 | `mermaid-rendering` | Mermaid diagrams | `markdown.mermaid` | off/final/streaming | `:506` |
| 17 | `cache-miss-notices` | Cache miss notices | `showCacheMissNotices` | true/false | `:513` |
| 18 | `collapse-changelog` | Collapse changelog | `collapseChangelog` | true/false | `:520` |
| 19 | `quiet-startup` | Quiet startup | `quietStartup` | true/false | `:527` |
| 20 | `install-telemetry` | Install telemetry | `enableInstallTelemetry` | true/false | `:534` |
| 21 | `default-project-trust` | Default project trust | `defaultProjectTrust` | ask/always/never | `:541` |
| 22 | `double-escape-action` | Double-escape action | `doubleEscapeAction` | tree/fork/none | `:548` |
| 23 | `tree-filter-mode` | Tree filter mode | `treeFilterMode` | 5 值 | `:555` |
| 24 | `warnings` | Warnings | `warnings` | 子菜单 | `:562` → `WarningSettingsSubmenu`（`:143` 的 `anthropic-extra-usage`） |
| 25 | `model-thinking` | Default thinking level per model | `modelThinkingLevels` | 多步子菜单（先选模型再选等级），可从子菜单里删 | `:577` |
| 26 | `tui-mode` | TUI mode | `tuiMode` | regular/fullscreen | `:678` |
| 27 | `fullscreen-exit-output` | Fullscreen exit output | `fullscreenExitOutput` | transcript/resume-hint | `:685` |
| 28 | `fullscreen-scrollbar` | Fullscreen scrollbar | `fullscreenScrollbar` | auto/always/hidden | `:692` |
| 29 | `fullscreen-copy-on-select` | Fullscreen copy on select | `fullscreenCopyOnSelect` | true/false | `:699` |
| 30 | `theme` | Theme | `theme` | 子菜单：单主题列表 + Automatic（Light theme / Dark theme / Apply / Change mode，`:296-370`） | `:706` |

**§1.2 里 69 个键中，pi 的 `/settings` 只覆盖 32 个**（默认可见 30 行：`show-images` 与
`image-width-cells` 只在 `getCapabilities().images` 为真时插入，`:717-733`）；剩下的走 JSON 手工编辑或 CLI。
`settings-submenu.ts` 本身只是两个通用组件（`SelectSubmenu:31`、`SteppedSubmenu:178`），
没有独立设置面。

### 1.4 `pi config` 与"没有 settings 键"的 CLI/env 旋钮

| 界面 | 作用 | 位置 |
|---|---|---|
| `pi config` | TUI，**按作用域启用/禁用**已解析的包资源（extensions/skills/prompts/themes），Tab 切 global/project | `cli/config-selector.ts:20-60`，组件 `modes/interactive/components/config-selector.ts` |
| `pi install/remove/update/list` | 包管理，写 `settings.packages` | `package-manager-cli.ts` |
| `--tools/-t` | 严格白名单，**胜出** `defaultTools`（`core/sdk.ts:258-262`） | `cli/args.ts:137-141` |
| `--models` | 逗号分隔，胜出 `enabledModels`（`main.ts:788`） | `cli/args.ts:131-132` |
| `--exclude-tools/-xt` | 过滤工具 | `cli/args.ts:142-146` |
| `--no-builtin-tools/-nbt` | 禁内建工具 | `cli/args.ts:133-134` |
| `--no-session` / `--name` | 临时会话 / 命名 | `cli/args.ts:121-122` / `:115-120` |
| `--api-key` | 一次性明文覆盖 | `cli/args.ts:108-109` |
| `--approve/-a`、`--no-approve/-na` | 一次性信任决定 | `cli/args.ts:219-222` |
| `--offline` | = `PI_OFFLINE=1`（`cli/args.ts:223`，帮助文本 `:318`） | — |
| `--verbose` | 只影响 TUI 启动打印 | `cli/args.ts:217-218` |
| `--tui-mode` | 一次性覆盖 `tuiMode` | `cli/args.ts:203-216` |
| `--no-extensions/-ne`、`--no-skills/-ns`、`--no-prompt-templates/-np`、`--no-themes`、`--no-context-files/-nc` | 关掉各类发现 | `cli/args.ts:169-194` |
| `--extension/-e`、`--skill`、`--prompt-template`、`--theme`、`--use-theme` | 附加路径 / 一次性主题 | `cli/args.ts:166-187` |

环境变量全表在 `docs/environment-variables.md:26-95`。**注意 `PI_TELEMETRY` 会盖掉设置键**
（`core/telemetry.ts:10-14`），而 `terminal.*` 的能力类环境变量会被**设置反过来盖掉**
（`@tui/terminal-image.ts:160-170`：`{...detectCapabilities(env), ...capabilityOverrides}`，设置在后面）。

### 1.5 RPC 命令面：哪些设置能实时改

`modes/rpc/rpc-types.ts:20-74` 的全部命令里，与设置有关的只有 6 条：

| 命令 | 对应设置键 | 位置 |
|---|---|---|
| `set_steering_mode` | `steeringMode` | `rpc-mode.ts:521-530` |
| `set_follow_up_mode` | `followUpMode` | 同上 |
| `set_auto_compaction` | `compaction.enabled` | `rpc-mode.ts:532-540` |
| `set_auto_retry` | `retry.enabled` | 同上 |
| `set_thinking_level` | 会话内思考等级（非持久键） | `rpc-types.ts:45` |
| `set_model` / `cycle_model` | 会话内模型（非持久键） | `rpc-types.ts:40-42` |

**没有**：改主题、改工具集、刷新模型目录、reload 设置、登录/登出、信任、导入、`/settings` 的任何一个开关。
pi 收下前四条后会**写回同一个键**（`core/agent-session.ts:1902-1917`），所以 App 的那四个"实时推送"不会与
文件里的值打架。

---

## 2. 表 A：pi 有、App 没有的 28 个键

判定列：**必须补** / **手机不可达** / **被本应用固定住** / **只有原版 TUI 读，App 自己的界面无读者**。
最后一类是本次新增的判据（见 §0.1）：**它不是"不可达"**，TUI 在终端页可达。

### A.1 被本应用固定住（1）—— **已裁决：不补**

| 键 | 结论 | 证据 | 建议动作 | 风险 |
|---|---|---|---|---|
| `sessionDir` | **被本应用固定住**（旧判定成立，但只对了一半） | App 同时传 `--session-dir`（`PiEngineHost` 的 guest 命令行构造）与 `PI_CODING_AGENT_SESSION_DIR`（同文件的 `extraEnv`）；pi 优先级 `cli/args.ts:129-130` + `main.ts:670-676` | 二选一：① 维持现状，把理由从"读者够不着"改成"App 钉住了根目录"；② 去掉两个启动输入，让键生效 | 去掉后 pi 的布局会从**平铺**变成**按 cwd 分组**（`core/session-manager.ts:476-489` `getDefaultSessionDirPath` → `<agentDir>/sessions/--<cwd-slug>--/`）。`PiSessionStore.list` 已两种布局都读，但 `mostRecentForResume` 只读平铺（其 KDoc 自己写着），`-c` 续接会再次失效。**这是唯一真正的阻塞点，比 `docs/settings-review.md` §7.5 描述的代价小** |
| — | 附带事实 | 现状本身与桌面安装**不 1:1**：桌面默认分组，这里平铺 | 若维持现状，至少在文档里写明这是 App 的偏离 | 用户把手机上的 agent dir 拷到桌面会看到布局不同 |

### A.2 设备上真的做不到 / 设了有害（7）—— **已裁决：不补**

| 键 | 结论 | 证据 | 建议动作 | 风险 |
|---|---|---|---|---|
| `terminal.images` | **手机不可达 + 设了有害** | 终端组件没有图片协议（`gradle/libs.versions.toml` 钉的 `org.connectbot:termlib 0.0.13`；`PtyLauncher.Spec.environment()` 明确写 OSC 解析器只支持 52/133/1337）；且**设置会盖掉环境**：`@tui/terminal-image.ts:160-170` 把 `capabilityOverrides` 展开在探测结果**之后** | 不补；若将来补，必须在描述里写明"本应用的终端不支持图片，设了只会得到乱码" | 设 `"kitty"` 会让 pi 往一个不能渲染的终端发 `\x1b_G` 序列 |
| `terminal.showImages` | **手机不可达** | 同上；且 **pi 自己会在不支持图片的终端里隐藏这一行**（`settings-selector.ts:449` + `:719-733` 的 `if (supportsImages)`） | 不补。**旧文档给的理由（"只有 TUI 画内联图片时读"）不如这条硬**——pi 自己的界面都不显示它 | 无 |
| `terminal.imageWidthCells` | **手机不可达** | 同 `terminal.showImages` | 不补 | 无 |
| `terminal.hyperlinks` | **手机不可达 + 设了有害** | OSC 8 未实现（同上注释："Advertising '1' would make pi emit links nothing can open"），App 主动设 `PI_HYPERLINKS=0`；设置会盖掉它（同 `:160-170`） | 不补 | 设 `true` 会让 URL 从输出里"消失"（`@tui/terminal-image.ts` 的注释描述的就是这个） |
| `lastChangelogVersion` | **被本应用固定住** | App 在两处都钉了 `PI_SKIP_VERSION_CHECK=1`：引擎（`PiEngineHost` 的 `extraEnv`）与终端（`PtyLauncher.Spec.environment()`）；没有更新检查就没有 changelog | 不补 | 无 |
| `collapseChangelog` | **被本应用固定住** | 同上（读者 `interactive-mode.ts:766`） | 不补 | 无 |
| `externalEditor` | **手机不可达** | pi 的回退链是 `$VISUAL`→`$EDITOR`→`nano`（`core/settings-manager.ts:969-979`）；guest 载荷里没有 `nano`/`vim`（`tools/fetch-runtime.mjs`：Ubuntu base + Node + ripgrep + fd + git，无编辑器） | 不补。**旧文档写了同一结论但没给证据**，这条把证据补上 | 无 |

### A.3 只有原版 TUI 读，但终端页可达（18）—— **已裁决：不补进设置页**（用户裁决）

| 键 | 读者（file:line） | 结论 | 证据（为什么不是"不可达"） | 建议动作 | 风险 |
|---|---|---|---|---|---|
| `terminal.clearOnShrink` | `interactive-mode.ts:1952` | 只有原版 TUI 读 | 终端页可运行 pi（`SettingsHome` 的终端行原文），`PtyLauncher` 把同一个 agent dir bind 进 guest | 不补成行；由「Pi 文件」查看器或终端承接 | 无 |
| `terminal.showTerminalProgress` | `interactive-mode.ts:3184` | 同上 | 同上（且终端组件不实现 OSC 9） | 同上 | 设了没观感 |
| `terminal.trueColor` | `main.ts:849` → `@tui/terminal-image.ts:160-170` | **功能上有效但冗余** | App 已经设 `COLORTERM=truecolor`，而终端组件**确实**解析 SGR 38/48 的 24 位（`PtyLauncher` 注释）→ 探测已得出 truecolor；这个键的唯一作用是**降级** | 不建议补（零收益开关） | 无 |
| `outputPad` | `interactive-mode.ts:1950` | 只有原版 TUI 读 | 同上 | 不补（旧的"TUI-only"结论对，理由要改） | 无 |
| `editorPaddingX` | `interactive-mode.ts:1957` | 同上 | 同上 | 同上 | 无 |
| `autocompleteMaxVisible` | `interactive-mode.ts:1958/2682` | 同上 | 同上 | 同上 | 无 |
| `showHardwareCursor` | `interactive-mode.ts:1951` | 同上 | 同上 | 同上 | 无 |
| `markdown.codeBlockIndent` | `interactive-mode.ts:1261` | 同上 | 同上 | 同上 | 无 |
| `markdown.mermaid` | `interactive-mode.ts:440` | 同上 | 同上 | 同上 | 无 |
| `quietStartup` | `interactive-mode.ts:1650` | 同上 | 同上 | 同上 | 无 |
| `doubleEscapeAction` | `interactive-mode.ts:2863` | 同上 | 同上 | 同上 | 无 |
| `treeFilterMode` | `interactive-mode.ts:5208` | 同上 | 同上 | 同上 | 无 |
| `warnings.anthropicExtraUsage` | `interactive-mode.ts:4909` | 同上 | 同上 | 同上 | 无 |
| `branchSummary.skipPrompt` | `interactive-mode.ts:5236` | 同上 | 同上（RPC 的 `navigate_tree` 由客户端传 `summarize`，与这个键无关） | 同上 | 无 |
| `tuiMode` | `interactive-mode.ts:523` | 同上 | 终端页跑的就是 TUI，`regular/fullscreen` 在那里有意义 | 同上 | 无 |
| `fullscreenExitOutput` | `interactive-mode.ts:6601` | 同上 | 同上 | 同上 | 无 |
| `fullscreenScrollbar` | `interactive-mode.ts:1936` | 同上 | `auto` 需要指针悬停（手机没有鼠标），`always`/`hidden` 仍有效 | 同上（这条是旧文档里唯一站得住的"手机不适用"论据） | 无 |
| `fullscreenCopyOnSelect` | `interactive-mode.ts:1944` | 同上 | 终端页的文本选择是 App 自己的（`ChatScreen`/终端组件的复制逻辑），pi 的选中复制在 alt-screen 里无从触发 | 同上 | 无 |

### A.4 pi 自己也不读（2）—— **已裁决：不补**

| 键 | 结论 | 证据 | 建议动作 | 风险 |
|---|---|---|---|---|
| `enableAnalytics` | **手机不可达** | `grep -rn "getEnableAnalytics" packages/` 全树 0 命中（只有 getter 与一个 test）；`docs/settings.md:61` 自己写"currently only asked for during the experimental first-time setup" | 保持不补 | 旧账说"pi 自己也是只写不读"，**核对成立** |
| `trackingId` | 同上 | `getTrackingId` 同样 0 消费者 | 保持不补 | 同上 |

### A.5 表 A 的净结论

28 = 1 + 7 + 18 + 2，逐类不重复计数：

- **必须补：0 个。**
- **被本应用固定住：3 个**——`sessionDir`（A.1）+ `lastChangelogVersion`、`collapseChangelog`（A.2 中由
  `PI_SKIP_VERSION_CHECK=1` 钉死的两个）。
- **设备上做不到 / 设了有害：5 个**——`terminal.images`、`terminal.showImages`、`terminal.imageWidthCells`、
  `terminal.hyperlinks`、`externalEditor`（A.2 余下的 5 行）。
- **pi 自己也不读：2 个**——`enableAnalytics`、`trackingId`（A.4）。
- **只有原版 TUI 读、终端页可达：18 个**（A.3）——**旧账把它们全判成"手机不可达"是错的**；
  正确处置是"不补成 18 行表单"，但理由必须改成 A.3 的说法，并且**必须给一条真正的通路**
  （§6.3 的「Pi 文件」查看器/编辑器，已实现，或终端页），否则"用户要调它就去终端"这句话在
  `PiPreSpawnConfig` 里被自己的"pi's TUI never runs here"否掉。

**裁决（用户，本批）**：以上 28 条**全部不补进设置页**。18 个 TUI 键的理由从"读者在 RPC 上够不着"
改成 **"这是 TUI 的旋钮：终端页里能改，不搬进设置页"**，并且这是一条**用户决定**而不是可达性结论；
`PiPreSpawnConfig` 里那句与事实矛盾的 "pi's TUI never runs here" 已在同一批修掉（只改那句断言，不动逻辑）。
正式文本：**D49**。

---

## 3. 表 B：App 有、但无用 / 可疑

| 行 | 结论 | 证据 | 建议动作 | 风险 |
|---|---|---|---|---|
| `app.credentials.oauth` | **指路牌，建议合并** | 标题自带「本应用暂无入口」；描述说明订阅登录只能在浏览器/终端完成；命令面板的 `/login` 已有「仅终端」徽标（`PiSlashCommands`）。同一件事两个提示 | 二选一：删行（留命令面板），或保留但在描述里不再重复"去终端" | 删行会少一个发现入口（旧账保留它的理由）；两种都站得住，但不该两处都写 |
| `app.runtime.diagnostics` + `SettingsHome` 的「诊断报告」 | **同一屏幕两个入口，建议合并** | `SettingsHome.onOpenDiagnostics` 与注册表行都指向 `DiagnosticsScreen`；注册表的 KDoc 自己承认"再给一条路径" | 保留注册表行（可搜索、是「运行时与诊断」组的自然成员），删掉首页「关于」段的重复入口；或反之 | 删一个会改变"引擎已退出时用户先翻关于"的可用性（旧账的理由），需产品判断 |
| `themes` 与 `theme` **同名「主题」** | **改名** | 注册表两行 `title = "主题"`；搜索结果会出现两条同名行，靠副标题（`扩展与资源 · 本地资源` / `外观 · 主题`）才分得清 | `themes` → 「自定义主题路径」（或「主题目录」）；`theme` 保持「主题」 | 改名会让老用户的肌肉记忆/文档引用失效（无功能风险） |
| `defaultTools` 的 `powershell` 预设 | **删预设** | pi 的合法取值含 `powershell`（`core/tools/index.ts:95`），但那是 Windows 替代项（`docs/settings.md:272-278`）；App 的 guest 载荷无 `pwsh`（`tools/fetch-runtime.mjs`） | 从 `presets` 里去掉 `powershell`；键的取值域不变（pi 允许的仍然允许，只是不再一键推荐） | 用户手输 `powershell` 仍可写进去（与 pi 一致） |
| `app.runtime.cacheRetention` 的 `defaultValue = str("short")` | **改文案/默认值** | pi 只有 `PI_CACHE_RETENTION=long` 与非设置两态（`@ai/api/anthropic-messages.ts:57`；`PiLaunchOptions.fromSettingValues` 只在 `== "long"` 时开） | 把默认值改成不写（键缺失 = 默认），或在描述里写明 `"short"` 是本应用的词 | 改默认值会让老用户已存的 `"short"` 变成显式值（行为不变） |
| 29 个注册的 `app.*` 键 + `app.workspace.current` / `app.workspace.names` | **不该住在 pi 的文档里** | `PiSettingsFileStore.isAppLocalKey` 只把含 `[]` 的键分流到 `app-prefs.json`；其余全部写进 `<agentDir>/settings.json`；`WorkspaceStore` 还写两个**未注册**的键（`SETTING_KEY` / `NAMES_KEY`） | ① 把全部 `app.*` 迁到 sidecar（需要一次性迁移读旧位置）；② 至少把两个 `app.workspace.*` 注册进注册表或迁走 | 迁移若不做兼容读，老用户的工作区选择会"消失"（会回落到 `workspace-1`，见 `GuestWorkspacePath`）；`app.workspace.*` 未注册意味着**搜索找不到它们**，而这正是"设置页看起来能改一切"的反面 |
| 7 个只读事实行（`piVersion`/`nodeVersion`/`rootfsUsage`/`engineStartup`/`wakeLock`/`prorootStatus`） | **不是"无用"，但与控件混排** | 它们是 `readOnly = true` 的 Text 行 + 一个 Switch（`proroot`）+ 一个 Value | 见 §6.3：拆出「状态」section，或整组移到首页 | 无 |

---

## 4. 表 C：与 pi 不一致（1:1 的真实含义）

每行给"pi 的定义"与"App 的现状"。**本表按可复现程度排序，前 3 条会直接让用户看到错的行为。**

| # | 键 / 行 | pi 的定义（证据） | App 现状（符号） | 结论 | 建议动作 | 风险 |
|---|---|---|---|---|---|---|
| C1 | `enabledModels` 的**生效时机** | 在 `createRuntime` 闭包内被读（`main.ts:788`，闭包范围 `main.ts:714-839`），而该闭包**每次新会话**都跑（`core/agent-session-runtime.ts:237` `newSession` → `:241` `this.createRuntime`），闭包里另有 `SettingsManager.create`（`main.ts:731`） | `effective = EffectiveKind.RestartEngine`（注册表 `enabledModels` 行）+ 行说明「pi 在引擎启动时解析一次，改完要重启引擎才生效」+ 一段声称"NewSession 是错的"的注释 | **不一致（判错）** | 改回 `NewSession`，删掉那段注释，改文案 | 界面标签错 = 用户以为必须重启引擎，白等一次重启；`docs/settings-review.md` §1.1 本来就写的 `NewSession`，是代码这一处改错了 |
| C2 | 所有数字行的 `max` | pi 无上界：`httpIdleTimeoutMs`/`websocketConnectTimeoutMs` 只拒非有限/负数（`core/settings-manager.ts:936-947`）；压缩量只要求非负安全整数（`:860`）；`retry.*` 无校验（`:927-955`）；`sessionDir` 无关 | 注册表给每一行加了 `max`（1h / 5min / 20 次 / 60000…），而编辑器对**手输**也 `coerceIn(low, high)`（`PiNumberEditorSheet`） | **不一致（取值域变窄）** | 把 `max` 降级为"滑块上限"，手输只校验 pi 真正的约束（`>= 0`、整数）；或直接去掉 `max` | 静默夹值：一个 2h 的 `httpIdleTimeoutMs` 打开一次编辑器就被写成 1h；`keepRecentTokens` 在 2M 上下文的模型上无法表达 |
| C3 | `enabledModels` 的**模式语法** | 非通配模式做**子串**匹配（`core/model-resolver.ts:135-160` `tryMatchModel`：先精确，再 `id`/`name` 的子串，alias 优先、否则最新日期版）；通配模式是 minimatch 且先做精确命中（`:280`、`:305`），`[` 也是通配字符；`:level` 后缀判定**大小写敏感**（`cli/args.ts:60-64`）；pi 自己的帮助示例就是逗号分隔的非通配部分名（`cli/args.ts:366`） | `PiModelInventory.matches` 只做"精确 `provider/id` / 精确 `id` / `*`+`?` 的 glob"，`[...]` 被归入"不支持语法"并**返回 false**；`stripThinkingSuffix` 用 `lowercase()` 比等级（比 pi 宽松） | **不一致（App 明明不知道却说"没启用"）** | ① 补上子串规则（含 alias/日期版选择）与 `[...]`；② 做不到的模式走已有的 `unjudgedPatterns`/「无法判断」状态而**不是 `false`**；③ 行说明补上 `provider/id`、裸 id、`:level` 三种拼法 | 用户按 pi 的方式写 `sonnet`，App 的「模型」页会说这个模型不在循环列表 —— 而 pi 实际上会循环它 |
| C4 | 写作用域 | pi 的设置界面一律写 global（`core/settings-manager.ts:788-792` 等各 setter 只改 `globalSettings`）；只有 `pi config` 有**用户可见**的作用域切换（`cli/config-selector.ts` 的 `writeScope`，Tab） | `PiSettingsFileStore.writeLocked`：项目文件存在且**已有该键** → 写项目，否则写全局；界面上没有任何提示 | **不一致（隐式作用域）** | 要么学 pi 一律写 global，要么在行上/编辑器中显示"将写入 项目 `.pi/settings.json`"，并让用户可切 | 用户在 A 工作区改了一个键（因为项目文件里恰好有它），在 B 工作区看不到效果 —— 这正是"设置不生效"类反馈的成因 |
| C5 | `theme` 默认值与命名规则 | 无默认；未设时按**终端背景探测**回退（`cli/startup-ui.ts:87`）。单个主题名**不能含 `/`**（`modes/interactive/theme/theme.ts:480-484`）；自动模式最多一个 `/`（`:580-593`），两个以上即整体无效 | `defaultValue = str("dark")`；主题编辑器对"原始值"不校验斜杠数量 | **不一致（把回退说成默认值）** | 描述改成「手机没有终端背景可探测，未设置时用 dark」；编辑器拒绝单个名字含 `/`，拒绝 2 个以上 `/` | `a/b/c` 被 pi 静默忽略，用户以为选了个主题 |
| C6 | `httpIdleTimeoutMs` 取值域 | 接受 number ≥ 0 与字符串 `"disabled"`（=0），并向下取整（`core/http-dispatcher.ts:19-35`）；**非法值抛异常**（`core/settings-manager.ts:188-197`），而该 getter 在 `main.ts:851` 与 `core/sdk.ts:316` 被调 | 数字行；`"disabled"` 会显示成「未设置」（`intValueOrNull` 返回 null） | **不一致（合法值被显示成未设置）** | 显示层识别 `"disabled"`；并且**读一次 pi 的解析规则**：非法值应显示成错误而不是"未设置" | 用户手写 `"disabled"`（pi 文档没写，但代码支持）会看到"未设置"，以为等于默认 300000 —— 实际是无超时 |
| C7 | `defaultTools` 预设 | 合法值 8 个，`powershell` 是 Windows 项 | `presets` 含 `powershell` | **不一致（设备上不成立）** | 去掉预设（见 §3） | 无 |
| C8 | `thinkingBudgets` 的键域 | 只有 `minimal`/`low`/`medium`/`high`（`core/settings-manager.ts:67-72`） | 对象行编辑器接受任意键 | **不一致（无校验）** | 编辑器把键限制在这 4 个（或对未知键给出警告） | 拼错 `hight` 会被 pi 静默忽略 |
| C9 | `modelThinkingLevels` / `compaction.modelOverrides` 的键 | `"provider/modelId"`，**精确、大小写敏感**（`core/settings-manager.ts:805`、`:866`；`docs/settings.md:154`）；模型 id 可含斜杠 | 对象行编辑器接受任意键，无提示 | **不一致（无校验）** | 键的输入提示 + 大小写敏感说明；`compaction.modelOverrides` 的值必须是对象（pi 会抛，`:868-872`） | 写错键 = 静默无效；写非对象值 = **每次压缩判定抛异常**（`:870`） |
| C10 | JSON 保真 | pi 写 `JSON.stringify(x, null, 2)`（`core/settings-manager.ts:659`、`core/trust-manager.ts:134`、`core/auth-storage.ts:106/187`） | `PiSettingsFileStore.writeDocument` 与 `PiConfigFiles.stringify` 写紧凑 JSON | **不一致（格式）** | 改成 2 空格缩进（语义无风险，且让 diff 可读） | 无（纯格式） |
| C11 | 数组成员的类型保真 | pi 无 schema 校验，数组元素可以是对象（`packages`） | 列表编辑器对以 `{`/`[` 开头的行按 JSON 解析，否则走 `scalarOf`（会把 `true`/数字转成真布尔/数字） | **可能不一致** | 纯字符串数组（`extensions`/`skills`/`prompts`/`themes`/`defaultTools`）应永远按字符串写；现在 `defaultTools` 里手输 `true` 会写成 JSON 布尔 | 用户把 `defaultTools` 的一行写成 `1`，pi 收到数字工具名（无效果但不报错） |
| C12 | `websocketConnectTimeoutMs` 的默认值归属 | 默认在 provider：`@ai/api/openai-codex-responses.ts:50` `15_000`，应用点 `:1049`；getter 本身返回 `undefined`（`core/settings-manager.ts:957-959`） | `defaultValue = num(15000)`，描述说"只对支持 WebSocket 传输的厂商生效" | 大体一致，但默认值归属要说清 | 描述改成"OpenAI Codex 的默认值是 15 秒；其他厂商各自不同" | 其他 WS provider 的默认值可能不同，用户会以为 15s 是全局默认 |
| C13 | `extensions`/`skills`/`prompts`/`themes`/`packages` 的 `EffectiveKind` | 这些路径在**每次建会话**时被重新读取，三处证据：① `SettingsManager.create` 在建会话闭包内（`main.ts:731`，闭包 `main.ts:714-839`）；② 资源加载器**每次 `reload()` 都重读设置并重新解析包路径**（`core/resource-loader.ts:386-404`：`await this.settingsManager.reload()` → `const resolvedPaths = await this.packageManager.resolve()`）；③ 包管理器读的就是这个 manager（`core/package-manager.ts:914/978`），而加载器由 `createAgentSessionServices` 在闭包内构造（`core/agent-session-services.ts:147-154`） | 五行都是 `RestartEngine` | **可改为 `NewSession`**（旧账 §7.2 把它列为"不确定"，现证据齐了） | 改成 `NewSession`，删掉 §7.2 的悬置 | 标成"需重启引擎"会让用户多付一次重启成本；反向风险：如果 App 的安装流程依赖重启（`ExtensionLifecycle` + `EngineRestartCoordinator`），改了标签不会改变那条流程 |
| C14 | `enableSkillCommands` | pi 只在 TUI 注册命令（`interactive-mode.ts:716`） | App 读它构建自己的命令面板（`PiSessionViewModel` 的 `piCommandPalette`），并在写后刷新 | 一致（App 是消费者） | 无需动作 | 无 |
| C15 | `app.runtime.systemPrompt` / `appendSystemPrompt` 与 **SYSTEM.md 文件**的关系 | `--system-prompt` **优先于**文件：`const systemPromptSource = this.systemPromptSource ?? this.discoverSystemPromptFile()`（`core/resource-loader.ts:526`），文件位置 = `<agentDir>/SYSTEM.md`（`:1029`）与 `<cwd>/.pi/SYSTEM.md`（`:1024`，需项目受信）；`APPEND_SYSTEM.md` 同理（`:1038`/`:1043`） | 两行描述**完全没提文件**，只说"整份替换 pi 自带的系统提示词" | **文案缺一半**（行为与 pi 的 flag 一致，是"第二真相"没说出来） | 描述里加一句"设置后 `~/.pi/agent/SYSTEM.md` 与项目 `.pi/SYSTEM.md` 会被忽略"；并提供这些文件的查看/编辑入口（§6.3） | 用户写好的 `SYSTEM.md` 被一个非空设置静默盖掉，且 App 里看不到该文件 |
| C16 | `images.*` 的 `EffectiveKind` | `blockImages` 在每次转换消息时**动态读**（`core/sdk.ts:271-273`，"Check setting dynamically so mid-session changes take effect"），`autoResize` 在附件/read 时读（`agent-session.ts:522/2792`） | 两行 `NewSession` | **一致**（动态读的是同一个进程内的 `SettingsManager` 快照，文件改动仍要等新会话） | 无需动作 | 无 |
| C17 | `enableInstallTelemetry` 的 `EffectiveKind` | 每次构建归因头时问一次 `settingsManager`（`core/telemetry.ts:8-14` ← `core/provider-attribution.ts:40`），但 manager 的文档只在建会话时重读 | `NewSession` | 一致 | 无需动作 | 无 |
| C18 | `defaultProjectTrust` | 非交互模式用它的是**启动时**那个 manager（`main.ts:745` `startupSettingsManager`；`docs/settings.md:16`） | `RestartEngine` | 一致（比 "NewSession" 更严） | 无需动作 | 无 |

---

## 5. 信息架构：为什么乱，以及目标骨架

### 5.1 现状骨架（工作区当前内容）

组序与 section（来自 `PiSettingsCatalog.settings` 的 `group`/`section`，显示顺序 = 注册表顺序）：

| 组（id / 标题） | section（按出现顺序） | 行数 |
|---|---|---|
| `G_MODEL` 模型与推理 | 本机模型(1) → 默认模型(5) → 行为(2) → 循环模型(1) → 凭证(3) | 12 |
| `G_PROMPTS` 提示词 | 系统提示词(2) | 2 |
| `G_MESSAGES` 消息与网络 | 送达(2) → 传输(1) → 网络(3) | 6 |
| `G_COMPACTION` 上下文与压缩 | 压缩(4) → 分支摘要(1) → 动作(1) | 6 |
| `G_RETRY` 重试与网络 | 重试(4) → Provider 重试（高级）(3) | 7 |
| `G_TOOLS` 工具 | 内建工具(1) → 展示(1) | 2 |
| `G_SESSIONS` 会话 | 存储(1) | 1 |
| `G_RESOURCES` 扩展与资源 | 本地资源(5) → 资源包(1) | 6 |
| `G_APPEARANCE` 外观 | 主题(1) → 排版(4) → 图片(2) | 7 |
| `G_TERMINAL` 终端与 Shell | Shell(3) → 终端显示(1) → 按键条(1) | 5 |
| `G_SECURITY` 安全与信任 | 信任(1) → 动作(1) | 2 |
| `G_RUNTIME` 运行时与诊断 | 版本(1) → 运行时(5) → 进程(4) → 后台(2) → 诊断(1) | 13 |
| `G_ABOUT` 隐私与关于 | 隐私(1) | 1 |

合计 70 行；首页另有 5 个非注册表入口（设备能力 / 资源包 / 终端 / 模型 / 开源许可 / 诊断报告）。

### 5.2 与 pi 自己分组的对比

pi 有**两套**组织，都不同于 App：

| pi 的组织 | 内容 |
|---|---|
| `docs/settings.md` 的分节（16 节） | Model & Thinking / UI & Display / Telemetry and update checks / Network / Warnings / Compaction / Branch Summary / Retry / Message Delivery / Terminal & Images / Shell / Tools / Sessions / Model Cycling / Markdown / Resources |
| TUI 的**一条扁平列表**（`settings-selector.ts:462-830`，30 行，无分组，只有 3 个子菜单） | 顺序见 §1.3：压缩 → 图片 → 技能命令 → 光标/内边距/输出 → 自动补全 → 终端重绘 → 送达 → 传输 → 超时 → 思考 → markdown → 通知 → 更新 → 遥测 → 信任 → 双击 Esc → 树过滤 → 警告 → 逐模型思考 → TUI 模式 → 全屏 → 主题 |

**pi 没有"分组"这件东西**：它用一条扁平列表 + 子菜单。App 的 13 组是**app 自己的设计**
（`docs/pi-android-ui-spec.md` §6.4 的 12 组 + 提示词组）。所以"和 pi 1:1"在**信息架构上不适用**——
pi 侧没有对应物可抄；能抄的只有 `docs/settings.md` 的**分节归属**。下面按这个标准挑错。

### 5.3 具体的乱点

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| I1 | **同名两行**：`themes` 与 `theme` 都叫「主题」 | 注册表两行 `title = "主题"`；搜索会同时命中 | `themes` 改名「自定义主题路径」 |
| I2 | **section 名与内容不符**：`G_RETRY` 叫「重试与网络」，里面没有网络；网络在 `G_MESSAGES` | `G_RETRY` 的 7 行全是 `retry.*`；`httpIdleTimeoutMs`/`httpProxy` 在 `G_MESSAGES/网络` | 组名改「重试」，或把网络行搬过来 |
| I3 | **单行 section**：`传输`(1)、`主题`(1)、`隐私`(1)、`会话/存储`(1)、`工具/内建工具`(1)、`主题/图片` 之外还有 `分支摘要`(1)、`动作`(1) | 各组 `sectionsIn` 的结果 | 单行 section 的 section 头去掉，直接并入邻节（如 `transport` 并入「网络」，`theme` 并入「显示」） |
| I4 | **`enabledModels` 独自一节「循环模型」**，但它与 `defaultModel`/`defaultProvider` 是同一件事（"用哪些模型"） | pi 的 `docs/settings.md` 有独立的 "Model Cycling" 节，但也把它和 `/model` 的选择放在同一叙事里（`:294-304`） | 并入「默认模型」节，位置在 `defaultModel` 之后 |
| I5 | **`G_MODEL/凭证`(3 行) 排在组末**，而"先填 Key 才有模型"是新用户的第一步 | pi 的首次流程（`cli/setup.ts`）就是登录/填 Key 优先；`docs/settings.md:10` 也说 `/model` 与 Ctrl+S 是设默认模型的正路 | 把「凭证」提到组首（在 `app.models.inventory` 之后），或在首页的「当前模型卡」里直接给"未配置 → 去填 Key"的路径 |
| I6 | **`app.localModels.manage` 挂在「凭证」**，但它配的是端点（`models.json` + 占位 Key），不是凭证 | 行自身的描述与 `PiCredentialService` 都写它写 `models.json` | 移到「本机模型」节（与 `app.models.inventory` 同节） |
| I7 | **`G_ABOUT` 组只有一行**（`enableInstallTelemetry`），却叫「隐私与关于」，而"关于"内容（许可、诊断）在首页 | `G_ABOUT` 的 `settingsIn` 只有 1 行 | 组名改「隐私」；或把「开源许可」做成注册表行（注册表把它删掉的理由写在 `app.about.licenses` 的注释里："合规面不是 pi 行为"——那就更不该由组名承诺） |
| I8 | **`G_RUNTIME/运行时` 节混了控件与事实**：`proroot`（Switch）与 `nodeVersion`/`rootfsUsage`/`engineStartup`/`prorootStatus`（只读）同节 | 该节 5 行里 4 行 `readOnly` | 拆成「运行时状态」（只读事实）与「运行时选择」（`proroot`）；或把 4 个只读事实移到首页状态卡 |
| I9 | **`G_COMPACTION/动作` 与 `G_SECURITY/动作` 与首页的危险区**：`app.security.emergencyStop` 同时出现在组的「动作」节和页面底部（`SettingsGroupScreen` 的注释写了"两处必须走同一条路径"） | 该注释自己描述了这件事 | 保留（有意为之），但两处的文案必须完全一致 |
| I10 | **`G_APPEARANCE` 混 pi 键与 app 键**：`theme`（pi）与 `app.appearance.*`（app）同组；`images.*`（pi）被放进「图片」节，而 pi 把 `images.*` 与 `terminal.*` 放同一节 | `docs/settings.md:215-226` | 把 `images.*` 移到「终端与 Shell」组（与 pi 的分节一致，且该组只有 5 行），外观组只留主题与排版 |
| I11 | **`G_PROMPTS` 组名与 `prompts` 键同名**（注册表注释自己声明"这个 id 与 `prompts` 键无关"） | 注册表 `G_PROMPTS` 的注释 | 组名改「系统提示词」（与它的唯一 section 同名） |
| I12 | **动作行与设置行同形**：7 个 Action 行（凭证 2 + 本机模型 1 + 立即压缩 1 + 紧急停止 1 + 重启引擎 1 + 导出诊断 1）与布尔/数值行同一套视觉 | `SettingsGroupScreen.openRow` 的三分支（hostAction / Action→确认框 / 其余→编辑器） | 动作行给统一的可识别样式（如右侧按钮化），或全部收敛到「动作」节 |

### 5.4 目标骨架（只诊断，不实施）

原则：**先按"这行是谁的"分三段，再按 pi 的 `docs/settings.md` 分节**。

```
设置首页
├─ 搜索
├─ 当前模型卡                      （默认模型 + 思考等级，pi 的 /model 心智）
├─ 模型                            （app 自己的屏幕：厂商/模型/是否生效）
├─ 设备能力 / 资源包 / 终端          （非 pi 设置，各自一个屏幕）
└─ 全部设置（13 组 → 建议 11 组）
   1. 模型与推理       本机模型(app.localModels.manage) → 凭证(apiKey, oauth) →
                       默认模型(defaultProvider/defaultModel/defaultThinkingLevel) →
                       循环模型(enabledModels，并入「默认模型」节) →
                       逐模型与预算(modelThinkingLevels/thinkingBudgets) → 行为(hideThinkingBlock/showCacheMissNotices)
   2. 提示词           SYSTEM.md 文件入口 + 自定义系统提示 + 追加系统提示
   3. 消息与网络       送达(steeringMode/followUpMode) → 传输(transport) → 网络(httpIdleTimeoutMs/
                       websocketConnectTimeoutMs/httpProxy)
   4. 上下文与压缩     压缩(4) → 分支摘要(reserveTokens/skipPrompt[不可达，不列]) → 动作(立即压缩)
   5. 重试             （组名去掉"与网络"）重试(4) → Provider 重试(3)
   6. 工具             内建工具(defaultTools) → 展示(app.tools.expandByDefault)
   7. 会话             存储(app.sessions.resumeLast) + 会话目录说明（sessionDir 被钉住的事实写在这里）
   8. 扩展与资源       本地资源(5) → 资源包(1)
   9. 外观与终端       主题(theme) → 排版(4) → 图片(images.*)
  10. 运行时与诊断     运行时状态(4 个只读事实) → 运行时选择(proroot) → 进程(4) → 后台(2) → 诊断(导出)
  11. 安全与信任       信任(defaultProjectTrust) → 动作(紧急停止)
  12. 隐私            安装遥测
```

搬动理由（每条一句话）：

| 搬动 | 理由（证据） |
|---|---|
| `app.localModels.manage` 凭证 → 本机模型 | 它写 `models.json`（端点/模型定义），不写 `auth.json` |
| 凭证提到「默认模型」之前 | 新用户必须先有凭证才有可选模型（pi 的首次流程同序） |
| `enabledModels` 独立节 → 并入「默认模型」 | 与 `defaultModel` 回答同一个问题；pi 文档也把它与模型选择同段 |
| `themes` 改名 | 消除与 `theme` 的同名 |
| `images.*` 外观 → 终端与 Shell | pi 的 `docs/settings.md` 把它们与 `terminal.*` 同节（`:215-226`）；且该组只有 5 行 |
| `G_RETRY` 改名「重试」 | 组内没有网络设置 |
| `G_PROMPTS` 改名「系统提示词」+ 加 SYSTEM.md 入口 | 与唯一 section 同名，并让"文件形态"可见（§6.3） |
| `G_RUNTIME/运行时` 拆成状态/选择 | 只读事实与控件不能同形同节 |
| `G_ABOUT` 改名「隐私」 | "关于"内容不在这个组里 |

---

## 6. 文件面：pi 到底读哪些文件，App 现在能看/能改什么

### 6.1 pi 读写的文件清单

`agent dir` = `getAgentDir()`（`config.ts:528-535`）= `$PI_CODING_AGENT_DIR` 或 `~/.pi/agent`；
项目目录 = `<cwd>/<CONFIG_DIR_NAME>`（`config.ts:504`，`.pi`，来自 `package.json` 的 `piConfig.configDir`）。
guest 里 agent dir 是 `/root/.pi/agent`（App bind 上来）。

| 文件 / 目录 | 格式 | 权限 | pi 加锁？ | pi 运行期会重写？ | 证据 |
|---|---|---|---|---|---|
| `<agentDir>/settings.json` | JSON，2 空格缩进；缺失键 = 用默认；未知键**保留**（写入前重读文件、只覆盖改过的字段） | 默认 umask（无 0600） | **是**：`proper-lockfile.lockSync(path, {realpath:false})`，10 次 × 20ms；锁是 `<file>.lock` **目录** | 是（每次 `setX`） | `core/settings-manager.ts:236-291`、`:632-661`、`:659` |
| `<workspace>/.pi/settings.json` | 同上；作为**覆盖层**深合并（数组 replace、对象 merge，`isMergeableObject` 排除数组） | 同上 | **是**（同一条 `withLock("project")`） | 是（`setProject*` 系列 + `pi install -l`） | `core/settings-manager.ts:164-186`、`:233`、`:1094-1162` |
| `<agentDir>/models.json` | JSON；顶层 `providers`；模型只有 `id` 必填；**注释会被剥掉再 parse**（`stripJsonComments`）；provider 的 `oauth` 只能是字面量 `"radius"` | 默认 | **不加锁**（pi 从不写它） | **否**（pi 只在启动/refresh 读） | `core/model-config.ts:199-215`、`:251-290`（`model-runtime.ts:174-175`）；App 侧的取证见 `PiConfigFiles` 类 KDoc |
| `<agentDir>/models-store.json` | JSON，2 空格缩进；动态发现模型的缓存 | 默认 | 有锁（`ModelsStore` 自己的 withLock） | **是**（refresh 时） | `core/models-store.ts:52`、`:132/143`、`core/model-runtime.ts:180` |
| `<agentDir>/auth.json` | `Record<providerId, Credential>`；`api_key` = `{type,key,env?}`，`oauth` = `{access,refresh,expires,...}`；pi **读**时做 revision 校验 | **`mode: 0o600`（只在创建时生效**，不 chmod 已存在的文件） | **是**：`lockfile.lockSync(authPath, {realpath:false})` | 是（登录/改 Key 时） | `core/auth-storage.ts:17`、`:25`、`:60-110`、`:341` |
| `<agentDir>/trust.json` | `Record<规范化绝对路径, true\|false\|null>`，键排序，2 空格 + 末尾换行 | 默认 | **是**：`lockfile.lockSync(trustDir, {realpath:false, lockfilePath: <path>.lock})` | 是（`/trust`、信任弹窗） | `core/trust-manager.ts:125-135`、`:143-152`、`:213` |
| `<agentDir>/keybindings.json` | JSON，命名空间 id → 键数组；旧 id **在内存里迁移**，不改文件 | 默认 | 否（只读） | **否**（`/reload` 只重新载入） | `core/keybindings.ts:380`、`docs/keybindings.md:3-7` |
| `<agentDir>/AGENTS.md`（或 `AGENTS.override.md`/`AGENTS.MD`/`CLAUDE.md`/`CLAUDE.MD`） | Markdown，整份注入上下文 | 默认 | 否 | 否 | `core/resource-loader.ts:72`、`:129-133`（全局） |
| `<cwd>`…根各级 `AGENTS.md` | 同上，**从根到 cwd 每一级各取第一个命中**；被 worktree 阴影的那个跳过 | 默认 | 否 | 否 | `core/resource-loader.ts:135-154` |
| `<agentDir>/SYSTEM.md` | Markdown，**整份替换**系统提示 | 默认 | 否 | 否 | `core/resource-loader.ts:1029`、`:526` |
| `<cwd>/.pi/SYSTEM.md` | 同上，**优先于全局**，但只在项目受信时（`isProjectTrusted()`） | 默认 | 否 | 否 | `core/resource-loader.ts:1023-1027` |
| `<agentDir>/APPEND_SYSTEM.md` / `<cwd>/.pi/APPEND_SYSTEM.md` | Markdown，**追加** | 默认 | 否 | 否 | `core/resource-loader.ts:1037-1048` |
| `<agentDir>/themes/*.json`、`<cwd>/.pi/themes/*.json`，以及 `themes` 设置里的路径 | pi 主题 JSON（`colors` + 可选 `vars`）；名字不能含 `/` | 默认 | 否 | 否（只读；有文件 watcher，改完热重载） | `config.ts:537-539`、`core/resource-loader.ts:875`、`modes/interactive/theme/theme.ts:449-471`、`:834-860` |
| `<agentDir>/skills/**/SKILL.md`、`<cwd>/.pi/skills/**`、`<cwd>/.agents/skills/**`、`~/.agents/skills/**` | 一个技能 = 一个带 name/description 的文件；默认目录一层深 | 默认 | 否 | 否 | `core/skills.ts:448-455`；协议面 `core/package-manager.ts:365-395` |
| `<agentDir>/prompts/**.md`、`<cwd>/.pi/prompts/**.md` | 提示模板，支持 `$1`/`$@`/`${1:-default}` | 默认 | 否 | 否 | `core/prompt-templates.ts:203` |
| `<agentDir>/extensions/**`、`<cwd>/.pi/extensions/**`（项目优先） | 扩展（`index.ts`/`package.json` 入口） | 默认 | 否 | 否 | `core/extensions/loader.ts:783-790` |
| `<agentDir>/npm/node_modules/<name>`、`<cwd>/.pi/npm/node_modules/<name>` | 包安装目录；`pi install` 写 | 默认 | 装包时有自己的锁（`package-manager-cli.ts:160/178`） | 是（install/update/remove） | `core/package-manager.ts:2031-2033`、`:2068-2074` |
| `<agentDir>/sessions/**.jsonl` | JSONL：首行 header（含 cwd、模型、时间），其后每行一个 entry；**同一 session 文件持续 append** | 默认 | 不加锁（实验性 session-worker 例外：`experimental/session-worker.ts:533`） | **是**（每轮 message_end；压缩/fork 会重写） | `core/session-manager.ts:998`、`:1035`、`:1054`、`:1652-1657` |
| `<agentDir>/sessions/--<cwd-slug>--/` | **默认布局**（按 cwd 分组）；App 用 `--session-dir` 钉成平铺 | 默认 | 否 | 是 | `core/session-manager.ts:476-489`、`:1551` |
| `<agentDir>/bin/` | pi 管理的二进制（fd、rg） | 默认（二进制 0755） | 否 | 是（缺了就下） | `config.ts:562-564`、`utils/tools-manager.ts:10`、`:322` |
| `<agentDir>/tools/` | `getToolsDir()`，**全树没有消费者**（只有定义） | — | 否 | 否 | `config.ts:557-559`（`grep -rn "getToolsDir"` 只命中定义） |
| `<agentDir>/pi-debug.log` | 调试日志 | 默认 | 否 | 是（TUI 出错时写） | `config.ts:577-579`、`modes/interactive/interactive-mode.ts:6452` |
| `<agentDir>/` 目录本身 | — | App 的 `ensureAgentMirrorDir` 用默认权限；pi 创建时 `mkdirSync(..., {recursive:true, mode:0o700})`（仅 auth 路径） | — | — | `core/auth-storage.ts:59-62` |

**锁的实现细节（写文件的人必须照做）**：`proper-lockfile` 的锁是**目录** `<target>.lock`（mkdir 原子）；
`lockSync(path, {realpath:false})` 默认重试 10 次、间隔按指数退避起步 20ms 左右，stale 窗口 10s；
trust.json 用的是 `lockfilePath: <path>.lock` 这一形式（等价效果，但锁目录名不同：
`trust.json.lock`）。App 侧已有等价实现：`PiConfigFiles.withLock`。

### 6.2 App 的现状矩阵

行 = pi 的文件；列 = 现在能看吗 / 能改吗 / 该不该给。

| pi 文件 | 现在能看吗 | 现在能改吗 | 该不该给 |
|---|---|---|---|
| `<agentDir>/settings.json` | **否** | 字段级只（设置页各行的编辑器 / 凭证页写 selection 三键）；整份文件**看不到** | 该给「看 + 受限改」（§6.3） |
| `<agentDir>/models.json` | **否**（字段级：凭证页预填读它与 `models-store.json`） | 字段级只（`PiModelsFile.upsert`，保留未建模的键） | 该给「看 + 受限改」 |
| `<agentDir>/auth.json` | **否**（凭证页只显示掩码） | 字段级只（`PiAuthStorage.setApiKey`，保留 `oauth` 条目、0600、pi 的锁） | **只给看（掩码）**；**绝不**给整份文本编辑 |
| `<agentDir>/models-store.json` | **否** | 否（凭证页只读它做预填） | 只给看（诊断用） |
| `<agentDir>/trust.json` | **否** | 否（唯一真相是 `ProjectTrust` + 信任弹窗） | **只给看**，且文案写明"信任决定请在本应用的项目页改" |
| `<agentDir>/keybindings.json` | **否** | **否** | 该给「看 + 改」（pi 没有编辑器，只有文件）。**但文案必须写明：它只影响在终端里跑的原版 pi TUI，对本应用自己的界面没有任何作用**——否则又是一次"以为改了 pi 的设置"（`app.interaction.keybindings` 那个冒牌行被删掉就是这个原因） |
| `<agentDir>/AGENTS.md`、`<cwd>/**/AGENTS.md` | 项目内那份**能**（工作区文件树）；全局那份**不能** | 项目内那份**能**（`WorkspaceFiles.writeText`，非原子、无锁） | 该给「看 + 改」；**并要在 `app.runtime.noContextFiles` 行说明这些文件的存在与覆盖顺序** |
| `<agentDir>/SYSTEM.md`、`<cwd>/.pi/SYSTEM.md` | 项目内那份**能**（文件树里的 `.pi/`）；全局那份**不能** | 项目内那份**能** | 该给「看 + 改」；与 `app.runtime.systemPrompt` 的优先关系必须写在两行上（§4 C15） |
| `<agentDir>/APPEND_SYSTEM.md` 等 | 同上 | 同上 | 同上 |
| `<agentDir>/themes/*.json`、`<cwd>/.pi/themes/*.json` | **能**（资源段的「主题」类，只读） | **否**（`editable = false`） | 该给「看 + 改」（主题是用户最常自己写的文件之一）；改完 pi 有 watcher 热重载 |
| `skills` / `prompts` / `extensions` 各目录 | **能**（资源段，只读；来自 `.pi` / `.agents` / agent dir / 包） | **否** | 该给「看 + 改」，**但要区分目录**：包目录里的文件（`npm/node_modules/**`）改了会在下次 update 被覆盖 → 只给看 |
| `<agentDir>/npm/**`、`<cwd>/.pi/npm/**` | 部分（资源段把包里的扩展/技能列出来） | 否 | 只给看（写由包管理屏幕负责） |
| `<agentDir>/sessions/**` | **能**（会话列表页，读 header 与 entry） | 删除会话（`PiSessionViewModel.deleteSession`），不能编辑内容 | **不给文本编辑**（pi 正在 append；改了会被覆盖/破坏索引）。若给，只读查看 + 导出 |
| `<workspace>/.pi/settings.json` | **能**（工作区文件树的 `.pi/` 下，当普通文本） | **能**（`WorkspaceViewer` 的编辑态 → `WorkspaceFiles.writeText`，整份覆盖、非原子、无锁） | **这是当前最危险的一条**：与 `docs/settings-review.md` §8「App 有意不提供原始 settings.json 编辑器」自相矛盾。要么关掉这条路（把 `.pi` 从文件树里挡掉），要么把它升级成受约束的编辑器（§6.3） |
| `<workspace>/AGENTS.md`、`<workspace>` 其它文件 | 能 | 能 | 保持（这是工作区，本来就是用户的地盘） |
| `<agentDir>/bin/**`、`pi-debug.log` | 否 | 否 | 只给看（诊断）或不给 |

### 6.3 最小的「Pi 文件」查看 / 编辑器方案

**入口**：设置首页「其他」段（现在只有 终端 / 模型 两行），加第 3 行「Pi 文件」；
或放在「运行时与诊断」组末尾。理由：它既不是 pi 设置也不是 pi 功能（是文件），
与「模型」「终端」同类，放首页最自然。

**根**（两个，切换选择器）：

1. `~/.pi/agent`（宿主 `<files>/pi/.pi/agent`，即 `PiPaths.agentDir` / `AgentLayout.agentMirrorDir`）
2. `<workspace>/.pi`（`PiProjectConfig.root(workspace)`）

**文件清单（白名单，不给自由浏览）**——每项标注哪个是"看"哪个是"改"：

| 项 | 档 | 说明 |
|---|---|---|
| `settings.json`（两个根） | **改**，但只能"受约束的改" | 见下 |
| `models.json` | **改** | 已有 `PiModelsFile` 的类型化写；文件视图只做查看 + 语法校验提示 |
| `auth.json` | **只看（掩码）** | `key` 显示成 `PiAuthStorage.mask`；不给整份编辑，写一律走凭证页 |
| `keybindings.json` | **改** | pi 没有编辑器；写前用 pi 的规则校验（`docs/keybindings.md` 的键名/修饰键），并提示"这一份只影响在终端里运行的原版 pi TUI" |
| `SYSTEM.md` / `APPEND_SYSTEM.md`（两个根） | **改** | 与 `app.runtime.systemPrompt` 的关系写在页头（设置非空时文件被忽略） |
| `AGENTS.md` / 其它上下文文件名 | **改** | 同样的覆盖顺序说明 |
| `themes/*.json` | **改** | 写前校验：必须是对象且含 `colors`；名字不能含 `/` |
| `skills/**`、`prompts/**`、`extensions/**`（两个根，非包目录） | **改** | 扩展的 `index.ts` 给语法高亮但**不校验**（如实说明"改错会让 pi 报扩展加载错误"） |
| `trust.json`、`models-store.json`、`npm/**`、`bin/**`、`sessions/**`、`pi-debug.log` | **只看** | 每条给一句"为什么不能改"（pi 运行期自己写 / 有别的权威界面） |

**写的时候必须满足的 pi 约束**（每一条都有 pi 侧依据）：

1. **锁**：`settings.json`/`auth.json` 用 pi 的 `<file>.lock` **目录**锁，10 次 × 20ms（`core/settings-manager.ts:236-261`、`core/auth-storage.ts:68-100`）。App 已有等价物 `PiConfigFiles.withLock`；**当前设置页的写路径没有用它**（`PiSettingsFileStore.write` 只有进程内 `synchronized`）——两个写路径必须统一。
2. **原子写**：临时文件 + rename（pi 没有做，是被 App 改进过的；`PiConfigFiles.write` 已实现）。注意 `PiConfigFiles` 的临时名是 `<name>.tmp-<pid>`，与 `PiSettingsFileStore` 相同 → 两个写入方会撞同一个临时文件（见 `docs/settings-audit-impl.md` 的 B3），必须加随机后缀。
3. **0600**：`auth.json` 创建时 `mode: 0o600`，**不改已存在文件的权限**（`core/auth-storage.ts:24-25`）；App 的 `restrictToOwner` 会收紧已有文件 —— 比 pi 更严，可保留但要在 KDoc 里写明这是有意差异。
4. **JSON 保真**：数组 replace、对象 merge；未知键保留；**2 空格缩进**（与 pi 的 `JSON.stringify(x, null, 2)` 一致）。
5. **app-only 键不许写进 pi 的文档**：`app.*` 应走 `app-prefs.json` sidecar（机制已存在：`PiSettingsFileStore.isAppLocalKey` + `appLocalFile`）。现状是**只有含 `[]` 的键**走 sidecar，其余 29+2 个 `app.*` 键住在 pi 的 `settings.json` 里（§0.2）。
6. **不允许把键写成 pi 会抛异常的东西**：`compaction.reserveTokens/keepRecentTokens` 非负安全整数（`core/settings-manager.ts:860-878`）、`compaction.modelOverrides[model]` 必须是对象（`:868-872`）、`httpIdleTimeoutMs`/`websocketConnectTimeoutMs` 必须能被 `parseHttpIdleTimeoutMs` 解析（否则 `:188-197` 抛，且在 `main.ts:851` 就会让引擎起不来）。

**绝不直接在 App 里手改的文件**（会被 pi 运行期覆盖，或会破坏唯一真相）：

| 文件 | 原因 |
|---|---|
| `auth.json` | pi 每次凭据变更整份重写（`core/auth-storage.ts:106/187`），且它带 `0600` 与 `oauth` 结构；手改会丢掉 revision 校验的语义 |
| `models-store.json` | pi 的 `refresh()` 会重写（`core/models-store.ts:132/143`） |
| `sessions/**.jsonl` | pi 在 `message_end` 持续 append（`core/session-manager.ts:1035/1054`），压缩/fork 会重写整份；手改会让索引与 `-c` 排序错乱（它是唯一从数据里恢复的真相） |
| `trust.json` | 它必须与 `ProjectTrustStore`/信任弹窗的流程一致；`docs/known-gaps.md` §I10 的教训就是"授权只能有一个真相来源" |
| `bin/**` | pi 自己下载/校验/覆盖（`utils/tools-manager.ts`） |
| `npm/**` 下的包文件 | `pi update`/`install` 会覆盖；改它们等于改了别人的包 |

**如果只做一件事**：先把 `<workspace>/.pi/settings.json` **从工作区文件树的自由编辑里挡住**
（或至少加一条"这是 pi 的设置文件，建议在设置页改"的确认），再按上面的白名单加「Pi 文件」屏。
现状是"最该被保护的那份文件反而最容易被整份覆盖"。

### 6.4 本批落地记录（`applied (uncommitted)`）与相对 §6.3 的偏差

新增（都不在别人的批次里）：

| 文件 | 作用 | 对应上面哪一条 |
|---|---|---|
| `app/src/main/kotlin/app/pi/settings/PiFiles.kt` | 纯逻辑：两个根、可写档判定（默认拒绝）、文档类别、写前 JSON 校验、路径工具 | "根的枚举 / 路径→可写档 / 文件类型 / 写入前置校验" |
| `app/src/main/kotlin/app/pi/ui/screens/PiFilesScreen.kt` | 屏幕：两个根切换、目录树、查看、编辑 | §6.3 的入口、白名单、写入约束 |
| `app/src/test/kotlin/app/pi/settings/PiFilesCheck.kt` | 裸 JVM harness（115 条断言） | 纯逻辑的守门 |
| `SettingsHome.kt`（唯一被改的在飞目录文件） | 「其他」段第 3 行入口 + 自托管 | §6.3 的入口 |
| `rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt` | 只改那句与事实矛盾的 `pi's TUI never runs here`（见 §0.1、D49） | — |

复用而不是重写：查看器 = `WorkspaceViewer`（只读）；锁与原子写 = `PiConfigFiles.withLock` +
`PiConfigFiles.write`；列目录/类型/大小/时间 = `WorkspaceFiles.list` / `kindOf` / `formatSize` /
`formatTime`；注释剥离 = `PiJsonComments.strip`。

**四处偏差，按现实改的（§6.3 的原文保留在上面，这里说明改了什么、为什么）**：

1. **查看与编辑不是同一个组件**。§6.3 写的是"复用 `WorkspaceViewer` 查看/编辑"，但它的编辑态
   保存路径硬编码成 `WorkspaceFiles.writeText`（整份覆盖、非原子、无锁），与本任务的硬约束
   "写入必须走 `PiConfigFiles.withLock` + `write`"直接冲突，而改 `WorkspaceViewer` 不在本批范围。
   所以：**查看**复用 `WorkspaceViewer`（传 `editable = false`，它仍然给只读预览、二进制、超大
   文件与 HTML），**编辑**由 `PiFilesScreen` 自己提供，入口是查看器右上角的 ⋮（`onMore`）。
2. **`keybindings.json` 落在只读**。§6.3 判"改"，而本轮用户给的收紧白名单里没有它，白名单是
   穷举的，所以默认拒绝把它落成只读（原因写在 `piFilesReadOnlyReason` 里，说明它只影响终端里的
   原版 TUI）。要放开只需把它加进 `WRITABLE_ROOT_FILES` 一行。
3. **主题校验比 §6.3 更严，但不去抄 pi 的色表**。§6.3 只写"必须是对象且含 `colors`"；pi 的 schema
   还要求 `name` 是必填的非空字符串、且名字不能含 `/`（`theme-json.ts:20`、`:140-143`）。这两条
   本批做成了 blocker。pi 还会要求 `colors` 里的必需色一个不缺（`:103-137`）—— **这一条不复制**：
   那份清单属于 pi 的 schema，抄一份就是第二个会漂移的真相；编辑器改为给一句提示（缺色的名字由
   pi 自己报）。
4. **入口由 `SettingsHome` 自托管，没有接在 `PiRoot.kt`**。现实是设置首页的宿主是
   `PiSettingsStack`（不是 `PiRoot`），而它正在别人的批次里（`M`）。所以本批让 `SettingsHome`
   自己托管这一屏：`onOpenPiFiles` 参数为 `null`（默认）时，行点击就地用 `PiFilesScreen` 替换
   首页内容；传了值就交还给调用方。搬到 `PiSettingsStack` 只需在那里加一个布尔并把
   `onOpenPiFiles = { piFiles = true }` 传进来。

**未完成 / 未验证**：

- **Compose 编译器插件没有跑**：`tools/typecheck.sh` 的头部自己写明它不加载 Compose plugin，
  所以 composable 调用规则与 `@OptIn` 必要性**没有**被检查；那两类错误只能在 CI 的
  `:app` 构建里暴露。
- **没有真机验证**：两个根是否真的指向 `PiFilesScreen` 以为的那两个目录（宿主路径
  `<files>/pi/.pi/agent` 与 `<files>/pi/workspaces/<ws>/.pi`）、编辑器在有软键盘时的可用性、
  保存后设置页是否立刻显示新值（依赖 `PiDirectoryWatch` 的 inotify 回调）。
- **本批未动**（按裁决）：`<workspace>/.pi/settings.json` 仍然能从工作区文件树自由编辑 —— 上面
  "如果只做一件事"说的那件事**不归本批**（`ui/screens/Workspace*.kt` 是别人的批次）。

---

## 7. 旧账核对（哪些结论过期 / 错了）

| 旧账 | 旧说法 | 核对结果 |
|---|---|---|
| `docs/settings-review.md` §1.2 + §9（25 个 TUI 键"读者够不着"） | 「App 的引擎是 RPC，不渲染 TUI，所以这些键在手机上够不着」 | **判据错**。App 自带终端页可运行原版 pi（`SettingsHome` 的终端行原文 + `PtyLauncher.Spec.environment()` 的注释），TUI 读同一个 `settings.json`。逐键的正确分类见 §2 A.2/A.3：真不可达 7 个，其余 18 个是"只有原版 TUI 读、终端页可达" |
| `docs/settings-review.md` §1.1 `enabledModels` = `NewSession` | （写对了） | **对的**，但**代码是 `RestartEngine` 且注释反过来骂 `NewSession`**（注册表 `enabledModels` 行）。证据：`main.ts:788` 在 `createRuntime` 闭包内（`main.ts:714-839`），闭包每次新会话都跑（`core/agent-session-runtime.ts:237/241`）。**代码错、文档对** |
| `docs/settings-review.md` §7.2（资源类五行 `RestartEngine` 不确定） | 「新会话也许够，需要真机判据」 | **可以定论**：够。`SettingsManager.create` 在建会话闭包内（`main.ts:731`），资源加载/包管理读的是这个 manager（`core/agent-session-services.ts:147`、`core/package-manager.ts:914`、`core/resource-loader.ts:875`）。可改 `NewSession`（§4 C13） |
| `docs/settings-review.md` §7.5（`sessionDir` 的代价） | 「要让键生效需：去掉两个启动输入 + 让 `mostRecentForResume` 读分组目录 + 会话列表跟改」 | **基本成立**，但会话列表那一半**已经做完**：`PiSessionStore.list` 已两种布局都读（其 KDoc 与扫目录逻辑）。**只剩 `mostRecentForResume` 只读平铺**这一处阻塞。另新增一条旧账没写的事实：现在这套 pin **改变了 pi 的会话布局**（平铺 vs 分组，`core/session-manager.ts:476-489`），本身就是 1:1 偏离 |
| `docs/settings-review.md` §8「原始 `settings.json` 编辑器不做」 | 「再加一个原始编辑器会写出 pi 的写入方不会产生的文件」 | **与代码矛盾**：`<workspace>/.pi/settings.json` 已经能通过工作区文件树当普通文本整份编辑（`ProjectScreen` 的「全部文件」→ `WorkspaceViewer`，`editable = true`）。而且那条路的写是**非原子、无锁**的（`WorkspaceFiles.writeText` → `file.writeText`）。要做的是**收口或升级**，不是"不做" |
| `docs/settings-review.md` §4.3「没有发现与 pi 文档不符的默认值」 | 默认值全部一致 | 逐个复核：**默认值确实一致**，但**边界不一致**：数字行的 `max` 是 App 发明的（§4 C2），`httpIdleTimeoutMs` 少认了 `"disabled"`（C6），`theme` 的"默认值"是 App 的回退选择（C5）。旧账只对了"文档里的默认值"这一半 |
| `docs/settings-review.md` §0.4 计数（67 键 = 41 pi + 26 app） | 快照 | **过期**。按工作区当前内容重放：**70 键 = 41 pi + 29 app**，23 个 `piOwnedKeys`，`unread = []`、`dups = []`、`stale = []`、`app.* 进白名单 = []`、9 个搜索 chip 全部可解析（我用 Python 等价重放了 `PiSettingsAuditCheck` 的六条断言） |
| `docs/known-gaps.md` §I7 | 13 个死键已处置；四个只读行由 `RuntimeFacts` 供真值 | **成立**（`RuntimeFacts` 与 `valueOverrides` 都在；注册表里 `readOnly` 事实行齐）。补充：这四行 + `prorootStatus` 现在与 `proroot` 开关**同 section**，是新的 IA 问题（§5.3 I8） |
| `docs/known-gaps.md` §I10 | `app.device.*` 两份真相已删 | **成立**（注册表里已无 `app.device.*`；`SettingsHome` 指向 `DeviceCapabilityStore`）。同类问题在**别处仍在**：`enabledModels` 有**两个写入方**（凭证页 `PiEnginePreferences.selectModel` 整份替换 + 设置页的列表编辑器手改），而 `packages` 行当初就是因为这个被改成只读的（注册表 `packages` 行的注释）。建议对 `enabledModels` 用同一条规则：只留一个写入方，或明确谁覆盖谁 |
| `docs/known-gaps.md` §I11 | 三项 CLI/env 能力已成设置行 | **成立**（`app.runtime.offline/cacheRetention/noContextFiles` + `PiPreSpawnConfig` 的 `APP_EXPOSED_PRE_SPAWN`）。但审查中另发现一处**新的错误断言**：`PiPreSpawnConfig` 的 `NOT_EXPOSED_PRE_SPAWN` 里把 `PI_HYPERLINKS`/`PI_IMAGE_PROTOCOL`/`PI_TRUE_COLOR` 等归为"pi's TUI never runs here"，与 `PtyLauncher` 和 `SettingsHome` 的终端行矛盾（§0.1） |
| `PiSettingsAuditCheck`（`app/src/test/.../PiSettingsAuditCheck.kt`） | 规则 1：注册键要么在 App 源码里出现，要么在 `piOwnedKeys` 里声明 | **规则本身太弱，会假通过**：它把 `app/src/main/kotlin` 下所有 `.kt` 的**原始文本**（含注释与任意字符串）拼起来做 `contains("\"$key\"")`。所以一句 KDoc 里提到某个键名就能让"有消费者"成立（搜索 chip 那条检查剥了注释，规则 1 没剥）。另外 `thinkingBudgets` 是靠 `SettingsSearchScreen` 里的提示 chip 通过的——它并不是消费者。**建议**：规则 1 至少剥掉注释；把 `piOwnedKeys` 拆成"仅写"与"仅提示"两类，后者不算消费者 |

---

## 8. 表 A/B/C 的落地顺序建议（按"能立刻动手"排序）

1. **`enabledModels` 的 `EffectiveKind` 与文案**（§4 C1）：一处改动，去掉一条假陈述。
2. **数字行的 `max`**（§4 C2）：把 `max` 变成"滑块上限"，手输只挡 pi 真正的约束。
3. **`themes` 改名 + `powershell` 预设去掉 + `cacheRetention` 默认值**（§3）：三处文案/数据改动，零行为风险。
4. **`enabledModels` 匹配器**（§4 C3）：补子串规则，或对做不到的模式显示「无法判断」。
5. **`enabledModels` 的第二个写入方**（§7 旧账表最后一行）：定一个权威。
6. **`<workspace>/.pi/settings.json` 的编辑路收口**（§6.3 最后一段）：加确认或挡住。
7. **`<(agentDir)>` 的写路径统一取锁 + 临时文件名加随机后缀**（§6.3 约束 1/2）。
8. **`app.*` 键迁到 sidecar**（§0.2 最后一行、§6.3 约束 5）：需要一次性迁移读旧位置。
9. **「Pi 文件」屏**（§6.3）：新功能，最后做。
10. **骨架重排**（§5.4）：纯显示层，风险最低但工作量集中在注册表与 `SettingsHome`。

---

## 9. 我没能确认的项

写明缺什么证据，不要当成结论用。

1. **`enabledModels` 在 `switch_session`/`resume` 时是否也重新解析**：我核到 `newSession` 会调 `createRuntime`
   （`core/agent-session-runtime.ts:237-241`），`switchSession`/`fork`/`resume` 走的是同一族方法但我没有逐个
   读完（`:216` 附近有 `createRuntime`）。若它们也重建，`NewSession` 的分类更强；若某个路径不重建，
   那一格要单独写。判据：真机上改 `enabledModels` → 不重启 → `new_session` → 看循环列表。
2. **19 个 TUI 键在 App 终端里跑 pi TUI 的实际观感**：我只核到"可以跑"（`SettingsHome` 的终端行、
   `PtyLauncher` 的环境与 bind），没有真机验证 `termlib 0.0.13` 对 `tuiMode: fullscreen`（alt screen）、
   `clearOnShrink`、`showTerminalProgress` 的渲染是否正常。判据：终端里 `pi` → `/settings` 改这几项 → 看效果。
3. **App 的终端组件对 SGR 38/48 的支持我只读到注释**（`PtyLauncher` 的 "libvterm parses SGR 38/48 with
   24-bit values"），没有独立验证；`terminal.trueColor` 的"冗余"结论依赖这条注释。
4. **guest 载荷里是否真的没有 `nano`/`vim`/`pwsh`**：我读的是 `tools/fetch-runtime.mjs` 的清单
   （Ubuntu base + Node + ripgrep + fd + git + CA bundle）。**没有在设备上 `ls /usr/bin` 核过**。
   这条支撑 §2 A.2 的 `externalEditor` 与 §3 的 `powershell` 预设两个建议。
5. **`app.workspace.current` / `app.workspace.names` 是否还有别的读取方**：我只确认了它们的定义与
   `WorkspaceStore` 用法；没有穷举全树（`grep` 的 app-only 键清单是 `"app.*"` 字面量，动态拼出来的键看不到）。
6. **`docs/settings-review.md` §6 声称 `settings-audit` "0 失败"**：我用 Python 等价重放得到同样的
   0 失败，但**没有跑真 harness**（本机不编译、不跑 Gradle 是硬约束）。`PiSettingsAuditCheck` 的规则 1
   的假通过风险（§7 旧账表最后一行）是读代码得出的，没有构造反例去实测。
7. **`pi config` 的 scope 选择在 RPC 下是否可达**：`cli/config-selector.ts` 是 TUI 命令；我没有核
   `pi config` 是否有非交互形式（`docs/packages.md` 未逐页读）。这影响 §4 C4 里"学 pi 一律写 global"的建议
   是否完整。
8. **`ProjectScreen.kt` / `WorkspaceViewer.kt` 正在被另一个代理改**（`git diff --stat` 显示
   `ProjectScreen.kt` 有 687 行改动）。§6.2 里"工作区文件树能改 `.pi/settings.json`"这条结论是
   **在当前工作区内容**上核的；如果那一批改动把 `.pi` 挡掉了或改成只读，这一条要作废。
