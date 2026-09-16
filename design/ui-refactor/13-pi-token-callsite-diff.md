# 13 · pi 官方源码「上色/上底」调用点逐点差异清单

> **性质**：只读审计 + 清单。除本文件外没有创建/修改/删除任何文件，没有 commit、push，没有跑 gradle。
> **口径**：本文只比**颜色与叠加**（前景 token、背景 token、bold / dim / italic / underline / inverse），**不比排版、尺寸、圆角、间距、文案**。
> **pi 侧 file:line 的来源**：全部是 pi 0.85.1 **实际发行的 `dist/*.js`**（`/tmp/node_modules/@earendil-works/pi-coding-agent/`），我逐个 grep 出来、再回读代码确认间接 token；不是从 `.ts` 源码或文档推的。因此下文 pi 的行号可以与 app 注释里的 `.ts` 行号不同——**以本文的 `.js` 行号为准**，两者指同一处。
> **裁决状态**：凡与冻结板子（`design-demos/direction-b-v2.html`）或 `07-construction-decisions.md` / `11-designer-adjudication.md` 冲突的，本文**显式标注**并给选项，一条都没写成"已经定了"。

---

## 1. 方法与覆盖面

### 1.1 pi 侧扫了多少

| 项 | 数 |
|---|---|
| `dist/**/*.js` 里含 `theme.fg(` / `theme.bg(` 的文件 | **46 个**（45 个核心 UI/工具渲染器 + 1 个示例扩展 `dist/extensions/llama/ui.js`，后者不是产品 UI）；另有 **1 个**文件 `components/daxnuts.js:112-124` 用局部别名 `t.fg(` 上色 5 处 → **触到主题 token 的文件共 47 个** |
| `theme.fg(` / `theme.bg(` **出现次数**（不含 `dist/bundle/chunks/chunk-JVUZSMYM.js` 这份打包副本、不含 `.map`） | **484 次**；加 `daxnuts.js` 的 5 次别名调用 = **489 处绘制调用** |
| 其中 token 是**字符串字面量**的 | 474 次 |
| 其中 token 是**变量/三元**、必须回读代码才能解析的 | **15 处**（逐条列在 §1.3） |
| 覆盖的目录 | `dist/modes/interactive/**`（`interactive-mode.js` 一个文件 85 处）、`dist/modes/interactive/components/**`、`dist/modes/interactive/theme/theme.js`、`dist/core/tools/renderers/*.js`、`dist/core/tools/render-utils.js` |
| 被用到的 token（去重） | **fg 33 个 / bg 7 个**（bg 是 `selectedBg`、`searchMatchBg`、`userMessageBg`、`customMessageBg`、`toolPendingBg`、`toolSuccessBg`、`toolErrorBg`）——另有 7 个 `thinking*` 只通过 `getThinkingBorderColor` 上色，已计入 fg |

**pi-tui 里没有 `theme.fg/bg`**：`/tmp/node_modules/@earendil-works/pi-tui/dist/**` 全文 0 处；组件库只**接收**样式回调（如 `markdown.js:271` 的 `this.defaultTextStyle.color`、`box.js` 的 `bgFn`、`components/image.js:78` 的 `fallbackColor`）。真正把 token 名绑成回调的地方全在 `theme/theme.js`，共 **7 个映射函数 / 54 条绑定**：

| 映射函数 | pi file:line | 条数 |
|---|---|---|
| `getMarkdownTheme()` | `theme.js:928-966` | 10（heading/link/linkUrl/code/codeBlock/codeBlockBorder/quote/quoteBorder/hr/listBullet） |
| `getSelectListTheme()` | `theme.js:967-974` | 5（selectedPrefix/selectedText/description/scrollInfo/noMatch） |
| `getEditorTheme()` | `theme.js:976-980` | 1（borderColor = `borderMuted`） |
| `getSettingsListTheme()` | `theme.js:982-989` | 5（label/value/description/cursor/hint） |
| `buildCliHighlightTheme()` | `theme.js:790-825` | 25（语法高亮 + emphasis/strong/link/addition/deletion） |
| `getThinkingBorderColor(level)` | `theme.js:242-262` | 7（`thinkingOff`…`thinkingMax`） |
| `getBashModeBorderColor()` | `theme.js:263-265` | 1（`bashMode`） |

回读这几张表之后，pi 每个 token 的语义都能落到具体构件上，下面所有判定都按它来。

### 1.2 我们侧扫了多少

| 项 | 数 |
|---|---|
| `app/src/main/kotlin/app/pi/ui/**` 里含取色的文件 | **69 个**（全部 169 个 .kt 里） |
| `palette.xxx` / `MaterialTheme.colorScheme.xxx` 出现次数 | **900 次** = `palette.*` **515** + `colorScheme.*` **385** |
| 被引用到的 `palette.*` 属性 | **59 个**（= `PiPalette` 声明的 56 + 3 个 export 全部 + 5 个派生 token；派生 token 也在内） |
| `MaterialTheme.colorScheme` 里出现最多的 5 个槽位 | `onSurfaceVariant` 130、`onSurface` 116、`primary` 40、`outline` 21、`surfaceContainerLow` 20 |

**必须知道的一件事**：我们 385 处 `colorScheme.*` 里有 **305 处其实是 pi token 的别名**（`PiTheme.kt:362-413` 的映射）：

| M3 槽位 | 解析成 | 判定 |
|---|---|---|
| `onSurface` | `text` | 别名，一致 |
| `onSurfaceVariant` | `muted` | 别名，一致 |
| `primary` / `surfaceTint` / `inversePrimary` | `accent` | 别名，一致 |
| `outline` | `borderMuted` | 别名，一致 |
| `outlineVariant` | `borderMuted @55%` | **派生**（pi 没有带 alpha 的 `borderMuted`） |
| `secondary` | `borderAccent` | 别名，一致 |
| `surfaceContainerLow` | `cardBg` | 别名，一致 |
| `surface` / `background` | `pageBg` | 别名，一致 |
| `tertiary` / `tertiaryContainer` / `onTertiaryContainer` | `customMessageLabel` / `customMessageBg` / `customMessageText` | 别名，一致 |
| `error` / `errorContainer` / `onErrorContainer` | `error` / `toolErrorBg` / `text` | 别名，一致 |
| **`surfaceContainer` / `surfaceContainerHigh` / `surfaceContainerHighest` / `surfaceBright` / `surfaceDim`** | `cardBg` 向 `text` 混 3%/6%/10%/12%，`pageBg` 混 2% | **派生，pi 没有对应 token**（`PiTheme.kt:390-399`） |

所以「我们用了 `onSurfaceVariant`」在颜色上**等于**用了 `muted`，不是差异；而「我们用了 `surfaceContainerHigh`」是**调色板外的颜色**，这类我会单独标 `派生`。

### 1.3 pi 里 15 处间接 token（回读结果）

| pi file:line | 表达式 | 解析出来的 token |
|---|---|---|
| `components/bash-execution.js:28` `:32` `:38` | `colorKey` | `!` 用 `bashMode`，`!!`（excludeFromContext）用 `dim`（`:26`） |
| `components/bash-execution.js:37` `:40` | `colorKey` | 同上（命令头 + spinner + 上下边框同源） |
| `components/config-selector.js:317` | `inherited ? "dim" : "accent"` | 继承的组头 `dim`，本层组头 `accent` |
| `components/config-selector.js:323` | `color`（`:322`） | 继承 `dim`，否则 `muted` |
| `components/model-selector.js:298` | `refreshStatusSuccess ? "success" : "muted"` | 刷新成功 `success` / 否则 `muted` |
| `components/scoped-models-selector.js:138` | `kind` | `success` / `error`（`:135-137`） |
| `components/session-selector.js:140` | `color`（`:139`） | status 消息 error→`error`，否则 `accent` |
| `components/session-selector.js:410` | `messageColor`（`:402-408`） | 删除确认 `error` / 当前会话 `accent` / 已命名 `warning` / 否则**不上色** |
| `components/session-selector.js:418` | `isConfirmingDelete ? "error" : "dim"` | 右侧（条数 + 时间）`error` 或 `dim` |
| `interactive-mode.js:1230-1234` | `d.type === "error" ? "error" : "warning"` | 资源诊断行 `error` / `warning` |
| `interactive-mode.js:1247` | `color = "mdHeading"`（默认参数） | 启动清单的段头 `mdHeading` |
| `interactive-mode.js:2867` | `color`（`:2866`） | managed-tool 状态：warning→`warning`，否则 **`dim`** |
| `theme.js:984-985` | `selected ? "accent" : …` | 设置列表：选中 label `accent`、未选中**不上色**；选中 value `accent`、未选中 `muted` |

---

## 2. 差异清单

排序规则（任务书第 4 步）：**用户看得见的面积 × 自定义主题下会不会暴露**。面积按 `12-color-area-and-scrollbar-plan.md` 的口径估；「暴露」看的是两套内置主题（`dark`/`light`）里两个 token 是否同值——**同值的差异只在用户导入自己写的主题时才看得见**，这类我单列在 §2.3。

判定用词：`一致` / `不一致（我们用了别的 token）` / `我们没有这个元素` / `我们有但 pi 没有（记录，不算错）`。

### 2.1 主表：两套内置主题下就看得见的差异

| # | pi 的画法（file:line · token · 叠加） | 我们的（file:line · token） | 判定 | 内置主题看得出来吗 | 建议 |
|---|---|---|---|---|---|
| **D1** | 压缩卡是**整块 `customMessageBg` 底的 Box**，`Box(1,1,bg)` → 满宽卡片；标签 `[compaction]` = `customMessageLabel` + **bold**（`custom-message` 系列：`compaction-summary-message.js:13`、`:29`、`:35-41`） | `ui/blocks/CompactionBlock.kt:73/81/93/101`：只把**一个 chip** 涂 `customMessageBg`（`:81`），卡片本体是页面底，上下是 `borderMuted@50%` 的 hairline（`:73`、`:93`），caption 用 `muted`（`:101`） | **不一致（着色范围 + 形状）** | **看得出来**（dark `#2D2838` vs `#18181E`，light `#EDE7F6` vs `#F8F8F8` 都是明显两块） | **需要裁决**（与板子冲突，见 §6-R1）。若按 pi：把 `:73-101` 的 hairline+chip 换成 `BlockCard(color = customMessageBg)`、去掉两条 hairline、caption 换 `customMessageText`。若保留 v2：本行记为「刻意保留的形态差」 |
| **D2** | 会话列表**当前会话**那 1 行：**会话名**染 `accent`（`session-selector.js:402-408` 的 `isCurrent → messageColor="accent"`，`:410` 上色） | `ui/screens/SessionsScreen.kt:790-797` 会话名 = `onSurface`（= `text`）；当前是靠徽标说（`:832-870` 的 `CurrentBadge`，`primary` = `accent`） | **不一致（我们用了别的 token）** | **看得出来**（dark `#D4D4D4` vs `#8ABEB7`） | **需要裁决**：`11-designer-adjudication.md:735-739` 的 KDoc 已经明确「按 11 的规则外观以 v2 为准，当前靠徽标」。**不要自动改**；若要 1:1，改法是 `:790-796` 的 `color` 改成 `if (active) palette.accent` 并把徽标降级为纯标记。风险：一屏两个 accent（徽标 + 名字）→ 违反 `11` §C「一屏一个 accent」的替代方案与 `12 §6.4` 的面积计算 |
| **D3** | 助手错误句一律 `error`：`assistant-message.js:140`（截断）、`:148`（中止）、`:153`（错误）；工具错误 `edit.js:67`、`write.js:122`、`interactive-mode.js:2232/:3522` 也都是 `error` | `ui/blocks/ErrorBlock.kt:82` 的错误**句子** = `palette.text`（只有 `✗`/「出错了」/「详情」用 `error`，`:64/:71/:102/:105`） | **不一致（我们用了别的 token）** | **看得出来**（`text` `#D4D4D4` vs `error` `#CC6666`） | **建议改**（面积 = 卡上一整句）：`ErrorBlock.kt:80-83` 的 `color = palette.text` → `palette.error`。依据：pi 没有任何一处把错误文字画成 `text`。冲突：板子 `06 §2` 错误块正文写的可能是正文色 → 见 §6-R2 |
| **D4** | 启动画面（first-time-setup）：logo + 欢迎句 = `accent`，欢迎句另加 **bold**（`first-time-setup.js:32`、`:34`）；选项未选中 = `text`（`:61`） | `ui/screens/BootScreen.kt:105` 标题 = `palette.text`、`:112` 副标题 = `palette.muted`、`:183` = `text`、`:201` = `muted`；进度条填充 = `accent`（`:328`） | **不一致（我们用了别的 token）** | **看得出来**（`#8ABEB7` vs `#D4D4D4`/`#808080`） | **需要裁决**（品牌行归 v2，见 §6-R3）：boot 屏不是 pi 的那一屏（pi 的是首启向导），板子自己有品牌稿。**建议不改实现**，只在文档里记清 pi 的原 token；若用户要求 1:1，改 `:105` → `accent` + bold、`:112` → `accent`（去掉 muted） |
| **D5** | `!` 命令盒的**输出正文** = `muted`（`bash-execution.js:106`、`:111`）；**完成态**里 `(exit N)` = `error`、`(cancelled)` = `warning`、截断提示 = `warning`（`:147`、`:150`、`:155`） | `ui/chat/BashPanel.kt:94` 输出正文 = `toolOutput`；状态行 `:102-105` = `muted` / `warning` / `success` / `error` | **不一致（输出正文：pi `muted` vs 我们 `toolOutput`）** → 见 §2.3 的 C4（内置主题同值）；状态行的 `success` 档属「我们有但 pi 没有」（§5.2，pi 的成功没有专门的颜色） | 输出正文：**看不出来**（dark 两者都是 `#808080`，light 都是 `#6C6C6C`）；状态行的 `success` **看得出来** | 正文改法见 C4；状态行不建议动。**`bashMode` 两条全宽边框 + 命令头已经排期**，不在本行 |
| **D6** | 会话树每行的**类型前缀**按类型上色：`user:` = `accent`、`assistant:` = **`success`**、toolCall/toolResult = `muted`、bash/其它 role = `dim`、`[compaction]` = **`borderAccent`**、`[branch summary]` = **`warning`**、`[model:]`/`[thinking:]`/`[custom:]`/`[label:]`/`[title:]` = `dim`（`tree-selector.js:623`、`:629`、`:632/636/639`、`:646`、`:649`、`:654`、`:657`、`:673`、`:677`、`:680-694`）；选中行再 **bold**（`:699`） | `ui/chat/SessionTreeScreen.kt:793-798` 一律 `colorScheme.primary`（= `accent`）+ 无 bold | **不一致（我们用了别的 token，且丢了按类型分色）** | **看得出来**（`success` `#B5BD68`、`warning` `#FFFF00`、`borderAccent` `#00D7FF` 与 `accent` `#8ABEB7` 各不相同） | **建议改**（面积小但一屏 11 行都受影响）：把 `entryLabel(entry)` 从「返回字符串」改成返回 `(String, Color)`，按上表映射。**风险**：`SessionTreeScreen` 属 `12 §6.2` 的红线清单（会话加载批次在写）→ **只列不改**，等该批次落地 |
| **D7** | 思考块的**正文** = `thinkingText` + **italic**（`assistant-message.js:113`、`:115`）；思考块的**标题/等级** pi **不用 thinking 色阶**——pi 的 `thinkingOff…thinkingMax` **7 个 token 只喂编辑器边框**（`theme.js:242-262`）+ footer 里等级是 `dim`（`footer.js:204-206` 把整行 dim） | `ui/blocks/ThinkingBlockBlock.kt:41` `pen = palette.thinking(level)` → `:58` 3dp 色条、`:80-81` 等级标签都用色阶；正文 `:73/:84/:118` = `thinkingBodyOnCanvas`（→P6 后 `thinkingText`） | **不一致（色阶的使用范围：pi 只在编辑器边框，我们还用在色条 + 等级标签）**；正文那一档 P6 后一致 | **看得出来**（`thinkingMedium` `#81A2BE` / `thinkingHigh` `#B294BB` 与 `thinkingText` `#808080` 不同） | **记录 + 建议保留**（§5.2）：这一处是 app 自己把 `getThinkingBorderColor` 的语义从「编辑器边框」扩到「转录里的等级标记」，`07`/`12` 都没裁过。可以留；若用户要求严格 1:1，就把 `:58`/`:80-81` 改成 `thinkingText`（色条会与正文同灰，等级标记失去唯一的颜色通道） |
| **D8** | 引擎状态 / 通知类单行消息：managed-tool 非 warning 状态 **`dim`**（`interactive-mode.js:2866-2867`）、`Warning:` 前缀 `warning`（`:3527`）、错误 `error`（`:2232`） | `ui/blocks/NoticeBlock.kt:59-61` 三档 = `muted` / `warning` / `error`；正文 `:99` = `muted` | **不一致（Info 档：pi `dim` vs 我们 `muted`）** | **看得出来**（dark `#666666` vs `#808080`，浅色 `#767676` vs `#6C6C6C`） | **建议改**（面积小）：`NoticeBlock.kt:59` `Notice.Tone.Info -> palette.muted` → `palette.dim`。依据 `interactive-mode.js:2867`。注意 `:86` 的标题 metaOnCanvas 在 P6 会变 `muted`，两者会撞成同色——改的时候要一起看 |
| **D9** | 卡片展开/收起提示的**键名**是 `dim`（`read.js:72`、`keybinding-hints.js:31` 的 `fg("dim", key) + fg("muted", " desc")`、`compaction-summary-message.js:40`、`branch-summary-message.js:39`、`skill-invocation-message.js:42` 都是 `dim`），描述文字 `muted` | `ui/blocks/BlockChrome.kt:366` `ExpandLabel` 默认 `tint = palette.muted`（一个色，键名/描述不分） | **不一致（我们用了别的 token；且我们的提示里没有键名可 dim）** | **看得出来**（`#666666` vs `#808080`） | **建议保留 + 记录**（§5.2）：我们的提示是中文「展开 / 收起」，pi 的是 `ctrl+o to expand`；`dim` 在 pi 里给的是**键名**这一半，我们无对应物。若要贴 pi，可在「展开」后加一个 `dim` 的快捷键名（需要产品先决定用哪个键） |
| **D10** | 扩展对话框标题：`accent` + **bold**（`extension-selector.js:29`、`extension-input.js:32`、`extension-editor.js:40`）；选项未选中 = `text`、选中 = `accent` + 光标 `accent`（`:52-53`） | `ui/extension/ExtensionDialogs.kt:162` / `:289` 的标题走 `ExtensionSpans(defaultColor = palette.text)`（`:205`、`:306`），只有 `!` 前缀是 `warning`（`:161`、`:288`）；选项未选中 `text`（`:205`）✓ | **不一致（标题：pi `accent`+bold vs 我们 `text`）**；选项一档 **一致** | **看得出来**（`#8ABEB7` vs `#D4D4D4`） | **需要裁决**（`11` D-5 已定「扩展对话框与设置对话框共用同一个构件，颜色不新造」）。改法：`:305-307` 的 `defaultColor` → `palette.accent` + `fontWeight`；**但**扩展自己给的 ANSI 颜色优先，改了只影响「扩展没给色」的那些标题（即大多数） |
| **D11** | 会话列表 `[label]`/分支标签：`warning`（`tree-selector.js:593`）；会话树的 label 行 = `warning` | `ui/chat/SessionTreeScreen.kt:800-816` 用 `tertiaryContainer` 底 + `onTertiaryContainer` 字的小徽标（= `customMessageBg` / `customMessageText`） | **不一致（我们用了别的 token）** | **看得出来**（`#FFFF00` 的警示色 vs 一块紫底） | **需要裁决**：我们的是 v2 的「徽标」构件（`06 §2` 徽标），pi 是纯文字着色。两者形态不同 → 归 §5.2「我们有但 pi 没有」更准确；这条列在这里只是提醒**颜色语义不同**（警告色 vs 自定义消息色），不建议为它换掉徽标 |
| **D11b** | 信任提示（`trust-selector.js`）：标题 = `accent` + **bold**（`:32`）、cwd = `muted`（`:33`）、已保存决定/当前状态 = `muted`（`:35`、`:36`）、**选项标签：选中 = `accent` + 光标 `accent`，未选中 = `text`**（`:64-66`） | `packages/PiProjectTrustPrompt.kt`：提示文 `:89` = `muted` ✓、说明 `:95` = `warning`（pi 无对应物）、**选项色调 `:99` = `success` / `error`**、整行底 `:105` = `selectedBg@45%`、副标题 `:134` = `muted` ✓、脚注 `:143` = `dim` | **不一致（选项标签：pi `accent`/`text` + 光标，我们 `success`/`error`）**；标题的 `accent`+bold 我们**没有**（pi 有） | **看得出来**（`success` `#B5BD68` / `error` `#CC6666` vs `accent` `#8ABEB7` / `text` `#D4D4D4`） | **需要裁决**：我们的 `success`/`error` 是「信任 = 绿 / 不信任 = 红」的语义编码（比 pi 更直白），但它就是「我们用了别的 token」。三个选项：①改实现（标签 → 选中 `accent`、未选中 `text`，色调只留给 `:99` 之外的地方）；②改稿子；③不改（记为刻意的语义强化）。**面积小（一张对话框）**，我倾向 ③ |

### 2.2 次表：结构性/范围类差异（颜色对、画的范围不对）

| # | pi 的画法 | 我们的 | 判定 | 内置主题下 | 建议 |
|---|---|---|---|---|---|
| **D12** | 工具错误卡的**底** = `toolErrorBg`（`tool-execution.js:218-221` 的 `bgFn` 三选一 + `:47` 的 Box） | `ui/blocks/ErrorBlock.kt:50` = `toolErrorBg` ✓，但描边另加 `error@45%`（`:51`） | 底 **一致**；描边 **我们有但 pi 没有** | 看得出来（多一圈红线） | 保留（v2 的 35–45% 状态描边，`06 §2`） |
| **D13** | 工具卡：pi **只有底色，没有描边**（`tool-execution.js:47`、`:218-221`） | `ui/blocks/ToolBlockChrome.kt:550` `toolAccentColor(...).copy(alpha = 0.35f)` 描边、`:156` ToolRail 状态轨 | **我们有但 pi 没有（记录，不算错）** | 看得出来 | 保留（v2 冻结） |
| **D14** | 用户消息气泡 = **满宽** `userMessageBg`（`user-message.js:29` 的 `Box(outputPad,1,bg)`），正文 `userMessageText`（`:31`）；**pi 不画时间戳** | `ui/blocks/UserMessageBlock.kt:110` `userMessageBg` ✓、`:124` `userMessageText` ✓、`:146` 时间 = `palette.muted`（pi 无对应物） | 底色/正文 **一致**；时间戳 **我们有但 pi 没有** | 时间戳看得出来 | 保留 |
| **D15** | diff 卡：**整行**单色（符号+行号+正文同一个 `fg`）：上下文 `toolDiffContext`、删 `toolDiffRemoved`、增 `toolDiffAdded`（`diff.js:78/113/116/122/127`、`107/108`） | `ui/blocks/DiffBlock.kt:267-271` 整行映射 Added/Removed/else，`:287-288` 一次上色 ✓；行号列与正文同色 ✓ | **一致**（`12` D-2 已落地） | — | 无需改 |
| **D16** | 工具名 = `toolTitle` + bold（`tool-execution.js:91`、`:316`；各 renderer 的 `toolTitle`+bold，如 `read.js:27`） | `ui/blocks/ToolBlockChrome.kt:329` `palette.toolTitle` + `FontWeight.Bold` ✓（diff 卡 `DiffBlock.kt:137` 同） | **一致（已落地，见 §3）** | — | — |
| **D17** | 工具路径 = `accent`（`render-utils.js:57-63`），无效参数 = `error`（`:55`），缺参数 = `toolOutput` `...`（`:62`） | `ui/blocks/ToolBlockChrome.kt:208-215` 的 `ToolCallToken` 三档同 token ✓ | **一致（已落地/在排期）** | — | — |
| **D18** | 截断提示 `... (N more lines, …)` = `muted`（`read.js:99`、`grep.js:39`、`ls.js:31`、`find.js:37`、`write.js:106`、`tool-execution.js:103`） | `ReadBlock.kt:112/123/128`、`GrepBlock.kt:124/139/144`、`WriteBlock.kt:91/111/127`、`PathListBlock.kt:191/206/211`、`ShellBlock.kt:130`、`ToolCallBlock.kt:163` 全部 `palette.muted` ✓ | **一致** | — | — |
| **D19** | 截断/超限警告 `[Truncated: …]` = `warning`（`read.js:104/107/110`、`grep.js:53`、`find.js:48`、`ls.js:42`、`write.js:92/122` 用 `error`） | `ui/blocks/ToolBlockChrome.kt:506` `ToolNotice` = `palette.warning` ✓；空内容错误 `WriteBlock.kt:85/121` = `palette.error` ✓ | **一致** | — | — |
| **D20** | 语法高亮 25 槽（`theme.js:790-825`）：keyword→`syntaxKeyword`、built_in/class/type→`syntaxType`、literal/number→`syntaxNumber`、regexp/string→`syntaxString`、comment/doctag→`syntaxComment`、meta→`muted`、function/title→`syntaxFunction`、tag→`syntaxPunctuation`、name→`syntaxKeyword`、attr/variable/params→`syntaxVariable`、operator→`syntaxOperator`、punctuation→`syntaxPunctuation`、addition→`toolDiffAdded`、deletion→`toolDiffRemoved` | `ui/render/PiCodeHighlight.kt:74-85` 12 类一对一映射到同 token ✓ | **一致** | — | — |
| **D21** | Markdown 10 槽（`theme.js:928-939`）：heading/link/linkUrl/code/codeBlock/codeBlockBorder/quote/quoteBorder/hr/listBullet | `ui/render/PiMarkdownTheme.kt:265-266`（`mdHeading`/`text`）、`:283-284`（`mdCodeBlock`/`mdCode`）、`:285`（`mdQuote`）、`:288`（`mdListBullet`）、`:291`（`mdLink`）、`:189`（`mdHr`）✓；`PiMarkdownComponents.kt:431/498/506` = `mdCodeBlockBorder` ✓ | **一致** | — | — |
| **D22** | H1 = `mdHeading` + **bold + underline**（`pi-tui/dist/components/markdown.js:340`）、H2+ = `mdHeading` + **bold**（`:343`）；引用 = `mdQuote` + **italic**（`:417`）；行内码 = `mdCode`（`:527`）；链接 = `mdLink` + **underline**（`:531`）；列表符号 = `mdListBullet`（`:606`）；**表头 = bold**（`:775`） | `ui/render/PiMarkdownTheme.kt:272-273` H1 有 underline ✓、H2+ 无 underline ✓（`SemiBold` 对应 pi 的 bold）；`:284` 行内码 `mdCode` ✓；`:291` 链接 `mdLink` + underline ✓；`:288` 列表符号 `mdListBullet` ✓；表头 bold ✓（库的 `MarkdownTable.kt:154` 自己 `style.copy(fontWeight = Bold)`）；**`:285` 引用只有 `mdQuote`、没有 italic** | 引用 italic **不一致（丢了叠加）** | 看得出来（斜体） | **建议改**：`PiMarkdownTheme.kt:285` 的 `quote` 加 `fontStyle = FontStyle.Italic`。已核实：库的 blockquote 只从 `quote` 取**颜色**画竖条（`MarkdownBlockQuote.kt:34-38`），加 italic **不会**改变竖条 |
| **D22b** | 引用竖条 = **`mdQuoteBorder`**（`pi-tui markdown.js:450` 的 `quoteBorder("│ ")`），引用文字 = `mdQuote`（`:417`） | `PiMarkdownTheme.kt:285` 的 `quote` 同时喂竖条与文字（库 `MarkdownBlockQuote.kt:36` 用 `style.color` 画 bar）→ 竖条实际是 `mdQuote` | **不一致（我们用了别的 token）** | **看不出来**：dark `mdQuote`=`mdQuoteBorder`=`#808080`；light 都是 `#6C6C6C` | **已经知道、已经写在代码里**：`PiMarkdownTheme.kt:101-110` 自己记了这条折衷（「两个内置主题同值，手写主题才丢区分」）。**不建议改**（库把 bar 与文字绑在一个 style 上，除非不用库的 blockquote）。仅在此登记 |
| **D22c** | 链接后的 ` (href)` = `mdLinkUrl`（`pi-tui markdown.js:547`），但**只在终端不支持 OSC 8 超链接时**；支持时 pi 也只印链接文字（`:531-536`） | 我们不印 `mdLinkUrl`（`PiMarkdownTheme.kt:97`：选择"pi 的可超链接分支"） | **我们没有这个元素（选了 pi 的另一条分支）** | 若对到 pi 的 fallback 分支则看得出来（`mdLinkUrl` = `dim` = `#666666`）；对到 hyperlink 分支则**无差异** | 保留。**注意**：pi 走哪条分支取决于运行终端的 `getCapabilities().hyperlinks`（`pi-tui markdown.js:532`），我们的引擎跑在 PTY 里通常拿不到 OSC 8 支持 → pi 的"实际行为"可能是 fallback。这是我们的**主动选择**而非取色错，登记即可 |
| **D23** | Mermaid：border→`borderMuted`、text→`text`、edge→`accent`、edge label→`muted`、title→`accent`+**bold**（`mermaid.js:29-37`） | `ui/render/PiMermaid.kt:136-140` 五个类别**逐一对应同 token** ✓（含 title 的 bold） | **一致** | — | — |
| **D24** | 工具卡正文 = `toolOutput`（`read.js:97`、`write.js:104`、`grep.js:37`、`ls.js:29`、`find.js:35`、`bash.js:48`、`tool-execution.js:101`） | 18 处走 `palette.bodyOnTool`（派生，`12` 附录 B.2） | **不一致 → 已排期 P6**（`12 §6.2`） | — | 见 §3 |
| **D25** | diff 上下文 = `toolDiffContext`（`diff.js:78/127`） | 2 处走 `contextOnTool` | **不一致 → 已排期 P6** | — | 见 §3 |
| **D26** | 思考正文 = `thinkingText`（`assistant-message.js:115`） | 3 处走 `thinkingBodyOnCanvas` | **不一致 → 已排期 P6** | — | 见 §3 |
| **D27** | 元信息 = `muted` / `dim` | 3 处走 `metaOnCanvas`/`metaOnCard` | **不一致 → 已排期 P6** | — | 见 §3 |
| **D28** | 被拒态 = 无此态（pi 的工具卡只有 pending/success/error 三态，`tool-execution.js:218-221`） | `ui/theme/PiStateChip.kt:120` `StateTone.Rejected -> palette.bodyOnTool`（P6 后目标待定） | **我们有但 pi 没有**；P6 的连带问题 | — | 见 §3 + §6-R4 |

### 2.3 只有自定义主题才暴露的差异（**两套内置主题里两个 token 同值**）

这一节的每一条，在 `dark`/`light` 下**完全看不出来**（两 token 同值），但用户导入一份自己写的主题（pi 的主题是用户手写的 JSON，`~/.pi/agent/themes/`）就会露出来。用户的原话是「能和 pi 一模一样的，全都一模一样。对照他的代码」——这一节正是这句话最直接的落点。

> **两条例外**：`C7`（grep 组头）与 `C11`/`C12`（模型屏、设置列表）**内置主题下也看得出来**，放进来只是因为它们与相邻条目同源，每行的「内置主题下看出来吗」列已各自标明。**真正「只有自定义主题才暴露」的是 C1–C6、C8–C10 与 D22b。**

| # | pi 的画法（file:line · token） | 我们的（file:line · token） | 判定 | 内置主题下看出来吗（值相等证明） | 建议 |
|---|---|---|---|---|---|
| **C1** | Hook/自定义消息卡的**正文 markdown** 明确传 `customMessageText`（`custom-message.js:83` 的 `color: (text) => theme.fg("customMessageText", text)`） | `ui/blocks/HookMessageBlock.kt:73-76` `PiMarkdownText(...)` **不传 `textColor`** → 落到 `palette.text`（`PiMarkdownTheme.kt:265-266` 的 `textColor ?: palette.text`） | **不一致（我们用了别的 token）** | **看不出来**：dark `customMessageText`=`#D4D4D4`=`text`；light 两者都 `#1F2328` | **建议改**：`:75` 加 `textColor = palette.customMessageText`。这与同族卡 `SkillInvocationBlock.kt:120` 已经在做的完全一致——**同一族四张卡里，只有这一张没传** |
| **C2** | 压缩卡正文 `customMessageText`（`compaction-summary-message.js:35`） | `ui/blocks/CompactionBlock.kt:123-127` 不传 `textColor` → `palette.text` | **不一致** | **看不出来**（同 C1 的两个值） | **建议改**：`:126` 后加 `textColor = palette.customMessageText`。与 D1 是同一张卡的两件事，可以一起做 |
| **C3** | 分支摘要卡正文 `customMessageText`（`branch-summary-message.js:34`） | `ui/blocks/BranchSummaryBlock.kt:112-115` 不传 `textColor` → `palette.text` | **不一致** | **看不出来** | **建议改**：`:114` 后加 `textColor = palette.customMessageText` |
| **C4** | `!` 命令盒**输出正文** = `muted`（`bash-execution.js:106`、`:111`） | `ui/chat/BashPanel.kt:94` = `palette.toolOutput` | **不一致** | **看不出来**：dark `muted`=`toolOutput`=`#808080`；light 都是 `#6C6C6C` | **建议改**：`:94` → `palette.muted`。**但**：`12 §6.3` 刚刚把 `!!` 面板的边框/命令头排期成 `bashMode`/`dim`，同一次落地更省事 |
| **C5** | 技能卡**名字** = `customMessageText`（`skill-invocation-message.js:41`） | `ui/blocks/SkillInvocationBlock.kt:88` = `palette.text` | **不一致** | **看不出来** | **建议改**：`:88` → `palette.customMessageText`（与它下面 `:120` 的正文同 token，pi 也是同一个） |
| **C6** | grep/find/ls 的**结果行**全部 `toolOutput`（`grep.js:37`、`find.js:35`、`ls.js:29`） | `ui/blocks/GrepBlock.kt:205`：命中行 `contextOnTool`、非命中 `bodyOnTool`；P6 后 = `toolDiffContext` / `toolOutput` | **不一致（context 行用了 diff 的 token）** | **看不出来**：dark `toolDiffContext`=`toolOutput`=`#808080`；light 都是 `#6C6C6C` | **建议改**：`:205` 去掉 `context` 分支，全部用 `toolOutput`。依据：pi 的 grep 渲染器**不区分上下文行**，`toolDiffContext` 全 pi 只出现在 `diff.js` |
| **C7** | grep 的 ` in <path>` = `toolOutput`（`grep.js:22`）；pi **没有**按文件的组头 | `ui/blocks/GrepBlock.kt:174` 的**组头路径** = `palette.text` | **我们有但 pi 没有（记录，不算错）** | **看得出来**（dark `text` `#D4D4D4` vs `toolOutput` `#808080`）——所以这一条**严格说不属于本节**，放在 C6 旁边只是为了把 grep 的两处讲完 | 保留：`:170-173` 的 KDoc 已说明组头是 v2 的 `c-text`，且 pi 无对应构件。若要更贴 pi 的语义（路径 = `toolOutput`），改 `:174` 即可，但那会让文件路径与它下面的命中行同灰 |
| **C8** | 会话树行的**树前缀** = `dim`（`tree-selector.js:598` 的 `fg("dim", prefix)`）；会话列表行的前缀也是 `dim`（`session-selector.js:415`） | `ui/chat/SessionTreeScreen.kt` 的缩进是纯空白（无色）；`SessionsScreen.kt` 无前缀 | **我们没有这个元素**（我们的缩进是几何，不是字符） | — | 无需改 |
| **C9** | 会话树的**折叠记号** `⊞` 与**活动路径记号** `•` = `accent`（`tree-selector.js:589`、`:592`） | `ui/chat/SessionTreeScreen.kt:770-779` 活动路径是 **2dp 竖线**，`primary` = `accent` ✓（同色不同形） | 颜色 **一致**，形态 **我们有但 pi 没有** | — | 保留（`12 §6.4` 已裁决） |
| **C10** | 会话树的时间戳 = `muted`（`tree-selector.js:595`） | `SessionTreeScreen.kt:829` 摘要 = `onSurfaceVariant` = `muted` ✓ | **一致** | — | — |
| **C11** | 模型选择器：光标 `→ ` = `accent`、当前 `✓ ` = `accent`、选中 id = `accent`、provider 徽标 = `muted`、`· default` = `muted`、`(i/n)` = `muted`、错误行 = `error`、刷新成功 = `success`（`model-selector.js:267-298`） | 我们没有 pi 形态的模型选择器；`ui/settings/PiModelsScreen.kt:279-500` 用 `onSurface`/`onSurfaceVariant`/`primary`/`tertiary` | **我们没有这个元素（形态不同）**；`tertiary`（=`customMessageLabel`）用于「等待重启」= **我们有但 pi 没有** | 看得出来 | 归 §5.2；不建议为它重做模型屏 |
| **C12** | 设置列表（`getSettingsListTheme`，`theme.js:982-989`）：选中 label = `accent`、未选中 label **不上色**（终端默认 = `text`）、选中 value = `accent`、未选中 value = `muted`、description = `dim`、cursor = `accent`、hint = `dim` | `ui/settings/PiSettingsRows.kt:264/290/325/347/371/377` 用 `onSurface`（=`text`）/`onSurfaceVariant`（=`muted`）/`selectedBg` 命中底；`PiSettingsStyle.kt:295-301` 选中态 = `onSurface` vs `muted` | **不一致（选中态：pi 用 `accent`，我们用 `text`/`muted` + `selectedBg` 底）** | **看得出来**（`accent` vs `text`） | **需要裁决**：设置屏是 v2 自己的一整套（`11` A-09/A-11、`12 §6.4` 的 selectedBg 裁决都在这一片）。**不建议动**；若要 1:1，改法是 `PiSettingsStyle.kt:301` 的 `onSurface` → `palette.accent`。风险：设置屏已经有 `selectedBg` 底 + accent 命中条两处 accent，第三处会让整屏 accent 过量 |

**§2.3 的小结**：C1–C6 是**六处可以直接改、零观感风险**的（改前改后内置主题完全一样，只有导入自定义主题才不同）。这六处是本清单里性价比最高的部分，建议优先落地。

---

## 3. 已排期（只列不评，不重复建议）

1. **工具卡头部参数区分段上色**（`read`/`write`/`edit`/`ls` 路径 = `accent`、`read` 的 `:1-50` = `warning`、`grep`/`find` 的 pattern = `accent` 而 ` in <path>`/`(glob)`/`(limit N)` = `toolOutput`、`bash` 整行 `$ command` = `toolTitle` + bold、缺参数 `...` = `toolOutput`）——**另一个代理正在做**。pi 依据：`render-utils.js:55/62/63`、`read.js:23/27`、`grep.js:19-26`、`find.js:18-23`、`ls.js:15-17`、`write.js:90`、`edit.js:53`、`bash.js:29-31`。我们的现状：`ToolBlockChrome.kt:191-216` 的 `ToolCallToken` + `:298-309` 的 span 渲染已经具备机制。
2. **工具名 = `toolTitle` + 粗体**（`ToolBlockChrome.kt:320-338`、`DiffBlock.kt:132-138`）——**已落地**。
3. **撤掉 5 个对比度修正 token**（`bodyOnTool→toolOutput`、`contextOnTool→toolDiffContext`、`thinkingBodyOnCanvas→thinkingText`、`metaOnCanvas→muted`、`metaOnCard→dim`）——已裁决，排在 P6（26 个消费点 / 18 个文件，清单在 `12` 附录 B.2）。
4. **`!` 面板补 pi 的两条全宽 `bashMode` 边框 + 命令头**（`BashPanel.kt:51-62`）——已裁决。
5. **对话转录视口加滚动条**（只这一处；`scrollbarTrack`/`scrollbarThumb`）——已定稿。pi 依据：`interactive-mode.js:629-630` 接线、`tui-renderer.js` 之外的几何在 `pi-tui/dist/layout.js`。
6. **列表光标行用 `selectedBg`**（会话列表「正在操作的那一行」；会话树不加）；下箭头「回到最新」= `selectedBg` 底 + `text` 字形（pi 依据 `tui-renderer.js:17`）；上箭头保持现状。pi 依据：`session-selector.js:419-421`。
7. **搜索高亮保持「整行底」**（用户裁决，不改）；`infoBg` 不动。pi 依据：`tui-renderer.js:9/11/12`。

---

## 4. 一致的部分（用来证明覆盖到了）

| 元素 | pi | 我们 | 覆盖的 token |
|---|---|---|---|
| 用户消息气泡 | `user-message.js:29/31` | `UserMessageBlock.kt:110/124` | `userMessageBg`、`userMessageText` |
| 助手正文**无底色** | `assistant-message.js` 全文无 `theme.bg(` | `AssistantTextBlock.kt:76-93` 无底色容器 | —（`text` 一档见 C 说明） |
| 工具卡三态底 | `tool-execution.js:47/218-221` | `ToolBlockChrome.kt:105-110`、`:546` | `toolPendingBg` / `toolSuccessBg` / `toolErrorBg` |
| 工具名 | `tool-execution.js:91/316` + 各 renderer | `ToolBlockChrome.kt:329` | `toolTitle` + bold |
| 工具路径/无效参数 | `render-utils.js:55-63` | `ToolBlockChrome.kt:208-215` | `accent` / `error` / `toolOutput` |
| 截断提示 | `read.js:99`、`grep.js:39`、`ls.js:31`、`find.js:37`、`write.js:106`、`tool-execution.js:103` | `ReadBlock/GrepBlock/WriteBlock/PathListBlock/ShellBlock/ToolCallBlock` 的 `muted` | `muted` |
| 截断警告 | `read.js:104/107/110`、`grep.js:53`、`find.js:48`、`ls.js:42` | `ToolBlockChrome.kt:506` | `warning` |
| diff 整行上色 | `diff.js:78/107/108/113/116/122/127` | `DiffBlock.kt:267-271`、`:287-288` | `toolDiffContext` / `toolDiffRemoved` / `toolDiffAdded` |
| 代码块边框 | `theme.js:935` → `pi-tui markdown.js:384/398` | `PiMarkdownComponents.kt:431/498/506` | `mdCodeBlockBorder` |
| 语法高亮 25 槽 | `theme.js:790-825` | `PiCodeHighlight.kt:74-85` | 9 个 `syntax*` + `muted` + 2 个 diff |
| Mermaid 5 类 | `mermaid.js:29-37` | `PiMermaid.kt:136-140` | `borderMuted`/`text`/`accent`/`muted` |
| Markdown 10 槽 | `theme.js:928-939` | `PiMarkdownTheme.kt:189/265-266/283-291` | 全部 `md*` |
| 编辑器边框 = 思考等级 / `!` | `theme.js:242-265`、`interactive-mode.js:3417-3423` | `ChatScreen.kt:2682-2686` | `thinking(level)` / `bashMode` |
| 上下文百分比三档 | `footer.js:140/143`（>90 `error`、>70 `warning`） | `PiCommon.kt:166-169`、`:358-361` | `error` / `warning` / `accent`（环填充）/ `muted`（静息） |
| 页脚整行 dim | `footer.js:204/206/207` | `PiCommon.kt:216` 等 | `dim` / `muted` |
| 键名 dim + 描述 muted | `keybinding-hints.js:31` | 见 D9（我们有提示、无键名） | `dim` / `muted` |
| 选择列表主题 | `theme.js:967-974` | `SessionTreeScreen.kt:796`（`primary`）、`ExtensionDialogs.kt:205`（`text`） | `accent` / `muted` 部分覆盖 |
| 信任提示 | `trust-selector.js:32/33/35/36/64/65/66`（标题 `accent`+bold、cwd/状态 `muted`、选中 `accent`、未选中 `text`） | `packages/PiProjectTrustPrompt.kt:89/134`（`muted`）✓、`:143`（`dim`）✓；选项见 **D11b** | `muted` / `dim` 一档一致，`accent`/`text` 一档列 D11b |
| 错误消息 | `assistant-message.js:140/148/153`、`interactive-mode.js:2232/3522` | `ErrorBlock.kt:64/71` + D3 | `error` |
| 启动品牌行 | `interactive-mode.js:654`（logo `accent`+bold、版本 `dim`）、`first-time-setup.js:32/34` | `BootScreen.kt:105/112`（见 D4） | 不一致，列 D4 |
| 滚动条接线 | `interactive-mode.js:629-630` | 见 §3-5（未实现） | `scrollbarTrack` / `scrollbarThumb` |
| 搜索命中 | `tui-renderer.js:9/11/12` | `ChatScreen.kt:1525-1528` | `searchMatchBg` / `searchMatchText`（粒度不同，已裁决保持） |
| 扩展 ANSI→token | `theme.js:323-327` 的 `fg` 返回 SGR | `ui/extension/ChromeColor.kt:111-130` | 全 59 token 最近邻匹配 |
| export 三色 | `theme.js:768-790`（只喂 HTML 导出） | `PiTheme.kt:382-399` | `pageBg` / `cardBg` / `infoBg` |

---

## 5. 我们没有、pi 有的元素 / 我们有、pi 没有的元素

### 5.1 我们没有、pi 有的元素

| pi 元素 | pi file:line | 说明 | 是否要补 |
|---|---|---|---|
| 「working / retry / compaction / branchSummary」四个 spinner 组件 | `status-indicator.js:17/31/50/55`（`accent`/`warning` + `muted` 说明句） | pi 的转圈 + 说明句 | **不补**（`06 §5`「没有循环动画」，`12 §6.3` 已裁决保留 Stop 按钮） |
| 边框 loader | `bordered-loader.js:12/15/19`（`border` 框 + `accent` 转圈 + `muted` 文本） | 同上 | 不补 |
| 通用对话框边框 | `dynamic-border.js:11`（`border`） | 我们所有卡片/对话框描边走 `borderMuted` / 状态色 | 不补（形态不同） |
| 启动清单的段头 / 资源诊断 / 冲突提示 | `interactive-mode.js:1161/1164/1168/1219-1234/1247/1253/1302/1363/1369/1386/1392`（`accent`/`dim`/`mdLink`/`success`/`warning`/`error`/`mdHeading`） | pi 把加载到的 skills/prompts/extensions/主题冲突**打印进转录** | 我们把这些放在设置页（`SettingsHome` 等）。**不补**（产品已拆到设置） |
| `/session`、`/cost`、`/hotkeys`、What's New 等大段信息卡 | `interactive-mode.js:520/524/3541/5177/5191/5203-5244/5262/5385/5430`（`accent`/`dim`/`warning`/`muted`/bold） | pi 的斜杠命令输出 | 我们没有这些命令的信息卡。**不补** |
| Update Available / Package Updates 卡 | `interactive-mode.js:3527-3560`（`warning` 边框 ×2 + `accent` 动作 + `muted` 说明） | pi 的升级提示 | 不补 |
| 队列消息提示 `Steering:` / `Follow-up:` | `interactive-mode.js:3603/3607/3611`（`dim`） | pi 把排队消息画成 `dim` 行 | 我们有 `QueueChip`（`ChatScreen.kt:2539/2541`：`warning`/`muted`）。**形态不同**，见 §5.2 |
| `↓ Jump to latest message` 浮层 | `tui-renderer.js:14-18`（`selectedBg` + `text`） | 我们的下箭头 | **已排期**（§3-6） |
| 会话选择器 / 用户消息选择器（fork from message） | `session-selector.js:110-146/363-428/625-732`、`user-message-selector.js:26-93` | pi 的列表 UI | 我们有会话列表 + 会话树，**形态与访问路径不同**（`11` C 已定导航） |
| 模型/主题/思考等级/设置选择器 | `model-selector.js`、`theme-selector.js`、`thinking-selector.js:63`、`settings-selector.js:163-166`、`settings-submenu.js:28/32/54` | pi 的 `SelectList` 全家 | 我们有设置屏（见 C12）；思考等级在 `SettingsHome.kt:320` 用色阶 |
| 登录 / OAuth 对话框 | `login-dialog.js:35/78/81/84/96/99/101/110/125/127/150/154/167/175`、`oauth-selector.js:93` | pi 的终端登录流程 | 我们有 `ui/settings/PiCredentialScreen.kt`（`onSurface`/`onSurfaceVariant`），形态完全不同 |
| 首启向导（主题选择 + 遥测 opt-in） | `first-time-setup.js:32/34/37/38/43/44/60/61` | pi 的首启屏 | 我们有 `BootScreen`（见 D4）；**没有**遥测 opt-in 屏（产品没有遥测） |
| 彩蛋 / 公告 | `armin.js:2 处`、`daxnuts.js:124`（`mdLink`）、`earendil-announcement.js:30`（`mdLink`） | 装饰 | 不补 |
| 图片 fallback（工具返回的图） | `tool-execution.js:302-307`（`fallbackColor: toolOutput`） | pi 的工具图占位文字用 `toolOutput` | 我们 `ImageGridBlock.kt:254` 用 `metaOnCard` → P6 后 `dim`。与 pi 的 `toolOutput` **不同**（见 `12 §6.2` 的"不能机械替换"表最后一行）。**不确定**该用哪个（要看该格只服务工具图还是也服务用户图） |
| `[skill]` 分类的紧凑 read 头 | `read.js:74-81`（`customMessageLabel`/`customMessageText`/`accent` + `warning` 行号 + `dim` 提示） | pi 对 `SKILL.md`/`AGENTS.md`/`CLAUDE.md` 的 read 卡有专门的紧凑头 | 我们的 `ReadBlock` 走通用 `ToolHeader`。归 §3-1 的排期范围 |

### 5.2 我们有、pi 没有的元素（记录，**不算错**）

| 我们的元素 | file:line | 用的 token | 为什么不算错 |
|---|---|---|---|
| 工具卡状态描边 35% | `ToolBlockChrome.kt:550`、`:556` | `toolAccentColor@35%` | v2 `06 §2`「描边 1px 状态色 35%」；pi 只有底色 |
| 工具轨 ToolRail / 状态节点 | `ui/blocks/ToolRail.kt:156/174` | `borderMuted` / `pageBg` 挖孔 | v2 `06 §3` 构件 1；pi 无 |
| 3dp AccentStripe | `BlockChrome.kt` 的 `AccentStripe`（各卡调用点：`ErrorBlock.kt:57`、`HookMessageBlock.kt:55`、`SkillInvocationBlock.kt:76`、`BranchSummaryBlock.kt:62`、`ThinkingBlockBlock.kt:58`） | 状态色 / `customMessageLabel` / 思考色阶 | v2 `06 §2`；pi 无 |
| 工具卡页脚（状态字形 + 耗时 + DurationMeter） | `ToolBlockChrome.kt:433/441/447` | `toolAccentColor` / `text` | pi 的耗时是另一行 `muted`（`bash.js:95`），量级不同 |
| diff 卡的 `+N / −N` 计数 | `DiffBlock.kt:157/159` | `toolDiffAdded` / `toolDiffRemoved` | pi 的 diff **没有计数**；同 token，语义延伸 |
| 压缩卡的 chip + hairline 形态 | `CompactionBlock.kt:73/81/93` | `customMessageBg` / `borderMuted@50%` | v2 形态；见 D1（这一条同时是差异） |
| 会话列表「当前」徽标 | `SessionsScreen.kt:832-870` | `primary`（=`accent`） | `11` 已裁决「外观以 v2 为准」；见 D2 |
| 会话树「当前」标记 / 2dp 活动路径线 | `SessionTreeScreen.kt:770-779`、`:817-825` | `primary` / `outlineVariant` | `12 §6.4` 已裁决保留 |
| 「插话 / 排队」QueueChip | `ChatScreen.kt:2539/2541` | `warning` / `muted` | pi 的 `Steering:`/`Follow-up:` 是 `dim` 单行（`interactive-mode.js:3603/3607`），我们的是 chip |
| 上下文环 PiMeter（弧填充） | `PiCommon.kt:311-335`、`:358-361` | `muted` 轨 / `accent`·`warning`·`error` 填充 | pi 无环；三档阈值与 pi 一致 |
| 思考块的等级标签 + 色条 | `ThinkingBlockBlock.kt:41/58/80-81` | 思考色阶 | 见 D7（pi 的色阶只喂编辑器边框） |
| 通知条 / Boot 卡 / 工作区各类卡 | `NoticeBlock.kt`、`BootScreen.kt`、`ProjectScreen.kt`、`WorkspaceChrome.kt`、`WorkspaceViewer.kt`、`DeviceCapabilityScreen.kt` | `pageBg`/`cardBg`/`infoBg`/`errorContainer`(= `toolErrorBg`)/`surfaceContainer*`（派生） | pi 无这些屏；`infoBg`/`cardBg`/`pageBg` 是 export token（`12 §2.2` 已裁定用法正当） |
| M3 surface 阶梯（`surfaceContainer`/`High`/`Highest`/`Bright`/`Dim`） | `PiTheme.kt:390-399` + 30 个消费点 | **派生色（非 pi token）** | pi 只有 `pageBg`/`cardBg` 两档，手机需要中间调；`12 §2.2` 已记录为「不应算抢色」。但**严格 1:1 的读法下它们是调色板外的颜色**，见 §6-R5 |
| 图片网格块 / 查看器 / 缩略图 | `ImageGridBlock.kt:218/219/242/254/269`、`PiImageViewer.kt:130/156/237` | `cardBg`/`borderMuted@50%`/`muted`/`metaOnCard`/`text` | pi 的 TUI 不画用户附件图（`12 §1.5-A`）；用户消息图 pi **零面积** |
| 扩展状态行 | 已删除（`11` D-3 用户裁决） | — | 不要再当成漏画 |
| 主题导入/导出往返 | `PiThemeFiles.kt:118-134/527-592` | 59 token 全量 | pi 的主题 JSON 契约 |
| 扩展颜色清单 | `ChromeColor.kt:80-101` | 59 token | pi 的扩展只拿到 SGR 字节 |

---

## 6. 需要用户 / 父代理裁决的

### R1（D1）压缩卡：整卡底色 vs v2 的 chip 形态
- **冲突**：pi 把压缩卡画成满宽 `customMessageBg` 卡片（`compaction-summary-message.js:13`）；v2 把它画成「上下 hairline + 中间一枚 chip」（`CompactionBlock.kt:69-103`）。这不是"取色错"，是**着色范围差 5.6pp**（`12 §4.4`）。
- **三个选项**：①**改实现**（照 pi 换成整卡 `customMessageBg`，与 `BranchSummaryBlock`/`HookMessageBlock` 统一，观感变重）；②**改稿子**（把板子的 hairline 形态改成卡片，等于承认 v2 这一处该跟 pi）；③**不改**（保留 v2，本行记为刻意差异，并在 `docs/pi-android-ui-spec.md` 写明）。
- **我的倾向**：①。理由：同族的 `BranchSummaryBlock.kt:51` 与 `HookMessageBlock.kt:43` **已经是**满卡 `customMessageBg`，只有压缩卡不是——这首先是**我们自己的两张自定义卡不一致**，其次才是与 pi 不一致。

### R2（D3）错误卡的句子：`text` vs `error`
- **冲突**：pi 把每一条错误句子都染 `error`（`§2.1 D3` 列的 7 处：`assistant-message.js:140/148/153`、`edit.js:67`、`write.js:122`、`interactive-mode.js:2232/3522`）；v2 的错误块把句子画成正文色（只有 `✗`/「出错了」/「详情」是错误色）。
- **三个选项**：①**改实现**（句子 → `error`，整句变红，面积约一张卡的一行）；②**改稿子**；③**不改**。
- **我的倾向**：①。理由：pi 侧**没有**任何一处把错误文字画成正文色，而"错误句是错误色"是用户最终会对照的那一格。

### R3（D4）Boot 品牌行：`accent` vs `text`/`muted`
- **冲突**：pi 的等效屏（首启向导）把 logo + 欢迎句染 `accent`（`first-time-setup.js:32/34`）；我们的 `BootScreen` 是 v2 自己的品牌稿，用 `text`/`muted`。
- **选项**：①改实现（标题 → `accent` + bold）；②改稿子；③不改（记为"屏不同、无可对"）。
- **我的倾向**：③。理由：pi 的那一屏是首启向导，我们的 BootScreen 是启动/安装进度屏，两者不是同一块 UI；`11` 的规则「颜色听 pi」针对的是**同一个构件**。

### R4（P6 连带）`StateTone.Rejected` 在撤掉 `bodyOnTool` 之后用什么
- 已在 `12 §6.2` 列出（选项 A `toolOutput` / 选项 B `muted`）。这里只提醒：`PiStateChip.kt:120` 是**唯一**一处被 `07` D2 单独裁过的 token，撤 token 时不能顺手改，需要一句话追加账本。

### R5（§5.2）M3 surface 阶梯（5 个派生槽位、约 30 个消费点）
- **问题**：`surfaceContainer`/`High`/`Highest`/`Bright`/`Dim` 是 `cardBg` 向 `text` 混合出来的颜色，**pi 调色板里没有**。用户的原话是「能和 pi 一模一样的，全都一模一样」。严格读法下，这些是**我们新造的颜色**。
- **选项**：①**改实现**——把所有 `surfaceContainerHigh/Highest` 收敛到 `cardBg`/`pageBg` 两档（观感会塌，卡片与页面失去层次）；②**改稿子**——把板子的三档底改成两档；③**不改**——在 `docs/pi-android-ui-spec.md` 里把"派生槽位"写成正式例外（`12 §2.2` 目前只是"不算抢色"，没有写成规则）。
- **我的倾向**：③，但**必须落成文字**。理由：pi 是终端，它**不能**表达中间调；手机能，而 `06 §2` 的面板本身就用 `surf-low`/`surf-high` 两档（= `cardBg` + 一档派生）。这条是"终端做不到、手机可以"的正常差异，但它现在**没有账本**。

### R6（D10）扩展对话框标题：`accent` + bold vs `text`
- **冲突**：pi 的主机给对话框标题上 `accent` + bold（`extension-selector.js:29` 等三处）；我们的标题走"扩展给什么色就用什么色，没给就 `text`"。
- **选项**：①改实现（`defaultColor` → `accent` + bold，只在扩展没给色时生效）；②改稿子（`11` D-5 已定"与设置对话框共用同一构件"，改标题色等于让扩展对话框与设置对话框的标题不同色）；③不改。
- **我的倾向**：③。理由：①会让**扩展对话框**与**设置对话框**（同一构件）标题不同色，破坏 `11` D-5 的合并；而 pi 的两者本来就是两种壳。

### R7（D22）引用块的 italic
- pi 的引用文字是 `mdQuote` + italic（`pi-tui markdown.js:417`），我们只有 `mdQuote`（`PiMarkdownTheme.kt:285`）。
- **建议直接改**：在 `:285` 的 `quote` 上加 `fontStyle = FontStyle.Italic`。已核实库的竖条只取该 style 的**颜色**（`MarkdownBlockQuote.kt:34-38`），所以这条改动只影响文字，竖条不动——`mdQuoteBorder`/`mdQuote` 的那条已知折衷（`PiMarkdownTheme.kt:101-110`）也不受影响。
- 与板子的关系：**需要确认 v2 的引用是不是斜体**。若板子画的是正体，这条就是「改实现 vs 改稿子 vs 不改」的选择；若板子本来就是斜体（或没画引用），直接改。
- **表头的 bold 已核实为一致**（库 `MarkdownTable.kt:154`），不再是不确定项。

### R8（D11b）信任提示的选项标签：`success`/`error` vs pi 的 `accent`/`text`
- **冲突**：pi 的 trust selector 把**选中的那一项**染 `accent`（未选中 `text`），信任与否靠文字说（`trust-selector.js:64-66`）；我们把「信任 / 不信任」两行分别染绿 / 红（`PiProjectTrustPrompt.kt:99`）。
- **选项**：①改实现（标签 → 选中 `accent` / 未选中 `text`）；②改稿子；③不改（记为刻意的语义强化——绿/红比 accent 更直接，且这是一个**一次性安全决策**，不是列表浏览）。
- **我的倾向**：③。理由：这里 `success`/`error` 的语义（可信任 / 不信任）比 pi 的「光标在哪」更能表达这个对话框要问的问题，而 pi 的场景是键盘列表、我们的场景是两枚按钮。

---

## 附录 A · pi 46 个文件的上色调用点数量（覆盖面证明）

```
interactive-mode.js 85 | tree-selector.js 33 | theme/theme.js 24 | extensions/llama/ui.js 22(示例)
session-selector.js 19 | model-selector.js 16 | config-selector.js 15 | login-dialog.js 14
scoped-models-selector.js 13 | core/tools/renderers/read.js 12 | oauth-selector.js 11
components/bash-execution.js 11 | components/tool-execution.js 10 | first-time-setup.js 8
core/tools/renderers/{grep,bash}.js 8 | components/trust-selector.js 7 | footer.js 7 | diff.js 7
core/tools/renderers/{find,edit}.js 7 | {skill-invocation,compaction-summary,branch-summary,mermaid,
earendil-announcement}-message.js 6 | user-message-selector.js 5 | assistant-message.js 5
core/tools/renderers/{write,ls}.js 5 | components/status-indicator.js 4 | settings-selector.js 4
extension-selector.js 4 | settings-submenu.js 3 | custom-message.js 3 | bordered-loader.js 3
core/tools/render-utils.js 3 | tui-renderer.js 2 | user-message.js 2 | keybinding-hints.js 2
extension-input.js 2 | custom-entry.js 2 | armin.js 2 | thinking-selector.js 1
extension-editor.js 1 | dynamic-border.js 1
```

**未在上表逐个列出的文件**：
- `daxnuts.js`（5 处，局部别名 `t.fg`：`accent`/`success`/`muted`/`dim`/`mdLink`）——彩蛋，归 §5.1。
- **完全没有任何 `fg`/`bg` 调用的组件**（因此不在本次清单里，**不是漏扫**）：`countdown-timer.js`、`custom-editor.js`、`visual-truncate.js`、`markdown-transform.js`、`session-selector-search.js`、`theme-selector.js`、`show-images-selector.js`。它们要么纯转发、要么由库自己取色。
- **颜色发生在别处**的三类：`countdown-timer.js`/`custom-editor.js` 只把回调或 `borderColor` 转交给别人（真正的 token 在 `theme.js:976-989` 的映射表里）；`dynamic-border.js:11` 的 `border` 由构造参数给（默认 `border`）；`pi-tui` 的 `bgFn`/`fallbackColor`（`box.js`、`components/image.js:78`）由调用方传入。

## 附录 B · 本清单里「可以直接改、零观感风险」的 8 处

| 处 | file:line | 改法 |
|---|---|---|
| C1 | `ui/blocks/HookMessageBlock.kt:75` | 加 `textColor = palette.customMessageText` |
| C2 | `ui/blocks/CompactionBlock.kt:126` | 加 `textColor = palette.customMessageText` |
| C3 | `ui/blocks/BranchSummaryBlock.kt:114` | 加 `textColor = palette.customMessageText` |
| C4 | `ui/chat/BashPanel.kt:94` | `palette.toolOutput` → `palette.muted` |
| C5 | `ui/blocks/SkillInvocationBlock.kt:88` | `palette.text` → `palette.customMessageText` |
| C6 | `ui/blocks/GrepBlock.kt:205` | 去掉 `context` 分支，全部 `toolOutput` |
| R7a | `ui/render/PiMarkdownTheme.kt:285` | `quote` 加 `fontStyle = FontStyle.Italic` |
| D8 | `ui/blocks/NoticeBlock.kt:59` | `Notice.Tone.Info` → `palette.dim` |

**「零观感风险」的含义**：C1–C6 与 R7a 在 `dark`/`light` 两套内置主题下**像素完全相同**（两 token 同值），只有导入自定义主题才生效；D8 在深色下会从 `#808080` 变到 `#666666`（可见但面积小）。

## 附录 C · 我不确定的两处（明确标注）

1. **`ImageGridBlock.kt:254` 的图片占位标签**该用 `toolOutput`（pi 的工具图 fallback，`tool-execution.js:307`）还是 `dim`（`12 §6.2` 给的映射）：取决于该格是否也被**用户附件**复用——用户附件的图在 pi 侧零面积，无可对。**不确定**。
2. **pi 助手正文的"无色"是否等价于我们的 `palette.text`**：pi 的正文 markdown 不传 `defaultTextStyle.color`（`assistant-message.js:85`），所以是**终端默认前景**；我们的 `PiMarkdownTheme.kt:265-266` 显式用 `palette.text`。在 pi 的常规配置下两者同值，但我**没有验证** pi 是否会通过 OSC 10 把终端默认前景设成主题的 `text`（`theme.js:471` 的 `getThemeForRgbColor` 是反方向）。标为**不确定**，判定暂记 `一致`。

**本轮已经消掉的不确定项**（后续核实结果）：
- ~~markdown 表头是否 bold~~ → **一致**：库 `multiplatform-markdown-renderer 0.45.0` 的 `MarkdownTable.kt:154` 是 `style.copy(fontWeight = FontWeight.Bold)`，与 pi 的 `markdown.js:775` 相同。
- ~~引用竖条是否另用 `mdQuoteBorder`~~ → 我们的竖条确实用 `mdQuote`，但 `PiMarkdownTheme.kt:101-110` **已经自己记录了**这条折衷；两个内置主题同值，只有手写主题才暴露（列为 D22b）。
- ~~链接的 ` (href)`（`mdLinkUrl`）~~ → pi 只在**不支持 OSC 8** 的终端才印；我们选了另一条分支（列为 D22c）。
