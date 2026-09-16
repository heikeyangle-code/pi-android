# 字体审查 · 待应用清单（红线文件）

这份清单是**字体使用审查**（规则 #7，`docs/pi-android-ui-spec.md` §1）在**会话加载路**这条线上
的欠账。审查本身已经落地（见文末「已落地」），这里单列的是**当时不许动的三个位置**——修它们会与
正在改写会话加载路的代理撞车，所以只记录、不应用。

> **等会话加载代理落地后再应用。** 应用时逐条回读，**行号一律当作线索**：
> 这三个文件在审查期间被另一个代理重写（`ChatScreen.kt` 位移 100 行以上、
> `ChatSheets.kt` 整段重写），`docs/ui-prose-audit.md` 已经为本项目立过同一条规矩
> ——「pi 的源码用 `file:line`，我们自己的树用符号名」。所以每一条都带**符号名 + 原文**，
> 原文是唯一的真相。

**规则 #7 原文**（`docs/pi-android-ui-spec.md` §1）：

> **两种声音** | 人写给人的用系统字体；机器输出的（命令/代码/diff/终端/工具输出）用等宽。
> 这条规则让"谁在说话"一目了然。

**角色表**（`ui/theme/PiTheme.kt` 的 `PiTextStyles`，本次审查未改定义）：

| 角色 | 字号/行高 | 字体 |
|---|---|---|
| `meta` | 12/18 | 系统字 |
| `monoSmall` | 12/18 | JetBrains Mono |
| `mono` | 13/20 | JetBrains Mono |
| `code` | 13/19 | JetBrains Mono |
| `prose` | 14/23 | 系统字 |
| `numeric` | = `mono` | JetBrains Mono |

**边界规则**（本次审查定下、已由裁决确认）：

> 稿子明确画成 mono 的**中文分类词**（`N 项` / `N 处` / `图片 N` / 「技能」 / 工具卡状态词 /
> 「已允许」）不算违例；**我们后加的整句**（「pi 会加载它」「审计日志：…」）才算。
> 一句话里既有我们的话又有机器值时（`审计日志：<path>`），**拆两段**
> （`ui/components/PiCommon.kt` 的 `PiMixedLine`），不许整行改 mono。

---

## A. 需要改的（7 条）

### A1 · `ChatScreen.kt` · `AttachmentThumb`（审查时 `:3284`）

| | |
|---|---|
| 现在 | `Text(image.mimeType.substringAfter('/').ifEmpty { "图片" }, style = PiTheme.text.meta, …)` |
| 应改成 | `style = PiTheme.text.monoSmall` |
| 依据 | 附件占位显示的是 MIME 子类型（`png` / `jpeg`）＝机器标识符。v2 的图片网格把「第 N 张图片 / image/png」整块画在 `mono t12`（`direction-b-v2.html:854`，`className="mono t12 c-muted"`）；同一张卡片的 `ui/blocks/ImageGridBlock.kt:251` 本轮已统一为 `monoSmall`。规则 #7 的机器语言层 |

### A2 · `ChatSheets.kt` · `ModelPickerSheet` → `ModelRow`（审查时 `:175`）

| | |
|---|---|
| 现在 | 副行 `provider/id · 200k 上下文 · 思考 · 图片`，整行 `PiTheme.text.meta` |
| 应改成 | 模型 id 那一段 `monoSmall`（或照稿拆两行：id 一行 mono、provider 一行系统字） |
| 依据 | v2 的模型 sheet：`<span className="mono t13 c-text ell fl1">{m.id}</span>`（`direction-b-v2.html:1537`），provider 另起一行 `t12 c-muted`（`:1540`）。模型 id 是标识符 → 规则 #7 |

### A3 · `ChatSheets.kt` · `ModelPickerSheet` → `ModelRow`（审查时 `:186`）

| | |
|---|---|
| 现在 | 费用行 `"$${trimCost(cost)} / M 输入 · … / M 输出"`，`PiTheme.text.meta` |
| 应改成 | `PiTheme.text.monoSmall` |
| 依据 | v2 模型行第三行 `<div className="mono t12 c-muted">{m.c}</div>`（`direction-b-v2.html:1541`）；`05-compose-migration-plan.md` §4.2「耗时/退出码/行数/token/费用/秒数 六类改成 `numeric`」 |

### A4 · `ChatSheets.kt` · `ContextBlock`（审查时 `:710`）

| | |
|---|---|
| 现在 | `"已用 " + … + " / 窗口 " + … + " · " + (自动压缩开/关)`，整行 `PiTheme.text.meta` |
| 应改成 | 拆两段：读数为机器值 → `monoSmall`；`· 自动压缩开/关` 是我们的词 → 系统字（或把这三个读数按 `PiMixedLine` 的形状处理） |
| 依据 | v2 的「会话信息」sheet 把「上下文占用」的值画成 `mono tab`（`direction-b-v2.html:1585`）；token 数属 §4.2 的六类读数 → 规则 #7 |

### A5 · `ChatSheets.kt` · `UsageRow` 的值（审查时 `:846`）

| | |
|---|---|
| 现在 | `Text(value ?: "—", style = PiTheme.text.meta)` — 输出 / 缓存读 / 缓存写 / 费用四个读数 |
| 应改成 | `value` → `PiTheme.text.monoSmall`（`label` 保持 `meta`：那是我们的词） |
| 依据 | v2 「会话信息」sheet：label 列 `t13 c-muted` 系统字、value 列 `mono tab`（`direction-b-v2.html:1583`、`:1585`）；`05` §4.2 token/费用 |

### A6 · `ChatSheets.kt` · `StatLine` 的值（审查时 `:906`）

| | |
|---|---|
| 现在 | `Text(value, style = PiTheme.text.meta)`，一张表覆盖全部读数行 |
| 应改成 | 按行的**来源**分档：`会话 ID` / `会话文件` / `Token` / `上下文占用` / `累计费用` → `monoSmall`；`消息` / `工具调用` / `会话名称` 保持系统字 |
| 依据 | v2 的同一张表用三元表达式逐行决定：
`className={'t13 c-text fl1 '+(r[0]==='消息'||r[0]==='工具调用'||r[0]==='会话名称'?'':'mono tab')}`（`direction-b-v2.html:1585`）—— 会话名是我们/用户写的，其余是机器写的 |

### A7 · `ChatSheets.kt` · `ForkPickerSheet` 的条目 id（审查时 `:996`）

| | |
|---|---|
| 现在 | `Text(message.entryId, style = PiTheme.text.meta)` |
| 应改成 | `PiTheme.text.monoSmall` |
| 依据 | 条目 id 是会话文件里的标识符（同 sheet 的等宽序号已经是 `PiTheme.text.mono`，`07-construction-decisions.md:310`「分叉列表每条加等宽序号」）→ 规则 #7 |

### A8 · `ChatSheets.kt` · `SessionToolsSheet` 的扩展名（审查时 `:429`）

| | |
|---|---|
| 现在 | `Text("${extension.name}（${extension.scope}）", style = MaterialTheme.typography.bodyLarge, …)` |
| 应改成 | `extension.name` → `PiTheme.text.mono`（`（scope）` 是我们的词，若同屏还画它，按 `PiMixedLine` 拆） |
| 依据 | 扩展名是标识符。v2 的扩展行 `<Row key={e.t} title={e.t} mono value={e.s} …>`（`direction-b-v2.html:2966`，`mono` 加在 title 上）；本轮已按同一条把 `packages/PiPackagesScreen.kt` 的内置扩展名改成 `mono` |

---

## B. `ui/chat/SessionTreeScreen.kt`（3 条）

> 这个文件在原始白名单里，但审查期间它被会话加载代理改动（新增 `onLoadEntries`），
> 因此一并留在这里，等那条线落地后再应用。

### B1 · 树行 · `entryLabel(entry)`（审查时 `:793`）

| | |
|---|---|
| 现在 | `style = MaterialTheme.typography.labelMedium`（系统字，primary 色） |
| 应改成 | `PiTheme.text.monoSmall` |
| 依据 | `entryLabel` 返回的是 **pi 的条目类型名**：`entry.message.role`（`user`/`assistant`/`toolResult`）或 `compaction`/`branch_summary`/`entry.type` —— 机器分类词，不是我们写的界面文案。先例：`ui/blocks/HookMessageBlock.kt:57` 的 `item.customType` 已是 `monoSmall`；v2 的 `CustomHead` 标签槽恒为 `mono t12`（`direction-b-v2.html:943-951`，用在 `:2601`/`:2622`）；`06-v2-construction-reference.md` §3 构件 9「自定义消息卡…等宽标签」 |

### B2 · 条目 tab · `EntryRow` 的 `entry.type`（审查时 `:907`）

| | |
|---|---|
| 现在 | `style = MaterialTheme.typography.labelMedium` |
| 应改成 | `PiTheme.text.monoSmall` |
| 依据 | 同 B1（同一个值、同一类机器分类词） |

### B3 · 条目 tab · `EntryRow` 的 `entry.id.orEmpty()`（审查时 `:913`）

| | |
|---|---|
| 现在 | `style = PiTheme.text.meta`（系统字） |
| 应改成 | `PiTheme.text.monoSmall` |
| 依据 | 会话条目 id 是机器标识符 → 规则 #7；同族 A7（`ChatSheets` 的 `message.entryId`） |

---

## C. 明确**不改**的（复核用，免得下一轮又翻出来）

这些在审查中判为**正确**，依据逐条给出，应用上面的清单时不要顺手改：

| 位置 | 现在 | 为什么保持 |
|---|---|---|
| `ChatScreen.kt` 对话内查找框 placeholder「在对话里查找」 | `PiTheme.text.mono` | `02-real-content.md:145` 明写「搜索框是等宽字体」；两个兄弟搜索框在稿子里也是 mono（`direction-b-v2.html:1868` 会话列表、`:1912` 会话树） |
| `ChatScreen.kt` 模型 chip（含空态「选择模型」） | `monoSmall` | v2 的 chip 是 `mono t12`（`direction-b-v2.html:1345`），内容就是机器值 |
| `ChatScreen.kt` 键位 chip（`/ ! !! @ 图片 编辑器`） | `monoSmall` | v2 `mono t12`（`:1428`）——**含中文的 chip 也画 mono**，是稿子明确的中文分类词 |
| `ChatScreen.kt` 队列 chip（`⇢ 穿插 N` / `⇣ 后续 N`） | `monoSmall` | v2 `<Chip … mono>`（`:1384-1385`） |
| `ChatScreen.kt`「收回并编辑」「加载更早的 N 条」「关闭查找」、查找计数 | `meta` / `monoSmall` | v2 `t12` / `mono t12`（`:1387`、`:1622`、`:1559`、`:1557`） |
| `ChatScreen.kt` 引擎状态符号/词 | `monoSmall` / `meta` | v2 `Engine`：符号 `mono t12`、词 `t12 c-text`（`:465-468`） |
| `ChatSheets.kt` `status.key` | `monoSmall` | `07-construction-decisions.md:188`「键名等宽灰」 |
| `SessionTreeScreen.kt` 搜索 placeholder「筛选条目文字」 | `monoSmall` | v2 `className="mono t12 c-muted"`（`:1912`） |
| 工具卡页脚状态词（`运行中`/`成功`/`失败`/`被拒` · `退出码 N` · `N 行`） | `monoSmall` | v2 页脚 `div` 整体 `className="mono t12"`（`:748`）；`06 §2` 工具卡 |
| `GrepBlock.kt`「N 处」、`PathListBlock.kt`「N 项」 | `numeric` | v2 工具卡 `right="12 项"` 在 `mono t12` 里（`:2554`） |
| `ImageGridBlock.kt`「图片 N」 | `monoSmall` | v2 `mono t12`（`:854`） |
| `SkillInvocationBlock.kt`「技能」、`BranchSummaryBlock.kt`「分支摘要」、`HookMessageBlock.kt` 的 `customType` | `monoSmall` | `06 §3` 构件 9「等宽标签」+ v2 `:2601`/`:2622`（本轮已把 `BranchSummaryBlock` 对齐） |
| `PiPackagesScreen.kt` 来源种类槽「本地」、作用域词「所有项目（全局）」/「仅当前项目」 | `monoSmall` | 它们是**值槽里的分类词**，同槽是 `npm`/`git`（`:996`）；稿子这类槽画 mono |
| `PiPackagesScreen.kt`「已允许」/「已拒绝」 | `mono` | v2 的审批行带 `valueMono`（`:2929-2931`） |
| `ui/terminal/**` 键帽/动作 chip 的裸 `FontFamily.Monospace` | 系统等宽 | `07-construction-decisions.md:6-9` D8「终端页保留系统等宽（划出范围）」 |

---

## D. 本轮已落地（对照用）

审查的其余部分已经改完并验证（`tools/typecheck.sh` 只剩 `DiagnosticsReport.kt` 那 3 条
`BuildConfig` 假阳性；`python3 tools/check-nested-comments.py` → OK）。要点：

- **规则 #7 的机器值改回机器字**：`DiffBlock` 折叠行、`ImageGridBlock` 的 MIME 与 `+N`、
  `PiBilledCostLine` 计费行、`LicensesScreen` 许可标题（`git 1:2.43.0-…` 这类包名+版本）、
  `PiCredentialScreen` 模型 id、`PiModelsScreen` 的默认/循环模型值、`DeviceCapabilityScreen`
  的写入边界路径行、`PiMarkdownComponents` 的围栏语言标记、`PiPackagesScreen` 的内置扩展名。
- **规则 #7 的我们的话改回系统字**：`LicensesScreen` 的「阅读」、`PiPackagesScreen` 的
  「pi 会加载它」/「已安装：pi 会加载它」。
- **工具卡内的机器结果**：`EditBlock` / `WriteBlock` 的 `item.output` → `monoSmall`
  （`ErrorBlock` 的 message 仍是 prose，那是独立错误块）。
- **混排行拆两段**：新增 `ui/components/PiCommon.kt` 的 `PiMixedLine`；用于
  `WorkspaceViewer` 查看器副行（路径 mono + 大小/时间系统字）、`PiSettingsEditors`「保存为 」、
  `DeviceCapabilityScreen`「审计日志：」、`PiPackagesScreen.resourceOrigin`
  （「（资源包：」+ 包名 mono +「）」）、`PiSettingsRows` 危险行（`!` monoSmall + 「执行」 meta）。
- **字号**：`PiTheme.kt` 的 `labelSmall` 11.5 sp → **12 sp**（B7 的同类收尾：11.5 低于
  `01-design-spec.md` §2 的标签地板，且 `06 §2` 的 5 档里没有这一档）；
  `BlockChrome.ExpandLabel` 的 chevron 14 dp → **13 dp**（稿子 `direction-b-v2.html:911` `s={13}`）。
