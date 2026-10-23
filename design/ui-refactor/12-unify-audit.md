# 12 · 统一化审计与目标（工具卡除外）

> 2026-10-23。前面只谈"要不要重做"，这篇回答"到底乱在哪、怎么收成一套"。
> 统计命令基于当前工作区 `app/src/main/kotlin`。

## 1. 乱在哪（数字）

| 项 | 现状 | 问题 |
|---|---|---|
| 组件族 | `PiSettings*` 16 个（Card/Row/RowShell/ValueRow/ToggleRow/Switch/Chip/Badge/Chevron/Hairline/CardSlice/Dialog/Sheet/EntryRow/ListExpander/EffectiveBadge/Stack）与通用 `Pi*` ≈25 个（PiValueRow/PiSwitchRow/PiTable/PiStatusLine/PiSectionHeader/PiSeg/PiOptionPickerSheet/PiNumberEditorSheet/PiTextEditorSheet/PiThemeEditorSheet/PiThinkingBudgetsSheet/PiDialog…） | **两套并行**，设置页不复用通用件；同一个"行/开关/芯片"有两份实现 |
| 页面私有组件 | `ChatScreen.kt` 私有可组合 **41 个**；`ChatSheets.kt` 17 个 | 同一个概念（chip/sheet/row）在页面内再造 |
| 顶栏 | 4 处实现：`PiTopBar`、`ChatTopBarIcon`、`ViewerTopBar`、设置各页各自拼 | 高度/内边距/图标命中区不一致 |
| 弹层外壳 | ≥8 种：`ModelPickerSheet`/`ThinkingPickerSheet`/`SessionToolsSheet`/`ContextSheet`/`SessionStatsSheet`/`ForkPickerSheet` 各自搭壳；`WorkspaceRootSheet`/`RowMenuSheet`/`MenuSheet`/`NewSheet`/`NameDialog`；`WsSheet`；`PiDialog`；`PiSettingEditorSheet` | 调用点 **82 处** `ModalBottomSheet/AlertDialog`，圆角、把手、拖拽、遮罩各不同 |
| 间距 | `PiSpacing` 引用 322 次，但**裸 `.dp` 670 次** | 一半是 token，一半是随手值 |
| 字号 | 排版 token **0 个**，裸 `.sp` 56 次 | 没有字号层级，页面各写各的 |
| 圆角 | 14 种字面量 | 6/8/10/12/14/16/20/24…没有角色 |

## 2. 目标：一套 token + 8 个组件

**tokens**（`ui/theme`）：
- `PiSpacing` 保留（4/8/12/16/20/24/32 七档），**新增禁止线**：新代码不得出现裸 dp（例外：1px 细线、图标尺寸）。
- **`PiType`**：6 档 —— `page`(22/600)、`row`(15/400)、`meta`(12/400 mono)、`micro`(10/400 mono)、`title`(17/600)、`mono`(13)。
- **`PiShape`**：4 个角色 —— `chip`(999)、`control`(10)、`card`(12)、`sheet`(20)。

**8 个组件**（`ui/components`，全部唯一）：
1. `PiTopBar`（标题/返回/右侧动作/引擎态，唯一顶栏）
2. `PiSection`（分组标题 + 卡片容器）
3. `PiRow`（48/56 两档；值 / 开关 / chevron / 危险色；行内不再套卡片）
4. `PiChip`（26 高，999 圆角，mono 10.5，可带 ×/dot）
5. `PiSeg`（已有，3 项以内）
6. `PiSheet`（唯一弹层外壳：把手、圆角 20、遮罩 55%+2px 模糊、可选搜索行、最大 80%）
7. `PiDialog`（已有，确认/输入两种）
8. `PiEmpty`（空态：一行 mono + 可选动作）

**硬规则**：任何 `screens/**`、`chat/**`、`settings/**` 不得再定义顶栏/sheet/chip/row。需要新形态时先扩这 8 个。

## 3. 迁移批次（每批 CI 绿再下一批；工具卡全程不动）

| 批 | 内容 | 触及文件 | 风险 |
|---|---|---|---|
| **B1** | 顶栏 4→1；对话页新增页脚（pi 的会计师行） | PiRoot.kt / ChatScreen.kt / ViewerTopBar | 小，视觉收益最大 |
| **B2** | 弹层外壳 8→1（先聊天 6 个 sheet） | ChatSheets.kt | 中，逐个换壳 |
| **B3** | 设置页迁到 `PiSection/PiRow`，删除 `PiSettings*` 16 个 | settings/* | 中，面大但机械 |
| **B4** | 工作区 5 个 sheet + 列表行迁到同一套 | ProjectScreen / WorkspaceChrome | 中 |
| **B5** | 清理裸 dp/sp、14 种圆角、空态/载入/错误统一 | 全仓 | 低，但需一遍过 |

## 4. 约束（本机没有 Android SDK）

`ANDROID_HOME` 为空、无 `gradle` 二进制、只有 JDK 17 → **本机不能编译**。
因此不做"一次改 242 个文件"的大提交；每批小、可 review、靠 CI 验证，绿了再下一批。

## 5. 与 pi 的关系

这套统一层不是"发明新设计"，而是把 pi 的语义固定下来：
顶栏=标题区、页脚=状态（pi 的 footer）、`PiRow`=pi 的 selector 行、`PiSheet`=pi 的 overlay、
`PiChip`=pi 的状态标签。视觉值全部来自 `PiPalette` 的 56 个 token，不新增颜色。
