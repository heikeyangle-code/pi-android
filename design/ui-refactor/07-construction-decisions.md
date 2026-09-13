# 07 · 施工期裁决记录

> 施工过程中父代理做的裁决，按时间顺序。作用：多个代理并行时，谁都不用猜「这个冲突当时怎么定的」；
> 后来的人也能看到为什么某个数字是这样。每次都写「裁决 + 理由 + 影响谁」。

## D8 · 等宽字体随包打包
**裁决**：打包 JetBrains Mono 2.304（Regular + Bold，546KB），替换 App 的机器语言层字体。
**理由**：机器语言层是主要内容；系统等宽字换机变样，而 `DiffBlock` 的行号列（30dp）/符号列（16dp）是按字宽凑的。
**影响**：`PiMonoFamily`（`PiTheme.kt`），`mono`/`monoSmall` 两个角色；终端页保留系统等宽（划出范围）。

## D7 · 撤销「一屏只有一个 accent」
**裁决**：不做这条全局预算（用户：「不要做 C 的那个一屏幕一个什么了，那个不好维护」）。
**影响**：`04-direction-b-graft.md` §2、`direction-approved.md`、`05` 计划里四处派生要求。

## D6 · 终端只留设置里一行入口
**裁决**：终端不再投入；输入区 chip 与溢出菜单入口去掉，唯一入口在设置首页；实现一律不动。

## D5 · 底栏是自绘 56dp 条，不是 M3 NavigationBar
**裁决**：底栏换成 v2 的 `TabBar`：高 56、底色 `surfaceDim`、上边 1px、图标 20、标签 12、选中 accent + 500 字重 + 底部 `18×1` accent 线（距底 6dp）、**无** indicator 胶囊；三个图标用 v2 的三条 18×18 描边字形（不是 Material 图标）。
**理由**：底栏每屏都在，M3 默认的 80dp + 胶囊与稿子差得肉眼可见。
**影响**：`PiRoot.kt` + 新增 `ui/components/PiNavGlyph.kt`；Snackbar 底部兜底 88 → 64。

## D4 · `NavRequest.Workbench` 暂不改名
**裁决**：等 B3 删掉它唯一的消费者（composer 的终端 chip）之后再改名。
**理由**：现在改就是加一个零消费者成员（`PiSpacing` 那 13 个死 token 就是这么来的）。

## D3 · `NavRequest.SessionList` 暂时仍切目的地
**裁决**：保持「切到对话 + 开覆盖层」，直到 B4 把入口接到对话页左上角。
**理由**：B4 之前若只开覆盖层，用户点 `/resume` 会停在工作区看一个浮层——拿体验换计划字面不值。

## D2 · 「被拒」用 `palette.bodyOnTool`，不新增颜色
**裁决**：`StateTone.Rejected` 映射到已有的 `PiPalette.bodyOnTool`（工具卡正文色，已按 4.5:1 对三种卡底做过对比度抬升），**不**进 `PiPalette` 新增令牌、也不用 `muted`。
**理由**：v2 给「被拒」的是中性灰 `#9E9E9E`，`bodyOnTool` 是它在本 App 里语义最接近的既有派生色；「被拒」不是失败，不该用 error 色，也不该暗到与禁用态混淆。
**影响**：`PiStateChip.kt` 一行。

## D1 · 页水平内边距以 v2 的 14 为准
**裁决**：`PiV2Layout.pageHorizontal = 14.dp` 是唯一事实源；`PiSpacing.screen = 16.dp` 暂留但**新代码不许再用它做页水平内边距**，等 B7 把残留消费者迁完后删除。
**理由**：v2 全篇 14；但 `screen` 现在还被当成"块间距"等用途混用（转录列表 `spacedBy(PiSpacing.screen)` = 16，而 v2 的块间距是 8），不能粗暴改值，只能逐批迁移。
**影响**：B5（转录与块）、B6（设置）改用 `PiV2Layout`；B7 收尾。

## D9 · 设置搜索命中行用 pi 的 `selectedBg` 做填充
**裁决**：命中行 = 1px accent 左线 + `palette.selectedBg` 填充（不是 `surfaceContainerLow`——那和卡底同色，等于没有填充）。
**理由**：`selectedBg` 是 pi 自己的选中令牌（v2 的 `--selected-bg` 就是它），不是新颜色；原实现只靠一条 1px 线，在卡内几乎看不见。
**已知的可读性代价（接受）**：深色副行 2.8:1、浅色 accent 线 2.85:1，都略低于 3:1。不改——这两个组合就是 v2 与 pi 令牌本身的取值（用户明确不许动 pi 取色），而且同一行还有正文色标题（深色 7.5:1 / 浅色 10.4:1）承担信息。

## D10 · 「当前生效值」2px 条按「被显式写过」推导，不限制每组一条
**裁决**：Value 行的值若在 store 里被显式写过，就带 2px accent 条；一组里可能有多条，不压成一条。
**理由**：每一条都确实在生效，为了视觉压掉几条等于撒谎；也不给宿主加纯视觉参数 `currentKeys`。
