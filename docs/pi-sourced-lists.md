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

### App 维护、且**问不到**的表（写明理由）

| 表 | pi 的真身 | 为什么问不到 |
|---|---|---|
| `ui/chat/PiSlashCommands.kt` 的 `PI_BUILTIN_SLASH_COMMANDS` | `core/slash-commands.ts:19-43` | **pi 故意不给**：内置命令被排除在 `get_commands` 之外（`docs/rpc.md:853`），RPC 也没有任何命令能列出它们 |
| `packages/PiPackageFilters.kt` 的 `RESOURCE_TYPES`（4 个键） | `modes/interactive/components/config-selector.ts:26-38` | RPC 没有通道；四个键是稳定的形状 |
| `ui/settings/PiSettingsRegistry.kt` | `core/settings-manager.ts:106-158` 的 `Settings` 接口 | **pi 不通过 RPC 暴露设置 schema**（只有文件）。每行的**值**来自 pi 的文件，但键/默认值/描述只能是 App 的转写——所以它需要的是"每个键都有消费者"的审计（见 `docs/settings-review.md`） |
| `ui/render/PiMarkdownTheme.kt`、`ui/render/PiCodeHighlight.kt` 的渲染令牌 | pi 的 markdown/高亮渲染器内部 | 渲染器内部没有数据通道；主题**颜色**是例外——那些读的是 pi 的主题 JSON |

## 这条规则怎么落地（防止再长出新表）

1. **新加一个"列表类界面"时，先回答"pi 从哪知道这件事"**：RPC？文件？都没有？（第三种要写进
   `pi 无对应物` 并说明理由。）
2. **能问 pi 的，就不要自己列**；**pi 靠看目录发现的，就按 pi 的规则看同一个目录**——
   不要自己发明一套发现规则（扩展/技能/模板/主题这四种的规则各不相同，pi 在
   `core/package-manager.ts:645-653` 里分派，照抄它）。
3. **能被 pi 验证的表，就加一条 `tools/pi-contract.mjs` 断言**（现在有：RPC 命令名、扩展 UI 方法名、
   `models.json` 的替换与合并语义、随包扩展能不能装进引擎、10 个内置厂商的 baseUrl）。
4. **写出去的东西不要覆盖 pi 自己的**：这也是本次审计抓到的一处真问题——
   `PiCredentialService.save` 原本把 App 的显示名（`Google AI Studio`）写进 `models.json` 的
   provider 块，覆盖了 pi 自己的 `Google`。现在只对 pi 没有的厂商写 `name`。
