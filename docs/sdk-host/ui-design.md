# 新增 UI 面的设计

宿主让扩展的 UI 调用都有了落点；这一页定义 App 怎么**原生地**画它们。决策已定，实现按此做，不再回头问。
现有组件：`ExtensionDialogs.kt`（对话框）、`ExtensionWidgetCard.kt`（widget 卡）、`ExtensionChrome.kt`、`ChatScreen.kt`。

## 0. 总原则

- 扩展能给的只有两种东西：**数据**（通知、状态、对话框、字符串行）和**终端组件**（只会吐带 ANSI 颜色的文本行）。
  数据一律原生控件；终端组件走「表面」，用等宽字体忠实呈现，不假装它是原生控件。
- 想要真正原生的扩展界面，走 PAUI（§7）：扩展发声明式节点树，App 用 Compose 画。表面是兜底，PAUI 是正路。
- 任何表面都必须有**明确的退出**：关闭按钮 = 先给组件发 `Esc`，组件没自己关就 `ui.close`。否则扩展里的 `await ctx.ui.custom(...)` 会永远挂着。

## 1. 表面（Surface）视图 `SurfaceView`

- 输入：`ui.surface` 事件（`lines[]` 含 ANSI，`rev`）。只画 `rev` 最大的一帧。
- 渲染：复用 `app.pi.rpc.Ansi` 解析（已有，含 24 位色 / 粗斜下划线 / 清理）；字体用内置 JetBrains Mono；
  不做光标寻址、不做滚动区域（pi-tui 的整屏程序在这里退化为「当前这一屏的行」）。
- 宽度：`列数 = floor(可用像素宽 / 单字符像素宽)`。布局变化（旋转、字号、折叠）后**防抖 100 ms** 发 `ui.resize{width,height}`。
  多个表面共用一个宽度（宿主存一份）；不同宽度的容器取最窄的。
- 交互：`interactive:true` 的表面显示**按键条**（§2）；`false` 的只读，不抢焦点。
- 错误：行里出现 `[render failed]` 前缀时，卡片边框变 error 色。

## 2. 按键条与输入

- 按键条：`↑ ↓ ← → ⏎ Esc Tab ␠ ^C`，每个键发 `ui.input{data}`：

  | 键 | data |
  |---|---|
  | ↑ ↓ → ← | `\x1b[A` `\x1b[B` `\x1b[C` `\x1b[D` |
  | Enter | `\r` |
  | Esc | `\x1b` |
  | Tab / Shift-Tab | `\t` / `\x1b[Z` |
  | Space | ` ` |
  | Ctrl-C | `\x03` |
  | Backspace | `\x7f` |

- 文字输入：按键条旁一个「⌨」打开系统输入法，输入的文本按**整串**发 `ui.input`（IME 组合期间不发，提交时发）。
- 触摸：表面里的列表行可点击时，**不**映射成鼠标事件（pi-tui 组件没有鼠标协议）；扩展想要触摸交互就用 PAUI。

## 3. 摆放（placement）

| 宿主事件 | 摆放 |
|---|---|
| `ui.widget{lines}` | 现有 widget 卡，不变 |
| `ui.widget{surfaceId}` | 同一张 widget 卡，卡体是 `SurfaceView`，默认折叠成 3 行预览，点开展开；interactive 的展开成底部面板 |
| `ui.chrome{slot:footer}` | 输入框**下方**一条细栏，最多 3 行，点击展开 |
| `ui.chrome{slot:header}` | 对话列表**顶部**一条细栏，同上 |
| `ui.chrome{slot:editor, supported:false}` | 输入框旁出现一枚「扩展想接管输入框」小标记，点开说明「手机端不支持终端编辑器，已降级」。**不**弹窗打扰 |
| `ui.surface{mode:"custom", overlay:true}` | 底部面板，高 85%，顶部右侧 ✕ |
| `ui.surface{mode:"custom", overlay:false}` | 全屏页，系统返回键 = Esc |

## 4. 对话框

- `req ui.select/confirm/input/editor` 走现有 `ExtensionDialogQueue`，按到达顺序排队。
- **`ui.cancel{id}`**：队列里对应的对话框立刻关闭（若正在显示，淡出，不弹「已超时」打扰）。这是旧协议没有的。
- `timeout` 参数：对话框标题行右侧显示倒计时圈；到点由宿主 `ui.cancel`，App 不自己计时（避免两边赛跑）。
- 会话被替换（`session.bound`）：队列里所有挂起的对话框作废（宿主已取消，会各发 `ui.cancel`）。

## 5. 项目信任弹窗（`project.trust.required`）

- 标题「信任这个项目吗？」，正文：路径 + 一句「项目里有需要信任才会加载的扩展 / 技能 / 配置。信任后会执行项目里的扩展代码。」
- 两个按钮：**信任** → `project.trust{trusted:true}`；**不信任** → `project.trust{trusted:false}`。都会写入 pi 的信任库并重载。
- 在用户表态前，宿主已按「不信任」启动，聊天可用；弹窗是非阻塞的横幅 + 点开详情，不挡输入。

## 6. 启动与错误状态

| 事件 | 状态条 |
|---|---|
| 进程起了，`host.ready` 未到 | 「引擎启动中」 |
| `host.ready` → `session.open` 应答 | 「加载 pi…」 |
| `session.open` 应答 → `ext.bound` | 「加载扩展…」（可聊天，扩展命令稍后才有） |
| `ext.bound.ok=false` | 非阻塞错误条「扩展加载失败」+ 展开看原文 |
| `host.fatal` / 进程退出 | 走现有 `EngineExitCause` 报告，把 `host.fatal.message` 附进去 |

`seq` 跳号：静默 `session.snapshot` 对账，**不**打扰用户；连续三次对账仍跳号才提示「连接不稳定」。

## 7. PAUI（声明式原生界面）接到总线上

旧设计里的下行通道是 `setStatus("paui/…")`（hack）。SDK 下换成扩展事件总线：

| 方向 | 通道 | 内容 |
|---|---|---|
| 扩展 → App | `pi.events.emit("paui:render", {id, tree})` / `("paui:close", {id})` | 节点树（`docs/paui-design.md`） |
| App → 扩展 | `bus.emit("paui:event", {id, nodeId, event, value})` | 用户操作 |

App 启动时 `bus.subscribe(["paui:render","paui:close"])`。节点树的编解码与渲染器已在仓库（`app/pi/paui/*`、`PauiView.kt`），
下一步只是把输入从「某个 widget 的 JSON 行」换成 `bus` 事件。**表面与 PAUI 不冲突**：同一个扩展可以先发表面兜底，再发 PAUI 覆盖。

## 8. 主题

App 启动与主题切换时发 `ui.palette`，键为语义名（`宿主 ui/theme.mjs` 的 `DEFAULT_PALETTE`：`text muted dim accent border success error warning mdHeading mdLink mdCode …`），
值取自 `PiPalette` 对应色。没映射到的名字宿主用默认暗色值。

## 9. 子代理（步骤 6 的设计，这里先定形状）

数据源是 pi-subagents 的进程内总线 RPC（`subagent.rpc`）和它的事件（`subagent:jobs-changed`、`asyncStarted`、`childStatus`、`asyncComplete`、`processTerminal`，
名字以 `subagent.rpc ping` 返回的 `capabilities.events` 为准）。

- **入口**：聊天顶栏一枚「子代理 N」徽标（N = 运行中数），点开进列表页；widget 卡里的子代理行也能点进去。
- **列表页**：按「运行中 / 排队 / 已结束」分组；每行 = 名称 + 状态 + 耗时 + 最近活动一行。
- **详情页**（每个子代理一页）：顶部状态与停止按钮；中间**对话记录**（`subagent.rpc status` 带 `view/lines/index` 取，运行中随事件合并后 250 ms 一刷）；
  底部输入框 = 「给它发指令」（`steer`，模式 `steer`/`follow_up`/`auto` 做成输入框旁的小切换）；已停止的可「继续」（`resume`）。
- **合并刷新**：事件会成串到达且重复，**250 ms 内合并成一次 `status` 读取**，读取进行中又来事件则读完再读一次（pi-subagents 文档明确的建议）。
- 成本：详情页底部显示 `cost` 的该子代理用量。
