# 14 · 就着现状说怎么统一（不看幻想稿，只看代码）

> 前面那份 `unify-v1.html` 是我凭空画的：行高 56、卡片圆角 12、页脚……**都和现状不符，作废**。
> 这份是逐处对照当前代码写的：哪里两套、各自的值是多少、改成什么、先改哪。

## 0. 你现在的真实值（这些是基准，不要改）

| 项 | 现状值 | 出处 |
|---|---|---|
| 顶栏 | 高 **48**、左右 inset 14、press box **30×30** radius 8、图标 17 | `PiRoot.kt` `TOP_BAR_*`；`ChatScreen.kt` `CHAT_TOP_BAR_*`（同一组数字写了两遍） |
| 页面水平边距 | **14** | `PiSettingsStyle.pageHorizontal` |
| 行内边距 | 垂直 10 / 水平 14 | `PiSettingsStyle.rowPadding*` |
| 卡片 | 内边距 12（松 14）、圆角 **10** | `PiSettingsStyle.card*` |
| 分组 | 组间距 18、组头距 7、分隔线 inset 14 | `PiSettingsStyle.group*/dividerInset` |
| 芯片 | 高 **26**、水平 9 | `PiSettingsStyle.chip*` |
| 开关 | 38×22，旋钮 16 | `PiSettingsStyle.switch*` |
| 搜索框 | 高 **40** | `PiSettingsStyle.searchFieldHeight` |
| 状态信息 | 已从对话页移除（`PiStatusLine` 仍在组件里但不再被调用），用量收在 ⋮ 的「上下文与用量」 | `ChatScreen.kt:1960` 注释 |

## 1. 真正的“不统一”是这五条

### 1.1 弹层是 Material，页面是手绘（最刺眼）

`ChatSheets.kt` 的 `ModelPickerSheet` 等直接用了
`ModalBottomSheet`（M3 默认：28dp 圆角、拖拽把手、自己的遮罩）+
`OutlinedTextField`（M3 字段，56 高、描边、内部 padding 与全 App 不同）+
`MaterialTheme.typography.titleMedium` + `TextButton`。

结果：**一打开 sheet 就像进了另一个 App**。这是“乱”的第一来源。

**改**：`PiSheet`（自绘外壳：圆角 20 顶部、36×3 把手、遮罩 `0x85000000`、标题 17/600、可选搜索行）
+ `PiField`（高 40，与设置搜索同款）+ `PiRow`。8 个 sheet 全部换壳，删 `ModalBottomSheet` 直调。

### 1.2 排版三套

- `PiTheme.text`（meta/mono/monoSmall/prose/code）——54 个文件在用；
- `MaterialTheme.typography.*`——**28 个文件**在用（顶栏 `titleMedium`、sheet 标题、设置页等）；
- 裸 `.sp`——56 处。

**改**：定义 `PiType`：`page` 22/600、`title` 17/600、`row` 15/400、`meta` 12/400、`micro` 10/400 mono、`prose` 15/1.6、`mono` 13。
`PiTheme.text.*` 变成它的别名；28 个文件里的 `MaterialTheme.typography` 全部替换；裸 sp 清零。

### 1.3 间距两套 + 裸 dp 670

`PiSpacing`（322 处）与 `PiSettingsStyle`（自成一套 14/10/12/18/26/38/40）并行；另有 **670 处裸 `.dp`**。

**改**：把 `PiSettingsStyle` 提升为全局 `PiMetrics`（值不变，就是上表），`PiSpacing` 迁到它；
新代码禁止裸 dp（例外：1dp 细线、图标尺寸）。

### 1.4 组件两族 + 一个 41 个私有组合的页面

`PiSettings*` 16 个（Card/Row/RowShell/ValueRow/ToggleRow/Switch/Chip/Badge/Chevron/Hairline/CardSlice/Dialog/Sheet/EntryRow/ListExpander/EffectiveBadge/Stack）
与通用 `Pi*`（ValueRow/SwitchRow/Table/SectionHeader/Seg/OptionPicker/…）并行；
`ChatScreen.kt` 里还有 **41 个私有 Composable**，其中 `ChatTopBarIcon` 和顶栏是 `PiTopBar` 的重复实现。

**改**：统一到 8 个（`PiTopBar`/`PiSection`/`PiRow`/`PiChip`/`PiSeg`/`PiSheet`/`PiDialog`/`PiEmpty`）；
`PiSettings*` 迁完删除；对话页顶栏改为调用 `PiTopBar(actions=…)`。

### 1.5 圆角 14 种

8/10/12/14/16/20/28（M3 sheet）/999… **改**：四个角色——`chip 999` / `control 8` / `card 10` / `sheet 20`。

## 2. 你要的“优化”，具体是这五件事

按“收益/风险”排序，每件都能单独验收：

| # | 做什么 | 收益 |
|---|---|---|
| 1 | **弹层统一**（8 个 sheet 换 PiSheet + PiField） | 打开任何 sheet 不再像另一个 App；这是观感最大的一块 |
| 2 | **顶栏统一**（对话页删私有实现，用 PiTopBar；视图页同款） | 三处顶栏一模一样：48、30 按压框、17 图标 |
| 3 | **排版统一**（PiType 一套，干掉 28 处 M3 typography 与 56 处裸 sp） | 字号层级一致，页面之间不再“这儿大那儿小” |
| 4 | **设置页去重**（PiSettings* 16 → PiRow/PiSection，12 组收成 8 组：模型/资源/外观/对话/输入/工具/引擎/应用） | 设置页与工作区、对话用同一套行，删掉一整套并行组件 |
| 5 | **上下文入口优化**（不做页脚）：⋮ 左侧加一个 `12%` 的 `PiChip`，点击直接开 ContextSheet | 你要的信息可见，但不像页脚那样常驻占高；`PiStatusLine` 可以删了 |

## 3. 不做

- 不加页脚（你的判断对：信息已经收起来，再加一行是重复）；
- 不改工具卡；
- 不改主题（颜色已是 pi 令牌，`system` 按 `13-system-theme.md` 单独移植）；
- 不改三 tab 结构。

## 4. 顺序

`1 → 2 → 3 → 5`（先观感、后结构），第 4 项面大，放最后一批。
每批只碰它该碰的文件，CI 绿了再下一批。
