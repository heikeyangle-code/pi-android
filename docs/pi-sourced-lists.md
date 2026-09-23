# 资源列表的来源规则（App 尽量不维护自己的表）

## 规则

**界面上任何"列出某类东西"的地方，只能有三个来源：**

1. **问 pi** —— RPC 命令（`get_commands`、`get_available_models`、`get_entries`、`pi list`…）；
2. **看 pi 写/读的地方** —— `<agentDir>` 下的文件与目录（`settings.json`、`models.json`、`auth.json`、
   `models-store.json`、`sessions/`、`extensions/`、`skills/`、`prompts/`、`themes/`），
   以及 pi 自己的发现规则；
3. **`pi 无对应物（App 的决定）`** —— 只有这时才允许 App 自己维护一张表，**并且必须能说出
   "为什么问不到"**。

违反这条的后果不是审美问题，而是本仓库已经付过两次学费的那个形状：**App 相信一份副本，副本过期了就静默地做错事**（§M11 能力被默认值覆盖、§M12 配置键被删掉）。

**每张留下来的表，要么被 `tools/pi-contract.mjs` 的断言钉住（它能被 pi 验证的那部分），要么在下面写明"为什么不可达"。**

## 现状清单（2026-09-13 审计）

> **最近更新（2026-09-23，pi 0.87.1 审计）**：把"App 自己维护、pi 问不到"的五张表全部钉进了
> `tools/pi-contract.mjs` 的 **`tables` 组**（设置键、内置 slash 命令、资源类型、工具名、主题令牌），
> 并把"pi 有而 App 没暴露"的设置键从构建日志里的一行搬成了下面那张可排期的表。**那一组第一天就
> 抓到真漏洞**：`/bug`（pi 0.86.1 新增）两张 slash 表都没覆盖。表格内容见"App 维护、且**问不到**的表"。
> 上面 2026-09-13 那行日期标的是那次审计的时间，不动。

### 已经是"问 pi / 看 pi 的地方"

| 界面 | 来源 |
|---|---|
| 会话列表 | **pi 写的目录**：`<agentDir>/sessions/`，两种布局都读（按 cwd 分组 + 平铺），排序照 `SessionManager` 的 `modified` |
| 扩展列表 | **pi 看的目录**：`<agentDir>/extensions/`，按 `collectAutoExtensionEntries` 的规则 |
| 技能 / 提示模板 / 主题 | **pi 看的目录**：`skills/`（`SKILL.md` 一层）、`prompts/**/*.md`、`themes/**/*.json`，全局与工作区两个根 |
| 资源包 | **问 pi**：`pi list`（`settings.json` 的 `packages`） |
| 模型 | **问 pi + 看 pi 的目录**：`get_available_models`（合成后的快照）与 `models-store.json`（pi 抓来的目录，凭证之前就存在） |
| 斜杠命令 | **问 pi**：`get_commands`（加上 pi 明确排除在外的内置命令，见下表） |
| 设置的值 | **看 pi 的文件**：`settings.json`（全局 + 项目，按 pi 的合并规则） |

### 仍然由 App 维护、且**能**被 pi 验证的表

| 表 | 条目 | pi 的真身 | 处置 |
|---|---|---|---|
| `packages/PiProviderPresets.kt` | 13 个厂商 | pi 的 `packages/ai/src/providers/*.ts`（**37** 个 provider） | **已钉住**：`tools/pi-contract.mjs` 对每个 `builtInPi` 预设断言它的 `baseUrl` 仍然出现在钉住的引擎里。这张表特殊——**它会被写出去**（`PiCredentialService.save` 把 baseUrl/api 写进 `models.json`），过期就等于覆盖引擎自己的端点，而 `get_available_models` 按凭证过滤、列不出"还没有 key 的厂商"，所以问不到全量 |
| `packages/PiPackageModel.kt` 的 `PiBuiltinExtension.SHIPPED` | 3 个 | 无（**`pi 无对应物`**：pi 没有"自带扩展"这个概念） | 名字与用途说明是 App 的知识；但**条目本身应与 `extensions/` 目录里的实际文件一致**，而 `readBuiltins` 每次都用文件系统核一遍（不在就显示"未安装"，是可见的错，不是静默的错） |

### App 维护、且**问不到**的表（写明理由 + 钉在哪）

这些表**仍然要 App 自己维护**（理由在中列），但自 2026-09-23 起它们**全部**进入了
`tools/pi-contract.mjs` 的 `tables` 组：钉的是"App 的副本还指着 pi 真有的东西"，不是"副本的内容对不对"
（后者只能靠 `docs/settings-review.md` 那种消费者审计）。**改动这些表之前先读那一组断言的失败文案**——
它写明了该回去重读 pi 的哪个文件。

| 表 | pi 的真身 | 为什么问不到 | 契约怎么钉 |
|---|---|---|---|
| `ui/chat/PiSlashCommands.kt` 的 `PI_BUILTIN_SLASH_COMMANDS`（11 行）+ `PI_UNLISTED_BUILTIN_COMMANDS`（13 条文案） | `core/slash-commands.ts` 的 `BUILTIN_SLASH_COMMANDS`（**24** 个名字） | **pi 故意不给**：内置命令被排除在 `get_commands` 之外（`docs/rpc.md:853`），RPC 也没有任何命令能列出它们 | **双向**：每个 App 名字必须仍在 pi 的列表里，且 pi 的每个名字必须被两张表之一覆盖。**这条已经抓到真漏洞**：`/bug` 是 pi 0.86.1 新增的，两张表都没它，用户输入 `/bug` 得到的是"没有这个命令"——直到 0.87.1 审计 |
| `packages/PiPackageFilters.kt` 的 `RESOURCE_TYPES`（4 个键） | `modes/interactive/components/config-selector.ts` 的 `RESOURCE_TYPES` | RPC 没有通道；四个键是稳定的形状 | 四个键逐个断言仍在 pi 的列表里 |
| `ui/settings/PiSettingsRegistry.kt` 的 **38 个 `pi.*` 键** | `core/settings-manager.ts` 的 `Settings` 接口（52 个顶层键） | **pi 不通过 RPC 暴露设置 schema**（只有文件）。每行的**值**来自 pi 的文件，但键/默认值/描述只能是 App 的转写——所以它还需要"每个键都有消费者"的审计（见 `docs/settings-review.md`） | 每个 `pi.*` 键（含 `compaction.*`/`retry.provider.*` 这类嵌套路径）必须在 `Settings` 的 `.d.ts` 里仍能解析；**反方向只打印不失败**（pi 有而 App 没暴露的 24 个是决策，不是缺陷） |
| `PiQuickAdd.builtinToolDefaults`（4）+ `PiSettingsRegistry` 的 `optionalTools`（3） | `core/tools/index.ts` 的 `ToolName` 联合（8 个：read/bash/powershell/edit/write/grep/find/ls） | 工具名只出现在 pi 的系统提示词里，没有任何端点在列它 | 7 个名字逐个断言仍是 pi 的内建工具（chip 写出去的是**完整白名单**，名字错了等于一个工具都不启用） |
| `ui/theme/PiThemeFiles.kt` 的 `REQUIRED_TOKENS`（51）+ `OPTIONAL_FALLBACKS`（5） | `modes/interactive/theme/theme-schema.json` 的 `colors`（**56** 个 token）+ `theme.ts` 的 `withThemeColorFallbacks` | 主题 schema 没有端点；用户主题文件的令牌集只能照着抄 | 两个方向都断言：App 的 51∪5 必须**正好等于** schema 的 56 个 token 名；五个 fallback 必须指向 pi 指的同一个 token |
| `ui/render/PiMarkdownTheme.kt`、`ui/render/PiCodeHighlight.kt` 的渲染令牌 | pi 的 markdown/高亮渲染器内部 | 渲染器内部没有数据通道；主题**颜色**是例外——那些读的是 pi 的主题 JSON（`PiPalette.kt` 的转写由 `theme` 组逐值断言） | **未钉**：这两个表的"令牌"是渲染器内部取值，pi 没有可读的声明面。若哪天它变成可读的，先在这里登记再加断言 |

## 这条规则怎么落地（防止再长出新表）

1. **新加一个"列表类界面"时，先回答"pi 从哪知道这件事"**：RPC？文件？都没有？（第三种要写进
   `pi 无对应物` 并说明理由。）
2. **能问 pi 的，就不要自己列**；**pi 靠看目录发现的，就按 pi 的规则看同一个目录**——
   不要自己发明一套发现规则（扩展/技能/模板/主题这四种的规则各不相同，pi 在
   `core/package-manager.ts:645-653` 里分派，照抄它）。
3. **能被 pi 验证的表，就加一条 `tools/pi-contract.mjs` 断言**（现在有：RPC 命令名、扩展 UI 方法名、
   `models.json` 的替换与合并语义、随包扩展能不能装进引擎、10 个内置厂商的 baseUrl、工具结果文本、
   会话条目面，以及上面那五张抄写表 = `tables` 组）。
   **一条也不许"只加断言不留后果"**：每条都要能在失败时打印"回去重读哪个 App 文件"。
4. **写出去的东西不要覆盖 pi 自己的**：这也是本次审计抓到的一处真问题——
   `PiCredentialService.save` 原本把 App 的显示名（`Google AI Studio`）写进 `models.json` 的
   provider 块，覆盖了 pi 自己的 `Google`。现在只对 pi 没有的厂商写 `name`。

## 附：pi 有、App 没有暴露的设置键（24 个）

`tools/pi-contract.mjs` 的 `tables` 组把 38 个 `pi.*` 键逐个对着 `Settings` 接口断言，**反方向只打印**
——因为"pi 有而 App 没给一行"是决策，不是缺陷。但打印只活在构建日志里，所以在这里排一次队：
**四类**，每一类要不要动都不需要这一轮下结论，只需要它可被排期。

> 判据来源：pi 0.87.1 的 `docs/settings.md`（每行一句官方说明）与 `core/settings-manager.ts` 的
> `Settings` 接口。App 侧的 `PiSettingsRegistry.kt` 是那张表的另一端。

| 类 | 键 | 一句话判断 |
|---|---|---|
| **A. 终端交互界面专属**（App 没有那个界面，设了也不会有可见效果） | `tuiMode`、`fullscreenScrollbar`、`fullscreenCopyOnSelect`、`fullscreenExitOutput`、`outputPad`、`editorPaddingX`、`autocompleteMaxVisible`、`doubleEscapeAction`、`externalEditor`、`showHardwareCursor`、`quietStartup`、`collapseChangelog`、`treeFilterMode`、`terminal`（`showImages`/`imageWidthCells`/`clearOnShrink`/`showTerminalProgress`/`hyperlinks`/`images`） | **不动**。这些键描述的是 pi 自己的 TUI（`tui.ts` / `interactive-mode.ts`）；本 App 用 `PtyLauncher` 把原版 TUI 跑在终端页里，那个 TUI 直接读 `settings.json`，用户要调就去终端页调。给它们做 Android 行只会多出"设了看不出变化"的开关（`docs/settings-review.md` 的 §I11 形状） |
| **B. 资源**追加**列表**（`extensions`/`skills`/`prompts`/`themes`） | `extensions`、`skills`、`prompts`、`themes` | **可排期，但要先想清语义**。这四个是 pi 的"额外路径"列表，App 现在**只读目录**（`PiResourceDiscovery.kt` 按 pi 的发现规则看 `extensions/`、`skills/`、`prompts/`、`themes/`），不写 settings 里的追加项。要暴露就得同时回答"相对路径怎么算""工作区与全局哪个根"，属于新界面而不是新键 |
| **C. 记账 / 内部状态**（pi 自己写、用户基本不该手改） | `lastChangelogVersion`、`trackingId`、`warnings`（`anthropicExtraUsage`）、`enableAnalytics` | **不动**，除非用户能看见它的效果。`lastChangelogVersion`/`trackingId` 是 pi 的状态簿记；`enableAnalytics` 是实验性首启的埋点开关；`warnings.anthropicExtraUsage` 只影响 pi 的一条提示语 |
| **D. 渲染选项** | `markdown`（`codeBlockIndent`、`mermaid`） | **排在 B 之后**：App 有自己的 markdown 渲染栈（`ui/render/PiMarkdownTheme.kt`、`PiCodeHighlight.kt`），pi 的这两个键是它自己的渲染器用的，改了只影响终端页 |
| **E. 已用别的手段覆盖** | `sessionDir` | **不用暴露**：App 一直显式传 `--session-dir`（并设 `PI_CODING_AGENT_SESSION_DIR`），优先级高于这个设置键——见 `PiSettingsRegistry.kt:863-864` 的注释 |

**不改这一节不需要任何理由，改它需要一个用户能看见的场景。** 下一轮 bump 时 `tables` 组会把新出现的
"pi 有而 App 没有"的键继续打印出来；**新键应该被加进这张表**，而不是留在日志里。
