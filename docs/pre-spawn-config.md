# 进程启动期配置（pre-spawn configuration）

这份文件回答一个问题：**pi 的哪些配置只在 `pi --mode rpc` 启动那一刻被读、之后改不动，本应用把它们暴露到什么程度，判断依据是什么。**

它是 `docs/surface-inventory.md` 里「设置：那些只在启动时生效的行」这一面的深挖，也是 `docs/settings-review.md` §5（`EffectiveKind` 判据）与 §7.2/§7.5 的续篇。**权威的表在代码里**：`rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt`；这份文档是它的叙述版本，并补上代码表不适合承载的取舍与不确定性。

## 0. 四态标注与规则

每条配置的判定只用四种说法（`docs/pi-sourced-lists.md`）：

| 标注 | 含义 |
|---|---|
| `pi 有 <file:line>` | pi 确实有这个行为，本应用也接上了（行或硬编码），给得出读取点 |
| `pi 无对应物（App 的决定）` | pi 没有这个概念，是 App 自己造的键/行为 |
| `pi 有但我们够不着（卡在哪）` | pi 有，但本应用没有可达通道；必须写清卡点 |
| `一致` / `有意偏离` | 有对应关系；偏离要写理由 |

两条贯穿全文的判断规则：

1. **能写进 `settings.json` 并由 pi 读取的，就不是「启动期独占」。** 这种配置在设置里以 pi 的键出现（值来自 pi 的文件），不需要、也不应该在「进程」里再开一个开关。反例是 `--tools`：它在 `core/sdk.ts:258-262` 里**压过** `defaultTools`，所以一个进程开关会让设置行静默失效——这正是 §M12 的「两份真相」形状。
2. **只有 CLI / 环境变量独占的（pi 的 `Settings` 接口没有对应键），才可能需要 App 暴露**；再叠加「手机上有明确意义」这一条。够不着或没意义的，写在下面并给理由，不硬做。

「运行时能不能改」的公共依据：RPC 命令全集在 `modes/rpc/rpc-types.ts:20-74`（33 个 `RpcCommand`），里面**没有任何**改系统提示词、改工具集、改资源发现、重读设置的命令。`--mode rpc` 的进程只从 stdin 收这些命令，所以凡是 CLI/env 传进去的值，进程内都没有写回通道。能运行时改的只有：模型（`set_model`，`rpc-mode.ts:472`）、思考等级（`set_thinking_level`，`:499`）、穿插/后续模式（`:521`/`:526`）、自动压缩（`:540`）、自动重试（`:549`）、会话名（`set_session_name`，`:661`）、会话切换/派生（`:605`/`:613`/`:621`）。

**一个容易搞错的前提**：RPC 的 `new_session`（`rpc-mode.ts:437`）会重建 runtime（`core/agent-session-runtime.ts:226-252` → `createRuntime`），因此 **pi 会重读 `settings.json`**、重扫资源目录。但 App 的五个进程键不是从设置文件直接读给 pi 的：`PiSessionViewModel.launchOptions()` 把值读出来写进**进程的 argv/env**，`parsed` 在整个进程里不变（`main.ts:772-773`、`:771`、`:764-767`）。所以新会话也不会让这五个键生效——`RestartEngine` 是唯一正确的标签。

---

## 1. 全量表

### 1.1 CLI / 环境变量独占（pi 无 settings 键）——真正的启动期独占

| pi 配置 | pi 读取点 | 通道 | pi settings 键 | RPC 运行时可改 | App | EffectiveKind | 判定 |
|---|---|---|---|---|---|---|---|
| `--system-prompt <text>` | `cli/args.ts:110` → `main.ts:772` → `core/resource-loader.ts:526` | CLI | 无（`Settings` 接口 `core/settings-manager.ts:106-158` 里没有 `systemPrompt`） | **否**：`rpc-types.ts:20-74` 无此命令；值钉在 `parsed` 上 | `app.runtime.systemPrompt`（整份替换） | `RestartEngine` ✓ | `一致` |
| `--append-system-prompt <text>`（可重复） | `cli/args.ts:112-114` → `main.ts:773` → `resource-loader.ts:532-534` → `core/agent-session.ts:1079-1080` → `core/system-prompt.ts:41` | CLI | 无 | **否**，同上 | **本轮新增** `app.runtime.appendSystemPrompt`（追加） | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--no-context-files` / `-nc` | `cli/args.ts:194` → `main.ts:771` → `resource-loader.ts:516` | CLI | 无 | **否**：RPC 无 reload，值钉在 `parsed` 上 | **本轮新增** `app.runtime.noContextFiles` | `RestartEngine` ✓ | `一致`（本轮补齐） |
| `--offline` / `PI_OFFLINE=1` | `cli/args.ts:223`；`main.ts:565-568`；`core/package-manager.ts:54`；`core/model-runtime.ts:196` | CLI 或 env | 无 | **否**：`main()` 只读一次；env 在 spawn 时固定 | `app.runtime.offline` | `RestartEngine` ✓ | `一致` |
| `PI_CACHE_RETENTION=long` | `packages/ai/src/api/anthropic-messages.ts:57`、`openai-completions.ts:305`、`openai-responses.ts:62`、`pi-messages.ts:350`、`bedrock-converse-stream.ts:798` | env | 无 | **否**：每次请求读的是**进程环境**，进程内没有改 env 的通道 | `app.runtime.cacheRetention` | `RestartEngine` ✓ | `一致` |
| `<agentDir>/SYSTEM.md`（项目 `.pi/SYSTEM.md`，仅项目被信任时） | `resource-loader.ts:1022-1035`，用于 `:526`；**被显式 `--system-prompt` 遮蔽** | 文件发现 | 无 | **否**：RPC 无 reload | 无行；App 的替换行有值时它就不被读 | — | `pi 有但我们够不着`：卡在本应用没有写该文件的界面（专家可在 工作区 → 终端 里放）；且与替换行互斥，见 §2.1 |
| `<agentDir>/APPEND_SYSTEM.md`（项目 `.pi/APPEND_SYSTEM.md`，仅项目被信任时） | `resource-loader.ts:1037-1047`，用于 `:532-534`；**被显式 `--append-system-prompt` 遮蔽** | 文件发现 | 无 | **否** | 无行；本轮新增的追加行有值时它就不被读 | — | `pi 有但我们够不着`：同上一行 |

### 1.2 pi 有 settings 键——不算启动期独占，App 走设置行

| CLI / env | pi 的 settings 键 | pi 读取点 | 运行时能改吗 | App 的行 | 判定 |
|---|---|---|---|---|---|
| `--tools <a,b>` | `defaultTools` | `cli/args.ts:137-141`；`main.ts:535`；`core/sdk.ts:258-262`（CLI 顶掉设置） | 新会话可（`new_session` 重读设置） | `defaultTools`（`NewSession`） | `一致`：**不开进程开关**——CLI 会压过设置，见规则 1 |
| `--models <patterns>` | `enabledModels` | `main.ts:788`（`parsed.models ?? getEnabledModels()`） | 新会话可 | `enabledModels`（`NewSession`） | `一致` |
| `--provider` / `--model` | `defaultProvider` / `defaultModel` | `main.ts:477-512` | **可**：`set_model`（`rpc-mode.ts:472`） | 两行（`NewSession`） | `一致` |
| `--thinking <level>` | `defaultThinkingLevel`、`modelThinkingLevels` | `main.ts:512`；`core/sdk.ts:240-251` | **可**：`set_thinking_level`（`rpc-mode.ts:499`） | 两行（`NewSession`） | `一致` |
| `--approve` / `--no-approve` | `defaultProjectTrust` | `main.ts:725-744` | 否（启动时的 manager） | 行（`RestartEngine`） | `一致` |
| `--session-dir` / `PI_CODING_AGENT_SESSION_DIR` | `sessionDir` | `cli/args.ts:129-130`；`main.ts:670-676` | 否 | **有意无行**：App 两条更高优先级输入都传了，写了也读不到（`settings-review.md` §7.5） | `有意偏离` |
| `--extension <path>` | `extensions` | `cli/args.ts:166-168`；`main.ts:709` | 新会话可（`settings-review.md` §7.2） | 行（`RestartEngine`） | `一致`（更省的标签待设备判据） |
| `--skill <path>` | `skills` | `cli/args.ts:171-173`；`main.ts:710` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--prompt-template <path>` | `prompts` | `cli/args.ts:174-176`；`main.ts:711` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--theme <path>` | `themes` | `cli/args.ts:177-179`；`main.ts:712` | 同上 | 行（`RestartEngine`） | `一致`（同上） |
| `--use-theme <name>` | `theme` | `cli/args.ts:180-187`；`main.ts:662-663` | App 侧热读 | 行（`Reload`） | `有意偏离`：App 自绘主题、重进界面重读；pi 侧只在它自己的 TUI 启动读 |
| `VISUAL` / `EDITOR` | `externalEditor` | `core/settings-manager.ts:969-971` | — | **无行** | `一致`：手机上没有外部编辑器 |
| `HTTP_PROXY` / `HTTPS_PROXY` | `httpProxy` | `main.ts:583` / `:850` | 否 | 行（`RestartEngine`） | `一致` |
| `PI_TELEMETRY` | `enableInstallTelemetry` | `core/telemetry.ts:10-14`（env **覆盖**设置） | 新会话可 | 行 `enableInstallTelemetry`（`NewSession`） | `一致`：只暴露设置键，不暴露 env（否则两份真相） |

### 1.3 App 硬编码的进程输入（用户不可调，`App 的决定`）

| 输入 | 位置 | 为什么固定 |
|---|---|---|
| `--mode rpc` | `PiEngineHost.kt:274` | 本应用与引擎的唯一协议；没有交互式 TUI 可给 |
| `--session-dir <agentDir>/sessions` | `PiEngineHost.kt:275` | 会话列表读取方（`PiSessionStore`）必须与 pi 看同一目录 |
| `PI_CODING_AGENT_DIR` | `PiEngineHost.kt:306` | agent 目录 bind 进 guest，App 的文件读取方与 pi 必须看同一份 |
| `PI_CODING_AGENT_SESSION_DIR` | `PiEngineHost.kt:307` | 同上 |
| `PI_SKIP_VERSION_CHECK=1` | `PiEngineHost.kt:310` | 更新检查是 App 的事，手机应用不该在背后联网 |
| `PI_ANDROID_BRIDGE_FILE` | `PiEngineHost.kt:316` | App 自带设备桥扩展的约定 |

`PI_PACKAGE_DIR` 未设置：引擎路径由载荷决定（`RuntimeProvisioner` 按 revision 解包）。

### 1.4 CLI / env 独占，但 App 决定不做（每条给理由）

| pi 配置 | pi 读取点 | 理由 |
|---|---|---|
| `--no-extensions` / `-ne` | `cli/args.ts:169-170`；`main.ts:767` | 关掉发现会让 pi 不再扫描 `<agentDir>/extensions/`，而**本应用的设备能力扩展正装在那里**——打开等于让 `android_*` 工具全部消失 |
| `--no-skills` | `cli/args.ts:188-189`；`main.ts:768` | App 的技能页读同一批目录；pi 不认会让那一页列出引擎用不了的技能 |
| `--no-prompt-templates` | `cli/args.ts:190-191`；`main.ts:769` | 同上（提示模板页） |
| `--no-themes` | `cli/args.ts:192-193`；`main.ts:770` | 同上（主题页） |
| `--exclude-tools` / `-xt` | `cli/args.ts:142-146`；`main.ts:537-539`；`core/sdk.ts:260-262` | CLI 独占，但工具全集 = 内建 + 扩展运行时注册，设置页列不出准确名单；`defaultTools` 已覆盖「关内建工具」，再来一个否认列表就是同一件事的第二份真相 |
| `--no-session` | `cli/args.ts:121-122` | App 的会话列表/续接/导出都建立在会话文件上，没有临时会话界面；属功能新增 |
| `--name` / `-n` | `cli/args.ts:115-120` | RPC 有 `set_session_name`（`rpc-types.ts:62`，`rpc-mode.ts:661`）且 App 已在用（`PiSessionViewModel.setSessionName`），启动参数会与运行时可改的名字重复 |
| `--verbose` | `cli/args.ts:217-218`；`main.ts:942` | 只到 pi 交互式 TUI 的启动输出，RPC 无消费者 |
| `--tui-mode` | `cli/args.ts:203-216`；`main.ts:943` | pi 自己的 TUI 布局；这里的引擎没有 TUI（`tuiMode` 设置键同理） |
| `--api-key` | `cli/args.ts:108-109`；`main.ts:806-815` | 一次性明文参数；App 的凭证面把 key 写进 `auth.json`/`models.json`，也不会让 key 出现在进程参数里 |
| `PI_SHARE_VIEWER_URL` | `config.ts:521-524` | `/share` 是交互式 TUI 内置命令，不在 RPC 面 |
| `PI_HARDWARE_CURSOR` / `PI_HYPERLINKS` / `PI_IMAGE_PROTOCOL` / `PI_TRUE_COLOR` / `PI_TUI_ESC_TIMEOUT` | `docs/environment-variables.md:89-93` | 只被 pi 自己的终端渲染器读；本应用的终端是自绘的 Compose 面板，pi 的 TUI 不运行 |
| `PI_STARTUP_BENCHMARK` | `main.ts:914-916` | 开发期基准开关，且在非交互模式下会直接报错退出 |
| `PI_RADIUS_GATEWAY` / `PI_SERVER_DIR` / `PI_SERVER_ID` | `experimental/radius-auth.ts:8`；`experimental/server.ts:51-52` | 仅源码版的实验特性，不在发行版里 |

### 1.5 pi 的 settings 键里「只在进程启动时读」的行（`RestartEngine` 复核）

| 键 | pi 读取点 | App 标签 | 复核 |
|---|---|---|---|
| `httpProxy` | `main.ts:583` / `:850` | `RestartEngine` | ✓ 进程级 dispatcher，一次 |
| `httpIdleTimeoutMs` | `core/settings-manager.ts:936` → `main.ts:851` | `RestartEngine` | ✓ 进程启动配置 dispatcher |
| `defaultProjectTrust` | `main.ts:745`（启动时那个 manager） | `RestartEngine` | ✓ 读它的是进程启动时建的 manager，新会话不重读 |
| `extensions` / `packages` / `skills` / `prompts` / `themes` | `main.ts:715-718`（每次 `createRuntime`） | `RestartEngine` | `一致`，但**可能有更省的选择**：源码读下来新会话也够（`agent-session-runtime.ts:226-252` → 新 loader + `reload()`）。维持现状的理由与判据写在 `settings-review.md` §7.2，需真机确认 |
| `sessionDir` | `core/settings-manager.ts:724` → `main.ts:670-676` | 无行 | `有意偏离`（§7.5） |
| `terminal.*`（capability overrides 等） | `main.ts:849` `setCapabilityOverrides` | 无行 | `一致`：只服务 pi 的 TUI，手机无意义 |

---

## 2. 用户的两个问题

### 2.1 「我现在能修改系统提示词吗？」

**能。** 有四个入口，其中两个已经在设置里（本轮把第二个补齐）：

| # | 入口 | 行为 | 生效条件 |
|---|---|---|---|
| 1 | 设置 → **提示词** → 「自定义系统提示」 | 传 `--system-prompt`，**整份替换** pi 自带的系统提示词。留空 = 不传，pi 用自带提示词（不是传空串） | 保存后点「重启引擎」（或下次启动 App） |
| 2 | 同处 → 「**追加系统提示**」（本轮新增） | 传 `--append-system-prompt`，**追加**到 pi 组装好的提示词末尾，不替换。`pi` 先做「替换/自带 + 追加」再拼上下文文件与技能（`core/system-prompt.ts:34-41`），所以第 1、2 行可以同时设置：先替换再追加 | 同上 |
| 3 | agent 目录里的 `SYSTEM.md`（项目 `.pi/SYSTEM.md`，项目被信任时） | pi 自己发现并当作替换提示词 | **前提是第 1 行留空**：`resource-loader.ts:526` 是 `systemPromptSource ?? discoverSystemPromptFile()`，显式参数一有值就不再发现文件 |
| 4 | agent 目录里的 `APPEND_SYSTEM.md`（项目同上） | pi 自己发现并追加 | **前提是第 2 行留空**：`resource-loader.ts:532-534` 是 `if (!appendSources)` |

几个必须知道的前提与后果：

- **只在启动时读。** 两行的值由 App 读出后写进新进程的 argv；正在跑的引擎改不动（RPC 没有对应命令，`rpc-types.ts:20-74`）。所以文案统一写「改动在重启引擎后生效」，标签是「需重启引擎」。
- **这两行住在「提示词」分组（本轮从 运行时与诊断 → 进程 搬过来）。** 只搬显示位置：键一个字没改（`app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt`），已经写在 `settings.json` 里的值照旧读得出来。搬家的理由是「用户在哪里找它」——这两行是「模型被交代了什么」，不是「引擎怎么跑」，pi 自己的 CLI 里 `--model` 与 `--system-prompt` 也是挨着的（`cli/args.ts:108-112`），所以设置首页把「提示词」排在「模型与推理」之后。`pre-spawn` 检查的契约随之从「一个分组」改成「两个登记在册的 pre-spawn 分组」（见 §3）。
- **两行的编辑器是多行框。** 整份系统提示词是几千字的 Markdown，`PiSetting.multiline = true` 让 `PiTextEditorSheet` 给多行、框内可滚动的输入框（`PiSettingsEditors.kt` 的 `PiTextEditorMaxLines`）。以前「多行」是从 `depth > 1` 推出来的，`app.runtime.systemPrompt` 漏了 `depth`，于是落进单行框、只显示第一行 —— 这正是「几千字只进去几十字」那个缺陷；`depth` 现在不再决定字段画几行。
- **重启不会丢值。** 值存在 pi 的 `settings.json`（全局文档，键名以 `app.` 开头，pi 不校验、直接忽略）；agent 目录是 bind 进 guest 的，换载荷、重解包 rootfs 都不动它（`docs/pi-contract.md` 版本升级一节）。
- **替换与追加是两回事，不互相顶掉。** 同时设置时两条参数都在命令行上，pi 分别消费。
- **第 3、4 条是本应用没有界面的专家路径。** 用户可以在 工作区 → 终端 里放文件；本应用不写这两个文件，避免和上面两行互相遮蔽出第三种状态。
- **一个 pi 的边角行为**：`--system-prompt` / `--append-system-prompt` 的值会先经过 `resource-loader.ts:54-64` 的 `resolvePromptInput`——如果这串文字**恰好是一个存在的路径**，pi 会把它当文件读成内容。多行的追加内容不会撞上；单行且看起来像路径的替换内容可能撞上。属 pi 行为，不是本应用能修的。

### 2.2 「官方都支持调整哪些东西？我这里面都全了吗？」

- **官方支持调整的全部启动期东西** = §1.1（CLI/env 独占）+ §1.2（同时有 settings 键的）+ §1.3（App 固定的）+ §1.4（不做）。
- **全了吗？** 在「CLI/env 独占」这一格里，改动前**不全**：`--append-system-prompt` 的模型字段存在但设置页没有行（§I11 形状：有值没人写），`--no-context-files` 完全没有。本轮补齐了这两条。改动后，**手机上说得通、且 pi 没有 settings 键的启动期开关，本应用都暴露了**；剩下的是三类，各有理由：pi 已有设置键（§1.2）、App 必须固定（§1.3）、手机上没意义或有害（§1.4）。
- **手机上没意义/不做的，各自理由一句话**：
  - TUI 专属（`--verbose`、`--tui-mode`、`PI_HARDWARE_CURSOR` 一族、`PI_SHARE_VIEWER_URL`）：pi 的 TUI 在这里不运行。
  - 会拆掉 App 自己的面（`--no-extensions` 会关掉设备能力；`--no-skills`/`--no-prompt-templates`/`--no-themes` 会让资源页与引擎互相矛盾）。
  - 重复通道（`--tools`/`--models`/`--provider`/`--model`/`--thinking`/`--approve`/`PI_TELEMETRY`/`HTTP_PROXY`）：pi 有设置键，或 RPC 能运行时改。
  - 功能缺失而非补缺（`--no-session`）：App 的会话面建立在文件上。
  - 安全/约定（`--api-key`）：App 用凭证文件，避免 key 进进程参数。

---

## 3. 本轮改了什么

1. **`app.runtime.appendSystemPrompt`**（新行，`RestartEngine`，多行编辑器 `PiSetting.multiline = true`；当时是用 `depth = 2` 冒充的，现已被 `multiline` 取代）：接进 `PiLaunchOptions.fromSettingValues`；文案写清「追加，不是替换」与「重启引擎后生效」。
2. **`app.runtime.noContextFiles`**（新行，`RestartEngine`）：接 `--no-context-files`，手机上有明确意义（关掉工作区上下文文件对系统提示的注入）且 pi 无 settings 键。
3. **纯函数**：`PiLaunchOptions.fromSettingValues(offline, cacheRetention, systemPrompt, appendSystemPrompt, noContextFiles)`——把「空串=未设置、只有 `long` 才算长保留、布尔缺省=false」这三条归一化从 ViewModel 挪进 `:rpc`；`PiSessionViewModel.launchOptions()` 只留文件 IO。
4. **`rpc/.../PiPreSpawnConfig.kt`**（新文件）：启动期表 + 三条规则（暴露项不得有 pi 设置键；覆盖项不得与暴露项重叠；未暴露项必须写理由）+ `piPreSpawnKnob()` 判定。
5. **`pre-spawn` bare-JVM harness**（`app/src/test/.../PiPreSpawnCheck.kt` + `tools/run-app-pure-checks.sh` 注册）：断言五个键都真的进 argv/env、注册表里都是 `RestartEngine`、ViewModel 里都读、暴露项与设置键不重叠。
6. **`tools/pi-contract.mjs`**：新增一条 surface 断言——表中每个 CLI 参数名/环境变量名必须仍出现在钉住的引擎产物里。失败信息写明「pi 把未知 `--flag` 当扩展参数而不报错，所以开关会静默失效 → 重读 `cli/args.ts` 的 `parseArgs` 并更新 `PiPreSpawnConfig.kt` + `PiLaunchOptions.kt`」。
7. **修过期注释/文案**：注册表「进程」一节原注释说 `effective` 用 `Reload`（与代码实际的 `RestartEngine` 矛盾），已改正并把五行的清单写全（这五行里的两行提示词现在住在「提示词」分组，注册表注释已同步成「三行 + 另两行的去处」）；`app.runtime.systemPrompt` 文案补上「整份替换」并指向新的追加行。

## 4. 不确定项与设备判据

1. **新增两行的端到端效果**：判据——把「追加系统提示」写成一句可观察的偏好（例如「每次回答都以『好』结尾」）→ 重启引擎 → 发一条消息，看行为是否改变；再把「不加载上下文文件」打开 → 重启 → 在工作区放一个会显著改变模型行为的上下文文件，看它是否被忽略。
2. **`--append-system-prompt` 是否遮蔽 `APPEND_SYSTEM.md`**：静态依据是 `resource-loader.ts:532-534`；RPC 面上系统提示词不可观测，所以这条**没有 wire 通道可钉**，只能靠重读源码。若将来 pi 改成合并，App 的「追加行」仍正确，只是文件不再被顶掉。
3. **资源类五行（`extensions`/`packages`/`skills`/`prompts`/`themes`）的 `RestartEngine`**：维持现状；判据在 `settings-review.md` §7.2（装一个技能 → 开新会话 → 看命令面板是否出现）。
4. **`--no-context-files` 的实际收益**：只有在工作区真的存在上下文文件时才有可观察差别。若设备上确认手机用户几乎不会有，这一行可以考虑撤掉——但撤之前要先确认「pi 不会从 guest 的祖先目录读到意外的文件」（`resource-loader.ts:66-` 的 `loadContextFileFromDir` 走的是 cwd 与 agent 侧约定，需在设备上核一遍实际读到的文件集）。

## 5. 自检（本仓库规矩）

- `bash tools/typecheck.sh` → `:rpc 0 / :app 0`；
- `bash tools/run-app-pure-checks.sh` → 含新增的 `pre-spawn` 全过；
- `python3 tools/check-nested-comments.py` → 0 处；
- `node tools/pi-contract.mjs --pi <已安装的钉住版本>`（CI 的 `contract` job）→ 新增的参数名断言通过。
