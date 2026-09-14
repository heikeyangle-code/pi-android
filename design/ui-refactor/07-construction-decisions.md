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
