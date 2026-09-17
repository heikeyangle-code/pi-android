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
- **已查清并答复用户**：状态行下面那一行「深色小方块 + accent 的 `6`」**就是** `ExtensionStatusRow`（`ui/extension/ExtensionChrome.kt:54-87`，把扩展 `ctx.ui.setStatus()` 的文本逐字渲染成 mono + accent）；那个方块是**真彩 emoji**（系统 emoji 字体渲染——emoji 保留自身配色，而同一行的数字被染成 accent；若是缺字 tofu 两者会同色）；随包只有 3 个扩展（`PiPackageModel.kt:113-120`：设备桥 / 权限门 / 代码高亮），`plan-mode` 不在其中，而 pi 自带示例扩展 `plan-mode` 正好报 ``📋 完成数/总数`` ⇒ **是用户自己装的扩展，不是 app 的 bug**。

## D19 · 删掉输入区的「编辑器」芯片（外部编辑器在 Android 上没有可交付物）
**裁决**：删掉 `编辑器` 芯片及其全部实现，删干净（launcher、handler、参数、提示文案、只讲它的注释）。
**理由**：pi 的能力是 `app.editor.external`（`keybindings.md:129` ctrl+g → `interactive-mode.ts:4246-4261` → `external-editor.ts:14-52`）：把草稿写进临时文件、**启动命令行编辑器**（`$EDITOR`/nano/vim）、退出码 0 才读回。Android 上没有可启动的命令行，app 只能映射成 `ACTION_EDIT` 交接；而用户机上没有任何应用接它，这个按钮唯一可能的结果是一句「没有应用能编辑文本」。用户裁决：「删了编辑器，删干净。」属于「pi 有、平台交付不了」那一类，不是「pi 没有所以不做」。
**影响**：`ui/screens/ChatScreen.kt`（`:352-400`、`:1519`、`:2327-2328`、`:2411`、以及 `:2538` 芯片清单注释）。`06 §2` 原话列了六个芯片，注释里必须写明删的是哪个、为什么，别让后来的人当成漏画。

## D20 · 曲别针 = 附件入口；图片走字节通道，其它文件**只插路径、不复制**
**裁决**：输入区的 `图片` 芯片改成**曲别针**，选择器 mime `*/*`：
- 选到**图片** → 走既有 `ImageContent` 附件通道（pi 协议里唯一合法的字节通道）；
- 选到**其它文件** → 把它在**手机上的真实路径**（`/storage/emulated/0/…`）以文本插入输入框，**不复制**、不改写；拿不到真实路径的来源（云盘等 provider）什么都不插，并给一句诚实提示。

**理由（关键事实）**：`runtime/PiRuntime.kt:165-178` 的 `ProotCommand.baseBinds` 是**每次启动都带**的绑定，其中有 `-b <共享存储>:/sdcard` 与 `-b <共享存储>:/storage/emulated/0`——**手机的共享存储在 guest 里同路径可见**，pi 的 agent 用 `read` 就能读到那个路径。用户先选过「复制进工作区」，随后改成「发送文件路径就行，别复制了」，而这个绑定事实说明**他改得对**：复制是多余的，还平白在工作区留下副本。
**为什么不能送字节**：pi 的对话内容类型只有 `TextContent | ImageContent`（`packages/ai/src/types.ts:351-372`、`:456`），`@file` 只是 CLI 参数（`cli/file-processor.ts:24`），RPC 明确不支持任意文件字节（`main.ts:641`）。所以「发任何东西」在 pi 协议下的正确形态就是：图片给字节、其它给路径。
**影响**：`ui/screens/ChatScreen.kt`（选择器 mime、URI→真实路径解析、插入形态）。插入用**绝对路径**（与 pi 终端里 `drop files to attach` 插入绝对路径一致），**不要**混用 `@` mention 的相对路径风格。

## D21 · 插话与排队改走 `prompt` + `streamingBehavior`（不再发裸命令）
**现象**：用户问「没有给我做插话功能吗？官方是有插话的吗？」——插话**有**（流式中直接发送），但他这一问让我把链路核了一遍，发现 app 与 pi 的实现不同。
**裁决**：流式中的普通发送与「后续」排队都改成 pi 的形式：`session.prompt(text, { streamingBehavior: "steer" | "followUp" })`。
**理由**：pi 的 TUI 两条路都走这个入口（`interactive-mode.ts:3137-3143` 的注释写明「This handles extension commands (execute immediately), prompt template expansion, and queueing」；followUp 在 `:4143-4150`），RPC 的 `prompt` **接受** `streamingBehavior` 并原样转发（`rpc-mode.ts:395-408`）；而裸 `steer` / `follow_up` 命令直连内部方法（`:418-426`），**绕过 `_processInput`** ⇒ 扩展 `emitInput`、模板/技能展开、压缩期排队都不会发生。pi 在**压缩期间**另有一条岔路（`:3125-3131` steer / `:4130-4136` followUp）会先排队而不是直接发。
**影响**：`ui/PiSessionViewModel.kt`（流式发送分支、follow-up 分支、以及那条手工 `echoUserPrompt`——改成 `prompt` 后用户行由 `prompt` 自己发布，必须确认只渲染一次）、`engine/PiEngineSession.kt`、`rpc/.../Commands.kt`（`prompt` 可能需要补 `streamingBehavior` 字段）。

**落地结果（施工代理复核后的两条更正）**：
1. **wire 不用改**：`Commands.kt:46-57` 的 `prompt` 早就带 `streamingBehavior`（枚举拼写 `steer`/`followUp`，`:16-22`），`PiEngineSession.prompt` 早已透传（`:758-765`）；`send`/`sendFollowUp` 改成一次 `engine.prompt(..., if (streaming) Steer/FollowUp else null)`。裸 `steer`/`follow_up` 的**生产调用点因此归零**；两个 builder 保留（`PiCommands` 是 pi 33 条命令面的转录，`CommandsTest.kt` 钉它们的 wire 形状），各补一段 KDoc 说明「app 已不再发送、为什么」。
2. **手工 echo 删对了，而且理由比「会不会渲染两遍」更严重**：`prompt` 的 echo 会**登记**一条 pending echo（`Transcript.kt:883-897`），pi 随后那条 `message_end(role="user")` 命中它即为确认（`:919-1010`）；若再手工 echo 一次，除了屏幕上两行气泡，还会留下一条**永远不会被消费的陈旧 echo**——它按文本匹配，会在**下一条无关消息**的 `message_end` 到来时被错误地当成确认吞掉。所以两处手工 `echoUserPrompt` 已删，全仓只剩 `PiEngineSession.kt:763` 一个调用点。

## D22 · 压缩期间「先排队再发」暂不做（记录成已知缺口，不是漏做）
**事实（施工代理逐行核过）**：`UiState.meta` **没有** `isCompacting` 字段（`EngineMeta` 的字段里没有它；`PiResponses.kt:91/:276` 解析出来的那个只被引擎收尾路径 `PiEngineSession.kt:959` 读，用来决定要不要先 abort）；`compaction_start`/`compaction_end` **只被转录 reducer 消费**（画压缩卡），`PiSessionViewModel.onEvent` 没有这两个臂。所以 app 里现在**没有任何「正在压缩」的事实可用**，`send` 只按 `engine.transcript.streaming` 决策 ⇒ 压缩期是**直接发**，与 pi 的 TUI（`session.isCompacting` 为真时改走 `queueCompactionMessage`，`interactive-mode.ts:3125-3131` steer / `:4130-4136` followUp）**不等价**。
**为什么这轮不做**（三件必须一起做，其中两件的前置不在本轮范围）：
- RPC **既不能写也观察不到**压缩队列：命令全集里没有 `queue_compaction_message`，`queue_update` 只带 `{steering, followUp}`（`Events.kt:508-509`）；所以只能 app 本地暂存 + 压缩结束后自己 flush。
- flush 只能挂在 `compaction_end` 上，而事件通道有**已记录的丢事件缺陷**（`hang-and-crash-review.md` B6：`PiEngineSession.kt:178` 容量 256、`:507` `tryEmit` 丢弃）。漏一个 `compaction_end` = 用户那条消息**永远发不出去**，而气泡早已 echo 在屏幕上——比现状更糟。
- 「排队显示在哪」那一半在 `ChatScreen.kt` 的队列行（`⇢ 穿插 N` / `⇣ 排队 N`）；本地暂存若折进这两个计数，等于让芯片对「pi 收到了几条」说假话。
**最小安全路径（留给以后）**：① `UiState.compacting`（两个事件臂，事件其实已经送到 VM，只是没人接）；② 给 `compaction_start/end` 做不丢的通道；③ `send`/`sendFollowUp` 在压缩期本地暂存 + `compaction_end` 后按原 delivery choice flush + 折进队列 chip。

**已核实的 pi 侧事实（父代理在 `/root/pi-src` 读到，补上原文「无法核实」那条）**：`session.prompt()` 在压缩进行中**直接抛错**——`if (this._compactionAbortController !== undefined) throw new Error("Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry.")`（`core/agent-session.ts:1192-1196`，位于扩展命令之后、流式判断之前）。而裸命令那条路**没有这个检查**：`session.steer()`/`followUp()` → `_queueUserInput`（`:1388-1413`，只做扩展命令拦截 + `_runInputHandlers` + 技能/模板展开）→ 压进队列，**不抛错**。pi 的 TUI 在压缩期是**本地暂存**：`queueCompactionMessage`（`interactive-mode.ts:4408-4414`）+ `flushCompactionQueue`（`:4426+`）。
**由此暴露的回归（D22 的修正案）**：D21 把发送统一改走 `prompt` 之后，**压缩期间发消息会收到 pi 的英文报错，且消息没有被排队**（改之前裸 `steer` 会排队）。修法见 D23。

## D23 · 压缩期改走裸队列命令（补 D21 引入的窄窗口回归）
**裁决**：`UiState` 增加 `compacting`（`compaction_start`/`compaction_end` 两个事件臂，并在 `refreshState()` 里用已解析的 `get_state.isCompacting` **对账**，以免那条已知的丢事件缺陷把标志永久卡在 true）；发送时**压缩期为真就走裸 `steer`/`follow_up`**，否则维持 `prompt` + `streamingBehavior`。
**理由**：pi 的 `prompt` 在压缩期必抛（上一条事实），而队列命令不抛且排进 pi 自己的队列 ⇒ 消息在界面的队列行上**本来就看得见**（`queue_update`），因此**不需要本地暂存**（也就不需要新 UI），更不会踩「漏一个 `compaction_end` 就永远发不出去」那个坑（`hang-and-crash-review.md` B6）。代价：压缩期这一条不经过 `prompt` 的压缩检查/预检——而那正是要绕开的。最坏情况：若标志 stale-true，消息只会进 pi 的 steering 队列并在下一个 agent step 交付，**不是失败**。
**顺带更正 D21 的一处措辞**：裸 `steer`/`follow_up` **并非**绕过整个 `_processInput`——`_queueUserInput` 会跑 `_runInputHandlers` 与技能/模板展开，它跳过的只是压缩检查、预检与 model 校验。

## D24 · 命令面板从 23 条砍到 11 条（用户裁决「没用的全删了」）
**删除 12 条，三组理由**：A 本平台无法交付 4 条（`share` 要 gh CLI 建 gist、`changelog`/`hotkeys` 是 TUI 渲染面、`quit` 无「退出 pi」语义）；B 同屏已有更直接入口 3 条（`settings`→底栏「设置」、`model`→顶栏模型按钮、`thinking`→输入框 `◐`）；C 目的地是设置行 5 条（`scoped-models` `trust` `login` `logout` `reload`）——用户原话「点一下需要跳转到设置导航的，全都删掉」。
**保留 11 条**：`tree export import copy name session fork clone new compact resume`，逐条走过执行路径（描述与行为一致、空态都有话说），且**面板里不再有「点了也完不成」的命令**（原 `/model`、`/thinking` 是需要参数却没候选的两条，随删除消失）。
**连带删除**：`PiCommandAction` 的 5 个取值（`OpenSettings`/`PickModel`/`PickThinking`/`OpenModelScope`/`TerminalOnly`）、`PiSlashCommand.appLanding` 字段、`ComposerRoute.Unreachable`、`notifyTerminalOnly()`、`cycleModel()`（入口删除后零调用者；RPC 本体与测试保留）。**顺带修掉一条错的旧指路**：`/trust` 原来指「设置 → 扩展 → 扩展包与项目信任」，实际在「安全与信任 → 信任」。
**顺序与措辞**：分组顺序改成 pi 的 TUI 顺序（内置 → 模板 → 扩展 → 技能，`interactive-mode.ts:727`；wire 的顺序不同，见 `rpc-mode.ts:684-708`）；`tree` 改回 pi 直译（去掉 app 自加的「从某条消息分叉」）；`copy` 改「复制最后一条回复到剪贴板」。
**scope 徽章**：保留 pi 的派生（`interactive-mode.ts:586-606` 的 `u/p/t` + npm/git），**渲染按用户裁决转汉字**（用户 / 项目 / 临时），npm/git 余部原样保留。
**新增**：`PI_UNLISTED_BUILTIN_COMMANDS` + `unlistedBuiltinHint(name)`（12 条）——手工键入这些名字时不再说「去掉 `/` 当正文发」（那是把 pi 真有的命令教成错误用法），而是陈述事实并指向本应用自己的入口。

## D25 · `streaming` 的语义回到 pi（「正在跑一轮」）
**现象**：用户在干活时看到发送键**一会三角一会方块**；点三角时发送**撞上** pi 的 `Agent is already processing. Specify streamingBehavior…`（`agent-session.ts:1219-1223`）。
**根因**：`finishStreaming()` 在 `message_end`（每条助手消息）就清 `streaming`，而一轮里带工具调用时 `message_end` 与下一条 `message_start` 交替 ⇒ 标志一轮内翻好几次。
**裁决**：只有**轮次级**事件清标志——`agent_end` / `agent_settled` / 引擎退出；`message_end` 继续走收尾刷新但用 `finishStreaming(endsTurn = false)`。行级「此刻正在吐字」由 `item.streaming` 独立承担（它仍由 `message_end` 清），两类消费者各拿各的信号。
**连带修好的三处（施工代理逐条复核）**：① `interrupted` 判定（旧语义下工具运行中会被误判成「回合已结束、工具未回」并闪烁）；② CPU 唤醒锁（`turnRunning` 里 `streaming` 为假时可能在命令跑到一半放锁）；③ 顶栏引擎标签（工具一跑就掉回「就绪」）。
**同时**：流式发送键恒为「停止」，投递方式交给两个只在干活时出现的芯片——`插话`（steer，现在插进本轮）与`排队`（followUp，本轮结束后），两者都走 `prompt` + `streamingBehavior`。

## D26 · 不让界面说谎（本轮从审计里收口的三处）
1. **`call()` 在引擎未附着时不再静默返回**：新增 `NoEngine { Notify, Quiet }`；动作类与「用户刚打开的读」（`/session`、`/fork`、`/tree` 那些会永久停在「正在读取…」或显示「没有可分叉的消息」的地方）走 `Notify`（`fail("引擎未就绪…")`，并对同一句话去重，避免引擎死时同帧叠四条）；只有 `refreshState` 这种内部重读是 `Quiet`。
2. **`/compact` 的两条英文原因映射成中文**（`Nothing to compact…` / `Already compacted…`，`agent-session.ts:1985-1992`），其余 reason 原文透出（保留 pi 原文便于搜索）。
3. **已知缺口（记录，未做）**：`forkMessages` 是裸 List，分不出「还没加载」与「加载完是空的」，所以 sheet 的空态文案在引擎 detach 的窄窗口里仍可能不准（snackbar 已说真话）。最小修法：`UiState` 加 `forkMessagesLoaded`（及 `statsLoaded`/`treeLoaded`），甲在空态分两支。

## D27 · 读数行从顶栏拿掉，折进输入框键位行的一枚上下文圆环
**用户原话**：「abc 做成这个吧，**不要在顶栏了**，那还占显示区域。在输入框下面、之前那些别的功能后面，加一个这种**圆形的按钮**。**按钮本身就能显示占用的上下文进度**。点开这个按钮会出现详细信息。」
**裁决**：顶栏**读数行整条删除**（`PiStatusLine` 的调用撤掉）；键位行末尾加一枚 **24dp 细弧环**（环宽依设计稿；不放数字；触控 32dp；占位 30dp），弧长 = `contextUsage.percent`；颜色沿用 pi 的阈值（≤70 `accent`、>70 `warning`、>90 `error`，`footer.ts:154-156`）；`percent` 为 null（压缩后到下次回复前，`agent-session.ts:3443`）画**空轨道 + 中央 `?`**（`?` 是 pi 自己的拼法，`footer.ts:110`）。点击打开**新的**详情 sheet（见 D29）。
**理由**：读数行是**常驻横带**，手机顶部最贵；而它的九个数字都是「点开才需要看」的。环本身承担「还能不能继续聊」这一个常驻问题。
**设计来源**：`design-demos/context-gauge-options.html`（变体 A 定稿；B/C 仅作对照）。**可用宽是 310dp 不是 332dp**——它在输入框容器里（360 − 2×14 − 2×10 − 2×1）。
**连带修掉的既有 bug**：那一行在流式态（`插话`/`排队` 出现时）**本来就会溢出 37/81dp**（360dp 屏），最坏把发送键挤出可见区。退让阶梯定为**三步**（R1 尾距 6→4 → `清空` 进菜单 → `排队` 进菜单），走完最坏态 293dp ✓ 余 17dp，**保留 `◐ 中`**。

## D28 · 附件芯片改纯图标（无汉字）
**用户原话**：「附件本身图标就行，不要有汉字。」
**裁决**：`KeyChip(label = "附件", icon = …)` 改成**只画纸夹**（label 为空时不画文本、也不留那个 4dp 间隔）；**无障碍标签必须另给**（不能跟着变空——`clickable(onClickLabel=…)` 原来用的就是 label）。实测省 **26dp**，正好抵消上面那部分溢出。
**同时**：`draft` 与待发附件改为**可保存**（见 D30），浮层（sheet / 溢出菜单）**保持裸 `remember`**——切回来重新打开更符合预期，这条取舍写进注释。

## D29 · 扩展状态行**彻底不显示**；所有数字只在环的详情里出现一次
**用户原话**：「扩展状态那个东西…那个还占显示区域」「彻底不显示」。
**裁决**：`ExtensionStatusRow` 构件与其上色接线删除（上一批已做）；**数据保留**（`UiState.extensionStatuses` / `setExtensionStatus` / `"setStatus"` 路由），落点改为「会话与队列」sheet 末尾的**「扩展状态（N）」**一节（键名等宽灰 + 状态全文用扩展自己的颜色、可换行不限行；0 条时整节不渲染）。
**为什么**：pi 的那一行是**终端 footer** 的产物——`footer.ts:232-241` 把所有扩展状态**按 key 字母序**、**用一个空格拼成一行**，靠旁边的 cwd/模型/用量提供语境。搬到手机顶部单独一行就成了一个孤零零的 emoji + 数字。且 pi 核心**一个状态都不写**（整个 src 只有声明与转发），它 100% 是扩展通道。
**详情页同时裁决**：**不复用「会话信息」**，新做一张（用户：「不要复用会话信息那个，直接重绘一个」）；「会话信息」里那些上下文/用量信息**删干净**（用户：「那个地方太丑了」），它只留会话元数据（名称/ID/文件/消息统计/工具调用统计）。

## D30 · 命中率加「本会话累计」；切目的地保住每屏状态
1. **命中率**：用户问「缓存命中率不能算本聊天总共的吗？」——能。**新增「命中率（本会话累计）」** = `Σ缓存读 ÷ (Σ输入 + Σ缓存读 + Σ缓存写) × 100`（一位小数，四个数都是服务商回报的真值，只做四则运算），并在注释里标明它是**派生量**；pi 原生那条保留、标签写全为「命中率（**最后一条回复**）」——两条标签必须不同，用户上次把累计读成本轮就是因为两个数长得像又没标签。**「本轮命中率」不算**（再派生一个会让同屏出现三个近义百分比）。
2. **切目的地保住每屏状态**：用户「切走之前是什么样，切回来什么样就可以了」。根因是 `PiRoot` 用普通 `when` 切目的地、**没有 `SaveableStateHolder`**，离开组合即丢掉整屏状态（滚动位置/跟随/窗口/搜索词）。修法是加 `rememberSaveableStateHolder()` + 每目的地一层 `SaveableStateProvider(key)`（key 用枚举常量名，闭集、无需回收）。**否决了「进入即贴底」的兜底**：那是给症状打补丁，且要碰本项目出过事故的「观测布局 → 请求滚动」那条线（D-事故见 `docs/streaming-review.md`）；首次进入/新会话交给既有跟随逻辑（数据变化触发）。
3. **「本轮用量」加上了**（用户：「本轮也加上吧，加的要是准确的数据」）：口径 = 从最近一次轮次开始、**模型每一次回复**的用量**之和**（不是取最后一条），**不累加流式增量**（`message_update.usage` 在一条消息内是累计值，重复计会翻倍），**不含**压缩/摘要那次调用；replay 时从 entries 重算（最后一条 user 之后的所有助手条目）。

## D31 · 删掉整屏 Boot 接管与空会话里的引擎文字
**用户原话**：「现在我每次进软件，还有一个什么**运行时正在启动**什么东西，一两秒就会没有，**把那个页给我删了**。还有聊天界面那个什么，**引擎启动什么玩意，那些文字**。」
**裁决**：
1. **整屏 Boot 接管只在「真的有解包工作」时出现**（用户补充：「但第一次安装软件，那个进度条肯定也得留着呀」）：`RuntimeProvisioner.ensureReady` 的快速路径（stamp 命中）**一个 step 都不上报**，所以每次打开那一秒其实是 **`Boot.Idle` 初始态**在自检+spawn 期间顶着 => **`Boot.Idle` 不再接管**（直接显示正常对话页 + 底栏 + 输入框），**只有 `Boot.Working(step)` 才整屏**（第一次安装 / 换引擎版本的解包，带步骤与进度条）。**`Boot.Failed` 照旧整屏**（错误态 + 重试 + 诊断，不是等待）。**底栏在任何情况下都不再隐藏**（解包几分钟时用户要能去设置/工作区）。`ui/screens/BootScreen.kt` **保留**（第一次安装要用）。
2. **删掉空会话里的引擎文字**（`ChatScreen.kt` 的 `PiEmptyState(title = "引擎正在启动"/"引擎已就绪", body = …)` 整块）：**空会话就是空白 + 底栏 + 输入框**，不写替代文案。
3. **保留**顶栏那行一个词的引擎状态（`就绪`/`工作中`）——它不是一段说明文字，用户未点名。
**前提（动手前须核）**：引擎未就绪时发消息**本来就会排队**、等引擎起来自动处理（`PiSessionViewModel` 的 `engineStarting` 逻辑），所以删掉接管页之后用户仍可立刻打字并得到回应。若这条不成立，则不删——先报。
**核查结果与落实（前提只成立一半，已补齐）**：
- **成立的一半**：`Boot.Ready` 之后、引擎还在 `Starting` 的那一段，`session` 已非空，消息经 `prompt` 进 pi 自己的队列（pi 启动完成前不读 stdin），气泡先出现、答复随后到。
- **不成立的一半**：**`Boot.Idle` 那一两秒里 `session` 还是 `null`**（`session = engine` 与 `boot = Boot.Ready` 是 `attach()` 里的相邻两行，`Boot.Idle` 是构造默认值），而 `send()` 的第一行是 `val engine = session ?: return` —— **消息会被静默丢弃**，没有回显也没有错误。按用户「弄成正常的样子」的裁决，删页不能以静默丢输入为代价。
- **落地**：`PiSessionViewModel` 加 `pendingPrompts` + `parkUntilAttached()`，把 `send` / `sendFollowUp` / `runPromptCommand` 三条**用户能在没有引擎时触达**的路径在 `session == null` 时**记住**（闭包重入原函数，压缩窗口/流式行为/乐观回显的检查一条都不会漏），`attach()` 在 `boot = Boot.Ready` 之后按序重放（先拷贝再清空）。稳态开销为零：一个空列表 + 每次发送一次 null 判断，无轮询、无定时器、不触发重组。
- **定稿条件**：`ChatScreen` 变成 `boot is Boot.Working || boot is Boot.Failed` 才接管 —— `Idle` 直接显示正常对话页 + 底栏 + 输入框。`BootScreen.kt` 仍是 `Working` / `Failed` 的全部实现，不动。
- **附带**：`PiSessionViewModel.engineStarting()` 在空态文字删除后已无调用点，**保留**（它是「引擎在、还没开始服务」这一窗口的唯一可读表述），KDoc 改成说明为什么留。

## D32 · 设备桥 30 个工具合并成 21 个，同时把这轮提词压到最紧
**先把账对齐**：用户给的「每轮 5992 字符」是上一笔（`3156926`「提词器瘦身」）**之前**的数，那一笔已经在 HEAD 里
（删掉 30 条 description 尾巴上的「（危险等级：…）」390 字符、`environmentGuidance` 796→356、技能 front-matter 的
description 180→90 字节）。本轮起点是 HEAD 那份；口径与上一笔一致 = 工具 `description` + `promptSnippet` +
`promptGuidelines` + `environmentGuidance` + 技能 front-matter 的 `description` + 命令描述（另附 UTF-8 字节）。
**用户原话**：「把我们自己写的提词压到最紧，并把设备桥的 30 个工具合并成更少的工具，同时把 Kotlin/docs 里所有引用旧工具名
的地方改到自洽」「唯一不可谈判的是调用精度不受影响、不产生无故试错」。

**合并（30 → 21：6 组合并、15 个保持独立）**

| 合并后 | 由谁合并 | 判别参数（必填） | 危险级 |
|---|---|---|---|
| `android_download` | `android_export` + `android_import` | `op:"write"\|"read"` | dangerous |
| `android_files` | `android_files_read` + `android_files_write` | `op:"read"\|"write"` | dangerous |
| `android_device_state` | `android_battery` + `android_location` + `android_sensors` + `android_sensor` | `what:"battery"\|"location"\|"sensors"\|"sensor"` | read |
| `android_say` | `android_notify` + `android_toast` + `android_tts` | `kind:"notification"\|"toast"\|"speak"` | control |
| `android_app` | `android_apps` + `android_launch` | `action:"list"\|"launch"` | control |
| `android_clipboard` | `android_clipboard_get` + `android_clipboard_set` | `text`（给了=写，不给=读） | control |

独立：`android_bridge_status` `android_ui_dump` `android_tap` `android_input` `android_swipe` `android_key` `android_keyevent`
`android_screenshot` `android_stop_app` `android_share` `android_open` `android_torch` `android_vibrate` `android_files_list` `android_shell`。

**为什么这 6 组能合，别的不能**
1. 可合的判据是「**同一件设备能力、只是方向或种类不同**，判别参数是**闭集且必填**，每个分支的必填组合一句话写得清」：
   Download 读/写、SAF 读/写、电池/位置/传感器/采样、通知/短提示/朗读、列应用/启应用、剪贴板读/写。
2. **`android_share` + `android_open` 不合并**：两者都是「把控制权交给别的应用」，但用户/模型那句话完全不同
   （「分享出去」vs「打开它」），合并要发明一个 `kind`，而两分支的参数面（`text`/`url`/`subject` vs `url`）重叠不全，
   会把一句明确的话变成一次猜测。**`android_tap` + `android_swipe` 不合并**：一个是「点中某个控件」，一个是「从 A 拖到 B」，
   参数面（`index`/`x`/`y`/`longPress` vs `x1`/`y1`/`x2`/`y2`/`durationMs`）没有公共子集，合起来只多一个必填判别参数、不省描述。
   **`android_key` + `android_keyevent` 不合并**：能力/权限根本不同（无障碍 vs ADB/Shizuku），合并会让「需不需要 Shizuku」
   从工具名上消失。
3. **两处细节保精度**：① 每个 `run` 的分支是原实现的**原样搬运**（同一端点、同一请求体、同一结果格式化与错误文案），
   只按判别参数落分支；`op`/`what`/`kind`/`action` 非法时抛 `[BAD_PARAM] …只能是 "a" 或 "b"…`（与 bridge 的
   `[CODE] 原因` + `提示：` 同形），不留给模型猜的句子。② 每个返回文本开头带**实际跑的分支**（`[op write]`/`[what sensors]`/
   `[kind toast]`/`[action list]`/`[clipboard read]`），读的一眼看出是哪条路。
4. **会被同一句话触发的工具对，在各自 description 里钉死**：`download`↔`files`、`files_list`↔`download(op="read")`、
   `share`↔`open`、`say(notification)`↔`say(toast)`、`app(list)`↔`app(launch)`、`key`↔`keyevent`。

**字数（脚本按上面口径量，字符 / UTF-8 字节）**

| 项 | 改前（HEAD，30 工具） | 改后（21 工具） |
|---|---|---|
| 工具 description 合计 | 2837 / 6289 | 2317 / 4655 |
| `promptSnippet` 合计 | 675 / 1609 | 340 / 886 |
| `promptGuidelines` 合计（条数） | 755 / 1501（11 条） | 558 / 1024（7 条） |
| `environmentGuidance` | 361 / 743 | 217 / 411 |
| 技能 front-matter `description` | 55 / 147 | 31 / 77 |
| 命令描述 | 27 / 81 | 22 / 66 |
| **五项合计** | **4710 / 10370** | **3485 / 7119（−26.0% 字符，−31.3% 字节）** |
| 参数 schema 里 description 合计 | 1174 / 2502 | 1620 / 3118 |
| 含参数 description 的总量 | 5884 / 12872 | 5105 / 10237（−13.2% 字符，−20.5% 字节） |

**为什么这样最短且不丢精度**
- 删掉的只有四类：讲「为什么这么设计/历史决策/后端是谁」的解释、堆枚举清单、同一事实在多处重复、每条尾巴上的
  「危险操作…会请求确认」（收进 snippet 的一个「（需确认）」，8 个 dangerous 工具各一处）。
- **没删**：参数 schema 的 description 语义（默认值/范围/单位/二选一/代价）一个字没丢，合并工具额外加了
  「仅 `op="write"`」这类分支限定语——这就是参数 description 从 1174 涨到 1620 的全部原因，是有意的加法；
  能力、权限要求（无障碍 / Shizuku / SAF / 存储权限 / Android 版本）与失败处置（`[DISABLED]`→`android_bridge_status`、
  `NOT_FOUND`→重新 dump、二进制读回→改用 bash）全部保留。
- `promptGuidelines` 11 条收敛到 7 条，只留跨工具行为性的（dump→tap 工作流、控件树读不出改 screenshot、
  `[DISABLED]`/`[NO_PERMISSION]` 处置、shell 只管工作区、`app` launch 要精确包名、全局动作 vs 原始按键、SAF 路径写法），
  工具自己的 specialty 挂回该工具自己的 guideline。
- `environmentGuidance` 只留三件任何工具都装不下的事（身份、`/workspace` 与 `/sdcard` + 设备策略只管 `android_*`、
  危险操作确认语义），标题里的「Android 设备环境（pi-android）」哨兵原样保留（`before_agent_start` 依赖它）。
- 技能正文一个字没删（按需读取，不进每轮上下文），只把 front-matter description 压到 31 字符并把正文里的旧工具名改成新名。

**配套改动**：`danger.ts` 的 `DANGER_LEVELS` 按最终 21 个工具重写（合并工具取各分支的**最高**等级：`android_app` 的
list 是 read、launch 是 control，故 control；`android_clipboard` 同理），`describeDangerousCall` 把原来 4 个
case 收成 `android_download` / `android_files` 两个并按 `input.op` 给不同文案（读=内容进模型上下文，写=可能覆盖已有文件 /
文件离开沙箱）；`isDeviceTool` / `shellPrecheck` / 禁止清单一个字没动。

**自洽性交代**：`ShellPolicyMirrorCheck` 的检查 ④（注册工具集合 == `DANGER_LEVELS` 键集合）现在是 21 == 21；
`docs/surfaces-terminal-workbench-device.md` 的「两边都是 30 个」改成 21；Kotlin 只改字符串与注释（
`DeviceShell.kt`、`DeviceAppActions.kt`、`DeviceSystemActions.kt`、`DeviceCapabilityStore.kt`、
`DeviceCapabilityScreen.kt`），分支、类型、签名、策略、端点、参数校验一行未动；`grep -rho "android_[a-z_]*" --include=*.kt`
得到的每个名字都在这 21 个里。

**已知取舍（不藏着）**：确认的「记住本次会话」是 `permission-gate.ts` 按**工具名**记账的（`sessionGrants.has(toolName)`），
该文件本轮不许动，所以合并后「记住 `android_files`」会同时覆盖它的 read 与 write 两个方向——比合并前粗一档。
要收回去的最小改法（留给下一轮）：记住的键改成 `toolName + ":" + input.op`（对没有 `op` 的工具退化成工具名）。

> **这一条已经收回**（`46a1c07` 之后的对话批次）：`pi-android-permission-gate.ts` 的记账键现在就是
> `grantKey(toolName, input) = toolName[:op]`，提示语也带上方向（`android_files（write）`），
> 恢复成合并前「两个方向各自记账」的精度。

---

## D33 · 对话十项修复：分叉、自由选择复制、滑动跳顶、缩略图、看图、附件、输入框位置、滚动箭头
**用户原话（逐条）**：「长按对话分叉…提示错误」「对话列表里的名字是一样的，分不清谁是谁，后面写个 123 也行」
「长按只能出现一个特别丑的框，是复制全部，没法用系统的自由复制」「往下稍微用力划一下，它直接回到聊天的最顶部了」
「缩略图显示的不全」「图片不能点开看大图」「打开软件之后，聊天页这个输入框是跑到屏幕最上端的」
「把回到底部这个按钮弄得小一点，只剩一个箭头，半透明一点…弄成上下两个箭头」「（附件）这个来源没有可用的文件路径」
「对话管理那里是不是还残留着查看终端界面的聊天记录的一些残留」。

**逐条根因与裁决**（每条都追到根因，不是调参；记录在案以便下次不要重犯）：
1. **分叉失败是有条件的，不是偶发**：长按把转写的 `UserMessage.key` 当 `entryId` 发给 `fork`，而**实时路径**的 key 是合成的
   `user-<millis>-<n>`（只有**回放路径**才是 pi 的 entry id），pi 在 `agent-session-runtime.ts:275-276` 抛
   `Invalid entry ID for forking` → 「自己刚发的消息必失败、旧会话回放出来的必成功」。裁决：对 `get_fork_messages`
   **三级解析**（精确 id → 序号+文本 → 文本），成功后按 pi 的做法（`interactive-mode.ts:5157-5165`）把该消息**回填输入框**。
2. **分叉列表每条加等宽序号 1/2/3**，顺序 = pi 返回顺序（不排序、不过滤）。
3. **长按文本 = 系统自由选择**：全 App `SelectionContainer` 原本 0 命中（设计里写了、实现从没接上）。块级
   `SelectableContent`，一张卡一个 scope；**「复制全部」不丢**，挪到各块的 ⋮；图片/日期分隔这类非文本块不动。
4. **用力下划跳最顶**：窗口在 **fling 进行中**继续往头部灌行（原逻辑不看 `isScrollInProgress`）+ 哨兵行 key 恒定使
   LazyColumn 锚定失效。裁决：`TailFollow.mayLoadEarlier` + `prependAnchoredIndex` 两条**纯函数**（`TailFollowCheck` H1–H11 钉住）。
5. **缩略图必须完整**：`aspectRatio(1f)` + `Crop` → 单图按真实宽高比 + 60% 屏高上限，多图等大方格改 `Fit`，
   并加**解码面积上限**（1:5 长截图原本 ≈23 MB/张）。
6. **点图开原图**：原处是个**空手势**（吞点击却什么都不做）→ 全屏 `Dialog` 查看器（捏合/双击/✕/返回/点空白），
   独立窗口 ⇒ 关闭后列表滚动位置**结构性**不动。
7. **输入框跑到最上端**：`ChatBody` 是 `Column(fillMaxSize)`，**空转写**时 `if (!emptyTranscript)` 里的 `weight(1f)`
   子项根本不存在 → 剩余空间分不到，输入框贴到顶栏下。裁决：把 `Box(weight(1f))` 移到 `if` 外面（**任何空转写**都会触发，
   包括启动那 1–2 秒与新会话）。
8. **回到底部改两枚半透明箭头**（上=第一条、下=最新），未读改用小方块；已在顶/已在底各自隐藏。
9. **附件（非图片）**：实现与自己写的设计相反（去猜设备路径，Android 10+ 几乎必失败）→ 照设计**流式拷进
   `<workspace>/attachments/`** 并把**工作区相对路径**插进输入框；三种失败各有具体人话，且什么也不插。
10. **终端残留**：删掉 `BlockChrome.formatChars`（已删的 system-prompt 行残留，0 调用点）；会话管理里已无「看终端聊天记录」入口。

**会话树对照 pi 另修 4 个真 bug**：逐代缩进（线性 200 条会退到屏外）、缺 `activePathIds` 当前路径高亮、
兄弟未按活路径优先、搜索未按空白分词的 AND。**「点一条切过去」是协议缺口**（RPC 没有 `navigateTree` 等价命令，`rpc-mode.ts` case 已全枚举），界面改成明说。

## D34 · 设置：`multiline` 取代 `depth` 决定画几行；两个提示词开关搬出「运行时」
**用户原话**：「那个修改两种系统提示词那个地方复制，根本就复制不进去呀，好几千字只能复进去几十字」「它俩在那个运行时设置里面，不能挪到别的位置吗」。
**根因**：`app.runtime.systemPrompt` 漏了 `depth`（默认 1），而编辑器把 `depth` 当成「画几行」的依据 → 给它开了**单行框**；
单行框的 `heightInLines(1,1)` 只画第一段，所以几千字看着像「只进去几十字」。**粘贴与写盘都不截断**（`Clipboard` → `onValueChange` 原样；
`JsonPrimitive` 保留换行）—— 坏的是**可见性**。
**裁决**：把「多行」从 `depth`（导航语义：2/3 = 还能再进一层）里**拆出来**成为显式字段 `PiSetting.multiline`；
只有该多行的两项置 true（另一项那个只为冒充多行而写的 `depth = 2` 删掉）。多行框 `maxLines = 12`，**滚动交给 Compose 自己**
（`heightInLines` + `textFieldScroll` 按光标跟随），不在框外再套 `verticalScroll`。
**位置**：两项搬到新分组 **「提示词 · 系统提示词」**（插在「模型与推理」之后 —— pi 的 CLI 里 `--model` 与 `--system-prompt` 相邻，
`cli/args.ts:108-112`）；`key` 一个字不改，所以已写过的值原样读出。
**连带**：`pre-spawn` 纯检查的契约从「必须住在 `G_RUNTIME`」改成「住在登记在册的 pre-spawn 分组之一」，
并用负向对照证明它仍能抓到乱搬；4 处过时文档同步（`docs/pre-spawn-config.md`、`docs/pi-android-ui-spec.md` 的 13 组清单、
`00-screen-inventory.md`、`02-real-content.md`）。

## D35 · 工作区那一屏：按 `workspace-final.html` 1:1 实现，唯一新增是 `.html` 预览
**用户原话**：「工作区那个地方…只能看到改过的文件，也没法打开，也没法编辑，也没法删除」「都完整的按他说的来，一丁点差距都不能有」「就只加一个功能，HTML 文件可以打开」。
**裁决**：把「项目现场」从只读补成「现场 + 能动手」——① 当前目录卡（可点 → 工作区面板）② 正在跑 ③ 本次会话改过（行可点开**就地 diff** +
行尾 ⋮ + pending 写入行）④ 全部文件（面包屑 + 目录优先 + 行 ⋮ 打开/编辑/重命名/删除/复制路径 + 新建）⑤ 资源段（四类分段 ×
五种来源 `项目 .pi`/`.agents`/`全局`/`包`/`扩展贡献`，按 pi 优先级排，四种状态：正常/同名冲突/解析失败/被禁用 + 未受信任压暗），
外加查看器/编辑器/保存中/保存失败/未保存离开/二进制/超大/空文件/加载中，**17 个 mode 全部有实现**。
**资源规则没有另造**：转调 `PiResourceDiscovery`/`PiAutoExtensions`（它们转录的是 pi 的 `collectSkillEntries`/`FILE_PATTERNS`）。
**唯一相对稿子新增**：`.html` 用**系统 WebView** 渲染（`android.webkit.WebView`，没引 `androidx.webkit`；
`javaScriptEnabled`/`domStorageEnabled`/`allowFileAccess` 全开，`allowContentAccess` 收紧；`onReceivedError` 只认主框架）。
**12 条未能 1:1 的偏差逐条写在提交 `47129a2` 的正文里**（真正切换工作区、设备目录选择、「在对话里用」、「看它在对话里的那一步」、
裸 diff 形态、顶栏副行、Sheet/Snack 形态、扩展贡献只有一条真数据、版本号取不到、全局路径写法、二进制分享、文件名省略）。

## D36 · 会话树：缩进上限 + 行尾收窄 + 说明句搬进列表
**用户原话**：「树那里往下滑会被框住看不全…只能上下滑，然后这个工作树是很宽的」「它就是很宽，往下滑都跑到右边去了，下面就空了」。
**根因（算出来的，不是猜的）**：缩进 `depth × 12dp` **无上限** + 行尾固定 ≈94dp 的「分叉新会话」按钮，两者都从文本列里扣钱：
360dp 屏文本列在第 6 层剩 156dp、第 11 层 96dp、**第 19 层算成 0**（Compose 夹成 0）；而树是 DFS 展开、**最深节点排在列表最后**，
所以「滑到底看到的是一排空白带」——这就是「下面就空了」。标签行原本还没有行数上限，列一窄就换行，更糟。
**裁决**：**不**上横向滚动（那只是把「够不到」换成「要横着拖」，且 Compose 单容器双轴要靠嵌套滚动，方向写错就复现同一抱怨）。
改成：缩进两段式（0–4 层 ×12dp，5–10 层每层 +4dp，**≥10 层封顶 72dp**）+ 行尾动作 94 → **58dp**（分支字形 +「分叉」）+ 标签与徽章单行省略。
改后 360dp 屏文本列：252/228/208/200dp（depth 1/3/6/8）—— **放得下，不需要横向滚动**。
**「被框住」不是缺底部 inset**：覆盖层已被 Scaffold inner padding 内缩，列表视口下沿本来就是常驻底栏上沿（兄弟列表同模式且正常），
所以**故意没有**再塞一次 56dp（那会变成最后一行下面的死带）。
**用户追加裁决**（「怎么好怎么人性化你就怎么来」）：把那句「分叉会新建会话文件」从固定 chrome **搬进列表第一条**（常量 key，文案一字不改），
固定 chrome 少 44dp、滚动后视口净多 62dp；相对稿子的**有意偏离**只有「放开头而不是末尾」（手机竖屏视口最贵，这句话只在第一次进树时有用），理由写进 `TREE_FORK_HINT` 的 KDoc。

## D37 · 会话覆盖层：对照 V2 与 pi 修 bug + 更正三处自相矛盾的文档
**用户原话**：「点击左上角出现的这些什么会话树啊什么的…根本就不符合 V2 的美学规范吧，感觉很乱，不好看」「把 bug 修复…对比 pi 源代码」。
**真 bug**：① **搜索语义错了** —— 旧实现把整个搜索框当一个短语 `contains`，而 pi 是**按空白分词、每词都要命中**
（`session-selector-search.ts:135-183`），所以 `read file` 搜不到写着「file … read」的会话，且同一个覆盖层里列表与树对空格的理解还不一致；
② **「新建会话」按钮浮在列表上**，盖住最后一两行还点不到（v2 是列表下面独立一行）；③ **条数只在列表视图画**（稿子两个视图都画）。
**偏差**：行不在卡片里（v2 每组一个 `surf-low` 卡、分隔线只在行之间且全不透明）—— 这是「乱」的主要来源；排序 chip 永远选中；
分组头间距；行内 gap/字号/时间字体；「当前」徽标形态；搜索区块少 8dp；**空态应顶对齐（离筛选行 86dp）而不是垂直居中**。
**文档更正（以冻结稿为准）**：`02-real-content.md` §1.2/§1.4 与 `06-v2-construction-reference.md` 的「会话行三行」→ **两行**；
冻结稿 **D1 台**里不存在的「左缘 2px 条」删除；**E3 台**（它在 `direction-b-v2.html` 的 dev 目录里，**不在** `11-designer-adjudication.md`）
的「两个 chip 都选中」按 `SwipeRow` 的 `active={byTime}` 与 `phone26` 改成「选中态跟随当前排序」。
**协议缺口（如实标注，不伪造）**：RPC 无 `navigate_tree`；会话摘要无 `allMessagesText`；`set_session_name`/`export_html`/`clone` **不接会话参数**。

## D38 · 设备桥升级的裁决：不收紧授权、如无必要勿增实体、提词要记账
**用户原话**：「全面给我修到最强的状态…拥有最强控制安卓系统的能力。**除了授权不用收紧**，还是保持现在的就行，或者说再放宽点也行」
「变强不要这么点…能变多强就变多强，自己看着办」「13. keyevent/相机/QS fallback 写进技能 —— **如无必要，勿增实体**」。
**裁决**：
1. **不新增任何确认/审批**，不把「记住本次会话」改成逐次确认，不改连接模型；**安全底线保持不变**（`DeviceShellGuard` 的硬禁用清单、
   工作区写入边界、设备策略只管 `android_*`）—— 这些是能力边界，不是「授权」。放宽可用性限制可以，但必须逐条列出放宽了什么与风险。
2. **如无必要勿增实体**：每个新增（工具/参数/技能段落/设置行/文档）都要先回答「不加会怎样」，答不上来就不加；
   该给 agent 的信息**优先塞进已有实体的返回里**（错误码、`hint`、`bridge_status` 的一行），而不是新写手册。
3. **提词成本记账**：新能力优先做成**已有工具的新参数/新分支**（D32 刚把 30 个工具合并到 21、提词压掉 26%），
   真新增工具则按 pi 内置写法 + 登记 `DANGER_LEVELS` + 同步 Kotlin 提示与 docs，并在报告里给出**提词增量字数**。
4. **优先级**：消除静默失败（启动应用被 BAL 拦却报 200、启动自身自杀、无障�碍三态误诊、手势 busy 泄漏、三套坐标空间、
   桥只在 Activity 里）→ 能力（选择器/等待原语、SoM 截图、前台包名、包可见性、`intent:`）→ **Shizuku 引导**（单点收益最大：
   能力从「无障碍模拟」升到 ADB 级）；审计日志结构化，但**不做**外部建议里的「shell 逐次确认」与「UDS + uid 校验」（那是收紧授权）。

## D39 · 工作区屏：把 21 条与冻结稿的偏差收掉 + 查看器语法着色
**依据**：`design/ui-refactor/design-demos/workspace-final.html`（工作区专稿，构件与 `direction-b-v2.html` 同源）。
**收掉的偏差**：③④ 整段缺卡片容器（行内容从 12dp 起 → 14+12=26dp）；面包屑 `width(170)` 定宽把当前目录挤出屏外
（→ `widthIn(max=170)` + `FlowRow` 换行）；③ 行标题用完整内部路径（→ 叶子名 + 目录进副行，并删掉自造的「· 就地看这次 diff」）；
计数把 pending 扣掉（→ 计数/摘要用含 pending 的 `changed`，去重只留在列表行层面）；同名资源对被全局重排拆开
（→ 排序单位改为「同名组」）；用户可见文案里出现稿子段号（→ 改成段名）；pending 行缺上下 1px 状态色、末尾多一条线；
段头「新建」被画成描边胶囊（→ 无底 accent 文本 + 13 加号）而空目录主按钮被画成描边（→ accent 实底 32 高胶囊）；
行标题字重（非 strong 500、等宽行标题 `mono` 14）；Notice/ErrBlock 标题与动作的 `t13`/`t12` 取 12 的 `meta`；
徽标无条件色块；`⋮` 字符 → 16px 横排三点矢量；资源行去掉文件夹图标、解析失败换原因、禁用不追加原因；
② 每条命令各一张卡 → 段内共一片卡；引擎没起来那条空态用 file 图标；① 的 `›` 字符 → 14px 描边 chevron；
二进制 `▤` 字符 → 稿子的 `bin` 矢量；`formatTime` 补「前天」档；查看器只读正文接上**现成的**高亮通道
（`rememberPiHighlightedCode`，整份一次请求 + 按可见行惰性切片，引擎不在就是原来的单色，编辑态不上色）。
**与裁决/规范的冲突，以及处理（逐条）**：
1. **列表里的卡**：稿子的 `Rows` 是一个 `Card` 塞整列，手机上 ④ 可能有几千行、把整列放进一个 lazy item 等于放弃懒加载。
   所以卡壳由「每一片」自带（`WsCardSlice`：首片圆上角、末片圆下角、行间线画在卡内），屏幕上仍是一张卡。**有意偏离原型写法**。
2. **行间线透明度**：稿子的 `.div` 是不透明 `borderMuted`，而本项目 `06 §2` 写「组内 inset hairline（`borderMuted` 55%）」。
   以 **`06 §2` 为准**（冻结规范优先于原型），保持 `outlineVariant`；矛盾写在 `WsHairline` 的 KDoc 里，谁想改回去先改 `06 §2`。
3. **未受信任的压暗范围**：稿子整段 `opacity:.5`；这里只压暗「项目 .pi」那一族 —— 受项目信任影响的只有从**这个项目**读来的资源，
   `.agents`/全局/包里的资源照旧会被 pi 加载，一起压暗等于谎报。行内另有文字说明，颜色不是唯一信号。**有意偏离**。
4. **对话框按钮顺序**：稿子主项在左，本项目全应用是「取消在左」，保持全应用一致，不改。
5. **② 段头 aside**：稿子只有一条命令所以 `aside="进入上下文"` 是常量；这一屏最多四条、各条可能不同。改成
   「全部一致时才提到段头（这时行里不重复写），不一致时段头不表态」——既不重复也不自相矛盾。行副行本身照稿子（那半句稿子没有）。
6. **`!` / `!!` 那条命令没有刻度与进度段**：`ToolCall.elapsedMs` 是 `endedAt - ts`，流式中恒为 null；转录里的 bash 调用另有 `ts`，
   所以照 `ShellBlock` 的先例现算（不起计时器）。而 `BashRun`（`ui/PiSessionViewModel.kt`）**没有时间戳字段**，算不出来 ——
   拿不到就不画，不编一个数。补它要给 `BashRun` 加字段，那个文件不在这一批边界里。
7. **`t13` 系统字没有对应角色**：`PiTextStyles`/`piTypography` 的系统字只有 12/14/15/17，稿子写 `t13` 的几处（Notice 正文、
   ErrBlock 动作、空目录标题）取 12 的 `meta` —— 与对话页 `NoticeBlock` 对同一个 `t13` 的既有取法一致；空目录主按钮的
   `t13 w6` 取 `bodyMedium`（14）+ SemiBold，因为**按钮标签**在本项目里的既有角色就是它（`PiDialogAction`）。
8. **D14 复现一次**：本批在 `WorkspaceChrome` 的表格里把「15，加粗 500」写成连排的字面量时，恰好拼出了块注释的开头序列
   （斜杠 + 两个星号），`check-nested-comments.py` 一次报了 38 处。改成「字重 **500**」的写法后通过 ——
   D14 那条教训在 Markdown 表格里同样成立（`.md` 不被该脚本扫描，所以这里只是说明，不是另一处待修）。

## D40 · 主题语义 1:1：面积占比审查 + 滚动条（只对话页）+ 撤掉 5 个派生色

**用户裁决**（原话）：①「各种色的占据屏幕比例要和我的软件的比例差不多」；②「不用弄什么对比度调整啥的。用默认的」；
③「全修的一致」（指所有"pi 有、我们没按 pi 语义用"的地方）；④「滚动条别做了，把滚动条这个着色弄到上下按钮上」→ 后改成
「V2 有滚动条吗？有 就按 v 二的去做，并且按 pi token 一比一」→ 最终「对话界面有一个就行。别的地方不用做」；
⑤「整行是指的搜索时候是吧？如果是搜索时就不要管了，保持现在就行」。
**审查依据**：`design/ui-refactor/12-color-area-and-scrollbar-plan.md`（面积占比逐 token 对照表 + 改法清单，全为估算，
方法在 §1/附录 A，两组灵敏度的差值排序不变）。

**面积审查结论**：同一份会话内容下，`bashMode` 是**唯一**的真配色差（pi 6.45% vs 我们 0.72%，因为 `!` 面板缺 pi 的两条全宽
`bashMode` 边框）；`toolSuccessBg` 几乎一致（15.15% / 14.62%）；其余大差值（`userMessageBg`、图片口径）是**结构差** ——
pi 的 TUI 不画用户图片附件、不画卡片底；`toolOutput`/`text`/`muted`/`toolTitle` 是**归属差**（派生 token 顶替）；
列表光标行 `selectedBg` 是唯一真·面积缺口（10.0% vs 0）。

**决定与落地**：
1. **工具名用 `toolTitle`，并且加粗**（`ui/blocks/ToolBlockChrome.kt` 的 `ToolHeader`）。pi 每个内置渲染器都用它画工具名
   （`core/tools/renderers/bash.ts:40` 等），且是 `theme.fg("toolTitle", theme.bold(toolName))`
   （`components/tool-execution.js:91`、`:316`）—— 字重也要跟。随包字体只有 Regular + Bold（`PiMonoFamily`），
   所以取 `FontWeight.Bold`（用真字形，不合成 SemiBold）。v2 原型画的是 `muted`，**被用户裁决推翻**；
   `06 §2` 与 `docs/pi-android-ui-spec.md` §2.5 两处文档同步改成 `toolTitle`，否则文档和实现互相说谎。
2. **撤掉 5 个对比度修正 token**，改回 pi 原 token：`bodyOnTool→toolOutput`、`contextOnTool→toolDiffContext`、
   `thinkingBodyOnCanvas→thinkingText`、`metaOnCanvas→muted`、`metaOnCard→dim`（26 个消费点 / 18 文件，
   `ui/theme/PiContrast.kt` 一并删除）。**已知取舍**：pi 自己的 `toolOutput #808080` 在 `toolSuccessBg #283228` 上是
   **3.37:1**、在 `toolPendingBg` 上 3.69:1，低于 `docs/pi-android-ui-spec.md` §9 的 4.5:1 地板 —— 这是 **pi 的原值**，
   按用户裁决照用，不再由我们改写（`gap-disposition.md` 的 F13/F14 记录同步更新）。
3. **滚动条只做对话转录视口**（`ChatScreen.kt` 的 `LazyColumn`），其它任何容器都不加 —— 这一收窄同时就是 1:1 pi：
   pi 全仓只有一处接线（`interactive-mode.js:629-630`）且 `createChatViewport` 只建一个 `ScrollView`。几何按 v2
   （`direction-b-v2.html:143-146`：3dp 宽、滑块圆角 2dp、贴右缘），颜色按 pi 原值（轨道 `scrollbarTrack`、滑块
   `scrollbarThumb`），只新增 `ui/components/PiScrollbar.kt` 一个文件 + `ChatScreen.kt` 两行接线；不加依赖、不留计时器、
   状态不进 `Bundle`、**不加"滚动条模式"设置项**（D38.2 勿增实体）。
   四处与 v2/pi 的冲突在此定案：**(a)** v2 写 `track: transparent`，按"pi token 1:1"**画出轨道**（pi 就是铺满一列 track 再盖
   thumb，`layout.js:216-220`）；**(b)** pi 的 `auto` 模式 1 秒后整条消失、而用户要求默认可点，故取 pi **自己的 `always`
   模式**当基线（不发明），1000ms 降级为"激活态回落"；**(c)** 命中区取 **24dp**（视觉 3dp / 激活 6dp），不用 48dp 的理由
   是它占 360dp 屏的 13.3% 且与右下角两枚箭头、系统返回边带重叠；**(d)** 「回到最新」那颗改 `selectedBg` 底 + `text` 字形
   （1:1 pi 的「↓ Jump to latest message」指示条 `tui-renderer.js:14-18`），但**保留 `borderMuted` 描边** —— 否则
   `selectedBg` 对页面底只有 1.59/1.43，按钮认不出来。「回到顶部」pi 没有对应物，保持现状，不发明映射。
4. **列表光标行用 `selectedBg` 整行底**，落点＝**正在被长按/操作框打开的那一行**（`SessionsScreen.kt` 的 `actions` 状态，
   改 3 处）。依据：pi 的 `selectedBg` 画的是**键盘光标行**（`session-selector.js:421`、`tree-selector.js:603-604`），
   而"当前会话"pi 只把**名字染 accent**；v2 原型与 pi 一致（`:1812` 的 SwipeRow 用 `--selected-bg`，`:1891` 的 `sel`
   就是长按那一行）。**会话树不加** —— 我们没有光标行，加了等于发明新交互。
5. **搜索高亮保持现状**（命中整块变色）：用户裁决。我们只有行级索引（`ChatScreen.kt:1516-1526`），pi 是逐字区间
   （`tui-renderer.js:9`），逐字改要给每种 block 传区间并各写测试。
6. **`!` 面板不补循环转圈**（`06 §5` 明令没有循环动画；pi 的 loader 是终端字符动画，手机上可点的 Stop 更合适）。
7. **`infoBg` 不动**：pi 的 TUI 零消费（export-only），我们只用在两处小面积提示条上（`ProjectScreen.kt`、
   `ExtensionUiHost.kt`），属"pi 没有对应构件"的部分。

**实施顺序**（P0 记账 → P1 滚动条 → P2 `!` 面板两条全宽线 → P3 工具名 → P4 列表光标行 → P5 下箭头 → P6 撤派生 token，
P6 按 token 分 5 批提交）。P3 已随本条落地；其余等对应批次让出文件。

**D39 那批留下的四个问题，裁决如下**（同批落地）：
8. **被禁用资源的原因不进副行，也不加"详情"入口**：稿子的 Disabled 行本来就没有原因（`:1370-1374`），
   而"包设了 `autoload:false` / 筛选没命中"这两句话是**我们自己**写的解释。加了就是给一个稿子没有的
   构件开洞，且原因本就能在设置里查到（过滤规则、包设置）。所以按稿子只留 `⊘ 已禁用` 徽标。
9. **`WsChip.active` 删除**：段头改走 `WsSectionAction`、空目录改走 `solid` 之后它没有调用方了。没有调用方的
   形态不给它留着（如无必要勿增实体），几何数值仍写在 `WsChip` 的 KDoc 里，将来要"选中的胶囊"照着补回来。
10. **查看器换文件的一拍延迟**：`rememberPiHighlightedCode` 的 `hasStreamed` 是按**调用点**记的，所以同一个查看器
    打开第二个文件时会被当成"又变了一次"，延后 `STREAM_SETTLE_MS`（200 ms）才发请求。修法是查看器侧
    `key(path) { … }`（换文件＝新的调用点，首帧就着色，仍然只发一次请求），**没有动 `ui/render/**`**。
11. **超大文件的「… 其余 N 行未显示」保持 `meta`**：稿子 `:1140-1146` 画的是 `mono t12`，但那是一句**中文句子**
    而不是机器分类词，按规则 #7 与人写给人的文案同族（与 D39-7 对 `t13` 的取法一致）。要改的话先改稿子那一行。
12. **工具卡的参数区按 pi 分段上色**（接第 1 条：第 1 条管工具名那一半，这条管同一条行的另一半）。用户要求
   「每一处都对着 pi 官方源码改，不许凭印象」，逐条核过 `dist/core/tools/renderers/*.js` 与
   `dist/core/tools/render-utils.js`，每个内置渲染器的调用行都是**若干个 `theme.fg(token, …)` 拼起来的**，
   而 v2 原型把整个主体画成 `c-text` —— 与第 1 条同一条裁决（「全修的一致」）推翻。落法：新增
   `ToolCallPart`（text + **pi 的 token 名** + 可选 bold）与 `ToolHeader(subject: List<ToolCallPart>)`，
   由 `ToolHeader` 一处把 token 解析成 `PiPalette` 的颜色（与 `PiSyntaxToken` 同一分工，这样每个 block 只
   需要引用 pi 的 `fg(...)`，不碰颜色值）；**文字内容、顺序、间距、字号、等宽全部未动**，颜色是这条参数唯一携带的通道。逐工具配方与源码行：
   - **read**（`read.js:24-28`）：`${fg("toolTitle", bold("read"))} ${pathDisplay}${formatReadLineRange(args, theme)}`；
     路径 = `accent`（`render-utils.js:57-63` 的 `renderToolPath` → `fg("accent", shortenPath(value))`），
     行范围 = `warning`（`read.js:19-23`：`theme.fg("warning", …)` 画 `:startLine` 或 `:startLine-endLine`），无 offset/limit 时它是空串。
   - **write**（`write.js:87-90`）：同样的 `toolTitle` 名 + `renderToolPath` → 路径 `accent`。
   - **edit**（`edit.js:51-54`）：同上（`formatEditCall` 只有名字和路径两段）。
   - **grep**（`grep.js:16-27`）：`fg("accent", …)` 画 `/pattern/` + `fg("toolOutput", …)` 画 ` in <path>`，
     再按需 `fg("toolOutput", …)` 画 ` (glob)` 与 ` limit N`。
   - **find**（`find.js:15-25`）：`fg("accent", pattern)` + `fg("toolOutput", …)` 画 ` in <path>` + `fg("toolOutput", …)` 画 ` (limit N)`。
   - **ls**（`ls.js:12-19`）：`renderToolPath(str(args?.path), theme, cwd, { emptyFallback: "." })` → 路径 `accent`，
     再按需 `fg("toolOutput", …)` 画 ` (limit N)`。
   - **bash / powershell**（`bash.js:26-32` 的 `formatShellCall`）：**整行**（prompt 与 command 一起）
     `fg("toolTitle", bold(...))`，只有 timeout 后缀 `fg("muted", …)` 画 ` (timeout Ns)`；命令缺失时
     该段走 `invalidArgText`（`error`）/ `fg("toolOutput", "...")`。这是唯一一条**粗体超过工具名**的公式，
     所以命令那一段带 `bold = true`——否则同一行的 `$` 与命令会一半粗一半不粗，与 pi 的 `bold(...)` 不一致
     （这一处是「只改颜色」之外的唯一越界，已在交付报告里单列）。
   - **缺参数**：`render-utils.js:57-63` 的三条分支 —— `rawPath === null` → `invalidArgText`（`error`）、
     空值 → `fg("toolOutput", "...")`、否则 `accent`。本 App 的空值文案是我们自己的词（「文件」/「未命名文件」），
     所以**只取 token**（`toolOutput`），文案不动。
   - **通用回退卡**（`tool-execution.js:274-278` → `:315-322`）：pi 对这一类**整段不上色** ——
     `theme.fg("toolTitle", theme.bold(this.toolName))` 之后直接拼原始 JSON 参数，没有任何 `fg`。
     所以 `ToolCallBlock` 的副行取 `ToolCallToken.Uncoloured`（＝本 App 的 `text`，即「终端默认前景」的等价物），
     而不是 `accent`，也不分段。
   - **diff 卡**（本 App 自己的卡，pi 没有对应物）：它的名字格是从调用里抬上来的工具名
     （`rpc/.../Transcript.kt:541-564`），所以按 pi 的**工具名**取 `toolTitle`；路径格按 `renderToolPath` 取 `accent`
     （`render-utils.js:57-63`）。**只改颜色**：pi 对工具名另有 `bold`，这张卡的名字没有跟着加粗（它不是 pi 的调用行），
     要 1:1 对齐粗体的话说一声。
13. **shell 卡补 pi 的两样（收起预览、warnings 行），同时保留 v2 的三样（右端读数、页脚、chevron）**。
   用户裁决「多的三样保留」，所以这一条只做加法：v2 那套（header 右端的耗时刻度读数、页脚的
   `状态字形 + 状态词 + 耗时刻度`、展开 chevron）一格没动，补的是 pi 有而我们缺的两处。
   - **收起时也画尾部预览**。pi：`const BASH_PREVIEW_LINES = 5;`（`dist/core/tools/renderers/bash.js:14`），
     收起分支调 `truncateToVisualLines(styledOutput, BASH_PREVIEW_LINES, width)` 取**最后 5 个可见行**
     （`:56-65`；命令的结论在末尾，pi 自己的 bash 工具也是截尾，`core/tools/bash.ts:234`），
     `state.cachedSkipped > 0` 时在预览之前插一行 `theme.fg("muted", …)`（`:63-64`）。
     本 App 原来**收起时什么都不画** —— 一条跑完的命令在收起态连一行结果都看不到。现在收起＝最后 5 行
     + 一行 `muted` 提示（措辞沿用既有中文「上方还有 N 行未显示」）。**展开后的行为不变**：
     展开仍是 5 行 + 可点的「展开全部（上方还有 N 行）」（`fullOutput` 后到本 App 的 200 行预算）。
     两个实现细节：① 画哪一窗随状态走（`expanded && fullOutput` 才用大预算），否则「展开过再收起」
     会退回成 200 行的"预览"；② 提示行放在预览**之后**（pi 放在之前）—— 我们的措辞是「**上方**还有 N 行」，
     放在上面这句就不成立，且与展开态那句位置一致。
   - **warnings 行按 pi 的位置与颜色，且与展开无关**。pi：`if (truncation?.truncated || fullOutputPath)`
     时在**正文之后**追加一行 `theme.fg("warning", …)`，内容是方括号包起来的 `warnings.join(". ")`
     （`bash.js:78-90`，`warnings` 由 `Full output: <path>` 与 `Truncated: showing X of Y lines` 拼成）。
     本 App 对应的数据是 `item.outputTruncated` / `fullOutputPath`，句子早就由 `truncationNotice`
     按 pi 的拼法翻译好了（`ToolOutputParse.kt`），但它原来挂在 `if (expanded)` 里 —— 收起时被截断了也不说。
     现在移出展开分支、位置在正文之后、颜色是 `palette.warning`（`ToolNotice` 本来就是 warning 色）。
     **不重复**：pi 的 bash 工具会把这句同时追加到自己的输出末尾，`stripFullOutputFooter` 在画正文前
     先把它从正文里剥掉（`bash.js:59-64`），所以这条信息全卡只出现一次。

## D41 · 逐点 token 对齐（来源 `13-pi-token-callsite-diff.md`）
**用户裁决**（原话）：「能和 pi 一模一样的，全都一模一样」+「全修的一致」。审计表逐点核过 pi 0.85.1 的
`dist/modes/interactive/**`、`dist/core/tools/renderers/**`、`dist/modes/interactive/theme/theme.js`（下面每条的行号都出自这份 dist，
即「你手上那份 app 里跑的 pi」；同一个语句在上游 `.ts` 里行号不同，凡引用 `.ts` 的地方都是既有注释留下的）。
**落地（10 处，全部内置主题下零观感风险或已批准）**：
1. `ui/blocks/HookMessageBlock.kt:83` 补 `textColor = palette.customMessageText`。pi：`components/custom-message.js:83-85`
   `color: (text) => theme.fg("customMessageText", text)`。
2. `ui/blocks/CompactionBlock.kt:137` 同上。pi：`components/compaction-summary-message.js:35`（展开分支；收起分支同 token，`:38-40`）。
3. `ui/blocks/BranchSummaryBlock.kt:119` 同上。pi：`components/branch-summary-message.js:34-36`。
4. `ui/blocks/SkillInvocationBlock.kt:95` 标题里的技能名 `text` → `customMessageText`（正文那处早在 `:127` 就是它）。
   pi：`components/skill-invocation-message.js:39-42` 的 `fg("customMessageLabel","[skill] ") + fg("customMessageText", name) + fg("dim", " (… to expand)")`。
   —— 这四处合起来消掉「同族四张卡只有 Skill 传了 `customMessageText`」的不一致（`PiMarkdownTheme` 的 `body = textColor ?: palette.text`）。
5. `ui/blocks/GrepBlock.kt:212` 去掉 `if (match.context) contextOnTool else bodyOnTool` → 一律 `palette.toolOutput`。
   pi：`core/tools/renderers/grep.js:30-37` 的 `displayLines.map((line) => theme.fg("toolOutput", line))`，**没有**上下文分支；
   `toolDiffContext` 在整个 pi 里只出现在 diff 渲染器（`components/diff.js:78`、`:127`）与主题 schema（`theme/theme-json.js:63`）。
   `ToolOutputParse.GrepMatch.context` **保留**（`app/src/test/.../ToolOutputParseCheck.kt:82`、`:88` 断言它），只是 UI 不再分叉。
6. `ui/render/PiMarkdownTheme.kt:295` 的 `quote` 加 `fontStyle = FontStyle.Italic`。pi 的引用是**两层**：主题给颜色
   （`theme/theme.js:936` `quote: (text) => theme.fg("mdQuote", text)`），渲染器再套 italic
   （pi-tui `components/markdown.js:417`；本机核到的是打包副本 `dist/bundle/chunks/chunk-JVUZSMYM.js:584`：
   `case"blockquote":{let quoteStyle=text=>this.theme.quote(this.theme.italic(text)) …`）。竖条只取该 style 的颜色，所以竖条不动。
7. `ui/blocks/ErrorBlock.kt:91` 错误句子 `text` → `palette.error`。pi 的错误**文字**全是 `error`，没有一处用正文色：
   `components/assistant-message.js:153`（`fg("error", "Error: " + errorMsg)`）、`interactive-mode.js:2232`/`:2795`/`:3522`、
   工具栏错误分支 `renderers/edit.js:67`、`write.js:122`。（审计文里写「7 处」，实数为 `modes/` + `core/` 共 **17 处** `fg("error", …)`，
   里面既有文字也有 `(exit N)` 之类读数；**"错误文字没有一处是正文色"这个判断成立**，只是条数要更正。）
8. `ui/blocks/NoticeBlock.kt:64` `Notice.Tone.Info` 的 `muted` → `palette.dim`。pi 的信息态状态行：
   `interactive-mode.js:2866-2867` `const color = status.type === "warning" ? "warning" : "dim";`。
9. **压缩卡改成 pi 的整卡满宽 `customMessageBg`**（`ui/blocks/CompactionBlock.kt:77-101`）。pi：
   `components/compaction-summary-message.js:13` `super(1, 1, (t) => theme.bg("customMessageBg", t))` —— 整块底色、满宽。
   随之删掉 v2 的两条 hairline 与 chip 自己的 `Surface`（同色 chip 在卡内不可见、hairline 会把卡切成两半），
   标签行改成与另外三张同族卡一致的「3px `customMessageLabel` 条 + 等宽标签」，副行由居中改为左对齐（chip 没了就没有对齐基准）。
   外框取同族的 `customMessageLabel@35%`（pi 的盒子无边框，这条是 App 自己的家族约定）。
10. `ui/blocks/ImageGridBlock.kt:249`、`:261` 图片占位标签 → `palette.toolOutput`。pi：工具图片的 fallback 文字
   `new Image(…, { fallbackColor: (s) => theme.fg("toolOutput", s) }, …)`（`components/tool-execution.js:307`）。
   同一段 fallback 的两行一起改，否则一个标签会被拆成两种颜色。

**保持不动（记账，逐条给理由）**：
- **R3（D4）Boot 品牌行**：保持 `text`/`muted`。pi 的等效屏是**首启向导**（`first-time-setup.js:32`/`:34` 把 logo 与欢迎句染 `accent`），
  本 App 的 `BootScreen` 是 v2 自己的启动/安装进度屏 —— 屏不同，`11` 的「颜色听 pi」针对同一个构件，这里没有同一个构件可对。
- **R5（§5.2）M3 surface 阶梯**：实现保持，**已把「派生槽位」写进 `docs/pi-android-ui-spec.md` §2.1**（表格一行 + 规则一条，
  含五个混合比例、为什么 pi 做不到、以及"导入自定义主题时的真实差异来源"）。理由：pi 只有 `pageBg`/`cardBg` 两档底色，
  终端表达不了中间调；手机需要层次，`06 §2` 的面板本身就要 `surf-low`/`surf-high` 两档。严格 1:1 只能收敛回两档，观感会塌。
- **R6（D10）扩展对话框标题**：保持「扩展给什么色用什么色，没给就 `text`」。pi 给的是 `accent` + bold（`extension-selector.js:29` 等三处），
  但本 App 的扩展对话框与设置对话框**共用同一构件**（`11` D-5 的合并），改标题色会让这两者标题不同色；pi 的两者本来就是两种壳。
- **R8（D11b）信任提示的选项标签**：保持 `success`/`error`。pi 的 trust selector 是键盘列表，选中的一项染 `accent`、其余 `text`
  （`components/trust-selector.js:64-66`）；这里是**一次性安全决策**的两枚按钮，绿/红比「光标在哪」更直接表达"信任与否"。
- **R4（P6 连带）`StateTone.Rejected` 的落点**：`ui/theme/PiStateChip.kt` 的 `StateTone.Rejected` 现在是 `palette.bodyOnTool`
  （`07` D2 单独裁过）。**P6 撤掉派生 token 时改成 `toolOutput`**，本批不动（P6 单独做，避免两批拆不开）。
**D41 补充 · 文档与实现同步**：`docs/pi-android-ui-spec.md` §2.2 的排版表原来写着「正文 15 / 元信息 11.5 / 机器小 11.5」，
那是 B7 收敛字号**之前**的旧值（11.5 低于该表自己写的 12 标签地板；15 是行标题那一档，不是聊天正文）。已按 `PiTextStyles` 的实际值改成
`prose 14/23` · `meta 12/18` · `monoSmall 12/18`，并补一行 `code 13/19`、`numeric` = `mono`，口径写明「5 档以 `06 §2` 为准、落地以 `PiTextStyles` 为准」，
同时点明 15/17 两档来自 M3 的 `bodyLarge`/`titleMedium`（行/屏标题步进），不在 `PiTextStyles` 里 —— 这样这两份文档不会再各自漂移。

## D42 · 记在账上的两件待办：proot 的 l2s 隐患、proroot 作为可选运行时

**来源**：用户问「DSHA 的这个 proot 的坑我们避免了吗 / proroot 不也有坑吗」，考察对象是本机运行的 DSHA（`com.dsh.client`）与它自带的修复脚本
`/root/.dsh/flatten-l2s.py`。**结论：一半避开、一半没处理，另有一条我们自己特有的暴露点。**

**已避开的三条**（有码为证）：
1. `--link2symlink` 的 store 必须先存在（proot 自己从不创建，缺了 guest 里每次硬链接都 ENOENT）。我们建了 `PiPaths.l2s`
   （`runtime/PiRuntime.kt:110`）并把它绑回自己的绝对路径（`:206-209`）；`docs/device-verification.md` 的 A3/B1 就是验它。
2. store 放在**持久**的 rootfs 内（`<rootfs>/.l2s`）而不是 `/tmp`，所以"临时目录被清理 → 目标悬空"这个触发条件不成立。
3. 本仓库**不打包/备份 guest**（Kotlin 侧没有任何 tar/zip 输出流），所以 DSHA 那个"tar 遍历撞 ELOOP、备份必失败"的症状结构性不会出现。

**未处理的五条（待办）**：
1. **没有写入侧纪律**：DSHA 的治本是让写入一律 `rename`（`fs-write-patch.sh`）。我们 guest 里跑 npm
   （`packages/PiPackageService.kt:14`）、apt/dpkg、git，它们都会 `link()` → 照样生成 `.l2s.*` 链。
2. **没有实体化/修复工具**：DSHA 有 `flatten-l2s.py`（只碰含 `.l2s.` 的 symlink；先写同目录临时文件 + fsync + md5 校验，再 `os.replace`
   原子替换；读不出的只报告不删；孤儿数据文件默认保留）。我们没有对应物。
3. **升级会悬空（我们特有，DSHA 那份文档没覆盖）**：`runtime/RuntimeProvisioner.kt:171-173` 的 `wipe()` 在运行时 revision 变化时
   整个删掉 `<files>/pi/runtime` 重铺（`:19` 自己写明这一层是 volatile），而 `.l2s` 就在里面。任何落在**持久区**、指向 rootfs 内 l2s 目标的链，
   升级后会全部悬空、读 ENOENT —— 因为我们不打包，它不会在备份时报错，只会某天"某个文件读不出来"。
   判定：`find /data/data/app.pi/files/pi/workspace /data/data/app.pi/files/pi/.pi -name "*.l2s.*"`（**上机量**；构建机上 `/data/data/app.pi` 不存在）。
4. `git commit`/`gc` 在 `--link2symlink` 下的可靠性：`docs/known-gaps.md:870` 自己写着"下结论前先量"，**仍未量**。
5. 修法优先级（派活时照这个顺序）：① 把 `.l2s` 移出 volatile 目录（或 wipe 时保留它）② 加实体化修复（照 DSHA 的安全设计）
   ③ 上机判定有没有隐患链并写进 `device-verification.md` ④ 我们自己的写入路径一律 `rename` ⑤ 量 git commit/gc。

**proroot（`https://github.com/coderredlab/proroot`）作为可选运行时**：用户要「放设置里一个、默认关、打开获得更好性能」。
已核到的官方事实（README）：rootless、**自称零 ptrace 开销**、proot 的 drop-in；**5 个 .so** 放 `jniLibs/arm64-v8a`；要求
**Android 8.0+/arm64-v8a/Ubuntu arm64 + glibc**；选项 `-r/-w/-b/-0/--link2symlink/--static-loader`；env `PROROOT_TMP_DIR` 等；
**`--link2symlink` 仍然存在，自述是 "anchor + symlink groups"**（所以上面那五条**不会**因为换 proroot 而自动消失，这一条更正我此前对用户的口头结论）；
**源码不公开、License 是 Proprietary（"free to use in your projects"，不许分发修改过的二进制）**，作者重心已转向 proroom。
**待交付**：`docs/proroot-research.md`（机制/性能数字有无/依赖与探测/回退/许可与登记/维护风险/最好方案/验证命令）——研究代理产出后据此再裁。


## D43 · `!` / `!!` 面板按 pi 对齐（排期 P2）
> 编号说明：本条原本按派活当时的下一号写成 D42，但同一时间 D42 已被另一条
> （「记在账上的两件待办：proot 的 l2s 隐患、proroot 作为可选运行时」，`:594`）占用 —— 于是顺延为 **D43**，
> 文件里不再有两个同号条目。引用本条的地方（`ui/chat/BashPanel.kt` 的 KDoc、`02-real-content.md` 的面板行）已同步。
**用户裁决**：排期里的 P2 —— 「`!` 命令面板按 pi 源码对齐」。pi 源码：`dist/modes/interactive/components/bash-execution.js`
（逐条核过，下面引号里的行号都是这一份 dist，即 app 里实际跑的那份）。
**pi 原文（构造函数 `:26-43`）**：
```
27:  const colorKey = excludeFromContext ? "dim" : "bashMode";
28:  const borderColor = (str) => theme.fg(colorKey, str);
30:  this.addChild(new Spacer(1));
32:  this.addChild(new DynamicBorder(borderColor));          // 上边：整宽
37:  const header = new Text(theme.fg(colorKey, theme.bold(`$ ${command}`)), 1, 0);
43:  this.addChild(new DynamicBorder(borderColor));          // 下边：整宽
```
`components/dynamic-border.js`：`render(width) { return [this.color("─".repeat(Math.max(1, width)))]; }` —— 整宽的一条 `─`。
输出正文（`:106` 展开 / `:111` 收起）：`availableLines.map((line) => theme.fg("muted", line))`。
状态行（`:131-158`）：只有 `cancelled` → `warning`、`error` → `error` 两种状态词，成功（`setComplete` 的 `"complete"`，`:72-77`）
**不贡献任何一段**；剩下的只有收起时的「还有 N 行」提示与截断告警 `warning`（`:155`）。
**落地**：
1. **补上整框**：`ui/chat/BashPanel.kt` 的 `Modifier.bashPanelEdges(color)`（`bashPanelEdges`）在卡片上下各画一条 1dp 线，
   颜色 = `dim`（`!!`）/ `bashMode`（否则）。**为什么用路径而不是 `border()`**：v2 给这条面板的几何是 12dp 圆角卡
   （`PiShapes.cardInner`），`border()` 是四面、而 pi 只要两面；直线会被 `Surface` 自己的 `clip(shape)` 在两个圆角处切掉两端，
   而「整宽」正是这条边框要表达的东西 —— 所以线沿圆角弧走（与 `WorkspaceChrome.wsSheetTopEdge` 同一手法）。
   **为什么 `onDrawWithContent` 而不是 `drawBehind`**：`Surface(modifier = …)` 把自己的 `.background().clip()` 接在调用方
   modifier **之后**，`drawBehind` 画在卡片底色底下、永远看不见；先 `drawContent()` 再画两条路径才对。线宽取全应用唯一的
   1px（`06 §2` 线宽）——终端里那条边是一整行字符，1dp 是手机上的等价物。
2. **输出正文** `palette.toolOutput` → `palette.muted`（`BashPanel.kt` 的正文那处）。`toolOutput` 是**工具卡**正文的 token，
   组件不同、底色不同，只在 pi 自带主题里同值。
3. **命令头**保持 `bashMode`/`dim`，并按 `:37` **补粗体**。pi 的更新路径（`:100`
   `new Text(theme.fg("bashMode", theme.bold(...)))`）**丢掉了 `colorKey`**：`!!` 的命令一旦产出输出就不再 dim。那是上游
   自己的不一致，不是规则 —— 本 App 每次组合都从 `run` 重建面板，只有构造函数那条规则可循，所以以 `:27`/`:37` 为准。
4. **状态行照 pi：成功的命令不画**。原来成功会画一条 `success` 色的「成功 · 进入上下文」，`已结束`/`退出码 N` 也在同一函数里；
   现在 `statusText` 只在 `cancelled`（warning）/ 非零退出（error）/ 截断（warning）三种情况下有内容，干净跑完返回 null。
   **去 v2 板核过**：`direction-b-v2.html` 里 `停止命令`、`关闭输出`、`bashMode` **零命中**，`06`/`11` 也没有这颗面板的条款，
   所以板子没有要求过状态行 —— 按 pi 改，不留偏离。
5. **loader 不补**：pi 运行中画的是 `Loader`（转圈 + muted 文字，`:40`），本 App 保持 **Stop 按钮**。依据已有的两条裁决：
   `06 §5`「没有循环动画」，以及 `07` D40.6「这条面板是运行中的 `!` 命令唯一能停下来的地方」。
**有意偏离（记账，1 条）**：**`进入/不进上下文` 这半句 pi 不写**。pi 在构造标题前就把 `!!` 剥掉了
（`interactive-mode.js:2503-2504` `const command = isExcluded ? text.slice(2).trim() : text.slice(1).trim();`），
只让边框颜色说明这件事；我们保留文字，依据本仓库自己的 §9 / `06 §4`「颜色不能是唯一信号」—— 1dp 的边框色不足以承载
「这次跑进不进上下文」这个事实，而这正是这条面板要报告的东西。文字跟着**哪一条状态行被画出来**就走哪一条。
**随之出现的文档漂移（不在本批边界，留给后续）**：`design/ui-refactor/02-real-content.md:656` 那一行的「状态行」列还列着
`成功 · 不进上下文` / `已结束 · 输出被截断，完整输出：<path>` 这些串，前者已经不画、后者只剩截断那半句；`BashPanel.kt:64,73,81`
的行号也早已漂移。

## D44 · proroot 作为**可选**运行时落地（默认关；装机与维护永远 proot）
**用户裁决**（原话）：「proroot 现在能用了吗？让他全都做完，做完美？推到分支上能用啊？」——
即 **做成真的能用**：一个设置项（**默认关**）、打开后真正走 proroot、不可用时自动回退 proot、
**这个分支推到远端后能装能跑**。用户已知并接受的取舍：**闭源、提速幅度未量化、永远不做默认**。
**父代补充澄清的边界**（用户追问「只有执行命令用 proroot 不就白装了」）：
proroot 覆盖的「执行命令这一层」**包括全部日常重头戏** —— ① **引擎进程本身**（Node + pi，最大头、启动耗时主体）
② **终端 PTY**（`PtyLauncher`）③ **pi 的工具执行**（引擎进程内跑命令/读写文件那条路）④ **用户后续装包**
（`packages/GuestCommand` 的 `npm install` / `pi install`）。**只有两类留在 proot**：
① **首次装机/解压**（rootfs、工具、node、pnpm、harness、guard 那六步：一次性、必须万无一失、且没有性能收益）
② **维护类**（备份/恢复、配置快照、`RuntimeSelfCheck`、`PtyLauncher` 的 `script(1)` 能力探测）。
**别读成「proroot 只在边角用」**：性能敏感的那条日常路径全部走 proroot，proot 只兜「装机 + 维护 + 回退」。

**决策点**：`runtime/RuntimeSelection.kt` 是唯一的「用哪个运行时」的地方（纯判定在 `RuntimeChoice.decide`）；
argv/env 由 `runtime/GuestCommandLine.kt` 分派到 `ProotCommand` / `ProrootCommand`，两者**共用**
`runtime/GuestRecipe.kt`（绑定表 + 公共 env + `/bin/bash -c` 尾巴）——**不存在第二份会漂移的绑定清单**。
`allowProroot = false` 是装机/维护路径显式传入的，分界线在调用点可见，不藏在某个默认值里。

**选择逻辑（四条全成立才用 proroot）**：① 设置开着 ② 5 个 `.so` 都在 `nativeLibraryDir`
③ **探针门禁**在该解包 revision + 这 5 个文件的 sha256 上通过 ④ 连续失败 < 3。
判序 = 开关 → 文件 → 门禁 → 失败预算；任何一条不成立即 proot（`RuntimeChoice.decide` 是纯函数，harness 逐个条件钉死）。

**三层兜底**（照 DSHA 的形态，`docs/proroot-research.md` §7.1 原文，写进代码注释注明来源）：
① **文件缺失** → 直接 proot，说明里列出缺哪些文件；
② **proroot 启动失败** → 计一次失败并**用 proot 重试一次**（`GuestCommand.execute` 的护窗期归属判定：
快退 + 输出里有 `[proroot]`/`libproroot.so` 才算启动失败；引擎侧 `PiEngineHost` 在 `spawn` 抛异常时同样回退一次）；
③ **连续 3 次失败** → 强制回退 proot **直到用户重新打开开关**（计数持久化在 `RuntimePreferences`，重开即清零）。
「告知用户」的落点：设置行「运行时（实际生效）」+ 导出诊断报告的「运行时选择」段 + 一条 log（App 内没有 toast 通道，
而聊天/会话层不在本批边界）。

**探针门禁**（这是敢默认安全的唯一依据）：第一次用 proroot 之前跑一次并**按 revision + `.so` 的 sha256 缓存**
（`<runtime>/.proroot-probe`，随 `wipe()` 失效；失败结论也缓存，因为门禁是一次测量不是重试循环）。两条都要过：
① **raw syscall 探针**（`ProrootRawProbe`）：guest 里种一个 marker 文件再**用 raw `openat` 读回**，
判定 = **必须翻译到 guest 文件系统**，并且**同一路径的 raw 内容与 libc 内容不得不同**——后者（静默读到宿主文件）是**否决级**；
② **`rg`/`fd` 真调用**（复用 `GuestToolProbe`，参数化到 proroot）：两者是 **musl 静态** Rust 二进制，
不走走动态链接器，全靠 proroot 的 `--static-loader` + inline `svc` 改写，`--version` 正常而 guest 路径读空是这类缺口的形状。
**这两条不过 → 不许用 proroot**（上机判据见 `docs/device-verification.md` §J1–J3）。
**门禁结论的失效条件（补记，2026-09-16）**：缓存 key = 解包 revision + 5 个 `.so` 的 sha256，**开关不在 key 里** ——
所以一条"未通过"会活过拨开关（哪怕它只是一次瞬时原因），而设置行承诺过"关掉再打开就是再试一次"。
修复：**用户把开关从关→开时删掉探针缓存**（`runtime/ProrootRetry.kt`，`RuntimeSelection.setProrootEnabled` 调用；
纯判定 + 注入回调，所以 harness 是**执行**生产的那条转移而不是断言一份副本）；**关掉时不删**（结论描述的是运行时树，
不是开关）；**正常重启不重跑门禁** —— 缓存继续生效，那正是缓存存在的意义（`ProrootRetry` 只管开关那一次写入，
不碰普通启动路径）。上机判据 §J8。

**`--kill-on-exit` 的替代**（proroot 拒收该 flag）：`GuestTreeReaper` 自己回收 guest 树。
root 的**闭包**由 `/proc/<pid>/stat` 的父子关系算出（`GuestProcessTree`，纯逻辑），**先 TERM 后 KILL**，
各自 3 s / 2 s 超时并轮询；**最深优先、root 最后**（先杀父会把子 reparent 到 init，边就没了）；
每次观测带 `(pid, starttime)` 身份，pid 回收不会误杀；**幸存者在报告里写出来**，不假装清干净。
只能杀「我们自己启动的那棵树」：root 来自 `ProrootLaunchHandle`（`Process.pid()` 在本项目的编译类路径上不可用），
而它识别的是 **proroot 自己写的那张表** —— `.proroot-config-<launcher pid>`。

**`.proroot-config-*` 清理策略**：`PROROOT_TMP_DIR` = `<files>/pi/runtime/proroot-tmp`（**宿主真实路径**、volatile）；
**每次 proroot 启动之前**扫一遍，**只删 `/proc/<pid>` 不存在的**（引擎 + 终端 + 装包可能同时在跑，
按"除了我全删"会拆掉正在工作的 guest）；停止那次 guest 时由该次启动的 handle **删掉它自己那份**；
**兜底上限 32 份**，扫完仍超量就按 mtime 从旧到新删到上限并**记一条日志**（`keepNames` 保护本次启动）。
**不用"年龄"当唯一判据，也不留常驻计时器**（清扫只发生在启动前，那是唯一可能新增一份的时刻）。

**永不默认**：默认关。DSHA 反过来是默认开（自研自用，§7.1），我们是通用 App，所以抄它的**机制**不抄它的**默认**。

**一个 App-only 的存储决定**：设置项**不写进 pi 的 `settings.json`**——它决定在 pi 存在之前用哪个二进制启动 guest，
pi 没有读者。落地形态沿用本仓库已有的「app-only pref」存法（`SharedPreferences`，与 `DeviceCapabilityStore` 同一种），
并在设置栈里用一个 store 装饰器（`ui/settings/AppOnlySettingsStore.kt`）接管两行：
`app.runtime.proroot`（开关，写 prefs）与 `app.runtime.prorootStatus`（**派生只读**：实际生效的运行时 + 回退原因）。
分组摘要也改读后者，因为「设置里写了什么 ≠ 实际生效什么」。

**影响**：`runtime/`（新增 16 个文件：`RuntimeSelection`/`RuntimePreferences`/`ProrootCommand`/`GuestRecipe`/`GuestCommandLine`/
`RuntimeChoice`/`ProrootProbe`/`ProrootRawProbe`/`ProrootProbeCache`/`ProrootLaunchHandle`/`ProrootConfigSweep`/
`GuestProcessTree`/`GuestTreeReaper`/`ShellQuote`/`ProrootRetry`/`GuestToolProbe`〔原本只在 main 之外，随本批一起进来〕；
改 `PiRuntime`/`PtyLauncher`/`PtySession`/`RuntimeSelfCheck`）、`app/src/test/kotlin/app/pi/runtime/`（新增两个 harness 源：
`ProrootCheck`、`GuestToolProbeCheck`）、
`packages/GuestCommand.kt`、`engine/PiEngineHost.kt`、`ui/settings/`（`AppOnlySettingsStore`、`PiSettingsRegistry` 两行、
`PiSettingsStack`、`DiagnosticsReport`）、`tools/run-app-pure-checks.sh` 的 `proroot` harness（新增），
许可资产两份（`assets/licenses/{proprietary-third-party,proroot-license}.txt`），
以及 `docs/pi-android-app-design.md` §2.3.1、`docs/device-verification.md` §J、`docs/known-gaps.md` §N。

---

## D45 · shell 命令卡：折叠态**不画**输出（主动偏离 pi；换取手机屏幕高度）
**用户裁决**（原话）：「弄成和原来一样的吧，现在这样太占面积了，本来手机屏幕就不大，只把这一个地方弄成原来的就行。」

**背景**：D41 那一批把工具卡逐点对齐 pi 时，`ui/blocks/ShellBlock.kt` 的折叠态从「什么都不画」
改成了 pi 的形状 —— pi 的折叠 shell 卡画最后 `BASH_PREVIEW_LINES`（=5）行
（`dist/core/tools/renderers/bash.js:56-70`，`truncateToVisualLines(styledOutput, 5, width)`），
再上面加一行 muted 的「… (N earlier lines, … to expand)」；同时截断警告行也从「只展开时显示」
改成「两种状态都显示」（pi 的 `:78-90` 与 `expanded` 无关）。

**裁决**：**这两处都退回原来的形状** —— 折叠时只留标题行（命令 + `›`）与页脚（状态 / 退出码 / 行数 / 「已截断」），
输出正文与截断警告都只在展开后出现。

**为什么明知不一致也要退**：一次 shell 调用在折叠时要多占 **5 行尾巴 + 1 行提示**，而「一连串 shell 调用」
正是对话流最吃高度的地方 —— 手机上这是「一屏看四轮」和「一屏看两轮」的差别。信息并没有丢：
标题行说了**跑了什么**，页脚说了**结果如何**（状态、退出码、行数、是否截断），而要点开看全文的动作本来就在。
即：pi 的折叠态是给**终端**设计的（终端一屏 50 行），手机不是。

**代价与边界**：这一处是本仓库**主动偏离 pi 的清单**里的一条（与 D41 的 R5 派生色、D40 的滚动条并列），
所以 ledger 记名、代码里两处 KDoc 都写明「pi 是 X，这里故意不是 X，理由是什么」，而不是把注释留在旧的
"in both states" 上。**改回去是一行**：`ShellBlock.kt` 的两个守卫（正文 `if (expanded && bodyText.isNotEmpty())`、
警告 `if (expanded && notice != null)`）。

**颜色一个字都没动**：同一批里 `shellSubject` 的两段式上色（命令 `toolTitle` + bold、` (timeout Ns)` 为 `muted`）
保留 —— 这次只回退「折叠时显示什么」。

---

## D46 · 对话页三处：删掉每行的 ⋮、`回到最新` 一步到底、折叠态也能加载更早
**用户裁决**（原话）：「那三个点彻底删掉，恢复原来的显示面积。那些功能我试了，长按都能出来。
大不了就是点空白的地方呗。」＋「下箭头这个。就是停在半路。这么简单的功能，怎么修这么多次修不好？」
＋「屏幕最上方，如果消息被折叠的话，加载历史对话根本就加载不出来呀。」

### 1. 删掉 ⋮（每条消息少占 32 dp）
`a15aba8`（2026-09-15「对话十项修复」）为了让**自由选字**生效（`SelectableContent`），把长按让给了系统
选择条，于是给每个有动作的块加了一个 ⋮（`BlockChrome.BlockMenuButton`，16 dp 图标 / 32 dp 热区）。
它的布局是 `Row { Box(weight 1f){内容}; ⋮ }`，所以**每条用户消息、每段助手正文、每张工具卡都少 32 dp 宽**。
用户实测「长按都能出来」，因此**整个删掉**：`BlockActionMenu` 去掉 `menuButton` 参数与那个 Row，
三处调用点（`UserMessageBlock` / `AssistantTextBlock` / `ToolBlockChrome`）同步去掉，`BlockMenuButton`
与 `MoreVert`/`IconButton` 两个 import 一并删。**文字选择保留不动** —— 长按卡片自身的 chrome
（留白、轨道、边距）仍然打开同一个菜单，这是那条 KDoc 一直写着的分工。取消它不构成 pi 的偏离：
pi 是终端，本来没有这个按钮。

### 2. `回到最新` 停在半路：是**投递**坏了，不是算术
`pinToTail()` 在「最后一行完全不在屏幕上」时给的是 `TailPin(tail, 0)` —— 让最后一行**贴着视口顶部**，
并指望**下一帧**再对齐它的底部（KDoc 原文："One extra frame"）。但那一帧只在跟随效应的**某个 key
变化**时才会到来（`LaunchedEffect(state.revision, state.streaming, renderedItems.size, scrolling, atBottom, tailPoke, …)`），
而在这条分支真正服务的情形里它**不变**：尾部行比视口高时，跳转前后 `!canScrollForward` 都是 false，
`requestScrollToItem` 又按设计**不开启滚动会话**，于是"下一帧"永远不来 —— 视图停在"最后一行贴着屏幕顶部、
最新几行还在折线以下"，再点也没用。**修法**：这一分支直接要**行末**（`PIN_TO_END_PX`，比任何内容都大的
偏移，测量阶段会夹到内容末端，见 `LazyListMeasure.kt:246-269`），一步到底，不依赖第二帧。常量取 `1 shl 24`
（约一万六千屏）：足够大，又远未到 `Int.MAX_VALUE`，测量阶段的偏移加法不会回绕。harness 除了钉这个值，
还钉了「一步之后不再要求第二次」（F2 + F2b）与「它仍然与偏移无关，所以去重仍然必要」（G9/G10）。

### 3. 折叠态加载不出更早：`earlierArmed` 只有一条武装边
武装条件原来只有一条：「离开窗口顶部」。而**行短到列表根本滚不动**时（折叠工具卡就是这个形状），
`atTop` 永远为真、那条边永不发生 → 一批之后 `hiddenCount` 永久 > 0，而到达会话**文件**的读取又被
`hiddenCount == 0` 把着门 → 两边都卡死，只剩手动点那一行能出来。**修法**：把「视口无处可滚」
（`!canScrollForward`）作为**第二条武装边**（纯函数 `reArmsEarlier`，harness 钉 H12–H16，其中 H16 把旧
行为当作缺陷本身断言下来）。产生的循环是**有界**的：每轮前置一批，一旦内容高过视口就停（或历史读完）
—— 也就是"把一屏填满"。

### 影响
`ui/blocks/{BlockChrome,UserMessageBlock,AssistantTextBlock,ToolBlockChrome}.kt`、
`ui/chat/TailFollow.kt`、`ui/screens/ChatScreen.kt`、`app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt`。
**颜色一处未动。** 只能上机看的：⋮ 删掉之后各块的宽度是否真的回来了（尤其工具卡的命令行与输出）、
`回到最新` 是否一按就贴底、以及折叠态下往上滑/点「加载更早」是否连续接出更早的内容。

---

## D47 · 附件：两条路线各是什么，以及删掉「已放入工作区」那条提示
**用户提问**（原话）：「我现在发送附件是把东西复制到工作区吗？走的什么路线？发送完为什么有个提示呢？
占住我的输入框了好几秒，去不掉，把这个提示删掉。」

### 两条路线（按 MIME 分，`ChatScreen.kt` 的 picker 回调）
- **图片**（`image/*`）→ **不复制任何东西**。`readBounded` 读进内存（有读取上限，避免先分配再判断），
  `compressAttachment` 按 pi 的参数压（最长边 2000、base64 < 4.5 MB、质量阶梯、每轮缩 0.75），
  `AttachmentBudget.decide` 按**本条消息的总量**判定，通过就进 `attachments`；发送时以
  **base64 内联**进 RPC 消息（pi 的 `ImageContent`）。所以图片是"随消息走"，工作区里不留副本。
- **非图片**（其余一切）→ **复制进工作区**：`copyIntoWorkspace` 写到
  `<workspace>/attachments/<清洗过的唯一文件名>`（名字做过清洗，并且落点被 `canonicalPath` 再校验一次
  必须在该目录内），然后把**相对路径**（`attachments/<name>`）当**文本**插进输入框 —— pi 的 agent 按
  这个路径自己去读，与用户手打一个路径完全同路。**不复制就不行**：App 手里只有 `content://` URI（pi 读不到），
  而"猜一个设备路径"会让 pi 报一个用户从没提过的文件。

### 删掉那条提示
`WorkspaceCopy.Copied` 原来会 `notifyUser("已放入工作区：<path>")`。删掉，理由有两条：**那句提示说的就是
"路径"，而路径这一刻已经落在输入框里**（信息重复）；而它走的是全应用那一条 M3 snackbar
（`ExtensionUiHost`，`Info` → `SnackbarDuration.Short`、`actionLabel = null`），画在底部、**压住输入框约 4 秒
且没有可点的关闭**。
**失败那三条提示全部保留**（读不到 / 太大 / 写不进）：它们解释"为什么什么都没复制"，
而输入框里没有路径可以替它们说话。

**影响**：`ui/screens/ChatScreen.kt` 一处（`WorkspaceCopy.Copied` 分支）。只能上机看：选一个非图片文件后
输入框里出现 `attachments/<name>`、工作区里确有该文件、且**不再弹那条提示**。

**补记（2026-09-17）· 关着的时候一次检查都不做**：上面那版是「每次 guest 启动前扫一遍配置表」＋「每次
都查 5 个 `.so` 在不在」。用户指出这是白做：「如果没打开，根本没必要检查。如果打开了，能用了，检查也没用。」
两条都对，所以：

- `RuntimeSelection.plan()` / `status()` 在开关**没开**时**根本不调** `missingProrootComponents()`，
  也**不扫**配置表 —— 判定函数的第一条就是开关，后面两个条件的结果在那种情况下必然被丢掉；
- 开关**开着**时，`missingProrootComponents()` 改成**每进程最多算一次**（键是 `nativeLibraryDir` 路径，
  与 `ProrootProbe.digestOf` 同一个套路）。理由是这五个文件来自**已安装的 APK**：一个进程活着的期间它们
  不可能出现或消失，换一套就是一次升级，而升级会杀掉进程 —— 所以它是"这次安装"的属性，不是"这次启动"的。
- 代价：关掉开关**之后**，之前 proroot 留下的那几份 `.proroot-config-*` 不再被立刻收走；下一次**打开**
  开关启动 guest 时收，或者运行环境 revision 变化时随 `wipe()` 一起没。条数上限本来就是 32 份 × 274 KB，
  这是有界的。
