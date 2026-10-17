# 进程启动期配置（pre-spawn configuration）

这份文件回答一个问题：**pi 的哪些配置只在 `pi --mode rpc` 启动那一刻被读、之后改不动，本应用把它们暴露到什么程度，判断依据是什么。**

它是 `docs/surface-inventory.md` 里「设置：那些只在启动时生效的行」这一面的深挖，也是 `docs/settings-review.md` §5（`EffectiveKind` 判据）与 §7.2/§7.5 的续篇。**权威的表在代码里**：`rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt`；这份文档是它的叙述版本，并补上代码表不适合承载的取舍与不确定性。

> ### ⚠️ 状态更新（2026-10-16）：提示词那两行**已经不是进程参数了**
>
> 设置 →「系统提示词」的两行不再发射 `--system-prompt` / `--append-system-prompt`，改成**直接读写 pi 自己的两个文件**：
> `<agentDir>/SYSTEM.md` 与 `<agentDir>/APPEND_SYSTEM.md`（新文件 `app/pi/ui/PromptFileSettingsStore.kt`，做成
> `PiSettingsStore` 装饰器；空白 = **删文件**）。两个 flag 从 `APP_EXPOSED_PRE_SPAWN` 移进
> `NOT_EXPOSED_PRE_SPAWN`，`PiLaunchOptions` 的两个字段与 `renderPromptFlag` 一并删除。
>
> **为什么必须换通道**：`resource-loader.js:475` 是 `this.systemPromptSource ?? this.discoverSystemPromptFile()`
> —— 命令上给了参数，**文件发现整段不跑**。于是用户（或任何界面）写在 `SYSTEM.md` 里的东西**永远是死的**，
> 同一件东西两个入口、其中一个静默赢。
>
> **生效时机也因此变了**：文件是**每个会话**重建资源加载器时读的
> （`agent-session-services.js:63-69` 新建 loader + `reload()`；`agent-session-runtime.js:116` 换会话重建 services），
> 所以这两行的 `EffectiveKind` 从 `RestartEngine` 改成 **`NewSession`** —— 不再需要重启引擎。
>
> **下文里凡是把这两行说成"命令行参数 / 需重启引擎 / 与文件互斥"的句子都是那次改动之前的记录**，
> 已就地标注；§1.1、§2.1、§4 是受影响的三处。

## 0. 四态标注与规则

每条配置的判定只用四种说法（`docs/pi-sourced-lists.md`）：

| 标注 | 含义 |
|---|---|
| `pi 有 <file:line>` | pi 确实有这个行为，本应用也接上了（行或硬编码），给得出读取点 |
| `pi 无对应物（App 的决定）` | pi 没有这个概念，是 App 自己造的键/行为 |
| `pi 有但我们够不着（卡在哪）` | pi 有，但本应用没有可达通道；必须写清卡点 |
| `一致` / `有意偏离` | 有对应关系；偏离要写理由 |

两条贯穿全文的判断规则：

1. **能写进 `settings.json` 并由 pi 读取的，就不是「启动期独占」。** 这种配置在设置里以 pi 的键出现（值来自 pi 的文件），不需要、也不应该在「进程」里再开一个开关。反例是 `--tools`：它在 `core/sdk.ts:258-264` 里**压过** `defaultTools`，所以一个进程开关会让设置行静默失效——这正是 §M12 的「两份真相」形状。
2. **只有 CLI / 环境变量独占的（pi 的 `Settings` 接口没有对应键），才可能需要 App 暴露**；再叠加「手机上有明确意义」这一条。够不着或没意义的，写在下面并给理由，不硬做。

「运行时能不能改」的公共依据：RPC 命令全集在 `modes/rpc/rpc-types.ts:20-74`（33 个 `RpcCommand`），里面**没有任何**改系统提示词、改工具集、改资源发现、重读设置的命令。`--mode rpc` 的进程只从 stdin 收这些命令，所以凡是 CLI/env 传进去的值，进程内都没有写回通道。能运行时改的只有：模型（`set_model`，`rpc-mode.ts:472`）、思考等级（`set_thinking_level`，`:499`）、引导/后续模式（`:521`/`:526`）、自动压缩（`:540`）、自动重试（`:549`）、会话名（`set_session_name`，`:661`）、会话切换/派生（`:605`/`:613`/`:621`）。

**一个容易搞错的前提**：RPC 的 `new_session`（`rpc-mode.ts:437`）会重建 runtime（`core/agent-session-runtime.ts:226-252` → `createRuntime`），因此 **pi 会重读 `settings.json`**、重扫资源目录。但 App 的这些进程键不是从设置文件直接读给 pi 的：`PiSessionViewModel.launchOptions()` 把值读出来写进**进程的 argv/env**，`parsed` 在整个进程里不变（`main.ts:776-777`、`:775`、`:764-778`）。所以新会话也不会让这些键生效——**对走 argv/env 的那些键，`RestartEngine` 是唯一正确的标签**。

> （2026-10-16 更正：**提示词那两行是这条规则的例外。** 它们不再走 argv，值就是 pi 按会话读的文件，所以标签是 `NewSession`。上面那句"唯一正确的标签"只对仍然从进程参数进来的键成立。）

---

## 1. 全量表

### 1.1 CLI / 环境变量独占（pi 无 settings 键）——真正的启动期独占

| pi 配置 | pi 读取点 | 通道 | pi settings 键 | RPC 运行时可改 | App | EffectiveKind | 判定 |
|---|---|---|---|---|---|---|---|
| ~~`--system-prompt <text>`~~ **（2026-10-16 起本应用不再发射）** | `cli/args.ts:110` → `main.ts:776` → `core/resource-loader.ts:475`（**给了参数就不再去发现文件**，这就是换通道的原因） | ~~CLI~~ **不再是本应用的通道** | 无（`Settings` 接口 `core/settings-manager.ts:110-163` 里没有 `systemPrompt`） | — | 两行改成直接读写 `<agentDir>/SYSTEM.md`（`app/pi/ui/PromptFileSettingsStore.kt`） | **`NewSession`**（原 `RestartEngine`） | `一致`：口径由"参数"改成 pi 自己的文件形态，见文首状态更新与 §2.1 |
| ~~`--append-system-prompt <text>`（可重复）~~ **（同上，不再发射）** | `cli/args.ts:112-114` → `main.ts:777` → `resource-loader.ts:533-534` → `core/agent-session.ts:1122-1123` → `core/system-prompt.ts:64` | ~~CLI~~ **不再是本应用的通道** | 无 | — | 直接读写 `<agentDir>/APPEND_SYSTEM.md` | **`NewSession`**（原 `RestartEngine`） | `一致`：同上 |
| `--no-context-files` / `-nc` | `cli/args.ts:194` → `main.ts:775` → `resource-loader.ts:273`/`:516` | CLI | 无 | **否**：RPC 无 reload，值钉在 `parsed` 上 | **本轮新增** `app.runtime.noContextFiles` | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--no-extensions` / `-ne` | `cli/args.ts:169` → `main.ts:771` → `resource-loader.ts:452`/`:556` | CLI | 无 | **否**：值钉在 `parsed` 上；pi 侧 `-e` 显式路径仍生效，但 App 不传 | **本轮新增** `app.runtime.noExtensions`（1:1，随包扩展也一起停） | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--no-skills` / `-ns` | `cli/args.ts:188` → `main.ts:772` → `resource-loader.ts:468`/`:674` | CLI | 无 | **否** | **本轮新增** `app.runtime.noSkills` | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--no-prompt-templates` / `-np` | `cli/args.ts:190` → `main.ts:773` → `resource-loader.ts:483`/`:697` | CLI | 无 | **否** | **本轮新增** `app.runtime.noPromptTemplates` | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--no-themes` | `cli/args.ts:192` → `main.ts:774` → `resource-loader.ts:502`/`:721` | CLI | 无 | **否** | **本轮新增** `app.runtime.noThemes` | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--offline` / `PI_OFFLINE=1` | `cli/args.ts:223`；`main.ts:569-572`；`core/package-manager.ts:54`；`core/model-runtime.ts:197` | CLI 或 env | 无 | **否**：`main()` 只读一次；env 在 spawn 时固定 | `app.runtime.offline` | `RestartEngine` ✓ | `一致` |
| `PI_CACHE_RETENTION=long` | `packages/ai/src/api/anthropic-messages.ts:64`、`openai-completions.ts:293`、`openai-responses.ts:62`、`pi-messages.ts:352`、`bedrock-converse-stream.ts:819` | env | 无 | **否**：每次请求读的是**进程环境**，进程内没有改 env 的通道 | `app.runtime.cacheRetention` | `RestartEngine` ✓ | `一致` |
| `<agentDir>/SYSTEM.md`（项目 `.pi/SYSTEM.md`，仅项目被信任时） | `resource-loader.js:944-955` 的 `discoverSystemPromptFile()`，读在 `:475`（`resolvePromptInput`） | 文件发现 | 无 | **否**：改动在**新会话**生效（loader 按会话重建） | **已接**（2026-10-16）：设置 →「自定义系统提示」写的就是这个全局文件；项目级那份在「Pi 文件」屏的「项目 .pi」根下编辑 | `NewSession` | `一致` —— ~~`pi 有但我们够不着`：卡在本应用没有写该文件的界面（专家可在 工作区 → 终端 里放）；且与替换行互斥~~ **那两条都不成立了** |
| `<agentDir>/APPEND_SYSTEM.md`（项目 `.pi/APPEND_SYSTEM.md`，仅项目被信任时） | `discoverAppendSystemPromptFile()`（同上 `:955-964`），读在 `:482-491` | 文件发现 | 无 | **否**，同上 | **已接**：设置 →「追加系统提示」写这个全局文件（项目级同上） | `NewSession` | `一致` —— ~~`pi 有但我们够不着`；被显式 `--append-system-prompt` 遮蔽~~ **不再遮蔽**（那个参数不发了） |

### 1.2 pi 有 settings 键——不算启动期独占，App 走设置行

| CLI / env | pi 的 settings 键 | pi 读取点 | 运行时能改吗 | App 的行 | 判定 |
|---|---|---|---|---|---|
| `--tools <a,b>` | `defaultTools` | `cli/args.ts:137-141`；`main.ts:538-539`；`core/sdk.ts:258-264`（CLI 顶掉设置） | 新会话可（`new_session` 重读设置） | `defaultTools`（`NewSession`） | `一致`：**不开进程开关**——CLI 会压过设置，见规则 1 |
| `--models <patterns>` | `enabledModels` | `main.ts:792`（`parsed.models ?? getEnabledModels()`） | 新会话可 | `enabledModels`（`NewSession`） | `一致` |
| `--provider` / `--model` | `defaultProvider` / `defaultModel` | `main.ts:472-516` | **可**：`set_model`（`rpc-mode.ts:472`） | 两行（`NewSession`） | `一致` |
| `--thinking <level>` | `defaultThinkingLevel`、`modelThinkingLevels` | `main.ts:515-516`；`core/sdk.ts:237-255` | **可**：`set_thinking_level`（`rpc-mode.ts:499`） | 两行（`NewSession`） | `一致` |
| `--approve` / `--no-approve` | `defaultProjectTrust` | `main.ts:729-748` | 否（启动时的 manager） | 行（`RestartEngine`） | `一致` |
| `--session-dir` / `PI_CODING_AGENT_SESSION_DIR` | `sessionDir` | `cli/args.ts:129-130`；`main.ts:676-679` | 否 | **有意无行**：App 两条更高优先级输入都传了，写了也读不到（`settings-review.md` §7.5） | `有意偏离` |
| `--extension <path>` | `extensions` | `cli/args.ts:166-168`；`main.ts:713` | 新会话可（`settings-review.md` §7.2） | 行（`RestartEngine`） | `一致`（更省的标签待设备判据） |
| `--skill <path>` | `skills` | `cli/args.ts:171-173`；`main.ts:714` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--prompt-template <path>` | `prompts` | `cli/args.ts:174-176`；`main.ts:715` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--theme <path>` | `themes` | `cli/args.ts:177-179`；`main.ts:716` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--use-theme <name>` | `theme` | `cli/args.ts:180-187`；`main.ts:666-667` | App 侧热读 | 行（`Reload`） | `有意偏离`：App 自绘主题、重进界面重读；pi 侧只在它自己的 TUI 启动读 |
| `VISUAL` / `EDITOR` | `externalEditor` | `core/settings-manager.ts:986-988` | — | **无行** | `一致`：手机上没有外部编辑器 |
| `HTTP_PROXY` / `HTTPS_PROXY` | `httpProxy` | `main.ts:587` / `:854` | 否 | 行（`RestartEngine`） | `一致` |
| `PI_TELEMETRY` | `enableInstallTelemetry` | `core/telemetry.ts:8-10`（env **覆盖**设置） | 新会话可 | 行 `enableInstallTelemetry`（`NewSession`） | `一致`：只暴露设置键，不暴露 env（否则两份真相） |

### 1.3 App 硬编码的进程输入（用户不可调，`App 的决定`）

| 输入 | 位置 | 为什么固定 |
|---|---|---|
| `--mode rpc` | `PiEngineHost.kt:591` | 本应用与引擎的唯一协议；没有交互式 TUI 可给 |
| `--session-dir <agentDir>/sessions` | `PiEngineHost.kt:592` | 会话列表读取方（`PiSessionStore`）必须与 pi 看同一目录 |
| `PI_CODING_AGENT_DIR` | `PiEngineHost.kt:621` | agent 目录 bind 进 guest，App 的文件读取方与 pi 必须看同一份 |
| `PI_CODING_AGENT_SESSION_DIR` | `PiEngineHost.kt:622` | 同上 |
| `PI_SKIP_VERSION_CHECK=1` | `PiEngineHost.kt:625` | 更新检查是 App 的事，手机应用不该在背后联网 |
| `PI_ANDROID_BRIDGE_FILE` | `PiEngineHost.kt:631` | App 自带设备桥扩展的约定 |

`PI_PACKAGE_DIR` 未设置：引擎路径由载荷决定（`RuntimeProvisioner` 按 revision 解包）。

### 1.4 CLI / env 独占，但 App 决定不做（每条给理由）

| pi 配置 | pi 读取点 | 理由 |
|---|---|---|
| `--exclude-tools` / `-xt` | `cli/args.ts:142-146`；`main.ts:541-542`；`core/sdk.ts:260-264` | CLI 独占，但工具全集 = 内建 + 扩展运行时注册，设置页列不出准确名单；`defaultTools` 已覆盖「关内建工具」，再来一个否认列表就是同一件事的第二份真相 |
| `--no-session` | `cli/args.ts:121-122` | App 的会话列表/续接/导出都建立在会话文件上，没有临时会话界面；属功能新增 |
| `--name` / `-n` | `cli/args.ts:115-120` | RPC 有 `set_session_name`（`rpc-types.ts:68`，`rpc-mode.ts:661`）且 App 已在用（`PiSessionViewModel.setSessionName`），启动参数会与运行时可改的名字重复 |
| `--verbose` | `cli/args.ts:217-218`；`main.ts:946` | 只到 pi 交互式 TUI 的启动输出，RPC 无消费者 |
| `--tui-mode` | `cli/args.ts:203-216`；`main.ts:947` | pi 自己的 TUI 布局；这里的引擎没有 TUI（`tuiMode` 设置键同理） |
| `--api-key` | `cli/args.ts:108-109`；`main.ts:810-818` | 一次性明文参数；App 的凭证面把 key 写进 `auth.json`/`models.json`，也不会让 key 出现在进程参数里 |
| `PI_SHARE_VIEWER_URL` | `config.ts:515-519` | `/share` 是交互式 TUI 内置命令，不在 RPC 面 |
| `PI_HARDWARE_CURSOR` / `PI_HYPERLINKS` / `PI_IMAGE_PROTOCOL` / `PI_TRUE_COLOR` / `PI_TUI_ESC_TIMEOUT` | `docs/environment-variables.md:90-94` | 只被 pi 自己的终端渲染器读；本应用的终端是自绘的 Compose 面板，pi 的 TUI 不运行 |
| `PI_STARTUP_BENCHMARK` | `main.ts:918-921` | 开发期基准开关，且在非交互模式下会直接报错退出 |
| `PI_RADIUS_GATEWAY` / `PI_SERVER_DIR` / `PI_SERVER_ID` | `experimental/radius-auth.ts:9`；`experimental/server.ts:51-52` | 仅源码版的实验特性，不在发行版里 |

### 1.5 pi 的 settings 键里「只在进程启动时读」的行（`RestartEngine` 复核）

| 键 | pi 读取点 | App 标签 | 复核 |
|---|---|---|---|
| `httpProxy` | `main.ts:587` / `:854` | `RestartEngine` | ✓ 进程级 dispatcher，一次 |
| `httpIdleTimeoutMs` | `core/settings-manager.ts:941` → `main.ts:855` | `RestartEngine` | ✓ 进程启动配置 dispatcher |
| `defaultProjectTrust` | `main.ts:749`（启动时那个 manager） | `RestartEngine` | ✓ 读它的是进程启动时建的 manager，新会话不重读 |
| `extensions` / `packages` / `skills` / `prompts` / `themes` | `main.ts:764-778`（每次 `createRuntime`） | `RestartEngine` | `一致`，但**可能有更省的选择**：源码读下来新会话也够（`agent-session-runtime.ts:226-252` → 新 loader + `reload()`）。维持现状的理由与判据写在 `settings-review.md` §7.2，需真机确认 |
| `sessionDir` | `core/settings-manager.ts:729` → `main.ts:676-679` | 无行 | `有意偏离`（§7.5） |
| `terminal.*`（capability overrides 等） | `main.ts:853` `setCapabilityOverrides` | 无行 | `一致`：只服务 pi 的 TUI，手机无意义 |

---

## 2. 用户的两个问题

### 2.1 「我现在能修改系统提示词吗？」

**能。** ~~有四个入口，其中两个已经在设置里（本轮把第二个补齐）~~ → **2026-10-16 起是"两个文件、两个层级"，不再是两个命令行参数**：

| # | 入口 | 实际改动的东西 | 生效 |
|---|---|---|---|
| 1 | 设置 →「**自定义系统提示**」 | **全局** `<agentDir>/SYSTEM.md` —— pi 用它**替换**自带的系统提示词。留空 = **删掉文件** | **开一个新会话** |
| 2 | 同处 →「**追加系统提示**」 | **全局** `<agentDir>/APPEND_SYSTEM.md` —— **追加**在组装好的提示词里，不替换。两者是两个独立的段（`preamble` / `addendum`），可以同时设 | 同上 |
| 3 | 「Pi 文件」屏 →「**agent 目录**」根 | 就是 1、2 的那两个文件（同一份东西的另一个入口） | 同上 |
| 4 | 「Pi 文件」屏 →「**项目 .pi**」根 | `<工作区>/.pi/SYSTEM.md` / `APPEND_SYSTEM.md` —— **项目级**：只对这个工作区、且**项目被信任**时才算；**同名不合并、项目优先**（pi 官方文档 `docs/configuration.md:39`） | 同上 |

> ~~原先那张四行表把同一件东西说成了三种：设置页传命令行参数、文件"被参数遮蔽"、所以想用文件就必须先清空那一行。~~
> **那个互斥关系已经不存在了** —— 参数不发了，设置页写的就是文件本身；"项目级"与"全局级"是 pi 自己的两层，不是 App 的两条通道。

几个必须知道的前提与后果：

- ~~**只在启动时读。** 两行的值由 App 读出后写进新进程的 argv；正在跑的引擎改不动（RPC 没有对应命令，`rpc-types.ts:20-74`）。所以文案统一写「改动在重启引擎后生效」，标签是「需重启引擎」。~~ **2026-10-16 起正好相反**：两行改的是 pi **按会话**读的文件，进程内不用重启，界面标签是「**新会话**」。走 argv/env 的其余启动期键不受影响，仍是「需重启引擎」。
- **这两行住在「提示词」分组（本轮从 运行时与诊断 → 进程 搬过来）。** ~~只搬显示位置：键一个字没改（`app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt`），已经写在 `settings.json` 里的值照旧读得出来。~~ → **2026-10-17：行身份键改成 `app.prompts.system` / `app.prompts.append`，框上沿显示的是真正被写的文件路径**（`PiSetting.keyLabel`）。`app.runtime.*` 那两个名字连"行身份"都不再保留 —— 它们既不是 pi 的键，也不在 `app.runtime` 这个命名空间里了；`settings.json` 里的旧值仍然被彻底忽略（见下一行）。搬家的理由是「用户在哪里找它」——这两行是「模型被交代了什么」，不是「引擎怎么跑」，pi 自己的 CLI 里 `--model` 与 `--system-prompt` 也是挨着的（`cli/args.ts:108-112`），所以设置首页把「提示词」排在「模型与推理」之后。`pre-spawn` 检查的契约随之从「一个分组」改成「两个登记在册的 pre-spawn 分组」（见 §3）。
- **两行的编辑器是多行框。** 整份系统提示词是几千字的 Markdown，`PiSetting.multiline = true` 让 `PiTextEditorSheet` 给多行、框内可滚动的输入框（`PiSettingsEditors.kt` 的 `PiTextEditorMaxLines`）。以前「多行」是从 `depth > 1` 推出来的，`app.runtime.systemPrompt` 漏了 `depth`，于是落进单行框、只显示第一行 —— 这正是「几千字只进去几十字」那个缺陷；`depth` 现在不再决定字段画几行。
- ~~**重启不会丢值。** 值存在 pi 的 `settings.json`（全局文档，键名以 `app.` 开头，pi 不校验、直接忽略）~~ → **值不再住 `settings.json`**：它就是 `<agentDir>/` 下那两个文件本身。agent 目录在 rootfs 内、由 `DurablePreserve` 保护，换载荷、重解包 rootfs 都不动它（`docs/pi-contract.md` 版本升级一节）。旧键 `app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt` 里可能还留着 2026-10-16 之前写的值 —— **被彻底忽略、不迁移、不回退**（用户裁定不做兼容）。
- **替换与追加是两回事，不互相顶掉。** 同时设置时 pi 分别消费两个文件：`SYSTEM.md` 落到 `preamble` 段，`APPEND_SYSTEM.md` 落到 `addendum` 段（`core/system-prompt.js:78-98`）。
- ~~**第 3、4 条是本应用没有界面的专家路径。** 用户可以在 工作区 → 终端 里放文件；本应用不写这两个文件，避免和上面两行互相遮蔽出第三种状态。~~ → **正好相反**：本应用现在**就是**写这两个文件的那一方（`app/pi/ui/PromptFileSettingsStore.kt`）；"专家路径"只剩"直接用编辑器改文件"这一种，而它与设置页改的是同一份东西 —— 不再有"第三种状态"。
- ~~**一个 pi 的边角行为**：`--system-prompt` / `--append-system-prompt` 的值会先经过 `resource-loader.ts:54` 的 `resolvePromptInput`——如果这串文字**恰好是一个存在的路径**，pi 会把它当文件读成内容。多行的追加内容不会撞上；单行且看起来像路径的替换内容可能撞上。~~ **对本应用不再适用**（那两个参数一个都不发了，值是文件）。它仍是 pi 自己 CLI 的行为，留在原地只为了避免以后有人照它去推 App 的行为。

### 2.2 「官方都支持调整哪些东西？我这里面都全了吗？」

- **官方支持调整的全部启动期东西** = §1.1（CLI/env 独占）+ §1.2（同时有 settings 键的）+ §1.3（App 固定的）+ §1.4（不做）。
- **全了吗？** 改动前**不全**：`--append-system-prompt` 的模型字段存在但设置页没有行（§I11 形状：有值没人写），`--no-context-files` 完全没有，四个发现抑制（`--no-extensions` / `--no-skills` / `--no-prompt-templates` / `--no-themes`）是「设计过、没实现」。这几条现在都补齐了。改动后，**手机上说得通、且 pi 没有 settings 键的启动期开关，本应用都暴露了**；剩下的是三类，各有理由：pi 已有设置键（§1.2）、App 必须固定（§1.3）、手机上说不出意义（§1.4）。
- **四个 `--no-*` 与 pi 1:1，一个特例都没有。** pi 的语义是「只停*发现*」：显式传进来的 `-e` / `--skill` / `--prompt-template` / `--theme` 仍然生效（`core/resource-loader.ts:452` / `:468` / `:483` / `:502`；帮助文本 `cli/args.ts:303`）。**本应用一个显式路径都不传**，所以打开「停用扩展发现」时，随包扩展（`app/src/main/assets/pi-extensions/`，由 `DeviceBridgeController.installExtensionAssets` 拷进 `<agentDir>/extensions/`）和用户自己的扩展、资源包里的扩展**一起停**。这正是「我自带的那个也支持开关」：随包扩展是 pi 正常发现的普通扩展，开关就该把它关掉。
- **「只关别人的扩展、留下自带的」不在这次范围里。** 那是一个不同的 app 侧功能（例如在 App 侧给出「哪些扩展启用」的白名单），**不许**用 `-e` 去对抗 `--no-extensions` 来实现；这样旗标的语义才没有 App 私货。
- **四个抑制的已知代价**（写进了行文案，不藏着）：技能/模板/主题三个资源页是**按目录列文件**的，引擎不再加载它们之后，页面上仍会列出这些文件。这是「把引擎的启动面交给用户」与「资源页只显示引擎真会用的东西」之间的一次有意识取舍。
- **手机上不做的，各自理由一句话**：
  - TUI 专属（`--verbose`、`--tui-mode`、`PI_HARDWARE_CURSOR` 一族、`PI_SHARE_VIEWER_URL`）：pi 的 TUI 在这里不运行。
  - 工具黑名单（`--exclude-tools`）：工具全集由内建 + 扩展在运行时注册决定，设置页列不出准确名单；`defaultTools` 已覆盖「关内建工具」。
  - 重复通道（`--tools`/`--models`/`--provider`/`--model`/`--thinking`/`--approve`/`PI_TELEMETRY`/`HTTP_PROXY`）：pi 有设置键，或 RPC 能运行时改。
  - 功能缺失而非补缺（`--no-session`）：App 的会话面建立在文件上。
  - 安全/约定（`--api-key`）：App 用凭证文件，避免 key 进进程参数。

---

## 3. 本轮改了什么

**第一轮（提示词与上下文面）**

1. **`app.runtime.appendSystemPrompt`**（新行，`RestartEngine`，多行编辑器 `PiSetting.multiline = true`）：接进 `PiLaunchOptions.fromSettingValues`；文案写清「追加，不是替换」与「重启引擎后生效」。
2. **`app.runtime.noContextFiles`**（新行，`RestartEngine`）：接 `--no-context-files`，手机上有明确意义（关掉工作区上下文文件对系统提示的注入）且 pi 无 settings 键。
3. **纯函数**：`PiLaunchOptions.fromSettingValues(...)`——把「空串=未设置、只有 `long` 才算长保留、布尔缺省=false」这三条归一化从 ViewModel 挪进 `:rpc`；`PiSessionViewModel.launchOptions()` 只留文件 IO。
4. **`rpc/.../PiPreSpawnConfig.kt`**（新文件）：启动期表 + 三条规则（暴露项不得有 pi 设置键；覆盖项不得与暴露项重叠；未暴露项必须写理由）+ `piPreSpawnKnob()` 判定。
5. **`pre-spawn` bare-JVM harness**（`app/src/test/.../PiPreSpawnCheck.kt` + `tools/run-app-pure-checks.sh` 注册）。
6. **`tools/pi-contract.mjs`**：新增一条 surface 断言——表中每个 CLI 参数名/环境变量名必须仍出现在钉住的引擎产物里。失败信息写明「pi 把未知 `--flag` 当扩展参数而不报错，所以开关会静默失效 → 重读 `cli/args.ts` 的 `parseArgs` 并更新 `PiPreSpawnConfig.kt` + `PiLaunchOptions.kt`」。
7. **修过期注释/文案**：注册表「进程」一节原注释说 `effective` 用 `Reload`（与代码实际的 `RestartEngine` 矛盾），已改正。

**第二轮（四个发现抑制，1:1）**

8. **`app.runtime.noExtensions` / `noSkills` / `noPromptTemplates` / `noThemes`**（四条新行，Switch，`RestartEngine`，注册表 设置 → 运行时与诊断 → 进程）：分别接 `--no-extensions`（`cli/args.ts:169`）、`--no-skills`（`:188`）、`--no-prompt-templates`（`:190`）、`--no-themes`（`:192`）；每个键在 `PiSessionViewModel.launchOptions()` 里读，经 `fromSettingValues` 进 argv。
9. **`PiLaunchOptions`**：新增四个布尔字段与 `commandLineSuffix()` 的四段追加；**没有** `-e`、没有额外路径、没有随包扩展配对。`fromSettingValues` 只多四个可空布尔参数。
10. **`PiPreSpawnConfig.kt`**：四条从 `NOT_EXPOSED_PRE_SPAWN` 移到 `APP_EXPOSED_PRE_SPAWN`；全表 `piReader` 的 `file:line` 按**当时**钉住的 **0.86.1** 重新核过一遍（此后 `PI_VERSION` 又动过三轮：0.87.1 → 0.99.2 → 1.0.0，行号只保证写作时成立；参数名本身由 `tools/pi-contract.mjs` 的 surface 断言每次核对）。
11. **`pre-spawn` harness**：新增「四个抑制都进命令行」「只有这四个开关时 argv 恰好等于这四个旗标」「没有 `-e` 之类附带的显式来源旗标」「默认（全不设）时 argv 为空」四类断言，以及注册表/ViewModel 的接线检查。

**第三轮（提示词换通道：从命令行参数改成 pi 自己的文件，2026-10-16）**

12. **两行不再发射参数**：`PiLaunchOptions` 删掉 `systemPrompt` / `appendSystemPrompt` 两个字段、`commandLineSuffix()` 里的两行发射、以及 `renderPromptFlag`（`quote` 仍被 `quoteIfNeeded` 使用，保留）；`fromSettingValues` 去掉对应形参。
13. **`PiPreSpawnConfig`**：两个旋钮从 `APP_EXPOSED_PRE_SPAWN` 移进 `NOT_EXPOSED_PRE_SPAWN`，理由写在行内（"给了参数就不再去发现文件，那正是用户写的 `SYSTEM.md` 变死的原因"），并写明"不许再加回来"。
14. **新 `app/pi/ui/PromptFileSettingsStore.kt`**：一个 `PiSettingsStore` 装饰器，把 `app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt` 两个键转发到 `<agentDir>/SYSTEM.md` / `<agentDir>/APPEND_SYSTEM.md`。**空白 = 删文件**（pi 只看 `existsSync`；而"只有空白"的文件会让 `customPrompt` 为真、把 pi 自带的 preamble 替换成那点空白）；写是同目录临时文件 + `Files.move`（与「Pi 文件」屏同写一个文件时不撕裂）；读给编辑器的文本剥 BOM；缓存与 `PiSettingsFileStore.Document` 同一套 `(size,mtime)` 节流。套在 `PiSessionViewModel` 装饰器链的最外层。
15. **`EffectiveKind`：`RestartEngine` → `NewSession`**（文件按会话重建资源加载器时读）；两行描述按官方语义写短（**替换** / **追加**）。
16. **新增一行只读读数**（`app.prompts.effectiveSource`）。同一轮里它迭代过两次，落点是：
    - **标题「提示词文件」**，覆盖**四份**文件（`SYSTEM.md` / `APPEND_SYSTEM.md` × 工作区 / 全局）。
    - **值**沿用那四条「已发现的 …」资源读数的形状与用词（`PiResourceFacts`）：
      `2 份 · 项目（<工作区>/.pi） 1 · 全局（~/.pi/agent） 1`，零状态 `还没有任何提示词文件`；
      来源词取自 `DiscoverySource`（**构造级**保证同一个词，不是靠肉眼对），为此
      `PiResourceFacts.kt` 进了 `settings-store` harness 的编译闭包。
    - **行下面还有一块证据**（`detailOverrides`，那个通道本来只给 proroot 探针用）：
      存在的文件**按 pi 的读取顺序逐行列出**（`<工作区>/.pi/SYSTEM.md`、`~/.pi/agent/SYSTEM.md`、
      `…/APPEND_SYSTEM.md`…）—— 这就是 pi 在它 TUI 的 `Context` 小节里印的东西
      （`modes/interactive/interactive-mode.js:1382-1392`）。只读行的**值**是 `maxLines = 1`，
      pi 那种多行列表只能走这块证据；两者出自**同一次**读数（`PromptFileFacts.read`），
      行与它的证据不会各说一套。
    - **不报字数**（pi 自己的读数也不报），因此这一行只 stat 文件、**不读文件内容**。
    - ~~早一版只覆盖 `SYSTEM.md`、且报每份文件多少字、把路径塞进行描述~~ —— 都改掉了。
    - **与 pi 还剩一处有意差距**：列表为空时 pi **整节不出现**，这里只是**证据块不画**
      （`detail()` 返回 null），行本身还在并说 `还没有任何提示词文件` —— 注册表没有条件隐藏机制，
      硬做就是新造机制。另外 pi 列的是**胜出**的那一份（它知道项目信任与否），这里列的是
      **两个候选都列** —— 因为这一行存在的理由正是 App 不知道那个裁决。
17. **harness 的断言方向反过来**：`PiLaunchOptionsTest` 改成"反射断言没有那两个字段 + 全开时一个 flag 都不发"；`PiPreSpawnCheck` / `ExtensionFlagArgsCheck` 跟着改（后者新增"两个 prompt flag 一个都不发"，因为把它们留在"忽略名单"里会藏住"不该再出现"的 flag）；`PiSettingsStoreCheck` 新增一整节钉新语义（缺文件 = null、写入真的落到 `<agentDir>/SYSTEM.md`、**空白 = 删文件**、非字符串 = 清空、BOM 剥离、无临时文件残留、**不回退也不迁移旧值**；以及读数行的四份计数与顺序）。

## 4. 不确定项与设备判据

1. **提示词两行的端到端效果**：判据——把「追加系统提示」写成一句可观察的偏好（例如「每次回答都以『好』结尾」）→ **开一个新会话**（2026-10-16 起不再需要重启引擎）→ 发一条消息，看行为是否改变；再把「不加载上下文文件」打开 → 重启 → 在工作区放一个会显著改变模型行为的上下文文件，看它是否被忽略。（后者仍走 argv，仍要重启。）
2. ~~**`--append-system-prompt` 是否遮蔽 `APPEND_SYSTEM.md`**：静态依据是 `resource-loader.ts:533-534`；RPC 面上系统提示词不可观测，所以这条**没有 wire 通道可钉**，只能靠重读源码。若将来 pi 改成合并，App 的「追加行」仍正确，只是文件不再被顶掉。~~ → **已作废（2026-10-16）**：那个参数不发了，**没有任何东西遮蔽这两个文件**。这一条曾经无法用 wire 钉住，现在的答案不需要 wire —— 改通道把那半问题整个删掉了。
3. **资源类五行（`extensions`/`packages`/`skills`/`prompts`/`themes`）的 `RestartEngine`**：维持现状；判据在 `settings-review.md` §7.2（装一个技能 → 开新会话 → 看命令面板是否出现）。
4. **`--no-context-files` 的实际收益**：只有在工作区真的存在上下文文件时才有可观察差别。若设备上确认手机用户几乎不会有，这一行可以考虑撤掉——但撤之前要先确认「pi 不会从 guest 的祖先目录读到意外的文件」（`resource-loader.ts:71` 的 `loadContextFileFromDir` 走的是 cwd 与 agent 侧约定，需在设备上核一遍实际读到的文件集）。
5. **四个抑制的端到端效果**：判据——打开「停用扩展发现」→ 重启引擎 → `android_*` 工具应当**一起消失**（随包扩展是普通被发现的扩展），模型侧不再有这些工具；打开其余三个 → 重启 → 命令面板里对应的技能/模板消失、pi 侧主题不再加载（App 自己的配色不变）。**未在本机跑过，只按代码推演。**
6. **「打开停用扩展发现后设备能力工具消失」是否会让某条 App 侧功能报错**：App 侧读扩展目录的界面（资源包/扩展页）仍会列出文件，与引擎实际加载的不一致；这是有意的（见 §2.2 的代价一段），但设备上要确认没有哪条功能把「扩展存在」当成「工具可用」。判据：打开开关 → 重启 → 进入扩展页（应仍能列出）→ 让模型调用一个 `android_*` 工具（应报工具不存在，而不是崩溃）。

## 5. 自检（本仓库规矩）

本轮按约束**不在本机跑任何编译/构建**（这台机器就是用户的手机），验证全部留给 CI：

- `bash tools/typecheck.sh` → 期望 `:rpc 0 / :app 0`（本轮到 `:rpc` 的 `PiLaunchOptions.kt` / `PiPreSpawnConfig.kt`、`app` 的 `PiSettingsRegistry.kt` / `PiSessionViewModel.kt`、`app/src/test` 的 `pre-spawn` harness）；
- `bash tools/run-app-pure-checks.sh` → 期望 `pre-spawn` / `extension-flags` / `settings-audit` 全过；
- `python3 tools/check-nested-comments.py` → 期望 0 处；
- `node tools/pi-contract.mjs --pi <已安装的钉住版本>`（CI 的 `contract` job）→ 期望新增的参数名断言通过。
