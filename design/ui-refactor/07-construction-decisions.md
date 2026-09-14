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

## D11 · 项目现场的 git 区块不做
**裁决**：工作区（项目现场）不显示分支/未提交改动。
**理由**：pi 没有对应 RPC；工作区目录本身也不是 git 仓库，跑 `git status` 只会得到「not a git repository」，把它画成「没有改动」就是把失败说成事实。用户原话：「好，没有就不做了」。要真做，得先定义「哪个目录算仓库」，那是产品决策而不是 UI 施工。

## D12 · 剩余 UI 批次与复用安排
- **B4 会话列表覆盖层 + 对话页左上角入口** → 复用 B1/B3 那位（源码映射与分批设计都在它上下文里）。
- **B6c 包管理页视觉 + 设置旧样式残留** → 复用 B6 那位（`PiSettingsStyle` 是它写的，这页必须复用它）。
- **上色 1:1（含扩展审计）** → 复用审计报告作者。
- **卡死第二轮（新会话首条回复）+ 已落地的 markdown 修复** → 复用诊断作者。
- **B5 对话块渲染**（工具卡/执行轨道/块 chrome/状态行读数）→ 等上色批让出 `ui/blocks/DiffBlock.kt` 与 `ToolBodyText.kt` 之后，**复用上色那位**（它那时已深度读过这批文件），不新开人。
- **B8 状态面与浮层**（Boot 三态 / 扩展对话框四型 / Snackbar 三档 / 空态错误态的形态）→ 计划里没有单独成批，需要补一批。
- **B7 排印与收尾**（`numeric` 消费点、字距字重、删 3 处 tonalElevation 与唯一 shadowElevation、顶栏 48dp、死代码 `PiEffectiveBadge`、`PiSettingsRegistry` 里 WorkbenchScreen 旧注释、`PiSpacing.screen` 退役、`PiV2Layout` 折进 `PiSpacing`）→ 最后一批。
- **验收**：v2 设计师那位做 reviewer（它能读图），拿实机截图与 `design-demos/shots-v2/phoneNN.png` 逐台比对，只报编号化偏差、不改代码。

## D13 · 推送策略（本机网络与 GitHub 的兼容）
**现象**：`git push` 连续十几次失败（TLS 被掐 / 连接重置），但同一时刻 `curl https://github.com` 是 200。
**处置**：`git config http.version HTTP/1.1` + `http.postBuffer` 调大，并且**一次只推一个提交**（`git push origin <sha>:refs/heads/main` 逐个推）。三条积压提交一次成功。
**结论**：这台手机的网络在 HTTP/2 大包上传上不稳；以后推送失败先试这两招，不要反复重试同一个命令。

## D14 · Kotlin 注释里的 `/**` 会吞掉整个文件（一次真实事故与教训）
**事故**：B8 在 `ExtensionDialogs.kt` 的 KDoc 里写了 `` `ui/theme/**` ``。Kotlin 的块注释**可嵌套**，于是 `/**` 又开了一层注释，把该文件**后面的所有代码**（包括刚定义的 `DialogMaxWidth` 等常量）都吞进注释里 ⇒ CI 报 10 处 `Unresolved reference`，同时注释守卫报 7 处 `nested block comment`。
**修复**：只改那一行——KDoc 里写 `` `ui/theme/` `` 而不是 `` `ui/theme/**` ``。另有 6 处是级联假阳性（被吞掉的单行 KDoc 又被当成新的嵌套开头），改掉根因后守卫转 OK。
**教训（给以后的人）**：**不要**写脚本全局替换 `/**`——**字符串字面量里也有 `/**`**（例如 `add(".git/**")`、测试里的标记串、提示文案 `` (ui/terminal/**) ``），批量替换会把它们改坏。我第一次就是这么干的，一次改了 7 个文件，把 `.git/**` 和一处测试断言改成了 `.git`，已全部 revert 后只改真正的那一行。定位这类问题用 `python3 tools/check-nested-comments.py`（它按语法位置判断，不误伤字符串）。

## D15 · 状态行的「数值与分隔」照 pi，不照 v2 的样本字面
**现象**：用户实机截图（终审前的旧版）指着状态行说「计费那一栏有问题」。核出 5 条，其中**两条是终审时改错的**。

**裁决**（逐条）：

| 项 | 现为 | 改为 | 依据 |
|---|---|---|---|
| `%` | 缺（`上下文 1.3` 直接顶进度段） | `上下文 1.3%` | pi `footer.ts:111`；v2 `:794` 也是 `上下文 {p.pct}%` |
| 百分比精度 | `%.0f`（终审由一位改零位） | `%.1f` | pi `toFixed(1)`（`footer.ts:111`） |
| 费用精度 | `%.2f`（终审由三位改两位） | `%.3f` | pi `toFixed(3)`（`footer.ts:143`） |
| ` (auto)` | 挂在百分比后 | 挂在**窗口**后（`1.3%/1.0M (auto)`） | pi `${percent}%/${window}${auto}`（`footer.ts:150-156`） |
| 读数分隔 | 插 ` · ` | 只留间隙（无 `·`） | v2 `StateLine` 实现（`:798-803`）+ 裁定清单 B-1 |

**理由（为什么零位小数那条推理是错的，写下来防再犯）**：终审时我读 v2 的 hero 注释 `上下文 52% [8 段进度] 104k / 200k` 与 `StateLine` 的 `{p.pct}%`，判断「v2 用整数」，于是把 pi 的 `toFixed(1)` 改成 `%.0f`。**这是把「板子的样本取值」当成了「板子的格式规定」**：v2 的 `StateLine` 原样打印传入值、自己不做任何舍入，52 只是它样本数据恰好是整数；数值来源是 pi，所以格式也该是 pi 的。同理费用：v2 的 `$0.42` 只是样本，pi 是三位，而两位小数会把 `$0.006` 显示成 `$0.01`、`$0.004` 显示成 `$0.00`——金额读数不是装饰。
**教训（给以后的人）**：v2 板子上出现的**任何具体数值**都可能是样本数据，不是格式规定。判定格式要看板子的**代码**（`{p.x}` 是怎么被拼进去的），必要时回 pi 的源码对 `toFixed`/模板串。

**影响**：`ui/components/PiCommon.kt` 的 `PiStatusLine`（含 KDoc 里两处自相矛盾的说明：一处说 v2 是两位、一处说「pi 的三位」而代码是两位）。

## D16 · diff 卡的解析对象是 pi 的「给人看」diff，不是 unified diff
**现象**：用户实机截图（旧版）里 `edit` 卡的头部是 `+0 −0`、第二行 `新增 0 行 · 删除 0 行`，而正文明明有 4 行 `-`、1 行 `+`；且**正文一点颜色都没有**。

**根因（已核实，不是配色问题）**：pi 的 `edit` 工具 details 是 `{ diff, patch, firstChangedLine }`（`core/tools/edit.ts:210`）。`diff` 由 `generateDiffString` 产出（`core/tools/edit-diff.ts:376-480`），是**面向人的显示串**：`+123 text` / `-123 text` / ` 123 text`，长上下文折叠成 ` <pad> ...`，**没有 `@@`**；真 unified diff 在 `patch` 里。而 app 的 `detailsDiffText` 优先取 `diff`，`buildToolDiff` 又用只认 `@@` 的 `parseUnifiedDiff` ⇒ **0 个 hunk** ⇒ `added/removed` 都落到 0（头部 `+0 −0`），且 `DiffBlock` 走「解析失败」分支把原始串整段单色打印。

**裁决**：按 pi `modes/interactive/components/diff.ts` 的语义解析并渲染 `diff`（前缀定 kind、相邻的单删单增做逐词反白、上下文行用 `toolDiffContext`）；`patch` 只用来**兜底取路径**（`edit` 的 details 不带 path，所以标题恒为「未命名文件」）与兼容只给 unified diff 的工具/扩展。**颜色仍取 pi 的整行上色**（`diff.ts:127-152`），不改成 v2 那种「只给符号列上色、正文用 text」——用户红线是上色 1:1。pi 的 `...` 省略行按 context 渲染、数字列留空：**pi 的省略行不带「省了多少行」，不为凑 v2 的「… N 行未变」去算一个数**。

**影响**：`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt`、`ui/blocks/DiffBlock.kt`。

## D17 · `展开/收起` 的箭头方向：折叠→右，展开→下
**现象**：用户截图里折叠态的思考行画的是 `展开 ⌄`（向下箭头）。
**裁决**：统一为 v2 的 `Icon n={expanded?'down':'right'}`（`direction-b-v2.html:911`）。`ui/blocks/BlockChrome.kt` 的 `ExpandLabel` 原来写成 `expanded→Up / collapsed→Down`，方向反了；`ToolBlockChrome.kt:224` 与 `DiffBlock.kt:141` 本来就是对的。
**影响**：`ExpandLabel` 的全部调用点（思考块、错误详情、技能调用、hook 消息、分支摘要）。

## D18 · 用户实机反馈（旧版 APK）的处置归档
用户装的是**终审前**的 APK，报「计费栏有问题 / diff 卡有问题 / 输入框 / 底下乱七八糟 / 还有很多问题」。逐条核到当前代码后分三类，记下来免得下次重复排查：

- **真错，已在 D15/D16/D17 修**：状态行 5 项、diff 全链路、`ExpandLabel` 箭头。
- **旧版才有、当前代码已对**：输入区（composer 已是 v2 的单一 14 圆角框、芯片行在框内、`◐`+等级名、30×30 accent 圆盘、边框取思考等级色/bashMode）、顶栏「选择模型」胶囊（旧版带 ⓘ 图标与填充底）、三个空态的 68dp 圆底。
- **不是 bug（v2 原样）**：底部本来就是三层（输入框 + 键位行 + 底栏）；「回到最新」浮层压住正文是 v2 的画法（`:1515`）；`回到最新` 后面没有 `· N` 是 `unseenRows == 0` 时的正常形态。
- **待用户确认**：状态行下面那一行「深色小方块 + accent 的 `6`」，形状符合扩展状态行（`ui/extension/ExtensionChrome.kt:54` 把扩展 `ctx.ui.setStatus()` 的文本逐字渲染成 mono + accent），而 pi 自带示例扩展 `plan-mode` 正好报 ``📋 完成数/总数``——若用户确实装了该扩展则不是 bug。
