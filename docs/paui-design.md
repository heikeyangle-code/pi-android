# PAUI：把 pi 在 RPC 模式下缺的 UI 能力接回来，并给 App 一套自有 UI 扩展协议

状态：设计 + 三个已跑通的验证实验。**App 侧没有任何实现，没编译、没上机跑过。**
权威是 pi 1.5.0 的源码（`@earendil-works/pi-coding-agent` dist，行为用真实进程验证过）。
实验脚本在 `tools/paui-experiments/`，复现方式见 §4。

## 0. 结论先行

1. **不需要改 pi，也不需要 fork。** pi 把 `ExtensionRunner` 作为公开类导出，扩展又能 `import` 它；
   一个随 App 发布的"桥接扩展"在加载时给 `ExtensionRunner.prototype.setUIContext` 套一层，就能
   **在 pi 包装之前把宿主 UI 上下文整体换成我们自己的完整实现**，并拿到 runner 实例去调用别的扩展注册的
   消息/条目渲染器。（实验 3 实测通过。）
2. **上行通道已经有：扩展斜杠命令。** `AgentSession.prompt()` 对 `/xxx` 先找扩展命令并**立即执行，
   流式输出期间也一样**，不经过 LLM、不排队（`agent-session.js` 的 `prompt`）。App 发
   `{"type":"prompt","message":"/paui <json>"}`，扩展里 `registerCommand("paui", …)` 接收。
3. **下行通道：`setStatus(key, text)`。** 它是 pi 已有的 `extension_ui_request`，本来 App 就不渲染它，
   text 可带任意长度的 JSON（实验 2 带了整屏渲染结果）。语义天然是"按 key 覆盖，空值清除"，正好是
   surface 的"最新状态"。`appendEntry` 只用于需要进会话文件的持久内容。
4. **路线：A（声明式组件树）为主，B（ANSI 兜底）做适配器输出，C（WebView）默认关闭。**
   第三方 TUI 扩展由适配器在 guest 里**无头运行真实 pi-tui 组件**，把 `render(width)` 的 ANSI 行
   翻译成 PAUI 的 `ansi` 节点；识别得出的常见组件（SelectList、SettingsList、Input、Markdown、Text）
   翻译成原生节点。自己写的扩展直接输出 PAUI 节点。
5. **要产品拍板的、以及我没法在这里验证的，集中在 §5。** 最大的一条：App 实际用的是打包过的引擎入口，
   `ExtensionRunner` 在那里是否和扩展 `import` 到的是同一个模块实例，必须上机验。

## 1. 16 项能力对照表

"TUI 语义"来自 `modes/interactive/interactive-mode.js`；"RPC 现状"来自 `modes/rpc/rpc-mode.js`。
路径记号：**B** = 桥接扩展替换 UI 上下文；**R** = 桥接扩展抓 runner；**A** = App 原生实现；**×** = 不做并显式报错。

| # | 能力 | TUI 里的真实语义（调用方/时机） | RPC 现状 | 接回需要什么 | 路径 | 风险 |
|---|------|------|------|------|------|------|
| 1 | `custom(factory, opts)` | `showExtensionCustom`：`factory(tui, theme, keybindings, done)` 返回组件；`overlay:true` 走 `tui.showOverlay`，否则占用输入框位置（`pushExtensionDialog`）；`done(result)` 关闭并 resolve | 立刻 resolve `undefined` | 虚拟 TUI 宿主 + 一个 surface（弹层/整屏）+ 按键/点击上行 + 关闭时 resolve | B | 中。要自己实现 `tui` 的子集（`requestRender`、`terminal.columns/rows`、`showOverlay`）。依赖键盘的组件靠 §2.4 的触屏输入模型 |
| 2 | `setWidget(key, factory, {placement})` | `setExtensionWidget`：组件放进输入框上/下方的 widget 容器；字符串数组限 `MAX_WIDGET_LINES` | 只处理字符串数组（已在 App 渲染）；工厂被丢弃 | 同 #1，surface 类型 `widget` | B | 低。每次 `requestRender` 才重新发送，要节流 |
| 3 | `setFooter(factory)` | 替换底部状态栏；工厂拿到 `footerDataProvider`（git 分支、扩展状态、可用 provider 数） | no-op | 一个 `footer` 槽 + 自己供 `footerDataProvider` | B + A | 中。`footerDataProvider` 是 pi 内部类型，要按实际用到的字段造一个替身；移动端的"底栏"放哪是产品问题 |
| 4 | `setHeader(factory)` | 替换启动时的头部 banner | no-op | `header` 槽（聊天顶部可折叠横幅） | B | 低 |
| 5 | `setEditorComponent(factory)` | 整个替换输入框（vim 模式之类），工厂拿 `(tui, editorTheme, keybindings)`，pi 再接线 `onSubmit/onChange` 等 | no-op | **按已定边界不替换输入框。** | × | 显式报错 `unsupported_capability`；输入框上方提供工具栏槽（PAUI `toolbar` surface）作为替代 |
| 6 | `getEditorComponent()` | 返回当前注册的工厂 | 恒 `undefined` | 桥接记下 #5 收到的工厂并原样返回 | B | 无 |
| 7 | `addAutocompleteProvider(factory)` | 工厂包装当前 provider（装饰器链），编辑器里 Tab/输入时取候选 | no-op | guest 里构造 provider 链（基础是 pi-tui 的 `CombinedAutocompleteProvider`），App 输入时发 `complete{text,cursor}`，回候选，原生弹出 | B + A | 中。补全是请求/应答，要防抖和取消；候选要能在输入框原生展示 |
| 8 | `onTerminalInput(handler)` | 订阅原始按键，handler 可吞掉输入 | 返回空取消函数 | 软键盘没有"原始按键流"。只在有 surface 获得焦点、或接了物理键盘时转发 | B + A | 高。**部分支持**：能用的只有"surface 聚焦时的按键"，全局热键类扩展永远用不了，必须在适配结果里写明 |
| 9 | `getEditorText()` | 同步返回输入框文字 | 恒 `""` | 桥接和扩展在同一进程，App 把输入框文字防抖（≈150ms）经 `/paui editor_text` 上报，桥接缓存后同步返回 | B | 中。有 ≤150ms 的陈旧；这是同步 API 的天花板，文档里要写 |
| 10 | `getAllThemes()` | 列出可用主题 | `[]` | 桥接读 agentDir 的 `themes/` 目录 + 内置名；或让 App 回答 | B | 低。pi 没导出 `getAvailableThemesWithPaths`，不要依赖内部路径 |
| 11 | `setTheme(t)` | 切换主题并写 settings | `{success:false}` | 写 pi 设置的 `theme`（App 已有主题设置页）并通知 App 重载配色 | B + A | 低。返回值要诚实：写成功才 `success:true` |
| 12 | `setWorkingMessage(msg)` | 代理忙碌时的 loader 文案 | no-op | `setStatus("paui/working", …)`，App 的"思考/工作中"行显示该文案 | B + A | 低 |
| 13 | `setWorkingVisible(b)` | 显示/隐藏 loader | no-op | 同 #12，字段 `visible` | B + A | 低 |
| 14 | `setWorkingIndicator(opts)` | 自定义 loader 动画帧/间隔 | no-op | 同 #12，字段 `frames/intervalMs`；App 用自己的动画，帧是文字就按帧播 | B + A | 低。不追求逐像素一致 |
| 15 | `registerMessageRenderer(type, fn)` | 自定义消息（`sendMessage` 产生）在 transcript 里怎么画：`fn(message, {expanded}, theme)` 返回组件（`interactive-mode.js` `getMessageRenderer`） | 注册成功但没人调 | 桥接抓 runner → 对每条 `custom_message` 调 `runner.getMessageRenderer(type)` → 渲染成节点，随 entry 下发 | R | **中高。** 取决于 runner 抓取；调用时机是"消息出现 + 展开状态变化" |
| 16 | `registerEntryRenderer(type, fn)` | 同上，针对 `appendEntry` 的 `custom` 条目 | 同上 | 同 #15，`runner.getEntryRenderer` | R | 同上 |

**源码里还有、清单没写到的几项**（同样 no-op，接回方式与上面同类）：
`setHiddenThinkingLabel`（改"思考已隐藏"文案 → 状态字段）、`getTheme(name)`、`getToolsExpanded/setToolsExpanded`
（对应 App 的工具卡展开状态，可读写）、`pasteToEditor`（现在退化成 `setEditorText`，丢了粘贴语义，App 里补一个"粘贴"事件）、
`registerToolRenderer`（工具调用怎么画；同 #15 路径 R，**这个对第三方工具扩展价值最大**）、
`registerMarkdownTransformer`（用户/助手 Markdown 渲染前改写；在 guest 里对 transcript 文本调一次即可）。

### 对第三方扩展的兼容影响

| 类别 | 今天（RPC 现状） | 接回后 | 永远用不了 |
|---|---|---|---|
| 只用 `select/confirm/input/editor/notify/setStatus/setWidget(数组)` | 能用 | 能用 | — |
| `custom()` 弹层/向导/选择器类 | 静默取消（`ctx.hasUI` 为 true，所以扩展走"取消分支"，没有任何报错） | 能用（surface） | 依赖鼠标/`MouseRegion`、alternate screen、游戏类 |
| `setWidget(工厂)` / `setHeader` / `setFooter` | 丢弃 | 能用 | — |
| 自定义消息/条目/工具渲染器 | 注册了但没人画，transcript 里只有原始内容 | 能用（路径 R） | — |
| `setEditorComponent`（vim/emacs 模式等） | 无效 | 显式报错 | 永远不支持（边界已定） |
| `onTerminalInput` 全局热键 | 无效 | 仅 surface 聚焦时 | 全局热键 |
| 其余只读 API（theme、getEditorText…） | 返回空值 | 返回真值（有陈旧窗口） | — |

## 2. UI 协议（PAUI）

### 2.1 三条路线对比

| | A 声明式组件树 | B ANSI 兜底 | C WebView/HTML |
|---|---|---|---|
| 表达力 | 词表内的一切；词表外不能表达 | 终端能画的一切，但只是"字符画" | 最强 |
| 原生观感 | 最好（Compose 原生，跟随主题、无障碍、动态字号） | 差：等宽字体、折行由 guest 决定，宽度写死 | 差：和 App 观感割裂，字体/暗色要自己对齐 |
| 交互 | 按组件语义（点、滑、长按、输入法） | 只能"点一行 → 翻译成方向键+回车"这类启发式 | 完整（DOM） |
| 性能 | 好，增量 diff 可做 | 好（文本），但整屏重发 | 最重：WebView 启动与内存，列表里不可用 |
| 安全面 | 小：数据而不是代码，限额即可 | 小：只是文本（要剥掉终端控制序列，只留 SGR/OSC8 链接） | 大：脚本执行、网络、与 App 的桥 |
| 实现成本 | 高（词表 + 渲染器 + diff + 事件） | **低**（App 已有 `ExtensionSpans` 把 ANSI 前景色映射到主题 token，`ExtensionWidgetLines` 可复用） | 中（但安全评审成本高） |
| 长期维护 | 词表要版本化；协议稳定后很省 | 随 pi-tui 的输出细节漂移，但 SGR 是稳定的 | 持续的安全与兼容成本 |

**推荐：A 为协议本体，B 为适配器的兜底输出（同一协议里的 `ansi` 节点），C 仅作为显式声明的、独立 surface 的可选项且默认关闭。**
理由：第三方 TUI 扩展数量大且不会为 App 改写，必须有零适配的兜底（B）；自己写的扩展要精美的原生界面（A）；
C 的收益（复杂图表、富编辑器）被 App 已有的 Mermaid/SVG/公式原生渲染覆盖大半，而安全成本最高。

### 2.2 词表大小

**v1 必需（约 18 个）**：布局 `column / row / box / scroll / spacer / divider`；文本 `text`（带 `style` 令牌与行内样式 span）、
`markdown`、`code`、`ansi`（兜底）；交互 `button / input / toggle / select / list`（带选中、可搜索）；
反馈 `progress / badge / notice`；`image`（引用 guest 文件或 base64，受限额约束）。
**后补**：`table`、`tabs`、`chart`、`form`（校验）、`datetime`、`sheet`（半屏弹层）、`canvas`。
`ansi` 必须从 v1 就有，它是未知内容的降级落点。

文本样式只用**语义令牌**（`accent/muted/dim/success/warning/error/…`，与 pi 主题令牌同名，App 已有
`tokenColorFor` 映射），不传颜色值；这样主题切换、暗色、对比度全由 App 负责。

### 2.3 Surface（承载位）

| surface | 位置 | 对应 pi 能力 |
|---|---|---|
| `overlay` | 半屏/居中弹层，可取消 | `custom({overlay:true})` |
| `screen` | 整屏页，返回手势关闭 | `custom()`（占用输入框位置的那种） |
| `widget` | 输入框上/下方卡片 | `setWidget` |
| `header` / `footer` | 聊天顶部可折叠横幅 / 底栏 | `setHeader` / `setFooter` |
| `toolbar` | 输入框上方一行动作 | 替代 `setEditorComponent`（已定边界） |
| `inline` | 消息/条目/工具调用行内 | 三个 renderer |
| `banner` | 临时横幅 | 自有协议 |

系统 chrome（状态栏、导航、返回手势、权限弹窗、主题基色）**不是 surface**，协议里没有任何字段能碰它们。

### 2.4 输入与焦点（让"只读键盘"的 TUI 组件在触屏可用）

TUI 组件只有一个入口：`handleInput(data: string)`（键盘字节）。模型：

1. **surface 有焦点，焦点即该 surface 的根组件**（pi-tui 的 `Focusable` 概念）。App 不做逐组件焦点树。
2. **键盘语义层**：App 把触屏手势翻译成 pi-tui 的按键序列再发给组件——方向键、Enter、Esc、Tab、Backspace、
   文本输入（逐字符）、粘贴（括号粘贴 `\x1b[200~…\x1b[201~`）。按键映射表直接取 pi-tui 的 `keys`，不自造。
3. **A 路线的原生节点不走键盘**：点按钮就是 `event{id:"btn1",type:"press"}`，不经过按键翻译。
4. **ANSI 节点的触屏降级**：底部浮一排 ↑ ↓ ← → ⏎ Esc Tab 键 + 文本输入条；"点某一行"翻译成
   "上/下移动 Δ 行然后回车"——仅对识别为 SelectList/SettingsList 的节点启用，其他 ANSI 节点只有按键条。
5. 不支持的输入（鼠标、滚轮、修饰键组合）在**适配结果里显式标记**，而不是静默丢弃。

### 2.5 双向通道

| 方向 | 机制 | 说明 | 对升级的依赖 |
|---|---|---|---|
| 下行（guest→App） | `extension_ui_request` 的 `setStatus(statusKey="paui/<surface>/<id>", statusText=JSON)` | 覆盖语义；`undefined` 清除；有序，随 stdout | 只依赖 pi 已有的、且文档化的 RPC 事件 |
| 下行（需持久） | `appendEntry(customType, data)` → `entry_appended` | 进会话文件，随会话恢复；仅用于需要持久的内容 | 同上 |
| 上行（App→guest） | `prompt` 命令，`message="/paui <json>"` | 扩展命令立即执行、流式期间也行 | 依赖 pi 的"扩展命令优先"行为（源码注释写明是设计意图） |
| 阻塞式结果（`custom()` 的返回值） | 桥接内部用 Promise，`close` 事件上行时 resolve | 不占用 pi 的 pending 对话表 | 无 |

三种实现路径的"跟随上游升级成本"：

| 方案 | 做法 | 升级成本 | 脆弱点 |
|---|---|---|---|
| **桥接扩展（推荐）** | App 自带扩展，加载时包装 `ExtensionRunner.prototype.setUIContext` | **低到中**：只依赖 3 个名字 `ExtensionRunner`（公开导出）、`setUIContext`、`getMessageRenderer/getEntryRenderer`；每次 pi 升级跑一次契约检查（`tools/pi-contract.mjs` 增加这几个名字存在性断言） | 方法被改名/内联；打包后模块实例不同（§5） |
| 自建 SDK 宿主 | 自己的入口脚本，用 `createAgentSessionRuntime` + 自己的 uiContext，不再用 `runRpcMode` | 中到高：要自己维护一份 rpc-mode 的命令分发（≈650 行），每次升级对照合并 | 命令集漂移；失去 pi 的 RPC 修复 |
| 最小补丁/上游 PR | 改 pi 的 `rpc-mode.js` 或推 PR 让 RPC 宿主转发工厂 | 补丁：每次升级重打；PR：慢且结果不可控 | 违反"不 fork 内核"；可以并行做 PR，不作为依赖 |

### 2.6 版本与能力协商

握手：App 连上后发 `/paui hello {protocol:1, caps:[…], limits:{…}, viewport:{cols,…}}`，桥接回 `paui/hello`
`{protocol, caps, host}`，两边取交集。节点带 `type` 与可选 `fallback`（一个更简单的节点）；
**未知节点**渲染 `fallback`，没有 `fallback` 就渲染一行"这个界面需要更新版本的 App（节点 xxx）"并上报，
**不破坏整页**。未知字段忽略；未知 surface 整体拒绝并向扩展返回 `unsupported_capability` 错误
（`custom()` 以 reject 的形式抛给扩展，而不是返回 `undefined`）。

### 2.7 限额与安全

| 项 | 限额（初值，可调） |
|---|---|
| 树深度 | 24 |
| 单 surface 节点数 | 2000 |
| 单条下行消息 | 1 MiB |
| 下行频率 | 每 surface 每秒 ≤ 20 次，合并取最后一次 |
| 图片 | 单张 ≤ 2 MiB，总计 ≤ 8 MiB；只允许 guest 文件引用或 data URI，不加载网络图片 |
| `ansi` 节点 | 只保留 SGR 与 OSC 8 链接，其余控制序列丢弃（防光标/清屏/标题注入） |
| 链接 | 只允许 `http(s)`，点击走 App 的确认 |
| HTML/脚本 | v1 不支持。C 路线若开，独立 WebView、禁网络、禁 `file://`、消息桥只通 `/paui` 同款事件 |
| 超限 | 丢弃该次更新并给扩展一个可见的错误（`paui_limit_exceeded`），不静默截断 |

### 2.8 适配器：让第三方 TUI 扩展自动获得原生界面

流程：`custom(factory)` → 桥接构造虚拟 `tui`（实验 2 证明真实 pi-tui 组件可无头渲染）→ `factory(tui, theme, keybindings, done)`
→ 取 `component.render(width)` 的行 →
1. **组件识别**（鸭子类型，不用 `instanceof`，因为扩展经 jiti 加载，同一个类跨模块边界会不相等——pi 自己在
   `setCustomEditorComponent` 里也是这么做的）：认出 `SelectList / SettingsList / Input / Markdown / Text` 的，
   直接读它们的公开状态翻译成原生节点；
2. 认不出的，输出 `ansi` 节点（B 兜底）。

**版本脆弱性的控制**：适配器只依赖 pi-tui 的**公开导出**与 SGR 输出；每次 pi 升级，契约检查对一组"黄金组件"
（上面五个 + Container/VStack/HStack）渲染快照，输出变了就让 CI 红灯，而不是线上静默变形。

## 3. 推荐架构的数据流

```
              ┌────────────────────────── guest（proot Ubuntu）──────────────────────────┐
 App (Compose)│  pi --mode rpc                                                            │
  ┌────────┐  │   ├─ 桥接扩展 pi-android-paui（随 App 发布，第一个加载）                    │
  │ PAUI   │  │   │    · 加载时包装 ExtensionRunner.prototype.setUIContext：                 │
  │ 渲染器  │  │   │      richerUi = { ...rpcUi, custom, setWidget(工厂), setFooter, …}      │
  │ (A节点 │  │   │    · 抓 runner → 调 message/entry/tool renderer                         │
  │  +ansi)│  │   │    · 虚拟 tui + 真 pi-tui 组件无头渲染 + 适配器（识别/兜底）             │
  └──▲──┬──┘  │   │    · 状态：surface 表、editorText 缓存、pending custom() 的 Promise       │
     │  │     │   └─ 第三方扩展：调用 ctx.ui.*，拿到的就是 richerUi                        │
     │  │     └───────────────────────────────────────────────────────────────────────────┘
     │  │ 下行  stdout JSONL: extension_ui_request{setStatus, statusKey:"paui/…", statusText:JSON}
     │  │       entry_appended（持久内容）
     │  └─ 上行  stdin JSONL: prompt{message:"/paui {…}"}  →  桥接命令 handler（立即执行，流式期间亦然）
     └──────── 事件：press / input / select / key / close / complete / editor_text / resize
```

要点：
- 扩展无感：它调的仍是 `ctx.ui.custom(...)`，只是宿主换成了完整的实现。
- 自己写的扩展：`import { paui } from "…"` 的一个小库，把 PAUI 节点直接 `setStatus`，不经过 pi-tui。
- 现有 App 侧复用：`ExtensionDialogQueue`（同时多个请求的排序/取消）、`ExtensionSpans`（ANSI→主题令牌）、
  `TuiOnlyScan`（启发式扫描"这个扩展用了 TUI-only API"）——接回之后它的结论从"不支持"变成"已适配/部分适配"，
  并按 §1 的表给出每一项的状态。

## 4. 三个证伪实验

下面三个**已经在 pi 1.5.0 上跑过**，代码都在 `tools/paui-experiments/`：

```
cd tools/paui-experiments && PI=<pi-coding-agent 目录>
EXT=../ext.ts  CMD=/probe  node drv.mjs $PI     # 实验 1
EXT=../ext2.ts CMD=/render node drv.mjs $PI     # 实验 2
EXT=../ext3.ts CMD=/rr     node drv.mjs $PI     # 实验 3
```
（`drv.mjs` 以 `--mode rpc` 启动 pi，充当 App：应答 `extension_ui_request`，发 `prompt`。）

| # | 实验 | 证伪的命题 | 判据 | 结果 |
|---|---|---|---|---|
| 1 | 扩展在 `session_start` 里改写 `ctx.ui.custom`，再由斜杠命令触发；App 端应答 | "`ctx.ui` 是各扩展共享的同一个对象，改写会被其他扩展看见" | 命令里调 `c.ui.custom()` 得到改写后的返回值 | **通过**：返回 `patched:{"clicked":"ok"}`，且 `custom()` 请求经 `input` 往返 |
| 2 | 在 guest 里用真 pi-tui（`Container/Text/SelectList`）无头渲染，发方向键，再渲染；结果放进 `setStatus` | "TUI 组件能脱离终端渲染并接收按键；输出可被 App 消费" | 两次 `render(40)` 不同（选中项移动）且只含 SGR | **通过**：只有 `\x1b[38;5;Nm…\x1b[39m`，选中项从 Alpha 移到 Beta；`setStatus` 带整屏 JSON 无问题 |
| 3 | 扩展导入 `ExtensionRunner`，包装 `setUIContext`：抓 `this`、整体替换宿主 ui；再用 runner 调另一处注册的 message renderer | "不改 pi 就能抓到 runner、替换整个 UI 上下文、调用别人注册的渲染器" | `custom()` 返回我们的实现；`getMessageRenderer("demo")` 能渲染 | **通过**：`custom: "from-richer-host"`，渲染出 `RENDERED:hi`，`getAllThemes` 也被替换 |

**下一步要补的实验（每个 1–2 天，都能在 CI/真机以外大部分用 node 完成）：**

| # | 实验 | 证伪什么 | 判据 |
|---|---|---|---|
| 4 | **用打包后的引擎入口重跑实验 3**（App 实际使用的 `rpc-entry`） | "`ExtensionRunner` 在打包入口里和扩展 import 到的是同一个实例" | 实验 3 的 `haveRunner:true`；不通过则退回"自建 SDK 宿主"路线，设计其余部分不变 |
| 5 | **把一个真实页面用协议重画**：选 App 里现成的"工作区 → 资源"的一个列表，写成 PAUI 节点 JSON，由一个最小 Compose 渲染器画出（先只做 `column/row/text/button/list/toggle`） | "词表 v1 足够表达真实页面；事件回传延迟可接受" | 用不超过 18 个节点类型还原页面；点按到状态更新的往返 < 200 ms（真机，proot 内） |
| 6 | **翻译一个真实第三方 TUI 扩展**：挑 pi 生态里用 `custom()` 的向导/选择器（先找一个只用 SelectList + Text 的），走适配器 | "适配器能让不改源码的第三方扩展自动出界面" | 该扩展的整条流程（打开 → 选择 → done）在 `ansi` 兜底下走通；识别成 SelectList 后变成原生列表 |

## 5. 未解决的问题与需要产品拍板的点

**需要验证（我没法在这里验证的）**
1. **打包入口里的模块实例**（实验 4）。这是整个"桥接"路线的前提，最先验。不通过则走自建 SDK 宿主，成本见 §2.5。
2. 桥接扩展必须**第一个加载**：`setUIContext` 在 `bindExtensions` 时被调用，晚加载的扩展拿到的仍是旧对象。
   加载顺序由 pi 的发现顺序决定，要确认 App 的 `SHIPPED` 扩展确实排在用户扩展之前；若有扩展在自己的顶层代码
   里就把 `ctx.ui.custom` 解构缓存下来，会拿到旧实现（极少见，但要写进兼容说明）。
3. `ui_prompt_start/ui_prompt_end` 事件：pi 用它们告诉其他扩展"此刻有个 UI 提示在占用"。桥接的 `custom()`
   必须保持这个语义（实验 3 里通过"替换宿主 ui、让 pi 照常包装"天然保持了；若改成自建宿主要自己发）。
4. 真机上 `setStatus` JSON 的吞吐（大树、20 次/秒）对 proot 管道与 JSON 解析的压力，没测。

**需要你拍板**
1. **`setEditorComponent` 显式报错 vs 提供 `toolbar` 槽**：我建议两个都做（报错 + 工具栏），但工具栏是新的产品面，要不要做？
2. **`setFooter` 的移动端形态**：pi 的底栏装的是 git 分支/模型/token 这类信息；App 已有自己的顶栏与上下文指示。
   是让扩展底栏作为聊天底部的一条可折叠行，还是并入现有状态区？
3. **路线 C 开不开**：我的建议是 v1 不开。开的话需要单独的安全评审和一个"这个扩展要用网页界面"的授权弹窗。
4. **自写扩展的发布方式**：PAUI 小库随桥接扩展内置（扩展 `import` 即可），还是单独发包？
5. **兼容状态的呈现**：`TuiOnlyScan` 现在给出"扩展用了 TUI-only API"，接回后改成每项 ✅/部分/❌ 三态并解释原因——这条会改扩展列表的 UI，确认方向后做。
6. **权限**：surface 能不能抢焦点/弹在其他界面之上？我建议只有"用户刚发起的命令或工具调用期间"允许 `overlay/screen`，其余走 `banner` 通知，避免扩展随时劫持屏幕。

## 6. 同类实现（链接未逐条打开核对，按名称与我已知的设计给出）

- VS Code Webview API — https://code.visualstudio.com/api/extension-guides/webview：扩展 UI = 沙箱网页 + `postMessage`；表达力最强，代价是重、与宿主观感割裂（对应路线 C）。
- Figma 插件 UI — https://www.figma.com/plugin-docs/creating-ui/：插件逻辑在沙箱，UI 在 iframe，两边靠消息通信；证明"双进程 + 消息"可行，但 UI 是网页。
- Telegram Mini Apps — https://core.telegram.org/bots/webapps：宿主提供主题变量与主按钮等原生 chrome，内容是网页；"主题令牌 + 少量原生控件"的混合思路可借鉴。
- Shopify remote-ui / remote-dom — https://github.com/Shopify/remote-dom：沙箱里构建 UI 树，序列化成变更流发给宿主用原生组件渲染；与路线 A（组件树 + 变更流）最接近，值得参考其"只发变更、宿主白名单组件"的设计。
- React Server Components — https://react.dev/reference/rsc/server-components：服务端产出可序列化的组件描述，客户端渲染；说明"数据而非代码"的 UI 描述可以承载交互（客户端组件占位）。
- Slack Block Kit — https://api.slack.com/block-kit：有限词表的 JSON 块 + 事件回调；词表小、跨端一致，是"词表该多大"的好参照。
- Adaptive Cards — https://adaptivecards.io：带 `fallback` 与版本协商的声明式卡片；§2.6 的降级设计直接参考它。
- Flutter Remote Flutter Widgets（rfw）— https://pub.dev/packages/rfw：下发文本格式的 widget 描述，客户端用白名单组件渲染；原生观感 + 不重新发版的一个实例。
- Server-driven UI（Airbnb Ghost Platform、Lyft 等的公开分享）：同样是"后端下发组件树、客户端白名单渲染"，经验教训是**词表一旦发出就要向后兼容**，所以 §2.6 的能力协商不能省。
