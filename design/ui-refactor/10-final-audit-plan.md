# 10 · 终审计划：逐台对照 v2，有偏差就修

> 用户原话：「最终的效果到没达到 v2 所有文档最初的设计效果。**不能有任何偏差**。让他对照图片和相关 HTML 和文档审查。给他们划分好文件，一人一半。**发现不一致的就自主修复**，修复完了我才会安装亲自查看。」
> 执行方式：派两个审查代理（下称**甲 / 乙**），文件集互不重叠，各自「审 + 修」，然后由父代理提交推 CI、拉 APK 给用户；用户装完截图，再交 v2 设计师代理做验收评审。

## 1. 文件划分（互不重叠，越界即冲突）

| | **甲 · 对话流内核** | **乙 · 外壳、页面与浮层** |
|---|---|---|
| 可改 | `ui/blocks/**`、`ui/render/**`、`ui/chat/**`、`ui/theme/**`、`ui/components/PiCommon.kt`、**`ui/screens/ChatScreen.kt`（对话页内核：转录列表/输入区/滚动跟随）** | `ui/PiRoot.kt`、`ui/screens/**`（**ChatScreen.kt 除外**，发现那里的问题只报告不修改）、`ui/settings/**`、`ui/packages/**`、`ui/extension/**`、`ui/device/**`、`MainActivity.kt`、**新建** `ui/components/PiDialog.kt` |
| 只读 | 乙的全部 | 甲的全部 |
| 不许碰 | `rpc/**`、`ui/terminal/**`、`PiPalette` 的令牌值与 `colorScheme()` 映射 | 同左 |

**跨半项**：对话框合并（`PiSettingsDialog` 是 AlertDialog、扩展对话框是自绘 Dialog，底色 surf-high vs surf-low 不一致）→ **乙独立完成**：在 `ui/components/PiDialog.kt` 新建统一构件，`ui/settings/**` 与 `ui/extension/**` 的调用点都换成它。甲不许创建或编辑 `PiDialog.kt`。

## 2. 审查依据（三者不一致时以谁为准）

1. `design-demos/direction-b-v2.html` —— **冻结的设计稿，最终依据**（尺寸、层级、状态、交互）。
2. `design-demos/shots-v2/phone01..61.png` —— 逐台参照图（`06` §1 有编号对照：phone1–5 hero，6–18 对话流与状态，19–28 会话列表与树，29–32 工作区，33–51 设置，52–54 Snackbar，55–58 扩展对话框，59–61 Boot）。**代理能读图**，要真的去看。
3. `06-v2-construction-reference.md` —— 数值与构件表（**从 HTML 摘的，可能摘漏**；与 HTML 冲突时以 HTML 为准，并把冲突报给父代理）。
4. `01-design-spec.md`、`brand-spec.md`、`03-navigation-decision.md`、`04-direction-b-graft.md`、`07-construction-decisions.md`、`09-highlight-fidelity.md` —— 规格、品牌边界与已裁决项。
5. `02-real-content.md` —— 真实文案与状态。

## 3. 审查方法（必须照做）

1. **逐台过**：61 台参照图，每台先回答「这一屏/这个状态现在是哪个文件哪个函数渲染的」，再逐项比对：版面结构、层级、间距、字号、圆角、线宽、颜色来源、状态三重编码、交互可达性。
2. **偏差必须编号化**：`编号 · 位置（file:line）· 现状 · v2 期望 · 依据（图号 / HTML 行号 / 文档节）`。**禁止"感觉不太像"这种条目**——每条都要能指到证据。
3. **然后自己修**（这就是"自主修复"）。修复时要写清依据；改完重新自查一遍同一台，确认差异消失。
4. **两条硬红线**：
   - **pi 令牌的颜色一个都不许改**（v2 里颜色的来源就是 pi 主题；能改的只是"哪个令牌用在哪、什么时候用"）；
   - **用户可见文案与协议一字不许编**（v2 没画、pi 协议里没有的，报给父代理，不许脑补）。
5. **不许越界**：只动自己那一半；不许 gradle/编译、不许 git 写操作、不许开子代理（编译是 CI 的事，提交是父代理的事）。

## 4. 收尾项（原来攒着没派的"最后一批"，并入本次审查）

| 项 | 归属 | 说明 |
|---|---|---|
| 顶栏 48dp（现为 M3 默认 64） | 乙 | 全局外壳 |
| 扩展对话框与设置对话框合并成一个构件 | 乙 | 见 §1 跨半项 |
| input/editor 对话框自动聚焦并弹键盘 | 乙 | 与「发完收键盘」同一类焦点问题 |
| `numeric`（等宽数字）的消费点补齐 | 甲 | 令牌在 `PiTheme.kt`，用法在状态行/时间/计数 |
| 字距/字重对比、字号档位收敛（13/14 之争） | 甲 | 排印 |
| 删 3 处 `tonalElevation` + 唯一 `shadowElevation` | 各自半区 | v2 无阴影 |
| 死代码：`PiCommon.PiEffectiveBadge` 零引用 | 甲 | 删或说明为什么留 |
| `PiSettingsRegistry` 里 WorkbenchScreen 的旧注释 | 乙 | 终端已降级 |
| `PiV2Layout` 折进 `PiSpacing`；`PiSpacing.screen` 退役 | 甲 | 折完删旧名，别留两张表 |
| 键盘弹出时是否收起底栏 | 乙 | 先报结论（`targetSdk=28` 与 `isImeVisible` 的兼容坑），父代理拿给用户定 |

## 5. 交付与验收链

1. 甲/乙各自交「偏差清单 + 已修项 + 未修项及原因 + v2 自身有歧义处」。
2. 父代理逐条核，提交推 CI；**CI 绿**才拉 APK 进用户 Download。
3. **用户亲自安装查看**。
4. 用户截图 → 交 **v2 设计师代理**逐台对比出评审（它知道原意、能读图），只报编号化偏差，不改代码。
5. 评审出的问题回到甲/乙修，直到用户认可。

## 6. 从 B5 交上来的、必须由本次审查收口的具体项（别漏）

| 项 | 归属 | 具体要求 |
|---|---|---|
| **块间距 16 → 8** | 甲 | 唯一调用点是 `ChatScreen.kt:971-982`（`blockSpacing` 与 `contentPadding(vertical=…)`）。v2 的块间距是 8。 |
| **执行轨道的 run 收口** | 甲 | 现在每张卡自画上下接续段（`ToolRail.kt` 的 `RAIL_BRIDGE`，为了在 16/8 两种间距下都不断线），代价是首个节点上方 8dp 短头、末卡下方约 16dp 尾巴；v2 是 run 上下各缩进 16。正解：列表侧给每项传 `firstOfRun`/`lastOfRun`（`previous/next` 是否为 `ToolCall`/`ToolDiff`，约 6 行），然后 `RAIL_BRIDGE` 改为块间距。 |
| **删掉 `latestUsage` 占位参数** | 甲 | 状态行按 v2 重排后，pi 的 ↑输入 / W 缓存写 / CH 命中率不再出现在这一行（会话信息 sheet 仍在显示），该参数成了死参——顺手删。 |
| **百分比/费用显示精度按 v2** | 甲 | v2 写 `52%` / `$0.42`，现在保留 pi 的 `52.3%` / `$0.420`。设计稿是外观依据 ⇒ 按 v2 的字面；完整精度仍在会话信息 sheet 里可见。 |
| **diff 节点配色对齐 v2** | 甲 | 现在用 `StateTone.Muted`（muted@45% 环）以复用同一节点组件；v2 是 `borderMuted` 实线环 + `bodyOnTool` 字。若差异肉眼可见就对齐；若为复用而保留，写清理由。 |
| **工具卡顶栏主体排印** | 甲 | v2 是等宽 12 `text`，现在是 `bodyLarge`/`toolTitle`。属排印，与收尾项一起做。 |
| **空态的 π 字形不要外溢** | 乙 | `PiEmptyState` 默认 `markPi = true`，会让会话列表×2、设置搜索、会话树×3 也变成 π；那几处 v2 用的是各自的图标（例如「没有匹配的会话」是放大镜）。在这些调用点显式 `markPi = false`，保留 v2 的图标。 |
